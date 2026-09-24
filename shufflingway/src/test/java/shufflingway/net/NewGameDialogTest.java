package shufflingway.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/**
 * File → New Game on an open connection: two {@link NewGameDialog}s, one per client, wired
 * straight to each other so each one's sends are the other's arrivals. The dialogs are never
 * shown; the negotiation is driven through the same entry points the buttons use.
 */
class NewGameDialogTest {

    private static final List<String> HOST_SERIALS   = List.of("1-001H", "1-002R");
    private static final List<String> JOINER_SERIALS = List.of("2-001H", "2-002R");

    private static MatchSetup previous(boolean localIsHost, String opponent, boolean debug) {
        return new MatchSetup(1, List.of("x"), "Old deck", opponent, 42L, localIsHost, true, debug);
    }

    private static GameAction ready(String deckName, String username, List<String> serials) {
        return GameAction.of(ActionType.NEW_GAME_READY, new JSONObject()
                .put("deckName", deckName)
                .put("username", username)
                .put("serials", new JSONArray(serials)));
    }

    /** Both clients' dialogs, delivering to each other, and what each one started. */
    private static final class Pair {
        NewGameDialog host, joiner;
        MatchSetup hostStarted, joinerStarted;

        Pair(boolean debug) {
            host   = new NewGameDialog(null, previous(true, "Joiner", debug), true,
                    a -> joiner.onAction(a), s -> hostStarted = s);
            joiner = new NewGameDialog(null, previous(false, "Host", debug), false,
                    a -> host.onAction(a), s -> joinerStarted = s);
        }
    }

    @Test
    void theHostStartsOnceBothAreReadyAndBothClientsGetTheSameGame() {
        Pair p = new Pair(true);

        p.host.markReady(ready("Host deck", "Host", HOST_SERIALS), 5);
        assertFalse(p.host.readyEnabled(), "the host is waiting on the joiner");

        p.joiner.markReady(ready("Joiner deck", "Joiner", JOINER_SERIALS), 7);
        assertEquals("Start Game", p.host.readyLabel(), "both ready: the host's Ready becomes Start Game");
        assertTrue(p.host.readyEnabled());
        assertEquals("Ready", p.joiner.readyLabel(), "the joiner waits for the host to start");
        assertFalse(p.joiner.readyEnabled());

        p.host.startAsHost();

        MatchSetup h = p.hostStarted, j = p.joinerStarted;
        assertNotNull(h, "the host's new game started");
        assertNotNull(j, "the joiner's new game started from the host's GAME_SETUP");
        assertEquals(5, h.localDeckId());
        assertEquals(7, j.localDeckId());
        assertEquals(JOINER_SERIALS, h.remoteSerials());
        assertEquals(HOST_SERIALS, j.remoteSerials());
        assertEquals(h.seed(), j.seed(), "one seed, so both shuffle the same way");
        assertEquals(h.hostGoesFirst(), j.hostGoesFirst(), "one coin flip");
        assertTrue(h.localIsHost());
        assertFalse(j.localIsHost());
        assertTrue(h.debugEnabled() && j.debugEnabled(), "the new game keeps the debug setting");
    }

    @Test
    void startGameWaitsForTheHostEvenWhenTheJoinerIsReadyFirst() {
        Pair p = new Pair(false);

        p.joiner.markReady(ready("Joiner deck", "Joiner", JOINER_SERIALS), 7);
        assertEquals("Ready", p.host.readyLabel(), "the host has not picked a deck yet");
        assertNull(p.joinerStarted);

        p.host.markReady(ready("Host deck", "Host", HOST_SERIALS), 5);
        assertEquals("Start Game", p.host.readyLabel());
        assertNull(p.hostStarted, "nothing starts until the host clicks Start Game");

        p.host.startAsHost();
        assertNotNull(p.joinerStarted);
        assertFalse(p.joinerStarted.debugEnabled());
    }

    @Test
    void cancellingTellsTheOpponentAndIgnoresAnythingAfter() {
        List<GameAction> sent = new ArrayList<>();
        MatchSetup[] started = { null };
        NewGameDialog dlg = new NewGameDialog(null, previous(true, "Joiner", false), true,
                sent::add, s -> started[0] = s);

        dlg.cancel();
        assertEquals(ActionType.NEW_GAME_CANCEL, sent.get(sent.size() - 1).type());
        assertTrue(dlg.isFinished());

        dlg.onAction(ready("Joiner deck", "Joiner", JOINER_SERIALS));   // crossed the Cancel on the wire
        assertNull(started[0], "a closed negotiation starts nothing");
    }

    @Test
    void messagesOutsideTheNegotiationAreLeftToTheCaller() {
        NewGameDialog dlg = new NewGameDialog(null, previous(true, "Joiner", false), true, a -> { }, s -> { });
        assertFalse(dlg.onAction(GameAction.of(ActionType.ADVANCE_PHASE)));
        assertTrue(dlg.onAction(GameAction.of(ActionType.NEW_GAME_REQUEST)),
                "both asking at once is already handled by the dialog being open");
    }
}
