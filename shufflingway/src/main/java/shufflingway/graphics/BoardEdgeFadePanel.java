package shufflingway.graphics;

import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;

import javax.swing.JPanel;
import javax.swing.UIManager;

/**
 * A player's zone backdrop: flat element colour, with an optional {@link BoardTexture} over it,
 * over most of its height, fading to the board's neutral tone over the last {@code fadeHeight}
 * pixels of its centre-facing edge.
 *
 * <p>The board gradient used to live entirely in the strip between the two zones. That strip is
 * whatever the zones leave over — a few dozen pixels at best, and far less once a zone grows — and
 * a gradient with no room to fade reads as a hard bright line rather than a horizon. Fading inside
 * the zone itself gives it as much room as it needs, and lets the gradient begin above the Forward
 * row's scrollbar so the bar sits over the board rather than over flat colour.
 *
 * <p>Only the far side is flat, so the zone's own furniture — deck, Break Zone, damage — still sits
 * on solid colour. Whatever occupies the fade region must be non-opaque for the fade to show
 * through; the Forward zone's scroll pane and its inner panels already are.
 *
 * <p>{@code fadeAtBottom} names the centre-facing edge: true for the top seat (P2, centre is below
 * it), false for the bottom seat (P1).
 */
public class BoardEdgeFadePanel extends JPanel {

	private final boolean fadeAtBottom;
	private int fadeHeight;

	public BoardEdgeFadePanel(boolean fadeAtBottom, int fadeHeight) {
		this.fadeAtBottom = fadeAtBottom;
		this.fadeHeight   = Math.max(0, fadeHeight);
	}

	/**
	 * Sets how deep the fade reaches into the zone. Keep it inside the band where the zone's own
	 * columns have already ended (or are non-opaque), or their solid backgrounds will cut a step
	 * across the fade.
	 */
	public void setFadeHeight(int px) {
		int v = Math.max(0, px);
		if (v == fadeHeight) return;
		fadeHeight = v;
		repaint();
	}

	private PriorityGlow glow;

	/**
	 * The priority glow, painted over this backdrop and under everything on it. It must reach no
	 * deeper than the fade, where the zone's contents are non-opaque, or their backgrounds cut it.
	 */
	public void setGlow(PriorityGlow glow) {
		this.glow = glow;
		glow.addPainter(this);
	}

	private BoardTexture texture = BoardTexture.NONE;
	private int textureVariant;

	/**
	 * The surface laid over the zone's colour, grown from {@code variant}'s seeds: give each seat its
	 * own, so the two fields are not copies. It fades out toward the centre with the colour. It shows
	 * only where the zone's contents are non-opaque, which is everywhere but the damage stack.
	 */
	public void setTexture(BoardTexture texture, int variant) {
		BoardTexture t = texture == null ? BoardTexture.NONE : texture;
		if (t == this.texture && variant == textureVariant) return;
		this.texture = t;
		this.textureVariant = variant;
		repaint();
	}

	@Override
	protected void paintComponent(Graphics g) {
		int w = getWidth(), h = getHeight();
		if (w <= 0 || h <= 0) return;

		Color flat = getBackground();
		Color edge = UIManager.getColor("Panel.background");
		if (flat == null) flat = edge;
		int f = Math.min(fadeHeight, h);

		// The colour and texture cover the whole zone, and the fade is the neutral tone laid over
		// them from clear to solid, so the texture fades out with the colour.
		Graphics2D g2 = (Graphics2D) g.create();
		g2.setColor(flat);
		g2.fillRect(0, 0, w, h);
		texture.paint(g2, w, h, textureVariant);
		if (f > 0) {
			Color clear = new Color(edge.getRed(), edge.getGreen(), edge.getBlue(), 0);
			if (fadeAtBottom) {
				g2.setPaint(new GradientPaint(0, h - f, clear, 0, h, edge));
				g2.fillRect(0, h - f, w, f);
			} else {
				g2.setPaint(new GradientPaint(0, f, clear, 0, 0, edge));
				g2.fillRect(0, 0, w, f);
			}
		}
		g2.dispose();
		if (glow != null) glow.paint(this, g);
	}
}
