package shufflingway;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ProcessBuilder.Redirect;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONObject;

import scraper.AppPaths;

public final class UpdateChecker {

    private static final String RELEASES_API =
            "https://api.github.com/repos/RockefellerA/shufflingway/releases/latest";

    /** Passed to a copy started by an in-place update, followed by the PID of the copy it replaces. */
    static final String AFTER_UPDATE_ARG = "--after-update";

    /** How long a restarted copy waits for the one it replaces to exit before starting anyway. */
    private static final long REPLACED_COPY_WAIT_SECONDS = 30;

    /**
     * A release's jar asset, e.g. {@code shufflingway-0.2.348-java25.jar}: its version, then the Java
     * feature version of the runtime that release's installers bundle. The same jar runs on every
     * platform; it carries the native libraries for all of them.
     */
    private static final Pattern JAR_ASSET =
            Pattern.compile("shufflingway-([0-9A-Za-z.]+)-java(\\d+)\\.jar", Pattern.CASE_INSENSITIVE);

    /** The class loader a staged update runs in, by name, so the staged copy can tell it is one. */
    static final String STAGED_LOADER_NAME = "shufflingway-staged-update";

    /** An interrupted staged download older than this is left over from a run that ended. */
    private static final Duration STALE_PART_AGE = Duration.ofHours(1);

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
        PKG(".pkg"),  // macOS
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

    /** Whether an update can be applied without the installer, or why it goes through the installer. */
    public enum InPlaceStatus {
        /** The jar can be swapped in place (Windows) or staged in the user's data folder (macOS, Linux). */
        READY,
        /** A dev run, or an install layout this updater doesn't recognise. */
        NOT_INSTALLED,
        /**
         * The update can't be written: on Windows, installed in a folder this user can't write, such
         * as Program Files; elsewhere, the user's data folder isn't writable.
         */
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
        if (os.contains("mac") || os.contains("darwin"))         return InstallerKind.PKG;
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
                        Integer.parseInt(m.group(2)));
            }
        }

        boolean newer = !"dev".equals(current) && isNewer(latest, current);
        return new ReleaseInfo(current, latest, installerUrl, kind, jar, newer);
    }

    // ---------------------------------------------------------------------------------------------
    // Installer download (every platform; the fallback when an update can't be applied in place)
    // ---------------------------------------------------------------------------------------------

    /**
     * Downloads the installer for {@code kind} to a temp file, launches the platform's installer
     * for it, then exits the app. Blocking — must be called from a background thread.
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
            // macOS: `open` hands the PKG to Installer, which replaces the app in /Applications.
            //   The running app quits so Installer can replace the bundle.
            case PKG -> new ProcessBuilder("open", path);
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
        int runtime = Runtime.version().feature();
        if (isWindows()) return inPlaceStatus(info.jar(), currentInstallation(), runtime);
        return stagedStatus(info.jar(), installedLauncher(), updatesDir(), runtime);
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
        if (!isWindows()) {
            stageUpdate(info, progress);
            return;
        }
        Installation inst = currentInstallation();
        if (inst == null || info.jar() == null)
            throw new IOException("This copy of Shufflingway can't update itself in place.");

        HttpURLConnection conn = openJarDownload(info.jar());
        try (InputStream in = conn.getInputStream()) {
            installJar(in, conn.getContentLengthLong(), inst, info.jar(), info.latestVersion(), progress);
        }
    }

    private static HttpURLConnection openJarDownload(JarAsset jar) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create(jar.url()).toURL().openConnection();
        conn.setRequestProperty("User-Agent", "Shufflingway-App");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(60000);
        return conn;
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
        downloadVerified(in, total, part, jar.sha256(), progress);
        Files.move(part, dest, REPLACE_EXISTING);

        Path tmp = inst.cfg().resolveSibling(inst.cfg().getFileName() + ".tmp");
        Files.writeString(tmp, newCfg, ISO_8859_1);
        Files.move(tmp, inst.cfg(), REPLACE_EXISTING, ATOMIC_MOVE);
    }

    /**
     * Writes {@code in} to {@code part} and checks its SHA-256 against {@code sha256}. On any failure
     * {@code part} is deleted and this throws.
     */
    static void downloadVerified(InputStream in, long total, Path part, String sha256,
                                 ProgressCallback progress) throws IOException {
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
        if (!HexFormat.of().formatHex(sha.digest()).equalsIgnoreCase(sha256)) {
            Files.deleteIfExists(part);
            throw new IOException("The download didn't match the release's checksum, so nothing was changed.");
        }
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
        Path exe;
        if (isWindows()) {
            Installation inst = currentInstallation();
            exe = inst == null ? null : inst.exe();
        } else {
            exe = installedLauncher();
        }
        if (exe == null) throw new IOException("Not running from an installed copy.");
        ProcessBuilder pb = new ProcessBuilder(relaunchCommand(exe, ProcessHandle.current().pid(), isMac()))
                .redirectOutput(Redirect.DISCARD)
                .redirectError(Redirect.DISCARD);
        forgetLauncherState(pb.environment());
        pb.start();
    }

    /**
     * Removes the mark the Linux launcher sets in its own environment while it starts the JVM.
     * A launcher that inherits it, with the {@code LD_LIBRARY_PATH} set alongside it, takes itself
     * for that inner start and hands its arguments to the JVM as options, which refuses them.
     */
    static void forgetLauncherState(Map<String, String> env) {
        env.remove("_JPACKAGE_LAUNCHER");
    }

    /**
     * The command that starts the launcher at {@code exe} again. On macOS a launcher inside an app
     * bundle is started through {@code open}, so the new copy runs as that app.
     */
    static List<String> relaunchCommand(Path exe, long pid, boolean mac) {
        Path bundle = mac ? appBundle(exe) : null;
        if (bundle != null)
            return List.of("open", "-n", bundle.toString(), "--args", AFTER_UPDATE_ARG, String.valueOf(pid));
        return List.of(exe.toString(), AFTER_UPDATE_ARG, String.valueOf(pid));
    }

    /** The {@code .app} folder holding a launcher at {@code X.app/Contents/MacOS/X}, or {@code null}. */
    static Path appBundle(Path exe) {
        Path macos = exe.toAbsolutePath().getParent();
        Path contents = macos == null ? null : macos.getParent();
        Path bundle = contents == null ? null : contents.getParent();
        if (bundle == null || bundle.getFileName() == null) return null;
        boolean layout = "MacOS".equals(macos.getFileName().toString())
                && "Contents".equals(contents.getFileName().toString())
                && bundle.getFileName().toString().endsWith(".app");
        return layout ? bundle : null;
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
    // Staged update (macOS and Linux)
    //
    // The installed app folder belongs to root there (a .pkg in /Applications, a .deb in /opt), and
    // changing a macOS app bundle breaks its signature. So the new jar goes into the user's data
    // folder instead. At every start, the installed copy looks there first: when a jar newer than
    // itself is waiting, it runs that jar's main in this same process, in a class loader of its own.
    // The process stays the installed launcher, with the runtime and options it started with.
    // ---------------------------------------------------------------------------------------------

    /** Where a staged update waits: {@code updates} in the user's data folder. */
    static Path updatesDir() {
        return AppPaths.appDataDir().resolve("updates");
    }

    /** The jpackage launcher this run was started from, or {@code null} for a dev run. */
    static Path installedLauncher() {
        String exe = System.getProperty("jpackage.app-path");
        return exe == null || exe.isBlank() ? null : Path.of(exe);
    }

    private static boolean isMac() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("mac") || os.contains("darwin");
    }

    static InPlaceStatus stagedStatus(JarAsset jar, Path launcher, Path updates, int runtimeFeature) {
        if (launcher == null)                    return InPlaceStatus.NOT_INSTALLED;
        try {
            Files.createDirectories(updates);
        } catch (IOException | SecurityException e) {
            return InPlaceStatus.READ_ONLY;
        }
        if (!isWritable(updates))                return InPlaceStatus.READ_ONLY;
        if (jar == null)                         return InPlaceStatus.NO_JAR;
        if (jar.javaFeature() > runtimeFeature)  return InPlaceStatus.NEEDS_NEW_RUNTIME;
        return InPlaceStatus.READY;
    }

    private static void stageUpdate(ReleaseInfo info, ProgressCallback progress) throws IOException {
        if (installedLauncher() == null || info.jar() == null)
            throw new IOException("This copy of Shufflingway can't update itself in place.");
        HttpURLConnection conn = openJarDownload(info.jar());
        try (InputStream in = conn.getInputStream()) {
            stageJar(in, conn.getContentLengthLong(), updatesDir(), info.jar(), progress);
        }
    }

    /**
     * Writes {@code in} into {@code updates} as {@code jar}'s file once its SHA-256 matches. The next
     * start runs it. Nothing in {@code updates} changes unless the checksum matches.
     */
    static void stageJar(InputStream in, long total, Path updates, JarAsset jar,
                         ProgressCallback progress) throws IOException {
        if (!JAR_ASSET.matcher(jar.name()).matches())
            throw new IOException("Unexpected release name: " + jar.name());
        Files.createDirectories(updates);
        Path part = updates.resolve(jar.name() + ".part");
        downloadVerified(in, total, part, jar.sha256(), progress);
        Files.move(part, updates.resolve(jar.name()), REPLACE_EXISTING, ATOMIC_MOVE);
    }

    /** Why the last staged update couldn't start, for {@link #logStagedUpdateFailure()}. */
    private static Throwable stagedFailure;

    /**
     * Runs first in {@code main} on macOS and Linux. When the user's data folder holds an update
     * newer than this copy, this runs that jar's {@code main} with {@code args} and returns
     * {@code true}: the caller then returns without starting itself. Returns {@code false} on
     * Windows, for a dev run, when this already is the staged copy, when nothing newer is waiting,
     * or when the staged jar fails to start; a jar that fails to start is deleted, so the installed
     * version runs from then on.
     */
    public static boolean runStagedUpdate(String[] args) {
        if (isWindows() || installedLauncher() == null) return false;
        if (STAGED_LOADER_NAME.equals(UpdateChecker.class.getClassLoader().getName())) return false;
        Path jar = pickStagedJar(updatesDir(), currentVersion(), Runtime.version().feature(), Instant.now());
        return jar != null && runJar(jar, args);
    }

    /**
     * The newest jar in {@code updates} that is newer than {@code current} and runs on this
     * runtime, or {@code null}. Deletes the jars that are not newer, which a later installer has
     * overtaken, the older ones the chosen jar replaces, and interrupted downloads older than
     * {@link #STALE_PART_AGE}.
     */
    static Path pickStagedJar(Path updates, String current, int runtimeFeature, Instant now) {
        Path best = null;
        String bestVersion = null;
        List<Path> stale = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(updates)) {
            for (Path f : files) {
                String name = f.getFileName().toString();
                Matcher m = JAR_ASSET.matcher(name);
                if (!m.matches()) {
                    if (name.endsWith(".part") && isOlderThan(f, now.minus(STALE_PART_AGE))) stale.add(f);
                    continue;
                }
                String version = m.group(1);
                if (Integer.parseInt(m.group(2)) > runtimeFeature) continue;  // the installer's to apply
                if (!isNewer(version, current)) {
                    stale.add(f);
                } else if (best == null || isNewer(version, bestVersion)) {
                    if (best != null) stale.add(best);
                    best = f;
                    bestVersion = version;
                } else {
                    stale.add(f);
                }
            }
        } catch (IOException | NumberFormatException e) {
            return null;  // no updates folder yet, or nothing readable in it
        }
        for (Path f : stale) {
            try {
                Files.deleteIfExists(f);
            } catch (IOException e) {
                // A later start retries.
            }
        }
        return best;
    }

    private static boolean isOlderThan(Path f, Instant cutoff) {
        try {
            return Files.getLastModifiedTime(f).compareTo(FileTime.from(cutoff)) < 0;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Runs the {@code Main-Class} of {@code jar} in a class loader of its own, whose parent is the
     * platform loader, so none of this copy's classes leak into it. The thread's context loader is
     * set to it first: Swing's event thread, JDBC's driver lookup and ImageIO's plugin scan read
     * that. Returns {@code false}, with {@code jar} deleted, when it can't be loaded or its main
     * throws.
     */
    static boolean runJar(Path jar, String[] args) {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        URLClassLoader loader = null;
        try {
            String mainClass;
            try (JarFile jf = new JarFile(jar.toFile())) {
                Manifest mf = jf.getManifest();
                mainClass = mf == null ? null : mf.getMainAttributes().getValue(Attributes.Name.MAIN_CLASS);
            }
            if (mainClass == null) throw new IOException(jar.getFileName() + " has no Main-Class.");
            // Kept open once main returns: the staged copy's classes load from it for the life of
            // the process.
            loader = new URLClassLoader(STAGED_LOADER_NAME,
                    new URL[] { jar.toUri().toURL() }, ClassLoader.getPlatformClassLoader());
            Method main = Class.forName(mainClass, true, loader).getMethod("main", String[].class);
            thread.setContextClassLoader(loader);
            main.invoke(null, (Object) args);
            return true;
        } catch (IOException | ReflectiveOperationException | LinkageError | RuntimeException e) {
            thread.setContextClassLoader(previous);
            stagedFailure = e instanceof InvocationTargetException ite && ite.getCause() != null
                    ? ite.getCause() : e;
            // Closed so the jar can be deleted: Windows will not delete a file that is still open.
            if (loader != null) {
                try {
                    loader.close();
                } catch (IOException ignored) {
                    // The delete below may then fail too, and that is handled there.
                }
            }
            try {
                Files.deleteIfExists(jar);
            } catch (IOException ignored) {
                // pickStagedJar offers it again next start, and this fails the same way.
            }
            return false;
        }
    }

    /** Logs why a staged update couldn't start, if one couldn't. Call once the log is open. */
    public static void logStagedUpdateFailure() {
        Throwable t = stagedFailure;
        if (t != null) AppLogger.log("A downloaded update failed to start; running the installed version", t);
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
