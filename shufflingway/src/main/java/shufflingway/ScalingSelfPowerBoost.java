package shufflingway;

/**
 * A passive self-targeting power boost whose magnitude scales with a counted game state.
 *
 * <p>Active while the owning card is on the field; the bonus is recomputed on each query as
 * {@link #perUnit} multiplied by the count derived from {@link #source}.
 *
 * <p>Currently supports only "For each Forward opponent controls, &lt;CardName&gt; gains +N power."
 */
public record ScalingSelfPowerBoost(
        Source source,
        int    perUnit
) {
    public enum Source {
        /** Number of Forwards the opponent currently controls. */
        OPPONENT_FORWARDS
    }
}
