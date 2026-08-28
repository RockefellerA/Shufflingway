package shufflingway;

import java.util.List;
import java.util.Locale;

/**
 * Parsed "You can only use this ability if you control [X]" restriction on an action ability.
 *
 * <p>Two modes:
 * <ul>
 *   <li><b>Named mode</b> ({@link #isNamedMode()}): every card name in {@link #requiredCardNames}
 *       must be present on the controlling player's field.</li>
 *   <li><b>Count mode</b>: at least {@link #minCount} field cards that satisfy all of the
 *       optional {@link #element}, {@link #job}, {@link #category}, {@link #cardType},
 *       {@link #minPower} and {@link #maxCost} filters must be present.  When {@link #exactCount} is {@code true}
 *       the count must be exactly {@link #minCount}, not "or more".  {@link #orCardNames} lists
 *       card-name alternatives that each individual card may satisfy instead of the job/element
 *       filters (e.g. "Job Samurai or Card Name Samurai").</li>
 *   <li><b>Crystal mode</b>: {@link #requiresCrystal} is {@code true} — condition is met when
 *       the controlling player has at least 1 Crystal.</li>
 *   <li><b>Named-state mode</b>: {@link #stateCardName} is non-null — condition is met when the
 *       named card is on the controlling player's field in the state {@link #namedState} gives
 *       (dull, active, or attacking).</li>
 * </ul>
 */
public record ControlCondition(
        List<String> requiredCardNames, // named mode: must be present on controlling player's field
        int          minCount,          // count mode: minimum matching cards (1 = "a/an")
        boolean      exactCount,        // true = exactly minCount ("only N"), not "N or more"
        String       cardType,          // null | "Forward" | "Monster" | "Backup" | "Character"
        String       element,           // null or element name the card must have (e.g. "Fire")
        String       job,               // null or job name (e.g. "Scion of the Seventh Dawn")
        String       category,          // null or category name (e.g. "DFF")
        int          minPower,          // 0 = no power filter; > 0 = card power must be ≥ this
        List<String> orCardNames,       // per-card OR alternative: also matches if name is in this list
        boolean      anyOf,             // named mode: true = ANY required name suffices; false = ALL required
        String       excludeElement,    // null or element name the card must NOT have (e.g. "Ice")
        String       stateCardName,     // non-null: the named card must be on the field in namedState
        boolean      requiresCrystal,   // true: condition requires the player to have ≥1 Crystal
        boolean      allHave,           // true: ALL controlled cards of cardType must satisfy element/job (not "N or more")
        boolean      opponentControls,  // true: check opponent's field instead of activating player's field
        int          minCost,           // 0 = no cost filter; > 0 = card cost must be ≥ this
        List<ControlCondition> orAlternatives, // per-card OR filters; a card counts if it matches ANY
        boolean      bothFields,        // true: count across BOTH players' fields ("neither player controls…")
        int          maxCost,           // 0 = no cost ceiling; > 0 = card cost must be <= this
        NamedCardState namedState       // which state stateCardName must be in; DULL when unset
) {
    /** The state a named card has to be in for a named-state condition to hold. */
    public enum NamedCardState { DULL, ACTIVE, ATTACKING }

    public ControlCondition {
        requiredCardNames = List.copyOf(requiredCardNames);
        orCardNames       = List.copyOf(orCardNames);
        orAlternatives    = List.copyOf(orAlternatives);
        // Dull was the only named-state wording the parser read before the other two were added,
        // so it is the default the older constructors keep getting without spelling it out.
        if (namedState == null) namedState = NamedCardState.DULL;
    }

    /**
     * A condition on one named card's state — "If Trey is active, …", "If Trey is dull, …",
     * "If Queen is attacking, …".
     *
     * <p>Read against the field rather than against the printing card, like every other condition
     * here: the name is looked up on the controlling player's side, so a second copy of the card in
     * the right state satisfies it. Every printing in the corpus names its own carrier, which makes
     * that indistinguishable from asking the carrier directly until two copies are out at once.
     */
    public static ControlCondition forNamedCardState(String cardName, NamedCardState state) {
        return new ControlCondition(List.of(), 0, false, null, null, null, null, 0,
                List.of(), false, null, cardName, false, false, false, 0, List.of(), false, 0, state);
    }

    /** Compatibility constructor preserving the prior 16-arg signature; defaults {@code orAlternatives} to empty. */
    public ControlCondition(List<String> requiredCardNames, int minCount, boolean exactCount,
            String cardType, String element, String job, String category, int minPower,
            List<String> orCardNames, boolean anyOf, String excludeElement, String stateCardName,
            boolean requiresCrystal, boolean allHave, boolean opponentControls, int minCost) {
        this(requiredCardNames, minCount, exactCount, cardType, element, job, category, minPower,
                orCardNames, anyOf, excludeElement, stateCardName, requiresCrystal, allHave,
                opponentControls, minCost, List.of(), false);
    }

    /**
     * Compatibility constructor preserving the prior 19-arg canonical form; defaults
     * {@code maxCost} to 0 (no ceiling). Only Garland 17-004C prints one, so every other caller
     * keeps the shorter form.
     */
    public ControlCondition(List<String> requiredCardNames, int minCount, boolean exactCount,
            String cardType, String element, String job, String category, int minPower,
            List<String> orCardNames, boolean anyOf, String excludeElement, String stateCardName,
            boolean requiresCrystal, boolean allHave, boolean opponentControls, int minCost,
            List<ControlCondition> orAlternatives, boolean bothFields) {
        this(requiredCardNames, minCount, exactCount, cardType, element, job, category, minPower,
                orCardNames, anyOf, excludeElement, stateCardName, requiresCrystal, allHave,
                opponentControls, minCost, orAlternatives, bothFields, 0, null);
    }

    /**
     * Count-mode condition whose members may satisfy any one of {@code alternatives} — the
     * "N or more X and/or Y" wording (Hien 17-016L: "5 or more Fire Characters and/or Category XIV
     * Characters"). It is a union over a single pool, so a card matching several alternatives is
     * still only counted once.
     */
    public static ControlCondition forAnyOfFilters(int minCount, List<ControlCondition> alternatives) {
        return new ControlCondition(List.of(), minCount, false, null, null, null, null, 0,
                List.of(), false, null, null, false, false, false, 0, alternatives, false);
    }

    /**
     * "Neither player controls [type]" — exactly zero of {@code cardType} across both fields.
     *
     * <p>Distinct from a plain exact-zero condition, which only inspects one player's field:
     * {@link #opponentControls} chooses a side, and this condition has to hold on both at once.
     */
    public static ControlCondition forNeitherPlayerControls(String cardType) {
        return new ControlCondition(List.of(), 0, true, cardType, null, null, null, 0,
                List.of(), false, null, null, false, false, false, 0, List.of(), true);
    }

    /** Compatibility constructor preserving the prior 15-arg signature; defaults {@code minCost} to 0. */
    public ControlCondition(List<String> requiredCardNames, int minCount, boolean exactCount,
            String cardType, String element, String job, String category, int minPower,
            List<String> orCardNames, boolean anyOf, String excludeElement, String stateCardName,
            boolean requiresCrystal, boolean allHave, boolean opponentControls) {
        this(requiredCardNames, minCount, exactCount, cardType, element, job, category, minPower,
                orCardNames, anyOf, excludeElement, stateCardName, requiresCrystal, allHave,
                opponentControls, 0);
    }

    /** Convenience constructor without {@code opponentControls}; defaults it to {@code false}. */
    public ControlCondition(List<String> requiredCardNames, int minCount, boolean exactCount,
            String cardType, String element, String job, String category, int minPower,
            List<String> orCardNames, boolean anyOf, String excludeElement, String stateCardName,
            boolean requiresCrystal, boolean allHave) {
        this(requiredCardNames, minCount, exactCount, cardType, element, job, category, minPower,
                orCardNames, anyOf, excludeElement, stateCardName, requiresCrystal, allHave, false);
    }

    /** Convenience constructor without {@code allHave} or {@code opponentControls}; defaults both to {@code false}. */
    public ControlCondition(List<String> requiredCardNames, int minCount, boolean exactCount,
            String cardType, String element, String job, String category, int minPower,
            List<String> orCardNames, boolean anyOf, String excludeElement, String stateCardName,
            boolean requiresCrystal) {
        this(requiredCardNames, minCount, exactCount, cardType, element, job, category, minPower,
                orCardNames, anyOf, excludeElement, stateCardName, requiresCrystal, false, false);
    }

    /** Convenience constructor without {@code requiresCrystal}, {@code allHave}, or {@code opponentControls}. */
    public ControlCondition(List<String> requiredCardNames, int minCount, boolean exactCount,
            String cardType, String element, String job, String category, int minPower,
            List<String> orCardNames, boolean anyOf, String excludeElement, String stateCardName) {
        this(requiredCardNames, minCount, exactCount, cardType, element, job, category, minPower,
                orCardNames, anyOf, excludeElement, stateCardName, false, false, false);
    }

    /** Convenience constructor without {@code stateCardName}, {@code requiresCrystal}, {@code allHave}, or {@code opponentControls}. */
    public ControlCondition(List<String> requiredCardNames, int minCount, boolean exactCount,
            String cardType, String element, String job, String category, int minPower,
            List<String> orCardNames, boolean anyOf, String excludeElement) {
        this(requiredCardNames, minCount, exactCount, cardType, element, job, category, minPower,
                orCardNames, anyOf, excludeElement, null, false, false, false);
    }

    /** Convenience constructor without {@code excludeElement}, {@code stateCardName}, {@code requiresCrystal}, or {@code allHave}. */
    public ControlCondition(List<String> requiredCardNames, int minCount, boolean exactCount,
            String cardType, String element, String job, String category, int minPower,
            List<String> orCardNames, boolean anyOf) {
        this(requiredCardNames, minCount, exactCount, cardType, element, job, category, minPower,
                orCardNames, anyOf, null, null, false, false, false);
    }

    /** Convenience constructor preserving the prior 9-arg signature; defaults {@code anyOf} to {@code false} (AND semantics). */
    public ControlCondition(List<String> requiredCardNames, int minCount, boolean exactCount,
            String cardType, String element, String job, String category, int minPower,
            List<String> orCardNames) {
        this(requiredCardNames, minCount, exactCount, cardType, element, job, category, minPower,
                orCardNames, false, null, null, false, false, false);
    }

    /** Factory method for a crystal condition. */
    public static ControlCondition forCrystal() {
        return new ControlCondition(List.of(), 0, false, null, null, null, null, 0,
                List.of(), false, null, null, true, false, false);
    }

    /**
     * Factory method for an all-have condition: ALL controlled cards of {@code cardType} must
     * satisfy the given {@code element} and/or {@code jobFilter}.
     */
    public static ControlCondition forAllHave(String cardType, String element, String jobFilter) {
        return new ControlCondition(List.of(), 0, false, cardType, element, jobFilter, null, 0,
                List.of(), false, null, null, false, true, false);
    }

    /** Factory method for an opponent-controls condition. */
    public static ControlCondition forOpponentCount(int count, String cardType) {
        return new ControlCondition(List.of(), count, false, cardType, null, null, null, 0,
                List.of(), false, null, null, false, false, true);
    }

    /** Returns {@code true} when this condition checks for specific named cards rather than a count. */
    public boolean isNamedMode() { return !requiredCardNames.isEmpty(); }

    @Override
    public String toString() {
        if (requiresCrystal) return "hasCrystal";
        if (isNamedMode()) return String.join(anyOf ? " | " : " & ", requiredCardNames);
        if (allHave) {
            StringBuilder ah = new StringBuilder("allHave(").append(cardType != null ? cardType : "any");
            if (element != null) ah.append(" elem=").append(element);
            if (job     != null) ah.append(" job=").append(job);
            return ah.append(')').toString();
        }
        StringBuilder sb = new StringBuilder();
        if (opponentControls) sb.append("opp:");
        if (exactCount) sb.append('=');
        sb.append(minCount).append('+');
        if (element        != null) sb.append(' ').append(element);
        if (job            != null) sb.append(' ').append(job);
        if (category       != null) sb.append(' ').append(category);
        if (minPower        > 0   ) sb.append(" pow>=").append(minPower);
        if (minCost         > 0   ) sb.append(" cost>=").append(minCost);
        if (maxCost         > 0   ) sb.append(" cost<=").append(maxCost);
        if (cardType       != null) sb.append(' ').append(cardType);
        if (excludeElement != null) sb.append(" !").append(excludeElement);
        if (!orCardNames.isEmpty()) sb.append('/').append(String.join("|", orCardNames));
        if (stateCardName  != null) sb.append(' ').append(namedState.name().toLowerCase(Locale.ROOT))
                                      .append(':').append(stateCardName);
        return sb.toString();
    }
}
