package shufflingway;

/**
 * A single "discard from hand" payment cost on an Action Ability.
 *
 * <p>Examples and their parsed form:
 * <ul>
 *   <li>{@code discard 1 card}
 *       → {@code DiscardCost(1, null, null, null, null, null, false, false)}</li>
 *   <li>{@code discard 1 Water card}
 *       → {@code DiscardCost(1, null, "Water", null, null, null, false, false)}</li>
 *   <li>{@code discard 1 Summon}
 *       → {@code DiscardCost(1, null, null, "Summon", null, null, false, false)}</li>
 *   <li>{@code discard 1 Card Name Red Mage}
 *       → {@code DiscardCost(1, "Red Mage", null, null, null, null, false, false)}</li>
 *   <li>{@code Discard 2 Category VI Characters}
 *       → {@code DiscardCost(2, null, null, "Character", "VI", null, false, false)}</li>
 *   <li>{@code Discard 3 cards, each of a different card type}
 *       → {@code DiscardCost(3, null, null, null, null, null, false, true)}</li>
 *   <li>{@code Discard 1 Job Moogle}
 *       → {@code DiscardCost(1, null, null, null, null, "Moogle", false, false)}</li>
 *   <li>{@code Discard a total of 2 Job Ninja or Card Name Ninja}
 *       → {@code DiscardCost(2, "Ninja", null, null, null, "Ninja", true, false)}</li>
 * </ul>
 *
 * @param count            number of cards to discard
 * @param cardName         non-null → must discard a card with this exact name
 * @param element          non-null → must discard a card of this element
 * @param cardType         non-null → "Summon", "Forward", "Backup", "Monster", or "Character"
 * @param category         non-null → must discard a card belonging to this category
 * @param job              non-null → must discard a card with this Job
 * @param jobOrName        {@code job} and {@code cardName} are a union rather than an intersection
 *                         — "Job Ninja <em>or</em> Card Name Ninja" (17-103R Yugiri). Only ever set
 *                         with both of them present; the naming follows
 *                         {@link CostReductionModifier#jobOrName()}, which reads the same phrase.
 * @param eachDifferentType each discarded card must be a different card type
 */
public record DiscardCost(
        int     count,
        String  cardName,
        String  element,
        String  cardType,
        String  category,
        String  job,
        boolean jobOrName,
        boolean eachDifferentType
) {}
