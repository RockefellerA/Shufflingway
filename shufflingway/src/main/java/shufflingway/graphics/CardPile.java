package shufflingway.graphics;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;

/**
 * A zone drawn as a pile: its top card over the edges of the cards beneath, the pile deeper the
 * more cards the zone holds. The Main Deck and Break Zone slots use it.
 *
 * <p>The pile is seen from in front and a little to one side, so the cards beneath show along the
 * top card's bottom edge and one side. Its bottom card sits in a bottom corner of the canvas, and
 * each card above it sits a step up and across, so the top card climbs as the pile grows: see
 * {@link Lean}. The canvas has room for a full pile, so it is the same size whatever the count:
 * {@link #size}.
 */
public final class CardPile {

	/** Which way the pile leans: the way the top card moves from the bottom card as the pile grows. */
	public enum Lean {
		/** Bottom card in the bottom-right corner; the cards beneath show along the right edge. */
		LEFT,
		/** Bottom card in the bottom-left corner; the cards beneath show along the left edge. */
		RIGHT
	}

	/** The cards at which a pile reaches its full depth: a Main Deck. A larger zone is drawn no deeper. */
	public static final int FULL = 50;

	/** A full pile's depth, as a fraction of the card's height: 10px on the 205px card at 1080p. */
	private static final double DEPTH_FRACTION = 0.05;

	/** The card stock along a pile's side, alternating so the cards beneath read as separate cards. */
	private static final Color EDGE_LIGHT = new Color(196, 192, 182);
	private static final Color EDGE_DARK  = new Color(150, 146, 138);

	/** The outline of the pile's bottom card, which sets the pile off from the slot behind it. */
	private static final Color BASE_OUTLINE = new Color(20, 20, 20, 200);

	private CardPile() {}

	/** The canvas {@link #render} draws a pile of {@code cw × ch} cards on: a card plus a full pile's rise. */
	public static Dimension size(int cw, int ch) {
		return new Dimension(cw + fullSpanX(cw, ch), ch + fullDepth(ch));
	}

	/**
	 * Renders a pile of {@code count} cards of {@code cw × ch}, {@code top} face up on it, on a
	 * canvas of {@link #size}. The canvas is transparent where the pile does not reach.
	 */
	public static BufferedImage render(Image top, int count, int cw, int ch, Lean lean) {
		Dimension size = size(cw, ch);
		int full = fullDepth(ch);
		int depth = depth(count, ch);
		Rectangle card = topCard(count, cw, ch, lean);
		// Each card beneath sits a step down, and back across the way the pile leans.
		double stepX = full == 0 ? 0 : fullSpanX(cw, ch) / (double) full;
		if (lean == Lean.RIGHT) stepX = -stepX;
		double arc = Math.min(cw, ch) * CardAnimation.CORNER_RADIUS_FRACTION * 2.0;

		BufferedImage canvas = new BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = canvas.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		// Bottom card first.
		for (int i = depth; i >= 1; i--) {
			RoundRectangle2D edge = new RoundRectangle2D.Double(
					card.x + i * stepX, card.y + i, cw, ch, arc, arc);
			g.setColor(i % 2 == 0 ? EDGE_DARK : EDGE_LIGHT);
			g.fill(edge);
			if (i == depth) {
				g.setColor(BASE_OUTLINE);
				g.draw(edge);
			}
		}
		g.drawImage(CardAnimation.toARGB(top, cw, ch), card.x, card.y, null);
		g.dispose();
		return canvas;
	}

	/** Where {@link #render} draws the top card of a {@code count}-card pile, for anything drawn over it. */
	public static Rectangle topCard(int count, int cw, int ch, Lean lean) {
		int full = fullDepth(ch);
		int span = fullSpanX(cw, ch);
		int rise = full - depth(count, ch);
		// Where a left-leaning pile's top card sits; a right-leaning pile is its mirror.
		int leftX = full == 0 ? 0 : (int) Math.round(span * (double) rise / full);
		return new Rectangle(lean == Lean.LEFT ? leftX : span - leftX, rise, cw, ch);
	}

	/** How far a full pile rises, in pixels, under cards {@code ch} tall. */
	private static int fullDepth(int ch) {
		return (int) Math.round(ch * DEPTH_FRACTION);
	}

	/** How far a full pile reaches across: its rise, at the card's own slope. */
	private static int fullSpanX(int cw, int ch) {
		return ch == 0 ? 0 : (int) Math.round(fullDepth(ch) * (double) cw / ch);
	}

	/** How many card edges show beneath the top card of a {@code count}-card pile. */
	private static int depth(int count, int ch) {
		return (int) Math.round(Math.min(Math.max(count, 0), FULL) * fullDepth(ch) / (double) FULL);
	}
}
