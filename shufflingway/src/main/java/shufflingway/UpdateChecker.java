package shufflingway;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Scanner;

public final class UpdateChecker {

    private static final String RELEASES_API =
            "https://api.github.com/repos/RockefellerA/shufflingway/releases/latest";

    private UpdateChecker() {}

    public record ReleaseInfo(String currentVersion, String latestVersion,
                              String msiUrl, boolean updateAvailable) {}

    @FunctionalInterface
    public interface ProgressCallback {
        void onProgress(int percent);
    }

    public static String currentVersion() {
        String v = UpdateChecker.class.getPackage().getImplementationVersion();
        return v != null ? v : "dev";
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

        String msiUrl = null;
        JSONArray assets = release.getJSONArray("assets");
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.getJSONObject(i);
            if (asset.getString("name").endsWith(".msi")) {
                msiUrl = asset.getString("browser_download_url");
                break;
            }
        }

        boolean newer = !"dev".equals(current) && isNewer(latest, current);
        return new ReleaseInfo(current, latest, msiUrl, newer);
    }

    /**
     * Downloads the MSI to a temp file, launches the Windows installer, then exits the app.
     * Blocking — must be called from a background thread.
     */
    public static void downloadAndInstall(String msiUrl, ProgressCallback progress) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(msiUrl).openConnection();
        conn.setRequestProperty("User-Agent", "Shufflingway-App");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(60000);

        int total = conn.getContentLength();
        Path dest = Files.createTempFile("shufflingway-update-", ".msi");

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

        new ProcessBuilder("msiexec", "/i", dest.toAbsolutePath().toString()).start();
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
