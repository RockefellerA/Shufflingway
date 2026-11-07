package shufflingway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import javax.imageio.ImageIO;
import javax.swing.Icon;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A Forward row is rebuilt with fresh labels whenever a card leaves it, and anything that reads a
 * slot in the same event — the floating damage number, the break animation of the next Forward a
 * sweep breaks — must see where the slot will be drawn and what it shows, not the row's origin and
 * a placeholder.
 */
class FieldSlotLayoutTest {

	private static JLabel slot() {
		JLabel l = new JLabel();
		l.setPreferredSize(new Dimension(100, 100));
		return l;
	}

	@Test
	void aSlotInARowRebuiltThisEventIsPlacedBeforeItsPositionIsRead() {
		// The row sits in a scroll pane, as the Forward rows do, in a window that is packed — so
		// it can be laid out — but never shown.
		JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
		JFrame window = new JFrame();
		try {
			JScrollPane scroll = new JScrollPane(row);
			scroll.setPreferredSize(new Dimension(600, 200));
			window.add(scroll, BorderLayout.CENTER);
			row.add(slot());
			row.add(slot());
			row.add(slot());
			window.pack();

			// The first Forward leaves: the row is rebuilt with two fresh labels.
			row.removeAll();
			JLabel first = slot(), second = slot();
			row.add(first);
			row.add(second);
			assertFalse(second.isValid());
			assertEquals(0, second.getX(), "unplaced, it reads as the row's origin");

			MainWindow.layOutIfUnplaced(second);

			assertEquals(100, second.getX(), "placed where the row will draw it");
			assertEquals(0, first.getX());
		} finally {
			window.dispose();
		}
	}

	private static CardData forwardWithArt(String name, String imageUrl) {
		return new CardData(imageUrl, name, "Fire", 2, 5000, "Forward", false, 0, false, true,
				Set.of(), 0, List.of(), null, List.of(),
				List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
				false, false, null, false, false, false, false, false, 1,
				null, null, null, "");
	}

	@Test
	void survivorsOfARowRebuildKeepTheirArtInsteadOfThePlaceholder(@TempDir Path dir) throws Exception {
		BufferedImage art = new BufferedImage(60, 84, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = art.createGraphics();
		g.setColor(new Color(40, 120, 200));
		g.fillRect(0, 0, 60, 84);
		g.dispose();
		Path png = dir.resolve("art.png");
		ImageIO.write(art, "png", png.toFile());
		String url = png.toUri().toString();
		// Loaded once up front, so the slots' renders all hit the memory cache instead of each
		// fetching the file and trying to store it against a card row that does not exist.
		ImageCache.load(url);

		// The leftmost is broken; the other two are copies of one printing, so equals() cannot
		// tell them apart and each must still get its own render back.
		CardData leaving = forwardWithArt("Leaving", url);
		CardData twin = forwardWithArt("Twin", url);
		CardData otherTwin = forwardWithArt("Twin", url);
		assertEquals(twin, otherTwin);

		MainWindow[] box = new MainWindow[1];
		SwingUtilities.invokeAndWait(() -> {
			MainWindow mw = new MainWindow();
			for (CardData c : List.of(leaving, twin, otherTwin)) {
				mw.gameState.getIdentity().put(c, false);
				mw.placeP2CardInForwardZone(c);
			}
			box[0] = mw;
		});
		MainWindow mw = box[0];

		// Wait out the entry animations and the background renders.
		long deadline = System.currentTimeMillis() + 15_000;
		boolean[] settled = { false };
		while (!settled[0] && System.currentTimeMillis() < deadline) {
			Thread.sleep(50);
			SwingUtilities.invokeAndWait(() -> {
				boolean all = true;
				for (CardData c : List.of(leaving, twin, otherTwin)) {
					JLabel slot = mw.findFieldSlotLabel(c, false);
					all &= slot != null && slot.getClientProperty(MainWindow.SLOT_ART)
							instanceof MainWindow.SlotArt a && a.card() == c;
				}
				settled[0] = all;
			});
		}
		assertTrue(settled[0], "every slot finished rendering its card");

		SwingUtilities.invokeAndWait(() -> {
			JLabel twinSlot = mw.findFieldSlotLabel(twin, false);
			Icon twinArt = twinSlot.getIcon();
			Icon otherTwinArt = mw.findFieldSlotLabel(otherTwin, false).getIcon();
			assertNotSame(twinArt, otherTwinArt);
			assertEquals(0, mw.findFieldSlot(leaving, false).idx());

			// One event, as a sweep is: the break rebuilds the row, and what the survivors show
			// right then is what the next break in the sweep would animate.
			mw.breakP2Forward(0);

			assertEquals(0, mw.findFieldSlot(twin, false).idx(), "the survivors shifted left");
			assertNotSame(twinSlot, mw.findFieldSlotLabel(twin, false), "the row was rebuilt with fresh labels");
			assertSame(twinArt, mw.findFieldSlotLabel(twin, false).getIcon());
			assertSame(otherTwinArt, mw.findFieldSlotLabel(otherTwin, false).getIcon());
		});
	}
}
