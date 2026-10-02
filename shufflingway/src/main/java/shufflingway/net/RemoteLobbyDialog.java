package shufflingway.net;

import scraper.AppPaths;
import scraper.CardDatabase;
import shufflingway.UpdateChecker;
import shufflingway.dialog.DeckChooserPanel;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.SQLException;

/**
 * Modal dialog that joins a lobby on a dedicated server with a chosen deck. Nobody hosts: the
 * server owns the lobby's settings and starts the match once both players have a deck in, so
 * there is no Host/Join choice and no Start button. See {@link RemoteLobbyExchange} for the wire.
 *
 * <p>Connecting confirms the chosen deck once the server's settings arrive and allow it; the
 * dialog then stays open, showing "waiting for an opponent", until the server starts the match.
 * If the server switches on the Standard banlist meanwhile, the deck is deselected and has to be
 * confirmed again with the Connect button, now "Confirm Deck". On success it exposes a live
 * {@link GameConnection} via {@link #getConnection()} and the agreed {@link MatchSetup} via
 * {@link #getSetup()}; cancelling or failing returns {@code null} from both.
 */
public class RemoteLobbyDialog extends JDialog {

    private static final int CONNECT_TIMEOUT_MS = 10_000;

    private volatile GameConnection connection;
    private volatile MatchSetup     setup;

    private final JTextField serverField;
    private final JTextField portField;
    private final JTextField lobbyField;
    private final JLabel statusLabel;
    /** "Standard Banlist: Enabled", shown while the server enforces it. */
    private final JLabel banlistLabel;
    /** "Debug Mode: Enabled", shown while the server allows the Debug menu. */
    private final JLabel debugLabel;
    private final JButton connectBtn;
    private final DeckChooserPanel deckChooser;

    // Lobby state once connected; EDT only.
    private boolean settingsSeen;
    /** The server's banlist reset count as last seen; sent with the deck. */
    private int     lastResets;
    private int     confirmedDeckId = -1;

    public RemoteLobbyDialog(Frame owner) {
        super(owner, "Remote Game", true);
        setResizable(false);
        setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        JPanel content = new JPanel(new BorderLayout(10, 10));
        content.setBorder(BorderFactory.createEmptyBorder(16, 20, 12, 20));

        JPanel fields = new JPanel(new GridBagLayout());
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(4, 4, 4, 4);
        gc.anchor = GridBagConstraints.WEST;

        serverField = new JTextField(16);
        portField   = new JTextField(String.valueOf(HostLobbyDialog.DEFAULT_PORT), 6);
        lobbyField  = new JTextField(16);
        lobbyField.setToolTipText("Leave blank to be paired with anyone waiting");
        addRow(fields, gc, 0, "Server:", serverField);
        addRow(fields, gc, 1, "Port:", portField);
        addRow(fields, gc, 2, "Lobby (optional):", lobbyField);
        content.add(fields, BorderLayout.NORTH);

        statusLabel = new JLabel(" ", SwingConstants.CENTER);
        statusLabel.setFont(new Font("Dialog", Font.PLAIN, 12));

        deckChooser = new DeckChooserPanel("Your Deck", this::refreshConnectButton);

        // Blank rather than hidden while off, so each line's height is reserved from the start and
        // the dialog (not resizable) does not have to make room when the server switches one on.
        banlistLabel = noticeLabel();
        debugLabel   = noticeLabel();

        JPanel notices = new JPanel(new GridLayout(0, 1, 0, 2));
        notices.add(banlistLabel);
        notices.add(debugLabel);

        JPanel south = new JPanel(new BorderLayout(0, 2));
        south.add(statusLabel, BorderLayout.CENTER);
        south.add(notices, BorderLayout.SOUTH);

        JPanel centre = new JPanel(new BorderLayout(0, 6));
        centre.add(deckChooser, BorderLayout.CENTER);
        centre.add(south, BorderLayout.SOUTH);
        content.add(centre, BorderLayout.CENTER);

        connectBtn = new JButton("Connect");
        connectBtn.setEnabled(false);
        connectBtn.addActionListener(e -> {
            if (connection == null) attemptConnect();
            else confirmDeck();
        });

        JButton cancelBtn = new JButton("Cancel");
        cancelBtn.addActionListener(e -> dispose());
        // Leaving without a match hangs up, so the server frees this seat.
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosed(WindowEvent e) {
                GameConnection conn = connection;
                if (setup == null && conn != null) conn.close();
            }
        });

        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        btnRow.add(cancelBtn);
        btnRow.add(connectBtn);
        content.add(btnRow, BorderLayout.SOUTH);

        setContentPane(content);
        pack();
        setMinimumSize(new Dimension(320, 420));
        setLocationRelativeTo(owner);

        getRootPane().setDefaultButton(connectBtn);
    }

    private static void addRow(JPanel panel, GridBagConstraints gc, int row, String label, JTextField field) {
        gc.gridx = 0; gc.gridy = row; gc.fill = GridBagConstraints.NONE; gc.weightx = 0;
        panel.add(new JLabel(label), gc);
        gc.gridx = 1; gc.fill = GridBagConstraints.HORIZONTAL; gc.weightx = 1;
        panel.add(field, gc);
    }

    private static JLabel noticeLabel() {
        JLabel l = new JLabel(" ", SwingConstants.CENTER);
        l.setFont(new Font("Dialog", Font.BOLD, 12));
        l.setForeground(new Color(0xc0392b));
        return l;
    }

    /**
     * Before connecting the button connects, and needs a deck chosen. Once connected it confirms
     * the chosen deck, and is spent until the server's banlist voids that deck.
     */
    private void refreshConnectButton() {
        boolean picked = deckChooser.getSelectedDeckId() >= 0;
        if (connection == null) {
            connectBtn.setText("Connect");
            connectBtn.setEnabled(picked);
        } else {
            connectBtn.setText("Confirm Deck");
            connectBtn.setEnabled(picked && settingsSeen && confirmedDeckId < 0);
        }
    }

    /** Applies the server's lobby options, in wire order. */
    private void applySettings(LobbyExchange.LobbySettings s) {
        debugLabel.setText(s.debug() ? "Debug Mode: Enabled" : " ");
        banlistLabel.setText(s.banlist() ? "Standard Banlist: Enabled" : " ");
        deckChooser.setBanlistEnforced(s.banlist());
        boolean first = !settingsSeen;
        boolean reset = s.resets() != lastResets;
        settingsSeen = true;
        lastResets   = s.resets();
        if (first) {
            // The deck was chosen before the server's settings were known; it stands if they allow
            // it. The banlist, if on, has already deselected one it refuses.
            if (deckChooser.getSelectedDeckId() >= 0) confirmDeck();
            else statusLabel.setText("Your deck breaks the Standard banlist. Choose another and confirm it.");
        } else if (reset) {
            unconfirm();
            deckChooser.clearSelection();
            statusLabel.setText("The server enabled the Standard banlist. Choose a deck and confirm it.");
        }
        refreshConnectButton();
    }

    /** Sends the chosen deck to the server, which starts the match once the opponent's is in too. */
    private void confirmDeck() {
        int id = deckChooser.getSelectedDeckId();
        GameConnection conn = connection;
        if (id < 0 || conn == null) return;
        try {
            conn.send(RemoteLobbyExchange.deckAction(id, deckChooser.getSelectedDeckName(), lastResets));
        } catch (SQLException ex) {
            statusLabel.setText("Could not read your deck: " + ex.getMessage());
            return;
        }
        confirmedDeckId = id;
        deckChooser.setEnabled(false);
        statusLabel.setText("Deck confirmed. Waiting for an opponent…");
        refreshConnectButton();
    }

    private void unconfirm() {
        confirmedDeckId = -1;
        deckChooser.setEnabled(true);
    }

    /** Runs {@code r} on the EDT and waits, so the lobby's state moves in wire order. */
    private static void onEdt(Runnable r) {
        try {
            SwingUtilities.invokeAndWait(r);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (InvocationTargetException e) {
            throw new IllegalStateException(e.getCause());
        }
    }

    private void attemptConnect() {
        String server = serverField.getText().trim();
        if (server.isEmpty()) { statusLabel.setText("Enter a server address."); return; }
        int port;
        try {
            port = Integer.parseInt(portField.getText().trim());
        } catch (NumberFormatException e) {
            statusLabel.setText("Invalid port number.");
            return;
        }
        if (deckChooser.getSelectedDeckId() < 0) { statusLabel.setText("Choose a deck first."); return; }
        String lobby = lobbyField.getText().trim();

        connectBtn.setEnabled(false);
        serverField.setEnabled(false);
        portField.setEnabled(false);
        lobbyField.setEnabled(false);
        statusLabel.setText("Connecting…");

        new Thread(() -> {
            try {
                Socket socket = new Socket();
                socket.connect(new InetSocketAddress(server, port), CONNECT_TIMEOUT_MS);
                GameConnection conn = new GameConnection(socket);
                connection = conn;

                SwingUtilities.invokeLater(() -> statusLabel.setText("Verifying…"));
                String localChecksum;
                try (CardDatabase db = new CardDatabase(AppPaths.dbPath())) {
                    localChecksum = db.computeCardChecksum();
                }
                conn.send(RemoteLobbyExchange.helloAction(UpdateChecker.currentVersion(), localChecksum, lobby));
                RemoteLobbyExchange.awaitWelcome(conn);

                SwingUtilities.invokeLater(() -> {
                    statusLabel.setText("Connected. Waiting for the server's settings…");
                    refreshConnectButton();
                });

                RemoteLobbyExchange.RemoteMatch match = RemoteLobbyExchange.awaitMatch(conn,
                        settings -> onEdt(() -> applySettings(settings)));

                int[] deck = {-1};
                onEdt(() -> deck[0] = confirmedDeckId);
                if (deck[0] < 0) throw new IOException("Server started the match before your deck was confirmed");
                setup = match.toSetup(deck[0]);
                SwingUtilities.invokeLater(this::dispose);
            } catch (IOException | SQLException | RuntimeException ex) {
                GameConnection conn = connection;
                if (conn != null) { conn.close(); connection = null; }
                SwingUtilities.invokeLater(() -> {
                    settingsSeen = false;
                    lastResets   = 0;
                    unconfirm();
                    debugLabel.setText(" ");
                    banlistLabel.setText(" ");
                    deckChooser.setBanlistEnforced(false);
                    serverField.setEnabled(true);
                    portField.setEnabled(true);
                    lobbyField.setEnabled(true);
                    statusLabel.setText("Failed: " + ex.getMessage());
                    refreshConnectButton();
                });
            }
        }, "RemoteLobby-connect").start();
    }

    /** Returns the live connection, or {@code null} if cancelled or failed. */
    public GameConnection getConnection() { return connection; }

    /** The agreed match parameters, or {@code null} if setup did not complete. */
    public MatchSetup getSetup() { return setup; }
}
