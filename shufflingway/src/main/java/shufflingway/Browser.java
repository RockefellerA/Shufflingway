package shufflingway;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;

/** Opens web pages in the user's default browser. */
public final class Browser {

    private Browser() {}

    /** Opens {@code url} in the default browser; does nothing where the desktop has no browser to hand it to. */
    public static void open(String url) {
        if (!Desktop.isDesktopSupported()
                || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) return;
        try {
            Desktop.getDesktop().browse(new URI(url));
        } catch (IOException | URISyntaxException ex) {
            ex.printStackTrace();
        }
    }
}
