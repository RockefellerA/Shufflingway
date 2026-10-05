package shufflingway.server;

import org.json.JSONArray;
import org.json.JSONObject;
import shufflingway.DeckFormat;
import shufflingway.net.ActionType;
import shufflingway.net.GameAction;
import shufflingway.net.LobbyExchange;
import shufflingway.net.RemoteLobbyExchange;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One client's connection to the relay server, read on its own thread.
 *
 * <p>Until its match starts the session speaks the lobby protocol (see {@link RemoteLobbyExchange})
 * and asks the {@link LobbyRegistry} for everything. Once paired it is a pipe: each line the client
 * sends is checked to be a well-formed action and written, unchanged, to its opponent. The pairing
 * itself is what keeps games apart — a session only ever writes to the one peer the registry gave
 * it, so nothing a client sends can name, and so reach, another game.
 *
 * <p>The wire is the client's: newline-delimited JSON {@link GameAction}s, UTF-8. Lines are read
 * with a length cap, since an unbounded {@code readLine} lets one client exhaust the server's
 * memory by never sending a newline.
 */
final class ClientSession implements Runnable {

    /** Longest line accepted. A 60-card deck list is under 2 KiB; in-game actions are smaller. */
    static final int MAX_LINE_BYTES = 64 * 1024;
    /** How long a new connection has to say HELLO. */
    static final int HANDSHAKE_TIMEOUT_MS = 15_000;
    /** Wrong lobby passwords tolerated on one connection before it is hung up. */
    static final int MAX_PASSWORD_FAILURES = 5;
    static final int MAX_DECK_SIZE = 200;

    private static final AtomicLong IDS = new AtomicLong();

    private final long id = IDS.incrementAndGet();
    private final Socket socket;
    private final RelayServer server;
    private final LobbyRegistry registry;
    private final InputStream in;
    private final OutputStream out;
    /** Guards {@link #out}; held across a whole match start by {@link #sendToBoth}. */
    private final Object sendLock = new Object();
    private final ByteArrayOutputStream lineBuf = new ByteArrayOutputStream(256);
    /** Size in bytes of the line {@link #readLine} last returned, without its terminator. */
    private int lastLineBytes;

    // Abuse limits (RelayServer.Limits); read on this session's own thread only.
    private final TokenBucket messageBudget;
    private final TokenBucket byteBudget;
    private final long lobbyListIntervalNanos;
    private long lastLobbyListNanos;

    // Set by the handshake, before the session is visible to the registry.
    private String username = "";
    private String version = "";
    private String cardChecksum = "";

    /** The opponent, once the registry pairs this session into a match. */
    private volatile ClientSession peer;
    private volatile boolean hungUp;
    private int passwordFailures;

    ClientSession(Socket socket, RelayServer server) throws IOException {
        this.socket = socket;
        this.server = server;
        this.registry = server.registry();
        this.in = new BufferedInputStream(socket.getInputStream());
        this.out = new BufferedOutputStream(socket.getOutputStream());

        RelayServer.Limits limits = server.limits();
        long now = System.nanoTime();
        this.messageBudget = new TokenBucket(limits.messageBurst(), limits.messagesPerSecond(), now);
        this.byteBudget = new TokenBucket(limits.byteBurst(), limits.bytesPerSecond(), now);
        this.lobbyListIntervalNanos = limits.lobbyListIntervalMs() * 1_000_000L;
        // Backdated by one interval, so the first request is answered at once.
        this.lastLobbyListNanos = now - lobbyListIntervalNanos;
    }

    String username()     { return username; }
    String version()      { return version; }
    String cardChecksum() { return cardChecksum; }
    ClientSession peer()  { return peer; }
    InetAddress address() { return socket.getInetAddress(); }

    void pairWith(ClientSession opponent) {
        peer = opponent;
    }

    /** Called by the registry under its lock, on a wrong lobby password. */
    void passwordFailed() {
        passwordFailures++;
    }

    @Override
    public String toString() {
        return "#" + id + (username.isEmpty() ? "" : " " + username)
                + " (" + socket.getInetAddress().getHostAddress() + ")";
    }

    // ---------------------------------------------------------------------------------------------
    // Reading
    // ---------------------------------------------------------------------------------------------

    @Override
    public void run() {
        boolean saidGoodbye = false;
        String why = "disconnected";
        try {
            socket.setTcpNoDelay(true);
            // Lets the OS notice a peer that vanished without closing (a dropped Wi-Fi link, a
            // sleeping laptop), which would otherwise hold a match open indefinitely.
            socket.setKeepAlive(true);
            socket.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
            if (!handshake()) return;
            socket.setSoTimeout(0);
            server.log(this + " connected (version " + version + ")");

            String line;
            while ((line = readLine()) != null) {
                // Over budget is hung up, not dropped: a dropped move would desync the match, and
                // the opponent is told their game is over either way.
                long now = System.nanoTime();
                if (!messageBudget.tryTake(1, now) || !byteBudget.tryTake(lastLineBytes, now)) {
                    why = "sent messages too fast";
                    hangUp("Sending too fast");
                    break;
                }
                ClientSession p = peer;
                GameAction action = GameAction.deserialize(line);
                if (p != null) {
                    if (action.type() == ActionType.DISCONNECT) {
                        p.sendLine(line);
                        saidGoodbye = true;
                        break;
                    }
                    if (!serverOnly(action.type())) p.sendLine(line);
                } else if (!handleLobby(action)) {
                    break;
                }
            }
        } catch (SocketTimeoutException e) {
            why = "timed out before saying HELLO";
        } catch (IOException e) {
            why = e.getMessage();
        } catch (RuntimeException e) {
            // A malformed line: bad JSON, or a type this server does not know.
            why = "sent a malformed message";
            hangUp("Malformed message");
        } finally {
            registry.leave(this, saidGoodbye);
            // Closing a socket with unread input resets it, and the client could lose the reason a
            // hang-up just sent; a hung-up session is closed after the grace period instead.
            if (!hungUp) close();
            server.ended(this, why);
        }
    }

    /**
     * Types that only ever pass between a client and the server. Dropped rather than relayed: a
     * keep-alive or a lobby-list poll still in flight when the match started means nothing to the
     * opponent, whose game would otherwise have to ignore it.
     */
    private static boolean serverOnly(ActionType type) {
        return switch (type) {
            case HELLO, PING, LOBBY_LIST, LOBBY_CREATE, LOBBY_JOIN, LOBBY_ERROR -> true;
            default -> false;
        };
    }

    /** Reads one line, without its terminator; {@code null} at end of stream. */
    private String readLine() throws IOException {
        lineBuf.reset();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') {
                int n = lineBuf.size();
                byte[] bytes = lineBuf.toByteArray();
                // Clients on Windows end lines with CRLF (PrintWriter.println).
                if (n > 0 && bytes[n - 1] == '\r') n--;
                lastLineBytes = n;
                return new String(bytes, 0, n, StandardCharsets.UTF_8);
            }
            if (lineBuf.size() >= MAX_LINE_BYTES) throw new IOException("sent a message over the size limit");
            lineBuf.write(b);
        }
        return null;
    }

    private boolean handshake() throws IOException {
        String line = readLine();
        if (line == null) return false;
        GameAction hello = GameAction.deserialize(line);
        if (hello.type() != ActionType.HELLO) {
            hangUp("Expected HELLO");
            return false;
        }
        JSONObject p = hello.payload();
        version      = clean(p.optString("version"), 32);
        cardChecksum = clean(p.optString("cardChecksum"), 128);
        username     = clean(p.optString("username"), 8);
        if (version.isEmpty() || cardChecksum.isEmpty()) {
            hangUp("Missing version or card checksum");
            return false;
        }
        send(GameAction.of(ActionType.HELLO, new JSONObject().put("server", "Shufflingway relay")));
        return true;
    }

    /** Handles one action from a client not yet in a match; false ends the session. */
    private boolean handleLobby(GameAction action) {
        JSONObject p = action.payload();
        switch (action.type()) {
            case LOBBY_LIST -> {
                // At most one list per interval. A request in between is ignored rather than
                // refused: the client polls well inside the limit, so only a flood ever hits it, and
                // answering a flood is exactly the outbound traffic it is after.
                long now = System.nanoTime();
                if (now - lastLobbyListNanos < lobbyListIntervalNanos) return true;
                lastLobbyListNanos = now;
                // A poll sent just before joining arrives after; the lobby screen is gone by then.
                if (!registry.inLobby(this)) send(registry.listFor(this));
            }
            case LOBBY_CREATE -> {
                String name = clean(p.optString("name"), Integer.MAX_VALUE);
                String password = p.optString("password");
                if (name.isEmpty() || name.length() > RemoteLobbyExchange.LOBBY_NAME_MAX_LENGTH) {
                    send(error("Lobby names are 1 to " + RemoteLobbyExchange.LOBBY_NAME_MAX_LENGTH + " characters"));
                } else if (password.length() > RemoteLobbyExchange.PASSWORD_MAX_LENGTH) {
                    send(error("Passwords are at most " + RemoteLobbyExchange.PASSWORD_MAX_LENGTH + " characters"));
                } else {
                    // A format the clients cannot play yet is not one a lobby can be opened in.
                    DeckFormat format = LobbyExchange.formatOf(p);
                    if (!format.available()) format = DeckFormat.STANDARD;
                    send(registry.create(this, name, password,
                            p.optBoolean("banlist", false), p.optBoolean("debug", false), format));
                }
            }
            case LOBBY_JOIN -> {
                send(registry.join(this, clean(p.optString("name"), Integer.MAX_VALUE), p.optString("password")));
                if (passwordFailures >= MAX_PASSWORD_FAILURES) {
                    hangUp("Too many wrong passwords");
                    return false;
                }
            }
            case DECK_LIST -> {
                String problem = deckProblem(p);
                if (problem != null) send(error(problem));
                else registry.submitDeck(this, p);
            }
            case PING -> { }
            case DISCONNECT -> { return false; }
            default -> {
                hangUp("Unexpected " + action.type() + " before the match started");
                return false;
            }
        }
        return true;
    }

    /** Why a DECK_LIST payload is unfit to forward, or {@code null}. Card legality is the clients'. */
    private static String deckProblem(JSONObject deck) {
        JSONArray serials = deck.optJSONArray("serials");
        if (serials == null || serials.isEmpty()) return "Your deck is empty";
        if (serials.length() > MAX_DECK_SIZE) return "Your deck is too large";
        for (int i = 0; i < serials.length(); i++) {
            if (!(serials.opt(i) instanceof String s) || s.isEmpty() || s.length() > 32) {
                return "Your deck list is malformed";
            }
        }
        return null;
    }

    private static String clean(String s, int maxLength) {
        String v = s == null ? "" : s.strip().replaceAll("\\p{Cntrl}", "");
        return v.length() > maxLength ? v.substring(0, maxLength) : v;
    }

    private static GameAction error(String reason) {
        return GameAction.of(ActionType.LOBBY_ERROR, new JSONObject().put("reason", reason));
    }

    // ---------------------------------------------------------------------------------------------
    // Writing
    // ---------------------------------------------------------------------------------------------

    void send(GameAction action) {
        sendLine(action.serialize());
    }

    /**
     * Writes one line. A failure closes the socket rather than throwing, which ends this session's
     * own reader and so cleans it up on its own thread; the caller is usually the opponent's
     * thread, which has nothing to do about it.
     */
    void sendLine(String line) {
        synchronized (sendLock) {
            writeLocked(line);
        }
    }

    private void writeLocked(String line) {
        try {
            out.write(line.getBytes(StandardCharsets.UTF_8));
            out.write('\n');
            out.flush();
        } catch (IOException e) {
            close();
        }
    }

    /**
     * Starts a match: writes each player's opening messages while holding both players' send
     * locks, so neither can relay a first move to the other until both have their GAME_SETUP. A
     * fast client answering its setup would otherwise land a KEEP_HAND in a lobby that is still
     * waiting for the setup. Locks are taken in id order, and a relay holds only one, so this
     * cannot deadlock.
     */
    static void sendToBoth(ClientSession a, List<GameAction> toA, ClientSession b, List<GameAction> toB) {
        ClientSession first  = a.id < b.id ? a : b;
        ClientSession second = first == a ? b : a;
        synchronized (first.sendLock) {
            synchronized (second.sendLock) {
                for (GameAction m : toA) a.writeLocked(m.serialize());
                for (GameAction m : toB) b.writeLocked(m.serialize());
            }
        }
    }

    /**
     * Ends the connection from the server's side: says why (unless {@code reason} is null), then
     * half-closes, so the client reads the reason before end of stream. The client is expected to
     * close its end; the server closes anyway shortly after.
     */
    void hangUp(String reason) {
        hungUp = true;
        if (reason != null) {
            send(GameAction.of(ActionType.DISCONNECT, new JSONObject().put("reason", reason)));
        }
        try {
            socket.shutdownOutput();
        } catch (IOException ignored) {
            // Already closed.
        }
        server.closeLater(this);
    }

    void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Nothing left to release.
        }
    }
}
