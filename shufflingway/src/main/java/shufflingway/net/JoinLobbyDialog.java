package shufflingway.net;

import org.json.JSONObject;
import scraper.AppPaths;
import scraper.CardDatabase;
import shufflingway.UpdateChecker;
import shufflingway.dialog.DeckChooserPanel;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.net.Socket;
import java.sql.SQLException;

/**
 * Modal dialog that connects to a host's IP:port.
 *
 * <p>Connecting sends this player's deck, then blocks for the host's deck and the host-authored
 * seed and coin flip — so the dialog stays open, showing "waiting for host", until the host
 * presses Start. On success it exposes a live {@link GameConnection} via
 * {@link #getConnection()} and the agreed {@link MatchSetup} via {@link #getSetup()}.
 * Cancelling or failing returns {@code null} from both.
 */
public class JoinLobbyDialog extends JDialog {

    private GameConnection connection;
    private MatchSetup     setup;

    private final JTextField hostField;
    private final JTextField portField;
    private final JLabel statusLabel;
    /** "Debug Mode: Enabled", shown under the status while the host has debugging switched on. */
    private final JLabel debugLabel;
    private final JButton connectBtn;
    private final DeckChooserPanel deckChooser;

    public JoinLobbyDialog(Frame owner) {
        super(owner, "Join Game", true);
        setResizable(false);
        setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        JPanel content = new JPanel(new BorderLayout(10, 10));
        content.setBorder(BorderFactory.createEmptyBorder(16, 20, 12, 20));

        JPanel fields = new JPanel(new GridBagLayout());
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(4, 4, 4, 4);
        gc.anchor = GridBagConstraints.WEST;

        gc.gridx = 0; gc.gridy = 0; gc.fill = GridBagConstraints.NONE; gc.weightx = 0;
        fields.add(new JLabel("Host IP:"), gc);
        gc.gridx = 1; gc.fill = GridBagConstraints.HORIZONTAL; gc.weightx = 1;
        hostField = new JTextField(16);
        fields.add(hostField, gc);

        gc.gridx = 0; gc.gridy = 1; gc.fill = GridBagConstraints.NONE; gc.weightx = 0;
        fields.add(new JLabel("Port:"), gc);
        gc.gridx = 1; gc.fill = GridBagConstraints.HORIZONTAL; gc.weightx = 1;
        portField = new JTextField(String.valueOf(HostLobbyDialog.DEFAULT_PORT), 6);
        fields.add(portField, gc);

        content.add(fields, BorderLayout.NORTH);

        statusLabel = new JLabel(" ", SwingConstants.CENTER);
        statusLabel.setFont(new Font("Dialog", Font.PLAIN, 12));

        deckChooser = new DeckChooserPanel("Your Deck", this::refreshConnectButton);

        // Blank rather than hidden while off, so the line's height is reserved from the start and
        // the dialog (not resizable) does not have to make room when the host switches it on.
        debugLabel = new JLabel(" ", SwingConstants.CENTER);
        debugLabel.setFont(new Font("Dialog", Font.BOLD, 12));
        debugLabel.setForeground(new Color(0xc0392b));

        JPanel south = new JPanel(new BorderLayout(0, 2));
        south.add(statusLabel, BorderLayout.CENTER);
        south.add(debugLabel, BorderLayout.SOUTH);

        JPanel centre = new JPanel(new BorderLayout(0, 6));
        centre.add(deckChooser, BorderLayout.CENTER);
        centre.add(south, BorderLayout.SOUTH);
        content.add(centre, BorderLayout.CENTER);

        connectBtn = new JButton("Connect");
        connectBtn.setEnabled(false);
        connectBtn.addActionListener(e -> attemptConnect());

        JButton cancelBtn = new JButton("Cancel");
        cancelBtn.addActionListener(e -> dispose());

        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        btnRow.add(cancelBtn);
        btnRow.add(connectBtn);
        content.add(btnRow, BorderLayout.SOUTH);

        setContentPane(content);
        pack();
        setMinimumSize(new Dimension(320, 360));
        setLocationRelativeTo(owner);

        getRootPane().setDefaultButton(connectBtn);
    }

    private void showDebugMode(boolean enabled) {
        debugLabel.setText(enabled ? "Debug Mode: Enabled" : " ");
    }

    /** Connecting without a deck would only fail at the exchange, so gate the button instead. */
    private void refreshConnectButton() {
        connectBtn.setEnabled(deckChooser.getSelectedDeckId() >= 0);
    }

    private void attemptConnect() {
        String host = hostField.getText().trim();
        if (host.isEmpty()) { statusLabel.setText("Enter a host address."); return; }
        int port;
        try {
            port = Integer.parseInt(portField.getText().trim());
        } catch (NumberFormatException e) {
            statusLabel.setText("Invalid port number.");
            return;
        }
        int    deckId   = deckChooser.getSelectedDeckId();
        String deckName = deckChooser.getSelectedDeckName();
        if (deckId < 0) { statusLabel.setText("Choose a deck first."); return; }

        connectBtn.setEnabled(false);
        statusLabel.setText("Connecting…");

        new Thread(() -> {
            try {
                Socket socket = new Socket(host, port);
                GameConnection conn = new GameConnection(socket);

                SwingUtilities.invokeLater(() -> statusLabel.setText("Verifying…"));
                String localVersion = UpdateChecker.currentVersion();
                String localChecksum;
                try (CardDatabase db = new CardDatabase(AppPaths.dbPath())) {
                    localChecksum = db.computeCardChecksum();
                }
                conn.send(GameAction.of(ActionType.HELLO, new JSONObject()
                        .put("version", localVersion)
                        .put("cardChecksum", localChecksum)));

                GameAction response = conn.receiveSync();
                if (response.type() == ActionType.DISCONNECT) {
                    String reason = response.payload().optString("reason", "Rejected by host");
                    conn.close();
                    SwingUtilities.invokeLater(() -> {
                        statusLabel.setText(reason);
                        connectBtn.setEnabled(true);
                    });
                    return;
                }
                if (response.type() != ActionType.HELLO) {
                    conn.close();
                    SwingUtilities.invokeLater(() -> {
                        statusLabel.setText("Unexpected response from host.");
                        connectBtn.setEnabled(true);
                    });
                    return;
                }

                connection = conn;

                // Send our deck immediately; the host reads it when they press Start.
                conn.send(LobbyExchange.deckListAction(deckId, deckName));
                SwingUtilities.invokeLater(() ->
                        statusLabel.setText("Waiting for host to start…"));

                LobbyExchange.RemoteDeck remote = LobbyExchange.awaitDeckList(conn,
                        debug -> SwingUtilities.invokeLater(() -> showDebugMode(debug)));
                GameAction setupAction = LobbyExchange.awaitGameSetup(conn);

                setup = new MatchSetup(deckId, remote.serials(), remote.name(), remote.username(),
                        setupAction.payload().getLong("seed"),
                        false,
                        setupAction.payload().getBoolean("hostGoesFirst"),
                        setupAction.payload().optBoolean("debug", false));
                SwingUtilities.invokeLater(this::dispose);
            } catch (IOException | SQLException ex) {
                if (connection != null) { connection.close(); connection = null; }
                SwingUtilities.invokeLater(() -> {
                    showDebugMode(false);
                    statusLabel.setText("Failed: " + ex.getMessage());
                    refreshConnectButton();
                });
            }
        }, "JoinLobby-connect").start();
    }

    /** Returns the live connection, or {@code null} if cancelled or failed. */
    public GameConnection getConnection() { return connection; }

    /** The agreed match parameters, or {@code null} if setup did not complete. */
    public MatchSetup getSetup() { return setup; }
}
