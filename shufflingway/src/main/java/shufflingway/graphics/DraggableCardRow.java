package shufflingway.graphics;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.Border;

/**
 * A row of cards the player puts in order by dragging. A card picked up follows the pointer along
 * the row, lifted a little above it; the others slide aside as it passes halfway over them, and
 * it drops into the gap left for it when the button is let go. A click without a drag does nothing.
 *
 * <p>Cards are addressed by their place in the list the row was built for, never by value: the
 * same printing twice is two equal {@code CardData}, and the row has to keep them apart.
 * {@link #order()} reads the arrangement back as those places, left to right.
 */
public class DraggableCardRow extends JPanel {

	/** How far the pointer moves with the button down before a press becomes a drag. */
	static final int DRAG_THRESHOLD = 5;
	/** How far a card being dragged rides above the row. */
	private static final int LIFT = 6;
	private static final int GAP  = 8;

	private static final Border SLOT_BORDER = BorderFactory.createLineBorder(Color.GRAY, 2);
	private static final Border HOLE_BORDER = BorderFactory.createDashedBorder(Color.LIGHT_GRAY, 2, 6, 4, true);
	private static final Color  SLOT_FILL   = Color.DARK_GRAY;
	private static final Color  HOLE_FILL   = new Color(0x22, 0x22, 0x22);
	private static final Color  SHADOW      = new Color(0, 0, 0, 90);
	private static final Border LIFTED      = CardAnimation.createCardGlowBorder(Color.YELLOW);

	private final int cardW, cardH;
	private final boolean reorderable;
	private final JLabel[] slots;
	private final Icon[] images;
	/** The card in each slot, left to right, as its place in the original list. */
	private final List<Integer> order = new ArrayList<>();

	private IntConsumer onHover   = card -> { };
	private Runnable    onUnhover = () -> { };

	/** The slot the button went down on, or -1. */
	private int pressSlot = -1;
	private Point pressPoint;
	/** The card being dragged, or -1 while none is. */
	private int dragged = -1;
	/** Where in the card the pointer took hold of it. */
	private Point grab;
	/** The dragged card's left edge, in this row's coordinates. */
	private int liftedX;

	/**
	 * @param count       how many cards; their images arrive later, through {@link #setImage}
	 * @param font        for the "Loading..." shown until a card's image arrives
	 * @param reorderable whether the cards can be dragged at all; either way, hovering reports them
	 */
	public DraggableCardRow(int count, int cardW, int cardH, Font font, boolean reorderable) {
		super(new FlowLayout(FlowLayout.CENTER, GAP, GAP));
		this.cardW = cardW;
		this.cardH = cardH;
		this.reorderable = reorderable;
		slots  = new JLabel[count];
		images = new Icon[count];
		for (int i = 0; i < count; i++) {
			order.add(i);
			JLabel slot = new JLabel("Loading...", SwingConstants.CENTER);
			slot.setPreferredSize(new Dimension(cardW, cardH));
			slot.setMinimumSize(new Dimension(cardW, cardH));
			slot.setOpaque(true);
			slot.setForeground(Color.WHITE);
			slot.setFont(font);
			if (reorderable) slot.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			MouseAdapter mouse = slotMouse(i);
			slot.addMouseListener(mouse);
			slot.addMouseMotionListener(mouse);
			slots[i] = slot;
			add(slot);
		}
		render();
	}

	/**
	 * Reports the pointer coming onto a card, as its place in the original list, and leaving the
	 * cards. Quiet while a card is being dragged; the card under the pointer is reported again
	 * once it is dropped.
	 */
	public void setHoverListener(IntConsumer onHover, Runnable onUnhover) {
		this.onHover   = onHover;
		this.onUnhover = onUnhover;
	}

	/** Shows {@code image} for the card at place {@code card} in the original list, wherever it now is. */
	public void setImage(int card, Icon image) {
		images[card] = image;
		render();
		repaint();
	}

	/** The cards left to right, each as its place in the original list. */
	public List<Integer> order() {
		return List.copyOf(order);
	}

	private MouseAdapter slotMouse(int slot) {
		return new MouseAdapter() {
			@Override public void mouseEntered(MouseEvent e) {
				if (dragged < 0) onHover.accept(order.get(slot));
			}
			@Override public void mouseExited(MouseEvent e) {
				if (dragged < 0) onUnhover.run();
			}
			@Override public void mousePressed(MouseEvent e) {
				if (!reorderable || !SwingUtilities.isLeftMouseButton(e)) return;
				pressSlot  = slot;
				pressPoint = inRow(e);
			}
			// Swing sends the whole drag, and the release, to the card the button went down on.
			@Override public void mouseDragged(MouseEvent e) {
				if (pressSlot >= 0) drag(inRow(e));
			}
			@Override public void mouseReleased(MouseEvent e) {
				if (pressSlot >= 0 && SwingUtilities.isLeftMouseButton(e)) drop(inRow(e));
			}
		};
	}

	private Point inRow(MouseEvent e) {
		return SwingUtilities.convertPoint(e.getComponent(), e.getPoint(), this);
	}

	private void drag(Point p) {
		if (dragged < 0) {
			if (p.distance(pressPoint) < DRAG_THRESHOLD) return;
			dragged = order.get(pressSlot);
			Rectangle from = slots[pressSlot].getBounds();
			grab = new Point(pressPoint.x - from.x, pressPoint.y - from.y);
			onUnhover.run();
			setSlotCursors(Cursor.MOVE_CURSOR);
		}
		// Along the row only, and no further than its end cards.
		liftedX = Math.max(slots[0].getX(), Math.min(slots[slots.length - 1].getX(), p.x - grab.x));
		int target = slotUnder(liftedX + cardW / 2);
		int at = order.indexOf(dragged);
		if (target != at) {
			order.remove(at);
			order.add(target, dragged);
			render();
		}
		repaint();
	}

	private void drop(Point p) {
		pressSlot = -1;
		if (dragged < 0) return;   // a click, not a drag
		dragged = -1;
		setSlotCursors(Cursor.HAND_CURSOR);
		render();
		repaint();
		for (int s = 0; s < slots.length; s++)
			if (slots[s].getBounds().contains(p)) { onHover.accept(order.get(s)); return; }
	}

	/** The slot whose share of the row, gaps split down the middle, holds {@code x}. */
	private int slotUnder(int x) {
		for (int s = 0; s < slots.length - 1; s++) {
			Rectangle b = slots[s].getBounds();
			if (x < b.x + b.width + GAP / 2) return s;
		}
		return slots.length - 1;
	}

	private void setSlotCursors(int cursor) {
		for (JLabel slot : slots) slot.setCursor(Cursor.getPredefinedCursor(cursor));
	}

	/** Puts each card in its slot, and an empty, dashed hole where the dragged one will land. */
	private void render() {
		for (int s = 0; s < slots.length; s++) {
			int card = order.get(s);
			JLabel slot = slots[s];
			boolean hole = card == dragged;
			slot.setIcon(hole ? null : images[card]);
			slot.setText(hole || images[card] != null ? null : "Loading...");
			slot.setBackground(hole ? HOLE_FILL : SLOT_FILL);
			slot.setBorder(hole ? HOLE_BORDER : SLOT_BORDER);
		}
	}

	/** The dragged card, drawn over the others, raised and shadowed. */
	@Override
	protected void paintChildren(Graphics g) {
		super.paintChildren(g);
		if (dragged < 0) return;
		Graphics2D g2 = (Graphics2D) g.create();
		int y = slots[0].getY() - LIFT;
		g2.setColor(SHADOW);
		g2.fillRect(liftedX + 4, y + LIFT + 2, cardW, cardH);
		Icon image = images[dragged];
		if (image != null) {
			image.paintIcon(this, g2, liftedX, y);
		} else {
			g2.setColor(SLOT_FILL);
			g2.fillRect(liftedX, y, cardW, cardH);
		}
		LIFTED.paintBorder(this, g2, liftedX, y, cardW, cardH);
		g2.dispose();
	}
}
