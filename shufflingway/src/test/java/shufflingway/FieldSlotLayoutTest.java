package shufflingway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;

import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;

import org.junit.jupiter.api.Test;

/**
 * A Forward row is rebuilt with fresh labels whenever a card leaves it, and anything that reads a
 * slot's position in the same event — the floating damage number, the break animation — must see
 * where the slot will be drawn, not the row's origin.
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
}
