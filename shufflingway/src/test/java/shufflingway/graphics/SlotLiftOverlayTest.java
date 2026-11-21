package shufflingway.graphics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;

/**
 * A lift is drawn towards the centre of the board: P1's slots rise above their row and P2's blocker
 * drops below its own. Everything that follows a lifted card — the overlay's own painting, where a
 * break animation starts, where a damage number floats from — reads {@link SlotLiftOverlay#offset}
 * as "how far above", so a lowered slot has to report a negative one.
 */
class SlotLiftOverlayTest {

	private static final int MAX_LIFT = 12;

	@Test
	void aRaisedSlotSettlesAboveItsRow() throws Exception {
		SlotLiftOverlay overlay = new SlotLiftOverlay(MAX_LIFT);
		JLabel slot = slotInARow();
		Object card = new Object();

		onEdt(() -> overlay.setLifted(card, slot, true));

		List<Integer> seen = offsetsUntil(overlay, slot, MAX_LIFT);
		assertTrue(seen.stream().allMatch(o -> o >= 0), "never below the row on the way up: " + seen);
	}

	@Test
	void aLoweredSlotSettlesBelowItsRow() throws Exception {
		SlotLiftOverlay overlay = new SlotLiftOverlay(MAX_LIFT);
		JLabel slot = slotInARow();
		Object card = new Object();

		onEdt(() -> overlay.setLifted(card, slot, true, true));

		List<Integer> seen = offsetsUntil(overlay, slot, -MAX_LIFT);
		assertTrue(seen.stream().allMatch(o -> o <= 0), "never above the row on the way down: " + seen);
		boolean[] lifted = { false };
		onEdt(() -> lifted[0] = overlay.isLifted(slot));
		assertTrue(lifted[0], "the overlay is painting it, so the label must not");
	}

	@Test
	void aLoweredSlotComesBackUpTheWayItWentDown() throws Exception {
		SlotLiftOverlay overlay = new SlotLiftOverlay(MAX_LIFT);
		JLabel slot = slotInARow();
		Object card = new Object();
		onEdt(() -> overlay.setLifted(card, slot, true, true));
		offsetsUntil(overlay, slot, -MAX_LIFT);

		// Settled through the plain overload, which knows nothing of direction.
		onEdt(() -> overlay.setLifted(card, slot, false));

		List<Integer> seen = offsetsUntil(overlay, slot, 0);
		assertTrue(seen.stream().allMatch(o -> o <= 0), "it does not jump above the row to settle: " + seen);
		boolean[] lifted = { true };
		onEdt(() -> lifted[0] = overlay.isLifted(slot));
		assertFalse(lifted[0], "back in its row, the label paints itself again");
	}

	/** A slot with a parent: one without is taken for a card that has left the field and dropped. */
	private static JLabel slotInARow() {
		JLabel slot = new JLabel();
		new JPanel().add(slot);
		return slot;
	}

	/** Samples {@code slot}'s offset as the lift's timer moves it, until it reaches {@code goal}. */
	private static List<Integer> offsetsUntil(SlotLiftOverlay overlay, JLabel slot, int goal) throws Exception {
		List<Integer> seen = new ArrayList<>();
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (System.nanoTime() < deadline) {
			int[] offset = { 0 };
			onEdt(() -> offset[0] = overlay.offset(slot));
			seen.add(offset[0]);
			if (offset[0] == goal) return seen;
			Thread.sleep(5);
		}
		fail("never reached " + goal + "; saw " + seen);
		return seen;
	}

	private static void onEdt(Runnable r) throws Exception {
		SwingUtilities.invokeAndWait(r);
	}
}
