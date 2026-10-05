package shufflingway.graphics;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.util.Random;

import shufflingway.CardState;
import shufflingway.UiScale;

/**
 * The flourish over a field card whose damage an effect has just negated: a soft white flash that
 * swells and fades across the whole card, and light green sparkles twinkling up out of the
 * bottom-left corner where the damage pill was.
 *
 * <p>Painted over the slot canvas by {@link FieldSlotLabel}, not baked into the card render, so a
 * re-render landing mid-flash (the one that clears the pill) does not cut it short. Drawing happens
 * in the card's own upright frame through the same transform {@link CardAnimation} uses for the
 * pill, so on a dull card the sparkles still sit over the pill.
 */
public final class DamageNegateFlash {

	public static final int DURATION_MS = 1000;

	/** Fraction of the run at which the white flash peaks. */
	private static final double FLASH_PEAK = 0.12;
	/** Fraction of the run at which the white flash has gone. */
	private static final double FLASH_END  = 0.80;
	/** Fraction of the run at which the sparkles begin — as the pill clears. */
	private static final double SPARKLE_START = 0.12;
	private static final int    SPARKLES = 10;

	private static final Color WHITE       = new Color(255, 255, 255);
	/** Bloom and star halos: deep enough to read against a card's pale text box. */
	private static final Color GREEN_HALO  = new Color(70, 215, 90);
	private static final Color GREEN       = new Color(150, 250, 140);
	private static final Color GREEN_CORE  = new Color(235, 255, 230);

	private static final float CORNER     = UiScale.scale(8f);
	private static final float GLOW_STEP  = UiScale.scale(2f);
	private static final int   GLOW_RINGS = 7;
	/** The patch the damage pill covers, in the card's upright frame, measured from its bottom-left. */
	private static final float PILL_W     = UiScale.scale(44f);
	private static final float PILL_H     = UiScale.scale(22f);
	private static final float PILL_INSET = UiScale.scale(4f);

	private DamageNegateFlash() {}

	/**
	 * Paints one frame onto {@code g0}, whose origin is the slot canvas's top-left.
	 *
	 * @param t    progress through the run, 0 to 1
	 * @param seed fixes the sparkle layout for one run, so it holds still from frame to frame
	 */
	public static void paint(Graphics2D g0, CardState state, double t, long seed) {
		if (t < 0 || t >= 1) return;
		int cw = CardAnimation.CARD_W, ch = CardAnimation.CARD_H;
		Graphics2D g = (Graphics2D) g0.create();
		try {
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			if (state == CardState.DULL) {
				g.translate(ch, CardAnimation.LEFT_GUTTER);
				g.rotate(Math.PI / 2);
			} else {
				g.translate(CardAnimation.LEFT_GUTTER, 0);
			}
			Shape card = new RoundRectangle2D.Float(0, 0, cw, ch, CORNER, CORNER);
			g.clip(card);
			paintFlash(g, cw, ch, flashEnvelope(t));
			paintSparkles(g, ch, t, seed);
		} finally {
			g.dispose();
		}
	}

	/** Rises fast to the peak, then eases away. */
	private static double flashEnvelope(double t) {
		if (t < FLASH_PEAK) return smooth(t / FLASH_PEAK);
		if (t >= FLASH_END) return 0;
		return 1 - smooth((t - FLASH_PEAK) / (FLASH_END - FLASH_PEAK));
	}

	/** A white wash over the card, with a brighter glow banked up against its edge. */
	private static void paintFlash(Graphics2D g, int cw, int ch, double env) {
		if (env <= 0) return;
		g.setColor(alpha(WHITE, 0.38 * env));
		g.fillRect(0, 0, cw, ch);
		g.setStroke(new BasicStroke(GLOW_STEP));
		for (int i = 0; i < GLOW_RINGS; i++) {
			double fall = 1 - i / (double) GLOW_RINGS;
			g.setColor(alpha(WHITE, 0.45 * env * fall * fall));
			float inset = GLOW_STEP * (i + 0.5f);
			g.draw(new RoundRectangle2D.Float(inset, inset, cw - inset * 2, ch - inset * 2, CORNER, CORNER));
		}
	}

	/** A green bloom over the pill's patch, and four-point stars twinkling upward out of it. */
	private static void paintSparkles(Graphics2D g, int ch, double t, long seed) {
		if (t < SPARKLE_START) return;
		double local = (t - SPARKLE_START) / (1 - SPARKLE_START);
		float cx = PILL_INSET + PILL_W / 2f;
		float cy = ch - PILL_INSET - PILL_H / 2f;

		double bloom = Math.sin(Math.PI * Math.min(1, local * 1.15));
		if (bloom > 0) {
			float r = PILL_W * 0.8f;
			g.setPaint(new RadialGradientPaint(cx, cy, r, new float[]{ 0f, 1f },
					new Color[]{ alpha(GREEN_HALO, 0.55 * bloom), alpha(GREEN_HALO, 0) }));
			g.fillOval(Math.round(cx - r), Math.round(cy - r), Math.round(r * 2), Math.round(r * 2));
		}

		Random rnd = new Random(seed);
		for (int i = 0; i < SPARKLES; i++) {
			double birth = rnd.nextDouble() * 0.5;
			double life  = Math.min(0.3 + rnd.nextDouble() * 0.3, 1 - birth);
			float  ox    = (float) ((rnd.nextDouble() - 0.5) * PILL_W);
			float  oy    = (float) ((rnd.nextDouble() - 0.5) * PILL_H);
			float  size  = UiScale.scale(3f) + (float) rnd.nextDouble() * UiScale.scale(3.5f);
			double spin  = (rnd.nextDouble() - 0.5) * Math.PI / 2;
			double u = (local - birth) / life;
			if (u <= 0 || u >= 1) continue;
			double a = Math.sin(Math.PI * u);
			float x = cx + ox;
			float y = cy + oy - (float) (u * UiScale.scale(12f));
			paintStar(g, x, y, size * (float) (0.6 + 0.4 * a), spin * u, a);
		}
	}

	/** A four-point star: a soft green halo, the star in green, a near-white core. */
	private static void paintStar(Graphics2D g0, float x, float y, float size, double angle, double a) {
		Graphics2D g = (Graphics2D) g0.create();
		g.translate(x, y);
		g.rotate(angle);
		g.setColor(alpha(GREEN_HALO, 0.5 * a));
		g.fill(star(size * 1.6f, size * 0.5f));
		g.setColor(alpha(GREEN, a));
		g.fill(star(size, size * 0.22f));
		g.setColor(alpha(GREEN_CORE, a));
		g.fill(star(size * 0.5f, size * 0.14f));
		g.dispose();
	}

	private static Shape star(float r, float waist) {
		Path2D.Float p = new Path2D.Float();
		p.moveTo(0, -r);
		p.lineTo(waist, -waist);
		p.lineTo(r, 0);
		p.lineTo(waist, waist);
		p.lineTo(0, r);
		p.lineTo(-waist, waist);
		p.lineTo(-r, 0);
		p.lineTo(-waist, -waist);
		p.closePath();
		return p;
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
