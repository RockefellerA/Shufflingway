package shufflingway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import shufflingway.net.ActionType;
import shufflingway.net.ChoiceKind;
import shufflingway.net.GameAction;
import shufflingway.net.MatchSetup;

/**
 * The Phase 1 multiplayer invariant: two clients starting from the same {@link MatchSetup}
 * deal the same game, each seating itself as P1.
 *
 * <p>These build both clients' {@link GameState} in one JVM and compare them, which is the
 * closest a unit test gets to the two-window check — {@code MainWindow} is not headless.
 */
class MultiplayerSetupTest {

    private static final List<String> HOST_SERIALS   = List.of("h1", "h2", "h3");
    private static final List<String> JOINER_SERIALS = List.of("j1", "j2", "j3");

    private static CardData card(String name, int cost) {
        return new CardData(null, name, "Fire", cost, 5000, "Forward", false, 0, false, false,
                Set.of(), 0, List.of(), null, List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(),
                false, false, null, false, false, false, false, false, 1,
                null, null, null, "");
    }

    /** A 50-card deck whose cards are all distinguishable, so any reordering shows up. */
    private static List<CardData> deck(String prefix) {
        List<CardData> cards = new ArrayList<>();
        for (int i = 0; i < 50; i++) cards.add(card(prefix + "-" + i, i % 9));
        return cards;
    }

    private static List<String> names(java.util.Collection<CardData> cards) {
        return cards.stream().map(CardData::name).toList();
    }

    private static MatchSetup hostSetup(long seed, boolean hostGoesFirst) {
        return new MatchSetup(1, JOINER_SERIALS, "Joiner Deck", "Joiner", seed, true, hostGoesFirst);
    }

    private static MatchSetup joinerSetup(long seed, boolean hostGoesFirst) {
        return new MatchSetup(2, HOST_SERIALS, "Host Deck", "Host", seed, false, hostGoesFirst);
    }

    /**
     * Builds the game as one client sees it: its own deck as P1, the opponent's as P2.
     * This mirrors what {@code MainWindow.loadMultiplayerDecks} does.
     */
    private static GameState dealFor(MatchSetup setup) {
        GameState state = new GameState();
        List<CardData> own      = setup.localIsHost() ? deck("host") : deck("joiner");
        List<CardData> opponent = setup.localIsHost() ? deck("joiner") : deck("host");
        state.initializeDeck(own, List.of(), setup.localDeckRandom());
        state.initializeP2MainDeck(opponent, setup.remoteDeckRandom());
        return state;
    }

    @Test
    void bothClientsDealTheSameDeckOrderFromOneSeed() {
        GameState host   = dealFor(hostSetup(0xC0FFEEL, true));
        GameState joiner = dealFor(joinerSetup(0xC0FFEEL, true));

        // The host's deck is P1 on the host's client and P2 on the joiner's — same order in both.
        assertEquals(names(host.getP1MainDeck()), names(joiner.getP2MainDeck()),
                "the host's deck must be dealt identically on both clients");
        assertEquals(names(host.getP2MainDeck()), names(joiner.getP1MainDeck()),
                "the joiner's deck must be dealt identically on both clients");
    }

    @Test
    void openingDealChecksumsAgreeAcrossClients() {
        GameState host   = dealFor(hostSetup(42L, false));
        GameState joiner = dealFor(joinerSetup(42L, false));

        assertEquals(MatchChecksum.ofOpeningDeal(host, true, false),
                     MatchChecksum.ofOpeningDeal(joiner, false, false),
                     "mirrored clients must produce the same opening-deal digest");
    }

    @Test
    void checksumCatchesAClientThatDealtADifferentGame() {
        GameState host   = dealFor(hostSetup(42L, false));
        GameState joiner = dealFor(joinerSetup(43L, false)); // wrong seed — a desync

        assertNotEquals(MatchChecksum.ofOpeningDeal(host, true, false),
                        MatchChecksum.ofOpeningDeal(joiner, false, false),
                        "a differing deal must not digest the same");
    }

    @Test
    void checksumCoversTheAgreedFirstPlayer() {
        GameState host = dealFor(hostSetup(42L, false));
        assertNotEquals(MatchChecksum.ofOpeningDeal(host, true, true),
                        MatchChecksum.ofOpeningDeal(host, true, false),
                        "disagreeing about who moves first is itself a desync");
    }

    @Test
    void theTwoDecksDoNotShareAShuffleStream() {
        // Both decks drawing from one stream would correlate them; they get separate streams.
        List<CardData> a = deck("x");
        List<CardData> b = deck("x");
        MatchSetup setup = hostSetup(7L, true);
        java.util.Collections.shuffle(a, setup.hostDeckRandom());
        java.util.Collections.shuffle(b, setup.joinerDeckRandom());
        assertNotEquals(names(a), names(b));
    }

    @Test
    void firstPlayerFollowsTheHostsFlip() {
        assertTrue(hostSetup(1L, true).localGoesFirst());
        assertFalse(joinerSetup(1L, true).localGoesFirst());
        assertFalse(hostSetup(1L, false).localGoesFirst());
        assertTrue(joinerSetup(1L, false).localGoesFirst());
    }

    @Test
    void seededDealIsReproducible() {
        assertEquals(names(dealFor(hostSetup(99L, true)).getP1MainDeck()),
                     names(dealFor(hostSetup(99L, true)).getP1MainDeck()),
                     "the same seed must deal the same game every run");
    }

    // ── Opening hand replication ─────────────────────────────────────────
    //
    // The sending client settles its own hand through keepHand/mulligan; the receiving client
    // applies the permutation to its P2 copy. These assert the two land in the same place.

    /** Deals both clients and draws each side's opening five, as game start does. */
    private static GameState[] dealtPair(long seed) {
        GameState host   = dealFor(hostSetup(seed, true));
        GameState joiner = dealFor(joinerSetup(seed, true));
        host.drawP2OpeningHand();
        joiner.drawP2OpeningHand();
        return new GameState[] { host, joiner };
    }

    @Test
    void keepingAReorderedHandLeavesBothClientsAgreeingOnIt() {
        GameState[] pair = dealtPair(5L);
        GameState host = pair[0], joiner = pair[1];

        // The host arranges their opening five and keeps it.
        List<CardData> drawn = host.drawOpeningHand();
        List<Integer> order = List.of(3, 0, 4, 1, 2);
        List<CardData> arranged = order.stream().map(drawn::get).toList();
        host.keepHand(arranged);

        // The joiner's client holds that same hand as P2 and applies the permutation.
        assertTrue(joiner.reorderP2Hand(order));
        assertEquals(names(host.getP1Hand()), names(joiner.getP2Hand()),
                "hand order must agree, since later actions address cards by index");
    }

    @Test
    void mulliganLeavesBothClientsWithTheSameHandAndDeck() {
        GameState[] pair = dealtPair(11L);
        GameState host = pair[0], joiner = pair[1];

        List<CardData> drawn = host.drawOpeningHand();
        List<Integer> bottomOrder = List.of(2, 4, 0, 3, 1);
        host.mulligan(bottomOrder.stream().map(drawn::get).toList());

        assertTrue(joiner.mulliganP2(bottomOrder));

        assertEquals(names(host.getP1MainDeck()), names(joiner.getP2MainDeck()),
                "the mulliganed cards must go to the bottom in the same order on both clients");
    }

    @Test
    void mulliganWithoutReplicationDesyncsTheDeck() {
        // Guards the reason MULLIGAN is on the wire at all: it reorders the deck.
        GameState[] pair = dealtPair(11L);
        GameState host = pair[0], joiner = pair[1];

        List<CardData> drawn = host.drawOpeningHand();
        host.mulligan(new ArrayList<>(drawn));

        assertNotEquals(names(host.getP1MainDeck()), names(joiner.getP2MainDeck()),
                "an unreplicated mulligan must not silently look like agreement");
    }

    // ── Card play replication ────────────────────────────────────────────
    //
    // executePlay is parameterised by player, so the opponent's play runs the same code against
    // P2's zones. These assert the two seats really do produce the same result, which is the
    // whole reason there is one implementation instead of a P1 copy and a P2 copy.

    private static CardData backup(String name, String element, int cost) {
        return new CardData(null, name, element, cost, 0, "Backup", false, 0, false, false,
                Set.of(), 0, List.of(), null, List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(),
                false, false, null, false, false, false, false, false, 1,
                null, null, null, "");
    }

    @Test
    void aPlayCostsTheSameInEitherSeat() {
        // One Backup cast for 2, paid by discarding two same-element cards from hand.
        MainWindow p1Seat = new MainWindow();
        MainWindow p2Seat = new MainWindow();
        for (MainWindow mw : List.of(p1Seat, p2Seat)) {
            List<CardData> hand = mw == p1Seat ? mw.gameState.getP1Hand() : mw.gameState.getP2Hand();
            hand.add(backup("Cast Me", "Fire", 2));
            hand.add(backup("Pay A", "Fire", 3));
            hand.add(backup("Pay B", "Fire", 3));
        }

        p1Seat.executePlay(true,  p1Seat.gameState.getP1Hand().get(0), 0,
                List.of(1, 2), List.of(), Map.of());
        p2Seat.executePlay(false, p2Seat.gameState.getP2Hand().get(0), 0,
                List.of(1, 2), List.of(), Map.of());

        assertEquals(0, p1Seat.gameState.getP1Hand().size(), "hand is emptied by the cast and its payment");
        assertEquals(p1Seat.gameState.getP1Hand().size(), p2Seat.gameState.getP2Hand().size());
        assertEquals(names(p1Seat.gameState.getP1BreakZone()), names(p2Seat.gameState.getP2BreakZone()),
                "the same cards must reach the Break Zone in the same order");
        assertEquals(p1Seat.p1BackupCards[0].name(), p2Seat.p2BackupCards[0].name(),
                "the cast card must land in the same backup slot on both boards");
        assertEquals(0, p1Seat.gameState.getP1CpForElement("Fire"),
                "CP generated for payment is cleared once the cost is paid");
        assertEquals(p1Seat.gameState.getP1CpForElement("Fire"),
                     p2Seat.gameState.getP2CpForElement("Fire"));
    }

    @Test
    void discardIndicesBelowThePlayedCardShiftItCorrectly() {
        // The played card sits above its payment in hand, so removing the payment first moves it.
        MainWindow mw = new MainWindow();
        List<CardData> hand = mw.gameState.getP2Hand();
        hand.add(backup("Pay A", "Ice", 3));
        hand.add(backup("Pay B", "Ice", 3));
        hand.add(backup("Cast Me", "Ice", 2));

        mw.executePlay(false, hand.get(2), 2, List.of(0, 1), List.of(), Map.of());

        assertTrue(mw.gameState.getP2Hand().isEmpty(),
                "the played card must be removed, not left behind by a stale index");
        assertEquals("Cast Me", mw.p2BackupCards[0].name());
        // Discards are applied high-index-first so the lower indices stay valid, which puts the
        // later card in the Break Zone first. Deterministic, so both clients agree on it.
        assertEquals(List.of("Pay B", "Pay A"), names(mw.gameState.getP2BreakZone()));
    }

    @Test
    void aMalformedHandOrderIsRejectedRatherThanApplied() {
        GameState joiner = dealtPair(3L)[1];
        List<String> before = names(joiner.getP2Hand());

        assertFalse(joiner.reorderP2Hand(List.of(0, 0, 1, 2, 3)), "duplicate index");
        assertFalse(joiner.reorderP2Hand(List.of(0, 1, 2, 3)),    "wrong length");
        assertFalse(joiner.reorderP2Hand(List.of(0, 1, 2, 3, 9)), "out of range");
        assertFalse(joiner.mulliganP2(List.of(0, 1, 2, 3, 9)),    "out of range");

        assertEquals(before, names(joiner.getP2Hand()),
                "a rejected order must leave the hand untouched so the desync can be reported");
    }

    // =========================================================================================
    // Phase 4 — the combat wire format.
    //
    // Applying an ATTACK or a BLOCK needs a MainWindow, so what is reachable here is the format
    // itself: what a declaration puts on the wire, and what comes back off it. That is where this
    // protocol is easiest to get quietly wrong, because a JSON object's keys are strings and the
    // party damage spread is keyed by attacker slot.
    // =========================================================================================

    @Test
    void anAttackNamesItsZoneAndEverySlotInTheParty() {
        JSONObject solo = RemoteOpponent.attackAction(
                ForwardTarget.CardZone.MONSTER, List.of(2), 7000).payload();
        assertEquals("MONSTER", solo.getString("zone"));
        assertEquals(List.of(2), intList(solo.getJSONArray("indices")));
        assertEquals(7000, solo.getInt("power"));

        JSONObject party = RemoteOpponent.attackAction(
                ForwardTarget.CardZone.FORWARD, List.of(0, 3), 12000).payload();
        assertEquals(List.of(0, 3), intList(party.getJSONArray("indices")),
                "every party member travels — the receiver replays the whole declaration");
        assertEquals(12000, party.getInt("power"),
                "the combined power rides along so the receiver can cross-check its own total");
    }

    @Test
    void decliningToBlockSendsNoSlotAtAll() {
        JSONObject declined = RemoteOpponent.blockAction(null, -1, null).payload();
        assertFalse(declined.getBoolean("blocked"));
        assertFalse(declined.has("zone"), "a declined block names no blocker");
        assertFalse(declined.has("idx"),  "...and no slot, so a stale index cannot be read back");
    }

    @Test
    void aSingleBlockCarriesItsSlotAndNoDamageSpread() {
        JSONObject blocked = RemoteOpponent.blockAction(
                ForwardTarget.CardZone.BACKUP, 1, null).payload();
        assertTrue(blocked.getBoolean("blocked"));
        assertEquals("BACKUP", blocked.getString("zone"));
        assertEquals(1, blocked.getInt("idx"));
        assertFalse(blocked.has("damage"),
                "one attacker takes the blocker's whole power — there is nothing to divide");
    }

    @Test
    void aPartyBlocksDamageSpreadSurvivesItsStringKeys() {
        Map<Integer, Integer> spread = new LinkedHashMap<>();
        spread.put(0, 3000);
        spread.put(3, 5000);

        JSONObject payload = RemoteOpponent.blockAction(
                ForwardTarget.CardZone.FORWARD, 2, spread).payload();

        assertEquals(spread, RemoteOpponent.damageSpread(payload),
                "attacker slots go out as JSON keys, which are strings — they have to come back "
                + "as the Integers the damage map is keyed by, or every slot misses");
    }

    @Test
    void aBlockWithNoSpreadReadsAsNoDamageRatherThanNull() {
        JSONObject declined = RemoteOpponent.blockAction(null, -1, null).payload();
        assertEquals(Map.of(), RemoteOpponent.damageSpread(declined),
                "the caller assigns whatever this returns, so an absent spread must be empty");
    }

    // =========================================================================================
    // Two-sided card choices — the Phase 5 seam.
    //
    // Unlike an ATTACK or a BLOCK, which say what already happened, a CHOICE is one half of a
    // decision the other client is parked on mid-effect. Every answer is a list of small
    // integers; the ChoiceKind says what they index. Hand and slot indices do not flip on the way
    // across — the two clients hold each zone in the same order, so index n means the same card on
    // both — while a field code names a side and must be flipped into the receiver's frame.
    // =========================================================================================

    @Test
    void aRevealNamesEveryCardTheSenderChoseToShow() {
        JSONObject payload = RemoteOpponent.choiceAction(
                ChoiceKind.REVEAL_HAND, List.of(0, 2, 5)).payload();
        assertEquals("REVEAL_HAND", payload.getString("kind"));
        assertEquals(List.of(0, 2, 5), intList(payload.getJSONArray("indices")));
    }

    @Test
    void aSelectionCarriesTheOneCardItPicked() {
        JSONObject payload = RemoteOpponent.choiceAction(
                ChoiceKind.SELECT_REVEALED, List.of(2)).payload();
        assertEquals("SELECT_REVEALED", payload.getString("kind"));
        assertEquals(List.of(2), intList(payload.getJSONArray("indices")),
                "the selection indexes the opponent's hand, not the revealed subset — the "
                + "receiver discards from the hand directly");
    }

    // The kinds share one action type, so the kind is what tells a waiting client whether the
    // answer that just landed is the one it is parked on.
    @Test
    void everyKindOfChoiceIsDistinguishableOnTheWire() {
        Set<String> names = new HashSet<>();
        for (ChoiceKind kind : ChoiceKind.values())
            assertTrue(names.add(RemoteOpponent.choiceAction(kind, List.of()).payload().getString("kind")),
                    "two kinds serialized to the same string, so a waiting client cannot tell "
                    + "which question was answered");
    }

    // A CHOICE has to survive the same JSON round trip every other action does.
    @Test
    void aPriorityPassCarriesNothingAndStillArrives() {
        GameAction back = GameAction.deserialize(
                RemoteOpponent.choiceAction(ChoiceKind.PRIORITY_PASS, List.of()).serialize());
        assertEquals("PRIORITY_PASS", back.payload().getString("kind"));
        assertEquals(0, back.payload().getJSONArray("indices").length(),
                "the message is the whole answer — an empty payload must survive the round trip, "
                + "or the combat window it releases never closes");
    }

    @Test
    void aChoiceSurvivesSerialization() {
        GameAction sent = RemoteOpponent.choiceAction(ChoiceKind.REVEAL_HAND, List.of(1, 4));
        GameAction back = GameAction.deserialize(sent.serialize());
        assertEquals(ActionType.CHOICE, back.type());
        assertEquals("REVEAL_HAND", back.payload().getString("kind"));
        assertEquals(List.of(1, 4), intList(back.payload().getJSONArray("indices")));
    }

    private static List<Integer> intList(JSONArray arr) {
        List<Integer> out = new ArrayList<>(arr.length());
        for (int i = 0; i < arr.length(); i++) out.add(arr.getInt(i));
        return out;
    }

    // =========================================================================================
    // Packing a field target into a choice answer.
    //
    // An answer travels as integers, so a card the player pointed at has to survive as one. The
    // side is the part that does not mean the same thing on both clients: the chooser packs their
    // own field, and the receiver reads it as their opponent's.
    // =========================================================================================

    @Test
    void aFieldTargetSurvivesBeingPackedIntoOneInteger() {
        for (ForwardTarget.CardZone zone : ForwardTarget.CardZone.values()) {
            for (boolean isP1 : new boolean[] { true, false }) {
                ForwardTarget target = new ForwardTarget(isP1, 3, zone);
                assertEquals(target, ForwardTarget.fromChoiceCode(target.choiceCode()),
                        "side, zone and slot all have to come back — a target that decodes to the "
                        + "wrong zone breaks a different card than the player chose");
            }
        }
    }

    @Test
    void everyFieldSlotPacksToADistinctCode() {
        Set<Integer> codes = new HashSet<>();
        for (ForwardTarget.CardZone zone : ForwardTarget.CardZone.values())
            for (boolean isP1 : new boolean[] { true, false })
                for (int idx = 0; idx < 12; idx++)
                    assertTrue(codes.add(new ForwardTarget(isP1, idx, zone).choiceCode()),
                            "two slots sharing a code would silently retarget a choice");
    }

    @Test
    void theSideFlipsOnArrivalAndNothingElseDoes() {
        ForwardTarget chosen = new ForwardTarget(true, 2, ForwardTarget.CardZone.BACKUP);
        ForwardTarget asRead = ForwardTarget.fromChoiceCode(
                ForwardTarget.flipChoiceSide(chosen.choiceCode()));
        assertEquals(new ForwardTarget(false, 2, ForwardTarget.CardZone.BACKUP), asRead,
                "the chooser packed their own Backup 2; on this client that is the opponent's");
    }

    @Test
    void flippingTwiceIsTheOriginalCode() {
        int code = new ForwardTarget(false, 4, ForwardTarget.CardZone.MONSTER).choiceCode();
        assertEquals(code, ForwardTarget.flipChoiceSide(ForwardTarget.flipChoiceSide(code)),
                "the flip is the only reinterpretation applied, so it has to be its own inverse");
    }

    @Test
    void aCodeNamingNoZoneDecodesToNothingRatherThanThrowing() {
        int noSuchZone = 0xF << 8;
        assertNull(ForwardTarget.fromChoiceCode(noSuchZone),
                "this is the first thing done to a number a remote client sent — a malformed one "
                + "is a desync to report, not an exception thrown through a half-resolved effect");
    }

    // =========================================================================================
    // Routing a question to whoever is in the seat.
    //
    // MainWindow.decide is the single place that knows how a seat is answered. These pin the two
    // branches reachable without a connection; the remote branch needs a live socket and a modal
    // wait, which is two-window territory.
    // =========================================================================================

    @Test
    void theLocalSeatIsAskedThroughItsOwnDialog() {
        MainWindow mw = new MainWindow();
        boolean[] asked = { false, false };
        List<Integer> answer = mw.decide(PlayerChoice.by(true, ChoiceKind.OWN_FIELD_CARD)
                .locally(() -> { asked[0] = true; return List.of(7); })
                .byCpu(()   -> { asked[1] = true; return List.of(9); }));
        assertEquals(List.of(7), answer);
        assertTrue(asked[0], "seat P1 is the local player on every client");
        assertFalse(asked[1], "the AI must not answer a question put to the local player");
    }

    @Test
    void theOtherSeatIsAnsweredByTheAiWhenNobodyIsConnected() {
        MainWindow mw = new MainWindow();
        boolean[] asked = { false, false };
        List<Integer> answer = mw.decide(PlayerChoice.by(false, ChoiceKind.OWN_FIELD_CARD)
                .locally(() -> { asked[0] = true; return List.of(7); })
                .byCpu(()   -> { asked[1] = true; return List.of(9); }));
        assertEquals(List.of(9), answer);
        assertFalse(asked[0], "the local player must not answer for their opponent");
        assertTrue(asked[1]);
    }

    @Test
    void anUndescribedBranchDeclinesRatherThanInventingAMove() {
        MainWindow mw = new MainWindow();
        assertEquals(List.of(), mw.decide(PlayerChoice.by(false, ChoiceKind.OWN_FIELD_CARD)),
                "a question that forgot to say how the AI answers it must pick nothing — "
                + "choosing something would be a rule this client applied and the other did not");
    }

    // =========================================================================================
    // Look at the top N cards — the answer covering that whole family.
    //
    // Nine different effects, one answer shape: which of the peeked cards go to hand, to the
    // Break Zone, back on top and to the bottom, each group in arrival order. It has to survive
    // the wire as integers, and — more importantly — a malformed one has to be rejected, because
    // an index appearing twice would put the same card in two zones at once.
    // =========================================================================================

    private static DeckLookDecision decision(List<Integer> hand, List<Integer> brk,
                                             List<Integer> top, List<Integer> bottom) {
        return new DeckLookDecision(hand, brk, top, bottom);
    }

    @Test
    void aDeckLookAnswerSurvivesTheRoundTrip() {
        DeckLookDecision sent = decision(List.of(2), List.of(0), List.of(3), List.of(1, 4));
        assertEquals(sent, DeckLookDecision.fromAnswer(sent.toAnswer(), 5));
    }

    @Test
    void orderWithinADestinationIsPartOfTheAnswer() {
        DeckLookDecision oneWay   = decision(List.of(), List.of(), List.of(0, 1, 2), List.of());
        DeckLookDecision reversed = decision(List.of(), List.of(), List.of(2, 1, 0), List.of());
        assertNotEquals(oneWay.toAnswer(), reversed.toAnswer(),
                "a player who ordered three cards onto the top of their deck decided something, "
                + "and it has to reach the other client");
        assertEquals(reversed, DeckLookDecision.fromAnswer(reversed.toAnswer(), 3));
    }

    @Test
    void keepingEverythingOnTopIsTheAnswerThatChangesNothing() {
        DeckLookDecision noOp = DeckLookDecision.keepOnTop(3);
        assertEquals(List.of(0, 1, 2), noOp.toTop());
        assertEquals(noOp, DeckLookDecision.fromAnswer(noOp.toAnswer(), 3),
                "the do-nothing arrangement is what an unanswered or rejected look falls back to, "
                + "so it has to survive the same round trip as a real one");
    }

    @Test
    void theCardTakenToHandIsRecoverable() {
        assertEquals(2, decision(List.of(2), List.of(), List.of(), List.of(0, 1)).handCard(),
                "riders like \"if the card added to your hand has an EX Burst\" ask for it");
        assertEquals(-1, DeckLookDecision.keepOnTop(3).handCard());
    }

    @Test
    void anAnswerThatNamesACardTwiceIsRejected() {
        assertNull(DeckLookDecision.fromAnswer(List.of(1, 0, 0, 2, 2), 3),
                "index 2 in both hand and bottom would put one card in two zones at once");
    }

    @Test
    void anAnswerThatLosesACardIsRejected() {
        assertNull(DeckLookDecision.fromAnswer(List.of(0, 0, 2, 0, 1), 3),
                "a permutation of 2 cards cannot arrange the 3 that were revealed");
    }

    @Test
    void anAnswerNamingACardThatWasNotRevealedIsRejected() {
        assertNull(DeckLookDecision.fromAnswer(List.of(1, 0, 0, 7, 1), 3));
    }

    @Test
    void countsThatOverrunTheCardsAreRejected() {
        assertNull(DeckLookDecision.fromAnswer(List.of(2, 2, 2, 0, 1, 2), 3),
                "the three counts claim six cards out of three");
    }

    // -----------------------------------------------------------------------------------------
    // The two reveal effects that answer with the same shape but live outside LookConfig.
    //
    // Their AI answers are the only part of LookAtDeckDialogs a headless test can reach, and they
    // are worth reaching: the arrangement now has to be a permutation of the revealed cards, so an
    // AI that drops or duplicates one no longer merely picks oddly — the reveal does nothing.
    // -----------------------------------------------------------------------------------------

    /** The revealed cards, cheapest first, so "takes the most expensive" is not the identity. */
    private static List<CardData> revealed() {
        return List.of(card("Cheap", 1), card("Dear", 6), card("Middling", 3));
    }

    @Test
    void theAiTakesTheDearestRevealedCardsAndBottomsTheRest() {
        DeckLookDecision d = LookAtDeckDialogs.cpuRevealAddUpToMatchingRestBottom(
                revealed(), 2, null, null, null, null, -1, null, null);
        assertEquals(List.of(1, 2), d.toHand(), "cost 6 then cost 3");
        assertEquals(List.of(0), d.toBottom());
        assertTrue(d.toBreak().isEmpty() && d.toTop().isEmpty());
    }

    @Test
    void theAiRespectsTheRevealsCostCeiling() {
        DeckLookDecision d = LookAtDeckDialogs.cpuRevealAddUpToMatchingRestBottom(
                revealed(), 2, null, null, null, null, 3, null, null);
        assertEquals(List.of(2, 0), d.toHand(), "cost 6 is out of reach, so cost 3 then cost 1");
        assertEquals(List.of(1), d.toBottom());
    }

    // A mandatory "add all" (9-051R Fat Chocobo) is not a cap: every match is taken however many
    // there are, and maxAdd stops applying. Reveal no match and nothing is taken.
    @Test
    void theAiTakesEveryMatchWhenTheAddIsMandatory() {
        DeckLookDecision d = LookAtDeckDialogs.cpuRevealAddUpToMatchingRestBottom(
                revealed(), 1, null, null, null, null, -1, null, null, true);
        assertEquals(List.of(1, 2, 0), d.toHand(), "all three, dearest first, despite maxAdd of 1");
        assertTrue(d.toBottom().isEmpty());
    }

    @Test
    void aMandatoryAddTakesNothingWhenNothingMatches() {
        DeckLookDecision d = LookAtDeckDialogs.cpuRevealAddUpToMatchingRestBottom(
                revealed(), 3, "Dragoon", null, null, null, -1, null, null, true);
        assertTrue(d.toHand().isEmpty(), "no Dragoon among them");
        assertEquals(List.of(0, 1, 2), d.toBottom());
    }

    // The forced take still has to be an arrangement of exactly the revealed cards.
    @Test
    void aMandatoryAddAnswerIsAlwaysALegalArrangement() {
        for (DeckLookDecision d : List.of(
                LookAtDeckDialogs.cpuRevealAddUpToMatchingRestBottom(
                        revealed(), 1, null, null, null, null, -1, null, null, true),
                LookAtDeckDialogs.cpuRevealAddUpToMatchingRestBottom(
                        revealed(), 9, "Dragoon", null, null, null, -1, null, null, true)))
            assertEquals(d, DeckLookDecision.fromAnswer(d.toAnswer(), 3),
                    "a mandatory take must still round-trip through the wire answer");
    }
    @Test
    void anAiRevealAnswerIsAlwaysALegalArrangement() {
        // maxAdd larger than the reveal, and a filter nothing matches: the two ways an arrangement
        // loses or repeats a card.
        for (DeckLookDecision d : List.of(
                LookAtDeckDialogs.cpuRevealAddUpToMatchingRestBottom(
                        revealed(), 9, null, null, null, null, -1, null, null),
                LookAtDeckDialogs.cpuRevealAddUpToMatchingRestBottom(
                        revealed(), 1, "Dragoon", null, null, null, -1, null, null),
                LookAtDeckDialogs.cpuRevealAddUpToExcludingNameRestBz(revealed(), 9, "Dear"),
                LookAtDeckDialogs.cpuRevealAddUpToExcludingNameRestBz(revealed(), 1, "Cheap")))
            assertEquals(d, DeckLookDecision.fromAnswer(d.toAnswer(), 3),
                    "an AI answer the receiver would reject leaves the reveal doing nothing");
    }

    // -----------------------------------------------------------------------------------------
    // Playing a revealed card onto the field.
    //
    // The fifth destination, and the one that is not a zone append: it takes a slot and can fire
    // an entry trigger. None of that travels — the placement rule is the same on both clients and
    // an entry trigger is simply the next question — so what the player decided is still an index.
    // -----------------------------------------------------------------------------------------

    /** Every eligible card, so maxPlay is what limits the AI rather than the filter. */
    private static final Predicate<CardData> ANY = c -> true;

    @Test
    void aCardPlayedOntoTheFieldSurvivesTheRoundTrip() {
        DeckLookDecision sent = new DeckLookDecision(List.of(0), List.of(), List.of(3),
                List.of(1), List.of(2));
        assertEquals(sent, DeckLookDecision.fromAnswer(sent.toAnswer(), 4));
    }

    @Test
    void theFieldGroupIsDistinctFromEveryOtherDestination() {
        DeckLookDecision played = new DeckLookDecision(List.of(), List.of(), List.of(),
                List.of(1), List.of(0));
        DeckLookDecision bottomed = new DeckLookDecision(List.of(), List.of(), List.of(),
                List.of(0, 1), List.of());
        assertNotEquals(played.toAnswer(), bottomed.toAnswer(),
                "a card put into play and a card sent to the bottom cannot read the same");
        assertEquals(List.of(0), DeckLookDecision.fromAnswer(played.toAnswer(), 2).toField());
    }

    @Test
    void theAiPlaysTheDearestCardsItIsAllowedAndBottomsTheRest() {
        DeckLookDecision d = LookAtDeckDialogs.cpuRevealPlayOntoField(revealed(), 1, ANY, RevealRest.BOTTOM);
        assertEquals(List.of(1), d.toField(), "cost 6");
        assertEquals(List.of(0, 2), d.toBottom());
        assertTrue(d.toHand().isEmpty());
    }

    @Test
    void theRevealThatSendsTheRestToHandDoesNotBottomThem() {
        DeckLookDecision d = LookAtDeckDialogs.cpuRevealPlayOntoField(revealed(), 1, ANY, RevealRest.HAND);
        assertEquals(List.of(1), d.toField());
        assertEquals(List.of(0, 2), d.toHand(), "26-053L Bartz keeps what it does not play");
        assertTrue(d.toBottom().isEmpty());
    }

    @Test
    void theAiPrefersPlayingARevealedCardToHoldingIt() {
        // Both branches accept a Forward, and every card here is one.
        DeckLookDecision d = LookAtDeckDialogs.cpuRevealAddToHandOrPlayOntoField(
                revealed(), "Forward", null, "Forward");
        assertEquals(List.of(1), d.toField(), "the dearest playable card, not the dearest card in hand");
        assertTrue(d.toHand().isEmpty(), "the two branches are alternatives — only one fires");
        assertEquals(List.of(0, 2), d.toBottom());
    }

    @Test
    void theAiFallsBackToHandWhenNothingIsPlayable() {
        DeckLookDecision d = LookAtDeckDialogs.cpuRevealAddToHandOrPlayOntoField(
                revealed(), "Forward", null, "Backup");
        assertTrue(d.toField().isEmpty(), "none of the revealed cards is a Backup");
        assertEquals(List.of(1), d.toHand());
    }

    @Test
    void theAiTakesTheTopmostCopyOfANamedCardRatherThanTheDearest() {
        List<CardData> cards = List.of(card("Moogle", 1), card("Bahamut", 9), card("Moogle", 4));
        DeckLookDecision d = LookAtDeckDialogs.cpuRevealPlayNamedOntoField(
                cards, c -> c.name().equals("Moogle"));
        assertEquals(List.of(0), d.toField(),
                "copies of one card name differ only by printing, so there is nothing to weigh");
        assertEquals(List.of(1, 2), d.toBottom());
    }

    @Test
    void anAiFieldPlayAnswerIsAlwaysALegalArrangement() {
        for (DeckLookDecision d : List.of(
                LookAtDeckDialogs.cpuRevealPlayOntoField(revealed(), 9, ANY, RevealRest.BOTTOM),
                LookAtDeckDialogs.cpuRevealPlayOntoField(revealed(), 1, c -> false, RevealRest.HAND),
                LookAtDeckDialogs.cpuRevealAddToHandOrPlayOntoField(
                        revealed(), "Backup", null, "Backup"),
                LookAtDeckDialogs.cpuRevealPlayNamedOntoField(revealed(), c -> false)))
            assertEquals(d, DeckLookDecision.fromAnswer(d.toAnswer(), 3),
                    "an AI answer the receiver would reject leaves the reveal doing nothing");
    }

    // -----------------------------------------------------------------------------------------
    // Which revealed cards a player may take.
    //
    // One predicate now answers that for the dialog and for the AI. It used to be two, and only
    // the dialog carried an unconditional "must be a Character" gate — so a reveal that said
    // "Summon" in as many words let the AI take one and offered a human nothing.
    // -----------------------------------------------------------------------------------------

    /** A card of an arbitrary type and Category, for the filters that distinguish them. */
    private static CardData typed(String name, int cost, String type, String category) {
        return new CardData(null, name, "Fire", cost, 5000, type, false, 0, false, false,
                Set.of(), 0, List.of(), null, List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(),
                false, false, null, false, false, false, false, false, 1,
                null, category, "", "");
    }

    @Test
    void aRevealThatNamesSummonsLetsAPlayerTakeOne() {
        List<CardData> cards = List.of(typed("Shiva", 2, "Summon", ""), card("Guard", 1));
        DeckLookDecision d = LookAtDeckDialogs.cpuRevealAddUpToMatchingRestBottom(
                cards, 1, null, null, null, "Summon", -1, null, null);
        assertEquals(List.of(0), d.toHand(),
                "the text says Summon, so a Character gate has no business overriding it");
    }

    @Test
    void aCostOnlyRevealRestrictsNothingButCost() {
        List<CardData> cards = List.of(typed("Shiva", 2, "Summon", ""), card("Dear", 9));
        DeckLookDecision d = LookAtDeckDialogs.cpuRevealAddUpToMatchingRestBottom(
                cards, 1, null, null, null, null, 2, null, null);
        assertEquals(List.of(0), d.toHand(),
                "\"add up to 1 card of cost 2 or less\" says card, not Character");
    }

    @Test
    void aRevealSelectingByCategoryAloneStillMeansCharacters() {
        List<CardData> cards = List.of(typed("Summoned", 2, "Summon", "VII"),
                                       typed("Embodied", 3, "Forward", "VII"));
        DeckLookDecision d = LookAtDeckDialogs.cpuRevealAddUpToMatchingRestBottom(
                cards, 1, null, "VII", null, null, -1, null, null);
        assertEquals(List.of(1), d.toHand(),
                "the noun the text used is dropped by the parser, and for a bare Job/Category "
                + "reveal it was reliably a Character one");
    }

    @Test
    void theAiWillNotTakeTheCardTheRevealExcludes() {
        DeckLookDecision d = LookAtDeckDialogs.cpuRevealAddUpToExcludingNameRestBz(
                revealed(), 2, "Dear");
        assertEquals(List.of(2, 0), d.toHand(), "the dearest card is the excluded one");
        assertEquals(List.of(1), d.toBreak(), "and it is what the Break Zone gets");
        assertTrue(d.toBottom().isEmpty(), "this reveal has no bottom-of-deck destination");
    }

    @Test
    void aDeckLookAnswerCrossesTheWireIntact() {
        DeckLookDecision sent = decision(List.of(1), List.of(), List.of(0), List.of(2));
        GameAction action = RemoteOpponent.choiceAction(ChoiceKind.DECK_LOOK, sent.toAnswer());
        GameAction back   = GameAction.deserialize(action.serialize());
        assertEquals("DECK_LOOK", back.payload().getString("kind"));
        assertEquals(sent, DeckLookDecision.fromAnswer(intList(back.payload().getJSONArray("indices")), 3));
    }

    // =========================================================================================
    // Naming an Element, a Job or a Category.
    //
    // Not a choice among cards, so nothing on the board indexes it — the vocabulary it came from
    // does. Asserted on Elements, whose list is a constant; Jobs and Categories run the identical
    // code over lists the card database supplies, which a unit test has no business loading.
    // =========================================================================================

    private static final NamedThing.Vocabulary ELEMENT = NamedThing.Vocabulary.ELEMENT;

    /** Discards the log these calls offer for database trouble; the Element list needs no database. */
    private static final java.util.function.Consumer<String> QUIET = msg -> {};

    @Test
    void aNamedElementSurvivesTheRoundTrip() {
        List<Integer> answer = NamedThing.toAnswer(NamedThing.of(ELEMENT, "Water"), QUIET);
        assertEquals(List.of(new NamedThing(ELEMENT, "Water")),
                NamedThing.fromAnswer(answer, QUIET));
    }

    @Test
    void twoNamedThingsTravelInOneAnswer() {
        List<NamedThing> both = List.of(new NamedThing(ELEMENT, "Fire"),
                                        new NamedThing(ELEMENT, "Dark"));
        assertEquals(both, NamedThing.fromAnswer(NamedThing.toAnswer(both, QUIET), QUIET),
                "\"name 1 Element and 1 Job\" is one question with two answers");
    }

    @Test
    void theVocabularyOrdinalIsPartOfTheAnswer() {
        assertEquals(List.of(ELEMENT.ordinal(), 0),
                NamedThing.toAnswer(NamedThing.of(ELEMENT, "Fire"), QUIET),
                "the receiver has to know which list to read the index out of");
    }

    @Test
    void namingNothingIsAnEmptyAnswerRatherThanAMissingOne() {
        assertEquals(List.of(), NamedThing.toAnswer(NamedThing.of(ELEMENT, null), QUIET));
        assertEquals(List.of(), NamedThing.fromAnswer(List.of(), QUIET));
    }

    @Test
    void aNameThisClientDoesNotKnowIsNotSent() {
        assertEquals(List.of(), NamedThing.toAnswer(NamedThing.of(ELEMENT, "Plasma"), QUIET),
                "half an answer is worse than none — the receiver would apply the part that survived");
    }

    @Test
    void namesAreMatchedWithoutRegardToCase() {
        assertEquals(NamedThing.toAnswer(NamedThing.of(ELEMENT, "Fire"), QUIET),
                     NamedThing.toAnswer(NamedThing.of(ELEMENT, "fire"), QUIET),
                     "the dialogs and the database do not always agree on case");
    }

    @Test
    void anAnswerNamingAnUnknownVocabularyIsRejected() {
        assertNull(NamedThing.fromAnswer(List.of(99, 0), QUIET));
    }

    @Test
    void anAnswerIndexingPastTheVocabularyIsRejected() {
        assertNull(NamedThing.fromAnswer(List.of(ELEMENT.ordinal(), 99), QUIET),
                "two clients disagreeing about the card database is a desync, not a name to act on");
    }

    @Test
    void anAnswerMissingHalfOfAPairIsRejected() {
        assertNull(NamedThing.fromAnswer(List.of(ELEMENT.ordinal()), QUIET));
    }

    // =========================================================================================
    // Rolls the game makes rather than a player.
    //
    // "Your opponent discards 1 card at random" used to call Math.random() on both clients, which
    // discarded different cards. One client rolls now and the other applies — so the receiver has
    // to be able to tell a real run of picks from a run that could not have happened, and the pool
    // being one smaller each time is what makes that non-obvious.
    // =========================================================================================

    @Test
    void aPickIsAgainstThePoolAsItStoodForThatPick() {
        assertTrue(RandomPicks.fitPool(List.of(2, 1, 0), 3),
                "three picks out of three: the last one addresses a pool of one");
        assertTrue(RandomPicks.fitPool(List.of(0, 0, 0), 3));
    }

    @Test
    void aPickPastTheShrunkenPoolIsRejected() {
        assertFalse(RandomPicks.fitPool(List.of(0, 2), 3),
                "the second pick sees two cards, so 2 is off the end of it");
        assertTrue(RandomPicks.fitPool(List.of(0, 1), 3),
                "and 1 is the last position it can legally name");
    }

    @Test
    void morePicksThanCardsIsRejected() {
        assertFalse(RandomPicks.fitPool(List.of(0, 0, 0), 2));
        assertFalse(RandomPicks.fitPool(List.of(-1), 3));
        assertFalse(RandomPicks.fitPool(List.of(3), 3));
    }

    @Test
    void rollingNeverProducesARunItsOwnCheckWouldReject() {
        for (int pool = 1; pool <= 6; pool++)
            for (int count = 1; count <= pool + 2; count++)
                for (int attempt = 0; attempt < 40; attempt++) {
                    List<Integer> picks = RandomPicks.roll(count, pool);
                    assertEquals(Math.min(count, pool), picks.size(),
                            "asking for more cards than the pool holds takes the whole pool");
                    assertTrue(RandomPicks.fitPool(picks, pool),
                            "the receiver rejecting a legitimate roll means nothing happens at all");
                }
    }

    @Test
    void anEmptyPoolIsPickedFromNotAtAll() {
        assertEquals(List.of(), RandomPicks.roll(3, 0));
        assertTrue(RandomPicks.fitPool(List.of(), 0));
    }

    // =========================================================================================
    // Phase 4 — the combat checksum.
    //
    // A battle's whole output is damage, breaks and dull state. The board digest used to carry
    // only which cards were standing, so two clients that disagreed about a point of damage
    // still agreed on the hash — and stayed agreed until something else broke the card, by which
    // point the cause was many turns behind. These are the assertions that the digest now covers
    // what combat actually moves.
    // =========================================================================================

    /** The same battle-1 board seen from each seat: the card is P1's here and P2's there. */
    private static MainWindow[] mirroredSeats() {
        MainWindow host   = new MainWindow();
        MainWindow joiner = new MainWindow();
        host.placeCardInForwardZone(card("Attacker", 3));
        joiner.placeP2CardInForwardZone(card("Attacker", 3));
        return new MainWindow[] { host, joiner };
    }

    private static String hostDigest(MainWindow mw)   { return MatchChecksum.ofCombat(mw, true,  1); }
    private static String joinerDigest(MainWindow mw) { return MatchChecksum.ofCombat(mw, false, 1); }

    @Test
    void bothSeatsDigestTheSameBattleIdentically() {
        MainWindow[] seats = mirroredSeats();
        assertEquals(hostDigest(seats[0]), joinerDigest(seats[1]),
                "each client seats itself as P1, so the digest has to be taken in host/joiner "
                + "order or two agreeing boards would never match");
    }

    @Test
    void aPointOfDamageOnlyOneSeatAppliedChangesTheDigest() {
        MainWindow[] seats = mirroredSeats();
        String before = hostDigest(seats[0]);

        seats[0].p1ForwardDamage.set(0, 3000);

        assertNotEquals(before, hostDigest(seats[0]),
                "damage has to reach the digest — a battle that landed here and not there is "
                + "exactly the divergence this exists to catch");
        assertNotEquals(hostDigest(seats[0]), joinerDigest(seats[1]),
                "and the two seats must now disagree");
    }

    @Test
    void aMissedDullChangesTheDigest() {
        MainWindow[] seats = mirroredSeats();
        String before = hostDigest(seats[0]);

        seats[0].p1ForwardStates.set(0, CardState.DULL);

        assertNotEquals(before, hostDigest(seats[0]),
                "attacking dulls, so a dull that only one client applied is a free extra attack");
    }

    @Test
    void consecutiveBattlesOnOneBoardDigestDifferently() {
        MainWindow[] seats = mirroredSeats();
        assertNotEquals(MatchChecksum.ofCombat(seats[0], true, 1),
                        MatchChecksum.ofCombat(seats[0], true, 2),
                "the battle number is in the digest, so a checksum cannot be matched against the "
                + "wrong battle and pass");
    }
}
