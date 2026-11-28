package shufflingway.graphics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import javax.swing.JMenuItem;
import javax.swing.UIManager;

import org.junit.jupiter.api.Test;

class CardActionMenuTest {

	@Test
	void anItemTakesTheTooltipLookAsItIsAdded() {
		CardActionMenu menu = new CardActionMenu();
		JMenuItem item = menu.add(new JMenuItem("Play"));
		assertEquals(ToolTipStyle.BACKGROUND, menu.getBackground());
		assertEquals(ToolTipStyle.BACKGROUND, item.getBackground());
		assertEquals(ToolTipStyle.TEXT, item.getForeground());
	}

	@Test
	void theRowUnderThePointerLightsUp() {
		JMenuItem play = new JMenuItem("Play");
		new CardActionMenu().add(play);
		play.setArmed(true);
		assertEquals(CardActionMenu.HIGHLIGHT, cornerColour(play));
	}

	// An option that cannot be taken stays dark under the pointer, in a theme that lets the
	// pointer rest on one too.
	@Test
	void aDisabledRowDoesNotLightUp() {
		Object navigable = UIManager.get("MenuItem.disabledAreNavigable");
		UIManager.put("MenuItem.disabledAreNavigable", Boolean.TRUE);
		try {
			JMenuItem warp = new JMenuItem("Play (Warp 2)");
			warp.setEnabled(false);
			new CardActionMenu().add(warp);
			warp.setArmed(true);
			assertTrue(warp.isArmed(), "the pointer is resting on it");
			assertEquals(ToolTipStyle.BACKGROUND, cornerColour(warp));
		} finally {
			UIManager.put("MenuItem.disabledAreNavigable", navigable);
		}
	}

	/** The colour {@code item} paints just inside its top-left corner, clear of its text. */
	private static Color cornerColour(JMenuItem item) {
		item.setSize(item.getPreferredSize());
		BufferedImage img = new BufferedImage(item.getWidth(), item.getHeight(), BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		item.paint(g);
		g.dispose();
		return new Color(img.getRGB(1, 1));
	}
}
