package shufflingway;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Paint;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;

import javax.swing.BorderFactory;
import javax.swing.JPanel;
import javax.swing.Timer;

/**
 * PhaseTracker -- a Swing component for displaying FFTCG turn phase progression.
 *
 * The six main phases (Active, Draw, Main 1, Attack, Main 2, End) sit on a
 * horizontal track. The current phase glows blue when {@code isMyTurn} is true,
 * red otherwise. On phase change the old diamond's halo fades out as the new
 * one's fades in over ~240ms.
 *
 * The Attack phase is shown as a cluster of four small sub-diamonds connected
 * by a thinner inner line, representing the four internal sub-steps of the
 * Attack phase. The same blue/red glow animates between sub-steps as
 * {@link #setAttackStep(int)} advances.
 *
 * Usage (controlled -- parent owns state):
 * <pre>
 *   PhaseTracker tracker = new PhaseTracker();
 *   sidePanel.add(tracker);
 *   tracker.setState("Main 1", 3, true);
 *
 *   // entering Attack:
 *   tracker.setPhase("Attack");      // attackStep auto-resets to 0
 *   tracker.setAttackStep(1);        // walk through sub-steps
 *   tracker.setAttackStep(2);
 *   tracker.setAttackStep(3);
 *   tracker.setPhase("Main 2");      // exits Attack
 * </pre>
 */
public class PhaseTracker extends JPanel {

    public static final String[] PHASES = {
        "Active", "Draw", "Main 1", "Attack", "Main 2", "End"
    };
    private static final String[] PHASE_LABELS = {
        "ACT", "DRAW", "M1", "ATK", "M2", "END"
    };
    private static final int ATTACK_PHASE_IDX = 3;
    public  static final int ATTACK_SUB_STEPS = 4;

    // -- Geometry ---------------------------------------------------------------
    private static final int DIAMOND         = 20;
    private static final int SUB_DIAMOND     = 9;
    private static final int SUB_CONNECTOR   = 5;
    private static final int CLUSTER_WIDTH   =
        ATTACK_SUB_STEPS * SUB_DIAMOND + (ATTACK_SUB_STEPS - 1) * SUB_CONNECTOR;
    private static final int PAD_X           = 12;
    private static final int PAD_TOP         = 8;
    private static final int PAD_BOTTOM      = 8;
    private static final int TOP_STRIP_H     = 22;
    private static final int LABEL_GAP       = 8;
    private static final int LABEL_H         = 10;
    private static final int GLOW_RADIUS     = 20;
    private static final int SUB_GLOW_RADIUS = 12;

    // -- Colors -----------------------------------------------------------------
    private static final Color BG                  = new Color(0xd4d0c8);
    private static final Color STROKE              = new Color(0x222222);
    private static final Color PAST_FILL           = new Color(0x8a8a8a);
    private static final Color CONNECTOR_MID       = new Color(0x555555);
    private static final Color CONNECTOR_HI        = new Color(0xaaaaaa);
    private static final Color CONNECTOR_LO        = new Color(0x333333);
    private static final Color LABEL_FUTURE        = new Color(0xaaaaaa);
    private static final Color LABEL_PAST          = new Color(0x666666);

    private static final Color BLUE          = new Color(0x4ab4ff);
    private static final Color BLUE_FILL     = new Color(0xe8f4ff);
    private static final Color BLUE_PILL_BG  = new Color(0x1d4f7a);

    private static final Color RED           = new Color(0xff5252);
    private static final Color RED_FILL      = new Color(0xffe8e8);
    private static final Color RED_PILL_BG   = new Color(0x7a1d1d);

    // -- State ------------------------------------------------------------------
    private int     phaseIdx       = 0;
    private int     prevPhaseIdx   = 0;
    private int     attackStep     = 0;   // 0..3; only meaningful in Attack phase
    private int     prevAttackStep = 0;
    private int     turn           = 1;
    private boolean isMyTurn       = true;

    private static final int ANIM_MS = 240;
    private long  animStart = 0L;
    private float progress  = 1f;
    private final Timer animTimer;

    private Font pixelFont;
    private Font pixelFontSmall;

    public PhaseTracker() {
        setOpaque(true);
        setBackground(BG);
        setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(0x888888)));

        Font base;
        try {
            base = new Font("Press Start 2P", Font.PLAIN, 7);
            if (!base.getFamily().toLowerCase().contains("press")) {
                base = new Font(Font.MONOSPACED, Font.BOLD, 9);
            }
        } catch (Exception e) {
            base = new Font(Font.MONOSPACED, Font.BOLD, 9);
        }
        pixelFont      = base.deriveFont(13f);
        pixelFontSmall = base.deriveFont(12f);

        int h = PAD_TOP + TOP_STRIP_H + 8 + DIAMOND + LABEL_GAP + LABEL_H + PAD_BOTTOM;
        setPreferredSize(new Dimension(Short.MAX_VALUE, h));
        setMinimumSize(new Dimension(140, h));
        setMaximumSize(new Dimension(Integer.MAX_VALUE, h));

        animTimer = new Timer(15, e -> {
            long now = System.currentTimeMillis();
            float p = Math.min(1f, (now - animStart) / (float) ANIM_MS);
            progress = easeOut(p);
            if (p >= 1f) {
                progress = 1f;
                ((Timer) e.getSource()).stop();
            }
            repaint();
        });
    }

    // -- Public API -------------------------------------------------------------

    public void setState(String phase, int turn, boolean isMyTurn) {
        setPhase(phase);
        setTurn(turn);
        setMyTurn(isMyTurn);
    }

    /** Move to a new phase. Entering Attack resets the sub-step to 0. */
    public void setPhase(String phase) {
        int next = indexOfPhase(phase);
        if (next < 0 || next == phaseIdx) return;
        savePrevAndKick();
        phaseIdx = next;
        if (phaseIdx == ATTACK_PHASE_IDX) attackStep = 0;
    }

    /**
     * Walk through the 4 Attack-phase sub-steps (0=Prep, 1=Declare, 2=Block, 3=Damage).
     * Clamped to [0, 3]. Has no visible effect if current phase is not Attack,
     * but the value is retained.
     */
    public void setAttackStep(int step) {
        step = Math.max(0, Math.min(ATTACK_SUB_STEPS - 1, step));
        if (step == attackStep) return;
        savePrevAndKick();
        attackStep = step;
    }

    public void setTurn(int turn) {
        if (this.turn == turn) return;
        this.turn = turn;
        repaint();
    }

    public void setMyTurn(boolean isMyTurn) {
        if (this.isMyTurn == isMyTurn) return;
        this.isMyTurn = isMyTurn;
        repaint();
    }

    public String  getPhase()      { return PHASES[phaseIdx]; }
    public int     getTurn()       { return turn; }
    public boolean isMyTurn()      { return isMyTurn; }
    public int     getAttackStep() { return attackStep; }

    private static int indexOfPhase(String phase) {
        for (int i = 0; i < PHASES.length; i++) {
            if (PHASES[i].equalsIgnoreCase(phase)) return i;
        }
        return -1;
    }

    private static float easeOut(float t) { return 1f - (1f - t) * (1f - t); }

    /** Snapshot the current (phase, sub-step) as "previous" and kick the fade animation. */
    private void savePrevAndKick() {
        prevPhaseIdx   = phaseIdx;
        prevAttackStep = attackStep;
        animStart      = System.currentTimeMillis();
        progress       = 0f;
        animTimer.restart();
        repaint();
    }

    // -- Rendering --------------------------------------------------------------

    @Override
    protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);

        int w = getWidth();

        Color glow     = isMyTurn ? BLUE         : RED;
        Color glowFill = isMyTurn ? BLUE_FILL    : RED_FILL;
        Color pillBg   = isMyTurn ? BLUE_PILL_BG : RED_PILL_BG;

        drawTopStrip(g, w, pillBg);

        // Layout: each stop has a half-width; Attack uses cluster width
        int trackY  = PAD_TOP + TOP_STRIP_H + 6;
        int centerY = trackY + DIAMOND / 2;
        int n       = PHASES.length;

        int[] halfW = new int[n];
        int   totalItem = 0;
        for (int i = 0; i < n; i++) {
            halfW[i] = (i == ATTACK_PHASE_IDX) ? CLUSTER_WIDTH / 2 : DIAMOND / 2;
            totalItem += 2 * halfW[i];
        }
        int connectorW = Math.max(4, (w - 2 * PAD_X - totalItem) / (n - 1));

        int[] cx = new int[n];
        cx[0] = PAD_X + halfW[0];
        for (int i = 1; i < n; i++) {
            cx[i] = cx[i - 1] + halfW[i - 1] + connectorW + halfW[i];
        }

        // Connectors between main stops (3-row beveled line)
        for (int i = 0; i < n - 1; i++) {
            int x1 = cx[i]   + halfW[i];
            int x2 = cx[i+1] - halfW[i+1];
            if (x2 <= x1) continue;
            g.setColor(CONNECTOR_LO);  g.fillRect(x1, centerY - 1, x2 - x1, 1);
            g.setColor(CONNECTOR_MID); g.fillRect(x1, centerY,     x2 - x1, 1);
            g.setColor(CONNECTOR_HI);  g.fillRect(x1, centerY + 1, x2 - x1, 1);
        }

        // Stops
        for (int i = 0; i < n; i++) {
            if (i == ATTACK_PHASE_IDX) {
                drawAttackCluster(g, cx[i], centerY, trackY, glow, glowFill);
            } else {
                drawMainStop(g, i, cx[i], centerY, trackY, glow, glowFill);
            }
        }

        g.dispose();
    }

    /** "TURN N"  |  [ YOUR TURN ] row. */
    private void drawTopStrip(Graphics2D g, int w, Color pillBg) {
        g.setFont(pixelFont);
        g.setColor(new Color(0x333333));
        FontMetrics fm = g.getFontMetrics();
        int stripY = PAD_TOP + fm.getAscent();
        g.drawString("TURN " + turn, PAD_X, stripY);

        String pillText  = isMyTurn ? "YOUR TURN" : "OPPONENT'S TURN";
        int    pillTextW = fm.stringWidth(pillText);
        int    pillPadX  = 5, pillPadY = 2;
        int    pillW     = pillTextW + pillPadX * 2;
        int    pillH     = fm.getAscent() + fm.getDescent() + pillPadY * 2;
        int    pillX     = w - PAD_X - pillW;
        int    pillY     = PAD_TOP + (TOP_STRIP_H - pillH) / 2;
        g.setColor(pillBg);
        g.fillRect(pillX, pillY, pillW, pillH);
        g.setColor(Color.WHITE);
        g.drawString(pillText, pillX + pillPadX, pillY + pillPadY + fm.getAscent());
    }

    /** Render a regular main-phase diamond + its label. */
    private void drawMainStop(Graphics2D g, int i, int cx, int cy, int trackY,
                              Color glow, Color glowFill) {
        boolean isPast = i < phaseIdx;

        float haloAlpha = 0f;
        if (isCurrentStop(i, -1))   haloAlpha = progress;
        else if (isPrevStop(i, -1)) haloAlpha = 1f - progress;
        haloAlpha = clamp01(haloAlpha);

        if (haloAlpha > 0.01f) drawHalo(g, cx, cy, GLOW_RADIUS, glow, haloAlpha);

        Color fill   = computeFill(isPast, glowFill, haloAlpha);
        Color border = haloAlpha > 0.01f ? lerpColor(STROKE, glow, haloAlpha) : STROKE;
        drawDiamond(g, cx, cy, DIAMOND, fill, border, 1.5f);

        Color labelColor = computeLabelColor(isPast, glow, haloAlpha);
        drawLabel(g, PHASE_LABELS[i], cx, trackY, labelColor);
    }

    /** Render the Attack phase as a row of four sub-diamonds + shared label. */
    private void drawAttackCluster(Graphics2D g, int cxCenter, int cy, int trackY,
                                   Color glow, Color glowFill) {
        boolean phaseIsAttack   = phaseIdx == ATTACK_PHASE_IDX;
        boolean phasePastAttack = phaseIdx >  ATTACK_PHASE_IDX;

        int stride = SUB_DIAMOND + SUB_CONNECTOR;
        int leftCenter = cxCenter - (ATTACK_SUB_STEPS - 1) * stride / 2;
        int[] subCx = new int[ATTACK_SUB_STEPS];
        for (int i = 0; i < ATTACK_SUB_STEPS; i++) subCx[i] = leftCenter + i * stride;

        // Beveled connector between sub-diamonds (matches main track style)
        for (int i = 0; i < ATTACK_SUB_STEPS - 1; i++) {
            int x1 = subCx[i]   + SUB_DIAMOND / 2;
            int x2 = subCx[i+1] - SUB_DIAMOND / 2;
            if (x2 <= x1) continue;
            g.setColor(CONNECTOR_LO);  g.fillRect(x1, cy - 1, x2 - x1, 1);
            g.setColor(CONNECTOR_MID); g.fillRect(x1, cy,     x2 - x1, 1);
            g.setColor(CONNECTOR_HI);  g.fillRect(x1, cy + 1, x2 - x1, 1);
        }

        // Sub-diamonds
        for (int i = 0; i < ATTACK_SUB_STEPS; i++) {
            boolean isSubPast = phasePastAttack || (phaseIsAttack && attackStep > i);

            float haloAlpha = 0f;
            if (isCurrentStop(ATTACK_PHASE_IDX, i))   haloAlpha = progress;
            else if (isPrevStop(ATTACK_PHASE_IDX, i))  haloAlpha = 1f - progress;
            haloAlpha = clamp01(haloAlpha);

            if (haloAlpha > 0.01f) drawHalo(g, subCx[i], cy, SUB_GLOW_RADIUS, glow, haloAlpha);

            Color fill   = computeFill(isSubPast, glowFill, haloAlpha);
            Color border = haloAlpha > 0.01f ? lerpColor(STROKE, glow, haloAlpha) : STROKE;
            drawDiamond(g, subCx[i], cy, SUB_DIAMOND, fill, border, 1.25f);
        }

        // Shared "ATK" label below the cluster
        float clusterHalo = 0f;
        for (int i = 0; i < ATTACK_SUB_STEPS; i++) {
            if (isCurrentStop(ATTACK_PHASE_IDX, i)) { clusterHalo = progress; break; }
            if (isPrevStop(ATTACK_PHASE_IDX, i))    { clusterHalo = 1f - progress; break; }
        }
        clusterHalo = clamp01(clusterHalo);

        Color labelColor = computeLabelColor(phasePastAttack, glow, clusterHalo);
        drawLabel(g, PHASE_LABELS[ATTACK_PHASE_IDX], cxCenter, trackY, labelColor);
    }

    /**
     * Returns true when (p, subStep) is the currently active stop.
     * subStep < 0 means "not a sub-step" (regular main-phase diamond).
     */
    private boolean isCurrentStop(int p, int subStep) {
        if (p != phaseIdx) return false;
        if (p == ATTACK_PHASE_IDX) return subStep == attackStep;
        return subStep < 0;
    }

    private boolean isPrevStop(int p, int subStep) {
        if (progress >= 1f) return false;
        if (p != prevPhaseIdx) return false;
        if (p == ATTACK_PHASE_IDX) return subStep == prevAttackStep;
        return subStep < 0;
    }

    private static Color computeFill(boolean isPast, Color glowFill, float haloAlpha) {
        if (haloAlpha > 0.01f) {
            Color base = isPast ? PAST_FILL : new Color(0, 0, 0, 0);
            return lerpColor(base, glowFill, haloAlpha);
        }
        return isPast ? PAST_FILL : null;
    }

    private static Color computeLabelColor(boolean isPast, Color glow, float haloAlpha) {
        Color baseColor = isPast ? LABEL_PAST : LABEL_FUTURE;
        if (haloAlpha > 0.01f) return lerpColor(baseColor, glow, haloAlpha);
        return baseColor;
    }

    private void drawLabel(Graphics2D g, String label, int cx, int trackY, Color color) {
        g.setFont(pixelFontSmall);
        FontMetrics lfm = g.getFontMetrics();
        int labelW = lfm.stringWidth(label);
        int labelX = cx - labelW / 2;
        int labelY = trackY + DIAMOND + LABEL_GAP + lfm.getAscent();
        g.setColor(color);
        g.drawString(label, labelX, labelY);
    }

    private void drawHalo(Graphics2D g, int cx, int cy, int radius, Color color, float alpha) {
        Point2D center = new Point2D.Float(cx, cy);
        float[] dist = { 0.0f, 0.35f, 1.0f };
        Color core = new Color(color.getRed(), color.getGreen(), color.getBlue(),
                               Math.round(220 * alpha));
        Color mid  = new Color(color.getRed(), color.getGreen(), color.getBlue(),
                               Math.round(110 * alpha));
        Color edge = new Color(color.getRed(), color.getGreen(), color.getBlue(), 0);
        Color[] colors = { core, mid, edge };
        RadialGradientPaint paint = new RadialGradientPaint(center, radius, dist, colors);
        Paint old = g.getPaint();
        g.setPaint(paint);
        g.fillOval(cx - radius, cy - radius, radius * 2, radius * 2);
        g.setPaint(old);
    }

    private void drawDiamond(Graphics2D g, int cx, int cy, int size,
                             Color fill, Color border, float strokeWidth) {
        int half = size / 2;
        Path2D.Float p = new Path2D.Float();
        p.moveTo(cx,        cy - half);
        p.lineTo(cx + half, cy);
        p.lineTo(cx,        cy + half);
        p.lineTo(cx - half, cy);
        p.closePath();

        if (fill != null) {
            g.setColor(fill);
            g.fill(p);
        }
        g.setStroke(new BasicStroke(strokeWidth));
        g.setColor(border);
        g.draw(p);
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    private static Color lerpColor(Color a, Color b, float t) {
        t = clamp01(t);
        int ar = a.getRed(),   ag = a.getGreen(), ab = a.getBlue(),  aa = a.getAlpha();
        int br = b.getRed(),   bg = b.getGreen(), bb = b.getBlue(),  ba = b.getAlpha();
        return new Color(
            Math.round(ar + (br - ar) * t),
            Math.round(ag + (bg - ag) * t),
            Math.round(ab + (bb - ab) * t),
            Math.round(aa + (ba - aa) * t)
        );
    }
}
