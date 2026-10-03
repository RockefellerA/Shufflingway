package shufflingway.graphics;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;

import javax.swing.BorderFactory;
import javax.swing.JToolTip;
import javax.swing.UIManager;
import javax.swing.border.BevelBorder;

import shufflingway.UiScale;

/**
 * The tooltip an {@link ActionButton} shows: white text on dark grey inside a raised bevel, in
 * place of the look-and-feel's pale yellow box. The text is the ability's HTML description, so
 * its Special name keeps its orange.
 *
 * <p>Only action buttons use it so far; {@link FieldSlotLabel#createToolTip} hands it out while
 * the pointer is over one and the stock tooltip everywhere else on the slot.
 */
public class AbilityToolTip extends JToolTip {

	private static final Color BACKGROUND = new Color(0x2c, 0x2c, 0x31);
	private static final Color TEXT       = Color.WHITE;

	// Raised bevel: lit along the top and left, shaded along the bottom and right, two lines deep.
	private static final Color HIGHLIGHT_OUTER = new Color(0x9c, 0x9c, 0xa4);
	private static final Color HIGHLIGHT_INNER = new Color(0x5e, 0x5e, 0x66);
	private static final Color SHADOW_OUTER    = new Color(0x0e, 0x0e, 0x11);
	private static final Color SHADOW_INNER    = new Color(0x1c, 0x1c, 0x20);

	/**
	 * The widest a description may run on one line before it is split onto two — about the
	 * longest quarter of the action abilities wrap.
	 */
	private static final int ONE_LINE_MAX = 600;
	/**
	 * The widest a line may ever be; only a description longer than two of these takes three,
	 * which is 29 of the 1,886 action abilities.
	 */
	private static final int LINE_MAX = 680;

	public AbilityToolTip() {
		setOpaque(true);
		setBackground(BACKGROUND);
		setForeground(TEXT);
		int padV = UiScale.scale(5), padH = UiScale.scale(8);
		setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createBevelBorder(BevelBorder.RAISED,
						HIGHLIGHT_OUTER, HIGHLIGHT_INNER, SHADOW_OUTER, SHADOW_INNER),
				BorderFactory.createEmptyBorder(padV, padH, padV, padH)));
		Font base = UIManager.getFont("ToolTip.font");
		if (base != null) setFont(base.deriveFont(UiScale.scale(12f)));
	}

	/**
	 * Sets the description, sized to its text: on one line as wide as the words when it fits,
	 * otherwise wrapped onto two lines of about equal length.
	 *
	 * <p>Swing's HTML only wraps at a width it is given, and one it is given it always fills — so
	 * a fixed width made every short description trail off into empty tooltip. The width is worked
	 * out here instead, from the text as this tooltip's font measures it: none at all for a line
	 * that fits, and otherwise half the text plus its longest word, which is the slack word
	 * wrapping needs to stay on two lines. Past {@link #LINE_MAX} a line goes no wider, and only
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
		if (width <= UiScale.scale(ONE_LINE_MAX)) return html;

		int longestWord = 0;
		for (String word : plain.split("\\s+")) longestWord = Math.max(longestWord, fm.stringWidth(word));
		int lineWidth = Math.min(UiScale.scale(LINE_MAX), (width + 1) / 2 + longestWord);
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
