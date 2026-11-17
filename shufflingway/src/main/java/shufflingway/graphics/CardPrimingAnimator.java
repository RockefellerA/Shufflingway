package shufflingway.graphics;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import shufflingway.UiScale;

/**
 * Transparent overlay that plays a Priming: the Forward catches fire at its centre, a ring of flame
 * in the incoming card's Element spreads outward across it, and the card it primes into is what the
 * fire leaves behind.
 *
 * <ol>
 *   <li>A flame ignites at the centre of the card.</li>
 *   <li>The burn front, a ragged circle, widens to the card's corners. Behind it is the incoming
 *       card, glowing with heat close to the edge; ahead of it is the outgoing card, scorching as
 *       the flames reach it. Flame tongues stand on the front and stream outward.</li>
 *   <li>Past the corners the ring breaks away from the card and dies out, embers flying off it,
 *       and the incoming card settles exactly on its slot render.</li>
 * </ol>
 *
 * <p>Both cards are drawn here for the whole animation, so the slot underneath must be blank while
 * it plays — {@code FieldEntryAnimator.primeWithAnim} holds it blank and draws the primed card into it
 * on the last frame.
 *
 * <p>Distances in the burn are measured in units of {@code R}, half the diagonal of the card's opaque
 * pixels: 1 is the card's corners, wherever the slot canvas puts the card.
 */
public class CardPrimingAnimator extends JComponent {

	public static final int FRAME_MS     = 16;
	public static final int TOTAL_FRAMES = 56;   // 896 ms

	/**
	 * Frames the final, flame-free card stays drawn after {@link #TOTAL_FRAMES}, so the slot it hands
	 * over to has its icon before the overlay stops covering it.
	 */
	private static final int LINGER = 4;

	private static final int IGNITE_END = 8;    // the flame grows to full strength at the centre
	private static final int SPREAD_END = 44;   // the front comes to rest past every corner
	private static final int FLASH_END  = 12;

	/** How far the noise holds the front back from a perfect circle, in R. */
	private static final double FRONT_WOBBLE = 0.28;
	/** Noise cells per R in the front's outline. */
	private static final double FRONT_CELLS  = 2.4;
	/** Radius inside which the wobble eases off, so the fire starts dead centre. */
	private static final double WOBBLE_EASE  = 0.25;

	/** Height of the tallest flames above the front at full strength, in R. */
	private static final double FLAME_LEN   = 0.30;
	/** How quickly the flames fade into the incoming card behind the front, in R. */
	private static final double FLAME_INNER = 0.05;
	/**
	 * How much the turbulence eats out of the flames. It only ever subtracts, so the flames stand
	 * as tongues of every height, hottest at the root and cooling toward the tip.
	 */
	private static final double EROSION     = 0.85;
	/** Half-width of the line burning along the front itself, which the erosion never breaks, in R. */
	private static final double EDGE        = 0.035;
	private static final double EDGE_HEAT   = 0.75;
	/** Tongues round the ring at the coarsest octave. The tongue noise wraps at this period. */
	private static final int    TONGUES      = 20;
	/** Tongue-noise cells per R along the radius: fewer than round the ring, so the tongues run long. */
	private static final double RADIAL_CELLS = 2.0;
	/** How far the tongue noise streams outward each frame, in radial cells. */
	private static final double FLOW         = 0.14;
	/** Front position at which the flames start to die: they have reached the card's corners. */
	private static final double FADE_FROM    = 1.0;
	/** Outermost reach of anything the animation draws, in R. */
	private static final double REACH        = 1.6;

	/** Width of the scorched band on the outgoing card ahead of the front, in R. */
	private static final double SCORCH    = 0.13;
	/** Width of the heat glow on the incoming card behind the front, in R. */
	private static final double AFTERGLOW = 0.12;
	/** The latest the front may come to rest; a burn puts it further out only if its card needs it. */
	private static final double FRONT_END = 1.45;

	private static final int EMBER_COUNT   = 34;
	/** Palette slices round the ring when the card has more than one Element. */
	private static final int ANGLE_BUCKETS = 96;

	/** Period wide enough that the front noise never visibly repeats across a card. */
	private static final int NO_WRAP = 1 << 16;

	// -------------------------------------------------------------------------
	// Palettes
	// -------------------------------------------------------------------------

	/**
	 * The flame colours for {@code element}, from the cool edge of a flame to its core: deep, main,
	 * bright, core. {@code null} for anything that is not an Element.
	 *
	 * <p>Not {@code ElementColor}: those are board colours, and Light's grey and Dark's near-black
	 * make no fire. Dark burns violet over black instead, Light white over gold.
	 */
	static Color[] ramp(String element) {
		if (element == null) return null;
		return switch (element.trim().toLowerCase()) {
			case "fire"      -> rgb(150, 20, 0,    235, 80, 20,    255, 175, 45,   255, 242, 190);
			case "ice"       -> rgb(20, 70, 150,   60, 170, 240,   150, 225, 255,  238, 252, 255);
			case "wind"      -> rgb(10, 90, 40,    40, 185, 90,    150, 240, 150,  228, 255, 222);
			case "earth"     -> rgb(125, 72, 0,    228, 168, 0,    255, 220, 70,   255, 250, 205);
			case "lightning" -> rgb(72, 18, 112,   172, 88, 214,   218, 160, 255,  250, 236, 255);
			case "water"     -> rgb(14, 48, 132,   58, 120, 212,   130, 182, 242,  226, 240, 255);
			case "light"     -> rgb(165, 132, 60,  242, 222, 150,  255, 248, 214,  255, 255, 255);
			case "dark"      -> rgb(22, 4, 32,     96, 30, 142,    172, 92, 232,   236, 202, 255);
			default          -> null;
		};
	}

	private static Color[] rgb(int... c) {
		return new Color[] {
			new Color(c[0], c[1], c[2]), new Color(c[3], c[4], c[5]),
			new Color(c[6], c[7], c[8]), new Color(c[9], c[10], c[11]),
		};
	}

	/**
	 * A burn's colours, by direction from the centre. One Element colours the whole ring; two or
	 * more take turns round it, each holding two opposite arcs, blended where they meet.
	 */
	private static final class Palette {
		final int       buckets;
		/** Premultiplied ARGB by flame intensity 0..255, per bucket. */
		final int[][]   flame;
		/** The colour the outgoing card chars toward, and the one the incoming card glows with. */
		final int[]     scorch, heat;
		final Color[][] ramps;

		Palette(String[] elements) {
			List<Color[]> found = new ArrayList<>();
			if (elements != null)
				for (String e : elements) { Color[] r = ramp(e); if (r != null) found.add(r); }
			if (found.isEmpty()) found.add(ramp("fire"));
			int n = found.size();
			buckets = n == 1 ? 1 : ANGLE_BUCKETS;
			flame   = new int[buckets][];
			scorch  = new int[buckets];
			heat    = new int[buckets];
			ramps   = new Color[buckets][];
			for (int b = 0; b < buckets; b++) {
				Color[] r;
				if (n == 1) r = found.get(0);
				else {
					double s    = (b + 0.5) / buckets * n * 2;
					int    i    = (int) s;
					double into = smoothstep(0.7, 1.0, s - i);
					r = blend(found.get(i % n), found.get((i + 1) % n), into);
				}
				ramps[b]  = r;
				flame[b]  = flameLut(r);
				scorch[b] = r[0].getRGB();
				heat[b]   = r[2].getRGB();
			}
		}

		/** The bucket for {@code turn}, an angle as a fraction of a full turn in [0, 1). */
		int bucket(double turn) {
			return buckets == 1 ? 0 : Math.min(buckets - 1, (int) (turn * buckets));
		}

		/** An ember's colour at {@code heat} 1 (just thrown) down to 0 (gone cold). */
		Color ember(int bucket, double heat, int alpha) {
			Color[] r = ramps[bucket];
			double  s = Math.max(0, Math.min(1, heat)) * 3;
			int     i = Math.min(2, (int) s);
			Color   c = mix(r[i], r[i + 1], s - i);
			return new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.max(0, Math.min(255, alpha)));
		}

		private static Color[] blend(Color[] a, Color[] b, double t) {
			Color[] out = new Color[a.length];
			for (int i = 0; i < a.length; i++) out[i] = mix(a[i], b[i], t);
			return out;
		}

		/**
		 * Intensity to colour: transparent at the flame's edge, through the Element's deep and main
		 * colours, to its bright colour and a near-white core.
		 */
		private static int[] flameLut(Color[] r) {
			double[] at    = { 0.0,  0.15, 0.38, 0.62, 0.85, 1.0 };
			Color[]  col   = { r[0], r[0], r[1], r[2], r[3], r[3] };
			double[] alpha = { 0.0,  0.50, 0.85, 0.95, 1.0,  1.0 };
			int[] lut = new int[256];
			for (int i = 0; i < 256; i++) {
				double v = i / 255.0;
				int    s = 0;
				while (s < at.length - 2 && v > at[s + 1]) s++;
				double t = (v - at[s]) / (at[s + 1] - at[s]);
				Color  c = mix(col[s], col[s + 1], t);
				double a = alpha[s] + (alpha[s + 1] - alpha[s]) * t;
				lut[i] = premultiplied(c.getRed(), c.getGreen(), c.getBlue(), a);
			}
			return lut;
		}
	}

	// -------------------------------------------------------------------------
	// One Priming in flight
	// -------------------------------------------------------------------------

	private static final class Ember {
		final double turn, speed, size, drift;
		final int    born, life;

		Ember(Random rnd) {
			turn  = rnd.nextDouble();
			born  = 3 + rnd.nextInt(30);
			life  = 12 + rnd.nextInt(11);
			speed = 0.012 + rnd.nextDouble() * 0.018;
			size  = UiScale.scale(1.5f + rnd.nextFloat() * 1.8f);
			drift = (rnd.nextDouble() - 0.5) * 0.06;
		}
	}

	private static final class Burn {
		/** The slot canvas's top-left, in layered-pane coordinates. */
		final Point origin;
		final int   w, h;
		final int[] from, to;
		/** Where the front has to be for each canvas pixel to have burned through, in R. */
		final float[] cardFront;
		final byte[]  cardHue;
		final BufferedImage card;
		final int[]         cardPx;

		/** The flame grid: half resolution, its top-left in canvas pixels, its size in cells. */
		final int gx, gy, gw, gh;
		final float[] gridFront, gridRadius, gridTurn;
		final byte[]  gridHue;
		final BufferedImage flames;
		final int[]         flamesPx;

		/** The card's centre in canvas pixels, and R. */
		final double cx, cy, radius;
		final double frontEnd;
		final Palette palette;
		final Ember[] embers;
		final int     seed;
		final Rectangle extent;
		int frame;

		Burn(BufferedImage fromImg, BufferedImage toImg, Point slotCenter, String[] elements) {
			w = toImg.getWidth();
			h = toImg.getHeight();
			origin = new Point(slotCenter.x - w / 2, slotCenter.y - h / 2);
			to   = toImg.getRGB(0, 0, w, h, null, 0, w);
			from = sameSize(fromImg, w, h).getRGB(0, 0, w, h, null, 0, w);

			Rectangle body = opaqueBounds(toImg);
			cx     = body.x + body.width  / 2.0;
			cy     = body.y + body.height / 2.0;
			radius = Math.max(1.0, Math.hypot(body.width, body.height) / 2.0);

			Random rnd = new Random();
			seed    = rnd.nextInt();
			palette = new Palette(elements);
			embers  = new Ember[EMBER_COUNT];
			for (int i = 0; i < EMBER_COUNT; i++) embers[i] = new Ember(rnd);

			cardFront = new float[w * h];
			cardHue   = new byte[w * h];
			double latest = 0;
			for (int y = 0; y < h; y++)
				for (int x = 0; x < w; x++) {
					int k = y * w + x;
					cardFront[k] = (float) frontCoordinate(x + 0.5, y + 0.5);
					cardHue[k]   = (byte) palette.bucket(turnOf(x + 0.5, y + 0.5));
					if (((from[k] | to[k]) >>> 24) != 0) latest = Math.max(latest, cardFront[k]);
				}
			// Every visible pixel has to be past the afterglow by the last frame, or it would not
			// match the slot render the animation hands over to.
			frontEnd = Math.max(FRONT_END, latest + AFTERGLOW + 0.02);
			card   = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
			cardPx = ((DataBufferInt) card.getRaster().getDataBuffer()).getData();

			int half = (int) Math.ceil(REACH * radius);
			gx = (int) Math.floor(cx) - half;
			gy = (int) Math.floor(cy) - half;
			gw = half;
			gh = half;
			gridFront  = new float[gw * gh];
			gridRadius = new float[gw * gh];
			gridTurn   = new float[gw * gh];
			gridHue    = new byte[gw * gh];
			for (int j = 0; j < gh; j++)
				for (int i = 0; i < gw; i++) {
					int    k  = j * gw + i;
					double px = gx + 2 * i + 1, py = gy + 2 * j + 1;
					gridFront[k]  = (float) frontCoordinate(px, py);
					gridRadius[k] = (float) (Math.hypot(px - cx, py - cy) / radius);
					gridTurn[k]   = (float) turnOf(px, py);
					gridHue[k]    = (byte) palette.bucket(gridTurn[k]);
				}
			flames   = new BufferedImage(gw, gh, BufferedImage.TYPE_INT_ARGB_PRE);
			flamesPx = ((DataBufferInt) flames.getRaster().getDataBuffer()).getData();

			Rectangle grid = new Rectangle(origin.x + gx, origin.y + gy, gw * 2, gh * 2);
			int pad = OverlayDirtyRegion.PAD + (int) Math.ceil(UiScale.scale(8f));
			grid.grow(pad, pad);
			extent = grid.union(new Rectangle(origin.x, origin.y, w, h));

			render();
		}

		/**
		 * The front position at which ({@code x}, {@code y}) burns through: its distance from the
		 * centre, held back by noise so the burning hole comes out ragged rather than round.
		 */
		private double frontCoordinate(double x, double y) {
			double r    = Math.hypot(x - cx, y - cy) / radius;
			double n    = contrast(fbm((x - cx) / radius * FRONT_CELLS, (y - cy) / radius * FRONT_CELLS,
					NO_WRAP, seed));
			double ease = Math.min(1.0, r / WOBBLE_EASE);
			return r + FRONT_WOBBLE * n * ease;
		}

		/** The direction of ({@code x}, {@code y}) from the centre, as a fraction of a turn in [0, 1). */
		private double turnOf(double x, double y) {
			double t = Math.atan2(y - cy, x - cx) / (2 * Math.PI);
			return t < 0 ? t + 1 : t;
		}

		private double front(int f) {
			double t = Math.min(1.0, (double) f / SPREAD_END);
			return frontEnd * t * t * (3 - 2 * t);
		}

		/** How fierce the flames are: igniting over the first frames, dying once past the corners. */
		private double strength(int f) {
			double ignite = Math.sqrt(Math.min(1.0, (double) f / IGNITE_END));
			double out    = frontEnd - 0.05;
			double dying  = Math.max(0, Math.min(1, (out - front(f)) / (out - FADE_FROM)));
			return ignite * smoothstep(0, 1, dying);
		}

		void render() {
			int    f      = Math.min(frame, TOTAL_FRAMES);
			double front  = front(f);
			double fierce = strength(f);
			double ignite = Math.min(1.0, (double) f / IGNITE_END);
			renderCard(front, ignite);
			renderFlames(front, fierce, f);
		}

		/** Each pixel from the outgoing card ahead of the front, from the incoming card behind it. */
		private void renderCard(double front, double ignite) {
			for (int k = 0; k < cardPx.length; k++) {
				double u = cardFront[k] - front;
				if (u >= 0) {
					int argb = from[k];
					if (u < SCORCH && ignite > 0 && (argb >>> 24) != 0) {
						double s = (1 - u / SCORCH) * ignite;
						argb = toward(argb, palette.scorch[cardHue[k]], 1 - 0.80 * s, 0.30 * s);
					}
					cardPx[k] = argb;
				} else {
					int argb = to[k];
					if (u > -AFTERGLOW && (argb >>> 24) != 0) {
						double g = 1 + u / AFTERGLOW;
						argb = toward(argb, palette.heat[cardHue[k]], 1 - 0.78 * g * g, 0.78 * g * g);
					}
					cardPx[k] = argb;
				}
			}
		}

		/**
		 * The ring of flame on the front: a line burning along the front itself, tongues licking
		 * outward from it wherever the turbulence spares them, the whole pattern streaming away from
		 * the centre frame by frame. Dying flames cool down the palette as well as fading, so they go
		 * out deep rather than as a grey ghost of their core.
		 */
		private void renderFlames(double front, double fierce, int f) {
			if (fierce <= 0.005) { Arrays.fill(flamesPx, 0); return; }
			double len  = FLAME_LEN * (0.35 + 0.65 * fierce);
			double live = Math.min(1.0, fierce * 1.6);
			double cool = 0.4 + 0.6 * live;
			double fade = Math.pow(live, 0.7);
			double flow = f * FLOW;
			for (int k = 0; k < flamesPx.length; k++) {
				double u = gridFront[k] - front;
				if (u < -FLAME_INNER || u > len) { flamesPx[k] = 0; continue; }
				double n = contrast(fbm(gridTurn[k] * TONGUES, gridRadius[k] * RADIAL_CELLS - flow, TONGUES, seed + 7));
				double i = u >= 0
						? 1 - u / len - (1 - n) * EROSION
						: (1 + u / FLAME_INNER) * (0.55 + 0.45 * n);
				double edge = 1 - Math.abs(u) / EDGE;
				if (edge > 0) i = Math.max(i, EDGE_HEAT * edge);
				if (i <= 0) { flamesPx[k] = 0; continue; }
				int argb = palette.flame[gridHue[k]][(int) (Math.min(1.0, i * cool) * 255)];
				flamesPx[k] = fade >= 1.0 ? argb : scalePremultiplied(argb, fade);
			}
		}

		void paint(Graphics2D g2) {
			g2.drawImage(card, origin.x, origin.y, null);
			int f = Math.min(frame, TOTAL_FRAMES);
			paintFlash(g2, f);
			if (strength(f) > 0.005)
				g2.drawImage(flames, origin.x + gx, origin.y + gy, gw * 2, gh * 2, null);
			paintEmbers(g2, f);
		}

		/** The flash of ignition at the centre, gone before the hole it opens is much wider than it. */
		private void paintFlash(Graphics2D g2, int f) {
			if (f >= FLASH_END) return;
			double t     = (double) f / FLASH_END;
			float  alpha = (float) (Math.pow(Math.sin(Math.PI * Math.min(1.0, t * 1.4)), 1.5) * 0.8);
			if (alpha < 0.01f) return;
			float   r    = (float) (radius * (0.12 + 0.30 * t));
			Color[] ramp = palette.ramps[0];
			float[] at   = { 0f, 0.45f, 1f };
			Color[] col  = { withAlpha(ramp[3], alpha), withAlpha(ramp[2], alpha * 0.55f), withAlpha(ramp[2], 0f) };
			float   x    = (float) (origin.x + cx), y = (float) (origin.y + cy);
			Graphics2D fg = (Graphics2D) g2.create();
			fg.setPaint(new RadialGradientPaint(x, y, r, at, col));
			fg.fill(new Ellipse2D.Float(x - r, y - r, 2 * r, 2 * r));
			fg.dispose();
		}

		/** Sparks thrown off the front, cooling through the Element's colours as they fly. */
		private void paintEmbers(Graphics2D g2, int f) {
			for (Ember e : embers) {
				int age = f - e.born;
				if (age < 0 || age >= e.life) continue;
				double t     = (double) age / e.life;
				double start = Math.max(0, front(e.born) - FRONT_WOBBLE * 0.5);
				double dist  = Math.min(REACH - 0.05, start + e.speed * age);
				double turn  = e.turn + e.drift * t;
				double ang   = turn * 2 * Math.PI;
				double x     = origin.x + cx + Math.cos(ang) * dist * radius;
				double y     = origin.y + cy + Math.sin(ang) * dist * radius;
				int    alpha = (int) Math.round(Math.pow(1 - t, 1.3) * 235);
				double size  = e.size * (1 - 0.5 * t);
				if (alpha < 4 || size < 0.3) continue;
				int bucket = palette.bucket(((turn % 1) + 1) % 1);
				g2.setColor(palette.ember(bucket, 1 - t, alpha / 3));
				double halo = size * 2.4;
				g2.fill(new Ellipse2D.Double(x - halo, y - halo, 2 * halo, 2 * halo));
				g2.setColor(palette.ember(bucket, 1 - t * 0.7, alpha));
				g2.fill(new Ellipse2D.Double(x - size, y - size, 2 * size, 2 * size));
			}
		}
	}

	// -------------------------------------------------------------------------
	// The overlay
	// -------------------------------------------------------------------------

	private final List<Burn> burns = new ArrayList<>();
	private final Timer      timer;
	private final OverlayDirtyRegion dirty = new OverlayDirtyRegion();

	public CardPrimingAnimator() {
		setOpaque(false);
		setFocusable(false);
		timer = new Timer(FRAME_MS, e -> tick());
		timer.setCoalesce(true);
	}

	/** Installs the animator on {@code frame}'s layered pane at DRAG_LAYER. */
	public static CardPrimingAnimator install(JFrame frame) {
		CardPrimingAnimator a  = new CardPrimingAnimator();
		JLayeredPane        lp = frame.getRootPane().getLayeredPane();
		a.setBounds(0, 0, lp.getWidth(), lp.getHeight());
		lp.add(a, JLayeredPane.DRAG_LAYER);
		lp.addComponentListener(new ComponentAdapter() {
			@Override public void componentResized(ComponentEvent e) {
				a.setBounds(0, 0, lp.getWidth(), lp.getHeight());
			}
		});
		return a;
	}

	/**
	 * Burns {@code from} into {@code to} — both slot renders, already turned to the slot's state —
	 * centred on {@code center} in layered-pane coordinates, in the colours of {@code elements}.
	 * {@code to} sets the canvas; a {@code from} of another size is drawn onto one of the same size.
	 */
	public void start(BufferedImage from, BufferedImage to, Point center, String[] elements) {
		Burn b = new Burn(from, to, center, elements);
		burns.add(b);
		dirty.repaint(this, unionOfExtents());
		if (!timer.isRunning()) timer.start();
	}

	/** Never intercepts mouse events — the board components below stay active. */
	@Override
	public boolean contains(int x, int y) {
		return false;
	}

	/** Advances every burn one frame. Package-private so a test can step frames on the EDT. */
	void tick() {
		burns.removeIf(b -> { b.frame++; return b.frame >= TOTAL_FRAMES + LINGER; });
		for (Burn b : burns) if (b.frame <= TOTAL_FRAMES) b.render();
		if (burns.isEmpty()) timer.stop();
		dirty.repaint(this, unionOfExtents());
	}

	private Rectangle unionOfExtents() {
		Rectangle r = null;
		for (Burn b : burns) r = OverlayDirtyRegion.union(r, b.extent);
		return r;
	}

	@Override
	protected void paintComponent(Graphics g) {
		if (burns.isEmpty()) return;
		Graphics2D g2 = (Graphics2D) g.create();
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,  RenderingHints.VALUE_ANTIALIAS_ON);
		g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		for (Burn b : burns) b.paint(g2);
		g2.dispose();
	}

	// -------------------------------------------------------------------------
	// Noise and pixel arithmetic
	// -------------------------------------------------------------------------

	/** Hashed lattice value in [0, 1). */
	private static double lattice(int x, int y, int seed) {
		int h = seed ^ (x * 0x27d4eb2d) ^ (y * 0x165667b1);
		h = (h ^ (h >>> 15)) * 0x2c1b3c6d;
		h = (h ^ (h >>> 12)) * 0x297a2d39;
		h ^= h >>> 15;
		return (h & 0xFFFFFF) / (double) 0x1000000;
	}

	/** Smooth value noise in [0, 1], wrapping every {@code period} cells along x. */
	private static double valueNoise(double x, double y, int period, int seed) {
		int    xi = (int) Math.floor(x), yi = (int) Math.floor(y);
		double fx = x - xi, fy = y - yi;
		int    x0 = Math.floorMod(xi, period), x1 = Math.floorMod(xi + 1, period);
		double sx = fx * fx * (3 - 2 * fx), sy = fy * fy * (3 - 2 * fy);
		double top    = lattice(x0, yi, seed)     + (lattice(x1, yi, seed)     - lattice(x0, yi, seed))     * sx;
		double bottom = lattice(x0, yi + 1, seed) + (lattice(x1, yi + 1, seed) - lattice(x0, yi + 1, seed)) * sx;
		return top + (bottom - top) * sy;
	}

	/** Three octaves of {@link #valueNoise}; each octave doubles the period, so the wrap holds. */
	private static double fbm(double x, double y, int period, int seed) {
		return 0.55 * valueNoise(x,     y,     period,     seed)
		     + 0.30 * valueNoise(x * 2, y * 2, period * 2, seed + 101)
		     + 0.15 * valueNoise(x * 4, y * 4, period * 4, seed + 202);
	}

	/** Spreads {@link #fbm}'s output, which bunches round the middle, back across [0, 1]. */
	private static double contrast(double n) {
		return Math.max(0, Math.min(1, (n - 0.5) * 1.8 + 0.5));
	}

	private static double smoothstep(double edge0, double edge1, double x) {
		double t = Math.max(0, Math.min(1, (x - edge0) / (edge1 - edge0)));
		return t * t * (3 - 2 * t);
	}

	private static Color mix(Color a, Color b, double t) {
		return new Color(
				(int) Math.round(a.getRed()   + (b.getRed()   - a.getRed())   * t),
				(int) Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * t),
				(int) Math.round(a.getBlue()  + (b.getBlue()  - a.getBlue())  * t));
	}

	private static Color withAlpha(Color c, float alpha) {
		return new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.max(0, Math.min(255, Math.round(alpha * 255))));
	}

	/** {@code argb}'s colour kept by {@code keep} plus {@code add} of {@code rgb}, its alpha unchanged. */
	private static int toward(int argb, int rgb, double keep, double add) {
		int r = clamp((int) (((argb >> 16) & 0xFF) * keep + ((rgb >> 16) & 0xFF) * add));
		int g = clamp((int) (((argb >>  8) & 0xFF) * keep + ((rgb >>  8) & 0xFF) * add));
		int b = clamp((int) (( argb        & 0xFF) * keep + ( rgb        & 0xFF) * add));
		return (argb & 0xFF000000) | (r << 16) | (g << 8) | b;
	}

	private static int premultiplied(int r, int g, int b, double alpha) {
		int a = clamp((int) Math.round(alpha * 255));
		return (a << 24) | (clamp(r * a / 255) << 16) | (clamp(g * a / 255) << 8) | clamp(b * a / 255);
	}

	private static int scalePremultiplied(int argb, double s) {
		int a = (int) ((argb >>> 24)          * s);
		int r = (int) (((argb >> 16) & 0xFF)  * s);
		int g = (int) (((argb >>  8) & 0xFF)  * s);
		int b = (int) (( argb        & 0xFF)  * s);
		return (a << 24) | (r << 16) | (g << 8) | b;
	}

	private static int clamp(int v) {
		return v < 0 ? 0 : v > 255 ? 255 : v;
	}

	/** {@code img} itself when it is {@code w} by {@code h}; otherwise drawn top-left onto a canvas that is. */
	private static BufferedImage sameSize(BufferedImage img, int w, int h) {
		if (img.getWidth() == w && img.getHeight() == h) return img;
		BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = out.createGraphics();
		g.drawImage(img, 0, 0, null);
		g.dispose();
		return out;
	}

	/** Bounding box of {@code img}'s visible pixels; the whole image if none are. */
	private static Rectangle opaqueBounds(BufferedImage img) {
		int w = img.getWidth(), h = img.getHeight();
		int minX = w, minY = h, maxX = -1, maxY = -1;
		for (int y = 0; y < h; y++)
			for (int x = 0; x < w; x++)
				if ((img.getRGB(x, y) >>> 24) > 16) {
					if (x < minX) minX = x;
					if (x > maxX) maxX = x;
					if (y < minY) minY = y;
					if (y > maxY) maxY = y;
				}
		return maxX < 0 ? new Rectangle(0, 0, w, h) : new Rectangle(minX, minY, maxX - minX + 1, maxY - minY + 1);
	}
}
