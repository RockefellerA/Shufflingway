package shufflingway;

/**
 * A single "return to its owner's hand" payment cost on an Action Ability.
 * Always targets a card on the player's own field.
 *
 * <p>Examples and their parsed form:
 * <ul>
 *   <li>{@code return <cardName> to its owner's hand}
 *       → {@code ReturnToHandCost(1, "<cardName>", null, null, null)}</li>
 *   <li>{@code return 1 Category <category> Character other than <cardName> to its owner's hand}
 *       → {@code ReturnToHandCost(1, null, "Character", "<category>", "<cardName>")}</li>
 * </ul>
 *
 * @param count       number of cards to return
 * @param cardName    non-null → must return a card with this exact name
 * @param cardType    non-null → filter by type: "Summon", "Forward", "Backup", "Monster", "Character"
 * @param category    non-null → filter by category (e.g. "VII")
 * @param excludeName non-null → skip cards with this name ("other than X")
 */
public record ReturnToHandCost(
        int    count,
        String cardName,
        String cardType,
        String category,
        String excludeName
) {}
