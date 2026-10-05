package shufflingway.graphics;

import java.awt.Color;
import java.awt.Dimension;
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
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javax.swing.JComponent;
import javax.swing.SwingWorker;

import shufflingway.AppSettings;
import shufflingway.ImageCache;

/**
 * An opponent's hand drawn as a fan of face-down card backs peeking in from the board's outer edge.
 *
 * <p>Only the innermost {@link HandFanLayout#PEEK_FRACTION} of each card is inside the component;
 * the rest is clipped away against the screen edge. {@link HandFanLayout} decides the shape — this
 * class only paints cards into it.
 *
 * <p>Cards the opponent has revealed while they sat in hand are {@link Shown}: those are drawn face
 * up, upside down as the opponent holds them, in the leftmost slots in the order they were shown,
 * and hovering one previews it. The rest are backs. The fan is a picture of what is known about the
 * hand, not of its order, so a shown card's slot says nothing about where it sits in the hand. The
 * exact count lives in the tooltip and the fan itself carries no text. The seat's own hand is face
 * up and interactive, and is drawn by {@link PlayerHandFanPanel} instead.
 *
 * <p>{@code isP1} is still carried rather than assumed, because the seat decides which edge the
 * cards hang from and which way the fan tilts, and a hot-seat build could want backs on either side.
 */
public class HandFanPanel extends JComponent {

	// Every back is the same image, so without separation the overlaps read as one dark mass. A
	// plain outline is not enough: it has to work against the near-black default art *and* against
	// a light custom cardback. A drop shadow cast onto the card behind, plus a light edge, reads on
	// both — the shadow supplies depth where the art is light, the edge where the art is dark.
	private static final Color SHADOW = new Color(0, 0, 0, 110);
	private static final Color EDGE   = new Color(255, 255, 255, 60);
	/** Shadow offset, as a fraction of CARD_W — scales with the cards rather than the screen. */
	private static final double SHADOW_OFFSET_FRACTION = 0.018;

	/** Matching {@code PlayerHandFanPanel.FACE_SUPERSAMPLE}, so both fans resample the same way. */
	private static final int BACK_SUPERSAMPLE = 2;

	/**
	 * A card in the hand its owner's opponent has seen.
	 *
	 * @param handIdx where it sits in the hand, so a card leaving the hand can be animated out of the
	 *                slot it is drawn in
	 * @param url     its face
	 */
	public record Shown(int handIdx, String url) {}

	private final boolean         isP1;
	private final Supplier<Image> cardback;

	private int    count;
	/** Drawn face up in slots 0..size-1; see {@link Shown}. */
	private List<Shown> shown = List.of();
	/** Faces of the shown cards, by URL, at the size the back is built at. */
	private final Map<String, BufferedImage> faces   = new HashMap<>();
	/** URLs already handed to a loader, so a repaint cannot queue the same image twice. */
	private final Set<String>                loading = new HashSet<>();

	private Consumer<String> onPreview     = url -> {};
	private Runnable         onPreviewHide = () -> {};
	/** The shown slot whose preview is up, or -1. */
	private int previewSlot = -1;
	/** Cardback pre-scaled to card size, built lazily on first paint. See {@link #cardbackStale()}. */
	private BufferedImage back;
	/** Identity of whatever {@link #back} was built from; a mismatch invalidates the cache. */
	private String backKey = "";
	/** The whole fan as last painted, at device resolution; rebuilt when anything below changes. */
	private BufferedImage fan;
	private int    fanCount, fanW, fanH;
	private double fanSx, fanSy;

	/** @see HandFanLayout#peekHeight() */
	public static int peekHeight() {
		return HandFanLayout.peekHeight();
	}

	/**
	 * @param isP1     true for the bottom seat (cards peek up), false for the top seat (peek down)
	 * @param cardback supplies the raw cardback image; {@code MainWindow::loadCardbackImage} honours
	 *                 the custom-cardback preference, so this is re-consulted whenever it changes
	 */
	public HandFanPanel(boolean isP1, Supplier<Image> cardback) {
		this.isP1     = isP1;
		this.cardback = cardback;
		// Width 0 mirrors the forward zone's scroll pane: claim height only, never widen the column.
		setPreferredSize(new Dimension(0, peekHeight()));
		setMinimumSize(new Dimension(0, peekHeight()));
		setOpaque(false);
		setHand(0, List.of());

		MouseAdapter mouse = new MouseAdapter() {
			@Override public void mouseMoved(MouseEvent e)  { setPreviewSlot(slotAt(e.getPoint())); }
			@Override public void mouseEntered(MouseEvent e) { setPreviewSlot(slotAt(e.getPoint())); }
			@Override public void mouseExited(MouseEvent e)  { setPreviewSlot(-1); }
		};
		addMouseListener(mouse);
		addMouseMotionListener(mouse);
	}

	/**
	 * Where hovering a shown card sends its face, and what clears it again — the side-panel preview.
	 */
	public void setPreview(Consumer<String> show, Runnable hide) {
		onPreview     = show != null ? show : url -> {};
		onPreviewHide = hide != null ? hide : () -> {};
	}

	/**
	 * Updates the hand: {@code n} cards, of which {@code shown} are drawn face up. Refreshes the
	 * tooltip, and repaints only if something changed.
	 */
	public final void setHand(int n, List<Shown> shownCards) {
		List<Shown> next = new ArrayList<>();
		for (Shown s : shownCards)
			if (s.handIdx() >= 0 && s.handIdx() < n && next.size() < n) next.add(s);
		boolean changed = n != count || !next.equals(shown);
		count = n;
		shown = List.copyOf(next);
		// Assigned unconditionally: the constructor's seeding call must leave a correct tooltip.
		setToolTipText((isP1 ? "P1" : "P2") + " Hand: " + n
				+ (shown.isEmpty() ? "" : " (" + shown.size() + " revealed)"));
		if (changed) {
			List<String> urls = new ArrayList<>();
			for (Shown s : shown) urls.add(s.url());
			faces.keySet().retainAll(urls);
			fan = null;
			// The card under the pointer may have just left, or moved slot.
			if (previewSlot >= 0) { previewSlot = -1; onPreviewHide.run(); }
		}
		if (cardbackStale() || changed) repaint();
	}

	/** The slot card {@code handIdx} is drawn in: shown cards first, then the rest in hand order. */
	private int slotOf(int handIdx) {
		int hidden = 0;
		for (int i = 0; i < shown.size(); i++) if (shown.get(i).handIdx() == handIdx) return i;
		for (int i = 0; i < handIdx; i++) if (!isShown(i)) hidden++;
		return shown.size() + hidden;
	}

	private boolean isShown(int handIdx) {
		for (Shown s : shown) if (s.handIdx() == handIdx) return true;
		return false;
	}

	/** The topmost slot covering {@code p}, or -1. The leftmost card is on top, so that is the first hit. */
	private int slotAt(Point p) {
		int w = getWidth(), h = getHeight();
		if (count <= 0 || w <= 0 || h <= 0) return -1;
		HandFanLayout.Slot[] slots = HandFanLayout.slots(count, w, isP1, HandFanLayout.restTop(isP1, h));
		Shape outline = outline();
		for (int i = 0; i < slots.length; i++)
			if (HandFanLayout.transformFor(slots[i]).createTransformedShape(outline).contains(p)) return i;
		return -1;
	}

	/** Shows the preview for {@code slot} if it holds a shown card, and clears it otherwise. */
	private void setPreviewSlot(int slot) {
		int target = slot >= 0 && slot < shown.size() ? slot : -1;
		if (target == previewSlot) return;
		previewSlot = target;
		if (target >= 0) onPreview.accept(shown.get(target).url());
		else             onPreviewHide.run();
	}

	private static Shape outline() {
		int cw = CardAnimation.CARD_W, ch = CardAnimation.CARD_H;
		double diameter = Math.min(cw, ch) * CardAnimation.CORNER_RADIUS_FRACTION * 2.0;
		return new RoundRectangle2D.Double(0, 0, cw - 1, ch - 1, diameter, diameter);
	}

	/**
	 * Detects a cardback change and drops the cache if it finds one.
	 *
	 * <p>Nothing notifies us when the preference changes, so — like the deck labels, which simply
	 * reload on every refresh — we re-derive from {@link AppSettings} instead of being told. The
	 * file's length and timestamp join the path because Preferences copies the chosen image to a
	 * fixed destination name: re-picking a <em>different</em> file with the <em>same</em> filename
	 * yields an identical path. Card size joins it so a UI-scale change rebuilds too.
	 */
	private boolean cardbackStale() {
		String path = AppSettings.getCustomCardbackPath();
		String size = "|" + CardAnimation.CARD_W + "x" + CardAnimation.CARD_H;
		String key;
		if (path.isEmpty()) {
			key = "default" + size;
		} else {
			File f = new File(path);
			key = path + "|" + f.length() + "|" + f.lastModified() + size;
		}
		if (key.equals(backKey)) return false;
		backKey = key;
		back    = null;   // rebuilt lazily on the next paint
		return true;
	}

	/**
	 * Centre of hand card {@code handIdx} in this panel's coordinates, or {@code null} outside the
	 * fan. Most of the card lies past the screen edge, so a slide starting here comes out of the hand.
	 */
	public Point cardCenter(int handIdx) {
		int w = getWidth(), h = getHeight();
		if (handIdx < 0 || handIdx >= count || w <= 0 || h <= 0) return null;
		HandFanLayout.Slot s = HandFanLayout.slots(count, w, isP1, HandFanLayout.restTop(isP1, h))[slotOf(handIdx)];
		return new Point((int) Math.round(s.cx()), (int) Math.round(s.cy()));
	}

	@Override
	protected void paintComponent(Graphics g0) {
		super.paintComponent(g0);
		int w = getWidth(), h = getHeight();
		if (count <= 0 || w <= 0 || h <= 0) return;

		// Built here rather than in the constructor: tests construct the window without ever
		// painting it, and an eager decode would cost every one of them an image load.
		if (back == null) {
			Image raw = cardback.get();
			if (raw == null) return;
			// Oversized, and scaled back down by the slot transform below — see
			// PlayerHandFanPanel.FACE_SUPERSAMPLE for why a fanned card wants that and a card on
			// the field does not. One image for the whole fan, so it is nearly free here.
			int src = raw.getWidth(null);
			int cap = src > 0 ? Math.max(CardAnimation.CARD_W, src) : Integer.MAX_VALUE;
			int bw  = Math.min(CardAnimation.CARD_W * BACK_SUPERSAMPLE, cap);
			int bh  = (int) Math.round(bw * (double) CardAnimation.CARD_H / CardAnimation.CARD_W);
			back = CardAnimation.toARGB(raw, bw, bh);
			fan  = null;
		}

		// Device pixels per user pixel, so the cached fan is rendered at the screen's real
		// resolution and blitted back 1:1 rather than resampled.
		AffineTransform dev = ((Graphics2D) g0).getTransform();
		double sx = dev.getScaleX(), sy = dev.getScaleY();
		if (fan == null || fanCount != count || fanW != w || fanH != h || fanSx != sx || fanSy != sy) {
			fan = renderFan(w, h, sx, sy);
			fanCount = count; fanW = w; fanH = h; fanSx = sx; fanSy = sy;
		}
		Graphics2D g = (Graphics2D) g0.create();
		g.scale(1 / sx, 1 / sy);
		g.drawImage(fan, 0, 0, null);
		g.dispose();
	}

	/**
	 * Paints the fan into an image {@code scaleX}/{@code scaleY} times the panel's size. Every back is
	 * a rotated, supersampled resample that the screen pipeline cannot accelerate, so drawing the fan
	 * live cost a software transform per card on every repaint — and an animation overlay repaints
	 * whatever lies beneath it.
	 */
	private BufferedImage renderFan(int w, int h, double scaleX, double scaleY) {
		BufferedImage img = new BufferedImage(Math.max(1, (int) Math.round(w * scaleX)),
				Math.max(1, (int) Math.round(h * scaleY)), BufferedImage.TYPE_INT_ARGB_PRE);
		Graphics2D g = img.createGraphics();
		g.scale(scaleX, scaleY);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,   RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,  RenderingHints.VALUE_INTERPOLATION_BICUBIC);
		g.setRenderingHint(RenderingHints.KEY_RENDERING,      RenderingHints.VALUE_RENDER_QUALITY);

		int cw = CardAnimation.CARD_W;
		int ch = CardAnimation.CARD_H;

		HandFanLayout.Slot[] slots =
				HandFanLayout.slots(count, w, isP1, HandFanLayout.restTop(isP1, h));

		Shape  outline   = outline();
		double shadowOff = cw * SHADOW_OFFSET_FRACTION;
		double dir       = isP1 ? 1 : -1;

		// Right to left, so the leftmost card sits on top: an opponent's fan seen from across the
		// table overlaps the other way round from your own. It is also what keeps a shown card
		// readable, since upside down its cost is in the corner a right-hand neighbour would cover.
		for (int i = slots.length - 1; i >= 0; i--) {
			HandFanLayout.Slot slot = slots[i];
			AffineTransform tx = HandFanLayout.transformFor(slot);

			// Shadow first, so it falls on the card already drawn to the left; the card then covers
			// all of its own shadow but the offset sliver. Cast away from the screen edge, i.e. in
			// the direction the cards actually stand out.
			AffineTransform sx = new AffineTransform(tx);
			sx.preConcatenate(AffineTransform.getTranslateInstance(shadowOff, dir * shadowOff));
			g.setColor(SHADOW);
			g.fill(sx.createTransformedShape(outline));

			// A shown card faces the way its owner holds it, so from this side it reads upside down
			// — and the end that peeks out of the hand is its top, the cost and the name. One still
			// loading draws as a back until it arrives.
			BufferedImage face = i < shown.size() ? face(shown.get(i).url()) : null;
			BufferedImage art  = face != null ? face : back;
			AffineTransform bx = new AffineTransform(tx);
			if (face != null) bx.rotate(Math.PI, cw / 2.0, ch / 2.0);
			bx.scale(cw / (double) art.getWidth(), ch / (double) art.getHeight());
			g.drawImage(art, bx, null);
			g.setColor(EDGE);
			g.draw(tx.createTransformedShape(outline));
		}

		g.dispose();
		return img;
	}

	/**
	 * The decoded face for {@code url}, or {@code null} while it is still loading. Decoding happens
	 * off the EDT; its arrival drops the cached fan so the next paint draws the face in.
	 */
	private BufferedImage face(String url) {
		if (url == null) return null;
		BufferedImage cached = faces.get(url);
		if (cached != null || !loading.add(url)) return cached;

		new SwingWorker<BufferedImage, Void>() {
			@Override protected BufferedImage doInBackground() throws Exception {
				Image raw = ImageCache.load(url);
				if (raw == null) return null;
				// Sized as the back is, for the same reason — see BACK_SUPERSAMPLE.
				int src = raw.getWidth(null);
				int cap = src > 0 ? Math.max(CardAnimation.CARD_W, src) : Integer.MAX_VALUE;
				int fw  = Math.min(CardAnimation.CARD_W * BACK_SUPERSAMPLE, cap);
				int fh  = (int) Math.round(fw * (double) CardAnimation.CARD_H / CardAnimation.CARD_W);
				return CardAnimation.toARGB(raw, fw, fh);
			}
			@Override protected void done() {
				loading.remove(url);
				try {
					BufferedImage img = get();
					if (img == null) return;
					boolean stillShown = shown.stream().anyMatch(s -> url.equals(s.url()));
					if (!stillShown) return;
					faces.put(url, img);
					fan = null;
					repaint();
				} catch (InterruptedException | ExecutionException ignored) {}
			}
		}.execute();
		return null;
	}
}
