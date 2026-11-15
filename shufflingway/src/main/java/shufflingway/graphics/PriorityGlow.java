package shufflingway.graphics;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.LinearGradientPaint;
import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/**
 * A faint glow beside the board's centre line that shows who holds priority: red above the line,
 * on the opponent's side, while they hold it; blue below it, on yours, while you do.
 *
 * <p>Painted by every panel the glow crosses ({@link #addPainter}), each calling {@link #paint}
 * from its own {@code paintComponent} — so it lies behind that panel's contents and never tints a
 * card. More than one panel is needed because two meet at the line on each side: the board strip
 * that touches it, which shrinks to nothing as the zones grow, and the zone backdrop beyond.
 * Each paints the slice of the glow that falls inside it.
 *
 * <p>A change of priority fades over {@link #FADE_MS} rather than switching, so a hand-off that
 * comes straight back — the CPU passing — reads as a flicker of colour, not a flash.
 */
public final class PriorityGlow {

	private static final Color OPPONENT = new Color(225, 55, 55);
	private static final Color YOU      = new Color(60, 125, 235);

	/** The glow's opacity at the line itself, fading to nothing {@link #depth} away. */
	private static final float PEAK_ALPHA = 0.20f;

	/** Where the fade stands, as a fraction of the peak, a third of the way out: a glow, not a stripe. */
	private static final float[] FALLOFF_AT = { 0f, 0.33f, 1f };
	private static final float[] FALLOFF    = { 1f, 0.40f, 0f };

	private static final int FADE_MS = 200;

	private final int depth;
	private final List<JComponent> painters = new ArrayList<>();
	private final Timer fader;
	private JComponent line;
	private BooleanSupplier shown = () -> true;

	private boolean youHavePriority = true;
	/** How strongly each side glows now, 0 to 1 — moving towards the side that holds priority. */
	private float yours = 1f, theirs = 0f;
	private long fadeStart;
	private float yoursFrom, theirsFrom;

	/** @param depth how far, in pixels, the glow reaches from the line into each side */
	public PriorityGlow(int depth) {
		this.depth = depth;
		fader = new Timer(15, e -> step());
	}

	/** The centre line the glow lies against. */
	public void setLine(JComponent line) {
		this.line = line;
	}

	/** A panel the glow may cross: it calls {@link #paint} first thing in its {@code paintComponent}. */
	public void addPainter(JComponent panel) {
		painters.add(panel);
	}

	/** When the glow shows at all — while a game is in progress, not over an empty board. */
	public void setShown(BooleanSupplier shown) {
		this.shown = shown;
		repaintBand();
	}

	/** Who holds priority now: you ({@code true}) or your opponent. */
	public void setYouHavePriority(boolean you) {
		if (you == youHavePriority && !fader.isRunning()) {
			repaintBand();   // a game starting: the glow appears without anything having changed
			return;
		}
		youHavePriority = you;
		yoursFrom  = yours;
		theirsFrom = theirs;
		fadeStart  = System.currentTimeMillis();
		if (!fader.isRunning()) fader.start();
	}

	private void step() {
		float t = Math.min(1f, (System.currentTimeMillis() - fadeStart) / (float) FADE_MS);
		yours  = yoursFrom  + ((youHavePriority ? 1f : 0f) - yoursFrom)  * t;
		theirs = theirsFrom + ((youHavePriority ? 0f : 1f) - theirsFrom) * t;
		if (t >= 1f) fader.stop();
		repaintBand();
	}

	/** Repaints only the strip the glow can occupy, in each painter. */
	private void repaintBand() {
		if (line == null) return;
		for (JComponent p : painters) {
			Point top = SwingUtilities.convertPoint(line, 0, 0, p);
			int y0 = top.y - depth, y1 = top.y + line.getHeight() + depth;
			if (y1 >= 0 && y0 <= p.getHeight()) p.repaint(0, y0, p.getWidth(), y1 - y0);
		}
	}

	/** Paints the part of the glow that falls inside {@code panel}, which is one of the {@link #addPainter painters}. */
	public void paint(JComponent panel, Graphics g) {
		if (line == null || line.getParent() == null || !shown.getAsBoolean()) return;
		Point top = SwingUtilities.convertPoint(line, 0, 0, panel);
		int above = top.y, below = top.y + line.getHeight();
		if (below + depth < 0 || above - depth > panel.getHeight()) return;
		Graphics2D g2 = (Graphics2D) g.create();
		band(g2, panel.getWidth(), above, above - depth, OPPONENT, theirs);
		band(g2, panel.getWidth(), below, below + depth, YOU, yours);
		g2.dispose();
	}

	/** A band from {@code edge}, at full strength, fading out ({@link #FALLOFF}) by {@code far}. */
	private static void band(Graphics2D g2, int width, int edge, int far, Color c, float level) {
		if (level <= 0.01f || edge == far) return;
		Color[] stops = new Color[FALLOFF.length];
		for (int i = 0; i < stops.length; i++)
			stops[i] = new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.round(255 * PEAK_ALPHA * level * FALLOFF[i]));
		g2.setPaint(new LinearGradientPaint(0, edge, 0, far, FALLOFF_AT, stops));
		g2.fillRect(0, Math.min(edge, far), width, Math.abs(far - edge));
	}
}
