package shufflingway;

import java.util.List;

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
 * {@code "other forward attacks"}, {@code "party attacks"}, {@code "enters the field"},
 * {@code "put into break zone"}, {@code "damaged card put into break zone"},
 * {@code "enters the field or put into break zone"},
 * {@code "cast summon"}, {@code "damage zone"},
 * {@code "either player receives damage"}, {@code "you receive damage"},
 * {@code "primed into"}, or {@code "is priming"}.
 *
 * <p>The two priming triggers watch opposite ends of the same act. {@code "primed into"} names the
 * Eikon that arrives and lives on that Eikon, with {@link #triggerCard()} holding the primer's
 * name. {@code "is priming"} fires on the payment itself, before the fetched card is known, and
 * {@link #triggerCard()} holds a subject phrase over the priming card ({@code "Dion or a Character
 * you control"}) resolved the same way the chosen-by-opponent subjects are.
 *
 * <p>For {@code trigger == "other forward attacks"}, {@link #triggerCard()} holds the full
 * subject phrase (e.g. {@code "a Forward other than Tifa you control"}). The ability fires
 * on any same-side Forward attack that satisfies that phrase; the effect is resolved with the
 * attacking Forward as the source so that "it" refers to the attacker.
 *
 * <p>For {@code trigger == "party attacks"}, optional filters narrow which parties fire the trigger:
 * <ul>
 *   <li>{@code partyMinCount} — party must contain at least this many qualifying members (0 = any party).</li>
 *   <li>{@code partyCategory} — qualifying members must have this category (null = any).</li>
 *   <li>{@code partyJob} — qualifying members must have this job (null = any).</li>
 *   <li>{@code partyCardNames} — every name listed must be somewhere in the party (empty = any).
 *       A conjunction, not alternatives: 12-044R Shikaree X wants Shikaree Y <em>and</em>
 *       Shikaree Z, and the printings that name one partner want that one and the carrier.</li>
 * </ul>
 *
 * <p>The {@code triggerCard} field holds:
 * <ul>
 *   <li>For ETF / attack / block triggers — the name of the card on the field that owns the trigger.</li>
 *   <li>For break-zone triggers — the subject description that must match the card being broken
 *       (e.g. {@code "a Forward you control"}, {@code "Geomancer"},
 *       {@code "a Character opponent controls"}).</li>
 *   <li>For {@code "damaged card put into break zone"} — the same, with a trailing
 *       {@code "damaged by [name]"} naming the card whose damage the trigger watches
 *       ({@code "a Forward damaged by Galuf"}). Split by
 *       {@code CardData.DAMAGED_BY_BZ_SUBJECT}; the half ahead of it is an ordinary
 *       break-zone subject.</li>
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
 *   <li>{@code opponentTurnOnly} — fires only while the turn belongs to the ability owner's
 *       opponent, which is the mirror of {@code yourTurnOnly} and the only restriction the
 *       corpus states ahead of the trigger rather than after the effect.</li>
 *   <li>{@code rfpConditionCard} — fires only if the named card is in the RFP zone.</li>
 *   <li>{@code bzConditionCard} — fires only if the named card is in the owner's Break Zone.</li>
 *   <li>{@code bzConditionJob} — additionally requires the Break Zone card to have this Job
 *       ("if you have a Card Name X with Job Y in your Break Zone").</li>
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
        boolean opponentTurnOnly,      // "During your opponent's turn, when …" — fires only while the opponent has the turn
        String  rfpConditionCard,      // non-empty: trigger only if this card is in the RFP zone
        String  bzConditionCard,       // non-empty: trigger only if this card is in the owner's Break Zone
        String  bzConditionJob,        // non-empty: the Break Zone card must also have this Job
        int     castPaymentMinElements,// > 0: trigger only if the card was cast with ≥ N distinct element types
        boolean castOnly,              // true = "enters the field due to your cast" — only fires when cast from hand
        boolean warpOnly,              // true = "enters the field due to Warp" — only fires when entering via Warp resolution
        int     damageThreshold,       // > 0: only fires when controlling player has ≥ this many damage counters
        // Party-attack filter fields (all ignored when trigger != "party attacks")
        int     partyMinCount,    // ≥ 1: party must have ≥ N qualifying members; 0 = no requirement
        String  partyCategory,    // non-null: qualifying members must have this category
        String  partyJob,         // non-null: qualifying members must have this job
        List<String> partyCardNames // every name listed must be in the party; empty = no name requirement
) {
    /** Defensive copy, so a caller cannot mutate a filter the dispatch reads on every party attack. */
    public AutoAbility {
        partyCardNames = partyCardNames == null ? List.of() : List.copyOf(partyCardNames);
    }

    /**
     * A copy of this ability carrying {@code newEffectText} in place of {@link #effectText()}.
     * Used to restore quoted granted-ability text that was masked out while the card's text was
     * being scanned for triggers of its own.
     */
    public AutoAbility withEffectText(String newEffectText) {
        return new AutoAbility(triggerCard, trigger, youMay, opponentMay, newEffectText,
                oncePerTurn, yourTurnOnly, opponentTurnOnly, rfpConditionCard, bzConditionCard, bzConditionJob,
                castPaymentMinElements, castOnly, warpOnly, damageThreshold,
                partyMinCount, partyCategory, partyJob, partyCardNames);
    }

    /**
     * A copy of this ability with {@link #oncePerTurn()} set. Used by the trigger forms that state
     * the limit in the trigger clause rather than as the trailing "This effect will trigger only
     * once per turn." sentence the restriction stripper reads — Colkhab 18-041C's "…for the first
     * time in that turn".
     */
    public AutoAbility withOncePerTurn() {
        return oncePerTurn ? this : new AutoAbility(triggerCard, trigger, youMay, opponentMay, effectText,
                true, yourTurnOnly, opponentTurnOnly, rfpConditionCard, bzConditionCard, bzConditionJob,
                castPaymentMinElements, castOnly, warpOnly, damageThreshold,
                partyMinCount, partyCategory, partyJob, partyCardNames);
    }

    /**
     * A copy of this ability with {@link #opponentTurnOnly()} set — the restriction Lunafreya
     * 8-132L states as a "During your opponent's turn," prefix ahead of its trigger. Applied
     * after the fact for the same reason {@link #withOncePerTurn()} is: the trigger sentence is
     * parsed by the ordinary machinery once the prefix has been lifted off it.
     */
    public AutoAbility withOpponentTurnOnly() {
        return opponentTurnOnly ? this
                : new AutoAbility(triggerCard, trigger, youMay, opponentMay, effectText,
                        oncePerTurn, yourTurnOnly, true, rfpConditionCard, bzConditionCard, bzConditionJob,
                        castPaymentMinElements, castOnly, warpOnly, damageThreshold,
                        partyMinCount, partyCategory, partyJob, partyCardNames);
    }
}
