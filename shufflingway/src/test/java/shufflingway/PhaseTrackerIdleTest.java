package shufflingway;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import org.junit.jupiter.api.Test;

/** {@link PhaseTracker}'s idle state: what the track shows until a game's first player is decided. */
class PhaseTrackerIdleTest {

	@Test
	void aNewTrackerIsIdleUntilItsFirstPhase() {
		PhaseTracker tracker = new PhaseTracker();
		assertTrue(tracker.isIdle());
		assertNull(tracker.getPhase());
		assertEquals(0, tracker.getTurn());

		tracker.setState("Active", 1, false);
		assertFalse(tracker.isIdle());
		assertEquals("Active", tracker.getPhase());
		assertEquals(1, tracker.getTurn());
	}

	@Test
	void clearingAGameReturnsTheTrackToTurnZeroWithNoPhase() {
		PhaseTracker tracker = new PhaseTracker();
		tracker.setState("Attack", 7, true);
		tracker.setAttackStep(2);

		tracker.clearGame();

		assertTrue(tracker.isIdle());
		assertNull(tracker.getPhase());
		assertEquals(0, tracker.getTurn());
		assertEquals(0, tracker.getAttackStep());

		// The next game's first phase is entered fresh, not skipped as unchanged.
		tracker.setState("Active", 1, true);
		assertEquals("Active", tracker.getPhase());
	}

	@Test
	void theTurnShowsOnceHandsAreDealtAndThePillOnceTheFirstPlayerIsDecided() {
		PhaseTracker tracker = new PhaseTracker();
		tracker.updateUI();
		tracker.setSize(480, tracker.getPreferredSize().height);
		assertEquals(0, inkedTurnSide(tracker), "no turn number before a game");
		assertEquals(0, inkedPillSide(tracker), "no turn pill before a game");

		tracker.beginDeal();
		assertTrue(tracker.isIdle());
		assertEquals(0, tracker.getTurn());
		assertTrue(inkedTurnSide(tracker) > 0, "TURN 0 while the opening hands are decided");
		assertEquals(0, inkedPillSide(tracker), "no turn pill until the first player is decided");

		tracker.setState("Active", 1, true);
		assertTrue(inkedTurnSide(tracker) > 0);
		assertTrue(inkedPillSide(tracker) > 0, "the pill once the first turn begins");

		tracker.clearGame();
		assertEquals(0, inkedTurnSide(tracker));
		assertEquals(0, inkedPillSide(tracker));
	}

	/** The left half of the top strip, where "TURN N" is written. */
	private static int inkedTurnSide(PhaseTracker tracker) {
		return inkedInTopStrip(tracker, 0, tracker.getWidth() / 2);
	}

	/** The right half of the top strip, where the turn pill sits. */
	private static int inkedPillSide(PhaseTracker tracker) {
		return inkedInTopStrip(tracker, tracker.getWidth() / 2, tracker.getWidth());
	}

	/** Pixels in columns [x0, x1) of the top strip (PAD_TOP 8 + TOP_STRIP_H 22) that are not the background. */
	private static int inkedInTopStrip(PhaseTracker tracker, int x0, int x1) {
		BufferedImage img = new BufferedImage(tracker.getWidth(), tracker.getHeight(), BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		tracker.paint(g);
		g.dispose();
		int background = tracker.getBackground().getRGB() & 0xffffff;
		int inked = 0;
		for (int y = 0; y < 8 + 22; y++)
			for (int x = x0; x < x1; x++)
				if ((img.getRGB(x, y) & 0xffffff) != background) inked++;
		return inked;
	}
}
