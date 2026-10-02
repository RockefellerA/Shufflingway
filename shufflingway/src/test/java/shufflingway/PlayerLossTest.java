package shufflingway;

import static org.junit.jupiter.api.Assertions.*;
import static shufflingway.TestCards.*;

import java.util.List;
import java.util.function.Consumer;

import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;

import shufflingway.graphics.CardSlideAnimator;

/**
 * How a player loses the game, and what stops them: 7 points of damage, an empty deck, and a card
 * that says they can't lose (PR-143 Garnet).
 *
 * <p>Damage resolves from P1's seat at P2, so every choice the damage provokes — an EX Burst, a
 * shield's dull trigger — falls to the CPU and opens no dialog.
 */
class PlayerLossTest {

	/** A Forward built from its printed text, with its abilities, Job and Category parsed. */
	private static CardData makeTextForward(String name, String element, int cost, int power,
			String job, String category, String text) {
		return new CardData(null, name, element, cost, power, "Forward", false, 0, false, false,
				CardData.parseTraits(text, name), 0, List.of(), null, List.of(),
				CardData.parseActionAbilities(text), CardData.parseAutoAbilities(text),
				CardData.parseFieldAbilities(text, "Forward"),
				CardData.parseIfControlBoosts(text, "Forward"),
				CardData.parseFieldPowerGrants(text, "Forward"),
				List.of(), List.of(), List.of(), List.of(), List.of(),
				false, false, null, false, false, false, false, false,
				CardData.parseMaxAttacksPerTurn(text, name),
				job, category, null, text);
	}

	/** Gives P2 {@code damage} points already taken and {@code deck} cards left to flip. */
	private static MainWindow p2At(int damage, int deck) {
		MainWindow mw = new MainWindow();
		for (int i = 0; i < damage; i++) {
			mw.gameState.getP2DamageZone().add(makeForward("Taken " + i, "Water", 1, 1000));
			mw.p2DamageCount++;
		}
		for (int i = 0; i < deck; i++) mw.gameState.getP2MainDeck().add(makeForward("Deck " + i, "Water", 1, 1000));
		return mw;
	}

	/**
	 * Lets every damage reveal that is already scheduled run: each point flips its card at once
	 * and reveals it on a timer one slide-animation later.
	 */
	private static void settleReveals() throws Exception {
		Thread.sleep(CardSlideAnimator.TOTAL_FRAMES * CardSlideAnimator.FRAME_MS + 400L);
		SwingUtilities.invokeAndWait(() -> { });
	}

	/**
	 * {@code isP1}'s overflow "+" tooltip after a repaint of their Damage Zone. Read through
	 * {@code refreshDamageZoneSlots} rather than by waiting on a point's reveal timer, which calls
	 * the same refresh: Swing timers are not reliably serviced across the shared test JVM.
	 */
	private static String overflowTooltip(MainWindow mw, boolean isP1) throws Exception {
		String[] seen = new String[1];
		SwingUtilities.invokeAndWait(() -> {
			mw.refreshDamageZoneSlots(isP1);
			seen[0] = (isP1 ? mw.p1OverflowDamageButton : mw.p2OverflowDamageButton).getToolTipText();
		});
		return seen[0];
	}

	// =========================================================================================
	// PR-196 Balthier & Fran: "Balthier & Fran is also Card Name Balthier and Card Name Fran in all
	// situations. Dull 8 active Job Sky Pirate Category XII Forwards: Balthier & Fran deals your
	// opponent 7 points of damage."
	// =========================================================================================

	private static final String BALTHIER_FRAN_PR_196 = "Balthier & Fran is also Card Name Balthier and Card Name "
			+ "Fran in all situations. [[br]] Dull 8 active Job Sky Pirate Category XII Forwards: Balthier & Fran "
			+ "deals your opponent 7 points of damage.";

	private static CardData balthierFran() {
		return makeTextForward("Balthier & Fran", "Wind", 8, 12000, "Sky Pirate", "XII", BALTHIER_FRAN_PR_196);
	}

	/** Resolves Balthier & Fran's action ability for P1, at P2. */
	private static void sevenPointsAtP2(MainWindow mw) {
		CardData bf = balthierFran();
		Consumer<GameContext> fn = ActionResolver.parse(bf.actionAbilities().get(0).effectText(), bf);
		assertNotNull(fn, "the ability's effect parses");
		fn.accept(mw.buildGameContext(true));
	}

	@Test
	void balthierAndFransCostIsEightActiveSkyPirateCategoryTwelveForwards() {
		List<ActionAbility> abilities = balthierFran().actionAbilities();
		assertEquals(1, abilities.size());
		List<DullForwardCost> costs = abilities.get(0).dullForwardCosts();
		assertEquals(1, costs.size());
		assertEquals(8, costs.get(0).count());
		assertEquals("Sky Pirate", costs.get(0).job());
		assertEquals("XII", costs.get(0).category());
	}

	@Test
	void sevenPointsOnAPlayerWithDamageStopsAtTheSeventhAndEndsTheGame() throws Exception {
		MainWindow mw = p2At(3, 10);
		sevenPointsAtP2(mw);

		assertEquals(7, mw.gameState.getP2DamageZone().size(), "4 points finish them; the other 3 flip nothing");
		assertTrue(mw.gameState.isP1GameOver(), "the 7th point is decided as it lands");
		assertEquals(6, mw.gameState.getP2MainDeck().size());
		settleReveals();
		assertEquals(7, mw.gameState.getP2DamageZone().size(), "and nothing more lands once the reveals run");
	}

	@Test
	void sevenPointsOnAnUndamagedPlayerIsExactlyLethal() throws Exception {
		MainWindow mw = p2At(0, 10);
		sevenPointsAtP2(mw);

		assertTrue(mw.gameState.isP1GameOver());
		assertEquals(7, mw.gameState.getP2DamageZone().size());
	}

	private static final String RA_LA_26_121L = "If you receive damage while Ra-la is active, dull Ra-la. The "
			+ "damage becomes 0 instead.[[br]]When active Ra-la becomes dull, choose 1 Forward opponent controls. "
			+ "Break it.[[br]]《2》: Activate Ra-la.";

	/** P2 at {@code damage}, with 26-121L Ra-la active on their field. */
	private static MainWindow p2WithRaLaAt(int damage) {
		MainWindow mw = p2At(damage, 10);
		placeP2Forward(mw, makeTextForward("Ra-la", "Light", 4, 9000, "The Last Mercy", "XIV", RA_LA_26_121L));
		return mw;
	}

	@Test
	void raLaSpendsHerselfOnTheFirstPointAndTheOtherSixLand() throws Exception {
		MainWindow mw = p2WithRaLaAt(0);
		sevenPointsAtP2(mw);

		assertEquals(CardState.DULL, mw.p2ForwardStates.get(0), "the first point dulled her");
		assertEquals(6, mw.gameState.getP2DamageZone().size(), "only the first point became 0");
		assertFalse(mw.gameState.isP1GameOver(), "6 is not lethal");
	}

	@Test
	void raLaOnlyShieldsOncePerActivationSoOneDamageAlreadyTakenStillMeansSeven() throws Exception {
		MainWindow mw = p2WithRaLaAt(1);
		sevenPointsAtP2(mw);

		assertEquals(CardState.DULL, mw.p2ForwardStates.get(0));
		assertEquals(7, mw.gameState.getP2DamageZone().size(), "1 already, 1 shielded, 6 more");
		assertTrue(mw.gameState.isP1GameOver());
	}

	// =========================================================================================
	// PR-143 Garnet: "If you control 8 or more Category IX Forwards, you can't lose the game."
	//
	// Every loss is suppressed while it holds — 7 damage, an empty deck when damage or a draw
	// needs a card — and damage keeps landing past 7. The moment it stops holding, a player on 7 or
	// more damage loses; an empty deck only loses at the next draw that needs a card.
	// =========================================================================================

	private static final String GARNET_PR_143 = "If you control 8 or more Category IX Forwards, you can't lose the game.";

	private static CardData garnet() {
		return makeTextForward("Garnet", "Water", 3, 7000, "Princess/Summoner", "IX", GARNET_PR_143);
	}

	/**
	 * Seats Garnet and {@code others} more Category IX Forwards on {@code isP1}'s field — Garnet
	 * counts herself, so {@code others = 7} is the 8 she asks for. Returns Garnet.
	 */
	private static CardData seatGarnetWith(MainWindow mw, boolean isP1, int others) {
		CardData g = garnet();
		if (isP1) placeP1Forward(mw, g); else placeP2Forward(mw, g);
		for (int i = 0; i < others; i++) {
			CardData f = makeCategoryForward("Category IX " + i, "Water", "IX");
			if (isP1) placeP1Forward(mw, f); else placeP2Forward(mw, f);
		}
		return g;
	}

	/** P1 at {@code damage} with {@code deck} cards left; damage is dealt to P1 from P2's seat. */
	private static MainWindow p1At(int damage, int deck) {
		MainWindow mw = new MainWindow();
		for (int i = 0; i < damage; i++) mw.gameState.getP1DamageZone().add(makeForward("Taken " + i, "Fire", 1, 1000));
		for (int i = 0; i < deck; i++) mw.gameState.getP1MainDeck().add(makeForward("Deck " + i, "Fire", 1, 1000));
		return mw;
	}

	private static void damageP1(MainWindow mw, int points) {
		mw.buildGameContext(false).dealDamageToOpponent(points);
	}

	@Test
	void garnetProtectsWithEightCategoryNineForwardsCountingHerself() {
		MainWindow eight = new MainWindow();
		CardData g = seatGarnetWith(eight, true, 7);
		assertSame(g, eight.cannotLoseSource(true));
		assertFalse(eight.cannotLoseTheGame(false), "you, not your opponent");

		MainWindow seven = new MainWindow();
		seatGarnetWith(seven, true, 6);
		assertFalse(seven.cannotLoseTheGame(true), "7 is not 8 or more");
	}

	@Test
	void sevenDamageDoesNotLoseWhileGarnetHolds() throws Exception {
		MainWindow mw = p1At(6, 10);
		seatGarnetWith(mw, true, 7);
		damageP1(mw, 3);

		assertFalse(mw.gameState.isP1GameOver());
		assertEquals(9, mw.gameState.getP1DamageZone().size(), "damage keeps landing past 7");
		assertEquals(7, mw.gameState.getP1MainDeck().size(), "each point still flips a card");
		assertEquals("2 damage past 7 — click to see", overflowTooltip(mw, true));
	}

	@Test
	void withoutTheEighthForwardSevenDamageLosesAsUsual() {
		MainWindow mw = p1At(6, 10);
		seatGarnetWith(mw, true, 6);
		damageP1(mw, 1);
		assertTrue(mw.gameState.isP1GameOver());
	}

	@Test
	void anEmptyDeckUnderGarnetMakesADamagePointDoNothing() {
		MainWindow mw = p1At(7, 0);
		seatGarnetWith(mw, true, 7);
		damageP1(mw, 2);

		assertFalse(mw.gameState.isP1GameOver(), "no card to flip, and P1 can't lose");
		assertEquals(7, mw.gameState.getP1DamageZone().size(), "no damage is assigned for those points");
	}

	@Test
	void anEmptyDeckUnderGarnetDrawsNothingAndLosesNothing() {
		MainWindow mw = p1At(0, 0);
		seatGarnetWith(mw, true, 7);
		mw.drawCardsForPlayer(true, 1);
		assertFalse(mw.gameState.isP1GameOver());
	}

	@Test
	void whenGarnetLeavesAPlayerOnSevenOrMoreLosesAtOnce() {
		MainWindow mw = p1At(8, 5);
		CardData g = seatGarnetWith(mw, true, 7);
		assertFalse(mw.gameState.isP1GameOver());

		mw.breakP1Forward(mw.p1ForwardCards.indexOf(g));
		assertTrue(mw.gameState.isP1GameOver());
	}

	@Test
	void losingOneOfTheEightForwardsEndsTheProtectionToo() {
		MainWindow mw = p1At(7, 5);
		seatGarnetWith(mw, true, 7);
		mw.breakP1Forward(mw.p1ForwardCards.size() - 1);
		assertTrue(mw.gameState.isP1GameOver(), "7 Category IX Forwards left");
	}

	/** P1 casts a plain Summon and lets it resolve off the Stack. */
	private static void resolveASummon(MainWindow mw) {
		CardData summon = makeSummon("Some Summon", "Fire", 1, "Draw 1 card.");
		mw.gameState.getIdentity().put(summon, true);
		mw.pushSummonOnStack(summon, true, 0, 0, false, null, false);
		mw.passStackPriority();
	}

	// The loss is applied when the flag flips, whatever flipped it: here an effect has taken
	// Garnet's abilities, and the next Stack entry to finish is where the flag is re-read.
	@Test
	void whenGarnetLosesHerAbilitiesTheFlipIsCaughtWhenTheNextEntryResolves() {
		MainWindow mw = p1At(8, 5);
		CardData g = seatGarnetWith(mw, true, 7);
		mw.lostAbilitiesCards.add(g);
		assertFalse(mw.gameState.isP1GameOver(), "nothing has re-read the flag yet");

		resolveASummon(mw);
		assertTrue(mw.gameState.isP1GameOver());
	}

	@Test
	void anEntryResolvingWhileGarnetStillHoldsChangesNothing() {
		MainWindow mw = p1At(8, 5);
		seatGarnetWith(mw, true, 7);
		resolveASummon(mw);
		assertFalse(mw.gameState.isP1GameOver());
	}

	@Test
	void theFlipIsWhatLosesNotTheDamageCountAlone() {
		// Protection that never held has nothing to end: a player put on 7 by some other route
		// is not lost here, at a refresh, but where the damage was dealt.
		MainWindow mw = p1At(8, 5);
		mw.refreshCannotLoseTheGame();
		assertFalse(mw.gameState.isP1GameOver());
	}

	@Test
	void afterGarnetLeavesAnEmptyDeckOnlyLosesAtTheNextDraw() {
		MainWindow mw = p1At(3, 0);
		CardData g = seatGarnetWith(mw, true, 7);
		mw.breakP1Forward(mw.p1ForwardCards.indexOf(g));
		assertFalse(mw.gameState.isP1GameOver(), "an empty deck is not a loss until a card is needed");

		mw.drawCardsForPlayer(true, 1);
		assertTrue(mw.gameState.isP1GameOver());
	}

	@Test
	void balthierAndFransSevenPointsDoNotEndAGameGarnetIsHolding() throws Exception {
		MainWindow mw = p2At(3, 10);
		seatGarnetWith(mw, false, 7);
		sevenPointsAtP2(mw);

		assertFalse(mw.gameState.isP1GameOver());
		assertEquals(10, mw.gameState.getP2DamageZone().size(), "every point lands");
		assertEquals("3 damage past 7 — click to see", overflowTooltip(mw, false));
	}
}
