package shufflingway.graphics;

import shufflingway.CardState;
import shufflingway.FontLoader;
import shufflingway.UiScale;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.RescaleOp;
import java.util.List;

import javax.swing.ImageIcon;

/** Static utilities for rendering and transforming card images. */
public class CardAnimation {

	public static int CARD_W = UiScale.scale(140);
	public static int CARD_H = UiScale.scale(205);

	/**
	 * Empty canvas kept along the card's left edge, so {@link TraitTab} has somewhere to poke into.
	 * An ACTIVE card's art is inset this far from the canvas's left side. A DULL card is
	 * {@code CARD_H} wide and fills the canvas sideways, so its art is inset this far from the top
	 * instead — the same card edge once rotated CW.
	 *
	 * <p>What is left of the canvas after the art and this gutter, {@link #rightGutter()}, sits
	 * along the card's right edge: beside an ACTIVE card and below a DULL one. {@link ActionButton}
	 * uses it. {@link #renderBackupCardAtAngle} slides between the two orientations so the rotation
	 * stays continuous.
	 */
	public static int LEFT_GUTTER = UiScale.scale(30);

	/** The strip along the card's right edge: beside an ACTIVE card, below a DULL one. See {@link #LEFT_GUTTER}. */
	public static int rightGutter() {
		return CARD_H - CARD_W - LEFT_GUTTER;
	}

	private CardAnimation() {}

	/**
	 * Renders a rotation frame, {@code angle} running 0 (active, upright) to {@code PI/2} (dull).
	 * The card's centre is interpolated between the anchors {@link #renderBackupCard} uses for the
	 * two end states, so frame 0 and the final frame land exactly on the static renders instead of
	 * popping, and the art visibly slides left out of {@link #LEFT_GUTTER} as it dulls.
	 */
	public static BufferedImage renderBackupCardAtAngle(BufferedImage card, double angle) {
		BufferedImage canvas = new BufferedImage(CARD_H, CARD_H, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = canvas.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
		double t = Math.max(0.0, Math.min(1.0, angle / (Math.PI / 2)));
		// Active: art at (LEFT_GUTTER, 0) -> centre (LEFT_GUTTER + CARD_W/2, CARD_H/2).
		// Dull:   art at (0, LEFT_GUTTER) spanning CARD_H x CARD_W -> centre (CARD_H/2, LEFT_GUTTER + CARD_W/2).
		double cx = lerp(LEFT_GUTTER + CARD_W / 2.0, CARD_H / 2.0, t);
		double cy = lerp(CARD_H / 2.0, LEFT_GUTTER + CARD_W / 2.0, t);
		g.translate(cx, cy);
		g.rotate(angle);
		g.translate(-CARD_W / 2.0, -CARD_H / 2.0);
		g.drawImage(card, 0, 0, null);
		g.dispose();
		return canvas;
	}

	private static double lerp(double from, double to, double t) {
		return from + (to - from) * t;
	}

	/**
	 * Composites a (possibly transformed) card image onto a square
	 * {@code CARD_H × CARD_H} canvas, respecting the slot alignment rules:
	 * <ul>
	 *   <li>Active - upright card pinned to the left edge, top</li>
	 *   <li>Dull — card rotated 90° CW ({@code CARD_H × CARD_W}), pinned left, below the tab gutter</li>
	 * </ul>
	 */
	public static BufferedImage renderBackupCard(BufferedImage card, CardState state) {
		return renderBackupCard(card, state, false);
	}

	public static BufferedImage renderBackupCard(BufferedImage card, CardState state, boolean highlight) {
		return renderBackupCard(card, state, highlight, false);
	}

	public static BufferedImage renderBackupCard(BufferedImage card, CardState state, boolean highlight, boolean selected) {
		return renderBackupCard(card, state, highlight, selected, false);
	}

	public static BufferedImage renderBackupCard(BufferedImage card, CardState state, boolean highlight, boolean selected, boolean frozen) {
		return renderBackupCard(card, state, highlight, selected, frozen, null);
	}

	/** Glow on a card the player has picked — an attacker being lined up, a blocker being assigned. */
	public static final Color GLOW_SELECTED   = new Color(255, 165, 0);
	/** Glow on a card the player could still act with this step. */
	public static final Color GLOW_ACTIONABLE = new Color(0, 220, 0);
	/** Glow on a card that is part of a declared attack, on either side of the board. */
	public static final Color GLOW_ATTACKING  = new Color(215, 45, 45);
	/**
	 * Glow on a declared blocker, on either side of the board, until its battle is over. The orange
	 * of {@link #GLOW_SELECTED}, so P1's blocker reads the same once declared as while it was being
	 * assigned, and the opponent's reads the same as P1's.
	 */
	public static final Color GLOW_BLOCKING   = GLOW_SELECTED;
	/** Glow on a card that has attacked as often as it may this turn. */
	public static final Color GLOW_EXHAUSTED  = new Color(140, 140, 140);

	/**
	 * @param glow drawn when neither {@code selected} nor {@code highlight} applies; {@code null}
	 *             for none. A slot has one glow, so the three are ranked rather than blended: what
	 *             the player is doing right now outranks what the board is telling them about.
	 */
	public static BufferedImage renderBackupCard(BufferedImage card, CardState state, boolean highlight,
			boolean selected, boolean frozen, Color glow) {
		BufferedImage canvas = new BufferedImage(CARD_H, CARD_H, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = canvas.createGraphics();
		if (frozen) card = applyBlueTint(card);
		switch (state) {
			case CardState.DULL -> {
				BufferedImage rotated = rotateCW90(card);          // now CARD_H × CARD_W
				g.drawImage(rotated, 0, LEFT_GUTTER, null);        // pinned left, below the tab gutter
			}
			default -> g.drawImage(card, LEFT_GUTTER, 0, null);   // pinned to top, past the tab gutter
		}

		// Card bounds within the square canvas depend on orientation
		boolean dull = (state == CardState.DULL);
		int cx = dull ? 0 : LEFT_GUTTER,  cy = dull ? LEFT_GUTTER : 0;
		int cw = dull ? CARD_H : CARD_W;
		int ch = dull ? CARD_W : CARD_H;

		if (selected) {
			drawRoundedGlow(g, GLOW_SELECTED, cx, cy, cw, ch);
		} else if (highlight) {
			drawRoundedGlow(g, GLOW_ACTIONABLE, cx, cy, cw, ch);
		} else if (glow != null) {
			drawRoundedGlow(g, glow, cx, cy, cw, ch);
		}
		g.dispose();
		return canvas;
	}

	/**
	 * Draws a multi-layer inner glow on {@code g} within the rectangle
	 * {@code (cx, cy, cw, ch)}.  Layers fade inward with a quadratic falloff,
	 * matching the PhaseTracker halo style.  Sharp-cornered — for card displays outside the
	 * field (hand, Break Zone, dialogs), where card images are shown rectangular.
	 */
	public static void drawGlow(Graphics2D g, Color color, int cx, int cy, int cw, int ch) {
		drawGlow(g, color, cx, cy, cw, ch, 0);
	}

	/**
	 * Rounded variant of {@link #drawGlow} for cards on the field: each layer's corner radius
	 * matches the card silhouette clipped in {@link #toARGB} (shrinking as the layers move
	 * inward), so the glow hugs the rounded card edge instead of squaring it off.
	 */
	public static void drawRoundedGlow(Graphics2D g, Color color, int cx, int cy, int cw, int ch) {
		// Same arc diameter as the toARGB rounding mask: 2 × (5% of the shorter side).
		drawGlow(g, color, cx, cy, cw, ch,
				(int) Math.round(Math.min(cw, ch) * CORNER_RADIUS_FRACTION * 2.0));
	}

	private static void drawGlow(Graphics2D g, Color color, int cx, int cy, int cw, int ch, int arc) {
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		int layers = 10;
		for (int layer = layers; layer >= 0; layer--) {
			float t   = (float) layer / layers;      // 1.0 at edge → 0.0 innermost
			int alpha = Math.round(t * t * 185);     // quadratic falloff, max ~185/255
			g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha));
			g.setStroke(new BasicStroke(1.5f));
			int off = layers - layer;
			// Inner parallel curve of a rounded rect: corner radius shrinks with the inset.
			int layerArc = Math.max(0, arc - 2 * off);
			g.drawRoundRect(cx + off, cy + off, cw - 1 - 2 * off, ch - 1 - 2 * off, layerArc, layerArc);
		}
	}

	/** Returns a zero-inset border that paints a multi-layer inner glow using the given {@code color}. */
	public static javax.swing.border.Border createCardGlowBorder(Color color) {
		return new javax.swing.border.AbstractBorder() {
			@Override public void paintBorder(java.awt.Component c, java.awt.Graphics g0,
					int x, int y, int w, int h) {
				drawGlow((Graphics2D) g0, color, x, y, w, h);
			}
		};
	}

	/** Draws {@code value} in a dark pill at the bottom-right edge of the card (rotated with the card when dull). */
	public static void renderPowerOverlayRight(BufferedImage canvas, int value, Color textColor, CardState state) {
		renderPowerOverlayRight(canvas, value, 0, textColor, state);
	}

	/**
	 * {@link #renderPowerOverlayRight(BufferedImage, int, Color, CardState)} over the power the card
	 * prints, {@code printed}, or 0 for none. The pill is at least as wide as that number, so a
	 * printed digit the overlay has no counterpart for stays covered. Otherwise a 10000 brought
	 * down to 9000 would leave the printed 1 beside the pill, reading as 19000. Below the printed
	 * power the pill is darker as well: the printed number shows faintly through a pill, and a
	 * leftover 1 seen through it would still read as part of the number.
	 */
	public static void renderPowerOverlayRight(BufferedImage canvas, int value, int printed, Color textColor,
			CardState state) {
		renderPill(canvas, value, printed, value < printed ? REDUCED_PILL_ALPHA : PILL_ALPHA, textColor, true, state);
	}

	/**
	 * Where a pill's digits go, as fractions of the card: the power pill's right edge, and the
	 * middle of the digits' height, which is the printed digits' middle. Every card prints its
	 * power right-aligned in the same spot, so the power pill covers the printed number at any
	 * card size, and the printed number shows evenly above and below its shorter digits. The damage
	 * pill mirrors it across the card.
	 */
	private static final double PILL_DIGITS_RIGHT  = 0.955;
	private static final double PILL_DIGITS_MIDDLE = 0.932;
	/** The pill's margin around its digits, as a fraction of the digits' height. */
	private static final double PILL_PAD = 0.45;
	/** The pill's opacity, out of 255: the printed number shows faintly through it. */
	private static final int PILL_ALPHA = 180;
	/** The pill's opacity over a power below the printed one; see {@link #renderPowerOverlayRight(BufferedImage, int, int, Color, CardState)}. */
	private static final int REDUCED_PILL_ALPHA = 225;

	/**
	 * The pills' point size: 14 at 1080p, about a point more at 1440p and a point less at 720p.
	 * Tuned by eye against the printed power. At a fixed fraction of the card the pill reads
	 * small on a large card and heavy on a small one.
	 */
	private static float pillPointSize() {
		return (float) (14 + 3.5 * (UiScale.factor - 1));
	}

	/**
	 * Renders a pill at the card's bottom-left ({@code !alignRight}) or bottom-right ({@code alignRight}) corner,
	 * in the card's natural (unrotated) coordinate frame. When {@code state == DULL} the pill is drawn through
	 * a CW 90° transform so it stays anchored to the card's bottom edge — which lives on the LEFT side of the
	 * dull canvas — and the text reads correctly once the viewer rotates their view to read the dull card.
	 *
	 * <p>The digits are placed by their ink rather than their advance, so the trailing gap in the
	 * pixel font's advance does not push them off the printed number.
	 *
	 * @param cover a number the pill is widened to cover as if it held it, digits still aligned
	 *              as before; 0 for none
	 * @param alpha the pill's opacity, out of 255
	 */
	private static void renderPill(BufferedImage canvas, int value, int cover, int alpha, Color textColor,
			boolean alignRight, CardState state) {
		Graphics2D g = canvas.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		if (state == CardState.DULL) {
			// CW 90° about (0,0) followed by translate so that drawing in (0..CARD_W, 0..CARD_H) lands on
			// the rotated card's natural face: original (0,0) → canvas (CARD_H, LEFT_GUTTER);
			// original (CARD_W, CARD_H) → canvas (0, LEFT_GUTTER + CARD_W).
			g.translate(CARD_H, LEFT_GUTTER);
			g.rotate(Math.PI / 2);
		} else {
			g.translate(LEFT_GUTTER, 0);   // active art starts past the tab gutter
		}
		String text = String.valueOf(value);
		Font font = FontLoader.loadOverlayFont(pillPointSize());
		g.setFont(font);
		Rectangle2D ink = font.createGlyphVector(g.getFontRenderContext(), text).getVisualBounds();
		double boxW = ink.getWidth();
		if (cover > 0)
			boxW = Math.max(boxW, font.createGlyphVector(g.getFontRenderContext(), String.valueOf(cover))
					.getVisualBounds().getWidth());
		double inkLeft = alignRight ? CARD_W * PILL_DIGITS_RIGHT - ink.getWidth()
		                            : CARD_W * (1 - PILL_DIGITS_RIGHT);
		double boxLeft = alignRight ? CARD_W * PILL_DIGITS_RIGHT - boxW : inkLeft;
		double inkTop  = CARD_H * PILL_DIGITS_MIDDLE - ink.getHeight() / 2;
		double pad     = ink.getHeight() * PILL_PAD;
		g.setColor(new Color(0, 0, 0, alpha));
		int arc = Math.max(3, UiScale.scale(5));
		g.fillRoundRect((int) Math.round(boxLeft - pad), (int) Math.round(inkTop - pad),
				(int) Math.round(boxW + 2 * pad), (int) Math.round(ink.getHeight() + 2 * pad), arc, arc);
		g.setColor(textColor);
		g.drawString(text, (int) Math.round(inkLeft - ink.getX()), (int) Math.round(inkTop - ink.getY()));
		g.dispose();
	}

	/** Draws accumulated damage in a red pill at the card's bottom-left (rotated with the card when dull). */
	public static void renderDamageOverlay(BufferedImage canvas, int damage, CardState state) {
		renderPill(canvas, damage, 0, PILL_ALPHA, new Color(255, 50, 50), false, state);
	}

	/**
	 * Draws the counters on a card as the face of a die — an orb for each pile, carrying its count
	 * in its placer's color, laid out as {@link CounterDice} describes — turned with the card when
	 * it is dull.
	 *
	 * @param orbs the card's piles of counters, in the order they were placed
	 */
	public static void renderCounterOverlay(BufferedImage canvas, List<CounterOrb> orbs, CardState state) {
		if (orbs.isEmpty()) return;
		Graphics2D g = canvas.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		if (state == CardState.DULL) {
			g.translate(CARD_H, LEFT_GUTTER);   // the same rotation as renderPill
			g.rotate(Math.PI / 2);
		} else {
			g.translate(LEFT_GUTTER, 0);   // active art starts past the tab gutter
		}
		CounterDice.draw(g, orbs);
		g.dispose();
	}

	/** Returns a {@code CARD_H × CARD_H} placeholder canvas with a card outline and "Loading…" text. */
	public static BufferedImage renderPlaceholder(CardState state) {
		BufferedImage canvas = new BufferedImage(CARD_H, CARD_H, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = canvas.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		int cx, cy, rw, rh;
		if (state == CardState.DULL) {
			// rotated 90° CW: CARD_H wide × CARD_W tall, pinned left, below the tab gutter
			cx = 0; cy = LEFT_GUTTER; rw = CARD_H; rh = CARD_W;
		} else {
			cx = LEFT_GUTTER; cy = 0; rw = CARD_W; rh = CARD_H;
		}

		g.setColor(new Color(30, 30, 30, 200));
		g.fillRoundRect(cx + 2, cy + 2, rw - 4, rh - 4, 10, 10);
		g.setColor(new Color(160, 160, 160));
		g.setStroke(new BasicStroke(2f));
		g.drawRoundRect(cx + 2, cy + 2, rw - 4, rh - 4, 10, 10);

		String text = "Loading...";
		Font font = FontLoader.loadPixelFont(11);
		g.setFont(font);
		FontMetrics fm = g.getFontMetrics();
		int tx = cx + (rw - fm.stringWidth(text)) / 2;
		int ty = cy + (rh + fm.getAscent()) / 2 - fm.getDescent();
		g.setColor(new Color(180, 180, 180));
		g.drawString(text, tx, ty);
		g.dispose();
		return canvas;
	}

	/**
	 * Corner-rounding radius applied to every scaled card image, as a fraction of the card's
	 * shorter side. The source card images are opaque JPEGs whose rounded corners are baked
	 * against white (no alpha channel), so on the board they'd otherwise show sharp white corners.
	 * Clipping each card to a rounded silhouette drops those corners to transparent — the board
	 * shows through instead — and gives every card a consistent rounded-card look. Tune to taste.
	 */
	public static final double CORNER_RADIUS_FRACTION = 0.05;

	/**
	 * The reduction from which {@link #toARGB} area-averages instead of taking one bilinear step.
	 * A bilinear step reads only a 2x2 neighbourhood. At 2-3x (1080p, 1440p) the hard edges it
	 * leaves read crisper than an averaged image. At 4x and beyond (720p) it skips most of the
	 * source, and a card's name and text break up.
	 */
	private static final double AREA_AVERAGE_FROM = 4.0;

	/** Converts any {@link Image} to a scaled {@link BufferedImage} (ARGB). */
	public static BufferedImage toARGB(Image src, int w, int h) {
		if (src.getWidth(null) >= w * AREA_AVERAGE_FROM)
			src = new ImageIcon(src.getScaledInstance(w, h, Image.SCALE_SMOOTH)).getImage();   // waits for the load
		BufferedImage buf = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = buf.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		// Paint an antialiased rounded-rectangle mask, then keep only the image inside it (SrcIn),
		// so the square (white) corners fall outside the silhouette and become transparent.
		double diameter = Math.min(w, h) * CORNER_RADIUS_FRACTION * 2.0;
		g.setColor(Color.WHITE);
		g.fill(new RoundRectangle2D.Double(0, 0, w, h, diameter, diameter));
		g.setComposite(AlphaComposite.SrcIn);
		g.drawImage(src, 0, 0, w, h, null);
		g.dispose();
		return buf;
	}

	/** Rotates a {@link BufferedImage} 90° clockwise. Result dimensions are {@code h × w}. */
	static BufferedImage rotateCW90(BufferedImage src) {
		int w = src.getWidth(), h = src.getHeight();
		BufferedImage dst = new BufferedImage(h, w, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = dst.createGraphics();
		g.translate(h, 0);
		g.rotate(Math.PI / 2);
		g.drawImage(src, 0, 0, null);
		g.dispose();
		return dst;
	}

	/** Applies a blue tint to a {@link BufferedImage} (darkens R/G, boosts B). */
	static BufferedImage applyBlueTint(BufferedImage src) {
		float[] scales  = { 0.4f, 0.4f, 1.0f, 1.0f };
		float[] offsets = { 0f,   0f,   60f,  0f   };
		return new RescaleOp(scales, offsets, null).filter(src, null);
	}
}
