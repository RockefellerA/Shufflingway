package shufflingway.graphics;

import java.awt.FontMetrics;

import javax.swing.JToolTip;

/**
 * The tooltip an {@link ActionButton} shows: the app's tooltip look ({@link ToolTipStyle}) in the
 * theme's tooltip font, sized to its text. It applies the look itself rather than relying on the
 * installed defaults, so it reads the same wherever it is built. The text is the ability's HTML
 * description, so its Special name keeps its orange.
 *
 * <p>Only action buttons use it so far; {@link FieldSlotLabel#createToolTip} hands it out while
 * the pointer is over one and the stock tooltip everywhere else on the slot.
 */
public class AbilityToolTip extends JToolTip {

	/**
	 * The widest a description may run on one line before it is split onto two, in multiples of
	 * the font's size (600 px at 12 pt) — about the longest quarter of the action abilities wrap.
	 */
	private static final float ONE_LINE_MAX_EM = 600f / 12f;
	/**
	 * The widest a line may ever be, in multiples of the font's size (680 px at 12 pt); only a
	 * description longer than two of these takes three, which is 29 of the 1,886 action abilities.
	 */
	private static final float LINE_MAX_EM = 680f / 12f;

	public AbilityToolTip() {
		setOpaque(true);
		setBackground(ToolTipStyle.BACKGROUND);
		setForeground(ToolTipStyle.TEXT);
		setBorder(ToolTipStyle.border());
	}

	/**
	 * Sets the description, sized to its text: on one line as wide as the words when it fits,
	 * otherwise wrapped onto two lines of about equal length.
	 *
	 * <p>Swing's HTML only wraps at a width it is given, and one it is given it always fills — so
	 * a fixed width made every short description trail off into empty tooltip. The width is worked
	 * out here instead, from the text as this tooltip's font measures it: none at all for a line
	 * that fits, and otherwise half the text plus its longest word, which is the slack word
	 * wrapping needs to stay on two lines. Past {@link #LINE_MAX_EM} a line goes no wider, and only
	 * then does a description take a third.
	 */
	@Override
	public void setTipText(String tipText) {
		super.setTipText(fitted(tipText));
	}

	private String fitted(String html) {
		if (html == null || !html.startsWith("<html>")) return html;
		FontMetrics fm = getFontMetrics(getFont());
		String plain = plainText(html);
		int width = fm.stringWidth(plain);
		float em = getFont().getSize2D();
		if (width <= ONE_LINE_MAX_EM * em) return html;

		int longestWord = 0;
		for (String word : plain.split("\\s+")) longestWord = Math.max(longestWord, fm.stringWidth(word));
		int lineWidth = Math.min(Math.round(LINE_MAX_EM * em), (width + 1) / 2 + longestWord);
		String body = html.substring("<html>".length()).replaceFirst("(?i)</html>\\s*$", "");
		int cssWidth = Math.round(lineWidth / CSS_PX_TO_SCREEN);
		return "<html><div style='width: " + cssWidth + "px'>" + body + "</div></html>";
	}

	/**
	 * How many screen pixels Swing's HTML lays out per CSS {@code px}: it reads a CSS pixel as
	 * 1/96 inch on a 72-dpi basis, so every width comes out a third wider than written. The width
	 * above is measured in screen pixels, so it is converted before it goes into the style.
	 */
	private static final float CSS_PX_TO_SCREEN = 4f / 3f;

	/** The words of an HTML description as they are drawn: tags dropped, entities read. */
	static String plainText(String html) {
		return html.replaceAll("<[^>]*>", "")
				.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&")
				.replaceAll("\\s+", " ").trim();
	}
}
