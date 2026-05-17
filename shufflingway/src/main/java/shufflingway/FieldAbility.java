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
 * {@code "party attacks"}, or {@code "enters the field"}.
 */
public record FieldAbility(
        String  triggerCard,   // name of the card whose event fires this ability
        String  trigger,       // "attacks" | "blocks" | "attacks or blocks" | "enters the field"
        boolean youMay,        // true = ability owner may decline the effect
        boolean opponentMay,   // true = opponent of the ability owner may decline the effect
        String  effectText     // raw effect text, "you may"/"your opponent may" already stripped
) {}
