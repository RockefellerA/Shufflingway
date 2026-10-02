package shufflingway;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static shufflingway.TestCards.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

/**
 * Behaviour tests for Summons, one section per card, against a real {@link MainWindow} wherever
 * the effect touches the board.
 *
 * <p>Sections run in serial order — set by set, then card number within a set — so a card's tests
 * are found by its serial, and a new set's Summons go on the end. Each section opens with a banner
 * naming the card and its printed text.
 *
 * <p>Summons resolve from P2's seat through {@link #castAsP2}: the CPU makes the choices, so each
 * board is built to leave it one legal target, with an illegal one beside it wherever the card
 * restricts what it may choose. A P1 choice opens a modal dialog and hangs the JVM, so an effect
 * that makes P1 choose is set up so P1 has only one answer, or answered through a spy.
 */
class SummonBehaviorTest {

	/** Resolves {@code summon}'s effect from P2's seat, as a cast would once it leaves the Stack. */
	private static void castAsP2(MainWindow mw, CardData summon) {
		castAsP2(mw, summon, false);
	}

	/**
	 * {@link #castAsP2}, or, with {@code exBurst}, as the Summon resolves off an EX Burst — the
	 * case an "If [name] results from an EX Burst, … instead." alternative reads.
	 */
	private static void castAsP2(MainWindow mw, CardData summon, boolean exBurst) {
		Consumer<GameContext> fn = ActionResolver.parse(summon.summonEffect(), summon);
		assertNotNull(fn, summon.name() + " parses");
		fn.accept(mw.buildGameContext(false, exBurst));
	}

	/**
	 * Resolves {@code summon}'s effect from P1's seat. Only for effects where P1 makes no choice —
	 * "your opponent selects …", which the CPU then answers.
	 */
	private static void castAsP1(MainWindow mw, CardData summon) {
		Consumer<GameContext> fn = ActionResolver.parse(summon.summonEffect(), summon);
		assertNotNull(fn, summon.name() + " parses");
		fn.accept(mw.buildGameContext(true));
	}

	/**
	 * {@link #castAsP2} for a "Select 1 (or up to N) of the M following actions" Summon, taking
	 * {@code options} in the order given.
	 */
	private static void castAsP2Selecting(MainWindow mw, CardData summon, int... options) {
		GameContext ctx = spy(mw.buildGameContext(false));
		doAnswer(inv -> {
			List<String> offered = inv.getArgument(1);
			List<String> taken = new ArrayList<>();
			for (int option : options) taken.add(offered.get(option));
			return taken;
		}).when(ctx).chooseActions(any(), anyList(), anyInt(), anyBoolean());
		Consumer<GameContext> fn = ActionResolver.parse(summon.summonEffect(), summon);
		assertNotNull(fn, summon.name() + " parses");
		fn.accept(ctx);
	}

	/** A mock whose 20-argument selection call answers with {@code targets}. */
	private static GameContext contextChoosing(List<ForwardTarget> targets) {
		GameContext ctx = mock(GameContext.class);
		when(ctx.consumePreloadedTargets()).thenReturn(null);
		when(ctx.selectCharacters(
				anyInt(), anyBoolean(), anyBoolean(), anyBoolean(),
				any(), any(), anyInt(), any(), anyInt(), any(),
				anyBoolean(), anyBoolean(), anyBoolean(),
				any(), any(), any(), any(), anyBoolean(), any(), anyBoolean()
		)).thenReturn(targets);
		return ctx;
	}

	/** A Stack entry for an enters-the-field auto-ability of {@code from}, controlled by {@code isP1}. */
	private static StackEntry autoEntryFrom(CardData from, boolean isP1) {
		AutoAbility aa = CardData.parseAutoAbilities(
				"When " + from.name() + " enters the field, draw 1 card.").get(0);
		return new StackEntry(from, null, aa, isP1, 0, false, null, false, false, 0, 0);
	}

	private static int damageOn(MainWindow mw, CardData p1Forward) {
		return mw.p1ForwardDamage.get(mw.p1ForwardCards.indexOf(p1Forward));
	}

	private static void dullP1Forward(MainWindow mw, CardData card) {
		mw.p1ForwardStates.set(mw.p1ForwardCards.indexOf(card), CardState.DULL);
	}

	private static void dullP2Forward(MainWindow mw, CardData card) {
		mw.p2ForwardStates.set(mw.p2ForwardCards.indexOf(card), CardState.DULL);
	}

	private static boolean p1BackupOnField(MainWindow mw, CardData card) {
		return Arrays.asList(mw.p1BackupCards).contains(card);
	}

	private static void fillP2Deck(MainWindow mw, int count) {
		for (int i = 0; i < count; i++) mw.gameState.getP2MainDeck().add(makeForward("Deck " + i, "Water", 2, 5000));
	}

	// =========================================================================================
	// 1-004C Ifrit: "EX BURST Choose 1 Forward. Deal it 4000 damage."
	// =========================================================================================

	private static final String IFRIT_1_004C = "[[ex]]EX BURST[[/]] Choose 1 Forward. Deal it 4000 damage.";

	@Test
	void ifritDeals4000DamageToTheChosenForward() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 3, 7000);
		placeP1Forward(mw, theirs);
		castAsP2(mw, makeSummon("Ifrit", "Fire", 1, IFRIT_1_004C));
		assertEquals(4000, damageOn(mw, theirs));
	}

	@Test
	void ifritBreaksAForwardOf4000Power() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 2, 4000);
		placeP1Forward(mw, theirs);
		castAsP2(mw, makeSummon("Ifrit", "Fire", 1, IFRIT_1_004C));
		assertFalse(mw.p1ForwardCards.contains(theirs));
		assertTrue(mw.gameState.getP1BreakZone().contains(theirs));
	}

	// =========================================================================================
	// 1-018L Bahamut: "Choose up to 2 Forwards opponent controls. Deal them 10000 damage. If they are
	// put from the field into the Break Zone this turn, remove them from the game instead."
	// =========================================================================================

	private static final String BAHAMUT_1_018L = "Choose up to 2 Forwards opponent controls. Deal them 10000 "
			+ "damage. If they are put from the field into the Break Zone this turn, remove them from the game instead.";

	@Test
	void bahamutRemovesTheTwoForwardsItBreaksFromTheGame() {
		MainWindow mw = new MainWindow();
		CardData a = makeForward("A", "Water", 4, 9000);
		CardData b = makeForward("B", "Ice", 4, 8000);
		CardData mine = makeForward("Mine", "Fire", 4, 9000);
		placeP1Forward(mw, a);
		placeP1Forward(mw, b);
		placeP2Forward(mw, mine);
		castAsP2(mw, makeSummon("Bahamut", "Fire", 9, BAHAMUT_1_018L));

		assertTrue(mw.p1ForwardCards.isEmpty());
		assertTrue(mw.gameState.getP1RemovedFromGame().containsAll(List.of(a, b)), "removed, not broken");
		assertFalse(mw.gameState.getP1BreakZone().contains(a));
		assertFalse(mw.gameState.getP1BreakZone().contains(b));
		assertEquals(0, mw.p2ForwardDamage.get(0), "opponent's Forwards only");
	}

	@Test
	void aForwardThatSurvivesBahamutIsStillRemovedIfBrokenLaterThisTurn() {
		MainWindow mw = new MainWindow();
		CardData big = makeForward("Big", "Water", 6, 12000);
		placeP1Forward(mw, big);
		castAsP2(mw, makeSummon("Bahamut", "Fire", 9, BAHAMUT_1_018L));
		assertEquals(10000, damageOn(mw, big));

		mw.buildGameContext(false).breakP1Forward(0);
		assertTrue(mw.gameState.getP1RemovedFromGame().contains(big));
		assertFalse(mw.gameState.getP1BreakZone().contains(big));
	}

	// =========================================================================================
	// 1-023R Brynhildr: "EX BURST Choose 1 Forward. Deal it 7000 damage."
	// =========================================================================================

	private static final String BRYNHILDR_1_023R = "[[ex]]EX BURST[[/]] Choose 1 Forward. Deal it 7000 damage.";

	@Test
	void brynhildrDeals7000Damage() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 5, 9000);
		placeP1Forward(mw, theirs);
		castAsP2(mw, makeSummon("Brynhildr", "Fire", 3, BRYNHILDR_1_023R));
		assertEquals(7000, damageOn(mw, theirs));
	}

	@Test
	void brynhildrBreaksAForwardOf7000Power() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 4, 7000);
		placeP1Forward(mw, theirs);
		castAsP2(mw, makeSummon("Brynhildr", "Fire", 3, BRYNHILDR_1_023R));
		assertTrue(mw.gameState.getP1BreakZone().contains(theirs));
	}

	// =========================================================================================
	// 1-038R Shiva: "EX BURST Choose 1 Forward. Dull it and Freeze it."
	// =========================================================================================

	private static final String SHIVA_1_038R = "[[ex]]EX BURST[[/]] Choose 1 Forward. Dull it and Freeze it.";

	@Test
	void shivaDullsAndFreezesTheChosenForward() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 4, 8000);
		placeP1Forward(mw, theirs);
		castAsP2(mw, makeSummon("Shiva", "Ice", 3, SHIVA_1_038R));
		assertEquals(CardState.DULL, mw.p1ForwardStates.get(0));
		assertTrue(mw.p1ForwardFrozen.get(0));
	}

	// =========================================================================================
	// 1-039C Shiva: "EX BURST Choose 1 dull Forward. Deal it 5000 damage."
	// =========================================================================================

	private static final String SHIVA_1_039C = "[[ex]]EX BURST[[/]] Choose 1 dull Forward. Deal it 5000 damage.";

	@Test
	void shivaDeals5000DamageToADullForwardOnly() {
		// The active one is the costlier kill, so ignoring "dull" would pick it.
		MainWindow mw = new MainWindow();
		CardData active = makeForward("Active", "Water", 6, 5000);
		CardData dull   = makeForward("Dull", "Wind", 2, 5000);
		placeP1Forward(mw, active);
		placeP1Forward(mw, dull);
		dullP1Forward(mw, dull);
		castAsP2(mw, makeSummon("Shiva", "Ice", 1, SHIVA_1_039C));

		assertTrue(mw.gameState.getP1BreakZone().contains(dull));
		assertEquals(0, damageOn(mw, active), "an active Forward cannot be chosen");
	}

	@Test
	void shivaDoesNothingWithOnlyAnActiveForward() {
		MainWindow mw = new MainWindow();
		CardData active = makeForward("Active", "Water", 3, 5000);
		placeP1Forward(mw, active);
		castAsP2(mw, makeSummon("Shiva", "Ice", 1, SHIVA_1_039C));
		assertEquals(0, damageOn(mw, active));
	}

	// =========================================================================================
	// 1-052R Hades: "Choose 1 Forward and 1 Backup opponent controls. Dull the Forward and return the
	// Backup to its owner's hand. Your opponent discards 1 card from his/her hand."
	//
	// One action per chosen type, which ChooseTwoMixedTypes (one action for both groups) cannot
	// read. ChooseCharacter took it instead: it chose only a Forward, left the rest "not yet
	// implemented", and returned a card *named* "the Backup" to hand.
	// =========================================================================================

	private static final String HADES_1_052R = "Choose 1 Forward and 1 Backup opponent controls. Dull the Forward "
			+ "and return the Backup to its owner's hand. Your opponent discards 1 card from his/her hand.";

	@Test
	void hadesDullsTheForwardReturnsTheBackupThenTheOpponentDiscards() {
		// P1's hand is empty, so the returned Backup is the one card there to discard, and P1's
		// forced discard needs no answer.
		MainWindow mw = new MainWindow();
		CardData forward = makeForward("Theirs", "Water", 4, 8000);
		CardData backup  = makeBackup("Their Backup", "Water", 3);
		CardData myBackup = makeBackup("My Backup", "Ice", 3);
		placeP1Forward(mw, forward);
		placeP1Backup(mw, backup);
		placeP2Backup(mw, myBackup);
		castAsP2(mw, makeSummon("Hades", "Ice", 5, HADES_1_052R));

		assertEquals(CardState.DULL, mw.p1ForwardStates.get(0));
		assertFalse(p1BackupOnField(mw, backup));
		assertTrue(mw.gameState.getP1BreakZone().contains(backup), "returned to hand, then discarded");
		assertTrue(mw.gameState.getP1Hand().isEmpty());
		assertSame(myBackup, mw.p2BackupCards[0], "opponent's Backup only");
	}

	@Test
	void hadesSplitChoiceIsNamedForItsOwnParser() {
		assertEquals("ChooseTypeAndTypeSplitActions", ActionResolver.matchedPatternName(HADES_1_052R, null));
	}

	@Test
	void anUnreadableTrailingSentenceLeavesTheSplitChoiceUnclaimed() {
		// Fail closed: the choice is not claimed at the cost of its last sentence.
		assertNull(ActionResolverChoose.tryParseChooseTypeAndTypeSplitActions(
				"Choose 1 Forward and 1 Backup opponent controls. Dull the Forward and return the Backup "
				+ "to its owner's hand. Your opponent frobnicates.", null));
	}

	// =========================================================================================
	// 1-061R Alexander: "EX BURST Choose 1 Forward of cost 5 or more. Break it."
	// =========================================================================================

	private static final String ALEXANDER_1_061R = "[[ex]]EX BURST[[/]] Choose 1 Forward of cost 5 or more. Break it.";

	@Test
	void alexanderBreaksAForwardOfCost5OrMoreOnly() {
		// The cost-4 one is the bigger threat, so ignoring the cost would pick it.
		MainWindow mw = new MainWindow();
		CardData five = makeForward("Five", "Water", 5, 5000);
		CardData four = makeForward("Four", "Water", 4, 12000);
		placeP1Forward(mw, five);
		placeP1Forward(mw, four);
		castAsP2(mw, makeSummon("Alexander", "Wind", 4, ALEXANDER_1_061R));

		assertTrue(mw.gameState.getP1BreakZone().contains(five));
		assertTrue(mw.p1ForwardCards.contains(four), "cost 4 cannot be chosen");
	}

	@Test
	void alexanderDoesNothingWithOnlyACost4Forward() {
		MainWindow mw = new MainWindow();
		CardData four = makeForward("Four", "Water", 4, 8000);
		placeP1Forward(mw, four);
		castAsP2(mw, makeSummon("Alexander", "Wind", 4, ALEXANDER_1_061R));
		assertTrue(mw.p1ForwardCards.contains(four));
	}

	// =========================================================================================
	// 1-062L Valefor: "Return all Forwards to their owners' hands."
	// =========================================================================================

	private static final String VALEFOR_1_062L = "Return all Forwards to their owners' hands.";

	@Test
	void valeforReturnsEveryForwardToItsOwnersHand() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 4, 8000);
		CardData mine   = makeForward("Mine", "Wind", 3, 7000);
		CardData backup = makeBackup("Their Backup", "Water", 2);
		placeP1Forward(mw, theirs);
		placeP2Forward(mw, mine);
		placeP1Backup(mw, backup);
		castAsP2(mw, makeSummon("Valefor", "Wind", 5, VALEFOR_1_062L));

		assertTrue(mw.p1ForwardCards.isEmpty());
		assertTrue(mw.p2ForwardCards.isEmpty());
		assertTrue(mw.gameState.getP1Hand().contains(theirs));
		assertTrue(mw.gameState.getP2Hand().contains(mine));
		assertTrue(p1BackupOnField(mw, backup), "Forwards only");
	}

	// =========================================================================================
	// 1-074R Sylph: "EX BURST Choose 1 Forward. Activate it. All the Forwards you control gain +1000
	// power until the end of the turn."
	// =========================================================================================

	private static final String SYLPH_1_074R = "[[ex]]EX BURST[[/]] Choose 1 Forward. Activate it. All the "
			+ "Forwards you control gain +1000 power until the end of the turn.";

	@Test
	void sylphActivatesOneForwardAndBoostsAllOfYours() {
		MainWindow mw = new MainWindow();
		CardData a = makeForward("A", "Wind", 3, 5000);
		CardData b = makeForward("B", "Wind", 3, 6000);
		CardData theirs = makeForward("Theirs", "Water", 4, 7000);
		placeP2Forward(mw, a);
		placeP2Forward(mw, b);
		placeP1Forward(mw, theirs);
		dullP2Forward(mw, a);
		dullP2Forward(mw, b);
		castAsP2(mw, makeSummon("Sylph", "Wind", 1, SYLPH_1_074R));

		long active = mw.p2ForwardStates.stream().filter(s -> s == CardState.ACTIVE).count();
		assertEquals(1, active, "one Forward is activated");
		assertEquals(6000, mw.effectiveP2ForwardPower(0));
		assertEquals(7000, mw.effectiveP2ForwardPower(1));
		assertEquals(7000, mw.effectiveP1ForwardPower(0), "yours only");
	}

	// =========================================================================================
	// 1-106C Golem: "EX BURST Choose 1 Forward. It gains +2000 power until the end of the turn. If it
	// is blocking, it gains +4000 power until the end of the turn instead."
	// =========================================================================================

	private static final String GOLEM_1_106C = "[[ex]]EX BURST[[/]] Choose 1 Forward. It gains +2000 power until "
			+ "the end of the turn. If it is blocking, it gains +4000 power until the end of the turn instead.";

	@Test
	void golemGrants2000Power() {
		MainWindow mw = new MainWindow();
		placeP2Forward(mw, makeForward("Mine", "Earth", 3, 5000));
		castAsP2(mw, makeSummon("Golem", "Earth", 1, GOLEM_1_106C));
		assertEquals(7000, mw.effectiveP2ForwardPower(0));
	}

	@Test
	void golemGrants4000InsteadToABlockingForward() {
		MainWindow mw = new MainWindow();
		placeP2Forward(mw, makeForward("Mine", "Earth", 3, 5000));
		mw.p2BlockingIdx = 0;
		castAsP2(mw, makeSummon("Golem", "Earth", 1, GOLEM_1_106C));
		assertEquals(9000, mw.effectiveP2ForwardPower(0), "+4000 instead, not as well");
	}

	// =========================================================================================
	// 1-110C Titan: "Choose 1 Forward you control. Dull it. It cannot be broken this turn."
	// =========================================================================================

	private static final String TITAN_1_110C = "Choose 1 Forward you control. Dull it. It cannot be broken this turn.";

	@Test
	void titanDullsYourForwardAndItCannotBeBrokenThisTurn() {
		MainWindow mw = new MainWindow();
		CardData mine   = makeForward("Mine", "Earth", 3, 7000);
		CardData theirs = makeForward("Theirs", "Water", 3, 7000);
		placeP2Forward(mw, mine);
		placeP1Forward(mw, theirs);
		castAsP2(mw, makeSummon("Titan", "Earth", 2, TITAN_1_110C));

		assertEquals(CardState.DULL, mw.p2ForwardStates.get(0));
		assertEquals(CardState.ACTIVE, mw.p1ForwardStates.get(0), "yours only");
		mw.buildGameContext(false).breakP2Forward(0);
		assertTrue(mw.p2ForwardCards.contains(mine), "it cannot be broken");
	}

	@Test
	void titanDoesNothingToAForwardTheOpponentControls() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 3, 7000);
		placeP1Forward(mw, theirs);
		castAsP2(mw, makeSummon("Titan", "Earth", 2, TITAN_1_110C));
		assertEquals(CardState.ACTIVE, mw.p1ForwardStates.get(0));
		mw.buildGameContext(false).breakP1Forward(0);
		assertFalse(mw.p1ForwardCards.contains(theirs), "no protection either");
	}

	// =========================================================================================
	// 1-117R Hecatoncheir: "Choose 1 Backup of cost 3 or more. Break it."
	// =========================================================================================

	private static final String HECATONCHEIR_1_117R = "Choose 1 Backup of cost 3 or more. Break it.";

	@Test
	void hecatoncheirBreaksABackupOfCost3OrMoreOnly() {
		MainWindow mw = new MainWindow();
		CardData three = makeBackup("Three", "Water", 3);
		CardData two   = makeBackup("Two", "Water", 2);
		placeP1Backup(mw, three);
		placeP1Backup(mw, two);
		castAsP2(mw, makeSummon("Hecatoncheir", "Earth", 3, HECATONCHEIR_1_117R));

		assertTrue(mw.gameState.getP1BreakZone().contains(three));
		assertTrue(p1BackupOnField(mw, two), "cost 2 cannot be chosen");
	}

	@Test
	void hecatoncheirDoesNothingWithOnlyACost2Backup() {
		MainWindow mw = new MainWindow();
		CardData two = makeBackup("Two", "Water", 2);
		placeP1Backup(mw, two);
		castAsP2(mw, makeSummon("Hecatoncheir", "Earth", 3, HECATONCHEIR_1_117R));
		assertTrue(p1BackupOnField(mw, two));
	}

	// =========================================================================================
	// 1-123R Odin: "Choose 1 Forward of cost 4 or less. Break it."
	// =========================================================================================

	private static final String ODIN_1_123R = "Choose 1 Forward of cost 4 or less. Break it.";

	@Test
	void odinBreaksAForwardOfCost4OrLessOnly() {
		// The cost-5 one is the bigger threat, so ignoring the cost would pick it.
		MainWindow mw = new MainWindow();
		CardData four = makeForward("Four", "Water", 4, 5000);
		CardData five = makeForward("Five", "Water", 5, 12000);
		placeP1Forward(mw, four);
		placeP1Forward(mw, five);
		castAsP2(mw, makeSummon("Odin", "Lightning", 4, ODIN_1_123R));

		assertTrue(mw.gameState.getP1BreakZone().contains(four));
		assertTrue(mw.p1ForwardCards.contains(five), "cost 5 cannot be chosen");
	}

	@Test
	void odinDoesNothingWithOnlyACost5Forward() {
		MainWindow mw = new MainWindow();
		CardData five = makeForward("Five", "Water", 5, 9000);
		placeP1Forward(mw, five);
		castAsP2(mw, makeSummon("Odin", "Lightning", 4, ODIN_1_123R));
		assertTrue(mw.p1ForwardCards.contains(five));
	}

	// =========================================================================================
	// 1-124R Odin: "EX BURST Choose 1 Forward. Break it."
	// =========================================================================================

	private static final String ODIN_1_124R = "[[ex]]EX BURST[[/]] Choose 1 Forward. Break it.";

	@Test
	void odinBreaksAnyForward() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 9, 12000);
		placeP1Forward(mw, theirs);
		castAsP2(mw, makeSummon("Odin", "Lightning", 7, ODIN_1_124R));
		assertTrue(mw.gameState.getP1BreakZone().contains(theirs));
	}

	// =========================================================================================
	// 1-143C Ramuh: "EX BURST Choose 1 active Forward. Deal it 5000 damage."
	// =========================================================================================

	private static final String RAMUH_1_143C = "[[ex]]EX BURST[[/]] Choose 1 active Forward. Deal it 5000 damage.";

	@Test
	void ramuhDeals5000DamageToAnActiveForwardOnly() {
		// The dull one is the costlier kill, so ignoring "active" would pick it.
		MainWindow mw = new MainWindow();
		CardData active = makeForward("Active", "Water", 2, 5000);
		CardData dull   = makeForward("Dull", "Wind", 6, 5000);
		placeP1Forward(mw, active);
		placeP1Forward(mw, dull);
		dullP1Forward(mw, dull);
		castAsP2(mw, makeSummon("Ramuh", "Lightning", 1, RAMUH_1_143C));

		assertTrue(mw.gameState.getP1BreakZone().contains(active));
		assertEquals(0, damageOn(mw, dull), "a dull Forward cannot be chosen");
	}

	@Test
	void ramuhDoesNothingWithOnlyADullForward() {
		MainWindow mw = new MainWindow();
		CardData dull = makeForward("Dull", "Wind", 3, 5000);
		placeP1Forward(mw, dull);
		dullP1Forward(mw, dull);
		castAsP2(mw, makeSummon("Ramuh", "Lightning", 1, RAMUH_1_143C));
		assertEquals(0, damageOn(mw, dull));
	}

	// =========================================================================================
	// 1-170C Fairy: "EX BURST Choose 1 Forward. Activate it. Draw 1 card."
	// =========================================================================================

	private static final String FAIRY_1_170C = "[[ex]]EX BURST[[/]] Choose 1 Forward. Activate it. Draw 1 card.";

	@Test
	void fairyActivatesTheForwardAndDraws() {
		MainWindow mw = new MainWindow();
		CardData mine = makeForward("Mine", "Water", 3, 7000);
		placeP2Forward(mw, mine);
		dullP2Forward(mw, mine);
		fillP2Deck(mw, 3);
		castAsP2(mw, makeSummon("Fairy", "Water", 2, FAIRY_1_170C));

		assertEquals(CardState.ACTIVE, mw.p2ForwardStates.get(0));
		assertEquals(1, mw.gameState.getP2Hand().size());
		assertEquals(2, mw.gameState.getP2MainDeck().size());
	}

	// =========================================================================================
	// 1-172C Moogle: "EX BURST Draw 2 cards, then discard 1 card from your hand."
	// =========================================================================================

	private static final String MOOGLE_1_172C = "[[ex]]EX BURST[[/]] Draw 2 cards, then discard 1 card from your hand.";

	@Test
	void moogleDrawsTwoThenDiscardsOne() {
		MainWindow mw = new MainWindow();
		fillP2Deck(mw, 3);
		castAsP2(mw, makeSummon("Moogle", "Water", 1, MOOGLE_1_172C));

		assertEquals(1, mw.gameState.getP2MainDeck().size(), "two drawn");
		assertEquals(1, mw.gameState.getP2Hand().size(), "one kept");
		assertEquals(1, mw.gameState.getP2BreakZone().size(), "one discarded");
	}

	// =========================================================================================
	// 1-178R Leviathan: "EX BURST Choose 1 Forward. Return it to its owner's hand."
	// =========================================================================================

	private static final String LEVIATHAN_1_178R = "[[ex]]EX BURST [[/]]Choose 1 Forward. Return it to its owner's hand.";

	@Test
	void leviathanReturnsTheForwardToItsOwnersHand() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Fire", 4, 8000);
		placeP1Forward(mw, theirs);
		castAsP2(mw, makeSummon("Leviathan", "Water", 3, LEVIATHAN_1_178R));

		assertTrue(mw.p1ForwardCards.isEmpty());
		assertTrue(mw.gameState.getP1Hand().contains(theirs), "its owner's hand, not the caster's");
		assertTrue(mw.gameState.getP2Hand().isEmpty());
	}

	// =========================================================================================
	// 1-190S Bahamut Fury: "EX BURST Choose 1 Forward. You may discard 1 card from your hand. If you
	// do so, deal it 7000 damage. If not, deal it 5000 damage."
	//
	// The CPU takes every optional discard it can afford (GameContextImpl.offerOptionalDiscard).
	// =========================================================================================

	private static final String BAHAMUT_FURY_1_190S = "[[ex]]EX BURST[[/]] Choose 1 Forward. You may discard 1 card "
			+ "from your hand. If you do so, deal it 7000 damage. If not, deal it 5000 damage.";

	@Test
	void bahamutFuryDeals7000AfterADiscard() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 4, 7000);
		CardData spare  = makeForward("Spare", "Fire", 2, 5000);
		placeP1Forward(mw, theirs);
		mw.gameState.getP2Hand().add(spare);
		castAsP2(mw, makeSummon("Bahamut Fury", "Fire", 2, BAHAMUT_FURY_1_190S));

		assertTrue(mw.gameState.getP2BreakZone().contains(spare), "the discard");
		assertTrue(mw.gameState.getP1BreakZone().contains(theirs), "7000 breaks a 7000 Forward");
	}

	@Test
	void bahamutFuryDeals5000WithNothingToDiscard() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 4, 8000);
		placeP1Forward(mw, theirs);
		castAsP2(mw, makeSummon("Bahamut Fury", "Fire", 2, BAHAMUT_FURY_1_190S));
		assertEquals(5000, damageOn(mw, theirs));
	}

	/**
	 * "1 card" is any card, and must go through the by-type route rather than the by-name one —
	 * asking for a card named "card" would find nothing and silently take the "if not" branch every
	 * time.
	 */
	@Test
	void bahamutFuryOffersAnyCardRatherThanACardNamedCard() {
		GameContext ctx = contextChoosing(List.of(new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD)));
		CardData bahamutFury = makeSummon("Bahamut Fury", "Fire", 2, BAHAMUT_FURY_1_190S);
		ArgumentCaptor<String> type = ArgumentCaptor.forClass(String.class);

		ActionResolver.parse(bahamutFury.summonEffect(), bahamutFury).accept(ctx);

		verify(ctx).mayDiscardCardOfTypeFromHandOrElse(type.capture(), any(), any());
		assertEquals("card", type.getValue(), "the CardFilters vocabulary for 'any card'");
		verify(ctx, never()).mayDiscardCardNameFromHandOrElse(any(), any(), any());
	}


	// =========================================================================================
	// 1-198S Valefor: "EX BURST Deal 3000 damage to all the Forwards opponent controls. If you control
	// Card Name Yuna, activate all the Backups you control."
	// =========================================================================================

	private static final String VALEFOR_1_198S = "[[ex]]EX BURST[[/]] Deal 3000 damage to all the Forwards opponent "
			+ "controls. If you control Card Name Yuna, activate all the Backups you control.";

	/** P2's two dull Backups and one Forward, P1's two Forwards; {@code withYuna} adds P2's Yuna. */
	private static MainWindow castValefor198(boolean withYuna, CardData small, CardData large, CardData mine) {
		MainWindow mw = new MainWindow();
		placeP1Forward(mw, small);
		placeP1Forward(mw, large);
		placeP2Forward(mw, mine);
		if (withYuna) placeP2Forward(mw, makeForward("Yuna", "Wind", 3, 7000));
		placeP2Backup(mw, makeBackup("Backup A", "Wind", 2));
		placeP2Backup(mw, makeBackup("Backup B", "Wind", 3));
		mw.p2BackupStates[0] = CardState.DULL;
		mw.p2BackupStates[1] = CardState.DULL;
		castAsP2(mw, makeSummon("Valefor", "Wind", 2, VALEFOR_1_198S));
		return mw;
	}

	@Test
	void valeforDeals3000ToEveryForwardTheOpponentControls() {
		CardData small = makeForward("Small", "Water", 2, 3000);
		CardData large = makeForward("Large", "Water", 4, 8000);
		CardData mine  = makeForward("Mine", "Wind", 2, 3000);
		MainWindow mw = castValefor198(false, small, large, mine);

		assertTrue(mw.gameState.getP1BreakZone().contains(small));
		assertEquals(3000, damageOn(mw, large));
		assertEquals(0, mw.p2ForwardDamage.get(0), "opponent's Forwards only");
		assertEquals(CardState.DULL, mw.p2BackupStates[0], "no Yuna, no activation");
		assertEquals(CardState.DULL, mw.p2BackupStates[1]);
	}

	@Test
	void valeforActivatesYourBackupsIfYouControlYuna() {
		MainWindow mw = castValefor198(true, makeForward("Small", "Water", 2, 3000),
				makeForward("Large", "Water", 4, 8000), makeForward("Mine", "Wind", 2, 3000));
		assertEquals(CardState.ACTIVE, mw.p2BackupStates[0]);
		assertEquals(CardState.ACTIVE, mw.p2BackupStates[1]);
	}

	// =========================================================================================
	// 2-002C Ifrit: "EX BURST Choose 1 Forward. Deal it 6000 damage."
	// =========================================================================================

	private static final String IFRIT_2_002C = "[[ex]]EX BURST [[/]]Choose 1 Forward. Deal it 6000 damage.";

	@Test
	void ifritDeals6000DamageToTheChosenForward() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 4, 8000);
		placeP1Forward(mw, theirs);
		castAsP2(mw, makeSummon("Ifrit", "Fire", 2, IFRIT_2_002C));
		assertEquals(6000, damageOn(mw, theirs));
	}

	@Test
	void ifritBreaksAForwardOf6000Power() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 3, 6000);
		placeP1Forward(mw, theirs);
		castAsP2(mw, makeSummon("Ifrit", "Fire", 2, IFRIT_2_002C));
		assertTrue(mw.gameState.getP1BreakZone().contains(theirs));
	}

	// =========================================================================================
	// 2-019R Belias, the Gigas: "EX BURST Choose 1 Forward. Until the end of the turn, it gains +1000
	// power, Haste and First Strike. Draw 1 card."
	// =========================================================================================

	private static final String BELIAS_2_019R = "[[ex]]EX BURST [[/]]Choose 1 Forward. Until the end of the turn, "
			+ "it gains +1000 power, Haste and First Strike. Draw 1 card.";

	@Test
	void beliasGrantsPowerHasteAndFirstStrikeThenDraws() {
		MainWindow mw = new MainWindow();
		placeP2Forward(mw, makeForward("Mine", "Fire", 3, 7000));
		fillP2Deck(mw, 2);
		castAsP2(mw, makeSummon("Belias, the Gigas", "Fire", 2, BELIAS_2_019R));

		assertEquals(8000, mw.effectiveP2ForwardPower(0));
		assertTrue(mw.effectiveP2HasTrait(0, CardData.Trait.HASTE));
		assertTrue(mw.effectiveP2HasTrait(0, CardData.Trait.FIRST_STRIKE));
		assertEquals(1, mw.gameState.getP2Hand().size());
	}

	// =========================================================================================
	// 2-044R Mateus, the Corrupt: "EX BURST Opponent puts 1 attacking Forward into the Break Zone."
	//
	// The opponent selects, so it is cast from P1's seat and the CPU answers. The CPU gives up its
	// cheapest eligible Forward, which makes the cheaper idle one the decoy.
	// =========================================================================================

	private static final String MATEUS_2_044R = "[[ex]]EX BURST [[/]]Opponent puts 1 attacking Forward into the Break Zone.";

	@Test
	void mateusMakesTheOpponentPutAnAttackingForwardIntoTheBreakZone() {
		MainWindow mw = new MainWindow();
		CardData idle      = makeForward("Idle", "Fire", 2, 5000);
		CardData attacking = makeForward("Attacking", "Fire", 5, 9000);
		placeP2Forward(mw, idle);
		placeP2Forward(mw, attacking);
		mw.p2DeclaredAttackers.add(attacking);
		castAsP1(mw, makeSummon("Mateus, the Corrupt", "Ice", 5, MATEUS_2_044R));

		assertTrue(mw.gameState.getP2BreakZone().contains(attacking));
		assertTrue(mw.p2ForwardCards.contains(idle), "only an attacking Forward");
	}

	@Test
	void mateusDoesNothingWhenNoForwardIsAttacking() {
		MainWindow mw = new MainWindow();
		CardData idle = makeForward("Idle", "Fire", 2, 5000);
		placeP2Forward(mw, idle);
		castAsP1(mw, makeSummon("Mateus, the Corrupt", "Ice", 5, MATEUS_2_044R));
		assertTrue(mw.p2ForwardCards.contains(idle));
	}

	// =========================================================================================
	// 2-045C Moomba: "EX BURST Choose 1 Forward. Deal it damage equal to its power minus 1000."
	// =========================================================================================

	private static final String MOOMBA_2_045C = "[[ex]]EX BURST [[/]]Choose 1 Forward. Deal it damage equal to its "
			+ "power minus 1000.";

	@Test
	void moombaDealsDamageEqualToTheForwardsPowerMinus1000() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 5, 9000);
		placeP1Forward(mw, theirs);
		castAsP2(mw, makeSummon("Moomba", "Ice", 2, MOOMBA_2_045C));
		assertEquals(8000, damageOn(mw, theirs));
		assertTrue(mw.p1ForwardCards.contains(theirs), "1000 short of breaking it");
	}

	// =========================================================================================
	// 2-049H Asura: "Select 1 of the 3 following actions. 'Choose up to 2 Forwards. Activate them.'
	// 'Choose up to 5 Backups. Activate them.' 'Choose 1 Character card of cost 2 or less into your
	// Break Zone. Add it to your hand.'"
	//
	// "into" is a misprint for "in", corrected on the Re-059H reprint — which parsed while the
	// original did not. Accepted in the zone slot, which sits between the target descriptor and the
	// followup separator: a card that puts something *into* the Break Zone says so after that
	// separator, out of this group's reach.
	//
	// The CPU takes the first action, so each test picks its action through a spy.
	// =========================================================================================

	private static final String ASURA_2_049H = "Select 1 of the 3 following actions.[[br]]\"Choose up to 2 Forwards. "
			+ "Activate them.\"[[br]]\"Choose up to 5 Backups. Activate them.\"[[br]]\"Choose 1 Character card of "
			+ "cost 2 or less into your Break Zone. Add it to your hand.\"";

	private static CardData asura() {
		return makeSummon("Asura", "Wind", 1, ASURA_2_049H);
	}

	@Test
	void asurasFirstActionActivatesUpToTwoForwards() {
		MainWindow mw = new MainWindow();
		CardData a = makeForward("A", "Wind", 3, 7000);
		CardData b = makeForward("B", "Wind", 3, 7000);
		placeP2Forward(mw, a);
		placeP2Forward(mw, b);
		dullP2Forward(mw, a);
		dullP2Forward(mw, b);
		castAsP2Selecting(mw, asura(), 0);

		assertEquals(CardState.ACTIVE, mw.p2ForwardStates.get(0));
		assertEquals(CardState.ACTIVE, mw.p2ForwardStates.get(1));
	}

	@Test
	void asurasSecondActionActivatesUpToFiveBackups() {
		MainWindow mw = new MainWindow();
		for (int i = 0; i < 5; i++) {
			placeP2Backup(mw, makeBackup("Backup " + i, "Wind", 2));
			mw.p2BackupStates[i] = CardState.DULL;
		}
		castAsP2Selecting(mw, asura(), 1);
		for (int i = 0; i < 5; i++) assertEquals(CardState.ACTIVE, mw.p2BackupStates[i], "Backup " + i);
	}

	@Test
	void asurasThirdActionReturnsACheapCharacterFromTheBreakZone() {
		MainWindow mw = new MainWindow();
		CardData cheap  = makeForward("Cheap", "Wind", 2, 5000);
		CardData costly = makeForward("Costly", "Wind", 3, 7000);
		mw.gameState.getP2BreakZone().add(costly);
		mw.gameState.getP2BreakZone().add(cheap);
		castAsP2Selecting(mw, asura(), 2);

		assertTrue(mw.gameState.getP2Hand().contains(cheap));
		assertTrue(mw.gameState.getP2BreakZone().contains(costly), "cost 3 cannot be chosen");
	}

	private static final String ASURA_OPTION =
			"Choose 1 Character card of cost 2 or less into your Break Zone. Add it to your hand.";

	@Test
	void asurasMisprintReadsAsItsReprintDoes() {
		assertEquals("ChooseCharacter / AddToHand",
				ActionResolver.fullDescription(ASURA_OPTION, null));
		assertEquals("ChooseCharacter / AddToHand",
				ActionResolver.fullDescription(
						"Choose 1 Character of cost 2 or less in your Break Zone. Add it to your hand.",
						null));
	}

	@Test
	void asuraNamesAllThreeOfItsOptions() {
		String summon = "Select 1 of the 3 following actions. "
				+ "\"Choose up to 2 Forwards. Activate them.\" "
				+ "\"Choose up to 5 Backups. Activate them.\" "
				+ "\"" + ASURA_OPTION + "\"";
		assertEquals("SelectFollowingActions(1 of 3: ChooseCharacter / Activate "
						+ "| ChooseCharacter / Activate | ChooseCharacter / AddToHand)",
				ActionResolver.fullDescription(summon, null));
	}

	// =========================================================================================
	// 2-070R Shemhazai, the Whisperer: "Each Forward can only be blocked by a Forward with a cost
	// inferior or equal to its own this turn."
	// =========================================================================================

	private static final String SHEMHAZAI_2_070R = "Each Forward can only be blocked by a Forward with a cost "
			+ "inferior or equal to its own this turn.";

	@Test
	void shemhazaiStopsCostlierForwardsFromBlocking() {
		MainWindow mw = new MainWindow();
		CardData attacker = makeForward("Attacker", "Fire", 3, 7000);
		placeP1Forward(mw, attacker);
		assertFalse(mw.p1AttackerCostFiltersExclude(attacker, 4), "no restriction before the cast");

		castAsP2(mw, makeSummon("Shemhazai, the Whisperer", "Wind", 1, SHEMHAZAI_2_070R));
		assertTrue(mw.p1AttackerCostFiltersExclude(attacker, 4), "cost 4 cannot block a cost 3");
		assertFalse(mw.p1AttackerCostFiltersExclude(attacker, 3), "equal cost can");
		assertFalse(mw.p1AttackerCostFiltersExclude(attacker, 1), "lower cost can");
	}

	// =========================================================================================
	// 2-080C Carbuncle: "Choose 1 Summon targeting a Character you control. Cancel its effect."
	// =========================================================================================

	private static final String CARBUNCLE_2_080C = "Choose 1 Summon targeting a Character you control. Cancel its effect.";

	/** P1's damage Summon on the Stack, already aimed at {@code target}. */
	private static StackEntry p1SummonAimedAt(MainWindow mw, ForwardTarget target) {
		CardData damage = makeSummon("Thunder", "Lightning", 2, "Choose 1 Forward. Deal it 5000 damage.");
		mw.pushSummonOnStack(damage, true, 0, 0, false, List.of(target), true);
		return mw.gameState.getStack().get(0);
	}

	@Test
	void carbuncleCancelsASummonTargetingYourForward() {
		MainWindow mw = new MainWindow();
		placeP2Forward(mw, makeForward("Mine", "Earth", 3, 7000));
		StackEntry entry = p1SummonAimedAt(mw, new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD));
		castAsP2(mw, makeSummon("Carbuncle", "Earth", 3, CARBUNCLE_2_080C));
		assertTrue(mw.cancelledStackEntries.contains(entry));
	}

	@Test
	void carbuncleLeavesASummonTargetingTheOpponentsForward() {
		MainWindow mw = new MainWindow();
		placeP2Forward(mw, makeForward("Mine", "Earth", 3, 7000));
		placeP1Forward(mw, makeForward("Theirs", "Fire", 3, 7000));
		StackEntry entry = p1SummonAimedAt(mw, new ForwardTarget(true, 0, ForwardTarget.CardZone.FORWARD));
		castAsP2(mw, makeSummon("Carbuncle", "Earth", 3, CARBUNCLE_2_080C));
		assertFalse(mw.cancelledStackEntries.contains(entry));
	}

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

	// =========================================================================================
	// 2-107C Cyclops: "EX BURST All Forwards opponent controls lose 3000 power until the end of the
	// turn."
	// =========================================================================================

	private static final String CYCLOPS_2_107C = "[[ex]]EX BURST [[/]]All Forwards opponent controls lose 3000 "
			+ "power until the end of the turn.";

	@Test
	void cyclopsTakes3000PowerFromEveryForwardTheOpponentControls() {
		MainWindow mw = new MainWindow();
		placeP1Forward(mw, makeForward("A", "Water", 3, 7000));
		placeP1Forward(mw, makeForward("B", "Fire", 5, 9000));
		placeP2Forward(mw, makeForward("Mine", "Lightning", 3, 7000));
		castAsP2(mw, makeSummon("Cyclops", "Lightning", 3, CYCLOPS_2_107C));

		assertEquals(4000, mw.effectiveP1ForwardPower(0));
		assertEquals(6000, mw.effectiveP1ForwardPower(1));
		assertEquals(7000, mw.effectiveP2ForwardPower(0), "opponent's Forwards only");
	}

	// =========================================================================================
	// 2-117R Adrammelech, the Wroth: "EX BURST Choose 1 active Forward. Deal it 7000 damage."
	// =========================================================================================

	private static final String ADRAMMELECH_2_117R = "[[ex]]EX BURST [[/]]Choose 1 active Forward. Deal it 7000 damage.";

	@Test
	void adrammelechDeals7000DamageToAnActiveForward() {
		MainWindow mw = new MainWindow();
		CardData active = makeForward("Active", "Water", 4, 7000);
		placeP1Forward(mw, active);
		castAsP2(mw, makeSummon("Adrammelech, the Wroth", "Lightning", 3, ADRAMMELECH_2_117R));
		assertTrue(mw.gameState.getP1BreakZone().contains(active));
	}

	@Test
	void adrammelechDoesNothingWithOnlyADullForward() {
		MainWindow mw = new MainWindow();
		CardData dull = makeForward("Dull", "Water", 4, 7000);
		placeP1Forward(mw, dull);
		dullP1Forward(mw, dull);
		castAsP2(mw, makeSummon("Adrammelech, the Wroth", "Lightning", 3, ADRAMMELECH_2_117R));
		assertEquals(0, damageOn(mw, dull));
	}

	// =========================================================================================
	// 2-133R Cúchulainn, the Impure: "EX BURST Choose 1 Forward opponent controls. It loses 1000
	// power for each dull Character opponent controls until the end of the turn. Draw 1 card."
	// =========================================================================================

	private static final String CUCHULAINN_2_133R = "[[ex]]EX BURST[[/]] Choose 1 Forward opponent controls. It loses "
			+ "1000 power for each dull Character opponent controls until the end of the turn. Draw 1 card.";

	@Test
	void cuchulainnTakes1000PowerPerDullCharacterTheOpponentControlsThenDraws() {
		MainWindow mw = new MainWindow();
		placeP1Forward(mw, makeForward("Theirs", "Fire", 5, 9000));
		placeP1Backup(mw, makeBackup("Dull A", "Fire", 2));
		placeP1Backup(mw, makeBackup("Dull B", "Fire", 2));
		placeP1Backup(mw, makeBackup("Active", "Fire", 2));
		mw.p1BackupStates[0] = CardState.DULL;
		mw.p1BackupStates[1] = CardState.DULL;
		placeP2Backup(mw, makeBackup("My Dull", "Water", 2));
		mw.p2BackupStates[0] = CardState.DULL;
		fillP2Deck(mw, 2);
		castAsP2(mw, makeSummon("Cúchulainn, the Impure", "Water", 4, CUCHULAINN_2_133R));

		assertEquals(7000, mw.effectiveP1ForwardPower(0), "two dull Characters: -2000, not counting yours");
		assertEquals(1, mw.gameState.getP2Hand().size());
	}

	@Test
	void cuchulainnTakesNothingWhenNothingIsDullButStillDraws() {
		MainWindow mw = new MainWindow();
		placeP1Forward(mw, makeForward("Theirs", "Fire", 5, 9000));
		placeP1Backup(mw, makeBackup("Active", "Fire", 2));
		fillP2Deck(mw, 2);
		castAsP2(mw, makeSummon("Cúchulainn, the Impure", "Water", 4, CUCHULAINN_2_133R));

		assertEquals(9000, mw.effectiveP1ForwardPower(0));
		assertEquals(1, mw.gameState.getP2Hand().size());
	}

	@Test
	void aSelfSideStateCountIsLeftAloneRatherThanCountedWrong() {
		// GameContext counts a card state on the opponent's field only, so the self-side reading
		// would drop "dull" and count every Character. The branch declines it instead; the text
		// falls through to the plain reduce rather than silently over-counting.
		CardData cu = makeSummon("Cuchulainn, the Impure", "Water", 4, CUCHULAINN_2_133R);
		String selfSide = "Choose 1 Forward opponent controls. It loses 1000 power for each dull "
				+ "Character you control until the end of the turn.";
		assertNotEquals("ChooseCharacter / PowerReduceUntilForEach",
				ActionResolver.fullDescription(selfSide, cu),
				"no printing has this shape, and counting it as unconditioned would be wrong");
	}


	// =========================================================================================
	// 2-140C Leviathan: "EX BURST All Water Forwards gain +2000 power until the end of the turn."
	// =========================================================================================

	private static final String LEVIATHAN_2_140C = "[[ex]]EX BURST [[/]]All Water Forwards gain +2000 power until "
			+ "the end of the turn.";

	@Test
	void leviathanBoostsEveryWaterForwardOnBothSides() {
		MainWindow mw = new MainWindow();
		placeP2Forward(mw, makeForward("My Water", "Water", 3, 7000));
		placeP2Forward(mw, makeForward("My Fire", "Fire", 3, 7000));
		placeP1Forward(mw, makeForward("Their Water", "Water", 3, 5000));
		castAsP2(mw, makeSummon("Leviathan", "Water", 1, LEVIATHAN_2_140C));

		assertEquals(9000, mw.effectiveP2ForwardPower(0));
		assertEquals(7000, mw.effectiveP2ForwardPower(1), "Water only");
		assertEquals(7000, mw.effectiveP1ForwardPower(0), "all Water Forwards, the opponent's too");
	}

	// =========================================================================================
	// 3-002R Ifrit: "EX BURST Choose 1 Forward. Deal it 7000 damage. If you control a Job Class Zero
	// Cadet Forward, deal it 8000 damage instead."
	// =========================================================================================

	private static final String IFRIT_3_002R = "[[ex]]EX BURST[[/]] Choose 1 Forward. Deal it 7000 damage. If you "
			+ "control a Job Class Zero Cadet Forward, deal it 8000 damage instead.";

	@Test
	void ifritDeals7000Damage() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 6, 10000);
		placeP1Forward(mw, theirs);
		castAsP2(mw, makeSummon("Ifrit", "Fire", 4, IFRIT_3_002R));
		assertEquals(7000, damageOn(mw, theirs));
	}

	@Test
	void ifritDeals8000InsteadWithAClassZeroCadet() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 6, 10000);
		placeP1Forward(mw, theirs);
		placeP2Forward(mw, makeForwardWithJob("Cadet", "Fire", 2, 5000, "Class Zero Cadet"));
		castAsP2(mw, makeSummon("Ifrit", "Fire", 4, IFRIT_3_002R));
		assertEquals(8000, damageOn(mw, theirs));
	}

	// =========================================================================================
	// 3-020H Phoenix: "Choose up to 1 Forward of cost 2 or less in your Break Zone. Play it onto the
	// field. Deal 2000 damage to all the Forwards opponent controls."
	// =========================================================================================

	private static final String PHOENIX_3_020H = "Choose up to 1 Forward of cost 2 or less in your Break Zone. "
			+ "Play it onto the field. Deal 2000 damage to all the Forwards opponent controls.";

	@Test
	void phoenixPlaysACheapForwardFromTheBreakZoneThenDamagesTheOpponentsForwards() {
		MainWindow mw = new MainWindow();
		CardData cheap  = makeForward("Cheap", "Fire", 2, 5000);
		CardData costly = makeForward("Costly", "Fire", 3, 7000);
		mw.gameState.getP2BreakZone().add(costly);
		mw.gameState.getP2BreakZone().add(cheap);
		CardData small = makeForward("Small", "Water", 1, 2000);
		CardData large = makeForward("Large", "Water", 4, 8000);
		placeP1Forward(mw, small);
		placeP1Forward(mw, large);
		castAsP2(mw, makeSummon("Phoenix", "Fire", 4, PHOENIX_3_020H));

		assertTrue(mw.p2ForwardCards.contains(cheap), "played onto the field");
		assertTrue(mw.gameState.getP2BreakZone().contains(costly), "cost 3 cannot be chosen");
		assertEquals(0, mw.p2ForwardDamage.get(mw.p2ForwardCards.indexOf(cheap)), "opponent's Forwards only");
		assertTrue(mw.gameState.getP1BreakZone().contains(small));
		assertEquals(2000, damageOn(mw, large));
	}

	// =========================================================================================
	// 3-032R Shiva: "Choose up to 2 Forwards opponent controls. Dull them."
	// =========================================================================================

	private static final String SHIVA_3_032R = "Choose up to 2 Forwards opponent controls. Dull them.";

	@Test
	void shivaDullsTwoOfTheOpponentsForwards() {
		MainWindow mw = new MainWindow();
		for (int i = 0; i < 3; i++) placeP1Forward(mw, makeForward("Theirs " + i, "Water", 3, 7000));
		placeP2Forward(mw, makeForward("Mine", "Ice", 3, 7000));
		castAsP2(mw, makeSummon("Shiva", "Ice", 2, SHIVA_3_032R));

		long dull = mw.p1ForwardStates.stream().filter(s -> s == CardState.DULL).count();
		assertEquals(2, dull);
		assertEquals(CardState.ACTIVE, mw.p2ForwardStates.get(0), "opponent's Forwards only");
	}

	// =========================================================================================
	// 3-037H Zalera, the Death Seraph: "Break all the dull Forwards of costs 2, 3, 5, 7, 11, and 13
	// opponent controls."
	// =========================================================================================

	private static final String ZALERA_3_037H = "Break all the dull Forwards of costs 2, 3, 5, 7, 11, and 13 "
			+ "opponent controls.";

	@Test
	void zaleraBreaksTheOpponentsDullForwardsOfPrimeCost() {
		MainWindow mw = new MainWindow();
		CardData two = makeForward("Two", "Water", 2, 5000);
		CardData three = makeForward("Three", "Water", 3, 7000);
		CardData four = makeForward("Four", "Water", 4, 8000);
		CardData five = makeForward("Five", "Water", 5, 9000);
		CardData activeThree = makeForward("Active Three", "Water", 3, 7000);
		CardData mine = makeForward("Mine", "Ice", 2, 5000);
		for (CardData c : List.of(two, three, four, five, activeThree)) placeP1Forward(mw, c);
		for (CardData c : List.of(two, three, four, five)) dullP1Forward(mw, c);
		placeP2Forward(mw, mine);
		dullP2Forward(mw, mine);
		castAsP2(mw, makeSummon("Zalera, the Death Seraph", "Ice", 4, ZALERA_3_037H));

		assertTrue(mw.gameState.getP1BreakZone().containsAll(List.of(two, three, five)));
		assertTrue(mw.p1ForwardCards.contains(four), "4 is not on the list");
		assertTrue(mw.p1ForwardCards.contains(activeThree), "dull Forwards only");
		assertTrue(mw.p2ForwardCards.contains(mine), "opponent's Forwards only");
	}

	// =========================================================================================
	// 3-061R Diablos: "EX BURST Choose 1 Forward. Deal it 1000 damage for each Character you control.
	// If you control a Job Class Zero Cadet Forward, select up to 3 Backups you control. Activate
	// them."
	// =========================================================================================

	private static final String DIABLOS_3_061R = "[[ex]]EX BURST[[/]] Choose 1 Forward. Deal it 1000 damage for each "
			+ "Character you control. If you control a Job Class Zero Cadet Forward, select up to 3 Backups you "
			+ "control. Activate them.";

	/** P2 with {@code mine} and two dull Backups — three Characters — against one P1 Forward. */
	private static MainWindow castDiablos(CardData mine, CardData theirs) {
		MainWindow mw = new MainWindow();
		placeP1Forward(mw, theirs);
		placeP2Forward(mw, mine);
		placeP2Backup(mw, makeBackup("Backup A", "Wind", 2));
		placeP2Backup(mw, makeBackup("Backup B", "Wind", 2));
		mw.p2BackupStates[0] = CardState.DULL;
		mw.p2BackupStates[1] = CardState.DULL;
		castAsP2(mw, makeSummon("Diablos", "Wind", 3, DIABLOS_3_061R));
		return mw;
	}

	@Test
	void diablosDeals1000DamagePerCharacterYouControl() {
		CardData theirs = makeForward("Theirs", "Water", 5, 9000);
		MainWindow mw = castDiablos(makeForward("Mine", "Wind", 3, 7000), theirs);
		assertEquals(3000, damageOn(mw, theirs));
		assertEquals(CardState.DULL, mw.p2BackupStates[0], "no Cadet, no activation");
	}

	@Test
	void diablosActivatesYourBackupsWithAClassZeroCadet() {
		CardData theirs = makeForward("Theirs", "Water", 5, 9000);
		MainWindow mw = castDiablos(makeForwardWithJob("Cadet", "Wind", 3, 7000, "Class Zero Cadet"), theirs);
		assertEquals(3000, damageOn(mw, theirs));
		assertEquals(CardState.ACTIVE, mw.p2BackupStates[0]);
		assertEquals(CardState.ACTIVE, mw.p2BackupStates[1]);
	}

	/** Stubs the Backup picker: any selection that includes Backups. */
	private static List<ForwardTarget> anyBackupSelection(GameContext ctx) {
		return ctx.selectCharacters(anyInt(), anyBoolean(), anyBoolean(), anyBoolean(), any(), any(),
				anyInt(), any(), anyInt(), any(), anyBoolean(), eq(true), anyBoolean(),
				any(), any(), any(), any(), anyBoolean(), any(), anyBoolean());
	}

	@Test
	void theCadetsControlGateActivatesThreeOfYourOwnBackups() {
		// 3-061R's second half, as the choose chain hands it over.
		ForwardTarget backup = new ForwardTarget(true, 1, ForwardTarget.CardZone.BACKUP);
		GameContext ctx = mock(GameContext.class);
		when(ctx.consumePreloadedTargets()).thenReturn(null);
		when(ctx.controlConditionMet(any())).thenReturn(true);
		when(anyBackupSelection(ctx)).thenReturn(List.of(backup));

		ActionResolver.parse("If you control a Job Class Zero Cadet Forward, select up to 3 Backups you "
				+ "control. Activate them.", null).accept(ctx);

		verify(ctx).selectCharacters(eq(3), eq(true), eq(false), eq(true), any(), any(),
				anyInt(), any(), anyInt(), any(), eq(false), eq(true), eq(false),
				any(), any(), any(), any(), anyBoolean(), any(), anyBoolean());
		verify(ctx).activateTarget(backup);
	}

	// =========================================================================================
	// 3-071H Chaos, Walker of the Wheel: "EX BURST Choose 1 Forward opponent controls. Break it. If
	// that Forward is put into the Break Zone, your opponent may play 1 Forward from their hand
	// onto the field."
	//
	// The last sentence was read as the caster's own play from their own hand, on every cast. The
	// opponent's offer is checked through a spy: P1 answering it for real opens a dialog.
	// =========================================================================================

	private static final String CHAOS_3_071H = "[[ex]]EX BURST[[/]] Choose 1 Forward opponent controls. Break it. If "
			+ "that Forward is put into the Break Zone, your opponent may play 1 Forward from their hand onto the field.";

	/** P2 casts Chaos through a spy that declines the opponent's play; returns the spy. */
	private static GameContext castChaosAsP2(MainWindow mw) {
		GameContext ctx = spy(mw.buildGameContext(false));
		doReturn(null).when(ctx).opponentMayPlayCharacterFromHand(anyBoolean(), anyBoolean(), anyBoolean(),
				anyInt(), any(), anyInt(), any(), any(), any(), any(), any(), anyBoolean(), any(), anyBoolean(), any());
		CardData chaos = makeSummon("Chaos, Walker of the Wheel", "Wind", 3, CHAOS_3_071H);
		ActionResolver.parse(chaos.summonEffect(), chaos).accept(ctx);
		return ctx;
	}

	@Test
	void chaosBreaksTheForwardAndOffersTheOpponentAPlay() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 4, 8000);
		placeP1Forward(mw, theirs);
		GameContext ctx = castChaosAsP2(mw);

		assertTrue(mw.gameState.getP1BreakZone().contains(theirs));
		verify(ctx).opponentMayPlayCharacterFromHand(eq(true), eq(false), eq(false), anyInt(), any(), anyInt(),
				any(), any(), any(), any(), any(), anyBoolean(), any(), anyBoolean(), any());
	}

	@Test
	void chaosNeverPlaysFromTheCastersHand() {
		MainWindow mw = new MainWindow();
		placeP1Forward(mw, makeForward("Theirs", "Water", 4, 8000));
		CardData mineInHand = makeForward("In My Hand", "Wind", 2, 5000);
		mw.gameState.getP2Hand().add(mineInHand);
		castChaosAsP2(mw);

		assertTrue(mw.gameState.getP2Hand().contains(mineInHand));
		assertTrue(mw.p2ForwardCards.isEmpty());
	}

	@Test
	void chaosOffersNothingWhenTheForwardIsNotPutIntoTheBreakZone() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 4, 8000);
		placeP1Forward(mw, theirs);
		mw.p1ForwardTempTraits.get(0).add(CardData.Trait.CANNOT_BE_BROKEN);
		GameContext ctx = castChaosAsP2(mw);

		assertTrue(mw.p1ForwardCards.contains(theirs));
		verify(ctx, never()).opponentMayPlayCharacterFromHand(anyBoolean(), anyBoolean(), anyBoolean(), anyInt(),
				any(), anyInt(), any(), any(), any(), any(), any(), anyBoolean(), any(), anyBoolean(), any());
	}

	// =========================================================================================
	// 3-074R Atomos: "Choose 1 Forward. Deal it damage equal to the highest power Forward you control."
	// =========================================================================================

	private static final String ATOMOS_3_074R = "Choose 1 Forward. Deal it damage equal to the highest power "
			+ "Forward you control.";

	@Test
	void atomosDealsDamageEqualToYourHighestPowerForward() {
		MainWindow mw = new MainWindow();
		placeP2Forward(mw, makeForward("Small", "Earth", 2, 5000));
		placeP2Forward(mw, makeForward("Big", "Earth", 4, 8000));
		CardData theirs = makeForward("Theirs", "Water", 6, 10000);
		placeP1Forward(mw, theirs);
		castAsP2(mw, makeSummon("Atomos", "Earth", 4, ATOMOS_3_074R));
		assertEquals(8000, damageOn(mw, theirs));
	}

	// =========================================================================================
	// 3-087H Zeromus, the Condemner: "EX BURST Choose up to 1 Forward from your Break Zone of cost
	// equal to or less than the damage you have been dealt. Return it to your hand. Your opponent
	// selects 1 Forward of cost equal to or less than the damage you have been dealt and puts it
	// into the Break Zone."
	//
	// "Your opponent selects" was the caster choosing the opponent's Forward. Each half is tested
	// from the seat where only the CPU has a choice to make.
	// =========================================================================================

	private static final String ZEROMUS_3_087H = "[[ex]]EX BURST[[/]] Choose up to 1 Forward from your Break Zone of "
			+ "cost equal to or less than the damage you have been dealt. Return it to your hand. Your opponent "
			+ "selects 1 Forward of cost equal to or less than the damage you have been dealt and puts it into the "
			+ "Break Zone.";

	private static void takeDamage(List<CardData> damageZone, int points) {
		for (int i = 0; i < points; i++) damageZone.add(makeForward("Damage " + i, "Earth", 1, 1000));
	}

	@Test
	void zeromusReturnsAForwardFromYourBreakZoneWithinYourDamage() {
		// P2 casts at 3 damage. P1's one Forward costs 5, over the limit, so P1 has nothing to select.
		MainWindow mw = new MainWindow();
		takeDamage(mw.gameState.getP2DamageZone(), 3);
		CardData three = makeForward("Three", "Earth", 3, 7000);
		CardData four  = makeForward("Four", "Earth", 4, 8000);
		mw.gameState.getP2BreakZone().add(four);
		mw.gameState.getP2BreakZone().add(three);
		CardData theirs = makeForward("Theirs", "Water", 5, 9000);
		placeP1Forward(mw, theirs);
		castAsP2(mw, makeSummon("Zeromus, the Condemner", "Earth", 7, ZEROMUS_3_087H));

		assertTrue(mw.gameState.getP2Hand().contains(three));
		assertTrue(mw.gameState.getP2BreakZone().contains(four), "cost 4 is over 3 damage");
		assertTrue(mw.p1ForwardCards.contains(theirs), "cost 5 is over 3 damage");
	}

	@Test
	void zeromusLetsTheOpponentSelectWhichForwardTheyLose() {
		// P1 casts at 3 damage with an empty Break Zone; the CPU gives up its cheapest eligible one.
		MainWindow mw = new MainWindow();
		takeDamage(mw.gameState.getP1DamageZone(), 3);
		CardData two   = makeForward("Two", "Water", 2, 5000);
		CardData three = makeForward("Three", "Water", 3, 7000);
		CardData five  = makeForward("Five", "Water", 5, 9000);
		placeP2Forward(mw, three);
		placeP2Forward(mw, two);
		placeP2Forward(mw, five);
		castAsP1(mw, makeSummon("Zeromus, the Condemner", "Earth", 7, ZEROMUS_3_087H));

		assertTrue(mw.gameState.getP2BreakZone().contains(two));
		assertTrue(mw.p2ForwardCards.containsAll(List.of(three, five)));
	}

	// =========================================================================================
	// 3-102R Odin: "Choose 1 Forward. If it has 7000 power or less, break it. If you control a Job
	// Class Zero Cadet Forward, break it regardless of its power instead."
	//
	// Two sentences over one chosen Forward, and "instead" makes the second a replacement for the
	// first's test rather than a second break. Left to the plain break followup, find() matched
	// "break it" in the first sentence and broke whatever was chosen: the card ignored its power
	// gate AND the condition that lifts it, and broke any Forward on the table.
	//
	// The power read is the effective one, not the printed one — a 7000 Forward holding a lend is
	// out of range, and a 9000 one that has been cut down is in it.
	// =========================================================================================

	private static final String ODIN_BREAK_SUMMON =
			"Choose 1 Forward. If it has 7000 power or less, break it. "
			+ "If you control a Job Class Zero Cadet Forward, break it regardless of its power instead.";

	/** Casts Odin at P2's only Forward and reports whether it was broken. */
	private static boolean odinBreaks(MainWindow mw) {
		GameContext ctx = mw.buildGameContext(true);
		ctx.preloadTargets(List.of(fwd(false, 0)));
		Consumer<GameContext> effect = ActionResolver.parse(ODIN_BREAK_SUMMON, null);
		assertNotNull(effect, "the printed wording has to parse for the rest of this to mean anything");
		effect.accept(ctx);
		return mw.p2ForwardCards.isEmpty();
	}

	/** A board with one P2 Forward of {@code power}, and optionally a Class Zero Cadet for P1. */
	private static MainWindow odinBoard(int power, boolean withCadet) {
		MainWindow mw = new MainWindow();
		placeP2Forward(mw, makeForward("Victim", "Fire", 3, power));
		if (withCadet) placeP1Forward(mw, makeJobCard("Ace", "Fire", "Forward", "Class Zero Cadet"));
		return mw;
	}

	@Test
	void odinBreaksAForwardInsideThePowerGate() {
		assertTrue(odinBreaks(odinBoard(7000, false)), "7000 is \"7000 power or less\"");
		assertTrue(odinBreaks(odinBoard(3000, false)));
	}

	@Test
	void andLeavesOneAboveIt() {
		// The whole point of the gate, and what the card was doing to every Forward regardless.
		assertFalse(odinBreaks(odinBoard(9000, false)), "9000 is out of range with no Cadet out");
		assertFalse(odinBreaks(odinBoard(8000, false)));
	}

	@Test
	void unlessYouControlAClassZeroCadetForward() {
		assertTrue(odinBreaks(odinBoard(9000, true)), "the Cadet lifts the gate entirely");
	}

	@Test
	void andTheCadetHasToBeOneYouControl() {
		// "If you control" — the opponent's Cadet is not yours, and lifts nothing.
		MainWindow mw = odinBoard(9000, false);
		placeP2Forward(mw, makeJobCard("Ace", "Fire", "Forward", "Class Zero Cadet"));

		GameContext ctx = mw.buildGameContext(true);
		ctx.preloadTargets(List.of(fwd(false, 0)));
		ActionResolver.parse(ODIN_BREAK_SUMMON, null).accept(ctx);

		assertEquals(2, mw.p2ForwardCards.size(), "nothing broke");
	}

	@Test
	void andThePowerReadIsTheEffectiveOne() {
		// A 7000 Forward holding a +3000 lend is out of range when Odin resolves.
		MainWindow lent = odinBoard(7000, false);
		lent.buildGameContext(true).boostTarget(fwd(false, 0), 3000,
				EnumSet.noneOf(CardData.Trait.class));
		assertEquals(10000, lent.effectiveP2ForwardPower(0));
		assertFalse(odinBreaks(lent), "the lend puts it out of the gate");

		// And a 9000 one that has been cut down is inside it.
		MainWindow cut = odinBoard(9000, false);
		cut.buildGameContext(true).reduceTarget(fwd(false, 0), 3000,
				EnumSet.noneOf(CardData.Trait.class));
		assertEquals(6000, cut.effectiveP2ForwardPower(0));
		assertTrue(odinBreaks(cut), "and the reduction brings it in");
	}

	@Test
	void odinsTwoClausesAreNoLongerDescribedAsTwoBreaks() {
		assertEquals("ChooseCharacter / BreakIfPower(7000-) | IfControl(1+ Class Zero Cadet Forward: Break)",
				ActionResolver.fullDescription(ODIN_BREAK_SUMMON, null));
	}

	@Test
	void andAJobNameIsReadApartFromTheTypeNounAfterIt() {
		// Odin's condition exposed this, but it is not Odin's: the Job group ends on a lookahead
		// and so consumed no space, leaving the type group to start at one and fail. The lazy Job
		// group then backtracked over the noun, and 36 printings of "if you control a Job X
		// Forward" asked for a Job no card has.
		//
		// Asserted on the parts rather than on the rendering, because the singular form renders
		// identically either way — "1+ Class Zero Cadet Forward" whether the noun is the type or
		// the tail of the Job name, which is why nothing caught it.
		ControlCondition singular = CardData.parseControlCondition("a Job Class Zero Cadet Forward");
		assertNotNull(singular);
		assertEquals("Class Zero Cadet", singular.job(), "the noun is not part of the Job name");
		assertEquals("Forward", singular.cardType());

		ControlCondition plural = CardData.parseControlCondition("2 or more Job Standard Unit Backups");
		assertNotNull(plural);
		assertEquals("Standard Unit", plural.job());
		assertEquals("Backup", plural.cardType());
		assertEquals(2, plural.minCount());

		// And a condition with no Job at all still reads its type from the same slot.
		ControlCondition plain = CardData.parseControlCondition("a Fire Forward");
		assertNotNull(plain);
		assertNull(plain.job());
		assertEquals("Forward", plain.cardType());
		assertEquals("Fire", plain.element());
	}

	// =========================================================================================
	// 3-112H Exodus, the Judge-Sal: "Select 1 number. Your opponent selects 1 number. Break all
	// Forwards of cost equal to either number."
	//
	// The opponent's number used to be worked out by the AI from P1's Forwards, whoever cast it: a
	// human facing the CPU's Exodus never picked, and two networked clients worked out different
	// numbers from their mirrored boards.
	// =========================================================================================

	private static final String EXODUS_3_112H = "Select 1 number. Your opponent selects 1 number. Break all Forwards "
			+ "of cost equal to either number.";

	@Test
	void exodusBreaksEveryForwardOfEitherNumberOnBothSides() {
		MainWindow mw = new MainWindow();
		CardData p1Three = makeForward("P1 Three", "Water", 3, 7000);
		CardData p1Four  = makeForward("P1 Four", "Water", 4, 8000);
		CardData p1Five  = makeForward("P1 Five", "Water", 5, 9000);
		CardData p2Three = makeForward("P2 Three", "Lightning", 3, 7000);
		CardData p2Two   = makeForward("P2 Two", "Lightning", 2, 5000);
		for (CardData c : List.of(p1Three, p1Four, p1Five)) placeP1Forward(mw, c);
		for (CardData c : List.of(p2Three, p2Two)) placeP2Forward(mw, c);
		GameContext ctx = spy(mw.buildGameContext(false));
		doReturn(3).when(ctx).selectNumber(anyInt(), anyInt(), anyString());
		doReturn(5).when(ctx).opponentSelectsNumber(anyInt(), anyInt(), anyString());
		CardData exodus = makeSummon("Exodus, the Judge-Sal", "Lightning", 4, EXODUS_3_112H);
		ActionResolver.parse(exodus.summonEffect(), exodus).accept(ctx);

		assertEquals(List.of(p1Four), mw.p1ForwardCards);
		assertEquals(List.of(p2Two), mw.p2ForwardCards);
	}

	@Test
	void exodusAsksTheOpponentForTheSecondNumber() {
		// P1 casts and names 2; the CPU, answering for itself, names the cost most of P1's share.
		MainWindow mw = new MainWindow();
		CardData fourA = makeForward("Four A", "Water", 4, 8000);
		CardData fourB = makeForward("Four B", "Water", 4, 8000);
		CardData six   = makeForward("Six", "Water", 6, 10000);
		CardData cpuTwo = makeForward("CPU Two", "Lightning", 2, 5000);
		for (CardData c : List.of(fourA, fourB, six)) placeP1Forward(mw, c);
		placeP2Forward(mw, cpuTwo);
		GameContext ctx = spy(mw.buildGameContext(true));
		doReturn(2).when(ctx).selectNumber(anyInt(), anyInt(), anyString());
		CardData exodus = makeSummon("Exodus, the Judge-Sal", "Lightning", 4, EXODUS_3_112H);
		ActionResolver.parse(exodus.summonEffect(), exodus).accept(ctx);

		assertEquals(List.of(six), mw.p1ForwardCards, "the CPU named 4");
		assertTrue(mw.gameState.getP2BreakZone().contains(cpuTwo), "P1 named 2");
	}

	// =========================================================================================
	// 3-123R Famfrit, the Darkening Cloud: "EX BURST Both players select 1 Forward they control and
	// put it into the Break Zone."
	//
	// P1 is given no Forwards, so only the CPU has a choice to make; P1 selecting opens a dialog.
	// =========================================================================================

	private static final String FAMFRIT_3_123R = "[[ex]]EX BURST[[/]] Both players select 1 Forward they control and "
			+ "put it into the Break Zone.";

	@Test
	void famfritHasTheCasterPutTheirCheapestForwardIntoTheBreakZone() {
		MainWindow mw = new MainWindow();
		CardData two  = makeForward("Two", "Water", 2, 5000);
		CardData five = makeForward("Five", "Water", 5, 9000);
		placeP2Forward(mw, five);
		placeP2Forward(mw, two);
		castAsP2(mw, makeSummon("Famfrit, the Darkening Cloud", "Water", 3, FAMFRIT_3_123R));

		assertTrue(mw.gameState.getP2BreakZone().contains(two));
		assertTrue(mw.p2ForwardCards.contains(five), "one Forward each");
	}

	// =========================================================================================
	// 3-135H Syldra: "Choose up to 2 Forwards opponent controls. Return them to their owners' hand."
	// =========================================================================================

	private static final String SYLDRA_3_135H = "Choose up to 2 Forwards opponent controls. Return them to their "
			+ "owners' hand.";

	@Test
	void syldraReturnsTwoOfTheOpponentsForwardsToTheirHand() {
		MainWindow mw = new MainWindow();
		CardData a = makeForward("A", "Fire", 3, 7000);
		CardData b = makeForward("B", "Fire", 4, 8000);
		CardData mine = makeForward("Mine", "Water", 3, 7000);
		placeP1Forward(mw, a);
		placeP1Forward(mw, b);
		placeP2Forward(mw, mine);
		castAsP2(mw, makeSummon("Syldra", "Water", 6, SYLDRA_3_135H));

		assertTrue(mw.gameState.getP1Hand().containsAll(List.of(a, b)));
		assertTrue(mw.p2ForwardCards.contains(mine), "opponent's Forwards only");
	}

	// =========================================================================================
	// 3-145L Ultima, the High Seraph: "If you control a Light Forward, the cost to cast Ultima, The
	// High Seraph is reduced by 2. Remove from the game all the Forwards on the field other than
	// Light and Dark. Then, remove from the top of your deck twice the number of cards removed by
	// the previous effect."
	// =========================================================================================

	private static final String ULTIMA_3_145L = "If you control a Light Forward, the cost to cast Ultima, The High "
			+ "Seraph is reduced by 2. [[br]] Remove from the game all the Forwards on the field other than Light "
			+ "and Dark. Then, remove from the top of your deck twice the number of cards removed by the previous "
			+ "effect.";

	@Test
	void ultimaRemovesEveryForwardButLightAndDarkThenTwiceThatFromYourDeck() {
		MainWindow mw = new MainWindow();
		CardData fire  = makeForward("Fire One", "Fire", 3, 7000);
		CardData light = makeForward("Light One", "Light", 3, 7000);
		CardData water = makeForward("Water One", "Water", 3, 7000);
		CardData dark  = makeForward("Dark One", "Dark", 3, 7000);
		placeP1Forward(mw, fire);
		placeP1Forward(mw, light);
		placeP2Forward(mw, water);
		placeP2Forward(mw, dark);
		fillP2Deck(mw, 6);
		castAsP2(mw, makeSummon("Ultima, the High Seraph", "Light", 7, ULTIMA_3_145L));

		assertTrue(mw.gameState.getP1RemovedFromGame().contains(fire));
		assertTrue(mw.gameState.getP2RemovedFromGame().contains(water));
		assertEquals(List.of(light), mw.p1ForwardCards);
		assertEquals(List.of(dark), mw.p2ForwardCards);
		assertEquals(2, mw.gameState.getP2MainDeck().size(), "two removed, so four off the deck");
	}

	// =========================================================================================
	// 3-147L Zodiark, Keeper of Precepts: "If you control a Dark Forward, the cost to cast Zodiark,
	// Keeper of Precepts is reduced by 3. Break all the Forwards opponent controls. You receive
	// damage equal to the number of Forwards broken by this effect."
	// =========================================================================================

	private static final String ZODIARK_3_147L = "If you control a Dark Forward, the cost to cast Zodiark, Keeper of "
			+ "Precepts is reduced by 3.[[br]] Break all the Forwards opponent controls. You receive damage equal to "
			+ "the number of Forwards broken by this effect.";

	@Test
	void zodiarkBreaksTheOpponentsForwardsAndYouTakeDamageForEach() {
		MainWindow mw = new MainWindow();
		CardData a = makeForward("A", "Water", 3, 7000);
		CardData b = makeForward("B", "Water", 5, 9000);
		CardData mine = makeForward("Mine", "Dark", 3, 7000);
		placeP1Forward(mw, a);
		placeP1Forward(mw, b);
		placeP2Forward(mw, mine);
		fillP2Deck(mw, 4);
		castAsP2(mw, makeSummon("Zodiark, Keeper of Precepts", "Dark", 7, ZODIARK_3_147L));

		assertTrue(mw.gameState.getP1BreakZone().containsAll(List.of(a, b)));
		assertTrue(mw.p2ForwardCards.contains(mine), "opponent's Forwards only");
		assertEquals(2, mw.gameState.getP2DamageZone().size(), "one point per Forward broken");
	}

	@Test
	void zodiarkDealsYouNothingWhenItBreaksNothing() {
		MainWindow mw = new MainWindow();
		placeP2Forward(mw, makeForward("Mine", "Dark", 3, 7000));
		fillP2Deck(mw, 4);
		castAsP2(mw, makeSummon("Zodiark, Keeper of Precepts", "Dark", 7, ZODIARK_3_147L));

		assertTrue(mw.gameState.getP2DamageZone().isEmpty());
		assertEquals(1, mw.p2ForwardCards.size(), "and its own Forwards are untouched");
	}


	// =========================================================================================
	// 4-003C Ifrit: "EX BURST Choose 1 Forward. Deal it 4000 damage. If you control 5 or more Fire
	// Characters, deal it 7000 damage instead."
	// =========================================================================================

	private static final String IFRIT_4_003C = "[[ex]]EX BURST [[/]]Choose 1 Forward. Deal it 4000 damage. If you "
			+ "control 5 or more Fire Characters, deal it 7000 damage instead.";

	/** P2 with {@code fireBackups} Fire Backups casts Ifrit at P1's 9000 Forward. */
	private static int ifrit4003Damage(int fireBackups) {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 5, 9000);
		placeP1Forward(mw, theirs);
		for (int i = 0; i < fireBackups; i++) placeP2Backup(mw, makeBackup("Fire " + i, "Fire", 2));
		castAsP2(mw, makeSummon("Ifrit", "Fire", 2, IFRIT_4_003C));
		return damageOn(mw, theirs);
	}

	@Test
	void ifritDeals4000BelowFiveFireCharacters() {
		assertEquals(4000, ifrit4003Damage(4));
	}

	@Test
	void ifritDeals7000InsteadWithFiveFireCharacters() {
		assertEquals(7000, ifrit4003Damage(5));
	}

	// =========================================================================================
	// 4-016R Bahamut: "Choose 1 Forward. Deal it 8000 damage. If it is put from the field into the
	// Break Zone this turn, remove it from the game instead."
	// =========================================================================================

	private static final String BAHAMUT_4_016R = "Choose 1 Forward. Deal it 8000 damage. If it is put from the field "
			+ "into the Break Zone this turn, remove it from the game instead.";

	@Test
	void bahamutRemovesTheForwardItBreaksFromTheGame() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 4, 8000);
		placeP1Forward(mw, theirs);
		castAsP2(mw, makeSummon("Bahamut", "Fire", 4, BAHAMUT_4_016R));

		assertTrue(mw.gameState.getP1RemovedFromGame().contains(theirs));
		assertFalse(mw.gameState.getP1BreakZone().contains(theirs));
	}

	@Test
	void bahamutDeals8000Damage() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 6, 10000);
		placeP1Forward(mw, theirs);
		castAsP2(mw, makeSummon("Bahamut", "Fire", 4, BAHAMUT_4_016R));
		assertEquals(8000, damageOn(mw, theirs));
	}

	// =========================================================================================
	// 4-033C Shiva: "EX BURST Choose 1 dull Forward. Deal it 6000 damage and 1000 more damage for each
	// Card Name Shiva in your Break Zone."
	// =========================================================================================

	private static final String SHIVA_4_033C = "[[ex]]EX BURST[[/]] Choose 1 dull Forward. Deal it 6000 damage and "
			+ "1000 more damage for each Card Name Shiva in your Break Zone.";

	@Test
	void shivaDeals6000Plus1000PerShivaInYourBreakZone() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 6, 10000);
		placeP1Forward(mw, theirs);
		dullP1Forward(mw, theirs);
		mw.gameState.getP2BreakZone().add(makeSummon("Shiva", "Ice", 2, "Draw 1 card."));
		mw.gameState.getP2BreakZone().add(makeSummon("Shiva", "Ice", 3, "Draw 1 card."));
		mw.gameState.getP2BreakZone().add(makeSummon("Ifrit", "Fire", 2, "Draw 1 card."));
		castAsP2(mw, makeSummon("Shiva", "Ice", 2, SHIVA_4_033C));
		assertEquals(8000, damageOn(mw, theirs), "two Shiva: 6000 + 2000");
	}

	@Test
	void shivaDoesNothingWithOnlyAnActiveForward4033() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 3, 5000);
		placeP1Forward(mw, theirs);
		castAsP2(mw, makeSummon("Shiva", "Ice", 2, SHIVA_4_033C));
		assertEquals(0, damageOn(mw, theirs));
	}

	// =========================================================================================
	// 4-046R Lich: "Deal each Forward opponent controls damage equal to half of its power (round up to
	// the nearest 1000)."
	// =========================================================================================

	private static final String LICH_4_046R = "Deal each Forward opponent controls damage equal to half of its power "
			+ "(round up to the nearest 1000).";

	@Test
	void lichDealsHalfPowerRoundedUpToEachOpposingForward() {
		MainWindow mw = new MainWindow();
		CardData odd  = makeForward("Odd", "Water", 3, 7000);
		CardData even = makeForward("Even", "Water", 4, 8000);
		CardData mine = makeForward("Mine", "Ice", 3, 7000);
		placeP1Forward(mw, odd);
		placeP1Forward(mw, even);
		placeP2Forward(mw, mine);
		castAsP2(mw, makeSummon("Lich", "Ice", 4, LICH_4_046R));

		assertEquals(4000, damageOn(mw, odd), "3500 rounds up to 4000");
		assertEquals(4000, damageOn(mw, even));
		assertEquals(0, mw.p2ForwardDamage.get(0), "opponent's Forwards only");
	}

	// =========================================================================================
	// 4-051H Alexander: "EX BURST Choose 1 Forward of power 9000 or more. Break it."
	// =========================================================================================

	private static final String ALEXANDER_4_051H = "[[ex]]EX BURST[[/]] Choose 1 Forward of power 9000 or more. Break it.";

	@Test
	void alexanderBreaksAForwardOfPower9000OrMore() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 5, 9000);
		placeP1Forward(mw, theirs);
		castAsP2(mw, makeSummon("Alexander", "Wind", 5, ALEXANDER_4_051H));
		assertTrue(mw.gameState.getP1BreakZone().contains(theirs));
	}

	@Test
	void alexanderDoesNothingWithOnlyAForwardUnder9000Power() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 5, 8000);
		placeP1Forward(mw, theirs);
		castAsP2(mw, makeSummon("Alexander", "Wind", 5, ALEXANDER_4_051H));
		assertTrue(mw.p1ForwardCards.contains(theirs));
	}

	// =========================================================================================
	// 4-052C Alexander: "EX BURST Select 1 of the 2 following actions. 'Choose 1 Monster. Break it.'
	// 'Choose 1 Backup you control. Activate it. Draw 1 card.'"
	// =========================================================================================

	private static final String ALEXANDER_4_052C = "[[ex]]EX BURST[[/]] Select 1 of the 2 following actions.[[br]] "
			+ "\"Choose 1 Monster. Break it.\"[[br]] \"Choose 1 Backup you control. Activate it. Draw 1 card.\"";

	@Test
	void alexandersFirstActionBreaksAMonster() {
		MainWindow mw = new MainWindow();
		CardData monster = makeMonster("Their Monster", "Water", 3);
		placeP1Monster(mw, monster);
		castAsP2Selecting(mw, makeSummon("Alexander", "Wind", 2, ALEXANDER_4_052C), 0);
		assertTrue(mw.gameState.getP1BreakZone().contains(monster));
	}

	@Test
	void alexandersSecondActionActivatesYourBackupAndDraws() {
		MainWindow mw = new MainWindow();
		placeP2Backup(mw, makeBackup("Mine", "Wind", 2));
		mw.p2BackupStates[0] = CardState.DULL;
		fillP2Deck(mw, 2);
		castAsP2Selecting(mw, makeSummon("Alexander", "Wind", 2, ALEXANDER_4_052C), 1);

		assertEquals(CardState.ACTIVE, mw.p2BackupStates[0]);
		assertEquals(1, mw.gameState.getP2Hand().size());
	}

	// =========================================================================================
	// 4-073C Atomos: "EX BURST Select 1 of the 2 following actions. 'Choose 1 dull Forward. Break it.'
	// 'Choose 1 Forward. Deal it 8000 damage.'"
	// =========================================================================================

	private static final String ATOMOS_4_073C = "[[ex]]EX BURST[[/]] Select 1 of the 2 following actions.[[br]] "
			+ "\"Choose 1 dull Forward. Break it.\"[[br]] \"Choose 1 Forward. Deal it 8000 damage.\"";

	@Test
	void atomossFirstActionBreaksADullForward() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 6, 10000);
		placeP1Forward(mw, theirs);
		dullP1Forward(mw, theirs);
		castAsP2Selecting(mw, makeSummon("Atomos", "Earth", 6, ATOMOS_4_073C), 0);
		assertTrue(mw.gameState.getP1BreakZone().contains(theirs));
	}

	@Test
	void atomossSecondActionDeals8000Damage() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 6, 10000);
		placeP1Forward(mw, theirs);
		castAsP2Selecting(mw, makeSummon("Atomos", "Earth", 6, ATOMOS_4_073C), 1);
		assertEquals(8000, damageOn(mw, theirs));
	}

	// =========================================================================================
	// 4-093R Hecatoncheir: "Choose 1 Forward you control and 1 Forward opponent controls. Each Forward
	// deals damage equal to its power to the other."
	// =========================================================================================

	private static final String HECATONCHEIR_4_093R = "Choose 1 Forward you control and 1 Forward opponent controls. "
			+ "Each Forward deals damage equal to its power to the other.";

	@Test
	void hecatoncheirMakesTheTwoForwardsFight() {
		MainWindow mw = new MainWindow();
		CardData mine   = makeForward("Mine", "Earth", 4, 7000);
		CardData theirs = makeForward("Theirs", "Water", 3, 5000);
		placeP2Forward(mw, mine);
		placeP1Forward(mw, theirs);
		castAsP2(mw, makeSummon("Hecatoncheir", "Earth", 2, HECATONCHEIR_4_093R));

		assertTrue(mw.gameState.getP1BreakZone().contains(theirs), "7000 into a 5000");
		assertEquals(5000, mw.p2ForwardDamage.get(0), "5000 back into a 7000");
	}

	// =========================================================================================
	// 4-114L Raiden: "Choose up to 2 Forwards opponent controls. Remove the first Forward from the
	// game, and break the other."
	// =========================================================================================

	private static final String RAIDEN_4_114L = "Choose up to 2 Forwards opponent controls. Remove the first Forward "
			+ "from the game, and break the other.";

	@Test
	void raidenRemovesOneForwardFromTheGameAndBreaksTheOther() {
		MainWindow mw = new MainWindow();
		CardData a = makeForward("A", "Water", 3, 7000);
		CardData b = makeForward("B", "Water", 4, 8000);
		CardData mine = makeForward("Mine", "Lightning", 3, 7000);
		placeP1Forward(mw, a);
		placeP1Forward(mw, b);
		placeP2Forward(mw, mine);
		castAsP2(mw, makeSummon("Raiden", "Lightning", 9, RAIDEN_4_114L));

		assertTrue(mw.p1ForwardCards.isEmpty());
		assertEquals(1, mw.gameState.getP1RemovedFromGame().size(), "one removed");
		assertEquals(1, mw.gameState.getP1BreakZone().size(), "one broken");
		assertTrue(mw.p2ForwardCards.contains(mine), "opponent's Forwards only");
	}

	@Test
	void raidenBreaksTheRightForwardWhenTheFirstSatInALowerSlot() {
		// Removing the first shifts the slots behind it. The other used to be broken by its old
		// index: out of bounds here with two, the wrong Forward with three.
		MainWindow mw = new MainWindow();
		CardData a = makeForward("A", "Water", 3, 7000);
		CardData b = makeForward("B", "Water", 4, 8000);
		CardData c = makeForward("C", "Water", 5, 9000);
		for (CardData f : List.of(a, b, c)) placeP1Forward(mw, f);
		GameContext ctx = spy(mw.buildGameContext(false));
		doReturn(List.of(new ForwardTarget(true, 0, ForwardTarget.CardZone.FORWARD),
				new ForwardTarget(true, 1, ForwardTarget.CardZone.FORWARD))).when(ctx).consumePreloadedTargets();
		CardData raiden = makeSummon("Raiden", "Lightning", 9, RAIDEN_4_114L);
		ActionResolver.parse(raiden.summonEffect(), raiden).accept(ctx);

		assertTrue(mw.gameState.getP1RemovedFromGame().contains(a), "the first, removed");
		assertTrue(mw.gameState.getP1BreakZone().contains(b), "the other, broken");
		assertEquals(List.of(c), mw.p1ForwardCards, "and nothing else");
	}

	// =========================================================================================
	// 4-116C Ramuh: "EX BURST Select 1 of the 2 following actions. 'Choose 1 Forward of cost 2 or less.
	// Break it.' 'Choose 1 Monster of cost 2 or less. Break it.'"
	// =========================================================================================

	private static final String RAMUH_4_116C = "[[ex]]EX BURST[[/]] Select 1 of the 2 following actions.[[br]] "
			+ "\"Choose 1 Forward of cost 2 or less. Break it.\"[[br]] \"Choose 1 Monster of cost 2 or less. Break it.\"";

	@Test
	void ramuhsFirstActionBreaksACheapForward() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 2, 5000);
		placeP1Forward(mw, theirs);
		castAsP2Selecting(mw, makeSummon("Ramuh", "Lightning", 1, RAMUH_4_116C), 0);
		assertTrue(mw.gameState.getP1BreakZone().contains(theirs));
	}

	@Test
	void ramuhsFirstActionLeavesACost3Forward() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 3, 5000);
		placeP1Forward(mw, theirs);
		castAsP2Selecting(mw, makeSummon("Ramuh", "Lightning", 1, RAMUH_4_116C), 0);
		assertTrue(mw.p1ForwardCards.contains(theirs));
	}

	@Test
	void ramuhsSecondActionBreaksACheapMonster() {
		MainWindow mw = new MainWindow();
		CardData monster = makeMonster("Their Monster", "Water", 2);
		placeP1Monster(mw, monster);
		castAsP2Selecting(mw, makeSummon("Ramuh", "Lightning", 1, RAMUH_4_116C), 1);
		assertTrue(mw.gameState.getP1BreakZone().contains(monster));
	}

	// =========================================================================================
	// 4-128C PuPu: "EX BURST Discard 1 card. Then, draw 2 cards."
	// =========================================================================================

	private static final String PUPU_4_128C = "[[ex]]EX BURST[[/]] Discard 1 card. Then, draw 2 cards.[[br]]";

	@Test
	void pupuDiscardsOneThenDrawsTwo() {
		MainWindow mw = new MainWindow();
		CardData spare = makeForward("Spare", "Water", 2, 5000);
		mw.gameState.getP2Hand().add(spare);
		fillP2Deck(mw, 3);
		castAsP2(mw, makeSummon("PuPu", "Water", 1, PUPU_4_128C));

		assertTrue(mw.gameState.getP2BreakZone().contains(spare), "the discard");
		assertEquals(2, mw.gameState.getP2Hand().size(), "then two drawn");
		assertEquals(1, mw.gameState.getP2MainDeck().size());
	}

	// =========================================================================================
	// 4-143R Leviathan: "EX BURST Choose 1 Forward you control and 1 Forward opponent controls. Return
	// them to their owners' hand."
	// =========================================================================================

	private static final String LEVIATHAN_4_143R = "[[ex]]EX BURST[[/]] Choose 1 Forward you control and 1 Forward "
			+ "opponent controls. Return them to their owners' hand.";

	@Test
	void leviathanReturnsOneForwardFromEachSideToItsOwner() {
		MainWindow mw = new MainWindow();
		CardData mine   = makeForward("Mine", "Water", 3, 7000);
		CardData theirs = makeForward("Theirs", "Fire", 4, 8000);
		placeP2Forward(mw, mine);
		placeP1Forward(mw, theirs);
		castAsP2(mw, makeSummon("Leviathan", "Water", 3, LEVIATHAN_4_143R));

		assertTrue(mw.gameState.getP2Hand().contains(mine));
		assertTrue(mw.gameState.getP1Hand().contains(theirs));
	}

	// =========================================================================================
	// 5-003C Ifrit: "EX BURST Choose 1 Forward. You may discard 1 Card Name Ifrit from your hand. If
	// you do so, deal it 10000 damage. If not, deal it 5000 damage."
	//
	// Bahamut Fury 1-190S's shape with a named discard: only an Ifrit in hand buys the 10000, and
	// any other card there is left alone.
	//
	// Unlike Bahamut Fury's any-card discard, the CPU passes on a named one
	// (GameContextImpl.mayDiscardCardNameFromHandOrElse), so the paid branch is driven through a
	// mock that answers the offer.
	// =========================================================================================

	private static final String IFRIT_5_003C = "[[ex]]EX BURST[[/]]Choose 1 Forward. You may discard 1 Card Name "
			+ "Ifrit from your hand. If you do so, deal it 10000 damage. If not, deal it 5000 damage.";

	/** Casts Ifrit against a mock that chose {@code t} and answers the discard offer with {@code discards}. */
	private static GameContext castIfrit5Answering(ForwardTarget t, boolean discards) {
		GameContext ctx = contextChoosing(List.of(t));
		doAnswer(inv -> {
			inv.<Consumer<GameContext>>getArgument(discards ? 1 : 2).accept(ctx);
			return null;
		}).when(ctx).mayDiscardCardNameFromHandOrElse(any(), any(), any());
		CardData ifrit = makeSummon("Ifrit", "Fire", 2, IFRIT_5_003C);
		ActionResolver.parse(ifrit.summonEffect(), ifrit).accept(ctx);
		return ctx;
	}

	@Test
	void ifritOffersADiscardOfACardNamedIfritAndDeals10000IfPaid() {
		ForwardTarget t = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);
		GameContext ctx = castIfrit5Answering(t, true);

		verify(ctx).mayDiscardCardNameFromHandOrElse(eq("Ifrit"), any(), any());
		verify(ctx, never()).mayDiscardCardOfTypeFromHandOrElse(any(), any(), any());
		verify(ctx).damageTarget(t, 10000);
		verify(ctx, never()).damageTarget(t, 5000);
	}

	@Test
	void ifritDeals5000WhenTheDiscardIsDeclined() {
		ForwardTarget t = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);
		GameContext ctx = castIfrit5Answering(t, false);

		verify(ctx).damageTarget(t, 5000);
		verify(ctx, never()).damageTarget(t, 10000);
	}

	@Test
	void ifritDeals5000WhenTheHandHoldsNoIfrit() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 5, 9000);
		CardData spare  = makeForward("Spare", "Fire", 2, 5000);
		placeP1Forward(mw, theirs);
		mw.gameState.getP2Hand().add(spare);
		castAsP2(mw, makeSummon("Ifrit", "Fire", 2, IFRIT_5_003C));

		assertEquals(5000, damageOn(mw, theirs));
		assertEquals(List.of(spare), mw.gameState.getP2Hand(), "only a card named Ifrit pays");
	}

	// =========================================================================================
	// 5-019L Phoenix: "Choose 1 Forward of cost 3 or less in your Break Zone and up to 1 Forward
	// opponent controls. Play the former onto the field dull and deal the latter 8000 damage."
	// =========================================================================================

	private static final String PHOENIX_5_019L = "Choose 1 Forward of cost 3 or less in your Break Zone and up to 1 "
			+ "Forward opponent controls. Play the former onto the field dull and deal the latter 8000 damage.";

	@Test
	void phoenixPlaysACheapForwardFromTheBreakZoneDullAndDamagesTheOpponents() {
		MainWindow mw = new MainWindow();
		CardData fallen   = makeForward("Fallen", "Fire", 3, 7000);
		CardData tooDear  = makeForward("Too Dear", "Fire", 4, 8000);
		CardData theirs   = makeForward("Theirs", "Water", 4, 8000);
		mw.gameState.getP2BreakZone().add(tooDear);
		mw.gameState.getP2BreakZone().add(fallen);
		placeP1Forward(mw, theirs);
		castAsP2(mw, makeSummon("Phoenix", "Fire", 7, PHOENIX_5_019L));

		assertEquals(List.of(fallen), mw.p2ForwardCards, "cost 3 or less");
		assertEquals(CardState.DULL, mw.p2ForwardStates.get(0), "and it enters dull");
		assertTrue(mw.gameState.getP2BreakZone().contains(tooDear), "cost 4 stays behind");
		assertTrue(mw.gameState.getP1BreakZone().contains(theirs), "8000 breaks an 8000 Forward");
	}

	@Test
	void phoenixStillPlaysTheFormerWithNoOpposingForward() {
		// "up to 1 Forward opponent controls" — an empty side is not a failed choice.
		MainWindow mw = new MainWindow();
		CardData fallen = makeForward("Fallen", "Fire", 2, 5000);
		mw.gameState.getP2BreakZone().add(fallen);
		castAsP2(mw, makeSummon("Phoenix", "Fire", 7, PHOENIX_5_019L));

		assertEquals(List.of(fallen), mw.p2ForwardCards);
		assertFalse(mw.gameState.getP2BreakZone().contains(fallen));
	}

	// =========================================================================================
	// 5-032H Glasya Labolas: "Select up to 2 of the 4 following actions. "Your opponent discards 1
	// card from his/her hand." "Choose 1 Forward. Dull it." "Choose 1 Forward. Freeze it."
	// "Choose 1 dull Forward. Deal it 7000 damage.""
	//
	// P1's hand holds one card whenever the discard is taken, so P1's forced discard needs no
	// answer.
	// =========================================================================================

	private static final String GLASYA_LABOLAS_5_032H = "Select up to 2 of the 4 following actions.[[br]] "
			+ "\"Your opponent discards 1 card from his/her hand.\"[[br]] \"Choose 1 Forward. Dull it.\"[[br]] "
			+ "\"Choose 1 Forward. Freeze it.\"[[br]] \"Choose 1 dull Forward. Deal it 7000 damage.\"";

	private static CardData glasyaLabolas() {
		return makeSummon("Glasya Labolas", "Ice", 3, GLASYA_LABOLAS_5_032H);
	}

	@Test
	void glasyaLabolasDullsThenFreezesTheSameForward() {
		MainWindow mw = new MainWindow();
		placeP1Forward(mw, makeForward("Theirs", "Water", 4, 8000));
		castAsP2Selecting(mw, glasyaLabolas(), 1, 2);

		assertEquals(CardState.DULL, mw.p1ForwardStates.get(0));
		assertTrue(mw.p1ForwardFrozen.get(0));
	}

	@Test
	void glasyaLabolasMakesTheOpponentDiscardAndDamagesOnlyADullForward() {
		MainWindow mw = new MainWindow();
		CardData held   = makeForward("Held", "Water", 2, 5000);
		CardData dull   = makeForward("Dull", "Water", 4, 7000);
		CardData active = makeForward("Active", "Water", 4, 7000);
		mw.gameState.getP1Hand().add(held);
		placeP1Forward(mw, active);
		placeP1Forward(mw, dull);
		dullP1Forward(mw, dull);
		castAsP2Selecting(mw, glasyaLabolas(), 0, 3);

		assertTrue(mw.gameState.getP1BreakZone().contains(held), "the discard");
		assertTrue(mw.gameState.getP1BreakZone().contains(dull), "7000 breaks the dull 7000 Forward");
		assertEquals(List.of(active), mw.p1ForwardCards, "an active Forward is not a legal choice");
	}

	@Test
	void glasyaLabolasMayTakeASingleAction() {
		MainWindow mw = new MainWindow();
		placeP1Forward(mw, makeForward("Theirs", "Water", 4, 8000));
		castAsP2Selecting(mw, glasyaLabolas(), 2);

		assertTrue(mw.p1ForwardFrozen.get(0));
		assertEquals(CardState.ACTIVE, mw.p1ForwardStates.get(0), "the dull was not selected");
	}

	// =========================================================================================
	// 5-044C Mateus, the Corrupt: "Choose 1 blocking Forward. Break it."
	// =========================================================================================

	private static final String MATEUS_5_044C = "Choose 1 blocking Forward. Break it.";

	@Test
	void mateusBreaksTheBlockingForward() {
		MainWindow mw = new MainWindow();
		CardData idle     = makeForward("Idle", "Water", 2, 5000);
		CardData blocking = makeForward("Blocking", "Water", 5, 9000);
		placeP1Forward(mw, idle);
		placeP1Forward(mw, blocking);
		mw.p1BlockingIdx = 1;
		castAsP2(mw, makeSummon("Mateus, the Corrupt", "Ice", 1, MATEUS_5_044C));

		assertTrue(mw.gameState.getP1BreakZone().contains(blocking));
		assertEquals(List.of(idle), mw.p1ForwardCards, "only a blocking Forward");
	}

	@Test
	void mateusDoesNothingWhenNoForwardIsBlocking() {
		MainWindow mw = new MainWindow();
		CardData idle = makeForward("Idle", "Water", 2, 5000);
		placeP1Forward(mw, idle);
		castAsP2(mw, makeSummon("Mateus, the Corrupt", "Ice", 1, MATEUS_5_044C));
		assertEquals(List.of(idle), mw.p1ForwardCards);
	}

	// =========================================================================================
	// 5-049C Asura: "EX BURST Choose 1 Forward you control. Activate it and negate all damage dealt
	// to it. Draw 1 card."
	// =========================================================================================

	private static final String ASURA_5_049C = "[[ex]]EX BURST [[/]]Choose 1 Forward you control. Activate it and "
			+ "negate all damage dealt to it. Draw 1 card.";

	@Test
	void asuraActivatesAndHealsYourForwardThenDraws() {
		MainWindow mw = new MainWindow();
		CardData mine   = makeForward("Mine", "Wind", 3, 7000);
		CardData theirs = makeForward("Theirs", "Water", 3, 7000);
		placeP2Forward(mw, mine);
		placeP1Forward(mw, theirs);
		dullP2Forward(mw, mine);
		dullP1Forward(mw, theirs);
		mw.p2ForwardDamage.set(0, 4000);
		mw.p1ForwardDamage.set(0, 4000);
		fillP2Deck(mw, 2);
		castAsP2(mw, makeSummon("Asura", "Wind", 2, ASURA_5_049C));

		assertEquals(CardState.ACTIVE, mw.p2ForwardStates.get(0));
		assertEquals(0, mw.p2ForwardDamage.get(0), "the damage already dealt is negated");
		assertEquals(1, mw.gameState.getP2Hand().size(), "then a card drawn");
		assertEquals(CardState.DULL, mw.p1ForwardStates.get(0), "a Forward you control only");
		assertEquals(4000, damageOn(mw, theirs));
	}

	// =========================================================================================
	// 5-062L Diabolos: "Select up to 2 of the 4 following actions. "Choose 1 Forward of cost 5 or
	// more. Break it." "Choose 1 Forward of cost 4 or less. Until the end of the turn, its power
	// becomes 1000." "Activate all the Forwards you control." "Activate all the Backups you
	// control.""
	//
	// Board tests for the whole card. The section below it covers the second option's wording.
	// =========================================================================================

	private static final String DIABOLOS_5_062L = "Select up to 2 of the 4 following actions.[[br]] "
			+ "\"Choose 1 Forward of cost 5 or more. Break it.\"[[br]] "
			+ "\"Choose 1 Forward of cost 4 or less. Until the end of the turn, its power becomes 1000. \"[[br]] "
			+ "\"Activate all the Forwards you control.\"[[br]] \"Activate all the Backups you control.\"";

	private static CardData diabolos() {
		return makeSummon("Diabolos", "Wind", 5, DIABOLOS_5_062L);
	}

	@Test
	void diabolosBreaksACostlyForwardAndShrinksACheapOne() {
		MainWindow mw = new MainWindow();
		CardData costly = makeForward("Costly", "Water", 5, 9000);
		CardData cheap  = makeForward("Cheap", "Water", 4, 8000);
		placeP1Forward(mw, costly);
		placeP1Forward(mw, cheap);
		castAsP2Selecting(mw, diabolos(), 0, 1);

		assertTrue(mw.gameState.getP1BreakZone().contains(costly), "cost 5 or more");
		assertEquals(List.of(cheap), mw.p1ForwardCards, "cost 4 is not a legal break");
		assertEquals(1000, mw.effectiveP1ForwardPower(0), "but is a legal shrink");
	}

	@Test
	void diabolosFirstOptionLeavesACost4Forward() {
		MainWindow mw = new MainWindow();
		CardData cheap = makeForward("Cheap", "Water", 4, 8000);
		placeP1Forward(mw, cheap);
		castAsP2Selecting(mw, diabolos(), 0);
		assertEquals(List.of(cheap), mw.p1ForwardCards);
	}

	@Test
	void diabolosActivatesYourForwardsAndBackups() {
		MainWindow mw = new MainWindow();
		CardData mine   = makeForward("Mine", "Wind", 3, 7000);
		CardData theirs = makeForward("Theirs", "Water", 3, 7000);
		placeP2Forward(mw, mine);
		placeP1Forward(mw, theirs);
		placeP2Backup(mw, makeBackup("My Backup", "Wind", 2));
		dullP2Forward(mw, mine);
		dullP1Forward(mw, theirs);
		mw.p2BackupStates[0] = CardState.DULL;
		castAsP2Selecting(mw, diabolos(), 2, 3);

		assertEquals(CardState.ACTIVE, mw.p2ForwardStates.get(0));
		assertEquals(CardState.ACTIVE, mw.p2BackupStates[0]);
		assertEquals(CardState.DULL, mw.p1ForwardStates.get(0), "only the Forwards you control");
	}

	// =========================================================================================
	// 5-062L Diabolos, second option: "Choose 1 Forward of cost 4 or less. Until the end of the
	// turn, its power becomes 1000."
	//
	// The same act FOLLOWUP_POWER_BECOMES already handled, with the duration fronted instead of
	// trailing. Only this card and 3-066R Barbariccia print it that way, and both went unread —
	// the pattern required the "until the end of the turn" to come last.
	// =========================================================================================

	private static final String DIABOLOS_MODAL_SUMMON =
			"Select up to 2 of the 4 following actions. "
			+ "\"Choose 1 Forward of cost 5 or more. Break it.\" "
			+ "\"Choose 1 Forward of cost 4 or less. Until the end of the turn, its power becomes 1000. \" "
			+ "\"Activate all the Forwards you control.\" "
			+ "\"Activate all the Backups you control.\"";

	private static final String FRONTED_POWER_BECOMES =
			"Choose 1 Forward of cost 4 or less. Until the end of the turn, its power becomes 1000.";

	@Test
	void aFrontedUntilEndOfTurnStillSetsThePower() {
		ForwardTarget victim = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);
		GameContext ctx = mock(GameContext.class);
		when(ctx.consumePreloadedTargets()).thenReturn(null);
		when(ctx.selectCharacters(anyInt(), anyBoolean(), anyBoolean(), anyBoolean(), any(), any(),
				anyInt(), any(), anyInt(), any(), anyBoolean(), anyBoolean(), anyBoolean(),
				any(), any(), any(), any(), anyBoolean(), any(), anyBoolean()))
				.thenReturn(List.of(victim));

		ActionResolver.parse(FRONTED_POWER_BECOMES, null).accept(ctx);

		verify(ctx).setTargetBasePower(victim, 1000);
	}

	@Test
	void andTheOtherPrintingOfThatWordingReadsTheSameWay() {
		// 3-066R Barbariccia's enters-the-field ability, the corpus's only other fronted form.
		assertEquals("ChooseCharacter / PowerBecomes",
				ActionResolver.fullDescription(
						"Choose 1 Forward opponent controls. Until the end of the turn, its power becomes 1000.",
						null));
	}

	@Test
	void diabolosNamesAllFourOfItsOptions() {
		assertEquals("SelectFollowingActions(up to 2 of 4: ChooseCharacter / Break "
						+ "| ChooseCharacter / PowerBecomes | AllFieldEffect | AllFieldEffect)",
				ActionResolver.fullDescription(DIABOLOS_MODAL_SUMMON, null));
	}

	// =========================================================================================
	// 5-077H Carbuncle: "Choose 1 Forward. It gains +2000 power until the end of the turn. If its
	// power has become 9000 or less, return Carbuncle to your hand."
	//
	// Cast through the Stack rather than castAsP2: returning the Summon is something only a real
	// resolution can do, since the card goes to hand in place of the Break Zone as it leaves.
	// =========================================================================================

	private static final String CARBUNCLE_5_077H = "Choose 1 Forward. It gains +2000 power until the end of the "
			+ "turn. If its power has become 9000 or less, return Carbuncle to your hand.";

	/** P2 casts Carbuncle on its own Forward of {@code power}; P1 passes and it resolves. */
	private static MainWindow castCarbuncleOn(int power, CardData carbuncle) {
		MainWindow mw = new MainWindow();
		placeP2Forward(mw, makeForward("Mine", "Earth", 3, power));
		mw.gameState.getIdentity().put(carbuncle, false);  // whose Break Zone it goes to
		mw.pushSummonOnStack(carbuncle, false, 0, 0, false, null, false);
		mw.passStackPriority();
		return mw;
	}

	@Test
	void carbuncleReturnsToHandWhenThePowerEndsAt9000OrLess() {
		CardData carbuncle = makeSummon("Carbuncle", "Earth", 2, CARBUNCLE_5_077H);
		MainWindow mw = castCarbuncleOn(5000, carbuncle);

		assertEquals(7000, mw.effectiveP2ForwardPower(0));
		assertTrue(mw.gameState.getP2Hand().contains(carbuncle));
		assertFalse(mw.gameState.getP2BreakZone().contains(carbuncle));
	}

	@Test
	void carbuncleReturnsAtExactly9000() {
		CardData carbuncle = makeSummon("Carbuncle", "Earth", 2, CARBUNCLE_5_077H);
		MainWindow mw = castCarbuncleOn(7000, carbuncle);

		assertEquals(9000, mw.effectiveP2ForwardPower(0));
		assertTrue(mw.gameState.getP2Hand().contains(carbuncle), "\"9000 or less\" includes 9000");
	}

	@Test
	void carbuncleGoesToTheBreakZoneWhenThePowerEndsAbove9000() {
		CardData carbuncle = makeSummon("Carbuncle", "Earth", 2, CARBUNCLE_5_077H);
		MainWindow mw = castCarbuncleOn(8000, carbuncle);

		assertEquals(10000, mw.effectiveP2ForwardPower(0));
		assertTrue(mw.gameState.getP2BreakZone().contains(carbuncle));
		assertFalse(mw.gameState.getP2Hand().contains(carbuncle));
	}

	@Test
	void carbuncleIsDescribedWithItsGate() {
		// "It gains +2000 power until the end of the turn" puts the duration last, which takes the
		// boost's other branch from "Until the end of the turn, it gains"; both read the gate.
		assertEquals("ChooseCharacter / PowerBoost + IfPowerBecame(9000 or less: ReturnNamedToHand)",
				ActionResolver.fullDescription(CARBUNCLE_5_077H, null));
	}

	// =========================================================================================
	// 5-081C Cockatrice: "Choose 1 Forward. During this turn, it cannot attack or block, and if it
	// is dealt damage, the damage becomes 0 instead."
	//
	// Two clauses in one sentence, and each half's own branch scans with find() — so whichever ran
	// first would have claimed its clause and dropped the other. Read together, ahead of both.
	//
	// The shield is the unspent kind. The card names no number of hits, so a Forward dealt damage
	// twice in a turn takes neither; the one-shot shield beside it would have stopped only the
	// first.
	// =========================================================================================

	private static final String COCKATRICE_SUMMON =
			"Choose 1 Forward. During this turn, it cannot attack or block, "
			+ "and if it is dealt damage, the damage becomes 0 instead.";

	@Test
	void cockatriceLocksTheForwardAndShieldsIt() {
		MainWindow mw = new MainWindow();
		CardData victim = makeForward("Victim", "Fire", 3, 7000);
		placeP2Forward(mw, victim);

		GameContext ctx = mw.buildGameContext(true);
		ctx.preloadTargets(List.of(fwd(false, 0)));
		Consumer<GameContext> effect = ActionResolver.parse(COCKATRICE_SUMMON, null);
		assertNotNull(effect, "the printed wording has to parse for the rest of this to mean anything");
		effect.accept(ctx);

		assertTrue(mw.p2CannotAttack.contains(victim), "neither half may be dropped");
		assertTrue(mw.p2CannotBlock.contains(victim));
		assertTrue(mw.allIncomingDmgZeroThisTurnSet.contains(victim));
	}

	@Test
	void andTheShieldIsNotSpentByTheFirstHit() {
		// The difference between this shield and the one-shot beside it. "If it is dealt damage,
		// the damage becomes 0" names no number of hits, so the second one is stopped too.
		MainWindow mw = new MainWindow();
		CardData victim = makeForward("Victim", "Fire", 3, 7000);
		placeP2Forward(mw, victim);

		GameContext ctx = mw.buildGameContext(true);
		ctx.preloadTargets(List.of(fwd(false, 0)));
		ActionResolver.parse(COCKATRICE_SUMMON, null).accept(ctx);

		ctx.damageTarget(fwd(false, 0), 3000);
		ctx.damageTarget(fwd(false, 0), 3000);

		assertEquals(0, mw.p2ForwardDamage.get(0), "both hits became 0");
		assertEquals(1, mw.p2ForwardCards.size());
	}

	// =========================================================================================
	// 5-100H Odin: "Select 1 of the 2 following actions. "Choose 1 Forward of cost 3 or less. Break
	// it." "Choose 1 Monster of cost 3 or less. Break it.""
	// =========================================================================================

	private static final String ODIN_5_100H = "Select 1 of the 2 following actions.[[br]] \"Choose 1 Forward of cost "
			+ "3 or less. Break it.\"[[br]] \"Choose 1 Monster of cost 3 or less. Break it.\"";

	private static CardData odin5() {
		return makeSummon("Odin", "Lightning", 3, ODIN_5_100H);
	}

	@Test
	void odinsFirstActionBreaksACost3Forward() {
		MainWindow mw = new MainWindow();
		CardData cheap = makeForward("Cheap", "Water", 3, 7000);
		CardData dear  = makeForward("Dear", "Water", 4, 8000);
		placeP1Forward(mw, cheap);
		placeP1Forward(mw, dear);
		castAsP2Selecting(mw, odin5(), 0);

		assertTrue(mw.gameState.getP1BreakZone().contains(cheap));
		assertEquals(List.of(dear), mw.p1ForwardCards, "cost 4 is out of reach");
	}

	@Test
	void odinsSecondActionBreaksACost3Monster() {
		MainWindow mw = new MainWindow();
		CardData monster = makeMonster("Their Monster", "Water", 3);
		placeP1Monster(mw, monster);
		castAsP2Selecting(mw, odin5(), 1);
		assertTrue(mw.gameState.getP1BreakZone().contains(monster));
	}

	@Test
	void odinsSecondActionLeavesACost4Monster() {
		MainWindow mw = new MainWindow();
		CardData monster = makeMonster("Their Monster", "Water", 4);
		placeP1Monster(mw, monster);
		castAsP2Selecting(mw, odin5(), 1);
		assertTrue(mw.p1MonsterCards.contains(monster));
	}

	// =========================================================================================
	// 5-117C Ramuh: "EX BURST Choose 1 damaged Forward. Deal it 7000 damage."
	// =========================================================================================

	private static final String RAMUH_5_117C = "[[ex]]EX BURST[[/]] Choose 1 damaged Forward. Deal it 7000 damage.";

	@Test
	void ramuhFinishesADamagedForwardAndIgnoresAnUndamagedOne() {
		MainWindow mw = new MainWindow();
		CardData fresh = makeForward("Fresh", "Water", 2, 5000);
		CardData hurt  = makeForward("Hurt", "Water", 4, 8000);
		placeP1Forward(mw, fresh);
		placeP1Forward(mw, hurt);
		mw.p1ForwardDamage.set(1, 1000);
		castAsP2(mw, makeSummon("Ramuh", "Lightning", 1, RAMUH_5_117C));

		assertTrue(mw.gameState.getP1BreakZone().contains(hurt), "1000 + 7000 breaks an 8000 Forward");
		assertEquals(List.of(fresh), mw.p1ForwardCards);
		assertEquals(0, damageOn(mw, fresh), "an undamaged Forward is not a legal choice");
	}

	@Test
	void ramuhDoesNothingWhenNoForwardIsDamaged() {
		MainWindow mw = new MainWindow();
		CardData fresh = makeForward("Fresh", "Water", 2, 5000);
		placeP1Forward(mw, fresh);
		castAsP2(mw, makeSummon("Ramuh", "Lightning", 1, RAMUH_5_117C));
		assertEquals(0, damageOn(mw, fresh));
	}

	// =========================================================================================
	// 5-133H Bismarck: "Select 1 of the 3 following actions. "Choose 1 Monster. Return it to its
	// owner's hand." "Choose 1 Character you control. Return it to its owner's hand." "Choose 1
	// Forward. Halve its power until the end of the turn (round down to the nearest 1000).""
	//
	// Board tests for the first two options. The section below it covers the third.
	// =========================================================================================

	private static final String BISMARCK_5_133H = "Select 1 of the 3 following actions.[[br]] \"Choose 1 Monster. "
			+ "Return it to its owner's hand.\"[[br]] \"Choose 1 Character you control. Return it to its owner's "
			+ "hand.\"[[br]] \"Choose 1 Forward. Halve its power until the end of the turn (round down to the "
			+ "nearest 1000).\"";

	private static CardData bismarck() {
		return makeSummon("Bismarck", "Water", 2, BISMARCK_5_133H);
	}

	@Test
	void bismarcksFirstActionReturnsAMonsterToItsOwner() {
		MainWindow mw = new MainWindow();
		CardData monster = makeMonster("Their Monster", "Fire", 3);
		placeP1Monster(mw, monster);
		castAsP2Selecting(mw, bismarck(), 0);

		assertTrue(mw.gameState.getP1Hand().contains(monster), "to its owner's hand, not the caster's");
		assertFalse(mw.p1MonsterCards.contains(monster));
	}

	@Test
	void bismarcksSecondActionReturnsOneOfYourOwnCharacters() {
		MainWindow mw = new MainWindow();
		CardData mine   = makeForward("Mine", "Water", 3, 7000);
		CardData theirs = makeForward("Theirs", "Fire", 3, 7000);
		placeP2Forward(mw, mine);
		placeP1Forward(mw, theirs);
		castAsP2Selecting(mw, bismarck(), 1);

		assertTrue(mw.gameState.getP2Hand().contains(mine));
		assertEquals(List.of(theirs), mw.p1ForwardCards, "a Character you control only");
	}

	// =========================================================================================
	// 5-133H Bismarck, third option: "Choose 1 Forward. Halve its power until the end of the turn
	// (round down to the nearest 1000)."
	//
	// The corpus's only printing of "halve". Expressed as a reduction rather than a new base
	// power, because that is what it is — the amount is read per target off the power the Forward
	// has when this resolves, so a lend on it is halved along with the rest.
	// =========================================================================================

	private static final String BISMARCK_OPTION =
			"Choose 1 Forward. Halve its power until the end of the turn (round down to the nearest 1000).";

	private static int bismarckAgainst(int printedPower) {
		MainWindow mw = new MainWindow();
		placeP2Forward(mw, makeForward("Victim", "Fire", 3, printedPower));

		GameContext ctx = mw.buildGameContext(true);
		ctx.preloadTargets(List.of(fwd(false, 0)));
		Consumer<GameContext> effect = ActionResolver.parse(BISMARCK_OPTION, null);
		assertNotNull(effect, "the printed wording has to parse for the rest of this to mean anything");
		effect.accept(ctx);
		return mw.effectiveP2ForwardPower(0);
	}

	@Test
	void bismarckHalvesAnEvenPowerExactly() {
		assertEquals(4000, bismarckAgainst(8000));
	}

	@Test
	void andRoundsAnOddOneDownToTheNearestThousand() {
		// 9000 halves to 4500, which is not a legal power — the printing says which way to go.
		assertEquals(4000, bismarckAgainst(9000));
		assertEquals(3000, bismarckAgainst(7000));
	}

	@Test
	void andHalvesThePowerTheForwardActuallyHasRatherThanItsPrinted() {
		MainWindow mw = new MainWindow();
		placeP2Forward(mw, makeForward("Victim", "Fire", 3, 5000));

		GameContext ctx = mw.buildGameContext(true);
		ctx.boostTarget(fwd(false, 0), 3000, EnumSet.noneOf(CardData.Trait.class));
		assertEquals(8000, mw.effectiveP2ForwardPower(0), "the lend is on before Bismarck resolves");

		ctx.preloadTargets(List.of(fwd(false, 0)));
		ActionResolver.parse(BISMARCK_OPTION, null).accept(ctx);

		assertEquals(4000, mw.effectiveP2ForwardPower(0), "8000 halved, not 5000");
	}

	@Test
	void bismarckNamesAllThreeOfItsOptions() {
		String summon = "Select 1 of the 3 following actions. "
				+ "\"Choose 1 Monster. Return it to its owner's hand.\" "
				+ "\"Choose 1 Character you control. Return it to its owner's hand.\" "
				+ "\"Choose 1 Forward. Halve its power until the end of the turn (round down to the nearest 1000).\"";
		assertEquals("SelectFollowingActions(1 of 3: ChooseCharacter / ReturnToOwnersHand "
						+ "| ChooseCharacter / ReturnToOwnersHand | ChooseCharacter / HalvePower)",
				ActionResolver.fullDescription(summon, null));
	}

	// =========================================================================================
	// 5-139C Leviathan: "EX BURST Choose 1 Forward. If its cost is equal to or less than the number of
	// cards in your hand, return it to its owner's hand."
	//
	// "Your hand" is the caster's, P2's here, read when the Summon resolves.
	// =========================================================================================

	private static final String LEVIATHAN_5_139C = "[[ex]]EX BURST[[/]]Choose 1 Forward. If its cost is equal to or "
			+ "less than the number of cards in your hand, return it to its owner's hand.";

	/** Leviathan cast from P2's seat, against P1's lone {@code theirs}, with three cards in P2's hand. */
	private static MainWindow castLeviathan139Against(CardData theirs) {
		MainWindow mw = new MainWindow();
		placeP1Forward(mw, theirs);
		for (int i = 0; i < 3; i++) mw.gameState.getP2Hand().add(makeForward("Held " + i, "Water", 2, 5000));
		castAsP2(mw, makeSummon("Leviathan", "Water", 1, LEVIATHAN_5_139C));
		return mw;
	}

	@Test
	void leviathanReturnsAForwardCostingNoMoreThanYourHandSize() {
		CardData theirs = makeForward("Theirs", "Fire", 3, 7000);
		MainWindow mw = castLeviathan139Against(theirs);

		assertTrue(mw.gameState.getP1Hand().contains(theirs), "cost 3 against 3 cards in hand");
		assertTrue(mw.p1ForwardCards.isEmpty());
	}

	@Test
	void leviathanLeavesAForwardCostingMoreThanYourHandSize() {
		CardData theirs = makeForward("Theirs", "Fire", 4, 8000);
		MainWindow mw = castLeviathan139Against(theirs);

		assertEquals(List.of(theirs), mw.p1ForwardCards, "cost 4 against 3 cards in hand");
		assertFalse(mw.gameState.getP1Hand().contains(theirs));
	}

	// =========================================================================================
	// 6-017C Bahamut: "Choose 1 Forward. Deal it 10000 damage. Bahamut deals you 1 point of
	// damage. EX Bursts of cards put into the Damage Zone due to this damage cannot be used."
	//
	// The suppression clause was already wired — for "due to this ability", which every other
	// printing says. Bahamut names the damage instead, and the two come to the same thing here:
	// the ability the suppression scopes to is the one resolution that dealt it, and nothing else
	// in this text puts a card into a Damage Zone.
	// =========================================================================================

	private static final String BAHAMUT_SELF_DAMAGE_SUMMON =
			"EX BURST Choose 1 Forward. Deal it 10000 damage. Bahamut deals you 1 point of damage. "
			+ "EX Bursts of cards put into the Damage Zone due to this damage cannot be used.";

	@Test
	void bahamutBurnsHurtsYouAndSuppressesTheExBurst() {
		ForwardTarget victim = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);
		GameContext ctx = mock(GameContext.class);
		when(ctx.consumePreloadedTargets()).thenReturn(null);
		when(ctx.selectCharacters(anyInt(), anyBoolean(), anyBoolean(), anyBoolean(), any(), any(),
				anyInt(), any(), anyInt(), any(), anyBoolean(), anyBoolean(), anyBoolean(),
				any(), any(), any(), any(), anyBoolean(), any(), anyBoolean()))
				.thenReturn(List.of(victim));

		Consumer<GameContext> effect = ActionResolver.parse(BAHAMUT_SELF_DAMAGE_SUMMON, null);
		assertNotNull(effect, "the printed wording has to parse for the rest of this to mean anything");
		effect.accept(ctx);

		verify(ctx).damageTarget(victim, 10000);
		verify(ctx).dealDamageToSelf(1);
		verify(ctx).suppressExBurstsThisAbility();
	}

	@Test
	void andTheSuppressionIsNoLongerAnUnreadTail() {
		// Both routes to this description now join with " + ". The secondary reaches the
		// compound fallback again since "this damage" in the last sentence became a backward
		// reference, which is what it is — the string is the same either way, and
		// bahamutBurnsHurtsYouAndSuppressesTheExBurst is what holds the behaviour.
		assertEquals("ChooseCharacter / Damage + DealPlayerDamageToSelf + ExBurstSuppression",
				ActionResolver.fullDescription(BAHAMUT_SELF_DAMAGE_SUMMON, null));
	}

	@Test
	void bahamutBreaksTheForwardAndPutsOnePointOnItsCaster() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 5, 10000);
		placeP1Forward(mw, theirs);
		fillP2Deck(mw, 2);
		castAsP2(mw, makeSummon("Bahamut", "Fire", 4, "[[ex]]EX BURST[[/]] Choose 1 Forward. Deal it 10000 "
				+ "damage. Bahamut deals you 1 point of damage. EX Bursts of cards put into the Damage Zone "
				+ "due to this damage cannot be used."));

		assertTrue(mw.gameState.getP1BreakZone().contains(theirs), "10000 breaks a 10000 Forward");
		assertEquals(1, mw.gameState.getP2DamageZone().size(), "\"you\" is the caster");
		assertTrue(mw.gameState.getP1DamageZone().isEmpty());
	}

	// =========================================================================================
	// 6-029C Doomtrain: "If your opponent has 1 card or less in his/her hand, the cost required to
	// cast Doomtrain is reduced by 2. Choose 1 dull Forward. Deal it 9000 damage."
	//
	// The discount is 16-030L Shantotto's, printed before "his/her" became "their". It was read
	// only in the newer wording, so Doomtrain always cost 4.
	// =========================================================================================

	private static final String DOOMTRAIN_6_029C = "If your opponent has 1 card or less in his/her hand, the cost "
			+ "required to cast Doomtrain is reduced by 2.[[br]] Choose 1 dull Forward. Deal it 9000 damage.";

	/** Doomtrain as printed: a cost-4 Summon with its discount parsed from its text. */
	private static CardData makeDoomtrain() {
		return makeSummonWithCostText("Doomtrain", "Ice", 4, DOOMTRAIN_6_029C);
	}

	/**
	 * A Summon whose printed cost modifiers ("the cost required to cast … is reduced by N") are
	 * parsed from its text, which {@code makeSummon} leaves empty.
	 */
	private static CardData makeSummonWithCostText(String name, String element, int cost, String text) {
		return new CardData(null, name, element, cost, 0, "Summon", false, 0, false, false,
				Set.of(), 0, List.of(), null, List.of(),
				List.of(), List.of(), List.of(),
				List.of(), List.of(), List.of(), List.of(),
				CardData.parseSelfCostModifiers(text),
				List.of(), List.of(),
				false, false, null, false, false, false, false, false, 1,
				null, null, null, text);
	}

	@Test
	void doomtrainDeals9000ToADullForwardOnly() {
		MainWindow mw = new MainWindow();
		CardData dull   = makeForward("Dull", "Water", 4, 9000);
		CardData active = makeForward("Active", "Water", 4, 9000);
		placeP1Forward(mw, active);
		placeP1Forward(mw, dull);
		dullP1Forward(mw, dull);
		castAsP2(mw, makeDoomtrain());

		assertTrue(mw.gameState.getP1BreakZone().contains(dull));
		assertEquals(List.of(active), mw.p1ForwardCards, "an active Forward is not a legal choice");
	}

	@Test
	void doomtrainCostsTwoLessWhileTheOpponentHoldsOneCardOrFewer() {
		MainWindow mw = new MainWindow();
		CardData doomtrain = makeDoomtrain();
		mw.gameState.getP1Hand().add(doomtrain);
		mw.gameState.getP2Hand().clear();
		mw.gameState.getP2Hand().add(makeForward("Held", "Fire", 2, 5000));
		assertEquals(2, mw.effectiveCastCost(doomtrain), "one card is \"1 card or less\"");

		mw.gameState.getP2Hand().add(makeForward("Held 2", "Fire", 2, 5000));
		assertEquals(4, mw.effectiveCastCost(doomtrain), "two is not");
	}

	// =========================================================================================
	// 6-038R Hades: "Choose 1 dull Forward. Break it. That Forward's controller discards 1 card from
	// their hand."
	//
	// P1's hand holds one card, so P1's forced discard needs no answer.
	// =========================================================================================

	private static final String HADES_6_038R = "Choose 1 dull Forward. Break it. That Forward's controller discards "
			+ "1 card from their hand. ";

	@Test
	void hadesBreaksADullForwardAndItsControllerDiscards() {
		MainWindow mw = new MainWindow();
		CardData dull   = makeForward("Dull", "Water", 4, 8000);
		CardData active = makeForward("Active", "Water", 4, 8000);
		CardData held   = makeForward("Held", "Water", 2, 5000);
		CardData mine   = makeForward("Mine", "Ice", 2, 5000);
		placeP1Forward(mw, active);
		placeP1Forward(mw, dull);
		dullP1Forward(mw, dull);
		mw.gameState.getP1Hand().add(held);
		mw.gameState.getP2Hand().add(mine);
		castAsP2(mw, makeSummon("Hades", "Ice", 5, HADES_6_038R));

		assertTrue(mw.gameState.getP1BreakZone().contains(dull));
		assertEquals(List.of(active), mw.p1ForwardCards, "only a dull Forward");
		assertTrue(mw.gameState.getP1BreakZone().contains(held), "the broken Forward's controller discards");
		assertEquals(List.of(mine), mw.gameState.getP2Hand(), "not the caster");
	}

	// =========================================================================================
	// 6-051C Choco/Mog: "EX BURST Search for 1 Job Chocobo or Job Moogle and add it to your hand."
	// =========================================================================================

	private static final String CHOCO_MOG_6_051C =
			"[[ex]]EX BURST[[/]] Search for 1 Job Chocobo or Job Moogle and add it to your hand.";

	@Test
	void chocoMogFindsAJobChocobo() {
		MainWindow mw = new MainWindow();
		CardData chocobo = makeForwardWithJob("Boko", "Wind", 2, 5000, "Chocobo");
		CardData other   = makeForwardWithJob("Knight", "Wind", 2, 5000, "Warrior");
		mw.gameState.getP2MainDeck().add(other);
		mw.gameState.getP2MainDeck().add(chocobo);
		castAsP2(mw, makeSummon("Choco/Mog", "Wind", 1, CHOCO_MOG_6_051C));

		assertEquals(List.of(chocobo), mw.gameState.getP2Hand());
		assertTrue(mw.gameState.getP2MainDeck().contains(other));
	}

	@Test
	void chocoMogFindsAJobMoogleJustAsWell() {
		MainWindow mw = new MainWindow();
		CardData moogle = makeForwardWithJob("Mog", "Wind", 2, 5000, "Moogle");
		CardData other  = makeForwardWithJob("Knight", "Wind", 2, 5000, "Warrior");
		mw.gameState.getP2MainDeck().add(other);
		mw.gameState.getP2MainDeck().add(moogle);
		castAsP2(mw, makeSummon("Choco/Mog", "Wind", 1, CHOCO_MOG_6_051C));

		assertEquals(List.of(moogle), mw.gameState.getP2Hand());
	}

	// =========================================================================================
	// 6-052R Diablos: "Choose 1 Forward. Its power becomes 4000 until the end of the turn. Draw 1
	// card."
	// =========================================================================================

	private static final String DIABLOS_6_052R =
			"Choose 1 Forward. Its power becomes 4000 until the end of the turn. Draw 1 card.";

	@Test
	void diablosSetsThePowerTo4000AndDraws() {
		MainWindow mw = new MainWindow();
		placeP1Forward(mw, makeForward("Theirs", "Water", 5, 9000));
		fillP2Deck(mw, 2);
		castAsP2(mw, makeSummon("Diablos", "Wind", 3, DIABLOS_6_052R));

		assertEquals(4000, mw.effectiveP1ForwardPower(0));
		assertEquals(1, mw.gameState.getP2Hand().size(), "the caster draws");
	}

	// =========================================================================================
	// 6-074C Cactuar: "Choose 1 Forward. Deal it 4000 damage for each CP of a different Element you
	// paid to cast Cactuar."
	//
	// The count is read off the last cast's payment record, which the CPU's hand cast has to write
	// the way a human's does: the Elements of CP actually produced, not the pool bucket its plan
	// booked them to, and the count beside them. Otherwise a CPU Cactuar deals damage scaled by
	// whatever P1 last paid.
	// =========================================================================================

	private static final String CACTUAR_6_074C = "Choose 1 Forward. Deal it 4000 damage for each CP of a different "
			+ "Element you paid to cast Cactuar.";

	@Test
	void cactuarDeals4000PerDistinctElementPaid() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 5, 13000);
		placeP1Forward(mw, theirs);
		mw.lastCastPaymentDistinctElements = 3;
		castAsP2(mw, makeSummon("Cactuar", "Earth", 4, CACTUAR_6_074C));
		assertEquals(12000, damageOn(mw, theirs));
	}

	@Test
	void aSingleElementPaymentDealsJust4000() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 5, 13000);
		placeP1Forward(mw, theirs);
		mw.lastCastPaymentDistinctElements = 1;
		castAsP2(mw, makeSummon("Cactuar", "Earth", 4, CACTUAR_6_074C));
		assertEquals(4000, damageOn(mw, theirs));
	}

	@Test
	void theCpuCastingCactuarRecordsWhatItActuallyPaid() {
		MainWindow mw = new MainWindow();
		mw.gameState.startFirstTurn(GameState.Player.P2);
		CardData theirs = makeForward("Theirs", "Water", 5, 16000);
		placeP1Forward(mw, theirs);
		String[] elements = {"Earth", "Fire", "Ice", "Wind"};
		for (int i = 0; i < elements.length; i++) placeP2Backup(mw, makeBackup("Backup " + i, elements[i], 2));
		CardData cactuar = makeSummon("Cactuar", "Earth", 4, CACTUAR_6_074C);
		mw.gameState.getIdentity().put(cactuar, false);
		mw.gameState.getP2Hand().clear();
		mw.gameState.getP2Hand().add(cactuar);
		mw.lastCastPaymentDistinctElements = 1;  // P1's last cast, left standing

		assertTrue(new ComputerPlayer(mw).castFromHandIfAble(), "four Backups pay for a cost-4 Summon");
		if (mw.gameState.stackSize() > 0) mw.passStackPriority();

		assertEquals(Set.of("Earth", "Fire", "Ice", "Wind"), mw.lastCastPaymentElements);
		assertEquals(4, mw.lastCastPaymentDistinctElements);
		assertTrue(mw.gameState.getP1BreakZone().contains(theirs), "16000 breaks a 16000 Forward");
	}

	// Cactuar is read when it resolves, and a cast made in response writes its own payment over
	// the record in between. The Summon's own record is kept with it on the Stack instead.

	/** A payment record for {@code card}, paid with one CP of each of {@code elements}. */
	private static CastPaymentRecord paidWith(CardData card, String... elements) {
		return new CastPaymentRecord(card, elements.length, Set.of(elements), Set.of(elements),
				0, 0, List.of(), true, List.of());
	}

	/** P2 casts Cactuar at P1's lone {@code theirs}, with {@code payment} on record as it goes on the Stack. */
	private static CardData pushCactuar(MainWindow mw, CardData theirs, boolean paid, String... elements) {
		placeP1Forward(mw, theirs);
		CardData cactuar = makeSummon("Cactuar", "Earth", 4, CACTUAR_6_074C);
		mw.gameState.getIdentity().put(cactuar, false);
		CardData other = makeSummon("Earlier", "Fire", 2, "Draw 1 card.");
		paidWith(paid ? cactuar : other, elements).restore(mw);
		mw.pushSummonOnStack(cactuar, false, 0, 0, false, null, false);
		return cactuar;
	}

	@Test
	void cactuarCountsItsOwnPaymentThoughAResponseWasPaidForSince() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 5, 13000);
		pushCactuar(mw, theirs, true, "Earth", "Fire", "Ice");

		CardData response = makeSummon("Response", "Water", 1, "Draw 1 card.");
		paidWith(response, "Water").restore(mw);  // P1's response, cast on top of Cactuar
		mw.passStackPriority();

		assertEquals(12000, damageOn(mw, theirs), "three Elements, not the response's one");
		assertSame(response, mw.lastCastPaymentCard, "and the last cast is still the response");
		assertEquals(1, mw.lastCastPaymentDistinctElements);
	}

	@Test
	void aCactuarCastWithoutPayingCountsNoElements() {
		// Cast by an effect "without paying the cost": nothing was paid, so no Element was either,
		// whatever the card before it cost.
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 5, 13000);
		pushCactuar(mw, theirs, false, "Earth", "Fire", "Ice");
		mw.passStackPriority();

		assertEquals(0, damageOn(mw, theirs));
	}

	// =========================================================================================
	// 6-075R Titan: "EX BURST Choose 1 Forward you control and 1 Forward opponent controls. The former
	// gains +2000 power until the end of the turn. Then, the former deals damage equal to its power
	// to the latter."
	//
	// One-way: only the former deals damage.
	// =========================================================================================

	private static final String TITAN_6_075R = "[[ex]]EX BURST[[/]] Choose 1 Forward you control and 1 Forward "
			+ "opponent controls. The former gains +2000 power until the end of the turn. Then, the former deals "
			+ "damage equal to its power to the latter.";

	@Test
	void titanBoostsYourForwardThenItHitsTheirsForItsNewPower() {
		MainWindow mw = new MainWindow();
		CardData mine   = makeForward("Mine", "Earth", 3, 5000);
		CardData theirs = makeForward("Theirs", "Water", 4, 7000);
		placeP2Forward(mw, mine);
		placeP1Forward(mw, theirs);
		castAsP2(mw, makeSummon("Titan", "Earth", 5, TITAN_6_075R));

		assertEquals(7000, mw.effectiveP2ForwardPower(0), "5000 + 2000");
		assertTrue(mw.gameState.getP1BreakZone().contains(theirs), "7000 breaks a 7000 Forward");
		assertEquals(0, mw.p2ForwardDamage.get(0), "the latter deals nothing back");
	}

	@Test
	void titansHitIsTheBoostedPowerNotThePrinted() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 4, 9000);
		placeP2Forward(mw, makeForward("Mine", "Earth", 3, 5000));
		placeP1Forward(mw, theirs);
		castAsP2(mw, makeSummon("Titan", "Earth", 5, TITAN_6_075R));
		assertEquals(7000, damageOn(mw, theirs));
	}

	// =========================================================================================
	// 6-092C Quezacotl: "EX BURST Choose 1 Forward you control and 1 Forward opponent controls. Until
	// the end of the turn, the former gains +2000 power and the latter loses 2000 power."
	// =========================================================================================

	private static final String QUEZACOTL_6_092C = "[[ex]]EX BURST[[/]] Choose 1 Forward you control and 1 Forward "
			+ "opponent controls. Until the end of the turn, the former gains +2000 power and the latter loses "
			+ "2000 power.";

	@Test
	void quezacotlRaisesYoursAndLowersTheirs() {
		MainWindow mw = new MainWindow();
		placeP2Forward(mw, makeForward("Mine", "Lightning", 3, 5000));
		placeP1Forward(mw, makeForward("Theirs", "Water", 4, 7000));
		castAsP2(mw, makeSummon("Quezacotl", "Lightning", 2, QUEZACOTL_6_092C));

		assertEquals(7000, mw.effectiveP2ForwardPower(0));
		assertEquals(5000, mw.effectiveP1ForwardPower(0));
	}

	// =========================================================================================
	// 6-102R Ramuh: "Select up to 2 of the 4 following actions. "Choose 1 Monster of cost 2 or less.
	// Break it." "Choose 1 Forward of cost 4 or less. Dull it." "Choose 1 active Forward. Deal it
	// 7000 damage." "Choose 1 Lightning Forward. It gains Haste until the end of the turn.""
	// =========================================================================================

	private static final String RAMUH_6_102R = "Select up to 2 of the 4 following actions.[[br]] \"Choose 1 Monster "
			+ "of cost 2 or less. Break it.\"[[br]] \"Choose 1 Forward of cost 4 or less. Dull it.\"[[br]] "
			+ "\"Choose 1 active Forward. Deal it 7000 damage.\"[[br]] \"Choose 1 Lightning Forward. It gains "
			+ "Haste until the end of the turn.\"";

	private static CardData ramuh6() {
		return makeSummon("Ramuh", "Lightning", 3, RAMUH_6_102R);
	}

	@Test
	void ramuhBreaksACheapMonsterAndDullsACheapForward() {
		MainWindow mw = new MainWindow();
		CardData monster = makeMonster("Their Monster", "Water", 2);
		CardData cheap   = makeForward("Cheap", "Water", 4, 8000);
		CardData dear    = makeForward("Dear", "Water", 5, 9000);
		placeP1Monster(mw, monster);
		placeP1Forward(mw, dear);
		placeP1Forward(mw, cheap);
		castAsP2Selecting(mw, ramuh6(), 0, 1);

		assertTrue(mw.gameState.getP1BreakZone().contains(monster));
		assertEquals(CardState.ACTIVE, mw.p1ForwardStates.get(0), "cost 5 is out of reach");
		assertEquals(CardState.DULL, mw.p1ForwardStates.get(1));
	}

	@Test
	void ramuhsMonsterBreakStopsAtCost2() {
		MainWindow mw = new MainWindow();
		CardData monster = makeMonster("Their Monster", "Water", 3);
		placeP1Monster(mw, monster);
		castAsP2Selecting(mw, ramuh6(), 0);
		assertTrue(mw.p1MonsterCards.contains(monster));
	}

	@Test
	void ramuhDeals7000ToAnActiveForwardOnly() {
		MainWindow mw = new MainWindow();
		CardData active = makeForward("Active", "Water", 4, 7000);
		CardData dull   = makeForward("Dull", "Water", 4, 7000);
		placeP1Forward(mw, dull);
		placeP1Forward(mw, active);
		dullP1Forward(mw, dull);
		castAsP2Selecting(mw, ramuh6(), 2);

		assertTrue(mw.gameState.getP1BreakZone().contains(active));
		assertEquals(List.of(dull), mw.p1ForwardCards);
		assertEquals(0, damageOn(mw, dull));
	}

	@Test
	void ramuhGivesALightningForwardHaste() {
		MainWindow mw = new MainWindow();
		placeP2Forward(mw, makeForward("Lightning One", "Lightning", 3, 7000));
		placeP2Forward(mw, makeForward("Water One", "Water", 3, 7000));
		castAsP2Selecting(mw, ramuh6(), 3);

		assertTrue(mw.effectiveP2HasTrait(0, CardData.Trait.HASTE));
		assertFalse(mw.effectiveP2HasTrait(1, CardData.Trait.HASTE), "a Lightning Forward only");
	}

	// =========================================================================================
	// 6-117C Siren: "EX BURST Choose 1 Water Forward. It gains +4000 power until the end of the turn."
	// =========================================================================================

	private static final String SIREN_6_117C =
			"[[ex]]EX BURST[[/]] Choose 1 Water Forward. It gains +4000 power until the end of the turn.";

	@Test
	void sirenBoostsAWaterForwardOnly() {
		MainWindow mw = new MainWindow();
		placeP2Forward(mw, makeForward("Fire One", "Fire", 3, 5000));
		placeP2Forward(mw, makeForward("Water One", "Water", 3, 5000));
		castAsP2(mw, makeSummon("Siren", "Water", 1, SIREN_6_117C));

		assertEquals(9000, mw.effectiveP2ForwardPower(1));
		assertEquals(5000, mw.effectiveP2ForwardPower(0), "not a Water Forward");
	}

	// =========================================================================================
	// 6-125R Leviathan: "Select 1 of the 3 following actions. "Choose 1 Forward. Return it to its
	// owner's hand." "Choose 1 action ability. Cancel its effect." "During this turn, if a Forward
	// you control is dealt damage by a Summon, the damage becomes 0 instead.""
	//
	// Board tests for the first two options. The section below it covers the third.
	// =========================================================================================

	private static final String LEVIATHAN_6_125R = "Select 1 of the 3 following actions.[[br]] \"Choose 1 Forward. "
			+ "Return it to its owner's hand.\"[[br]] \"Choose 1 action ability. Cancel its effect.\"[[br]] "
			+ "\"During this turn, if a Forward you control is dealt damage by a Summon, the damage becomes 0 "
			+ "instead.\"";

	private static CardData leviathan6() {
		return makeSummon("Leviathan", "Water", 3, LEVIATHAN_6_125R);
	}

	@Test
	void leviathansFirstActionReturnsAForwardToItsOwner() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Fire", 4, 8000);
		placeP1Forward(mw, theirs);
		castAsP2Selecting(mw, leviathan6(), 0);

		assertTrue(mw.gameState.getP1Hand().contains(theirs));
		assertTrue(mw.p1ForwardCards.isEmpty());
	}

	@Test
	void leviathansSecondActionCancelsAnActionAbilityOnTheStack() {
		MainWindow mw = new MainWindow();
		CardData source = makeForward("Activator", "Fire", 3, 7000);
		placeP1Forward(mw, source);
		ActionAbility draw = CardData.parseActionAbilities("《1》: Draw 1 card.").get(0);
		StackEntry entry = new StackEntry(source, draw, true);
		mw.gameState.pushStack(entry);
		castAsP2Selecting(mw, leviathan6(), 1);

		assertTrue(mw.cancelledStackEntries.contains(entry));
	}

	// =========================================================================================
	// 6-125R Leviathan, third option: "During this turn, if a Forward you control is dealt damage
	// by a Summon, the damage becomes 0 instead."
	//
	// Its B-047 reprint says "by a Summon or an ability" and was the only wording read; this one
	// did not parse at all. They are not the same effect, so the narrower printing gets a shield
	// of its own rather than borrowing the wider one — an ability that is not a Summon still gets
	// through here.
	// =========================================================================================

	private static final String LEVIATHAN_OPTION =
			"During this turn, if a Forward you control is dealt damage by a Summon, "
			+ "the damage becomes 0 instead.";

	private static final String LEVIATHAN_REPRINT_OPTION =
			"During this turn, if a Forward you control is dealt damage by a Summon or an ability, "
			+ "the damage becomes 0 instead.";

	/** Runs {@code option} for P1 and returns the board, with one P1 Forward on the field. */
	private static MainWindow leviathanBoard(String option) {
		MainWindow mw = new MainWindow();
		placeP1Forward(mw, makeForward("Ally", "Water", 3, 9000));

		GameContext ctx = mw.buildGameContext(true);
		Consumer<GameContext> effect = ActionResolver.parse(option, null);
		assertNotNull(effect, "the printed wording has to parse: " + option);
		effect.accept(ctx);
		return mw;
	}

	/**
	 * Deals 5000 to the P1 Forward with the resolution flagged the way a Summon's or a plain
	 * ability's would be, and reports how much of it landed.
	 */
	private static int leviathanDamageThrough(MainWindow mw, boolean asSummon) {
		GameContext ctx = mw.buildGameContext(true);
		int before = mw.p1ForwardDamage.get(0);
		mw.currentResolutionIsSummon = asSummon;
		try {
			ctx.damageP1Forward(0, 5000);
		} finally {
			mw.currentResolutionIsSummon = false;
		}
		return mw.p1ForwardDamage.get(0) - before;
	}

	@Test
	void leviathanStopsSummonDamage() {
		MainWindow mw = leviathanBoard(LEVIATHAN_OPTION);
		assertEquals(0, leviathanDamageThrough(mw, true));
	}

	@Test
	void butLetsAPlainAbilityThrough() {
		// The printing says "by a Summon" and stops there. Reading it as the reprint's wider
		// sentence would blank damage the card never mentions.
		MainWindow mw = leviathanBoard(LEVIATHAN_OPTION);
		assertEquals(5000, leviathanDamageThrough(mw, false));
	}

	@Test
	void whileTheReprintStopsBoth() {
		MainWindow mw = leviathanBoard(LEVIATHAN_REPRINT_OPTION);
		assertEquals(0, leviathanDamageThrough(mw, true));
		assertEquals(0, leviathanDamageThrough(mw, false));
	}

	@Test
	void leviathanNamesAllThreeOfItsOptions() {
		String summon = "Select 1 of the 3 following actions. "
				+ "\"Choose 1 Forward. Return it to its owner's hand.\" "
				+ "\"Choose 1 action ability. Cancel its effect.\" "
				+ "\"" + LEVIATHAN_OPTION + "\"";
		assertEquals("SelectFollowingActions(1 of 3: ChooseCharacter / ReturnToOwnersHand "
						+ "| CancelAbilityOnStack | AllOwnForwardsNullifyAbilityDamage)",
				ActionResolver.fullDescription(summon, null));
	}

	// =========================================================================================
	// 7-005C Ifrit: "EX BURST Choose 1 Forward. Deal it 7000 damage. If Ifrit results from an EX
	// Burst, deal it 8000 damage instead."
	//
	// Every Opus 7 Summon has an "If [name] results from an EX Burst, … instead." alternative, so
	// each is cast both ways. Ifrit is also resolved from a real EX Burst entry on the Stack, the
	// route that carries the flag from the entry to the context.
	// =========================================================================================

	private static final String IFRIT_7_005C = "[[ex]]EX BURST[[/]] Choose 1 Forward. Deal it 7000 damage. If Ifrit "
			+ "results from an EX Burst, deal it 8000 damage instead.";

	@Test
	void ifritDeals7000WhenCast() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 4, 8000);
		placeP1Forward(mw, theirs);
		castAsP2(mw, makeSummon("Ifrit", "Fire", 4, IFRIT_7_005C));
		assertEquals(7000, damageOn(mw, theirs));
	}

	@Test
	void ifritDeals8000FromAnExBurst() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 4, 8000);
		placeP1Forward(mw, theirs);
		castAsP2(mw, makeSummon("Ifrit", "Fire", 4, IFRIT_7_005C), true);
		assertTrue(mw.gameState.getP1BreakZone().contains(theirs), "8000 breaks an 8000 Forward");
	}

	@Test
	void ifritDeals8000FromAnExBurstEntryOnTheStack() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 4, 8000);
		CardData ifrit  = makeSummon("Ifrit", "Fire", 4, IFRIT_7_005C);
		placeP1Forward(mw, theirs);
		mw.gameState.getIdentity().put(ifrit, false);
		mw.gameState.getP2DamageZone().add(ifrit);  // where an EX Burst is revealed from
		mw.gameState.pushStack(new StackEntry(ifrit, false, true));
		mw.passStackPriority();

		assertTrue(mw.gameState.getP1BreakZone().contains(theirs));
		assertTrue(mw.gameState.getP2DamageZone().contains(ifrit),
				"an EX Burst was never cast, so it stays in the Damage Zone");
		assertFalse(mw.gameState.getP2BreakZone().contains(ifrit));
	}

	// =========================================================================================
	// 7-031C Shiva: "EX BURST Choose up to 2 Forwards opponent controls. Freeze them. If Shiva
	// results from an EX Burst, dull them and Freeze them instead."
	// =========================================================================================

	private static final String SHIVA_7_031C = "[[ex]]EX BURST[[/]] Choose up to 2 Forwards opponent controls. Freeze "
			+ "them. If Shiva results from an EX Burst, dull them and Freeze them instead.";

	/** P2 casts Shiva against two P1 Forwards, with one of its own beside them. */
	private static MainWindow castShiva7(boolean exBurst) {
		MainWindow mw = new MainWindow();
		placeP1Forward(mw, makeForward("Theirs A", "Water", 4, 8000));
		placeP1Forward(mw, makeForward("Theirs B", "Water", 3, 7000));
		placeP2Forward(mw, makeForward("Mine", "Ice", 3, 7000));
		castAsP2(mw, makeSummon("Shiva", "Ice", 3, SHIVA_7_031C), exBurst);
		return mw;
	}

	@Test
	void shivaFreezesTwoOpposingForwardsWithoutDullingThem() {
		MainWindow mw = castShiva7(false);
		assertTrue(mw.p1ForwardFrozen.get(0));
		assertTrue(mw.p1ForwardFrozen.get(1));
		assertEquals(CardState.ACTIVE, mw.p1ForwardStates.get(0), "Freeze alone");
		assertEquals(CardState.ACTIVE, mw.p1ForwardStates.get(1));
		assertFalse(mw.p2ForwardFrozen.get(0), "opponent's Forwards only");
	}

	@Test
	void shivaFromAnExBurstDullsThemAsWell() {
		MainWindow mw = castShiva7(true);
		assertTrue(mw.p1ForwardFrozen.get(0));
		assertTrue(mw.p1ForwardFrozen.get(1));
		assertEquals(CardState.DULL, mw.p1ForwardStates.get(0));
		assertEquals(CardState.DULL, mw.p1ForwardStates.get(1));
		assertEquals(CardState.ACTIVE, mw.p2ForwardStates.get(0));
	}

	// =========================================================================================
	// 7-045C Alexander: "EX BURST Choose 1 Forward of cost 5 or more opponent controls. Return it to
	// its owner's hand. If Alexander results from an EX Burst, break it instead."
	// =========================================================================================

	private static final String ALEXANDER_7_045C = "[[ex]]EX BURST[[/]] Choose 1 Forward of cost 5 or more opponent "
			+ "controls. Return it to its owner's hand. If Alexander results from an EX Burst, break it instead.";

	/** P2 casts Alexander against P1's {@code costly} and a cost-4 Forward beside it. */
	private static MainWindow castAlexander7(boolean exBurst, CardData costly, CardData cheap) {
		MainWindow mw = new MainWindow();
		placeP1Forward(mw, cheap);
		placeP1Forward(mw, costly);
		castAsP2(mw, makeSummon("Alexander", "Wind", 2, ALEXANDER_7_045C), exBurst);
		return mw;
	}

	@Test
	void alexanderReturnsACostlyForwardWhenCast() {
		CardData costly = makeForward("Costly", "Water", 5, 9000);
		CardData cheap  = makeForward("Cheap", "Water", 4, 8000);
		MainWindow mw = castAlexander7(false, costly, cheap);

		assertTrue(mw.gameState.getP1Hand().contains(costly));
		assertEquals(List.of(cheap), mw.p1ForwardCards, "cost 4 is out of reach");
	}

	@Test
	void alexanderFromAnExBurstBreaksItInstead() {
		CardData costly = makeForward("Costly", "Water", 5, 9000);
		CardData cheap  = makeForward("Cheap", "Water", 4, 8000);
		MainWindow mw = castAlexander7(true, costly, cheap);

		assertTrue(mw.gameState.getP1BreakZone().contains(costly));
		assertFalse(mw.gameState.getP1Hand().contains(costly), "broken, not returned");
		assertEquals(List.of(cheap), mw.p1ForwardCards);
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
	// 7-103C Ramuh: "EX BURST Choose 1 Forward. It loses 7000 power until the end of the turn. If
	// Ramuh results from an EX Burst, it loses 8000 power until the end of the turn instead."
	// =========================================================================================

	private static final String RAMUH_7_103C = "[[ex]]EX BURST[[/]] Choose 1 Forward. It loses 7000 power until the "
			+ "end of the turn. If Ramuh results from an EX Burst, it loses 8000 power until the end of the turn "
			+ "instead.";

	@Test
	void ramuhTakes7000PowerWhenCast() {
		MainWindow mw = new MainWindow();
		placeP1Forward(mw, makeForward("Theirs", "Water", 5, 10000));
		castAsP2(mw, makeSummon("Ramuh", "Lightning", 4, RAMUH_7_103C));
		assertEquals(3000, mw.effectiveP1ForwardPower(0));
	}

	@Test
	void ramuhTakes8000FromAnExBurstAndNotBoth() {
		MainWindow mw = new MainWindow();
		placeP1Forward(mw, makeForward("Theirs", "Water", 5, 10000));
		castAsP2(mw, makeSummon("Ramuh", "Lightning", 4, RAMUH_7_103C), true);
		assertEquals(2000, mw.effectiveP1ForwardPower(0), "8000 instead of 7000, not 15000");
	}

	// =========================================================================================
	// 7-125C Leviathan: "EX BURST Choose 1 Forward of cost 4 or less. Return it to its owner's
	// hand. If Leviathan results from an EX Burst, return it to its owner's hand and draw 1 card
	// instead."
	// =========================================================================================

	private static final String LEVIATHAN_7_125C = "[[ex]]EX BURST[[/]] Choose 1 Forward of cost 4 or less. Return it "
			+ "to its owner's hand. If Leviathan results from an EX Burst, return it to its owner's hand and draw "
			+ "1 card instead.";

	/** P2 casts Leviathan against P1's {@code cheap} and a cost-5 Forward beside it. */
	private static MainWindow castLeviathan7(boolean exBurst, CardData cheap, CardData costly) {
		MainWindow mw = new MainWindow();
		placeP1Forward(mw, costly);
		placeP1Forward(mw, cheap);
		fillP2Deck(mw, 2);
		castAsP2(mw, makeSummon("Leviathan", "Water", 4, LEVIATHAN_7_125C), exBurst);
		return mw;
	}

	@Test
	void leviathanReturnsACheapForwardWithoutDrawingWhenCast() {
		CardData cheap  = makeForward("Cheap", "Fire", 4, 8000);
		CardData costly = makeForward("Costly", "Fire", 5, 9000);
		MainWindow mw = castLeviathan7(false, cheap, costly);

		assertTrue(mw.gameState.getP1Hand().contains(cheap));
		assertEquals(List.of(costly), mw.p1ForwardCards, "cost 5 is out of reach");
		assertTrue(mw.gameState.getP2Hand().isEmpty(), "the draw is the EX Burst's alone");
	}

	@Test
	void leviathanFromAnExBurstReturnsItAndDraws() {
		CardData cheap  = makeForward("Cheap", "Fire", 4, 8000);
		CardData costly = makeForward("Costly", "Fire", 5, 9000);
		MainWindow mw = castLeviathan7(true, cheap, costly);

		assertTrue(mw.gameState.getP1Hand().contains(cheap));
		assertEquals(1, mw.gameState.getP2Hand().size(), "and the caster draws 1");
		assertEquals(List.of(costly), mw.p1ForwardCards);
	}

	// =========================================================================================
	// Opus 8. Six of its Summons are reprints whose text matches an earlier printing covered above
	// (8-017C/1-023R Brynhildr, 8-040C/2-044R Mateus, 8-066C/3-071H Chaos, 8-083C/1-117R
	// Hecatoncheir, 8-094C/1-124R Odin, 8-125C/1-170C Fairy), so they have no section of their own.
	//
	// Several carry a cast restriction as their first line rather than an effect: "You can only
	// cast X during your turn." and "You can only pay with CP produced by Backups to cast X." Those
	// are card properties, read by castRestrictionMet and canAffordCard, and tested there.
	// =========================================================================================

	/** Whether P1 may cast {@code card} now, and whether P2 may, on P1's turn. */
	private static void assertCastOnlyDuringYourTurn(CardData card) {
		MainWindow mw = new MainWindow();
		mw.gameState.startFirstTurn(GameState.Player.P1);
		assertTrue(mw.castRestrictionMet(card, true), "on its caster's own turn");
		assertFalse(mw.castRestrictionMet(card, false), "not on the opponent's");
	}

	/**
	 * P1 holds {@code card} with three spare cards behind it: 6 CP by discarding, enough for any of
	 * these. Returns whether that hand alone can pay for it.
	 */
	private static boolean payableFromHandAlone(CardData card) {
		MainWindow mw = new MainWindow();
		mw.gameState.getP1Hand().clear();
		mw.gameState.getP1Hand().add(card);
		for (int i = 0; i < 3; i++) mw.gameState.getP1Hand().add(makeForward("Spare " + i, card.element(), 2, 5000));
		return mw.canAffordCard(card, 0);
	}

	/** P1 holds only {@code card}, with {@code card.cost()} active Backups of its Element. */
	private static boolean payableFromBackups(CardData card) {
		MainWindow mw = new MainWindow();
		mw.gameState.getP1Hand().clear();
		mw.gameState.getP1Hand().add(card);
		for (int i = 0; i < card.cost(); i++) placeP1Backup(mw, makeBackup("Backup " + i, card.element(), 2));
		return mw.canAffordCard(card, 0);
	}

	// =========================================================================================
	// 8-003C Ifrit: "EX BURST Choose 1 Forward. Deal it 4000 damage and 1000 more damage for each
	// Fire Backup you control."
	// =========================================================================================

	private static final String IFRIT_8_003C = "[[ex]]EX BURST[[/]] Choose 1 Forward. Deal it 4000 damage and 1000 "
			+ "more damage for each Fire Backup you control.";

	@Test
	void ifritAdds1000ForEachFireBackupYouControl() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 5, 9000);
		placeP1Forward(mw, theirs);
		placeP2Backup(mw, makeBackup("Fire A", "Fire", 2));
		placeP2Backup(mw, makeBackup("Fire B", "Fire", 2));
		placeP2Backup(mw, makeBackup("Water", "Water", 2));
		placeP1Backup(mw, makeBackup("Their Fire", "Fire", 2));
		castAsP2(mw, makeSummon("Ifrit", "Fire", 4, IFRIT_8_003C));

		assertEquals(6000, damageOn(mw, theirs), "two Fire Backups of yours; not the Water, not theirs");
	}

	// =========================================================================================
	// 8-015H Bahamut: "You can only pay with CP produced by Backups to cast Bahamut. Choose 1
	// Forward. Deal it 10000 damage. If it is put from the field into the Break Zone this turn,
	// remove it from the game instead."
	// =========================================================================================

	private static final String BAHAMUT_8_015H = "You can only pay with CP produced by Backups to cast Bahamut.[[br]] "
			+ "Choose 1 Forward. Deal it 10000 damage. If it is put from the field into the Break Zone this "
			+ "turn, remove it from the game instead.";

	@Test
	void bahamut8RemovesThe10000DamageForwardFromTheGame() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 5, 10000);
		placeP1Forward(mw, theirs);
		castAsP2(mw, makeSummon("Bahamut", "Fire", 4, BAHAMUT_8_015H));

		assertTrue(mw.gameState.getP1RemovedFromGame().contains(theirs));
		assertFalse(mw.gameState.getP1BreakZone().contains(theirs), "removed instead");
	}

	@Test
	void bahamutCanOnlyBePaidForWithBackups() {
		CardData bahamut = makeSummon("Bahamut", "Fire", 4, BAHAMUT_8_015H);
		assertFalse(payableFromHandAlone(bahamut), "discards are not Backups");
		assertTrue(payableFromBackups(bahamut));
		assertTrue(payableFromHandAlone(makeSummon("Plain", "Fire", 4, "Draw 1 card.")),
				"the same hand pays for a card without the restriction");
	}

	// =========================================================================================
	// 8-032R Shiva: "You can only cast Shiva during your turn. Choose up to 3 Forwards or Monsters
	// opponent controls. Dull them and Freeze them."
	// =========================================================================================

	private static final String SHIVA_8_032R = "You can only cast Shiva during your turn.[[br]] Choose up to 3 "
			+ "Forwards or Monsters opponent controls. Dull them and Freeze them.";

	@Test
	void shivaDullsAndFreezesThreeOpposingForwardsAndMonsters() {
		MainWindow mw = new MainWindow();
		placeP1Forward(mw, makeForward("Theirs A", "Water", 4, 8000));
		placeP1Forward(mw, makeForward("Theirs B", "Water", 3, 7000));
		placeP1Monster(mw, makeMonster("Their Monster", "Water", 2));
		placeP2Forward(mw, makeForward("Mine", "Ice", 3, 7000));
		castAsP2(mw, makeSummon("Shiva", "Ice", 4, SHIVA_8_032R));

		for (int i = 0; i < 2; i++) {
			assertEquals(CardState.DULL, mw.p1ForwardStates.get(i));
			assertTrue(mw.p1ForwardFrozen.get(i));
		}
		assertEquals(CardState.DULL, mw.p1MonsterStates.get(0), "a Monster counts as one of the three");
		assertTrue(mw.p1MonsterFrozen.get(0));
		assertEquals(CardState.ACTIVE, mw.p2ForwardStates.get(0), "opponent's only");
	}

	@Test
	void shivaCanOnlyBeCastDuringYourTurn() {
		assertCastOnlyDuringYourTurn(makeSummon("Shiva", "Ice", 4, SHIVA_8_032R));
	}

	// =========================================================================================
	// 8-033C Shiva: "EX BURST Choose 1 dull Forward. Deal it 5000 damage and 1000 more damage for
	// each Ice Backup you control."
	// =========================================================================================

	private static final String SHIVA_8_033C = "[[ex]]EX BURST[[/]] Choose 1 dull Forward. Deal it 5000 damage and "
			+ "1000 more damage for each Ice Backup you control.";

	@Test
	void shivaDeals5000PlusItsIceBackupsToADullForwardOnly() {
		MainWindow mw = new MainWindow();
		CardData dull   = makeForward("Dull", "Water", 4, 7000);
		CardData active = makeForward("Active", "Water", 4, 7000);
		placeP1Forward(mw, active);
		placeP1Forward(mw, dull);
		dullP1Forward(mw, dull);
		placeP2Backup(mw, makeBackup("Ice A", "Ice", 2));
		placeP2Backup(mw, makeBackup("Ice B", "Ice", 2));
		castAsP2(mw, makeSummon("Shiva", "Ice", 4, SHIVA_8_033C));

		assertTrue(mw.gameState.getP1BreakZone().contains(dull), "5000 + 2000 breaks a 7000 Forward");
		assertEquals(List.of(active), mw.p1ForwardCards, "an active Forward is not a legal choice");
	}

	// =========================================================================================
	// 8-046R Alexander: "Choose 1 Character of cost 4 or more. Break it."
	// =========================================================================================

	private static final String ALEXANDER_8_046R = "Choose 1 Character of cost 4 or more. Break it.";

	@Test
	void alexanderBreaksACostlyBackupAsReadilyAsAForward() {
		MainWindow mw = new MainWindow();
		CardData backup = makeBackup("Their Backup", "Water", 4);
		CardData cheap  = makeForward("Cheap", "Water", 3, 7000);
		placeP1Backup(mw, backup);
		placeP1Forward(mw, cheap);
		castAsP2(mw, makeSummon("Alexander", "Wind", 4, ALEXANDER_8_046R));

		assertTrue(mw.gameState.getP1BreakZone().contains(backup), "\"Character\" includes Backups");
		assertEquals(List.of(cheap), mw.p1ForwardCards, "cost 3 is out of reach");
	}

	// =========================================================================================
	// 8-051C Garuda: "EX BURST Choose 1 Forward. Deal it 2000 damage and 1000 more damage for each
	// Wind Character you control."
	// =========================================================================================

	private static final String GARUDA_8_051C = "[[ex]]EX BURST[[/]] Choose 1 Forward. Deal it 2000 damage and 1000 "
			+ "more damage for each Wind Character you control.";

	@Test
	void garudaCountsEveryWindCharacterYouControl() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 5, 9000);
		placeP1Forward(mw, theirs);
		placeP2Backup(mw, makeBackup("Wind Backup", "Wind", 2));
		placeP2Backup(mw, makeBackup("Fire Backup", "Fire", 2));
		placeP1Backup(mw, makeBackup("Their Wind", "Wind", 2));
		// The Wind Forward is placed dull so it is no candidate for the choice beside P1's.
		CardData windForward = makeForward("Wind Forward", "Wind", 3, 7000);
		placeP2Forward(mw, windForward);
		dullP2Forward(mw, windForward);
		castAsP2(mw, makeSummon("Garuda", "Wind", 4, GARUDA_8_051C));

		assertEquals(4000, damageOn(mw, theirs), "2000, plus a Wind Forward and a Wind Backup");
	}

	// =========================================================================================
	// 8-077C Titan: "EX BURST Choose 1 Forward. Until the end of the turn, it gains +1000 power for
	// each Earth Backup you control."
	// =========================================================================================

	private static final String TITAN_8_077C = "[[ex]]EX BURST[[/]] Choose 1 Forward. Until the end of the turn, it "
			+ "gains +1000 power for each Earth Backup you control.";

	@Test
	void titanAdds1000ForEachEarthBackupYouControl() {
		MainWindow mw = new MainWindow();
		placeP2Forward(mw, makeForward("Mine", "Earth", 3, 5000));
		for (int i = 0; i < 3; i++) placeP2Backup(mw, makeBackup("Earth " + i, "Earth", 2));
		placeP2Backup(mw, makeBackup("Fire", "Fire", 2));
		castAsP2(mw, makeSummon("Titan", "Earth", 1, TITAN_8_077C));

		assertEquals(8000, mw.effectiveP2ForwardPower(0), "three Earth Backups; not the Fire one");
	}

	// =========================================================================================
	// 8-081R Fenrir: "If you cast Fenrir, you may pay an extra 《2》. Choose 1 Light Forward or Dark
	// Forward. Break it. If you paid the extra cost, remove it from the game instead."
	//
	// Whether the extra cost was paid travels on the Stack entry, so both cases are cast there.
	// =========================================================================================

	private static final String FENRIR_8_081R = "If you cast Fenrir, you may pay an extra 《2》.[[br]] Choose 1 Light "
			+ "Forward or Dark Forward. Break it. If you paid the extra cost, remove it from the game instead.";

	/** P2 casts Fenrir at P1's Dark Forward, with a Fire Forward beside it; P1 passes. */
	private static MainWindow castFenrir(boolean paidExtra, CardData dark, CardData fire) {
		MainWindow mw = new MainWindow();
		placeP1Forward(mw, fire);
		placeP1Forward(mw, dark);
		CardData fenrir = makeSummon("Fenrir", "Earth", 2, FENRIR_8_081R);
		mw.gameState.getIdentity().put(fenrir, false);
		mw.pushSummonOnStack(fenrir, false, 0, 0, paidExtra, null, false);
		mw.passStackPriority();
		return mw;
	}

	@Test
	void fenrirBreaksALightOrDarkForward() {
		CardData dark = makeForward("Dark One", "Dark", 5, 9000);
		CardData fire = makeForward("Fire One", "Fire", 5, 9000);
		MainWindow mw = castFenrir(false, dark, fire);

		assertTrue(mw.gameState.getP1BreakZone().contains(dark));
		assertEquals(List.of(fire), mw.p1ForwardCards, "a Fire Forward is not a legal choice");
	}

	@Test
	void fenrirWithItsExtraCostRemovesItFromTheGameInstead() {
		CardData dark = makeForward("Dark One", "Dark", 5, 9000);
		CardData fire = makeForward("Fire One", "Fire", 5, 9000);
		MainWindow mw = castFenrir(true, dark, fire);

		assertTrue(mw.gameState.getP1RemovedFromGame().contains(dark));
		assertFalse(mw.gameState.getP1BreakZone().contains(dark));
		assertEquals(List.of(fire), mw.p1ForwardCards);
	}

	// =========================================================================================
	// 8-103R Raiden: "You can only pay with CP produced by Backups to cast Raiden. Choose 1 Forward.
	// Break it."
	// =========================================================================================

	private static final String RAIDEN_8_103R = "You can only pay with CP produced by Backups to cast Raiden.[[br]] "
			+ "Choose 1 Forward. Break it.";

	@Test
	void raidenBreaksAForward() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 7, 12000);
		placeP1Forward(mw, theirs);
		castAsP2(mw, makeSummon("Raiden", "Lightning", 4, RAIDEN_8_103R));
		assertTrue(mw.gameState.getP1BreakZone().contains(theirs));
	}

	@Test
	void raidenCanOnlyBePaidForWithBackups() {
		CardData raiden = makeSummon("Raiden", "Lightning", 4, RAIDEN_8_103R);
		assertFalse(payableFromHandAlone(raiden));
		assertTrue(payableFromBackups(raiden));
	}

	// =========================================================================================
	// 8-104C Ramuh: "EX BURST Choose 1 active Forward. Deal it 5000 damage and 1000 more damage for
	// each Lightning Backup you control."
	// =========================================================================================

	private static final String RAMUH_8_104C = "[[ex]]EX BURST[[/]] Choose 1 active Forward. Deal it 5000 damage and "
			+ "1000 more damage for each Lightning Backup you control.";

	@Test
	void ramuhDeals5000PlusItsLightningBackupsToAnActiveForwardOnly() {
		MainWindow mw = new MainWindow();
		CardData active = makeForward("Active", "Water", 4, 7000);
		CardData dull   = makeForward("Dull", "Water", 4, 7000);
		placeP1Forward(mw, dull);
		placeP1Forward(mw, active);
		dullP1Forward(mw, dull);
		placeP2Backup(mw, makeBackup("Lightning A", "Lightning", 2));
		placeP2Backup(mw, makeBackup("Lightning B", "Lightning", 2));
		castAsP2(mw, makeSummon("Ramuh", "Lightning", 4, RAMUH_8_104C));

		assertTrue(mw.gameState.getP1BreakZone().contains(active), "5000 + 2000 breaks a 7000 Forward");
		assertEquals(List.of(dull), mw.p1ForwardCards);
	}

	// =========================================================================================
	// 8-111R Alexander: "Select 1 of the 2 following actions. "Choose 1 Forward of cost 3 or less
	// opponent controls. Return it to its owner's hand." "Choose 1 Monster of cost 3 or less
	// opponent controls. Return it to its owner's hand.""
	// =========================================================================================

	private static final String ALEXANDER_8_111R = "Select 1 of the 2 following actions. [[br]] \"Choose 1 Forward of "
			+ "cost 3 or less opponent controls. Return it to its owner's hand.\"[[br]] \"Choose 1 Monster of cost "
			+ "3 or less opponent controls. Return it to its owner's hand.\"";

	private static CardData alexander8() {
		return makeSummon("Alexander", "Water", 1, ALEXANDER_8_111R);
	}

	@Test
	void alexandersFirstActionReturnsACheapOpposingForward() {
		MainWindow mw = new MainWindow();
		CardData cheap = makeForward("Cheap", "Fire", 3, 7000);
		CardData dear  = makeForward("Dear", "Fire", 4, 8000);
		placeP1Forward(mw, dear);
		placeP1Forward(mw, cheap);
		castAsP2Selecting(mw, alexander8(), 0);

		assertTrue(mw.gameState.getP1Hand().contains(cheap));
		assertEquals(List.of(dear), mw.p1ForwardCards, "cost 4 is out of reach");
	}

	@Test
	void alexandersSecondActionReturnsACheapOpposingMonster() {
		MainWindow mw = new MainWindow();
		CardData monster = makeMonster("Their Monster", "Fire", 3);
		placeP1Monster(mw, monster);
		castAsP2Selecting(mw, alexander8(), 1);

		assertTrue(mw.gameState.getP1Hand().contains(monster));
		assertFalse(mw.p1MonsterCards.contains(monster));
	}

	// =========================================================================================
	// 8-130C Leviathan: "EX BURST Choose 1 Forward. Until the end of the turn, it loses 1000 power
	// for each Water Character you control."
	// =========================================================================================

	private static final String LEVIATHAN_8_130C = "[[ex]]EX BURST[[/]] Choose 1 Forward. Until the end of the turn, it "
			+ "loses 1000 power for each Water Character you control.";

	@Test
	void leviathanTakes1000ForEachWaterCharacterYouControl() {
		MainWindow mw = new MainWindow();
		placeP1Forward(mw, makeForward("Theirs", "Fire", 4, 8000));
		for (int i = 0; i < 3; i++) placeP2Backup(mw, makeBackup("Water " + i, "Water", 2));
		placeP2Backup(mw, makeBackup("Fire", "Fire", 2));
		placeP1Backup(mw, makeBackup("Their Water", "Water", 2));
		castAsP2(mw, makeSummon("Leviathan", "Water", 3, LEVIATHAN_8_130C));

		assertEquals(5000, mw.effectiveP1ForwardPower(0), "three Water Backups of yours; not theirs");
	}

	// =========================================================================================
	// 8-133H Madeen: "You can only cast Madeen during your turn. If you have received 5 points of
	// damage or more, the cost required to cast Madeen is reduced by 2. Deal 9000 damage to all
	// the Forwards."
	// =========================================================================================

	private static final String MADEEN_8_133H = "You can only cast Madeen during your turn.[[br]] If you have "
			+ "received 5 points of damage or more, the cost required to cast Madeen is reduced by 2.[[br]] Deal "
			+ "9000 damage to all the Forwards.";

	@Test
	void madeenDeals9000ToEveryForwardOnBothSides() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 5, 9000);
		CardData big    = makeForward("Big", "Water", 7, 10000);
		CardData mine   = makeForward("Mine", "Light", 5, 9000);
		placeP1Forward(mw, theirs);
		placeP1Forward(mw, big);
		placeP2Forward(mw, mine);
		castAsP2(mw, makeSummonWithCostText("Madeen", "Light", 8, MADEEN_8_133H));

		assertTrue(mw.gameState.getP1BreakZone().contains(theirs));
		assertTrue(mw.gameState.getP2BreakZone().contains(mine), "\"all the Forwards\" includes your own");
		assertEquals(9000, damageOn(mw, big), "a 10000 Forward survives");
	}

	@Test
	void madeenCostsTwoLessOnceYouHaveReceivedFiveDamage() {
		MainWindow mw = new MainWindow();
		CardData madeen = makeSummonWithCostText("Madeen", "Light", 8, MADEEN_8_133H);
		mw.gameState.getP1Hand().add(madeen);
		for (int i = 0; i < 4; i++) mw.gameState.getP1DamageZone().add(makeForward("Dmg " + i, "Fire", 1, 1000));
		assertEquals(8, mw.effectiveCastCost(madeen), "four is not five");

		mw.gameState.getP1DamageZone().add(makeForward("Dmg 4", "Fire", 1, 1000));
		assertEquals(6, mw.effectiveCastCost(madeen));
	}

	@Test
	void madeenCanOnlyBeCastDuringYourTurn() {
		assertCastOnlyDuringYourTurn(makeSummonWithCostText("Madeen", "Light", 8, MADEEN_8_133H));
	}

	// =========================================================================================
	// 8-135H Ark: "You can only cast Ark during your turn. All the Forwards lose 8000 power until the
	// end of the turn. Draw 1 card for each Forward whose power became 0 or less due to the
	// previous effect."
	// =========================================================================================

	private static final String ARK_8_135H = "You can only cast Ark during your turn.[[br]] All the Forwards lose 8000 "
			+ "power until the end of the turn. Draw 1 card for each Forward whose power became 0 or less due to "
			+ "the previous effect.";

	@Test
	void arkDrawsOneCardForEachForwardItTookToZero() {
		MainWindow mw = new MainWindow();
		CardData eightK = makeForward("Eight", "Water", 4, 8000);
		CardData fiveK  = makeForward("Five", "Water", 3, 5000);
		CardData tenK   = makeForward("Ten", "Water", 6, 10000);
		CardData mine   = makeForward("Mine", "Dark", 4, 7000);
		placeP1Forward(mw, eightK);
		placeP1Forward(mw, fiveK);
		placeP1Forward(mw, tenK);
		placeP2Forward(mw, mine);
		fillP2Deck(mw, 5);
		castAsP2(mw, makeSummon("Ark", "Dark", 10, ARK_8_135H));

		assertEquals(3, mw.gameState.getP2Hand().size(), "8000, 5000 and 7000 all reached 0 or less; 10000 did not");
		assertTrue(mw.gameState.getP1BreakZone().contains(eightK), "a Forward at 0 power is put into the Break Zone");
		assertTrue(mw.gameState.getP2BreakZone().contains(mine), "your own count too");
		assertEquals(List.of(tenK), mw.p1ForwardCards);
	}

	@Test
	void arkDrawsNothingWhenNoForwardReachesZero() {
		// The draw is a count, not a card: with nothing brought to 0, it is none. Read as a plain
		// "Draw 1 card", it drew one every time.
		MainWindow mw = new MainWindow();
		placeP1Forward(mw, makeForward("Big", "Water", 7, 10000));
		fillP2Deck(mw, 2);
		castAsP2(mw, makeSummon("Ark", "Dark", 10, ARK_8_135H));

		assertTrue(mw.gameState.getP2Hand().isEmpty());
		assertEquals(2000, mw.effectiveP1ForwardPower(0));
	}

	@Test
	void anyMassPowerLossPutsForwardsItLeavesAtZeroOrUnderTheirDamageIntoTheBreakZone() {
		// Found through Ark, but not Ark's alone: the mass loss never ran the rule process the
		// single-target loss does, so a Forward left at 0 power, or with more damage than power,
		// stayed on the field.
		MainWindow mw = new MainWindow();
		CardData zeroed  = makeForward("Zeroed", "Water", 2, 3000);
		CardData damaged = makeForward("Damaged", "Water", 4, 6000);
		CardData healthy = makeForward("Healthy", "Water", 5, 9000);
		placeP1Forward(mw, zeroed);
		placeP1Forward(mw, damaged);
		placeP1Forward(mw, healthy);
		mw.p1ForwardDamage.set(1, 3000);
		ActionResolver.parse("All the Forwards opponent controls lose 3000 power until the end of the turn.", null)
				.accept(mw.buildGameContext(false));

		assertTrue(mw.gameState.getP1BreakZone().contains(zeroed), "3000 - 3000 is 0");
		assertTrue(mw.gameState.getP1BreakZone().contains(damaged), "3000 damage against 3000 power");
		assertEquals(List.of(healthy), mw.p1ForwardCards);
	}

	@Test
	void arkCanOnlyBeCastDuringYourTurn() {
		assertCastOnlyDuringYourTurn(makeSummon("Ark", "Dark", 10, ARK_8_135H));
	}

	// =========================================================================================
	// 9-002H Ifrita: "a total of 5 or more Card Name Ifrita and/or Card Name Ifrit in your Break
	// Zone (before paying the cost for Ifrita)": an Ifrit discarded to pay for her does not count.
	// =========================================================================================

	@Test
	void ifritaCountsHerBreakZoneAsItWasBeforeHerCost() {
		MainWindow mw = new MainWindow();
		CardData ifrita = makeSummon("Ifrita", "Fire", 3, "");
		for (int i = 0; i < 4; i++) mw.gameState.getP1BreakZone().add(makeSummon("Ifrit", "Fire", 2, ""));
		CardData discarded = makeSummon("Ifrit", "Fire", 2, "");
		mw.gameState.getP1BreakZone().add(discarded);
		DamageInsteadCondition parsed = ActionResolver.parseDamageInsteadCondition("you have a total of 5 or "
				+ "more Card Name Ifrita and/or Card Name Ifrit in your Break Zone (before paying the cost for Ifrita)");
		assertInstanceOf(DamageInsteadCondition.BreakZoneNamesBeforePayingAtLeast.class, parsed);
		DamageInsteadCondition.BreakZoneNamesBeforePayingAtLeast b =
				(DamageInsteadCondition.BreakZoneNamesBeforePayingAtLeast) parsed;
		DamageInsteadCondition bound = new DamageInsteadCondition.BreakZoneNamesBeforePayingAtLeast(
				b.min(), b.names(), b.payerName(), ifrita);
		GameContext ctx = mw.buildGameContext(true);
		assertTrue(ActionResolver.insteadConditionMet(ctx, bound), "five in the Break Zone, none paid for her");

		mw.lastCastPaymentCard = ifrita;
		mw.lastCastPaymentDiscards.add(discarded);
		assertFalse(ActionResolver.insteadConditionMet(ctx, bound), "one of the five arrived paying for her");
	}

	// The card as a whole: "Select 1 of the 3 following actions. If you have a total of 5 or more
	// Card Name Ifrita and/or Card Name Ifrit in your Break Zone (before paying the cost for
	// Ifrita), select up to 3 of the 3 following actions instead. "Choose 1 Forward. Deal it 7000
	// damage." "Choose 1 Monster of cost 3 or less. Break it." "Deal 3000 damage to all the
	// Forwards opponent controls.""

	private static final String IFRITA_9_002H = "Select 1 of the 3 following actions. If you have a total of 5 or "
			+ "more Card Name Ifrita and/or Card Name Ifrit in your Break Zone (before paying the cost for Ifrita), "
			+ "select up to 3 of the 3 following actions instead.[[br]] \"Choose 1 Forward. Deal it 7000 "
			+ "damage.\"[[br]] \"Choose 1 Monster of cost 3 or less. Break it.\"[[br]] \"Deal 3000 damage to all "
			+ "the Forwards opponent controls.\"";

	/** How many actions P2's Ifrita offers, with {@code ifrits} Ifrits in P2's Break Zone. */
	private static int ifritaSelectionCap(int ifrits) {
		MainWindow mw = new MainWindow();
		for (int i = 0; i < ifrits; i++) mw.gameState.getP2BreakZone().add(makeSummon("Ifrit", "Fire", 2, ""));
		GameContext ctx = spy(mw.buildGameContext(false));
		int[] cap = { -1 };
		doAnswer(inv -> {
			cap[0] = inv.getArgument(2);
			return List.of();
		}).when(ctx).chooseActions(any(), anyList(), anyInt(), anyBoolean());
		CardData ifrita = makeSummon("Ifrita", "Fire", 3, IFRITA_9_002H);
		ActionResolver.parse(ifrita.summonEffect(), ifrita).accept(ctx);
		return cap[0];
	}

	@Test
	void ifritaOffersOneActionUntilHerBreakZoneHoldsFive() {
		assertEquals(1, ifritaSelectionCap(4));
		assertEquals(3, ifritaSelectionCap(5));
	}

	@Test
	void ifritaTakingAllThreeActionsResolvesEachOfThem() {
		// One opposing Forward, so the 7000 has only one place to go: which of several the CPU
		// would choose is its own business, and a board offering it a choice made this flaky.
		MainWindow mw = new MainWindow();
		CardData big     = makeForward("Big", "Water", 5, 11000);
		CardData monster = makeMonster("Their Monster", "Water", 3);
		placeP1Forward(mw, big);
		placeP1Monster(mw, monster);
		for (int i = 0; i < 5; i++) mw.gameState.getP2BreakZone().add(makeSummon("Ifrit", "Fire", 2, ""));
		castAsP2Selecting(mw, makeSummon("Ifrita", "Fire", 3, IFRITA_9_002H), 0, 1, 2);

		assertTrue(mw.gameState.getP1BreakZone().contains(monster), "the Monster break");
		assertEquals(10000, damageOn(mw, big), "7000 from the first action and 3000 from the third");
	}

	@Test
	void ifritasSweepHitsOnlyTheOpponentsForwards() {
		MainWindow mw = new MainWindow();
		CardData small = makeForward("Small", "Water", 2, 3000);
		CardData mine  = makeForward("Mine", "Fire", 3, 7000);
		placeP1Forward(mw, small);
		placeP2Forward(mw, mine);
		castAsP2Selecting(mw, makeSummon("Ifrita", "Fire", 3, IFRITA_9_002H), 2);

		assertTrue(mw.gameState.getP1BreakZone().contains(small), "3000 to all breaks the 3000 Forward");
		assertEquals(0, mw.p2ForwardDamage.get(0), "opponent's Forwards only");
	}

	// =========================================================================================
	// 9-017C Belias: "Choose 1 Forward. Until the end of the turn, it gains +1000 power and First
	// Strike. Draw 1 card. If you have received 4 points of damage or more, it also gains Haste
	// until the end of the turn."
	//
	// The trailing gate sat past the draw and was dropped.
	// =========================================================================================

	private static final String BELIAS_9_017C = "EX BURST Choose 1 Forward. Until the end of the turn, it "
			+ "gains +1000 power and First Strike. Draw 1 card. If you have received 4 points of damage or "
			+ "more, it also gains Haste until the end of the turn.";

	/** P2 casts Belias at its one Forward with {@code damage} points of damage; true if it gained Haste. */
	private static boolean beliasGrantsHaste(int damage) {
		MainWindow mw = new MainWindow();
		CardData mine = makeForward("Mine", "Fire", 3, 7000);
		placeP2Forward(mw, mine);
		fillP2Deck(mw, 1);
		for (int i = 0; i < damage; i++) mw.gameState.getP2DamageZone().add(makeForward("Damage " + i, "Fire", 1, 1000));
		castAsP2(mw, makeSummon("Belias", "Fire", 1, BELIAS_9_017C));

		assertEquals(8000, mw.effectiveP2ForwardPower(0));
		assertTrue(mw.effectiveP2HasTrait(0, CardData.Trait.FIRST_STRIKE));
		assertEquals(1, mw.gameState.getP2Hand().size(), "the draw happens either way");
		return mw.effectiveP2HasTrait(0, CardData.Trait.HASTE);
	}

	@Test
	void beliasGrantsHasteOnlyAtFourDamage() {
		assertFalse(beliasGrantsHaste(3));
		assertTrue(beliasGrantsHaste(4));
	}

	// =========================================================================================
	// 9-025H Zalera: "Choose 1 dull Forward of cost 2, 3, 5 or 7. Break it."
	// =========================================================================================

	private static final String ZALERA_9_025H = "Choose 1 dull Forward of cost 2, 3, 5 or 7. Break it.";

	@Test
	void zaleraBreaksADullForwardOfAListedCostOnly() {
		MainWindow mw = new MainWindow();
		CardData dullFive  = makeForward("Dull Five", "Water", 5, 9000);
		CardData dullFour  = makeForward("Dull Four", "Water", 4, 8000);
		CardData activeThree = makeForward("Active Three", "Water", 3, 7000);
		placeP1Forward(mw, dullFour);
		placeP1Forward(mw, activeThree);
		placeP1Forward(mw, dullFive);
		dullP1Forward(mw, dullFour);
		dullP1Forward(mw, dullFive);
		castAsP2(mw, makeSummon("Zalera", "Ice", 1, ZALERA_9_025H));

		assertTrue(mw.gameState.getP1BreakZone().contains(dullFive));
		assertEquals(List.of(dullFour, activeThree), mw.p1ForwardCards,
				"cost 4 is not listed, and an active Forward is not a legal choice");
	}

	// =========================================================================================
	// 9-032C Mind Flayer: "EX BURST Choose 1 Forward opponent controls. Dull it. Draw 1 card."
	// =========================================================================================

	private static final String MIND_FLAYER_9_032C =
			"[[ex]]EX BURST[[/]] Choose 1 Forward opponent controls. Dull it. Draw 1 card.";

	@Test
	void mindFlayerDullsAnOpposingForwardAndDraws() {
		MainWindow mw = new MainWindow();
		placeP1Forward(mw, makeForward("Theirs", "Water", 4, 8000));
		placeP2Forward(mw, makeForward("Mine", "Ice", 3, 7000));
		fillP2Deck(mw, 2);
		castAsP2(mw, makeSummon("Mind Flayer", "Ice", 2, MIND_FLAYER_9_032C));

		assertEquals(CardState.DULL, mw.p1ForwardStates.get(0));
		assertEquals(CardState.ACTIVE, mw.p2ForwardStates.get(0), "opponent's only");
		assertEquals(1, mw.gameState.getP2Hand().size());
	}

	// =========================================================================================
	// 9-046H Shemhazai: "Select 1 of the 3 following actions. "Choose 1 Backup of cost 3 or more.
	// Return it to its owner's hand." "Choose 1 Monster of cost 3 or more. Break it." "Activate
	// all the Forwards you control.""
	// =========================================================================================

	private static final String SHEMHAZAI_9_046H = "Select 1 of the 3 following actions.[[br]] \"Choose 1 Backup of "
			+ "cost 3 or more. Return it to its owner's hand.\"[[br]] \"Choose 1 Monster of cost 3 or more. Break "
			+ "it.\"[[br]] \"Activate all the Forwards you control.\"";

	private static CardData shemhazai() {
		return makeSummon("Shemhazai", "Wind", 2, SHEMHAZAI_9_046H);
	}

	@Test
	void shemhazaisFirstActionReturnsACostlyBackup() {
		MainWindow mw = new MainWindow();
		CardData cheap = makeBackup("Cheap", "Water", 2);
		CardData dear  = makeBackup("Dear", "Water", 3);
		placeP1Backup(mw, cheap);
		placeP1Backup(mw, dear);
		castAsP2Selecting(mw, shemhazai(), 0);

		assertTrue(mw.gameState.getP1Hand().contains(dear));
		assertTrue(p1BackupOnField(mw, cheap), "cost 2 is out of reach");
	}

	@Test
	void shemhazaisSecondActionBreaksACostlyMonster() {
		MainWindow mw = new MainWindow();
		CardData cheap = makeMonster("Cheap Monster", "Water", 2);
		CardData dear  = makeMonster("Dear Monster", "Water", 3);
		placeP1Monster(mw, cheap);
		placeP1Monster(mw, dear);
		castAsP2Selecting(mw, shemhazai(), 1);

		assertTrue(mw.gameState.getP1BreakZone().contains(dear));
		assertTrue(mw.p1MonsterCards.contains(cheap));
	}

	@Test
	void shemhazaisThirdActionActivatesYourForwards() {
		MainWindow mw = new MainWindow();
		CardData mine   = makeForward("Mine", "Wind", 3, 7000);
		CardData theirs = makeForward("Theirs", "Water", 3, 7000);
		placeP2Forward(mw, mine);
		placeP1Forward(mw, theirs);
		dullP2Forward(mw, mine);
		dullP1Forward(mw, theirs);
		castAsP2Selecting(mw, shemhazai(), 2);

		assertEquals(CardState.ACTIVE, mw.p2ForwardStates.get(0));
		assertEquals(CardState.DULL, mw.p1ForwardStates.get(0), "yours only");
	}

	// =========================================================================================
	// 9-047C Sylph: "EX BURST All the Forwards you control gain +1000 power until the end of the
	// turn. Draw 1 card."
	// =========================================================================================

	private static final String SYLPH_9_047C = "[[ex]]EX BURST[[/]] All the Forwards you control gain +1000 power "
			+ "until the end of the turn. Draw 1 card.";

	@Test
	void sylphBoostsEveryForwardYouControlAndDraws() {
		MainWindow mw = new MainWindow();
		placeP2Forward(mw, makeForward("Mine A", "Wind", 3, 7000));
		placeP2Forward(mw, makeForward("Mine B", "Wind", 2, 5000));
		placeP1Forward(mw, makeForward("Theirs", "Water", 3, 7000));
		fillP2Deck(mw, 2);
		castAsP2(mw, makeSummon("Sylph", "Wind", 1, SYLPH_9_047C));

		assertEquals(8000, mw.effectiveP2ForwardPower(0));
		assertEquals(6000, mw.effectiveP2ForwardPower(1));
		assertEquals(7000, mw.effectiveP1ForwardPower(0), "yours only");
		assertEquals(1, mw.gameState.getP2Hand().size());
	}

	// =========================================================================================
	// 9-065C Golem: "EX BURST Choose 1 Forward. It gains +2000 power until the end of the turn. Draw
	// 1 card."
	// =========================================================================================

	private static final String GOLEM_9_065C = "[[ex]]EX BURST[[/]] Choose 1 Forward. It gains +2000 power until the "
			+ "end of the turn. Draw 1 card.";

	@Test
	void golemBoostsAForwardAndDraws() {
		MainWindow mw = new MainWindow();
		placeP2Forward(mw, makeForward("Mine", "Earth", 3, 7000));
		fillP2Deck(mw, 2);
		castAsP2(mw, makeSummon("Golem", "Earth", 2, GOLEM_9_065C));

		assertEquals(9000, mw.effectiveP2ForwardPower(0));
		assertEquals(1, mw.gameState.getP2Hand().size());
	}

	// =========================================================================================
	// 9-068H Mist Dragon: "Select 1 of the 3 following actions. "Choose 1 Summon of cost 5 or less.
	// Cancel its effect." "Choose 1 Forward you control. Dull it. During this turn, if it is dealt
	// damage, the damage becomes 0 instead." "Remove all the cards in your opponent's Break Zone
	// from the game. Draw 1 card.""
	//
	// Board tests for the first and third options. The section below it covers the second.
	// =========================================================================================

	private static final String MIST_DRAGON_9_068H = "Select 1 of the 3 following actions. [[br]] \"Choose 1 Summon "
			+ "of cost 5 or less. Cancel its effect.\"[[br]] \"Choose 1 Forward you control. Dull it. During this "
			+ "turn, if it is dealt damage, the damage becomes 0 instead.\"[[br]] \"Remove all the cards in your "
			+ "opponent's Break Zone from the game. Draw 1 card.\"";

	private static CardData mistDragon() {
		return makeSummon("Mist Dragon", "Earth", 3, MIST_DRAGON_9_068H);
	}

	/** A P1 Summon of {@code cost} on the Stack, the one Mist Dragon's first action can reach. */
	private static StackEntry p1SummonOnStack(MainWindow mw, int cost) {
		CardData theirs = makeSummon("Theirs", "Water", cost, "Draw 1 card.");
		mw.gameState.getIdentity().put(theirs, true);
		StackEntry entry = new StackEntry(theirs, true, false);
		mw.gameState.pushStack(entry);
		return entry;
	}

	@Test
	void mistDragonCancelsASummonOfCostFiveOrLess() {
		MainWindow mw = new MainWindow();
		StackEntry entry = p1SummonOnStack(mw, 5);
		castAsP2Selecting(mw, mistDragon(), 0);
		assertTrue(mw.cancelledStackEntries.contains(entry));
	}

	@Test
	void mistDragonCannotReachACostSixSummon() {
		MainWindow mw = new MainWindow();
		StackEntry entry = p1SummonOnStack(mw, 6);
		castAsP2Selecting(mw, mistDragon(), 0);
		assertFalse(mw.cancelledStackEntries.contains(entry));
	}

	@Test
	void mistDragonRemovesTheOpponentsBreakZoneAndDraws() {
		MainWindow mw = new MainWindow();
		CardData a = makeForward("Gone A", "Water", 2, 5000);
		CardData b = makeSummon("Gone B", "Water", 2, "");
		CardData mine = makeForward("Mine", "Earth", 2, 5000);
		mw.gameState.getP1BreakZone().add(a);
		mw.gameState.getP1BreakZone().add(b);
		mw.gameState.getP2BreakZone().add(mine);
		fillP2Deck(mw, 2);
		castAsP2Selecting(mw, mistDragon(), 2);

		assertTrue(mw.gameState.getP1BreakZone().isEmpty());
		assertTrue(mw.gameState.getP1RemovedFromGame().containsAll(List.of(a, b)));
		assertEquals(List.of(mine), mw.gameState.getP2BreakZone(), "your own Break Zone is untouched");
		assertEquals(1, mw.gameState.getP2Hand().size());
	}

	// =========================================================================================
	// 9-068H Mist Dragon, second option: "Choose 1 Forward you control. Dull it. During this turn,
	// if it is dealt damage, the damage becomes 0 instead."
	//
	// The inward-pointing twin of Shiva above, and read by the same branch. Split on ". ", the
	// dull branch would claim the first sentence and the shield would fall to the secondary
	// parser, where "it" names nothing — which is what left the option reported as "Dull + ?".
	// =========================================================================================

	private static final String MIST_DRAGON_OPTION =
			"Choose 1 Forward you control. Dull it. During this turn, if it is dealt damage, "
			+ "the damage becomes 0 instead.";

	@Test
	void mistDragonDullsItsOwnForwardAndShieldsIt() {
		MainWindow mw = new MainWindow();
		CardData ally = makeForward("Ally", "Water", 3, 7000);
		placeP1Forward(mw, ally);

		GameContext ctx = mw.buildGameContext(true);
		ctx.preloadTargets(List.of(fwd(true, 0)));
		Consumer<GameContext> effect = ActionResolver.parse(MIST_DRAGON_OPTION, null);
		assertNotNull(effect, "the printed wording has to parse for the rest of this to mean anything");
		effect.accept(ctx);

		assertEquals(CardState.DULL, mw.p1ForwardStates.get(0));
		assertTrue(mw.allIncomingDmgZeroThisTurnSet.contains(ally));

		ctx.damageTarget(fwd(true, 0), 3000);
		ctx.damageTarget(fwd(true, 0), 3000);
		assertEquals(0, mw.p1ForwardDamage.get(0), "both hits became 0");
	}

	@Test
	void mistDragonNamesAllThreeOfItsOptions() {
		String summon = "Select 1 of the 3 following actions. "
				+ "\"Choose 1 Summon of cost 5 or less. Cancel its effect.\" "
				+ "\"Choose 1 Forward you control. Dull it. During this turn, if it is dealt "
				+ "damage, the damage becomes 0 instead.\" "
				+ "\"Remove all the cards in your opponent's Break Zone from the game. Draw 1 card.\"";
		assertEquals("SelectFollowingActions(1 of 3: ChooseCharacter / CancelEffect "
						+ "| ChooseCharacter / DullAndShieldAllIncoming "
						+ "| RemoveAllOppBzFromGame + DrawCards)",
				ActionResolver.fullDescription(summon, null));
	}

	// =========================================================================================
	// 9-083C Odin: "EX BURST Choose 1 Forward of cost 2 or less. Break it. Draw 1 card."
	// =========================================================================================

	private static final String ODIN_9_083C =
			"[[ex]]EX BURST[[/]] Choose 1 Forward of cost 2 or less. Break it. Draw 1 card.";

	@Test
	void odinBreaksACheapForwardAndDraws() {
		MainWindow mw = new MainWindow();
		CardData cheap = makeForward("Cheap", "Water", 2, 5000);
		CardData dear  = makeForward("Dear", "Water", 3, 7000);
		placeP1Forward(mw, dear);
		placeP1Forward(mw, cheap);
		fillP2Deck(mw, 2);
		castAsP2(mw, makeSummon("Odin", "Lightning", 3, ODIN_9_083C));

		assertTrue(mw.gameState.getP1BreakZone().contains(cheap));
		assertEquals(List.of(dear), mw.p1ForwardCards, "cost 3 is out of reach");
		assertEquals(1, mw.gameState.getP2Hand().size());
	}

	// =========================================================================================
	// 9-093H Bahamut ZERO: "Choose 1 active Forward opponent controls. Break it. Dull all the
	// Forwards opponent controls."
	// =========================================================================================

	private static final String BAHAMUT_ZERO_9_093H =
			"Choose 1 active Forward opponent controls. Break it. Dull all the Forwards opponent controls.";

	@Test
	void bahamutZeroBreaksAnActiveForwardThenDullsTheRest() {
		MainWindow mw = new MainWindow();
		CardData active  = makeForward("Active", "Water", 5, 9000);
		CardData already = makeForward("Already Dull", "Water", 5, 9000);
		CardData mine    = makeForward("Mine", "Lightning", 3, 7000);
		placeP1Forward(mw, already);
		placeP1Forward(mw, active);
		dullP1Forward(mw, already);
		placeP2Forward(mw, mine);
		castAsP2(mw, makeSummon("Bahamut ZERO", "Lightning", 6, BAHAMUT_ZERO_9_093H));

		assertTrue(mw.gameState.getP1BreakZone().contains(active), "the dull one was not a legal choice");
		assertEquals(List.of(already), mw.p1ForwardCards);
		assertEquals(CardState.DULL, mw.p1ForwardStates.get(0));
		assertEquals(CardState.ACTIVE, mw.p2ForwardStates.get(0), "opponent's only");
	}

	@Test
	void bahamutZeroDullsEveryOtherOpposingForward() {
		MainWindow mw = new MainWindow();
		placeP1Forward(mw, makeForward("Target", "Water", 5, 9000));
		placeP1Forward(mw, makeForward("Other", "Water", 5, 9000));
		castAsP2(mw, makeSummon("Bahamut ZERO", "Lightning", 6, BAHAMUT_ZERO_9_093H));

		assertEquals(1, mw.p1ForwardCards.size(), "one was broken");
		assertEquals(CardState.DULL, mw.p1ForwardStates.get(0), "and the other dulled");
	}

	// =========================================================================================
	// 9-113H Famfrit: "Each player selects up to 2 Forwards or Monsters he/she controls (select as
	// many as possible). Put them into the Break Zone."
	//
	// "Up to 2" only caps the number: "select as many as possible" means a player with three
	// gives up two, and a player with two gives up both without being asked. It had been an
	// honest "up to", so a human could select nothing and keep everything.
	// =========================================================================================

	private static final String FAMFRIT_9_113H = "Each player selects up to 2 Forwards or Monsters he/she controls "
			+ "(select as many as possible). Put them into the Break Zone.";

	@Test
	void famfritTakesTwoFromEachPlayerOrAllTheyHave() {
		MainWindow mw = new MainWindow();
		CardData theirForward = makeForward("Their Forward", "Water", 4, 8000);
		CardData theirMonster = makeMonster("Their Monster", "Water", 2);
		placeP1Forward(mw, theirForward);
		placeP1Monster(mw, theirMonster);
		CardData cheap = makeForward("Cheap", "Water", 2, 5000);
		CardData mid   = makeForward("Mid", "Water", 3, 7000);
		CardData dear  = makeForward("Dear", "Water", 6, 10000);
		placeP2Forward(mw, dear);
		placeP2Forward(mw, cheap);
		placeP2Forward(mw, mid);
		castAsP2(mw, makeSummon("Famfrit", "Water", 6, FAMFRIT_9_113H));

		assertTrue(mw.gameState.getP1BreakZone().containsAll(List.of(theirForward, theirMonster)),
				"P1 holds exactly two, so both go, with nothing to choose");
		assertTrue(mw.p1ForwardCards.isEmpty());
		assertEquals(2, mw.gameState.getP2BreakZone().size(), "P2 holds three and gives up two");
		assertEquals(List.of(dear), mw.p2ForwardCards, "the CPU keeps its costliest");
	}

	@Test
	void famfritTakesTheOnlyCardAPlayerHas() {
		MainWindow mw = new MainWindow();
		CardData lone = makeForward("Lone", "Water", 4, 8000);
		placeP1Forward(mw, lone);
		castAsP2(mw, makeSummon("Famfrit", "Water", 6, FAMFRIT_9_113H));
		assertTrue(mw.gameState.getP1BreakZone().contains(lone));
	}

	// =========================================================================================
	// 9-114C Cúchulainn, the Impure: "EX BURST Choose 1 Forward. It loses all abilities until the
	// end of the turn. Draw 1 card."
	// =========================================================================================

	private static final String CUCHULAINN_IMPURE_9_114C = "[[ex]]EX BURST[[/]] Choose 1 Forward. It loses all "
			+ "abilities until the end of the turn. Draw 1 card.";

	@Test
	void cuchulainnTheImpureStripsAForwardsAbilitiesAndDraws() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 4, 8000);
		placeP1Forward(mw, theirs);
		fillP2Deck(mw, 2);
		castAsP2(mw, makeSummon("Cúchulainn, the Impure", "Water", 1, CUCHULAINN_IMPURE_9_114C));

		assertTrue(mw.lostAbilitiesCards.contains(theirs));
		assertEquals(1, mw.gameState.getP2Hand().size());
	}

	// =========================================================================================
	// 10-002H Ifrit: "Choose 1 Forward. Deal it 8000 damage. All the Fire Forwards you control gain
	// +2000 power until the end of the turn."
	// =========================================================================================

	private static final String IFRIT_10_002H = "Choose 1 Forward. Deal it 8000 damage. All the Fire Forwards you "
			+ "control gain +2000 power until the end of the turn.";

	@Test
	void ifritDeals8000AndBoostsYourFireForwards() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 4, 8000);
		placeP1Forward(mw, theirs);
		placeP2Forward(mw, makeForward("Fire One", "Fire", 3, 5000));
		placeP2Forward(mw, makeForward("Water One", "Water", 3, 5000));
		castAsP2(mw, makeSummon("Ifrit", "Fire", 3, IFRIT_10_002H));

		assertTrue(mw.gameState.getP1BreakZone().contains(theirs));
		assertEquals(7000, mw.effectiveP2ForwardPower(0));
		assertEquals(5000, mw.effectiveP2ForwardPower(1), "Fire Forwards only");
	}

	// =========================================================================================
	// 10-003C Balasa: "EX BURST Choose 1 Forward. Deal it 2000 damage for each Backup you control."
	// =========================================================================================

	private static final String BALASA_10_003C =
			"EX BURST Choose 1 Forward. Deal it 2000 damage for each Backup you control.";

	@Test
	void balasaDeals2000ForEachBackupYouControl() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 5, 9000);
		placeP1Forward(mw, theirs);
		for (int i = 0; i < 3; i++) placeP2Backup(mw, makeBackup("Mine " + i, "Fire", 2));
		placeP1Backup(mw, makeBackup("Theirs", "Water", 2));
		castAsP2(mw, makeSummon("Balasa", "Fire", 4, BALASA_10_003C));

		assertEquals(6000, damageOn(mw, theirs), "three Backups of yours; not theirs");
	}

	// =========================================================================================
	// 10-030H Shiva: "Choose 1 active Forward and 1 dull Forward. Dull the former and deal the
	// latter 9000 damage. If you have cast a Card Name Shiva other than Shiva this turn, also
	// Freeze all the Forwards opponent controls."
	//
	// A cast Shiva has already counted itself by the time it resolves, so "another" is a count
	// of two.
	// =========================================================================================

	private static final String SHIVA_10_030H = "Choose 1 active Forward and 1 dull Forward. Dull the former and deal "
			+ "the latter 9000 damage. If you have cast a Card Name Shiva other than Shiva this turn, also Freeze "
			+ "all the Forwards opponent controls.";

	/** P2 casts Shiva against P1's active and dull Forwards, having cast {@code shivas} Shivas this turn. */
	private static MainWindow castShiva10(int shivas, CardData active, CardData dull) {
		MainWindow mw = new MainWindow();
		placeP1Forward(mw, active);
		placeP1Forward(mw, dull);
		dullP1Forward(mw, dull);
		mw.turn(false).castCountByNameThisTurn.put("shiva", shivas);
		castAsP2(mw, makeSummon("Shiva", "Ice", 4, SHIVA_10_030H));
		return mw;
	}

	@Test
	void shivaDullsTheActiveOneAndDeals9000ToTheDullOne() {
		CardData active = makeForward("Active", "Water", 4, 8000);
		CardData dull   = makeForward("Dull", "Water", 5, 9000);
		MainWindow mw = castShiva10(1, active, dull);

		assertTrue(mw.gameState.getP1BreakZone().contains(dull));
		assertEquals(List.of(active), mw.p1ForwardCards);
		assertEquals(CardState.DULL, mw.p1ForwardStates.get(0));
		assertFalse(mw.p1ForwardFrozen.get(0), "this Shiva alone does not count");
	}

	@Test
	void shivaAlsoFreezesThemAllWhenAnotherShivaWasCast() {
		CardData active = makeForward("Active", "Water", 4, 8000);
		CardData dull   = makeForward("Dull", "Water", 5, 9000);
		MainWindow mw = castShiva10(2, active, dull);

		assertEquals(List.of(active), mw.p1ForwardCards);
		assertTrue(mw.p1ForwardFrozen.get(0));
	}

	// =========================================================================================
	// 10-041C Shivan: "EX BURST Choose 1 dull Forward. Deal it 4000 damage. Draw 1 card."
	// =========================================================================================

	private static final String SHIVAN_10_041C =
			"[[ex]]EX BURST[[/]] Choose 1 dull Forward. Deal it 4000 damage. Draw 1 card.";

	@Test
	void shivanDeals4000ToADullForwardAndDraws() {
		MainWindow mw = new MainWindow();
		CardData dull   = makeForward("Dull", "Water", 3, 7000);
		CardData active = makeForward("Active", "Water", 3, 7000);
		placeP1Forward(mw, active);
		placeP1Forward(mw, dull);
		dullP1Forward(mw, dull);
		fillP2Deck(mw, 2);
		castAsP2(mw, makeSummon("Shivan", "Ice", 1, SHIVAN_10_041C));

		assertEquals(4000, damageOn(mw, dull));
		assertEquals(0, damageOn(mw, active));
		assertEquals(1, mw.gameState.getP2Hand().size());
	}

	// =========================================================================================
	// 10-053C Sylph: "EX BURST Choose 1 Forward. Deal it 3000 damage. Draw 1 card."
	// =========================================================================================

	private static final String SYLPH_10_053C = "EX BURST Choose 1 Forward. Deal it 3000 damage. Draw 1 card.";

	@Test
	void sylphDeals3000AndDraws() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 3, 7000);
		placeP1Forward(mw, theirs);
		fillP2Deck(mw, 2);
		castAsP2(mw, makeSummon("Sylph", "Wind", 2, SYLPH_10_053C));

		assertEquals(3000, damageOn(mw, theirs));
		assertEquals(1, mw.gameState.getP2Hand().size());
	}

	// =========================================================================================
	// 10-055H Chocobo: "Choose 1 Forward you control. Return it to its owner's hand. Draw 1 card."
	// =========================================================================================

	private static final String CHOCOBO_10_055H =
			"Choose 1 Forward you control. Return it to its owner's hand. Draw 1 card.";

	@Test
	void chocoboReturnsYourOwnForwardAndDraws() {
		MainWindow mw = new MainWindow();
		CardData mine   = makeForward("Mine", "Wind", 3, 7000);
		CardData theirs = makeForward("Theirs", "Water", 3, 7000);
		placeP2Forward(mw, mine);
		placeP1Forward(mw, theirs);
		fillP2Deck(mw, 2);
		castAsP2(mw, makeSummon("Chocobo", "Wind", 2, CHOCOBO_10_055H));

		assertTrue(mw.gameState.getP2Hand().contains(mine));
		assertEquals(2, mw.gameState.getP2Hand().size(), "the returned Forward and the drawn card");
		assertEquals(List.of(theirs), mw.p1ForwardCards, "a Forward you control only");
	}

	// =========================================================================================
	// 10-068C Cu Sith: "EX BURST Choose 1 Forward or Backup in your Break Zone. Add it to your
	// hand." — "your" is the resolving player's, so the salvaged card must land in the hand of
	// whoever cast it. GameContext#addTargetToHand used to append to P1's hand unconditionally,
	// which handed P2's own Break Zone card to P1 whenever the CPU cast this.
	// =========================================================================================

	private static final String CU_SITH_TEXT =
			"[[ex]]EX BURST [[/]]Choose 1 Forward or Backup in your Break Zone. Add it to your hand.";

	private static CardData makeCuSith() {
		return new CardData(null, "Cu Sith", "Earth", 2, 0, "Summon", false, 0, true, false,
				Set.of(), 0, List.of(), null, List.of(),
				List.of(), List.of(),
				List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
				false, false, null, false, false, false, false, false, 1,
				null, null, null, CU_SITH_TEXT);
	}

	/**
	 * Resolves Cu Sith's salvage for {@code casterIsP1}, with a single Forward sitting in that
	 * player's Break Zone. One eligible card makes the choice deterministic on both sides.
	 */
	private static MainWindow runCuSithSalvage(boolean casterIsP1) {
		MainWindow mw = new MainWindow();
		CardData cuSith = makeCuSith();

		CardData salvaged = makeForward("Salvage Me", "Earth", 3, 5000);
		mw.gameState.getIdentity().put(salvaged, casterIsP1);
		(casterIsP1 ? mw.gameState.getP1BreakZone() : mw.gameState.getP2BreakZone()).add(salvaged);

		GameContext ctx = mw.buildGameContext(casterIsP1);
		ActionResolver.parse(cuSith.summonEffect(), cuSith).accept(ctx);
		return mw;
	}

	@Test
	void cuSithReturnsToTheCastingPlayersHandForP2() {
		MainWindow mw = runCuSithSalvage(false);
		assertEquals(1, mw.gameState.getP2Hand().size(), "the CPU salvages into its own hand");
		assertEquals("Salvage Me", mw.gameState.getP2Hand().get(0).name());
		assertTrue(mw.gameState.getP1Hand().isEmpty(), "P1 must not receive the CPU's Break Zone card");
		assertTrue(mw.gameState.getP2BreakZone().isEmpty(), "the card leaves the CPU's Break Zone");
	}

	@Test
	void cuSithReturnsToTheCastingPlayersHandForP1() {
		MainWindow mw = runCuSithSalvage(true);
		assertEquals(1, mw.gameState.getP1Hand().size(), "P1 salvages into their own hand");
		assertEquals("Salvage Me", mw.gameState.getP1Hand().get(0).name());
		assertTrue(mw.gameState.getP2Hand().isEmpty(), "P2 must not receive P1's Break Zone card");
		assertTrue(mw.gameState.getP1BreakZone().isEmpty(), "the card leaves P1's Break Zone");
	}

	// =========================================================================================
	// 10-076H Titan: "Select up to 2 of the 4 following actions. "Choose 1 Forward you control. It
	// gains +1000 power until the end of the turn." "All the Forwards you control gain Brave until
	// the end of the turn." "All the Forwards you control gain 'This Forward cannot become dull by
	// your opponent's Summons or abilities' until the end of the turn." "Draw 1 card.""
	//
	// Board tests for the first, second and fourth options. The section below covers the third.
	// =========================================================================================

	private static final String TITAN_10_076H = "Select up to 2 of the 4 following actions.[[br]]   \"Choose 1 Forward "
			+ "you control. It gains +1000 power until the end of the turn.\"[[br]]   \"All the Forwards you control "
			+ "gain Brave until the end of the turn.\"[[br]]   \"All the Forwards you control gain 'This Forward "
			+ "cannot become dull by your opponent's Summons or abilities' until the end of the turn.\"[[br]]   "
			+ "\"Draw 1 card.\"";

	@Test
	void titanBoostsOneOfYourForwardsAndDraws() {
		MainWindow mw = new MainWindow();
		placeP2Forward(mw, makeForward("Mine", "Earth", 3, 7000));
		fillP2Deck(mw, 2);
		castAsP2Selecting(mw, makeSummon("Titan", "Earth", 1, TITAN_10_076H), 0, 3);

		assertEquals(8000, mw.effectiveP2ForwardPower(0));
		assertEquals(1, mw.gameState.getP2Hand().size());
	}

	@Test
	void titanGivesEveryForwardYouControlBrave() {
		MainWindow mw = new MainWindow();
		placeP2Forward(mw, makeForward("Mine A", "Earth", 3, 7000));
		placeP2Forward(mw, makeForward("Mine B", "Earth", 2, 5000));
		placeP1Forward(mw, makeForward("Theirs", "Water", 3, 7000));
		castAsP2Selecting(mw, makeSummon("Titan", "Earth", 1, TITAN_10_076H), 1);

		assertTrue(mw.effectiveP2HasTrait(0, CardData.Trait.BRAVE));
		assertTrue(mw.effectiveP2HasTrait(1, CardData.Trait.BRAVE));
		assertFalse(mw.effectiveP1HasTrait(0, CardData.Trait.BRAVE), "yours only");
	}

	// =========================================================================================
	// 10-076H Titan, third option: "All the Forwards you control gain 'This Forward cannot become
	// dull by your opponent's Summons or abilities' until the end of the turn."
	//
	// The board-wide twin of the single-target grant, and granted the same way a keyword is: the
	// quoted sentence is a protection with a trait behind it, temporary for the turn either way.
	//
	// Titan quotes with ', which is also the apostrophe in "your opponent's", so there is no
	// delimiter to split the blob on — it is taken whole. 23-039R Asura, the other printing of
	// this shape, uses " and grants two at once. A quote with no trait behind it is declined
	// rather than granted in part.
	// =========================================================================================

	private static final String TITAN_OPTION =
			"All the Forwards you control gain 'This Forward cannot become dull by your "
			+ "opponent's Summons or abilities' until the end of the turn.";

	@Test
	void titanGrantsTheProtectionToEveryForwardYouControl() {
		MainWindow mw = new MainWindow();
		placeP1Forward(mw, makeForward("First",  "Earth", 3, 7000));
		placeP1Forward(mw, makeForward("Second", "Earth", 2, 5000));
		placeP2Forward(mw, makeForward("Theirs", "Fire",  3, 7000));

		GameContext ctx = mw.buildGameContext(true);
		Consumer<GameContext> effect = ActionResolver.parse(TITAN_OPTION, null);
		assertNotNull(effect, "the printed wording has to parse for the rest of this to mean anything");
		effect.accept(ctx);

		assertTrue(mw.effectiveP1HasTrait(0, CardData.Trait.CANNOT_BE_DULLED_BY_OPP));
		assertTrue(mw.effectiveP1HasTrait(1, CardData.Trait.CANNOT_BE_DULLED_BY_OPP));
		assertFalse(mw.effectiveP2HasTrait(0, CardData.Trait.CANNOT_BE_DULLED_BY_OPP),
				"\"you control\" is a filter, not decoration");
	}

	@Test
	void andReadsTheOtherWordOrderTheSameWay() {
		// 23-039R Asura fronts the duration and grants two protections, double-quoted.
		assertEquals("AllFieldQuotedProtectionGrant",
				ActionResolver.fullDescription(
						"Until the end of the turn, all the Forwards you control gain \"This Forward "
						+ "cannot be returned to its owner's hand by your opponent's Summons or "
						+ "abilities.\" and \"The power of this Forward cannot be decreased by your "
						+ "opponent's Summons or abilities.\"", null));
	}

	@Test
	void andDeclinesAQuotedAbilityWithNoTraitBehindIt() {
		// "cannot be broken" prints in exactly this shape and has no trait here. Granting the ones
		// that were understood and dropping the rest would look like the card had resolved.
		assertNull(ActionResolverFieldAbility.tryParseAllFieldQuotedProtectionGrant(
				"All the Forwards you control gain \"This Forward cannot be broken\" until the end of the turn."));
	}

	@Test
	void andDeclinesTheSameGrantWithNoDurationAtAll() {
		// Without a duration this is a permanent printed field ability, which must not be
		// shortened to a turn.
		assertNull(ActionResolverFieldAbility.tryParseAllFieldQuotedProtectionGrant(
				"All the Forwards you control gain 'This Forward cannot become dull by your "
				+ "opponent's Summons or abilities'."));
	}

	@Test
	void titanNamesAllFourOfItsOptions() {
		String summon = "Select up to 2 of the 4 following actions. "
				+ "\"Choose 1 Forward you control. It gains +1000 power until the end of the turn.\" "
				+ "\"All the Forwards you control gain Brave until the end of the turn.\" "
				+ "\"" + TITAN_OPTION + "\" "
				+ "\"Draw 1 card.\"";
		assertEquals("SelectFollowingActions(up to 2 of 4: ChooseCharacter / PowerBoost "
						+ "| AllFieldKeywordGrant | AllFieldQuotedProtectionGrant | DrawCards)",
				ActionResolver.fullDescription(summon, null));
	}

	// =========================================================================================
	// 10-100C Raiden (REVENANT WINGS): "EX BURST Deal 2000 damage to all the active Forwards
	// opponent controls. Draw 1 card."
	// =========================================================================================

	private static final String RAIDEN_10_100C = "[[ex]]EX BURST [[/]]Deal 2000 damage to all the active Forwards "
			+ "opponent controls. Draw 1 card.";

	@Test
	void raidenHitsEveryActiveOpposingForwardAndDraws() {
		MainWindow mw = new MainWindow();
		CardData activeA = makeForward("Active A", "Water", 3, 7000);
		CardData activeB = makeForward("Active B", "Water", 2, 2000);
		CardData dull    = makeForward("Dull", "Water", 3, 7000);
		placeP1Forward(mw, activeA);
		placeP1Forward(mw, dull);
		placeP1Forward(mw, activeB);
		dullP1Forward(mw, dull);
		placeP2Forward(mw, makeForward("Mine", "Lightning", 3, 7000));
		fillP2Deck(mw, 2);
		castAsP2(mw, makeSummon("Raiden (REVENANT WINGS)", "Lightning", 1, RAIDEN_10_100C));

		assertEquals(2000, damageOn(mw, activeA));
		assertTrue(mw.gameState.getP1BreakZone().contains(activeB), "2000 breaks a 2000 Forward");
		assertEquals(0, damageOn(mw, dull), "active Forwards only");
		assertEquals(0, mw.p2ForwardDamage.get(0), "opponent's only");
		assertEquals(1, mw.gameState.getP2Hand().size());
	}

	// =========================================================================================
	// 10-102H Ramuh: "EX BURST Choose 1 Forward that entered the field this turn. Deal it 8000
	// damage."
	// =========================================================================================

	private static final String RAMUH_10_102H = "EX BURST Choose 1 Forward that entered the field this turn. Deal it "
			+ "8000 damage.";

	@Test
	void ramuhHitsOnlyAForwardThatEnteredThisTurn() {
		MainWindow mw = new MainWindow();
		mw.gameState.startFirstTurn(GameState.Player.P2);
		CardData fresh = makeForward("Fresh", "Water", 4, 8000);
		CardData old   = makeForward("Old", "Water", 4, 8000);
		placeP1Forward(mw, old);
		placeP1Forward(mw, fresh);
		mw.p1ForwardPlayedOnTurn.set(0, mw.gameState.getTurnNumber() - 1);
		castAsP2(mw, makeSummon("Ramuh", "Lightning", 2, RAMUH_10_102H));

		assertTrue(mw.gameState.getP1BreakZone().contains(fresh));
		assertEquals(List.of(old), mw.p1ForwardCards);
		assertEquals(0, damageOn(mw, old), "it was on the field before this turn");
	}

	// =========================================================================================
	// 10-110C Cúchulainn: "Choose 1 Forward. Until the end of the turn, it loses 1000 power for each
	// card in your hand. If its power has become 0 or less by the previous effect, draw 1 card."
	// =========================================================================================

	private static final String CUCHULAINN_10_110C = "Choose 1 Forward. Until the end of the turn, it loses 1000 "
			+ "power for each card in your hand. If its power has become 0 or less by the previous effect, draw 1 "
			+ "card.";

	/** P2 casts Cúchulainn with three cards in hand at P1's Forward of {@code power}. */
	private static MainWindow castCuchulainnAt(CardData theirs) {
		MainWindow mw = new MainWindow();
		placeP1Forward(mw, theirs);
		for (int i = 0; i < 3; i++) mw.gameState.getP2Hand().add(makeForward("Held " + i, "Water", 2, 5000));
		fillP2Deck(mw, 2);
		castAsP2(mw, makeSummon("Cúchulainn", "Water", 1, CUCHULAINN_10_110C));
		return mw;
	}

	@Test
	void cuchulainnTakesAThousandPerCardInHandAndDrawsWhenThatReachesZero() {
		CardData theirs = makeForward("Theirs", "Water", 2, 3000);
		MainWindow mw = castCuchulainnAt(theirs);

		assertEquals(4, mw.gameState.getP2Hand().size(), "3000 lost against 3000 power: the draw");
		assertTrue(mw.gameState.getP1BreakZone().contains(theirs), "and a Forward at 0 is broken");
	}

	@Test
	void cuchulainnDrawsNothingWhenThePowerStaysAboveZero() {
		CardData theirs = makeForward("Theirs", "Water", 3, 5000);
		MainWindow mw = castCuchulainnAt(theirs);

		assertEquals(2000, mw.effectiveP1ForwardPower(0));
		assertEquals(3, mw.gameState.getP2Hand().size());
	}

	// =========================================================================================
	// 10-125H Leviathan: "EX BURST Draw 2 cards, then put 1 card from your hand on the top or bottom
	// of your deck."
	//
	// The put-back was dropped: the draw took "Draw 2 cards" off the front, and Leviathan kept
	// both. The CPU puts the card on the bottom, where it will not draw it straight back.
	// =========================================================================================

	private static final String LEVIATHAN_10_125H = "EX BURST Draw 2 cards, then put 1 card from your hand on the top "
			+ "or bottom of your deck.";

	@Test
	void leviathanDrawsTwoThenPutsOneBack() {
		MainWindow mw = new MainWindow();
		fillP2Deck(mw, 3);
		mw.gameState.getP2Hand().add(makeForward("Held", "Water", 1, 1000));
		castAsP2(mw, makeSummon("Leviathan", "Water", 3, LEVIATHAN_10_125H));

		assertEquals(2, mw.gameState.getP2Hand().size(), "one held, two drawn, one put back");
		assertEquals(2, mw.gameState.getP2MainDeck().size());
		assertFalse(mw.gameState.getP2Hand().contains(mw.gameState.getP2MainDeck().peekLast()),
				"the card put back went to the bottom");
	}

	@Test
	void leviathanIsReadAsTheDrawAndThePutTogether() {
		assertEquals("DrawThenPutHandOnTopOrBottom",
				ActionResolver.matchedPatternName(makeSummon("Leviathan", "Water", 3, LEVIATHAN_10_125H).summonEffect(),
						null));
	}

	// =========================================================================================
	// 11-001R Ifrit: "EX BURST Choose 1 Forward. Deal it 5000 damage. If your opponent has received
	// 5 points of damage or more, deal it 8000 damage instead."
	//
	// Every Opus 11 Summon upgrades on the damage the caster's opponent has received, so each
	// section puts damage on the caster's own side below the threshold: it must not count.
	// =========================================================================================

	private static final String IFRIT_11_001R = "[[ex]]EX BURST[[/]] Choose 1 Forward. Deal it 5000 damage. If your "
			+ "opponent has received 5 points of damage or more, deal it 8000 damage instead.";

	@Test
	void ifritDeals5000UntilTheOpponentHasTakenFive() {
		MainWindow mw = new MainWindow();
		takeDamage(mw.gameState.getP1DamageZone(), 4);
		takeDamage(mw.gameState.getP2DamageZone(), 5);
		CardData theirs = makeForward("Theirs", "Water", 5, 9000);
		placeP1Forward(mw, theirs);
		castAsP2(mw, makeSummon("Ifrit", "Fire", 1, IFRIT_11_001R));
		assertEquals(5000, damageOn(mw, theirs), "the caster's own damage is not the opponent's");
	}

	@Test
	void ifritDeals8000OnceTheOpponentHasTakenFive() {
		MainWindow mw = new MainWindow();
		takeDamage(mw.gameState.getP1DamageZone(), 5);
		CardData theirs = makeForward("Theirs", "Water", 5, 9000);
		placeP1Forward(mw, theirs);
		castAsP2(mw, makeSummon("Ifrit", "Fire", 1, IFRIT_11_001R));
		assertEquals(8000, damageOn(mw, theirs));
	}

	// =========================================================================================
	// 11-034R Shiva: "EX BURST Choose 1 dull Forward. Deal it 6000 damage. If your opponent has
	// received 5 points of damage or more, deal it 9000 damage instead."
	// =========================================================================================

	private static final String SHIVA_11_034R = "[[ex]]EX BURST[[/]] Choose 1 dull Forward. Deal it 6000 damage. If "
			+ "your opponent has received 5 points of damage or more, deal it 9000 damage instead.";

	@Test
	void shivaDeals6000ToADullForwardUntilTheOpponentHasTakenFive() {
		MainWindow mw = new MainWindow();
		takeDamage(mw.gameState.getP1DamageZone(), 4);
		takeDamage(mw.gameState.getP2DamageZone(), 5);
		CardData active = makeForward("Active", "Water", 5, 10000);
		CardData dull   = makeForward("Dull", "Water", 5, 10000);
		placeP1Forward(mw, active);
		placeP1Forward(mw, dull);
		dullP1Forward(mw, dull);
		castAsP2(mw, makeSummon("Shiva", "Ice", 1, SHIVA_11_034R));
		assertEquals(6000, damageOn(mw, dull));
		assertEquals(0, damageOn(mw, active), "only a dull Forward can be chosen");
	}

	@Test
	void shivaDeals9000ToADullForwardOnceTheOpponentHasTakenFive() {
		MainWindow mw = new MainWindow();
		takeDamage(mw.gameState.getP1DamageZone(), 5);
		CardData active = makeForward("Active", "Water", 5, 10000);
		CardData dull   = makeForward("Dull", "Water", 5, 10000);
		placeP1Forward(mw, active);
		placeP1Forward(mw, dull);
		dullP1Forward(mw, dull);
		castAsP2(mw, makeSummon("Shiva", "Ice", 1, SHIVA_11_034R));
		assertEquals(9000, damageOn(mw, dull));
		assertEquals(0, damageOn(mw, active));
	}

	// =========================================================================================
	// 11-055R Pandemonium: "EX BURST Choose up to 1 Backup you control. Activate it, and deal 2000
	// damage to all the Forwards opponent controls. If your opponent has received 5 points of
	// damage or more, activate it, and deal 4000 damage to all the Forwards opponent controls
	// instead."
	// =========================================================================================

	private static final String PANDEMONIUM_11_055R = "[[ex]]EX BURST[[/]] Choose up to 1 Backup you control. "
			+ "Activate it, and deal 2000 damage to all the Forwards opponent controls. If your opponent has "
			+ "received 5 points of damage or more, activate it, and deal 4000 damage to all the Forwards "
			+ "opponent controls instead.";

	/** Two Forwards on P1's side and one dull Backup on P2's, with {@code p1Damage} taken by P1. */
	private static MainWindow pandemoniumBoard(int p1Damage, CardData... p1Forwards) {
		MainWindow mw = new MainWindow();
		takeDamage(mw.gameState.getP1DamageZone(), p1Damage);
		for (CardData f : p1Forwards) placeP1Forward(mw, f);
		placeP2Backup(mw, makeBackup("Backup", "Wind", 2));
		mw.p2BackupStates[0] = CardState.DULL;
		return mw;
	}

	@Test
	void pandemoniumActivatesYourBackupAndDeals2000ToAllTheirForwards() {
		CardData left  = makeForward("Left", "Water", 3, 7000);
		CardData right = makeForward("Right", "Water", 3, 7000);
		MainWindow mw = pandemoniumBoard(4, left, right);
		takeDamage(mw.gameState.getP2DamageZone(), 5);
		castAsP2(mw, makeSummon("Pandemonium", "Wind", 1, PANDEMONIUM_11_055R));
		assertEquals(CardState.ACTIVE, mw.p2BackupStates[0]);
		assertEquals(2000, damageOn(mw, left));
		assertEquals(2000, damageOn(mw, right));
	}

	@Test
	void pandemoniumDeals4000OnceTheOpponentHasTakenFive() {
		CardData left  = makeForward("Left", "Water", 3, 7000);
		CardData right = makeForward("Right", "Water", 3, 7000);
		MainWindow mw = pandemoniumBoard(5, left, right);
		castAsP2(mw, makeSummon("Pandemonium", "Wind", 1, PANDEMONIUM_11_055R));
		assertEquals(CardState.ACTIVE, mw.p2BackupStates[0]);
		assertEquals(4000, damageOn(mw, left));
		assertEquals(4000, damageOn(mw, right));
	}

	@Test
	void pandemoniumStillDealsItsDamageWithNoBackupToActivate() {
		// "Up to 1": choosing nothing is legal, and the damage does not hang off the choice.
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 3, 7000);
		placeP1Forward(mw, theirs);
		castAsP2(mw, makeSummon("Pandemonium", "Wind", 1, PANDEMONIUM_11_055R));
		assertEquals(2000, damageOn(mw, theirs));
	}

	// =========================================================================================
	// 11-070R Exodus (FFTA): "EX BURST Choose 1 Forward you control. Until the end of the turn, it
	// gains +3000 power, Brave and "This Forward cannot become dull by your opponent's Summons or
	// abilities." and "This Forward cannot be returned to its owner's hand by your opponent's
	// Summons or abilities." If your opponent has received 5 points of damage or more, all the
	// Forwards you control gain all previous effects instead."
	// =========================================================================================

	private static final String EXODUS_11_070R = "[[ex]]EX BURST[[/]] Choose 1 Forward you control. Until the end "
			+ "of the turn, it gains +3000 power, Brave and \"This Forward cannot become dull by your opponent's "
			+ "Summons or abilities.\" and \"This Forward cannot be returned to its owner's hand by your "
			+ "opponent's Summons or abilities.\" If your opponent has received 5 points of damage or more, all "
			+ "the Forwards you control gain all previous effects instead.";

	/** Whether P2's Forward in {@code idx} carries all of Exodus's grant. */
	private static boolean hasExodusGrant(MainWindow mw, int idx) {
		return mw.effectiveP2ForwardPower(idx) == 8000
				&& mw.effectiveP2HasTrait(idx, CardData.Trait.BRAVE)
				&& mw.effectiveP2HasTrait(idx, CardData.Trait.CANNOT_BE_DULLED_BY_OPP)
				&& mw.effectiveP2HasTrait(idx, CardData.Trait.CANNOT_BE_RETURNED_TO_HAND_BY_OPP);
	}

	/** Two 5000-power Forwards on P2's side and one on P1's, with {@code p1Damage} taken by P1. */
	private static MainWindow exodusBoard(int p1Damage) {
		MainWindow mw = new MainWindow();
		takeDamage(mw.gameState.getP1DamageZone(), p1Damage);
		placeP2Forward(mw, makeForward("Mine A", "Earth", 3, 5000));
		placeP2Forward(mw, makeForward("Mine B", "Earth", 3, 5000));
		placeP1Forward(mw, makeForward("Theirs", "Water", 3, 5000));
		return mw;
	}

	@Test
	void exodusGrantsOneOfYourForwardsItsWholeEffect() {
		MainWindow mw = exodusBoard(4);
		takeDamage(mw.gameState.getP2DamageZone(), 5);
		castAsP2(mw, makeSummon("Exodus (FFTA)", "Earth", 1, EXODUS_11_070R));
		assertTrue(hasExodusGrant(mw, 0) ^ hasExodusGrant(mw, 1), "exactly one of the two");
		assertEquals(5000, mw.effectiveP1ForwardPower(0), "yours only");
	}

	@Test
	void exodusGrantsAllYourForwardsOnceTheOpponentHasTakenFive() {
		MainWindow mw = exodusBoard(5);
		castAsP2(mw, makeSummon("Exodus (FFTA)", "Earth", 1, EXODUS_11_070R));
		assertTrue(hasExodusGrant(mw, 0));
		assertTrue(hasExodusGrant(mw, 1));
		assertEquals(5000, mw.effectiveP1ForwardPower(0), "yours only");
		assertFalse(mw.effectiveP1HasTrait(0, CardData.Trait.BRAVE));
	}

	// =========================================================================================
	// 11-100R Adrammelech, the Wroth: "EX BURST Choose 1 active Forward. Deal it 6000 damage. If
	// your opponent has received 5 points of damage or more, deal it 9000 damage instead."
	// =========================================================================================

	private static final String ADRAMMELECH_11_100R = "[[ex]]EX BURST [[/]]Choose 1 active Forward. Deal it 6000 "
			+ "damage. If your opponent has received 5 points of damage or more, deal it 9000 damage instead.";

	@Test
	void adrammelechDeals6000ToAnActiveForwardUntilTheOpponentHasTakenFive() {
		MainWindow mw = new MainWindow();
		takeDamage(mw.gameState.getP1DamageZone(), 4);
		takeDamage(mw.gameState.getP2DamageZone(), 5);
		CardData active = makeForward("Active", "Water", 5, 10000);
		CardData dull   = makeForward("Dull", "Water", 5, 10000);
		placeP1Forward(mw, dull);
		placeP1Forward(mw, active);
		dullP1Forward(mw, dull);
		castAsP2(mw, makeSummon("Adrammelech, the Wroth", "Lightning", 1, ADRAMMELECH_11_100R));
		assertEquals(6000, damageOn(mw, active));
		assertEquals(0, damageOn(mw, dull), "only an active Forward can be chosen");
	}

	@Test
	void adrammelechDeals9000ToAnActiveForwardOnceTheOpponentHasTakenFive() {
		MainWindow mw = new MainWindow();
		takeDamage(mw.gameState.getP1DamageZone(), 5);
		CardData active = makeForward("Active", "Water", 5, 10000);
		CardData dull   = makeForward("Dull", "Water", 5, 10000);
		placeP1Forward(mw, dull);
		placeP1Forward(mw, active);
		dullP1Forward(mw, dull);
		castAsP2(mw, makeSummon("Adrammelech, the Wroth", "Lightning", 1, ADRAMMELECH_11_100R));
		assertEquals(9000, damageOn(mw, active));
		assertEquals(0, damageOn(mw, dull));
	}

	// =========================================================================================
	// 11-113R Famfrit (FFTA): "EX BURST Draw 1 card. If your opponent has received 5 points of
	// damage or more, draw 2 cards instead."
	// =========================================================================================

	private static final String FAMFRIT_11_113R = "[[ex]]EX BURST [[/]]Draw 1 card. If your opponent has received "
			+ "5 points of damage or more, draw 2 cards instead.";

	@Test
	void famfritFftaDraws1UntilTheOpponentHasTakenFive() {
		MainWindow mw = new MainWindow();
		takeDamage(mw.gameState.getP1DamageZone(), 4);
		takeDamage(mw.gameState.getP2DamageZone(), 5);
		fillP2Deck(mw, 3);
		castAsP2(mw, makeSummon("Famfrit (FFTA)", "Water", 1, FAMFRIT_11_113R));
		assertEquals(1, mw.gameState.getP2Hand().size());
	}

	@Test
	void famfritFftaDraws2OnceTheOpponentHasTakenFive() {
		MainWindow mw = new MainWindow();
		takeDamage(mw.gameState.getP1DamageZone(), 5);
		fillP2Deck(mw, 3);
		castAsP2(mw, makeSummon("Famfrit (FFTA)", "Water", 1, FAMFRIT_11_113R));
		assertEquals(2, mw.gameState.getP2Hand().size(), "2 instead of 1, not 1 and then 2 more");
	}

	// =========================================================================================
	// 12-002H Amaterasu: "Choose 1 auto-ability. Cancel its effect. If that auto-ability triggered
	// from a Forward, deal that Forward 8000 damage."
	//
	// The damage was dropped: the rider's pattern knew only the Re-001H reprint's "If the
	// cancelled auto-ability", so this printing fell to the plain cancel, which stops at "Cancel its
	// effect." And "that Forward" was looked up by equality, which found another copy of the card
	// as readily as the one the ability triggered from.
	// =========================================================================================

	private static final String AMATERASU_12_002H = "Choose 1 auto-ability. Cancel its effect. If that auto-ability "
			+ "triggered from a Forward, deal that Forward 8000 damage. ";

	private static final String AMATERASU_RE_001H = "Choose 1 auto-ability. Cancel its effect. If the cancelled "
			+ "auto-ability triggered from a Forward, deal that Forward 8000 damage.";

	@Test
	void amaterasuCancelsTheAbilityAndDeals8000ToTheForwardItTriggeredFrom() {
		MainWindow mw = new MainWindow();
		CardData from = makeForward("Trigger Source", "Fire", 4, 9000);
		// The equal copy sits on P1's side, which an equality lookup would search first.
		CardData twin = makeForward("Trigger Source", "Fire", 4, 9000);
		placeP1Forward(mw, twin);
		placeP2Forward(mw, from);
		StackEntry entry = autoEntryFrom(from, false);
		mw.gameState.pushStack(entry);
		fillP2Deck(mw, 1);
		castAsP1(mw, makeSummon("Amaterasu", "Fire", 3, AMATERASU_12_002H));

		// The damage lets the Stack resolve, which consumes the cancellation as it skips the entry.
		assertTrue(mw.cancelledStackEntries.contains(entry) || !mw.gameState.getStack().contains(entry));
		assertTrue(mw.gameState.getP2Hand().isEmpty(), "the cancelled ability's draw never happens");
		assertEquals(8000, (int) mw.p2ForwardDamage.get(0), "that Forward");
		assertEquals(0, damageOn(mw, twin), "not its twin");
	}

	@Test
	void amaterasuDealsNoDamageWhenTheAbilityCameFromABackup() {
		MainWindow mw = new MainWindow();
		CardData bystander = makeForward("Bystander", "Water", 4, 9000);
		placeP1Forward(mw, bystander);
		StackEntry entry = autoEntryFrom(makeBackup("Backup", "Fire", 2), false);
		mw.gameState.pushStack(entry);
		castAsP1(mw, makeSummon("Amaterasu", "Fire", 3, AMATERASU_12_002H));

		assertTrue(mw.cancelledStackEntries.contains(entry));
		assertEquals(0, damageOn(mw, bystander));
	}

	@Test
	void amaterasuIsReadWithItsDamageInBothPrintings() {
		assertEquals("CancelAutoAbilityAndDamageIfForward",
				ActionResolver.matchedPatternName(AMATERASU_12_002H, null));
		assertEquals("CancelAutoAbilityAndDamageIfForward",
				ActionResolver.matchedPatternName(AMATERASU_RE_001H, null));
	}

	// =========================================================================================
	// 12-005C Ifrit: "EX BURST Choose 1 Forward. Deal it 9000 damage."
	// =========================================================================================

	private static final String IFRIT_12_005C = "[[ex]]EX BURST[[/]] Choose 1 Forward. Deal it 9000 damage.";

	@Test
	void ifritDeals9000ToTheChosenForward() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 6, 10000);
		placeP1Forward(mw, theirs);
		castAsP2(mw, makeSummon("Ifrit", "Fire", 5, IFRIT_12_005C));
		assertEquals(9000, damageOn(mw, theirs));
	}

	// =========================================================================================
	// 12-025H Carbuncle: "Select 1 of the 3 following actions. If your opponent has 2 cards or less
	// in their hand, select up to 2 of the 3 following actions instead. "Choose 1 Forward. Dull
	// it." "Choose 1 Ice Forward. It gains +2000 power until the end of the turn." "Choose up to 2
	// Characters opponent controls. Freeze them.""
	// =========================================================================================

	private static final String CARBUNCLE_12_025H = "Select 1 of the 3 following actions. If your opponent has 2 "
			+ "cards or less in their hand, select up to 2 of the 3 following actions instead.[[br]]   \"Choose 1 "
			+ "Forward. Dull it.\"[[br]]   \"Choose 1 Ice Forward. It gains +2000 power until the end of the "
			+ "turn.\"[[br]]   \"Choose up to 2 Characters opponent controls. Freeze them.\"";

	/**
	 * Casts Carbuncle from P2's seat taking {@code options}, and returns what it offered:
	 * {@code {count, upTo ? 1 : 0}}.
	 */
	private static int[] castCarbuncle(MainWindow mw, int... options) {
		GameContext ctx = spy(mw.buildGameContext(false));
		int[] offer = new int[2];
		doAnswer(inv -> {
			offer[0] = inv.getArgument(2);
			offer[1] = (boolean) inv.getArgument(3) ? 1 : 0;
			List<String> offered = inv.getArgument(1);
			List<String> taken = new ArrayList<>();
			for (int option : options) taken.add(offered.get(option));
			return taken;
		}).when(ctx).chooseActions(any(), anyList(), anyInt(), anyBoolean());
		CardData carbuncle = makeSummon("Carbuncle", "Ice", 2, CARBUNCLE_12_025H);
		ActionResolver.parse(carbuncle.summonEffect(), carbuncle).accept(ctx);
		return offer;
	}

	private static void inP1Hand(MainWindow mw, int count) {
		for (int i = 0; i < count; i++) mw.gameState.getP1Hand().add(makeForward("Held " + i, "Water", 1, 1000));
	}

	@Test
	void carbuncleOffersOneActionWhileTheOpponentHoldsThreeCards() {
		MainWindow mw = new MainWindow();
		inP1Hand(mw, 3);
		placeP1Forward(mw, makeForward("Theirs", "Water", 3, 7000));
		assertArrayEquals(new int[] { 1, 0 }, castCarbuncle(mw, 0));
	}

	@Test
	void carbuncleOffersUpToTwoActionsOnceTheOpponentHoldsTwoOrFewer() {
		MainWindow mw = new MainWindow();
		inP1Hand(mw, 2);
		placeP1Forward(mw, makeForward("Theirs", "Water", 3, 7000));
		assertArrayEquals(new int[] { 2, 1 }, castCarbuncle(mw, 0));
	}

	@Test
	void carbuncleDullsAForwardAndFreezesTheirCharacters() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 3, 7000);
		placeP1Forward(mw, theirs);
		castCarbuncle(mw, 0, 2);
		assertEquals(CardState.DULL, mw.p1ForwardStates.get(0));
		assertTrue(mw.p1ForwardFrozen.get(0));
	}

	@Test
	void carbuncleBoostsAnIceForward() {
		MainWindow mw = new MainWindow();
		placeP2Forward(mw, makeForward("Fire One", "Fire", 3, 7000));
		placeP2Forward(mw, makeForward("Ice One", "Ice", 3, 7000));
		castCarbuncle(mw, 1);
		assertEquals(7000, mw.effectiveP2ForwardPower(0), "Ice only");
		assertEquals(9000, mw.effectiveP2ForwardPower(1));
	}

	// =========================================================================================
	// 12-030C Shiva: "Choose 1 dull Forward. Deal it 10000 damage minus 1000 damage for each card in
	// your opponent's hand."
	// =========================================================================================

	private static final String SHIVA_12_030C = "Choose 1 dull Forward. Deal it 10000 damage minus 1000 damage for "
			+ "each card in your opponent's hand.";

	@Test
	void shivaDeals1000LessForEachCardInTheOpponentsHand() {
		MainWindow mw = new MainWindow();
		inP1Hand(mw, 3);
		for (int i = 0; i < 5; i++) mw.gameState.getP2Hand().add(makeForward("Mine " + i, "Ice", 1, 1000));
		CardData dull = makeForward("Dull", "Water", 5, 12000);
		placeP1Forward(mw, dull);
		dullP1Forward(mw, dull);
		castAsP2(mw, makeSummon("Shiva", "Ice", 2, SHIVA_12_030C));
		assertEquals(7000, damageOn(mw, dull), "their three cards, not the caster's five");
	}

	@Test
	void shivaDealsTheFull10000AgainstAnEmptyHand() {
		MainWindow mw = new MainWindow();
		CardData dull   = makeForward("Dull", "Water", 5, 12000);
		CardData active = makeForward("Active", "Water", 5, 12000);
		placeP1Forward(mw, active);
		placeP1Forward(mw, dull);
		dullP1Forward(mw, dull);
		castAsP2(mw, makeSummon("Shiva", "Ice", 2, SHIVA_12_030C));
		assertEquals(10000, damageOn(mw, dull));
		assertEquals(0, damageOn(mw, active), "only a dull Forward can be chosen");
	}

	// =========================================================================================
	// 12-039C Alexander: "EX BURST Draw 1 card. If you have cast 4 or more cards this turn, draw 2
	// cards instead."
	// =========================================================================================

	private static final String ALEXANDER_12_039C = "[[ex]]EX BURST[[/]] Draw 1 card. If you have cast 4 or more "
			+ "cards this turn, draw 2 cards instead.";

	@Test
	void alexanderDraws1UntilYouHaveCastFour() {
		MainWindow mw = new MainWindow();
		fillP2Deck(mw, 3);
		mw.turn(false).cardsCastThisTurn = 3;
		mw.turn(true).cardsCastThisTurn  = 4;
		castAsP2(mw, makeSummon("Alexander", "Wind", 1, ALEXANDER_12_039C));
		assertEquals(1, mw.gameState.getP2Hand().size(), "the opponent's casts are not yours");
	}

	@Test
	void alexanderDraws2OnceYouHaveCastFour() {
		MainWindow mw = new MainWindow();
		fillP2Deck(mw, 3);
		mw.turn(false).cardsCastThisTurn = 4;
		castAsP2(mw, makeSummon("Alexander", "Wind", 1, ALEXANDER_12_039C));
		assertEquals(2, mw.gameState.getP2Hand().size());
	}

	// =========================================================================================
	// 12-049H Diabolos: "Choose 1 Forward. If its power has been increased or decreased, break it."
	//
	// The plain break followup scans with find() and matched "break it" inside the condition, so
	// the summon broke whatever was chosen — the question it is entirely about was never asked.
	//
	// The question is asked as a comparison rather than as a log of what happened: an
	// until-end-of-turn boost, a reduction, a permanent boost, a one-shot "its power becomes N"
	// and a continuous field grant all land in the same place, and the card does not distinguish
	// them.
	// =========================================================================================

	private static final String DIABOLOS_BREAK_SUMMON =
			"Choose 1 Forward. If its power has been increased or decreased, break it.";

	/** Two P2 Forwards at printed power, for the power-change tests to move one of. */
	private static MainWindow twoPlainForwards(MainWindow mw) {
		placeP2Forward(mw, makeForward("Untouched", "Fire", 3, 7000));
		placeP2Forward(mw, makeForward("Moved", "Fire", 3, 7000));
		return mw;
	}

	@Test
	void aForwardAtItsPrintedPowerIsChosenAndLeftStanding() {
		// The choose still happens — being chosen is an event other cards watch, and only the
		// break is conditional.
		MainWindow mw = twoPlainForwards(new MainWindow());
		GameContext ctx = mw.buildGameContext(true);
		ctx.preloadTargets(List.of(fwd(false, 0)));

		Consumer<GameContext> effect = ActionResolver.parse(DIABOLOS_BREAK_SUMMON, null);
		assertNotNull(effect, "the printed wording has to parse for the rest of this to mean anything");
		effect.accept(ctx);

		assertEquals(2, mw.p2ForwardCards.size(), "nothing had moved, so nothing breaks");
	}

	@Test
	void andABoostedOneIsBroken() {
		MainWindow mw = twoPlainForwards(new MainWindow());
		GameContext ctx = mw.buildGameContext(true);
		ctx.boostTarget(fwd(false, 1), 2000, EnumSet.noneOf(CardData.Trait.class));
		ctx.preloadTargets(List.of(fwd(false, 1)));

		ActionResolver.parse(DIABOLOS_BREAK_SUMMON, null).accept(ctx);

		assertEquals(List.of("Untouched"), mw.p2ForwardCards.stream().map(CardData::name).toList());
	}

	@Test
	void andSoIsAWeakenedOne() {
		// "Increased or decreased" — the card asks whether the power moved, not which way.
		MainWindow mw = twoPlainForwards(new MainWindow());
		GameContext ctx = mw.buildGameContext(true);
		ctx.reduceTarget(fwd(false, 1), 1000, EnumSet.noneOf(CardData.Trait.class));
		ctx.preloadTargets(List.of(fwd(false, 1)));

		ActionResolver.parse(DIABOLOS_BREAK_SUMMON, null).accept(ctx);

		assertEquals(List.of("Untouched"), mw.p2ForwardCards.stream().map(CardData::name).toList());
	}

	@Test
	void aOneShotPowerOverrideCountsAsMovedToo() {
		// "Its power becomes N" replaces the printed value rather than adding to it, and the
		// comparison catches it for the same reason it catches the others.
		MainWindow mw = twoPlainForwards(new MainWindow());
		GameContext ctx = mw.buildGameContext(true);
		ctx.setTargetBasePower(fwd(false, 1), 3000);

		assertTrue(ctx.targetPowerHasChanged(fwd(false, 1)));
		assertFalse(ctx.targetPowerHasChanged(fwd(false, 0)));
	}

	@Test
	void theConditionIsNamedRatherThanDroppedFromTheReport() {
		assertEquals("ChooseCharacter / BreakIfPowerChanged",
				ActionResolver.fullDescription(DIABOLOS_BREAK_SUMMON, null));
	}

	@Test
	void aPlainBreakFollowupIsStillUnconditional() {
		// The guard that makes the ordering above safe rather than lucky: the plain break has to
		// keep claiming its own text, and only its own.
		assertEquals("ChooseCharacter / Break",
				ActionResolver.fullDescription("Choose 1 Forward. Break it.", null));
	}

	// =========================================================================================
	// 12-059C Kujata: "Choose 1 Forward. Deal it 2000 damage for each CP required to cast it."
	// =========================================================================================

	private static final String KUJATA_12_059C = "Choose 1 Forward. Deal it 2000 damage for each CP required to "
			+ "cast it.";

	@Test
	void kujataDeals2000ForEachCpOfTheChosenForwardsCost() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 4, 10000);
		placeP1Forward(mw, theirs);
		castAsP2(mw, makeSummon("Kujata", "Earth", 4, KUJATA_12_059C));
		assertEquals(8000, damageOn(mw, theirs), "cost 4, not Kujata's own cost 4 read twice");
	}

	@Test
	void kujataReadsTheCostOfTheForwardItChose() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 2, 10000);
		placeP1Forward(mw, theirs);
		castAsP2(mw, makeSummon("Kujata", "Earth", 4, KUJATA_12_059C));
		assertEquals(4000, damageOn(mw, theirs));
	}

	// =========================================================================================
	// 12-068H Fenrir: "Select up to 2 of the 4 following actions. "Choose 1 Light Forward or Dark
	// Forward. Break it." "Choose 1 Backup of cost 4 or more. Break it." "Choose 1 Monster. Break
	// it." "Choose 1 Earth Forward of cost 2 in your Break Zone. Play it onto the field dull.""
	// =========================================================================================

	private static final String FENRIR_12_068H = "Select up to 2 of the 4 following actions.[[br]]   \"Choose 1 "
			+ "Light Forward or Dark Forward. Break it.\"[[br]]   \"Choose 1 Backup of cost 4 or more. Break "
			+ "it.\"[[br]]   \"Choose 1 Monster. Break it.\"[[br]]   \"Choose 1 Earth Forward of cost 2 in your "
			+ "Break Zone. Play it onto the field dull.\"";

	@Test
	void fenrirBreaksALightForwardAndABackupOfCostFourOrMore() {
		MainWindow mw = new MainWindow();
		CardData light = makeForward("Light One", "Light", 3, 7000);
		CardData fire  = makeForward("Fire One", "Fire", 3, 7000);
		placeP1Forward(mw, fire);
		placeP1Forward(mw, light);
		CardData cheap = makeBackup("Cheap Backup", "Fire", 3);
		CardData dear  = makeBackup("Dear Backup", "Fire", 4);
		placeP1Backup(mw, cheap);
		placeP1Backup(mw, dear);
		castAsP2Selecting(mw, makeSummon("Fenrir", "Earth", 5, FENRIR_12_068H), 0, 1);

		assertEquals(List.of(fire), mw.p1ForwardCards, "only a Light or Dark Forward");
		assertTrue(mw.gameState.getP1BreakZone().contains(dear));
		assertTrue(p1BackupOnField(mw, cheap), "cost 3 is under 4");
	}

	@Test
	void fenrirBreaksAMonsterAndPlaysAnEarthForwardOfCostTwoDull() {
		MainWindow mw = new MainWindow();
		CardData monster = makeMonster("Monster", "Water", 2);
		placeP1Monster(mw, monster);
		CardData earthTwo   = makeForward("Earth Two", "Earth", 2, 5000);
		CardData earthThree = makeForward("Earth Three", "Earth", 3, 7000);
		CardData fireTwo    = makeForward("Fire Two", "Fire", 2, 5000);
		for (CardData c : List.of(earthThree, fireTwo, earthTwo)) {
			mw.gameState.getIdentity().put(c, false);
			mw.gameState.getP2BreakZone().add(c);
		}
		castAsP2Selecting(mw, makeSummon("Fenrir", "Earth", 5, FENRIR_12_068H), 2, 3);

		assertFalse(mw.p1MonsterCards.contains(monster));
		assertEquals(List.of(earthTwo), mw.p2ForwardCards, "Earth, and of cost 2");
		assertEquals(CardState.DULL, mw.p2ForwardStates.get(0));
	}

	// =========================================================================================
	// 12-077H Odin: "The cost required to cast Odin is reduced by 1 for each Card Name Odin in your
	// Break Zone (it cannot become 0). Choose 1 Forward. Break it."
	// =========================================================================================

	private static final String ODIN_12_077H = "The cost required to cast Odin is reduced by 1 for each Card Name "
			+ "Odin in your Break Zone (it cannot become 0).[[br]]   Choose 1 Forward. Break it.";

	@Test
	void odinBreaksTheChosenForward() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 7, 12000);
		placeP1Forward(mw, theirs);
		castAsP2(mw, makeSummonWithCostText("Odin", "Lightning", 6, ODIN_12_077H));
		assertTrue(mw.gameState.getP1BreakZone().contains(theirs));
	}

	@Test
	void odinCostsOneLessForEachOdinInYourBreakZone() {
		MainWindow mw = new MainWindow();
		CardData odin = makeSummonWithCostText("Odin", "Lightning", 6, ODIN_12_077H);
		mw.gameState.getP1Hand().add(odin);
		mw.gameState.getP1BreakZone().add(makeSummon("Ramuh", "Lightning", 5, "Choose 1 Forward. Break it."));
		assertEquals(6, mw.effectiveCastCost(odin), "only Card Name Odin counts");

		for (int i = 0; i < 2; i++) mw.gameState.getP1BreakZone().add(makeSummon("Odin", "Lightning", 6, "Break it."));
		assertEquals(4, mw.effectiveCastCost(odin));
	}

	@Test
	void odinsCostCannotBecomeZero() {
		MainWindow mw = new MainWindow();
		CardData odin = makeSummonWithCostText("Odin", "Lightning", 6, ODIN_12_077H);
		mw.gameState.getP1Hand().add(odin);
		for (int i = 0; i < 7; i++) mw.gameState.getP1BreakZone().add(makeSummon("Odin", "Lightning", 6, "Break it."));
		assertEquals(1, mw.effectiveCastCost(odin));
	}

	// =========================================================================================
	// 12-088C Ramuh: "EX BURST Choose 1 active Forward. Break it."
	// =========================================================================================

	private static final String RAMUH_12_088C = "[[ex]]EX BURST [[/]]Choose 1 active Forward. Break it.";

	@Test
	void ramuhBreaksAnActiveForward() {
		MainWindow mw = new MainWindow();
		CardData active = makeForward("Active", "Water", 5, 9000);
		CardData dull   = makeForward("Dull", "Water", 5, 9000);
		placeP1Forward(mw, dull);
		placeP1Forward(mw, active);
		dullP1Forward(mw, dull);
		castAsP2(mw, makeSummon("Ramuh", "Lightning", 5, RAMUH_12_088C));
		assertEquals(List.of(dull), mw.p1ForwardCards, "only an active Forward can be chosen");
	}

	// =========================================================================================
	// 12-097H Syldra: "Search for 2 Water Characters, 2 Category V Characters, or 1 of each, each
	// with a different cost, and add them to your hand."
	// =========================================================================================

	private static final String SYLDRA_12_097H = "Search for 2 Water Characters, 2 Category V Characters, or 1 of "
			+ "each, each with a different cost, and add them to your hand.";

	private static void inP2Deck(MainWindow mw, CardData... cards) {
		Collections.addAll(mw.gameState.getP2MainDeck(), cards);
	}

	@Test
	void syldraFindsTwoWaterCharactersOfDifferentCosts() {
		MainWindow mw = new MainWindow();
		CardData waterTwo   = makeForward("Water Two", "Water", 2, 5000);
		CardData waterTwoB  = makeForward("Water Two B", "Water", 2, 5000);
		CardData waterFour  = makeForward("Water Four", "Water", 4, 8000);
		CardData fire       = makeForward("Fire", "Fire", 3, 7000);
		inP2Deck(mw, waterTwo, waterTwoB, fire, waterFour);
		castAsP2(mw, makeSummon("Syldra", "Water", 4, SYLDRA_12_097H));

		List<CardData> hand = mw.gameState.getP2Hand();
		assertEquals(2, hand.size());
		assertTrue(hand.stream().allMatch(c -> c.element().equals("Water")));
		assertNotEquals(hand.get(0).cost(), hand.get(1).cost(), "each with a different cost");
	}

	@Test
	void syldraTakesOneWaterAndOneCategoryVCharacter() {
		MainWindow mw = new MainWindow();
		CardData water = makeForward("Water Two", "Water", 2, 5000);
		CardData catV  = makeCategoryForward("Bartz", "Wind", "V");
		CardData other = makeForward("Other", "Fire", 5, 9000);
		inP2Deck(mw, other, catV, water);
		castAsP2(mw, makeSummon("Syldra", "Water", 4, SYLDRA_12_097H));

		assertEquals(Set.of(water, catV), Set.copyOf(mw.gameState.getP2Hand()));
	}

	@Test
	void syldraAddsOnlyOneWhenEveryMatchSharesACost() {
		MainWindow mw = new MainWindow();
		inP2Deck(mw, makeForward("Water A", "Water", 2, 5000), makeForward("Water B", "Water", 2, 5000));
		castAsP2(mw, makeSummon("Syldra", "Water", 4, SYLDRA_12_097H));
		assertEquals(1, mw.gameState.getP2Hand().size());
	}

	// =========================================================================================

	// =========================================================================================
	// 12-108C Remora: "Choose 1 Forward. Draw 1 card. Then, until the end of the turn, it loses
	// 2000 power for each card in your hand."
	//
	// The sentence split put the draw in the primary followup and the reduction in the secondary,
	// and neither half means anything alone — "it" in the second names the card the first never
	// chose. Both were logged as unimplemented and nothing happened at all.
	// =========================================================================================

	private static final String REMORA_SUMMON =
			"Choose 1 Forward. Draw 1 card. Then, until the end of the turn, "
			+ "it loses 2000 power for each card in your hand.";

	@Test
	void remoraDrawsBeforeItCountsTheHand() {
		// The order is the point: the drawn card is in hand when the hand is counted, so it is
		// worth another 2000 of reduction. A hand of 2 that draws to 3 reduces by 6000.
		CardData remora = makeForward("Remora", "Water", 2, 0);
		ForwardTarget victim = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);
		GameContext ctx = mock(GameContext.class);
		when(ctx.consumePreloadedTargets()).thenReturn(null);
		when(ctx.yourHandSize()).thenReturn(3);
		when(ctx.selectCharacters(anyInt(), anyBoolean(), anyBoolean(), anyBoolean(), any(), any(),
				anyInt(), any(), anyInt(), any(), anyBoolean(), anyBoolean(), anyBoolean(),
				any(), any(), any(), any(), anyBoolean(), any(), anyBoolean()))
				.thenReturn(List.of(victim));

		Consumer<GameContext> effect = ActionResolver.parse(REMORA_SUMMON, remora);
		assertNotNull(effect, "the printed wording has to parse for the rest of this to mean anything");
		effect.accept(ctx);

		InOrder order = inOrder(ctx);
		order.verify(ctx).drawCards(1);
		order.verify(ctx).yourHandSize();
		verify(ctx).reduceTarget(eq(victim), eq(6000), any());
	}

	@Test
	void andReportsBothHalvesAsOneClause() {
		CardData remora = makeForward("Remora", "Water", 2, 0);
		assertEquals("ChooseCharacter / DrawThenPowerReduceForEachHand",
				ActionResolver.fullDescription(REMORA_SUMMON, remora));
	}

	/**
	 * The cost P1 would pay for {@code summon} from hand with {@code damage} points taken — Opus
	 * 13's "If you have received 5 points of damage or more, the cost required to cast [Self] is
	 * reduced by 3."
	 */
	private static int castCostAtDamage(CardData summon, int damage) {
		MainWindow mw = new MainWindow();
		mw.gameState.getP1Hand().add(summon);
		takeDamage(mw.gameState.getP1DamageZone(), damage);
		return mw.effectiveCastCost(summon);
	}

	// =========================================================================================
	// 13-012R Bahamut: "If you have received 5 points of damage or more, the cost required to cast
	// Bahamut is reduced by 3. EX BURST Choose 1 Forward. Deal it 8000 damage. If it is put from the
	// field into the Break Zone this turn, remove it from the game instead."
	// =========================================================================================

	private static final String BAHAMUT_13_012R = "If you have received 5 points of damage or more, the cost "
			+ "required to cast Bahamut is reduced by 3.[[ex]][[br]]   EX BURST[[/]] Choose 1 Forward. Deal it 8000 "
			+ "damage. If it is put from the field into the Break Zone this turn, remove it from the game instead.";

	@Test
	void bahamutRemovesFromTheGameAForwardItsDamageBreaks() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 4, 7000);
		placeP1Forward(mw, theirs);
		castAsP2(mw, makeSummonWithCostText("Bahamut", "Fire", 5, BAHAMUT_13_012R));
		assertFalse(mw.gameState.getP1BreakZone().contains(theirs));
		assertTrue(mw.gameState.getP1PermanentRfp().contains(theirs));
	}

	@Test
	void bahamutAlsoRemovesAForwardBrokenLaterThatTurn() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 5, 9000);
		placeP1Forward(mw, theirs);
		castAsP2(mw, makeSummonWithCostText("Bahamut", "Fire", 5, BAHAMUT_13_012R));
		assertEquals(8000, damageOn(mw, theirs), "9000 survives the 8000");

		mw.buildGameContext(false).breakTarget(new ForwardTarget(true, 0, ForwardTarget.CardZone.FORWARD));
		assertTrue(mw.gameState.getP1PermanentRfp().contains(theirs), "\"this turn\", not just by the damage");
	}

	@Test
	void bahamutCostsThreeLessOnceYouHaveReceivedFive() {
		assertEquals(5, castCostAtDamage(makeSummonWithCostText("Bahamut", "Fire", 5, BAHAMUT_13_012R), 4));
		assertEquals(2, castCostAtDamage(makeSummonWithCostText("Bahamut", "Fire", 5, BAHAMUT_13_012R), 5));
	}

	// =========================================================================================
	// 13-021R Shiva: "If you have received 5 points of damage or more, the cost required to cast
	// Shiva is reduced by 3. EX BURST Choose up to 2 Forwards opponent controls. Dull them and
	// Freeze them."
	// =========================================================================================

	private static final String SHIVA_13_021R = "If you have received 5 points of damage or more, the cost "
			+ "required to cast Shiva is reduced by 3.[[ex]][[br]]   EX BURST[[/]] Choose up to 2 Forwards "
			+ "opponent controls. Dull them and Freeze them.";

	@Test
	void shivaDullsAndFreezesTwoOfTheirForwards() {
		MainWindow mw = new MainWindow();
		placeP1Forward(mw, makeForward("Left", "Water", 3, 7000));
		placeP1Forward(mw, makeForward("Right", "Water", 3, 7000));
		placeP2Forward(mw, makeForward("Mine", "Ice", 3, 7000));
		castAsP2(mw, makeSummonWithCostText("Shiva", "Ice", 5, SHIVA_13_021R));

		for (int i = 0; i < 2; i++) {
			assertEquals(CardState.DULL, mw.p1ForwardStates.get(i));
			assertTrue(mw.p1ForwardFrozen.get(i));
		}
		assertEquals(CardState.ACTIVE, mw.p2ForwardStates.get(0), "opponent's only");
	}

	@Test
	void shivaCostsThreeLessOnceYouHaveReceivedFive() {
		assertEquals(5, castCostAtDamage(makeSummonWithCostText("Shiva", "Ice", 5, SHIVA_13_021R), 4));
		assertEquals(2, castCostAtDamage(makeSummonWithCostText("Shiva", "Ice", 5, SHIVA_13_021R), 5));
	}

	// =========================================================================================
	// 13-045R Dryad: "EX BURST Choose 1 Character you control. It gains "This Character cannot be
	// chosen by your opponent's abilities." until the end of the turn. If you have received 5 points
	// of damage or more, draw 1 card, all the Characters you control gain "This Character cannot be
	// chosen by your opponent's abilities." until the end of the turn instead."
	// =========================================================================================

	private static final String DRYAD_13_045R = "[[ex]]EX BURST[[/]] Choose 1 Character you control. It gains "
			+ "\"This Character cannot be chosen by your opponent's abilities.\" until the end of the turn. If you "
			+ "have received 5 points of damage or more, draw 1 card, all the Characters you control gain \"This "
			+ "Character cannot be chosen by your opponent's abilities.\" until the end of the turn instead.";

	/** A Forward and a Backup on P2's side and a Forward on P1's, with {@code p2Damage} taken by P2. */
	private static MainWindow dryadBoard(int p2Damage, CardData mineFwd, CardData mineBkp, CardData theirs) {
		MainWindow mw = new MainWindow();
		takeDamage(mw.gameState.getP2DamageZone(), p2Damage);
		fillP2Deck(mw, 2);
		placeP2Forward(mw, mineFwd);
		placeP2Backup(mw, mineBkp);
		placeP1Forward(mw, theirs);
		return mw;
	}

	@Test
	void dryadShieldsOneOfYourCharactersFromAbilities() {
		CardData fwd = makeForward("Mine", "Wind", 3, 7000);
		CardData bkp = makeBackup("My Backup", "Wind", 2);
		CardData theirs = makeForward("Theirs", "Water", 3, 7000);
		MainWindow mw = dryadBoard(4, fwd, bkp, theirs);
		takeDamage(mw.gameState.getP1DamageZone(), 5);
		castAsP2(mw, makeSummon("Dryad", "Wind", 1, DRYAD_13_045R));

		assertTrue(mw.cannotBeChosenByAbilities.contains(fwd) ^ mw.cannotBeChosenByAbilities.contains(bkp),
				"exactly one of the two");
		assertFalse(mw.cannotBeChosenBySummons.contains(fwd) || mw.cannotBeChosenBySummons.contains(bkp),
				"abilities only");
		assertFalse(mw.cannotBeChosenByAbilities.contains(theirs));
		assertTrue(mw.gameState.getP2Hand().isEmpty(), "the draw is the upgrade's, and the opponent's damage is not yours");
	}

	@Test
	void dryadDrawsAndShieldsAllYourCharactersOnceYouHaveReceivedFive() {
		CardData fwd = makeForward("Mine", "Wind", 3, 7000);
		CardData bkp = makeBackup("My Backup", "Wind", 2);
		CardData theirs = makeForward("Theirs", "Water", 3, 7000);
		MainWindow mw = dryadBoard(5, fwd, bkp, theirs);
		castAsP2(mw, makeSummon("Dryad", "Wind", 1, DRYAD_13_045R));

		assertTrue(mw.cannotBeChosenByAbilities.contains(fwd));
		assertTrue(mw.cannotBeChosenByAbilities.contains(bkp), "Characters, so the Backup too");
		assertFalse(mw.cannotBeChosenByAbilities.contains(theirs));
		assertEquals(1, mw.gameState.getP2Hand().size());
	}

	// =========================================================================================
	// 13-053R Alexander: "Choose 1 Forward you control and up to 1 Forward opponent controls.
	// Until the end of the turn, the former gains +3000 power and "This Forward cannot become dull
	// by your opponent's Summons or abilities." If you have received 5 points of damage or more,
	// also deal the latter damage equal to the highest power Forward you control."
	//
	// The former/latter branch for this shape was already written; the pattern reaching it allowed
	// a single non-word character after "abilities" where the printing has two — the grant's own
	// full stop and then the closing quote. Declined on that alone, the card fell past every
	// former/latter case to the plain choose parser, which read the selection and then neither
	// half of the effect.
	// =========================================================================================

	private static final String ALEXANDER_FORMER_LATTER_SUMMON =
			"EX BURST Choose 1 Forward you control and up to 1 Forward opponent controls. "
			+ "Until the end of the turn, the former gains +3000 power and \"This Forward cannot "
			+ "become dull by your opponent's Summons or abilities.\" If you have received 5 points "
			+ "of damage or more, also deal the latter damage equal to the highest power Forward "
			+ "you control.";

	private static final ForwardTarget ALEX_MINE   = new ForwardTarget(true,  0, ForwardTarget.CardZone.FORWARD);
	private static final ForwardTarget ALEX_THEIRS = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);

	/** The two selections come back from consecutive {@code selectCharacters} calls, in text order. */
	private static GameContext alexanderContext(int damageTaken) {
		GameContext ctx = mock(GameContext.class);
		when(ctx.isP1()).thenReturn(true);
		when(ctx.consumePreloadedTargets()).thenReturn(null);
		when(ctx.selfDamageCount()).thenReturn(damageTaken);
		when(ctx.selfHighestForwardPower()).thenReturn(9000);
		when(ctx.selectCharacters(anyInt(), anyBoolean(), anyBoolean(), anyBoolean(), any(), any(),
				anyInt(), any(), anyInt(), any(), anyBoolean(), anyBoolean(), anyBoolean(),
				any(), any(), any(), any(), anyBoolean(), any(), anyBoolean()))
				.thenReturn(List.of(ALEX_MINE))
				.thenReturn(List.of(ALEX_THEIRS));
		return ctx;
	}

	@Test
	void alexanderBoostsAndProtectsYourForwardBelowTheThreshold() {
		GameContext ctx = alexanderContext(4);

		Consumer<GameContext> effect = ActionResolver.parse(ALEXANDER_FORMER_LATTER_SUMMON, null);
		assertNotNull(effect, "the printed wording has to parse for the rest of this to mean anything");
		effect.accept(ctx);

		verify(ctx).boostTarget(ALEX_MINE, 3000,
				EnumSet.of(CardData.Trait.CANNOT_BE_DULLED_BY_OPP));
		verify(ctx, never()).damageTarget(any(), anyInt());
	}

	@Test
	void andBurnsTheirsForYourHighestPowerOnceYouHaveTakenFive() {
		GameContext ctx = alexanderContext(5);

		ActionResolver.parse(ALEXANDER_FORMER_LATTER_SUMMON, null).accept(ctx);

		verify(ctx).boostTarget(ALEX_MINE, 3000,
				EnumSet.of(CardData.Trait.CANNOT_BE_DULLED_BY_OPP));
		verify(ctx).damageTarget(ALEX_THEIRS, 9000);
	}

	@Test
	void alexanderIsReadAsTheFormerLatterCardItIs() {
		assertEquals("ChooseFormerLatter", ActionResolver.fullDescription(ALEXANDER_FORMER_LATTER_SUMMON, null));
	}

	// =========================================================================================
	// 13-072R Odin: "If you have received 5 points of damage or more, the cost required to cast Odin
	// is reduced by 3. EX BURST Choose 1 Forward of cost 5 or less. Break it."
	// =========================================================================================

	private static final String ODIN_13_072R = "If you have received 5 points of damage or more, the cost required "
			+ "to cast Odin is reduced by 3.[[ex]][[br]]   EX BURST[[/]] Choose 1 Forward of cost 5 or less. Break it.";

	@Test
	void odinBreaksAForwardOfCostFiveOrLess() {
		MainWindow mw = new MainWindow();
		CardData six  = makeForward("Six", "Water", 6, 9000);
		CardData five = makeForward("Five", "Water", 5, 9000);
		placeP1Forward(mw, six);
		placeP1Forward(mw, five);
		castAsP2(mw, makeSummonWithCostText("Odin", "Lightning", 5, ODIN_13_072R));
		assertEquals(List.of(six), mw.p1ForwardCards, "cost 6 is over the limit");
	}

	@Test
	void odinCostsThreeLessOnceYouHaveReceivedFive() {
		assertEquals(5, castCostAtDamage(makeSummonWithCostText("Odin", "Lightning", 5, ODIN_13_072R), 4));
		assertEquals(2, castCostAtDamage(makeSummonWithCostText("Odin", "Lightning", 5, ODIN_13_072R), 5));
	}

	// =========================================================================================
	// 13-100R Leviathan: "EX BURST Choose 1 Forward. During this turn, the next damage dealt to it is
	// reduced by 5000 instead. Draw 1 card. If you have received 5 points of damage or more, all the
	// Forwards you control also gain "This Forward cannot be chosen by your opponent's Summons or
	// abilities." until the end of the turn."
	// =========================================================================================

	private static final String LEVIATHAN_13_100R = "[[ex]]EX BURST[[/]] Choose 1 Forward. During this turn, the "
			+ "next damage dealt to it is reduced by 5000 instead. Draw 1 card. If you have received 5 points of "
			+ "damage or more, all the Forwards you control also gain \"This Forward cannot be chosen by your "
			+ "opponent's Summons or abilities.\" until the end of the turn.";

	@Test
	void leviathanShieldsTheNextDamageAndDraws() {
		MainWindow mw = new MainWindow();
		takeDamage(mw.gameState.getP2DamageZone(), 4);
		fillP2Deck(mw, 2);
		CardData mine = makeForward("Mine", "Water", 4, 9000);
		placeP2Forward(mw, mine);
		castAsP2(mw, makeSummon("Leviathan", "Water", 2, LEVIATHAN_13_100R));

		assertEquals(1, mw.gameState.getP2Hand().size());
		assertFalse(mw.cannotBeChosenBySummons.contains(mine), "four is not five");
		mw.buildGameContext(true).damageTarget(new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD), 7000);
		assertEquals(2000, (int) mw.p2ForwardDamage.get(0), "7000 less 5000");
	}

	@Test
	void leviathanAlsoShieldsAllYourForwardsOnceYouHaveReceivedFive() {
		MainWindow mw = new MainWindow();
		takeDamage(mw.gameState.getP2DamageZone(), 5);
		fillP2Deck(mw, 2);
		CardData left  = makeForward("Left", "Water", 4, 9000);
		CardData right = makeForward("Right", "Water", 4, 9000);
		CardData theirs = makeForward("Theirs", "Fire", 4, 9000);
		placeP2Forward(mw, left);
		placeP2Forward(mw, right);
		placeP1Forward(mw, theirs);
		castAsP2(mw, makeSummon("Leviathan", "Water", 2, LEVIATHAN_13_100R));

		for (CardData c : List.of(left, right)) {
			assertTrue(mw.cannotBeChosenBySummons.contains(c));
			assertTrue(mw.cannotBeChosenByAbilities.contains(c));
		}
		assertFalse(mw.cannotBeChosenBySummons.contains(theirs), "yours only");
		assertEquals(1, mw.gameState.getP2Hand().size(), "the draw happens either way");
	}

	// =========================================================================================
	// 14-002R Ifrita: "EX BURST Choose 1 Forward. Deal it 7000 damage. Search for 1 Card Name Ifrita
	// and add it to your hand."
	// =========================================================================================

	private static final String IFRITA_14_002R = "[[ex]]EX BURST[[/]] Choose 1 Forward. Deal it 7000 damage. Search "
			+ "for 1 Card Name Ifrita and add it to your hand.";

	@Test
	void ifritaDeals7000AndSearchesForAnotherIfrita() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 5, 9000);
		placeP1Forward(mw, theirs);
		CardData ifrit  = makeSummon("Ifrit", "Fire", 3, "Choose 1 Forward. Deal it 5000 damage.");
		CardData ifrita = makeSummon("Ifrita", "Fire", 5, IFRITA_14_002R);
		mw.gameState.getP2MainDeck().add(ifrit);
		mw.gameState.getP2MainDeck().add(ifrita);
		castAsP2(mw, makeSummon("Ifrita", "Fire", 5, IFRITA_14_002R));

		assertEquals(7000, damageOn(mw, theirs));
		assertEquals(List.of(ifrita), mw.gameState.getP2Hand(), "Card Name Ifrita, not Ifrit");
	}

	// =========================================================================================
	// 14-029R Shivalry: "EX BURST Choose 1 Forward. Until the end of the turn, it gains +3000 power
	// and "When a Forward opponent controls is put from the field into the Break Zone on the same
	// turn that the chosen Forward has dealt it damage, your opponent discards 1 card from their
	// hand.""
	// =========================================================================================

	private static final String SHIVALRY_14_029R = "[[ex]]EX BURST[[/]] Choose 1 Forward. Until the end of the turn, "
			+ "it gains +3000 power and \"When a Forward opponent controls is put from the field into the Break "
			+ "Zone on the same turn that the chosen Forward has dealt it damage, your opponent discards 1 card "
			+ "from their hand.\"";

	@Test
	void theCpuGivesShivalrysBoostToItsOwnForward() {
		// The AI read the grant's "Break Zone" as a break and aimed the whole buff at the opponent.
		MainWindow mw = new MainWindow();
		placeP2Forward(mw, makeForward("Mine", "Ice", 3, 7000));
		placeP1Forward(mw, makeForward("Theirs", "Water", 3, 7000));
		castAsP2(mw, makeSummon("Shivalry", "Ice", 2, SHIVALRY_14_029R));
		assertEquals(10000, mw.effectiveP2ForwardPower(0));
		assertEquals(7000, mw.effectiveP1ForwardPower(0));
	}

	/**
	 * P1 casts Shivalry on its own Forward (preloaded, so no dialog) and then breaks P2's, which the
	 * CPU answers; with {@code damaged}, P1's Forward is recorded as having damaged it first.
	 */
	private static MainWindow shivalryThenBreakTheirs(boolean damaged) {
		MainWindow mw = new MainWindow();
		CardData mine   = makeForward("Mine", "Ice", 3, 7000);
		CardData theirs = makeForward("Theirs", "Water", 3, 7000);
		placeP1Forward(mw, mine);
		placeP2Forward(mw, theirs);
		mw.gameState.getP2Hand().add(makeForward("Held", "Water", 1, 1000));
		mw.gameState.getP2Hand().add(makeForward("Held too", "Water", 1, 1000));
		GameContext ctx = mw.buildGameContext(true);
		ctx.preloadTargets(List.of(new ForwardTarget(true, 0, ForwardTarget.CardZone.FORWARD)));
		CardData shivalry = makeSummon("Shivalry", "Ice", 2, SHIVALRY_14_029R);
		ActionResolver.parse(shivalry.summonEffect(), shivalry).accept(ctx);
		assertEquals(10000, mw.effectiveP1ForwardPower(0), "+3000 to the chosen Forward");

		if (damaged) mw.recordDamagedBy(theirs, mine);
		ctx.breakTarget(new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD));
		assertTrue(mw.gameState.getP2BreakZone().contains(theirs));
		return mw;
	}

	@Test
	void shivalrysForwardMakesThemDiscardWhenAForwardItDamagedIsBroken() {
		assertEquals(1, shivalryThenBreakTheirs(true).gameState.getP2Hand().size());
	}

	@Test
	void butNotWhenTheBrokenForwardWasNeverDamagedByIt() {
		assertEquals(2, shivalryThenBreakTheirs(false).gameState.getP2Hand().size());
	}

	// =========================================================================================
	// 14-047R Choco/Mog: "Choose 1 Forward. Deal it 1000 damage for each Character you control.
	// If you control a Job Chocobo or Card Name Chocobo and a Job Moogle or Card Name Moogle,
	// draw 1 card."
	//
	// A conjunction of two conditions, each wanting a card of its own — not a disjunction, and not
	// one condition over one card. Both existing modes claimed it whole and got it wrong: named
	// mode read it as an either-or over the names "Chocobo and a Job Moogle" and "Moogle", so it
	// drew on any Moogle at all and never on the pair; count mode's Job group, which runs to the
	// next " or Card Name", took 7-062R Hope's "a Job Father and a Job Mother" as one Job name
	// that matches nobody.
	// =========================================================================================

	private static final String CHOCO_MOG_CONDITION =
			"a Job Chocobo or Card Name Chocobo and a Job Moogle or Card Name Moogle";

	/** A board with the given Forwards on P1's side, asked whether Choco/Mog's condition holds. */
	private static boolean chocoMogConditionOn(CardData... p1Forwards) {
		MainWindow mw = new MainWindow();
		for (CardData c : p1Forwards) placeP1Forward(mw, c);
		ControlCondition cond = CardData.parseControlCondition(CHOCO_MOG_CONDITION);
		assertNotNull(cond, "the condition has to parse for the rest of this to mean anything");
		return mw.controlConditionMet(cond, true);
	}

	@Test
	void chocoMogNeedsBothHalves() {
		assertFalse(chocoMogConditionOn(makeJobCard("Chocobo", "Wind", "Forward", "Chocobo")),
				"a Chocobo alone is not enough");
		assertFalse(chocoMogConditionOn(makeJobCard("Mog", "Wind", "Forward", "Moogle")),
				"nor is a Moogle alone — which is what the old read drew on");
		assertTrue(chocoMogConditionOn(
						makeJobCard("Chocobo", "Wind", "Forward", "Chocobo"),
						makeJobCard("Mog", "Wind", "Forward", "Moogle")),
				"both together satisfy it");
	}

	@Test
	void andEachHalfTakesTheJobOrTheCardName() {
		// "a Job Chocobo or Card Name Chocobo" — either arm satisfies that half on its own, which
		// is the disjunction named mode dropped when it read the name and threw the Job away.
		assertTrue(chocoMogConditionOn(
						makeForward("Chocobo", "Wind", 3, 7000),                       // by name
						makeJobCard("Mog", "Wind", "Forward", "Moogle")),              // by job
				"a card named Chocobo pairs with a Job Moogle");
		assertTrue(chocoMogConditionOn(
						makeJobCard("Boco", "Wind", "Forward", "Chocobo"),             // by job
						makeForward("Moogle", "Wind", 3, 7000)),                       // by name
				"and a Job Chocobo pairs with a card named Moogle");
	}

	@Test
	void andOneCardCannotSatisfyBothHalves() {
		// Each half wants a card of its own, which is what separates this from the per-card OR the
		// record already had.
		assertFalse(chocoMogConditionOn(makeJobCard("Chocobo", "Wind", "Forward", "Chocobo|Moogle")),
				"a single Character counts once, however many of the Jobs it carries");
	}

	@Test
	void andHopesPlainerConjunctionReadsTheSameWay() {
		// 7-062R Hope, the other printing of this shape and the one count mode mangled.
		ControlCondition cond = CardData.parseControlCondition("a Job Father and a Job Mother");
		assertNotNull(cond);
		assertEquals("1+ Father & 1+ Mother", cond.toString());
	}

	@Test
	void chocoMogsConditionIsDescribedAsTheConjunctionItIs() {
		assertEquals("ChooseCharacter / DamageForEach + IfControl(1+ Chocobo/Chocobo & 1+ Moogle/Moogle: DrawCards)",
				ActionResolver.fullDescription(
						"EX BURST Choose 1 Forward. Deal it 1000 damage for each Character you control. "
						+ "If you control a Job Chocobo or Card Name Chocobo and a Job Moogle or "
						+ "Card Name Moogle, draw 1 card.", null));
	}

	private static final String CHOCO_MOG_14_047R = "[[ex]]EX BURST[[/]] Choose 1 Forward. Deal it 1000 damage for "
			+ "each Character you control. If you control a Job Chocobo or Card Name Chocobo and a Job Moogle or "
			+ "Card Name Moogle, draw 1 card.";

	@Test
	void chocoMogDeals1000ForEachOfYourCharactersAndDrawsForThePair() {
		MainWindow mw = new MainWindow();
		fillP2Deck(mw, 2);
		CardData theirs = makeForward("Theirs", "Water", 5, 9000);
		placeP1Forward(mw, theirs);
		placeP1Forward(mw, makeForward("Their Other", "Water", 5, 9000));
		placeP2Forward(mw, makeJobCard("Boco", "Wind", "Forward", "Chocobo"));
		placeP2Forward(mw, makeJobCard("Mog", "Wind", "Forward", "Moogle"));
		placeP2Backup(mw, makeBackup("My Backup", "Wind", 2));
		castAsP2(mw, makeSummon("Choco/Mog", "Wind", 2, CHOCO_MOG_14_047R));

		assertEquals(3000, damageOn(mw, theirs) + damageOn(mw, mw.p1ForwardCards.get(1)),
				"three Characters of yours; theirs do not count");
		assertEquals(1, mw.gameState.getP2Hand().size());
	}

	@Test
	void chocoMogDoesNotDrawWithoutThePair() {
		MainWindow mw = new MainWindow();
		fillP2Deck(mw, 2);
		placeP1Forward(mw, makeForward("Theirs", "Water", 5, 9000));
		placeP2Forward(mw, makeJobCard("Boco", "Wind", "Forward", "Chocobo"));
		castAsP2(mw, makeSummon("Choco/Mog", "Wind", 2, CHOCO_MOG_14_047R));
		assertTrue(mw.gameState.getP2Hand().isEmpty());
	}

	// =========================================================================================
	// 14-060R Carbuncle: "EX BURST All the Forwards you control gain +2000 power until the end of the
	// turn. If all the Backups you control have Earth Element, draw 1 card."
	//
	// It resolved its power grant and then its conditional draw, but neither the pattern name nor
	// the description could say so: the gate that carries it was in parse()'s chain and in
	// neither report chain, so the record read "AllFieldPowerBoost + ?".
	// =========================================================================================

	private static final String CARBUNCLE_14_060R =
			"[[ex]]EX BURST[[/]] All the Forwards you control gain +2000 power until the end of "
			+ "the turn. If all the Backups you control have Earth Element, draw 1 card.";
	private static GameContext resolveCarbuncle(boolean allBackupsEarth) {
		CardData carbuncle = makeSummon("Carbuncle", "Earth", 1, CARBUNCLE_14_060R);
		Consumer<GameContext> fn = ActionResolver.parse(carbuncle.summonEffect(), carbuncle);
		assertNotNull(fn, "Carbuncle's two halves should parse");
		GameContext ctx = mock(GameContext.class);
		when(ctx.controlConditionMet(any())).thenReturn(allBackupsEarth);
		fn.accept(ctx);
		return ctx;
	}

	@Test
	void carbuncleAlwaysBoostsAndDrawsOnlyOnAnAllEarthBench() {
		verify(resolveCarbuncle(true)).drawCards(1);
		GameContext mixed = resolveCarbuncle(false);
		verify(mixed).applyMassFieldPowerBoost(2000, true, false, false, true, null, -1, null, null, null,
				EnumSet.noneOf(CardData.Trait.class));
		verify(mixed, never()).drawCards(anyInt());
	}

	@Test
	void carbunclesConditionalDrawIsNamedRatherThanLeftAsAQuestionMark() {
		CardData carbuncle = makeSummon("Carbuncle", "Earth", 1, CARBUNCLE_14_060R);
		String text = carbuncle.summonEffect();
		assertEquals("AllFieldPowerBoost + IfAllHaveElement",
				ActionResolver.matchedPatternName(text, carbuncle));
		assertEquals("AllFieldPowerBoost + IfAllHaveElement(Backups=Earth: DrawCards)",
				ActionResolver.fullDescription(text, carbuncle));
	}

	// =========================================================================================
	// 14-091R Ramuh: "EX BURST Choose up to 2 Forwards. Deal them 1000 damage and 1000 more damage
	// for each Card Name Ramuh in your Break Zone."
	// =========================================================================================

	private static final String RAMUH_14_091R = "[[ex]]EX BURST[[/]] Choose up to 2 Forwards. Deal them 1000 damage "
			+ "and 1000 more damage for each Card Name Ramuh in your Break Zone.";

	@Test
	void ramuhDeals1000PlusAThousandForEachRamuhInYourBreakZone() {
		MainWindow mw = new MainWindow();
		CardData left  = makeForward("Left", "Water", 4, 9000);
		CardData right = makeForward("Right", "Water", 4, 9000);
		placeP1Forward(mw, left);
		placeP1Forward(mw, right);
		for (int i = 0; i < 2; i++) mw.gameState.getP2BreakZone().add(makeSummon("Ramuh", "Lightning", 2, "Draw 1 card."));
		mw.gameState.getP2BreakZone().add(makeSummon("Ifrit", "Fire", 2, "Draw 1 card."));
		mw.gameState.getP1BreakZone().add(makeSummon("Ramuh", "Lightning", 2, "Draw 1 card."));
		castAsP2(mw, makeSummon("Ramuh", "Lightning", 2, RAMUH_14_091R));

		assertEquals(3000, damageOn(mw, left), "1000, and 1000 for each of your two Ramuh");
		assertEquals(3000, damageOn(mw, right));
	}

	@Test
	void ramuhDealsItsBase1000WithNoRamuhInYourBreakZone() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 4, 9000);
		placeP1Forward(mw, theirs);
		castAsP2(mw, makeSummon("Ramuh", "Lightning", 2, RAMUH_14_091R));
		assertEquals(1000, damageOn(mw, theirs));
	}

	// =========================================================================================
	// 14-113R Leviathan: "EX BURST Select up to 2 of the 3 following actions. "Choose 1 Forward
	// opponent controls. Return it to its owner's hand." "Choose 1 Light Forward or Dark Forward. Put
	// it at the top or bottom of its owner's deck." "Choose 1 Water Forward in your Break Zone. Add it
	// to your hand.""
	// =========================================================================================

	private static final String LEVIATHAN_14_113R = "[[ex]]EX BURST[[/]] Select up to 2 of the 3 following actions."
			+ "[[br]]   \"Choose 1 Forward opponent controls. Return it to its owner's hand.\"[[br]]   \"Choose 1 "
			+ "Light Forward or Dark Forward. Put it at the top or bottom of its owner's deck.\"[[br]]   \"Choose 1 "
			+ "Water Forward in your Break Zone. Add it to your hand.\"";

	@Test
	void leviathanReturnsTheirForwardAndRecoversAWaterForward() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Fire", 4, 8000);
		placeP1Forward(mw, theirs);
		CardData water = makeForward("Water One", "Water", 3, 7000);
		CardData fire  = makeForward("Fire One", "Fire", 3, 7000);
		for (CardData c : List.of(fire, water)) {
			mw.gameState.getIdentity().put(c, false);
			mw.gameState.getP2BreakZone().add(c);
		}
		castAsP2Selecting(mw, makeSummon("Leviathan", "Water", 6, LEVIATHAN_14_113R), 0, 2);

		assertTrue(mw.gameState.getP1Hand().contains(theirs));
		assertEquals(List.of(water), mw.gameState.getP2Hand(), "a Water Forward only");
		assertTrue(mw.gameState.getP2BreakZone().contains(fire));
	}

	@Test
	void leviathanPutsALightForwardIntoItsOwnersDeck() {
		MainWindow mw = new MainWindow();
		CardData light = makeForward("Light One", "Light", 4, 8000);
		CardData water = makeForward("Water One", "Water", 4, 8000);
		placeP1Forward(mw, water);
		placeP1Forward(mw, light);
		castAsP2Selecting(mw, makeSummon("Leviathan", "Water", 6, LEVIATHAN_14_113R), 1);

		assertEquals(List.of(water), mw.p1ForwardCards, "only a Light or Dark Forward");
		assertTrue(mw.gameState.getP1MainDeck().contains(light), "into its owner's deck");
	}

	/**
	 * Opus 15's "Before paying the cost to cast [Self], you can pay 《C》 to reduce the cost required
	 * to cast [Self] by 2." — an alternative cost of one Crystal and the printed cost less two.
	 */
	private static void assertCrystalReducesByTwo(CardData summon, String element) {
		assertEquals(1, summon.altCrystalCost(), summon.name() + ": one Crystal");
		assertEquals(Collections.nCopies(summon.cost() - 2, element), summon.altCpElements(),
				summon.name() + ": the cost less two");
		assertFalse(summon.summonEffect().contains("Before paying"), "the cost sentence is not the effect");
	}

	// =========================================================================================
	// 15-009C Bahamut: "Before paying the cost to cast Bahamut, you can pay 《C》 to reduce the cost
	// required to cast Bahamut by 2. Choose 1 Forward. Deal it 9000 damage."
	// =========================================================================================

	private static final String BAHAMUT_15_009C = "Before paying the cost to cast Bahamut, you can pay 《C》 to reduce "
			+ "the cost required to cast Bahamut by 2.[[br]]   Choose 1 Forward. Deal it 9000 damage.";

	@Test
	void bahamutDeals9000ToTheChosenForward() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 6, 10000);
		placeP1Forward(mw, theirs);
		castAsP2(mw, makeSummon("Bahamut", "Fire", 4, BAHAMUT_15_009C));
		assertEquals(9000, damageOn(mw, theirs));
	}

	@Test
	void bahamutCanPayACrystalForTwoLess() {
		assertCrystalReducesByTwo(makeSummon("Bahamut", "Fire", 4, BAHAMUT_15_009C), "Fire");
	}


	// =========================================================================================
	// 15-014H Brynhildr: "EX BURST Choose 1 Forward. Deal it 5000 damage. When it is put from the
	// field into the Break Zone this turn, draw 1 card." — the trailing sentence is a delayed
	// trigger marked onto the chosen Forward, firing for the caster whenever that Forward later
	// leaves the field for the Break Zone. The mark has to be applied before the damage, or the
	// common case (5000 is lethal) would break the Forward before anything was watching it.
	// =========================================================================================

	private static final String BRYNHILDR_TEXT =
			"Choose 1 Forward. Deal it 5000 damage. "
			+ "When it is put from the field into the Break Zone this turn, draw 1 card.";

	/** P1 casts Brynhildr at P2's only Forward, which has {@code oppPower} power. */
	private static MainWindow castBrynhildrAt(int oppPower) {
		MainWindow mw = new MainWindow();
		mw.gameState.initializeDeck(List.of(
				makeForward("Deck Card A", "Fire", 2, 5000),
				makeForward("Deck Card B", "Fire", 2, 5000)), List.of());
		mw.gameState.getP1Hand().clear();

		CardData victim = makeForward("Victim", "Ice", 3, oppPower);
		mw.gameState.getIdentity().put(victim, false);
		mw.placeP2CardInForwardZone(victim);

		GameContext ctx = mw.buildGameContext(true);
		ctx.preloadTargets(List.of(new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD)));
		ActionResolver.parse(BRYNHILDR_TEXT, null).accept(ctx);
		return mw;
	}

	@Test
	void brynhildrDrawsWhenItsOwnDamageIsLethal() {
		MainWindow mw = castBrynhildrAt(5000);
		assertTrue(mw.p2ForwardCards.isEmpty(), "5000 damage to a 5000-power Forward breaks it");
		assertEquals(1, mw.gameState.getP2BreakZone().size(), "the Forward reached the Break Zone");
		assertEquals(1, mw.gameState.getP1Hand().size(),
				"the mark is set before the damage, so a lethal hit still draws");
		assertTrue(mw.gameState.getP2Hand().isEmpty(),
				"the caster draws, not the broken Forward's controller");
	}

	@Test
	void brynhildrDrawsLaterWhenTheMarkedForwardSurvivesTheDamage() {
		MainWindow mw = castBrynhildrAt(9000);
		assertEquals(1, mw.p2ForwardCards.size(), "5000 damage does not break a 9000-power Forward");
		assertTrue(mw.gameState.getP1Hand().isEmpty(), "nothing drawn while it is still on the field");

		mw.breakP2Forward(0);   // broken later in the turn by something else
		assertEquals(1, mw.gameState.getP1Hand().size(),
				"the delayed trigger fires whatever puts it into the Break Zone");
	}

	@Test
	void brynhildrDrawTriggerFiresOnlyOnce() {
		MainWindow mw = castBrynhildrAt(5000);
		assertEquals(1, mw.gameState.getP1Hand().size());
		// A second trip through the Break Zone (e.g. replayed from the Break Zone and broken again)
		// must not re-fire a mark that was already consumed.
		mw.addToBreakZone(mw.gameState.getP2BreakZone().get(0), true);
		assertEquals(1, mw.gameState.getP1Hand().size(), "the mark is consumed when it fires");
	}

	@Test
	void brynhildrsDelayedClauseIsNamedRatherThanReportedUnread() {
		// The report used to read the clause as an unread tail on a card that had been drawing all
		// along. The mark carries an arbitrary effect rather than a card count, so the delayed half
		// names what it will do; a draw is one payload among several.
		assertEquals("ChooseCharacter / Damage + OnFieldToBz(DrawCards)",
				ActionResolver.fullDescription(BRYNHILDR_TEXT, null));
	}

	// =========================================================================================
	// 15-030H Shiva: "EX BURST Choose 1 Forward and up to 1 other Forward. Dull the former. If you
	// have 2 or more Card Name Shiva in your Break Zone, also dull the latter. If you have 4 or more,
	// also Freeze them. If you have 6 or more, also your opponent discards 2 cards from their hand."
	// =========================================================================================

	private static final String SHIVA_15_030H = "[[ex]]EX BURST[[/]] Choose 1 Forward and up to 1 other Forward. "
			+ "Dull the former. If you have 2 or more Card Name Shiva in your Break Zone, also dull the latter. If "
			+ "you have 4 or more, also Freeze them. If you have 6 or more, also your opponent discards 2 cards "
			+ "from their hand.";

	/**
	 * Casts Shiva from P2's seat with {@code shivas} Card Name Shiva (and one Ifrit) in P2's Break
	 * Zone, against two P1 Forwards and a two-card P1 hand — which a forced discard of 2 empties
	 * without asking.
	 */
	private static MainWindow shivaWithShivasInBreakZone(int shivas) {
		MainWindow mw = new MainWindow();
		placeP1Forward(mw, makeForward("Left", "Water", 3, 7000));
		placeP1Forward(mw, makeForward("Right", "Water", 3, 7000));
		inP1Hand(mw, 2);
		mw.gameState.getP2BreakZone().add(makeSummon("Ifrit", "Fire", 1, "Draw 1 card."));
		for (int i = 0; i < shivas; i++) mw.gameState.getP2BreakZone().add(makeSummon("Shiva", "Ice", 1, "Draw 1 card."));
		castAsP2(mw, makeSummon("Shiva", "Ice", 1, SHIVA_15_030H));
		return mw;
	}

	private static int dullP1Forwards(MainWindow mw) {
		return (int) mw.p1ForwardStates.stream().filter(s -> s == CardState.DULL).count();
	}

	@Test
	void shivaDullsOnlyTheFormerWithFewerThanTwoShivas() {
		MainWindow mw = shivaWithShivasInBreakZone(1);
		assertEquals(1, dullP1Forwards(mw), "the Ifrit is not a Shiva");
		assertFalse(mw.p1ForwardFrozen.contains(true));
		assertEquals(2, mw.gameState.getP1Hand().size());
	}

	@Test
	void shivaAlsoDullsTheLatterWithTwoShivas() {
		MainWindow mw = shivaWithShivasInBreakZone(2);
		assertEquals(2, dullP1Forwards(mw));
		assertFalse(mw.p1ForwardFrozen.contains(true));
	}

	@Test
	void shivaAlsoFreezesThemWithFour() {
		MainWindow mw = shivaWithShivasInBreakZone(4);
		assertEquals(2, dullP1Forwards(mw));
		assertEquals(List.of(true, true), mw.p1ForwardFrozen);
		assertEquals(2, mw.gameState.getP1Hand().size(), "the discard is six's");
	}

	@Test
	void shivaAlsoMakesThemDiscardTwoWithSix() {
		MainWindow mw = shivaWithShivasInBreakZone(6);
		assertEquals(2, dullP1Forwards(mw));
		assertEquals(List.of(true, true), mw.p1ForwardFrozen);
		assertTrue(mw.gameState.getP1Hand().isEmpty());
	}

	// =========================================================================================
	// 15-031C Shiva: "Before paying the cost to cast Shiva, you can pay 《C》 to reduce the cost
	// required to cast Shiva by 2. Select 1 of the 2 following actions. "Choose 1 dull Forward. Deal
	// it 9000 damage." "All the Ice Forwards you control gain +3000 power until the end of the
	// turn.""
	// =========================================================================================

	private static final String SHIVA_15_031C = "Before paying the cost to cast Shiva, you can pay 《C》 to reduce the "
			+ "cost required to cast Shiva by 2.[[br]]   Select 1 of the 2 following actions.[[br]]   \"Choose 1 dull "
			+ "Forward. Deal it 9000 damage.\"[[br]]   \"All the Ice Forwards you control gain +3000 power until the "
			+ "end of the turn.\"";

	@Test
	void shivaDeals9000ToADullForward() {
		MainWindow mw = new MainWindow();
		CardData active = makeForward("Active", "Water", 5, 10000);
		CardData dull   = makeForward("Dull", "Water", 5, 10000);
		placeP1Forward(mw, active);
		placeP1Forward(mw, dull);
		dullP1Forward(mw, dull);
		castAsP2Selecting(mw, makeSummon("Shiva", "Ice", 3, SHIVA_15_031C), 0);
		assertEquals(9000, damageOn(mw, dull));
		assertEquals(0, damageOn(mw, active));
	}

	@Test
	void shivaBoostsYourIceForwards() {
		MainWindow mw = new MainWindow();
		placeP2Forward(mw, makeForward("Ice One", "Ice", 3, 7000));
		placeP2Forward(mw, makeForward("Fire One", "Fire", 3, 7000));
		placeP1Forward(mw, makeForward("Their Ice", "Ice", 3, 7000));
		castAsP2Selecting(mw, makeSummon("Shiva", "Ice", 3, SHIVA_15_031C), 1);
		assertEquals(10000, mw.effectiveP2ForwardPower(0));
		assertEquals(7000, mw.effectiveP2ForwardPower(1), "Ice only");
		assertEquals(7000, mw.effectiveP1ForwardPower(0), "yours only");
	}

	@Test
	void shivaCanPayACrystalForTwoLess() {
		assertCrystalReducesByTwo(makeSummon("Shiva", "Ice", 3, SHIVA_15_031C), "Ice");
	}

	// =========================================================================================
	// 15-049C Garchimacera: "Before paying the cost to cast Garchimacera, you can pay 《C》 to reduce
	// the cost required to cast Garchimacera by 2. Select 1 of the 2 following actions. "Choose 1
	// Forward of cost 2 or less. Return it to its owner's hand. Draw 1 card." "Choose 1 Forward of
	// cost 5 or more. Break it.""
	// =========================================================================================

	private static final String GARCHIMACERA_15_049C = "Before paying the cost to cast Garchimacera, you can pay 《C》 "
			+ "to reduce the cost required to cast Garchimacera by 2.[[br]]   Select 1 of the 2 following actions."
			+ "[[br]]   \"Choose 1 Forward of cost 2 or less. Return it to its owner's hand. Draw 1 card.\"[[br]]   "
			+ "\"Choose 1 Forward of cost 5 or more. Break it.\"";

	@Test
	void garchimaceraReturnsACheapForwardAndDraws() {
		MainWindow mw = new MainWindow();
		fillP2Deck(mw, 2);
		CardData two   = makeForward("Two", "Water", 2, 5000);
		CardData three = makeForward("Three", "Water", 3, 7000);
		placeP1Forward(mw, three);
		placeP1Forward(mw, two);
		castAsP2Selecting(mw, makeSummon("Garchimacera", "Wind", 3, GARCHIMACERA_15_049C), 0);
		assertTrue(mw.gameState.getP1Hand().contains(two));
		assertEquals(List.of(three), mw.p1ForwardCards, "cost 3 is over 2");
		assertEquals(1, mw.gameState.getP2Hand().size());
	}

	@Test
	void garchimaceraBreaksAForwardOfCostFiveOrMore() {
		MainWindow mw = new MainWindow();
		CardData four = makeForward("Four", "Water", 4, 8000);
		CardData five = makeForward("Five", "Water", 5, 9000);
		placeP1Forward(mw, five);
		placeP1Forward(mw, four);
		castAsP2Selecting(mw, makeSummon("Garchimacera", "Wind", 3, GARCHIMACERA_15_049C), 1);
		assertEquals(List.of(four), mw.p1ForwardCards);
	}

	@Test
	void garchimaceraCanPayACrystalForTwoLess() {
		assertCrystalReducesByTwo(makeSummon("Garchimacera", "Wind", 3, GARCHIMACERA_15_049C), "Wind");
	}

	// =========================================================================================
	// 15-053H Diabolos: "Choose 1 Forward. Its power becomes 3000 until the end of the turn. If
	// you have cast 4 or more cards this turn, all the Forwards' power become 3000 until the end
	// of the turn instead."
	//
	// The corpus's only board-wide "power become". Wiring the gate without it left the upgraded
	// branch selected and then unable to do anything, which was a worse answer at 4+ casts than
	// the single-target base it replaced.
	//
	// The choose still happens on the upgraded branch: the card names a target and then overrides
	// every Forward, so the selection is kept for the "when chosen" triggers that watch it.
	// =========================================================================================

	private static final String DIABOLOS_SUMMON =
			"Choose 1 Forward. Its power becomes 3000 until the end of the turn. If you have cast "
			+ "4 or more cards this turn, all the Forwards' power become 3000 until the end of the "
			+ "turn instead.";

	@Test
	void diabolosSetsOneForwardUnderTheThreshold() {
		CardData diabolos = makeForward("Diabolos", "Ice", 4, 0);
		ForwardTarget victim = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);
		GameContext ctx = mock(GameContext.class);
		when(ctx.consumePreloadedTargets()).thenReturn(null);
		when(ctx.selfCardsCastThisTurn()).thenReturn(3);
		when(ctx.selectCharacters(anyInt(), anyBoolean(), anyBoolean(), anyBoolean(), any(), any(),
				anyInt(), any(), anyInt(), any(), anyBoolean(), anyBoolean(), anyBoolean(),
				any(), any(), any(), any(), anyBoolean(), any(), anyBoolean()))
				.thenReturn(List.of(victim));

		ActionResolver.parse(DIABOLOS_SUMMON, diabolos).accept(ctx);

		verify(ctx).setTargetBasePower(victim, 3000);
		verify(ctx, never()).setAllForwardsBasePower(anyInt());
	}

	@Test
	void andTheWholeBoardAtIt() {
		CardData diabolos = makeForward("Diabolos", "Ice", 4, 0);
		GameContext ctx = mock(GameContext.class);
		when(ctx.consumePreloadedTargets()).thenReturn(null);
		when(ctx.selfCardsCastThisTurn()).thenReturn(4);
		when(ctx.selectCharacters(anyInt(), anyBoolean(), anyBoolean(), anyBoolean(), any(), any(),
				anyInt(), any(), anyInt(), any(), anyBoolean(), anyBoolean(), anyBoolean(),
				any(), any(), any(), any(), anyBoolean(), any(), anyBoolean()))
				.thenReturn(List.of(new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD)));

		ActionResolver.parse(DIABOLOS_SUMMON, diabolos).accept(ctx);

		verify(ctx).setAllForwardsBasePower(3000);
	}

	@Test
	void theBoardWideSweepReachesBothSides() {
		MainWindow mw = new MainWindow();
		CardData mine   = makeForward("Mine", "Ice", 5, 9000);
		CardData theirs = makeForward("Theirs", "Fire", 5, 9000);
		placeP1Forward(mw, mine);
		placeP2Forward(mw, theirs);

		mw.buildGameContext(true).setAllForwardsBasePower(3000);

		assertEquals(3000, mw.effectiveP1ForwardPower(0), "his own side is not spared");
		assertEquals(3000, mw.effectiveP2ForwardPower(0));
	}

	// =========================================================================================
	// 15-076C Titan: "Before paying the cost to cast Titan, you can pay 《C》 to reduce the cost
	// required to cast Titan by 2. Select 1 of the 2 following actions. "Choose 1 Forward. It gains
	// +10000 power until the end of the turn." "Choose 1 Forward. Deal it 7000 damage.""
	// =========================================================================================

	private static final String TITAN_15_076C = "Before paying the cost to cast Titan, you can pay 《C》 to reduce the "
			+ "cost required to cast Titan by 2.[[br]]   Select 1 of the 2 following actions.[[br]]   \"Choose 1 "
			+ "Forward. It gains +10000 power until the end of the turn.\"[[br]]   \"Choose 1 Forward. Deal it 7000 "
			+ "damage.\"";

	@Test
	void titanGivesYourForward10000() {
		MainWindow mw = new MainWindow();
		placeP2Forward(mw, makeForward("Mine", "Earth", 3, 7000));
		placeP1Forward(mw, makeForward("Theirs", "Water", 3, 7000));
		castAsP2Selecting(mw, makeSummon("Titan", "Earth", 3, TITAN_15_076C), 0);
		assertEquals(17000, mw.effectiveP2ForwardPower(0));
		assertEquals(7000, mw.effectiveP1ForwardPower(0), "the CPU keeps a buff for itself");
	}

	@Test
	void titanDeals7000ToTheirForward() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 5, 9000);
		placeP1Forward(mw, theirs);
		castAsP2Selecting(mw, makeSummon("Titan", "Earth", 3, TITAN_15_076C), 1);
		assertEquals(7000, damageOn(mw, theirs));
	}

	@Test
	void titanCanPayACrystalForTwoLess() {
		assertCrystalReducesByTwo(makeSummon("Titan", "Earth", 3, TITAN_15_076C), "Earth");
	}

	// =========================================================================================
	// 15-082H Hecatoncheir: "You can only cast Hecatoncheir during your turn. Deal 8000 damage to all
	// Forwards."
	// =========================================================================================

	private static final String HECATONCHEIR_15_082H = "You can only cast Hecatoncheir during your turn.[[br]]   Deal "
			+ "8000 damage to all Forwards.";

	@Test
	void hecatoncheirDeals8000ToEveryForwardOnBothSides() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 4, 8000);
		CardData big    = makeForward("Big", "Water", 6, 10000);
		CardData mine   = makeForward("Mine", "Earth", 4, 7000);
		placeP1Forward(mw, theirs);
		placeP1Forward(mw, big);
		placeP2Forward(mw, mine);
		castAsP2(mw, makeSummonWithCostText("Hecatoncheir", "Earth", 5, HECATONCHEIR_15_082H));

		assertTrue(mw.gameState.getP1BreakZone().contains(theirs));
		assertTrue(mw.gameState.getP2BreakZone().contains(mine), "all Forwards, yours too");
		assertEquals(8000, damageOn(mw, big));
	}

	@Test
	void hecatoncheirCanOnlyBeCastDuringYourTurn() {
		assertCastOnlyDuringYourTurn(makeSummonWithCostText("Hecatoncheir", "Earth", 5, HECATONCHEIR_15_082H));
	}

	// =========================================================================================
	// 15-090H Odin: "Select 1 of the 2 following actions. "Choose 1 Forward of cost 2 or less. Break
	// it." "Choose 1 Forward of cost 4 or less. If you have received a point of damage this turn,
	// break it.""
	// =========================================================================================

	private static final String ODIN_15_090H = "Select 1 of the 2 following actions.[[br]]   \"Choose 1 Forward of "
			+ "cost 2 or less. Break it.\"[[br]]   \"Choose 1 Forward of cost 4 or less. If you have received a point "
			+ "of damage this turn, break it.\"";

	@Test
	void odinBreaksAForwardOfCostTwoOrLess() {
		MainWindow mw = new MainWindow();
		CardData two   = makeForward("Two", "Water", 2, 5000);
		CardData three = makeForward("Three", "Water", 3, 7000);
		placeP1Forward(mw, three);
		placeP1Forward(mw, two);
		castAsP2Selecting(mw, makeSummon("Odin", "Lightning", 2, ODIN_15_090H), 0);
		assertEquals(List.of(three), mw.p1ForwardCards);
	}

	@Test
	void odinsSecondActionBreaksOnlyOnceYouHaveTakenDamageThisTurn() {
		for (boolean damaged : new boolean[] { false, true }) {
			MainWindow mw = new MainWindow();
			CardData four = makeForward("Four", "Water", 4, 8000);
			placeP1Forward(mw, four);
			mw.turn(false).receivedDamageThisTurn = damaged;
			mw.turn(true).receivedDamageThisTurn  = true;
			castAsP2Selecting(mw, makeSummon("Odin", "Lightning", 2, ODIN_15_090H), 1);
			assertEquals(damaged, mw.gameState.getP1BreakZone().contains(four),
					damaged ? "you have received damage this turn" : "the opponent's damage is not yours");
		}
	}

	// =========================================================================================
	// 15-105C Remora: "Before paying the cost to cast Remora, you can pay 《C》 to reduce the cost
	// required to cast Remora by 2. Choose 1 Forward you control and 1 Forward opponent controls.
	// Until the end of the turn, the former gains +7000 power and the latter loses 7000 power."
	// =========================================================================================

	private static final String REMORA_15_105C = "Before paying the cost to cast Remora, you can pay 《C》 to reduce the "
			+ "cost required to cast Remora by 2.[[br]]   Choose 1 Forward you control and 1 Forward opponent controls. "
			+ "Until the end of the turn, the former gains +7000 power and the latter loses 7000 power.";

	@Test
	void remoraRaisesYoursAndLowersTheirs() {
		MainWindow mw = new MainWindow();
		placeP2Forward(mw, makeForward("Mine", "Lightning", 3, 5000));
		placeP1Forward(mw, makeForward("Theirs", "Water", 5, 9000));
		castAsP2(mw, makeSummon("Remora", "Lightning", 4, REMORA_15_105C));
		assertEquals(12000, mw.effectiveP2ForwardPower(0));
		assertEquals(2000, mw.effectiveP1ForwardPower(0));
	}

	@Test
	void remoraCanPayACrystalForTwoLess() {
		assertCrystalReducesByTwo(makeSummon("Remora", "Lightning", 4, REMORA_15_105C), "Lightning");
	}

	// =========================================================================================
	// 15-106C Atomos: "Before paying the cost to cast Atomos, you can pay 《C》 to reduce the cost
	// required to cast Atomos by 2. Your opponent selects 1 Forward they control. Put it into the
	// Break Zone."
	// =========================================================================================

	private static final String ATOMOS_15_106C = "Before paying the cost to cast Atomos, you can pay 《C》 to reduce the "
			+ "cost required to cast Atomos by 2.[[br]]   Your opponent selects 1 Forward they control. Put it into "
			+ "the Break Zone.";

	@Test
	void atomosMakesTheOpponentPutOneOfTheirForwardsIntoTheBreakZone() {
		// P1 casts, so the CPU does the selecting.
		MainWindow mw = new MainWindow();
		placeP2Forward(mw, makeForward("Theirs A", "Water", 3, 7000));
		placeP2Forward(mw, makeForward("Theirs B", "Water", 5, 9000));
		CardData mine = makeForward("Mine", "Water", 3, 7000);
		placeP1Forward(mw, mine);
		castAsP1(mw, makeSummon("Atomos", "Water", 3, ATOMOS_15_106C));

		assertEquals(1, mw.p2ForwardCards.size());
		assertEquals(1, mw.gameState.getP2BreakZone().size());
		assertEquals(List.of(mine), mw.p1ForwardCards, "their Forward, not yours");
	}

	@Test
	void atomosCanPayACrystalForTwoLess() {
		assertCrystalReducesByTwo(makeSummon("Atomos", "Water", 3, ATOMOS_15_106C), "Water");
	}

	// =========================================================================================
	// 16-016C Bahamut: "Choose 1 Forward. Deal it 9000 damage. If the cost to cast Bahamut was paid
	// with CP of 3 or more different Elements, deal it 12000 damage instead."
	//
	// Opus 16's "paid with CP of N or more/less different Elements" reads the caster's payment
	// record; this board and the ones after it set that record directly.
	// =========================================================================================

	private static final String BAHAMUT_16_016C = "Choose 1 Forward. Deal it 9000 damage. If the cost to cast Bahamut "
			+ "was paid with CP of 3 or more different Elements, deal it 12000 damage instead.";

	@Test
	void bahamutDeals9000OrTwelveThousandForThreeElements() {
		for (int elements : new int[] { 2, 3 }) {
			MainWindow mw = new MainWindow();
			CardData theirs = makeForward("Theirs", "Water", 7, 13000);
			placeP1Forward(mw, theirs);
			mw.lastCastPaymentDistinctElements = elements;
			castAsP2(mw, makeSummon("Bahamut", "Fire", 4, BAHAMUT_16_016C));
			assertEquals(elements >= 3 ? 12000 : 9000, damageOn(mw, theirs), elements + " Elements");
		}
	}

	// =========================================================================================
	// 16-028C Shiva: "Choose 1 dull Forward. Break it. If the cost to cast Shiva was paid with CP of 3
	// or more different Elements, your opponent discards 1 card from their hand."
	// =========================================================================================

	private static final String SHIVA_16_028C = "Choose 1 dull Forward. Break it. If the cost to cast Shiva was paid "
			+ "with CP of 3 or more different Elements, your opponent discards 1 card from their hand.";

	@Test
	void shivaBreaksADullForwardAndMakesThemDiscardForThreeElements() {
		for (int elements : new int[] { 2, 3 }) {
			MainWindow mw = new MainWindow();
			CardData active = makeForward("Active", "Water", 5, 9000);
			CardData dull   = makeForward("Dull", "Water", 5, 9000);
			placeP1Forward(mw, active);
			placeP1Forward(mw, dull);
			dullP1Forward(mw, dull);
			inP1Hand(mw, 1);
			mw.lastCastPaymentDistinctElements = elements;
			castAsP2(mw, makeSummon("Shiva", "Ice", 4, SHIVA_16_028C));

			assertEquals(List.of(active), mw.p1ForwardCards, "only a dull Forward");
			assertEquals(elements >= 3 ? 0 : 1, mw.gameState.getP1Hand().size(), elements + " Elements");
		}
	}

	// =========================================================================================
	// 16-046C Chocobo Chick (VII): "Activate all the Backups you control. Draw 1 card. If the cost to
	// cast Chocobo Chick (VII) was paid with CP of 3 or more different Elements, all the Forwards you
	// control also gain +2000 power until the end of the turn."
	// =========================================================================================

	private static final String CHOCOBO_CHICK_16_046C = "Activate all the Backups you control. Draw 1 card. If the cost "
			+ "to cast Chocobo Chick (VII) was paid with CP of 3 or more different Elements, all the Forwards you "
			+ "control also gain +2000 power until the end of the turn.";

	@Test
	void chocoboChickActivatesYourBackupsDrawsAndBoostsForThreeElements() {
		for (int elements : new int[] { 2, 3 }) {
			MainWindow mw = new MainWindow();
			fillP2Deck(mw, 2);
			placeP2Backup(mw, makeBackup("My Backup", "Wind", 2));
			mw.p2BackupStates[0] = CardState.DULL;
			placeP2Forward(mw, makeForward("Mine", "Wind", 3, 7000));
			placeP1Forward(mw, makeForward("Theirs", "Water", 3, 7000));
			mw.lastCastPaymentDistinctElements = elements;
			castAsP2(mw, makeSummon("Chocobo Chick (VII)", "Wind", 4, CHOCOBO_CHICK_16_046C));

			assertEquals(CardState.ACTIVE, mw.p2BackupStates[0]);
			assertEquals(1, mw.gameState.getP2Hand().size());
			assertEquals(elements >= 3 ? 9000 : 7000, mw.effectiveP2ForwardPower(0), elements + " Elements");
			assertEquals(7000, mw.effectiveP1ForwardPower(0), "yours only");
		}
	}

	// =========================================================================================
	// 16-074C Cactuar: "Choose 1 Forward. Break it. If the cost to cast Cactuar was paid with CP of 2
	// or less different Elements, Cactuar deals you 1 point of damage."
	// =========================================================================================

	private static final String CACTUAR_16_074C = "Choose 1 Forward. Break it. If the cost to cast Cactuar was paid "
			+ "with CP of 2 or less different Elements, Cactuar deals you 1 point of damage.";

	@Test
	void cactuarBreaksAndDamagesYouForTwoOrFewerElements() {
		for (int elements : new int[] { 2, 3 }) {
			MainWindow mw = new MainWindow();
			fillP2Deck(mw, 3);
			CardData theirs = makeForward("Theirs", "Water", 5, 9000);
			placeP1Forward(mw, theirs);
			mw.lastCastPaymentDistinctElements = elements;
			castAsP2(mw, makeSummon("Cactuar", "Earth", 4, CACTUAR_16_074C));

			assertTrue(mw.gameState.getP1BreakZone().contains(theirs));
			assertEquals(elements <= 2 ? 1 : 0, mw.gameState.getP2DamageZone().size(), elements + " Elements");
			assertTrue(mw.gameState.getP1DamageZone().isEmpty(), "\"you\" is the caster");
		}
	}

	// =========================================================================================
	// 16-086C Ixion: "Choose 1 Forward. It loses 8000 power until the end of the turn. If the cost to
	// cast Ixion was paid with CP of 3 or more different Elements, all the Forwards opponent controls
	// also lose 2000 power until the end of the turn."
	// =========================================================================================

	private static final String IXION_16_086C = "Choose 1 Forward. It loses 8000 power until the end of the turn. If "
			+ "the cost to cast Ixion was paid with CP of 3 or more different Elements, all the Forwards opponent "
			+ "controls also lose 2000 power until the end of the turn.";

	@Test
	void ixionShrinksOneForwardAndAllOfTheirsForThreeElements() {
		for (int elements : new int[] { 2, 3 }) {
			MainWindow mw = new MainWindow();
			placeP1Forward(mw, makeForward("Big", "Water", 6, 12000));
			placeP1Forward(mw, makeForward("Other", "Water", 7, 12000));
			placeP2Forward(mw, makeForward("Mine", "Lightning", 3, 7000));
			mw.lastCastPaymentDistinctElements = elements;
			castAsP2(mw, makeSummon("Ixion", "Lightning", 4, IXION_16_086C));

			int extra = elements >= 3 ? 2000 : 0;
			List<Integer> powers = List.of(mw.effectiveP1ForwardPower(0), mw.effectiveP1ForwardPower(1));
			assertTrue(powers.contains(4000 - extra) && powers.contains(12000 - extra), elements + " Elements: " + powers);
			assertEquals(7000, mw.effectiveP2ForwardPower(0), "yours are spared");
		}
	}

	// =========================================================================================
	// 16-125C Leviathan: "Choose 1 Forward of cost 5 or less. Put it at the top or bottom of its
	// owner's deck. If the cost to cast Leviathan was paid with CP of 3 or more different Elements,
	// also draw 1 card, then discard 1 card from your hand."
	// =========================================================================================

	private static final String LEVIATHAN_16_125C = "Choose 1 Forward of cost 5 or less. Put it at the top or bottom of "
			+ "its owner's deck. If the cost to cast Leviathan was paid with CP of 3 or more different Elements, also "
			+ "draw 1 card, then discard 1 card from your hand.";

	@Test
	void leviathanPutsACheapForwardIntoItsOwnersDeck() {
		MainWindow mw = new MainWindow();
		CardData five = makeForward("Five", "Water", 5, 9000);
		CardData six  = makeForward("Six", "Water", 6, 9000);
		placeP1Forward(mw, six);
		placeP1Forward(mw, five);
		mw.lastCastPaymentDistinctElements = 2;
		castAsP2(mw, makeSummon("Leviathan", "Water", 4, LEVIATHAN_16_125C));
		assertEquals(List.of(six), mw.p1ForwardCards, "cost 6 is over 5");
		assertTrue(mw.gameState.getP1MainDeck().contains(five));
	}

	@Test
	void leviathanDrawsThenDiscardsForThreeElements() {
		MainWindow mw = new MainWindow();
		fillP2Deck(mw, 2);
		mw.gameState.getP2Hand().add(makeForward("Held", "Water", 1, 1000));
		placeP1Forward(mw, makeForward("Theirs", "Water", 3, 7000));
		mw.lastCastPaymentDistinctElements = 3;
		castAsP2(mw, makeSummon("Leviathan", "Water", 4, LEVIATHAN_16_125C));
		assertEquals(1, mw.gameState.getP2Hand().size(), "one drawn, one discarded");
		assertEquals(1, mw.gameState.getP2BreakZone().size());
	}

	// =========================================================================================
	// 16-132S Bahamut: "Before paying the cost to cast Bahamut, you can remove 10 Fire Characters
	// and/or Category X Characters in your Break Zone from the game to reduce the cost required to
	// cast Bahamut by 5. Choose 1 Forward. Deal it 10000 damage. If it is put from the field into
	// the Break Zone this turn, remove it from the game instead." The Break Zone reduction is
	// CardBehaviorTest's (everyBreakZoneReductionReadsWhole).
	// =========================================================================================

	private static final String BAHAMUT_16_132S = "Before paying the cost to cast Bahamut, you can remove 10 Fire "
			+ "Characters and/or Category X Characters in your Break Zone from the game to reduce the cost required "
			+ "to cast Bahamut by 5.[[br]]   Choose 1 Forward. Deal it 10000 damage. If it is put from the field into "
			+ "the Break Zone this turn, remove it from the game instead.";

	@Test
	void bahamutRemovesFromTheGameTheForwardIts10000Breaks() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 6, 10000);
		placeP1Forward(mw, theirs);
		castAsP2(mw, makeSummon("Bahamut", "Fire", 6, BAHAMUT_16_132S));
		assertTrue(mw.gameState.getP1PermanentRfp().contains(theirs));
		assertFalse(mw.gameState.getP1BreakZone().contains(theirs));
	}

	// =========================================================================================
	// 17-014R Bahamut: "Choose up to 2 Forwards. Divide 10000 damage among them as you like. If
	// you have received 5 points of damage or more, divide 15000 damage among those instead."
	//
	// The divide parser has read the alternate amount all along; only the report could not say so,
	// because the description split the followup on ". " and left the condition in the tail. These
	// pin both halves: the amount actually dealt, and the name that now covers the whole clause.
	// =========================================================================================

	private static final String BAHAMUT_SUMMON =
			"Choose up to 2 Forwards. Divide 10000 damage among them as you like. "
			+ "If you have received 5 points of damage or more, divide 15000 damage among those "
			+ "instead. (Units must be 1000.)";

	/** One target, so the allocation dialog is skipped and the whole amount lands on it. */
	private static GameContext bahamutContext(ForwardTarget victim, int damageTaken) {
		GameContext ctx = mock(GameContext.class);
		when(ctx.consumePreloadedTargets()).thenReturn(null);
		when(ctx.selfDamageCount()).thenReturn(damageTaken);
		when(ctx.selectCharacters(anyInt(), anyBoolean(), anyBoolean(), anyBoolean(), any(), any(),
				anyInt(), any(), anyInt(), any(), anyBoolean(), anyBoolean(), anyBoolean(),
				any(), any(), any(), any(), anyBoolean(), any(), anyBoolean()))
				.thenReturn(List.of(victim));
		return ctx;
	}

	@Test
	void bahamutDividesTenThousandUntilYouHaveTakenFivePoints() {
		ForwardTarget victim = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);
		GameContext ctx = bahamutContext(victim, 4);

		ActionResolver.parse(BAHAMUT_SUMMON, null).accept(ctx);

		verify(ctx).damageTarget(victim, 10000);
	}

	@Test
	void bahamutDividesFifteenThousandOnceYouHave() {
		ForwardTarget victim = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);
		GameContext ctx = bahamutContext(victim, 5);

		ActionResolver.parse(BAHAMUT_SUMMON, null).accept(ctx);

		verify(ctx).damageTarget(victim, 15000);
	}

	@Test
	void bahamutsAlternateAmountIsNoLongerAnUnreadTail() {
		assertEquals("ChooseCharacter / DivideDamageInstead",
				ActionResolver.fullDescription(BAHAMUT_SUMMON, null));
	}

	// =========================================================================================
	// 17-027R Shiva, first option: "Select 1 of the 2 following actions. "Choose 1 dull Forward of
	// cost 2 or less. Break it. Your opponent discards 1 card." "Choose 1 Forward. If it deals damage
	// other than battle damage to a Forward this turn, the damage becomes 0 instead.""
	// =========================================================================================

	private static final String SHIVA_17_027R = "Select 1 of the 2 following actions.[[br]]   \"Choose 1 dull Forward "
			+ "of cost 2 or less. Break it. Your opponent discards 1 card.\"[[br]]   \"Choose 1 Forward. If it deals "
			+ "damage other than battle damage to a Forward this turn, the damage becomes 0 instead.\"";

	@Test
	void shivaBreaksACheapDullForwardAndTheOpponentDiscards() {
		MainWindow mw = new MainWindow();
		CardData cheapDull   = makeForward("Cheap Dull", "Water", 2, 5000);
		CardData cheapActive = makeForward("Cheap Active", "Water", 2, 5000);
		CardData dearDull    = makeForward("Dear Dull", "Water", 3, 7000);
		placeP1Forward(mw, cheapActive);
		placeP1Forward(mw, dearDull);
		placeP1Forward(mw, cheapDull);
		dullP1Forward(mw, dearDull);
		dullP1Forward(mw, cheapDull);
		inP1Hand(mw, 1);
		castAsP2Selecting(mw, makeSummon("Shiva", "Ice", 2, SHIVA_17_027R), 0);

		assertEquals(List.of(cheapActive, dearDull), mw.p1ForwardCards, "dull, and of cost 2 or less");
		assertTrue(mw.gameState.getP1Hand().isEmpty());
	}

	// =========================================================================================
	// 17-027R Shiva, second option: "Choose 1 Forward. If it deals damage other than battle damage
	// to a Forward this turn, the damage becomes 0 instead."
	//
	// The narrow member of the outgoing-shield family. 23-024R Shiva stops everything its target
	// deals; this one stops only what it deals to a Forward, and only outside combat — so the
	// chosen Forward still attacks, still blocks, and still hurts the player normally.
	// =========================================================================================

	private static final String SHIVA_NON_BATTLE_OPTION =
			"Choose 1 Forward. If it deals damage other than battle damage to a Forward this turn, "
			+ "the damage becomes 0 instead.";

	/** Runs the option on the single P2 Forward and hands back the board it left. */
	private static MainWindow shivaNonBattleOn(CardData victim) {
		MainWindow mw = new MainWindow();
		placeP2Forward(mw, victim);

		GameContext ctx = mw.buildGameContext(true);
		ctx.preloadTargets(List.of(fwd(false, 0)));
		Consumer<GameContext> effect = ActionResolver.parse(SHIVA_NON_BATTLE_OPTION, null);
		assertNotNull(effect, "the printed wording has to parse for the rest of this to mean anything");
		effect.accept(ctx);
		return mw;
	}

	@Test
	void shivaBlanksTheAbilityDamageItsTargetDealsToAForward() {
		CardData victim = makeForward("Victim", "Ice", 3, 7000);
		MainWindow mw = shivaNonBattleOn(victim);
		placeP1Forward(mw, makeForward("Ally", "Fire", 3, 9000));

		GameContext ctx = mw.buildGameContext(false);
		mw.currentAbilitySource = victim;
		try {
			ctx.damageP1Forward(0, 5000);
		} finally {
			mw.currentAbilitySource = null;
		}

		assertEquals(0, mw.p1ForwardDamage.get(0), "non-battle damage to a Forward becomes 0");
	}

	@Test
	void butLeavesItsCombatDamageAlone() {
		// "other than battle damage" is the whole difference from 23-024R Shiva, which stops that too.
		CardData victim = makeForward("Victim", "Ice", 3, 7000);
		MainWindow mw = shivaNonBattleOn(victim);
		CardData blocker = makeForward("Blocker", "Fire", 3, 9000);
		placeP1Forward(mw, blocker);

		assertEquals(7000, mw.modifyOutgoingCombatDamage(false, 0, 7000, blocker));
	}

	@Test
	void andLeavesItsDamageToThePlayerAlone() {
		// "to a Forward" is the other half of the difference.
		CardData victim = makeForward("Victim", "Ice", 3, 7000);
		MainWindow mw = shivaNonBattleOn(victim);

		assertEquals(1, mw.combatDamagePointsToOpponent(victim));
	}

	@Test
	void shivasNarrowShieldIsNamedApartFromTheWideOne() {
		assertEquals("ChooseCharacter / ZeroOutgoingAbilityDamageToForwards",
				ActionResolver.fullDescription(SHIVA_NON_BATTLE_OPTION, null));
	}

	// =========================================================================================
	// 17-053R Chocobo: "EX BURST Reveal the top 3 cards of your deck. Add 1 Character among them to
	// your hand and return the other cards to the bottom of your deck in any order."
	// =========================================================================================

	private static final String CHOCOBO_17_053R = "[[ex]]EX BURST[[/]] Reveal the top 3 cards of your deck. Add 1 "
			+ "Character among them to your hand and return the other cards to the bottom of your deck in any order.";

	@Test
	void chocoboAddsTheCharacterAmongTheTopThreeAndBottomsTheRest() {
		MainWindow mw = new MainWindow();
		CardData summonA = makeSummon("Summon A", "Fire", 2, "Draw 1 card.");
		CardData forward = makeForward("Forward", "Wind", 3, 7000);
		CardData summonB = makeSummon("Summon B", "Ice", 2, "Draw 1 card.");
		CardData fourth  = makeForward("Fourth", "Wind", 3, 7000);
		for (CardData c : List.of(summonA, forward, summonB, fourth)) mw.gameState.getP2MainDeck().add(c);
		castAsP2(mw, makeSummon("Chocobo", "Wind", 1, CHOCOBO_17_053R));

		assertEquals(List.of(forward), mw.gameState.getP2Hand(), "a Character, not a Summon");
		assertSame(fourth, mw.gameState.getP2MainDeck().peekFirst(), "the rest went to the bottom");
		assertEquals(Set.of(summonA, summonB), Set.copyOf(List.copyOf(mw.gameState.getP2MainDeck()).subList(1, 3)));
	}

	// =========================================================================================
	// 17-070R Titan: "Select 1 of the 2 following actions. "Choose 1 Forward of cost 5 or more. Deal it
	// 9000 damage." "All the Forwards you control gain +5000 power until the end of the turn.""
	// =========================================================================================

	private static final String TITAN_17_070R = "Select 1 of the 2 following actions.[[br]]   \"Choose 1 Forward of "
			+ "cost 5 or more. Deal it 9000 damage.\"[[br]]   \"All the Forwards you control gain +5000 power until the "
			+ "end of the turn.\"";

	@Test
	void titanDeals9000ToAForwardOfCostFiveOrMore() {
		MainWindow mw = new MainWindow();
		CardData four = makeForward("Four", "Water", 4, 10000);
		CardData five = makeForward("Five", "Water", 5, 10000);
		placeP1Forward(mw, four);
		placeP1Forward(mw, five);
		castAsP2Selecting(mw, makeSummon("Titan", "Earth", 3, TITAN_17_070R), 0);
		assertEquals(9000, damageOn(mw, five));
		assertEquals(0, damageOn(mw, four), "cost 4 is under 5");
	}

	@Test
	void titanGivesAllYourForwards5000() {
		MainWindow mw = new MainWindow();
		placeP2Forward(mw, makeForward("Left", "Earth", 3, 7000));
		placeP2Forward(mw, makeForward("Right", "Earth", 3, 7000));
		placeP1Forward(mw, makeForward("Theirs", "Water", 3, 7000));
		castAsP2Selecting(mw, makeSummon("Titan", "Earth", 3, TITAN_17_070R), 1);
		assertEquals(12000, mw.effectiveP2ForwardPower(0));
		assertEquals(12000, mw.effectiveP2ForwardPower(1));
		assertEquals(7000, mw.effectiveP1ForwardPower(0), "yours only");
	}

	/**
	 * Opus 17–18's "If you cast [Self], you may pay/remove … as an extra cost": the Stack entry
	 * settles whether it was paid, and resolution parses the text rewritten to match — see
	 * 18-045C Dryad below.
	 */
	private static void castAsP2Paying(MainWindow mw, CardData summon, boolean paid) {
		String text = paid ? ActionResolver.applyExtraCostPaid(summon.summonEffect())
		                   : ActionResolver.stripExtraCostClause(summon.summonEffect());
		Consumer<GameContext> fn = ActionResolver.parse(text, summon);
		assertNotNull(fn, summon.name() + (paid ? " (paid)" : " (unpaid)") + " parses");
		fn.accept(mw.buildGameContext(false));
	}

	// =========================================================================================
	// 17-090R Ixion: "If you cast Ixion, you may pay an extra 《Lightning》《2》. Break all the Forwards
	// of cost 2 or less. If you paid the extra cost, break all the Forwards of cost 3 or less
	// instead."
	// =========================================================================================

	private static final String IXION_17_090R = "If you cast Ixion, you may pay an extra 《Lightning》《2》.[[br]]   "
			+ "Break all the Forwards of cost 2 or less. If you paid the extra cost, break all the Forwards of cost 3 "
			+ "or less instead.";

	@Test
	void ixionBreaksEveryForwardOfCostTwoOrThreeWhenPaid() {
		for (boolean paid : new boolean[] { false, true }) {
			MainWindow mw = new MainWindow();
			CardData theirTwo   = makeForward("Their Two", "Water", 2, 5000);
			CardData theirThree = makeForward("Their Three", "Water", 3, 7000);
			CardData theirFour  = makeForward("Their Four", "Water", 4, 8000);
			CardData myTwo      = makeForward("My Two", "Lightning", 2, 5000);
			placeP1Forward(mw, theirTwo);
			placeP1Forward(mw, theirThree);
			placeP1Forward(mw, theirFour);
			placeP2Forward(mw, myTwo);
			castAsP2Paying(mw, makeSummon("Ixion", "Lightning", 2, IXION_17_090R), paid);

			assertTrue(mw.gameState.getP2BreakZone().contains(myTwo), "all the Forwards, yours too");
			assertEquals(paid ? List.of(theirFour) : List.of(theirThree, theirFour), mw.p1ForwardCards,
					paid ? "paid: cost 3 or less" : "unpaid: cost 2 or less");
		}
	}

	// =========================================================================================
	// 17-109R Cúchulainn: "EX BURST Choose 1 Forward. At the beginning of the next Main Phase 1, put it
	// into the Break Zone."
	// =========================================================================================

	private static final String CUCHULAINN_17_109R = "[[ex]]EX BURST[[/]] Choose 1 Forward. At the beginning of the "
			+ "next Main Phase 1, put it into the Break Zone.";

	@Test
	void cuchulainnPutsTheChosenForwardIntoTheBreakZoneAtTheNextMainPhase1() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 5, 9000);
		placeP1Forward(mw, theirs);
		castAsP2(mw, makeSummon("Cúchulainn", "Water", 4, CUCHULAINN_17_109R));
		assertEquals(List.of(theirs), mw.p1ForwardCards, "not yet");

		mw.firePendingMainPhase1(true);
		assertTrue(mw.gameState.getP1BreakZone().contains(theirs), "whichever player's Main Phase 1 comes first");
	}

	@Test
	void cuchulainnLeavesTheSlotAloneOnceThatForwardHasGone() {
		// By identity: a Forward that has left and another now in its slot are not "it".
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 5, 9000);
		placeP1Forward(mw, theirs);
		castAsP2(mw, makeSummon("Cúchulainn", "Water", 4, CUCHULAINN_17_109R));
		mw.buildGameContext(true).forceTargetToBreakZone(new ForwardTarget(true, 0, ForwardTarget.CardZone.FORWARD));
		CardData replacement = makeForward("Replacement", "Water", 5, 9000);
		placeP1Forward(mw, replacement);

		mw.firePendingMainPhase1(true);
		assertEquals(List.of(replacement), mw.p1ForwardCards);
		assertEquals(List.of(theirs), mw.gameState.getP1BreakZone(), "and nothing else went with it");
	}

	// =========================================================================================
	// 18-005C Salamander: "If you cast Salamander, you may remove 10 Fire cards in your Break Zone from
	// the game as an extra cost. Deal 5000 damage to all the Forwards opponent controls. If you paid
	// the extra cost, deal 7000 damage to all the Forwards opponent controls instead."
	// =========================================================================================

	private static final String SALAMANDER_18_005C = "If you cast Salamander, you may remove 10 Fire cards in your "
			+ "Break Zone from the game as an extra cost.[[br]]   Deal 5000 damage to all the Forwards opponent "
			+ "controls. If you paid the extra cost, deal 7000 damage to all the Forwards opponent controls instead.";

	@Test
	void salamanderDeals5000ToAllTheirForwardsOr7000WhenPaid() {
		for (boolean paid : new boolean[] { false, true }) {
			MainWindow mw = new MainWindow();
			CardData theirs = makeForward("Theirs", "Water", 5, 9000);
			CardData mine   = makeForward("Mine", "Fire", 5, 9000);
			placeP1Forward(mw, theirs);
			placeP2Forward(mw, mine);
			castAsP2Paying(mw, makeSummon("Salamander", "Fire", 3, SALAMANDER_18_005C), paid);

			assertEquals(paid ? 7000 : 5000, damageOn(mw, theirs), paid ? "paid" : "unpaid");
			assertEquals(0, (int) mw.p2ForwardDamage.get(0), "opponent's only");
		}
	}

	// =========================================================================================
	// 18-024C Shiva: "If you cast Shiva, you may remove 10 Ice cards in your Break Zone from the game
	// as an extra cost. Choose 1 Forward. Dull it and Freeze it. If you paid the extra cost, break it
	// instead."
	// =========================================================================================

	private static final String SHIVA_18_024C = "If you cast Shiva, you may remove 10 Ice cards in your Break Zone "
			+ "from the game as an extra cost.[[br]]   Choose 1 Forward. Dull it and Freeze it. If you paid the extra "
			+ "cost, break it instead.";

	@Test
	void shivaDullsAndFreezesUnpaid() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 5, 9000);
		placeP1Forward(mw, theirs);
		castAsP2Paying(mw, makeSummon("Shiva", "Ice", 1, SHIVA_18_024C), false);
		assertEquals(CardState.DULL, mw.p1ForwardStates.get(0));
		assertTrue(mw.p1ForwardFrozen.get(0));
	}

	@Test
	void shivaBreaksInsteadWhenPaid() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 5, 9000);
		placeP1Forward(mw, theirs);
		castAsP2Paying(mw, makeSummon("Shiva", "Ice", 1, SHIVA_18_024C), true);
		assertTrue(mw.gameState.getP1BreakZone().contains(theirs));
	}

	// =========================================================================================
	// 18-045C Dryad: "If you cast Dryad, you may remove 10 Wind cards in your Break Zone from the
	// game as an extra cost. … If you paid the extra cost, also draw 1 card."
	//
	// The conditional clause is settled before the effect is parsed, not while it resolves: the
	// Stack entry records whether the cost was paid, and resolution rewrites the text to match.
	// Parsing the printed text instead would leave the clause for a find() matcher to claim and
	// draw on every cast — which is why the raw wording deliberately does not resolve it.
	// =========================================================================================

	private static final String DRYAD_SUMMON =
			"Choose 1 Forward. Deal it 2000 damage for each Wind Character you control. "
			+ "If you paid the extra cost, also draw 1 card.";

	@Test
	void dryadDrawsOnlyWhenTheExtraCostWasPaid() {
		CardData dryad = makeForward("Dryad", "Wind", 3, 0);

		String paid = ActionResolver.applyExtraCostPaid(DRYAD_SUMMON);
		assertEquals("ChooseCharacter / DamageForEach + DrawCards",
				ActionResolver.fullDescription(paid, dryad));

		String notPaid = ActionResolver.stripExtraCostClause(DRYAD_SUMMON);
		assertEquals("ChooseCharacter / DamageForEach",
				ActionResolver.fullDescription(notPaid, dryad));
	}

	@Test
	void andTheDamageIsDealtEitherWay() {
		CardData dryad = makeForward("Dryad", "Wind", 3, 0);
		ForwardTarget victim = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);

		for (String text : List.of(ActionResolver.applyExtraCostPaid(DRYAD_SUMMON),
				ActionResolver.stripExtraCostClause(DRYAD_SUMMON))) {
			GameContext ctx = mock(GameContext.class);
			when(ctx.consumePreloadedTargets()).thenReturn(null);
			when(ctx.countSelfFieldCards(anyBoolean(), anyBoolean(), anyBoolean(),
					any(), any(), any(), any(), anyInt())).thenReturn(3);
			when(ctx.selectCharacters(anyInt(), anyBoolean(), anyBoolean(), anyBoolean(), any(), any(),
					anyInt(), any(), anyInt(), any(), anyBoolean(), anyBoolean(), anyBoolean(),
					any(), any(), any(), any(), anyBoolean(), any(), anyBoolean()))
					.thenReturn(List.of(victim));

			Consumer<GameContext> effect = ActionResolver.parse(text, dryad);
			assertNotNull(effect, "both branches have to parse: " + text);
			effect.accept(ctx);

			verify(ctx).damageTarget(victim, 6000);   // 2000 x 3 Wind Characters
		}
	}

	@Test
	void andTheRawWordingDoesNotResolveTheClauseItself() {
		// The guard that keeps the draw honest. Resolution always hands parse() one of the two
		// rewritten texts; if the printed wording resolved the clause on its own, a cast that paid
		// nothing would still draw.
		CardData dryad = makeForward("Dryad", "Wind", 3, 0);
		assertEquals("ChooseCharacter / DamageForEach + ?",
				ActionResolver.fullDescription(DRYAD_SUMMON, dryad),
				"the clause is left unread on purpose — the Stack entry settles it");
	}

	// =========================================================================================
	// 18-063C Hashmal: "If you cast Hashmal, you may remove 10 Earth cards in your Break Zone from the
	// game as an extra cost. Choose 1 Forward you control. It gains +7000 power until the end of the
	// turn. If you paid the extra cost, all the Forwards you control gain +7000 power until the end
	// of the turn instead."
	// =========================================================================================

	private static final String HASHMAL_18_063C = "If you cast Hashmal, you may remove 10 Earth cards in your Break "
			+ "Zone from the game as an extra cost.[[br]]   Choose 1 Forward you control. It gains +7000 power until "
			+ "the end of the turn. If you paid the extra cost, all the Forwards you control gain +7000 power until "
			+ "the end of the turn instead.";

	@Test
	void hashmalBoostsOneOfYourForwardsOrAllOfThemWhenPaid() {
		for (boolean paid : new boolean[] { false, true }) {
			MainWindow mw = new MainWindow();
			placeP2Forward(mw, makeForward("Left", "Earth", 3, 7000));
			placeP2Forward(mw, makeForward("Right", "Earth", 3, 7000));
			placeP1Forward(mw, makeForward("Theirs", "Water", 3, 7000));
			castAsP2Paying(mw, makeSummon("Hashmal", "Earth", 1, HASHMAL_18_063C), paid);

			int boosted = (mw.effectiveP2ForwardPower(0) == 14000 ? 1 : 0) + (mw.effectiveP2ForwardPower(1) == 14000 ? 1 : 0);
			assertEquals(paid ? 2 : 1, boosted, paid ? "paid: all of them" : "unpaid: one");
			assertEquals(7000, mw.effectiveP1ForwardPower(0), "yours only");
		}
	}

	// =========================================================================================
	// 18-084C Ramuh: "If you cast Ramuh, you may remove 10 Lightning cards in your Break Zone from the
	// game as an extra cost. Choose 1 Forward. Deal it 7000 damage. If you paid the extra cost, deal
	// it 15000 damage instead."
	// =========================================================================================

	private static final String RAMUH_18_084C = "If you cast Ramuh, you may remove 10 Lightning cards in your Break "
			+ "Zone from the game as an extra cost.[[br]]   Choose 1 Forward. Deal it 7000 damage. If you paid the "
			+ "extra cost, deal it 15000 damage instead.";

	@Test
	void ramuhDeals7000Or15000WhenPaid() {
		for (boolean paid : new boolean[] { false, true }) {
			MainWindow mw = new MainWindow();
			CardData theirs = makeForward("Theirs", "Water", 8, 16000);
			placeP1Forward(mw, theirs);
			castAsP2Paying(mw, makeSummon("Ramuh", "Lightning", 2, RAMUH_18_084C), paid);
			assertEquals(paid ? 15000 : 7000, damageOn(mw, theirs), paid ? "paid" : "unpaid");
		}
	}

	// =========================================================================================
	// 18-096C Leviathan: "Choose 1 auto-ability triggered from a Forward. Cancel its effect. If you
	// paid the extra cost and that Forward is on the field, return that Forward to its owner's
	// hand." The paid branch used to resolve as the return alone, looking for a card called "that
	// Forward" — so paying the extra cost bought nothing and cost the cancel.
	// =========================================================================================

	private static final String LEVIATHAN_18_096C =
			"Choose 1 auto-ability triggered from a Forward. Cancel its effect. If you paid the extra "
			+ "cost and that Forward is on the field, return that Forward to its owner's hand.";

	@Test
	void leviathanPaidCancelsAndReturnsTheForwardTheAbilityCameFrom() {
		CardData from = makeForward("Trigger Source", "Fire", 4, 7000);
		GameContext ctx = mock(GameContext.class);
		when(ctx.isP1()).thenReturn(true);
		when(ctx.cancelFilteredAbilityOnStack(any(), any(), eq(false))).thenReturn(autoEntryFrom(from, false));

		ActionResolver.parse(ActionResolver.applyExtraCostPaid(LEVIATHAN_18_096C)).accept(ctx);

		verify(ctx).cancelFilteredAbilityOnStack(any(), any(), eq(false));
		verify(ctx).returnCardToOwnersHandIfOnField(from);
	}

	@Test
	void leviathanPaidReturnsNothingWhenNothingWasCancelled() {
		GameContext ctx = mock(GameContext.class);
		when(ctx.isP1()).thenReturn(true);
		when(ctx.cancelFilteredAbilityOnStack(any(), any(), eq(false))).thenReturn(null);

		ActionResolver.parse(ActionResolver.applyExtraCostPaid(LEVIATHAN_18_096C)).accept(ctx);

		verify(ctx, never()).returnCardToOwnersHandIfOnField(any());
	}

	@Test
	void leviathanUnpaidOnlyCancels() {
		CardData from = makeForward("Trigger Source", "Fire", 4, 7000);
		GameContext ctx = mock(GameContext.class);
		when(ctx.isP1()).thenReturn(true);
		when(ctx.cancelFilteredAbilityOnStack(any(), any(), eq(false))).thenReturn(autoEntryFrom(from, false));

		ActionResolver.parse(ActionResolver.stripExtraCostClause(LEVIATHAN_18_096C)).accept(ctx);

		verify(ctx).cancelFilteredAbilityOnStack(any(), any(), eq(false));
		verify(ctx, never()).returnCardToOwnersHandIfOnField(any());
		verify(ctx, never()).returnNamedCardToOwnersHand(any());
	}

	@Test
	void leviathanPrintedTextIsNotReadAsAReturnOfACardCalledThatForward() {
		// The runtime only ever parses the text after the extra-cost rewrite. Read as printed, the
		// payoff's condition is unknowable, so the whole ability declines rather than returning.
		assertNull(ActionResolver.parse(LEVIATHAN_18_096C));
	}

	@Test
	void theReturnedForwardIsTheCardItselfNotAnotherCopy() {
		// Identity, because CardData is a record: two copies of one printing are equal(), and the
		// copy still on the field is not "that Forward" once the one the ability came from is gone.
		MainWindow mw = new MainWindow();
		CardData onField = makeForward("Twin", "Fire", 3, 7000);
		CardData gone    = makeForward("Twin", "Fire", 3, 7000);
		placeP2Forward(mw, onField);
		GameContext ctx = mw.buildGameContext(true);

		assertFalse(ctx.returnCardToOwnersHandIfOnField(gone), "the card that left is not on the field");
		assertEquals(List.of(onField), mw.p2ForwardCards, "and the copy that stayed is not taken instead");

		assertTrue(ctx.returnCardToOwnersHandIfOnField(onField));
		assertTrue(mw.p2ForwardCards.isEmpty());
		assertTrue(mw.gameState.getP2Hand().stream().anyMatch(c -> c == onField), "back to its owner's hand");
	}

	// =========================================================================================
	// 18-136S Titan and 24-065H Fenrir: the two extra-cost Summons that refer back to the card
	// that paid the cost, rather than saying "If you paid the extra cost" like the other sixteen.
	//
	// That distinction is the whole reason they need resolution-time handling. The sixteen are
	// settled before the resolver ever sees them - applyExtraCostPaid/stripExtraCostClause rewrite
	// the text into one branch or the other - whereas these two have to read the payment itself,
	// through extraCostRemovedCardPower() and extraCostDiscardedCardCost().
	//
	// Fenrir was the more dangerous of the two: "break it and draw 1 card" contains "break it", so
	// the generic Break followup claimed it and broke the chosen Forward outright, with no cost
	// comparison and no extra cost paid. It counted as "fully parsed" in the coverage report the
	// whole time, because a wrong answer has no "?" in it.
	// =========================================================================================

	// --- Titan 18-136S --------------------------------------------------------------------

	private static final String TITAN_EFFECT =
			"Choose 1 Forward. Deal it damage equal to the power of the Forward removed by the extra cost.";

	@Test
	void titanDealsDamageEqualToTheRemovedForwardsPower() {
		ForwardTarget t = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);
		GameContext ctx = contextChoosing(List.of(t));
		when(ctx.extraCostRemovedCardPower()).thenReturn(8000);

		Consumer<GameContext> fn = ActionResolver.parse(TITAN_EFFECT, makeForward("Titan", "Earth", 4, 0));
		assertNotNull(fn, "Titan should parse");
		fn.accept(ctx);

		verify(ctx).damageTarget(t, 8000);
	}

	/**
	 * The extra cost is optional, and Titan is all payoff — cast without paying it, the Summon
	 * chooses a Forward and does nothing. Dealing 0 damage would not be the same thing: it still
	 * counts as having dealt damage to everything watching for that.
	 */
	@Test
	void titanDealsNoDamageAtAllWhenTheExtraCostWentUnpaid() {
		ForwardTarget t = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);
		GameContext ctx = contextChoosing(List.of(t));
		when(ctx.extraCostRemovedCardPower()).thenReturn(0);

		ActionResolver.parse(TITAN_EFFECT, makeForward("Titan", "Earth", 4, 0)).accept(ctx);

		verify(ctx, never()).damageTarget(any(), anyInt());
	}

	/** A Forward is still chosen either way — the choice is not conditional on the payment. */
	@Test
	void titanStillChoosesATargetWithoutTheExtraCost() {
		GameContext ctx = contextChoosing(List.of(new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD)));
		when(ctx.extraCostRemovedCardPower()).thenReturn(0);

		ActionResolver.parse(TITAN_EFFECT, makeForward("Titan", "Earth", 4, 0)).accept(ctx);

		verify(ctx).selectCharacters(
				anyInt(), anyBoolean(), anyBoolean(), anyBoolean(),
				any(), any(), anyInt(), any(), anyInt(), any(),
				anyBoolean(), anyBoolean(), anyBoolean(),
				any(), any(), any(), any(), anyBoolean(), any(), anyBoolean());
	}

	// --- Fenrir 24-065H -------------------------------------------------------------------

	private static final String FENRIR_EFFECT =
			"Choose 1 Forward opponent controls. If its cost is equal to the cost of the card "
			+ "discarded by the extra cost, break it and draw 1 card.";

	/** Chooses an opposing Forward of the given cost and resolves Fenrir against it. */
	private static GameContext fenrirBoard(int chosenCost, int discardedCost) {
		ForwardTarget t = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);
		GameContext ctx = contextChoosing(List.of(t));
		when(ctx.p2Forward(0)).thenReturn(makeForward("Bahamut", "Fire", chosenCost, 9000));
		when(ctx.extraCostDiscardedCardCost()).thenReturn(discardedCost);
		return ctx;
	}

	@Test
	void fenrirBreaksAndDrawsWhenTheCostsMatch() {
		GameContext ctx = fenrirBoard(3, 3);

		Consumer<GameContext> fn = ActionResolver.parse(FENRIR_EFFECT, makeForward("Fenrir", "Ice", 3, 0));
		assertNotNull(fn, "Fenrir should parse");
		fn.accept(ctx);

		verify(ctx).breakTarget(new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD));
		verify(ctx).drawCards(1);
	}

	/** The regression: an unequal cost used to break the Forward anyway. */
	@Test
	void fenrirDoesNothingWhenTheCostsDiffer() {
		GameContext ctx = fenrirBoard(5, 3);

		ActionResolver.parse(FENRIR_EFFECT, makeForward("Fenrir", "Ice", 3, 0)).accept(ctx);

		verify(ctx, never()).breakTarget(any());
		verify(ctx, never()).drawCards(anyInt());
	}

	/** No discard means no cost to match — not a match against cost 0. */
	@Test
	void fenrirDoesNothingWhenTheExtraCostWentUnpaid() {
		GameContext ctx = fenrirBoard(0, 0);

		ActionResolver.parse(FENRIR_EFFECT, makeForward("Fenrir", "Ice", 3, 0)).accept(ctx);

		verify(ctx, never()).breakTarget(any());
		verify(ctx, never()).drawCards(anyInt());
	}

	/**
	 * Both cards must report a followup naming the extra-cost payoff. Fenrir reporting plain
	 * "Break" is what let a card that ignored its own condition sit in the "fully parsed" bucket.
	 */
	@Test
	void theExtraCostPayoffsAreNamedRatherThanReportedAsPlainBreakOrNothing() {
		assertEquals("ChooseCharacter / IfCostEqualsExtraCostDiscardBreakDraw",
				ActionResolver.fullDescription(FENRIR_EFFECT, makeForward("Fenrir", "Ice", 3, 0)));
		assertEquals("ChooseCharacter / DamageEqualToExtraCostPower",
				ActionResolver.fullDescription(TITAN_EFFECT, makeForward("Titan", "Earth", 4, 0)));
	}

	/**
	 * The sixteen "If you paid the extra cost" Summons are decided before the resolver sees them,
	 * and must stay that way — 18-084C Ramuh resolves to one damage figure or the other, never to
	 * an extra-cost lookup.
	 */
	@Test
	void theIfYouPaidWordingIsStillSettledBeforeResolution() {
		String ramuh = "Choose 1 Forward. Deal it 7000 damage. "
				+ "If you paid the extra cost, deal it 15000 damage instead.";
		assertEquals("Choose 1 Forward. Deal it 15000 damage.", ActionResolver.applyExtraCostPaid(ramuh));
		assertEquals("Choose 1 Forward. Deal it 7000 damage.", ActionResolver.stripExtraCostClause(ramuh));

		ForwardTarget t = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);
		GameContext ctx = contextChoosing(List.of(t));
		ActionResolver.parse(ActionResolver.applyExtraCostPaid(ramuh), makeForward("Ramuh", "Lightning", 3, 0))
				.accept(ctx);
		verify(ctx).damageTarget(t, 15000);
		verify(ctx, never()).extraCostRemovedCardPower();
	}

	/**
	 * Opus 19's "If a [Element] Forward has entered your field this turn, the cost required to cast
	 * [Self] is reduced by N." — P1's cost before any Forward entered, after one of another Element
	 * did, and after one of {@code element} did.
	 */
	private static void assertEnteredElementReduces(CardData summon, String element, int reducedCost) {
		MainWindow mw = new MainWindow();
		mw.gameState.getP1Hand().add(summon);
		assertEquals(summon.cost(), mw.effectiveCastCost(summon), summon.name() + ": nothing has entered");

		String other = element.equals("Water") ? "Fire" : "Water";
		placeP1Forward(mw, makeForward("Other Element", other, 3, 7000));
		assertEquals(summon.cost(), mw.effectiveCastCost(summon), summon.name() + ": " + other + " is not " + element);

		placeP1Forward(mw, makeForward("Arrival", element, 3, 7000));
		assertEquals(reducedCost, mw.effectiveCastCost(summon), summon.name() + ": " + element + " entered");
	}

	// =========================================================================================
	// 19-001R Ifrit: "If a Fire Forward has entered your field this turn, the cost required to cast
	// Ifrit is reduced by 3. Choose 1 Forward. Deal it 7000 damage."
	// =========================================================================================

	private static final String IFRIT_19_001R = "If a Fire Forward has entered your field this turn, the cost required "
			+ "to cast Ifrit is reduced by 3.[[br]]   Choose 1 Forward. Deal it 7000 damage.";

	@Test
	void ifritDeals7000() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 5, 9000);
		placeP1Forward(mw, theirs);
		castAsP2(mw, makeSummonWithCostText("Ifrit", "Fire", 3, IFRIT_19_001R));
		assertEquals(7000, damageOn(mw, theirs));
	}

	@Test
	void ifritCostsThreeLessOnceAFireForwardHasEntered() {
		assertEnteredElementReduces(makeSummonWithCostText("Ifrit", "Fire", 3, IFRIT_19_001R), "Fire", 0);
	}

	// =========================================================================================
	// 19-022R Shiva: "If an Ice Forward has entered your field this turn, the cost required to cast
	// Shiva is reduced by 4. Choose 1 Forward opponent controls. Dull it and Freeze it. Draw 1 card."
	// =========================================================================================

	private static final String SHIVA_19_022R = "If an Ice Forward has entered your field this turn, the cost required "
			+ "to cast Shiva is reduced by 4.[[br]]   Choose 1 Forward opponent controls. Dull it and Freeze it. Draw 1 "
			+ "card.";

	@Test
	void shivaDullsAndFreezesTheirForwardAndDraws() {
		MainWindow mw = new MainWindow();
		fillP2Deck(mw, 2);
		placeP1Forward(mw, makeForward("Theirs", "Water", 5, 9000));
		placeP2Forward(mw, makeForward("Mine", "Ice", 3, 7000));
		castAsP2(mw, makeSummonWithCostText("Shiva", "Ice", 4, SHIVA_19_022R));

		assertEquals(CardState.DULL, mw.p1ForwardStates.get(0));
		assertTrue(mw.p1ForwardFrozen.get(0));
		assertEquals(CardState.ACTIVE, mw.p2ForwardStates.get(0), "opponent's only");
		assertEquals(1, mw.gameState.getP2Hand().size());
	}

	@Test
	void shivaCostsFourLessOnceAnIceForwardHasEntered() {
		assertEnteredElementReduces(makeSummonWithCostText("Shiva", "Ice", 4, SHIVA_19_022R), "Ice", 0);
	}

	// =========================================================================================
	// 19-035R Alexander: "If a Wind Forward has entered your field this turn, the cost required to
	// cast Alexander is reduced by 4. Select 1 of the 2 following actions. "Choose 1 Forward of cost
	// 5 or more. Break it." "Choose 1 Monster. Break it. Draw 1 card.""
	// =========================================================================================

	private static final String ALEXANDER_19_035R = "If a Wind Forward has entered your field this turn, the cost "
			+ "required to cast Alexander is reduced by 4.[[br]]   Select 1 of the 2 following actions.[[br]]   "
			+ "\"Choose 1 Forward of cost 5 or more. Break it.\"[[br]]   \"Choose 1 Monster. Break it. Draw 1 card.\"";

	@Test
	void alexanderBreaksAForwardOfCostFiveOrMore() {
		MainWindow mw = new MainWindow();
		CardData four = makeForward("Four", "Water", 4, 8000);
		CardData five = makeForward("Five", "Water", 5, 9000);
		placeP1Forward(mw, four);
		placeP1Forward(mw, five);
		castAsP2Selecting(mw, makeSummonWithCostText("Alexander", "Wind", 4, ALEXANDER_19_035R), 0);
		assertEquals(List.of(four), mw.p1ForwardCards);
	}

	@Test
	void alexanderBreaksAMonsterAndDraws() {
		MainWindow mw = new MainWindow();
		fillP2Deck(mw, 2);
		CardData monster = makeMonster("Monster", "Water", 2);
		placeP1Monster(mw, monster);
		castAsP2Selecting(mw, makeSummonWithCostText("Alexander", "Wind", 4, ALEXANDER_19_035R), 1);
		assertFalse(mw.p1MonsterCards.contains(monster));
		assertEquals(1, mw.gameState.getP2Hand().size());
	}

	@Test
	void alexanderCostsFourLessOnceAWindForwardHasEntered() {
		assertEnteredElementReduces(makeSummonWithCostText("Alexander", "Wind", 4, ALEXANDER_19_035R), "Wind", 0);
	}

	// =========================================================================================
	// 19-064R Fenrir: "If an Earth Forward has entered your field this turn, the cost required to cast
	// Fenrir is reduced by 3. Select 1 of the 2 following actions. "Choose 1 Multi-Element Forward.
	// Break it." "Choose 1 Light Forward or Dark Forward. Break it.""
	// =========================================================================================

	private static final String FENRIR_19_064R = "If an Earth Forward has entered your field this turn, the cost "
			+ "required to cast Fenrir is reduced by 3.[[br]]   Select 1 of the 2 following actions.[[br]]   \"Choose 1 "
			+ "Multi-Element Forward. Break it.\"[[br]]   \"Choose 1 Light Forward or Dark Forward. Break it.\"";

	@Test
	void fenrirBreaksAMultiElementForward() {
		MainWindow mw = new MainWindow();
		CardData single = makeForward("Single", "Water", 3, 7000);
		CardData multi  = makeForward("Multi", "Fire/Water", 3, 7000);
		placeP1Forward(mw, single);
		placeP1Forward(mw, multi);
		castAsP2Selecting(mw, makeSummonWithCostText("Fenrir", "Earth", 3, FENRIR_19_064R), 0);
		assertEquals(List.of(single), mw.p1ForwardCards);
	}

	@Test
	void fenrirBreaksADarkForward() {
		MainWindow mw = new MainWindow();
		CardData water = makeForward("Water One", "Water", 3, 7000);
		CardData dark  = makeForward("Dark One", "Dark", 3, 7000);
		placeP1Forward(mw, water);
		placeP1Forward(mw, dark);
		castAsP2Selecting(mw, makeSummonWithCostText("Fenrir", "Earth", 3, FENRIR_19_064R), 1);
		assertEquals(List.of(water), mw.p1ForwardCards);
	}

	@Test
	void fenrirCostsThreeLessOnceAnEarthForwardHasEntered() {
		assertEnteredElementReduces(makeSummonWithCostText("Fenrir", "Earth", 3, FENRIR_19_064R), "Earth", 0);
	}

	// =========================================================================================
	// 19-083R Ramuh: "If a Lightning Forward has entered your field this turn, the cost required to
	// cast Ramuh is reduced by 3. Choose 1 Forward of cost 3 or less. Deal it 8000 damage."
	// =========================================================================================

	private static final String RAMUH_19_083R = "If a Lightning Forward has entered your field this turn, the cost "
			+ "required to cast Ramuh is reduced by 3.[[br]]   Choose 1 Forward of cost 3 or less. Deal it 8000 damage.";

	@Test
	void ramuhDeals8000ToAForwardOfCostThreeOrLess() {
		MainWindow mw = new MainWindow();
		CardData three = makeForward("Three", "Water", 3, 10000);
		CardData four  = makeForward("Four", "Water", 4, 10000);
		placeP1Forward(mw, four);
		placeP1Forward(mw, three);
		castAsP2(mw, makeSummonWithCostText("Ramuh", "Lightning", 3, RAMUH_19_083R));
		assertEquals(8000, damageOn(mw, three));
		assertEquals(0, damageOn(mw, four));
	}

	@Test
	void ramuhCostsThreeLessOnceALightningForwardHasEntered() {
		assertEnteredElementReduces(makeSummonWithCostText("Ramuh", "Lightning", 3, RAMUH_19_083R), "Lightning", 0);
	}

	// =========================================================================================
	// 19-101R Leviathan: "Choose 1 Forward opponent controls. Return it to its owner's hand. Until
	// the end of the next turn, your opponent cannot cast any copies of it."
	//
	// The corpus's only cast ban that outlives the turn it is set in, so it is a countdown of turn
	// boundaries rather than a turn-scoped flag: two from the cast, covering the rest of this turn
	// and the whole of the next.
	//
	// Both sentences are read as one clause. Split, "it" in the ban reaches the secondary parser
	// with no referent and the bounce runs alone — which is what left the card at "+ ?".
	// =========================================================================================

	private static final String LEVIATHAN_BOUNCE_SUMMON =
			"Choose 1 Forward opponent controls. Return it to its owner's hand. "
			+ "Until the end of the next turn, your opponent cannot cast any copies of it.";

	/** Bounces the single P2 Forward and returns the board it left. */
	private static MainWindow leviathanBounce(CardData victim) {
		MainWindow mw = new MainWindow();
		placeP2Forward(mw, victim);

		GameContext ctx = mw.buildGameContext(true);
		ctx.preloadTargets(List.of(fwd(false, 0)));
		Consumer<GameContext> effect = ActionResolver.parse(LEVIATHAN_BOUNCE_SUMMON, null);
		assertNotNull(effect, "the printed wording has to parse for the rest of this to mean anything");
		effect.accept(ctx);
		return mw;
	}

	@Test
	void leviathanBouncesAndBarsTheNameFromComingBack() {
		CardData victim = makeForward("Bounced", "Fire", 3, 7000);
		MainWindow mw = leviathanBounce(victim);

		assertTrue(mw.p2ForwardCards.isEmpty(), "the Forward went back to hand");
		assertFalse(mw.castRestrictionMet(victim, false), "and its owner may not cast it again");
	}

	@Test
	void andTheBanIsOnTheNameRatherThanTheCopy() {
		// "any copies of it" — a second printing of the same card is barred too.
		CardData victim = makeForward("Bounced", "Fire", 3, 7000);
		MainWindow mw = leviathanBounce(victim);

		assertFalse(mw.castRestrictionMet(makeForward("Bounced", "Fire", 3, 7000), false));
		assertTrue(mw.castRestrictionMet(makeForward("Someone Else", "Fire", 3, 7000), false),
				"and nothing else is");
	}

	@Test
	void andItBindsOnlyTheOpponent() {
		CardData victim = makeForward("Bounced", "Fire", 3, 7000);
		MainWindow mw = leviathanBounce(victim);

		assertTrue(mw.castRestrictionMet(victim, true), "the caster is free to cast their own copy");
	}

	@Test
	void andItSurvivesThisTurnsEndButNotTheNextTurnsEnd() {
		// "Until the end of the next turn" is two boundaries from the cast, which is the whole
		// reason this is a countdown rather than one of the turn-scoped flags beside it.
		CardData victim = makeForward("Bounced", "Fire", 3, 7000);
		MainWindow mw = leviathanBounce(victim);

		mw.ageCastNameBans();
		assertFalse(mw.castRestrictionMet(victim, false), "still barred through the next turn");

		mw.ageCastNameBans();
		assertTrue(mw.castRestrictionMet(victim, false), "and free once that turn has ended");
	}

	@Test
	void leviathansBanIsNoLongerAnUnreadTail() {
		assertEquals("ChooseCharacter / ReturnToOwnersHandAndBanCopies",
				ActionResolver.fullDescription(LEVIATHAN_BOUNCE_SUMMON, null));
	}

	@Test
	void leviathanCostsThreeLessOnceAWaterForwardHasEntered() {
		assertEnteredElementReduces(makeSummonWithCostText("Leviathan", "Water", 3,
				"If a Water Forward has entered your field this turn, the cost required to cast Leviathan is "
				+ "reduced by 3.[[br]]   " + LEVIATHAN_BOUNCE_SUMMON), "Water", 0);
	}

	// =========================================================================================
	// 19-104H Madeen: "You must control a Light Forward to cast Madeen. Choose 1 Forward. Remove it
	// from the game."
	// =========================================================================================

	private static final String MADEEN_19_104H = "You must control a Light Forward to cast Madeen.[[br]]   Choose 1 "
			+ "Forward. Remove it from the game.";

	@Test
	void madeenRemovesTheChosenForwardFromTheGame() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 5, 9000);
		placeP1Forward(mw, theirs);
		placeP2Forward(mw, makeForward("Light One", "Light", 3, 7000));
		castAsP2(mw, makeSummonWithCostText("Madeen", "Light", 1, MADEEN_19_104H));
		assertTrue(mw.gameState.getP1PermanentRfp().contains(theirs));
	}

	@Test
	void madeenNeedsYouToControlALightForward() {
		CardData madeen = makeSummonWithCostText("Madeen", "Light", 1, MADEEN_19_104H);
		MainWindow mw = new MainWindow();
		placeP1Forward(mw, makeForward("Fire One", "Fire", 3, 7000));
		placeP2Forward(mw, makeForward("Their Light", "Light", 3, 7000));
		assertFalse(mw.castRestrictionMet(madeen, true), "the opponent's Light Forward is not yours");

		placeP1Forward(mw, makeForward("Light One", "Light", 3, 7000));
		assertTrue(mw.castRestrictionMet(madeen, true));
	}

	// =========================================================================================
	// 19-105H Ark: "The cost required to cast Ark is reduced by 1 for each CP required to cast the
	// highest cost Dark Forward you control. Choose 1 Forward opponent controls. Break it. Your
	// opponent discards 2 cards."
	// =========================================================================================

	private static final String ARK_19_105H = "The cost required to cast Ark is reduced by 1 for each CP required to "
			+ "cast the highest cost Dark Forward you control.[[br]]   Choose 1 Forward opponent controls. Break it. "
			+ "Your opponent discards 2 cards.";

	@Test
	void arkBreaksTheirForwardAndTheyDiscardTwo() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 5, 9000);
		CardData mine   = makeForward("Mine", "Dark", 5, 9000);
		placeP1Forward(mw, theirs);
		placeP2Forward(mw, mine);
		inP1Hand(mw, 2);
		castAsP2(mw, makeSummonWithCostText("Ark", "Dark", 9, ARK_19_105H));

		assertTrue(mw.gameState.getP1BreakZone().contains(theirs));
		assertEquals(List.of(mine), mw.p2ForwardCards, "opponent's only");
		assertTrue(mw.gameState.getP1Hand().isEmpty());
	}

	@Test
	void arkCostsLessByTheCostOfYourHighestCostDarkForward() {
		MainWindow mw = new MainWindow();
		CardData ark = makeSummonWithCostText("Ark", "Dark", 9, ARK_19_105H);
		mw.gameState.getP1Hand().add(ark);
		placeP1Forward(mw, makeForward("Fire Seven", "Fire", 7, 9000));
		placeP2Forward(mw, makeForward("Their Dark", "Dark", 8, 9000));
		assertEquals(9, mw.effectiveCastCost(ark), "neither a Fire Forward nor the opponent's Dark counts");

		placeP1Forward(mw, makeForward("Dark Three", "Dark", 3, 7000));
		placeP1Forward(mw, makeForward("Dark Five", "Dark", 5, 9000));
		assertEquals(4, mw.effectiveCastCost(ark), "the highest, not the sum");
	}

	// =========================================================================================
	// 19-130S Bahamut: "If you control 3 or more Category XIII Characters, the cost required to cast
	// Bahamut is reduced by 3. Choose 1 Forward. Deal it 10000 damage. If it is put from the field
	// into the Break Zone this turn, remove it from the game instead."
	// =========================================================================================

	private static final String BAHAMUT_19_130S = "If you control 3 or more Category XIII Characters, the cost "
			+ "required to cast Bahamut is reduced by 3.[[br]]   Choose 1 Forward. Deal it 10000 damage. If it is put "
			+ "from the field into the Break Zone this turn, remove it from the game instead.";

	@Test
	void bahamutRemovesFromTheGameTheForwardIts10000DamageBreaks() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 5, 9000);
		placeP1Forward(mw, theirs);
		castAsP2(mw, makeSummonWithCostText("Bahamut", "Fire", 5, BAHAMUT_19_130S));
		assertTrue(mw.gameState.getP1PermanentRfp().contains(theirs));
	}

	@Test
	void bahamutCostsThreeLessWithThreeCategoryXiiiCharacters() {
		MainWindow mw = new MainWindow();
		CardData bahamut = makeSummonWithCostText("Bahamut", "Fire", 5, BAHAMUT_19_130S);
		mw.gameState.getP1Hand().add(bahamut);
		placeP1Forward(mw, makeCategoryForward("Lightning", "Lightning", "XIII"));
		placeP1Forward(mw, makeCategoryForward("Snow", "Ice", "XIII"));
		placeP1Forward(mw, makeCategoryForward("Bartz", "Wind", "V"));
		assertEquals(5, mw.effectiveCastCost(bahamut), "two of Category XIII");

		placeP1Forward(mw, makeCategoryForward("Vanille", "Earth", "XIII"));
		assertEquals(2, mw.effectiveCastCost(bahamut));
	}

	/**
	 * Casts {@code summon} from P2's seat taking {@code options}, and returns what its "Select N of
	 * the following actions" offered: {@code {count, upTo ? 1 : 0}}.
	 */
	private static int[] castAsP2ReportingOffer(MainWindow mw, CardData summon, int... options) {
		GameContext ctx = spy(mw.buildGameContext(false));
		int[] offer = new int[2];
		doAnswer(inv -> {
			offer[0] = inv.getArgument(2);
			offer[1] = (boolean) inv.getArgument(3) ? 1 : 0;
			List<String> offered = inv.getArgument(1);
			List<String> taken = new ArrayList<>();
			for (int option : options) taken.add(offered.get(option));
			return taken;
		}).when(ctx).chooseActions(any(), anyList(), anyInt(), anyBoolean());
		Consumer<GameContext> fn = ActionResolver.parse(summon.summonEffect(), summon);
		assertNotNull(fn, summon.name() + " parses");
		fn.accept(ctx);
		return offer;
	}

	// =========================================================================================
	// 20-003H Ifrit: "Select 1 of the 2 following actions. If you control 5 or more Fire Characters,
	// select up to 2 of the 2 following actions instead. "Choose 1 Forward. Deal it 5000 damage."
	// "Choose 1 Forward. Deal it 5000 damage.""
	// =========================================================================================

	private static final String IFRIT_20_003H = "Select 1 of the 2 following actions. If you control 5 or more Fire "
			+ "Characters, select up to 2 of the 2 following actions instead.[[br]]   \"Choose 1 Forward. Deal it 5000 "
			+ "damage.\"[[br]]   \"Choose 1 Forward. Deal it 5000 damage.\"";

	/** P2 holds {@code fire} Fire Characters — Backups, so none of them is a target — and P1 one Forward. */
	private static MainWindow ifritWithFireCharacters(int fire) {
		MainWindow mw = new MainWindow();
		for (int i = 0; i < fire; i++) placeP2Backup(mw, makeBackup("Fire " + i, "Fire", 2));
		placeP2Backup(mw, makeBackup("Water", "Water", 2));
		placeP1Forward(mw, makeForward("Theirs", "Water", 7, 12000));
		return mw;
	}

	@Test
	void ifritOffersOneActionWithFourFireCharacters() {
		assertArrayEquals(new int[] { 1, 0 },
				castAsP2ReportingOffer(ifritWithFireCharacters(4), makeSummon("Ifrit", "Fire", 1, IFRIT_20_003H), 0));
	}

	@Test
	void ifritOffersBothWithFiveFireCharacters() {
		MainWindow mw = ifritWithFireCharacters(4);
		placeP2Forward(mw, makeForward("Fire Forward", "Fire", 3, 7000));
		assertArrayEquals(new int[] { 2, 1 },
				castAsP2ReportingOffer(mw, makeSummon("Ifrit", "Fire", 1, IFRIT_20_003H), 0, 1));
		assertEquals(10000, damageOn(mw, mw.p1ForwardCards.get(0)), "5000 twice");
	}

	// =========================================================================================
	// 20-037H Mateus (FFTA): "Select 1 of the 2 following actions. "Freeze all the Forwards opponent
	// controls. Remove Mateus (FFTA) from the game." "Freeze all the Backups opponent controls. Remove
	// Mateus (FFTA) from the game.""
	//
	// Both options used to run. Each is two complete sentences, so the sentence composer — which cut
	// inside the quotations — found every fragment readable and resolved them all, header and all,
	// ahead of the Select parser.
	// =========================================================================================

	private static final String MATEUS_20_037H = "Select 1 of the 2 following actions.[[br]]   \"Freeze all the "
			+ "Forwards opponent controls. Remove Mateus (FFTA) from the game.\"[[br]]   \"Freeze all the Backups "
			+ "opponent controls. Remove Mateus (FFTA) from the game.\"";

	private static MainWindow mateusBoard() {
		MainWindow mw = new MainWindow();
		placeP1Forward(mw, makeForward("Their Forward", "Water", 3, 7000));
		placeP1Backup(mw, makeBackup("Their Backup", "Water", 2));
		placeP2Forward(mw, makeForward("Mine", "Ice", 3, 7000));
		return mw;
	}

	@Test
	void mateusFreezesTheirForwardsOnly() {
		MainWindow mw = mateusBoard();
		castAsP2Selecting(mw, makeSummon("Mateus (FFTA)", "Ice", 2, MATEUS_20_037H), 0);
		assertTrue(mw.p1ForwardFrozen.get(0));
		assertFalse(mw.p1BackupFrozen[0], "one action, not both");
		assertFalse(mw.p2ForwardFrozen.get(0), "opponent's only");
	}

	@Test
	void mateusFreezesTheirBackupsOnly() {
		MainWindow mw = mateusBoard();
		castAsP2Selecting(mw, makeSummon("Mateus (FFTA)", "Ice", 2, MATEUS_20_037H), 1);
		assertTrue(mw.p1BackupFrozen[0]);
		assertFalse(mw.p1ForwardFrozen.get(0), "one action, not both");
	}

	@Test
	void mateusIsReadAsTheChoiceItIs() {
		assertTrue(ActionResolver.fullDescription(MATEUS_20_037H, null).startsWith("SelectFollowingActions(1 of 2"));
	}

	// =========================================================================================
	// 20-051H Fat Chocobo: "The cost required to cast Fat Chocobo is reduced by 1 for every 3 Wind
	// Characters you control. Deal 8000 damage to all the Forwards opponent controls."
	// =========================================================================================

	private static final String FAT_CHOCOBO_20_051H = "The cost required to cast Fat Chocobo is reduced by 1 for "
			+ "every 3 Wind Characters you control.[[br]]   Deal 8000 damage to all the Forwards opponent controls.";

	@Test
	void fatChocoboDeals8000ToAllTheirForwards() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 5, 9000);
		CardData mine   = makeForward("Mine", "Wind", 5, 9000);
		placeP1Forward(mw, theirs);
		placeP2Forward(mw, mine);
		castAsP2(mw, makeSummonWithCostText("Fat Chocobo", "Wind", 8, FAT_CHOCOBO_20_051H));
		assertEquals(8000, damageOn(mw, theirs));
		assertEquals(0, (int) mw.p2ForwardDamage.get(0), "opponent's only");
	}

	@Test
	void fatChocoboCostsOneLessForEveryThreeWindCharacters() {
		MainWindow mw = new MainWindow();
		CardData fat = makeSummonWithCostText("Fat Chocobo", "Wind", 8, FAT_CHOCOBO_20_051H);
		mw.gameState.getP1Hand().add(fat);
		for (int i = 0; i < 2; i++) placeP1Forward(mw, makeForward("Wind " + i, "Wind", 3, 7000));
		for (int i = 0; i < 3; i++) placeP1Backup(mw, makeBackup("Wind Backup " + i, "Wind", 2));
		placeP1Backup(mw, makeBackup("Fire Backup", "Fire", 2));
		assertEquals(7, mw.effectiveCastCost(fat), "five Wind Characters make one group of three");

		placeP1Forward(mw, makeForward("Wind 2", "Wind", 3, 7000));
		assertEquals(6, mw.effectiveCastCost(fat));
	}

	// =========================================================================================
	// 20-081H Fenrir: "If Fenrir chooses a Forward of cost 2 or less, the cost required to cast Fenrir
	// is reduced by 1. Choose 1 Forward. It gains "This Forward cannot be chosen by your opponent's
	// Summons." until the end of the turn."
	// =========================================================================================

	private static final String FENRIR_20_081H = "If Fenrir chooses a Forward of cost 2 or less, the cost required to "
			+ "cast Fenrir is reduced by 1.[[br]]   Choose 1 Forward. It gains \"This Forward cannot be chosen by your "
			+ "opponent's Summons.\" until the end of the turn.";

	@Test
	void fenrirShieldsYourForwardFromTheirSummons() {
		MainWindow mw = new MainWindow();
		CardData mine   = makeForward("Mine", "Earth", 3, 7000);
		CardData theirs = makeForward("Theirs", "Water", 3, 7000);
		placeP2Forward(mw, mine);
		placeP1Forward(mw, theirs);
		castAsP2(mw, makeSummonWithCostText("Fenrir", "Earth", 1, FENRIR_20_081H));

		assertTrue(mw.cannotBeChosenBySummons.contains(mine), "the CPU protects its own");
		assertFalse(mw.cannotBeChosenByAbilities.contains(mine), "Summons only");
		assertFalse(mw.cannotBeChosenBySummons.contains(theirs));
	}

	// The discount is an alternate cost: the cast is paid at the reduced price and its choice is then
	// held to a Forward of cost 2 or less, both as it goes on the Stack and as it resolves. The cast
	// pays before it chooses, so a discount read as a plain condition would have nothing to test.

	@Test
	void fenrirsDiscountIsReadAsAChoiceItCommitsTo() {
		CardData fenrir = makeSummonWithCostText("Fenrir", "Earth", 1, FENRIR_20_081H);
		assertEquals(new CardData.AltChosenTargetReduction(2, 1), fenrir.altChosenTargetReduction());
		assertEquals(List.of(), fenrir.altCpElements(), "cost 1, less 1");
		assertTrue(fenrir.selfCostModifiers().isEmpty(), "an option taken at cast, not a standing reduction");
		assertTrue(fenrir.narrowedToChosenReduction(fenrir.summonEffect())
				.startsWith("Choose 1 Forward of cost 2 or less. It gains"));
	}

	@Test
	void aChosenTargetDiscountNamingAnotherCardIsNotThisCardsOwn() {
		CardData fenrir = makeSummonWithCostText("Fenrir", "Earth", 1,
				FENRIR_20_081H.replace("If Fenrir chooses", "If Odin chooses"));
		assertNull(fenrir.altChosenTargetReduction());
		assertEquals(fenrir.summonEffect(), fenrir.narrowedToChosenReduction(fenrir.summonEffect()));
	}

	@Test
	void fenrirsDiscountIsOfferedOnlyWithAForwardItCouldChoose() {
		MainWindow mw = new MainWindow();
		CardData fenrir = makeSummonWithCostText("Fenrir", "Earth", 1, FENRIR_20_081H);
		placeP1Forward(mw, makeForward("Three", "Earth", 3, 7000));
		placeP2Forward(mw, makeForward("Their Three", "Water", 3, 7000));
		assertFalse(mw.chosenReductionHasTarget(fenrir, true), "nothing of cost 2 or less to commit to");

		placeP2Forward(mw, makeForward("Their Two", "Water", 2, 5000));
		assertTrue(mw.chosenReductionHasTarget(fenrir, true), "Fenrir chooses any Forward, the opponent's too");
	}

	/** P2 holds Fenrir in hand and controls a cost-3 and a cost-2 Forward. */
	private static MainWindow fenrirInP2Hand(CardData fenrir, CardData three, CardData two) {
		MainWindow mw = new MainWindow();
		mw.gameState.getIdentity().put(fenrir, false);
		mw.gameState.getP2Hand().add(fenrir);
		placeP2Forward(mw, three);
		placeP2Forward(mw, two);
		return mw;
	}

	@Test
	void aDiscountedFenrirChoosesOnlyAForwardOfCostTwoOrLess() {
		// The CPU picks at random between its own two Forwards, so an unrestricted choice would pass
		// once in two; repeated, it cannot pass by luck.
		for (int attempt = 0; attempt < 10; attempt++) {
			CardData fenrir = makeSummonWithCostText("Fenrir", "Earth", 1, FENRIR_20_081H);
			CardData three = makeForward("Three", "Earth", 3, 7000);
			CardData two   = makeForward("Two", "Earth", 2, 5000);
			MainWindow mw = fenrirInP2Hand(fenrir, three, two);

			mw.executeAltPlay(false, fenrir, 0, AltPayment.NOTHING_HANDED_OVER, List.of(), List.of(), Map.of(),
					null, false);
			assertTrue(mw.summonsCastWithChosenReduction.contains(fenrir));
			assertEquals(List.of(new ForwardTarget(false, 1, ForwardTarget.CardZone.FORWARD)),
					mw.gameState.peekStack().preSelectedTargets(), "the cost-2 Forward");
			if (mw.gameState.stackSize() > 0) mw.passStackPriority();

			assertTrue(mw.cannotBeChosenBySummons.contains(two));
			assertFalse(mw.cannotBeChosenBySummons.contains(three));
			assertFalse(mw.summonsCastWithChosenReduction.contains(fenrir), "forgotten once it has resolved");
		}
	}

	@Test
	void aFullPriceFenrirChoosesAnyForward() {
		CardData fenrir = makeSummonWithCostText("Fenrir", "Earth", 1, FENRIR_20_081H);
		CardData three = makeForward("Three", "Earth", 3, 7000);
		MainWindow mw = new MainWindow();
		mw.gameState.getIdentity().put(fenrir, false);
		placeP2Forward(mw, three);

		mw.pushSummonOnStack(fenrir, false, 0, 0, false, null, false);
		assertFalse(mw.summonsCastWithChosenReduction.contains(fenrir));
		if (mw.gameState.stackSize() > 0) mw.passStackPriority();
		assertTrue(mw.cannotBeChosenBySummons.contains(three), "cost 3 is fine at full price");
	}

	// =========================================================================================
	// 20-103H Ramuh: "Select up to 2 of the 3 following actions. "Choose 1 Forward. Dull it." "Choose
	// 1 Forward. Deal it 5000 damage." "Choose 1 Forward. It gains Haste until the end of the turn.""
	// =========================================================================================

	private static final String RAMUH_20_103H = "Select up to 2 of the 3 following actions.[[br]]   \"Choose 1 "
			+ "Forward. Dull it.\"[[br]]   \"Choose 1 Forward. Deal it 5000 damage.\"[[br]]   \"Choose 1 Forward. It "
			+ "gains Haste until the end of the turn.\"";

	@Test
	void ramuhDullsAndDamagesTheirForward() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 5, 9000);
		placeP1Forward(mw, theirs);
		castAsP2Selecting(mw, makeSummon("Ramuh", "Lightning", 2, RAMUH_20_103H), 0, 1);
		assertEquals(CardState.DULL, mw.p1ForwardStates.get(0));
		assertEquals(5000, damageOn(mw, theirs));
	}

	@Test
	void ramuhGivesYourForwardHaste() {
		MainWindow mw = new MainWindow();
		placeP2Forward(mw, makeForward("Mine", "Lightning", 3, 7000));
		placeP1Forward(mw, makeForward("Theirs", "Water", 3, 7000));
		castAsP2Selecting(mw, makeSummon("Ramuh", "Lightning", 2, RAMUH_20_103H), 2);
		assertTrue(mw.effectiveP2HasTrait(0, CardData.Trait.HASTE));
		assertFalse(mw.effectiveP1HasTrait(0, CardData.Trait.HASTE), "the CPU keeps a buff for itself");
	}

	// =========================================================================================
	// 20-118H Unicorn: "Select 1 of the 2 following actions. If you control a Category VI Forward,
	// select up to 2 of the 2 following actions instead. "Choose 1 Forward. Activate it. Draw 1
	// card." "Choose 1 Forward of cost 4 or less opponent controls. Return it to its owner's hand.""
	// =========================================================================================

	private static final String UNICORN_20_118H = "Select 1 of the 2 following actions. If you control a Category VI "
			+ "Forward, select up to 2 of the 2 following actions instead.[[br]]   \"Choose 1 Forward. Activate it. "
			+ "Draw 1 card.\"[[br]]   \"Choose 1 Forward of cost 4 or less opponent controls. Return it to its "
			+ "owner's hand.\"";

	@Test
	void unicornOffersOneActionWithoutACategoryViForward() {
		MainWindow mw = new MainWindow();
		fillP2Deck(mw, 2);
		placeP2Forward(mw, makeCategoryForward("Bartz", "Wind", "V"));
		placeP1Forward(mw, makeForward("Theirs", "Water", 4, 8000));
		assertArrayEquals(new int[] { 1, 0 },
				castAsP2ReportingOffer(mw, makeSummon("Unicorn", "Water", 2, UNICORN_20_118H), 1));
	}

	@Test
	void unicornOffersBothWithACategoryViForwardAndResolvesThem() {
		MainWindow mw = new MainWindow();
		fillP2Deck(mw, 2);
		placeP2Forward(mw, makeCategoryForward("Terra", "Fire", "VI"));
		mw.p2ForwardStates.set(0, CardState.DULL);
		CardData cheap = makeForward("Cheap", "Water", 4, 8000);
		CardData dear  = makeForward("Dear", "Water", 5, 9000);
		placeP1Forward(mw, dear);
		placeP1Forward(mw, cheap);
		assertArrayEquals(new int[] { 2, 1 },
				castAsP2ReportingOffer(mw, makeSummon("Unicorn", "Water", 2, UNICORN_20_118H), 0, 1));

		assertEquals(CardState.ACTIVE, mw.p2ForwardStates.get(0), "the CPU activates its own");
		assertEquals(1, mw.gameState.getP2Hand().size());
		assertEquals(List.of(dear), mw.p1ForwardCards, "cost 5 is over 4");
		assertTrue(mw.gameState.getP1Hand().contains(cheap));
	}

	// =========================================================================================
	// 21-012H Bahamut: "During your turn, the cost required to cast Bahamut is reduced by 2. Choose
	// 1 Forward. Deal it 10000 damage. This damage cannot be reduced. If it is put from the field
	// into the Break Zone this turn, remove it from the game instead."
	// =========================================================================================

	private static final String BAHAMUT_21_012H = "During your turn, the cost required to cast Bahamut is reduced by "
			+ "2.[[br]]   Choose 1 Forward. Deal it 10000 damage. This damage cannot be reduced. If it is put from the "
			+ "field into the Break Zone this turn, remove it from the game instead.";

	@Test
	void bahamutsTenThousandRemovesWhatItBreaksFromTheGame() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 5, 9000);
		placeP1Forward(mw, theirs);
		castAsP2(mw, makeSummonWithCostText("Bahamut", "Fire", 5, BAHAMUT_21_012H));
		assertTrue(mw.gameState.getP1RemovedFromGame().contains(theirs));
		assertFalse(mw.gameState.getP1BreakZone().contains(theirs));
	}

	@Test
	void bahamutsDamageCannotBeReduced() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 5, 9000);
		placeP1Forward(mw, theirs);
		mw.buildGameContext(true).shieldActivePlayerDamageReduction(5000);

		ActionResolver.parse("Choose 1 Forward. Deal it 10000 damage.", null).accept(mw.buildGameContext(false));
		assertEquals(List.of(theirs), mw.p1ForwardCards, "the shield is live: 10000 less 5000 does not break it");

		castAsP2(mw, makeSummonWithCostText("Bahamut", "Fire", 5, BAHAMUT_21_012H));
		assertTrue(mw.gameState.getP1RemovedFromGame().contains(theirs));
	}

	@Test
	void bahamutsRemovalOutlivesTheDamage() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 7, 12000);
		placeP1Forward(mw, theirs);
		castAsP2(mw, makeSummonWithCostText("Bahamut", "Fire", 5, BAHAMUT_21_012H));
		assertEquals(10000, damageOn(mw, theirs), "12000 power survives");

		mw.buildGameContext(false).breakTarget(fwd(true, 0));
		assertTrue(mw.gameState.getP1RemovedFromGame().contains(theirs), "broken later the same turn");
		assertFalse(mw.gameState.getP1BreakZone().contains(theirs));
	}

	@Test
	void bahamutCostsTwoLessDuringYourTurnOnly() {
		MainWindow mw = new MainWindow();
		CardData bahamut = makeSummonWithCostText("Bahamut", "Fire", 5, BAHAMUT_21_012H);
		mw.gameState.getP1Hand().add(bahamut);
		mw.gameState.startFirstTurn(GameState.Player.P1);
		assertEquals(3, mw.effectiveCastCost(bahamut));

		mw.gameState.startFirstTurn(GameState.Player.P2);
		assertEquals(5, mw.effectiveCastCost(bahamut));
	}

	// =========================================================================================
	// 21-028H Shiva: "If you cast Shiva, you may remove 4 Ice Cards in your Break Zone from the game
	// as an extra cost. Choose 1 Character. Dull it and Freeze it. If you paid the extra cost,
	// return Shiva to its owner's hand."
	//
	// Whether the extra cost was paid travels on the Stack entry, so both cases are cast there.
	// =========================================================================================

	private static final String SHIVA_21_028H = "If you cast Shiva, you may remove 4 Ice Cards in your Break Zone from "
			+ "the game as an extra cost.[[br]]   Choose 1 Character. Dull it and Freeze it. If you paid the extra cost, "
			+ "return Shiva to its owner's hand.";

	@Test
	void shivasExtraCostIsFourIceCardsFromTheBreakZone() {
		assertEquals(ExtraCost.bzRemoveElement(4, "Ice"), makeSummon("Shiva", "Ice", 1, SHIVA_21_028H).extraCost());
	}

	/** P2 casts Shiva at P1's lone Backup — a Character that is not a Forward; P1 passes. */
	private static MainWindow castShiva21(CardData shiva, boolean paidExtra) {
		MainWindow mw = new MainWindow();
		placeP1Backup(mw, makeBackup("Theirs", "Water", 2));
		mw.gameState.getIdentity().put(shiva, false);
		mw.pushSummonOnStack(shiva, false, 0, 0, paidExtra, null, false);
		mw.passStackPriority();
		return mw;
	}

	@Test
	void shivaDullsAndFreezesACharacterAndStaysInTheBreakZoneUnpaid() {
		CardData shiva = makeSummon("Shiva", "Ice", 1, SHIVA_21_028H);
		MainWindow mw = castShiva21(shiva, false);
		assertEquals(CardState.DULL, mw.p1BackupStates[0], "a Backup is a Character");
		assertTrue(mw.p1BackupFrozen[0]);
		assertFalse(mw.gameState.getP2Hand().contains(shiva));
	}

	@Test
	void shivaWithItsExtraCostReturnsToItsOwnersHand() {
		CardData shiva = makeSummon("Shiva", "Ice", 1, SHIVA_21_028H);
		MainWindow mw = castShiva21(shiva, true);
		assertEquals(CardState.DULL, mw.p1BackupStates[0]);
		assertTrue(mw.p1BackupFrozen[0]);
		assertTrue(mw.gameState.getP2Hand().contains(shiva));
		assertFalse(mw.gameState.getP2BreakZone().contains(shiva));
	}

	@Test
	void aBorrowedShivaReturnsToItsOwnerNotItsCaster() {
		MainWindow mw = new MainWindow();
		placeP2Backup(mw, makeBackup("Theirs", "Water", 2));
		CardData shiva = makeSummon("Shiva", "Ice", 1, SHIVA_21_028H);
		mw.gameState.getIdentity().put(shiva, false);
		mw.gameState.getP1Hand().clear();
		mw.pushSummonOnStack(shiva, true, 0, 0, true,
				List.of(new ForwardTarget(false, 0, ForwardTarget.CardZone.BACKUP)), false);
		mw.passStackPriority();

		assertTrue(mw.p2BackupFrozen[0]);
		assertTrue(mw.gameState.getP2Hand().contains(shiva), "P2 owns it");
		assertFalse(mw.gameState.getP1Hand().contains(shiva), "P1 only cast it");
	}

	// =========================================================================================
	// 21-054H Pandemonium:"Select 1 of the 2 following actions. If you have cast 2 or more cards
	// other than Pandemonium this turn, select up to 2 of the 2 following actions instead." Its
	// own cast is not one of those cards.
	// =========================================================================================

	@Test
	void pandemoniumsOwnCastDoesNotCountTowardItsUpgrade() {
		GameContext ctx = mock(GameContext.class);
		when(ctx.selfCardsCastThisTurn()).thenReturn(2);
		when(ctx.countCardsNamedCastThisTurn("Pandemonium")).thenReturn(1);
		CardData pandemonium = makeSummon("Pandemonium", "Wind", 4, "");
		ActionResolver.parse("Select 1 of the 2 following actions. If you have cast 2 or more cards other than "
				+ "Pandemonium this turn, select up to 2 of the 2 following actions instead. \"Choose 1 Forward of "
				+ "cost 5 or more. Deal it 8000 damage.\" \"Draw 1 card.\"", pandemonium).accept(ctx);
		verify(ctx).chooseActions(eq(pandemonium), anyList(), eq(1), eq(false));
	}

	private static final String PANDEMONIUM_21_054H = "Select 1 of the 2 following actions. If you have cast 2 or more "
			+ "cards other than Pandemonium this turn, select up to 2 of the 2 following actions instead.[[br]]   "
			+ "\"Choose 1 Forward of cost 5 or more. Deal it 8000 damage.\"[[br]]   \"Search for 1 Wind Forward of cost "
			+ "5 or more and add it to your hand.\"";

	/** P2 has cast {@code cards} cards this turn, Pandemonium itself among them. */
	private static MainWindow pandemoniumAfterCasting(int cards) {
		MainWindow mw = new MainWindow();
		mw.turn(false).cardsCastThisTurn = cards;
		mw.turn(false).castCountByNameThisTurn.put("pandemonium", 1);
		placeP1Forward(mw, makeForward("Theirs", "Water", 5, 9000));
		return mw;
	}

	@Test
	void pandemoniumOffersOneActionAfterOneOtherCast() {
		assertArrayEquals(new int[] { 1, 0 }, castAsP2ReportingOffer(pandemoniumAfterCasting(2),
				makeSummon("Pandemonium", "Wind", 2, PANDEMONIUM_21_054H), 0));
	}

	@Test
	void pandemoniumOffersBothAfterTwoOtherCasts() {
		assertArrayEquals(new int[] { 2, 1 }, castAsP2ReportingOffer(pandemoniumAfterCasting(3),
				makeSummon("Pandemonium", "Wind", 2, PANDEMONIUM_21_054H), 0));
	}

	@Test
	void pandemoniumDeals8000ToAForwardOfCostFiveOrMore() {
		MainWindow mw = new MainWindow();
		CardData cheap = makeForward("Cheap", "Water", 4, 9000);
		CardData dear  = makeForward("Dear", "Water", 5, 9000);
		placeP1Forward(mw, cheap);
		placeP1Forward(mw, dear);
		castAsP2Selecting(mw, makeSummon("Pandemonium", "Wind", 2, PANDEMONIUM_21_054H), 0);
		assertEquals(8000, damageOn(mw, dear));
		assertEquals(0, damageOn(mw, cheap), "cost 4 is under 5");
	}

	@Test
	void pandemoniumSearchesForAWindForwardOfCostFiveOrMore() {
		MainWindow mw = new MainWindow();
		CardData wind5 = makeForward("Wind Five", "Wind", 5, 9000);
		mw.gameState.getP2MainDeck().add(makeForward("Wind Four", "Wind", 4, 8000));
		mw.gameState.getP2MainDeck().add(makeForward("Fire Six", "Fire", 6, 10000));
		mw.gameState.getP2MainDeck().add(wind5);
		castAsP2Selecting(mw, makeSummon("Pandemonium", "Wind", 2, PANDEMONIUM_21_054H), 1);
		assertEquals(List.of(wind5), mw.gameState.getP2Hand());
	}

	// =========================================================================================
	// 21-071H Titan: "You can only pay with Earth CP to cast Titan. Select 1 of the 2 following
	// actions. "Choose 1 Forward. Deal it 9000 damage." "Choose 1 dull Forward. Break it.""
	// =========================================================================================

	private static final String TITAN_21_071H = "You can only pay with Earth CP to cast Titan.[[br]]   Select 1 of the "
			+ "2 following actions.[[br]]   \"Choose 1 Forward. Deal it 9000 damage.\"[[br]]   \"Choose 1 dull Forward. "
			+ "Break it.\"";

	@Test
	void titanDeals9000ToAForward() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 7, 12000);
		placeP1Forward(mw, theirs);
		castAsP2Selecting(mw, makeSummon("Titan", "Earth", 3, TITAN_21_071H), 0);
		assertEquals(9000, damageOn(mw, theirs));
	}

	@Test
	void titanBreaksADullForwardOnly() {
		MainWindow mw = new MainWindow();
		CardData active = makeForward("Active", "Water", 7, 12000);
		CardData dull   = makeForward("Dull", "Water", 7, 12000);
		placeP1Forward(mw, active);
		placeP1Forward(mw, dull);
		dullP1Forward(mw, dull);
		castAsP2Selecting(mw, makeSummon("Titan", "Earth", 3, TITAN_21_071H), 1);
		assertTrue(mw.gameState.getP1BreakZone().contains(dull));
		assertEquals(List.of(active), mw.p1ForwardCards);
	}

	/** P1 holds only {@code card}, with one active Earth Backup and two Fire ones. */
	private static boolean payableWithOneEarthAndTwoFireBackups(CardData card) {
		MainWindow mw = new MainWindow();
		mw.gameState.getP1Hand().clear();
		mw.gameState.getP1Hand().add(card);
		placeP1Backup(mw, makeBackup("Earth", "Earth", 2));
		placeP1Backup(mw, makeBackup("Fire A", "Fire", 2));
		placeP1Backup(mw, makeBackup("Fire B", "Fire", 2));
		return mw.canAffordCard(card, 0);
	}

	@Test
	void titanCanOnlyBePaidForWithEarthCp() {
		CardData titan = makeSummon("Titan", "Earth", 3, TITAN_21_071H);
		assertEquals("Earth", titan.castElementOnly());
		assertTrue(payableFromBackups(titan), "three Earth Backups");
		assertFalse(payableWithOneEarthAndTwoFireBackups(titan));
		assertTrue(payableWithOneEarthAndTwoFireBackups(makeSummon("Titan", "Earth", 3,
				TITAN_21_071H.replace("You can only pay with Earth CP to cast Titan.[[br]]   ", ""))),
				"the Fire CP would pay the generic part without the restriction");
	}

	// =========================================================================================
	// 21-084H Odin: "Choose 1 Forward or Monster of cost 4 or less. Break it. If you control 5 or
	// more Lightning Characters, also draw 1 card." — the gate and its condition both parsed
	// already; the sole blocker was the additive "also" in front of the inner effect, which no
	// pattern starts with. parse() now strips it like the "Then, " connective it sits alongside.
	// =========================================================================================

	private static final String ODIN_TEXT =
			"Choose 1 Forward or Monster of cost 4 or less. Break it. "
			+ "If you control 5 or more Lightning Characters, also draw 1 card.";

	@Test
	void odinConditionalDrawParses() {
		assertNotNull(ActionResolver.parse(ODIN_TEXT, null), "the whole effect should parse");
		assertNotNull(ActionResolver.parse("If you control 5 or more Lightning Characters, also draw 1 card.", null),
				"the 'also'-prefixed gate should parse on its own");
		assertEquals("DrawCards", ActionResolver.fullDescription("also draw 1 card.", null),
				"a leading 'also' is stripped like any other additive connective");
	}

	/** P1 casts Odin at P2's lone cost-3 Forward, with {@code lightningAllies} Lightning Forwards out. */
	private static MainWindow castOdinWith(int lightningAllies) {
		MainWindow mw = new MainWindow();
		mw.gameState.initializeDeck(List.of(
				makeForward("Deck Card A", "Lightning", 2, 5000),
				makeForward("Deck Card B", "Lightning", 2, 5000)), List.of());
		mw.gameState.getP1Hand().clear();

		for (int i = 0; i < lightningAllies; i++) {
			CardData ally = makeForward("Bolt " + i, "Lightning", 2, 5000);
			mw.gameState.getIdentity().put(ally, true);
			mw.placeCardInForwardZone(ally);
		}
		CardData victim = makeForward("Victim", "Ice", 3, 7000);
		mw.gameState.getIdentity().put(victim, false);
		mw.placeP2CardInForwardZone(victim);

		GameContext ctx = mw.buildGameContext(true);
		ctx.preloadTargets(List.of(new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD)));
		ActionResolver.parse(ODIN_TEXT, null).accept(ctx);
		return mw;
	}

	@Test
	void odinDrawsWhenFiveLightningCharactersAreControlled() {
		MainWindow mw = castOdinWith(5);
		assertTrue(mw.p2ForwardCards.isEmpty(), "the cost 3 Forward is broken either way");
		assertEquals(1, mw.gameState.getP1Hand().size(), "5 Lightning Characters — the draw fires");
	}

	@Test
	void odinSkipsTheDrawBelowFiveLightningCharacters() {
		MainWindow mw = castOdinWith(4);
		assertTrue(mw.p2ForwardCards.isEmpty(), "the break is unconditional");
		assertTrue(mw.gameState.getP1Hand().isEmpty(), "only 4 Lightning Characters — no draw");
	}

	@Test
	void odinBreaksAMonsterOfCostFourOrLess() {
		MainWindow mw = new MainWindow();
		CardData monster = makeMonster("Theirs", "Water", 4);
		CardData dear    = makeForward("Dear", "Water", 5, 9000);
		placeP1Monster(mw, monster);
		placeP1Forward(mw, dear);
		castAsP2(mw, makeSummon("Odin", "Lightning", 3, ODIN_TEXT));
		assertTrue(mw.gameState.getP1BreakZone().contains(monster));
		assertEquals(List.of(dear), mw.p1ForwardCards, "cost 5 is over 4");
	}

	// =========================================================================================
	// 21-116H Leviathan: "During your turn, the cost required to cast Leviathan is reduced by 2.
	// Choose 1 Forward. Put it at the bottom of its owner's deck. Then, you may play 1 Forward of
	// cost 2 or less from your hand onto the field."
	// =========================================================================================

	private static final String LEVIATHAN_21_116H = "During your turn, the cost required to cast Leviathan is reduced "
			+ "by 2.[[br]]   Choose 1 Forward. Put it at the bottom of its owner's deck. Then, you may play 1 Forward "
			+ "of cost 2 or less from your hand onto the field.";

	@Test
	void leviathanBottomsTheirForwardAndPlaysACheapForwardFromHand() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 5, 9000);
		placeP1Forward(mw, theirs);
		mw.gameState.getP1MainDeck().add(makeForward("Deck Top", "Water", 2, 5000));
		CardData two   = makeForward("Two", "Water", 2, 5000);
		CardData three = makeForward("Three", "Water", 3, 7000);
		for (CardData c : List.of(three, two)) {
			mw.gameState.getIdentity().put(c, false);
			mw.gameState.getP2Hand().add(c);
		}
		castAsP2(mw, makeSummonWithCostText("Leviathan", "Water", 6, LEVIATHAN_21_116H));

		assertTrue(mw.p1ForwardCards.isEmpty());
		assertSame(theirs, mw.gameState.getP1MainDeck().peekLast(), "under the card that was already there");
		assertEquals(List.of(two), mw.p2ForwardCards, "cost 3 is over 2");
		assertEquals(List.of(three), mw.gameState.getP2Hand());
	}

	@Test
	void leviathanCostsTwoLessDuringYourTurnOnly() {
		MainWindow mw = new MainWindow();
		CardData leviathan = makeSummonWithCostText("Leviathan", "Water", 6, LEVIATHAN_21_116H);
		mw.gameState.getP1Hand().add(leviathan);
		mw.gameState.startFirstTurn(GameState.Player.P1);
		assertEquals(4, mw.effectiveCastCost(leviathan));

		mw.gameState.startFirstTurn(GameState.Player.P2);
		assertEquals(6, mw.effectiveCastCost(leviathan));
	}

	// =========================================================================================
	// 22-014R Belias, the Gigas: "Choose 1 Forward you control and 1 Forward opponent controls.
	// Until the end of the turn, the former gains +3000 power and First Strike. Deal the latter
	// 7000 damage. If the CP paid to cast Belias, the Gigas was only produced by Backups, also draw
	// 1 card."
	//
	// 22-014R to 22-093R share the last sentence. Which CP paid for the cast is recorded on
	// MainWindow as it is paid, so each is cast here with that record set by hand.
	// =========================================================================================

	private static final String BELIAS_22_014R = "Choose 1 Forward you control and 1 Forward opponent controls. Until "
			+ "the end of the turn, the former gains +3000 power and First Strike. Deal the latter 7000 damage. If the CP "
			+ "paid to cast Belias, the Gigas was only produced by Backups, also draw 1 card.";

	/** P2 casts {@code summon} having paid with Backups alone or not, with a 2-card deck to draw from. */
	private static void castAsP2PaidByBackups(MainWindow mw, CardData summon, boolean backupsOnly) {
		fillP2Deck(mw, 2);
		mw.lastCastWasPaidByBackupsOnly = backupsOnly;
		castAsP2(mw, summon);
	}

	private static MainWindow beliasBoard() {
		MainWindow mw = new MainWindow();
		placeP2Forward(mw, makeForward("Mine", "Fire", 3, 7000));
		placeP1Forward(mw, makeForward("Theirs", "Water", 3, 7000));
		return mw;
	}

	@Test
	void beliasPumpsTheFormerAndBurnsTheLatter() {
		MainWindow mw = beliasBoard();
		castAsP2PaidByBackups(mw, makeSummon("Belias, the Gigas", "Fire", 3, BELIAS_22_014R), false);
		assertEquals(10000, mw.effectiveP2ForwardPower(0));
		assertTrue(mw.effectiveP2HasTrait(0, CardData.Trait.FIRST_STRIKE));
		assertTrue(mw.p1ForwardCards.isEmpty(), "7000 damage breaks a 7000 Forward");
		assertTrue(mw.gameState.getP2Hand().isEmpty(), "paid otherwise — no draw");
	}

	@Test
	void beliasDrawsWhenPaidByBackupsOnly() {
		MainWindow mw = beliasBoard();
		castAsP2PaidByBackups(mw, makeSummon("Belias, the Gigas", "Fire", 3, BELIAS_22_014R), true);
		assertEquals(1, mw.gameState.getP2Hand().size());
		assertEquals(10000, mw.effectiveP2ForwardPower(0), "and the rest still resolves");
	}

	// =========================================================================================
	// 22-027R Shiva: "All the Characters opponent controls lose all their abilities until the end
	// of the turn. If the CP paid to cast Shiva was only produced by Backups, also draw 1 card."
	// =========================================================================================

	private static final String SHIVA_22_027R = "All the Characters opponent controls lose all their abilities until "
			+ "the end of the turn. If the CP paid to cast Shiva was only produced by Backups, also draw 1 card.";

	@Test
	void shivaSilencesEveryCharacterTheyControl() {
		MainWindow mw = new MainWindow();
		CardData theirForward = makeForward("Their Forward", "Water", 3, 7000);
		CardData theirBackup  = makeBackup("Their Backup", "Water", 2);
		CardData theirMonster = makeMonster("Their Monster", "Water", 2);
		CardData mine         = makeForward("Mine", "Ice", 3, 7000);
		placeP1Forward(mw, theirForward);
		placeP1Backup(mw, theirBackup);
		placeP1Monster(mw, theirMonster);
		placeP2Forward(mw, mine);
		castAsP2PaidByBackups(mw, makeSummon("Shiva", "Ice", 3, SHIVA_22_027R), false);

		assertTrue(mw.lostAbilitiesCards.contains(theirForward));
		assertTrue(mw.lostAbilitiesCards.contains(theirBackup));
		assertTrue(mw.lostAbilitiesCards.contains(theirMonster));
		assertFalse(mw.lostAbilitiesCards.contains(mine), "opponent's only");
		assertTrue(mw.gameState.getP2Hand().isEmpty());
	}

	@Test
	void shivaDrawsWhenPaidByBackupsOnly() {
		MainWindow mw = new MainWindow();
		placeP1Forward(mw, makeForward("Theirs", "Water", 3, 7000));
		castAsP2PaidByBackups(mw, makeSummon("Shiva", "Ice", 3, SHIVA_22_027R), true);
		assertEquals(1, mw.gameState.getP2Hand().size());
	}

	// =========================================================================================
	// 22-037R Alexander: "Choose 1 Forward you control and 1 Forward opponent controls. During this
	// turn, the next damage dealt to the former is dealt to the latter instead. If the CP paid to
	// cast Alexander was only produced by Backups, also draw 1 card."
	// =========================================================================================

	private static final String ALEXANDER_22_037R = "Choose 1 Forward you control and 1 Forward opponent controls. "
			+ "During this turn, the next damage dealt to the former is dealt to the latter instead. If the CP paid to "
			+ "cast Alexander was only produced by Backups, also draw 1 card.";

	@Test
	void alexanderRedirectsTheNextDamageOnly() {
		MainWindow mw = new MainWindow();
		CardData mine   = makeForward("Mine", "Wind", 3, 9000);
		CardData theirs = makeForward("Theirs", "Water", 3, 9000);
		placeP2Forward(mw, mine);
		placeP1Forward(mw, theirs);
		castAsP2PaidByBackups(mw, makeSummon("Alexander", "Wind", 3, ALEXANDER_22_037R), false);

		GameContext p1 = mw.buildGameContext(true);
		p1.damageTarget(fwd(false, 0), 4000);
		assertEquals(4000, damageOn(mw, theirs), "the former's damage lands on the latter");
		assertEquals(0, (int) mw.p2ForwardDamage.get(0));

		p1.damageTarget(fwd(false, 0), 3000);
		assertEquals(3000, (int) mw.p2ForwardDamage.get(0), "the next damage only");
		assertEquals(4000, damageOn(mw, theirs));
		assertTrue(mw.gameState.getP2Hand().isEmpty());
	}

	@Test
	void alexanderDrawsWhenPaidByBackupsOnly() {
		MainWindow mw = new MainWindow();
		placeP2Forward(mw, makeForward("Mine", "Wind", 3, 9000));
		placeP1Forward(mw, makeForward("Theirs", "Water", 3, 9000));
		castAsP2PaidByBackups(mw, makeSummon("Alexander", "Wind", 3, ALEXANDER_22_037R), true);
		assertEquals(1, mw.gameState.getP2Hand().size());
	}

	// =========================================================================================
	// 22-057R Carbuncle: "Choose 1 Character you control. Dull it. Until the end of the turn, it
	// gains "This Character cannot be broken." and "This Character cannot be chosen by your
	// opponent's Summons or abilities." If the CP paid to cast Carbuncle was only produced by
	// Backups, also draw 1 card."
	// =========================================================================================

	private static final String CARBUNCLE_22_057R = "Choose 1 Character you control. Dull it. Until the end of the "
			+ "turn, it gains \"This Character cannot be broken.\" and \"This Character cannot be chosen by your "
			+ "opponent's Summons or abilities.\" If the CP paid to cast Carbuncle was only produced by Backups, also "
			+ "draw 1 card.";

	@Test
	void carbuncleDullsAndProtectsYourCharacter() {
		MainWindow mw = new MainWindow();
		CardData mine   = makeForward("Mine", "Earth", 3, 7000);
		CardData theirs = makeForward("Theirs", "Water", 3, 7000);
		placeP2Forward(mw, mine);
		placeP1Forward(mw, theirs);
		castAsP2PaidByBackups(mw, makeSummon("Carbuncle", "Earth", 3, CARBUNCLE_22_057R), false);

		assertEquals(CardState.DULL, mw.p2ForwardStates.get(0));
		assertTrue(mw.cannotBeChosenBySummons.contains(mine));
		assertTrue(mw.cannotBeChosenByAbilities.contains(mine));
		assertFalse(mw.cannotBeChosenBySummons.contains(theirs), "you control");
		assertEquals(CardState.ACTIVE, mw.p1ForwardStates.get(0));

		mw.buildGameContext(true).breakTarget(fwd(false, 0));
		assertEquals(List.of(mine), mw.p2ForwardCards, "cannot be broken");
		assertTrue(mw.gameState.getP2Hand().isEmpty());
	}

	@Test
	void carbuncleDrawsWhenPaidByBackupsOnly() {
		MainWindow mw = new MainWindow();
		placeP2Forward(mw, makeForward("Mine", "Earth", 3, 7000));
		castAsP2PaidByBackups(mw, makeSummon("Carbuncle", "Earth", 3, CARBUNCLE_22_057R), true);
		assertEquals(1, mw.gameState.getP2Hand().size());
	}

	// =========================================================================================
	// 22-076R Odin: "Choose 1 Forward or Monster of cost 3 or 4. Break it. If the CP paid to cast
	// Odin was only produced by Backups, also draw 1 card."
	// =========================================================================================

	private static final String ODIN_22_076R = "Choose 1 Forward or Monster of cost 3 or 4. Break it. If the CP paid "
			+ "to cast Odin was only produced by Backups, also draw 1 card.";

	@Test
	void odinBreaksAForwardOfCostThreeOrFourOnly() {
		MainWindow mw = new MainWindow();
		CardData two  = makeForward("Two", "Water", 2, 5000);
		CardData four = makeForward("Four", "Water", 4, 8000);
		CardData five = makeForward("Five", "Water", 5, 9000);
		placeP1Forward(mw, two);
		placeP1Forward(mw, four);
		placeP1Forward(mw, five);
		castAsP2PaidByBackups(mw, makeSummon("Odin", "Lightning", 3, ODIN_22_076R), false);
		assertTrue(mw.gameState.getP1BreakZone().contains(four));
		assertEquals(List.of(two, five), mw.p1ForwardCards);
		assertTrue(mw.gameState.getP2Hand().isEmpty());
	}

	@Test
	void odinBreaksAMonsterOfCostThreeAndDrawsWhenPaidByBackupsOnly() {
		MainWindow mw = new MainWindow();
		CardData monster = makeMonster("Theirs", "Water", 3);
		CardData two     = makeForward("Two", "Water", 2, 5000);
		placeP1Monster(mw, monster);
		placeP1Forward(mw, two);
		castAsP2PaidByBackups(mw, makeSummon("Odin", "Lightning", 3, ODIN_22_076R), true);
		assertTrue(mw.gameState.getP1BreakZone().contains(monster));
		assertEquals(List.of(two), mw.p1ForwardCards);
		assertEquals(1, mw.gameState.getP2Hand().size());
	}

	// =========================================================================================
	// 22-093R Anima (X): "Choose 1 Forward opponent controls. Remove it from the game. Your opponent
	// draws 1 card. If the CP paid to cast Anima (X) was only produced by Backups, also draw 1
	// card."
	// =========================================================================================

	private static final String ANIMA_22_093R = "Choose 1 Forward opponent controls. Remove it from the game. Your "
			+ "opponent draws 1 card. If the CP paid to cast Anima (X) was only produced by Backups, also draw 1 card.";

	/** P2 casts Anima at P1's lone Forward, P1 holding an empty hand over a 1-card deck. */
	private static MainWindow castAnima(CardData theirs, boolean backupsOnly) {
		MainWindow mw = new MainWindow();
		placeP1Forward(mw, theirs);
		mw.gameState.getP1Hand().clear();
		mw.gameState.getP1MainDeck().add(makeForward("Deck Card", "Water", 2, 5000));
		castAsP2PaidByBackups(mw, makeSummon("Anima (X)", "Water", 3, ANIMA_22_093R), backupsOnly);
		return mw;
	}

	@Test
	void animaRemovesTheirForwardAndTheyDraw() {
		CardData theirs = makeForward("Theirs", "Water", 5, 9000);
		MainWindow mw = castAnima(theirs, false);
		assertTrue(mw.gameState.getP1RemovedFromGame().contains(theirs));
		assertEquals(1, mw.gameState.getP1Hand().size(), "your opponent draws 1");
		assertTrue(mw.gameState.getP2Hand().isEmpty());
	}

	@Test
	void animaDrawsForYouTooWhenPaidByBackupsOnly() {
		MainWindow mw = castAnima(makeForward("Theirs", "Water", 5, 9000), true);
		assertEquals(1, mw.gameState.getP1Hand().size());
		assertEquals(1, mw.gameState.getP2Hand().size());
	}

	// =========================================================================================
	// 22-109H Eden: "The cost required to cast Eden is reduced by 1 for each Category VIII Character
	// you control. Choose up to 2 Forwards. Divide 30000 damage among them as you like. (Units must
	// be 1000.) This damage cannot be reduced."
	// =========================================================================================

	private static final String EDEN_22_109H = "The cost required to cast Eden is reduced by 1 for each Category VIII "
			+ "Character you control.[[br]]   Choose up to 2 Forwards. Divide 30000 damage among them as you like. "
			+ "(Units must be 1000.) This damage cannot be reduced.";

	@Test
	void edenDivides30000AmongTheirForwards() {
		MainWindow mw = new MainWindow();
		CardData big   = makeForward("Big", "Water", 7, 15000);
		CardData small = makeForward("Small", "Water", 5, 9000);
		CardData mine  = makeForward("Mine", "Light", 3, 7000);
		placeP1Forward(mw, big);
		placeP1Forward(mw, small);
		placeP2Forward(mw, mine);
		castAsP2(mw, makeSummonWithCostText("Eden", "Light", 8, EDEN_22_109H));
		assertTrue(mw.p1ForwardCards.isEmpty(), "30000 covers 15000 and 9000");
		assertEquals(List.of(mine), mw.p2ForwardCards);
		assertEquals(0, (int) mw.p2ForwardDamage.get(0));
	}

	@Test
	void edensDamageCannotBeReduced() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 9, 30000);
		placeP1Forward(mw, theirs);
		mw.buildGameContext(true).shieldActivePlayerDamageReduction(5000);
		castAsP2(mw, makeSummonWithCostText("Eden", "Light", 8, EDEN_22_109H));
		assertTrue(mw.gameState.getP1BreakZone().contains(theirs));
	}

	@Test
	void edenCostsOneLessForEachCategoryViiiCharacter() {
		MainWindow mw = new MainWindow();
		CardData eden = makeSummonWithCostText("Eden", "Light", 8, EDEN_22_109H);
		mw.gameState.getP1Hand().add(eden);
		placeP1Forward(mw, makeCategoryForward("Squall", "Ice", "VIII"));
		placeP1Forward(mw, makeCategoryForward("Rinoa", "Wind", "VIII"));
		placeP1Forward(mw, makeCategoryForward("Terra", "Fire", "VI"));
		assertEquals(6, mw.effectiveCastCost(eden));
	}

	// =========================================================================================
	// 23-016R Bahamut: "Choose 1 Forward with 9000 power or less and up to 1 Forward in your
	// opponent's Break Zone. Remove them from the game."
	// =========================================================================================

	private static final String BAHAMUT_23_016R = "Choose 1 Forward with 9000 power or less and up to 1 Forward in your "
			+ "opponent's Break Zone. Remove them from the game.";

	@Test
	void bahamutRemovesAForwardOf9000PowerOrLessAndOneFromTheirBreakZone() {
		MainWindow mw = new MainWindow();
		CardData weak   = makeForward("Weak", "Water", 4, 9000);
		CardData strong = makeForward("Strong", "Water", 5, 10000);
		placeP1Forward(mw, strong);
		placeP1Forward(mw, weak);
		CardData bzForward = makeForward("Fallen", "Water", 3, 7000);
		CardData bzBackup  = makeBackup("Fallen Backup", "Water", 2);
		mw.gameState.getP1BreakZone().add(bzBackup);
		mw.gameState.getP1BreakZone().add(bzForward);
		castAsP2(mw, makeSummon("Bahamut", "Fire", 5, BAHAMUT_23_016R));

		assertEquals(List.of(strong), mw.p1ForwardCards, "10000 power is over 9000");
		assertTrue(mw.gameState.getP1RemovedFromGame().contains(weak));
		assertTrue(mw.gameState.getP1RemovedFromGame().contains(bzForward));
		assertEquals(List.of(bzBackup), mw.gameState.getP1BreakZone(), "a Backup is not a Forward");
	}

	@Test
	void bahamutStillRemovesTheFieldForwardWithAnEmptyBreakZone() {
		MainWindow mw = new MainWindow();
		CardData weak = makeForward("Weak", "Water", 4, 9000);
		placeP1Forward(mw, weak);
		castAsP2(mw, makeSummon("Bahamut", "Fire", 5, BAHAMUT_23_016R));
		assertTrue(mw.gameState.getP1RemovedFromGame().contains(weak), "the Break Zone half is up to 1");
	}

	// =========================================================================================
	// 23-024R Shiva: "Choose 1 Forward. Dull it and Freeze it. During this turn, if it deals
	// damage to a Forward or a player, the damage becomes 0 instead."
	//
	// The outgoing mirror of Cockatrice's shield, and unspent for the same reason — the wording
	// names no number of hits. "To a Forward or a player" is every way the card can deal damage,
	// so the mark is read on all four paths: combat damage to a Forward, combat damage to the
	// player, and the same two from the Forward's own abilities. The last two share the mark that
	// 29-012H Neon's Runic already sets on a Stack entry; the combat ones cannot, because combat
	// has no ability source to ask about.
	// =========================================================================================

	private static final String SHIVA_SUMMON =
			"EX BURST Choose 1 Forward. Dull it and Freeze it. During this turn, if it deals "
			+ "damage to a Forward or a player, the damage becomes 0 instead.";

	/** Runs Shiva on the single P2 Forward and hands back the board it left. */
	private static MainWindow shivaOn(CardData victim) {
		MainWindow mw = new MainWindow();
		placeP2Forward(mw, victim);

		GameContext ctx = mw.buildGameContext(true);
		ctx.preloadTargets(List.of(fwd(false, 0)));
		Consumer<GameContext> effect = ActionResolver.parse(SHIVA_SUMMON, null);
		assertNotNull(effect, "the printed wording has to parse for the rest of this to mean anything");
		effect.accept(ctx);
		return mw;
	}

	@Test
	void shivaDullsFreezesAndDisarms() {
		CardData victim = makeForward("Victim", "Fire", 3, 7000);
		MainWindow mw = shivaOn(victim);

		assertEquals(CardState.DULL, mw.p2ForwardStates.get(0), "neither half may be dropped");
		assertTrue(mw.p2ForwardFrozen.get(0));
		assertTrue(mw.allOutgoingDmgZeroThisTurnSet.contains(victim));
	}

	@Test
	void andItsCombatDamageToAForwardBecomesZero() {
		CardData victim = makeForward("Victim", "Fire", 3, 7000);
		MainWindow mw = shivaOn(victim);
		CardData blocker = makeForward("Blocker", "Ice", 3, 8000);
		placeP1Forward(mw, blocker);

		assertEquals(0, mw.modifyOutgoingCombatDamage(false, 0, 7000, blocker));
	}

	@Test
	void andItsCombatDamageToThePlayerBecomesZero() {
		// The other half of "a Forward or a player". Player damage is counted in points rather
		// than in power, so zeroing it means the point is never dealt at all.
		CardData victim = makeForward("Victim", "Fire", 3, 7000);
		MainWindow mw = shivaOn(victim);

		assertEquals(0, mw.combatDamagePointsToOpponent(victim));
	}

	@Test
	void andTheDisarmIsNotSpentByTheFirstHit() {
		// The difference between this and the one-shot outgoing shield beside it: the card names
		// no number of hits, so the second attack deals nothing either.
		CardData victim = makeForward("Victim", "Fire", 3, 7000);
		MainWindow mw = shivaOn(victim);
		CardData blocker = makeForward("Blocker", "Ice", 3, 8000);
		placeP1Forward(mw, blocker);

		assertEquals(0, mw.modifyOutgoingCombatDamage(false, 0, 7000, blocker));
		assertEquals(0, mw.modifyOutgoingCombatDamage(false, 0, 7000, blocker));
	}

	@Test
	void andAnotherCopyOfTheSameCardIsUntouched() {
		// CardData is a record, so a second printing is equals() to the one that was chosen. The
		// mark is identity-keyed for exactly this reason.
		CardData victim = makeForward("Victim", "Fire", 3, 7000);
		MainWindow mw = shivaOn(victim);
		CardData twin = makeForward("Victim", "Fire", 3, 7000);
		placeP2Forward(mw, twin);

		assertEquals(1, mw.combatDamagePointsToOpponent(twin), "the twin still deals its point");
	}

	@Test
	void shivaIsDescribedByWhichWayItsShieldPoints() {
		assertEquals("ChooseCharacter / DullAndFreezeAndZeroAllOutgoing",
				ActionResolver.fullDescription(SHIVA_SUMMON, null));
	}

	// =========================================================================================
	// 23-039R Asura: "Activate all the Forwards you control. Until the end of the turn, all the
	// Forwards you control gain "This Forward cannot be returned to its owner's hand by your
	// opponent's Summons or abilities." and "The power of this Forward cannot be decreased by your
	// opponent's Summons or abilities.""
	// =========================================================================================

	private static final String ASURA_23_039R = "Activate all the Forwards you control. Until the end of the turn, "
			+ "all the Forwards you control gain \"This Forward cannot be returned to its owner's hand by your "
			+ "opponent's Summons or abilities.\" and \"The power of this Forward cannot be decreased by your "
			+ "opponent's Summons or abilities.\"";

	@Test
	void asuraActivatesAndProtectsYourForwards() {
		MainWindow mw = new MainWindow();
		placeP2Forward(mw, makeForward("Mine A", "Wind", 3, 7000));
		placeP2Forward(mw, makeForward("Mine B", "Wind", 3, 7000));
		placeP1Forward(mw, makeForward("Theirs", "Water", 3, 7000));
		dullP2Forward(mw, mw.p2ForwardCards.get(0));
		dullP2Forward(mw, mw.p2ForwardCards.get(1));
		dullP1Forward(mw, mw.p1ForwardCards.get(0));
		castAsP2(mw, makeSummon("Asura", "Wind", 1, ASURA_23_039R));

		for (int i = 0; i < 2; i++) {
			assertEquals(CardState.ACTIVE, mw.p2ForwardStates.get(i));
			assertTrue(mw.effectiveP2HasTrait(i, CardData.Trait.CANNOT_BE_RETURNED_TO_HAND_BY_OPP));
			assertTrue(mw.effectiveP2HasTrait(i, CardData.Trait.POWER_CANNOT_BE_DECREASED_BY_OPP));
		}
		assertEquals(CardState.DULL, mw.p1ForwardStates.get(0), "you control");
		assertFalse(mw.effectiveP1HasTrait(0, CardData.Trait.CANNOT_BE_RETURNED_TO_HAND_BY_OPP));
	}

	@Test
	void asurasForwardsResistTheirOpponentsBounceAndDebuff() {
		MainWindow mw = new MainWindow();
		placeP2Forward(mw, makeForward("Mine", "Wind", 3, 7000));
		castAsP2(mw, makeSummon("Asura", "Wind", 1, ASURA_23_039R));

		CardData debuff = makeSummon("Debuff", "Ice", 2, "Choose 1 Forward. It loses 3000 power until the end of the turn.");
		GameContext p1 = mw.buildGameContext(true);
		p1.preloadTargets(List.of(fwd(false, 0)));
		ActionResolver.parse(debuff.textEn(), debuff).accept(p1);
		assertEquals(7000, mw.effectiveP2ForwardPower(0));

		CardData bounce = makeSummon("Bounce", "Wind", 2, "Choose 1 Forward. Return it to its owner's hand.");
		p1 = mw.buildGameContext(true);
		p1.preloadTargets(List.of(fwd(false, 0)));
		ActionResolver.parse(bounce.textEn(), bounce).accept(p1);
		assertEquals(1, mw.p2ForwardCards.size());
	}

	// =========================================================================================
	// 23-064R Golem: "Choose 1 Forward. Reveal the top 3 cards of your deck. Add 1 card among them
	// to your hand and return the other cards to the bottom of your deck in any order. If you
	// added a Forward to your hand, deal the chosen Forward damage equal to the power of the added
	// Forward."
	//
	// The middle sentence on its own is the wording LOOK_TOP_DECK_ADD_TO_HAND_REST_BOTTOM claims,
	// and that parser declined the card outright rather than reveal and then drop the burn. The
	// Choose chain now reads all three sentences as one clause, so the amount comes from the card
	// the reveal put into hand — read off the copy in hand, whose power is the printed one.
	// =========================================================================================

	private static final String GOLEM_SUMMON =
			"Choose 1 Forward. Reveal the top 3 cards of your deck. Add 1 card among them to your "
			+ "hand and return the other cards to the bottom of your deck in any order. If you "
			+ "added a Forward to your hand, deal the chosen Forward damage equal to the power of "
			+ "the added Forward.";

	private static GameContext golemContext(ForwardTarget victim, CardData added) {
		GameContext ctx = mock(GameContext.class);
		when(ctx.consumePreloadedTargets()).thenReturn(null);
		when(ctx.cardAddedToHandByLook()).thenReturn(added);
		when(ctx.selectCharacters(anyInt(), anyBoolean(), anyBoolean(), anyBoolean(), any(), any(),
				anyInt(), any(), anyInt(), any(), anyBoolean(), anyBoolean(), anyBoolean(),
				any(), any(), any(), any(), anyBoolean(), any(), anyBoolean()))
				.thenReturn(List.of(victim));
		return ctx;
	}

	@Test
	void golemBurnsForThePowerOfTheForwardItAddedToHand() {
		ForwardTarget victim = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);
		GameContext ctx = golemContext(victim, makeForward("Behemoth", "Earth", 5, 9000));

		ActionResolver.parse(GOLEM_SUMMON, null).accept(ctx);

		ArgumentCaptor<LookConfig> config = ArgumentCaptor.forClass(LookConfig.class);
		verify(ctx).lookAtTopDeck(config.capture());
		assertEquals(3, config.getValue().count());
		assertEquals(LookConfig.LookAction.ADD_TO_HAND_REST_BOTTOM, config.getValue().action());
		assertTrue(config.getValue().reveal(), "\"Reveal\" makes the three cards public");

		verify(ctx).damageTarget(victim, 9000);
	}

	@Test
	void golemDealsNothingWhenTheAddedCardIsNotAForward() {
		ForwardTarget victim = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);
		GameContext ctx = golemContext(victim, makeBackup("Chocobo", "Earth", 2));

		ActionResolver.parse(GOLEM_SUMMON, null).accept(ctx);

		verify(ctx).lookAtTopDeck(any());
		verify(ctx, never()).damageTarget(any(), anyInt());
	}

	@Test
	void golemDealsNothingWhenTheLookAddedNoCardAtAll() {
		// An empty deck adds nothing, and "the power of the added Forward" then names no card.
		ForwardTarget victim = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);
		GameContext ctx = golemContext(victim, null);

		ActionResolver.parse(GOLEM_SUMMON, null).accept(ctx);

		verify(ctx, never()).damageTarget(any(), anyInt());
	}

	@Test
	void golemsThreeSentencesAreDescribedAsOneClause() {
		assertEquals("ChooseCharacter / RevealAddToHandIfForwardDamageAddedPower",
				ActionResolver.fullDescription(GOLEM_SUMMON, null));
	}

	// =========================================================================================
	// 23-080R Odin: "Before paying the cost to cast Odin, you can remove 6 Lightning cards in your
	// Break Zone from the game to reduce the cost required to cast Odin by 6. EX BURST Choose 1
	// Forward. Break it."
	// =========================================================================================

	private static final String ODIN_23_080R = "Before paying the cost to cast Odin, you can remove 6 Lightning cards "
			+ "in your Break Zone from the game to reduce the cost required to cast Odin by 6.[[br]]   [[ex]]EX "
			+ "BURST[[/]] Choose 1 Forward. Break it.";

	@Test
	void odinBreaksAForward() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 9, 15000);
		placeP1Forward(mw, theirs);
		castAsP2(mw, makeSummon("Odin", "Lightning", 9, ODIN_23_080R));
		assertTrue(mw.gameState.getP1BreakZone().contains(theirs));
	}

	@Test
	void odinsDiscountTakesSixLightningCardsOfAnyType() {
		CardData.AltBzRemovalReduction r = makeSummon("Odin", "Lightning", 9, ODIN_23_080R).altBzRemovalReduction();
		assertNotNull(r);
		assertEquals(6, r.count());
		assertEquals(6, r.reduction());
		assertTrue(r.admits(makeSummon("Ramuh", "Lightning", 3, "")));
		assertTrue(r.admits(makeBackup("Lightning Backup", "Lightning", 2)));
		assertFalse(r.admits(makeForward("Fire Forward", "Fire", 3, 7000)));
	}

	// =========================================================================================
	// 23-113R Leviathan: "Choose 1 card in your Damage Zone. Add it to your hand and draw 1 card.
	// Then, put 1 card from your hand into the Damage Zone (its EX Burst effect will not trigger)."
	// =========================================================================================

	private static final String LEVIATHAN_23_113R = "Choose 1 card in your Damage Zone. Add it to your hand and draw 1 "
			+ "card. Then, put 1 card from your hand into the Damage Zone (its EX Burst effect will not trigger).";

	@Test
	void leviathanSwapsADamageZoneCardAndDraws() {
		MainWindow mw = new MainWindow();
		CardData damage = makeForward("Damage", "Water", 5, 9000);
		CardData deck   = makeForward("Deck", "Water", 2, 5000);
		mw.gameState.getP2DamageZone().add(damage);
		mw.gameState.getP2MainDeck().add(deck);
		castAsP2(mw, makeSummon("Leviathan", "Water", 1, LEVIATHAN_23_113R));

		assertTrue(mw.gameState.getP2MainDeck().isEmpty(), "drew 1");
		assertEquals(1, mw.gameState.getP2DamageZone().size(), "one out, one in");
		assertEquals(1, mw.gameState.getP2Hand().size());
		assertEquals(Set.of(damage, deck), Set.of(mw.gameState.getP2Hand().get(0), mw.gameState.getP2DamageZone().get(0)));
	}

	// =========================================================================================
	// 24-008C Salamander (XII RW): "EX BURST Choose 1 Forward and up to 1 other Forward. Deal the
	// former 7000 damage. If you control 5 or more Backups, also deal the latter 5000 damage."
	// =========================================================================================

	private static final String SALAMANDER_24_008C = "[[ex]]EX BURST[[/]] Choose 1 Forward and up to 1 other Forward. "
			+ "Deal the former 7000 damage. If you control 5 or more Backups, also deal the latter 5000 damage.";

	/** P2 casts Salamander at P1's two 9000-power Forwards with {@code backups} Backups out. */
	private static List<Integer> salamanderDamageWith(int backups) {
		MainWindow mw = new MainWindow();
		for (int i = 0; i < backups; i++) placeP2Backup(mw, makeBackup("Backup " + i, "Fire", 2));
		placeP1Forward(mw, makeForward("Theirs A", "Water", 5, 9000));
		placeP1Forward(mw, makeForward("Theirs B", "Water", 5, 9000));
		castAsP2(mw, makeSummon("Salamander (XII RW)", "Fire", 3, SALAMANDER_24_008C));
		List<Integer> dealt = new ArrayList<>(mw.p1ForwardDamage);
		Collections.sort(dealt);
		return dealt;
	}

	@Test
	void salamanderDealsTheFormer7000Only() {
		assertEquals(List.of(0, 7000), salamanderDamageWith(4));
	}

	@Test
	void salamanderAlsoDealsTheLatter5000WithFiveBackups() {
		assertEquals(List.of(5000, 7000), salamanderDamageWith(5));
	}

	// =========================================================================================
	// 24-014H Bahamut: "Select 1 of the 2 following actions. "Choose 1 Forward. Deal it 20000
	// damage." "Deal 10000 damage to all the Forwards opponent controls. Remove the top card of your
	// deck from the game until there are only 3 cards left in the deck.""
	// =========================================================================================

	private static final String BAHAMUT_24_014H = "Select 1 of the 2 following actions.[[br]]\"Choose 1 Forward. Deal "
			+ "it 20000 damage.\"[[br]]\"Deal 10000 damage to all the Forwards opponent controls. Remove the top card "
			+ "of your deck from the game until there are only 3 cards left in the deck.\"";

	@Test
	void bahamutDeals20000ToAForward() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 9, 25000);
		placeP1Forward(mw, theirs);
		fillP2Deck(mw, 7);
		castAsP2Selecting(mw, makeSummon("Bahamut", "Fire", 5, BAHAMUT_24_014H), 0);
		assertEquals(20000, damageOn(mw, theirs));
		assertEquals(7, mw.gameState.getP2MainDeck().size(), "the first action mills nothing");
	}

	@Test
	void bahamutSweepsFor10000AndRemovesItsDeckDownToThree() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 7, 12000);
		CardData mine   = makeForward("Mine", "Fire", 7, 12000);
		placeP1Forward(mw, theirs);
		placeP2Forward(mw, mine);
		fillP2Deck(mw, 7);
		castAsP2Selecting(mw, makeSummon("Bahamut", "Fire", 5, BAHAMUT_24_014H), 1);

		assertEquals(10000, damageOn(mw, theirs));
		assertEquals(0, (int) mw.p2ForwardDamage.get(0), "opponent's only");
		assertEquals(3, mw.gameState.getP2MainDeck().size());
		assertEquals(4, mw.gameState.getP2RemovedFromGame().size());
	}

	@Test
	void bahamutRemovesNothingFromADeckOfThreeOrLess() {
		MainWindow mw = new MainWindow();
		placeP1Forward(mw, makeForward("Theirs", "Water", 7, 12000));
		fillP2Deck(mw, 2);
		castAsP2Selecting(mw, makeSummon("Bahamut", "Fire", 5, BAHAMUT_24_014H), 1);
		assertEquals(2, mw.gameState.getP2MainDeck().size());
		assertTrue(mw.gameState.getP2RemovedFromGame().isEmpty());
	}

	// =========================================================================================
	// 24-026H Zalera, the Death Seraph: "You can only cast Zalera, the Death Seraph during your
	// turn. Select 1 of the 2 following actions. "Your opponent selects 1 Forward of cost 5 or more
	// they control. Put it into the Break Zone. Your opponent discards 1 card." "Your opponent
	// selects 1 Forward of cost 4 or less they control. Put it into the Break Zone. Your opponent
	// discards 1 card.""
	//
	// Cast from P1's seat, so the CPU is the opponent who selects and discards.
	// =========================================================================================

	private static final String ZALERA_24_026H = "You can only cast Zalera, the Death Seraph during your turn.[[br]]"
			+ "Select 1 of the 2 following actions. [[br]]\"Your opponent selects 1 Forward of cost 5 or more they "
			+ "control. Put it into the Break Zone. Your opponent discards 1 card.\" [[br]]\"Your opponent selects 1 "
			+ "Forward of cost 4 or less they control. Put it into the Break Zone. Your opponent discards 1 card.\"";

	/** {@link #castAsP2Selecting} from P1's seat, for a Select whose options P1 makes no choice in. */
	private static void castAsP1Selecting(MainWindow mw, CardData summon, int... options) {
		GameContext ctx = spy(mw.buildGameContext(true));
		doAnswer(inv -> {
			List<String> offered = inv.getArgument(1);
			List<String> taken = new ArrayList<>();
			for (int option : options) taken.add(offered.get(option));
			return taken;
		}).when(ctx).chooseActions(any(), anyList(), anyInt(), anyBoolean());
		Consumer<GameContext> fn = ActionResolver.parse(summon.summonEffect(), summon);
		assertNotNull(fn, summon.name() + " parses");
		fn.accept(ctx);
	}

	/** P2 controls a cost-5 and a cost-4 Forward and holds two cards. */
	private static MainWindow zaleraBoard(CardData five, CardData four) {
		MainWindow mw = new MainWindow();
		placeP2Forward(mw, five);
		placeP2Forward(mw, four);
		mw.gameState.getP2Hand().add(makeForward("Hand A", "Water", 2, 5000));
		mw.gameState.getP2Hand().add(makeForward("Hand B", "Water", 3, 7000));
		return mw;
	}

	@Test
	void zaleraTakesTheirForwardOfCostFiveOrMoreAndACard() {
		CardData five = makeForward("Five", "Water", 5, 9000);
		CardData four = makeForward("Four", "Water", 4, 8000);
		MainWindow mw = zaleraBoard(five, four);
		castAsP1Selecting(mw, makeSummon("Zalera, the Death Seraph", "Ice", 4, ZALERA_24_026H), 0);
		assertEquals(List.of(four), mw.p2ForwardCards);
		assertTrue(mw.gameState.getP2BreakZone().contains(five));
		assertEquals(1, mw.gameState.getP2Hand().size());
	}

	@Test
	void zaleraTakesTheirForwardOfCostFourOrLessAndACard() {
		CardData five = makeForward("Five", "Water", 5, 9000);
		CardData four = makeForward("Four", "Water", 4, 8000);
		MainWindow mw = zaleraBoard(five, four);
		castAsP1Selecting(mw, makeSummon("Zalera, the Death Seraph", "Ice", 4, ZALERA_24_026H), 1);
		assertEquals(List.of(five), mw.p2ForwardCards);
		assertTrue(mw.gameState.getP2BreakZone().contains(four));
		assertEquals(1, mw.gameState.getP2Hand().size());
	}

	@Test
	void zaleraCanOnlyBeCastDuringYourTurn() {
		CastRestriction r = makeSummon("Zalera, the Death Seraph", "Ice", 4, ZALERA_24_026H).castRestriction();
		assertNotNull(r);
		assertTrue(r.yourTurnOnly());
	}

	// =========================================================================================
	// 24-035C Mateus (XII RW): "EX BURST Select 1 of the 2 following actions. "Choose 1 dull Forward
	// of cost 3 or less. Break it." "Choose 1 Forward. Dull it. Draw 1 card.""
	// =========================================================================================

	private static final String MATEUS_24_035C = "[[ex]]EX BURST[[/]] Select 1 of the 2 following actions.[[br]]"
			+ "\"Choose 1 dull Forward of cost 3 or less. Break it.\"[[br]]\"Choose 1 Forward. Dull it. Draw 1 card.\"";

	@Test
	void mateusBreaksADullForwardOfCostThreeOrLess() {
		MainWindow mw = new MainWindow();
		CardData dullThree   = makeForward("Dull Three", "Water", 3, 7000);
		CardData activeThree = makeForward("Active Three", "Water", 3, 7000);
		CardData dullFour    = makeForward("Dull Four", "Water", 4, 8000);
		placeP1Forward(mw, activeThree);
		placeP1Forward(mw, dullFour);
		placeP1Forward(mw, dullThree);
		dullP1Forward(mw, dullFour);
		dullP1Forward(mw, dullThree);
		castAsP2Selecting(mw, makeSummon("Mateus (XII RW)", "Ice", 2, MATEUS_24_035C), 0);
		assertTrue(mw.gameState.getP1BreakZone().contains(dullThree));
		assertEquals(List.of(activeThree, dullFour), mw.p1ForwardCards);
	}

	@Test
	void mateusDullsAForwardAndDraws() {
		MainWindow mw = new MainWindow();
		placeP1Forward(mw, makeForward("Theirs", "Water", 3, 7000));
		fillP2Deck(mw, 1);
		castAsP2Selecting(mw, makeSummon("Mateus (XII RW)", "Ice", 2, MATEUS_24_035C), 1);
		assertEquals(CardState.DULL, mw.p1ForwardStates.get(0));
		assertEquals(1, mw.gameState.getP2Hand().size());
	}

	// =========================================================================================
	// 24-038H Valefor: "You can only cast Valefor during your Main Phase. If you cast Valefor, you
	// may pay 《X》 as an extra cost. Reveal the top 7 cards of your deck. Play 1 Wind Character of
	// cost X or less among them onto the field. Then, shuffle the other cards revealed and return
	// them to the bottom of your deck."
	//
	// X travels on the Stack entry with the extra cost, so both cases are cast there.
	// =========================================================================================

	private static final String VALEFOR_24_038H = "You can only cast Valefor during your Main Phase.[[br]]If you cast "
			+ "Valefor, you may pay 《X》 as an extra cost.[[br]]Reveal the top 7 cards of your deck. Play 1 Wind "
			+ "Character of cost X or less among them onto the field. Then, shuffle the other cards revealed and "
			+ "return them to the bottom of your deck.";

	/** P2 casts Valefor paying X = {@code x} (unpaid at 0) over an 8-card deck; P1 passes. */
	private static MainWindow castValefor(int x) {
		MainWindow mw = new MainWindow();
		for (CardData c : List.of(
				makeForward("Wind Four", "Wind", 4, 8000),
				makeBackup("Wind Three", "Wind", 3),
				makeForward("Fire Two", "Fire", 2, 5000),
				makeForward("Wind Five", "Wind", 5, 9000),
				makeForward("Water One", "Water", 1, 3000),
				makeForward("Ice Two", "Ice", 2, 5000),
				makeForward("Earth Three", "Earth", 3, 7000),
				makeForward("Unrevealed", "Wind", 1, 3000)))
			mw.gameState.getP2MainDeck().add(c);
		CardData valefor = makeSummon("Valefor", "Wind", 1, VALEFOR_24_038H);
		mw.gameState.getIdentity().put(valefor, false);
		mw.pushSummonOnStack(valefor, false, 0, x, x > 0, null, false);
		mw.passStackPriority();
		return mw;
	}

	@Test
	void valeforPlaysAWindCharacterOfCostXOrLess() {
		MainWindow mw = castValefor(3);
		assertEquals("Wind Three", Arrays.stream(mw.p2BackupCards).filter(c -> c != null)
				.map(CardData::name).findFirst().orElse(null), "Wind, and cost 3 is X or less");
		assertTrue(mw.p2ForwardCards.isEmpty(), "Wind Four and Wind Five cost more than X");
		assertEquals(7, mw.gameState.getP2MainDeck().size());
		assertEquals("Unrevealed", mw.gameState.getP2MainDeck().peekFirst().name(), "the other six went under it");
	}

	@Test
	void valeforWithoutXPlaysNothing() {
		MainWindow mw = castValefor(0);
		assertTrue(mw.p2ForwardCards.isEmpty());
		assertTrue(Arrays.stream(mw.p2BackupCards).allMatch(c -> c == null), "X is 0");
		assertEquals(8, mw.gameState.getP2MainDeck().size());
	}

	@Test
	void valeforCanOnlyBeCastDuringYourMainPhase() {
		CastRestriction r = makeSummon("Valefor", "Wind", 1, VALEFOR_24_038H).castRestriction();
		assertNotNull(r);
		assertTrue(r.mainPhaseOnly());
	}

	// =========================================================================================
	// 24-054C Wing Wraith: "EX BURST Choose 1 Forward opponent controls. Deal it 3000 damage. Search
	// for 1 Card Name Wing Wraith and add it to your hand."
	// =========================================================================================

	private static final String WING_WRAITH_24_054C = "[[ex]]EX BURST[[/]] Choose 1 Forward opponent controls. Deal it "
			+ "3000 damage. Search for 1 Card Name Wing Wraith and add it to your hand.";

	@Test
	void wingWraithDeals3000AndFetchesAnother() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 3, 7000);
		CardData mine   = makeForward("Mine", "Wind", 3, 7000);
		placeP1Forward(mw, theirs);
		placeP2Forward(mw, mine);
		CardData wraith = makeSummon("Wing Wraith", "Wind", 1, WING_WRAITH_24_054C);
		mw.gameState.getP2MainDeck().add(makeForward("Other", "Wind", 1, 3000));
		mw.gameState.getP2MainDeck().add(wraith);
		castAsP2(mw, makeSummon("Wing Wraith", "Wind", 1, WING_WRAITH_24_054C));

		assertEquals(3000, damageOn(mw, theirs));
		assertEquals(0, (int) mw.p2ForwardDamage.get(0), "opponent controls");
		assertEquals(List.of(wraith), mw.gameState.getP2Hand());
	}

	// =========================================================================================
	// 24-056C Cu Sith, second option: "Choose 1 Forward opponent controls. If it deals damage to
	// you or a Forward this turn, the damage becomes 0 instead."
	//
	// The same effect 23-024R Shiva prints as "damage to a Forward or a player", spelled from the
	// caster's side: "you" is the only player a chosen Forward deals damage to. Read by the same
	// branch and marked the same way, so nothing the Forward deals lands.
	// =========================================================================================

	private static final String CU_SITH_OPTION =
			"Choose 1 Forward opponent controls. If it deals damage to you or a Forward this turn, "
			+ "the damage becomes 0 instead.";

	@Test
	void cuSithDisarmsTheirForwardEntirely() {
		MainWindow mw = new MainWindow();
		CardData victim = makeForward("Theirs", "Fire", 3, 7000);
		placeP2Forward(mw, victim);
		CardData blocker = makeForward("Ally", "Water", 3, 9000);
		placeP1Forward(mw, blocker);

		GameContext ctx = mw.buildGameContext(true);
		ctx.preloadTargets(List.of(fwd(false, 0)));
		Consumer<GameContext> effect = ActionResolver.parse(CU_SITH_OPTION, null);
		assertNotNull(effect, "the printed wording has to parse for the rest of this to mean anything");
		effect.accept(ctx);

		assertTrue(mw.allOutgoingDmgZeroThisTurnSet.contains(victim),
				"\"to you or a Forward\" is the same reach as \"to a Forward or a player\"");
		assertEquals(0, mw.modifyOutgoingCombatDamage(false, 0, 7000, blocker));
		assertEquals(0, mw.combatDamagePointsToOpponent(victim));
	}

	@Test
	void cuSithNamesBothOfItsOptions() {
		String summon = "EX BURST Select 1 of the 2 following actions. "
				+ "\"Choose 1 Backup you control. Remove it from the game. Draw 1 card.\" "
				+ "\"" + CU_SITH_OPTION + "\"";
		assertEquals("SelectFollowingActions(1 of 2: ChooseCharacter / RemoveFromGame + DrawCards "
						+ "| ChooseCharacter / ZeroAllOutgoingDamage)",
				ActionResolver.fullDescription(summon, null));
	}

	@Test
	void cuSithRemovesYourBackupAndDraws() {
		String summon = "[[ex]]EX BURST[[/]] Select 1 of the 2 following actions.[[br]]\"Choose 1 Backup you control. "
				+ "Remove it from the game. Draw 1 card.\"[[br]]\"" + CU_SITH_OPTION + "\"";
		MainWindow mw = new MainWindow();
		CardData mine = makeBackup("Mine", "Earth", 2);
		placeP2Backup(mw, mine);
		placeP1Backup(mw, makeBackup("Theirs", "Water", 2));
		fillP2Deck(mw, 1);
		castAsP2Selecting(mw, makeSummon("Cu Sith", "Earth", 2, summon), 0);
		assertTrue(mw.gameState.getP2RemovedFromGame().contains(mine));
		assertEquals(1, mw.gameState.getP2Hand().size());
		assertNotNull(mw.p1BackupCards[0], "you control");
	}

	// =========================================================================================
	// 24-073H Valigarmanda: "Choose 1 Forward. Dull it and Freeze it. It loses 9000 power until the
	// end of the turn. Deal it 9000 damage."
	// =========================================================================================

	private static final String VALIGARMANDA_24_073H = "Choose 1 Forward. Dull it and Freeze it. It loses 9000 power "
			+ "until the end of the turn. Deal it 9000 damage.";

	@Test
	void valigarmandaBreaksAForwardOf18000Power() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 9, 18000);
		placeP1Forward(mw, theirs);
		castAsP2(mw, makeSummon("Valigarmanda", "Lightning", 4, VALIGARMANDA_24_073H));
		assertTrue(mw.gameState.getP1BreakZone().contains(theirs), "9000 left, 9000 dealt");
	}

	@Test
	void valigarmandaDullsFreezesAndWeakensWhatSurvives() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 10, 20000);
		placeP1Forward(mw, theirs);
		castAsP2(mw, makeSummon("Valigarmanda", "Lightning", 4, VALIGARMANDA_24_073H));
		assertEquals(CardState.DULL, mw.p1ForwardStates.get(0));
		assertTrue(mw.p1ForwardFrozen.get(0));
		assertEquals(11000, mw.effectiveP1ForwardPower(0));
		assertEquals(9000, damageOn(mw, theirs));
	}

	// =========================================================================================
	// 24-074C Odin: "EX BURST Choose 1 Forward opponent controls. Break it. Remove as many cards from
	// the top of your deck from the game as the CP required to cast the chosen Forward."
	// =========================================================================================

	private static final String ODIN_24_074C = "[[ex]]EX BURST[[/]] Choose 1 Forward opponent controls. Break it. "
			+ "Remove as many cards from the top of your deck from the game as the CP required to cast the chosen "
			+ "Forward.";

	@Test
	void odinBreaksTheirForwardAndRemovesItsCostFromYourDeck() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 4, 8000);
		CardData mine   = makeForward("Mine", "Lightning", 2, 5000);
		placeP1Forward(mw, theirs);
		placeP2Forward(mw, mine);
		fillP2Deck(mw, 6);
		castAsP2(mw, makeSummon("Odin", "Lightning", 5, ODIN_24_074C));

		assertTrue(mw.gameState.getP1BreakZone().contains(theirs));
		assertEquals(List.of(mine), mw.p2ForwardCards, "opponent controls");
		assertEquals(4, mw.gameState.getP2RemovedFromGame().size(), "cost 4");
		assertEquals(2, mw.gameState.getP2MainDeck().size());
	}

	@Test
	void theSameBranchReadsKylmasTwoCardsPerCp() {
		// 27-078R Kylma's action ability prints the per-CP form; the removal used to find() its
		// opening words and take a flat 2.
		MainWindow mw = new MainWindow();
		placeP1Forward(mw, makeForward("Theirs", "Water", 3, 7000));
		fillP2Deck(mw, 8);
		ActionResolver.parse("Choose 1 Forward. Break it. Remove the top 2 cards of your deck from the game for each "
				+ "CP required to cast the chosen Forward.", makeBackup("Kylma", "Lightning", 2))
				.accept(mw.buildGameContext(false));
		assertEquals(6, mw.gameState.getP2RemovedFromGame().size(), "2 for each of 3 CP");
	}

	// =========================================================================================
	// 24-099C Siren (MOBIUS): "EX BURST Choose 1 Forward. During this turn, the next damage dealt to
	// it becomes 0 instead. Draw 1 card."
	// =========================================================================================

	private static final String SIREN_24_099C = "[[ex]]EX BURST[[/]] Choose 1 Forward. During this turn, the next "
			+ "damage dealt to it becomes 0 instead. Draw 1 card.";

	@Test
	void sirenShieldsYourForwardFromItsNextDamageAndDraws() {
		MainWindow mw = new MainWindow();
		placeP2Forward(mw, makeForward("Mine", "Water", 3, 9000));
		placeP1Forward(mw, makeForward("Theirs", "Water", 3, 9000));
		fillP2Deck(mw, 1);
		castAsP2(mw, makeSummon("Siren (MOBIUS)", "Water", 3, SIREN_24_099C));
		assertEquals(1, mw.gameState.getP2Hand().size());
		assertTrue(mw.nextIncomingDmgZeroSet.contains(mw.p2ForwardCards.get(0)), "the CPU shields its own");

		GameContext p1 = mw.buildGameContext(true);
		p1.damageTarget(fwd(false, 0), 5000);
		assertEquals(0, (int) mw.p2ForwardDamage.get(0), "the CPU shields its own");
		p1.damageTarget(fwd(false, 0), 4000);
		assertEquals(4000, (int) mw.p2ForwardDamage.get(0), "the next damage only");
	}

	// =========================================================================================
	// 24-106H Leviathan: "If you cast Leviathan, you may remove 4 Card Name Leviathan in your Break
	// Zone from the game as an extra cost. Your opponent selects 1 Forward or Monster they control.
	// Put it into the Break Zone. If you paid the extra cost, your opponent selects up to 2 Forwards
	// and/or Monsters they control instead (select as many as possible). Put them into the Break
	// Zone."
	//
	// Cast by P1 on the Stack, which carries the extra cost, so the CPU is the one selecting.
	// =========================================================================================

	private static final String LEVIATHAN_24_106H = "If you cast Leviathan, you may remove 4 Card Name Leviathan in "
			+ "your Break Zone from the game as an extra cost.[[br]]Your opponent selects 1 Forward or Monster they "
			+ "control. Put it into the Break Zone. If you paid the extra cost, your opponent selects up to 2 Forwards "
			+ "and/or Monsters they control instead (select as many as possible). Put them into the Break Zone.";

	private static MainWindow castLeviathan24(boolean paidExtra) {
		MainWindow mw = new MainWindow();
		placeP2Forward(mw, makeForward("Their Forward", "Water", 3, 7000));
		placeP2Forward(mw, makeForward("Their Other", "Water", 3, 7000));
		CardData monster = makeMonster("Their Monster", "Water", 2);
		mw.gameState.getIdentity().put(monster, false);
		mw.placeP2CardInMonsterZone(monster);
		CardData leviathan = makeSummon("Leviathan", "Water", 3, LEVIATHAN_24_106H);
		mw.gameState.getIdentity().put(leviathan, true);
		mw.pushSummonOnStack(leviathan, true, 0, 0, paidExtra, null, false);
		mw.passStackPriority();
		return mw;
	}

	@Test
	void leviathansExtraCostIsFourLeviathansFromTheBreakZone() {
		assertEquals(ExtraCost.bzRemoveCardName(4, "Leviathan"),
				makeSummon("Leviathan", "Water", 3, LEVIATHAN_24_106H).extraCost());
	}

	@Test
	void leviathanTakesOneForwardOrMonsterUnpaid() {
		MainWindow mw = castLeviathan24(false);
		assertEquals(2, mw.p2ForwardCards.size() + mw.p2MonsterCards.size());
		assertEquals(1, mw.gameState.getP2BreakZone().size());
	}

	@Test
	void leviathanTakesTwoWithItsExtraCost() {
		MainWindow mw = castLeviathan24(true);
		assertEquals(1, mw.p2ForwardCards.size() + mw.p2MonsterCards.size());
		assertEquals(2, mw.gameState.getP2BreakZone().size());
	}

	// =========================================================================================
	// 25-004H Ifrit:an alternate cast cost paid by removing a Backup from the game rather than
	// by Crystals. The cost sentence has to be recognised for its own sake AND so summonEffect()
	// strips it — while it stayed in the effect text the resolver matched the combined string and
	// the actual summon effect never ran.
	// =========================================================================================

	private static final String IFRIT_TEXT =
			"Before paying the cost to cast Ifrit, you can remove 1 Fire Backup you control from the game "
			+ "to reduce the cost required to cast Ifrit by 2.[[br]]"
			+ "Choose 1 Forward and up to 1 other Forward. Deal the former 9000 damage and deal the latter 4000 damage.";

	@Test
	void ifritAlternateCostRemovesAFireBackupAndReducesTheCost() {
		CardData ifrit = makeSummon("Ifrit", "Fire", 4, IFRIT_TEXT);
		CardData.AltFieldRemoval removal = ifrit.altFieldRemoval();
		assertNotNull(removal, "the remove-from-game alternate cost should parse");
		assertEquals(1, removal.count());
		assertEquals("Fire", removal.element());
		assertEquals("Backup", removal.type());
		assertEquals(0, ifrit.altCrystalCost(), "this cost is paid with a Backup, not Crystals");
		assertEquals(List.of("Fire", "Fire"), ifrit.altCpElements(), "cost 4 reduced by 2");
	}

	// A name containing a comma is why this pattern cannot reuse the Crystal variant's [^,]+.
	@Test
	void alternateRemovalCostParsesThroughCommasInTheCardName() {
		String text = "Before paying the cost to cast Mateus, the Corrupt, you can remove 1 Ice Backup you control "
				+ "from the game to reduce the cost required to cast Mateus, the Corrupt by 2.[[br]]"
				+ "Choose 1 dull Forward and up to 1 other Forward. Break the former, dull and Freeze the latter.";
		CardData mateus = makeSummon("Mateus, the Corrupt", "Ice", 4, text);
		assertNotNull(mateus.altFieldRemoval(), "a comma in the card name must not break the match");
		assertEquals("Ice", mateus.altFieldRemoval().element());
		assertEquals(List.of("Ice", "Ice"), mateus.altCpElements());
	}

	@Test
	void ifritSummonEffectDropsTheCostSentence() {
		CardData ifrit = makeSummon("Ifrit", "Fire", 4, IFRIT_TEXT);
		String effect = ifrit.summonEffect();
		assertFalse(effect.contains("Before paying"), "the cost sentence is not part of the effect");
		assertEquals("Choose 1 Forward and up to 1 other Forward. "
				+ "Deal the former 9000 damage and deal the latter 4000 damage.", effect);
		assertNotNull(ActionResolver.parse(effect, ifrit), "the remaining effect should parse");
		assertEquals("ChooseFormerLatter", ActionResolver.fullDescription(effect, ifrit));
	}

	// The neighbouring "remove … from the game" costs are deliberately out of this pattern's reach:
	// each removes from a different place or with a different shape, and claiming them here would
	// report a Backup removal the player never agreed to.
	@Test
	void alternateRemovalCostIgnoresTheBreakZoneAndInsteadOfPayingForms() {
		CardData odin = makeSummon("Odin", "Lightning", 6,
				"Before paying the cost to cast Odin, you can remove 5 Lightning cards in your Break Zone "
				+ "from the game to reduce the cost required to cast Odin by 4.");
		assertNull(odin.altFieldRemoval(), "Break Zone removal is a different cost");

		CardData vayne = makeSummon("Vayne", "Lightning", 5,
				"Before paying the cost to cast Vayne, you can remove any number of active Backups you control "
				+ "from the game to reduce the cost required to cast Vayne by 1 for each Backup removed.");
		assertNull(vayne.altFieldRemoval(), "a variable count is not this fixed-count cost");

		CardData sonon = makeSummon("Sonon", "Earth", 3,
				"You can remove 1 Earth Backup you control and 1 Lightning Backup you control from the game "
				+ "(instead of paying the CP cost) to cast Sonon.");
		assertNull(sonon.altFieldRemoval(), "instead-of-paying is not a cost reduction");
	}

	@Test
	void ifritDealsNineThousandToTheFormerAndFourThousandToTheLatter() {
		CardData ifrit = makeSummon("Ifrit", "Fire", 4, IFRIT_TEXT);
		Consumer<GameContext> fn = ActionResolver.parse(ifrit.summonEffect(), ifrit);
		assertNotNull(fn);

		GameContext ctx = mock(GameContext.class);
		when(ctx.consumePreloadedTargets()).thenReturn(null);
		ForwardTarget former = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);
		ForwardTarget latter = new ForwardTarget(false, 1, ForwardTarget.CardZone.FORWARD);
		// "up to 1 OTHER Forward" excludes the first by name, so the former must be resolvable.
		when(ctx.p2Forward(0)).thenReturn(makeForward("Former", "Fire", 3, 7000));
		when(ctx.selectCharacters(anyInt(), anyBoolean(), anyBoolean(), anyBoolean(), any(), any(),
				anyInt(), any(), anyInt(), any(), anyBoolean(), anyBoolean(), anyBoolean(),
				any(), any(), any(), any(), anyBoolean(), any(), anyBoolean()))
				.thenReturn(List.of(former)).thenReturn(List.of(latter));

		fn.accept(ctx);
		verify(ctx).damageTarget(former, 9000);
		verify(ctx).damageTarget(latter, 4000);
	}

	// The second target is "up to 1", so declining it must still resolve the first.
	@Test
	void ifritStillDamagesTheFormerWhenNoSecondForwardIsChosen() {
		CardData ifrit = makeSummon("Ifrit", "Fire", 4, IFRIT_TEXT);
		Consumer<GameContext> fn = ActionResolver.parse(ifrit.summonEffect(), ifrit);
		assertNotNull(fn);

		GameContext ctx = mock(GameContext.class);
		when(ctx.consumePreloadedTargets()).thenReturn(null);
		ForwardTarget former = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);
		when(ctx.p2Forward(0)).thenReturn(makeForward("Former", "Fire", 3, 7000));
		when(ctx.selectCharacters(anyInt(), anyBoolean(), anyBoolean(), anyBoolean(), any(), any(),
				anyInt(), any(), anyInt(), any(), anyBoolean(), anyBoolean(), anyBoolean(),
				any(), any(), any(), any(), anyBoolean(), any(), anyBoolean()))
				.thenReturn(List.of(former)).thenReturn(List.of());

		fn.accept(ctx);
		verify(ctx).damageTarget(former, 9000);
		verify(ctx, never()).damageTarget(any(), eq(4000));
	}

	@Test
	void altFieldRemovalCandidatesOffersOnlyMatchingElementBackups() {
		MainWindow mw = new MainWindow();
		CardData ifrit = makeSummon("Ifrit", "Fire", 4, IFRIT_TEXT);

		mw.p1BackupCards[0] = makeBackup("Fire Guy", "Fire", 2);
		mw.p1BackupCards[1] = makeBackup("Ice Guy", "Ice", 2);
		mw.p1BackupCards[2] = makeBackup("Other Fire Guy", "Fire", 3);

		assertEquals(List.of(0, 2), mw.altFieldRemovalCandidates(ifrit.altFieldRemoval()),
				"only Fire Backups can pay this cost");
	}

	// Every Opus 25 Summon opens with the same Backup removal, so each of the sections below casts
	// from its full printed text: the cost sentence has to come off before the effect resolves.
	@Test
	void everyOpus25SummonReadsItsBackupRemovalAndCastsWithoutIt() {
		record Printing(CardData summon, String element) {}
		List<Printing> printings = List.of(
				new Printing(makeSummon("Ifrit", "Fire", 4, IFRIT_TEXT), "Fire"),
				new Printing(makeSummon("Mateus, the Corrupt", "Ice", 4, MATEUS_25_030H), "Ice"),
				new Printing(makeSummon("Chocobo Chick (VII)", "Wind", 3, CHOCOBO_CHICK_25_040H), "Wind"),
				new Printing(makeSummon("Atomos", "Earth", 3, ATOMOS_25_053H), "Earth"),
				new Printing(makeSummon("Adrammelech, the Wroth", "Lightning", 5, ADRAMMELECH_25_083H), "Lightning"),
				new Printing(makeSummon("Famfrit, the Darkening Cloud", "Water", 4,
						OPUS_25_BACKUP_REMOVAL.formatted("Water", "Famfrit, the Darkening Cloud") + FAMFRIT_25_088H),
						"Water"));
		for (Printing p : printings) {
			CardData summon = p.summon();
			CardData.AltFieldRemoval removal = summon.altFieldRemoval();
			assertNotNull(removal, summon.name());
			assertEquals(1, removal.count(), summon.name());
			assertEquals(p.element(), removal.element(), summon.name());
			assertEquals("Backup", removal.type(), summon.name());
			assertEquals(summon.cost() - 2, summon.altCpElements().size(), summon.name() + " is reduced by 2");
			assertFalse(summon.summonEffect().contains("Before paying"), summon.name());
		}
	}

	/** The Opus 25 cost sentence, for an element and a card name. */
	private static final String OPUS_25_BACKUP_REMOVAL =
			"Before paying the cost to cast %2$s, you can remove 1 %1$s Backup you control from the game "
			+ "to reduce the cost required to cast %2$s by 2.[[br]]";

	// =========================================================================================
	// 25-030H Mateus, the Corrupt: "Choose 1 dull Forward and up to 1 other Forward. Break the
	// former, dull and Freeze the latter."
	// =========================================================================================

	private static final String MATEUS_25_030H = OPUS_25_BACKUP_REMOVAL.formatted("Ice", "Mateus, the Corrupt")
			+ "Choose 1 dull Forward and up to 1 other Forward. Break the former, dull and Freeze the latter.";

	@Test
	void mateusBreaksTheDullForwardAndFreezesTheOther() {
		MainWindow mw = new MainWindow();
		CardData dull   = makeForward("Dull One", "Water", 5, 9000);
		CardData active = makeForward("Active One", "Water", 5, 9000);
		placeP1Forward(mw, dull);
		placeP1Forward(mw, active);
		dullP1Forward(mw, dull);
		castAsP2(mw, makeSummon("Mateus, the Corrupt", "Ice", 4, MATEUS_25_030H));

		assertTrue(mw.gameState.getP1BreakZone().contains(dull), "the former must be dull");
		assertEquals(List.of(active), mw.p1ForwardCards);
		assertEquals(CardState.DULL, mw.p1ForwardStates.get(0));
		assertTrue(mw.p1ForwardFrozen.get(0));
	}

	@Test
	void mateusNeverBreaksAnActiveForward() {
		MainWindow mw = new MainWindow();
		CardData active = makeForward("Active One", "Water", 5, 9000);
		placeP1Forward(mw, active);
		castAsP2(mw, makeSummon("Mateus, the Corrupt", "Ice", 4, MATEUS_25_030H));

		assertEquals(List.of(active), mw.p1ForwardCards, "the former must be dull");
	}

	// =========================================================================================
	// 25-040H Chocobo Chick (VII): "Deal 2000 damage to all the Forwards opponent controls. Then,
	// you may play 1 Forward of cost 2 or less from your hand onto the field."
	// =========================================================================================

	private static final String CHOCOBO_CHICK_25_040H = OPUS_25_BACKUP_REMOVAL.formatted("Wind", "Chocobo Chick (VII)")
			+ "Deal 2000 damage to all the Forwards opponent controls. "
			+ "Then, you may play 1 Forward of cost 2 or less from your hand onto the field.";

	@Test
	void chocoboChickSweepsTheirForwardsAndPlaysACheapForward() {
		MainWindow mw = new MainWindow();
		CardData small = makeForward("Small", "Water", 1, 2000);
		CardData big   = makeForward("Big", "Water", 4, 8000);
		CardData mine  = makeForward("Mine", "Wind", 3, 5000);
		placeP1Forward(mw, small);
		placeP1Forward(mw, big);
		placeP2Forward(mw, mine);
		CardData cheap  = makeForward("Cheap", "Wind", 2, 5000);
		CardData costly = makeForward("Costly", "Wind", 3, 7000);
		mw.gameState.getP2Hand().add(costly);
		mw.gameState.getP2Hand().add(cheap);
		castAsP2(mw, makeSummon("Chocobo Chick (VII)", "Wind", 3, CHOCOBO_CHICK_25_040H));

		assertTrue(mw.gameState.getP1BreakZone().contains(small));
		assertEquals(2000, damageOn(mw, big));
		assertEquals(0, (int) mw.p2ForwardDamage.get(mw.p2ForwardCards.indexOf(mine)), "opponent controls");
		assertTrue(mw.p2ForwardCards.contains(cheap), "cost 2 or less");
		assertEquals(List.of(costly), mw.gameState.getP2Hand());
	}

	// =========================================================================================
	// 25-053H Atomos: "Choose 1 Forward. Deal it 1000 damage for each card in your Break Zone."
	// =========================================================================================

	private static final String ATOMOS_25_053H = OPUS_25_BACKUP_REMOVAL.formatted("Earth", "Atomos")
			+ "Choose 1 Forward. Deal it 1000 damage for each card in your Break Zone.";

	@Test
	void atomosDealsAThousandPerCardInTheCastersBreakZone() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 6, 10000);
		placeP1Forward(mw, theirs);
		for (int i = 0; i < 4; i++) mw.gameState.getP2BreakZone().add(makeForward("Gone " + i, "Earth", 2, 5000));
		for (int i = 0; i < 3; i++) mw.gameState.getP1BreakZone().add(makeForward("Theirs Gone " + i, "Water", 2, 5000));
		castAsP2(mw, makeSummon("Atomos", "Earth", 3, ATOMOS_25_053H));

		assertEquals(4000, damageOn(mw, theirs), "your Break Zone, not theirs");
	}

	// =========================================================================================
	// 25-083H Adrammelech, the Wroth: "Deal 7000 damage to all the Forwards opponent controls.
	// Adrammelech, the Wroth deals you 1 point of damage."
	// =========================================================================================

	private static final String ADRAMMELECH_25_083H = OPUS_25_BACKUP_REMOVAL.formatted("Lightning", "Adrammelech, the Wroth")
			+ "Deal 7000 damage to all the Forwards opponent controls. "
			+ "Adrammelech, the Wroth deals you 1 point of damage.";

	@Test
	void adrammelechSweepsTheirForwardsAndDamagesItsCaster() {
		MainWindow mw = new MainWindow();
		CardData weak   = makeForward("Weak", "Water", 3, 7000);
		CardData strong = makeForward("Strong", "Water", 5, 9000);
		CardData mine   = makeForward("Mine", "Lightning", 3, 7000);
		placeP1Forward(mw, weak);
		placeP1Forward(mw, strong);
		placeP2Forward(mw, mine);
		fillP2Deck(mw, 3);
		castAsP2(mw, makeSummon("Adrammelech, the Wroth", "Lightning", 5, ADRAMMELECH_25_083H));

		assertTrue(mw.gameState.getP1BreakZone().contains(weak));
		assertEquals(7000, damageOn(mw, strong));
		assertEquals(List.of(mine), mw.p2ForwardCards, "opponent controls");
		assertEquals(1, mw.gameState.getP2DamageZone().size(), "1 point to the caster");
		assertTrue(mw.gameState.getP1DamageZone().isEmpty());
	}

	// =========================================================================================
	// 25-088H Famfrit, the Darkening Cloud: "Choose 1 auto-ability triggered from a Forward. Put
	// that Forward into the Break Zone. Draw 1 card." The ability is chosen, not cancelled — it
	// still resolves. Unparsed since the compound fallback stopped composing past the choice it
	// could not read; before that it put a Forward nobody chose into the Break Zone.
	// =========================================================================================

	private static final String FAMFRIT_25_088H = "Choose 1 auto-ability triggered from a Forward. "
			+ "Put that Forward into the Break Zone. Draw 1 card.";

	@Test
	void famfritPutsTheChosenAbilitysForwardIntoTheBreakZoneAndDraws() {
		CardData from = makeForward("Trigger Source", "Fire", 4, 7000);
		ForwardTarget slot = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);
		GameContext ctx = mock(GameContext.class);
		when(ctx.isP1()).thenReturn(true);
		when(ctx.chooseAbilityOnStack(any(), any())).thenReturn(autoEntryFrom(from, false));
		when(ctx.fieldSlotOf(from)).thenReturn(slot);

		ActionResolver.parse(FAMFRIT_25_088H).accept(ctx);

		verify(ctx).forceTargetToBreakZone(slot);
		verify(ctx, never()).breakTarget(any());
		verify(ctx, never()).cancelFilteredAbilityOnStack(any(), any(), anyBoolean());
		verify(ctx).drawCards(1);
	}

	@Test
	void famfritStillDrawsWhenThatForwardHasLeftTheField() {
		CardData from = makeForward("Trigger Source", "Fire", 4, 7000);
		GameContext ctx = mock(GameContext.class);
		when(ctx.isP1()).thenReturn(true);
		when(ctx.chooseAbilityOnStack(any(), any())).thenReturn(autoEntryFrom(from, false));
		when(ctx.fieldSlotOf(from)).thenReturn(null);

		ActionResolver.parse(FAMFRIT_25_088H).accept(ctx);

		verify(ctx, never()).forceTargetToBreakZone(any());
		verify(ctx).drawCards(1);
	}

	@SuppressWarnings("unchecked")
	@Test
	void famfritChoosesOnlyAnAutoAbilityFromAForward() {
		GameContext ctx = mock(GameContext.class);
		when(ctx.isP1()).thenReturn(true);
		ActionResolver.parse(FAMFRIT_25_088H).accept(ctx);
		ArgumentCaptor<Predicate<StackEntry>> filter = ArgumentCaptor.forClass(Predicate.class);
		verify(ctx).chooseAbilityOnStack(filter.capture(), any());
		assertTrue(filter.getValue().test(autoEntryFrom(makeForward("Fwd", "Fire", 9, 7000), false)));
		assertFalse(filter.getValue().test(autoEntryFrom(makeBackup("Bkp", "Fire", 2), false)));
	}

	@Test
	void famfritOnARealBoardLeavesTheAbilityOnTheStack() {
		MainWindow mw = new MainWindow();
		CardData from = makeForward("Trigger Source", "Fire", 4, 7000);
		// The equal copy sits on P1's side: two on one side would trip the uniqueness rule, and on
		// P1's it is the one an equality lookup would reach first, since P1's field is searched first.
		CardData twin = makeForward("Trigger Source", "Fire", 4, 7000);
		mw.placeCardInForwardZone(twin);
		placeP2Forward(mw, from);
		StackEntry entry = autoEntryFrom(from, false);
		mw.gameState.pushStack(entry);
		mw.gameState.getP1MainDeck().add(makeForward("Deck Card", "Fire", 1, 1000));

		ActionResolver.parse(FAMFRIT_25_088H).accept(mw.buildGameContext(true));

		assertTrue(mw.gameState.getP2BreakZone().stream().anyMatch(c -> c == from), "that Forward");
		assertTrue(mw.p1ForwardCards.stream().anyMatch(c -> c == twin), "not its twin");
		assertTrue(mw.gameState.getStack().contains(entry), "chosen, not cancelled");
		assertEquals(1, mw.gameState.getP1Hand().size(), "drew 1");
	}

	// =========================================================================================
	// Opus 26: "During your turn, you can dull 2 active [Element] Forwards you control (instead of
	// paying the CP cost) to cast [Self]." Each Summon below is cast from its full printed text,
	// so the cost sentence has to come off before the effect resolves.
	// =========================================================================================

	/** The Opus 26 cost sentence, for an element and a card name. */
	private static final String OPUS_26_DULL_COST = "During your turn, you can dull 2 active %s Forwards you control "
			+ "(instead of paying the CP cost) to cast %s.[[br]]";

	@Test
	void everyOpus26SummonReadsItsDullCostAndCastsWithoutIt() {
		List<CardData> printings = List.of(
				makeSummon("Phoenix", "Fire", 3, PHOENIX_26_017R),
				makeSummon("Kujata", "Ice", 3, KUJATA_26_027R),
				makeSummon("Alexander", "Wind", 3, ALEXANDER_26_042R),
				makeSummon("Titan", "Earth", 4, TITAN_26_072R),
				makeSummon("Odin", "Lightning", 3, ODIN_26_087R),
				makeSummon("Leviathan", "Water", 3, LEVIATHAN_26_119R));
		for (CardData summon : printings) {
			List<DullForwardCost> costs = summon.altDullCosts();
			assertEquals(1, costs.size(), summon.name());
			assertEquals(2, costs.get(0).count(), summon.name());
			assertEquals(summon.element(), costs.get(0).element(), summon.name());
			assertTrue(summon.altDullYourTurnOnly(), summon.name());
			assertFalse(summon.summonEffect().contains("instead of paying"), summon.name());
		}
	}

	@Test
	void theDullCostNeedsTwoActiveForwardsOfItsElementOnYourTurn() {
		CardData kujata = makeSummon("Kujata", "Ice", 3, KUJATA_26_027R);

		MainWindow paid = new MainWindow();
		placeP1Forward(paid, makeForward("Ice A", "Ice", 2, 5000));
		placeP1Forward(paid, makeForward("Ice B", "Ice", 2, 5000));
		assertTrue(paid.canPayAltDullCost(kujata));

		MainWindow offElement = new MainWindow();
		placeP1Forward(offElement, makeForward("Ice A", "Ice", 2, 5000));
		placeP1Forward(offElement, makeForward("Fire B", "Fire", 2, 5000));
		assertFalse(offElement.canPayAltDullCost(kujata), "both must be Ice");

		MainWindow oneDull = new MainWindow();
		placeP1Forward(oneDull, makeForward("Ice A", "Ice", 2, 5000));
		placeP1Forward(oneDull, makeForward("Ice B", "Ice", 2, 5000));
		oneDull.p1ForwardStates.set(1, CardState.DULL);
		assertFalse(oneDull.canPayAltDullCost(kujata), "both must be active");

		MainWindow theirTurn = new MainWindow();
		placeP1Forward(theirTurn, makeForward("Ice A", "Ice", 2, 5000));
		placeP1Forward(theirTurn, makeForward("Ice B", "Ice", 2, 5000));
		theirTurn.gameState.startFirstTurn(GameState.Player.P2);
		assertFalse(theirTurn.canPayAltDullCost(kujata), "during your turn only");
	}

	// =========================================================================================
	// 26-017R Phoenix: "Choose 1 Fire Forward of cost 2 or less in your Break Zone. Play it onto
	// the field dull."
	// =========================================================================================

	private static final String PHOENIX_26_017R = OPUS_26_DULL_COST.formatted("Fire", "Phoenix")
			+ "Choose 1 Fire Forward of cost 2 or less in your Break Zone. Play it onto the field dull.";

	@Test
	void phoenixReturnsACheapFireForwardFromYourBreakZoneDull() {
		MainWindow mw = new MainWindow();
		CardData target = makeForward("Cheap Fire", "Fire", 2, 5000);
		mw.gameState.getP2BreakZone().add(makeForward("Costly Fire", "Fire", 3, 7000));
		mw.gameState.getP2BreakZone().add(makeForward("Cheap Ice", "Ice", 2, 5000));
		mw.gameState.getP2BreakZone().add(makeBackup("Fire Backup", "Fire", 2));
		mw.gameState.getP2BreakZone().add(target);
		mw.gameState.getP1BreakZone().add(makeForward("Their Fire", "Fire", 1, 3000));
		castAsP2(mw, makeSummon("Phoenix", "Fire", 3, PHOENIX_26_017R));

		assertEquals(List.of(target), mw.p2ForwardCards);
		assertEquals(CardState.DULL, mw.p2ForwardStates.get(0));
		assertFalse(mw.gameState.getP2BreakZone().contains(target));
		assertEquals(3, mw.gameState.getP2BreakZone().size());
		assertEquals(1, mw.gameState.getP1BreakZone().size(), "your Break Zone");
	}

	// =========================================================================================
	// 26-027R Kujata: "Choose 1 dull Forward. Break it."
	// =========================================================================================

	private static final String KUJATA_26_027R = OPUS_26_DULL_COST.formatted("Ice", "Kujata")
			+ "Choose 1 dull Forward. Break it.";

	@Test
	void kujataBreaksTheDullForward() {
		MainWindow mw = new MainWindow();
		CardData dull   = makeForward("Dull One", "Water", 5, 9000);
		CardData active = makeForward("Active One", "Water", 5, 9000);
		placeP1Forward(mw, active);
		placeP1Forward(mw, dull);
		dullP1Forward(mw, dull);
		castAsP2(mw, makeSummon("Kujata", "Ice", 3, KUJATA_26_027R));

		assertTrue(mw.gameState.getP1BreakZone().contains(dull));
		assertEquals(List.of(active), mw.p1ForwardCards);
	}

	// =========================================================================================
	// 26-042R Alexander: "Choose 1 Character of cost 5 or more. Break it."
	// =========================================================================================

	private static final String ALEXANDER_26_042R = OPUS_26_DULL_COST.formatted("Wind", "Alexander")
			+ "Choose 1 Character of cost 5 or more. Break it.";

	// The same shape as 8-046R above with the threshold raised a step, so a cost-4 Character that
	// one would take is out of reach here.
	@Test
	void alexander26BreaksACostFiveBackupButNotACostFourForward() {
		MainWindow mw = new MainWindow();
		CardData costly = makeBackup("Costly Backup", "Water", 5);
		CardData cheap  = makeForward("Cheap Forward", "Water", 4, 9000);
		placeP1Backup(mw, costly);
		placeP1Forward(mw, cheap);
		castAsP2(mw, makeSummon("Alexander", "Wind", 3, ALEXANDER_26_042R));

		assertTrue(mw.gameState.getP1BreakZone().contains(costly), "a Character, not only a Forward");
		assertFalse(p1BackupOnField(mw, costly));
		assertEquals(List.of(cheap), mw.p1ForwardCards, "cost 5 or more");
	}

	// =========================================================================================
	// 26-072R Titan: "Choose 1 Forward you control and 1 Forward opponent controls. The former gains
	// +2000 power until the end of the turn. Then, the former deals damage equal to its power to
	// the latter."
	// =========================================================================================

	private static final String TITAN_26_072R = OPUS_26_DULL_COST.formatted("Earth", "Titan")
			+ "Choose 1 Forward you control and 1 Forward opponent controls. "
			+ "The former gains +2000 power until the end of the turn. "
			+ "Then, the former deals damage equal to its power to the latter.";

	@Test
	void titanBoostsYourForwardBeforeItDealsItsPower() {
		MainWindow mw = new MainWindow();
		CardData mine   = makeForward("Mine", "Earth", 4, 7000);
		CardData theirs = makeForward("Theirs", "Water", 6, 10000);
		placeP2Forward(mw, mine);
		placeP1Forward(mw, theirs);
		castAsP2(mw, makeSummon("Titan", "Earth", 4, TITAN_26_072R));

		assertEquals(9000, mw.effectiveP2ForwardPower(0), "+2000 until the end of the turn");
		assertEquals(9000, damageOn(mw, theirs), "the boosted power, not the printed 7000");
		assertEquals(0, (int) mw.p2ForwardDamage.get(0), "the latter deals nothing back");
	}

	@Test
	void titanBreaksTheirForwardWhenTheBoostIsEnough() {
		MainWindow mw = new MainWindow();
		placeP2Forward(mw, makeForward("Mine", "Earth", 4, 7000));
		CardData theirs = makeForward("Theirs", "Water", 5, 8000);
		placeP1Forward(mw, theirs);
		castAsP2(mw, makeSummon("Titan", "Earth", 4, TITAN_26_072R));

		assertTrue(mw.gameState.getP1BreakZone().contains(theirs), "9000 dealt to an 8000-power Forward");
	}

	// =========================================================================================
	// 26-087R Odin: "Choose 1 Forward with 《LB》 of cost 6 or less. Break it."
	//
	// The only printing that filters by 《LB》. Without an arm for it the choose phrase left
	// " with 《LB》" unmatched before the followup separator and the whole choice failed, so Odin's
	// second action read as unimplemented and the player could only ever take the first.
	// =========================================================================================

	private static final String ODIN_LB_ACTION =
			"Choose 1 Forward with 《LB》 of cost 6 or less. Break it.";

	/** A Limit Break Forward — the printing 《LB》 names. */
	private static CardData makeLbForward(String name, String element, int cost, int power) {
		return new CardData(null, name, element, cost, power, "Forward", true, cost, false, false,
				Set.of(), 0, List.of(), null, List.of(),
				List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
				List.of(), List.of(), List.of(),
				false, false, null, false, false, false, false, false, 1,
				null, null, null, "");
	}

	@Test
	void odinsSecondActionParsesAtAll() {
		CardData odin = makeForward("Odin", "Lightning", 5, 0);
		assertNotNull(ActionResolver.parse(ODIN_LB_ACTION, odin));
		assertEquals("ChooseCharacter / Break",
				ActionResolver.fullDescription(ODIN_LB_ACTION, odin));
	}

	@Test
	void andItOffersOnlyLimitBreakForwards() {
		MainWindow mw = new MainWindow();
		CardData lb    = makeLbForward("Limit Broken", "Fire", 5, 9000);
		CardData plain = makeForward("Ordinary", "Fire", 5, 9000);
		placeP2Forward(mw, lb);
		placeP2Forward(mw, plain);

		List<ForwardTarget> eligible = mw.buildGameContext(true).selectCharacters(
				1, false, false, false, CardFilters.LIMIT_BREAK_CONDITION, null,
				6, "less", -1, null, true, false, false,
				null, null, null, null, false, null, false);

		// One eligible target needs no prompt, so the selection answers with it directly.
		assertEquals(1, eligible.size(), "only the 《LB》 Forward qualifies");
		assertEquals(lb, mw.p2ForwardCards.get(eligible.get(0).idx()));
	}

	@Test
	void anUnknownKeywordFilterIsDeclinedRatherThanIgnored() {
		// An unread filter would widen the choice to every Forward on the table, which is exactly
		// what the without-《…》 arm beside it exists to prevent.
		CardData odin = makeForward("Odin", "Lightning", 5, 0);
		assertNull(ActionResolver.parse(
				"Choose 1 Forward with 《Something Else》 of cost 6 or less. Break it.", odin));
	}

	private static final String ODIN_26_087R = OPUS_26_DULL_COST.formatted("Lightning", "Odin")
			+ "Select 1 of the 2 following actions.[[br]]"
			+ "\"Choose 1 Forward of cost 4 or less. Break it.\"[[br]]"
			+ "\"" + ODIN_LB_ACTION + "\"";

	@Test
	void odinsFirstActionBreaksAForwardOfCostFourOrLess() {
		MainWindow mw = new MainWindow();
		CardData cheap  = makeForward("Cheap", "Water", 4, 9000);
		CardData costly = makeLbForward("Costly", "Water", 5, 9000);
		placeP1Forward(mw, costly);
		placeP1Forward(mw, cheap);
		castAsP2Selecting(mw, makeSummon("Odin", "Lightning", 3, ODIN_26_087R), 0);

		assertTrue(mw.gameState.getP1BreakZone().contains(cheap));
		assertEquals(List.of(costly), mw.p1ForwardCards, "cost 4 or less, 《LB》 or not");
	}

	@Test
	void odinsSecondActionBreaksALimitBreakForwardOfCostSix() {
		MainWindow mw = new MainWindow();
		CardData lb    = makeLbForward("Limit Broken", "Water", 6, 9000);
		CardData plain = makeForward("Ordinary", "Water", 3, 9000);
		placeP1Forward(mw, plain);
		placeP1Forward(mw, lb);
		castAsP2Selecting(mw, makeSummon("Odin", "Lightning", 3, ODIN_26_087R), 1);

		// A broken LB card goes back to the LB deck, so the Break Zone stays empty.
		assertEquals(List.of(plain), mw.p1ForwardCards, "a cheap Forward without 《LB》 does not qualify");
		assertTrue(mw.gameState.getP1BreakZone().isEmpty());
	}

	// =========================================================================================
	// 26-119R Leviathan: "Choose 1 Forward or Monster opponent controls. Return it to its owner's
	// hand."
	// =========================================================================================

	private static final String LEVIATHAN_26_119R = OPUS_26_DULL_COST.formatted("Water", "Leviathan")
			+ "Choose 1 Forward or Monster opponent controls. Return it to its owner's hand.";

	@Test
	void leviathanReturnsTheirForwardToItsOwnersHand() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Fire", 5, 9000);
		CardData mine   = makeForward("Mine", "Water", 3, 7000);
		placeP1Forward(mw, theirs);
		placeP2Forward(mw, mine);
		castAsP2(mw, makeSummon("Leviathan", "Water", 3, LEVIATHAN_26_119R));

		assertEquals(List.of(theirs), mw.gameState.getP1Hand());
		assertTrue(mw.p1ForwardCards.isEmpty());
		assertEquals(List.of(mine), mw.p2ForwardCards, "opponent controls");
	}

	@Test
	void leviathanReturnsAMonsterJustAsWell() {
		MainWindow mw = new MainWindow();
		CardData monster = makeMonster("Their Monster", "Fire", 2);
		placeP1Monster(mw, monster);
		placeP1Backup(mw, makeBackup("Their Backup", "Fire", 2));
		castAsP2(mw, makeSummon("Leviathan", "Water", 3, LEVIATHAN_26_119R));

		assertEquals(List.of(monster), mw.gameState.getP1Hand());
		assertTrue(mw.p1MonsterCards.isEmpty());
		assertNotNull(mw.p1BackupCards[0], "a Backup is neither");
	}

	// =========================================================================================
	// 27-002H Ifrit: "If you cast Ifrit, you may remove 1 Card Name Ifrit in your Break Zone from the
	// game as an extra cost. EX BURST Choose 1 Forward. Deal it 5000 damage. If you paid the extra
	// cost, deal it 8000 damage instead."
	// =========================================================================================

	private static final String IFRIT_27_002H = "If you cast Ifrit, you may remove 1 Card Name Ifrit in your Break "
			+ "Zone from the game as an extra cost.[[br]][[ex]]EX BURST[[/]] Choose 1 Forward. Deal it 5000 damage. "
			+ "If you paid the extra cost, deal it 8000 damage instead.";

	@Test
	void ifrit27sExtraCostIsOneIfritFromTheBreakZone() {
		assertEquals(ExtraCost.bzRemoveCardName(1, "Ifrit"),
				makeSummon("Ifrit", "Fire", 1, IFRIT_27_002H).extraCost());
	}

	@Test
	void ifrit27Deals5000OrBreaksAnEightThousandForwardWhenPaid() {
		for (boolean paid : new boolean[] { false, true }) {
			MainWindow mw = new MainWindow();
			CardData theirs = makeForward("Theirs", "Water", 4, 8000);
			placeP1Forward(mw, theirs);
			castAsP2Paying(mw, makeSummon("Ifrit", "Fire", 1, IFRIT_27_002H), paid);

			if (paid) assertTrue(mw.gameState.getP1BreakZone().contains(theirs), "paid: 8000 instead");
			else      assertEquals(5000, damageOn(mw, theirs), "unpaid: 5000");
		}
	}

	// =========================================================================================
	// 27-031H Moomba: "You can dull 2 active Category VIII Forwards you control (instead of paying
	// the CP cost) to cast Moomba. Choose 1 Forward. If it is dull or has received damage, break
	// it."
	// =========================================================================================

	private static final String MOOMBA_27_031H = "You can dull 2 active Category VIII Forwards you control "
			+ "(instead of paying the CP cost) to cast Moomba.[[br]]"
			+ "Choose 1 Forward. If it is dull or has received damage, break it.";

	@Test
	void moombasDullCostNamesTwoCategoryEightForwardsOnAnyTurn() {
		CardData moomba = makeSummon("Moomba", "Ice", 3, MOOMBA_27_031H);
		List<DullForwardCost> costs = moomba.altDullCosts();
		assertEquals(1, costs.size());
		assertEquals(2, costs.get(0).count());
		assertEquals("VIII", costs.get(0).category());
		assertNull(costs.get(0).element(), "any element");
		assertFalse(moomba.altDullYourTurnOnly(), "no timing clause on this printing");
		assertFalse(moomba.summonEffect().contains("instead of paying"));

		MainWindow paid = new MainWindow();
		placeP1Forward(paid, makeCategoryForward("Squall", "Ice", "VIII"));
		placeP1Forward(paid, makeCategoryForward("Zell", "Earth", "VIII"));
		assertTrue(paid.canPayAltDullCost(moomba));

		MainWindow offCategory = new MainWindow();
		placeP1Forward(offCategory, makeCategoryForward("Squall", "Ice", "VIII"));
		placeP1Forward(offCategory, makeCategoryForward("Cloud", "Wind", "VII"));
		assertFalse(offCategory.canPayAltDullCost(moomba), "both must be Category VIII");
	}

	@Test
	void moombaBreaksADullForward() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 5, 9000);
		placeP1Forward(mw, theirs);
		dullP1Forward(mw, theirs);
		castAsP2(mw, makeSummon("Moomba", "Ice", 3, MOOMBA_27_031H));
		assertTrue(mw.gameState.getP1BreakZone().contains(theirs));
	}

	@Test
	void moombaBreaksADamagedForward() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 5, 9000);
		placeP1Forward(mw, theirs);
		mw.p1ForwardDamage.set(0, 1000);
		castAsP2(mw, makeSummon("Moomba", "Ice", 3, MOOMBA_27_031H));
		assertTrue(mw.gameState.getP1BreakZone().contains(theirs));
	}

	@Test
	void moombaLeavesAnActiveUndamagedForward() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 5, 9000);
		placeP1Forward(mw, theirs);
		castAsP2(mw, makeSummon("Moomba", "Ice", 3, MOOMBA_27_031H));
		assertEquals(List.of(theirs), mw.p1ForwardCards, "neither dull nor damaged");
	}

	// =========================================================================================
	// 27-052H Chaos, Walker of the Wheel: "Choose 1 Character without 《Multicard》 you control.
	// Remove it from the game. Search for 1 Character with the same name and add it to your hand."
	// =========================================================================================

	private static final String CHAOS_27_052H = "Choose 1 Character without 《Multicard》 you control. "
			+ "Remove it from the game. Search for 1 Character with the same name and add it to your hand.";

	/** A Forward printed with 《Multicard》. */
	private static CardData makeMulticardForward(String name, String element, int cost, int power) {
		return new CardData(null, name, element, cost, power, "Forward", false, 0, false, true,
				Set.of(), 0, List.of(), null, List.of(),
				List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
				List.of(), List.of(), List.of(),
				false, false, null, false, false, false, false, false, 1,
				null, null, null, "");
	}

	@Test
	void chaosTradesYourCharacterForAnotherCopyFromYourDeck() {
		MainWindow mw = new MainWindow();
		CardData plain = makeForward("Plain", "Wind", 3, 7000);
		CardData multi = makeMulticardForward("Multi", "Wind", 3, 7000);
		placeP2Forward(mw, plain);
		placeP2Forward(mw, multi);
		placeP1Forward(mw, makeForward("Theirs", "Water", 3, 7000));
		CardData copy = makeForward("Plain", "Wind", 3, 7000);
		mw.gameState.getP2MainDeck().add(makeForward("Other", "Wind", 2, 5000));
		mw.gameState.getP2MainDeck().add(makeMulticardForward("Multi", "Wind", 3, 7000));
		mw.gameState.getP2MainDeck().add(copy);
		castAsP2(mw, makeSummon("Chaos, Walker of the Wheel", "Wind", 1, CHAOS_27_052H));

		assertTrue(mw.gameState.getP2RemovedFromGame().contains(plain));
		assertEquals(List.of(multi), mw.p2ForwardCards, "without 《Multicard》");
		assertEquals(1, mw.p1ForwardCards.size(), "you control");
		assertEquals(List.of(copy), mw.gameState.getP2Hand(), "the same name");
		assertEquals(2, mw.gameState.getP2MainDeck().size());
	}

	// =========================================================================================
	// 27-065H Hashmal, Bringer of Order: "Name 1 Job or Category. Reveal the top 3 cards of your
	// deck. Add up to 2 Characters of the named Job or Category among them to your hand and return
	// the other cards to the bottom of your deck in any order."
	// =========================================================================================

	private static final String HASHMAL_27_065H = "Name 1 Job or Category. Reveal the top 3 cards of your deck. "
			+ "Add up to 2 Characters of the named Job or Category among them to your hand and return the other "
			+ "cards to the bottom of your deck in any order.";

	/** Casts Hashmal from P2's seat with {@code named} ({@code {"job"|"category", value}}) as the answer. */
	private static void castHashmalNaming(MainWindow mw, String... named) {
		GameContext ctx = spy(mw.buildGameContext(false));
		doReturn(named).when(ctx).selectJobOrCategory(any());
		Consumer<GameContext> fn = ActionResolver.parse(HASHMAL_27_065H, null);
		assertNotNull(fn);
		fn.accept(ctx);
	}

	@Test
	void hashmalAddsUpToTwoOfTheNamedJobFromTheTopThree() {
		MainWindow mw = new MainWindow();
		CardData warriorA = makeJobCard("Warrior A", "Earth", "Forward", "Warrior");
		CardData mage     = makeJobCard("Mage", "Earth", "Forward", "Mage");
		CardData warriorB = makeJobCard("Warrior B", "Earth", "Backup", "Warrior");
		CardData deep     = makeJobCard("Warrior Deep", "Earth", "Forward", "Warrior");
		Collections.addAll(mw.gameState.getP2MainDeck(), warriorA, mage, warriorB, deep);
		castHashmalNaming(mw, "job", "Warrior");

		assertEquals(Set.of(warriorA, warriorB), Set.copyOf(mw.gameState.getP2Hand()), "Forward or Backup");
		assertEquals(List.of(deep, mage), List.copyOf(mw.gameState.getP2MainDeck()),
				"only the top 3; the rest to the bottom");
	}

	@Test
	void hashmalReadsANamedCategoryToo() {
		MainWindow mw = new MainWindow();
		CardData seven = makeCategoryForward("Seven", "Earth", "VII");
		CardData eight = makeCategoryForward("Eight", "Earth", "VIII");
		Collections.addAll(mw.gameState.getP2MainDeck(), eight, seven);
		castHashmalNaming(mw, "category", "VII");

		assertEquals(List.of(seven), mw.gameState.getP2Hand());
		assertEquals(List.of(eight), List.copyOf(mw.gameState.getP2MainDeck()));
	}

	// =========================================================================================
	// 27-089H Ramuh: "EX BURST Choose 1 Forward. Deal it 6000 damage. Reveal the top 3 cards of your
	// deck. Add 1 Card Name Ramuh among them to your hand and return the other cards to the bottom of
	// your deck in any order."
	// =========================================================================================

	private static final String RAMUH_27_089H = "[[ex]]EX BURST[[/]] Choose 1 Forward. Deal it 6000 damage. Reveal the "
			+ "top 3 cards of your deck. Add 1 Card Name Ramuh among them to your hand and return the other cards to "
			+ "the bottom of your deck in any order.";

	@Test
	void ramuhDeals6000AndFindsAnotherRamuhInTheTopThree() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 5, 9000);
		placeP1Forward(mw, theirs);
		CardData other = makeForward("Other", "Lightning", 2, 5000);
		CardData ramuh = makeSummon("Ramuh", "Lightning", 2, RAMUH_27_089H);
		CardData third = makeForward("Third", "Lightning", 2, 5000);
		CardData deep  = makeForward("Deep", "Lightning", 2, 5000);
		Collections.addAll(mw.gameState.getP2MainDeck(), other, ramuh, third, deep);
		castAsP2(mw, makeSummon("Ramuh", "Lightning", 2, RAMUH_27_089H));

		assertEquals(6000, damageOn(mw, theirs));
		assertEquals(List.of(ramuh), mw.gameState.getP2Hand());
		assertEquals(deep, mw.gameState.getP2MainDeck().peekFirst(), "the other two went to the bottom");
		assertEquals(3, mw.gameState.getP2MainDeck().size());
	}

	@Test
	void ramuhLooksNoDeeperThanThree() {
		MainWindow mw = new MainWindow();
		placeP1Forward(mw, makeForward("Theirs", "Water", 5, 9000));
		CardData deepRamuh = makeSummon("Ramuh", "Lightning", 2, RAMUH_27_089H);
		Collections.addAll(mw.gameState.getP2MainDeck(),
				makeForward("A", "Lightning", 2, 5000), makeForward("B", "Lightning", 2, 5000),
				makeForward("C", "Lightning", 2, 5000), deepRamuh);
		castAsP2(mw, makeSummon("Ramuh", "Lightning", 2, RAMUH_27_089H));

		assertTrue(mw.gameState.getP2Hand().isEmpty());
		assertEquals(deepRamuh, mw.gameState.getP2MainDeck().peekFirst());
	}

	// =========================================================================================
	// 27-108H Leviathan: "Choose 1 Forward of cost 4 or less opponent controls. Return it to its
	// owner's hand. You may play 1 Forward of cost 3 or less from your hand onto the field dull."
	// =========================================================================================

	private static final String LEVIATHAN_27_108H = "Choose 1 Forward of cost 4 or less opponent controls. Return it "
			+ "to its owner's hand. You may play 1 Forward of cost 3 or less from your hand onto the field dull.";

	@Test
	void leviathan27BouncesACheapForwardAndPlaysOneOfYoursDull() {
		MainWindow mw = new MainWindow();
		CardData cheap  = makeForward("Cheap", "Fire", 4, 8000);
		CardData costly = makeForward("Costly", "Fire", 5, 9000);
		placeP1Forward(mw, costly);
		placeP1Forward(mw, cheap);
		CardData three = makeForward("Three", "Water", 3, 7000);
		CardData four  = makeForward("Four", "Water", 4, 8000);
		mw.gameState.getP2Hand().add(four);
		mw.gameState.getP2Hand().add(three);
		castAsP2(mw, makeSummon("Leviathan", "Water", 5, LEVIATHAN_27_108H));

		assertEquals(List.of(cheap), mw.gameState.getP1Hand(), "cost 4 or less, to its owner's hand");
		assertEquals(List.of(costly), mw.p1ForwardCards);
		assertEquals(List.of(three), mw.p2ForwardCards, "cost 3 or less");
		assertEquals(CardState.DULL, mw.p2ForwardStates.get(0));
		assertEquals(List.of(four), mw.gameState.getP2Hand());
	}

	/**
	 * Opus 28's "Select up to 2 of the 2 following actions. If you selected 2 actions, the cost
	 * required to cast [Self] is increased by 《C》": the surcharge is paid at cast time, so
	 * resolution reads {@code wasExtraCostPaid()} — paid takes both actions, unpaid takes one. Takes
	 * {@code options} in the order given, and returns the context for verifying a call on it.
	 */
	private static GameContext castAsP2Selecting(MainWindow mw, CardData summon, boolean paid, int... options) {
		GameContext ctx = spy(mw.buildGameContext(false));
		doReturn(paid).when(ctx).wasExtraCostPaid();
		doAnswer(inv -> {
			List<String> offered = inv.getArgument(1);
			List<String> taken = new ArrayList<>();
			for (int option : options) taken.add(offered.get(option));
			return taken;
		}).when(ctx).chooseActions(any(), anyList(), anyInt(), anyBoolean());
		Consumer<GameContext> fn = ActionResolver.parse(summon.summonEffect(), summon);
		assertNotNull(fn, summon.name() + " parses");
		fn.accept(ctx);
		return ctx;
	}

	/** The Opus 28 surcharge sentence for {@code name}, at one Crystal. */
	private static String opus28Surcharge(String name) {
		return "Select up to 2 of the 2 following actions. If you selected 2 actions, the cost required to cast "
				+ name + " is increased by 《C》.[[br]]";
	}

	@Test
	void everyOpus28SummonPricesItsSecondActionInCrystals() {
		List<CardData> printings = List.of(
				makeSummon("Brynhildr", "Fire", 2, BRYNHILDR_28_018H),
				makeSummon("Shiva", "Ice", 2, SHIVA_28_028H),
				makeSummon("Sylph", "Wind", 2, SYLPH_28_048H),
				makeSummon("Cactuar", "Earth", 2, CACTUAR_28_064H),
				makeSummon("Bahamut SIN", "Lightning", 4, BAHAMUT_SIN_28_087H),
				makeSummon("Alexander", "Water", 2, ALEXANDER_28_096H));
		for (CardData summon : printings) {
			ExtraCost ec = summon.extraCost();
			assertNotNull(ec, summon.name());
			assertEquals(ExtraCost.Type.CRYSTAL, ec.type(), summon.name());
			assertEquals(summon.name().equals("Bahamut SIN") ? 2 : 1, ec.count(), summon.name());
		}
	}

	// =========================================================================================
	// 28-018H Brynhildr: "Choose 1 Forward. Deal it 5000 damage. If it is a Multi-Element Forward,
	// deal it 10000 damage instead." / "Choose 1 Monster. Break it."
	// =========================================================================================

	private static final String BRYNHILDR_28_018H = opus28Surcharge("Brynhildr")
			+ "\"Choose 1 Forward. Deal it 5000 damage. If it is a Multi-Element Forward, deal it 10000 damage "
			+ "instead.\"[[br]]\"Choose 1 Monster. Break it.\"";

	@Test
	void brynhildrDeals5000ToASingleElementForward() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 5, 9000);
		placeP1Forward(mw, theirs);
		castAsP2Selecting(mw, makeSummon("Brynhildr", "Fire", 2, BRYNHILDR_28_018H), false, 0);
		assertEquals(5000, damageOn(mw, theirs));
	}

	@Test
	void brynhildrDeals10000ToAMultiElementForward() {
		MainWindow mw = new MainWindow();
		CardData twin = makeForward("Twin", "Water/Wind", 5, 9000);
		placeP1Forward(mw, twin);
		castAsP2Selecting(mw, makeSummon("Brynhildr", "Fire", 2, BRYNHILDR_28_018H), false, 0);
		assertTrue(mw.gameState.getP1BreakZone().contains(twin), "10000 instead");
	}

	@Test
	void brynhildrPaidDamagesAForwardAndBreaksAMonster() {
		MainWindow mw = new MainWindow();
		CardData theirs  = makeForward("Theirs", "Water", 5, 9000);
		CardData monster = makeMonster("Their Monster", "Water", 2);
		placeP1Forward(mw, theirs);
		placeP1Monster(mw, monster);
		castAsP2Selecting(mw, makeSummon("Brynhildr", "Fire", 2, BRYNHILDR_28_018H), true, 0, 1);

		assertEquals(5000, damageOn(mw, theirs));
		assertTrue(mw.p1MonsterCards.isEmpty());
		assertTrue(mw.gameState.getP1BreakZone().contains(monster));
	}

	// =========================================================================================
	// 28-028H Shiva: "Choose 1 Forward. If it has dealt damage to a Forward this turn, break it." /
	// "Choose 1 Forward opponent controls. Dull it. Your opponent discards 1 card."
	// =========================================================================================

	private static final String SHIVA_28_028H = opus28Surcharge("Shiva")
			+ "\"Choose 1 Forward. If it has dealt damage to a Forward this turn, break it.\"[[br]]"
			+ "\"Choose 1 Forward opponent controls. Dull it. Your opponent discards 1 card.\"";

	@Test
	void shiva28SparesAForwardThatHasDealtNoDamageThisTurn() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 5, 9000);
		placeP1Forward(mw, theirs);
		castAsP2Selecting(mw, makeSummon("Shiva", "Ice", 2, SHIVA_28_028H), false, 0);
		assertEquals(List.of(theirs), mw.p1ForwardCards, "it has dealt no damage this turn");
	}

	// recordDamagedBy is where every damage path — battle and ability — credits the dealer once the
	// damage has landed, so it stands in here for the blow itself.
	@Test
	void shiva28BreaksAForwardThatHasDealtDamageToAForward() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 5, 9000);
		placeP1Forward(mw, theirs);
		mw.recordDamagedBy(makeForward("Its Victim", "Ice", 3, 7000), theirs);
		castAsP2Selecting(mw, makeSummon("Shiva", "Ice", 2, SHIVA_28_028H), false, 0);
		assertTrue(mw.gameState.getP1BreakZone().contains(theirs));
	}

	@Test
	void shiva28DoesNotCountDamageToAMonsterThatIsNoForward() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 5, 9000);
		placeP1Forward(mw, theirs);
		mw.recordDamagedBy(makeMonster("A Monster", "Ice", 2), theirs, false);
		castAsP2Selecting(mw, makeSummon("Shiva", "Ice", 2, SHIVA_28_028H), false, 0);
		assertEquals(List.of(theirs), mw.p1ForwardCards, "a Forward, not any Character");
	}

	@Test
	void shiva28DullsTheirForwardAndTheyDiscard() {
		// One card in P1's hand, so P1's forced discard needs no answer.
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 5, 9000);
		CardData mine   = makeForward("Mine", "Ice", 3, 7000);
		placeP1Forward(mw, theirs);
		placeP2Forward(mw, mine);
		CardData inHand = makeForward("In Hand", "Water", 2, 5000);
		mw.gameState.getP1Hand().add(inHand);
		castAsP2Selecting(mw, makeSummon("Shiva", "Ice", 2, SHIVA_28_028H), false, 1);

		assertEquals(CardState.DULL, mw.p1ForwardStates.get(0));
		assertEquals(CardState.ACTIVE, mw.p2ForwardStates.get(0), "opponent controls");
		assertTrue(mw.gameState.getP1BreakZone().contains(inHand));
		assertTrue(mw.gameState.getP1Hand().isEmpty());
	}

	// =========================================================================================
	// 28-048H Sylph: "Choose 1 Forward. It gains 'This Forward cannot be chosen by your opponent's
	// abilities.' until the end of the turn." / "Draw 1 card."
	// =========================================================================================

	private static final String SYLPH_28_048H = opus28Surcharge("Sylph")
			+ "\"Choose 1 Forward. It gains 'This Forward cannot be chosen by your opponent's abilities.' until "
			+ "the end of the turn.\"[[br]]\"Draw 1 card.\"";

	@Test
	void sylphPaidShieldsAForwardFromAbilitiesAndDraws() {
		MainWindow mw = new MainWindow();
		placeP2Forward(mw, makeForward("Mine", "Wind", 3, 7000));
		fillP2Deck(mw, 1);
		GameContext ctx = castAsP2Selecting(mw, makeSummon("Sylph", "Wind", 2, SYLPH_28_048H), true, 0, 1);

		verify(ctx).shieldCannotBeChosen(fwd(false, 0), false, true);
		assertEquals(1, mw.gameState.getP2Hand().size());
	}

	@Test
	void sylphUnpaidTakesOnlyTheActionSelected() {
		MainWindow mw = new MainWindow();
		placeP2Forward(mw, makeForward("Mine", "Wind", 3, 7000));
		fillP2Deck(mw, 1);
		GameContext ctx = castAsP2Selecting(mw, makeSummon("Sylph", "Wind", 2, SYLPH_28_048H), false, 1);

		verify(ctx, never()).shieldCannotBeChosen(any(), anyBoolean(), anyBoolean());
		assertEquals(1, mw.gameState.getP2Hand().size());
	}

	// =========================================================================================
	// 28-064H Cactuar, first option: "Choose 1 Forward. If it has entered the field other than from
	// any player's hand this turn, break it."
	// =========================================================================================

	private static final String CACTUAR_28_064H = opus28Surcharge("Cactuar")
			+ "\"Choose 1 Forward. If it has entered the field other than from any player's hand this turn, break "
			+ "it.\"[[br]]\"Choose 1 Forward. It gains 'This Forward cannot attack or block.' until the end of the "
			+ "turn. Draw 1 card.\"";

	// A card seated straight onto the field never left a hand, so it arrived from somewhere else —
	// the Break Zone, the deck, out of the game — which is the case Cactuar breaks.
	@Test
	void cactuarBreaksAForwardThatEnteredOtherThanFromAHandThisTurn() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 5, 9000);
		placeP1Forward(mw, theirs);
		castAsP2Selecting(mw, makeSummon("Cactuar", "Earth", 2, CACTUAR_28_064H), false, 0);
		assertTrue(mw.gameState.getP1BreakZone().contains(theirs));
	}

	@Test
	void cactuarSparesAForwardPlayedFromAHand() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 5, 9000);
		mw.gameState.getP1Hand().add(theirs);
		mw.gameState.getP1Hand().remove(theirs);
		placeP1Forward(mw, theirs);
		castAsP2Selecting(mw, makeSummon("Cactuar", "Earth", 2, CACTUAR_28_064H), false, 0);
		assertEquals(List.of(theirs), mw.p1ForwardCards, "it came from a hand");
	}

	@Test
	void cactuarSparesAForwardThatEnteredOnAnEarlierTurn() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 5, 9000);
		placeP1Forward(mw, theirs);
		mw.enteredOtherThanFromHandThisTurn.clear();   // what a turn boundary does
		castAsP2Selecting(mw, makeSummon("Cactuar", "Earth", 2, CACTUAR_28_064H), false, 0);
		assertEquals(List.of(theirs), mw.p1ForwardCards, "not this turn");
	}

	@Test
	void aCardDiscardedFromAHandLosesItsFromHandMark() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 5, 9000);
		mw.gameState.getIdentity().put(theirs, true);
		mw.gameState.getIdentity().put(theirs, true);
		mw.gameState.getP1Hand().add(theirs);
		mw.gameState.getP1Hand().remove(theirs);
		mw.addToBreakZone(theirs);
		mw.gameState.getP1BreakZone().remove(theirs);
		placeP1Forward(mw, theirs);
		assertTrue(mw.enteredOtherThanFromHandThisTurn.contains(theirs),
				"hand, then Break Zone, then field: it entered from the Break Zone");
	}

	// =========================================================================================
	// 28-064H Cactuar, second option: "Choose 1 Forward. It gains 'This Forward cannot attack or
	// block.' until the end of the turn. Draw 1 card."
	//
	// The followup pattern already read this sentence — with double quotes around the grant.
	// Cactuar is the one printing that uses ', and nothing else distinguishes the two.
	// =========================================================================================

	private static final String CACTUAR_OPTION =
			"Choose 1 Forward. It gains 'This Forward cannot attack or block.' until the end of "
			+ "the turn. Draw 1 card.";

	@Test
	void cactuarLocksTheForwardAndDraws() {
		ForwardTarget victim = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);
		GameContext ctx = mock(GameContext.class);
		when(ctx.consumePreloadedTargets()).thenReturn(null);
		when(ctx.selectCharacters(anyInt(), anyBoolean(), anyBoolean(), anyBoolean(), any(), any(),
				anyInt(), any(), anyInt(), any(), anyBoolean(), anyBoolean(), anyBoolean(),
				any(), any(), any(), any(), anyBoolean(), any(), anyBoolean()))
				.thenReturn(List.of(victim));

		Consumer<GameContext> effect = ActionResolver.parse(CACTUAR_OPTION, null);
		assertNotNull(effect, "the printed wording has to parse for the rest of this to mean anything");
		effect.accept(ctx);

		verify(ctx).setP2ForwardCannotAttack(0);
		verify(ctx).setP2ForwardCannotBlock(0);
		verify(ctx).drawCards(1);
	}

	@Test
	void andTheSingleQuotedGrantIsNamedLikeTheDoubleQuotedOne() {
		assertEquals("ChooseCharacter / CannotAttackOrBlock + DrawCards",
				ActionResolver.fullDescription(CACTUAR_OPTION, null));
		assertEquals("ChooseCharacter / CannotAttackOrBlock + DrawCards",
				ActionResolver.fullDescription(CACTUAR_OPTION.replace('\'', '"'), null));
	}

	// =========================================================================================
	// 28-087H Bahamut SIN: "Choose 1 active Forward. Break it." / "Choose 1 Forward of cost 3 or
	// less. Break it." Its second action costs 《C》《C》 rather than 《C》.
	// =========================================================================================

	private static final String BAHAMUT_SIN_28_087H = "Select up to 2 of the 2 following actions. If you selected "
			+ "2 actions, the cost required to cast Bahamut SIN is increased by 《C》《C》.[[br]]"
			+ "\"Choose 1 active Forward. Break it.\"[[br]]\"Choose 1 Forward of cost 3 or less. Break it.\"";

	@Test
	void bahamutSinBreaksAnActiveForwardButNotADullOne() {
		MainWindow mw = new MainWindow();
		CardData active = makeForward("Active", "Water", 6, 10000);
		CardData dull   = makeForward("Dull", "Water", 6, 10000);
		placeP1Forward(mw, dull);
		placeP1Forward(mw, active);
		dullP1Forward(mw, dull);
		castAsP2Selecting(mw, makeSummon("Bahamut SIN", "Lightning", 4, BAHAMUT_SIN_28_087H), false, 0);

		assertTrue(mw.gameState.getP1BreakZone().contains(active));
		assertEquals(List.of(dull), mw.p1ForwardCards);
	}

	@Test
	void bahamutSinPaidBreaksAnActiveForwardAndACheapOne() {
		MainWindow mw = new MainWindow();
		CardData big   = makeForward("Big", "Water", 6, 10000);
		CardData cheap = makeForward("Cheap", "Water", 3, 7000);
		placeP1Forward(mw, big);
		placeP1Forward(mw, cheap);
		dullP1Forward(mw, cheap);
		castAsP2Selecting(mw, makeSummon("Bahamut SIN", "Lightning", 4, BAHAMUT_SIN_28_087H), true, 0, 1);

		assertTrue(mw.p1ForwardCards.isEmpty(), "the active one, then the cost-3 one");
		assertEquals(2, mw.gameState.getP1BreakZone().size());
	}

	// =========================================================================================
	// 28-096H Alexander: "During this turn, if a Forward you control is dealt damage, reduce the
	// damage by 2000 instead." / "Activate all the Forwards you control."
	// =========================================================================================

	private static final String ALEXANDER_28_096H = opus28Surcharge("Alexander")
			+ "\"During this turn, if a Forward you control is dealt damage, reduce the damage by 2000 instead.\""
			+ "[[br]]\"Activate all the Forwards you control.\"";

	@Test
	void alexander28PaidActivatesYourForwardsAndShieldsThem() {
		MainWindow mw = new MainWindow();
		CardData mine   = makeForward("Mine", "Water", 4, 8000);
		CardData theirs = makeForward("Theirs", "Fire", 4, 8000);
		placeP2Forward(mw, mine);
		placeP1Forward(mw, theirs);
		dullP2Forward(mw, mine);
		dullP1Forward(mw, theirs);
		castAsP2Selecting(mw, makeSummon("Alexander", "Water", 2, ALEXANDER_28_096H), true, 0, 1);

		assertEquals(CardState.ACTIVE, mw.p2ForwardStates.get(0));
		assertEquals(CardState.DULL, mw.p1ForwardStates.get(0), "you control");

		GameContext ctx = mw.buildGameContext(true);
		ctx.damageTarget(fwd(false, 0), 5000);
		ctx.damageTarget(fwd(true, 0), 5000);
		assertEquals(3000, (int) mw.p2ForwardDamage.get(0), "5000 less 2000");
		assertEquals(5000, damageOn(mw, theirs), "only yours are shielded");
	}

	// =========================================================================================
	// 29-013H Bahamut: "You can only cast Bahamut during your turn. Choose 1 Forward opponent
	// controls. Deal it 10000 damage. When it is put from the field into the Break Zone this turn,
	// if your opponent has received 5 points of damage or less, Bahamut deals your opponent 1 point
	// of damage."
	// =========================================================================================

	private static final String BAHAMUT_29_013H = "You can only cast Bahamut during your turn.[[br]]Choose 1 Forward "
			+ "opponent controls. Deal it 10000 damage. When it is put from the field into the Break Zone this turn, "
			+ "if your opponent has received 5 points of damage or less, Bahamut deals your opponent 1 point of "
			+ "damage.";

	/** P2 casts Bahamut at P1's {@code power} Forward with P1 on {@code damage} points. */
	private static MainWindow castBahamut29(int power, int damage) {
		MainWindow mw = new MainWindow();
		placeP1Forward(mw, makeForward("Theirs", "Water", 6, power));
		takeDamage(mw.gameState.getP1DamageZone(), damage);
		for (int i = 0; i < 3; i++) mw.gameState.getP1MainDeck().add(makeForward("Deck " + i, "Water", 1, 1000));
		castAsP2(mw, makeSummon("Bahamut", "Fire", 6, BAHAMUT_29_013H));
		return mw;
	}

	@Test
	void bahamut29BreaksTheForwardAndPingsAnOpponentOnFive() {
		MainWindow mw = castBahamut29(10000, 5);
		assertTrue(mw.p1ForwardCards.isEmpty());
		assertEquals(6, mw.gameState.getP1DamageZone().size(), "5 or less: 1 more point");
	}

	@Test
	void bahamut29DoesNotPingAnOpponentOnSix() {
		MainWindow mw = castBahamut29(10000, 6);
		assertTrue(mw.p1ForwardCards.isEmpty());
		assertEquals(6, mw.gameState.getP1DamageZone().size(), "past the ceiling");
	}

	@Test
	void bahamut29DoesNotPingWhenTheForwardSurvives() {
		MainWindow mw = castBahamut29(12000, 0);
		assertEquals(1, mw.p1ForwardCards.size());
		assertTrue(mw.gameState.getP1DamageZone().isEmpty(), "it never went to the Break Zone");
	}

	// =========================================================================================
	// 29-024H Shiva: "Select up to 3 of the 4 following actions. "Choose up to 2 Forwards. Dull them
	// and Freeze them." "Choose 1 Backup. Dull it and Freeze it." "Your opponent discards 1 card."
	// "All the Ice Forwards you control gain +3000 power until the end of the turn.""
	// =========================================================================================

	private static final String SHIVA_29_024H = "Select up to 3 of the 4 following actions.[[br]]"
			+ "\"Choose up to 2 Forwards. Dull them and Freeze them.\"[[br]]"
			+ "\"Choose 1 Backup. Dull it and Freeze it.\"[[br]]"
			+ "\"Your opponent discards 1 card.\"[[br]]"
			+ "\"All the Ice Forwards you control gain +3000 power until the end of the turn.\"";

	@Test
	void shiva29FreezesTwoForwardsAndABackupAndBoostsYourIce() {
		MainWindow mw = new MainWindow();
		placeP1Forward(mw, makeForward("Theirs A", "Water", 4, 8000));
		placeP1Forward(mw, makeForward("Theirs B", "Water", 4, 8000));
		CardData backup = makeBackup("Their Backup", "Water", 3);
		placeP1Backup(mw, backup);
		placeP2Forward(mw, makeForward("My Ice", "Ice", 3, 7000));
		placeP2Forward(mw, makeForward("My Fire", "Fire", 3, 7000));
		castAsP2Selecting(mw, makeSummon("Shiva", "Ice", 5, SHIVA_29_024H), 0, 1, 3);

		for (int i = 0; i < 2; i++) {
			assertEquals(CardState.DULL, mw.p1ForwardStates.get(i));
			assertTrue(mw.p1ForwardFrozen.get(i));
		}
		assertEquals(CardState.DULL, mw.p1BackupStates[0]);
		assertTrue(mw.p1BackupFrozen[0]);
		assertEquals(10000, mw.effectiveP2ForwardPower(0), "Ice: +3000");
		assertEquals(7000, mw.effectiveP2ForwardPower(1), "only Ice Forwards");
	}

	@Test
	void shiva29MakesTheOpponentDiscard() {
		MainWindow mw = new MainWindow();
		CardData inHand = makeForward("In Hand", "Water", 2, 5000);
		mw.gameState.getP1Hand().add(inHand);
		castAsP2Selecting(mw, makeSummon("Shiva", "Ice", 5, SHIVA_29_024H), 2);
		assertEquals(List.of(inHand), mw.gameState.getP1BreakZone());
	}

	// =========================================================================================
	// 29-041H Alexander: "Choose up to 3 Forwards opponent controls. Deal them 5000 damage."
	// =========================================================================================

	private static final String ALEXANDER_29_041H = "Choose up to 3 Forwards opponent controls. Deal them 5000 damage.";

	@Test
	void alexander29DamagesUpToThreeOfTheirForwards() {
		MainWindow mw = new MainWindow();
		for (int i = 0; i < 4; i++) placeP1Forward(mw, makeForward("Theirs " + i, "Water", 4, 8000));
		CardData mine = makeForward("Mine", "Wind", 3, 7000);
		placeP2Forward(mw, mine);
		castAsP2(mw, makeSummon("Alexander", "Wind", 3, ALEXANDER_29_041H));

		assertEquals(3, mw.p1ForwardDamage.stream().filter(d -> d == 5000).count(), "up to 3");
		assertEquals(1, mw.p1ForwardDamage.stream().filter(d -> d == 0).count());
		assertEquals(0, (int) mw.p2ForwardDamage.get(0), "opponent controls");
	}

	// =========================================================================================
	// 29-074H Moogle Trio: "EX BURST Choose 1 Forward. Deal it 2000 damage for each Forward you
	// control."
	// =========================================================================================

	private static final String MOOGLE_TRIO_29_074H = "[[ex]]EX BURST[[/]] Choose 1 Forward. Deal it 2000 damage for "
			+ "each Forward you control.";

	@Test
	void moogleTrioDeals2000PerForwardTheCasterControls() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 6, 10000);
		placeP1Forward(mw, theirs);
		placeP1Forward(mw, makeForward("Theirs Too", "Water", 6, 10000));
		for (int i = 0; i < 3; i++) placeP2Forward(mw, makeForward("Mine " + i, "Earth", 2, 5000));
		castAsP2(mw, makeSummon("Moogle Trio", "Earth", 1, MOOGLE_TRIO_29_074H));

		assertTrue(mw.p1ForwardDamage.contains(6000), "3 Forwards you control, not their 2");
	}

	// =========================================================================================
	// 29-094H Ramuh: "Choose 1 Forward opponent controls. Deal it 5000 damage. If you discarded a
	// Card Name Ramuh to cast Ramuh, deal it 7000 damage and draw 1 card instead."
	// =========================================================================================

	private static final String RAMUH_29_094H = "Choose 1 Forward opponent controls. Deal it 5000 damage. If you "
			+ "discarded a Card Name Ramuh to cast Ramuh, deal it 7000 damage and draw 1 card instead.";

	/** P2 casts Ramuh at P1's 9000-power Forward, having discarded {@code discarded} to pay for it. */
	private static MainWindow castRamuh29(CardData discarded) {
		MainWindow mw = new MainWindow();
		placeP1Forward(mw, makeForward("Theirs", "Water", 5, 9000));
		fillP2Deck(mw, 1);
		CardData ramuh = makeSummon("Ramuh", "Lightning", 2, RAMUH_29_094H);
		mw.lastCastPaymentCard = ramuh;
		if (discarded != null) mw.lastCastPaymentDiscards.add(discarded);
		castAsP2(mw, ramuh);
		return mw;
	}

	@Test
	void ramuh29Deals5000WithNoRamuhDiscarded() {
		MainWindow mw = castRamuh29(makeForward("Not Ramuh", "Lightning", 2, 5000));
		assertEquals(5000, (int) mw.p1ForwardDamage.get(0));
		assertTrue(mw.gameState.getP2Hand().isEmpty());
	}

	@Test
	void ramuh29Deals7000AndDrawsWhenARamuhWasDiscarded() {
		MainWindow mw = castRamuh29(makeSummon("Ramuh", "Lightning", 3, "Draw 1 card."));
		assertEquals(7000, (int) mw.p1ForwardDamage.get(0));
		assertEquals(1, mw.gameState.getP2Hand().size(), "and draw 1 card");
	}

	// =========================================================================================
	// 29-101H Syldra — two alternatives, each with its own cost ceiling
	//
	// "Play 1 Forward of cost 4 or less other than Multi-Element or 1 Card Name Faris of cost 6 or
	// less among them onto the field." One card is played, from whichever branch it satisfies, and
	// the branches do not share a ceiling — the Faris branch reaches costs the Forward branch
	// cannot, which is why it is printed as a second alternative rather than as a wider filter.
	//
	// The effect also carries a cast restriction in front of it. That is parsed off the card into
	// a CastRestriction and enforced at cast time, so the effect parsers have to step over the
	// sentence; leaving it there is what made this text unparseable.
	// =========================================================================================

	private static final String SYLDRA_29_101H =
			"You can only cast Syldra during your turn. Reveal the top 5 cards of your deck. "
			+ "Play 1 Forward of cost 4 or less other than Multi-Element or 1 Card Name Faris of "
			+ "cost 6 or less among them onto the field and return the other cards to the bottom "
			+ "of your deck in any order.";

	@Test
	void syldraReadsBothAlternativesAndTheirSeparateCeilings() {
		CardData syldra = makeSummon("Syldra", "Water", 4, SYLDRA_29_101H);
		assertEquals("RevealPlayTypeCostOrNamedCostRestBottom",
				ActionResolver.matchedPatternName(SYLDRA_29_101H, syldra),
				"the cast-restriction sentence must not defeat the anchored pattern");

		GameContext ctx = mock(GameContext.class);
		ActionResolver.parse(SYLDRA_29_101H, syldra).accept(ctx);
		verify(ctx).revealTopNPlayTypeCostOrNamedCostOntoFieldRestBottom(
				5, "Forward", 4, true, "Faris", 6);
	}

	/** P2's deck, top card first — P2 so the reveal resolves through the AI seat, dialog-free. */
	private static MainWindow syldraDeck(CardData... topFirst) {
		MainWindow mw = new MainWindow();
		Collections.addAll(mw.gameState.getP2MainDeck(), topFirst);
		return mw;
	}

	private static void resolveSyldra(MainWindow mw) {
		mw.buildGameContext(false).revealTopNPlayTypeCostOrNamedCostOntoFieldRestBottom(
				5, "Forward", 4, true, "Faris", 6);
	}

	@Test
	void theNamedBranchReachesACostTheTypeBranchCannot() {
		MainWindow mw = syldraDeck(
				makeForward("Faris", "Wind", 6, 9000),        // eligible: named, cost 6
				makeForward("Bartz", "Wind", 3, 7000),        // eligible: Forward, cost 3
				makeForward("Krile", "Wind", 5, 8000),        // too dear for the Forward branch
				makeForward("Twin", "Fire/Water", 4, 8000),   // Multi-Element, excluded
				makeSummon("Shiva", "Ice", 2, ""));           // not a Forward, not Faris

		resolveSyldra(mw);

		assertEquals(1, mw.p2ForwardCards.size());
		assertEquals("Faris", mw.p2ForwardCards.get(0).name(),
				"the dearest eligible card, and only the named branch could supply it");
	}

	@Test
	void theTypeBranchExcludesMultiElementAndAnythingTooDear() {
		MainWindow mw = syldraDeck(
				makeForward("Krile", "Wind", 5, 8000),        // over the Forward ceiling
				makeForward("Twin", "Fire/Water", 4, 8000),   // at the ceiling but Multi-Element
				makeForward("Bartz", "Wind", 3, 7000),        // the only eligible card
				makeForward("Lenna", "Water", 2, 5000),
				makeSummon("Shiva", "Ice", 2, ""));

		resolveSyldra(mw);

		assertEquals(1, mw.p2ForwardCards.size());
		assertEquals("Bartz", mw.p2ForwardCards.get(0).name(),
				"\"other than Multi-Element\" rules out the cost-4 twin above it");
	}

	@Test
	void theRevealedCardLandsOnTheResolvingPlayersField() {
		// Guards the whole reveal-and-play family, not just Syldra: each of them built its
		// placement inline against P1's zones, which are P1-only, so P2 resolving any of them
		// handed the card to P1 instead.
		MainWindow mw = syldraDeck(
				makeForward("Bartz", "Wind", 3, 7000),
				makeForward("Lenna", "Water", 2, 5000),
				makeSummon("Shiva", "Ice", 2, ""));

		resolveSyldra(mw);

		assertEquals(1, mw.p2ForwardCards.size(), "P2 resolved it, so P2 gets the Forward");
		assertEquals(0, mw.p1ForwardCards.size(), "and nothing lands on the opponent's board");
	}

	@Test
	void theCardsNotPlayedGoToTheBottomOfTheDeck() {
		MainWindow mw = syldraDeck(
				makeForward("Bartz", "Wind", 3, 7000),
				makeForward("Krile", "Wind", 5, 8000),
				makeForward("Lenna", "Water", 2, 5000),
				makeForward("Galuf", "Earth", 4, 8000),
				makeSummon("Shiva", "Ice", 2, ""),
				makeForward("Deep Deck", "Fire", 1, 3000));   // never revealed — stays put

		resolveSyldra(mw);

		List<CardData> deck = new ArrayList<>(mw.gameState.getP2MainDeck());
		assertEquals(5, deck.size(), "one of the six was played");
		assertEquals("Deep Deck", deck.get(0).name(),
				"the unrevealed card is now on top; the other four went under it");
		assertFalse(deck.stream().anyMatch(c -> c.name().equals("Galuf")),
				"the dearest eligible Forward was the one played");
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

	// Madeen's effect: "You may search for 1 Light Forward and remove it from the game. If you do
	// so, remove the chosen Forward from the game. If not, break the chosen Forward." The same
	// shape as Ark's, with remove and break as its two branches.

	private static void stubSearch(GameContext ctx, boolean found) {
		when(ctx.searchDeckForCard(
				anyBoolean(), anyBoolean(), anyBoolean(), anyBoolean(),
				anyInt(), any(), any(), any(), any(), any(), any(), any(),
				any(), anyInt(), anyBoolean(), any())).thenReturn(found);
	}

	private static final String MADEEN_EFFECT =
			"Madeen cannot be cancelled. Choose 1 Forward opponent controls. "
			+ "You may search for 1 Light Forward and remove it from the game. "
			+ "If you do so, remove the chosen Forward from the game. If not, break the chosen Forward.";

	@Test
	void madeenRemovesTheChosenForwardOnlyWhenTheSearchSucceeds() {
		ForwardTarget t = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);
		GameContext ctx = contextChoosing(List.of(t));
		when(ctx.promptYouMay(any())).thenReturn(true);
		stubSearch(ctx, true);

		Consumer<GameContext> fn = ActionResolver.parse(MADEEN_EFFECT, makeForward("Madeen", "Light", 5, 0));
		assertNotNull(fn, "Madeen should parse");
		fn.accept(ctx);

		verify(ctx).removeTargetFromGame(t);
		verify(ctx, never()).breakTarget(any());
	}

	/** Madeen's "if not" is a break, where Ark's is damage — the two branches are read, not assumed. */
	@Test
	void madeenBreaksTheChosenForwardWhenTheSearchIsDeclined() {
		ForwardTarget t = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);
		GameContext ctx = contextChoosing(List.of(t));
		when(ctx.promptYouMay(any())).thenReturn(false);
		stubSearch(ctx, true);

		ActionResolver.parse(MADEEN_EFFECT, makeForward("Madeen", "Light", 5, 0)).accept(ctx);

		verify(ctx).breakTarget(t);
		verify(ctx, never()).removeTargetFromGame(any());
	}

	/**
	 * The precedence guard that makes this work: WhenYouDoSo sits ~200 call sites earlier in
	 * parse() and used to claim the whole sentence, resolving "remove the chosen Forward from the
	 * game" as a remove-by-name and never offering the search that gates it.
	 */
	@Test
	void madeenIsClaimedByTheChooseParserRatherThanWhenYouDoSo() {
		CardData madeen = makeForward("Madeen", "Light", 5, 0);
		assertEquals("ChooseCharacter / MaySearchRfgThenElse",
				ActionResolver.fullDescription(MADEEN_EFFECT, madeen));

		GameContext ctx = contextChoosing(List.of(new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD)));
		when(ctx.promptYouMay(any())).thenReturn(true);
		stubSearch(ctx, true);
		ActionResolver.parse(MADEEN_EFFECT, madeen).accept(ctx);
		verify(ctx, never()).removeNamedCardFromGame(any());
	}

	// =========================================================================================
	// 29-117H Ark: "Choose up to 2 Forwards opponent controls. You may search for 1 Dark Forward and
	// remove it from the game. If you do so, break the chosen Forwards. If not, deal 8000 damage to
	// the chosen Forwards."
	// =========================================================================================

	private static final String ARK_29_117H = "Choose up to 2 Forwards opponent controls. You may search for 1 Dark "
			+ "Forward and remove it from the game. If you do so, break the chosen Forwards. If not, deal 8000 damage "
			+ "to the chosen Forwards.";

	/** P2 casts Ark at P1's two 10000-power Forwards, answering its "you may" with {@code search}. */
	private static MainWindow castArk(boolean search, CardData... deck) {
		MainWindow mw = new MainWindow();
		placeP1Forward(mw, makeForward("Theirs A", "Water", 6, 10000));
		placeP1Forward(mw, makeForward("Theirs B", "Water", 6, 10000));
		CardData mine = makeForward("Mine", "Dark", 3, 7000);
		placeP2Forward(mw, mine);
		// Owners recorded as a dealt deck would have them: the removed card is filed by owner.
		for (CardData c : deck) {
			mw.gameState.getIdentity().put(c, false);
			mw.gameState.getP2MainDeck().add(c);
		}
		GameContext ctx = spy(mw.buildGameContext(false));
		doReturn(search).when(ctx).promptYouMay(any());
		CardData ark = makeSummon("Ark", "Dark", 5, ARK_29_117H);
		Consumer<GameContext> fn = ActionResolver.parse(ark.summonEffect(), ark);
		assertNotNull(fn);
		fn.accept(ctx);
		assertEquals(List.of(mine), mw.p2ForwardCards, "opponent controls");
		return mw;
	}

	@Test
	void arkBreaksBothWhenItRemovesADarkForwardFromYourDeck() {
		CardData dark = makeForward("Dark One", "Dark", 4, 8000);
		MainWindow mw = castArk(true, makeForward("Light One", "Light", 4, 8000), dark);

		assertTrue(mw.p1ForwardCards.isEmpty(), "both broken");
		assertEquals(List.of(dark), mw.gameState.getP2RemovedFromGame());
	}

	@Test
	void arkDeals8000WhenTheSearchIsDeclined() {
		MainWindow mw = castArk(false, makeForward("Dark One", "Dark", 4, 8000));

		assertEquals(List.of(8000, 8000), mw.p1ForwardDamage);
		assertTrue(mw.gameState.getP2RemovedFromGame().isEmpty());
	}

	@Test
	void arkDeals8000WhenTheDeckHasNoDarkForward() {
		MainWindow mw = castArk(true, makeForward("Light One", "Light", 4, 8000));

		assertEquals(List.of(8000, 8000), mw.p1ForwardDamage, "nothing removed, so \"if not\"");
		assertTrue(mw.gameState.getP2RemovedFromGame().isEmpty());
	}
}
