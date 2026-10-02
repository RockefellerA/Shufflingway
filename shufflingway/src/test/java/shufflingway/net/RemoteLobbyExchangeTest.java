package shufflingway.net;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/** Drives {@link RemoteLobbyExchange} against a scripted server over loopback. */
class RemoteLobbyExchangeTest {

    private ServerSocket listener;
    private GameConnection client;
    private GameConnection server;

    @BeforeEach
    void connect() throws IOException {
        listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        Socket c = new Socket(InetAddress.getLoopbackAddress(), listener.getLocalPort());
        client = new GameConnection(c);
        server = new GameConnection(listener.accept());
    }

    @AfterEach
    void close() throws IOException {
        client.close();
        server.close();
        listener.close();
    }

    private static GameAction opponentDeck() {
        return GameAction.of(ActionType.DECK_LIST, new JSONObject()
                .put("deckName", "Wind Haste")
                .put("username", "Zidane")
                .put("serials", new JSONArray(List.of("1-001H", "1-001H", "2-002R"))));
    }

    private static GameAction gameSetup(String seat) {
        JSONObject p = new JSONObject()
                .put("seed", 42L)
                .put("hostGoesFirst", true)
                .put("debug", false)
                .put("banlist", true);
        if (seat != null) p.put("seat", seat);
        return GameAction.of(ActionType.GAME_SETUP, p);
    }

    @Test
    void welcomeAcceptsHelloAndSurfacesARejectionsReason() throws IOException {
        server.send(GameAction.of(ActionType.HELLO));
        RemoteLobbyExchange.awaitWelcome(client);

        server.send(GameAction.of(ActionType.DISCONNECT, new JSONObject().put("reason", "Version mismatch")));
        IOException ex = assertThrows(IOException.class, () -> RemoteLobbyExchange.awaitWelcome(client));
        assertEquals("Version mismatch", ex.getMessage());
    }

    @Test
    void matchCarriesOpponentDeckAndTheSeatTheServerAssigned() throws IOException {
        List<LobbyExchange.LobbySettings> seen = new ArrayList<>();
        server.send(LobbyExchange.lobbySettingsAction(new LobbyExchange.LobbySettings(false, false, 0)));
        server.send(GameAction.of(ActionType.PING));
        server.send(LobbyExchange.lobbySettingsAction(new LobbyExchange.LobbySettings(false, true, 1)));
        server.send(opponentDeck());
        server.send(gameSetup("joiner"));

        RemoteLobbyExchange.RemoteMatch match = RemoteLobbyExchange.awaitMatch(client, seen::add);
        assertEquals(List.of(new LobbyExchange.LobbySettings(false, false, 0),
                new LobbyExchange.LobbySettings(false, true, 1)), seen);

        MatchSetup setup = match.toSetup(7);
        assertEquals(7, setup.localDeckId());
        assertEquals(List.of("1-001H", "1-001H", "2-002R"), setup.remoteSerials());
        assertEquals("Wind Haste", setup.remoteDeckName());
        assertEquals("Zidane", setup.remoteUsername());
        assertFalse(setup.localIsHost());
        assertFalse(setup.localGoesFirst());
        assertTrue(setup.banlistEnabled());
        // The joiner seat shuffles its own deck from the joiner stream, as on LAN.
        assertEquals(new Random(43).nextLong(), setup.localDeckRandom().nextLong());
    }

    @Test
    void hostSeatIsOnlyEverGrantedExplicitly() throws IOException {
        server.send(opponentDeck());
        server.send(gameSetup("host"));
        assertTrue(RemoteLobbyExchange.awaitMatch(client, s -> {}).toSetup(1).localIsHost());

        server.send(opponentDeck());
        server.send(gameSetup(null));
        assertFalse(RemoteLobbyExchange.awaitMatch(client, s -> {}).toSetup(1).localIsHost());
    }

    @Test
    void setupWithoutAnOpponentsDeckIsRefused() {
        server.send(gameSetup("host"));
        assertThrows(IOException.class, () -> RemoteLobbyExchange.awaitMatch(client, s -> {}));
    }

    @Test
    void serverClosingTheLobbyEndsTheWaitWithItsReason() {
        server.send(GameAction.of(ActionType.DISCONNECT, new JSONObject().put("reason", "Lobby expired")));
        IOException ex = assertThrows(IOException.class, () -> RemoteLobbyExchange.awaitMatch(client, s -> {}));
        assertEquals("Lobby expired", ex.getMessage());
    }
}
