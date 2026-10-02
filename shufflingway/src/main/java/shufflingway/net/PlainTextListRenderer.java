package shufflingway.net;

import javax.swing.DefaultListCellRenderer;

/**
 * A list cell renderer that shows its text as written, never as HTML.
 *
 * <p>Swing labels, list cells among them, render any text starting with {@code <html>} as HTML,
 * which can restyle the cell and load images from a URL. These lists show names other players
 * chose (lobby names, host names), so markup in one is shown as the characters it is made of.
 * Set here, before any text is, so it holds from the first row on.
 */
class PlainTextListRenderer extends DefaultListCellRenderer {

    PlainTextListRenderer() {
        putClientProperty("html.disable", Boolean.TRUE);
    }
}
