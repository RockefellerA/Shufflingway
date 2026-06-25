package shufflingway.graphics;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.MultipleGradientPaint;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

/**
 * Counter — a translucent, slightly-oblong glass demi-orb used to mark
 * counters on a card. Renders as a tinted glass dome lying flat on the
 * card surface: soft contact shadow, tinted body (lighter at the lit
 * center, richer toward the rim), a dark glass rim for thickness, an
 * interior caustic glow, a soft specular highlight and a crisp glint.
 *
 * The tint is fully customizable — pass any hex string ("#36b06a",
 * "36b06a", or short "#3b6"). Wire your own color picker to either the
 * {@link #setTint(String)} method (component use) or the {@code hex}
 * argument of {@link #render(int,int,String)} (static image use).
 *
 * Two ways to use it:
 *
 *  1. As a {@link JComponent} placed over a card:
 *     <pre>
 *       Counter c = new Counter("#2f7fe0");
 *       c.setBounds(x, y, 64, 56);
 *       cardLayeredPane.add(c, Integer.valueOf(5));
 *       // later, from your picker:
 *       c.setTint(pickedHex);
 *     </pre>
 *
 *  2. As a static image (e.g. for an ImageIcon or to blit onto card art):
 *     <pre>
 *       BufferedImage orb = Counter.render(64, 56, "#d23b3b");
 *     </pre>
 *
 * Run {@code java Counter} for a demo window showing several tints.
 */
public class Counter extends JComponent {

    /** Aspect ratio of the orb bounding box (slightly wider than tall). */
    public static final double DEFAULT_ASPECT = 64.0 / 56.0;

    /** Overall translucency of the glass body (0 = invisible, 1 = opaque).
     *  ~0.78 keeps it reading as solid glass while letting card art tint
     *  through faintly at the edges. Lower it for more see-through glass. */
    private double bodyAlpha = 0.78;

    private int[] tint = { 0x36, 0xb0, 0x6a }; // default green

    public Counter() {}

    public Counter(String hex) { setTint(hex); }

    // ── Public API ───────────────────────────────────────────────────────────

    /** Set the glass tint from a hex string. Accepts "#rrggbb", "rrggbb",
     *  "#rgb" or "rgb". Triggers a repaint. */
    public void setTint(String hex) {
        this.tint = hexToRgb(hex);
        repaint();
    }

    /** Set body translucency (0..1). Triggers a repaint. */
    public void setBodyAlpha(double a) {
        this.bodyAlpha = Math.max(0, Math.min(1, a));
        repaint();
    }

    public double getBodyAlpha() { return bodyAlpha; }

    @Override
    public boolean isOpaque() { return false; }

    @Override
    protected void paintComponent(Graphics g) {
        paint((Graphics2D) g, getWidth(), getHeight(), tint, bodyAlpha);
    }

    /** Render a counter into a transparent image at the given size + tint
     *  (uses the default body alpha). */
    public static BufferedImage render(int w, int h, String hex) {
        return render(w, h, hex, 0.72);
    }

    /** Render a counter into a transparent image with explicit body alpha. */
    public static BufferedImage render(int w, int h, String hex, double bodyAlpha) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        paint(g, w, h, hexToRgb(hex), bodyAlpha);
        g.dispose();
        return img;
    }

    // ── Rendering ────────────────────────────────────────────────────────────

    private static void paint(Graphics2D g0, int w, int h, int[] tint, double bodyAlpha) {
        if (w <= 0 || h <= 0) return;
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,    RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING,       RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,   RenderingHints.VALUE_INTERPOLATION_BILINEAR);

        double cx = w / 2.0, cy = h / 2.0;
        double rx = w * 0.40, ry = h * 0.40;

        // 1 ── contact shadow on the card (flattened ellipse below the orb)
        {
            Graphics2D sg = (Graphics2D) g.create();
            sg.translate(cx, cy + ry * 0.42);
            sg.scale(1.0, 0.5);
            float r = (float) (rx * 1.15);
            RadialGradientPaint sh = new RadialGradientPaint(
                new Point2D.Float(0, 0), r,
                new float[] { 0f, 0.7f, 1f },
                new Color[] {
                    new Color(0, 0, 0, 115),
                    new Color(0, 0, 0, 56),
                    new Color(0, 0, 0, 0),
                });
            sg.setPaint(sh);
            sg.fill(new Ellipse2D.Double(-r, -r, 2 * r, 2 * r));
            sg.dispose();
        }

        // Precompute tonal variants of the tint.
        int[] light = mix(tint, new int[]{255,255,255}, 0.55);
        int[] deep  = mix(tint, new int[]{0,0,0},       0.45);
        int[] midDk = mix(tint, new int[]{0,0,0},       0.20);
        int[] rimDk = mix(tint, new int[]{0,0,0},       0.55);

        Ellipse2D orb = new Ellipse2D.Double(cx - rx, cy - ry, 2 * rx, 2 * ry);

        // 2 ── glass body: radial tint, lit center offset upward
        {
            float r = (float) (rx * 1.05);
            // focus (lit point) above center; RadialGradientPaint focus must
            // sit inside the radius, which it does here.
            RadialGradientPaint body = new RadialGradientPaint(
                new Point2D.Double(cx, cy),                  // gradient center
                r,
                new Point2D.Double(cx, cy - ry * 0.35),      // focus (highlight bias)
                new float[] { 0f, 0.45f, 0.85f, 1f },
                new Color[] {
                    rgba(light, bodyAlpha * 0.95),
                    rgba(tint,  bodyAlpha),
                    rgba(midDk, bodyAlpha),
                    rgba(deep,  bodyAlpha * 0.90),
                },
                MultipleGradientPaint.CycleMethod.NO_CYCLE);
            g.setPaint(body);
            g.fill(orb);
        }

        // 3 ── dark inner rim for glass thickness
        {
            Ellipse2D rim = new Ellipse2D.Double(
                cx - rx * 0.985, cy - ry * 0.985, 2 * rx * 0.985, 2 * ry * 0.985);
            g.setStroke(new BasicStroke((float) Math.max(1.0, w * 0.02)));
            g.setColor(rgba(rimDk, 0.5));
            g.draw(rim);
        }

        // 4 ── caustic: bright crescent low inside the glass (light focusing
        //      opposite the highlight). Clipped to the orb interior.
        {
            Graphics2D cg = (Graphics2D) g.create();
            cg.clip(new Ellipse2D.Double(
                cx - rx * 0.92, cy - ry * 0.92, 2 * rx * 0.92, 2 * ry * 0.92));
            float r = (float) (rx * 0.9);
            RadialGradientPaint caustic = new RadialGradientPaint(
                new Point2D.Double(cx, cy + ry * 0.45), r,
                new float[] { 0f, 1f },
                new Color[] { rgba(light, 0.5), rgba(light, 0.0) });
            cg.setPaint(caustic);
            cg.fill(new Ellipse2D.Double(cx - r, cy + ry * 0.3 - r, 2 * r, 2 * r));
            cg.dispose();
        }

        // 5 ── primary specular highlight: soft white oblong near the top
        {
            Graphics2D hg = (Graphics2D) g.create();
            hg.translate(cx, cy - ry * 0.42);
            hg.scale(1.0, 0.62);
            float r = (float) (rx * 0.62);
            RadialGradientPaint hi = new RadialGradientPaint(
                new Point2D.Float(0, 0), r,
                new float[] { 0f, 0.6f, 1f },
                new Color[] {
                    new Color(255, 255, 255, 235),
                    new Color(255, 255, 255, 64),
                    new Color(255, 255, 255, 0),
                });
            hg.setPaint(hi);
            hg.fill(new Ellipse2D.Double(-r, -r, 2 * r, 2 * r));
            hg.dispose();
        }

        // 6 ── crisp small glint, upper-left
        {
            Graphics2D gg = (Graphics2D) g.create();
            gg.translate(cx - rx * 0.28, cy - ry * 0.5);
            gg.scale(1.0, 0.8);
            float r = (float) (rx * 0.16);
            RadialGradientPaint gl = new RadialGradientPaint(
                new Point2D.Float(0, 0), r,
                new float[] { 0f, 1f },
                new Color[] {
                    new Color(255, 255, 255, 242),
                    new Color(255, 255, 255, 0),
                });
            gg.setPaint(gl);
            gg.fill(new Ellipse2D.Double(-r, -r, 2 * r, 2 * r));
            gg.dispose();
        }

        g.dispose();
    }

    // ── Color helpers ────────────────────────────────────────────────────────

    private static int[] hexToRgb(String hex) {
        if (hex == null) return new int[]{ 0x36, 0xb0, 0x6a };
        String s = hex.trim();
        if (s.startsWith("#")) s = s.substring(1);
        if (s.length() == 3) {
            StringBuilder b = new StringBuilder();
            for (char c : s.toCharArray()) b.append(c).append(c);
            s = b.toString();
        }
        if (s.length() != 6) return new int[]{ 0x36, 0xb0, 0x6a };
        try {
            return new int[] {
                Integer.parseInt(s.substring(0, 2), 16),
                Integer.parseInt(s.substring(2, 4), 16),
                Integer.parseInt(s.substring(4, 6), 16),
            };
        } catch (NumberFormatException e) {
            return new int[]{ 0x36, 0xb0, 0x6a };
        }
    }

    private static int[] mix(int[] a, int[] b, double t) {
        return new int[] {
            (int) Math.round(a[0] + (b[0] - a[0]) * t),
            (int) Math.round(a[1] + (b[1] - a[1]) * t),
            (int) Math.round(a[2] + (b[2] - a[2]) * t),
        };
    }

    private static Color rgba(int[] rgb, double a) {
        int alpha = (int) Math.round(Math.max(0, Math.min(1, a)) * 255);
        return new Color(rgb[0], rgb[1], rgb[2], alpha);
    }

    // ── Standalone demo ──────────────────────────────────────────────────────
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            JFrame f = new JFrame("Counter demo");
            f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

            JPanel root = new JPanel(new FlowLayout(FlowLayout.LEFT, 24, 24)) {
                @Override protected void paintComponent(Graphics g) {
                    super.paintComponent(g);
                }
            };
            root.setBackground(new Color(0x2a2a2a));

            String[] tints = { "#d23b3b", "#2f7fe0", "#36b06a",
                               "#b760d8", "#e0a020", "#e8e8ef" };
            for (String hex : tints) {
                // a fake "card" so translucency is visible over artwork
                JPanel card = new JPanel(null) {
                    @Override protected void paintComponent(Graphics g) {
                        super.paintComponent(g);
                        Graphics2D g2 = (Graphics2D) g;
                        GradientPaint gp = new GradientPaint(
                            0, 0, new Color(0x6b4a2f),
                            getWidth(), getHeight(), new Color(0x3a2817));
                        g2.setPaint(gp);
                        g2.fillRoundRect(0, 0, getWidth(), getHeight(), 14, 14);
                    }
                };
                card.setPreferredSize(new Dimension(150, 210));
                card.setOpaque(false);

                Counter c = new Counter(hex);
                int cw = 70, ch = 60;
                c.setBounds((150 - cw) / 2, (210 - ch) / 2, cw, ch);
                card.add(c);

                JPanel cell = new JPanel();
                cell.setLayout(new BoxLayout(cell, BoxLayout.Y_AXIS));
                cell.setOpaque(false);
                card.setAlignmentX(Component.CENTER_ALIGNMENT);
                JLabel lbl = new JLabel(hex);
                lbl.setForeground(new Color(0xbbbbbb));
                lbl.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
                lbl.setAlignmentX(Component.CENTER_ALIGNMENT);
                lbl.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
                cell.add(card);
                cell.add(lbl);
                root.add(cell);
            }

            f.setContentPane(root);
            f.pack();
            f.setLocationRelativeTo(null);
            f.setVisible(true);
        });
    }
}
