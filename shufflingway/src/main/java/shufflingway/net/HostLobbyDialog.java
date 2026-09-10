package shufflingway.net;

import org.json.JSONObject;
import scraper.AppPaths;
import scraper.CardDatabase;
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
 * for an opponent to connect. Share your IP address and port with the opponent
 * out-of-band (chat, voice, etc.).
 *
 * <p>"Start Game" unlocks once an opponent has connected <em>and</em> the host has picked a
 * deck. Pressing it runs {@link LobbyExchange} — decks are swapped, the host picks the shuffle
 * seed and flips for first turn — and the results are exposed as a {@link MatchSetup} alongside
 * the live {@link GameConnection}. Cancelling returns {@code null} from both.
 */
public class HostLobbyDialog extends JDialog {

    static final int DEFAULT_PORT = 7777;

    private GameConnection connection;
    private ServerSocket serverSocket;
    private MatchSetup    setup;

    private final JLabel statusLabel;
    private final JButton cancelBtn;
    private final JButton startBtn;
    private final DeckChooserPanel deckChooser;

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
        ipPanel.setBorder(BorderFactory.createTitledBorder("Share one of these with your opponent"));
        for (String ip : getLocalAddresses()) {
            JLabel lbl = new JLabel(ip + "  :  " + DEFAULT_PORT, SwingConstants.CENTER);
            lbl.setFont(new Font("Monospaced", Font.BOLD, 13));
            ipPanel.add(lbl);
        }
        content.add(ipPanel, BorderLayout.NORTH);

        statusLabel = new JLabel("Waiting for opponent…", SwingConstants.CENTER);
        statusLabel.setFont(new Font("Dialog", Font.PLAIN, 12));

        deckChooser = new DeckChooserPanel("Your Deck", this::refreshStartButton);

        JPanel centre = new JPanel(new BorderLayout(0, 6));
        centre.add(deckChooser, BorderLayout.CENTER);
        centre.add(statusLabel, BorderLayout.SOUTH);
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

    /** Start unlocks only once both halves are ready: an opponent connected and a deck picked. */
    private void refreshStartButton() {
        startBtn.setEnabled(connection != null && deckChooser.getSelectedDeckId() >= 0);
    }

    /**
     * Swaps decks with the joiner, authors the seed and coin flip, and closes the dialog.
     * Runs off the EDT because it blocks on the joiner's deck list.
     */
    private void beginMatch() {
        int    deckId   = deckChooser.getSelectedDeckId();
        String deckName = deckChooser.getSelectedDeckName();
        if (deckId < 0 || connection == null) return;

        startBtn.setEnabled(false);
        cancelBtn.setEnabled(false);
        statusLabel.setText("Exchanging decks…");

        new Thread(() -> {
            try {
                connection.send(LobbyExchange.deckListAction(deckId, deckName));
                LobbyExchange.RemoteDeck remote = LobbyExchange.awaitDeckList(connection);

                boolean hostGoesFirst = new Random().nextBoolean();
                long    seed          = LobbyExchange.sendGameSetup(connection, hostGoesFirst);

                setup = new MatchSetup(deckId, remote.serials(), remote.name(), remote.username(),
                        seed, true, hostGoesFirst);
                SwingUtilities.invokeLater(this::dispose);
            } catch (IOException | SQLException ex) {
                SwingUtilities.invokeLater(() -> {
                    statusLabel.setText("Setup failed: " + ex.getMessage());
                    cancelBtn.setEnabled(true);
                    refreshStartButton();
                });
            }
        }, "HostLobby-setup").start();
    }

    private void openServerSocket() {
        new Thread(() -> {
            try {
                serverSocket = new ServerSocket(DEFAULT_PORT);
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
                    connection = conn;
                    SwingUtilities.invokeLater(() -> {
                        statusLabel.setText("Connected: " + conn.getRemoteAddress());
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

    private void cancel() {
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
