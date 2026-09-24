package shufflingway;

import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.io.InputStream;

public class FontLoader {

    /** Classpath directory holding the bundled UI fonts (.otf/.ttf). */
    private static final String FONT_DIR = "/fonts";

    /**
     * Bundled file for the numeric badges baked onto card art — the damage and power pills, the
     * modified-cost badge, and the deck's remaining-card count. Those render at 10–15pt over
     * artwork, a size at which a pixel face stays crisp and {@link #UI_FONT_FILE} does not.
     * Reached through {@link #loadOverlayFont}, never {@link #loadPixelFont}.
     */
    private static final String OVERLAY_FONT_FILE = "Pixel_NES.otf";

    /** Bundled file for all other app text. Reached through {@link #loadPixelFont}. */
    private static final String UI_FONT_FILE = "FF36.ttf";

    /** Point size at which cap heights are measured for normalization; larger = less rounding error. */
    private static final float MEASURE_SIZE = 100f;

    /**
     * Cap height of {@link #OVERLAY_FONT_FILE} (Pixel NES) at {@link #MEASURE_SIZE}. This is the size
     * baseline: every {@code loadPixelFont} size constant was tuned against it, so the overlay font
     * scales by exactly 1.0 and every other font is matched to it.
     */
    private static final double REFERENCE_CAP_HEIGHT =
            capHeight(loadFontResource(FONT_DIR + "/" + OVERLAY_FONT_FILE));

    /** The body UI font, derived per-call to the requested size. */
    private static Font baseFont;

    /** Per-font multiplier that matches {@link #baseFont}'s cap height to {@link #REFERENCE_CAP_HEIGHT}. */
    private static double fontScale = 1.0;

    /** The card-overlay font, derived per-call to the requested size. */
    private static Font overlayFont;

    /** Cap-height multiplier for {@link #overlayFont}; 1.0 by construction, but measured for safety. */
    private static double overlayScale = 1.0;

    static {
        baseFont = loadRegistered(UI_FONT_FILE);
        overlayFont = loadRegistered(OVERLAY_FONT_FILE);
        if (baseFont == null) baseFont = overlayFont;   // body text is worth more than the right face
        if (baseFont == null)
            System.err.println("Failed to load UI font from " + FONT_DIR);
        fontScale = scaleFor(baseFont);
        overlayScale = scaleFor(overlayFont);
    }

    /**
     * Returns the body UI font derived to {@code size} (after UI scaling). Everything except the
     * numeric card overlays flows through here — see {@link #loadOverlayFont} for those.
     */
    public static Font loadPixelFont(float size) {
        float scaled = UiScale.scale(size);
        if (baseFont != null) return baseFont.deriveFont((float) (scaled * fontScale));
        return new Font("Arial", Font.PLAIN, (int) scaled);
    }

    /**
     * Returns the card-overlay font ({@link #OVERLAY_FONT_FILE}) derived to {@code size}, cap-height
     * matched to {@link #loadPixelFont} so the same argument yields the same visual size.
     */
    public static Font loadOverlayFont(float size) {
        if (overlayFont == null) return loadPixelFont(size);
        return overlayFont.deriveFont((float) (UiScale.scale(size) * overlayScale));
    }

    /**
     * The overlay font at exactly {@code size}, with no {@link UiScale} factor applied. For
     * components such as {@link PhaseTracker} whose geometry is also in unscaled pixels — scaling
     * only their text would shrink it inside fixed-size chrome.
     */
    public static Font overlayFontUnscaled(float size) {
        if (overlayFont == null) return new Font(Font.MONOSPACED, Font.BOLD, (int) size);
        return overlayFont.deriveFont((float) (size * overlayScale));
    }

    /** Unscaled twin of {@link #loadPixelFont}, for the same callers as {@link #overlayFontUnscaled}. */
    public static Font uiFontUnscaled(float size) {
        if (baseFont == null) return new Font("Arial", Font.PLAIN, (int) size);
        return baseFont.deriveFont((float) (size * fontScale));
    }

    // -------------------------------------------------------------------------
    // Glyph fallback
    // -------------------------------------------------------------------------

    /**
     * Stand-in for characters the bundled fonts have no glyph for. Both bundled fonts cover ASCII
     * and Latin-1 only, so arrows (← →) and the CP-cost brackets (《 》) would otherwise render as
     * missing-glyph boxes. {@code Font.DIALOG} is a JVM logical font, so it is always present.
     */
    private static final Font FALLBACK_BASE = new Font(Font.DIALOG, Font.PLAIN, 1);

    /** Cap-height multiplier that sizes {@link #FALLBACK_BASE} to sit alongside the base font. */
    private static final double FALLBACK_SCALE = scaleFor(FALLBACK_BASE);

    /**
     * The fallback font at the same requested size as {@link #loadPixelFont}, cap-height matched so
     * substituted glyphs sit at the same visual size as the pixel text around them.
     */
    public static Font fallbackFont(float size) {
        float scaled = UiScale.scale(size);
        return FALLBACK_BASE.deriveFont((float) (scaled * FALLBACK_SCALE));
    }

    /** True when the base UI font has a glyph for every character in {@code text}. */
    public static boolean canDisplayAll(String text) {
        return baseFont == null || text == null || baseFont.canDisplayUpTo(text) < 0;
    }

    /**
     * Draws {@code text} with its baseline at ({@code x}, {@code y}), rendering each character in
     * {@code base} and falling back to {@code fallback} only for the ones {@code base} cannot
     * display. Leaves {@code g2}'s font set to {@code base}.
     *
     * @return the total advance width, so callers can lay out what follows
     */
    public static float drawWithFallback(Graphics2D g2, String text, float x, float y,
                                         Font base, Font fallback) {
        float cursor = x;
        for (int i = 0; i < text.length(); ) {
            Font run = fontFor(text.charAt(i), base, fallback);
            int end = i + 1;
            while (end < text.length() && fontFor(text.charAt(end), base, fallback) == run) end++;
            String chunk = text.substring(i, end);
            g2.setFont(run);
            g2.drawString(chunk, cursor, y);
            cursor += (float) run.getStringBounds(chunk, g2.getFontRenderContext()).getWidth();
            i = end;
        }
        g2.setFont(base);
        return cursor - x;
    }

    /**
     * Advance width {@link #drawWithFallback} would produce for {@code text} — use this instead of
     * {@code FontMetrics.stringWidth} when centring text that may contain substituted glyphs.
     */
    public static float widthWithFallback(Graphics2D g2, String text, Font base, Font fallback) {
        float w = 0;
        for (int i = 0; i < text.length(); ) {
            Font run = fontFor(text.charAt(i), base, fallback);
            int end = i + 1;
            while (end < text.length() && fontFor(text.charAt(end), base, fallback) == run) end++;
            w += (float) run.getStringBounds(text.substring(i, end), g2.getFontRenderContext()).getWidth();
            i = end;
        }
        return w;
    }

    private static Font fontFor(char c, Font base, Font fallback) {
        return base.canDisplay(c) ? base : fallback;
    }

    /**
     * Wraps {@code text} in HTML so the characters the base UI font lacks render in the fallback
     * family, for Swing components ({@code JLabel}, {@code AbstractButton}) that take a single
     * {@link Font} and so cannot mix runs the way {@link #drawWithFallback} does. Everything else
     * keeps the component's own font.
     *
     * <p>Returns {@code text} unchanged when nothing needs substituting, so the common case keeps
     * plain-text layout rather than paying for Swing's HTML view.
     */
    public static String htmlWithFallback(String text) {
        if (text == null || canDisplayAll(text)) return text;
        StringBuilder sb = new StringBuilder("<html>");
        boolean inFallback = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean needs = baseFont != null && !baseFont.canDisplay(c);
            if (needs != inFallback) {
                sb.append(needs ? "<span style='font-family:" + FALLBACK_BASE.getFamily() + "'>"
                                : "</span>");
                inFallback = needs;
            }
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                default  -> {
                    if (c > 127) sb.append("&#").append((int) c).append(';');
                    else         sb.append(c);
                }
            }
        }
        if (inFallback) sb.append("</span>");
        return sb.append("</html>").toString();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Multiplier that makes {@code font}'s cap height match the default font's, so its glyphs render
     * at roughly the same visual size for a given {@code loadPixelFont} argument. Returns 1.0 when
     * either measurement is unavailable (e.g. headless load failure).
     */
    private static double scaleFor(Font font) {
        double h = capHeight(font);
        return (REFERENCE_CAP_HEIGHT > 0 && h > 0) ? REFERENCE_CAP_HEIGHT / h : 1.0;
    }

    /** Visual height of a capital "H" for {@code font} at {@link #MEASURE_SIZE}, or 0 if unmeasurable. */
    private static double capHeight(Font font) {
        if (font == null) return 0;
        FontRenderContext frc = new FontRenderContext(null, true, true);
        GlyphVector gv = font.deriveFont(MEASURE_SIZE).createGlyphVector(frc, "H");
        return gv.getVisualBounds().getHeight();
    }

    /** Loads a bundled font and registers it with the graphics environment. */
    private static Font loadRegistered(String fileName) {
        Font f = loadFontResource(FONT_DIR + "/" + fileName);
        if (f != null) {
            try { GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(f); }
            catch (Exception ignored) {}
        }
        return f;
    }

    /** Creates a {@link Font} from a classpath resource path, or {@code null} if it can't be read. */
    private static Font loadFontResource(String path) {
        try (InputStream is = FontLoader.class.getResourceAsStream(path)) {
            if (is == null) return null;
            return Font.createFont(Font.TRUETYPE_FONT, is);
        } catch (Exception e) {
            return null;
        }
    }

}
