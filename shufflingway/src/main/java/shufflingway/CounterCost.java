package shufflingway;

/**
 * A single "remove N [Name] Counter(s) from [CardName]" action-ability cost.
 *
 * <p>Examples:
 * <ul>
 *   <li>{@code remove 2 Item Counters from cardName1}
 *       → {@code CounterCost("cardName1", "Item", 2)}</li>
 *   <li>{@code remove 1 Shuriken Counter from cardName2}
 *       → {@code CounterCost("cardName2", "Shuriken", 1)}</li>
 * </ul>
 */
public record CounterCost(
        String cardName,     // card that the counters must be on (typically the source card)
        String counterName,  // name of the counter type (e.g. "Item", "Shuriken")
        int    count         // number of counters to remove
) {}
