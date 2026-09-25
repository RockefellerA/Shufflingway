package shufflingway;

/**
 * One side of a reveal's "add N of them to your hand <b>or</b> play M of them onto the field"
 * choice: how many cards that branch may take, and which of the revealed cards it accepts.
 *
 * <p>The two branches are alternatives, not allowances — the player takes one branch or the other
 * and the untaken one lapses. That is the difference from {@link RevealQuota}, whose entries are
 * all spent in the same resolution.
 *
 * <p>A record rather than six more parameters on the context method, because the three printings
 * in the family filter on different things and every one of them has to be expressible on either
 * side: Serah 17-031L names a type on the left and a Job and a type on the right, Garuda (XVI)
 * 29-046L names an Element on both, and Yuri 16-061R names nothing at all on the left ("add 1
 * card") and an Element and a cost on the right.
 *
 * @param max           how many cards this branch may take
 * @param elementFilter bar-separated Elements, any one of which qualifies; {@code null} for any
 * @param typeFilter    "Forward", "Backup", "Monster", "Character", "Summon", or "Card" for a
 *                      branch that names no type at all — Yuri's hand side, which takes any of
 *                      the revealed cards including a Summon
 * @param jobFilter     bar-separated Jobs, any one of which qualifies; {@code null} for any
 * @param costVal       the cost filter's number, or {@code -1} for no cost filter
 * @param costCmp       how {@code costVal} is compared — the {@code CardFilters} spelling, where
 *                      {@code null} means an exact cost. Yuri's "of cost 3" is exact and not a
 *                      ceiling, which is the whole reason this is a pair and not a maximum.
 * @param nameFilter    a card name that qualifies as an alternative to {@code jobFilter} — "Job
 *                      Samurai or Card Name Samurai" (26-002R Ayame); {@code null} for none
 */
record RevealBranch(int max, String elementFilter, String typeFilter, String jobFilter,
		int costVal, String costCmp, String nameFilter) {

	/** A branch with no card-name alternative. */
	RevealBranch(int max, String elementFilter, String typeFilter, String jobFilter,
			int costVal, String costCmp) {
		this(max, elementFilter, typeFilter, jobFilter, costVal, costCmp, null);
	}

	/** A branch taking {@code max} cards of {@code typeFilter} and nothing else. */
	static RevealBranch of(int max, String typeFilter) {
		return new RevealBranch(max, null, typeFilter, null, -1, null);
	}

	/** Whether {@code card} is one this branch would accept. */
	boolean accepts(CardData card) {
		return matchesType(card)
				&& CardFilters.meetsElementFilter(card, elementFilter)
				&& matchesIdentity(card)
				&& CardFilters.meetsCostConstraint(card.cost(), costVal, costCmp);
	}

	/** The Job, or the name printed as its alternative; either qualifies. */
	private boolean matchesIdentity(CardData card) {
		if (nameFilter == null) return CardFilters.meetsJobFilter(card, jobFilter);
		return CardFilters.meetsCardNameFilter(card, nameFilter)
				|| (jobFilter != null && CardFilters.meetsJobFilter(card, jobFilter));
	}

	private boolean matchesType(CardData card) {
		return switch (typeFilter.toLowerCase(java.util.Locale.ROOT)) {
			case "card"      -> true;
			case "monster"   -> card.isMonster();
			case "forward"   -> card.isForward();
			case "backup"    -> card.isBackup();
			case "summon"    -> card.isSummon();
			case "character" -> card.isForward() || card.isBackup() || card.isMonster();
			default          -> false;
		};
	}

	/** How the branch reads in a dialog title or a log line: "1 Wind Character of cost 3". */
	String describe() {
		return max + (elementFilter != null ? " " + elementFilter.replace("|", " or ") : "")
				+ (jobFilter != null ? " Job " + jobFilter.replace("|", " or ") : "")
				+ (nameFilter != null ? (jobFilter != null ? " or" : "") + " Card Name " + nameFilter : "")
				+ " " + typeFilter + CardFilters.formatCostFilterLabel(costVal, costCmp);
	}
}
