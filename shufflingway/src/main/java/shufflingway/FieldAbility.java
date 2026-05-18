package shufflingway;

/**
 * A parsed passive "When [card] [trigger], [effect]" ability.
 *
 * <p>Field abilities fire automatically when the named game event occurs — they have no
 * activation cost and are not placed in a hand or played; they are always active while the
 * card is on the field.
 *
 * <p>The {@code youMay} and {@code opponentMay} flags mark optional effects:
 * <ul>
 *   <li>{@code youMay} — the controller of the card may decline the effect.</li>
 *   <li>{@code opponentMay} — the opponent of the card's controller may decline the effect.</li>
 * </ul>
 *
 * <p>The {@code trigger} is normalised to one of:
 * {@code "attacks"}, {@code "blocks"}, {@code "attacks or blocks"},
 * {@code "party attacks"}, {@code "enters the field"},
 * {@code "put into break zone"}, {@code "cast summon"}, or {@code "damage zone"}.
 *
 * <p>The {@code triggerCard} field holds:
 * <ul>
 *   <li>For ETF / attack / block triggers — the name of the card on the field that owns the trigger.</li>
 *   <li>For break-zone triggers — the subject description that must match the card being broken
 *       (e.g. {@code "a Forward you control"}, {@code "Geomancer"},
 *       {@code "a Character opponent controls"}).</li>
 *   <li>For cast-summon / damage-zone triggers — empty string (trigger is not card-specific).</li>
 * </ul>
 *
 * <p>Firing restrictions:
 * <ul>
 *   <li>{@code oncePerTurn} — fires at most once per turn (tracked in {@code usedOncePerTurnAbilities}).</li>
 *   <li>{@code yourTurnOnly} — fires only during the ability owner's turn.</li>
 * </ul>
 */
public record FieldAbility(
        String  triggerCard,      // card name / break-zone subject / "" for global triggers
        String  trigger,          // normalised trigger type
        boolean youMay,           // true = ability owner may decline the effect
        boolean opponentMay,      // true = opponent of the ability owner may decline the effect
        String  effectText,       // raw effect text, restrictions already stripped
        boolean oncePerTurn,      // "This effect will trigger only once per turn"
        boolean yourTurnOnly,     // "This effect will trigger only during your turn"
        String  rfpConditionCard  // non-empty: trigger only if this card is in the RFP zone
) {}
