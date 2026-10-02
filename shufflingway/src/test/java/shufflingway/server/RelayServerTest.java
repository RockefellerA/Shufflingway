package shufflingway.server;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import shufflingway.net.ActionType;
import shufflingway.net.GameAction;
import shufflingway.net.GameConnection;
import shufflingway.net.LobbyExchange;
import shufflingway.net.MatchSetup;
import shufflingway.net.RemoteLobbyExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Real sockets against a real {@link RelayServer} on a free loopback port. */
@Timeout(20)
class RelayServerTest {

    private static final String VERSION  = "1.2.3";
    private static final String CHECKSUM = "abc123";

    private RelayServer server;
    private final List<String> log = Collections.synchronizedList(new ArrayList<>());
    private final List<GameConnection> clients = new ArrayList<>();

    @BeforeEach
    void startServer() throws IOException {
        // Every test client shares the loopback address.
        server = new RelayServer(0, 64, log::add);
        server.start();
    }

    @AfterEach
    void stopServer() {
        clients.forEach(GameConnection::close);
        server.close();
    }

    // ---------------------------------------------------------------------------------------------
    // A scripted client
    // ---------------------------------------------------------------------------------------------

    private GameConnection rawConnect() throws IOException {
        Socket s = new Socket(InetAddress.getLoopbackAddress(), server.port());
        s.setSoTimeout(5_000);
        GameConnection c = new GameConnection(s);
        clients.add(c);
        return c;
    }

    private GameConnection connect(String username, String checksum) throws IOException {
        GameConnection c = rawConnect();
        c.send(GameAction.of(ActionType.HELLO, new JSONObject()
                .put("version", VERSION).put("cardChecksum", checksum).put("username", username)));
        RemoteLobbyExchange.awaitWelcome(c);
        return c;
    }

    private GameConnection connect(String username) throws IOException {
        return connect(username, CHECKSUM);
    }

    private static List<RemoteLobbyExchange.LobbyInfo> list(GameConnection c) throws IOException {
        c.send(RemoteLobbyExchange.listAction());
        GameAction reply = c.receiveSync();
        assertEquals(ActionType.LOBBY_LIST, reply.type());
        return RemoteLobbyExchange.lobbiesOf(reply);
    }

    private static GameAction deck(String name, String... serials) {
        return GameAction.of(ActionType.DECK_LIST, new JSONObject()
                .put("deckName", name).put("username", "").put("resets", 0)
                .put("serials", new JSONArray(List.of(serials))));
    }

    private static GameAction chat(String text) {
        return GameAction.of(ActionType.CHAT, new JSONObject().put("message", text));
    }

    private static final RemoteLobbyExchange.LobbyListener IGNORE = new RemoteLobbyExchange.LobbyListener() {
        @Override public void onLobbies(List<RemoteLobbyExchange.LobbyInfo> lobbies) { }
        @Override public void onError(String reason) { fail("unexpected LOBBY_ERROR: " + reason); }
        @Override public void onSettings(LobbyExchange.LobbySettings settings) { }
    };

    /** Creator and joiner, paired in lobby {@code name} and through to the match start. */
    private record Pair(GameConnection host, GameConnection joiner,
                        RemoteLobbyExchange.RemoteMatch hostMatch, RemoteLobbyExchange.RemoteMatch joinerMatch) {}

    private Pair pair(String name) throws IOException {
        GameConnection a = connect("A-" + name);
        a.send(RemoteLobbyExchange.createAction(name, "", false, false));
        assertEquals(ActionType.LOBBY_SETTINGS, a.receiveSync().type());
        GameConnection b = connect("B-" + name);
        b.send(RemoteLobbyExchange.joinAction(name, ""));
        assertEquals(ActionType.LOBBY_SETTINGS, b.receiveSync().type());
        a.send(deck("deck-a", "1-001H"));
        b.send(deck("deck-b", "2-002R"));
        return new Pair(a, b, RemoteLobbyExchange.awaitMatch(a, IGNORE), RemoteLobbyExchange.awaitMatch(b, IGNORE));
    }

    // ---------------------------------------------------------------------------------------------
    // Lobbies
    // ---------------------------------------------------------------------------------------------

    @Test
    void passwordLobbyIsListedUntilSomeoneJoinsItWithTheRightPassword() throws IOException {
        GameConnection alice = connect("Alice");
        alice.send(RemoteLobbyExchange.createAction("Den", "hunter2", true, false));
        assertEquals(new LobbyExchange.LobbySettings(false, true, 0), LobbyExchange.settingsOf(alice.receiveSync()));

        GameConnection bob = connect("Bob");
        assertEquals(List.of(new RemoteLobbyExchange.LobbyInfo("Den", "Alice", true, true, false)), list(bob));

        bob.send(RemoteLobbyExchange.joinAction("Den", "wrong"));
        GameAction refused = bob.receiveSync();
        assertEquals(ActionType.LOBBY_ERROR, refused.type());
        assertEquals("Wrong password", refused.payload().getString("reason"));

        bob.send(RemoteLobbyExchange.joinAction("den", "hunter2"));
        assertEquals(ActionType.LOBBY_SETTINGS, bob.receiveSync().type());

        // Both seats taken: no longer advertised.
        assertEquals(List.of(), list(connect("Carol")));
    }

    @Test
    void lobbyNamesAreUniqueIgnoringCase() throws IOException {
        GameConnection alice = connect("Alice");
        alice.send(RemoteLobbyExchange.createAction("Den", "", false, false));
        alice.receiveSync();
        GameConnection bob = connect("Bob");
        bob.send(RemoteLobbyExchange.createAction("DEN", "", false, false));
        assertEquals(ActionType.LOBBY_ERROR, bob.receiveSync().type());
    }

    @Test
    void lobbiesOnAnotherCardDatabaseAreHiddenAndRefused() throws IOException {
        GameConnection alice = connect("Alice");
        alice.send(RemoteLobbyExchange.createAction("Den", "", false, false));
        alice.receiveSync();

        GameConnection bob = connect("Bob", "different");
        assertEquals(List.of(), list(bob));
        bob.send(RemoteLobbyExchange.joinAction("Den", ""));
        GameAction refused = bob.receiveSync();
        assertEquals(ActionType.LOBBY_ERROR, refused.type());
        assertTrue(refused.payload().getString("reason").contains("Card database"));
    }

    @Test
    void tooManyWrongPasswordsHangsUp() throws IOException {
        GameConnection alice = connect("Alice");
        alice.send(RemoteLobbyExchange.createAction("Den", "pw", false, false));
        alice.receiveSync();

        GameConnection mallory = connect("Mallory");
        for (int i = 1; i < ClientSession.MAX_PASSWORD_FAILURES; i++) {
            mallory.send(RemoteLobbyExchange.joinAction("Den", "guess" + i));
            assertEquals(ActionType.LOBBY_ERROR, mallory.receiveSync().type());
        }
        mallory.send(RemoteLobbyExchange.joinAction("Den", "last guess"));
        assertEquals(ActionType.LOBBY_ERROR, mallory.receiveSync().type());
        assertEquals(ActionType.DISCONNECT, mallory.receiveSync().type());
        assertThrows(IOException.class, mallory::receiveSync);
    }

    @Test
    void joinerLeavingReopensTheLobbyAndCreatorLeavingClosesIt() throws IOException {
        GameConnection alice = connect("Alice");
        alice.send(RemoteLobbyExchange.createAction("Den", "", false, false));
        alice.receiveSync();
        GameConnection bob = connect("Bob");
        bob.send(RemoteLobbyExchange.joinAction("Den", ""));
        bob.receiveSync();

        GameConnection carol = connect("Carol");
        assertEquals(List.of(), list(carol));
        bob.close();
        assertEventually(() -> list(carol).size() == 1);

        carol.send(RemoteLobbyExchange.joinAction("Den", ""));
        carol.receiveSync();
        alice.close();
        GameAction bye = carol.receiveSync();
        assertEquals(ActionType.DISCONNECT, bye.type());
        assertEquals("The lobby's creator left", bye.payload().getString("reason"));
    }

    // ---------------------------------------------------------------------------------------------
    // Matches
    // ---------------------------------------------------------------------------------------------

    @Test
    void matchStartGivesTheCreatorTheHostSeatAndBothTheSameDeal() throws IOException {
        Pair p = pair("Den");
        MatchSetup host   = p.hostMatch().toSetup(1);
        MatchSetup joiner = p.joinerMatch().toSetup(2);

        assertTrue(host.localIsHost());
        assertFalse(joiner.localIsHost());
        assertEquals(host.seed(), joiner.seed());
        assertEquals(host.hostGoesFirst(), joiner.hostGoesFirst());
        assertNotEquals(host.localGoesFirst(), joiner.localGoesFirst());
        assertEquals(List.of("2-002R"), host.remoteSerials());
        assertEquals(List.of("1-001H"), joiner.remoteSerials());
        assertFalse(p.hostMatch().matchId().isEmpty());
        assertEquals(p.hostMatch().matchId(), p.joinerMatch().matchId());
        assertEquals(1, server.registry().matchCount());
        assertEquals(0, server.registry().lobbyCount());
    }

    @Test
    void inGameMessagesPassUnchangedInBothDirections() throws IOException {
        Pair p = pair("Den");
        GameAction keep = GameAction.of(ActionType.KEEP_HAND, new JSONObject().put("order", new JSONArray(List.of(2, 0, 1))));
        p.host().send(keep);
        assertEquals(keep.serialize(), p.joiner().receiveSync().serialize());
        p.joiner().send(chat("gl hf"));
        assertEquals("gl hf", p.host().receiveSync().payload().getString("message"));
    }

    @Test
    void concurrentMatchesNeverCrossTalk() throws IOException {
        List<Pair> pairs = new ArrayList<>();
        for (int i = 0; i < 5; i++) pairs.add(pair("Lobby" + i));
        for (int i = 0; i < pairs.size(); i++) {
            pairs.get(i).host().send(chat("to joiner " + i));
            pairs.get(i).joiner().send(chat("to host " + i));
        }
        for (int i = 0; i < pairs.size(); i++) {
            assertEquals("to joiner " + i, pairs.get(i).joiner().receiveSync().payload().getString("message"));
            assertEquals("to host " + i, pairs.get(i).host().receiveSync().payload().getString("message"));
        }
        assertEquals(5, server.registry().matchCount());
    }

    @Test
    void droppedPlayerIsReportedToTheirOpponent() throws IOException {
        Pair p = pair("Den");
        p.host().close();
        GameAction bye = p.joiner().receiveSync();
        assertEquals(ActionType.DISCONNECT, bye.type());
        assertEquals("Opponent disconnected", bye.payload().getString("reason"));
        assertThrows(IOException.class, p.joiner()::receiveSync);
        assertEquals(0, server.registry().matchCount());
    }

    @Test
    void playersOwnGoodbyeIsRelayedOnceWithItsReason() throws IOException {
        Pair p = pair("Den");
        p.joiner().send(GameAction.of(ActionType.DISCONNECT, new JSONObject().put("reason", "Player left")));
        GameAction bye = p.host().receiveSync();
        assertEquals("Player left", bye.payload().getString("reason"));
        assertThrows(IOException.class, p.host()::receiveSync);
    }

    @Test
    void serverHopMessagesAreNotRelayed() throws IOException {
        Pair p = pair("Den");
        p.host().send(GameAction.of(ActionType.PING));
        p.host().send(RemoteLobbyExchange.listAction());
        p.host().send(chat("only this"));
        assertEquals(ActionType.CHAT, p.joiner().receiveSync().type());
    }

    // ---------------------------------------------------------------------------------------------
    // Hostile input
    // ---------------------------------------------------------------------------------------------

    @Test
    void connectionsPastThePerAddressCapAreRefused() throws IOException {
        try (RelayServer capped = new RelayServer(0, 2, log::add)) {
            capped.start();
            List<Socket> open = new ArrayList<>();
            try {
                for (int i = 0; i < 2; i++) open.add(new Socket(InetAddress.getLoopbackAddress(), capped.port()));
                Socket third = new Socket(InetAddress.getLoopbackAddress(), capped.port());
                open.add(third);
                third.setSoTimeout(5_000);
                GameConnection c = new GameConnection(third);
                GameAction refused = c.receiveSync();
                assertEquals(ActionType.DISCONNECT, refused.type());
                assertEquals("Too many connections from your address", refused.payload().getString("reason"));
            } finally {
                for (Socket s : open) s.close();
            }
        }
    }

    @Test
    void anythingButHelloFirstIsRefused() throws IOException {
        GameConnection c = rawConnect();
        c.send(RemoteLobbyExchange.listAction());
        assertEquals(ActionType.DISCONNECT, c.receiveSync().type());
    }

    @Test
    void anOversizedLineEndsTheConnection() throws IOException {
        Socket s = new Socket(InetAddress.getLoopbackAddress(), server.port());
        s.setSoTimeout(5_000);
        try (s) {
            OutputStream out = s.getOutputStream();
            byte[] junk = "x".repeat(ClientSession.MAX_LINE_BYTES + 1).getBytes(StandardCharsets.UTF_8);
            try {
                out.write(junk);
                out.flush();
            } catch (IOException closedMidWrite) {
                // The server may hang up before the whole line is written; that is the point.
            }
            assertEquals(-1, s.getInputStream().read());
        } catch (IOException reset) {
            // A reset rather than an orderly close also means the server dropped it.
        }
        assertEventually(() -> log.stream().anyMatch(l -> l.contains("size limit")));
    }

    @Test
    void malformedMessageIsHungUpWithoutDisturbingOtherMatches() throws IOException {
        Pair p = pair("Den");

        Socket raw = new Socket(InetAddress.getLoopbackAddress(), server.port());
        raw.setSoTimeout(5_000);
        GameConnection c = new GameConnection(raw);
        clients.add(c);
        c.send(GameAction.of(ActionType.HELLO, new JSONObject()
                .put("version", VERSION).put("cardChecksum", CHECKSUM).put("username", "Eve")));
        RemoteLobbyExchange.awaitWelcome(c);
        raw.getOutputStream().write("{\"type\":\"NO_SUCH_TYPE\"}\nnot json\n".getBytes(StandardCharsets.UTF_8));
        GameAction bye = c.receiveSync();
        assertEquals(ActionType.DISCONNECT, bye.type());
        assertEquals("Malformed message", bye.payload().getString("reason"));

        p.host().send(chat("still fine"));
        assertEquals("still fine", p.joiner().receiveSync().payload().getString("message"));
    }

    // ---------------------------------------------------------------------------------------------

    private interface Check { boolean ok() throws IOException; }

    private static void assertEventually(Check check) throws IOException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            if (check.ok()) return;
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        fail("condition not met within 5s");
    }
}
