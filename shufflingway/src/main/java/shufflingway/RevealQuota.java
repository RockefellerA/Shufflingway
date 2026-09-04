package shufflingway;

import java.util.ArrayList;
import java.util.List;

/**
 * One "up to N &lt;Element&gt; card" allowance inside a reveal that prints several of them —
 * Shantotto 14-067H's "Add up to 1 Wind card <b>and</b> up to 1 Earth card", Terra 27-014H's
 * "up to 1 Fire card and up to 1 Wind or Lightning card", Cindy 27-063H's Earth/Ice-or-Lightning
 * pair.
 *
 * <p><b>Why this is not one filter with a count.</b> "Add up to 2 Wind or Earth cards" would let a
 * player take two Wind cards; these texts do not. Each allowance is spent separately, so revealing
 * two Wind cards and no Earth one takes exactly one card. That is the whole difference, and it is
 * why {@code revealTopAddUpToMatchingRestBottom}'s single {@code maxAdd} could not express it.
 *
 * <p>{@code elementFilter} is bar-separated, the form {@link CardFilters#meetsElementFilter} reads,
 * so a quota naming alternatives ("Wind or Lightning") is one allowance that either Element fills —
 * an alternation <em>inside</em> a quota, where the quota list itself is a conjunction.
 *
 * <p><b>A card fills at most one quota.</b> Elements are not mutually exclusive: a Wind/Earth
 * multicard answers to both of Shantotto's allowances, but taking it spends only one of them. That
 * is what makes eligibility a matching problem rather than a per-card test, and why
 * {@link LookAtDeckDialogs#quotasAdmit} exists instead of a {@code Predicate}.
 *
 * @param count         how many cards this allowance may take
 * @param elementFilter bar-separated Elements any one of which fills it
 */
record RevealQuota(int count, String elementFilter) {

	/** Whether {@code card} is the kind this allowance accepts. */
	boolean accepts(CardData card) {
		return CardFilters.meetsElementFilter(card, elementFilter);
	}

	/** How the allowance reads in a log line or a dialog's instructions. */
	String describe() {
		return "up to " + count + " " + elementFilter.replace("|", " or ") + " card"
				+ (count == 1 ? "" : "s");
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
