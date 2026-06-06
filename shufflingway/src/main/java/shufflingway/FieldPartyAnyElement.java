package shufflingway;

/**
 * A passive always-on grant: while this card is on the field, Forwards matching the
 * filter that are controlled by the same player may form a party with Forwards of any Element.
 *
 * <p>Examples:
 * <ul>
 *   <li>"The Forwards you control can form a party with Forwards of any Element."</li>
 *   <li>"The Job Warrior of Light Forwards you control can form a party with Job Warrior of Light Forwards of any Element."</li>
 *   <li>"The Category VI Forwards you control can form a party with Forwards of any Element."</li>
 * </ul>
 *
 * <p>Turn-scoped versions ("…this turn") are tracked separately via a runtime flag
 * rather than this record.
 */
public record FieldPartyAnyElement(
        String jobFilter,       // null = any job
        String categoryFilter,  // null = any category
        String cardNameFilter   // null = any name
) {
    /**
     * Returns {@code true} if this grant applies to {@code card}.
     * Callers must ensure the card and the grant source belong to the same player.
     */
    public boolean appliesToCard(CardData card) {
        if (!card.isForward()) return false;
        if (!CardFilters.meetsJobFilter(card, jobFilter))           return false;
        if (!CardFilters.meetsCategoryFilter(card, categoryFilter)) return false;
        if (!CardFilters.meetsCardNameFilter(card, cardNameFilter)) return false;
        return true;
    }
}
