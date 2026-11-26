package shufflingway.graphics;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;

import javax.swing.ButtonModel;
import javax.swing.JButton;

import shufflingway.UiScale;

/**
 * A button drawn as a raised bezel: lit along its top and left edges, shaded along its bottom and
 * right, its face shaded like a slight bulge. Pressed, it sinks: the edges swap, the face darkens
 * and the label drops a pixel. Disabled, it lies flat and dimmed, with no bezel, so only a button
 * that does something stands out. The background colour is the face's colour.
 *
 * <p>The corners at one end may be rounded, so the button can close off the end of a row of
 * square ones.
 */
public class BezelButton extends JButton {

	/** Which end's corners are rounded, if either. */
	public enum Round { LEFT, RIGHT, NONE }

	private final Round round;

	public BezelButton(String text, Round round) {
		super(text);
		this.round = round;
		setContentAreaFilled(false);
		setOpaque(false);
		setBorderPainted(false);
		setFocusPainted(false);
	}

	/** Whether the button is being held down, drawn sunk. */
	private boolean isDown() {
		ButtonModel m = getModel();
		return m.isArmed() && m.isPressed();
	}

	@Override
	protected void paintComponent(Graphics g0) {
		boolean down = isDown();
		int w = getWidth(), h = getHeight();
		int bevel = Math.max(2, UiScale.scale(2));
		double arc = h * 2 / 3.0;   // corner radius a third of the height

		Color base = getBackground();
		if (getModel().isRollover() && !down) base = mix(base, Color.WHITE, 0.10);
		Color lit   = mix(base, Color.WHITE, 0.55);
		Color shade = mix(base, Color.BLACK, 0.45);

		Graphics2D g = (Graphics2D) g0.create();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		Shape outer = shape(0, 0, w, h, arc);
		if (!isEnabled()) {
			g.setColor(mix(base, Color.GRAY, 0.5));
			g.fill(outer);
			g.setColor(mix(base, Color.BLACK, 0.65));
			g.setStroke(new BasicStroke(1f));
			g.draw(shape(0.5, 0.5, w - 1, h - 1, arc - 1));
			g.dispose();
			super.paintComponent(g0);
			return;
		}
		// The edges: the whole shape in the bottom-right colour, then the band along the top and
		// left that a copy of it shifted down and right by the bevel leaves uncovered.
		g.setColor(down ? lit : shade);
		g.fill(outer);
		Area topLeft = new Area(outer);
		topLeft.subtract(new Area(AffineTransform.getTranslateInstance(bevel, bevel).createTransformedShape(outer)));
		g.setColor(down ? shade : lit);
		g.fill(topLeft);
		// The face, lit from above when raised and shadowed from above when sunk.
		Color faceTop    = down ? mix(base, Color.BLACK, 0.20) : mix(base, Color.WHITE, 0.18);
		Color faceBottom = down ? mix(base, Color.BLACK, 0.05) : mix(base, Color.BLACK, 0.10);
		g.setPaint(new GradientPaint(0, bevel, faceTop, 0, h - bevel, faceBottom));
		g.fill(shape(bevel, bevel, w - 2 * bevel, h - 2 * bevel, Math.max(0, arc - 2 * bevel)));
		g.setColor(mix(base, Color.BLACK, 0.65));
		g.setStroke(new BasicStroke(1f));
		g.draw(shape(0.5, 0.5, w - 1, h - 1, arc - 1));
		g.dispose();

		Graphics2D label = (Graphics2D) g0.create();
		if (down) label.translate(1, 1);
		super.paintComponent(label);
		label.dispose();
	}

	/** A {@code w × h} rectangle at {@code (x, y)}, its corners at the {@link #round} end rounded. */
	private Shape shape(double x, double y, double w, double h, double arc) {
		if (round == Round.NONE) return new Rectangle2D.Double(x, y, w, h);
		// A rounded rectangle wider by the arc, so the corners at the far end fall outside the bounds.
		Area a = new Area(round == Round.LEFT
				? new RoundRectangle2D.Double(x, y, w + arc, h, arc, arc)
				: new RoundRectangle2D.Double(x - arc, y, w + arc, h, arc, arc));
		a.intersect(new Area(new Rectangle2D.Double(x, y, w, h)));
		return a;
	}

	private static Color mix(Color a, Color b, double t) {
		return new Color(
				(int) Math.round(a.getRed()   + (b.getRed()   - a.getRed())   * t),
				(int) Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * t),
				(int) Math.round(a.getBlue()  + (b.getBlue()  - a.getBlue())  * t));
	}
}
