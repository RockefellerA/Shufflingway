package shufflingway.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Properties;
import java.util.jar.JarFile;

import org.junit.jupiter.api.Test;
import org.sqlite.JDBC;

import com.formdev.flatlaf.FlatLaf;

import dev.matrixlab.webp4j.WebPCodec;

/**
 * The notices keep up with the libraries the build uses. Where a jar carries its own licence
 * text the notice must contain it, so an upgrade that changes it fails here rather than the
 * notice going quietly out of date. webp4j's jar carries none, so its version is pinned instead.
 */
class ThirdPartyNoticesTest {

	private static final String TEXT = ThirdPartyNotices.text();

	/** FlatLaf's licence text and copyright line are the ones in FlatLaf's jar. */
	@Test
	void flatLafsNoticeMatchesItsJar() throws Exception {
		try (JarFile jar = jarOf(FlatLaf.class)) {
			assertContains(entry(jar, "META-INF/LICENSE"), "FlatLaf's Apache License, from " + jar.getName());
			String copyright = jar.getManifest().getMainAttributes().getValue("Implementation-Copyright")
					.replace("All rights reserved.", "").trim();
			assertTrue(TEXT.contains(copyright), "the notice names \"" + copyright + "\"");
		}
	}

	/** The SQLite JDBC Driver's Apache License and its Zentus notice are the ones in its jar. */
	@Test
	void sqliteJdbcsNoticesMatchItsJar() throws Exception {
		try (JarFile jar = jarOf(JDBC.class)) {
			assertContains(entry(jar, "META-INF/maven/org.xerial/sqlite-jdbc/LICENSE"),
					"sqlite-jdbc's Apache License, from " + jar.getName());
			assertContains(entry(jar, "META-INF/maven/org.xerial/sqlite-jdbc/LICENSE.zentus"),
					"sqlite-jdbc's Zentus notice, from " + jar.getName());
		}
	}

	/**
	 * webp4j's own MIT notice, and libwebp's and GIFLIB's, which its natives compile in, were taken
	 * from the source of webp4j 2.5.0: its LICENSE, its libwebp submodule's COPYING, and COPYING of
	 * the GIFLIB tag its CMakeLists.txt fetches. Its jar carries none of them to compare against.
	 */
	@Test
	void webp4jsNoticesWereTakenForTheVersionInUse() throws Exception {
		try (JarFile jar = jarOf(WebPCodec.class)) {
			Properties pom = new Properties();
			try (InputStream in = jar.getInputStream(jar.getEntry(
					"META-INF/maven/dev.matrixlab.webp4j/webp4j-core/pom.properties"))) {
				pom.load(in);
			}
			assertEquals("2.5.0", pom.getProperty("version"),
					"webp4j has changed version. Check what its natives now compile in (CMakeLists.txt and "
					+ "the libwebp submodule at the new tag), update /licenses/webp4j.txt, libwebp.txt and "
					+ "giflib.txt to match, then the version here.");
		}
		assertTrue(TEXT.contains("Copyright (c) 2024 Mr Nanko"));
		assertTrue(TEXT.contains("Copyright (c) 2010, Google Inc. All rights reserved."));
		assertTrue(TEXT.contains("The GIFLIB distribution is Copyright (c) 1997  Eric S. Raymond"));
	}

	/** Both bundled fonts are credited; the CC BY one with its licence. */
	@Test
	void theBundledFontsAreCredited() {
		assertTrue(TEXT.contains("Final Fantasy 3/6 Font, by Veylon"));
		assertTrue(TEXT.contains("https://creativecommons.org/licenses/by/3.0/"));
		assertTrue(TEXT.contains("Pixel NES, by Neale Davidson"));
	}

	/** The licence the earlier notices call "the last of these notices" is. */
	@Test
	void theApacheLicenceComesLast() {
		assertTrue(ThirdPartyNotices.FILES.getLast().endsWith("/Apache-2.0.txt"));
	}

	private static void assertContains(String licence, String what) {
		assertTrue(normalise(TEXT).contains(normalise(licence).strip()), what + " is in the notices");
	}

	private static JarFile jarOf(Class<?> type) throws Exception {
		Path path = Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
		return new JarFile(path.toFile());
	}

	private static String entry(JarFile jar, String name) throws IOException {
		try (InputStream in = jar.getInputStream(jar.getEntry(name))) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	/** Line endings aside: the jars differ, and git may check the notices out with Windows ones. */
	private static String normalise(String s) {
		return s.replace("\r\n", "\n");
	}
}
