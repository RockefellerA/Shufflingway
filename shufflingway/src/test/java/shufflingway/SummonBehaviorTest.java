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

	// =========================================================================================
	// 7-084C Yojimbo: "Choose 1 Forward you control and 1 Forward opponent controls. The former
	// gains +1000 power until the end of the turn. Then, each Forward deals damage equal to its
	// power to the other. If Yojimbo results from an EX Burst, the former gains +3000 power until
	// the end of the turn instead. Then, each Forward deals damage equal to its power to the other."
	//
	// The card spells the effect out once per case, so the mutual-damage sentence appears twice;
	// there is still one boost and one fight. The mutual-damage followup find()'d that sentence
	// and dropped the boost.
	// =========================================================================================

	private static final String YOJIMBO_7_084C = "Choose 1 Forward you control and 1 Forward opponent "
			+ "controls. The former gains +1000 power until the end of the turn. Then, each Forward deals "
			+ "damage equal to its power to the other. If Yojimbo results from an EX Burst, the former "
			+ "gains +3000 power until the end of the turn instead. Then, each Forward deals damage equal "
			+ "to its power to the other.";

	/** P2 casts Yojimbo: its 7000 Forward against P1's 9000 one. */
	private static MainWindow castYojimbo(boolean exBurst, CardData mine, CardData theirs) {
		MainWindow mw = new MainWindow();
		placeP2Forward(mw, mine);
		placeP1Forward(mw, theirs);
		Consumer<GameContext> fn = ActionResolver.parse(YOJIMBO_7_084C,
				makeSummon("Yojimbo", "Earth", 4, YOJIMBO_7_084C));
		assertNotNull(fn);
		fn.accept(mw.buildGameContext(false, exBurst));
		return mw;
	}

	@Test
	void yojimboBoostsTheFormerBy1000ThenTheyFightOnce() {
		CardData mine = makeForward("Mine", "Earth", 3, 7000);
		CardData theirs = makeForward("Theirs", "Fire", 4, 9000);
		MainWindow mw = castYojimbo(false, mine, theirs);

		// 8000 into a 9000: it survives with 8000 damage. 9000 back into an 8000: broken.
		assertTrue(mw.p1ForwardCards.contains(theirs));
		assertEquals(8000, mw.p1ForwardDamage.get(mw.p1ForwardCards.indexOf(theirs)),
				"the boost is in the damage dealt, and it is dealt once");
		assertFalse(mw.p2ForwardCards.contains(mine));
	}

	@Test
	void yojimboFromAnExBurstBoostsBy3000Instead() {
		CardData mine = makeForward("Mine", "Earth", 3, 7000);
		CardData theirs = makeForward("Theirs", "Fire", 4, 9000);
		MainWindow mw = castYojimbo(true, mine, theirs);

		// 10000 into a 9000: broken. 9000 back into a 10000: it survives.
		assertFalse(mw.p1ForwardCards.contains(theirs));
		assertTrue(mw.p2ForwardCards.contains(mine));
		assertEquals(9000, mw.p2ForwardDamage.get(mw.p2ForwardCards.indexOf(mine)));
		assertEquals(10000, mw.effectiveP2ForwardPower(mw.p2ForwardCards.indexOf(mine)));
	}

	// =========================================================================================
	// 29-116H Madeen: "Madeen cannot be cancelled." The sentence was skipped by the parser (a find()
	// past it) and read by nothing: the Stack's cancel protection answered only for abilities
	// (Yoran-Oran 29-075H) and refused every Summon outright.
	// =========================================================================================

	private static final String MADEEN_29_116H = "Madeen cannot be cancelled.[[br]]Choose 1 Forward opponent "
			+ "controls. You may search for 1 Light Forward and remove it from the game. If you do so, remove "
			+ "the chosen Forward from the game. If not, break the chosen Forward.";

	private static final String CANCEL_A_SUMMON = "Choose 1 Summon or auto-ability. Cancel its effect.";

	@Test
	void madeenCarriesItsCancelProtectionAndResolvesWithoutTheSentence() {
		CardData madeen = makeSummon("Madeen", "Light", 3, MADEEN_29_116H);
		assertTrue(madeen.cannotBeCancelled());
		assertTrue(madeen.summonEffect().startsWith("Choose 1 Forward opponent controls."),
				"a property of the card, not part of what it does: " + madeen.summonEffect());
		assertFalse(makeSummon("Shiva", "Ice", 2, "Draw 1 card.").cannotBeCancelled());
		assertFalse(makeSummon("Odd", "Ice", 2, "Madeen cannot be cancelled. Draw 1 card.").cannotBeCancelled(),
				"only a sentence naming the card itself");
	}

	@Test
	void anOrdinarySummonOnTheStackCanBeCancelled() {
		MainWindow mw = new MainWindow();
		CardData shiva = makeSummon("Shiva", "Ice", 2, "Draw 1 card.");
		mw.pushSummonOnStack(shiva, true, 0, 0, false, null, false);
		StackEntry entry = mw.gameState.getStack().get(0);

		ActionResolver.parse(CANCEL_A_SUMMON, makeSummon("Canceller", "Water", 2, CANCEL_A_SUMMON))
				.accept(mw.buildGameContext(false));

		assertTrue(mw.cancelledStackEntries.contains(entry));
	}

	@Test
	void madeenOnTheStackCannotBeCancelled() {
		MainWindow mw = new MainWindow();
		CardData madeen = makeSummon("Madeen", "Light", 3, MADEEN_29_116H);
		mw.pushSummonOnStack(madeen, true, 0, 0, false, null, false);
		StackEntry entry = mw.gameState.getStack().get(0);
		assertSame(madeen, entry.source());

		ActionResolver.parse(CANCEL_A_SUMMON, makeSummon("Canceller", "Water", 2, CANCEL_A_SUMMON))
				.accept(mw.buildGameContext(false));

		assertFalse(mw.cancelledStackEntries.contains(entry));
		assertFalse(mw.cancelStackEntry(entry), "and refused however the cancel arrives");
		assertFalse(mw.cancelledStackEntries.contains(entry));
	}

	@Test
	void neonCanStillBlankMadeensDamageBecauseThatIsNotACancel() {
		// Madeen's protection is against cancels only. Neon's "the damage becomes 0" lets the
		// Summon resolve, so a cannot-be-cancelled Summon stays within its reach.
		MainWindow mw = new MainWindow();
		CardData madeen = makeSummon("Madeen", "Light", 3, MADEEN_29_116H);
		mw.pushSummonOnStack(madeen, true, 0, 0, false, null, false);
		String neon = "Choose 1 Summon or auto-ability. During this turn, if it deals damage to a Forward or "
				+ "a player, the damage becomes 0 instead.";

		ActionResolver.parse(neon, makeForward("Neon", "Water", 3, 7000)).accept(mw.buildGameContext(false));

		assertTrue(mw.damageZeroedSourcesThisTurn.contains(madeen));
		assertTrue(mw.cancelledStackEntries.isEmpty(), "and it is still not cancelled");
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
