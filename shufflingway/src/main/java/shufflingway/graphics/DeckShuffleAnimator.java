package shufflingway.graphics;

import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLayeredPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/**
 * Transparent overlay that plays a Main Deck being shuffled, a riffle drawn in place of the pile
 * the deck's slot shows.
 *
 * <ol>
 *   <li>Cut, to {@link #CUT_END}: the top half lifts off and is set down beside the bottom half.</li>
 *   <li>Riffle, to {@link #RIFFLE_END}: the halves bend up at their inner edges, and cards flick off
 *       them in turn, a few at a time, onto one pile between them.</li>
 *   <li>Square, to {@link #TOTAL_FRAMES}: the shuffled pile slides back onto the slot and is set
 *       down.</li>
 * </ol>
 *
 * <p>Every pile is a {@link CardPile} render of the cards it holds, so the halves sink as they
 * empty and the middle pile climbs as it fills, and the first and last frames are the slot's own
 * pile exactly. The slot must show nothing while this plays; {@code onDone} says when it may show
 * its pile again.
 *
 * <p>{@code tools/sounds/make_shuffle_samples.py} times the shuffle sound to these phases, so a
 * change to one wants the same change to the other.
 */
public class DeckShuffleAnimator extends JComponent {

	public static final int FRAME_MS = 16;
	/** The frame the cut half comes to rest beside the bottom half. */
	static final int CUT_END = 10;               // 160 ms
	/** The frame the last card lands on the middle pile. */
	static final int RIFFLE_END = 34;            // 544 ms
	/** The frame the shuffled pile is back on the slot. */
	public static final int TOTAL_FRAMES = 44;   // 704 ms

	/**
	 * Frames the finished pile stays drawn after {@code onDone}, so the slot has its pile back
	 * before the overlay stops covering it.
	 */
	private static final int LINGER = 2;

	/** Frames a packet of cards takes to fall from its half onto the middle pile. */
	static final int DROP_FRAMES = 3;
	/** The most packets one riffle drops. A bigger deck falls a few cards at a time. */
	static final int MAX_DROPS = 18;

	/** How far from the bottom half the cut half is set down, in card widths. */
	private static final double CUT_DISTANCE = 0.62;
	/** How high the cut half is lifted on its way across, in card heights. */
	private static final double CUT_LIFT = 0.10;
	/** How far the halves bend up at their inner edges while they riffle. */
	private static final double BEND = Math.toRadians(5);
	/** Frames the bend takes to come on after the cut, and to go off before the riffle ends. */
	private static final int BEND_ON = 3, BEND_OFF = 5;
	/** How high a packet flicks on its way to the middle pile, in card heights. */
	private static final double DROP_ARC = 0.04;
	/**
	 * How far below a falling packet its shadow lies, in card heights, and how dark it is. Without
	 * it a dark cardback in the air is lost against the dark piles beneath it.
	 */
	private static final double SHADOW_DROP = 0.018;
	private static final float SHADOW_ALPHA = 0.45f;
	/** How high the shuffled pile is lifted on its way back to the slot, in card heights. */
	private static final double RETURN_LIFT = 0.03;

	/**
	 * A packet of {@code size} cards that leaves its half on frame {@code start} and lands on the
	 * middle pile {@link #DROP_FRAMES} later. {@code fromCut} is whether it comes off the cut half.
	 */
	record Drop(boolean fromCut, int size, int start) {}

	/**
	 * The packets a riffle of {@code count} cards drops, in the order they fall. The cut is the top
	 * {@code count / 2} cards. The bottom half drops first, then the halves take turns, so each has
	 * one packet more at most than the other. The packets leave at even steps from the frame after
	 * the cut to {@link #DROP_FRAMES} before the riffle ends, so the last has landed by then.
	 */
	static List<Drop> plan(int count, Random rnd) {
		int cut    = count / 2;
		int drops  = Math.min(count, MAX_DROPS);
		int[] cutSizes    = packets(cut, drops / 2, rnd);
		int[] bottomSizes = packets(count - cut, drops - drops / 2, rnd);
		int first = CUT_END + 1, last = RIFFLE_END - DROP_FRAMES;
		List<Drop> out = new ArrayList<>(drops);
		for (int i = 0; i < drops; i++) {
			boolean fromCut = i % 2 == 1;
			int start = drops == 1 ? first
					: first + (int) Math.round((double) i * (last - first) / (drops - 1));
			out.add(new Drop(fromCut, fromCut ? cutSizes[i / 2] : bottomSizes[i / 2], start));
		}
		return out;
	}

	/** {@code n} cards dealt at random into {@code k} packets of at least one card each. */
	private static int[] packets(int n, int k, Random rnd) {
		int[] sizes = new int[k];
		if (k == 0) return sizes;
		Arrays.fill(sizes, 1);
		for (int i = k; i < n; i++) sizes[rnd.nextInt(k)]++;
		return sizes;
	}

	// -------------------------------------------------------------------------
	// One shuffle in flight
	// -------------------------------------------------------------------------

	private static final class Shuffle {
		final Object key;
		/** Where the slot draws its pile's canvas, in layered-pane coordinates. */
		final Point origin;
		final BufferedImage face, card, shadow;
		final int count, cw, ch;
		final CardPile.Lean lean;
		/** +1 when the cut is set down to the right of the bottom half, -1 to its left. */
		final int dir;
		/** How far across the cut half is set down, in pixels. */
		final int cutDistance;
		final List<Drop> drops;
		final Runnable onDone;
		final Rectangle extent;
		final Pile whole = new Pile(), cutHalf = new Pile(), bottomHalf = new Pile(), middle = new Pile();
		int frame;

		Shuffle(Object key, Point origin, Image cardback, int count, int cw, int ch, CardPile.Lean lean,
				boolean cutLeft, Runnable onDone, Random rnd) {
			this.key    = key;
			this.origin = new Point(origin);
			this.count  = count;
			this.cw     = cw;
			this.ch     = ch;
			this.lean   = lean;
			this.dir    = cutLeft ? -1 : 1;
			this.onDone = onDone;
			face = CardAnimation.scaledCard(cardback, cw, ch);
			card = CardAnimation.toARGB(face, cw, ch);
			shadow = silhouette(card, SHADOW_ALPHA);
			cutDistance = (int) Math.round(CUT_DISTANCE * cw);
			drops = plan(count, rnd);

			Dimension size = CardPile.size(cw, ch);
			Rectangle home = new Rectangle(origin.x, origin.y, size.width, size.height);
			Rectangle r = home.union(new Rectangle(origin.x + dir * cutDistance, origin.y, size.width, size.height));
			// Room above for the lifts and the bend, which swings the halves' inner corners up.
			int grow = (int) Math.ceil(ch * (CUT_LIFT + DROP_ARC) + Math.hypot(cw, ch) * Math.sin(BEND))
					+ OverlayDirtyRegion.PAD;
			r.grow(grow, grow);
			extent = r;
		}

		/** A pile's render, redone only when the cards it holds change. */
		private final class Pile {
			private int held = -1;
			private BufferedImage img;

			BufferedImage of(int n) {
				if (n != held) {
					img  = CardPile.render(face, n, cw, ch, lean);
					held = n;
				}
				return img;
			}
		}

		/** Cards still in the cut half ({@code fromCut}) or the bottom half on frame {@code f}. */
		int held(boolean fromCut, int f) {
			int n = fromCut ? count / 2 : count - count / 2;
			for (Drop d : drops) if (d.fromCut() == fromCut && d.start() <= f) n -= d.size();
			return n;
		}

		/** Cards on the middle pile on frame {@code f}. */
		int landed(int f) {
			int n = 0;
			for (Drop d : drops) if (d.start() + DROP_FRAMES <= f) n += d.size();
			return n;
		}

		/** Cards falling onto the middle pile on frame {@code f}. */
		int falling(int f) {
			int n = 0;
			for (Drop d : drops) if (d.start() <= f && f < d.start() + DROP_FRAMES) n += d.size();
			return n;
		}

		void paint(Graphics2D g2) {
			int f = Math.min(frame, TOTAL_FRAMES);
			if (f == 0) {
				g2.drawImage(whole.of(count), origin.x, origin.y, null);
				return;
			}
			if (f > RIFFLE_END) {
				double s = (double) (f - RIFFLE_END) / (TOTAL_FRAMES - RIFFLE_END);
				Point at = middleAt(f);
				at.translate(0, -(int) Math.round(RETURN_LIFT * ch * Math.sin(Math.PI * s)));
				g2.drawImage(middle.of(count), at.x, at.y, null);
				return;
			}
			double bend = bend(f);
			int bottom = held(false, f), cut = held(true, f), mid = landed(f);
			AffineTransform bottomAt = halfTransform(false, f, bend);
			AffineTransform cutAt    = halfTransform(true, f, bend);
			if (bottom > 0) g2.drawImage(bottomHalf.of(bottom), bottomAt, null);
			if (cut > 0)    g2.drawImage(cutHalf.of(cut), cutAt, null);
			if (mid > 0) {
				Point at = middleAt(f);
				g2.drawImage(middle.of(mid), at.x, at.y, null);
			}
			for (Drop d : drops)
				if (d.start() <= f && f < d.start() + DROP_FRAMES) paintDrop(g2, d, f, d.fromCut() ? cutAt : bottomAt, bend);
		}

		/**
		 * A falling packet: its top card, from where it lay on its half to the top of the middle
		 * pile it lands on, flicking up as it goes and straightening out of its half's bend.
		 */
		private void paintDrop(Graphics2D g2, Drop d, int f, AffineTransform half, double bend) {
			double t = (double) (f - d.start()) / DROP_FRAMES;
			Rectangle was = CardPile.topCard(held(d.fromCut(), d.start()) + d.size(), cw, ch, lean);
			Point2D from = half.transform(new Point2D.Double(was.getCenterX(), was.getCenterY()), null);
			Rectangle lands = CardPile.topCard(landed(d.start() + DROP_FRAMES), cw, ch, lean);
			Point mid = middleAt(f);
			double toX = mid.x + lands.getCenterX(), toY = mid.y + lands.getCenterY();
			double e = t * t;
			double x = from.getX() + (toX - from.getX()) * e;
			double y = from.getY() + (toY - from.getY()) * e - DROP_ARC * ch * Math.sin(Math.PI * t);
			AffineTransform at = AffineTransform.getTranslateInstance(x, y);
			at.rotate(halfAngle(d.fromCut(), bend) * (1 - t));
			at.translate(-cw / 2.0, -ch / 2.0);
			AffineTransform shade = AffineTransform.getTranslateInstance(0, SHADOW_DROP * ch);
			shade.concatenate(at);
			g2.drawImage(shadow, shade, null);
			g2.drawImage(card, at, null);
		}

		/** {@code img}'s shape in black, {@code alpha} as opaque as it is. */
		private static BufferedImage silhouette(BufferedImage img, float alpha) {
			int w = img.getWidth(), h = img.getHeight();
			int[] px = img.getRGB(0, 0, w, h, null, 0, w);
			for (int i = 0; i < px.length; i++) px[i] = Math.round((px[i] >>> 24) * alpha) << 24;
			BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
			out.setRGB(0, 0, w, h, px, 0, w);
			return out;
		}

		/**
		 * Where a half's pile canvas is drawn on frame {@code f}, bent by {@code bend} about the
		 * outer bottom corner of its bottom card. The bottom half stays on the slot. The cut half
		 * starts on top of it, lifts, and is set down {@link #cutDistance} across.
		 */
		private AffineTransform halfTransform(boolean cutHalf, int f, double bend) {
			double x = origin.x, y = origin.y;
			if (cutHalf) {
				double t = Math.min(1.0, (double) f / CUT_END);
				double e = smoothstep(t);
				// The cut's bottom card starts on the bottom half's top card.
				Rectangle base = CardPile.topCard(0, cw, ch, lean);
				Rectangle onTop = CardPile.topCard(held(false, 0), cw, ch, lean);
				x += Math.round((onTop.x - base.x) * (1 - e) + dir * cutDistance * e);
				y += Math.round((onTop.y - base.y) * (1 - e) - CUT_LIFT * ch * Math.sin(Math.PI * t));
			}
			AffineTransform at = AffineTransform.getTranslateInstance(x, y);
			double angle = halfAngle(cutHalf, bend);
			if (angle != 0) {
				Rectangle base = CardPile.topCard(0, cw, ch, lean);
				boolean outerIsRight = cutHalf == (dir > 0);
				at.rotate(angle, outerIsRight ? base.getMaxX() : base.getMinX(), base.getMaxY());
			}
			return at;
		}

		/** A half's turn: {@code bend}, the way that lifts its inner edge, the edge facing the other half. */
		private double halfAngle(boolean cutHalf, double bend) {
			return cutHalf ? dir * bend : -dir * bend;
		}

		/** How far the halves are bent on frame {@code f}: coming on after the cut, going off before the riffle ends. */
		private static double bend(int f) {
			double on  = smoothstep((double) (f - CUT_END) / BEND_ON);
			double off = smoothstep((double) (RIFFLE_END - f) / BEND_OFF);
			return BEND * on * off;
		}

		/**
		 * Where the middle pile's canvas is drawn on frame {@code f}: halfway between the halves
		 * while they riffle, then sliding back onto the slot.
		 */
		private Point middleAt(int f) {
			double across = dir * (cutDistance / 2);
			if (f > RIFFLE_END)
				across *= 1 - smoothstep((double) (f - RIFFLE_END) / (TOTAL_FRAMES - RIFFLE_END));
			return new Point(origin.x + (int) Math.round(across), origin.y);
		}
	}

	// -------------------------------------------------------------------------
	// The overlay
	// -------------------------------------------------------------------------

	private final List<Shuffle> shuffles = new ArrayList<>();
	private final Timer timer;
	private final OverlayDirtyRegion dirty = new OverlayDirtyRegion();

	public DeckShuffleAnimator() {
		setOpaque(false);
		setFocusable(false);
		timer = new Timer(FRAME_MS, e -> tick());
		timer.setCoalesce(true);
	}

	/** Installs the animator on {@code frame}'s layered pane at DRAG_LAYER. */
	public static DeckShuffleAnimator install(JFrame frame) {
		DeckShuffleAnimator a  = new DeckShuffleAnimator();
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
	 * Shuffles the pile of {@code count} {@code cardback}s, each {@code cw × ch}, that a slot draws
	 * with its canvas's top-left at {@code origin} in layered-pane coordinates, leaning
	 * {@code lean}. The cut is set down to the left of the bottom half when {@code cutLeft}, so
	 * the shuffle can keep clear of the window's edge.
	 *
	 * <p>{@code deck} names the pile: shuffling it again before this one ends starts it over, and
	 * only the later shuffle's {@code onDone} runs. {@code onDone} (which may be null) runs on the
	 * EDT once the pile is back on the slot. Fewer than two cards make no shuffle, and
	 * {@code onDone} runs straight away. Safe to call from any thread.
	 */
	public void start(Object deck, Point origin, Image cardback, int count, int cw, int ch,
			CardPile.Lean lean, boolean cutLeft, Runnable onDone) {
		if (!SwingUtilities.isEventDispatchThread()) {
			SwingUtilities.invokeLater(() -> start(deck, origin, cardback, count, cw, ch, lean, cutLeft, onDone));
			return;
		}
		// Not the deck's own shuffle stream: both clients of a match draw that in step, and only one
		// of them may be animating.
		start(deck, origin, cardback, count, cw, ch, lean, cutLeft, onDone, new Random());
	}

	/** {@link #start(Object, Point, Image, int, int, int, CardPile.Lean, boolean, Runnable)} on the EDT, the packets dealt from {@code rnd}. */
	void start(Object deck, Point origin, Image cardback, int count, int cw, int ch,
			CardPile.Lean lean, boolean cutLeft, Runnable onDone, Random rnd) {
		shuffles.removeIf(s -> s.key == deck);
		if (count < 2) {
			if (onDone != null) onDone.run();
		} else {
			shuffles.add(new Shuffle(deck, origin, cardback, count, cw, ch, lean, cutLeft, onDone, rnd));
			if (!timer.isRunning()) timer.start();
		}
		dirty.repaint(this, unionOfExtents());
	}

	/** Never intercepts mouse events — the board components below stay active. */
	@Override
	public boolean contains(int x, int y) {
		return false;
	}

	/** Advances every shuffle one frame. Package-private so a test can step frames on the EDT. */
	void tick() {
		List<Runnable> done = new ArrayList<>();
		shuffles.removeIf(s -> {
			s.frame++;
			if (s.frame == TOTAL_FRAMES && s.onDone != null) done.add(s.onDone);
			return s.frame > TOTAL_FRAMES + LINGER;
		});
		if (shuffles.isEmpty()) timer.stop();
		dirty.repaint(this, unionOfExtents());
		// After the list is settled, so an onDone that shuffles again starts cleanly.
		for (Runnable r : done) r.run();
	}

	/** Whether {@code deck} is being shuffled, its finished pile included while it lingers. */
	boolean isShuffling(Object deck) {
		for (Shuffle s : shuffles) if (s.key == deck) return true;
		return false;
	}

	/**
	 * The cards {@code deck}'s shuffle shows on its current frame, piles and falling packets
	 * together, or -1 when it is not being shuffled. A test reads it to check that no card goes
	 * missing on the way.
	 */
	int cardsShown(Object deck) {
		for (Shuffle s : shuffles) {
			if (s.key != deck) continue;
			int f = Math.min(s.frame, TOTAL_FRAMES);
			if (f == 0 || f > RIFFLE_END) return s.count;
			return s.held(true, f) + s.held(false, f) + s.landed(f) + s.falling(f);
		}
		return -1;
	}

	private Rectangle unionOfExtents() {
		Rectangle r = null;
		for (Shuffle s : shuffles) r = OverlayDirtyRegion.union(r, s.extent);
		return r;
	}

	@Override
	protected void paintComponent(Graphics g) {
		if (shuffles.isEmpty()) return;
		Graphics2D g2 = (Graphics2D) g.create();
		g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
		for (Shuffle s : shuffles) s.paint(g2);
		g2.dispose();
	}

	private static double smoothstep(double t) {
		t = Math.max(0, Math.min(1, t));
		return t * t * (3 - 2 * t);
	}
}
