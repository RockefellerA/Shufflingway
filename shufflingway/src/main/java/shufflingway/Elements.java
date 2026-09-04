package shufflingway;

import java.util.List;

/**
 * The game's eight Elements, in printed order.
 *
 * <p>A vocabulary rather than a rule, and read by nearly every layer: the CP pools count per
 * Element, the AI totals its CP across them, the "name an Element" dialogs offer them, and
 * {@link NamedThing} indexes into this list to send a named Element across the wire. Order is
 * therefore part of the contract — a position in this list means the same Element on both
 * clients, so nothing may be reordered or inserted without both ends agreeing.
 *
 * <p>It used to live on {@code ActionResolverPatterns}, which is a package-private class of
 * compiled regexes that never referenced it: the constant had no reader inside the resolver
 * family and eight outside it. Held as an immutable {@code List} rather than the {@code String[]}
 * it was, because a shared array is a global every caller can write to.
 */
public final class Elements {

	private Elements() {}

	/** Every Element, in printed order. */
	public static final List<String> ALL =
			List.of("Fire", "Ice", "Wind", "Earth", "Lightning", "Water", "Light", "Dark");
}
