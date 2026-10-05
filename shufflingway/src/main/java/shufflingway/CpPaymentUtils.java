package shufflingway;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

	/**
	 * The CP of each Element a cast owes: 1 for each of the card's own {@code baseElems}, plus 1 for
	 * every entry of {@code extraElems} — a fixed extra cost's named tokens, one entry per token.
	 * Fina 8-060L (Wind) with her 《Wind》《Wind》《Wind》 surcharge owes 4 Wind.
	 */
	public static Map<String, Integer> requiredCpByElement(String[] baseElems, String[] extraElems) {
		Map<String, Integer> need = new java.util.LinkedHashMap<>();
		for (String e : baseElems) need.put(e, 1);
		if (extraElems != null) for (String e : extraElems) need.merge(e, 1, Integer::sum);
		return need;
	}

	/** Whether {@code paid} has at least the CP of every Element {@code need} asks for. */
	public static boolean requiredCpMet(Map<String, Integer> paid, Map<String, Integer> need) {
		return need.entrySet().stream().allMatch(e -> paid.getOrDefault(e.getKey(), 0) >= e.getValue());
	}

	/**
	 * Whether dulling {@code backups} (1 CP each) and discarding {@code discards} (2 CP each) meets
	 * every per-element minimum in {@code needs} — the element half of a cost like 《Fire》, which a
	 * total alone cannot check. Each card is credited to whichever of its elements is still most
	 * short, the assignment {@code AbilityPaymentDialog} makes, so a multi-element card fills the
	 * requirement that needs it. Empty {@code needs} is always met.
	 */
	public static boolean elementNeedsMet(Iterable<CardData> backups, Iterable<CardData> discards,
			Map<String, Integer> needs) {
		if (needs.isEmpty()) return true;
		Map<String, Integer> paid = elementCpPaid(backups, discards, needs);
		return needs.entrySet().stream().allMatch(e -> paid.getOrDefault(e.getKey(), 0) >= e.getValue());
	}

	/** Per element of {@code needs}, the CP {@code backups} and {@code discards} put toward it. */
	public static Map<String, Integer> elementCpPaid(Iterable<CardData> backups, Iterable<CardData> discards,
			Map<String, Integer> needs) {
		String[] elems = needs.keySet().toArray(String[]::new);
		Map<String, Integer> paid = new java.util.LinkedHashMap<>();
		for (String e : elems) paid.put(e, 0);
		if (elems.length == 0) return paid;
		for (CardData c : backups)
			if (c != null && matchesAnyElement(c, elems))
				paid.merge(contributingElement(c, elems, paid, needs), 1, Integer::sum);
		for (CardData c : discards)
			if (c != null && matchesAnyElement(c, elems))
				paid.merge(contributingElement(c, elems, paid, needs), 2, Integer::sum);
		return paid;
	}

	/**
	 * Whether every element in {@code needed} can be given a CP source of its own from
	 * {@code sources}, each source being the set of those elements it could produce.
	 *
	 * <p>A source pays one element, not all of them: discarding a Fire/Ice card makes 2 CP of Fire
	 * <em>or</em> of Ice, and an any-element Backup makes 1 CP of one. So a Fire/Ice cost cannot be
	 * met by a single Fire/Ice discard, and asking each element separately whether some source
	 * could cover it says yes to exactly that. This is a bipartite matching, found by augmenting
	 * paths; {@code needed} is never more than a few elements.
	 */
	public static boolean distinctSourcesCover(List<String> needed, List<Set<String>> sources) {
		int[] paying = new int[sources.size()];   // element index each source is assigned, or -1
		Arrays.fill(paying, -1);
		for (int e = 0; e < needed.size(); e++)
			if (!assignSource(e, needed, sources, paying, new boolean[sources.size()])) return false;
		return true;
	}

	/** Finds element {@code e} a source, moving an earlier element to another source if that frees one. */
	private static boolean assignSource(int e, List<String> needed, List<Set<String>> sources,
			int[] paying, boolean[] tried) {
		for (int s = 0; s < sources.size(); s++) {
			if (tried[s] || !sources.get(s).contains(needed.get(e))) continue;
			tried[s] = true;
			if (paying[s] < 0 || assignSource(paying[s], needed, sources, paying, tried)) {
				paying[s] = e;
				return true;
			}
		}
		return false;
	}

	/** Returns true if {@code source} contains any element from {@code playedElems}. */
	public static boolean matchesAnyElement(CardData source, String[] playedElems) {
		for (String pe : playedElems)
			if (source.containsElement(pe)) return true;
		return false;
	}

	/**
	 * Returns true when the card whose action ability is being paid for may also be dulled for CP
	 * toward that same ability.
	 *
	 * <p>It comes down to whether the ability spends the source's dull. A 《Dull》 cost does, so the
	 * one dull cannot also be sold for CP — letting it would settle both halves of a
	 * 《Fire》《Dull》 cost with a single dull, which is the bug this rule was written for. Without a
	 * 《Dull》 cost the source is an ordinary CP source like any other Backup on the row, and a
	 * Backup that the ability's own cost sacrifices (1-053C Summoner, 6-047C White Mage) is the case
	 * that makes it worth having: it is leaving the field regardless, so its dull is free money.
	 *
	 * <p>Both seats ask this one question — {@code AbilityPaymentDialog} when it decides which slots
	 * a human may click, {@code ComputerPlayer.p2PlanAbilityPayment} when it plans P2's payment.
	 * They disagreed once already, in P2's favour.
	 */
	public static boolean sourceCanFundOwnAbility(ActionAbility ability) {
		return !ability.requiresDull();
	}

	/**
	 * Returns true when {@code handCard} may be discarded from hand for CP: non-Light/Dark
	 * cards always may; Light/Dark cards only when a field grant ("You can discard [Light and
	 * Dark|Dark] Element cards from your hand to produce CP") covers their element.
	 *
	 * @param ldDiscardGrants the Light/Dark elements granted by the player's field cards
	 *                        (see {@code MainWindow.lightDarkDiscardGrants})
	 */
	public static boolean canDiscardForCp(CardData handCard, Set<String> ldDiscardGrants) {
		if (!handCard.isLightOrDark()) return true;
		for (String e : handCard.elements())
			if (ldDiscardGrants.contains(e)) return true;
		return false;
	}

	/**
	 * Returns the parenthetical text for a payment dialog's hint, listing which hand cards may be
	 * discarded for CP.
	 *
	 * @param elem            the element being paid
	 * @param isLD            true when the cost is element-agnostic (Light/Dark card, or a cast that
	 *                        accepts any element). {@code elem} is then omitted from the list: it is
	 *                        irrelevant to eligibility, and naming Light or Dark there would wrongly
	 *                        imply those cards are discardable
	 * @param elemOnly        true when only cards of {@code elem} may be discarded ("you can only pay
	 *                        with [Element] CP")
	 * @param ldDiscardGrants the Light/Dark elements a field grant makes discardable. A grant is
	 *                        stated as the exclusion it lifts rather than appended as an extra
	 *                        clause: Spiritus (Dark only) narrows the text to "non-Light", and
	 *                        Tilika (both) removes the exclusion entirely
	 */
	public static String discardEligibility(String elem, boolean isLD, boolean elemOnly,
			Set<String> ldDiscardGrants) {
		if (elemOnly) return elem + " only";
		boolean light = containsIgnoreCase(ldDiscardGrants, "Light");
		boolean dark  = containsIgnoreCase(ldDiscardGrants, "Dark");
		if (light && dark) return (isLD ? "" : elem + ", ") + "including Light/Dark";
		String excluded = light ? "non-Dark" : dark ? "non-Light" : "non-Light/Dark";
		return (isLD ? "" : elem + ", ") + excluded;
	}

	/** Returns true if {@code values} holds {@code target}, ignoring case. */
	private static boolean containsIgnoreCase(Set<String> values, String target) {
		for (String v : values)
			if (v.equalsIgnoreCase(target)) return true;
		return false;
	}
}
