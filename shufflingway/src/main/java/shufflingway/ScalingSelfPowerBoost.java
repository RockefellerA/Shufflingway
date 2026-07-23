package shufflingway;

/**
 * A passive self-targeting power boost whose magnitude scales with a counted game state.
 *
 * <p>Active while the owning card is on the field; the bonus is recomputed on each query as
 * {@link #perUnit} multiplied by {@code floor(count / groupSize)}, where {@code count} is
 * derived from {@link #source} and {@code groupSize} is 1 for "for each" patterns and N for
 * "for every N" patterns.
 */
public record ScalingSelfPowerBoost(
        Source source,
        int    perUnit,
        String jobFilter,      // pipe-separated; null if unused
        String categoryFilter, // null if unused
        String cardNameFilter, // pipe-separated; null if unused
        String elementFilter,  // include-only element (e.g., "Earth"); null if unused
        String excludeElement, // exclude this element (e.g., "Fire"); null if unused
        boolean requireActive, // count only active (non-dull) characters
        int    groupSize       // 1 for "for each", N for "for every N"
) {
    /** Convenience constructor for sources that do not filter at all (groupSize = 1). */
    public ScalingSelfPowerBoost(Source source, int perUnit) {
        this(source, perUnit, null, null, null, null, null, false, 1);
    }

    /** Convenience constructor with all filter fields but groupSize = 1. */
    public ScalingSelfPowerBoost(Source source, int perUnit,
            String jobFilter, String categoryFilter, String cardNameFilter,
            String elementFilter, String excludeElement, boolean requireActive) {
        this(source, perUnit, jobFilter, categoryFilter, cardNameFilter,
                elementFilter, excludeElement, requireActive, 1);
    }

    public enum Source {
        /** Number of Forwards the opponent currently controls. */
        OPPONENT_FORWARDS,
        /** Number of Backups the opponent currently controls. */
        OPPONENT_BACKUPS,
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
        /**
         * Number of Summons in the controller's Break Zone.
         * Typically paired with {@link #groupSize} &gt; 1 for "for every N Summons" patterns.
         */
        SUMMONS_IN_BREAK_ZONE,
        /** Number of cards currently in the controller's hand. */
        CARDS_IN_HAND,
        /**
         * Number of counters placed on the source card itself, named by {@link #cardNameFilter}
         * (e.g. "EXP" for "For each EXP Counter placed on Palom, Palom gains +1000 power.").
         */
        COUNTERS_ON_SELF
    }
}
