package shufflingway.graphics;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

import javax.swing.DefaultButtonModel;

import shufflingway.FontLoader;

/**
 * A player's Crystal count: an elongated upright hexagon (point-top, flat left/right sides) in the
 * player's colour, the count centred inside it, on a raised face like the bar's buttons beside it.
 * It is not a button to click: it never lights up, sinks or fires.
 */
public class CrystalDisplay extends BezelButton {
	public static final int CRYSTAL_H = 36;  // default height

	private int count;
	/** The hexagon's colour. Fixed, so the white count always reads against it. */
	private final Color color;

	public CrystalDisplay(int initial, Color color) {
		super("", Round.NONE);
		this.count = initial;
		this.color = color;
		// Left enabled, so it is drawn raised rather than dimmed, but with a model the mouse and
		// keyboard cannot arm, press or roll over.
		setModel(new DefaultButtonModel() {
			@Override public void setArmed(boolean b)    {}
			@Override public void setPressed(boolean b)  {}
			@Override public void setRollover(boolean b) {}
		});
		setFocusable(false);
		setPreferredSize(new Dimension(CardAnimation.CARD_W, CRYSTAL_H));
		setMinimumSize(new Dimension(CardAnimation.CARD_W, CRYSTAL_H));
		setMaximumSize(new Dimension(CardAnimation.CARD_W, CRYSTAL_H));
		setToolTipText("Crystals");
	}

	public void setCount(int n) {
		this.count = n;
		repaint();
	}

	/** Resets the count for a new game. */
	public void hardReset() {
		setCount(0);
	}

	@Override
	protected void paintComponent(Graphics g0) {
		super.paintComponent(g0);
		Graphics2D g = (Graphics2D) g0.create();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

		int cw = getWidth(), ch = getHeight();

		// Elongated upright hexagon, 11 wide to 16 tall, filling the height less 2px top and bottom,
		// centered: 22 x 32 in a 36px row.
		int hxH = Math.max(8, ch - 4);
		int hxW = Math.round(hxH * 11 / 16f);
		int hxX = (cw - hxW) / 2;
		int hxY = (ch - hxH) / 2;

		int shoulder = hxH / 5;
		int[] xp = { hxX + hxW/2, hxX + hxW, hxX + hxW, hxX + hxW/2, hxX,                   hxX           };
		int[] yp = { hxY,          hxY + shoulder, hxY + hxH - shoulder, hxY + hxH, hxY + hxH - shoulder, hxY + shoulder };

		g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 210));
		g.fillPolygon(xp, yp, 6);
		g.setColor(color.darker());
		g.setStroke(new BasicStroke(1.5f));
		g.drawPolygon(xp, yp, 6);

		// Count number centered inside the hexagon (no colon)
		g.setFont(FontLoader.loadOverlayFont(10));
		g.setColor(Color.WHITE);
		String text = String.valueOf(count);
		FontMetrics fm = g.getFontMetrics();
		int tx = (cw - fm.stringWidth(text)) / 2;
		int ty = hxY + hxH / 2 + fm.getAscent() / 2 - 1;
		g.drawString(text, tx, ty);

		g.dispose();
	}
}
