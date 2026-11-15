package shufflingway;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a removal does to one chosen opposing Forward, read off an effect's text: damage, a break, or
 * power lost until the end of the turn (HANDOFF-cpu-opponents-turn decision 6). The CPU plays one on
 * P1's turn when it breaks a Forward outright.
 *
 * <p>Read anchored end to end, from the wordings below alone, as {@link CombatTrick} is: a removal
 * misread could be paid for and break nothing.
 *
 * @param kind   what it does to the Forward
 * @param amount the damage dealt or power lost; 0 for a break
 */
record Removal(Kind kind, int amount) {

	enum Kind { DAMAGE, BREAK, POWER_LOSS }

	private static final Pattern EX_BURST = Pattern.compile("(?i)^(?:\\[\\[ex\\]\\])?\\s*EX\\s+BURST\\s*(?:\\[\\[/\\]\\])?\\s*");

	/** Sentences a removal may end with that cost nothing: "Draw 1 card.", "If …, draw 1 card." */
	private static final Pattern HARMLESS_TAIL = Pattern.compile(
			"(?i)^(?:\\s*(?:If\\s+[^,.]+,\\s+)?draw\\s+\\d+\\s+cards?\\.)*\\s*$");

	/** "choose 1 [filters] Forward / Character [opponent controls]." — never one of its controller's own. */
	private static final String CHOICE =
			"choose\\s+1\\s+(?:(?!you\\s+control)[^.])*?\\b(?:Forward|Character)\\b(?:(?!you\\s+control)[^.])*?\\.\\s+";

	private static final Pattern DAMAGE = Pattern.compile("(?is)^" + CHOICE
			+ "deal\\s+it\\s+(?<n>\\d+)\\s+damage\\s*\\.(?<rest>.*)$");

	private static final Pattern BREAK = Pattern.compile("(?is)^" + CHOICE + "break\\s+it\\s*\\.(?<rest>.*)$");

	private static final Pattern POWER_LOSS = Pattern.compile("(?is)^" + CHOICE
			+ "(?:until\\s+the\\s+end\\s+of\\s+the\\s+turn,\\s+it\\s+loses\\s+(?<n1>\\d+)\\s+power"
			+ "|it\\s+loses\\s+(?<n2>\\d+)\\s+power\\s+until\\s+the\\s+end\\s+of\\s+the\\s+turn)\\s*\\.(?<rest>.*)$");

	/** "… It cannot block this turn." / "… Dull it[ and Freeze it]." — a chosen Forward kept from blocking. */
	private static final Pattern DENIES_BLOCK = Pattern.compile("(?is)^" + CHOICE
			+ "(?:it\\s+cannot\\s+block\\s+this\\s+turn|dull\\s+it(?:\\s+and\\s+freeze\\s+it)?)\\s*\\.(?<rest>.*)$");

	/** "Deal N damage to the blocking Forward." / "Deal the blocking Forward N damage." / "… to the Forward that blocks X." */
	private static final Pattern DAMAGES_BLOCKER = Pattern.compile("(?is)^deal\\s+(?:(?<n1>\\d+)\\s+damage\\s+to\\s+"
			+ "(?:the\\s+blocking\\s+Forward|the\\s+Forward\\s+that\\s+blocks\\s+[^.]+?)|the\\s+blocking\\s+Forward\\s+(?<n2>\\d+)\\s+damage)"
			+ "\\s*\\.(?<rest>.*)$");

	private static final Pattern BREAKS_BLOCKER = Pattern.compile("(?is)^break\\s+the\\s+blocking\\s+Forward\\s*\\.(?<rest>.*)$");

	/**
	 * Whether {@code effectText} keeps one chosen Forward from blocking — "it cannot block this turn",
	 * or dulled — read as {@link #of} reads a removal. Not a removal: once a block is made, dulling the
	 * blocker does not take it out of the battle, so this is asked only before blocks are declared.
	 */
	static boolean deniesBlock(String effectText) {
		if (effectText == null) return false;
		Matcher m = DENIES_BLOCK.matcher(EX_BURST.matcher(effectText.trim()).replaceFirst("").trim());
		return m.matches() && harmless(m.group("rest"));
	}

	/**
	 * The removal {@code effectText} deals the Forward blocking its source, with nothing chosen — the
	 * "When … is blocked" wordings (2-013C, 15-096C Noel, 6-088L Estinien) — or {@code null}.
	 */
	static Removal ofBlocker(String effectText) {
		if (effectText == null) return null;
		String text = EX_BURST.matcher(effectText.trim()).replaceFirst("").trim();
		Matcher m = DAMAGES_BLOCKER.matcher(text);
		if (m.matches() && harmless(m.group("rest")))
			return new Removal(Kind.DAMAGE, Integer.parseInt(m.group("n1") != null ? m.group("n1") : m.group("n2")));
		m = BREAKS_BLOCKER.matcher(text);
		if (m.matches() && harmless(m.group("rest"))) return new Removal(Kind.BREAK, 0);
		return null;
	}

	/** The removal {@code effectText} is, or {@code null} when it is none this reads. */
	static Removal of(String effectText) {
		if (effectText == null) return null;
		String text = EX_BURST.matcher(effectText.trim()).replaceFirst("").trim();
		Matcher m = DAMAGE.matcher(text);
		if (m.matches() && harmless(m.group("rest"))) return new Removal(Kind.DAMAGE, Integer.parseInt(m.group("n")));
		m = BREAK.matcher(text);
		if (m.matches() && harmless(m.group("rest"))) return new Removal(Kind.BREAK, 0);
		m = POWER_LOSS.matcher(text);
		if (m.matches() && harmless(m.group("rest")))
			return new Removal(Kind.POWER_LOSS, Integer.parseInt(m.group("n1") != null ? m.group("n1") : m.group("n2")));
		return null;
	}

	private static boolean harmless(String rest) {
		return rest == null || HARMLESS_TAIL.matcher(rest).matches();
	}
}
