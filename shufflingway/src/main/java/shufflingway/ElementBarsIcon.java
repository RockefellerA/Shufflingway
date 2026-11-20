package shufflingway;

import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import javax.swing.Icon;
import javax.swing.JTable;
import javax.swing.table.DefaultTableCellRenderer;

/**
 * A row of small upright bars, one per Element, sized to the text it sits beside: a deck's
 * Elements ({@link DeckElements}) before its name wherever decks are listed, and a card's in the
 * Element columns of the Deck Manager and the Card Browser.
 *
 * <p>Each bar is its Element's board colour, with three exceptions. At this size the board's
 * grey-blue Water reads as Ice, so Water takes a deeper blue. Light and Dark, which only a card's
 * bars show, are white and black as printed. Every bar has a darker edge so the pale ones (Earth,
 * Ice, Light) stand out on a light background, except Dark's, which is grey so it still shows on a
 * dark one.
 */
public final class ElementBarsIcon implements Icon {

    private static final Color WATER_BAR = new Color(0x2A, 0x6C, 0xC6);
    private static final Color DARK_EDGE = new Color(0x80, 0x80, 0x80);

    private final List<Color> fills = new ArrayList<>();
    private final List<Color> edges = new ArrayList<>();
    private final int barW, gap, barH;

    public ElementBarsIcon(Collection<ElementColor> elements, int fontSize) {
        for (ElementColor ec : elements) {
            Color fill = switch (ec) {
                case WATER -> WATER_BAR;
                case LIGHT -> Color.WHITE;
                case DARK  -> Color.BLACK;
                default    -> ec.color;
            };
            fills.add(fill);
            edges.add(ec == ElementColor.DARK ? DARK_EDGE : fill.darker());
        }
        barH = Math.max(8, Math.round(fontSize * 0.9f));
        barW = Math.max(3, Math.round(barH * 0.35f));
        gap  = Math.max(1, Math.round(barH * 0.15f));
    }

    /**
     * A card's Elements from its Element text ("Fire", "Lightning/Water"), in the order the text
     * names them, Light and Dark included. Empty for a card with no Element.
     */
    public static List<ElementColor> elementsOf(String elementText) {
        List<ElementColor> out = new ArrayList<>();
        if (elementText == null) return out;
        for (String part : elementText.split("/")) {
            ElementColor ec = ElementColor.fromName(part);
            if (ec != null) out.add(ec);
        }
        return out;
    }

    /** A renderer for an Element column: the card's bars ahead of its Element text. */
    public static DefaultTableCellRenderer columnRenderer() {
        return new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(
                    JTable t, Object value, boolean sel, boolean focus, int row, int col) {
                super.getTableCellRendererComponent(t, value, sel, focus, row, col);
                List<ElementColor> elements = elementsOf(value == null ? null : value.toString());
                setIcon(elements.isEmpty() ? null : new ElementBarsIcon(elements, t.getFont().getSize()));
                return this;
            }
        };
    }

    @Override public int getIconWidth()  { return fills.size() * (barW + gap) - gap; }
    @Override public int getIconHeight() { return barH; }

    @Override
    public void paintIcon(Component c, Graphics g, int x, int y) {
        for (int i = 0; i < fills.size(); i++) {
            int bx = x + i * (barW + gap);
            g.setColor(fills.get(i));
            g.fillRect(bx, y, barW, barH);
            g.setColor(edges.get(i));
            g.drawRect(bx, y, barW - 1, barH - 1);
        }
    }
}
