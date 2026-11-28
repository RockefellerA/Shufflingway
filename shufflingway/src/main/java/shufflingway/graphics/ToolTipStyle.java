package shufflingway.graphics;

import java.awt.Color;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.UIManager;
import javax.swing.border.BevelBorder;
import javax.swing.border.Border;
import javax.swing.plaf.BorderUIResource;
import javax.swing.plaf.ColorUIResource;

import shufflingway.UiScale;

/**
 * How every tooltip in the app looks: white text on dark grey inside a framed, raised bevel, in
 * place of each look-and-feel's own box. {@link #install} makes it the default under every theme;
 * {@link AbilityToolTip} applies it directly and adds its own line fitting. The
 * menus a card opens to be played wear it too ({@link CardActionMenu}).
 *
 * <p>The font is left to the theme, so a dialog's tooltips keep the size of the dialog's text.
 */
public final class ToolTipStyle {

	public static final Color BACKGROUND = new Color(0x2c, 0x2c, 0x31);
	public static final Color TEXT       = Color.WHITE;

	// Raised bevel: lit along the top and left, shaded along the bottom and right, two lines deep.
	private static final Color HIGHLIGHT_OUTER = new Color(0x9c, 0x9c, 0xa4);
	private static final Color HIGHLIGHT_INNER = new Color(0x5e, 0x5e, 0x66);
	private static final Color SHADOW_OUTER    = new Color(0x0e, 0x0e, 0x11);
	private static final Color SHADOW_INNER    = new Color(0x1c, 0x1c, 0x20);

	// The frame around the bevel, two lines on every side. The bevel's shaded edges are lost on
	// the dark board, so a light line goes round it; a light line is lost on pale card art or a
	// light dialog, so a dark outline goes round that. Between them all four edges read anywhere.
	private static final Color FRAME_OUTER = Color.BLACK;
	private static final Color FRAME_INNER = new Color(0xa8, 0xa8, 0xb0);

	/** The UI defaults {@link #install} sets. */
	static final List<String> KEYS = List.of(
			"ToolTip.background", "ToolTip.foreground", "ToolTip.border",
			"ToolTip.backgroundInactive", "ToolTip.foregroundInactive", "ToolTip.borderInactive");

	private ToolTipStyle() {}

	/**
	 * The frame, the bevel inside it and a tooltip's padding inside that, scaled with the board. A
	 * {@code UIResource}, so a look-and-feel that installs it can also take it back.
	 */
	public static Border border() {
		return framed(UiScale.scale(5), UiScale.scale(8));
	}

	/** The frame and bevel of {@link #border}, around {@code padV} and {@code padH} of padding. */
	public static Border framed(int padV, int padH) {
		int line = Math.max(1, UiScale.scale(1));
		return new BorderUIResource.CompoundBorderUIResource(
				BorderFactory.createCompoundBorder(
						BorderFactory.createLineBorder(FRAME_OUTER, line),
						BorderFactory.createLineBorder(FRAME_INNER, line)),
				BorderFactory.createCompoundBorder(
						BorderFactory.createBevelBorder(BevelBorder.RAISED,
								HIGHLIGHT_OUTER, HIGHLIGHT_INNER, SHADOW_OUTER, SHADOW_INNER),
						BorderFactory.createEmptyBorder(padV, padH, padV, padH)));
	}

	/**
	 * Makes this the look of every tooltip a look-and-feel builds. Values set through
	 * {@link UIManager#put} sit above the look-and-feel's own and outlive a change of theme, so one
	 * call covers every theme; {@code Theme.apply} makes it on each change all the same, after
	 * {@link UiScale} is set, so the border is scaled for the screen.
	 */
	public static void install() {
		ColorUIResource background = new ColorUIResource(BACKGROUND);
		ColorUIResource text       = new ColorUIResource(TEXT);
		Border border = border();
		UIManager.put("ToolTip.background", background);
		UIManager.put("ToolTip.foreground", text);
		UIManager.put("ToolTip.border", border);
		// A disabled control's tooltip usually says why it is disabled, so it keeps the same look;
		// Metal would otherwise draw it in a grey of its own.
		UIManager.put("ToolTip.backgroundInactive", background);
		UIManager.put("ToolTip.foregroundInactive", text);
		UIManager.put("ToolTip.borderInactive", border);
		// FlatLaf's rounded tooltip corners ("ToolTip.borderCornerRadius") are left alone, though
		// on Windows 11 they round the frame's corners off. With them off FlatLaf paints a drop
		// shadow of its own instead, and that popup could show the shadow with no tooltip in it.
	}
}
