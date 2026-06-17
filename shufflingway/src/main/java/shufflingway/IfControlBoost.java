package shufflingway;

import java.util.List;
import java.util.Set;

/**
 * A conditional passive ability of the form "If you control [X], [target] gains [Z]."
 *
 * <p>Active while the owning card is on the field AND every {@link #conditions} is satisfied.
 * When any condition ceases to be met (e.g., the required card leaves the field) the bonus
 * is immediately removed from the target.
 *
 * <p>The {@link #exceptCardName} handles the "other than" exclusion pattern:
 * "If you control a Job X <b>other than Queen</b>, Queen gains …" — the card named "Queen" is
 * excluded from the pool when evaluating whether a Job-X card is present.
 *
 * <p>The target may be either a specific card by name ({@link #targetCardName}) <i>or</i> a
 * filter ({@link #targetFilter}).  When the filter is non-null it takes precedence: every
 * controlled card that matches the filter receives the bonus.  When the filter is null the
 * legacy {@link #targetCardName} match is used.
 */
public record IfControlBoost(
        List<ControlCondition> conditions,    // all must be satisfied simultaneously (AND)
        String    exceptCardName,             // excluded card name ("other than X"); empty string if none
        String    targetCardName,             // card on the field that receives the bonus (legacy name target)
        FieldPowerGrant targetFilter,         // filter-style target (category/job/type); null = name target
        int       powerBonus,                 // +N power added to the target (0 if no power effect)
        Set<CardData.Trait> grantedTraits,    // traits granted to the target while active
        String    specialText,                // quoted special ability text (display only; empty if none)
        boolean   cannotBeChosenBySummons,    // target cannot be chosen by any Summon while active
        boolean   cannotBeChosenByAbilities,  // target cannot be chosen by any ability while active
        boolean   cannotBeBlocked,            // target cannot be blocked (unconditionally) while active
        int[]     cannotBeBlockedByCost       // null = no restriction; {costVal, 1} = "or more", {costVal, 0} = "or less"
) {
    public IfControlBoost {
        conditions    = List.copyOf(conditions);
        grantedTraits = Set.copyOf(grantedTraits);
        if (exceptCardName == null) exceptCardName = "";
        if (specialText    == null) specialText    = "";
    }

    /** Compatibility constructor preserving the prior 10-arg signature; defaults cannotBeBlockedByCost to null. */
    public IfControlBoost(List<ControlCondition> conditions, String exceptCardName,
            String targetCardName, FieldPowerGrant targetFilter, int powerBonus,
            Set<CardData.Trait> grantedTraits, String specialText,
            boolean cannotBeChosenBySummons, boolean cannotBeChosenByAbilities, boolean cannotBeBlocked) {
        this(conditions, exceptCardName, targetCardName, targetFilter, powerBonus, grantedTraits,
                specialText, cannotBeChosenBySummons, cannotBeChosenByAbilities, cannotBeBlocked, null);
    }

    /** Compatibility constructor preserving the prior 9-arg signature; defaults cannotBeBlocked/Cost to false/null. */
    public IfControlBoost(List<ControlCondition> conditions, String exceptCardName,
            String targetCardName, FieldPowerGrant targetFilter, int powerBonus,
            Set<CardData.Trait> grantedTraits, String specialText,
            boolean cannotBeChosenBySummons, boolean cannotBeChosenByAbilities) {
        this(conditions, exceptCardName, targetCardName, targetFilter, powerBonus, grantedTraits,
                specialText, cannotBeChosenBySummons, cannotBeChosenByAbilities, false, null);
    }

    /** Compatibility constructor preserving the prior 8-arg signature; uses name-target mode. */
    public IfControlBoost(List<ControlCondition> conditions, String exceptCardName,
            String targetCardName, int powerBonus, Set<CardData.Trait> grantedTraits,
            String specialText, boolean cannotBeChosenBySummons, boolean cannotBeChosenByAbilities) {
        this(conditions, exceptCardName, targetCardName, null, powerBonus, grantedTraits,
                specialText, cannotBeChosenBySummons, cannotBeChosenByAbilities, false, null);
    }

    /** Returns {@code true} when {@code card} is a valid target of this boost. */
    public boolean appliesToCard(CardData card) {
        if (targetFilter != null) return targetFilter.appliesToCard(card);
        return targetCardName != null && targetCardName.equalsIgnoreCase(card.name());
    }
}
