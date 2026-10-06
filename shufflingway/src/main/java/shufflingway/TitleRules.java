package shufflingway;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Title Format deck construction, per the official rules:
 *
 * <ol>
 *   <li>The deck names a category, and is built of cards of that category plus the two
 *       exceptions below. A card of two categories ("MOBIUS · VII") belongs to both.
 *       Special, Anniversary, FFRK and MQ cannot be a deck's category.
 *   <li>[Job (Standard Unit)] Backups may go in any deck, up to three of each card.
 *   <li>Special category cards may go in any deck, up to three of each card.
 *   <li>At least 30 cards of the deck's category.
 *   <li>Title has its own banlist — the {@link Banlist}'s Title section — checked by the caller.
 *   <li>No Limit Break cards.
 * </ol>
 *
 * The deck's category is not chosen up front here; it is found: the category, among those the
 * deck could be built on, with the most cards in it.
 */
public final class TitleRules {

    /** Categories a Title deck cannot be built on. */
    public static final Set<String> EXCLUDED_CATEGORIES = Set.of("Special", "Anniversary", "FFRK", "MQ");
    /** The category whose cards go in any deck. */
    public static final String SPECIAL = "Special";
    /** The Job whose Backups go in any deck. */
    public static final String STANDARD_UNIT = "Standard Unit";
    public static final int MIN_CATEGORY_CARDS = 30;
    /** Copies allowed of each card that is in the deck by an exception rather than its category. */
    public static final int MAX_EXCEPTION_COPIES = 3;

    private TitleRules() {}

    /** One line of a deck. */
    public record Card(String serial, String type, String job, String category1, String category2,
                       int copies, boolean limitBreak) {

        boolean inCategory(String category) {
            return category.equalsIgnoreCase(blankToNull(category1)) || category.equalsIgnoreCase(blankToNull(category2));
        }

        /** In the deck by rule 2 or 3 whatever its category. */
        boolean allowedAnywhere() {
            return inCategory(SPECIAL) || isStandardUnitBackup();
        }

        boolean isStandardUnitBackup() {
            if (type == null || !type.equalsIgnoreCase("Backup") || job == null) return false;
            for (String j : job.split("/"))
                if (j.strip().equalsIgnoreCase(STANDARD_UNIT)) return true;
            return false;
        }
    }

    /**
     * The outcome of a check: the category the deck is legal under, or why it is not legal.
     * Exactly one of the two is non-null.
     */
    public record Verdict(String category, String reason) {
        public boolean legal() { return category != null; }

        static Verdict legal(String category) { return new Verdict(category, null); }
        static Verdict refused(String reason) { return new Verdict(null, reason); }
    }

    /**
     * Whether {@code deck} can be played in Title, and under which category. The 50-card main
     * deck and the banlist are the caller's to check.
     */
    public static Verdict check(List<Card> deck) {
        for (Card c : deck)
            if (c.limitBreak() && c.copies() > 0) return Verdict.refused("Limit Break cards cannot be used in Title");

        // Every category the deck could be built on, with how many of its cards belong to it.
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Card c : deck) {
            // A card printed with the same category twice counts once toward it.
            Set<String> cats = new LinkedHashSet<>();
            for (String cat : new String[]{ blankToNull(c.category1()), blankToNull(c.category2()) })
                if (cat != null && !isExcluded(cat)) cats.add(cat);
            for (String cat : cats) counts.merge(cat, c.copies(), Integer::sum);
        }
        if (counts.isEmpty()) return Verdict.refused("No category this deck can be built on");

        // Most cards first: that is the category a player building this deck meant.
        List<Map.Entry<String, Integer>> ranked = counts.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue()).toList();
        String firstReason = null;
        for (Map.Entry<String, Integer> e : ranked) {
            String reason = refusalUnder(deck, e.getKey(), e.getValue());
            if (reason == null) return Verdict.legal(e.getKey());
            if (firstReason == null) firstReason = reason;
        }
        return Verdict.refused(firstReason);
    }

    /** Why {@code deck} is not legal with {@code category} as its category, or null if it is. */
    private static String refusalUnder(List<Card> deck, String category, int inCategory) {
        if (inCategory < MIN_CATEGORY_CARDS)
            return "Needs " + MIN_CATEGORY_CARDS + " cards of one category (" + category + " has " + inCategory + ")";
        for (Card c : deck) {
            if (c.copies() <= 0 || c.inCategory(category)) continue;
            if (!c.allowedAnywhere())
                return c.serial() + " is not in category " + category;
            if (c.copies() > MAX_EXCEPTION_COPIES)
                return c.serial() + ": at most " + MAX_EXCEPTION_COPIES + " copies outside the deck's category";
        }
        return null;
    }

    /**
     * Deck rows as {@code DeckDatabase.getDeckCards} returns them — count, serial, name, type,
     * element, cost, power, job, category 1, category 2 — as {@link Card}s, the serials in
     * {@code lbSerials} marked as Limit Break cards.
     */
    public static List<Card> fromDeckRows(List<Object[]> rows, Set<String> lbSerials) {
        List<Card> out = new java.util.ArrayList<>(rows.size());
        for (Object[] r : rows) {
            String serial = (String) r[1];
            out.add(new Card(serial, (String) r[3], (String) r[7], (String) r[8], (String) r[9],
                    (Integer) r[0], lbSerials.contains(serial)));
        }
        return out;
    }

    public static boolean isExcluded(String category) {
        for (String x : EXCLUDED_CATEGORIES) if (x.equalsIgnoreCase(category)) return true;
        return false;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
