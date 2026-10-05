package shufflingway.dialog;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.event.KeyEvent;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;

import scraper.DeckDatabase;
import scraper.DeckDatabase.DeckSummary;
import shufflingway.AppSettings;
import shufflingway.DeckFormat;

/**
 * The New Game dialog for a game against the CPU: the game's format and banlist, and a deck for
 * each side. Both rules are remembered for the next game, and every change re-filters the decks.
 */
public class DeckSelectDialog extends JDialog {

    private static final Color BANNED_FG = new Color(0xC6, 0x28, 0x28);

    private int playerDeckId = -1;
    private int cpuDeckId    = -1;
    private DeckFormat format = AppSettings.getCpuFormat();

    /** Decks the chosen format and banlist refuse, with why. */
    private Map<Integer, String> refused = Map.of();
    private final List<DeckSummary> decks;

    public DeckSelectDialog(JFrame parent) {
        super(parent, "New Game – Choose Decks", true);
        setSize(700, 500);
        setLocationRelativeTo(parent);
        setLayout(new BorderLayout(8, 8));
        getRootPane().setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        decks = loadDecks();
        FormatPicker rules = new FormatPicker(format, AppSettings.isBanlistAgainstCpu());
        format = rules.format();   // a remembered format that is not playable falls back
        applyRules(rules.format(), rules.banlist());

        DefaultListModel<DeckSummary> listModel = new DefaultListModel<>();
        for (DeckSummary d : decks) listModel.addElement(d);

        JList<DeckSummary> playerList = new JList<>(listModel);
        playerList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        playerList.setCellRenderer(new DeckListRenderer());
        playerList.setFixedCellHeight(28);

        JList<DeckSummary> cpuList = new JList<>(listModel);
        cpuList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        cpuList.setCellRenderer(new DeckListRenderer());
        cpuList.setFixedCellHeight(28);

        JButton startBtn  = new JButton("Start Game");
        JButton cancelBtn = new JButton("Cancel");
        startBtn.setEnabled(false);

        Runnable updateStart = () -> {
            DeckSummary p = playerList.getSelectedValue();
            DeckSummary c = cpuList.getSelectedValue();
            startBtn.setEnabled(p != null && isEligible(p) && c != null && isEligible(c));
        };

        playerList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                DeckSummary sel = playerList.getSelectedValue();
                if (sel != null && !isEligible(sel)) playerList.clearSelection();
                else updateStart.run();
            }
        });

        cpuList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                DeckSummary sel = cpuList.getSelectedValue();
                if (sel != null && !isEligible(sel)) cpuList.clearSelection();
                else updateStart.run();
            }
        });

        JLabel headerLabel = new JLabel();
        headerLabel.setBorder(BorderFactory.createEmptyBorder(6, 0, 4, 0));
        Runnable showHeader = () -> headerLabel.setText(rules.banlist() || format.hasSetWindow()
                ? "Select a deck with exactly 50 main cards that is legal in " + format.label()
                        + (rules.banlist() ? " and follows the banlist" : "") + " for each side:"
                : "Select a deck with exactly 50 main cards for each side:");
        showHeader.run();

        // A change re-filters both lists; a deck the new rules refuse is deselected.
        rules.setOnChange(() -> {
            AppSettings.setCpuFormat(rules.format());
            AppSettings.setBanlistAgainstCpu(rules.banlist());
            AppSettings.save();
            applyRules(rules.format(), rules.banlist());
            for (JList<DeckSummary> l : List.of(playerList, cpuList)) {
                DeckSummary sel = l.getSelectedValue();
                if (sel != null && !isEligible(sel)) l.clearSelection();
                l.repaint();
            }
            showHeader.run();
            updateStart.run();
        });

        startBtn.addActionListener(e -> {
            DeckSummary p = playerList.getSelectedValue();
            DeckSummary c = cpuList.getSelectedValue();
            if (p != null) playerDeckId = p.id();
            if (c != null) cpuDeckId    = c.id();
            dispose();
        });
        cancelBtn.addActionListener(e -> dispose());

        getRootPane().registerKeyboardAction(
                e -> dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        getRootPane().setDefaultButton(startBtn);

        JPanel listsPanel = new JPanel(new GridLayout(1, 2, 12, 0));

        JPanel playerPanel = new JPanel(new BorderLayout(0, 4));
        JLabel playerLabel = new JLabel("Player", SwingConstants.CENTER);
        playerLabel.setBorder(BorderFactory.createEmptyBorder(0, 0, 2, 0));
        JScrollPane playerScroll = new JScrollPane(playerList);
        playerScroll.setPreferredSize(new Dimension(0, 0));
        playerPanel.add(playerLabel,  BorderLayout.NORTH);
        playerPanel.add(playerScroll, BorderLayout.CENTER);

        JPanel cpuPanel = new JPanel(new BorderLayout(0, 4));
        JLabel cpuLabel = new JLabel("CPU", SwingConstants.CENTER);
        cpuLabel.setBorder(BorderFactory.createEmptyBorder(0, 0, 2, 0));
        JScrollPane cpuScroll = new JScrollPane(cpuList);
        cpuScroll.setPreferredSize(new Dimension(0, 0));
        cpuPanel.add(cpuLabel,  BorderLayout.NORTH);
        cpuPanel.add(cpuScroll, BorderLayout.CENTER);

        listsPanel.add(playerPanel);
        listsPanel.add(cpuPanel);

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        btnPanel.add(startBtn);
        btnPanel.add(cancelBtn);

        JPanel north = new JPanel(new BorderLayout());
        north.add(rules,       BorderLayout.NORTH);
        north.add(headerLabel, BorderLayout.SOUTH);

        add(north,       BorderLayout.NORTH);
        add(listsPanel,  BorderLayout.CENTER);
        add(btnPanel,    BorderLayout.SOUTH);
    }

    /** Returns the player's selected deck ID, or -1 if cancelled. */
    public int getPlayerDeckId() { return playerDeckId; }

    /** Returns the CPU's selected deck ID, or -1 if cancelled. */
    public int getCpuDeckId() { return cpuDeckId; }

    /** @deprecated Use {@link #getPlayerDeckId()} instead. */
    @Deprecated
    public int getSelectedDeckId() { return playerDeckId; }

    /** The format the game was set up in. */
    public DeckFormat getFormat() { return format; }

    private List<DeckSummary> loadDecks() {
        try (DeckDatabase db = new DeckDatabase()) {
            return db.getDecksSummary();
        } catch (SQLException e) {
            JOptionPane.showMessageDialog(this, "Error loading decks:\n" + e.getMessage(),
                    "Database Error", JOptionPane.ERROR_MESSAGE);
            return List.of();
        }
    }

    /** Re-reads which decks the format and banlist refuse. */
    private void applyRules(DeckFormat newFormat, boolean banlist) {
        format = newFormat;
        try (DeckDatabase db = new DeckDatabase()) {
            refused = DeckRules.refusals(db, decks, newFormat, banlist);
        } catch (SQLException e) {
            refused = Map.of();
            JOptionPane.showMessageDialog(this, "Error checking decks against the format:\n"
                    + e.getMessage(), "Database Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private boolean isEligible(DeckSummary d) {
        return d.mainCardCount() == 50 && !refused.containsKey(d.id());
    }

    private class DeckListRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(
                JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            setToolTipText(null);
            if (value instanceof DeckSummary d) {
                setText(d.name() + "  (" + d.mainCardCount() + " / 50"
                        + (d.lbCardCount() > 0 ? " +" + d.lbCardCount() + " LB" : "") + ")");
                String why = refused.get(d.id());
                if (why != null) {
                    setForeground(BANNED_FG);
                    setBackground(list.getBackground());
                    setToolTipText(why);
                } else if (d.mainCardCount() != 50) {
                    setForeground(Color.GRAY);
                    setBackground(list.getBackground());
                }
            }
            return this;
        }
    }
}
