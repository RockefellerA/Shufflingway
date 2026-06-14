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

    /** Multiplier applied to design-pixel constants. 1.0 means no scaling. */
    public static double factor = 1.0;

    private UiScale() {}

    public static void init() {
        String override = System.getProperty("shufflingway.uiscale");
        if (override != null) {
            try {
                factor = clamp(Double.parseDouble(override));
                return;
            } catch (NumberFormatException ignored) {}
        }
        if (!isMac()) return;

        Rectangle usable = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getMaximumWindowBounds();
        double wRatio = usable.width  / (double) DESIGN_W;
        double hRatio = usable.height / (double) DESIGN_H;
        double fit = Math.min(wRatio, hRatio);
        if (fit < 1.0) {
            factor = clamp(fit * 0.98);
        }
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
