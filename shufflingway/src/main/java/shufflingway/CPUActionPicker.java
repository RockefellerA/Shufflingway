package shufflingway;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How the AI picks from a "select N of the following actions" menu it controls — Seymour 27-028H's
 * "select up to 2 of the 4 following actions", and every menu like it.
 *
 * <p>The picks resolve in the order they are returned, so the picker decides two things: which
 * options are worth taking on the current board, and in what order. An option is judged by its
 * wording against a {@link Board} snapshot:
 * <ul>
 *   <li>"Choose … dull Forward …" needs a dull opposing target — one already on the board, or one
 *       another picked option dulls first ("Choose up to 2 Characters. Dull them …"). Dulling
 *       options are therefore always resolved ahead of the options that want dull targets.</li>
 *   <li>A dulling option needs an active opposing Character of the kind it names.</li>
 *   <li>"Your opponent discards …" needs cards in the opponent's hand.</li>
 *   <li>"… your next Summon is reduced …" needs a Summon in the AI's own hand.</li>
 *   <li>Removing from the opponent's Break Zone is preferred when it holds cards and dropped when
 *       it is empty, since then it could only hit the AI's own.</li>
 *   <li>Taking a chosen Character off the field — "Choose 1 Backup. Put it on top of its owner's
 *       deck." (22-122L Tidus), or returned, broken, removed from the game — needs an opposing
 *       Character of the kind it names. With none, the only target left is the AI's own.</li>
 *   <li>Choosing a Forward by its state or cost — "Choose 1 active Forward of cost 3 or less."
 *       (24-125H Tonberry &amp; Cactuar), "Choose 1 damaged Forward." (24-123H Vajradhara Wu) —
 *       needs an opposing Forward meeting every qualifier, whatever is then done to it.</li>
 *   <li>"Play 1 Card Name Lightning of cost 4 or less from your hand onto the field." (26-130H
 *       Lightning) needs a card in the AI's own hand that the play would take.</li>
 * </ul>
 * Anything else is taken at face value. Live options keep their printed order; dead ones are taken
 * only when the menu demands an exact count.
 */
final class CPUActionPicker {

    private CPUActionPicker() {}

    /** A Forward the opponent controls, as much of it as a printed target filter asks about. */
    record OpposingForward(int cost, boolean dull, boolean damaged) {}

    /**
     * What the picker needs to know about the board, seen from the AI's seat.
     *
     * @param oppForwards         the Forwards the opponent controls
     * @param oppActiveCharacters active Characters (Forwards, Backups, Monsters) the opponent controls
     * @param oppDullCharacters   dull Characters the opponent controls
     * @param oppBackups          Backups the opponent controls, active or dull
     * @param ownHandCanPlay      whether a "Play … from your hand" option, given its text, would
     *                            find a card in the AI's own hand
     */
    record Board(boolean oppBreakZoneEmpty, List<OpposingForward> oppForwards,
                 int oppActiveCharacters, int oppDullCharacters, int oppBackups, int oppHandSize,
                 boolean ownHandHasSummon, Predicate<String> ownHandCanPlay) {
        Board { oppForwards = List.copyOf(oppForwards); }

        /** A board on which every "Play … from your hand" option is taken at face value. */
        Board(boolean oppBreakZoneEmpty, List<OpposingForward> oppForwards, int oppActiveCharacters,
              int oppDullCharacters, int oppBackups, int oppHandSize, boolean ownHandHasSummon) {
            this(oppBreakZoneEmpty, oppForwards, oppActiveCharacters, oppDullCharacters, oppBackups,
                    oppHandSize, ownHandHasSummon, option -> true);
        }

        int oppActiveForwards() { return (int) oppForwards.stream().filter(f -> !f.dull()).count(); }

        int oppDullForwards() { return (int) oppForwards.stream().filter(OpposingForward::dull).count(); }
    }

    /** "Choose 1 dull Forward." — group {@code kind} is Forward or Character. */
    private static final Pattern NEEDS_DULL = Pattern.compile(
            "(?i)^Choose\\b[^.]*?\\bdull\\s+(?<kind>Forward|Character)s?\\b");

    /** "Choose up to 2 Characters. Dull them …" — group {@code what} is the chosen set. */
    private static final Pattern DULLS = Pattern.compile(
            "(?i)^Choose\\b(?<what>[^.]*)\\.\\s*Dull\\s+(?:them|it)\\b");

    /**
     * "Choose 1 Backup. Put it on top of its owner's deck." — a chosen Character taken off the field.
     * Group {@code kind} is Forward, Backup or Character.
     */
    private static final Pattern REMOVES = Pattern.compile(
            "(?i)^Choose\\s+(?:up\\s+to\\s+)?\\d+\\s+(?<kind>Forward|Backup|Character)s?"
            + "(?:\\s+(?:your\\s+)?opponent\\s+controls)?\\.\\s*"
            + "(?:Put\\s+(?:it|them)\\s+(?:on\\s+top|at\\s+the\\s+bottom)\\s+of\\s+(?:its|their)\\s+owner"
            + "|Return\\s+(?:it|them)\\s+to\\s+(?:its|their)\\s+owner"
            + "|Break\\s+(?:it|them)\\b"
            + "|Remove\\s+(?:it|them)\\s+from\\s+the\\s+game)");

    /**
     * "Choose 1 active Forward of cost 3 or less. Break it." — a chosen Forward named by the
     * qualifiers a target has to meet. Groups: {@code state} (active, dull or damaged), {@code cost}
     * and {@code cmp} (less or more; absent for exactly that cost).
     */
    private static final Pattern CHOOSES_FORWARD = Pattern.compile(
            "(?i)^Choose\\s+(?:up\\s+to\\s+)?\\d+\\s+(?:(?<state>active|dull|damaged)\\s+)?Forwards?"
            + "(?:\\s+of\\s+cost\\s+(?<cost>\\d+)(?:\\s+or\\s+(?<cmp>less|more))?)?"
            + "(?:\\s+(?:your\\s+)?opponent\\s+controls)?\\.");

    private static final Pattern OPP_DISCARDS = Pattern.compile("(?i)^Your\\s+opponent\\s+discards\\b");

    private static final Pattern SUMMON_DISCOUNT = Pattern.compile(
            "(?i)\\bcost\\s+required\\s+to\\s+cast\\s+your\\s+next\\s+Summon\\s+is\\s+reduced\\b");

    /** "Play 1 Card Name Lightning of cost 4 or less from your hand onto the field." */
    private static final Pattern PLAYS_FROM_HAND = Pattern.compile("(?i)^Play\\b[^.]*\\bfrom\\s+your\\s+hand\\b");

    private enum Kind { NEEDS_DULL_FORWARD, NEEDS_DULL_CHARACTER, DULLS_FORWARD, DULLS_CHARACTER,
                        REMOVES_FORWARD, REMOVES_BACKUP, REMOVES_CHARACTER, CHOOSES_FORWARD,
                        OPP_DISCARDS, SUMMON_DISCOUNT, PLAYS_FROM_HAND, OTHER }

    /**
     * The options the AI takes, in the order they should resolve.
     *
     * @param upTo when false the menu demands exactly {@code selectCount}, so dead options are
     *             taken to make up the count
     */
    static List<String> pick(List<String> actions, int selectCount, boolean upTo, Board board) {
        List<String> ordered = new ArrayList<>(actions.size());
        for (String a : actions) if (removesFromOpponentBreakZone(a) && !board.oppBreakZoneEmpty()) ordered.add(a);
        for (String a : actions) if (!removesFromOpponentBreakZone(a)) ordered.add(a);

        // The first dulling option that can reach an active opposing Forward, which a "dull
        // Forward" option can then be pointed at; likewise any active Character, for "dull Character".
        String forwardDuller = null, characterDuller = null;
        for (String a : ordered) {
            Kind k = kindOf(a);
            if (k != Kind.DULLS_FORWARD && k != Kind.DULLS_CHARACTER) continue;
            if (forwardDuller == null && board.oppActiveForwards() > 0) forwardDuller = a;
            if (characterDuller == null && (k == Kind.DULLS_FORWARD ? board.oppActiveForwards()
                    : board.oppActiveCharacters()) > 0) characterDuller = a;
        }

        List<String> chosen = new ArrayList<>();
        for (String a : ordered) {
            if (chosen.size() >= selectCount) break;
            if (chosen.contains(a)) continue;
            Kind k = kindOf(a);
            boolean needsDull = k == Kind.NEEDS_DULL_FORWARD || k == Kind.NEEDS_DULL_CHARACTER;
            boolean hasDullTarget = k == Kind.NEEDS_DULL_FORWARD ? board.oppDullForwards() > 0
                    : board.oppDullCharacters() > 0;
            if (needsDull && !hasDullTarget) {
                // Live only behind an option that dulls its target first, and only when both fit.
                String enabler = k == Kind.NEEDS_DULL_FORWARD ? forwardDuller : characterDuller;
                if (enabler == null) continue;
                if (!chosen.contains(enabler)) {
                    if (chosen.size() + 2 > selectCount) continue;
                    chosen.add(enabler);
                }
                chosen.add(a);
            } else if (isLive(a, k, board)) {
                chosen.add(a);
            }
        }
        if (!upTo)
            for (String a : ordered) {
                if (chosen.size() >= selectCount) break;
                if (!chosen.contains(a)) chosen.add(a);
            }
        return dullersFirst(chosen);
    }

    private static boolean isLive(String action, Kind k, Board board) {
        return switch (k) {
            case CHOOSES_FORWARD      -> {
                Matcher m = CHOOSES_FORWARD.matcher(action.trim());
                if (!m.find()) yield false;
                String state = m.group("state"), cost = m.group("cost"), cmp = m.group("cmp");
                yield board.oppForwards().stream().anyMatch(f -> meets(f, state, cost, cmp));
            }
            case NEEDS_DULL_FORWARD   -> board.oppDullForwards() > 0;
            case NEEDS_DULL_CHARACTER -> board.oppDullCharacters() > 0;
            case DULLS_FORWARD        -> board.oppActiveForwards() > 0;
            case DULLS_CHARACTER      -> board.oppActiveCharacters() > 0;
            case REMOVES_FORWARD      -> board.oppActiveForwards() + board.oppDullForwards() > 0;
            case REMOVES_BACKUP       -> board.oppBackups() > 0;
            case REMOVES_CHARACTER    -> board.oppActiveCharacters() + board.oppDullCharacters() > 0;
            case OPP_DISCARDS         -> board.oppHandSize() > 0;
            case SUMMON_DISCOUNT      -> board.ownHandHasSummon();
            case PLAYS_FROM_HAND      -> board.ownHandCanPlay().test(action.trim());
            case OTHER                -> true;
        };
    }

    /** Whether {@code f} meets a {@link #CHOOSES_FORWARD} filter's qualifiers; a null one asks nothing. */
    private static boolean meets(OpposingForward f, String state, String cost, String cmp) {
        if (state != null) {
            String s = state.toLowerCase(Locale.ROOT);
            if (s.equals("active") && f.dull())   return false;
            if (s.equals("dull") && !f.dull())    return false;
            if (s.equals("damaged") && !f.damaged()) return false;
        }
        if (cost != null) {
            int c = Integer.parseInt(cost);
            if (cmp == null ? f.cost() != c
                    : cmp.equalsIgnoreCase("less") ? f.cost() > c : f.cost() < c) return false;
        }
        return true;
    }

    /** {@code chosen} with every option that wants a dull target moved behind the dulling options. */
    private static List<String> dullersFirst(List<String> chosen) {
        int lastDuller = -1;
        for (int i = 0; i < chosen.size(); i++) {
            Kind k = kindOf(chosen.get(i));
            if (k == Kind.DULLS_FORWARD || k == Kind.DULLS_CHARACTER) lastDuller = i;
        }
        if (lastDuller < 0) return chosen;
        List<String> before = new ArrayList<>(), moved = new ArrayList<>(), after = new ArrayList<>();
        for (int i = 0; i < chosen.size(); i++) {
            String a = chosen.get(i);
            Kind k = kindOf(a);
            boolean needsDull = k == Kind.NEEDS_DULL_FORWARD || k == Kind.NEEDS_DULL_CHARACTER;
            if (needsDull && i < lastDuller) moved.add(a);
            else if (i <= lastDuller)        before.add(a);
            else                             after.add(a);
        }
        List<String> out = new ArrayList<>(before);
        out.addAll(moved);
        out.addAll(after);
        return out;
    }

    private static Kind kindOf(String action) {
        String a = action.trim();
        // About the AI's own hand rather than a target, so ahead of the own-side shortcut below: a
        // play whose cost is counted ("… equal to the number of Job X you control") is still a play.
        if (PLAYS_FROM_HAND.matcher(a).find()) return Kind.PLAYS_FROM_HAND;
        // Options aimed at the AI's own side are not about opposing targets; take them at face value.
        if (a.toLowerCase(Locale.ROOT).contains("you control")) return Kind.OTHER;
        Matcher m = NEEDS_DULL.matcher(a);
        if (m.find())
            return m.group("kind").equalsIgnoreCase("Forward") ? Kind.NEEDS_DULL_FORWARD : Kind.NEEDS_DULL_CHARACTER;
        m = DULLS.matcher(a);
        if (m.find()) {
            String what = m.group("what").toLowerCase(Locale.ROOT);
            if (what.contains("character")) return Kind.DULLS_CHARACTER;
            if (what.contains("forward"))   return Kind.DULLS_FORWARD;
            return Kind.OTHER;
        }
        m = REMOVES.matcher(a);
        if (m.find())
            return switch (m.group("kind").toLowerCase(Locale.ROOT)) {
                case "forward" -> Kind.REMOVES_FORWARD;
                case "backup"  -> Kind.REMOVES_BACKUP;
                default        -> Kind.REMOVES_CHARACTER;
            };
        if (CHOOSES_FORWARD.matcher(a).find()) return Kind.CHOOSES_FORWARD;
        if (OPP_DISCARDS.matcher(a).find())    return Kind.OPP_DISCARDS;
        if (SUMMON_DISCOUNT.matcher(a).find()) return Kind.SUMMON_DISCOUNT;
        return Kind.OTHER;
    }

    /** Whether {@code action} removes cards from a Break Zone the opponent's cards can be in. */
    static boolean removesFromOpponentBreakZone(String action) {
        String a = action.toLowerCase(Locale.ROOT);
        if (!a.contains("break zone") || !a.contains("remove")) return false;
        return a.contains("either player") || a.contains("any player") || a.contains("opponent");
    }
}
