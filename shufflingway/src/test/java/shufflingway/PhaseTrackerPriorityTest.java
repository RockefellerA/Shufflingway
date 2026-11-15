package shufflingway;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/** {@link PhaseTracker#setPriorityListener}: what the board's priority glow is told, and when. */
class PhaseTrackerPriorityTest {

	@Test
	void theListenerHearsWhoHoldsPriorityNowAndEachTimeItMoves() {
		PhaseTracker tracker = new PhaseTracker();
		List<Boolean> heard = new ArrayList<>();
		tracker.setPriorityListener(heard::add);

		tracker.setHasPriority(false);
		tracker.setHasPriority(false);   // no change: nothing to tell
		tracker.setMyTurn(false);        // priority was already the opponent's
		tracker.setMyTurn(true);         // the turn passing brings it back

		assertEquals(List.of(true, false, true), heard);
		assertTrue(tracker.hasPriority());
	}
}
