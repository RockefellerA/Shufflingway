package shufflingway;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;
import com.formdev.flatlaf.FlatSystemProperties;
import java.awt.Window;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.UnsupportedLookAndFeelException;
import javax.swing.plaf.metal.MetalLookAndFeel;

/**
 * The look chosen in File → Preferences: Latte or Espresso, FlatLaf's light and dark themes
 * recoloured in roasted browns, or the classic Swing look Shufflingway had before FlatLaf.
 *
 * <p>Only the Swing chrome follows it — menus, dialogs, the title bar, scroll bars, text fields.
 * The board is painted in its own colours (the field colours in Preferences, the card art), so it
 * looks the same in every theme.
 */
public enum Theme {

	/**
	 * Swing's own Metal look with its default Ocean theme, under the OS title bar — what
	 * Shufflingway always had, never having set a look explicitly. The default, so FlatLaf is opt-in.
	 */
	CLASSIC("Classic (original)"),
	// Latte or Espresso to follow the OS, as PsaroEditor offers. Not offered here yet; restoring it
	// means this line and its case in isDark(). A saved SYSTEM reads as CLASSIC while it is out.
	// SYSTEM("Match system"),
	/** The light theme, {@link #LATTE_COLOURS}. Named by role, since the name is what is saved. */
	LIGHT("Latte (Light)"),
	/** The dark theme, {@link #ESPRESSO_COLOURS}. */
	DARK("Espresso (Dark)");

	/*
	 * FlatLaf derives every other colour of a theme — borders, hovers, grid lines, the title bar,
	 * the focus ring — from a few variables, so naming those is the whole palette. Each sets
	 * @accentColor to null because a system accent colour, where FlatLaf finds one, would replace
	 * the brown.
	 */

	/**
	 * Milky coffee: a latte-beige ground, cream fields, dark-roast text, and selections and focus in
	 * coffee brown.
	 */
	private static final Map<String, String> LATTE_COLOURS = Map.of(
			"@background", "#ebe0cd",
			"@foreground", "#3a2a1f",
			// FlatLaf's tint of the text colour fades too far into beige to read as a hint
			"@disabledForeground", "#7d6b58",
			"@accentBaseColor", "#6f4e37",
			"@accentColor", "null");

	/**
	 * A dark roast: an espresso-brown ground under crema text, with selections and focus in caramel.
	 * The ground is {@link ElementColor#DARK}'s, the field colour the opponent's half of the board
	 * has by default, so the panels beside the board match it.
	 */
	private static final Map<String, String> ESPRESSO_COLOURS = Map.of(
			"@background", "#2e2825",
			"@foreground", "#e3d4c2",
			"@accentBaseColor", "#a8693a",
			"@accentColor", "null");

	private final String label;

	Theme(String label) {
		this.label = label;
	}

	@Override
	public String toString() {
		return label;
	}

	/** The saved choice; {@link #CLASSIC} until the user picks one. */
	public static Theme saved() {
		String name = AppSettings.getTheme();
		if (name == null) return CLASSIC;
		try {
			return valueOf(name);
		} catch (IllegalArgumentException e) {
			return CLASSIC;
		}
	}

	public void save() {
		AppSettings.setTheme(name());
		AppSettings.save();
	}

	public boolean isDark() {
		return switch (this) {
			case LIGHT, CLASSIC -> false;
			case DARK -> true;
			// case SYSTEM -> systemIsDark();
		};
	}

	/** Installs this theme and restyles every open window. */
	public void apply() {
		if (this == CLASSIC) {
			try {
				UIManager.setLookAndFeel(new MetalLookAndFeel());
			} catch (UnsupportedLookAndFeelException e) {
				return;   // Metal ships with every JDK; there is nothing to fall back to
			}
			// FlatLaf.updateUI's counterpart for a look that is not FlatLaf. Restyling the root pane
			// also hands a window that had FlatLaf's title bar back to the OS one.
			for (Window w : Window.getWindows()) SwingUtilities.updateComponentTreeUI(w);
			return;
		}
		// FlatLaf draws its own title bar on Windows; keep the menus below it, not inside it
		System.setProperty(FlatSystemProperties.MENUBAR_EMBEDDED, "false");
		// FlatLaf hides a table's grid by default. Column lines back, as Metal draws them and as the
		// Deck Manager's Deck Contents table paints its own, so the card tables beside it match.
		// A FlatLaf default rather than UIManager.put, which would outlive a switch back to Classic.
		// The spacing is the other half: FlatLaf zeroes it too, and cells painted edge to edge cover
		// the lines drawn beneath them.
		FlatLaf.setGlobalExtraDefaults(Map.of(
				"Table.showVerticalLines", "true",
				"Table.intercellSpacing", "1,0"));
		FlatLaf laf = isDark() ? new FlatDarkLaf() : new FlatLightLaf();
		laf.setExtraDefaults(isDark() ? ESPRESSO_COLOURS : LATTE_COLOURS);
		FlatLaf.setup(laf);
		FlatLaf.updateUI();
	}

	/**
	 * Whether the OS is set to dark mode: Windows' "app mode", macOS' appearance, or GNOME's
	 * colour scheme. Anything that cannot be read counts as light. Only the commented-out SYSTEM
	 * choice reads it.
	 */
	static boolean systemIsDark() {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		if (os.contains("win")) {
			// "AppsUseLightTheme    REG_DWORD    0x0" when apps are set to dark
			String out = run("reg", "query", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
					"/v", "AppsUseLightTheme");
			return out.matches("(?s).*AppsUseLightTheme\\s+REG_DWORD\\s+0x0+\\s.*");
		}
		if (os.contains("mac")) {
			// prints "Dark" in dark mode; in light mode the key does not exist
			return run("defaults", "read", "-g", "AppleInterfaceStyle").trim().equalsIgnoreCase("dark");
		}
		String scheme = run("gsettings", "get", "org.gnome.desktop.interface", "color-scheme");
		if (!scheme.isBlank()) {
			return scheme.contains("dark");
		}
		return run("gsettings", "get", "org.gnome.desktop.interface", "gtk-theme").toLowerCase(Locale.ROOT)
				.contains("dark");
	}

	/** The command's output, or "" if it cannot be run, fails or takes too long. */
	private static String run(String... command) {
		try {
			Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
			p.getOutputStream().close();
			if (!p.waitFor(3, TimeUnit.SECONDS)) {
				p.destroyForcibly();
				return "";
			}
			String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			return p.exitValue() == 0 ? out : "";
		} catch (IOException e) {
			return "";
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return "";
		}
	}
}
