package scraper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Resolves the per-user data directory and database path for the packaged application.
 *
 * <p>On Windows the database lives in {@code %APPDATA%\Shufflingway\shufflingway.db}.
 * On macOS it lives in {@code ~/Library/Application Support/Shufflingway/shufflingway.db}.
 * On other OSes (or when {@code APPDATA} is unset on Windows) it falls back to
 * {@code ~/.shufflingway/shufflingway.db} so the app also works when run directly
 * from the project during development.
 */
public final class AppPaths {

    private AppPaths() {}

    /** Returns the per-user application data directory, creating it if necessary. */
    public static Path appDataDir() {
        String home = System.getProperty("user.home");
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        Path dir;
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            dir = (appData != null && !appData.isBlank())
                    ? Path.of(appData, "Shufflingway")
                    : Path.of(home, ".shufflingway");
        } else if (os.contains("mac") || os.contains("darwin")) {
            dir = Path.of(home, "Library", "Application Support", "Shufflingway");
        } else {
            dir = Path.of(home, ".shufflingway");
        }
        try { Files.createDirectories(dir); } catch (IOException ignored) {}
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
