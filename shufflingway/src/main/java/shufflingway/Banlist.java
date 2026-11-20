package shufflingway;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The format banlists in {@code /data/banlist.txt}, a hand-maintained resource.
 *
 * <p>The file is split into formats by {@code **Name**} lines. Within a format, a category line
 * opens a list of {@code [serial] Name} rows that runs until the next category or format. Lines
 * that are neither are ignored. Three categories are read:
 * <ul>
 *   <li>{@code Banned} — the card may not be in a deck.
 *   <li>{@code Restricted [X]} — a deck may hold at most X copies of each card on the list.
 *   <li>{@code Conditional [Name Limit X]} — a deck holding the listed card may hold at most X
 *       cards in total whose name is one of those in the row's parentheses:
 *       {@code [23-117L] Chaos (Chaos, Feral Chaos)}. The row's own name stands in when there
 *       are no parentheses.
 * </ul>
 */
public final class Banlist {

    public static final String STANDARD = "Standard";

    private static final String BANNED      = "Banned";
    private static final String RESTRICTED  = "Restricted";
    private static final String CONDITIONAL = "Conditional";

    private static final String RESOURCE = "/data/banlist.txt";

    private static final Pattern FORMAT_LINE   = Pattern.compile("^\\*\\*(.+?)\\*\\*$");
    private static final Pattern CATEGORY_LINE = Pattern.compile("^([A-Za-z]+)(?:\\s*\\[([^\\]]*)\\])?$");
    private static final Pattern CARD_ROW      = Pattern.compile("^\\[([^\\]]+)\\](.*)$");
    private static final Pattern COUNT         = Pattern.compile("^\\d+$");
    private static final Pattern NAME_LIMIT    = Pattern.compile("(?i)^name\\s+limit\\s+(\\d+)$");
    private static final Pattern TRAILING_NAMES = Pattern.compile("\\(([^)]*)\\)\\s*$");

    private static Banlist instance;

    /** One line of a deck: a serial as stored, the card's name and how many copies. */
    public record DeckCard(String serial, String name, int copies) {}

    /** A Conditional rule: with {@code trigger} in the deck, {@code names} share a limit. */
    public record NameLimit(String trigger, List<String> names, int limit) {
        boolean covers(String name) {
            if (name == null) return false;
            for (String n : names) if (n.equalsIgnoreCase(name.strip())) return true;
            return false;
        }

        /** The names joined for display: "Chaos or Feral Chaos". */
        public String describeNames() {
            return joinWithOr(names);
        }
    }

    /**
     * One way a format's banlist lists a card.
     *
     * @param format   the format as the file prints it: "Standard", "Title"
     * @param category "Banned", "Restricted" or "Conditional"
     * @param limit    the copy limit for Restricted, the name limit for Conditional, 0 for Banned
     * @param names    the names that share a Conditional limit; empty otherwise
     */
    public record Listing(String format, String category, int limit, List<String> names) {

        public boolean banned() {
            return category.equals(BANNED);
        }

        /** As the file heads it, then the format: "Restricted [1] (Standard)". */
        public String label() {
            String bracket = switch (category) {
                case RESTRICTED  -> " [" + limit + "]";
                case CONDITIONAL -> " [Name Limit " + limit + "]";
                default          -> "";
            };
            return category + bracket + " (" + format + ")";
        }

        /** The rule as a sentence: "In Standard, a deck may hold at most 1 copy of this card." */
        public String explanation() {
            return switch (category) {
                case RESTRICTED  -> "In " + format + ", a deck may hold at most " + limit
                        + (limit == 1 ? " copy" : " copies") + " of this card.";
                case CONDITIONAL -> "In " + format + ", a deck holding this card may hold at most "
                        + limit + " cards named " + joinWithOr(names) + ".";
                default          -> "In " + format + ", a deck may not hold this card.";
            };
        }
    }

    /**
     * What in a deck breaks a format's banlist, each part keyed by deck serial as given.
     *
     * @param banned         deck serials that are banned
     * @param overRestricted deck serials whose card is over its restricted limit → that limit
     * @param overNameLimit  each broken name limit → the deck serials counted against it
     */
    public record Violations(Set<String> banned, Map<String, Integer> overRestricted,
                             Map<NameLimit, List<String>> overNameLimit) {

        public static final Violations NONE = new Violations(Set.of(), Map.of(), Map.of());

        public boolean isEmpty() {
            return banned.isEmpty() && overRestricted.isEmpty() && overNameLimit.isEmpty();
        }

        /** Whether this deck serial is part of any violation. */
        public boolean flags(String serial) {
            if (banned.contains(serial) || overRestricted.containsKey(serial)) return true;
            for (List<String> serials : overNameLimit.values())
                if (serials.contains(serial)) return true;
            return false;
        }
    }

    /** Banned serials keyed by lower-cased format name. */
    private final Map<String, Set<String>> bannedByFormat;
    /** Restricted serial → copy limit, keyed by lower-cased format name. */
    private final Map<String, Map<String, Integer>> restrictedByFormat;
    /** Conditional name limits, keyed by lower-cased format name. */
    private final Map<String, List<NameLimit>> nameLimitsByFormat;
    /** Each format's name as the file prints it, keyed by lower-cased name, in file order. */
    private final Map<String, String> formatNames;

    private Banlist(Map<String, Set<String>> bannedByFormat,
                    Map<String, Map<String, Integer>> restrictedByFormat,
                    Map<String, List<NameLimit>> nameLimitsByFormat,
                    Map<String, String> formatNames) {
        this.bannedByFormat = bannedByFormat;
        this.restrictedByFormat = restrictedByFormat;
        this.nameLimitsByFormat = nameLimitsByFormat;
        this.formatNames = formatNames;
    }

    /** The banlist bundled with the app, read once. An absent or unreadable file bans nothing. */
    public static synchronized Banlist get() {
        if (instance == null) {
            try (InputStream is = Banlist.class.getResourceAsStream(RESOURCE)) {
                instance = is == null
                        ? new Banlist(Map.of(), Map.of(), Map.of(), Map.of())
                        : parse(new InputStreamReader(is, StandardCharsets.UTF_8));
            } catch (IOException e) {
                instance = new Banlist(Map.of(), Map.of(), Map.of(), Map.of());
            }
        }
        return instance;
    }

    static Banlist parse(Reader reader) throws IOException {
        Map<String, Set<String>> banned = new HashMap<>();
        Map<String, Map<String, Integer>> restricted = new HashMap<>();
        Map<String, List<NameLimit>> nameLimits = new HashMap<>();
        Map<String, String> formatNames = new LinkedHashMap<>();
        String format = null;
        String category = null;
        String bracket = null;
        BufferedReader br = new BufferedReader(reader);
        String line;
        while ((line = br.readLine()) != null) {
            line = line.strip();
            if (line.isEmpty()) continue;
            Matcher m;
            if ((m = FORMAT_LINE.matcher(line)).matches()) {
                format = m.group(1).strip().toLowerCase(Locale.ROOT);
                formatNames.putIfAbsent(format, m.group(1).strip());
                category = null;
            } else if ((m = CARD_ROW.matcher(line)).matches()) {
                String serial = m.group(1).strip();
                if (format == null || category == null || serial.isEmpty()) continue;
                switch (category) {
                    case "banned" ->
                        banned.computeIfAbsent(format, k -> new LinkedHashSet<>()).add(serial);
                    case "restricted" -> {
                        // "Restricted" printed without a count means one copy.
                        int limit = bracket != null && COUNT.matcher(bracket).matches()
                                ? Integer.parseInt(bracket) : 1;
                        restricted.computeIfAbsent(format, k -> new LinkedHashMap<>()).put(serial, limit);
                    }
                    case "conditional" -> {
                        Matcher lm = bracket == null ? null : NAME_LIMIT.matcher(bracket);
                        List<String> names = limitedNames(m.group(2));
                        if (lm != null && lm.matches() && !names.isEmpty())
                            nameLimits.computeIfAbsent(format, k -> new ArrayList<>())
                                    .add(new NameLimit(serial, names, Integer.parseInt(lm.group(1))));
                    }
                    default -> {}
                }
            } else if ((m = CATEGORY_LINE.matcher(line)).matches()) {
                category = m.group(1).toLowerCase(Locale.ROOT);
                bracket = m.group(2) == null ? null : m.group(2).strip();
            }
        }
        return new Banlist(banned, restricted, nameLimits, formatNames);
    }

    /** The names in a Conditional row's trailing parentheses, or the row's own name without them. */
    private static List<String> limitedNames(String afterSerial) {
        String rest = afterSerial.strip();
        Matcher m = TRAILING_NAMES.matcher(rest);
        List<String> names = new ArrayList<>();
        if (m.find()) {
            for (String n : m.group(1).split(","))
                if (!n.isBlank()) names.add(n.strip());
        } else if (!rest.isEmpty()) {
            names.add(rest);
        }
        return names;
    }

    /** The serials banned in {@code format}, in file order. */
    public Set<String> banned(String format) {
        Set<String> set = bannedByFormat.get(key(format));
        return set == null ? Set.of() : Collections.unmodifiableSet(set);
    }

    /** The serials restricted in {@code format} with each one's copy limit, in file order. */
    public Map<String, Integer> restricted(String format) {
        Map<String, Integer> map = restrictedByFormat.get(key(format));
        return map == null ? Map.of() : Collections.unmodifiableMap(map);
    }

    /** The Conditional name limits in {@code format}, in file order. */
    public List<NameLimit> nameLimits(String format) {
        List<NameLimit> list = nameLimitsByFormat.get(key(format));
        return list == null ? List.of() : Collections.unmodifiableList(list);
    }

    /**
     * Whether the card is banned in {@code format}. A reprint's serial ("23-007C / 15-007C")
     * is banned when any of its printings is.
     */
    public boolean isBanned(String format, String serial) {
        return listedPrinting(banned(format), serial) != null;
    }

    public boolean isBannedInStandard(String serial) {
        return isBanned(STANDARD, serial);
    }

    /**
     * Whether the card is listed as Restricted, or as the card that sets off a Conditional name
     * limit, in {@code format}: legal, but a deck can hold only so many. Reprints count as for
     * {@link #isBanned}.
     */
    public boolean isLimited(String format, String serial) {
        if (listedPrinting(restricted(format).keySet(), serial) != null) return true;
        for (NameLimit rule : nameLimits(format))
            if (listedPrinting(Set.of(rule.trigger()), serial) != null) return true;
        return false;
    }

    public boolean isLimitedInStandard(String serial) {
        return isLimited(STANDARD, serial);
    }

    /**
     * Every listing of the card, format by format in file order, each format's in the order
     * Banned, Restricted, Conditional. Empty for a card no format lists. Reprints count as for
     * {@link #isBanned}.
     */
    public List<Listing> listings(String serial) {
        List<Listing> out = new ArrayList<>();
        for (String format : formatNames.values()) {
            if (isBanned(format, serial)) out.add(new Listing(format, BANNED, 0, List.of()));
            Map<String, Integer> limits = restricted(format);
            String card = listedPrinting(limits.keySet(), serial);
            if (card != null) out.add(new Listing(format, RESTRICTED, limits.get(card), List.of()));
            for (NameLimit rule : nameLimits(format))
                if (listedPrinting(Set.of(rule.trigger()), serial) != null)
                    out.add(new Listing(format, CONDITIONAL, rule.limit(), rule.names()));
        }
        return out;
    }

    /**
     * Everything in {@code deck} that breaks the banlist of {@code format}. Restricted copies are
     * counted per card, so a reprint's serial and the original's add up against one limit. A name
     * limit applies once the trigger card is in the deck, and counts every card bearing one of
     * its names.
     */
    public Violations check(String format, List<DeckCard> deck) {
        Set<String> banned = new LinkedHashSet<>();
        for (DeckCard c : deck)
            if (isBanned(format, c.serial())) banned.add(c.serial());

        Map<String, Integer> overRestricted = new LinkedHashMap<>();
        Map<String, Integer> limits = restricted(format);
        if (!limits.isEmpty()) {
            Map<String, Integer> copiesByCard = new HashMap<>();
            Map<String, String> cardBySerial = new LinkedHashMap<>();
            for (DeckCard c : deck) {
                String card = listedPrinting(limits.keySet(), c.serial());
                if (card == null) continue;
                cardBySerial.put(c.serial(), card);
                copiesByCard.merge(card, c.copies(), Integer::sum);
            }
            cardBySerial.forEach((serial, card) -> {
                int limit = limits.get(card);
                if (copiesByCard.get(card) > limit) overRestricted.put(serial, limit);
            });
        }

        Map<NameLimit, List<String>> overNameLimit = new LinkedHashMap<>();
        for (NameLimit rule : nameLimits(format)) {
            Set<String> trigger = Set.of(rule.trigger());
            boolean triggered = false;
            for (DeckCard c : deck)
                if (c.copies() > 0 && listedPrinting(trigger, c.serial()) != null) triggered = true;
            if (!triggered) continue;
            int total = 0;
            List<String> counted = new ArrayList<>();
            for (DeckCard c : deck) {
                if (!rule.covers(c.name())) continue;
                total += c.copies();
                counted.add(c.serial());
            }
            if (total > rule.limit()) overNameLimit.put(rule, counted);
        }

        return banned.isEmpty() && overRestricted.isEmpty() && overNameLimit.isEmpty()
                ? Violations.NONE
                : new Violations(banned, overRestricted, overNameLimit);
    }

    /**
     * Deck rows as {@code DeckDatabase.getDeckCards} returns them — copies, serial, name, … — as
     * {@link DeckCard}s.
     */
    public static List<DeckCard> fromDeckRows(List<Object[]> rows) {
        List<DeckCard> deck = new ArrayList<>(rows.size());
        for (Object[] r : rows)
            deck.add(new DeckCard((String) r[1], (String) r[2], (Integer) r[0]));
        return deck;
    }

    /** The entry of {@code listed} naming one of the printings in {@code serial}, or null. */
    private static String listedPrinting(Set<String> listed, String serial) {
        if (serial == null || listed.isEmpty()) return null;
        for (String part : serial.split("/")) {
            String p = part.strip();
            if (listed.contains(p)) return p;
        }
        return null;
    }

    /** "Chaos", "Chaos or Feral Chaos", "A, B or C". */
    private static String joinWithOr(List<String> names) {
        if (names.size() == 1) return names.get(0);
        return String.join(", ", names.subList(0, names.size() - 1))
                + " or " + names.get(names.size() - 1);
    }

    private static String key(String format) {
        return format.toLowerCase(Locale.ROOT);
    }
}
