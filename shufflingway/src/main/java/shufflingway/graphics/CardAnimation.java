package shufflingway.graphics;

import shufflingway.CardState;
import shufflingway.FontLoader;
import shufflingway.UiScale;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.RescaleOp;

/** Static utilities for rendering and transforming card images. */
public class CardAnimation {

	public static int CARD_W = UiScale.scale(140);
	public static int CARD_H = UiScale.scale(205);

	private CardAnimation() {}

	public static BufferedImage renderBackupCardAtAngle(BufferedImage card, double angle) {
		BufferedImage canvas = new BufferedImage(CARD_H, CARD_H, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = canvas.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
		g.translate(CARD_H / 2.0, CARD_H / 2.0);
		g.rotate(angle);
		g.translate(-CARD_W / 2.0, -CARD_H / 2.0);
		g.drawImage(card, 0, 0, null);
		g.dispose();
		return canvas;
	}

	/**
	 * Composites a (possibly transformed) card image onto a square
	 * {@code CARD_H × CARD_H} canvas, respecting the slot alignment rules:
	 * <ul>
	 *   <li>Active - upright card pinned to the left edge, top</li>
	 *   <li>Dull — card rotated 90° CW ({@code CARD_H × CARD_W}), pinned left + bottom</li>
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
		BufferedImage canvas = new BufferedImage(CARD_H, CARD_H, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = canvas.createGraphics();
		if (frozen) card = applyBlueTint(card);
		switch (state) {
			case CardState.DULL -> {
				BufferedImage rotated = rotateCW90(card);          // now CARD_H × CARD_W
				g.drawImage(rotated, 0, CARD_H - CARD_W, null);   // pinned to bottom-left
			}
			default -> g.drawImage(card, 0, 0, null);             // pinned to top-left
		}

		// Card bounds within the square canvas depend on orientation
		boolean dull = (state == CardState.DULL);
		int cx = 0,  cy = dull ? CARD_H - CARD_W : 0;
		int cw = dull ? CARD_H : CARD_W;
		int ch = dull ? CARD_W : CARD_H;

		if (selected) {
			drawGlow(g, new Color(255, 165, 0), cx, cy, cw, ch);
		} else if (highlight) {
			drawGlow(g, new Color(0, 220, 0), cx, cy, cw, ch);
		}
		g.dispose();
		return canvas;
	}

	/**
	 * Draws a multi-layer inner glow on {@code g} within the rectangle
	 * {@code (cx, cy, cw, ch)}.  Layers fade inward with a quadratic falloff,
	 * matching the PhaseTracker halo style.
	 */
	public static void drawGlow(Graphics2D g, Color color, int cx, int cy, int cw, int ch) {
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		int layers = 10;
		for (int layer = layers; layer >= 0; layer--) {
			float t   = (float) layer / layers;      // 1.0 at edge → 0.0 innermost
			int alpha = Math.round(t * t * 185);     // quadratic falloff, max ~185/255
			g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha));
			g.setStroke(new BasicStroke(1.5f));
			int off = layers - layer;
			g.drawRect(cx + off, cy + off, cw - 1 - 2 * off, ch - 1 - 2 * off);
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
		renderPill(canvas, value, textColor, true, state);
	}

	/**
	 * Renders a pill at the card's bottom-left ({@code !alignRight}) or bottom-right ({@code alignRight}) corner,
	 * in the card's natural (unrotated) coordinate frame. When {@code state == DULL} the pill is drawn through
	 * a CW 90° transform so it stays anchored to the card's bottom edge — which lives on the LEFT side of the
	 * dull canvas — and the text reads correctly once the viewer rotates their view to read the dull card.
	 */
	private static void renderPill(BufferedImage canvas, int value, Color textColor, boolean alignRight, CardState state) {
		Graphics2D g = canvas.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		if (state == CardState.DULL) {
			// CW 90° about (0,0) followed by translate so that drawing in (0..CARD_W, 0..CARD_H) lands on
			// the rotated card's natural face: original (0,0) → canvas (CARD_H, CARD_H - CARD_W);
			// original (CARD_W, CARD_H) → canvas (0, CARD_H).
			g.translate(CARD_H, CARD_H - CARD_W);
			g.rotate(Math.PI / 2);
		}
		String text = String.valueOf(value);
		Font font = FontLoader.loadPixelNESFont(14);
		g.setFont(font);
		FontMetrics fm = g.getFontMetrics();
		int tw = fm.stringWidth(text);
		int tx = alignRight ? CARD_W - tw - 8 : 4;
		int ty = CARD_H - 8;
		g.setColor(new Color(0, 0, 0, 180));
		g.fillRoundRect(tx - 4, ty - fm.getAscent() - 1, tw + 8, fm.getAscent() + fm.getDescent() + 2, 5, 5);
		g.setColor(textColor);
		g.drawString(text, tx, ty);
		g.dispose();
	}

	/** Draws accumulated damage in a red pill at the card's bottom-left (rotated with the card when dull). */
	public static void renderDamageOverlay(BufferedImage canvas, int damage, CardState state) {
		renderPill(canvas, damage, new Color(255, 50, 50), false, state);
	}

	/** Blits a counter orb onto the card's top-left corner with the total counter count overlaid. */
	public static void renderCounterOverlay(BufferedImage canvas, int totalCount, CardState state, String hexColor) {
		int orbW = Math.max(16, CARD_W / 5);
		int orbH = (int) Math.round(orbW * 56.0 / 64.0);
		BufferedImage orb = Counter.render(orbW, orbH, hexColor);
		Graphics2D g = canvas.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		if (state == CardState.DULL) {
			g.translate(CARD_H, CARD_H - CARD_W);
			g.rotate(Math.PI / 2);
		}
		int x = (CARD_W - orbW) / 2, y = (CARD_H - orbH) / 2;
		g.drawImage(orb, x, y, null);
		String text = String.valueOf(totalCount);
		Font font = FontLoader.loadPixelNESFont(10);
		g.setFont(font);
		FontMetrics fm = g.getFontMetrics();
		int tx = x + (orbW - fm.stringWidth(text)) / 2;
		int ty = y + (orbH + fm.getAscent()) / 2 - fm.getDescent();
		g.setColor(new Color(0, 0, 0, 230));
		for (int dx = 1; dx <= 2; dx++)
			for (int dy = 1; dy <= 2; dy++)
				g.drawString(text, tx + dx, ty + dy);
		g.setColor(Color.WHITE);
		g.drawString(text, tx, ty);
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
			// rotated 90° CW: CARD_H wide × CARD_W tall, pinned bottom-left
			cx = 0; cy = CARD_H - CARD_W; rw = CARD_H; rh = CARD_W;
		} else {
			cx = 0; cy = 0; rw = CARD_W; rh = CARD_H;
		}

		g.setColor(new Color(30, 30, 30, 200));
		g.fillRoundRect(cx + 2, cy + 2, rw - 4, rh - 4, 10, 10);
		g.setColor(new Color(160, 160, 160));
		g.setStroke(new BasicStroke(2f));
		g.drawRoundRect(cx + 2, cy + 2, rw - 4, rh - 4, 10, 10);

		String text = "Loading...";
		Font font = FontLoader.loadPixelNESFont(11);
		g.setFont(font);
		FontMetrics fm = g.getFontMetrics();
		int tx = cx + (rw - fm.stringWidth(text)) / 2;
		int ty = cy + (rh + fm.getAscent()) / 2 - fm.getDescent();
		g.setColor(new Color(180, 180, 180));
		g.drawString(text, tx, ty);
		g.dispose();
		return canvas;
	}

	/** Converts any {@link Image} to a scaled {@link BufferedImage} (ARGB). */
	public static BufferedImage toARGB(Image src, int w, int h) {
		BufferedImage buf = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = buf.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
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
