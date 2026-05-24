package shufflingway;

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
class CardAnimation {

	static final int CARD_W = 140;
	static final int CARD_H = 205;

	private CardAnimation() {}

	static BufferedImage renderBackupCardAtAngle(BufferedImage card, double angle) {
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
	static BufferedImage renderBackupCard(BufferedImage card, CardState state) {
		return renderBackupCard(card, state, false);
	}

	static BufferedImage renderBackupCard(BufferedImage card, CardState state, boolean highlight) {
		return renderBackupCard(card, state, highlight, false);
	}

	static BufferedImage renderBackupCard(BufferedImage card, CardState state, boolean highlight, boolean selected) {
		return renderBackupCard(card, state, highlight, selected, false);
	}

	static BufferedImage renderBackupCard(BufferedImage card, CardState state, boolean highlight, boolean selected, boolean frozen) {
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
	static void drawGlow(Graphics2D g, Color color, int cx, int cy, int cw, int ch) {
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
	static javax.swing.border.Border createCardGlowBorder(Color color) {
		return new javax.swing.border.AbstractBorder() {
			@Override public void paintBorder(java.awt.Component c, java.awt.Graphics g0,
					int x, int y, int w, int h) {
				drawGlow((Graphics2D) g0, color, x, y, w, h);
			}
		};
	}

	/** Draws {@code value} in a dark pill in the bottom-right of {@code canvas} using {@code textColor}. */
	static void renderPowerOverlayRight(BufferedImage canvas, int value, Color textColor, CardState state) {
		int rightEdge = (state == CardState.DULL) ? canvas.getWidth() : CARD_W;
		renderPill(canvas, value, textColor, true, rightEdge);
	}

	private static void renderPill(BufferedImage canvas, int value, Color textColor, boolean alignRight) {
		renderPill(canvas, value, textColor, alignRight, canvas.getWidth());
	}

	private static void renderPill(BufferedImage canvas, int value, Color textColor, boolean alignRight, int rightEdge) {
		Graphics2D g = canvas.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		String text = String.valueOf(value);
		Font font = FontLoader.loadPixelNESFont(13);
		g.setFont(font);
		FontMetrics fm = g.getFontMetrics();
		int tw = fm.stringWidth(text);
		int tx = alignRight ? rightEdge - tw - 8 : 4;
		int ty = canvas.getHeight() - 5;
		g.setColor(new Color(0, 0, 0, 180));
		g.fillRoundRect(tx - 4, ty - fm.getAscent() - 1, tw + 8, fm.getAscent() + fm.getDescent() + 2, 5, 5);
		g.setColor(textColor);
		g.drawString(text, tx, ty);
		g.dispose();
	}

	/** Draws accumulated damage in a red pill at the bottom-left. */
	static void renderDamageOverlay(BufferedImage canvas, int damage) {
		renderPill(canvas, damage, new Color(255, 50, 50), false);
	}

	/** Returns a {@code CARD_H × CARD_H} placeholder canvas with a card outline and "Loading…" text. */
	static BufferedImage renderPlaceholder(CardState state) {
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
	static BufferedImage toARGB(Image src, int w, int h) {
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
