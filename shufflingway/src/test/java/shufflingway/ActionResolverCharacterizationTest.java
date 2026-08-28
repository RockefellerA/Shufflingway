package shufflingway;

import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Locks {@link ActionResolver}'s behaviour over the whole card corpus so it can be
 * restructured safely.
 *
 * <p>{@code ActionResolver} dispatches through long ordered if-chains in which position is
 * load-bearing: matchers use {@code find()}, so a general pattern placed ahead of a specific
 * one silently claims text that belongs to the specific one. Only a handful of those ordering
 * constraints are documented in comments, which makes the chains impossible to reorganise by
 * inspection. This test records what the resolver actually decides for every ability on every
 * card, so any behaviour change during a refactor shows up as a diff rather than as a bug
 * discovered later.
 *
 * <p>For each ability it records the parse outcome, the matched pattern name and the full
 * description — the three observable outputs — including thrown exceptions, so current
 * failure behaviour is pinned too.
 *
 * <h2>Two assertions, not one</h2>
 *
 * <p>The three columns are not equally stable, and collapsing them into a single equality check
 * made the test useless during the ongoing work to close the naming gaps: every intended fix
 * produced a diff, so accepting a regeneration meant eyeballing hundreds of lines and hoping.
 * They are therefore checked separately:
 *
 * <ul>
 *   <li><b>Parse outcome is invariant.</b> Naming work only edits {@code matchedPatternName()}
 *       and {@code fullDescription()}, neither of which {@code parse()} consults, so this column
 *       cannot legitimately move. Any change here is a regression and fails on its own terms.</li>
 *   <li><b>Name and description may churn.</b> Diffs are classified as FILLED (null to a value,
 *       what closing a gap looks like), LOST (a value to null, always a regression) or CHANGED
 *       (one value to another, usually an ordering mistake where a new guard stole a name from
 *       an earlier pattern). A regeneration is safe to accept when every diff is FILLED.</li>
 * </ul>
 *
 * <p>Regenerate deliberately, after reviewing the diff, with:
 * <pre>  mvn test -Dtest=ActionResolverCharacterizationTest -Dcharacterization.regenerate=true</pre>
 *
 * <p>The card database is not checked in, so this test skips when it is absent.
 */
public class ActionResolverCharacterizationTest {

	private static final Path GOLDEN =
			Path.of("src", "test", "resources", "actionresolver-characterization.txt");

	/** Written next to the golden file's target dir on mismatch, for diffing. */
	private static final Path ACTUAL =
			Path.of("target", "actionresolver-characterization.actual.txt");

	private static final int MAX_REPORTED_DIFFS = 25;

	private static final String NULL = "(null)";

	@Test
	void resolverBehaviourMatchesGoldenFile() throws Exception {
		List<CardCorpus.Entry> corpus = CardCorpus.load();
		if (corpus.isEmpty()) {
			System.out.println("[characterization] " + CardCorpus.dbFile()
					+ " not found or empty — skipping.");
			return;
		}

		List<String> actual = record(corpus);

		if (Boolean.getBoolean("characterization.regenerate") || !Files.exists(GOLDEN)) {
			Files.createDirectories(GOLDEN.getParent());
			Files.write(GOLDEN, actual, StandardCharsets.UTF_8);
			System.out.printf("[characterization] wrote %s (%d records from %d cards)%n",
					GOLDEN, actual.size() - 1, corpus.size());
			return;
		}

		List<String> expected = Files.readAllLines(GOLDEN, StandardCharsets.UTF_8);
		if (expected.equals(actual)) return;

		Files.createDirectories(ACTUAL.getParent());
		Files.write(ACTUAL, actual, StandardCharsets.UTF_8);

		Map<String, Rec> before = index(expected);
		Map<String, Rec> after = index(actual);

		String parseDiff = describeParseOutcomeDiff(before, after);
		if (parseDiff != null) fail(parseDiff);

		fail(describeNamingDiff(before, after));
	}

	// ---------------------------------------------------------------- recording

	/** One line per ability, plus a leading header so a truncated file is obvious. */
	private static List<String> record(List<CardCorpus.Entry> corpus) {
		List<String> out = new ArrayList<>();
		int abilities = 0;
		List<String> body = new ArrayList<>();

		for (CardCorpus.Entry entry : corpus) {
			CardData card = entry.card();
			String type = card.type();

			List<ActionAbility> actions = card.actionAbilities();
			for (int i = 0; i < actions.size(); i++) {
				final String text = actions.get(i).effectText();
				body.add(line(entry.serial(), "action", i,
						call(() -> ActionResolver.parse(text, card) != null ? "parsed" : "unparsed"),
						call(() -> ActionResolver.matchedPatternName(text, card)),
						call(() -> ActionResolver.fullDescription(text, card))));
				abilities++;
			}

			List<AutoAbility> autos = card.autoAbilities();
			for (int i = 0; i < autos.size(); i++) {
				final AutoAbility auto = autos.get(i);
				final String text = auto.effectText();
				// Recognition, not parseability: some auto abilities are dispatched by
				// AutoAbilityTriggers ahead of its ActionResolver.parse check, and asking parse()
				// alone pinned them as unimplemented while they worked. The name and description
				// columns still come from the resolver, so a self-dispatched ability shows as
				// parsed with no name — which is what it is.
				body.add(line(entry.serial(), "auto", i,
						call(() -> AutoAbilityParsingTest.isAutoAbilityRecognized(auto, card)
								? "parsed" : "unparsed"),
						call(() -> ActionResolver.matchedPatternName(text, card)),
						call(() -> ActionResolver.fullDescription(text, card))));
				abilities++;
			}

			List<FieldAbility> fields = card.fieldAbilities();
			for (int i = 0; i < fields.size(); i++) {
				FieldAbility fa = fields.get(i);
				body.add(line(entry.serial(), "field", i,
						call(() -> FieldAbilityParsingTest.isFieldAbilityRecognized(fa, card, type)
								? "parsed" : "unparsed"),
						"-",
						call(() -> FieldAbilityParsingTest.describeFieldAbility(fa, card, type))));
				abilities++;
			}

			// Summons carry their whole effect in one unnamed block rather than in the ability
			// lists above, so without this row the file covered every card type but this one.
			//
			// Gated on the card type, as SummonParsingTest's query is: CardData.summonEffect()
			// only cleans markup off the card text and does not check what it is cleaning, so on
			// a Forward it happily returns the card's entire text. Recording that would add
			// thousands of rows whose "summon effect" is not one.
			if ("Summon".equalsIgnoreCase(type)) {
				final String summonText = card.summonEffect();
				if (summonText != null && !summonText.isBlank()) {
					body.add(line(entry.serial(), "summon", 0,
							call(() -> ActionResolver.parse(summonText, card) != null ? "parsed" : "unparsed"),
							call(() -> ActionResolver.matchedPatternName(summonText, card)),
							call(() -> ActionResolver.fullDescription(summonText, card))));
					abilities++;
				}
			}
		}

		out.add("# ActionResolver characterization: " + corpus.size()
				+ " cards, " + abilities + " abilities");
		out.addAll(body);
		return out;
	}

	private static String line(String serial, String kind, int idx,
	                           String parsed, String pattern, String desc) {
		return String.join("\t", serial, kind + "#" + idx, parsed, norm(pattern), norm(desc));
	}

	/**
	 * Runs one resolver call, converting a thrown exception into a recorded value so current
	 * failure behaviour is pinned alongside success behaviour.
	 */
	private static String call(ThrowingSupplier body) {
		try {
			String v = body.get();
			return v == null ? NULL : v;
		} catch (Exception | StackOverflowError e) {
			return "!!" + e.getClass().getSimpleName();
		}
	}

	/** Collapses whitespace so every record stays on one tab-separated line. */
	private static String norm(String s) {
		if (s == null) return NULL;
		String v = s.replaceAll("\\s+", " ").trim();
		return v.isEmpty() ? "(empty)" : v;
	}

	// ---------------------------------------------------------------- diffing

	/** One recorded ability, keyed by serial and slot so records match up across a reordering. */
	private record Rec(String key, String parsed, String name, String desc) {}

	private static Map<String, Rec> index(List<String> lines) {
		Map<String, Rec> out = new LinkedHashMap<>();
		for (String l : lines) {
			if (l.startsWith("#")) continue;
			String[] f = l.split("\t", -1);
			if (f.length < 5) continue;
			String key = f[0] + "\t" + f[1];
			out.put(key, new Rec(key, f[2], f[3], f[4]));
		}
		return out;
	}

	/**
	 * Fails if the parse-outcome column moved. Naming work edits only {@code matchedPatternName()}
	 * and {@code fullDescription()}, so this column is invariant under it — a diff here means a
	 * real behaviour change and is reported before any naming churn, which would otherwise bury it.
	 */
	private static String describeParseOutcomeDiff(Map<String, Rec> before, Map<String, Rec> after) {
		List<String> moved = new ArrayList<>();
		for (Rec e : before.values()) {
			Rec a = after.get(e.key());
			if (a == null) moved.add("  " + e.key() + "\tRECORD DISAPPEARED (was " + e.parsed() + ")");
			else if (!e.parsed().equals(a.parsed()))
				moved.add("  " + e.key() + "\t" + e.parsed() + " -> " + a.parsed());
		}
		for (Rec a : after.values()) {
			if (!before.containsKey(a.key()))
				moved.add("  " + a.key() + "\tRECORD APPEARED (" + a.parsed() + ")");
		}
		if (moved.isEmpty()) return null;

		StringBuilder sb = new StringBuilder();
		sb.append("PARSE OUTCOME CHANGED — this is a regression, not a naming fix.\n");
		sb.append("  ").append(moved.size()).append(" record(s) changed parse outcome.\n");
		sb.append("  Only matchedPatternName() and fullDescription() should be edited by naming\n");
		sb.append("  work, and parse() consults neither. Fix this before regenerating ")
		  .append(GOLDEN).append(".\n");
		sb.append("  full output written to ").append(ACTUAL).append('\n');
		moved.stream().limit(MAX_REPORTED_DIFFS).forEach(m -> sb.append(m).append('\n'));
		if (moved.size() > MAX_REPORTED_DIFFS) sb.append("  ... further differences suppressed\n");
		return sb.toString();
	}

	/**
	 * Reports name/description churn, classified so an intended gap-closing pass is
	 * distinguishable at a glance from an ordering mistake.
	 */
	private static String describeNamingDiff(Map<String, Rec> before, Map<String, Rec> after) {
		List<String> filled = new ArrayList<>();
		List<String> lost = new ArrayList<>();
		List<String> changed = new ArrayList<>();

		for (Rec e : before.values()) {
			Rec a = after.get(e.key());
			if (a == null) continue; // already reported by the parse-outcome check
			classify(e.key(), "name", e.name(), a.name(), filled, lost, changed);
			classify(e.key(), "desc", e.desc(), a.desc(), filled, lost, changed);
		}

		StringBuilder sb = new StringBuilder();
		sb.append("ActionResolver naming/description changed against ").append(GOLDEN).append('\n');
		sb.append("  parse outcome unchanged for every record — no behaviour regression.\n");
		sb.append("  FILLED  ").append(filled.size()).append("  (null -> value; closing a gap)\n");
		sb.append("  LOST    ").append(lost.size()).append("  (value -> null; REGRESSION)\n");
		sb.append("  CHANGED ").append(changed.size())
		  .append("  (value -> other value; usually a guard inserted at the wrong position)\n");
		sb.append("  full output written to ").append(ACTUAL).append('\n');
		if (lost.isEmpty() && changed.isEmpty()) {
			sb.append("  Every diff is FILLED — safe to regenerate.\n");
		}
		appendSection(sb, "LOST", lost);
		appendSection(sb, "CHANGED", changed);
		appendSection(sb, "FILLED", filled);
		return sb.toString();
	}

	private static void classify(String key, String column, String was, String now,
	                             List<String> filled, List<String> lost, List<String> changed) {
		if (was.equals(now)) return;
		String entry = "  " + key + "\t" + column + ": " + was + " -> " + now;
		if (NULL.equals(was))      filled.add(entry);
		else if (NULL.equals(now)) lost.add(entry);
		else                       changed.add(entry);
	}

	private static void appendSection(StringBuilder sb, String label, List<String> entries) {
		if (entries.isEmpty()) return;
		sb.append("--- ").append(label).append(" (").append(entries.size()).append(")\n");
		entries.stream().limit(MAX_REPORTED_DIFFS).forEach(e -> sb.append(e).append('\n'));
		if (entries.size() > MAX_REPORTED_DIFFS) sb.append("  ... further entries suppressed\n");
	}

	@FunctionalInterface
	private interface ThrowingSupplier {
		String get() throws Exception;
	}

	/** Convenience for regenerating outside Maven; see the class javadoc for the usual route. */
	public static void main(String[] args) throws IOException, Exception {
		System.setProperty("characterization.regenerate", "true");
		new ActionResolverCharacterizationTest().resolverBehaviourMatchesGoldenFile();
	}
}
