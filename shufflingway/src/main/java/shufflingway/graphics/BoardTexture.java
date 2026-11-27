package shufflingway.graphics;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.LinearGradientPaint;
import java.awt.TexturePaint;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;

import shufflingway.UiScale;

/**
 * A surface laid over a player's zone colour, chosen per side in Preferences beside the colour.
 *
 * <p>A texture is a translucent overlay of light and dark, not a picture, so it takes on whatever
 * colour the zone already has: Sand over Earth is a beach, over Dark a black-sand one; Steel over
 * Light is bare steel, over Fire anodised red; Wood over Earth is pine, over Fire cherry, over Dark
 * ebony. It is generated rather than loaded: one seamless tile per texture and variant, built the
 * first time it is painted at a given scale and tiled from there.
 *
 * <p>Each texture comes in variants, one per seat, grown from different seeds, so the two fields
 * are not copies of each other: the same ripples or the same planks facing each other across the
 * board would give the pattern away.
 *
 * <p>Sizes are in design pixels (the 1920x1080 grid {@link UiScale} scales from), and the tile is
 * built at device resolution, so a grain is the same size on the board at every window size and
 * stays sharp on a scaled display. Every noise lattice that runs across the tile divides it, which
 * is what makes it wrap without a seam.
 *
 * <p>The cached tiles are touched only from painting, so only from the event thread.
 */
public enum BoardTexture {

	NONE("None"),
	SAND("Sand"),
	STEEL("Steel"),
	WOOD("Wood");

	private final String label;

	/** The tiles built so far, by variant. */
	private final Map<Integer, Tile> tiles = new HashMap<>();

	/** A built tile, and the device pixels per design pixel it was built for. */
	private record Tile(BufferedImage image, double unit) {}

	BoardTexture(String label) {
		this.label = label;
	}

	/** The name shown in Preferences. */
	public String label() { return label; }

	/** {@link #label}, so a combo box lists the textures by name. */
	@Override
	public String toString() { return label; }

	/** The name kept in settings; {@link #parse} reads it back. */
	public String id() { return name(); }

	/** The texture {@code id} names, or {@link #NONE} for none or one this build does not know. */
	public static BoardTexture parse(String id) {
		if (id == null) return NONE;
		try {
			return valueOf(id.strip().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			return NONE;
		}
	}

	// =============================================================================================
	// Painting
	// =============================================================================================

	/**
	 * Sand and steel's tile, in design pixels. Twice as wide as it is tall, as a zone is far wider
	 * than tall: a sand ripple's bends would otherwise be seen to repeat across it.
	 */
	static final int PERIOD_W = 768, PERIOD_H = 384;

	/**
	 * Wood's tile, in design pixels: about a whole zone, so no board is seen twice in a field. A
	 * board or a knot seen twice gives wood away far sooner than a repeat gives away sand or steel.
	 * Five planks tall, as a zone is (481 design pixels at 1080p), so {@link #MAX_KNOTS} counts the
	 * knots a field shows.
	 */
	static final int FIELD_W = 1728, FIELD_H = 480;

	/** This texture's tile width in design pixels. */
	int periodW() { return this == WOOD ? FIELD_W : PERIOD_W; }

	/** This texture's tile height in design pixels. */
	int periodH() { return this == WOOD ? FIELD_H : PERIOD_H; }

	/**
	 * Lays this texture over {@code (0, 0, w, h)} of {@code g}, which already holds the zone's
	 * colour. Anchored at {@code g}'s origin. {@code variant} picks which of the texture's seeds to
	 * grow it from. Does nothing for {@link #NONE}.
	 */
	public void paint(Graphics2D g, int w, int h, int variant) {
		if (this == NONE || w <= 0 || h <= 0) return;
		double device = Math.max(0.25, g.getTransform().getScaleX());
		BufferedImage t = tile(device * UiScale.factor, variant);

		Graphics2D g2 = (Graphics2D) g.create();
		g2.setPaint(new TexturePaint(t,
				new Rectangle2D.Double(0, 0, t.getWidth() / device, t.getHeight() / device)));
		g2.fillRect(0, 0, w, h);
		if (this == STEEL) paintSheen(g2, w, h);
		g2.dispose();
	}

	/**
	 * Brushed metal catches the light in broad soft bands across its grain. They span the whole
	 * zone rather than repeating with the tile, which would give the repeat away.
	 */
	private static void paintSheen(Graphics2D g, int w, int h) {
		Color light = new Color(255, 255, 255), dark = new Color(0, 0, 0);
		float[] at = {0f, 0.16f, 0.34f, 0.55f, 0.74f, 0.9f, 1f};
		Color[] tone = {
				alpha(dark, 0.10), alpha(light, 0.10), alpha(dark, 0.06), alpha(light, 0.16),
				alpha(dark, 0.08), alpha(light, 0.06), alpha(dark, 0.10)};
		g.setPaint(new LinearGradientPaint(0, 0, w, h * 0.35f, at, tone));
		g.fillRect(0, 0, w, h);
	}

	private static Color alpha(Color c, double a) {
		return new Color(c.getRed(), c.getGreen(), c.getBlue(), (int) Math.round(a * 255));
	}

	/** This texture's tile for {@code variant} at {@code unit} device pixels per design pixel. */
	private BufferedImage tile(double unit, int variant) {
		Tile t = tiles.get(variant);
		if (t == null || Math.abs(t.unit() - unit) > 1e-6) {
			t = new Tile(build(unit, variant), unit);
			tiles.put(variant, t);
		}
		return t.image();
	}

	/** How strong the overlay is at full light and at full dark, as alpha. */
	private double maxLight() {
		return switch (this) {
			case SAND -> 0.30;
			case WOOD -> 0.24;
			default   -> 0.22;
		};
	}

	private double maxDark() {
		return switch (this) {
			case SAND -> 0.34;
			case WOOD -> 0.42;
			default   -> 0.26;
		};
	}

	/**
	 * This texture's shade at a point in design pixels, from -1 (darkest) to 1 (lightest), grown
	 * from {@code variant}'s seeds.
	 */
	double shade(double u, double v, int variant) {
		int seed = 1000 * variant;
		return switch (this) {
			case SAND  -> sand(u, v, seed);
			case STEEL -> steel(u, v, seed);
			case WOOD  -> wood(u, v, seed);
			case NONE  -> 0;
		};
	}

	/** The tile, its rows shaded in parallel: wood's runs to millions of pixels. */
	private BufferedImage build(double unit, int variant) {
		int pw = periodW(), ph = periodH();
		int tw = Math.max(16, (int) Math.round(pw * unit));
		int th = Math.max(8, (int) Math.round(ph * unit));
		double stepU = (double) pw / tw, stepV = (double) ph / th;
		double light = maxLight(), dark = maxDark();
		int[] argb = new int[tw * th];
		IntStream.range(0, th).parallel().forEach(y -> {
			double v = (y + 0.5) * stepV;
			for (int x = 0; x < tw; x++) {
				double s = Math.max(-1, Math.min(1, shade((x + 0.5) * stepU, v, variant)));
				argb[y * tw + x] = s >= 0
						? ((int) Math.round(s * light * 255) << 24) | 0xFFFFFF
						: ((int) Math.round(-s * dark * 255) << 24);
			}
		});
		BufferedImage img = new BufferedImage(tw, th, BufferedImage.TYPE_INT_ARGB);
		img.setRGB(0, 0, tw, th, argb, 0, tw);
		return img;
	}

	// =============================================================================================
	// Sand and steel, as a shade from -1 (darkest) to 1 (lightest) at a point in design pixels
	// =============================================================================================

	/**
	 * Sand: fine grain with the odd glinting or dark grain in it, over wind ripples that meander and
	 * fade in and out, over a slow mottle. A ripple is lit along its long windward slope and shadowed
	 * on its short steep lee, which is what makes it read as a ridge rather than a stripe.
	 */
	private static double sand(double u, double v, int seed) {
		double grain = 0.65 * noise(u, v, 1, 1, 1 + seed) + 0.35 * noise(u, v, 2, 2, 2 + seed);

		double speck = 0;
		double h = hash01(Math.floorMod((int) Math.floor(u), PERIOD_W), Math.floorMod((int) Math.floor(v), PERIOD_H), 3 + seed);
		if (h > 0.985) speck = 1;
		else if (h < 0.012) speck = -1;

		double warp = 1.0 * noise(u, v, 96, 96, 4 + seed) + 0.45 * noise(u, v, 48, 48, 5 + seed);
		double phase = v / 24.0 + warp;
		double t = phase - Math.floor(phase);
		double crest = 0.74;
		double ripple = t < crest
				? 0.55 * Math.pow(t / crest, 2)
				: -0.9 * Math.pow(1 - (t - crest) / (1 - crest), 1.5);
		double strength = 0.55 + 0.45 * noise(u, v, 128, 128, 6 + seed);

		double mottle = 0.6 * noise(u, v, 64, 64, 7 + seed) + 0.4 * noise(u, v, 32, 32, 8 + seed);

		return 0.45 * grain + 0.55 * ripple * strength + 0.20 * mottle + 0.6 * speck;
	}

	/**
	 * Brushed steel: streaks drawn out along the grain, from broad bands a design pixel tall down to
	 * hairlines a quarter of one, with a little grain in them. The sheen is painted over the whole
	 * zone apart from the tile (see {@link #paintSheen}).
	 */
	private static double steel(double u, double v, int seed) {
		double streak = 0.45 * noise(u, v, 192, 1, 11 + seed)
				+ 0.25 * noise(u, v, 48, 0.5, 12 + seed)
				+ 0.30 * noise(u, v, 8, 0.25, 13 + seed);
		double grain = noise(u, v, 1, 1, 14 + seed);
		return 1.15 * streak + 0.15 * grain;
	}

	// =============================================================================================
	// Wood
	// =============================================================================================

	/** How tall a plank of {@link #wood} is, in design pixels. It divides {@link #FIELD_H}. */
	static final int PLANK_H = 96;

	/**
	 * Wood: rows of planks, each row two or three boards of uneven length laid end to end, and every
	 * board its own cut from its own tree. Nothing in one board carries into the next: its rings,
	 * streaks, fibres and knots are all grown from its own seed, in its own coordinates. A dark seam
	 * with a lit edge parts it from its neighbours.
	 *
	 * <p>A board is either rift-sawn, the tree's heart well off its face, so the growth rings cross
	 * it as near-parallel lines; or flat-sawn, the heart running inside it, the cut drifting deeper
	 * from the heart along its length, so each ring closes in an arch pointing toward the deep end,
	 * nested one inside the next. Each ring darkens softly into a band of latewood. Boards differ in
	 * how fast their tree grew (the ring spacing), how far their grain runs off the edge, how wavy
	 * it is, their tone and their contrast, and carry up to two knots, the grain swirling round each,
	 * with no more than {@link #MAX_KNOTS} to a field.
	 */
	private static double wood(double u, double v, int seed) {
		Planks planks = PLANKS.computeIfAbsent(seed, BoardTexture::layPlanks);
		int row = Math.floorMod((int) Math.floor(v / PLANK_H), FIELD_H / PLANK_H);
		double y = v - Math.floor(v / PLANK_H) * PLANK_H;

		// Find the board: the row's joints start anywhere along it, and wrap with the tile.
		double along = u - planks.starts()[row];
		along -= FIELD_W * Math.floor(along / FIELD_W);
		Board[] boards = planks.rows()[row];
		Board board = boards[boards.length - 1];
		for (Board b : boards) {
			if (along < b.begin() + b.length()) { board = b; break; }
		}
		double s = along - board.begin(), length = board.length();
		int id = board.id();
		int bs = seed + 100 * id;   // the board's own seeds

		// Seams with the boards beside and above, and the end joint with the next along.
		if (y < 1 || s < 0.75) return -0.85;
		double edge = y < 2.5 || s < 2 ? 0.4 : y > PLANK_H - 1.5 || s > length - 1.5 ? -0.35 : 0;

		double tone     = (hash01(id, 0, 50 + seed) - 0.5) * 0.45;
		double contrast = 0.7 + 0.6 * hash01(id, 1, 51 + seed);
		double spacing  = 3.0 + 4.0 * hash01(id, 2, 52 + seed);
		double runout   = (hash01(id, 3, 53 + seed) - 0.5) * 0.05;
		double wave     = 0.8 + 3.2 * hash01(id, 4, 54 + seed);
		boolean rift    = hash01(id, 5, 55 + seed) < 0.35;

		double heartY, depth;
		if (rift) {
			double side = hash01(id, 6, 56 + seed) < 0.5 ? -1 : 1;
			heartY = PLANK_H / 2.0 + side * (110 + 120 * hash01(id, 7, 57 + seed));
			depth = 8;
		} else {
			heartY = PLANK_H * (0.25 + 0.5 * hash01(id, 6, 56 + seed));
			double tip   = length * (0.1 + 0.6 * hash01(id, 7, 57 + seed));
			double dir   = hash01(id, 8, 58 + seed) < 0.5 ? -1 : 1;
			double slope = 0.05 + 0.08 * hash01(id, 9, 59 + seed);
			// How much deeper the cut runs past the tip: none behind it, then growing steadily, and
			// levelling off once the arches are done, leaving broad flat grain past them.
			double x = dir * (s - tip) / 30;
			double past = 30 * (x > 20 ? x : Math.log1p(Math.exp(x)));
			depth = 3 + 60 * Math.tanh(slope * past / 60);
		}
		double dy = y - heartY - runout * s;
		double r = Math.sqrt(dy * dy + depth * depth)
				+ wave * boardNoise(s, y, 96, 32, 1 + bs)
				+ 0.8 * boardNoise(s, y, 24, 8, 2 + bs);

		// Knots: a dark core ringed in on itself under a darker rim, a shadow round it, and the
		// grain bowing round it.
		double knot = Double.NaN, halo = 0;
		for (Knot k : board.knots()) {
			double ks = k.s(), ky = k.y(), kr = k.r();
			double ds = (s - ks) / 1.5, dk = Math.sqrt(ds * ds + (y - ky) * (y - ky));
			r += 2.4 * kr * Math.exp(-Math.pow(dk / (2.6 * kr), 2));
			double q = dk / kr;
			if (q < 1) {
				knot = q > 0.85 ? -0.95
						: -0.72 - 0.15 * (1 - q) + 0.1 * Math.sin(2 * Math.PI * dk / 2.4)
								+ 0.12 * boardNoise(s, y, 1, 1, 3 + bs);
			} else if (q < 1.8) {
				halo = Math.min(halo, -0.35 * (1 - (q - 1) / 0.8));
			}
		}
		if (!Double.isNaN(knot)) return knot + tone * 0.5;

		double t = r / spacing;
		t -= Math.floor(t);
		double latewood = Math.max(0, 1 - Math.pow((t - 0.85) / 0.1, 2));
		double ring = 0.3 * Math.sin(2 * Math.PI * t) - 0.7 * latewood * latewood;

		double band   = boardNoise(s, y, 192, 6, 4 + bs);
		double blotch = boardNoise(s, y, 288, 48, 5 + bs);
		double fibres = 0.6 * boardNoise(s, y, 48, 1, 6 + bs) + 0.4 * boardNoise(s, y, 12, 0.5, 7 + bs);
		return contrast * 0.45 * ring + 0.22 * band + 0.12 * blotch + 0.35 * fibres + tone + edge + halo;
	}

	/** A board's share of its row's length: from 0.6 to 1.4 of an even share. */
	private static double boardWeight(int row, int board, int seed) {
		return 0.6 + 0.8 * hash01(row, 10 + board, 42 + seed);
	}

	/**
	 * The most knots one field shows. A board draws from none to two, so a seed can deal a field
	 * far more than another; past this many, the extras are dropped by a random priority, thinning
	 * them evenly across the field rather than from one end.
	 */
	static final int MAX_KNOTS = 9;

	/** A knot: how far along its board, how far down it, and its radius, in design pixels. */
	record Knot(double s, double y, double r) {}

	/** A board: where along its row it begins, its length, its own id, and its knots. */
	record Board(double begin, double length, int id, List<Knot> knots) {}

	/** A wood tile's layout for one seed: where each row's first joint falls, and its boards. */
	record Planks(double[] starts, Board[][] rows) {}

	/** Each seed's layout, worked out once: shading looks boards up rather than re-deriving them. */
	private static final Map<Integer, Planks> PLANKS = new ConcurrentHashMap<>();

	/** Lays out the boards and knots of the wood tile grown from {@code seed}. */
	static Planks layPlanks(int seed) {
		int rowCount = FIELD_H / PLANK_H;
		double[] starts = new double[rowCount];
		double[][] begins = new double[rowCount][], lengths = new double[rowCount][];
		record Candidate(int row, int board, Knot knot, double priority) {}
		List<Candidate> candidates = new ArrayList<>();

		for (int row = 0; row < rowCount; row++) {
			int count = 2 + (int) (hash01(row, 0, 40 + seed) * 2);
			starts[row] = hash01(row, 1, 41 + seed) * FIELD_W;
			double total = 0;
			for (int i = 0; i < count; i++) total += boardWeight(row, i, seed);
			begins[row] = new double[count];
			lengths[row] = new double[count];
			double begin = 0;
			for (int b = 0; b < count; b++) {
				double length = boardWeight(row, b, seed) / total * FIELD_W;
				begins[row][b] = begin;
				lengths[row][b] = length;
				begin += length;

				int id = row * 4 + b;
				int knots = (int) (hash01(id, 10, 60 + seed) * 2.6);
				for (int k = 0; k < knots; k++) {
					double ks = length * (0.08 + 0.84 * hash01(id, 11 + 3 * k, 61 + seed));
					double ky = PLANK_H * (0.12 + 0.76 * hash01(id, 12 + 3 * k, 62 + seed));
					double size = hash01(id, 13 + 3 * k, 63 + seed);
					candidates.add(new Candidate(row, b, new Knot(ks, ky, 2.5 + 9 * size * size),
							hash01(id, 30 + k, 64 + seed)));
				}
			}
		}

		candidates.sort(Comparator.comparingDouble(Candidate::priority));
		List<List<List<Knot>>> kept = new ArrayList<>();
		for (int row = 0; row < rowCount; row++) {
			List<List<Knot>> perBoard = new ArrayList<>();
			for (int b = 0; b < begins[row].length; b++) perBoard.add(new ArrayList<>());
			kept.add(perBoard);
		}
		for (Candidate c : candidates.subList(0, Math.min(MAX_KNOTS, candidates.size())))
			kept.get(c.row()).get(c.board()).add(c.knot());

		Board[][] rows = new Board[rowCount][];
		for (int row = 0; row < rowCount; row++) {
			rows[row] = new Board[begins[row].length];
			for (int b = 0; b < rows[row].length; b++)
				rows[row][b] = new Board(begins[row][b], lengths[row][b], row * 4 + b,
						List.copyOf(kept.get(row).get(b)));
		}
		return new Planks(starts, rows);
	}

	// =============================================================================================
	// Noise
	// =============================================================================================

	/**
	 * Smooth value noise from -1 to 1 on a lattice of {@code cw} by {@code ch} design pixels,
	 * wrapping with sand and steel's tile. {@code cw} must divide {@link #PERIOD_W}, and {@code ch}
	 * {@link #PERIOD_H}.
	 */
	static double noise(double u, double v, double cw, double ch, int seed) {
		return lattice(u / cw, v / ch, (int) Math.round(PERIOD_W / cw), (int) Math.round(PERIOD_H / ch), seed);
	}

	/**
	 * Smooth value noise in a board's own coordinates, which never wrap: a board ends at its joint,
	 * so nothing in it has to meet anything past its end.
	 */
	private static double boardNoise(double s, double y, double cw, double ch, int seed) {
		return lattice(s / cw, y / ch, 0, 0, seed);
	}

	/** Value noise at lattice coordinates, wrapping every {@code nx} by {@code ny} cells (0: never). */
	private static double lattice(double x, double y, int nx, int ny, int seed) {
		int x0 = (int) Math.floor(x), y0 = (int) Math.floor(y);
		double fx = smooth(x - x0), fy = smooth(y - y0);
		int xa = nx > 0 ? Math.floorMod(x0, nx) : x0, xb = nx > 0 ? Math.floorMod(x0 + 1, nx) : x0 + 1;
		int ya = ny > 0 ? Math.floorMod(y0, ny) : y0, yb = ny > 0 ? Math.floorMod(y0 + 1, ny) : y0 + 1;
		double top = lerp(hash(xa, ya, seed), hash(xb, ya, seed), fx);
		double bot = lerp(hash(xa, yb, seed), hash(xb, yb, seed), fx);
		return lerp(top, bot, fy);
	}

	private static double smooth(double t) {
		return t * t * (3 - 2 * t);
	}

	private static double lerp(double a, double b, double t) {
		return a + (b - a) * t;
	}

	/** A fixed pseudo-random value from -1 to 1 for a lattice point. */
	private static double hash(int x, int y, int seed) {
		return hash01(x, y, seed) * 2 - 1;
	}

	/** A fixed pseudo-random value from 0 to 1 for a lattice point. */
	private static double hash01(int x, int y, int seed) {
		int h = x * 374761393 + y * 668265263 + seed * 1274126177;
		h = (h ^ (h >>> 13)) * 1103515245;
		h ^= h >>> 16;
		return (h & 0x7FFFFFFF) / (double) 0x7FFFFFFF;
	}
}
