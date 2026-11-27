package shufflingway;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.ProcessBuilder.Redirect;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.tools.ToolProvider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import shufflingway.UpdateChecker.InPlaceStatus;
import shufflingway.UpdateChecker.InstallerKind;
import shufflingway.UpdateChecker.Installation;
import shufflingway.UpdateChecker.JarAsset;
import shufflingway.UpdateChecker.ReleaseInfo;

class UpdateCheckerTest {

    /** The launcher config jpackage writes for the Windows install, CRLF as on disk. */
    private static final String CFG = "[Application]\r\n"
            + "app.classpath=$APPDIR\\shufflingway.jar\r\n"
            + "app.mainclass=shufflingway.MainWindow\r\n"
            + "\r\n"
            + "[JavaOptions]\r\n"
            + "java-options=-Djpackage.app-version=0.2.347\r\n"
            + "java-options=--enable-native-access=ALL-UNNAMED\r\n";

    private static final String NEW_JAR = "shufflingway-0.2.348-java25.jar";

    // ---------------------------------------------------------------------------------------------
    // Release parsing
    // ---------------------------------------------------------------------------------------------

    @Test
    void parseReleasePicksTheInstallerAndTheVerifiedJar() {
        String body = """
                {"tag_name":"v0.2.348","assets":[
                  {"name":"shufflingway-server.jar","browser_download_url":"https://x/server.jar","digest":"sha256:aa"},
                  {"name":"Shufflingway-0.2.348.msi","browser_download_url":"https://x/a.msi","digest":"sha256:bb"},
                  {"name":"shufflingway-0.2.348-java25.jar","browser_download_url":"https://x/a.jar","digest":"sha256:cc"}]}
                """;
        ReleaseInfo info = UpdateChecker.parseRelease(body, "0.2.347", InstallerKind.MSI);

        assertEquals("0.2.348", info.latestVersion());
        assertTrue(info.updateAvailable());
        assertEquals("https://x/a.msi", info.installerUrl());
        assertEquals(new JarAsset(NEW_JAR, "https://x/a.jar", "cc", 25), info.jar());
    }

    @Test
    void onMacTheInstallerIsThePkg() {
        String body = """
                {"tag_name":"v0.2.348","assets":[
                  {"name":"Shufflingway-0.2.348.msi","browser_download_url":"https://x/a.msi"},
                  {"name":"Shufflingway-1.2.348.pkg","browser_download_url":"https://x/a.pkg"},
                  {"name":"shufflingway_0.2.348_amd64.deb","browser_download_url":"https://x/a.deb"}]}
                """;
        assertEquals("https://x/a.pkg",
                UpdateChecker.parseRelease(body, "0.2.347", InstallerKind.PKG).installerUrl());
    }

    @Test
    void aJarWithoutAChecksumIsNotOffered() {
        String body = """
                {"tag_name":"v0.2.348","assets":[
                  {"name":"shufflingway-0.2.348-java25.jar","browser_download_url":"https://x/a.jar"}]}
                """;
        assertNull(UpdateChecker.parseRelease(body, "0.2.347", InstallerKind.MSI).jar());
    }

    // ---------------------------------------------------------------------------------------------
    // Launcher config
    // ---------------------------------------------------------------------------------------------

    @Test
    void rewriteCfgChangesOnlyTheClasspathAndVersionLines() throws IOException {
        String expected = CFG.replace("$APPDIR\\shufflingway.jar", "$APPDIR\\" + NEW_JAR)
                .replace("app-version=0.2.347", "app-version=0.2.348");
        assertEquals(expected, UpdateChecker.rewriteCfg(CFG, NEW_JAR, "0.2.348"));
    }

    @Test
    void rewriteCfgRefusesAnythingButOnePlainJarAndVersion() {
        String noJar = CFG.replace("app.classpath", "app.mainjar");
        String twoJars = CFG.replace("app.mainclass", "app.classpath=$APPDIR\\other.jar\r\napp.mainclass");
        assertThrows(IOException.class, () -> UpdateChecker.rewriteCfg(noJar, NEW_JAR, "0.2.348"));
        assertThrows(IOException.class, () -> UpdateChecker.rewriteCfg(twoJars, NEW_JAR, "0.2.348"));
        assertThrows(IOException.class, () -> UpdateChecker.rewriteCfg(CFG, "..\\evil.jar", "0.2.348"));
        assertThrows(IOException.class, () -> UpdateChecker.rewriteCfg(CFG, NEW_JAR, "0.2.348\r\nx=y"));
    }

    @Test
    void installationAtReadsTheJarTheConfigRuns(@TempDir Path root) throws IOException {
        Installation inst = install(root);

        assertNotNull(inst);
        assertEquals("shufflingway.jar", inst.jarName());
        assertEquals(root.resolve("app").resolve("Shufflingway.cfg"), inst.cfg());
    }

    // ---------------------------------------------------------------------------------------------
    // Installing the jar
    // ---------------------------------------------------------------------------------------------

    @Test
    void installJarAddsTheJarAndRepointsTheConfig(@TempDir Path root) throws Exception {
        Installation inst = install(root);
        byte[] data = "new jar".getBytes(UTF_8);
        List<Integer> progress = new ArrayList<>();

        UpdateChecker.installJar(new ByteArrayInputStream(data), data.length, inst,
                new JarAsset(NEW_JAR, "u", sha256(data), 25), "0.2.348", progress::add);

        Path app = root.resolve("app");
        assertArrayEquals(data, Files.readAllBytes(app.resolve(NEW_JAR)));
        assertEquals("old", Files.readString(app.resolve("shufflingway.jar")), "the running jar is left alone");
        assertEquals(NEW_JAR, UpdateChecker.installationAt(root.resolve("Shufflingway.exe")).jarName());
        assertTrue(Files.readString(inst.cfg(), ISO_8859_1).contains("app-version=0.2.348\r\n"));
        assertEquals(Set.of(".package", "Shufflingway.cfg", "shufflingway.jar", NEW_JAR), names(app));
        assertEquals(100, progress.get(progress.size() - 1));
    }

    @Test
    void aChecksumMismatchChangesNothing(@TempDir Path root) throws Exception {
        Installation inst = install(root);
        JarAsset jar = new JarAsset(NEW_JAR, "u", sha256("something else".getBytes(UTF_8)), 25);

        assertThrows(IOException.class, () -> UpdateChecker.installJar(
                new ByteArrayInputStream("new jar".getBytes(UTF_8)), 7, inst, jar, "0.2.348", p -> {}));

        assertEquals(CFG, Files.readString(inst.cfg(), ISO_8859_1));
        assertEquals(Set.of(".package", "Shufflingway.cfg", "shufflingway.jar"), names(root.resolve("app")));
    }

    @Test
    void startupDeletesJarsTheConfigNoLongerRuns(@TempDir Path root) throws Exception {
        Installation inst = install(root);
        Path app = root.resolve("app");
        Files.writeString(inst.cfg(), UpdateChecker.rewriteCfg(CFG, NEW_JAR, "0.2.348"), ISO_8859_1);
        Files.writeString(app.resolve(NEW_JAR), "new");
        Files.writeString(app.resolve("shufflingway-0.2.349-java25.jar.part"), "half");
        Files.writeString(app.resolve("Shufflingway.cfg.tmp"), "x");

        UpdateChecker.removeStaleFiles(UpdateChecker.installationAt(root.resolve("Shufflingway.exe")));

        assertEquals(Set.of(".package", "Shufflingway.cfg", NEW_JAR), names(app));
    }

    // ---------------------------------------------------------------------------------------------
    // When the update goes through the installer instead
    // ---------------------------------------------------------------------------------------------

    @Test
    void inPlaceStatusFallsBackToTheInstallerWhenItCantSwapTheJar(@TempDir Path root) throws Exception {
        Installation inst = install(root);
        JarAsset jar25 = new JarAsset(NEW_JAR, "u", "00", 25);
        Installation unwritable = new Installation(root.resolve("x.exe"), root.resolve("missing"),
                root.resolve("missing").resolve("x.cfg"), "shufflingway.jar");

        assertEquals(InPlaceStatus.READY, UpdateChecker.inPlaceStatus(jar25, inst, 25));
        assertEquals(InPlaceStatus.NEEDS_NEW_RUNTIME,
                UpdateChecker.inPlaceStatus(new JarAsset(NEW_JAR, "u", "00", 26), inst, 25));
        assertEquals(InPlaceStatus.NO_JAR, UpdateChecker.inPlaceStatus(null, inst, 25));
        assertEquals(InPlaceStatus.NOT_INSTALLED, UpdateChecker.inPlaceStatus(jar25, null, 25));
        assertEquals(InPlaceStatus.READ_ONLY, UpdateChecker.inPlaceStatus(jar25, unwritable, 25));
    }

    // ---------------------------------------------------------------------------------------------
    // Restart
    // ---------------------------------------------------------------------------------------------

    @Test
    void theRestartedCopyWaitsForTheOldOneToExit() throws Exception {
        String java = ProcessHandle.current().info().command().orElseThrow();
        Process old = new ProcessBuilder(java, "-version")
                .redirectErrorStream(true).redirectOutput(Redirect.DISCARD).start();

        UpdateChecker.waitForReplacedCopy(new String[] { UpdateChecker.AFTER_UPDATE_ARG, String.valueOf(old.pid()) });

        assertFalse(old.isAlive());
    }

    @Test
    void aMissingOrMalformedPidDoesNotHoldUpStartup() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            UpdateChecker.waitForReplacedCopy(new String[] { UpdateChecker.AFTER_UPDATE_ARG, "nope" });
            UpdateChecker.waitForReplacedCopy(new String[] { UpdateChecker.AFTER_UPDATE_ARG });
            UpdateChecker.waitForReplacedCopy(new String[] { UpdateChecker.AFTER_UPDATE_ARG,
                    String.valueOf(Long.MAX_VALUE) });
            UpdateChecker.waitForReplacedCopy(new String[0]);
        });
    }

    // ---------------------------------------------------------------------------------------------
    // Staged update (macOS and Linux)
    // ---------------------------------------------------------------------------------------------

    @Test
    void stageJarKeepsTheJarOnlyOnceItsChecksumMatches(@TempDir Path root) throws Exception {
        Path updates = root.resolve("updates");
        byte[] data = "new jar".getBytes(UTF_8);

        assertThrows(IOException.class, () -> UpdateChecker.stageJar(new ByteArrayInputStream(data),
                data.length, updates, new JarAsset(NEW_JAR, "u", sha256("other".getBytes(UTF_8)), 25), p -> {}));
        assertEquals(Set.of(), names(updates));

        UpdateChecker.stageJar(new ByteArrayInputStream(data), data.length, updates,
                new JarAsset(NEW_JAR, "u", sha256(data), 25), p -> {});
        assertArrayEquals(data, Files.readAllBytes(updates.resolve(NEW_JAR)));
        assertEquals(Set.of(NEW_JAR), names(updates));
    }

    @Test
    void stageJarRefusesANameThatIsNotAReleaseJar(@TempDir Path root) {
        JarAsset evil = new JarAsset("../shufflingway-0.2.348-java25.jar", "u", "00", 25);
        assertThrows(IOException.class, () -> UpdateChecker.stageJar(
                new ByteArrayInputStream(new byte[0]), 0, root, evil, p -> {}));
    }

    @Test
    void pickStagedJarRunsTheNewestAndClearsWhatItOvertook(@TempDir Path updates) throws Exception {
        Instant now = Instant.parse("2026-11-27T12:00:00Z");
        for (String name : List.of("shufflingway-0.2.340-java25.jar",   // older than the install
                                   "shufflingway-0.2.347-java25.jar",   // the installed version
                                   "shufflingway-0.2.348-java25.jar",   // newer, but overtaken
                                   "shufflingway-0.2.350-java25.jar",   // the one to run
                                   "shufflingway-0.2.351-java26.jar",   // needs the next installer
                                   "shufflingway-0.2.352-java25.jar.part",
                                   "shufflingway-0.2.353-java25.jar.part",
                                   "notes.txt")) {
            Files.writeString(updates.resolve(name), "x");
        }
        Files.setLastModifiedTime(updates.resolve("shufflingway-0.2.352-java25.jar.part"),
                FileTime.from(now.minus(Duration.ofHours(2))));
        Files.setLastModifiedTime(updates.resolve("shufflingway-0.2.353-java25.jar.part"),
                FileTime.from(now.minus(Duration.ofMinutes(5))));

        Path picked = UpdateChecker.pickStagedJar(updates, "0.2.347", 25, now);

        assertEquals(updates.resolve("shufflingway-0.2.350-java25.jar"), picked);
        assertEquals(Set.of("shufflingway-0.2.350-java25.jar", "shufflingway-0.2.351-java26.jar",
                "shufflingway-0.2.353-java25.jar.part", "notes.txt"), names(updates));
    }

    @Test
    void pickStagedJarFindsNothingOnceAnInstallerHasOvertakenIt(@TempDir Path updates) throws Exception {
        Files.writeString(updates.resolve(NEW_JAR), "x");

        assertNull(UpdateChecker.pickStagedJar(updates, "0.2.349", 25, Instant.now()));
        assertEquals(Set.of(), names(updates));
        assertNull(UpdateChecker.pickStagedJar(updates.resolve("missing"), "0.2.349", 25, Instant.now()));
    }

    @Test
    void runJarRunsTheJarsMainInALoaderOfItsOwn(@TempDir Path root) throws Exception {
        Path jar = mainJar(root, "System.setProperty(\"staged.test\", "
                + "Main.class.getClassLoader().getName() + \"|\" "
                + "+ Thread.currentThread().getContextClassLoader().getName() + \"|\" "
                + "+ String.join(\",\", args));");
        ClassLoader before = Thread.currentThread().getContextClassLoader();
        try {
            assertTrue(UpdateChecker.runJar(jar, new String[] { "--after-update", "42" }));
            String loader = UpdateChecker.STAGED_LOADER_NAME;
            assertEquals(loader + "|" + loader + "|--after-update,42", System.getProperty("staged.test"));
            assertTrue(Files.exists(jar));
        } finally {
            // A started copy keeps its loader, and so its jar, open for the life of the process.
            // The test has to close it, or Windows cannot delete the temp folder holding the jar.
            ClassLoader staged = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(before);
            if (staged != before && staged instanceof URLClassLoader u) u.close();
            System.clearProperty("staged.test");
        }
    }

    @Test
    void aStagedJarThatFailsToStartIsDeleted(@TempDir Path root) throws Exception {
        ClassLoader before = Thread.currentThread().getContextClassLoader();
        Path throwing = mainJar(root, "throw new IllegalStateException(\"boom\");");
        Path broken = Files.writeString(root.resolve("shufflingway-0.2.349-java25.jar"), "not a jar");

        assertFalse(UpdateChecker.runJar(throwing, new String[0]));
        assertFalse(UpdateChecker.runJar(broken, new String[0]));

        assertFalse(Files.exists(throwing));
        assertFalse(Files.exists(broken));
        assertTrue(before == Thread.currentThread().getContextClassLoader(), "the context loader is put back");
    }

    @Test
    void stagedStatusNeedsAnInstalledCopyAWritableFolderAndAJarForThisRuntime(@TempDir Path root)
            throws Exception {
        Path launcher = root.resolve("bin").resolve("shufflingway");
        Path updates = root.resolve("updates");
        Path blocked = Files.writeString(root.resolve("file"), "x").resolve("updates");
        JarAsset jar25 = new JarAsset(NEW_JAR, "u", "00", 25);

        assertEquals(InPlaceStatus.READY, UpdateChecker.stagedStatus(jar25, launcher, updates, 25));
        assertEquals(InPlaceStatus.NOT_INSTALLED, UpdateChecker.stagedStatus(jar25, null, updates, 25));
        assertEquals(InPlaceStatus.READ_ONLY, UpdateChecker.stagedStatus(jar25, launcher, blocked, 25));
        assertEquals(InPlaceStatus.NO_JAR, UpdateChecker.stagedStatus(null, launcher, updates, 25));
        assertEquals(InPlaceStatus.NEEDS_NEW_RUNTIME,
                UpdateChecker.stagedStatus(new JarAsset(NEW_JAR, "u", "00", 26), launcher, updates, 25));
    }

    @Test
    void onMacTheRestartGoesThroughOpenForTheAppBundle() {
        Path mac = Path.of("/Applications/Shufflingway.app/Contents/MacOS/Shufflingway");
        Path linux = Path.of("/opt/shufflingway/bin/shufflingway");
        // Spelled by this JVM's path rules, as the launcher paths below are: the bundle path is made
        // absolute, which on Windows puts a drive letter in front of it.
        String bundle = Path.of("/Applications/Shufflingway.app").toAbsolutePath().toString();

        assertEquals(List.of("open", "-n", bundle, "--args",
                UpdateChecker.AFTER_UPDATE_ARG, "7"), UpdateChecker.relaunchCommand(mac, 7, true));
        assertEquals(List.of(linux.toString(), UpdateChecker.AFTER_UPDATE_ARG, "7"),
                UpdateChecker.relaunchCommand(linux, 7, true));
        assertEquals(List.of(linux.toString(), UpdateChecker.AFTER_UPDATE_ARG, "7"),
                UpdateChecker.relaunchCommand(linux, 7, false));
    }

    @Test
    void theRestartedLauncherDoesNotInheritTheRunningOnesStartupMark() {
        // The Linux launcher sets these on itself; inheriting both made the new one hand
        // --after-update to the JVM as an option ("Unrecognized option"), and it never started.
        java.util.Map<String, String> env = new java.util.HashMap<>(java.util.Map.of(
                "_JPACKAGE_LAUNCHER", "0", "LD_LIBRARY_PATH", ":/opt/shufflingway/lib/app", "HOME", "/home/u"));

        UpdateChecker.forgetLauncherState(env);

        assertEquals(java.util.Map.of("LD_LIBRARY_PATH", ":/opt/shufflingway/lib/app", "HOME", "/home/u"), env);
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    /** A jar whose {@code Main.main(String[] args)} runs {@code body}, named like a release jar. */
    private static Path mainJar(Path root, String body) throws Exception {
        Path src = Files.createDirectories(root.resolve("src"));
        Path classes = Files.createDirectories(root.resolve("classes"));
        Path java = Files.writeString(src.resolve("Main.java"),
                "public class Main { public static void main(String[] args) { " + body + " } }");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-d", classes.toString(), java.toString()));

        Manifest mf = new Manifest();
        mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        mf.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "Main");
        Path jar = root.resolve(NEW_JAR);
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar), mf)) {
            out.putNextEntry(new JarEntry("Main.class"));
            out.write(Files.readAllBytes(classes.resolve("Main.class")));
            out.closeEntry();
        }
        return jar;
    }

    /** Lays out a jpackage install under {@code root} and reads it back. */
    private static Installation install(Path root) throws IOException {
        Path app = Files.createDirectories(root.resolve("app"));
        Files.writeString(app.resolve("Shufflingway.cfg"), CFG, ISO_8859_1);
        Files.writeString(app.resolve("shufflingway.jar"), "old");
        Files.writeString(app.resolve(".package"), "pkg");
        return UpdateChecker.installationAt(root.resolve("Shufflingway.exe"));
    }

    private static Set<String> names(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(f -> f.getFileName().toString()).collect(Collectors.toSet());
        }
    }

    private static String sha256(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }
}
