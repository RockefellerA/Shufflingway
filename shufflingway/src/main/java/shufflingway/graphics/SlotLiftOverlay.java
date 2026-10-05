package shufflingway.graphics;

import javax.swing.*;
import javax.swing.event.ChangeListener;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.ComponentListener;
import java.awt.event.HierarchyEvent;
import java.awt.event.HierarchyListener;
import java.beans.PropertyChangeListener;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Transparent overlay on the frame's PALETTE_LAYER that draws chosen card slots raised a few pixels
 * above where their layout puts them — P1's attackers and blocker, from selection until combat resolves.
 *
 * <p>A slot cannot simply be moved. The Forward row lives in a scroll pane whose viewport clips to
 * the card row and sits flush against the centre-facing edge, and the Backup row is packed tight
 * under it, so a slot nudged upwards would lose its top edge rather than rise. Instead a lifted slot stops
 * painting itself — its label asks {@link #isLifted} — and this overlay paints the slot's current
 * icon, offset, from the layered pane where nothing clips it. Because it paints whatever icon the
 * label holds, a dull rotation or a re-render carries on as normal while the card is up.
 *
 * <p>Lifts are keyed by the card, not the label: the Forward row rebuilds every label whenever one
 * Forward leaves the field, and a party member still standing should stay up rather than drop and
 * rise again. A key's label is swapped for the new one the next time it is lifted.
 *
 * <p>Rising and settling are eased over a few frames. Toggling a slot down and back up within one
 * event — a refresh that runs before the attack is recorded as declared — never reaches a frame,
 * so it does not show.
 */
public class SlotLiftOverlay extends JComponent {

	private static final int FRAMES   = 8;    // ~130 ms at 16 ms/frame
	private static final int FRAME_MS = 16;

	private static final class Lift {
		JLabel    slot;
		JViewport viewport;
		double    progress;   // 0 = resting in the row, 1 = fully raised
		boolean   target;
		Rectangle lastDrawn;  // overlay coordinates; erased when the slot leaves the tree
	}

	private final int                 maxLift;
	private final Map<Object, Lift>   lifts = new IdentityHashMap<>();
	private final Timer               timer;
	private final PropertyChangeListener iconListener = e -> repaintSlot((JLabel) e.getSource());
	private final ComponentListener   moveListener = new ComponentAdapter() {
		@Override public void componentMoved(ComponentEvent e)   { repaint(); }
		@Override public void componentResized(ComponentEvent e) { repaint(); }
	};
	private final ChangeListener      scrollListener = e -> repaint();
	/**
	 * A slot leaving the tree is dropped on the next event rather than at once, so a row rebuild
	 * that re-lifts the same card under a new label in the same event keeps its lift.
	 */
	private final HierarchyListener   detachListener = e -> {
		if ((e.getChangeFlags() & HierarchyEvent.PARENT_CHANGED) == 0) return;
		if (e.getComponent().getParent() == null) SwingUtilities.invokeLater(this::pruneDetached);
	};

	public SlotLiftOverlay(int maxLift) {
		this.maxLift = maxLift;
		setOpaque(false);
		setFocusable(false);
		timer = new Timer(FRAME_MS, e -> tick());
		timer.setCoalesce(true);
	}

	/** Installs the overlay on {@code frame}'s layered pane, under the DRAG_LAYER animations. */
	public static SlotLiftOverlay install(JFrame frame, int maxLift) {
		SlotLiftOverlay o  = new SlotLiftOverlay(maxLift);
		JLayeredPane    lp = frame.getRootPane().getLayeredPane();
		o.setBounds(0, 0, lp.getWidth(), lp.getHeight());
		lp.add(o, JLayeredPane.PALETTE_LAYER);
		lp.addComponentListener(new ComponentAdapter() {
			@Override public void componentResized(ComponentEvent e) {
				o.setBounds(0, 0, lp.getWidth(), lp.getHeight());
			}
		});
		return o;
	}

	/**
	 * Raises or lowers the card {@code key}, drawn from {@code slot}; the change eases in over the
	 * next few frames. {@code key} is compared by identity.
	 */
	public void setLifted(Object key, JLabel slot, boolean lifted) {
		Lift l = lifts.get(key);
		if (l == null) {
			if (!lifted) return;
			// A slot that keeps its label across cards (a Backup slot) may still be drawn raised for
			// the card that stood there before; this card takes the slot over from it.
			dropOthersOn(slot, key);
			l = new Lift();
			lifts.put(key, l);
			attach(slot, l);
		} else if (l.slot != slot) {
			if (l.lastDrawn != null) repaint(l.lastDrawn);
			detach(l);
			attach(slot, l);
			repaintSlot(slot);
		}
		l.target = lifted;
		if (!lifted && l.progress == 0) {
			// Raised and lowered again before a frame drew it: nothing to animate back.
			detach(l);
			lifts.remove(key);
			return;
		}
		if (l.progress != (lifted ? 1.0 : 0.0) && !timer.isRunning()) timer.start();
	}

	/** Settles whatever is raised in {@code slot} — for a slot whose card has left it. */
	public void lowerSlot(JLabel slot) {
		for (Map.Entry<Object, Lift> e : lifts.entrySet())
			if (e.getValue().slot == slot) { setLifted(e.getKey(), slot, false); return; }
	}

	/** Drops, without animating, every lift drawn from {@code slot} other than {@code key}'s. */
	private void dropOthersOn(JLabel slot, Object key) {
		for (Iterator<Map.Entry<Object, Lift>> it = lifts.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<Object, Lift> e = it.next();
			if (e.getKey() == key || e.getValue().slot != slot) continue;
			if (e.getValue().lastDrawn != null) repaint(e.getValue().lastDrawn);
			detach(e.getValue());
			it.remove();
		}
	}

	/**
	 * True while this overlay is painting {@code slot} — its label must then paint nothing itself,
	 * or the card shows twice.
	 */
	public boolean isLifted(JLabel slot) {
		Lift l = liftFor(slot);
		return l != null && l.progress > 0;
	}

	/** How far {@code slot} is drawn above its laid-out position right now, in pixels. */
	public int offset(JLabel slot) {
		Lift l = liftFor(slot);
		return l == null ? 0 : offset(l);
	}

	/** Never intercepts mouse events — the board components below stay active. */
	@Override
	public boolean contains(int x, int y) {
		return false;
	}

	private Lift liftFor(JLabel slot) {
		for (Lift l : lifts.values()) if (l.slot == slot) return l;
		return null;
	}

	private int offset(Lift l) {
		return (int) Math.round(maxLift * ease(l.progress));
	}

	private void tick() {
		pruneDetached();
		boolean moving = false;
		for (Iterator<Lift> it = lifts.values().iterator(); it.hasNext(); ) {
			Lift   l    = it.next();
			double goal = l.target ? 1.0 : 0.0;
			if (l.progress == goal) continue;
			boolean wasResting = l.progress == 0;
			double step = 1.0 / FRAMES;
			l.progress = l.target ? Math.min(1.0, l.progress + step) : Math.max(0.0, l.progress - step);
			repaintSlot(l.slot);
			// The label hands its painting over on the way up and takes it back on landing.
			if (wasResting || l.progress == 0) l.slot.repaint();
			if (l.progress == 0) {
				detach(l);
				it.remove();
			} else if (l.progress != goal) {
				moving = true;
			}
		}
		if (!moving) timer.stop();
	}

	/** Drops cards whose slot has left the tree — a broken Forward — erasing where it was drawn. */
	private void pruneDetached() {
		for (Iterator<Lift> it = lifts.values().iterator(); it.hasNext(); ) {
			Lift l = it.next();
			if (l.slot.getParent() != null) continue;
			if (l.lastDrawn != null) repaint(l.lastDrawn);
			detach(l);
			it.remove();
		}
	}

	private void attach(JLabel slot, Lift l) {
		l.slot      = slot;
		l.lastDrawn = null;
		slot.addPropertyChangeListener("icon", iconListener);
		slot.addComponentListener(moveListener);
		slot.addHierarchyListener(detachListener);
		l.viewport = (JViewport) SwingUtilities.getAncestorOfClass(JViewport.class, slot);
		if (l.viewport != null) l.viewport.addChangeListener(scrollListener);
	}

	private void detach(Lift l) {
		l.slot.removePropertyChangeListener("icon", iconListener);
		l.slot.removeComponentListener(moveListener);
		l.slot.removeHierarchyListener(detachListener);
		if (l.viewport != null) l.viewport.removeChangeListener(scrollListener);
	}

	/** Repaints the strip {@code slot} can occupy anywhere between resting and fully raised. */
	public void repaintSlot(JLabel slot) {
		if (!slot.isShowing() || !isShowing()) return;
		Point p = SwingUtilities.convertPoint(slot, 0, 0, this);
		int pad = OverlayDirtyRegion.PAD;
		repaint(p.x - pad, p.y - maxLift - pad, slot.getWidth() + 2 * pad, slot.getHeight() + maxLift + 2 * pad);
	}

	@Override
	protected void paintComponent(Graphics g) {
		for (Lift l : lifts.values()) {
			JLabel slot = l.slot;
			Icon   icon = slot.getIcon();
			if (l.progress == 0 || icon == null || !slot.isShowing()) continue;
			Graphics2D g2 = (Graphics2D) g.create();
			try {
				// Stay inside the row's viewport sideways, so a card scrolled half out of view is not
				// drawn over the neighbouring zone; upwards it may rise by the full lift.
				if (l.viewport != null && l.viewport.getParent() != null) {
					Rectangle vr = SwingUtilities.convertRectangle(
							l.viewport.getParent(), l.viewport.getBounds(), this);
					vr.y      -= maxLift;
					vr.height += maxLift;
					g2.clip(vr);
				}
				Point     p  = SwingUtilities.convertPoint(slot, 0, 0, this);
				Rectangle ir = FieldSlotLabel.iconBounds(slot, icon);
				int x = p.x + ir.x;
				int y = p.y + ir.y - offset(l);
				icon.paintIcon(slot, g2, x, y);
				// The slot paints nothing of its own while raised, so its buttons come up with it.
				if (slot instanceof FieldSlotLabel f) f.paintOverlays(g2, x, y);
				l.lastDrawn = new Rectangle(x, y, icon.getIconWidth(), icon.getIconHeight());
			} finally {
				g2.dispose();
			}
		}
	}

	/** Smoothstep: eases out of the row and into the raised position. */
	private static double ease(double t) {
		return t * t * (3 - 2 * t);
	}
}
