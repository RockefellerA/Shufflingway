package shufflingway.graphics;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;

import shufflingway.CardState;
import shufflingway.FontLoader;
import static shufflingway.graphics.CardAnimation.CARD_H;
import static shufflingway.graphics.CardAnimation.CARD_W;
import static shufflingway.graphics.CardAnimation.LEFT_GUTTER;

/**
 * Round buttons along a field card's right edge, one per action ability the player can reach on
 * it plus one for Priming — the clickable counterpart of {@link TraitTab}, which sits on the left
 * edge. A button glows blue while its ability could be used right now, and is drawn pushed in
 * while the pointer holds it down.
 *
 * <p>They live in {@link CardAnimation#rightGutter()}, the strip the slot canvas keeps along the
 * card's right edge. Like the tabs, that edge lands in a different place depending on the card's
 * state:
 * <ul>
 *   <li>{@link CardState#ACTIVE} — the strip right of the art; buttons stack downwards.</li>
 *   <li>{@link CardState#DULL} — a CW rotation sends the right edge to the bottom, so the strip
 *       below the art; buttons stack leftwards, mirrored so each keeps its spot on the card.</li>
 * </ul>
 *
 * <p>Unlike the tabs, buttons are not baked into the slot's rendered icon. The icon is rebuilt
 * off the event thread after each change, which is far too slow for a press to show; the slot
 * label paints them on top of its icon instead, from {@link #paint}.
 *
 * <p>Geometry is authored against a 140px-wide card and scaled by the live {@code CARD_W}.
 */
public final class ActionButton {

	private ActionButton() {}

	/** What a button does when pressed. */
	public enum Kind { ABILITY, PRIME }

	/**
	 * The cost symbol drawn on a button's face — one per button. When a cost carries more than
	 * one of these, the first in this order is shown; the tooltip names the whole cost.
	 */
	public enum Glyph {
		/** A Special ability (an 《S》 cost): a red S outlined in white, legible on a Fire face too. */
		SPECIAL,
		/** A 《Dull》 cost: an arrow running right and turning down. */
		DULL,
		/** A discard cost: a white arrow pointing right. */
		DISCARD,
		/** None of the above: the face carries the Spec's label, if it has one. */
		NONE
	}

	/**
	 * One button: what it is, how its face reads, whether it may be pressed right now, and its
	 * tooltip.
	 *
	 * <p>The face shows the ability's cost. Its colour is the cost's Elements — a single Element
	 * fills it, two split it in half, three into thirds — and neutral grey when the cost names no
	 * Element. On top sits the cost's {@link Glyph}, or, when it has none, {@code label} (the
	 * generic CP amount, as the card prints it in its cost circle), or nothing.
	 *
	 * @param colors the cost's Elements in printed order, each once; empty for a neutral face
	 * @param glyph  the cost symbol on the face
	 * @param label  short text for a face with no glyph, or {@code null}
	 */
	public record Spec(Kind kind, List<Color> colors, Glyph glyph, String label,
	                   boolean usable, String tooltip) {

		public Spec {
			colors = List.copyOf(colors);
		}

		/** The Priming button: neutral, carrying the Priming glyph. */
		public static Spec prime(boolean usable, String tooltip) {
			return new Spec(Kind.PRIME, List.of(), Glyph.NONE, null, usable, tooltip);
		}
	}

	/** Card width the geometry below was authored against; everything scales off it. */
	private static final int DESIGN_CARD_W = 140;

	private static final float DIAMETER  = 26f;
	private static final float GAP       = 10f;   // between stacked buttons
	private static final float CLEARANCE = 3f;    // between the card edge and a button
	private static final float MARGIN    = 4f;    // kept clear at each end of the stack
	private static final float BEZEL     = 2.6f;

	/** The fill of the Special glyph's S. */
	private static final Color SPECIAL_RED = new Color(0xd8, 0x1e, 0x1e);

	/** The glow on a button that can be pressed. */
	public static final Color GLOW = new Color(60, 150, 255);

	private static final Color FACE        = new Color(0x40, 0x40, 0x46);
	private static final Color FACE_SHADE  = new Color(0x30, 0x30, 0x35);
	private static final Color FACE_HOVER  = new Color(0x4c, 0x4c, 0x54);
	private static final Color BEZEL_LITE  = new Color(0xc4, 0xc4, 0xca);
	private static final Color BEZEL_DARK  = new Color(0x4e, 0x4e, 0x55);
	private static final Color BEZEL_LITE_OFF = new Color(0x80, 0x80, 0x84);
	private static final Color BEZEL_DARK_OFF = new Color(0x3a, 0x3a, 0x3e);

	private static float scale() {
		return CARD_W / (float) DESIGN_CARD_W;
	}

	/**
	 * Where each of {@code count} buttons sits on the {@code CARD_H x CARD_H} slot canvas, in
	 * order. The stack is centred on the card edge; one too long to fit at the usual spacing is
	 * squeezed to the length of the edge.
	 *
	 * <p>Shared by {@link #paint} and {@link #indexAt} so a button's hit area cannot drift away
	 * from where it was drawn.
	 */
	public static List<Ellipse2D.Float> layout(CardState state, int count) {
		if (count <= 0) return List.of();
		float s     = scale();
		float d     = DIAMETER * s;
		float step  = d + GAP * s;
		float span  = CARD_H - 2 * MARGIN * s;
		if (count > 1 && count * d + (count - 1) * GAP * s > span) step = (span - d) / (count - 1);
		float lead  = (CARD_H - (d + (count - 1) * step)) / 2f;
		// Across the edge: just clear of the art, in the strip along the card's right edge.
		float across = LEFT_GUTTER + CARD_W + CLEARANCE * s;
		boolean dull = state == CardState.DULL;

		List<Ellipse2D.Float> out = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			float along = lead + i * step;
			// The rotation reverses the along-edge axis, so mirror the dull stack.
			float x = dull ? CARD_H - along - d : across;
			float y = dull ? across : along;
			out.add(new Ellipse2D.Float(x, y, d, d));
		}
		return out;
	}

	/** The index of the button covering canvas point {@code (x, y)}, or -1 when none does. */
	public static int indexAt(CardState state, int count, double x, double y) {
		List<Ellipse2D.Float> circles = layout(state, count);
		for (int i = 0; i < circles.size(); i++)
			if (circles.get(i).contains(x, y)) return i;
		return -1;
	}

	/**
	 * Paints {@code buttons} onto {@code g}, whose origin is the slot canvas's top-left corner.
	 *
	 * @param pressed the index of the button held down, or -1
	 * @param hover   the index of the button under the pointer, or -1
	 */
	public static void paint(Graphics2D g0, CardState state, List<Spec> buttons, int pressed, int hover) {
		List<Ellipse2D.Float> circles = layout(state, buttons.size());
		Graphics2D g = (Graphics2D) g0.create();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		for (int i = 0; i < circles.size(); i++) {
			Spec spec = buttons.get(i);
			Ellipse2D.Float c = circles.get(i);
			drawButton(g, spec, (float) c.getCenterX(), (float) c.getCenterY(), c.width / 2f,
					spec.usable() && i == pressed, spec.usable() && i == hover);
		}
		g.dispose();
	}

	private static void drawButton(Graphics2D g, Spec spec, float cx, float cy, float r,
			boolean pressed, boolean hover) {
		float s   = scale();
		boolean on = spec.usable();

		if (on) {
			// Soft halo, brightest against the button. Dimmer while held down: a pushed-in button
			// sits lower than its own glow.
			int layers = 5;
			for (int k = layers; k >= 1; k--) {
				int alpha = (pressed ? 18 : 30) + (layers - k) * (pressed ? 14 : 22);
				g.setColor(new Color(GLOW.getRed(), GLOW.getGreen(), GLOW.getBlue(), alpha));
				g.setStroke(new BasicStroke(1.4f * s));
				g.draw(circle(cx, cy, r + k * 0.9f * s));
			}
		}
		if (!pressed) {
			// Drop shadow: raised off the board.
			g.setColor(new Color(0, 0, 0, 110));
			g.fill(circle(cx + 0.8f * s, cy + 1.6f * s, r));
		}
		// Bezel: lit from the top-left when raised, the reverse when pushed in.
		Color lite = on ? BEZEL_LITE : BEZEL_LITE_OFF;
		Color dark = on ? BEZEL_DARK : BEZEL_DARK_OFF;
		g.setPaint(new GradientPaint(cx - r, cy - r, pressed ? dark : lite, cx + r, cy + r, pressed ? lite : dark));
		g.fill(circle(cx, cy, r));
		// Face, inset from the bezel: the cost's Elements, or neutral grey.
		float fr = r - BEZEL * s;
		Ellipse2D.Float face = circle(cx, cy, fr);
		if (spec.colors().isEmpty()) {
			g.setPaint(new GradientPaint(cx, cy - fr, hover ? FACE_HOVER : FACE, cx, cy + fr, FACE_SHADE));
			g.fill(face);
		} else {
			fillWedges(g, spec.colors(), cx, cy, fr, s);
		}
		// One shading pass over whatever the face is: lit from above when raised, the other way up
		// when pushed in, brighter under the pointer, and darkened while the button cannot be used.
		Color shine = new Color(255, 255, 255, hover ? 85 : 55), shadow = new Color(0, 0, 0, 95);
		g.setPaint(new GradientPaint(cx, cy - fr, pressed ? shadow : shine, cx, cy + fr, pressed ? shine : shadow));
		g.fill(face);
		if (!on) {
			g.setColor(new Color(0x20, 0x20, 0x24, 165));
			g.fill(face);
		}
		g.setColor(new Color(0, 0, 0, 200));
		g.setStroke(new BasicStroke(0.8f * s));
		g.draw(circle(cx, cy, r));
		if (on) {
			g.setColor(new Color(GLOW.getRed(), GLOW.getGreen(), GLOW.getBlue(), 190));
			g.setStroke(new BasicStroke(1f * s));
			g.draw(face);
		}

		// Glyph: nudged down-right while pushed in, dimmed while unusable.
		float nudge = pressed ? 0.9f * s : 0f;
		float size  = fr * 2 * 0.80f;
		Graphics2D gg = (Graphics2D) g.create();
		if (!on) gg.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.45f));
		float gx = cx - size / 2 + nudge, gy = cy - size / 2 + nudge;
		if (spec.kind() == Kind.PRIME)  TraitTab.drawPrimingIcon(gg, gx, gy, size, false);
		else switch (spec.glyph()) {
			case SPECIAL -> drawSpecialIcon(gg, gx, gy, size);
			case DULL    -> drawDullIcon(gg, gx, gy, size);
			case DISCARD -> drawDiscardIcon(gg, gx, gy, size);
			case NONE    -> { if (spec.label() != null) drawLabel(gg, spec.label(), cx + nudge, cy + nudge, size); }
		}
		gg.dispose();
	}

	/**
	 * Fills the face with one wedge per Element, starting at the top and running anticlockwise,
	 * so two read left then right and three read left, bottom, right. Thin dark seams keep
	 * neighbouring colours apart.
	 */
	private static void fillWedges(Graphics2D g, List<Color> colors, float cx, float cy, float fr, float s) {
		int n = colors.size();
		float sweep = 360f / n;
		for (int i = 0; i < n; i++) {
			g.setColor(colors.get(i));
			g.fill(new Arc2D.Float(cx - fr, cy - fr, fr * 2, fr * 2, 90 + i * sweep, sweep, Arc2D.PIE));
		}
		if (n < 2) return;
		g.setColor(new Color(0, 0, 0, 150));
		g.setStroke(new BasicStroke(0.9f * s));
		for (int i = 0; i < n; i++) {
			double a = Math.toRadians(90 + i * sweep);
			g.draw(new Line2D.Float(cx, cy, cx + (float) (Math.cos(a) * fr), cy - (float) (Math.sin(a) * fr)));
		}
	}

	/**
	 * The Dull glyph — an arrow running right and turning down, the way a card turns sideways —
	 * into the box {@code [x, y, x + size, y + size]}. White on a dark outline so it reads on any
	 * Element's colour. Authored on the 24x24 grid {@link TraitTab}'s glyphs use.
	 */
	public static void drawDullIcon(Graphics2D g0, float x, float y, float size) {
		Graphics2D g = (Graphics2D) g0.create();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.translate(x, y);
		g.scale(size / 24f, size / 24f);

		Path2D.Float shaft = new Path2D.Float();
		shaft.moveTo(4, 7.5f);
		shaft.lineTo(12, 7.5f);
		shaft.quadTo(17.5f, 7.5f, 17.5f, 13f);
		shaft.lineTo(17.5f, 15f);
		Path2D.Float head = new Path2D.Float();
		head.moveTo(12.5f, 14.5f);
		head.lineTo(22.5f, 14.5f);
		head.lineTo(17.5f, 21f);
		head.closePath();

		g.setColor(new Color(0, 0, 0, 200));
		g.setStroke(new BasicStroke(5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.draw(shaft);
		g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.draw(head);
		g.setColor(Color.WHITE);
		g.setStroke(new BasicStroke(2.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.draw(shaft);
		g.fill(head);
		g.dispose();
	}

	/**
	 * The Special glyph — a bold red S with a white outline, so it stands off a Fire-red face as
	 * well as any other — into the box {@code [x, y, x + size, y + size]}. A thin dark rim outside
	 * the white keeps the outline itself visible on the pale faces (Ice, Water, Earth).
	 */
	public static void drawSpecialIcon(Graphics2D g0, float x, float y, float size) {
		Graphics2D g = (Graphics2D) g0.create();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		Font font = new Font(Font.SANS_SERIF, Font.BOLD, 100).deriveFont(size * 0.95f);
		Shape glyph = font.createGlyphVector(g.getFontRenderContext(), "S").getOutline();
		Rectangle2D b = glyph.getBounds2D();
		// Centre the letter's own outline in the box, not its font metrics: an S has no descender.
		g.translate(x + (size - b.getWidth()) / 2 - b.getX(), y + (size - b.getHeight()) / 2 - b.getY());

		float s = size / 24f;
		g.setStroke(new BasicStroke(4.6f * s, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.setColor(new Color(0, 0, 0, 150));
		g.draw(glyph);
		g.setStroke(new BasicStroke(3.2f * s, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.setColor(Color.WHITE);
		g.draw(glyph);
		g.setColor(SPECIAL_RED);
		g.fill(glyph);
		g.dispose();
	}

	/**
	 * The Discard glyph — a white arrow pointing right, outlined in black — into the box
	 * {@code [x, y, x + size, y + size]}. Authored on the 24x24 grid {@link TraitTab}'s glyphs use.
	 */
	public static void drawDiscardIcon(Graphics2D g0, float x, float y, float size) {
		Graphics2D g = (Graphics2D) g0.create();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.translate(x, y);
		g.scale(size / 24f, size / 24f);

		Path2D.Float arrow = new Path2D.Float();
		arrow.moveTo(3.5f, 9.5f);
		arrow.lineTo(12.5f, 9.5f);
		arrow.lineTo(12.5f, 5f);
		arrow.lineTo(20.5f, 12f);
		arrow.lineTo(12.5f, 19f);
		arrow.lineTo(12.5f, 14.5f);
		arrow.lineTo(3.5f, 14.5f);
		arrow.closePath();

		g.setColor(Color.WHITE);
		g.fill(arrow);
		g.setColor(Color.BLACK);
		g.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.draw(arrow);
		g.dispose();
	}

	/** Centred text on the face — the generic CP amount — white over a dark outline. */
	private static void drawLabel(Graphics2D g, String text, float cx, float cy, float size) {
		Font font = FontLoader.loadOverlayFont(12).deriveFont(size * 0.85f);
		g.setFont(font);
		FontMetrics fm = g.getFontMetrics();
		float tx = cx - fm.stringWidth(text) / 2f;
		float ty = cy + (fm.getAscent() - fm.getDescent()) / 2f;
		g.setColor(new Color(0, 0, 0, 200));
		for (int dx = -1; dx <= 1; dx++)
			for (int dy = -1; dy <= 1; dy++)
				if (dx != 0 || dy != 0) g.drawString(text, tx + dx, ty + dy);
		g.setColor(Color.WHITE);
		g.drawString(text, tx, ty);
	}

	private static Ellipse2D.Float circle(float cx, float cy, float r) {
		return new Ellipse2D.Float(cx - r, cy - r, r * 2, r * 2);
	}
}
