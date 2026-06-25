package shufflingway.graphics;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * Transparent overlay installed on the frame's DRAG_LAYER that renders
 * in-flight card-slide animations without intercepting mouse events.
 */
public class CardSlideAnimator extends JComponent {

	public static final int TOTAL_FRAMES = 20;   // 320 ms at 16 ms/frame
	public static final int FRAME_MS     = 16;

	private static class Slide {
		final BufferedImage img;
		final Point         start, end;
		int frame;   // negative while waiting for the stagger delay to elapse

		Slide(BufferedImage img, Point start, Point end, int delayFrames) {
			this.img   = img;
			this.start = start;
			this.end   = end;
			this.frame = -delayFrames;
		}
	}

	private final List<Slide> slides = new ArrayList<>();
	private final Timer       timer;

	public CardSlideAnimator() {
		setOpaque(false);
		setFocusable(false);
		timer = new Timer(FRAME_MS, e -> tick());
		timer.setCoalesce(true);
	}

	/** Installs the animator on {@code frame}'s layered pane at DRAG_LAYER. */
	public static CardSlideAnimator install(JFrame frame) {
		CardSlideAnimator a  = new CardSlideAnimator();
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
	 * Queues a card-slide animation. Both points must already be in the
	 * layered-pane coordinate space and represent the center of the card.
	 * {@code delayFrames} ticks elapse before the card begins moving.
	 */
	public void startSlide(BufferedImage img, Point start, Point end, int delayFrames) {
		slides.add(new Slide(img, start, end, delayFrames));
		if (!timer.isRunning()) timer.start();
	}

	/** Never intercepts mouse events — the board components below stay active. */
	@Override
	public boolean contains(int x, int y) {
		return false;
	}

	private void tick() {
		slides.removeIf(s -> { s.frame++; return s.frame >= TOTAL_FRAMES; });
		if (slides.isEmpty()) timer.stop();
		repaint();
	}

	@Override
	protected void paintComponent(Graphics g) {
		if (slides.isEmpty()) return;
		Graphics2D g2 = (Graphics2D) g.create();
		g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
				RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		for (Slide s : slides) {
			if (s.frame <= 0) continue;   // still in stagger delay
			double t = easeIn((double) s.frame / TOTAL_FRAMES);
			int x = (int) Math.round(s.start.x + (s.end.x - s.start.x) * t);
			int y = (int) Math.round(s.start.y + (s.end.y - s.start.y) * t);
			g2.drawImage(s.img,
					x - CardAnimation.CARD_W / 2,
					y - CardAnimation.CARD_H / 2,
					null);
		}
		g2.dispose();
	}

	/** Quadratic ease-in: card accelerates as it leaves the deck. */
	private static double easeIn(double t) {
		return t * t;
	}
}
