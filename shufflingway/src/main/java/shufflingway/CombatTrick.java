package shufflingway;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a combat trick does to a Forward of its controller's, read off an effect's text: power until
 * the end of the turn, First Strike, a shield against the damage that would break it
 * (HANDOFF-cpu-opponents-turn decision 5). The CPU plays one on P1's turn to win a battle.
 *
 * <p>Read anchored end to end, and only from the wordings below, so a text this does not wholly
 * understand is no trick: a trick the CPU misreads could be paid for and do nothing.
 *
 * @param subject     the choice that picks its Forward ("choose 1 Forward you control."), read with
 *                    {@link ActionResolver#targetSpec}; {@code null} for one that reaches every Forward
 *                    its controller has
 * @param power       power gained until the end of the turn
 * @param firstStrike whether it grants First Strike
 * @param shield      whether it keeps the Forward from being broken — "the next damage dealt to it
 *                    becomes 0", "it cannot be broken this turn"
 * @param dulls       whether it dulls the Forward too (1-110C Titan), which no Forward that has yet to
 *                    block can afford
 * @param unbreakable whether the shield is "it cannot be broken", which stops a break effect as well as
 *                    lethal damage — "the next damage becomes 0" stops only the damage
 */
record CombatTrick(String subject, int power, boolean firstStrike, boolean shield, boolean dulls, boolean unbreakable) {

	/** A trick that is no "cannot be broken" shield. */
	CombatTrick(String subject, int power, boolean firstStrike, boolean shield, boolean dulls) {
		this(subject, power, firstStrike, shield, dulls, false);
	}

	/** A leading EX BURST tag, which changes nothing about what the effect does. */
	private static final Pattern EX_BURST = Pattern.compile("(?i)^(?:\\[\\[ex\\]\\])?\\s*EX\\s+BURST\\s*(?:\\[\\[/\\]\\])?\\s*");

	/** Sentences a trick may end with that cost nothing: "Draw 1 card.", "If …, draw 1 card." */
	private static final Pattern HARMLESS_TAIL = Pattern.compile(
			"(?i)^(?:\\s*(?:If\\s+[^,.]+,\\s+)?draw\\s+\\d+\\s+cards?\\.)*\\s*$");

	/** "choose 1 [filters] Forward [you control / other than X you control]." — the Forward the trick lands on. */
	private static final String OWN_CHOICE =
			"(?<subject>choose\\s+1\\s+(?:(?!opponent\\b)[^.])*?\\bForward\\b(?:(?!opponent\\b)[^.])*?)\\.\\s+";

	/** "… it gains +N power[, Haste] and First Strike until the end of the turn." in either order of clauses. */
	private static final Pattern CHOSEN_GAINS = Pattern.compile("(?is)^" + OWN_CHOICE
			+ "(?:until\\s+the\\s+end\\s+of\\s+the\\s+turn,\\s+it\\s+gains\\s+(?<g1>[^.\"]+?)"
			+ "|it\\s+gains\\s+(?<g2>[^.\"]+?)\\s+until\\s+the\\s+end\\s+of\\s+the\\s+turn)\\s*\\.(?<rest>.*)$");

	/** "All the Forwards you control gain +N power until the end of the turn." (14-060R Carbuncle) */
	private static final Pattern ALL_OWN_GAIN = Pattern.compile("(?is)^all\\s+the\\s+Forwards\\s+you\\s+control\\s+"
			+ "gain\\s+(?<g>[^.\"]+?)\\s+until\\s+the\\s+end\\s+of\\s+the\\s+turn\\s*\\.(?<rest>.*)$");

	/** "… During this turn, the next damage dealt to it becomes 0 instead." (24-099C Siren) */
	private static final Pattern CHOSEN_NEXT_DAMAGE_ZERO = Pattern.compile("(?is)^" + OWN_CHOICE
			+ "(?:during\\s+this\\s+turn,\\s+)?the\\s+next\\s+damage\\s+dealt\\s+to\\s+it\\s+becomes\\s+0\\s+instead\\s*\\.(?<rest>.*)$");

	/** "Choose 1 Forward you control. Dull it. It cannot be broken this turn." (1-110C Titan) */
	private static final Pattern CHOSEN_DULL_UNBREAKABLE = Pattern.compile("(?is)^" + OWN_CHOICE
			+ "(?<dull>dull\\s+it\\.\\s+)?it\\s+cannot\\s+be\\s+broken\\s+this\\s+turn\\s*\\.(?<rest>.*)$");

	private static final Pattern PLUS_POWER = Pattern.compile("(?i)\\+(\\d+)\\s+power");

	/** The trick {@code effectText} plays, or {@code null} when it is none this reads. */
	static CombatTrick of(String effectText) {
		if (effectText == null) return null;
		String text = EX_BURST.matcher(effectText.trim()).replaceFirst("").trim();
		Matcher m = CHOSEN_GAINS.matcher(text);
		if (m.matches() && harmless(m.group("rest"))) {
			CombatTrick t = gains(m.group("subject"), m.group("g1") != null ? m.group("g1") : m.group("g2"));
			if (t != null) return t;
		}
		m = ALL_OWN_GAIN.matcher(text);
		if (m.matches() && harmless(m.group("rest"))) {
			CombatTrick t = gains(null, m.group("g"));
			if (t != null) return t;
		}
		m = CHOSEN_NEXT_DAMAGE_ZERO.matcher(text);
		if (m.matches() && harmless(m.group("rest"))) return new CombatTrick(subject(m), 0, false, true, false);
		m = CHOSEN_DULL_UNBREAKABLE.matcher(text);
		if (m.matches() && harmless(m.group("rest")))
			return new CombatTrick(subject(m), 0, false, true, m.group("dull") != null, true);
		return null;
	}

	/** What a "gains …" clause grants; {@code null} when it grants nothing a battle turns on. */
	private static CombatTrick gains(String subject, String granted) {
		Matcher p = PLUS_POWER.matcher(granted);
		int power = p.find() ? Integer.parseInt(p.group(1)) : 0;
		boolean firstStrike = granted.toLowerCase(Locale.ROOT).contains("first strike");
		if (power <= 0 && !firstStrike) return null;
		return new CombatTrick(subject == null ? null : subject.trim() + ".", power, firstStrike, false, false);
	}

	private static String subject(Matcher m) {
		return m.group("subject").trim() + ".";
	}

	private static boolean harmless(String rest) {
		return rest == null || HARMLESS_TAIL.matcher(rest).matches();
	}
}
