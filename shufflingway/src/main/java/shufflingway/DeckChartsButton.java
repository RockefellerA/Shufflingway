package shufflingway;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Rectangle2D;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.Popup;
import javax.swing.PopupFactory;
import javax.swing.UIManager;

/**
 * The Deck Manager's chart button, under the deck list: a small Element pie beside a small cost
 * curve, shapes only, without figures. Resting the pointer on either one opens that chart full
 * size just above the button, with its figures: the pie with each Element's card count, the curve
 * with the number of cards at each cost. The popup follows the pointer from one half to the other
 * and closes when the pointer leaves the button.
 *
 * <p>The charts count the main deck only; see {@link DeckStats}.
 */
final class DeckChartsButton extends JButton {

    private enum Chart { ELEMENTS, COSTS }

    // The button's preview: a pie and a row of bars, the pie's diameter the bars' full height.
    private static final int MINI_H      = UiScale.scale(16);
    private static final int MINI_GAP    = UiScale.scale(6);
    /** The bars share a fixed width, so the button keeps its size however many costs the axis has. */
    private static final int MINI_BARS_W = UiScale.scale(30);

    // The full-size charts.
    private static final int PIE_D       = UiScale.scale(150);
    private static final int CURVE_H     = UiScale.scale(130);
    private static final int CURVE_BAR_W = UiScale.scale(20);
    private static final int CURVE_GAP   = UiScale.scale(6);
    private static final int PAD         = UiScale.scale(10);
    private static final int SWATCH      = UiScale.scale(10);

    /** The cost curve's bars: one blue, which reads on the light and the dark themes alike. */
    private static final Color BAR  = new Color(0x4F8FD6);
    /** Around slices, swatches and bars, so Dark and Light still stand out on a theme near their colour. */
    private static final Color EDGE = new Color(0, 0, 0, 110);

    private DeckStats stats = DeckStats.EMPTY;
    private Popup popup;
    private Chart showing;

    DeckChartsButton() {
        setIcon(new MiniCharts());
        setFocusable(false);
        setMargin(new Insets(2, 6, 2, 6));
        getAccessibleContext().setAccessibleName("Deck charts: Elements and cost curve");
        MouseAdapter hover = new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) { show(chartAt(e.getX())); }
            @Override public void mouseMoved(MouseEvent e)   { show(chartAt(e.getX())); }
            @Override public void mouseExited(MouseEvent e)  { hidePopup(); }
        };
        addMouseListener(hover);
        addMouseMotionListener(hover);
    }

    /** Shows {@code stats}, in the preview and in a chart that is open. */
    void setStats(DeckStats stats) {
        this.stats = stats;
        repaint();
        if (showing != null) {
            // Reopened rather than repainted: a new Element can make the legend taller.
            Chart open = showing;
            hidePopup();
            show(open);
        }
    }

    /** The chart under {@code x}: the pie on the left of the gap between them, the curve on the right. */
    private Chart chartAt(int x) {
        int iconLeft = (getWidth() - getIcon().getIconWidth()) / 2;
        return x < iconLeft + MINI_H + MINI_GAP / 2 ? Chart.ELEMENTS : Chart.COSTS;
    }

    private void show(Chart chart) {
        if (chart == showing && popup != null) return;
        hidePopup();
        if (!isShowing()) return;
        ChartPanel content = new ChartPanel(chart);
        Dimension size = content.getPreferredSize();
        // Above the button, left edges level; below it instead if the screen's top is in the way.
        Point at = getLocationOnScreen();
        Rectangle screen = getGraphicsConfiguration().getBounds();
        int x = Math.max(screen.x, Math.min(at.x, screen.x + screen.width - size.width));
        int y = at.y - size.height - UiScale.scale(4);
        if (y < screen.y) y = at.y + getHeight() + UiScale.scale(4);
        popup = PopupFactory.getSharedInstance().getPopup(this, content, x, y);
        popup.show();
        showing = chart;
    }

    private void hidePopup() {
        if (popup != null) popup.hide();
        popup = null;
        showing = null;
    }

    @Override public void removeNotify() {
        hidePopup();
        super.removeNotify();
    }

    // -------------------------------------------------------------------------
    // Drawing, at either size
    // -------------------------------------------------------------------------

    /** The pie of {@code stats}'s Elements in a circle {@code d} across at ({@code x}, {@code y}); a ring when empty. */
    private static void paintPie(Graphics2D g, DeckStats stats, double x, double y, double d, Color gaps) {
        int total = 0;
        for (int n : stats.elements().values()) total += n;
        Ellipse2D circle = new Ellipse2D.Double(x, y, d, d);
        if (total == 0) {
            g.setColor(EDGE);
            g.setStroke(new BasicStroke(1f));
            g.draw(circle);
            return;
        }
        // Clockwise from twelve o'clock, in the game's Element order.
        double start = 90;
        for (Map.Entry<ElementColor, Integer> e : stats.elements().entrySet()) {
            double extent = -360.0 * e.getValue() / total;
            g.setColor(e.getKey().color);
            g.fill(new Arc2D.Double(x, y, d, d, start, extent, Arc2D.PIE));
            start += extent;
        }
        if (gaps != null && stats.elements().size() > 1) {
            g.setColor(gaps);
            g.setStroke(new BasicStroke(1.5f));
            start = 90;
            for (int n : stats.elements().values()) {
                double extent = -360.0 * n / total;
                g.draw(new Arc2D.Double(x, y, d, d, start, extent, Arc2D.PIE));
                start += extent;
            }
        }
        g.setColor(EDGE);
        g.setStroke(new BasicStroke(1f));
        g.draw(circle);
    }

    /** Elements' names as the game prints them: "Lightning", not "LIGHTNING". */
    private static String elementName(ElementColor ec) {
        String n = ec.name();
        return n.charAt(0) + n.substring(1).toLowerCase(Locale.ROOT);
    }

    /** The preview on the button: the pie, then the bars, all shapes and no figures. */
    private final class MiniCharts implements Icon {
        @Override public int getIconWidth()  { return MINI_H + MINI_GAP + MINI_BARS_W; }
        @Override public int getIconHeight() { return MINI_H; }

        @Override public void paintIcon(Component c, Graphics g0, int x, int y) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            paintPie(g, stats, x + 0.5, y + 0.5, MINI_H - 1, null);

            List<Integer> axis = stats.costAxis();
            int tallest = stats.tallestCost();
            double slot = MINI_BARS_W / (double) axis.size();
            double barW = Math.max(1, slot * 0.75);
            double left = x + MINI_H + MINI_GAP, base = y + MINI_H;
            g.setColor(BAR);
            for (int i = 0; i < axis.size(); i++) {
                int n = stats.atCost(axis.get(i));
                if (n == 0 || tallest == 0) continue;
                double h = Math.max(1, (MINI_H - 1) * n / (double) tallest);
                g.fill(new Rectangle2D.Double(left + i * slot, base - h, barW, h));
            }
            g.setColor(EDGE);
            g.setStroke(new BasicStroke(1f));
            g.draw(new Line2D.Double(left, base - 0.5, left + MINI_BARS_W, base - 0.5));
            g.dispose();
        }
    }

    /** One chart at full size, with its figures, as the popup shows it. */
    private final class ChartPanel extends JComponent {
        private final Chart chart;
        private final Font font  = UIManager.getFont("Label.font");
        private final Font title = font.deriveFont(Font.BOLD);

        ChartPanel(Chart chart) {
            this.chart = chart;
            setOpaque(true);
            Color line = UIManager.getColor("Separator.foreground");
            setBorder(BorderFactory.createLineBorder(line != null ? line : Color.GRAY));
        }

        private Color text() {
            Color c = UIManager.getColor("Label.foreground");
            return c != null ? c : Color.BLACK;
        }

        private Color dim() {
            Color c = UIManager.getColor("Label.disabledForeground");
            return c != null ? c : Color.GRAY;
        }

        @Override public Dimension getPreferredSize() {
            FontMetrics fm = getFontMetrics(font), tm = getFontMetrics(title);
            int head = tm.getHeight() + UiScale.scale(6);
            if (chart == Chart.ELEMENTS) {
                // A swatch, a gap, the longest Element name, a gap and room for a three-digit count.
                int legendW = 0;
                for (ElementColor ec : stats.elements().keySet())
                    legendW = Math.max(legendW, SWATCH + UiScale.scale(14) + fm.stringWidth(elementName(ec)) + fm.stringWidth("000"));
                int legendH = stats.elements().size() * (fm.getHeight() + UiScale.scale(2));
                int w = PAD + PIE_D + (legendW > 0 ? PAD + legendW : 0) + PAD;
                return new Dimension(Math.max(w, PAD * 2 + tm.stringWidth(elementsTitle())),
                        PAD + head + Math.max(PIE_D, legendH) + PAD);
            }
            int bars = stats.costAxis().size();
            int w = PAD + bars * CURVE_BAR_W + (bars - 1) * CURVE_GAP + PAD;
            return new Dimension(Math.max(w, PAD * 2 + tm.stringWidth(costsTitle())),
                    PAD + head + fm.getHeight() + CURVE_H + fm.getHeight() + PAD);
        }

        private String elementsTitle() { return "Elements (" + stats.cards() + " cards)"; }
        private String costsTitle()    { return "Cost Curve"; }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            Color bg = UIManager.getColor("Panel.background");
            g.setColor(bg != null ? bg : getBackground());
            g.fillRect(0, 0, getWidth(), getHeight());

            FontMetrics tm = g.getFontMetrics(title);
            g.setFont(title);
            g.setColor(text());
            g.drawString(chart == Chart.ELEMENTS ? elementsTitle() : costsTitle(), PAD, PAD + tm.getAscent());
            int top = PAD + tm.getHeight() + UiScale.scale(6);
            g.setFont(font);
            if (chart == Chart.ELEMENTS) paintElements(g, top, bg);
            else                         paintCosts(g, top);
            g.dispose();
        }

        private void paintElements(Graphics2D g, int top, Color bg) {
            FontMetrics fm = g.getFontMetrics();
            if (stats.cards() == 0) {
                paintPie(g, stats, PAD, top, PIE_D, bg);
                g.setColor(dim());
                String none = "No cards";
                g.drawString(none, PAD + (PIE_D - fm.stringWidth(none)) / 2, top + PIE_D / 2 + fm.getAscent() / 2);
                return;
            }
            paintPie(g, stats, PAD, top, PIE_D, bg);
            // The legend beside the pie: a swatch, the Element and its card count, in slice order.
            int lx = PAD + PIE_D + PAD, row = fm.getHeight() + UiScale.scale(2);
            int legendW = getWidth() - lx - PAD;
            int y = top + Math.max(0, (PIE_D - stats.elements().size() * row) / 2);
            for (Map.Entry<ElementColor, Integer> e : stats.elements().entrySet()) {
                int sy = y + (row - SWATCH) / 2;
                g.setColor(e.getKey().color);
                g.fillRect(lx, sy, SWATCH, SWATCH);
                g.setColor(EDGE);
                g.drawRect(lx, sy, SWATCH, SWATCH);
                int baseline = y + (row + fm.getAscent() - fm.getDescent()) / 2;
                g.setColor(text());
                g.drawString(elementName(e.getKey()), lx + SWATCH + UiScale.scale(6), baseline);
                String count = String.valueOf(e.getValue());
                g.drawString(count, lx + legendW - fm.stringWidth(count), baseline);
                y += row;
            }
        }

        private void paintCosts(Graphics2D g, int top) {
            FontMetrics fm = g.getFontMetrics();
            List<Integer> axis = stats.costAxis();
            int tallest = stats.tallestCost();
            int base = top + fm.getHeight() + CURVE_H;
            for (int i = 0; i < axis.size(); i++) {
                int cost = axis.get(i), n = stats.atCost(cost);
                int bx = PAD + i * (CURVE_BAR_W + CURVE_GAP);
                if (n > 0 && tallest > 0) {
                    int h = Math.max(1, (int) Math.round(CURVE_H * n / (double) tallest));
                    g.setColor(BAR);
                    g.fillRect(bx, base - h, CURVE_BAR_W, h);
                    g.setColor(EDGE);
                    g.drawRect(bx, base - h, CURVE_BAR_W, h);
                    String count = String.valueOf(n);
                    g.setColor(text());
                    g.drawString(count, bx + (CURVE_BAR_W - fm.stringWidth(count)) / 2, base - h - fm.getDescent() - 1);
                }
                String label = String.valueOf(cost);
                g.setColor(dim());
                g.drawString(label, bx + (CURVE_BAR_W - fm.stringWidth(label)) / 2, base + fm.getAscent() + 1);
            }
            g.setColor(dim());
            g.drawLine(PAD, base, PAD + axis.size() * (CURVE_BAR_W + CURVE_GAP) - CURVE_GAP, base);
        }
    }
}
