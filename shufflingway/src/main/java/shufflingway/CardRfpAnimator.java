package shufflingway;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * Transparent overlay that renders "removed from game" animations:
 *   1. Card collapses uniformly toward its center while brightening to white.
 *   2. A radial white flash bursts outward as the card vanishes.
 *   3. Eight 4-pointed sparkles (alternating white / gold / purple) drift
 *      outward and fade.
 */
class CardRfpAnimator extends JComponent {

    static final int FRAME_MS      = 16;
    static final int TOTAL_FRAMES  = 30;   // 480 ms total

    private static final int COLLAPSE_FRAMES = 12;  // frames 1–12: card scales to 0
    private static final int FLASH_START     =  8;  // flash overlaps end of collapse
    private static final int FLASH_END       = 20;
    private static final int SPARKLE_START   = 10;
    private static final int SPARKLE_COUNT   =  8;

    // Alternating: white, gold, white, lavender, white, gold, white, lavender
    private static final int[] SP_R = { 255, 255, 255, 200, 255, 255, 255, 180 };
    private static final int[] SP_G = { 255, 215, 255, 160, 255, 215, 255, 130 };
    private static final int[] SP_B = { 255,   0, 255, 255, 255,   0, 255, 255 };

    private static class Rfp {
        final BufferedImage img;
        final Point         center;
        int frame;  // starts at 0; first rendered value is 1

        Rfp(BufferedImage img, Point center) {
            this.img    = img;
            this.center = center;
        }
    }

    private final List<Rfp> rfps  = new ArrayList<>();
    private final Timer     timer;

    CardRfpAnimator() {
        setOpaque(false);
        setFocusable(false);
        timer = new Timer(FRAME_MS, e -> tick());
        timer.setCoalesce(true);
    }

    static CardRfpAnimator install(JFrame frame) {
        CardRfpAnimator a  = new CardRfpAnimator();
        JLayeredPane    lp = frame.getRootPane().getLayeredPane();
        a.setBounds(0, 0, lp.getWidth(), lp.getHeight());
        lp.add(a, JLayeredPane.DRAG_LAYER);
        lp.addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent e) {
                a.setBounds(0, 0, lp.getWidth(), lp.getHeight());
            }
        });
        return a;
    }

    void startRfp(BufferedImage img, Point center) {
        rfps.add(new Rfp(img, center));
        if (!timer.isRunning()) timer.start();
    }

    @Override public boolean contains(int x, int y) { return false; }

    private void tick() {
        rfps.removeIf(r -> { r.frame++; return r.frame >= TOTAL_FRAMES; });
        if (rfps.isEmpty()) timer.stop();
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        if (rfps.isEmpty()) return;
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,  RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        for (Rfp anim : rfps) renderRfp(g2, anim);
        g2.dispose();
    }

    private void renderRfp(Graphics2D g2, Rfp anim) {
        int w  = anim.img.getWidth();
        int h  = anim.img.getHeight();
        int cx = anim.center.x;
        int cy = anim.center.y;
        int f  = anim.frame;

        float maxFlash   = Math.max(w, h) * 0.55f;
        float maxTravel  = Math.max(w, h) * 0.38f;

        // Phase 1 — card collapses and whitens
        if (f <= COLLAPSE_FRAMES) {
            double t     = (double) f / COLLAPSE_FRAMES;
            double ease  = t * t;                       // quadratic: slow start, fast end
            double scale = 1.0 - ease;
            float  alpha = (float)(1.0 - ease);

            Graphics2D cg = (Graphics2D) g2.create();
            cg.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
            AffineTransform at = new AffineTransform();
            at.translate(cx, cy);
            at.scale(scale, scale);
            at.translate(-w / 2.0, -h / 2.0);
            cg.drawImage(anim.img, at, null);

            // White wash builds up as card collapses
            float whiteAlpha = Math.min((float)(ease * 0.85), 0.85f);
            if (whiteAlpha > 0.01f && scale > 0.0) {
                int dw = (int) Math.round(w * scale);
                int dh = (int) Math.round(h * scale);
                cg.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, whiteAlpha));
                cg.setColor(Color.WHITE);
                cg.fillRect(cx - dw / 2, cy - dh / 2, dw, dh);
            }
            cg.dispose();
        }

        // Phase 2 — radial flash burst (white-to-gold gradient)
        if (f >= FLASH_START && f < FLASH_END) {
            double ft     = (double)(f - FLASH_START) / (FLASH_END - FLASH_START);
            float  fAlpha = (float)(Math.sin(ft * Math.PI) * 0.85);
            float  radius = (float)((1.0 - (1.0 - ft) * (1.0 - ft)) * maxFlash);

            if (radius > 0.5f && fAlpha > 0.01f) {
                Graphics2D fg = (Graphics2D) g2.create();
                fg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                float[] fracs  = { 0f, 0.35f, 1f };
                Color[] cols   = {
                    new Color(1f, 1f,    1f,    fAlpha),
                    new Color(1f, 0.92f, 0.55f, fAlpha * 0.5f),
                    new Color(1f, 1f,    1f,    0f)
                };
                RadialGradientPaint rgp = new RadialGradientPaint(
                    (float) cx, (float) cy, radius, fracs, cols);
                fg.setPaint(rgp);
                int ri = (int) radius;
                fg.fillOval(cx - ri, cy - ri, ri * 2, ri * 2);
                fg.dispose();
            }
        }

        // Phase 3 — sparkles drift outward and fade
        if (f >= SPARKLE_START) {
            int    sparkleLifetime = TOTAL_FRAMES - SPARKLE_START;
            double st       = (double)(f - SPARKLE_START) / sparkleLifetime;
            float  spAlpha  = (float)(1.0 - st);
            double travel   = st * maxTravel;
            float  armLen   = (float)(Math.sin(st * Math.PI) * 10.0);

            for (int i = 0; i < SPARKLE_COUNT; i++) {
                double angle = i * (2.0 * Math.PI / SPARKLE_COUNT);
                int    sx    = (int)(cx + Math.cos(angle) * travel);
                int    sy    = (int)(cy + Math.sin(angle) * travel);
                int    alpha = Math.round(spAlpha * 220);
                if (alpha <= 0 || armLen < 0.5f) continue;

                Graphics2D sg = (Graphics2D) g2.create();
                sg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                sg.setColor(new Color(SP_R[i], SP_G[i], SP_B[i], alpha));
                sg.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                int ai = (int) armLen;
                int di = (int)(armLen * 0.6f);
                sg.drawLine(sx - ai, sy,      sx + ai, sy);       // horizontal
                sg.drawLine(sx,      sy - ai, sx,      sy + ai);  // vertical
                sg.drawLine(sx - di, sy - di, sx + di, sy + di);  // diagonal \
                sg.drawLine(sx - di, sy + di, sx + di, sy - di);  // diagonal /
                sg.dispose();
            }
        }
    }
}
