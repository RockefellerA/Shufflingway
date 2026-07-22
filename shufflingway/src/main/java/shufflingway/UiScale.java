package shufflingway;

import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;

/**
 * Global UI scaling factor and window sizing.
 *
 * The Swing layout throughout this project is expressed in design pixels
 * targeting a 1920x1080 frame. The user chooses a target window resolution
 * (see {@link AppSettings#getResolution()}); the design is scaled uniformly
 * to fit it, preserving proportions, with any leftover area shown as
 * letterbox margin. The result is then shrunk further if it would not fit
 * the usable screen area, so the window is never clipped behind the taskbar
 * or dock. Vanilla Swing ignores {@code -Dsun.java2d.uiScale}, so we apply
 * the factor to pixel constants ourselves.
 *
 * <p>{@link #init()} must be called once at startup, before any class
 * that uses scaled constants is loaded.</p>
 */
public final class UiScale {

    private static final int DESIGN_W = 1920;
    private static final int DESIGN_H = 1080;

    /**
     * The outer window size in device pixels, after fitting the chosen resolution to the usable
     * screen area. Populated by {@link #compute()}; declared before {@link #factor} so their
     * defaults are in place before {@code compute()} overwrites them.
     */
    private static int windowW = DESIGN_W;
    private static int windowH = DESIGN_H;

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
        int[] res = targetResolution();
        int targetW = res[0];
        int targetH = res[1];

        String override = System.getProperty("shufflingway.uiscale");
        if (override != null) {
            try {
                double f = clamp(Double.parseDouble(override));
                windowW = (int) Math.round(DESIGN_W * f);
                windowH = (int) Math.round(DESIGN_H * f);
                return f;
            } catch (NumberFormatException ignored) {}
        }

        // Uniform fit of the 1920x1080 design into the chosen resolution (preserves proportions).
        double resFactor = Math.min(targetW / (double) DESIGN_W, targetH / (double) DESIGN_H);

        // Then shrink further if the chosen window would not fit the usable screen area. Using the
        // OS-reported usable bounds (which exclude the taskbar/dock on whichever edge it is docked,
        // and honour display scaling) is more robust than subtracting a fixed taskbar height, and
        // guarantees the whole board is visible rather than clipped behind the taskbar.
        double screenFit = usableScreenFit(targetW, targetH);

        windowW = (int) Math.round(targetW * screenFit);
        windowH = (int) Math.round(targetH * screenFit);
        return clamp(resFactor * screenFit);
    }

    /**
     * Returns a multiplier ≤ 1.0 that shrinks a {@code w × h} window so it fits within the usable
     * screen area (the maximum bounds a window may occupy, excluding the taskbar/dock). Returns
     * {@code 1.0} when the window already fits, or when no display is available (headless).
     */
    private static double usableScreenFit(int w, int h) {
        if (GraphicsEnvironment.isHeadless()) return 1.0;
        try {
            Rectangle usable = GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getMaximumWindowBounds();
            if (usable.width <= 0 || usable.height <= 0) return 1.0;
            double fit = Math.min(usable.width / (double) w, usable.height / (double) h);
            return Math.min(1.0, fit);
        } catch (Throwable t) {
            return 1.0;
        }
    }

    /** Parses the chosen {@code "WxH"} resolution, falling back to the 1920x1080 design size. */
    private static int[] targetResolution() {
        String r = AppSettings.getResolution();
        int x = r.indexOf('x');
        if (x > 0) {
            try {
                int w = Integer.parseInt(r.substring(0, x).trim());
                int h = Integer.parseInt(r.substring(x + 1).trim());
                if (w > 0 && h > 0) return new int[]{w, h};
            } catch (NumberFormatException ignored) {}
        }
        return new int[]{DESIGN_W, DESIGN_H};
    }

    /**
     * Outer frame width in device pixels: the chosen resolution width, shrunk if needed so the
     * window fits the usable screen. Uncovered area appears as letterbox margin around the board.
     */
    public static int windowWidth() {
        return windowW;
    }

    /** Outer frame height in device pixels. See {@link #windowWidth()}. */
    public static int windowHeight() {
        return windowH;
    }

    /**
     * Total vertical letterbox margin: the window height beyond the scaled board (0 when they
     * match). Split evenly above and below the board to centre the play area.
     */
    public static int letterboxVertical() {
        return Math.max(0, windowH - scale(DESIGN_H));
    }

    /**
     * Total horizontal letterbox margin: the window width beyond the scaled board (0 for all
     * standard resolutions). Split evenly left and right to centre the play area.
     */
    public static int letterboxHorizontal() {
        return Math.max(0, windowW - scale(DESIGN_W));
    }

    public static int scale(int px) {
        return (int) Math.round(px * factor);
    }

    public static float scale(float v) {
        return (float) (v * factor);
    }

    private static double clamp(double v) {
        return Math.max(0.5, Math.min(2.0, v));
    }
}
