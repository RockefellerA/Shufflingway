package shufflingway.graphics;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.function.Supplier;

import javax.swing.JComponent;

import shufflingway.AppSettings;

/**
 * An opponent's hand drawn as a fan of face-down card backs peeking in from the board's outer edge.
 *
 * <p>Only the innermost {@link HandFanLayout#PEEK_FRACTION} of each card is inside the component;
 * the rest is clipped away against the screen edge. {@link HandFanLayout} decides the shape — this
 * class only paints backs into it.
 *
 * <p>Contents are never revealed — an opponent's hand is only ever a count — so the exact number
 * lives in the tooltip and the fan itself carries no text. The seat's own hand is face up and
 * interactive, and is drawn by {@link PlayerHandFanPanel} instead.
 *
 * <p>{@code isP1} is still carried rather than assumed, because the seat decides which edge the
 * cards hang from and which way the fan tilts, and a hot-seat build could want backs on either side.
 */
public class HandFanPanel extends JComponent {

	// Every back is the same image, so without separation the overlaps read as one dark mass. A
	// plain outline is not enough: it has to work against the near-black default art *and* against
	// a light custom cardback. A drop shadow cast onto the card behind, plus a light edge, reads on
	// both — the shadow supplies depth where the art is light, the edge where the art is dark.
	private static final Color SHADOW = new Color(0, 0, 0, 110);
	private static final Color EDGE   = new Color(255, 255, 255, 60);
	/** Shadow offset, as a fraction of CARD_W — scales with the cards rather than the screen. */
	private static final double SHADOW_OFFSET_FRACTION = 0.018;

	/** Matching {@code PlayerHandFanPanel.FACE_SUPERSAMPLE}, so both fans resample the same way. */
	private static final int BACK_SUPERSAMPLE = 2;

	private final boolean         isP1;
	private final Supplier<Image> cardback;

	private int    count;
	/** Cardback pre-scaled to card size, built lazily on first paint. See {@link #cardbackStale()}. */
	private BufferedImage back;
	/** Identity of whatever {@link #back} was built from; a mismatch invalidates the cache. */
	private String backKey = "";

	/** @see HandFanLayout#peekHeight() */
	public static int peekHeight() {
		return HandFanLayout.peekHeight();
	}

	/**
	 * @param isP1     true for the bottom seat (cards peek up), false for the top seat (peek down)
	 * @param cardback supplies the raw cardback image; {@code MainWindow::loadCardbackImage} honours
	 *                 the custom-cardback preference, so this is re-consulted whenever it changes
	 */
	public HandFanPanel(boolean isP1, Supplier<Image> cardback) {
		this.isP1     = isP1;
		this.cardback = cardback;
		// Width 0 mirrors the forward zone's scroll pane: claim height only, never widen the column.
		setPreferredSize(new Dimension(0, peekHeight()));
		setMinimumSize(new Dimension(0, peekHeight()));
		setOpaque(false);
		setCount(0);
	}

	/** Updates the card count, refreshes the tooltip, and repaints only if something changed. */
	public final void setCount(int n) {
		boolean changed = (n != count);
		count = n;
		// Assigned unconditionally: the constructor's seeding call must leave a correct tooltip.
		setToolTipText((isP1 ? "P1" : "P2") + " Hand: " + n);
		if (cardbackStale() || changed) repaint();
	}

	/**
	 * Detects a cardback change and drops the cache if it finds one.
	 *
	 * <p>Nothing notifies us when the preference changes, so — like the deck labels, which simply
	 * reload on every refresh — we re-derive from {@link AppSettings} instead of being told. The
	 * file's length and timestamp join the path because Preferences copies the chosen image to a
	 * fixed destination name: re-picking a <em>different</em> file with the <em>same</em> filename
	 * yields an identical path. Card size joins it so a UI-scale change rebuilds too.
	 */
	private boolean cardbackStale() {
		String path = AppSettings.getCustomCardbackPath();
		String size = "|" + CardAnimation.CARD_W + "x" + CardAnimation.CARD_H;
		String key;
		if (path.isEmpty()) {
			key = "default" + size;
		} else {
			File f = new File(path);
			key = path + "|" + f.length() + "|" + f.lastModified() + size;
		}
		if (key.equals(backKey)) return false;
		backKey = key;
		back    = null;   // rebuilt lazily on the next paint
		return true;
	}

	@Override
	protected void paintComponent(Graphics g0) {
		super.paintComponent(g0);
		int w = getWidth(), h = getHeight();
		if (count <= 0 || w <= 0 || h <= 0) return;

		// Built here rather than in the constructor: tests construct the window without ever
		// painting it, and an eager decode would cost every one of them an image load.
		if (back == null) {
			Image raw = cardback.get();
			if (raw == null) return;
			// Oversized, and scaled back down by the slot transform below — see
			// PlayerHandFanPanel.FACE_SUPERSAMPLE for why a fanned card wants that and a card on
			// the field does not. One image for the whole fan, so it is nearly free here.
			int src = raw.getWidth(null);
			int cap = src > 0 ? Math.max(CardAnimation.CARD_W, src) : Integer.MAX_VALUE;
			int bw  = Math.min(CardAnimation.CARD_W * BACK_SUPERSAMPLE, cap);
			int bh  = (int) Math.round(bw * (double) CardAnimation.CARD_H / CardAnimation.CARD_W);
			back = CardAnimation.toARGB(raw, bw, bh);
		}

		Graphics2D g = (Graphics2D) g0.create();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,   RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,  RenderingHints.VALUE_INTERPOLATION_BICUBIC);
		g.setRenderingHint(RenderingHints.KEY_RENDERING,      RenderingHints.VALUE_RENDER_QUALITY);

		int cw = CardAnimation.CARD_W;
		int ch = CardAnimation.CARD_H;

		HandFanLayout.Slot[] slots =
				HandFanLayout.slots(count, w, isP1, HandFanLayout.restTop(isP1, h));

		double diameter = Math.min(cw, ch) * CardAnimation.CORNER_RADIUS_FRACTION * 2.0;
		RoundRectangle2D outline = new RoundRectangle2D.Double(0, 0, cw - 1, ch - 1, diameter, diameter);
		double shadowOff = cw * SHADOW_OFFSET_FRACTION;
		double dir       = isP1 ? 1 : -1;

		// Left to right, so each card overlaps the one before it and the rightmost sits on top.
		for (HandFanLayout.Slot slot : slots) {
			AffineTransform tx = HandFanLayout.transformFor(slot);

			// Shadow first, so it falls on the card already drawn to the left; the card then covers
			// all of its own shadow but the offset sliver. Cast away from the screen edge, i.e. in
			// the direction the cards actually stand out.
			AffineTransform sx = new AffineTransform(tx);
			sx.preConcatenate(AffineTransform.getTranslateInstance(shadowOff, dir * shadowOff));
			g.setColor(SHADOW);
			g.fill(sx.createTransformedShape(outline));

			AffineTransform bx = new AffineTransform(tx);
			bx.scale(cw / (double) back.getWidth(), ch / (double) back.getHeight());
			g.drawImage(back, bx, null);
			g.setColor(EDGE);
			g.draw(tx.createTransformedShape(outline));
		}

		g.dispose();
	}
}
