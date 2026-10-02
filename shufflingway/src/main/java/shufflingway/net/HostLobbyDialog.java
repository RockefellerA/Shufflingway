package shufflingway.net;

import org.json.JSONObject;
import scraper.AppPaths;
import scraper.CardDatabase;
import shufflingway.AppSettings;
import shufflingway.UpdateChecker;
import shufflingway.dialog.DeckChooserPanel;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.net.*;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Random;

/**
 * Modal dialog that opens a {@link ServerSocket} on the default port and waits
 * for an opponent to connect. The host is announced on the local network
 * (see {@link LanDiscovery}); across the internet, share your IP address and port out-of-band.
 *
 * <p>"Start Game" unlocks once an opponent has connected and confirmed a deck, <em>and</em> the
 * host has picked one. Pressing it runs {@link LobbyExchange} — decks are swapped, the host picks
 * the shuffle seed and flips for first turn — and the results are exposed as a
 * {@link MatchSetup} alongside the live {@link GameConnection}. Cancelling returns {@code null}
 * from both.
 *
 * <p>Switching on "Enable Standard Banlist" deselects both players' decks; each has to choose
 * again from the decks the banlist allows.
 */
public class HostLobbyDialog extends JDialog {

    static final int DEFAULT_PORT = 7777;

    private GameConnection connection;
    private ServerSocket serverSocket;
    private MatchSetup    setup;

    /** Announces this host to the local network while it waits for an opponent. */
    private volatile LanDiscovery.Broadcaster broadcaster;

    private final JLabel statusLabel;
    private final JButton cancelBtn;
    private final JButton startBtn;
    private final DeckChooserPanel deckChooser;
    /**
     * "Enable Debugging": whether the Debug menu may be used during the match, on both clients.
     * Offered only to a host who has the Debug menu at all; otherwise the match runs without it.
     */
    private final JCheckBox debugBox;
    /** "Enable Standard Banlist": decks that break it cannot be chosen, by either player. */
    private final JCheckBox banlistBox;

    /** Times the banlist has been switched on; sent with the settings to void older LOBBY_READYs. */
    private int banlistResets;
    /** Whether the joiner has a deck confirmed under the current settings. EDT only. */
    private boolean opponentReady;
    /** Start was pressed and the deck swap is under way. EDT only. */
    private boolean starting;
    private volatile boolean cancelled;

    /** Fixed when Start is pressed, for the lobby reader to build the match from. */
    private volatile int     matchDeckId = -1;
    private volatile boolean matchHostGoesFirst;
    private volatile boolean matchDebug;
    private volatile boolean matchBanlist;

    public HostLobbyDialog(Frame owner) {
        super(owner, "Host Game", true);
        setResizable(false);
        setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { cancel(); }
        });

        JPanel content = new JPanel(new BorderLayout(10, 10));
        content.setBorder(BorderFactory.createEmptyBorder(16, 20, 12, 20));

        // Show all local IPv4 addresses so the host can tell the opponent which to use
        JPanel ipPanel = new JPanel(new GridLayout(0, 1, 0, 4));
        ipPanel.setBorder(BorderFactory.createTitledBorder("Your IP Address:"));
        for (String ip : getLocalAddresses()) {
            JLabel lbl = new JLabel(ip + "  :  " + DEFAULT_PORT, SwingConstants.CENTER);
            lbl.setFont(new Font("Monospaced", Font.BOLD, 13));
            ipPanel.add(lbl);
        }
        content.add(ipPanel, BorderLayout.NORTH);

        statusLabel = new JLabel("Waiting for opponent…", SwingConstants.CENTER);
        statusLabel.setFont(new Font("Dialog", Font.PLAIN, 12));

        deckChooser = new DeckChooserPanel("Your Deck", this::refreshStartButton);

        debugBox = new JCheckBox("Enable Debugging", false);
        debugBox.setToolTipText("Let both players use the Debug menu during this game.");
        debugBox.addActionListener(e -> sendLobbySettings());

        banlistBox = new JCheckBox("Enable Standard Banlist", false);
        banlistBox.setToolTipText(
                "Decks that break the Standard banlist cannot be chosen, by either player.");
        banlistBox.addActionListener(e -> onBanlistToggled());

        JPanel options = new JPanel(new GridLayout(0, 1, 0, 2));
        options.add(banlistBox);
        if (AppSettings.isDebugEnabled()) options.add(debugBox);

        JPanel south = new JPanel(new BorderLayout(0, 4));
        south.add(statusLabel, BorderLayout.CENTER);
        south.add(options, BorderLayout.SOUTH);

        JPanel centre = new JPanel(new BorderLayout(0, 6));
        centre.add(deckChooser, BorderLayout.CENTER);
        centre.add(south, BorderLayout.SOUTH);
        content.add(centre, BorderLayout.CENTER);

        cancelBtn = new JButton("Cancel");
        cancelBtn.addActionListener(e -> cancel());

        startBtn = new JButton("Start Game");
        startBtn.setEnabled(false);
        startBtn.addActionListener(e -> beginMatch());

        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        btnRow.add(cancelBtn);
        btnRow.add(startBtn);
        content.add(btnRow, BorderLayout.SOUTH);

        setContentPane(content);
        pack();
        setMinimumSize(new Dimension(380, 400));
        setLocationRelativeTo(owner);

        openServerSocket();
    }

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
        deckChooser.setBanlistEnforced(on);
        if (on) {
            banlistResets++;
            deckChooser.clearSelection();
            opponentReady = false;
        }
        sendLobbySettings();
        showOpponentStatus();
        refreshStartButton();
    }

    /** Start unlocks only once both halves are ready: the joiner's deck confirmed, and one picked here. */
    private void refreshStartButton() {
        startBtn.setEnabled(connection != null && opponentReady && !starting
                && deckChooser.getSelectedDeckId() >= 0);
    }

    private void showOpponentStatus() {
        GameConnection conn = connection;
        if (conn == null || starting) return;
        statusLabel.setText("Connected: " + conn.getRemoteAddress() + " — "
                + (opponentReady ? "opponent is ready." : "opponent is choosing a deck…"));
    }

    /**
     * Sends this side's deck, which asks the joiner for theirs; the lobby reader takes the answer
     * from there and authors the seed and coin flip. The deck is read off the EDT.
     */
    private void beginMatch() {
        int    deckId   = deckChooser.getSelectedDeckId();
        String deckName = deckChooser.getSelectedDeckName();
        GameConnection conn = connection;
        if (deckId < 0 || conn == null || !opponentReady) return;

        starting = true;
        lockSettings(true);   // the values are sent with the setup and fixed from here
        matchDeckId        = deckId;
        matchHostGoesFirst = new Random().nextBoolean();
        matchDebug         = debugEnabled();
        matchBanlist       = banlistBox.isSelected();
        statusLabel.setText("Exchanging decks…");

        new Thread(() -> {
            try {
                conn.send(LobbyExchange.deckListAction(deckId, deckName));
            } catch (SQLException ex) {
                SwingUtilities.invokeLater(() -> startFailed("Setup failed: " + ex.getMessage()));
            }
        }, "HostLobby-setup").start();
    }

    /** Freezes the lobby while Start is under way; unlocking leaves Start to {@link #refreshStartButton}. */
    private void lockSettings(boolean locked) {
        if (locked) startBtn.setEnabled(false);
        cancelBtn.setEnabled(!locked);
        debugBox.setEnabled(!locked);
        banlistBox.setEnabled(!locked);
        deckChooser.setEnabled(!locked);
    }

    /** Start did not go through; back to waiting in the lobby. */
    private void startFailed(String message) {
        starting = false;
        lockSettings(false);
        statusLabel.setText(message);
        refreshStartButton();
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
                setup = new MatchSetup(matchDeckId, remote.serials(), remote.name(), remote.username(),
                        seed, true, hostGoesFirst, debug, banlist, remote.counterColor());
                SwingUtilities.invokeLater(this::dispose);
            } catch (IOException ex) {
                if (cancelled) return;
                conn.close();
                SwingUtilities.invokeLater(() -> {
                    connection = null;
                    opponentReady = false;
                    starting = false;
                    lockSettings(false);
                    statusLabel.setText("Opponent disconnected.");
                    refreshStartButton();
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
        refreshStartButton();
    }

    private void openServerSocket() {
        new Thread(() -> {
            try {
                serverSocket = new ServerSocket(DEFAULT_PORT);
                broadcaster = LanDiscovery.startBroadcast(DEFAULT_PORT, AppSettings.getUsername());
                while (true) {
                    Socket client = serverSocket.accept();
                    GameConnection conn = new GameConnection(client);
                    String rejection = performHandshake(conn);
                    if (rejection != null) {
                        conn.send(GameAction.of(ActionType.DISCONNECT,
                                new JSONObject().put("reason", rejection)));
                        conn.close();
                        final String reason = rejection;
                        SwingUtilities.invokeLater(() ->
                                statusLabel.setText("Rejected: " + reason + " — waiting…"));
                        continue;
                    }
                    SwingUtilities.invokeLater(() -> {
                        // Assigned and first sent on the EDT, where the checkbox is read, so a
                        // toggle cannot fall between the two and leave the joiner a stale value.
                        stopBroadcast();
                        connection = conn;
                        opponentReady = false;
                        sendLobbySettings();
                        startLobbyReader(conn);
                        showOpponentStatus();
                        cancelBtn.setText("Cancel");
                        refreshStartButton();
                    });
                    break;
                }
            } catch (IOException e) {
                if (serverSocket != null && !serverSocket.isClosed()) {
                    SwingUtilities.invokeLater(() -> statusLabel.setText("Error: " + e.getMessage()));
                }
            } finally {
                stopBroadcast();
                try { if (serverSocket != null) serverSocket.close(); }
                catch (IOException ignored) {}
            }
        }, "HostLobby-accept").start();
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

    private void cancel() {
        cancelled = true;
        stopBroadcast();
        try { if (serverSocket != null) serverSocket.close(); }
        catch (IOException ignored) {}
        if (connection != null) { connection.close(); connection = null; }
        dispose();
    }

    /** Returns the live connection, or {@code null} if the dialog was cancelled. */
    public GameConnection getConnection() { return connection; }

    /** The agreed match parameters, or {@code null} if setup did not complete. */
    public MatchSetup getSetup() { return setup; }

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
