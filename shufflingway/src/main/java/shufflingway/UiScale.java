package shufflingway;

import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;

/**
 * Global UI scaling factor.
 *
 * The Swing layout throughout this project is expressed in design pixels
 * targeting a 1920x1080 frame. On macOS, every laptop smaller than 16"
 * reports a logical screen size smaller than that (e.g. 14" MBP =
 * 1512x982 logical), so the frame is clipped off-screen. Vanilla Swing on
 * macOS ignores {@code -Dsun.java2d.uiScale}, so the only way to shrink
 * the UI is to multiply pixel constants ourselves.
 *
 * <p>{@link #init()} must be called once at startup, before any class
 * that uses scaled constants is loaded.</p>
 */
public final class UiScale {

    private static final int DESIGN_W = 1920;
    private static final int DESIGN_H = 1080;

    /**
     * Multiplier applied to design-pixel constants. 1.0 means no scaling.
     *
     * <p>Computed in the static initializer so it is correct on the very first
     * read, regardless of which class loads first. Calling {@link #init()}
     * remains supported as an explicit no-op marker for documentation.</p>
     */
    public static double factor = compute();

    private UiScale() {}

    /** No-op kept for clarity at the call site; static init does the real work. */
    public static void init() {}

    private static double compute() {
        String override = System.getProperty("shufflingway.uiscale");
        if (override != null) {
            try {
                return clamp(Double.parseDouble(override));
            } catch (NumberFormatException ignored) {}
        }
        if (!isMac()) return 1.0;

        Rectangle usable = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getMaximumWindowBounds();
        double wRatio = usable.width  / (double) DESIGN_W;
        double hRatio = usable.height / (double) DESIGN_H;
        double fit = Math.min(wRatio, hRatio);
        return (fit < 1.0) ? clamp(fit * 0.98) : 1.0;
    }

    public static int scale(int px) {
        return (int) Math.round(px * factor);
    }

    public static float scale(float v) {
        return (float) (v * factor);
    }

    private static boolean isMac() {
        String os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("mac") || os.contains("darwin");
    }

    private static double clamp(double v) {
        return Math.max(0.5, Math.min(2.0, v));
    }
}
