package shufflingway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import shufflingway.net.MatchSetup;

/**
 * The host's "Enable Debugging" option on its way through the lobby: the LOBBY_SETTINGS updates
 * the joiner shows before Start, and the final value GAME_SETUP carries into the match.
 *
 * <p>Loopback on an ephemeral port, read with {@code receiveSync} as the lobby reads it, before
 * any reader thread is started.
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

    private static GameAction deckList() {
        return GameAction.of(ActionType.DECK_LIST, new JSONObject()
                .put("deckName", "Host deck")
                .put("username", "Host")
                .put("serials", new JSONArray(List.of("1-001H"))));
    }

    @Test
    void theJoinerSeesEachDebugSettingBeforeTheDeckList() throws IOException {
        host.send(LobbyExchange.lobbySettingsAction(false));   // on connect
        host.send(LobbyExchange.lobbySettingsAction(true));    // the host ticks the box
        host.send(deckList());                                 // and presses Start

        List<Boolean> seen = new ArrayList<>();
        LobbyExchange.RemoteDeck deck = LobbyExchange.awaitDeckList(joiner, seen::add);

        assertEquals(List.of(false, true), seen, "each setting reaches the joiner, in order");
        assertEquals(List.of("1-001H"), deck.serials(), "and the deck list is still read after them");
    }

    @Test
    void gameSetupCarriesTheFinalDebugSettingPastALateUpdate() throws IOException {
        host.send(LobbyExchange.lobbySettingsAction(false));   // still in flight when Start is pressed
        LobbyExchange.sendGameSetup(host, true, true);

        GameAction setup = LobbyExchange.awaitGameSetup(joiner);
        assertEquals(ActionType.GAME_SETUP, setup.type());
        assertTrue(setup.payload().getBoolean("debug"), "GAME_SETUP is the final word");
        assertTrue(setup.payload().getBoolean("hostGoesFirst"));
    }

    @Test
    void aMatchSetupWithoutTheFlagRunsWithDebuggingOff() {
        MatchSetup setup = new MatchSetup(1, List.of("h1"), "Deck", "Host", 7L, true, true);
        assertFalse(setup.debugEnabled(), "unchecked is the lobby's default");
    }
}
