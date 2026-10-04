package shufflingway.dialog;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.swing.JComponent;
import javax.swing.Timer;

import shufflingway.ElementColor;
import shufflingway.FontLoader;

/**
 * The CP gauge at the top of a payment window: one cell per CP the cost needs, so the player can
 * see which Elements went into a cast.
 *
 * <p>Cells an Element requirement pins (the 《Fire》 of a Fire card) come first, tinted in that
 * Element's colour until paid; the rest are generic and start empty. Each payment fills cells in
 * the order it was selected — its own Element's pinned cells first, then generic ones — in the
 * colour of the CP it produced. A filled pinned cell lights up on its own; the whole bar lights up
 * once the window says the payment can proceed.
 *
 * <p>The gauge only draws what it is told. Which Element a payment produced, and whether the
 * payment is complete, are the window's decisions, made by the same accounting that enables
 * Confirm, so the two cannot disagree.
 */
public class CpPaymentBar extends JComponent {

    /**
     * CP produced by one payment source.
     *
     * @param key     identifies the source across updates (a backup slot, a hand index), so a
     *                payment keeps its cells while others come and go
     * @param element the Element of the CP produced; a pinned cell only takes its own Element
     * @param amount  how much CP the source produced
     */
    public record Contribution(String key, String element, int amount) {}

    private static final int   BAR_H  = 18;
    private static final int   PAD    = 5;
    /** Room to the right of the bar for the "total / cost" count. */
    private static final int   COUNT_W = 44;
    private static final Color TRACK_TOP    = new Color(30, 30, 34);
    private static final Color TRACK_BOTTOM = new Color(54, 54, 60);
    private static final Color GLOW         = new Color(255, 226, 130);
    private static final Color GENERIC_FILL = new Color(150, 150, 150);

    private final int      cost;
    /** The Element each cell is pinned to, or null for a generic cell. */
    private final String[] pinned;
    /** Sources in the order they were first seen; fills are laid out in this order. */
    private final List<String> arrival = new ArrayList<>();

    private final Color[]   targetColor;
    private final Color[]   shownColor;
    private final float[]   level;
    private final float[]   lit;
    private boolean complete;
    private float   glow;
    private float   phase;
    private int     total;

    private final Timer timer = new Timer(16, e -> tick());

    /**
     * @param cost   the CP the cost needs
     * @param pinned Elements the cost requires, mapped to how many cells each pins; the bar is
     *               widened to fit them when they outnumber {@code cost} (a multi-Element card
     *               still needs 1 CP of each of its Elements)
     */
    public CpPaymentBar(int cost, Map<String, Integer> pinned) {
        this.cost = cost;
        int pinnedCells = pinned.values().stream().mapToInt(Integer::intValue).sum();
        int cells = Math.max(1, Math.max(cost, pinnedCells));
        this.pinned = new String[cells];
        int i = 0;
        for (Map.Entry<String, Integer> e : pinned.entrySet())
            for (int n = 0; n < e.getValue() && i < cells; n++) this.pinned[i++] = e.getKey();
        targetColor = new Color[cells];
        shownColor  = new Color[cells];
        level       = new float[cells];
        lit         = new float[cells];
        setFont(FontLoader.loadPixelFont(11));
        setForeground(new Color(60, 60, 60));
        setOpaque(false);
    }

    /**
     * Shows the current payment.
     *
     * @param paid     every source currently selected, with the Element it produced
     * @param complete whether the payment can proceed — the window's Confirm state
     */
    public void update(List<Contribution> paid, boolean complete) {
        Map<String, Contribution> byKey = new LinkedHashMap<>();
        for (Contribution c : paid) byKey.put(c.key(), c);
        arrival.retainAll(byKey.keySet());
        for (String k : byKey.keySet()) if (!arrival.contains(k)) arrival.add(k);

        Color[] next = new Color[pinned.length];
        total = 0;
        for (String k : arrival) {
            Contribution c = byKey.get(k);
            total += c.amount();
            int left = c.amount();
            for (int i = 0; i < pinned.length && left > 0; i++)
                if (next[i] == null && pinned[i] != null && pinned[i].equalsIgnoreCase(c.element())) {
                    next[i] = colorOf(c.element());
                    left--;
                }
            for (int i = 0; i < pinned.length && left > 0; i++)
                if (next[i] == null && pinned[i] == null) {
                    next[i] = colorOf(c.element());
                    left--;
                }
        }
        System.arraycopy(next, 0, targetColor, 0, next.length);
        this.complete = complete;
        if (!timer.isRunning()) timer.start();
        repaint();
    }

    /** The colour a cell of {@code element} CP is drawn in. */
    static Color colorOf(String element) {
        ElementColor ec = ElementColor.fromName(element);
        return ec == null ? GENERIC_FILL : ec.color;
    }

    private void tick() {
        boolean settled = true;
        // One fill front sweeps left to right and one drain front right to left, so a payment
        // spanning several cells (a 2 CP discard) reads as a single motion rather than each cell
        // filling at once. Each front eases out over the whole distance it has left to travel.
        float toFill = 0f, toDrain = 0f;
        for (int i = 0; i < pinned.length; i++) {
            if (targetColor[i] != null) toFill += 1f - level[i];
            else                        toDrain += level[i];
        }
        float fillStep = frontStep(toFill), drainStep = frontStep(toDrain);
        for (int i = 0; i < pinned.length && fillStep > 0f; i++) {
            if (targetColor[i] == null || level[i] >= 1f) continue;
            float d = Math.min(1f - level[i], fillStep);
            level[i] = level[i] + d > 0.999f ? 1f : level[i] + d;
            fillStep -= d;
        }
        for (int i = pinned.length - 1; i >= 0 && drainStep > 0f; i--) {
            if (targetColor[i] != null || level[i] <= 0f) continue;
            float d = Math.min(level[i], drainStep);
            level[i] = level[i] - d < 0.001f ? 0f : level[i] - d;
            drainStep -= d;
        }
        boolean arrived = true;
        for (int i = 0; i < pinned.length; i++) {
            boolean filled = targetColor[i] != null;
            if (filled) {
                shownColor[i] = shownColor[i] == null ? targetColor[i] : blend(shownColor[i], targetColor[i], 0.25f);
                if (!shownColor[i].equals(targetColor[i])) settled = false;
            }
            if (level[i] != (filled ? 1f : 0f)) arrived = false;
            boolean wantLit = filled && level[i] >= 1f && (pinned[i] != null || complete);
            lit[i] = approach(lit[i], wantLit ? 1f : 0f, 0.18f);
            if (lit[i] != (wantLit ? 1f : 0f)) settled = false;
        }
        if (!arrived) settled = false;
        // The whole bar lights only once the fill front has reached the end.
        glow = approach(glow, complete && arrived ? 1f : 0f, 0.12f);
        if (glow > 0f) {
            phase += 0.045f;
            settled = false;
        }
        if (settled) timer.stop();
        repaint();
    }

    /** How far a front with {@code remaining} cells left to cover moves this tick. */
    private static float frontStep(float remaining) {
        if (remaining <= 0f) return 0f;
        return Math.min(remaining, Math.max(0.06f, remaining * 0.18f));
    }

    /** Eases {@code from} a fraction of the way toward {@code to}, snapping once close. */
    private static float approach(float from, float to, float rate) {
        float v = from + (to - from) * rate;
        return Math.abs(to - v) < 0.01f ? to : v;
    }

    private static Color blend(Color a, Color b, float t) {
        return new Color(
                Math.round(a.getRed()   + (b.getRed()   - a.getRed())   * t),
                Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * t),
                Math.round(a.getBlue()  + (b.getBlue()  - a.getBlue())  * t),
                Math.round(a.getAlpha() + (b.getAlpha() - a.getAlpha()) * t));
    }

    private static Color withAlpha(Color c, int alpha) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.max(0, Math.min(255, alpha)));
    }

    @Override public Dimension getPreferredSize() {
        return new Dimension(2 * 130 + COUNT_W + 2 * PAD, BAR_H + 2 * PAD);
    }

    @Override public void removeNotify() {
        timer.stop();
        super.removeNotify();
    }

    @Override protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        // The bar takes half the room it is given, centred with its count beside it.
        float w = (getWidth() - 2f * PAD - COUNT_W) / 2f, h = BAR_H;
        float x = (getWidth() - w - COUNT_W) / 2f, y = (getHeight() - BAR_H) / 2f;
        Shape track = new RoundRectangle2D.Float(x, y, w, h, h, h);

        // Outer glow once the payment can proceed, breathing gently.
        if (glow > 0f) {
            float pulse = 0.75f + 0.25f * (float) Math.sin(phase * 2 * Math.PI);
            for (int r = PAD; r >= 1; r--) {
                int a = Math.round(glow * pulse * 70f / r);
                g2.setColor(withAlpha(GLOW, a));
                g2.fill(new RoundRectangle2D.Float(x - r, y - r, w + 2 * r, h + 2 * r, h + 2 * r, h + 2 * r));
            }
        }

        g2.setPaint(new GradientPaint(0, y, TRACK_TOP, 0, y + h, TRACK_BOTTOM));
        g2.fill(track);

        Shape oldClip = g2.getClip();
        g2.clip(track);
        int n = pinned.length;
        for (int i = 0; i < n; i++) {
            float cx = x + w * i / n, cw = w * (i + 1) / n - w * i / n;
            if (pinned[i] != null) {
                g2.setColor(withAlpha(colorOf(pinned[i]), 70));
                g2.fill(new Rectangle2D.Float(cx, y, cw, h));
            }
            if (level[i] > 0f && shownColor[i] != null) {
                Color base = shownColor[i];
                Color top  = blend(base, Color.WHITE, 0.25f + 0.2f * lit[i]);
                Color bot  = blend(base, Color.BLACK, 0.25f - 0.15f * lit[i]);
                g2.setPaint(new GradientPaint(0, y, top, 0, y + h, bot));
                g2.fill(new Rectangle2D.Float(cx, y, cw * level[i], h));
                if (lit[i] > 0f) {
                    g2.setColor(withAlpha(Color.WHITE, Math.round(70 * lit[i])));
                    g2.fill(new Rectangle2D.Float(cx, y + 2, cw * level[i], h * 0.32f));
                }
            }
        }

        // A band of light sweeping across the full bar while it is lit.
        if (glow > 0f) {
            float sweep = (phase * 0.6f) % 1.6f - 0.3f;
            float bx = x + w * sweep, bw = w * 0.22f;
            g2.setPaint(new GradientPaint(bx, 0, withAlpha(Color.WHITE, 0),
                    bx + bw / 2, 0, withAlpha(Color.WHITE, Math.round(90 * glow)), true));
            g2.fill(new Rectangle2D.Float(bx, y, bw, h));
        }

        // Cell dividers; the edge between pinned and generic cells is drawn a little firmer.
        for (int i = 1; i < n; i++) {
            float dx = x + w * i / n;
            boolean groupEdge = pinned[i - 1] != null && pinned[i] == null;
            g2.setColor(new Color(0, 0, 0, groupEdge ? 160 : 100));
            g2.fill(new Rectangle2D.Float(dx - 0.5f, y, groupEdge ? 1.5f : 1f, h));
            g2.setColor(new Color(255, 255, 255, 28));
            g2.fill(new Rectangle2D.Float(dx + 0.5f, y, 1f, h));
        }
        g2.setClip(oldClip);

        g2.setStroke(new BasicStroke(1.2f));
        g2.setColor(blend(new Color(18, 18, 20), GLOW, glow));
        g2.draw(track);

        // The count sits beside the bar rather than on it, where it would straddle a divider.
        String text = total + " / " + cost;
        g2.setFont(getFont());
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
        FontMetrics fm = g2.getFontMetrics();
        int tx = Math.round(x + w + PAD + (COUNT_W - fm.stringWidth(text)) / 2f);
        int ty = Math.round(y + (h - fm.getHeight()) / 2f) + fm.getAscent();
        g2.setColor(blend(getForeground(), new Color(150, 110, 0), glow));
        g2.drawString(text, tx, ty);
        g2.dispose();
    }
}
