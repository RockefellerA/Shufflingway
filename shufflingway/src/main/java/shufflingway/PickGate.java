package shufflingway;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A rule that can refuse a pick because of what has already been picked in the same selection.
 *
 * <p>Card text spells these as a rider on a multi-card choice — "up to 3 Job Warring Triad
 * <em>with different names</em>" (20-008H Kefka), "up to 4 Forwards of cost 2, <em>each of a
 * different Element</em>" (1-135L Golbez). The filters those selections already carry decide
 * which cards are <em>eligible</em>; this decides which combinations of them are <em>legal</em>,
 * which no per-card filter can express.
 *
 * <p>Enforced at pick time rather than by narrowing the pool before the window opens: every
 * eligible card stays on offer, and one that would collide simply will not select. That is what
 * lets a player take the copy they want when several share a name, and it is the rule
 * {@code CardPickerDialog}'s deck-search picker was already following for "with different names".
 */
public enum PickGate {

	/** No constraint — every eligible card can be added to any selection. */
	ANY,

	/** No two picks may share a name. */
	DISTINCT_NAMES,

	/**
	 * No two picks may share an <em>any</em> element. A multi-element card takes every element it
	 * carries out of play for the rest of the selection, and cannot itself be added once any one of
	 * its elements is spoken for — Fire/Ice blocks a later Fire pick and a later Ice pick alike, and
	 * a standing Fire pick blocks it.
	 */
	DISTINCT_ELEMENTS;

	/** Whether {@code candidate} may join a selection that already holds {@code picked}. */
	public boolean allows(List<CardData> picked, CardData candidate) {
		if (candidate == null) return false;
		switch (this) {
			case ANY -> { return true; }
			case DISTINCT_NAMES -> {
				for (CardData c : picked)
					if (c != null && c.name().equalsIgnoreCase(candidate.name())) return false;
				return true;
			}
			case DISTINCT_ELEMENTS -> {
				for (CardData c : picked)
					if (c != null && sharesAnyElement(c, candidate)) return false;
				return true;
			}
		}
		return true;
	}

	/** True when the two cards have at least one element in common. */
	private static boolean sharesAnyElement(CardData a, CardData b) {
		for (String e : elementsOf(b))
			if (a.containsElement(e)) return true;
		return false;
	}

	/** The printed elements of {@code card}, which the model stores slash-separated. */
	static List<String> elementsOf(CardData card) {
		String raw = card.element();
		if (raw == null || raw.isBlank()) return List.of();
		return Arrays.stream(raw.split("/")).map(String::trim).filter(e -> !e.isEmpty()).toList();
	}

	/** The rider as a selection dialog should word it, or {@code ""} for {@link #ANY}. */
	public String hint() {
		return switch (this) {
			case ANY              -> "";
			case DISTINCT_NAMES   -> ", each with a different name";
			case DISTINCT_ELEMENTS-> ", each of a different Element";
		};
	}

	/**
	 * The largest selection this gate can admit from {@code pool}, which is what an "up to N"
	 * selection can actually reach. Greedy in pool order, which is exact for both constraints
	 * here: names partition the pool, and the element case is only ever asked about small pools.
	 */
	public int maxSelectable(List<CardData> pool, int cap) {
		List<CardData> taken = new ArrayList<>();
		for (CardData c : pool) {
			if (taken.size() >= cap) break;
			if (allows(taken, c)) taken.add(c);
		}
		return taken.size();
	}
}
