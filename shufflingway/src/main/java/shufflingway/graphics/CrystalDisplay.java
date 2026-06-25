package shufflingway.graphics;

import shufflingway.ElementColor;
import shufflingway.FontLoader;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * A compact custom component that renders an elongated upright hexagon
 * (point-top, flat left/right sides) in crystal-blue and draws ": N"
 * centred inside it, where N is the current Crystal count.
 */
public class CrystalDisplay extends javax.swing.JComponent {
	public static final int CRYSTAL_H = 36;  // component height

	private int     count;
	/** Once true (count has been > 0 this game), the display stays visible. */
	private boolean hasBeenNonZero = false;
	/** Index into ElementColor.values(); starts at ICE to match the original blue. */
	private int     colorIndex     = ElementColor.ICE.ordinal();

	public CrystalDisplay(int initial) {
		this.count = initial;
		setPreferredSize(new Dimension(CardAnimation.CARD_W, CRYSTAL_H));
		setMinimumSize(new Dimension(CardAnimation.CARD_W, CRYSTAL_H));
		setMaximumSize(new Dimension(CardAnimation.CARD_W, CRYSTAL_H));
		setOpaque(false);
		setToolTipText("Crystals — click to change element color");
		addMouseListener(new MouseAdapter() {
			@Override public void mousePressed(MouseEvent e) {
				colorIndex = (colorIndex + 1) % ElementColor.values().length;
				repaint();
			}
		});
	}

	/** Updates count, latches persistence when count first exceeds zero, then repaints. */
	public void setCount(int n) {
		this.count = n;
		if (n > 0) hasBeenNonZero = true;
		repaint();
	}

	/** Fully resets for a new game: count and persistence flag. */
	public void hardReset() {
		count          = 0;
		hasBeenNonZero = false;
		repaint();
	}

	@Override
	protected void paintComponent(Graphics g0) {
		super.paintComponent(g0);
		if (!hasBeenNonZero) return;
		Graphics2D g = (Graphics2D) g0.create();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

		int cw = getWidth(), ch = getHeight();

		// Elongated upright hexagon: 22 px wide, 32 px tall, centred
		int hxW = 22, hxH = 32;
		int hxX = (cw - hxW) / 2;
		int hxY = (ch - hxH) / 2;

		int shoulder = hxH / 5;
		int[] xp = { hxX + hxW/2, hxX + hxW, hxX + hxW, hxX + hxW/2, hxX,                   hxX           };
		int[] yp = { hxY,          hxY + shoulder, hxY + hxH - shoulder, hxY + hxH, hxY + hxH - shoulder, hxY + shoulder };

		Color base = ElementColor.values()[colorIndex].color;
		g.setColor(new Color(base.getRed(), base.getGreen(), base.getBlue(), 210));
		g.fillPolygon(xp, yp, 6);
		g.setColor(base.darker());
		g.setStroke(new BasicStroke(1.5f));
		g.drawPolygon(xp, yp, 6);

		// Count number centred inside the hexagon (no colon)
		g.setFont(FontLoader.loadPixelNESFont(10));
		g.setColor(Color.WHITE);
		String text = String.valueOf(count);
		FontMetrics fm = g.getFontMetrics();
		int tx = (cw - fm.stringWidth(text)) / 2;
		int ty = hxY + hxH / 2 + fm.getAscent() / 2 - 1;
		g.drawString(text, tx, ty);

		g.dispose();
	}
}
