package shufflingway;

import com.formdev.flatlaf.FlatLaf;
import java.awt.Color;
import java.awt.Component;
import javax.swing.UIManager;

/**
 * The fixed colours the Swing windows set on their own components, made to read under whichever
 * {@link Theme} is in use.
 *
 * <p>A colour chosen for Metal's white tables and light grey panels is safe only when the component
 * sets its partner too — a background with its own text colour. Setting one alone leaves the other
 * to the theme, and under FlatLaf Dark that pairs a light grey cell with light text, or dark blue
 * text with a dark panel. Every method here returns the Classic colour unchanged, so the original
 * look stays exactly as it was, and a FlatLaf counterpart taken from the theme otherwise.
 *
 * <p>Read at paint or render time where the component allows it, so a theme switched in
 * Preferences reaches an open window on its next repaint.
 */
public final class ThemeColors {

	private ThemeColors() {}

	/** Whether a FlatLaf theme is installed; Classic keeps every colour the app always used. */
	public static boolean isFlat() {
		return UIManager.getLookAndFeel() instanceof FlatLaf;
	}

	/** Whether the theme in use is a dark one. */
	public static boolean isDark() {
		return isFlat() && FlatLaf.isLafDark();
	}

	/**
	 * The shading of a cell with nothing in it — a Summon's Power, a Backup's Job: Classic's light
	 * grey, or under FlatLaf the table's own background drawn a little toward its text, which reads
	 * the same way in light and dark.
	 */
	public static Color emptyCell(Component table) {
		return isFlat() ? mix(table.getBackground(), table.getForeground(), 0.14f) : Color.LIGHT_GRAY;
	}

	/** A boxed value drawn white in Classic; the theme's text-field background under FlatLaf. */
	public static Color valueBox() {
		return uiColor("TextField.background", Color.WHITE);
	}

	/** Text typed into a field, black in Classic. */
	public static Color fieldText() {
		return uiColor("TextField.foreground", Color.BLACK);
	}

	/**
	 * The game log's page: white in Classic; under FlatLaf the theme's text-area background, the
	 * same surface its chat field below is drawn on.
	 */
	public static Color logBackground() {
		return uiColor("TextArea.background", Color.WHITE);
	}

	/** The game log's text, black in Classic. */
	public static Color logText() {
		return uiColor("TextArea.foreground", Color.BLACK);
	}

	/**
	 * A chat line you sent, in the game log: blue. Paired with {@link #logChatOpponent()}'s orange,
	 * the two hues that stay apart under the common forms of colour blindness. Each chat colour is
	 * set for light and dark separately, not mirrored through {@link #text(Color)}, so either can be
	 * tuned without moving the other.
	 */
	public static Color logChatYou() {
		return isDark() ? new Color(0x6AA0E5) : new Color(0x1F5FAF);
	}

	/** A chat line from the opponent, in the game log: orange. See {@link #logChatYou()}. */
	public static Color logChatOpponent() {
		return isDark() ? new Color(0xFFAB59) : new Color(0x974A00);
	}

	/** Secondary text — a hint, a placeholder, an unavailable entry — grey in Classic. */
	public static Color dimText() {
		return uiColor("Label.disabledForeground", Color.GRAY);
	}

	/**
	 * A text colour picked against a light background — a format's blue, a ban's red, a muted grey
	 * — kept readable on a dark theme's background by mirroring its lightness, so the hue survives
	 * and a dark shade becomes the matching light one. Unchanged in Classic and FlatLaf Light, and
	 * for a colour that is already light.
	 */
	public static Color text(Color c) {
		if (c == null || !isDark()) return c;
		float[] hsl = toHsl(c);
		if (hsl[2] >= 0.5f) return c;
		return fromHsl(hsl[0], hsl[1], 1f - hsl[2], c.getAlpha());
	}

	/**
	 * The theme's colour for {@code key}, copied out of its {@code ColorUIResource}. A look treats a
	 * UIResource colour as its own default, free to replace: FlatLaf swaps a text area's for its
	 * read-only background the moment the area is made read-only.
	 */
	private static Color uiColor(String key, Color classic) {
		if (!isFlat()) return classic;
		Color c = UIManager.getColor(key);
		return c != null ? new Color(c.getRGB(), true) : classic;
	}

	private static Color mix(Color a, Color b, float t) {
		return new Color(
				Math.round(a.getRed()   + (b.getRed()   - a.getRed())   * t),
				Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * t),
				Math.round(a.getBlue()  + (b.getBlue()  - a.getBlue())  * t));
	}

	private static float[] toHsl(Color c) {
		float r = c.getRed() / 255f, g = c.getGreen() / 255f, b = c.getBlue() / 255f;
		float max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
		float l = (max + min) / 2f;
		if (max == min) return new float[]{0f, 0f, l};
		float d = max - min;
		float s = l > 0.5f ? d / (2f - max - min) : d / (max + min);
		float h;
		if (max == r)      h = (g - b) / d + (g < b ? 6f : 0f);
		else if (max == g) h = (b - r) / d + 2f;
		else               h = (r - g) / d + 4f;
		return new float[]{h / 6f, s, l};
	}

	private static Color fromHsl(float h, float s, float l, int alpha) {
		if (s == 0f) {
			int v = Math.round(l * 255f);
			return new Color(v, v, v, alpha);
		}
		float q = l < 0.5f ? l * (1f + s) : l + s - l * s;
		float p = 2f * l - q;
		return new Color(
				Math.round(hue(p, q, h + 1f / 3f) * 255f),
				Math.round(hue(p, q, h) * 255f),
				Math.round(hue(p, q, h - 1f / 3f) * 255f),
				alpha);
	}

	private static float hue(float p, float q, float t) {
		if (t < 0f) t += 1f;
		if (t > 1f) t -= 1f;
		if (t < 1f / 6f) return p + (q - p) * 6f * t;
		if (t < 1f / 2f) return q;
		if (t < 2f / 3f) return p + (q - p) * (2f / 3f - t) * 6f;
		return p;
	}
}
