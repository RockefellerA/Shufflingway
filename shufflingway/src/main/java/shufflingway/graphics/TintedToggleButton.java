package shufflingway.graphics;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;

import javax.swing.JToggleButton;

/**
 * A toggle button that takes on a colour tint while it is pressed in, and looks like any other
 * button while it is not.
 *
 * <p>The tint is painted over the look and feel's own rendering rather than set as the background:
 * the default look and feel draws a selected toggle in its own "select" grey and ignores
 * {@code setBackground} for that state, so the only colour that reliably shows is one laid on top.
 * It is translucent, so the pressed-in shading and the label both still read through it.
 */
public class TintedToggleButton extends JToggleButton {

	private final Color tint;

	/**
	 * @param text the label
	 * @param tint the colour to wash the button with while selected; its alpha is replaced, so
	 *             pass the plain colour
	 */
	public TintedToggleButton(String text, Color tint) {
		super(text);
		this.tint = new Color(tint.getRed(), tint.getGreen(), tint.getBlue(), 140);
	}

	@Override
	protected void paintComponent(Graphics g) {
		super.paintComponent(g);
		if (!isSelected()) return;
		Graphics2D g2 = (Graphics2D) g.create();
		try {
			g2.setColor(tint);
			g2.fillRect(0, 0, getWidth(), getHeight());
		} finally {
			g2.dispose();
		}
	}
}
