package shufflingway.graphics;

import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import javax.swing.JPanel;
import javax.swing.UIManager;

public class GradientPanel extends JPanel {
	private Color          gradientColor;
	private final boolean  colorAtTop;

	public GradientPanel(boolean colorAtTop) {
		this.colorAtTop = colorAtTop;
	}

	public void setGradientColor(Color c) {
		this.gradientColor = c;
		repaint();
	}

	@Override
	protected void paintComponent(Graphics g) {
		super.paintComponent(g);
		Color base   = UIManager.getColor("Panel.background");
		Color top    = colorAtTop && gradientColor != null ? gradientColor : base;
		Color bottom = !colorAtTop && gradientColor != null ? gradientColor : base;
		Graphics2D g2d = (Graphics2D) g.create();
		g2d.setPaint(new GradientPaint(0, 0, top, 0, getHeight(), bottom));
		g2d.fillRect(0, 0, getWidth(), getHeight());
		g2d.dispose();
	}
}
