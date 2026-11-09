package shufflingway.graphics;

import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Insets;
import java.awt.Rectangle;

import javax.swing.AbstractButton;
import javax.swing.ButtonGroup;
import javax.swing.ButtonModel;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JToggleButton;
import javax.swing.border.AbstractBorder;
import javax.swing.border.CompoundBorder;
import javax.swing.plaf.basic.BasicBorders;
import javax.swing.plaf.metal.MetalLookAndFeel;
import javax.swing.plaf.metal.MetalToggleButtonUI;

/**
 * A toggle button that sits sunk into its bezel and takes on a colour tint while it is pressed
 * in, and stands proud of a raised bezel while it is not. A sunken button also drops its label —
 * text or icon — one pixel down and right, the way a physically pushed key would.
 *
 * <p>Inside a {@link ButtonGroup} it latches: once pressed in, mouse clicks and the
 * space bar do nothing until another button in the group is chosen. A plain toggle in a group
 * cannot be deselected by a second click either, but it still fires an action event for one,
 * so anything listening would react to a choice that did not change. Outside a group the button
 * toggles normally, since nothing else could ever release it.
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
		setModel(new LatchingModel());
		// Not a UIResource, so a look-and-feel change keeps it. The margin border keeps
		// setMargin working, as it does inside the look and feel's own compound border.
		setBorder(new CompoundBorder(new BezelBorder(), new BasicBorders.MarginBorder()));
	}

	/**
	 * @param icon the label, drawn in place of text
	 * @param tint as for {@link #TintedToggleButton(String, Color)}
	 */
	public TintedToggleButton(Icon icon, Color tint) {
		this((String) null, tint);
		setIcon(icon);
	}

	/** Re-installs the tinting delegate whenever the look and feel hands out a fresh one. */
	@Override
	public void updateUI() {
		setUI(new TintUI());
	}

	/** Pressed in: chosen, or held down by the mouse or space bar on the way to being chosen. */
	private static boolean isSunk(ButtonModel m) {
		return m.isSelected() || (m.isArmed() && m.isPressed());
	}

	/**
	 * A toggle model that will not arm or press while it is selected within a group. A click is
	 * only an action once the model has been both armed and pressed, so refusing those two is
	 * enough to swallow mouse, keyboard and {@code doClick} alike, with no pressed-state flicker.
	 */
	private static final class LatchingModel extends ToggleButtonModel {
		@Override
		public void setArmed(boolean b) {
			super.setArmed(b && !isLatched());
		}

		@Override
		public void setPressed(boolean b) {
			super.setPressed(b && !isLatched());
		}

		private boolean isLatched() {
			return isSelected() && getGroup() != null;
		}
	}

	/**
	 * A one-pixel outline around a two-pixel bevel, lit from the top left when the button stands
	 * out and shadowed from the top left when it is sunk. Three pixels a side, the same as Metal's
	 * own toggle border, so swapping it in does not resize the button. The bevel is translucent
	 * white and black, so it shades whatever fill lies underneath, tint included.
	 */
	private static final class BezelBorder extends AbstractBorder {
		private static final Color LIGHT       = new Color(255, 255, 255, 210);
		private static final Color LIGHT_SOFT  = new Color(255, 255, 255, 100);
		private static final Color SHADOW      = new Color(0, 0, 0, 120);
		private static final Color SHADOW_SOFT = new Color(0, 0, 0, 55);
		private static final Color NONE        = new Color(0, 0, 0, 0);

		@Override
		public void paintBorder(Component c, Graphics g, int x, int y, int w, int h) {
			g.setColor(c.isEnabled() ? MetalLookAndFeel.getControlDarkShadow()
					: MetalLookAndFeel.getControlShadow());
			g.drawRect(x, y, w - 1, h - 1);
			if (isSunk(((AbstractButton) c).getModel())) {
				ring(g, x + 1, y + 1, w - 2, h - 2, SHADOW, LIGHT_SOFT);
				ring(g, x + 2, y + 2, w - 4, h - 4, SHADOW_SOFT, NONE);
			} else {
				ring(g, x + 1, y + 1, w - 2, h - 2, LIGHT, SHADOW);
				ring(g, x + 2, y + 2, w - 4, h - 4, LIGHT_SOFT, SHADOW_SOFT);
			}
		}

		/**
		 * One bevel ring: top and left edges in {@code topLeft}, bottom and right in
		 * {@code bottomRight}. Every pixel is drawn exactly once, so translucent colours do not
		 * double up at the corners.
		 */
		private static void ring(Graphics g, int x, int y, int w, int h, Color topLeft, Color bottomRight) {
			int r = x + w - 1, b = y + h - 1;
			g.setColor(topLeft);
			g.drawLine(x, y, r - 1, y);
			g.drawLine(x, y + 1, x, b - 1);
			g.setColor(bottomRight);
			g.drawLine(x, b, r, b);
			g.drawLine(r, y, r, b - 1);
		}

		@Override
		public Insets getBorderInsets(Component c, Insets insets) {
			insets.set(3, 3, 3, 3);
			return insets;
		}

		@Override
		public boolean isBorderOpaque() {
			return false;
		}
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

		@Override
		protected void paintText(Graphics g, JComponent c, Rectangle textRect, String text) {
			if (isSunk(((AbstractButton) c).getModel())) {
				textRect = new Rectangle(textRect);
				textRect.translate(1, 1);
			}
			super.paintText(g, c, textRect, text);
		}

		@Override
		protected void paintIcon(Graphics g, AbstractButton b, Rectangle iconRect) {
			if (isSunk(b.getModel())) {
				iconRect = new Rectangle(iconRect);
				iconRect.translate(1, 1);
			}
			super.paintIcon(g, b, iconRect);
		}
	}
}
