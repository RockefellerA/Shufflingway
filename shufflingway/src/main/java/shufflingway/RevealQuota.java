package shufflingway;

import java.util.ArrayList;
import java.util.List;

/**
 * One "up to N &lt;kind&gt;" allowance inside a reveal that prints several of them —
 * Shantotto 14-067H's "Add up to 1 Wind card <b>and</b> up to 1 Earth card", Terra 27-014H's
 * "up to 1 Fire card and up to 1 Wind or Lightning card", Cindy 27-063H's Earth/Ice-or-Lightning
 * pair, Cid Garlond 30-079C's "up to 1 Lightning card and up to 1 Multi-Element card", and
 * Fickblix 30-044L's "up to 1 Forward and up to 1 Monster".
 *
 * <p><b>Why this is not one filter with a count.</b> "Add up to 2 Wind or Earth cards" would let a
 * player take two Wind cards; these texts do not. Each allowance is spent separately, so revealing
 * two Wind cards and no Earth one takes exactly one card. That is the whole difference, and it is
 * why {@code revealTopAddUpToMatchingRestBottom}'s single {@code maxAdd} could not express it.
 *
 * <p>An allowance names an Element or a card type, and Jayden Rundall 30-104H's narrow a type by
 * Category and cost: "up to 1 Category FFBE Character of cost 2 or less and up to 1 Category FFBE
 * Character of cost 4 or less". {@code elementFilter} is bar-separated, the form
 * {@link CardFilters#meetsElementFilter} reads, so a quota naming alternatives ("Wind or Lightning")
 * is one allowance that either Element fills — an alternation <em>inside</em> a quota, where the
 * quota list itself is a conjunction. {@code typeFilter} is a card type, "Forward", "Backup",
 * "Monster", "Character" or "Summon". Each filter may be absent: {@code null}, or a
 * {@code maxCost} below 0.
 *
 * <p><b>A card fills at most one quota.</b> Elements are not mutually exclusive: a Wind/Earth
 * multicard answers to both of Shantotto's allowances, but taking it spends only one of them, and a
 * cost-1 FFBE Character answers to both of Jayden's. That is what makes eligibility a matching
 * problem rather than a per-card test, and why {@link LookAtDeckDialogs#quotasAdmit} exists instead
 * of a {@code Predicate}.
 *
 * @param count         how many cards this allowance may take
 * @param elementFilter bar-separated Elements any one of which fills it, or {@code null}
 * @param typeFilter    the card type that fills it, or {@code null}
 * @param category      the Category it must belong to, or {@code null}
 * @param maxCost       the highest cost it admits, or below 0 for any cost
 */
record RevealQuota(int count, String elementFilter, String typeFilter, String category, int maxCost) {

	/** An Element allowance, which is what every printing before Opus 30 named. */
	RevealQuota(int count, String elementFilter) {
		this(count, elementFilter, null, null, -1);
	}

	/** An Element or card-type allowance with no Category or cost bound. */
	RevealQuota(int count, String elementFilter, String typeFilter) {
		this(count, elementFilter, typeFilter, null, -1);
	}

	/** Whether {@code card} is the kind this allowance accepts. */
	boolean accepts(CardData card) {
		return CardFilters.meetsElementFilter(card, elementFilter) && meetsType(card)
				&& (category == null || CardFilters.meetsCategoryFilter(card, category))
				&& (maxCost < 0 || card.cost() <= maxCost);
	}

	private boolean meetsType(CardData card) {
		if (typeFilter == null) return true;
		return switch (typeFilter) {
			case "Forward"   -> card.isForward();
			case "Backup"    -> card.isBackup();
			case "Monster"   -> card.isMonster();
			case "Summon"    -> card.isSummon();
			case "Character" -> card.isForward() || card.isBackup() || card.isMonster();
			default          -> false;
		};
	}

	/** How the allowance reads in a log line or a dialog's instructions. */
	String describe() {
		String plural = count == 1 ? "" : "s";
		String cat = category == null ? "" : "Category " + category + " ";
		String element = elementFilter == null ? "" : elementFilter.replace("|", " or ") + " ";
		String noun = typeFilter != null ? typeFilter : "card";
		String cost = maxCost < 0 ? "" : " of cost " + maxCost + " or less";
		return "up to " + count + " " + cat + element + noun + plural + cost;
	}

	/** The printed list, as one phrase: "up to 1 Wind card and up to 1 Earth card". */
	static String describeAll(List<RevealQuota> quotas) {
		List<String> parts = new ArrayList<>(quotas.size());
		for (RevealQuota q : quotas) parts.add(q.describe());
		return String.join(" and ", parts);
	}

	/** The most cards the whole list could ever take, so a caller can stop asking. */
	static int totalCount(List<RevealQuota> quotas) {
		int total = 0;
		for (RevealQuota q : quotas) total += q.count();
		return total;
	}
}
