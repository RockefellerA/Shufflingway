package shufflingway;

import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URL;
import java.sql.SQLException;
import java.util.concurrent.ConcurrentHashMap;

import javax.imageio.ImageIO;

import dev.matrixlab.webp4j.WebPCodec;
import scraper.CardDatabase;

/**
 * Session-level image cache that layers three sources:
 * <ol>
 *   <li><b>Memory</b> — a {@link ConcurrentHashMap} keyed by image URL, populated
 *       on first access and retained for the lifetime of the JVM.</li>
 *   <li><b>Database</b> — the {@code image_data} BLOB column in
 *       {@code shufflingway.db}.  Populated from the network on first download.</li>
 *   <li><b>Network</b> — falls back to an HTTP download when no blob is stored.
 *       The downloaded bytes are automatically persisted to the DB so subsequent
 *       sessions skip the network entirely.</li>
 * </ol>
 *
 * <p>All DB access is serialised through a single long-lived {@link CardDatabase}
 * connection.  Call {@link #shutdown()} once on application exit to close it.</p>
 */
public final class ImageCache {

    private static final float WEBP_QUALITY = 82.0f;

    private static final ConcurrentHashMap<String, byte[]> mem = new ConcurrentHashMap<>();
    private static CardDatabase db;

    private ImageCache() {}

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Returns a decoded {@link Image} for the given URL.
     * Sources are tried in order: memory → DB → network.
     * A successful network fetch is persisted to the DB automatically.
     *
     * @param url image URL; {@code null} returns {@code null}
     * @throws IOException if a network fetch was required and failed
     */
    public static Image load(String url) throws IOException {
        if (url == null) return null;

        byte[] bytes = mem.get(url);

        if (bytes == null) {
            bytes = fetchFromDb(url);
            if (bytes != null) mem.putIfAbsent(url, bytes);
        }

        if (bytes == null) {
            byte[] raw = fetchFromNetwork(url);
            if (raw != null) {
                bytes = toWebP(raw);
                mem.putIfAbsent(url, bytes);
                persistToDb(url, bytes);
            }
        }

        if (bytes == null) return null;
        if (isWebP(bytes)) return WebPCodec.decodeImage(bytes);
        return ImageIO.read(new ByteArrayInputStream(bytes));
    }

    /** Closes the backing DB connection.  Safe to call even if never opened. */
    public static synchronized void shutdown() {
        if (db != null) {
            try { db.close(); } catch (SQLException ignored) {}
            db = null;
        }
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private static synchronized byte[] fetchFromDb(String url) {
        try {
            return openDb().getImageData(url);
        } catch (Exception e) {
            return null;
        }
    }

    private static synchronized void persistToDb(String url, byte[] bytes) {
        try {
            int rows = openDb().saveImageDataByUrl(url, bytes);
            if (rows == 0) {
                System.err.println("[ImageCache] persistToDb: no card row has image_url = " + url);
            }
        } catch (Exception e) {
            System.err.println("[ImageCache] persistToDb failed for " + url + ": " + e.getMessage());
        }
    }

    /** Opens the DB connection on first use; reuses it thereafter. */
    private static CardDatabase openDb() throws SQLException {
        if (db == null) db = new CardDatabase(scraper.AppPaths.dbPath());
        return db;
    }

    private static byte[] fetchFromNetwork(String url) throws IOException {
        try (var in  = new URL(url).openStream();
             var out = new ByteArrayOutputStream()) {
            in.transferTo(out);
            return out.toByteArray();
        }
    }

    private static byte[] toWebP(byte[] raw) throws IOException {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(raw));
        if (img == null) return raw;
        return WebPCodec.encodeImage(img, WEBP_QUALITY);
    }

    private static boolean isWebP(byte[] b) {
        return b.length > 11
            && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
            && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P';
    }
}
