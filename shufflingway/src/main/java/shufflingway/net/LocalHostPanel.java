package shufflingway.net;

import org.json.JSONObject;
import scraper.AppPaths;
import scraper.CardDatabase;
import shufflingway.AppSettings;
import shufflingway.UpdateChecker;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.net.*;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Random;

/**
 * The Host tab of {@link LocalLobbyDialog}. Pressing Host opens a {@link ServerSocket} on
 * {@link #DEFAULT_PORT} and waits for an opponent to connect. The host is announced on the local
 * network (see {@link LanDiscovery}); across the internet, share the address shown here
 * out-of-band. Nothing is opened or announced until Host is pressed, so a player who only came to
 * join never ties up the port or appears in anyone's list.
 *
 * <p>"Start Game" unlocks once an opponent has connected and confirmed a deck, <em>and</em> the
 * host has picked one. Pressing it runs {@link LobbyExchange} — decks are swapped, the host picks
 * the shuffle seed and flips for first turn — and the dialog closes with the resulting
 * {@link MatchSetup}. If the opponent leaves first, hosting ends and the tab can host again.
 *
 * <p>Switching on "Enable Standard Banlist" deselects both players' decks; each has to choose
 * again from the decks the banlist allows.
 */
final class LocalHostPanel extends LocalLobbyDialog.Role {

    static final int DEFAULT_PORT = 7777;

    private GameConnection connection;
    private volatile ServerSocket serverSocket;

    /** Announces this host to the local network while it waits for an opponent. */
    private volatile LanDiscovery.Broadcaster broadcaster;

    private final JButton hostBtn;
    /**
     * "Enable Debugging": whether the Debug menu may be used during the match, on both clients.
     * Offered only to a host who has the Debug menu at all; otherwise the match runs without it.
     */
    private final JCheckBox debugBox;
    /** "Enable Standard Banlist": decks that break it cannot be chosen, by either player. */
    private final JCheckBox banlistBox;

    /** Host was pressed and has not ended since. EDT only. */
    private boolean hosting;
    /** Times the banlist has been switched on; sent with the settings to void older LOBBY_READYs. */
    private int banlistResets;
    /** Whether the joiner has a deck confirmed under the current settings. EDT only. */
    private boolean opponentReady;
    /** Start was pressed and the deck swap is under way. EDT only. */
    private boolean starting;
    /** The dialog has closed; threads still running stand down quietly. */
    private volatile boolean closed;

    /** Fixed when Start is pressed, for the lobby reader to build the match from. */
    private volatile int     matchDeckId = -1;
    private volatile boolean matchHostGoesFirst;
    private volatile boolean matchDebug;
    private volatile boolean matchBanlist;

    LocalHostPanel(LocalLobbyDialog lobby) {
        super(lobby, new BorderLayout(0, 6));
        setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        // Show all local IPv4 addresses so the host can tell the opponent which to use
        JPanel ipPanel = new JPanel(new GridLayout(0, 1, 0, 4));
        ipPanel.setBorder(BorderFactory.createTitledBorder("Your IP Address:"));
        for (String ip : getLocalAddresses()) {
            JLabel lbl = new JLabel(ip + "  :  " + DEFAULT_PORT, SwingConstants.CENTER);
            lbl.setFont(new Font("Monospaced", Font.BOLD, 13));
            ipPanel.add(lbl);
        }
        add(ipPanel, BorderLayout.NORTH);

        debugBox = new JCheckBox("Enable Debugging", false);
        debugBox.setToolTipText("Let both players use the Debug menu during this game.");
        debugBox.addActionListener(e -> sendLobbySettings());

        banlistBox = new JCheckBox("Enable Standard Banlist", false);
        banlistBox.setToolTipText(
                "Decks that break the Standard banlist cannot be chosen, by either player.");
        banlistBox.addActionListener(e -> onBanlistToggled());

        hostBtn = new JButton("Host");
        hostBtn.addActionListener(e -> startHosting());

        // The Join tab is taller, so the spare height goes above the Host button, not between options.
        JPanel options = new JPanel(new GridBagLayout());
        GridBagConstraints gc = new GridBagConstraints();
        gc.gridx = 0; gc.gridy = 0; gc.weightx = 1; gc.anchor = GridBagConstraints.WEST;
        gc.insets = new Insets(0, 0, 2, 0);
        options.add(banlistBox, gc);
        if (AppSettings.isDebugEnabled()) { gc.gridy++; options.add(debugBox, gc); }
        gc.gridy++; gc.weighty = 1; gc.anchor = GridBagConstraints.SOUTHEAST;
        options.add(hostBtn, gc);
        add(options, BorderLayout.CENTER);
    }

    // ---------------------------------------------------------------------------------------------
    // Role
    // ---------------------------------------------------------------------------------------------

    @Override JButton commitButton() { return hostBtn; }

    @Override String actionLabel() { return "Start Game"; }

    /** Start unlocks only once both halves are ready: the joiner's deck confirmed, and one picked here. */
    @Override boolean actionEnabled() {
        return connection != null && opponentReady && !starting
                && lobby.deckChooser().getSelectedDeckId() >= 0;
    }

    @Override void onAction() { beginMatch(); }

    @Override void refreshControls() { hostBtn.setEnabled(!hosting); }

    @Override void activated() {
        lobby.deckChooser().setBanlistEnforced(banlistBox.isSelected());
        lobby.setStatus("Choose your options, then press Host to wait for an opponent.");
    }

    @Override void shutdown(boolean matched) {
        closed = true;
        stopBroadcast();
        closeServerSocket();
        if (!matched && connection != null) { connection.close(); connection = null; }
    }

    // ---------------------------------------------------------------------------------------------
    // Lobby
    // ---------------------------------------------------------------------------------------------

    /** Whether the match will run with the Debug menu usable. */
    private boolean debugEnabled() {
        return AppSettings.isDebugEnabled() && debugBox.isSelected();
    }

    /** Tells a connected joiner the current settings, so their lobby can show them before Start. */
    private void sendLobbySettings() {
        GameConnection conn = connection;
        if (conn != null) conn.send(LobbyExchange.lobbySettingsAction(new LobbyExchange.LobbySettings(
                debugEnabled(), banlistBox.isSelected(), banlistResets)));
    }

    /**
     * Switching the banlist on voids both players' decks: the host's is deselected here, and the
     * joiner, told by the settings that follow, drops theirs and has to confirm one again.
     */
    private void onBanlistToggled() {
        boolean on = banlistBox.isSelected();
        lobby.deckChooser().setBanlistEnforced(on);
        if (on) {
            banlistResets++;
            lobby.deckChooser().clearSelection();
            opponentReady = false;
        }
        sendLobbySettings();
        showOpponentStatus();
        lobby.refresh();
    }

    private void showOpponentStatus() {
        GameConnection conn = connection;
        if (conn == null || starting) return;
        lobby.setStatus("Connected: " + conn.getRemoteAddress() + " — "
                + (opponentReady ? "opponent is ready." : "opponent is choosing a deck…"));
    }

    /**
     * Sends this side's deck, which asks the joiner for theirs; the lobby reader takes the answer
     * from there and authors the seed and coin flip. The deck is read off the EDT.
     */
    private void beginMatch() {
        int    deckId   = lobby.deckChooser().getSelectedDeckId();
        String deckName = lobby.deckChooser().getSelectedDeckName();
        GameConnection conn = connection;
        if (deckId < 0 || conn == null || !opponentReady) return;

        starting = true;
        lockSettings(true);   // the values are sent with the setup and fixed from here
        matchDeckId        = deckId;
        matchHostGoesFirst = new Random().nextBoolean();
        matchDebug         = debugEnabled();
        matchBanlist       = banlistBox.isSelected();
        lobby.setStatus("Exchanging decks…");
        lobby.refresh();

        new Thread(() -> {
            try {
                conn.send(LobbyExchange.deckListAction(deckId, deckName));
            } catch (SQLException ex) {
                SwingUtilities.invokeLater(() -> startFailed("Setup failed: " + ex.getMessage()));
            }
        }, "HostLobby-setup").start();
    }

    /** Freezes the lobby while Start is under way; unlocking leaves Start to {@link #actionEnabled}. */
    private void lockSettings(boolean locked) {
        lobby.setCancelEnabled(!locked);
        debugBox.setEnabled(!locked);
        banlistBox.setEnabled(!locked);
        lobby.deckChooser().setEnabled(!locked);
    }

    /** Start did not go through; back to waiting in the lobby. */
    private void startFailed(String message) {
        starting = false;
        matchDeckId = -1;
        lockSettings(false);
        lobby.setStatus(message);
        lobby.refresh();
    }

    /**
     * Reads the joiner from connect until it answers Start with its deck, then sends the game
     * setup. It ends there, before the game's own reader starts on the same connection.
     */
    private void startLobbyReader(GameConnection conn) {
        new Thread(() -> {
            try {
                LobbyExchange.RemoteDeck remote = LobbyExchange.hostAwaitJoinerDeck(conn,
                        (resets, ready) -> SwingUtilities.invokeLater(() -> onJoinerReady(resets, ready)));
                // The joiner sends its deck only in answer to ours, so Start has fixed the match.
                if (matchDeckId < 0) throw new IOException("Opponent sent a deck before Start");
                boolean hostGoesFirst = matchHostGoesFirst;
                boolean debug         = matchDebug;
                boolean banlist       = matchBanlist;
                long    seed          = LobbyExchange.sendGameSetup(conn, hostGoesFirst, debug, banlist);
                MatchSetup setup = new MatchSetup(matchDeckId, remote.serials(), remote.name(), remote.username(),
                        seed, true, hostGoesFirst, debug, banlist, remote.counterColor());
                SwingUtilities.invokeLater(() -> lobby.finish(conn, setup));
            } catch (IOException ex) {
                if (closed) return;
                conn.close();
                SwingUtilities.invokeLater(() -> {
                    connection = null;
                    opponentReady = false;
                    starting = false;
                    lockSettings(false);
                    hostingEnded("Opponent disconnected. Host again, or join a game instead.");
                });
            }
        }, "HostLobby-reader").start();
    }

    private void onJoinerReady(int resets, boolean ready) {
        if (resets != banlistResets) return;   // sent before the joiner saw the latest reset
        opponentReady = ready;
        if (starting && !ready) {
            startFailed("Opponent has no deck confirmed yet.");
            return;
        }
        showOpponentStatus();
        lobby.refresh();
    }

    // ---------------------------------------------------------------------------------------------
    // Hosting
    // ---------------------------------------------------------------------------------------------

    private void startHosting() {
        hosting       = true;
        opponentReady = false;
        matchDeckId   = -1;
        lobby.commit(this);
        lobby.setStatus("Waiting for opponent…");

        new Thread(() -> {
            ServerSocket ss = null;
            try {
                ss = new ServerSocket(DEFAULT_PORT);
                serverSocket = ss;
                if (closed) return;   // the dialog closed while the socket was opening
                broadcaster = LanDiscovery.startBroadcast(DEFAULT_PORT, AppSettings.getUsername());
                while (true) {
                    Socket client = ss.accept();
                    GameConnection conn = new GameConnection(client);
                    String rejection = performHandshake(conn);
                    if (rejection != null) {
                        conn.send(GameAction.of(ActionType.DISCONNECT,
                                new JSONObject().put("reason", rejection)));
                        conn.close();
                        final String reason = rejection;
                        SwingUtilities.invokeLater(() ->
                                lobby.setStatus("Rejected: " + reason + " — waiting…"));
                        continue;
                    }
                    SwingUtilities.invokeLater(() -> opponentConnected(conn));
                    break;
                }
            } catch (IOException e) {
                if (!closed) SwingUtilities.invokeLater(() -> hostingEnded("Could not host: " + e.getMessage()));
            } finally {
                stopBroadcast();
                if (ss != null) {
                    try { ss.close(); } catch (IOException ignored) {}
                }
            }
        }, "HostLobby-accept").start();
    }

    private void opponentConnected(GameConnection conn) {
        if (closed) { conn.close(); return; }
        // Assigned and first sent on the EDT, where the checkbox is read, so a toggle cannot fall
        // between the two and leave the joiner a stale value.
        connection = conn;
        opponentReady = false;
        sendLobbySettings();
        startLobbyReader(conn);
        showOpponentStatus();
        lobby.refresh();
    }

    /** Hosting stopped without a match; the tab can host again, or the Join tab be used. */
    private void hostingEnded(String message) {
        hosting = false;
        lobby.setStatus(message);
        lobby.uncommit();
    }

    /**
     * Exchanges HELLO with the joining player and validates their version and card checksum.
     * Returns {@code null} on success, or a human-readable rejection reason on failure.
     */
    private static String performHandshake(GameConnection conn) {
        try {
            String localVersion = UpdateChecker.currentVersion();
            String localChecksum;
            try (CardDatabase db = new CardDatabase(AppPaths.dbPath())) {
                localChecksum = db.computeCardChecksum();
            }

            GameAction hello = conn.receiveSync();
            if (hello.type() != ActionType.HELLO) {
                return "Unexpected message during handshake";
            }

            String remoteVersion = hello.payload().optString("version", "");
            String remoteChecksum = hello.payload().optString("cardChecksum", "");

            boolean devMode = "dev".equals(localVersion) || "dev".equals(remoteVersion);
            if (!devMode && !localVersion.equals(remoteVersion)) {
                return "Version mismatch (host: " + localVersion + ", joiner: " + remoteVersion + ")";
            }
            if (!localChecksum.equals(remoteChecksum)) {
                return "Card database mismatch — re-sync card data and try again";
                // TODO: Consider a cardCount variable (e.g. "host has 4262 cards, you have 4261)
            }

            conn.send(GameAction.of(ActionType.HELLO, new JSONObject()
                    .put("version", localVersion)
                    .put("cardChecksum", localChecksum)));
            return null;
        } catch (IOException | SQLException e) {
            return "Handshake error: " + e.getMessage();
        }
    }

    private void stopBroadcast() {
        LanDiscovery.Broadcaster b = broadcaster;
        if (b != null) b.stop();
    }

    private void closeServerSocket() {
        ServerSocket ss = serverSocket;
        if (ss != null) {
            try { ss.close(); } catch (IOException ignored) {}
        }
    }

    private static List<String> getLocalAddresses() {
        List<String> addrs = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces.hasMoreElements()) {
                NetworkInterface iface = ifaces.nextElement();
                if (!iface.isUp() || iface.isLoopback()) continue;
                Enumeration<InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (addr instanceof Inet4Address) addrs.add(addr.getHostAddress());
                }
            }
        } catch (SocketException ignored) {}
        if (addrs.isEmpty()) addrs.add("127.0.0.1");
        return addrs;
    }
}
