package shufflingway;

import java.lang.reflect.Proxy;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;

/**
 * What a deck search would find, asked without searching.
 *
 * <p>The filters a search applies are read by the resolver ({@link ActionResolverSearch}) and handed
 * to one of {@link GameContext}'s four {@code searchDeckFor…} primitives as the effect resolves.
 * So the effect is parsed as it would resolve and run against a stand-in context that does nothing
 * but note the first search it is asked for — nothing is searched, moved, prompted for or logged —
 * and the deck is then matched as the search itself matches it ({@link MainWindow#deckSearchMatches}).
 * The parser stays the one reading of the text.
 *
 * <p>Asked for the CPU, which may count what is in its own deck, though not its order, as any
 * player can count their own: the X it pays for 22-004H Angeal's "search for 1 Job SOLDIER Forward
 * of cost X and play it onto the field".
 */
final class SearchProbe {

	private SearchProbe() {}

	/**
	 * What a search finds: the matching cards in deck order, and where the search puts the one it
	 * takes ("hand", "field", "breakZone", …).
	 */
	record Found(List<CardData> cards, String destination) {}

	/**
	 * The cards in {@code isP1}'s deck that {@code effectText}'s search would find, or {@code null}
	 * when the text asks for no search this reads.
	 */
	static Found finds(MainWindow mw, String effectText, CardData source, boolean isP1) {
		Object[] asked = new Object[1];
		String[] primitive = new String[1];
		Consumer<GameContext> effect = ActionResolver.parse(effectText, source);
		if (effect == null) return null;
		GameContext standIn = (GameContext) Proxy.newProxyInstance(GameContext.class.getClassLoader(),
				new Class<?>[]{GameContext.class}, (proxy, method, args) -> {
					String name = method.getName();
					if (name.startsWith("searchDeckFor") && primitive[0] == null) {
						primitive[0] = name;
						asked[0] = args;
					}
					// A "you may search" is taken, so the search behind it is reached.
					if (name.equals("promptYouMay")) return true;
					return nothingOf(method.getReturnType());
				});
		try {
			effect.accept(standIn);
		} catch (RuntimeException e) {
			return null;   // a step the stand-in's empty answers cannot carry: no reading
		}
		if (primitive[0] == null) return null;
		Object[] p = (Object[]) asked[0];
		Collection<CardData> deck = isP1 ? mw.gameState.getP1MainDeck() : mw.gameState.getP2MainDeck();
		boolean fwd = (boolean) p[0], bk = (boolean) p[1], mn = (boolean) p[2], sm = (boolean) p[3];
		int cost = (int) p[4];
		String cmp = (String) p[5];
		return switch (primitive[0]) {
			// (…, name, job, category, element, excludeName, excludeElem, destination, count, dull, trait[, riders])
			case "searchDeckForCard", "searchDeckForCardWithRiders" -> new Found(mw.deckSearchMatches(deck,
					fwd, bk, mn, sm, cost, cmp, -1, null, (String) p[6], (String) p[7], (String) p[8],
					(String) p[9], (String) p[10], (String) p[11], (CardData.Trait) p[15], false), (String) p[12]);
			// (…, power, powerCmp, name, job, category, element, excludeName, excludeElem, destination, count, dull, trait)
			case "searchDeckForCardWithPower" -> new Found(mw.deckSearchMatches(deck,
					fwd, bk, mn, sm, cost, cmp, (int) p[6], (String) p[7], (String) p[8], (String) p[9],
					(String) p[10], (String) p[11], (String) p[12], (String) p[13], (CardData.Trait) p[17], false),
					(String) p[14]);
			// (…, name, job, element, excludeName, excludeElem, destination, count, dull, trait) — no category
			case "searchDeckForNamedCardWithJob" -> new Found(mw.deckSearchMatches(deck,
					fwd, bk, mn, sm, cost, cmp, -1, null, (String) p[6], (String) p[7], null,
					(String) p[8], (String) p[9], (String) p[10], (CardData.Trait) p[14], true), (String) p[11]);
			default -> null;
		};
	}

	/** The stand-in's answer to anything else: nothing — false, zero or {@code null}. */
	private static Object nothingOf(Class<?> type) {
		if (!type.isPrimitive() || type == void.class) return null;
		if (type == boolean.class) return false;
		if (type == char.class)    return '\0';
		if (type == long.class)    return 0L;
		if (type == float.class)   return 0f;
		if (type == double.class)  return 0d;
		if (type == byte.class)    return (byte) 0;
		if (type == short.class)   return (short) 0;
		return 0;
	}
}
