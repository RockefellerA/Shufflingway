package shufflingway;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Scanner;

public final class UpdateChecker {

    private static final String RELEASES_API =
            "https://api.github.com/repos/RockefellerA/shufflingway/releases/latest";

    private UpdateChecker() {}

    /** Installer kind picked from a release's assets, matched to the current OS. */
    public enum InstallerKind {
        MSI(".msi"),  // Windows
        DMG(".dmg"),  // macOS
        DEB(".deb");  // Linux (Debian/Ubuntu)

        final String extension;
        InstallerKind(String extension) { this.extension = extension; }
    }

    public record ReleaseInfo(String currentVersion, String latestVersion,
                              String installerUrl, InstallerKind installerKind,
                              boolean updateAvailable) {}

    @FunctionalInterface
    public interface ProgressCallback {
        void onProgress(int percent);
    }

    public static String currentVersion() {
        String v = UpdateChecker.class.getPackage().getImplementationVersion();
        return v != null ? v : "dev";
    }

    /** Returns the installer kind appropriate for the current platform, or {@code null} if none. */
    static InstallerKind installerKindForCurrentPlatform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win"))                                  return InstallerKind.MSI;
        if (os.contains("mac") || os.contains("darwin"))         return InstallerKind.DMG;
        if (os.contains("nix") || os.contains("nux") || os.contains("aix")) return InstallerKind.DEB;
        return null;
    }

    /** Blocking — must be called from a background thread. */
    public static ReleaseInfo checkLatest() throws IOException {
        String current = currentVersion();

        HttpURLConnection conn = (HttpURLConnection) new URL(RELEASES_API).openConnection();
        conn.setRequestProperty("Accept", "application/vnd.github+json");
        conn.setRequestProperty("User-Agent", "Shufflingway-App");
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(10000);

        String body;
        try (InputStream in = conn.getInputStream();
             Scanner sc = new Scanner(in, "UTF-8")) {
            body = sc.useDelimiter("\\A").next();
        }

        JSONObject release = new JSONObject(body);
        String tag = release.getString("tag_name");
        String latest = tag.startsWith("v") ? tag.substring(1) : tag;

        InstallerKind kind = installerKindForCurrentPlatform();
        String installerUrl = null;
        if (kind != null) {
            JSONArray assets = release.getJSONArray("assets");
            for (int i = 0; i < assets.length(); i++) {
                JSONObject asset = assets.getJSONObject(i);
                if (asset.getString("name").toLowerCase(Locale.ROOT).endsWith(kind.extension)) {
                    installerUrl = asset.getString("browser_download_url");
                    break;
                }
            }
        }

        boolean newer = !"dev".equals(current) && isNewer(latest, current);
        return new ReleaseInfo(current, latest, installerUrl, kind, newer);
    }

    /**
     * Downloads the installer for {@code kind} to a temp file, launches the platform-appropriate
     * installer or DMG mount, then exits the app. Blocking — must be called from a background thread.
     */
    public static void downloadAndInstall(String installerUrl, InstallerKind kind,
                                          ProgressCallback progress) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(installerUrl).openConnection();
        conn.setRequestProperty("User-Agent", "Shufflingway-App");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(60000);

        int total = conn.getContentLength();
        Path dest = Files.createTempFile("shufflingway-update-", kind.extension);

        try (InputStream in = conn.getInputStream();
             var out = Files.newOutputStream(dest)) {
            byte[] buf = new byte[8192];
            long downloaded = 0;
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                downloaded += n;
                if (total > 0) progress.onProgress((int) (downloaded * 100 / total));
            }
        }

        String path = dest.toAbsolutePath().toString();
        ProcessBuilder pb = switch (kind) {
            // Windows: launch the MSI installer.
            case MSI -> new ProcessBuilder("msiexec", "/i", path);
            // macOS: `open` mounts the DMG; user drags the new .app to /Applications.
            //   The running app must quit so the user can replace the bundle.
            case DMG -> new ProcessBuilder("open", path);
            // Linux: hand the .deb to the user's default GUI installer (e.g. gnome-software).
            //   Avoids requiring sudo from this process.
            case DEB -> new ProcessBuilder("xdg-open", path);
        };
        pb.start();
        System.exit(0);
    }

    private static boolean isNewer(String candidate, String current) {
        int[] a = parseVer(candidate);
        int[] b = parseVer(current);
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int ai = i < a.length ? a[i] : 0;
            int bi = i < b.length ? b[i] : 0;
            if (ai > bi) return true;
            if (ai < bi) return false;
        }
        return false;
    }

    private static int[] parseVer(String v) {
        String[] parts = v.split("\\.");
        int[] nums = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try { nums[i] = Integer.parseInt(parts[i]); }
            catch (NumberFormatException ignored) {}
        }
        return nums;
    }
}
