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
import java.lang.reflect.InvocationTargetException;
import java.net.Socket;
import java.sql.SQLException;
import java.util.List;

/**
 * Modal dialog that connects to a host's IP:port.
 *
 * <p>Connecting confirms the chosen deck once the host's settings arrive and allow it; the
 * dialog then stays open, showing "waiting for host", until the host presses Start, when the
 * decks are swapped and the host's seed and coin flip arrive. If the host switches on the
 * Standard banlist meanwhile, the deck is deselected and has to be confirmed again, with the
 * Connect button, now "Confirm Deck". On success it exposes a live {@link GameConnection} via
 * {@link #getConnection()} and the agreed {@link MatchSetup} via {@link #getSetup()}.
 * Cancelling or failing returns {@code null} from both.
 */
public class JoinLobbyDialog extends JDialog {

    private volatile GameConnection connection;
    private volatile MatchSetup     setup;

    private final DefaultListModel<LanDiscovery.Host> hostModel = new DefaultListModel<>();
    private final JList<LanDiscovery.Host> hostList = new JList<>(hostModel);
    private LanDiscovery.Listener discovery;
    private final JTextField hostField;
    private final JTextField portField;
    private final JLabel statusLabel;
    /** "Standard Banlist: Enabled", shown above the debug line while the host enforces it. */
    private final JLabel banlistLabel;
    /** "Debug Mode: Enabled", shown under the status while the host has debugging switched on. */
    private final JLabel debugLabel;
    private final JButton connectBtn;
    private final DeckChooserPanel deckChooser;

    // Lobby state once connected; EDT only.
    private boolean settingsSeen;
    /** The host's banlist reset count as last seen; echoed with LOBBY_READY. */
    private int     lastResets;
    private int     confirmedDeckId = -1;
    private String  confirmedDeckName;
    private boolean starting;

    /** The deck sent in answer to Start, for the match setup. */
    private volatile int startDeckId = -1;

    public JoinLobbyDialog(Frame owner) {
        super(owner, "Join Game", true);
        setResizable(false);
        setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        JPanel content = new JPanel(new BorderLayout(10, 10));
        content.setBorder(BorderFactory.createEmptyBorder(16, 20, 12, 20));

        hostList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        hostList.setVisibleRowCount(4);
        hostList.setCellRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> l, Object v, int i,
                                                                    boolean sel, boolean focus) {
                JLabel c = (JLabel) super.getListCellRendererComponent(l, v, i, sel, focus);
                if (v instanceof LanDiscovery.Host h) c.setToolTipText(h.address() + ":" + h.port());
                return c;
            }
        });
        JScrollPane hostScroll = new JScrollPane(hostList);
        hostScroll.setBorder(BorderFactory.createTitledBorder("Hosts on your network"));
        hostScroll.setPreferredSize(new Dimension(280, 110));

        JPanel fields = new JPanel(new GridBagLayout());
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(4, 4, 4, 4);
        gc.anchor = GridBagConstraints.WEST;

        gc.gridx = 0; gc.gridy = 0; gc.fill = GridBagConstraints.NONE; gc.weightx = 0;
        fields.add(new JLabel("Host IP (manual):"), gc);
        gc.gridx = 1; gc.fill = GridBagConstraints.HORIZONTAL; gc.weightx = 1;
        hostField = new JTextField(16);
        fields.add(hostField, gc);

        gc.gridx = 0; gc.gridy = 1; gc.fill = GridBagConstraints.NONE; gc.weightx = 0;
        fields.add(new JLabel("Port:"), gc);
        gc.gridx = 1; gc.fill = GridBagConstraints.HORIZONTAL; gc.weightx = 1;
        portField = new JTextField(String.valueOf(HostLobbyDialog.DEFAULT_PORT), 6);
        fields.add(portField, gc);

        JPanel top = new JPanel(new BorderLayout(0, 6));
        top.add(hostScroll, BorderLayout.CENTER);
        top.add(fields, BorderLayout.SOUTH);
        content.add(top, BorderLayout.NORTH);

        hostList.addListSelectionListener(e -> {
            LanDiscovery.Host h = hostList.getSelectedValue();
            if (e.getValueIsAdjusting() || h == null || connection != null) return;
            hostField.setText(h.address());
            portField.setText(String.valueOf(h.port()));
        });

        statusLabel = new JLabel(" ", SwingConstants.CENTER);
        statusLabel.setFont(new Font("Dialog", Font.PLAIN, 12));

        deckChooser = new DeckChooserPanel("Your Deck", this::refreshConnectButton);

        // Blank rather than hidden while off, so each line's height is reserved from the start and
        // the dialog (not resizable) does not have to make room when the host switches one on.
        banlistLabel = new JLabel(" ", SwingConstants.CENTER);
        banlistLabel.setFont(new Font("Dialog", Font.BOLD, 12));
        banlistLabel.setForeground(new Color(0xc0392b));

        debugLabel = new JLabel(" ", SwingConstants.CENTER);
        debugLabel.setFont(new Font("Dialog", Font.BOLD, 12));
        debugLabel.setForeground(new Color(0xc0392b));

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

        connectBtn = new JButton("Join");
        connectBtn.setEnabled(false);
        connectBtn.addActionListener(e -> {
            if (connection == null) attemptConnect();
            else confirmDeck();
        });

        JButton cancelBtn = new JButton("Cancel");
        cancelBtn.addActionListener(e -> dispose());
        // Leaving without a match hangs up, so the host sees the opponent go.
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosed(WindowEvent e) {
                discovery.stop();
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
        setMinimumSize(new Dimension(320, 460));
        setLocationRelativeTo(owner);

        discovery = LanDiscovery.startListening(hosts -> SwingUtilities.invokeLater(() -> showHosts(hosts)));

        getRootPane().setDefaultButton(connectBtn);
    }

    /** Refreshes the list, keeping the selected host selected if it is still announcing. */
    private void showHosts(List<LanDiscovery.Host> hosts) {
        if (connection != null) return;
        LanDiscovery.Host selected = hostList.getSelectedValue();
        hostModel.clear();
        hosts.forEach(hostModel::addElement);
        if (selected != null && hosts.contains(selected)) hostList.setSelectedValue(selected, true);
    }

    private void showDebugMode(boolean enabled) {
        debugLabel.setText(enabled ? "Debug Mode: Enabled" : " ");
    }

    private void showBanlist(boolean enabled) {
        banlistLabel.setText(enabled ? "Standard Banlist: Enabled" : " ");
    }

    /**
     * Before connecting the button connects, and needs a deck chosen. Once connected it confirms
     * the chosen deck, and is spent until the host's banlist voids that deck.
     */
    private void refreshConnectButton() {
        boolean picked = deckChooser.getSelectedDeckId() >= 0;
        if (connection == null) {
            connectBtn.setText("Join");
            connectBtn.setEnabled(picked);
        } else {
            connectBtn.setText("Confirm Deck");
            connectBtn.setEnabled(picked && settingsSeen && confirmedDeckId < 0 && !starting);
        }
    }

    /** Applies the host's lobby options, in wire order. */
    private void applySettings(LobbyExchange.LobbySettings s) {
        showDebugMode(s.debug());
        showBanlist(s.banlist());
        deckChooser.setBanlistEnforced(s.banlist());
        boolean first = !settingsSeen;
        boolean reset = s.resets() != lastResets;
        settingsSeen = true;
        lastResets   = s.resets();
        if (first) {
            // The deck was chosen before the host's settings were known; it stands if they allow
            // it. The banlist, if on, has already deselected one it refuses.
            if (deckChooser.getSelectedDeckId() >= 0) confirmDeck();
            else statusLabel.setText("Your deck breaks the Standard banlist. Choose another and confirm it.");
        } else if (reset) {
            unconfirm();
            deckChooser.clearSelection();
            statusLabel.setText("The host enabled the Standard banlist. Choose a deck and confirm it.");
        }
        refreshConnectButton();
    }

    /** Commits the chosen deck and tells the host this side is ready. */
    private void confirmDeck() {
        int id = deckChooser.getSelectedDeckId();
        GameConnection conn = connection;
        if (id < 0 || conn == null || starting) return;
        confirmedDeckId   = id;
        confirmedDeckName = deckChooser.getSelectedDeckName();
        deckChooser.setEnabled(false);
        conn.send(LobbyExchange.lobbyReadyAction(lastResets, true));
        statusLabel.setText("Deck confirmed. Waiting for host to start…");
        refreshConnectButton();
    }

    private void unconfirm() {
        confirmedDeckId   = -1;
        confirmedDeckName = null;
        deckChooser.setEnabled(true);
    }

    /**
     * The reply to the host's Start, asked for on the connection thread: the confirmed deck, or
     * a LOBBY_READY false when there is none (the host then waits for another Start).
     */
    private GameAction answerStart() {
        int[]    deck   = {-1};
        String[] name   = {null};
        int[]    resets = {0};
        onEdt(() -> {
            deck[0]   = confirmedDeckId;
            name[0]   = confirmedDeckName;
            resets[0] = lastResets;
            if (deck[0] >= 0) {
                starting = true;
                statusLabel.setText("Exchanging decks…");
                refreshConnectButton();
            }
        });
        if (deck[0] < 0) return LobbyExchange.lobbyReadyAction(resets[0], false);
        try {
            GameAction reply = LobbyExchange.deckListAction(deck[0], name[0]);
            startDeckId = deck[0];
            return reply;
        } catch (SQLException ex) {
            onEdt(() -> {
                starting = false;
                unconfirm();
                statusLabel.setText("Could not read your deck: " + ex.getMessage());
                refreshConnectButton();
            });
            return LobbyExchange.lobbyReadyAction(resets[0], false);
        }
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
        String host = hostField.getText().trim();
        if (host.isEmpty()) { statusLabel.setText("Enter a host address."); return; }
        int port;
        try {
            port = Integer.parseInt(portField.getText().trim());
        } catch (NumberFormatException e) {
            statusLabel.setText("Invalid port number.");
            return;
        }
        if (deckChooser.getSelectedDeckId() < 0) { statusLabel.setText("Choose a deck first."); return; }

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
                SwingUtilities.invokeLater(() -> {
                    statusLabel.setText("Connected. Waiting for the host's settings…");
                    refreshConnectButton();
                });

                // The deck is confirmed once the host's settings arrive; the host's Start asks for it.
                LobbyExchange.RemoteDeck remote = LobbyExchange.joinerAwaitStart(conn,
                        settings -> onEdt(() -> applySettings(settings)),
                        this::answerStart);
                GameAction setupAction = LobbyExchange.awaitGameSetup(conn);

                setup = new MatchSetup(startDeckId, remote.serials(), remote.name(), remote.username(),
                        setupAction.payload().getLong("seed"),
                        false,
                        setupAction.payload().getBoolean("hostGoesFirst"),
                        setupAction.payload().optBoolean("debug", false),
                        setupAction.payload().optBoolean("banlist", false),
                        remote.counterColor());
                SwingUtilities.invokeLater(this::dispose);
            } catch (IOException | SQLException | RuntimeException ex) {
                GameConnection conn = connection;
                if (conn != null) { conn.close(); connection = null; }
                SwingUtilities.invokeLater(() -> {
                    settingsSeen = false;
                    lastResets   = 0;
                    starting     = false;
                    unconfirm();
                    showDebugMode(false);
                    showBanlist(false);
                    deckChooser.setBanlistEnforced(false);
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
