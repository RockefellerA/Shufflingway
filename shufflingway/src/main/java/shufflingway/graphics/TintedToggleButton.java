package shufflingway.graphics;

import java.awt.Color;
import java.awt.Graphics;

import javax.swing.AbstractButton;
import javax.swing.JToggleButton;
import javax.swing.plaf.metal.MetalToggleButtonUI;

/**
 * A toggle button that takes on a colour tint while it is pressed in, and looks like any other
 * button while it is not.
 *
 * <p>The tint is laid over the look and feel's pressed-in background and under everything drawn
 * after it, so the label keeps its own colour. It cannot simply be set as the background: the
 * default look and feel fills a selected toggle with its own "select" grey and ignores
 * {@code setBackground} for that state. A UI delegate is the one place between that fill and the
 * text, which is why this carries its own rather than painting over the finished button.
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

	/** Re-installs the tinting delegate whenever the look and feel hands out a fresh one. */
	@Override
	public void updateUI() {
		setUI(new TintUI());
	}

	/**
	 * The Metal toggle delegate, with the tint added to the pressed-in fill. Metal paints that fill
	 * in {@code paintButtonPressed} and the icon and text after it, so a wash laid down here stays
	 * behind the label. Only a selected button is tinted: the same method also runs while the mouse
	 * is held down on an unselected one.
	 */
	private final class TintUI extends MetalToggleButtonUI {
		@Override
		protected void paintButtonPressed(Graphics g, AbstractButton b) {
			super.paintButtonPressed(g, b);
			if (!b.isSelected() || !b.isContentAreaFilled() || tint == null) return;
			g.setColor(tint);
			g.fillRect(0, 0, b.getWidth(), b.getHeight());
		}
	}
}
