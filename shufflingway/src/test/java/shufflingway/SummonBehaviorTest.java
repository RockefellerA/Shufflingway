package shufflingway;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static shufflingway.TestCards.*;

import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

/**
 * Behaviour tests for Summons, one section per card, against a real {@link MainWindow} wherever
 * the effect touches the board.
 *
 * <p>Split out of {@link CardBehaviorTest} so a Summon's tests can be found by name. Resolve on
 * P2's seat, or answer prompts through a spy: a P1 choice opens a modal dialog and hangs the JVM.
 */
class SummonBehaviorTest {

	// =========================================================================================
	// 2-087R Hashmal, Bringer of Order: "Name 1 Job or 1 Element. Until the end of the turn, all
	// Forwards you control gain +1000 power and the named Job or Element."
	//
	// It parsed, and the power landed, but the rest did nothing. The Job went to a per-slot row
	// nothing read, so a Forward that gained Warrior matched no Job Warrior filter. The Element
	// was written as an override ("becomes"), so a Fire Forward that gained Ice was Ice alone.
	// =========================================================================================

	private static final String HASHMAL_2_087R = "Name 1 Job or 1 Element. Until the end of the turn, "
			+ "all Forwards you control gain +1000 power and the named Job or Element.";

	/** P2's board with two Forwards, Hashmal resolved with {@code named} as the answer to the prompt. */
	private static MainWindow resolveHashmal(String kind, String named, CardData... forwards) {
		MainWindow mw = new MainWindow();
		for (CardData f : forwards) placeP2Forward(mw, f);
		placeP1Forward(mw, makeForward("Theirs", "Fire", 3, 7000));
		GameContext ctx = spy(mw.buildGameContext(false));
		doReturn(new String[] { kind, named }).when(ctx).selectJobOrElement(anyString());
		Consumer<GameContext> fn = ActionResolver.parse(HASHMAL_2_087R,
				makeSummon("Hashmal, Bringer of Order", "Earth", 5, HASHMAL_2_087R));
		assertNotNull(fn);
		fn.accept(ctx);
		return mw;
	}

	@Test
	void hashmalGrantsTheNamedJobToEveryForwardYouControl() {
		CardData a = makeForward("A", "Fire", 3, 7000);
		CardData b = makeForward("B", "Water", 2, 5000);
		MainWindow mw = resolveHashmal("job", "Warrior", a, b);

		assertTrue(mw.meetsJobFilterEffective(a, "Warrior"));
		assertTrue(mw.meetsJobFilterEffective(b, "Warrior"));
		assertTrue(mw.meetsJobFilterEffective(a, "Knight|Warrior"), "as one of several alternatives");
		assertFalse(mw.meetsJobFilterEffective(mw.p1ForwardCards.get(0), "Warrior"), "yours only");
		assertEquals(8000, mw.effectiveP2ForwardPower(0));
		assertEquals(6000, mw.effectiveP2ForwardPower(1));
	}

	@Test
	void hashmalsElementIsGainedAlongsideTheForwardsOwn() {
		CardData a = makeForward("A", "Fire", 3, 7000);
		MainWindow mw = resolveHashmal("element", "Ice", a);

		assertTrue(mw.effectiveContainsElement(a, "Ice"));
		assertTrue(mw.effectiveContainsElement(a, "Fire"), "gain, not become");
		assertEquals(List.of("Fire", "Ice"), mw.effectiveElements(a));
		assertTrue(mw.effectiveContainsElement(a, "Multi-Element"));
	}

	@Test
	void hashmalsGrantsEndAtTheEndOfTheTurn() {
		CardData a = makeForward("A", "Fire", 3, 7000);
		MainWindow mw = resolveHashmal("element", "Ice", a);
		mw.fireEndOfTurnEffects(false);
		assertFalse(mw.effectiveContainsElement(a, "Ice"));
		assertEquals(List.of("Fire"), mw.effectiveElements(a));
	}

	@Test
	void hashmalsElementReachesOnlyTheCopyOnTheField() {
		// CardData is a record: two copies of one printing are equal. The grant is by identity.
		CardData onField = makeForward("Twin", "Fire", 3, 7000);
		CardData inHand = makeForward("Twin", "Fire", 3, 7000);
		MainWindow mw = resolveHashmal("element", "Ice", onField);
		assertTrue(mw.effectiveContainsElement(onField, "Ice"));
		assertFalse(mw.effectiveContainsElement(inHand, "Ice"));
	}
}
