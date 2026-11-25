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
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
    // Helpers
    // ---------------------------------------------------------------------------------------------

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
