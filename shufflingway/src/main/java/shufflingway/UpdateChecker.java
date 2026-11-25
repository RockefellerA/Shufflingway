package shufflingway;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ProcessBuilder.Redirect;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Scanner;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONObject;

public final class UpdateChecker {

    private static final String RELEASES_API =
            "https://api.github.com/repos/RockefellerA/shufflingway/releases/latest";

    /** Passed to a copy started by an in-place update, followed by the PID of the copy it replaces. */
    static final String AFTER_UPDATE_ARG = "--after-update";

    /** How long a restarted copy waits for the one it replaces to exit before starting anyway. */
    private static final long REPLACED_COPY_WAIT_SECONDS = 30;

    /**
     * A release's jar asset, e.g. {@code shufflingway-0.2.348-java25.jar}. The {@code javaNN} suffix is
     * the Java feature version of the runtime that release's installer bundles.
     */
    private static final Pattern JAR_ASSET =
            Pattern.compile("shufflingway-[0-9A-Za-z.]+-java(\\d+)\\.jar", Pattern.CASE_INSENSITIVE);

    /** The launcher config's single classpath entry: {@code app.classpath=$APPDIR\<jar>}. */
    private static final Pattern CLASSPATH_LINE =
            Pattern.compile("(?m)^app\\.classpath=\\$APPDIR\\\\(.*)$");

    /** The launcher config's version option, which jpackage writes and nothing in the app reads. */
    private static final Pattern APP_VERSION_LINE =
            Pattern.compile("(?m)^java-options=-Djpackage\\.app-version=.*$");

    /** A plain jar file name, with no folder part or classpath separator. */
    private static final Pattern PLAIN_JAR_NAME = Pattern.compile("[^\\\\/:*?\"<>|;]+\\.jar");

    /** Files an update writes into the app folder: jars and interrupted downloads of them. */
    private static final Pattern UPDATE_FILE =
            Pattern.compile("shufflingway[^\\\\/]*\\.jar(\\.part)?", Pattern.CASE_INSENSITIVE);

    /** A version that can go into the launcher config as-is. */
    private static final Pattern PLAIN_VERSION = Pattern.compile("[0-9A-Za-z.+-]+");

    private UpdateChecker() {}

    /** Installer kind picked from a release's assets, matched to the current OS. */
    public enum InstallerKind {
        MSI(".msi"),  // Windows
        DMG(".dmg"),  // macOS
        DEB(".deb");  // Linux (Debian/Ubuntu)

        final String extension;
        InstallerKind(String extension) { this.extension = extension; }
    }

    /** A release's jar, with the SHA-256 GitHub reports for it and the Java version it needs. */
    public record JarAsset(String name, String url, String sha256, int javaFeature) {}

    /** {@code jar} is null when the release has no jar asset, or none GitHub reports a SHA-256 for. */
    public record ReleaseInfo(String currentVersion, String latestVersion,
                              String installerUrl, InstallerKind installerKind,
                              JarAsset jar, boolean updateAvailable) {}

    /** An installed copy: its launcher, its app folder, the launcher's config and the jar it runs. */
    record Installation(Path exe, Path appDir, Path cfg, String jarName) {}

    /** Whether an update can replace the installed jar itself, or why it goes through the installer. */
    public enum InPlaceStatus {
        /** The jar can be swapped in place. */
        READY,
        /** Not Windows, a dev run, or an install layout this updater doesn't recognise. */
        NOT_INSTALLED,
        /** Installed in a folder this user can't write, such as Program Files. */
        READ_ONLY,
        /** The release has no jar with a checksum to verify it against. */
        NO_JAR,
        /** The release needs a newer Java runtime than the installed copy bundles. */
        NEEDS_NEW_RUNTIME
    }

    @FunctionalInterface
    public interface ProgressCallback {
        void onProgress(int percent);
    }

    public static String currentVersion() {
        String v = UpdateChecker.class.getPackage().getImplementationVersion();
        return v != null ? v : "dev";
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
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
        HttpURLConnection conn = (HttpURLConnection) URI.create(RELEASES_API).toURL().openConnection();
        conn.setRequestProperty("Accept", "application/vnd.github+json");
        conn.setRequestProperty("User-Agent", "Shufflingway-App");
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(10000);

        String body;
        try (InputStream in = conn.getInputStream();
             Scanner sc = new Scanner(in, "UTF-8")) {
            body = sc.useDelimiter("\\A").next();
        }
        return parseRelease(body, currentVersion(), installerKindForCurrentPlatform());
    }

    /** Reads a releases-API response: the version, this platform's installer and the jar asset. */
    static ReleaseInfo parseRelease(String body, String current, InstallerKind kind) {
        JSONObject release = new JSONObject(body);
        String tag = release.getString("tag_name");
        String latest = tag.startsWith("v") ? tag.substring(1) : tag;

        String installerUrl = null;
        JarAsset jar = null;
        JSONArray assets = release.optJSONArray("assets");
        for (int i = 0; assets != null && i < assets.length(); i++) {
            JSONObject asset = assets.getJSONObject(i);
            String name = asset.getString("name");
            String url = asset.getString("browser_download_url");
            if (kind != null && installerUrl == null
                    && name.toLowerCase(Locale.ROOT).endsWith(kind.extension)) {
                installerUrl = url;
            }
            Matcher m = JAR_ASSET.matcher(name);
            String digest = asset.optString("digest", "");
            if (jar == null && m.matches() && digest.startsWith("sha256:")) {
                jar = new JarAsset(name, url, digest.substring("sha256:".length()),
                        Integer.parseInt(m.group(1)));
            }
        }

        boolean newer = !"dev".equals(current) && isNewer(latest, current);
        return new ReleaseInfo(current, latest, installerUrl, kind, jar, newer);
    }

    // ---------------------------------------------------------------------------------------------
    // Installer download (every platform; the fallback on Windows)
    // ---------------------------------------------------------------------------------------------

    /**
     * Downloads the installer for {@code kind} to a temp file, launches the platform-appropriate
     * installer or DMG mount, then exits the app. Blocking — must be called from a background thread.
     */
    public static void downloadAndInstall(String installerUrl, InstallerKind kind,
                                          ProgressCallback progress) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create(installerUrl).toURL().openConnection();
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

    // ---------------------------------------------------------------------------------------------
    // In-place update (Windows)
    //
    // The launcher reads app\<Launcher>.cfg at every start; its app.classpath line names the jar to
    // run. Windows locks the running jar, so an update writes the new jar beside it under its own
    // name, points the config at that, and restarts. The new copy waits for the old one to exit,
    // then deletes the jar the config no longer names.
    // ---------------------------------------------------------------------------------------------

    /** Whether {@code info}'s update can be applied in place by this run. */
    public static InPlaceStatus inPlaceStatus(ReleaseInfo info) {
        Installation inst = isWindows() ? currentInstallation() : null;
        return inPlaceStatus(info.jar(), inst, Runtime.version().feature());
    }

    static InPlaceStatus inPlaceStatus(JarAsset jar, Installation inst, int runtimeFeature) {
        if (inst == null)                        return InPlaceStatus.NOT_INSTALLED;
        if (!isWritable(inst.appDir()))          return InPlaceStatus.READ_ONLY;
        if (jar == null)                         return InPlaceStatus.NO_JAR;
        if (jar.javaFeature() > runtimeFeature)  return InPlaceStatus.NEEDS_NEW_RUNTIME;
        return InPlaceStatus.READY;
    }

    /**
     * The installation this run was started from, or {@code null} for a dev run or an install
     * layout this updater doesn't recognise. The jpackage launcher sets {@code jpackage.app-path}.
     */
    static Installation currentInstallation() {
        String exe = System.getProperty("jpackage.app-path");
        if (exe == null || exe.isBlank()) return null;
        return installationAt(Path.of(exe));
    }

    /** Reads the installation a jpackage launcher at {@code exe} runs, or {@code null}. */
    static Installation installationAt(Path exe) {
        Path dir = exe.toAbsolutePath().getParent();
        if (dir == null) return null;
        String file = exe.getFileName().toString();
        int dot = file.lastIndexOf('.');
        String launcher = dot > 0 ? file.substring(0, dot) : file;
        Path appDir = dir.resolve("app");
        Path cfg = appDir.resolve(launcher + ".cfg");
        try {
            String jar = classpathJar(Files.readString(cfg, ISO_8859_1));
            return jar == null ? null : new Installation(exe, appDir, cfg, jar);
        } catch (IOException e) {
            return null;
        }
    }

    /** The jar a launcher config runs, or {@code null} unless it names exactly one plain jar. */
    static String classpathJar(String cfg) {
        Matcher m = CLASSPATH_LINE.matcher(cfg);
        if (!m.find()) return null;
        String jar = m.group(1);
        if (m.find() || !PLAIN_JAR_NAME.matcher(jar).matches()) return null;
        return jar;
    }

    /**
     * {@code cfg} with its classpath pointing at {@code jarName} and its version option at
     * {@code version}. Bytes outside those two lines, line endings included, are kept.
     */
    static String rewriteCfg(String cfg, String jarName, String version) throws IOException {
        if (classpathJar(cfg) == null)
            throw new IOException("The launcher config doesn't name a single jar.");
        if (!PLAIN_JAR_NAME.matcher(jarName).matches() || !PLAIN_VERSION.matcher(version).matches())
            throw new IOException("Unexpected release name: " + jarName + " / " + version);
        String out = CLASSPATH_LINE.matcher(cfg)
                .replaceFirst(Matcher.quoteReplacement("app.classpath=$APPDIR\\" + jarName));
        return APP_VERSION_LINE.matcher(out)
                .replaceFirst(Matcher.quoteReplacement("java-options=-Djpackage.app-version=" + version));
    }

    /** Whether files can be created in {@code dir}. {@code Files.isWritable} isn't reliable on Windows. */
    static boolean isWritable(Path dir) {
        try {
            Path probe = Files.createTempFile(dir, ".write-test", ".tmp");
            Files.delete(probe);
            return true;
        } catch (IOException | SecurityException e) {
            return false;
        }
    }

    /**
     * Downloads the release jar into this installation and points the launcher config at it, so the
     * next start runs the new version. Throws, with the installation unchanged, when the download
     * fails or doesn't match the release's SHA-256. Blocking — call from a background thread.
     */
    public static void downloadInPlace(ReleaseInfo info, ProgressCallback progress) throws IOException {
        Installation inst = currentInstallation();
        if (inst == null || info.jar() == null)
            throw new IOException("This copy of Shufflingway can't update itself in place.");

        HttpURLConnection conn = (HttpURLConnection) URI.create(info.jar().url()).toURL().openConnection();
        conn.setRequestProperty("User-Agent", "Shufflingway-App");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(60000);
        try (InputStream in = conn.getInputStream()) {
            installJar(in, conn.getContentLengthLong(), inst, info.jar(), info.latestVersion(), progress);
        }
    }

    /**
     * Writes {@code in} to the app folder as {@code jar}'s file, checks its SHA-256, then points the
     * launcher config at it. Nothing in the installation changes unless the checksum matches.
     */
    static void installJar(InputStream in, long total, Installation inst, JarAsset jar,
                           String version, ProgressCallback progress) throws IOException {
        if (jar.name().equalsIgnoreCase(inst.jarName()))
            throw new IOException(jar.name() + " is already the installed jar.");
        String newCfg = rewriteCfg(Files.readString(inst.cfg(), ISO_8859_1), jar.name(), version);

        Path dest = inst.appDir().resolve(jar.name());
        Path part = inst.appDir().resolve(jar.name() + ".part");
        MessageDigest sha = sha256();
        try (OutputStream out = Files.newOutputStream(part)) {
            byte[] buf = new byte[8192];
            long downloaded = 0;
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                sha.update(buf, 0, n);
                downloaded += n;
                if (total > 0) progress.onProgress((int) (downloaded * 100 / total));
            }
        } catch (IOException e) {
            Files.deleteIfExists(part);
            throw e;
        }
        if (!HexFormat.of().formatHex(sha.digest()).equalsIgnoreCase(jar.sha256())) {
            Files.deleteIfExists(part);
            throw new IOException("The download didn't match the release's checksum, so nothing was changed.");
        }
        Files.move(part, dest, REPLACE_EXISTING);

        Path tmp = inst.cfg().resolveSibling(inst.cfg().getFileName() + ".tmp");
        Files.writeString(tmp, newCfg, ISO_8859_1);
        Files.move(tmp, inst.cfg(), REPLACE_EXISTING, ATOMIC_MOVE);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);  // every JDK ships SHA-256
        }
    }

    /**
     * Starts the installed launcher again, telling the new copy to wait for this one to exit. The
     * caller exits next.
     */
    public static void relaunch() throws IOException {
        Installation inst = currentInstallation();
        if (inst == null) throw new IOException("Not running from an installed copy.");
        new ProcessBuilder(inst.exe().toString(), AFTER_UPDATE_ARG,
                String.valueOf(ProcessHandle.current().pid()))
                .redirectOutput(Redirect.DISCARD)
                .redirectError(Redirect.DISCARD)
                .start();
    }

    /**
     * Runs first in {@code main}. A copy started by {@link #relaunch()} waits for the copy it
     * replaces to exit; then every run on Windows deletes the jars and interrupted downloads in the
     * app folder that the launcher config no longer names.
     */
    public static void finishPendingUpdate(String[] args) {
        waitForReplacedCopy(args);
        Installation inst = isWindows() ? currentInstallation() : null;
        if (inst != null) removeStaleFiles(inst);
    }

    static void waitForReplacedCopy(String[] args) {
        for (int i = 0; i + 1 < args.length; i++) {
            if (!AFTER_UPDATE_ARG.equals(args[i])) continue;
            long pid;
            try {
                pid = Long.parseLong(args[i + 1]);
            } catch (NumberFormatException e) {
                return;
            }
            ProcessHandle.of(pid).ifPresent(old -> {
                try {
                    old.onExit().get(REPLACED_COPY_WAIT_SECONDS, TimeUnit.SECONDS);
                } catch (Exception e) {
                    // Start anyway; a jar the old copy still holds is deleted on a later start.
                }
            });
            return;
        }
    }

    static void removeStaleFiles(Installation inst) {
        String cfgTmp = inst.cfg().getFileName() + ".tmp";
        try (DirectoryStream<Path> files = Files.newDirectoryStream(inst.appDir())) {
            for (Path f : files) {
                String name = f.getFileName().toString();
                boolean stale = name.equalsIgnoreCase(cfgTmp)
                        || (UPDATE_FILE.matcher(name).matches() && !name.equalsIgnoreCase(inst.jarName()));
                if (!stale) continue;
                try {
                    Files.deleteIfExists(f);
                } catch (IOException e) {
                    // Still open in another running copy; a later start retries.
                }
            }
        } catch (IOException e) {
            // Unreadable app folder: nothing to clean.
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Versions
    // ---------------------------------------------------------------------------------------------

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
