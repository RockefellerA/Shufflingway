package shufflingway;

import java.util.Collection;
import java.util.Locale;

/**
 * The formats a game can be played in. A format decides which decks may be taken into a game;
 * the {@link Banlist}, when a game enables it, refuses some of those again.
 *
 * <ul>
 *   <li>{@link #STANDARD} — any legal 50-card deck.
 *   <li>{@link #L3}, {@link #L6} — every card, Limit Break cards included, from the latest 3 or 6
 *       sets, or a PR- promo.
 *   <li>{@link #TITLE} — one category's cards; see {@link TitleRules}. A Title game also plays
 *       by its own rules: a cast needs no CP of the card's Element, and the uniqueness rule is
 *       by card number rather than by name.
 * </ul>
 */
public enum DeckFormat {

    STANDARD("Standard", 0),
    L3("L3", 3),
    L6("L6", 6),
    TITLE("Title", 0);

    private final String label;
    /** How many of the latest sets the deck may draw from; 0 for no limit. */
    private final int setWindow;

    DeckFormat(String label, int setWindow) {
        this.label     = label;
        this.setWindow = setWindow;
    }

    /** The name shown in the UI. */
    public String label() { return label; }

    /** Whether the format can be chosen for a game. */
    public boolean available() { return true; }

    /**
     * The {@link Banlist} section this format plays under: each its own, named as the format is.
     * An empty section — L3 and L6 have nothing in theirs — bans nothing, as a missing one would.
     */
    public String banlistName() { return label; }

    /** Whether the format limits which sets a deck may draw from. */
    public boolean hasSetWindow() { return setWindow > 0; }

    /** The name sent on the wire and kept in settings; {@link #parse} reads it back. */
    public String id() { return name(); }

    /**
     * The format {@code id} names, or {@link #STANDARD} for none or one this build does not know —
     * what an older peer or an older settings file means by saying nothing.
     */
    public static DeckFormat parse(String id) {
        if (id == null) return STANDARD;
        try {
            return valueOf(id.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return STANDARD;
        }
    }

    /**
     * The numbered set one printing belongs to — "28-001C" is set 28 — or 0 for a PR- promo or
     * any other unnumbered prefix. For a reprint's combined serial ("13-071R/2-101H") this reads
     * only the first printing; {@link #latestSet} reads them all.
     */
    public static int setPrefix(String serial) {
        if (serial == null) return 0;
        int dash = serial.indexOf('-');
        if (dash <= 0) return 0;
        try {
            return Integer.parseInt(serial.substring(0, dash).strip());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** The newest numbered set among a serial's printings — 13 for "13-071R/2-101H" — or 0. */
    public static int latestSet(String serial) {
        if (serial == null) return 0;
        int latest = 0;
        for (String printing : serial.split("/")) latest = Math.max(latest, setPrefix(printing.strip()));
        return latest;
    }

    /**
     * Whether every card in {@code serials} is inside this format's set window, given that the
     * newest set in the card pool is {@code newestSet}. A card is inside it when any of its
     * printings is — a reprint in a recent set is legal there whatever set it first appeared in —
     * and a PR- promo always is. A format with no window allows every set; one with a window
     * allows nothing when the newest set is unknown.
     */
    public boolean setsAllow(Collection<String> serials, int newestSet) {
        if (setWindow == 0) return true;
        if (newestSet <= 0) return false;
        int oldest = newestSet - setWindow + 1;
        for (String serial : serials) {
            if (serial != null && !inWindow(serial, oldest)) return false;
        }
        return true;
    }

    /** Whether any printing of {@code serial} is a promo or from set {@code oldest} onward. */
    private static boolean inWindow(String serial, int oldest) {
        for (String printing : serial.split("/")) {
            String p = printing.strip();
            if (p.startsWith("PR-")) return true;
            int set = setPrefix(p);
            if (set != 0 && set >= oldest) return true;
        }
        return false;
    }

    /**
     * How wide a format is: a deck legal in one is legal in every wider one. Title stands apart
     * and is only as wide as itself.
     */
    private int breadth() {
        return switch (this) {
            case L3 -> 0;
            case L6 -> 1;
            case STANDARD -> 2;
            case TITLE -> -1;
        };
    }

    /**
     * Whether moving from one set of game rules to another can refuse a deck the first allowed —
     * switching the banlist on, narrowing the format, or moving into or out of Title. A lobby voids
     * the decks already chosen when this holds; loosening the rules leaves them standing.
     */
    public static boolean tightens(DeckFormat fromFormat, boolean fromBanlist,
                                   DeckFormat toFormat, boolean toBanlist) {
        if (toBanlist && !fromBanlist) return true;
        if (fromFormat == toFormat) return false;
        if (fromFormat == TITLE || toFormat == TITLE) return true;
        return toFormat.breadth() < fromFormat.breadth();
    }

    /** A one-line summary for a lobby's notice: "Format: L3 · Banlist: Enabled". */
    public static String describe(DeckFormat format, boolean banlist) {
        return "Format: " + format.label() + (banlist ? "  ·  Banlist: Enabled" : "");
    }
}
