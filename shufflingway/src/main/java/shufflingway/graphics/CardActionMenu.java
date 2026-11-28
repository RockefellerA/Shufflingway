package shufflingway.graphics;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;

import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.border.Border;
import javax.swing.border.EmptyBorder;
import javax.swing.plaf.basic.BasicGraphicsUtils;
import javax.swing.plaf.basic.BasicMenuItemUI;

import shufflingway.GenericCp;
import shufflingway.UiScale;

/**
 * The menu a card opens when it is clicked to be played, dressed as the tooltips are
 * ({@link ToolTipStyle}): white on dark grey inside a framed bevel. An item takes the look as it
 * is added.
 *
 * <p>Each theme draws menu items its own way, and the colours of the row under the pointer and of
 * an option that cannot be taken are fixed inside that drawing. So the items here are drawn by a
 * renderer of their own, which reads the same under every theme.
 */
public class CardActionMenu extends JPopupMenu {

	/** The row under the pointer. */
	static final Color HIGHLIGHT     = new Color(0x48, 0x5c, 0x80);
	/** An option that cannot be taken right now. */
	static final Color DISABLED_TEXT = new Color(0x86, 0x86, 0x8e);

	/** Left with FlatLaf's rounded corners, as the tooltips are: see {@link ToolTipStyle#install}. */
	public CardActionMenu() {
		setBackground(ToolTipStyle.BACKGROUND);
		setBorder(ToolTipStyle.framed(UiScale.scale(2), 0));
	}

	@Override
	public JMenuItem add(JMenuItem item) {
		item.setUI(new ItemUI());
		return super.add(item);
	}

	/** A menu item drawn white on the menu's dark grey, whatever the theme. */
	private static final class ItemUI extends BasicMenuItemUI {

		@Override
		protected void installDefaults() {
			super.installDefaults();
			selectionBackground = HIGHLIGHT;
			selectionForeground = ToolTipStyle.TEXT;
			disabledForeground  = DISABLED_TEXT;
			// Before the HTML of an ability's label is laid out, so it is laid out in white.
			menuItem.setBackground(ToolTipStyle.BACKGROUND);
			menuItem.setForeground(ToolTipStyle.TEXT);
			menuItem.setOpaque(true);
			// Classic edges the row under the pointer in two tones of its own; the menu's frame is
			// the only edge here. The room the edge took is kept, so the rows keep their size.
			Border edge = menuItem.getBorder();
			if (edge != null) menuItem.setBorder(new EmptyBorder(edge.getBorderInsets(menuItem)));
		}

		/** Only an option that can be taken lights up under the pointer. */
		@Override
		protected void paintBackground(Graphics g, JMenuItem item, Color bgColor) {
			super.paintBackground(g, item, item.isEnabled() ? bgColor : item.getBackground());
		}

		/**
		 * Basic draws a disabled item's text in the theme's own colour, read past this renderer's,
		 * and that grey is meant for a light menu. Generic CP circles are set in their own font
		 * ({@link GenericCp}), as the HTML labels set them.
		 */
		@Override
		protected void paintText(Graphics g, JMenuItem item, Rectangle textRect, String text) {
			if (item.isEnabled()) {
				super.paintText(g, item, textRect, text);
				return;
			}
			Graphics2D g2 = (Graphics2D) g;
			Font font = item.getFont();
			Font circleFont = GenericCp.font(font);
			float x = textRect.x;
			float y = textRect.y + item.getFontMetrics(font).getAscent();
			g2.setColor(disabledForeground);
			int start = 0;
			for (int i = 0; i <= text.length(); i++) {
				boolean circle = i < text.length() && circleFont != null && GenericCp.isCircle(text.charAt(i));
				if (i < text.length() && !circle) continue;
				x = drawRun(item, g2, text.substring(start, i), font, x, y);
				if (circle) x = drawRun(item, g2, text.substring(i, i + 1), circleFont, x, y);
				start = i + 1;
			}
		}

		/** Draws {@code run} in {@code font} at {@code x}, and returns where the next run starts. */
		private static float drawRun(JMenuItem item, Graphics2D g, String run, Font font, float x, float y) {
			if (run.isEmpty()) return x;
			g.setFont(font);
			BasicGraphicsUtils.drawString(item, g, run, x, y);
			return x + item.getFontMetrics(font).stringWidth(run);
		}
	}
}
