package shufflingway.graphics;

import java.awt.Rectangle;

import javax.swing.JComponent;

/**
 * What a full-window animation overlay painted last frame, so each frame repaints only the area the
 * animation covers rather than the whole window.
 *
 * <p>The overlays are transparent and span the frame's layered pane. A bare {@code repaint()} on one
 * therefore repaints everything beneath it, window-wide — both hand fans, the board, the side panel —
 * sixty times a second to move one card. Repainting the union of this frame's extent and last
 * frame's keeps the work to where the animation is, and still erases where it was.
 */
final class OverlayDirtyRegion {

	/** Slack around an extent for antialiased edges. */
	static final int PAD = 2;

	private Rectangle last;

	/**
	 * Repaints {@code now} — what the overlay is about to draw, or {@code null} when it draws nothing —
	 * together with what it drew last frame.
	 */
	void repaint(JComponent overlay, Rectangle now) {
		Rectangle r = last == null ? now : now == null ? last : last.union(now);
		if (r != null) overlay.repaint(r);
		last = now;
	}

	/** Union of {@code a} and {@code b}, either of which may be {@code null}. */
	static Rectangle union(Rectangle a, Rectangle b) {
		return a == null ? b : b == null ? a : a.union(b);
	}

	/** Square of half-side {@code radius} around ({@code cx}, {@code cy}), padded. */
	static Rectangle around(int cx, int cy, double radius) {
		int r = (int) Math.ceil(radius) + PAD;
		return new Rectangle(cx - r, cy - r, 2 * r, 2 * r);
	}
}
