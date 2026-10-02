package shufflingway.net;

import org.json.JSONObject;
import scraper.AppPaths;
import scraper.CardDatabase;
import shufflingway.UpdateChecker;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.Socket;
import java.sql.SQLException;
import java.util.List;

/**
 * The Join tab of {@link LocalLobbyDialog}: connects to a host's IP:port, picked from the hosts
 * announcing themselves on the local network or typed in.
 *
 * <p>Joining needs a deck chosen; it is confirmed once the host's settings arrive and allow it.
 * The dialog then stays open, showing "waiting for host", until the host presses Start, when the
 * decks are swapped and the host's seed and coin flip arrive. If the host switches on the
 * Standard banlist meanwhile, the deck is deselected and has to be confirmed again with
 * Confirm Deck.
 */
final class LocalJoinPanel extends LocalLobbyDialog.Role {

    private volatile GameConnection connection;

    private final DefaultListModel<LanDiscovery.Host> hostModel = new DefaultListModel<>();
    private final JList<LanDiscovery.Host> hostList = new JList<>(hostModel);
    /** Listens for hosts while the Join tab is usable; {@code null} while this machine hosts. */
    private LanDiscovery.Listener discovery;
    private final JTextField hostField;
    private final JTextField portField;
    private final JButton    joinBtn;

    // Lobby state; EDT only.
    /** Join was pressed and has not failed since. */
    private boolean connecting;
    private boolean settingsSeen;
    /** The host's banlist reset count as last seen; echoed with LOBBY_READY. */
    private int     lastResets;
    private int     confirmedDeckId = -1;
    private String  confirmedDeckName;
    private boolean starting;
    /** The dialog has closed; a connect still in flight hangs up. */
    private volatile boolean closed;

    /** The deck sent in answer to Start, for the match setup. */
    private volatile int startDeckId = -1;

    LocalJoinPanel(LocalLobbyDialog lobby) {
        super(lobby, new BorderLayout(0, 6));
        setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        hostField = new JTextField(14);
        portField = new JTextField(String.valueOf(LocalHostPanel.DEFAULT_PORT), 5);
        joinBtn   = new JButton("Join");
        joinBtn.addActionListener(e -> attemptConnect());

        hostList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        hostList.setVisibleRowCount(4);
        hostList.setCellRenderer(new PlainTextListRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> l, Object v, int i,
                                                                    boolean sel, boolean focus) {
                JLabel c = (JLabel) super.getListCellRendererComponent(l, v, i, sel, focus);
                if (v instanceof LanDiscovery.Host h) c.setToolTipText(h.address() + ":" + h.port());
                return c;
            }
        });
        hostList.addListSelectionListener(e -> {
            LanDiscovery.Host h = hostList.getSelectedValue();
            if (e.getValueIsAdjusting() || h == null || connecting) return;
            hostField.setText(h.address());
            portField.setText(String.valueOf(h.port()));
        });
        hostList.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && joinBtn.isEnabled()) attemptConnect();
            }
        });
        JScrollPane hostScroll = new JScrollPane(hostList);
        hostScroll.setBorder(BorderFactory.createTitledBorder("Hosts on your network"));
        hostScroll.setPreferredSize(new Dimension(280, 110));
        add(hostScroll, BorderLayout.CENTER);

        JPanel fields = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        fields.add(new JLabel("Host IP:"));
        fields.add(hostField);
        fields.add(new JLabel("Port:"));
        fields.add(portField);
        fields.add(joinBtn);
        add(fields, BorderLayout.SOUTH);

        setListening(true);
    }

    // ---------------------------------------------------------------------------------------------
    // Role
    // ---------------------------------------------------------------------------------------------

    @Override JButton commitButton() { return joinBtn; }

    @Override String actionLabel() { return "Confirm Deck"; }

    /** Spent once the deck is confirmed, until the host's banlist voids it. */
    @Override boolean actionEnabled() {
        return connection != null && settingsSeen && confirmedDeckId < 0 && !starting
                && lobby.deckChooser().getSelectedDeckId() >= 0;
    }

    @Override void onAction() { confirmDeck(); }

    /** Joining needs a deck chosen; the deck is confirmed once the host's settings allow it. */
    @Override void refreshControls() {
        hostList.setEnabled(!connecting);
        hostField.setEnabled(!connecting);
        portField.setEnabled(!connecting);
        joinBtn.setEnabled(!connecting && lobby.deckChooser().getSelectedDeckId() >= 0);
    }

    @Override void activated() {
        lobby.deckChooser().setBanlistEnforced(false);   // until a host's settings say otherwise
        lobby.setStatus("Choose a deck, then pick a host on your network or enter its address.");
    }

    @Override void shutdown(boolean matched) {
        closed = true;
        setListening(false);
        GameConnection conn = connection;
        if (!matched && conn != null) { conn.close(); connection = null; }
    }

    /** Starts or stops listening for hosts; while stopped the list is empty. */
    void setListening(boolean on) {
        if (on == (discovery != null)) return;
        if (on) {
            LanDiscovery.Listener[] self = new LanDiscovery.Listener[1];
            // A late update from a listener since stopped is dropped rather than shown.
            self[0] = discovery = LanDiscovery.startListening(hosts -> SwingUtilities.invokeLater(() -> {
                if (discovery == self[0]) showHosts(hosts);
            }));
        } else {
            discovery.stop();
            discovery = null;
            hostModel.clear();
        }
    }

    /** Refreshes the list, keeping the selected host selected if it is still announcing. */
    private void showHosts(List<LanDiscovery.Host> hosts) {
        if (connecting) return;
        LanDiscovery.Host selected = hostList.getSelectedValue();
        hostModel.clear();
        hosts.forEach(hostModel::addElement);
        if (selected != null && hosts.contains(selected)) hostList.setSelectedValue(selected, true);
    }

    // ---------------------------------------------------------------------------------------------
    // Lobby
    // ---------------------------------------------------------------------------------------------

    /** Applies the host's lobby options, in wire order. */
    private void applySettings(LobbyExchange.LobbySettings s) {
        lobby.showNotices(s.banlist(), s.debug());
        lobby.deckChooser().setBanlistEnforced(s.banlist());
        boolean first = !settingsSeen;
        boolean reset = s.resets() != lastResets;
        settingsSeen = true;
        lastResets   = s.resets();
        if (first) {
            // The deck was chosen before the host's settings were known; it stands if they allow
            // it. The banlist, if on, has already deselected one it refuses.
            if (lobby.deckChooser().getSelectedDeckId() >= 0) confirmDeck();
            else lobby.setStatus("Your deck breaks the Standard banlist. Choose another and confirm it.");
        } else if (reset) {
            unconfirm();
            lobby.deckChooser().clearSelection();
            lobby.setStatus("The host enabled the Standard banlist. Choose a deck and confirm it.");
        }
        lobby.refresh();
    }

    /** Commits the chosen deck and tells the host this side is ready. */
    private void confirmDeck() {
        int id = lobby.deckChooser().getSelectedDeckId();
        GameConnection conn = connection;
        if (id < 0 || conn == null || starting) return;
        confirmedDeckId   = id;
        confirmedDeckName = lobby.deckChooser().getSelectedDeckName();
        lobby.deckChooser().setEnabled(false);
        conn.send(LobbyExchange.lobbyReadyAction(lastResets, true));
        lobby.setStatus("Deck confirmed. Waiting for host to start…");
        lobby.refresh();
    }

    private void unconfirm() {
        confirmedDeckId   = -1;
        confirmedDeckName = null;
        lobby.deckChooser().setEnabled(true);
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
                lobby.setStatus("Exchanging decks…");
                lobby.refresh();
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
                lobby.setStatus("Could not read your deck: " + ex.getMessage());
                lobby.refresh();
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

    // ---------------------------------------------------------------------------------------------
    // Connection
    // ---------------------------------------------------------------------------------------------

    private void attemptConnect() {
        String host = hostField.getText().trim();
        if (host.isEmpty()) { lobby.setStatus("Enter a host address."); return; }
        int port;
        try {
            port = Integer.parseInt(portField.getText().trim());
        } catch (NumberFormatException e) {
            lobby.setStatus("Invalid port number.");
            return;
        }
        if (lobby.deckChooser().getSelectedDeckId() < 0) { lobby.setStatus("Choose a deck first."); return; }

        connecting = true;
        lobby.commit(this);
        lobby.setStatus("Connecting…");

        new Thread(() -> {
            try {
                Socket socket = new Socket(host, port);
                GameConnection conn = new GameConnection(socket);

                SwingUtilities.invokeLater(() -> lobby.setStatus("Verifying…"));
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
                    conn.close();
                    String reason = response.payload().optString("reason", "Rejected by host");
                    SwingUtilities.invokeLater(() -> connectFailed(reason));
                    return;
                }
                if (response.type() != ActionType.HELLO) {
                    conn.close();
                    SwingUtilities.invokeLater(() -> connectFailed("Unexpected response from host."));
                    return;
                }

                connection = conn;
                if (closed) { conn.close(); return; }   // the dialog closed while connecting
                SwingUtilities.invokeLater(() -> {
                    lobby.setStatus("Connected. Waiting for the host's settings…");
                    lobby.refresh();
                });

                // The deck is confirmed once the host's settings arrive; the host's Start asks for it.
                LobbyExchange.RemoteDeck remote = LobbyExchange.joinerAwaitStart(conn,
                        settings -> onEdt(() -> applySettings(settings)),
                        this::answerStart);
                GameAction setupAction = LobbyExchange.awaitGameSetup(conn);

                MatchSetup setup = new MatchSetup(startDeckId, remote.serials(), remote.name(), remote.username(),
                        setupAction.payload().getLong("seed"),
                        false,
                        setupAction.payload().getBoolean("hostGoesFirst"),
                        setupAction.payload().optBoolean("debug", false),
                        setupAction.payload().optBoolean("banlist", false),
                        remote.counterColor());
                SwingUtilities.invokeLater(() -> lobby.finish(conn, setup));
            } catch (IOException | SQLException | RuntimeException ex) {
                GameConnection conn = connection;
                if (conn != null) { conn.close(); connection = null; }
                if (!closed) SwingUtilities.invokeLater(() -> connectFailed("Failed: " + ex.getMessage()));
            }
        }, "JoinLobby-connect").start();
    }

    /** The connection failed or ended before the match; the lobby is back to before Join. */
    private void connectFailed(String message) {
        connecting   = false;
        settingsSeen = false;
        lastResets   = 0;
        starting     = false;
        unconfirm();
        lobby.showNotices(false, false);
        lobby.deckChooser().setBanlistEnforced(false);
        lobby.setStatus(message);
        lobby.uncommit();
    }
}
