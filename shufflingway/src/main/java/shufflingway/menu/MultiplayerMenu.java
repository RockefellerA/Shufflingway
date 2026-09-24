package shufflingway.menu;

import shufflingway.net.GameAction;
import shufflingway.net.GameConnection;
import shufflingway.net.HostLobbyDialog;
import shufflingway.net.JoinLobbyDialog;
import shufflingway.net.MatchSetup;

import javax.swing.*;
import java.util.function.Consumer;

/**
 * Multiplayer menu — lets P1 host or join a game over a direct TCP connection.
 * Once the lobby has agreed on decks, shuffle seed and first player, the active
 * {@link GameConnection} is stored and the resulting {@link MatchSetup} is handed to the
 * main window, which starts the game from it.
 */
public class MultiplayerMenu extends JMenu {

    private GameConnection activeConnection;
    private final JMenuItem hostItem;
    private final JMenuItem joinItem;
    private final JMenuItem disconnectItem;

    /**
     * @param onConnected    receives the agreed match parameters; the main window starts the
     *                       networked game from them
     * @param onDisconnected receives why the connection ended. It carries a reason rather than
     *                       being a bare signal because the main window has to say what happened
     *                       and, more importantly, release anything still waiting on the peer
     */
    public MultiplayerMenu(JFrame owner, Consumer<MatchSetup> onConnected,
                           Consumer<String> onDisconnected, Consumer<GameAction> onActionReceived) {
        super("Multiplayer");

        hostItem = new JMenuItem("Host Game…");
        joinItem = new JMenuItem("Join Game…");
        disconnectItem = new JMenuItem("Disconnect");
        refreshItems();

        hostItem.addActionListener(e -> {
            HostLobbyDialog dlg = new HostLobbyDialog(owner);
            dlg.setVisible(true);
            // A connection without a setup means the lobby was cancelled after connecting.
            if (dlg.getConnection() != null && dlg.getSetup() != null)
                activate(dlg.getConnection(), dlg.getSetup(), owner,
                        onConnected, onDisconnected, onActionReceived);
        });

        joinItem.addActionListener(e -> {
            JoinLobbyDialog dlg = new JoinLobbyDialog(owner);
            dlg.setVisible(true);
            if (dlg.getConnection() != null && dlg.getSetup() != null)
                activate(dlg.getConnection(), dlg.getSetup(), owner,
                        onConnected, onDisconnected, onActionReceived);
        });

        disconnectItem.addActionListener(e -> disconnect(owner, onDisconnected));

        add(hostItem);
        add(joinItem);
        addSeparator();
        add(disconnectItem);
    }

    private void activate(GameConnection conn, MatchSetup setup, JFrame owner,
                          Consumer<MatchSetup> onConnected, Consumer<String> onDisconnected,
                          Consumer<GameAction> onActionReceived) {
        if (activeConnection != null) activeConnection.close();
        activeConnection = conn;
        refreshItems();

        conn.addListener(new shufflingway.net.ConnectionListener() {
            @Override
            public void onActionReceived(GameAction action) {
                SwingUtilities.invokeLater(() -> onActionReceived.accept(action));
            }
            @Override
            public void onDisconnected(String reason) {
                SwingUtilities.invokeLater(() -> {
                    activeConnection = null;
                    refreshItems();
                    if (onDisconnected != null) onDisconnected.accept(reason);
                    JOptionPane.showMessageDialog(owner,
                        "Opponent disconnected: " + reason,
                        "Disconnected", JOptionPane.WARNING_MESSAGE);
                });
            }
        });

        // Hand the setup over before starting the reader: onConnected only queues the game
        // start on the EDT, and queuing it first guarantees it runs ahead of any inbound action.
        // Started the other way round, a fast peer's first message could be processed against a
        // game that had not been built yet.
        onConnected.accept(setup);
        conn.start();
    }

    private void disconnect(JFrame owner, Consumer<String> onDisconnected) {
        if (activeConnection != null) {
            activeConnection.send(GameAction.of(shufflingway.net.ActionType.DISCONNECT,
                    new org.json.JSONObject().put("reason", "Player left")));
            activeConnection.close();
            activeConnection = null;
        }
        refreshItems();
        if (onDisconnected != null) onDisconnected.accept("you left the game");
        JOptionPane.showMessageDialog(owner, "Disconnected.", "Multiplayer",
                JOptionPane.INFORMATION_MESSAGE);
    }

    /**
     * While connected only Disconnect is live: hosting or joining another game would drop this
     * one without a word to the opponent. A new game with the same opponent is File → New Game.
     */
    private void refreshItems() {
        boolean connected = activeConnection != null;
        hostItem.setEnabled(!connected);
        joinItem.setEnabled(!connected);
        disconnectItem.setEnabled(connected);
    }

    /** Returns the active connection, or {@code null} if not connected. */
    public GameConnection getActiveConnection() { return activeConnection; }
}
