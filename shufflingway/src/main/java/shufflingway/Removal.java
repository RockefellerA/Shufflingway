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
