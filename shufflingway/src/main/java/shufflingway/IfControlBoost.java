package shufflingway;

import java.util.List;
import java.util.Set;

/**
 * A conditional passive ability of the form "If you control [X], [targetCard] gains [Z]."
 *
 * <p>Active while the owning card is on the field AND every {@link #conditions} is satisfied.
 * When any condition ceases to be met (e.g., the required card leaves the field) the bonus
 * is immediately removed from the target.
 *
 * <p>The {@link #exceptCardName} handles the "other than" exclusion pattern:
 * "If you control a Job X <b>other than Queen</b>, Queen gains …" — the card named "Queen" is
 * excluded from the pool when evaluating whether a Job-X card is present.
 */
public record IfControlBoost(
        List<ControlCondition> conditions,    // all must be satisfied simultaneously (AND)
        String    exceptCardName,             // excluded card name ("other than X"); empty string if none
        String    targetCardName,             // card on the field that receives the bonus
        int       powerBonus,                 // +N power added to the target (0 if no power effect)
        Set<CardData.Trait> grantedTraits,    // traits granted to the target while active
        String    specialText,                // quoted special ability text (display only; empty if none)
        boolean   cannotBeChosenBySummons,    // target cannot be chosen by any Summon while active
        boolean   cannotBeChosenByAbilities   // target cannot be chosen by any ability while active
) {
    public IfControlBoost {
        conditions    = List.copyOf(conditions);
        grantedTraits = Set.copyOf(grantedTraits);
        if (exceptCardName == null) exceptCardName = "";
        if (specialText    == null) specialText    = "";
    }
}
