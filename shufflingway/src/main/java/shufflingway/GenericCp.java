package shufflingway;

import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Generic CP as a card prints it: the amount in a white circle. Written with Unicode's negative
 * circled digits (❶ to ❿, then ⓫ to ⓴), which light text on a dark tooltip or menu draws as a
 * white disc with the digit standing out of it in the background's colour.
 *
 * <p>Fonts draw them very differently: Classic's Dialog tiny, and in bold as a bare dot; Segoe UI
 * well, but only to ten. So each one is set in {@link #FAMILY}, the first installed font of a few
 * that draw all twenty at the size of the text around them, in bold as well. With none of those
 * installed, the amount is written out as "any" per CP instead.
 */
public final class GenericCp {

	/** Best first: Windows, then macOS, then Linux, then the passable. */
	private static final List<String> CANDIDATES = List.of("Yu Gothic UI", "Cambria Math", "MS Gothic",
			"Hiragino Sans", "Noto Sans CJK JP", "DejaVu Sans", "Segoe UI Symbol", "Apple Symbols");

	/** The font the circles are set in, or {@code null} when none of {@link #CANDIDATES} draws them. */
	static final String FAMILY = findFamily();

	private GenericCp() {}

	/** {@code amount} generic CP: its circle, or "any" per CP where no circle can be drawn. */
	static String label(int amount) {
		String circle = FAMILY == null ? null : circle(amount);
		return circle != null ? circle : String.join(", ", Collections.nCopies(amount, "any"));
	}

	/** {@code html} with every circle in it set in {@link #FAMILY}. */
	static String html(String html) {
		if (FAMILY == null) return html;
		StringBuilder sb = new StringBuilder(html.length());
		for (int i = 0; i < html.length(); i++) {
			char c = html.charAt(i);
			if (isCircle(c)) sb.append("<font face='").append(FAMILY).append("'>").append(c).append("</font>");
			else sb.append(c);
		}
		return sb.toString();
	}

	/** The circled digit for {@code amount}, or {@code null} past twenty. */
	static String circle(int amount) {
		if (amount >= 1 && amount <= 10)  return String.valueOf((char) (0x2776 + amount - 1));
		if (amount >= 11 && amount <= 20) return String.valueOf((char) (0x24EB + amount - 11));
		return null;
	}

	/** {@code like}'s style and size in {@link #FAMILY}, or {@code null} when there is no such font. */
	public static Font font(Font like) {
		return FAMILY == null ? null : new Font(FAMILY, like.getStyle(), like.getSize());
	}

	/** Whether {@code c} is one of the circles. */
	public static boolean isCircle(char c) {
		return (c >= 0x2776 && c <= 0x277F) || (c >= 0x24EB && c <= 0x24F4);
	}

	private static String findFamily() {
		Set<String> installed;
		try {
			installed = new HashSet<>(Arrays.asList(
					GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
		} catch (RuntimeException e) {
			return null;
		}
		for (String family : CANDIDATES) {
			if (!installed.contains(family)) continue;
			Font font = new Font(family, Font.PLAIN, 12);
			if (font.canDisplay(0x2776) && font.canDisplay(0x277F) && font.canDisplay(0x24EB)) return family;
		}
		return null;
	}
}
