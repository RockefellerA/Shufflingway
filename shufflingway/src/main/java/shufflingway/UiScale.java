package shufflingway;

import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.util.ArrayList;
import java.util.List;

/**
 * Global UI scaling factor and window sizing.
 *
 * The Swing layout throughout this project is expressed in design pixels
 * targeting a 1920x1080 frame. The user chooses a target window resolution
 * (see {@link AppSettings#getResolution()}), or gets the largest the screen fits.
 * The largest size a screen fits opens maximized, with the design fitted to the
 * screen's work area (the screen less the taskbar). A smaller one opens as a
 * window of that size, shrunk if it would not fit the work area, with the design
 * scaled uniformly to fit it and any leftover area shown as letterbox margin.
 * Vanilla Swing ignores {@code -Dsun.java2d.uiScale}, so we apply the factor to
 * pixel constants ourselves.
 *
 * <p>{@link #init()} must be called once at startup, before any class
 * that uses scaled constants is loaded.</p>
 */
public final class UiScale {

    private static final int DESIGN_W = 1920;
    private static final int DESIGN_H = 1080;

    /**
     * The window's own chrome at 1080p — title bar, menu bar and frame border — which Swing draws
     * at a fixed size whatever the factor. The board was laid out in the 1018px of a 1080px window
     * left below it, so a window smaller than the design has to fit the board in what the chrome
     * leaves, not in its whole height.
     */
    private static final int CHROME_H = 62;

    /**
     * The title bar and menu bar of a maximized window, whose frame border is off-screen: 45 under
     * FlatLaf's title bar and 46 under the Windows one (the Classic theme), measured at 100%.
     */
    private static final int MAXIMIZED_CHROME_H = 46;

    /**
     * The 16:9 size after the largest choice in {@link AppSettings#RESOLUTIONS}. A screen that fits
     * it has room for more than any choice, so even the largest opens there as a window rather
     * than stretching the board past the scales it has been laid out for.
     */
    private static final int[] NEXT_SIZE_UP = {3840, 2160};

    /**
     * The outer window size in device pixels, after fitting the chosen resolution to the usable
     * screen area; for a maximized window, the work area it fills. Populated by {@link #compute()},
     * like the fields below; all are declared before {@link #factor} so their defaults are in place
     * before {@code compute()} overwrites them.
     */
    private static int windowW = DESIGN_W;
    private static int windowH = DESIGN_H;

    /** The window chrome above the board: {@link #CHROME_H}, or {@link #MAXIMIZED_CHROME_H}. */
    private static int chromeH = CHROME_H;

    /** The resolution in effect: the one chosen in Preferences, else the largest the screen fits. */
    private static String resolution = AppSettings.DEFAULT_RESOLUTION;

    /** Whether the window opens maximized. */
    private static boolean maximized;

    /**
     * Where the window opens, in screen coordinates: the work area it fills maximized, or a window
     * centred in that work area. Null when there is no display to measure.
     */
    private static Rectangle windowBounds;

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
        GraphicsConfiguration gc = targetScreen();
        Rectangle screen = gc == null ? null : gc.getBounds();
        String chosen = AppSettings.getResolution();
        if (chosen == null && screen != null) {
            List<String> fit = resolutionsFor(screen.width, screen.height);
            chosen = fit.get(fit.size() - 1);
        }
        if (chosen != null) resolution = chosen;
        int[] res = parse(resolution);
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

        Rectangle work = workArea(gc);
        if (screen != null && work != null && opensMaximized(resolution, screen.width, screen.height)) {
            maximized = true;
            windowBounds = work;
            chromeH = MAXIMIZED_CHROME_H;
            windowW = work.width;
            windowH = work.height;
            return clamp(Math.min(work.width / (double) DESIGN_W,
                    (work.height - MAXIMIZED_CHROME_H) / (double) (DESIGN_H - CHROME_H)));
        }

        // Uniform fit of the 1920x1080 design into the chosen resolution (preserves proportions),
        // the height fitted below the unscaled chrome. At 720p the chrome is a larger share of the
        // window than the design gave it, and a plain fit would overlap the two players' rows.
        double resFactor = Math.min(targetW / (double) DESIGN_W,
                (targetH - CHROME_H) / (double) (DESIGN_H - CHROME_H));

        // Then shrink further if the chosen window would not fit the work area, which excludes the
        // taskbar or dock on whichever edge it is docked and honours display scaling, so the whole
        // board is visible rather than clipped behind the taskbar.
        double screenFit = work == null ? 1.0
                : Math.min(1.0, Math.min(work.width / (double) targetW, work.height / (double) targetH));

        windowW = (int) Math.round(targetW * screenFit);
        windowH = (int) Math.round(targetH * screenFit);
        if (work != null)
            windowBounds = new Rectangle(work.x + (work.width - windowW) / 2,
                    work.y + (work.height - windowH) / 2, windowW, windowH);
        return clamp(resFactor * screenFit);
    }

    /**
     * The choices in {@link AppSettings#RESOLUTIONS} that fit a {@code screenW x screenH} screen,
     * smallest first. Never empty: the smallest is offered even to a screen it does not fit.
     */
    public static List<String> resolutionsFor(int screenW, int screenH) {
        List<String> fit = new ArrayList<>();
        for (String r : AppSettings.RESOLUTIONS)
            if (fits(parse(r), screenW, screenH)) fit.add(r);
        if (fit.isEmpty()) fit.add(AppSettings.RESOLUTIONS[0]);
        return fit;
    }

    /** The choices that fit the screen {@code gc} shows; all of them when there is none to measure. */
    public static List<String> resolutionsFor(GraphicsConfiguration gc) {
        if (gc == null) return List.of(AppSettings.RESOLUTIONS);
        Rectangle b = gc.getBounds();
        return resolutionsFor(b.width, b.height);
    }

    /**
     * Whether {@code res} opens maximized on a {@code screenW x screenH} screen. It does when it is
     * the largest size the screen fits, so that the next size up does not fit; a size bigger than
     * the screen does too. Otherwise the screen has room for more, and it opens as a window.
     */
    public static boolean opensMaximized(String res, int screenW, int screenH) {
        int i = List.of(AppSettings.RESOLUTIONS).indexOf(res);
        int[] next = i >= 0 && i + 1 < AppSettings.RESOLUTIONS.length
                ? parse(AppSettings.RESOLUTIONS[i + 1]) : NEXT_SIZE_UP;
        return !fits(next, screenW, screenH);
    }

    private static boolean fits(int[] size, int screenW, int screenH) {
        return size[0] <= screenW && size[1] <= screenH;
    }

    /**
     * The monitor the window opens on: the one it last closed on, else the primary. Null when
     * there is no display.
     */
    private static GraphicsConfiguration targetScreen() {
        if (GraphicsEnvironment.isHeadless()) return null;
        try {
            GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
            String saved = AppSettings.getWindowScreen();
            for (GraphicsDevice d : ge.getScreenDevices())
                if (d.getIDstring().equals(saved)) return d.getDefaultConfiguration();
            return ge.getDefaultScreenDevice().getDefaultConfiguration();
        } catch (Throwable t) {
            return null;
        }
    }

    /** {@code gc}'s bounds less the taskbar or dock, in screen coordinates; null if empty or unknown. */
    private static Rectangle workArea(GraphicsConfiguration gc) {
        if (gc == null) return null;
        try {
            Rectangle b = gc.getBounds();
            Insets in = Toolkit.getDefaultToolkit().getScreenInsets(gc);
            Rectangle work = new Rectangle(b.x + in.left, b.y + in.top,
                    b.width - in.left - in.right, b.height - in.top - in.bottom);
            return work.width > 0 && work.height > 0 ? work : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** The resolution in effect: the one chosen in Preferences, else the largest the screen fits. */
    public static String resolution() {
        return resolution;
    }

    /** Whether the window opens maximized, filling the work area of its screen. */
    public static boolean isMaximized() {
        return maximized;
    }

    /**
     * Where the window opens, in screen coordinates: the work area it fills when
     * {@link #isMaximized()}, else a window centred in it. Null when there is no display to measure.
     */
    public static Rectangle windowBounds() {
        return windowBounds == null ? null : new Rectangle(windowBounds);
    }

    /** Parses a {@code "WxH"} resolution, falling back to the 1920x1080 design size. */
    private static int[] parse(String r) {
        int x = r == null ? -1 : r.indexOf('x');
        if (r != null && x > 0) {
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
     *
     * <p>Measured both with the chrome scaled and with it at its fixed size, and the smaller
     * taken. Below 1080p the window is taller than the scaled design by the chrome's extra share,
     * which is not the board's to spare. Above it the chrome is a smaller share than the design
     * gave it, and the height that frees goes to the board's middle, as it always has. A
     * maximized window's height is its work area's, below the smaller chrome it has there.
     */
    public static int letterboxVertical() {
        return Math.max(0, Math.min(windowH - scale(DESIGN_H),
                windowH - chromeH - scale(DESIGN_H - CHROME_H)));
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
