package shufflingway.graphics;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;

import static shufflingway.graphics.CardAnimation.CARD_H;
import static shufflingway.graphics.CardAnimation.CARD_W;
import static shufflingway.graphics.CardAnimation.LEFT_GUTTER;

/**
 * The ATB gauge for a Forward that is not ready yet — one that entered the field too recently to
 * attack or to pay a 《Dull》 cost, and has no Haste. An Active Time Battle bar just off the card's
 * centre-facing edge, it fills an eighth for each phase of the wait that goes by, rising to each new
 * level over {@link #RISE_MS}; when the card becomes ready it tops out, flashes, holds gold for a
 * beat and fades.
 *
 * <p>This draws the bar and says where it goes; {@link NotReadyGaugeOverlay} keeps each card's bar
 * and paints it over the board. Sizes are authored against the 140 x 205 design card and scaled to
 * the live one.
 */
public final class NotReadyMarker {

	/**
	 * How many phases the gauge counts across a full wait: Main Phase 1, the Attack Phase, Main
	 * Phase 2 and the End Phase of the controller's turn, then the same four of the opponent's.
	 */
	public static final int PHASES = 8;

	/** How long the gauge takes to rise to its new level when a phase goes by. */
	public static final int RISE_MS = 500;

	private static final float DESIGN_W = 140f;
	private static final float DESIGN_H = 205f;

	/** The bar, in design units: as wide as the card less an inset at each end, and this tall. */
	private static final float BAR_INSET = 9f;
	private static final float BAR_W     = DESIGN_W - 2 * BAR_INSET;
	private static final float BAR_H     = 7f;
	/** Between the bar and the card's edge, in design units. */
	private static final float BAR_GAP   = 5f;
	private static final int   FLARE_RINGS = 5;

	private static final Color GAUGE_SHADOW = new Color(0, 0, 0, 130);
	private static final Color TRACK        = new Color(10, 14, 44, 235);
	private static final Color RIM          = new Color(228, 230, 242);
	private static final Color FILL_LOW     = new Color(214, 128, 18);
	private static final Color FILL_HIGH    = new Color(255, 226, 70);
	private static final Color FILL_SHEEN   = new Color(255, 255, 255, 80);

	/** How long the clearing takes to top the gauge out from empty; from part-way, that share of it. */
	private static final double CLEAR_FILL_MS = 405;
	/** Once full: the flash rises, dies away, the gauge holds gold for a beat, then fades. */
	private static final double FLASH_RISE_MS = 60, FLASH_FALL_MS = 150, GAUGE_HOLD_MS = 110, GAUGE_FADE_MS = 225;

	private NotReadyMarker() {}

	/** How full the gauge is with {@code phasesGone} of the {@link #PHASES} gone by, from 0 to 1. */
	public static double fill(int phasesGone) {
		return Math.max(0, Math.min(PHASES, phasesGone)) / (double) PHASES;
	}

	/** How full a gauge rising from {@code from} to {@code to} is {@code ms} into its {@link #RISE_MS}. */
	public static double riseFill(double from, double to, double ms) {
		return from + (to - from) * smooth(ms / RISE_MS);
	}

	/** How long the clearing of a gauge resting {@code from} full runs, in milliseconds: shorter the fuller it is. */
	public static int clearingMs(double from) {
		return (int) Math.ceil(fadeStartMs(from) + GAUGE_FADE_MS);
	}

	/**
	 * Where the bar goes for a Forward whose slot canvas has its left edge at {@code canvasX}: across
	 * the upright card's width, a short gap off {@code edgeY}, the card's centre-facing edge — above
	 * it, or below it when {@code below}.
	 */
	public static Rectangle2D.Double bar(double canvasX, double edgeY, boolean below) {
		double sx = CARD_W / DESIGN_W, sy = CARD_H / DESIGN_H;
		double w = BAR_W * sx, h = BAR_H * sy, gap = BAR_GAP * sy;
		double y = below ? edgeY + gap : edgeY - gap - h;
		return new Rectangle2D.Double(canvasX + LEFT_GUTTER + BAR_INSET * sx, y, w, h);
	}

	/** Everything drawing {@code bar} can touch — its shadow and the flare rings of its clearing — in whole pixels. */
	public static Rectangle reach(Rectangle2D bar) {
		double sx = bar.getWidth() / BAR_W, sy = bar.getHeight() / BAR_H;
		double rx = (FLARE_RINGS + 1) * sx + 1, ry = (FLARE_RINGS + 1.5) * sy + 1;
		int x0 = (int) Math.floor(bar.getMinX() - rx), y0 = (int) Math.floor(bar.getMinY() - ry);
		int x1 = (int) Math.ceil(bar.getMaxX() + rx),  y1 = (int) Math.ceil(bar.getMaxY() + ry);
		return new Rectangle(x0, y0, x1 - x0, y1 - y0);
	}

	/** Paints the bar {@code fill} full, from 0 to 1, in {@code bar}. */
	public static void paint(Graphics2D g, Rectangle2D bar, double fill) {
		draw(g, bar, fill, 0);
	}

	/**
	 * Paints, in {@code bar}, a gauge that was resting {@code from} full, {@code ms} milliseconds into
	 * its clearing: at 0 it is the resting gauge, and by {@link #clearingMs} it is gone.
	 */
	public static void paintClearing(Graphics2D g, Rectangle2D bar, double from, double ms) {
		if (ms < 0 || ms >= clearingMs(from)) return;
		draw(g, bar, from, ms);
	}

	// ── Drawing ──────────────────────────────────────────────────────────

	/** The gauge resting at {@code from} full, {@code ms} into its clearing; 0 is the resting gauge. */
	private static void draw(Graphics2D g0, Rectangle2D bar, double from, double ms) {
		double fullAt = CLEAR_FILL_MS * (1 - from);
		double fill   = ms >= fullAt ? 1 : from + (1 - from) * smooth(ms / fullAt);
		double topped = ms - fullAt;
		double flash  = topped < 0 ? 0
				: topped < FLASH_RISE_MS ? smooth(topped / FLASH_RISE_MS)
				: 1 - smooth((topped - FLASH_RISE_MS) / FLASH_FALL_MS);
		double fadeAt = fadeStartMs(from);
		double alpha  = ms < fadeAt ? 1 : 1 - smooth((ms - fadeAt) / GAUGE_FADE_MS);
		if (alpha <= 0) return;

		Graphics2D g = (Graphics2D) g0.create();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
		g.translate(bar.getX(), bar.getY());
		g.scale(bar.getWidth() / BAR_W, bar.getHeight() / BAR_H);
		g.setComposite(AlphaComposite.SrcOver.derive((float) alpha));

		RoundRectangle2D outer = new RoundRectangle2D.Float(0, 0, BAR_W, BAR_H, BAR_H, BAR_H);
		g.setColor(GAUGE_SHADOW);
		g.fill(new RoundRectangle2D.Float(1, 1.5f, BAR_W, BAR_H, BAR_H, BAR_H));
		g.setColor(TRACK);
		g.fill(outer);
		if (fill > 0) {
			float h = BAR_H - 2;
			Graphics2D f = (Graphics2D) g.create();
			f.clip(new RoundRectangle2D.Float(1, 1, BAR_W - 2, h, h, h));
			float filled = (float) ((BAR_W - 2) * Math.min(1, fill));
			f.setPaint(new GradientPaint(0, 0, FILL_LOW, BAR_W, 0, FILL_HIGH));
			f.fill(new Rectangle2D.Float(1, 1, filled, h));
			f.setColor(FILL_SHEEN);
			f.fill(new Rectangle2D.Float(1, 1, filled, h / 2f));
			f.dispose();
		}
		g.setStroke(new BasicStroke(1f));
		g.setColor(RIM);
		g.draw(outer);
		if (flash > 0) {
			g.setColor(alpha(Color.WHITE, 0.75 * flash));
			g.fill(outer);
			for (int i = 1; i <= FLARE_RINGS; i++) {
				double fall = 1 - i / (FLARE_RINGS + 1.0);
				g.setColor(alpha(FILL_HIGH, 0.55 * flash * fall * fall));
				float arc = BAR_H + 2 * i;
				g.draw(new RoundRectangle2D.Float(-i, -i, BAR_W + 2 * i, BAR_H + 2 * i, arc, arc));
			}
		}
		g.dispose();
	}

	/** When, into the clearing of a gauge resting {@code from} full, it begins to fade. */
	private static double fadeStartMs(double from) {
		return CLEAR_FILL_MS * (1 - from) + FLASH_RISE_MS + FLASH_FALL_MS + GAUGE_HOLD_MS;
	}

	private static double smooth(double x) {
		x = Math.max(0, Math.min(1, x));
		return x * x * (3 - 2 * x);
	}

	private static Color alpha(Color c, double a) {
		return new Color(c.getRed(), c.getGreen(), c.getBlue(),
				(int) Math.round(Math.max(0, Math.min(1, a)) * 255));
	}
}
