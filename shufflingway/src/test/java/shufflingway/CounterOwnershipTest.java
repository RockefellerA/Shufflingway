package shufflingway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import shufflingway.GameState.CounterPile;
import shufflingway.graphics.CounterOrb;

/**
 * Counters remember who placed them, so the board can draw each player's in that player's color.
 * The rules never ask: everything the engine reads stays a count by name.
 */
class CounterOwnershipTest {

	private static CardData forward(String name) {
		return new CardData(null, name, "Fire", 3, 7000, "Forward", false, 0, false, false,
				Set.of(), 0, List.of(), null, List.of(),
				List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
				List.of(), List.of(), List.of(),
				false, false, null, false, false, false, false, false, 1,
				null, null, null, "");
	}

	@Test
	void eachPlayersCountersOfOneNameFormTheirOwnPile() {
		GameState gs = new GameState();
		CardData card = forward("Target");
		gs.placeCounters(card, "Poison", 1, true);
		gs.placeCounters(card, "Shuriken", 2, true);
		gs.placeCounters(card, "Poison", 1, false);

		assertEquals(List.of(new CounterPile("Poison", true, 1), new CounterPile("Poison", false, 1),
				new CounterPile("Shuriken", true, 2)), gs.getCounterPiles(card));
	}

	@Test
	void theEngineStillReadsCountsByNameWhoeverPlacedThem() {
		GameState gs = new GameState();
		CardData card = forward("Target");
		gs.placeCounters(card, "Poison", 1, true);
		gs.placeCounters(card, "Poison", 2, false);

		assertEquals(3, gs.getCounters(card, "Poison"));
		assertEquals(Map.of("Poison", 3), gs.getCountersMap(card));
	}

	@Test
	void thePlayerWhoPlacedFirstComesFirst() {
		GameState gs = new GameState();
		CardData card = forward("Target");
		gs.placeCounters(card, "Poison", 2, false);
		gs.placeCounters(card, "Poison", 1, true);

		assertEquals(List.of(new CounterPile("Poison", false, 2), new CounterPile("Poison", true, 1)),
				gs.getCounterPiles(card));
	}

	@Test
	void removalTakesTheNewestCountersFirst() {
		GameState gs = new GameState();
		CardData card = forward("Target");
		gs.placeCounters(card, "Poison", 2, true);
		gs.placeCounters(card, "Poison", 1, false);
		gs.placeCounters(card, "Poison", 1, true);

		assertEquals(2, gs.removeCounters(card, "Poison", 2));
		assertEquals(List.of(new CounterPile("Poison", true, 2)), gs.getCounterPiles(card),
				"the last P1 counter and the P2 one under it came off; the first two P1 counters stay");
		assertEquals(2, gs.removeCounters(card, "Poison", 5));
		assertEquals(List.of(), gs.getCounterPiles(card));
		assertEquals(Map.of(), gs.getCountersMap(card));
	}

	@Test
	void whatACardHadWhenItLeftIsStillCountedByName() {
		GameState gs = new GameState();
		CardData card = forward("Target");
		gs.placeCounters(card, "Brainwashing", 1, true);
		gs.placeCounters(card, "Brainwashing", 1, false);
		gs.clearCounters(card);

		assertEquals(2, gs.getCountersWhenLeftField(card, "Brainwashing"));
		assertEquals(List.of(), gs.getCounterPiles(card));
	}

	@Test
	void anEffectsCountersAreItsControllers() {
		MainWindow mw = new MainWindow();
		CardData card = forward("Target");
		new GameContextImpl(mw, false, false).placeCounters(card, "Poison", 1);
		new GameContextImpl(mw, true, false).placeCounters(card, "Poison", 2);

		assertEquals(List.of(new CounterPile("Poison", false, 1), new CounterPile("Poison", true, 2)),
				mw.gameState.getCounterPiles(card));
	}

	@Test
	void eachOrbIsDrawnInItsPlacersColor() {
		MainWindow mw = new MainWindow();
		List<CounterOrb> orbs = mw.counterOrbs(List.of(
				new CounterPile("Poison", true, 1), new CounterPile("Shuriken", false, 2)));

		assertEquals(List.of(new CounterOrb(1, mw.counterColorFor(true)), new CounterOrb(2, mw.counterColorFor(false))), orbs);
		assertNotEquals(orbs.get(0).hex(), orbs.get(1).hex());
	}

	@Test
	void theTooltipSaysWhoseOnlyWhereBothPlayersHaveCountersOfAName() {
		String tip = MainWindow.buildCounterTooltip(List.of(
				new CounterPile("Poison", true, 1), new CounterPile("Poison", false, 1),
				new CounterPile("Shuriken", false, 3)));

		assertEquals("<html>Poison ×1 (Yours)<br>Poison ×1 (Theirs)<br>Shuriken ×3<br></html>", tip);
	}
}
