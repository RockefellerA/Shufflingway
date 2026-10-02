package shufflingway.net;

import shufflingway.dialog.DeckChooserPanel;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

/**
 * Modal dialog for a game over a direct TCP connection — on the local network, or to an address
 * typed in. Like {@link RemoteLobbyDialog} it offers two tabs: Host ({@link LocalHostPanel}) opens
 * this machine to an opponent, Join ({@link LocalJoinPanel}) connects to someone hosting. The deck
 * chooser, status line, lobby notices and the button under them are shared, and each tab says what
 * that button does for its side. Once a side starts hosting or connecting, the other tab is locked
 * until that attempt ends.
 *
 * <p>On success it exposes a live {@link GameConnection} via {@link #getConnection()} and the agreed
 * {@link MatchSetup} via {@link #getSetup()}; cancelling or failing returns {@code null} from both.
 */
public class LocalLobbyDialog extends JDialog {

    /**
     * One side of the lobby, shown as a tab. It drives the shared controls through the dialog, and
     * the dialog asks it what the button under the deck chooser says and does.
     */
    abstract static class Role extends JPanel {
        final LocalLobbyDialog lobby;

        Role(LocalLobbyDialog lobby, LayoutManager layout) {
            super(layout);
            this.lobby = lobby;
        }

        /** The tab's own button, Host or Join, which commits the dialog to this side. */
        abstract JButton commitButton();
        /** What the button under the deck chooser says for this side. */
        abstract String actionLabel();
        abstract boolean actionEnabled();
        abstract void onAction();
        /** Enables this tab's own controls for the stage it is at. */
        abstract void refreshControls();
        /** The tab was selected while neither side is committed: apply its deck rules and prompt. */
        abstract void activated();
        /** The dialog closed. Release sockets, and the connection unless the match went ahead. */
        abstract void shutdown(boolean matched);
    }

    private GameConnection connection;
    private MatchSetup     setup;
    private boolean        closed;

    private final DeckChooserPanel deckChooser;
    private final JLabel  statusLabel;
    private final JLabel  banlistLabel;
    private final JLabel  debugLabel;
    private final JButton actionBtn;
    private final JButton cancelBtn;
    private final LocalHostPanel hostPanel;
    private final LocalJoinPanel joinPanel;
    private final JTabbedPane    tabs;
    /** The side that has started hosting or connecting, or {@code null} while the choice is open. */
    private Role committed;

    public LocalLobbyDialog(Frame owner) {
        super(owner, "Local Game", true);
        setResizable(false);
        setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        JPanel content = new JPanel(new BorderLayout(10, 10));
        content.setBorder(BorderFactory.createEmptyBorder(16, 20, 12, 20));

        // The shared controls come first: each tab is built against them.
        deckChooser = new DeckChooserPanel("Your Deck", this::refresh);
        statusLabel = new JLabel(" ", SwingConstants.CENTER);
        statusLabel.setFont(new Font("Dialog", Font.PLAIN, 12));
        // Blank rather than hidden while off, so each line's height is reserved from the start and
        // the dialog (not resizable) does not have to make room when the host switches one on.
        banlistLabel = noticeLabel();
        debugLabel   = noticeLabel();

        actionBtn = new JButton();
        actionBtn.addActionListener(e -> activeRole().onAction());
        cancelBtn = new JButton("Cancel");
        cancelBtn.addActionListener(e -> dispose());

        hostPanel = new LocalHostPanel(this);
        joinPanel = new LocalJoinPanel(this);
        tabs = new JTabbedPane();
        tabs.addTab("Host", hostPanel);
        tabs.addTab("Join", joinPanel);
        tabs.addChangeListener(e -> { if (committed == null) activate(); });
        content.add(tabs, BorderLayout.NORTH);

        // Wide enough for either side's label, so the row does not shift when the tab changes.
        actionBtn.setText(joinPanel.actionLabel());
        Dimension join = actionBtn.getPreferredSize();
        actionBtn.setText(hostPanel.actionLabel());
        Dimension host = actionBtn.getPreferredSize();
        actionBtn.setPreferredSize(new Dimension(Math.max(join.width, host.width), Math.max(join.height, host.height)));

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

        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        btnRow.add(cancelBtn);
        btnRow.add(actionBtn);
        content.add(btnRow, BorderLayout.SOUTH);

        // Leaving without a match closes the server socket or hangs up, so the opponent sees it.
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosed(WindowEvent e) {
                closed = true;
                boolean matched = setup != null;
                hostPanel.shutdown(matched);
                joinPanel.shutdown(matched);
            }
        });

        setContentPane(content);
        pack();
        setLocationRelativeTo(owner);
        activate();
    }

    private static JLabel noticeLabel() {
        JLabel l = new JLabel(" ", SwingConstants.CENTER);
        l.setFont(new Font("Dialog", Font.BOLD, 12));
        l.setForeground(new Color(0xc0392b));
        return l;
    }

    private Role activeRole() {
        return (Role) tabs.getSelectedComponent();
    }

    private void activate() {
        Role role = activeRole();
        showNotices(false, false);
        role.activated();
        getRootPane().setDefaultButton(role.commitButton());
        refresh();
    }

    // ---------------------------------------------------------------------------------------------
    // What the tabs drive; EDT only
    // ---------------------------------------------------------------------------------------------

    DeckChooserPanel deckChooser() { return deckChooser; }

    void setStatus(String text) { statusLabel.setText(text); }

    void showNotices(boolean banlist, boolean debug) {
        banlistLabel.setText(banlist ? "Standard Banlist: Enabled" : " ");
        debugLabel.setText(debug ? "Debug Mode: Enabled" : " ");
    }

    void setCancelEnabled(boolean enabled) { cancelBtn.setEnabled(enabled); }

    /** Re-reads the active tab's controls and the shared button. */
    void refresh() {
        if (tabs == null) return;   // the deck chooser reports before the tabs exist
        Role role = activeRole();
        role.refreshControls();
        actionBtn.setText(role.actionLabel());
        actionBtn.setEnabled(role.actionEnabled());
    }

    /**
     * Locks the dialog to {@code role}: the other tab is disabled until {@link #uncommit}. Hosting
     * also stops listening for other hosts, so this machine does not appear in its own list.
     */
    void commit(Role role) {
        committed = role;
        for (int i = 0; i < tabs.getTabCount(); i++) tabs.setEnabledAt(i, tabs.getComponentAt(i) == role);
        if (role == hostPanel) joinPanel.setListening(false);
        getRootPane().setDefaultButton(actionBtn);
        refresh();
    }

    /** The committed side's attempt ended without a match; both tabs are open again. */
    void uncommit() {
        if (committed == hostPanel) joinPanel.setListening(true);
        committed = null;
        for (int i = 0; i < tabs.getTabCount(); i++) tabs.setEnabledAt(i, true);
        getRootPane().setDefaultButton(activeRole().commitButton());
        refresh();
    }

    /** The match is agreed: closes the dialog with it, or hangs up if the dialog already closed. */
    void finish(GameConnection conn, MatchSetup matchSetup) {
        if (closed) { conn.close(); return; }
        connection = conn;
        setup      = matchSetup;
        dispose();
    }

    /** Returns the live connection, or {@code null} if cancelled or failed. */
    public GameConnection getConnection() { return connection; }

    /** The agreed match parameters, or {@code null} if setup did not complete. */
    public MatchSetup getSetup() { return setup; }
}
