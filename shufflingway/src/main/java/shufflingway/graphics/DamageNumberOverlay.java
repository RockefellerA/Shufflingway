package shufflingway.graphics;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JLayeredPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import shufflingway.CardState;
import shufflingway.FontLoader;
import shufflingway.UiScale;

/**
 * Transparent overlay on the frame's DRAG_LAYER that floats damage up off a field card: a red
 * number that rises from a quarter of the way up the card's centre line, then fades, over one second.
 *
 * <p>One {@link #spawn} is one instance of damage dealt to one card, as resolved — a blow in
 * battle, or what an ability or Summon dealt. A blocker hit by a party shows the party's one
 * combined blow, not a share per member.
 *
 * <p>Where a number starts is fixed at spawn time and never follows the slot. A card the blow
 * breaks has its slot emptied straight away, and its number keeps rising where it stood, over the
 * break animation — which is why this lives on the layered pane rather than being painted by the
 * slot, as {@link DamageNegateFlash} is.
 */
public class DamageNumberOverlay extends JComponent {

	public static final int DURATION_MS = 1000;
	private static final int FRAME_MS = 16;
	/** Fraction of the run the number holds full strength before it starts to fade. */
	private static final double FADE_START = 0.45;
	private static final float FONT_SIZE = 20f;

	private static final Color RED     = new Color(255, 60, 60);
	private static final Color OUTLINE = new Color(25, 0, 0, 235);

	/** How far the number drifts up over the run. */
	private static final float RISE    = UiScale.scale(44f);
	/** Where the number starts, as a fraction of the card's height measured up from its bottom edge. */
	private static final double START_UP = 0.25;
	private static final float STROKE  = UiScale.scale(3f);

	static final class Burst {
		final BufferedImage img;
		/** Top-left of {@link #img} at the start of the run, in overlay coordinates. */
		final int x, y;
		/** Which way is up on the card, as a unit step on screen: the way the number rises. */
		final int dx, dy;
		final long startNs = System.nanoTime();

		Burst(BufferedImage img, int x, int y, int dx, int dy) {
			this.img = img;
			this.x   = x;
			this.y   = y;
			this.dx  = dx;
			this.dy  = dy;
		}

		double progress() {
			return (System.nanoTime() - startNs) / 1_000_000.0 / DURATION_MS;
		}

		/** Everything this burst covers between its first frame and its last. */
		Rectangle sweep() {
			int rise = (int) Math.ceil(RISE);
			Rectangle r = new Rectangle(x, y, img.getWidth(), img.getHeight());
			r.add(new Rectangle(x + dx * rise, y + dy * rise, img.getWidth(), img.getHeight()));
			r.grow(OverlayDirtyRegion.PAD, OverlayDirtyRegion.PAD);
			return r;
		}
	}

	private final List<Burst> bursts = new ArrayList<>();
	private final Timer timer;
	private final OverlayDirtyRegion dirty = new OverlayDirtyRegion();
	private Font font;

	public DamageNumberOverlay() {
		setOpaque(false);
		setFocusable(false);
		timer = new Timer(FRAME_MS, e -> tick());
		timer.setCoalesce(true);
	}

	/** Installs the overlay on {@code frame}'s layered pane, in front of the other DRAG_LAYER animations. */
	public static DamageNumberOverlay install(JFrame frame) {
		DamageNumberOverlay o  = new DamageNumberOverlay();
		JLayeredPane        lp = frame.getRootPane().getLayeredPane();
		o.setBounds(0, 0, lp.getWidth(), lp.getHeight());
		lp.add(o, JLayeredPane.DRAG_LAYER);
		// In front of the break animation, so the number on a broken card rises over its halves.
		lp.moveToFront(o);
		lp.addComponentListener(new ComponentAdapter() {
			@Override public void componentResized(ComponentEvent e) {
				o.setBounds(0, 0, lp.getWidth(), lp.getHeight());
			}
		});
		return o;
	}

	/**
	 * Floats {@code amount} up off the card in {@code slot}. It starts centred across the card, a
	 * quarter of the way up from its bottom edge, and rises toward the card's top. A dull card is
	 * turned clockwise (see {@link CardAnimation#renderBackupCard}), and the number is turned with
	 * it: the same point on the card is a quarter of the way in from the slot's left, centred top
	 * to bottom, and the number rises to the right.
	 *
	 * @param lift how far the slot is drawn above its laid-out position, by {@link SlotLiftOverlay}
	 */
	public void spawn(JLabel slot, CardState state, int lift, int amount) {
		if (!slot.isShowing() || !isShowing()) return;
		Icon icon = slot.getIcon();
		Rectangle ir = icon != null ? FieldSlotLabel.iconBounds(slot, icon)
				: new Rectangle(0, 0, slot.getWidth(), slot.getHeight());
		bursts.add(burstAt(SwingUtilities.convertPoint(slot, ir.x, ir.y - lift, this), state, amount));
		if (!timer.isRunning()) timer.start();
		tick();
	}

	/** The burst for {@code amount} over a slot canvas whose top-left is at {@code canvas}. */
	Burst burstAt(Point canvas, CardState state, int amount) {
		Text t = renderNumber(String.valueOf(amount));
		// Top-left of the number in the card's own upright frame, with the digits centred on the
		// starting point.
		int ix = (int) Math.round(CardAnimation.CARD_W / 2.0 - t.centerX);
		int iy = (int) Math.round(CardAnimation.CARD_H * (1 - START_UP) - t.centerY);
		if (state != CardState.DULL)
			return new Burst(t.img, canvas.x + CardAnimation.LEFT_GUTTER + ix, canvas.y + iy, 0, -1);
		// Turned clockwise with the card and placed through the transform CardAnimation draws a dull
		// card's pills with, (x, y) -> (CARD_H - y, LEFT_GUTTER + x). The card's top faces right.
		BufferedImage turned = CardAnimation.rotateCW90(t.img);
		return new Burst(turned, canvas.x + CardAnimation.CARD_H - iy - t.img.getHeight(),
				canvas.y + CardAnimation.LEFT_GUTTER + ix, 1, 0);
	}

	/**
	 * A drawn number, and the centre of its digits within the image — what is set on the starting
	 * point, rather than the image's own centre, which the font's descent and the outline's
	 * padding would pull off the digits.
	 */
	private record Text(BufferedImage img, double centerX, double centerY) {}

	/** Draws {@code s} once, outlined in dark red and filled red, so each frame is a single blit. */
	private Text renderNumber(String s) {
		if (font == null) font = FontLoader.loadOverlayFont(FONT_SIZE);
		FontMetrics fm = getFontMetrics(font);
		int pad = (int) Math.ceil(STROKE) + 1;
		BufferedImage img = new BufferedImage(fm.stringWidth(s) + 2 * pad,
				fm.getAscent() + fm.getDescent() + 2 * pad, BufferedImage.TYPE_INT_ARGB);
		int baseline = pad + fm.getAscent();
		Graphics2D g = img.createGraphics();
		Rectangle2D digits;
		try {
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
			g.setStroke(new BasicStroke(STROKE, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			Shape outline = font.createGlyphVector(g.getFontRenderContext(), s).getOutline(pad, baseline);
			digits = outline.getBounds2D();
			g.setColor(OUTLINE);
			g.draw(outline);
			g.setColor(RED);
			g.fill(outline);
		} finally {
			g.dispose();
		}
		return new Text(img, digits.getCenterX(), digits.getCenterY());
	}

	/** Never intercepts mouse events — the board components below stay active. */
	@Override
	public boolean contains(int x, int y) {
		return false;
	}

	private void tick() {
		bursts.removeIf(b -> b.progress() >= 1);
		if (bursts.isEmpty()) timer.stop();
		Rectangle now = null;
		for (Burst b : bursts) now = OverlayDirtyRegion.union(now, b.sweep());
		dirty.repaint(this, now);
	}

	@Override
	protected void paintComponent(Graphics g) {
		if (bursts.isEmpty()) return;
		for (Burst b : bursts) paintBurst((Graphics2D) g, b, b.progress());
	}

	/** Paints {@code b} as it stands {@code t} of the way through its run, 0 to 1. */
	static void paintBurst(Graphics2D g, Burst b, double t) {
		t = Math.max(0, t);
		if (t >= 1) return;
		// Eases out: a quick lift off the card that slows as it fades.
		double up    = RISE * (1 - (1 - t) * (1 - t));
		double alpha = t < FADE_START ? 1 : 1 - smooth((t - FADE_START) / (1 - FADE_START));
		Graphics2D g2 = (Graphics2D) g.create();
		try {
			g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) alpha));
			g2.drawImage(b.img, (int) Math.round(b.x + b.dx * up), (int) Math.round(b.y + b.dy * up), null);
		} finally {
			g2.dispose();
		}
	}

	private static double smooth(double x) {
		x = Math.max(0, Math.min(1, x));
		return x * x * (3 - 2 * x);
	}
}
