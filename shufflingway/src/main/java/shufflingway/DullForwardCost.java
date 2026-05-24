package shufflingway;

/**
 * An ability cost that requires dulling N forwards currently on the field.
 * Represents a "Dull N [condition] [element] Forward(s)" cost phrase.
 *
 * <p>Example: {@code "Dull 1 active Forward"} → {@code DullForwardCost(1, "active", null)}
 */
public record DullForwardCost(int count, String condition, String element) {}
