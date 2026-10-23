package shufflingway.graphics;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;

import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLayeredPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import shufflingway.FontLoader;
import shufflingway.UiScale;

/**
 * The end-of-game banner: the winner's half of the board dims by a fifth behind a floating
 * translucent blue window in the style of a classic Final Fantasy menu — top-to-bottom blue
 * gradient, smooth white border — reading "WINNER", and the loser's half dims by 60% under
 * "GAME OVER". Both dims are plain black, so the field colours darken rather than shift. Both fade
 * in together.
 *
 * <p>The halves are read live from {@code board} and {@code divider} at paint time, so the overlay
 * follows the board through resizes and side-panel moves without being told. It sits just above
 * {@link SlotLiftOverlay} and below the popup and animation layers, so menus and tooltips still draw
 * over it, and it never takes mouse events — the final board stays inspectable underneath.
 */
public class GameOverOverlay extends JComponent {

	private static final int FADE_MS  = 400;
	private static final int FRAME_MS = 16;

	private static final String WIN_TEXT  = "WINNER";
	private static final String LOSE_TEXT = "GAME OVER";
	private static final float  TEXT_SIZE = 44f;

	/** Black laid over each half: 25% behind the winner's window, 50% under the loser's text. */
	private static final Color WIN_DIM  = new Color(0, 0, 0, 51);
	private static final Color LOSE_DIM = new Color(0, 0, 0, 153);

	/** Window fill, sampled from the FF7 menu: royal blue at the top falling to navy at the foot. */
	private static final Color WINDOW_TOP    = new Color(64, 80, 200, 205);
	private static final Color WINDOW_BOTTOM = new Color(8, 8, 80, 205);
	/** Border, outermost to innermost: a dark keyline, the white frame, a grey bevel. */
	private static final Color BORDER_OUTER = new Color(16, 16, 40);
	private static final Color BORDER_FRAME = new Color(232, 232, 240);
	private static final Color BORDER_INNER = new Color(120, 120, 150);

	private static final Color TEXT        = Color.WHITE;
	private static final Color TEXT_SHADOW = new Color(40, 40, 56);

	private final JComponent board;
	private final JComponent divider;
	private final Timer      timer;

	/** Whether a result is up, and if so which half won. */
	private boolean showing;
	private boolean winnerIsBottom;
	private float   alpha;

	private GameOverOverlay(JComponent board, JComponent divider) {
		this.board   = board;
		this.divider = divider;
		setOpaque(false);
		setFocusable(false);
		timer = new Timer(FRAME_MS, e -> tick());
		timer.setCoalesce(true);
	}

	/**
	 * Installs the overlay on {@code frame}'s layered pane. {@code board} is the panel holding both
	 * players' halves; {@code divider} is the line between them, whose centre splits the two.
	 */
	public static GameOverOverlay install(JFrame frame, JComponent board, JComponent divider) {
		GameOverOverlay o  = new GameOverOverlay(board, divider);
		JLayeredPane    lp = frame.getRootPane().getLayeredPane();
		o.setBounds(0, 0, lp.getWidth(), lp.getHeight());
		lp.add(o, Integer.valueOf(JLayeredPane.PALETTE_LAYER + 1));
		lp.addComponentListener(new ComponentAdapter() {
			@Override public void componentResized(ComponentEvent e) {
				o.setBounds(0, 0, lp.getWidth(), lp.getHeight());
			}
		});
		return o;
	}

	/** Shows the result, the bottom seat (P1) winning when {@code winnerIsBottom}. */
	public void showResult(boolean winnerIsBottom) {
		this.winnerIsBottom = winnerIsBottom;
		showing = true;
		alpha   = 0f;
		timer.start();
		repaint();
	}

	/** Removes the result, for a new game. */
	public void clear() {
		timer.stop();
		showing = false;
		alpha   = 0f;
		repaint();
	}

	/** Never intercepts mouse events — the board components below stay active. */
	@Override
	public boolean contains(int x, int y) {
		return false;
	}

	private void tick() {
		alpha = Math.min(1f, alpha + (float) FRAME_MS / FADE_MS);
		if (alpha >= 1f) timer.stop();
		repaint();
	}

	@Override
	protected void paintComponent(Graphics g) {
		if (!showing || alpha <= 0f || !board.isShowing()) return;

		Rectangle b = SwingUtilities.convertRectangle(board.getParent(), board.getBounds(), this);
		int splitY = divider.isShowing()
				? SwingUtilities.convertPoint(divider, 0, divider.getHeight() / 2, this).y
				: b.y + b.height / 2;
		Rectangle top    = new Rectangle(b.x, b.y, b.width, splitY - b.y);
		Rectangle bottom = new Rectangle(b.x, splitY, b.width, b.y + b.height - splitY);

		Graphics2D g2 = (Graphics2D) g.create();
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
		g2.setFont(FontLoader.loadOverlayFont(TEXT_SIZE));

		paintWinner(g2, winnerIsBottom ? bottom : top);
		paintLoser (g2, winnerIsBottom ? top : bottom);
		g2.dispose();
	}

	private void paintLoser(Graphics2D g2, Rectangle half) {
		g2.setColor(LOSE_DIM);
		g2.fill(half);
		Rectangle2D ink = inkBounds(g2, LOSE_TEXT);
		drawShadowedText(g2, LOSE_TEXT,
				(float) (half.getCenterX() - ink.getCenterX()),
				(float) (half.getCenterY() - ink.getCenterY()));
	}

	private void paintWinner(Graphics2D g2, Rectangle half) {
		g2.setColor(WIN_DIM);
		g2.fill(half);
		Rectangle2D ink = inkBounds(g2, WIN_TEXT);
		int padX = UiScale.scale(36);
		int padY = UiScale.scale(22);
		int w = (int) Math.ceil(ink.getWidth())  + 2 * padX;
		int h = (int) Math.ceil(ink.getHeight()) + 2 * padY;
		int x = half.x + (half.width - w) / 2;
		int y = half.y + (half.height - h) / 2;

		float arc   = UiScale.scale(14f);
		float frame = Math.max(2f, UiScale.scale(4f));
		RoundRectangle2D outer = new RoundRectangle2D.Float(x, y, w, h, arc, arc);

		g2.setPaint(new GradientPaint(0, y, WINDOW_TOP, 0, y + h, WINDOW_BOTTOM));
		g2.fill(outer);

		// The frame is stroked centred on an inset outline, so it lies wholly inside the window.
		float inset = frame / 2f;
		g2.setStroke(new BasicStroke(frame));
		g2.setColor(BORDER_FRAME);
		g2.draw(new RoundRectangle2D.Float(x + inset, y + inset, w - frame, h - frame,
				arc - frame, arc - frame));
		g2.setStroke(new BasicStroke(1f));
		g2.setColor(BORDER_INNER);
		g2.draw(new RoundRectangle2D.Float(x + frame, y + frame, w - 2 * frame, h - 2 * frame,
				arc - 2 * frame, arc - 2 * frame));
		g2.setColor(BORDER_OUTER);
		g2.draw(outer);

		drawShadowedText(g2, WIN_TEXT,
				(float) (x + w / 2.0 - ink.getCenterX()),
				(float) (y + h / 2.0 - ink.getCenterY()));
	}

	/**
	 * The ink bounds of {@code text} relative to its baseline origin. Centring on these rather than
	 * on font metrics keeps the pixel face's generous ascent from pushing the text off-centre.
	 */
	private static Rectangle2D inkBounds(Graphics2D g2, String text) {
		return g2.getFont().createGlyphVector(g2.getFontRenderContext(), text).getVisualBounds();
	}

	private static void drawShadowedText(Graphics2D g2, String text, float x, float baseline) {
		float off = Math.max(1, UiScale.scale(3));
		g2.setColor(TEXT_SHADOW);
		g2.drawString(text, x + off, baseline + off);
		g2.setColor(TEXT);
		g2.drawString(text, x, baseline);
	}
}
