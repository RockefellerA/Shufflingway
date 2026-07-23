package shufflingway;

import java.util.Map;

/** Pure static utilities for CP element-assignment during card payment. */
public class CpPaymentUtils {
	private CpPaymentUtils() {}

	/** Returns the first element of {@code source} that matches one of {@code playedElems}. */
	public static String contributingElement(CardData source, String[] playedElems) {
		for (String pe : playedElems)
			if (source.containsElement(pe)) return pe;
		return playedElems[0];
	}

	/**
	 * Returns the element of {@code source} that has the largest remaining deficit
	 * ({@code required - alreadyPaid}), so multi-element payment cards fill whichever
	 * requirement is still most needed rather than always defaulting to the first match.
	 */
	public static String contributingElement(CardData source, String[] playedElems,
			Map<String, Integer> cpByElem, Map<String, Integer> costByElem) {
		String best = null;
		int maxDeficit = Integer.MIN_VALUE;
		for (String pe : playedElems) {
			if (source.containsElement(pe)) {
				int deficit = costByElem.getOrDefault(pe, 0) - cpByElem.getOrDefault(pe, 0);
				if (deficit > maxDeficit) {
					maxDeficit = deficit;
					best = pe;
				}
			}
		}
		return best != null ? best : playedElems[0];
	}

	/** Returns true if {@code source} contains any element from {@code playedElems}. */
	public static boolean matchesAnyElement(CardData source, String[] playedElems) {
		for (String pe : playedElems)
			if (source.containsElement(pe)) return true;
		return false;
	}

	/**
	 * Returns true when {@code handCard} may be discarded from hand for CP: non-Light/Dark
	 * cards always may; Light/Dark cards only when a field grant ("You can discard [Light and
	 * Dark|Dark] Element cards from your hand to produce CP") covers their element.
	 *
	 * @param ldDiscardGrants the Light/Dark elements granted by the player's field cards
	 *                        (see {@code MainWindow.lightDarkDiscardGrants})
	 */
	public static boolean canDiscardForCp(CardData handCard, java.util.Set<String> ldDiscardGrants) {
		if (!handCard.isLightOrDark()) return true;
		for (String e : handCard.elements())
			if (ldDiscardGrants.contains(e)) return true;
		return false;
	}
}
