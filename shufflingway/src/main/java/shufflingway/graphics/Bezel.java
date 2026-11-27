package shufflingway.graphics;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Rectangle2D;

/**
 * The raised bezel {@link BezelButton} and {@link DamageSlot} are drawn with: lit along the top and
 * left edges, shaded along the bottom and right, the face shaded like a slight bulge. Pressed in,
 * the edges swap and the face darkens.
 *
 * <p>How far in is a fraction, so a bezel can be animated going in or out. Halfway, the two edges
 * meet in the same colour and the bezel lies flat.
 */
final class Bezel {

	private Bezel() {}

	/**
	 * Paints a bezel in {@code base}: the edges in the band between {@code outer} and {@code face}
	 * ({@code bevel} wide), the face, and {@code outline} around the lot.
	 *
	 * @param sink how far in it is pressed, from 0 (raised) to 1 (sunk)
	 */
	static void paint(Graphics2D g, Shape outer, Shape face, Shape outline, int bevel, Color base, double sink) {
		Color lit   = mix(base, Color.WHITE, 0.55);
		Color shade = mix(base, Color.BLACK, 0.45);
		// The edges: the whole shape in the bottom-right colour, then the band along the top and left
		// that a copy of it shifted down and right by the bevel leaves uncovered.
		g.setColor(mix(shade, lit, sink));
		g.fill(outer);
		Area topLeft = new Area(outer);
		topLeft.subtract(new Area(AffineTransform.getTranslateInstance(bevel, bevel).createTransformedShape(outer)));
		g.setColor(mix(lit, shade, sink));
		g.fill(topLeft);
		// The face, lit from above when raised and shadowed from above when sunk.
		Color faceTop    = mix(mix(base, Color.WHITE, 0.18), mix(base, Color.BLACK, 0.20), sink);
		Color faceBottom = mix(mix(base, Color.BLACK, 0.10), mix(base, Color.BLACK, 0.05), sink);
		Rectangle2D fb = face.getBounds2D();
		g.setPaint(new GradientPaint(0, (float) fb.getMinY(), faceTop, 0, (float) fb.getMaxY(), faceBottom));
		g.fill(face);
		g.setColor(mix(base, Color.BLACK, 0.65));
		g.setStroke(new BasicStroke(1f));
		g.draw(outline);
	}

	/** {@code a} moved a fraction {@code t} of the way to {@code b}. */
	static Color mix(Color a, Color b, double t) {
		return new Color(
				(int) Math.round(a.getRed()   + (b.getRed()   - a.getRed())   * t),
				(int) Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * t),
				(int) Math.round(a.getBlue()  + (b.getBlue()  - a.getBlue())  * t));
	}
}
