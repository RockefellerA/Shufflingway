package shufflingway;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static shufflingway.TestCards.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import scraper.DeckDatabase.DeckCardDetail;

/**
 * Behaviour tests for LB cards, the Forwards cast from the Limit Break deck, one section per card.
 *
 * <p>Sections run in serial order — set by set, then card number within a set — so a card's tests
 * are found by its serial, and a new set's LB cards go on the end. Each section opens with a banner
 * naming the card, its LB cost and the printed text under test. The first section is the one
 * exception: it holds what is true of every LB card whatever its abilities.
 *
 * <p>Two kinds of LB test live elsewhere, on purpose. The LB deck's own rules — what may be cast
 * from it, when, and how the CPU plans a cast — are in {@link CardBehaviorTest}, beside the
 * cast-limit gates they share with the hand. And an LB card that is one member of a family read by
 * a shared parser (23-130H Luso with Relm and Alisaie's choose-then-search, 25-111H The Emperor
 * among the search-trigger printings) is tested with its family, where a change to that parser is
 * checked against every card it reads.
 *
 * <p>A card under test is built by {@link #makeLbForward} from its printed text, through the same
 * builder a dealt deck goes through, so it carries every ability and property the game would read
 * off it. Its triggers resolve from P2's seat — {@link #enterAsP2}, {@link #attackAsP2} — where the
 * CPU makes the choices, so each board is built to leave it one legal target, with an illegal one
 * beside it wherever the card restricts what it may choose. A P1 choice opens a modal dialog and
 * hangs the JVM.
 */
class LimitBehaviorTest {

	/**
	 * An LB Forward as a dealt deck builds it ({@link MainWindow#buildCardData}): every property
	 * read off {@code text}, which is the printed text as the database stores it.
	 */
	private static CardData makeLbForward(String name, String element, int cost, int power, int lbCost,
			String text) {
		return makeLbForward(name, element, cost, power, lbCost, null, null, text);
	}

	/** {@link #makeLbForward}, for a card whose Job or Category the test depends on. */
	private static CardData makeLbForward(String name, String element, int cost, int power, int lbCost,
			String job, String category, String text) {
		return makeLbForward(name, element, cost, power, lbCost, job, category, null, text);
	}

	/** {@link #makeLbForward}, for a card printed with two Categories. */
	private static CardData makeLbForward(String name, String element, int cost, int power, int lbCost,
			String job, String category1, String category2, String text) {
		return MainWindow.buildCardData(new DeckCardDetail(null, name, element, cost, power, "Forward",
				true, lbCost, false, text, false, job, category1, category2));
	}

	/** A main-deck card with no text, built the same way, for a test that reads its Job or Category. */
	private static CardData makePlainCard(String name, String element, String type, int cost, int power,
			String job, String category) {
		return MainWindow.buildCardData(new DeckCardDetail(null, name, element, cost, power, type,
				false, 0, false, "", false, job, category, null));
	}

	/** Plays {@code card} onto P2's field and resolves what its arrival put on the Stack. */
	private static void enterAsP2(MainWindow mw, CardData card) {
		resolveWhatQueues(mw, card, () -> placeP2Forward(mw, card));
	}

	/** Declares {@code card} attacking from P2's field and resolves what that put on the Stack. */
	private static void attackAsP2(MainWindow mw, CardData card) {
		resolveWhatQueues(mw, card, () -> mw.autoAbilityTriggers.triggerAutoAbilitiesForAttack(card, false));
	}

	/**
	 * Runs {@code trigger}, then resolves each Stack entry it added, top first, the way the Stack's
	 * own auto-ability arm does: with the targets chosen as it went on the Stack, under its source.
	 * Entries already on the Stack are left alone — a card placed earlier in the test queued its
	 * arrival there, and that is not the ability under test.
	 */
	private static void resolveWhatQueues(MainWindow mw, CardData source, Runnable trigger) {
		int before = mw.gameState.getStack().size();
		trigger.run();
		assertTrue(mw.gameState.getStack().size() > before, source.name() + "'s ability went on the Stack");
		resolveDownTo(mw, before);
	}

	/** Resolves the Stack's top entries, top first, until {@code depth} entries are left. */
	private static void resolveDownTo(MainWindow mw, int depth) {
		while (mw.gameState.getStack().size() > depth) resolveTop(mw);
	}

	/**
	 * Pops the Stack's top entry, an auto or action ability, and resolves it the way the Stack's own
	 * arms do: with the targets chosen as it went on the Stack, under its source.
	 */
	private static void resolveTop(MainWindow mw) {
		StackEntry e = mw.gameState.popStack();
		String text = e.autoAbility() != null ? e.autoAbility().effectText() : e.ability().effectText();
		Consumer<GameContext> effect = ActionResolver.parse(text, e.source(), e.xValue());
		assertNotNull(effect, "resolves: " + text);
		GameContext ctx = mw.buildGameContext(e.isP1());
		if (e.preSelectedTargets() != null) ctx.preloadTargets(mw.liveTargets(e));
		mw.currentAbilitySource = e.source();
		mw.currentAbilitySourceIsP1 = e.isP1();
		try { effect.accept(ctx); } finally { mw.currentAbilitySource = null; }
	}

	/**
	 * Resolves {@code text} as an action ability of {@code source} used from {@code isP1}'s seat,
	 * as the Stack would: under its source, against that seat's context. From P1's seat only for
	 * an effect in which P1 makes no choice.
	 */
	private static void useAbilityAs(MainWindow mw, boolean isP1, CardData source, String text) {
		Consumer<GameContext> effect = ActionResolver.parse(text, source);
		assertNotNull(effect, text);
		mw.currentAbilitySource = source;
		mw.currentAbilitySourceIsP1 = isP1;
		try { effect.accept(mw.buildGameContext(isP1)); } finally { mw.currentAbilitySource = null; }
	}

	/** {@link #useAbilityAs} for a Summon's effect, which resolves as a Summon and not as an ability. */
	private static void castSummonAs(MainWindow mw, boolean isP1, String text) {
		CardData summon = makeSummon("Bolt", "Lightning", 3, text);
		Consumer<GameContext> effect = ActionResolver.parse(text, summon);
		assertNotNull(effect, text);
		mw.currentSummonSource = summon;
		mw.currentSummonSourceIsP1 = isP1;
		mw.currentResolutionIsSummon = true;
		try { effect.accept(mw.buildGameContext(isP1)); } finally {
			mw.currentSummonSource = null;
			mw.currentResolutionIsSummon = false;
		}
	}

	private static int p1Damage(MainWindow mw, CardData card) {
		return mw.p1ForwardDamage.get(mw.p1ForwardCards.indexOf(card));
	}

	private static int p2Damage(MainWindow mw, CardData card) {
		return mw.p2ForwardDamage.get(mw.p2ForwardCards.indexOf(card));
	}

	/** Puts {@code card} on top of {@code isP1}'s deck, with its owner recorded. */
	private static void putOnTopOfDeck(MainWindow mw, CardData card, boolean isP1) {
		mw.gameState.getIdentity().put(card, isP1);
		(isP1 ? mw.gameState.getP1MainDeck() : mw.gameState.getP2MainDeck()).addFirst(card);
	}

	/** Puts {@code count} plain Forwards on top of {@code isP1}'s deck. */
	private static void putPlainCardsOnTop(MainWindow mw, boolean isP1, int count) {
		for (int i = 0; i < count; i++) putOnTopOfDeck(mw, makeForward("Deck " + i, "Water", 2, 5000), isP1);
	}

	/** Puts {@code card} into P2's Break Zone, with its owner recorded. */
	private static void putInP2BreakZone(MainWindow mw, CardData card) {
		mw.gameState.getIdentity().put(card, false);
		mw.gameState.getP2BreakZone().add(card);
	}

	// =========================================================================================
	// Every LB card. Two things hold for all of them, whatever their abilities:
	//
	//   - The "Limit Break -- N" line is a cost declaration, which the ETL already reads into
	//     lb_cost. It grants the card nothing, so it is not an ability of any kind.
	//   - An LB card leaving the field is put into the Break Zone, so "put from the field into the
	//     Break Zone" triggers see it, and then moves on to the LB deck face up.
	// =========================================================================================

	@Test
	void theLimitBreakDeclarationIsNotAFieldAbility() {
		// It is a cost declaration the ETL already reads into lb_cost, so it grants the card
		// nothing and must not sit in fieldAbilities() as a record nothing will ever parse.
		String text = "Limit Break -- 1[[br]]Jack Garland is also Card Name Garland in all situations.[[br]]"
				+ "Jack Garland cannot be blocked by a Monster that is also a Forward.";
		List<FieldAbility> fas = CardData.parseFieldAbilities(text, "Forward");

		assertEquals(1, fas.size(), "only the block restriction is an ability");
		assertEquals("Jack Garland cannot be blocked by a Monster that is also a Forward.",
				fas.get(0).effectText(), "the alias declaration was already excluded; the LB line now is too");
	}

	@Test
	void aLimitBreakCardKeepsItsRealAbilitiesAtTheirNewIndices() {
		// The exclusion renumbers every LB card's abilities, so the guard is that nothing is lost.
		String text = "(Cards with 《LB》 cannot be included in your main deck.)[[br]]Limit Break -- 1[[br]]"
				+ "Brave[[br]]Maat must attack once per turn if possible.";
		List<FieldAbility> fas = CardData.parseFieldAbilities(text, "Forward");

		assertEquals(1, fas.size(), "the reminder, the LB line and the keyword all drop out");
		assertEquals("Maat must attack once per turn if possible.", fas.get(0).effectText());
	}

	@Test
	void lbCardPassesThroughTheBreakZoneOnItsWayToTheLbDeck() {
		// An LB card leaving the field is put into the Break Zone — so "put from the field into the
		// Break Zone" triggers fire — and then moves on to the LB deck face up. It must not stay in
		// the Break Zone, and must not be left in no zone at all.
		MainWindow mw = new MainWindow();
		CardData drake = makeWindDrake();
		mw.gameState.getP2LbDeck().add(drake);
		placeP2Forward(mw, drake);

		mw.breakP2Forward(mw.p2ForwardCards.indexOf(drake));

		assertTrue(mw.p2ForwardCards.isEmpty(), "it left the field");
		assertTrue(mw.gameState.getP2BreakZone().isEmpty(), "an LB card does not stay in the Break Zone");
		assertTrue(mw.gameState.getP2PermanentRfp().isEmpty(), "nor is it removed from the game");
		assertEquals(List.of(drake), mw.gameState.getP2LbDeck(), "it is back in the LB deck");
	}

	@Test
	void nonLbCardStaysInTheBreakZone() {
		MainWindow mw = new MainWindow();
		CardData plain = makeForward("Plain", "Wind", 3, 7000);
		placeP2Forward(mw, plain);

		mw.breakP2Forward(mw.p2ForwardCards.indexOf(plain));

		assertEquals(List.of(plain), mw.gameState.getP2BreakZone(),
				"only LB cards pass through — the control for the test above");
	}

	// =========================================================================================
	// 22-112R Zack (LB 1): "When Zack enters the field, choose 1 Forward. Deal it 3000 damage."
	// =========================================================================================

	private static final String ZACK_22_112R = "(Cards with 《LB》 cannot be included in your main deck.)"
			+ "[[br]]   Limit Break -- 1[[br]]   When Zack enters the field, choose 1 Forward. Deal it 3000 damage.";

	@Test
	void zackDeals3000DamageToAForwardAcrossTheTable() {
		// "Choose 1 Forward" reaches Zack himself too; the CPU aims it across the table.
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 4, 7000);
		placeP1Forward(mw, theirs);
		CardData zack = makeLbForward("Zack", "Fire", 3, 7000, 1, ZACK_22_112R);

		enterAsP2(mw, zack);

		assertEquals(3000, p1Damage(mw, theirs));
		assertEquals(0, p2Damage(mw, zack), "not at himself");
	}

	// =========================================================================================
	// 22-113L Mont Leonis (LB 3): "When Mont Leonis enters the field, choose 1 Fire Forward of cost
	// 3 or less in your Break Zone and 1 Fire Forward of cost 5 or less in your Break Zone. If you
	// control 5 or more Fire Backups, play them onto the field. They gain Haste until the end of
	// the turn. Then, put 1 Backup you control into the Break Zone."
	//
	// Two official FAQ rulings drive the shape:
	//   - unless both Forwards can be chosen the ability is not placed on the stack;
	//   - with too few Backups you "also do not put a Backup into the Break Zone" — the control
	//     condition governs the sacrifice as well as the play.
	// =========================================================================================

	private static final String MONT_LEONIS_TEXT =
			"Limit Break -- 3[[br]]   When Mont Leonis enters the field, choose 1 Fire Forward of cost 3 or less "
			+ "in your Break Zone and 1 Fire Forward of cost 5 or less in your Break Zone. If you control 5 or "
			+ "more Fire Backups, play them onto the field. They gain Haste until the end of the turn. "
			+ "Then, put 1 Backup you control into the Break Zone.";

	private static CardData makeMontLeonis() {
		return new CardData(null, "Mont Leonis", "Fire", 8, 9000, "Forward", true, 3, false, false,
				Set.of(), 0, List.of(), null, List.of(),
				List.of(), CardData.parseAutoAbilities(MONT_LEONIS_TEXT),
				List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
				false, false, null, false, false, false, false, false, 1,
				"Archlord", "FFBE", null, MONT_LEONIS_TEXT);
	}

	/** The card's enters-the-field ability text, as CardData extracts it. */
	private static String montLeonisEffect() {
		CardData mont = makeMontLeonis();
		assertEquals(1, mont.autoAbilities().size(), "the ETB ability should be extracted");
		return mont.autoAbilities().get(0).effectText();
	}

	private static final ForwardTarget BZ_LOW  = new ForwardTarget(true, 1, ForwardTarget.CardZone.BREAK_ZONE);

	private static final ForwardTarget BZ_HIGH = new ForwardTarget(true, 4, ForwardTarget.CardZone.BREAK_ZONE);

	/** A context with a Break Zone deep enough to supply both Forwards. */
	private static GameContext montLeonisContext(boolean conditionMet) {
		GameContext ctx = mock(GameContext.class);
		when(ctx.isP1()).thenReturn(true);
		// Unstubbed this returns an empty list, not null, which makes selectTargets treat the
		// sacrifice as already-targeted and skip the selection entirely.
		when(ctx.consumePreloadedTargets()).thenReturn(null);
		when(ctx.countSelfBreakZoneMatching(true, false, false, false, "fire", 3)).thenReturn(1);
		when(ctx.countSelfBreakZoneMatching(true, false, false, false, "fire", 5)).thenReturn(3);
		when(ctx.selectTwoOwnBreakZoneForwards("fire", 3, 5)).thenReturn(List.of(BZ_LOW, BZ_HIGH));
		when(ctx.controlConditionMet(any())).thenReturn(conditionMet);
		return ctx;
	}

	@Test
	void montLeonisPlaysBothForwardsWithHasteAndTakesTheBackupCost() {
		Consumer<GameContext> fn = ActionResolver.parse(montLeonisEffect(), makeMontLeonis());
		assertNotNull(fn, "Mont Leonis' enters-the-field ability should parse");

		GameContext ctx = montLeonisContext(true);
		ForwardTarget landedLow  = new ForwardTarget(true, 2, ForwardTarget.CardZone.FORWARD);
		ForwardTarget landedHigh = new ForwardTarget(true, 3, ForwardTarget.CardZone.FORWARD);
		when(ctx.playTargetOntoField(BZ_LOW)).thenReturn(landedLow);
		when(ctx.playTargetOntoField(BZ_HIGH)).thenReturn(landedHigh);
		ForwardTarget sacrificed = new ForwardTarget(true, 0, ForwardTarget.CardZone.BACKUP);
		when(ctx.selectCharacters(anyInt(), anyBoolean(), anyBoolean(), anyBoolean(), any(), any(),
				anyInt(), any(), anyInt(), any(), anyBoolean(), anyBoolean(), anyBoolean(),
				any(), any(), any(), any(), anyBoolean(), any(), anyBoolean()))
				.thenReturn(List.of(sacrificed));

		fn.accept(ctx);

		verify(ctx).playTargetOntoField(BZ_LOW);
		verify(ctx).playTargetOntoField(BZ_HIGH);
		EnumSet<CardData.Trait> haste = EnumSet.of(CardData.Trait.HASTE);
		verify(ctx).boostTarget(landedLow, 0, haste);
		verify(ctx).boostTarget(landedHigh, 0, haste);
		verify(ctx).forceTargetToBreakZone(sacrificed);
	}

	// Playing removes the card from the Break Zone, so the deeper index has to go first or the
	// shallower target would slide out from under the second play.
	@Test
	void montLeonisPlaysTheDeeperBreakZoneIndexFirst() {
		Consumer<GameContext> fn = ActionResolver.parse(montLeonisEffect(), makeMontLeonis());
		GameContext ctx = montLeonisContext(true);
		when(ctx.playTargetOntoField(any())).thenReturn(null);

		fn.accept(ctx);

		InOrder order = inOrder(ctx);
		order.verify(ctx).playTargetOntoField(BZ_HIGH);
		order.verify(ctx).playTargetOntoField(BZ_LOW);
	}

	// FAQ: "If you control 4 or less Backups, you also do not put a Backup into the Break Zone."
	@Test
	void montLeonisTakesNoBackupCostWhenTheControlConditionFails() {
		Consumer<GameContext> fn = ActionResolver.parse(montLeonisEffect(), makeMontLeonis());
		GameContext ctx = montLeonisContext(false);

		fn.accept(ctx);

		verify(ctx, never()).playTargetOntoField(any());
		verify(ctx, never()).boostTarget(any(), anyInt(), any());
		verify(ctx, never()).forceTargetToBreakZone(any());
	}

	// FAQ: unless both Forwards can be chosen the auto-ability is never placed on the stack, so
	// the player is not asked to pick anything.
	@Test
	void montLeonisDoesNotTriggerWhenTheBreakZoneCannotSupplyBoth() {
		Consumer<GameContext> fn = ActionResolver.parse(montLeonisEffect(), makeMontLeonis());
		GameContext ctx = mock(GameContext.class);
		when(ctx.countSelfBreakZoneMatching(true, false, false, false, "fire", 3)).thenReturn(1);
		when(ctx.countSelfBreakZoneMatching(true, false, false, false, "fire", 5)).thenReturn(1);

		fn.accept(ctx);

		verify(ctx, never()).selectTwoOwnBreakZoneForwards(any(), anyInt(), anyInt());
		verify(ctx, never()).controlConditionMet(any());
		verify(ctx, never()).playTargetOntoField(any());
		verify(ctx, never()).forceTargetToBreakZone(any());
	}

	@Test
	void montLeonisDoesNotTriggerWithNoCheapEnoughForward() {
		Consumer<GameContext> fn = ActionResolver.parse(montLeonisEffect(), makeMontLeonis());
		GameContext ctx = mock(GameContext.class);
		// Plenty of cost-5 Forwards, but nothing at cost 3 or less to fill the first slot.
		when(ctx.countSelfBreakZoneMatching(true, false, false, false, "fire", 3)).thenReturn(0);
		when(ctx.countSelfBreakZoneMatching(true, false, false, false, "fire", 5)).thenReturn(4);

		fn.accept(ctx);

		verify(ctx, never()).selectTwoOwnBreakZoneForwards(any(), anyInt(), anyInt());
		verify(ctx, never()).playTargetOntoField(any());
	}

	@Test
	void montLeonisReportsItsOwnPatternName() {
		CardData mont = makeMontLeonis();
		String effect = montLeonisEffect();
		assertEquals("ChooseTwoBzFwdPlayIfControl", ActionResolver.matchedPatternName(effect, mont));
		assertEquals("ChooseTwoBzFwdPlayIfControl", ActionResolver.fullDescription(effect, mont));
	}

	// =========================================================================================
	// 22-114H Viktora (LB 2): "If your opponent has no cards in their hand, Viktora gains +3000
	// power.[[br]]When Viktora enters the field, choose 1 Forward. If your opponent has 2 cards or
	// less in their hand, deal it 7000 damage."
	//
	// Both halves count the opponent's hand, against different thresholds.
	// =========================================================================================

	private static final String VIKTORA_22_114H = "Limit Break -- 2[[br]]   If your opponent has no cards in "
			+ "their hand, Viktora gains +3000 power.[[br]]   When Viktora enters the field, choose 1 Forward. If "
			+ "your opponent has 2 cards or less in their hand, deal it 7000 damage.";

	private static CardData makeViktora() {
		return makeLbForward("Viktora", "Ice", 4, 7000, 2, VIKTORA_22_114H);
	}

	private static void fillP1Hand(MainWindow mw, int count) {
		for (int i = 0; i < count; i++) {
			CardData c = makeForward("Held " + i, "Water", 2, 5000);
			mw.gameState.getIdentity().put(c, true);
			mw.gameState.getP1Hand().add(c);
		}
	}

	@Test
	void viktoraGains3000PowerOnlyWhileTheOpponentsHandIsEmpty() {
		MainWindow mw = new MainWindow();
		CardData viktora = makeViktora();
		placeP2Forward(mw, viktora);
		int idx = mw.p2ForwardCards.indexOf(viktora);

		assertEquals(10000, mw.effectiveP2ForwardPower(idx), "no cards in the opponent's hand");
		fillP1Hand(mw, 1);
		assertEquals(7000, mw.effectiveP2ForwardPower(idx), "one card switches it off");
	}

	@Test
	void viktoraDeals7000WhileTheOpponentHoldsTwoCardsOrFewer() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 4, 9000);
		placeP1Forward(mw, theirs);
		fillP1Hand(mw, 2);

		enterAsP2(mw, makeViktora());

		assertEquals(7000, p1Damage(mw, theirs), "two is still \"2 cards or less\"");
	}

	@Test
	void viktoraDealsNothingAgainstAThirdCard() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 4, 9000);
		placeP1Forward(mw, theirs);
		fillP1Hand(mw, 3);

		enterAsP2(mw, makeViktora());

		assertEquals(0, p1Damage(mw, theirs));
	}

	// =========================================================================================
	// 22-115R Serjes (LB 1): "When Serjes enters the field, select 1 of the 2 following actions.
	// "Choose 1 Forward. Dull it." "Choose 1 Forward. Freeze it.""
	//
	// The trigger layer resolves a select as the card arrives, without putting it on the Stack, so
	// these place Serjes and read the board. The CPU picks the action, which between them covers
	// both: a Forward that is active has its dull to lose, one that is already dull has only its
	// next activation.
	// =========================================================================================

	private static final String SERJES_22_115R = "(Cards with 《LB》 cannot be included in your main deck.)"
			+ "[[br]]   Limit Break -- 1[[br]]   When Serjes enters the field, select 1 of the 2 following "
			+ "actions.[[br]]   \"Choose 1 Forward. Dull it.\"[[br]]   \"Choose 1 Forward. Freeze it.\"";

	private static CardData makeSerjes() {
		return makeLbForward("Serjes", "Ice", 3, 7000, 1, SERJES_22_115R);
	}

	@Test
	void serjesDullsAnActiveForwardAcrossTheTable() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 4, 7000);
		placeP1Forward(mw, theirs);
		CardData serjes = makeSerjes();

		placeP2Forward(mw, serjes);

		int i = mw.p1ForwardCards.indexOf(theirs);
		assertEquals(CardState.DULL, mw.p1ForwardStates.get(i));
		assertFalse(mw.p1ForwardFrozen.get(i), "one action of the two");
		int s = mw.p2ForwardCards.indexOf(serjes);
		assertEquals(CardState.ACTIVE, mw.p2ForwardStates.get(s), "not himself");
		assertFalse(mw.p2ForwardFrozen.get(s));
	}

	@Test
	void serjesFreezesAForwardThatIsAlreadyDull() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 4, 7000);
		placeP1Forward(mw, theirs);
		int i = mw.p1ForwardCards.indexOf(theirs);
		mw.p1ForwardStates.set(i, CardState.DULL);

		placeP2Forward(mw, makeSerjes());

		assertTrue(mw.p1ForwardFrozen.get(i), "dulling it again would do nothing");
	}

	// =========================================================================================
	// 22-116L Ace (LB 3): "When Ace enters the field, deal 1000 damage for each Wind Character you
	// control to all the Forwards opponent controls.[[br]]When Ace attacks, reveal the top card of
	// your deck. If it is a Wind card, add it to your hand."
	//
	// A Character is a Forward, Backup or Monster, and Ace is a Wind one himself, so he counts
	// towards his own damage.
	// =========================================================================================

	private static final String ACE_22_116L = "Limit Break -- 3[[br]]   When Ace enters the field, deal 1000 "
			+ "damage for each Wind Character you control to all the Forwards opponent controls.[[br]]   When "
			+ "Ace attacks, reveal the top card of your deck. If it is a Wind card, add it to your hand.";

	private static CardData makeAce() {
		return makeLbForward("Ace", "Wind", 8, 8000, 3, ACE_22_116L);
	}

	@Test
	void aceDeals1000PerWindCharacterCountingHimselfAndBackups() {
		MainWindow mw = new MainWindow();
		CardData ally = makeForward("Wind Ally", "Wind", 3, 7000);
		placeP2Forward(mw, ally);
		placeP2Backup(mw, makeBackup("Wind Aide", "Wind", 2));
		placeP2Backup(mw, makeBackup("Fire Aide", "Fire", 2));
		CardData big   = makeForward("Big", "Water", 5, 9000);
		CardData small = makeForward("Small", "Ice", 2, 3000);
		placeP1Forward(mw, big);
		placeP1Forward(mw, small);
		CardData ace = makeAce();

		enterAsP2(mw, ace);

		assertEquals(3000, p1Damage(mw, big), "Ace, the Wind Forward and the Wind Backup; not the Fire Backup");
		assertTrue(mw.gameState.getP1BreakZone().contains(small), "all the Forwards opponent controls");
		assertEquals(0, p2Damage(mw, ace), "and none of his own");
		assertEquals(0, p2Damage(mw, ally));
	}

	@Test
	void aceAddsARevealedWindCardToHand() {
		MainWindow mw = new MainWindow();
		CardData ace = makeAce();
		placeP2Forward(mw, ace);
		CardData wind = makeForward("Wind Top", "Wind", 2, 5000);
		putOnTopOfDeck(mw, wind, false);

		attackAsP2(mw, ace);

		assertEquals(List.of(wind), mw.gameState.getP2Hand());
		assertTrue(mw.gameState.getP2MainDeck().isEmpty());
	}

	@Test
	void aceLeavesARevealedCardOfAnotherElementOnTop() {
		MainWindow mw = new MainWindow();
		CardData ace = makeAce();
		placeP2Forward(mw, ace);
		CardData fire = makeForward("Fire Top", "Fire", 2, 5000);
		putOnTopOfDeck(mw, fire, false);

		attackAsP2(mw, ace);

		assertTrue(mw.gameState.getP2Hand().isEmpty());
		assertSame(fire, mw.gameState.getP2MainDeck().peekFirst(), "revealed and left where it was");
	}

	// =========================================================================================
	// 22-117R Yuri (LB 2): "If you control 5 or more Backups, Yuri gains +4000 power.[[br]]When Yuri
	// enters the field, you may search for 1 Card Name Chelinka Forward of cost 2 or less and play
	// it onto the field."
	// =========================================================================================

	private static final String YURI_22_117R = "(Cards with 《LB》 cannot be included in your main deck.)"
			+ "[[br]]   Limit Break -- 2[[br]]   If you control 5 or more Backups, Yuri gains +4000 power.[[br]]   "
			+ "When Yuri enters the field, you may search for 1 Card Name Chelinka Forward of cost 2 or less "
			+ "and play it onto the field.";

	private static CardData makeYuri() {
		return makeLbForward("Yuri", "Wind", 5, 4000, 2, YURI_22_117R);
	}

	@Test
	void yuriGains4000PowerFromTheFifthBackup() {
		MainWindow mw = new MainWindow();
		CardData yuri = makeYuri();
		placeP2Forward(mw, yuri);
		for (int i = 0; i < 4; i++) placeP2Backup(mw, makeBackup("Aide " + i, "Wind", 2));
		int idx = mw.p2ForwardCards.indexOf(yuri);

		assertEquals(4000, mw.effectiveP2ForwardPower(idx), "four Backups");
		placeP2Backup(mw, makeBackup("Aide 4", "Wind", 2));
		assertEquals(8000, mw.effectiveP2ForwardPower(idx), "five");
	}

	@Test
	void yuriPlaysAChelinkaForwardOfCost2OrLessFromTheDeck() {
		MainWindow mw = new MainWindow();
		CardData chelinka = makeForward("Chelinka", "Wind", 2, 5000);
		CardData pricey   = makeForward("Chelinka", "Wind", 3, 6000);
		CardData backup   = makeBackup("Chelinka", "Wind", 1);
		CardData stranger = makeForward("Stranger", "Wind", 2, 5000);
		for (CardData c : List.of(pricey, backup, stranger, chelinka)) putOnTopOfDeck(mw, c, false);

		enterAsP2(mw, makeYuri());

		assertTrue(mw.p2ForwardCards.contains(chelinka), "played onto the field");
		assertFalse(mw.gameState.getP2MainDeck().contains(chelinka));
		assertEquals(3, mw.gameState.getP2MainDeck().size());
		assertTrue(mw.gameState.getP2MainDeck().containsAll(List.of(pricey, backup, stranger)),
				"cost 3, a Backup and another name all stay behind");
	}

	// =========================================================================================
	// 22-119R Maat (LB 1): "Brave[[br]]When Maat enters the field, until the end of the turn, all the
	// Forwards you control gain +1000 power and Brave."
	// =========================================================================================

	private static final String MAAT_22_119R = "(Cards with 《LB》 cannot be included in your main deck.)"
			+ "[[br]]   Limit Break -- 1[[br]]   Brave[[br]]   When Maat enters the field, until the end of the "
			+ "turn, all the Forwards you control gain +1000 power and Brave.";

	private static CardData makeMaat() {
		return makeLbForward("Maat", "Earth", 4, 8000, 1, MAAT_22_119R);
	}

	@Test
	void maatGivesTheForwardsYouControlPlus1000AndBraveForTheTurn() {
		MainWindow mw = new MainWindow();
		CardData ally   = makeForward("Ally", "Earth", 3, 5000);
		CardData theirs = makeForward("Theirs", "Water", 3, 5000);
		placeP2Forward(mw, ally);
		placeP1Forward(mw, theirs);
		CardData maat = makeMaat();

		enterAsP2(mw, maat);

		int a = mw.p2ForwardCards.indexOf(ally);
		int t = mw.p1ForwardCards.indexOf(theirs);
		assertEquals(6000, mw.effectiveP2ForwardPower(a));
		assertTrue(mw.effectiveP2HasTrait(a, CardData.Trait.BRAVE), "the power and the Brave");
		assertEquals(9000, mw.effectiveP2ForwardPower(mw.p2ForwardCards.indexOf(maat)), "Maat is one of them");
		assertEquals(5000, mw.effectiveP1ForwardPower(t), "only the Forwards you control");
		assertFalse(mw.effectiveP1HasTrait(t, CardData.Trait.BRAVE));

		mw.turnPhases().runP2EndOfTurnCleanup();
		assertEquals(5000, mw.effectiveP2ForwardPower(a));
		assertFalse(mw.effectiveP2HasTrait(a, CardData.Trait.BRAVE), "until the end of the turn");
	}

	@Test
	void maatsOwnBraveIsPrintedAndOutlastsTheTurn() {
		MainWindow mw = new MainWindow();
		CardData maat = makeMaat();
		placeP2Forward(mw, maat);

		mw.turnPhases().runP2EndOfTurnCleanup();

		assertTrue(mw.effectiveP2HasTrait(mw.p2ForwardCards.indexOf(maat), CardData.Trait.BRAVE));
	}

	// =========================================================================================
	// 22-120H Cloud (LB 2): "When Cloud enters the field, choose 1 Forward of cost 3 or less. Break
	// it.[[br]][[s]]Limit Burst[[/]] 《S》《Lightning》《Lightning》《Lightning》《Lightning》《Dull》:
	// Choose 1 Forward opponent controls. Break it. Cloud deals your opponent 1 point of damage."
	//
	// Limit Burst is a Special Ability: besides the CP and the dull, it is paid by discarding a card
	// named Cloud.
	// =========================================================================================

	private static final String CLOUD_22_120H = "Limit Break -- 2[[br]]   When Cloud enters the field, choose 1 "
			+ "Forward of cost 3 or less. Break it.[[br]]   [[s]]Limit Burst[[/]] 《S》《Lightning》《Lightning》"
			+ "《Lightning》《Lightning》《Dull》: Choose 1 Forward opponent controls. Break it. Cloud deals your "
			+ "opponent 1 point of damage.";

	private static CardData makeCloud() {
		return makeLbForward("Cloud", "Lightning", 4, 8000, 2, CLOUD_22_120H);
	}

	@Test
	void cloudBreaksAForwardOfCost3OrLessOnEntering() {
		MainWindow mw = new MainWindow();
		CardData cheap = makeForward("Cheap", "Water", 3, 9000);
		CardData dear  = makeForward("Dear", "Water", 4, 3000);
		placeP1Forward(mw, cheap);
		placeP1Forward(mw, dear);

		enterAsP2(mw, makeCloud());

		assertTrue(mw.gameState.getP1BreakZone().contains(cheap));
		assertTrue(mw.p1ForwardCards.contains(dear), "cost 4");
	}

	@Test
	void cloudsLimitBurstIsASpecialAbilityPaidWithFourLightningAndADull() {
		List<ActionAbility> actions = makeCloud().actionAbilities();
		assertEquals(1, actions.size());
		ActionAbility burst = actions.get(0);

		assertEquals("Limit Burst", burst.abilityName());
		assertTrue(burst.isSpecial(), "a card named Cloud is discarded too");
		assertTrue(burst.requiresDull());
		assertEquals(List.of("Lightning", "Lightning", "Lightning", "Lightning"), burst.cpCost());
	}

	@Test
	void cloudsLimitBurstBreaksAnOpposingForwardAndDealsAPointOfDamage() {
		MainWindow mw = new MainWindow();
		CardData cloud = makeCloud();
		placeP2Forward(mw, cloud);
		CardData theirs = makeForward("Theirs", "Water", 6, 10000);
		placeP1Forward(mw, theirs);
		putOnTopOfDeck(mw, makeForward("Deck", "Water", 2, 5000), true);

		useAbilityAs(mw, false, cloud, cloud.actionAbilities().get(0).effectText());

		assertTrue(mw.gameState.getP1BreakZone().contains(theirs), "any cost, any power");
		assertEquals(1, mw.gameState.getP1DamageZone().size(), "your opponent");
		assertTrue(mw.gameState.getP2DamageZone().isEmpty());
	}

	// =========================================================================================
	// 22-121R Lightning (LB 2): "When Lightning enters the field, choose 1 Lightning Summon in your
	// Break Zone. Add it to your hand."
	// =========================================================================================

	private static final String LIGHTNING_22_121R = "(Cards with 《LB》 cannot be included in your main deck.)"
			+ "[[br]]   Limit Break -- 2[[br]]   When Lightning enters the field, choose 1 Lightning Summon in "
			+ "your Break Zone. Add it to your hand.";

	@Test
	void lightningReturnsALightningSummonFromTheBreakZone() {
		MainWindow mw = new MainWindow();
		CardData ramuh = makeSummon("Ramuh", "Lightning", 3, "");
		CardData ifrit = makeSummon("Ifrit", "Fire", 3, "");
		CardData bolt  = makeForward("Bolt", "Lightning", 3, 7000);
		for (CardData c : List.of(ifrit, bolt, ramuh)) putInP2BreakZone(mw, c);

		enterAsP2(mw, makeLbForward("Lightning", "Lightning", 4, 5000, 2, LIGHTNING_22_121R));

		assertEquals(List.of(ramuh), mw.gameState.getP2Hand());
		assertTrue(mw.gameState.getP2BreakZone().containsAll(List.of(ifrit, bolt)),
				"a Summon of another Element, and a Lightning card that is not a Summon");
	}

	// =========================================================================================
	// 22-122L Tidus (LB 3): "When Tidus enters the field, if you control 8 or more Forwards and/or
	// Backups, you may pay 《Water》《Water》《Water》. When you do so, select up to 3 of the 3
	// following actions. "Choose 1 Forward. Put it at the bottom of its owner's deck." "Choose 1
	// Backup. Put it on top of its owner's deck." "Draw 2 cards.""
	//
	// dispatch() has no site for this: the trigger layer reads it through its own inline shape,
	// ConditionGateMay, so the characterization file shows it as "parsed (null) (null)" and says
	// nothing about what it does. These pin the count in front of the payment and each of the three
	// actions on a real board. Driven as P2, where the AI takes every action it is offered.
	// =========================================================================================

	private static final String TIDUS_22_122L = "When Tidus enters the field, if you control 8 or more "
			+ "Forwards and/or Backups, you may pay 《Water》《Water》《Water》. When you do so, select up to 3 "
			+ "of the 3 following actions. \"Choose 1 Forward. Put it at the bottom of its owner's deck.\" "
			+ "\"Choose 1 Backup. Put it on top of its owner's deck.\" \"Draw 2 cards.\"";

	/** Tidus on P2's field with {@code backups} active Water Backups and two other Forwards. */
	private static MainWindow tidusBoard(CardData tidus, int backups) {
		MainWindow mw = new MainWindow();
		mw.gameState.getIdentity().put(tidus, false);
		for (int i = 0; i < backups; i++) {
			CardData b = makeBackup("Backup" + i, "Water", 1);
			mw.gameState.getIdentity().put(b, false);
			mw.placeP2CardInFirstBackupSlot(b);
		}
		// Backups enter dull; these have been in play since an earlier turn.
		Arrays.fill(mw.p2BackupStates, CardState.ACTIVE);
		for (int i = 0; i < 2; i++) {
			CardData f = makeForward("Ally" + i, "Water", 2, 5000);
			mw.gameState.getIdentity().put(f, false);
			mw.placeP2CardInForwardZone(f);
		}
		mw.placeP2CardInForwardZone(tidus);
		// The AI declines an optional payoff naming a Forward while P1 has none to aim it at.
		CardData p1Forward = makeForward("Seymour", "Ice", 7, 9000);
		mw.gameState.getIdentity().put(p1Forward, true);
		mw.placeCardInForwardZone(p1Forward);
		for (int i = 0; i < 5; i++) {
			CardData d = makeForward("Deck" + i, "Water", 1, 1000);
			mw.gameState.getIdentity().put(d, false);
			mw.gameState.getP2MainDeck().add(d);
		}
		return mw;
	}

	private static long dullP2Backups(MainWindow mw) {
		return Arrays.stream(mw.p2BackupStates).filter(s -> s == CardState.DULL).count();
	}

	@Test
	void tidusPaysAndActsWithEightForwardsAndBackups() {
		CardData tidus = makeTextCard("Tidus", "Water", "Forward", 5, 9000, null, TIDUS_22_122L);
		MainWindow mw = tidusBoard(tidus, 5);        // 5 Backups + 3 Forwards = 8

		mw.autoAbilityTriggers.triggerAutoAbilitiesForEntersField(tidus, false);

		assertEquals(3, dullP2Backups(mw), "《Water》《Water》《Water》 was paid");
		assertTrue(mw.gameState.getP2Hand().size() >= 2, "the draw action ran: " + mw.gameState.getP2Hand());
	}

	@Test
	void tidusDoesNothingWithSeven() {
		CardData tidus = makeTextCard("Tidus", "Water", "Forward", 5, 9000, null, TIDUS_22_122L);
		MainWindow mw = tidusBoard(tidus, 4);        // 4 + 3 = 7

		mw.autoAbilityTriggers.triggerAutoAbilitiesForEntersField(tidus, false);

		assertEquals(0, dullP2Backups(mw), "nothing paid");
		assertTrue(mw.gameState.getP2Hand().isEmpty());
	}

	@Test
	void tidusRunsAllThreeActions() {
		CardData tidus = makeTextCard("Tidus", "Water", "Forward", 5, 9000, null, TIDUS_22_122L);
		MainWindow mw = tidusBoard(tidus, 5);        // 5 Backups + 3 Forwards = 8
		CardData seymour = mw.p1ForwardCards.get(0);
		CardData p1Backup = makeBackup("Guado Retainer", "Earth", 2);
		mw.gameState.getIdentity().put(p1Backup, true);
		mw.placeCardInFirstBackupSlot(p1Backup);
		mw.gameState.getP1MainDeck().clear();

		mw.autoAbilityTriggers.triggerAutoAbilitiesForEntersField(tidus, false);

		assertEquals(seymour, mw.gameState.getP1MainDeck().peekLast(),
				"the Forward went to the bottom of its owner's deck");
		assertEquals(p1Backup, mw.gameState.getP1MainDeck().peekFirst(),
				"the Backup to the top of its owner's deck");
		assertFalse(Arrays.asList(mw.p1BackupCards).contains(p1Backup), "and off the field");
		assertEquals(2, mw.gameState.getP2Hand().size(), "and Tidus's controller drew 2");
	}

	@Test
	void tidusDoesNotCountAMonsterTowardHisEight() {
		CardData tidus = makeTextCard("Tidus", "Water", "Forward", 5, 9000, null, TIDUS_22_122L);
		MainWindow mw = tidusBoard(tidus, 4);        // 4 Backups + 3 Forwards = 7
		CardData monster = makeTextCard("Flan", "Water", "Monster", 2, 0, null, "");
		mw.gameState.getIdentity().put(monster, false);
		mw.placeP2CardInMonsterZone(monster);
		assertTrue(mw.p2MonsterCards.contains(monster), "the Monster is on the field to be counted");

		mw.autoAbilityTriggers.triggerAutoAbilitiesForEntersField(tidus, false);

		assertEquals(0, dullP2Backups(mw), "a Monster is neither a Forward nor a Backup");
		assertTrue(mw.gameState.getP2Hand().isEmpty());
	}

	@Test
	void tidusWhoCannotPayTheWaterTakesNoAction() {
		CardData tidus = makeTextCard("Tidus", "Water", "Forward", 5, 9000, null, TIDUS_22_122L);
		MainWindow mw = tidusBoard(tidus, 5);        // 8, but every Backup is already dull
		Arrays.fill(mw.p2BackupStates, CardState.DULL);
		CardData seymour = mw.p1ForwardCards.get(0);

		mw.autoAbilityTriggers.triggerAutoAbilitiesForEntersField(tidus, false);

		assertTrue(mw.gameState.getP2Hand().isEmpty(), "no draw without the payment");
		assertTrue(mw.p1ForwardCards.contains(seymour), "and no Forward put into the deck");
	}

	// =========================================================================================
	// 22-123R Leo (LB 1): "When Leo enters the field, draw 1 card."
	// =========================================================================================

	private static final String LEO_22_123R = "(Cards with 《LB》 cannot be included in your main deck.)"
			+ "[[br]]   Limit Break -- 1[[br]]   When Leo enters the field, draw 1 card.";

	@Test
	void leoDrawsTheTopCard() {
		MainWindow mw = new MainWindow();
		CardData next = makeForward("Next", "Water", 2, 5000);
		CardData top  = makeForward("Top", "Water", 2, 5000);
		putOnTopOfDeck(mw, next, false);
		putOnTopOfDeck(mw, top, false);

		enterAsP2(mw, makeLbForward("Leo", "Water", 3, 3000, 1, LEO_22_123R));

		assertEquals(List.of(top), mw.gameState.getP2Hand());
		assertEquals(List.of(next), List.copyOf(mw.gameState.getP2MainDeck()));
	}

	// =========================================================================================
	// 22-124H Little Leela (LB 1): "When Little Leela attacks, choose 1 Forward opponent controls. It
	// loses 7000 power until the end of the turn."
	// =========================================================================================

	private static final String LITTLE_LEELA_22_124H = "Limit Break -- 1[[br]]   When Little Leela attacks, "
			+ "choose 1 Forward opponent controls. It loses 7000 power until the end of the turn.";

	private static CardData makeLittleLeela() {
		return makeLbForward("Little Leela", "Dark", 5, 8000, 1, LITTLE_LEELA_22_124H);
	}

	@Test
	void littleLeelaTakes7000PowerForTheTurn() {
		MainWindow mw = new MainWindow();
		CardData leela = makeLittleLeela();
		placeP2Forward(mw, leela);
		CardData theirs = makeForward("Theirs", "Water", 5, 9000);
		placeP1Forward(mw, theirs);

		attackAsP2(mw, leela);

		int t = mw.p1ForwardCards.indexOf(theirs);
		assertEquals(2000, mw.effectiveP1ForwardPower(t));
		mw.turnPhases().runP2EndOfTurnCleanup();
		assertEquals(9000, mw.effectiveP1ForwardPower(t), "until the end of the turn");
	}

	@Test
	void aForwardLittleLeelaTakesTo0PowerIsPutIntoTheBreakZone() {
		// A rule process, not a break: a Forward with 0 power goes to the Break Zone.
		MainWindow mw = new MainWindow();
		CardData leela = makeLittleLeela();
		placeP2Forward(mw, leela);
		CardData theirs = makeForward("Theirs", "Water", 3, 7000);
		placeP1Forward(mw, theirs);

		attackAsP2(mw, leela);

		assertFalse(mw.p1ForwardCards.contains(theirs));
		assertTrue(mw.gameState.getP1BreakZone().contains(theirs));
	}

	// =========================================================================================
	// 23-118H Ardyn (LB 1): "When Ardyn is put from the field into the Break Zone, you may play 1
	// face down Card Name Ardyn from your LB deck onto the field dull. If you do so, turn 1 face
	// down card in your LB deck face up."  (Parsing, effect wiring and board behaviour.)
	//
	// The only ability in the corpus that reaches into an LB deck for a card rather than casting
	// one, so both halves needed primitives of their own. "Face down" is this engine's unspent: an
	// LB index is face up once cast or spent paying for a cast, and that set is what the LIMIT
	// counter, the LB viewer and FieldGrantCalculator's face-up tally all read.
	//
	// Two things the wording invites and the rules deny. The turn-up is not a cost — the official
	// FAQ says Ardyn plays from the LB deck even with no face-down card left to turn up — so the
	// play's answer gates the turn-up and never the reverse. And the card Ardyn just played is
	// spent by then, so it is not a candidate to turn face up: a lone Ardyn plays itself out and
	// turns nothing.
	// =========================================================================================

	private static final String ARDYN_23_118H =
			"play 1 face down Card Name Ardyn from your LB deck onto the field dull. "
			+ "If you do so, turn 1 face down card in your LB deck face up.";

	/** Builds a MainWindow with {@code lbCards} as P2's LB deck, all face down. */
	private static MainWindow withP2LbDeck(CardData... lbCards) {
		MainWindow mw = new MainWindow();
		mw.gameState.getP2LbDeck().addAll(List.of(lbCards));
		return mw;
	}

	private static CardData ardynLbCard() {
		return makeForward("Ardyn", "Fire", 6, 9000);
	}

	@Test
	void ardynPlaysFromTheLbDeckAndThenPaysWithATurnUp() {
		GameContext ctx = mock(GameContext.class);
		when(ctx.promptYouMay(anyString())).thenReturn(true);
		when(ctx.playFaceDownLbCardOntoFieldDull("Ardyn")).thenReturn(true);

		ActionResolver.parse(ARDYN_23_118H, null).accept(ctx);

		verify(ctx).playFaceDownLbCardOntoFieldDull("Ardyn");
		verify(ctx).turnOneFaceDownLbCardFaceUp();
	}

	@Test
	void decliningArdynTouchesTheLbDeckNotAtAll() {
		// Asked before anything happens, and a decline has to leave no trace: a card arriving on
		// the field is an event other abilities watch.
		GameContext ctx = mock(GameContext.class);
		when(ctx.promptYouMay(anyString())).thenReturn(false);

		ActionResolver.parse(ARDYN_23_118H, null).accept(ctx);

		verify(ctx, never()).playFaceDownLbCardOntoFieldDull(anyString());
		verify(ctx, never()).turnOneFaceDownLbCardFaceUp();
	}

	@Test
	void nothingIsTurnedFaceUpWhenNoArdynWasThereToPlay() {
		// "If you do so" — the turn-up hangs off the play having happened, so an LB deck with no
		// face down Ardyn in it costs the player nothing.
		GameContext ctx = mock(GameContext.class);
		when(ctx.promptYouMay(anyString())).thenReturn(true);
		when(ctx.playFaceDownLbCardOntoFieldDull("Ardyn")).thenReturn(false);

		ActionResolver.parse(ARDYN_23_118H, null).accept(ctx);

		verify(ctx, never()).turnOneFaceDownLbCardFaceUp();
	}

	@Test
	void playingFromTheLbDeckSpendsThatIndexAndLandsTheCardDull() {
		CardData ardyn = ardynLbCard();
		MainWindow mw = withP2LbDeck(ardyn, makeForward("Filler", "Ice", 2, 5000));

		assertTrue(mw.buildGameContext(false).playFaceDownLbCardOntoFieldDull("Ardyn"));

		assertEquals(1, mw.p2ForwardCards.size());
		assertSame(ardyn, mw.p2ForwardCards.get(0));
		assertEquals(CardState.DULL, mw.p2ForwardStates.get(0), "it arrives dull, as printed");
		assertTrue(mw.p2SpentLbIndices.contains(0), "the copy it played is no longer face down");
		assertFalse(mw.p2SpentLbIndices.contains(1), "and nothing else was spent by the play alone");
	}

	@Test
	void theTurnUpTakesACardOtherThanTheOneJustPlayed() {
		MainWindow mw = withP2LbDeck(ardynLbCard(), makeForward("Filler", "Ice", 2, 5000));
		GameContext ctx = mw.buildGameContext(false);

		assertTrue(ctx.playFaceDownLbCardOntoFieldDull("Ardyn"));
		assertTrue(ctx.turnOneFaceDownLbCardFaceUp());

		assertEquals(Set.of(0, 1), mw.p2SpentLbIndices);
		assertEquals(1, mw.p2ForwardCards.size(), "the turn-up puts nothing on the field");
	}

	@Test
	void aLoneArdynStillPlaysWithNothingLeftToTurnFaceUp() {
		// The FAQ case. Ardyn is the only card in the LB deck, so by the time the turn-up is asked
		// there is no face down card at all — and the play stands regardless.
		CardData ardyn = ardynLbCard();
		MainWindow mw = withP2LbDeck(ardyn);
		GameContext ctx = mw.buildGameContext(false);

		assertTrue(ctx.playFaceDownLbCardOntoFieldDull("Ardyn"));
		assertFalse(ctx.turnOneFaceDownLbCardFaceUp(), "nothing left to turn face up");

		assertSame(ardyn, mw.p2ForwardCards.get(0), "and the play is not taken back");
		assertEquals(Set.of(0), mw.p2SpentLbIndices);
	}

	@Test
	void aFaceUpCopyIsNotEligibleAndTheFaceDownOneIsTaken() {
		CardData spentArdyn = ardynLbCard();
		CardData faceDown   = ardynLbCard();
		MainWindow mw = withP2LbDeck(spentArdyn, faceDown);
		mw.p2SpentLbIndices.add(0);

		assertTrue(mw.buildGameContext(false).playFaceDownLbCardOntoFieldDull("Ardyn"));

		assertSame(faceDown, mw.p2ForwardCards.get(0));
		assertEquals(Set.of(0, 1), mw.p2SpentLbIndices);
	}

	@Test
	void anLbDeckWithoutTheNamedCardPlaysNothing() {
		MainWindow mw = withP2LbDeck(makeForward("Filler", "Ice", 2, 5000));

		assertFalse(mw.buildGameContext(false).playFaceDownLbCardOntoFieldDull("Ardyn"));

		assertTrue(mw.p2ForwardCards.isEmpty());
		assertTrue(mw.p2SpentLbIndices.isEmpty());
	}

	@Test
	void ardynsPrintedTextReachesThatParserAsAPutIntoBreakZoneTrigger() {
		// The seam between the card and the parser: the printed sentence has to arrive at the
		// dispatcher as a break-zone trigger on Ardyn itself, with the "you may" lifted off — which
		// is what leaves the effect body for ActionResolver to claim, and why the offer is put by
		// the parser rather than by the trigger layer.
		List<AutoAbility> autos = CardData.parseAutoAbilities(
				"When Ardyn is put from the field into the Break Zone, you may play 1 face down "
				+ "Card Name Ardyn from your LB deck onto the field dull. If you do so, turn 1 face "
				+ "down card in your LB deck face up.");
		assertEquals(1, autos.size());

		AutoAbility auto = autos.get(0);
		assertEquals("put into break zone", auto.trigger());
		assertEquals("Ardyn", auto.triggerCard());
		assertTrue(auto.youMay(), "the offer is printed, and is lifted off the effect text");
		assertNotNull(ActionResolver.parse(auto.effectText(), null),
				"and what is left is what this parser reads");
	}

	@Test
	void ardynIsNamedRatherThanReadAsTheSourceReturningFromTheBreakZone() {
		// The find()-based play-onto-field parser sits just below this one and would take "play …
		// onto the field" out of the middle of the sentence.
		assertEquals("PlayFaceDownLbCardOntoFieldDull",
				ActionResolver.matchedPatternName(ARDYN_23_118H, null));
	}

	// =========================================================================================
	// 23-119R Vincent (LB 2): "First Strike[[br]]When Vincent enters the field, you may put 1 Fire
	// Backup you control into the Break Zone. When you do so, choose 1 Forward opponent controls.
	// Deal it 9000 damage."
	//
	// The trigger layer resolves a "when you do so" as the card arrives, without putting it on the
	// Stack, so these place Vincent and read the board.
	// =========================================================================================

	private static final String VINCENT_23_119R = "Limit Break -- 2[[br]]   First Strike[[br]]   When Vincent "
			+ "enters the field, you may put 1 Fire Backup you control into the Break Zone. When you do so, choose "
			+ "1 Forward opponent controls. Deal it 9000 damage.";

	private static CardData makeVincent() {
		return makeLbForward("Vincent", "Fire", 5, 8000, 2, "Galian Beast", "VII", VINCENT_23_119R);
	}

	@Test
	void vincentPutsAFireBackupIntoTheBreakZoneToDeal9000() {
		MainWindow mw = new MainWindow();
		CardData fire  = makeBackup("Fire Aide", "Fire", 2);
		CardData water = makeBackup("Water Aide", "Water", 2);
		placeP2Backup(mw, fire);
		placeP2Backup(mw, water);
		CardData theirs = makeForward("Theirs", "Water", 6, 10000);
		placeP1Forward(mw, theirs);

		placeP2Forward(mw, makeVincent());

		assertTrue(mw.gameState.getP2BreakZone().contains(fire), "the Fire Backup pays for it");
		assertTrue(Arrays.asList(mw.p2BackupCards).contains(water), "only the one, and only Fire");
		assertEquals(9000, p1Damage(mw, theirs));
	}

	@Test
	void withoutAFireBackupVincentDealsNothing() {
		MainWindow mw = new MainWindow();
		CardData water = makeBackup("Water Aide", "Water", 2);
		placeP2Backup(mw, water);
		CardData theirs = makeForward("Theirs", "Water", 6, 10000);
		placeP1Forward(mw, theirs);

		placeP2Forward(mw, makeVincent());

		assertTrue(Arrays.asList(mw.p2BackupCards).contains(water));
		assertEquals(0, p1Damage(mw, theirs), "no Backup put there, so nothing to do so");
	}

	@Test
	void vincentHasFirstStrike() {
		MainWindow mw = new MainWindow();
		CardData vincent = makeVincent();
		placeP2Forward(mw, vincent);

		assertTrue(mw.effectiveP2HasTrait(mw.p2ForwardCards.indexOf(vincent), CardData.Trait.FIRST_STRIKE));
	}

	// =========================================================================================
	// 23-120R Kuja (LB 2): "When Kuja is chosen by your opponent's Summon or ability, choose up to 1
	// Forward. Freeze it.[[br]]Dull active Kuja: Choose 1 Forward. Dull it."
	//
	// A trigger on being chosen resolves inside the opponent's selection, without going on the
	// Stack, so the auto-ability tests fire the event and read the board.
	// =========================================================================================

	private static final String KUJA_23_120R = "Limit Break -- 2[[br]]   When Kuja is chosen by your opponent's "
			+ "Summon or ability, choose up to 1 Forward. Freeze it.[[br]]   Dull active Kuja: Choose 1 Forward. "
			+ "Dull it.";

	private static CardData makeKuja() {
		return makeLbForward("Kuja", "Ice", 5, 8000, 2, "Genome", "DFF", KUJA_23_120R);
	}

	@Test
	void kujaFreezesAForwardWhenTheOpponentChoosesHim() {
		MainWindow mw = new MainWindow();
		CardData kuja = makeKuja();
		placeP2Forward(mw, kuja);
		CardData theirs = makeForward("Theirs", "Water", 4, 7000);
		placeP1Forward(mw, theirs);
		mw.p1ForwardStates.set(mw.p1ForwardCards.indexOf(theirs), CardState.DULL);

		mw.autoAbilityTriggers.triggerAutoAbilitiesForChosenByOpponentSummonOrAbility(false, List.of(kuja));

		assertTrue(mw.p1ForwardFrozen.get(mw.p1ForwardCards.indexOf(theirs)));
		assertFalse(mw.p2ForwardFrozen.get(mw.p2ForwardCards.indexOf(kuja)), "not himself");
	}

	@Test
	void kujaDoesNotAnswerAnAllyBeingChosen() {
		MainWindow mw = new MainWindow();
		CardData kuja = makeKuja();
		CardData ally = makeForward("Ally", "Ice", 3, 7000);
		placeP2Forward(mw, kuja);
		placeP2Forward(mw, ally);
		CardData theirs = makeForward("Theirs", "Water", 4, 7000);
		placeP1Forward(mw, theirs);
		mw.p1ForwardStates.set(mw.p1ForwardCards.indexOf(theirs), CardState.DULL);

		mw.autoAbilityTriggers.triggerAutoAbilitiesForChosenByOpponentSummonOrAbility(false, List.of(ally));

		assertFalse(mw.p1ForwardFrozen.get(mw.p1ForwardCards.indexOf(theirs)), "\"when Kuja is chosen\"");
	}

	@Test
	void kujasActionAbilityIsPaidByDullingHimself() {
		MainWindow mw = new MainWindow();
		CardData kuja = makeKuja();
		placeP1Forward(mw, kuja);
		ActionAbility dull = kuja.actionAbilities().get(0);

		assertTrue(mw.canActivateAbility(dull, false, CardState.ACTIVE, 0, kuja, true), "an active Kuja pays it");
		mw.p1ForwardStates.set(mw.p1ForwardCards.indexOf(kuja), CardState.DULL);
		assertFalse(mw.canActivateAbility(dull, false, CardState.DULL, 0, kuja, true),
				"a dull one has nothing to pay with");
	}

	@Test
	void kujasActionAbilityDullsTheChosenForward() {
		MainWindow mw = new MainWindow();
		CardData kuja = makeKuja();
		placeP2Forward(mw, kuja);
		CardData theirs = makeForward("Theirs", "Water", 4, 7000);
		placeP1Forward(mw, theirs);

		useAbilityAs(mw, false, kuja, kuja.actionAbilities().get(0).effectText());

		assertEquals(CardState.DULL, mw.p1ForwardStates.get(mw.p1ForwardCards.indexOf(theirs)));
	}

	// =========================================================================================
	// 23-122R Cid Highwind (LB 1): "When Cid Highwind enters the field, reveal the top 3 cards of
	// your deck. Add 1 Backup among them to your hand and return the other cards to the bottom of
	// your deck in any order."
	// =========================================================================================

	private static final String CID_HIGHWIND_23_122R = "Limit Break -- 1[[br]]   When Cid Highwind enters the "
			+ "field, reveal the top 3 cards of your deck. Add 1 Backup among them to your hand and return the "
			+ "other cards to the bottom of your deck in any order.";

	private static CardData makeCidHighwind() {
		return makeLbForward("Cid Highwind", "Wind", 4, 4000, 1, "Engineer", "VII", CID_HIGHWIND_23_122R);
	}

	@Test
	void cidHighwindAddsABackupFromTheTopThree() {
		MainWindow mw = new MainWindow();
		CardData fourth = makeForward("Fourth", "Wind", 2, 5000);
		CardData third  = makeForward("Third", "Wind", 2, 5000);
		CardData backup = makeBackup("Aide", "Wind", 2);
		CardData first  = makeForward("First", "Wind", 2, 5000);
		for (CardData c : List.of(fourth, third, backup, first)) putOnTopOfDeck(mw, c, false);

		enterAsP2(mw, makeCidHighwind());

		assertEquals(List.of(backup), mw.gameState.getP2Hand());
		Deque<CardData> deck = mw.gameState.getP2MainDeck();
		assertSame(fourth, deck.peekFirst(), "the fourth card was never revealed, and is on top now");
		assertEquals(Set.of(first, third), Set.copyOf(List.copyOf(deck).subList(1, 3)), "the rest go under it");
	}

	@Test
	void cidHighwindAddsNothingWhenNoBackupIsRevealed() {
		MainWindow mw = new MainWindow();
		CardData fourth = makeForward("Fourth", "Wind", 2, 5000);
		for (CardData c : List.of(fourth, makeForward("Third", "Wind", 2, 5000),
				makeForward("Second", "Wind", 2, 5000), makeForward("First", "Wind", 2, 5000)))
			putOnTopOfDeck(mw, c, false);

		enterAsP2(mw, makeCidHighwind());

		assertTrue(mw.gameState.getP2Hand().isEmpty(), "Forwards only");
		assertEquals(4, mw.gameState.getP2MainDeck().size());
		assertSame(fourth, mw.gameState.getP2MainDeck().peekFirst(), "all three went to the bottom");
	}

	// =========================================================================================
	// 23-123H Deathgaze (LB 2): "When Deathgaze enters the field, choose 1 Forward of cost 5 or 10.
	// Break it."
	//
	// Two exact costs, not a range: a Forward of cost 6 to 9 is out of reach.
	// =========================================================================================

	private static final String DEATHGAZE_23_123H = "Limit Break -- 2[[br]]   When Deathgaze enters the field, "
			+ "choose 1 Forward of cost 5 or 10. Break it.";

	private static CardData makeDeathgaze() {
		return makeLbForward("Deathgaze", "Wind", 7, 8000, 2, "Deathgaze", "VI", DEATHGAZE_23_123H);
	}

	@Test
	void deathgazeBreaksAForwardOfCost5() {
		MainWindow mw = new MainWindow();
		CardData five = makeForward("Five", "Water", 5, 9000);
		CardData four = makeForward("Four", "Water", 4, 9000);
		CardData six  = makeForward("Six", "Water", 6, 9000);
		for (CardData c : List.of(four, five, six)) placeP1Forward(mw, c);

		enterAsP2(mw, makeDeathgaze());

		assertTrue(mw.gameState.getP1BreakZone().contains(five));
		assertTrue(mw.p1ForwardCards.containsAll(List.of(four, six)), "either side of 5");
	}

	@Test
	void deathgazeBreaksAForwardOfCost10() {
		MainWindow mw = new MainWindow();
		CardData ten  = makeForward("Ten", "Water", 10, 12000);
		CardData nine = makeForward("Nine", "Water", 9, 12000);
		placeP1Forward(mw, nine);
		placeP1Forward(mw, ten);

		enterAsP2(mw, makeDeathgaze());

		assertTrue(mw.gameState.getP1BreakZone().contains(ten));
		assertTrue(mw.p1ForwardCards.contains(nine), "between the two costs");
	}

	// =========================================================================================
	// 23-124L Eiko (LB 3): "When Eiko enters the field, you may search for 1 Summon and remove it
	// from the game. You can cast it without paying the cost this turn."
	//
	// One more way into the free-cast borrowing family, ending where Krile, Terra and Nanaa Mihgo
	// end: a Summon the ability user may cast this turn without paying for it. Eiko's comes out of
	// the deck rather than a Break Zone, which is why the registration needs to know which card the
	// search actually moved — the permission names it and nothing else in the text does.
	// =========================================================================================

	private static final String EIKO_23_124L =
			"search for 1 Summon and remove it from the game. You can cast it without paying the "
			+ "cost this turn.";

	@Test
	void eikoIsNamedRatherThanReportedUnread() {
		assertEquals("SearchSummonRfgFreeCastThisTurn",
				ActionResolver.matchedPatternName(EIKO_23_124L, makeForward("Eiko", "Earth", 6, 9000)));
	}

	@Test
	void eikoRoutesToTheSearchAndRegisterPrimitiveWithNoFilters() {
		GameContext ctx = mock(GameContext.class);
		ActionResolver.parse(EIKO_23_124L, makeForward("Eiko", "Earth", 6, 9000)).accept(ctx);
		verify(ctx).searchSummonRfgFreeCastThisTurn(-1, null);
	}

	@Test
	void eikoRegistersTheSummonTheSearchActuallyMoved() {
		MainWindow mw = new MainWindow();
		CardData summon  = makeSummon("Fetched", "Earth", 4, "");
		CardData decoy   = makeForward("Not a Summon", "Earth", 3, 5000);
		mw.gameState.getIdentity().put(summon, false);
		mw.gameState.getIdentity().put(decoy,  false);
		mw.gameState.getP2MainDeck().add(summon);
		mw.gameState.getP2MainDeck().add(decoy);

		mw.buildGameContext(false).searchSummonRfgFreeCastThisTurn(-1, null);

		assertFalse(mw.gameState.getP2MainDeck().contains(summon), "it left the deck");
		assertTrue(mw.gameState.getP2RemovedFromGame().contains(summon), "and was removed from the game");
		assertTrue(mw.bzPlayableP2.containsKey(summon));
		assertTrue(mw.bzPlayableP2.get(summon).freeCast());
		assertTrue(mw.bzPlayableP2.get(summon).expiresThisTurn(), "\"this turn\"");
		// Not rfgAfterUse: it is already out of the game, and casting it is what puts it into the
		// Break Zone. The Krile/Nanaa clause exists to keep a Summon out of one.
		assertFalse(mw.bzPlayableP2.get(summon).rfgAfterUse());
		assertTrue(mw.gameState.getP2MainDeck().contains(decoy), "the search reads Summons only");
	}

	@Test
	void eikoRegistersNothingWhenTheDeckHoldsNoSummon() {
		MainWindow mw = new MainWindow();
		CardData decoy = makeForward("Not a Summon", "Earth", 3, 5000);
		mw.gameState.getIdentity().put(decoy, false);
		mw.gameState.getP2MainDeck().add(decoy);

		mw.buildGameContext(false).searchSummonRfgFreeCastThisTurn(-1, null);

		assertTrue(mw.bzPlayableP2.isEmpty(), "a search that finds nothing grants nothing");
	}

	@Test
	void theSearchSiblingsStayApartOnTheirDestinationClause() {
		// 2-142R Lenne casts it there and then; Eiko hands over a permission for the turn. Only
		// the last clause separates the two texts, so both patterns are anchored.
		assertEquals("SearchAndCastSummonFree",
				ActionResolver.matchedPatternName(
						"search for 1 Summon and cast it without paying the cost. If you do not "
						+ "cast it, put the Summon into the Break Zone.",
						makeForward("Lenne", "Water", 3, 5000)));
		assertNotEquals("SearchSummonRfgFreeCastThisTurn",
				ActionResolver.matchedPatternName(
						"search for 1 Summon and remove it from the game.",
						makeForward("Eiko", "Earth", 6, 9000)));
	}

	// =========================================================================================
	// 23-125R Noctis (LB 2): "When Noctis enters the field, choose 1 Forward in your Break Zone. Add
	// it to your hand."
	// =========================================================================================

	private static final String NOCTIS_23_125R = "Limit Break -- 2[[br]]   When Noctis enters the field, choose "
			+ "1 Forward in your Break Zone. Add it to your hand.";

	@Test
	void noctisReturnsAForwardFromTheBreakZone() {
		MainWindow mw = new MainWindow();
		CardData forward = makeForward("Gladiolus", "Earth", 3, 7000);
		CardData backup  = makeBackup("Aide", "Earth", 2);
		CardData summon  = makeSummon("Titan", "Earth", 3, "");
		for (CardData c : List.of(backup, summon, forward)) putInP2BreakZone(mw, c);

		enterAsP2(mw, makeLbForward("Noctis", "Earth", 6, 7000, 2, "King", "XV", NOCTIS_23_125R));

		assertEquals(List.of(forward), mw.gameState.getP2Hand());
		assertTrue(mw.gameState.getP2BreakZone().containsAll(List.of(backup, summon)), "Forwards only");
	}

	// =========================================================================================
	// 23-126L Edge (LB 3): "When Edge enters the field, until the end of the turn, all the Forwards
	// of Lightning, Job Ninja and Card Name Ninja you control gain "When this Forward attacks,
	// choose 1 Forward opponent controls. Deal it 7000 damage.""
	// =========================================================================================

	// 20-006C Blacksmith / 23-126L Edge: a trigger to each of your Forwards on the field now. Edge's
	// filter is a union — the Forwards of each kind named.
	@Test
	void edgeGrantsItsTriggerToLightningJobNinjaAndCardNameNinjaForwards() {
		MainWindow mw = new MainWindow();
		CardData lightning = makeForward("Bolt", "Lightning", 3, 7000);
		CardData ninja     = makeForward("Ninja", "Water", 3, 7000);
		CardData other     = makeForward("Other", "Fire", 3, 7000);
		for (CardData c : List.of(lightning, ninja, other)) placeP2Forward(mw, c);

		ActionResolver.parse("until the end of the turn, all the Forwards of Lightning, Job Ninja and Card Name Ninja "
				+ "you control gain \"When this Forward attacks, choose 1 Forward opponent controls. Deal it 7000 "
				+ "damage.\"", makeForward("Edge", "Lightning", 5, 9000)).accept(mw.buildGameContext(false));

		Predicate<CardData> hasTrigger = c -> mw.effectiveAutoAbilities(c).stream()
				.anyMatch(a -> a.effectText().contains("Deal it 7000 damage"));
		assertTrue(hasTrigger.test(lightning), "Lightning");
		assertTrue(hasTrigger.test(ninja), "Card Name Ninja");
		assertFalse(hasTrigger.test(other), "neither");
		mw.fireEndOfTurnEffects(false);
		assertFalse(hasTrigger.test(lightning), "until the end of the turn");
	}

	// =========================================================================================
	// 23-127R Nyx (LB 1): "If a Lightning Character or Category XV Character you controlled has been
	// put from the field into the Break Zone this turn, the cost required to cast Nyx is reduced by
	// 4."
	//
	// "Put from the field into the Break Zone" is a destination, not a break: it covers a Forward
	// broken, one put there by a cost or an effect, and a Backup or Monster just the same. Read
	// against PlayerTurnState.putToBzFromFieldThisTurn, which addToBreakZone keeps for every route
	// and only once the card has actually arrived. Both of her LB-deck cast paths price her through
	// castCostFor.
	// =========================================================================================

	private static final String NYX_23_127R = "Limit Break -- 1[[br]]   If a Lightning Character or Category XV "
			+ "Character you controlled has been put from the field into the Break Zone this turn, the cost "
			+ "required to cast Nyx is reduced by 4.";

	private static int nyxCost(MainWindow mw) {
		return mw.castCostFor(makeLbForward("Nyx", "Lightning", 5, 9000, 1, "Kingsglaive", "XV", NYX_23_127R), true);
	}

	@Test
	void nyxCostsFiveUntilALightningOrCategoryXvCharacterGoes() {
		MainWindow mw = new MainWindow();
		assertEquals(5, nyxCost(mw));

		placeP1Forward(mw, makePlainCard("Cloud", "Fire", "Forward", 2, 5000, null, "VII"));
		mw.breakP1Forward(0);
		assertEquals(5, nyxCost(mw), "another Element and Category");
	}

	@Test
	void aBrokenLightningForwardTakesFourOff() {
		MainWindow mw = new MainWindow();
		placeP1Forward(mw, makeForward("Bolt", "Lightning", 2, 5000));

		mw.breakP1Forward(0);

		assertEquals(1, nyxCost(mw));
	}

	@Test
	void aLightningForwardPutIntoTheBreakZoneWithoutBreakingCounts() {
		// Sacrificed for a cost, or put there by an effect: a put, which is all the card asks for.
		MainWindow mw = new MainWindow();
		placeP1Forward(mw, makeForward("Bolt", "Lightning", 2, 5000));

		mw.putP1ForwardIntoBreakZone(0);

		assertEquals(1, nyxCost(mw));
	}

	@Test
	void aCategoryXvBackupCounts() {
		MainWindow mw = new MainWindow();
		placeP1Backup(mw, makePlainCard("Ignis", "Fire", "Backup", 2, 0, "Retainer", "XV"));

		mw.autoAbilityTriggers.breakP1BackupSlot(0);

		assertEquals(1, nyxCost(mw), "a Character, not only a Forward");
	}

	@Test
	void aCharacterOfTwoElementsCountsForEither() {
		MainWindow mw = new MainWindow();
		placeP1Forward(mw, makeForward("Prompto", "Earth/Lightning", 2, 5000));

		mw.breakP1Forward(0);

		assertEquals(1, nyxCost(mw), "Earth/Lightning is a Lightning Character");
	}

	@Test
	void onlyACharacterYouControlledCounts() {
		MainWindow mw = new MainWindow();
		placeP2Forward(mw, makeForward("Theirs", "Lightning", 2, 5000));

		mw.breakP2Forward(0);

		assertEquals(5, nyxCost(mw));
	}

	@Test
	void aCharacterRemovedFromTheGameDoesNotCount() {
		MainWindow mw = new MainWindow();
		placeP1Forward(mw, makeForward("Bolt", "Lightning", 2, 5000));

		mw.removeP1ForwardToRfg(0);

		assertEquals(5, nyxCost(mw), "it left the field, but not for the Break Zone");
	}

	// =========================================================================================
	// 23-128R Beatrix (LB 1): "When Beatrix attacks, choose 1 Forward. If you control 3 or more Job
	// Knight Forwards, break it."
	//
	// Beatrix is a Knight herself, so she is one of the three.
	// =========================================================================================

	private static final String BEATRIX_23_128R = "Limit Break -- 1[[br]]   When Beatrix attacks, choose 1 "
			+ "Forward. If you control 3 or more Job Knight Forwards, break it.";

	/** Beatrix attacking from P2's field beside {@code allies}, at a P1 Forward; returns that Forward. */
	private static CardData beatrixAttacksBeside(MainWindow mw, CardData... allies) {
		CardData beatrix = makeLbForward("Beatrix", "Water", 4, 8000, 1, "Knight", "DFF", BEATRIX_23_128R);
		placeP2Forward(mw, beatrix);
		for (CardData ally : allies) {
			if (ally.isBackup()) placeP2Backup(mw, ally); else placeP2Forward(mw, ally);
		}
		CardData theirs = makeForward("Theirs", "Fire", 5, 9000);
		placeP1Forward(mw, theirs);
		attackAsP2(mw, beatrix);
		return theirs;
	}

	@Test
	void beatrixBreaksWithTwoOtherKnightsBesideHer() {
		MainWindow mw = new MainWindow();
		CardData theirs = beatrixAttacksBeside(mw,
				makeForwardWithJob("Steiner", "Water", 3, 7000, "Knight"),
				makeForwardWithJob("Haurchefant", "Water", 3, 7000, "Knight"));

		assertTrue(mw.gameState.getP1BreakZone().contains(theirs), "three Knights, Beatrix among them");
	}

	@Test
	void beatrixBreaksNothingWithOnlyOneOtherKnight() {
		MainWindow mw = new MainWindow();
		CardData theirs = beatrixAttacksBeside(mw, makeForwardWithJob("Steiner", "Water", 3, 7000, "Knight"));

		assertTrue(mw.p1ForwardCards.contains(theirs));
	}

	@Test
	void aKnightBackupIsNotAKnightForward() {
		MainWindow mw = new MainWindow();
		CardData theirs = beatrixAttacksBeside(mw,
				makeForwardWithJob("Steiner", "Water", 3, 7000, "Knight"),
				makeJobCard("Knight Aide", "Water", "Backup", "Knight"));

		assertTrue(mw.p1ForwardCards.contains(theirs), "\"Job Knight Forwards\"");
	}

	// =========================================================================================
	// 23-129H Lunafreya (LB 2): "When Lunafreya enters the field, reveal the top 5 cards of your
	// deck. Add 1 card among them to your hand and return the other cards to the bottom of your
	// deck in any order. If the card added to your hand has an EX Burst, you may trigger its EX
	// Burst effect. (This effect is put on the stack.)"
	//
	// The look clause says "reveal", not "look at", and carries a rider that acts on whichever
	// card was taken. The readings either side of hers — a private look, a look with no rider, a
	// rider the parser does not know — are in CardBehaviorTest with the look parser's other users.
	// =========================================================================================

	private static final String LUNAFREYA_TEXT =
			"Limit Break -- 2[[br]]   When Lunafreya enters the field, reveal the top 5 cards of your "
			+ "deck. Add 1 card among them to your hand and return the other cards to the bottom of "
			+ "your deck in any order. If the card added to your hand has an EX Burst, you may trigger "
			+ "its EX Burst effect. (This effect is put on the stack.)";

	@Test
	void lunafreyaRevealsFiveAndOffersTheAddedCardsExBurst() {
		List<AutoAbility> abilities = CardData.parseAutoAbilities(LUNAFREYA_TEXT);
		assertEquals(1, abilities.size());
		AutoAbility etf = abilities.get(0);
		assertEquals("enters the field", etf.trigger());
		assertEquals("LookTopDeckAddToHandRestBottom + AddedCardExBurst",
				ActionResolver.matchedPatternName(etf.effectText(), null));

		Consumer<GameContext> fn = ActionResolver.parse(etf.effectText(), null);
		assertNotNull(fn);
		GameContext ctx = mock(GameContext.class);
		fn.accept(ctx);

		// The rider can only know what was taken after the look has resolved.
		InOrder order = inOrder(ctx);
		order.verify(ctx).lookAtTopDeck(
				new LookConfig(5, LookConfig.LookAction.ADD_TO_HAND_REST_BOTTOM, null, null, true));
		order.verify(ctx).triggerExBurstOfCardAddedToHand();
	}

	@Test
	void lunafreyaRevealsRatherThanLooksSoTheCardsArePublic() {
		// "Reveal" shows the cards to both players; a "look at" would keep them private to the
		// controller. Same card movement either way, so only this flag carries the difference.
		AutoAbility etf = CardData.parseAutoAbilities(LUNAFREYA_TEXT).get(0);
		GameContext ctx = mock(GameContext.class);
		ActionResolver.parse(etf.effectText(), null).accept(ctx);

		ArgumentCaptor<LookConfig> cfg = ArgumentCaptor.forClass(LookConfig.class);
		verify(ctx).lookAtTopDeck(cfg.capture());
		assertTrue(cfg.getValue().reveal(), "Lunafreya reveals, so both players see the cards");
	}

	// =========================================================================================
	// 24-115R Tenzen (LB 3): "When Tenzen enters the field, until the end of the turn, all the Job
	// Samurai Forwards and Card Name Samurai Forwards you control gain +3000 power, Brave and
	// 'This Forward can attack twice in the same turn.'"  (Effect wiring.)
	//
	// Three payloads on one filtered set. Neither neighbour could carry it: the job-or-name power
	// boost stops at the power, and the mass keyword grant reads only a Job — so splitting the
	// sentence between them would have handed each half a different set of Forwards.
	//
	// The quoted grant cannot go through grantedSelfFieldAbilityEffect either, which checks the
	// quoted subject against the source's own name. Here the subject is "This Forward" — every
	// card in the set at once, and Tenzen is not necessarily one of them.
	// =========================================================================================

	private static final String TENZEN_24_115R =
			"until the end of the turn, all the Job Samurai Forwards and Card Name Samurai Forwards "
			+ "you control gain +3000 power, Brave and \"This Forward can attack twice in the same turn.\"";

	@Test
	void tenzenAppliesAllThreePayloadsToTheOneSet() {
		GameContext ctx = mock(GameContext.class);
		ActionResolver.parse(TENZEN_24_115R, null).accept(ctx);

		// Same job-or-name pair to all three, so all three reach the same Forwards.
		verify(ctx).applyMassFieldJobCardNamePowerBoost(3000, true, false, false, true, "Samurai", "Samurai", null);
		verify(ctx).applyMassFieldJobCardNameKeywordGrant(
				EnumSet.of(CardData.Trait.BRAVE), true, false, false, true, "Samurai", "Samurai");
		verify(ctx).applyMassFieldJobCardNameMaxAttacks(2, false, true, "Samurai", "Samurai");
	}

	@Test
	void tenzenIsAttributedToTheThreePayloadReading() {
		assertEquals("UntilEotAllJobCardNameGainPowerTraitsAbility",
				ActionResolver.matchedPatternName(TENZEN_24_115R, null),
				"a plain power-boost name would mean two of the three payloads were being dropped");
	}

	@Test
	void anUnreadableGrantDeclinesTheWholeSentence() {
		// Better unclaimed than resolved with its third payload silently missing.
		assertNull(ActionResolver.parse(
				"until the end of the turn, all the Job Samurai Forwards and Card Name Samurai "
				+ "Forwards you control gain +3000 power, Brave and \"This Forward flumphs the "
				+ "widget.\"", null));
	}

	// =========================================================================================
	// 24-116R Snow (LB 2): "When Snow enters the field, you may search for 1 Card Name Lightning or
	// Card Name Serah and add it to your hand."
	//
	// "Card Name Lightning" is a name, not an Element: a Lightning Forward called something else is
	// not one of the two.
	// =========================================================================================

	private static final String SNOW_24_116R = "Limit Break -- 2[[br]]When Snow enters the field, you may search "
			+ "for 1 Card Name Lightning or Card Name Serah and add it to your hand.";

	private static CardData makeSnow() {
		return makeLbForward("Snow", "Ice", 5, 5000, 2, "Sentinel/L'Cie", "XIII", SNOW_24_116R);
	}

	@Test
	void snowSearchesForSerah() {
		MainWindow mw = new MainWindow();
		CardData serah = makeForward("Serah", "Lightning", 2, 5000);
		CardData hope  = makeForward("Hope", "Lightning", 2, 5000);
		putOnTopOfDeck(mw, serah, false);
		putOnTopOfDeck(mw, hope, false);

		enterAsP2(mw, makeSnow());

		assertEquals(List.of(serah), mw.gameState.getP2Hand());
		assertTrue(mw.gameState.getP2MainDeck().contains(hope));
	}

	@Test
	void snowSearchesForLightningByNameNotByElement() {
		MainWindow mw = new MainWindow();
		CardData lightning = makeForward("Lightning", "Lightning", 3, 7000);
		CardData bolt      = makeForward("Bolt", "Lightning", 2, 5000);
		putOnTopOfDeck(mw, lightning, false);
		putOnTopOfDeck(mw, bolt, false);

		enterAsP2(mw, makeSnow());

		assertEquals(List.of(lightning), mw.gameState.getP2Hand());
		assertTrue(mw.gameState.getP2MainDeck().contains(bolt), "a Lightning Forward, but not Card Name Lightning");
	}

	// =========================================================================================
	// 24-117R Zidane (LB 1): "Zidane cannot be blocked by a Forward of cost 4 or more.[[br]]When
	// Zidane enters the field, your opponent puts the top 2 cards of their deck into the Break
	// Zone. If both cards are of the same Element, draw 1 card."
	// =========================================================================================

	private static final String ZIDANE_24_117R = "Limit Break -- 1[[br]]Zidane cannot be blocked by a Forward "
			+ "of cost 4 or more.[[br]]When Zidane enters the field, your opponent puts the top 2 cards of their "
			+ "deck into the Break Zone. If both cards are of the same Element, draw 1 card.";

	private static CardData makeZidane() {
		return makeLbForward("Zidane", "Wind", 4, 5000, 1, "Tantalus Member/Thief", "THEATRHYTHM", ZIDANE_24_117R);
	}

	@Test
	void zidaneCannotBeBlockedByAForwardOfCost4OrMore() {
		MainWindow mw = new MainWindow();
		CardData zidane = makeZidane();

		assertTrue(mw.p1AttackerCostFiltersExclude(zidane, 4));
		assertTrue(mw.p1AttackerCostFiltersExclude(zidane, 7));
		assertFalse(mw.p1AttackerCostFiltersExclude(zidane, 3), "cost 3 may block him");
	}

	@Test
	void zidaneDrawsWhenBothMilledCardsShareAnElement() {
		MainWindow mw = new MainWindow();
		CardData a = makeForward("Mill A", "Water", 2, 5000);
		CardData b = makeForward("Mill B", "Water", 3, 6000);
		putOnTopOfDeck(mw, b, true);
		putOnTopOfDeck(mw, a, true);
		CardData draw = makeForward("Draw", "Wind", 2, 5000);
		putOnTopOfDeck(mw, draw, false);

		enterAsP2(mw, makeZidane());

		assertTrue(mw.gameState.getP1BreakZone().containsAll(List.of(a, b)), "the opponent's top two");
		assertEquals(List.of(draw), mw.gameState.getP2Hand(), "both Water, so Zidane's controller draws");
	}

	@Test
	void zidaneDrawsNothingWhenTheMilledCardsDiffer() {
		MainWindow mw = new MainWindow();
		CardData a = makeForward("Mill A", "Water", 2, 5000);
		CardData b = makeForward("Mill B", "Fire", 3, 6000);
		putOnTopOfDeck(mw, b, true);
		putOnTopOfDeck(mw, a, true);
		putOnTopOfDeck(mw, makeForward("Draw", "Wind", 2, 5000), false);

		enterAsP2(mw, makeZidane());

		assertTrue(mw.gameState.getP1BreakZone().containsAll(List.of(a, b)), "milled either way");
		assertTrue(mw.gameState.getP2Hand().isEmpty());
	}

	// =========================================================================================
	// 24-118R Selh'teus (LB 1): "If Selh'teus is dealt damage by your opponent's Summons or
	// abilities, the damage becomes 0 instead.[[br]]Selh'teus cannot be broken by opposing Summons
	// or abilities that don't deal damage."
	//
	// Selh'teus sits on P1's side so that the opposing effects are the CPU's, and choose him
	// without a dialog. Battle damage and his own side's effects still reach him.
	// =========================================================================================

	private static final String SELHTEUS_24_118R = "Limit Break -- 1[[br]]If Selh'teus is dealt damage by your "
			+ "opponent's Summons or abilities, the damage becomes 0 instead.[[br]]Selh'teus cannot be broken by "
			+ "opposing Summons or abilities that don't deal damage.";

	/** Selh'teus on P1's field, with a P2 Forward to use abilities from; returns Selh'teus. */
	private static CardData selhteusFacingP2(MainWindow mw) {
		CardData selhteus = makeLbForward("Selh'teus", "Earth", 5, 9000, 1, "Kuluu", "XI", SELHTEUS_24_118R);
		placeP1Forward(mw, selhteus);
		placeP2Forward(mw, makeForward("Caster", "Fire", 3, 7000));
		return selhteus;
	}

	@Test
	void anOpposingAbilitysDamageToSelhteusBecomes0() {
		MainWindow mw = new MainWindow();
		CardData selhteus = selhteusFacingP2(mw);

		useAbilityAs(mw, false, mw.p2ForwardCards.get(0), "Choose 1 Forward opponent controls. Deal it 5000 damage.");

		assertEquals(0, p1Damage(mw, selhteus));
	}

	@Test
	void anOpposingSummonsDamageToSelhteusBecomes0() {
		MainWindow mw = new MainWindow();
		CardData selhteus = selhteusFacingP2(mw);

		castSummonAs(mw, false, "Choose 1 Forward opponent controls. Deal it 5000 damage.");

		assertEquals(0, p1Damage(mw, selhteus));
	}

	@Test
	void hisOwnSidesDamageStillReachesSelhteus() {
		MainWindow mw = new MainWindow();
		CardData selhteus = selhteusFacingP2(mw);

		useAbilityAs(mw, true, makeForward("Ally", "Earth", 3, 7000), "Deal 3000 damage to all the Forwards.");

		assertEquals(3000, p1Damage(mw, selhteus), "\"your opponent's\" Summons or abilities only");
	}

	@Test
	void anOpposingBreakThatDealsNoDamageCannotBreakSelhteus() {
		MainWindow mw = new MainWindow();
		CardData selhteus = selhteusFacingP2(mw);

		useAbilityAs(mw, false, mw.p2ForwardCards.get(0), "Choose 1 Forward opponent controls. Break it.");

		assertTrue(mw.p1ForwardCards.contains(selhteus));
	}

	// =========================================================================================
	// 24-119R Gilgamesh (LB 1): "When Gilgamesh enters the field, select 1 of the 2 following
	// actions. "Choose 1 Forward of cost 1. Break it." "Choose 2 cards in your opponent's Break
	// Zone. Remove them from the game.""
	//
	// Resolved as he arrives, without going on the Stack (tools/HANDOFF-inline-auto-abilities.md),
	// so these place him and read the board. Each board leaves the CPU one action worth taking.
	// =========================================================================================

	private static final String GILGAMESH_24_119R = "Limit Break -- 1[[br]]When Gilgamesh enters the field, "
			+ "select 1 of the 2 following actions.[[br]]\"Choose 1 Forward of cost 1. Break it.\"[[br]]\"Choose 2 "
			+ "cards in your opponent's Break Zone. Remove them from the game.\"";

	private static CardData makeGilgamesh() {
		return makeLbForward("Gilgamesh", "Lightning", 4, 7000, 1, "Warrior", "SOPFFO", GILGAMESH_24_119R);
	}

	@Test
	void gilgameshBreaksAForwardOfCost1() {
		MainWindow mw = new MainWindow();
		CardData one = makeForward("One", "Water", 1, 9000);
		CardData two = makeForward("Two", "Water", 2, 3000);
		placeP1Forward(mw, one);
		placeP1Forward(mw, two);

		placeP2Forward(mw, makeGilgamesh());

		assertTrue(mw.gameState.getP1BreakZone().contains(one));
		assertTrue(mw.p1ForwardCards.contains(two), "exactly cost 1");
	}

	@Test
	void gilgameshRemovesTwoCardsFromTheOpponentsBreakZone() {
		MainWindow mw = new MainWindow();
		CardData a = makeForward("Buried A", "Water", 3, 7000);
		CardData b = makeForward("Buried B", "Water", 3, 7000);
		for (CardData c : List.of(a, b)) {
			mw.gameState.getIdentity().put(c, true);
			mw.gameState.getP1BreakZone().add(c);
		}

		placeP2Forward(mw, makeGilgamesh());

		assertTrue(mw.gameState.getP1BreakZone().isEmpty());
		assertTrue(mw.gameState.getP1PermanentRfp().containsAll(List.of(a, b)));
	}

	// =========================================================================================
	// 24-120R Warrior of Light (LB 1): "You must control 3 or more Job Standard Unit Forwards and/or
	// Job Warrior of Light Forwards to cast Warrior of Light.[[br]]The Forwards other than Warrior
	// of Light you control gain "This Forward cannot be chosen by your opponent's abilities." and
	// "If this Forward is dealt damage by your opponent's abilities, the damage becomes 0
	// instead.""
	//
	// Both grants name abilities, so an opposing Summon still reaches the Forwards. Warrior of Light
	// sits on P1's side so that the opposing effects are the CPU's.
	// =========================================================================================

	private static final String WARRIOR_OF_LIGHT_24_120R = "Limit Break -- 1[[br]]You must control 3 or more Job "
			+ "Standard Unit Forwards and/or Job Warrior of Light Forwards to cast Warrior of Light.[[br]]The "
			+ "Forwards other than Warrior of Light you control gain \"This Forward cannot be chosen by your "
			+ "opponent's abilities.\" and \"If this Forward is dealt damage by your opponent's abilities, the "
			+ "damage becomes 0 instead.\"";

	private static CardData makeWarriorOfLight() {
		return makeLbForward("Warrior of Light", "Water", 2, 8000, 1, "Warrior of Light", "SOPFFO",
				WARRIOR_OF_LIGHT_24_120R);
	}

	@Test
	void warriorOfLightNeedsThreeStandardUnitOrWarriorOfLightForwards() {
		MainWindow mw = new MainWindow();
		CardData wol = makeWarriorOfLight();
		placeP2Forward(mw, makeForwardWithJob("Unit A", "Water", 2, 5000, "Standard Unit"));
		placeP2Forward(mw, makeForwardWithJob("Unit B", "Water", 2, 5000, "Standard Unit"));
		placeP2Backup(mw, makeJobCard("Unit Aide", "Water", "Backup", "Standard Unit"));

		assertFalse(mw.castRestrictionMet(wol, false), "two Forwards; the Backup is not one");
		placeP2Forward(mw, makeForwardWithJob("Another", "Water", 3, 7000, "Warrior of Light"));
		assertTrue(mw.castRestrictionMet(wol, false), "\"and/or\": the third may be a Job Warrior of Light");
	}

	/** Warrior of Light and an ally on P1's field, a P2 Forward to use abilities from; returns the ally. */
	private static CardData warriorOfLightBesideAnAlly(MainWindow mw) {
		placeP1Forward(mw, makeWarriorOfLight());
		CardData ally = makeForward("Ally", "Water", 3, 9000);
		placeP1Forward(mw, ally);
		placeP2Forward(mw, makeForward("Caster", "Fire", 3, 7000));
		return ally;
	}

	@Test
	void anOpposingAbilityCannotChooseTheForwardsBesideWarriorOfLight() {
		MainWindow mw = new MainWindow();
		CardData ally = warriorOfLightBesideAnAlly(mw);

		useAbilityAs(mw, false, mw.p2ForwardCards.get(0), "Choose 1 Forward opponent controls. Dull it.");

		assertEquals(CardState.ACTIVE, mw.p1ForwardStates.get(mw.p1ForwardCards.indexOf(ally)));
		assertEquals(CardState.DULL, mw.p1ForwardStates.get(0), "Warrior of Light grants it only to the others");
	}

	@Test
	void anOpposingAbilitysDamageToTheForwardsBesideWarriorOfLightBecomes0() {
		MainWindow mw = new MainWindow();
		CardData ally = warriorOfLightBesideAnAlly(mw);

		useAbilityAs(mw, false, mw.p2ForwardCards.get(0), "Deal 3000 damage to all the Forwards opponent controls.");

		assertEquals(0, p1Damage(mw, ally), "chooses nothing, so only the second grant stands in the way");
		assertEquals(3000, mw.p1ForwardDamage.get(0), "not Warrior of Light himself");
	}

	@Test
	void anOpposingSummonStillDamagesTheForwardsBesideWarriorOfLight() {
		MainWindow mw = new MainWindow();
		CardData ally = warriorOfLightBesideAnAlly(mw);

		castSummonAs(mw, false, "Deal 3000 damage to all the Forwards opponent controls.");

		assertEquals(3000, p1Damage(mw, ally), "the grant names abilities, not Summons");
	}

	// =========================================================================================
	// 24-121H Iron Giant (SOPFFO) (LB 2): "When Iron Giant (SOPFFO) enters the field, select 1 of
	// the 2 following actions. "Choose 1 Forward of cost 2 or less. Deal it 5000 damage." "Choose
	// 1 dull Forward. Deal it 7000 damage.""
	//
	// Resolved as it arrives, as Gilgamesh's select is. Each board leaves the CPU one action worth
	// taking.
	// =========================================================================================

	private static final String IRON_GIANT_24_121H = "Limit Break -- 2[[br]]When Iron Giant (SOPFFO) enters the "
			+ "field, select 1 of the 2 following actions.[[br]]\"Choose 1 Forward of cost 2 or less. Deal it 5000 "
			+ "damage.\"[[br]]\"Choose 1 dull Forward. Deal it 7000 damage.\"";

	private static CardData makeIronGiant() {
		return makeLbForward("Iron Giant (SOPFFO)", "Fire/Ice", 3, 5000, 2, "Iron Giant", "SOPFFO", IRON_GIANT_24_121H);
	}

	@Test
	void ironGiantDeals5000ToAForwardOfCost2OrLess() {
		MainWindow mw = new MainWindow();
		CardData cheap = makeForward("Cheap", "Water", 2, 5000);
		placeP1Forward(mw, cheap);

		placeP2Forward(mw, makeIronGiant());

		assertTrue(mw.gameState.getP1BreakZone().contains(cheap), "5000 breaks a 5000 Forward");
	}

	@Test
	void ironGiantDeals7000ToADullForward() {
		MainWindow mw = new MainWindow();
		CardData dull = makeForward("Dull", "Water", 5, 7000);
		placeP1Forward(mw, dull);
		mw.p1ForwardStates.set(mw.p1ForwardCards.indexOf(dull), CardState.DULL);

		placeP2Forward(mw, makeIronGiant());

		assertTrue(mw.gameState.getP1BreakZone().contains(dull), "cost 5, so only the second action reaches it");
	}

	// =========================================================================================
	// 24-122H Magna Roader (LB 2): "When Magna Roader enters the field, if you control 4 or more Ice
	// Characters, your opponent reveals their hand. Select 1 card from their hand. Your opponent
	// discards this card.[[br]]When Magna Roader enters the field, if you control 4 or more Wind
	// Characters, Magna Roader gains Haste until the end of the turn."
	//
	// Magna Roader is Ice and Wind, so he counts towards both.
	// =========================================================================================

	private static final String MAGNA_ROADER_24_122H = "Limit Break -- 2[[br]]When Magna Roader enters the field, "
			+ "if you control 4 or more Ice Characters, your opponent reveals their hand. Select 1 card from their "
			+ "hand. Your opponent discards this card.[[br]]When Magna Roader enters the field, if you control 4 or "
			+ "more Wind Characters, Magna Roader gains Haste until the end of the turn.";

	/** Magna Roader entering P2's field beside three {@code element} Backups, against a P1 hand of two. */
	private static CardData magnaRoaderEntersBeside(MainWindow mw, String element) {
		for (int i = 0; i < 3; i++) placeP2Backup(mw, makeBackup(element + " Aide " + i, element, 2));
		for (int i = 0; i < 2; i++) {
			CardData c = makeForward("Held " + i, "Water", 2, 5000);
			mw.gameState.getIdentity().put(c, true);
			mw.gameState.getP1Hand().add(c);
		}
		CardData magna = makeLbForward("Magna Roader", "Ice/Wind", 5, 7000, 2, "Magna Roader", "VI",
				MAGNA_ROADER_24_122H);
		enterAsP2(mw, magna);
		return magna;
	}

	@Test
	void withFourIceCharactersMagnaRoaderMakesTheOpponentDiscard() {
		MainWindow mw = new MainWindow();
		CardData magna = magnaRoaderEntersBeside(mw, "Ice");

		assertEquals(1, mw.gameState.getP1Hand().size());
		assertEquals(1, mw.gameState.getP1BreakZone().size(), "the selected card is discarded");
		assertFalse(mw.effectiveP2HasTrait(mw.p2ForwardCards.indexOf(magna), CardData.Trait.HASTE),
				"one Wind Character, himself");
	}

	@Test
	void withFourWindCharactersMagnaRoaderGainsHasteForTheTurn() {
		MainWindow mw = new MainWindow();
		CardData magna = magnaRoaderEntersBeside(mw, "Wind");
		int m = mw.p2ForwardCards.indexOf(magna);

		assertTrue(mw.effectiveP2HasTrait(m, CardData.Trait.HASTE));
		assertEquals(2, mw.gameState.getP1Hand().size(), "one Ice Character, himself");
		mw.turnPhases().runP2EndOfTurnCleanup();
		assertFalse(mw.effectiveP2HasTrait(m, CardData.Trait.HASTE), "until the end of the turn");
	}

	// =========================================================================================
	// 24-123H Vajradhara Wu (LB 2): "When Vajradhara Wu enters the field, select 1 of the 2
	// following actions. "Choose 1 damaged Forward. Break it." "Choose 1 Forward opponent
	// controls. Vajradhara Wu and the chosen Forward deal damage equal to their respective power
	// to the other.""
	//
	// Resolved as he arrives, as Gilgamesh's select is.
	// =========================================================================================

	private static final String VAJRADHARA_WU_24_123H = "Limit Break -- 2[[br]]When Vajradhara Wu enters the "
			+ "field, select 1 of the 2 following actions.[[br]]\"Choose 1 damaged Forward. Break it.\"[[br]]"
			+ "\"Choose 1 Forward opponent controls. Vajradhara Wu and the chosen Forward deal damage equal to "
			+ "their respective power to the other.\"";

	private static CardData makeVajradharaWu() {
		return makeLbForward("Vajradhara Wu", "Wind/Earth", 5, 8000, 2, "Vajradhara", "VII", VAJRADHARA_WU_24_123H);
	}

	@Test
	void vajradharaWuBreaksADamagedForward() {
		// Fighting this one would cost Wu his life, so breaking it is the action worth taking.
		MainWindow mw = new MainWindow();
		CardData hurt = makeForward("Hurt", "Water", 6, 12000);
		placeP1Forward(mw, hurt);
		mw.p1ForwardDamage.set(mw.p1ForwardCards.indexOf(hurt), 2000);

		placeP2Forward(mw, makeVajradharaWu());

		assertTrue(mw.gameState.getP1BreakZone().contains(hurt));
	}

	@Test
	void vajradharaWuAndTheChosenForwardDealTheirPowerToEachOther() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 3, 5000);
		placeP1Forward(mw, theirs);
		CardData wu = makeVajradharaWu();

		placeP2Forward(mw, wu);

		assertTrue(mw.gameState.getP1BreakZone().contains(theirs), "8000 to a 5000 Forward");
		assertEquals(5000, p2Damage(mw, wu), "and 5000 back");
	}

	// =========================================================================================
	// 24-124H The Ur-Dragon King (LB 2): "When The Ur-Dragon King enters the field, choose up to 2
	// Characters in your Break Zone. If you control 4 or more Earth Characters, add them to your
	// hand.[[br]]When The Ur-Dragon King enters the field, choose 1 Forward. If you control 4 or
	// more Lightning Characters, break it."
	//
	// Two triggers on one arrival, each with its own Element count; the King is Earth and Lightning,
	// so he counts towards both.
	// =========================================================================================

	private static final String UR_DRAGON_KING_24_124H = "Limit Break -- 2[[br]]When The Ur-Dragon King enters "
			+ "the field, choose up to 2 Characters in your Break Zone. If you control 4 or more Earth Characters, "
			+ "add them to your hand.[[br]]When The Ur-Dragon King enters the field, choose 1 Forward. If you "
			+ "control 4 or more Lightning Characters, break it.";

	private record UrDragonBoard(CardData buriedForward, CardData buriedBackup, CardData theirs) {}

	/** The King entering P2's field beside three {@code element} Backups. */
	private static UrDragonBoard urDragonKingEntersBeside(MainWindow mw, String element) {
		for (int i = 0; i < 3; i++) placeP2Backup(mw, makeBackup(element + " Aide " + i, element, 2));
		CardData forward = makeForward("Buried", "Earth", 3, 7000);
		CardData backup  = makeBackup("Buried Aide", "Earth", 2);
		putInP2BreakZone(mw, forward);
		putInP2BreakZone(mw, backup);
		CardData theirs = makeForward("Theirs", "Water", 5, 9000);
		placeP1Forward(mw, theirs);
		enterAsP2(mw, makeLbForward("The Ur-Dragon King", "Earth/Lightning", 9, 9000, 2, "Behemoth", "SOPFFO",
				UR_DRAGON_KING_24_124H));
		return new UrDragonBoard(forward, backup, theirs);
	}

	@Test
	void withFourEarthCharactersTheKingReturnsTwoCharactersAndBreaksNothing() {
		MainWindow mw = new MainWindow();
		UrDragonBoard b = urDragonKingEntersBeside(mw, "Earth");

		assertTrue(mw.gameState.getP2Hand().containsAll(List.of(b.buriedForward(), b.buriedBackup())),
				"Characters, Backups included");
		assertTrue(mw.p1ForwardCards.contains(b.theirs()), "one Lightning Character, himself");
	}

	@Test
	void withFourLightningCharactersTheKingBreaksAndReturnsNothing() {
		MainWindow mw = new MainWindow();
		UrDragonBoard b = urDragonKingEntersBeside(mw, "Lightning");

		assertTrue(mw.gameState.getP1BreakZone().contains(b.theirs()));
		assertTrue(mw.gameState.getP2Hand().isEmpty(), "one Earth Character, himself");
		assertTrue(mw.gameState.getP2BreakZone().containsAll(List.of(b.buriedForward(), b.buriedBackup())));
	}

	// =========================================================================================
	// 24-125H Tonberry & Cactuar (LB 2): "When Tonberry & Cactuar enters the field, select 1 of the 2
	// following actions. "Choose 1 active Forward of cost 3 or less. Break it." "Look at the top 2
	// cards of your deck. Add 1 card among them to your hand and return the other card to the
	// bottom of your deck.""
	//
	// Resolved as it arrives, as Gilgamesh's select is.
	// =========================================================================================

	private static final String TONBERRY_CACTUAR_24_125H = "Limit Break -- 2[[br]]When Tonberry & Cactuar enters "
			+ "the field, select 1 of the 2 following actions.[[br]]\"Choose 1 active Forward of cost 3 or less. "
			+ "Break it.\"[[br]]\"Look at the top 2 cards of your deck. Add 1 card among them to your hand and "
			+ "return the other card to the bottom of your deck.\"";

	private static CardData makeTonberryCactuar() {
		return makeLbForward("Tonberry & Cactuar", "Lightning/Water", 4, 5000, 2, "Tonberry/Cactuar", "TYPE-0",
				TONBERRY_CACTUAR_24_125H);
	}

	@Test
	void tonberryAndCactuarBreaksAnActiveForwardOfCost3OrLess() {
		MainWindow mw = new MainWindow();
		CardData active = makeForward("Active", "Water", 3, 9000);
		CardData dull   = makeForward("Dull", "Water", 2, 9000);
		placeP1Forward(mw, active);
		placeP1Forward(mw, dull);
		mw.p1ForwardStates.set(mw.p1ForwardCards.indexOf(dull), CardState.DULL);
		putPlainCardsOnTop(mw, false, 2);

		placeP2Forward(mw, makeTonberryCactuar());

		assertTrue(mw.gameState.getP1BreakZone().contains(active));
		assertTrue(mw.p1ForwardCards.contains(dull), "active only");
	}

	@Test
	void withNothingToBreakTonberryAndCactuarLooksAtTheTopTwo() {
		MainWindow mw = new MainWindow();
		CardData dull = makeForward("Dull", "Water", 2, 9000);
		placeP1Forward(mw, dull);
		mw.p1ForwardStates.set(mw.p1ForwardCards.indexOf(dull), CardState.DULL);
		CardData third = makeForward("Third", "Water", 2, 5000);
		putOnTopOfDeck(mw, third, false);
		putPlainCardsOnTop(mw, false, 2);

		placeP2Forward(mw, makeTonberryCactuar());

		assertEquals(1, mw.gameState.getP2Hand().size(), "one of the two");
		assertSame(third, mw.gameState.getP2MainDeck().peekFirst(), "the other went under it");
		assertEquals(2, mw.gameState.getP2MainDeck().size());
	}

	// =========================================================================================
	// 24-126H Ultima Weapon (LB 2): "When Ultima Weapon enters the field, choose 1 Forward. If you
	// control 4 or more Fire Characters, deal it 9000 damage.[[br]]When Ultima Weapon enters the
	// field, if you control 4 or more Water Characters, your opponent selects 1 Forward they
	// control. Put it into the Break Zone."
	//
	// The second trigger has the opponent select, and a P1 selection opens a dialog, so it is
	// resolved from P1's seat with the CPU selecting; the first is resolved from P2's as usual.
	// =========================================================================================

	private static final String ULTIMA_WEAPON_24_126H = "Limit Break -- 2[[br]]When Ultima Weapon enters the "
			+ "field, choose 1 Forward. If you control 4 or more Fire Characters, deal it 9000 damage.[[br]]When "
			+ "Ultima Weapon enters the field, if you control 4 or more Water Characters, your opponent selects 1 "
			+ "Forward they control. Put it into the Break Zone.";

	private static CardData makeUltimaWeapon() {
		return makeLbForward("Ultima Weapon", "Water/Fire", 6, 9000, 2, "Weapon", "X", ULTIMA_WEAPON_24_126H);
	}

	@Test
	void withFourFireCharactersUltimaWeaponDeals9000() {
		MainWindow mw = new MainWindow();
		for (int i = 0; i < 3; i++) placeP2Backup(mw, makeBackup("Fire Aide " + i, "Fire", 2));
		CardData theirs = makeForward("Theirs", "Water", 5, 10000);
		placeP1Forward(mw, theirs);

		enterAsP2(mw, makeUltimaWeapon());

		assertEquals(9000, p1Damage(mw, theirs), "Ultima Weapon and three Fire Backups");
	}

	@Test
	void withThreeFireCharactersUltimaWeaponDealsNothing() {
		MainWindow mw = new MainWindow();
		for (int i = 0; i < 2; i++) placeP2Backup(mw, makeBackup("Fire Aide " + i, "Fire", 2));
		CardData theirs = makeForward("Theirs", "Water", 5, 10000);
		placeP1Forward(mw, theirs);

		enterAsP2(mw, makeUltimaWeapon());

		assertEquals(0, p1Damage(mw, theirs));
	}

	/** Ultima Weapon's Water trigger, resolved from P1's seat beside {@code waterBackups}. */
	private static CardData ultimaWeaponWaterTriggerBeside(MainWindow mw, int waterBackups) {
		for (int i = 0; i < waterBackups; i++) placeP1Backup(mw, makeBackup("Water Aide " + i, "Water", 2));
		CardData theirs = makeForward("Theirs", "Fire", 5, 10000);
		placeP2Forward(mw, theirs);
		CardData weapon = makeUltimaWeapon();
		mw.suppressAutoAbilityForNextCards = 1;
		placeP1Forward(mw, weapon);
		useAbilityAs(mw, true, weapon, weapon.autoAbilities().get(1).effectText());
		return theirs;
	}

	@Test
	void withFourWaterCharactersTheOpponentPutsAForwardIntoTheBreakZone() {
		MainWindow mw = new MainWindow();
		CardData theirs = ultimaWeaponWaterTriggerBeside(mw, 3);

		assertTrue(mw.gameState.getP2BreakZone().contains(theirs));
	}

	@Test
	void withThreeWaterCharactersTheOpponentKeepsTheirForwards() {
		MainWindow mw = new MainWindow();
		CardData theirs = ultimaWeaponWaterTriggerBeside(mw, 2);

		assertTrue(mw.p2ForwardCards.contains(theirs));
	}

	// =========================================================================================
	// 25-106R Machina (LB 1): "You can only cast Machina if you don't control any Forwards.[[br]]For
	// each point of damage you have received, Machina gains +1000 power."
	// =========================================================================================

	private static final String MACHINA_25_106R = "Limit Break -- 1[[br]]You can only cast Machina if you don't "
			+ "control any Forwards.[[br]]For each point of damage you have received, Machina gains +1000 power.";

	private static CardData makeMachina() {
		return makeLbForward("Machina", "Fire", 2, 4000, 1, "Class Zero Cadet", "TYPE-0", MACHINA_25_106R);
	}

	@Test
	void machinaCanOnlyBeCastWhileYouControlNoForwards() {
		MainWindow mw = new MainWindow();
		CardData machina = makeMachina();
		placeP1Backup(mw, makeBackup("Aide", "Fire", 2));
		placeP2Forward(mw, makeForward("Theirs", "Water", 3, 7000));

		assertTrue(mw.castRestrictionMet(machina, true), "a Backup, and the opponent's Forward, are no bar");
		placeP1Forward(mw, makeForward("Ally", "Fire", 2, 5000));
		assertFalse(mw.castRestrictionMet(machina, true));
	}

	@Test
	void machinaGains1000PowerPerPointOfDamageYouHaveReceived() {
		MainWindow mw = new MainWindow();
		CardData machina = makeMachina();
		placeP2Forward(mw, machina);
		int idx = mw.p2ForwardCards.indexOf(machina);

		assertEquals(4000, mw.effectiveP2ForwardPower(idx), "no damage yet");
		for (int i = 0; i < 3; i++) mw.gameState.getP2DamageZone().add(makeForward("Damage " + i, "Fire", 2, 5000));
		assertEquals(7000, mw.effectiveP2ForwardPower(idx));
		mw.gameState.getP1DamageZone().add(makeForward("Their Damage", "Water", 2, 5000));
		assertEquals(7000, mw.effectiveP2ForwardPower(idx), "the damage you have received, not your opponent's");
	}

	// =========================================================================================
	// 25-107H Red XIII (LB 2): "If you control 3 or more Category VII Forwards, Red XIII gains
	// Haste, First Strike and Brave.[[br]]When Red XIII enters the field, all the Forwards you
	// control gain +2000 power until the end of the turn."
	//
	// Red XIII is Category VII himself, so he is one of the three.
	// =========================================================================================

	private static final String RED_XIII_25_107H = "Limit Break -- 2[[br]]If you control 3 or more Category VII "
			+ "Forwards, Red XIII gains Haste, First Strike and Brave.[[br]]When Red XIII enters the field, all the "
			+ "Forwards you control gain +2000 power until the end of the turn.";

	private static CardData makeRedXiii() {
		return makeLbForward("Red XIII", "Fire", 4, 7000, 2, "Warrior", "VII", RED_XIII_25_107H);
	}

	@Test
	void redXiiiGainsHasteFirstStrikeAndBraveFromTheThirdCategoryViiForward() {
		MainWindow mw = new MainWindow();
		CardData red = makeRedXiii();
		placeP2Forward(mw, red);
		placeP2Forward(mw, makePlainCard("Cloud", "Fire", "Forward", 2, 5000, null, "VII"));
		placeP2Backup(mw, makePlainCard("Tifa", "Earth", "Backup", 2, 0, null, "VII"));
		int r = mw.p2ForwardCards.indexOf(red);

		for (CardData.Trait t : List.of(CardData.Trait.HASTE, CardData.Trait.FIRST_STRIKE, CardData.Trait.BRAVE))
			assertFalse(mw.effectiveP2HasTrait(r, t), "two Forwards; the Backup is not one: " + t);
		placeP2Forward(mw, makePlainCard("Barret", "Earth", "Forward", 3, 7000, null, "VII"));
		for (CardData.Trait t : List.of(CardData.Trait.HASTE, CardData.Trait.FIRST_STRIKE, CardData.Trait.BRAVE))
			assertTrue(mw.effectiveP2HasTrait(r, t), "Red XIII and two more: " + t);
	}

	@Test
	void redXiiiGivesTheForwardsYouControlPlus2000ForTheTurn() {
		MainWindow mw = new MainWindow();
		CardData ally   = makeForward("Ally", "Fire", 3, 5000);
		CardData theirs = makeForward("Theirs", "Water", 3, 5000);
		placeP2Forward(mw, ally);
		placeP1Forward(mw, theirs);
		CardData red = makeRedXiii();

		enterAsP2(mw, red);

		int a = mw.p2ForwardCards.indexOf(ally);
		assertEquals(7000, mw.effectiveP2ForwardPower(a));
		assertEquals(9000, mw.effectiveP2ForwardPower(mw.p2ForwardCards.indexOf(red)), "Red XIII is one of them");
		assertEquals(5000, mw.effectiveP1ForwardPower(mw.p1ForwardCards.indexOf(theirs)), "only yours");
		mw.turnPhases().runP2EndOfTurnCleanup();
		assertEquals(5000, mw.effectiveP2ForwardPower(a), "until the end of the turn");
	}

	// =========================================================================================
	// 25-109H Shuyin (LB 1): "When Shuyin enters the field, you may discard 1 card. When you do so,
	// search for 1 Dark Forward and add it to your hand."
	//
	// Unlike Vincent's "put … into the Break Zone. When you do so", a discard is not one of the
	// prices the trigger layer charges as the card arrives, so this one goes on the Stack and the
	// resolver reads the discard and its payoff together.
	// =========================================================================================

	private static final String SHUYIN_25_109H = "Limit Break -- 1[[br]]When Shuyin enters the field, you may "
			+ "discard 1 card. When you do so, search for 1 Dark Forward and add it to your hand.";

	private static CardData makeShuyin() {
		return makeLbForward("Shuyin", "Ice", 5, 5000, 1, "Illusion", "X", SHUYIN_25_109H);
	}

	@Test
	void shuyinDiscardsACardToSearchForADarkForward() {
		MainWindow mw = new MainWindow();
		CardData held = makeForward("Held", "Water", 2, 5000);
		mw.gameState.getIdentity().put(held, false);
		mw.gameState.getP2Hand().add(held);
		CardData dark       = makeForward("Dark One", "Dark", 5, 9000);
		CardData darkBackup = makeBackup("Dark Aide", "Dark", 3);
		CardData ice        = makeForward("Ice One", "Ice", 3, 7000);
		for (CardData c : List.of(darkBackup, ice, dark)) putOnTopOfDeck(mw, c, false);

		enterAsP2(mw, makeShuyin());

		assertTrue(mw.gameState.getP2BreakZone().contains(held), "discarded");
		assertEquals(List.of(dark), mw.gameState.getP2Hand());
		assertTrue(mw.gameState.getP2MainDeck().containsAll(List.of(darkBackup, ice)), "a Dark Forward only");
	}

	@Test
	void withNothingToDiscardShuyinsAbilityNeverReachesTheStack() {
		// A price that cannot be paid leaves nothing to do so; the trigger layer does not offer it.
		MainWindow mw = new MainWindow();
		CardData dark = makeForward("Dark One", "Dark", 5, 9000);
		putOnTopOfDeck(mw, dark, false);

		placeP2Forward(mw, makeShuyin());

		assertTrue(mw.gameState.getStack().isEmpty());
		assertTrue(mw.gameState.getP2Hand().isEmpty());
		assertTrue(mw.gameState.getP2MainDeck().contains(dark));
	}

	// =========================================================================================
	// 25-110R Aerith (LB 2): "When Aerith enters the field, you may search for 1 Card Name Cloud or
	// Card Name Zack and add it to your hand.[[br]]《Dull》, put Aerith into the Break Zone: Choose
	// 1 auto-ability that is choosing only 1 Category VII Forward you control. Cancel its effect."
	//
	// The cancel reaches one kind of entry on the Stack: an auto-ability, choosing exactly one
	// card, which is a Category VII Forward on Aerith's side. A Summon, an entry choosing two, or
	// one choosing a Forward of another Category or across the table is out of reach — and with
	// nothing in reach, she cannot be used at all, or she would go to the Break Zone for nothing.
	// =========================================================================================

	private static final String AERITH_25_110R = "Limit Break -- 2[[br]]When Aerith enters the field, you may "
			+ "search for 1 Card Name Cloud or Card Name Zack and add it to your hand.[[br]]《Dull》, put Aerith "
			+ "into the Break Zone: Choose 1 auto-ability that is choosing only 1 Category VII Forward you "
			+ "control. Cancel its effect.";

	private static CardData makeAerith() {
		return makeLbForward("Aerith", "Wind", 5, 4000, 2, "Ancient", "VII", AERITH_25_110R);
	}

	@Test
	void aerithSearchesForZack() {
		MainWindow mw = new MainWindow();
		CardData zack = makeForward("Zack", "Fire", 3, 7000);
		CardData tifa = makeForward("Tifa", "Earth", 3, 7000);
		putOnTopOfDeck(mw, tifa, false);
		putOnTopOfDeck(mw, zack, false);

		enterAsP2(mw, makeAerith());

		assertEquals(List.of(zack), mw.gameState.getP2Hand());
		assertTrue(mw.gameState.getP2MainDeck().contains(tifa), "another name");
	}

	/** An auto-ability of P1's on the Stack, choosing {@code targets}. */
	private static StackEntry p1AutoAbilityChoosing(MainWindow mw, ForwardTarget... targets) {
		CardData source = makeForward("Caster", "Water", 3, 7000);
		AutoAbility auto = CardData.parseAutoAbilities(
				"When Caster enters the field, choose 1 Forward. Deal it 5000 damage.").get(0);
		StackEntry entry = new StackEntry(source, null, auto, true, 0, false, List.of(targets),
				false, false, 0, 0);
		mw.gameState.pushStack(entry);
		return entry;
	}

	private static ForwardTarget p2Forward(MainWindow mw, CardData card) {
		return new ForwardTarget(false, mw.p2ForwardCards.indexOf(card), ForwardTarget.CardZone.FORWARD);
	}

	/** Aerith on P2's field beside {@code others}; returns Aerith. */
	private static CardData aerithBeside(MainWindow mw, CardData... others) {
		CardData aerith = makeAerith();
		mw.suppressAutoAbilityForNextCards = 1;
		placeP2Forward(mw, aerith);
		for (CardData c : others) placeP2Forward(mw, c);
		return aerith;
	}

	private static boolean aerithCanBeUsed(MainWindow mw, CardData aerith) {
		return mw.canActivateAbility(aerith.actionAbilities().get(0), false, CardState.ACTIVE, 0, aerith, false);
	}

	private static void useAerith(MainWindow mw, CardData aerith) {
		useAbilityAs(mw, false, aerith, aerith.actionAbilities().get(0).effectText());
	}

	@Test
	void aerithCancelsAnAutoAbilityChoosingOnlyACategoryViiForwardOfHers() {
		MainWindow mw = new MainWindow();
		CardData cloud  = makePlainCard("Cloud", "Wind", "Forward", 2, 5000, null, "VII");
		CardData aerith = aerithBeside(mw, cloud);
		StackEntry entry = p1AutoAbilityChoosing(mw, p2Forward(mw, cloud));

		assertTrue(aerithCanBeUsed(mw, aerith));
		useAerith(mw, aerith);

		assertTrue(mw.cancelledStackEntries.contains(entry));
	}

	@Test
	void aerithCannotCancelOneChoosingAForwardOfAnotherCategory() {
		MainWindow mw = new MainWindow();
		CardData lightning = makePlainCard("Lightning", "Wind", "Forward", 2, 5000, null, "XIII");
		CardData aerith = aerithBeside(mw, lightning);
		StackEntry entry = p1AutoAbilityChoosing(mw, p2Forward(mw, lightning));

		assertFalse(aerithCanBeUsed(mw, aerith), "a Category XIII Forward is not one of hers to protect");
		useAerith(mw, aerith);
		assertFalse(mw.cancelledStackEntries.contains(entry));
	}

	@Test
	void aerithCannotCancelOneChoosingTwoForwards() {
		MainWindow mw = new MainWindow();
		CardData cloud = makePlainCard("Cloud", "Wind", "Forward", 2, 5000, null, "VII");
		CardData tifa  = makePlainCard("Tifa", "Earth", "Forward", 2, 5000, null, "VII");
		CardData aerith = aerithBeside(mw, cloud, tifa);
		StackEntry entry = p1AutoAbilityChoosing(mw, p2Forward(mw, cloud), p2Forward(mw, tifa));

		assertFalse(aerithCanBeUsed(mw, aerith), "\"choosing only 1\"");
		useAerith(mw, aerith);
		assertFalse(mw.cancelledStackEntries.contains(entry));
	}

	@Test
	void aerithCannotCancelOneChoosingACategoryViiForwardAcrossTheTable() {
		MainWindow mw = new MainWindow();
		CardData aerith = aerithBeside(mw);
		CardData theirs = makePlainCard("Cloud", "Wind", "Forward", 2, 5000, null, "VII");
		placeP1Forward(mw, theirs);
		StackEntry entry = p1AutoAbilityChoosing(mw,
				new ForwardTarget(true, mw.p1ForwardCards.indexOf(theirs), ForwardTarget.CardZone.FORWARD));

		assertFalse(aerithCanBeUsed(mw, aerith), "\"you control\"");
		useAerith(mw, aerith);
		assertFalse(mw.cancelledStackEntries.contains(entry));
	}

	@Test
	void aerithCannotCancelASummon() {
		MainWindow mw = new MainWindow();
		CardData cloud  = makePlainCard("Cloud", "Wind", "Forward", 2, 5000, null, "VII");
		CardData aerith = aerithBeside(mw, cloud);
		StackEntry summon = new StackEntry(makeSummon("Bolt", "Lightning", 3, ""), null, true, 0,
				List.of(p2Forward(mw, cloud)));
		mw.gameState.pushStack(summon);

		assertFalse(aerithCanBeUsed(mw, aerith), "an auto-ability only");
	}

	// =========================================================================================
	// 25-113R Yang (LB 1): "Brave[[br]]When Yang enters the field, you may play 1 Forward of cost
	// equal to or less than the number of Job Monk and/or Card Name Monk you control from your hand
	// onto the field."
	//
	// Yang's Job is King/Monk, so he counts himself.
	// =========================================================================================

	private static final String YANG_25_113R = "Limit Break -- 1[[br]]Brave[[br]]When Yang enters the field, you "
			+ "may play 1 Forward of cost equal to or less than the number of Job Monk and/or Card Name Monk you "
			+ "control from your hand onto the field.";

	private static CardData makeYang() {
		return makeLbForward("Yang", "Earth", 5, 8000, 1, "King/Monk", "IV", YANG_25_113R);
	}

	private static void putInP2Hand(MainWindow mw, CardData card) {
		mw.gameState.getIdentity().put(card, false);
		mw.gameState.getP2Hand().add(card);
	}

	@Test
	void yangPlaysAForwardOfCostUpToTheMonksYouControl() {
		MainWindow mw = new MainWindow();
		placeP2Backup(mw, makeJobCard("Monk Aide", "Earth", "Backup", "Monk"));
		placeP2Backup(mw, makePlainCard("Monk", "Earth", "Backup", 2, 0, null, null));
		CardData three = makeForward("Three", "Earth", 3, 7000);
		CardData four  = makeForward("Four", "Earth", 4, 8000);
		putInP2Hand(mw, four);
		putInP2Hand(mw, three);

		enterAsP2(mw, makeYang());

		assertTrue(mw.p2ForwardCards.contains(three), "Yang, a Job Monk and a Card Name Monk: three");
		assertEquals(List.of(four), mw.gameState.getP2Hand());
	}

	@Test
	void aloneYangCountsOnlyHimself() {
		MainWindow mw = new MainWindow();
		CardData two = makeForward("Two", "Earth", 2, 5000);
		putInP2Hand(mw, two);

		enterAsP2(mw, makeYang());

		assertEquals(List.of(two), mw.gameState.getP2Hand(), "one Monk, so cost 1 at most");
	}

	@Test
	void yangHasBrave() {
		MainWindow mw = new MainWindow();
		CardData yang = makeYang();
		placeP2Forward(mw, yang);

		assertTrue(mw.effectiveP2HasTrait(mw.p2ForwardCards.indexOf(yang), CardData.Trait.BRAVE));
	}

	// =========================================================================================
	// 25-114R Kain (LB 1): "First Strike[[br]]If you control 3 or more Job Dragoon and/or Card Name
	// Dragoon, the cost required to cast Kain is reduced by 5.[[br]]When Kain enters the field,
	// choose 1 Forward in your Break Zone. Add it to your hand."
	//
	// Characters of any type count towards the three; Kain himself, not yet cast, does not.
	// =========================================================================================

	private static final String KAIN_25_114R = "Limit Break -- 1[[br]]First Strike[[br]]If you control 3 or more "
			+ "Job Dragoon and/or Card Name Dragoon, the cost required to cast Kain is reduced by 5.[[br]]When Kain "
			+ "enters the field, choose 1 Forward in your Break Zone. Add it to your hand.";

	private static CardData makeKain() {
		return makeLbForward("Kain", "Lightning", 9, 8000, 1, "Dragoon", "IV", KAIN_25_114R);
	}

	@Test
	void kainCostsFiveLessFromTheThirdDragoon() {
		MainWindow mw = new MainWindow();
		placeP1Forward(mw, makeForwardWithJob("Dragoon A", "Lightning", 3, 7000, "Dragoon"));
		placeP1Backup(mw, makeJobCard("Dragoon B", "Lightning", "Backup", "Dragoon"));
		placeP2Forward(mw, makeForwardWithJob("Their Dragoon", "Wind", 3, 7000, "Dragoon"));

		assertEquals(9, mw.castCostFor(makeKain(), true), "two of yours; the opponent's is not");
		placeP1Backup(mw, makePlainCard("Dragoon", "Wind", "Backup", 2, 0, null, null));
		assertEquals(4, mw.castCostFor(makeKain(), true), "\"and/or Card Name Dragoon\"");
	}

	@Test
	void kainReturnsAForwardFromTheBreakZone() {
		MainWindow mw = new MainWindow();
		CardData forward = makeForward("Cecil", "Dark", 3, 7000);
		CardData backup  = makeBackup("Aide", "Lightning", 2);
		for (CardData c : List.of(backup, forward)) putInP2BreakZone(mw, c);

		enterAsP2(mw, makeKain());

		assertEquals(List.of(forward), mw.gameState.getP2Hand());
		assertTrue(mw.gameState.getP2BreakZone().contains(backup), "a Forward only");
	}

	@Test
	void kainHasFirstStrike() {
		MainWindow mw = new MainWindow();
		CardData kain = makeKain();
		placeP2Forward(mw, kain);

		assertTrue(mw.effectiveP2HasTrait(mw.p2ForwardCards.indexOf(kain), CardData.Trait.FIRST_STRIKE));
	}

	// =========================================================================================
	// 25-115H Sephiroth (LB 4): "When Sephiroth enters the field or attacks, dull all the Forwards
	// other than Sephiroth.[[br]]At the end of each of your turns, choose 1 dull Forward other than
	// Sephiroth. Break it."
	//
	// "All the Forwards" is both sides, his own included.
	// =========================================================================================

	private static final String SEPHIROTH_25_115H = "Limit Break -- 4[[br]]When Sephiroth enters the field or "
			+ "attacks, dull all the Forwards other than Sephiroth.[[br]]At the end of each of your turns, choose 1 "
			+ "dull Forward other than Sephiroth. Break it.";

	private static CardData makeSephiroth() {
		return makeLbForward("Sephiroth", "Lightning", 8, 8000, 4, "War Hero", "VII", SEPHIROTH_25_115H);
	}

	@Test
	void sephirothDullsEveryOtherForwardAsHeEnters() {
		MainWindow mw = new MainWindow();
		CardData ally   = makeForward("Ally", "Lightning", 3, 7000);
		CardData theirs = makeForward("Theirs", "Water", 3, 7000);
		placeP2Forward(mw, ally);
		placeP1Forward(mw, theirs);
		CardData seph = makeSephiroth();

		enterAsP2(mw, seph);

		assertEquals(CardState.DULL, mw.p1ForwardStates.get(mw.p1ForwardCards.indexOf(theirs)));
		assertEquals(CardState.DULL, mw.p2ForwardStates.get(mw.p2ForwardCards.indexOf(ally)), "his own too");
		assertEquals(CardState.ACTIVE, mw.p2ForwardStates.get(mw.p2ForwardCards.indexOf(seph)), "not himself");
	}

	@Test
	void sephirothDullsThemAgainWhenHeAttacks() {
		MainWindow mw = new MainWindow();
		CardData seph = makeSephiroth();
		mw.suppressAutoAbilityForNextCards = 1;
		placeP2Forward(mw, seph);
		CardData theirs = makeForward("Theirs", "Water", 3, 7000);
		placeP1Forward(mw, theirs);

		attackAsP2(mw, seph);

		assertEquals(CardState.DULL, mw.p1ForwardStates.get(mw.p1ForwardCards.indexOf(theirs)));
	}

	@Test
	void atTheEndOfHisTurnSephirothBreaksADullForward() {
		MainWindow mw = new MainWindow();
		CardData seph = makeSephiroth();
		mw.suppressAutoAbilityForNextCards = 1;
		placeP2Forward(mw, seph);
		mw.p2ForwardStates.set(mw.p2ForwardCards.indexOf(seph), CardState.DULL);
		CardData dull   = makeForward("Dull", "Water", 3, 7000);
		CardData active = makeForward("Active", "Water", 2, 5000);
		placeP1Forward(mw, dull);
		placeP1Forward(mw, active);
		mw.p1ForwardStates.set(mw.p1ForwardCards.indexOf(dull), CardState.DULL);

		resolveWhatQueues(mw, seph, () -> mw.autoAbilityTriggers.triggerAutoAbilitiesForEndOfYourTurn(false));

		assertTrue(mw.gameState.getP1BreakZone().contains(dull));
		assertTrue(mw.p1ForwardCards.contains(active), "a dull Forward");
		assertTrue(mw.p2ForwardCards.contains(seph), "other than Sephiroth, dull as he is");
	}

	@Test
	void sephirothBreaksNothingAtTheEndOfTheOpponentsTurn() {
		MainWindow mw = new MainWindow();
		CardData seph = makeSephiroth();
		mw.suppressAutoAbilityForNextCards = 1;
		placeP2Forward(mw, seph);
		CardData dull = makeForward("Dull", "Water", 3, 7000);
		placeP1Forward(mw, dull);
		mw.p1ForwardStates.set(mw.p1ForwardCards.indexOf(dull), CardState.DULL);
		int before = mw.gameState.getStack().size();

		mw.autoAbilityTriggers.triggerAutoAbilitiesForEndOfYourTurn(true);

		assertEquals(before, mw.gameState.getStack().size(), "\"each of your turns\"");
		assertTrue(mw.p1ForwardCards.contains(dull));
	}

	// =========================================================================================
	// 25-116H Graff (LB 2): "When Graff enters the field, choose 1 Forward you control. Remove it
	// from the game.[[br]]When Graff attacks, choose 1 Forward opponent controls. It loses 4000
	// power until the end of the turn."
	//
	// The arrival is a price: the Forward removed is one of his own side's, Graff himself among
	// the candidates, and an LB card removed from the game goes back to the LB deck face up. The
	// CPU pays it with its cheapest Forward, and with Graff only when he stands alone
	// (MainWindow.aiCheapestOwnTargets).
	// =========================================================================================

	private static final String GRAFF_25_116H = "Limit Break -- 2[[br]]When Graff enters the field, choose 1 "
			+ "Forward you control. Remove it from the game.[[br]]When Graff attacks, choose 1 Forward opponent "
			+ "controls. It loses 4000 power until the end of the turn.";

	private static CardData makeGraff() {
		return makeLbForward("Graff", "Water", 3, 9000, 2, "Grand Général", "MOBIUS", GRAFF_25_116H);
	}

	@Test
	void aloneGraffRemovesHimselfAndGoesBackToTheLbDeck() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Fire", 3, 7000);
		placeP1Forward(mw, theirs);
		CardData graff = makeGraff();
		mw.gameState.getP2LbDeck().add(graff);

		enterAsP2(mw, graff);

		assertTrue(mw.p2ForwardCards.isEmpty(), "the only Forward he controls is himself");
		assertTrue(mw.p1ForwardCards.contains(theirs), "a Forward you control, not the opponent's");
		assertTrue(mw.gameState.getP2RemovedFromGame().isEmpty(), "an LB card does not leave the game");
		assertEquals(List.of(graff), mw.gameState.getP2LbDeck());
	}

	@Test
	void besideAnAllyGraffRemovesTheAlly() {
		MainWindow mw = new MainWindow();
		CardData ally = makeForward("Ally", "Water", 2, 3000);
		placeP2Forward(mw, ally);
		CardData graff = makeGraff();

		enterAsP2(mw, graff);

		assertEquals(List.of(graff), mw.p2ForwardCards);
		assertEquals(List.of(ally), mw.gameState.getP2RemovedFromGame());
	}

	@Test
	void graffSparesHimselfEvenBesideDearerAllies() {
		// Graff costs 3, so cost alone would pick him; the source goes last.
		MainWindow mw = new MainWindow();
		CardData dear    = makeForward("Dear", "Water", 5, 9000);
		CardData cheaper = makeForward("Cheaper", "Water", 4, 7000);
		placeP2Forward(mw, dear);
		placeP2Forward(mw, cheaper);
		CardData graff = makeGraff();

		enterAsP2(mw, graff);

		assertEquals(List.of(cheaper), mw.gameState.getP2RemovedFromGame(), "the cheapest that is not Graff");
		assertTrue(mw.p2ForwardCards.containsAll(List.of(graff, dear)));
	}

	@Test
	void whenGraffAttacksAnOpposingForwardLoses4000ForTheTurn() {
		MainWindow mw = new MainWindow();
		CardData graff = makeGraff();
		mw.suppressAutoAbilityForNextCards = 1;
		placeP2Forward(mw, graff);
		CardData theirs = makeForward("Theirs", "Fire", 3, 7000);
		placeP1Forward(mw, theirs);
		int t = mw.p1ForwardCards.indexOf(theirs);

		attackAsP2(mw, graff);

		assertEquals(3000, mw.effectiveP1ForwardPower(t));
		assertEquals(9000, mw.effectiveP2ForwardPower(mw.p2ForwardCards.indexOf(graff)), "not his own");
		mw.turnPhases().runP2EndOfTurnCleanup();
		assertEquals(7000, mw.effectiveP1ForwardPower(t), "until the end of the turn");
	}

	// =========================================================================================
	// 25-117R Leonora (LB 2): "When Leonora enters the field, choose 1 Category IV Forward in your
	// Break Zone. Add it to your hand.[[br]]《Dull》: Choose 1 Category IV Forward other than
	// Leonora. During this turn, the next damage dealt to it becomes 0 instead."
	//
	// Leonora is Category IV herself, which is why the shield names her out.
	// =========================================================================================

	private static final String LEONORA_25_117R = "Limit Break -- 2[[br]]When Leonora enters the field, choose 1 "
			+ "Category IV Forward in your Break Zone. Add it to your hand.[[br]]《Dull》: Choose 1 Category IV "
			+ "Forward other than Leonora. During this turn, the next damage dealt to it becomes 0 instead.";

	private static CardData makeLeonora() {
		return makeLbForward("Leonora", "Water", 6, 2000, 2, "Epopt", "IV", LEONORA_25_117R);
	}

	@Test
	void leonoraReturnsACategoryIvForwardFromTheBreakZone() {
		MainWindow mw = new MainWindow();
		CardData cecil  = makePlainCard("Cecil", "Dark", "Forward", 3, 7000, null, "IV");
		CardData rosa   = makePlainCard("Rosa", "Water", "Backup", 2, 0, null, "IV");
		CardData cloud  = makePlainCard("Cloud", "Wind", "Forward", 2, 5000, null, "VII");
		for (CardData c : List.of(rosa, cloud, cecil)) putInP2BreakZone(mw, c);

		enterAsP2(mw, makeLeonora());

		assertEquals(List.of(cecil), mw.gameState.getP2Hand());
		assertTrue(mw.gameState.getP2BreakZone().containsAll(List.of(rosa, cloud)), "a Category IV Forward only");
	}

	@Test
	void leonoraZeroesTheNextDamageToACategoryIvForward() {
		MainWindow mw = new MainWindow();
		CardData leonora = makeLeonora();
		mw.suppressAutoAbilityForNextCards = 1;
		placeP2Forward(mw, leonora);
		CardData edward = makePlainCard("Edward", "Water", "Forward", 2, 9000, null, "IV");
		CardData cloud  = makePlainCard("Cloud", "Wind", "Forward", 2, 9000, null, "VII");
		placeP2Forward(mw, edward);
		placeP2Forward(mw, cloud);

		useAbilityAs(mw, false, leonora, leonora.actionAbilities().get(0).effectText());
		castSummonAs(mw, false, "Deal 1000 damage to all the Forwards.");

		assertEquals(0, p2Damage(mw, edward), "the next damage becomes 0");
		assertEquals(1000, p2Damage(mw, cloud), "a Category VII Forward cannot be chosen");
		assertEquals(1000, p2Damage(mw, leonora), "nor Leonora herself");
		castSummonAs(mw, false, "Deal 1000 damage to all the Forwards.");
		assertEquals(1000, p2Damage(mw, edward), "only the next damage");
	}

	@Test
	void leonoraCannotBeUsedWithNoOtherCategoryIvForward() {
		MainWindow mw = new MainWindow();
		CardData leonora = makeLeonora();
		mw.suppressAutoAbilityForNextCards = 1;
		placeP2Forward(mw, leonora);
		placeP2Forward(mw, makePlainCard("Cloud", "Wind", "Forward", 2, 5000, null, "VII"));
		ActionAbility shield = leonora.actionAbilities().get(0);

		assertFalse(mw.canActivateAbility(shield, false, CardState.ACTIVE, 0, leonora, false),
				"other than Leonora, and of Category IV");
		placeP1Forward(mw, makePlainCard("Edge", "Wind", "Forward", 3, 7000, null, "IV"));
		assertTrue(mw.canActivateAbility(shield, false, CardState.ACTIVE, 0, leonora, false),
				"either side's");
	}

	// =========================================================================================
	// 25-118H Golbez (LB 2): "《1》《X》: Search for 1 Job Archfiend of cost X and play it onto the
	// field. You can only use this ability once per turn."
	//
	// Exactly cost X, not X or less.
	// =========================================================================================

	private static final String GOLBEZ_25_118H = "Limit Break -- 2[[br]]《1》《X》: Search for 1 Job Archfiend of "
			+ "cost X and play it onto the field. You can only use this ability once per turn.";

	private static CardData makeGolbez() {
		return makeLbForward("Golbez", "Dark", 5, 9000, 2, "Warlock", "IV", GOLBEZ_25_118H);
	}

	@Test
	void golbezsAbilityCostsXAndIsOncePerTurn() {
		ActionAbility search = makeGolbez().actionAbilities().get(0);

		assertTrue(search.hasXCost());
		assertEquals(List.of(""), search.cpCost(), "one generic CP besides X");
		assertTrue(search.oncePerTurn());
	}

	@Test
	void golbezPlaysAnArchfiendOfCostXFromTheDeck() {
		MainWindow mw = new MainWindow();
		CardData golbez = makeGolbez();
		mw.suppressAutoAbilityForNextCards = 1;
		placeP2Forward(mw, golbez);
		CardData rubicante = makeForwardWithJob("Rubicante", "Fire", 3, 8000, "Archfiend");
		CardData barbariccia = makeForwardWithJob("Barbariccia", "Wind", 4, 8000, "Archfiend");
		CardData stranger = makeForward("Stranger", "Fire", 3, 7000);
		for (CardData c : List.of(barbariccia, stranger, rubicante)) putOnTopOfDeck(mw, c, false);
		String text = golbez.actionAbilities().get(0).effectText();

		mw.currentAbilitySource = golbez;
		mw.currentAbilitySourceIsP1 = false;
		try {
			ActionResolver.parse(text, golbez, 3).accept(mw.buildGameContext(false));
		} finally { mw.currentAbilitySource = null; }

		assertTrue(mw.p2ForwardCards.contains(rubicante), "X = 3");
		assertTrue(mw.gameState.getP2MainDeck().containsAll(List.of(barbariccia, stranger)),
				"cost 4, and a cost-3 Forward that is not an Archfiend, stay behind");
	}

	// =========================================================================================
	// 26-124R Amarant (LB 1): "When Amarant enters the field, choose 1 Forward. Deal it 3000 damage
	// for every 2 Fire Characters you control."
	//
	// Amarant is Fire and on the field when it resolves, so he is one of the count.
	// =========================================================================================

	private static final String AMARANT_26_124R = "Limit Break -- 1[[br]]When Amarant enters the field, choose 1 "
			+ "Forward. Deal it 3000 damage for every 2 Fire Characters you control.";

	/**
	 * Amarant entering P2's field beside {@code fireBackups} Fire Backups and a Water one, facing a
	 * Forward big enough to read the damage off. Returns that Forward.
	 */
	private static CardData amarantEntersBeside(MainWindow mw, int fireBackups) {
		for (int i = 0; i < fireBackups; i++) placeP2Backup(mw, makeBackup("Fire Aide " + i, "Fire", 2));
		placeP2Backup(mw, makeBackup("Water Aide", "Water", 2));
		CardData theirs = makeForward("Theirs", "Water", 5, 9000);
		placeP1Forward(mw, theirs);
		enterAsP2(mw, makeLbForward("Amarant", "Fire", 7, 7000, 1, "Fighter", "IX", AMARANT_26_124R));
		return theirs;
	}

	@Test
	void amarantIsHimselfHalfOfTheFirstPair() {
		MainWindow mw = new MainWindow();
		for (int i = 0; i < 2; i++) placeP1Backup(mw, makeBackup("Their Fire " + i, "Fire", 2));

		CardData theirs = amarantEntersBeside(mw, 1);

		assertEquals(3000, p1Damage(mw, theirs),
				"Amarant and one Fire Backup; the Water Backup and the opponent's Fire Backups do not count");
	}

	@Test
	void amarantDealsAnother3000ForEachFurtherPair() {
		MainWindow mw = new MainWindow();

		CardData theirs = amarantEntersBeside(mw, 4);

		assertEquals(6000, p1Damage(mw, theirs), "five Fire Characters are two pairs; the fifth adds nothing");
	}

	// =========================================================================================
	// 26-125R Heretical Knight Garland (LB 2): "When Heretical Knight Garland enters the field,
	// choose up to 2 Backups opponent controls. Freeze them."
	// =========================================================================================

	private static final String HERETICAL_GARLAND_26_125R = "Limit Break -- 2[[br]]When Heretical Knight Garland "
			+ "enters the field, choose up to 2 Backups opponent controls. Freeze them.";

	@Test
	void hereticalKnightGarlandFreezesTwoOpposingBackups() {
		MainWindow mw = new MainWindow();
		for (int i = 0; i < 3; i++) placeP1Backup(mw, makeBackup("Their Aide " + i, "Water", 2));
		CardData theirs = makeForward("Theirs", "Water", 3, 7000);
		placeP1Forward(mw, theirs);
		placeP2Backup(mw, makeBackup("Own Aide", "Ice", 2));

		enterAsP2(mw, makeLbForward("Heretical Knight Garland", "Ice", 6, 8000, 2, "Knight", "PICTLOGICA",
				HERETICAL_GARLAND_26_125R));

		int frozen = 0;
		for (int i = 0; i < 3; i++) {
			if (!mw.p1BackupFrozen[i]) continue;
			frozen++;
			assertEquals(CardState.ACTIVE, mw.p1BackupStates[i], "freezing does not dull");
		}
		assertEquals(2, frozen, "up to 2, of the 3");
		assertFalse(mw.p2BackupFrozen[0], "opponent controls");
		assertFalse(mw.p1ForwardFrozen.get(mw.p1ForwardCards.indexOf(theirs)), "Backups only");
	}

	// =========================================================================================
	// 26-126R Yuffie (LB 2): "When Yuffie enters the field, if you control 2 or more Category VII
	// Forwards, draw 1 card.[[br]]《Dull》, discard 2 Category VII cards: Choose 1 Forward. Break it."
	//
	// Yuffie is Category VII, so one other Category VII Forward makes two. How the discard cost is
	// read is tested in CardBehaviorTest, beside Folka's and Lumina's; these pay it.
	// =========================================================================================

	private static final String YUFFIE_26_126R = "Limit Break -- 2[[br]]When Yuffie enters the field, if you control 2 "
			+ "or more Category VII Forwards, draw 1 card.[[br]]《Dull》, discard 2 Category VII cards: Choose 1 "
			+ "Forward. Break it.";

	private static CardData makeYuffie() {
		return makeLbForward("Yuffie", "Wind", 4, 5000, 2, "Ninja", "VII", YUFFIE_26_126R);
	}

	/** Yuffie on P2's field without her arrival, facing one Forward. */
	private static CardData yuffieFacing(MainWindow mw, CardData theirs) {
		CardData yuffie = makeYuffie();
		mw.suppressAutoAbilityForNextCards = 1;
		placeP2Forward(mw, yuffie);
		placeP1Forward(mw, theirs);
		return yuffie;
	}

	@Test
	void besideAnotherCategoryViiForwardYuffieDrawsACard() {
		MainWindow mw = new MainWindow();
		placeP2Forward(mw, makePlainCard("Tifa", "Earth", "Forward", 2, 5000, null, "VII"));
		putPlainCardsOnTop(mw, false, 1);

		enterAsP2(mw, makeYuffie());

		assertEquals(1, mw.gameState.getP2Hand().size());
	}

	@Test
	void besideACategoryViiBackupYuffieDrawsNothing() {
		MainWindow mw = new MainWindow();
		placeP2Backup(mw, makePlainCard("Aerith", "Earth", "Backup", 2, 0, null, "VII"));
		putPlainCardsOnTop(mw, false, 1);

		enterAsP2(mw, makeYuffie());

		assertTrue(mw.gameState.getP2Hand().isEmpty(), "Forwards: a Backup does not make the second");
	}

	@Test
	void yuffieDiscardsTwoCategoryViiCardsToBreakAForward() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 3, 7000);
		CardData yuffie = yuffieFacing(mw, theirs);
		CardData tifa    = makePlainCard("Tifa", "Earth", "Forward", 2, 5000, null, "VII");
		CardData squall  = makePlainCard("Squall", "Ice", "Forward", 2, 5000, null, "VIII");
		CardData bahamut = makePlainCard("Bahamut", "Fire", "Summon", 4, 0, null, "VII");
		for (CardData c : List.of(tifa, squall, bahamut)) putInP2Hand(mw, c);
		int y = mw.p2ForwardCards.indexOf(yuffie);
		int before = mw.gameState.stackSize();

		assertTrue(mw.autoAbilityTriggers.executeP2AbilityActivation(yuffie.actionAbilities().get(0), yuffie,
				() -> mw.p2ForwardStates.set(y, CardState.DULL), new ArrayList<>(), new ArrayList<>(), 0));
		assertEquals(List.of(squall), mw.gameState.getP2Hand(), "a Summon of the Category pays as well as a Forward");
		assertEquals(CardState.DULL, mw.p2ForwardStates.get(y));
		assertEquals(before + 1, mw.gameState.stackSize());
		resolveTop(mw);

		assertTrue(mw.gameState.getP1BreakZone().contains(theirs));
		assertTrue(mw.p2ForwardCards.contains(yuffie), "the CPU aims it across the table");
	}

	@Test
	void yuffiesBreakNeedsTwoCategoryViiCardsInHand() {
		MainWindow mw = new MainWindow();
		CardData yuffie = yuffieFacing(mw, makeForward("Theirs", "Water", 3, 7000));
		putInP2Hand(mw, makePlainCard("Tifa", "Earth", "Forward", 2, 5000, null, "VII"));
		putInP2Hand(mw, makePlainCard("Squall", "Ice", "Forward", 2, 5000, null, "VIII"));
		ActionAbility breakIt = yuffie.actionAbilities().get(0);

		assertFalse(mw.canActivateAbility(breakIt, false, CardState.ACTIVE, 0, yuffie, false));
		putInP2Hand(mw, makePlainCard("Barret", "Fire", "Forward", 3, 7000, null, "VII"));
		assertTrue(mw.canActivateAbility(breakIt, false, CardState.ACTIVE, 0, yuffie, false));
	}

	// =========================================================================================
	// 26-127R Dark Fina (LB 3): "When Dark Fina enters the field, you may cast 1 Summon of cost 7 or
	// less from your hand without paying the cost.[[br]]When you cast a Summon, choose 1 Character
	// in your Break Zone. Add it to your hand. This effect will trigger only during your turn and
	// only once per turn."
	//
	// The free cast is a cast, so on her own turn it wakes her second ability, which goes on the
	// Stack above the Summon and resolves first.
	// =========================================================================================

	private static final String DARK_FINA_26_127R = "Limit Break -- 3[[br]]When Dark Fina enters the field, you may "
			+ "cast 1 Summon of cost 7 or less from your hand without paying the cost.[[br]]When you cast a Summon, "
			+ "choose 1 Character in your Break Zone. Add it to your hand. This effect will trigger only during your "
			+ "turn and only once per turn.";

	private static CardData makeDarkFina() {
		return makeLbForward("Dark Fina", "Earth", 9, 9000, 3, "Sage of Hess", "FFBE", DARK_FINA_26_127R);
	}

	/** Dark Fina on P2's field without her arrival. */
	private static CardData darkFinaOnTheField(MainWindow mw) {
		CardData fina = makeDarkFina();
		mw.suppressAutoAbilityForNextCards = 1;
		placeP2Forward(mw, fina);
		return fina;
	}

	@Test
	void darkFinaCastsASummonOfCostSevenOrLessForFreeAndTheCastWakesHerOtherAbility() {
		MainWindow mw = new MainWindow();
		mw.gameState.startFirstTurn(GameState.Player.P2);
		CardData odin  = makeSummon("Odin", "Earth", 8, "Draw 1 card.");
		CardData titan = makeSummon("Titan", "Earth", 7, "Draw 1 card.");
		putInP2Hand(mw, odin);
		putInP2Hand(mw, titan);
		CardData lasswell = makeForward("Lasswell", "Earth", 3, 7000);
		putInP2BreakZone(mw, lasswell);
		CardData fina = makeDarkFina();
		int before = mw.gameState.stackSize();

		placeP2Forward(mw, fina);
		assertEquals(before + 1, mw.gameState.stackSize(), "her arrival");
		resolveTop(mw);

		assertEquals(List.of(odin), mw.gameState.getP2Hand(), "cost 8 is out of reach");
		assertEquals(before + 2, mw.gameState.stackSize(), "the Summon, and her cast trigger above it");
		assertSame(fina, mw.gameState.peekStack().source());
		resolveTop(mw);
		assertSame(titan, mw.gameState.peekStack().source(), "the Summon resolves after it");
		assertEquals(List.of(odin, lasswell), mw.gameState.getP2Hand());
	}

	@Test
	void darkFinaReturnsACharacterFromTheBreakZoneOncePerTurn() {
		MainWindow mw = new MainWindow();
		mw.gameState.startFirstTurn(GameState.Player.P2);
		CardData fina = darkFinaOnTheField(mw);
		CardData ifrit    = makeSummon("Ifrit", "Fire", 2, "");
		CardData lasswell = makeForward("Lasswell", "Earth", 3, 7000);
		for (CardData c : List.of(ifrit, lasswell)) putInP2BreakZone(mw, c);

		resolveWhatQueues(mw, fina, () -> mw.autoAbilityTriggers.triggerAutoAbilitiesForCastSummon(false));

		assertEquals(List.of(lasswell), mw.gameState.getP2Hand());
		assertTrue(mw.gameState.getP2BreakZone().contains(ifrit), "a Summon is not a Character");
		putInP2BreakZone(mw, makeForward("Rain", "Fire", 3, 7000));
		int before = mw.gameState.stackSize();
		mw.autoAbilityTriggers.triggerAutoAbilitiesForCastSummon(false);
		assertEquals(before, mw.gameState.stackSize(), "only once per turn");
	}

	@Test
	void darkFinaWakesOnlyToHerOwnSummonsOnHerOwnTurn() {
		MainWindow mw = new MainWindow();                          // P1 holds the turn by default
		darkFinaOnTheField(mw);
		putInP2BreakZone(mw, makeForward("Lasswell", "Earth", 3, 7000));
		int before = mw.gameState.stackSize();

		mw.autoAbilityTriggers.triggerAutoAbilitiesForCastSummon(false);
		assertEquals(before, mw.gameState.stackSize(), "her controller's Summon, on the opponent's turn");
		mw.gameState.startFirstTurn(GameState.Player.P2);
		mw.autoAbilityTriggers.triggerAutoAbilitiesForCastSummon(true);
		assertEquals(before, mw.gameState.stackSize(), "the opponent's Summon, on her controller's turn");
	}

	// =========================================================================================
	// 26-128R Shantotto (LB 3): "When Shantotto enters the field, choose up to 2 active Forwards
	// opponent controls. Break them."
	// =========================================================================================

	private static final String SHANTOTTO_26_128R = "Limit Break -- 3[[br]]When Shantotto enters the field, choose up "
			+ "to 2 active Forwards opponent controls. Break them.";

	@Test
	void shantottoBreaksTwoActiveOpposingForwards() {
		MainWindow mw = new MainWindow();
		CardData first  = makeForward("First", "Water", 3, 7000);
		CardData second = makeForward("Second", "Water", 5, 9000);
		CardData dull   = makeForward("Dull", "Water", 2, 5000);
		for (CardData c : List.of(first, second, dull)) placeP1Forward(mw, c);
		mw.p1ForwardStates.set(mw.p1ForwardCards.indexOf(dull), CardState.DULL);
		CardData ally = makeForward("Ally", "Lightning", 2, 5000);
		placeP2Forward(mw, ally);

		enterAsP2(mw, makeLbForward("Shantotto", "Lightning", 9, 8000, 3, "Mage", "XI", SHANTOTTO_26_128R));

		assertTrue(mw.gameState.getP1BreakZone().containsAll(List.of(first, second)));
		assertTrue(mw.p1ForwardCards.contains(dull), "active Forwards only");
		assertTrue(mw.p2ForwardCards.contains(ally), "opponent controls");
	}

	// =========================================================================================
	// 26-129R Refia (LB 2): "When Refia enters the field, choose 1 Category WOFF Forward of cost 3
	// or less other than Light or Dark in your Break Zone. Play it onto the field."
	// =========================================================================================

	private static final String REFIA_26_129R = "Limit Break -- 2[[br]]When Refia enters the field, choose 1 Category "
			+ "WOFF Forward of cost 3 or less other than Light or Dark in your Break Zone. Play it onto the field.";

	private static CardData makeRefia() {
		return makeLbForward("Refia", "Water", 6, 5000, 2, "White Mage", "WOFF", REFIA_26_129R);
	}

	/** Stocks P2's Break Zone with a card failing each part of Refia's filter, and returns them. */
	private static List<CardData> stockWhatRefiaCannotChoose(MainWindow mw) {
		List<CardData> cards = List.of(
				makePlainCard("Reynn", "Ice", "Forward", 4, 8000, null, "WOFF"),          // cost 4
				makePlainCard("Enna Kros", "Light", "Forward", 2, 5000, null, "WOFF"),    // Light
				makePlainCard("Shadow Lord", "Dark", "Forward", 3, 8000, null, "WOFF"),   // Dark
				makePlainCard("Cloud", "Wind", "Forward", 2, 5000, null, "VII"),          // another Category
				makePlainCard("Tama", "Wind", "Backup", 2, 0, null, "WOFF"));             // a Backup
		cards.forEach(c -> putInP2BreakZone(mw, c));
		return cards;
	}

	@Test
	void refiaPlaysACategoryWoffForwardOfCostThreeOrLessFromTheBreakZone() {
		MainWindow mw = new MainWindow();
		List<CardData> others = stockWhatRefiaCannotChoose(mw);
		CardData lann = makePlainCard("Lann", "Fire", "Forward", 3, 7000, null, "WOFF");
		putInP2BreakZone(mw, lann);

		enterAsP2(mw, makeRefia());

		assertTrue(mw.p2ForwardCards.contains(lann));
		assertTrue(mw.gameState.getP2BreakZone().containsAll(others));
	}

	@Test
	void refiaPlaysNothingWhenNoCardInTheBreakZoneQualifies() {
		MainWindow mw = new MainWindow();
		List<CardData> others = stockWhatRefiaCannotChoose(mw);
		CardData refia = makeRefia();
		int before = mw.gameState.stackSize();

		placeP2Forward(mw, refia);
		resolveDownTo(mw, before);

		assertEquals(List.of(refia), mw.p2ForwardCards);
		assertEquals(others.size(), mw.gameState.getP2BreakZone().size());
	}

	// =========================================================================================
	// 26-130H Lightning (LB 1): "If there are 7 or more face-up cards in your LB deck, Lightning
	// gains Haste.[[br]]When Lightning leaves the field, select 1 of the 2 following actions.
	// "Play 1 Card Name Lightning of cost 4 or less from your hand onto the field." "Search for 1
	// Card Name Lightning and add it to your hand.""
	//
	// An LB deck is 8 cards, and Lightning on the field is not in it, so 7 face up there means every
	// other LB card has been spent. The select resolves as the trigger fires, without going on the
	// Stack (see the Serjes section).
	// =========================================================================================

	private static final String LIGHTNING_26_130H = "Limit Break -- 1[[br]]If there are 7 or more face-up cards in "
			+ "your LB deck, Lightning gains Haste.[[br]]When Lightning leaves the field, select 1 of the 2 following "
			+ "actions.[[br]]\"Play 1 Card Name Lightning of cost 4 or less from your hand onto the field.\"[[br]]"
			+ "\"Search for 1 Card Name Lightning and add it to your hand.\"";

	/** Lightning on P2's field, with her place in the LB deck to go back to. */
	private static CardData lightningOnTheField(MainWindow mw) {
		CardData lightning = makeLbForward("Lightning", "Light", 5, 8000, 1, "Ravager/L'Cie", "XIII",
				LIGHTNING_26_130H);
		mw.gameState.getP2LbDeck().add(lightning);
		placeP2Forward(mw, lightning);
		return lightning;
	}

	@Test
	void lightningGainsHasteWithSevenFaceUpCardsLeftInTheLbDeck() {
		MainWindow mw = new MainWindow();
		int l = mw.p2ForwardCards.indexOf(lightningOnTheField(mw));
		for (int i = 0; i < 7; i++) mw.p2SpentLbIndices.add(i);

		assertFalse(mw.effectiveP2HasTrait(l, CardData.Trait.HASTE), "her own is one of the seven spent");
		mw.p2SpentLbIndices.add(7);
		assertTrue(mw.effectiveP2HasTrait(l, CardData.Trait.HASTE));
	}

	@Test
	void asLightningLeavesTheFieldALightningFromHandTakesHerPlace() {
		MainWindow mw = new MainWindow();
		CardData lightning = lightningOnTheField(mw);
		CardData fromHand = makePlainCard("Lightning", "Lightning", "Forward", 3, 7000, null, "XIII");
		putInP2Hand(mw, fromHand);

		mw.breakP2Forward(mw.p2ForwardCards.indexOf(lightning));

		assertEquals(List.of(fromHand), mw.p2ForwardCards);
		assertTrue(mw.gameState.getP2Hand().isEmpty());
		assertEquals(List.of(lightning), mw.gameState.getP2LbDeck());
	}

	@Test
	void withNoLightningSheCouldPlayLightningSearchesForOne() {
		MainWindow mw = new MainWindow();
		CardData lightning = lightningOnTheField(mw);
		CardData tooDear = makePlainCard("Lightning", "Lightning", "Forward", 5, 9000, null, "XIII");
		putInP2Hand(mw, tooDear);
		CardData inDeck = makePlainCard("Lightning", "Wind", "Forward", 2, 5000, null, "XIII");
		putOnTopOfDeck(mw, inDeck, false);
		putPlainCardsOnTop(mw, false, 2);

		mw.breakP2Forward(mw.p2ForwardCards.indexOf(lightning));

		assertTrue(mw.p2ForwardCards.isEmpty(), "cost 4 or less");
		assertEquals(List.of(tooDear, inDeck), mw.gameState.getP2Hand(), "so the search is the one taken");
	}

	// =========================================================================================
	// 27-113R Firion (LB 1): "When Firion enters the field, choose up to 3 Fire Backups and/or Wind
	// Backups. Activate them. Look at the top 3 cards of your deck. Add 1 card among them to your
	// hand and return the other cards to the bottom of your deck in any order." The "and/or"
	// defeated the Element-union rewrite, so the choose failed and the look-at-top parser took the
	// ability alone.
	// =========================================================================================

	@Test
	void firionActivatesFireAndWindBackupsThenLooksAtTheTopThree() {
		MainWindow mw = new MainWindow();
		CardData fire  = makeJobCard("Fire Aide", "Fire", "Backup", null);
		CardData wind  = makeJobCard("Wind Aide", "Wind", "Backup", null);
		CardData water = makeJobCard("Water Aide", "Water", "Backup", null);
		CardData[] row = { fire, wind, water };
		for (int i = 0; i < row.length; i++) {
			mw.gameState.getIdentity().put(row[i], false);
			mw.p2BackupCards[i] = row[i];
			mw.p2BackupStates[i] = CardState.DULL;
		}
		mw.gameState.getP2Hand().clear();
		for (int i = 0; i < 4; i++) {
			CardData c = makeForward("Deck " + i, "Fire", 2, 5000);
			mw.gameState.getIdentity().put(c, false);
			mw.gameState.getP2MainDeck().addLast(c);
		}
		CardData bottomBefore = mw.gameState.getP2MainDeck().peekLast();

		ActionResolver.parse("choose up to 3 Fire Backups and/or Wind Backups. Activate them. Look at the "
				+ "top 3 cards of your deck. Add 1 card among them to your hand and return the other cards "
				+ "to the bottom of your deck in any order.", makeForward("Firion", "Fire/Wind", 7, 9000))
				.accept(mw.buildGameContext(false));

		assertEquals(CardState.ACTIVE, mw.p2BackupStates[0]);
		assertEquals(CardState.ACTIVE, mw.p2BackupStates[1]);
		assertEquals(CardState.DULL, mw.p2BackupStates[2], "Water is neither");
		assertEquals(1, mw.gameState.getP2Hand().size(), "one of the three to hand");
		assertEquals(3, mw.gameState.getP2MainDeck().size());
		assertSame(bottomBefore, mw.gameState.getP2MainDeck().peekFirst(),
				"the untouched fourth card is now on top; the other two went under it");
	}

	// =========================================================================================
	// 27-115R Shadow Lord (LB 2): "When Shadow Lord enters the field, choose up to 2 Characters. If
	// you control 4 or more Ice Characters, dull them and Freeze them.[[br]]When Shadow Lord enters
	// the field, choose 1 Character in your Break Zone. If you control 4 or more Earth Characters,
	// add it to your hand."
	//
	// Shadow Lord is Ice and Earth, so he counts towards both.
	// =========================================================================================

	private static final String SHADOW_LORD_27_115R = "Limit Break -- 2[[br]]When Shadow Lord enters the field, "
			+ "choose up to 2 Characters. If you control 4 or more Ice Characters, dull them and Freeze them.[[br]]"
			+ "When Shadow Lord enters the field, choose 1 Character in your Break Zone. If you control 4 or more "
			+ "Earth Characters, add it to your hand.";

	/**
	 * Shadow Lord entering P2's field beside three {@code element} Backups, facing two Forwards, with
	 * {@code buried} in P2's Break Zone. Returns the two Forwards.
	 */
	private static List<CardData> shadowLordEntersBeside(MainWindow mw, String element, CardData buried) {
		for (int i = 0; i < 3; i++) placeP2Backup(mw, makeBackup(element + " Aide " + i, element, 2));
		List<CardData> theirs = List.of(makeForward("First", "Fire", 3, 7000), makeForward("Second", "Fire", 4, 8000));
		theirs.forEach(c -> placeP1Forward(mw, c));
		putInP2BreakZone(mw, buried);
		enterAsP2(mw, makeLbForward("Shadow Lord", "Ice/Earth", 6, 8000, 2, "Kindred", "XI", SHADOW_LORD_27_115R));
		return theirs;
	}

	@Test
	void withFourIceCharactersShadowLordDullsAndFreezesTwoCharacters() {
		MainWindow mw = new MainWindow();
		CardData buried = makeForward("Buried", "Earth", 3, 7000);

		for (CardData c : shadowLordEntersBeside(mw, "Ice", buried)) {
			int i = mw.p1ForwardCards.indexOf(c);
			assertEquals(CardState.DULL, mw.p1ForwardStates.get(i), c.name());
			assertTrue(mw.p1ForwardFrozen.get(i), c.name());
		}
		assertTrue(mw.gameState.getP2BreakZone().contains(buried), "one Earth Character, himself");
	}

	@Test
	void withFourEarthCharactersShadowLordReturnsACharacterFromTheBreakZone() {
		MainWindow mw = new MainWindow();
		CardData buried = makeBackup("Buried", "Water", 2);

		List<CardData> theirs = shadowLordEntersBeside(mw, "Earth", buried);

		assertEquals(List.of(buried), mw.gameState.getP2Hand(), "a Character: a Backup is one");
		for (CardData c : theirs)
			assertEquals(CardState.ACTIVE, mw.p1ForwardStates.get(mw.p1ForwardCards.indexOf(c)),
					"one Ice Character, himself");
	}

	// =========================================================================================
	// 27-116R The Emperor (LB 1): "When The Emperor attacks, choose 1 Forward. Dull it.[[br]]When
	// The Emperor leaves the field, you may search for 1 Card Name The Emperor and add it to your
	// hand."
	//
	// He goes back to the LB deck as he leaves, so the search finds another printing in the main
	// deck, never himself.
	// =========================================================================================

	private static final String EMPEROR_27_116R = "Limit Break -- 1[[br]]When The Emperor attacks, choose 1 Forward. "
			+ "Dull it.[[br]]When The Emperor leaves the field, you may search for 1 Card Name The Emperor and add "
			+ "it to your hand.";

	private static CardData makeEmperor() {
		return makeLbForward("The Emperor", "Ice/Lightning", 4, 7000, 1, "Emperor", "SOPFFO", EMPEROR_27_116R);
	}

	@Test
	void whenTheEmperorAttacksHeDullsAnOpposingForward() {
		MainWindow mw = new MainWindow();
		CardData emperor = makeEmperor();
		placeP2Forward(mw, emperor);
		CardData theirs = makeForward("Theirs", "Fire", 3, 7000);
		placeP1Forward(mw, theirs);

		attackAsP2(mw, emperor);

		assertEquals(CardState.DULL, mw.p1ForwardStates.get(mw.p1ForwardCards.indexOf(theirs)));
		assertEquals(CardState.ACTIVE, mw.p2ForwardStates.get(mw.p2ForwardCards.indexOf(emperor)), "not himself");
	}

	@Test
	void asTheEmperorLeavesTheFieldHeSearchesForAnotherEmperor() {
		MainWindow mw = new MainWindow();
		CardData emperor = makeEmperor();
		mw.gameState.getP2LbDeck().add(emperor);
		placeP2Forward(mw, emperor);
		CardData another = makePlainCard("The Emperor", "Dark", "Backup", 3, 0, null, null);
		putOnTopOfDeck(mw, another, false);
		putPlainCardsOnTop(mw, false, 2);

		resolveWhatQueues(mw, emperor, () -> mw.breakP2Forward(mw.p2ForwardCards.indexOf(emperor)));

		assertEquals(List.of(another), mw.gameState.getP2Hand());
		assertEquals(List.of(emperor), mw.gameState.getP2LbDeck(), "he goes back to the LB deck");
	}

	// =========================================================================================
	// 27-117R Onion Knight (LB 2): "When Onion Knight leaves the field, you may play 1 Category DFF
	// Forward of cost 4 or less from your hand onto the field dull.[[br]]Put Onion Knight into the
	// Break Zone: Choose 1 Forward of cost 4 or less. Break it."
	// =========================================================================================

	private static final String ONION_KNIGHT_27_117R = "Limit Break -- 2[[br]]When Onion Knight leaves the field, "
			+ "you may play 1 Category DFF Forward of cost 4 or less from your hand onto the field dull.[[br]]Put "
			+ "Onion Knight into the Break Zone: Choose 1 Forward of cost 4 or less. Break it.";

	/** Onion Knight on P2's field, with his place in the LB deck to go back to. */
	private static CardData onionKnightOnTheField(MainWindow mw) {
		CardData onion = makeLbForward("Onion Knight", "Wind/Lightning", 6, 8000, 2, "Ninja", "DFF",
				ONION_KNIGHT_27_117R);
		mw.gameState.getP2LbDeck().add(onion);
		placeP2Forward(mw, onion);
		return onion;
	}

	@Test
	void asOnionKnightLeavesTheFieldADffForwardComesInDullFromHand() {
		MainWindow mw = new MainWindow();
		CardData onion = onionKnightOnTheField(mw);
		CardData luneth = makePlainCard("Luneth", "Wind", "Forward", 4, 7000, null, "DFF");
		CardData dear   = makePlainCard("Firion", "Fire", "Forward", 5, 8000, null, "DFF");
		CardData other  = makePlainCard("Arc", "Water", "Forward", 3, 5000, null, "III");
		for (CardData c : List.of(dear, other, luneth)) putInP2Hand(mw, c);

		resolveWhatQueues(mw, onion, () -> mw.breakP2Forward(mw.p2ForwardCards.indexOf(onion)));

		assertEquals(List.of(luneth), mw.p2ForwardCards);
		assertEquals(CardState.DULL, mw.p2ForwardStates.get(0), "onto the field dull");
		assertTrue(mw.gameState.getP2Hand().containsAll(List.of(dear, other)), "Category DFF, cost 4 or less");
	}

	@Test
	void onionKnightPutsHimselfIntoTheBreakZoneToBreakAForwardOfCostFourOrLess() {
		MainWindow mw = new MainWindow();
		CardData onion = onionKnightOnTheField(mw);
		CardData cheap = makeForward("Cheap", "Water", 4, 7000);
		CardData dear  = makeForward("Dear", "Water", 5, 9000);
		placeP1Forward(mw, cheap);
		placeP1Forward(mw, dear);
		int before = mw.gameState.stackSize();

		assertTrue(mw.autoAbilityTriggers.executeP2AbilityActivation(onion.actionAbilities().get(0), onion,
				() -> {}, new ArrayList<>(), new ArrayList<>(), 0));
		assertTrue(mw.p2ForwardCards.isEmpty(), "the cost");
		assertEquals(List.of(onion), mw.gameState.getP2LbDeck(), "an LB card goes back to the LB deck");
		assertFalse(mw.p1Turn.turnOpponentFwdBroken, "putting him into the Break Zone is not breaking him");
		resolveDownTo(mw, before);

		assertTrue(mw.gameState.getP1BreakZone().contains(cheap));
		assertTrue(mw.p1ForwardCards.contains(dear), "cost 4 or less");
	}

	// =========================================================================================
	// 27-118R Vaan (LB 2): "When Vaan enters the field, if you control 4 or more Wind Characters,
	// activate all the Backups you control.[[br]]When Vaan enters the field, choose 1 Forward. If
	// you control 4 or more Water Characters, it loses 8000 power until the end of the turn."
	//
	// Vaan is Wind and Water, so he counts towards both.
	// =========================================================================================

	private static final String VAAN_27_118R = "Limit Break -- 2[[br]]When Vaan enters the field, if you control 4 "
			+ "or more Wind Characters, activate all the Backups you control.[[br]]When Vaan enters the field, "
			+ "choose 1 Forward. If you control 4 or more Water Characters, it loses 8000 power until the end of "
			+ "the turn.";

	/** Vaan entering P2's field beside three dull {@code element} Backups, facing one Forward. Returns it. */
	private static CardData vaanEntersBeside(MainWindow mw, String element) {
		for (int i = 0; i < 3; i++) placeP2Backup(mw, makeBackup(element + " Aide " + i, element, 2));
		Arrays.fill(mw.p2BackupStates, 0, 3, CardState.DULL);
		CardData theirs = makeForward("Theirs", "Fire", 5, 9000);
		placeP1Forward(mw, theirs);
		enterAsP2(mw, makeLbForward("Vaan", "Wind/Water", 7, 5000, 2, "Sky Pirate", "XII", VAAN_27_118R));
		return theirs;
	}

	@Test
	void withFourWindCharactersVaanActivatesHisBackups() {
		MainWindow mw = new MainWindow();
		placeP1Backup(mw, makeBackup("Their Aide", "Wind", 2));
		mw.p1BackupStates[0] = CardState.DULL;

		CardData theirs = vaanEntersBeside(mw, "Wind");

		for (int i = 0; i < 3; i++) assertEquals(CardState.ACTIVE, mw.p2BackupStates[i]);
		assertEquals(CardState.DULL, mw.p1BackupStates[0], "the Backups you control");
		assertEquals(9000, mw.effectiveP1ForwardPower(mw.p1ForwardCards.indexOf(theirs)),
				"one Water Character, himself");
	}

	@Test
	void withFourWaterCharactersVaanTakes8000FromAForwardForTheTurn() {
		MainWindow mw = new MainWindow();
		CardData theirs = vaanEntersBeside(mw, "Water");
		int t = mw.p1ForwardCards.indexOf(theirs);

		assertEquals(1000, mw.effectiveP1ForwardPower(t));
		assertEquals(CardState.DULL, mw.p2BackupStates[0], "one Wind Character, himself");
		mw.turnPhases().runP2EndOfTurnCleanup();
		assertEquals(9000, mw.effectiveP1ForwardPower(t), "until the end of the turn");
	}

	// =========================================================================================
	// 27-119R Warrior of Light (LB 2): "When Warrior of Light enters the field, you may search for 1
	// Earth or Water Job Standard Unit of cost 3 or less and play it onto the field.[[br]]When a Job
	// Standard Unit enters your field, draw 1 card, then discard 1 card."
	//
	// The Standard Unit his arrival plays wakes his second ability at once.
	// =========================================================================================

	private static final String WARRIOR_OF_LIGHT_27_119R = "Limit Break -- 2[[br]]When Warrior of Light enters the "
			+ "field, you may search for 1 Earth or Water Job Standard Unit of cost 3 or less and play it onto the "
			+ "field.[[br]]When a Job Standard Unit enters your field, draw 1 card, then discard 1 card.";

	private static CardData makeWarriorOfLight27() {
		return makeLbForward("Warrior of Light", "Earth/Water", 7, 5000, 2, "Warrior of Light", "SOPFFO",
				WARRIOR_OF_LIGHT_27_119R);
	}

	private static CardData standardUnit(String name, String element, int cost) {
		return makeForwardWithJob(name, element, cost, 5000, "Standard Unit");
	}

	@Test
	void warriorOfLightPlaysAnEarthOrWaterStandardUnitFromTheDeck() {
		MainWindow mw = new MainWindow();
		CardData unit     = standardUnit("Cornelian Guard", "Water", 3);
		CardData fire     = standardUnit("Fire Guard", "Fire", 2);
		CardData dear     = standardUnit("Elite Guard", "Earth", 4);
		CardData stranger = makeForwardWithJob("Knight", "Earth", 2, 5000, "Knight");
		for (CardData c : List.of(fire, dear, stranger, unit)) putOnTopOfDeck(mw, c, false);

		enterAsP2(mw, makeWarriorOfLight27());

		assertTrue(mw.p2ForwardCards.contains(unit));
		for (CardData c : List.of(fire, dear, stranger)) assertFalse(mw.p2ForwardCards.contains(c), c.name());
		assertTrue(mw.gameState.getP2Hand().isEmpty(), "its arrival drew a card, then discarded it");
		assertEquals(1, mw.gameState.getP2BreakZone().size());
	}

	@Test
	void aStandardUnitEnteringHisSideDrawsAndThenDiscards() {
		MainWindow mw = new MainWindow();
		CardData wol = makeWarriorOfLight27();
		mw.suppressAutoAbilityForNextCards = 1;
		placeP2Forward(mw, wol);
		putInP2Hand(mw, makeForward("Held", "Earth", 2, 5000));
		putPlainCardsOnTop(mw, false, 1);

		resolveWhatQueues(mw, wol, () -> placeP2Forward(mw, standardUnit("Cornelian Guard", "Water", 3)));

		assertEquals(1, mw.gameState.getP2Hand().size(), "drew one, then discarded one");
		assertEquals(1, mw.gameState.getP2BreakZone().size());
		int before = mw.gameState.stackSize();
		placeP1Forward(mw, standardUnit("Their Guard", "Fire", 2));
		assertEquals(before, mw.gameState.stackSize(), "your field");
	}

	// =========================================================================================
	// 27-120R Terra (LB 2): "When Terra enters the field, you may pay 《Lightning》. When you do so,
	// choose 1 Forward. Break it.[[br]]When Terra enters the field, you may pay 《Fire》. When you do
	// so, remove all the cards in your opponent's Break Zone from the game."
	//
	// Both resolve as she arrives (PayWhenDoSo, see the inline-auto-abilities handoff), so these
	// place her and read the board. Each board leaves P2 one Backup to pay with, so only one of the
	// two is paid.
	// =========================================================================================

	private static final String TERRA_27_120R = "Limit Break -- 2[[br]]When Terra enters the field, you may pay "
			+ "《Lightning》. When you do so, choose 1 Forward. Break it.[[br]]When Terra enters the field, you may pay "
			+ "《Fire》. When you do so, remove all the cards in your opponent's Break Zone from the game.";

	/** P1 with a Forward and a card in its Break Zone; Terra entering beside one {@code element} Backup. */
	private static List<CardData> terraEntersPaying(MainWindow mw, String element) {
		CardData theirs = makeForward("Theirs", "Water", 3, 7000);
		placeP1Forward(mw, theirs);
		CardData buried = makeForward("Buried", "Water", 2, 5000);
		mw.gameState.getIdentity().put(buried, true);
		mw.gameState.getP1BreakZone().add(buried);
		placeP2Backup(mw, makeBackup(element + " Aide", element, 2));
		placeP2Forward(mw, makeLbForward("Terra", "Lightning/Fire", 5, 7000, 2, "Magitek Knight", "VI", TERRA_27_120R));
		return List.of(theirs, buried);
	}

	@Test
	void terraPaysLightningToBreakAForward() {
		MainWindow mw = new MainWindow();

		List<CardData> theirs = terraEntersPaying(mw, "Lightning");

		assertEquals(CardState.DULL, mw.p2BackupStates[0], "《Lightning》 was paid");
		assertTrue(mw.gameState.getP1BreakZone().containsAll(theirs), "the Forward broken; no 《Fire》 to clear the zone");
		assertTrue(mw.gameState.getP1PermanentRfp().isEmpty());
	}

	@Test
	void terraPaysFireToRemoveTheOpponentsBreakZoneFromTheGame() {
		MainWindow mw = new MainWindow();
		CardData own = makeForward("Own", "Fire", 2, 5000);
		putInP2BreakZone(mw, own);

		List<CardData> theirs = terraEntersPaying(mw, "Fire");

		assertEquals(CardState.DULL, mw.p2BackupStates[0], "《Fire》 was paid");
		assertEquals(List.of(theirs.get(1)), mw.gameState.getP1PermanentRfp());
		assertTrue(mw.gameState.getP1BreakZone().isEmpty());
		assertTrue(mw.p1ForwardCards.contains(theirs.get(0)), "no 《Lightning》 for the break");
		assertEquals(List.of(own), mw.gameState.getP2BreakZone(), "your opponent's Break Zone only");
	}

	// =========================================================================================
	// 27-121R Locke (LB 2): "When Locke enters the field, choose 1 Forward opponent controls. Return
	// it to its owner's hand.[[br]]When a Forward opponent controls is returned from the field to its
	// owner's hand, your opponent discards 1 card. This effect will trigger only once per turn."
	//
	// His own arrival is the first return. P1 discards, and a P1 choice opens a dialog, so each
	// board leaves P1 one card to discard.
	// =========================================================================================

	private static final String LOCKE_27_121R = "Limit Break -- 2[[br]]When Locke enters the field, choose 1 Forward "
			+ "opponent controls. Return it to its owner's hand.[[br]]When a Forward opponent controls is returned "
			+ "from the field to its owner's hand, your opponent discards 1 card. This effect will trigger only "
			+ "once per turn.";

	private static CardData makeLocke() {
		return makeLbForward("Locke", "Water/Ice", 7, 8000, 2, "Treasure Hunter", "VI", LOCKE_27_121R);
	}

	@Test
	void lockeReturnsAnOpposingForwardAndTheReturnMakesTheOpponentDiscard() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Fire", 5, 9000);
		placeP1Forward(mw, theirs);
		CardData ally = makeForward("Ally", "Water", 2, 5000);
		placeP2Forward(mw, ally);

		enterAsP2(mw, makeLocke());

		assertTrue(mw.p1ForwardCards.isEmpty());
		assertTrue(mw.p2ForwardCards.contains(ally), "opponent controls");
		assertTrue(mw.gameState.getP1Hand().isEmpty(), "returned, then discarded as the only card in hand");
		assertEquals(List.of(theirs), mw.gameState.getP1BreakZone());
	}

	@Test
	void lockesDiscardWatchesOpposingForwardsOncePerTurn() {
		MainWindow mw = new MainWindow();
		CardData locke = makeLocke();
		mw.suppressAutoAbilityForNextCards = 1;
		placeP2Forward(mw, locke);
		CardData held = makeForward("Held", "Fire", 2, 5000);
		mw.gameState.getIdentity().put(held, true);
		mw.gameState.getP1Hand().add(held);
		int before = mw.gameState.stackSize();

		mw.autoAbilityTriggers.triggerAutoAbilitiesForCharacterReturnedToHand(false, true);
		mw.autoAbilityTriggers.triggerAutoAbilitiesForCharacterReturnedToHand(true, false);
		assertEquals(before, mw.gameState.stackSize(), "his own side's Forward, and an opposing Backup");
		resolveWhatQueues(mw, locke, () -> mw.autoAbilityTriggers.triggerAutoAbilitiesForCharacterReturnedToHand(true, true));
		assertEquals(List.of(held), mw.gameState.getP1BreakZone());
		mw.autoAbilityTriggers.triggerAutoAbilitiesForCharacterReturnedToHand(true, true);
		assertEquals(before, mw.gameState.stackSize(), "only once per turn");
	}

	// =========================================================================================
	// 28-119R Serah (LB 1): "When Serah enters the field, choose 1 Forward. If you control 3 or more
	// Fire Characters, deal it 5000 damage.[[br]]When Serah enters the field, if you control 3 or
	// more Ice Characters, your opponent discards 1 card."
	//
	// Serah is Fire and Ice, so she counts towards both. P1 discards, so P1 holds one card: a
	// choice between two would open a dialog.
	// =========================================================================================

	private static final String SERAH_28_119R = "Limit Break -- 1[[br]]When Serah enters the field, choose 1 Forward. "
			+ "If you control 3 or more Fire Characters, deal it 5000 damage.[[br]]When Serah enters the field, if "
			+ "you control 3 or more Ice Characters, your opponent discards 1 card.";

	/** Serah entering P2's field beside two {@code element} Backups, facing a Forward and a one-card hand. */
	private static CardData serahEntersBeside(MainWindow mw, String element) {
		for (int i = 0; i < 2; i++) placeP2Backup(mw, makeBackup(element + " Aide " + i, element, 2));
		CardData theirs = makeForward("Theirs", "Water", 5, 9000);
		placeP1Forward(mw, theirs);
		CardData held = makeForward("Held", "Water", 2, 5000);
		mw.gameState.getIdentity().put(held, true);
		mw.gameState.getP1Hand().add(held);
		enterAsP2(mw, makeLbForward("Serah", "Fire/Ice", 5, 5000, 1, "Saboteur", "XIII", SERAH_28_119R));
		return theirs;
	}

	@Test
	void withThreeFireCharactersSerahDeals5000Damage() {
		MainWindow mw = new MainWindow();

		CardData theirs = serahEntersBeside(mw, "Fire");

		assertEquals(5000, p1Damage(mw, theirs));
		assertEquals(1, mw.gameState.getP1Hand().size(), "one Ice Character, herself");
	}

	@Test
	void withThreeIceCharactersSerahMakesTheOpponentDiscard() {
		MainWindow mw = new MainWindow();

		CardData theirs = serahEntersBeside(mw, "Ice");

		assertTrue(mw.gameState.getP1Hand().isEmpty());
		assertEquals(1, mw.gameState.getP1BreakZone().size(), "the card is discarded");
		assertEquals(0, p1Damage(mw, theirs), "one Fire Character, herself");
	}

	// =========================================================================================
	// 28-120R Ritz (LB 1): "When Ritz enters the field, gain 《C》."
	// =========================================================================================

	private static final String RITZ_28_120R = "Limit Break -- 1[[br]]When Ritz enters the field, gain 《C》.";

	@Test
	void ritzGainsACrystalAsSheEnters() {
		MainWindow mw = new MainWindow();
		int p1Before = mw.gameState.getP1Crystals();

		enterAsP2(mw, makeLbForward("Ritz", "Ice/Wind", 5, 5000, 1, "Fencer", "FFTA", RITZ_28_120R));

		assertEquals(1, mw.gameState.getP2Crystals());
		assertEquals(p1Before, mw.gameState.getP1Crystals(), "her controller's");
	}

	// =========================================================================================
	// 28-121R Gilgamesh (LB 1): "First Strike Brave"
	//
	// Two keywords on one line, with no break between them.
	// =========================================================================================

	@Test
	void gilgameshHasFirstStrikeAndBrave() {
		MainWindow mw = new MainWindow();
		CardData gilgamesh = makeLbForward("Gilgamesh", "Wind/Earth", 4, 8000, 1, "Warrior", "VII",
				"Limit Break -- 1[[br]]First Strike Brave");
		placeP2Forward(mw, gilgamesh);
		int g = mw.p2ForwardCards.indexOf(gilgamesh);

		assertTrue(mw.effectiveP2HasTrait(g, CardData.Trait.FIRST_STRIKE));
		assertTrue(mw.effectiveP2HasTrait(g, CardData.Trait.BRAVE));
	}

	// =========================================================================================
	// 28-122R Rufus (LB 2): "When Rufus enters the field, you may pay 《Earth》《Lightning》. When you
	// do so, choose 1 Category VII Forward of cost 3 or less in your Break Zone. Play it onto the
	// field."
	//
	// Placed, and then anything it queued resolved, so the tests read the same whether the trigger
	// layer resolves the payment inline or puts it on the Stack.
	// =========================================================================================

	private static final String RUFUS_28_122R = "Limit Break -- 2[[br]]When Rufus enters the field, you may pay "
			+ "《Earth》《Lightning》. When you do so, choose 1 Category VII Forward of cost 3 or less in your Break "
			+ "Zone. Play it onto the field.";

	/**
	 * Rufus entering P2's field beside one active Backup of each of {@code elements}, with a Category
	 * VII Forward of cost 3 in the Break Zone and two that miss the filter. Returns the cost-3 one.
	 */
	private static CardData rufusEntersBeside(MainWindow mw, String... elements) {
		for (String e : elements) placeP2Backup(mw, makeBackup(e + " Aide", e, 2));
		CardData tseng = makePlainCard("Tseng", "Lightning", "Forward", 3, 7000, null, "VII");
		putInP2BreakZone(mw, makePlainCard("Reno", "Lightning", "Forward", 4, 7000, null, "VII"));
		putInP2BreakZone(mw, makePlainCard("Kain", "Lightning", "Forward", 2, 5000, null, "IV"));
		putInP2BreakZone(mw, tseng);
		CardData rufus = makeLbForward("Rufus", "Earth/Lightning", 5, 8000, 2, "President of Shinra", "VII",
				RUFUS_28_122R);
		int before = mw.gameState.stackSize();
		placeP2Forward(mw, rufus);
		resolveDownTo(mw, before);
		return tseng;
	}

	@Test
	void rufusPaysEarthAndLightningToPlayACategoryViiForwardFromTheBreakZone() {
		MainWindow mw = new MainWindow();

		CardData tseng = rufusEntersBeside(mw, "Earth", "Lightning");

		assertTrue(mw.p2ForwardCards.contains(tseng));
		assertEquals(2, mw.gameState.getP2BreakZone().size(), "cost 4, and Category IV, stay behind");
		assertEquals(CardState.DULL, mw.p2BackupStates[0], "《Earth》");
		assertEquals(CardState.DULL, mw.p2BackupStates[1], "《Lightning》");
	}

	@Test
	void withNothingInTheBreakZoneToTakeRufusPaysNothing() {
		MainWindow mw = new MainWindow();
		placeP2Backup(mw, makeBackup("Earth Aide", "Earth", 2));
		placeP2Backup(mw, makeBackup("Lightning Aide", "Lightning", 2));
		putInP2BreakZone(mw, makePlainCard("Reno", "Lightning", "Forward", 4, 7000, null, "VII"));
		int before = mw.gameState.stackSize();

		placeP2Forward(mw, makeLbForward("Rufus", "Earth/Lightning", 5, 8000, 2, "President of Shinra", "VII",
				RUFUS_28_122R));
		resolveDownTo(mw, before);

		assertEquals(CardState.ACTIVE, mw.p2BackupStates[0], "the CPU does not pay for an empty choice");
		assertEquals(CardState.ACTIVE, mw.p2BackupStates[1]);
	}

	@Test
	void withoutTheLightningRufusPlaysNothing() {
		MainWindow mw = new MainWindow();

		CardData tseng = rufusEntersBeside(mw, "Earth");

		assertFalse(mw.p2ForwardCards.contains(tseng), "the play is what the payment buys");
		assertEquals(CardState.ACTIVE, mw.p2BackupStates[0], "nothing paid");
	}

	// =========================================================================================
	// 28-123R Ashe (LB 2): "When Ashe enters the field, reveal the top 5 cards of your deck. Add 1
	// Category MBM Character among them to your hand and return the other cards to the bottom of
	// your deck in any order.[[br]]At the beginning of the Attack Phase during each of your turns,
	// you may pay 《1》. When you do so, play 1 Category MBM Character of cost 3 or less from your
	// hand onto the field."
	// =========================================================================================

	private static final String ASHE_28_123R = "Limit Break -- 2[[br]]When Ashe enters the field, reveal the top 5 "
			+ "cards of your deck. Add 1 Category MBM Character among them to your hand and return the other cards to "
			+ "the bottom of your deck in any order.[[br]]At the beginning of the Attack Phase during each of your "
			+ "turns, you may pay 《1》. When you do so, play 1 Category MBM Character of cost 3 or less from your "
			+ "hand onto the field.";

	private static CardData makeAshe() {
		return makeLbForward("Ashe", "Lightning/Water", 6, 8000, 2, "Princess", "MBM", ASHE_28_123R);
	}

	@Test
	void asheAddsACategoryMbmCharacterFromTheTopFive() {
		MainWindow mw = new MainWindow();
		CardData sixth = makePlainCard("Basch", "Fire", "Forward", 3, 7000, null, "MBM");
		putOnTopOfDeck(mw, sixth, false);
		CardData vaan    = makePlainCard("Vaan", "Wind", "Forward", 3, 7000, null, "MBM");
		CardData esper   = makePlainCard("Belias", "Fire", "Summon", 3, 0, null, "MBM");
		CardData outside = makePlainCard("Balthier", "Wind", "Forward", 3, 7000, null, "XII");
		for (CardData c : List.of(vaan, esper, outside)) putOnTopOfDeck(mw, c, false);
		putPlainCardsOnTop(mw, false, 2);

		enterAsP2(mw, makeAshe());

		assertEquals(List.of(vaan), mw.gameState.getP2Hand(), "a Character, not the Summon; the sixth is out of reach");
		assertSame(sixth, mw.gameState.getP2MainDeck().peekFirst(), "the other four went under it");
		assertEquals(5, mw.gameState.getP2MainDeck().size());
	}

	@Test
	void atTheStartOfHerAttackPhaseAshePaysOneToPlayACheapMbmCharacter() {
		MainWindow mw = new MainWindow();
		mw.gameState.startFirstTurn(GameState.Player.P2);
		mw.suppressAutoAbilityForNextCards = 1;
		placeP2Forward(mw, makeAshe());
		placeP2Backup(mw, makeBackup("Aide", "Fire", 2));
		CardData penelo = makePlainCard("Penelo", "Wind", "Forward", 3, 5000, null, "MBM");
		CardData dear   = makePlainCard("Basch", "Fire", "Forward", 4, 8000, null, "MBM");
		CardData other  = makePlainCard("Larsa", "Earth", "Backup", 2, 0, null, "XII");
		for (CardData c : List.of(dear, other, penelo)) putInP2Hand(mw, c);
		int before = mw.gameState.stackSize();

		mw.autoAbilityTriggers.triggerAutoAbilitiesForBeginningOfAttackPhase(false);
		resolveDownTo(mw, before);

		assertTrue(mw.p2ForwardCards.contains(penelo));
		assertEquals(List.of(dear, other), mw.gameState.getP2Hand(), "cost 3 or less, Category MBM");
		assertEquals(CardState.DULL, mw.p2BackupStates[0], "《1》 was paid");
	}

	@Test
	void ashePlaysNothingAtTheStartOfTheOpponentsAttackPhase() {
		MainWindow mw = new MainWindow();                          // P1 holds the turn by default
		mw.suppressAutoAbilityForNextCards = 1;
		placeP2Forward(mw, makeAshe());
		placeP2Backup(mw, makeBackup("Aide", "Fire", 2));
		putInP2Hand(mw, makePlainCard("Penelo", "Wind", "Forward", 3, 5000, null, "MBM"));
		int before = mw.gameState.stackSize();

		mw.autoAbilityTriggers.triggerAutoAbilitiesForBeginningOfAttackPhase(true);
		resolveDownTo(mw, before);

		assertEquals(1, mw.gameState.getP2Hand().size(), "during each of your turns");
		assertEquals(CardState.ACTIVE, mw.p2BackupStates[0]);
	}

	// =========================================================================================
	// 28-124R Bahamut (LB 1): "When Bahamut enters the field, choose 1 Forward. It loses 4000 power
	// until the end of the turn.[[br]]When Bahamut enters the field, choose 1 Forward. Deal it 4000
	// damage."
	//
	// Two separate abilities, each with its own choice. Neither alone breaks a 7000-power Forward;
	// together they do.
	// =========================================================================================

	private static final String BAHAMUT_28_124R = "Limit Break -- 1[[br]]When Bahamut enters the field, choose 1 "
			+ "Forward. It loses 4000 power until the end of the turn.[[br]]When Bahamut enters the field, choose 1 "
			+ "Forward. Deal it 4000 damage.";

	private static CardData makeBahamut28() {
		return makeLbForward("Bahamut", "Water/Fire", 9, 8000, 1, "Dragon", "SOPFFO", BAHAMUT_28_124R);
	}

	@Test
	void bahamutTakes4000PowerAndDeals4000Damage() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Earth", 5, 9000);
		placeP1Forward(mw, theirs);
		int t = mw.p1ForwardCards.indexOf(theirs);

		enterAsP2(mw, makeBahamut28());

		assertEquals(5000, mw.effectiveP1ForwardPower(t));
		assertEquals(4000, p1Damage(mw, theirs));
		mw.turnPhases().runP2EndOfTurnCleanup();
		assertEquals(9000, mw.effectiveP1ForwardPower(t), "the loss lasts until the end of the turn");
	}

	@Test
	void togetherBahamutsTwoAbilitiesBreakA7000PowerForward() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Earth", 4, 7000);
		placeP1Forward(mw, theirs);

		enterAsP2(mw, makeBahamut28());

		assertTrue(mw.gameState.getP1BreakZone().contains(theirs), "3000 power, 4000 damage");
	}

	// =========================================================================================
	// 29-119R Reynn (LB 2): "When Reynn enters the field, reveal the top 5 cards of your deck. Add 1
	// Category WOFF Character among them to your hand and return the other cards to the bottom of
	// your deck in any order.[[br]]《Dull》, put Reynn into the Break Zone: Play 1 Category WOFF
	// Character of cost 3 or less from your hand onto the field."
	// =========================================================================================

	private static final String REYNN_29_119R = "Limit Break -- 2[[br]]When Reynn enters the field, reveal the top "
			+ "5 cards of your deck. Add 1 Category WOFF Character among them to your hand and return the other cards "
			+ "to the bottom of your deck in any order.[[br]]《Dull》, put Reynn into the Break Zone: Play 1 Category "
			+ "WOFF Character of cost 3 or less from your hand onto the field.";

	private static CardData makeReynn() {
		return makeLbForward("Reynn", "Fire", 5, 5000, 2, "Mirage Keeper", "DFF", "WOFF", REYNN_29_119R);
	}

	@Test
	void reynnAddsACategoryWoffCharacterFromTheTopFive() {
		MainWindow mw = new MainWindow();
		CardData sixth = makePlainCard("Lann", "Fire", "Forward", 3, 7000, null, "WOFF");
		putOnTopOfDeck(mw, sixth, false);
		CardData tama    = makePlainCard("Tama", "Wind", "Backup", 2, 0, null, "WOFF");
		CardData mirage  = makePlainCard("Chocochick", "Earth", "Summon", 2, 0, null, "WOFF");
		CardData outside = makePlainCard("Tidus", "Water", "Forward", 3, 7000, null, "X");
		for (CardData c : List.of(tama, mirage, outside)) putOnTopOfDeck(mw, c, false);
		putPlainCardsOnTop(mw, false, 2);

		enterAsP2(mw, makeReynn());

		assertEquals(List.of(tama), mw.gameState.getP2Hand(), "a Character, Backups included, not the Summon; "
				+ "the sixth card is out of reach");
		assertSame(sixth, mw.gameState.getP2MainDeck().peekFirst(), "the other four went under it");
		assertEquals(5, mw.gameState.getP2MainDeck().size());
	}

	@Test
	void reynnPutsHerselfIntoTheBreakZoneToPlayACheapWoffCharacter() {
		MainWindow mw = new MainWindow();
		CardData reynn = makeReynn();
		mw.gameState.getP2LbDeck().add(reynn);
		mw.suppressAutoAbilityForNextCards = 1;
		placeP2Forward(mw, reynn);
		CardData lann  = makePlainCard("Lann", "Fire", "Forward", 3, 7000, null, "WOFF");
		CardData dear  = makePlainCard("Enna Kros", "Fire", "Forward", 5, 9000, null, "WOFF");
		CardData other = makePlainCard("Firion", "Fire", "Forward", 2, 5000, null, "II");
		for (CardData c : List.of(dear, other, lann)) putInP2Hand(mw, c);
		int r = mw.p2ForwardCards.indexOf(reynn);
		int before = mw.gameState.stackSize();

		assertTrue(mw.autoAbilityTriggers.executeP2AbilityActivation(reynn.actionAbilities().get(0), reynn,
				() -> mw.p2ForwardStates.set(r, CardState.DULL), new ArrayList<>(), new ArrayList<>(), 0));
		assertTrue(mw.p2ForwardCards.isEmpty(), "the cost");
		assertEquals(List.of(reynn), mw.gameState.getP2LbDeck(), "an LB card goes back to the LB deck");
		resolveDownTo(mw, before);

		assertEquals(List.of(lann), mw.p2ForwardCards);
		assertEquals(List.of(dear, other), mw.gameState.getP2Hand(), "Category WOFF, cost 3 or less");
	}

	// =========================================================================================
	// 29-120R Seymour (LB 2): "When Seymour enters the field, select 1 Job Summoner you control.
	// Put it into the Break Zone. When you do so, choose 1 dull Forward opponent controls. Break
	// it. Your opponent discards 1 card."
	//
	// The select is mandatory — there is no "you may" — and is read by the select-then-put
	// rewrite. Driven as P2.
	// =========================================================================================

	@Test
	void seymourSacrificesASummonerToBreakADullForward() {
		MainWindow mw = new MainWindow();
		CardData seymour = makeTextCard("Seymour", "Ice", "Forward", 7, 9000, null,
				"When Seymour enters the field, select 1 Job Summoner you control. Put it into the Break "
				+ "Zone. When you do so, choose 1 dull Forward opponent controls. Break it. Your opponent "
				+ "discards 1 card.");
		CardData summoner = makeTextCard("Yuna", "Ice", "Backup", 2, 0, "Summoner", "");
		CardData victim = makeForward("Victim", "Fire", 5, 9000);
		mw.gameState.getIdentity().put(seymour, false);
		mw.gameState.getIdentity().put(summoner, false);
		mw.placeP2CardInFirstBackupSlot(summoner);
		mw.gameState.getIdentity().put(victim, true);
		mw.placeCardInForwardZone(victim);
		mw.p1ForwardStates.set(0, CardState.DULL);
		// P1's hand is left empty: the discard falls to P1, and a card there would open its dialog.
		mw.placeP2CardInForwardZone(seymour);
		assertFalse(seymour.autoAbilities().get(0).youMay(), "Seymour's cost is not optional");

		mw.autoAbilityTriggers.triggerAutoAbilitiesForEntersField(seymour, false);

		assertTrue(mw.gameState.getP2BreakZone().contains(summoner));
		assertFalse(mw.p1ForwardCards.contains(victim));
	}

	// =========================================================================================
	// 29-121R Wind Drake (LB 1): "When Wind Drake enters the field, choose 1 Character in your
	// Break Zone. Remove it from the game.[[br]]When Wind Drake leaves the field, add the card
	// removed by Wind Drake's ability to your hand."
	//
	// The removal is credited to Wind Drake in the cardsRemovedBySource pile, which the leave
	// trigger empties again; the retrieval is worded with the definite article ("the card")
	// because the removal takes exactly one. Its own trip to the Break Zone as an LB card is in
	// the first section.
	// =========================================================================================

	private static final String WIND_DRAKE_TEXT =
			"Limit Break -- 1[[br]]When Wind Drake enters the field, choose 1 Character in your "
			+ "Break Zone. Remove it from the game.[[br]]When Wind Drake leaves the field, add the "
			+ "card removed by Wind Drake's ability to your hand.";

	/** Wind Drake as printed: an LB Forward carrying both of its auto-abilities. */
	private static CardData makeWindDrake() {
		return new CardData(null, "Wind Drake", "Wind", 5, 8000, "Forward", true, 1, false, false,
				Set.of(), 0, List.of(), null, List.of(),
				List.of(), CardData.parseAutoAbilities(WIND_DRAKE_TEXT), List.of(), List.of(), List.of(),
				List.of(), List.of(), List.of(), List.of(), List.of(),
				false, false, null, false, false, false, false, false, 1,
				"Dragon", "V", null, WIND_DRAKE_TEXT);
	}

	/** Seats {@code cards} in P2's Break Zone with their owner recorded. */
	private static void stockP2BreakZone(MainWindow mw, CardData... cards) {
		for (CardData c : cards) {
			mw.gameState.getIdentity().put(c, false);
			mw.gameState.getP2BreakZone().add(c);
		}
	}

	@Test
	void windDrakeSplitsIntoAnEnterAndALeaveTrigger() {
		List<AutoAbility> autos = makeWindDrake().autoAbilities();
		assertEquals(2, autos.size(), "the Limit Break declaration is a card property, not an ability");
		assertEquals("enters the field", autos.get(0).trigger());
		assertEquals("leaves the field", autos.get(1).trigger());
		assertEquals("Wind Drake", autos.get(1).triggerCard(),
				"the leave trigger is keyed to Wind Drake itself, or it never fires");
	}

	@Test
	void windDrakeExilesAndReturnsOneBreakZoneCharacter() {
		MainWindow mw = new MainWindow();
		CardData drake = makeWindDrake();
		mw.gameState.getIdentity().put(drake, false);   // P2's side: the AI picks without a dialog
		CardData bz1 = makeForward("BzOne", "Wind", 2, 5000);
		CardData bz2 = makeForward("BzTwo", "Wind", 4, 9000);
		stockP2BreakZone(mw, bz1, bz2);

		GameContext ctx = mw.buildGameContext(false);
		mw.currentAbilitySource = drake;                 // as the stack sets while an ability resolves
		try {
			ActionResolver.parse(drake.autoAbilities().get(0).effectText(), drake).accept(ctx);
		} finally {
			mw.currentAbilitySource = null;
		}
		assertEquals(1, mw.gameState.getP2BreakZone().size(), "exactly one card left the Break Zone");
		assertEquals(1, mw.gameState.getP2PermanentRfp().size(), "and is out of the game");
		assertEquals(1, ctx.cardsRemovedBySourceCount(drake),
				"credited to Wind Drake, which is how the leave trigger finds it again");

		CardData exiled = mw.gameState.getP2PermanentRfp().get(0);
		ActionResolver.parse(drake.autoAbilities().get(1).effectText(), drake)
				.accept(mw.buildGameContext(false));
		assertEquals(List.of(exiled), mw.gameState.getP2Hand(), "the exiled card comes back to hand");
		assertEquals(0, mw.gameState.getP2PermanentRfp().size(), "and is no longer out of the game");
		assertFalse(mw.cardsRemovedBySource.containsKey(drake), "the pile is emptied on the way out");
	}

	@Test
	void windDrakeIgnoresNonCharactersInTheBreakZone() {
		MainWindow mw = new MainWindow();
		CardData drake = makeWindDrake();
		mw.gameState.getIdentity().put(drake, false);
		CardData summon = makeSummon("Kujata", "Wind", 2, "");
		stockP2BreakZone(mw, summon);

		GameContext ctx = mw.buildGameContext(false);
		mw.currentAbilitySource = drake;
		try {
			ActionResolver.parse(drake.autoAbilities().get(0).effectText(), drake).accept(ctx);
		} finally {
			mw.currentAbilitySource = null;
		}
		assertEquals(List.of(summon), mw.gameState.getP2BreakZone(),
				"a Summon is not a Character — nothing eligible, so nothing is removed");
		assertEquals(0, ctx.cardsRemovedBySourceCount(drake));
	}

	@Test
	void windDrakeLeaveTriggerIsQuietWhenItRemovedNothing() {
		MainWindow mw = new MainWindow();
		CardData drake = makeWindDrake();
		mw.gameState.getIdentity().put(drake, false);

		// No enter-the-field removal happened, so there is no pile to call back.
		ActionResolver.parse(drake.autoAbilities().get(1).effectText(), drake)
				.accept(mw.buildGameContext(false));
		assertTrue(mw.gameState.getP2Hand().isEmpty(), "nothing to add to hand");
	}

	// =========================================================================================
	// 29-122R Mika (LB 1): "When Mika enters the field, choose 1 Forward. It gains "If possible,
	// this Forward must block." until the end of the turn."
	//
	// Rule 10.1.3.1.3: a Forward compelled to block must be the one declared, so neither declining
	// nor a different blocker is on offer, and whether it survives does not matter. A compelled
	// Forward that cannot block lifts the compulsion: that is the "if possible".
	// =========================================================================================

	private static final String MIKA_29_122R = "Limit Break -- 1[[br]]When Mika enters the field, choose 1 Forward. "
			+ "It gains \"If possible, this Forward must block.\" until the end of the turn.";

	private static CardData makeMika() {
		return makeLbForward("Mika", "Earth", 3, 5000, 1, "Yevon Maester", "X", MIKA_29_122R);
	}

	/**
	 * Mika enters P2's field facing {@code theirs} alone, so the CPU's choice is P1's one Forward,
	 * then attacks during P2's Attack Phase, with P1 to declare a block.
	 */
	private static CardData mikaCompelsThenAttacks(MainWindow mw, CardData theirs) {
		mw.gameState.startFirstTurn(GameState.Player.P2);
		for (int i = 0; i < 3; i++) mw.gameState.advancePhase();   // Draw, Main 1, Attack
		placeP1Forward(mw, theirs);
		CardData mika = makeMika();
		enterAsP2(mw, mika);
		mw.pendingP2Attacker    = mika;
		mw.pendingP2AttackerIdx = mw.p2ForwardCards.indexOf(mika);
		return mika;
	}

	@Test
	void theForwardMikaChoosesMustBlockUntilTheEndOfTheTurn() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Fire", 3, 7000);
		placeP1Forward(mw, theirs);

		enterAsP2(mw, makeMika());

		CardData anyAttacker = makeForward("Anyone", "Ice", 2, 3000);
		assertTrue(mw.forwardCompelledToBlock(theirs, anyAttacker), "it names no attacker, so it binds against all");
		assertFalse(mw.forwardCompelledToBlock(mw.p2ForwardCards.get(0), anyAttacker), "the CPU aims it across the table");
		mw.turnPhases().runP2EndOfTurnCleanup();
		assertFalse(mw.forwardCompelledToBlock(theirs, anyAttacker), "until the end of the turn");
	}

	@Test
	void theForwardMikaChoseIsTheOnlyBlockAndDecliningIsRefused() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Fire", 3, 3000);   // it does not survive the block
		mikaCompelsThenAttacks(mw, theirs);
		CardData bystander = makeForward("Bystander", "Fire", 3, 8000);
		placeP1Forward(mw, bystander);
		int t = mw.p1ForwardCards.indexOf(theirs), b = mw.p1ForwardCards.indexOf(bystander);

		assertTrue(mw.isForwardBlockSelectable(t));
		assertFalse(mw.isForwardBlockSelectable(b), "a different blocker would dodge the compulsion");
		assertNotNull(mw.p1BlockDeclarationRefusal(), "and so would declining");
		mw.p1BlockerSelection = b;
		assertNotNull(mw.p1BlockDeclarationRefusal());
		mw.p1BlockerSelection = t;
		assertNull(mw.p1BlockDeclarationRefusal());
	}

	@Test
	void mikasCompulsionLiftsWhenTheChosenForwardCannotBlock() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Fire", 3, 7000);
		mikaCompelsThenAttacks(mw, theirs);
		CardData bystander = makeForward("Bystander", "Fire", 3, 8000);
		placeP1Forward(mw, bystander);
		mw.p1ForwardStates.set(mw.p1ForwardCards.indexOf(theirs), CardState.DULL);

		assertTrue(mw.isForwardBlockSelectable(mw.p1ForwardCards.indexOf(bystander)), "if possible");
		assertNull(mw.p1BlockDeclarationRefusal(), "and declining is open again");
	}

	@Test
	void theCpuBlocksWithTheForwardMikaChoseEvenWhenItWillNotSurvive() {
		MainWindow mw = new MainWindow();
		CardData attacker = makeForward("Attacker", "Fire", 4, 7000);
		CardData frail  = makeForward("Frail", "Ice", 2, 3000);
		CardData sturdy = makeForward("Sturdy", "Ice", 4, 9000);
		placeP1Forward(mw, attacker);
		placeP2Forward(mw, frail);
		placeP2Forward(mw, sturdy);
		CardData mika = makeMika();
		int f = mw.p2ForwardCards.indexOf(frail);
		// P1's Mika, with the choice made as it went on the Stack.
		GameContext ctx = mw.buildGameContext(true);
		ctx.preloadTargets(List.of(new ForwardTarget(false, f, ForwardTarget.CardZone.FORWARD)));
		ActionResolver.parse(mika.autoAbilities().get(0).effectText(), mika).accept(ctx);
		ComputerPlayer cp = new ComputerPlayer(mw);
		ForwardTarget p1Attack = new ForwardTarget(true, mw.p1ForwardCards.indexOf(attacker), ForwardTarget.CardZone.FORWARD);

		assertEquals(new ForwardTarget(false, f, ForwardTarget.CardZone.FORWARD), cp.chooseBlocker(7000, p1Attack),
				"Frail blocks, though Sturdy would survive and break the attacker");
		mw.p2ForwardStates.set(f, CardState.DULL);
		assertEquals(new ForwardTarget(false, mw.p2ForwardCards.indexOf(sturdy), ForwardTarget.CardZone.FORWARD),
				cp.chooseBlocker(7000, p1Attack), "a dull Frail cannot block, which lifts it");
	}

	// =========================================================================================
	// 29-123R Jack Garland (LB 1): "Jack Garland cannot be blocked by a Monster that is also a
	// Forward." A restriction on the blocker's card type, barring exactly the Monsters an effect
	// turned into Forwards, and leaving Backups acting as Forwards alone since those are not
	// Monsters. (His full printed text is the first section's example of the Limit Break line.)
	// =========================================================================================

	private static final String GARLAND_NO_MONSTER_BLOCK =
			"Jack Garland cannot be blocked by a Monster that is also a Forward.";

	@Test
	void jackGarlandBarsMonstersThatBecameForwards() {
		MainWindow mw = new MainWindow();
		CardData garland = makeIcbCard("Jack Garland", "Lightning", "Forward", GARLAND_NO_MONSTER_BLOCK);

		assertTrue(mw.barsMonsterForwardBlockers(garland));
		assertFalse(mw.barsMonsterForwardBlockers(makeForward("Plain", "Fire", 3, 7000)));

		mw.lostAbilitiesCards.add(garland);
		assertFalse(mw.barsMonsterForwardBlockers(garland), "the restriction goes with the abilities");
	}

	@Test
	void aMonsterActingAsAForwardCannotBeDeclaredAgainstJackGarland() {
		MainWindow mw = new MainWindow();
		CardData monster = makeForwardWithTraits("Chocobo", "Wind", 5000, Set.of());
		mw.placeCardInMonsterZone(monster);
		mw.p1MonsterTempForwardPower.put(monster, 5000);   // an effect made it a Forward
		mw.placeP2CardInForwardZone(makeForward("Plain", "Fire", 3, 7000));

		// P2 attacks with an ordinary Forward: the Monster may block.
		mw.pendingP2Attacker    = mw.p2ForwardCards.get(0);
		mw.pendingP2AttackerIdx = 0;
		assertTrue(mw.isMonsterBlockSelectable(0), "nothing bars it yet");

		// Swap the attacker for Jack Garland and the same Monster is out.
		CardData garland = makeIcbCard("Jack Garland", "Lightning", "Forward", GARLAND_NO_MONSTER_BLOCK);
		mw.p2ForwardCards.set(0, garland);
		mw.pendingP2Attacker = garland;

		assertFalse(mw.isMonsterBlockSelectable(0),
				"the Monster is a Forward only by grant, which is exactly what the text names");
	}

	// =========================================================================================
	// 29-124R Auron (LB 1): "If you control a Category X Forward other than Auron, Auron gains
	// +2000 power and Brave."
	//
	// Auron is Category X himself, which is what "other than Auron" is there for.
	// =========================================================================================

	private static final String AURON_29_124R = "Limit Break -- 1[[br]]If you control a Category X Forward other "
			+ "than Auron, Auron gains +2000 power and Brave.";

	private static CardData makeAuron() {
		return makeLbForward("Auron", "Water", 4, 8000, 1, "Guardian", "THEATRHYTHM", "X", AURON_29_124R);
	}

	@Test
	void auronGainsPowerAndBraveBesideAnotherCategoryXForward() {
		MainWindow mw = new MainWindow();
		CardData auron = makeAuron();
		placeP2Forward(mw, auron);
		placeP2Backup(mw, makePlainCard("Yuna", "Water", "Backup", 2, 0, null, "X"));
		placeP2Forward(mw, makePlainCard("Vivi", "Fire", "Forward", 2, 5000, null, "IX"));
		int a = mw.p2ForwardCards.indexOf(auron);

		assertEquals(8000, mw.effectiveP2ForwardPower(a), "himself, a Backup and another Category are not it");
		assertFalse(mw.effectiveP2HasTrait(a, CardData.Trait.BRAVE));
		placeP2Forward(mw, makePlainCard("Tidus", "Water", "Forward", 3, 7000, null, "X"));
		assertEquals(10000, mw.effectiveP2ForwardPower(a));
		assertTrue(mw.effectiveP2HasTrait(a, CardData.Trait.BRAVE));
	}

	@Test
	void aCategoryXForwardTheOpponentControlsDoesNotCountForAuron() {
		MainWindow mw = new MainWindow();
		CardData auron = makeAuron();
		placeP2Forward(mw, auron);
		placeP1Forward(mw, makePlainCard("Tidus", "Water", "Forward", 3, 7000, null, "X"));
		int a = mw.p2ForwardCards.indexOf(auron);

		assertEquals(8000, mw.effectiveP2ForwardPower(a), "you control");
		assertFalse(mw.effectiveP2HasTrait(a, CardData.Trait.BRAVE));
	}
}
