package shufflingway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import shufflingway.dialog.StackOrderingDialog;

/**
 * P1's simultaneous triggers go on the Stack in the order the stack-ordering dialog numbered them:
 * the one numbered 1st resolves first, and is also the first to make its push-time choices.
 */
class TriggerOrderTest {

	private static CardData card(String name, String type, String text) {
		return new CardData(null, name, "Lightning", 2, "Forward".equals(type) ? 5000 : 0, type,
				false, 0, false, false,
				Set.of(), 0, List.of(), null, List.of(),
				List.of(), CardData.parseAutoAbilities(text), List.of(), List.of(), List.of(),
				List.of(), List.of(), List.of(), List.of(), List.of(),
				false, false, null, false, false, false, false, false, 1,
				null, null, null, text);
	}

	@Test
	void theAbilityNumberedFirstGoesOnTheStackFirstAndResolvesFirst() {
		MainWindow mw = new MainWindow();
		for (int i = 0; i < 5; i++) mw.gameState.getP1MainDeck().add(card("Filler " + i, "Forward", ""));
		// A watcher already on the field and the arriving Forward's own trigger: two at once, and
		// neither asks anything as it goes on the Stack.
		mw.placeCardInFirstBackupSlot(card("Watcher", "Backup", "When a Forward enters your field, draw 1 card."));

		// Number them against the order they were collected in, so following the collection order
		// by mistake cannot pass.
		List<String> offered = new ArrayList<>();
		mw.autoAbilityTriggers.stackOrderChooser = items -> {
			items.forEach(it -> offered.add(it.source().name()));
			List<StackOrderingDialog.Item> ordered = new ArrayList<>(items);
			Collections.reverse(ordered);
			return ordered;
		};
		mw.placeCardInForwardZone(card("Arrival", "Forward", "When Arrival enters the field, draw 1 card."));
		while (!mw.gameState.getStack().isEmpty()) mw.resolveTopOfStack();

		String log = mw.gameLogText();
		assertEquals(2, offered.size(), "both triggers were offered for ordering: " + offered + "\n" + log);
		String first = offered.get(1), second = offered.get(0);

		int firstPushed  = log.indexOf(first + " — pushed to stack");
		int secondPushed = log.indexOf(second + " — pushed to stack");
		assertTrue(firstPushed >= 0 && secondPushed >= 0, log);
		assertTrue(firstPushed < secondPushed,
				"the one numbered 1st goes on the Stack, and so makes its choices, before the 2nd\n" + log);

		int firstResolved  = log.indexOf("Resolving \"" + first + "\"");
		int secondResolved = log.indexOf("Resolving \"" + second + "\"");
		assertTrue(firstResolved >= 0 && secondResolved >= 0, log);
		assertTrue(firstResolved < secondResolved, "and resolves before it\n" + log);
	}
}
