package shufflingway;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Properties;

/**
 * Persists application settings to {@code settings.ini} in the same directory
 * as the card database (see {@link scraper.AppPaths}).
 *
 * All access is through static methods; the file is loaded once on class
 * initialisation and can be saved at any time.
 */
public final class AppSettings {

    private static final String DIR  = scraper.AppPaths.appDataDir().toString();
    private static final String PATH = DIR + File.separator + "settings.ini";
    private static final String CARDBACK_CUSTOM_DIR =
            DIR + File.separator + "cardback" + File.separator + "custom";

    private static final Properties props = new Properties();

    static { load(); }

    private AppSettings() {}

    // -------------------------------------------------------------------------
    // Load / save
    // -------------------------------------------------------------------------

    /** Loads settings from disk, silently ignoring any I/O errors. */
    public static void load() {
        File file = new File(PATH);
        if (!file.exists()) return;
        try (FileInputStream fis = new FileInputStream(file)) {
            props.load(fis);
        } catch (IOException ignored) {}
    }

    /** Writes current settings to disk, creating parent directories as needed. */
    public static void save() {
        try {
            new File(DIR).mkdirs();
            try (FileOutputStream fos = new FileOutputStream(PATH)) {
                props.store(fos, "Shufflingway Settings");
            }
        } catch (IOException ignored) {}
    }

    // -------------------------------------------------------------------------
    // Settings
    // -------------------------------------------------------------------------

    /** Returns the saved P1 board color selection, or {@code "Default"} if unset. */
    public static String getP1BoardColor() {
        return props.getProperty("p1.board.color", "Default");
    }

    /** Saves the P1 board color selection (call {@link #save()} to persist). */
    public static void setP1BoardColor(String color) {
        props.setProperty("p1.board.color", color);
    }

    /** Returns the saved P2 board color selection, or {@code "Default"} if unset. */
    public static String getP2BoardColor() {
        return props.getProperty("p2.board.color", "Default");
    }

    /** Saves the P2 board color selection (call {@link #save()} to persist). */
    public static void setP2BoardColor(String color) {
        props.setProperty("p2.board.color", color);
    }

    /**
     * Returns which side the info panel is docked to: {@code "left"} or {@code "right"}.
     * Defaults to {@code "left"} if unset (matching the former log position).
     */
    public static String getSidePanelSide() {
        return props.getProperty("side.panel.side", "left");
    }

    /** Saves the side panel docking side (call {@link #save()} to persist). */
    public static void setSidePanelSide(String side) {
        props.setProperty("side.panel.side", side);
    }

    /**
     * Returns the saved side-panel pixel width, or {@code defaultW} if no value
     * has been persisted yet.
     */
    public static int getSidePanelWidth(int defaultW) {
        String v = props.getProperty("side.panel.width");
        if (v == null) return defaultW;
        try { return Integer.parseInt(v); } catch (NumberFormatException e) { return defaultW; }
    }

    /** Saves the side-panel pixel width (call {@link #save()} to persist). */
    public static void setSidePanelWidth(int w) {
        props.setProperty("side.panel.width", String.valueOf(w));
    }

    /** Returns the directory where custom card back images are stored. */
    public static String getCardbackCustomDir() {
        return CARDBACK_CUSTOM_DIR;
    }

    /** Returns the absolute path of the user's custom card back, or {@code ""} if none is set. */
    public static String getCustomCardbackPath() {
        return props.getProperty("cardback.custom.path", "");
    }

    /** Sets the custom card back path (call {@link #save()} to persist). Pass {@code ""} to reset. */
    public static void setCustomCardbackPath(String path) {
        props.setProperty("cardback.custom.path", path);
    }

    /**
     * Master switch for the Debug section in the Preferences dialog. The Debug section (and any
     * individual debug toggles) are hidden unless {@code settings.ini} contains {@code debug=1}.
     * There is intentionally no setter — users opt in by editing the file by hand.
     */
    public static boolean isDebugEnabled() {
        return "1".equals(props.getProperty("debug", "0"));
    }

    /**
     * Debug toggle: when {@code true}, the opening-hand mulligan button stays enabled across
     * repeated mulligans (overrides the once-per-game limit). Defaults to {@code false}.
     */
    public static boolean isDebugUnlimitedMulligan() {
        return Boolean.parseBoolean(props.getProperty("debug.unlimited.mulligan", "false"));
    }

    /** Sets the unlimited-mulligan debug flag (call {@link #save()} to persist). */
    public static void setDebugUnlimitedMulligan(boolean enabled) {
        props.setProperty("debug.unlimited.mulligan", Boolean.toString(enabled));
    }

    /**
     * Debug toggle: when {@code true}, P1 always wins the opening coin flip and goes first.
     * Defaults to {@code false} (random 50/50 flip).
     */
    public static boolean isDebugAlwaysWinCoinFlip() {
        return Boolean.parseBoolean(props.getProperty("debug.always.win.coin.flip", "false"));
    }

    /** Sets the always-win-coin-flip debug flag (call {@link #save()} to persist). */
    public static void setDebugAlwaysWinCoinFlip(boolean enabled) {
        props.setProperty("debug.always.win.coin.flip", Boolean.toString(enabled));
    }
}
