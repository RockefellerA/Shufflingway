package shufflingway;

import shufflingway.dialog.NameSelectionDialogs;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * One thing a player named when an ability told them to — an Element, a Job, a Category.
 *
 * <p>"Name 1 Job. Forwards of that Job gain +1000 power" is a choice like any other, but it is not
 * a choice <em>among cards</em>, so nothing on the board indexes it. What indexes it instead is the
 * vocabulary it was drawn from: the eight Elements are a fixed list, and the Jobs and Categories
 * are every value in the card database, sorted. Both clients build all three the same way from the
 * same data, so a position in one names the same thing on both.
 *
 * <p>That indirection is the whole point. The alternative is sending the word itself, which the
 * answer format has no room for — and the alternative to <em>that</em> was what this replaced: each
 * client picking its own Job at random and never comparing notes.
 *
 * @param vocabulary which list {@code value} was drawn from
 * @param value      the named Element, Job or Category
 */
record NamedThing(Vocabulary vocabulary, String value) {

	/**
	 * The lists a player can be told to name something out of.
	 *
	 * <p>Ordinals are wire values — they travel in every naming answer — so entries may be added
	 * at the end but not reordered.
	 */
	enum Vocabulary {
		ELEMENT, JOB, CATEGORY;

		/**
		 * Every value in this vocabulary, in the one order both clients agree on.
		 *
		 * <p>Sorted at the source: {@link Elements#ALL} is a fixed list and the two database
		 * lists are built through a {@code TreeSet}, so none of this depends on what order SQLite
		 * felt like returning rows in.
		 */
		List<String> values(Consumer<String> log) {
			return switch (this) {
				case ELEMENT  -> Elements.ALL;
				case JOB      -> NameSelectionDialogs.jobNames(log);
				case CATEGORY -> NameSelectionDialogs.categoryNames(log);
			};
		}
	}

	/** A one-thing answer, or the empty list when {@code value} is null — a seat that declined. */
	static List<NamedThing> of(Vocabulary vocabulary, String value) {
		return value == null ? List.of() : List.of(new NamedThing(vocabulary, value));
	}

	/**
	 * Flattens a naming answer into the integers it travels as: {@code [ordinal, index]} per thing
	 * named, in order. Two pairs is an ability that asked for two — "name 1 Element and 1 Job".
	 *
	 * <p>An empty list comes back when anything named is missing from this client's vocabulary,
	 * because a half-sent answer is worse than none: the receiver would apply the part that
	 * survived. That should not happen between clients holding the same card database, and it is
	 * logged when it does.
	 */
	static List<Integer> toAnswer(List<NamedThing> named, Consumer<String> log) {
		List<Integer> out = new ArrayList<>(named.size() * 2);
		for (NamedThing t : named) {
			int i = indexIn(t.vocabulary().values(log), t.value());
			if (i < 0) {
				log.accept("[Name] " + t.value() + " is not a known "
						+ t.vocabulary().name().toLowerCase() + " — nothing named");
				return List.of();
			}
			out.add(t.vocabulary().ordinal());
			out.add(i);
		}
		return out;
	}

	/**
	 * Reads back a {@link #toAnswer}, or {@code null} when it does not name anything this client
	 * recognises — an unknown vocabulary, or an index past the end of one. Either means the two
	 * clients disagree about the card database, which is a desync for the caller to report rather
	 * than a name to act on.
	 */
	static List<NamedThing> fromAnswer(List<Integer> answer, Consumer<String> log) {
		if (answer.size() % 2 != 0) return null;
		Vocabulary[] all = Vocabulary.values();
		List<NamedThing> out = new ArrayList<>(answer.size() / 2);
		for (int k = 0; k < answer.size(); k += 2) {
			int ordinal = answer.get(k), index = answer.get(k + 1);
			if (ordinal < 0 || ordinal >= all.length) return null;
			List<String> vocabulary = all[ordinal].values(log);
			if (index < 0 || index >= vocabulary.size()) return null;
			out.add(new NamedThing(all[ordinal], vocabulary.get(index)));
		}
		return out;
	}

	/** Case-insensitive position, because the dialogs and the database do not always agree on case. */
	private static int indexIn(List<String> vocabulary, String value) {
		for (int i = 0; i < vocabulary.size(); i++)
			if (vocabulary.get(i).equalsIgnoreCase(value)) return i;
		return -1;
	}
}
