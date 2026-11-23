package shufflingway;

import static org.junit.jupiter.api.Assertions.*;
import static shufflingway.TestCards.*;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import scraper.DeckDatabase.DeckCardDetail;

/**
 * The rulings of the official FFTCG FAQ, checked against the engine.
 *
 * <p>Source: <a href="https://fftcg.square-enix-games.com/na/play-article/faq">the FAQ page</a>,
 * which lists rulings card by card, newest Opus first. Sections here mostly follow the same
 * order, and every banner quotes the ruling it checks with the card's serial, so a ruling can be
 * found on the page from the banner. Cards sharing one ruling are grouped into one section (the
 * "When you do so" payoffs, the Monsters that become Forwards, the "Damage N" pairs), and the
 * last two sections collect the action-ability rulings that need the ability on the Stack and the
 * casting-cost rulings.
 *
 * <p>What is left out: cards whose behaviour is already tested in another class
 * ({@link CardBehaviorTest}, {@link SummonBehaviorTest}, {@link LimitBehaviorTest} and the rest —
 * by serial, or by their printed text where a test carries it without one); rulings that only
 * point at the general rules ("see the keywords explanations") or concern a translation; and
 * rulings with nothing observable to assert (when a choice is "made at resolution", say).
 *
 * <p>Every test is board behaviour against a real {@code new MainWindow()}. Each card is built
 * from its printed text the way a dealt deck builds it ({@link MainWindow#buildCardData}), so
 * what is under test is the engine's reading of the real card, not a hand-made stand-in; the
 * factories sit at the end of the file, one per card. Cards that make a choice sit on P2's side
 * so the AI makes it. Three traps, each of which has cost a run:
 *
 * <ul>
 *   <li><b>P1's choices open a dialog</b> that nobody answers, and the test stalls until it times
 *       out. A discard is a choice once P1 holds more cards than it must discard, so a target
 *       invalidated "in response" is broken rather than returned to P1's hand when a discard may
 *       follow. The class timeout is the guard: every test runs in well under a second.</li>
 *   <li><b>{@code p2TakeDamage} does not finish an EX Burst</b> headless — the reveal waits on an
 *       animation. Put the card in the Damage Zone and call
 *       {@code autoAbilityTriggers.triggerExBurst} as the damage path does.</li>
 *   <li><b>A printing with the generic icon is a multicard</b> ({@link #genericCard}); built as an
 *       ordinary card, a second copy on the field falls to the uniqueness rule.</li>
 * </ul>
 *
 * <p>Finding your way around: every section opens with a banner of {@code =} rules naming the
 * card, so one search prints the table of contents —
 *
 * <pre>    Grep: pattern "// ={10,}", -A 1, this file</pre>
 */
@Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class OfficialFaqBehaviorTest {

	@AfterEach
	void restoreCpuOffers() {
		CPUOptional.acceptsEveryOffer = false;
	}

	// =========================================================================================
	// Shared factories
	// =========================================================================================

	/** A card as a dealt deck builds it: every property read off its printed {@code text}. */
	private static CardData card(String name, String element, String type, int cost, int power,
			String job, String category1, String category2, boolean exBurst, String text) {
		return MainWindow.buildCardData(new DeckCardDetail(null, name, element, cost, power, type,
				false, 0, exBurst, text, false, job, category1, category2));
	}

	/** As {@link #card}, for a printing with the generic (multicard) icon: several may share a name on the field. */
	private static CardData genericCard(String name, String element, String type, int cost, int power,
			String job, String category1, String category2, boolean exBurst, String text) {
		return MainWindow.buildCardData(new DeckCardDetail(null, name, element, cost, power, type,
				false, 0, exBurst, text, true, job, category1, category2));
	}

	/** A vanilla Forward of {@code category}. */
	private static CardData categoryForward(String name, String element, int cost, int power, String category) {
		return card(name, element, "Forward", cost, power, null, category, null, false, "");
	}

	/** A vanilla Forward with a Job. */
	private static CardData jobForward(String name, String element, int cost, int power, String job) {
		return card(name, element, "Forward", cost, power, job, null, null, false, "");
	}

	/** Resolves everything on the Stack, top first, as it does once both players pass on each entry. */
	private static void resolveStack(MainWindow mw) {
		while (!mw.gameState.getStack().isEmpty()) mw.resolveTopOfStack();
	}

	/** Seats {@code c} on {@code isP1}'s Forward row without firing its enters-field abilities. */
	private static void seatForward(MainWindow mw, boolean isP1, CardData c) {
		mw.suppressAutoAbilityForNextCards = 1;
		if (isP1) placeP1Forward(mw, c);
		else placeP2Forward(mw, c);
		mw.suppressAutoAbilityForNextCards = 0;
	}

	/** Seats {@code c} in {@code isP1}'s first empty Backup slot, active, without its enters-field abilities. */
	private static void seatBackup(MainWindow mw, boolean isP1, CardData c) {
		placeBackup(mw, c, isP1);
	}

	/** P2 fields {@code c}: its enters-field abilities go on the Stack, the AI choosing for them. */
	private static void p2Arrives(MainWindow mw, CardData c) {
		if ("Backup".equals(c.type())) {
			mw.gameState.getIdentity().put(c, false);
			mw.placeP2CardInFirstBackupSlot(c);
		} else if ("Monster".equals(c.type())) {
			mw.gameState.getIdentity().put(c, false);
			mw.placeP2CardInMonsterZone(c);
		} else {
			placeP2Forward(mw, c);
		}
	}

	/** {@code n} plain cards on {@code isP1}'s deck, owners recorded. */
	private static void fillDeck(MainWindow mw, boolean isP1, int n) {
		for (int i = 0; i < n; i++) {
			CardData c = makeForward((isP1 ? "P1" : "P2") + " Deck " + i, "Water", 2, 5000);
			mw.gameState.getIdentity().put(c, isP1);
			(isP1 ? mw.gameState.getP1MainDeck() : mw.gameState.getP2MainDeck()).add(c);
		}
	}

	private static void toHand(MainWindow mw, boolean isP1, CardData c) {
		mw.gameState.getIdentity().put(c, isP1);
		(isP1 ? mw.gameState.getP1Hand() : mw.gameState.getP2Hand()).add(c);
	}

	private static void toBreakZone(MainWindow mw, boolean isP1, CardData c) {
		mw.gameState.getIdentity().put(c, isP1);
		(isP1 ? mw.gameState.getP1BreakZone() : mw.gameState.getP2BreakZone()).add(c);
	}

	private static int hand(MainWindow mw, boolean isP1) {
		return (isP1 ? mw.gameState.getP1Hand() : mw.gameState.getP2Hand()).size();
	}

	private static int crystals(MainWindow mw, boolean isP1) {
		return isP1 ? mw.gameState.getP1Crystals() : mw.gameState.getP2Crystals();
	}

	private static int damageZone(MainWindow mw, boolean isP1) {
		return (isP1 ? mw.gameState.getP1DamageZone() : mw.gameState.getP2DamageZone()).size();
	}

	/** A one-off effect {@code isP1} resolves from a nameless Summon, {@code targets} chosen in advance. */
	private static void respond(MainWindow mw, boolean isP1, String text, ForwardTarget... targets) {
		GameContext ctx = mw.buildGameContext(isP1);
		if (targets.length > 0) ctx.preloadTargets(List.of(targets));
		CardData summon = makeSummon("Response", "Wind", 1, text);
		mw.gameState.getIdentity().put(summon, isP1);
		Consumer<GameContext> fn = ActionResolver.parse(text, summon);
		assertNotNull(fn, text);
		fn.accept(ctx);
	}

	/** In the window before the top entry resolves, {@code c}'s controller returns it to hand. */
	private static void bounce(MainWindow mw, CardData c) {
		boolean isP1 = mw.p1ForwardCards.contains(c);
		List<CardData> row = isP1 ? mw.p1ForwardCards : mw.p2ForwardCards;
		respond(mw, isP1, "Choose 1 Forward you control. Return it to its owner's hand.", fwd(isP1, row.indexOf(c)));
	}

	/** In the window before the top entry resolves, {@code c} leaves its owner's Break Zone for the game. */
	private static void exileFromBreakZone(MainWindow mw, boolean ownerIsP1, CardData c) {
		assertTrue((ownerIsP1 ? mw.gameState.getP1BreakZone() : mw.gameState.getP2BreakZone()).remove(c));
		mw.gameState.addToPermanentRfp(c);
	}

	/** Resolves {@code source}'s auto ability {@code index} from {@code isP1}'s seat, as the Stack would. */
	private static void resolveAutoAs(MainWindow mw, boolean isP1, CardData source, int index) {
		String effect = source.autoAbilities().get(index).effectText();
		Consumer<GameContext> fn = ActionResolver.parse(effect, source);
		assertNotNull(fn, effect);
		mw.currentAbilitySource = source;
		mw.currentAbilitySourceIsP1 = isP1;
		try {
			fn.accept(mw.buildGameContext(isP1));
		} finally {
			mw.currentAbilitySource = null;
		}
	}

	/** Resolves {@code source}'s action ability {@code index} from {@code isP1}'s seat, its cost already paid. */
	private static void resolveActionAs(MainWindow mw, boolean isP1, CardData source, int index) {
		mw.currentAbilitySource = source;
		mw.currentAbilitySourceIsP1 = isP1;
		try {
			ActionResolver.resolve(source.actionAbilities().get(index), source, mw.gameState,
					mw.buildGameContext(isP1), 0);
		} finally {
			mw.currentAbilitySource = null;
		}
	}

	// =========================================================================================
	// OPUS 30
	// =========================================================================================

	// =========================================================================================
	// 30-021H Gestahlian Empire Cid: "If the removed card is a multi-element card, it is counted as
	// a card with multiple elements."
	// =========================================================================================

	/** P2's Cid removes exactly {@code cards} from its Break Zone; whether P1 then discarded its one card. */
	private static boolean cidMakesOpponentDiscard(CardData... cards) {
		MainWindow mw = new MainWindow();
		CardData cid = gestahlianEmpireCid_30_021H();
		seatBackup(mw, false, cid);
		for (CardData c : cards) toBreakZone(mw, false, c);
		toHand(mw, true, makeForward("Only Card", "Fire", 2, 5000));
		resolveActionAs(mw, false, cid, 0);
		assertEquals(cards.length, mw.gameState.getP2RemovedFromGame().size(), "all three removed");
		return hand(mw, true) == 0;
	}

	@Test
	void cidCountsBothElementsOfAMultiElementCard() {
		assertTrue(cidMakesOpponentDiscard(
				makeForward("Fire Ice", "Fire/Ice", 3, 7000),
				makeForward("Wind A", "Wind", 2, 5000),
				makeForward("Wind B", "Wind", 2, 5000)),
				"Fire, Ice and Wind are three Elements");
		assertFalse(cidMakesOpponentDiscard(
				makeForward("Fire Ice", "Fire/Ice", 3, 7000),
				makeForward("Fire A", "Fire", 2, 5000),
				makeForward("Ice A", "Ice", 2, 5000)),
				"Fire and Ice are only two");
	}

	// =========================================================================================
	// 30-092R Steiner: "You cannot draw a card with a Multi-Element Forward including Water."
	// =========================================================================================

	/** P2 controls {@code ally}, then Steiner arrives; how many cards P2 drew. */
	private static int steinerDrawsBeside(CardData ally) {
		MainWindow mw = new MainWindow();
		fillDeck(mw, false, 3);
		seatForward(mw, false, ally);
		p2Arrives(mw, steiner_30_092R());
		resolveStack(mw);
		return hand(mw, false);
	}

	@Test
	void steinerDoesNotDrawForAMultiElementForwardIncludingWater() {
		assertEquals(0, steinerDrawsBeside(makeForward("Water Fire", "Fire/Water", 3, 7000)));
		assertEquals(1, steinerDrawsBeside(makeForward("Fire", "Fire", 3, 7000)), "a Fire Forward does");
	}

	// =========================================================================================
	// 30-101H Wakka: "You can draw a card even if your opponent does not select a Forward."
	// =========================================================================================

	/** Wakka and {@code others} more Category X Forwards on P2's field, P1 with none; P2's draw at end of turn. */
	private static int wakkaDrawsWith(int others) {
		MainWindow mw = new MainWindow();
		fillDeck(mw, false, 3);
		CardData wakka = wakka_30_101H();
		seatForward(mw, false, wakka);
		for (int i = 0; i < others; i++) seatForward(mw, false, categoryForward("X " + i, "Water", 2, 5000, "X"));
		resolveAutoAs(mw, false, wakka, 1);
		return hand(mw, false);
	}

	@Test
	void wakkaDrawsEvenWhenTheOpponentHasNoForwardToSelect() {
		assertEquals(1, wakkaDrawsWith(5), "6 Category X Forwards; nothing to select, the draw still happens");
		assertEquals(0, wakkaDrawsWith(4), "5 is short of the condition");
	}

	// =========================================================================================
	// 30-107H Terra: "Even if you control 2 or more Category VI characters, you draw and discard
	// only one card each."
	// =========================================================================================

	@Test
	void terraDrawsOnlyOneCardWhateverTheCategoryViCount() {
		MainWindow mw = new MainWindow();
		fillDeck(mw, false, 5);
		CardData terra = terra_30_107H();
		seatForward(mw, false, terra);
		seatForward(mw, false, categoryForward("VI A", "Fire", 2, 5000, "VI"));
		seatForward(mw, false, categoryForward("VI B", "Fire", 2, 5000, "VI"));
		seatForward(mw, true, makeForward("Big", "Water", 6, 12000));

		resolveAutoAs(mw, false, terra, 0);

		assertEquals(3000, mw.p1ForwardDamage.get(0), "1000 for each of 3 Category VI Characters");
		assertEquals(1, hand(mw, false), "but the draw is not multiplied");
	}

	@Test
	void terraMakesTheOpponentDiscardOnlyOneCardWhateverTheCategoryViCount() {
		// On P1's side: P2 then holds several cards, and the AI discards without a dialog. Its one
		// Forward is the only legal choice, so P1's selection makes itself.
		MainWindow mw = new MainWindow();
		CardData terra = terra_30_107H();
		seatForward(mw, true, terra);
		seatForward(mw, true, categoryForward("VI A", "Fire", 2, 5000, "VI"));
		seatForward(mw, true, categoryForward("VI B", "Fire", 2, 5000, "VI"));
		seatForward(mw, false, makeForward("Big", "Water", 6, 12000));
		for (int i = 0; i < 4; i++) toHand(mw, false, makeForward("P2 Hand " + i, "Fire", 2, 5000));

		resolveAutoAs(mw, true, terra, 1);

		assertEquals(3000, mw.p2ForwardDamage.get(0));
		assertEquals(3, hand(mw, false), "one discard");
	}

	// =========================================================================================
	// 30-110H The Emperor: "Your opponent will discard a card even if you control no Backups."
	// =========================================================================================

	@Test
	void theEmperorMakesTheOpponentDiscardWithNoBackupsToActivate() {
		MainWindow mw = new MainWindow();
		toHand(mw, true, makeForward("Only Card", "Fire", 2, 5000));
		p2Arrives(mw, theEmperor_30_110H());
		resolveStack(mw);
		assertEquals(0, hand(mw, true));
	}

	// =========================================================================================
	// 30-116H Yiazmat: "If you do not choose either a Forward or a Monster, Yiazmat will not deal
	// damage to your opponent."
	// =========================================================================================

	/** P2's Yiazmat arrives with 5 Category MBM cards to remove; P1 fields {@code p1Forward} if not null. */
	private static MainWindow yiazmatArrivesFacing(CardData p1Forward) {
		CPUOptional.acceptsEveryOffer = true;
		MainWindow mw = new MainWindow();
		fillDeck(mw, true, 3);
		for (int i = 0; i < 5; i++) toBreakZone(mw, false, categoryForward("MBM " + i, "Water", 2, 5000, "MBM"));
		if (p1Forward != null) seatForward(mw, true, p1Forward);
		p2Arrives(mw, yiazmat_30_116H());
		resolveStack(mw);
		return mw;
	}

	@Test
	void yiazmatDealsNoDamageWhenItChoseNothing() {
		MainWindow mw = yiazmatArrivesFacing(null);
		assertEquals(5, mw.gameState.getP2RemovedFromGame().size(), "the price was paid");
		assertEquals(0, damageZone(mw, true), "nothing chosen, no point of damage");
	}

	@Test
	void yiazmatDealsItsPointOfDamageWhenItChoseAForward() {
		MainWindow mw = yiazmatArrivesFacing(makeForward("Theirs", "Fire", 3, 7000));
		assertEquals(1, mw.gameState.getP1BreakZone().size(), "the Forward broken");
		assertEquals(1, damageZone(mw, true));
	}

	// =========================================================================================
	// OPUS 28
	// =========================================================================================

	// =========================================================================================
	// 28-007H Cloud: "If the chosen Category VII character becomes invalid during resolution, you
	// do not gain a crystal."
	// =========================================================================================

	@Test
	void cloudGainsNoCrystalWhenTheChosenCharacterLeavesTheBreakZone() {
		MainWindow mw = new MainWindow();
		CardData tifa = categoryForward("Tifa", "Earth", 3, 7000, "VII");
		toBreakZone(mw, false, tifa);
		p2Arrives(mw, cloud_28_007H());
		int before = crystals(mw, false);

		exileFromBreakZone(mw, false, tifa);
		resolveStack(mw);

		assertEquals(before, crystals(mw, false));
	}

	@Test
	void cloudGainsTheCrystalWhenTheChosenCharacterIsStillThere() {
		MainWindow mw = new MainWindow();
		CardData tifa = categoryForward("Tifa", "Earth", 3, 7000, "VII");
		toBreakZone(mw, false, tifa);
		p2Arrives(mw, cloud_28_007H());
		int before = crystals(mw, false);
		resolveStack(mw);
		assertTrue(mw.gameState.getP2Hand().contains(tifa));
		assertEquals(before + 1, crystals(mw, false));
	}

	// =========================================================================================
	// 28-016L Vivi: "Damage allocation is determined at the point when auto abilities are applied
	// to the stack." / "Even if you are not controlling any category IX Forwards other than Vivi
	// when resolving auto abilities, you deal 10000 damage."
	// =========================================================================================

	/** Vivi arrives on P2's side beside a Category IX ally, which P2 bounces first if {@code allyLeaves}. */
	private static int viviDeals(boolean allyLeaves) {
		MainWindow mw = new MainWindow();
		seatForward(mw, true, makeForward("Big", "Water", 7, 15000));
		CardData zidane = categoryForward("Zidane", "Wind", 3, 7000, "IX");
		seatForward(mw, false, zidane);
		p2Arrives(mw, vivi_28_016L());
		if (allyLeaves) bounce(mw, zidane);
		resolveStack(mw);
		return mw.p1ForwardDamage.get(0);
	}

	@Test
	void viviDividesTenThousandBesideACategoryIxAlly() {
		assertEquals(10000, viviDeals(false));
	}

	@Test
	void viviStillDividesTenThousandWhenHerCategoryIxAllyLeavesFirst() {
		assertEquals(10000, viviDeals(true), "the allocation was fixed as the ability went on the Stack");
	}

	// =========================================================================================
	// 28-023C Kam'lanaut: "If the chosen Forward becomes invalid during resolution, you do not gain
	// a crystal."
	// =========================================================================================

	/** P1 fields {@code target}; P2's {@code arriving} chooses it; P1 bounces it first if {@code bounce}. Returns P2's crystal gain. */
	private static int crystalsGainedAgainst(CardData arriving, CardData target, boolean bounce) {
		MainWindow mw = new MainWindow();
		seatForward(mw, true, target);
		p2Arrives(mw, arriving);
		int before = crystals(mw, false);
		if (bounce) bounce(mw, target);
		resolveStack(mw);
		return crystals(mw, false) - before;
	}

	@Test
	void kamLanautGainsNoCrystalWhenTheChosenForwardLeaves() {
		assertEquals(1, crystalsGainedAgainst(kamLanaut_28_023C(), makeForward("Theirs", "Fire", 3, 7000), false));
		assertEquals(0, crystalsGainedAgainst(kamLanaut_28_023C(), makeForward("Theirs", "Fire", 3, 7000), true));
	}

	// =========================================================================================
	// 28-027C The Emperor: "If the chosen Forward becomes invalid during resolution, your opponent
	// does not discard a card."
	// =========================================================================================

	@Test
	void theEmperorMakesNoDiscardWhenTheChosenForwardLeaves() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Fire", 3, 7000);
		seatForward(mw, true, theirs);
		mw.p1ForwardStates.set(0, CardState.DULL);
		CardData kept = makeForward("Kept", "Fire", 2, 5000);
		toHand(mw, true, kept);
		p2Arrives(mw, theEmperor_28_027C());

		respond(mw, true, "Choose 1 Forward you control. Break it.", fwd(true, 0));
		resolveStack(mw);

		assertTrue(mw.gameState.getP1Hand().contains(kept), "no discard");
	}

	@Test
	void theEmperorReturnsTheForwardAndTheOpponentDiscards() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Fire", 3, 7000);
		seatForward(mw, true, theirs);
		mw.p1ForwardStates.set(0, CardState.DULL);
		p2Arrives(mw, theEmperor_28_027C());
		resolveStack(mw);
		assertTrue(mw.p1ForwardCards.isEmpty());
		assertTrue(mw.gameState.getP1BreakZone().contains(theirs), "returned to hand, then the only card to discard");
	}

	// =========================================================================================
	// 28-030H Shuyin: "If the chosen Forward becomes invalid during resolution, Shuyin does not
	// removed from the game."
	// =========================================================================================

	@Test
	void shuyinStaysWhenTheChosenForwardLeaves() {
		// P1 holds no cards, so the AI takes the first option.
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Fire", 5, 9000);
		seatForward(mw, true, theirs);
		CardData shuyin = shuyin_28_030H();
		p2Arrives(mw, shuyin);

		bounce(mw, theirs);
		resolveStack(mw);

		assertTrue(mw.p2ForwardCards.contains(shuyin), "Shuyin is not removed alone");
	}

	@Test
	void shuyinRemovesTheChosenForwardAndHimself() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Fire", 5, 9000);
		seatForward(mw, true, theirs);
		CardData shuyin = shuyin_28_030H();
		p2Arrives(mw, shuyin);
		resolveStack(mw);
		assertTrue(mw.gameState.getP1RemovedFromGame().contains(theirs));
		assertTrue(mw.gameState.getP2RemovedFromGame().contains(shuyin));
	}

	// =========================================================================================
	// 28-036R Yazoo / 28-038R Loz: "If the chosen Job Remnant becomes invalid during resolution,
	// you do not gain a crystal."
	// =========================================================================================

	/** P2's {@code remnant} arrives over a Job Remnant in its Break Zone, which leaves first if {@code exile}. */
	private static int remnantCrystals(CardData remnant, boolean exile) {
		MainWindow mw = new MainWindow();
		CardData kadaj = jobForward("Kadaj", "Ice", 3, 7000, "Remnant");
		toBreakZone(mw, false, kadaj);
		p2Arrives(mw, remnant);
		int before = crystals(mw, false);
		if (exile) exileFromBreakZone(mw, false, kadaj);
		resolveStack(mw);
		return crystals(mw, false) - before;
	}

	@Test
	void yazooGainsNoCrystalsWhenTheChosenRemnantLeaves() {
		assertEquals(2, remnantCrystals(yazoo_28_036R(), false));
		assertEquals(0, remnantCrystals(yazoo_28_036R(), true));
	}

	@Test
	void lozGainsNoCrystalsWhenTheChosenRemnantLeaves() {
		assertEquals(2, remnantCrystals(loz_28_038R(), false));
		assertEquals(0, remnantCrystals(loz_28_038R(), true));
	}

	// =========================================================================================
	// 28-054L Yuffie: "If you cannot choose two cards from your opponent's Break Zone, or if both
	// chosen cards become invalid targets during resolution, you do not gain a crystal."
	// =========================================================================================

	/** P2's Yuffie arrives over {@code inP1Bz} cards in P1's Break Zone; P2's crystal gain. */
	private static int yuffieCrystals(int inP1Bz, boolean exileBoth) {
		MainWindow mw = new MainWindow();
		for (int i = 0; i < inP1Bz; i++) toBreakZone(mw, true, makeForward("BZ " + i, "Fire", 2, 5000));
		p2Arrives(mw, yuffie_28_054L());
		int before = crystals(mw, false);
		if (exileBoth)
			for (CardData c : List.copyOf(mw.gameState.getP1BreakZone())) exileFromBreakZone(mw, true, c);
		resolveStack(mw);
		return crystals(mw, false) - before;
	}

	@Test
	void yuffieGainsCrystalsForTwoChosenCards() {
		assertEquals(2, yuffieCrystals(2, false));
	}

	@Test
	void yuffieGainsNoCrystalsWhenSheCannotChooseTwoCards() {
		assertEquals(0, yuffieCrystals(1, false));
	}

	@Test
	void yuffieGainsNoCrystalsWhenBothChosenCardsLeave() {
		assertEquals(0, yuffieCrystals(2, true));
	}

	// =========================================================================================
	// 28-062C Vincent: "If the chosen Forward becomes invalid during resolution, you do not gain a
	// crystal."
	// =========================================================================================

	/** As {@link #crystalsGainedAgainst}, the target dull. */
	private static int crystalsGainedAgainstDull(CardData arriving, boolean bounce) {
		MainWindow mw = new MainWindow();
		CardData target = makeForward("Theirs", "Fire", 3, 7000);
		seatForward(mw, true, target);
		mw.p1ForwardStates.set(0, CardState.DULL);
		p2Arrives(mw, arriving);
		int before = crystals(mw, false);
		if (bounce) bounce(mw, target);
		resolveStack(mw);
		return crystals(mw, false) - before;
	}

	@Test
	void vincentGainsNoCrystalWhenTheChosenForwardLeaves() {
		assertEquals(1, crystalsGainedAgainstDull(vincent_28_062C(), false));
		assertEquals(0, crystalsGainedAgainstDull(vincent_28_062C(), true));
	}

	// =========================================================================================
	// 28-069L Prishe: "If either Prishe or the chosen Forward is no longer on the field when
	// resolving an auto ability, the remaining Forward takes no damage."
	// =========================================================================================

	@Test
	void prisheAndTheChosenForwardDealEachOtherTheirPower() {
		MainWindow mw = new MainWindow();
		seatForward(mw, true, makeForward("Theirs", "Fire", 5, 12000));
		CardData prishe = prishe_28_069L();
		p2Arrives(mw, prishe);
		mw.p2ForwardPowerBoost.set(0, 10000);   // 19000: she survives the 12000
		resolveStack(mw);
		assertEquals(12000, mw.p2ForwardDamage.get(mw.p2ForwardCards.indexOf(prishe)));
		assertTrue(mw.gameState.getP1BreakZone().size() == 1, "19000 to the 12000 Forward");
	}

	@Test
	void prisheTakesNoDamageWhenTheChosenForwardLeaves() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Fire", 5, 7000);
		seatForward(mw, true, theirs);
		CardData prishe = prishe_28_069L();
		p2Arrives(mw, prishe);

		bounce(mw, theirs);
		resolveStack(mw);

		assertEquals(0, mw.p2ForwardDamage.get(mw.p2ForwardCards.indexOf(prishe)));
	}

	@Test
	void theChosenForwardTakesNoDamageWhenPrisheLeaves() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Fire", 5, 12000);
		seatForward(mw, true, theirs);
		CardData prishe = prishe_28_069L();
		p2Arrives(mw, prishe);

		bounce(mw, prishe);
		resolveStack(mw);

		assertEquals(0, mw.p1ForwardDamage.get(0));
	}

	// =========================================================================================
	// 28-074C Rikku: "If the chosen Forward becomes invalid during resolution, you do not receive
	// damage."
	// =========================================================================================

	/** P2's Rikku arrives over a Category X Forward in its Break Zone, which leaves first if {@code exile}. */
	private static MainWindow rikkuArrives(boolean exile) {
		CPUOptional.acceptsEveryOffer = true;
		MainWindow mw = new MainWindow();
		fillDeck(mw, false, 3);
		CardData paine = categoryForward("Paine", "Earth", 2, 5000, "X");
		toBreakZone(mw, false, paine);
		p2Arrives(mw, rikku_28_074C());
		if (exile) exileFromBreakZone(mw, false, paine);
		resolveStack(mw);
		return mw;
	}

	@Test
	void rikkuDealsYouNoDamageWhenTheChosenForwardLeaves() {
		assertEquals(0, damageZone(rikkuArrives(true), false));
	}

	@Test
	void rikkuPlaysTheChosenForwardAndDealsYouOnePoint() {
		MainWindow mw = rikkuArrives(false);
		assertEquals(2, mw.p2ForwardCards.size(), "Rikku and Paine");
		assertEquals(1, damageZone(mw, false));
	}

	// =========================================================================================
	// 28-082R Cait Sith: "If the chosen Forward becomes invalid during resolution, you do not draw
	// a card."
	// =========================================================================================

	private static int caitSithDraws(boolean bounce) {
		MainWindow mw = new MainWindow();
		fillDeck(mw, false, 3);
		CardData theirs = makeForward("Theirs", "Fire", 3, 7000);
		seatForward(mw, true, theirs);
		p2Arrives(mw, caitSith_28_082R());
		if (bounce) bounce(mw, theirs);
		resolveStack(mw);
		return hand(mw, false);
	}

	@Test
	void caitSithDrawsNothingWhenTheChosenForwardLeaves() {
		assertEquals(1, caitSithDraws(false));
		assertEquals(0, caitSithDraws(true));
	}

	// =========================================================================================
	// 28-111H Yuna: "Even if a multi-element Forward including Water, Yuna's ability will not
	// trigger."
	// =========================================================================================

	/** Yuna on P2's field, a P1 Forward to aim at; {@code arriving} enters P2's field. Whether Yuna triggered. */
	private static boolean yunaTriggersOn(CardData arriving) {
		MainWindow mw = new MainWindow();
		seatForward(mw, true, makeForward("Theirs", "Fire", 3, 7000));
		seatForward(mw, false, yuna_28_111H());
		placeP2Forward(mw, arriving);
		return !mw.gameState.getStack().isEmpty();
	}

	@Test
	void yunaDoesNotTriggerOnAMultiElementForwardIncludingWater() {
		assertFalse(yunaTriggersOn(makeForward("Water Fire", "Fire/Water", 3, 7000)));
	}

	@Test
	void yunaTriggersOnAForwardOfAnotherElement() {
		assertTrue(yunaTriggersOn(makeForward("Fire", "Fire", 3, 7000)));
	}

	// =========================================================================================
	// OPUS 27
	// =========================================================================================

	// =========================================================================================
	// 27-012H Zell: "The division of damage is decided at the point this ability is placed on the
	// Stack." / "If the 9000 damage is divided but there are no longer [four or more] category VIII
	// Characters at the point when this effect is resolved, the divided up 9000 damage is still
	// inflicted."
	// =========================================================================================

	/** Zell arrives on P2's side with 3 Category VIII allies (4 with him); P2 bounces one first if {@code oneLeaves}. */
	private static int zellDeals(boolean oneLeaves) {
		MainWindow mw = new MainWindow();
		seatForward(mw, true, makeForward("Big", "Water", 7, 15000));
		CardData squall = categoryForward("Squall", "Ice", 3, 7000, "VIII");
		seatForward(mw, false, squall);
		seatForward(mw, false, categoryForward("Rinoa", "Ice", 3, 7000, "VIII"));
		seatForward(mw, false, categoryForward("Quistis", "Ice", 3, 7000, "VIII"));
		p2Arrives(mw, zell_27_012H());
		if (oneLeaves) bounce(mw, squall);
		resolveStack(mw);
		return mw.p1ForwardDamage.get(0);
	}

	@Test
	void zellDividesNineThousandWithFourCategoryViiiCharacters() {
		assertEquals(9000, zellDeals(false));
	}

	@Test
	void zellStillDividesNineThousandWhenTheCategoryViiiCountDropsFirst() {
		assertEquals(9000, zellDeals(true), "the division was decided as the ability went on the Stack");
	}

	// =========================================================================================
	// "When you do so" — the price is paid as the ability resolves, and the payoff then goes on
	// the Stack as an auto ability of its own (rule 11.8.17). The FAQ restates this for every card
	// carrying the wording; these helpers check it the same way for each.
	// =========================================================================================

	/** Resolves entries until a "When you do so" payoff is on top, and returns it; fails if none appears. */
	private static StackEntry resolveToPayoff(MainWindow mw) {
		while (!mw.gameState.getStack().isEmpty()) {
			StackEntry top = mw.gameState.peekStack();
			if (top.autoAbility() != null && top.autoAbility().isWhenYouDoSo()) return top;
			mw.resolveTopOfStack();
		}
		fail("no \"When you do so\" payoff went on the Stack");
		return null;
	}

	/**
	 * With {@code trigger} just fired: nothing paid yet; once the ability resolves the price is
	 * paid and the payoff sits on the Stack undone; resolving that entry does it.
	 */
	private static void assertPaysThenStacksThePayoff(MainWindow mw, Runnable trigger,
			BooleanSupplier pricePaid, BooleanSupplier payoffDone) {
		trigger.run();
		assertFalse(pricePaid.getAsBoolean(), "nothing is paid as the ability goes on the Stack");
		resolveToPayoff(mw);
		assertTrue(pricePaid.getAsBoolean(), "the price is paid as the ability resolves");
		assertFalse(payoffDone.getAsBoolean(), "the payoff waits on the Stack as its own entry");
		mw.resolveTopOfStack();
		assertTrue(payoffDone.getAsBoolean(), "and is done when that entry resolves");
		resolveStack(mw);
	}

	/** Seats Monster {@code c} on {@code isP1}'s field without firing its enters-field abilities. */
	private static void seatMonster(MainWindow mw, boolean isP1, CardData c) {
		mw.suppressAutoAbilityForNextCards = 1;
		if (isP1) placeP1Monster(mw, c);
		else {
			mw.gameState.getIdentity().put(c, false);
			mw.placeP2CardInMonsterZone(c);
		}
		mw.suppressAutoAbilityForNextCards = 0;
	}

	/** P2's turn, with {@code monster} on P2's field: it acts as a Forward. */
	private static MainWindow p2TurnWithMonster(CardData monster) {
		MainWindow mw = new MainWindow();
		mw.gameState.startFirstTurn(GameState.Player.P2);
		seatMonster(mw, false, monster);
		return mw;
	}

	/** {@code isP1}'s Forward row index of {@code c}. */
	private static int slot(MainWindow mw, boolean isP1, CardData c) {
		return (isP1 ? mw.p1ForwardCards : mw.p2ForwardCards).indexOf(c);
	}

	private static int power(MainWindow mw, boolean isP1, CardData c) {
		int i = slot(mw, isP1, c);
		return isP1 ? mw.effectiveP1ForwardPower(i) : mw.effectiveP2ForwardPower(i);
	}

	// =========================================================================================
	// 27-006R Chaos Advent: "A Forward card played using this card's auto-ability loses its
	// original element and becomes a Darkness element card." / "… has both its original Job and
	// the Job: Chaos." / "If a Forward card is played using this card's auto-ability while a Light
	// or Darkness element Character is already under the player's control, both are placed in the
	// Break Zone." / "When a Forward card is placed on the field in this situation, it is counted
	// as having its original element … auto-abilities triggered when a Darkness element card is
	// placed on the field will not trigger."
	// =========================================================================================

	/** Chaos Advent on P2's field over a deck topped by {@code jecht}; P1 breaks it and everything resolves. */
	private static MainWindow chaosAdventBrokenOver(CardData jecht, CardData... alsoSeated) {
		CPUOptional.acceptsEveryOffer = true;
		MainWindow mw = new MainWindow();
		fillDeck(mw, false, 6);
		mw.gameState.getIdentity().put(jecht, false);
		mw.gameState.getP2MainDeck().addFirst(jecht);
		for (CardData c : alsoSeated) seatForward(mw, false, c);
		CardData chaos = chaosAdvent_27_006R();
		seatForward(mw, false, chaos);
		respond(mw, true, "Choose 1 Forward opponent controls. Break it.", fwd(false, slot(mw, false, chaos)));
		resolveStack(mw);
		return mw;
	}

	private static CardData sopffoKnight() {
		return card("Jecht", "Fire", "Forward", 3, 7000, "Knight", "SOPFFO", null, false, "");
	}

	@Test
	void chaosAdventsForwardBecomesDarkInsteadOfGainingDark() {
		CardData jecht = sopffoKnight();
		MainWindow mw = chaosAdventBrokenOver(jecht);
		assertTrue(mw.p2ForwardCards.contains(jecht), "played");
		assertTrue(mw.effectiveContainsElement(jecht, "Dark"));
		assertFalse(mw.effectiveContainsElement(jecht, "Fire"), "its original Element is lost, not kept beside Dark");
	}

	@Test
	void chaosAdventsForwardKeepsItsJobAndGainsJobChaos() {
		CardData jecht = sopffoKnight();
		MainWindow mw = chaosAdventBrokenOver(jecht);
		GameContext ctx = mw.buildGameContext(false);
		assertTrue(ctx.effectiveHasJob(jecht, "Chaos"));
		assertTrue(ctx.effectiveHasJob(jecht, "Knight"));
	}

	@Test
	void chaosAdventsForwardAndAnAlreadyControlledDarkCharacterBothGoToTheBreakZone() {
		CardData jecht = sopffoKnight();
		CardData dark = makeForward("Garland", "Dark", 5, 9000);
		MainWindow mw = chaosAdventBrokenOver(jecht, dark);
		assertTrue(mw.gameState.getP2BreakZone().contains(jecht));
		assertTrue(mw.gameState.getP2BreakZone().contains(dark));
	}

	@Test
	void chaosAdventsForwardDoesNotTriggerDarkForwardEntersAbilities() {
		CardData jecht = sopffoKnight();
		CardData sleipnir = sleipnir_26_091C();
		MainWindow mw = chaosAdventBrokenOver(jecht, sleipnir);
		assertTrue(mw.p2ForwardCards.contains(jecht));
		assertEquals(5000, power(mw, false, sleipnir), "it entered as a Fire Forward");
	}

	// =========================================================================================
	// 27-017R Luneth: "If this card's auto-ability reveals a Character with the same name as a
	// Character already on your field that does not also have the general unit icon, that card
	// cannot be played to the field."
	// =========================================================================================

	@Test
	void lunethCannotPlayARevealedCharacterSharingANameOnTheField() {
		MainWindow mw = new MainWindow();
		fillDeck(mw, false, 2);
		CardData arc = makeForward("Arc", "Water", 3, 6000);
		seatForward(mw, false, arc);
		CardData secondArc = makeForward("Arc", "Water", 3, 6000);
		mw.gameState.getIdentity().put(secondArc, false);
		mw.gameState.getP2MainDeck().addFirst(secondArc);

		p2Arrives(mw, luneth_27_017R());
		resolveStack(mw);

		assertTrue(mw.p2ForwardCards.contains(arc), "the Arc already there stays");
		assertFalse(mw.p2ForwardCards.contains(secondArc));
		assertFalse(mw.gameState.getP2BreakZone().contains(secondArc), "never played, so never put into the Break Zone");
	}

	// =========================================================================================
	// 27-049R Balthier: "CP are paid at the point when effects are resolved. The auto-ability to
	// select one Forward controlled by the opponent and deal it 4000 damage is then added to the
	// stack after the payment."
	// =========================================================================================

	/** {@code n} active Backups of {@code element} seated on {@code isP1}'s side. */
	private static CardData[] seatBackups(MainWindow mw, boolean isP1, String element, int n) {
		CardData[] out = new CardData[n];
		for (int i = 0; i < n; i++) seatBackup(mw, isP1, out[i] = makeBackup(element + " Backup " + i, element, 2));
		return out;
	}

	private static boolean allDull(MainWindow mw, boolean isP1, CardData... backups) {
		CardData[] row = isP1 ? mw.p1BackupCards : mw.p2BackupCards;
		CardState[] states = isP1 ? mw.p1BackupStates : mw.p2BackupStates;
		for (CardData b : backups)
			for (int i = 0; i < row.length; i++)
				if (row[i] == b && states[i] != CardState.DULL) return false;
		return true;
	}

	@Test
	void balthierPaysAsItResolvesAndThenStacksItsSecondHit() {
		CPUOptional.acceptsEveryOffer = true;
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Fire", 5, 12000);
		seatForward(mw, true, theirs);
		CardData[] wind = seatBackups(mw, false, "Wind", 3);

		p2Arrives(mw, balthier_27_049R());
		assertFalse(allDull(mw, false, wind), "nothing is paid as the ability goes on the Stack");
		resolveToPayoff(mw);
		assertTrue(allDull(mw, false, wind), "《Wind》《Wind》《1》 paid as it resolved");
		int before = mw.p1ForwardDamage.get(0);
		mw.resolveTopOfStack();
		assertEquals(before + 4000, mw.p1ForwardDamage.get(0), "the payoff's own 4000");
		resolveStack(mw);
		assertEquals(8000, mw.p1ForwardDamage.get(0), "both abilities' 4000");
	}

	// =========================================================================================
	// 27-074H Adel (VIII): "The Job: Witch card in the Break Zone is removed from the game at the
	// point when abilities are resolved. The auto-ability to enhance Adel (VIII) is then added to
	// the stack after the card is removed."
	// =========================================================================================

	@Test
	void adelRemovesTheWitchAsItResolvesAndThenStacksHerBoost() {
		CPUOptional.acceptsEveryOffer = true;
		MainWindow mw = new MainWindow();
		CardData edea = jobForward("Edea", "Ice", 3, 7000, "Witch");
		toBreakZone(mw, false, edea);
		CardData adel = adelViii_27_074H();
		assertPaysThenStacksThePayoff(mw, () -> p2Arrives(mw, adel),
				() -> mw.gameState.getP2RemovedFromGame().contains(edea),
				() -> power(mw, false, adel) == 9000);
	}

	// =========================================================================================
	// 27-106C Mog (VI): "Cards in the Break Zone are removed from the game at the point when
	// effects are resolved. The auto-ability to draw one card is then added to the stack after
	// those cards have been removed."
	// =========================================================================================

	@Test
	void mogRemovesFiveAsItResolvesAndThenStacksTheDraw() {
		CPUOptional.acceptsEveryOffer = true;
		MainWindow mw = new MainWindow();
		fillDeck(mw, false, 3);
		for (int i = 0; i < 5; i++) toBreakZone(mw, false, makeForward("BZ " + i, "Water", 2, 5000));
		assertPaysThenStacksThePayoff(mw, () -> p2Arrives(mw, mogVi_27_106C()),
				() -> mw.gameState.getP2BreakZone().isEmpty(),
				() -> hand(mw, false) == 1);
	}

	// =========================================================================================
	// OPUS 26
	// =========================================================================================

	// =========================================================================================
	// Opus 26 Monsters that "also become a Forward" during their controller's turn with "When X
	// deals damage to your opponent, put X into the Break Zone. When you do so, …" — 26-019C
	// Behemoth, 26-028C Cripshay, 26-056C Gorger, 26-080C Mandragora, 26-090C Xiphos and 26-116C
	// Malboro: "X is placed in the Break Zone at the point when abilities are resolved. The
	// auto-ability … is then added to the Stack after X has been placed."
	// =========================================================================================

	/** On P2's turn, {@code monster} deals P1 damage; its price and payoff are checked as for any "When you do so". */
	private static void assertMonsterHitPaysThenStacks(MainWindow mw, CardData monster, BooleanSupplier payoffDone) {
		assertPaysThenStacksThePayoff(mw,
				() -> mw.autoAbilityTriggers.triggerAutoAbilitiesForDealsDamageToOpponent(monster, false),
				() -> mw.gameState.getP2BreakZone().contains(monster), payoffDone);
	}

	@Test
	void behemothGoesToTheBreakZoneThenStacksItsDamage() {
		CardData behemoth = behemoth_26_019C();
		MainWindow mw = p2TurnWithMonster(behemoth);
		CardData theirs = makeForward("Theirs", "Fire", 3, 7000);
		seatForward(mw, true, theirs);
		assertMonsterHitPaysThenStacks(mw, behemoth, () -> mw.gameState.getP1BreakZone().contains(theirs));
	}

	@Test
	void cripshayGoesToTheBreakZoneThenStacksTheDiscard() {
		CardData cripshay = cripshay_26_028C();
		MainWindow mw = p2TurnWithMonster(cripshay);
		toHand(mw, true, makeForward("A", "Fire", 2, 5000));
		toHand(mw, true, makeForward("B", "Fire", 2, 5000));
		assertMonsterHitPaysThenStacks(mw, cripshay, () -> hand(mw, true) == 0);
	}

	@Test
	void gorgerGoesToTheBreakZoneThenStacksItsDraw() {
		CardData gorger = gorger_26_056C();
		MainWindow mw = p2TurnWithMonster(gorger);
		fillDeck(mw, false, 3);
		assertMonsterHitPaysThenStacks(mw, gorger, () -> hand(mw, false) == 1);
	}

	@Test
	void mandragoraGoesToTheBreakZoneThenStacksTheReturnToHand() {
		CardData mandragora = mandragora_26_080C();
		MainWindow mw = p2TurnWithMonster(mandragora);
		toBreakZone(mw, false, makeForward("Lost", "Earth", 2, 5000));
		assertMonsterHitPaysThenStacks(mw, mandragora, () -> hand(mw, false) == 1);
	}

	@Test
	void xiphosGoesToTheBreakZoneThenStacksTheBreak() {
		CardData xiphos = xiphos_26_090C();
		MainWindow mw = p2TurnWithMonster(xiphos);
		CardData theirs = makeForward("Theirs", "Fire", 3, 7000);
		seatForward(mw, true, theirs);
		assertMonsterHitPaysThenStacks(mw, xiphos, () -> mw.gameState.getP1BreakZone().contains(theirs));
	}

	@Test
	void malboroGoesToTheBreakZoneThenStacksThePowerLoss() {
		CardData malboro = malboro_26_116C();
		MainWindow mw = p2TurnWithMonster(malboro);
		CardData theirs = makeForward("Theirs", "Fire", 5, 9000);
		seatForward(mw, true, theirs);
		assertMonsterHitPaysThenStacks(mw, malboro, () -> power(mw, true, theirs) == 5000);
	}

	// =========================================================================================
	// 26-023C Wol: "The two cards are discarded from the player's hand at the point when abilities
	// are resolved. The auto-ability to select one Forward and Break it is then added to the Stack
	// after the cards are discarded."
	// =========================================================================================

	@Test
	void wolDiscardsAsItResolvesAndThenStacksTheBreak() {
		CPUOptional.acceptsEveryOffer = true;
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Fire", 5, 9000);
		seatForward(mw, true, theirs);
		CardData wol = wol_26_023C();
		seatForward(mw, false, wol);
		toHand(mw, false, makeForward("A", "Ice", 2, 5000));
		toHand(mw, false, makeForward("B", "Ice", 2, 5000));
		assertPaysThenStacksThePayoff(mw, () -> mw.autoAbilityTriggers.triggerAutoAbilitiesForAttack(wol, false),
				() -> hand(mw, false) == 0,
				() -> mw.gameState.getP1BreakZone().contains(theirs));
	}

	// =========================================================================================
	// 26-026R Kuja: "The Backup is removed from the game at the point when abilities are resolved.
	// The auto-ability to make the opponent discard two cards from their hand is then added to the
	// Stack after the Backup has been removed."
	// =========================================================================================

	@Test
	void kujaRemovesTheBackupAsItResolvesAndThenStacksTheDiscard() {
		CPUOptional.acceptsEveryOffer = true;
		MainWindow mw = new MainWindow();
		CardData ice = makeBackup("Ice Backup", "Ice", 2);
		seatBackup(mw, false, ice);
		toHand(mw, true, makeForward("A", "Fire", 2, 5000));
		toHand(mw, true, makeForward("B", "Fire", 2, 5000));
		assertPaysThenStacksThePayoff(mw, () -> p2Arrives(mw, kuja_26_026R()),
				() -> mw.gameState.getP2RemovedFromGame().contains(ice),
				() -> hand(mw, true) == 0);
	}

	// =========================================================================================
	// 26-037H Halone: "If not a single character has been selected then the opponent does not
	// discard from their hand."
	// =========================================================================================

	@Test
	void haloneMakesNoDiscardWhenNoCharacterWasChosen() {
		// P1 controls nothing to choose; the AI leaves its own Characters alone.
		MainWindow mw = new MainWindow();
		CardData kept = makeForward("Kept", "Fire", 2, 5000);
		toHand(mw, true, kept);
		p2Arrives(mw, halone_26_037H());
		resolveStack(mw);
		assertTrue(mw.gameState.getP1Hand().contains(kept));
	}

	@Test
	void haloneMakesTheOpponentDiscardWhenSheChoseACharacter() {
		MainWindow mw = new MainWindow();
		seatForward(mw, true, makeForward("Theirs", "Fire", 3, 7000));
		toHand(mw, true, makeForward("Only Card", "Fire", 2, 5000));
		p2Arrives(mw, halone_26_037H());
		resolveStack(mw);
		assertEquals(0, hand(mw, true));
	}

	// =========================================================================================
	// 26-044C Sky Samurai: "If not a single Backup card has been selected then Sky Samurai's Power
	// is not increased by 2000."
	// =========================================================================================

	/** P2's Sky Samurai attacks with {@code backups} dull P2 Backups on the field; its power after. */
	private static int skySamuraiPowerAfterAttack(int backups) {
		MainWindow mw = new MainWindow();
		CardData samurai = skySamurai_26_044C();
		seatForward(mw, false, samurai);
		for (CardData b : seatBackups(mw, false, "Wind", backups))
			for (int i = 0; i < mw.p2BackupCards.length; i++)
				if (mw.p2BackupCards[i] == b) mw.p2BackupStates[i] = CardState.DULL;
		mw.autoAbilityTriggers.triggerAutoAbilitiesForAttack(samurai, false);
		resolveStack(mw);
		return power(mw, false, samurai);
	}

	@Test
	void skySamuraiGainsNoPowerWhenNoBackupWasChosen() {
		assertEquals(7000, skySamuraiPowerAfterAttack(0));
	}

	@Test
	void skySamuraiGainsTwoThousandWhenItChoseABackup() {
		assertEquals(9000, skySamuraiPowerAfterAttack(1));
	}

	// =========================================================================================
	// 26-064R Ignis: "The two Forward cards are dulled at the point when abilities are resolved.
	// The auto-ability … is then added to the Stack after the cards have been dulled." / "If any
	// Forwards selected to be dulled by this auto-ability are removed from the field before the
	// effect is resolved, refer to their power when they were last on the field."
	// =========================================================================================

	/** Ignis arrives on P2's side beside one active 5000 Forward, P1 holding a 20000 Forward. */
	private static CardData ignisArrives(MainWindow mw, CardData gladiolus) {
		CPUOptional.acceptsEveryOffer = true;
		seatForward(mw, true, makeForward("Wall", "Water", 7, 20000));
		seatForward(mw, false, gladiolus);
		CardData ignis = ignis_26_064R();
		p2Arrives(mw, ignis);
		return ignis;
	}

	@Test
	void ignisDullsAsItResolvesAndThenStacksTheDamage() {
		MainWindow mw = new MainWindow();
		CardData gladiolus = makeForward("Gladiolus", "Earth", 3, 5000);
		CardData ignis = ignisArrives(mw, gladiolus);
		assertEquals(CardState.ACTIVE, mw.p2ForwardStates.get(slot(mw, false, gladiolus)), "nothing dulled yet");
		resolveToPayoff(mw);
		assertEquals(CardState.DULL, mw.p2ForwardStates.get(slot(mw, false, gladiolus)));
		assertEquals(CardState.DULL, mw.p2ForwardStates.get(slot(mw, false, ignis)));
		assertEquals(0, mw.p1ForwardDamage.get(0), "the damage waits on the Stack");
		mw.resolveTopOfStack();
		assertEquals(12000, mw.p1ForwardDamage.get(0), "7000 + 5000");
	}

	@Test
	void ignisReadsADulledForwardThatLeftAtItsLastPower() {
		MainWindow mw = new MainWindow();
		CardData gladiolus = makeForward("Gladiolus", "Earth", 3, 5000);
		ignisArrives(mw, gladiolus);
		resolveToPayoff(mw);
		bounce(mw, gladiolus);
		mw.resolveTopOfStack();
		assertEquals(12000, mw.p1ForwardDamage.get(0), "Gladiolus counted at its last power on the field");
	}

	// =========================================================================================
	// 26-065L Vanille: "Vanille is removed from the game at the point when abilities are resolved.
	// The auto-ability to select one Category XIII Forward from your Break Zone and add it to your
	// hand is then added to the Stack after she has been removed."
	// =========================================================================================

	@Test
	void vanilleRemovesHerselfAsItResolvesAndThenStacksTheReturn() {
		CPUOptional.acceptsEveryOffer = true;
		MainWindow mw = new MainWindow();
		CardData fang = categoryForward("Fang", "Wind", 3, 7000, "XIII");
		toBreakZone(mw, false, fang);
		CardData vanille = vanille_26_065L();
		seatForward(mw, false, vanille);
		assertPaysThenStacksThePayoff(mw,
				() -> respond(mw, true, "Choose 1 Forward opponent controls. Break it.", fwd(false, 0)),
				() -> mw.gameState.getP2RemovedFromGame().contains(vanille),
				() -> mw.gameState.getP2Hand().contains(fang));
	}

	// =========================================================================================
	// 26-074C Tama: "Crystal is paid at the point when abilities are resolved. The auto-ability to
	// select one Category: "WOFF" character card and add it to your hand is then added to the
	// Stack after the crystal has been paid."
	// =========================================================================================

	@Test
	void tamasSecondOptionPaysTheCrystalAndThenStacksTheReturn() {
		// The option the AI declares is its own business; this resolves the second one as declared.
		CPUOptional.acceptsEveryOffer = true;
		MainWindow mw = new MainWindow();
		CardData tama = tama_26_074C();
		seatBackup(mw, false, tama);
		mw.gameState.addP2Crystals(1);
		CardData lann = categoryForward("Lann", "Fire", 3, 7000, "WOFF");
		toBreakZone(mw, false, lann);
		String option = "You may pay 《C》. When you do so, choose 1 Category WOFF Character in your Break Zone. "
				+ "Add it to your hand.";
		mw.currentAbilitySource = tama;
		mw.currentAbilitySourceIsP1 = false;
		try { ActionResolver.parse(option, tama).accept(mw.buildGameContext(false)); }
		finally { mw.currentAbilitySource = null; }

		assertEquals(0, crystals(mw, false), "the crystal is paid as the option resolves");
		assertFalse(mw.gameState.getP2Hand().contains(lann), "the return waits on the Stack");
		resolveToPayoff(mw);
		mw.resolveTopOfStack();
		assertTrue(mw.gameState.getP2Hand().contains(lann));
	}

	// =========================================================================================
	// 26-092H Serah: "Serah will not inflict damage on the opponent if the number of category:
	// "XIII" Forward cards under the player's control is two or lower before this card's
	// action-ability is resolved."
	// =========================================================================================

	/** Serah and {@code others} more Category XIII Forwards on P2's field; P1's damage after her action ability. */
	private static int serahDamageWith(int others) {
		MainWindow mw = new MainWindow();
		mw.gameState.startFirstTurn(GameState.Player.P2);
		fillDeck(mw, true, 3);
		CardData serah = serah_26_092H();
		seatForward(mw, false, serah);
		for (int i = 0; i < others; i++) seatForward(mw, false, categoryForward("XIII " + i, "Lightning", 2, 5000, "XIII"));
		resolveActionAs(mw, false, serah, 0);
		return damageZone(mw, true);
	}

	@Test
	void serahDealsNoDamageWithOnlyTwoCategoryXiiiForwards() {
		assertEquals(0, serahDamageWith(1));
	}

	@Test
	void serahDealsHerPointWithThreeCategoryXiiiForwards() {
		assertEquals(1, serahDamageWith(2));
	}

	// =========================================================================================
	// 26-105C Garnet: "Characters are dulled at the point when abilities are resolved. The
	// auto-ability to select one Forward and place it at the bottom of the deck is then added to
	// the Stack after the characters are dulled."
	// =========================================================================================

	@Test
	void garnetDullsFourAsItResolvesAndThenStacksTheBottomOfDeck() {
		CPUOptional.acceptsEveryOffer = true;
		MainWindow mw = new MainWindow();
		fillDeck(mw, true, 2);
		CardData theirs = makeForward("Theirs", "Fire", 5, 9000);
		seatForward(mw, true, theirs);
		CardData[] backups = seatBackups(mw, false, "Water", 4);
		assertPaysThenStacksThePayoff(mw, () -> p2Arrives(mw, garnet_26_105C()),
				() -> allDull(mw, false, backups) || mw.p2ForwardStates.contains(CardState.DULL),
				() -> mw.gameState.getP1MainDeck().peekLast() == theirs);
	}

	// =========================================================================================
	// 26-108C Thaliak: "You are still able to draw one card, even if the opponent was unable to
	// select any Forwards."
	// =========================================================================================

	/** Thaliak arrives on P2's side beside {@code others} more Job The Twelve; P1 has no Forward. P2's draw. */
	private static int thaliakDrawsWith(int others) {
		MainWindow mw = new MainWindow();
		fillDeck(mw, false, 3);
		for (int i = 0; i < others; i++) seatForward(mw, false, jobForward("Twelve " + i, "Water", 2, 5000, "The Twelve"));
		p2Arrives(mw, thaliak_26_108C());
		resolveStack(mw);
		return hand(mw, false);
	}

	@Test
	void thaliakDrawsEvenWhenTheOpponentHasNoForwardToSelect() {
		assertEquals(1, thaliakDrawsWith(3));
	}

	@Test
	void thaliakDrawsNothingWithFewerThanFourJobTheTwelve() {
		assertEquals(0, thaliakDrawsWith(2));
	}

	// =========================================================================================
	// 26-114H Asura, Manusya of War: "The character is returned to the player's hand at the point
	// when abilities are resolved. The auto-ability to reduce the Power of all Forwards apart from
	// Asura Manusya of War by 8000 … is then added to the Stack after the character has returned."
	// =========================================================================================

	@Test
	void asuraReturnsACharacterAsItResolvesAndThenStacksThePowerLoss() {
		CPUOptional.acceptsEveryOffer = true;
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Fire", 6, 12000);
		seatForward(mw, true, theirs);
		CardData backup = makeBackup("Returner", "Water", 2);
		seatBackup(mw, false, backup);
		assertPaysThenStacksThePayoff(mw, () -> p2Arrives(mw, asuraManusyaOfWar_26_114H()),
				() -> mw.gameState.getP2Hand().contains(backup),
				() -> power(mw, true, theirs) == 4000);
	}

	// =========================================================================================
	// 26-117R Yuna: "Cards from the deck are placed in the Break Zone at the point when abilities
	// are resolved. The auto-ability to select one Forward and reduce its Power by 5000 … is then
	// added to the Stack after cards are placed in the Break Zone."
	// =========================================================================================

	@Test
	void yunaMillsAsItResolvesAndThenStacksThePowerLoss() {
		CPUOptional.acceptsEveryOffer = true;
		MainWindow mw = new MainWindow();
		fillDeck(mw, false, 4);
		CardData theirs = makeForward("Theirs", "Fire", 6, 12000);
		seatForward(mw, true, theirs);
		assertPaysThenStacksThePayoff(mw, () -> p2Arrives(mw, yuna_26_117R()),
				() -> mw.gameState.getP2BreakZone().size() == 2,
				() -> power(mw, true, theirs) == 7000);
	}

	// =========================================================================================
	// OPUS 25
	// =========================================================================================

	/** Whether at least {@code atLeast} of {@code isP1}'s Backups and Forwards are dull: a "dull N" price, paid. */
	private static boolean dullCount(MainWindow mw, boolean isP1, int atLeast) {
		int n = 0;
		for (CardState s : isP1 ? mw.p1BackupStates : mw.p2BackupStates) if (s == CardState.DULL) n++;
		for (CardState s : isP1 ? mw.p1ForwardStates : mw.p2ForwardStates) if (s == CardState.DULL) n++;
		return n >= atLeast;
	}

	// =========================================================================================
	// 25-003C AVALANCHE Member: "The Category: VII card is discarded at the point when effects are
	// resolved. The Auto Ability to select one Forward and apply 7000 damage to it is then added
	// to the Stack after the card has been discarded."
	// =========================================================================================

	@Test
	void avalancheMemberDiscardsAsItResolvesAndThenStacksTheDamage() {
		CPUOptional.acceptsEveryOffer = true;
		MainWindow mw = new MainWindow();
		seatForward(mw, true, makeForward("Theirs", "Fire", 5, 9000));
		CardData tifa = categoryForward("Tifa", "Earth", 3, 7000, "VII");
		toHand(mw, false, tifa);
		assertPaysThenStacksThePayoff(mw, () -> p2Arrives(mw, avalancheMember_25_003C()),
				() -> mw.gameState.getP2BreakZone().contains(tifa),
				() -> mw.p1ForwardDamage.get(0) == 7000);
	}

	// =========================================================================================
	// 25-019C Necron: "If Necron has left the Field before its Auto Ability is resolved, then the
	// Forward card selected is not removed from the game."
	// =========================================================================================

	/** P2's Necron arrives choosing P1's Forward; P2 bounces Necron first if {@code necronLeaves}. */
	private static MainWindow necronArrives(CardData theirs, boolean necronLeaves) {
		MainWindow mw = new MainWindow();
		seatForward(mw, true, theirs);
		CardData necron = necron_25_019C();
		p2Arrives(mw, necron);
		if (necronLeaves) bounce(mw, necron);
		resolveStack(mw);
		return mw;
	}

	@Test
	void necronRemovesTheChosenForwardWhileHeStays() {
		CardData theirs = makeForward("Theirs", "Fire", 3, 7000);
		assertTrue(necronArrives(theirs, false).gameState.getP1RemovedFromGame().contains(theirs));
	}

	@Test
	void necronRemovesNothingWhenHeLeftFirst() {
		CardData theirs = makeForward("Theirs", "Fire", 3, 7000);
		MainWindow mw = necronArrives(theirs, true);
		assertTrue(mw.p1ForwardCards.contains(theirs));
	}

	// =========================================================================================
	// 25-052R Ursula: "Damage is not applied to the selected Forward card if Ursula is no longer in
	// the Field before the second effect of her ability is resolved."
	// =========================================================================================

	@Test
	void ursulaDealsNoDamageWhenSheLeftBeforeHerAttackAbilityResolved() {
		MainWindow mw = new MainWindow();
		seatForward(mw, true, makeForward("Theirs", "Fire", 3, 7000));
		CardData ursula = ursula_25_052R();
		seatForward(mw, false, ursula);
		mw.autoAbilityTriggers.triggerAutoAbilitiesForAttack(ursula, false);
		bounce(mw, ursula);
		resolveStack(mw);
		assertEquals(0, mw.p1ForwardDamage.get(0));
	}

	// =========================================================================================
	// 25-079C SOLDIER Candidate: "CP are paid at the point when effects are resolved. The Auto
	// Ability to Search for one card with the card name: "SOLDIER Candidate" and place it on the
	// Field is then added to the Stack after the CP have been paid."
	// =========================================================================================

	@Test
	void soldierCandidatePaysAsItResolvesAndThenStacksTheSearch() {
		CPUOptional.acceptsEveryOffer = true;
		MainWindow mw = new MainWindow();
		fillDeck(mw, false, 2);
		CardData next = soldierCandidate_25_079C();
		mw.gameState.getIdentity().put(next, false);
		mw.gameState.getP2MainDeck().add(next);
		CardData[] backups = seatBackups(mw, false, "Lightning", 1);
		CardData candidate = soldierCandidate_25_079C();
		seatForward(mw, false, candidate);
		assertPaysThenStacksThePayoff(mw,
				() -> respond(mw, true, "Choose 1 Forward opponent controls. Break it.", fwd(false, 0)),
				() -> allDull(mw, false, backups),
				() -> mw.p2ForwardCards.contains(next));
	}

	// =========================================================================================
	// 25-086R Abzu: "The opponent's Forward card's Power is not increased by any and all Field
	// Abilities." / "If the Power value of cards has been increased by an Action Ability, Auto
	// Ability or Summon etc. that has already been resolved, their Power will stay increased, even
	// if Abzu is placed on the field after those effect's resolution. However, no further
	// increases … will be possible."
	// =========================================================================================

	private static final String P1_BOOST = "Choose 1 Forward you control. It gains +2000 power until the end of the turn.";

	/** A P1 Backup whose field ability raises P1's Forwards by 1000. */
	private static CardData p1Anthem() {
		return card("Anthem", "Fire", "Backup", 2, 0, null, null, null, false, "The Forwards you control gain +1000 power.");
	}

	@Test
	void abzuStopsTheOpponentsFieldAbilitiesFromRaisingPower() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Fire", 3, 5000);
		seatForward(mw, true, theirs);
		seatMonster(mw, false, abzu_25_086R());
		seatBackup(mw, true, p1Anthem());
		assertEquals(5000, power(mw, true, theirs));
	}

	@Test
	void abzuKeepsAnEarlierBoostButStopsTheNextOne() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Fire", 3, 5000);
		seatForward(mw, true, theirs);
		respond(mw, true, P1_BOOST, fwd(true, 0));
		seatMonster(mw, false, abzu_25_086R());
		assertEquals(7000, power(mw, true, theirs), "resolved before Abzu arrived, so it stays");
		respond(mw, true, P1_BOOST, fwd(true, 0));
		assertEquals(7000, power(mw, true, theirs), "but no further increase");
	}

	// =========================================================================================
	// 25-091H Kraken (III): "The selected Backup card will not be removed from the game if Kraken
	// (III) is no longer present in the Field at the point when the Auto Ability is resolved."
	// =========================================================================================

	@Test
	void krakenRemovesNoBackupWhenHeLeftFirst() {
		MainWindow mw = new MainWindow();
		CardData backup = makeBackup("Theirs", "Fire", 2);
		seatBackup(mw, true, backup);
		CardData kraken = krakenIii_25_091H();
		p2Arrives(mw, kraken);
		bounce(mw, kraken);
		resolveStack(mw);
		assertSame(backup, mw.p1BackupCards[0]);
	}

	@Test
	void krakenRemovesTheChosenBackupWhileHeStays() {
		MainWindow mw = new MainWindow();
		CardData backup = makeBackup("Theirs", "Fire", 2);
		seatBackup(mw, true, backup);
		p2Arrives(mw, krakenIii_25_091H());
		resolveStack(mw);
		assertTrue(mw.gameState.getP1RemovedFromGame().contains(backup));
	}

	// =========================================================================================
	// 25-101L Meia: "The card in the Break Zone is removed from the game at the point when effects
	// are resolved. The Auto Ability to make the opponent select one Forward … is then added to
	// the Stack after the card has been removed."
	// =========================================================================================

	@Test
	void meiaRemovesAsItResolvesAndThenStacksTheSelection() {
		// P1 holds no Forward: its selection would open a dialog, and the price and the Stack are
		// what this ruling is about.
		CPUOptional.acceptsEveryOffer = true;
		MainWindow mw = new MainWindow();
		CardData mobius = categoryForward("Wol", "Light", 3, 7000, "MOBIUS");
		toBreakZone(mw, false, mobius);
		p2Arrives(mw, meia_25_101L());
		assertFalse(mw.gameState.getP2RemovedFromGame().contains(mobius));
		resolveToPayoff(mw);
		assertTrue(mw.gameState.getP2RemovedFromGame().contains(mobius));
		resolveStack(mw);
	}

	// =========================================================================================
	// OPUS 24
	// =========================================================================================

	// =========================================================================================
	// 24-047R Sophia (SOPFFO): "《C》 is paid at the point when effects are resolved. The Auto
	// Ability to select one option … is then added to the Stack after the payment is made."
	// =========================================================================================

	@Test
	void sophiaPaysTheCrystalAsItResolvesAndThenStacksTheSelection() {
		CPUOptional.acceptsEveryOffer = true;
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Fire", 5, 9000);
		seatForward(mw, true, theirs);
		mw.gameState.addP2Crystals(1);
		assertPaysThenStacksThePayoff(mw, () -> p2Arrives(mw, sophiaSopffo_24_047R()),
				() -> crystals(mw, false) == 0,
				() -> mw.gameState.getP1BreakZone().contains(theirs));
	}

	// =========================================================================================
	// 24-057C Cloud: "《Earth》《2》 is paid at the point when effects are resolved. The Auto Ability
	// to select one Dulled Forward and Break it is then added to the Stack after the payment."
	// =========================================================================================

	@Test
	void cloudBackupPaysAsItResolvesAndThenStacksTheBreak() {
		CPUOptional.acceptsEveryOffer = true;
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Fire", 5, 9000);
		seatForward(mw, true, theirs);
		mw.p1ForwardStates.set(0, CardState.DULL);
		CardData[] earth = seatBackups(mw, false, "Earth", 3);
		assertPaysThenStacksThePayoff(mw, () -> p2Arrives(mw, cloud_24_057C()),
				() -> allDull(mw, false, earth),
				() -> mw.gameState.getP1BreakZone().contains(theirs));
	}

	// =========================================================================================
	// 24-068H Morse: "《Earth》 is paid at the point when effects are resolved. The Auto Ability to
	// select one Forward … and have Morse and that Forward card both take damage … is then added
	// to the Stack after the payment is made."
	// =========================================================================================

	@Test
	void morsePaysAsItResolvesAndThenStacksTheExchange() {
		CPUOptional.acceptsEveryOffer = true;
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Fire", 3, 5000);
		seatForward(mw, true, theirs);
		CardData[] earth = seatBackups(mw, false, "Earth", 1);
		CardData morse = morse_24_068H();
		assertPaysThenStacksThePayoff(mw, () -> p2Arrives(mw, morse),
				() -> allDull(mw, false, earth),
				() -> mw.gameState.getP1BreakZone().contains(theirs));
		assertEquals(5000, mw.p2ForwardDamage.get(slot(mw, false, morse)));
	}

	// =========================================================================================
	// 24-072C Leo: "The four Characters selected are Dulled at the point when effects are
	// resolved. The Auto Ability to select one Forward and Break it is then added to the Stack
	// after the Characters have been Dulled."
	// =========================================================================================

	@Test
	void leoDullsFourAsItResolvesAndThenStacksTheBreak() {
		CPUOptional.acceptsEveryOffer = true;
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Fire", 5, 9000);
		seatForward(mw, true, theirs);
		seatBackups(mw, false, "Earth", 4);
		assertPaysThenStacksThePayoff(mw, () -> p2Arrives(mw, leo_24_072C()),
				() -> dullCount(mw, false, 4),
				() -> mw.gameState.getP1BreakZone().contains(theirs));
	}

	// =========================================================================================
	// 24-108H Wakka: "Nothing will happen if the Forward that Wakka chose has a greater Power value
	// than Wakka does at the point when effects are resolved."
	// =========================================================================================

	/** Wakka (8000 with two Category X allies) arrives on P2's side choosing P1's 8000 Forward. */
	private static MainWindow wakkaArrives(CardData theirs, boolean boostFirst) {
		MainWindow mw = new MainWindow();
		fillDeck(mw, true, 2);
		seatForward(mw, true, theirs);
		seatForward(mw, false, categoryForward("Tidus", "Water", 2, 5000, "X"));
		seatForward(mw, false, categoryForward("Yuna", "Water", 2, 5000, "X"));
		p2Arrives(mw, wakka_24_108H());
		if (boostFirst) respond(mw, true, P1_BOOST, fwd(true, 0));
		resolveStack(mw);
		return mw;
	}

	@Test
	void wakkaPutsAForwardOfNoGreaterPowerOnTopOfItsOwnersDeck() {
		CardData theirs = makeForward("Theirs", "Fire", 4, 8000);
		assertSame(theirs, wakkaArrives(theirs, false).gameState.getP1MainDeck().peekFirst());
	}

	@Test
	void wakkaDoesNothingWhenTheChosenForwardOutgrewHimBeforeResolution() {
		CardData theirs = makeForward("Theirs", "Fire", 4, 8000);
		assertTrue(wakkaArrives(theirs, true).p1ForwardCards.contains(theirs));
	}

	// =========================================================================================
	// 24-111H Lucio: "It is not possible to only search for one card with the job "Warrior of the
	// Crystal"."
	// =========================================================================================

	/** Lucio arrives on P2's side over a deck holding {@code elements} Warriors of the Crystal. */
	private static int lucioSearches(String... elements) {
		CPUOptional.acceptsEveryOffer = true;
		MainWindow mw = new MainWindow();
		fillDeck(mw, false, 2);
		for (String e : elements) {
			CardData w = jobForward(e + " Warrior", e, 3, 7000, "Warrior of the Crystal");
			mw.gameState.getIdentity().put(w, false);
			mw.gameState.getP2MainDeck().add(w);
		}
		p2Arrives(mw, lucio_24_111H());
		resolveStack(mw);
		return hand(mw, false);
	}

	@Test
	void lucioCannotSearchForOnlyOneWarriorOfTheCrystal() {
		assertEquals(0, lucioSearches("Fire"));
	}

	@Test
	void lucioSearchesForTwoWarriorsOfDifferentElements() {
		assertEquals(2, lucioSearches("Fire", "Ice"));
	}

	// =========================================================================================
	// OPUS 23
	// =========================================================================================

	// =========================================================================================
	// 23-002L Caius: "Cards from the player's hand are discarded at the point when effects are
	// resolved. The auto ability to remove Caius from the Break Zone and play him on the field in
	// the dull state is then added to the stack after the cards are discarded."
	// =========================================================================================

	@Test
	void caiusDiscardsAsItResolvesAndThenStacksHisReturn() {
		CPUOptional.acceptsEveryOffer = true;
		MainWindow mw = new MainWindow();
		CardData caius = caius_23_002L();
		seatForward(mw, false, caius);
		for (int i = 0; i < 3; i++) toHand(mw, false, makeForward("Hand " + i, "Fire", 2, 5000));
		assertPaysThenStacksThePayoff(mw,
				() -> respond(mw, true, "Choose 1 Forward opponent controls. Break it.", fwd(false, 0)),
				() -> hand(mw, false) == 0,
				() -> mw.p2ForwardCards.contains(caius));
		assertEquals(CardState.DULL, mw.p2ForwardStates.get(slot(mw, false, caius)));
	}

	// =========================================================================================
	// 23-006R Soulcage: "Soulcage is removed from the game at the point when effects are resolved.
	// The auto ability … to select one monster card from their own Break Zone and add it to their
	// hand is then added to the stack after Soulcage is removed."
	// =========================================================================================

	@Test
	void soulcageRemovesItselfAsItResolvesAndThenStacksTheReturn() {
		CPUOptional.acceptsEveryOffer = true;
		MainWindow mw = new MainWindow();
		CardData soulcage = soulcage_23_006R();
		seatMonster(mw, false, soulcage);
		CardData bomb = makeMonster("Bomb", "Fire", 2);
		toBreakZone(mw, false, bomb);
		assertPaysThenStacksThePayoff(mw,
				() -> respond(mw, true, "Choose 1 Monster opponent controls. Break it.",
						new ForwardTarget(false, 0, ForwardTarget.CardZone.MONSTER)),
				() -> mw.gameState.getP2RemovedFromGame().contains(soulcage),
				() -> mw.gameState.getP2Hand().contains(bomb));
	}

	// =========================================================================================
	// 23-017C Parai: "The point of damage is taken at the point when effects are resolved. The
	// auto ability to search for one category "FFL" character card … is then added to the stack
	// after the damage is taken."
	// =========================================================================================

	@Test
	void paraiTakesTheDamageAsItResolvesAndThenStacksTheSearch() {
		CPUOptional.acceptsEveryOffer = true;
		MainWindow mw = new MainWindow();
		fillDeck(mw, false, 3);
		CardData ffl = categoryForward("Aire", "Fire", 3, 7000, "FFL");
		mw.gameState.getIdentity().put(ffl, false);
		mw.gameState.getP2MainDeck().add(ffl);
		assertPaysThenStacksThePayoff(mw, () -> p2Arrives(mw, parai_23_017C()),
				() -> damageZone(mw, false) == 1,
				() -> mw.gameState.getP2Hand().contains(ffl));
	}

	// =========================================================================================
	// 23-021C Weiss: "The Crystal is paid at the point when effects are resolved. The auto ability
	// to search for a single job: Tsviets card … is then added to the stack after payment."
	// =========================================================================================

	@Test
	void weissPaysTheCrystalAsItResolvesAndThenStacksTheSearch() {
		CPUOptional.acceptsEveryOffer = true;
		MainWindow mw = new MainWindow();
		fillDeck(mw, false, 2);
		CardData rosso = jobForward("Rosso", "Ice", 3, 7000, "Tsviets");
		mw.gameState.getIdentity().put(rosso, false);
		mw.gameState.getP2MainDeck().add(rosso);
		mw.gameState.addP2Crystals(1);
		assertPaysThenStacksThePayoff(mw, () -> p2Arrives(mw, weiss_23_021C()),
				() -> crystals(mw, false) == 0,
				() -> mw.gameState.getP2Hand().contains(rosso));
	}

	// =========================================================================================
	// 23-028L Cecil: "The point of damage is taken at the point when effects are resolved. The
	// auto ability to select two characters, dull and freeze them, is then added to the stack
	// after the damage is taken."
	// =========================================================================================

	@Test
	void cecilTakesTheDamageAsItResolvesAndThenStacksTheFreeze() {
		CPUOptional.acceptsEveryOffer = true;
		MainWindow mw = new MainWindow();
		fillDeck(mw, false, 3);
		toHand(mw, false, makeForward("Spare", "Ice", 2, 5000));
		seatForward(mw, true, makeForward("A", "Fire", 3, 7000));
		seatForward(mw, true, makeForward("B", "Fire", 3, 7000));
		assertPaysThenStacksThePayoff(mw, () -> p2Arrives(mw, cecil_23_028L()),
				() -> damageZone(mw, false) == 1,
				() -> mw.p1ForwardStates.get(0) == CardState.DULL && mw.p1ForwardStates.get(1) == CardState.DULL);
	}

	// =========================================================================================
	// 23-038H Lady Lilith: "The Crystal is paid at the point when effects are resolved. The auto
	// ability to select one Forward and gain control of it is then added to the stack after
	// payment is made."
	// =========================================================================================

	@Test
	void ladyLilithPaysAsItResolvesAndThenStacksTheControlChange() {
		CPUOptional.acceptsEveryOffer = true;
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Fire", 5, 9000);
		seatForward(mw, true, theirs);
		mw.gameState.addP2Crystals(3);
		assertPaysThenStacksThePayoff(mw, () -> p2Arrives(mw, ladyLilith_23_038H()),
				() -> crystals(mw, false) == 0,
				() -> mw.p2ForwardCards.contains(theirs));
	}

	// =========================================================================================
	// 23-065R Gogmagog: "The monster card is removed from the game at the point when effects are
	// resolved. The auto ability to play Gogmagog to the field from the Break Zone is then added
	// to the stack after the monster is removed."
	// =========================================================================================

	@Test
	void gogmagogRemovesAMonsterAsItResolvesAndThenStacksItsReturn() {
		CPUOptional.acceptsEveryOffer = true;
		MainWindow mw = new MainWindow();
		CardData gogmagog = gogmagog_23_065R();
		seatMonster(mw, false, gogmagog);
		CardData bomb = makeMonster("Bomb", "Earth", 2);
		seatMonster(mw, false, bomb);
		assertPaysThenStacksThePayoff(mw,
				() -> respond(mw, true, "Choose 1 Monster opponent controls. Break it.",
						new ForwardTarget(false, mw.p2MonsterCards.indexOf(gogmagog), ForwardTarget.CardZone.MONSTER)),
				() -> mw.gameState.getP2RemovedFromGame().contains(bomb),
				() -> mw.p2MonsterCards.contains(gogmagog));
	}

	// =========================================================================================
	// 23-067R Shantotto: "Two Summons must be selected. If only one Summon is present in the Break
	// Zone, it is not possible to select it." / "It is possible to select one of your own Summons
	// and one of the opponent's Summons to make up the required two."
	// =========================================================================================

	@Test
	void shantottoTakesNothingWhenOnlyOneSummonIsThere() {
		MainWindow mw = new MainWindow();
		CardData fire = makeSummon("Fire", "Fire", 2, "Choose 1 Forward. Deal it 3000 damage.");
		toBreakZone(mw, true, fire);
		p2Arrives(mw, shantotto_23_067R());
		resolveStack(mw);
		assertTrue(mw.gameState.getP1BreakZone().contains(fire));
	}

	@Test
	void shantottoTakesOneSummonFromEachBreakZone() {
		MainWindow mw = new MainWindow();
		CardData fire = makeSummon("Fire", "Fire", 2, "Choose 1 Forward. Deal it 3000 damage.");
		CardData blizzard = makeSummon("Blizzard", "Ice", 2, "Choose 1 Forward. Dull it.");
		toBreakZone(mw, true, fire);
		toBreakZone(mw, false, blizzard);
		p2Arrives(mw, shantotto_23_067R());
		resolveStack(mw);
		assertFalse(mw.gameState.getP1BreakZone().contains(fire));
		assertFalse(mw.gameState.getP2BreakZone().contains(blizzard));
	}

	// =========================================================================================
	// 23-068C SOLDIER Candidate: "Forward cards left in the field will not take damage if the
	// SOLDIER Candidate or the Forward card chosen has left the field at the point when this auto
	// ability is resolved."
	// =========================================================================================

	@Test
	void soldierCandidateTakesNoDamageWhenTheChosenForwardLeft() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Fire", 3, 4000);
		seatForward(mw, true, theirs);
		CardData candidate = soldierCandidate_23_068C();
		p2Arrives(mw, candidate);
		bounce(mw, theirs);
		resolveStack(mw);
		assertEquals(0, mw.p2ForwardDamage.get(slot(mw, false, candidate)));
	}

	@Test
	void theForwardSoldierCandidateChoseTakesNoDamageWhenHeLeft() {
		MainWindow mw = new MainWindow();
		seatForward(mw, true, makeForward("Theirs", "Fire", 3, 9000));
		CardData candidate = soldierCandidate_23_068C();
		p2Arrives(mw, candidate);
		bounce(mw, candidate);
		resolveStack(mw);
		assertEquals(0, mw.p1ForwardDamage.get(0));
	}

	// =========================================================================================
	// 23-084C G'raha Tia: "A G'raha Tia card in the Break Zone is removed from the game at the
	// point when effects are resolved. The auto ability to select two Forwards … and inflict 4000
	// damage to them is then added to the stack after the card is removed."
	// =========================================================================================

	@Test
	void grahaTiaRemovesHisCopyAsItResolvesAndThenStacksTheDamage() {
		CPUOptional.acceptsEveryOffer = true;
		MainWindow mw = new MainWindow();
		seatForward(mw, true, makeForward("A", "Fire", 3, 7000));
		seatForward(mw, true, makeForward("B", "Fire", 3, 7000));
		CardData copy = gRahaTia_23_084C();
		toBreakZone(mw, false, copy);
		assertPaysThenStacksThePayoff(mw, () -> p2Arrives(mw, gRahaTia_23_084C()),
				() -> mw.gameState.getP2RemovedFromGame().contains(copy),
				() -> mw.p1ForwardDamage.get(0) == 4000 && mw.p1ForwardDamage.get(1) == 4000);
	}

	// =========================================================================================
	// 23-092R Black Widow: "The Monster card is placed in the Break Zone at the point when effects
	// are resolved. The auto ability to select one Forward and inflict 9000 damage on it is then
	// added to the stack after the card is placed in the Break Zone."
	// =========================================================================================

	@Test
	void blackWidowPutsAMonsterAwayAsItResolvesAndThenStacksTheDamage() {
		CPUOptional.acceptsEveryOffer = true;
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Fire", 5, 9000);
		seatForward(mw, true, theirs);
		CardData widow = blackWidow_23_092R();
		seatMonster(mw, false, widow);
		seatMonster(mw, false, makeMonster("Bomb", "Lightning", 2));
		assertPaysThenStacksThePayoff(mw, () -> mw.autoAbilityTriggers.triggerAutoAbilitiesForAttack(widow, false),
				() -> !mw.gameState.getP2BreakZone().isEmpty(),
				() -> mw.gameState.getP1BreakZone().contains(theirs));
	}

	// =========================================================================================
	// 23-094C Y'shtola: "The effect of this card's ability is still resolved, even if the player
	// controls fewer job: "Scion of the Seventh Dawn" cards than the number of Forwards selected
	// at the point when effects are resolved."
	// =========================================================================================

	@Test
	void yshtolaDullsAllSheChoseEvenWithFewerScionsAtResolution() {
		MainWindow mw = new MainWindow();
		seatForward(mw, true, makeForward("A", "Fire", 3, 7000));
		seatForward(mw, true, makeForward("B", "Fire", 3, 7000));
		CardData thancred = jobForward("Thancred", "Lightning", 3, 7000, "Scion of the Seventh Dawn");
		seatForward(mw, false, thancred);
		p2Arrives(mw, yShtola_23_094C());
		bounce(mw, thancred);
		resolveStack(mw);
		assertEquals(CardState.DULL, mw.p1ForwardStates.get(0));
		assertEquals(CardState.DULL, mw.p1ForwardStates.get(1));
	}

	// =========================================================================================
	// 23-101C Dancer: "The ability's effects are rendered invalid if the Forward selected is no
	// longer a valid target … even if you control five or more Backup cards."
	// =========================================================================================

	/** Dancer arrives as P2's fifth Backup, facing two P1 Forwards; the chosen one bounces first if {@code bounce}. */
	private static MainWindow dancerArrives(boolean bounce) {
		MainWindow mw = new MainWindow();
		seatForward(mw, true, makeForward("A", "Fire", 3, 9000));
		seatForward(mw, true, makeForward("B", "Fire", 3, 9000));
		seatBackups(mw, false, "Water", 4);
		p2Arrives(mw, dancer_23_101C());
		if (bounce) {
			ForwardTarget chosen = mw.gameState.peekStack().preSelectedTargets().get(0);
			bounce(mw, mw.p1ForwardCards.get(chosen.idx()));
		}
		resolveStack(mw);
		return mw;
	}

	@Test
	void dancerWithFiveBackupsWeakensEveryOpposingForward() {
		MainWindow mw = dancerArrives(false);
		assertEquals(5000, mw.effectiveP1ForwardPower(0));
		assertEquals(5000, mw.effectiveP1ForwardPower(1));
	}

	@Test
	void dancerDoesNothingWhenTheChosenForwardLeftEvenWithFiveBackups() {
		MainWindow mw = dancerArrives(true);
		assertEquals(1, mw.p1ForwardCards.size());
		assertEquals(9000, mw.effectiveP1ForwardPower(0));
	}

	// =========================================================================================
	// 23-115L Venat: "The Crystal is paid at the point when effects are resolved. The auto ability
	// to select one Forward and remove it from the game is then added to the stack after payment."
	// =========================================================================================

	@Test
	void venatPaysTheCrystalAsItResolvesAndThenStacksTheRemoval() {
		CPUOptional.acceptsEveryOffer = true;
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Fire", 5, 9000);
		seatForward(mw, true, theirs);
		CardData venat = venat_23_115L();
		seatForward(mw, false, venat);
		mw.gameState.addP2Crystals(1);
		assertPaysThenStacksThePayoff(mw,
				() -> respond(mw, true, "Choose 1 Forward opponent controls. Break it.", fwd(false, 0)),
				() -> crystals(mw, false) == 0,
				() -> mw.gameState.getP1RemovedFromGame().contains(theirs));
	}

	// =========================================================================================
	// OPUS 22
	// =========================================================================================

	// =========================================================================================
	// 22-005R Ignacio: "The cards from the deck are placed into the Break Zone at resolution. After
	// placing the cards in the Break Zone, the auto-ability … is placed on the stack." / "Cards
	// will be put into the Break Zone even if there are 2 or less cards left in the deck, however
	// the subsequent auto-ability will not be triggered."
	// =========================================================================================

	/** {@code n} Fire cards on P2's deck. */
	private static void fireDeck(MainWindow mw, int n) {
		for (int i = 0; i < n; i++) {
			CardData c = makeForward("Fire " + i, "Fire", 2, 5000);
			mw.gameState.getIdentity().put(c, false);
			mw.gameState.getP2MainDeck().add(c);
		}
	}

	@Test
	void ignacioMillsAsItResolvesAndThenStacksTheDamage() {
		MainWindow mw = new MainWindow();
		fireDeck(mw, 3);
		seatForward(mw, true, makeForward("Theirs", "Water", 6, 10000));
		assertPaysThenStacksThePayoff(mw, () -> p2Arrives(mw, ignacio_22_005R()),
				() -> mw.gameState.getP2BreakZone().size() == 3,
				() -> mw.p1ForwardDamage.get(0) == 9000);
	}

	@Test
	void ignacioMillsWhatThereIsButStacksNoPayoffShortOfThree() {
		MainWindow mw = new MainWindow();
		fireDeck(mw, 2);
		seatForward(mw, true, makeForward("Theirs", "Water", 6, 10000));
		p2Arrives(mw, ignacio_22_005R());
		mw.resolveTopOfStack();
		assertEquals(2, mw.gameState.getP2BreakZone().size(), "both cards still go");
		assertTrue(mw.gameState.getStack().isEmpty(), "but nothing triggers after");
		assertEquals(0, mw.p1ForwardDamage.get(0));
	}

	// =========================================================================================
	// 22-040H Enkidu: "CP is paid at resolution. After paying the cost, the auto-ability "play
	// Enkidu onto the field." is placed on the stack." / "If Enkidu leaves the Break Zone before
	// the second auto-ability is resolved, Enkidu does not enter the field."
	// =========================================================================================

	/** Enkidu in P2's Break Zone with 2 active Backups to pay with; a Card Name Gilgamesh enters P2's field. */
	private static CardData enkiduWatches(MainWindow mw, CardData[] payWith) {
		CPUOptional.acceptsEveryOffer = true;
		CardData enkidu = enkidu_22_040H();
		toBreakZone(mw, false, enkidu);
		CardData[] b = seatBackups(mw, false, "Wind", 2);
		System.arraycopy(b, 0, payWith, 0, 2);
		return enkidu;
	}

	@Test
	void enkiduPaysAsItResolvesAndThenStacksHisReturn() {
		MainWindow mw = new MainWindow();
		CardData[] backups = new CardData[2];
		CardData enkidu = enkiduWatches(mw, backups);
		assertPaysThenStacksThePayoff(mw, () -> placeP2Forward(mw, makeForward("Gilgamesh", "Earth", 3, 7000)),
				() -> allDull(mw, false, backups),
				() -> mw.p2ForwardCards.contains(enkidu));
	}

	@Test
	void enkiduStaysPutWhenHeLeftTheBreakZoneBeforeThePayoffResolved() {
		MainWindow mw = new MainWindow();
		CardData enkidu = enkiduWatches(mw, new CardData[2]);
		placeP2Forward(mw, makeForward("Gilgamesh", "Earth", 3, 7000));
		resolveToPayoff(mw);
		exileFromBreakZone(mw, false, enkidu);
		resolveStack(mw);
		assertFalse(mw.p2ForwardCards.contains(enkidu));
		assertTrue(mw.gameState.getP2RemovedFromGame().contains(enkidu));
	}

	// =========================================================================================
	// 22-044R Cid (FFBE): "CP is paid at resolution. After paying the cost, the auto-ability "play
	// Cid (FFBE) from the Break Zone onto the field dull." is placed on the stack." / "If Cid
	// (FFBE) leaves the Break Zone before the second auto-ability is resolved, Cid (FFBE) does not
	// enter the field."
	// =========================================================================================

	private static CardData cidFfbeOnField(MainWindow mw, CardData[] payWith) {
		CPUOptional.acceptsEveryOffer = true;
		CardData cid = cidFfbe_22_044R();
		seatForward(mw, false, cid);
		CardData[] b = seatBackups(mw, false, "Wind", 3);
		System.arraycopy(b, 0, payWith, 0, 3);
		return cid;
	}

	@Test
	void cidFfbePaysAsItResolvesAndThenStacksHisReturn() {
		MainWindow mw = new MainWindow();
		CardData[] backups = new CardData[3];
		CardData cid = cidFfbeOnField(mw, backups);
		assertPaysThenStacksThePayoff(mw,
				() -> respond(mw, true, "Choose 1 Forward opponent controls. Break it.", fwd(false, 0)),
				() -> allDull(mw, false, backups),
				() -> mw.p2ForwardCards.contains(cid));
		assertEquals(CardState.DULL, mw.p2ForwardStates.get(slot(mw, false, cid)));
	}

	@Test
	void cidFfbeStaysPutWhenHeLeftTheBreakZoneBeforeThePayoffResolved() {
		MainWindow mw = new MainWindow();
		CardData cid = cidFfbeOnField(mw, new CardData[3]);
		respond(mw, true, "Choose 1 Forward opponent controls. Break it.", fwd(false, 0));
		resolveToPayoff(mw);
		exileFromBreakZone(mw, false, cid);
		resolveStack(mw);
		assertFalse(mw.p2ForwardCards.contains(cid));
	}

	// =========================================================================================
	// 22-061L Gilgamesh: "CP is paid at resolution. After payment, the auto-ability "choose 1
	// Forward or Monster. Break it." is placed on the stack."
	// =========================================================================================

	@Test
	void gilgameshPaysAsItResolvesAndThenStacksTheBreak() {
		CPUOptional.acceptsEveryOffer = true;
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Fire", 5, 9000);
		seatForward(mw, true, theirs);
		CardData[] earth = seatBackups(mw, false, "Earth", 3);
		assertPaysThenStacksThePayoff(mw, () -> p2Arrives(mw, gilgamesh_22_061L()),
				() -> allDull(mw, false, earth),
				() -> mw.gameState.getP1BreakZone().contains(theirs));
	}

	// =========================================================================================
	// 22-065R Sieghard: "The Backups are dulled when resolved. After dulling them, the auto-ability
	// "choose 1 Forward of cost 6 or less in your Break Zone. Play it onto the field." is placed on
	// the stack."
	// =========================================================================================

	@Test
	void sieghardDullsAsItResolvesAndThenStacksThePlay() {
		CPUOptional.acceptsEveryOffer = true;
		MainWindow mw = new MainWindow();
		CardData[] backups = seatBackups(mw, false, "Earth", 4);
		CardData fallen = makeForward("Fallen", "Earth", 5, 9000);
		toBreakZone(mw, false, fallen);
		assertPaysThenStacksThePayoff(mw, () -> p2Arrives(mw, sieghard_22_065R()),
				() -> allDull(mw, false, backups),
				() -> mw.p2ForwardCards.contains(fallen));
	}

	// =========================================================================================
	// 22-079L Seifer: "The cards from the deck are put into the Break Zone at resolution. After
	// putting the cards into the Break Zone, the auto-ability … is placed on the stack." / "Cards
	// will be put into the Break Zone by the first auto-ability even if there are 1 or less cards
	// left in the deck, however the subsequent auto-ability will not be triggered."
	// =========================================================================================

	@Test
	void seiferMillsAsItResolvesAndThenStacksTheReturn() {
		CPUOptional.acceptsEveryOffer = true;
		MainWindow mw = new MainWindow();
		fillDeck(mw, false, 3);
		CardData squall = categoryForward("Squall", "Ice", 3, 7000, "VIII");
		toBreakZone(mw, false, squall);
		assertPaysThenStacksThePayoff(mw, () -> p2Arrives(mw, seifer_22_079L()),
				() -> mw.gameState.getP2BreakZone().size() == 3,
				() -> mw.gameState.getP2Hand().contains(squall));
	}

	@Test
	void seiferMillsTheLastCardButStacksNoPayoff() {
		CPUOptional.acceptsEveryOffer = true;
		MainWindow mw = new MainWindow();
		fillDeck(mw, false, 1);
		CardData squall = categoryForward("Squall", "Ice", 3, 7000, "VIII");
		toBreakZone(mw, false, squall);
		p2Arrives(mw, seifer_22_079L());
		mw.resolveTopOfStack();
		assertEquals(2, mw.gameState.getP2BreakZone().size(), "the one card still goes");
		assertTrue(mw.gameState.getStack().isEmpty(), "but nothing triggers after");
		assertFalse(mw.gameState.getP2Hand().contains(squall));
	}

	// =========================================================================================
	// 22-090H Lulu: "The Summons are removed from the game at resolution. After removal, the
	// auto-ability "choose 1 Forward. Deal it 7000 damage." is placed on the stack."
	// =========================================================================================

	@Test
	void luluRemovesTheSummonsAsItResolvesAndThenStacksTheDamage() {
		CPUOptional.acceptsEveryOffer = true;
		MainWindow mw = new MainWindow();
		mw.gameState.startFirstTurn(GameState.Player.P2);
		seatForward(mw, true, makeForward("Theirs", "Fire", 5, 9000));
		seatForward(mw, false, lulu_22_090H());
		CardData a = makeSummon("Thunder", "Lightning", 2, "Choose 1 Forward. Deal it 3000 damage.");
		CardData b = makeSummon("Water", "Water", 2, "Choose 1 Forward. Deal it 3000 damage.");
		toBreakZone(mw, false, a);
		toBreakZone(mw, false, b);
		assertPaysThenStacksThePayoff(mw, () -> mw.autoAbilityTriggers.triggerAutoAbilitiesForBeginningOfAttackPhase(false),
				() -> mw.gameState.getP2RemovedFromGame().containsAll(List.of(a, b)),
				() -> mw.p1ForwardDamage.get(0) == 7000);
	}

	// =========================================================================================
	// 22-091C Blue Mage: "The Monster is discarded at resolution. Once discarded, the auto-ability
	// "draw 1 card." is placed on the stack."
	// =========================================================================================

	@Test
	void blueMageDiscardsAsItResolvesAndThenStacksTheDraw() {
		CPUOptional.acceptsEveryOffer = true;
		MainWindow mw = new MainWindow();
		fillDeck(mw, false, 2);
		CardData bomb = makeMonster("Bomb", "Water", 2);
		toHand(mw, false, bomb);
		assertPaysThenStacksThePayoff(mw, () -> p2Arrives(mw, blueMage_22_091C()),
				() -> mw.gameState.getP2BreakZone().contains(bomb),
				() -> hand(mw, false) == 1);
	}

	// =========================================================================================
	// 22-103C Faris: "The Forward is removed from the game at resolution. After removal, the
	// auto-ability "your opponent selects 1 Forward they control. Put it into the Break Zone." is
	// placed on the stack."
	// =========================================================================================

	@Test
	void farisRemovesHerForwardAsItResolvesAndThenStacksTheSelection() {
		// P1 holds no Forward: its selection would open a dialog.
		CPUOptional.acceptsEveryOffer = true;
		MainWindow mw = new MainWindow();
		CardData mine = makeForward("Mine", "Water", 2, 5000);
		seatForward(mw, false, mine);
		p2Arrives(mw, faris_22_103C());
		assertFalse(mw.gameState.getP2RemovedFromGame().contains(mine));
		resolveToPayoff(mw);
		assertTrue(mw.gameState.getP2RemovedFromGame().contains(mine));
		resolveStack(mw);
	}

	// =========================================================================================
	// OPUS 21
	// =========================================================================================

	// =========================================================================================
	// 21-005C Black Mage: "《1》is paid at the point when effects are resolved. The Auto Ability
	// "Obtain 《C》" is then added to the stack after payment has been made."
	// =========================================================================================

	@Test
	void blackMagePaysAsItResolvesAndThenStacksTheCrystal() {
		CPUOptional.acceptsEveryOffer = true;
		MainWindow mw = new MainWindow();
		CardData[] backups = seatBackups(mw, false, "Fire", 1);
		assertPaysThenStacksThePayoff(mw, () -> p2Arrives(mw, blackMage_21_005C()),
				() -> allDull(mw, false, backups),
				() -> crystals(mw, false) == 1);
	}

	// =========================================================================================
	// 21-032R Terra: "It is not possible to search for only a single card."
	// =========================================================================================

	private static int terraSearches(int... costs) {
		CPUOptional.acceptsEveryOffer = true;
		MainWindow mw = new MainWindow();
		fillDeck(mw, false, 2);
		for (int cost : costs) {
			CardData s = makeSummon("Ice Summon " + cost, "Ice", cost, "Choose 1 Forward. Dull it.");
			mw.gameState.getIdentity().put(s, false);
			mw.gameState.getP2MainDeck().add(s);
		}
		p2Arrives(mw, terra_21_032R());
		resolveStack(mw);
		return hand(mw, false);
	}

	@Test
	void terraCannotSearchForOnlyOneIceSummon() {
		assertEquals(0, terraSearches(2));
	}

	@Test
	void terraSearchesForTwoIceSummonsOfDifferentCosts() {
		assertEquals(2, terraSearches(2, 3));
	}

	// =========================================================================================
	// 21-040R Rursan Arbiter: "If Cid Aulstyne is present on the Field, then it is not possible to
	// deploy the Rursan Arbiter to the Field in the first place. If an effect would otherwise cause
	// the Rursan Arbiter to deploy to the Field, then it will remain in its original location."
	// =========================================================================================

	@Test
	void rursanArbiterStaysInHandWhileCidAulstyneIsControlled() {
		MainWindow mw = new MainWindow();
		seatForward(mw, false, makeForward("Cid Aulstyne", "Ice", 5, 9000));
		CardData arbiter = rursanArbiter_21_040R();
		toHand(mw, false, arbiter);
		respond(mw, false, "Play 1 Forward of cost 5 or less from your hand onto the field.");
		assertFalse(mw.p2ForwardCards.contains(arbiter));
		assertTrue(mw.gameState.getP2Hand().contains(arbiter), "it remains where it was");
	}

	// =========================================================================================
	// 21-050H Sophia (SOPFFO): "One Forward belonging to [Category (SOPFFO)] is dulled at the point
	// when effects are resolved. The Auto Ability "Select one Forward and inflict 5000 damage on
	// it" is then added to the stack after the Forward card has been dulled."
	// =========================================================================================

	@Test
	void sophiaDullsAsItResolvesAndThenStacksTheDamage() {
		CPUOptional.acceptsEveryOffer = true;
		MainWindow mw = new MainWindow();
		seatForward(mw, true, makeForward("Theirs", "Fire", 5, 9000));
		CardData sophia = sophiaSopffo_21_050H();
		seatForward(mw, false, sophia);
		assertPaysThenStacksThePayoff(mw, () -> mw.autoAbilityTriggers.triggerAutoAbilitiesForCastSummon(false),
				() -> mw.p2ForwardStates.get(slot(mw, false, sophia)) == CardState.DULL,
				() -> mw.p1ForwardDamage.get(0) == 5000);
	}

	// =========================================================================================
	// 21-069C Krile: "Summons are discarded at the point when effects are resolved. The Auto
	// Ability "Select one Forward and inflict 8000 damage to it" is then added to the stack after
	// the card/s are discarded."
	// =========================================================================================

	@Test
	void krileDiscardsAsItResolvesAndThenStacksTheDamage() {
		CPUOptional.acceptsEveryOffer = true;
		MainWindow mw = new MainWindow();
		seatForward(mw, true, makeForward("Theirs", "Fire", 5, 9000));
		CardData quake = makeSummon("Quake", "Earth", 3, "Choose 1 Forward. Deal it 3000 damage.");
		toHand(mw, false, quake);
		assertPaysThenStacksThePayoff(mw, () -> p2Arrives(mw, krile_21_069C()),
				() -> mw.gameState.getP2BreakZone().contains(quake),
				() -> mw.p1ForwardDamage.get(0) == 8000);
	}

	// =========================================================================================
	// 21-075R Haveh: "If Haveh is present on the Field then power values cannot have "+" modifiers
	// applied through any and all Field abilities." / "In cases where an … ability … that applies a
	// "+" modifier had already been resolved and Haveh is deployed to the Field afterwards, the
	// previously applied modifiers will remain in effect but any subsequent abilities or Summons
	// will be unable to apply "+" modifiers from then on."
	// =========================================================================================

	@Test
	void havehStopsTheOpponentsFieldAbilitiesFromRaisingPower() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Fire", 3, 5000);
		seatForward(mw, true, theirs);
		seatForward(mw, false, haveh_21_075R());
		seatBackup(mw, true, p1Anthem());
		assertEquals(5000, power(mw, true, theirs));
	}

	@Test
	void havehKeepsAnEarlierBoostButStopsTheNextOne() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Fire", 3, 5000);
		seatForward(mw, true, theirs);
		respond(mw, true, P1_BOOST, fwd(true, 0));
		seatForward(mw, false, haveh_21_075R());
		assertEquals(7000, power(mw, true, theirs), "resolved before Haveh arrived, so it stays");
		respond(mw, true, P1_BOOST, fwd(true, 0));
		assertEquals(7000, power(mw, true, theirs), "but no further increase");
	}

	// =========================================================================================
	// 21-086C Gunbreaker: "《C》 is paid at the point when effects are resolved. The Auto Ability
	// "deal 2000 damage to all the Forwards opponent controls" is then added to the stack after."
	// =========================================================================================

	@Test
	void gunbreakerPaysTheCrystalAsItResolvesAndThenStacksTheDamage() {
		CPUOptional.acceptsEveryOffer = true;
		MainWindow mw = new MainWindow();
		seatForward(mw, true, makeForward("Theirs", "Fire", 6, 12000));
		CardData gunbreaker = gunbreaker_21_086C();
		seatForward(mw, false, gunbreaker);
		mw.gameState.addP2Crystals(1);
		mw.autoAbilityTriggers.triggerAutoAbilitiesForAttack(gunbreaker, false);
		assertEquals(1, crystals(mw, false), "nothing paid as the abilities go on the Stack");
		resolveToPayoff(mw);
		assertEquals(0, crystals(mw, false));
		int before = mw.p1ForwardDamage.get(0);
		mw.resolveTopOfStack();
		assertEquals(before + 2000, mw.p1ForwardDamage.get(0));
		resolveStack(mw);
		assertEquals(4000, mw.p1ForwardDamage.get(0), "both 2000s");
	}

	// =========================================================================================
	// 21-088C Gilgamesh: "Gilgamesh can only attack twice in one turn, even if the action ability
	// is used twice in a turn."
	// =========================================================================================

	@Test
	void gilgameshAttacksOnlyTwiceHoweverOftenHisAbilityIsUsed() {
		MainWindow mw = new MainWindow();
		CardData gilgamesh = gilgamesh_21_088C();
		seatForward(mw, false, gilgamesh);
		resolveActionAs(mw, false, gilgamesh, 0);
		resolveActionAs(mw, false, gilgamesh, 0);
		assertEquals(2, mw.maxAttacksPerTurn(gilgamesh));
	}

	// =========================================================================================
	// 21-094C Shantotto: "If a single character has multiple elements then all of them will be
	// counted."
	// =========================================================================================

	@Test
	void shantottoCountsEveryElementOfAMultiElementCharacter() {
		MainWindow mw = new MainWindow();
		seatForward(mw, true, makeForward("Theirs", "Water", 7, 15000));
		seatForward(mw, false, makeForward("Fire Ice", "Fire/Ice", 3, 7000));
		p2Arrives(mw, shantotto_21_094C());
		resolveStack(mw);
		assertEquals(9000, mw.p1ForwardDamage.get(0), "Fire, Ice and Shantotto's own Earth");
	}

	// =========================================================================================
	// 21-125S Onion Knight / 21-128S Shantotto: "Crystals are paid at the point when effects are
	// resolved. The Auto Ability … is then added to the stack after payment is made."
	// =========================================================================================

	@Test
	void onionKnightPaysTheCrystalsAsItResolvesAndThenStacksTheSearch() {
		CPUOptional.acceptsEveryOffer = true;
		MainWindow mw = new MainWindow();
		fillDeck(mw, false, 2);
		CardData dff = categoryForward("Bartz", "Wind", 3, 7000, "DFF");
		mw.gameState.getIdentity().put(dff, false);
		mw.gameState.getP2MainDeck().add(dff);
		mw.gameState.addP2Crystals(2);
		assertPaysThenStacksThePayoff(mw, () -> p2Arrives(mw, onionKnight_21_125S()),
				() -> crystals(mw, false) == 0,
				() -> mw.p2ForwardCards.contains(dff));
	}

	@Test
	void shantottoDffPaysTheCrystalsAsItResolvesAndThenStacksThePlay() {
		CPUOptional.acceptsEveryOffer = true;
		MainWindow mw = new MainWindow();
		CardData dff = categoryForward("Bartz", "Wind", 5, 9000, "DFF");
		toBreakZone(mw, false, dff);
		mw.gameState.addP2Crystals(2);
		assertPaysThenStacksThePayoff(mw, () -> p2Arrives(mw, shantotto_21_128S()),
				() -> crystals(mw, false) == 0,
				() -> mw.p2ForwardCards.contains(dff));
	}

	// =========================================================================================
	// 21-130S Noctis: "It is possible to deploy Noctis to the Field using Summons or Abilities."
	// =========================================================================================

	@Test
	void noctisCanBePlayedByAnAbilityWithoutACategoryDffForward() {
		MainWindow mw = new MainWindow();
		CardData noctis = noctis_21_130S();
		toHand(mw, false, noctis);
		respond(mw, false, "Play 1 Forward of cost 3 or less from your hand onto the field.");
		assertTrue(mw.p2ForwardCards.contains(noctis));
	}

	// =========================================================================================
	// OPUS 20
	// =========================================================================================

	// =========================================================================================
	// 20-017R Palom: "Crystals are paid at the point effects are resolved. The Auto-ability
	// "Select one Forward and inflict 8000 damage on it" is added to the stack after paying."
	// =========================================================================================

	@Test
	void palomPaysTheCrystalAsItResolvesAndThenStacksTheDamage() {
		CPUOptional.acceptsEveryOffer = true;
		MainWindow mw = new MainWindow();
		seatForward(mw, true, makeForward("Theirs", "Fire", 5, 9000));
		mw.gameState.addP2Crystals(1);
		assertPaysThenStacksThePayoff(mw, () -> p2Arrives(mw, palom_20_017R()),
				() -> crystals(mw, false) == 0,
				() -> mw.p1ForwardDamage.get(0) == 8000);
	}

	// =========================================================================================
	// 20-021R Red XIII: "If no [category (VII)] are controlled then the player is unable to cast
	// Red XIII and cannot deploy him to the Field through summons or abilities either."
	// =========================================================================================

	@Test
	void redXiiiCannotBePlayedByAnAbilityWithoutACategoryViiForward() {
		MainWindow mw = new MainWindow();
		CardData red = redXiii_20_021R();
		toHand(mw, false, red);
		respond(mw, false, "Play 1 Forward of cost 2 or less from your hand onto the field.");
		assertFalse(mw.p2ForwardCards.contains(red));
		assertTrue(mw.gameState.getP2Hand().contains(red));
	}

	// =========================================================================================
	// 20-029C Jihl Nabaat: "It is possible to add +2000 to Jihl Nabaat's power, even if
	// multi-element cards including the ice element are controlled, as long as all characters have
	// the ice element." / "《Ice》《Ice》 is paid at the point when abilities are resolved. The
	// Auto-ability "Select two characters. These characters are Dulled and frozen" is added to the
	// stack after paying."
	// =========================================================================================

	@Test
	void jihlNabaatGainsPowerBesideAMultiElementIceCharacter() {
		MainWindow mw = new MainWindow();
		CardData jihl = jihlNabaat_20_029C();
		seatForward(mw, false, jihl);
		seatForward(mw, false, makeForward("Ice Fire", "Fire/Ice", 3, 7000));
		assertEquals(9000, power(mw, false, jihl));
	}

	@Test
	void jihlNabaatPaysAsItResolvesAndThenStacksTheFreeze() {
		CPUOptional.acceptsEveryOffer = true;
		MainWindow mw = new MainWindow();
		seatForward(mw, true, makeForward("A", "Fire", 3, 7000));
		seatForward(mw, true, makeForward("B", "Fire", 3, 7000));
		CardData[] ice = seatBackups(mw, false, "Ice", 2);
		seatForward(mw, false, jihlNabaat_20_029C());
		assertPaysThenStacksThePayoff(mw,
				() -> respond(mw, true, "Choose 1 Forward opponent controls. Break it.", fwd(false, 0)),
				() -> allDull(mw, false, ice),
				() -> mw.p1ForwardStates.get(0) == CardState.DULL && mw.p1ForwardStates.get(1) == CardState.DULL);
	}

	// =========================================================================================
	// 20-031R Celes: "The effect will still be resolved appropriately, even if you control fewer
	// [category (VI)] characters than the number of Forwards selected at the point effects are
	// resolved."
	// =========================================================================================

	@Test
	void celesDullsAllSheChoseEvenWithFewerCategoryViAtResolution() {
		MainWindow mw = new MainWindow();
		seatForward(mw, true, makeForward("A", "Fire", 3, 7000));
		seatForward(mw, true, makeForward("B", "Fire", 3, 7000));
		CardData edgar = categoryForward("Edgar", "Ice", 3, 7000, "VI");
		seatForward(mw, false, edgar);
		p2Arrives(mw, celes_20_031R());
		bounce(mw, edgar);
		resolveStack(mw);
		assertEquals(CardState.DULL, mw.p1ForwardStates.get(0));
		assertEquals(CardState.DULL, mw.p1ForwardStates.get(1));
	}

	// =========================================================================================
	// 20-060R Yuri / 20-066R Ignis / 20-085C Assassin / 20-112C Frimelda / 20-122R Leslie: the
	// price — a discard, a Backup into the Break Zone, CP, a discarded Forward — is paid as the
	// ability resolves, and the payoff is added to the stack after.
	// =========================================================================================

	@Test
	void yuriDiscardsAsItResolvesAndThenStacksTheSearch() {
		CPUOptional.acceptsEveryOffer = true;
		MainWindow mw = new MainWindow();
		fillDeck(mw, false, 2);
		CardData chelinka = makeForward("Chelinka", "Wind", 3, 7000);
		mw.gameState.getIdentity().put(chelinka, false);
		mw.gameState.getP2MainDeck().add(chelinka);
		toHand(mw, false, makeForward("Spare", "Wind", 2, 5000));
		assertPaysThenStacksThePayoff(mw, () -> p2Arrives(mw, yuri_20_060R()),
				() -> hand(mw, false) == 0,
				() -> mw.p2ForwardCards.contains(chelinka));
	}

	@Test
	void ignisPutsABackupAwayAsItResolvesAndThenStacksTheBreak() {
		CPUOptional.acceptsEveryOffer = true;
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Fire", 5, 9000);
		seatForward(mw, true, theirs);
		CardData backup = makeBackup("Spare", "Earth", 2);
		seatBackup(mw, false, backup);
		assertPaysThenStacksThePayoff(mw, () -> p2Arrives(mw, ignis_20_066R()),
				() -> mw.gameState.getP2BreakZone().contains(backup),
				() -> mw.gameState.getP1BreakZone().contains(theirs));
	}

	@Test
	void assassinPaysAsItResolvesAndThenStacksTheDamage() {
		CPUOptional.acceptsEveryOffer = true;
		MainWindow mw = new MainWindow();
		seatForward(mw, true, makeForward("Theirs", "Fire", 5, 9000));
		CardData[] lightning = seatBackups(mw, false, "Lightning", 3);
		assertPaysThenStacksThePayoff(mw, () -> p2Arrives(mw, assassin_20_085C()),
				() -> allDull(mw, false, lightning),
				() -> mw.p1ForwardDamage.get(0) == 3000);
	}

	@Test
	void frimeldaStacksItsPayoffOnceAForwardIsDiscarded() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Fire", 5, 9000);
		seatForward(mw, true, theirs);
		CardData top = makeForward("Drawn", "Lightning", 2, 5000);
		mw.gameState.getIdentity().put(top, false);
		mw.gameState.getP2MainDeck().add(top);
		toHand(mw, false, makeForward("Held", "Lightning", 2, 5000));
		CardData frimelda = frimelda_20_112C();
		seatForward(mw, false, frimelda);
		assertPaysThenStacksThePayoff(mw, () -> mw.autoAbilityTriggers.triggerAutoAbilitiesForAttack(frimelda, false),
				() -> mw.gameState.getP2BreakZone().size() == 1,
				() -> power(mw, true, theirs) == 6000 && power(mw, false, frimelda) == 10000);
	}

	@Test
	void leslieDiscardsAsItResolvesAndThenStacksTheReturn() {
		CPUOptional.acceptsEveryOffer = true;
		MainWindow mw = new MainWindow();
		fillDeck(mw, false, 2);
		CardData theirs = makeForward("Theirs", "Fire", 5, 9000);
		seatForward(mw, true, theirs);
		toHand(mw, false, categoryForward("Tifa", "Earth", 3, 7000, "VII"));
		assertPaysThenStacksThePayoff(mw, () -> p2Arrives(mw, leslie_20_122R()),
				() -> hand(mw, false) == 0,
				() -> mw.gameState.getP1Hand().contains(theirs) && hand(mw, false) == 1);
	}

	// =========================================================================================
	// OPUS 19
	// =========================================================================================

	// =========================================================================================
	// 19-009C Bomb: "The 2 Fire Backups are dulled when resolved. After dulling them, the
	// auto-ability "search for 1 Card Name Bomb of cost 2 or less and play it onto the field." is
	// placed on the stack."
	// =========================================================================================

	@Test
	void bombDullsAsItResolvesAndThenStacksTheSearch() {
		CPUOptional.acceptsEveryOffer = true;
		MainWindow mw = new MainWindow();
		fillDeck(mw, false, 2);
		// Bomb 19-009C carries the generic icon, so a second generic Bomb may join it.
		CardData next = genericCard("Bomb", "Fire", "Monster", 2, 0, "Bomb", null, null, false, "");
		mw.gameState.getIdentity().put(next, false);
		mw.gameState.getP2MainDeck().add(next);
		CardData[] fire = seatBackups(mw, false, "Fire", 2);
		assertPaysThenStacksThePayoff(mw, () -> p2Arrives(mw, bomb_19_009C()),
				() -> allDull(mw, false, fire),
				() -> mw.p2MonsterCards.contains(next));
	}

	// =========================================================================================
	// 19-027R Chocobo Eater: "If Chocobo Eater leaves the field before the auto-ability is
	// resolved, the chosen Forward is not removed from the game."
	// =========================================================================================

	@Test
	void chocoboEaterRemovesNothingWhenItLeftFirst() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Fire", 2, 5000);
		seatForward(mw, true, theirs);
		CardData eater = chocoboEater_19_027R();
		p2Arrives(mw, eater);
		respond(mw, true, "Choose 1 Monster opponent controls. Break it.",
				new ForwardTarget(false, mw.p2MonsterCards.indexOf(eater), ForwardTarget.CardZone.MONSTER));
		resolveStack(mw);
		assertTrue(mw.p1ForwardCards.contains(theirs));
	}

	// =========================================================================================
	// 19-059R Aster Protoflorian / 19-061H Doga / 19-066C Behemoth / 19-072C Eald'narche /
	// 19-080R Vivi / 19-100C Larsa: each price is paid at resolution, and its payoff is placed on
	// the stack after.
	// =========================================================================================

	@Test
	void asterProtoflorianPaysAsItResolvesAndThenStacksTheBreak() {
		CPUOptional.acceptsEveryOffer = true;
		MainWindow mw = new MainWindow();
		// One target for each ability, so whichever payoff surfaces first has something to break.
		seatForward(mw, true, makeForward("Theirs", "Fire", 5, 9000));
		seatBackup(mw, true, makeBackup("Their Backup", "Fire", 2));
		seatBackups(mw, false, "Earth", 4);
		assertPaysThenStacksThePayoff(mw, () -> p2Arrives(mw, asterProtoflorian_19_059R()),
				() -> dullCount(mw, false, 2),
				() -> mw.gameState.getP1BreakZone().size() == 1);
		assertEquals(2, mw.gameState.getP1BreakZone().size(), "both abilities paid and broke");
	}

	@Test
	void dogaDiscardsTheSummonAsItResolvesAndThenStacksTheCrystals() {
		CPUOptional.acceptsEveryOffer = true;
		MainWindow mw = new MainWindow();
		CardData summon = makeSummon("Fire", "Fire", 2, "Choose 1 Forward. Deal it 3000 damage.");
		toHand(mw, false, summon);
		assertPaysThenStacksThePayoff(mw, () -> p2Arrives(mw, doga_19_061H()),
				() -> mw.gameState.getP2BreakZone().contains(summon),
				() -> crystals(mw, false) == 2);
	}

	@Test
	void behemothPaysAsItResolvesAndThenStacksTheRestriction() {
		CPUOptional.acceptsEveryOffer = true;
		MainWindow mw = new MainWindow();
		seatForward(mw, true, makeForward("Theirs", "Fire", 5, 9000));
		CardData[] earth = seatBackups(mw, false, "Earth", 1);
		p2Arrives(mw, behemoth_19_066C());
		assertFalse(allDull(mw, false, earth));
		resolveToPayoff(mw);
		assertTrue(allDull(mw, false, earth), "《Earth》 paid as it resolved");
		resolveStack(mw);
	}

	@Test
	void ealdnarcheDiscardsAsItResolvesAndThenStacksTheBreak() {
		CPUOptional.acceptsEveryOffer = true;
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Fire", 5, 9000);
		seatForward(mw, true, theirs);
		toHand(mw, false, makeForward("Spare", "Earth", 2, 5000));
		assertPaysThenStacksThePayoff(mw, () -> p2Arrives(mw, ealdNarche_19_072C()),
				() -> hand(mw, false) == 0,
				() -> mw.gameState.getP1BreakZone().contains(theirs));
	}

	@Test
	void viviRevealsAsItResolvesAndThenStacksTheDamage() {
		CPUOptional.acceptsEveryOffer = true;
		MainWindow mw = new MainWindow();
		seatForward(mw, true, makeForward("Theirs", "Fire", 6, 12000));
		toHand(mw, false, makeForward("Bolt A", "Lightning", 2, 5000));
		toHand(mw, false, makeForward("Bolt B", "Lightning", 2, 5000));
		p2Arrives(mw, vivi_19_080R());
		resolveToPayoff(mw);
		assertEquals(0, mw.p1ForwardDamage.get(0), "the damage waits on the Stack");
		mw.resolveTopOfStack();
		assertEquals(4000, mw.p1ForwardDamage.get(0), "2000 for each of the 2 revealed");
	}

	@Test
	void larsaDiscardsAsItResolvesAndThenStacksTheSearch() {
		CPUOptional.acceptsEveryOffer = true;
		MainWindow mw = new MainWindow();
		fillDeck(mw, false, 2);
		CardData vaan = categoryForward("Vaan", "Wind", 3, 7000, "XII");
		mw.gameState.getIdentity().put(vaan, false);
		mw.gameState.getP2MainDeck().add(vaan);
		toHand(mw, false, makeForward("Spare", "Water", 2, 5000));
		assertPaysThenStacksThePayoff(mw, () -> p2Arrives(mw, larsa_19_100C()),
				() -> !mw.gameState.getP2Hand().stream().anyMatch(c -> c.name().equals("Spare")),
				() -> mw.gameState.getP2Hand().contains(vaan));
	}

	// =========================================================================================
	// 19-124L Y'shtola: "The 1 point of damage is received when resolved. Once received, the
	// auto-ability "until the end of the turn, Y'shtola gains Haste and …" is placed on the stack.
	// In addition, this auto-ability and Y'shtola's second auto-ability are placed on the stack at
	// the same time."
	// =========================================================================================

	@Test
	void yshtolasPayoffAndHerDamageWatcherGoOnTheStackTogether() {
		CPUOptional.acceptsEveryOffer = true;
		MainWindow mw = new MainWindow();
		fillDeck(mw, false, 3);
		seatForward(mw, true, makeForward("Theirs", "Fire", 5, 9000));
		p2Arrives(mw, yShtola_19_124L());
		assertEquals(0, damageZone(mw, false));
		mw.resolveTopOfStack();
		assertEquals(1, damageZone(mw, false), "the point received as it resolved");
		assertEquals(2, mw.gameState.stackSize(), "the payoff and the 4000-damage watcher, together");
		assertTrue(mw.gameState.getStack().stream()
				.anyMatch(e -> e.autoAbility() != null && e.autoAbility().isWhenYouDoSo()));
		resolveStack(mw);
		assertEquals(4000, mw.p1ForwardDamage.get(0));
	}

	// =========================================================================================
	// OPUS 18
	// =========================================================================================

	// =========================================================================================
	// 18-009H Tidus: "The 5 cards from the deck are placed in the break zone at the point when
	// effects are resolved. The auto-ability … is then placed on the stack afterwards." / "If
	// there are four or fewer cards in the deck then they are still placed in the break zone, but
	// the auto-ability is not triggered afterwards."
	// =========================================================================================

	@Test
	void tidusMillsAsItResolvesAndThenStacksTheDamage() {
		MainWindow mw = new MainWindow();
		fireDeck(mw, 5);
		seatForward(mw, true, makeForward("Theirs", "Water", 5, 9000));
		assertPaysThenStacksThePayoff(mw, () -> p2Arrives(mw, tidus_18_009H()),
				() -> mw.gameState.getP2BreakZone().size() == 5,
				() -> mw.p1ForwardDamage.get(0) == 8000);
	}

	@Test
	void tidusMillsWhatThereIsButStacksNoPayoffShortOfFive() {
		MainWindow mw = new MainWindow();
		fireDeck(mw, 4);
		seatForward(mw, true, makeForward("Theirs", "Water", 5, 9000));
		p2Arrives(mw, tidus_18_009H());
		mw.resolveTopOfStack();
		assertEquals(4, mw.gameState.getP2BreakZone().size(), "all four still go");
		assertTrue(mw.gameState.getStack().isEmpty(), "but nothing triggers after");
		assertEquals(0, mw.p1ForwardDamage.get(0));
	}

	// =========================================================================================
	// 18-010C Berserker / 18-075R Seifer / 18-085C Dragoon / 18-095C Rune Fencer / 18-111L Basch /
	// 18-120H Tifa: each price is paid at resolution, and its payoff is placed on the stack after.
	// =========================================================================================

	@Test
	void berserkerPaysAsItResolvesAndThenStacksTheCompulsion() {
		CPUOptional.acceptsEveryOffer = true;
		MainWindow mw = new MainWindow();
		seatForward(mw, true, makeForward("Theirs", "Fire", 5, 9000));
		CardData[] fire = seatBackups(mw, false, "Fire", 1);
		p2Arrives(mw, berserker_18_010C());
		assertFalse(allDull(mw, false, fire));
		resolveToPayoff(mw);
		assertTrue(allDull(mw, false, fire), "《Fire》 paid as it resolved");
		resolveStack(mw);
	}

	@Test
	void seiferTakesTheDamageAsItResolvesAndThenStacksTheReturn() {
		CPUOptional.acceptsEveryOffer = true;
		MainWindow mw = new MainWindow();
		fillDeck(mw, false, 3);
		CardData squall = categoryForward("Squall", "Ice", 3, 7000, "VIII");
		toBreakZone(mw, false, squall);
		assertPaysThenStacksThePayoff(mw, () -> p2Arrives(mw, seifer_18_075R()),
				() -> damageZone(mw, false) == 1,
				() -> mw.gameState.getP2Hand().contains(squall));
	}

	@Test
	void dragoonPaysAsItResolvesAndThenStacksTheBreak() {
		CPUOptional.acceptsEveryOffer = true;
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Fire", 5, 9000);
		seatForward(mw, true, theirs);
		CardData[] lightning = seatBackups(mw, false, "Lightning", 5);
		assertPaysThenStacksThePayoff(mw, () -> p2Arrives(mw, dragoon_18_085C()),
				() -> allDull(mw, false, lightning),
				() -> mw.gameState.getP1BreakZone().contains(theirs));
	}

	@Test
	void runeFencerPaysAsItResolvesAndThenStacksTheSecondLoss() {
		CPUOptional.acceptsEveryOffer = true;
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Fire", 7, 15000);
		seatForward(mw, true, theirs);
		CardData[] water = seatBackups(mw, false, "Water", 3);
		p2Arrives(mw, runeFencer_18_095C());
		assertFalse(allDull(mw, false, water));
		resolveToPayoff(mw);
		assertTrue(allDull(mw, false, water));
		int before = power(mw, true, theirs);
		mw.resolveTopOfStack();
		assertEquals(before - 7000, power(mw, true, theirs));
		resolveStack(mw);
		assertEquals(4000, power(mw, true, theirs), "15000 - 4000 - 7000");
	}

	@Test
	void baschTakesTheDamageAsItResolvesAndThenStacksTheSearch() {
		CPUOptional.acceptsEveryOffer = true;
		MainWindow mw = new MainWindow();
		fillDeck(mw, false, 3);
		CardData vaan = makeForward("Vaan", "Fire", 3, 7000);
		mw.gameState.getIdentity().put(vaan, false);
		mw.gameState.getP2MainDeck().add(vaan);
		assertPaysThenStacksThePayoff(mw, () -> p2Arrives(mw, basch_18_111L()),
				() -> damageZone(mw, false) == 1,
				() -> mw.gameState.getP2Hand().contains(vaan));
	}

	@Test
	void tifaPaysAsSheResolvesAndThenStacksTheExchange() {
		CPUOptional.acceptsEveryOffer = true;
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Fire", 3, 5000);
		seatForward(mw, true, theirs);
		CardData[] backups = seatBackups(mw, false, "Earth", 2);
		assertPaysThenStacksThePayoff(mw, () -> p2Arrives(mw, tifa_18_120H()),
				() -> allDull(mw, false, backups),
				() -> mw.gameState.getP1BreakZone().contains(theirs));
	}

	// =========================================================================================
	// OPUS 17
	// =========================================================================================

	// =========================================================================================
	// 17-008H Samurai / 17-021C Red XIII / 17-023H Scholar / 17-032C Terra / 17-043H Dancer /
	// 17-086H Red Mage / 17-116C Gordon / 17-126H Rursan Reaver / 17-129H Vinera Fennes: each
	// price is paid at the point of resolution, and the payoff goes onto the stack after.
	// =========================================================================================

	@Test
	void samuraiPaysAsItResolvesAndThenStacksTheDamage() {
		CPUOptional.acceptsEveryOffer = true;
		MainWindow mw = new MainWindow();
		seatForward(mw, true, makeForward("Theirs", "Water", 5, 9000));
		CardData[] fire = seatBackups(mw, false, "Fire", 2);
		assertPaysThenStacksThePayoff(mw, () -> p2Arrives(mw, samurai_17_008H()),
				() -> allDull(mw, false, fire),
				() -> mw.p1ForwardDamage.get(0) == 8000);
	}

	@Test
	void redXiiiBackupStacksEachPayoffAfterItsOwnPrice() {
		CPUOptional.acceptsEveryOffer = true;
		MainWindow mw = new MainWindow();
		seatForward(mw, true, makeForward("Theirs", "Water", 7, 20000));
		toHand(mw, false, makeForward("Spare", "Fire", 2, 5000));
		CardData[] fire = seatBackups(mw, false, "Fire", 2);
		p2Arrives(mw, redXiii_17_021C());
		for (int i = 0; i < 2; i++) {
			resolveToPayoff(mw);
			int before = mw.p1ForwardDamage.get(0);
			mw.resolveTopOfStack();
			assertEquals(before + 7000, mw.p1ForwardDamage.get(0), "payoff " + (i + 1));
		}
		assertEquals(0, hand(mw, false), "the discard");
		assertTrue(allDull(mw, false, fire), "and the 《Fire》《1》");
	}

	@Test
	void scholarPaysAsItResolvesAndThenStacksTheRandomDiscard() {
		CPUOptional.acceptsEveryOffer = true;
		MainWindow mw = new MainWindow();
		toHand(mw, true, makeForward("Only Card", "Fire", 2, 5000));
		CardData[] ice = seatBackups(mw, false, "Ice", 1);
		assertPaysThenStacksThePayoff(mw, () -> p2Arrives(mw, scholar_17_023H()),
				() -> allDull(mw, false, ice),
				() -> hand(mw, true) == 0);
	}

	@Test
	void terraRemovesTheSummonAsItResolvesAndThenStacksTheCounter() {
		MainWindow mw = new MainWindow();
		CardData summon = makeSummon("Blizzard", "Ice", 2, "Choose 1 Forward. Dull it.");
		toBreakZone(mw, false, summon);
		CardData terra = terra_17_032C();
		assertPaysThenStacksThePayoff(mw, () -> p2Arrives(mw, terra),
				() -> mw.gameState.getP2RemovedFromGame().contains(summon),
				() -> !mw.gameState.getCounterPiles(terra).isEmpty());
	}

	@Test
	void dancerPaysAsItResolvesAndThenStacksTheActivation() {
		CPUOptional.acceptsEveryOffer = true;
		MainWindow mw = new MainWindow();
		CardData[] wind = seatBackups(mw, false, "Wind", 1);
		CardData tired = makeForward("Tired", "Wind", 3, 7000);
		seatForward(mw, false, tired);
		mw.p2ForwardStates.set(0, CardState.DULL);
		assertPaysThenStacksThePayoff(mw, () -> p2Arrives(mw, dancer_17_043H()),
				() -> allDull(mw, false, wind),
				() -> mw.p2ForwardStates.get(slot(mw, false, tired)) == CardState.ACTIVE);
	}

	@Test
	void redMagePaysAsItResolvesAndThenStacksTheBreak() {
		CPUOptional.acceptsEveryOffer = true;
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Fire", 4, 8000);
		seatForward(mw, true, theirs);
		CardData[] lightning = seatBackups(mw, false, "Lightning", 2);
		assertPaysThenStacksThePayoff(mw, () -> p2Arrives(mw, redMage_17_086H()),
				() -> allDull(mw, false, lightning),
				() -> mw.gameState.getP1BreakZone().contains(theirs));
	}

	@Test
	void gordonPaysAsItResolvesAndThenStacksTheDraw() {
		CPUOptional.acceptsEveryOffer = true;
		MainWindow mw = new MainWindow();
		fillDeck(mw, false, 4);
		CardData[] water = seatBackups(mw, false, "Water", 1);
		p2Arrives(mw, gordon_17_116C());
		assertFalse(allDull(mw, false, water));
		resolveToPayoff(mw);
		assertTrue(allDull(mw, false, water));
		int before = hand(mw, false);
		mw.resolveTopOfStack();
		assertEquals(before + 1, hand(mw, false));
	}

	@Test
	void rursanReaverPaysAsItResolvesAndThenStacksTheLoss() {
		CPUOptional.acceptsEveryOffer = true;
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Fire", 5, 9000);
		seatForward(mw, true, theirs);
		CardData[] water = seatBackups(mw, false, "Water", 2);
		assertPaysThenStacksThePayoff(mw, () -> p2Arrives(mw, rursanReaver_17_126H()),
				() -> allDull(mw, false, water),
				() -> power(mw, true, theirs) == 4000);
	}

	@Test
	void vineraFennesPaysTheCrystalAsItResolvesAndThenStacksTheLoss() {
		CPUOptional.acceptsEveryOffer = true;
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Fire", 5, 9000);
		seatForward(mw, true, theirs);
		CardData vinera = vineraFennes_17_129H();
		seatForward(mw, false, vinera);
		mw.gameState.addP2Crystals(1);
		assertPaysThenStacksThePayoff(mw, () -> mw.autoAbilityTriggers.triggerAutoAbilitiesForAttack(vinera, false),
				() -> crystals(mw, false) == 0,
				() -> power(mw, true, theirs) == 4000);
	}

	// =========================================================================================
	// 17-022H Umaro: "If there are 5 or more Category VI Characters when Umaro enters the field,
	// its auto-ability does not go onto the stack."
	// =========================================================================================

	@Test
	void umarosDiscardDoesNotGoOnTheStackWithFiveCategoryVi() {
		MainWindow mw = new MainWindow();
		for (int i = 0; i < 4; i++) seatForward(mw, false, categoryForward("VI " + i, "Ice", 2, 5000, "VI"));
		toHand(mw, false, makeForward("Kept", "Ice", 2, 5000));
		p2Arrives(mw, umaro_17_022H());
		assertTrue(mw.gameState.getStack().isEmpty());
	}

	// =========================================================================================
	// 17-081H Lyse: "When Yda is on the field, you cannot attempt to bring Lyse out. If Lyse is
	// about to enter the field due to an effect, it will return to its previous location."
	// =========================================================================================

	@Test
	void lyseStaysInHandWhileYdaIsControlled() {
		MainWindow mw = new MainWindow();
		seatForward(mw, false, makeForward("Yda", "Earth", 3, 7000));
		CardData lyse = lyse_17_081H();
		toHand(mw, false, lyse);
		respond(mw, false, "Play 1 Forward of cost 4 or less from your hand onto the field.");
		assertFalse(mw.p2ForwardCards.contains(lyse));
		assertTrue(mw.gameState.getP2Hand().contains(lyse));
	}

	// =========================================================================================
	// OPUS 16
	// =========================================================================================

	// =========================================================================================
	// 16-013H Sol / 16-026L Cloud of Darkness / 16-050H Ceodore: each price is paid at the point
	// of resolution, and its payoff is placed onto the stack after.
	// =========================================================================================

	@Test
	void solPaysTheCrystalAsItResolvesAndThenStacksThePlay() {
		CPUOptional.acceptsEveryOffer = true;
		MainWindow mw = new MainWindow();
		CardData ally = makeForward("Ally", "Fire", 3, 7000);
		toHand(mw, false, ally);
		mw.gameState.addP2Crystals(1);
		assertPaysThenStacksThePayoff(mw, () -> p2Arrives(mw, sol_16_013H()),
				() -> crystals(mw, false) == 0,
				() -> mw.p2ForwardCards.contains(ally));
	}

	@Test
	void cloudOfDarknessPaysAsItResolvesAndThenStacksTheRemoval() {
		CPUOptional.acceptsEveryOffer = true;
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Fire", 5, 9000);
		seatForward(mw, true, theirs);
		mw.gameState.addP2Crystals(2);
		assertPaysThenStacksThePayoff(mw, () -> p2Arrives(mw, cloudOfDarkness_16_026L()),
				() -> crystals(mw, false) == 0,
				() -> mw.gameState.getP1RemovedFromGame().contains(theirs));
	}

	@Test
	void ceodoreDullsAsItResolvesAndThenStacksTheBreak() {
		CPUOptional.acceptsEveryOffer = true;
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Fire", 5, 9000);
		seatForward(mw, true, theirs);
		seatForward(mw, false, categoryForward("Rosa", "Wind", 2, 5000, "IV"));
		seatForward(mw, false, categoryForward("Kain", "Wind", 2, 5000, "IV"));
		assertPaysThenStacksThePayoff(mw, () -> p2Arrives(mw, ceodore_16_050H()),
				() -> dullCount(mw, false, 3),
				() -> mw.gameState.getP1BreakZone().contains(theirs));
	}

	// =========================================================================================
	// 16-018C Lilty / 16-025C Bard / 16-052C Selkie, at Damage 5: "X is placed into the Break Zone
	// at the point of resolution. After it is placed into the Break Zone, the auto-ability … is
	// placed onto the stack."
	// =========================================================================================

	/** Five cards in P2's Damage Zone, so its "Damage 5 --" abilities are on. */
	private static void p2AtDamage5(MainWindow mw) {
		for (int i = 0; i < 5; i++) mw.gameState.getP2DamageZone().add(makeForward("Damage " + i, "Fire", 2, 5000));
	}

	@Test
	void liltyGoesToTheBreakZoneAsItResolvesAndThenStacksTheDamage() {
		CPUOptional.acceptsEveryOffer = true;
		MainWindow mw = new MainWindow();
		p2AtDamage5(mw);
		seatForward(mw, true, makeForward("Theirs", "Fire", 6, 12000));
		CardData lilty = lilty_16_018C();
		assertPaysThenStacksThePayoff(mw, () -> p2Arrives(mw, lilty),
				() -> mw.gameState.getP2BreakZone().contains(lilty),
				() -> mw.p1ForwardDamage.get(0) == 10000);
	}

	@Test
	void bardGoesToTheBreakZoneAsItResolvesAndThenStacksTheDiscard() {
		CPUOptional.acceptsEveryOffer = true;
		MainWindow mw = new MainWindow();
		p2AtDamage5(mw);
		toHand(mw, true, makeForward("A", "Fire", 2, 5000));
		toHand(mw, true, makeForward("B", "Fire", 2, 5000));
		CardData bard = bard_16_025C();
		assertPaysThenStacksThePayoff(mw, () -> p2Arrives(mw, bard),
				() -> mw.gameState.getP2BreakZone().contains(bard),
				() -> hand(mw, true) == 0);
	}

	@Test
	void selkieGoesToTheBreakZoneAsItResolvesAndThenStacksTheBreak() {
		CPUOptional.acceptsEveryOffer = true;
		MainWindow mw = new MainWindow();
		p2AtDamage5(mw);
		CardData theirs = makeForward("Theirs", "Fire", 5, 9000);
		seatForward(mw, true, theirs);
		CardData selkie = selkie_16_052C();
		assertPaysThenStacksThePayoff(mw, () -> p2Arrives(mw, selkie),
				() -> mw.gameState.getP2BreakZone().contains(selkie),
				() -> mw.gameState.getP1BreakZone().contains(theirs));
	}

	// =========================================================================================
	// 16-051L Cecil: "If a Category IV Character Card is brought out by Cecil's effect, then the
	// auto-ability "Choose up to 2 Backups. Activate them." is placed onto the stack."
	// =========================================================================================

	@Test
	void cecilActivatesBackupsWhenTheCharacterHePlaysIsCategoryIv() {
		CPUOptional.acceptsEveryOffer = true;
		MainWindow mw = new MainWindow();
		CardData[] backups = seatBackups(mw, false, "Wind", 2);
		for (int i = 0; i < 2; i++) mw.p2BackupStates[i] = CardState.DULL;
		CardData rosa = categoryForward("Rosa", "Wind", 3, 7000, "IV");
		toHand(mw, false, rosa);
		p2Arrives(mw, cecil_16_051L());
		resolveStack(mw);
		assertTrue(mw.p2ForwardCards.contains(rosa));
		assertFalse(allDull(mw, false, backups[0]), "activated");
		assertFalse(allDull(mw, false, backups[1]), "activated");
	}

	// =========================================================================================
	// 16-087C Puppetmaster / 16-101C Yuke / 16-110C Clavat: the price (a discard; at Damage 5, the
	// Backup itself into the Break Zone) is paid at the point of resolution, and the payoff is
	// placed onto the stack after.
	// =========================================================================================

	@Test
	void puppetmasterDiscardsAsItResolvesAndThenStacksTheReturn() {
		CPUOptional.acceptsEveryOffer = true;
		MainWindow mw = new MainWindow();
		toBreakZone(mw, false, makeForward("Lost", "Lightning", 3, 7000));
		toHand(mw, false, makeForward("Spare", "Lightning", 2, 5000));
		assertPaysThenStacksThePayoff(mw, () -> p2Arrives(mw, puppetmaster_16_087C()),
				() -> hand(mw, false) == 0,
				() -> hand(mw, false) == 1);
	}

	@Test
	void yukeGoesToTheBreakZoneAsItResolvesAndThenStacksTheBreak() {
		CPUOptional.acceptsEveryOffer = true;
		MainWindow mw = new MainWindow();
		p2AtDamage5(mw);
		CardData theirs = makeForward("Theirs", "Fire", 5, 9000);
		seatForward(mw, true, theirs);
		CardData yuke = yuke_16_101C();
		assertPaysThenStacksThePayoff(mw, () -> p2Arrives(mw, yuke),
				() -> mw.gameState.getP2BreakZone().contains(yuke),
				() -> mw.gameState.getP1BreakZone().contains(theirs));
	}

	@Test
	void clavatGoesToTheBreakZoneAsItResolvesAndThenStacksTheDraw() {
		CPUOptional.acceptsEveryOffer = true;
		MainWindow mw = new MainWindow();
		p2AtDamage5(mw);
		fillDeck(mw, false, 3);
		CardData clavat = clavat_16_110C();
		assertPaysThenStacksThePayoff(mw, () -> p2Arrives(mw, clavat),
				() -> mw.gameState.getP2BreakZone().contains(clavat),
				() -> hand(mw, false) == 2);
	}

	// =========================================================================================
	// OPUS 15
	// =========================================================================================

	// =========================================================================================
	// 15-012H Faris: "If you choose two Forwards which are in your Break Zone, one Power 1000 and
	// one Power 2000, and one of them is removed from the game before the point of resolution,
	// then the card left in your Break Zone cannot enter the field."
	// =========================================================================================

	/** Faris arrives on P2's side over a 1000 and a 2000 Forward in its Break Zone; the 1000 leaves first if {@code exile}. */
	private static MainWindow farisArrives(CardData small, CardData big, boolean exile) {
		MainWindow mw = new MainWindow();
		toBreakZone(mw, false, small);
		toBreakZone(mw, false, big);
		p2Arrives(mw, faris_15_012H());
		if (exile) exileFromBreakZone(mw, false, small);
		resolveStack(mw);
		return mw;
	}

	@Test
	void farisPlaysTwoForwardsTotallingThreeThousand() {
		CardData small = makeForward("Small", "Fire", 1, 1000), big = makeForward("Big", "Fire", 1, 2000);
		MainWindow mw = farisArrives(small, big, false);
		assertTrue(mw.p2ForwardCards.contains(small));
		assertTrue(mw.p2ForwardCards.contains(big));
	}

	@Test
	void farisPlaysNeitherWhenOneOfTheTwoLeftFirst() {
		CardData small = makeForward("Small", "Fire", 1, 1000), big = makeForward("Big", "Fire", 1, 2000);
		MainWindow mw = farisArrives(small, big, true);
		assertTrue(mw.gameState.getP2BreakZone().contains(big), "the one left cannot enter");
	}

	// =========================================================================================
	// 15-013L Firion / 15-034R Snow / 15-084L Robel-Akbel / 15-112R Shinryu Celestia / 15-124H
	// Relm / 15-126R Lenna: "Crystal is paid [or the Character returned or put away] at the point
	// of resolution. After it is paid, the auto-ability … goes onto the stack."
	// =========================================================================================

	@Test
	void firionPaysTheCrystalAsItResolvesAndThenStacksTheDrawAndBoost() {
		CPUOptional.acceptsEveryOffer = true;
		MainWindow mw = new MainWindow();
		fillDeck(mw, false, 2);
		mw.gameState.addP2Crystals(1);
		CardData firion = firion_15_013L();
		assertPaysThenStacksThePayoff(mw, () -> p2Arrives(mw, firion),
				() -> crystals(mw, false) == 0,
				() -> hand(mw, false) == 1 && power(mw, false, firion) == 8000);
	}

	@Test
	void snowPaysTheCrystalAsItResolvesAndThenStacksTheFreeze() {
		CPUOptional.acceptsEveryOffer = true;
		MainWindow mw = new MainWindow();
		seatForward(mw, true, makeForward("Theirs", "Fire", 5, 9000));
		mw.gameState.addP2Crystals(1);
		assertPaysThenStacksThePayoff(mw, () -> p2Arrives(mw, snow_15_034R()),
				() -> crystals(mw, false) == 0,
				() -> mw.p1ForwardFrozen.get(0));
	}

	@Test
	void bartzPaysTheCrystalsAsItResolvesAndThenStacksTheReturn() {
		CPUOptional.acceptsEveryOffer = true;
		MainWindow mw = new MainWindow();
		seatForward(mw, false, bartz_15_055H());
		CardData galuf = jobForward("Galuf", "Earth", 3, 7000, "Warrior of Light");
		seatForward(mw, false, galuf);
		mw.gameState.addP2Crystals(3);
		assertPaysThenStacksThePayoff(mw,
				() -> respond(mw, true, "Choose 1 Forward opponent controls. Break it.", fwd(false, slot(mw, false, galuf))),
				() -> crystals(mw, false) == 0,
				() -> mw.p2ForwardCards.contains(galuf));
	}

	@Test
	void robelAkbelPaysTheCrystalAsItResolvesAndThenStacksTheBreak() {
		CPUOptional.acceptsEveryOffer = true;
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Fire", 5, 9000);
		seatForward(mw, true, theirs);
		mw.gameState.addP2Crystals(1);
		assertPaysThenStacksThePayoff(mw, () -> p2Arrives(mw, robelAkbel_15_084L()),
				() -> crystals(mw, false) == 0,
				() -> mw.gameState.getP1BreakZone().contains(theirs));
	}

	@Test
	void shinryuCelestiaPutsACharacterAwayAsItResolvesAndThenStacksTheLoss() {
		CPUOptional.acceptsEveryOffer = true;
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Fire", 6, 12000);
		seatForward(mw, true, theirs);
		CardData backup = makeBackup("Spare", "Water", 2);
		seatBackup(mw, false, backup);
		assertPaysThenStacksThePayoff(mw, () -> p2Arrives(mw, shinryuCelestia_15_112R()),
				() -> !mw.gameState.getP2BreakZone().isEmpty(),
				() -> power(mw, true, theirs) <= 5000);
	}

	@Test
	void relmReturnsACharacterAsItResolvesAndThenStacksTheCrystal() {
		CPUOptional.acceptsEveryOffer = true;
		MainWindow mw = new MainWindow();
		seatForward(mw, false, makeForward("Mine", "Water", 2, 5000));
		assertPaysThenStacksThePayoff(mw, () -> p2Arrives(mw, relm_15_124H()),
				() -> hand(mw, false) == 1,
				() -> crystals(mw, false) == 1);
	}

	@Test
	void lennaPaysEachPriceAsItsAbilityResolvesAndStacksEachPlay() {
		CPUOptional.acceptsEveryOffer = true;
		MainWindow mw = new MainWindow();
		// Two cost-2 Forwards, so either ability has one to take whatever the other took.
		CardData a = makeForward("Two A", "Water", 2, 5000), b = makeForward("Two B", "Water", 2, 5000);
		toBreakZone(mw, false, a);
		toBreakZone(mw, false, b);
		mw.gameState.addP2Crystals(3);
		p2Arrives(mw, lenna_15_126R());
		assertEquals(3, crystals(mw, false), "nothing paid as the abilities go on the Stack");
		resolveToPayoff(mw);
		assertTrue(crystals(mw, false) < 3, "the first price paid as it resolved");
		resolveStack(mw);
		assertEquals(0, crystals(mw, false));
		assertTrue(mw.p2ForwardCards.contains(a));
		assertTrue(mw.p2ForwardCards.contains(b));
	}

	// =========================================================================================
	// 15-131S Wedge / 15-134S Biggs: "The auto-ability which is activated by the party attack will
	// be activated if two or more [Job (Avalanche)] Forwards are included in it, even if Forwards
	// which have other Jobs are also part of it." / "[The card] does not need to be included in the
	// party attack needed to activate the auto-ability."
	// =========================================================================================

	/** Two Job AVALANCHE Operative Forwards and a Merc on P2's field, attacking as a party. */
	private static List<CardData> avalancheParty(MainWindow mw) {
		CardData a = jobForward("Jessie", "Earth", 2, 5000, "AVALANCHE Operative");
		CardData b = jobForward("Barret", "Fire", 3, 7000, "AVALANCHE Operative");
		CardData merc = jobForward("Cloud", "Wind", 3, 7000, "Merc");
		for (CardData c : List.of(a, b, merc)) seatForward(mw, false, c);
		return List.of(a, b, merc);
	}

	@Test
	void biggsDrawsForAPartyWithTwoOperativesAndAMercThatHeIsNotIn() {
		MainWindow mw = new MainWindow();
		fillDeck(mw, false, 2);
		seatForward(mw, false, biggs_15_134S());
		mw.autoAbilityTriggers.triggerAutoAbilitiesForPartyAttack(false, avalancheParty(mw));
		resolveStack(mw);
		assertEquals(1, hand(mw, false));
	}

	@Test
	void wedgeBreaksForAPartyWithTwoOperativesAndAMercThatHeIsNotIn() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Fire", 5, 9000);
		seatForward(mw, true, theirs);
		mw.p1ForwardStates.set(0, CardState.DULL);
		seatForward(mw, false, wedge_15_131S());
		mw.autoAbilityTriggers.triggerAutoAbilitiesForPartyAttack(false, avalancheParty(mw));
		resolveStack(mw);
		assertTrue(mw.gameState.getP1BreakZone().contains(theirs));
	}

	// =========================================================================================
	// 15-135S Tseng: "The [Job (Member of the Turks)] which you put from your hand onto the field
	// does not need to be the one you just added to your hand." / "Even if in the five revealed
	// cards there is no [Job (Member of the Turks)] card, you can place a [Job (Member of the
	// Turks)] from your hand onto the field."
	// =========================================================================================

	@Test
	void tsengPlaysATurkFromHandWhenNoneWasRevealed() {
		CPUOptional.acceptsEveryOffer = true;
		MainWindow mw = new MainWindow();
		fillDeck(mw, false, 5);
		CardData elena = jobForward("Elena", "Lightning", 2, 5000, "Member of the Turks");
		toHand(mw, false, elena);
		p2Arrives(mw, tseng_15_135S());
		resolveStack(mw);
		assertTrue(mw.p2ForwardCards.contains(elena));
	}

	// =========================================================================================
	// 15-137S Rude / 15-138S Reno: "The auto-ability is put onto the stack, and if you control
	// three or less [Job (Member of the Turks)] cards before the point of resolution, then …"
	// nothing happens.
	// =========================================================================================

	/** P2's turn; {@code turk} and three more Turks on P2's field; one leaves before resolution if {@code oneLeaves}. */
	private static MainWindow turksAttackPhase(CardData turk, boolean oneLeaves) {
		MainWindow mw = new MainWindow();
		mw.gameState.startFirstTurn(GameState.Player.P2);
		seatForward(mw, true, makeForward("Theirs", "Fire", 3, 7000));
		seatForward(mw, false, turk);
		CardData elena = jobForward("Elena", "Lightning", 2, 5000, "Member of the Turks");
		seatForward(mw, false, elena);
		seatForward(mw, false, jobForward("Cissnei", "Lightning", 2, 5000, "Member of the Turks"));
		seatForward(mw, false, jobForward("Shotgun", "Lightning", 2, 5000, "Member of the Turks"));
		mw.autoAbilityTriggers.triggerAutoAbilitiesForBeginningOfAttackPhase(false);
		assertFalse(mw.gameState.getStack().isEmpty(), "four Turks: it goes on the Stack");
		if (oneLeaves) bounce(mw, elena);
		resolveStack(mw);
		return mw;
	}

	@Test
	void rudeDullsTheOpposingForwardsWithFourTurks() {
		assertEquals(CardState.DULL, turksAttackPhase(rude_15_137S(), false).p1ForwardStates.get(0));
	}

	@Test
	void rudeDullsNothingWhenATurkLeftBeforeResolution() {
		assertEquals(CardState.ACTIVE, turksAttackPhase(rude_15_137S(), true).p1ForwardStates.get(0));
	}

	@Test
	void renoGrantsHasteWithFourTurks() {
		MainWindow mw = turksAttackPhase(reno_15_138S(), false);
		assertTrue(mw.effectiveP2HasTrait(1, CardData.Trait.HASTE), "Elena");
	}

	@Test
	void renoGrantsNoHasteWhenATurkLeftBeforeResolution() {
		MainWindow mw = turksAttackPhase(reno_15_138S(), true);
		assertFalse(mw.effectiveP2HasTrait(1, CardData.Trait.HASTE));
	}

	// =========================================================================================
	// OPUS 14 / 13
	// =========================================================================================

	// =========================================================================================
	// 14-114R Luzaf: "After the Forward has been returned to its owner's hand, the auto-ability
	// "Your opponent selects 1 Forward they control. Put it into the Break Zone" goes onto the
	// stack."
	// =========================================================================================

	@Test
	void luzafReturnsHisForwardAsItResolvesAndThenStacksTheSelection() {
		// P1 holds no Forward: its selection would open a dialog.
		MainWindow mw = new MainWindow();
		CardData mine = makeForward("Mine", "Water", 2, 5000);
		seatForward(mw, false, mine);
		p2Arrives(mw, luzaf_14_114R());
		assertTrue(mw.p2ForwardCards.contains(mine));
		resolveToPayoff(mw);
		assertTrue(mw.gameState.getP2Hand().contains(mine));
		resolveStack(mw);
	}

	// =========================================================================================
	// 13-006C Xande: "Damage is taken at the point of resolution. After taking damage, the
	// auto-ability "Choose 1 Forward. Deal it 7000 damage" is placed in the stack."
	// =========================================================================================

	@Test
	void xandeTakesTheDamageAsItResolvesAndThenStacksTheHit() {
		CPUOptional.acceptsEveryOffer = true;
		MainWindow mw = new MainWindow();
		fillDeck(mw, false, 3);
		seatForward(mw, true, makeForward("Theirs", "Water", 5, 9000));
		assertPaysThenStacksThePayoff(mw, () -> p2Arrives(mw, xande_13_006C()),
				() -> damageZone(mw, false) == 1,
				() -> mw.p1ForwardDamage.get(0) == 7000);
	}

	// =========================================================================================
	// 13-016H Rubicante: "The damage from the auto-ability which activates when Rubicante is placed
	// into the Break Zone refers to the power Rubicante had just before it was placed in the Break
	// Zone." 29-088C Soldier (FFTS): "The value used to calculate damage is based on the Soldier
	// [FFTS]'s Power immediately before leaving the field."
	// =========================================================================================

	/** {@code dealer} on P2's field at Damage 3, broken by P1; the damage its farewell deals P1's 20000 Forward. */
	private static int lastPowerDealt(CardData dealer) {
		MainWindow mw = new MainWindow();
		for (int i = 0; i < 3; i++) mw.gameState.getP2DamageZone().add(makeForward("Damage " + i, "Fire", 2, 5000));
		seatForward(mw, true, makeForward("Wall", "Water", 7, 20000));
		seatForward(mw, false, dealer);
		respond(mw, true, "Choose 1 Forward opponent controls. Break it.", fwd(false, 0));
		resolveStack(mw);
		return mw.p1ForwardDamage.get(0);
	}

	@Test
	void rubicanteDealsThePowerHeHadOnTheFieldIncludingHisDamageThreeBoost() {
		assertEquals(8000, lastPowerDealt(rubicante_13_016H()), "5000 + 3000");
	}

	@Test
	void soldierFftsDealsThePowerItHadOnTheFieldIncludingItsDamageThreeBoost() {
		assertEquals(8000, lastPowerDealt(soldierFfts_29_088C()), "7000 + 1000");
	}

	// =========================================================================================
	// 13-022H Cid Randell: "Even if a Forward with "When (card) enters the field..." enters the
	// field, its auto-ability will not be activated." / "Auto-abilities for cards such as [9-036H]
	// Ghost or [10-007H] Zack which activate when a Forward other than itself enters the field
	// will not activate."
	// =========================================================================================

	@Test
	void cidRandellStopsAnOpposingForwardsOwnEntersFieldAbility() {
		MainWindow mw = new MainWindow();
		fillDeck(mw, true, 2);
		seatForward(mw, false, cidRandell_13_022H());
		placeP1Forward(mw, card("Drawer", "Fire", "Forward", 2, 5000, null, null, null, false,
				"When Drawer enters the field, draw 1 card."));
		assertTrue(mw.gameState.getStack().isEmpty());
		assertEquals(0, hand(mw, true));
	}

	@Test
	void cidRandellStopsTheOpponentsWatchersOfAForwardEntering() {
		MainWindow mw = new MainWindow();
		fillDeck(mw, true, 2);
		seatForward(mw, false, cidRandell_13_022H());
		seatForward(mw, true, card("Watcher", "Fire", "Forward", 2, 5000, null, null, null, false,
				"When a Forward enters your field, draw 1 card."));
		placeP1Forward(mw, makeForward("Arrival", "Fire", 2, 5000));
		assertTrue(mw.gameState.getStack().isEmpty());
	}

	// =========================================================================================
	// 13-027C Time Mage / 13-068C Alchemist / 13-069C Red Mage / 13-092C Sage: "If a Multi-Element
	// card is turned over, it can be added to your hand so long as it has either [Element]."
	// =========================================================================================

	/** {@code flipper} arrives on P2's side over a deck of {@code miss} then {@code hit}; whether {@code hit} reached the hand. */
	private static boolean flipStopsOn(CardData flipper, String miss, String hit) {
		MainWindow mw = new MainWindow();
		CardData a = makeForward("Miss", miss, 2, 5000), b = makeForward("Hit", hit, 3, 7000);
		for (CardData c : List.of(a, b)) { mw.gameState.getIdentity().put(c, false); mw.gameState.getP2MainDeck().add(c); }
		fillDeck(mw, false, 2);
		p2Arrives(mw, flipper);
		resolveStack(mw);
		return mw.gameState.getP2Hand().contains(b) && !mw.gameState.getP2Hand().contains(a);
	}

	@Test
	void timeMageTakesAMultiElementCardWithFireOrWater() {
		assertTrue(flipStopsOn(timeMage_13_027C(), "Earth", "Fire/Earth"));
	}

	@Test
	void alchemistTakesAMultiElementCardWithIceOrWind() {
		assertTrue(flipStopsOn(alchemist_13_068C(), "Earth", "Wind/Fire"));
	}

	@Test
	void redMageTakesAMultiElementCardWithWindOrEarth() {
		assertTrue(flipStopsOn(redMage_13_069C(), "Fire", "Earth/Water"));
	}

	@Test
	void sageTakesAMultiElementCardWithEarthOrLightning() {
		assertTrue(flipStopsOn(sage_13_092C(), "Fire", "Fire/Lightning"));
	}

	// =========================================================================================
	// 13-064R Yang: "When damage is at 3 or more, both Damage 1 and Damage 3 are active." 13-115L
	// Golbez: "When damage is at 6 points or more, both auto-abilities will activate."
	// =========================================================================================

	@Test
	void yangAtDamageThreeHasBothHisDamageOneAndDamageThreeAbilities() {
		CPUOptional.acceptsEveryOffer = true;
		MainWindow mw = new MainWindow();
		for (int i = 0; i < 3; i++) mw.gameState.getP2DamageZone().add(makeForward("Damage " + i, "Fire", 2, 5000));
		CardData monk = jobForward("Sabin", "Earth", 2, 5000, "Monk");
		toHand(mw, false, monk);
		p2Arrives(mw, yang_13_064R());
		resolveStack(mw);
		assertTrue(mw.p2ForwardCards.contains(monk), "Damage 1: the Monk was played");
		assertEquals(6000, power(mw, false, monk), "Damage 3: and gains +1000");
	}

	@Test
	void golbezAtDamageSixTriggersBothEntersFieldAbilities() {
		MainWindow mw = new MainWindow();
		for (int i = 0; i < 6; i++) mw.gameState.getP2DamageZone().add(makeForward("Damage " + i, "Fire", 2, 5000));
		fillDeck(mw, false, 4);
		p2Arrives(mw, golbez_13_115L());
		assertEquals(2, mw.gameState.stackSize());
	}

	// =========================================================================================
	// 13-086R Agrias: "Both the first and second auto-abilities will be activated if you control
	// [1-156C] Ovelia." (Ovelia is a Job Princess.)
	// =========================================================================================

	@Test
	void agriasDrawsAndWeakensBesideOvelia() {
		MainWindow mw = new MainWindow();
		fillDeck(mw, false, 2);
		CardData theirs = makeForward("Theirs", "Fire", 5, 9000);
		seatForward(mw, true, theirs);
		seatForward(mw, false, jobForward("Ovelia", "Water", 2, 5000, "Princess"));
		p2Arrives(mw, agrias_13_086R());
		resolveStack(mw);
		assertEquals(1, hand(mw, false), "the first ability: a Job Princess");
		assertEquals(2000, power(mw, true, theirs), "the second: a Card Name Ovelia");
	}

	// =========================================================================================
	// 13-127H Chime: "If a chosen Forward becomes an unsuitable target … by leaving the field
	// before the point of resolution, the remaining Forward will be returned to the owner's hand."
	// =========================================================================================

	@Test
	void chimeReturnsTheRemainingForwardWhenTheOtherLeft() {
		MainWindow mw = new MainWindow();
		CardData a = makeForward("A", "Fire", 3, 7000), b = makeForward("B", "Fire", 3, 7000);
		seatForward(mw, true, a);
		seatForward(mw, true, b);
		p2Arrives(mw, chime_13_127H());
		bounce(mw, a);
		resolveStack(mw);
		assertTrue(mw.gameState.getP1Hand().contains(b), "returned, not frozen");
		assertTrue(mw.p1ForwardCards.isEmpty());
	}

	// =========================================================================================
	// OPUS 12 / 11
	// =========================================================================================

	// =========================================================================================
	// 12-041C Dancer: "Only 1000 damage is inflicted if there are six or fewer characters with the
	// wind element at the resolution step."
	// =========================================================================================

	/** Dancer and {@code others} more Wind Characters on P2's field; the damage its action ability deals. */
	private static int dancerDealsWith(int others) {
		MainWindow mw = new MainWindow();
		seatForward(mw, true, makeForward("Theirs", "Fire", 5, 9000));
		CardData dancer = dancer_12_041C();
		seatForward(mw, false, dancer);
		for (CardData b : seatBackups(mw, false, "Wind", others)) assertNotNull(b);
		resolveActionAs(mw, false, dancer, 0);
		return mw.p1ForwardDamage.get(0);
	}

	@Test
	void dancerDealsOneThousandWithSixWindCharacters() {
		assertEquals(1000, dancerDealsWith(5));
	}

	@Test
	void dancerDealsFourThousandWithSevenWindCharacters() {
		// Five Backups is the most a field holds, so the seventh is a Forward.
		MainWindow mw = new MainWindow();
		seatForward(mw, true, makeForward("Theirs", "Fire", 5, 9000));
		CardData dancer = dancer_12_041C();
		seatForward(mw, false, dancer);
		seatForward(mw, false, makeForward("Wind Ally", "Wind", 2, 5000));
		seatBackups(mw, false, "Wind", 5);
		resolveActionAs(mw, false, dancer, 0);
		assertEquals(4000, mw.p1ForwardDamage.get(0));
	}

	// =========================================================================================
	// 12-051R Paine: "Backups are not activated if there are two or fewer category X characters
	// present at the resolution step."
	// =========================================================================================

	private static MainWindow paineArrives(boolean oneLeaves) {
		MainWindow mw = new MainWindow();
		CardData yuna = categoryForward("Yuna", "Water", 2, 5000, "X");
		seatForward(mw, false, yuna);
		seatForward(mw, false, categoryForward("Rikku", "Wind", 2, 5000, "X"));
		seatBackups(mw, false, "Wind", 1);
		mw.p2BackupStates[0] = CardState.DULL;
		p2Arrives(mw, paine_12_051R());
		if (oneLeaves) bounce(mw, yuna);
		resolveStack(mw);
		return mw;
	}

	@Test
	void paineActivatesTheBackupsWithThreeCategoryX() {
		assertEquals(CardState.ACTIVE, paineArrives(false).p2BackupStates[0]);
	}

	@Test
	void paineActivatesNothingWhenTheCountDroppedBeforeResolution() {
		assertEquals(CardState.DULL, paineArrives(true).p2BackupStates[0]);
	}

	// =========================================================================================
	// 12-054C Moogle (Crystal Hunt): "The EX burst is still triggered when Moogle (Crystal Hunt) is
	// turned over on the third point of damage."
	// =========================================================================================

	@Test
	void moogleBurstsWhenItIsTheThirdPointOfDamage() {
		MainWindow mw = new MainWindow();
		// As the damage path does once the card is revealed: it is already in the Damage Zone when
		// its EX Burst is offered.
		for (int i = 0; i < 2; i++) mw.gameState.getP2DamageZone().add(makeForward("Damage " + i, "Fire", 2, 5000));
		fillDeck(mw, false, 3);
		CardData moogle = moogleCrystalHunt_12_054C();
		mw.gameState.getIdentity().put(moogle, false);
		mw.gameState.getP2DamageZone().add(moogle);
		mw.autoAbilityTriggers.triggerExBurst(moogle, false);
		resolveStack(mw);
		assertEquals(1, hand(mw, false), "the EX Burst drew a card");
	}

	// =========================================================================================
	// 12-066C Vaigali: "A "multi-element" card is one that has two or more elements."
	// =========================================================================================

	@Test
	void vaigaliSearchesForAMultiElementCard() {
		CPUOptional.acceptsEveryOffer = true;
		MainWindow mw = new MainWindow();
		fillDeck(mw, false, 2);
		CardData multi = makeForward("Multi", "Fire/Ice", 3, 7000);
		mw.gameState.getIdentity().put(multi, false);
		mw.gameState.getP2MainDeck().add(multi);
		p2Arrives(mw, vaigali_12_066C());
		resolveStack(mw);
		assertEquals(List.of(multi), mw.gameState.getP2Hand());
	}

	// =========================================================================================
	// 12-073C Red Mage / 12-092C Ultros / 12-123R Urianger: the price is paid at the resolution
	// step, and the payoff is placed in the stack after.
	// =========================================================================================

	@Test
	void redMageGoesToTheBreakZoneAsItResolvesAndThenStacksTheBreak() {
		CPUOptional.acceptsEveryOffer = true;
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Fire", 3, 7000);
		seatForward(mw, true, theirs);
		CardData redMage = redMage_12_073C();
		seatBackup(mw, false, redMage);
		assertPaysThenStacksThePayoff(mw, () -> mw.autoAbilityTriggers.triggerAutoAbilitiesForCastSummon(false),
				() -> mw.gameState.getP2BreakZone().contains(redMage),
				() -> mw.gameState.getP1BreakZone().contains(theirs));
	}

	@Test
	void ultrosPaysAsItResolvesAndThenStacksTheSearch() {
		CPUOptional.acceptsEveryOffer = true;
		MainWindow mw = new MainWindow();
		fillDeck(mw, false, 2);
		CardData next = ultros_12_092C();
		mw.gameState.getIdentity().put(next, false);
		mw.gameState.getP2MainDeck().add(next);
		CardData[] water = seatBackups(mw, false, "Water", 1);
		seatForward(mw, false, ultros_12_092C());
		assertPaysThenStacksThePayoff(mw,
				() -> respond(mw, true, "Choose 1 Forward opponent controls. Break it.", fwd(false, 0)),
				() -> allDull(mw, false, water),
				() -> mw.gameState.getP2Hand().contains(next));
	}

	@Test
	void uriangerPlaysTheMonsterAsItResolvesAndThenStacksTheReturn() {
		CPUOptional.acceptsEveryOffer = true;
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Fire", 5, 9000);
		seatForward(mw, true, theirs);
		CardData bomb = makeMonster("Bomb", "Water", 2);
		toHand(mw, false, bomb);
		assertPaysThenStacksThePayoff(mw, () -> p2Arrives(mw, urianger_12_123R()),
				() -> mw.p2MonsterCards.contains(bomb),
				() -> mw.gameState.getP1Hand().contains(theirs));
	}

	// =========================================================================================
	// 11-015L Braska's Final Aeon: "This ability means you can cancel any "reduce damage" or
	// "damage becomes 0" effects an inflict the original damage value."
	// =========================================================================================

	@Test
	void braskasFinalAeonDealsItsFullTenThousandThroughAReduction() {
		MainWindow mw = new MainWindow();
		seatForward(mw, true, card("Reducer", "Wind", "Forward", 5, 12000, null, null, null, false,
				"If Reducer is dealt damage, reduce the damage by 2000 instead."));
		CardData aeon = braskaSFinalAeon_11_015L();
		seatForward(mw, false, aeon);
		mw.autoAbilityTriggers.triggerAutoAbilitiesForAttack(aeon, false);
		resolveStack(mw);
		assertEquals(10000, mw.p1ForwardDamage.get(0));
	}

	// =========================================================================================
	// 11-131S Barret: "The number of cards with the category "VII" is referenced at the point of
	// resolution."
	// =========================================================================================

	@Test
	void barretCountsCategoryViiAsHisAbilityResolves() {
		MainWindow mw = new MainWindow();
		seatForward(mw, true, makeForward("Theirs", "Water", 7, 15000));
		CardData tifa = categoryForward("Tifa", "Earth", 3, 7000, "VII");
		seatForward(mw, false, tifa);
		seatForward(mw, false, categoryForward("Aerith", "Wind", 3, 7000, "VII"));
		p2Arrives(mw, barret_11_131S());
		bounce(mw, tifa);
		resolveStack(mw);
		assertEquals(2000, mw.p1ForwardDamage.get(0), "Barret and Aerith at resolution");
	}

	// =========================================================================================
	// "Also Card Name X in all situations" — 29-021R Ultima Weapon Origin (Astos), 22-030C
	// Shinryu Celestia (Celestia), 21-124L Jack Garland (Garland), 14-124H and 27-013L Zeromus
	// (Zemus), 12-110L Neo Exdeath (Exdeath): "This applies whether it is in the Break Zone, Deck
	// or a player hand."
	// =========================================================================================

	/** Whether a search for "Card Name {@code alias}" from P2's deck finds {@code card}. */
	private static boolean searchFindsAs(CardData card, String alias) {
		MainWindow mw = new MainWindow();
		fillDeck(mw, false, 2);
		mw.gameState.getIdentity().put(card, false);
		mw.gameState.getP2MainDeck().add(card);
		respond(mw, false, "Search for 1 Card Name " + alias + " and add it to your hand.");
		return mw.gameState.getP2Hand().contains(card);
	}

	@Test
	void ultimaWeaponOriginIsAstosInTheDeck() {
		assertTrue(searchFindsAs(ultimaWeaponOrigin_29_021R(), "Astos"));
	}

	@Test
	void ultimaWeaponOriginIsAstosInTheBreakZone() {
		MainWindow mw = new MainWindow();
		CardData uwo = ultimaWeaponOrigin_29_021R();
		toBreakZone(mw, false, uwo);
		respond(mw, false, "Choose 1 Card Name Astos in your Break Zone. Add it to your hand.");
		assertTrue(mw.gameState.getP2Hand().contains(uwo));
	}

	@Test
	void shinryuCelestiaIsCelestiaInTheDeck() {
		assertTrue(searchFindsAs(shinryuCelestia_22_030C(), "Celestia"));
	}

	@Test
	void jackGarlandIsGarlandInTheDeck() {
		assertTrue(searchFindsAs(jackGarland_21_124L(), "Garland"));
	}

	@Test
	void zeromusIsZemusInTheDeck() {
		assertTrue(searchFindsAs(zeromus_14_124H(), "Zemus"));
		assertTrue(searchFindsAs(zeromus_27_013L(), "Zemus"));
	}

	@Test
	void neoExdeathIsExdeathInTheDeck() {
		assertTrue(searchFindsAs(neoExdeath_12_110L(), "Exdeath"));
	}

	// =========================================================================================
	// 11-052C Tiamat / 11-082C Lich / 11-111C Kraken: "[CP] is payed at the point of resolution.
	// After paying this cost, "choose one Forward…" and any subsequent auto-abilities are then
	// added to the stack."
	// =========================================================================================

	@Test
	void tiamatPaysAsItResolvesAndThenStacksTheBreak() {
		CPUOptional.acceptsEveryOffer = true;
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Fire", 5, 9000);
		seatForward(mw, true, theirs);
		CardData[] wind = seatBackups(mw, false, "Wind", 3);
		assertPaysThenStacksThePayoff(mw, () -> p2Arrives(mw, tiamat_11_052C()),
				() -> allDull(mw, false, wind),
				() -> mw.gameState.getP1BreakZone().contains(theirs));
	}

	@Test
	void lichPaysAsItResolvesAndThenStacksTheReturn() {
		CPUOptional.acceptsEveryOffer = true;
		MainWindow mw = new MainWindow();
		CardData fallen = makeForward("Fallen", "Earth", 3, 7000);
		toBreakZone(mw, false, fallen);
		CardData[] earth = seatBackups(mw, false, "Earth", 2);
		assertPaysThenStacksThePayoff(mw, () -> p2Arrives(mw, lich_11_082C()),
				() -> allDull(mw, false, earth),
				() -> mw.gameState.getP2Hand().contains(fallen));
	}

	@Test
	void krakenPaysAsItResolvesAndThenStacksTheReturn() {
		CPUOptional.acceptsEveryOffer = true;
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Fire", 5, 9000);
		seatForward(mw, true, theirs);
		CardData[] water = seatBackups(mw, false, "Water", 2);
		assertPaysThenStacksThePayoff(mw, () -> p2Arrives(mw, kraken_11_111C()),
				() -> allDull(mw, false, water),
				() -> mw.gameState.getP1Hand().contains(theirs));
	}

	// =========================================================================================
	// 11-054R Hooded Man: "It is not possible to deploy the Hooded Man to the field if Kain is
	// already deployed there. If an effect would cause this to happen then the Hooded Man would
	// remain in the Zone it is currently in."
	// =========================================================================================

	@Test
	void hoodedManStaysInHandWhileKainIsControlled() {
		MainWindow mw = new MainWindow();
		seatForward(mw, false, makeForward("Kain", "Lightning", 3, 7000));
		CardData hooded = hoodedMan_11_054R();
		toHand(mw, false, hooded);
		respond(mw, false, "Play 1 Forward of cost 4 or less from your hand onto the field.");
		assertFalse(mw.p2ForwardCards.contains(hooded));
		assertTrue(mw.gameState.getP2Hand().contains(hooded));
	}

	// =========================================================================================
	// 11-072R Tyro: "Tyro can create CP of any Element, but this does not mean that he has
	// acquired that elemental affinity itself. He remains an Earth Element card."
	// =========================================================================================

	@Test
	void tyroRemainsAnEarthCard() {
		MainWindow mw = new MainWindow();
		CardData tyro = tyro_11_072R();
		seatBackup(mw, false, tyro);
		assertTrue(mw.effectiveContainsElement(tyro, "Earth"));
		assertFalse(mw.effectiveContainsElement(tyro, "Fire"));
	}

	// =========================================================================================
	// 11-094C Zangetsu: "All character chosen will still be dulled, even if the number of job
	// "ninja" or name "ninja" cards changes at the point of resolution."
	// =========================================================================================

	@Test
	void zangetsuDullsAllHeChoseEvenWithFewerNinjaAtResolution() {
		MainWindow mw = new MainWindow();
		seatForward(mw, true, makeForward("A", "Fire", 3, 7000));
		seatForward(mw, true, makeForward("B", "Fire", 3, 7000));
		CardData ninja = jobForward("Edge", "Wind", 3, 7000, "Ninja");
		seatForward(mw, false, ninja);
		p2Arrives(mw, zangetsu_11_094C());
		bounce(mw, ninja);
		resolveStack(mw);
		assertEquals(CardState.DULL, mw.p1ForwardStates.get(0));
		assertEquals(CardState.DULL, mw.p1ForwardStates.get(1));
	}

	// =========================================================================================
	// 11-097H Nyx: "The job "Kingsglaive" card that is placed in the Break Zone is specified at the
	// point of resolution. After paying this cost, "choose one Forward…" … are then added to the
	// stack." / "It is also possible to choose to place Nyx himself in the break zone."
	// =========================================================================================

	@Test
	void nyxPutsAKingsglaiveAwayAsItResolvesAndThenStacksTheBreak() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Fire", 5, 9000);
		seatForward(mw, true, theirs);
		assertPaysThenStacksThePayoff(mw, () -> p2Arrives(mw, nyx_11_097H()),
				() -> !mw.gameState.getP2BreakZone().isEmpty(),
				() -> mw.gameState.getP1BreakZone().contains(theirs));
	}

	// =========================================================================================
	// 11-106H Owzer: "A card keeps its previous status, even after switching controlling player." /
	// "If switching control of a card results in a player having control of two or more cards with
	// the same name … all such cards will be placed in the Break Zone." / "Switching control of a
	// card does not count as that card having been deployed on the field or having left the field."
	// =========================================================================================

	@Test
	void aForwardOwzerTakesKeepsItsBoost() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Fire", 2, 5000);
		seatForward(mw, true, theirs);
		respond(mw, true, P1_BOOST, fwd(true, 0));
		p2Arrives(mw, owzer_11_106H());
		resolveStack(mw);
		assertTrue(mw.p2ForwardCards.contains(theirs));
		assertEquals(7000, power(mw, false, theirs), "+2000 until the end of the turn stays with it");
	}

	@Test
	void aForwardOwzerTakesAndItsNamesakeBothGoToTheBreakZone() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Bob", "Fire", 2, 5000);
		CardData mine = makeForward("Bob", "Water", 2, 5000);
		seatForward(mw, true, theirs);
		seatForward(mw, false, mine);
		p2Arrives(mw, owzer_11_106H());
		resolveStack(mw);
		assertTrue(mw.gameState.getP1BreakZone().contains(theirs), "to its owner's Break Zone");
		assertTrue(mw.gameState.getP2BreakZone().contains(mine));
	}

	@Test
	void aForwardOwzerTakesDoesNotEnterTheField() {
		MainWindow mw = new MainWindow();
		fillDeck(mw, false, 2);
		seatForward(mw, true, makeForward("Theirs", "Fire", 2, 5000));
		seatForward(mw, false, card("Watcher", "Water", "Forward", 2, 5000, null, null, null, false,
				"When a Forward enters your field, draw 1 card."));
		p2Arrives(mw, owzer_11_106H());
		resolveStack(mw);
		assertEquals(2, mw.p2ForwardCards.size(), "the Watcher and the Forward taken");
		assertEquals(0, hand(mw, false), "no enters-the-field trigger");
	}

	// =========================================================================================
	// 11-107C Izayoi: "The number of cards with the job "Ninja" or the name "Ninja" are referenced
	// at the point of resolution."
	// =========================================================================================

	@Test
	void izayoiCountsNinjaAsHerAbilityResolves() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Fire", 2, 5000);
		seatForward(mw, true, theirs);
		CardData ninja = jobForward("Edge", "Wind", 3, 7000, "Ninja");
		seatForward(mw, false, ninja);
		p2Arrives(mw, izayoi_11_107C());
		bounce(mw, ninja);
		resolveStack(mw);
		assertTrue(mw.p1ForwardCards.contains(theirs), "cost 2 is more than the 1 Ninja left");
	}

	// =========================================================================================
	// 10-018C Ryid / 10-064R Atlas: "When Damage 5 is active, both … field abilities are therefore
	// active." 11-137S Azul: "When the total damage is 5 or over, both the damage 3 and damage 5
	// effects are applied." 10-095H Desch: "When you have 3 or more points of damage, both Damage 1
	// and Damage 3 are active."
	// =========================================================================================

	/** {@code card} seated on P2's field with {@code damage} points in P2's Damage Zone. */
	private static MainWindow atDamage(CardData card, int damage) {
		MainWindow mw = new MainWindow();
		for (int i = 0; i < damage; i++) mw.gameState.getP2DamageZone().add(makeForward("Damage " + i, "Fire", 2, 5000));
		seatForward(mw, false, card);
		return mw;
	}

	@Test
	void ryidAtDamageFiveHasBraveAndPlusTwoThousand() {
		CardData ryid = ryid_10_018C();
		MainWindow mw = atDamage(ryid, 5);
		assertEquals(11000, power(mw, false, ryid));
		assertTrue(mw.effectiveP2HasTrait(0, CardData.Trait.BRAVE));
	}

	@Test
	void atlasAtDamageFiveHasBothBoosts() {
		CardData atlas = atlas_10_064R();
		assertEquals(10000, power(atDamage(atlas, 5), false, atlas), "7000 + 1000 + 2000");
	}

	@Test
	void azulAtDamageFiveTriggersAllThreeOfHisWatchers() {
		CardData azul = azul_11_137S();
		MainWindow mw = atDamage(azul, 5);
		seatForward(mw, true, makeForward("Theirs", "Fire", 6, 12000));
		respond(mw, true, "Choose 1 Forward opponent controls. Deal it 1000 damage.", fwd(false, 0));
		resolveStack(mw);
		assertEquals(6000, mw.p1ForwardDamage.get(0), "1000 + 2000 + 3000");
	}

	@Test
	void deschAtDamageThreeHasFirstStrikeAndHisAttackDamage() {
		CardData desch = desch_10_095H();
		MainWindow mw = atDamage(desch, 3);
		seatForward(mw, true, makeForward("Theirs", "Fire", 5, 9000));
		assertTrue(mw.effectiveP2HasTrait(0, CardData.Trait.FIRST_STRIKE), "Damage 1");
		mw.autoAbilityTriggers.triggerAutoAbilitiesForAttack(desch, false);
		resolveStack(mw);
		assertEquals(2000, mw.p1ForwardDamage.get(0), "Damage 3");
	}

	// =========================================================================================
	// 10-024R Eald'narche / 10-043H Arc / 10-103R Ramza / 11-098C Puck: "When Damage [N] is active,
	// the two auto-abilities are triggered when [it] enters the field and may be stacked in the
	// order of your choice."
	// =========================================================================================

	/** {@code card} arrives on P2's side at {@code damage}; how many abilities went on the Stack. */
	private static int arrivalTriggersAtDamage(CardData card, int damage, Consumer<MainWindow> board) {
		MainWindow mw = new MainWindow();
		for (int i = 0; i < damage; i++) mw.gameState.getP2DamageZone().add(makeForward("Damage " + i, "Fire", 2, 5000));
		board.accept(mw);
		p2Arrives(mw, card);
		return mw.gameState.stackSize();
	}

	@Test
	void ealdnarcheAtDamageFiveTriggersBothAbilities() {
		assertEquals(2, arrivalTriggersAtDamage(ealdNarche_10_024R(), 5, mw -> {
			seatForward(mw, true, makeForward("A", "Fire", 3, 7000));
			seatForward(mw, true, makeForward("B", "Fire", 3, 7000));
		}));
	}

	@Test
	void arcAtDamageThreeTriggersBothAbilities() {
		assertEquals(2, arrivalTriggersAtDamage(arc_10_043H(), 3, mw -> {
			CardData unit = jobForward("Unit", "Wind", 2, 5000, "Standard Unit");
			seatForward(mw, false, unit);
			mw.p2ForwardStates.set(0, CardState.DULL);
			toBreakZone(mw, false, jobForward("Fallen Unit", "Wind", 2, 5000, "Standard Unit"));
		}));
	}

	@Test
	void ramzaAtDamageFiveTriggersBothAbilities() {
		assertEquals(2, arrivalTriggersAtDamage(ramza_10_103R(), 5, mw -> {
			toBreakZone(mw, false, categoryForward("Agrias", "Lightning", 3, 7000, "FFT"));
			toHand(mw, false, categoryForward("Mustadio", "Lightning", 2, 5000, "FFT"));
		}));
	}

	@Test
	void puckAtDamageThreeTriggersBothAbilities() {
		assertEquals(2, arrivalTriggersAtDamage(puck_11_098C(), 3, mw -> {
			toBreakZone(mw, false, makeForward("One", "Lightning", 1, 3000));
			toBreakZone(mw, false, makeForward("Two", "Lightning", 2, 5000));
		}));
	}

	// =========================================================================================
	// 10-007H Zack: "When a Forward that is both Fire and Category VII enters the field, the
	// auto-ability is triggered twice."
	// =========================================================================================

	@Test
	void zackTriggersTwiceForAFireCategoryViiForward() {
		MainWindow mw = new MainWindow();
		seatForward(mw, true, makeForward("Theirs", "Water", 6, 12000));
		seatForward(mw, false, zack_10_007H());
		placeP2Forward(mw, categoryForward("Cloud", "Fire", 3, 7000, "VII"));
		assertEquals(2, mw.gameState.stackSize());
	}

	// =========================================================================================
	// 10-032H Scylla: "If the chosen Forward is no longer a legal target at the time of resolution
	// or has left the field, your opponent does not discard a card."
	// =========================================================================================

	@Test
	void scyllaMakesNoDiscardWhenTheChosenForwardLeft() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Fire", 3, 7000);
		seatForward(mw, true, theirs);
		mw.p1ForwardStates.set(0, CardState.DULL);
		CardData kept = makeForward("Kept", "Fire", 2, 5000);
		toHand(mw, true, kept);
		p2Arrives(mw, scylla_10_032H());
		// Broken rather than returned: a second card in P1's hand would make any discard a dialog.
		respond(mw, true, "Choose 1 Forward you control. Break it.", fwd(true, 0));
		resolveStack(mw);
		assertTrue(mw.gameState.getP1Hand().contains(kept));
	}

	// =========================================================================================
	// 10-071R Cid (MOBIUS): "The number of Forwards you control is checked at the time of
	// resolution." 9-001C Irvine / 9-009R Gijuk / 9-026C Cid Aulstyne: the count is checked at
	// resolution.
	// =========================================================================================

	@Test
	void cidMobiusCountsHisForwardsAsHisAbilityResolves() {
		MainWindow mw = new MainWindow();
		seatForward(mw, true, makeForward("Theirs", "Fire", 6, 12000));
		CardData a = makeForward("A", "Earth", 2, 5000);
		seatForward(mw, false, a);
		seatForward(mw, false, makeForward("B", "Earth", 2, 5000));
		p2Arrives(mw, cidMobius_10_071R());
		bounce(mw, a);
		resolveStack(mw);
		assertEquals(4000, mw.p1ForwardDamage.get(0), "one Forward left at resolution");
	}

	@Test
	void irvineComparesForwardCountsAsHisAbilityResolves() {
		MainWindow mw = new MainWindow();
		seatForward(mw, true, makeForward("A", "Water", 6, 12000));
		seatForward(mw, true, makeForward("B", "Water", 6, 12000));
		seatForward(mw, false, makeForward("Mine", "Fire", 2, 5000));
		p2Arrives(mw, irvine_9_001C());
		int chosen = mw.gameState.peekStack().preSelectedTargets().get(0).idx();
		bounce(mw, mw.p1ForwardCards.get(1 - chosen));
		resolveStack(mw);
		assertEquals(5000, mw.p1ForwardDamage.get(0), "one each at resolution: not greater");
	}

	@Test
	void gijukCountsHeadhuntersAsHisAbilityResolves() {
		MainWindow mw = new MainWindow();
		seatForward(mw, true, makeForward("Theirs", "Water", 6, 12000));
		CardData hh = jobForward("Ba'Gamnan", "Fire", 3, 7000, "Headhunter");
		seatForward(mw, false, hh);
		seatForward(mw, false, jobForward("Bwagi", "Fire", 2, 5000, "Headhunter"));
		seatForward(mw, false, jobForward("Rinok", "Fire", 2, 5000, "Headhunter"));
		p2Arrives(mw, gijuk_9_009R());
		assertFalse(mw.gameState.getStack().isEmpty(), "four Headhunters: it goes on the Stack");
		bounce(mw, hh);
		resolveStack(mw);
		assertEquals(0, mw.p1ForwardDamage.get(0), "three at resolution");
	}

	@Test
	void cidAulstyneChecksTheOpponentsHandAsHisAbilityResolves() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Water", 5, 9000);
		seatForward(mw, true, theirs);
		for (int i = 0; i < 3; i++) toHand(mw, true, makeForward("Hand " + i, "Water", 2, 5000));
		p2Arrives(mw, cidAulstyne_9_026C());
		for (CardData c : List.copyOf(mw.gameState.getP1Hand())) {
			mw.gameState.getP1Hand().remove(c);
			mw.gameState.getP1BreakZone().add(c);
		}
		resolveStack(mw);
		assertTrue(mw.gameState.getP1BreakZone().contains(theirs), "no cards in hand at resolution: break it");
	}

	// =========================================================================================
	// 10-115C Cecil: "Every time a Forward receives damage during the turn when Cecil used the
	// action ability, the damage is reduced by -2000. The effect doesn't trigger just once."
	// =========================================================================================

	@Test
	void cecilsShieldReducesEveryHitThatTurn() {
		MainWindow mw = new MainWindow();
		CardData cecil = cecil_10_115C();
		seatBackup(mw, false, cecil);
		seatForward(mw, false, makeForward("Mine", "Water", 5, 9000));
		resolveActionAs(mw, false, cecil, 0);
		mw.applyDamageToForward(false, 0, 3000, true, false);
		mw.applyDamageToForward(false, 0, 3000, true, false);
		assertEquals(2000, mw.p2ForwardDamage.get(0), "1000 and 1000");
	}

	// =========================================================================================
	// 10-135S Kefka: "When Kefka is the only Forward that you control and which can be chosen, you
	// must choose him."
	// =========================================================================================

	@Test
	void kefkaBreaksHimselfWhenHeIsTheOnlyForwardYouControl() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Fire", 3, 7000);
		seatForward(mw, true, theirs);
		CardData kefka = kefka_10_135S();
		seatForward(mw, false, kefka);
		mw.autoAbilityTriggers.triggerAutoAbilitiesForAttack(kefka, false);
		resolveStack(mw);
		assertTrue(mw.gameState.getP2BreakZone().contains(kefka));
		assertTrue(mw.gameState.getP1BreakZone().contains(theirs));
	}

	// =========================================================================================
	// OPUS 9
	// =========================================================================================

	// =========================================================================================
	// 9-013C Fusilier / 9-040C Nu Mou / 9-044C Viera / 9-062H Vincent / 9-071C Bangaa / 9-086R
	// Goblin / 9-090C Sage / 9-108C White Mage: "The payment … occurs at resolution. After paying,
	// the auto-ability "When you do so…" goes onto the stack and any other auto/action/special
	// abilities can then be stacked in turn."
	// =========================================================================================

	@Test
	void fusilierPaysAsItResolvesAndThenStacksTheDamage() {
		CPUOptional.acceptsEveryOffer = true;
		MainWindow mw = new MainWindow();
		seatForward(mw, true, makeForward("Theirs", "Water", 5, 9000));
		CardData[] fire = seatBackups(mw, false, "Fire", 1);
		assertPaysThenStacksThePayoff(mw, () -> p2Arrives(mw, fusilier_9_013C()),
				() -> allDull(mw, false, fire),
				() -> mw.p1ForwardDamage.get(0) == 5000);
	}

	@Test
	void nuMouPaysAsItResolvesAndThenStacksTheFreeze() {
		CPUOptional.acceptsEveryOffer = true;
		MainWindow mw = new MainWindow();
		seatForward(mw, true, makeForward("Theirs", "Water", 5, 9000));
		CardData[] ice = seatBackups(mw, false, "Ice", 1);
		assertPaysThenStacksThePayoff(mw, () -> p2Arrives(mw, nuMou_9_040C()),
				() -> allDull(mw, false, ice),
				() -> mw.p1ForwardFrozen.get(0));
	}

	@Test
	void vieraPaysAsItResolvesAndThenStacksTheDamage() {
		CPUOptional.acceptsEveryOffer = true;
		MainWindow mw = new MainWindow();
		seatForward(mw, true, makeForward("Theirs", "Water", 5, 9000));
		CardData[] wind = seatBackups(mw, false, "Wind", 1);
		assertPaysThenStacksThePayoff(mw, () -> p2Arrives(mw, viera_9_044C()),
				() -> allDull(mw, false, wind),
				() -> mw.p1ForwardDamage.get(0) == 3000);
	}

	@Test
	void vincentPutsABackupAwayAsItResolvesAndThenStacksTheDamage() {
		CPUOptional.acceptsEveryOffer = true;
		MainWindow mw = new MainWindow();
		seatForward(mw, true, makeForward("Theirs", "Water", 5, 9000));
		mw.p1ForwardStates.set(0, CardState.DULL);
		CardData backup = makeBackup("Spare", "Earth", 2);
		seatBackup(mw, false, backup);
		assertPaysThenStacksThePayoff(mw, () -> p2Arrives(mw, vincent_9_062H()),
				() -> mw.gameState.getP2BreakZone().contains(backup),
				() -> mw.p1ForwardDamage.get(0) == 8000);
	}

	@Test
	void bangaaPaysAsItResolvesAndThenStacksTheReturn() {
		CPUOptional.acceptsEveryOffer = true;
		MainWindow mw = new MainWindow();
		CardData fallen = makeForward("Fallen", "Earth", 3, 7000);
		toBreakZone(mw, false, fallen);
		CardData[] earth = seatBackups(mw, false, "Earth", 1);
		assertPaysThenStacksThePayoff(mw, () -> p2Arrives(mw, bangaa_9_071C()),
				() -> allDull(mw, false, earth),
				() -> mw.gameState.getP2Hand().contains(fallen));
	}

	@Test
	void goblinGoesToTheBreakZoneAsItResolvesAndThenStacksTheDraw() {
		CPUOptional.acceptsEveryOffer = true;
		CardData goblin = goblin_9_086R();
		MainWindow mw = p2TurnWithMonster(goblin);
		fillDeck(mw, false, 3);
		assertPaysThenStacksThePayoff(mw,
				() -> mw.autoAbilityTriggers.triggerAutoAbilitiesForDealsDamageToOpponent(goblin, false),
				() -> mw.gameState.getP2BreakZone().contains(goblin),
				() -> hand(mw, false) == 2);
	}

	@Test
	void sagePaysAsItResolvesAndThenStacksTheReturn() {
		CPUOptional.acceptsEveryOffer = true;
		MainWindow mw = new MainWindow();
		CardData summon = makeSummon("Thunder", "Lightning", 2, "Choose 1 Forward. Deal it 3000 damage.");
		toBreakZone(mw, false, summon);
		CardData[] lightning = seatBackups(mw, false, "Lightning", 1);
		assertPaysThenStacksThePayoff(mw, () -> p2Arrives(mw, sage_9_090C()),
				() -> allDull(mw, false, lightning),
				() -> mw.gameState.getP2Hand().contains(summon));
	}

	@Test
	void whiteMagePaysAsItResolvesAndThenStacksTheDraw() {
		CPUOptional.acceptsEveryOffer = true;
		MainWindow mw = new MainWindow();
		fillDeck(mw, false, 2);
		CardData[] water = seatBackups(mw, false, "Water", 1);
		assertPaysThenStacksThePayoff(mw, () -> p2Arrives(mw, whiteMage_9_108C()),
				() -> allDull(mw, false, water),
				() -> hand(mw, false) == 1);
	}

	// =========================================================================================
	// 9-023R Quistis: "If there are less than 5 cards in your deck, reveal them all." 9-067C Cid
	// Garlond: "If there are less than 4 cards in your deck, reveal them all."
	// =========================================================================================

	@Test
	void quistisRevealsWhatIsLeftOfAShortDeck() {
		MainWindow mw = new MainWindow();
		fillDeck(mw, false, 2);
		CardData squall = categoryForward("Squall", "Ice", 3, 7000, "VIII");
		mw.gameState.getIdentity().put(squall, false);
		mw.gameState.getP2MainDeck().add(squall);
		p2Arrives(mw, quistis_9_023R());
		resolveStack(mw);
		assertTrue(mw.gameState.getP2Hand().contains(squall));
		assertEquals(2, mw.gameState.getP2MainDeck().size(), "the other two back on the deck");
	}

	@Test
	void cidGarlondRevealsWhatIsLeftOfAShortDeck() {
		MainWindow mw = new MainWindow();
		fillDeck(mw, false, 1);
		CardData earth = makeForward("Earthy", "Earth", 3, 7000);
		mw.gameState.getIdentity().put(earth, false);
		mw.gameState.getP2MainDeck().add(earth);
		p2Arrives(mw, cidGarlond_9_067C());
		resolveStack(mw);
		assertTrue(mw.gameState.getP2Hand().contains(earth));
	}

	// =========================================================================================
	// 9-041R Ahriman: "During your turn and when on the field, Ahriman is of both Forward and
	// Monster types."
	// =========================================================================================

	@Test
	void ahrimanActsAsAForwardOnItsOwnersTurnAndBreaksOnHit() {
		CardData ahriman = ahriman_9_041R();
		MainWindow mw = p2TurnWithMonster(ahriman);
		CardData theirs = makeForward("Theirs", "Fire", 5, 9000);
		seatForward(mw, true, theirs);
		mw.autoAbilityTriggers.triggerAutoAbilitiesForDealsDamageToOpponent(ahriman, false);
		resolveStack(mw);
		assertTrue(mw.gameState.getP1BreakZone().contains(theirs));
	}

	// =========================================================================================
	// 9-056H The Magus Sisters: "You must remove 1 other copy of Card Name The Magus Sisters than
	// the one put into the Break Zone in order for the effect to work."
	// =========================================================================================

	@Test
	void theMagusSistersCannotRemoveThemselvesToReturn() {
		CPUOptional.acceptsEveryOffer = true;
		MainWindow mw = new MainWindow();
		CardData sisters = theMagusSisters_9_056H();
		seatForward(mw, false, sisters);
		respond(mw, true, "Choose 1 Forward opponent controls. Break it.", fwd(false, 0));
		resolveStack(mw);
		assertTrue(mw.gameState.getP2BreakZone().contains(sisters), "no other copy to remove");
		assertFalse(mw.gameState.getP2RemovedFromGame().contains(sisters));
	}

	@Test
	void theMagusSistersReturnByRemovingAnotherCopy() {
		CPUOptional.acceptsEveryOffer = true;
		MainWindow mw = new MainWindow();
		CardData copy = theMagusSisters_9_056H();
		toBreakZone(mw, false, copy);
		CardData sisters = theMagusSisters_9_056H();
		seatForward(mw, false, sisters);
		respond(mw, true, "Choose 1 Forward opponent controls. Break it.", fwd(false, 0));
		resolveStack(mw);
		assertEquals(1, mw.gameState.getP2RemovedFromGame().size());
		assertEquals(1, mw.p2ForwardCards.size(), "one Magus Sisters back on the field");
	}

	// =========================================================================================
	// 9-058L Luso: "In the case that a Forward holds more than one Element type, each of those
	// Elements count."
	// =========================================================================================

	@Test
	void lusoCountsEachElementOfAMultiElementForward() {
		MainWindow mw = new MainWindow();
		CardData luso = luso_9_058L();
		seatForward(mw, false, luso);
		seatForward(mw, false, makeForward("Fire Ice", "Fire/Ice", 3, 7000));
		assertTrue(mw.effectiveP2HasTrait(0, CardData.Trait.FIRST_STRIKE), "Wind, Fire and Ice");
		assertTrue(mw.effectiveP2HasTrait(0, CardData.Trait.BRAVE));
	}

	@Test
	void lusoGainsFirstStrikeAndBraveWithThreeSingleElementForwards() {
		MainWindow mw = new MainWindow();
		CardData luso = luso_9_058L();
		seatForward(mw, false, luso);
		seatForward(mw, false, makeForward("Fire", "Fire", 3, 7000));
		seatForward(mw, false, makeForward("Ice", "Ice", 3, 7000));
		assertTrue(mw.effectiveP2HasTrait(0, CardData.Trait.FIRST_STRIKE));
		assertTrue(mw.effectiveP2HasTrait(0, CardData.Trait.BRAVE));
	}

	// =========================================================================================
	// 9-122H Locke: "If there is only 1 card left in your deck, reveal it." 8-018R Marche / 7-120H
	// Faris: "If there are less than 5 cards in your deck, reveal them all."
	// =========================================================================================

	@Test
	void lockeRevealsTheOneCardLeft() {
		MainWindow mw = new MainWindow();
		CardData celes = categoryForward("Celes", "Ice", 3, 7000, "VI");
		mw.gameState.getIdentity().put(celes, false);
		mw.gameState.getP2MainDeck().add(celes);
		CardData locke = locke_9_122H();
		seatForward(mw, false, locke);
		resolveAutoAs(mw, false, locke, 1);
		assertTrue(mw.gameState.getP2Hand().contains(celes));
	}

	@Test
	void marcheRevealsWhatIsLeftOfAShortDeck() {
		MainWindow mw = new MainWindow();
		fillDeck(mw, false, 2);
		CardData fire = makeForward("Fiery", "Fire", 3, 7000);
		mw.gameState.getIdentity().put(fire, false);
		mw.gameState.getP2MainDeck().add(fire);
		p2Arrives(mw, marche_8_018R());
		resolveStack(mw);
		assertTrue(mw.gameState.getP2Hand().contains(fire));
	}

	@Test
	void farisRevealsWhatIsLeftOfAShortDeck() {
		MainWindow mw = new MainWindow();
		fillDeck(mw, false, 1);
		CardData galuf = categoryForward("Galuf", "Earth", 3, 7000, "V");
		mw.gameState.getIdentity().put(galuf, false);
		mw.gameState.getP2MainDeck().add(galuf);
		p2Arrives(mw, faris_7_120H());
		resolveStack(mw);
		assertTrue(mw.gameState.getP2Hand().contains(galuf));
	}

	// =========================================================================================
	// 9-121L Wol: "The Element limitation only applies when playing Wol from your hand onto the
	// field. There is no limit to playing this card onto the field from other effects." 8-074H
	// Gladiolus: "If Gladiolus enters the field due to the effect of an ability, payment is not
	// limited to Earth CP."
	// =========================================================================================

	@Test
	void wolCanBePlayedByAnEffect() {
		MainWindow mw = new MainWindow();
		CardData wol = wol_9_121L();
		toHand(mw, false, wol);
		respond(mw, false, "Play 1 Forward of cost 4 or less from your hand onto the field.");
		assertTrue(mw.p2ForwardCards.contains(wol));
	}

	@Test
	void gladiolusCanBePlayedByAnEffect() {
		MainWindow mw = new MainWindow();
		CardData gladiolus = gladiolus_8_074H();
		toHand(mw, false, gladiolus);
		respond(mw, false, "Play 1 Forward of cost 4 or less from your hand onto the field.");
		assertTrue(mw.p2ForwardCards.contains(gladiolus));
	}

	// =========================================================================================
	// 8-013C Warrior: "When using this ability multiple times, the damage increases by 2000 for
	// each time it is used."
	// =========================================================================================

	@Test
	void warriorsBoostStacksForEachUse() {
		MainWindow mw = new MainWindow();
		CardData warrior = warrior_8_013C();
		seatForward(mw, false, warrior);
		CardData blocker = makeForward("Blocker", "Water", 7, 20000);
		seatForward(mw, true, blocker);
		resolveActionAs(mw, false, warrior, 0);
		resolveActionAs(mw, false, warrior, 0);
		mw.resolveCombat(warrior, false, 0, blocker, true, 0);
		assertEquals(9000, mw.p1ForwardDamage.get(0), "5000 + 2000 + 2000");
	}

	// =========================================================================================
	// 8-056R Deathgaze (IX): "The Forward removed from the field returns immediately after Deathgaze
	// [IX] leaves the field. … If Deathgaze [IX] leaves the field prior to resolving, the chosen
	// Forward is not removed from the field. If a Forward with the same name as the Forward which
	// was removed is in your opponent's field when Deathgaze [IX] leaves the field, the Forward
	// which was removed does not return." 7-056H Dorgann carries the same three rulings.
	// =========================================================================================

	private static void p1BreaksP2Monster(MainWindow mw, CardData monster) {
		respond(mw, true, "Choose 1 Monster opponent controls. Break it.",
				new ForwardTarget(false, mw.p2MonsterCards.indexOf(monster), ForwardTarget.CardZone.MONSTER));
	}

	@Test
	void deathgazeRemovesNothingWhenItLeftFirst() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Fire", 3, 7000);
		seatForward(mw, true, theirs);
		CardData deathgaze = deathgazeIx_8_056R();
		p2Arrives(mw, deathgaze);
		p1BreaksP2Monster(mw, deathgaze);
		resolveStack(mw);
		assertTrue(mw.p1ForwardCards.contains(theirs));
	}

	@Test
	void deathgazesForwardReturnsWhenItLeaves() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Fire", 3, 7000);
		seatForward(mw, true, theirs);
		CardData deathgaze = deathgazeIx_8_056R();
		p2Arrives(mw, deathgaze);
		resolveStack(mw);
		assertTrue(mw.gameState.getP1RemovedFromGame().contains(theirs));
		p1BreaksP2Monster(mw, deathgaze);
		resolveStack(mw);
		assertTrue(mw.p1ForwardCards.contains(theirs));
	}

	@Test
	void deathgazesForwardStaysRemovedWhenItsNamesakeIsOnTheField() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Bob", "Fire", 3, 7000);
		seatForward(mw, true, theirs);
		CardData deathgaze = deathgazeIx_8_056R();
		p2Arrives(mw, deathgaze);
		resolveStack(mw);
		seatForward(mw, true, makeForward("Bob", "Water", 3, 7000));
		p1BreaksP2Monster(mw, deathgaze);
		resolveStack(mw);
		assertFalse(mw.p1ForwardCards.contains(theirs));
		assertTrue(mw.gameState.getP1RemovedFromGame().contains(theirs));
	}

	@Test
	void dorgannRemovesNothingWhenHeLeftFirst() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Fire", 3, 7000);
		seatForward(mw, true, theirs);
		CardData dorgann = dorgann_7_056H();
		seatForward(mw, false, dorgann);
		p2Arrives(mw, makeBackup("Arrival", "Wind", 2));
		assertFalse(mw.gameState.getStack().isEmpty(), "a Backup entered his field");
		bounce(mw, dorgann);
		resolveStack(mw);
		assertTrue(mw.p1ForwardCards.contains(theirs));
	}

	// =========================================================================================
	// 8-082R Prompto: "When entering the field, the two auto abilities are triggered."
	// =========================================================================================

	@Test
	void promptoTriggersBothAbilities() {
		MainWindow mw = new MainWindow();
		seatForward(mw, true, makeForward("A", "Fire", 3, 7000));
		p2Arrives(mw, prompto_8_082R());
		assertEquals(2, mw.gameState.stackSize());
	}

	// =========================================================================================
	// 8-089R Ark Angel EV: "Even if the Forward chosen can't be dulled, it can still be dealt 2000
	// damage."
	// =========================================================================================

	@Test
	void arkAngelEvStillDealsItsDamageToAForwardAlreadyDull() {
		MainWindow mw = new MainWindow();
		seatForward(mw, true, makeForward("Theirs", "Fire", 5, 9000));
		p2Arrives(mw, arkAngelEv_8_089R());
		respond(mw, true, "Choose 1 Forward you control. Dull it.", fwd(true, 0));
		resolveStack(mw);
		assertEquals(2000, mw.p1ForwardDamage.get(0));
	}

	// =========================================================================================
	// 8-092C Alphinaud: "In the case that Alphinaud is no longer on the field when resolving, this
	// is based on Alphinaud's power just before leaving the field."
	// =========================================================================================

	@Test
	void alphinaudReadsHisLastPowerWhenHeLeftBeforeResolution() {
		MainWindow mw = new MainWindow();
		seatForward(mw, true, makeForward("Theirs", "Fire", 2, 5000));
		CardData alphinaud = alphinaud_8_092C();
		seatForward(mw, false, alphinaud);
		mw.autoAbilityTriggers.triggerAutoAbilitiesForAttack(alphinaud, false);
		bounce(mw, alphinaud);
		resolveStack(mw);
		assertEquals(CardState.DULL, mw.p1ForwardStates.get(0), "5000 is no more than his last 5000");
	}

	// =========================================================================================
	// 8-118H Steiner: "If Steiner enters the field Dulled, it becomes active."
	// =========================================================================================

	@Test
	void steinerEnteringDullIsActivated() {
		MainWindow mw = new MainWindow();
		CardData steiner = steiner_8_118H();
		p2Arrives(mw, steiner);
		mw.p2ForwardStates.set(slot(mw, false, steiner), CardState.DULL);
		resolveStack(mw);
		assertEquals(CardState.ACTIVE, mw.p2ForwardStates.get(slot(mw, false, steiner)));
	}

	// =========================================================================================
	// 7-003C Red Mage / 11-085C Red Mage / 7-122C Mime: ""The cost paid to play" this card refers
	// to the cost paid to move this card from your hand onto the field. This does not include any
	// cost used to play this card using the effect of another card."
	// =========================================================================================

	/** {@code c} played onto P2's field by an effect, its arrival resolved. */
	private static MainWindow playedByEffect(CardData c, Consumer<MainWindow> board) {
		MainWindow mw = new MainWindow();
		board.accept(mw);
		toHand(mw, false, c);
		respond(mw, false, "Play 1 Forward from your hand onto the field.");
		assertTrue(mw.p2ForwardCards.contains(c));
		resolveStack(mw);
		return mw;
	}

	@Test
	void redMagePlayedByAnEffectPaidNoIceCp() {
		MainWindow mw = playedByEffect(redMage_7_003C(), m -> toHand(m, true, makeForward("Kept", "Fire", 2, 5000)));
		assertEquals(1, hand(mw, true), "no discard");
	}

	@Test
	void redMagePlayedByAnEffectPaidNoCpOfTwoElements() {
		CardData redMage = redMage_11_085C();
		MainWindow mw = playedByEffect(redMage, m -> {});
		assertEquals(7000, power(mw, false, redMage));
	}

	@Test
	void mimePlayedByAnEffectPaidNoEarthCp() {
		CardData mime = mime_7_122C();
		MainWindow mw = playedByEffect(mime, m -> {});
		assertEquals(8000, power(mw, false, mime));
	}

	// =========================================================================================
	// 7-004C Fritt: "Fritt is both a Forward and a Monster." ("also a Monster in all situations")
	// =========================================================================================

	@Test
	void frittIsAMonsterInTheBreakZone() {
		MainWindow mw = new MainWindow();
		CardData fritt = fritt_7_004C();
		toBreakZone(mw, false, fritt);
		respond(mw, false, "Choose 1 Monster in your Break Zone. Add it to your hand.");
		assertTrue(mw.gameState.getP2Hand().contains(fritt));
	}

	@Test
	void frittIsAMonsterOnTheField() {
		MainWindow mw = new MainWindow();
		CardData fritt = fritt_7_004C();
		seatForward(mw, false, fritt);
		respond(mw, true, "Choose 1 Monster opponent controls. Break it.");
		assertTrue(mw.gameState.getP2BreakZone().contains(fritt));
	}

	// =========================================================================================
	// 7-015R Fang: "This card's field abilities can be used cumulatively. If you control 6 or more
	// Category XIII characters, Fang gains +3000 power, First Strike and Haste."
	// =========================================================================================

	@Test
	void fangsThresholdsAllApplyAtSixCategoryXiii() {
		MainWindow mw = new MainWindow();
		CardData fang = fang_7_015R();
		seatForward(mw, false, fang);
		for (int i = 0; i < 5; i++) seatForward(mw, false, categoryForward("XIII " + i, "Fire", 2, 5000, "XIII"));
		assertEquals(10000, power(mw, false, fang));
		assertTrue(mw.effectiveP2HasTrait(0, CardData.Trait.FIRST_STRIKE));
		assertTrue(mw.effectiveP2HasTrait(0, CardData.Trait.HASTE));
	}

	// =========================================================================================
	// 7-132S Firion: "Even if you are in control of 4 or more Forwards, you may only deal 6000
	// damage."
	// =========================================================================================

	@Test
	void firionDealsNoMoreThanSixThousand() {
		MainWindow mw = new MainWindow();
		seatForward(mw, true, makeForward("Theirs", "Water", 6, 12000));
		for (int i = 0; i < 3; i++) seatForward(mw, false, makeForward("Ally " + i, "Fire", 2, 5000));
		p2Arrives(mw, firion_7_132S());
		resolveStack(mw);
		assertEquals(6000, mw.p1ForwardDamage.get(0));
	}

	// =========================================================================================
	// 7-034L Sephiroth: "When Sephiroth attacks his Auto Ability goes on the stack, if during the
	// resolution phase, your opponents handsize becomes 0, the chosen forward gets dulled and
	// frozen."
	// =========================================================================================

	@Test
	void sephirothChecksTheOpponentsHandAsHisAttackAbilityResolves() {
		MainWindow mw = new MainWindow();
		seatForward(mw, true, makeForward("Theirs", "Fire", 5, 9000));
		CardData only = makeForward("Only Card", "Fire", 2, 5000);
		toHand(mw, true, only);
		CardData sephiroth = sephiroth_7_034L();
		seatForward(mw, false, sephiroth);
		mw.autoAbilityTriggers.triggerAutoAbilitiesForAttack(sephiroth, false);
		mw.gameState.getP1Hand().remove(only);
		mw.gameState.getP1BreakZone().add(only);
		resolveStack(mw);
		assertEquals(CardState.DULL, mw.p1ForwardStates.get(0));
		assertTrue(mw.p1ForwardFrozen.get(0));
	}

	// =========================================================================================
	// 7-035L Serah: "Even if the number of Category XIII characters has changed by the resolution
	// phase, the selected characters will still all be dulled."
	// =========================================================================================

	@Test
	void serahDullsAllSheChoseEvenWithFewerCategoryXiiiAtResolution() {
		MainWindow mw = new MainWindow();
		seatForward(mw, true, makeForward("A", "Fire", 3, 7000));
		seatForward(mw, true, makeForward("B", "Fire", 3, 7000));
		CardData lightning = categoryForward("Lightning", "Lightning", 3, 7000, "XIII");
		seatForward(mw, false, lightning);
		p2Arrives(mw, serah_7_035L());
		bounce(mw, lightning);
		resolveStack(mw);
		assertEquals(CardState.DULL, mw.p1ForwardStates.get(0));
		assertEquals(CardState.DULL, mw.p1ForwardStates.get(1));
	}

	// =========================================================================================
	// 7-071R Shantotto: "This ability overwrites the card's Element. When Shantotto becomes the
	// specified Element, she ceases to be Earth Element."
	// =========================================================================================

	@Test
	void shantottoTakesOnOneElementInsteadOfAddingIt() {
		MainWindow mw = new MainWindow();
		CardData shantotto = shantotto_7_071R();
		seatBackup(mw, false, shantotto);
		resolveActionAs(mw, false, shantotto, 0);
		long elements = List.of("Fire", "Ice", "Wind", "Earth", "Lightning", "Water").stream()
				.filter(e -> mw.effectiveContainsElement(shantotto, e)).count();
		assertEquals(1, elements, "the named Element replaces Earth");
	}

	// =========================================================================================
	// 6-084L Leo: "Backups can produce CP of any Element, but they do not become that Element."
	// =========================================================================================

	@Test
	void leosBackupsKeepTheirOwnElement() {
		MainWindow mw = new MainWindow();
		seatForward(mw, false, leo_6_084L());
		CardData backup = makeBackup("Earthy", "Earth", 2);
		seatBackup(mw, false, backup);
		assertTrue(mw.effectiveContainsElement(backup, "Earth"));
		assertFalse(mw.effectiveContainsElement(backup, "Fire"));
	}

	// =========================================================================================
	// 7-097R Barbara: "Barbara herself also gains Haste."
	// =========================================================================================

	@Test
	void barbaraGivesHerselfHaste() {
		MainWindow mw = new MainWindow();
		seatForward(mw, false, barbara_7_097R());
		assertTrue(mw.effectiveP2HasTrait(0, CardData.Trait.HASTE));
	}

	// =========================================================================================
	// 6-012R Zell: "The damage dealt is based on Zell's power when resolving."
	// =========================================================================================

	@Test
	void zellDealsHisPowerAsItIsWhenHisAbilityResolves() {
		MainWindow mw = new MainWindow();
		seatForward(mw, true, makeForward("Theirs", "Water", 6, 12000));
		CardData zell = zell_6_012R();
		p2Arrives(mw, zell);
		respond(mw, false, "Choose 1 Forward you control. It gains +3000 power until the end of the turn.",
				fwd(false, slot(mw, false, zell)));
		resolveStack(mw);
		assertEquals(7000, mw.p1ForwardDamage.get(0), "4000 + 3000");
	}

	// =========================================================================================
	// 5-071R Leyak: "If … Leyak is removed from the field before its auto-ability resolves, it
	// can't be put into the Break Zone and therefore cannot activate the Characters you control and
	// make you draw 1 card." 5-046R Buccaboo / 5-106R Black Knight carry the same ruling.
	// =========================================================================================

	@Test
	void leyakDrawsWhenItGoesToTheBreakZone() {
		MainWindow mw = new MainWindow();
		fillDeck(mw, false, 2);
		CardData attacker = makeForward("Attacker", "Fire", 3, 7000);
		seatForward(mw, true, attacker);
		seatMonster(mw, false, leyak_5_071R());
		mw.autoAbilityTriggers.triggerAutoAbilitiesForAttack(attacker, true);
		resolveStack(mw);
		assertEquals(1, hand(mw, false));
	}

	@Test
	void leyakDoesNothingWhenItLeftTheFieldFirst() {
		MainWindow mw = new MainWindow();
		fillDeck(mw, false, 2);
		CardData attacker = makeForward("Attacker", "Fire", 3, 7000);
		seatForward(mw, true, attacker);
		CardData leyak = leyak_5_071R();
		seatMonster(mw, false, leyak);
		mw.autoAbilityTriggers.triggerAutoAbilitiesForAttack(attacker, true);
		p1BreaksP2Monster(mw, leyak);
		resolveStack(mw);
		assertEquals(0, hand(mw, false));
	}

	@Test
	void buccabooMakesNoDiscardWhenItLeftTheFieldFirst() {
		MainWindow mw = new MainWindow();
		toHand(mw, true, makeForward("A", "Fire", 2, 5000));
		toHand(mw, true, makeForward("B", "Fire", 2, 5000));
		CardData buccaboo = buccaboo_5_046R();
		seatMonster(mw, false, buccaboo);
		placeP1Forward(mw, makeForward("Arrival", "Fire", 3, 7000));
		assertFalse(mw.gameState.getStack().isEmpty(), "an opposing Character entered");
		p1BreaksP2Monster(mw, buccaboo);
		resolveStack(mw);
		assertEquals(2, hand(mw, true));
	}

	@Test
	void blackKnightBreaksAnArrivingForwardOfCostFourOrLess() {
		MainWindow mw = new MainWindow();
		CardData knight = blackKnight_5_106R();
		seatMonster(mw, false, knight);
		CardData arrival = makeForward("Arrival", "Fire", 3, 7000);
		placeP1Forward(mw, arrival);
		resolveStack(mw);
		assertTrue(mw.gameState.getP2BreakZone().contains(knight));
		assertTrue(mw.gameState.getP1BreakZone().contains(arrival));
	}

	@Test
	void blackKnightBreaksNothingWhenItLeftTheFieldFirst() {
		MainWindow mw = new MainWindow();
		CardData knight = blackKnight_5_106R();
		seatMonster(mw, false, knight);
		CardData arrival = makeForward("Arrival", "Fire", 3, 7000);
		placeP1Forward(mw, arrival);
		if (!mw.gameState.getStack().isEmpty()) p1BreaksP2Monster(mw, knight);
		resolveStack(mw);
		assertTrue(mw.p1ForwardCards.contains(arrival));
	}

	// =========================================================================================
	// 6-087R Edea: "You cannot choose this card from the Break Zone."
	// =========================================================================================

	@Test
	void edeaCannotPlayHerselfFromTheBreakZone() {
		MainWindow mw = new MainWindow();
		CardData edea = edea_6_087R();
		toBreakZone(mw, false, edea);   // where her own cost put her
		resolveActionAs(mw, false, edea, 0);
		assertTrue(mw.gameState.getP2BreakZone().contains(edea));
		assertFalse(Arrays.asList(mw.p2BackupCards).contains(edea));
	}

	// =========================================================================================
	// 6-090H Kain: "Even if control has changed, the card keeps the same status. For example, if
	// Kain is dulled he stays dulled, and effects such as "Add +1000 power until the end of turn"
	// stay in effect."
	// =========================================================================================

	@Test
	void kainKeepsHisStatusWhenTheOpponentTakesHim() {
		MainWindow mw = new MainWindow();
		CardData kain = kain_6_090H();
		seatForward(mw, false, kain);
		mw.p2ForwardStates.set(0, CardState.DULL);
		respond(mw, false, "Choose 1 Forward you control. It gains +1000 power until the end of the turn.", fwd(false, 0));
		placeP1Forward(mw, makeForward("Dark One", "Dark", 3, 7000));
		resolveStack(mw);
		assertTrue(mw.p1ForwardCards.contains(kain), "taken");
		assertEquals(CardState.DULL, mw.p1ForwardStates.get(slot(mw, true, kain)), "still dull");
		assertEquals(10000, power(mw, true, kain), "and still +1000");
	}

	// =========================================================================================
	// 6-099H Frimelda: "The number of Job Sword Saint is calculated during resolution."
	// =========================================================================================

	@Test
	void frimeldaCountsSwordSaintsAsHerAbilityResolves() {
		MainWindow mw = new MainWindow();
		seatForward(mw, true, makeForward("Theirs", "Water", 6, 12000));
		CardData orlandeau = jobForward("Orlandeau", "Lightning", 3, 7000, "Sword Saint");
		seatForward(mw, false, orlandeau);
		p2Arrives(mw, frimelda_6_099H());
		bounce(mw, orlandeau);
		resolveStack(mw);
		assertEquals(4000, mw.p1ForwardDamage.get(0), "Frimelda alone at resolution");
	}

	// =========================================================================================
	// 5-149S Amodar: ""Double the power of Amodar" means his current power plus any adjustments."
	// =========================================================================================

	@Test
	void amodarDoublesHisCurrentPower() {
		MainWindow mw = new MainWindow();
		CardData amodar = amodar_5_149S();
		seatForward(mw, false, amodar);
		respond(mw, false, "Choose 1 Forward you control. It gains +1000 power until the end of the turn.", fwd(false, 0));
		seatForward(mw, false, makeForward("Ally", "Fire", 2, 5000));
		respond(mw, true, "Choose 1 Forward opponent controls. Break it.", fwd(false, 1));
		resolveStack(mw);
		assertEquals(18000, power(mw, false, amodar), "(8000 + 1000) doubled");
	}

	// =========================================================================================
	// 5-042C Trickster: "This doesn't apply just for attack or block. Trickster doesn't receive any
	// damage from cards like [4-093R] Hecatoncheir either."
	// =========================================================================================

	@Test
	void tricksterTakesNoDamageFromAFirstStrikeForwardsAbility() {
		MainWindow mw = new MainWindow();
		seatForward(mw, false, trickster_5_042C());
		CardData striker = makeForwardWithTraits("Striker", "Fire", 7000, Set.of(CardData.Trait.FIRST_STRIKE));
		seatForward(mw, true, striker);
		String text = "Choose 1 Forward opponent controls. Deal it 5000 damage.";
		GameContext ctx = mw.buildGameContext(true);
		ctx.preloadTargets(List.of(fwd(false, 0)));
		mw.currentAbilitySource = striker;
		mw.currentAbilitySourceIsP1 = true;
		try { ActionResolver.parse(text, striker).accept(ctx); } finally { mw.currentAbilitySource = null; }
		assertEquals(0, mw.p2ForwardDamage.get(0));
	}

	// =========================================================================================
	// 5-052H Arc / 5-074H Ingus / 5-141H Refia: "If a Forward is both [of the Element] and Job
	// Warrior of Light, it still only gains +1000 power."
	// =========================================================================================

	private static int warriorOfLightBoost(CardData lord, String element) {
		MainWindow mw = new MainWindow();
		seatForward(mw, false, lord);
		CardData both = jobForward("Both", element, 3, 7000, "Warrior of Light");
		seatForward(mw, false, both);
		return power(mw, false, both);
	}

	@Test
	void arcGivesAWindWarriorOfLightOnlyOneThousand() {
		assertEquals(8000, warriorOfLightBoost(arc_5_052H(), "Wind"));
	}

	@Test
	void ingusGivesAnEarthWarriorOfLightOnlyOneThousand() {
		assertEquals(8000, warriorOfLightBoost(ingus_5_074H(), "Earth"));
	}

	@Test
	void refiaGivesAWaterWarriorOfLightOnlyOneThousand() {
		assertEquals(8000, warriorOfLightBoost(refia_5_141H(), "Water"));
	}

	// =========================================================================================
	// Action abilities whose ruling turns on what happens between use and resolution. P2 uses
	// them, so the AI pays and chooses as the ability goes on the Stack; P1 then responds.
	// =========================================================================================

	/** P2 uses {@code source}'s action ability {@code index}, dulling the Backups in {@code backupSlots} for its CP. */
	private static void p2Uses(MainWindow mw, CardData source, int index, Integer... backupSlots) {
		assertTrue(mw.autoAbilityTriggers.executeP2AbilityActivation(source.actionAbilities().get(index), source,
				() -> {}, List.of(backupSlots), List.of(), 0), "the ability reached the Stack");
		assertFalse(mw.gameState.getStack().isEmpty());
	}

	// =========================================================================================
	// 19-082H Lightning: "If the chosen Forward becomes an inappropriate target before the
	// resolution of the action ability, Lightning is not broken." / "Even if Lightning has left the
	// field before resolution of the action ability, the chosen Forward is broken."
	// =========================================================================================

	/** P2's Lightning uses her ability on P1's Forward, an Odin in hand for its cost. */
	private static CardData lightningAims(MainWindow mw, CardData theirs) {
		seatForward(mw, true, theirs);
		CardData lightning = lightning_19_082H();
		seatForward(mw, false, lightning);
		toHand(mw, false, makeSummon("Odin", "Lightning", 5, "Choose 1 Forward. Break it."));
		p2Uses(mw, lightning, 0);
		return lightning;
	}

	@Test
	void lightningIsNotBrokenWhenHerTargetLeftFirst() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Fire", 5, 9000);
		CardData lightning = lightningAims(mw, theirs);
		bounce(mw, theirs);
		resolveStack(mw);
		assertTrue(mw.p2ForwardCards.contains(lightning));
	}

	@Test
	void lightningsTargetIsBrokenEvenWhenSheLeftFirst() {
		MainWindow mw = new MainWindow();
		CardData theirs = makeForward("Theirs", "Fire", 5, 9000);
		CardData lightning = lightningAims(mw, theirs);
		bounce(mw, lightning);
		resolveStack(mw);
		assertTrue(mw.gameState.getP1BreakZone().contains(theirs));
	}

	// =========================================================================================
	// 15-092C Sonitus: "If the chosen Forward is no longer a legal target or has left the field at
	// the point of resolution, you will not receive one point of damage."
	// =========================================================================================

	private static MainWindow sonitusAims(boolean targetLeaves) {
		MainWindow mw = new MainWindow();
		fillDeck(mw, false, 3);
		CardData theirs = makeForward("Theirs", "Fire", 5, 9000);
		seatForward(mw, true, theirs);
		CardData sonitus = sonitus_15_092C();
		seatBackup(mw, false, sonitus);
		seatBackups(mw, false, "Lightning", 3);
		p2Uses(mw, sonitus, 0, 1, 2, 3);
		if (targetLeaves) bounce(mw, theirs);
		resolveStack(mw);
		return mw;
	}

	@Test
	void sonitusBreaksItsTargetAndDealsYouAPoint() {
		MainWindow mw = sonitusAims(false);
		assertEquals(1, mw.gameState.getP1BreakZone().size());
		assertEquals(1, damageZone(mw, false));
	}

	@Test
	void sonitusDealsYouNothingWhenItsTargetLeftFirst() {
		assertEquals(0, damageZone(sonitusAims(true), false));
	}

	// =========================================================================================
	// 10-037C Dendrobium: "If none of the chosen Forwards are legal targets at the time of
	// resolution or have left the field, you do not draw a card." 10-137S Lightning: the same, for
	// her special ability.
	// =========================================================================================

	@Test
	void dendrobiumDrawsNothingWhenItsTargetLeftFirst() {
		MainWindow mw = new MainWindow();
		fillDeck(mw, false, 3);
		CardData theirs = makeForward("Theirs", "Fire", 5, 9000);
		seatForward(mw, true, theirs);
		CardData dendrobium = dendrobium_10_037C();
		seatMonster(mw, false, dendrobium);
		p2Uses(mw, dendrobium, 0);
		bounce(mw, theirs);
		resolveStack(mw);
		assertEquals(0, hand(mw, false));
	}

	@Test
	void lightningsCrushingBlowDrawsNothingWhenHerTargetsLeftFirst() {
		MainWindow mw = new MainWindow();
		fillDeck(mw, false, 3);
		CardData theirs = makeForward("Theirs", "Fire", 5, 9000);
		seatForward(mw, true, theirs);
		CardData lightning = lightning_10_137S();
		seatForward(mw, false, lightning);
		toHand(mw, false, lightning_10_137S());
		p2Uses(mw, lightning, 0);
		bounce(mw, theirs);
		resolveStack(mw);
		assertEquals(0, hand(mw, false), "the copy went as the cost, and nothing was drawn");
	}

	// =========================================================================================
	// 10-139S Onion Knight: "In the case that Onion Knight is no longer on the field when the
	// ability resolves, this is based on Onion Knight's last known power just before leaving the
	// field."
	// =========================================================================================

	@Test
	void onionKnightsMeteorUsesHisLastPowerWhenHeLeftFirst() {
		MainWindow mw = new MainWindow();
		seatForward(mw, true, makeForward("Theirs", "Fire", 6, 12000));
		CardData knight = onionKnight_10_139S();
		seatForward(mw, false, knight);
		toHand(mw, false, onionKnight_10_139S());
		p2Uses(mw, knight, 1);
		bounce(mw, knight);
		resolveStack(mw);
		assertEquals(7000, mw.p1ForwardDamage.get(0));
	}

	// =========================================================================================
	// 6-020H Montblanc: "The number of Category FFTA2 Forwards is calculated during resolution."
	// =========================================================================================

	@Test
	void montblancCountsCategoryFfta2AsHisAbilityResolves() {
		MainWindow mw = new MainWindow();
		seatForward(mw, true, makeForward("Theirs", "Water", 6, 12000));
		CardData montblanc = montblanc_6_020H();
		seatBackup(mw, false, montblanc);
		seatBackups(mw, false, "Fire", 1);
		CardData luso = categoryForward("Luso", "Wind", 2, 5000, "FFTA2");
		seatForward(mw, false, luso);
		seatForward(mw, false, categoryForward("Adelle", "Wind", 2, 5000, "FFTA2"));
		p2Uses(mw, montblanc, 0, 1);
		bounce(mw, luso);
		resolveStack(mw);
		assertEquals(2000, mw.p1ForwardDamage.get(0), "one FFTA2 Forward at resolution");
	}

	// =========================================================================================
	// 6-101R Raijin: "When resolving this special ability, is still resolves even if you have lost
	// control of Card Name Seifer or Card Name Fujin."
	// =========================================================================================

	@Test
	void raijinSpecialStillResolvesWithoutSeifer() {
		MainWindow mw = new MainWindow();
		seatForward(mw, true, makeForward("Theirs", "Water", 6, 12000));
		CardData raijin = raijin_6_101R();
		seatBackup(mw, false, raijin);
		CardData seifer = makeForward("Seifer", "Lightning", 3, 7000);
		seatForward(mw, false, seifer);
		seatForward(mw, false, makeForward("Fujin", "Wind", 3, 7000));
		toHand(mw, false, raijin_6_101R());
		p2Uses(mw, raijin, 0);
		bounce(mw, seifer);
		resolveStack(mw);
		assertEquals(8000, mw.p1ForwardDamage.get(0));
	}

	// =========================================================================================
	// Casting cost — what the field and the turn make a card in hand cost. The cast itself is not
	// run: these rulings are about the number the payment dialog asks for.
	// =========================================================================================

	// =========================================================================================
	// 22-070C Ramza: "If both field abilities are applied, the cost required to cast Ramza is 0."
	// =========================================================================================

	@Test
	void ramzaCostsNothingWithAKnightAndAnAttackThisTurn() {
		MainWindow mw = new MainWindow();
		CardData knight = jobForward("Agrias", "Earth", 3, 7000, "Knight");
		seatForward(mw, false, knight);
		mw.recordAttackDeclared(knight);
		assertEquals(0, mw.castCostFor(ramza_22_070C(), false));
	}

	// =========================================================================================
	// 23-108R Fourchenault: "If the player controls both a Card Name: "Alisaie" and a Card Name:
	// "Alphinaud" card, the cost to cast Fourchenault becomes 0."
	// =========================================================================================

	@Test
	void fourchenaultCostsNothingBesideAlisaieAndAlphinaud() {
		MainWindow mw = new MainWindow();
		seatForward(mw, false, makeForward("Alisaie", "Fire", 3, 7000));
		seatForward(mw, false, makeForward("Alphinaud", "Wind", 3, 7000));
		assertEquals(0, mw.castCostFor(fourchenault_23_108R(), false));
	}

	// =========================================================================================
	// 14-130H Cloud of Darkness: "If both of the conditions to reduce cost by 2 are met, then the
	// cost will reduce by 4."
	// =========================================================================================

	@Test
	void cloudOfDarknessTakesBothReductions() {
		MainWindow mw = new MainWindow();
		toHand(mw, true, makeForward("Only Card", "Fire", 2, 5000));
		mw.buildGameContext(false).forceOpponentDiscard(1);   // P2's ability makes P1 discard
		fillDeck(mw, false, 3);
		mw.buildGameContext(false).drawCards(3);
		assertEquals(0, mw.castCostFor(cloudOfDarkness_14_130H(), false));
	}

	// =========================================================================================
	// 17-129H Vinera Fennes: "When in possession of 2 or more crystals, the cost required to cast
	// Vinera Fennes becomes 0."
	// =========================================================================================

	@Test
	void vineraFennesCostsNothingWithTwoCrystals() {
		MainWindow mw = new MainWindow();
		mw.gameState.addP2Crystals(2);
		assertEquals(0, mw.castCostFor(vineraFennes_17_129H(), false));
	}

	// =========================================================================================
	// 19-125H Mog (VI): "If you control 4 or more Category VI Characters, you do not need to pay the
	// CP cost to cast."
	// =========================================================================================

	@Test
	void mogCostsNothingWithFourCategoryVi() {
		MainWindow mw = new MainWindow();
		for (int i = 0; i < 4; i++) seatForward(mw, false, categoryForward("VI " + i, "Water", 2, 5000, "VI"));
		assertEquals(0, mw.castCostFor(mogVi_19_125H(), false));
	}

	// =========================================================================================
	// 27-076C Kain: "The cost to cast Kain will only be reduced by three, even if two Job: Dragoon or
	// card name: Dragoon cards are cast in a single turn."
	// =========================================================================================

	@Test
	void kainIsReducedByThreeOnlyHoweverManyDragoonsWereCast() {
		MainWindow mw = new MainWindow();
		mw.turn(false).castJobsThisTurn.add("dragoon");
		mw.turn(false).castNamesThisTurn.add("dragoon");
		assertEquals(2, mw.castCostFor(kain_27_076C(), false));
	}

	// =========================================================================================
	// 13-062H Bhunivelze: "Cards which have multiple elements on the field … are treated as being
	// Multi-Element cards, and so they are not counted in the effect which reduces costs."
	// =========================================================================================

	@Test
	void bhunivelzeDoesNotCountAMultiElementBackup() {
		MainWindow mw = new MainWindow();
		seatBackup(mw, false, makeBackup("Fire", "Fire", 2));
		seatBackup(mw, false, makeBackup("Ice", "Ice", 2));
		seatBackup(mw, false, makeBackup("Fire Ice", "Fire/Ice", 2));
		assertEquals(6, mw.castCostFor(bhunivelze_13_062H(), false), "Fire and Ice: 2 × 2");
	}

	// ---- end of sections ----

	// =========================================================================================
	// Card factories — each card's printed text as the card database holds it, by serial, newest
	// Opus first (the FAQ page's order)
	// =========================================================================================

	/** 30-021H Gestahlian Empire Cid. */
	private static CardData gestahlianEmpireCid_30_021H() {
		return card("Gestahlian Empire Cid", "Ice", "Backup", 2, 0, "Engineer", "VI", null, false,
				"《Dull》: Choose 3 cards in your Break Zone. Remove them from the game. If there are 3 or more "
				+ "different Elements among them, your opponent discards 1 card. You can only use this ability "
				+ "during your turn and only once per turn.");
	}

	/** 30-092R Steiner. */
	private static CardData steiner_30_092R() {
		return card("Steiner", "Water", "Forward", 4, 8000, "Knight", "IX", null, false,
				"When Steiner enters the field or attacks, if you control a Forward of an Element other than "
				+ "Water, draw 1 card.[[br]][[s]]Rend Power[[/]] 《S》《Dull》: Choose 1 Forward. Halve its power "
				+ "until the end of the turn (round down to the nearest 1000).");
	}

	/** 30-101H Wakka. */
	private static CardData wakka_30_101H() {
		return card("Wakka", "Water", "Forward", 4, 9000, "Guardian", "X", null, false,
				"When Wakka enters the field, draw 2 cards, then discard 1 card.[[br]]At the end of each of your "
				+ "turns, if you control 6 or more Category X Forwards, your opponent selects 1 Forward they "
				+ "control. Put it into the Break Zone. Draw 1 card.");
	}

	/** 30-107H Terra. */
	private static CardData terra_30_107H() {
		return card("Terra", "Fire/Ice", "Forward", 5, 7000, "Magitek Knight", "VI", null, false,
				"When Terra enters the field, choose 1 Forward opponent controls. Deal it 1000 damage for each "
				+ "Category VI Character you control. Draw 1 card.[[br]]When Terra enters the field, choose 1 "
				+ "Forward opponent controls. Deal it 1000 damage for each Category VI Character you control. Your "
				+ "opponent discards 1 card.");
	}

	/** 30-110H The Emperor. */
	private static CardData theEmperor_30_110H() {
		return card("The Emperor", "Ice/Wind", "Forward", 5, 8000, "Emperor", "II", null, false,
				"When The Emperor enters the field, activate all the Backups you control. Your opponent discards "
				+ "1 card.");
	}

	/** 30-116H Yiazmat. */
	private static CardData yiazmat_30_116H() {
		return card("Yiazmat", "Lightning/Water", "Forward", 5, 10000, "Dragon", "MBM", null, false,
				"When Yiazmat enters the field or at the beginning of Main Phase 1 during each of your turns, "
				+ "you may remove 5 Category MBM cards in your Break Zone from the game. When you do so, choose up "
				+ "to 1 Forward opponent controls and up to 1 Monster opponent controls. Break them. Yiazmat deals "
				+ "your opponent 1 point of damage.");
	}

	/** 29-021R Ultima Weapon Origin. */
	private static CardData ultimaWeaponOrigin_29_021R() {
		return card("Ultima Weapon Origin", "Ice", "Forward", 4, 9000, "Weapon", "SOPFFO", null, false,
				"Ultima Weapon Origin is also Card name Astos in all situations.[[br]]《0》: Activate Ultima "
				+ "Weapon Origin. You can only use this ability once per turn.[[br]]《Dull》: Deal 2000 damage to "
				+ "all the Forwards opponent controls.");
	}

	/** 29-088C Soldier (FFTS). */
	private static CardData soldierFfts_29_088C() {
		return genericCard("Soldier (FFTS)", "Lightning", "Forward", 3, 7000, "Standard Unit", "FFTS", null, false,
				"When Soldier (FFTS) is put from the field into the Break Zone, choose 1 Forward opponent "
				+ "controls. Deal it damage equal to Soldier (FFTS)'s power.[[br]]Damage 3 -- Soldier (FFTS) gains "
				+ "+1000 power.");
	}

	/** 28-007H Cloud. */
	private static CardData cloud_28_007H() {
		return card("Cloud", "Fire", "Forward", 4, 6000, "Strife Delivery Service", "VII", null, true,
				"[[ex]]EX BURST[[/]] When Cloud enters the field, choose 1 Category VII Character other than "
				+ "Card Name Cloud in your Break Zone. Add it to your hand. Gain 《C》.[[br]]《Dull》: Select 1 of the "
				+ "2 following actions.[[br]]\"Choose 1 Category VII Forward. It gains +2000 power until the end of "
				+ "the turn.\"[[br]]\"Gain 《C》.\"");
	}

	/** 28-016L Vivi. */
	private static CardData vivi_28_016L() {
		return card("Vivi", "Fire", "Forward", 4, 5000, "Black Mage", "IX", null, false,
				"When Vivi enters the field, choose any number of Forwards opponent controls. Divide 7000 damage "
				+ "among them as you like. If you control a Category IX Forward other than Vivi, divide 10000 "
				+ "damage among them instead. (Units must be 1000.)[[br]]《Fire》《Water》《Dull》: Choose 1 Forward. "
				+ "Deal it 9000 damage.");
	}

	/** 28-023C Kam'lanaut. */
	private static CardData kamLanaut_28_023C() {
		return card("Kam'lanaut", "Ice", "Forward", 4, 8000, "Archduke", "XI", null, true,
				"[[ex]]EX BURST[[/]] When Kam'lanaut enters the field, choose 1 Forward opponent controls. Dull "
				+ "it and Freeze it. Gain 《C》.[[br]][[s]]Frost Blade[[/]] 《S》《Dull》: Choose 1 dull Forward. Break "
				+ "it. Gain 《C》.");
	}

	/** 28-027C The Emperor. */
	private static CardData theEmperor_28_027C() {
		return card("The Emperor", "Ice", "Forward", 5, 8000, "Emperor", "DFF", "II", false,
				"When The Emperor enters the field, choose 1 dull Forward. Return it to its owner's hand. Your "
				+ "opponent discards 1 card.");
	}

	/** 28-030H Shuyin. */
	private static CardData shuyin_28_030H() {
		return card("Shuyin", "Ice", "Forward", 4, 5000, "Illusion", "X", null, false,
				"When Shuyin enters the field, select 1 of the 2 following actions.[[br]]\"Choose 1 Forward "
				+ "opponent controls. Remove it and Shuyin from the game.\"[[br]]\"Your opponent removes 2 cards in "
				+ "their hand from the game. Remove Shuyin from the game.\"");
	}

	/** 28-036R Yazoo. */
	private static CardData yazoo_28_036R() {
		return card("Yazoo", "Ice", "Forward", 3, 7000, "Remnant", "VII", null, false,
				"When Yazoo enters the field, choose 1 Job Remnant in your Break Zone. Remove it from the game. "
				+ "Gain 《C》《C》.[[br]]《C》: Choose 1 Character. Dull it and Freeze it. You can only use this ability "
				+ "during your turn and only once per turn.");
	}

	/** 28-038R Loz. */
	private static CardData loz_28_038R() {
		return card("Loz", "Ice", "Forward", 3, 7000, "Remnant", "VII", null, false,
				"When Loz enters the field, choose 1 Job Remnant in your Break Zone. Remove it from the game. "
				+ "Gain 《C》《C》.[[br]]《C》: Choose 1 Ice Forward. It gains +2000 power until the end of the turn. "
				+ "You can only use this ability once per turn.");
	}

	/** 28-054L Yuffie. */
	private static CardData yuffie_28_054L() {
		return card("Yuffie", "Wind", "Forward", 4, 8000, "Ninja", "VII", null, false,
				"When Yuffie enters the field, choose 2 cards in your opponent's Break Zone. Remove them from "
				+ "the game. Gain 《C》《C》.[[br]]《C》: Until the end of the turn, Yuffie gains +1000 power and "
				+ "Haste.[[br]]《C》: Until the end of the turn, Yuffie gains \"Yuffie cannot be chosen by your "
				+ "opponent's abilities.\"[[br]]《C》《C》: Choose 1 Forward. Deal it 8000 damage.");
	}

	/** 28-062C Vincent. */
	private static CardData vincent_28_062C() {
		return card("Vincent", "Earth", "Forward", 7, 8000, "Gunslinger", "VII", null, true,
				"[[ex]]EX BURST[[/]] When Vincent enters the field, choose 1 dull Forward. Break it. Gain "
				+ "《C》.[[br]]《C》: Vincent gains First Strike until the end of the turn.");
	}

	/** 28-069L Prishe. */
	private static CardData prishe_28_069L() {
		return card("Prishe", "Earth", "Forward", 6, 9000, "Abhorrent One/Monk", "XI", null, false,
				"Before paying the cost to cast Prishe, you can remove 1 Card Name Prishe in your Break Zone "
				+ "from the game to reduce the cost required to cast Prishe by 5.[[br]]When Prishe enters the "
				+ "field, choose 1 Forward opponent controls. Prishe and the chosen Forward deal damage equal to "
				+ "their respective power to the other.[[br]][[s]]Psychoanima[[/]] 《S》: Prishe gains \"Prishe "
				+ "cannot be broken.\" until the end of the turn.");
	}

	/** 28-074C Rikku. */
	private static CardData rikku_28_074C() {
		return card("Rikku", "Earth", "Forward", 5, 8000, "Gullwings/White Mage", "X", null, false,
				"When Rikku enters the field, choose 1 Category X Forward of cost 2 or less in your Break Zone. "
				+ "You may play it onto the field. If you do so, Rikku deals you 1 point of damage.");
	}

	/** 28-082R Cait Sith. */
	private static CardData caitSith_28_082R() {
		return card("Cait Sith", "Lightning", "Backup", 3, 0, "Spy", "VII", null, true,
				"[[ex]]EX BURST[[/]] When Cait Sith enters the field, choose 1 Forward opponent controls. Dull "
				+ "it. Draw 1 card.");
	}

	/** 28-111H Yuna. */
	private static CardData yuna_28_111H() {
		return card("Yuna", "Water", "Forward", 4, 8000, "Gullwings/Songstress", "X", null, false,
				"When a Forward of an Element other than Water enters your field, choose 1 Forward opponent "
				+ "controls. It loses 4000 power until the end of the turn.[[br]]Dull active Yuna and 1 active "
				+ "Category X Character: Search for 1 Job Gullwings Forward and play it onto the field. You can "
				+ "only use this ability during your turn and only once per turn.");
	}

	/** 27-006R Chaos Advent. */
	private static CardData chaosAdvent_27_006R() {
		return card("Chaos Advent", "Fire", "Forward", 4, 8000, "Warrior", "SOPFFO", null, false,
				"When Chaos Advent is put from the field into the Break Zone, reveal the top 7 cards of your "
				+ "deck. Play up to 1 Category SOPFFO Forward among them onto the field. Then, shuffle the other "
				+ "cards revealed and return them to the bottom of your deck. The Forward's Element becomes Dark "
				+ "and it gains Job Chaos. (This effect does not end at the end of the turn.)");
	}

	/** 27-012H Zell. */
	private static CardData zell_27_012H() {
		return card("Zell", "Fire", "Forward", 4, 8000, "SeeD Candidate", "VIII", null, true,
				"[[ex]]EX BURST[[/]] When Zell enters the field, choose any number of Forwards. Divide 5000 "
				+ "damage among them as you like. If you control 4 or more Category VIII Characters, divide 9000 "
				+ "damage among them as you like instead. (Units must be 1000.)[[br]]Damage 3 -- Zell gains Haste.");
	}

	/** 27-013L Zeromus. */
	private static CardData zeromus_27_013L() {
		return card("Zeromus", "Fire", "Forward", 4, 8000, "Lunarian", "MOBIUS", "IV", false,
				"Zeromus is also Card Name Zemus in all situations.[[br]]When 1 or more Characters you control "
				+ "are chosen by your opponent's Summon or ability, if your opponent doesn't pay 《4》 or 《C》, "
				+ "cancel its effects.");
	}

	/** 27-017R Luneth. */
	private static CardData luneth_27_017R() {
		return card("Luneth", "Fire", "Forward", 4, 5000, "Warrior of Light", "III", null, false,
				"When Luneth enters the field, reveal the top card of your deck. If it is a Character of cost 3 "
				+ "or less, play it onto the field.[[br]][[s]]Minuet[[/]] 《S》《Fire》: All the Forwards of cost 3 or "
				+ "less you control gain Haste until the end of the turn.");
	}

	/** 27-049R Balthier. */
	private static CardData balthier_27_049R() {
		return card("Balthier", "Wind", "Backup", 2, 0, "Sky Pirate", "XII", null, false,
				"When Balthier enters the field, choose 1 Forward opponent controls. Deal it 4000 "
				+ "damage.[[br]]When Balthier enters the field, you may pay 《Wind》《Wind》《1》. When you do so, "
				+ "choose 1 Forward opponent controls. Deal it 4000 damage.");
	}

	/** 27-074H Adel (VIII). */
	private static CardData adelViii_27_074H() {
		return card("Adel (VIII)", "Lightning", "Forward", 4, 8000, "Witch", "VIII", null, false,
				"When Adel (VIII) enters the field, you may remove 1 Job Witch in your Break Zone from the game. "
				+ "When you do so, Adel (VIII) gains +1000 power and \"Adel (VIII) cannot be chosen by your "
				+ "opponent's abilities of Characters of cost 4 or less.\" (This effect does not end at the end of "
				+ "the turn.)[[br]]When Adel (VIII) attacks, choose 1 Forward opponent controls. Break it.");
	}

	/** 27-076C Kain. */
	private static CardData kain_27_076C() {
		return card("Kain", "Lightning", "Forward", 5, 9000, "Dragoon", "DFF", "IV", false,
				"If you have cast a Job Dragoon or Card Name Dragoon this turn, the cost required to cast Kain "
				+ "is reduced by 3.[[br]]《Lightning》: Until the end of the turn, Kain gains Haste and First "
				+ "Strike. You can only use this ability during your turn.");
	}

	/** 27-106C Mog (VI). */
	private static CardData mogVi_27_106C() {
		return card("Mog (VI)", "Water", "Forward", 1, 3000, "Warrior/Moogle", "PICTLOGICA", "VI", false,
				"When Mog (VI) enters the field, you may remove 5 cards in your Break Zone from the game. When "
				+ "you do so, draw 1 card.");
	}

	/** 26-019C Behemoth. */
	private static CardData behemoth_26_019C() {
		return genericCard("Behemoth", "Fire", "Monster", 2, 7000, "Behemoth", "VII", null, false,
				"During your turn, Behemoth also becomes a Forward with 7000 power, Haste and \"When Behemoth "
				+ "deals damage to your opponent, put Behemoth into the Break Zone. When you do so, choose 1 "
				+ "Forward opponent controls. Deal it 8000 damage.\"");
	}

	/** 26-023C Wol. */
	private static CardData wol_26_023C() {
		return card("Wol", "Ice", "Forward", 5, 9000, "Warrior of Light", "PICTLOGICA", "MOBIUS", false,
				"During this turn, if your opponent has discarded a card due to your Summons or abilities, the "
				+ "cost required to cast Wol is reduced by 2.[[br]]When Wol attacks, you may discard 2 cards. When "
				+ "you do so, choose 1 Forward. Break it.");
	}

	/** 26-026R Kuja. */
	private static CardData kuja_26_026R() {
		return card("Kuja", "Ice", "Forward", 5, 9000, "Genome", "IX", null, false,
				"When Kuja enters the field, you may remove 1 Ice Backup you control from the game. When you do "
				+ "so, your opponent discards 2 cards.[[br]]When Kuja is chosen by your opponent's Summon or "
				+ "ability, if your opponent doesn't discard 1 card, cancel its effect.");
	}

	/** 26-028C Cripshay. */
	private static CardData cripshay_26_028C() {
		return genericCard("Cripshay", "Ice", "Monster", 2, 7000, "Cripshay", "VII", null, false,
				"During your turn, Cripshay also becomes a Forward with 7000 power, First Strike and \"When "
				+ "Cripshay deals damage to your opponent, put Cripshay into the Break Zone. When you do so, your "
				+ "opponent discards 2 cards.\"");
	}

	/** 26-037H Halone. */
	private static CardData halone_26_037H() {
		return card("Halone", "Ice", "Forward", 5, 9000, "The Twelve", "XIV", null, false,
				"When Halone enters the field or attacks, choose up to 2 Characters. If you control 6 or more "
				+ "Job The Twelve, dull them and Freeze them. Your opponent discards 1 card.");
	}

	/** 26-044C Sky Samurai. */
	private static CardData skySamurai_26_044C() {
		return genericCard("Sky Samurai", "Wind", "Forward", 3, 7000, "Standard Unit", "FFTS", null, false,
				"When Sky Samurai attacks, choose up to 2 Backups. Activate them. Sky Samurai gains +2000 power "
				+ "until the end of the turn.");
	}

	/** 26-056C Gorger. */
	private static CardData gorger_26_056C() {
		return genericCard("Gorger", "Wind", "Monster", 2, 7000, "Gorger", "VII", null, false,
				"During your turn, Gorger also becomes a Forward with 7000 power, \"Gorger cannot be blocked by a "
				+ "Forward of cost 3 or more.\" and \"When Gorger deals damage to your opponent, put Gorger into the "
				+ "Break Zone. When you do so, choose up to 3 Backups. Activate them. Draw 1 card.\"");
	}

	/** 26-064R Ignis. */
	private static CardData ignis_26_064R() {
		return card("Ignis", "Earth", "Forward", 3, 7000, "Retainer", "XV", null, false,
				"When Ignis enters the field, you may dull 2 active Forwards you control. When you do so, choose "
				+ "1 Forward. Deal it damage equal to the total power of the Forwards you dulled due to this "
				+ "ability.[[br]]Damage 3 -- Ignis gains +2000 power.");
	}

	/** 26-065L Vanille. */
	private static CardData vanille_26_065L() {
		return card("Vanille", "Earth", "Forward", 1, 4000, "Medic/L'Cie", "XIII", null, false,
				"When Vanille is put from the field into the Break Zone, you may remove Vanille from the game. "
				+ "When you do so, choose 1 Category XIII Forward in your Break Zone. Add it to your "
				+ "hand.[[br]]《Dull》: Reveal the top card of your deck. If it is a Category XIII card, add it to "
				+ "your hand. You can only use this ability once per turn.");
	}

	/** 26-074C Tama. */
	private static CardData tama_26_074C() {
		return card("Tama", "Earth", "Backup", 2, 0, "Companion", "WOFF", null, false,
				"When Tama enters the field, select 1 of the 2 following actions.[[br]]\"Gain 《C》.\"[[br]]\"You may "
				+ "pay 《C》. When you do so, choose 1 Category WOFF Character in your Break Zone. Add it to your "
				+ "hand.\"");
	}

	/** 26-080C Mandragora. */
	private static CardData mandragora_26_080C() {
		return genericCard("Mandragora", "Earth", "Monster", 2, 7000, "Mandragora", "XI", null, false,
				"During your turn, Mandragora also becomes a Forward with 7000 power, \"When Mandragora deals "
				+ "damage to a Forward, break it.\" and \"When Mandragora deals damage to your opponent, put "
				+ "Mandragora into the Break Zone. When you do so, choose 1 card in your Break Zone. Add it to "
				+ "your hand.\"");
	}

	/** 26-090C Xiphos. */
	private static CardData xiphos_26_090C() {
		return genericCard("Xiphos", "Lightning", "Monster", 2, 7000, "Xiphos", "X", null, false,
				"During your turn, Xiphos also becomes a Forward with 7000 power, \"When Xiphos is blocked, all "
				+ "the Forwards opponent controls lose 3000 power until the end of the turn.\" and \"When Xiphos "
				+ "deals damage to your opponent, put Xiphos into the Break Zone. When you do so, choose 1 Forward "
				+ "of cost 4 or less opponent controls. Break it.\"");
	}

	/** 26-091C Sleipnir. */
	private static CardData sleipnir_26_091C() {
		return card("Sleipnir", "Lightning", "Forward", 2, 5000, "Knight", "XVI", null, false,
				"When Sleipnir enters the field, you may reveal 1 Dark card from your hand. If you do so, draw 1 "
				+ "card.[[br]]When a Dark Forward enters your field, until the end of the turn, Sleipnir gains "
				+ "+3000 power and Haste.");
	}

	/** 26-092H Serah. */
	private static CardData serah_26_092H() {
		return card("Serah", "Lightning", "Forward", 4, 8000, "L'Cie", "XIII", null, false,
				"When Serah enters the field or deals damage to your opponent, choose 1 Forward of cost 4 or "
				+ "less opponent controls. If you control 2 or more Category XIII Forwards, break it.[[br]]《0》: If "
				+ "you control 3 or more Category XIII Forwards, Serah deals your opponent 1 point of damage. You "
				+ "can only use this ability during your turn and only once per turn.");
	}

	/** 26-105C Garnet. */
	private static CardData garnet_26_105C() {
		return card("Garnet", "Water", "Forward", 2, 3000, "Summoner", "PICTLOGICA", "IX", false,
				"When Garnet enters the field, you may dull 4 active Characters you control. When you do so, "
				+ "choose 1 Forward. Put it at the bottom of its owner's deck.");
	}

	/** 26-108C Thaliak. */
	private static CardData thaliak_26_108C() {
		return card("Thaliak", "Water", "Forward", 3, 5000, "The Twelve", "XIV", null, false,
				"When Thaliak enters the field, if you control 4 or more Job The Twelve, your opponent selects 1 "
				+ "Forward they control. Put it into the Break Zone. Draw 1 card.[[br]][[s]]Rheognosis[[/]] 《S》: "
				+ "Choose 1 Forward of cost 3 or less opponent controls. Return it to its owner's hand.");
	}

	/** 26-114H Asura, Manusya of War. */
	private static CardData asuraManusyaOfWar_26_114H() {
		return card("Asura, Manusya of War", "Water", "Forward", 8, 8000, "Primal", "XIV", null, false,
				"When Asura, Manusya of War enters the field, you may return 1 Character other than Asura, "
				+ "Manusya of War you control to its owner's hand. When you do so, all the Forwards other than "
				+ "Asura, Manusya of War lose 8000 power until the end of the turn.[[br]]When Asura, Manusya of "
				+ "War attacks, choose up to 6 Forwards. They lose 4000 power until the end of the turn.");
	}

	/** 26-116C Malboro. */
	private static CardData malboro_26_116C() {
		return genericCard("Malboro", "Water", "Monster", 2, 7000, "Malboro", "XII", null, false,
				"During your turn, Malboro also becomes a Forward with 7000 power, \"When Malboro attacks, choose "
				+ "1 Forward opponent controls. It loses 2000 power until the end of the turn.\" and \"When Malboro "
				+ "deals damage to your opponent, put Malboro into the Break Zone. When you do so, all the "
				+ "Forwards opponent controls lose 4000 power until the end of the turn.\"");
	}

	/** 26-117R Yuna. */
	private static CardData yuna_26_117R() {
		return card("Yuna", "Water", "Forward", 3, 7000, "Gullwings", "X", null, false,
				"When Yuna enters the field or attacks, you may put the top 2 cards of your deck into the Break "
				+ "Zone. When you do so, choose 1 Forward. It loses 5000 power until the end of the "
				+ "turn.[[br]]Damage 6 -- When Yuna attacks, until the end of the turn, all the Forwards opponent "
				+ "controls lose 5000 power for every 10 cards in your Break Zone.");
	}

	/** 25-003C AVALANCHE Member. */
	private static CardData avalancheMember_25_003C() {
		return genericCard("AVALANCHE Member", "Fire", "Forward", 2, 5000, "Standard Unit", "VII", null, false,
				"When AVALANCHE Member enters the field, you may discard 1 Category VII Character. When you do "
				+ "so, choose 1 Forward. Deal it 7000 damage.");
	}

	/** 25-019C Necron. */
	private static CardData necron_25_019C() {
		return card("Necron", "Ice", "Forward", 4, 8000, "Necron", "PICTLOGICA", "IX", false,
				"When Necron enters the field, choose 1 Forward of cost 5 or less opponent controls. Remove it "
				+ "from the game for as long as Necron is on the field. [[br]]《Ice》《Dull》: Choose 1 card removed "
				+ "by Necron's ability. Put it into the Break Zone.");
	}

	/** 25-052R Ursula. */
	private static CardData ursula_25_052R() {
		return card("Ursula", "Earth", "Forward", 2, 5000, "Princess/Monk", "IV", null, false,
				"When Ursula enters the field by Summons or abilities, you may search for 1 Job Monk or Card "
				+ "Name Monk and add it to your hand.[[br]]When Ursula attacks, choose up to 1 Forward opponent "
				+ "controls. Ursula and the chosen Forward deal damage equal to their respective power to the "
				+ "other.");
	}

	/** 25-079C SOLDIER Candidate. */
	private static CardData soldierCandidate_25_079C() {
		return genericCard("SOLDIER Candidate", "Lightning", "Forward", 2, 5000, "Standard Unit", "VII", null, false,
				"When SOLDIER Candidate is put from the field into the Break Zone, you may pay 《1》. When you do "
				+ "so, search for 1 Card Name SOLDIER Candidate and play it onto the field.");
	}

	/** 25-086R Abzu. */
	private static CardData abzu_25_086R() {
		return card("Abzu", "Water", "Monster", 3, 7000, "Corneo's Pet", "VII", null, false,
				"The power of Forwards opponent controls cannot be increased by Summons or "
				+ "abilities.[[br]]During your turn, Abzu also becomes a Forward with 7000 power and \"When Abzu "
				+ "attacks, choose 1 Forward. It loses 5000 power until the end of the turn.\"");
	}

	/** 25-091H Kraken (III). */
	private static CardData krakenIii_25_091H() {
		return card("Kraken (III)", "Water", "Forward", 4, 8000, "Kraken", "III", null, false,
				"The cost required to cast your Summons or Monsters is reduced by 1 (it cannot become "
				+ "0).[[br]]When Kraken (III) enters the field, choose 1 Backup opponent controls. Remove it from "
				+ "the game for as long as Kraken (III) is on the field.");
	}

	/** 25-101L Meia. */
	private static CardData meia_25_101L() {
		return card("Meia", "Water", "Forward", 3, 7000, "Vesna Krasna/Witch", "MOBIUS", null, false,
				"When Meia enters the field, you may remove 1 Category MOBIUS card in your Break Zone from the "
				+ "game. When you do so, your opponent selects 1 Forward they control. Put it into the Break "
				+ "Zone.[[br]]《Dull》, remove 1 Category MOBIUS card in the Break Zone from the game: During this "
				+ "turn, the cost required to cast your next Summon is reduced by 1 (it cannot become 0).");
	}

	/** 24-047R Sophia (SOPFFO). */
	private static CardData sophiaSopffo_24_047R() {
		return card("Sophia (SOPFFO)", "Wind", "Forward", 3, 8000, "Black Mage", "SOPFFO", null, false,
				"When Sophia (SOPFFO) enters the field, you may pay 《C》. When you do so, select 1 of the 2 "
				+ "following actions.[[br]]\"Choose 1 Forward of cost 5 or more. Break it.\"[[br]]\"Choose 1 Monster. "
				+ "Break it.\"");
	}

	/** 24-057C Cloud. */
	private static CardData cloud_24_057C() {
		return card("Cloud", "Earth", "Backup", 2, 0, "Warrior", "THEATRHYTHM", "VII", false,
				"When Cloud enters the field, you may pay 《Earth》《2》. When you do so, choose 1 dull Forward. "
				+ "Break it.[[br]][[s]]Braver[[/]] 《S》《Earth》《Dull》: Choose 1 dull Forward. Break it.");
	}

	/** 24-068H Morse. */
	private static CardData morse_24_068H() {
		return card("Morse", "Earth", "Forward", 3, 8000, "Warrior", "TYPE-0", null, false,
				"Brave[[br]]When Morse enters the field or attacks, you may pay 《Earth》. When you do so, choose "
				+ "1 Forward opponent controls. Morse and the chosen Forward deal damage equal to their respective "
				+ "power to the other.[[br]]Damage 3 -- Morse gains +1000 power.[[br]]Damage 5 -- Morse gains "
				+ "+1000 power.");
	}

	/** 24-072C Leo. */
	private static CardData leo_24_072C() {
		return card("Leo", "Earth", "Forward", 1, 3000, "King", "FFCC", null, false,
				"When Leo enters the field, you may dull 4 active Characters you control. When you do so, choose "
				+ "1 Forward. Break it.");
	}

	/** 24-108H Wakka. */
	private static CardData wakka_24_108H() {
		return card("Wakka", "Water", "Forward", 3, 6000, "Guardian/Blitzballer", "X", null, false,
				"For each Category X Character other than Wakka you control, Wakka gains +1000 power.[[br]]When "
				+ "Wakka enters the field, choose 1 Forward opponent controls. If its power is equal to or less "
				+ "than Wakka's power, put it on top of its owner's deck.[[br]][[s]]Element Reels[[/]] 《S》: Choose "
				+ "1 Forward. It loses 5000 power until the end of the turn.");
	}

	/** 24-111H Lucio. */
	private static CardData lucio_24_111H() {
		return card("Lucio", "Light", "Forward", 5, 8000, "Warrior of the Kindlelight", "FFBE", null, false,
				"When Lucio enters the field, you may search for 2 Job Warrior of the Crystal, each of a "
				+ "different Element, and add them to your hand.[[br]]At the beginning of the Attack Phase during "
				+ "each of your turns, Lucio gains +2000 power until the end of the turn. Gain "
				+ "《C》《C》.[[br]][[s]]Scintillating Edge[[/]] 《S》《1》: Choose 1 Forward. Remove it from the game.");
	}

	/** 23-002L Caius. */
	private static CardData caius_23_002L() {
		return card("Caius", "Fire", "Forward", 5, 9000, "Guardian of the Seeress", "XIII", null, false,
				"Brave[[br]]   When Caius enters the field, you may search for 1 Card Name Bahamut and add it to "
				+ "your hand. During this turn, the cost required to cast your next Card Name Bahamut is reduced "
				+ "by 5.[[br]]   When Caius is put from the field into the Break Zone, you may discard 3 cards. "
				+ "When you do so, play Caius from the Break Zone onto the field dull.");
	}

	/** 23-006R Soulcage. */
	private static CardData soulcage_23_006R() {
		return card("Soulcage", "Fire", "Monster", 2, 9000, "Undead", "IX", null, false,
				"If you control 2 or more Monsters, Soulcage also becomes a Forward with 9000 power.[[br]]   "
				+ "When Soulcage is put from the field into the Break Zone, you may remove Soulcage from the game. "
				+ "When you do so, choose 1 Monster in your Break Zone. Add it to your hand.");
	}

	/** 23-017C Parai. */
	private static CardData parai_23_017C() {
		return card("Parai", "Fire", "Forward", 2, 5000, "Warrior", "PICTLOGICA", "FFL", false,
				"When Parai enters the field, you may receive 1 point of damage. When you do so, search for 1 "
				+ "Category FFL Character and add it to your hand.");
	}

	/** 23-021C Weiss. */
	private static CardData weiss_23_021C() {
		return card("Weiss", "Ice", "Forward", 3, 7000, "Tsviets", "DFF", "VII", false,
				"If you control 2 or more Job Tsviets, Weiss gains +2000 power and Brave.[[br]]   When Weiss "
				+ "enters the field, you may pay 《C》. When you do so, search for 1 Job Tsviets and add it to your "
				+ "hand.");
	}

	/** 23-028L Cecil. */
	private static CardData cecil_23_028L() {
		return card("Cecil", "Ice", "Forward", 3, 9000, "Dark Knight", "IV", null, false,
				"When Cecil enters the field, discard 1 card.[[br]]   When Cecil enters the field, you may "
				+ "receive 1 point of damage. When you do so, choose 2 Characters. Dull them and Freeze "
				+ "them.[[br]]   Damage 5 -- [[s]]Dark Flame[[/]] 《S》: Deal 10000 damage to all the Forwards "
				+ "opponent controls. Cecil deals you 1 point of damage.");
	}

	/** 23-038H Lady Lilith. */
	private static CardData ladyLilith_23_038H() {
		return card("Lady Lilith", "Ice", "Forward", 2, 5000, "Lady Lilith", "XI", null, false,
				"When Lady Lilith enters the field, you may pay 《C》《C》《C》. When you do so, choose 1 Forward. You "
				+ "gain control of it.");
	}

	/** 23-065R Gogmagog. */
	private static CardData gogmagog_23_065R() {
		return card("Gogmagog", "Earth", "Monster", 2, 9000, "Voidborn", "XIII", null, false,
				"If you control 2 or more Monsters, Gogmagog also becomes a Forward with 9000 power.[[br]]   "
				+ "When Gogmagog is put from the field into the Break Zone, you may remove 1 Monster you control "
				+ "from the game. When you do so, play Gogmagog from your Break Zone onto the field.");
	}

	/** 23-067R Shantotto. */
	private static CardData shantotto_23_067R() {
		return card("Shantotto", "Earth", "Forward", 5, 8000, "Mage", "DFF", "XI", false,
				"When Shantotto enters the field, choose 2 Summons from your and/or your opponent's Break Zone. "
				+ "Remove them from the game. During this game, you can cast them as though you owned them and at "
				+ "any time you could normally cast them.");
	}

	/** 23-068C SOLDIER Candidate. */
	private static CardData soldierCandidate_23_068C() {
		return genericCard("SOLDIER Candidate", "Earth", "Forward", 2, 5000, "Standard Unit", "VII", null, false,
				"If you have 2 or more Job Standard Unit Forwards in your Break Zone, SOLDIER Candidate gains "
				+ "+3000 power.[[br]]   When SOLDIER Candidate enters the field, choose 1 Forward opponent "
				+ "controls. SOLDIER Candidate and the chosen Forward deal damage equal to their respective power "
				+ "to the other.");
	}

	/** 23-084C G'raha Tia. */
	private static CardData gRahaTia_23_084C() {
		return card("G'raha Tia", "Lightning", "Forward", 5, 9000, "Scion of the Seventh Dawn", "XIV", null, false,
				"When G'raha Tia enters the field, you may remove 1 Card Name G'raha Tia in your Break Zone from "
				+ "the game. When you do so, choose 2 Forwards opponent controls. Deal them 4000 damage and dull "
				+ "them.");
	}

	/** 23-092R Black Widow. */
	private static CardData blackWidow_23_092R() {
		return card("Black Widow", "Lightning", "Monster", 2, 8000, "War Machine", "VII", null, false,
				"If you control 2 or more Monsters, Black Widow also becomes a Forward with 8000 power.[[br]]   "
				+ "When Black Widow attacks, you may put 1 Monster you control into the Break Zone. When you do "
				+ "so, choose 1 Forward. Deal it 9000 damage.");
	}

	/** 23-094C Y'shtola. */
	private static CardData yShtola_23_094C() {
		return card("Y'shtola", "Lightning", "Forward", 3, 7000, "Scion of the Seventh Dawn", "XIV", null, false,
				"When Y'shtola enters the field, choose up to the same number of Forwards opponent controls as "
				+ "the Job Scion of the Seventh Dawn you control. Dull them.");
	}

	/** 23-101C Dancer. */
	private static CardData dancer_23_101C() {
		return genericCard("Dancer", "Water", "Backup", 2, 0, "Standard Unit", "XI", null, false,
				"When Dancer enters the field, choose 1 Forward. It loses 2000 power until the end of the turn. "
				+ "If you control 5 or more Backups, all the Forwards opponent controls lose 4000 power until the "
				+ "end of the turn instead.");
	}

	/** 23-108R Fourchenault. */
	private static CardData fourchenault_23_108R() {
		return card("Fourchenault", "Water", "Forward", 4, 8000, "Member of the Forum", "XIV", null, false,
				"The cost required to cast Fourchenault is reduced by 2 for each Card Name Alisaie or Card Name "
				+ "Alphinaud you control.[[br]]   When Fourchenault enters the field, select 1 of the 2 following "
				+ "actions.[[br]]   \"Choose 1 Forward opponent controls. Return it to its owner's hand.\"[[br]]   "
				+ "\"Choose 1 Job Scion of the Seventh Dawn. Activate it.\"");
	}

	/** 23-115L Venat. */
	private static CardData venat_23_115L() {
		return card("Venat", "Light", "Forward", 4, 9000, "Ancient (XIV)", "XIV", null, false,
				"When Venat enters the field, gain 《C》《C》.[[br]]   When Venat is put from the field into the "
				+ "Break Zone, you may pay 《C》. When you do so, choose 1 Forward. Remove it from the game.[[br]]   "
				+ "《C》《C》《Dull》: Search for 1 card and add it to your hand.");
	}

	/** 22-005R Ignacio. */
	private static CardData ignacio_22_005R() {
		return card("Ignacio", "Fire", "Forward", 5, 9000, "Sworn Six of Paladia", "FFBE", null, false,
				"When Ignacio enters the field, put the top 3 cards of your deck into the Break Zone. When you "
				+ "do so, choose up to 1 Forward. If all the cards put into the Break Zone are of Fire Element, "
				+ "deal it 9000 damage.");
	}

	/** 22-030C Shinryu Celestia. */
	private static CardData shinryuCelestia_22_030C() {
		return card("Shinryu Celestia", "Ice", "Backup", 2, 0, "L'Cie", "TYPE-0", null, false,
				"Shinryu Celestia is also Card Name Celestia in all situations.[[br]]   《Dull》: Choose 1 "
				+ "Forward. It gains +4000 power until the end of the turn. You can only use this ability if you "
				+ "control 5 or more Ice Backups.");
	}

	/** 22-040H Enkidu. */
	private static CardData enkidu_22_040H() {
		return card("Enkidu", "Wind", "Forward", 3, 7000, "Warrior", "V", null, false,
				"When Enkidu enters the field, choose up to 3 Backups you control. Activate them.[[br]]   When a "
				+ "Card Name Gilgamesh enters your field, you may pay 《2》. When you do so, play Enkidu onto the "
				+ "field. This effect will trigger only if Enkidu is in the Break Zone.");
	}

	/** 22-044R Cid (FFBE). */
	private static CardData cidFfbe_22_044R() {
		return card("Cid (FFBE)", "Wind", "Forward", 4, 8000, "Sworn Six of Paladia", "FFBE", null, false,
				"At the beginning of the Attack Phase during each of your turns, select 1 of the 2 following "
				+ "actions. \"Choose 2 Characters. Activate them.\" \"All the Forwards you control gain 'This Forward "
				+ "cannot be chosen by your opponent's Summons.' until the end of the turn.\"[[br]]   When Cid "
				+ "(FFBE) is put from the field into the Break Zone, you may pay 《3》. When you do so, play Cid "
				+ "(FFBE) from the Break Zone onto the field dull.");
	}

	/** 22-061L Gilgamesh. */
	private static CardData gilgamesh_22_061L() {
		return card("Gilgamesh", "Earth", "Forward", 3, 7000, "Praetorian", "V", null, false,
				"If you control 5 or more Backups, Gilgamesh gains +2000 power and cannot be broken by opposing "
				+ "Summons or abilities that don't deal damage.[[br]]   When Gilgamesh enters the field or "
				+ "attacks, you may pay 《Earth》《Earth》《1》. When you do so, choose 1 Forward or Monster. Break it.");
	}

	/** 22-065R Sieghard. */
	private static CardData sieghard_22_065R() {
		return card("Sieghard", "Earth", "Forward", 3, 7000, "Sworn Six of Paladia", "FFBE", null, false,
				"When Sieghard enters the field, you may dull 4 active Backups you control. When you do so, "
				+ "choose 1 Forward of cost 6 or less in your Break Zone. Play it onto the field.[[br]]   《C》: "
				+ "Until the end of the turn, Sieghard gains +3000 power and Brave.");
	}

	/** 22-070C Ramza. */
	private static CardData ramza_22_070C() {
		return card("Ramza", "Earth", "Forward", 4, 8000, "Knight", "THEATRHYTHM", "FFT", false,
				"If you control a Job Knight, the cost required to cast Ramza is reduced by 2.[[br]]   If a "
				+ "Forward you controlled attacked this turn, the cost required to cast Ramza is reduced by 2.");
	}

	/** 22-079L Seifer. */
	private static CardData seifer_22_079L() {
		return card("Seifer", "Lightning", "Forward", 3, 7000, "SeeD Candidate", "VIII", null, false,
				"When Seifer enters the field, you may put the top 2 cards of your deck into the Break Zone. "
				+ "When you do so, choose 1 Category VIII Forward other than Card Name Seifer in your Break Zone. "
				+ "Add it to your hand.[[br]]   When Seifer attacks, choose 1 Forward opponent controls. If there "
				+ "are 10 or more cards in your Break Zone, break it.");
	}

	/** 22-090H Lulu. */
	private static CardData lulu_22_090H() {
		return card("Lulu", "Lightning", "Forward", 2, 5000, "Guardian", "X", null, false,
				"At the beginning of the Attack Phase during each of your turns, you may remove 2 Summons in "
				+ "your Break Zone from the game. When you do so, choose 1 Forward. Deal it 7000 damage.[[br]]   "
				+ "Discard 1 Summon: Choose 1 Forward. Deal it 5000 damage.");
	}

	/** 22-091C Blue Mage. */
	private static CardData blueMage_22_091C() {
		return genericCard("Blue Mage", "Water", "Backup", 2, 0, "Standard Unit", "XI", null, false,
				"When Blue Mage enters the field, you may discard 1 Monster. When you do so, draw 1 card.[[br]]  "
				+ " 《1》《Dull》, discard 1 Water card, put Blue Mage into the Break Zone: Choose 1 Monster of cost 4 "
				+ "or less in your Break Zone. Play it onto the field.");
	}

	/** 22-103C Faris. */
	private static CardData faris_22_103C() {
		return card("Faris", "Water", "Backup", 2, 0, "Pirate/Warrior of Light", "THEATRHYTHM", "V", false,
				"When Faris enters the field, you may remove 1 Forward you control from the game. When you do "
				+ "so, your opponent selects 1 Forward they control. Put it into the Break Zone.");
	}

	/** 21-005C Black Mage. */
	private static CardData blackMage_21_005C() {
		return genericCard("Black Mage", "Fire", "Forward", 3, 7000, "Standard Unit", "XIV", null, false,
				"When Black Mage enters the field, you may pay 《1》. When you do so, gain 《C》.[[br]]   《C》: Until "
				+ "the end of the turn, Black Mage gains +1000 power and \"When Black Mage attacks, choose 1 "
				+ "Forward. Deal it 5000 damage.\"");
	}

	/** 21-032R Terra. */
	private static CardData terra_21_032R() {
		return card("Terra", "Ice", "Forward", 5, 8000, "Summoner", "WOFF", null, false,
				"When Terra enters the field, you may search for 2 Ice Summons each with a different cost and "
				+ "add them to your hand.[[br]]   [[s]]Magitek Laser[[/]] 《S》, discard 1 Summon: Choose 1 Forward. "
				+ "Deal it 9000 damage.");
	}

	/** 21-040R Rursan Arbiter. */
	private static CardData rursanArbiter_21_040R() {
		return card("Rursan Arbiter", "Ice", "Forward", 5, 8000, "L'Cie", "TYPE-0", null, false,
				"You cannot play Rursan Arbiter or Card Name Cid Aulstyne  while already in control of either "
				+ "Character.[[br]]   《0》: Choose 1 Forward. Break it. You can only use this ability if your "
				+ "opponent has no cards in their hand, during your turn and only once per turn.");
	}

	/** 21-050H Sophia (SOPFFO). */
	private static CardData sophiaSopffo_21_050H() {
		return card("Sophia (SOPFFO)", "Wind", "Forward", 3, 7000, "Black Mage", "SOPFFO", null, false,
				"When Sophia (SOPFFO) enters the field, choose 1 Summon in your Break Zone. If you control 2 or "
				+ "more Category SOPFFO  Forwards, add it to your hand.[[br]]   When you cast a Summon, you may "
				+ "dull 1 active Category SOPFFO  Forward you control. When you do so, choose 1 Forward. Deal it "
				+ "5000 damage.");
	}

	/** 21-069C Krile. */
	private static CardData krile_21_069C() {
		return card("Krile", "Earth", "Backup", 2, 0, "Warrior of Light", "THEATRHYTHM", "V", false,
				"When Krile enters the field, you may discard 1 Summon. When you do so, choose 1 Forward. Deal "
				+ "it 8000 damage.[[br]]   《Earth》《Earth》《Dull》, put Krile into the Break Zone: Choose 1 Summon in "
				+ "your Break Zone. Add it to your hand.");
	}

	/** 21-075R Haveh. */
	private static CardData haveh_21_075R() {
		return card("Haveh", "Earth", "Forward", 5, 9000, "Warrior", "FFBE", null, false,
				"The power of Forwards opponent controls cannot be increased by your opponent's Summons or "
				+ "abilities.[[br]]   The Job Warrior  you control gain \"This Character cannot be broken by "
				+ "opposing Summons or abilities that don't deal damage.\"[[br]]   Damage 3 --  Haveh gains +1000 "
				+ "power and Brave.");
	}

	/** 21-086C Gunbreaker. */
	private static CardData gunbreaker_21_086C() {
		return genericCard("Gunbreaker", "Lightning", "Forward", 4, 8000, "Standard Unit", "XIV", null, false,
				"When Gunbreaker attacks, deal 2000 damage to all the Forwards opponent controls.[[br]]   When "
				+ "Gunbreaker attacks, you may pay 《C》. When you do so, deal 2000 damage to all the Forwards "
				+ "opponent controls.");
	}

	/** 21-088C Gilgamesh. */
	private static CardData gilgamesh_21_088C() {
		return card("Gilgamesh", "Lightning", "Forward", 4, 8000, "Praetorian", "DFF", "V", false,
				"When Gilgamesh is put from the field into the Break Zone, gain 《C》.[[br]]   《C》: Until the end "
				+ "of the turn, Gilgamesh gains Haste, Brave and \"Gilgamesh can attack twice in the same "
				+ "turn.\"[[br]]   Damage 3 --  Gilgamesh gains +1000 power.");
	}

	/** 21-094C Shantotto. */
	private static CardData shantotto_21_094C() {
		return card("Shantotto", "Lightning", "Backup", 3, 0, "Mage", "THEATRHYTHM", "XI", false,
				"When Shantotto enters the field, choose 1 Forward. Deal it 3000 damage for each different "
				+ "Element among Characters you control.");
	}

	/** 21-124L Jack Garland. */
	private static CardData jackGarland_21_124L() {
		return card("Jack Garland", "Dark", "Forward", 3, 9000, "Stranger", "SOPFFO", null, false,
				"Jack Garland is also Card Name Garland  in all situations.[[br]]   Brave[[br]]   When Jack "
				+ "Garland enters the field, you may search for 1 Category SOPFFO  Forward and add it to your "
				+ "hand.[[br]]   《Dull》: Choose 1 Dark Character. Break it. Jack Garland deals you 1 point of "
				+ "damage.");
	}

	/** 21-125S Onion Knight. */
	private static CardData onionKnight_21_125S() {
		return card("Onion Knight", "Fire", "Forward", 4, 8000, "Onion Knight/Warrior of Light", "DFF", "III", false,
				"Haste[[br]]   When Onion Knight enters the field, you may pay 《C》《C》. When you do so, search "
				+ "for 1 Category DFF Forward of cost 3 or less and play it onto the field.[[br]]   [[s]]Sword & "
				+ "Sorcery[[/]] 《S》: Deal 10000 damage to the Forward that blocks Onion Knight.");
	}

	/** 21-128S Shantotto. */
	private static CardData shantotto_21_128S() {
		return card("Shantotto", "Ice", "Forward", 5, 9000, "Mage", "DFF", "XI", false,
				"When Shantotto enters the field, you may pay 《C》《C》. When you do so, choose 1 Category DFF "
				+ "Forward of cost 5 or less in your Break Zone. Play it onto the field.[[br]]   [[s]]Salvation "
				+ "Scythe[[/]] 《S》《Ice》: Choose up to 3 Characters. Dull them and Freeze them.");
	}

	/** 21-130S Noctis. */
	private static CardData noctis_21_130S() {
		return card("Noctis", "Ice", "Forward", 3, 9000, "Prince", "DFF", "XV", true,
				"You must control a Category DFF Forward to cast Noctis.[[br]]   [[ex]]EX BURST[[/]] When Noctis "
				+ "enters the field, choose 1 dull Forward opponent controls. Break it.");
	}

	/** 20-017R Palom. */
	private static CardData palom_20_017R() {
		return card("Palom", "Fire", "Forward", 2, 5000, "Black Mage", "IV", null, false,
				"When Palom enters the field, you may pay 《C》. When you do so, choose 1 Forward. Deal it 8000 "
				+ "damage.[[br]]   Put Palom and 1 Card Name Porom into the Break Zone: Choose 1 Forward. Deal it "
				+ "10000 damage.");
	}

	/** 20-021R Red XIII. */
	private static CardData redXiii_20_021R() {
		return card("Red XIII", "Fire", "Forward", 2, 9000, "Warrior", "VII", null, false,
				"You can only play Red XIII if you control a Category VII Forward.[[br]]   If you control 4 or "
				+ "more Category VII Characters, Red XIII gains Haste.");
	}

	/** 20-029C Jihl Nabaat. */
	private static CardData jihlNabaat_20_029C() {
		return card("Jihl Nabaat", "Ice", "Forward", 3, 7000, "PSICOM", "PICTLOGICA", "XIII", false,
				"If all the Characters you control have Ice Element, Jihl Nabaat gains +2000 power.[[br]]   When "
				+ "Jihl Nabaat is put from the field into the Break Zone, you may pay 《Ice》《Ice》. When you do so, "
				+ "choose 2 Characters. Dull them and Freeze them.");
	}

	/** 20-031R Celes. */
	private static CardData celes_20_031R() {
		return card("Celes", "Ice", "Forward", 2, 5000, "Rune Knight", "VI", null, false,
				"If you control a Card Name Locke, Celes gains Haste.[[br]]   The Card Name Locke you control "
				+ "gains Haste.[[br]]   When Celes enters the field, choose up to the same number of Forwards "
				+ "opponent controls as the Category VI Characters you control. Dull them.");
	}

	/** 20-060R Yuri. */
	private static CardData yuri_20_060R() {
		return card("Yuri", "Wind", "Forward", 3, 7000, "Lay Crystal User", "DFF", "FFCC", false,
				"When Yuri enters the field, you may discard 1 card. When you do so, search for 1 Card Name "
				+ "Chelinka of cost 4 or less and play it onto the field.[[br]]   When Yuri forms a party and "
				+ "attacks, select 1 of the 2 following actions. \"Choose 1 Forward of cost 4 or more. Break it.\" "
				+ "\"Activate all the Characters you control.\"");
	}

	/** 20-066R Ignis. */
	private static CardData ignis_20_066R() {
		return card("Ignis", "Earth", "Forward", 5, 9000, "Retainer", "XV", null, false,
				"When Ignis enters the field, you may put 1 Backup you control into the Break Zone. When you do "
				+ "so, choose 1 Forward. Break it.[[br]]   When a Backup you control is put from the field into "
				+ "the Break Zone, choose 1 Forward in your Break Zone. Add it to your hand. This effect will "
				+ "trigger only during your turn and only once per turn.");
	}

	/** 20-085C Assassin. */
	private static CardData assassin_20_085C() {
		return genericCard("Assassin", "Lightning", "Forward", 1, 3000, "Standard Unit", "FFTA", null, false,
				"When Assassin enters the field, you may pay 《Lightning》《Lightning》《1》. When you do so, choose 1 "
				+ "Forward. Deal it 3000 damage.[[br]]   Put Assassin into the Break Zone: Choose 1 damaged "
				+ "Forward. Break it. You can only use this ability during your turn.");
	}

	/** 20-112C Frimelda. */
	private static CardData frimelda_20_112C() {
		return card("Frimelda", "Water", "Forward", 3, 7000, "Sword Saint", "PICTLOGICA", "FFTA2", false,
				"When Frimelda attacks, draw 1 card, then discard 1 card. When you discard a Forward by this "
				+ "effect, choose 1 Forward. Until the end of the turn, it loses 3000 power and Frimelda gains "
				+ "+3000 power.");
	}

	/** 20-122R Leslie. */
	private static CardData leslie_20_122R() {
		return card("Leslie", "Water", "Forward", 1, 3000, "Lackey", "VII", null, false,
				"When Leslie enters the field, you may discard 1 card. When you do so, choose 1 Forward opponent "
				+ "controls. Return it to its owner's hand. If the discarded card is Category VII, also draw 1 "
				+ "card.");
	}

	/** 19-009C Bomb. */
	private static CardData bomb_19_009C() {
		return genericCard("Bomb", "Fire", "Monster", 2, 5000, "Bomb", "IX", null, false,
				"If you control 3 or more Fire Backups, Bomb also becomes a Forward with 5000 power and \"When "
				+ "Bomb is put from the field into the Break Zone, choose 1 Forward. Deal it 2000 damage.\"[[br]]   "
				+ "When Bomb enters the field, you may dull 2 active Fire Backups you control. When you do so, "
				+ "search for 1 Card Name Bomb of cost 2 or less and play it onto the field.");
	}

	/** 19-027R Chocobo Eater. */
	private static CardData chocoboEater_19_027R() {
		return card("Chocobo Eater", "Ice", "Monster", 1, 0, "Chocobo Eater", "X", null, false,
				"When Chocobo Eater enters the field, choose 1 Forward of cost 2 or less opponent controls. "
				+ "Remove it from the game for as long as Chocobo Eater is on the field.");
	}

	/** 19-059R Aster Protoflorian. */
	private static CardData asterProtoflorian_19_059R() {
		return card("Aster Protoflorian", "Earth", "Monster", 3, 0, "Annihilator", "XIII", null, false,
				"When Aster Protoflorian enters the field, you may pay 《Earth》《Earth》. When you do so, choose 1 "
				+ "Forward. Break it.[[br]]   When Aster Protoflorian enters the field, you may pay "
				+ "《Earth》《Earth》. When you do so, choose 1 Backup. Break it.");
	}

	/** 19-061H Doga. */
	private static CardData doga_19_061H() {
		return card("Doga", "Earth", "Forward", 2, 5000, "Magus", "III", null, false,
				"When Doga enters the field, you may discard 1 Summon. When you do so, gain 《C》《C》.[[br]]   "
				+ "《Fire》《C》《Dull》: Deal 7000 damage to all the Forwards opponent controls.[[br]]   "
				+ "《Lightning》《C》《Dull》: Choose 1 Forward. Break it.");
	}

	/** 19-066C Behemoth. */
	private static CardData behemoth_19_066C() {
		return genericCard("Behemoth", "Earth", "Monster", 1, 7000, "Behemoth", "IV", null, false,
				"When Behemoth enters the field, you may pay 《Earth》. When you do so, choose 1 Forward. It "
				+ "cannot attack or block until the end of the next turn.[[br]]   《Earth》: Until the end of the "
				+ "turn, Behemoth also becomes a Forward with 7000 power. You can only use this ability once per "
				+ "turn.");
	}

	/** 19-072C Eald'narche. */
	private static CardData ealdNarche_19_072C() {
		return card("Eald'narche", "Lightning", "Forward", 5, 9000, "Descendant of the Zilart", "THEATRHYTHM", "XI", false,
				"When Eald'narche enters the field, you may discard 1 card. When you do so, choose 1 Forward. "
				+ "Break it.");
	}

	/** 19-080R Vivi. */
	private static CardData vivi_19_080R() {
		return card("Vivi", "Lightning", "Forward", 1, 3000, "Black Mage", "PICTLOGICA", "IX", false,
				"When Vivi enters the field, you may reveal any number of Lightning cards from your hand. When "
				+ "you do so, choose 1 Forward. Deal it 2000 damage for each card you revealed.");
	}

	/** 19-082H Lightning. */
	private static CardData lightning_19_082H() {
		return card("Lightning", "Lightning", "Forward", 5, 9000, "Ravager/L'Cie", "XIII", null, false,
				"Haste[[br]]   When Lightning is put from the field into the Break Zone, you may play 1 Category "
				+ "XIII Forward of cost 6 or less from your hand onto the field.[[br]]   Discard 1 Card Name Odin: "
				+ "Choose 1 Forward opponent controls. Break it and Lightning.");
	}

	/** 19-100C Larsa. */
	private static CardData larsa_19_100C() {
		return card("Larsa", "Water", "Backup", 2, 0, "Fourthborn Son of the Emperor", "XII", null, false,
				"When Larsa enters the field, you may discard 1 card. When you do so, search for 1 Category XII "
				+ "Forward and add it to your hand.[[br]]   《Water》《Dull》, put Larsa into the Break Zone: Choose 1 "
				+ "Forward. During this turn, the next damage dealt to it is reduced by 2000 instead.");
	}

	/** 19-124L Y'shtola. */
	private static CardData yShtola_19_124L() {
		return card("Y'shtola", "Lightning/Fire", "Forward", 3, 8000, "Scion of the Seventh Dawn", "DFF", "XIV", false,
				"When Y'shtola enters the field, you may receive 1 point of damage. When you do so, until the "
				+ "end of the turn, Y'shtola gains Haste and \"Y'shtola cannot be blocked.\"[[br]]   When either "
				+ "player receives a point of damage, choose 1 Forward opponent controls. Deal it 4000 damage.");
	}

	/** 19-125H Mog (VI). */
	private static CardData mogVi_19_125H() {
		return card("Mog (VI)", "Water/Ice", "Forward", 4, 9000, "Warrior/Moogle", "VI", null, false,
				"The cost required to cast Mog (VI) is reduced by 1 for each Category VI Character you "
				+ "control.[[br]]   When Mog (VI) attacks, all the Forwards you control gain First Strike until "
				+ "the end of the turn.[[br]]   Damage 3 -- When Mog (VI) enters the field, choose 1 Character. "
				+ "Dull it and Freeze it. Draw 1 card.");
	}

	/** 18-009H Tidus. */
	private static CardData tidus_18_009H() {
		return card("Tidus", "Fire", "Forward", 4, 8000, "Guardian", "X", null, false,
				"Haste[[br]]   When Tidus enters the field, put the top 5 cards of your deck into the Break "
				+ "Zone. When you do so, choose up to 1 Forward. If all the cards put into the Break Zone are of "
				+ "Fire Element, deal it 8000 damage.");
	}

	/** 18-010C Berserker. */
	private static CardData berserker_18_010C() {
		return genericCard("Berserker", "Fire", "Forward", 3, 8000, "Standard Unit", "V", null, false,
				"First Strike[[br]]   Berserker must attack once per turn if possible.[[br]]   When Berserker "
				+ "enters the field, you may pay 《Fire》. When you do so, choose 1 Forward. It gains \"If possible, "
				+ "this Forward must block.\" until the end of the turn.");
	}

	/** 18-075R Seifer. */
	private static CardData seifer_18_075R() {
		return card("Seifer", "Lightning", "Forward", 3, 7000, "SeeD Candidate", "VIII", null, false,
				"When Seifer enters the field, you may receive 1 point of damage. When you do so, choose 1 "
				+ "Category VIII Forward other than Card Name Seifer in your Break Zone. Add it to your "
				+ "hand.[[br]]   When you receive a point of damage, choose 1 Forward. Until the end of the turn, "
				+ "it gains +2000 power and Haste.");
	}

	/** 18-085C Dragoon. */
	private static CardData dragoon_18_085C() {
		return genericCard("Dragoon", "Lightning", "Forward", 2, 5000, "Standard Unit", "XI", null, false,
				"When Dragoon enters the field, you may pay 《Lightning》《Lightning》《3》. When you do so, choose 1 "
				+ "Forward. Break it.[[br]]   Damage 3 -- Dragoon gains +3000 power and Haste.");
	}

	/** 18-095C Rune Fencer. */
	private static CardData runeFencer_18_095C() {
		return genericCard("Rune Fencer", "Water", "Forward", 4, 8000, "Standard Unit", "XI", null, false,
				"When Rune Fencer enters the field, choose 1 Forward. It loses 4000 power until the end of the "
				+ "turn.[[br]]   When Rune Fencer enters the field, you may pay 《Water》《Water》《1》. When you do so, "
				+ "choose 1 Forward. It loses 7000 power until the end of the turn.");
	}

	/** 18-111L Basch. */
	private static CardData basch_18_111L() {
		return card("Basch", "Fire/Earth", "Forward", 3, 7000, "Judge", "XII", null, false,
				"When Basch enters the field, you may receive 1 point of damage. When you do so, search for 1 "
				+ "Fire or Earth Character other than Card Name Basch and add it to your hand.[[br]]   Damage 3 -- "
				+ "Basch gains +1000 power and Brave.[[br]]   Damage 5 -- When Basch attacks, choose 1 Forward "
				+ "opponent controls. Deal it 8000 damage.");
	}

	/** 18-120H Tifa. */
	private static CardData tifa_18_120H() {
		return card("Tifa", "Wind/Earth", "Forward", 3, 8000, "Martial Artist", "DFF", "VII", false,
				"If Tifa is dealt damage, reduce the damage by 2000 instead.[[br]]   When Tifa enters the field, "
				+ "you may pay 《2》. When you do so, choose 1 Forward opponent controls. Tifa and the chosen "
				+ "Forward deal damage equal to their respective power to the other.[[br]]   [[s]]Meteor "
				+ "Strike[[/]] 《S》《Wind》《Earth》《2》: Choose up to 3 Forwards. Deal them 8000 damage.");
	}

	/** 17-008H Samurai. */
	private static CardData samurai_17_008H() {
		return genericCard("Samurai", "Fire", "Forward", 3, 7000, "Standard Unit", "XIV", null, false,
				"When Samurai enters the field, you may pay 《Fire》《1》. When you do so, choose 1 Forward. Deal it "
				+ "8000 damage.[[br]]   Damage 3 -- Samurai gains +1000 power and Brave.");
	}

	/** 17-021C Red XIII. */
	private static CardData redXiii_17_021C() {
		return card("Red XIII", "Fire", "Backup", 2, 0, "Warrior", "PICTLOGICA", "VII", false,
				"When Red XIII enters the field, you may discard 1 card. When you do so, choose 1 Forward. Deal "
				+ "it 7000 damage.[[br]]   When Red XIII enters the field, you may pay 《Fire》《1》. When you do so, "
				+ "choose 1 Forward. Deal it 7000 damage.");
	}

	/** 17-022H Umaro. */
	private static CardData umaro_17_022H() {
		return card("Umaro", "Ice", "Forward", 2, 9000, "Yeti", "PICTLOGICA", "VI", false,
				"Umaro must attack once per turn if possible.[[br]]   When Umaro enters the field, if you "
				+ "control 4 or less Category VI Characters, discard 1 card.[[br]]   《Dull》, put Umaro into the "
				+ "Break Zone: Your opponent discards 2 cards.");
	}

	/** 17-023H Scholar. */
	private static CardData scholar_17_023H() {
		return genericCard("Scholar", "Ice", "Forward", 2, 5000, "Standard Unit", "XI", null, false,
				"When Scholar enters the field, you may pay 《Ice》. When you do so, your opponent randomly "
				+ "discards 1 card.[[br]]   Damage 3 -- 《Ice》《Dull》: Choose 1 Forward. Dull it.");
	}

	/** 17-032C Terra. */
	private static CardData terra_17_032C() {
		return card("Terra", "Ice", "Forward", 3, 7000, "Magitek Knight", "DFF", "VI", false,
				"When Terra enters the field, choose 1 Summon in your Break Zone. Remove it from the game. When "
				+ "you do so, place 1 Magitek Counter on Terra.[[br]]   Remove 1 Magitek Counter from Terra: Terra "
				+ "gains +5000 power until the end of the turn.");
	}

	/** 17-043H Dancer. */
	private static CardData dancer_17_043H() {
		return genericCard("Dancer", "Wind", "Forward", 3, 7000, "Standard Unit", "XI", null, false,
				"When Dancer enters the field, you may pay 《Wind》. When you do so, choose up to 3 Characters. "
				+ "Activate them.[[br]]   Damage 3 -- Dull 1 active Job Dancer or Card Name Dancer: Choose 1 "
				+ "Forward. It gains +1000 power until the end of the turn.");
	}

	/** 17-081H Lyse. */
	private static CardData lyse_17_081H() {
		return card("Lyse", "Earth", "Forward", 4, 8000, "Fighter", "XIV", null, false,
				"You cannot play Lyse or Card Name Yda while already in control of either Character.[[br]]   If "
				+ "a Forward forming a party you control is dealt damage, the damage becomes 0 instead.[[br]]   "
				+ "When a party you control deals damage to your opponent, draw 1 card. Lyse deals your opponent 1 "
				+ "point of damage. This effect will trigger only once per turn.");
	}

	/** 17-086H Red Mage. */
	private static CardData redMage_17_086H() {
		return genericCard("Red Mage", "Lightning", "Forward", 3, 7000, "Standard Unit", "XIV", null, false,
				"When Red Mage enters the field, you may pay 《Lightning》《1》. When you do so, choose 1 Forward of "
				+ "cost 4 or less. Break it.[[br]]   Damage 3 -- When Red Mage enters the field, choose 1 Forward "
				+ "other than Red Mage. Until the end of the turn, it gains +1000 power and Haste.");
	}

	/** 17-116C Gordon. */
	private static CardData gordon_17_116C() {
		return card("Gordon", "Water", "Backup", 2, 0, "Prince", "PICTLOGICA", "II", false,
				"When Gordon enters the field, draw 1 card, then discard 1 card.[[br]]   When Gordon enters the "
				+ "field, you may pay 《Water》. When you do so, draw 1 card.");
	}

	/** 17-126H Rursan Reaver. */
	private static CardData rursanReaver_17_126H() {
		return genericCard("Rursan Reaver", "Water", "Forward", 2, 5000, "Standard Unit", "TYPE-0", null, false,
				"When Rursan Reaver enters the field, you may pay 《Water》《1》. When you do so, choose 1 Forward "
				+ "opponent controls. It loses 5000 power until the end of the turn.[[br]]   Damage 3 -- Rursan "
				+ "Reaver gains +2000 power and \"When Rursan Reaver attacks, choose 1 Forward opponent controls. "
				+ "It loses 2000 power until the end of the turn.\"");
	}

	/** 17-129H Vinera Fennes. */
	private static CardData vineraFennes_17_129H() {
		return card("Vinera Fennes", "Dark", "Forward", 2, 5000, "Assassin", "FFBE", null, false,
				"The cost required to cast Vinera Fennes is reduced by 1 for each 《C》 you have.[[br]]   "
				+ "Haste[[br]]   When Vinera Fennes attacks, you may pay 《C》. When you do so, all the Forwards "
				+ "opponent controls lose 5000 power until the end of the turn.");
	}

	/** 16-013H Sol. */
	private static CardData sol_16_013H() {
		return card("Sol", "Fire", "Forward", 2, 5000, "Warrior of Light", "FFL", null, false,
				"When Sol enters the field, you may pay 《C》. When you do so, play 1 Forward of cost 4 or less "
				+ "from your hand onto the field.[[br]]   When a party you control attacks, gain 《C》.[[br]]   《C》: "
				+ "All the Forwards you control gain +2000 power until the end of the turn.");
	}

	/** 16-018C Lilty. */
	private static CardData lilty_16_018C() {
		return genericCard("Lilty", "Fire", "Backup", 2, 0, "Standard Unit", "FFCC", null, false,
				"《Dull》: Return Lilty to its owner's hand. You can only use this ability during your turn.[[br]] "
				+ "  Damage 5 -- When Lilty enters the field, you may put Lilty into the Break Zone. When you do "
				+ "so, choose 1 Forward. Deal it 10000 damage.");
	}

	/** 16-025C Bard. */
	private static CardData bard_16_025C() {
		return genericCard("Bard", "Ice", "Backup", 2, 0, "Standard Unit", null, null, false,
				"《Dull》: Return Bard to its owner's hand. You can only use this ability during your turn.[[br]]  "
				+ " Damage 5 -- When Bard enters the field, you may put Bard into the Break Zone. When you do so, "
				+ "your opponent discards 2 cards.");
	}

	/** 16-026L Cloud of Darkness. */
	private static CardData cloudOfDarkness_16_026L() {
		return card("Cloud of Darkness", "Ice", "Forward", 3, 7000, "Wraith", "III", null, false,
				"When Cloud of Darkness enters the field, you may pay 《C》《C》. When you do so, choose 1 Forward "
				+ "opponent controls. Remove it from the game. As long as Cloud of Darkness is on the field, your "
				+ "opponent cannot play any copies of the removed card.[[br]]   Damage 3 -- Cloud of Darkness "
				+ "gains +2000 power.");
	}

	/** 16-050H Ceodore. */
	private static CardData ceodore_16_050H() {
		return card("Ceodore", "Wind", "Forward", 4, 8000, "Prince", "IV", null, false,
				"When Ceodore enters the field, you may dull 3 active Category IV Characters you control. When "
				+ "you do so, choose 1 Character of cost 4 or more. Break it.[[br]]   At the beginning of the "
				+ "Attack Phase during each of your turns, activate all the Category IV Forwards you control.");
	}

	/** 16-051L Cecil. */
	private static CardData cecil_16_051L() {
		return card("Cecil", "Wind", "Forward", 5, 8000, "Paladin", "DFF", "IV", false,
				"When Cecil enters the field, you may play 1 Character of cost 4 or less from your hand onto the "
				+ "field. When it enters the field, if it is a Category IV Character, choose up to 2 Backups. "
				+ "Activate them.[[br]]   When 1 or more Characters you control are chosen by your opponent's "
				+ "Summons or abilities, if your opponent doesn't pay 《2》, cancel their effects.");
	}

	/** 16-052C Selkie. */
	private static CardData selkie_16_052C() {
		return genericCard("Selkie", "Wind", "Backup", 2, 0, "Standard Unit", "FFCC", null, false,
				"《Dull》: Return Selkie to its owner's hand. You can only use this ability during your "
				+ "turn.[[br]]   Damage 5 -- When Selkie enters the field, you may put Selkie into the Break Zone. "
				+ "When you do so, choose 1 Character of cost 4 or more. Break it.");
	}

	/** 16-087C Puppetmaster. */
	private static CardData puppetmaster_16_087C() {
		return genericCard("Puppetmaster", "Lightning", "Forward", 3, 7000, "Standard Unit", "XI", null, false,
				"When Puppetmaster enters the field, you may discard 1 card. When you do so, choose 1 Character "
				+ "in your Break Zone. Add it to your hand.");
	}

	/** 16-101C Yuke. */
	private static CardData yuke_16_101C() {
		return genericCard("Yuke", "Lightning", "Backup", 2, 0, "Standard Unit", "FFCC", null, false,
				"《Dull》: Return Yuke to its owner's hand. You can only use this ability during your turn.[[br]]  "
				+ " Damage 5 -- When Yuke enters the field, you may put Yuke into the Break Zone. When you do so, "
				+ "choose 1 Forward of cost 5 or less. Break it.");
	}

	/** 16-110C Clavat. */
	private static CardData clavat_16_110C() {
		return genericCard("Clavat", "Water", "Backup", 2, 0, "Standard Unit", "FFCC", null, false,
				"《Dull》: Return Clavat to its owner's hand. You can only use this ability during your "
				+ "turn.[[br]]   Damage 5 -- When Clavat enters the field, you may put Clavat into the Break Zone. "
				+ "When you do so, draw 2 cards.");
	}

	/** 15-012H Faris. */
	private static CardData faris_15_012H() {
		return card("Faris", "Fire", "Forward", 6, 8000, "Pirate/Warrior of Light", "V", null, false,
				"When Faris enters the field, choose up to 2 Forwards in your Break Zone. If the total of their "
				+ "power is 3000, play them onto the field.[[br]]   [[s]]Pirate Storm[[/]] 《S》《Fire》: Choose any "
				+ "number of Forwards. Deal them a total amount of damage equal to 5000 multiplied by each Forward "
				+ "of cost 3 or less you control, split as you wish among the chosen Forwards (damage must be in "
				+ "increments of 1000).");
	}

	/** 15-013L Firion. */
	private static CardData firion_15_013L() {
		return card("Firion", "Fire", "Forward", 3, 7000, "Warrior/Rebel", "II", null, false,
				"When Firion enters the field, you may pay 《C》. When you do so, draw 1 card, Firion gains +1000 "
				+ "power, Haste and First Strike. (This effect does not end at the end of the turn.)[[br]]   《C》: "
				+ "Firion gains +3000 power until the end of the turn.");
	}

	/** 15-034R Snow. */
	private static CardData snow_15_034R() {
		return card("Snow", "Ice", "Forward", 4, 8000, "Sentinel/L'Cie", "DFF", "XIII", false,
				"When Snow enters the field, you may pay 《C》. When you do so, select 1 of the 2 following "
				+ "actions.[[br]]   \"Freeze all the Forwards opponent controls.\"[[br]]   \"Freeze all the Backups "
				+ "opponent controls.\"");
	}

	/** 15-055H Bartz. */
	private static CardData bartz_15_055H() {
		return card("Bartz", "Wind", "Forward", 4, 8000, "Wanderer/Warrior of Light", "THEATRHYTHM", "V", true,
				"[[ex]]EX BURST[[/]] When a Job Warrior of Light other than Bartz enters your field, gain "
				+ "《C》.[[br]]   If you have a 《C》, Bartz gains +2000 power.[[br]]   When a Job Warrior of Light "
				+ "you control is put from the field into the Break Zone, you may pay 《C》《C》《C》. When you do so, "
				+ "play it onto the field. This effect will trigger only once per turn.");
	}

	/** 15-084L Robel-Akbel. */
	private static CardData robelAkbel_15_084L() {
		return card("Robel-Akbel", "Earth", "Forward", 4, 8000, "Black Mage/Warlock Warlord", "XI", null, false,
				"When Robel-Akbel enters the field, you may pay 《C》. When you do so, choose 1 Forward or "
				+ "Monster. Break it.[[br]]   At the beginning of Main Phase 1 during each of your turns, choose 1 "
				+ "Backup in your Break Zone. Add it to your hand.");
	}

	/** 15-092C Sonitus. */
	private static CardData sonitus_15_092C() {
		return card("Sonitus", "Lightning", "Backup", 2, 0, "Kingsglaive", "XV", null, false,
				"《Lightning》《Lightning》《1》《Dull》, put Sonitus into the Break Zone: Choose 1 Forward. Break it. "
				+ "Sonitus deals you 1 point of damage.");
	}

	/** 15-112R Shinryu Celestia. */
	private static CardData shinryuCelestia_15_112R() {
		return card("Shinryu Celestia", "Water", "Forward", 4, 8000, "L'Cie", "TYPE-0", null, false,
				"Shinryu Celestia is also Card Name Celestia in all situations.[[br]]   When Shinryu Celestia "
				+ "enters the field, you may put 1 Character you control into the Break Zone. When you do so, "
				+ "choose 1 Forward. It loses 7000 power until the end of the turn.[[br]]   When a Category TYPE-0 "
				+ "Character you control is put from the field into the Break Zone, choose 1 Forward. It loses "
				+ "3000 power until the end of the turn.");
	}

	/** 15-124H Relm. */
	private static CardData relm_15_124H() {
		return card("Relm", "Water", "Forward", 3, 6000, "Pictomancer", "VI", null, false,
				"When Relm enters the field, choose 1 Forward or Monster you control. You may return it to its "
				+ "owner's hand. When you do so, gain 《C》.[[br]]   《C》《Dull》: Search for 1 Monster of cost 2 or "
				+ "less and play it onto the field.[[br]]   《C》《C》《Dull》: Search for 1 Monster of cost 5 or less "
				+ "and play it onto the field.");
	}

	/** 15-126R Lenna. */
	private static CardData lenna_15_126R() {
		return card("Lenna", "Water", "Forward", 3, 7000, "Princess/Warrior of Light", "THEATRHYTHM", "V", false,
				"When Lenna enters the field, you may pay 《C》. When you do so, choose 1 Forward of cost 2 or "
				+ "less in your Break Zone. Play it onto the field.[[br]]   When Lenna enters the field, you may "
				+ "pay 《C》《C》. When you do so, choose 1 Forward of cost 3 or less other than Card Name Lenna in "
				+ "your Break Zone. Play it onto the field.");
	}

	/** 15-131S Wedge. */
	private static CardData wedge_15_131S() {
		return card("Wedge", "Earth", "Forward", 3, 7000, "AVALANCHE Operative", "VII", null, false,
				"If you control a Job AVALANCHE Operative, the cost required to cast Wedge is reduced by "
				+ "2.[[br]]   When 2 or more Job AVALANCHE Operative Forwards you control form a party and attack, "
				+ "choose 1 dull Character of cost 4 or more opponent controls. Break it.");
	}

	/** 15-134S Biggs. */
	private static CardData biggs_15_134S() {
		return card("Biggs", "Earth", "Forward", 3, 7000, "AVALANCHE Operative", "VII", null, false,
				"If you control a Job AVALANCHE Operative, the cost required to cast Biggs is reduced by "
				+ "2.[[br]]   When 2 or more Job AVALANCHE Operative Forwards you control form a party and attack, "
				+ "draw 1 card, all Forwards in that party gain \"This Forward cannot be broken.\" until the end of "
				+ "the turn.");
	}

	/** 15-135S Tseng. */
	private static CardData tseng_15_135S() {
		return card("Tseng", "Lightning", "Forward", 4, 8000, "Member of the Turks", "VII", null, false,
				"The Job Member of the Turks Forwards other than Tseng you control gain +2000 power.[[br]]   "
				+ "When Tseng enters the field, reveal the top 5 cards of your deck. Add 1 Job Member of the Turks "
				+ "among them to your hand and return the other cards to the bottom of your deck in any order. "
				+ "Then, you may play 1 Job Member of the Turks from your hand onto the field.[[br]]");
	}

	/** 15-137S Rude. */
	private static CardData rude_15_137S() {
		return card("Rude", "Lightning", "Forward", 3, 7000, "Member of the Turks", "VII", null, false,
				"If you control a Job President of Shinra, the Job Member of the Turks Forwards you control gain "
				+ "+1000 power and Brave.[[br]]   At the beginning of the Attack Phase during each of your turns, "
				+ "if you control 4 or more Job Member of the Turks, dull all the Forwards opponent controls.[[br]]");
	}

	/** 15-138S Reno. */
	private static CardData reno_15_138S() {
		return card("Reno", "Lightning", "Forward", 3, 7000, "Member of the Turks", "VII", null, false,
				"If you control a Job President of Shinra, the Job Member of the Turks Forwards you control gain "
				+ "+1000 power and First Strike.[[br]]   At the beginning of the Attack Phase during each of your "
				+ "turns, if you control 4 or more Job Member of the Turks, all the Job Member of the Turks "
				+ "Forwards you control gain Haste until the end of the turn.[[br]]");
	}

	/** 14-114R Luzaf. */
	private static CardData luzaf_14_114R() {
		return card("Luzaf", "Water", "Backup", 4, 0, "Corsair", "XI", null, false,
				"Back Attack[[br]]   When Luzaf enters the field, choose 1 Forward you control. Return it to its "
				+ "owner's hand. When you do so, your opponent selects 1 Forward they control. Put it into the "
				+ "Break Zone.");
	}

	/** 14-124H Zeromus. */
	private static CardData zeromus_14_124H() {
		return card("Zeromus", "Ice/Lightning", "Forward", 3, 8000, "Lunarian", "IV", null, false,
				"Zeromus is also Card Name Zemus in all situations.[[br]]   When Zeromus enters the field or "
				+ "attacks, choose 1 Character. Freeze it.[[br]]   At the end of each of your opponent's turns, "
				+ "dull all the Forwards opponent controls.[[br]]   Damage 3 -- Zeromus gains Haste.");
	}

	/** 14-130H Cloud of Darkness. */
	private static CardData cloudOfDarkness_14_130H() {
		return card("Cloud of Darkness", "Water/Ice", "Forward", 4, 8000, "Wraith", "DFF", "III", false,
				"During this turn, if your opponent has discarded a card from their hand due to the Summons or "
				+ "abilities, the cost required to cast Cloud of Darkness is reduced by 2.[[br]]   During this "
				+ "turn, if you have drawn 3 or more cards, the cost required to cast Cloud of Darkness is reduced "
				+ "by 2.[[br]]   When Cloud of Darkness enters the field, choose 1 Character. Dull it and Freeze "
				+ "it.");
	}

	/** 13-006C Xande. */
	private static CardData xande_13_006C() {
		return card("Xande", "Fire", "Backup", 2, 0, "Dark Lord", "PICTLOGICA", "III", false,
				"When Xande enters the field, you may receive 1 point of damage. When you do so, choose 1 "
				+ "Forward. Deal it 7000 damage.[[br]]   《Fire》《Dull》, put Xande into the Break Zone: Search for 1 "
				+ "Card Name Xande Forward and add it to your hand.");
	}

	/** 13-016H Rubicante. */
	private static CardData rubicante_13_016H() {
		return card("Rubicante", "Fire", "Forward", 2, 5000, "Archfiend", "IV", null, false,
				"When Rubicante is put from the field into the Break Zone, choose 1 Forward opponent controls. "
				+ "Deal it damage equal to Rubicante's power.[[br]]   Damage 3 -- Rubicante gains +3000 power.");
	}

	/** 13-022H Cid Randell. */
	private static CardData cidRandell_13_022H() {
		return card("Cid Randell", "Ice", "Forward", 4, 6000, "Judgemaster", "FFTA", null, false,
				"If you control a Card Name Remedi, Cid Randell gains +2000 power.[[br]]   Opposing Forwards "
				+ "entering the field will not trigger any auto-abilities (this applies to their own abilities and "
				+ "abilities triggered by your opponent's Forward entering their field).");
	}

	/** 13-027C Time Mage. */
	private static CardData timeMage_13_027C() {
		return genericCard("Time Mage", "Ice", "Backup", 3, 0, "Standard Unit", "FFEX", null, false,
				"When Time Mage enters the field, turn over one card at a time from the top of your deck until a "
				+ "Fire or Water card is revealed. Add it to your hand. Then, shuffle the other cards revealed and "
				+ "return them to the bottom of your deck.");
	}

	/** 13-062H Bhunivelze. */
	private static CardData bhunivelze_13_062H() {
		return card("Bhunivelze", "Earth", "Forward", 10, 10000, "God", "XIII", null, false,
				"The cost required to cast Bhunivelze is reduced by 2 for each Backup of a different Element you "
				+ "control, other than Multi-Element.[[br]]   Damage 3 -- Bhunivelze gains Brave and \"Bhunivelze "
				+ "can attack twice in the same turn.\"[[br]]   Damage 6 -- When Bhunivelze attacks, choose 1 "
				+ "Character. Break it.");
	}

	/** 13-064R Yang. */
	private static CardData yang_13_064R() {
		return card("Yang", "Earth", "Forward", 3, 7000, "Monk", "DFF", "IV", false,
				"Damage 1 -- When Yang enters the field, you may play 1 Job Monk or Card Name Monk from your "
				+ "hand onto the field.[[br]]   Damage 3 -- Yang gains \"The Job Monk Forwards and Card Name Monk "
				+ "Forwards you control gain +1000 power and Brave.\"");
	}

	/** 13-068C Alchemist. */
	private static CardData alchemist_13_068C() {
		return genericCard("Alchemist", "Earth", "Backup", 3, 0, "Standard Unit", "FFEX", null, false,
				"When Alchemist enters the field, turn over one card at a time from the top of your deck until "
				+ "an Ice or Wind card is revealed. Add it to your hand. Then, shuffle the other cards revealed "
				+ "and return them to the bottom of your deck.");
	}

	/** 13-069C Red Mage. */
	private static CardData redMage_13_069C() {
		return genericCard("Red Mage", "Lightning", "Backup", 3, 0, "Standard Unit", "FFEX", null, false,
				"When Red Mage enters the field, turn over one card at a time from the top of your deck until a "
				+ "Wind or Earth card is revealed. Add it to your hand. Then, shuffle the other cards revealed and "
				+ "return them to the bottom of your deck.");
	}

	/** 13-086R Agrias. */
	private static CardData agrias_13_086R() {
		return card("Agrias", "Water", "Forward", 5, 9000, "Knight", "DFF", "FFT", true,
				"[[ex]]EX BURST[[/]] When Agrias enters the field, if you control a Job Princess, draw 1 "
				+ "card.[[br]]   When Agrias enters the field, choose 1 Forward opponent controls. If you control "
				+ "a Card Name Ovelia, it loses 7000 power until the end of the turn.[[br]]   Damage 5 -- When "
				+ "Agrias enters the field, you may search for 1 Category FFT Forward and add it to your hand.");
	}

	/** 13-092C Sage. */
	private static CardData sage_13_092C() {
		return genericCard("Sage", "Water", "Backup", 3, 0, "Standard Unit", "FFEX", null, false,
				"When Sage enters the field, turn over one card at a time from the top of your deck until an "
				+ "Earth or Lightning card is revealed. Add it to your hand. Then, shuffle the other cards "
				+ "revealed and return them to the bottom of your deck.");
	}

	/** 13-115L Golbez. */
	private static CardData golbez_13_115L() {
		return card("Golbez", "Wind/Lightning", "Forward", 5, 9000, "Warlock", "LOV", "IV", true,
				"[[ex]]EX BURST[[/]] When Golbez enters the field, you may search for 1 card of cost 2 and add "
				+ "it to your hand.[[br]]   Damage 6 -- When Golbez enters the field, you may search for up to 3 "
				+ "cards of cost 2 and add them to your hand. Then, you may play any number of Job Archfiend from "
				+ "your hand onto the field.");
	}

	/** 13-127H Chime. */
	private static CardData chime_13_127H() {
		return card("Chime", "Water/Ice", "Forward", 4, 8000, "Minister", "FFCC", null, false,
				"When Chime enters the field, choose 2 Forwards opponent controls. Return the first Forward to "
				+ "its owner's hand, dull and Freeze the other.");
	}

	/** 12-041C Dancer. */
	private static CardData dancer_12_041C() {
		return genericCard("Dancer", "Wind", "Forward", 3, 7000, "Standard Unit", "XIV", null, false,
				"《Dull》: Deal 1000 damage to all the Forwards opponent controls. If you control 7 or more Wind "
				+ "Characters, deal 4000 damage to all the Forwards opponent controls instead.");
	}

	/** 12-051R Paine. */
	private static CardData paine_12_051R() {
		return card("Paine", "Wind", "Forward", 4, 8000, "Gullwings", "X", null, false,
				"When Paine enters the field, if you control 3 or more Category X Characters, activate all the "
				+ "Backups you control.");
	}

	/** 12-054C Moogle (Crystal Hunt). */
	private static CardData moogleCrystalHunt_12_054C() {
		return card("Moogle (Crystal Hunt)", "Wind", "Forward", 3, 7000, "Moogle", "Crystal Hunt", null, true,
				"Damage 3 -- [[ex]]EX BURST[[/]] When Moogle (Crystal Hunt) enters the field, draw 1 card.");
	}

	/** 12-066C Vaigali. */
	private static CardData vaigali_12_066C() {
		return card("Vaigali", "Earth", "Backup", 4, 0, "Guild Master", "FFCC", null, false,
				"When Vaigali enters the field, you may search for 1 Multi-Element card and add it to your hand.");
	}

	/** 12-073C Red Mage. */
	private static CardData redMage_12_073C() {
		return genericCard("Red Mage", "Lightning", "Backup", 3, 0, "Standard Unit", "XIV", null, false,
				"When you cast a Summon, you may put Red Mage into the Break Zone. When you do so, choose 1 "
				+ "Forward of cost 3 or less. Break it.");
	}

	/** 12-092C Ultros. */
	private static CardData ultros_12_092C() {
		return card("Ultros", "Water", "Forward", 2, 5000, "Octopus", "THEATRHYTHM", "VI", false,
				"When Ultros is put from the field into the Break Zone, you may pay 《Water》. When you do so, "
				+ "search for 1 Card Name Ultros and add it to your hand.[[br]]   《Dull》, put Ultros into the "
				+ "Break Zone: Choose 1 Forward. It loses 5000 power until the end of the turn.");
	}

	/** 12-110L Neo Exdeath. */
	private static CardData neoExdeath_12_110L() {
		return card("Neo Exdeath", "Dark", "Forward", 6, 10000, "None", "MOBIUS", "V", false,
				"Neo Exdeath is also Card Name Exdeath in all situations.[[br]]   At the end of each of your "
				+ "opponent's turns, your opponent selects 1 Character they control. Put it into the Break Zone. "
				+ "[[br]]   When Neo Exdeath is chosen by your opponent's Summons or abilities, your opponent "
				+ "selects 1 Character they control. Put it into the Break Zone.");
	}

	/** 12-123R Urianger. */
	private static CardData urianger_12_123R() {
		return card("Urianger", "Lightning/Water", "Forward", 3, 7000, "Scion of the Seventh Dawn", "XIV", null, false,
				"When Urianger enters the field, you may play 1 Monster of cost 2 or less from your hand onto "
				+ "the field. When you do so, choose 1 Forward opponent controls. Return it to its owner's hand.");
	}

	/** 11-015L Braska's Final Aeon. */
	private static CardData braskaSFinalAeon_11_015L() {
		return card("Braska's Final Aeon", "Fire", "Forward", 7, 10000, "Final Aeon", "MOBIUS", "X", false,
				"When Braska's Final Aeon attacks or is chosen by your opponent's Summons or abilities, choose "
				+ "up to 1 Forward opponent controls. Deal it 10000 damage. This damage cannot be reduced.[[br]]   "
				+ "Discard Braska's Final Aeon: Choose 1 Forward. Deal it 10000 damage. You can only use this "
				+ "ability if you control 2 or more Job Summoner Forwards and if Braska's Final Aeon is in your "
				+ "hand.");
	}

	/** 11-052C Tiamat. */
	private static CardData tiamat_11_052C() {
		return card("Tiamat", "Wind", "Forward", 5, 9000, "Chaos", "I", null, false,
				"When Tiamat enters the field, you may pay 《Wind》《Wind》《1》. When you do so, choose 1 Forward of "
				+ "cost 5 or more. Break it.[[br]]   When Tiamat is put from the field into the Break Zone, you "
				+ "may search for 1 Job Chaos other than Card Name Tiamat and add it to your hand.");
	}

	/** 11-054R Hooded Man. */
	private static CardData hoodedMan_11_054R() {
		return card("Hooded Man", "Wind", "Forward", 4, 7000, "???", "IV", null, true,
				"You cannot play Hooded Man or Card Name Kain while already in control of either "
				+ "Character.[[br]]   [[ex]]EX BURST[[/]] When Hooded Man enters the field, choose 1 Category IV "
				+ "Forward other than Card Name Hooded Man in your Break Zone. Add it to your hand.[[br]]   At the "
				+ "end of each of your turns, activate all the Category IV Forwards you control.");
	}

	/** 11-072R Tyro. */
	private static CardData tyro_11_072R() {
		return card("Tyro", "Earth", "Backup", 5, 0, "Apprentice", "PICTLOGICA", "FFRK", true,
				"[[ex]]EX BURST[[/]] When Tyro enters the field, you may search for 1 Forward and add it to your "
				+ "hand.[[br]]   If Tyro is on the field, Tyro can produce CP of any Element. [[br]]   Damage 5 -- "
				+ "《Dull》: Choose 1 Forward. It gains +2000 power until the end of the turn.");
	}

	/** 11-082C Lich. */
	private static CardData lich_11_082C() {
		return card("Lich", "Earth", "Forward", 5, 9000, "Chaos", "I", null, false,
				"When Lich enters the field, you may pay 《Earth》《1》. When you do so, choose 1 Forward in your "
				+ "Break Zone. Add it to your hand.[[br]]   When Lich is put from the field into the Break Zone, "
				+ "you may search for 1 Job Chaos other than Card Name Lich and add it to your hand.");
	}

	/** 11-085C Red Mage. */
	private static CardData redMage_11_085C() {
		return genericCard("Red Mage", "Lightning", "Forward", 4, 7000, "Standard Unit", "Special", null, false,
				"When Red Mage enters the field, if the cost to play Red Mage was paid with CP of 2 or more "
				+ "different Elements, until the end of the turn, Red Mage gains +2000 power and Haste.");
	}

	/** 11-094C Zangetsu. */
	private static CardData zangetsu_11_094C() {
		return card("Zangetsu", "Lightning", "Forward", 4, 8000, "Ninja", "PICTLOGICA", "IV", false,
				"When Zangetsu enters the field, choose up to the same number of Characters opponent controls as "
				+ "the Job Ninja or Card Name Ninja you control. Dull them.[[br]]   《Lightning》: Zangetsu gains "
				+ "Haste until the end of the turn.");
	}

	/** 11-097H Nyx. */
	private static CardData nyx_11_097H() {
		return card("Nyx", "Lightning", "Forward", 4, 8000, "Kingsglaive", "XV", null, false,
				"When Nyx enters the field, put 1 Job Kingsglaive you control into the Break Zone. When you do "
				+ "so, choose 1 Forward. Break it.[[br]]   When a Job Kingsglaive other than Nyx you control is "
				+ "put from the field into the Break Zone, select 1 of the 2 following actions. \"Nyx gains Haste "
				+ "until the end of the turn.\" \"Choose 1 Forward. Dull it.\"");
	}

	/** 11-098C Puck. */
	private static CardData puck_11_098C() {
		return card("Puck", "Lightning", "Backup", 4, 0, "Prince", "IX", null, false,
				"When Puck enters the field, choose 1 Forward of cost 1 in your Break Zone. Play it onto the "
				+ "field.[[br]]   Damage 3 -- When Puck enters the field, choose 1 Forward of cost 2 in your Break "
				+ "Zone. Play it onto the field.");
	}

	/** 11-106H Owzer. */
	private static CardData owzer_11_106H() {
		return card("Owzer", "Water", "Backup", 5, 0, "Millionaire", "VI", null, false,
				"When Owzer enters the field, choose 1 Forward of cost 2 or less opponent controls. You gain "
				+ "control of it.");
	}

	/** 11-107C Izayoi. */
	private static CardData izayoi_11_107C() {
		return card("Izayoi", "Water", "Forward", 3, 7000, "Ninja", "PICTLOGICA", "IV", false,
				"When Izayoi enters the field, choose 1 Forward opponent controls. If its cost is equal to or "
				+ "less than the number of Job Ninja or Card Name Ninja you control, return it to its owner's "
				+ "hand.[[br]]   《Water》: During this turn, the next damage dealt to Izayoi is reduced by 2000 "
				+ "instead.");
	}

	/** 11-111C Kraken. */
	private static CardData kraken_11_111C() {
		return card("Kraken", "Water", "Forward", 5, 9000, "Chaos", "I", null, false,
				"When Kraken enters the field, you may pay 《Water》《1》. When you do so, choose 1 Forward. Return "
				+ "it to its owner's hand. [[br]]   When Kraken is put from the field into the Break Zone, you may "
				+ "search for 1 Job Chaos other than Card Name Kraken and add it to your hand.");
	}

	/** 11-131S Barret. */
	private static CardData barret_11_131S() {
		return card("Barret", "Fire", "Forward", 3, 7000, "AVALANCHE Operative", "VII", null, true,
				"[[ex]]EX BURST[[/]] When Barret enters the field, choose 1 Forward opponent controls. Deal it "
				+ "1000 damage for each Category VII Character you control. If Barret results from an EX Burst, "
				+ "deal it 2000 damage for each Category VII Character you control instead.");
	}

	/** 11-137S Azul. */
	private static CardData azul_11_137S() {
		return card("Azul", "Lightning", "Forward", 3, 7000, "Tsviets", "VII", null, false,
				"When Azul is dealt damage, choose 1 Forward opponent controls. Deal it 1000 damage.[[br]]   "
				+ "Damage 3 -- Azul gains \"When Azul is dealt damage, choose 1 Forward opponent controls. Deal it "
				+ "2000 damage.\"[[br]]   Damage 5 -- Azul gains \"When Azul is dealt damage, choose 1 Forward "
				+ "opponent controls. Deal it 3000 damage.\"");
	}

	/** 10-007H Zack. */
	private static CardData zack_10_007H() {
		return card("Zack", "Fire", "Forward", 4, 8000, "SOLDIER", "VII", null, false,
				"When Zack or a Fire Forward enters your field, choose 1 Forward opponent controls. Deal it 2000 "
				+ "damage.[[br]]   When Zack or a Category VII Forward enters your field, choose 1 Forward "
				+ "opponent controls. Deal it 2000 damage.");
	}

	/** 10-018C Ryid. */
	private static CardData ryid_10_018C() {
		return card("Ryid", "Fire", "Forward", 5, 9000, "Agito Cadet", "TYPE-0", null, false,
				"Damage 3 -- Ryid gains Brave.[[br]]   Damage 5 -- Ryid gains +2000 power.");
	}

	/** 10-024R Eald'narche. */
	private static CardData ealdNarche_10_024R() {
		return card("Eald'narche", "Ice", "Forward", 5, 9000, "Descendant of the Zilart", "XI", null, false,
				"If you control a Card Name Kam'lanaut, the cost for playing Eald'narche onto the field is "
				+ "reduced by 2.[[br]]   When Eald'narche enters the field, choose 1 Character opponent controls. "
				+ "Dull it.[[br]]   Damage 5 -- When Eald'narche enters the field, choose up to 3 Characters "
				+ "opponent controls. Dull them.");
	}

	/** 10-032H Scylla. */
	private static CardData scylla_10_032H() {
		return card("Scylla", "Ice", "Forward", 7, 7000, "Scylla", "III", null, false,
				"When Scylla enters the field, choose 1 dull Forward opponent controls. Break it. Your opponent "
				+ "discards 1 card from his/her hand.");
	}

	/** 10-037C Dendrobium. */
	private static CardData dendrobium_10_037C() {
		return genericCard("Dendrobium", "Ice", "Monster", 2, 0, "Stalker", "XIII", null, false,
				"Put Dendrobium into the Break Zone: Choose 1 Forward. Freeze it. Draw 1 card.[[br]]   Damage 5 "
				+ "-- Put Dendrobium into the Break Zone: Choose up to 3 Forwards. Freeze them. Draw 1 card. (If "
				+ "you have received 5 points of damage or more, this Character has this ability.)");
	}

	/** 10-043H Arc. */
	private static CardData arc_10_043H() {
		return card("Arc", "Wind", "Forward", 5, 8000, "Warrior of Light", "III", null, true,
				"EX BURST When Arc enters the field, choose up to 3 Job Standard Unit or Job Warrior of Light. "
				+ "Activate them.[[br]]   Damage 3 -- When Arc enters the field, choose 1 Job Standard Unit or Job "
				+ "Warrior of Light of cost 3 or less in your Break Zone. Play it onto the field.");
	}

	/** 10-064R Atlas. */
	private static CardData atlas_10_064R() {
		return card("Atlas", "Earth", "Forward", 3, 7000, "Colossus", "XIII", null, false,
				"Damage 2 -- Atlas gains +1000 power.[[br]]   Damage 5 -- Atlas gains +2000 power.");
	}

	/** 10-071R Cid (MOBIUS). */
	private static CardData cidMobius_10_071R() {
		return card("Cid (MOBIUS)", "Earth", "Backup", 4, 0, "Veteran", "MOBIUS", null, true,
				"EX BURST When Cid (MOBIUS) enters the field, choose 1 Forward opponent controls. Deal it 4000 "
				+ "damage for each Forward you control.");
	}

	/** 10-095H Desch. */
	private static CardData desch_10_095H() {
		return card("Desch", "Lightning", "Forward", 3, 7000, "Mysterious Man", "III", null, false,
				"Damage 1 -- Desch gains First Strike.[[br]]   Damage 3 -- When Desch attacks, deal 2000 damage "
				+ "to all the Forwards opponent controls.[[br]]   Damage 5 -- Put Desch into the Break Zone: "
				+ "Choose 1 Forward. Break it.");
	}

	/** 10-103R Ramza. */
	private static CardData ramza_10_103R() {
		return card("Ramza", "Lightning", "Forward", 5, 7000, "Knight", "DFF", "FFT", true,
				"EX BURST When Ramza enters the field, choose 1 Category FFT Character other than Card Name "
				+ "Ramza in your Break Zone. Add it to your hand.[[br]] Damage 5 -- When Ramza enters the field, "
				+ "you may play 1 Category FFT Character from your hand onto the field.[[br]] [[s]]Chirijiraden "
				+ "[[/]]《S》《Lightning》《Lightning》《Lightning》《Dull》: Deal 7000 damage to all the Forwards opponent "
				+ "controls.");
	}

	/** 10-115C Cecil. */
	private static CardData cecil_10_115C() {
		return card("Cecil", "Water", "Backup", 2, 0, "Paladin", "IV", null, false,
				"《Dull》, put Cecil into the Break Zone: During this turn, if a Forward you control is dealt "
				+ "damage, reduce the damage by 2000 instead.");
	}

	/** 10-135S Kefka. */
	private static CardData kefka_10_135S() {
		return card("Kefka", "Earth", "Forward", 4, 7000, "Mage", "DFF", "VI", true,
				"[[ex]]EX BURST [[/]]When Kefka attacks, choose 1 Forward you control and 1 Forward opponent "
				+ "controls. Break them.[[br]]   When a Character opponent controls is put from the field into the "
				+ "Break Zone, you may pay 《1》. When you do so, draw 1 card.");
	}

	/** 10-137S Lightning. */
	private static CardData lightning_10_137S() {
		return card("Lightning", "Lightning", "Forward", 4, 8000, "Ravager", "DFF", "XIII", false,
				"During this turn, if you attacked with 2 or more Forwards you controlled, the cost for playing "
				+ "Lightning onto the field is reduced by 3.[[br]]   [[s]]Crushing Blow[[/]] 《S》《Dull》: Choose up "
				+ "to 2 Forwards. Dull them. Draw 1 card.");
	}

	/** 10-139S Onion Knight. */
	private static CardData onionKnight_10_139S() {
		return card("Onion Knight", "Light", "Forward", 3, 7000, "Onion Knight", "DFF", "III", false,
				"When Onion Knight deals damage to your opponent, you may search for 1 Card Name Onion Knight "
				+ "and add it to your hand.[[br]]   [[s]]Blade Torrent [[/]]《S》: Until the end of the turn, Onion "
				+ "Knight gains +1000 power, Haste, First Strike and Brave.[[br]]   [[s]]Meteor [[/]]《S》《Dull》: "
				+ "Choose 1 Forward. Deal it damage equal to Onion Knight's power.");
	}

	/** 9-001C Irvine. */
	private static CardData irvine_9_001C() {
		return card("Irvine", "Fire", "Backup", 4, 0, "Sniper", "PICTLOGICA", "VIII", false,
				"When Irvine enters the field, choose 1 Forward. Deal it 5000 damage. If the number of Forwards "
				+ "your opponent controls is greater than the number of Forwards you control, deal it 7000 damage "
				+ "instead.");
	}

	/** 9-009R Gijuk. */
	private static CardData gijuk_9_009R() {
		return card("Gijuk", "Fire", "Forward", 2, 5000, "Headhunter", "XII", null, false,
				"When Gijuk enters the field, if you control 4 or more Job Headhunter Forwards, deal 5000 damage "
				+ "to all the Forwards opponent controls.");
	}

	/** 9-013C Fusilier. */
	private static CardData fusilier_9_013C() {
		return genericCard("Fusilier", "Fire", "Backup", 2, 0, "Standard Unit", "FFTA2", null, false,
				"When Fusilier enters the field, you may pay 《Fire》. When you do so, choose 1 Forward. Deal it "
				+ "5000 damage.");
	}

	/** 9-023R Quistis. */
	private static CardData quistis_9_023R() {
		return card("Quistis", "Ice", "Forward", 4, 7000, "Instructor", "VIII", null, true,
				"[[ex]]EX BURST[[/]] When Quistis enters the field, reveal the top 5 cards of your deck. Add 1 "
				+ "Category VIII Forward among them to your hand and return the other cards to the bottom of your "
				+ "deck in any order.[[br]] The Job SeeD Candidate Forwards you control gain +1000 "
				+ "power.[[br]][[s]]LV? Death[[/]] 《S》《Ice》《Dull》: Reveal the top card of your deck. Break all "
				+ "Forwards opponent controls with the same cost as the revealed card. Add the revealed card to "
				+ "your hand.");
	}

	/** 9-026C Cid Aulstyne. */
	private static CardData cidAulstyne_9_026C() {
		return card("Cid Aulstyne", "Ice", "Backup", 3, 0, "Grand Marshal", "PICTLOGICA", "TYPE-0", true,
				"[[ex]]EX BURST[[/]] When Cid Aulstyne enters the field, choose 1 Forward. If your opponent has "
				+ "2 cards or less in his/her hand, dull it and Freeze it. If your opponent has no cards in "
				+ "his/her hand, break it instead.");
	}

	/** 9-040C Nu Mou. */
	private static CardData nuMou_9_040C() {
		return genericCard("Nu Mou", "Ice", "Backup", 2, 0, "Standard Unit", "XII", null, false,
				"When Nu Mou enters the field, you may pay 《Ice》. When you do so, choose 1 Forward or Monster. "
				+ "Dull it and Freeze it.");
	}

	/** 9-041R Ahriman. */
	private static CardData ahriman_9_041R() {
		return genericCard("Ahriman", "Wind", "Monster", 3, 7000, "Ahriman", "Crystal Hunt", null, false,
				"During your turn, Ahriman also becomes a Forward with 7000 power.[[br]] When Ahriman deals "
				+ "damage to your opponent, choose 1 Forward of cost 5 or more opponent controls. Break it.");
	}

	/** 9-044C Viera. */
	private static CardData viera_9_044C() {
		return genericCard("Viera", "Wind", "Backup", 2, 0, "Standard Unit", "XII", null, false,
				"When Viera enters the field, you may pay 《Wind》. When you do so, deal 3000 damage to all the "
				+ "Forwards opponent controls.");
	}

	/** 9-056H The Magus Sisters. */
	private static CardData theMagusSisters_9_056H() {
		return card("The Magus Sisters", "Wind", "Forward", 4, 7000, "Mage", "IV", null, true,
				"[[ex]]EX BURST[[/]] When The Magus Sisters enters the field, you may search for 1 card and put "
				+ "it into the Break Zone.[[br]] When The Magus Sisters is put from the field into the Break Zone, "
				+ "you may remove 1 Card Name The Magus Sisters in your Break Zone from the game. When you do so, "
				+ "play The Magus Sisters from your Break Zone onto the field dull.");
	}

	/** 9-058L Luso. */
	private static CardData luso_9_058L() {
		return card("Luso", "Wind", "Forward", 2, 5000, "Youth", "FFTA2", null, false,
				"For each Forward other than Luso you control, Luso gains +1000 power.[[br]] If there are 3 or "
				+ "more different Elements among Forwards you control, Luso gains First Strike and Brave.[[br]] "
				+ "When Luso attacks, if there are 5 or more different Elements among Forwards you control, Luso "
				+ "deals your opponent 1 point of damage.");
	}

	/** 9-062H Vincent. */
	private static CardData vincent_9_062H() {
		return card("Vincent", "Earth", "Forward", 3, 7000, "Gunslinger", "VII", null, false,
				"When Vincent enters the field, you may put 1 Backup you control into the Break Zone. When you "
				+ "do so, choose 1 dull Forward. Deal it 8000 damage.[[br]][[s]]Zoom[[/]] 《S》《Earth》《1》《Dull》: "
				+ "Choose 1 Backup of cost 3 or more. Break it.");
	}

	/** 9-067C Cid Garlond. */
	private static CardData cidGarlond_9_067C() {
		return card("Cid Garlond", "Earth", "Backup", 4, 0, "Magitek Engineer", "XIV", null, false,
				"When Cid Garlond enters the field, reveal the top 4 cards of your deck. Add 1 Earth Character "
				+ "among them to your hand and return the other cards to the bottom of your deck in any "
				+ "order.[[br]] 《Earth》《Dull》, put Cid Garlond into the Break Zone: Choose 1 Category XIV "
				+ "Character in your Break Zone. Add it to your hand.");
	}

	/** 9-071C Bangaa. */
	private static CardData bangaa_9_071C() {
		return genericCard("Bangaa", "Earth", "Backup", 2, 0, "Standard Unit", "XII", null, false,
				"When Bangaa enters the field, you may pay 《Earth》. When you do so, choose 1 Earth Character in "
				+ "your Break Zone. Add it to your hand.");
	}

	/** 9-086R Goblin. */
	private static CardData goblin_9_086R() {
		return genericCard("Goblin", "Lightning", "Monster", 2, 5000, "Goblin", "Crystal Hunt", null, false,
				"During your turn, Goblin also becomes a Forward with 5000 power.[[br]] When Goblin deals damage "
				+ "to your opponent, you may put Goblin into the Break Zone. When you do so, draw 2 cards.");
	}

	/** 9-090C Sage. */
	private static CardData sage_9_090C() {
		return genericCard("Sage", "Lightning", "Backup", 2, 0, "Standard Unit", "FFTA2", null, false,
				"When Sage enters the field, you may pay 《Lightning》. When you do so, choose 1 Summon in your "
				+ "Break Zone. Add it to your hand.");
	}

	/** 9-108C White Mage. */
	private static CardData whiteMage_9_108C() {
		return genericCard("White Mage", "Water", "Backup", 2, 0, "Standard Unit", "FFTA2", null, false,
				"When White Mage enters the field, you may pay 《Water》. When you do so, draw 1 card.");
	}

	/** 9-121L Wol. */
	private static CardData wol_9_121L() {
		return card("Wol", "Light", "Forward", 4, 9000, "Warrior of Light", "MOBIUS", null, false,
				"You can only pay with Fire CP, Wind CP, Earth CP or Water CP and you must use CP of 2 or more "
				+ "different Elements to play Wol onto the field.[[br]] When Wol is put from the field into the "
				+ "Break Zone, choose 1 Forward opponent controls. Deal it 9000 damage.");
	}

	/** 9-122H Locke. */
	private static CardData locke_9_122H() {
		return card("Locke", "Light", "Forward", 4, 8000, "Treasure Hunter", "DFF", "VI", false,
				"At the beginning of your Main Phase 1, reveal the top 2 cards of your deck. Add 1 Category VI "
				+ "Character among them to your hand and return the other cards to the bottom of your deck in any "
				+ "order.[[br]] When Locke attacks, all the Category VI Forwards other than Locke you control gain "
				+ "+2000 power until the end of the turn.");
	}

	/** 8-013C Warrior. */
	private static CardData warrior_8_013C() {
		return genericCard("Warrior", "Fire", "Forward", 2, 5000, "Standard Unit", "XIV", null, false,
				"《Fire》: If Warrior deals damage to a Forward this turn, the damage increases by 2000 instead.");
	}

	/** 8-018R Marche. */
	private static CardData marche_8_018R() {
		return card("Marche", "Fire", "Forward", 4, 5000, "Clan Leader", "FFTA", null, true,
				"[[ex]]EX BURST [[/]]When Marche enters the field, reveal the top 5 cards of your deck. Add 1 "
				+ "Fire Character among them to your hand and return the other cards to the bottom of your deck in "
				+ "any order.");
	}

	/** 8-056R Deathgaze (IX). */
	private static CardData deathgazeIx_8_056R() {
		return card("Deathgaze (IX)", "Wind", "Monster", 3, 0, "Deathgaze", "IX", null, false,
				"When Deathgaze (IX) enters the field, choose 1 Forward opponent controls. Remove it from the "
				+ "game for as long as Deathgaze (IX) is on the field.");
	}

	/** 8-074H Gladiolus. */
	private static CardData gladiolus_8_074H() {
		return card("Gladiolus", "Earth", "Forward", 4, 9000, "Retainer", "XV", null, false,
				"You can only pay with Earth CP to play Gladiolus onto the field.[[br]] If you control Card Name "
				+ "Noctis, the cost for playing Gladiolus onto the field is reduced by 2.[[br]][[s]]Cyclone[[/]] "
				+ "《S》《Earth》《Earth》《2》《Dull》: Deal 7000 damage to all the Forwards opponent controls.");
	}

	/** 8-082R Prompto. */
	private static CardData prompto_8_082R() {
		return card("Prompto", "Earth", "Backup", 6, 0, "Retainer", "XV", null, true,
				"[[ex]]EX BURST[[/]] When Prompto enters the field, choose 1 Forward opponent controls. If your "
				+ "opponent controls 4 or more Forwards, remove it from the game.[[br]] When Prompto enters the "
				+ "field, choose 1 Forward opponent controls. Break it.");
	}

	/** 8-089R Ark Angel EV. */
	private static CardData arkAngelEv_8_089R() {
		return card("Ark Angel EV", "Lightning", "Forward", 4, 8000, "Ark Angel", "XI", null, true,
				"[[ex]]EX BURST[[/]] When Ark Angel EV enters the field, choose 1 active Forward opponent "
				+ "controls. Dull it and deal it 2000 damage.[[br]] 《5》: During this turn, the next damage dealt "
				+ "to Ark Angel EV by a Forward becomes 0 instead.");
	}

	/** 8-092C Alphinaud. */
	private static CardData alphinaud_8_092C() {
		return card("Alphinaud", "Lightning", "Forward", 2, 5000, "Scion of the Seventh Dawn", "XIV", null, false,
				"When Alphinaud attacks, dull all the Forwards with a power equal or inferior to Alphinaud's "
				+ "opponent controls.");
	}

	/** 8-118H Steiner. */
	private static CardData steiner_8_118H() {
		return card("Steiner", "Water", "Forward", 3, 7000, "Knight", "IX", null, false,
				"When Steiner or a Category IX Character enters your field, activate Steiner.[[br]] 《Dull》: "
				+ "Choose 1 Forward. It gains +1000 power until the end of the turn. During this turn, the next "
				+ "damage dealt to it is reduced by 1000 instead.");
	}

	/** 7-003C Red Mage. */
	private static CardData redMage_7_003C() {
		return genericCard("Red Mage", "Fire", "Forward", 2, 4000, "Standard Unit", "V", null, false,
				"When Red Mage enters the field, if the cost paid to play Red Mage included Ice CP, your "
				+ "opponent discards 1 card from his/her hand.[[br]] 《Dull》: Choose 1 Forward. Deal it 1000 damage.");
	}

	/** 7-004C Fritt. */
	private static CardData fritt_7_004C() {
		return card("Fritt", "Fire", "Forward", 2, 4000, "Ifrit", "WOFF", null, false,
				"Fritt is also a Monster in all situations.[[br]] When Fritt is put from the field into the "
				+ "Break Zone, you may search for 1 Card Name Ifrit and add it to your hand.");
	}

	/** 7-015R Fang. */
	private static CardData fang_7_015R() {
		return card("Fang", "Fire", "Forward", 3, 7000, "Commando", "XIII", null, false,
				"If you control 2 or more Category XIII Characters, Fang gains First Strike.[[br]] If you "
				+ "control 4 or more Category XIII Characters, Fang gains Haste.[[br]] If you control 6 or more "
				+ "Category XIII Characters, Fang gains +3000 power.");
	}

	/** 7-034L Sephiroth. */
	private static CardData sephiroth_7_034L() {
		return genericCard("Sephiroth", "Ice", "Forward", 7, 8000, "War Hero", "DFF", "VII", false,
				"When Sephiroth enters the field, your opponent discards 2 cards from his/her hand.[[br]] When "
				+ "Sephiroth attacks, choose 1 Forward opponent controls. If your opponent has no cards in his/her "
				+ "hand, dull it and Freeze it.[[br]][[s]]Shadow Flare[[/]] 《S》《Ice》: Your opponent discards 2 "
				+ "cards from his/her hand. You can only use this ability during your Main Phase.");
	}

	/** 7-035L Serah. */
	private static CardData serah_7_035L() {
		return card("Serah", "Ice", "Forward", 4, 8000, "Commando", "XIII", null, false,
				"When Serah enters the field, choose as many Characters as the Category XIII Characters you "
				+ "control. Dull them.[[br]] Dull 2 active Category XIII Characters: Choose 1 Forward. Freeze it.");
	}

	/** 7-056H Dorgann. */
	private static CardData dorgann_7_056H() {
		return card("Dorgann", "Wind", "Forward", 4, 8000, "Dawn Warrior", "V", null, false,
				"When a Backup enters your field, choose 1 Forward opponent controls. Remove it from the game "
				+ "for as long as Dorgann is on the field.");
	}

	/** 7-071R Shantotto. */
	private static CardData shantotto_7_071R() {
		return card("Shantotto", "Earth", "Backup", 2, 0, "Mage", "DFF", "XI", false,
				"《1》: Name 1 Element other than Light and Dark. Shantotto becomes the named Element until the "
				+ "end of the turn. You can only use this ability once per turn.");
	}

	/** 7-097R Barbara. */
	private static CardData barbara_7_097R() {
		return card("Barbara", "Lightning", "Forward", 4, 7000, "Dragoon", "FFL", null, false,
				"The Job Dragoon and Card Name Dragoon you control gain Haste.[[br]] 《1》: Choose 1 Job Dragoon "
				+ "or Card Name Dragoon other than Barbara. It gains +1000 power until the end of the turn.");
	}

	/** 7-120H Faris. */
	private static CardData faris_7_120H() {
		return card("Faris", "Water", "Forward", 4, 6000, "Warrior of Light", "V", null, false,
				"If you control a Card Name Lenna Forward, Faris gains +2000 power.[[br]] When Faris enters the "
				+ "field, reveal the top 5 cards of your deck. Add 1 Category V Character among them to your hand "
				+ "and return the other cards to the bottom of your deck in any order.");
	}

	/** 7-122C Mime. */
	private static CardData mime_7_122C() {
		return genericCard("Mime", "Water", "Forward", 4, 8000, "Standard Unit", "V", null, false,
				"When Mime enters the field, if the cost paid to play Mime included Earth CP, all the Forwards "
				+ "you control gain +2000 power until the end of the turn.");
	}

	/** 7-132S Firion. */
	private static CardData firion_7_132S() {
		return card("Firion", "Fire", "Forward", 2, 2000, "Warrior", "DFF", "II", true,
				"[[ex]]EX BURST[[/]] When Firion enters the field, choose 1 Forward opponent controls. Deal it "
				+ "2000 damage for each Forward you control (up to 3 Forwards).");
	}

	/** 6-012R Zell. */
	private static CardData zell_6_012R() {
		return card("Zell", "Fire", "Forward", 4, 4000, "SeeD Candidate", "DFF", "VIII", false,
				"Haste[[br]] When Zell enters the field, select 1 of the 2 following actions.[[br]] \"Choose 1 "
				+ "Forward opponent controls. Deal it damage equal to Zell's power.\"[[br]] \"Choose 1 Monster of "
				+ "cost 2 or less opponent controls. Break it.\"");
	}

	/** 6-020H Montblanc. */
	private static CardData montblanc_6_020H() {
		return card("Montblanc", "Fire", "Backup", 2, 0, "Moogle", "FFTA2", null, false,
				"《Fire》《Dull》: Choose 1 Forward. Deal it 2000 damage for each Category FFTA2 Forward you control.");
	}

	/** 6-084L Leo. */
	private static CardData leo_6_084L() {
		return card("Leo", "Earth", "Forward", 1, 1000, "King", "FFCC", null, false,
				"For each Character other than Leo you control, Leo gains +1000 power.[[br]] Backups you control "
				+ "can produce CP of any Element.");
	}

	/** 6-087R Edea. */
	private static CardData edea_6_087R() {
		return card("Edea", "Lightning", "Backup", 4, 0, "Witch", "THEATRHYTHM", "VIII", false,
				"《Dull》, put Edea into the Break Zone: Choose 1 Job Witch in your Break Zone. Play it onto the "
				+ "field. You can only use this ability during your turn.");
	}

	/** 6-090H Kain. */
	private static CardData kain_6_090H() {
		return card("Kain", "Lightning", "Forward", 3, 9000, "Dragoon", "IV", null, false,
				"When a Dark Forward of your opponent enters the field, your opponent gains control of "
				+ "Kain.[[br]] When a Dark Forward you control is put from the field into the Break Zone, your "
				+ "opponent gains control of Kain.");
	}

	/** 6-099H Frimelda. */
	private static CardData frimelda_6_099H() {
		return card("Frimelda", "Lightning", "Forward", 4, 8000, "Sword Saint", "FFTA2", null, false,
				"When Frimelda enters the field, choose 1 active Forward opponent controls. Deal it 4000 damage "
				+ "for each Job Sword Saint you control.");
	}

	/** 6-101R Raijin. */
	private static CardData raijin_6_101R() {
		return card("Raijin", "Lightning", "Backup", 2, 0, "Disciplinary Committee Member", "DFF", "VIII", false,
				"The Card Name Seifer you control gains Haste.[[br]][[s]]Raijin Special[[/]] 《S》《Dull》: Choose 1 "
				+ "active Forward. Deal it 8000 damage. You can only use this ability if you control Card Name "
				+ "Seifer and Card Name Fujin.");
	}

	/** 5-042C Trickster. */
	private static CardData trickster_5_042C() {
		return genericCard("Trickster", "Ice", "Forward", 3, 7000, "Standard Unit", "FFTA2", null, false,
				"If Trickster is dealt damage by a Forward with Haste or First Strike, the damage becomes 0 "
				+ "instead.");
	}

	/** 5-046R Buccaboo. */
	private static CardData buccaboo_5_046R() {
		return genericCard("Buccaboo", "Ice", "Monster", 2, 0, "Goblin", "XIII", null, false,
				"When a Character of your opponent enters the field, put Buccaboo into the Break Zone. If you do "
				+ "so, your opponent discards 2 cards from his/her hand.");
	}

	/** 5-052H Arc. */
	private static CardData arc_5_052H() {
		return card("Arc", "Wind", "Forward", 3, 7000, "Warrior of Light", "III", null, false,
				"The Job Warrior of Light Forwards and Wind Forwards other than Arc you control gain +1000 "
				+ "power.[[br]] Dull a total of 3 active Wind Forwards or Wind Backups: Choose 1 Forward. It "
				+ "cannot be blocked by a Forward of cost 3 or more this turn.");
	}

	/** 5-071R Leyak. */
	private static CardData leyak_5_071R() {
		return genericCard("Leyak", "Wind", "Monster", 1, 0, "Spook", "XIII", null, false,
				"When a Forward opponent controls attacks, put Leyak into the Break Zone. If you do so, activate "
				+ "all the Characters you control. Draw 1 card.");
	}

	/** 5-074H Ingus. */
	private static CardData ingus_5_074H() {
		return card("Ingus", "Earth", "Forward", 3, 7000, "Warrior of Light", "III", null, false,
				"The Job Warrior of Light Forwards and Earth Forwards other than Ingus you control gain +1000 "
				+ "power.[[br]] Dull a total of 3 active Earth Forwards or Earth Backups: Choose 1 Forward. It "
				+ "gains +2000 power until the end of the turn.");
	}

	/** 5-106R Black Knight. */
	private static CardData blackKnight_5_106R() {
		return genericCard("Black Knight", "Lightning", "Monster", 2, 0, "Black Knight", "THEATRHYTHM", "II", false,
				"When a Forward of your opponent of cost 4 or less enters the field, put Black Knight into the "
				+ "Break Zone. If you do so, break it.");
	}

	/** 5-141H Refia. */
	private static CardData refia_5_141H() {
		return card("Refia", "Water", "Forward", 3, 7000, "Warrior of Light", "III", null, false,
				"The Job Warrior of Light Forwards and Water Forwards other than Refia you control gain +1000 "
				+ "power.[[br]] Dull a total of 5 active Water Forwards or Water Backups: Choose 1 Forward "
				+ "opponent controls. Return it to its owner's hand.");
	}

	/** 5-149S Amodar. */
	private static CardData amodar_5_149S() {
		return card("Amodar", "Fire", "Forward", 4, 8000, "Commando", "XIII", null, false,
				"If your opponent controls any Monster, Amodar gains +2000 power, First Strike and Brave.[[br]] "
				+ "When a Forward you control is put from the field into the Break Zone, double the power of "
				+ "Amodar until the end of the turn.");
	}
}
