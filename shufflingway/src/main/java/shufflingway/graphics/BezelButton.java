package shufflingway.graphics;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.Area;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;

import javax.swing.ButtonModel;
import javax.swing.JButton;

import shufflingway.UiScale;

/**
 * A button drawn as a raised {@link Bezel}: lit along its top and left edges, shaded along its
 * bottom and right, its face shaded like a slight bulge. Pressed, it sinks: the edges swap, the face
 * darkens and the label drops a pixel. Disabled, it lies flat and dimmed, with no bezel, so only a
 * button that does something stands out. The background colour is the face's colour.
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
		if (getModel().isRollover() && !down) base = Bezel.mix(base, Color.WHITE, 0.10);

		Graphics2D g = (Graphics2D) g0.create();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		Shape outer   = shape(0, 0, w, h, arc);
		Shape outline = shape(0.5, 0.5, w - 1, h - 1, arc - 1);
		if (!isEnabled()) {
			g.setColor(Bezel.mix(base, Color.GRAY, 0.5));
			g.fill(outer);
			g.setColor(Bezel.mix(base, Color.BLACK, 0.65));
			g.setStroke(new BasicStroke(1f));
			g.draw(outline);
			g.dispose();
			super.paintComponent(g0);
			return;
		}
		Bezel.paint(g, outer, shape(bevel, bevel, w - 2 * bevel, h - 2 * bevel, Math.max(0, arc - 2 * bevel)),
				outline, bevel, base, down ? 1 : 0);
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
}
