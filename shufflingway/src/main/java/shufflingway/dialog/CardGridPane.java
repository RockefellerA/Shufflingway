package shufflingway.dialog;

import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.JPanel;
import javax.swing.JScrollPane;

/**
 * The card area of a zone viewer — the Break Zone, Removed From Play: a grid up to
 * {@link #COLUMNS} cards across with as many rows as the zone needs, sized to show
 * {@link #VISIBLE_ROWS} rows and scrolling vertically past that. Never horizontally: a long zone
 * grows downward instead of into a strip wider than the screen, and a short one is only as wide
 * as its cards.
 */
final class CardGridPane {

    static final int COLUMNS      = 5;
    static final int VISIBLE_ROWS = 2;
    private static final int GAP  = 8;

    private CardGridPane() {}

    /**
     * Lays {@code cells} out in the grid. Every cell is the size the first one asks for, which
     * holds for the viewers' card-plus-caption cells; the grid would stretch a smaller one.
     */
    static JScrollPane of(List<? extends Component> cells) {
        int cols = Math.max(1, Math.min(COLUMNS, cells.size()));
        int rows = (cells.size() + cols - 1) / cols;

        JPanel grid = new JPanel(new GridLayout(0, cols, GAP, GAP));
        grid.setBorder(BorderFactory.createEmptyBorder(GAP, GAP, GAP, GAP));
        for (Component c : cells) grid.add(c);

        Dimension cell  = cells.isEmpty() ? new Dimension(0, 0) : cells.get(0).getPreferredSize();
        int       shown = Math.max(1, Math.min(rows, VISIBLE_ROWS));
        int w = cols  * cell.width  + (cols  - 1) * GAP + 2 * GAP;
        int h = shown * cell.height + (shown - 1) * GAP + 2 * GAP;

        JScrollPane scroll = new JScrollPane(grid,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        // The viewport is sized, not the pane: the pane then adds room for the scrollbar itself
        // when the grid is taller than the viewport, rather than taking it out of the cards.
        scroll.getViewport().setPreferredSize(new Dimension(w, h));
        // A wheel notch moves a fraction of a row; the default single pixel barely moves at all.
        scroll.getVerticalScrollBar().setUnitIncrement(Math.max(16, cell.height / 6));
        return scroll;
    }
}
