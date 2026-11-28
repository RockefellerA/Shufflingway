package shufflingway.graphics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.awt.Font;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import javax.swing.ImageIcon;
import javax.swing.JLabel;

import org.junit.jupiter.api.Test;

class DraggableCardRowTest {

	private static final int W = 60, H = 80;
	private static final Font FONT = new Font("Dialog", Font.PLAIN, 10);

	private final List<Integer> hovered = new ArrayList<>();
	private int unhovers;

	private DraggableCardRow row(boolean reorderable) {
		DraggableCardRow row = new DraggableCardRow(5, W, H, FONT, reorderable);
		row.setSize(row.getPreferredSize());
		row.doLayout();
		row.setHoverListener(hovered::add, () -> unhovers++);
		return row;
	}

	private static JLabel slot(DraggableCardRow row, int i) {
		return (JLabel) row.getComponent(i);
	}

	private static int centreX(DraggableCardRow row, int i) {
		JLabel s = slot(row, i);
		return s.getX() + s.getWidth() / 2;
	}

	/** Takes hold of slot {@code from} at its centre and drags it, a few pixels at a time, to row x {@code toX}. */
	private static void dragTo(DraggableCardRow row, int from, int toX) {
		JLabel source = slot(row, from);
		int y = H / 2;
		send(source, MouseEvent.MOUSE_PRESSED, W / 2, y);
		int x = centreX(row, from);
		int step = toX > x ? 7 : -7;
		while (step > 0 ? x + step < toX : x + step > toX) {
			x += step;
			send(source, MouseEvent.MOUSE_DRAGGED, x - source.getX(), y);
		}
		send(source, MouseEvent.MOUSE_DRAGGED, toX - source.getX(), y);
	}

	/** Lets go of a drag that began on slot {@code from}, over row x {@code atX}. */
	private static void release(DraggableCardRow row, int from, int atX) {
		JLabel source = slot(row, from);
		send(source, MouseEvent.MOUSE_RELEASED, atX - source.getX(), H / 2);
	}

	/** Swing sends a whole drag, the release included, to the component the button went down on. */
	private static void send(JLabel target, int id, int x, int y) {
		int mods = id == MouseEvent.MOUSE_RELEASED ? 0 : InputEvent.BUTTON1_DOWN_MASK;
		target.dispatchEvent(new MouseEvent(target, id, 0, mods, x, y, 1, false, MouseEvent.BUTTON1));
	}

	@Test
	void aCardDraggedRightTakesTheSlotItIsDroppedOn() {
		DraggableCardRow row = row(true);
		int x = centreX(row, 2) + 1;
		dragTo(row, 0, x);
		release(row, 0, x);
		assertEquals(List.of(1, 2, 0, 3, 4), row.order());
	}

	@Test
	void aCardDraggedPastTheLeftEndGoesFirst() {
		DraggableCardRow row = row(true);
		dragTo(row, 4, -50);
		release(row, 4, -50);
		assertEquals(List.of(4, 0, 1, 2, 3), row.order());
	}

	// The others make room only once the card is halfway over one of them.
	@Test
	void aCardNotYetHalfwayOverItsNeighbourStaysPut() {
		DraggableCardRow row = row(true);
		int x = slot(row, 1).getX() - DraggableCardRow.DRAG_THRESHOLD - 3;
		dragTo(row, 0, x);
		release(row, 0, x);
		assertEquals(List.of(0, 1, 2, 3, 4), row.order());
	}

	@Test
	void aClickWithoutADragMovesNothing() {
		DraggableCardRow row = row(true);
		int x = centreX(row, 1) + DraggableCardRow.DRAG_THRESHOLD - 2;
		dragTo(row, 1, x);
		release(row, 1, x);
		assertEquals(List.of(0, 1, 2, 3, 4), row.order());
	}

	// "Take Hand", once the mulligan is spent: the order no longer matters.
	@Test
	void aRowThatIsNotReorderableIgnoresDrags() {
		DraggableCardRow row = row(false);
		int x = centreX(row, 3);
		dragTo(row, 0, x);
		release(row, 0, x);
		assertEquals(List.of(0, 1, 2, 3, 4), row.order());
	}

	// Two copies of one card are equal CardData, so the row reports places, never cards.
	@Test
	void hoverNamesTheCardByItsPlaceAndFallsQuietDuringADrag() {
		DraggableCardRow row = row(true);
		send(slot(row, 0), MouseEvent.MOUSE_ENTERED, W / 2, H / 2);
		assertEquals(List.of(0), hovered);

		int x = centreX(row, 2) + 1;
		dragTo(row, 0, x);
		assertEquals(1, unhovers, "picking a card up hides its preview");
		send(slot(row, 1), MouseEvent.MOUSE_ENTERED, W / 2, H / 2);
		assertEquals(List.of(0), hovered, "nothing passed over mid-drag is previewed");

		release(row, 0, x);
		assertEquals(List.of(0, 0), hovered, "dropped, the card under the pointer is the one just moved");
	}

	@Test
	void anImageArrivingAfterAMoveFindsItsCard() {
		DraggableCardRow row = row(true);
		int x = centreX(row, 2) + 1;
		dragTo(row, 0, x);
		release(row, 0, x);

		ImageIcon image = new ImageIcon(new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB));
		row.setImage(0, image);
		assertSame(image, slot(row, 2).getIcon());
		assertNull(slot(row, 0).getIcon());
		assertEquals("Loading...", slot(row, 0).getText());
	}

	// While a card is held, its slot is an empty hole: the card itself rides above the row.
	@Test
	void theHeldCardLeavesAHoleWhereItWillLand() {
		DraggableCardRow row = row(true);
		row.setImage(0, new ImageIcon(new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB)));
		dragTo(row, 0, centreX(row, 2) + 1);
		assertNull(slot(row, 2).getIcon());
		assertNull(slot(row, 2).getText());
	}
}
