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
import java.awt.Shape;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.Locale;

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
 * The Attack phase is shown as a cluster of four small sub-steps connected by a
 * thinner inner line, representing the four internal sub-steps of the Attack
 * phase. The same blue/red glow animates between sub-steps as
 * {@link #setAttackStep(int)} advances.
 *
 * Each of those four carries its own shape rather than a diamond: a circle for
 * Attack Preparation, a priority window before any attack is declared, so the track
 * visibly pauses rather than running straight through; a sword for declare-attackers;
 * a shield for declare-blockers; and a square for the damage step that ends the
 * phase. The sword and shield hang a little below the line the others sit on, and the
 * sword reaches further above it. Any of the four can be switched back to a diamond
 * one at a time; see {@link #PREP_STEP_AS_CIRCLE}.
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
    /**
     * Attack Preparation, the Attack phase's first sub-step, and the damage step that ends it.
     * Which shape marks each is the only thing these indices are used for here -- {@code
     * MainWindow} owns what the steps actually mean, and sets the damage one immediately before
     * it resolves combat.
     */
    private static final int ATTACK_PREP_STEP    = 0;
    private static final int ATTACK_DECLARE_STEP = 1;
    private static final int ATTACK_BLOCK_STEP   = 2;
    private static final int ATTACK_DAMAGE_STEP  = ATTACK_SUB_STEPS - 1;

    // -- Sub-step shape toggles -------------------------------------------------
    // Flip either to false and re-run to put that sub-step back to a plain diamond, which is what
    // the whole cluster used to be. They are here to be compared by eye, so they are the two
    // values to edit -- everything else about the marker (size, stroke, fill, glow, the connector
    // it butts against) follows from the shape that is actually chosen.
    //
    // Deliberately not final: as constants an IDE greys out whichever branch is currently off,
    // which is exactly the code you are about to want to read when comparing the two.

    /** Attack Preparation as a circle; false draws the diamond it replaced. */
    private static boolean PREP_STEP_AS_CIRCLE    = false;
    /** Declare-attackers as an upright sword; false draws the diamond it replaced. */
    private static boolean DECLARE_STEP_AS_SWORD  = true;
    /** Declare-blockers as a shield; false draws the diamond it replaced. */
    private static boolean BLOCK_STEP_AS_SHIELD   = true;
    /** The damage step as a square; false draws the diamond it replaced. */
    private static boolean DAMAGE_STEP_AS_SQUARE  = false;

    // -- Geometry ---------------------------------------------------------------
    private static final int DIAMOND         = 20;
    private static final int SUB_DIAMOND     = 9;
    /** Outline weight shared by every marker in the Attack cluster. */
    private static final float SUB_STROKE    = 1.25f;
    /**
     * Side of the damage step's square, smaller than the markers around it because a square fills
     * its box: at {@link #SUB_DIAMOND}'s 9 it carries over twice a diamond's ink and half again
     * the circle's, and swamps the row. At 7 the two end markers read as matched bookends around
     * the diamonds between them.
     */
    /**
     * Diameter of the Attack Preparation circle. Its own constant rather than {@link #SUB_DIAMOND}
     * because the circle, shield and square are all drawn a couple of pixels over the diamonds':
     * a diamond fills half its box and these fill most of theirs, so matching boxes made them read
     * small beside the sword rather than matched to it.
     */
    private static final int SUB_CIRCLE      = 11;
    private static final int SUB_SQUARE      = 9;

    // The sword and shield are the two markers that break the cluster's band, hanging SWORD_FOOT /
    // SHIELD_FOOT below the centre line where the others stop at about 4. Both feet are equal on
    // purpose, so the pair reads as sitting on a common baseline. Only the sword rises above the
    // band, which is what keeps it the tallest thing in the row without widening it.

    /** Half-width of the sword's crossguard, and so the whole marker: the cluster's full width. */
    private static final float SWORD_GUARD_HALF   = 4.5f;
    /** Half-thickness of the crossguard. Wants to stay above 1.5f: the connector it meets is 3px
     *  tall, and a guard that matches it merges into the line and leaves a bare vertical bar. */
    private static final float SWORD_GUARD_THICK  = 2f;
    /**
     * Blade and grip half-widths. Wider than they need to be to read as a sword, because the glow
     * has to get inside them: the active fill is what lights a marker up, and a 3px blade with a
     * 1.25f outline down each side left under 2px of it showing, so the sword stayed dim while the
     * shapes either side of it lit up. See {@link #SWORD_STROKE}.
     */
    private static final float SWORD_BLADE_HALF   = 2f;
    private static final float SWORD_GRIP_HALF    = 1.25f;
    /**
     * The sword's outline, thinner than {@link #SUB_STROKE}. The other markers are wide enough that
     * the border costs them nothing; on the sword every extra fraction of stroke is taken out of
     * the lit interior of a blade only a few pixels across.
     */
    private static final float SWORD_STROKE       = 1f;
    /** How far the pommel juts past the grip, and how tall that jut is. */
    private static final float SWORD_POMMEL       = 0.75f;
    private static final float SWORD_TIP          = 9f;   // above the centre line
    private static final float SWORD_FOOT         = 6f;   // below it

    private static final float SHIELD_HALF_W      = 5f;
    private static final float SHIELD_TOP         = 5f;   // flat top, level with the other markers
    /** Where the straight sides give way to the round bottom. */
    private static final float SHIELD_SHOULDER    = 1f;
    private static final float SHIELD_FOOT        = 6f;
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
    private boolean isMyTurn       = true;  // whose turn it is (controls the banner)
    private boolean hasPriority    = true;  // who currently holds priority (controls diamond color)

    /** Turn-pill names for a networked match; blank falls back to "YOUR" / "OPPONENT'S". */
    private String myName       = "";
    private String opponentName = "";

    private static final int ANIM_MS = 240;
    private long  animStart = 0L;
    private float progress  = 1f;
    private final Timer animTimer;

    /** Top strip ("TURN N" and the turn pill) — body face. */
    private Font stripFont;
    /** Phase labels under the diamonds — pixel face, they are short and sit in a 10px slot. */
    private Font labelFont;

    public PhaseTracker() {
        setOpaque(true);
        setBackground(BG);
        setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(0x888888)));

        // Unscaled: this component's geometry is in raw pixels, so its text must be too.
        stripFont = FontLoader.uiFontUnscaled(13f);
        labelFont = FontLoader.overlayFontUnscaled(12f);

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
        this.hasPriority = isMyTurn;
        repaint();
    }

    public void setHasPriority(boolean hasPriority) {
        if (this.hasPriority == hasPriority) return;
        this.hasPriority = hasPriority;
        repaint();
    }

    /**
     * Names the two players in the turn pill, which otherwise reads "YOUR TURN" /
     * "OPPONENT'S TURN". Either side may be {@code null} or blank to keep the generic wording —
     * that is the single-player case, and also a networked opponent who set no username.
     */
    public void setPlayerNames(String mine, String opponent) {
        myName       = mine     == null ? "" : mine.trim();
        opponentName = opponent == null ? "" : opponent.trim();
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

        Color glow     = hasPriority ? BLUE         : RED;
        Color glowFill = hasPriority ? BLUE_FILL    : RED_FILL;
        Color pillBg   = isMyTurn   ? BLUE_PILL_BG : RED_PILL_BG;

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
        g.setFont(stripFont);
        g.setColor(new Color(0x333333));
        FontMetrics fm = g.getFontMetrics();
        int stripY = PAD_TOP + fm.getAscent();
        g.drawString("TURN " + turn, PAD_X, stripY);

        String pillText  = pillText();
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

    /**
     * The turn pill's caption: the named player's possessive when a name is set for whoever is
     * taking the turn, otherwise the generic wording.
     */
    private String pillText() {
        String name = isMyTurn ? myName : opponentName;
        if (name.isEmpty()) return isMyTurn ? "YOUR TURN" : "OPPONENT'S TURN";
        String upper = name.toUpperCase(Locale.ROOT);
        return upper + (upper.endsWith("S") ? "' TURN" : "'S TURN");
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
            int x1 = subCx[i]   + subMarkerHalf(i);
            int x2 = subCx[i+1] - subMarkerHalf(i + 1);
            if (x2 <= x1) continue;
            g.setColor(CONNECTOR_LO);  g.fillRect(x1, cy - 1, x2 - x1, 1);
            g.setColor(CONNECTOR_MID); g.fillRect(x1, cy,     x2 - x1, 1);
            g.setColor(CONNECTOR_HI);  g.fillRect(x1, cy + 1, x2 - x1, 1);
        }

        // Sub-step markers
        for (int i = 0; i < ATTACK_SUB_STEPS; i++) {
            boolean isSubPast = phasePastAttack || (phaseIsAttack && attackStep > i);

            float haloAlpha = 0f;
            if (isCurrentStop(ATTACK_PHASE_IDX, i))   haloAlpha = progress;
            else if (isPrevStop(ATTACK_PHASE_IDX, i))  haloAlpha = 1f - progress;
            haloAlpha = clamp01(haloAlpha);

            if (haloAlpha > 0.01f) drawHalo(g, subCx[i], cy, subGlowRadius(i), glow, haloAlpha);

            Color fill   = computeFill(isSubPast, glowFill, haloAlpha);
            Color border = haloAlpha > 0.01f ? lerpColor(STROKE, glow, haloAlpha) : STROKE;
            drawSubStepMarker(g, i, subCx[i], cy, fill, border);
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
        g.setFont(labelFont);
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

    /**
     * The Attack Preparation marker: a circle sitting where the cluster's first diamond would.
     *
     * <p>Takes the same size, stroke width and fill/border colours as its neighbours, so it rides
     * the cluster's stride and animates through the glow exactly as they do -- only the outline
     * differs, which is the whole of the signal.
     *
     * <p>Drawn under {@link RenderingHints#VALUE_STROKE_PURE}, which is the whole of what makes it
     * round. Java2D's default is {@code VALUE_STROKE_NORMALIZE}: it nudges a stroked path onto the
     * pixel grid to keep thin straight lines crisp, and on a curve this small that shove lands
     * unevenly and comes out visibly lopsided. Mirroring the rendered pixels about both axes puts
     * numbers on it -- worst-case channel mismatch 84 normalized against 0 pure, i.e. exactly
     * symmetric. The hint is restored afterwards, so the diamonds drawn after this one in the same
     * pass keep the crispness normalizing buys them.
     */
    private void drawCircle(Graphics2D g, int cx, int cy, int size,
                            Color fill, Color border, float strokeWidth) {
        float half = size / 2f;
        fillAndStrokeExact(g, new Ellipse2D.Float(cx - half, cy - half, size, size),
                           fill, border, strokeWidth);
    }

    /**
     * The damage step's marker: the square that closes the Attack cluster, opposite the circle
     * that opens it.
     *
     * <p>Drawn at {@link #SUB_SQUARE} rather than the diamonds' size -- see that constant -- and
     * through {@link #fillAndStrokeExact} for the reason {@link #drawCircle} is. Measured the same
     * way, an even-sided square is off by 155 under the default hint and a odd-sided one by 1; both
     * are exact under {@code STROKE_PURE}, so the size stays a free choice rather than one
     * constrained by which parities happen to land on the grid.
     */
    private void drawSquare(Graphics2D g, int cx, int cy, int size,
                            Color fill, Color border, float strokeWidth) {
        float half = size / 2f;
        fillAndStrokeExact(g, new Rectangle2D.Float(cx - half, cy - half, size, size),
                           fill, border, strokeWidth);
    }

    /**
     * Draws sub-step {@code step}'s marker in whichever shape the toggles above select, at the
     * size that shape is drawn at. The single place the mapping from step to shape lives, so
     * {@link #subMarkerHalf} below can answer the same question for the connectors.
     */
    private void drawSubStepMarker(Graphics2D g, int step, int cx, int cy, Color fill, Color border) {
        if (step == ATTACK_PREP_STEP && PREP_STEP_AS_CIRCLE)
            drawCircle(g, cx, cy, SUB_CIRCLE, fill, border, SUB_STROKE);
        else if (step == ATTACK_DECLARE_STEP && DECLARE_STEP_AS_SWORD)
            // Takes the glow-tinted border like every other marker. It was held at the dark STROKE
            // for a while, because on the original narrow blade a tinted outline over the pale fill
            // washed the whole shape into a fat cross. Widening the blade and thinning the sword's
            // own stroke left enough lit interior for the detail to survive the tint, so the
            // exception is gone: an active marker whose outline stays black reads as the one thing
            // on the track that has not lit up.
            drawSword(g, cx, cy, fill, border, SWORD_STROKE);
        else if (step == ATTACK_BLOCK_STEP && BLOCK_STEP_AS_SHIELD)
            drawShield(g, cx, cy, fill, border, SUB_STROKE);
        else if (step == ATTACK_DAMAGE_STEP && DAMAGE_STEP_AS_SQUARE)
            drawSquare(g, cx, cy, SUB_SQUARE, fill, border, SUB_STROKE);
        else
            drawDiamond(g, cx, cy, SUB_DIAMOND, fill, border, SUB_STROKE);
    }

    /**
     * The declare-attackers marker: an upright sword, drawn as an outline the same way every other
     * marker is, so its fill still carries the past / current / still-to-come state.
     *
     * <p>Built around the centre line rather than centred on it. The crossguard straddles the line
     * so the connector meets the widest part of the shape, which puts the blade above and the grip
     * below -- the arrangement that makes it read as a sword rather than as a cross. The blade is
     * the long half and the grip the short one; at equal lengths this is a plus sign.
     */
    private void drawSword(Graphics2D g, int cx, int cy, Color fill, Color border, float strokeWidth) {
        float gh = SWORD_GUARD_THICK, gw = SWORD_GUARD_HALF, b = SWORD_BLADE_HALF;
        float grip = SWORD_GRIP_HALF, pom = SWORD_POMMEL, foot = cy + SWORD_FOOT;
        Path2D.Float p = new Path2D.Float();
        p.moveTo(cx, cy - SWORD_TIP);                       // point
        p.lineTo(cx + b, cy - SWORD_TIP + 2f);              // shoulder of the point
        p.lineTo(cx + b, cy - gh);                          // blade down to the guard
        p.lineTo(cx + gw, cy - gh);                         // guard, right arm
        p.lineTo(cx + gw, cy + gh);
        p.lineTo(cx + grip, cy + gh);                       // grip
        p.lineTo(cx + grip, foot - pom);
        p.lineTo(cx + grip + pom, foot - pom);              // pommel
        p.lineTo(cx + grip + pom, foot);
        p.lineTo(cx - grip - pom, foot);
        p.lineTo(cx - grip - pom, foot - pom);
        p.lineTo(cx - grip, foot - pom);
        p.lineTo(cx - grip, cy + gh);
        p.lineTo(cx - gw, cy + gh);                         // guard, left arm
        p.lineTo(cx - gw, cy - gh);
        p.lineTo(cx - b, cy - gh);
        p.lineTo(cx - b, cy - SWORD_TIP + 2f);
        p.closePath();
        fillAndStrokeExact(g, p, fill, border, strokeWidth);
    }

    /**
     * The declare-blockers marker: the square's flat top over the circle's round bottom, which is
     * the shield the two shapes either side of it already suggest between them.
     *
     * <p>Its foot is level with the sword's, so the middle pair of the cluster share a baseline
     * the circle and square do not reach. The round half is two quadratics rather than a true arc;
     * across five pixels of fall the difference does not survive rasterizing.
     */
    private void drawShield(Graphics2D g, int cx, int cy, Color fill, Color border, float strokeWidth) {
        float hw = SHIELD_HALF_W, foot = cy + SHIELD_FOOT, shoulder = cy + SHIELD_SHOULDER;
        Path2D.Float p = new Path2D.Float();
        p.moveTo(cx - hw, cy - SHIELD_TOP);
        p.lineTo(cx + hw, cy - SHIELD_TOP);
        p.lineTo(cx + hw, shoulder);
        p.quadTo(cx + hw, foot, cx, foot);
        p.quadTo(cx - hw, foot, cx - hw, shoulder);
        p.closePath();
        fillAndStrokeExact(g, p, fill, border, strokeWidth);
    }

    /**
     * Radius of the glow behind sub-step {@code i}.
     *
     * <p>The sword reaches {@link #SWORD_TIP} above the centre line and {@link #SWORD_FOOT} below
     * it, half again the height of anything else in the cluster, so the shared radius left its
     * point and its pommel outside the falloff and the whole marker read dimmer than its
     * neighbours. Sized to cover it instead.
     */
    private int subGlowRadius(int i) {
        boolean sword = i == ATTACK_DECLARE_STEP && DECLARE_STEP_AS_SWORD;
        return sword ? SUB_GLOW_RADIUS + 5 : SUB_GLOW_RADIUS;
    }

    /**
     * Half the width of sub-step {@code i}'s marker, which is where a connector has to stop.
     *
     * <p>Reads the same toggles {@link #drawSubStepMarker} does. The sword, shield and square are
     * each a different width from the diamond that replaces them, so switching any of them off has
     * to resize its connector too or the line stops short of, or runs into, what it now meets.
     */
    private static int subMarkerHalf(int i) {
        if (i == ATTACK_PREP_STEP     && PREP_STEP_AS_CIRCLE)   return SUB_CIRCLE / 2;
        if (i == ATTACK_DECLARE_STEP  && DECLARE_STEP_AS_SWORD) return (int) SWORD_GUARD_HALF;
        if (i == ATTACK_BLOCK_STEP    && BLOCK_STEP_AS_SHIELD)  return (int) SHIELD_HALF_W;
        if (i == ATTACK_DAMAGE_STEP   && DAMAGE_STEP_AS_SQUARE) return SUB_SQUARE / 2;
        return SUB_DIAMOND / 2;
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

        fillAndStroke(g, p, fill, border, strokeWidth);
    }

    /**
     * {@link #fillAndStroke} with stroke normalization turned off for the duration.
     *
     * <p>Java2D's default, {@code VALUE_STROKE_NORMALIZE}, shoves a stroked path onto the pixel
     * grid to keep thin straight lines crisp. On the small shapes here that shove lands unevenly
     * and the result is visibly lopsided -- mirroring the rendered pixels about both axes puts the
     * worst-case channel mismatch at 84 for the circle and 155 for an even-sided square, against 0
     * for both under {@code STROKE_PURE}, which is exact symmetry.
     *
     * <p>The hint is restored on the way out, so the diamonds drawn after these in the same pass
     * keep the crispness normalizing buys them. They are asymmetric under it too, by about 150,
     * but a straight 45-degree edge does not show it the way a curve or a flat side does.
     */
    private void fillAndStrokeExact(Graphics2D g, Shape shape,
                                    Color fill, Color border, float strokeWidth) {
        Object strokeControl = g.getRenderingHint(RenderingHints.KEY_STROKE_CONTROL);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        fillAndStroke(g, shape, fill, border, strokeWidth);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL,
                strokeControl != null ? strokeControl : RenderingHints.VALUE_STROKE_DEFAULT);
    }

    /** Paints one marker; a {@code null} fill is the hollow "still to come" state. */
    private void fillAndStroke(Graphics2D g, Shape shape,
                               Color fill, Color border, float strokeWidth) {
        if (fill != null) {
            g.setColor(fill);
            g.fill(shape);
        }
        g.setStroke(new BasicStroke(strokeWidth));
        g.setColor(border);
        g.draw(shape);
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
