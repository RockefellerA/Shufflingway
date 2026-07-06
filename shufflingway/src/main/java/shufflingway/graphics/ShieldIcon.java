package shufflingway.graphics;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.util.List;
import java.util.ArrayList;

/**
 * Translucent shield glyph overlaid on a damage-zone panel when
 * PLAYER_NEXT_DAMAGE_ZERO is active.  Starts invisible (GONE phase).
 *
 * <ul>
 *   <li>{@link #reset()} — show the shield at rest (call when effect is applied).</li>
 *   <li>{@link #triggerShatter()} — shield blocked a hit; burst-shard animation.</li>
 *   <li>{@link #triggerFade()} — effect expired with no hit blocked; quiet fade.</li>
 * </ul>
 */
public class ShieldIcon extends JComponent {

    private static final int[] DEFAULT_TINT = { 0x2f, 0x7f, 0xe0 };

    private static final float BODY_ALPHA       = 0.5f;
    private static final Color OUTLINE          = new Color(0xea, 0xf3, 0xff);
    private static final float OUTLINE_ALPHA    = 0.9f;
    private static final float OUTLINE_WIDTH_FRAC = 0.025f;

    private static final double[][] SHIELD_OUTLINE = buildHeraldicOutline();
    private static final int SHARD_COUNT = 6;
    private static final double CENTER_X = 50, CENTER_Y = 52;
    private static final Shard[] SHARDS = buildShards();

    // ---- State ---------------------------------------------------------------
    private int[] tint = DEFAULT_TINT;

    private enum Phase { REST, SHATTERING, FADING, GONE }
    private Phase phase = Phase.GONE;

    private float faceOpacity = 0f;
    private float wrapOpacity = 1f;
    private final float[]  shardOpacity = new float[SHARD_COUNT];
    private final double[] shardDx      = new double[SHARD_COUNT];
    private final double[] shardDy      = new double[SHARD_COUNT];
    private final double[] shardRot     = new double[SHARD_COUNT];

    private javax.swing.Timer animTimer;
    private long animStartMs;

    public ShieldIcon() {
        setOpaque(false);
    }

    // ---- Public API ----------------------------------------------------------

    public void setTint(String hex) { this.tint = hexToRgb(hex); repaint(); }

    /** Show the shield at rest — call when the effect is applied. */
    public void reset() {
        stopAnim();
        phase = Phase.REST;
        wrapOpacity = 1f;
        faceOpacity = 1f;
        for (int i = 0; i < SHARD_COUNT; i++) { shardOpacity[i] = 0f; shardDx[i] = 0; shardDy[i] = 0; shardRot[i] = 0; }
        repaint();
    }

    /** Effect blocked a hit — shard burst animation. */
    public void triggerShatter() {
        stopAnim();
        phase = Phase.SHATTERING;
        wrapOpacity = 1f;
        faceOpacity = 0f;
        for (int i = 0; i < SHARD_COUNT; i++) shardOpacity[i] = 1f;
        animStartMs = System.currentTimeMillis();
        animTimer = new javax.swing.Timer(16, e -> stepShatter());
        animTimer.start();
        repaint();
    }

    public boolean isShattering() { return phase == Phase.SHATTERING; }

    /** Effect expired with no hit blocked — quiet fade. */
    public void triggerFade() {
        stopAnim();
        if (phase == Phase.GONE) return;
        phase = Phase.FADING;
        animStartMs = System.currentTimeMillis();
        animTimer = new javax.swing.Timer(16, e -> stepFade());
        animTimer.start();
        repaint();
    }

    private void stopAnim() {
        if (animTimer != null) { animTimer.stop(); animTimer = null; }
    }

    // ---- Animation -----------------------------------------------------------

    private static final int SHATTER_STAGGER_MS   = 18;
    private static final int SHATTER_TRANSFORM_MS = 520;
    private static final int SHATTER_OPACITY_MS   = 420;

    private void stepShatter() {
        long t = System.currentTimeMillis() - animStartMs;
        boolean anyActive = false;
        for (int i = 0; i < SHARD_COUNT; i++) {
            long localT = t - (long) i * SHATTER_STAGGER_MS;
            if (localT < 0) { anyActive = true; continue; }
            Shard s = SHARDS[i];
            double dist      = 30 + (i % 3) * 8;
            double targetDx  = Math.cos(s.ang) * dist;
            double targetDy  = Math.sin(s.ang) * dist;
            double targetRot = (i % 2 != 0 ? 1 : -1) * (70 + i * 20);
            double pT = Math.min(1.0, localT / (double) SHATTER_TRANSFORM_MS);
            double oT = Math.min(1.0, localT / (double) SHATTER_OPACITY_MS);
            double pE = easeOutCubic(pT);
            shardDx[i]      = targetDx * pE;
            shardDy[i]      = targetDy * pE;
            shardRot[i]     = targetRot * pE;
            shardOpacity[i] = (float)(1.0 - easeInCubic(oT));
            if (pT < 1.0 || oT < 1.0) anyActive = true;
        }
        if (!anyActive) { stopAnim(); phase = Phase.GONE; }
        repaint();
    }

    private static final int FADE_MS = 420;

    private void stepFade() {
        long t = System.currentTimeMillis() - animStartMs;
        double p = Math.min(1.0, t / (double) FADE_MS);
        wrapOpacity = (float)(1.0 - p);
        if (p >= 1.0) { stopAnim(); phase = Phase.GONE; }
        repaint();
    }

    private static double easeOutCubic(double t) { return 1 - Math.pow(1 - t, 3); }
    private static double easeInCubic(double t)  { return t * t * t; }

    // ---- Rendering -----------------------------------------------------------

    @Override
    protected void paintComponent(Graphics g0) {
        if (phase == Phase.GONE) return;
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;

        // Draw into a square canvas sized to the shorter dimension, centered horizontally.
        int size    = Math.min(w, h);
        int offsetX = (w - size) / 2 - 1;

        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, clamp01(wrapOpacity)));
        g.translate(offsetX, 0);

        Color fill = rgba(tint, BODY_ALPHA);

        for (int i = 0; i < SHARD_COUNT; i++) {
            if (shardOpacity[i] <= 0f) continue;
            Shard s = SHARDS[i];
            Graphics2D sg = (Graphics2D) g.create();
            sg.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, clamp01(shardOpacity[i])));
            AffineTransform tx = new AffineTransform();
            tx.translate(size / 2.0, size / 2.0);
            tx.rotate(Math.toRadians(shardRot[i]));
            tx.translate(-size / 2.0, -size / 2.0);
            tx.translate(shardDx[i], shardDy[i]);
            sg.transform(tx);
            sg.setColor(fill);
            sg.fill(scaleTriangle(s.poly, size, size));
            sg.dispose();
        }

        if (faceOpacity > 0f) {
            Graphics2D fg = (Graphics2D) g.create();
            fg.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, clamp01(faceOpacity)));
            Path2D.Float outline = scaleOutline(size, size);
            fg.setColor(fill);
            fg.fill(outline);
            fg.setStroke(new BasicStroke((float) Math.max(1.0, size * OUTLINE_WIDTH_FRAC)));
            fg.setColor(rgba(new int[]{ OUTLINE.getRed(), OUTLINE.getGreen(), OUTLINE.getBlue() }, OUTLINE_ALPHA));
            fg.draw(outline);
            fg.dispose();
        }

        g.dispose();
    }

    private static float clamp01(float v) { return Math.max(0f, Math.min(1f, v)); }

    // ---- Geometry ------------------------------------------------------------

    private static final class Shard {
        final double[][] poly;
        final double ang;
        Shard(double[][] poly, double ang) { this.poly = poly; this.ang = ang; }
    }

    private static double[][] buildHeraldicOutline() {
        List<double[]> pts = new ArrayList<>();
        pts.add(new double[]{50,  4});
        pts.add(new double[]{86, 16});
        pts.add(new double[]{86, 48});
        addQuadratic(pts, 86, 48, 86, 80, 50, 97, 10);
        addQuadratic(pts, 50, 97, 14, 80, 14, 48, 10);
        pts.add(new double[]{14, 16});
        return pts.toArray(double[][]::new);
    }

    private static void addQuadratic(List<double[]> out,
                                     double x0, double y0,
                                     double cx, double cy,
                                     double x1, double y1, int steps) {
        for (int i = 1; i <= steps; i++) {
            double t = i / (double) steps, mt = 1 - t;
            out.add(new double[]{mt*mt*x0 + 2*mt*t*cx + t*t*x1,
                                  mt*mt*y0 + 2*mt*t*cy + t*t*y1});
        }
    }

    private static Shard[] buildShards() {
        double[][] outline = SHIELD_OUTLINE;
        double totalLen = 0;
        double[] segLen = new double[outline.length];
        for (int i = 0; i < outline.length; i++) {
            double[] a = outline[i], b = outline[(i+1) % outline.length];
            segLen[i] = Math.hypot(b[0]-a[0], b[1]-a[1]);
            totalLen += segLen[i];
        }
        double[][] sampled = new double[SHARD_COUNT][2];
        for (int k = 0; k < SHARD_COUNT; k++) {
            double target = (k / (double) SHARD_COUNT) * totalLen, acc = 0;
            for (int i = 0; i < outline.length; i++) {
                if (acc + segLen[i] >= target || i == outline.length - 1) {
                    double lT = segLen[i] == 0 ? 0 : (target - acc) / segLen[i];
                    double[] a = outline[i], b = outline[(i+1) % outline.length];
                    sampled[k][0] = a[0] + (b[0]-a[0]) * lT;
                    sampled[k][1] = a[1] + (b[1]-a[1]) * lT;
                    break;
                }
                acc += segLen[i];
            }
        }
        Shard[] shards = new Shard[SHARD_COUNT];
        for (int i = 0; i < SHARD_COUNT; i++) {
            double[] pt = sampled[i], next = sampled[(i+1) % SHARD_COUNT];
            double mx = (CENTER_X + pt[0] + next[0]) / 3.0, my = (CENTER_Y + pt[1] + next[1]) / 3.0;
            shards[i] = new Shard(new double[][]{{CENTER_X, CENTER_Y}, pt, next},
                                  Math.atan2(my - CENTER_Y, mx - CENTER_X));
        }
        return shards;
    }

    private static Path2D.Float scaleTriangle(double[][] poly, int w, int h) {
        Path2D.Float p = new Path2D.Float();
        for (int i = 0; i < poly.length; i++) {
            float x = (float)(poly[i][0] / 100.0 * w), y = (float)(poly[i][1] / 100.0 * h);
            if (i == 0) p.moveTo(x, y); else p.lineTo(x, y);
        }
        p.closePath();
        return p;
    }

    private static Path2D.Float scaleOutline(int w, int h) {
        Path2D.Float p = new Path2D.Float();
        double[][] o = SHIELD_OUTLINE;
        for (int i = 0; i < o.length; i++) {
            float x = (float)(o[i][0] / 100.0 * w), y = (float)(o[i][1] / 100.0 * h);
            if (i == 0) p.moveTo(x, y); else p.lineTo(x, y);
        }
        p.closePath();
        return p;
    }

    // ---- Color helpers -------------------------------------------------------

    private static int[] hexToRgb(String hex) {
        if (hex == null) return DEFAULT_TINT;
        String s = hex.trim();
        if (s.startsWith("#")) s = s.substring(1);
        if (s.length() == 3) { StringBuilder b = new StringBuilder(); for (char c : s.toCharArray()) b.append(c).append(c); s = b.toString(); }
        if (s.length() != 6) return DEFAULT_TINT;
        try {
            return new int[]{ Integer.parseInt(s.substring(0,2),16), Integer.parseInt(s.substring(2,4),16), Integer.parseInt(s.substring(4,6),16) };
        } catch (NumberFormatException e) { return DEFAULT_TINT; }
    }

    private static Color rgba(int[] rgb, float a) {
        return new Color(rgb[0], rgb[1], rgb[2], Math.round(clamp01(a) * 255));
    }
}
