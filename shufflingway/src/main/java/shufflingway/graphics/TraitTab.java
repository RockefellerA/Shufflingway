package shufflingway.graphics;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.QuadCurve2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import shufflingway.CardData;
import shufflingway.CardState;
import static shufflingway.graphics.CardAnimation.CARD_H;
import static shufflingway.graphics.CardAnimation.CARD_W;

/**
 * Small rectangular tabs that peek out from behind a field card, one per trait the card
 * currently has, each carrying a vector-drawn glyph (Haste, Brave, First Strike, Cannot Be
 * Broken, Priming), plus the Must/Cannot Attack/Block statuses the card is under.
 *
 * <p>Tabs are composited onto the square {@code CARD_H x CARD_H} field-card canvas built by
 * {@link CardAnimation#renderBackupCard}, the same way the damage, power and counter overlays
 * are. They always attach to the same edge of the card — its left — but that edge lands
 * somewhere different on the canvas depending on the card's state:
 * <ul>
 *   <li>{@link CardState#ACTIVE} — art is inset by {@link CardAnimation#LEFT_GUTTER}, so the
 *       card's left edge is at {@code LEFT_GUTTER} and tabs poke leftwards into the gutter,
 *       stacking downwards.</li>
 *   <li>{@link CardState#DULL} — art is rotated 90° CW and inset {@code LEFT_GUTTER} from the
 *       top, which puts the card's left edge along the TOP of the art: tabs poke upwards into that
 *       strip,
 *       stacking rightwards (mirrored, since the rotation reverses that axis, so a given trait
 *       keeps its position relative to the card).</li>
 * </ul>
 * The glyph is drawn upright in both cases.
 *
 * <p>Drawing is clipped to the free strip, so the half of each tab that would cover the art is
 * hidden and the tab reads as sitting behind the card — no reordering of the render pipeline
 * needed, since the overlay still runs after the art is composited.
 *
 * <p>The stack is centred on the card edge and capped at {@link #MAX_TABS}; past that the last
 * slot becomes a "more traits" indicator instead of a glyph.
 *
 * <p>Geometry is authored against a 140px-wide card and scaled by the live {@code CARD_W}, so
 * tabs track the UI scale along with everything else.
 */
public final class TraitTab {

    private TraitTab() {}

    // -- Visual constants (mirrors the HTML/CSS prototype) --------------------------------
    private static final Color BG_FILL      = new Color(0x40, 0x40, 0x46);
    private static final Color BEZEL        = new Color(0x8a, 0x8a, 0x8f);
    private static final Color ICON_LINE    = Color.WHITE;
    private static final Color ARROW_FILL   = new Color(0x5b, 0xc8, 0xe8);
    private static final Color ARROW_STROKE = Color.BLACK;
    private static final Color HEART_FILL   = new Color(0xe8, 0x45, 0x5a);
    private static final Color BLADE_FILL   = new Color(0xe4, 0xe4, 0xea);
    private static final Color BLADE_FULLER = new Color(0x9a, 0x9a, 0xa4);
    private static final Color GUARD_FILL   = new Color(0xa0, 0xa0, 0xa8);
    private static final Color GRIP_FILL    = new Color(0x7a, 0x4a, 0x2a);
    private static final Color SWORD_EDGE   = new Color(0x1e, 0x1e, 0x24);
    private static final Color SLASH_FILL   = new Color(0xff, 0xd2, 0x3f);
    private static final Color SLASH_EDGE   = new Color(0x8a, 0x60, 0x00);
    private static final Color SLASH_GLOW   = new Color(0xff, 0xd2, 0x3f, 70);
    private static final Color GEM_FILL     = new Color(0xe8, 0xb4, 0x4a);
    private static final Color PRIMED_FILL  = new Color(0x3d, 0xd9, 0x4a);
    private static final Color PRIMED_LINE  = new Color(0x0d, 0x3f, 0x14);
    private static final Color PRIMED_GLOW  = new Color(0xff, 0x8c, 0x1a);
    private static final Color ATTACK_FILL  = new Color(0xe2, 0x3b, 0x3b);
    private static final Color BLOCK_FILL   = new Color(0x3b, 0x7f, 0xe2);
    private static final Color FACE_INK     = Color.BLACK;

    /** Card width the tab geometry below was authored against; everything scales off it. */
    private static final int DESIGN_CARD_W = 140;

    /**
     * Most tabs one card can show. The stack runs along a {@code CARD_H} axis in both
     * orientations, and {@code n} tabs span {@code n*TAB_SHORT + (n-1)*TAB_GAP} — which at
     * 28/10 is 180 for five (fits 205 with room) and 218 for six (does not).
     */
    private static final int MAX_TABS = 5;

    private static final float TAB_LONG  = 48f;  // along the poke-out axis; half of it stays hidden
    private static final float TAB_SHORT = 28f;  // along the card edge
    private static final float TAB_GAP   = 10f;  // between stacked tabs
    private static final float BEZEL_W   = 2f;
    private static final float ICON_SIZE = 20f;
    private static final float ICON_INSET = 3f;  // glyph margin from the tab's outer edge

    /** Returns true if {@code trait} has a tab glyph; traits without one are never drawn. */
    public static boolean hasGlyph(CardData.Trait trait) {
        return trait == CardData.Trait.HASTE
            || trait == CardData.Trait.BRAVE
            || trait == CardData.Trait.FIRST_STRIKE
            || trait == CardData.Trait.CANNOT_BE_BROKEN
            || trait == CardData.Trait.PRIMING
            || trait == CardData.Trait.MUST_ATTACK
            || trait == CardData.Trait.MUST_BLOCK
            || trait == CardData.Trait.CANNOT_ATTACK
            || trait == CardData.Trait.CANNOT_BLOCK;
    }

    /**
     * What a trait's tab means, in the terms this engine actually implements — the tooltip text
     * players get on hover. Returns {@code null} for traits without a tab.
     *
     * <p>These describe Shufflingway's behaviour, not the comprehensive rules verbatim: First
     * Strike here resolves inside one atomic combat step, with no priority window between the
     * first blow and the return strike, so the text promises only what the engine delivers.
     *
     * <p>The four attack/block statuses get only a generic line here. On the board each tab's
     * tooltip lists the specific rules binding that card instead ("Cannot block a Forward with
     * higher power than its own.", "Must block Garland if able."), which MainWindow supplies.
     *
     * @param primed whether the card has actually been primed, which {@link CardData.Trait#PRIMING}
     *               reads two ways — the others ignore it
     */
    public static String description(CardData.Trait trait, boolean primed) {
        if (trait == null) return null;
        return switch (trait) {
            case HASTE            -> "Can attack and use abilities that require dulling "
                                   + "on the turn it enters the field.";
            case BRAVE            -> "Does not dull when it attacks.";
            case FIRST_STRIKE     -> "Deals its combat damage first. If that breaks the other "
                                   + "Forward, this one takes no damage back.";
            case CANNOT_BE_BROKEN -> "Survives damage that would break it. The damage stays on "
                                   + "it and clears at end of turn.";
            case PRIMING          -> primed
                                   ? "Primed. The card pulled from the deck is stacked on top, and "
                                   + "this Forward answers to both card names."
                                   : "Can be primed: pay the Priming cost to pull its named card "
                                   + "out of the deck and stack it on top.";
            case MUST_ATTACK      -> "Must attack if it is able to.";
            case MUST_BLOCK       -> "Must block if it is able to.";
            case CANNOT_ATTACK    -> "Cannot attack.";
            case CANNOT_BLOCK     -> "Cannot block.";
            default               -> null;
        };
    }

    /** The trait's own name for the tooltip heading — Priming reads as its state once it has one. */
    public static String displayName(CardData.Trait trait, boolean primed) {
        return trait == CardData.Trait.PRIMING && primed ? "Primed" : trait.displayName();
    }

    /**
     * One tab's placement on the card canvas: the trait it shows and the rectangle it occupies.
     * A {@code null} trait is the overflow indicator rather than a glyph.
     */
    public record Tab(CardData.Trait trait, Rectangle2D.Float bounds) {}

    /**
     * The strip of canvas the tabs are clipped to. Only the half of a tab inside this strip is
     * on screen — the rest sits behind the card art — so it is also the only part that should
     * answer to the pointer.
     */
    public static Rectangle visibleStrip(CardState state) {
        boolean dull = state == CardState.DULL;
        int room     = CardAnimation.LEFT_GUTTER;
        return dull ? new Rectangle(0, 0, CARD_H, room)
                    : new Rectangle(0, 0, room, CARD_H);
    }

    /**
     * Places a tab for each of {@code traits} that {@link #hasGlyph} can draw, in the given
     * order, centred on the card edge. Beyond {@link #MAX_TABS} drawable traits the last slot
     * becomes the overflow indicator, so the count of hidden traits is at least visible.
     *
     * <p>Shared by {@link #renderTraitTabs} and {@link #traitAt} so a tab's hit area cannot
     * drift away from where it was drawn.
     */
    public static List<Tab> layout(CardState state, List<CardData.Trait> traits) {
        List<CardData.Trait> drawable = new ArrayList<>();
        for (CardData.Trait t : traits) if (hasGlyph(t)) drawable.add(t);
        if (drawable.isEmpty()) return List.of();

        boolean overflow = drawable.size() > MAX_TABS;
        int slots  = Math.min(drawable.size(), MAX_TABS);
        int glyphs = overflow ? MAX_TABS - 1 : slots;   // last slot is the indicator when overflowing

        float s        = CARD_W / (float) DESIGN_CARD_W;
        float tabLong  = TAB_LONG * s;
        float tabShort = TAB_SHORT * s;
        float step     = tabShort + TAB_GAP * s;
        // Centre the stack on the card edge; the axis is CARD_H long in both orientations.
        float lead     = (CARD_H - (slots * tabShort + (slots - 1) * TAB_GAP * s)) / 2f;
        boolean dull   = state == CardState.DULL;
        // Free space outside the card's left edge: the gutter beside the art when active, the same
        // gutter above the rotated art when dull (a CW rotation sends left to top).
        int room       = CardAnimation.LEFT_GUTTER;

        List<Tab> out = new ArrayList<>(slots);
        for (int i = 0; i < slots; i++) {
            // The rotation reverses the along-edge axis, so mirror the dull stack to keep each
            // trait at the same spot on the card.
            float along = dull ? CARD_H - (lead + i * step) - tabShort : lead + i * step;
            // Straddle the art edge: half the tab lands in the free space, half is clipped away.
            float x = dull ? along : room - tabLong / 2f;
            float y = dull ? room - tabLong / 2f : along;
            float w = dull ? tabShort : tabLong;
            float h = dull ? tabLong  : tabShort;
            out.add(new Tab(i < glyphs ? drawable.get(i) : null, new Rectangle2D.Float(x, y, w, h)));
        }
        return out;
    }

    /**
     * The trait whose on-screen tab covers the canvas point {@code (x, y)}, or {@code null} when
     * the point is off every tab, on the clipped-away half of one, or on the overflow indicator.
     *
     * @param state the state the card was rendered in, which decides where the tabs sit
     */
    public static CardData.Trait traitAt(CardState state, List<CardData.Trait> traits, int x, int y) {
        Rectangle strip = visibleStrip(state);
        if (!strip.contains(x, y)) return null;
        for (Tab tab : layout(state, traits))
            if (tab.trait() != null && tab.bounds().contains(x, y)) return tab.trait();
        return null;
    }

    /**
     * Composites the {@link #layout} onto {@code canvas}, clipped to the {@link #visibleStrip}.
     * A no-op when no trait is drawable.
     *
     * @param canvas the square field-card canvas from {@link CardAnimation#renderBackupCard}
     * @param state  the card's state, which decides where the free strip is
     * @param primed whether this card has been primed, which lights the Priming glyph up; the
     *               other glyphs look the same either way
     */
    public static void renderTraitTabs(BufferedImage canvas, CardState state,
            List<CardData.Trait> traits, boolean primed) {
        List<Tab> tabs = layout(state, traits);
        if (tabs.isEmpty()) return;

        float s      = CARD_W / (float) DESIGN_CARD_W;
        boolean dull = state == CardState.DULL;

        Graphics2D g = canvas.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setClip(visibleStrip(state));
        for (Tab tab : tabs) {
            Rectangle2D.Float b = tab.bounds();
            drawTab(g, tab.trait(), b.x, b.y, b.width, b.height, s, dull, primed);
        }
        g.dispose();
    }

    /**
     * Draws one tab's chrome plus its glyph, hugging whichever half of the tab stays visible.
     * A {@code null} trait draws the overflow indicator instead.
     */
    private static void drawTab(Graphics2D g, CardData.Trait trait,
            float x, float y, float w, float h, float s, boolean dull, boolean primed) {
        float bezel = BEZEL_W * s;
        RoundRectangle2D.Float rr = new RoundRectangle2D.Float(
                x + bezel / 2, y + bezel / 2, w - bezel, h - bezel, 3 * s, 3 * s);
        g.setColor(BG_FILL);
        g.fill(rr);
        g.setStroke(new BasicStroke(bezel));
        g.setColor(BEZEL);
        g.draw(rr);

        // Keep the glyph in the half that survives the clip — the outer one either way: the top
        // half when the tab pokes up from a dull card, the left half when it pokes out to the side.
        float icon = ICON_SIZE * s;
        float inset = ICON_INSET * s;
        float ix = dull ? x + (w - icon) / 2f : x + inset;
        float iy = dull ? y + inset           : y + (h - icon) / 2f;
        if (trait == null) drawOverflowDots(g, ix, iy, icon);
        else               drawGlyph(g, trait, ix, iy, icon, primed);
    }

    /** Dispatches to the vector drawing for {@code trait}; silent for traits without a glyph. */
    private static void drawGlyph(Graphics2D g, CardData.Trait trait, float x, float y, float size,
            boolean primed) {
        switch (trait) {
            case HASTE            -> drawHasteIcon(g, x, y, size);
            case BRAVE            -> drawBraveIcon(g, x, y, size);
            case FIRST_STRIKE     -> drawFirstStrikeIcon(g, x, y, size);
            case CANNOT_BE_BROKEN -> drawCannotBeBrokenIcon(g, x, y, size);
            case PRIMING          -> drawPrimingIcon(g, x, y, size, primed);
            case MUST_ATTACK      -> drawMustAttackIcon(g, x, y, size);
            case MUST_BLOCK       -> drawMustBlockIcon(g, x, y, size);
            case CANNOT_ATTACK    -> drawCannotAttackIcon(g, x, y, size);
            case CANNOT_BLOCK     -> drawCannotBlockIcon(g, x, y, size);
            default               -> { }
        }
    }

    /** Draws three centred white dots indicating that further traits are hidden. */
    public static void drawOverflowDots(Graphics2D g0, float x, float y, float size) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        float s = size / 24f;
        g.translate(x, y);
        g.scale(s, s);

        float r = 2f, gap = 6f;   // radius and centre-to-centre spacing, 24x24 grid
        g.setColor(ICON_LINE);
        for (int i = -1; i <= 1; i++)
            g.fill(new Ellipse2D.Float(12 + i * gap - r, 12 - r, r * 2, r * 2));

        g.dispose();
    }

    /**
     * Draws the clock-face + broken-ring + up-arrow "Haste" glyph, upright, into the box
     * {@code [x, y, x + size, y + size]}. Coordinates below are authored in a 24x24 logical
     * grid and scaled to fit.
     */
    public static void drawHasteIcon(Graphics2D g0, float x, float y, float size) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        float s = size / 24f;
        g.translate(x, y);
        g.scale(s, s);

        // Broken ring: full circle minus a gap at lower-left where the arrow pokes through.
        // Center (12,12) r=8.5, gap between ~115deg and ~165deg.
        Arc2D.Float ring = new Arc2D.Float(12 - 8.5f, 12 - 8.5f, 17f, 17f, 165f, -310f, Arc2D.OPEN);
        g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(ICON_LINE);
        g.draw(ring);

        // Hour hand (12 o'clock, short) and minute hand (3 o'clock, long).
        g.draw(new Line2D.Float(12, 12, 12, 7.5f));
        g.draw(new Line2D.Float(12, 12, 17.5f, 12));

        // Up-arrow, poking through the ring gap at lower-left.
        Path2D.Float arrow = new Path2D.Float();
        arrow.moveTo(3.8f, 20.3f);
        arrow.lineTo(3.8f, 16.2f);
        arrow.lineTo(0.7f, 16.2f);
        arrow.lineTo(6f, 11.6f);
        arrow.lineTo(11.3f, 16.2f);
        arrow.lineTo(8.2f, 16.2f);
        arrow.lineTo(8.2f, 20.3f);
        arrow.closePath();
        g.setColor(ARROW_FILL);
        g.fill(arrow);
        g.setStroke(new BasicStroke(0.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(ARROW_STROKE);
        g.draw(arrow);

        g.dispose();
    }

    /**
     * Draws the "Brave" glyph — a chestplate outline with a heart at its center — into the box
     * {@code [x, y, x + size, y + size]}. Same 24x24 logical grid as {@link #drawHasteIcon}.
     */
    public static void drawBraveIcon(Graphics2D g0, float x, float y, float size) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        float s = size / 24f;
        g.translate(x, y);
        g.scale(s, s);

        Path2D.Float plate = new Path2D.Float();
        plate.moveTo(12, 5);
        plate.lineTo(8, 3);
        plate.lineTo(4, 4);
        plate.lineTo(3, 8);
        plate.lineTo(5, 10);
        plate.lineTo(4, 16);
        plate.lineTo(12, 22);
        plate.lineTo(20, 16);
        plate.lineTo(19, 10);
        plate.lineTo(21, 8);
        plate.lineTo(20, 4);
        plate.lineTo(16, 3);
        plate.closePath();
        g.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(ICON_LINE);
        g.draw(plate);

        Path2D.Float heart = new Path2D.Float();
        heart.moveTo(12, 16.5f);
        heart.curveTo(9, 14, 8, 12, 8, 10.3f);
        heart.curveTo(8, 8.9f, 9.1f, 8, 10.3f, 8);
        heart.curveTo(11.2f, 8, 12, 8.8f, 12, 9.6f);
        heart.curveTo(12, 8.8f, 12.8f, 8, 13.7f, 8);
        heart.curveTo(14.9f, 8, 16, 8.9f, 16, 10.3f);
        heart.curveTo(16, 12, 15, 14, 12, 16.5f);
        heart.closePath();
        g.setColor(HEART_FILL);
        g.fill(heart);
        g.setStroke(new BasicStroke(0.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(Color.BLACK);
        g.draw(heart);

        g.dispose();
    }

    /**
     * Draws the "Cannot Be Broken" glyph — a shield outline with a cut gem at its centre — into
     * the box {@code [x, y, x + size, y + size]}. Same 24x24 logical grid as
     * {@link #drawHasteIcon}, and the same two-part construction as {@link #drawBraveIcon}: a
     * white line-art shape with one filled, black-stroked accent inside it.
     *
     * <p>The gem carries the meaning — a shield alone reads as generic protection, while the
     * hardest thing there is reads as "cannot be broken" specifically.
     */
    public static void drawCannotBeBrokenIcon(Graphics2D g0, float x, float y, float size) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        float s = size / 24f;
        g.translate(x, y);
        g.scale(s, s);

        // Heater shield: flat shoulders, straight sides, curving to a point at the bottom.
        Path2D.Float shield = new Path2D.Float();
        shield.moveTo(12, 3.2f);
        shield.lineTo(20, 5.8f);
        shield.lineTo(20, 12);
        shield.curveTo(20, 17, 16.2f, 20.2f, 12, 21.6f);
        shield.curveTo(7.8f, 20.2f, 4, 17, 4, 12);
        shield.lineTo(4, 5.8f);
        shield.closePath();
        g.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(ICON_LINE);
        g.draw(shield);

        // Cut gem, centred on the shield's visual mass rather than its bounding box.
        Path2D.Float gem = new Path2D.Float();
        gem.moveTo(12, 7.8f);
        gem.lineTo(15.5f, 11.4f);
        gem.lineTo(12, 16.4f);
        gem.lineTo(8.5f, 11.4f);
        gem.closePath();
        g.setColor(GEM_FILL);
        g.fill(gem);
        g.setStroke(new BasicStroke(0.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(Color.BLACK);
        g.draw(gem);

        // Girdle facet: one line is enough to read as cut stone rather than a plain lozenge.
        g.setStroke(new BasicStroke(0.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(ICON_LINE);
        g.draw(new Line2D.Float(8.5f, 11.4f, 15.5f, 11.4f));

        g.dispose();
    }

    /**
     * Draws the "Priming" glyph — a bold letter P — into the box {@code [x, y, x + size, y + size]}.
     * Same 24x24 logical grid as {@link #drawHasteIcon}.
     *
     * <p>Two states, because the trait is a capability before it is a fact. Unprimed it is white
     * line art like the Haste ring and the Brave chestplate: the card <em>can</em> prime, and the
     * tab is there so a player can see that without reading the card. Primed, the same outline is
     * filled green and haloed in orange — the charge is the colour, so the change reads at a glance
     * across a board where the tab was already sitting.
     *
     * <p>The letterform is authored as a path rather than taken from a font outline, like every
     * other glyph here: a system font would render this differently on each machine and at each UI
     * scale, and the halo widths below are struck against these exact contours.
     */
    public static void drawPrimingIcon(Graphics2D g0, float x, float y, float size, boolean primed) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        float s = size / 24f;
        g.translate(x, y);
        g.scale(s, s);

        // Even-odd so the bowl's counter punches a hole rather than filling in.
        Path2D.Float p = new Path2D.Float(Path2D.WIND_EVEN_ODD);
        p.moveTo(5.5f, 4);
        p.lineTo(12.8f, 4);
        p.curveTo(16.6f, 4, 18.5f, 6f, 18.5f, 8.95f);
        p.curveTo(18.5f, 11.9f, 16.6f, 13.9f, 12.8f, 13.9f);
        p.lineTo(9.9f, 13.9f);
        p.lineTo(9.9f, 20);
        p.lineTo(5.5f, 20);
        p.closePath();
        // The counter is cut generously: unprimed the glyph is stroked rather than filled, and at
        // the 20px the tab actually renders at, a tighter hole closes up under its own outline.
        p.moveTo(9.9f, 7f);
        p.lineTo(12.5f, 7f);
        p.curveTo(14.2f, 7f, 15.2f, 7.8f, 15.2f, 8.95f);
        p.curveTo(15.2f, 10.1f, 14.2f, 10.9f, 12.5f, 10.9f);
        p.lineTo(9.9f, 10.9f);
        p.closePath();

        if (!primed) {
            // Capable, not charged: the outline alone, in the same white as the other line art.
            g.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(ICON_LINE);
            g.draw(p);
            g.dispose();
            return;
        }

        // Orange glow: successive translucent halos struck on the outline, widest first.
        float[] widths = { 5.5f, 3.6f, 2f };
        int[]   alphas = { 45, 80, 130 };
        for (int i = 0; i < widths.length; i++) {
            g.setColor(new Color(PRIMED_GLOW.getRed(), PRIMED_GLOW.getGreen(), PRIMED_GLOW.getBlue(),
                    alphas[i]));
            g.setStroke(new BasicStroke(widths[i], BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(p);
        }

        g.setColor(PRIMED_FILL);
        g.fill(p);
        g.setColor(PRIMED_LINE);
        g.setStroke(new BasicStroke(0.7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(p);

        g.dispose();
    }

    /**
     * Draws the "First Strike" glyph — a sword tilted point-up-left, a white swing trail curving
     * away from its point, and a yellow slash just ahead of the point — into the box
     * {@code [x, y, x + size, y + size]}. Same 24x24 logical grid as {@link #drawHasteIcon}.
     *
     * <p>The trail says the sword has already swung and the slash says it has already landed:
     * between them the glyph reads as "strikes first" rather than as a plain weapon.
     */
    public static void drawFirstStrikeIcon(Graphics2D g0, float x, float y, float size) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        float s = size / 24f;
        g.translate(x, y);
        g.scale(s, s);
        // The coordinates below are authored point-up-right; flipping the grid puts the point at
        // the upper left.
        g.translate(24, 0);
        g.scale(-1, 1);

        // Swing trail: a crescent leaving the point and fading as it curves down the outer side.
        // Drawn first so the point sits on top of where it starts, and wide under the point so it
        // reads as coming out of it.
        Path2D.Float trail = new Path2D.Float();
        trail.moveTo(17.9f, 6.3f);
        trail.curveTo(21.5f, 9.7f, 21.3f, 15.2f, 16.3f, 19.5f);
        trail.curveTo(18.9f, 14.7f, 19.3f, 10.7f, 16.9f, 7.7f);
        trail.closePath();
        // Kept faint so it stays behind the sword and the slash rather than competing with them.
        g.setPaint(new GradientPaint(18.3f, 7.7f, new Color(255, 255, 255, 130),
                16.3f, 19.5f, new Color(255, 255, 255, 0)));
        g.fill(trail);

        // Sword, authored point-up along x = 12 and turned 45 degrees clockwise about the centre,
        // then slid one unit toward the hilt corner so the slash has room past the point.
        Graphics2D sg = (Graphics2D) g.create();
        sg.rotate(Math.toRadians(45), 12, 12);
        sg.translate(0, 1);
        BasicStroke edge = new BasicStroke(0.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);

        Path2D.Float blade = new Path2D.Float();
        // The point stops short of the corner to leave room for the slash beyond it.
        blade.moveTo(12, 3f);
        blade.lineTo(13.7f, 6.1f);
        blade.lineTo(13.7f, 17f);
        blade.lineTo(10.3f, 17f);
        blade.lineTo(10.3f, 6.1f);
        blade.closePath();
        sg.setColor(BLADE_FILL);
        sg.fill(blade);
        sg.setStroke(edge);
        sg.setColor(SWORD_EDGE);
        sg.draw(blade);
        // Fuller: one centre line keeps the blade from reading as a flat white bar.
        sg.setColor(BLADE_FULLER);
        sg.draw(new Line2D.Float(12, 7f, 12, 15.8f));

        RoundRectangle2D.Float guard = new RoundRectangle2D.Float(7.2f, 17f, 9.6f, 2.2f, 1f, 1f);
        Rectangle2D.Float grip = new Rectangle2D.Float(11f, 19.2f, 2f, 3f);
        Ellipse2D.Float pommel = new Ellipse2D.Float(10.5f, 21.9f, 3f, 3f);
        sg.setColor(GRIP_FILL);
        sg.fill(grip);
        sg.setColor(SWORD_EDGE);
        sg.draw(grip);
        for (Shape metal : new Shape[]{ guard, pommel }) {
            sg.setColor(GUARD_FILL);
            sg.fill(metal);
            sg.setColor(SWORD_EDGE);
            sg.draw(metal);
        }
        sg.dispose();

        // Yellow slash just ahead of the point, square to the blade: a thin crescent, pointed at
        // both ends, bowing away from the sword like the leading edge of the cut.
        Path2D.Float slash = new Path2D.Float();
        slash.moveTo(16.25f, 1.25f);
        slash.quadTo(22.61f, 1.39f, 22.75f, 7.75f);
        slash.quadTo(19.92f, 4.08f, 16.25f, 1.25f);
        slash.closePath();
        g.setColor(SLASH_GLOW);
        g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(slash);
        g.setColor(SLASH_FILL);
        g.fill(slash);
        // A light edge: at tab size a heavier one swallows the yellow.
        g.setColor(SLASH_EDGE);
        g.setStroke(new BasicStroke(0.35f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(slash);

        g.dispose();
    }

    /** Draws the "Must Attack" glyph — an angry red face. See {@link #drawFace}. */
    public static void drawMustAttackIcon(Graphics2D g, float x, float y, float size) {
        drawFace(g, x, y, size, ATTACK_FILL, true);
    }

    /** Draws the "Must Block" glyph — an angry blue face. See {@link #drawFace}. */
    public static void drawMustBlockIcon(Graphics2D g, float x, float y, float size) {
        drawFace(g, x, y, size, BLOCK_FILL, true);
    }

    /** Draws the "Cannot Attack" glyph — a glum red face. See {@link #drawFace}. */
    public static void drawCannotAttackIcon(Graphics2D g, float x, float y, float size) {
        drawFace(g, x, y, size, ATTACK_FILL, false);
    }

    /** Draws the "Cannot Block" glyph — a glum blue face. See {@link #drawFace}. */
    public static void drawCannotBlockIcon(Graphics2D g, float x, float y, float size) {
        drawFace(g, x, y, size, BLOCK_FILL, false);
    }

    /**
     * Draws one of the four attack/block status faces into the box {@code [x, y, x + size, y + size]}.
     * Same 24x24 logical grid as {@link #drawHasteIcon}.
     *
     * <p>The fill says which action (red = attack, blue = block) and the expression says which way
     * it is compelled: {@code must} gets angry triangular eyes with brows slanting down toward the
     * centre and a smile; otherwise round dot eyes, brows tilted up toward the centre and a frown.
     */
    private static void drawFace(Graphics2D g0, float x, float y, float size, Color fill, boolean must) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        float s = size / 24f;
        g.translate(x, y);
        g.scale(s, s);

        Ellipse2D.Float face = new Ellipse2D.Float(12 - 9.6f, 12 - 9.6f, 19.2f, 19.2f);
        g.setColor(fill);
        g.fill(face);
        g.setColor(FACE_INK);
        g.setStroke(new BasicStroke(0.8f));
        g.draw(face);

        BasicStroke feature = new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);

        if (must) {
            // Triangular eyes, apex pointing inward.
            g.setStroke(new BasicStroke(0.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            for (Path2D.Float eye : new Path2D.Float[]{
                    tri(5.8f, 8.6f, 11f, 11.4f, 6.4f, 12.6f),
                    tri(18.2f, 8.6f, 13f, 11.4f, 17.6f, 12.6f) }) {
                g.fill(eye);
                g.draw(eye);
            }

            // Eyebrows slanting down toward the centre.
            g.setStroke(feature);
            g.draw(new Line2D.Float(5.4f, 6.4f, 10.6f, 9f));
            g.draw(new Line2D.Float(18.6f, 6.4f, 13.4f, 9f));

            g.draw(new QuadCurve2D.Float(8.2f, 15.2f, 12f, 18.6f, 15.8f, 15.2f));   // smile
        } else {
            // Dot eyes.
            g.fill(new Ellipse2D.Float(8.4f - 1.3f, 11f - 1.3f, 2.6f, 2.6f));
            g.fill(new Ellipse2D.Float(15.6f - 1.3f, 11f - 1.3f, 2.6f, 2.6f));

            // Eyebrows tilted up toward the centre.
            g.setStroke(feature);
            g.draw(new Line2D.Float(5.6f, 8.6f, 10.6f, 6.6f));
            g.draw(new Line2D.Float(18.4f, 8.6f, 13.4f, 6.6f));

            g.draw(new QuadCurve2D.Float(8.2f, 17.6f, 12f, 14.4f, 15.8f, 17.6f));   // frown
        }

        g.dispose();
    }

    private static Path2D.Float tri(float x1, float y1, float x2, float y2, float x3, float y3) {
        Path2D.Float p = new Path2D.Float();
        p.moveTo(x1, y1);
        p.lineTo(x2, y2);
        p.lineTo(x3, y3);
        p.closePath();
        return p;
    }
}
