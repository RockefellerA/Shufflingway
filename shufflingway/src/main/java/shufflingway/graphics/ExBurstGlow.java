package shufflingway.graphics;

import shufflingway.UiScale;

import javax.swing.JComponent;
import javax.swing.Timer;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.Line2D;
import java.awt.geom.PathIterator;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;

/**
 * A comet of light running clockwise around the inside edge of one damage-zone slot, shown for as
 * long as that card's EX Burst is resolving.
 *
 * <p>Overlays a single slot rather than the whole damage panel: the burst belongs to the one card
 * that was just flipped, and the glow is what says which one. {@link #start(int)} names the slot,
 * {@link #stop()} takes it away; both cross-fade, so a burst that turns out to have nothing to
 * resolve blinks rather than snapping.
 *
 * <p>EX Bursts bypass the stack, so only the player resolving one ever sees this — there is no
 * window for the opponent to respond in and nothing for them to read off it.
 */
public class ExBurstGlow extends JComponent {

	/** Seconds the head takes to travel the perimeter once. */
	private static final double REVOLUTION_SEC = 1.8;
	/** How much of the perimeter the trail behind the head covers. */
	private static final double TRAIL_FRAC = 0.36;
	/** Segments the perimeter is cut into; the trail is drawn from those that fall inside it. */
	private static final int SEGMENTS = 120;
	private static final int FADE_MS = 190;
	private static final int FRAME_MS = 16;

	private static final Color HALO = new Color(255, 178, 36);
	private static final Color MID  = new Color(255, 216, 104);
	private static final Color CORE = new Color(255, 242, 190);

	private static final float HALO_W = UiScale.scale(11f);
	private static final float MID_W  = UiScale.scale(6f);
	private static final float CORE_W = UiScale.scale(2.5f);
	private static final float CORNER = UiScale.scale(6f);

	/** Slot this is currently drawn over, or -1 when idle.  Read by the damage panel's layout. */
	private int slot = -1;
	private boolean fadingOut = false;
	private float opacity = 0f;

	private Timer timer;
	private long lastFrameNs;
	/** Head position along the perimeter, 0..1 clockwise from the top-left corner. */
	private double head = 0;

	/** Equally spaced perimeter points, cached against the size they were built for. */
	private float[][] ring;
	private int ringW = -1, ringH = -1;

	public ExBurstGlow() {
		setOpaque(false);
	}

	// ---- Public API ----------------------------------------------------------

	/** Arms the glow over damage slot {@code slotIndex} (0-6) and fades it in. */
	public void start(int slotIndex) {
		if (slotIndex < 0) return;
		if (slot != slotIndex) { slot = slotIndex; head = 0; }
		fadingOut = false;
		lastFrameNs = System.nanoTime();
		if (timer == null) {
			timer = new Timer(FRAME_MS, e -> step());
			timer.start();
		}
		// The slot is only known to the layout once it is set, so ask for one now.
		revalidate();
		repaint();
	}

	/** Fades the glow out; the slot is released once it has gone. */
	public void stop() {
		if (slot < 0) return;
		fadingOut = true;
	}

	/** The damage slot to sit over, or -1 when the glow is idle and wants no bounds. */
	public int slotIndex() { return slot; }

	// ---- Animation -----------------------------------------------------------

	private void step() {
		long now = System.nanoTime();
		double dt = (now - lastFrameNs) / 1_000_000_000.0;
		lastFrameNs = now;
		// A stalled EDT (a card image decode, a dialog opening) can hand us an arbitrarily long
		// frame; clamping keeps the head from jumping most of the way round in one step.
		dt = Math.min(dt, 0.05);

		head = (head + dt / REVOLUTION_SEC) % 1.0;

		float delta = (float) (dt * 1000.0 / FADE_MS);
		opacity = fadingOut ? Math.max(0f, opacity - delta) : Math.min(1f, opacity + delta);
		if (fadingOut && opacity <= 0f) {
			timer.stop();
			timer = null;
			slot = -1;
			fadingOut = false;
			revalidate();
		}
		repaint();
	}

	// ---- Rendering -----------------------------------------------------------

	@Override
	protected void paintComponent(Graphics g0) {
		int w = getWidth(), h = getHeight();
		if (opacity <= 0f || w <= 0 || h <= 0) return;

		float inset = HALO_W / 2f + 1f;
		if (w <= inset * 2 || h <= inset * 2) return;

		Shape path = new RoundRectangle2D.Float(inset, inset, w - inset * 2, h - inset * 2,
				CORNER, CORNER);
		float[][] pts = ring(path, w, h);

		Graphics2D g = (Graphics2D) g0.create();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

		// A slow breath under the comet, so a slot whose head is on the far side still reads as lit.
		double breath = 0.85 + 0.15 * Math.sin(head * 2 * Math.PI * 2);
		drawPass(g, path, pts, HALO, HALO_W, 0.16f, 0.07f * (float) breath);
		drawPass(g, path, pts, MID,  MID_W,  0.26f, 0.05f * (float) breath);
		drawPass(g, path, pts, CORE, CORE_W, 0.52f, 0.03f * (float) breath);

		g.dispose();
	}

	/**
	 * Strokes one layer of the comet: a faint ring the whole way round at {@code baseAlpha}, then
	 * the trail on top of it, brightest at the head and falling away behind.
	 */
	private void drawPass(Graphics2D g, Shape path, float[][] pts,
			Color color, float width, float peakAlpha, float baseAlpha) {
		g.setStroke(new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

		g.setColor(alpha(color, baseAlpha * opacity));
		g.draw(path);

		for (int i = 0; i < SEGMENTS; i++) {
			// Distance back along the perimeter from the head to this segment, wrapped into 0..1.
			double behind = (head - i / (double) SEGMENTS + 1.0) % 1.0;
			if (behind > TRAIL_FRAC) continue;
			double falloff = 1.0 - behind / TRAIL_FRAC;
			float a = (float) (peakAlpha * falloff * falloff * opacity);
			if (a < 0.015f) continue;
			g.setColor(alpha(color, a));
			float[] p = pts[i], q = pts[i + 1];
			g.draw(new Line2D.Float(p[0], p[1], q[0], q[1]));
		}
	}

	private static Color alpha(Color c, float a) {
		return new Color(c.getRed(), c.getGreen(), c.getBlue(),
				Math.round(Math.max(0f, Math.min(1f, a)) * 255f));
	}

	// ---- Perimeter geometry --------------------------------------------------

	/** {@link #SEGMENTS}+1 equally spaced points round {@code path}, the last repeating the first. */
	private float[][] ring(Shape path, int w, int h) {
		if (ring != null && ringW == w && ringH == h) return ring;
		ring = resample(path, SEGMENTS);
		ringW = w;
		ringH = h;
		return ring;
	}

	private static float[][] resample(Shape path, int n) {
		List<double[]> flat = new ArrayList<>();
		double[] seg = new double[6];
		for (PathIterator it = path.getPathIterator(null, 0.25); !it.isDone(); it.next()) {
			int type = it.currentSegment(seg);
			if (type == PathIterator.SEG_MOVETO || type == PathIterator.SEG_LINETO)
				flat.add(new double[]{ seg[0], seg[1] });
		}
		if (flat.size() < 2) return new float[n + 1][2];
		// Shoelace: with y running down the screen, a positive area means the points already run
		// clockwise.  RoundRectangle2D does, but deriving it beats asserting it.
		double area = 0;
		for (int i = 0; i < flat.size(); i++) {
			double[] a = flat.get(i), b = flat.get((i + 1) % flat.size());
			area += a[0] * b[1] - b[0] * a[1];
		}
		if (area < 0) java.util.Collections.reverse(flat);

		flat.add(flat.get(0).clone());   // close the loop so the walk below can cross the seam
		double[] cum = new double[flat.size()];
		for (int i = 1; i < flat.size(); i++) {
			double[] a = flat.get(i - 1), b = flat.get(i);
			cum[i] = cum[i - 1] + Math.hypot(b[0] - a[0], b[1] - a[1]);
		}
		double total = cum[cum.length - 1];

		float[][] out = new float[n + 1][2];
		int cursor = 1;
		for (int k = 0; k <= n; k++) {
			double target = total * k / n;
			while (cursor < cum.length - 1 && cum[cursor] < target) cursor++;
			double span = cum[cursor] - cum[cursor - 1];
			double t = span == 0 ? 0 : (target - cum[cursor - 1]) / span;
			double[] a = flat.get(cursor - 1), b = flat.get(cursor);
			out[k][0] = (float) (a[0] + (b[0] - a[0]) * t);
			out[k][1] = (float) (a[1] + (b[1] - a[1]) * t);
		}
		return out;
	}
}
