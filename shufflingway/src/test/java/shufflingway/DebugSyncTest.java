package shufflingway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import shufflingway.net.ActionType;
import shufflingway.net.GameAction;
import shufflingway.net.MatchSetup;

/**
 * Debug-menu changes in a multiplayer game: applied on the client that made them, sent as a
 * DEBUG action, and applied on the other client with the sides swapped, so the two boards stay
 * mirror images of each other.
 *
 * <p>Each test seats a host and a joiner window on the same board — a Forward that is the host's
 * P1 card and the joiner's P2 card — applies an op on the host as its menu would, delivers the
 * same op to the joiner through {@link RemoteOpponent}, and compares. Ops that name a card by
 * serial need the card database and are left to the manual two-window check.
 */
class DebugSyncTest {

    private static CardData forward(String name) {
        return new CardData(null, name, "Fire", 3, 7000, "Forward", false, 0, false, false,
                Set.of(), 0, List.of(), null, List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(),
                false, false, null, false, false, false, false, false, 1,
                null, null, null, "");
    }

    private static MatchSetup joinerSetup(boolean debug) {
        return new MatchSetup(2, List.of("h1"), "Host Deck", "Host", 1L, false, true, debug);
    }

    /** Host and joiner windows showing the same Forward: P1's on the host, P2's on the joiner. */
    private static MainWindow[] seats() {
        MainWindow host   = new MainWindow();
        MainWindow joiner = new MainWindow();
        host.placeCardInForwardZone(forward("Attacker"));
        joiner.placeP2CardInForwardZone(forward("Attacker"));
        return new MainWindow[] { host, joiner };
    }

    /** Applies {@code op} on the host as its Debug menu does, then delivers it to the joiner. */
    private static void sync(MainWindow host, MainWindow joiner, JSONObject op, boolean debugAllowed) {
        assertNull(host.debugUtility.applyOp(op, false), "the op applies on the client that made it");
        new RemoteOpponent(joiner, null, joinerSetup(debugAllowed))
                .onActionReceived(GameAction.of(ActionType.DEBUG, op));
    }

    private static JSONObject hostForwardOp(String kind) {
        return new JSONObject().put("op", kind).put("p1", true)
                .put("zone", "FORWARD").put("index", 0).put("card", "Attacker");
    }

    private static void assertBoardsAgree(MainWindow host, MainWindow joiner) {
        assertEquals(MatchChecksum.ofCombat(host, true, 1), MatchChecksum.ofCombat(joiner, false, 1),
                "the two boards still digest identically");
    }

    @Test
    void aDullOnTheHostsCardDullsTheSameCardOnTheJoinersOpponentSide() {
        MainWindow[] s = seats();
        sync(s[0], s[1], hostForwardOp("state").put("dull", true), true);

        assertEquals(CardState.DULL, s[0].p1ForwardStates.get(0));
        assertEquals(CardState.DULL, s[1].p2ForwardStates.get(0), "the host's P1 is the joiner's P2");
        assertBoardsAgree(s[0], s[1]);
    }

    @Test
    void aCounterLandsOnTheSameCardOnBothBoards() {
        MainWindow[] s = seats();
        sync(s[0], s[1], hostForwardOp("counter").put("name", "EXP").put("add", true), true);

        assertEquals(1, s[0].gameState.getCounters(s[0].p1ForwardCards.get(0), "EXP"));
        assertEquals(1, s[1].gameState.getCounters(s[1].p2ForwardCards.get(0), "EXP"));
    }

    @Test
    void aBreakEmptiesTheSameSlotOnBothBoards() {
        MainWindow[] s = seats();
        s[0].gameState.getIdentity().put(s[0].p1ForwardCards.get(0), true);
        s[1].gameState.getIdentity().put(s[1].p2ForwardCards.get(0), false);
        sync(s[0], s[1], hostForwardOp("break"), true);

        assertTrue(s[0].p1ForwardCards.isEmpty());
        assertTrue(s[1].p2ForwardCards.isEmpty());
        assertEquals(1, s[0].gameState.getP1BreakZone().size());
        assertEquals(1, s[1].gameState.getP2BreakZone().size(), "into the owner's Break Zone on both");
    }

    @Test
    void crystalAndDamageTargetsAreSwappedOnArrival() {
        MainWindow[] s = seats();
        JSONObject op = new JSONObject().put("op", "damage")
                .put("p1Damage", 0).put("p2Damage", 0)
                .put("p1Crystals", 3).put("p2Crystals", 1)
                .put("serial", "1-001H");   // only read when damage goes up
        sync(s[0], s[1], op, true);

        assertEquals(3, s[0].gameState.getP1Crystals());
        assertEquals(1, s[0].gameState.getP2Crystals());
        assertEquals(1, s[1].gameState.getP1Crystals(), "the joiner's own count is the host's P2 value");
        assertEquals(3, s[1].gameState.getP2Crystals());
    }

    @Test
    void anOpNamingACardThatIsNotThereChangesNothingAndIsADesync() {
        MainWindow[] s = seats();
        s[1].desyncReported = true;   // the report's dialog would hold a headless run open
        s[1].p2ForwardCards.set(0, forward("Someone Else"));

        sync(s[0], s[1], hostForwardOp("state").put("dull", true), true);

        assertEquals(CardState.ACTIVE, s[1].p2ForwardStates.get(0),
                "a board that has already drifted is not edited in the wrong place");
    }

    @Test
    void aDebugChangeInAGameWithDebuggingOffIsRefused() {
        MainWindow[] s = seats();
        s[1].desyncReported = true;

        sync(s[0], s[1], hostForwardOp("state").put("dull", true), false);

        assertEquals(CardState.ACTIVE, s[1].p2ForwardStates.get(0),
                "the host left debugging off, so the joiner does not take the change");
    }
}
