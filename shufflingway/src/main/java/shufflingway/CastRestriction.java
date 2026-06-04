package shufflingway;

import java.util.Set;

/**
 * Parsed cast-time restrictions for a card, derived from "You can only cast [Name] …" sentences.
 * A {@code null} value on {@link CardData#castRestriction()} means no restriction beyond the
 * normal rules (main phase, affordability, etc.).
 *
 * <p>Covered restriction forms:
 * <ul>
 *   <li>{@link #yourTurnOnly}          — "during your turn"</li>
 *   <li>{@link #mainPhaseOnly}         — "during your Main Phase"</li>
 *   <li>{@link #opponentTurnOnly}      — "during your opponent's turn" (Back Attack cards)</li>
 *   <li>{@link #requiresNoForwards}    — "if you don't control any Forwards"</li>
 *   <li>{@link #requiresAForward}      — "if you have a Forward" (must control ≥1 Forward)</li>
 *   <li>{@link #requiredBZTypes}       — one or more card types that must each be present in your Break Zone</li>
 *   <li>{@link #minBZAndRfpSummons}    — minimum combined Summon count across Break Zone and permanent RFP</li>
 *   <li>{@link #maxOpponentHandSize}   — opponent's hand must be ≤ this value; -1 means no restriction</li>
 *   <li>{@link #mustControlCondition}  — "You must control N or more Job X Forwards and/or Job Y Forwards"; {@code null} = no restriction</li>
 * </ul>
 */
public record CastRestriction(
        boolean          yourTurnOnly,
        boolean          mainPhaseOnly,
        boolean          opponentTurnOnly,
        boolean          requiresNoForwards,
        boolean          requiresAForward,
        Set<String>      requiredBZTypes,
        int              minBZAndRfpSummons,
        int              maxOpponentHandSize,
        ControlCondition mustControlCondition
) {
    public CastRestriction {
        requiredBZTypes = Set.copyOf(requiredBZTypes);
    }
}
