package shufflingway;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Reading Windows' dark mode for the theme a player who has not chosen one gets. The reports are
 * {@code reg query}'s output as Windows 11 prints it, CRLF line endings and blank lines included.
 */
class ThemeTest {

	private static final String KEY =
			"\r\nHKEY_CURRENT_USER\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize\r\n";

	@Test
	void appsSetToDarkReadAsDark() {
		assertTrue(Theme.windowsAppsAreDark(KEY + "    AppsUseLightTheme    REG_DWORD    0x0\r\n\r\n"));
	}

	@Test
	void appsSetToLightReadAsLight() {
		assertFalse(Theme.windowsAppsAreDark(KEY + "    AppsUseLightTheme    REG_DWORD    0x1\r\n\r\n"));
	}

	/** A value of 0x10 starts "0x" and "0", but is not zero. */
	@Test
	void aNonZeroValueStartingWithZeroIsNotDark() {
		assertFalse(Theme.windowsAppsAreDark(KEY + "    AppsUseLightTheme    REG_DWORD    0x10\r\n\r\n"));
	}

	/** Before Windows 10 there is no such value, and a query that failed reports nothing. */
	@Test
	void aMissingValueReadsAsLight() {
		assertFalse(Theme.windowsAppsAreDark(""));
		assertFalse(Theme.windowsAppsAreDark(
				"ERROR: The system was unable to find the specified registry key or value.\r\n"));
	}
}
