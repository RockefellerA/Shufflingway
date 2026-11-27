package shufflingway;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;

import javax.swing.Icon;

/**
 * A green shopping cart, for the Deck Manager's Buy Deck button: a handle running down into the
 * frame, a filled basket on it, and two wheels. Drawn on a 16-unit grid and scaled to
 * {@code size}, so it can match the button text's height.
 */
final class CartIcon implements Icon {

    /** A mid green, which reads on the light and the dark themes alike. */
    private static final Color GREEN = new Color(0x2E, 0x9E, 0x4F);

    private final int size;

    CartIcon(int size) {
        this.size = Math.max(12, size);
    }

    @Override public int getIconWidth()  { return size; }
    @Override public int getIconHeight() { return size; }

    @Override
    public void paintIcon(Component c, Graphics g, int x, int y) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g2.translate(x, y);
            g2.scale(size / 16.0, size / 16.0);
            g2.setColor(GREEN);

            // The handle, then down the back of the frame and along under the basket.
            Path2D frame = new Path2D.Double();
            frame.moveTo(0.8, 2.0);
            frame.lineTo(3.2, 2.0);
            frame.lineTo(5.2, 11.0);
            frame.lineTo(13.6, 11.0);
            g2.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.draw(frame);

            // The basket: wider at the top, its front leaning in toward the wheels.
            Path2D basket = new Path2D.Double();
            basket.moveTo(3.7, 4.4);
            basket.lineTo(15.2, 4.4);
            basket.lineTo(13.9, 9.3);
            basket.lineTo(4.8, 9.3);
            basket.closePath();
            g2.fill(basket);

            g2.fill(new Ellipse2D.Double(4.6, 12.3, 2.8, 2.8));
            g2.fill(new Ellipse2D.Double(11.1, 12.3, 2.8, 2.8));
        } finally {
            g2.dispose();
        }
    }
}
