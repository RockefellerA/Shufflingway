package shufflingway.graphics;

import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.ComponentListener;
import java.awt.event.HierarchyEvent;
import java.awt.event.HierarchyListener;
import java.awt.geom.Rectangle2D;
import java.beans.PropertyChangeListener;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.function.ToIntFunction;

import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JLayeredPane;
import javax.swing.JScrollPane;
import javax.swing.JViewport;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.ChangeListener;

import shufflingway.CardState;

/**
 * Transparent overlay on the frame's PALETTE_LAYER that draws the {@link NotReadyMarker} ATB gauge
 * of each character that is not ready yet, just off the card's centre-facing edge — above P1's
 * cards and below P2's — where the board has room and the card's own face stays clear.
 *
 * <p>The card's slot cannot draw it there: the Forward row lives in a scroll pane whose viewport
 * clips to the cards. So, like {@link SlotLiftOverlay}, this paints from the layered pane, following
 * the slot as the row scrolls or is laid out again, and moving with it when combat raises it — a
 * Forward that is not ready may still block. Sideways it stays inside the row's viewport, so a card
 * scrolled out of view takes its gauge with it.
 *
 * <p>A Forward's or Monster's gauge sits off its slot's edge. A Backup's hugs the card instead
 * ({@link #show(Object, JLabel, Integer, boolean, boolean)}): a dull card lies across its square
 * slot with free canvas above and below the art, and off the slot's edge the gauge would sit
 * against the Forward row — touching P1's Forwards and running into P2's.
 *
 * <p>Gauges are keyed by the card, not the label, as lifts are: the Forward row rebuilds every label
 * when a Forward leaves, and a gauge part-way through a rise carries on under the new label. A card
 * whose label leaves the tree without a new one has left the field, and its gauge goes too. A
 * Backup's slot keeps its label when the card leaves and empties its icon instead, which drops the
 * gauge the same way.
 *
 * <p>Read and written under the AWT tree lock, for the reason {@link SlotLiftOverlay} gives: tests
 * drive the board from their own thread while the timer ticks on the event thread.
 */
public class NotReadyGaugeOverlay extends JComponent {

	private static final int FRAME_MS = 16;

	private static final class Gauge {
		JLabel      slot;
		JViewport   viewport;
		JScrollPane scroll;
		boolean     below;
		/** Off the edge of the card itself rather than of its slot; see {@link #show(Object, JLabel, Integer, boolean, boolean)}. */
		boolean     hugsCard;
		/** The state the slot last drew its card in, kept through a turn, whose frames carry none. */
		CardState   drawnState;
		/** Phases gone by as shown at rest; {@code null} once the card is ready and only its clearing is left. */
		Integer     phases;
		/** When the running rise or clearing started, in {@link System#nanoTime}, or -1. */
		long        startNs = -1;
		boolean     clearing;
		double      from;
		double      to;
		/** Overlay coordinates; repainted when the gauge moves or goes. */
		Rectangle   lastDrawn;
	}

	private final ToIntFunction<JLabel> liftOffset;
	private final Map<Object, Gauge>    gauges = new IdentityHashMap<>();
	private final Timer                 timer;
	private final ComponentListener     moveListener = new ComponentAdapter() {
		@Override public void componentMoved(ComponentEvent e)   { repaint(); }
		@Override public void componentResized(ComponentEvent e) { repaint(); }
	};
	private final ChangeListener        scrollListener = e -> repaint();
	/**
	 * A slot leaving the tree is dropped on the next event rather than at once, so a row rebuild
	 * that shows the same card under a new label in the same event keeps its gauge.
	 */
	private final HierarchyListener     detachListener = e -> {
		if ((e.getChangeFlags() & HierarchyEvent.PARENT_CHANGED) == 0) return;
		if (e.getComponent().getParent() == null) SwingUtilities.invokeLater(this::pruneDetached);
	};
	/**
	 * A slot emptied of its card drops the gauge beside it, on the next event as a detached one is.
	 * Any other new icon is the card drawn again — perhaps turned — so its gauge is drawn again too.
	 */
	private final PropertyChangeListener iconListener = e -> {
		JLabel slot = (JLabel) e.getSource();
		if (e.getNewValue() != null)      slotMoved(slot);
		else if (e.getOldValue() != null) SwingUtilities.invokeLater(() -> pruneEmptied(slot));
	};

	/**
	 * @param liftOffset how far a slot is drawn above its laid-out position right now, negative
	 *                   below it — {@link SlotLiftOverlay#offset}
	 */
	public NotReadyGaugeOverlay(ToIntFunction<JLabel> liftOffset) {
		this.liftOffset = liftOffset;
		setOpaque(false);
		setFocusable(false);
		timer = new Timer(FRAME_MS, e -> tick());
		timer.setCoalesce(true);
	}

	/** Installs the overlay on {@code frame}'s layered pane, following the slots {@code lift} raises. */
	public static NotReadyGaugeOverlay install(JFrame frame, SlotLiftOverlay lift) {
		NotReadyGaugeOverlay o  = new NotReadyGaugeOverlay(lift::offset);
		JLayeredPane         lp = frame.getRootPane().getLayeredPane();
		o.setBounds(0, 0, lp.getWidth(), lp.getHeight());
		lp.add(o, JLayeredPane.PALETTE_LAYER);
		lp.addComponentListener(new ComponentAdapter() {
			@Override public void componentResized(ComponentEvent e) {
				o.setBounds(0, 0, lp.getWidth(), lp.getHeight());
			}
		});
		lift.setSlotMovedListener(o::slotMoved);
		return o;
	}

	/**
	 * Shows {@code card}'s gauge beside {@code slot}, {@code phasesGone} phases full — or none when
	 * {@code null}, the card being ready or never having waited. Below the slot when {@code below}
	 * (P2's side), above it otherwise. Compared with what the card showed before:
	 * <ul>
	 *   <li>further along, the gauge rises to its new level over {@link NotReadyMarker#RISE_MS};</li>
	 *   <li>gone, the card has become ready, and the gauge clears;</li>
	 *   <li>back after a clearing began — Haste lost again — it is simply shown.</li>
	 * </ul>
	 * Either animation starts from the gauge as it looks at that moment, so a rise still running hands
	 * over to the next one, or to the clearing, without a jump. Showing what is already shown changes
	 * nothing, so this may be called as often as the slot is redrawn.
	 */
	public void show(Object card, JLabel slot, Integer phasesGone, boolean below) {
		show(card, slot, phasesGone, below, false);
	}

	/**
	 * {@link #show(Object, JLabel, Integer, boolean)}, off the edge of the card itself when
	 * {@code hugsCard} — a Backup's. While the slot draws its card dull, lying across the square
	 * slot, that is the edge of the art, in the free canvas above or below it; upright, the card fills
	 * its slot and the two edges are one.
	 *
	 * <p>A slot shows one card: a gauge still beside it from a card that has gone is dropped, with no
	 * clearing, which would read as that card becoming ready.
	 */
	public void show(Object card, JLabel slot, Integer phasesGone, boolean below, boolean hugsCard) {
		synchronized (getTreeLock()) {
			for (Iterator<Map.Entry<Object, Gauge>> it = gauges.entrySet().iterator(); it.hasNext(); ) {
				Map.Entry<Object, Gauge> e = it.next();
				if (e.getKey() == card || e.getValue().slot != slot) continue;
				drop(e.getValue());
				it.remove();
			}
			Gauge g = gauges.get(card);
			if (g == null) {
				if (phasesGone == null) return;
				g = new Gauge();
				g.phases   = phasesGone;
				g.below    = below;
				g.hugsCard = hugsCard;
				gauges.put(card, g);
				attach(slot, g);
				repaintGauge(g);
				return;
			}
			if (g.slot != slot) {
				repaintGauge(g);
				detach(g);
				attach(slot, g);
			}
			g.below    = below;
			g.hugsCard = hugsCard;
			if (phasesGone == null) {
				if (g.phases != null) play(g, true, shownFill(g), 0);
				g.phases = null;
			} else if (g.phases == null || phasesGone < g.phases) {
				g.startNs = -1;
				g.phases  = phasesGone;
			} else if (phasesGone > g.phases) {
				play(g, false, shownFill(g), NotReadyMarker.fill(phasesGone));
				g.phases = phasesGone;
			}
			repaintGauge(g);
		}
	}

	/**
	 * Takes every gauge down at once, with no clearing: the board has stopped showing gauges, and a
	 * clearing would read as every waiting card becoming ready.
	 */
	public void clear() {
		synchronized (getTreeLock()) {
			for (Gauge g : gauges.values()) drop(g);
			gauges.clear();
			timer.stop();
		}
	}

	/** How many phases {@code card}'s gauge shows gone by at rest, or {@code null} when it shows none. */
	public Integer phasesShown(Object card) {
		synchronized (getTreeLock()) {
			Gauge g = gauges.get(card);
			return g == null ? null : g.phases;
		}
	}

	/** Repaints the gauge drawn beside {@code slot}, which has just been drawn somewhere else — a combat lift. */
	public void slotMoved(JLabel slot) {
		synchronized (getTreeLock()) {
			for (Gauge g : gauges.values()) if (g.slot == slot) repaintGauge(g);
		}
	}

	/** Never intercepts mouse events — the board components below stay active. */
	@Override
	public boolean contains(int x, int y) {
		return false;
	}

	/** How full {@code g} looks right now: part-way up a running rise, or at rest. */
	private static double shownFill(Gauge g) {
		if (g.startNs >= 0 && !g.clearing) return NotReadyMarker.riseFill(g.from, g.to, elapsedMs(g));
		return g.phases == null ? 0 : NotReadyMarker.fill(g.phases);
	}

	private void play(Gauge g, boolean clearing, double from, double to) {
		g.clearing = clearing;
		g.from     = from;
		g.to       = to;
		g.startNs  = System.nanoTime();
		if (!timer.isRunning()) timer.start();
	}

	private static double elapsedMs(Gauge g) {
		return (System.nanoTime() - g.startNs) / 1_000_000.0;
	}

	private static int durationMs(Gauge g) {
		return g.clearing ? NotReadyMarker.clearingMs(g.from) : NotReadyMarker.RISE_MS;
	}

	private void tick() {
		synchronized (getTreeLock()) {
			pruneDetached();
			boolean running = false;
			for (Iterator<Gauge> it = gauges.values().iterator(); it.hasNext(); ) {
				Gauge g = it.next();
				if (g.startNs < 0) continue;
				repaintGauge(g);
				if (elapsedMs(g) < durationMs(g)) {
					running = true;
				} else {
					g.startNs = -1;
					if (g.phases == null) {
						detach(g);
						it.remove();
					}
				}
			}
			if (!running) timer.stop();
		}
	}

	/** Drops the gauges of cards whose slot has left the tree — a Forward gone from the field. */
	private void pruneDetached() {
		synchronized (getTreeLock()) {
			for (Iterator<Gauge> it = gauges.values().iterator(); it.hasNext(); ) {
				Gauge g = it.next();
				if (g.slot.getParent() != null) continue;
				drop(g);
				it.remove();
			}
		}
	}

	/** Drops the gauge beside {@code slot} if it is still empty — a Backup gone from the field. */
	private void pruneEmptied(JLabel slot) {
		synchronized (getTreeLock()) {
			if (slot.getIcon() != null) return;
			for (Iterator<Gauge> it = gauges.values().iterator(); it.hasNext(); ) {
				Gauge g = it.next();
				if (g.slot != slot) continue;
				drop(g);
				it.remove();
			}
		}
	}

	/** Stops drawing {@code g}, as it stands, and stops following its slot; the caller forgets it. */
	private void drop(Gauge g) {
		if (g.lastDrawn != null) repaint(g.lastDrawn);
		detach(g);
	}

	private void attach(JLabel slot, Gauge g) {
		g.slot       = slot;
		g.lastDrawn  = null;
		g.drawnState = null;
		slot.addComponentListener(moveListener);
		slot.addHierarchyListener(detachListener);
		slot.addPropertyChangeListener("icon", iconListener);
		g.viewport = (JViewport) SwingUtilities.getAncestorOfClass(JViewport.class, slot);
		g.scroll   = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, slot);
		if (g.viewport != null) g.viewport.addChangeListener(scrollListener);
	}

	private void detach(Gauge g) {
		g.slot.removeComponentListener(moveListener);
		g.slot.removeHierarchyListener(detachListener);
		g.slot.removePropertyChangeListener("icon", iconListener);
		if (g.viewport != null) g.viewport.removeChangeListener(scrollListener);
	}

	/** Repaints where {@code g} was last drawn and where it goes now. */
	private void repaintGauge(Gauge g) {
		if (g.lastDrawn != null) repaint(g.lastDrawn);
		Rectangle2D bar = barFor(g);
		if (bar != null) repaint(NotReadyMarker.reach(bar));
	}

	/**
	 * Where {@code g}'s bar goes, in this overlay's coordinates: off the card's centre-facing edge,
	 * and below the row's scrollbar on P2's side while it shows, since it hangs between the cards and
	 * the centre there — unless it hugs a dull card, which keeps it inside the slot. {@code null}
	 * when the slot is not in the tree. Notes the state the slot draws its card in as it goes.
	 */
	private Rectangle2D barFor(Gauge g) {
		JLabel slot = g.slot;
		if (slot.getParent() == null) return null;
		Point     p    = SwingUtilities.convertPoint(slot, 0, 0, this);
		Icon      icon = slot.getIcon();
		Rectangle ir   = icon != null ? FieldSlotLabel.iconBounds(slot, icon)
		                              : new Rectangle(0, 0, slot.getWidth(), slot.getHeight());
		if (icon instanceof FieldCardIcon drawn) g.drawnState = drawn.state();
		double top = p.y + ir.y, bottom = top + ir.height;
		boolean insideSlot = g.hugsCard && g.drawnState == CardState.DULL;
		if (insideSlot) {
			// The dull art: CARD_H across, CARD_W deep, below the strip its tabs poke into.
			top    += CardAnimation.LEFT_GUTTER;
			bottom  = top + CardAnimation.CARD_W;
		}
		double edgeY = g.below ? bottom : top;
		if (!insideSlot && g.below && g.scroll != null && g.scroll.getParent() != null
				&& g.scroll.getHorizontalScrollBar().isVisible()) {
			Rectangle sr = SwingUtilities.convertRectangle(g.scroll.getParent(), g.scroll.getBounds(), this);
			edgeY = Math.max(edgeY, sr.y + sr.height);
		}
		edgeY -= liftOffset.applyAsInt(slot);
		return NotReadyMarker.bar(p.x + ir.x, edgeY, g.below);
	}

	@Override
	protected void paintComponent(Graphics g) {
		synchronized (getTreeLock()) {
			for (Gauge gauge : gauges.values()) paintGauge((Graphics2D) g, gauge);
		}
	}

	private void paintGauge(Graphics2D g0, Gauge g) {
		Rectangle2D bar = barFor(g);
		if (bar == null) return;
		g.lastDrawn = NotReadyMarker.reach(bar);
		if (g.startNs < 0 && g.phases == null) return;
		Graphics2D g2 = (Graphics2D) g0.create();
		try {
			if (g.viewport != null && g.viewport.getParent() != null) {
				Rectangle vr = SwingUtilities.convertRectangle(g.viewport.getParent(), g.viewport.getBounds(), this);
				g2.clipRect(vr.x, g.lastDrawn.y, vr.width, g.lastDrawn.height);
			}
			if (g.startNs >= 0 && g.clearing) {
				NotReadyMarker.paintClearing(g2, bar, g.from, elapsedMs(g));
			} else {
				double fill = g.startNs >= 0 ? NotReadyMarker.riseFill(g.from, g.to, elapsedMs(g))
				                             : NotReadyMarker.fill(g.phases);
				NotReadyMarker.paint(g2, bar, fill);
			}
		} finally {
			g2.dispose();
		}
	}
}
