package shufflingway;

import java.util.List;

/**
 * Parsed "You can only use this ability if you control [X]" restriction on an action ability.
 *
 * <p>Two modes:
 * <ul>
 *   <li><b>Named mode</b> ({@link #isNamedMode()}): every card name in {@link #requiredCardNames}
 *       must be present on the controlling player's field.</li>
 *   <li><b>Count mode</b>: at least {@link #minCount} field cards that satisfy all of the
 *       optional {@link #element}, {@link #job}, {@link #category}, {@link #cardType}, and
 *       {@link #minPower} filters must be present.  When {@link #exactCount} is {@code true}
 *       the count must be exactly {@link #minCount}, not "or more".  {@link #orCardNames} lists
 *       card-name alternatives that each individual card may satisfy instead of the job/element
 *       filters (e.g. "Job Samurai or Card Name Samurai").</li>
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
        String       dullCardName       // non-null: the named card must currently be DULL on the field
) {
    public ControlCondition {
        requiredCardNames = List.copyOf(requiredCardNames);
        orCardNames       = List.copyOf(orCardNames);
    }

    /** Convenience constructor without {@code dullCardName}; defaults it to {@code null}. */
    public ControlCondition(List<String> requiredCardNames, int minCount, boolean exactCount,
            String cardType, String element, String job, String category, int minPower,
            List<String> orCardNames, boolean anyOf, String excludeElement) {
        this(requiredCardNames, minCount, exactCount, cardType, element, job, category, minPower,
                orCardNames, anyOf, excludeElement, null);
    }

    /** Convenience constructor without {@code excludeElement}; defaults it to {@code null}. */
    public ControlCondition(List<String> requiredCardNames, int minCount, boolean exactCount,
            String cardType, String element, String job, String category, int minPower,
            List<String> orCardNames, boolean anyOf) {
        this(requiredCardNames, minCount, exactCount, cardType, element, job, category, minPower,
                orCardNames, anyOf, null);
    }

    /** Convenience constructor preserving the prior 9-arg signature; defaults {@code anyOf} to {@code false} (AND semantics). */
    public ControlCondition(List<String> requiredCardNames, int minCount, boolean exactCount,
            String cardType, String element, String job, String category, int minPower,
            List<String> orCardNames) {
        this(requiredCardNames, minCount, exactCount, cardType, element, job, category, minPower,
                orCardNames, false, null);
    }

    /** Returns {@code true} when this condition checks for specific named cards rather than a count. */
    public boolean isNamedMode() { return !requiredCardNames.isEmpty(); }

    @Override
    public String toString() {
        if (isNamedMode()) return String.join(anyOf ? " | " : " & ", requiredCardNames);
        StringBuilder sb = new StringBuilder();
        if (exactCount) sb.append('=');
        sb.append(minCount).append('+');
        if (element        != null) sb.append(' ').append(element);
        if (job            != null) sb.append(' ').append(job);
        if (category       != null) sb.append(' ').append(category);
        if (minPower        > 0   ) sb.append(" pow>=").append(minPower);
        if (cardType       != null) sb.append(' ').append(cardType);
        if (excludeElement != null) sb.append(" !").append(excludeElement);
        if (!orCardNames.isEmpty()) sb.append('/').append(String.join("|", orCardNames));
        if (dullCardName  != null) sb.append(" dull:").append(dullCardName);
        return sb.toString();
    }
}
