package scraper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Loads hand-authored card data for printings the official Square Enix API does not return —
 * promos, starter-deck exclusives, pre-release cards.
 *
 * <p>The data lives in {@code /resources/non_api_cards.json}, whose {@code _readme} block is the
 * authoring reference. Everything here is validation: the file is edited by hand, so a typo has
 * to fail the ETL rather than quietly produce a card that is missing an ability or has no
 * element. Unknown keys are rejected for that reason — {@code "catagory1"} would otherwise drop
 * a card's category without a word.
 *
 * <p>An editable copy can be dropped in the working directory as {@code non_api_cards.json}; it
 * takes precedence over the packaged resource, so cards can be added and re-scraped without a
 * rebuild.
 */
public final class NonApiCards {

    /** Classpath location of the packaged data file. */
    private static final String RESOURCE = "/resources/non_api_cards.json";

    /** Working-directory override, checked first so edits don't need a rebuild. */
    private static final Path OVERRIDE = Path.of("non_api_cards.json");

    private static final Set<String> TYPES =
            Set.of("Forward", "Backup", "Summon", "Monster", "Crystal");

    private static final Set<String> ELEMENTS =
            Set.of("Fire", "Ice", "Wind", "Earth", "Lightning", "Water", "Light", "Dark");

    private static final Set<String> RARITIES =
            Set.of("C", "R", "H", "L", "S", "PR", "B");

    /** Every key a card object may carry. Anything else is a typo, and fails the load. */
    private static final Set<String> CARD_KEYS = Set.of(
            "serial", "name", "type", "element", "cost", "power", "rarity", "job",
            "category1", "category2", "exBurst", "multicard", "text", "imageUrl", "_note");

    private NonApiCards() {}

    /**
     * Reads and validates the non-API card file.
     *
     * @return the cards in file order, or an empty list if the file defines none
     * @throws IllegalStateException if the file is missing, malformed, or defines an invalid card
     */
    public static List<ScrapedCard> load() {
        return parse(read());
    }

    /** Reads the override file if present, otherwise the packaged resource. */
    private static String read() {
        try {
            if (Files.isRegularFile(OVERRIDE)) {
                System.out.printf("Reading non-API cards from %s%n", OVERRIDE.toAbsolutePath());
                return Files.readString(OVERRIDE, StandardCharsets.UTF_8);
            }
            try (InputStream is = NonApiCards.class.getResourceAsStream(RESOURCE)) {
                if (is == null) throw new IllegalStateException(RESOURCE + " is not on the classpath");
                return new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not read non-API card data: " + e.getMessage(), e);
        }
    }

    /** Package-private so tests can exercise validation without touching the filesystem. */
    static List<ScrapedCard> parse(String json) {
        JSONArray entries;
        try {
            entries = new JSONObject(json).getJSONArray("cards");
        } catch (JSONException e) {
            throw new IllegalStateException(
                    "non_api_cards.json must be an object with a \"cards\" array: " + e.getMessage(), e);
        }

        List<ScrapedCard> cards = new ArrayList<>(entries.length());
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < entries.length(); i++) {
            JSONObject o;
            try {
                o = entries.getJSONObject(i);
            } catch (JSONException e) {
                throw new IllegalStateException("cards[" + i + "] is not an object", e);
            }
            ScrapedCard card = toCard(o, "cards[" + i + "]");
            if (!seen.add(card.serial))
                throw new IllegalStateException("cards[" + i + "]: duplicate serial " + card.serial);
            cards.add(card);
        }
        return cards;
    }

    private static ScrapedCard toCard(JSONObject o, String at) {
        for (String key : o.keySet())
            if (!CARD_KEYS.contains(key))
                throw new IllegalStateException(at + ": unknown key \"" + key + "\"");

        ScrapedCard c = new ScrapedCard();
        c.serial = required(o, "serial", at);
        String where = at + " (" + c.serial + ")";
        c.nameEn = required(o, "name", where);
        c.typeEn = required(o, "type", where);
        if (!TYPES.contains(c.typeEn))
            throw new IllegalStateException(
                    where + ": unknown type \"" + c.typeEn + "\"; expected one of " + TYPES);

        c.element = optional(o, "element");
        if (c.element != null)
            for (String part : c.element.split("/"))
                if (!ELEMENTS.contains(part))
                    throw new IllegalStateException(
                            where + ": unknown element \"" + part + "\" in \"" + c.element + "\"");

        if (!o.has("cost")) throw new IllegalStateException(where + ": missing \"cost\"");
        c.cost = intAt(o, "cost", where);
        if (c.cost < 0) throw new IllegalStateException(where + ": cost may not be negative");

        c.power = o.isNull("power") ? null : intAt(o, "power", where);
        if (c.power != null && c.power < 0)
            throw new IllegalStateException(where + ": power may not be negative");

        c.rarity = optional(o, "rarity");
        if (c.rarity != null && !RARITIES.contains(c.rarity))
            throw new IllegalStateException(
                    where + ": unknown rarity \"" + c.rarity + "\"; expected one of " + RARITIES);

        c.jobEn     = optional(o, "job");
        c.category1 = optional(o, "category1");
        c.category2 = optional(o, "category2");
        c.exBurst   = o.optBoolean("exBurst", false);
        c.multicard = o.optBoolean("multicard", false);
        c.textEn    = text(o, where);
        c.imageUrl  = optional(o, "imageUrl");
        return c;
    }

    /**
     * Card text, given either as one string or as an array of strings joined with a space.
     * The array form exists only so a long ability can be wrapped in the file; it carries no
     * meaning of its own, and ability boundaries still have to be spelled out as {@code [[br]]}.
     */
    private static String text(JSONObject o, String where) {
        if (!o.has("text") || o.isNull("text")) return null;
        Object raw = o.get("text");
        if (raw instanceof String s) return s.isBlank() ? null : s.trim();
        if (raw instanceof JSONArray arr) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < arr.length(); i++) {
                if (i > 0) sb.append(' ');
                sb.append(arr.getString(i).trim());
            }
            String joined = sb.toString().trim();
            return joined.isEmpty() ? null : joined;
        }
        throw new IllegalStateException(where + ": \"text\" must be a string or an array of strings");
    }

    private static String required(JSONObject o, String key, String where) {
        String v = optional(o, key);
        if (v == null) throw new IllegalStateException(where + ": missing or blank \"" + key + "\"");
        return v;
    }

    /** Returns the trimmed string at {@code key}, or null when absent, null or blank. */
    private static String optional(JSONObject o, String key) {
        if (!o.has(key) || o.isNull(key)) return null;
        String v = o.get(key).toString().trim();
        return v.isEmpty() ? null : v;
    }

    private static int intAt(JSONObject o, String key, String where) {
        try {
            return o.getInt(key);
        } catch (JSONException e) {
            throw new IllegalStateException(where + ": \"" + key + "\" must be a whole number", e);
        }
    }
}
