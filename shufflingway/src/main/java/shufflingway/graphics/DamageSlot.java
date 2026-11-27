package shufflingway.graphics;

import java.awt.Color;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.geom.Rectangle2D;

import javax.swing.JPanel;
import javax.swing.Timer;

import shufflingway.FontLoader;
import shufflingway.UiScale;

/**
 * One tile of a Damage Zone: a letter of D-A-M-A-G-E, or the player's tag on the last tile, on a
 * raised {@link Bezel} like the LIMIT, RFP and Crystal buttons. A point of damage presses it in:
 * it sinks and turns red, showing the card that went there, with "EX" in the opposite corner when
 * that card has an EX Burst. A tile whose card leaves the Damage Zone comes back out.
 *
 * <p>The tile is filled while it has a card image, which arrives once the card has slid into
 * place, so it presses in as the card lands. Going in and coming out each take
 * {@link #POP_FRAMES}.
 */
public class DamageSlot extends JPanel {

	/** Frame interval of the press, as the other animations use. */
	static final int FRAME_MS = 16;
	/** How long a tile takes to press in, or to come back out: 128 ms. */
	static final int POP_FRAMES = 8;
	/** The face of an empty tile, and of one holding a point of damage. */
	static final Color EMPTY  = Color.DARK_GRAY;
	static final Color FILLED = Color.RED;

	private final String  letter;
	/** P1's tiles show the card on the left, P2's on the right, the mirror of each other. */
	private final boolean cardOnLeft;

	private Image   card;
	private boolean exBurst;

	/** How far in the tile is, from 0 (raised and grey) to 1 (pressed in and red). */
	private double sink;
	/** The running press: where it started, where it is going, and its frame, -1 when none runs. */
	private double popFrom, popTo;
	private int    popFrame = -1;
	private Timer  frames;

	public DamageSlot(String letter, boolean cardOnLeft) {
		this.letter     = letter;
		this.cardOnLeft = cardOnLeft;
		setOpaque(true);
	}

	/** The bezel is the tile's whole background, which a board-colour sweep must not repaint. */
	@Override public void setBackground(Color c) {}

	/**
	 * Shows {@code img}, the card in this point of the Damage Zone, or empties the tile when
	 * {@code null}. A tile that gains its card presses in; one that loses it comes back out. Changing
	 * the card of a tile already in changes only the picture.
	 */
	public void setCard(Image img) {
		boolean wasFilled = card != null;
		card = img;
		if ((img != null) != wasFilled) moveTo(img != null ? 1 : 0);
		repaint();
	}

	/** Marks the card as having an EX Burst, drawn as "EX" in the corner away from it. */
	public void setExBurst(boolean exBurst) {
		this.exBurst = exBurst;
		repaint();
	}

	/** Empties the tile at once, raised and grey, without the animation: for a new game. */
	public void reset() {
		stopFrames();
		card    = null;
		exBurst = false;
		sink    = 0;
		repaint();
	}

	/** How far in the tile is drawn, from 0 (raised) to 1 (pressed in). */
	double sink() {
		return sink;
	}

	/** Whether a change is animated: only when the tile is on screen. Overridden by tests. */
	boolean animates() {
		return isShowing();
	}

	/** Advances the running press one frame, easing out so the tile seats firmly. */
	void tick() {
		if (popFrame < 0) return;
		popFrame++;
		double p = Math.min(1.0, popFrame / (double) POP_FRAMES);
		double eased = 1 - Math.pow(1 - p, 3);
		sink = popFrom + (popTo - popFrom) * eased;
		if (popFrame >= POP_FRAMES) stopFrames();
		repaint();
	}

	/** Starts the tile toward {@code target}, from wherever it is now, so a reversal mid-press is smooth. */
	private void moveTo(double target) {
		if (!animates()) {
			stopFrames();
			sink = target;
			return;
		}
		popFrom  = sink;
		popTo    = target;
		popFrame = 0;
		if (frames == null) {
			frames = new Timer(FRAME_MS, e -> tick());
			frames.start();
		}
	}

	private void stopFrames() {
		popFrame = -1;
		if (frames != null) {
			frames.stop();
			frames = null;
		}
	}

	@Override
	protected void paintComponent(Graphics g0) {
		Graphics2D g = (Graphics2D) g0.create();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

		int w = getWidth(), h = getHeight();
		int bevel = Math.max(2, UiScale.scale(2));
		Bezel.paint(g,
				new Rectangle2D.Double(0, 0, w, h),
				new Rectangle2D.Double(bevel, bevel, w - 2 * bevel, h - 2 * bevel),
				new Rectangle2D.Double(0.5, 0.5, w - 1, h - 1),
				bevel, Bezel.mix(EMPTY, FILLED, sink), sink);

		// What is on the face rides with it, a pixel down and right once it is in, as a pressed
		// button's label does.
		int shift = sink >= 0.5 ? 1 : 0;
		g.translate(shift, shift);
		int fx = bevel, fy = bevel, fw = w - 2 * bevel, fh = h - 2 * bevel;
		if (card != null) paintCard(g, fx, fy, fw, fh);

		g.setFont(FontLoader.loadPixelFont(14));
		g.setColor(Color.WHITE);
		FontMetrics fm = g.getFontMetrics();
		g.drawString(letter, (w - fm.stringWidth(letter)) / 2, (h + fm.getAscent() - fm.getDescent()) / 2);

		if (exBurst) {
			g.setFont(FontLoader.loadPixelFont(9));
			FontMetrics exFm = g.getFontMetrics();
			int exX = cardOnLeft ? fx + fw - exFm.stringWidth("EX") - 1 : fx + 1;
			int exY = fy + exFm.getAscent();
			g.setColor(Color.BLACK);
			g.drawString("EX", exX + 1, exY + 1);
			g.setColor(Color.YELLOW);
			g.drawString("EX", exX, exY);
		}
		g.dispose();
	}

	/** The card, fitted to half the face's width on this side, centred top to bottom. */
	private void paintCard(Graphics2D g, int fx, int fy, int fw, int fh) {
		int iw = card.getWidth(null), ih = card.getHeight(null);
		if (iw <= 0 || ih <= 0) return;
		double scale = Math.min((fw / 2.0) / iw, (double) fh / ih);
		int dw = (int) (iw * scale), dh = (int) (ih * scale);
		int dx = cardOnLeft ? fx : fx + fw - dw;
		int dy = fy + (fh - dh) / 2;
		g.drawImage(card, dx, dy, dx + dw, dy + dh, 0, 0, iw, ih, null);
	}
}
