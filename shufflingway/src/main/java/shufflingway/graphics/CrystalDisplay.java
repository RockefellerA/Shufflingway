package shufflingway.graphics;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.MultipleGradientPaint.ColorSpaceType;
import java.awt.MultipleGradientPaint.CycleMethod;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.util.ArrayDeque;

import javax.swing.DefaultButtonModel;
import javax.swing.Timer;

import shufflingway.FontLoader;

/**
 * A player's Crystal count: an elongated upright hexagon (point-top, flat left/right sides) in the
 * player's colour, the count centred inside it, on a raised face like the bar's buttons beside it.
 * It is not a button to click: it never lights up, sinks or fires.
 *
 * <p>A change of count is shown with a soft flash, white for a Crystal gained and yellow for one
 * spent. The flash brightens and fades over {@link #FLASH_FRAMES}, and the number changes at its
 * brightest. A count set while a flash runs waits for it to finish, so each change gets a flash.
 */
public class CrystalDisplay extends BezelButton {
	public static final int CRYSTAL_H = 36;  // default height

	/** Frame interval of the flash, as the other animations use. */
	static final int FRAME_MS = 16;
	/** How long one flash lasts, rising and falling: 448 ms. */
	static final int FLASH_FRAMES = 28;
	/** The flash's brightest frame, where the number changes. */
	static final int APEX_FRAME = FLASH_FRAMES / 2;

	/** The flash for a count going up, and for one going down. */
	static final Color GAIN_FLASH  = new Color(255, 255, 255);
	static final Color SPEND_FLASH = new Color(255, 214, 80);
	/**
	 * How opaque each flash is over the hexagon at its brightest. The yellow is laid on thicker: a
	 * thin yellow over the ice-blue hexagon mixes to green, and over the fire-red one to orange.
	 */
	private static final float GAIN_WASH_ALPHA  = 0.55f;
	private static final float SPEND_WASH_ALPHA = 0.75f;
	/** How opaque the glow around the hexagon is at the flash's brightest. */
	private static final float GLOW_ALPHA = 0.45f;

	/** The count drawn. While a flash is rising, it is still the count before the change. */
	private int shown;
	/** Counts set but not yet flashed in, oldest first. Each differs from the one before it. */
	private final ArrayDeque<Integer> queued = new ArrayDeque<>();
	/** The running flash: the count it brings in, its colour, and its frame, -1 when none runs. */
	private int flashTo;
	private Color flashColor = GAIN_FLASH;
	private int flashFrame = -1;
	private Timer frames;

	/** The hexagon's colour. Fixed, so the white count always reads against it. */
	private final Color color;

	public CrystalDisplay(int initial, Color color) {
		super("", Round.NONE);
		this.shown = initial;
		this.color = color;
		// Left enabled, so it is drawn raised rather than dimmed, but with a model the mouse and
		// keyboard cannot arm, press or roll over.
		setModel(new DefaultButtonModel() {
			@Override public void setArmed(boolean b)    {}
			@Override public void setPressed(boolean b)  {}
			@Override public void setRollover(boolean b) {}
		});
		setFocusable(false);
		setPreferredSize(new Dimension(CardAnimation.CARD_W, CRYSTAL_H));
		setMinimumSize(new Dimension(CardAnimation.CARD_W, CRYSTAL_H));
		setMaximumSize(new Dimension(CardAnimation.CARD_W, CRYSTAL_H));
		setToolTipText("Crystals");
	}

	/**
	 * Shows {@code n}, flashing it in: white when it is more than the count before it, yellow when
	 * less. Setting the count it already shows or is about to show does nothing. Off screen there is
	 * nothing to see, so the number simply changes.
	 */
	public void setCount(int n) {
		if (n == latest()) return;
		if (!animates()) {
			cancelFlash();
			shown = n;
			repaint();
			return;
		}
		queued.add(n);
		if (flashFrame < 0) startNext();
	}

	/** Resets the count for a new game, at once and without a flash. */
	public void hardReset() {
		cancelFlash();
		shown = 0;
		repaint();
	}

	/** The count drawn right now: until a flash reaches its brightest, the count before the change. */
	int shownCount() {
		return shown;
	}

	/** The running flash's strength, from 0 to 1 at its brightest; 0 when none runs. */
	double flashLevel() {
		if (flashFrame < 0) return 0;
		double s = Math.sin(Math.PI * flashFrame / FLASH_FRAMES);
		return s * s;
	}

	/** The running flash's colour, or {@code null} when none runs. */
	Color flashColor() {
		return flashFrame < 0 ? null : flashColor;
	}

	/** Whether a change is animated: only when the badge is on screen. Overridden by tests. */
	boolean animates() {
		return isShowing();
	}

	/** Advances the running flash one frame, changing the number on its brightest frame. */
	void tick() {
		if (flashFrame < 0) return;
		flashFrame++;
		if (flashFrame == APEX_FRAME) shown = flashTo;
		if (flashFrame >= FLASH_FRAMES) startNext();
		repaint();
	}

	/** The last count set, whether or not it is drawn yet. */
	private int latest() {
		if (!queued.isEmpty()) return queued.peekLast();
		return flashFrame >= 0 ? flashTo : shown;
	}

	/** Starts the flash for the next queued count, or stops when there is none. */
	private void startNext() {
		if (queued.isEmpty()) {
			cancelFlash();
			return;
		}
		flashTo    = queued.pollFirst();
		flashColor = flashTo > shown ? GAIN_FLASH : SPEND_FLASH;
		flashFrame = 0;
		if (frames == null) {
			frames = new Timer(FRAME_MS, e -> tick());
			frames.start();
		}
		repaint();
	}

	private void cancelFlash() {
		queued.clear();
		flashFrame = -1;
		if (frames != null) {
			frames.stop();
			frames = null;
		}
	}

	@Override
	protected void paintComponent(Graphics g0) {
		super.paintComponent(g0);
		Graphics2D g = (Graphics2D) g0.create();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

		int cw = getWidth(), ch = getHeight();

		// Elongated upright hexagon, 11 wide to 16 tall, filling the height less 2px top and bottom,
		// centered: 22 x 32 in a 36px row.
		int hxH = Math.max(8, ch - 4);
		int hxW = Math.round(hxH * 11 / 16f);
		int hxX = (cw - hxW) / 2;
		int hxY = (ch - hxH) / 2;

		int shoulder = hxH / 5;
		int[] xp = { hxX + hxW/2, hxX + hxW, hxX + hxW, hxX + hxW/2, hxX,                   hxX           };
		int[] yp = { hxY,          hxY + shoulder, hxY + hxH - shoulder, hxY + hxH, hxY + hxH - shoulder, hxY + shoulder };

		double level = flashLevel();
		if (level > 0) paintGlow(g, cw / 2.0, ch / 2.0, hxW * 1.6, ch / 2.0, level);

		g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 210));
		g.fillPolygon(xp, yp, 6);
		if (level > 0) {
			float wash = flashColor == SPEND_FLASH ? SPEND_WASH_ALPHA : GAIN_WASH_ALPHA;
			g.setColor(withAlpha(flashColor, wash * level));
			g.fillPolygon(xp, yp, 6);
		}
		g.setColor(color.darker());
		g.setStroke(new BasicStroke(1.5f));
		g.drawPolygon(xp, yp, 6);

		// Count number centered inside the hexagon (no colon)
		g.setFont(FontLoader.loadOverlayFont(10));
		g.setColor(Color.WHITE);
		String text = String.valueOf(shown);
		FontMetrics fm = g.getFontMetrics();
		int tx = (cw - fm.stringWidth(text)) / 2;
		int ty = hxY + hxH / 2 + fm.getAscent() / 2 - 1;
		g.drawString(text, tx, ty);

		g.dispose();
	}

	/**
	 * The flash's light around the hexagon: an elliptical glow centred on it, {@code rx} by
	 * {@code ry}, fading to nothing at its rim. Its height is the badge's own, so it is never cut
	 * off by the badge's edge.
	 */
	private void paintGlow(Graphics2D g, double cx, double cy, double rx, double ry, double level) {
		AffineTransform toEllipse = new AffineTransform(rx, 0, 0, ry, cx, cy);
		float a = (float) (GLOW_ALPHA * level);
		g.setPaint(new RadialGradientPaint(new Point2D.Double(0, 0), 1f, new Point2D.Double(0, 0),
				new float[] { 0f, 0.45f, 1f },
				new Color[] { withAlpha(flashColor, a), withAlpha(flashColor, a * 0.45f), withAlpha(flashColor, 0f) },
				CycleMethod.NO_CYCLE, ColorSpaceType.SRGB, toEllipse));
		g.fillRect(0, 0, getWidth(), getHeight());
	}

	private static Color withAlpha(Color c, double alpha) {
		int a = (int) Math.round(Math.max(0, Math.min(1, alpha)) * 255);
		return new Color(c.getRed(), c.getGreen(), c.getBlue(), a);
	}
}
