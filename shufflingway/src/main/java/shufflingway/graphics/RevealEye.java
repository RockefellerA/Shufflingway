package shufflingway.graphics;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;

/**
 * The eye drawn on a card the opponent knows although it is hidden from them: a card in your hand
 * they have been shown, or a face-down card on top of a deck that both players have seen. It is an
 * eye on a dark disc, so it reads on any art.
 */
public final class RevealEye {

	/** The rim's width, as a fraction of the disc's diameter. */
	private static final double RIM_FRACTION = 0.06;
	private static final Color DISC = new Color(20, 20, 28, 210);
	private static final Color RIM  = new Color(255, 255, 255, 150);
	private static final Color IRIS = new Color(70, 140, 230);

	private RevealEye() {}

	/** How far an eye {@code d} across reaches from its centre: its radius and half its rim. */
	public static double reach(double d) {
		return d / 2 + d * RIM_FRACTION / 2;
	}

	/** Paints an eye {@code d} across, centred on ({@code cx}, {@code cy}). */
	public static void paint(Graphics2D g0, double cx, double cy, double d) {
		Graphics2D g = (Graphics2D) g0.create();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		g.setColor(DISC);
		g.fill(new Ellipse2D.Double(cx - d / 2, cy - d / 2, d, d));
		g.setColor(RIM);
		g.setStroke(new BasicStroke((float) (d * RIM_FRACTION)));
		g.draw(new Ellipse2D.Double(cx - d / 2, cy - d / 2, d, d));

		// Almond: two arcs meeting at the corners of the eye.
		double ew = d * 0.68, eh = d * 0.42;
		Path2D.Double almond = new Path2D.Double();
		almond.moveTo(cx - ew / 2, cy);
		almond.quadTo(cx, cy - eh, cx + ew / 2, cy);
		almond.quadTo(cx, cy + eh, cx - ew / 2, cy);
		almond.closePath();
		g.setColor(Color.WHITE);
		g.fill(almond);

		double iris = d * 0.26;
		g.setColor(IRIS);
		g.fill(new Ellipse2D.Double(cx - iris / 2, cy - iris / 2, iris, iris));
		double pupil = iris * 0.45;
		g.setColor(Color.BLACK);
		g.fill(new Ellipse2D.Double(cx - pupil / 2, cy - pupil / 2, pupil, pupil));
		g.dispose();
	}
}
