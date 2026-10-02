package shufflingway.server;

import org.json.JSONObject;
import shufflingway.net.ActionType;
import shufflingway.net.GameAction;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * The dedicated relay server for remote play: a headless process that lists lobbies, pairs
 * players, and then passes each match's messages between its two players. It knows nothing of the
 * game's rules; both clients still run the whole game, exactly as over LAN.
 *
 * <pre>
 *   java -jar shufflingway-server.jar [--port 7777]
 * </pre>
 *
 * <p>One thread per connection. That is plenty for hundreds of players, and keeps each
 * connection's code a plain sequential loop ({@link ClientSession#run()}).
 */
public final class RelayServer implements Closeable {

    public static final int DEFAULT_PORT = 7777;
    static final int MAX_CONNECTIONS = 512;
    /** How long a hung-up client has to close its end before the server closes it. */
    static final long HANG_UP_GRACE_SECONDS = 5;

    /**
     * What one client may ask of the server. Set well above anything a real client does, since
     * tripping a message limit disconnects the player: dropping a move instead would desync the game.
     *
     * @param maxPerAddress       connections from one remote address at once; generous enough for
     *                            a household behind one router
     * @param lobbyListIntervalMs least time between two lobby lists sent to one connection; the
     *                            client polls every 4 s, and requests in between are ignored
     * @param messageBurst        messages one connection may send back to back
     * @param messagesPerSecond   messages one connection may sustain; real games send a few a second
     * @param byteBurst           bytes one connection may send back to back
     * @param bytesPerSecond      bytes one connection may sustain; a whole deck list is under 2 KB
     */
    public record Limits(int maxPerAddress, long lobbyListIntervalMs, int messageBurst,
                         int messagesPerSecond, int byteBurst, int bytesPerSecond) {

        public static final Limits DEFAULT = new Limits(8, 1_000, 200, 30, 1024 * 1024, 64 * 1024);

        public Limits withMaxPerAddress(int n) {
            return new Limits(n, lobbyListIntervalMs, messageBurst, messagesPerSecond, byteBurst, bytesPerSecond);
        }

        public Limits withLobbyListIntervalMs(long ms) {
            return new Limits(maxPerAddress, ms, messageBurst, messagesPerSecond, byteBurst, bytesPerSecond);
        }

        public Limits withMessageRate(int burst, int perSecond) {
            return new Limits(maxPerAddress, lobbyListIntervalMs, burst, perSecond, byteBurst, bytesPerSecond);
        }

        public Limits withByteRate(int burst, int perSecond) {
            return new Limits(maxPerAddress, lobbyListIntervalMs, messageBurst, messagesPerSecond, burst, perSecond);
        }
    }

    private final ServerSocket serverSocket;
    private final LobbyRegistry registry;
    private final Consumer<String> log;
    private final ScheduledExecutorService reaper;
    private final Map<InetAddress, Integer> perAddress = new HashMap<>();
    private final Limits limits;
    private int connections;

    /**
     * Binds the port; {@link #serve()} or {@link #start()} then accepts connections.
     *
     * @param port   0 for any free port (see {@link #port()})
     * @param limits what one client may ask of the server; usually {@link Limits#DEFAULT}
     * @param log    where server events go, one line each
     */
    public RelayServer(int port, Limits limits, Consumer<String> log) throws IOException {
        this.serverSocket = new ServerSocket(port);
        this.limits = limits;
        this.log = log;
        this.registry = new LobbyRegistry(log);
        this.reaper = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "relay-reaper");
            t.setDaemon(true);
            return t;
        });
    }

    public int port() {
        return serverSocket.getLocalPort();
    }

    LobbyRegistry registry() {
        return registry;
    }

    Limits limits() {
        return limits;
    }

    void log(String message) {
        log.accept(message);
    }

    /** Accepts connections on this thread until {@link #close()}. */
    public void serve() {
        log("listening on port " + port());
        while (!serverSocket.isClosed()) {
            Socket socket;
            try {
                socket = serverSocket.accept();
            } catch (SocketException e) {
                break;   // closed
            } catch (IOException e) {
                log("accept failed: " + e.getMessage());
                continue;
            }
            admit(socket);
        }
        log("stopped");
    }

    /** {@link #serve()} on a daemon thread. */
    public Thread start() {
        Thread t = new Thread(this::serve, "relay-accept");
        t.setDaemon(true);
        t.start();
        return t;
    }

    private void admit(Socket socket) {
        InetAddress address = socket.getInetAddress();
        String refusal = null;
        synchronized (this) {
            if (connections >= MAX_CONNECTIONS) refusal = "The server is full; try again later";
            else if (perAddress.getOrDefault(address, 0) >= limits.maxPerAddress())
                refusal = "Too many connections from your address";
            else {
                connections++;
                perAddress.merge(address, 1, Integer::sum);
            }
        }
        if (refusal != null) {
            refuse(socket, refusal);
            return;
        }
        try {
            Thread t = new Thread(new ClientSession(socket, this), "relay-session");
            t.setDaemon(true);
            t.start();
        } catch (IOException e) {
            release(address);
            try { socket.close(); } catch (IOException ignored) { }
        }
    }

    private void refuse(Socket socket, String reason) {
        try (socket) {
            socket.getOutputStream().write((GameAction.of(ActionType.DISCONNECT,
                    new JSONObject().put("reason", reason)).serialize() + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // It was going anyway.
        }
    }

    private synchronized void release(InetAddress address) {
        connections--;
        perAddress.computeIfPresent(address, (a, n) -> n <= 1 ? null : n - 1);
    }

    /** A session's last act: frees its connection slot. */
    void ended(ClientSession session, String why) {
        release(session.address());
        log(session + " " + why);
    }

    /** Closes {@code session}'s socket after the hang-up grace period. */
    void closeLater(ClientSession session) {
        try {
            reaper.schedule(session::close, HANG_UP_GRACE_SECONDS, TimeUnit.SECONDS);
        } catch (RejectedExecutionException shuttingDown) {
            session.close();
        }
    }

    @Override
    public void close() {
        try {
            serverSocket.close();
        } catch (IOException ignored) {
            // Already closed.
        }
        reaper.shutdownNow();
    }

    // ---------------------------------------------------------------------------------------------
    // Entry point
    // ---------------------------------------------------------------------------------------------

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static void main(String[] args) throws IOException {
        int port = DEFAULT_PORT;
        Limits limits = Limits.DEFAULT;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--port" -> port = intArg(args, ++i, "--port");
                case "--max-per-address" -> limits = limits.withMaxPerAddress(intArg(args, ++i, "--max-per-address"));
                case "--help", "-h" -> usage(null);
                default -> usage("unknown option: " + args[i]);
            }
        }
        RelayServer server = new RelayServer(port, limits,
                msg -> System.out.println(LocalDateTime.now().format(STAMP) + "  " + msg));
        Runtime.getRuntime().addShutdownHook(new Thread(server::close, "relay-shutdown"));
        server.serve();
    }

    private static int intArg(String[] args, int i, String option) {
        if (i >= args.length) usage(option + " needs a number");
        try {
            return Integer.parseInt(args[i]);
        } catch (NumberFormatException e) {
            usage(option + ": not a number: " + args[i]);
            return 0;
        }
    }

    private static void usage(String problem) {
        if (problem != null) System.err.println(problem);
        System.err.println("usage: java -jar shufflingway-server.jar [--port N] [--max-per-address N]");
        System.err.println("  --port             port to listen on (default " + DEFAULT_PORT + ")");
        System.err.println("  --max-per-address  connections allowed from one address (default "
                + Limits.DEFAULT.maxPerAddress() + ")");
        System.exit(problem == null ? 0 : 2);
    }
}
