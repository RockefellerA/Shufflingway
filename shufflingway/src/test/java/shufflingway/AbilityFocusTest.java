package shufflingway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * The auto ability in focus ({@link MainWindow#withAbilityFocus}): which card glows while it
 * chooses or resolves, and the line a target prompt shows under "Choose 1 Forward" to say what the
 * pick is for.
 */
class AbilityFocusTest {

	private static CardData forward(String name, int cost, int power) {
		return new CardData(null, name, "Lightning", cost, power, "Forward", false, 0, false, false,
				Set.of(), 0, List.of(), null, List.of(),
				List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
				false, false, null, false, false, false, false, false, 1,
				null, null, null, "");
	}

	@Test
	void theChooseSentenceAnEffectOpensWithIsLeftToThePrompt() {
		assertEquals("Deal it 10000 damage.",
				MainWindow.afterLeadingChoose("choose 1 Forward. Deal it 10000 damage."));
		assertEquals("It gains +1000 power until the end of the turn.",
				MainWindow.afterLeadingChoose("Choose 1 Forward. It gains +1000 power until the end of the turn."));
		assertEquals("", MainWindow.afterLeadingChoose("Choose 1 Forward of cost 6 or more."),
				"nothing but the choice: the prompt already says it all");
		assertEquals("Draw 1 card. Then, choose 1 Forward. Dull it.",
				MainWindow.afterLeadingChoose("Draw 1 card. Then, choose 1 Forward. Dull it."),
				"a choice in the middle of an effect is shown with the whole effect");
	}

	@Test
	void theCaptionNamesTheInnermostAbilityAndRestoresTheOuterOne() {
		MainWindow mw = new MainWindow();
		CardData glauca = forward("Glauca", 4, 8000);
		CardData ravus  = forward("Ravus", 5, 9000);
		assertNull(mw.abilityFocusCaption(), "nothing in focus, nothing to say");

		mw.runWithAbilityFocus(ravus, "you may remove 2 Job Captain in your Break Zone from the game. "
				+ "When you do so, choose 1 Forward. Deal it 10000 damage.", () -> {
			assertEquals("Ravus: you may remove 2 Job Captain in your Break Zone from the game. "
					+ "When you do so, choose 1 Forward. Deal it 10000 damage.", mw.abilityFocusCaption());
			// The payoff chooses its target inside the parent's resolution.
			mw.runWithAbilityFocus(ravus, "choose 1 Forward. Deal it 10000 damage.",
					() -> assertEquals("Ravus: Deal it 10000 damage.", mw.abilityFocusCaption()));
			mw.runWithAbilityFocus(glauca, "choose 1 Forward. It gains +1000 power until the end of the turn.",
					() -> assertEquals("Glauca: It gains +1000 power until the end of the turn.",
							mw.abilityFocusCaption()));
			assertEquals("Ravus: you may remove 2 Job Captain in your Break Zone from the game. "
					+ "When you do so, choose 1 Forward. Deal it 10000 damage.", mw.abilityFocusCaption(),
					"the outer ability is back in focus once the inner one is done");
		});
		assertNull(mw.abilityFocusCaption());
	}

	@Test
	void anAbilityThatThrowsStillLeavesFocus() {
		MainWindow mw = new MainWindow();
		try {
			mw.runWithAbilityFocus(forward("Glauca", 4, 8000), "choose 1 Forward. Break it.",
					() -> { throw new IllegalStateException("boom"); });
		} catch (IllegalStateException expected) {
			// The focus is restored on the way out regardless.
		}
		assertNull(mw.abilityFocusCaption());
	}

	@Test
	void aRealTriggerChoosesAndResolvesWithoutLeavingFocusBehind() throws Exception {
		// Glauca 11-091R: "When Glauca or a Forward enters your field, choose 1 Forward. It gains
		// +1000 power until the end of the turn." Her own arrival is the only Forward on the field,
		// so the choice makes itself and nothing waits on a click.
		CardData glauca = CardCorpus.bySerial("11-091R");
		Assumptions.assumeTrue(glauca != null, "needs shufflingway.db");
		MainWindow mw = new MainWindow();
		mw.gameState.getIdentity().put(glauca, true);
		mw.placeCardInForwardZone(glauca);
		while (!mw.gameState.getStack().isEmpty()) mw.resolveTopOfStack();

		assertEquals(glauca.power() + 1000, mw.effectiveP1ForwardPower(mw.p1ForwardCards.indexOf(glauca)),
				"the trigger resolved");
		assertNull(mw.abilityFocusCaption(), "and nothing is left in focus");
	}
}
