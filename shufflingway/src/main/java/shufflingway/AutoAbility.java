package shufflingway;

/**
 * A parsed passive "When [card] [trigger], [effect]" ability.
 *
 * <p>Auto abilities fire automatically when the named game event occurs — they have no
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
 * {@code "is blocked"}, {@code "blocks or is blocked"},
 * {@code "party attacks"}, {@code "enters the field"},
 * {@code "put into break zone"}, {@code "cast summon"}, {@code "damage zone"},
 * or {@code "primed into"}.
 *
 * <p>For {@code trigger == "party attacks"}, optional filters narrow which parties fire the trigger:
 * <ul>
 *   <li>{@code partyMinCount} — party must contain at least this many qualifying members (0 = any party).</li>
 *   <li>{@code partyCategory} — qualifying members must have this category (null = any).</li>
 *   <li>{@code partyJob} — qualifying members must have this job (null = any).</li>
 *   <li>{@code partyCardName} — party must include a member with this exact name (null = any).</li>
 * </ul>
 *
 * <p>The {@code triggerCard} field holds:
 * <ul>
 *   <li>For ETF / attack / block triggers — the name of the card on the field that owns the trigger.</li>
 *   <li>For break-zone triggers — the subject description that must match the card being broken
 *       (e.g. {@code "a Forward you control"}, {@code "Geomancer"},
 *       {@code "a Character opponent controls"}).</li>
 *   <li>For cast-summon / damage-zone triggers — empty string (trigger is not card-specific).</li>
 *   <li>For primed-into triggers — the name of the card that initiates the priming.</li>
 * </ul>
 *
 * <p>Firing restrictions:
 * <ul>
 *   <li>{@code castOnly} — fires only when the card was cast from hand.</li>
 *   <li>{@code warpOnly} — fires only when the card entered the field via Warp resolution.</li>
 *   <li>{@code oncePerTurn} — fires at most once per turn (tracked in {@code usedOncePerTurnAbilities}).</li>
 *   <li>{@code yourTurnOnly} — fires only during the ability owner's turn.</li>
 * </ul>
 */
public record AutoAbility(
        String  triggerCard,      // card name / break-zone subject / "" for global triggers
        String  trigger,          // normalised trigger type
        boolean youMay,           // true = ability owner may decline the effect
        boolean opponentMay,      // true = opponent of the ability owner may decline the effect
        String  effectText,       // raw effect text, restrictions already stripped
        boolean oncePerTurn,           // "This effect will trigger only once per turn"
        boolean yourTurnOnly,          // "This effect will trigger only during your turn"
        String  rfpConditionCard,      // non-empty: trigger only if this card is in the RFP zone
        int     castPaymentMinElements,// > 0: trigger only if the card was cast with ≥ N distinct element types
        boolean castOnly,              // true = "enters the field due to your cast" — only fires when cast from hand
        boolean warpOnly,              // true = "enters the field due to Warp" — only fires when entering via Warp resolution
        int     damageThreshold,       // > 0: only fires when controlling player has ≥ this many damage counters
        // Party-attack filter fields (all ignored when trigger != "party attacks")
        int     partyMinCount,    // ≥ 1: party must have ≥ N qualifying members; 0 = no requirement
        String  partyCategory,    // non-null: qualifying members must have this category
        String  partyJob,         // non-null: qualifying members must have this job
        String  partyCardName     // non-null: party must include a member with this exact name
) {}
