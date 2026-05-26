package shufflingway;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * Transparent overlay installed on the frame's DRAG_LAYER that renders
 * card-break animations: a diagonal slash sweeps across the card, then
 * the two halves drift apart and fade.
 */
class CardBreakAnimator extends JComponent {

	static final int TOTAL_FRAMES = 18;
	static final int SLASH_FRAMES = 5;
	static final int FRAME_MS     = 16;

	// Cut line, as fraction of image height: y at x=0 and y at x=width
	private static final double CUT_Y0_FRAC = 0.38;
	private static final double CUT_Y1_FRAC = 0.62;

	// Perpendicular drift directions (upper half: right+up, lower half: left+down)
	private static final double UPPER_DX = +0.331;
	private static final double UPPER_DY = -0.943;
	private static final double LOWER_DX = -0.331;
	private static final double LOWER_DY = +0.943;

	private static final int MAX_DRIFT_PX = 45;

	private static class Break {
		final BufferedImage img;
		final Point         center;
		int frame;

		Break(BufferedImage img, Point center) {
			this.img    = img;
			this.center = center;
		}
	}

	private final List<Break> breaks = new ArrayList<>();
	private final Timer       timer;

	CardBreakAnimator() {
		setOpaque(false);
		setFocusable(false);
		timer = new Timer(FRAME_MS, e -> tick());
		timer.setCoalesce(true);
	}

	/** Installs the animator on {@code frame}'s layered pane at DRAG_LAYER. */
	static CardBreakAnimator install(JFrame frame) {
		CardBreakAnimator a  = new CardBreakAnimator();
		JLayeredPane      lp = frame.getRootPane().getLayeredPane();
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
	 * Queues a break animation. {@code center} must be in layered-pane coordinates
	 * and represent the center of the card image.
	 */
	void startBreak(BufferedImage img, Point center) {
		breaks.add(new Break(img, center));
		if (!timer.isRunning()) timer.start();
	}

	/** Never intercepts mouse events. */
	@Override
	public boolean contains(int x, int y) { return false; }

	private void tick() {
		breaks.removeIf(b -> { b.frame++; return b.frame >= TOTAL_FRAMES; });
		if (breaks.isEmpty()) timer.stop();
		repaint();
	}

	@Override
	protected void paintComponent(Graphics g) {
		if (breaks.isEmpty()) return;
		Graphics2D g2 = (Graphics2D) g.create();
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,  RenderingHints.VALUE_ANTIALIAS_ON);
		g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		for (Break b : breaks) renderBreak(g2, b);
		g2.dispose();
	}

	private void renderBreak(Graphics2D g2, Break b) {
		int w  = b.img.getWidth();
		int h  = b.img.getHeight();
		int ox = b.center.x - w / 2;
		int oy = b.center.y - h / 2;

		int cutY0 = (int) Math.round(CUT_Y0_FRAC * h);
		int cutY1 = (int) Math.round(CUT_Y1_FRAC * h);

		if (b.frame < SLASH_FRAMES) {
			g2.drawImage(b.img, ox, oy, null);
			drawSlash(g2, b.frame, ox, oy, w, h, cutY0, cutY1);
		} else {
			double progress = (double)(b.frame - SLASH_FRAMES) / (TOTAL_FRAMES - SLASH_FRAMES);
			// Ease-out: fast start, decelerate
			double t     = 1.0 - (1.0 - progress) * (1.0 - progress);
			double drift = t * MAX_DRIFT_PX;
			float  alpha = (float)(1.0 - progress);

			AlphaComposite ac = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha);

			int udx = (int)(UPPER_DX * drift);
			int udy = (int)(UPPER_DY * drift);
			int ldx = (int)(LOWER_DX * drift);
			int ldy = (int)(LOWER_DY * drift);

			// Upper half polygon (above cut line), moved with the drift
			Polygon upperPoly = new Polygon(
				new int[]{ ox + udx,         ox + w + udx,         ox + w + udx,      ox + udx         },
				new int[]{ oy + udy,          oy + udy,              oy + cutY1 + udy,  oy + cutY0 + udy },
				4);

			// Lower half polygon (below cut line), moved with the drift
			Polygon lowerPoly = new Polygon(
				new int[]{ ox + ldx,          ox + w + ldx,          ox + w + ldx,      ox + ldx         },
				new int[]{ oy + cutY0 + ldy,  oy + cutY1 + ldy,      oy + h + ldy,      oy + h + ldy     },
				4);

			Graphics2D ug = (Graphics2D) g2.create();
			ug.setComposite(ac);
			ug.setClip(upperPoly);
			ug.drawImage(b.img, ox + udx, oy + udy, null);
			ug.dispose();

			Graphics2D lg = (Graphics2D) g2.create();
			lg.setComposite(ac);
			lg.setClip(lowerPoly);
			lg.drawImage(b.img, ox + ldx, oy + ldy, null);
			lg.dispose();
		}
	}

	private void drawSlash(Graphics2D g2, int frame, int ox, int oy,
			int w, int h, int cutY0, int cutY1) {
		// Sweep position: starts off the left edge, ends off the right edge
		double slashFrac = (double) frame / (SLASH_FRAMES - 1);
		double slashRelX = -w * 0.5 + slashFrac * w * 2.0;
		double slashCx   = ox + slashRelX;
		double slashCy   = oy + cutY0 + (cutY1 - cutY0) * slashRelX / w;

		// Unit vectors along and perpendicular to the cut line
		double dx  = w,  dy  = cutY1 - cutY0;
		double len = Math.sqrt(dx * dx + dy * dy);
		double ux  = dx / len, uy = dy / len;
		double nx  = -uy,      ny = ux;

		double halfWidth  = 10.0;
		double halfLength = h * 1.5;

		// Outer glow band
		int[] xs = buildParallelogram(slashCx, slashCy, ux, uy, nx, ny, halfLength, halfWidth);
		int[] ys = buildParallelogramY(slashCx, slashCy, ux, uy, nx, ny, halfLength, halfWidth);

		Graphics2D sg = (Graphics2D) g2.create();
		sg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		sg.setClip(ox, oy, w, h);
		sg.setColor(new Color(255, 255, 255, 160));
		sg.fillPolygon(xs, ys, 4);

		// Bright core
		double coreHalf = halfWidth * 0.25;
		int[] cxs = buildParallelogram(slashCx, slashCy, ux, uy, nx, ny, halfLength, coreHalf);
		int[] cys = buildParallelogramY(slashCx, slashCy, ux, uy, nx, ny, halfLength, coreHalf);
		sg.setColor(new Color(255, 255, 255, 240));
		sg.fillPolygon(cxs, cys, 4);

		sg.dispose();
	}

	private static int[] buildParallelogram(double cx, double cy,
			double ux, double uy, double nx, double ny,
			double halfLen, double halfW) {
		return new int[]{
			(int)(cx - ux * halfLen - nx * halfW),
			(int)(cx + ux * halfLen - nx * halfW),
			(int)(cx + ux * halfLen + nx * halfW),
			(int)(cx - ux * halfLen + nx * halfW)
		};
	}

	private static int[] buildParallelogramY(double cx, double cy,
			double ux, double uy, double nx, double ny,
			double halfLen, double halfW) {
		return new int[]{
			(int)(cy - uy * halfLen - ny * halfW),
			(int)(cy + uy * halfLen - ny * halfW),
			(int)(cy + uy * halfLen + ny * halfW),
			(int)(cy - uy * halfLen + ny * halfW)
		};
	}
}
