package shufflingway.dialog;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.sql.SQLException;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;

import scraper.DeckDatabase;
import scraper.DeckDatabase.DeckSummary;

/**
 * A single-deck picker: the list of local decks, with anything short of a legal 50-card main
 * deck greyed out and unselectable. Used by the multiplayer lobbies, where each player picks
 * only their own deck.
 */
public class DeckChooserPanel extends JPanel {

    private final JList<DeckSummary> deckList;

    /**
     * @param title      border title, e.g. "Your Deck"
     * @param onSelected run whenever the selection changes; check {@link #getSelectedDeckId()}
     */
    public DeckChooserPanel(String title, Runnable onSelected) {
        super(new BorderLayout(0, 4));
        setBorder(BorderFactory.createTitledBorder(title));

        DefaultListModel<DeckSummary> model = new DefaultListModel<>();
        for (DeckSummary d : loadDecks()) model.addElement(d);

        deckList = new JList<>(model);
        deckList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        deckList.setCellRenderer(new DeckListRenderer());
        deckList.setFixedCellHeight(24);
        deckList.addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) return;
            DeckSummary sel = deckList.getSelectedValue();
            // Illegal decks are shown for context but cannot be taken into a game.
            if (sel != null && sel.mainCardCount() != 50) deckList.clearSelection();
            else if (onSelected != null) onSelected.run();
        });

        JScrollPane scroll = new JScrollPane(deckList);
        scroll.setPreferredSize(new Dimension(300, 130));
        add(scroll, BorderLayout.CENTER);
    }

    /** Locks or unlocks the list; the selection is kept either way. */
    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        deckList.setEnabled(enabled);
    }

    /** The chosen deck's ID, or -1 if nothing legal is selected. */
    public int getSelectedDeckId() {
        DeckSummary sel = deckList.getSelectedValue();
        return sel == null ? -1 : sel.id();
    }

    /** The chosen deck's name, or {@code null} if nothing is selected. */
    public String getSelectedDeckName() {
        DeckSummary sel = deckList.getSelectedValue();
        return sel == null ? null : sel.name();
    }

    private List<DeckSummary> loadDecks() {
        try (DeckDatabase db = new DeckDatabase()) {
            return db.getDecksSummary();
        } catch (SQLException e) {
            JOptionPane.showMessageDialog(this, "Error loading decks:\n" + e.getMessage(),
                    "Database Error", JOptionPane.ERROR_MESSAGE);
            return List.of();
        }
    }

    private static class DeckListRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(
                JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (value instanceof DeckSummary d) {
                setText(d.name() + "  (" + d.mainCardCount() + " / 50"
                        + (d.lbCardCount() > 0 ? " +" + d.lbCardCount() + " LB" : "") + ")");
                if (d.mainCardCount() != 50) {
                    setForeground(Color.GRAY);
                    setBackground(list.getBackground());
                }
            }
            return this;
        }
    }
}
