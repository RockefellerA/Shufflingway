package shufflingway;

import java.util.ArrayList;
import java.util.List;

/**
 * Prints what the resolver currently makes of named cards — one line per ability giving the parse
 * outcome, the matched pattern name and the full description.
 *
 * <pre>
 *   mvn -q test-compile
 *   mvn exec:exec -Dexec.classpathScope=test -Dexec.executable=java \
 *       -Dexec.args="-cp %classpath shufflingway.CardProbe 17-084C 11-128H 16-002H"
 *   ... -Dexec.args="-cp %classpath shufflingway.CardProbe --name Gnash"
 *   ... -Dexec.args="-cp %classpath shufflingway.CardProbe --text 17-084C Lorenzo will not activate ..."
 * </pre>
 *
 * <p>{@code exec:exec}, not {@code exec:java}. The in-process form was tried and abandoned: it
 * printed nothing and had not returned after two minutes. The cause was not chased down — forking
 * is what makes {@code %classpath} available anyway, and that expansion is the whole reason to go
 * through Maven rather than assembling the test classpath by hand.
 *
 * <p><b>Why this exists.</b> Wiring a card starts with one question — does this text parse today,
 * and if so which parser claimed it? Answering it by reading the dispatch chains means opening
 * {@code ActionResolver}, {@code ActionResolverPatterns} and whichever module owns the parser, and
 * then still guessing at {@code find()} precedence. This answers it in three lines of output, and
 * it answers with what the code actually does rather than what the chains look like they do.
 *
 * <p>It is also the fastest way to see the blast radius of a widened pattern: name every card that
 * shares the wording, run it before and after, and diff. That is a cheaper loop than the
 * characterization test for a handful of cards, and the two agree — the golden file is the same
 * three columns over the whole corpus.
 *
 * <p><b>Not a test.</b> It asserts nothing and JUnit never picks it up (no {@code Test} suffix, no
 * annotations); it lives under {@code src/test/java} only because {@link CardCorpus}, which loads
 * the card database, does. The database is gitignored, so with no {@code shufflingway.db} present
 * this prints nothing and exits — the same way the characterization test skips.
 *
 * <p>The {@code --text} form takes a serial and an arbitrary sentence, and parses that sentence
 * against that card as the source. Use it for a wording the card does not print — a variant to
 * check a pattern against, or one half of a compound the chains would otherwise split.
 */
public final class CardProbe {

	private CardProbe() {}

	public static void main(String[] args) throws Exception {
		if (args.length == 0) {
			System.out.println("usage: CardProbe <serial>... | --name <cardName> | --text <serial> <sentence>");
			return;
		}
		List<CardCorpus.Entry> corpus = CardCorpus.load();
		if (corpus.isEmpty()) {
			System.out.println("no shufflingway.db beside the pom — nothing to probe");
			return;
		}

		if ("--text".equals(args[0])) {
			if (args.length < 3) { System.out.println("--text needs a serial and a sentence"); return; }
			CardData source = find(corpus, args[1]);
			if (source == null) { System.out.println("no card with serial " + args[1]); return; }
			System.out.println("### " + args[1] + "  " + source.name());
			// Everything after the serial, so an unquoted sentence still arrives whole.
			show("TEXT", String.join(" ", List.of(args).subList(2, args.length)), source);
			return;
		}

		boolean byName = "--name".equals(args[0]);
		List<String> wanted = new ArrayList<>(List.of(args).subList(byName ? 1 : 0, args.length));
		for (CardCorpus.Entry e : corpus) {
			boolean hit = byName
					? wanted.stream().anyMatch(w -> w.equalsIgnoreCase(e.card().name()))
					: wanted.contains(e.serial());
			if (!hit) continue;
			CardData c = e.card();
			System.out.println("### " + e.serial() + "  " + c.name()
					+ "  [" + c.type() + " " + c.element() + " cost " + c.cost() + "]");
			for (AutoAbility a : c.autoAbilities())     show("AUTO(" + a.trigger() + ")", a.effectText(), c);
			for (ActionAbility a : c.actionAbilities()) show("ACTION", a.effectText(), c);
			for (FieldAbility a : c.fieldAbilities())   show("FIELD", a.effectText(), c);
		}
	}

	private static CardData find(List<CardCorpus.Entry> corpus, String serial) {
		for (CardCorpus.Entry e : corpus) if (e.serial().equals(serial)) return e.card();
		return null;
	}

	/**
	 * The three columns for one ability text.
	 *
	 * <p>All three are printed even though the first implies the others are reachable: a text that
	 * parses but reports no name is the single most common state a half-wired card is in, and
	 * seeing "parsed / null / null" on one line is what names it as that rather than as working.
	 */
	private static void show(String kind, String text, CardData source) {
		System.out.println("  " + kind + " | " + text);
		System.out.println("    parse=" + (ActionResolver.parse(text, source) != null)
				+ "  name=" + ActionResolver.matchedPatternName(text, source)
				+ "  desc=" + ActionResolver.fullDescription(text, source));
	}
}
