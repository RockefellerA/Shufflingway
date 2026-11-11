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

import static java.util.Map.entry;

/**
 * The look chosen in File → Preferences: Paladin or Dark Knight, FlatLaf's light and dark themes
 * dressed as Cecil's two forms; Latte or Espresso, the same two recoloured in roasted browns; or
 * the classic Swing look Shufflingway had before FlatLaf.
 *
 * <p>Only the Swing chrome follows it — menus, dialogs, the title bar, scroll bars, text fields —
 * and the phase tracker, which carries a strip to match each. The board is painted in its own
 * colours (the field colours in Preferences, the card art), so it looks the same in every theme.
 */
public enum Theme {

	/**
	 * Swing's own Metal look with its default Ocean theme, under the OS title bar — what
	 * Shufflingway always had, never having set a look explicitly.
	 */
	CLASSIC("Classic (original)"),
	// Paladin or Dark Knight by the OS's mode, as a choice in the list, as PsaroEditor offers it. A
	// player who has not chosen already gets exactly that (see saved()); restoring it means this
	// line and its cases in isDark() and colours(). A saved SYSTEM reads as no choice while it is
	// out, which comes to the same thing.
	// SYSTEM("Match system"),
	/** Cecil the Paladin, {@link #PALADIN_COLOURS}. */
	PALADIN("Paladin (Light)"),
	/** Cecil the Dark Knight, {@link #DARK_KNIGHT_COLOURS}. */
	DARK_KNIGHT("Dark Knight (Dark)"),
	/** Latte, {@link #LATTE_COLOURS}. Named by role, since the name is what is saved. */
	LIGHT("Latte (Light)"),
	/** Espresso, {@link #ESPRESSO_COLOURS}. */
	DARK("Espresso (Dark)");

	/*
	 * FlatLaf derives every other colour of a theme — borders, hovers, grid lines, the title bar,
	 * the focus ring — from a few variables, so naming those is most of a palette. Each sets
	 * @accentColor to null because a system accent colour, where FlatLaf finds one, would replace
	 * the theme's own.
	 */

	/**
	 * Cecil's paladin plate: a polished-silver ground under navy text, selections and focus in
	 * royal blue, and gold trim — the rule under the menu bar, the rim of a default button, the bar
	 * across a selected tab, whose card is near-white, pale blue while the tab strip has focus. The
	 * title bar is the blue of the armour, lettered in silver-white; FlatLaf otherwise draws it in
	 * the window's own ground, which is what unifiedBackground stops.
	 */
	private static final Map<String, String> PALADIN_COLOURS = Map.ofEntries(
			entry("@background", "#e1e5ed"),
			entry("@foreground", "#1b2440"),
			entry("@disabledForeground", "#6a7490"),
			entry("@accentBaseColor", "#2c58a8"),
			entry("@accentColor", "null"),
			entry("@accentUnderlineColor", "#b08a34"),
			entry("@accentButtonDefaultBorderColor", "#b08a34"),
			entry("MenuBar.borderColor", "#b08a34"),
			// the same rule under a dialog's title bar, which has no menu bar to carry one
			entry("TitlePane.borderColor", "#b08a34"),
			entry("TitlePane.unifiedBackground", "false"),
			entry("TitlePane.background", "#24427f"),
			entry("TitlePane.inactiveBackground", "#56648a"),
			entry("TitlePane.foreground", "#f3f5fa"),
			entry("TitlePane.inactiveForeground", "#c3cadb"),
			entry("TitlePane.buttonHoverBackground", "#34549a"),
			entry("TitlePane.buttonPressedBackground", "#2d4b8d"),
			entry("TabbedPane.selectedBackground", "#f7f8fb"),
			entry("TabbedPane.focusColor", "#e6ecf7"));

	/**
	 * Cecil's dark knight armour: a blue-black gunmetal ground under pale lavender text, selections
	 * and default buttons in deep purple, and gold — the rims of those buttons, checkmarks, the bar
	 * across a selected tab, the rule under the menu bar. A selected tab's card is purple-grey,
	 * deeper purple while the tab strip has focus. The title bar is a shade blacker than the
	 * ground, lettered in gold.
	 */
	private static final Map<String, String> DARK_KNIGHT_COLOURS = Map.ofEntries(
			entry("@background", "#23222c"),
			entry("@foreground", "#dcd7e6"),
			entry("@disabledForeground", "#8a8499"),
			entry("@accentBaseColor", "#7149b8"),
			entry("@accentColor", "null"),
			entry("@accentUnderlineColor", "#c9a24c"),
			entry("Button.default.borderColor", "#c9a24c"),
			entry("Button.default.hoverBorderColor", "#dcb966"),
			entry("CheckBox.icon.checkmarkColor", "#d4af5a"),
			entry("MenuBar.borderColor", "#a8873f"),
			entry("TitlePane.borderColor", "#a8873f"),
			entry("TitlePane.unifiedBackground", "false"),
			entry("TitlePane.background", "#16151c"),
			entry("TitlePane.inactiveBackground", "#1d1c24"),
			entry("TitlePane.foreground", "#d6b25e"),
			entry("TitlePane.inactiveForeground", "#7d7889"),
			entry("TitlePane.buttonHoverBackground", "#2b2935"),
			entry("TitlePane.buttonPressedBackground", "#25232e"),
			entry("TabbedPane.selectedBackground", "#34304a"),
			entry("TabbedPane.focusColor", "#3d3260"));

	/**
	 * Milky coffee: a latte-beige ground, cream fields, dark-roast text, and selections and focus in
	 * coffee brown. A selected tab's card is the palest cream, a little darker while the tab strip
	 * has focus.
	 */
	private static final Map<String, String> LATTE_COLOURS = Map.of(
			"@background", "#ebe0cd",
			"@foreground", "#3a2a1f",
			// FlatLaf's tint of the text colour fades too far into beige to read as a hint
			"@disabledForeground", "#7d6b58",
			"@accentBaseColor", "#6f4e37",
			"@accentColor", "null",
			"TabbedPane.selectedBackground", "#f8f3ea",
			"TabbedPane.focusColor", "#f3e9d9");

	/**
	 * A dark roast: an espresso-brown ground under crema text, with selections and focus in caramel.
	 * The ground is {@link ElementColor#DARK}'s, the field colour the opponent's half of the board
	 * has by default, so the panels beside the board match it. A selected tab's card is a lighter
	 * roast, caramel-tinted while the tab strip has focus.
	 */
	private static final Map<String, String> ESPRESSO_COLOURS = Map.of(
			"@background", "#2e2825",
			"@foreground", "#e3d4c2",
			"@accentBaseColor", "#a8693a",
			"@accentColor", "null",
			"TabbedPane.selectedBackground", "#3e3531",
			"TabbedPane.focusColor", "#4a3a2e");

	/** The theme {@link #apply()} last installed. */
	private static Theme current = CLASSIC;

	private final String label;

	Theme(String label) {
		this.label = label;
	}

	/**
	 * The theme in use: the one {@link #apply()} last installed, which can differ from
	 * {@link #saved()} for the moment between Preferences saving a choice and applying it.
	 */
	public static Theme current() {
		return current;
	}

	@Override
	public String toString() {
		return label;
	}

	/**
	 * The saved choice, or {@link #systemDefault()} until the player picks one. The default is not
	 * saved, so it goes on following the OS from one launch to the next until a choice is made. A
	 * name this build does not know counts as no choice.
	 */
	public static Theme saved() {
		String name = AppSettings.getTheme();
		if (name != null) {
			try {
				return valueOf(name);
			} catch (IllegalArgumentException e) {
				// fall through to the default
			}
		}
		return systemDefault();
	}

	/**
	 * What a player who has not chosen a theme gets: {@link #DARK_KNIGHT} when the OS is set to
	 * dark mode, {@link #PALADIN} otherwise, including wherever the mode cannot be read.
	 */
	static Theme systemDefault() {
		return SystemDefault.THEME;
	}

	/**
	 * Asks the OS once a run, on first use: {@link #systemIsDark()} starts a process. Asking once
	 * also keeps Preferences showing the theme that was applied at launch, should the OS switch
	 * modes while the app is open.
	 */
	private static final class SystemDefault {
		static final Theme THEME = systemIsDark() ? DARK_KNIGHT : PALADIN;
	}

	public void save() {
		AppSettings.setTheme(name());
		AppSettings.save();
	}

	public boolean isDark() {
		return switch (this) {
			case CLASSIC, PALADIN, LIGHT -> false;
			case DARK_KNIGHT, DARK -> true;
			// case SYSTEM -> systemIsDark();
		};
	}

	/** The FlatLaf variables and keys this theme sets over FlatLaf's own light or dark theme. */
	private Map<String, String> colours() {
		return switch (this) {
			case CLASSIC -> Map.of();
			case PALADIN -> PALADIN_COLOURS;
			case DARK_KNIGHT -> DARK_KNIGHT_COLOURS;
			case LIGHT -> LATTE_COLOURS;
			case DARK -> ESPRESSO_COLOURS;
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
			current = this;
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
		// Tabs as cards, with the content boxed in below them, as Metal draws them. FlatLaf's own
		// style marks the selected tab with an underline alone, which fades to half strength once the
		// tab strip loses focus; a card keeps its fill, its outline and an accent bar across the top,
		// and the bar keeps its full colour. Each theme names the selected card's fill.
		FlatLaf.setGlobalExtraDefaults(Map.of(
				"Table.showVerticalLines", "true",
				"Table.intercellSpacing", "1,0",
				"TabbedPane.tabType", "card",
				"TabbedPane.hasFullBorder", "true",
				"TabbedPane.inactiveUnderlineColor", "$TabbedPane.underlineColor"));
		FlatLaf laf = isDark() ? new FlatDarkLaf() : new FlatLightLaf();
		laf.setExtraDefaults(colours());
		FlatLaf.setup(laf);
		current = this;
		FlatLaf.updateUI();
	}

	/**
	 * Whether the OS is set to dark mode: Windows' "app mode", macOS' appearance, or GNOME's
	 * colour scheme. Anything that cannot be read counts as light. Read for
	 * {@link #systemDefault()}.
	 */
	static boolean systemIsDark() {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		if (os.contains("win")) {
			return windowsAppsAreDark(run("reg", "query",
					"HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
					"/v", "AppsUseLightTheme"));
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

	/**
	 * Whether {@code reg query}'s report of {@code AppsUseLightTheme} says apps are set to dark:
	 * the value is 0, printed {@code AppsUseLightTheme    REG_DWORD    0x0}. A missing value — on a
	 * Windows older than 10, or an empty report from a query that failed — reads as light.
	 */
	static boolean windowsAppsAreDark(String regOutput) {
		return regOutput.matches("(?s).*AppsUseLightTheme\\s+REG_DWORD\\s+0x0+\\s.*");
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
