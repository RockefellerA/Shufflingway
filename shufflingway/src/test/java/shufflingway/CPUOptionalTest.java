package shufflingway;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import shufflingway.CPUOptional.Cost;

/**
 * {@link CPUOptional}: what an offer costs, read off its text, and the CPU's answer to it.
 * The cost readings are the census's (tools/probes/OptionalCensus), on the printings that put each
 * kind in the corpus.
 */
class CPUOptionalTest {

	/** The switch as the run started it — set for a whole run by the toggle-on pass. */
	private static final boolean AS_STARTED = Boolean.getBoolean(CPUOptional.ACCEPTS_EVERY_OFFER_PROPERTY);

	@AfterEach
	void restoreTheDebugSwitchAndTheRoll() {
		CPUOptional.acceptsEveryOffer = AS_STARTED;
		CPUOptional.roll = Math::random;
	}

	private static CardData card(String name) {
		return TestCards.makeForward(name, "Fire", 3, 7000);
	}

	@Test
	void thePriceIsReadAheadOfWhenYouDoSo() {
		assertEquals(Cost.CP, CPUOptional.costOf(
				"pay 《C》. When you do so, your opponent randomly discards 1 card.", card("Shantotto")));
		assertEquals(Cost.SELF_DAMAGE, CPUOptional.costOf(
				"receive 1 point of damage. When you do so, choose 1 Forward opponent controls. Break it.", card("Cecil")));
		assertEquals(Cost.DISCARD, CPUOptional.costOf(
				"discard 1 card. If you do so, draw 2 cards.", card("Tifa")));
		assertEquals(Cost.REMOVE_FROM_BZ, CPUOptional.costOf(
				"remove 3 cards in your Break Zone from the game. When you do so, draw 1 card.", card("Gilgamesh")));
		assertEquals(Cost.DULL, CPUOptional.costOf(
				"dull 1 active Backup you control. When you do so, draw 1 card.", card("Ramza")));
		assertEquals(Cost.RETURN_TO_HAND, CPUOptional.costOf(
				"return 1 Backup you control to its owner's hand. If you do so, draw 1 card.", card("Yuna")));
	}

	@Test
	void theSourcesOwnNameMarksASelfSacrificeOrSelfRemoval() {
		String paine = "put Paine into the Break Zone. If you do so, play 1 Card Name Paine from your hand onto the field.";
		assertEquals(Cost.SACRIFICE_SELF, CPUOptional.costOf(paine, card("Paine")));
		assertEquals(Cost.SACRIFICE_OTHER, CPUOptional.costOf(paine, card("Rikku")), "Paine is not this card");

		String aerith = "remove Aerith from the game. When you do so, place 3 Reraise Counters on Aerith.";
		assertEquals(Cost.REMOVE_SELF, CPUOptional.costOf(aerith, card("Aerith")));
		assertEquals(Cost.REMOVE_OTHER, CPUOptional.costOf(aerith, null), "no card to compare against");
	}

	@Test
	void aLeadingConditionOrChoiceIsReadPast() {
		// 15-061H Lehko Habhoka: the cast-count condition, then the price.
		assertEquals(Cost.SACRIFICE_SELF, CPUOptional.costOf("if you have cast 3 or 4 cards this turn, you may put "
				+ "Lehko Habhoka into the Break Zone. If you do so, search for 1 Character of cost 4 or less and play "
				+ "it onto the field.", card("Lehko Habhoka")));
		// 7-020C Lulu: the choice is the payoff's target, and the price is the sentence after it.
		assertEquals(Cost.SACRIFICE_OTHER, CPUOptional.costOf("choose 1 Forward. You may put 1 Backup other than "
				+ "Lulu you control into the Break Zone. If you do so, deal it 7000 damage.", card("Lulu")));
	}

	@Test
	void aPriceThatIsItselfAGainIsToldApart() {
		assertEquals(Cost.PLAY_CARD, CPUOptional.costOf("play 1 Monster of cost 2 or less from your hand onto the "
				+ "field. When you do so, choose 1 Forward opponent controls. Return it to its owner's hand.", card("Gogo")));
		assertEquals(Cost.SEARCH_TO_BZ, CPUOptional.costOf("search for 1 Summon and put it into the Break Zone. When "
				+ "you do so, choose up to 2 Characters. Dull them and Freeze them.", card("Terra")));
	}

	@Test
	void anEffectWithNoPriceCostsNothing() {
		assertEquals(Cost.NONE, CPUOptional.costOf("search for 1 Fire Character and play it onto the field.", card("Luso")));
		assertEquals(Cost.NONE, CPUOptional.costOf(
				"play 1 Character of cost 3 or less from your hand onto the field.", card("Cloud")));
		// With no "do so", only a pay, a discard or a self-damage leads a price.
		assertEquals(Cost.DISCARD, CPUOptional.costOf("discard 1 card.", card("Tifa")));
	}

	@Test
	void aPromptIsReadByWhatItOffers() {
		CardData kadaj = card("Kadaj");
		assertEquals(Cost.NONE, CPUOptional.costOfPrompt("Search your deck? Declining means you did not search.", kadaj));
		assertEquals(Cost.NONE, CPUOptional.costOfPrompt("Play 1 face down Ardyn from your LB deck onto the field dull?", kadaj));
		assertEquals(Cost.GIVE_CONTROL, CPUOptional.costOfPrompt("Give control of Leslie to your opponent?", kadaj));
		assertEquals(Cost.REMOVE_SELF, CPUOptional.costOfPrompt("Remove Kadaj from the game?", kadaj));
		assertEquals(Cost.REMOVE_OTHER, CPUOptional.costOfPrompt("Remove the chosen card from the game?", kadaj));
		assertEquals(Cost.DECK, CPUOptional.costOfPrompt("Put the chosen card on top of your deck?", kadaj));
		assertEquals(Cost.ON_CHOSEN, CPUOptional.costOfPrompt("Deal it 7000 damage?", kadaj));
		assertEquals(Cost.ON_CHOSEN, CPUOptional.costOfPrompt("Return it to its owner's hand?", kadaj));
	}

	@Test
	void theCallSitesOwnAnswerStandsWhereNoRuleSettlesIt() {
		CPUOptional.acceptsEveryOffer = false;
		List<String> log = new ArrayList<>();
		assertFalse(CPUOptional.ask(Cost.GIVE_CONTROL, "Give control of Leslie to your opponent?", () -> false,
				log::add).getAsBoolean());
		assertTrue(CPUOptional.ask(Cost.CP, "Pay 《1》?", () -> true, log::add).getAsBoolean());
		assertEquals(List.of(), log, "an answer that is the call site's own says nothing extra");
	}

	@Test
	void anOfferThatCostsNothingIsTaken() {
		CPUOptional.acceptsEveryOffer = false;
		for (Cost free : List.of(Cost.NONE, Cost.PLAY_CARD, Cost.SEARCH_TO_BZ)) {
			List<String> log = new ArrayList<>();
			assertTrue(CPUOptional.ask(free, "Search your deck?", () -> fail("a rule settles it"), log::add)
					.getAsBoolean(), free.name());
			assertEquals(List.of("[CPU] accepts (it costs nothing): Search your deck?"), log);
		}
	}

	@Test
	void aDrawTheDeckCannotCoverIsDeclined() {
		CPUOptional.acceptsEveryOffer = false;
		CPUOptional.Offer drawOne = new CPUOptional.Offer(Cost.NONE, "you may draw 1 card", 1, 0, 0, () -> true);
		CPUOptional.Decision declined = CPUOptional.decide(drawOne, deck(0));
		assertFalse(declined.accept(), "it would lose the game");
		assertEquals("drawing 1 from a deck of 0 would lose the game", declined.reason());
		assertTrue(CPUOptional.decide(drawOne, deck(1)).accept());
		assertTrue(CPUOptional.decide(drawOne, null).accept(), "no board to read: the rule stands aside");

		CPUOptional.Offer discardForTwo = new CPUOptional.Offer(Cost.DISCARD, "discard 1 card. If you do so, draw 2 cards.",
				2, 0, 0, () -> true);
		assertFalse(CPUOptional.decide(discardForTwo, deck(1)).accept(), "whatever the price");
	}

	/** 9-106R Ghis's blink, which costs the top 3 cards of the deck. */
	private static final String GHIS = "remove Ghis from the game. If you do so, return Ghis onto the field dull, then "
			+ "remove the top 3 cards of your deck from the game.";

	@Test
	void cardsOffTheTopOfTheDeckAreTakenOnlyWhenTheDeckCanSpareThem() {
		CPUOptional.acceptsEveryOffer = false;
		CPUOptional.Offer ghis = new CPUOptional.Offer(Cost.REMOVE_SELF, "Ghis — You may: " + GHIS, 0,
				CPUOptional.millsIn(GHIS), 0, () -> true);
		CPUOptional.Decision atFour = CPUOptional.decide(ghis, deck(4));
		assertFalse(atFour.accept(), "1 card left for a draw phase that takes 2");
		assertEquals("the deck cannot spare 3 of its 4 cards and still cover the next draw phase", atFour.reason());
		assertTrue(CPUOptional.decide(ghis, deck(5)).accept(), "2 left: the next draw phase is covered");
		assertTrue(CPUOptional.decide(ghis, null).accept(), "no board to read: the rule stands aside");
		CPUOptional.acceptsEveryOffer = true;
		assertTrue(CPUOptional.decide(ghis, deck(4)).accept(), "the debug switch takes it anyway");
	}

	@Test
	void theMilledCardsAreCountedOffTheText() {
		assertEquals(3, CPUOptional.millsIn(GHIS));
		assertEquals(2, CPUOptional.millsIn("put the top 2 cards of your deck into the Break Zone. When you do so, "
				+ "choose 1 Forward. It loses 5000 power until the end of the turn."));
		assertEquals(0, CPUOptional.millsIn("draw 2 cards"), "a draw is counted on its own");
		assertEquals(0, CPUOptional.millsIn("look at the top 3 cards of your deck"), "nothing leaves the deck");
	}

	/** A board with {@code deckSize} cards left, no damage and nothing keeping the seat in the game. */
	private static CPUOptional.Board deck(int deckSize) {
		return new CPUOptional.Board(deckSize, 0, false, null);
	}

	/** A board with {@code damage} points received and a deck that covers any draw. */
	private static CPUOptional.Board damage(int damage) {
		return new CPUOptional.Board(40, damage, false, null);
	}

	/** 13-060R Cecil's price: receive 1 point of damage, for a fight. */
	private static final String CECIL = "receive 1 point of damage. When you do so, choose 1 Forward opponent controls. "
			+ "Cecil and the chosen Forward deal damage equal to their respective power to the other.";

	@Test
	void selfDamageThatWouldLoseTheGameIsDeclined() {
		CPUOptional.acceptsEveryOffer = false;
		CPUOptional.Offer cecil = new CPUOptional.Offer(Cost.SELF_DAMAGE, "You may: " + CECIL, 0, 0,
				CPUOptional.selfDamageIn(CECIL), () -> true);

		CPUOptional.Decision atSix = CPUOptional.decide(cecil, damage(6));
		assertFalse(atSix.accept(), "the seventh point loses the game");
		assertEquals("taking 1 damage at 6 would lose the game", atSix.reason());

		CPUOptional.Decision atFive = CPUOptional.decide(cecil, damage(5));
		assertTrue(atFive.accept(), "a point short of losing: the call site's answer stands");
		assertNull(atFive.reason());
		assertFalse(CPUOptional.decide(new CPUOptional.Offer(Cost.SELF_DAMAGE, CECIL, 0, 0, 1, () -> false), damage(5))
				.accept(), "and that answer can be no");

		CPUOptional.Offer twoPoints = new CPUOptional.Offer(Cost.SELF_DAMAGE, "receive 2 points of damage", 0, 0, 2, () -> true);
		assertFalse(CPUOptional.decide(twoPoints, damage(5)).accept());
		assertTrue(CPUOptional.decide(twoPoints, damage(4)).accept());
	}

	@Test
	void aSeatThatCannotLoseTheGameIsNotTurnedAway() {
		CPUOptional.acceptsEveryOffer = false;
		CPUOptional.Offer cecil = new CPUOptional.Offer(Cost.SELF_DAMAGE, CECIL, 0, 0, 1, () -> true);
		assertTrue(CPUOptional.decide(cecil, new CPUOptional.Board(40, 6, true, null)).accept());
		CPUOptional.Offer drawOne = new CPUOptional.Offer(Cost.NONE, "you may draw 1 card", 1, 0, 0, () -> true);
		assertTrue(CPUOptional.decide(drawOne, new CPUOptional.Board(0, 0, true, null)).accept());
	}

	@Test
	void theDebugSwitchTakesEvenALosingOffer() {
		CPUOptional.acceptsEveryOffer = true;
		CPUOptional.Offer cecil = new CPUOptional.Offer(Cost.SELF_DAMAGE, CECIL, 0, 0, 1, () -> false);
		assertTrue(CPUOptional.decide(cecil, damage(6)).accept());
	}

	@Test
	void theSelfDamageIsCountedOffTheText() {
		assertEquals(1, CPUOptional.selfDamageIn(CECIL));
		assertEquals(2, CPUOptional.selfDamageIn("receive 2 points of damage. If you do so, draw 1 card."));
		assertEquals(1, CPUOptional.selfDamageIn("choose 1 Forward. Deal it 5000 damage. Buffasaur deals you 1 point of damage."));
		assertEquals(0, CPUOptional.selfDamageIn("choose 1 Forward. Deal it 7000 damage."), "damage to a Forward");
		assertEquals(0, CPUOptional.selfDamageIn("Cecil deals your opponent 1 point of damage."), "not this seat's");
		assertEquals(0, CPUOptional.selfDamageIn(null));
	}

	/** A board on which the offer's payoff has nothing to act on, for {@code why}. */
	private static CPUOptional.Board nothingToActOn(String why) {
		return new CPUOptional.Board(40, 0, false, why);
	}

	@Test
	void aPriceIsNotPaidForAPayoffWithNothingToActOn() {
		CPUOptional.acceptsEveryOffer = false;
		CPUOptional.Offer discard = new CPUOptional.Offer(Cost.DISCARD, "Leslie — You may: discard 1 card. When you do "
				+ "so, choose 1 Forward opponent controls. Return it to its owner's hand.", 0, 0, 0, () -> true);
		CPUOptional.Decision d = CPUOptional.decide(discard, nothingToActOn("its payoff has no opposing card to choose"));
		assertFalse(d.accept());
		assertEquals("its payoff has no opposing card to choose", d.reason());
		assertTrue(CPUOptional.decide(discard, nothingToActOn(null)).accept(), "something to act on: the site's answer");
		assertTrue(CPUOptional.decide(discard, null).accept(), "no board to read: the rule stands aside");
	}

	@Test
	void aFreeOfferIsTakenWhateverItsPayoffFinds() {
		CPUOptional.acceptsEveryOffer = false;
		CPUOptional.Offer play = new CPUOptional.Offer(Cost.PLAY_CARD, "play 1 Monster of cost 2 or less from your hand "
				+ "onto the field. When you do so, choose 1 Forward opponent controls. Return it to its owner's hand.",
				0, 0, 0, () -> false);
		assertTrue(CPUOptional.decide(play, nothingToActOn("its payoff has no opposing card to choose")).accept(),
				"the play is worth having on its own");
	}

	@Test
	void thePayoffIsWhatTheDoSoBuys() {
		assertEquals("choose 1 Forward opponent controls. Deal it 7000 damage.", CPUOptional.payoffOf(
				"pay 《Fire》《Fire》《1》. When you do so, choose 1 Forward opponent controls. Deal it 7000 damage."));
		assertEquals("choose 1 Forward. It gains +3000 power.", CPUOptional.payoffOf(
				"remove Angeal from the game. When you do so, choose 1 Forward. It gains +3000 power."));
		// 7-020C Lulu: the choice the payoff acts on is printed ahead of the price.
		assertEquals("choose 1 Forward. deal it 7000 damage.", CPUOptional.payoffOf("choose 1 Forward. You may put 1 "
				+ "Backup other than Lulu you control into the Break Zone. If you do so, deal it 7000 damage."));
		assertNull(CPUOptional.payoffOf("search for 1 Fire Character and play it onto the field."), "no do so");
		assertNull(CPUOptional.payoffOf(null));
	}

	@Test
	void theBreakChanceRisesWithTheSquareOfTheTargetsCost() {
		assertEquals(0.0, CPUOptional.breakChance(0));
		assertEquals(0.04, CPUOptional.breakChance(1), 1e-9);
		assertEquals(0.16, CPUOptional.breakChance(2), 1e-9);
		assertEquals(0.36, CPUOptional.breakChance(3), 1e-9);
		assertEquals(0.64, CPUOptional.breakChance(4), 1e-9);
		assertEquals(1.0, CPUOptional.breakChance(5));
		assertEquals(1.0, CPUOptional.breakChance(9), "every time from 5 up");
	}

	/** 12-073C Red Mage's offer. */
	private static final String RED_MAGE = "put Red Mage into the Break Zone. When you do so, choose 1 Forward of cost 3 "
			+ "or less. Break it.";

	/** A board on which the payoff would break an opposing card of printed {@code cost}. */
	private static CPUOptional.Board breaking(int cost) {
		return new CPUOptional.Board(40, 0, false, null, cost);
	}

	@Test
	void aCardThatPutsItselfIntoTheBreakZoneToBreakOneIsTakenOnTheRoll() {
		CPUOptional.acceptsEveryOffer = false;
		CPUOptional.Offer redMage = new CPUOptional.Offer(Cost.SACRIFICE_SELF, "Red Mage — You may: " + RED_MAGE, 0, 0, 0,
				() -> fail("the break chance settles it"));

		CPUOptional.roll = () -> 0.25;
		CPUOptional.Decision under = CPUOptional.decide(redMage, breaking(3));
		assertTrue(under.accept(), "25 is under cost 3's 36%");
		assertEquals("breaking a cost-3 card is worth the sacrifice 36% of the time; rolled 25", under.reason());
		CPUOptional.roll = () -> 0.375;
		assertFalse(CPUOptional.decide(redMage, breaking(3)).accept(), "37 is not");

		CPUOptional.roll = () -> 0.99;
		CPUOptional.Decision dear = CPUOptional.decide(redMage, breaking(5));
		assertTrue(dear.accept());
		assertEquals("breaking a cost-5 card is worth the sacrifice", dear.reason(), "no roll to report");

		CPUOptional.roll = () -> 0.0;
		CPUOptional.Decision free = CPUOptional.decide(redMage, breaking(0));
		assertFalse(free.accept(), "a cost-0 card is never worth a card");
		assertEquals("breaking a cost-0 card is not worth the sacrifice", free.reason());
	}

	@Test
	void theBreakChanceIsOnlyForACardThatPutsItselfIntoTheBreakZone() {
		CPUOptional.acceptsEveryOffer = false;
		CPUOptional.roll = () -> 0.99;
		CPUOptional.Offer pay = new CPUOptional.Offer(Cost.CP, "pay 《1》. When you do so, choose 1 Forward. Break it.",
				0, 0, 0, () -> true);
		CPUOptional.Decision paid = CPUOptional.decide(pay, breaking(1));
		assertTrue(paid.accept());
		assertNull(paid.reason(), "the call site's own answer");

		CPUOptional.Offer redMage = new CPUOptional.Offer(Cost.SACRIFICE_SELF, RED_MAGE, 0, 0, 0, () -> true);
		assertNull(CPUOptional.decide(redMage, deck(40)).reason(), "no break to judge: the call site's own answer");
		assertFalse(CPUOptional.decide(redMage, nothingToActOn("its payoff has no opposing card to choose")).accept(),
				"nothing to break is declined before any roll");
		CPUOptional.acceptsEveryOffer = true;
		assertTrue(CPUOptional.decide(redMage, breaking(1)).accept(), "the debug switch takes it anyway");
	}

	/** A board for a discard offer that trades {@code trade}. */
	private static CPUOptional.Board discarding(CPUOptional.CardTrade trade) {
		return new CPUOptional.Board(40, 0, false, null, -1, trade);
	}

	/** 14-086R Heidegger's offer: two cards for a break. */
	private static final CPUOptional.Offer HEIDEGGER = new CPUOptional.Offer(Cost.DISCARD,
			"Heidegger — You may: discard 2 cards. When you do so, choose 1 Forward. Break it.", 0, 0, 0,
			() -> fail("the discard rules settle it"));

	@Test
	void twoCardsArePaidOnlyForWhatCostsAsMuchAsTheyDo() {
		CPUOptional.acceptsEveryOffer = false;
		CPUOptional.Decision worth = CPUOptional.decide(HEIDEGGER, discarding(new CPUOptional.CardTrade(2, 4, 5, false, false)));
		assertTrue(worth.accept());
		assertEquals("a cost-5 card is worth 2 cards costing 4", worth.reason());
		assertTrue(CPUOptional.decide(HEIDEGGER, discarding(new CPUOptional.CardTrade(2, 4, 4, false, false))).accept(),
				"an even trade is taken");
		CPUOptional.Decision short_ = CPUOptional.decide(HEIDEGGER, discarding(new CPUOptional.CardTrade(2, 5, 4, false, false)));
		assertFalse(short_.accept());
		assertEquals("a cost-4 card is not worth 2 cards costing 5", short_.reason());
		CPUOptional.Decision unpriced = CPUOptional.decide(HEIDEGGER, discarding(new CPUOptional.CardTrade(2, 4, -1, false, false)));
		assertFalse(unpriced.accept(), "decline when unsure");
		assertEquals("nothing prices what 2 cards would buy", unpriced.reason());
	}

	@Test
	void oneCardForAPayoffThatLandsIsTheCallSitesAnswer() {
		CPUOptional.acceptsEveryOffer = false;
		CPUOptional.Offer redXiii = new CPUOptional.Offer(Cost.DISCARD, "discard 1 card. When you do so, choose 1 "
				+ "Forward. Deal it 7000 damage.", 0, 0, 0, () -> true);
		CPUOptional.Decision d = CPUOptional.decide(redXiii, discarding(new CPUOptional.CardTrade(1, 1, -1, false, false)));
		assertTrue(d.accept());
		assertNull(d.reason());
		assertNull(CPUOptional.decide(redXiii, deck(40)).reason(), "no trade read: the call site's answer");
	}

	@Test
	void aCardIsNotSpentOnDamageThatBreaksNothingOrAPayoffNoDiscardLands() {
		CPUOptional.acceptsEveryOffer = false;
		CPUOptional.Offer offer = new CPUOptional.Offer(Cost.DISCARD, "discard 1 card", 0, 0, 0, () -> true);
		CPUOptional.Decision weak = CPUOptional.decide(offer, discarding(new CPUOptional.CardTrade(1, 1, -1, true, false)));
		assertFalse(weak.accept());
		assertEquals("its damage would break no opposing Forward", weak.reason());
		CPUOptional.Decision keyed = CPUOptional.decide(offer, discarding(new CPUOptional.CardTrade(1, 1, -1, false, true)));
		assertFalse(keyed.accept());
		assertEquals("no card in its hand would make its payoff land", keyed.reason());
		CPUOptional.acceptsEveryOffer = true;
		assertTrue(CPUOptional.decide(offer, discarding(new CPUOptional.CardTrade(1, 1, -1, true, true))).accept(),
				"the debug switch takes it anyway");
	}

	@Test
	void aFieldCardIsPricedAsADiscardIs() {
		CPUOptional.acceptsEveryOffer = false;
		for (Cost cost : List.of(Cost.SACRIFICE_OTHER, Cost.REMOVE_OTHER, Cost.RETURN_TO_HAND)) {
			CPUOptional.Offer offer = new CPUOptional.Offer(cost, "a card of its own", 0, 0, 0, () -> true);
			assertFalse(CPUOptional.decide(offer, discarding(new CPUOptional.CardTrade(1, -1, -1, true, false))).accept(),
					cost.name());
			assertNull(CPUOptional.decide(offer, discarding(new CPUOptional.CardTrade(1, -1, -1, false, false))).reason(),
					cost.name() + ": one card for a payoff that lands is the call site's answer");
			assertFalse(CPUOptional.decide(offer, discarding(new CPUOptional.CardTrade(2, -1, 6, false, false))).accept(),
					cost.name() + ": two cards with no price told for them");
		}
	}

	@Test
	void aSweepOfBothSidesIsPaidOnlyWhenItTakesMoreOfTheirs() {
		CPUOptional.acceptsEveryOffer = false;
		CPUOptional.Offer susano = new CPUOptional.Offer(Cost.SACRIFICE_OTHER, "Susano — You may: put 1 Backup you control "
				+ "into the Break Zone. When you do so, deal 9000 damage to all the Forwards other than Susano.", 0, 0, 0,
				() -> fail("the sweep rule settles it"));
		CPUOptional.Decision more = CPUOptional.decide(susano, discarding(new CPUOptional.CardTrade(1, -1, -1, false, false, 6, 4)));
		assertTrue(more.accept());
		assertEquals("its sweep breaks cost 6 of the opponent's Forwards and 4 of its own", more.reason());
		assertFalse(CPUOptional.decide(susano, discarding(new CPUOptional.CardTrade(1, -1, -1, false, false, 4, 4))).accept(),
				"an even sweep is not worth the Backup");
		assertFalse(CPUOptional.decide(susano, discarding(new CPUOptional.CardTrade(1, -1, -1, false, false, 0, 0))).accept());
	}

	@Test
	void aCardFromItsOwnBreakZoneIsPaidEvenWhereTheSiteWouldSayNo() {
		CPUOptional.acceptsEveryOffer = false;
		String prompt = "Remove the chosen card in the Break Zone from the game?";
		assertEquals(Cost.REMOVE_FROM_BZ, CPUOptional.costOfPrompt(prompt, card("Lann")));
		List<String> log = new ArrayList<>();
		assertTrue(CPUOptional.ask(CPUOptional.costOfPrompt(prompt, card("Lann")), prompt, () -> false, log::add).getAsBoolean());
		assertEquals(List.of("[CPU] accepts (a card from its Break Zone costs next to nothing): " + prompt), log);
	}

	@Test
	void cardsOffTheTopOfTheDeckAreAMillNotACardOfItsOwn() {
		assertEquals(Cost.MILL, CPUOptional.costOf("put the top 2 cards of your deck into the Break Zone. When you do so, "
				+ "choose 1 Category VIII Forward other than Card Name Seifer in your Break Zone. Add it to your hand.",
				card("Seifer")));
		assertEquals(Cost.MILL, CPUOptional.costOf("remove the top 10 cards of your deck from the game. If you do so, "
				+ "return Cloud to the field dull.", card("Cloud")));
		assertEquals(Cost.REMOVE_SELF, CPUOptional.costOf(GHIS, card("Ghis")), "Ghis's price is himself");
	}

	@Test
	void aPriceNothingReadsIsDeclined() {
		CPUOptional.acceptsEveryOffer = false;
		CPUOptional.Decision d = CPUOptional.decide(new CPUOptional.Offer(Cost.OTHER, "an unread price", 0, 0, 0,
				() -> fail("decline when unsure")), deck(40));
		assertFalse(d.accept());
		assertEquals("nothing tells what it costs", d.reason());
		assertFalse(CPUOptional.decide(new CPUOptional.Offer(Cost.OTHER, "an unread price", 0, 0, 0, () -> true), null)
				.accept(), "with no board as well");
		CPUOptional.acceptsEveryOffer = true;
		assertTrue(CPUOptional.decide(new CPUOptional.Offer(Cost.OTHER, "x", 0, 0, 0, () -> false), null).accept());
	}

	@Test
	void thePriceIsWhatComesAheadOfTheDoSo() {
		assertEquals("discard 2 cards", CPUOptional.priceOf("discard 2 cards. When you do so, choose 1 Forward. Break it."));
		assertEquals("put 1 Backup other than Lulu you control into the Break Zone", CPUOptional.priceOf("choose 1 Forward. "
				+ "You may put 1 Backup other than Lulu you control into the Break Zone. If you do so, deal it 7000 damage."));
		assertNull(CPUOptional.priceOf("search for 1 Fire Character and play it onto the field."));
	}

	@Test
	void theDrawsAreCountedOffTheText() {
		assertEquals(1, CPUOptional.drawsIn("draw 1 card"));
		assertEquals(2, CPUOptional.drawsIn("discard 1 card. If you do so, draw 2 cards."));
		assertEquals(3, CPUOptional.drawsIn("draw 1 card. Then, draw 2 cards."));
		assertEquals(0, CPUOptional.drawsIn("draw up to 2 cards"), "a short deck protects \"up to\"");
		assertEquals(0, CPUOptional.drawsIn("your opponent draws 1 card"), "not this seat's draw");
	}

	@Test
	void aSearchThatRemovesWhatItFindsIsAPrice() {
		CardData madeen = card("Madeen");
		assertEquals(Cost.SEARCH_TO_RFG, CPUOptional.costOfPrompt("Search for 1 Light Forward and remove it from the game?", madeen));
		assertEquals(Cost.SEARCH_TO_RFG, CPUOptional.costOfPrompt("Search for 1 Dark Forward and remove it from the game?", madeen));
		assertEquals(Cost.SEARCH_TO_RFG, CPUOptional.costOf("search for up to 2 Card Name The Emperor and remove them "
				+ "from the game. Then, draw 1 card and place 1 Palamecia Counter on The Emperor for each card removed.",
				card("The Emperor")));
		// Not free, so no rule takes it: 29-116H Madeen's and 29-117H Ark's search stays declined.
		CPUOptional.acceptsEveryOffer = false;
		assertFalse(CPUOptional.ask(Cost.SEARCH_TO_RFG, "Search for 1 Light Forward and remove it from the game?",
				() -> false, s -> { }).getAsBoolean());
	}

}
