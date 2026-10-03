package shufflingway.graphics;

import shufflingway.UiScale;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * Transparent overlay that plays a Limit Break card's arrival on the field:
 *   1. The card lands in its slot slightly oversized and washed in gold, a golden burst flaring
 *      out from behind it.
 *   2. It shakes, the shake dying away as the gold drains out of the card.
 *   3. Gold sparks fly outward and fade, and the card settles exactly on its slot render.
 *
 * <p>The card is drawn here for the whole animation, so the slot underneath must be blank while it
 * plays — {@code FieldEntryAnimator} holds it blank and draws the card into it on the last frame.
 */
public class CardLimitBreakAnimator extends JComponent {

	public static final int FRAME_MS     = 16;
	public static final int TOTAL_FRAMES = 40;   // 640 ms

	private static final int PUNCH_END   = 8;    // card shrinks from oversized to its slot size
	private static final int BURST_END   = 20;   // golden flare behind the card
	private static final int SHAKE_END   = 28;
	private static final int WASH_END    = 30;   // gold drains out of the card
	private static final int SPARK_START = 2;
	private static final int SPARK_COUNT = 12;

	private static final double PUNCH_SCALE  = 0.07;   // extra size on the first frame
	private static final double SHAKE_CYCLES = 4.5;
	private static final double SHAKE_TILT   = 0.03;   // radians at full shake
	private static final int    SHAKE_PX     = UiScale.scale(7);
	private static final float  WASH_ALPHA   = 0.85f;

	private static final Color WASH  = new Color(255, 222, 110);
	private static final Color[] SPARK = {
		new Color(255, 200, 40), new Color(255, 245, 200), new Color(255, 225, 110),
	};

	private static class Arrival {
		final BufferedImage img;
		/** {@code img}'s silhouette filled with {@link #WASH}, laid over it to gild the card. */
		final BufferedImage gold;
		/** Centre of the card's opaque pixels, which a slot render keeps off the canvas centre. */
		final Point center;
		/** Half the larger side of the card's opaque pixels: how far the burst and sparks reach. */
		final double radius;
		final Point imgOrigin;
		/** Everything any frame of this arrival can touch: the repaint region while it plays. */
		final Rectangle extent;
		int frame;

		Arrival(BufferedImage img, Point slotCenter) {
			this.img = img;
			this.gold = silhouette(img, WASH);
			Rectangle r = opaqueBounds(img);
			this.imgOrigin = new Point(slotCenter.x - img.getWidth() / 2, slotCenter.y - img.getHeight() / 2);
			this.center = new Point(imgOrigin.x + r.x + r.width / 2, imgOrigin.y + r.y + r.height / 2);
			this.radius = Math.max(r.width, r.height) / 2.0;
			// The burst reaches 1.8 radii; the sparks 0.8 + 0.9 radii plus an arm. The card itself
			// grows by the punch, the tilt and the shake — 10% of its longer side covers the first two.
			Rectangle halo = OverlayDirtyRegion.around(center.x, center.y,
					Math.max(radius * 1.8, radius * 1.7 + UiScale.scale(10)));
			int grow = (int) Math.ceil(Math.max(img.getWidth(), img.getHeight()) * 0.1) + SHAKE_PX
					+ OverlayDirtyRegion.PAD;
			this.extent = halo.union(new Rectangle(imgOrigin.x - grow, imgOrigin.y - grow,
					img.getWidth() + 2 * grow, img.getHeight() + 2 * grow));
		}
	}

	private final List<Arrival> arrivals = new ArrayList<>();
	private final Timer         timer;
	private final OverlayDirtyRegion dirty = new OverlayDirtyRegion();

	public CardLimitBreakAnimator() {
		setOpaque(false);
		setFocusable(false);
		timer = new Timer(FRAME_MS, e -> tick());
		timer.setCoalesce(true);
	}

	/** Installs the animator on {@code frame}'s layered pane at DRAG_LAYER. */
	public static CardLimitBreakAnimator install(JFrame frame) {
		CardLimitBreakAnimator a  = new CardLimitBreakAnimator();
		JLayeredPane           lp = frame.getRootPane().getLayeredPane();
		a.setBounds(0, 0, lp.getWidth(), lp.getHeight());
		lp.add(a, JLayeredPane.DRAG_LAYER);
		lp.addComponentListener(new ComponentAdapter() {
			@Override public void componentResized(ComponentEvent e) {
				a.setBounds(0, 0, lp.getWidth(), lp.getHeight());
			}
		});
		return a;
	}

	/**
	 * Plays the arrival of {@code img} — a slot render, already turned to the state the card
	 * entered in — centred on {@code center} in layered-pane coordinates.
	 */
	public void start(BufferedImage img, Point center) {
		arrivals.add(new Arrival(img, center));
		if (!timer.isRunning()) timer.start();
	}

	/** Never intercepts mouse events — the board components below stay active. */
	@Override
	public boolean contains(int x, int y) {
		return false;
	}

	private void tick() {
		arrivals.removeIf(a -> { a.frame++; return a.frame >= TOTAL_FRAMES; });
		if (arrivals.isEmpty()) timer.stop();
		Rectangle now = null;
		for (Arrival a : arrivals) now = OverlayDirtyRegion.union(now, a.extent);
		dirty.repaint(this, now);
	}

	@Override
	protected void paintComponent(Graphics g) {
		if (arrivals.isEmpty()) return;
		Graphics2D g2 = (Graphics2D) g.create();
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,  RenderingHints.VALUE_ANTIALIAS_ON);
		g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		for (Arrival a : arrivals) render(g2, a);
		g2.dispose();
	}

	private static void render(Graphics2D g2, Arrival a) {
		int f = a.frame;
		renderBurst(g2, a, f);
		renderCard(g2, a, f);
		renderSparks(g2, a, f);
	}

	/** Golden flare behind the card: bright at once, widening as it fades. */
	private static void renderBurst(Graphics2D g2, Arrival a, int f) {
		if (f >= BURST_END) return;
		double t      = (double) f / BURST_END;
		float  alpha  = (float) ((1.0 - t) * (1.0 - t) * 0.9);
		float  radius = (float) (a.radius * (1.05 + 0.75 * easeOut(t)));
		if (alpha < 0.01f) return;
		float[] fracs = { 0f, 0.55f, 1f };
		Color[] cols  = {
			new Color(1f, 0.95f, 0.70f, alpha),
			new Color(1f, 0.78f, 0.15f, alpha * 0.6f),
			new Color(1f, 0.78f, 0.15f, 0f),
		};
		Graphics2D bg = (Graphics2D) g2.create();
		bg.setPaint(new RadialGradientPaint(a.center.x, a.center.y, radius, fracs, cols));
		int ri = Math.round(radius);
		bg.fillOval(a.center.x - ri, a.center.y - ri, ri * 2, ri * 2);
		bg.dispose();
	}

	/** The card itself — punched in, shaking, gilded — pivoting on its own centre. */
	private static void renderCard(Graphics2D g2, Arrival a, int f) {
		double scale = 1.0;
		if (f < PUNCH_END) {
			double t = 1.0 - (double) f / PUNCH_END;
			scale += PUNCH_SCALE * t * t;
		}
		double dx = 0, tilt = 0;
		if (f < SHAKE_END) {
			double t     = (double) f / SHAKE_END;
			double decay = (1.0 - t) * (1.0 - t);
			double phase = 2 * Math.PI * SHAKE_CYCLES * t;
			dx   = SHAKE_PX * decay * Math.sin(phase);
			tilt = SHAKE_TILT * decay * Math.sin(phase + Math.PI / 3);
		}
		float wash = 0f;
		if (f < WASH_END) {
			double t = (double) f / WASH_END;
			wash = (float) (WASH_ALPHA * Math.pow(1.0 - t, 1.5));
		}

		Graphics2D cg = (Graphics2D) g2.create();
		if (scale == 1.0 && dx == 0 && tilt == 0) {
			// At rest: drawn on whole pixels, so the last frames match the slot render exactly.
			cg.drawImage(a.img, a.imgOrigin.x, a.imgOrigin.y, null);
		} else {
			AffineTransform at = new AffineTransform();
			at.translate(a.center.x + dx, a.center.y);
			at.rotate(tilt);
			at.scale(scale, scale);
			at.translate(a.imgOrigin.x - a.center.x, a.imgOrigin.y - a.center.y);
			cg.transform(at);
			cg.drawImage(a.img, 0, 0, null);
		}
		if (wash > 0.01f) {
			cg.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, wash));
			if (scale == 1.0 && dx == 0 && tilt == 0) cg.drawImage(a.gold, a.imgOrigin.x, a.imgOrigin.y, null);
			else cg.drawImage(a.gold, 0, 0, null);
		}
		cg.dispose();
	}

	/** Four-pointed gold sparks thrown outward from the card's edge, as CardRfpAnimator draws them. */
	private static void renderSparks(Graphics2D g2, Arrival a, int f) {
		if (f < SPARK_START) return;
		double st      = (double) (f - SPARK_START) / (TOTAL_FRAMES - SPARK_START);
		int    alpha   = (int) Math.round((1.0 - st) * 230);
		float  armLen  = (float) (Math.sin(st * Math.PI) * UiScale.scale(10));
		if (alpha <= 0 || armLen < 0.5f) return;

		Graphics2D sg = (Graphics2D) g2.create();
		sg.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		for (int i = 0; i < SPARK_COUNT; i++) {
			double angle  = i * (2.0 * Math.PI / SPARK_COUNT) + Math.PI / SPARK_COUNT;
			double reach  = a.radius * (i % 2 == 0 ? 0.9 : 0.65);
			double travel = a.radius * 0.8 + reach * easeOut(st);
			int    sx     = (int) Math.round(a.center.x + Math.cos(angle) * travel);
			int    sy     = (int) Math.round(a.center.y + Math.sin(angle) * travel);
			Color  c      = SPARK[i % SPARK.length];
			sg.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), alpha));
			int ai = Math.round(armLen);
			int di = Math.round(armLen * 0.6f);
			sg.drawLine(sx - ai, sy,      sx + ai, sy);
			sg.drawLine(sx,      sy - ai, sx,      sy + ai);
			sg.drawLine(sx - di, sy - di, sx + di, sy + di);
			sg.drawLine(sx - di, sy + di, sx + di, sy - di);
		}
		sg.dispose();
	}

	private static double easeOut(double t) {
		return 1.0 - (1.0 - t) * (1.0 - t);
	}

	/** {@code src}'s alpha mask filled with {@code color}: the card's shape, rounded corners and all. */
	private static BufferedImage silhouette(BufferedImage src, Color color) {
		BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = out.createGraphics();
		g.drawImage(src, 0, 0, null);
		g.setComposite(AlphaComposite.SrcIn);
		g.setColor(color);
		g.fillRect(0, 0, src.getWidth(), src.getHeight());
		g.dispose();
		return out;
	}

	/** Bounding box of {@code img}'s visible pixels; the whole image if none are. */
	private static Rectangle opaqueBounds(BufferedImage img) {
		int w = img.getWidth(), h = img.getHeight();
		int minX = w, minY = h, maxX = -1, maxY = -1;
		for (int y = 0; y < h; y++)
			for (int x = 0; x < w; x++)
				if ((img.getRGB(x, y) >>> 24) > 16) {
					if (x < minX) minX = x;
					if (x > maxX) maxX = x;
					if (y < minY) minY = y;
					if (y > maxY) maxY = y;
				}
		return maxX < 0 ? new Rectangle(0, 0, w, h) : new Rectangle(minX, minY, maxX - minX + 1, maxY - minY + 1);
	}
}
