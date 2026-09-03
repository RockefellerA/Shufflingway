package shufflingway.graphics;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.IntFunction;

import javax.swing.JComponent;
import javax.swing.SwingWorker;
import javax.swing.Timer;

import shufflingway.FontLoader;
import shufflingway.ImageCache;

/**
 * The seat's own hand, fanned face up along the bottom edge of the board.
 *
 * <p>The mirror of {@link HandFanPanel}, and deliberately a separate component rather than a flag
 * on it: your own hand is read, not counted. It shows faces, it says which cards you can afford and
 * what they would actually cost, and every card in it is a thing to click. None of that belongs in
 * the component that draws an opponent's card backs.
 *
 * <h2>Rising out of the board</h2>
 * At rest a card shows the same peek as the opponent's fan — for a face-up card that peek is the
 * top of it, which is exactly the cost and the name. Hovering raises one card clear of the others
 * by {@link #HOVER_RISE_FRACTION} so more of the art is readable without leaving the board.
 *
 * <p>The rise is animated over {@link #RISE_MILLIS}, and so is the fall back. Each card carries its
 * own progress rather than the panel carrying one for "the hovered card", because moving between
 * two cards has to lift one while the other is still settling — and a card interrupted halfway
 * turns around from where it is rather than snapping to an end.
 *
 * <p>That rise needs vertical room the bottom band does not have, so the panel is laid out taller
 * than its resting peek and simply paints upward over the backup row. To keep that overhang from
 * swallowing clicks meant for the backups underneath, {@link #contains(int, int)} reports only the
 * pixels a card actually occupies — so the panel is transparent to the mouse everywhere else, and
 * the card you click is the card drawn on top.
 */
public class PlayerHandFanPanel extends JComponent {

	/** How far a hovered card rises, as a fraction of {@code CARD_H}. */
	private static final double HOVER_RISE_FRACTION = 0.22;

	/** How long a card takes to rise fully, and to fall back, in milliseconds. */
	private static final int RISE_MILLIS = 250;
	/** Animation frame interval — 60fps, which is smooth without repainting the board harder. */
	private static final int FRAME_MILLIS = 16;

	/**
	 * Extra height claimed above the resting peek, which is what the rise moves through. The panel
	 * overlaps whatever is above it by this much; nothing is painted there until a card is hovered.
	 */
	public static int overhangHeight() {
		return (int) Math.round(CardAnimation.CARD_H * HOVER_RISE_FRACTION);
	}

	/** Total height the panel wants: the visible peek plus the headroom a hovered card rises into. */
	public static int panelHeight() {
		return HandFanLayout.peekHeight() + overhangHeight();
	}

	// Matching HandFanPanel, so the two hands separate their overlaps the same way.
	private static final Color SHADOW = new Color(0, 0, 0, 110);
	private static final Color EDGE   = new Color(255, 255, 255, 60);
	private static final double SHADOW_OFFSET_FRACTION = 0.018;

	/**
	 * Resolution the faces are decoded at, as a multiple of the card size.
	 *
	 * <p>A card on the field is blitted 1:1 at whole-pixel coordinates, so its face reaches the
	 * screen exactly as it was decoded. A card in the fan never is: {@link HandFanLayout} tilts it
	 * and centres it on fractional coordinates, so even the upright middle card of a fan gets
	 * resampled on the way out. Decoded at card size that is a second resample of an already
	 * downscaled face, and it reads visibly softer than the same card on the board.
	 *
	 * <p>Decoding oversized and letting {@link #paintCard} fold the reduction into the same
	 * transform that rotates it spends the detail once instead of twice, off a source that still
	 * has detail left to spend. 2x is enough: the source art is only about 2.3x the card.
	 */
	private static final int FACE_SUPERSAMPLE = 2;

	/** Ring drawn around a card you can actually pay for right now. */
	private static final Color PLAYABLE_GLOW = new Color(30, 144, 255);
	/** A cost that an effect has brought down, and one an effect has pushed up. */
	private static final Color COST_DOWN = new Color(0x44EE44);
	private static final Color COST_UP   = new Color(0xFF8844);

	/**
	 * What the rules currently say about a card in hand: what it would cost to cast right now
	 * against what is printed on it, and whether it can be cast at all.
	 *
	 * <p>Every judgement here is the window's to make — affordability, timing, name conflicts — so
	 * the panel is handed the answers rather than the rules.
	 *
	 * <p>Asked afresh on every paint rather than stored alongside the card, because all three
	 * answers move under a hand that has not itself changed: the phase advances into a window where
	 * a Summon may be cast, a Backup dulls for CP, a cost-reducing Forward enters. Cached, the fan
	 * would keep showing whatever was true when the hand last gained or lost a card — which is
	 * during the Draw phase, when nothing is castable at all.
	 */
	public record State(int baseCost, int effectiveCost, boolean playable) {
		/** What an index outside the hand reports, so a stale repaint draws nothing alarming. */
		public static final State UNKNOWN = new State(0, 0, false);
	}

	private final Consumer<Integer>               onHoverChanged;
	private final BiConsumer<Integer, MouseEvent> onCardPressed;
	private final IntFunction<State>              stateOf;

	/** The hand itself: one image URL per card. Changes only when the hand does. */
	private List<String> cards = List.of();
	/** Faces by image URL, decoded once and rounded to the card silhouette. */
	private final Map<String, BufferedImage> faces = new HashMap<>();
	/** URLs already handed to a loader, so a repaint storm cannot queue the same image repeatedly. */
	private final java.util.Set<String> loading = new java.util.HashSet<>();

	/** Index of the card under the pointer, or -1. The only thing that moves a card. */
	private int hovered = -1;
	/**
	 * How far each card has risen, 0 seated to 1 fully out. One entry per card, kept in step with
	 * {@link #cards}; the timer walks these toward their targets and stops once they all arrive.
	 */
	private double[] rise = new double[0];
	private final Timer riseTimer;
	/** When the last frame ran, so the rise keeps to wall-clock time rather than to frame count. */
	private long lastFrameNanos;
	/**
	 * Holds the hover where it is while a card's menu is open. The menu takes the pointer off the
	 * fan the instant it appears, and without this the card you are choosing an action for drops
	 * back into the hand and takes its preview with it.
	 */
	private boolean hoverFrozen;

	/**
	 * @param stateOf        asked, per hand index, what that card costs and whether it can be cast;
	 *                       consulted on every paint, so it must be cheap and side-effect free
	 * @param onHoverChanged fired with the newly hovered index, or -1 when the pointer leaves every
	 *                       card — drives the side-panel preview
	 * @param onCardPressed  fired with the index and the original event, for the card menu
	 */
	public PlayerHandFanPanel(IntFunction<State> stateOf, Consumer<Integer> onHoverChanged,
			BiConsumer<Integer, MouseEvent> onCardPressed) {
		this.stateOf        = stateOf;
		this.onHoverChanged = onHoverChanged;
		this.onCardPressed  = onCardPressed;
		// Width 0 mirrors HandFanPanel: claim height only, never widen the column.
		setPreferredSize(new Dimension(0, panelHeight()));
		setMinimumSize(new Dimension(0, panelHeight()));
		setOpaque(false);
		setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

		MouseAdapter mouse = new MouseAdapter() {
			@Override public void mouseMoved(MouseEvent e)    { setHovered(cardAt(e.getPoint())); }
			@Override public void mouseDragged(MouseEvent e)  { setHovered(cardAt(e.getPoint())); }
			@Override public void mouseEntered(MouseEvent e)  { setHovered(cardAt(e.getPoint())); }
			@Override public void mouseExited(MouseEvent e)   { setHovered(-1); }
			@Override public void mousePressed(MouseEvent e) {
				int i = cardAt(e.getPoint());
				if (i >= 0) PlayerHandFanPanel.this.onCardPressed.accept(i, e);
			}
		};
		addMouseListener(mouse);
		addMouseMotionListener(mouse);

		riseTimer = new Timer(FRAME_MILLIS, e -> stepRise());
		riseTimer.setCoalesce(true);
	}

	/**
	 * Moves every card one frame toward where it should be, and stops once they are all there.
	 *
	 * <p>Stepped against elapsed wall-clock time so the duration holds whatever the frame rate
	 * does, but capped at a whole rise per frame: after a stall — the window dragged, a long GC —
	 * an uncapped step would teleport the card instead of moving it.
	 */
	private void stepRise() {
		long now = System.nanoTime();
		double elapsedMs = (now - lastFrameNanos) / 1_000_000.0;
		lastFrameNanos = now;
		double step = Math.min(elapsedMs, RISE_MILLIS) / RISE_MILLIS;

		boolean stillMoving = false;
		for (int i = 0; i < rise.length; i++) {
			double target = (i == hovered) ? 1 : 0;
			if (rise[i] == target) continue;
			rise[i] = rise[i] < target ? Math.min(target, rise[i] + step)
			                           : Math.max(target, rise[i] - step);
			if (rise[i] != target) stillMoving = true;
		}
		repaint();
		if (!stillMoving) riseTimer.stop();
	}

	/** Starts the frame timer if it is not already running, without disturbing a run in progress. */
	private void startRiseAnimation() {
		if (riseTimer.isRunning()) return;
		lastFrameNanos = System.nanoTime();
		riseTimer.start();
	}

	/** How far card {@code i} has risen; 0 for anything outside the hand. */
	private double riseOf(int i) {
		return i >= 0 && i < rise.length ? rise[i] : 0;
	}

	/**
	 * Smoothstep, so a card eases up out of the hand and settles rather than moving at a flat rate.
	 * Applied to the progress at read time, which is what lets an interrupted card reverse smoothly:
	 * the progress itself stays continuous, so the eased position does too.
	 */
	private static double eased(double t) {
		return t * t * (3 - 2 * t);
	}

	/**
	 * Orders cards bottom to top: one that has risen further is drawn over one that has not, and
	 * cards level with each other stack left to right so the rightmost sits on top.
	 *
	 * <p>Painting and hit testing both read this. That is the whole point — mid-animation the card
	 * on top is not simply the hovered one, and the card you can grab has to be the card you see.
	 */
	private final Comparator<Integer> stackingOrder = (a, b) -> {
		int byRise = Double.compare(riseOf(a), riseOf(b));
		return byRise != 0 ? byRise : Integer.compare(a, b);
	};

	/**
	 * Replaces the hand with {@code imageUrls}, in hand order.
	 *
	 * <p>A null entry is allowed and means a card whose artwork could not be resolved: it keeps its
	 * place in the fan and draws as a blank, which is what {@link #face(String)} already returns for
	 * one. Hence the null-tolerant copy rather than {@code List.copyOf}.
	 */
	public void setCards(List<String> imageUrls) {
		cards = java.util.Collections.unmodifiableList(new java.util.ArrayList<>(imageUrls));
		// Drop faces for cards that have left, so the cache is bounded by the hand rather than by
		// every card that has ever been in it. At FACE_SUPERSAMPLE a face is four times the pixels
		// it used to be, which is small per card and not small across a game's worth of them.
		faces.keySet().retainAll(cards);
		if (hovered >= cards.size()) hovered = -1;
		// Cards that kept their index keep their rise, so a hand changing under a raised card does
		// not drop it; anything new starts seated. The next mouse move re-decides regardless.
		rise = Arrays.copyOf(rise, cards.size());
		setToolTipText(cards.isEmpty() ? null : "Your hand: " + cards.size());
		repaint();
	}

	public int cardCount() { return cards.size(); }

	private void setHovered(int i) {
		if (hoverFrozen || i == hovered) return;
		hovered = i;
		// The preview follows the pointer at once; only the card's position is animated.
		onHoverChanged.accept(i);
		startRiseAnimation();
		repaint();
	}

	/**
	 * Freezes or releases the hover. Releasing re-reads the pointer, so a card whose menu was
	 * dismissed with the mouse elsewhere settles back down instead of staying raised.
	 */
	public void setHoverFrozen(boolean frozen) {
		if (frozen == hoverFrozen) return;
		hoverFrozen = frozen;
		if (frozen) return;
		Point p = getMousePosition();
		setHovered(p == null ? -1 : cardAt(p));
	}

	// -----------------------------------------------------------------------------------------
	// Geometry and hit testing
	// -----------------------------------------------------------------------------------------

	/**
	 * Where each card sits right now, part-way through its rise included. Shared by painting and hit
	 * testing so the two cannot disagree — a card half out of the hand must be grabbable half out of
	 * the hand, not at either end of the move.
	 */
	private HandFanLayout.Slot[] currentSlots() {
		HandFanLayout.Slot[] slots = HandFanLayout.slots(
				cards.size(), getWidth(), true, HandFanLayout.restTop(true, getHeight()));
		for (int i = 0; i < slots.length; i++) {
			double r = riseOf(i);
			if (r <= 0) continue;
			HandFanLayout.Slot s = slots[i];
			slots[i] = new HandFanLayout.Slot(
					s.cx(), s.cy() - eased(r) * overhangHeight(), s.theta());
		}
		return slots;
	}

	/** The card silhouette in card-local coordinates, matching the rounding {@code toARGB} applies. */
	private static Shape outline() {
		int cw = CardAnimation.CARD_W, ch = CardAnimation.CARD_H;
		double d = Math.min(cw, ch) * CardAnimation.CORNER_RADIUS_FRACTION * 2.0;
		return new RoundRectangle2D.Double(0, 0, cw - 1, ch - 1, d, d);
	}

	/**
	 * Index of the topmost card covering {@code p}, or -1 — topmost by {@link #stackingOrder}, so
	 * the answer is the card actually drawn at that pixel however far through a rise it is.
	 */
	private int cardAt(Point p) {
		HandFanLayout.Slot[] slots = currentSlots();
		Shape outline = outline();
		int best = -1;
		for (int i = 0; i < slots.length; i++) {
			if (!hits(slots[i], outline, p)) continue;
			if (best < 0 || stackingOrder.compare(i, best) > 0) best = i;
		}
		return best;
	}

	private static boolean hits(HandFanLayout.Slot slot, Shape outline, Point p) {
		return HandFanLayout.transformFor(slot).createTransformedShape(outline).contains(p);
	}

	/**
	 * Restricts the component's mouse footprint to the cards themselves.
	 *
	 * <p>The panel is laid out overlapping the backup row so a hovered card has somewhere to rise
	 * into. Without this, that overhang would sit invisibly in front of the backups and eat their
	 * clicks; with it, the panel is only "there" where a card is drawn.
	 */
	@Override
	public boolean contains(int x, int y) {
		return cardAt(new Point(x, y)) >= 0;
	}

	// -----------------------------------------------------------------------------------------
	// Painting
	// -----------------------------------------------------------------------------------------

	@Override
	protected void paintComponent(Graphics g0) {
		super.paintComponent(g0);
		int w = getWidth(), h = getHeight();
		if (cards.isEmpty() || w <= 0 || h <= 0) return;

		Graphics2D g = (Graphics2D) g0.create();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,       RenderingHints.VALUE_ANTIALIAS_ON);
		// Bicubic, not bilinear: this pass is a reduction as well as a rotation, and bilinear's
		// 2x2 tap undersamples a reduction. See FACE_SUPERSAMPLE.
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,      RenderingHints.VALUE_INTERPOLATION_BICUBIC);
		g.setRenderingHint(RenderingHints.KEY_RENDERING,          RenderingHints.VALUE_RENDER_QUALITY);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,  RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

		HandFanLayout.Slot[] slots = currentSlots();
		Shape  outline   = outline();
		double shadowOff = CardAnimation.CARD_W * SHADOW_OFFSET_FRACTION;

		List<Integer> order = new ArrayList<>(slots.length);
		for (int i = 0; i < slots.length; i++) order.add(i);
		order.sort(stackingOrder);

		for (int i : order) paintCard(g, i, slots[i], outline, shadowOff);
		g.dispose();
	}

	private void paintCard(Graphics2D g, int index, HandFanLayout.Slot slot, Shape outline,
			double shadowOff) {
		String url   = cards.get(index);
		State  state = stateOf.apply(index);
		if (state == null) state = State.UNKNOWN;
		AffineTransform tx = HandFanLayout.transformFor(slot);

		AffineTransform sx = new AffineTransform(tx);
		sx.preConcatenate(AffineTransform.getTranslateInstance(shadowOff, shadowOff));
		g.setColor(SHADOW);
		g.fill(sx.createTransformedShape(outline));

		BufferedImage face = face(url);
		if (face != null) {
			// The face is oversized, so the slot transform carries the reduction down with it:
			// one resample down and round, not one of each. Read off the image rather than
			// assuming FACE_SUPERSAMPLE, which face() is free to cap.
			AffineTransform fx = new AffineTransform(tx);
			fx.scale(CardAnimation.CARD_W / (double) face.getWidth(),
					CardAnimation.CARD_H / (double) face.getHeight());
			g.drawImage(face, fx, null);
		} else {
			// Placeholder until the face arrives, so the fan has the right shape from the first paint.
			g.setColor(Color.DARK_GRAY);
			g.fill(tx.createTransformedShape(outline));
		}

		// The overlays go on in the card's own frame, so they tilt with it rather than floating
		// upright over a rotated card.
		Graphics2D gc = (Graphics2D) g.create();
		gc.transform(tx);
		int delta = state.baseCost() - state.effectiveCost();
		if (delta != 0) drawCostPill(gc, state.effectiveCost(), delta);
		if (state.playable())
			CardAnimation.drawRoundedGlow(gc, PLAYABLE_GLOW, 0, 0,
					CardAnimation.CARD_W, CardAnimation.CARD_H);
		gc.dispose();

		g.setColor(EDGE);
		g.draw(tx.createTransformedShape(outline));
	}

	/** The adjusted cost, over the printed one — green when an effect made it cheaper, orange dearer. */
	private static void drawCostPill(Graphics2D g, int effectiveCost, int delta) {
		String text = String.valueOf(effectiveCost);
		g.setFont(FontLoader.loadOverlayFont(15));
		FontMetrics fm = g.getFontMetrics();
		int x = 8, y = fm.getAscent() + 7;
		g.setColor(Color.BLACK);
		g.drawString(text, x + 1, y + 1);
		g.drawString(text, x + 2, y + 1);
		g.drawString(text, x + 1, y + 2);
		g.drawString(text, x + 2, y + 2);
		g.setColor(delta > 0 ? COST_DOWN : COST_UP);
		g.drawString(text, x, y);
	}

	/**
	 * The decoded face for {@code url}, or {@code null} while it is still loading.
	 *
	 * <p>Decoding happens off the EDT and the result repaints the panel; {@link #loading} keeps a
	 * url from being queued twice, which matters because this is reached from paint and paint runs
	 * far more often than the hand changes.
	 */
	private BufferedImage face(String url) {
		if (url == null) return null;
		BufferedImage cached = faces.get(url);
		if (cached != null || !loading.add(url)) return cached;

		new SwingWorker<BufferedImage, Void>() {
			@Override protected BufferedImage doInBackground() throws Exception {
				Image raw = ImageCache.load(url);
				if (raw == null) return null;
				// Never decode larger than the source art: at a high UI scale the full
				// supersample would enlarge a 429px scan only to shrink it again, which costs
				// the memory and returns no detail. A source of unknown width (-1) is not a
				// case ImageCache produces, but it should not silently disable the supersample.
				int src = raw.getWidth(null);
				int cap = src > 0 ? Math.max(CardAnimation.CARD_W, src) : Integer.MAX_VALUE;
				int w   = Math.min(CardAnimation.CARD_W * FACE_SUPERSAMPLE, cap);
				int h   = (int) Math.round(w * (double) CardAnimation.CARD_H / CardAnimation.CARD_W);
				return CardAnimation.toARGB(raw, w, h);
			}
			@Override protected void done() {
				loading.remove(url);
				try {
					BufferedImage img = get();
					if (img != null) { faces.put(url, img); repaint(); }
				} catch (InterruptedException | ExecutionException ignored) {}
			}
		}.execute();
		return null;
	}
}
