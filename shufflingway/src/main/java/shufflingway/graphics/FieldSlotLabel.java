package shufflingway.graphics;

import java.awt.Cursor;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.function.Supplier;

import javax.swing.Icon;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import javax.swing.ToolTipManager;

import shufflingway.CardState;

/**
 * The label a field card slot is drawn in. On top of the card icon it paints that card's
 * {@link ActionButton}s, and it answers clicks on them itself.
 *
 * <p>A button press never reaches the slot's other mouse listeners — attack and block selection,
 * the context menu. Those are registered at each of the many places a slot is built; swallowing
 * the event here, ahead of all of them, is what keeps a button from also selecting the card.
 * A press arms the button and draws it pushed in, and the release fires it if the pointer is
 * still on it, like any button.
 *
 * <p>While {@link SlotLiftOverlay} has the slot raised, the label paints nothing and the overlay
 * paints both the icon and, through {@link #paintButtons}, the buttons.
 */
public class FieldSlotLabel extends JLabel {

	private final Supplier<SlotLiftOverlay> lift;

	private List<ActionButton.Spec> buttons = List.of();
	private List<Runnable>          actions = List.of();
	private int pressed = -1;
	private int hover   = -1;

	/** How long a tooltip stays up over a slot, long enough to read an ability. */
	private static final int TOOLTIP_DISMISS_MS = 20_000;
	/** The tooltip manager's delay before the pointer came over this slot, or -1 while it is elsewhere. */
	private int savedDismissDelay = -1;

	/**
	 * @param lift the overlay that draws this slot while it is raised; read on each paint, since
	 *             slots can be built before the overlay is installed
	 */
	public FieldSlotLabel(int horizontalAlignment, Supplier<SlotLiftOverlay> lift) {
		super("", horizontalAlignment);
		this.lift = lift;
		// Registered up front: a button's tooltip has to show even on a card with no tooltip text.
		ToolTipManager.sharedInstance().registerComponent(this);
	}

	/**
	 * Sets the buttons to draw and what each one does. Repaints only when what is drawn changed,
	 * so a caller may refresh this as often as it likes.
	 *
	 * @param actions one per button, run on the event thread when that button is clicked
	 */
	public void setButtons(List<ActionButton.Spec> buttons, List<Runnable> actions) {
		this.actions = List.copyOf(actions);
		if (buttons.equals(this.buttons)) return;
		this.buttons = List.copyOf(buttons);
		if (pressed >= buttons.size() || (pressed >= 0 && !buttons.get(pressed).usable())) pressed = -1;
		if (hover >= buttons.size()) hover = -1;
		repaintSlot();
	}

	/** The buttons currently drawn. */
	public List<ActionButton.Spec> buttons() {
		return buttons;
	}

	/**
	 * The state the buttons are laid out for — the one the current icon was drawn in — or
	 * {@code null} when the icon is not a settled card (none, or an animation frame), in which case
	 * no buttons are drawn.
	 */
	private CardState buttonState() {
		return getIcon() instanceof FieldCardIcon fi ? fi.state() : null;
	}

	/** Paints the buttons with the slot canvas's top-left at {@code (x, y)} on {@code g}. */
	public void paintButtons(Graphics2D g, int x, int y) {
		CardState state = buttonState();
		if (state == null || buttons.isEmpty()) return;
		Graphics2D g2 = (Graphics2D) g.create();
		g2.translate(x, y);
		ActionButton.paint(g2, state, buttons, pressed, hover);
		g2.dispose();
	}

	@Override
	protected void paintComponent(Graphics g) {
		SlotLiftOverlay overlay = lift.get();
		if (overlay != null && overlay.isLifted(this)) return;
		super.paintComponent(g);
		Icon icon = getIcon();
		if (icon == null) return;
		Rectangle ir = iconBounds(this, icon);
		paintButtons((Graphics2D) g, ir.x, ir.y);
	}

	/** The index of the button under label point {@code (x, y)}, or -1. */
	private int buttonAt(int x, int y) {
		CardState state = buttonState();
		Icon icon = getIcon();
		if (state == null || icon == null || buttons.isEmpty()) return -1;
		Rectangle ir = iconBounds(this, icon);
		return ActionButton.indexAt(state, buttons.size(), x - ir.x, y - ir.y);
	}

	@Override
	protected void processMouseEvent(MouseEvent e) {
		switch (e.getID()) {
			case MouseEvent.MOUSE_PRESSED -> {
				int i = buttonAt(e.getX(), e.getY());
				if (i >= 0 && SwingUtilities.isLeftMouseButton(e)) {
					// Taken here even when the button is unusable: a click on a button is a click on
					// the button, never on the card behind it.
					if (buttons.get(i).usable()) { pressed = i; repaintSlot(); }
					return;
				}
			}
			case MouseEvent.MOUSE_RELEASED -> {
				if (pressed >= 0) {
					int i = pressed;
					pressed = -1;
					repaintSlot();
					// Queued rather than run now, so the button is seen to come back up before a
					// modal payment dialog takes over the event thread.
					if (buttonAt(e.getX(), e.getY()) == i && i < actions.size())
						SwingUtilities.invokeLater(actions.get(i));
					return;
				}
				if (SwingUtilities.isLeftMouseButton(e) && buttonAt(e.getX(), e.getY()) >= 0) return;
			}
			case MouseEvent.MOUSE_CLICKED -> {
				if (SwingUtilities.isLeftMouseButton(e) && buttonAt(e.getX(), e.getY()) >= 0) return;
			}
			case MouseEvent.MOUSE_ENTERED -> {
				// A button's tooltip is a whole ability; give it time to be read.
				ToolTipManager ttm = ToolTipManager.sharedInstance();
				savedDismissDelay = ttm.getDismissDelay();
				ttm.setDismissDelay(Math.max(savedDismissDelay, TOOLTIP_DISMISS_MS));
			}
			case MouseEvent.MOUSE_EXITED -> {
				setHover(-1);
				if (savedDismissDelay >= 0) ToolTipManager.sharedInstance().setDismissDelay(savedDismissDelay);
				savedDismissDelay = -1;
			}
			default -> { }
		}
		super.processMouseEvent(e);
	}

	@Override
	protected void processMouseMotionEvent(MouseEvent e) {
		if (e.getID() == MouseEvent.MOUSE_MOVED || e.getID() == MouseEvent.MOUSE_DRAGGED)
			setHover(buttonAt(e.getX(), e.getY()));
		super.processMouseMotionEvent(e);
	}

	/** Repaints the slot wherever it is being drawn — in place, or raised by the lift overlay. */
	private void repaintSlot() {
		repaint();
		SlotLiftOverlay overlay = lift.get();
		if (overlay != null && overlay.isLifted(this)) overlay.repaintSlot(this);
	}

	private void setHover(int i) {
		int usableHover = i >= 0 && buttons.get(i).usable() ? i : -1;
		if (usableHover == hover) return;
		hover = usableHover;
		setCursor(hover >= 0 ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : null);
		repaintSlot();
	}

	/**
	 * A slot can be rebuilt out from under the pointer, and then never hears the pointer leave —
	 * so it hands back the tooltip delay it borrowed on its way off screen too.
	 */
	@Override
	public void removeNotify() {
		if (savedDismissDelay >= 0) ToolTipManager.sharedInstance().setDismissDelay(savedDismissDelay);
		savedDismissDelay = -1;
		super.removeNotify();
	}

	/**
	 * Stores the slot's own tooltip without ever leaving the tooltip manager. A plain component
	 * unregisters itself whenever its text is set to {@code null} — which the slot's hover tracker
	 * does on every move over a card with no counters — and its buttons' tooltips would go with it.
	 */
	@Override
	public void setToolTipText(String text) {
		putClientProperty(TOOL_TIP_TEXT_KEY, text);
	}

	@Override
	public String getToolTipText(MouseEvent e) {
		int i = buttonAt(e.getX(), e.getY());
		if (i >= 0) return buttons.get(i).tooltip();
		return super.getToolTipText(e);
	}

	/** Where {@code slot} lays out {@code icon}, in its own coordinates — honouring its alignment. */
	public static Rectangle iconBounds(JLabel slot, Icon icon) {
		Insets in = slot.getInsets();
		Rectangle viewR = new Rectangle(in.left, in.top,
				slot.getWidth() - in.left - in.right, slot.getHeight() - in.top - in.bottom);
		Rectangle iconR = new Rectangle();
		Rectangle textR = new Rectangle();
		Font font = slot.getFont();
		if (font == null) {
			return new Rectangle(viewR.x + (viewR.width - icon.getIconWidth()) / 2,
					viewR.y + (viewR.height - icon.getIconHeight()) / 2, icon.getIconWidth(), icon.getIconHeight());
		}
		SwingUtilities.layoutCompoundLabel(slot, slot.getFontMetrics(font), slot.getText(), icon,
				slot.getVerticalAlignment(), slot.getHorizontalAlignment(),
				slot.getVerticalTextPosition(), slot.getHorizontalTextPosition(),
				viewR, iconR, textR, slot.getIconTextGap());
		return iconR;
	}
}
