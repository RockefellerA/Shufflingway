package shufflingway;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Links to TCGplayer, where the cards Shufflingway shows can be bought. Not yet in use: the Deck
 * Manager's Buy Deck button that opens {@link #deckUrl} is commented out until Shufflingway is
 * accepted into TCGplayer's affiliate programme and {@link #affiliate} can wrap its links.
 *
 * <p>Mass Entry finds cards by TCGplayer's names, which are not always the card's own: TCGplayer
 * tells apart the printings that share a name ("Cloud (Common)", "Squire (Male Unit)",
 * "Machina LB") and adds to others ("Cocytus EX"). Each line therefore names its card as
 * TCGplayer does, read from the {@code names} of {@link #MAPPING}, which
 * {@code tools/tcgplayer_mapping.py} generates from TCGplayer's catalog.
 *
 * <p>Checked in a browser on 2026-11-27: a {@link #deckUrl} opens Mass Entry for Final Fantasy
 * TCG with the deck's list filled in, where the buyer then chooses foil or non-foil and the like.
 * The two cards it could not find then, Cocytus and Squire, were the ones TCGplayer names
 * differently, which the {@code names} now cover.
 */
public final class TcgPlayer {

    private TcgPlayer() {}

    /**
     * Shown beside every link that could earn Shufflingway a commission, not only in About, so
     * the user knows before they click.
     */
    public static final String DISCLOSURE =
            "Shufflingway may earn a commission on purchases made through this link.";

    /** TCGplayer's Mass Entry page, which fills the cart from a typed list of cards. */
    static final String MASS_ENTRY = "https://www.tcgplayer.com/massentry";

    /** The product line Mass Entry is set to, as TCGplayer names the game. */
    static final String PRODUCT_LINE = "Final Fantasy TCG";

    /** Between Mass Entry lines in the {@code c} parameter. */
    private static final String LINE_BREAK = "||";

    /** Each card's TCGplayer product, and TCGplayer's name for the cards it names differently. */
    static final String MAPPING = "/data/tcgplayer_products.json";

    /** TCGplayer's names, by serial, for the cards it names differently; read once, on first use. */
    private static final class Names {
        static final Map<String, String> BY_SERIAL = load();

        private static Map<String, String> load() {
            try (InputStream in = TcgPlayer.class.getResourceAsStream(MAPPING)) {
                if (in == null) return Map.of();
                JSONObject names = new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8))
                        .optJSONObject("names");
                if (names == null) return Map.of();
                Map<String, String> out = new HashMap<>();
                for (String serial : names.keySet()) out.put(serial, names.getString(serial));
                return Map.copyOf(out);
            } catch (IOException | JSONException e) {
                // Every card then goes by its own name, which Mass Entry finds for most of them.
                return Map.of();
            }
        }
    }

    /** {@code serial}'s card as TCGplayer names it: its entry in the mapping's names, or {@code name}. */
    static String tcgplayerName(String serial, String name) {
        return serial == null ? name : Names.BY_SERIAL.getOrDefault(serial, name);
    }

    /**
     * {@code destination} as an affiliate link, so the purchases it leads to are credited to
     * Shufflingway. Every link to TCGplayer goes out through here.
     *
     * <p>Returns {@code destination} unchanged until the programme approves Shufflingway and its
     * link format is known: typically the programme's tracking address with {@code destination}
     * URL-encoded into one of its parameters.
     */
    static String affiliate(String destination) {
        return destination;
    }

    /**
     * A link to Mass Entry with {@code rows}' cards filled in, through {@link #affiliate}. The
     * deck's Limit Break cards belong in it with the rest: they are cards the player needs too.
     *
     * @param rows the deck as the Deck Manager holds it: copies, serial, name, …; rows with no
     *             copies are left out
     */
    static String deckUrl(Collection<Object[]> rows) {
        List<String> lines = new ArrayList<>();
        for (Object[] r : rows) {
            int copies = r[0] instanceof Number n ? n.intValue() : 0;
            if (copies > 0) lines.add(massEntryLine(copies, (String) r[2], (String) r[1]));
        }
        return affiliate(MASS_ENTRY
                + "?productline=" + encode(PRODUCT_LINE)
                + "&c=" + encode(String.join(LINE_BREAK, lines)));
    }

    /** One Mass Entry line: the copies and the card as TCGplayer names it, as "3 Cloud (Common)". */
    static String massEntryLine(int copies, String name, String serial) {
        return copies + " " + tcgplayerName(serial, name);
    }

    /** {@code s} encoded for a query string, its spaces as {@code %20}, as browsers send them. */
    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
