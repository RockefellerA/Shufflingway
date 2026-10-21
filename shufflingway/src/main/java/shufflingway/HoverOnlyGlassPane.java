package shufflingway;

import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

import javax.swing.AbstractButton;
import javax.swing.JMenuBar;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;

/**
 * A glass pane that swallows clicks on the board but lets the pointer through, so hovering a card
 * still zooms it while this client waits on the opponent — and fades the board's controls, so what
 * cannot be clicked does not look as though it can.
 *
 * <p>Swing sends every mouse event over a visible glass pane to the pane itself, enter and exit
 * included, and the board's hover zoom is exactly those: each card's {@code mouseEntered} and
 * {@code mouseExited}. So this pane forwards movement to the component under the pointer and
 * synthesises the enter and exit as it crosses from one to the next, the way Swing's own
 * dispatcher does. Presses, releases, clicks, drags and the wheel stop here.
 *
 * <p><b>The controls are faded by painting, not disabled.</b> Turn flow keeps running under a wait
 * and enables and disables them as it goes — the Next button above all — so switching them off and
 * restoring them afterwards would undo whatever it did meanwhile. The menu bar, buttons and
 * editable text fields are painted over in the colour around them instead, and hover is not
 * forwarded to them, so they do not light up either. Controls already disabled are left as they
 * are, since they already look it.
 */
final class HoverOnlyGlassPane extends JPanel {

	/** How strongly a control is faded toward the colour around it, out of 255. */
	private static final int FADE_ALPHA = 150;

	/** One faded control: where it lies in this pane, and the colour it is faded toward. */
	record Faded(Rectangle bounds, Color toward) {}

	/** What the glass pane covers: the frame's layered pane, menu bar included. */
	private final Container board;
	/** The component the pointer was last forwarded to, or {@code null} when it is over none. */
	private Component hovered;

	HoverOnlyGlassPane(Container board) {
		this.board = board;
		setOpaque(false);
		MouseAdapter forward = new MouseAdapter() {
			@Override public void mouseMoved(MouseEvent e)   { hoverAt(e); }
			@Override public void mouseEntered(MouseEvent e) { hoverAt(e); }
			@Override public void mouseExited(MouseEvent e)  { leave(e); }
		};
		addMouseListener(forward);
		addMouseMotionListener(forward);
		addMouseWheelListener(e -> {});
	}

	/**
	 * Hidden, the pane hears nothing more, and Swing's dispatcher resumes with the component under
	 * the pointer: a card hovered through the pane is entered again there, and exited when the
	 * pointer leaves it.
	 */
	@Override
	public void setVisible(boolean visible) {
		if (!visible) hovered = null;
		super.setVisible(visible);
	}

	@Override
	protected void paintComponent(Graphics g) {
		Graphics2D g2 = (Graphics2D) g.create();
		try {
			for (Faded f : fadedControls()) {
				g2.setColor(f.toward());
				g2.fill(f.bounds());
			}
		} finally {
			g2.dispose();
		}
	}

	/** The controls this pane fades as it stands, in its own coordinates. */
	List<Faded> fadedControls() {
		List<Faded> out = new ArrayList<>();
		collectControls(board, out);
		return out;
	}

	private void collectControls(Container parent, List<Faded> out) {
		for (Component c : parent.getComponents()) {
			if (!c.isShowing()) continue;
			if (isControl(c)) {
				if (c.isEnabled())
					out.add(new Faded(SwingUtilities.convertRectangle(c.getParent(), c.getBounds(), this),
							fadeToward(c)));
			} else if (c instanceof Container k) {
				collectControls(k, out);
			}
		}
	}

	/** A menu bar, button or editable field: something that acts when clicked or typed into. */
	private static boolean isControl(Component c) {
		return c instanceof JMenuBar || c instanceof AbstractButton
				|| (c instanceof JTextComponent t && t.isEditable());
	}

	/** The background showing around {@code c} — its nearest opaque ancestor's — made translucent. */
	private static Color fadeToward(Component c) {
		Component around = c.getParent();
		while (around != null && !around.isOpaque()) around = around.getParent();
		Color bg = around != null ? around.getBackground() : c.getBackground();
		return new Color(bg.getRed(), bg.getGreen(), bg.getBlue(), FADE_ALPHA);
	}

	private void hoverAt(MouseEvent e) {
		Component under = listenerAt(e.getX(), e.getY());
		if (under != hovered) {
			if (hovered != null) send(hovered, MouseEvent.MOUSE_EXITED, e);
			hovered = under;
			if (under != null) send(under, MouseEvent.MOUSE_ENTERED, e);
		}
		if (under != null && e.getID() == MouseEvent.MOUSE_MOVED) send(under, MouseEvent.MOUSE_MOVED, e);
	}

	private void leave(MouseEvent e) {
		if (hovered != null) send(hovered, MouseEvent.MOUSE_EXITED, e);
		hovered = null;
	}

	/**
	 * The deepest component under the point that listens to the mouse — the one Swing would have
	 * delivered to with this pane out of the way — or {@code null} when that is one of the faded
	 * controls, which would only light up for a click that cannot land.
	 */
	private Component listenerAt(int x, int y) {
		Point p = SwingUtilities.convertPoint(this, x, y, board);
		Component c = SwingUtilities.getDeepestComponentAt(board, p.x, p.y);
		while (c != null && c != board && c.getMouseListeners().length == 0
				&& c.getMouseMotionListeners().length == 0)
			c = c.getParent();
		if (c == null || c == board) return null;
		for (Component k = c; k != null && k != board; k = k.getParent())
			if (isControl(k)) return null;
		return c;
	}

	private void send(Component to, int id, MouseEvent from) {
		MouseEvent converted = SwingUtilities.convertMouseEvent(this, from, to);
		to.dispatchEvent(new MouseEvent(to, id, from.getWhen(), from.getModifiersEx(),
				converted.getX(), converted.getY(), 0, false, MouseEvent.NOBUTTON));
	}
}
