package shufflingway.net;

import scraper.AppPaths;
import scraper.CardDatabase;
import shufflingway.AppSettings;
import shufflingway.UpdateChecker;
import shufflingway.dialog.DeckChooserPanel;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.SQLException;
import java.util.List;

/**
 * Modal dialog for remote play through the relay server ({@code shufflingway.server.RelayServer}).
 * Nobody hosts: after connecting, the player either joins one of the server's open lobbies
 * (entering its password if it has one) or creates their own, naming it and choosing its
 * settings. The server starts the match once both players in a lobby have confirmed a deck, so
 * there is no Start button. See {@link RemoteLobbyExchange} for the wire.
 *
 * <p>On success it exposes a live {@link GameConnection} via {@link #getConnection()} and the agreed
 * {@link MatchSetup} via {@link #getSetup()}; cancelling or failing returns {@code null} from both.
 */
public class RemoteLobbyDialog extends JDialog {

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    /** How often the lobby list is refreshed while browsing. */
    private static final int POLL_MS = 4_000;

    private volatile GameConnection connection;
    private volatile MatchSetup     setup;

    private final JTextField serverField;
    private final JTextField portField;
    private final JButton    connectBtn;

    private final JTabbedPane tabs;
    private final DefaultListModel<RemoteLobbyExchange.LobbyInfo> lobbyModel = new DefaultListModel<>();
    private final JList<RemoteLobbyExchange.LobbyInfo> lobbyList = new JList<>(lobbyModel);
    private final JPasswordField joinPasswordField;
    private final JButton joinBtn;
    private final JTextField lobbyNameField;
    private final JPasswordField createPasswordField;
    private final JCheckBox banlistBox;
    private final JCheckBox debugBox;
    private final JButton createBtn;

    private final DeckChooserPanel deckChooser;
    private final JLabel statusLabel;
    private final JLabel banlistLabel;
    private final JLabel debugLabel;
    private final JButton confirmBtn;
    private final Timer pollTimer;

    // Lobby state; EDT only.
    /** The server accepted this client's HELLO. */
    private boolean welcomed;
    /** A create or join is in flight; its buttons stay off until the answer. */
    private boolean awaitingReply;
    private String  pendingLobbyName;
    private String  lobbyName;
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

        // ── Server ───────────────────────────────────────────────────────────
        // The last server that accepted this client, so it only has to be typed once.
        serverField = new JTextField(AppSettings.getRemoteServer(), 14);
        portField   = new JTextField(String.valueOf(AppSettings.getRemotePort(HostLobbyDialog.DEFAULT_PORT)), 5);
        connectBtn  = new JButton("Connect");
        connectBtn.addActionListener(e -> attemptConnect());
        JPanel serverRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        serverRow.add(new JLabel("Server:"));
        serverRow.add(serverField);
        serverRow.add(new JLabel("Port:"));
        serverRow.add(portField);
        serverRow.add(connectBtn);

        // ── Join ─────────────────────────────────────────────────────────────
        lobbyList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        lobbyList.setVisibleRowCount(5);
        lobbyList.setCellRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> l, Object v, int i,
                                                                    boolean sel, boolean focus) {
                JLabel c = (JLabel) super.getListCellRendererComponent(l, v, i, sel, focus);
                if (v instanceof RemoteLobbyExchange.LobbyInfo info) {
                    String creator = info.creator().isEmpty() ? "" : " — " + info.creator();
                    c.setText(info.name() + creator
                            + (info.hasPassword() ? "  [password]" : "")
                            + (info.banlist() ? "  [banlist]" : "")
                            + (info.debug() ? "  [debug]" : ""));
                }
                return c;
            }
        });
        lobbyList.addListSelectionListener(e -> refreshControls());
        lobbyList.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && joinBtn.isEnabled()) joinLobby();
            }
        });
        joinPasswordField = new JPasswordField(12);
        joinBtn = new JButton("Join Lobby");
        joinBtn.addActionListener(e -> joinLobby());

        JPanel joinFooter = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        joinFooter.add(new JLabel("Password:"));
        joinFooter.add(joinPasswordField);
        joinFooter.add(joinBtn);
        JPanel joinPanel = new JPanel(new BorderLayout(0, 6));
        joinPanel.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        JScrollPane lobbyScroll = new JScrollPane(lobbyList);
        lobbyScroll.setPreferredSize(new Dimension(300, 110));
        joinPanel.add(lobbyScroll, BorderLayout.CENTER);
        joinPanel.add(joinFooter, BorderLayout.SOUTH);

        // ── Create ───────────────────────────────────────────────────────────
        String username = AppSettings.getUsername();
        lobbyNameField = new JTextField(username.isEmpty() ? "" : username + "'s lobby", 16);
        createPasswordField = new JPasswordField(16);
        createPasswordField.setToolTipText("Optional; leave blank for a lobby anyone can join");
        banlistBox = new JCheckBox("Enforce Standard banlist");
        debugBox   = new JCheckBox("Allow Debug menu");
        createBtn  = new JButton("Create Lobby");
        createBtn.addActionListener(e -> createLobby());

        JPanel createPanel = new JPanel(new GridBagLayout());
        createPanel.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(3, 3, 3, 3);
        gc.anchor = GridBagConstraints.WEST;
        addRow(createPanel, gc, 0, "Lobby name:", lobbyNameField);
        addRow(createPanel, gc, 1, "Password:", createPasswordField);
        gc.gridx = 1; gc.gridy = 2; gc.fill = GridBagConstraints.NONE;
        createPanel.add(banlistBox, gc);
        gc.gridy = 3;
        createPanel.add(debugBox, gc);
        gc.gridy = 4; gc.anchor = GridBagConstraints.EAST;
        createPanel.add(createBtn, gc);

        tabs = new JTabbedPane();
        tabs.addTab("Join", joinPanel);
        tabs.addTab("Create", createPanel);

        JPanel top = new JPanel(new BorderLayout(0, 8));
        top.add(serverRow, BorderLayout.NORTH);
        top.add(tabs, BorderLayout.CENTER);
        content.add(top, BorderLayout.NORTH);

        // ── Deck and status ──────────────────────────────────────────────────
        deckChooser = new DeckChooserPanel("Your Deck", this::refreshControls);

        statusLabel = new JLabel("Enter the server's address and connect.", SwingConstants.CENTER);
        statusLabel.setFont(new Font("Dialog", Font.PLAIN, 12));
        // Blank rather than hidden while off, so each line's height is reserved from the start and
        // the dialog (not resizable) does not have to make room when a lobby switches one on.
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

        confirmBtn = new JButton("Confirm Deck");
        confirmBtn.addActionListener(e -> confirmDeck());
        JButton cancelBtn = new JButton("Cancel");
        cancelBtn.addActionListener(e -> dispose());

        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        btnRow.add(cancelBtn);
        btnRow.add(confirmBtn);
        content.add(btnRow, BorderLayout.SOUTH);

        pollTimer = new Timer(POLL_MS, e -> {
            GameConnection conn = connection;
            if (conn != null && welcomed && lobbyName == null) conn.send(RemoteLobbyExchange.listAction());
        });

        // Leaving without a match hangs up, which frees this player's seat on the server.
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosed(WindowEvent e) {
                pollTimer.stop();
                GameConnection conn = connection;
                if (setup == null && conn != null) conn.close();
            }
        });

        setContentPane(content);
        pack();
        setLocationRelativeTo(owner);
        getRootPane().setDefaultButton(connectBtn);
        refreshControls();
    }

    private static void addRow(JPanel panel, GridBagConstraints gc, int row, String label, JComponent field) {
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

    /** Enables each control for the stage the dialog is at: connecting, browsing, or in a lobby. */
    private void refreshControls() {
        boolean disconnected = connection == null;
        boolean browsing = welcomed && lobbyName == null && !awaitingReply;
        serverField.setEnabled(disconnected);
        portField.setEnabled(disconnected);
        connectBtn.setEnabled(disconnected);

        lobbyList.setEnabled(browsing);
        joinPasswordField.setEnabled(browsing);
        joinBtn.setEnabled(browsing && lobbyList.getSelectedValue() != null);
        lobbyNameField.setEnabled(browsing);
        createPasswordField.setEnabled(browsing);
        banlistBox.setEnabled(browsing);
        debugBox.setEnabled(browsing);
        createBtn.setEnabled(browsing);

        confirmBtn.setEnabled(lobbyName != null && settingsSeen && confirmedDeckId < 0
                && deckChooser.getSelectedDeckId() >= 0);
        if (lobbyName != null) getRootPane().setDefaultButton(confirmBtn);
    }

    // ---------------------------------------------------------------------------------------------
    // Browsing
    // ---------------------------------------------------------------------------------------------

    /** Replaces the list, keeping the selected lobby selected if it is still open. */
    private void showLobbies(List<RemoteLobbyExchange.LobbyInfo> lobbies) {
        if (lobbyName != null) return;
        RemoteLobbyExchange.LobbyInfo selected = lobbyList.getSelectedValue();
        lobbyModel.clear();
        lobbies.forEach(lobbyModel::addElement);
        if (selected != null) {
            for (RemoteLobbyExchange.LobbyInfo l : lobbies) {
                if (l.name().equals(selected.name())) { lobbyList.setSelectedValue(l, false); break; }
            }
        }
        if (statusLabel.getText().startsWith("Connected")) {
            statusLabel.setText(lobbies.isEmpty()
                    ? "Connected. No open lobbies yet — create one, or wait for one to appear."
                    : "Connected. Join a lobby, or create your own.");
        }
        refreshControls();
    }

    private void joinLobby() {
        RemoteLobbyExchange.LobbyInfo lobby = lobbyList.getSelectedValue();
        GameConnection conn = connection;
        if (lobby == null || conn == null) return;
        String password = new String(joinPasswordField.getPassword());
        if (lobby.hasPassword() && password.isEmpty()) {
            statusLabel.setText("\"" + lobby.name() + "\" needs a password.");
            joinPasswordField.requestFocusInWindow();
            return;
        }
        awaitingReply = true;
        pendingLobbyName = lobby.name();
        statusLabel.setText("Joining \"" + lobby.name() + "\"…");
        conn.send(RemoteLobbyExchange.joinAction(lobby.name(), password));
        refreshControls();
    }

    private void createLobby() {
        GameConnection conn = connection;
        String name = lobbyNameField.getText().trim();
        if (conn == null) return;
        if (name.isEmpty()) { statusLabel.setText("Name your lobby."); return; }
        if (name.length() > RemoteLobbyExchange.LOBBY_NAME_MAX_LENGTH) {
            statusLabel.setText("Lobby names are at most " + RemoteLobbyExchange.LOBBY_NAME_MAX_LENGTH + " characters.");
            return;
        }
        String password = new String(createPasswordField.getPassword());
        if (password.length() > RemoteLobbyExchange.PASSWORD_MAX_LENGTH) {
            statusLabel.setText("Passwords are at most " + RemoteLobbyExchange.PASSWORD_MAX_LENGTH + " characters.");
            return;
        }
        awaitingReply = true;
        pendingLobbyName = name;
        statusLabel.setText("Creating \"" + name + "\"…");
        conn.send(RemoteLobbyExchange.createAction(name, password, banlistBox.isSelected(), debugBox.isSelected()));
        refreshControls();
    }

    private void showError(String reason) {
        awaitingReply = false;
        pendingLobbyName = null;
        statusLabel.setText(reason);
        refreshControls();
    }

    // ---------------------------------------------------------------------------------------------
    // In a lobby
    // ---------------------------------------------------------------------------------------------

    /** Applies the lobby's options, in wire order; the first set means the create or join worked. */
    private void applySettings(LobbyExchange.LobbySettings s) {
        if (lobbyName == null) {
            lobbyName = pendingLobbyName == null ? "the lobby" : pendingLobbyName;
            awaitingReply = false;
            pollTimer.stop();
        }
        debugLabel.setText(s.debug() ? "Debug Mode: Enabled" : " ");
        banlistLabel.setText(s.banlist() ? "Standard Banlist: Enabled" : " ");
        deckChooser.setBanlistEnforced(s.banlist());
        boolean first = !settingsSeen;
        boolean reset = s.resets() != lastResets;
        settingsSeen = true;
        lastResets   = s.resets();
        if (first) {
            // A deck chosen before the lobby's settings were known stands if they allow it. The
            // banlist, if on, has already deselected one it refuses.
            if (deckChooser.getSelectedDeckId() >= 0) confirmDeck();
            else statusLabel.setText("In \"" + lobbyName + "\". Choose a deck and confirm it.");
        } else if (reset) {
            unconfirm();
            deckChooser.clearSelection();
            statusLabel.setText("The lobby enabled the Standard banlist. Choose a deck and confirm it.");
        }
        refreshControls();
    }

    /** Sends the chosen deck to the server, which starts the match once the opponent's is in too. */
    private void confirmDeck() {
        int id = deckChooser.getSelectedDeckId();
        GameConnection conn = connection;
        if (id < 0 || conn == null || lobbyName == null) return;
        try {
            conn.send(RemoteLobbyExchange.deckAction(id, deckChooser.getSelectedDeckName(), lastResets));
        } catch (SQLException ex) {
            statusLabel.setText("Could not read your deck: " + ex.getMessage());
            return;
        }
        confirmedDeckId = id;
        deckChooser.setEnabled(false);
        statusLabel.setText("In \"" + lobbyName + "\". Deck confirmed; waiting for an opponent…");
        refreshControls();
    }

    private void unconfirm() {
        confirmedDeckId = -1;
        deckChooser.setEnabled(true);
    }

    // ---------------------------------------------------------------------------------------------
    // Connection
    // ---------------------------------------------------------------------------------------------

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

        connectBtn.setEnabled(false);
        serverField.setEnabled(false);
        portField.setEnabled(false);
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
                conn.send(RemoteLobbyExchange.helloAction(UpdateChecker.currentVersion(), localChecksum));
                RemoteLobbyExchange.awaitWelcome(conn);
                conn.send(RemoteLobbyExchange.listAction());

                SwingUtilities.invokeLater(() -> {
                    welcomed = true;
                    // Saved only once the server has accepted us, so a mistyped address is not kept.
                    AppSettings.setRemoteServer(server, port);
                    AppSettings.save();
                    statusLabel.setText("Connected. Fetching lobbies…");
                    pollTimer.start();
                    refreshControls();
                });

                RemoteLobbyExchange.RemoteMatch match = RemoteLobbyExchange.awaitMatch(conn,
                        new RemoteLobbyExchange.LobbyListener() {
                            @Override public void onLobbies(List<RemoteLobbyExchange.LobbyInfo> lobbies) {
                                onEdt(() -> showLobbies(lobbies));
                            }
                            @Override public void onError(String reason) {
                                onEdt(() -> showError(reason));
                            }
                            @Override public void onSettings(LobbyExchange.LobbySettings settings) {
                                onEdt(() -> applySettings(settings));
                            }
                        });

                int[] deck = {-1};
                onEdt(() -> deck[0] = confirmedDeckId);
                if (deck[0] < 0) throw new IOException("Server started the match before your deck was confirmed");
                setup = match.toSetup(deck[0]);
                SwingUtilities.invokeLater(this::dispose);
            } catch (IOException | SQLException | RuntimeException ex) {
                GameConnection conn = connection;
                if (conn != null) { conn.close(); connection = null; }
                SwingUtilities.invokeLater(() -> resetAfterFailure("Failed: " + ex.getMessage()));
            }
        }, "RemoteLobby-connect").start();
    }

    private void resetAfterFailure(String message) {
        pollTimer.stop();
        welcomed         = false;
        awaitingReply    = false;
        pendingLobbyName = null;
        lobbyName        = null;
        settingsSeen     = false;
        lastResets       = 0;
        unconfirm();
        lobbyModel.clear();
        debugLabel.setText(" ");
        banlistLabel.setText(" ");
        deckChooser.setBanlistEnforced(false);
        statusLabel.setText(message);
        getRootPane().setDefaultButton(connectBtn);
        refreshControls();
    }

    /** Returns the live connection, or {@code null} if cancelled or failed. */
    public GameConnection getConnection() { return connection; }

    /** The agreed match parameters, or {@code null} if setup did not complete. */
    public MatchSetup getSetup() { return setup; }
}
