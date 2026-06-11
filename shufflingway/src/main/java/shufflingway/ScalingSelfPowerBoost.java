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
        String cardNameFilter  // pipe-separated; null if unused
) {
    /** Convenience constructor for sources that do not filter (jobFilter/categoryFilter/cardNameFilter == null). */
    public ScalingSelfPowerBoost(Source source, int perUnit) {
        this(source, perUnit, null, null, null);
    }

    public enum Source {
        /** Number of Forwards the opponent currently controls. */
        OPPONENT_FORWARDS,
        /** Number of Characters the controller controls whose name differs from the source card's name. */
        OTHER_CHARACTERS_YOU_CONTROL,
        /**
         * Number of Forwards the controller controls (other than the source by name) that satisfy
         * the disjunction of {@link #jobFilter}, {@link #categoryFilter}, and {@link #cardNameFilter}.
         * A null filter is treated as "no restriction"; when all three are null, every other Forward counts.
         */
        OTHER_FORWARDS_YOU_CONTROL
    }
}
