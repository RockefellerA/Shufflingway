package shufflingway.graphics;

import static shufflingway.graphics.CardAnimation.CARD_H;
import static shufflingway.graphics.CardAnimation.CARD_W;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import shufflingway.FontLoader;

/**
 * The counters on a card, drawn as the face of a die: one orb for each named counter, the orbs
 * laid out as the pips a die shows for that many, each carrying how many counters of its name the
 * card holds. A card with Poison ×2 and Shuriken ×3 shows the two-pip face, one orb reading 2 and
 * the other 3.
 *
 * <p>Orbs take the names in the order they were placed, reading the face top row first, left to
 * right. A face shows at most nine orbs: from {@link #OVERFLOW} names on it shows all nine, the
 * centre one marked "..." in place of a count and the other eight carrying the first eight names.
 * The slot's tooltip lists every counter by name either way.
 */
final class CounterDice {

	private CounterDice() {}

	/** The fewest named counters one face cannot give an orb apiece. */
	static final int OVERFLOW = 10;

	/** What the centre orb of an overflowing face carries in place of a count. */
	static final String MORE = "...";

	/** Centre-to-centre distance between neighbouring pips, in orb widths. */
	private static final double PIP_SPACING = 1.12;
	/** Orb height over width: {@link Counter}'s slightly oblong dome. */
	private static final double ORB_ASPECT  = 56.0 / 64.0;

	/**
	 * The pips for {@code count} orbs as {column, row} on a 3 × 3 grid, (0, 0) top left, each face
	 * in reading order: the faces of a die, with two and three running bottom left to top right.
	 * Index 0 is a blank face.
	 */
	private static final int[][][] FACES = {
		{},
		{ {1, 1} },
		{ {2, 0}, {0, 2} },
		{ {2, 0}, {1, 1}, {0, 2} },
		{ {0, 0}, {2, 0}, {0, 2}, {2, 2} },
		{ {0, 0}, {2, 0}, {1, 1}, {0, 2}, {2, 2} },
		{ {0, 0}, {2, 0}, {0, 1}, {2, 1}, {0, 2}, {2, 2} },
		{ {0, 0}, {2, 0}, {0, 1}, {1, 1}, {2, 1}, {0, 2}, {2, 2} },
		{ {0, 0}, {1, 0}, {2, 0}, {0, 1}, {2, 1}, {0, 2}, {1, 2}, {2, 2} },
		{ {0, 0}, {1, 0}, {2, 0}, {0, 1}, {1, 1}, {2, 1}, {0, 2}, {1, 2}, {2, 2} },
	};

	/** The pips a face of {@code count} orbs shows; all nine from {@link #OVERFLOW} up. Not to be modified. */
	static int[][] pips(int count) {
		return FACES[Math.max(0, Math.min(count, 9))];
	}

	/**
	 * How far out from the centre the two- and four-pip faces set their pips, as a share of the
	 * grid step: drawn in so that diagonal neighbours sit exactly as far apart as a five's corner
	 * from its centre, rather than corner to corner across the whole face.
	 */
	private static final double DRAWN_IN = 0.5;

	/**
	 * The centre of each pip of the face for {@code count} orbs, in {@link #pips} order, as
	 * {x, y} from the face's centre in grid steps (one step between neighbouring pips in a row).
	 */
	static double[][] offsets(int count) {
		int[][] at = pips(count);
		double spread = at.length == 2 || at.length == 4 ? DRAWN_IN : 1.0;
		double[][] out = new double[at.length][];
		for (int i = 0; i < at.length; i++)
			out[i] = new double[] { (at[i][0] - 1) * spread, (at[i][1] - 1) * spread };
		return out;
	}

	/**
	 * What each orb of the face for {@code piles} carries, pip by pip in {@link #pips} order: the
	 * count of each named counter in turn, or for an overflowing face {@link #MORE} on the centre
	 * orb and the first eight counts around it.
	 */
	static List<String> labels(List<Integer> piles) {
		List<String> out = new ArrayList<>();
		if (piles.size() < OVERFLOW) {
			for (int count : piles) out.add(String.valueOf(count));
			return out;
		}
		int next = 0;
		for (int[] pip : pips(9))
			out.add(pip[0] == 1 && pip[1] == 1 ? MORE : String.valueOf(piles.get(next++)));
		return out;
	}

	/**
	 * Draws the face for {@code piles} — how many counters of each name, in the order they were
	 * placed — centred on the card, onto {@code g} set up so the upright card spans (0, 0) to
	 * (CARD_W, CARD_H).
	 */
	static void draw(Graphics2D g, List<Integer> piles, String hex) {
		if (piles.isEmpty()) return;
		int    orbW = Math.max(16, CARD_W / 5);
		int    orbH = (int) Math.round(orbW * ORB_ASPECT);
		double step = orbW * PIP_SPACING;
		BufferedImage orb = Counter.render(orbW, orbH, hex);
		double[][]   at     = offsets(piles.size());
		List<String> labels = labels(piles);
		Font font = FontLoader.loadOverlayFont(10);
		for (int i = 0; i < at.length; i++) {
			double cx = CARD_W / 2.0 + at[i][0] * step;
			double cy = CARD_H / 2.0 + at[i][1] * step;
			g.drawImage(orb, (int) Math.round(cx - orbW / 2.0), (int) Math.round(cy - orbH / 2.0), null);
			if (MORE.equals(labels.get(i))) drawEllipsis(g, cx, cy, orbW);
			else drawCount(g, labels.get(i), cx, cy, font);
		}
	}

	/** White digits with a dark drop shadow, centred on ({@code cx}, {@code cy}). */
	private static void drawCount(Graphics2D g, String text, double cx, double cy, Font font) {
		g.setFont(font);
		FontMetrics fm = g.getFontMetrics();
		int tx = (int) Math.round(cx - fm.stringWidth(text) / 2.0);
		int ty = (int) Math.round(cy + (fm.getAscent() - fm.getDescent()) / 2.0);
		g.setColor(new Color(0, 0, 0, 230));
		for (int dx = 1; dx <= 2; dx++)
			for (int dy = 1; dy <= 2; dy++)
				g.drawString(text, tx + dx, ty + dy);
		g.setColor(Color.WHITE);
		g.drawString(text, tx, ty);
	}

	/**
	 * {@link #MORE} across an orb as three dots, drawn rather than set in type, since the font's
	 * full stops are a pixel each at this size.
	 */
	private static void drawEllipsis(Graphics2D g, double cx, double cy, int orbW) {
		double r = Math.max(1.2, orbW * 0.075), spacing = orbW * 0.24;
		for (int i = -1; i <= 1; i++) {
			double x = cx + i * spacing;
			g.setColor(new Color(0, 0, 0, 200));
			g.fill(new Ellipse2D.Double(x - r - 0.8, cy - r - 0.8, 2 * r + 1.6, 2 * r + 1.6));
			g.setColor(Color.WHITE);
			g.fill(new Ellipse2D.Double(x - r, cy - r, 2 * r, 2 * r));
		}
	}
}
