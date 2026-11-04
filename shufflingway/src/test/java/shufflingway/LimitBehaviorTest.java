package shufflingway;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static shufflingway.TestCards.*;

import java.util.Arrays;
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
		return MainWindow.buildCardData(new DeckCardDetail(null, name, element, cost, power, "Forward",
				true, lbCost, false, text, false, null, null, null));
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
		while (mw.gameState.getStack().size() > before) {
			StackEntry e = mw.gameState.popStack();
			Consumer<GameContext> effect = ActionResolver.parse(e.autoAbility().effectText(), e.source(), e.xValue());
			assertNotNull(effect, "resolves: " + e.autoAbility().effectText());
			GameContext ctx = mw.buildGameContext(e.isP1());
			if (e.preSelectedTargets() != null) ctx.preloadTargets(mw.liveTargets(e));
			mw.currentAbilitySource = e.source();
			mw.currentAbilitySourceIsP1 = e.isP1();
			try { effect.accept(ctx); } finally { mw.currentAbilitySource = null; }
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
		Consumer<GameContext> burst = ActionResolver.parse(cloud.actionAbilities().get(0).effectText(), cloud);
		assertNotNull(burst);

		mw.currentAbilitySource = cloud;
		mw.currentAbilitySourceIsP1 = false;
		try { burst.accept(mw.buildGameContext(false)); } finally { mw.currentAbilitySource = null; }

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
}
