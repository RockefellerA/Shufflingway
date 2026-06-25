package shufflingway.graphics;

import java.awt.Image;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ColorConvertOp;

import javax.swing.ImageIcon;
import javax.swing.JLabel;

public class GrayscaleLabel extends JLabel {
	private String url;

	public GrayscaleLabel(String text) { super(text); }

	public void setUrl(String u) { this.url = u; }
	public String getUrl()       { return url; }

	@Override
	public void setIcon(javax.swing.Icon icon) {
		if (icon instanceof ImageIcon imageIcon) {
			Image src = imageIcon.getImage();
			BufferedImage buf = new BufferedImage(icon.getIconWidth(), icon.getIconHeight(), BufferedImage.TYPE_INT_ARGB);
			buf.getGraphics().drawImage(src, 0, 0, null);
			BufferedImage gray = new ColorConvertOp(ColorSpace.getInstance(ColorSpace.CS_GRAY), null).filter(buf, null);
			super.setIcon(new ImageIcon(gray));
		} else {
			super.setIcon(icon);
		}
	}
}
