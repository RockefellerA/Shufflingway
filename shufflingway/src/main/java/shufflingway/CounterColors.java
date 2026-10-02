package shufflingway;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Counter colors, as the "#rrggbb" strings the setting stores and the counter overlay draws with.
 *
 * <p>Each player's counters are drawn in their own color: the counters on the local player's
 * Characters in the color they chose, and the counters on the opponent's in the opponent's. A
 * networked opponent sends their choice with their deck list; against the CPU, or when the two
 * choices are too alike to tell apart on the board, the opponent's counters take the inverse of
 * the local color instead.
 */
public final class CounterColors {

    private CounterColors() {}

    /** The counter color for a player who has not chosen one. */
    public static final String DEFAULT = "#36b06a";

    /**
     * Two counter colors nearer than this, as straight-line distance in RGB (0–441), are hard to
     * tell apart at counter size.
     */
    static final double MIN_DISTANCE = 60;

    private static final Pattern HEX = Pattern.compile("#[0-9a-fA-F]{6}");

    /** {@code hex} normalised to lower case if it is a well-formed "#rrggbb", else {@code null}. */
    public static String validOrNull(String hex) {
        if (hex == null) return null;
        String v = hex.trim();
        return HEX.matcher(v).matches() ? v.toLowerCase(Locale.ROOT) : null;
    }

    /** {@code hex} if well formed, else {@link #DEFAULT}: a hand-edited setting cannot break drawing. */
    public static String validOrDefault(String hex) {
        String v = validOrNull(hex);
        return v != null ? v : DEFAULT;
    }

    /**
     * The RGB inverse of {@code hex}. A mid-tone inverts to almost itself (mid grey to mid grey),
     * so when the two would be hard to tell apart the far end of the lightness scale is used
     * instead: near-black for a light color, near-white for a dark one.
     */
    public static String inverse(String hex) {
        int rgb = rgbOf(hex);
        int inv = ~rgb & 0xFFFFFF;
        if (distance(rgb, inv) >= MIN_DISTANCE) return toHex(inv);
        return luminance(rgb) > 0.5 ? "#202020" : "#e0e0e0";
    }

    /**
     * The color for the opponent's counters, given the local player's. The opponent's own choice
     * when they sent one that is distinct from the local color; otherwise (the CPU, an older client
     * that sends none, or the same choice as the local player) the inverse of the local color.
     */
    public static String forOpponent(String local, String remote) {
        String l = validOrDefault(local);
        String r = validOrNull(remote);
        if (r != null && distance(rgbOf(l), rgbOf(r)) >= MIN_DISTANCE) return r;
        return inverse(l);
    }

    private static int rgbOf(String hex) {
        return Integer.parseInt(validOrDefault(hex).substring(1), 16);
    }

    private static String toHex(int rgb) {
        return String.format(Locale.ROOT, "#%06x", rgb);
    }

    private static double distance(int a, int b) {
        int dr = ((a >> 16) & 0xFF) - ((b >> 16) & 0xFF);
        int dg = ((a >> 8) & 0xFF) - ((b >> 8) & 0xFF);
        int db = (a & 0xFF) - (b & 0xFF);
        return Math.sqrt(dr * dr + dg * dg + db * db);
    }

    /** Perceived lightness, 0 (black) to 1 (white). */
    private static double luminance(int rgb) {
        return (0.299 * ((rgb >> 16) & 0xFF) + 0.587 * ((rgb >> 8) & 0xFF) + 0.114 * (rgb & 0xFF)) / 255.0;
    }
}
