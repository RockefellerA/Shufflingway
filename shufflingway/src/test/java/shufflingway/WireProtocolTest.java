package shufflingway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import shufflingway.net.ActionType;
import shufflingway.net.ChoiceKind;
import shufflingway.net.ConnectionListener;
import shufflingway.net.GameAction;
import shufflingway.net.GameConnection;
import shufflingway.net.MatchSetup;

/**
 * The choice protocol over a real TCP socket.
 *
 * <p>Everything else asserting on multiplayer builds both sides in one JVM and compares them, which
 * never touches the transport: {@link MultiplayerSetupTest} proves an answer <em>encodes</em>
 * correctly, and this proves it arrives. The two halves failed differently — an answer that encodes
 * fine and never lands leaves the other client parked on a modal wait forever, which is the failure
 * mode of the whole seam.
 *
 * <p>Loopback, on an ephemeral port, with the same {@link GameConnection} the game uses. What is
 * still out of reach is the layer above: {@code awaitChoice} parks in a Swing modal and needs
 * {@code MainWindow}, so the dialogs remain a two-window manual check.
 */
class WireProtocolTest {

    private ServerSocket   listener;
    private Socket         hostSide, joinerSide;
    private GameConnection host,     joiner;

    /** Actions the joiner's reader thread has delivered, oldest first. */
    private final BlockingQueue<GameAction> inbox = new ArrayBlockingQueue<>(64);

    /** Reasons the joiner was told the connection ended. */
    private final BlockingQueue<String> disconnects = new ArrayBlockingQueue<>(8);

    @BeforeEach
    void connect() throws IOException {
        listener   = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        joinerSide = new Socket(InetAddress.getLoopbackAddress(), listener.getLocalPort());
        hostSide   = listener.accept();

        host   = new GameConnection(hostSide);
        joiner = new GameConnection(joinerSide);
        joiner.addListener(new ConnectionListener() {
            @Override public void onActionReceived(GameAction action) { inbox.add(action); }
            @Override public void onDisconnected(String reason) {
                disconnects.add(reason == null ? "(none given)" : reason);
            }
        });
        joiner.start();
    }

    @AfterEach
    void disconnect() throws IOException {
        if (host     != null) host.close();
        if (joiner   != null) joiner.close();
        if (listener != null) listener.close();
    }

    /** The next action to arrive, or a failure — never a hang, which is the bug being hunted. */
    private GameAction next() throws InterruptedException {
        GameAction action = inbox.poll(5, TimeUnit.SECONDS);
        assertNotNull(action, "nothing arrived within 5s — the far client would still be waiting");
        return action;
    }

    private static List<Integer> indicesOf(GameAction action) {
        JSONArray raw = action.payload().getJSONArray("indices");
        List<Integer> out = new ArrayList<>(raw.length());
        for (int i = 0; i < raw.length(); i++) out.add(raw.getInt(i));
        return out;
    }

    /** Sends one answer of {@code kind} and reads back what landed on the other side. */
    private List<Integer> roundTrip(ChoiceKind kind, List<Integer> answer)
            throws InterruptedException {
        host.send(RemoteOpponent.choiceAction(kind, answer));
        GameAction arrived = next();
        assertEquals(kind.name(), arrived.payload().getString("kind"));
        return indicesOf(arrived);
    }

    @Test
    void everyKindOfAnswerSurvivesTheSocket() throws InterruptedException {
        // The payload shape differs per kind, so each gets one that looks like the real thing.
        for (ChoiceKind kind : ChoiceKind.values()) {
            List<Integer> answer = switch (kind) {
                case PRIORITY_PASS  -> List.of();
                case NAMED          -> List.of(NamedThing.Vocabulary.ELEMENT.ordinal(), 5);
                case DECK_LOOK      -> new DeckLookDecision(List.of(0), List.of(), List.of(3),
                                            List.of(1), List.of(2)).toAnswer();
                case OWN_FIELD_CARD -> List.of(new ForwardTarget(true, 2,
                                            ForwardTarget.CardZone.FORWARD).choiceCode());
                default             -> List.of(1, 0, 2);
            };
            assertEquals(answer, roundTrip(kind, answer),
                    kind + " did not arrive as it was sent");
        }
    }

    @Test
    void aPassCarryingNothingStillArrives() throws InterruptedException {
        assertEquals(List.of(), roundTrip(ChoiceKind.PRIORITY_PASS, List.of()),
                "the message is the whole answer; an empty payload that vanished would leave the "
                + "combat window it releases open forever");
    }

    @Test
    void aDeckLookArrivesAsTheSameArrangement() throws InterruptedException {
        DeckLookDecision sent = new DeckLookDecision(List.of(0), List.of(4), List.of(3),
                                                     List.of(1), List.of(2));
        List<Integer> landed = roundTrip(ChoiceKind.DECK_LOOK, sent.toAnswer());
        assertEquals(sent, DeckLookDecision.fromAnswer(landed, 5),
                "five cards went to five different destinations and all five have to survive");
    }

    @Test
    void aNamedThingArrivesNamingTheSameThing() throws InterruptedException {
        List<NamedThing> sent = NamedThing.of(NamedThing.Vocabulary.ELEMENT, "Water");
        List<Integer> landed = roundTrip(ChoiceKind.NAMED, NamedThing.toAnswer(sent, msg -> {}));
        assertEquals(sent, NamedThing.fromAnswer(landed, msg -> {}));
    }

    @Test
    void aFieldTargetArrivesOnTheOtherSideOfTheBoard() throws InterruptedException {
        ForwardTarget mine = new ForwardTarget(true, 1, ForwardTarget.CardZone.FORWARD);
        List<Integer> landed = roundTrip(ChoiceKind.OWN_FIELD_CARD, List.of(mine.choiceCode()));

        ForwardTarget theirs = ForwardTarget.fromChoiceCode(
                ForwardTarget.flipChoiceSide(landed.get(0)));
        assertEquals(new ForwardTarget(false, 1, ForwardTarget.CardZone.FORWARD), theirs,
                "the sender packed their own side, and it is the receiver's opponent's");
    }

    @Test
    void answersArriveInTheOrderTheyWereSent() throws InterruptedException {
        // The protocol is one question at a time, but a pass can follow an answer immediately, and
        // newline framing is the only thing keeping two actions in one write buffer apart.
        host.send(RemoteOpponent.choiceAction(ChoiceKind.DECK_LOOK, List.of(1, 0, 0, 0, 0)));
        host.send(RemoteOpponent.choiceAction(ChoiceKind.PRIORITY_PASS, List.of()));
        host.send(RemoteOpponent.choiceAction(ChoiceKind.MAY, List.of(1)));

        assertEquals("DECK_LOOK",     next().payload().getString("kind"));
        assertEquals("PRIORITY_PASS", next().payload().getString("kind"));
        assertEquals("MAY",           next().payload().getString("kind"));
    }

    @Test
    void aPhasePriorityOfferAndItsAnswerCrossInOrder() throws InterruptedException {
        // The full phase-transition exchange: the advancing client offers and holds the phase
        // open, the other answers, and only then does the advance follow. An offer that went
        // missing would advance the phase with the opponent never having had priority; a lost
        // answer would hold the phase open forever.
        host.send(GameAction.of(shufflingway.net.ActionType.PRIORITY_OFFER));
        GameAction offer = next();
        assertEquals(shufflingway.net.ActionType.PRIORITY_OFFER, offer.type());
        assertEquals(0, offer.payload().length(),
                "an empty payload is the whole message and has to survive the round trip");

        host.send(RemoteOpponent.choiceAction(ChoiceKind.PRIORITY_PASS, List.of()));
        host.send(GameAction.of(shufflingway.net.ActionType.ADVANCE_PHASE));
        assertEquals("PRIORITY_PASS", next().payload().getString("kind"));
        assertEquals(shufflingway.net.ActionType.ADVANCE_PHASE, next().type(),
                "the advance must not overtake the answer that permits it");
    }

    // -----------------------------------------------------------------------------------------
    // Phase 6 — the peer going away.
    //
    // The disconnect signal is what releases anything waiting on that peer, and a choice wait is a
    // modal dialog holding the EDT. If the signal never fires, the client is frozen behind the
    // notice telling it why. So the signal itself is worth asserting, even though what the main
    // window then does with it needs a real window.
    // -----------------------------------------------------------------------------------------

    @Test
    void closingOneEndTellsTheOther() throws InterruptedException {
        host.close();
        assertNotNull(disconnects.poll(5, TimeUnit.SECONDS),
                "nothing waiting on this peer would ever be released");
    }

    @Test
    void aGoodbyeArrivesBeforeTheSocketCloses() throws InterruptedException {
        // The close carries no reason, so the only way to say "they left" rather than "the network
        // died" is to get the message out while the socket is still up.
        host.send(GameAction.of(shufflingway.net.ActionType.DISCONNECT,
                new org.json.JSONObject().put("reason", "Player left")));
        GameAction goodbye = next();
        assertEquals(shufflingway.net.ActionType.DISCONNECT, goodbye.type());
        assertEquals("Player left", goodbye.payload().getString("reason"));

        host.close();
        assertNotNull(disconnects.poll(5, TimeUnit.SECONDS),
                "the close still has to follow — a peer that only sent DISCONNECT and stayed "
                + "connected must not leave this client hanging either");
    }

    /** A main window seated in a networked game against the far end of this test's socket. */
    private MainWindow networkedWindow() {
        MainWindow mw = new MainWindow();
        mw.opponent = new RemoteOpponent(mw, joiner,
                new MatchSetup(1, List.of("h1"), "Deck", "Host", 7L, true, true));
        return mw;
    }

    @Test
    void aDropEndsTheGameBeforeAnythingWaitingOnThePeerIsReleased() {
        MainWindow mw = networkedWindow();
        assertFalse(mw.gameState.isP1GameOver());

        mw.onOpponentDisconnected("Player left");
        assertTrue(mw.gameState.isP1GameOver(),
                "cancelling releases continuations — an outstanding block resumes as \"no block\", "
                + "a priority wait returns and carries on. They have to unwind into a finished "
                + "game, so the flag is set first");
    }

    @Test
    void theCloseFollowingAGoodbyeIsNotReportedTwice() {
        MainWindow mw = networkedWindow();
        mw.onOpponentDisconnected("Player left");
        String afterFirst = mw.gameState.isP1GameOver() ? "over" : "live";

        // The DISCONNECT message lands first, then the socket closing behind it fires again.
        mw.onOpponentDisconnected("Connection closed");
        assertEquals("over", afterFirst);
        assertTrue(mw.gameState.isP1GameOver());
    }

    @Test
    void aSoloGameIgnoresTheWholeThing() {
        MainWindow solo = new MainWindow();   // opponent is the built-in AI
        solo.onOpponentDisconnected("Connection closed");
        assertFalse(solo.gameState.isP1GameOver(),
                "there is no peer to lose, and ending the game would be a bug rather than tidiness");
    }

    @Test
    void theTargetsAControllerChoosesAreSentToTheOpponentWaitingOnThem() throws InterruptedException {
        // The other client is parked at the same point in the same effect, so the answer has to
        // leave this one or that game stops.
        MainWindow mw = sendingWindow();
        seatP2Forward(mw, forward("Sephiroth"));

        mw.selectChosenTargets(true,
                List.of(new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD)),
                1, "Choose 1 Forward", "Waiting...",
                () -> List.of(new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD)),
                List::of);

        GameAction sent = next();
        assertEquals(shufflingway.net.ActionType.CHOICE, sent.type());
        assertEquals(ChoiceKind.CHOSEN_TARGETS.name(), sent.payload().getString("kind"));
        assertEquals(new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD).choiceCode(),
                sent.payload().getJSONArray("indices").getInt(0),
                "packed from this client's seat; the receiver flips it into theirs");
    }

    @Test
    void aSummonsTargetsDoNotAlsoTravelAsALooseChoice() throws InterruptedException {
        // They ride inside the PLAY_CARD that casts it, and the receiving client replays them
        // rather than asking. A CHOICE sent as well would sit in the answer buffer with no waiter,
        // for the next question of that kind to pick up as its own.
        MainWindow mw = sendingWindow();
        seatP2Forward(mw, forward("Target"));
        CardData summon = summonChoosingAForward();
        mw.gameState.getIdentity().put(summon, true);
        mw.gameState.getP1Hand().add(summon);

        mw.executePlay(true, summon, 0, List.of(), List.of(), Map.of(), null, false, Map.of());

        assertTrue(inbox.isEmpty(),
                "executePlay's own send is the caller's job, and nothing else may go out from here");
    }

    @Test
    void anAbilityThatSpendsItsOwnSourceIsSentWithTheSlotItHeld() throws InterruptedException {
        // The activation used to be sent after the payment, by which time a "put [self] into the
        // Break Zone" cost had taken the source off the field — so the sender could not say where
        // it was, reported a desync, and sent nothing. It now goes from the commit point.
        MainWindow mw = sendingWindow();
        mw.desyncReported = true;   // the old failure opened a modal; make it a missing message instead
        CardData sage = abilityCard("Sage", "Backup", "Put Sage into the Break Zone: "
                + "All the Forwards you control gain +1000 power until the end of the turn.");
        ActionAbility ability = sage.actionAbilities().get(0);
        assertFalse(ability.breakZoneCosts().isEmpty(), "the text has to parse as a Break Zone cost");
        mw.gameState.getIdentity().put(sage, true);
        mw.p1BackupCards[2]  = sage;
        mw.p1BackupStates[2] = CardState.ACTIVE;

        mw.autoAbilityTriggers.showActionAbilityPaymentDialog(ability, sage, () -> {}, true);

        GameAction sent = next();
        assertEquals(ActionType.ACTIVATE_ABILITY, sent.type());
        assertEquals("BACKUP", sent.payload().getString("zone"));
        assertEquals(2, sent.payload().getInt("idx"), "where it stood when the payment committed");
        assertNull(mw.p1BackupCards[2], "and the cost was still paid here");
    }

    private static final String BOOST = "All the Forwards you control gain +1000 power until the end of the turn.";

    private static CardData abilityCard(String name, String type, String text) {
        return new CardData(null, name, "Fire", 2, 5000, type, false, 0, false, false,
                Set.of(), 0, List.of(), null, List.of(),
                CardData.parseActionAbilities(text), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(),
                false, false, null, false, false, false, false, false, 1,
                null, null, null, text);
    }

    @Test
    void anAbilityOffAPrimedStackIsSentAsTheTopCard() throws InterruptedException {
        // The Forward menu hands the payment the primed top card, which is not what the slot's
        // Forward list holds — so the source was "not on the field" and nothing was sent.
        MainWindow mw = sendingWindow();
        mw.desyncReported = true;
        CardData base = forward("Clive");
        CardData top  = abilityCard("Ifrit", "Forward", "《Dull》: " + BOOST);
        mw.gameState.getIdentity().put(base, true);
        mw.placeCardInForwardZone(base);
        mw.p1ForwardPrimedTop.set(0, top);

        mw.sendAbilityActivation(top.actionAbilities().get(0), top, AbilityPayment.none());

        GameAction sent = next();
        assertEquals("FORWARD", sent.payload().getString("zone"));
        assertEquals(0, sent.payload().getInt("idx"));
        assertTrue(sent.payload().getBoolean("primedTop"));
        assertEquals("Ifrit", sent.payload().getString("card"));
    }

    @Test
    void aBreakZoneAbilityIsSentWithItsPlaceInTheBreakZone() throws InterruptedException {
        MainWindow mw = sendingWindow();
        mw.desyncReported = true;
        CardData sage = abilityCard("Sage", "Backup",
                "《Fire》: " + BOOST + " You can only use this ability if Sage is in the Break Zone.");
        assertNotNull(sage.actionAbilities().get(0).breakZoneOnly(), "the text has to parse as a Break Zone ability");
        mw.gameState.getP1BreakZone().add(forward("Filler"));
        mw.gameState.getP1BreakZone().add(sage);

        mw.sendAbilityActivation(sage.actionAbilities().get(0), sage, AbilityPayment.none());

        GameAction sent = next();
        assertEquals("BREAK_ZONE", sent.payload().getString("zone"));
        assertEquals(1, sent.payload().getInt("idx"));
        assertFalse(sent.payload().getBoolean("opponentsCard"));
    }

    @Test
    void aPickFromTheLocalPlayersHandIsSentAsHandIndices() throws InterruptedException {
        // A discard, a card to play, a card to put under the deck: the other client is resolving
        // the same effect and waiting on which card left.
        MainWindow mw = sendingWindow();

        List<Integer> picked = mw.selectOwnHandCards(true, List.of(0, 1, 2), 1, "Waiting...",
                () -> List.of(2), List::of);

        assertEquals(List.of(2), picked);
        GameAction sent = next();
        assertEquals(ChoiceKind.HAND_CARDS.name(), sent.payload().getString("kind"));
        assertEquals(List.of(2), indicesOf(sent), "hand order is shared, so nothing is packed or flipped");
    }

    @Test
    void aYouMayTheLocalPlayerAnswersIsSent() throws InterruptedException {
        MainWindow mw = sendingWindow();

        assertFalse(mw.decideYesNo(true, "Waiting...", () -> false, () -> true));

        GameAction sent = next();
        assertEquals(ChoiceKind.MAY.name(), sent.payload().getString("kind"));
        assertEquals(List.of(0), indicesOf(sent), "a decline travels too — the far client is waiting on it");
    }

    @Test
    void anOptionTheLocalPlayerPicksIsSentAsItsPosition() throws InterruptedException {
        MainWindow mw = sendingWindow();

        assertEquals(2, mw.decideOption(true, 4, "Waiting...", () -> 2, () -> 0));

        GameAction sent = next();
        assertEquals(ChoiceKind.OPTION.name(), sent.payload().getString("kind"));
        assertEquals(List.of(2), indicesOf(sent));
    }

    @Test
    void aDismissedOptionIsSentAsNoAnswer() throws InterruptedException {
        // -1 is the dialog's "closed", and the far client has to learn the effect went nowhere.
        MainWindow mw = sendingWindow();

        assertEquals(-1, mw.decideOption(true, 4, "Waiting...", () -> -1, () -> 0));

        assertEquals(List.of(), indicesOf(next()));
    }

    @Test
    void aBorrowedCastIsSentFromWhereTheCardStoodBeforeItMoved() throws InterruptedException {
        // A card cast "as though you owned it" out of the opponent's Break Zone used to send
        // nothing, so the far client never saw it leave their Break Zone or arrive on this field.
        MainWindow mw = sendingWindow();
        mw.gameState.getP2BreakZone().add(forward("Filler"));
        CardData borrowed = forward("Borrowed");
        mw.gameState.getIdentity().put(borrowed, false);
        mw.gameState.getP2BreakZone().add(borrowed);
        mw.registerBorrowedPlayable(true, borrowed, PlayableEntry.bzThisTurn(0));

        mw.executePlayFromBzP1(borrowed, List.of(), List.of(), Map.of());

        GameAction sent = next();
        assertEquals(ActionType.BORROWED_PLAY, sent.type());
        assertEquals("BREAK_ZONE", sent.payload().getString("zone"));
        assertFalse(sent.payload().getBoolean("own"), "their Break Zone, from this seat");
        assertEquals(1, sent.payload().getInt("idx"), "where it stood before the cast took it");
        assertEquals("Borrowed", sent.payload().getString("card"));
        assertTrue(mw.p1ForwardCards.contains(borrowed), "and the cast still happened here");
    }

    @Test
    void passingOnTheOpponentsStackEntryTellsThem() throws InterruptedException {
        // Their client is waiting on exactly this; a pass that stayed here would leave their
        // entry unresolved forever, which is what the old local countdown amounted to.
        MainWindow mw = sendingWindow();
        CardData theirs = forward("Theirs");
        mw.gameState.getIdentity().put(theirs, false);
        mw.gameState.pushStack(new StackEntry(theirs, false, true));

        mw.passStackPriority();

        GameAction sent = next();
        assertEquals(ChoiceKind.STACK_PASS.name(), sent.payload().getString("kind"));
        assertEquals(List.of(), indicesOf(sent));
    }

    /** Seats a Forward on the opponent's field, owned by them, so breaking it can find an owner. */
    private static void seatP2Forward(MainWindow mw, CardData card) {
        mw.gameState.getIdentity().put(card, false);
        mw.placeP2CardInForwardZone(card);
    }

    /** A main window whose sends leave over {@code host}, so this test's inbox receives them. */
    private MainWindow sendingWindow() {
        MainWindow mw = new MainWindow();
        mw.opponent = new RemoteOpponent(mw, host,
                new MatchSetup(1, List.of("h1"), "Deck", "Host", 7L, true, true));
        return mw;
    }

    private static CardData forward(String name) {
        return new CardData(null, name, "Fire", 3, 7000, "Forward", false, 0, false, false,
                Set.of(), 0, List.of(), null, List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(),
                false, false, null, false, false, false, false, false, 1,
                null, null, null, "");
    }

    /** A Summon that picks a target on its way to the Stack, which is what makes the point. */
    private static CardData summonChoosingAForward() {
        String text = "Choose 1 Forward. Deal it 7000 damage.";
        return new CardData(null, "Firaga", "Fire", 2, 0, "Summon", false, 0, false, false,
                Set.of(), 0, List.of(), null, List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(),
                false, false, null, false, false, false, false, false, 1,
                null, null, null, text);
    }

    @Test
    void aCardNameWithAwkwardCharactersDoesNotBreakTheFraming() throws InterruptedException {
        // Actions carry card names for the receiver to check indices against, and the transport is
        // newline-delimited: a name holding a newline or a quote must not split one action in two.
        String awkward = "Cid \"the \nEngineer\" (VII)\r\n— Multi-Element";
        host.send(GameAction.of(shufflingway.net.ActionType.PLAY_CARD,
                new org.json.JSONObject().put("card", awkward).put("handIdx", 3)));

        GameAction arrived = next();
        assertEquals(awkward, arrived.payload().getString("card"),
                "JSON escaping is what keeps a newline inside a value from ending the line");
        assertEquals(3, arrived.payload().getInt("handIdx"));
        assertTrue(inbox.isEmpty(), "one action was sent, so exactly one should have arrived");
    }

    // =========================================================================================
    // The block step always answers the attacker.
    //
    // The attacking client parks on a BLOCK from the moment it declares, so every way out of the
    // defender's block step has to send one — including the step that never opens because nothing
    // can block, which used to send nothing and left the attacker waiting for good. And "nothing
    // can block" has to mean nothing: a Monster or Backup an effect has made a Forward blocks like
    // one, but only the Forward row was counted, so with one of those as the only blocker the step
    // was skipped and the defender lost a legal block.
    // =========================================================================================

    /**
     * A window defending on P2's turn, with a deck to take damage from and the opponent's half of
     * both combat priority rounds already delivered, so a whole combat runs without parking.
     */
    private MainWindow defendingWindow() {
        MainWindow mw = sendingWindow();
        List<CardData> deck = new ArrayList<>();
        for (int i = 0; i < 10; i++) deck.add(forward("Filler " + i));
        mw.gameState.initializeDeck(deck, List.of(), new Random(1));
        mw.gameState.startFirstTurn(GameState.Player.P2);
        mw.gameState.advancePhase();   // DRAW
        mw.gameState.advancePhase();   // MAIN_1
        mw.gameState.advancePhase();   // ATTACK
        RemoteOpponent remote = (RemoteOpponent) mw.opponent;
        for (int i = 0; i < 2; i++)
            remote.onActionReceived(RemoteOpponent.choiceAction(ChoiceKind.PRIORITY_PASS, List.of()));
        return mw;
    }

    /** Seats {@code monster} as P1's first Monster, active and made a Forward by an effect. */
    private static void seatP1MonsterAsForward(MainWindow mw, CardData monster) {
        mw.placeCardInMonsterZone(monster);
        mw.p1MonsterStates.set(0, CardState.ACTIVE);
        mw.p1MonsterTempForwardPower.put(monster, 7000);
    }

    /**
     * Every BLOCK this window has sent so far. The connection keeps order, so a marker sent behind
     * them bounds the read — which is what lets a test say none went out, not just that none came.
     */
    private List<GameAction> blocksSent() throws InterruptedException {
        host.send(GameAction.of(ActionType.CHAT, new JSONObject().put("msg", "marker")));
        List<GameAction> blocks = new ArrayList<>();
        for (GameAction a = next(); a.type() != ActionType.CHAT; a = next())
            if (a.type() == ActionType.BLOCK) blocks.add(a);
        return blocks;
    }

    private static GameAction onlyOne(List<GameAction> blocks) {
        assertEquals(1, blocks.size(), "exactly one answer per attack: " + blocks);
        return blocks.get(0);
    }

    @Test
    void aBlockStepWithNothingToBlockStillAnswersTheAttacker() throws InterruptedException {
        MainWindow mw = defendingWindow();
        CardData attacker = forward("Attacker");
        seatP2Forward(mw, attacker);
        CardData tired = forward("Tired");
        mw.placeCardInForwardZone(tired);
        mw.p1ForwardStates.set(0, CardState.DULL);

        mw.initP1BlockDeclaration(attacker, 0, () -> {});

        assertFalse(onlyOne(blocksSent()).payload().getBoolean("blocked"),
                "there was no choice to make, but the attacker is still waiting on the answer");
        assertNull(mw.pendingP2Attacker, "and no block step was left open here");
    }

    @Test
    void aPartyWithNothingToBlockItStillAnswersTheAttacker() throws InterruptedException {
        MainWindow mw = defendingWindow();
        seatP2Forward(mw, forward("Left"));
        seatP2Forward(mw, forward("Right"));

        mw.initP1BlockDeclarationVsParty(List.of(0, 1), 14000, () -> {});

        assertFalse(onlyOne(blocksSent()).payload().getBoolean("blocked"));
        assertNull(mw.pendingP2PartyIndices);
    }

    @Test
    void aMonsterActingAsAForwardOpensTheBlockStepAndItsBlockCrosses() throws InterruptedException {
        MainWindow mw = defendingWindow();
        CardData attacker = forward("Attacker");
        seatP2Forward(mw, attacker);
        seatP1MonsterAsForward(mw, abilityCard("Chocobo", "Monster", ""));

        mw.initP1BlockDeclaration(attacker, 0, () -> {});

        assertTrue(blocksSent().isEmpty(),
                "the Monster is the only blocker, so the step waits on a choice rather than declining");
        assertTrue(mw.isMonsterBlockSelectable(0), "and the Monster is offered as that choice");

        mw.p1BlockerMonsterIdx = 0;
        mw.handleP1BlockAction();

        GameAction block = onlyOne(blocksSent());
        assertTrue(block.payload().getBoolean("blocked"));
        assertEquals("MONSTER", block.payload().getString("zone"));
        assertEquals(0, block.payload().getInt("idx"));
    }

    @Test
    void aBackupActingAsAForwardOpensTheBlockStepAndItsBlockCrosses() throws InterruptedException {
        MainWindow mw = defendingWindow();
        CardData attacker = forward("Attacker");
        seatP2Forward(mw, attacker);
        CardData backup = abilityCard("Scholar", "Backup", "");
        mw.gameState.getIdentity().put(backup, true);
        mw.p1BackupCards[2]  = backup;
        mw.p1BackupStates[2] = CardState.ACTIVE;
        mw.p1BackupTempForwardPower.put(backup, 7000);

        mw.initP1BlockDeclaration(attacker, 0, () -> {});

        assertTrue(blocksSent().isEmpty(), "the Backup is a legal blocker, so nothing is declined for it");
        assertTrue(mw.isBackupBlockSelectable(2));

        mw.p1BlockerBackupIdx = 2;
        mw.handleP1BlockAction();

        GameAction block = onlyOne(blocksSent());
        assertTrue(block.payload().getBoolean("blocked"));
        assertEquals("BACKUP", block.payload().getString("zone"));
        assertEquals(2, block.payload().getInt("idx"));
    }

    @Test
    void aMonsterActingAsAForwardOpensTheBlockStepAgainstAParty() throws InterruptedException {
        MainWindow mw = defendingWindow();
        seatP2Forward(mw, forward("Left"));
        seatP2Forward(mw, forward("Right"));
        seatP1MonsterAsForward(mw, abilityCard("Chocobo", "Monster", ""));

        mw.initP1BlockDeclarationVsParty(List.of(0, 1), 14000, () -> {});

        assertTrue(blocksSent().isEmpty());
        assertEquals(List.of(0, 1), mw.pendingP2PartyIndices, "the party block step is open");
        assertTrue(mw.isMonsterBlockSelectable(0));
    }

    @Test
    void aMonsterThatIsNotAForwardIsNoBlocker() throws InterruptedException {
        MainWindow mw = defendingWindow();
        CardData attacker = forward("Attacker");
        seatP2Forward(mw, attacker);
        CardData monster = abilityCard("Chocobo", "Monster", "");
        mw.placeCardInMonsterZone(monster);
        mw.p1MonsterStates.set(0, CardState.ACTIVE);

        mw.initP1BlockDeclaration(attacker, 0, () -> {});

        assertFalse(onlyOne(blocksSent()).payload().getBoolean("blocked"),
                "widening the count to Monsters must not count every Monster");
    }

    @Test
    void aMonsterActingAsAForwardThatCannotBlockIsNoBlocker() throws InterruptedException {
        MainWindow mw = defendingWindow();
        CardData attacker = forward("Attacker");
        seatP2Forward(mw, attacker);
        CardData monster = abilityCard("Chocobo", "Monster", "");
        seatP1MonsterAsForward(mw, monster);
        mw.p1CannotBlock.add(monster);

        mw.initP1BlockDeclaration(attacker, 0, () -> {});

        assertFalse(onlyOne(blocksSent()).payload().getBoolean("blocked"));
    }

    // =========================================================================================
    // Priming used to be local only: the primed Forward and the card it fetched never reached the
    // opponent's board, and their copy of the deck kept the searched card in it.
    // =========================================================================================

    /** A Forward that primes into {@code target} for nothing, so no payment dialog opens. */
    private static CardData primer(String name, String target) {
        return new CardData(null, name, "Fire", 3, 7000, "Forward", false, 0, false, false,
                Set.of(), 0, List.of(), target, List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(),
                false, false, null, false, false, false, false, false, 1,
                null, null, null, "");
    }

    private static final List<String> PRIMING_DECK = List.of("Filler A", "Ifrit", "Filler B", "Filler C", "Filler D");

    /** A receiving window holding the sender's primer and deck on its P2 side, in the same order. */
    private static MainWindow receiverOfPriming() {
        MainWindow rx = new MainWindow();
        seatP2Forward(rx, primer("Primer", "Ifrit"));
        for (String n : PRIMING_DECK) {
            CardData c = forward(n);
            rx.gameState.getIdentity().put(c, false);
            rx.gameState.getP2MainDeck().add(c);
        }
        return rx;
    }

    private static List<String> names(java.util.Collection<CardData> cards) {
        return cards.stream().map(CardData::name).toList();
    }

    @Test
    void aPrimingReachesTheOpponentWithTheCardItFetchedAndTheDeckItLeft() throws InterruptedException {
        MainWindow tx = sendingWindow();
        CardData primer = primer("Primer", "Ifrit");
        tx.gameState.getIdentity().put(primer, true);
        tx.placeCardInForwardZone(primer);
        for (String n : PRIMING_DECK) {
            CardData c = forward(n);
            tx.gameState.getIdentity().put(c, true);
            tx.gameState.getP1MainDeck().add(c);
        }

        // One deck, one stream, on both clients — as a match hands them out.
        tx.p1DeckRandom = new java.util.Random(5);

        tx.priming.executePriming(primer, 0, new ArrayList<>(), new ArrayList<>());
        assertEquals("Ifrit", tx.p1ForwardPrimedTop.get(0).name(), "primed locally, as before");

        GameAction arrived = next();
        assertEquals(ActionType.PRIME, arrived.type());
        assertEquals(1, arrived.payload().getInt("found"), "Ifrit sat second in the deck searched");

        MainWindow rx = receiverOfPriming();
        rx.p2DeckRandom = new java.util.Random(5);
        new RemoteOpponent(rx, null, new MatchSetup(1, List.of("h1"), "Deck", "Host", 7L, false, false))
                .onActionReceived(arrived);

        assertFalse(rx.desyncReported, "the replayed shuffle came out as the sender's did");
        assertEquals("Ifrit", rx.p2ForwardPrimedTop.get(0).name(), "topped on the opponent's board too");
        assertEquals(names(tx.gameState.getP1MainDeck()), names(rx.gameState.getP2MainDeck()),
                "the same deck, in the same order the sender shuffled it into");
        assertEquals(PRIMING_DECK.size() - 1, rx.gameState.getP2MainDeck().size());
    }

    @Test
    void aPrimingThatFindsNothingStillSendsTheShuffledDeck() throws InterruptedException {
        MainWindow tx = sendingWindow();
        CardData primer = primer("Primer", "Shiva");
        tx.gameState.getIdentity().put(primer, true);
        tx.placeCardInForwardZone(primer);
        for (String n : PRIMING_DECK) tx.gameState.getP1MainDeck().add(forward(n));

        tx.priming.executePriming(primer, 0, new ArrayList<>(), new ArrayList<>());

        GameAction arrived = next();
        assertEquals(-1, arrived.payload().getInt("found"));
        assertFalse(arrived.payload().has("chosen"));
        assertEquals(PRIMING_DECK.size(), arrived.payload().getJSONArray("deck").length());
    }

    @Test
    void aPrimingWhoseDeckDoesNotMatchIsRefusedBeforeAnythingMoves() {
        MainWindow rx = receiverOfPriming();
        rx.desyncReported = true;   // keeps the desync dialog from opening under the test
        List<CardData> before = new ArrayList<>(rx.gameState.getP2MainDeck());
        // Their deck, per the sender, is one card longer than the one this client holds.
        List<CardData> theirs = new ArrayList<>(before);
        theirs.add(forward("Filler E"));
        List<CardData> after = new ArrayList<>(theirs);
        after.remove(1);
        GameAction action = RemoteOpponent.primeAction(primer("Primer", "Ifrit"), 0, List.of(), List.of(),
                theirs, after);

        new RemoteOpponent(rx, null, new MatchSetup(1, List.of("h1"), "Deck", "Host", 7L, false, false))
                .onActionReceived(action);

        assertNull(rx.p2ForwardPrimedTop.get(0));
        assertEquals(names(before), names(rx.gameState.getP2MainDeck()), "nothing moved");
    }

    // =========================================================================================
    // Mid-game shuffles. Both clients run every effect that shuffles a deck, and each shuffled
    // with its own unseeded Random, so the first search put the two copies of a deck in different
    // orders. Each deck now shuffles from one stream both clients hold.
    // =========================================================================================

    private static final List<String> SHUFFLED_DECK =
            List.of("A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L");

    /** Two clients' views of one player's deck: theirs as P1 on {@code owner}, as P2 on {@code other}. */
    private static MainWindow[] bothViewsOfOneDeck(long seed) {
        MainWindow owner = new MainWindow(), other = new MainWindow();
        for (String n : SHUFFLED_DECK) {
            owner.gameState.getP1MainDeck().add(forward(n));
            other.gameState.getP2MainDeck().add(forward(n));
        }
        owner.p1DeckRandom = new java.util.Random(seed);
        other.p2DeckRandom = new java.util.Random(seed);
        return new MainWindow[] { owner, other };
    }

    @Test
    void aDeckShuffledByAnEffectComesOutTheSameOnBothClients() {
        MainWindow[] views = bothViewsOfOneDeck(11);
        // Twice, because a stream that only agreed on the first draw would still split them later.
        for (int round = 0; round < 2; round++) {
            views[0].buildGameContext(true).shuffleDeck();
            views[1].buildGameContext(false).shuffleDeck();
            assertEquals(names(views[0].gameState.getP1MainDeck()), names(views[1].gameState.getP2MainDeck()));
        }
        assertNotEquals(SHUFFLED_DECK, names(views[0].gameState.getP1MainDeck()), "and it did shuffle");
    }

    @Test
    void revealedCardsShuffledToTheBottomLandTheSameOnBothClients() {
        MainWindow[] views = bothViewsOfOneDeck(12);
        views[0].buildGameContext(true).revealTopNCountJobPlaceAllAtBottom(5, "Warrior");
        views[1].buildGameContext(false).revealTopNCountJobPlaceAllAtBottom(5, "Warrior");
        assertEquals(names(views[0].gameState.getP1MainDeck()), names(views[1].gameState.getP2MainDeck()));
    }

    @Test
    void aSearchThatFindsNothingShufflesTheSameOnBothClients() {
        // MainWindow.shuffleDeck, the primitive the deck searches call once they are done.
        MainWindow[] views = bothViewsOfOneDeck(13);
        views[0].shuffleDeck(true);
        views[1].shuffleDeck(false);
        assertEquals(names(views[0].gameState.getP1MainDeck()), names(views[1].gameState.getP2MainDeck()));
    }
}
