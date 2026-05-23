package scraper;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Resolves the per-user data directory and database path for the packaged application.
 *
 * <p>On Windows the database lives in {@code %APPDATA%\Shufflingway\shufflingway.db}.
 * On any other OS (or when {@code APPDATA} is unset) it falls back to
 * {@code ~/.shufflingway/shufflingway.db} so the app also works when run directly
 * from the project during development on non-Windows machines.
 */
public final class AppPaths {

    private AppPaths() {}

    /** Returns the per-user application data directory, creating it if necessary. */
    public static Path appDataDir() {
        String appData = System.getenv("APPDATA");
        Path dir = (appData != null && !appData.isBlank())
                ? Path.of(appData, "Shufflingway")
                : Path.of(System.getProperty("user.home"), ".shufflingway");
        try { Files.createDirectories(dir); } catch (Exception ignored) {}
        return dir;
    }

    /** Returns the absolute path string to the SQLite database file. */
    public static String dbPath() {
        return appDataDir().resolve("shufflingway.db").toAbsolutePath().toString();
    }

    /** Returns the JDBC connection URL for the SQLite database. */
    public static String dbUrl() {
        return "jdbc:sqlite:" + dbPath();
    }
}
