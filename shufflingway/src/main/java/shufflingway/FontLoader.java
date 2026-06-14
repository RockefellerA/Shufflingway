package shufflingway;

import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.io.InputStream;

public class FontLoader {

    private static Font baseFont;

    static {
        Font loaded = null;
        try {
            InputStream is = FontLoader.class.getResourceAsStream("/resources/fonts/Pixel_NES.otf");
            if (is == null) throw new IOException("Font resource not found");
            loaded = Font.createFont(Font.TRUETYPE_FONT, is);
            GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(loaded);
        } catch (FontFormatException | IOException e) {
            System.err.println("Failed to load Pixel NES font: " + e.getMessage());
        }
        baseFont = loaded;
    }

    public static Font loadPixelNESFont(float size) {
        float scaled = UiScale.scale(size);
        if (baseFont != null) return baseFont.deriveFont(scaled);
        return new Font("Arial", Font.PLAIN, (int) scaled);
    }
}