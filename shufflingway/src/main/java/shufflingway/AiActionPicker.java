package shufflingway;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
 * </ul>
 * Anything else is taken at face value. Live options keep their printed order; dead ones are taken
 * only when the menu demands an exact count.
 */
final class AiActionPicker {

    private AiActionPicker() {}

    /**
     * What the picker needs to know about the board, seen from the AI's seat.
     *
     * @param oppActiveForwards   active Forwards the opponent controls
     * @param oppDullForwards     dull Forwards the opponent controls
     * @param oppActiveCharacters active Characters (Forwards, Backups, Monsters) the opponent controls
     * @param oppDullCharacters   dull Characters the opponent controls
     */
    record Board(boolean oppBreakZoneEmpty, int oppActiveForwards, int oppDullForwards,
                 int oppActiveCharacters, int oppDullCharacters, int oppHandSize,
                 boolean ownHandHasSummon) {}

    /** "Choose 1 dull Forward." — group {@code kind} is Forward or Character. */
    private static final Pattern NEEDS_DULL = Pattern.compile(
            "(?i)^Choose\\b[^.]*?\\bdull\\s+(?<kind>Forward|Character)s?\\b");

    /** "Choose up to 2 Characters. Dull them …" — group {@code what} is the chosen set. */
    private static final Pattern DULLS = Pattern.compile(
            "(?i)^Choose\\b(?<what>[^.]*)\\.\\s*Dull\\s+(?:them|it)\\b");

    private static final Pattern OPP_DISCARDS = Pattern.compile("(?i)^Your\\s+opponent\\s+discards\\b");

    private static final Pattern SUMMON_DISCOUNT = Pattern.compile(
            "(?i)\\bcost\\s+required\\s+to\\s+cast\\s+your\\s+next\\s+Summon\\s+is\\s+reduced\\b");

    private enum Kind { NEEDS_DULL_FORWARD, NEEDS_DULL_CHARACTER, DULLS_FORWARD, DULLS_CHARACTER,
                        OPP_DISCARDS, SUMMON_DISCOUNT, OTHER }

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
            } else if (isLive(k, board)) {
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

    private static boolean isLive(Kind k, Board board) {
        return switch (k) {
            case NEEDS_DULL_FORWARD   -> board.oppDullForwards() > 0;
            case NEEDS_DULL_CHARACTER -> board.oppDullCharacters() > 0;
            case DULLS_FORWARD        -> board.oppActiveForwards() > 0;
            case DULLS_CHARACTER      -> board.oppActiveCharacters() > 0;
            case OPP_DISCARDS         -> board.oppHandSize() > 0;
            case SUMMON_DISCOUNT      -> board.ownHandHasSummon();
            case OTHER                -> true;
        };
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
