package shufflingway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import shufflingway.net.ActionType;
import shufflingway.net.GameAction;
import shufflingway.net.GameConnection;
import shufflingway.net.LobbyExchange;
import shufflingway.net.LobbyExchange.LobbySettings;
import shufflingway.net.MatchSetup;

/**
 * The lobby on the wire: the host's options (debugging, the Standard banlist) as LOBBY_SETTINGS
 * the joiner sees before Start, the joiner's LOBBY_READY back, the deck swap Start sets off,
 * and the final values GAME_SETUP carries into the match.
 *
 * <p>Loopback on an ephemeral port, read with {@code receiveSync} as the lobby reads it, before
 * any reader thread is started. Each side's replies wait in the socket buffer, so one test
 * thread can play both ends in turn.
 */
class LobbyExchangeTest {

    private ServerSocket   listener;
    private GameConnection host, joiner;

    @BeforeEach
    void connect() throws IOException {
        listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        Socket joinerSide = new Socket(InetAddress.getLoopbackAddress(), listener.getLocalPort());
        Socket hostSide   = listener.accept();
        host   = new GameConnection(hostSide);
        joiner = new GameConnection(joinerSide);
    }

    @AfterEach
    void disconnect() throws IOException {
        if (host     != null) host.close();
        if (joiner   != null) joiner.close();
        if (listener != null) listener.close();
    }

    private static GameAction deckList(String name, String serial) {
        return GameAction.of(ActionType.DECK_LIST, new JSONObject()
                .put("deckName", name)
                .put("username", name)
                .put("serials", new JSONArray(List.of(serial))));
    }

    private static GameAction settings(boolean debug, boolean banlist, int resets) {
        return LobbyExchange.lobbySettingsAction(new LobbySettings(debug, banlist, resets));
    }

    @Test
    void theJoinerSeesEachSettingBeforeStartThenAnswersWithItsDeck() throws IOException {
        host.send(settings(false, false, 0));        // on connect
        host.send(settings(true, false, 0));         // the host ticks Enable Debugging
        host.send(settings(true, true, 1));          // and Enable Standard Banlist
        host.send(deckList("Host deck", "1-001H"));  // and presses Start

        List<LobbySettings> seen = new ArrayList<>();
        LobbyExchange.RemoteDeck hostDeck = LobbyExchange.joinerAwaitStart(joiner, seen::add,
                () -> deckList("Joiner deck", "2-002H"));

        assertEquals(List.of(new LobbySettings(false, false, 0), new LobbySettings(true, false, 0),
                new LobbySettings(true, true, 1)), seen, "each setting reaches the joiner, in order");
        assertEquals(List.of("1-001H"), hostDeck.serials(), "and Start still delivers the host's deck");

        LobbyExchange.RemoteDeck joinerDeck = LobbyExchange.hostAwaitJoinerDeck(host, (r, ready) -> {});
        assertEquals(List.of("2-002H"), joinerDeck.serials(), "the joiner's answer is its deck");
    }

    @Test
    void aJoinerWithNoDeckDeclinesStartAndTheLobbyGoesOn() throws IOException {
        host.send(settings(false, true, 1));
        host.send(deckList("Host deck", "1-001H"));   // Start, before the joiner has a deck
        host.send(deckList("Host deck", "1-001H"));   // Start again, once it has confirmed one

        List<GameAction> answers = new ArrayList<>(List.of(
                LobbyExchange.lobbyReadyAction(1, false),
                deckList("Joiner deck", "2-002H")));
        LobbyExchange.joinerAwaitStart(joiner, s -> {}, () -> answers.remove(0));

        List<String> readies = new ArrayList<>();
        joiner.send(LobbyExchange.lobbyReadyAction(1, true));   // behind the deck: left for the game's reader
        LobbyExchange.RemoteDeck joinerDeck = LobbyExchange.hostAwaitJoinerDeck(host,
                (resets, ready) -> readies.add(resets + ":" + ready));

        assertEquals(List.of("1:false"), readies, "the decline reaches the host, with its reset count");
        assertEquals(List.of("2-002H"), joinerDeck.serials(), "and the second Start gets the deck");
        assertTrue(answers.isEmpty());
    }

    @Test
    void settingsFromAnOlderHostReadAsBanlistOff() {
        GameAction old = GameAction.of(ActionType.LOBBY_SETTINGS, new JSONObject().put("debug", true));
        assertEquals(new LobbySettings(true, false, 0), LobbyExchange.settingsOf(old));
    }

    @Test
    void gameSetupCarriesTheFinalDebugSettingPastALateUpdate() throws IOException {
        host.send(settings(false, false, 0));   // still in flight when Start is pressed
        LobbyExchange.sendGameSetup(host, true, true, true, DeckFormat.L3);

        GameAction setup = LobbyExchange.awaitGameSetup(joiner);
        assertEquals(ActionType.GAME_SETUP, setup.type());
        assertTrue(setup.payload().getBoolean("debug"), "GAME_SETUP is the final word");
        assertTrue(setup.payload().getBoolean("banlist"), "and carries the banlist into the match");
        assertEquals(DeckFormat.L3, LobbyExchange.formatOf(setup.payload()), "and the format");
        assertTrue(setup.payload().getBoolean("hostGoesFirst"));
    }

    @Test
    void settingsCarryTheFormat() {
        LobbySettings sent = new LobbySettings(false, true, 2, DeckFormat.L6);
        assertEquals(sent, LobbyExchange.settingsOf(LobbyExchange.lobbySettingsAction(sent)));
    }

    @Test
    void settingsFromAnOlderHostReadAsStandard() {
        GameAction old = GameAction.of(ActionType.LOBBY_SETTINGS, new JSONObject().put("banlist", true));
        assertEquals(DeckFormat.STANDARD, LobbyExchange.settingsOf(old).format());
    }

    @Test
    void aMatchSetupWithoutTheFlagRunsWithDebuggingOff() {
        MatchSetup setup = new MatchSetup(1, List.of("h1"), "Deck", "Host", 7L, true, true);
        assertFalse(setup.debugEnabled(), "unchecked is the lobby's default");
    }

    // ---------------------------------------------------------------------------------------------
    // Counter colors travel with the deck list, beside the username
    // ---------------------------------------------------------------------------------------------

    private static GameAction deckListWithColor(Object counterColor) {
        GameAction deck = deckList("Host deck", "1-001H");
        deck.payload().put("counterColor", counterColor);
        return deck;
    }

    @Test
    void theDeckListCarriesTheSendersCounterColor() throws IOException {
        LobbyExchange.RemoteDeck deck = LobbyExchange.remoteDeckOf(deckListWithColor("#3060E0"));
        assertEquals("#3060e0", deck.counterColor());
    }

    @Test
    void aDeckListFromAnOlderClientCarriesNoCounterColor() throws IOException {
        assertNull(LobbyExchange.remoteDeckOf(deckList("Host deck", "1-001H")).counterColor());
    }

    @Test
    void aMalformedCounterColorIsDroppedRatherThanDrawn() throws IOException {
        assertNull(LobbyExchange.remoteDeckOf(deckListWithColor("url(evil)")).counterColor());
        assertNull(LobbyExchange.remoteDeckOf(deckListWithColor(42)).counterColor());
    }

    @Test
    void theCounterColorSurvivesTheDeckSwapIntoTheMatch() throws IOException {
        host.send(settings(false, false, 0));
        host.send(deckListWithColor("#3060e0"));
        LobbyExchange.RemoteDeck hostDeck = LobbyExchange.joinerAwaitStart(joiner, s -> {},
                () -> deckList("Joiner deck", "2-002H"));

        MatchSetup setup = new MatchSetup(2, hostDeck.serials(), hostDeck.name(), hostDeck.username(),
                7L, false, true, false, false, hostDeck.counterColor());
        assertEquals("#3060e0", setup.remoteCounterColor());
    }
}
