package shufflingway;

/**
 * A passive self-targeting power boost whose magnitude scales with a counted game state.
 *
 * <p>Active while the owning card is on the field; the bonus is recomputed on each query as
 * {@link #perUnit} multiplied by the count derived from {@link #source}.
 */
public record ScalingSelfPowerBoost(
        Source source,
        int    perUnit,
        String jobFilter,      // pipe-separated; null if unused
        String categoryFilter, // null if unused
        String cardNameFilter, // pipe-separated; null if unused
        String elementFilter,  // include-only element (e.g., "Earth"); null if unused
        String excludeElement, // exclude this element (e.g., "Fire"); null if unused
        boolean requireActive  // count only active (non-dull) characters
) {
    /** Convenience constructor for sources that do not filter at all. */
    public ScalingSelfPowerBoost(Source source, int perUnit) {
        this(source, perUnit, null, null, null, null, null, false);
    }

    public enum Source {
        /** Number of Forwards the opponent currently controls. */
        OPPONENT_FORWARDS,
        /**
         * Number of Characters the controller controls whose name differs from the source card's name.
         * Honors {@link #requireActive}, {@link #elementFilter}, {@link #excludeElement}, and the
         * job/category/cardName filters (as an OR disjunction across the non-null filters).
         */
        OTHER_CHARACTERS_YOU_CONTROL,
        /**
         * Number of Forwards the controller controls (other than the source by name) that satisfy
         * the disjunction of {@link #jobFilter}, {@link #categoryFilter}, and {@link #cardNameFilter},
         * the inclusive {@link #elementFilter}, the {@link #excludeElement} exclusion, and
         * {@link #requireActive}. When all filters are null/false, every other Forward counts.
         */
        OTHER_FORWARDS_YOU_CONTROL,
        /** Number of Backups the controller controls (other than the source by name), honoring all filter fields. */
        OTHER_BACKUPS_YOU_CONTROL,
        /** Number of Monsters the controller controls (other than the source by name), honoring all filter fields. */
        OTHER_MONSTERS_YOU_CONTROL,
        /** Number of damage points the controller has received (size of their damage zone). */
        DAMAGE_RECEIVED,
        /**
         * Number of cards whose name matches {@link #cardNameFilter} in the controller's Break Zone.
         * The {@link #cardNameFilter} field holds the card name to count.
         */
        CARD_NAME_IN_BREAK_ZONE,
        /** Number of cards currently in the controller's hand. */
        CARDS_IN_HAND
    }
}
