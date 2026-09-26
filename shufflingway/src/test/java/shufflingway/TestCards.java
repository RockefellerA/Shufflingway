package shufflingway;

import java.util.List;
import java.util.Set;

/**
 * Card builders and board placement shared across behaviour test classes.
 *
 * <p>{@link CardBehaviorTest} still carries private copies of these; new test classes use this one
 * so they do not have to reach into a 65,000-line file for a Forward.
 */
final class TestCards {

	private TestCards() {}

	static CardData makeForward(String name, String element, int cost, int power) {
		return new CardData(null, name, element, cost, power, "Forward", false, 0, false, false,
				Set.of(), 0, List.of(), null, List.of(),
				List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
				false, false, null, false, false, false, false, false, 1,
				null, null, null, "");
	}

	static CardData makeSummon(String name, String element, int cost, String text) {
		return new CardData(null, name, element, cost, 0, "Summon", false, 0, false, false,
				Set.of(), 0, List.of(), null, List.of(),
				List.of(), List.of(),
				List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
				false, false, null, false, false, false, false, false, 1,
				null, null, null, text);
	}

	/** Seats {@code card} on P1's Forward row with its owner recorded, as a real game would. */
	static void placeP1Forward(MainWindow mw, CardData card) {
		mw.gameState.getIdentity().put(card, true);
		mw.placeCardInForwardZone(card);
	}

	/** Seats {@code card} on P2's Forward row. Fires its enters-field triggers, as play would. */
	static void placeP2Forward(MainWindow mw, CardData card) {
		mw.gameState.getIdentity().put(card, false);
		mw.placeP2CardInForwardZone(card);
	}
}
