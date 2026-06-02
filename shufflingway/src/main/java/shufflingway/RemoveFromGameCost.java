package shufflingway;

/**
 * A single "remove from game" payment cost on an Action Ability.
 *
 * <p>Examples and their parsed form:
 * <ul>
 *   <li>{@code remove <cardName> from the game}
 *       → {@code RemoveFromGameCost("FIELD", 1, "<cardName>", null, null, null)}</li>
 *   <li>{@code remove 1 card in your hand from the game}
 *       → {@code RemoveFromGameCost("HAND", 1, null, null, null, null)}</li>
 *   <li>{@code remove 3 Summons in the Break Zone from the game}
 *       → {@code RemoveFromGameCost("BREAK_ZONE", 3, null, null, "Summon", null)}</li>
 *   <li>{@code Remove the top 5 cards of your deck from the game}
 *       → {@code RemoveFromGameCost("DECK", 5, null, null, null, null)}</li>
 *   <li>{@code Remove 2 backups from the game}
 *       → {@code RemoveFromGameCost("FIELD", 2, null, null, "Backup", null)}</li>
 *   <li>{@code remove 2 Card Name <cardName> in the Break Zone from the game}
 *       → {@code RemoveFromGameCost("BREAK_ZONE", 2, "<cardName>", null, null, null)}</li>
 *   <li>{@code remove all the Summons in your Break Zone from the game}
 *       → {@code RemoveFromGameCost("BREAK_ZONE", -1, null, null, "Summon", null)}</li>
 *   <li>{@code remove 1 Forward other than <cardName> from the game}
 *       → {@code RemoveFromGameCost("FIELD", 1, null, null, "Forward", "<cardName>")}</li>
 *   <li>{@code Remove 3 Ice cards in the Break Zone from the game}
 *       → {@code RemoveFromGameCost("BREAK_ZONE", 3, null, "Ice", null, null)}</li>
 * </ul>
 *
 * @param zone        source zone: {@code "HAND"}, {@code "FIELD"}, {@code "BREAK_ZONE"}, or {@code "DECK"}
 * @param count       number of cards to remove; {@code -1} means "all matching"
 * @param cardName    non-null → must remove a card with this exact name
 * @param element     non-null → filter by element (e.g. "Ice")
 * @param cardType    non-null → filter by type: "Summon", "Forward", "Backup", "Monster", "Character"
 * @param excludeName non-null → skip cards with this name ("other than X")
 */
public record RemoveFromGameCost(
        String zone,
        int    count,
        String cardName,
        String element,
        String cardType,
        String excludeName
) {}
