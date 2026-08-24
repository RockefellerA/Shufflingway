package shufflingway;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.Image;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;
import javax.swing.SwingWorker;
import javax.swing.Timer;

import shufflingway.graphics.CardSlideAnimator;
import static shufflingway.graphics.CardAnimation.CARD_H;
import static shufflingway.graphics.CardAnimation.CARD_W;
import static shufflingway.CardFilters.matchesDiscardType;
import static shufflingway.CardFilters.meetsCardNameFilter;
import static shufflingway.CardFilters.discardTypeKey;
import static shufflingway.CardFilters.meetsCategoryFilter;
import static shufflingway.CardFilters.meetsDiscardCost;
import static shufflingway.CpPaymentUtils.contributingElement;
import static shufflingway.CpPaymentUtils.matchesAnyElement;
import shufflingway.dialog.AbilityPaymentDialog;

/**
 * Auto-ability trigger dispatch and resolution. Extracted from MainWindow to keep that
 * file under the JDT memory threshold. Holds a back-pointer to MainWindow for state access;
 * accessed MainWindow members are package-private rather than private.
 */
final class AutoAbilityTriggers {

	private final MainWindow mw;

	AutoAbilityTriggers(MainWindow mw) {
		this.mw = mw;
	}

	// -------------------------------------------------------------------------
	// Simultaneous-trigger batching
	//
	// When a single game event (e.g. a card entering the field) causes several
	// auto-abilities to trigger at once, the active player should be allowed to
	// pick the order they go on the stack. We achieve this by capturing
	// {@link #executeAutoAbility} calls into a batch while {@code pendingBatch}
	// is non-null, then dispatching them through an ordering dialog before
	// running them via {@link #executeAutoAbilityImpl}.
	// -------------------------------------------------------------------------

	private List<StackOrderingDialog.Item> pendingBatch;

	/**
	 * Runs {@code collector} with batching enabled, then dispatches any
	 * abilities it collected through the stack-ordering UI (or CPU defaults).
	 * Re-entrant calls join the outer batch.
	 */
	private void withBatch(Runnable collector) {
		if (pendingBatch != null) { collector.run(); return; }
		pendingBatch = new ArrayList<>();
		try {
			collector.run();
			List<StackOrderingDialog.Item> batch = pendingBatch;
			pendingBatch = null;
			dispatchSimultaneous(batch);
		} finally {
			pendingBatch = null;
		}
	}

	/**
	 * Splits the batch by controller relative to the active player, prompts the
	 * controlling player to order each side (only when human and size &gt;= 2),
	 * then executes each ability in the chosen order.
	 */
	private void dispatchSimultaneous(List<StackOrderingDialog.Item> batch) {
		if (batch.isEmpty()) return;
		boolean apIsP1 = mw.gameState.getCurrentPlayer() == GameState.Player.P1;

		List<StackOrderingDialog.Item> apItems  = new ArrayList<>();
		List<StackOrderingDialog.Item> napItems = new ArrayList<>();
		for (StackOrderingDialog.Item it : batch) {
			if (it.controllerIsP1() == apIsP1) apItems.add(it);
			else                                napItems.add(it);
		}

		// AP pushes first (resolves last), NAP pushes second (resolves first).
		runOrdered(apItems,  apIsP1,  "Active Player");
		runOrdered(napItems, !apIsP1, "Non-Active Player");
	}

	private void runOrdered(List<StackOrderingDialog.Item> items, boolean controllerIsP1, String role) {
		if (items.isEmpty()) return;
		// CPU controls P2 — only show the dialog when P1 is choosing.
		if (controllerIsP1 && items.size() >= 2) {
			// Dialog returns resolution order: index 0 = top of stack (resolves first).
			// Push in reverse so the first-resolving ability lands on top of the stack.
			List<StackOrderingDialog.Item> ordered = StackOrderingDialog.show(mw.frame,
					"Choose Stack Order — " + role + " (" + (controllerIsP1 ? "P1" : "P2") + ")",
					items);
			for (int i = ordered.size() - 1; i >= 0; i--) {
				StackOrderingDialog.Item it = ordered.get(i);
				executeAutoAbilityImpl(it.ability(), it.source(), it.controllerIsP1(), it.paidExtraCost(),
						it.triggerCard());
			}
		} else {
			// No dialog: preserve historical iteration order (first walked = pushed
			// first = bottom of stack = resolves last).
			for (StackOrderingDialog.Item it : items) {
				executeAutoAbilityImpl(it.ability(), it.source(), it.controllerIsP1(), it.paidExtraCost(),
						it.triggerCard());
			}
		}
	}


	/**
	 * Matches "remove N [Name] Counter(s) from [CardName][.] When/If you do so, sub-effect".
	 * Used for auto-ability costs that consume a named counter before resolving an effect.
	 */
	private static final Pattern FA_REMOVE_COUNTER_WHEN_DO_SO =
			Pattern.compile(
				"(?i)^remove\\s+(?<n>\\d+)\\s+(?<counterName>.+?)\\s+Counters?\\s+from" +
				"\\s+(?<target>.+?)[.,!]\\s+(?:When|If)\\s+you\\s+do\\s+so[,.]?\\s+(?<sub>.+?)$",
				Pattern.DOTALL
			);

	/**
	 * Matches "remove N [type] [without 《Keyword》] [you control / opponent controls]
	 * from the game. When/If you do so, sub-effect."
	 * <ul>
	 *   <li>{@code count}     — number of cards to remove</li>
	 *   <li>{@code targets}   — card type: Backup, Forward, Monster, or Character</li>
	 *   <li>{@code excludekw} — optional keyword exclusion (e.g. "Multicard") from "without 《Keyword》"</li>
	 *   <li>{@code control}   — "you control" or "opponent controls"</li>
	 *   <li>{@code sub}       — effect to execute after the removal succeeds</li>
	 * </ul>
	 */
	private static final Pattern FA_REMOVE_FIELD_WHEN_DO_SO =
			Pattern.compile(
				"(?i)^remove\\s+(?<count>\\d+)\\s+" +
				"(?<targets>Backups?|Forwards?|Monsters?|Characters?)\\s+" +
				"(?:without\\s+《(?<excludekw>[^》]+)》\\s+)?" +
				"(?<control>(?:your\\s+)?opponent\\s+controls|you\\s+control)\\s+" +
				"from\\s+the\\s+game[.,]?\\s+" +
				"(?:When|If)\\s+you\\s+do\\s+so[,.]?\\s+" +
				"(?<sub>.+?)$",
				Pattern.DOTALL
			);

	/**
	 * Matches "put N [Job jobname / Card Name name / [Element] type] you control into the Break Zone.
	 * When/If you do so, sub-effect."
	 *
	 * <p>The element qualifier is optional (Vincent: "put 1 Fire Backup you control into the Break
	 * Zone"). It has to be part of this pattern rather than left to a later one: an unmatched
	 * qualifier here falls through to {@link #FA_PUT_SELF_INTO_BZ_IF_DO_SO}, whose {@code .+?}
	 * card-name group swallows the whole phrase and then rejects it for not naming the source.
	 */
	static final Pattern FA_PUT_INTO_BZ_WHEN_DO_SO =
			Pattern.compile(
				"(?i)^put\\s+(?<count>\\d+)\\s+" +
				"(?:" +
					"Job\\s+(?<job>.+?)\\s+you\\s+control" +
				"|" +
					"Card\\s+Name\\s+(?<cardname>\\S+(?:\\s+\\([^)]+\\))?)\\s+you\\s+control" +
				"|" +
					"(?:(?<element>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+)?" +
					"(?<type>Forwards?|Backups?|Monsters?|Characters?)\\s+you\\s+control" +
				")" +
				"\\s+into\\s+the\\s+Break\\s+Zone[.,]?\\s+" +
				"(?:When|If)\\s+you\\s+do\\s+so[,.]?\\s+" +
				"(?<sub>.+?)$",
				Pattern.DOTALL
			);

	/**
	 * Matches "put [CardName] into the Break Zone. If/When you do so, [sub-effect]"
	 * where [CardName] is the source card itself (self-break with conditional follow-up).
	 * Distinct from {@link #FA_PUT_INTO_BZ_WHEN_DO_SO} which requires a numeric count and "you control".
	 */
	static final Pattern FA_PUT_SELF_INTO_BZ_IF_DO_SO = Pattern.compile(
			"(?i)^put\\s+(?<cardname>.+?)\\s+into\\s+the\\s+Break\\s+Zone[.,]?\\s+" +
			"(?:When|If)\\s+you\\s+do\\s+so[,.]?\\s+(?<sub>.+?)$",
			Pattern.DOTALL
	);

	/**
	 * Matches a card's own passive field ability text:
	 * "If &lt;cardName&gt; is dealt damage by your opponent's Summons, the damage becomes 0 instead."
	 * Checked inline in {@link #modifyIncomingDamage} against the receiving card's field abilities.
	 */
	static final Pattern FA_NULLIFY_SUMMON_DAMAGE =
			Pattern.compile(
				"(?i)If\\s+(?<card>.+?)\\s+is\\s+dealt\\s+damage\\s+by\\s+your\\s+opponent's\\s+Summons?,\\s+the\\s+damage\\s+becomes\\s+0\\s+instead\\.?"
			);

	// "If <cardName> is dealt damage by abilities, reduce the damage by N instead." had its own
	// pattern (FA_REDUCE_ABILITY_DAMAGE) and its own block in modifyIncomingDamage. Removed: the
	// text is a strict subset of FA_DAMAGE_MODIFIER, whose "by abilities" source clause resolves to
	// the identical gate, so the two both fired and the reduction was applied twice. The surviving
	// copy also sits on the correct side of the "cannot be reduced" guard, which the old block did
	// not — see DamageResolver.modifyIncomingDamage.

	/** "If [name] is dealt damage by an ability, the damage becomes 0 instead." — persistent passive nullification vs non-Summon abilities. */
	static final Pattern FA_NULLIFY_ABILITY_DAMAGE =
			Pattern.compile(
				"(?i)If\\s+(?<card>.+?)\\s+is\\s+dealt\\s+damage\\s+by\\s+an?\\s+abilit(?:y|ies),\\s+the\\s+damage\\s+becomes\\s+0\\s+instead\\.?"
			);

	/** "If [name] is dealt damage by your opponent's abilities, the damage becomes 0 instead." — nullifies non-Summon ability damage whose source is on the opposing side. */
	static final Pattern FA_NULLIFY_OPPONENT_ABILITY_DAMAGE =
			Pattern.compile(
				"(?i)If\\s+(?<card>.+?)\\s+is\\s+dealt\\s+damage\\s+by\\s+your\\s+opponent's\\s+abilit(?:y|ies),\\s+the\\s+damage\\s+becomes\\s+0\\s+instead\\.?"
			);

	/**
	 * "The damage dealt by your abilities to Forwards opponent controls cannot be reduced." —
	 * Adelard 17-001H.
	 *
	 * <p>A field-wide, permanent version of what "This damage cannot be reduced." does for a single
	 * damage sentence, so {@link DamageResolver#modifyIncomingDamage} routes it into the same
	 * {@code unreduced} path rather than adding a second notion of unreducible damage.
	 *
	 * <p>"your abilities" excludes Summons. The corpus writes "Summons or abilities" when it means
	 * both, and Adelard's own sibling ability draws the same line ("if your ability deals damage to a
	 * Forward, double the damage instead"); the engine already reads a bare "ability" that way for
	 * {@code nullifyAbilityOnlyDmgSet}. Cu Chaspel 11-004C prints the turn-scoped, source-agnostic
	 * relative of this and routes through {@code disableOpponentDamageReduction} instead.
	 */
	static final Pattern FA_ABILITY_DAMAGE_TO_OPP_FORWARDS_UNREDUCIBLE = Pattern.compile(
		"(?i)^The\\s+damage\\s+dealt\\s+by\\s+your\\s+abilit(?:y|ies)\\s+to\\s+Forwards?\\s+" +
		"(?:your\\s+)?opponent\\s+controls?\\s+cannot\\s+be\\s+reduced[.!]?$"
	);

	/**
	 * General incoming-damage modifier field ability.
	 * Covers "reduce the damage by N", "the damage becomes N", and "the damage increases by N" variants,
	 * with optional source clauses: "by a Forward", "by a Character", "by [your opponent's] Summons
	 * [or abilities]", "by a Summon or an ability", "by [an] abilit[y|ies]", "other than battle
	 * damage", or no clause (any source).
	 * A leading "During your turn," / "During your opponent's turn," (Garland 3-004H), or the same
 * window spelled after "receives damage" (Cagnazzo 3-130R), restricts the modifier to one
 * player's turns; whichever position it is printed in, it lands in {@code turnpre} or
 * {@code turnpost} and is read against the carrier's own controller.
 * Also accepts "receives damage" as a synonym for "is dealt damage", and an optional threshold:
	 * "is dealt N damage or more" / "or less" (captured in {@code threshold}, with the direction in
	 * {@code threshcmp}) to apply the modifier only when the damage is on that side of N. Both
	 * comparisons are inclusive of N — Baigan 9-072H zeroes exactly 3000 as well as less.
	 * Groups: {@code card}, {@code threshold} (optional), {@code threshcmp} (present iff
	 * {@code threshold} is), {@code sourceclause} (optional), {@code reduceby} (optional),
	 * {@code setsto} (optional), {@code increaseby} (optional), {@code half} (optional).
	 *
	 * <p>{@code half} is Rosso 2-024R's "reduce the damage by half instead (numbers are rounded up to
	 * units of 1000)" — the one arm whose result is a function of the incoming amount rather than a
	 * printed number, which is why it carries no digits to capture.
	 *
	 * <p>The source clauses accept "from" as well as "by". Two printings word it that way and mean
	 * no different — Mystic Knight 3-048C ("receives damage from Summons or abilities") and the
	 * ability Behemoth 4-111H grants itself ("receives damage from a Forward"); every other printing
	 * says "by", which is why the alternative went unnoticed.
	 */
	static final Pattern FA_DAMAGE_MODIFIER = Pattern.compile(
		"(?i)^(?:During\\s+(?<turnpre>your\\s+opponent's|your)\\s+turn,\\s+)?" +
		"If\\s+(?<card>.+?)\\s+(?:is\\s+dealt|receives)\\s+(?:(?<threshold>\\d+)\\s+damage\\s+or\\s+(?<threshcmp>more|less)|damage)" +
		"(?<sourceclause>" +
			// Must precede the bare "by a Forward" branch, which names the source of battle damage.
			// This one names the source of an *ability's* damage (Gawain 7-107R) — the narrower
			// reading, and the opposite answer: one applies only to battle damage, the other only
			// to ability damage.
			"\\s+(?:by|from)\\s+a\\s+Forward(?:'s|s')\\s+abilit(?:y|ies)" +
			"|\\s+(?:by|from)\\s+a\\s+Forward" +
			// Ahead of the Summon and ability branches, which would otherwise never see it —
			// they are the narrower readings and "Character" names the source, not the effect.
			"|\\s+(?:by|from)\\s+a\\s+Character" +
			"|\\s+other\\s+than\\s+battle\\s+damage" +
			"|\\s+(?:by|from)\\s+(?:your\\s+opponent's\\s+)?(?:a\\s+)?Summons?(?:\\s+or\\s+(?:an?\\s+)?abilit(?:y|ies))?" +
			// Must precede the bare ability branch below, which stops at "abilities" and would
			// leave "other than special abilities" stranded against the comma this pattern
			// requires next — the whole sentence then fails to match rather than matching wrong.
			// Ghis 2-126R is the only printing that draws the line between the two kinds.
			"|\\s+(?:by|from)\\s+(?:your\\s+opponent's\\s+)?(?:an?\\s+)?abilit(?:y|ies)\\s+" +
			"other\\s+than\\s+special\\s+abilit(?:y|ies)" +
			"|\\s+(?:by|from)\\s+(?:your\\s+opponent's\\s+)?(?:a\\s+Summon\\s+or\\s+)?(?:an?\\s+)?abilit(?:y|ies)" +
			// The subject's own power, named either by pronoun or by repeating the card's name
			// (The Fiend 20-114L, Ifrit (XVI) 26-003R). Comma-free so the possessive branch cannot
			// reach past the clause into the effect half of the sentence.
			"|\\s+less\\s+than\\s+(?:his|her|its|[^,]+?'s)\\s+power" +
		")?" +
		// The window the shield is open in, when the printing states one at the far end of the
		// sentence instead of at the front (Cagnazzo 3-130R). Its own group rather than an arm of
		// the source clause above: that chain reads what *dealt* the damage, and its catch-all
		// would take a turn phrase for an ability source and answer the wrong question.
		"(?:\\s+during\\s+(?<turnpost>your\\s+opponent's|your)\\s+turn)?" +
		"\\s*,\\s+" +
		// Optional cost the replacement pays for itself: "remove 1 Barrier Counter from Number 24 and
		// the damage becomes 0 instead." (Number 24 20-036H, via its own self-named counter grant).
		// The removal is part of the replacement, not a separate effect — it happens only on the
		// resolutions this modifier actually claims, which is what makes one counter buy one shield.
		"(?:remove\\s+(?<rmcount>\\d+)\\s+(?<rmcounter>.+?)\\s+Counters?\\s+from\\s+(?<rmfrom>.+?)\\s+and\\s+)?" +
		// The halving arm precedes the numeric reduction it shares a prefix with, so "by half" is not
		// offered to a branch that can only read digits.
		"(?:(?<half>reduce\\s+the\\s+damage\\s+by\\s+half)|reduce\\s+the\\s+damage\\s+by\\s+(?<reduceby>\\d+)|the\\s+damage\\s+becomes\\s+(?<setsto>\\d+)|the\\s+damage\\s+increases\\s+by\\s+(?<increaseby>\\d+)|(?<double>double\\s+the\\s+damage))" +
		// A trailing parenthetical restating the rounding rule ("numbers are rounded up to units of
		// 1000" — Rosso 2-024R). Text, not a term: the rounding it describes is what the half arm
		// already does, so it is matched and discarded rather than captured.
		"\\s+instead(?:\\s*\\([^)]*\\))?[.!]?$"
	);

	/**
	 * "Auto-abilities, action abilities and special abilities of your Job [X] cannot be
	 * cancelled." — Yoran-Oran 29-075H.
	 *
	 * <p>The three kinds it lists are every kind of ability there is, so what the sentence
	 * actually draws is the line between abilities and Summons: a Summon its controller casts is
	 * not protected however the Job filter reads. Read per cancellation attempt by
	 * {@code MainWindow.stackEntryProtectedFromCancel}, off the entry's controller's field,
	 * because "your" in a card's own text is its controller.
	 * Group: {@code job}.
	 */
	static final Pattern FA_JOB_ABILITIES_CANNOT_BE_CANCELLED = Pattern.compile(
		"(?i)^Auto-abilities,\\s+action\\s+abilities\\s+and\\s+special\\s+abilities\\s+of\\s+your\\s+" +
		"Job\\s+(?<job>.+?)\\s+cannot\\s+be\\s+cancelled[.!]?$"
	);

	/**
	 * Outgoing damage doubler on the dealing card:
	 * "If [card] deals damage to a Forward or your opponent, double the damage instead."
	 * Checked against the DEALING card's field abilities (combat via {@code fieldAbilityCombatOutgoingMult},
	 * ability via {@code modifyIncomingDamage}/{@code dealDamageToOpponent}).
	 *
	 * <p>"a player" is Ardyn 28-002R's wording and is the widest of the three: his other ability
	 * damages whichever player failed to pay it, so the doubler is written to cover either side
	 * rather than the opponent alone. Readers that ask about damage to a player therefore test for
	 * "opponent" <em>or</em> "player" — matching on "opponent" alone silently dropped this printing.
	 * Groups: {@code card}, {@code target} (contains "Forward", "opponent" and/or "player").
	 */
	static final Pattern FA_OUTGOING_DAMAGE_DOUBLER = Pattern.compile(
		"(?i)^If\\s+(?<card>.+?)\\s+deals\\s+damage\\s+to\\s+" +
		"(?<target>a\\s+Forward(?:\\s+or\\s+your\\s+opponent)?|your\\s+opponent|a\\s+player)" +
		// "instead" is optional: every printing of this doubler carries it except the one Terra
		// 1-047R grants itself ("… double the damage"), which is the only corpus text of this
		// shape without it. Requiring it left that grant matching nothing at all.
		",\\s+double\\s+the\\s+damage(?:\\s+instead)?\\.?$"
	);

	/**
	 * Outgoing damage replacement on the dealing card:
	 * "If [card] deals damage to your opponent, the damage becomes N instead."
	 * Printed on Ba'Gamnan 2-088C ({@code N} = 0) and granted until end of turn by Ramada 17-125R,
	 * Cecil 15-073H and Fang 19-131S ({@code N} = 2).
	 *
	 * <p>A replacement, not a multiplier — the result is exactly {@code amount}, so it overrides
	 * {@link #FA_OUTGOING_DAMAGE_DOUBLER} rather than stacking with it, and {@code N} = 0 means the
	 * card deals no damage to the opponent at all.
	 *
	 * <p>The qualified printings must not be treated as this unconditional form — they carry extra
	 * conditions it would silently drop. Behemoth 24-084R ("other than by its ability") fails the
	 * pattern outright, but Lightning 26-098L ("If Lightning <em>forming a party</em> deals damage…")
	 * does not: {@code card} is lazy but unrestricted, so it absorbs the qualifier and the match
	 * succeeds with {@code card = "Lightning forming a party"}. What excludes an unread qualifier is
	 * the caller comparing {@code card} against the carrier's own name — every reader of this
	 * pattern must make that check, not assume the anchors did it. {@link #FA_SUBJECT_FORMING_PARTY}
	 * is how a reader that does honour the party qualifier takes it off first.
	 * Groups: {@code card}, {@code amount}.
	 */
	static final Pattern FA_OUTGOING_DAMAGE_TO_OPPONENT_SETS_TO = Pattern.compile(
		"(?i)^If\\s+(?<card>.+?)\\s+deals\\s+damage\\s+to\\s+your\\s+opponent,\\s+" +
		"the\\s+damage\\s+becomes\\s+(?<amount>\\d+)\\s+instead\\.?$"
	);

	/**
	 * "[Name] forming a party" — the party qualifier a damage subject can carry, as Lightning
	 * 26-098L's does. Group {@code name} is the card name underneath it.
	 *
	 * <p>Its own pattern rather than an optional tail on each subject, because the readers that
	 * honour it have to do two things with it: match the name against their carrier, and ask the
	 * board whether a party is declared right now. A reader that does not know it exists still
	 * declines the printing, because the whole phrase fails its name check.
	 */
	static final Pattern FA_SUBJECT_FORMING_PARTY = Pattern.compile(
		"(?i)^(?<name>.+?)\\s+forming\\s+a\\s+party$"
	);

	/**
	 * Outgoing damage boost: "If a Forward is dealt damage by your [Element] Summon,
	 * the damage increases by N instead."
	 * Checked on the CASTER's side field cards (not the target's side).
	 * Groups: {@code element}, {@code amount}.
	 */
	static final Pattern FA_ELEMENT_SUMMON_DAMAGE_BOOST = Pattern.compile(
		"(?i)If\\s+a\\s+Forward\\s+is\\s+dealt\\s+damage\\s+by\\s+your\\s+" +
		"(?<element>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+Summon,\\s+" +
		"the\\s+damage\\s+increases\\s+by\\s+(?<amount>\\d+)\\s+instead\\.?"
	);

	/**
	 * Outgoing Summon damage boost with no Element qualifier: "If a Forward is dealt damage by your
	 * Summon, the damage increases by N instead." — Terra 9-029C.
	 *
	 * <p>The unfiltered counterpart of {@link #FA_ELEMENT_SUMMON_DAMAGE_BOOST}, and kept as its own
	 * pattern for the same reason {@link #FA_FRIENDLY_FORWARD_BATTLE_DAMAGE_BOOST} is: making the
	 * element group optional there would let it claim this text with a null element, and every
	 * element-scoped card would then boost every Summon.
	 * Group: {@code amount}.
	 */
	static final Pattern FA_FRIENDLY_SUMMON_DAMAGE_BOOST = Pattern.compile(
		"(?i)^If\\s+a\\s+Forward\\s+is\\s+dealt\\s+damage\\s+by\\s+your\\s+Summon,\\s+" +
		"the\\s+damage\\s+increases\\s+by\\s+(?<amount>\\d+)\\s+instead[.!]?$"
	);

	/**
	 * Outgoing combat damage boost from a friendly Forward to an opposing Forward.
	 * "If a Fire Forward [or a Category SOPFFO Forward] you control deals damage to a Forward, the
	 * damage increases by N instead." — and the Job-filtered spelling of the same sentence,
	 * "If a Job SOLDIER Forward you control deals damage to a Forward…" (Angeal 22-004H).
	 * Checked on the ATTACKER's side field cards (Forwards and Backups).
	 * Groups: {@code element}, {@code category} (optional), {@code job}, {@code amount}.
	 *
	 * <p>The Category arm is Neon 21-011H's, and it is a second way for the <em>same</em> boost to
	 * qualify rather than a second boost: a Fire Category SOPFFO Forward gets +1000 once, not twice.
	 * {@link #elementForwardBoostCovers} is what both readers ask, so neither can double-count it.
	 *
	 * <p>The Job arm is a sibling of the Element one rather than an addition to it — Angeal's
	 * sentence names no Element, so a Job printing must not be readable as an Element printing with a
	 * null filter, which is what would happen if the Element group were simply made optional. That is
	 * the same trap {@link #FA_FRIENDLY_FORWARD_BATTLE_DAMAGE_BOOST} is kept separate to avoid.
	 *
	 * <p>"you control" is printed once, after the last arm, which is why it sits outside the
	 * optional group rather than inside the first.
	 */
	static final Pattern FA_ELEMENT_FORWARD_DAMAGE_BOOST = Pattern.compile(
		"(?i)If\\s+a\\s+" +
		"(?:(?<element>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+Forward" +
			"(?:\\s+or\\s+a\\s+Category\\s+(?<category>\\S+)\\s+Forward)?" +
		"|Job\\s+(?<job>[^,]+?)\\s+Forward)" +
		"\\s+you\\s+control" +
		"\\s+deals?\\s+damage\\s+to\\s+a\\s+Forward,\\s+the\\s+damage\\s+increases\\s+by\\s+(?<amount>\\d+)\\s+instead\\.?"
	);

	/**
	 * Whether {@code dealer} satisfies the filter a {@link #FA_ELEMENT_FORWARD_DAMAGE_BOOST} match
	 * captured — its Element, the Category the optional second arm named, or the Job named by the
	 * arm that carries no Element at all.
	 *
	 * <p>Shared by the combat reader ({@code MainWindow.friendlyElementForwardCombatBoost}) and the
	 * ability reader ({@code DamageResolver.applyCasterSideElementForwardDamageBoosts}), so the two
	 * cannot come to different answers about the same printing.
	 *
	 * <p>Every group is null-checked before it is consulted: the Job arm leaves {@code element}
	 * absent, so the Element test can no longer assume a value the way it could when that group was
	 * mandatory.
	 */
	static boolean elementForwardBoostCovers(Matcher m, CardData dealer, MainWindow mw) {
		if (dealer == null) return false;
		String element = m.group("element");
		if (element != null && mw.effectiveContainsElement(dealer, element)) return true;
		String category = m.group("category");
		if (category != null && CardFilters.meetsCategoryFilter(dealer, category)) return true;
		String job = m.group("job");
		return job != null && CardFilters.meetsJobFilter(dealer, job.trim());
	}

	/**
	 * Outgoing damage boost worded from the DEALING side, covering an Element Summon, an Element
	 * Character you control, or both: "If [your [Element] Summon or ]a [Element] Character you
	 * control deals damage to a Forward, the damage increases by N instead." — Lehftia 21-020C
	 * (both arms) and Iroha 8-004R / Re-004C (the Character arm alone).
	 *
	 * <p>Distinct from both of the patterns above on the axis each of them fixes.
	 * {@link #FA_ELEMENT_SUMMON_DAMAGE_BOOST} says the same thing about Summons from the receiving
	 * side ("If a Forward is dealt damage by your Fire Summon"), and
	 * {@link #FA_ELEMENT_FORWARD_DAMAGE_BOOST} covers only Forwards where this covers every
	 * Character — a Backup or Monster whose ability deals the damage counts here and not there.
	 *
	 * <p>The Character arm filters on an Element, a Category (Chelinka 7-054L), a Job (Garnet
	 * Bahamut 17-035R) or a Card Name (Rapha 13-082C, Papalymo 5-159S) — four ways of naming which
	 * of your Characters carry the boost, never combined in one printing. The Job and Card Name
	 * spellings drop the word "Character" altogether ("If the Card Name Marach you control deals
	 * damage…"), which is why those branches end at "you control" rather than at a type token — and
	 * why the Job branch has to refuse a job that ends in one, or it would also claim the
	 * Forward-scoped printings {@link #FA_ELEMENT_FORWARD_DAMAGE_BOOST} owns.
	 *
	 * <p>The two halves are read separately by the caller: {@code summonelement} gates the Summon
	 * damage path, the Character filter the combat and ability paths, and either half may be absent.
	 * Readers ask {@link #characterArmCovers} rather than picking a group, so a printing that filters
	 * by Category cannot be silently read as one that filters by nothing.
	 * Groups: {@code summonelement} (optional), {@code element} / {@code element2} (optional, see
	 * {@link #characterArmElement}), {@code category} (optional), {@code cardname} (optional),
	 * {@code amount}.
	 */
	static final Pattern FA_ELEMENT_SUMMON_OR_CHARACTER_DAMAGE_BOOST = Pattern.compile(
		"(?i)^If\\s+" +
		// Both halves of this arm may drop their Element, and Ifrit, Lord of the Inferno 14-006R
		// drops both: "If your Summon or an ability of a Character you control …" boosts every
		// Summon its controller casts and every ability their Characters use. summonarm is what
		// tells a reader the Summon half is present at all, now that its Element no longer does.
		"(?:your\\s+(?:(?<summonelement>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+)?(?<summonarm>Summon)" +
		"(?:\\s+or\\s+(?:an?\\s+ability\\s+of\\s+)?an?\\s+" +
		"(?:(?<element>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+)?(?<anycharacter>Character)\\s+you\\s+control)?" +
		"|(?:an?|the)\\s+(?:" +
			"(?<element2>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+Character" +
			"|Category\\s+(?<category>\\S+)\\s+Character" +
			// The Job arm names no card type at all (Garnet Bahamut 17-035R, "a Job Winged Chaos you
			// control"), so it must refuse a job that ends in one. Without the lookbehinds the lazy
			// group swallows the type token and this pattern also claims Angeal 22-004H's "a Job
			// SOLDIER Forward you control" — which FA_ELEMENT_FORWARD_DAMAGE_BOOST already claims,
			// and both readers add every match they find, so the boost would apply twice.
			"|Job\\s+(?<job>[^,]+?)(?<!Forward)(?<!Backup)(?<!Monster)(?<!Character)" +
			// Comma-free, so the lazy name cannot reach past the subject into the effect half.
			"|Card\\s+Name\\s+(?<cardname>[^,]+?)" +
		")\\s+you\\s+control)" +
		"\\s+deals?\\s+damage\\s+to\\s+a\\s+Forward,\\s+" +
		// Papalymo 5-159S prints the imperative wording of the same boost; every other printing in
		// the corpus uses the declarative one.
		"(?:the\\s+damage\\s+increases\\s+by|increase\\s+the\\s+damage\\s+by)\\s+(?<amount>\\d+)\\s+instead[.!]?$"
	);

	/**
	 * The Element named by {@link #FA_ELEMENT_SUMMON_OR_CHARACTER_DAMAGE_BOOST}'s Character arm,
	 * whichever branch matched it, or {@code null} when the text has only the Summon arm. The
	 * alternation puts that arm in two different groups depending on whether the Summon arm
	 * preceded it, so every reader goes through here rather than picking a group and hoping.
	 */
	static String characterArmElement(Matcher m) {
		return m.group("element") != null ? m.group("element") : m.group("element2");
	}

	/**
	 * Whether {@code dealer} satisfies the Character arm of a
	 * {@link #FA_ELEMENT_SUMMON_OR_CHARACTER_DAMAGE_BOOST} match — its Element, its Category or its
	 * Card Name, whichever the printing named.
	 *
	 * <p>The counterpart of {@link #elementForwardBoostCovers} for the wider pattern, and shared by
	 * the same two readers ({@code MainWindow.friendlyElementForwardCombatBoost} and
	 * {@code DamageResolver.applyCasterSideElementForwardDamageBoosts}) for the same reason: the
	 * combat and ability paths must agree about which cards a printing covers.
	 *
	 * <p>Returns false when the match carries only the Summon arm — every filter group is absent
	 * there, and a Summon is not a Character. That is what stops the Summon-only printings from
	 * boosting every Character on the field.
	 */
	static boolean characterArmCovers(Matcher m, CardData dealer, MainWindow mw) {
		if (dealer == null) return false;
		String element = characterArmElement(m);
		if (element != null && mw.effectiveContainsElement(dealer, element)) return true;
		String category = m.group("category");
		if (category != null && CardFilters.meetsCategoryFilter(dealer, category)) return true;
		String job = m.group("job");
		if (job != null && CardFilters.meetsJobFilter(dealer, job.trim())) return true;
		String cardname = m.group("cardname");
		if (cardname != null && CardFilters.meetsCardNameFilter(dealer, cardname.trim())) return true;
		// An unfiltered Character arm — "an ability of a Character you control" (Ifrit, Lord of the
		// Inferno 14-006R) — covers every one of them. Asked last, and off the arm's own group
		// rather than off the absence of filters: a Summon-only printing has no filters either, and
		// must not be read as covering every Character on the field.
		return m.group("anycharacter") != null && element == null
				&& category == null && job == null && cardname == null;
	}

	/**
	 * How a {@link #FA_ELEMENT_SUMMON_OR_CHARACTER_DAMAGE_BOOST} match names the Characters it
	 * covers, for the log line the damage paths write. Descriptive only — {@link #characterArmCovers}
	 * is what decides whether the boost applies.
	 */
	static String characterArmLabel(Matcher m) {
		String element = characterArmElement(m);
		if (element != null) return element + " Character";
		if (m.group("category") != null) return "Category " + m.group("category") + " Character";
		if (m.group("job") != null) return "Job " + m.group("job").trim();
		if (m.group("cardname") != null) return "Card Name " + m.group("cardname").trim();
		return "Character";
	}

	/**
	 * Field-wide incoming-damage modifier: "If a [Category X | Job Y | Element] Forward
	 * [of cost N or less/more] [other than Z] you control [other than Z] is dealt damage
	 * [less than its power | by a Backup | by [your opponent's] Summons/abilities],
	 * [reduce the damage by N | the damage becomes N] instead."
	 *
	 * <p>The element qualifier is matched against the damaged Forward's effective elements, so a
	 * Multi-Element Forward satisfies every clause naming one of its elements — Yuzuki 13-125R
	 * protects Fire and Water Forwards separately and is itself Water/Fire.
	 * Groups: {@code category}, {@code job} / {@code job2} (see {@link #fieldDamageModifierJob}),
	 * {@code element}, {@code cost}, {@code costcmp},
	 * {@code except1} (before "you control"), {@code except2} (after "you control"),
	 * {@code sourceclause}, {@code reduceby}, {@code setsto}.
	 */
	static final Pattern FA_FIELD_DAMAGE_MODIFIER = Pattern.compile(
		"(?i)^If\\s+a\\s+" +
		"(?:" +
			"(?:Category\\s+(?<category>\\S+)\\s+" +
				"|Job\\s+(?<job>.+?)\\s+(?=Forward)" +
				"|(?<element>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+)?" +
			"Forward(?:\\s+of\\s+cost\\s+(?<cost>\\d+)\\s+or\\s+(?<costcmp>less|more))?" +
			// The type-less Job spelling (Amber Bahamut 17-036R, "a Job Winged Chaos you control"),
			// which names no card type at all. Refuses a job ending in one so it cannot claim the
			// arm above by swallowing its "Forward" token — the same guard, and for the same reason,
			// as the Job arm of FA_ELEMENT_SUMMON_OR_CHARACTER_DAMAGE_BOOST.
			"|Job\\s+(?<job2>[^,]+?)(?<!Forward)(?<!Backup)(?<!Monster)(?<!Character)" +
		")" +
		"(?:\\s+other\\s+than\\s+(?<except1>.+?))?" +
		"\\s+you\\s+control" +
		"(?:\\s+other\\s+than\\s+(?<except2>.+?))?" +
		"\\s+is\\s+dealt\\s+damage" +
		"(?<sourceclause>" +
			"\\s+less\\s+than\\s+its\\s+power" +
			"|\\s+by\\s+a\\s+Backup" +
			// Battle damage, the mirror of the same clause on FA_DAMAGE_MODIFIER (Amber Bahamut
			// 17-036R). Must follow the Backup branch and precede nothing that starts "by a" —
			// the two name different sources and neither is a prefix of the other.
			"|\\s+by\\s+a\\s+Forward" +
			"|\\s+by\\s+(?:your\\s+opponent's\\s+)?(?:a\\s+)?Summons?(?:\\s+or\\s+(?:an?\\s+)?abilit(?:y|ies))?" +
			"|\\s+by\\s+(?:your\\s+opponent's\\s+)?(?:a\\s+Summon\\s+or\\s+)?(?:an?\\s+)?abilit(?:y|ies)" +
		")?" +
		"\\s*,\\s+" +
		"(?:reduce\\s+the\\s+damage\\s+by\\s+(?<reduceby>\\d+)|the\\s+damage\\s+becomes\\s+(?<setsto>\\d+))" +
		"\\s+instead\\.?$"
	);

	/**
	 * The Job a {@link #FA_FIELD_DAMAGE_MODIFIER} match filters on, whichever of its two arms
	 * carried it, or {@code null} when the printing names no Job.
	 *
	 * <p>The arm that names a card type puts it in {@code job} and the type-less one in
	 * {@code job2}, so every reader goes through here rather than picking a group and hoping —
	 * exactly as {@link #characterArmElement} exists for the boost pattern.
	 */
	static String fieldDamageModifierJob(Matcher m) {
		return m.group("job") != null ? m.group("job") : m.group("job2");
	}

	/**
	 * The imperative spelling of a {@link #FA_FIELD_DAMAGE_MODIFIER} reduction: "Reduce the damage
	 * dealt to the [Category X | Job Y | Element] [Forwards | Characters] you control by N." —
	 * Warrior of Light 2-145L.
	 *
	 * <p>Same effect, different sentence: that one states a condition ("If a … is dealt damage")
	 * and then an outcome, this one states the outcome directly. Kept separate rather than bolted
	 * onto that pattern as another alternative, because the two put their filter, their amount and
	 * their target-type token in different places and merging them would produce a regex neither
	 * printing could be read out of.
	 *
	 * <p>Unqualified by damage source: it reduces combat, ability and Summon damage alike, which is
	 * the difference from the {@code sourceclause} arms of its sibling.
	 * Groups: {@code category}, {@code job}, {@code element}, {@code types}, {@code amount}.
	 */
	static final Pattern FA_REDUCE_DAMAGE_TO_FILTER = Pattern.compile(
		"(?i)^Reduce\\s+the\\s+damage\\s+dealt\\s+to\\s+the\\s+" +
		"(?:Category\\s+(?<category>\\S+)\\s+" +
			"|Job\\s+(?<job>.+?)\\s+(?=Forwards?\\b|Characters?\\b|you\\s+control)" +
			"|(?<element>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+)?" +
		"(?<types>Forwards?|Characters?)?\\s*" +
		"you\\s+control\\s+by\\s+(?<amount>\\d+)[.!]?$"
	);

	/**
	 * Field-wide exact-amount damage nullification:
	 * "If a Forward you control receives N damage, the damage becomes 0 instead."
	 * Group: {@code amount} — the exact damage value to intercept.
	 */
	static final Pattern FA_FIELD_DAMAGE_EXACT_NULLIFY = Pattern.compile(
		"(?i)^If\\s+a\\s+Forward\\s+you\\s+control\\s+receives\\s+(?<amount>\\d+)\\s+damage,?\\s+the\\s+damage\\s+becomes\\s+0\\s+instead\\.?$"
	);

	/**
	 * Party-forming damage protection: "If a Forward forming a party with [CardName] is dealt damage,
	 * the damage becomes 0 instead."
	 * Group: {@code source} — the card name whose party membership triggers the protection.
	 */
	static final Pattern FA_PARTY_DAMAGE_PROTECTION = Pattern.compile(
		"(?i)^If\\s+a\\s+Forward\\s+forming\\s+a\\s+party\\s+with\\s+(?<source>.+?)\\s+is\\s+dealt\\s+damage,\\s+the\\s+damage\\s+becomes\\s+0\\s+instead\\.?$"
	);

	/**
	 * The reduction twin of {@link #FA_PARTY_DAMAGE_PROTECTION}, covering the carrier as well as
	 * its party: "If [card] or a Forward forming a party with [card] receives damage, the damage
	 * decreases by N instead." — White Mage 3-136C.
	 *
	 * <p>Two things separate it from that sibling. It reduces rather than replacing with 0, so it
	 * cannot ride the nullification path; and its first arm is unconditional — the carrier is
	 * protected whether or not it is in a party, while the second arm needs one. Both name captures
	 * are checked against the carrier by the caller, exactly as the neighbouring patterns require.
	 * Groups: {@code card}, {@code partner}, {@code amount}.
	 */
	static final Pattern FA_SELF_OR_PARTY_DAMAGE_REDUCTION = Pattern.compile(
		"(?i)^If\\s+(?<card>.+?)\\s+or\\s+a\\s+Forward\\s+forming\\s+a\\s+party\\s+with\\s+(?<partner>.+?)\\s+" +
		"(?:receives|is\\s+dealt)\\s+damage,\\s+" +
		"(?:the\\s+damage\\s+decreases\\s+by|reduce\\s+the\\s+damage\\s+by)\\s+(?<amount>\\d+)\\s+instead[.!]?$"
	);

	/**
	 * The self-conditioned twin of {@link #FA_PARTY_DAMAGE_PROTECTION}: "If [card] forms a party,
	 * the damage dealt to [whom] becomes 0 instead." Chocobo 5-060C and Paladin 12-102C name
	 * themselves as the protected card; Chelinka 20-049R names the whole party instead.
	 *
	 * <p>The difference from its sibling is which side of the party the condition sits on. That one
	 * is printed on the protector and asks whether the <em>damaged</em> Forward is partied with it;
	 * this one asks whether the <em>printing</em> card is in a party at all, and then protects either
	 * itself or everyone alongside it.
	 *
	 * <p>Group {@code card} is the Forward whose party membership is the condition, checked against
	 * the carrier by the caller. {@code wholeparty} is present only for the Chelinka wording and is
	 * matched first, so the lazy {@code target} group cannot claim it; exactly one of the two is
	 * non-null on any match.
	 */
	static final Pattern FA_PARTY_SELF_DAMAGE_NULLIFY = Pattern.compile(
		"(?i)^If\\s+(?<card>.+?)\\s+forms\\s+a\\s+party,\\s+the\\s+damage\\s+dealt\\s+to\\s+" +
		"(?:(?<wholeparty>the\\s+Forwards?\\s+forming\\s+this\\s+party)|(?<target>.+?))" +
		"\\s+becomes\\s+0\\s+instead[.!]?$"
	);

	/**
	 * Field ability: "The power of Forwards opponent controls cannot be increased by Summons or abilities."
	 * Placed on any field card; suppresses positive power boosts to the opposing player's Forwards
	 * regardless of who is applying the boost.
	 */
	static final Pattern FA_OPP_FORWARD_POWER_BOOST_SUPPRESSED = Pattern.compile(
		"(?i)The\\s+power\\s+of\\s+Forwards?\\s+(?:your\\s+)?opponent\\s+controls?\\s+cannot\\s+be\\s+increased\\s+by\\s+Summons?\\s+or\\s+abilit(?:y|ies)[.!]?"
	);

	/**
	 * Field ability: "The power of Forwards opponent controls cannot be increased by your opponent's Summons or abilities."
	 * Like FA_OPP_FORWARD_POWER_BOOST_SUPPRESSED but only blocks the forward-controller's OWN boosts;
	 * the field card's controller may still increase those Forwards' power.
	 */
	static final Pattern FA_OPP_FORWARD_SELF_BOOST_SUPPRESSED = Pattern.compile(
		"(?i)The\\s+power\\s+of\\s+Forwards?\\s+(?:your\\s+)?opponent\\s+controls?\\s+cannot\\s+be\\s+increased\\s+by\\s+your\\s+opponent(?:'s|s')\\s+Summons?\\s+or\\s+abilit(?:y|ies)[.!]?"
	);

	/**
	 * Field ability: "The power of Forwards cannot be increased by Summons or abilities." —
	 * Meltigemini 8-128R.
	 *
	 * <p>The unscoped twin of {@link #FA_OPP_FORWARD_POWER_BOOST_SUPPRESSED}: naming no controller,
	 * it binds every Forward on the table, its own controller's included. The two cannot collide —
	 * this one requires "Forwards" to be followed straight by "cannot", where that one requires the
	 * controller clause in between.
	 */
	static final Pattern FA_ALL_FORWARD_POWER_BOOST_SUPPRESSED = Pattern.compile(
		"(?i)^The\\s+power\\s+of\\s+Forwards?\\s+cannot\\s+be\\s+increased\\s+by\\s+Summons?\\s+or\\s+abilit(?:y|ies)[.!]?$"
	);

	/** Returns true if {@code card} has the opponent-Forward-power-boost-suppression field ability. */
	static boolean hasOppForwardPowerBoostSuppression(CardData card) {
		for (FieldAbility fa : card.fieldAbilities())
			if (FA_OPP_FORWARD_POWER_BOOST_SUPPRESSED.matcher(fa.effectText()).find()) return true;
		return false;
	}

	/** Returns true if {@code card} has the both-sides power-boost-suppression field ability. */
	static boolean hasAllForwardPowerBoostSuppression(CardData card) {
		for (FieldAbility fa : card.fieldAbilities())
			if (FA_ALL_FORWARD_POWER_BOOST_SUPPRESSED.matcher(fa.effectText().trim()).matches()) return true;
		return false;
	}

	/** Returns true if {@code card} has the self-only power-boost-suppression field ability. */
	static boolean hasOppForwardSelfBoostSuppression(CardData card) {
		for (FieldAbility fa : card.fieldAbilities())
			if (FA_OPP_FORWARD_SELF_BOOST_SUPPRESSED.matcher(fa.effectText()).find()) return true;
		return false;
	}

	/**
	 * Field ability: "Opposing Forwards entering the field will not trigger any auto-abilities ..."
	 * Suppresses both the entering Forward's own ETF abilities and the opponent's same-side ETF watchers.
	 * The controller's own "when an opposing Forward enters" abilities are NOT suppressed.
	 */
	static final Pattern FA_OPP_FORWARD_ETF_SUPPRESSED = Pattern.compile(
		"(?i)Opposing\\s+Forwards?\\s+entering\\s+the\\s+field\\s+will\\s+not\\s+trigger\\s+any\\s+auto.?abilities"
	);

	/** "You can cast Forwards from your Break Zone." — passive field ability. */
	static final Pattern FA_CAST_FORWARDS_FROM_BZ = Pattern.compile(
		"(?i)^You\\s+can\\s+cast\\s+Forwards?\\s+from\\s+your\\s+Break\\s+Zone[.!]?$"
	);

	static boolean hasCastForwardsFromBz(CardData card) {
		for (FieldAbility fa : card.fieldAbilities())
			if (FA_CAST_FORWARDS_FROM_BZ.matcher(fa.effectText().trim()).matches()) return true;
		return false;
	}

	/**
	 * "[Once per turn, ]you can cast [what] removed by [CardName]'s abilities at any time you could
	 * normally cast [it|them]." — Setzer 21-031H (any card, once a turn) and Rinoa 21-038R (Summons,
	 * as often as she likes).
	 *
	 * <p>Names the removing card rather than a zone: what it opens is the pile that card's own
	 * abilities have removed from the game, which {@code MainWindow.cardsRemovedBySource} already
	 * records for the counting printings. "At any time you could normally cast it" is ordinary
	 * casting timing, so the permission needs no clause of its own — it is the absence of "this
	 * turn" that makes the registration a standing one.
	 * Groups: {@code once} (present iff the printing limits itself), {@code what}, {@code card}.
	 */
	static final Pattern FA_CAST_REMOVED_BY_SELF = Pattern.compile(
		"(?i)^(?<once>Once\\s+per\\s+turn,\\s+)?you\\s+can\\s+cast\\s+" +
		"(?:an?\\s+(?<what1>card|Forward|Backup|Monster|Character|Summon)" +
		"|(?<what2>cards|Forwards|Backups|Monsters|Characters|Summons))\\s+removed\\s+by\\s+" +
		"(?<card>.+?)(?:'s|s')\\s+abilit(?:y|ies)\\s+at\\s+any\\s+time\\s+you\\s+could\\s+normally\\s+" +
		"cast\\s+(?:it|them)[.!]?$"
	);

	/**
	 * A card's standing permission to cast what its own abilities have removed from the game.
	 *
	 * <p>One record for every printing of the shape rather than a mechanism per card: the two in
	 * the corpus differ only in what they open and whether they cap it, and both are read by the
	 * one board sweep in {@code MainWindow.syncRfgRemovedPlayables}.
	 *
	 * @param cardType   the type filter, in the singular form {@link CardFilters#matchesDiscardType}
	 *                   takes; {@code "card"} for a printing that names no type
	 * @param oncePerTurn whether the printing caps its controller at one such cast per turn
	 */
	record CastRemovedPermission(String cardType, boolean oncePerTurn) {
		/** Whether this permission opens {@code card}. */
		boolean admits(CardData card) {
			return CardFilters.matchesDiscardType(card, cardType);
		}
	}

	/**
	 * The permission {@code card} prints over the cards its own abilities removed, or {@code null}
	 * when it prints none. Name-checked against its carrier, because the sentence names the card
	 * whose removals it opens and a card's own name in its own text means that card.
	 */
	static CastRemovedPermission castRemovedPermission(CardData card) {
		for (FieldAbility fa : card.fieldAbilities()) {
			Matcher m = FA_CAST_REMOVED_BY_SELF.matcher(fa.effectText().trim());
			if (!m.matches() || !m.group("card").trim().equalsIgnoreCase(card.name())) continue;
			String what = m.group("what1") != null ? m.group("what1") : m.group("what2");
			return new CastRemovedPermission(what.replaceAll("(?i)s$", ""), m.group("once") != null);
		}
		return null;
	}

	/**
	 * "You can cast [CardName] from your Break Zone." — a self-referential break-zone ability
	 * (e.g. Zenos) that lets the card cast itself while it sits in its owner's Break Zone.
	 * Distinct from {@link #FA_CAST_FORWARDS_FROM_BZ} by the name-vs-self check in
	 * {@link #canCastSelfFromBz}.  Group: {@code name}.
	 */
	static final Pattern FA_CAST_SELF_FROM_BZ = Pattern.compile(
		"(?i)^You\\s+can\\s+cast\\s+(?<name>.+?)\\s+from\\s+your\\s+Break\\s+Zone[.!]?$"
	);

	/** Returns {@code true} if {@code card} has "You can cast [its own name] from your Break Zone." */
	static boolean canCastSelfFromBz(CardData card) {
		for (FieldAbility fa : card.fieldAbilities()) {
			Matcher m = FA_CAST_SELF_FROM_BZ.matcher(fa.effectText().trim());
			if (m.matches() && m.group("name").trim().equalsIgnoreCase(card.name())) return true;
		}
		return false;
	}

	/** "You can only cast up to 2 cards per turn." — limits the controlling player. */
	static final Pattern FA_SELF_CAST_LIMIT = Pattern.compile(
		"(?i)^You\\s+can\\s+only\\s+cast\\s+up\\s+to\\s+2\\s+cards?\\s+per\\s+turn[.!]?$"
	);

	/** "Each player can only cast up to 2 cards per turn." — limits both players. */
	static final Pattern FA_BOTH_CAST_LIMIT = Pattern.compile(
		"(?i)^Each\\s+player\\s+can\\s+only\\s+cast\\s+up\\s+to\\s+2\\s+cards?\\s+per\\s+turn[.!]?$"
	);

	static boolean hasSelfCastLimit(CardData card) {
		for (FieldAbility fa : card.fieldAbilities())
			if (FA_SELF_CAST_LIMIT.matcher(fa.effectText().trim()).matches()) return true;
		return false;
	}

	static boolean hasBothCastLimit(CardData card) {
		for (FieldAbility fa : card.fieldAbilities())
			if (FA_BOTH_CAST_LIMIT.matcher(fa.effectText().trim()).matches()) return true;
		return false;
	}

	/** "If a card is put into your Break Zone in any situation, remove it from the game instead." */
	static final Pattern FA_BZ_TO_RFG_ANY_SITUATION = Pattern.compile(
		"(?i)^If\\s+a\\s+card\\s+is\\s+put\\s+into\\s+your\\s+Break\\s+Zone\\s+in\\s+any\\s+situation,\\s+remove\\s+it\\s+from\\s+the\\s+game\\s+instead[.!]?$"
	);

	/** "If a Character is put from the field into the Break Zone, you may remove it from the game instead." */
	static final Pattern FA_CHARACTER_FIELD_TO_BZ_MAY_RFG = Pattern.compile(
		"(?i)^If\\s+a\\s+Character\\s+is\\s+put\\s+from\\s+the\\s+field\\s+into\\s+the\\s+Break\\s+Zone,\\s+you\\s+may\\s+remove\\s+it\\s+from\\s+the\\s+game\\s+instead[.!]?$"
	);

	/** "If a damaged Forward opponent controls is put from the field into the Break Zone, remove it from the game instead." */
	static final Pattern FA_OPP_DAMAGED_FORWARD_FIELD_TO_BZ_RFG = Pattern.compile(
		"(?i)^If\\s+a\\s+damaged\\s+Forward\\s+opponent\\s+controls?\\s+is\\s+put\\s+from\\s+the\\s+field\\s+into\\s+the\\s+Break\\s+Zone,\\s+remove\\s+it\\s+from\\s+the\\s+game\\s+instead[.!]?$"
	);

	static boolean hasBzToRfgAnySituation(CardData card) {
		for (FieldAbility fa : card.fieldAbilities())
			if (FA_BZ_TO_RFG_ANY_SITUATION.matcher(fa.effectText()).find()) return true;
		return false;
	}

	static boolean hasCharacterFieldToBzMayRfg(CardData card) {
		for (FieldAbility fa : card.fieldAbilities())
			if (FA_CHARACTER_FIELD_TO_BZ_MAY_RFG.matcher(fa.effectText()).find()) return true;
		return false;
	}

	static boolean hasOppDamagedForwardFieldToBzRfg(CardData card) {
		for (FieldAbility fa : card.fieldAbilities())
			if (FA_OPP_DAMAGED_FORWARD_FIELD_TO_BZ_RFG.matcher(fa.effectText()).find()) return true;
		return false;
	}

	/**
	 * "If a Forward damaged by [name] is put from the field into the Break Zone on the same turn,
	 * remove it from the game instead." — Susano, Lord of the Revel 14-011H.
	 *
	 * <p>Unlike {@link #FA_OPP_DAMAGED_FORWARD_FIELD_TO_BZ_RFG}, which asks only whether the
	 * departing Forward carries damage, this one asks <em>who dealt it</em>: the redirect is owed to
	 * the carrier's own damage and to nothing else. That is why it needs
	 * {@code MainWindow.damagedBySourcesThisTurn} behind it rather than a damage count.
	 *
	 * <p>The name capture is checked against the carrier by {@link #hasDamagedBySelfFieldToBzRfg} —
	 * "Susano, Lord of the Revel" contains a comma, so {@code card} is anchored on both sides rather
	 * than stopped at one.
	 */
	static final Pattern FA_DAMAGED_BY_SELF_FIELD_TO_BZ_RFG = Pattern.compile(
		"(?i)^If\\s+(?:a|the)\\s+Forward\\s+damaged\\s+by\\s+(?<card>.+?)\\s+is\\s+put\\s+from\\s+the\\s+field\\s+" +
		"into\\s+the\\s+Break\\s+Zone\\s+(?:on|during)\\s+the\\s+same\\s+turn,\\s+" +
		"remove\\s+it\\s+from\\s+the\\s+game\\s+instead[.!]?$"
	);

	/** Whether {@code card} carries {@link #FA_DAMAGED_BY_SELF_FIELD_TO_BZ_RFG} naming itself. */
	static boolean hasDamagedBySelfFieldToBzRfg(CardData card) {
		for (FieldAbility fa : card.fieldAbilities()) {
			Matcher m = FA_DAMAGED_BY_SELF_FIELD_TO_BZ_RFG.matcher(fa.effectText().trim());
			if (m.matches() && m.group("card").trim().equalsIgnoreCase(card.name())) return true;
		}
		return false;
	}

	/**
	 * "During each turn, when an auto-ability triggered from your opponent's Forward is put on the
	 * stack for the first time in that turn, cancel its effect." — Bahamut (XVI) 29-115L.
	 *
	 * <p>Its subject is the Stack rather than the board, so it is read where an auto ability is
	 * pushed ({@code MainWindow.cancelFirstOppForwardAuto}) rather than at resolution: the rule is
	 * about what goes on, not what comes off.
	 *
	 * <p>The trailing period is optional because this printing has none — its text ends mid-clause
	 * with a trailing space, which is how it scrapes.
	 */
	static final Pattern FA_CANCEL_FIRST_OPP_FORWARD_AUTO = Pattern.compile(
		"(?i)^During\\s+each\\s+turn,\\s+when\\s+an\\s+auto-abilit(?:y|ies)\\s+triggered\\s+from\\s+" +
		"your\\s+opponent's\\s+Forwards?\\s+is\\s+put\\s+on\\s+the\\s+stack\\s+for\\s+the\\s+first\\s+time\\s+" +
		"in\\s+that\\s+turn,\\s+cancel\\s+its\\s+effect[.!]?\\s*$"
	);

	/** "If [name] deals damage to a Forward of cost N or more, double the damage instead." */
	static final Pattern FA_DOUBLE_DAMAGE_VS_COST_THRESHOLD =
			Pattern.compile(
				"(?i)If\\s+(?<name>.+?)\\s+deals?\\s+damage\\s+to\\s+a\\s+Forward\\s+of\\s+cost\\s+(?<cost>\\d+)" +
				"\\s+or\\s+more,\\s+double\\s+the\\s+damage\\s+instead[.!]?"
			);

	/** "If [card] deals damage to a Forward of cost N or more, [increase the damage by | the damage increases by] X instead." */
	static final Pattern FA_OUTGOING_FLAT_BOOST_VS_COST = Pattern.compile(
		"(?i)^If\\s+(?<card>.+?)\\s+deals?\\s+damage\\s+to\\s+a\\s+Forward\\s+of\\s+cost\\s+(?<cost>\\d+)\\s+or\\s+more," +
		"\\s+(?:increase\\s+the\\s+damage\\s+by|the\\s+damage\\s+increases\\s+by)\\s+(?<amount>\\d+)\\s+instead[.!]?$"
	);

	/**
	 * Self, unconditional outgoing flat boost vs any Forward:
	 * "If [card] deals damage to a Forward, [increase the damage by | the damage increases by] X instead."
	 * Checked against the DEALING card's own field abilities, matched on the card's name — this is the
	 * self variant, distinct from the "a Fire Forward you control" / "your Summon" grants which name no
	 * specific card ({@link #FA_ELEMENT_FORWARD_DAMAGE_BOOST}, {@link #FA_ELEMENT_SUMMON_DAMAGE_BOOST}).
	 * Applies to both combat and ability damage the source deals to a Forward; may carry a
	 * "Damage N --" threshold. Groups: {@code card}, {@code amount}.
	 */
	static final Pattern FA_OUTGOING_FLAT_BOOST = Pattern.compile(
		"(?i)^If\\s+(?<card>(?!(?:a|an|your|the)\\s)\\S.*?)\\s+deals?\\s+damage\\s+to\\s+a\\s+Forward," +
		"\\s+(?:increase\\s+the\\s+damage\\s+by|the\\s+damage\\s+increases\\s+by)\\s+(?<amount>\\d+)\\s+instead[.!]?$"
	);

	/** "If [card] is dealt damage by a Forward of cost N or more, reduce the damage by X instead." */
	static final Pattern FA_INCOMING_REDUCTION_VS_COST = Pattern.compile(
		"(?i)^If\\s+(?<card>.+?)\\s+is\\s+dealt\\s+damage\\s+by\\s+a\\s+Forward\\s+of\\s+cost\\s+(?<cost>\\d+)\\s+or\\s+more," +
		"\\s+reduce\\s+the\\s+damage\\s+by\\s+(?<amount>\\d+)\\s+instead[.!]?$"
	);

	/** "Opponent must block [cardName] if possible." — forces the opponent to declare a blocker when the named card attacks. */
	static final Pattern FA_OPPONENT_MUST_BLOCK = Pattern.compile(
		"(?i)^Opponent\\s+must\\s+block\\s+(?<cardname>.+?)\\s+if\\s+possible[.!]?$"
	);

	/**
	 * The field-wide block compulsion: "The Forwards you control must block if possible."
	 * (General Leo 15-021R), "The Forwards opponent controls must block if possible."
	 * (Jack Garland 24-079L), and "All Forwards must block if possible." (Layle 16-083H). The three
	 * differ only in whose Forwards they name, so one pattern reads all of them.
	 *
	 * <p>Unlike {@link #FA_OPPONENT_MUST_BLOCK} this sits on neither the attacker nor the blocker:
	 * it names a whole side, and every Forward on it is compelled. Since only one Forward can block
	 * a given attack, the effect is that the named side may not decline a block it could make — it
	 * constrains the answer, not which Forward gives it.
	 *
	 * <p>Group {@code scope} is the controller clause, and is absent for the "All Forwards" form.
	 */
	static final Pattern FA_FIELD_FORWARDS_MUST_BLOCK = Pattern.compile(
		"(?i)^(?:The\\s+Forwards?\\s+(?<scope>you\\s+control|(?:your\\s+)?opponent\\s+controls?)" +
		"|All\\s+Forwards?)\\s+must\\s+block\\s+if\\s+possible[.!]?$"
	);

	/**
	 * The attack-side twin of {@link #FA_FIELD_FORWARDS_MUST_BLOCK}: "All Forwards must attack once
	 * per turn if possible." (Layle 16-083H) and "The Forwards opponent controls must attack once
	 * per turn if possible." (Jack Garland 24-079L). "at least once" is accepted as the same thing —
	 * older printings word it that way and mean no different.
	 *
	 * <p>Group {@code scope} is the controller clause, absent for the "All Forwards" form.
	 */
	static final Pattern FA_FIELD_FORWARDS_MUST_ATTACK = Pattern.compile(
		"(?i)^(?:The\\s+Forwards?\\s+(?<scope>you\\s+control|(?:your\\s+)?opponent\\s+controls?)" +
		"|All\\s+Forwards?)\\s+must\\s+attack\\s+(?:at\\s+least\\s+)?once\\s+per\\s+turn\\s+if\\s+possible[.!]?$"
	);

	/**
	 * "During your opponent's turn, the Forwards opponent controls cannot use action abilities."
	 * (Sin 14-045H.)
	 *
	 * <p>Both clauses name the same player, and it is not the carrier's controller: the lock lands
	 * on the opposing player's Forwards, and only while that player is the one taking the turn.
	 * Off their turn they act normally, which is what makes this narrower than a blanket lock —
	 * it shuts down responses to the carrier's own attacks, not the opponent's whole game.
	 *
	 * <p>Action abilities only. Under rule 6-1-1 a Special Ability is its own kind of ability
	 * rather than a form of action ability, so it is not caught here; nor are auto or field
	 * abilities, which nobody "uses".
	 */
	static final Pattern FA_OPP_FORWARDS_CANNOT_USE_ACTION_ABILITIES = Pattern.compile(
		"(?i)^During\\s+your\\s+opponent.?s\\s+turn,\\s+the\\s+Forwards?\\s+" +
		"(?:your\\s+)?opponent\\s+controls?\\s+cannot\\s+use\\s+action\\s+abilit(?:y|ies)[.!]?$"
	);

	/** Returns true if {@code card} locks the opposing player's Forwards out of action abilities. */
	static boolean hasOppForwardsActionAbilityLock(CardData card) {
		for (FieldAbility fa : card.fieldAbilities())
			if (FA_OPP_FORWARDS_CANNOT_USE_ACTION_ABILITIES.matcher(fa.effectText().trim()).matches()) return true;
		return false;
	}

	/**
	 * "The Characters opponent controls cannot use special or action abilities."
	 * (The Emperor 2-147L.)
	 *
	 * <p>The unconditional, whole-board twin of
	 * {@link #FA_OPP_FORWARDS_CANNOT_USE_ACTION_ABILITIES}, and wider on all three axes that one is
	 * narrow on: every Character rather than Forwards alone, Special Abilities as well as action
	 * ones, and at all times rather than only while the locked player is taking their turn. What it
	 * keeps is the side scoping — it binds the opposing player's Characters, never its controller's.
	 *
	 * <p>Auto and field abilities are untouched: nobody "uses" those, so a locked Character keeps
	 * every trigger it prints. Nor does the lock reach an ability used from hand or from the Break
	 * Zone, since the text speaks of Characters on the field.
	 */
	static final Pattern FA_OPP_CHARACTERS_CANNOT_USE_ABILITIES = Pattern.compile(
		"(?i)^The\\s+Characters?\\s+(?:your\\s+)?opponent\\s+controls?\\s+cannot\\s+use\\s+" +
		"special\\s+or\\s+action\\s+abilit(?:y|ies)[.!]?$"
	);

	/**
	 * "[Self and] the [filter] you control can use action abilities [and special abilities] with
	 * 《Dull》 in the cost as though they had Haste." — Cherukiki 19-109H and Zangan 26-070H.
	 *
	 * <p>Not a Haste grant. It lifts one specific consequence of Haste — that a Character may pay a
	 * 《Dull》 cost the turn it arrives — and leaves the rest alone: a Forward under this permission
	 * still cannot attack on the turn it enters. Modelling it as {@code Trait.HASTE} would hand out
	 * the attack too, so it is asked as its own question at the point the dull cost is checked.
	 *
	 * <p>Zangan's printing shows both halves the grammar allows: an optional leading self-reference
	 * ("Zangan and …"), and a filtered set that names no card type ("the Card Name Tifa you
	 * control"), which reaches any Character. Cherukiki's names a type but no self ("The Category XI
	 * Forwards you control"). Which kinds of ability are covered differs too — only Zangan's extends
	 * to Special Abilities, which rule 6-1-1 makes a separate kind.
	 */
	static final Pattern FA_DULL_COST_AS_THOUGH_HASTE = Pattern.compile(
		"(?i)^(?:(?<selfname>[^,]+?)\\s+and\\s+)?(?:The\\s+)?" +
		"(?:Category\\s+(?<category>\\S+)|Card\\s+Name\\s+(?<cardname>.+?)|Job\\s+(?<job>.+?))\\s*" +
		"(?<type>Forwards?|Backups?|Monsters?|Characters?)?\\s*" +
		"you\\s+control\\s+can\\s+use\\s+action\\s+abilit(?:y|ies)" +
		"(?<special>\\s+and\\s+special\\s+abilit(?:y|ies))?\\s+" +
		"with\\s+《Dull》\\s+in\\s+the\\s+cost\\s+as\\s+though\\s+(?:they|it)\\s+had\\s+Haste[.!]?$"
	);

	/**
	 * Who may pay a 《Dull》 cost the turn they arrive, and for which kinds of ability.
	 *
	 * @param selfName    the carrier named alongside the filtered set, or {@code null}; checked by
	 *     identity against the carrier, since a card naming itself means that copy
	 * @param inclSpecial whether Special Abilities are covered as well as action abilities
	 */
	record DullCostHasteGrant(String selfName, String cardName, String category, String job,
			boolean inclForwards, boolean inclBackups, boolean inclMonsters, boolean inclSpecial) {

		/** True when this grant speaks to {@code ability} at all — a dull cost of a covered kind. */
		boolean coversAbility(ActionAbility ability) {
			return ability.requiresDull() && (inclSpecial || !ability.isSpecial());
		}

		/** True when {@code c} is inside the filtered set, using the shared field-filter rules. */
		boolean coversCard(CardData c) {
			if (c == null) return false;
			boolean typeOk = (inclForwards && c.isForward())
			              || (inclBackups  && c.isBackup())
			              || (inclMonsters && (c.isMonster() || c.alsoCountsAsMonster()));
			return typeOk
				&& CardFilters.meetsCardNameFilter(c, cardName)
				&& CardFilters.meetsCategoryFilter(c, category)
				&& CardFilters.meetsJobFilter(c, job);
		}
	}

	/** Reads a {@link DullCostHasteGrant} out of a field ability, or {@code null} if it is not one. */
	static DullCostHasteGrant parseDullCostHasteGrant(String effectText) {
		if (effectText == null) return null;
		Matcher m = FA_DULL_COST_AS_THOUGH_HASTE.matcher(effectText.trim());
		if (!m.matches()) return null;
		String type = m.group("type");
		String t    = type == null ? "character" : type.toLowerCase(Locale.ROOT);
		boolean any = t.startsWith("character");
		return new DullCostHasteGrant(
				m.group("selfname") != null ? m.group("selfname").trim() : null,
				m.group("cardname") != null ? m.group("cardname").trim() : null,
				m.group("category") != null ? m.group("category").trim() : null,
				m.group("job")      != null ? m.group("job").trim()      : null,
				any || t.startsWith("forward"),
				any || t.startsWith("backup"),
				any || t.startsWith("monster"),
				m.group("special") != null);
	}

	/** Returns true if {@code card} locks the opposing player's Characters out of used abilities. */
	static boolean hasOppCharacterAbilityLock(CardData card) {
		for (FieldAbility fa : card.fieldAbilities())
			if (FA_OPP_CHARACTERS_CANNOT_USE_ABILITIES.matcher(fa.effectText().trim()).matches()) return true;
		return false;
	}

	/**
	 * "During each turn, when your opponent casts a Summon for the first time in that turn, cancel
	 * its effect." (The Fiend 20-114L.)
	 *
	 * <p>Read off the board by {@code MainWindow.pushSummonOnStack} rather than dispatched as an
	 * auto-ability: the existing {@code "cast summon"} trigger fires on the <em>casting</em> player's
	 * own field and only after the Summon has resolved, so neither the side nor the timing this needs
	 * is available there. Both matter — the cancel has to land while the Summon is still on the
	 * Stack, and it has to land on the Summon its controller's opponent cast.
	 *
	 * <p>"for the first time in that turn" counts the caster's Summons within the turn, not the
	 * carrier's uses: a second Summon in the same turn resolves normally even if the first was never
	 * cancelled because the carrier had only just arrived.
	 */
	static final Pattern FA_CANCEL_OPP_FIRST_SUMMON_EACH_TURN = Pattern.compile(
		"(?i)^During\\s+each\\s+turn,\\s+when\\s+your\\s+opponent\\s+casts\\s+a\\s+Summon\\s+" +
		"for\\s+the\\s+first\\s+time\\s+in\\s+that\\s+turn,\\s+cancel\\s+its\\s+effect[.!]?$"
	);

	/** Returns true if {@code card} cancels the first Summon its controller's opponent casts each turn. */
	static boolean hasOppFirstSummonCancel(CardData card) {
		for (FieldAbility fa : card.fieldAbilities())
			if (FA_CANCEL_OPP_FIRST_SUMMON_EACH_TURN.matcher(fa.effectText().trim()).matches()) return true;
		return false;
	}

	/**
	 * A standing self-named block compulsion: "[card] must block if possible." (Ricard 6-103H) and
	 * the reversed printing "If possible, [card] must block." (Cecil 2-129L).
	 *
	 * <p>Unlike {@link #FA_THIS_FORWARD_MUST_BLOCK_NAMED} it names no attacker, so it binds against
	 * everything that attacks rather than one card. The {@code card} capture is checked against the
	 * carrier's own name by the caller, which is what keeps it off the granted "This Forward must
	 * block if possible." wording — that one is handled by the turn-scoped index set instead.
	 */
	static final Pattern FA_SELF_MUST_BLOCK = Pattern.compile(
		"(?i)^(?:If\\s+possible,\\s+)?(?<card>.+?)\\s+must\\s+block(?:\\s+if\\s+possible)?[.!]?$"
	);

	/**
	 * A standing self-named attack compulsion: "[card] must attack [at least] once per turn if
	 * possible." — Berserker 15-078C and 3-091C, Umaro 17-022H, Reddas 2-072C. The printed
	 * counterpart of the granted compulsion {@code permanentMustAttackOncePerTurn} already holds
	 * for Roche 29-076H, and satisfied the same way: one attack settles it for the turn.
	 */
	static final Pattern FA_SELF_MUST_ATTACK = Pattern.compile(
		"(?i)^(?<card>.+?)\\s+must\\s+attack\\s+(?:at\\s+least\\s+)?once\\s+per\\s+turn\\s+if\\s+possible[.!]?$"
	);

	/**
	 * "[card] cannot be blocked by a Monster that is also a Forward." — Jack Garland 29-123R.
	 *
	 * <p>A restriction on the blocker's card type, so it bars exactly the Monsters some effect has
	 * turned into Forwards — the only Monsters eligible to block at all — and leaves Backups acting
	 * as Forwards alone, since those are not Monsters. Group: {@code card}.
	 */
	static final Pattern FA_CANNOT_BE_BLOCKED_BY_MONSTER_FORWARD = Pattern.compile(
		"(?i)^(?<card>.+?)\\s+cannot\\s+be\\s+blocked\\s+by\\s+a\\s+Monster\\s+that\\s+is\\s+also\\s+a\\s+Forward[.!]?$"
	);

	/**
	 * "[card] cannot form parties." — Berserker 3-091C. A restriction on joining a party, not on
	 * attacking: the card may still attack on its own. Group: {@code card}.
	 */
	static final Pattern FA_SELF_CANNOT_FORM_PARTIES = Pattern.compile(
		"(?i)^(?<card>.+?)\\s+cannot\\s+form\\s+parties[.!]?$"
	);

	/**
	 * "[card] can only attack if you control N or more Forwards, or if you control a Job [job]
	 * Forward other than [card]." — Elena 11-088R.
	 *
	 * <p>Read directly rather than through {@link ControlCondition}: the two arms differ in both
	 * count and filter, and the second carries a name exclusion, which that record's per-card
	 * {@code orAlternatives} cannot express — it ORs filters within one count, not whole conditions.
	 * Groups: {@code card}, {@code count}, {@code job}, {@code except}.
	 */
	static final Pattern FA_SELF_ATTACK_REQUIRES_CONTROL = Pattern.compile(
		"(?i)^(?<card>.+?)\\s+can\\s+only\\s+attack\\s+if\\s+you\\s+control\\s+(?<count>\\d+)\\s+or\\s+more\\s+Forwards,?" +
		"\\s+or\\s+if\\s+you\\s+control\\s+an?\\s+Job\\s+(?<job>.+?)\\s+Forward\\s+other\\s+than\\s+(?<except>.+?)[.!]?$"
	);

	/**
	 * Outgoing battle-damage boost from any friendly Forward: "If a Forward you control deals battle
	 * damage to a Forward, the damage increases by N instead." — Tulien 21-072H.
	 *
	 * <p>The unfiltered counterpart of {@link #FA_ELEMENT_FORWARD_DAMAGE_BOOST}, kept as its own
	 * pattern rather than made by relaxing that one's element group: optional there would let it
	 * claim this text with a null element, and an element-scoped card would then boost every Forward.
	 * Group: {@code amount}.
	 */
	static final Pattern FA_FRIENDLY_FORWARD_BATTLE_DAMAGE_BOOST = Pattern.compile(
		"(?i)^If\\s+a\\s+Forward\\s+you\\s+control\\s+deals?\\s+battle\\s+damage\\s+to\\s+a\\s+Forward,\\s+" +
		"the\\s+damage\\s+increases\\s+by\\s+(?<amount>\\d+)\\s+instead[.!]?$"
	);

	/**
	 * "The cost required for the Characters opponent controls to use action abilities is increased
	 * by 《N》." — The Emperor 20-092R.
	 *
	 * <p>Read off the <em>opposing</em> field, like the Haste-suppression sentences: whoever
	 * controls this taxes the other player.
	 *
	 * <p>The 《N》 markup reaches this intact — {@code SUMMON_MARKUP} strips only {@code [[…]]} tags
	 * — so the guillemets are matched rather than assumed away. The bare form is accepted too, so a
	 * reprint that drops the markup still reads.
	 * Group: {@code amount}.
	 */
	static final Pattern FA_OPP_ACTION_ABILITY_COST_INCREASE = Pattern.compile(
		"(?i)^The\\s+cost\\s+required\\s+for\\s+the\\s+Characters\\s+opponent\\s+controls\\s+" +
		"to\\s+use\\s+action\\s+abilities\\s+is\\s+increased\\s+by\\s+" +
		"(?:《(?<amount>\\d+)》|(?<bare>\\d+))[.!]?$"
	);

	/** The 《N》 or bare amount from a {@link #FA_OPP_ACTION_ABILITY_COST_INCREASE} match. */
	static int actionAbilityCostIncreaseAmount(Matcher m) {
		return Integer.parseInt(m.group("amount") != null ? m.group("amount") : m.group("bare"));
	}

	/**
	 * "Your opponent may only declare as many attacks in the same turn as the number of Backups
	 * they control." — The Night Dancer 17-078R.
	 *
	 * <p>A cap on attack <em>declarations</em>, not on attackers: a party attack is one declaration
	 * however many Forwards join it, which is the unit {@code PlayerTurnState.attackDeclarationsThisTurn}
	 * already counts for Folka 22-104R's one-shot version.
	 *
	 * <p>"they" is the opponent — the attacking player counts their own Backups, not this card's
	 * controller's. The count is live, so a Backup entering mid-phase raises the cap and one
	 * leaving lowers it, which is why this is evaluated at declaration time rather than written
	 * into the turn state the way Folka's is.
	 */
	static final Pattern FA_OPP_ATTACKS_LIMITED_BY_OWN_BACKUPS = Pattern.compile(
		"(?i)^Your\\s+opponent\\s+may\\s+only\\s+declare\\s+as\\s+many\\s+attacks\\s+in\\s+the\\s+same\\s+turn\\s+" +
		"as\\s+the\\s+number\\s+of\\s+Backups\\s+they\\s+control[.!]?$"
	);

	/**
	 * "[Summons and/or ]abilities of your opponent must choose [cardName] if possible." — the
	 * targeting counterpart of {@link #FA_OPPONENT_MUST_BLOCK}: while the named card is a legal
	 * target, the opposing player's effects have to point at it.
	 *
	 * <p>Printings differ on the conjunction with no change in meaning — Yaag Rosch 1-174R and
	 * Cecil 1-162R print "Summons or abilities", Auron 16-136S and five others print "Summons and
	 * abilities" — so both are accepted. The Summons half is optional because Angeal 28-060R prints
	 * the abilities-only form, and whether it is present is what decides if Summons are bound.
	 * Groups: {@code summons} (present only when Summons are named), {@code cardname}.
	 */
	static final Pattern FA_OPPONENT_MUST_CHOOSE = Pattern.compile(
		"(?i)^(?:(?<summons>Summons?)\\s+(?:and|or)\\s+)?Abilit(?:y|ies)\\s+of\\s+your\\s+opponent\\s+" +
		"must\\s+choose\\s+(?<cardname>.+?)\\s+if\\s+possible[.!]?$"
	);

	/**
	 * "If [card] deals damage or is dealt damage while dull, the damage becomes 0 instead (this
	 * includes player damage)." — Cagnazzo 2-124H, whose own "When Cagnazzo blocks, dull Cagnazzo"
	 * auto ability is what normally puts it in that state mid-battle.
	 *
	 * <p>One sentence covering three damage paths — outgoing combat damage, damage to the opposing
	 * player, and incoming damage — each gated on the card being dull at the moment the damage
	 * would apply, not when the battle began.
	 * Groups: {@code card}.
	 */
	static final Pattern FA_DAMAGE_ZERO_WHILE_DULL = Pattern.compile(
		"(?i)^If\\s+(?<card>.+?)\\s+deals\\s+damage\\s+(?:or|and)\\s+is\\s+dealt\\s+damage\\s+while\\s+dull,\\s+" +
		"the\\s+damage\\s+becomes\\s+0\\s+instead" +
		"(?:\\s*\\(this\\s+includes\\s+player\\s+damage\\))?[.!]?$"
	);

	/**
	 * "This Forward must block [cardName] if possible." — the blocker-side counterpart of
	 * {@link #FA_OPPONENT_MUST_BLOCK}. That one sits on the attacker and compels <em>any</em>
	 * eligible blocker; this one sits on one specific Forward and compels only that Forward,
	 * and only against the named attacker. Granted until end of turn by Dio 26-075C, so it is
	 * read through {@link MainWindow#effectiveFieldAbilities} rather than off the printed card.
	 */
	static final Pattern FA_THIS_FORWARD_MUST_BLOCK_NAMED = Pattern.compile(
		"(?i)^This\\s+Forward\\s+must\\s+block\\s+(?<cardname>.+?)\\s+if\\s+possible[.!]?$"
	);

	/** "All Forwards lose Haste." — global suppression that strips Haste from every Forward in play. */
	static final Pattern FA_ALL_FORWARDS_LOSE_HASTE = Pattern.compile(
		"(?i)^All\\s+Forwards?\\s+lose\\s+Haste[.!]?$"
	);

	/** "Forwards cannot gain Haste." — global suppression that prevents any Forward from having Haste. */
	static final Pattern FA_FORWARDS_CANNOT_GAIN_HASTE = Pattern.compile(
		"(?i)^Forwards?\\s+cannot\\s+gain\\s+Haste[.!]?$"
	);

	/**
	 * "The Forwards opponent controls lose Haste." — the one-sided twin of
	 * {@link #FA_ALL_FORWARDS_LOSE_HASTE}, suppressing Haste only across the printing card's
	 * opponent's Forwards (The Magus Sisters (XIV) 20-083R).
	 */
	static final Pattern FA_OPP_FORWARDS_LOSE_HASTE = Pattern.compile(
		"(?i)^The\\s+Forwards?\\s+opponent\\s+controls\\s+lose\\s+Haste[.!]?$"
	);

	/**
	 * "During your turn, the Backups opponent controls cannot produce CP." — Titan (XVI) 29-068L.
	 *
	 * <p>Read off the opposing field by {@link MainWindow#backupCpSuppressed}, alongside the
	 * Haste-suppression sentences it is shaped like: the controller of the printing taxes the other
	 * player, and "during your turn" scopes it to the printer's own turn — which is precisely when
	 * the taxed player would be paying at instant speed.
	 */
	static final Pattern FA_OPP_BACKUPS_CANNOT_PRODUCE_CP = Pattern.compile(
		"(?i)^During\\s+your\\s+turn,\\s+the\\s+Backups?\\s+(?:your\\s+)?opponent\\s+controls?\\s+" +
		"cannot\\s+produce\\s+CP[.!]?$"
	);

	/**
	 * "The dull Forwards opponent controls lose their abilities." — Gentiana 11-033R.
	 *
	 * <p>Not the field-ability form of Halicarnassus 7-119H's "all the Forwards opponent controls
	 * lose their abilities <em>until the end of the turn</em>", which is a one-shot that writes into
	 * {@code lostAbilitiesCards} and schedules its own removal. This one carries no duration and a
	 * state filter, so it has to be a live query: a Forward it covers gets its abilities back the
	 * moment it activates, and there is no event to hang that restoration on. It is answered inside
	 * {@code MainWindow.lostAbilitiesCards}'s own membership test for that reason.
	 *
	 * <p>"their abilities" and "all abilities" are the same statement, so both spellings are taken.
	 */
	static final Pattern FA_OPP_DULL_FORWARDS_LOSE_ABILITIES = Pattern.compile(
		"(?i)^The\\s+dull\\s+Forwards?\\s+(?:your\\s+)?opponent\\s+controls?\\s+" +
		"lose\\s+(?:their|all)\\s+abilities[.!]?$"
	);

	/** "The Forwards opponent controls cannot gain Haste." — one-sided twin of {@link #FA_FORWARDS_CANNOT_GAIN_HASTE}. */
	static final Pattern FA_OPP_FORWARDS_CANNOT_GAIN_HASTE = Pattern.compile(
		"(?i)^The\\s+Forwards?\\s+opponent\\s+controls\\s+cannot\\s+gain\\s+Haste[.!]?$"
	);

	/**
	 * "If you receive damage while [cardName] is active, dull [cardName]. The damage becomes 0 instead."
	 * Groups: {@code card} (the self-dulling card name that must be active).
	 */
	static final Pattern FA_RECV_PLAYER_DAMAGE_ACTIVE_DULL_ZERO = Pattern.compile(
		"(?i)^If\\s+you\\s+receive\\s+damage\\s+while\\s+(?<card>.+?)\\s+is\\s+active,\\s+" +
		"dull\\s+(?<dullcard>.+?)[.,]?\\s+The\\s+damage\\s+becomes\\s+0\\s+instead[.!]?$"
	);

	/** "If [card] receives damage while dull, the damage is reduced by N instead." */
	static final Pattern FA_DAMAGE_WHILE_DULL_REDUCTION = Pattern.compile(
		"(?i)^If\\s+(?<card>.+?)\\s+(?:receives|is\\s+dealt)\\s+damage\\s+while\\s+dull,\\s+" +
		"the\\s+damage\\s+is\\s+reduced\\s+by\\s+(?<amount>\\d+)\\s+instead[.!]?$"
	);

	/**
	 * "If [card] is dealt damage by a Forward with [Trait1] or [Trait2], the damage becomes 0 instead."
	 * Nullifies battle damage when the attacking Forward has any of the listed traits.
	 * Groups: {@code card}, {@code trait1}, {@code trait2} (optional).
	 */
	static final Pattern FA_NULLIFY_TRAIT_FORWARD_DAMAGE = Pattern.compile(
		"(?i)^If\\s+(?<card>.+?)\\s+is\\s+dealt\\s+damage\\s+by\\s+a\\s+Forward\\s+with\\s+" +
		"(?<trait1>[^,]+?)(?:\\s+or\\s+(?<trait2>[^,]+?))?" +
		",\\s+the\\s+damage\\s+becomes\\s+0\\s+instead[.!]?$"
	);

	/**
	 * "During each turn, if [card] is dealt damage by your opponent's Summons or abilities for the
	 * first time in that turn, the damage becomes 0 instead." — Edge 15-045H.
	 *
	 * <p>A once-per-turn replacement rather than a standing one, so unlike
	 * {@link #FA_NULLIFY_OPPONENT_ABILITY_DAMAGE} it has to record that it fired. The slot is spent
	 * only on a resolution it actually claims — damage that is already 0, or that comes from the
	 * carrier's own side, leaves the shield up.
	 *
	 * <p>"Summons or abilities" names both, so no distinction is drawn between the two; what the
	 * clause does exclude is battle damage, and anything originating on the carrier's own side.
	 * Group: {@code card}.
	 */
	static final Pattern FA_FIRST_OPP_EFFECT_DAMAGE_ZERO_EACH_TURN = Pattern.compile(
		"(?i)^During\\s+each\\s+turn,\\s+if\\s+(?<card>.+?)\\s+is\\s+dealt\\s+damage\\s+by\\s+" +
		"your\\s+opponent's\\s+Summons?(?:\\s+or\\s+abilit(?:y|ies))?\\s+" +
		"for\\s+the\\s+first\\s+time\\s+in\\s+that\\s+turn,\\s+" +
		"the\\s+damage\\s+becomes\\s+0\\s+instead[.!]?$"
	);

	/** "If [name] deals damage to a Forward due to an ability, double the damage instead." */
	static final Pattern FA_DOUBLE_ABILITY_DAMAGE =
			Pattern.compile(
				"(?i)If\\s+(?<name>.+?)\\s+deals?\\s+damage\\s+to\\s+a\\s+Forward\\s+due\\s+to\\s+an\\s+ability,\\s+double\\s+the\\s+damage\\s+instead[.!]?"
			);

	/**
	 * "You can discard N Job [Job] (instead of paying the CP cost) to cast [CardName]."
	 * An alternate cast cost for a named card: discard matching cards from hand (no CP generated)
	 * instead of paying the normal cost.
	 * Groups: {@code count}, {@code job}, {@code target} (card name to cast).
	 *
	 * <p>King 9-010R prints the tail as "to play King from your hand onto the field" rather than
	 * "to cast King". Same cost, same timing — the only cast a hand card has — so the tail carries
	 * both. The {@code target} group stops before "from your hand" so the name stays a name.
	 *
	 * <p>Both printings in the corpus put this sentence on the card the cost buys, so the entry has
	 * to be read off the card in hand as well as off the field; see
	 * {@code MainWindow.findDiscardCastGrants}.
	 */
	static final Pattern FA_DISCARD_JOB_TO_CAST = Pattern.compile(
		"(?i)^You\\s+can\\s+discard\\s+(?<count>\\d+)\\s+Job\\s+(?<job>.+?)\\s+" +
		"\\(instead\\s+of\\s+paying\\s+the\\s+CP\\s+cost\\)\\s+to\\s+" +
		"(?:cast\\s+(?<target>[^.!]+?)|play\\s+(?<playtarget>[^.!]+?)\\s+from\\s+your\\s+hand\\s+onto\\s+the\\s+field)" +
		"\\s*\\.?$"
	);

	/** The card named by {@link #FA_DISCARD_JOB_TO_CAST}, whichever of its two tails matched. */
	static String discardJobToCastTarget(Matcher m) {
		String target = m.group("target") != null ? m.group("target") : m.group("playtarget");
		return target == null ? null : target.trim();
	}

	/**
	 * Matches "select [up to] N of the M following actions. "action1" "action2" ..."
	 * with an optional leading "if condition, " clause.
	 * <ul>
	 *   <li>{@code condition} — optional "if" clause text (without "if " prefix), e.g.
	 *       {@code "you control a Job AVALANCHE Operative Forward"}</li>
	 *   <li>{@code upTo}     — non-null when "up to" is present</li>
	 *   <li>{@code select}   — how many actions the player chooses</li>
	 *   <li>{@code total}    — total number of options listed</li>
	 *   <li>{@code actions}  — the remainder containing the quoted action strings</li>
	 * </ul>
	 */
	private static final Pattern FA_SELECT_FOLLOWING_ACTIONS =
		Pattern.compile(
			"(?i)^(?:if\\s+(?<condition>[^,]+),\\s+)?select\\s+(?<upTo>up\\s+to\\s+)?" +
			"(?<select>\\d+)\\s+of\\s+the\\s+(?<total>\\d+)\\s+following\\s+actions?[.!]?\\s*" +
			"(?<actions>.+)$",
			Pattern.DOTALL
		);

	/**
	 * Matches "select the following actions from top to bottom up to the same number of Elements
	 * other than [excludeelem] as the cost you paid to cast [cardname]. "a." "b." ..."
	 * Groups: {@code excludeelem}, {@code cardname}, {@code actions}.
	 */
	private static final Pattern FA_SELECT_FOLLOWING_ACTIONS_DYNAMIC_ELEMENTS = Pattern.compile(
		"(?i)^select\\s+the\\s+following\\s+actions?\\s+from\\s+top\\s+to\\s+bottom\\s+" +
		"up\\s+to\\s+the\\s+same\\s+number\\s+of\\s+Elements?\\s+other\\s+than\\s+" +
		"(?<excludeelem>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+" +
		"as\\s+the\\s+cost\\s+you\\s+paid\\s+to\\s+cast\\s+(?<cardname>.+?)[.!]?\\s*" +
		"(?<actions>.+)$",
		Pattern.DOTALL
	);

	/**
	 * Matches "reveal any number of Summons from your hand.
	 * When you reveal no Summons, [effect0].
	 * When you reveal N or more Summons, [effectN]."
	 */
	private static final Pattern FA_REVEAL_SUMMONS_CONDITIONAL = Pattern.compile(
		"(?i)^reveal\\s+any\\s+number\\s+of\\s+Summons?\\s+from\\s+your\\s+hand[.,]?\\s+" +
		"When\\s+you\\s+reveal\\s+no\\s+Summons?,?\\s+(?<effect0>.+?)[.]\\s+" +
		"When\\s+you\\s+reveal\\s+(?<n>\\d+)\\s+or\\s+more\\s+Summons?,?\\s+(?<effectN>.+?)$",
		Pattern.DOTALL
	);

	/**
	 * Matches "reveal any number of Summons from your hand. When you do so, [effect]" where the
	 * effect counts "up to the same number of [Type] as the Summons you revealed" — 15-037L Terra.
	 *
	 * <p>Sibling of {@link #FA_REVEAL_SUMMONS_CONDITIONAL} and mutually exclusive with it: that one
	 * branches on how many were revealed, this one uses the number itself as the target count.
	 * Group {@code effect} is the whole follow-up sentence, {@code type} the counted noun.
	 */
	private static final Pattern FA_REVEAL_SUMMONS_SAME_NUMBER = Pattern.compile(
		"(?i)^reveal\\s+any\\s+number\\s+of\\s+Summons?\\s+from\\s+your\\s+hand[.,]?\\s+" +
		"When\\s+you\\s+do\\s+so,?\\s+(?<effect>.*?up\\s+to\\s+the\\s+same\\s+number\\s+of\\s+" +
		"(?<type>Forwards?|Backups?|Monsters?|Characters?)\\s+as\\s+the\\s+Summons?\\s+you\\s+revealed.*)$",
		Pattern.DOTALL
	);

	/** The clause {@link #FA_REVEAL_SUMMONS_SAME_NUMBER} rewrites once the count is known. */
	private static final Pattern SAME_NUMBER_AS_REVEALED = Pattern.compile(
		"(?i)the\\s+same\\s+number\\s+of\\s+(?<type>Forwards?|Backups?|Monsters?|Characters?)" +
		"\\s+as\\s+the\\s+Summons?\\s+you\\s+revealed"
	);

	/** Matches "pay 《cost》[.] When/If you do so, sub-effect[. The maximum you can pay for 《X》 is N]". */
	private static final Pattern FA_PAY_WHEN_DO_SO = Pattern.compile(
		"(?i)^pay\\s+《([^》]+)》[.,]?\\s+(?:When|If)\\s+you\\s+do\\s+so[,.]?\\s+(.+?)(?:[.,]?\\s+The\\s+maximum\\s+you\\s+can\\s+pay\\s+for\\s+《X》\\s+is\\s+\\d+\\.?)?$",
		Pattern.DOTALL
	);
	private static final Pattern FA_MAX_X = Pattern.compile(
		"(?i)The\\s+maximum\\s+you\\s+can\\s+pay\\s+for\\s+《X》\\s+is\\s+(\\d+)"
	);
	private static final Set<String> ELEMENT_NAMES = Set.of(
		"fire", "ice", "wind", "earth", "lightning", "water", "light", "dark"
	);

	/** Strips the "When [name] attacks, " prefix from ICB specialText to extract the effect. */
	private static final Pattern ICB_WHEN_ATTACKS = Pattern.compile(
		"(?i)^When\\s+.+?\\s+attacks?,\\s*(?<effect>.+)$", Pattern.DOTALL
	);

	/**
	 * Returns true if {@code card} has an ETF auto-ability with the reveal-summons-conditional
	 * pattern. Static, so it reads the printed abilities only — granted ones are never of this
	 * shape, and its callers are asking about the card itself rather than a board state.
	 */
	static boolean hasRevealSummonsConditionalEtf(CardData card) {
		for (AutoAbility fa : card.autoAbilities()) {
			if (!fa.trigger().contains("enter")) continue;
			if (FA_REVEAL_SUMMONS_CONDITIONAL.matcher(fa.effectText()).find()) return true;
		}
		return false;
	}

	void triggerAutoAbilitiesForEntersField(CardData card, boolean isP1) {
		triggerAutoAbilitiesForEntersField(card, isP1, false);
	}

	/** @param paidExtraCost whether {@code card}'s optional extra cost was paid when it was cast (threaded to its own "enters the field" trigger only, not to watcher abilities on other cards). */
	void triggerAutoAbilitiesForEntersField(CardData card, boolean isP1, boolean paidExtraCost) {
		if (mw.suppressAutoAbilityForNextCard) {
			mw.suppressAutoAbilityForNextCard = false;
			// Re-evaluate field boosts even when ETF auto-abilities are suppressed
			mw.refreshAllForwardSlots();
			for (int i = 0; i < mw.p2ForwardCards.size(); i++) mw.refreshP2ForwardSlot(i);
			return;
		}
		// Check if the opponent suppresses this Forward's ETF abilities.
		// Suppresses only the entering card's own abilities and the opponent's same-side watchers.
		// The controller's own "enters opponent's field" watchers are NOT suppressed.
		boolean ownEtfSuppressed = card.isForward() && oppSuppressesForwardEtf(!isP1);
		withBatch(() -> {
			if (!ownEtfSuppressed) {
				for (AutoAbility fa : mw.effectiveAutoAbilities(card)) {
					if (!fa.triggerCard().equalsIgnoreCase(card.name())) continue;
					if (!fa.trigger().contains("enter")) continue;
					// "enters your field other than from your hand" — skip when played normally from hand
					if (fa.trigger().equals("enters your field not from hand") && mw.lastCardWasCast) continue;
					executeAutoAbility(fa, card, isP1, paidExtraCost);
				}
				// Watcher dispatch: "When a <Type> enters your field, ..." abilities live on other field cards
				// on the same side as the entering card.
				fireEntersYourFieldWatchers(card, isP1);
				// Also fire watcher abilities on break-zone cards (only those gated by bzConditionCard).
				fireEntersYourFieldBreakZoneWatchers(card, isP1);
			}
			// Watcher dispatch: "When a <Type> of your opponent enters the field, ..." lives on the
			// opponent's cards and uses trigger "enters opponent's field".
			// Not suppressed — the controller still gets their own triggers.
			fireEntersOpponentFieldWatchers(card, isP1);
		});
		// Remedi-style watchers ("a Character enters your opponent's field other than from their
		// hand") — only when the entering card was NOT played from hand. Run inline (outside the
		// batch) with the entering card supplied as the target, so "break it" can act on it.
		if (!mw.lastCardWasCast) fireEntersOpponentFieldNotFromHandWatchers(card, isP1);
		// Re-evaluate all conditional field boosts now that the field composition has changed
		mw.refreshAllForwardSlots();
		for (int i = 0; i < mw.p2ForwardCards.size(); i++) mw.refreshP2ForwardSlot(i);
		mw.showStackWindowIfNeeded();
	}

	/**
	 * Locates {@code card} on its controller's field and returns a {@link ForwardTarget} for it,
	 * or {@code null} if it is not currently on the field.
	 */
	private ForwardTarget enteringCardTarget(CardData card, boolean enteringIsP1) {
		List<CardData> fwds = enteringIsP1 ? mw.p1ForwardCards : mw.p2ForwardCards;
		int fi = fwds.indexOf(card);
		if (fi >= 0) return new ForwardTarget(enteringIsP1, fi, ForwardTarget.CardZone.FORWARD);
		CardData[] bkps = enteringIsP1 ? mw.p1BackupCards : mw.p2BackupCards;
		for (int i = 0; i < bkps.length; i++) if (bkps[i] == card) return new ForwardTarget(enteringIsP1, i, ForwardTarget.CardZone.BACKUP);
		List<CardData> mons = enteringIsP1 ? mw.p1MonsterCards : mw.p2MonsterCards;
		int mi = mons.indexOf(card);
		if (mi >= 0) return new ForwardTarget(enteringIsP1, mi, ForwardTarget.CardZone.MONSTER);
		return null;
	}

	/**
	 * Fires "a &lt;Type&gt; enters your opponent's field other than from their hand" watcher abilities
	 * (Remedi) on the opposite side from {@code enteringCard}. Runs each matching effect inline with
	 * the entering card preloaded as the target, so effects like "break it" act on the entering card.
	 */
	private void fireEntersOpponentFieldNotFromHandWatchers(CardData enteringCard, boolean enteringIsP1) {
		boolean watcherIsP1 = !enteringIsP1;
		ForwardTarget enteringTarget = enteringCardTarget(enteringCard, enteringIsP1);
		List<CardData> fwds = new ArrayList<>(watcherIsP1 ? mw.p1ForwardCards : mw.p2ForwardCards);
		CardData[]     bkps = watcherIsP1 ? mw.p1BackupCards : mw.p2BackupCards;
		List<CardData> mons = new ArrayList<>(watcherIsP1 ? mw.p1MonsterCards : mw.p2MonsterCards);
		for (CardData c : fwds) fireEntersOppNotFromHandWatcher(c, enteringCard, watcherIsP1, enteringTarget);
		for (CardData c : bkps) if (c != null) fireEntersOppNotFromHandWatcher(c, enteringCard, watcherIsP1, enteringTarget);
		for (CardData c : mons) fireEntersOppNotFromHandWatcher(c, enteringCard, watcherIsP1, enteringTarget);
	}

	private void fireEntersOppNotFromHandWatcher(CardData watcher, CardData enteringCard,
			boolean watcherIsP1, ForwardTarget enteringTarget) {
		if (mw.lostAbilitiesCards.contains(watcher)) return;
		for (AutoAbility fa : mw.effectiveAutoAbilities(watcher)) {
			if (!fa.trigger().equals("enters opponent's field not from hand")) continue;
			if (!matchesEntersFieldSubject(fa.triggerCard(), enteringCard, watcher)) continue;
			// Only the "if your opponent doesn't pay 《N》, [action]" form (Remedi) is wired for inline
			// resolution with the entering card as its target. Other watchers of this trigger (e.g. Cid
			// Raines / Jack Garland's "you may put self into the Break Zone. When you do so, …") need
			// their own self-sacrifice + entering-card plumbing and remain dormant for now.
			if (!ActionResolver.isIfOppNotPayAction(fa.effectText())) continue;
			Consumer<GameContext> effect = ActionResolver.parse(fa.effectText(), watcher);
			if (effect == null) continue;
			if (enteringTarget == null) {
				mw.logEntry("[AutoAbility] " + watcher.name() + " — entering card no longer on field; skipped");
				continue;
			}
			GameContext ctx = mw.buildGameContext(watcherIsP1);
			ctx.preloadTargets(List.of(enteringTarget));
			CardData prevSource  = mw.currentAbilitySource;
			boolean  prevSpecial = mw.currentAbilityIsSpecial;
			mw.currentAbilitySource    = watcher;
			mw.currentAbilityIsSpecial = false;
			try {
				mw.logEntry("[AutoAbility] " + watcher.name() + " — " + fa.effectText());
				effect.accept(ctx);
			} finally {
				mw.currentAbilitySource    = prevSource;
				mw.currentAbilityIsSpecial = prevSpecial;
			}
		}
	}

	/** True if the player identified by {@code oppIsP1} controls a card with {@link #FA_OPP_FORWARD_ETF_SUPPRESSED}. */
	private boolean oppSuppressesForwardEtf(boolean oppIsP1) {
		List<CardData> fwds = oppIsP1 ? mw.p1ForwardCards : mw.p2ForwardCards;
		CardData[]     bkps = oppIsP1 ? mw.p1BackupCards  : mw.p2BackupCards;
		List<CardData> mons = oppIsP1 ? mw.p1MonsterCards : mw.p2MonsterCards;
		for (CardData c : fwds) if (!mw.lostAbilitiesCards.contains(c) && hasOppForwardEtfSuppression(c)) return true;
		for (CardData c : bkps) if (c != null && !mw.lostAbilitiesCards.contains(c) && hasOppForwardEtfSuppression(c)) return true;
		for (CardData c : mons) if (!mw.lostAbilitiesCards.contains(c) && hasOppForwardEtfSuppression(c)) return true;
		return false;
	}

	private static boolean hasOppForwardEtfSuppression(CardData card) {
		for (FieldAbility fa : card.fieldAbilities())
			if (FA_OPP_FORWARD_ETF_SUPPRESSED.matcher(fa.effectText()).find()) return true;
		return false;
	}

	/**
	 * Fires "{@code <Type>} enters your field" auto-abilities on other field cards owned by the
	 * same player as {@code enteringCard}. The watcher's {@link AutoAbility#triggerCard()} encodes
	 * the type subject (e.g. "a Monster", "a Forward", "a Character") which is matched against
	 * the entering card's type.
	 */
	private void fireEntersYourFieldWatchers(CardData enteringCard, boolean enteringIsP1) {
		List<CardData> fwds = new ArrayList<>(enteringIsP1 ? mw.p1ForwardCards : mw.p2ForwardCards);
		CardData[]     bkps = enteringIsP1 ? mw.p1BackupCards : mw.p2BackupCards;
		List<CardData> mons = new ArrayList<>(enteringIsP1 ? mw.p1MonsterCards : mw.p2MonsterCards);
		for (CardData c : fwds) fireEntersYourFieldWatcher(c, enteringCard, enteringIsP1);
		for (CardData c : bkps) if (c != null) fireEntersYourFieldWatcher(c, enteringCard, enteringIsP1);
		for (CardData c : mons) fireEntersYourFieldWatcher(c, enteringCard, enteringIsP1);
	}

	private void fireEntersYourFieldWatcher(CardData watcher, CardData enteringCard, boolean enteringIsP1) {
		for (AutoAbility fa : mw.effectiveAutoAbilities(watcher)) {
			if (!fa.trigger().equals("enters your field")) continue;
			if (!matchesEntersFieldSubject(fa.triggerCard(), enteringCard, watcher)) continue;
			executeAutoAbility(fa, watcher, enteringIsP1);
		}
	}

	/**
	 * Fires "enters your field" watcher abilities that live on break-zone cards.
	 * Only abilities with {@link AutoAbility#bzConditionCard()} set are considered — plain
	 * field-watcher abilities on break-zone cards must not fire from there.
	 */
	private void fireEntersYourFieldBreakZoneWatchers(CardData enteringCard, boolean enteringIsP1) {
		List<CardData> bz = new ArrayList<>(enteringIsP1 ? mw.gameState.getP1BreakZone() : mw.gameState.getP2BreakZone());
		for (CardData c : bz) {
			for (AutoAbility fa : mw.effectiveAutoAbilities(c)) {
				if (!fa.trigger().equals("enters your field")) continue;
				if (fa.bzConditionCard().isEmpty()) continue;
				if (!matchesEntersFieldSubject(fa.triggerCard(), enteringCard, c)) continue;
				executeAutoAbility(fa, c, enteringIsP1);
			}
		}
	}

	/**
	 * Fires "enters opponent's field" watcher abilities that live on the opponent's field cards.
	 * Triggered when {@code enteringCard} (owned by {@code enteringIsP1}) enters the field;
	 * watchers on the opposite side use trigger {@code "enters opponent's field"}.
	 */
	private void fireEntersOpponentFieldWatchers(CardData enteringCard, boolean enteringIsP1) {
		boolean watcherIsP1 = !enteringIsP1;
		ForwardTarget enteringTarget = enteringCardTarget(enteringCard, enteringIsP1);
		List<CardData> fwds = new ArrayList<>(watcherIsP1 ? mw.p1ForwardCards : mw.p2ForwardCards);
		CardData[]     bkps = watcherIsP1 ? mw.p1BackupCards : mw.p2BackupCards;
		List<CardData> mons = new ArrayList<>(watcherIsP1 ? mw.p1MonsterCards : mw.p2MonsterCards);
		for (CardData c : fwds) fireEntersOpponentFieldWatcher(c, enteringCard, watcherIsP1, enteringTarget);
		for (CardData c : bkps) if (c != null) fireEntersOpponentFieldWatcher(c, enteringCard, watcherIsP1, enteringTarget);
		for (CardData c : mons) fireEntersOpponentFieldWatcher(c, enteringCard, watcherIsP1, enteringTarget);
	}

	private void fireEntersOpponentFieldWatcher(CardData watcher, CardData enteringCard,
			boolean watcherIsP1, ForwardTarget enteringTarget) {
		for (AutoAbility fa : mw.effectiveAutoAbilities(watcher)) {
			if (!fa.trigger().equals("enters opponent's field")) continue;
			if (!matchesEntersFieldSubject(fa.triggerCard(), enteringCard, watcher)) continue;
			// "dull it and Freeze it" (26-032L Charlotte) names no target of its own — "it" is the
			// card that entered. Run it inline with that card preloaded, as the Remedi-style
			// not-from-hand watchers do; everything else keeps the normal stack path.
			if (ActionResolver.isTriggeredTargetAction(fa.effectText())) {
				runWithEnteringCardAsTarget(fa, watcher, watcherIsP1, enteringTarget);
				continue;
			}
			executeAutoAbility(fa, watcher, watcherIsP1);
		}
	}

	/** Resolves {@code fa} immediately with {@code enteringTarget} preloaded as its target. */
	private void runWithEnteringCardAsTarget(AutoAbility fa, CardData watcher,
			boolean watcherIsP1, ForwardTarget enteringTarget) {
		Consumer<GameContext> effect = ActionResolver.parse(fa.effectText(), watcher);
		if (effect == null) return;
		if (enteringTarget == null) {
			mw.logEntry("[AutoAbility] " + watcher.name() + " — entering card no longer on field; skipped");
			return;
		}
		GameContext ctx = mw.buildGameContext(watcherIsP1);
		ctx.preloadTargets(List.of(enteringTarget));
		CardData prevSource  = mw.currentAbilitySource;
		boolean  prevSpecial = mw.currentAbilityIsSpecial;
		mw.currentAbilitySource    = watcher;
		mw.currentAbilityIsSpecial = false;
		try {
			mw.logEntry("[AutoAbility] " + watcher.name() + " — " + fa.effectText());
			effect.accept(ctx);
		} finally {
			mw.currentAbilitySource    = prevSource;
			mw.currentAbilityIsSpecial = prevSpecial;
		}
	}

	/**
	 * Returns {@code true} if {@code enteringCard} matches the watcher's subject phrase.
	 * Compound disjunctive subjects ("X or a Y or a Card Name Z") produced by
	 * {@code CardData#expandMultiSubjectTriggers} are split on " or " and any matching
	 * sub-subject succeeds. A sub-subject may be:
	 * <ul>
	 *   <li>a bare card name ({@code "Yshe"}) — matched by {@link CardData#name()};</li>
	 *   <li>a type phrase ({@code "a Forward"}, {@code "a Character"}) — matched by card type;</li>
	 *   <li>a job phrase ({@code "a Job Warrior"}) — matched by {@link CardData#hasJob};</li>
	 *   <li>a card-name phrase ({@code "a Card Name Warrior"}) — matched by name/aliases.</li>
	 * </ul>
	 */
	private boolean matchesEntersFieldSubject(String subject, CardData enteringCard, CardData self) {
		if (subject == null || subject.isBlank()) return false;
		for (String part : subject.split("(?i)\\s+or\\s+")) {
			if (matchesSingleSubject(part.trim(), enteringCard, self)) return true;
		}
		return false;
	}

	/**
	 * @param self the card that owns the trigger (the "source"); used to resolve "other than
	 *             [self name]" as a reference to that specific instance rather than every copy
	 *             of the name. May be {@code null} when no source context is available.
	 */
	/**
	 * The Elements an "other than …" exclusion names, or {@code null} when it names something else.
	 *
	 * <p>Only the two-or-more form is read as Elements — "Light and Dark", "Light or Dark", the
	 * nineteen printings that spell it that way. A bare single Element name is deliberately left to
	 * the card-name reading, because the two are genuinely ambiguous there: "a Job Manikin other
	 * than Lightning" (Delusory Warlock 13-070C) and "a Category XIII Character other than
	 * Lightning" (Lightning 4-115L) both mean the character, and every printing that means the
	 * Element instead says "of an Element other than X".
	 */
	private static List<String> excludedElementsOrNull(String phrase) {
		String[] parts = phrase.split("(?i)\\s+(?:and|or)\\s+");
		if (parts.length < 2) return null;
		List<String> out = new ArrayList<>(parts.length);
		for (String part : parts) {
			String name = null;
			for (String e : ActionResolverPatterns.ELEMENT_NAMES)
				if (e.equalsIgnoreCase(part.trim())) { name = e; break; }
			if (name == null) return null;
			out.add(name);
		}
		return out;
	}

	private boolean matchesSingleSubject(String subject, CardData enteringCard, CardData self) {
		if (subject.isEmpty()) return false;
		// "a [X] other than [Name]" — match base subject but exclude the named card
		Matcher otherThanM = java.util.regex.Pattern.compile(
				"(?i)^(.+?)\\s+other\\s+than\\s+(.+)$").matcher(subject);
		if (otherThanM.matches()) {
			String excludeName = otherThanM.group(2).trim();
			if (!matchesSingleSubject(otherThanM.group(1).trim(), enteringCard, self)) return false;
			// "a Forward other than Light and Dark you control" — Elements, not a card name.
			List<String> excludedElements = excludedElementsOrNull(excludeName);
			if (excludedElements != null) {
				for (String e : excludedElements)
					if (mw.effectiveContainsElement(enteringCard, e)) return false;
				return true;
			}
			// "other than [self name]" refers to THIS specific card (the rule that a card naming
			// itself means only that instance), so exclude only the source — another copy of the
			// same name entering still qualifies.
			if (self != null && CardFilters.meetsCardNameFilter(self, excludeName))
				return enteringCard != self;
			return !CardFilters.meetsCardNameFilter(enteringCard, excludeName);
		}
		// "a Job X Forward/Backup/Monster/Character" — job + type (must precede plain "a Job X")
		Matcher jobTypeM = java.util.regex.Pattern.compile(
				"(?i)^an?\\s+Job\\s+(?<job>.+?)\\s+(?<type>Forwards?|Backups?|Monsters?|Characters?)$").matcher(subject);
		if (jobTypeM.matches())
			return enteringCard.hasJob(jobTypeM.group("job").trim())
				&& meetsSubjectTypeFilter(enteringCard, jobTypeM.group("type"));
		// "a Category X Forward/Backup/Monster/Character" — category + type
		Matcher catTypeM = java.util.regex.Pattern.compile(
				"(?i)^an?\\s+Category\\s+(?<cat>.+?)\\s+(?<type>Forwards?|Backups?|Monsters?|Characters?)$").matcher(subject);
		if (catTypeM.matches())
			return CardFilters.meetsCategoryFilter(enteringCard, catTypeM.group("cat").trim())
				&& meetsSubjectTypeFilter(enteringCard, catTypeM.group("type"));
		// "a [Element] Forward/Backup/Monster/Character" — element + type (includes Multi-Element)
		Matcher elemTypeM = java.util.regex.Pattern.compile(
				"(?i)^an?\\s+(?<elem>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark|Multi-Element)\\s+(?<type>Forwards?|Backups?|Monsters?|Characters?)$").matcher(subject);
		if (elemTypeM.matches())
			return mw.effectiveContainsElement(enteringCard, elemTypeM.group("elem"))
				&& meetsSubjectTypeFilter(enteringCard, elemTypeM.group("type"));
		// "a Job X" / "an Job X" — match by job (any type)
		Matcher jobM = java.util.regex.Pattern.compile(
				"(?i)^an?\\s+Job\\s+(?<job>.+)$").matcher(subject);
		if (jobM.matches()) return enteringCard.hasJob(jobM.group("job").trim());
		// "a Card Name X" — match by card name or alias
		Matcher nameM = java.util.regex.Pattern.compile(
				"(?i)^an?\\s+Card\\s+Name\\s+(?<name>.+)$").matcher(subject);
		if (nameM.matches()) return CardFilters.meetsCardNameFilter(enteringCard, nameM.group("name").trim());
		// "a [Type]" — match by card type
		String s = subject.toLowerCase(java.util.Locale.ROOT).replaceAll("^(?:a|an)\\s+", "");
		switch (s) {
			case "monster", "monsters"     -> { return enteringCard.isMonster(); }
			case "forward", "forwards"     -> { return enteringCard.isForward(); }
			case "backup", "backups"       -> { return enteringCard.isBackup(); }
			case "summon", "summons"       -> { return enteringCard.isSummon(); }
			case "character", "characters" -> { return enteringCard.isForward() || enteringCard.isBackup() || enteringCard.isMonster(); }
		}
		// Bare card name (e.g. "Yshe") — exact name or alias match
		return CardFilters.meetsCardNameFilter(enteringCard, subject);
	}

	private boolean meetsSubjectTypeFilter(CardData c, String type) {
		return switch (type.toLowerCase(java.util.Locale.ROOT).replaceAll("s$", "")) {
			case "forward"   -> c.isForward();
			case "backup"    -> c.isBackup();
			case "monster"   -> c.isMonster();
			case "character" -> c.isForward() || c.isBackup() || c.isMonster();
			default          -> false;
		};
	}

	private static final java.util.regex.Pattern OTHER_FORWARD_SUBJECT_WITH_NAME =
			java.util.regex.Pattern.compile(
				"(?i)^a\\s+Forward\\s+other\\s+than\\s+(?<name>.+?)\\s+you\\s+control$");

	/**
	 * Returns true when {@code attacker} matches "a Forward other than [excluded] you control", or
	 * the unrestricted "a Forward you control" (Cloud 1-187S), which excludes nobody — the carrier
	 * attacking answers its own subject.
	 */
	private boolean matchesOtherForwardSubject(String triggerCard, CardData attacker) {
		if (CardData.ANY_OWN_FORWARD_SUBJECT.matcher(triggerCard.trim()).matches())
			return attacker.isForward();
		java.util.regex.Matcher m = OTHER_FORWARD_SUBJECT_WITH_NAME.matcher(triggerCard);
		if (!m.matches()) return false;
		String excludedName = m.group("name").trim();
		return attacker.isForward() && !CardFilters.meetsCardNameFilter(attacker, excludedName);
	}

	/**
	 * Returns true when {@code attacker} satisfies a {@link CardData#FILTER_FORWARD_SUBJECT}
	 * subject — "[a | N or more] Job X [or a Card Name Y] [Forward(s)] [other than Z] you control".
	 */
	private boolean matchesFilteredForwardSubject(String triggerCard, CardData attacker) {
		java.util.regex.Matcher m = CardData.FILTER_FORWARD_SUBJECT.matcher(triggerCard);
		if (!m.matches()) return false;
		// "…Forwards…" restricts the trigger to actual Forwards; without the noun any attacking
		// card type qualifies, which is what the plain "a Job X you control" subjects expect.
		if (m.group("fwdnoun") != null && !attacker.isForward()) return false;
		String exclude = m.group("exclude");
		if (exclude != null && CardFilters.meetsCardNameFilter(attacker, exclude.trim())) return false;
		String type1 = m.group("type1").trim();
		String val1  = m.group("val1").trim();
		String type2 = m.group("type2") != null ? m.group("type2").trim() : null;
		String val2  = m.group("val2")  != null ? m.group("val2").trim()  : null;
		boolean matches = type1.equalsIgnoreCase("Job")
				? mw.meetsJobFilterEffective(attacker, val1)
				: CardFilters.meetsCardNameFilter(attacker, val1);
		if (!matches && type2 != null) {
			matches = type2.equalsIgnoreCase("Job")
					? mw.meetsJobFilterEffective(attacker, val2)
					: CardFilters.meetsCardNameFilter(attacker, val2);
		}
		return matches;
	}

	/**
	 * True when {@code triggerCard} uses the "N or more …" count form, which describes the attack
	 * declaration as a whole rather than an individual attacker.
	 */
	private static boolean isCountFormSubject(String triggerCard) {
		java.util.regex.Matcher m = CardData.FILTER_FORWARD_SUBJECT.matcher(triggerCard);
		return m.matches() && m.group("count") != null;
	}

	/** One count-form watcher ability that has already fired for the current attack declaration. */
	private record DeclarationFire(CardData watcher, AutoAbility ability) {}

	final Set<DeclarationFire> firedThisDeclaration = new HashSet<>();
	private int     lastDeclarationSeen   = -1;
	private boolean lastDeclarationWasP1;

	/**
	 * Resets the once-per-declaration guard when a new attack declaration begins. Attack triggers
	 * fire once per attacker, so the declaration counter (bumped at each declaration site before
	 * any trigger runs) is what distinguishes "next member of the same party" from "a new attack".
	 */
	private void startAttackDeclarationScope(boolean isP1) {
		int decl = mw.turn(isP1).attackDeclarationsThisTurn;
		if (decl != lastDeclarationSeen || isP1 != lastDeclarationWasP1) {
			firedThisDeclaration.clear();
			lastDeclarationSeen  = decl;
			lastDeclarationWasP1 = isP1;
		}
	}

	void triggerAutoAbilitiesForDealsDamageToOpponent(CardData attacker, boolean attackerIsP1) {
		withBatch(() -> {
			for (AutoAbility fa : mw.effectiveAutoAbilities(attacker)) {
				if (!fa.triggerCard().equalsIgnoreCase(attacker.name())) continue;
				if (fa.trigger().equals("deals damage to opponent")) executeAutoAbility(fa, attacker, attackerIsP1);
			}
		});
		mw.showStackWindowIfNeeded();
	}

	void triggerAutoAbilitiesForPrimedInto(CardData primingCard, CardData primedCard, boolean primedCardIsP1) {
		withBatch(() -> {
			for (AutoAbility fa : mw.effectiveAutoAbilities(primedCard)) {
				if (!fa.triggerCard().equalsIgnoreCase(primingCard.name())) continue;
				if (fa.trigger().equals("primed into")) executeAutoAbility(fa, primedCard, primedCardIsP1);
			}
		});
		mw.showStackWindowIfNeeded();
	}

	/**
	 * Fires "When [subject] is priming" abilities on {@code primingCard}'s controller's field.
	 *
	 * <p>Priming does not use the stack, so nothing downstream would ever see it — this is called
	 * from the two sites where a prime completes, alongside the "primed into" dispatch that watches
	 * the other end of the same act.
	 *
	 * <p>The walk reads each Forward slot through its primed top card, as the attack and damage
	 * dispatches do, so a Character that has already been replaced by its Eikon no longer watches.
	 * {@code primingCard} is added back explicitly for exactly that reason: its own top card was
	 * set just before this call, and its ability was still live at the instant it paid.
	 */
	void triggerAutoAbilitiesForPriming(CardData primingCard, boolean isP1) {
		withBatch(() -> {
			List<CardData> watchers = new ArrayList<>();
			watchers.add(primingCard);
			List<CardData> fwds = isP1 ? mw.p1ForwardCards     : mw.p2ForwardCards;
			List<CardData> tops = isP1 ? mw.p1ForwardPrimedTop : mw.p2ForwardPrimedTop;
			for (int i = 0; i < fwds.size(); i++) {
				CardData top = i < tops.size() ? tops.get(i) : null;
				CardData eff = top != null ? top : fwds.get(i);
				if (eff != primingCard) watchers.add(eff);
			}
			for (CardData c : (isP1 ? mw.p1BackupCards : mw.p2BackupCards)) if (c != null) watchers.add(c);
			watchers.addAll(isP1 ? mw.p1MonsterCards : mw.p2MonsterCards);

			for (CardData watcher : watchers)
				for (AutoAbility fa : mw.effectiveAutoAbilities(watcher))
					if (fa.trigger().equals("is priming")
							&& matchesPrimingSubject(fa.triggerCard(), watcher, primingCard))
						executeAutoAbility(fa, watcher, isP1);
		});
		mw.showStackWindowIfNeeded();
	}

	/**
	 * Returns true when {@code priming} satisfies an "is priming" trigger's subject.
	 *
	 * <p>Shares the reading {@link #matchesChosenSubject} uses: a part naming the watcher itself is
	 * matched by <em>identity</em> — a card naming itself means that copy, so a second Dion priming
	 * must not fire the first one's ability — while every other part is a filter over the priming
	 * card. Both printed shapes are disjunctions ("Dion or a Character you control"), whose second
	 * half subsumes the first; the identity reading is what keeps the halves from disagreeing when
	 * a card of the same name primes on the same field.
	 */
	private boolean matchesPrimingSubject(String subject, CardData watcher, CardData priming) {
		if (subject == null || subject.isBlank()) return priming == watcher;
		for (String rawPart : subject.trim().split("(?i)\\s+or\\s+")) {
			String part = TRIGGER_SUBJECT_CTRL.matcher(rawPart.trim()).replaceFirst("").trim();
			if (part.isEmpty()) continue;
			if (CardFilters.meetsCardNameFilter(watcher, part)) {
				if (priming == watcher) return true;
				continue;
			}
			if (matchesSingleSubject(part, priming, watcher)) return true;
		}
		return false;
	}

	/** "this Forward" and friends — a self-reference spelled without the card's name. */
	private static final Pattern ATTACK_SUBJECT_SELF =
			Pattern.compile("(?i)^this\\s+(?:forward|backup|monster|character)$");

	void triggerAutoAbilitiesForAttack(CardData card, boolean isP1) {
		withBatch(() -> {
			for (AutoAbility fa : mw.effectiveAutoAbilities(card)) {
				// A granted ability spells its subject "this Forward" instead of naming a card, so
				// the name test alone drops it: effectiveAutoAbilities hands this loop the abilities
				// the card was given as well as the ones it prints, and Ellone 27-020R's "When this
				// Forward attacks, draw 1 card." never fired for want of this. No further identity
				// check is needed — every ability in this list is already the attacking card's, the
				// same reasoning matchesChosenSubject and matchesDamagedSubject spell out for the
				// walks that do have to tell watcher from subject.
				if (!fa.triggerCard().equalsIgnoreCase(card.name())
						&& !ATTACK_SUBJECT_SELF.matcher(fa.triggerCard().trim()).matches()) continue;
				if (fa.trigger().contains("attack")) executeAutoAbility(fa, card, isP1);
			}
			// "When 1 or more Forwards you control attack" — fires on any controller field card
			List<CardData> fwds = new ArrayList<>(isP1 ? mw.p1ForwardCards : mw.p2ForwardCards);
			for (CardData c : fwds)
				for (AutoAbility fa : mw.effectiveAutoAbilities(c))
					if (fa.trigger().equals("attack")) executeAutoAbility(fa, c, isP1);
			List<CardData> monsters = new ArrayList<>(isP1 ? mw.p1MonsterCards : mw.p2MonsterCards);
			for (CardData c : monsters)
				for (AutoAbility fa : mw.effectiveAutoAbilities(c))
					if (fa.trigger().equals("attack")) executeAutoAbility(fa, c, isP1);
			CardData[] bkps = isP1 ? mw.p1BackupCards : mw.p2BackupCards;
			for (CardData c : bkps)
				if (c != null)
					for (AutoAbility fa : mw.effectiveAutoAbilities(c))
						if (fa.trigger().equals("attack")) executeAutoAbility(fa, c, isP1);
			// "When a Forward other than [watcherCard] you control attacks" — watcher on same-side field cards
			if (card.isForward()) {
				List<CardData> watchFwds = new ArrayList<>(isP1 ? mw.p1ForwardCards : mw.p2ForwardCards);
				for (CardData watcherCard : watchFwds) {
					if (mw.lostAbilitiesCards.contains(watcherCard)) continue;
					for (AutoAbility fa : mw.effectiveAutoAbilities(watcherCard)) {
						if (!fa.trigger().equals("other forward attacks")) continue;
						if (!matchesOtherForwardSubject(fa.triggerCard(), card)) continue;
						Consumer<GameContext> effect = ActionResolver.parse(fa.effectText(), card);
						if (effect == null) {
							mw.logEntry("[AutoAbility] Unrecognized 'other forward attacks' effect: " + fa.effectText());
							continue;
						}
						mw.logEntry("[AutoAbility] " + watcherCard.name() + " — " + card.name() + " attacks, effect: " + fa.effectText());
						effect.accept(mw.buildGameContext(isP1));
					}
				}
			}
			// "When a Job X or Card Name Y you control attacks" — filtered watcher on all same-side cards
			{
				startAttackDeclarationScope(isP1);
				List<CardData> allWatchers = new ArrayList<>();
				for (CardData c : isP1 ? mw.p1ForwardCards : mw.p2ForwardCards) allWatchers.add(c);
				for (CardData c : isP1 ? mw.p1BackupCards  : mw.p2BackupCards)  if (c != null) allWatchers.add(c);
				for (CardData c : isP1 ? mw.p1MonsterCards : mw.p2MonsterCards) allWatchers.add(c);
				for (CardData watcherCard : allWatchers) {
					if (mw.lostAbilitiesCards.contains(watcherCard)) continue;
					for (AutoAbility fa : mw.effectiveAutoAbilities(watcherCard)) {
						if (!fa.trigger().equals("filtered forward attacks")) continue;
						if (!matchesFilteredForwardSubject(fa.triggerCard(), card)) continue;
						// A count-form subject ("1 or more …") is one event for the whole declaration:
						// a party of three qualifying attackers still fires it once. This method runs
						// per attacker, so the second and later members must be dropped here.
						if (isCountFormSubject(fa.triggerCard())
								&& !firedThisDeclaration.add(new DeclarationFire(watcherCard, fa)))
							continue;
						Consumer<GameContext> effect = ActionResolver.parse(fa.effectText(), watcherCard);
						if (effect == null) {
							mw.logEntry("[AutoAbility] Unrecognized 'filtered forward attacks' effect: " + fa.effectText());
							continue;
						}
						mw.logEntry("[AutoAbility] " + watcherCard.name() + " — " + card.name() + " attacks, effect: " + fa.effectText());
						effect.accept(mw.buildGameContext(isP1));
					}
				}
			}
			// ICB specialText — "When [name] attacks, [effect]" granted by conditional field boosts
			List<CardData> icbOwners = new ArrayList<>();
			for (CardData c : isP1 ? mw.p1ForwardCards : mw.p2ForwardCards) icbOwners.add(c);
			for (CardData c : isP1 ? mw.p1BackupCards  : mw.p2BackupCards)  if (c != null) icbOwners.add(c);
			for (CardData c : isP1 ? mw.p1MonsterCards : mw.p2MonsterCards) icbOwners.add(c);
			for (CardData owner : icbOwners) {
				if (mw.lostAbilitiesCards.contains(owner)) continue;
				for (IfControlBoost icb : owner.ifControlBoosts()) {
					if (icb.specialText().isEmpty()) continue;
					if (!icb.appliesToCard(card)) continue;
					if (!mw.icbConditionsMet(icb, isP1)) continue;
					Matcher stM = ICB_WHEN_ATTACKS.matcher(icb.specialText().trim());
					if (!stM.find()) continue;
					String effectText = stM.group("effect").trim();
					Consumer<GameContext> effect = ActionResolver.parse(effectText, card);
					if (effect == null) {
						mw.logEntry("[AutoAbility] Unrecognized ICB specialText attack effect: " + effectText);
						continue;
					}
					mw.logEntry("[AutoAbility] " + card.name() + " attacks — " + effectText);
					effect.accept(mw.buildGameContext(isP1));
				}
			}
		});
		// Fire any temporary attack triggers registered this turn by action abilities
		Map<CardData, List<Consumer<GameContext>>> tempTriggers
				= isP1 ? mw.p1TempAttackTriggers : mw.p2TempAttackTriggers;
		List<Consumer<GameContext>> effects = tempTriggers.get(card);
		if (effects != null) {
			GameContext ctx = mw.buildGameContext(isP1);
			for (Consumer<GameContext> effect : effects)
				effect.accept(ctx);
		}
		mw.showStackWindowIfNeeded();
	}

	void triggerAutoAbilitiesForBlock(CardData card, boolean isP1) {
		withBatch(() -> {
			for (AutoAbility fa : mw.effectiveAutoAbilities(card)) {
				if (!fa.triggerCard().equalsIgnoreCase(card.name())) continue;
				String t = fa.trigger();
				if (t.equals("blocks") || t.equals("attacks or blocks") || t.equals("blocks or is blocked"))
					executeAutoAbility(fa, card, isP1);
			}
		});
		Map<CardData, List<Consumer<GameContext>>> tempTriggers
				= isP1 ? mw.p1TempBlockTriggers : mw.p2TempBlockTriggers;
		List<Consumer<GameContext>> effects = tempTriggers.get(card);
		if (effects != null) {
			GameContext ctx = mw.buildGameContext(isP1);
			for (Consumer<GameContext> effect : effects)
				effect.accept(ctx);
		}
		mw.showStackWindowIfNeeded();
	}

	void triggerAutoAbilitiesForIsBlocked(CardData card, boolean isP1) {
		withBatch(() -> {
			for (AutoAbility fa : mw.effectiveAutoAbilities(card)) {
				if (!fa.triggerCard().equalsIgnoreCase(card.name())) continue;
				String t = fa.trigger();
				if (t.equals("is blocked") || t.equals("blocks or is blocked"))
					executeAutoAbility(fa, card, isP1);
			}
		});
		// The granted half, which only the block path used to read: 4-142R Malboro's "When Malboro
		// blocks or is blocked" fired when it blocked and never when it attacked and was blocked.
		Map<CardData, List<Consumer<GameContext>>> tempTriggers
				= isP1 ? mw.p1TempIsBlockedTriggers : mw.p2TempIsBlockedTriggers;
		List<Consumer<GameContext>> effects = tempTriggers.get(card);
		if (effects != null) {
			GameContext ctx = mw.buildGameContext(isP1);
			for (Consumer<GameContext> effect : effects)
				effect.accept(ctx);
		}
		mw.showStackWindowIfNeeded();
	}

	/**
	 * Fires "party attacks" field abilities on every card the controller has on the field,
	 * filtering by any party-composition requirements encoded in the {@link AutoAbility}.
	 *
	 * @param partyMembers the CardData objects that are attacking in the party
	 */
	void triggerAutoAbilitiesForPartyAttack(boolean isP1, List<CardData> partyMembers) {
		// Record the attacking party so "all Forwards in that party" followups can act on it
		// when their auto-ability resolves off the stack (see applyCurrentPartyForwardsPowerBoost).
		if (isP1) mw.p1Turn.currentPartyAttackers = new ArrayList<>(partyMembers);
		else      mw.p2Turn.currentPartyAttackers = new ArrayList<>(partyMembers);
		withBatch(() -> {
			List<CardData> fwds = new ArrayList<>(isP1 ? mw.p1ForwardCards : mw.p2ForwardCards);
			for (CardData card : fwds) {
				for (AutoAbility fa : mw.effectiveAutoAbilities(card)) {
					if (!fa.trigger().equals("party attacks")) continue;
					if (!partyAttackMatchesFilter(fa, partyMembers)) continue;
					executeAutoAbility(fa, card, isP1);
				}
			}
			CardData[] bkps = isP1 ? mw.p1BackupCards : mw.p2BackupCards;
			for (CardData card : bkps) {
				if (card == null) continue;
				for (AutoAbility fa : mw.effectiveAutoAbilities(card)) {
					if (!fa.trigger().equals("party attacks")) continue;
					if (!partyAttackMatchesFilter(fa, partyMembers)) continue;
					executeAutoAbility(fa, card, isP1);
				}
			}
		});
		mw.showStackWindowIfNeeded();
	}

	/** Returns true when the party composition satisfies all filter fields of a "party attacks" ability. */
	private boolean partyAttackMatchesFilter(AutoAbility fa, List<CardData> partyMembers) {
		if (fa.partyCardName() != null) {
			boolean found = partyMembers.stream()
					.anyMatch(m -> meetsCardNameFilter(m, fa.partyCardName()));
			if (!found) return false;
		}
		if (fa.partyMinCount() > 0) {
			long qualifying = partyMembers.stream()
					.filter(m -> partyMemberMatchesCountFilter(m, fa))
					.count();
			if (qualifying < fa.partyMinCount()) return false;
		}
		return true;
	}

	/** Returns true when {@code member} satisfies the category/job filter of a party-attack ability. */
	private boolean partyMemberMatchesCountFilter(CardData member, AutoAbility fa) {
		if (fa.partyCategory() != null) {
			boolean hasCategory =
					(member.category1() != null && member.category1().equalsIgnoreCase(fa.partyCategory())) ||
					(member.category2() != null && member.category2().equalsIgnoreCase(fa.partyCategory()));
			if (!hasCategory) return false;
		}
		if (fa.partyJob() != null) {
			boolean hasJob = member.jobs().stream()
					.anyMatch(j -> j.equalsIgnoreCase(fa.partyJob()));
			if (!hasJob) return false;
		}
		return true;
	}

	/**
	 * Trailing "you control" / "opponent controls" suffix on a break-zone trigger subject.
	 * Used to extract the controller check, leaving the filter clause(s) for separate matching.
	 */
	private static final Pattern BZ_SUBJECT_CTRL = Pattern.compile(
		"(?i)\\s+(?<ctrl>you|opponent)\\s+controls?$"
	);
	/** "Chocobo forming a party" — fires when the named card itself was in a party when broken. */
	private static final Pattern BZ_SUBJECT_SELF_PARTY = Pattern.compile(
		"(?i)^(?<name>.+?)\\s+forming\\s+a\\s+party$"
	);
	/** "a Forward forming a party with Bobby Corwen" — fires when another party member of the source card is broken. */
	private static final Pattern BZ_SUBJECT_PARTY_MEMBER = Pattern.compile(
		"(?i)^a\\s+Forward\\s+forming\\s+a\\s+party\\s+with\\s+(?<name>.+?)$"
	);

	/**
	 * Returns true when the broken card satisfies the break-zone trigger subject of {@code fa}.
	 * Handles named cards ("Geomancer"), type+controller phrases ("a Forward you control"),
	 * and "forming a party" variants.
	 *
	 * @param source       the card that owns the auto-ability
	 * @param partyMembers CardData objects that were in the attacker's party when the break occurred
	 */
	private boolean matchesBreakZoneSubject(AutoAbility fa, CardData source, CardData broken,
			boolean brokenIsP1, boolean abilityOwnerIsP1, Set<CardData> partyMembers) {
		String subject = fa.triggerCard().trim();

		// A card's own name in its own text refers to that card and nothing else, so a self-named
		// subject is settled by identity rather than by name. Without this, every other printing
		// sharing the name answered for it: blocking with Dark Knight 1-054C and losing it fired
		// the opposing Dark Knight 1-055C's "deals you 1 point of damage" — the opponent's card
		// reading a stranger's death as its own.
		if (subjectNamesItsOwnCard(fa, source) && broken != source) return false;

		// "Chocobo forming a party" — broken card is the named card and was in a party
		Matcher selfPartyM = BZ_SUBJECT_SELF_PARTY.matcher(subject);
		if (selfPartyM.matches()) {
			String name = selfPartyM.group("name").trim();
			return broken.name().equalsIgnoreCase(name) && partyMembers.contains(broken);
		}

		// "a Forward forming a party with Bobby Corwen" — another forward in source's party was broken
		Matcher partyMemberM = BZ_SUBJECT_PARTY_MEMBER.matcher(subject);
		if (partyMemberM.matches()) {
			String sourceName = partyMemberM.group("name").trim();
			return broken.isForward()
				&& !broken.name().equalsIgnoreCase(sourceName)
				&& partyMembers.contains(broken)
				&& partyMembers.contains(source);
		}

		// "a [filter] [you|opponent] control[s]" — filter may be a type, Job, Card Name,
		// or an OR combination thereof (e.g. "a Job Warrior or a Card Name Warrior you control")
		Matcher ctrlM = BZ_SUBJECT_CTRL.matcher(subject);
		if (ctrlM.find()) {
			boolean selfCtrl      = ctrlM.group("ctrl").equalsIgnoreCase("you");
			boolean brokenByOwner = (brokenIsP1 == abilityOwnerIsP1);
			if (selfCtrl != brokenByOwner) return false;
			String filters = subject.substring(0, ctrlM.start());
			for (String part : filters.split("(?i)\\s+or\\s+")) {
				if (matchesSingleSubject(part.trim(), broken, source)) return true;
			}
			return false;
		}

		// Fall back to named card match (handles "Geomancer", etc.)
		return broken.name().equalsIgnoreCase(subject);
	}

	/**
	 * True when {@code fa}'s break-zone subject names the card carrying it — "Dark Knight" on Dark
	 * Knight 1-055C, "Chocobo forming a party" on Chocobo 25-045C.
	 *
	 * <p>Such a subject can only ever be answered by the carrier itself, which is why these are
	 * dispatched from the broken card in {@link #triggerAutoAbilitiesForBreakZone} rather than
	 * from the board scan: no card still on the field can be the card that just left it.
	 */
	private static boolean subjectNamesItsOwnCard(AutoAbility fa, CardData source) {
		String subject = fa.triggerCard().trim();
		if (subject.equalsIgnoreCase(source.name())) return true;
		Matcher m = BZ_SUBJECT_SELF_PARTY.matcher(subject);
		return m.matches() && m.group("name").trim().equalsIgnoreCase(source.name());
	}

	/**
	 * Fires "put into break zone" field abilities on all field cards whose subject matches
	 * the card that just broke.  Must be called after the card is removed from the field.
	 *
	 * @param partyMembers the set of CardData objects that were in the attacking party at the time
	 *                     of the break; empty when the break did not occur during a party attack
	 */
	void triggerAutoAbilitiesForBreakZone(CardData broken, boolean brokenIsP1,
			Set<CardData> partyMembers) {
		withBatch(() -> {
			for (int pass = 0; pass < 2; pass++) {
				boolean ownerIsP1 = (pass == 0);
				List<CardData> fwds = new ArrayList<>(ownerIsP1 ? mw.p1ForwardCards : mw.p2ForwardCards);
				CardData[]     bkps = ownerIsP1 ? mw.p1BackupCards : mw.p2BackupCards;
				List<CardData> mons = new ArrayList<>(ownerIsP1 ? mw.p1MonsterCards : mw.p2MonsterCards);
				for (CardData c : fwds) fireBreakZoneTriggers(c, ownerIsP1, broken, brokenIsP1, partyMembers);
				for (CardData c : bkps) if (c != null) fireBreakZoneTriggers(c, ownerIsP1, broken, brokenIsP1, partyMembers);
				for (CardData c : mons) fireBreakZoneTriggers(c, ownerIsP1, broken, brokenIsP1, partyMembers);
			}
			// Fire self-break triggers on the broken card itself. It is no longer in any of the
			// field lists walked above, so nothing there can reach it — including its own
			// "When [card] is put from the field into the Break Zone, …", which is why that one is
			// dispatched here rather than through fireBreakZoneTriggers.
			//
			// Restricted to subjects that name the broken card. A filter subject ("a Forward you
			// control") arguably describes the broken card too, but firing those here would change
			// what every existing watcher does when it is the card that broke; that is a separate
			// question from letting a card see its own departure.
			for (AutoAbility fa : mw.effectiveAutoAbilities(broken)) {
				if (!fa.trigger().equals("enters the field or put into break zone")
						&& !fa.trigger().equals("put into break zone")) continue;
				if (!subjectNamesItsOwnCard(fa, broken)) continue;
				// The subject may still qualify how the card left — "Chocobo forming a party" only
				// answers for a Chocobo that was in one — so it is put through the same matcher the
				// board scan uses, with the broken card standing as its own source.
				if (!matchesBreakZoneSubject(fa, broken, broken, brokenIsP1, brokenIsP1, partyMembers))
					continue;
				executeAutoAbility(fa, broken, brokenIsP1);
			}
		});
		mw.showStackWindowIfNeeded();
	}

	private void fireBreakZoneTriggers(CardData card, boolean ownerIsP1, CardData broken,
			boolean brokenIsP1, Set<CardData> partyMembers) {
		for (AutoAbility fa : mw.effectiveAutoAbilities(card)) {
			if (fa.trigger().equals("damaged card put into break zone")) {
				if (matchesDamagedByBreakZoneSubject(fa, card, broken, brokenIsP1, ownerIsP1))
					executeAutoAbility(fa, card, ownerIsP1);
				continue;
			}
			if (!fa.trigger().equals("put into break zone")) continue;
			if (!matchesBreakZoneSubject(fa, card, broken, brokenIsP1, ownerIsP1, partyMembers)) continue;
			// The broken card travels with the trigger: an effect may name it back rather than only
			// the watcher ("play the Forward placed in the Break Zone onto the field dull").
			executeAutoAbility(fa, card, ownerIsP1, false, broken);
		}
	}

	/**
	 * Returns true when {@code broken} satisfies a "[a Forward] damaged by [watcher] is put from the
	 * field into the Break Zone on the same turn" subject — Galuf 15-066C, Firion 16-120C, Tifa
	 * 23-012C, Delita 16-014R, Machina 3-022H, Vermilion Bird l'Cie Zhuyu 5-011H, Bahamut 24-015C,
	 * and the copy Morrow 11-013R hands itself.
	 *
	 * <p>Two questions, in the order that makes the cheap one first: did this card deal the damage,
	 * and is the departing card the kind the subject describes. The damage half is settled by
	 * identity against {@code MainWindow}'s per-turn record, not by name — a second copy of Galuf
	 * elsewhere on the board did not deal this damage and does not get the trigger. The name check
	 * ahead of it only confirms the subject names its own carrier, which every printing does.
	 *
	 * <p>"the same turn" needs no check of its own: the record is emptied at end of turn and when a
	 * card arrives on the field, so an entry existing at all means the damage was dealt this turn to
	 * this incarnation of the card.
	 */
	private boolean matchesDamagedByBreakZoneSubject(AutoAbility fa, CardData watcher, CardData broken,
			boolean brokenIsP1, boolean watcherIsP1) {
		Matcher m = CardData.DAMAGED_BY_BZ_SUBJECT.matcher(fa.triggerCard().trim());
		if (!m.matches()) return false;
		if (!CardFilters.meetsCardNameFilter(watcher, m.group("name").trim())) return false;
		if (!mw.wasDamagedBy(broken, watcher)) return false;

		// The half ahead of "damaged by" is an ordinary break-zone subject: an optional controller
		// clause over a type word.
		String subject = m.group("subject").trim();
		Matcher ctrlM = BZ_SUBJECT_CTRL.matcher(subject);
		if (ctrlM.find()) {
			boolean selfCtrl = ctrlM.group("ctrl").equalsIgnoreCase("you");
			if (selfCtrl != (brokenIsP1 == watcherIsP1)) return false;
			subject = subject.substring(0, ctrlM.start()).trim();
		}
		// These printings use the definite and indefinite article interchangeably for the same
		// thing — "the Forward damaged by Machina", "a Forward damaged by Galuf" — and
		// matchesSingleSubject only strips the indefinite one.
		return matchesSingleSubject(subject.replaceFirst("(?i)^the\\s+", "a "), broken, watcher);
	}

	/**
	 * Fires "leaves the field" field abilities that belong to {@code departing} itself.
	 * Call this after the card has been removed from all field tracking lists.
	 */
	void triggerAutoAbilitiesForLeavesField(CardData departing, boolean isP1) {
		// Fire the departing card's own triggers first — a granted one is still its ability while it
		// is leaving — then drop everything an outlasts-the-turn effect had handed it.
		withBatch(() -> {
			for (AutoAbility fa : mw.effectiveAutoAbilities(departing)) {
				if (!fa.trigger().equals("leaves the field")) continue;
				if (!fa.triggerCard().equalsIgnoreCase(departing.name())) continue;
				executeAutoAbility(fa, departing, isP1);
			}
		});
		mw.clearPermanentGrants(departing);
		// "When that Forward leaves the field this turn, put [lender] into the Break Zone"
		// (7-055R Chocobo). Collected before the grants are cleared above would be wrong — the
		// lender is a separate card, and this is its debt coming due, not the borrower's ability.
		fireLeavesFieldPutIntoBzMarks(departing);
		// Per-turn attack/block restrictions are keyed by instance, so they have to be dropped
		// here or they would follow the card back in when it is replayed from the Break Zone.
		mw.clearCombatRestrictionsFor(departing);
		// Necron: cards the departing card had removed "for as long as it is on the field"
		// re-enter their owner's field.
		mw.returnTempExiledOnLeave(departing);
		mw.gameState.clearCounters(departing);
		// Re-evaluate all conditional field boosts now that the field composition has changed
		mw.refreshAllForwardSlots();
		for (int i = 0; i < mw.p2ForwardCards.size(); i++) mw.refreshP2ForwardSlot(i);
		// A withdrawn power grant can leave a Forward at 0 power or below its accumulated damage.
		mw.enforceForwardBreakRuleProcess();
		mw.showStackWindowIfNeeded();
		// If a Forward just left, check the other player's field cards for
		// "if your opponent doesn't control Forwards" field abilities
		if (departing.isForward()) mw.fireOppNoForwardsFieldAbilities(!isP1);
	}

	/**
	 * Puts into the Break Zone every card that lent {@code departing} something this turn on the
	 * promise of following it off the field — 7-055R Chocobo's "When that Forward leaves the field
	 * this turn, put Chocobo into the Break Zone."
	 *
	 * <p>The mark is consumed on the way through: a Forward only leaves the field once, and if the
	 * lender is replayed later it has no outstanding debt. Lenders that have already left by some
	 * other route are skipped rather than resurrected into the Break Zone.
	 */
	private void fireLeavesFieldPutIntoBzMarks(CardData departing) {
		List<CardData> lenders = mw.putIntoBzWhenLeavesFieldThisTurn.remove(departing);
		if (lenders == null) return;
		for (CardData lender : lenders) {
			int p1Idx = mw.p1ForwardCards.indexOf(lender);
			int p2Idx = p1Idx >= 0 ? -1 : mw.p2ForwardCards.indexOf(lender);
			if (p1Idx < 0 && p2Idx < 0) {
				mw.logEntry(lender.name() + " already left the field — nothing to put into the Break Zone");
				continue;
			}
			mw.logEntry(departing.name() + " left the field — " + lender.name() + " → Break Zone");
			// A put, not a break: the printed wording is "put … into the Break Zone", so nothing
			// watching for a break should fire.
			if (p1Idx >= 0) mw.putP1ForwardIntoBreakZone(p1Idx);
			else            mw.putP2ForwardIntoBreakZone(p2Idx);
		}
	}

	/**
	 * Fires the cast-a-Summon triggers for a Summon {@code casterIsP1} has just put on the Stack.
	 *
	 * <p>Three canonical triggers share this event and they do not share a side. "When you cast a
	 * Summon" belongs to the caster; "When your opponent casts a Summon" (Lenne 1-215S, Ezel 4-053R,
	 * Gladiator 7-090C, Nelapa 23-014H) belongs to the player who did not cast, and is precisely the
	 * side the text excludes; "When either player casts a Summon" (Clione 4-125C) belongs to both.
	 * Dispatching all three on the caster's field fired the opponent-side printings for whichever
	 * player they were not watching, and never for the one they were.
	 *
	 * <p>All three are collected in one batch, so a cast that wakes abilities on both sides is
	 * ordered once — active player's first onto the Stack, and so last to resolve — rather than in
	 * two independent rounds whose relative order would be an artefact of the call sequence here.
	 *
	 * <p>Deliberately does not open the Stack overlay, unlike every other event dispatcher here.
	 * Its caller is {@code MainWindow.pushSummonOnStack}, which runs before the Summon's own
	 * {@code showStackWindow}; showing it from here would resolve the Stack — the overlay resolves
	 * a P1-owned top entry on the spot — while the cast that is putting entries on it is still
	 * running. The old call site sat inside {@code resolveTopOfStack}, where the same call was a
	 * no-op because {@code isResolvingStack} was already set.
	 */
	void triggerAutoAbilitiesForCastSummon(boolean casterIsP1) {
		withBatch(() -> {
			collectEventTriggers("cast summon", casterIsP1);
			collectEventTriggers("opponent casts summon", !casterIsP1);
			collectEventTriggers("either player casts summon", casterIsP1);
			collectEventTriggers("either player casts summon", !casterIsP1);
		});
	}

	/**
	 * Fires the ordinal cast triggers for the card {@code isP1} has just cast — "During each turn,
	 * when you cast the second card you've cast, …" (Shikaree G 15-051C, Atomos 16-043H) and
	 * Rosa 14-057H's "…this turn" spelling of the same trigger.
	 *
	 * <p>Only the caster's own field is walked: every printing in the family says "you", so the
	 * count and the abilities watching it belong to the same player. Nothing on the opposing side
	 * watches this event.
	 *
	 * <p>Deliberately does not open the Stack overlay, for the reason
	 * {@link #triggerAutoAbilitiesForCastSummon} does not: this runs while the cast that woke it is
	 * still being recorded, and the overlay resolves a P1-owned top entry on the spot.
	 *
	 * @param countThisTurn how many cards {@code isP1} has now cast this turn, this one included
	 */
	void triggerAutoAbilitiesForNthCardCast(boolean isP1, int countThisTurn) {
		withBatch(() -> collectEventTriggers(CardData.nthCastTrigger(false, countThisTurn), isP1));
	}

	/**
	 * The Summon-counting twin of {@link #triggerAutoAbilitiesForNthCardCast} (Belgemine 24-052L),
	 * fired by {@code MainWindow.pushSummonOnStack} — the single point every Summon cast funnels
	 * through, and where {@link PlayerTurnState#summonsCastThisTurn} is kept.
	 *
	 * @param countThisTurn how many Summons {@code isP1} has now cast this turn, this one included
	 */
	void triggerAutoAbilitiesForNthSummonCast(boolean isP1, int countThisTurn) {
		withBatch(() -> collectEventTriggers(CardData.nthCastTrigger(true, countThisTurn), isP1));
	}

	/**
	 * Fires "chosen by opponent's summon" field abilities on {@code chosenSideIsP1}'s side — called
	 * when that player's Forward was selected as a target by the opponent's Summon.
	 *
	 * @param chosen the Forwards actually selected, all on {@code chosenSideIsP1}'s side
	 */
	void triggerAutoAbilitiesForChosenByOpponentSummon(boolean chosenSideIsP1, List<CardData> chosen) {
		triggerChosenByOpponentEvent("chosen by opponent's summon", chosenSideIsP1, chosen);
	}

	/**
	 * Fires "chosen by opponent's summon or ability" field abilities on {@code chosenSideIsP1}'s
	 * side — called when that player's Character was selected as a target by the opponent's Summon
	 * *or* action/auto-ability (broader than
	 * {@link #triggerAutoAbilitiesForChosenByOpponentSummon}, which only covers Summons).
	 *
	 * @param chosen the Characters actually selected, all on {@code chosenSideIsP1}'s side
	 */
	void triggerAutoAbilitiesForChosenByOpponentSummonOrAbility(boolean chosenSideIsP1,
			List<CardData> chosen) {
		triggerChosenByOpponentEvent("chosen by opponent's summon or ability", chosenSideIsP1, chosen);
	}

	/**
	 * Walks the chosen player's field and fires {@code triggerType} abilities whose subject the
	 * selection actually satisfies.
	 *
	 * <p>Unlike most event triggers, these are not "something happened to my side, everyone
	 * reacts": the subject decides which cards being chosen count. Two printings exist — a card
	 * naming itself ("When Emet-Selch is chosen…"), which fires only for the copy that was chosen,
	 * and a filter ("When a Forward you control is chosen…"), which fires on every watcher whenever
	 * a matching card was chosen. Dispatching field-wide regardless of subject made every
	 * self-naming card fire on any friendly Character being targeted.
	 */
	private void triggerChosenByOpponentEvent(String triggerType, boolean isP1, List<CardData> chosen) {
		if (chosen.isEmpty()) return;
		withBatch(() -> {
			List<CardData> fwds = new ArrayList<>(isP1 ? mw.p1ForwardCards : mw.p2ForwardCards);
			CardData[]     bkps = isP1 ? mw.p1BackupCards : mw.p2BackupCards;
			List<CardData> mons = new ArrayList<>(isP1 ? mw.p1MonsterCards : mw.p2MonsterCards);
			for (CardData c : fwds) fireChosenByOpponentTriggers(c, isP1, triggerType, chosen);
			for (CardData c : bkps) if (c != null) fireChosenByOpponentTriggers(c, isP1, triggerType, chosen);
			for (CardData c : mons) fireChosenByOpponentTriggers(c, isP1, triggerType, chosen);
		});
		mw.showStackWindowIfNeeded();
	}

	private void fireChosenByOpponentTriggers(CardData watcher, boolean isP1, String triggerType,
			List<CardData> chosen) {
		for (AutoAbility fa : mw.effectiveAutoAbilities(watcher))
			if (fa.trigger().equals(triggerType)
					&& matchesChosenSubject(fa.triggerCard(), watcher, chosen))
				executeAutoAbility(fa, watcher, isP1);
	}

	/** "1 or more Forwards you control" — the count prefix, stripped before splitting on " or ". */
	private static final Pattern CHOSEN_SUBJECT_COUNT = Pattern.compile("(?i)^\\d+\\s+or\\s+more\\s+");
	/**
	 * Trailing controller clause; the dispatch side already establishes the controller. Shared by
	 * the chosen-by-opponent and is-priming subject matchers, which read subjects the same way.
	 */
	private static final Pattern TRIGGER_SUBJECT_CTRL =
			Pattern.compile("(?i)\\s+(?:you\\s+control|opponent\\s+controls?)$");
	/** "this Forward" and friends — a self-reference spelled without the card's name. */
	private static final Pattern CHOSEN_SUBJECT_SELF =
			Pattern.compile("(?i)^this\\s+(?:forward|backup|monster|character)$");

	/**
	 * Returns true when {@code chosen} satisfies a chosen-by-opponent trigger's subject.
	 *
	 * <p>A subject naming the watcher itself — by card name, or as "this Forward" — is matched by
	 * <em>identity</em>, not by name, following the rule that a card naming itself refers to that
	 * specific copy. Every other subject is a filter, satisfied by any chosen card matching it.
	 *
	 * <p>The count prefix comes off before the disjunction is split, because "1 or more" itself
	 * contains an " or ".
	 */
	private boolean matchesChosenSubject(String subject, CardData watcher, List<CardData> chosen) {
		// A subject-less printing can only be about the watcher, so fall back to identity rather
		// than to firing unconditionally — the latter is the bug this method exists to prevent.
		if (subject == null || subject.isBlank())
			return chosen.stream().anyMatch(c -> c == watcher);

		String stripped = CHOSEN_SUBJECT_COUNT.matcher(subject.trim()).replaceFirst("");
		for (String rawPart : stripped.split("(?i)\\s+or\\s+")) {
			String part = TRIGGER_SUBJECT_CTRL.matcher(rawPart.trim()).replaceFirst("").trim();
			// "the Card Name Palom" — normalise the article so the shared subject matcher, which
			// expects "a"/"an", recognises it.
			part = part.replaceAll("(?i)^the\\s+", "a ");
			if (part.isEmpty()) continue;
			if (CHOSEN_SUBJECT_SELF.matcher(part).matches()
					|| CardFilters.meetsCardNameFilter(watcher, part)) {
				if (chosen.stream().anyMatch(c -> c == watcher)) return true;
				continue;
			}
			for (CardData c : chosen)
				if (matchesSingleSubject(part, c, watcher)) return true;
		}
		return false;
	}

	/**
	 * Fires "opponent searches" auto abilities when {@code searcherIsP1} searches their deck.
	 * The watchers are the searcher's opponent, so the abilities fire on the other side.
	 *
	 * <p>{@code searchingCard} is the card whose ability performed the search, or {@code null}
	 * when the search came from something else (a cast Summon, a game action). It matters for two
	 * reasons: 5-130R Tonberry only triggers on "a Character opponent controls" searching — a
	 * search with no Character behind it is not that — and its effect breaks that same Character,
	 * so the card has to be carried through to the effect as a preloaded target.
	 */
	void triggerAutoAbilitiesForSearch(CardData searchingCard, boolean searcherIsP1) {
		boolean watcherIsP1 = !searcherIsP1;
		ForwardTarget searcherTarget = searchingCard == null
				? null : findFieldTarget(searchingCard, searcherIsP1);
		withBatch(() -> {
			List<CardData> fwds = new ArrayList<>(watcherIsP1 ? mw.p1ForwardCards : mw.p2ForwardCards);
			CardData[]     bkps = watcherIsP1 ? mw.p1BackupCards : mw.p2BackupCards;
			List<CardData> mons = new ArrayList<>(watcherIsP1 ? mw.p1MonsterCards : mw.p2MonsterCards);
			List<CardData> watchers = new ArrayList<>(fwds);
			for (CardData c : bkps) if (c != null) watchers.add(c);
			watchers.addAll(mons);
			for (CardData watcher : watchers) fireSearchTriggers(watcher, watcherIsP1, searchingCard, searcherTarget);
		});
		mw.showStackWindowIfNeeded();
	}

	private void fireSearchTriggers(CardData watcher, boolean watcherIsP1,
			CardData searchingCard, ForwardTarget searcherTarget) {
		for (AutoAbility fa : mw.effectiveAutoAbilities(watcher)) {
			if (!fa.trigger().equals("opponent searches")) continue;
			// "a Character opponent controls searches" needs a Character behind the search;
			// "your opponent searches" is satisfied by the player searching at all.
			boolean needsCharacter = SEARCH_SUBJECT_IS_CHARACTER.matcher(fa.triggerCard()).find();
			if (needsCharacter && searchingCard == null) continue;
			// Only an effect that points back at the searcher needs it preloaded. Preloading
			// unconditionally would hand a target to any unrelated selection the effect makes.
			if (searcherTarget != null && REFERS_TO_TRIGGERING_CARD.matcher(fa.effectText()).find()) {
				runWithPreloadedTarget(fa, watcher, watcherIsP1, searcherTarget);
				continue;
			}
			executeAutoAbility(fa, watcher, watcherIsP1);
		}
	}

	/** Subject phrases that require a Character to have done the searching, not just the player. */
	private static final Pattern SEARCH_SUBJECT_IS_CHARACTER = Pattern.compile(
			"(?i)\\b(Character|Forward|Backup|Monster)\\b");

	/** An effect that points back at the card which fired the trigger. */
	private static final Pattern REFERS_TO_TRIGGERING_CARD = Pattern.compile(
			"(?i)\\bthat\\s+(?:Character|Forward)\\b");

	/** Resolves {@code fa} immediately with {@code target} preloaded, for effects naming it. */
	private void runWithPreloadedTarget(AutoAbility fa, CardData watcher,
			boolean watcherIsP1, ForwardTarget target) {
		Consumer<GameContext> effect = ActionResolver.parse(fa.effectText(), watcher);
		if (effect == null) return;
		GameContext ctx = mw.buildGameContext(watcherIsP1);
		ctx.preloadTargets(List.of(target));
		CardData prevSource  = mw.currentAbilitySource;
		boolean  prevSpecial = mw.currentAbilityIsSpecial;
		mw.currentAbilitySource    = watcher;
		mw.currentAbilityIsSpecial = false;
		try {
			mw.logEntry("[AutoAbility] " + watcher.name() + " — " + fa.effectText());
			effect.accept(ctx);
		} finally {
			mw.currentAbilitySource    = prevSource;
			mw.currentAbilityIsSpecial = prevSpecial;
		}
	}

	/** Locates {@code card} in {@code isP1}'s field zones, or {@code null} if it has left. */
	private ForwardTarget findFieldTarget(CardData card, boolean isP1) {
		List<CardData> fwds = isP1 ? mw.p1ForwardCards : mw.p2ForwardCards;
		for (int i = 0; i < fwds.size(); i++)
			if (fwds.get(i) == card) return new ForwardTarget(isP1, i, ForwardTarget.CardZone.FORWARD);
		CardData[] bkps = isP1 ? mw.p1BackupCards : mw.p2BackupCards;
		for (int i = 0; i < bkps.length; i++)
			if (bkps[i] == card) return new ForwardTarget(isP1, i, ForwardTarget.CardZone.BACKUP);
		List<CardData> mons = isP1 ? mw.p1MonsterCards : mw.p2MonsterCards;
		for (int i = 0; i < mons.size(); i++)
			if (mons.get(i) == card) return new ForwardTarget(isP1, i, ForwardTarget.CardZone.MONSTER);
		return null;
	}

	/**
	 * Fires "opponent discards … due to your Summons or abilities" abilities on {@code causerIsP1}'s
	 * field cards, for the card their effect just made the opponent discard.
	 *
	 * <p>Three trigger labels share this path because the printings differ in what they watch for:
	 * any card, a Character, or a Summon (27-036L Locke carries the last two simultaneously). The
	 * discarded card decides which fire.
	 */
	void triggerAutoAbilitiesForDiscardByEffect(CardData discarded, boolean causerIsP1) {
		if (discarded == null) return;
		List<String> labels = new ArrayList<>();
		labels.add("opponent discards by effect");
		if (discarded.isForward() || discarded.isBackup() || discarded.isMonster())
			labels.add("opponent discards character by effect");
		if (discarded.isSummon())
			labels.add("opponent discards summon by effect");
		for (String label : labels) triggerAutoAbilitiesForEvent(label, causerIsP1);
	}

	/**
	 * Fires "1 or more cards are added to your opponent's hand from the Break Zone" abilities.
	 * {@code handOwnerIsP1} is the player who salvaged, so the watchers are on the other side.
	 */
	void triggerAutoAbilitiesForBreakZoneToHand(boolean handOwnerIsP1) {
		triggerAutoAbilitiesForEvent("opponent salvages from break zone", !handOwnerIsP1);
	}

	/** Fires "damage zone" field abilities for all field cards belonging to the player who took damage. */
	void triggerAutoAbilitiesForDamageZone(boolean isP1) {
		triggerAutoAbilitiesForEvent("damage zone", isP1);
	}

	/** Fires "beginning of attack phase" auto-abilities on all field cards belonging to the active player. */
	void triggerAutoAbilitiesForBeginningOfAttackPhase(boolean isP1) {
		triggerAutoAbilitiesForEvent("beginning of attack phase", isP1);
	}

	/**
	 * Fires "beginning of attack phase each turn" auto-abilities for all field cards on both sides —
	 * the "during each player's turn" wording triggers regardless of whose turn it is.  The active
	 * player's abilities are dispatched first, matching {@link #dispatchSimultaneous}'s AP-then-NAP
	 * order.
	 *
	 * @param activeIsP1 whether the player whose Attack Phase is beginning is P1
	 */
	void triggerAutoAbilitiesForBeginningOfAttackPhaseEachTurn(boolean activeIsP1) {
		triggerAutoAbilitiesForEvent("beginning of attack phase each turn", activeIsP1);
		triggerAutoAbilitiesForEvent("beginning of attack phase each turn", !activeIsP1);
	}

	/**
	 * Fires "beginning of opponent's attack phase" auto-abilities (Ardyn 8-068L) for all field cards
	 * controlled by the player whose Attack Phase this is <em>not</em>. Call alongside
	 * {@link #triggerAutoAbilitiesForBeginningOfAttackPhase} at the start of {@code activeIsP1}'s
	 * Attack Phase.
	 *
	 * @param activeIsP1 whether the player whose Attack Phase is beginning is P1
	 */
	void triggerAutoAbilitiesForBeginningOfOppAttackPhase(boolean activeIsP1) {
		triggerAutoAbilitiesForEvent("beginning of opponent's attack phase", !activeIsP1);
	}

	/**
	 * Fires "end of your turn" auto-abilities for all cards controlled by {@code isP1}, including
	 * any granted to their Forwards by a card on the field (Vayne 9-022L).
	 */
	void triggerAutoAbilitiesForEndOfYourTurn(boolean isP1) {
		triggerAutoAbilitiesForEvent("end of your turn", isP1);
		mw.fireGrantedEndOfTurnForwardAbilities(isP1);
	}

	/** Fires "end of each player's turn" auto-abilities for all cards on both sides. */
	void triggerAutoAbilitiesForEndOfEachPlayersTurn() {
		triggerAutoAbilitiesForEvent("end of each player's turn", true);
		triggerAutoAbilitiesForEvent("end of each player's turn", false);
	}

	/**
	 * Fires "is dealt damage" auto-abilities for one instance of damage dealt to {@code damaged}.
	 * Called from every path that deals damage to a Forward — ability damage once the damage has
	 * been recorded and before any break check, and battle damage as combat resolves — because the
	 * trigger is on being dealt damage, not on surviving it.
	 *
	 * <p>Called <em>per instance</em>: an effect that damages three Forwards deals three separate
	 * damages and so meets the trigger three times, and a watcher of all three fires three times.
	 * That is the same reading {@link #triggerAutoAbilitiesForGainCrystal} takes of "gain a 《C》".
	 *
	 * <p>Dispatch walks the damaged card's controller's whole field rather than only the damaged
	 * card, because two subject forms exist. Most printings name the card itself ("When Gi Nattak
	 * is dealt damage"), and those fire only for the copy that took the damage. 18-012L Faris is
	 * the watcher form — "When Faris or a Job Warrior of Light Forward you control is dealt damage"
	 * — which reacts to damage dealt to some other card, so its ability lives on a card the walk
	 * has to reach independently of what was damaged. Both are decided by
	 * {@link #matchesDamagedSubject}, so the self-naming printings keep firing exactly once.
	 */
	void fireIsDealtDamageTriggers(CardData damaged, boolean damagedIsP1) {
		if (damaged == null) return;
		// Batched: one damage can meet the trigger on more than one card — the Forward's own
		// printing and a Faris watching it — and those are simultaneous, so their controller picks
		// the order they go on the stack.
		withBatch(() -> {
			for (CardData watcher : fieldCards(damagedIsP1))
				fireIsDealtDamageTriggers(watcher, damagedIsP1, damaged);
		});
		mw.showStackWindowIfNeeded();
	}

	private void fireIsDealtDamageTriggers(CardData watcher, boolean watcherIsP1, CardData damaged) {
		for (AutoAbility fa : mw.effectiveAutoAbilities(watcher)) {
			if (!fa.trigger().equals("is dealt damage")) continue;
			if (!matchesDamagedSubject(fa.triggerCard(), watcher, damaged)) continue;
			executeAutoAbility(fa, watcher, watcherIsP1);
		}
	}

	/** Every card {@code isP1} has on the field, in Forward / Backup / Monster order. */
	private List<CardData> fieldCards(boolean isP1) {
		List<CardData> cards = new ArrayList<>(isP1 ? mw.p1ForwardCards : mw.p2ForwardCards);
		for (CardData c : isP1 ? mw.p1BackupCards : mw.p2BackupCards) if (c != null) cards.add(c);
		cards.addAll(isP1 ? mw.p1MonsterCards : mw.p2MonsterCards);
		return cards;
	}

	/** "this Forward" and friends — a self-reference spelled without the card's name. */
	private static final Pattern DAMAGED_SUBJECT_SELF =
			Pattern.compile("(?i)^this\\s+(?:forward|backup|monster|character)$");

	/**
	 * The qualifying clauses this dispatch has already settled: "you control", since the walk only
	 * visits the damaged card's own side, and a "by …" source clause (20-024H Calbrena, 5-037R
	 * Zeid), which no printing in the corpus uses to narrow anything this dispatch decides.
	 *
	 * <p>Deliberately not "opponent controls": the walk cannot satisfy that, so such a subject is
	 * left intact and declines on the filter below rather than being read as its own side.
	 */
	private static final Pattern DAMAGED_SUBJECT_TAIL =
			Pattern.compile("(?i)\\s+(?:you\\s+control|by\\s+.*)$");

	/**
	 * True when {@code damaged} satisfies the subject of an "is dealt damage" trigger carried by
	 * {@code watcher}.
	 *
	 * <p>A subject naming the watcher — by card name, or as "this Forward" — is matched by
	 * <em>identity</em> rather than by name, following the rule that a card naming itself means
	 * that copy. Name equality would be the wrong test here: the walk now visits every card on the
	 * side, and {@code effectiveAutoAbilities} can hand a card an ability granted from elsewhere,
	 * whose text names its granter. Every other subject is a filter over the damaged card, so it
	 * fires on however many watchers match.
	 *
	 * <p>The subject may be compound — Faris reads "Faris or a Job Warrior of Light Forward you
	 * control" — and one match anywhere in it fires the ability once, not once per half. Faris
	 * being damaged satisfies both halves, and answers with a single trigger.
	 */
	private boolean matchesDamagedSubject(String subject, CardData watcher, CardData damaged) {
		// A subject-less printing can only be about the watcher itself, so fall back to identity
		// rather than firing unconditionally.
		if (subject == null || subject.isBlank()) return damaged == watcher;

		for (String rawPart : subject.split("(?i)\\s+or\\s+")) {
			String part = DAMAGED_SUBJECT_TAIL.matcher(rawPart.trim()).replaceFirst("").trim();
			if (part.isEmpty()) continue;
			if (DAMAGED_SUBJECT_SELF.matcher(part).matches()
					|| meetsCardNameFilter(watcher, part)) {
				if (damaged == watcher) return true;
				continue;
			}
			if (matchesSingleSubject(part, damaged, watcher)) return true;
		}
		return false;
	}

	/** Fires "end of opponent's turn" auto-abilities for all cards controlled by {@code isP1}. */
	void triggerAutoAbilitiesForEndOfOpponentTurn(boolean isP1) {
		triggerAutoAbilitiesForEvent("end of opponent's turn", isP1);
	}

	/** Fires "beginning of main phase 1" auto-abilities for all cards controlled by {@code isP1}. */
	void triggerAutoAbilitiesForBeginningOfMainPhase1(boolean isP1) {
		triggerAutoAbilitiesForEvent("beginning of main phase 1", isP1);
	}

	/** Fires "beginning of main phase 2" auto-abilities for all cards controlled by {@code isP1}. */
	void triggerAutoAbilitiesForBeginningOfMainPhase2(boolean isP1) {
		triggerAutoAbilitiesForEvent("beginning of main phase 2", isP1);
	}

	/** Fires "beginning of main phase 1 each turn" auto-abilities for all cards on both sides. */
	void triggerAutoAbilitiesForBeginningOfMainPhase1EachTurn() {
		triggerAutoAbilitiesForEvent("beginning of main phase 1 each turn", true);
		triggerAutoAbilitiesForEvent("beginning of main phase 1 each turn", false);
	}

	/**
	 * Fires "beginning of opponent's main phase 1" auto-abilities for all cards controlled by
	 * {@code isP1}. Call at the start of {@code !isP1}'s Main Phase 1.
	 */
	void triggerAutoAbilitiesForBeginningOfOppMainPhase1(boolean isP1) {
		triggerAutoAbilitiesForEvent("beginning of opponent's main phase 1", isP1);
	}

	/** Fires "either player receives damage" abilities on all field cards from both sides. */
	void triggerAutoAbilitiesForEitherPlayerReceivesDamage() {
		// Batch both sides together so the player sees one ordering dialog, not two.
		withBatch(() -> {
			triggerAutoAbilitiesForEvent("either player receives damage", true);
			triggerAutoAbilitiesForEvent("either player receives damage", false);
		});
		mw.showStackWindowIfNeeded();
	}

	/** Fires "you receive damage" abilities on all field cards belonging to the player who took damage. */
	void triggerAutoAbilitiesForYouReceiveDamage(boolean isP1) {
		triggerAutoAbilitiesForEvent("you receive damage", isP1);
	}

	/**
	 * Fires "when you gain a 《C》" abilities on the gaining player's field cards
	 * (16-115H Sarah (MOBIUS)).
	 *
	 * <p>Called once per Crystal, not once per effect: an ability that hands over 《C》《C》 gains
	 * two Crystals and so meets "gain a 《C》" twice. That matches how this engine already treats
	 * the closest analogue — a multi-point damage effect fires "you receive damage" per point,
	 * because each point is dealt as its own action.
	 */
	void triggerAutoAbilitiesForGainCrystal(boolean isP1) {
		triggerAutoAbilitiesForEvent("gain crystal", isP1);
	}

	/**
	 * Fires "becomes dull" auto abilities on {@code card} (owned by {@code isP1}) after it
	 * transitions from ACTIVE to DULL.  Only abilities whose {@code triggerCard} matches the
	 * card's name are executed.
	 */
	void triggerAutoAbilitiesForBecomesDull(CardData card, boolean isP1) {
		withBatch(() -> {
			for (AutoAbility fa : mw.effectiveAutoAbilities(card)) {
				if (!fa.trigger().equals("becomes dull")) continue;
				if (!fa.triggerCard().equalsIgnoreCase(card.name())) continue;
				executeAutoAbility(fa, card, isP1);
			}
		});
		mw.showStackWindowIfNeeded();
	}

	/**
	 * Fires "opponent uses ex burst" abilities on the field cards of the player whose opponent
	 * just resolved an EX Burst. {@code exBurstIsP1} is the player whose damage zone received
	 * the EX Burst card; the watchers belong to {@code !exBurstIsP1}.
	 */
	void triggerAutoAbilitiesForOpponentUsesExBurst(boolean exBurstIsP1) {
		triggerAutoAbilitiesForEvent("opponent uses ex burst", !exBurstIsP1);
	}

	/**
	 * Resolves the EX Burst effect on {@code card} for the player whose damage zone received it.
	 * The controlling player may decline; if accepted the effect resolves immediately, bypassing
	 * the stack so neither player can respond.
	 * Summon effects run the full card effect; forward/backup/monster effects strip the auto-ability
	 * trigger prefix and run the bare effect text.
	 */
	void triggerExBurst(CardData card, boolean isP1) {
		String effect = card.exBurstEffect();
		if (effect.isEmpty()) {
			mw.logEntry("[EX BURST] " + card.name() + " — no parseable effect");
			return;
		}
		// Strip any extra cost clause — extra cost cannot be paid when triggered as an EX Burst.
		if (card.extraCost() != null)
			effect = ActionResolver.stripExtraCostClause(effect);
		Consumer<GameContext> fn = ActionResolver.parse(effect, card);
		if (fn == null) {
			mw.logEntry("[EX BURST] Effect not yet implemented: " + effect);
			return;
		}
		if (isP1) {
			JDialog dlg = new JDialog(mw.frame, "EX Burst — " + card.name(), true);
			dlg.setResizable(false);
			dlg.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

			JLabel cardLabel = new JLabel("...", SwingConstants.CENTER);
			cardLabel.setPreferredSize(new Dimension(CARD_W, CARD_H));
			cardLabel.setMinimumSize(new Dimension(CARD_W, CARD_H));
			cardLabel.setOpaque(true);
			cardLabel.setBackground(Color.DARK_GRAY);
			cardLabel.setBorder(BorderFactory.createLineBorder(new Color(160, 110, 220), 1));
			cardLabel.addMouseListener(new MouseAdapter() {
				@Override public void mouseEntered(MouseEvent e) { mw.showZoomAt(card.imageUrl()); }
				@Override public void mouseExited(MouseEvent e)  { mw.hideZoom(); }
			});
			new SwingWorker<ImageIcon, Void>() {
				@Override protected ImageIcon doInBackground() throws Exception {
					Image img = ImageCache.load(card.imageUrl());
					return img == null ? null : new ImageIcon(img.getScaledInstance(CARD_W, CARD_H, Image.SCALE_SMOOTH));
				}
				@Override protected void done() {
					try { ImageIcon ic = get(); if (ic != null) { cardLabel.setIcon(ic); cardLabel.setText(null); } }
					catch (InterruptedException | ExecutionException ignored) {}
				}
			}.execute();

			JLabel nameLabel = new JLabel(card.name(), SwingConstants.CENTER);
			nameLabel.setFont(FontLoader.loadPixelFont(9));
			nameLabel.setPreferredSize(new Dimension(CARD_W, 18));

			JLabel effectLabel = new JLabel(
					"<html><div style='text-align:center;width:" + CARD_W + "px'>" + effect + "</div></html>",
					SwingConstants.CENTER);

			JPanel infoPanel = new JPanel();
			infoPanel.setLayout(new BoxLayout(infoPanel, BoxLayout.Y_AXIS));
			nameLabel.setAlignmentX(java.awt.Component.CENTER_ALIGNMENT);
			effectLabel.setAlignmentX(java.awt.Component.CENTER_ALIGNMENT);
			infoPanel.add(nameLabel);
			infoPanel.add(effectLabel);

			JPanel wrapper = new JPanel(new BorderLayout(0, 4));
			wrapper.setBorder(BorderFactory.createEmptyBorder(8, 8, 0, 8));
			wrapper.add(cardLabel,  BorderLayout.CENTER);
			wrapper.add(infoPanel,  BorderLayout.SOUTH);

			boolean[] activated = {false};
			JButton declineBtn = new JButton("Decline");
			declineBtn.setFont(FontLoader.loadPixelFont(11));
			declineBtn.addActionListener(ae -> { mw.hideZoom(); dlg.dispose(); });
			JButton okBtn = new JButton("OK");
			okBtn.setFont(FontLoader.loadPixelFont(11));
			okBtn.addActionListener(ae -> { activated[0] = true; mw.hideZoom(); dlg.dispose(); });

			JPanel south = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 6));
			south.add(declineBtn);
			south.add(okBtn);
			south.setBorder(BorderFactory.createEmptyBorder(0, 8, 8, 8));

			dlg.getContentPane().setLayout(new BorderLayout(0, 4));
			dlg.getContentPane().add(wrapper, BorderLayout.CENTER);
			dlg.getContentPane().add(south,   BorderLayout.SOUTH);
			dlg.pack();
			dlg.setLocationRelativeTo(mw.frame);
			dlg.setVisible(true);

			if (!activated[0]) {
				mw.logEntry("[EX BURST] " + card.name() + " — declined");
				return;
			}
		} else {
			mw.logEntry("[EX BURST] [AI] " + card.name() + " — auto-activates");
		}
		mw.logEntry("[EX BURST] " + card.name() + " — " + effect);
		if (card.isSummon()) { mw.currentResolutionIsSummon = true; mw.currentSummonSource = card; }
		try { fn.accept(mw.buildGameContext(isP1, true)); } finally { mw.currentResolutionIsSummon = false; mw.currentSummonSource = null; }
		triggerAutoAbilitiesForOpponentUsesExBurst(isP1);
	}

	/**
	 * Fires "warp placed" field abilities on the warping player's field cards whose
	 * {@code triggerCard} matches the card that was just moved from hand to the Warp zone.
	 */
	void triggerAutoAbilitiesForWarpPlaced(CardData warped, boolean isP1) {
		withBatch(() -> {
			List<CardData> all = new ArrayList<>();
			all.addAll(isP1 ? mw.p1ForwardCards : mw.p2ForwardCards);
			for (CardData c : (isP1 ? mw.p1BackupCards : mw.p2BackupCards)) if (c != null) all.add(c);
			all.addAll(isP1 ? mw.p1MonsterCards : mw.p2MonsterCards);
			for (CardData card : all)
				for (AutoAbility fa : mw.effectiveAutoAbilities(card))
					if (fa.trigger().equals("warp placed")
							&& fa.triggerCard().equalsIgnoreCase(warped.name()))
						executeAutoAbility(fa, card, isP1);
		});
		mw.showStackWindowIfNeeded();
	}

	/**
	 * Fires "warp counter removed" field abilities on the warping player's field cards (and
	 * their own warp-zone residents) whose {@code triggerCard} matches the card whose counter
	 * was just decremented.
	 */
	void triggerAutoAbilitiesForWarpCounterRemoved(CardData target, boolean isP1) {
		withBatch(() -> {
			List<CardData> all = new ArrayList<>();
			List<GameState.WarpEntry> warpZone = isP1
					? mw.gameState.getP1WarpZone() : mw.gameState.getP2WarpZone();
			all.addAll(isP1 ? mw.p1ForwardCards : mw.p2ForwardCards);
			for (CardData c : (isP1 ? mw.p1BackupCards : mw.p2BackupCards)) if (c != null) all.add(c);
			for (GameState.WarpEntry we : warpZone) if (we != null) all.add(we.card);
			all.addAll(isP1 ? mw.p1MonsterCards : mw.p2MonsterCards);
			for (CardData card : all)
				for (AutoAbility fa : mw.effectiveAutoAbilities(card))
					if (fa.trigger().equals("warp counter removed")
							&& (fa.triggerCard().equalsIgnoreCase("any player's card") || fa.triggerCard().equalsIgnoreCase(target.name())))
						executeAutoAbility(fa, card, isP1);
		});
		mw.showStackWindowIfNeeded();
	}

	private void triggerAutoAbilitiesForEvent(String triggerType, boolean isP1) {
		withBatch(() -> collectEventTriggers(triggerType, isP1));
		mw.showStackWindowIfNeeded();
	}

	/**
	 * Walks {@code isP1}'s field and fires every {@code triggerType} ability on it.
	 *
	 * <p>Split out of {@link #triggerAutoAbilitiesForEvent} so an event whose triggers span both
	 * sides can gather all of them inside a single {@link #withBatch}. Callers that open no batch of
	 * their own must go through {@code triggerAutoAbilitiesForEvent} instead — {@link #withBatch} is
	 * re-entrant, so nesting is safe, but calling this bare would push each ability straight onto the
	 * Stack and skip the simultaneous-trigger ordering entirely.
	 */
	private void collectEventTriggers(String triggerType, boolean isP1) {
		List<CardData> fwds = new ArrayList<>(isP1 ? mw.p1ForwardCards : mw.p2ForwardCards);
		CardData[]     bkps = isP1 ? mw.p1BackupCards : mw.p2BackupCards;
		List<CardData> mons = new ArrayList<>(isP1 ? mw.p1MonsterCards : mw.p2MonsterCards);
		for (CardData c : fwds) fireEventTriggers(c, isP1, triggerType);
		for (CardData c : bkps) if (c != null) fireEventTriggers(c, isP1, triggerType);
		for (CardData c : mons) fireEventTriggers(c, isP1, triggerType);
	}

	private void fireEventTriggers(CardData card, boolean isP1, String triggerType) {
		for (AutoAbility fa : mw.effectiveAutoAbilities(card))
			if (fa.trigger().equals(triggerType))
				executeAutoAbility(fa, card, isP1);
	}

	/**
	 * Resolves a triggered auto ability.  When the ability is optional ({@code youMay} or
	 * {@code opponentMay}), P1 is shown a Decline / OK dialog; the AI always accepts.
	 *
	 * <p>For {@code opponentMay} effects the execution context is flipped to the opponent's
	 * perspective so that "play from hand" and similar effects target the correct player.
	 */
	/**
	 * Batch-aware front door. When a simultaneous-trigger batch is open
	 * ({@link #withBatch}), this only records the ability — the actual
	 * execution is deferred until the batch is dispatched in the player-
	 * chosen order. Otherwise it runs immediately via
	 * {@link #executeAutoAbilityImpl}.
	 */
	private void executeAutoAbility(AutoAbility fa, CardData source, boolean isP1) {
		executeAutoAbility(fa, source, isP1, false);
	}

	/** @param paidExtraCost whether {@code source}'s optional extra cost was paid when it was cast. */
	private void executeAutoAbility(AutoAbility fa, CardData source, boolean isP1, boolean paidExtraCost) {
		executeAutoAbility(fa, source, isP1, paidExtraCost, null);
	}

	/**
	 * @param triggerCard the card whose event fired this trigger, for the effects that name it back
	 *     ("play the Forward placed in the Break Zone …" — Lunafreya 8-132L); {@code null} otherwise.
	 *     Carried on the batch item rather than in a field, so a batch holding two triggers on one
	 *     watcher resolves each against its own event.
	 */
	private void executeAutoAbility(AutoAbility fa, CardData source, boolean isP1, boolean paidExtraCost,
			CardData triggerCard) {
		if (mw.lostAbilitiesCards.contains(source)) return;
		if (pendingBatch != null) {
			pendingBatch.add(new StackOrderingDialog.Item(fa, source, isP1, paidExtraCost, triggerCard));
			return;
		}
		executeAutoAbilityImpl(fa, source, isP1, paidExtraCost, triggerCard);
	}

	private void executeAutoAbilityImpl(AutoAbility fa, CardData source, boolean isP1) {
		executeAutoAbilityImpl(fa, source, isP1, false);
	}

	/**
	 * Runs one triggered ability with {@code triggerCard} standing as the card whose event fired it,
	 * for the whole of the resolution. Held on {@link MainWindow#triggeringBrokenCard} rather than
	 * passed down, because the effect that reads it is reached through
	 * {@link ActionResolver#parse}'s {@code Consumer}, which carries no room for a second card.
	 * Restored afterwards so a nested resolution cannot leave its own event behind.
	 */
	private void executeAutoAbilityImpl(AutoAbility fa, CardData source, boolean isP1,
			boolean paidExtraCost, CardData triggerCard) {
		CardData previous = mw.triggeringBrokenCard;
		mw.triggeringBrokenCard = triggerCard;
		try {
			executeAutoAbilityImpl(fa, source, isP1, paidExtraCost);
		} finally {
			mw.triggeringBrokenCard = previous;
		}
	}

	private void executeAutoAbilityImpl(AutoAbility fa, CardData source, boolean isP1, boolean paidExtraCost) {
		// Damage threshold: skip if the controlling player doesn't have enough damage counters
		if (fa.damageThreshold() > 0) {
			int dmg = isP1 ? mw.gameState.getP1DamageZone().size() : mw.gameState.getP2DamageZone().size();
			if (dmg < fa.damageThreshold()) return;
		}

		// "only during your turn" — skip when the ability owner is not the active player
		if (fa.yourTurnOnly() && !isP1) return;

		// "During your opponent's turn, when …" — the mirror, and read off the turn rather than off
		// the side: the events this gates (a Forward of yours being broken) happen on both players'
		// turns, so the question is whose turn it is now, not who owns the ability.
		if (fa.opponentTurnOnly()
				&& (mw.gameState.getCurrentPlayer() == GameState.Player.P1) == isP1) {
			mw.logEntry("[AutoAbility] " + source.name() + " — only triggers during your opponent's turn");
			return;
		}

		// cast payment element condition: "if the cost to cast X was paid with CP of N or more different Elements"
		if (fa.castPaymentMinElements() > 0 && mw.lastCastPaymentDistinctElements < fa.castPaymentMinElements()) {
			mw.logEntry("[AutoAbility] " + source.name() + " — cast payment condition not met ("
					+ mw.lastCastPaymentDistinctElements + " distinct element(s), needed "
					+ fa.castPaymentMinElements() + ")");
			return;
		}

		// "due to your cast" — only fires when the card entered the field by being cast from hand
		if (fa.castOnly() && !mw.lastCardWasCast) return;

		// "due to Warp" — only fires when the card entered the field via Warp resolution
		if (fa.warpOnly() && !mw.lastCardWarpedIn) return;

		// "only if [card] is removed from the game" — skip if that card is not in the RFP zone
		if (!fa.rfpConditionCard().isEmpty()) {
			String cond = fa.rfpConditionCard();
			List<GameState.WarpEntry> warpZone = isP1
					? mw.gameState.getP1WarpZone() : mw.gameState.getP2WarpZone();
			List<CardData> permRfp = isP1
					? mw.gameState.getP1PermanentRfp() : mw.gameState.getP2PermanentRfp();
			boolean inRfp = warpZone.stream().anyMatch(e -> e.card.name().equalsIgnoreCase(cond))
					|| permRfp.stream().anyMatch(c -> c.name().equalsIgnoreCase(cond));
			if (!inRfp) return;
		}

		// "only if [card] is in the Break Zone" — skip if that card is not in the owner's Break Zone
		// (with an optional Job requirement: "a Card Name X with Job Y in your Break Zone")
		if (!fa.bzConditionCard().isEmpty()) {
			String cond    = fa.bzConditionCard();
			String condJob = fa.bzConditionJob().isEmpty() ? null : fa.bzConditionJob();
			List<CardData> bz = isP1 ? mw.gameState.getP1BreakZone() : mw.gameState.getP2BreakZone();
			if (bz.stream().noneMatch(c -> CardFilters.meetsCardNameFilter(c, cond)
					&& CardFilters.meetsJobFilter(c, condJob))) return;
		}

		// "only once per turn" — skip if already fired this turn
		if (fa.oncePerTurn() && mw.usedOncePerTurnAbilities
				.getOrDefault(source, Set.of()).contains(fa.effectText())) {
			mw.logEntry("[AutoAbility] " + source.name() + " — already used this turn, skipping");
			return;
		}

		// opponentMay effects run from the opponent's context
		boolean effectIsP1 = fa.opponentMay() ? !isP1 : isP1;

		// Detect "remove N [Name] Counter(s) from [CardName]. When you do so, [effect]"
		Matcher ctrM = FA_REMOVE_COUNTER_WHEN_DO_SO.matcher(fa.effectText());
		if (ctrM.find()) {
			executeCounterRemovalWhenDoSoAutoAbility(fa, source, isP1, effectIsP1, ctrM);
			return;
		}

		// Detect "pay 《X/N》. When you do so, [effect]" — requires a payment dialog before resolving.
		Matcher payM = FA_PAY_WHEN_DO_SO.matcher(fa.effectText());
		if (payM.find()) {
			executePayWhenDoSoAutoAbility(fa, source, isP1, effectIsP1, payM);
			return;
		}

		// Detect "remove N [type] [without 《Keyword》] you control from the game. When you do so, [effect]"
		Matcher rfM = FA_REMOVE_FIELD_WHEN_DO_SO.matcher(fa.effectText());
		if (rfM.find()) {
			executeRemoveFieldWhenDoSoAutoAbility(fa, source, isP1, effectIsP1, rfM);
			return;
		}

		// Detect "put N [Job/CardName/type] you control into the Break Zone. When you do so, [effect]"
		Matcher bzM = FA_PUT_INTO_BZ_WHEN_DO_SO.matcher(fa.effectText());
		if (bzM.find()) {
			executePutIntoBzWhenDoSoAutoAbility(fa, source, isP1, effectIsP1, bzM);
			return;
		}

		// Detect "put [CardName] into the Break Zone. If/When you do so, [effect]" (self-break)
		Matcher sbzM = FA_PUT_SELF_INTO_BZ_IF_DO_SO.matcher(fa.effectText());
		if (sbzM.find()) {
			executePutSelfIntoBzIfDoSoAutoAbility(fa, source, isP1, effectIsP1, sbzM);
			return;
		}

		// Detect "select [up to] N of the M following actions. "..." "..."..."
		Matcher selM = FA_SELECT_FOLLOWING_ACTIONS.matcher(fa.effectText());
		if (selM.find()) {
			executeSelectFollowingActionsAutoAbility(fa, source, isP1, effectIsP1, selM);
			return;
		}

		// Detect "reveal any number of Summons from your hand. When you reveal no Summons, [effect0]. When you reveal N or more Summons, [effectN]."
		Matcher rvlM = FA_REVEAL_SUMMONS_CONDITIONAL.matcher(fa.effectText());
		if (rvlM.find()) {
			executeRevealSummonsConditionalAutoAbility(fa, source, isP1, effectIsP1, rvlM);
			return;
		}

		// Detect "reveal any number of Summons from your hand. When you do so, [effect on up to the same number of Characters]."
		Matcher rvlSameM = FA_REVEAL_SUMMONS_SAME_NUMBER.matcher(fa.effectText());
		if (rvlSameM.find()) {
			executeRevealSummonsSameNumberAutoAbility(fa, source, isP1, effectIsP1, rvlSameM);
			return;
		}

		// Detect "select the following actions from top to bottom up to the same number of Elements other than X as the cost you paid to cast [CardName]."
		Matcher dynM = FA_SELECT_FOLLOWING_ACTIONS_DYNAMIC_ELEMENTS.matcher(fa.effectText());
		if (dynM.find()) {
			executeSelectFollowingActionsDynamicElements(fa, source, isP1, effectIsP1, dynM);
			return;
		}

		// Verify the effect is parseable before putting it on the stack.
		if (ActionResolver.parse(fa.effectText(), source) == null) {
			mw.logEntry("[AutoAbility] Unrecognized effect: " + fa.effectText());
			return;
		}

		// youMay / opponentMay: player decides at trigger time whether to put ability on stack.
		boolean p1GetsDialog = (fa.youMay() && isP1) || (fa.opponentMay() && !isP1);
		if (p1GetsDialog) {
			// If the effect requires discarding a card of a specific type, skip offering
			// when the player has no eligible cards in hand — nothing to choose from.
			String discardType = ActionResolver.youMayDiscardType(fa.effectText());
			if (discardType != null) {
				List<CardData> hand = effectIsP1 ? mw.gameState.getP1Hand() : mw.gameState.getP2Hand();
				boolean hasEligible = hand.stream().anyMatch(c -> matchesDiscardType(c, discardType));
				if (!hasEligible) {
					mw.logEntry("[AutoAbility] " + source.name() + " — no " + discardType + " in hand, offer skipped");
					return;
				}
			}
			int discardCount = ActionResolver.youMayDiscardCount(fa.effectText());
			if (discardCount > 0) {
				List<CardData> hand = effectIsP1 ? mw.gameState.getP1Hand() : mw.gameState.getP2Hand();
				if (hand.size() < discardCount) {
					mw.logEntry("[AutoAbility] " + source.name() + " — need " + discardCount + " cards to discard, have " + hand.size() + ", offer skipped");
					return;
				}
			}
			String prompt = (fa.youMay() ? "You may: " : "Your opponent may: ") + fa.effectText();
			int choice = mw.showEffectOptionDialog(source.name() + " — " + prompt,
					"Auto Ability", new Object[]{"OK", "Decline"});
			if (choice != 0) {
				mw.logEntry("[AutoAbility] " + source.name() + " — optional effect declined");
				return;
			}
		} else if (fa.youMay() || fa.opponentMay()) {
			mw.logEntry("[AutoAbility] [AI] auto-accepts optional ability");
		}

		if (fa.oncePerTurn())
			mw.usedOncePerTurnAbilities.computeIfAbsent(source, k -> new HashSet<>()).add(fa.effectText());

		// Reactive "chosen by opponent's Summons or abilities" triggers resolve INLINE, synchronously
		// within the opponent's in-progress target selection (see GameContextImpl.selectCharacters),
		// rather than being pushed onto the Stack.
		//
		// Under the rules the trigger goes on the Stack *above* the Summon or ability that chose it
		// and therefore resolves first. This engine cannot express that once the chooser is already
		// resolving — the selection can happen mid-resolution, at which point the chooser is off the
		// Stack and about to act on what it picked. Stacking the trigger there defers it behind the
		// chooser and inverts the order: a cancel becomes a no-op, and Emet-Selch (12-024H) is dealt
		// its lethal damage and broken before the removal that should have made that damage fizzle
		// ever runs. Resolving here reproduces the rules order in every path the selection can take.
		if (fa.trigger().startsWith("chosen by opponent's summon")) {
			Consumer<GameContext> effect = ActionResolver.parse(fa.effectText(), source);
			mw.logEntry("[AutoAbility] " + source.name() + " — " + fa.effectText());
			effect.accept(mw.buildGameContext(effectIsP1));
			return;
		}

		mw.logEntry("[AutoAbility] " + source.name() + " — pushed to stack");
		// An ability chooses its targets as it goes on the Stack, not when it resolves, so the
		// opponent can respond to what it is pointed at. The text is transformed the same way
		// resolution will transform it, or a conditional clause could change the eligible set.
		// The depth is taken first so any "when this is chosen" trigger the selection fires lands
		// above this entry and resolves before it (see GameState.insertStack).
		int depth = mw.gameState.stackSize();
		String effectText = paidExtraCost
				? ActionResolver.applyExtraCostPaid(fa.effectText())
				: ActionResolver.stripExtraCostClause(fa.effectText());
		List<ForwardTarget> preTargets = effectText.isBlank() ? null
				: ActionResolver.preSelectTargets(effectText, source, 0, mw.buildGameContext(effectIsP1));
		if (preTargets != null && preTargets.isEmpty()) preTargets = null;
		StackEntry entry = new StackEntry(source, null, fa, effectIsP1, 0, false, preTargets, false, paidExtraCost, 0, 0);
		mw.gameState.insertStack(depth, entry);
		mw.cancelFirstOppForwardAuto(entry);
	}

	private void executeCounterRemovalWhenDoSoAutoAbility(AutoAbility fa, CardData source,
			boolean isP1, boolean effectIsP1, Matcher m) {
		int    n           = Integer.parseInt(m.group("n"));
		String counterName = m.group("counterName").trim();
		String subEffect   = m.group("sub").trim();

		// Require enough counters to be present; skip silently if not.
		if (mw.gameState.getCounters(source, counterName) < n) {
			mw.logEntry("[AutoAbility] " + source.name() + " — not enough " + counterName
					+ " Counters (need " + n + ", have " + mw.gameState.getCounters(source, counterName) + ")");
			return;
		}

		// youMay / AI decision
		boolean p1GetsDialog = (fa.youMay() && isP1) || (fa.opponentMay() && !isP1);
		if (p1GetsDialog) {
			String prompt = (fa.youMay() ? "You may: " : "Your opponent may: ") + fa.effectText();
			int choice = mw.showEffectOptionDialog(source.name() + " — " + prompt,
					"Auto Ability", new Object[]{"OK", "Decline"});
			if (choice != 0) {
				mw.logEntry("[AutoAbility] " + source.name() + " — optional effect declined");
				return;
			}
		} else if (fa.youMay() || fa.opponentMay()) {
			mw.logEntry("[AutoAbility] [AI] auto-accepts optional ability");
		}

		// Remove the counter(s)
		int removed = mw.gameState.removeCounters(source, counterName, n);
		mw.logEntry("[AutoAbility] " + source.name() + " — removed " + removed + " " + counterName
				+ " Counter(s)  [remaining: " + mw.gameState.getCounters(source, counterName) + "]");

		// Execute the sub-effect
		Consumer<GameContext> effect = ActionResolver.parse(subEffect, source);
		if (effect == null) {
			mw.logEntry("[AutoAbility] Unrecognized counter-removal sub-effect: " + subEffect);
			return;
		}
		mw.logEntry("[AutoAbility] " + source.name() + " — when you do so: " + subEffect);
		effect.accept(mw.buildGameContext(effectIsP1));
	}

	private void executeRemoveFieldWhenDoSoAutoAbility(AutoAbility fa, CardData source,
			boolean isP1, boolean effectIsP1, Matcher m) {
		int     count          = Integer.parseInt(m.group("count"));
		String  targetsRaw     = m.group("targets").toLowerCase(java.util.Locale.ROOT);
		String  rawExcludeKw   = m.group("excludekw");
		boolean withoutMulticard = "Multicard".equalsIgnoreCase(rawExcludeKw != null ? rawExcludeKw.trim() : null);
		String  control        = m.group("control").toLowerCase(java.util.Locale.ROOT);
		boolean opponentOnly   = !control.contains("you control");
		boolean selfOnly       = !opponentOnly;
		boolean inclForwards   = targetsRaw.contains("forward") || targetsRaw.contains("character");
		boolean inclBackups    = targetsRaw.contains("backup")  || targetsRaw.contains("character");
		boolean inclMonsters   = targetsRaw.contains("monster") || targetsRaw.contains("character");
		String  subEffect      = m.group("sub").trim();

		// youMay / AI decision
		boolean p1GetsDialog = (fa.youMay() && isP1) || (fa.opponentMay() && !isP1);
		if (p1GetsDialog) {
			String prompt = (fa.youMay() ? "You may: " : "Your opponent may: ") + fa.effectText();
			int choice = mw.showEffectOptionDialog(source.name() + " — " + prompt,
					"Auto Ability", new Object[]{"OK", "Decline"});
			if (choice != 0) {
				mw.logEntry("[AutoAbility] " + source.name() + " — optional effect declined");
				return;
			}
		} else if (fa.youMay() || fa.opponentMay()) {
			mw.logEntry("[AutoAbility] [AI] auto-accepts optional ability");
		}

		// Select the card(s) to remove from the field
		GameContext ctx = mw.buildGameContext(effectIsP1);
		java.util.List<ForwardTarget> targets = ctx.selectCharacters(count, false,
				opponentOnly, selfOnly, null, null, -1, null, -1, null,
				inclForwards, inclBackups, inclMonsters, null, null, null, null, false, null, withoutMulticard);
		if (targets.isEmpty()) {
			mw.logEntry("[AutoAbility] " + source.name() + " — no valid target for field removal");
			return;
		}

		// Rebuild ctx after selectCharacters in case field indices shifted; remove targets
		GameContext ctx2 = mw.buildGameContext(effectIsP1);
		targets.forEach(t -> ctx2.removeTargetFromGame(t));

		// Parse and execute the sub-effect ("Its auto-ability will not trigger." is handled inside tryParsePlayFromHand)
		Consumer<GameContext> effect = ActionResolver.parse(subEffect, source);
		if (effect == null) {
			mw.logEntry("[AutoAbility] Unrecognized sub-effect: " + subEffect);
			return;
		}
		mw.logEntry("[AutoAbility] " + source.name() + " — when you do so: " + subEffect);
		effect.accept(mw.buildGameContext(effectIsP1));
	}

	private void executePutIntoBzWhenDoSoAutoAbility(AutoAbility fa, CardData source,
			boolean isP1, boolean effectIsP1, Matcher m) {
		int    count         = Integer.parseInt(m.group("count"));
		String jobRaw        = m.group("job");
		String cardNameRaw   = m.group("cardname");
		String typeRaw       = m.group("type");
		String elementRaw    = m.group("element");
		String subEffect     = m.group("sub").trim();

		String jobFilter      = jobRaw      != null ? jobRaw.trim()      : null;
		String cardNameFilter = cardNameRaw != null ? cardNameRaw.trim() : null;
		String elementFilter  = elementRaw  != null ? elementRaw.trim()  : null;
		boolean inclForwards, inclBackups, inclMonsters;
		if (jobFilter != null || cardNameFilter != null) {
			inclForwards = inclBackups = inclMonsters = true;
		} else if (typeRaw != null) {
			String tl = typeRaw.toLowerCase(java.util.Locale.ROOT);
			inclForwards = tl.contains("forward") || tl.contains("character");
			inclBackups  = tl.contains("backup")  || tl.contains("character");
			inclMonsters = tl.contains("monster") || tl.contains("character");
		} else {
			inclForwards = inclBackups = inclMonsters = true;
		}

		// youMay / AI decision
		boolean p1GetsDialog = (fa.youMay() && isP1) || (fa.opponentMay() && !isP1);
		if (p1GetsDialog) {
			String prompt = (fa.youMay() ? "You may: " : "Your opponent may: ") + fa.effectText();
			int choice = mw.showEffectOptionDialog(source.name() + " — " + prompt,
					"Auto Ability", new Object[]{"OK", "Decline"});
			if (choice != 0) {
				mw.logEntry("[AutoAbility] " + source.name() + " — optional effect declined");
				return;
			}
		} else if (fa.youMay() || fa.opponentMay()) {
			mw.logEntry("[AutoAbility] [AI] auto-accepts optional ability");
		}

		// Select the card(s) to put into the Break Zone
		GameContext ctx = mw.buildGameContext(effectIsP1);
		java.util.List<ForwardTarget> targets = ctx.selectCharacters(count, false,
				false, true, null, elementFilter, -1, null, -1, null,
				inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, null, null, false, null, false);
		if (targets.isEmpty()) {
			mw.logEntry("[AutoAbility] " + source.name() + " — no eligible target to put into Break Zone, sub-effect skipped");
			return;
		}

		// Rebuild ctx after selectCharacters in case field indices shifted; break the targets
		GameContext ctx2 = mw.buildGameContext(effectIsP1);
		targets.forEach(t -> ctx2.forceTargetToBreakZone(t));

		// Parse and execute the sub-effect
		Consumer<GameContext> effect = ActionResolver.parse(subEffect, source);
		if (effect == null) {
			mw.logEntry("[AutoAbility] Unrecognized sub-effect: " + subEffect);
			return;
		}
		mw.logEntry("[AutoAbility] " + source.name() + " — when you do so: " + subEffect);
		effect.accept(mw.buildGameContext(effectIsP1));
	}

	private void executePutSelfIntoBzIfDoSoAutoAbility(AutoAbility fa, CardData source,
			boolean isP1, boolean effectIsP1, Matcher m) {
		String cardName  = m.group("cardname").trim();
		String subEffect = m.group("sub").trim();

		if (!CardFilters.meetsCardNameFilter(source, cardName)) {
			mw.logEntry("[AutoAbility] " + source.name() + " — self-break: '" + cardName + "' does not match source, skipping");
			return;
		}

		// Only a printed "you may put …" is declinable, which the parser has already lifted into
		// youMay/opponentMay. "If you do so" is not the choice it looks like: it gates the
		// sub-effect on a step that has just happened unconditionally, and reading it as an offer
		// let the player refuse Clione 4-125C's own cost and keep it on the field — declining the
		// downside of a card whose upside is the cancel. Nine printings in this family are
		// mandatory (Clione, Grenade 5-008R, Buccaboo 5-046R, Leyak 5-071R, Black Knight 5-106R,
		// Tonberry 5-130R among them); the other thirty-one do say "you may".
		//
		// Gated exactly as the sibling executePutIntoBzWhenDoSoAutoAbility gates it, which had this
		// right already — the two differ only in whether the card put into the Break Zone is the
		// source itself or one it selects.
		boolean p1GetsDialog = (fa.youMay() && isP1) || (fa.opponentMay() && !isP1);
		if (p1GetsDialog) {
			int choice = mw.showEffectOptionDialog(source.name() + " — " + fa.effectText(),
					"Auto Ability", new Object[]{"Put into Break Zone", "Decline"});
			if (choice != 0) {
				mw.logEntry("[AutoAbility] " + source.name() + " — self-break declined");
				return;
			}
		} else if (fa.youMay() || fa.opponentMay()) {
			mw.logEntry("[AutoAbility] [AI] auto-accepts self-break for " + source.name());
		}

		// Break the source where it actually stands (no selection dialog needed — the text names it).
		//
		// All three rows are searched, not the Monsters alone. The family is mostly Backups (Bard
		// 12-028C, Summoner 12-031C, Red Mage 12-073C, Lilty 16-018C, Selkie 16-052C, Gladiator
		// 16-071C, Yuke 16-101C, Clavat 16-110C, Larsa 26-058H, Jack Garland 28-010R, Arciela
		// 28-058R) and Forwards (Tama 18-059R, Seifer 22-079L, Bhunivelze 24-033L, Cid Raines
		// 26-031H); a Monster-only lookup left every one of them logging "no longer on field" and
		// silently dropping the sub-effect it had just paid for.
		//
		// Matched by identity, which is what a card naming itself means and what findFieldSlot
		// already does. No corpus case distinguishes it from the name scan it replaces — the
		// same-name rule breaks the older copy on placement, so one side never holds two — but the
		// identity check is the one that stays right if that ever stops holding.
		ForwardTarget slot = mw.findFieldSlot(source, isP1);
		if (slot == null) {
			mw.logEntry("[AutoAbility] " + source.name() + " — no longer on field, sub-effect skipped");
			return;
		}
		mw.buildGameContext(isP1).forceTargetToBreakZone(slot);

		Consumer<GameContext> effect = ActionResolver.parse(subEffect, source);
		if (effect == null) {
			mw.logEntry("[AutoAbility] Unrecognized sub-effect: " + subEffect);
			return;
		}
		mw.logEntry("[AutoAbility] " + source.name() + " — if you do so: " + subEffect);
		effect.accept(mw.buildGameContext(effectIsP1));
	}

	private void executeRevealSummonsConditionalAutoAbility(AutoAbility fa, CardData source,
			boolean isP1, boolean effectIsP1, Matcher m) {
		String effect0 = m.group("effect0").trim();
		int    minN    = Integer.parseInt(m.group("n"));
		String effectN = m.group("effectN").trim();

		List<CardData> hand = effectIsP1 ? mw.gameState.getP1Hand() : mw.gameState.getP2Hand();
		List<CardData> summonsInHand = new ArrayList<>();
		for (CardData c : hand) if (c.isSummon()) summonsInHand.add(c);

		boolean p1GetsDialog = (fa.youMay() && isP1) || (fa.opponentMay() && !isP1);
		List<CardData> revealed;

		if (p1GetsDialog) {
			if (summonsInHand.isEmpty()) {
				mw.logEntry("[AutoAbility] " + source.name() + " — no Summons in hand, reveals 0");
				revealed = Collections.emptyList();
			} else {
				String prompt = (fa.youMay() ? "You may: " : "Your opponent may: ") + fa.effectText();
				int choice = mw.showEffectOptionDialog(source.name() + " — " + prompt,
						"Auto Ability", new Object[]{"Reveal...", "Decline"});
				if (choice != 0) {
					mw.logEntry("[AutoAbility] " + source.name() + " — optional effect declined");
					return;
				}
				revealed = mw.showRevealSummonsFromHandDialog(summonsInHand, source.name(), minN);
			}
		} else {
			// CPU logic: decline if 0 summons; reveal 1 if only 1 available; reveal exactly minN if 2+
			if (summonsInHand.isEmpty()) {
				mw.logEntry("[AutoAbility] [AI] " + source.name() + " — no Summons in hand, declines");
				return;
			} else if (summonsInHand.size() < minN) {
				revealed = new ArrayList<>(summonsInHand.subList(0, 1));
				mw.logEntry("[AutoAbility] [AI] " + source.name() + " — reveals 1 Summon: " + summonsInHand.get(0).name());
			} else {
				revealed = new ArrayList<>(summonsInHand.subList(0, minN));
				StringBuilder sb = new StringBuilder();
				for (int i = 0; i < revealed.size(); i++) {
					if (i > 0) sb.append(", ");
					sb.append(revealed.get(i).name());
				}
				mw.logEntry("[AutoAbility] [AI] " + source.name() + " — reveals " + minN + " Summon(s): " + sb);
			}
		}

		int count = revealed.size();
		if (count == 0) {
			mw.logEntry("[AutoAbility] " + source.name() + " — revealed 0 Summons → " + effect0);
			Consumer<GameContext> fn = ActionResolver.parse(effect0, source);
			if (fn != null) fn.accept(mw.buildGameContext(effectIsP1));
			else mw.logEntry("[AutoAbility] Unrecognized zero-reveal effect: " + effect0);
		} else if (count >= minN) {
			mw.logEntry("[AutoAbility] " + source.name() + " — revealed " + count + " Summon(s) → " + effectN);
			Consumer<GameContext> fn = ActionResolver.parse(effectN, source);
			if (fn != null) fn.accept(mw.buildGameContext(effectIsP1));
			else mw.logEntry("[AutoAbility] Unrecognized min-reveal effect: " + effectN);
		} else {
			mw.logEntry("[AutoAbility] " + source.name() + " — revealed " + count + " Summon(s), no additional effect");
		}
	}

	/**
	 * Writes the revealed count into the follow-up sentence: "up to the same number of Characters
	 * as the Summons you revealed" becomes "up to 2 Characters", which the resolver already reads.
	 */
	static String withRevealedCount(String effectText, int count) {
		return SAME_NUMBER_AS_REVEALED.matcher(effectText).replaceAll(count + " ${type}");
	}

	/**
	 * 15-037L Terra: reveal any number of Summons from hand, then run a follow-up effect on up to
	 * that many Characters.
	 *
	 * <p>The count is only known once the reveal is done, so the follow-up is written back into its
	 * own sentence — "up to the same number of Characters as the Summons you revealed" becomes
	 * "up to N Characters" — and handed to the resolver, which already reads that shape. Revealing
	 * nothing means "when you do so" never happened, so no follow-up runs at all.
	 */
	private void executeRevealSummonsSameNumberAutoAbility(AutoAbility fa, CardData source,
			boolean isP1, boolean effectIsP1, Matcher m) {
		String effectText = m.group("effect").trim();

		List<CardData> hand = effectIsP1 ? mw.gameState.getP1Hand() : mw.gameState.getP2Hand();
		List<CardData> summonsInHand = new ArrayList<>();
		for (CardData c : hand) if (c.isSummon()) summonsInHand.add(c);

		boolean p1GetsDialog = (fa.youMay() && isP1) || (fa.opponentMay() && !isP1);
		List<CardData> revealed;

		if (summonsInHand.isEmpty()) {
			mw.logEntry("[AutoAbility] " + source.name() + " — no Summons in hand, reveals 0");
			return;
		}
		if (p1GetsDialog) {
			String prompt = (fa.youMay() ? "You may: " : "Your opponent may: ") + fa.effectText();
			int choice = mw.showEffectOptionDialog(source.name() + " — " + prompt,
					"Auto Ability", new Object[]{"Reveal...", "Decline"});
			if (choice != 0) {
				mw.logEntry("[AutoAbility] " + source.name() + " — optional effect declined");
				return;
			}
			revealed = mw.showRevealSummonsFromHandDialog(summonsInHand, source.name(),
					"Reveal any number — you then get that many targets.");
		} else {
			// The AI reveals everything it can: the count is the target count, and a revealed
			// Summon stays in hand, so there is nothing to weigh against taking the maximum.
			revealed = new ArrayList<>(summonsInHand);
			StringBuilder names = new StringBuilder();
			for (CardData c : revealed) names.append(names.length() == 0 ? "" : ", ").append(c.name());
			mw.logEntry("[AutoAbility] [AI] " + source.name() + " — reveals " + revealed.size()
					+ " Summon(s): " + names);
		}

		int count = revealed.size();
		if (count == 0) {
			mw.logEntry("[AutoAbility] " + source.name() + " — revealed 0 Summons, no effect");
			return;
		}
		String resolved = withRevealedCount(effectText, count);
		Consumer<GameContext> fn = ActionResolver.parse(resolved, source);
		if (fn == null) {
			mw.logEntry("[AutoAbility] Unrecognized reveal-scaled effect: " + resolved);
			return;
		}
		mw.logEntry("[AutoAbility] " + source.name() + " — revealed " + count + " Summon(s) → " + resolved);
		fn.accept(mw.buildGameContext(effectIsP1));
	}

	private void executePayWhenDoSoAutoAbility(AutoAbility fa, CardData source, boolean isP1,
			boolean effectIsP1, Matcher payM) {
		String costToken = payM.group(1).trim();
		String subEffect = payM.group(2).trim().replaceAll("[.!,]+$", "");

		boolean isXCost = costToken.equalsIgnoreCase("X");
		boolean isElementCost = !isXCost && ELEMENT_NAMES.stream()
				.anyMatch(e -> costToken.toLowerCase(java.util.Locale.ROOT).contains(e));
		int fixedCost;
		if (!isXCost) {
			if (isElementCost) {
				fixedCost = 1;
			} else {
				try { fixedCost = Integer.parseInt(costToken); }
				catch (NumberFormatException e) {
					// Non-numeric, non-X cost token (e.g. 《C》 for crystal) — resolve normally.
					Consumer<GameContext> effect = ActionResolver.parse(fa.effectText(), source);
					if (effect != null) { mw.logEntry("[AutoAbility] " + source.name() + " — " + fa.effectText()); effect.accept(mw.buildGameContext(effectIsP1)); }
					else mw.logEntry("[AutoAbility] Unrecognized effect: " + fa.effectText());
					return;
				}
			}
		} else { fixedCost = 0; }

		Matcher maxM = FA_MAX_X.matcher(fa.effectText());
		int maxCp = isXCost ? (maxM.find() ? Integer.parseInt(maxM.group(1)) : Integer.MAX_VALUE) : fixedCost;

		// For fixed CP costs, check whether the paying player can actually generate enough CP.
		// effectIsP1 identifies the player who would pay (already accounts for opponentMay).
		// Skip the ability entirely if they cannot — no active backups and insufficient hand cards.
		if (!isXCost && fixedCost > 0) {
			CardData[] bkpCards  = mw.playerBackupCards(effectIsP1);
			CardState[] bkpStates = mw.playerBackupStates(effectIsP1);
			int availCp = 0;
			for (int i = 0; i < bkpCards.length; i++)
				if (bkpCards[i] != null && bkpStates[i] == CardState.ACTIVE) availCp++;
			availCp += mw.playerHand(effectIsP1).size() * 2;
			if (availCp < fixedCost) {
				mw.logEntry("[AutoAbility] " + source.name() + " — cannot afford " + fixedCost + " CP (" + costToken + "), skipping");
				return;
			}
		}

		// P1 gets a confirm dialog; AI auto-accepts.
		boolean p1GetsDialog = (fa.youMay() && isP1) || (fa.opponentMay() && !isP1);
		if (p1GetsDialog) {
			String prompt = (fa.youMay() ? "You may: " : "Your opponent may: ") + fa.effectText();
			int choice = mw.showEffectOptionDialog(source.name() + " — " + prompt,
					"Auto Ability", new Object[]{"OK", "Decline"});
			if (choice != 0) {
				mw.logEntry("[AutoAbility] " + source.name() + " — optional effect declined");
				return;
			}
		} else if (fa.youMay() || fa.opponentMay()) {
			// Decline if the effect targets Forwards but the opponent has none to target.
			boolean effectNeedsForward = subEffect.toLowerCase(java.util.Locale.ROOT).contains("forward");
			if (effectNeedsForward && mw.p1ForwardCards.isEmpty()) {
				mw.logEntry("[AutoAbility] [AI] declines optional ability — no opponent Forwards to target");
				return;
			}
			mw.logEntry("[AutoAbility] [AI] auto-accepts optional ability");
		}

		if (!isP1) {
			int target = isXCost ? 1 : fixedCost;
			int paid   = aiPayCp(effectIsP1, target);
			applyPayWhenDoSoEffect(subEffect, source, paid, effectIsP1);
			return;
		}

		String finalSubEffect = subEffect;
		showAutoAbilityPaymentDialog(source.name(), fixedCost, maxCp, isP1, 0,
				paid -> applyPayWhenDoSoEffect(finalSubEffect, source, paid, effectIsP1), null);
	}

	private void applyPayWhenDoSoEffect(String subEffect, CardData source, int xValue, boolean effectIsP1) {
		GameContext ctx = mw.buildGameContext(effectIsP1);
		// "Gain 《C》 for each CP paid as X" must be resolved with the known xValue directly —
		// the generic parse chain would see xValue=0 for this pattern and give 0 crystals.
		if (ActionResolver.isGainCrystalPerX(subEffect)) {
			ctx.logEntry("Effect: Gain " + xValue + " Crystal(s) (for each CP paid as X)");
			ctx.gainCrystal(xValue);
			return;
		}
		Consumer<GameContext> effect = ActionResolver.parse(subEffect, source, xValue);
		if (effect == null) {
			mw.logEntry("[AutoAbility] Unrecognized 'when you do so' effect: " + subEffect);
			return;
		}
		mw.logEntry("[AutoAbility] " + source.name() + " — when you do so: " + subEffect + " (X=" + xValue + ")");
		effect.accept(ctx);
	}

	/**
	 * Has the AI pay up to {@code target} CP by dulling active backups then discarding hand cards.
	 * Returns the amount actually paid.
	 */
	int aiPayCp(boolean payerIsP1, int target) {
		if (target <= 0) return 0;
		CardData[]  bkpCards  = mw.playerBackupCards(payerIsP1);
		CardState[] bkpStates = mw.playerBackupStates(payerIsP1);
		int paid = 0;
		for (int i = 0; i < bkpCards.length && paid < target; i++) {
			if (bkpCards[i] != null && bkpStates[i] == CardState.ACTIVE) {
				bkpStates[i] = CardState.DULL;
				mw.playerDullBackupSlot(payerIsP1, i);
				paid++;
				mw.logEntry("[AI] Pay CP: dull " + bkpCards[i].name() + " (" + paid + "/" + target + ")");
			}
		}
		List<Integer> discardIdx = new ArrayList<>();
		List<CardData> hand = mw.playerHand(payerIsP1);
		for (int i = hand.size() - 1; i >= 0 && paid < target; i--) {
			mw.logEntry("[AI] Pay CP: discard " + hand.get(i).name() + " from hand (" + Math.min(paid + 2, target) + "/" + target + ")");
			discardIdx.add(i);
			paid += 2;
		}
		for (int di : discardIdx) mw.playerBreakFromHand(payerIsP1, di);
		return Math.min(paid, target);
	}

	// ─── "Select N of M following actions" auto-ability ─────────────────────────

	private void executeSelectFollowingActionsAutoAbility(
			AutoAbility fa, CardData source, boolean isP1, boolean effectIsP1,
			Matcher m) {

		// Optional "if condition" prefix
		String condition = m.group("condition");
		if (condition != null && !checkAutoAbilityCondition(condition.trim(), isP1)) {
			mw.logEntry("[AutoAbility] " + source.name() + " — condition not met: " + condition);
			return;
		}

		boolean upTo       = m.group("upTo") != null;
		int     selectCount = Integer.parseInt(m.group("select"));
		int     totalCount  = Integer.parseInt(m.group("total"));

		// youMay / opponentMay decline dialog (the select dialog itself is the interaction,
		// but we still honour an explicit "you may" decline option)
		boolean p1GetsDialog = (fa.youMay() && isP1) || (fa.opponentMay() && !isP1);
		if (p1GetsDialog) {
			String prompt = "Select " + (upTo ? "up to " : "") + selectCount + " of "
					+ totalCount + " actions for " + source.name() + "?";
			int choice = mw.showEffectOptionDialog(prompt, "Auto Ability",
					new Object[]{"Choose Actions", "Decline"});
			if (choice != 0) {
				mw.logEntry("[AutoAbility] " + source.name() + " — optional select declined");
				return;
			}
		} else if (fa.youMay() || fa.opponentMay()) {
			mw.logEntry("[AutoAbility] [AI] auto-accepts select ability");
		}

		if (fa.oncePerTurn())
			mw.usedOncePerTurnAbilities.computeIfAbsent(source, k -> new HashSet<>())
					.add(fa.effectText());

		Consumer<GameContext> effect = ActionResolver.parse(fa.effectText(), source);
		if (effect == null) {
			mw.logEntry("[AutoAbility] " + source.name() + " — no actions found in select effect");
			return;
		}
		effect.accept(mw.buildGameContext(effectIsP1));
	}

	private void executeSelectFollowingActionsDynamicElements(
			AutoAbility fa, CardData source, boolean isP1, boolean effectIsP1, Matcher m) {
		String excludeElem = m.group("excludeelem");
		String actionsRaw  = m.group("actions");

		List<String> actions = new ArrayList<>();
		Matcher qm = ActionResolverPatterns.SELECT_FOLLOWING_QUOTED_ACTION.matcher(actionsRaw);
		while (qm.find()) actions.add(qm.group(1).trim());
		if (actions.isEmpty()) {
			mw.logEntry("[AutoAbility] " + source.name() + " — no actions found in dynamic select");
			return;
		}

		int maxCount = (int) mw.lastCastActualPaymentElements.stream()
				.filter(e -> !e.equalsIgnoreCase(excludeElem))
				.count();
		maxCount = Math.min(maxCount, actions.size());
		mw.logEntry("[AutoAbility] " + source.name() + " — " + maxCount
				+ " non-" + excludeElem + " element(s) used, up to " + maxCount + " action(s) available");

		if (maxCount == 0) return;

		int chosenCount;
		if (isP1) {
			chosenCount = showChooseActionCountDialog(source, actions, maxCount, excludeElem);
		} else {
			chosenCount = maxCount;
			mw.logEntry("[AutoAbility] [AI] " + source.name() + " takes " + chosenCount + " action(s) from top");
		}

		if (fa.oncePerTurn())
			mw.usedOncePerTurnAbilities.computeIfAbsent(source, k -> new HashSet<>())
					.add(fa.effectText());

		GameContext ctx = mw.buildGameContext(effectIsP1);
		for (int i = 0; i < chosenCount; i++) {
			String actionText = actions.get(i);
			Consumer<GameContext> effect = ActionResolver.parse(actionText, source);
			if (effect == null) {
				ctx.logEntry(source.name() + " action " + (i + 1) + " — unrecognized: " + actionText);
			} else {
				ctx.logEntry((isP1 ? "Selected: " : "[AI] Selected: ") + actionText);
				effect.accept(ctx);
			}
		}
	}

	private int showChooseActionCountDialog(
			CardData source, List<String> actions, int maxCount, String excludeElem) {
		StringBuilder msg = new StringBuilder("<html><body style='width:340px'>");
		msg.append("Non-").append(excludeElem).append(" elements paid: <b>").append(maxCount)
		   .append("</b>. Select how many actions to take from the top, in order:<br><br>");
		for (int i = 0; i < actions.size(); i++) {
			if (i < maxCount)
				msg.append("&nbsp;").append(i + 1).append(". ").append(actions.get(i)).append("<br>");
			else
				msg.append("<font color='gray'>&nbsp;").append(i + 1).append(". ")
				   .append(actions.get(i)).append("</font><br>");
		}
		msg.append("</body></html>");

		Object[] options = new Object[maxCount + 1];
		for (int i = 0; i <= maxCount; i++) options[i] = "Take " + i;

		int choice = mw.showEffectOptionDialog(msg.toString(),
				source.name() + " — Select Actions (Top to Bottom)", options);
		return (choice >= 0 && choice <= maxCount) ? choice : 0;
	}

	/**
	 * Shows a modal dialog for P1 to choose actions from a "select N of M" list.
	 * Uses radio buttons when exactly 1 must be chosen, checkboxes otherwise.
	 * Returns the chosen action texts, or an empty list if the dialog is dismissed.
	 */
	List<String> showSelectActionsDialog(
			CardData source, List<String> actions, int selectCount, boolean upTo) {

		int  n             = actions.size();
		boolean singlePick = selectCount == 1 && !upTo;
		String title = source.name() + " — Select "
				+ (upTo ? "up to " : "") + selectCount + " action" + (selectCount != 1 || upTo ? "s" : "");

		JDialog dlg = new JDialog(mw.frame, title, true);
		dlg.setResizable(false);
		dlg.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

		List<String> result = new ArrayList<>();

		JPanel choicesPanel = new JPanel(new GridLayout(0, 1, 0, 6));
		choicesPanel.setBorder(BorderFactory.createEmptyBorder(10, 12, 6, 12));

		JButton confirmBtn = new JButton("Confirm");
		confirmBtn.setFont(FontLoader.loadPixelFont(11));

		if (singlePick) {
			// ── Radio buttons — exactly one action ──
			javax.swing.ButtonGroup group = new javax.swing.ButtonGroup();
			javax.swing.JRadioButton[] radios = new javax.swing.JRadioButton[n];
			for (int i = 0; i < n; i++) {
				javax.swing.JRadioButton rb = new javax.swing.JRadioButton(
						"<html><body style='width:340px'>" + actions.get(i) + "</body></html>");
				rb.setFont(FontLoader.loadPixelFont(10));
				group.add(rb);
				radios[i] = rb;
				choicesPanel.add(rb);
			}
			radios[0].setSelected(true);
			confirmBtn.addActionListener(ae -> {
				for (int i = 0; i < radios.length; i++)
					if (radios[i].isSelected()) { result.add(actions.get(i)); break; }
				dlg.dispose();
			});
		} else {
			// ── Checkboxes — up to N, or exactly N ──
			javax.swing.JCheckBox[] checks = new javax.swing.JCheckBox[n];
			JLabel countLbl = new JLabel(
					"Selected: 0 / " + selectCount + (upTo ? " (up to)" : ""),
					SwingConstants.CENTER);
			countLbl.setFont(FontLoader.loadPixelFont(10));

			for (int i = 0; i < n; i++) {
				javax.swing.JCheckBox cb = new javax.swing.JCheckBox(
						"<html><body style='width:340px'>" + actions.get(i) + "</body></html>");
				cb.setFont(FontLoader.loadPixelFont(10));
				checks[i] = cb;
				cb.addItemListener(ie -> {
					int sel = 0;
					for (javax.swing.JCheckBox c : checks) if (c.isSelected()) sel++;
					countLbl.setText("Selected: " + sel + " / " + selectCount + (upTo ? " (up to)" : ""));
					// Disable unchecked boxes once limit is reached (applies to both exact and up-to)
					if (sel >= selectCount) {
						for (javax.swing.JCheckBox c : checks) if (!c.isSelected()) c.setEnabled(false);
					} else {
						for (javax.swing.JCheckBox c : checks) c.setEnabled(true);
					}
					confirmBtn.setEnabled(upTo || sel == selectCount);
				});
				choicesPanel.add(cb);
			}
			confirmBtn.setEnabled(upTo); // "up to" can confirm with 0; exact needs N selected
			confirmBtn.addActionListener(ae -> {
				for (int i = 0; i < checks.length; i++)
					if (checks[i].isSelected()) result.add(actions.get(i));
				dlg.dispose();
			});

			JPanel countRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 2));
			countRow.add(countLbl);
			choicesPanel.add(countRow);
		}

		JPanel south = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 6));
		south.add(confirmBtn);

		dlg.getContentPane().setLayout(new BorderLayout(0, 4));
		dlg.getContentPane().add(choicesPanel, BorderLayout.CENTER);
		dlg.getContentPane().add(south,        BorderLayout.SOUTH);
		dlg.pack();
		dlg.setLocationRelativeTo(mw.frame);
		dlg.setVisible(true);
		return result;
	}

	/**
	 * Evaluates a simple auto-ability precondition such as
	 * "you control a Job AVALANCHE Operative Forward".
	 * Returns {@code true} when the condition is satisfied, or when the condition
	 * text is not recognised (fail-open to avoid silently blocking abilities).
	 */
	private boolean checkAutoAbilityCondition(String condition, boolean isP1) {
		String lo = condition.toLowerCase(java.util.Locale.ROOT).trim();
		if (lo.startsWith("you control a") || lo.startsWith("you control an")) {
			String spec = lo.replaceFirst("^you\\s+control\\s+an?\\s+", "").trim();
			return controlsMatchingCard(spec, isP1);
		}
		// "your opponent has [no|N cards or less] cards in their hand"
		Matcher oppHandM = OPP_HAND_AT_MOST_CONDITION.matcher(lo);
		if (oppHandM.matches()) {
			int threshold = oppHandM.group("n") != null ? Integer.parseInt(oppHandM.group("n")) : 0;
			int oppHand   = (isP1 ? mw.gameState.getP2Hand() : mw.gameState.getP1Hand()).size();
			return oppHand <= threshold;
		}
		mw.logEntry("[AutoAbility] Unrecognized condition (defaulting to true): " + condition);
		return true;
	}

	/** "your opponent has [no|N cards or less] cards in their hand" — {@code n} absent means "no cards" (0). */
	private static final Pattern OPP_HAND_AT_MOST_CONDITION = Pattern.compile(
		"(?i)^your\\s+opponent\\s+has\\s+(?:no\\s+cards?|(?<n>\\d+)\\s+cards?\\s+or\\s+less)\\s+in\\s+" +
		"(?:his/her|his|her|their)\\s+hand$");

	/**
	 * Returns {@code true} if the given player has at least one card on the field that matches
	 * a description such as "forward", "job avalanche operative forward", "ice backup", etc.
	 */
	private boolean controlsMatchingCard(String spec, boolean isP1) {
		// Collect all field cards for this player
		List<CardData> field = new ArrayList<>();
		field.addAll(isP1 ? mw.p1ForwardCards : mw.p2ForwardCards);
		for (CardData c : (isP1 ? mw.p1BackupCards : mw.p2BackupCards)) if (c != null) field.add(c);
		field.addAll(isP1 ? mw.p1MonsterCards : mw.p2MonsterCards);

		// Determine target type restriction
		String specLo = spec.toLowerCase(java.util.Locale.ROOT);
		String requiredType = null;
		if      (specLo.endsWith("forward"))   requiredType = "Forward";
		else if (specLo.endsWith("backup"))    requiredType = "Backup";
		else if (specLo.endsWith("monster"))   requiredType = "Monster";
		else if (specLo.endsWith("character")) requiredType = null; // any type matches

		// Strip the type suffix to isolate job / element qualifiers
		String qualifiers = specLo
				.replaceAll("(?i)\\s+(forward|backup|monster|character)$", "").trim();
		// Strip leading "job " keyword if present (keep the actual job name)
		String jobFilter = qualifiers.startsWith("job ")
				? qualifiers.replaceFirst("^job\\s+", "").trim()
				: (qualifiers.isEmpty() ? null : qualifiers);

		for (CardData c : field) {
			if (c == null) continue;
			if (requiredType != null && !c.type().equalsIgnoreCase(requiredType)
					&& !(requiredType.equalsIgnoreCase("Monster") && c.alsoCountsAsMonster())) continue;
			if (jobFilter != null && !c.job().toLowerCase(java.util.Locale.ROOT).contains(jobFilter)) continue;
			return true;
		}
		return false;
	}

	/**
	 * Payment dialog for a auto ability that requires CP payment.
	 * Shows backup cards (1 CP each) and hand cards to discard (2 CP each), and calls
	 * {@code onConfirm} with total CP paid after dulling backups / discarding cards.
	 *
	 * <p>When {@code crystalAltCost > 0}, also adds a "Pay N Crystal" button that lets the player
	 * satisfy the whole cost with Crystals instead of assembling CP (disabled when the player holds
	 * fewer than {@code crystalAltCost} Crystals); pass {@code 0} and a {@code null} {@code onCrystalPaid}
	 * when there is no Crystal alternative. Exactly one of the callbacks fires when the player commits:
	 * {@code onConfirm} (with CP paid) for the CP route, or {@code onCrystalPaid} for the Crystal route
	 * (Crystals already spent). Neither fires on Cancel.
	 */
	void showAutoAbilityPaymentDialog(String cardName, int minCp, int maxCp,
			boolean isP1, int crystalAltCost, java.util.function.IntConsumer onConfirm, Runnable onCrystalPaid) {
		CardData[]     bkpCards  = mw.playerBackupCards(isP1);
		CardState[]    bkpStates = mw.playerBackupStates(isP1);
		String[]       bkpUrls  = mw.playerBackupUrls(isP1);
		List<CardData> hand      = mw.playerHand(isP1);

		String title = (maxCp == minCp)
				? cardName + " — Pay " + minCp + " CP"
				: cardName + " — Pay up to " + (maxCp == Integer.MAX_VALUE ? "any" : maxCp) + " CP";
		JDialog dlg = new JDialog(mw.frame, title, true);
		dlg.setResizable(false);
		dlg.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

		List<Integer> selectedBackups  = new ArrayList<>();
		List<Integer> selectedDiscards = new ArrayList<>();

		JLabel   cpLabel    = new JLabel();
		cpLabel.setFont(FontLoader.loadPixelFont(11));
		cpLabel.setHorizontalAlignment(SwingConstants.CENTER);

		JButton confirmBtn = new JButton("Confirm");
		confirmBtn.setFont(FontLoader.loadPixelFont(11));

		List<JLabel>  backupLbls  = new ArrayList<>();
		List<Integer> backupSlots = new ArrayList<>();
		List<JLabel>  discardLbls = new ArrayList<>();
		List<Integer> discardIdxs = new ArrayList<>();

		boolean[] canAddBackup  = {true};
		boolean[] canAddDiscard = {true};

		Runnable updateAll = () -> {
			int total  = selectedBackups.size() + selectedDiscards.size() * 2;
			if (minCp == maxCp) {
				// Fixed cost: any amount of CP may be produced when paying it, and CP produced
				// beyond the cost is wasted rather than counted as paid (see the Confirm handler).
				canAddBackup[0]  = true;
				canAddDiscard[0] = true;
			} else {
				// Variable X cost: maxCp is the effect's own "up to N" bound on X, not the
				// overpayment rule, so it still caps what can be produced here.
				boolean atMax = maxCp != Integer.MAX_VALUE && total >= maxCp;
				canAddBackup[0]  = !atMax;
				canAddDiscard[0] = maxCp == Integer.MAX_VALUE || total + 2 <= maxCp;
			}
			confirmBtn.setEnabled(total >= minCp);

			String cap = maxCp == Integer.MAX_VALUE ? "∞" : String.valueOf(maxCp);
			cpLabel.setText("CP produced: " + total + " / " + cap
					+ (minCp > 0 ? "  (min " + minCp + ")" : ""));

			for (int i = 0; i < backupLbls.size(); i++) {
				JLabel  lbl = backupLbls.get(i);
				boolean sel = selectedBackups.contains(backupSlots.get(i));
				lbl.setBorder(sel ? MainWindow.createCardGlowBorder(Color.YELLOW) : BorderFactory.createLineBorder(canAddBackup[0] ? Color.GRAY : new Color(80, 80, 80), 1));
				lbl.setBackground(sel || canAddBackup[0] ? Color.DARK_GRAY : new Color(50, 50, 50));
				lbl.setCursor(sel || canAddBackup[0]
						? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
			}
			for (int i = 0; i < discardLbls.size(); i++) {
				JLabel  lbl = discardLbls.get(i);
				boolean sel = selectedDiscards.contains(discardIdxs.get(i));
				lbl.setBorder(sel ? MainWindow.createCardGlowBorder(Color.YELLOW) : BorderFactory.createLineBorder(canAddDiscard[0] ? Color.GRAY : new Color(80, 80, 80), 1));
				lbl.setBackground(sel || canAddDiscard[0] ? Color.DARK_GRAY : new Color(50, 50, 50));
				lbl.setCursor(sel || canAddDiscard[0]
						? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
			}
		};
		updateAll.run();

		JPanel center = new JPanel();
		center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));

		List<Integer> eligibleBackupSlots = new ArrayList<>();
		for (int i = 0; i < bkpCards.length; i++)
			if (bkpCards[i] != null && bkpStates[i] == CardState.ACTIVE) eligibleBackupSlots.add(i);

		if (!eligibleBackupSlots.isEmpty()) {
			JLabel hdr = new JLabel("Backups — dull for 1 CP each:");
			hdr.setFont(FontLoader.loadPixelFont(9)); hdr.setAlignmentX(Component.LEFT_ALIGNMENT);
			JPanel bp = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6)); bp.setAlignmentX(Component.LEFT_ALIGNMENT);
			for (int slot : eligibleBackupSlots) {
				JLabel lbl = new JLabel("...", SwingConstants.CENTER);
				lbl.setPreferredSize(new Dimension(CARD_W, CARD_H)); lbl.setMinimumSize(new Dimension(CARD_W, CARD_H));
				lbl.setOpaque(true); lbl.setBackground(Color.DARK_GRAY); lbl.setForeground(Color.WHITE);
				lbl.setFont(FontLoader.loadPixelFont(10)); lbl.setBorder(BorderFactory.createLineBorder(Color.GRAY, 1));
				lbl.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
				final String url = bkpUrls[slot];
				lbl.addMouseListener(new MouseAdapter() {
					@Override public void mousePressed(MouseEvent ev) {
						if (!selectedBackups.remove(Integer.valueOf(slot)) && canAddBackup[0]) selectedBackups.add(slot);
						updateAll.run();
					}
					@Override public void mouseEntered(MouseEvent ev) { if (lbl.getIcon() != null) mw.showZoomAt(url); }
					@Override public void mouseExited(MouseEvent ev)  { mw.hideZoom(); }
				});
				new SwingWorker<ImageIcon, Void>() {
					@Override protected ImageIcon doInBackground() throws Exception {
						Image img = ImageCache.load(url);
						return img == null ? null : new ImageIcon(img.getScaledInstance(CARD_W, CARD_H, Image.SCALE_SMOOTH));
					}
					@Override protected void done() {
						try { ImageIcon ic = get(); if (ic != null) { lbl.setIcon(ic); lbl.setText(null); } }
						catch (InterruptedException | ExecutionException ignored) {}
					}
				}.execute();
				backupLbls.add(lbl); backupSlots.add(slot); bp.add(lbl);
			}
			center.add(hdr); center.add(bp);
		}

		if (!hand.isEmpty()) {
			java.util.Set<String> ldGrants = mw.lightDarkDiscardGrants(isP1);
			JLabel discHdr = new JLabel("Hand — discard for 2 CP each:");
			discHdr.setFont(FontLoader.loadPixelFont(9)); discHdr.setAlignmentX(Component.LEFT_ALIGNMENT);
			JPanel dp = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6)); dp.setAlignmentX(Component.LEFT_ALIGNMENT);
			for (int i = 0; i < hand.size(); i++) {
				final int hi = i; CardData hc = hand.get(i);
				boolean payable = CpPaymentUtils.canDiscardForCp(hc, ldGrants);
				JLabel lbl = new JLabel("...", SwingConstants.CENTER);
				lbl.setPreferredSize(new Dimension(CARD_W, CARD_H)); lbl.setMinimumSize(new Dimension(CARD_W, CARD_H));
				lbl.setOpaque(true); lbl.setBackground(payable ? Color.DARK_GRAY : new Color(50, 50, 50));
				lbl.setForeground(Color.WHITE); lbl.setFont(FontLoader.loadPixelFont(10));
				lbl.setBorder(BorderFactory.createLineBorder(payable ? Color.GRAY : new Color(80, 80, 80), 1));
				lbl.setCursor(payable ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
				final String imgUrl = hc.imageUrl();
				if (payable) {
					lbl.addMouseListener(new MouseAdapter() {
						@Override public void mousePressed(MouseEvent ev) {
							if (!selectedDiscards.remove(Integer.valueOf(hi)) && canAddDiscard[0]) selectedDiscards.add(hi);
							updateAll.run();
						}
						@Override public void mouseEntered(MouseEvent ev) { if (lbl.getIcon() != null) mw.showZoomAt(imgUrl); }
						@Override public void mouseExited(MouseEvent ev)  { mw.hideZoom(); }
					});
					discardLbls.add(lbl); discardIdxs.add(hi);
				} else {
					lbl.addMouseListener(new MouseAdapter() {
						@Override public void mouseEntered(MouseEvent ev) { if (lbl.getIcon() != null) mw.showZoomAt(imgUrl); }
						@Override public void mouseExited(MouseEvent ev)  { mw.hideZoom(); }
					});
				}
				new SwingWorker<ImageIcon, Void>() {
					@Override protected ImageIcon doInBackground() throws Exception {
						Image img = ImageCache.load(imgUrl);
						return img == null ? null : new ImageIcon(img.getScaledInstance(CARD_W, CARD_H, Image.SCALE_SMOOTH));
					}
					@Override protected void done() {
						try { ImageIcon ic = get(); if (ic != null) { lbl.setIcon(ic); lbl.setText(null); } }
						catch (InterruptedException | ExecutionException ignored) {}
					}
				}.execute();
				dp.add(lbl);
			}
			center.add(discHdr); center.add(dp);
		}

		JButton cancelBtn = new JButton("Cancel");
		cancelBtn.setFont(FontLoader.loadPixelFont(11));
		cancelBtn.addActionListener(ev -> {
			mw.logEntry("[AutoAbility] " + cardName + " — payment cancelled");
			dlg.dispose();
		});
		confirmBtn.addActionListener(ev -> {
			dlg.dispose();
			for (int slot : selectedBackups) {
				bkpStates[slot] = CardState.DULL;
				mw.playerDullBackupSlot(isP1, slot);
			}
			List<Integer> sortedDiscards = new ArrayList<>(selectedDiscards);
			sortedDiscards.sort(Collections.reverseOrder());
			for (int di : sortedDiscards) mw.playerBreakFromHand(isP1, di);
			// Producing CP beyond the cost is legal but the surplus is not part of the payment:
			// clamp so an odd fixed cost paid with a 2-CP discard can't inflate X.
			int produced = selectedBackups.size() + selectedDiscards.size() * 2;
			int paid     = maxCp == Integer.MAX_VALUE ? produced : Math.min(produced, maxCp);
			mw.logEntry("[AutoAbility] " + cardName + " — paid " + paid + " CP"
					+ (produced > paid ? " (" + (produced - paid) + " excess CP wasted)" : ""));
			mw.refreshP1HandLabel();
			mw.refreshP1BreakLabel();
			onConfirm.accept(paid);
		});

		JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 6));
		buttonPanel.add(confirmBtn);
		if (crystalAltCost > 0) {
			JButton crystalBtn = new JButton("Pay " + crystalAltCost + " Crystal" + (crystalAltCost == 1 ? "" : "s"));
			crystalBtn.setFont(FontLoader.loadPixelFont(11));
			crystalBtn.setEnabled(mw.playerCrystals(isP1) >= crystalAltCost);
			crystalBtn.addActionListener(ev -> {
				dlg.dispose();
				mw.playerSpendCrystals(isP1, crystalAltCost);
				mw.refreshCrystalDisplays();
				mw.logEntry("[AutoAbility] " + cardName + " — paid " + crystalAltCost + " Crystal" + (crystalAltCost == 1 ? "" : "s"));
				if (onCrystalPaid != null) onCrystalPaid.run();
			});
			buttonPanel.add(crystalBtn);
		}
		buttonPanel.add(cancelBtn);

		JPanel topPanel = new JPanel(new BorderLayout(0, 4));
		topPanel.setBorder(BorderFactory.createEmptyBorder(8, 8, 4, 8));
		topPanel.add(cpLabel, BorderLayout.CENTER);

		JPanel mainPanel = new JPanel(new BorderLayout(0, 4));
		mainPanel.setBorder(BorderFactory.createEmptyBorder(0, 8, 8, 8));
		mainPanel.add(new JScrollPane(center), BorderLayout.CENTER);
		mainPanel.add(buttonPanel,             BorderLayout.SOUTH);

		dlg.getContentPane().setLayout(new BorderLayout());
		dlg.getContentPane().add(topPanel,  BorderLayout.NORTH);
		dlg.getContentPane().add(mainPanel, BorderLayout.CENTER);
		dlg.pack(); dlg.setLocationRelativeTo(mw.frame); dlg.setVisible(true);
	}

	boolean canActivateHandAbility(ActionAbility ability, CardData source, boolean isP1) {
		if (ability.yourTurnOnly()) {
			GameState.Player activePlayer = isP1 ? GameState.Player.P1 : GameState.Player.P2;
			if (mw.gameState.getCurrentPlayer() != activePlayer) return false;
		}
		if (ability.oncePerTurn()
				&& mw.usedOncePerTurnAbilities.getOrDefault(source, Set.of()).contains(ability.effectText()))
			return false;
		GameState.GamePhase p = mw.gameState.getCurrentPhase();
		if (p != GameState.GamePhase.MAIN_1 && p != GameState.GamePhase.MAIN_2
				&& !(p == GameState.GamePhase.ATTACK && mw.attackSubStep == 0)) return false;
		if (ability.crystalCost() > 0 && mw.playerCrystals(isP1) < ability.crystalCost()) return false;
		for (BreakZoneCost bz : ability.breakZoneCosts())
			if (!bzCostSatisfied(bz, isP1)) return false;
		for (RemoveFromGameCost rfg : ability.removeFromGameCosts())
			if (!rfgCostSatisfied(rfg, isP1)) return false;
		for (ReturnToHandCost rth : ability.returnToHandCosts())
			if (!rfthCostSatisfied(rth, isP1)) return false;
		for (CounterCost cc : ability.counterCosts())
			if (!counterCostSatisfied(cc, source)) return false;
		return mw.canAffordAbilityCost(ability, isP1);
	}

	/**
	 * Returns {@code true} if an action ability whose source is in the Break Zone
	 * can currently be activated.
	 */
	boolean canActivateBzAbility(ActionAbility ability, CardData source, boolean isP1) {
		GameState.GamePhase phase = mw.gameState.getCurrentPhase();
		if (phase != GameState.GamePhase.MAIN_1 && phase != GameState.GamePhase.MAIN_2
				&& !(phase == GameState.GamePhase.ATTACK && mw.attackSubStep == 0)) return false;
		if (ability.yourTurnOnly() || ability.mainPhaseOnly()) {
			GameState.Player activePlayer = isP1 ? GameState.Player.P1 : GameState.Player.P2;
			if (mw.gameState.getCurrentPlayer() != activePlayer) return false;
		}
		if (ability.oncePerTurn()
				&& mw.usedOncePerTurnAbilities.getOrDefault(source, Set.of()).contains(ability.effectText()))
			return false;
		// "Damage N --" gates a Break-Zone ability exactly as it gates a field one (Ardyn 26-122H,
		// the only printing that pairs the two), so this mirrors canActivateAbility's check.
		if (ability.damageThreshold() > 0) {
			int dmg = isP1 ? mw.gameState.getP1DamageZone().size() : mw.gameState.getP2DamageZone().size();
			if (dmg < ability.damageThreshold()) return false;
		}
		if (ability.crystalCost() > 0 && mw.playerCrystals(isP1) < ability.crystalCost()) return false;
		for (BreakZoneCost bz : ability.breakZoneCosts())
			if (!bzCostSatisfied(bz, isP1)) return false;
		for (RemoveFromGameCost rfg : ability.removeFromGameCosts())
			if (!rfgCostSatisfied(rfg, isP1)) return false;
		for (ReturnToHandCost rth : ability.returnToHandCosts())
			if (!rfthCostSatisfied(rth, isP1)) return false;
		for (CounterCost cc : ability.counterCosts())
			if (!counterCostSatisfied(cc, source)) return false;
		for (DullForwardCost dfc : ability.dullForwardCosts())
			if (!dullForwardCostSatisfied(dfc, isP1)) return false;
		return mw.canAffordAbilityCost(ability, isP1);
	}

	/**
	 * Resolves "put N [type] into the Break Zone" costs for a break-zone-origin ability
	 * by selecting the appropriate field cards. Named-card costs are auto-selected; type-
	 * based costs prompt the player to choose. Returns {@code null} if cancelled or unpayable.
	 */
	private List<ForwardTarget> resolveBzCostTargetsForBzAbility(List<BreakZoneCost> bzCosts, boolean isP1) {
		List<ForwardTarget> all = new ArrayList<>();
		for (BreakZoneCost bz : bzCosts) {
			List<ForwardTarget> eligible = eligibleBzFieldCards(bz, isP1);
			if (eligible.size() < bz.count()) {
				mw.logEntry("Not enough eligible field cards for Break Zone cost.");
				return null;
			}
			if (!bz.name().isEmpty()) {
				all.add(eligible.get(0)); // named card: auto-select first match
			} else if (eligible.size() == bz.count()) {
				all.addAll(eligible); // only one possible selection
			} else {
				String typeLabel = bz.cardType().isEmpty() ? "card" : bz.cardType();
				List<ForwardTarget> picks = mw.showForwardSelectDialog(eligible, bz.count(), false,
						"Break Zone Cost: Break " + bz.count() + " " + typeLabel + "(s)");
				if (picks == null || picks.size() < bz.count()) return null;
				all.addAll(picks);
			}
		}
		return all;
	}

	/** Payment dialog for an action ability activated from the Break Zone. */
	void showBzAbilityPaymentDialog(ActionAbility ability, CardData source, boolean isP1) {
		// Own discount then the opposing field's tax (The Emperor 20-092R) — see effectiveAbilityCost.
		final ActionAbility eff = mw.effectiveAbilityCost(ability, isP1);
		List<String> rawCost = eff.cpCost();
		List<BreakZoneCost> bzCosts = eff.breakZoneCosts();

		if (rawCost.isEmpty() && !eff.hasXCost()) {
			List<ForwardTarget> bzTargets = resolveBzCostTargetsForBzAbility(bzCosts, isP1);
			if (bzTargets == null) return;
			executeAbilityPayment(eff, source, () -> {}, new ArrayList<>(), new ArrayList<>(), bzTargets, isP1, 0, -1);
			return;
		}

		new AbilityPaymentDialog(mw.frame, eff, source,
				mw.playerHand(isP1), mw.cpPayableBackupCards(isP1), mw.playerBackupStates(isP1), mw.playerBackupUrls(isP1),
				mw::showZoomAt, mw::hideZoom, null, null, mw.lightDarkDiscardGrants(isP1),
				eff.isSpecial() && mw.canPaySpecialCostWithCrystal(source, isP1),
				(discards, backups, xValue, sCostIdx, breaks) -> {
					List<ForwardTarget> bzTargets = resolveBzCostTargetsForBzAbility(bzCosts, isP1);
					if (bzTargets == null) return;
					executeAbilityPayment(eff, source, () -> {}, discards, backups, bzTargets, isP1,
							xValue, sCostIdx, breaks);
				}, mw.breakForCpBackupSlots(isP1))
			.show();
	}

	/**
	 * Builds the BZ-target list for an action ability's "put ... into the Break Zone" cost.
	 * A cost that names the source's own card ("Put [self] into the Break Zone") is a
	 * self-reference to THIS instance, so it breaks the source directly with no player choice —
	 * even when other copies of the same name are on the field.  Other costs select among the
	 * eligible field cards, prompting the player when more than {@code count} qualify.
	 */
	private List<ForwardTarget> autoResolveBzTargets(CardData source, List<BreakZoneCost> bzCosts, boolean isP1) {
		if (bzCosts.isEmpty()) return List.of();
		List<ForwardTarget> result = new ArrayList<>();

		for (BreakZoneCost bz : bzCosts) {
			// "Put [self] into the Break Zone" — the card naming itself means this specific instance.
			if (!bz.name().isEmpty() && meetsCardNameFilter(source, bz.name())) {
				ForwardTarget self = findSourceOnField(source, isP1);
				if (self != null) { result.add(self); continue; }
			}
			List<ForwardTarget> eligible = eligibleBzFieldCards(bz, isP1);
			if (eligible.size() <= bz.count()) result.addAll(eligible);
			else {
				String strAmt = bz.count() > 1 ? " cards" : " card";
				String text = "Select " + bz.count() + strAmt + " to put into the Break Zone.";
				result.addAll(mw.selectFieldTargetsInPlace(eligible, bz.count(), false, text));
			}
		}
		return result;
	}

	/** Finds the field position of {@code source} by object identity, or {@code null} if not found. */
	private ForwardTarget findSourceOnField(CardData source, boolean isP1) {
		if (isP1) {
			for (int i = 0; i < mw.p1ForwardCards.size(); i++) {
				CardData top = mw.p1ForwardPrimedTop.get(i);
				if (top == source || mw.p1ForwardCards.get(i) == source)
					return new ForwardTarget(true, i, ForwardTarget.CardZone.FORWARD);
			}
			for (int i = 0; i < mw.p1BackupCards.length; i++) {
				if (mw.p1BackupCards[i] == source)
					return new ForwardTarget(true, i, ForwardTarget.CardZone.BACKUP);
			}
			for (int i = 0; i < mw.p1MonsterCards.size(); i++) {
				if (mw.p1MonsterCards.get(i) == source)
					return new ForwardTarget(true, i, ForwardTarget.CardZone.MONSTER);
			}
		} else {
			for (int i = 0; i < mw.p2ForwardCards.size(); i++) {
				if (mw.p2ForwardCards.get(i) == source)
					return new ForwardTarget(false, i, ForwardTarget.CardZone.FORWARD);
			}
			for (int i = 0; i < mw.p2BackupCards.length; i++) {
				if (mw.p2BackupCards[i] == source)
					return new ForwardTarget(false, i, ForwardTarget.CardZone.BACKUP);
			}
			for (int i = 0; i < mw.p2MonsterCards.size(); i++) {
				if (mw.p2MonsterCards.get(i) == source)
					return new ForwardTarget(false, i, ForwardTarget.CardZone.MONSTER);
			}
		}
		return null;
	}

	boolean bzCostSatisfied(BreakZoneCost bz, boolean isP1) {
		return eligibleBzFieldCards(bz, isP1).size() >= bz.count();
	}

	/**
	 * How many counters a variable ("remove X …") cost spends this activation, between 1 and the
	 * number currently on {@code source}.
	 *
	 * <p>The effect these costs pay for reads X as an exact cost to match in the Break Zone
	 * (Lenna 12-109L, Leo 13-067L: "If its cost is X, play it onto the field."), so the amounts
	 * worth choosing are the costs actually sitting there. The human is offered those, and the AI
	 * takes the most expensive one it can reach — spending more counters than any Break Zone card
	 * costs would buy nothing.
	 */
	private int chooseVariableCounterAmount(CounterCost cc, CardData source, boolean isP1) {
		int available = mw.gameState.getCounters(source, cc.counterName());
		if (available <= 1) return available;

		// The distinct Break Zone Forward costs within reach, ascending — the only X values that
		// can pay off. Falls back to the full range when the zone offers nothing, so the player is
		// never blocked from spending by a heuristic.
		List<CardData> bz = isP1 ? mw.gameState.getP1BreakZone() : mw.gameState.getP2BreakZone();
		List<Integer> useful = bz.stream()
				.filter(CardData::isForward)
				.map(CardData::cost)
				.filter(c -> c >= 1 && c <= available)
				.distinct().sorted().toList();
		if (useful.isEmpty()) {
			List<Integer> all = new ArrayList<>();
			for (int i = 1; i <= available; i++) all.add(i);
			useful = all;
		}

		if (!isP1) return useful.get(useful.size() - 1);   // AI: the biggest it can actually use

		Object[] options = useful.stream().map(n -> "X = " + n).toArray();
		int choice = mw.showEffectOptionDialog(
				"<html><body style='width:320px'>" + source.name() + " has <b>" + available + "</b> "
				+ cc.counterName() + " Counter(s).<br><br>Remove how many? The number removed is the "
				+ "cost this ability can play back from your Break Zone.</body></html>",
				source.name() + " — remove " + cc.counterName() + " Counters", options);
		return choice >= 0 && choice < useful.size() ? useful.get(choice) : useful.get(0);
	}

	/** True when {@code source} (the activating card) has enough counters to pay {@code cc}. */
	boolean counterCostSatisfied(CounterCost cc, CardData source) {
		if (!source.name().equalsIgnoreCase(cc.cardName())) return false;
		// A variable cost names no amount, so what it needs is something to spend: X = 0 buys
		// nothing, since no Forward in the corpus costs 0.
		int required = cc.variable() ? 1 : cc.count();
		return mw.gameState.getCounters(source, cc.counterName()) >= required;
	}

	boolean dullForwardCostSatisfied(DullForwardCost dfc, boolean isP1) {
		boolean anyChar = "Character".equalsIgnoreCase(dfc.cardType());
		List<CardData>  fwds    = isP1 ? mw.p1ForwardCards  : mw.p2ForwardCards;
		List<CardState> fwdSt   = isP1 ? mw.p1ForwardStates : mw.p2ForwardStates;
		List<CardData>  mons    = isP1 ? mw.p1MonsterCards  : mw.p2MonsterCards;
		CardData[]      bkps    = isP1 ? mw.p1BackupCards   : mw.p2BackupCards;
		CardState[]     bkpSt   = isP1 ? mw.p1BackupStates  : mw.p2BackupStates;
		int eligible = 0;
		for (int i = 0; i < fwds.size(); i++) {
			if (fwdSt.get(i) != CardState.ACTIVE) continue;
			if (!dullForwardCostMatches(dfc, fwds.get(i))) continue;
			eligible++;
		}
		if (anyChar) {
			for (int i = 0; i < bkps.length; i++)
				if (bkps[i] != null && bkpSt[i] == CardState.ACTIVE && dullForwardCostMatches(dfc, bkps[i])) eligible++;
			for (CardData mon : mons)
				if (dullForwardCostMatches(dfc, mon)) eligible++;
		}
		return eligible >= dfc.count();
	}

	boolean discardCostSatisfied(DiscardCost dc, boolean isP1) {
		// Payers, not candidates: an "each of a different card type" cost is not satisfied by a hand
		// of three Forwards, and offering the ability on that hand only leads P1 to a picker that
		// will not let them complete the selection.
		return discardCostPayerIdxs(dc, mw.playerHand(isP1), Set.of()).size() >= dc.count();
	}

	/**
	 * Hand slots that can pay {@code dc}, skipping {@code excludedIdxs} — slots already spoken for
	 * by another cost on the same activation.  The shape
	 * {@code ComputerPlayer.p2PlanAbilityPayment} needs to reserve payers before its CP planner
	 * spends the hand, and the same order the P2 payment below selects in, so the slots the planner
	 * sets aside are slots the payment will accept.
	 */
	List<Integer> discardCostCandidateIdxs(DiscardCost dc, List<CardData> hand,
			Collection<Integer> excludedIdxs) {
		List<Integer> eligible = new ArrayList<>();
		for (int i = 0; i < hand.size(); i++) {
			if (excludedIdxs.contains(i)) continue;
			if (meetsDiscardCost(hand.get(i), dc)) eligible.add(i);
		}
		return eligible;
	}

	/**
	 * {@link #discardCostCandidateIdxs} narrowed to slots P2 may spend <em>together</em>: the same
	 * list when {@code dc} constrains nothing but the individual card, and one slot per card type
	 * when it reads "each of a different card type" (Ashe 5-114L). A caller pays {@code dc} when the
	 * result holds at least {@code dc.count()} slots, and spends its first {@code dc.count()}.
	 *
	 * <p>Taking the first card of each type is what makes that test correct rather than merely
	 * convenient: it yields as many distinct types as the hand can offer, so a shortfall here means
	 * no selection of any kind could have paid the cost.
	 *
	 * <p>Read by both P2's payment and {@code ComputerPlayer.p2PlanAbilityPayment}. The set rule has
	 * to be shared exactly as the per-card rule is — a planner that reserves three Forwards for a
	 * cost the payment then refuses to pay with them has planned for a cost it cannot pay.
	 * {@link CardFilters#discardTypeKey} is the same reading of "card type" that P1's picker
	 * enforces by hand, so the two players are held to one rule.
	 */
	List<Integer> discardCostPayerIdxs(DiscardCost dc, List<CardData> hand,
			Collection<Integer> excludedIdxs) {
		List<Integer> eligible = discardCostCandidateIdxs(dc, hand, excludedIdxs);
		if (!dc.eachDifferentType()) return eligible;
		List<Integer> distinct = new ArrayList<>();
		Set<String> typesTaken = new HashSet<>();
		for (int i : eligible)
			if (typesTaken.add(discardTypeKey(hand.get(i)))) distinct.add(i);
		return distinct;
	}

	/**
	 * Whether {@code card} satisfies the filters on {@code dfc}. Shared with the dull-based
	 * alternate cast cost (Nine 13-123L), which pays with the same kind of requirement.
	 */
	boolean dullForwardCostMatches(DullForwardCost dfc, CardData card) {
		// "other than [Name]" — Steiner 4-129L cannot pay his own cost with himself, and the bar is
		// by name, so a second copy of him cannot pay it either.
		if (dfc.exceptCardName() != null
				&& CardFilters.meetsCardNameFilter(card, dfc.exceptCardName())) return false;
		if (dfc.cardName() != null) {
			// Cloud 29-005L pays with either of two named Forwards ("dull 1 active Card Name Tifa
			// or Card Name Aerith"), the same alternative the Job branch below reads off the same
			// field. Without it his special ability could never be paid for with Aerith.
			boolean nameMatch   = card.name().equalsIgnoreCase(dfc.cardName());
			boolean orNameMatch = dfc.orCardName() != null
					&& card.name().equalsIgnoreCase(dfc.orCardName());
			if (!nameMatch && !orNameMatch) return false;
		}
		if (dfc.element()  != null && !dfc.element().isEmpty() && !mw.effectiveContainsElement(card, dfc.element())) return false;
		if (dfc.job() != null) {
			boolean jobMatch    = card.hasJob(dfc.job());
			boolean orNameMatch = dfc.orCardName() != null && card.name().equalsIgnoreCase(dfc.orCardName());
			if (!jobMatch && !orNameMatch) return false;
		}
		if (dfc.category() != null) {
			String cat = dfc.category();
			if (!cat.equalsIgnoreCase(card.category1()) && !cat.equalsIgnoreCase(card.category2())) return false;
		}
		return true;
	}

	private List<ForwardTarget> eligibleBzFieldCards(BreakZoneCost bz, boolean isP1) {
		List<ForwardTarget> result = new ArrayList<>();
		List<CardData> fwds = mw.playerForwardCards(isP1);
		List<CardData> mons = mw.playerMonsterCards(isP1);
		CardData[]     bkps = mw.playerBackupCards(isP1);
		if (!bz.name().isEmpty()) {
			for (int i = 0; i < fwds.size(); i++)
				if (meetsCardNameFilter(fwds.get(i), bz.name()))
					result.add(new ForwardTarget(isP1, i, ForwardTarget.CardZone.FORWARD));
			for (int i = 0; i < mons.size(); i++)
				if (meetsCardNameFilter(mons.get(i), bz.name()))
					result.add(new ForwardTarget(isP1, i, ForwardTarget.CardZone.MONSTER));
			for (int i = 0; i < bkps.length; i++)
				if (bkps[i] != null && meetsCardNameFilter(bkps[i], bz.name()))
					result.add(new ForwardTarget(isP1, i, ForwardTarget.CardZone.BACKUP));
			return result;
		}
		String typeDesc = bz.cardType();
		String last     = typeDesc.isEmpty() ? "" : typeDesc.substring(typeDesc.lastIndexOf(' ') + 1);
		String elemFilt = typeDesc.contains(" ") ? typeDesc.substring(0, typeDesc.lastIndexOf(' ')).trim() : null;
		if (last.equalsIgnoreCase("Forward")) {
			for (int i = 0; i < fwds.size(); i++) {
				if (elemFilt != null && !mw.effectiveContainsElement(fwds.get(i), elemFilt)) continue;
				result.add(new ForwardTarget(isP1, i, ForwardTarget.CardZone.FORWARD));
			}
		} else if (last.equalsIgnoreCase("Backup")) {
			for (int i = 0; i < bkps.length; i++) {
				if (bkps[i] == null) continue;
				if (elemFilt != null && !mw.effectiveContainsElement(bkps[i], elemFilt)) continue;
				result.add(new ForwardTarget(isP1, i, ForwardTarget.CardZone.BACKUP));
			}
		} else if (last.equalsIgnoreCase("Monster")) {
			for (int i = 0; i < mons.size(); i++) {
				if (elemFilt != null && !mw.effectiveContainsElement(mons.get(i), elemFilt)) continue;
				result.add(new ForwardTarget(isP1, i, ForwardTarget.CardZone.MONSTER));
			}
			for (int i = 0; i < fwds.size(); i++) {
				if (!fwds.get(i).alsoCountsAsMonster()) continue;
				if (elemFilt != null && !mw.effectiveContainsElement(fwds.get(i), elemFilt)) continue;
				result.add(new ForwardTarget(isP1, i, ForwardTarget.CardZone.FORWARD));
			}
		}
		return result;
	}

	boolean rfgCostSatisfied(RemoveFromGameCost rfg, boolean isP1) {
		if (rfg.count() == -1) return true; // "all" — always payable
		return switch (rfg.zone()) {
			case "DECK"       -> (isP1 ? mw.gameState.getP1MainDeck() : mw.gameState.getP2MainDeck()).size() >= rfg.count();
			case "HAND"       -> eligibleRfgHandIndices(rfg, isP1).size() >= rfg.count();
			case "BREAK_ZONE" -> eligibleRfgBzIndices(rfg, isP1).size() >= rfg.count();
			default           -> eligibleRfgFieldTargets(rfg, isP1).size() >= rfg.count();
		};
	}

	private List<Integer> eligibleRfgHandIndices(RemoveFromGameCost rfg, boolean isP1) {
		List<CardData> hand = mw.playerHand(isP1);
		List<Integer> result = new ArrayList<>();
		for (int i = 0; i < hand.size(); i++) {
			CardData c = hand.get(i);
			if (rfg.cardName() != null && !meetsCardNameFilter(c, rfg.cardName())) continue;
			if (rfg.element()  != null && !c.containsElement(rfg.element()))       continue;
			if (rfg.cardType() != null && !matchesDiscardType(c, rfg.cardType()))  continue;
			result.add(i);
		}
		return result;
	}

	private List<Integer> eligibleRfgBzIndices(RemoveFromGameCost rfg, boolean isP1) {
		List<CardData> bz = isP1 ? mw.gameState.getP1BreakZone() : mw.gameState.getP2BreakZone();
		List<Integer> result = new ArrayList<>();
		for (int i = 0; i < bz.size(); i++) {
			CardData c = bz.get(i);
			if (rfg.cardName() != null && !meetsCardNameFilter(c, rfg.cardName())) continue;
			if (rfg.element()  != null && !c.containsElement(rfg.element()))          continue;
			if (rfg.cardType() != null && !matchesDiscardType(c, rfg.cardType()))     continue;
			result.add(i);
		}
		return result;
	}

	private List<ForwardTarget> eligibleRfgFieldTargets(RemoveFromGameCost rfg, boolean isP1) {
		List<ForwardTarget> result = new ArrayList<>();
		List<CardData> fwds = mw.playerForwardCards(isP1);
		List<CardData> mons = mw.playerMonsterCards(isP1);
		CardData[]     bkps = mw.playerBackupCards(isP1);
		for (int i = 0; i < fwds.size(); i++) {
			CardData c = fwds.get(i);
			if (!matchesRfgFieldFilter(c, rfg)) continue;
			result.add(new ForwardTarget(isP1, i, ForwardTarget.CardZone.FORWARD));
		}
		for (int i = 0; i < bkps.length; i++) {
			if (bkps[i] == null) continue;
			if (!matchesRfgFieldFilter(bkps[i], rfg)) continue;
			result.add(new ForwardTarget(isP1, i, ForwardTarget.CardZone.BACKUP));
		}
		for (int i = 0; i < mons.size(); i++) {
			if (!matchesRfgFieldFilter(mons.get(i), rfg)) continue;
			result.add(new ForwardTarget(isP1, i, ForwardTarget.CardZone.MONSTER));
		}
		return result;
	}

	private boolean matchesRfgFieldFilter(CardData c, RemoveFromGameCost rfg) {
		if (rfg.cardName()    != null && !meetsCardNameFilter(c, rfg.cardName()))     return false;
		if (rfg.element()     != null && !mw.effectiveContainsElement(c, rfg.element()))           return false;
		if (rfg.cardType()    != null && !matchesDiscardType(c, rfg.cardType()))      return false;
		if (rfg.excludeName() != null &&  c.name().equalsIgnoreCase(rfg.excludeName())) return false;
		return true;
	}

	boolean rfthCostSatisfied(ReturnToHandCost rth, boolean isP1) {
		return eligibleRfthFieldTargets(rth, isP1).size() >= rth.count();
	}

	private List<ForwardTarget> eligibleRfthFieldTargets(ReturnToHandCost rth, boolean isP1) {
		List<ForwardTarget> result = new ArrayList<>();
		List<CardData> fwds = mw.playerForwardCards(isP1);
		List<CardData> mons = mw.playerMonsterCards(isP1);
		CardData[]     bkps = mw.playerBackupCards(isP1);
		for (int i = 0; i < fwds.size(); i++)
			if (matchesRfthFilter(fwds.get(i), rth)) result.add(new ForwardTarget(isP1, i, ForwardTarget.CardZone.FORWARD));
		for (int i = 0; i < bkps.length; i++)
			if (bkps[i] != null && matchesRfthFilter(bkps[i], rth)) result.add(new ForwardTarget(isP1, i, ForwardTarget.CardZone.BACKUP));
		for (int i = 0; i < mons.size(); i++)
			if (matchesRfthFilter(mons.get(i), rth)) result.add(new ForwardTarget(isP1, i, ForwardTarget.CardZone.MONSTER));
		return result;
	}

	private boolean matchesRfthFilter(CardData c, ReturnToHandCost rth) {
		if (rth.cardName()    != null && !meetsCardNameFilter(c, rth.cardName()))       return false;
		if (rth.cardType()    != null && !matchesDiscardType(c, rth.cardType()))        return false;
		if (rth.category()    != null && !meetsCategoryFilter(c, rth.category()))       return false;
		if (rth.excludeName() != null &&  c.name().equalsIgnoreCase(rth.excludeName())) return false;
		return true;
	}

	private void executeReturnToHandCost(ReturnToHandCost rth, boolean isP1) {
		GameContext ctx = mw.buildGameContext(isP1);
		if (rth.cardName() != null) {
			// Auto-find named card and return it
			List<ForwardTarget> eligible = eligibleRfthFieldTargets(rth, isP1);
			for (int i = 0; i < rth.count() && i < eligible.size(); i++)
				returnTargetToHand(ctx, eligible.get(i));
		} else {
			List<ForwardTarget> eligible = eligibleRfthFieldTargets(rth, isP1);
			if (eligible.isEmpty()) { mw.logEntry("No eligible field card for return-to-hand cost."); return; }
			List<ForwardTarget> picks = mw.showForwardSelectDialog(eligible, rth.count(), false, "Return to Hand (cost)");
			mw.applyTargetsHighestIndexFirst(picks, t -> returnTargetToHand(ctx, t));
		}
	}

	private void returnTargetToHand(GameContext ctx, ForwardTarget t) {
		switch (t.zone()) {
			case FORWARD -> { if (t.isP1()) ctx.returnP1ForwardToHand(t.idx()); else ctx.returnP2ForwardToHand(t.idx()); }
			case BACKUP  -> { if (t.isP1()) ctx.returnP1BackupToHand(t.idx());  else ctx.returnP2BackupToHand(t.idx()); }
			case MONSTER -> { if (t.isP1()) ctx.returnP1MonsterToHand(t.idx()); else ctx.returnP2MonsterToHand(t.idx()); }
		}
	}

	CardData fieldCardData(ForwardTarget t) {
		if (t.isP1()) return switch (t.zone()) {
			case FORWARD -> mw.p1ForwardCards.get(t.idx());
			case BACKUP  -> mw.p1BackupCards[t.idx()];
			case MONSTER -> mw.p1MonsterCards.get(t.idx());
			default      -> null;
		};
		return switch (t.zone()) {
			case FORWARD -> mw.p2ForwardCards.get(t.idx());
			case BACKUP  -> mw.p2BackupCards[t.idx()];
			case MONSTER -> mw.p2MonsterCards.get(t.idx());
			default      -> null;
		};
	}

	void breakP1BackupSlot(int idx) {
		CardData c = mw.p1BackupCards[idx];
		if (c == null) return;
		mw.startBreakAnim(mw.p1BackupLabels[idx]);
		mw.logEntry(c.name() + " → Break Zone");
		mw.addToBreakZone(c, true);
		mw.p1BackupTempForwardPower.remove(c); mw.p1BackupForwardBoost.remove(c);
		mw.p1BackupTempTraits.remove(c);       mw.p1BackupForwardDamage.remove(c);
		if (mw.p1BackupAttackIdx == idx) mw.p1BackupAttackIdx = -1;
		mw.p1BackupCards[idx]   = null;
		mw.p1BackupUrls[idx]    = null;
		mw.p1BackupStates[idx]  = CardState.ACTIVE;
		mw.p1BackupFrozen[idx]  = false;
		if (mw.p1BackupLabels[idx] != null) {
			mw.p1BackupLabels[idx].setIcon(null);
			mw.p1BackupLabels[idx].setText(null);
		}
		mw.syncBzForwardPlayables(true);
		mw.refreshP1BreakLabel();
		triggerAutoAbilitiesForLeavesField(c, true);
		triggerAutoAbilitiesForBreakZone(c, true, Collections.emptySet());
	}

	void breakP1MonsterSlot(int idx) {
		if (idx >= mw.p1MonsterCards.size()) return;
		mw.startBreakAnim(mw.p1MonsterLabels.get(idx));
		CardData c = mw.p1MonsterCards.get(idx);
		mw.logEntry(c.name() + " → Break Zone");
		mw.addToBreakZone(c, true);
		mw.p1MonsterTempForwardPower.remove(c);
		mw.p1MonsterPowerBoost.remove(c);
		mw.p1MonsterTempTraits.remove(c);
		mw.p1MonsterCards.remove(idx);
		mw.p1MonsterStates.remove(idx);
		mw.p1MonsterFrozen.remove(idx);
		mw.p1MonsterPlayedOnTurn.remove(idx);
		mw.p1MonsterDamage.remove(idx);
		mw.p1MonsterUrls.remove(idx);
		JLabel lbl = mw.p1MonsterLabels.remove(idx);
		if (mw.p1MonsterPanel != null) {
			mw.p1MonsterPanel.remove(lbl);
			mw.p1MonsterPanel.revalidate();
			mw.p1MonsterPanel.repaint();
		}
		mw.syncBzForwardPlayables(true);
		mw.refreshP1BreakLabel();
		triggerAutoAbilitiesForLeavesField(c, true);
		triggerAutoAbilitiesForBreakZone(c, true, Collections.emptySet());
	}

	/**
	 * Adds an action-ability section to {@code menu} for all abilities on {@code card}.
	 * Each item is enabled only when the ability is currently activatable.
	 *
	 * @param card        the card whose abilities to list
	 * @param state       current field state of the card
	 * @param playedTurn  turn the card entered the field
	 * @param applyDull   called on confirm if the ability has a Dull cost (dulls the card)
	 */
	void addAbilityMenuItems(JPopupMenu menu, CardData card, boolean isFrozen,
			CardState state, int playedTurn, Runnable applyDull, boolean isP1) {
		if (mw.lostAbilitiesCards.contains(card)) return;
		// Printed abilities first, then the ones borrowed from the removed-from-game zone (Clive
		// 26-005H). Borrowed specials join this list rather than the temp-granted one below so they
		// go through the same phase and 《S》-cost checks a printed special does; the temp list is
		// for cost-free once-per-turn copies, which these are not.
		List<ActionAbility> abilities = new ArrayList<>(card.actionAbilities());
		abilities.addAll(mw.rfgJobSpecialAbilities(card, isP1));
		List<ActionAbility> tempAbilities = (isP1 ? mw.p1TempGrantedAbilities : mw.p2TempGrantedAbilities)
				.getOrDefault(card, List.of());
		// Medusa grants a petrified Forward "《5》: Remove all Petrification Counters from this Forward."
		// It's driven off the counter's presence rather than a stored grant (which wouldn't survive the
		// turn), so synthesize the menu item whenever the card carries a Petrification Counter.
		boolean petrified = mw.gameState.getCounters(card, "Petrification") > 0;
		if (abilities.isEmpty() && tempAbilities.isEmpty() && !petrified) return;

		GameState.GamePhase phase = mw.gameState.getCurrentPhase();
		boolean isMainPhase  = phase == GameState.GamePhase.MAIN_1 || phase == GameState.GamePhase.MAIN_2;
		boolean isAttackPhase = phase == GameState.GamePhase.ATTACK;

		for (ActionAbility ability : abilities) {
			if (ability.whileCardInHand()) continue; // only usable from hand, not from the field
			if (ability.breakZoneOnly() != null) continue; // only usable from Break Zone
			boolean hasAttackRestriction = ability.whileCardAttacking() != null
					|| ability.whileCardBlocking() != null || ability.whilePartyAttacking()
					|| ability.hasBlockingTargetEffect() || ability.blockerForAttacker() != null;
			boolean phaseOk = hasAttackRestriction ? isAttackPhase : (isMainPhase || mw.p1MayActInAttackPhase());

			// "Each player can use this ability." — P1 (the human) is always the one driving this
			// menu, so when the card belongs to P2 (the CPU), let P1 activate it too, paying costs
			// from P1's own resources instead of P2's (P2's hand/backups aren't human-interactive).
			boolean activatorIsP1 = (!isP1 && ability.usableByEitherPlayer()) ? true : isP1;

			boolean abilityEnabled = phaseOk && mw.canActivateAbility(ability, isFrozen, state, playedTurn, card, activatorIsP1);
			String label = abilityEnabled ? mw.buildAbilityMenuLabelHtml(ability) : mw.buildAbilityMenuLabel(ability);
			if (activatorIsP1 != isP1) {
				String suffix = " (pay your own cost)";
				label = label.startsWith("<html>") ? label.replace("</html>", suffix + "</html>") : label + suffix;
			}
			JMenuItem item = new JMenuItem(label);
			item.setEnabled(abilityEnabled);
			item.addActionListener(ae ->
					showActionAbilityPaymentDialog(ability, card, applyDull, activatorIsP1));
			menu.add(item);
		}

		for (ActionAbility ability : tempAbilities) {
			boolean abilityEnabled = isMainPhase && mw.canActivateAbility(ability, isFrozen, state, playedTurn, card, isP1);
			JMenuItem item = new JMenuItem(abilityEnabled ? mw.buildAbilityMenuLabelHtml(ability) : mw.buildAbilityMenuLabel(ability));
			item.setEnabled(abilityEnabled);
			// Reuse the caller's dull runnable so a granted "《Dull》: …" ability (e.g. Machinist's) dulls
			// its grantee Forward when activated.
			item.addActionListener(ae ->
					showActionAbilityPaymentDialog(ability, card, applyDull, isP1));
			menu.add(item);
		}

		ActionAbility petrifyRemoval = petrificationRemovalAbility();
		if (petrified && petrifyRemoval != null) {
			boolean abilityEnabled = isMainPhase && mw.canActivateAbility(petrifyRemoval, isFrozen, state, playedTurn, card, isP1);
			JMenuItem item = new JMenuItem(abilityEnabled ? mw.buildAbilityMenuLabelHtml(petrifyRemoval) : mw.buildAbilityMenuLabel(petrifyRemoval));
			item.setEnabled(abilityEnabled);
			item.addActionListener(ae ->
					showActionAbilityPaymentDialog(petrifyRemoval, card, applyDull, isP1));
			menu.add(item);
		}
	}

	/** Lazily-parsed "《5》: Remove all Petrification Counters from this Forward." — Medusa's granted ability. */
	private static ActionAbility petrificationRemovalAbility;
	private static ActionAbility petrificationRemovalAbility() {
		if (petrificationRemovalAbility == null) {
			List<ActionAbility> parsed = CardData.parseActionAbilities(
					"《5》: Remove all Petrification Counters from this Forward.");
			if (!parsed.isEmpty()) petrificationRemovalAbility = parsed.get(0);
		}
		return petrificationRemovalAbility;
	}

	/**
	 * Payment dialog for an action ability.  Mirrors the Priming payment dialog
	 * but also handles Dull cost (dulls the source card) and Special cost (discards
	 * a same-name card from hand).  On successful payment calls
	 * {@link ActionResolver#resolve}.
	 */
	void showActionAbilityPaymentDialog(ActionAbility ability, CardData source,
			Runnable applyDull, boolean isP1) {
		// Own discount then the opposing field's tax (The Emperor 20-092R) — see effectiveAbilityCost.
		final ActionAbility eff = mw.effectiveAbilityCost(ability, isP1);
		List<String> rawCost = eff.cpCost();
		List<BreakZoneCost> bzCosts = eff.breakZoneCosts();

		// Wakka 16-138S: Reel Counters buy the whole cost. Offered ahead of the payment dialog
		// because what it waives is the dialog's entire subject — the CP, the 《S》 discard and the
		// 《Dull》 alike. Declining falls through to the ordinary payment, which may still be the
		// better line when the counters are wanted for something else.
		CardData.SpecialCostCounterWaiver waiver = eff.isSpecial()
				? mw.specialCostCounterWaiver(source, isP1) : null;
		if (waiver != null && isP1) {
			String label = "Remove " + waiver.count() + " " + waiver.counterName() + " Counter"
					+ (waiver.count() == 1 ? "" : "s");
			Object[] options = { label, "Pay the cost" };
			int choice = mw.showEffectOptionDialog("Use " + source.name()
					+ "'s special ability without paying the cost?", "Special Cost", options);
			if (choice < 0) return; // dismissed — nothing committed yet
			if (choice == 0) {
				int removed = mw.gameState.removeCounters(source, waiver.counterName(), waiver.count());
				mw.logEntry(source.name() + " — removed " + removed + " " + waiver.counterName()
						+ " Counter(s): special ability used without paying the cost"
						+ "  [remaining: " + mw.gameState.getCounters(source, waiver.counterName()) + "]");
				// A cost-stripped copy rather than a flag threaded through the payment: the waiver
				// says "without paying the cost", and an ability with no costs is exactly that.
				// Its restrictions travel with it, so a once-per-turn or your-turn-only ability is
				// no more usable than it was.
				executeAbilityPayment(eff.withCostsWaived(), source, () -> {},
						new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), isP1, 0, -1);
				return;
			}
		}

		// Zero CP + no X: confirm immediately.  Any S cost is resolved inside executeAbilityPayment,
		// which prompts when more than one hand card can pay it.
		if (rawCost.isEmpty() && !eff.hasXCost()) {
			executeAbilityPayment(eff, source, applyDull, new ArrayList<>(), new ArrayList<>(),
					autoResolveBzTargets(source, bzCosts, isP1), isP1, 0, -1);
			return;
		}

		CardData.SpecialAbilityProxy proxy = eff.isSpecial()
				? mw.effectiveSpecialAbilityProxy(source, isP1) : null;
		String primerName = eff.isSpecial() ? mw.priming.getPrimerCardName(source, isP1) : null;
		new AbilityPaymentDialog(mw.frame, eff, source,
				mw.playerHand(isP1), mw.cpPayableBackupCards(isP1), mw.playerBackupStates(isP1), mw.playerBackupUrls(isP1),
				mw::showZoomAt, mw::hideZoom, proxy, primerName, mw.lightDarkDiscardGrants(isP1),
				eff.isSpecial() && mw.canPaySpecialCostWithCrystal(source, isP1),
				(discards, backups, xValue, sCostIdx, breaks) -> executeAbilityPayment(eff, source, applyDull,
						discards, backups, autoResolveBzTargets(source, bzCosts, isP1), isP1, xValue,
						sCostIdx, breaks),
				mw.breakForCpBackupSlots(isP1))
			.show();
	}


	/**
	 * Executes a P2 (CPU) action ability with pre-computed payment lists.
	 * Skips all UI dialogs; discard-cost and dull-forward-cost extras are auto-resolved.
	 * {@code xValue} is the chosen X for X-cost abilities (active backups remaining after base
	 * payment, min 1); pass 0 for abilities that have no X in their cost.
	 *
	 * @return {@code true} when the ability reached the stack.  A {@code false} answer means the
	 *         payment abandoned the activation, and the caller must move on to the next ability
	 *         rather than restarting its scan — see {@link #executeAbilityPayment}.
	 */
	boolean executeP2AbilityActivation(ActionAbility ability, CardData source,
			Runnable applyDull, List<Integer> backupDullIndices, List<Integer> discardIndices, int xValue) {
		List<ForwardTarget> bzTargets = autoResolveBzTargets(source, ability.breakZoneCosts(), false);
		return executeAbilityPayment(ability, source, applyDull, discardIndices, backupDullIndices, bzTargets, false, xValue, -1);
	}

	/**
	 * Hand slots that can pay a Special ability's S cost: those sharing the source's name, those
	 * sharing the name of the primer beneath it (a primed Forward counts as having both names, so
	 * a primed Ifrit (XVI) accepts a Clive as well), and those meeting the source's proxy
	 * substitute.  Indices in {@code excludedIdxs} are already committed to CP payment.
	 *
	 * <p>Read by the payment above and by {@code ComputerPlayer.p2PlanAbilityPayment}, which
	 * reserves one of them from the CP payment it is planning.  Both read this one rule, so the slot
	 * the planner sets aside is always a slot the payment will accept.
	 */
	List<Integer> specialCostCandidateIdxs(CardData source, List<CardData> hand,
			Collection<Integer> excludedIdxs, boolean isP1) {
		String primerName = mw.priming.getPrimerCardName(source, isP1);
		CardData.SpecialAbilityProxy proxy = mw.effectiveSpecialAbilityProxy(source, isP1);
		List<Integer> eligible = new ArrayList<>();
		for (int i = 0; i < hand.size(); i++) {
			if (excludedIdxs.contains(i)) continue;
			CardData hc = hand.get(i);
			boolean isSameName = source.name().equalsIgnoreCase(hc.name())
					|| (primerName != null && primerName.equalsIgnoreCase(hc.name()));
			if (isSameName || (proxy != null && proxy.meetsSubstitute(hc))) eligible.add(i);
		}
		return eligible;
	}

	/**
	 * The slot in {@code idxs} holding the lowest-cost card, or -1 when {@code idxs} is empty; ties
	 * go to the earliest slot.
	 *
	 * <p>How P2 settles which of several eligible copies pays a 《S》 cost, read from both ends of
	 * the activation — {@code ComputerPlayer.p2SpecialCostPayerSlot} reserving one from its CP plan,
	 * and the payment below choosing one to discard. The reserved slot is never passed between them,
	 * so agreeing on the rule is what makes the reservation mean anything.
	 */
	static int cheapest(List<CardData> hand, List<Integer> idxs) {
		int best = -1;
		for (int i : idxs)
			if (best < 0 || hand.get(i).cost() < hand.get(best).cost()) best = i;
		return best;
	}

	/** Names accepted for {@code source}'s S cost, for the chooser title. */
	private String specialCostDescription(CardData source, boolean isP1) {
		String primerName = mw.priming.getPrimerCardName(source, isP1);
		StringBuilder sb = new StringBuilder(source.name());
		if (primerName != null && !primerName.equalsIgnoreCase(source.name()))
			sb.append(" or ").append(primerName);
		CardData.SpecialAbilityProxy proxy = mw.effectiveSpecialAbilityProxy(source, isP1);
		if (proxy != null) sb.append(" or ").append(proxy.substituteDescription());
		return sb.toString();
	}

	/**
	 * Executes the full payment for an action ability: dulls selected backups,
	 * discards hand cards for CP, optionally dulls the source card, optionally
	 * discards a same-name card (Special), then calls {@link ActionResolver#resolve}.
	 *
	 * @param sCostHandIdx hand index the player already committed to the S cost slot in
	 *     {@link AbilityPaymentDialog}, or {@code -1} when the choice has not been made yet
	 *     (the zero-CP fast path and the CPU path both pass {@code -1}).
	 */
	/**
	 * Pays "put [self] at the bottom of its owner's deck", if this ability prints one.
	 *
	 * <p>The cost names a card, and it is only paid when that name is the source's own — the
	 * printing is a statement about its own carrier, and the same sentence can appear quoted
	 * inside an ability granted to somebody else.
	 *
	 * <p>The slot is found by identity, not by {@code indexOf}: {@link CardData} is a record, so
	 * two copies of the same card are equal and the first copy on the row would be moved instead.
	 */
	void payBottomOfDeckCost(ActionAbility ability, CardData source, boolean isP1) {
		String named = ability.bottomOfDeckCostCardName();
		if (named == null || !named.equalsIgnoreCase(source.name())) return;
		List<CardData> fwds = isP1 ? mw.p1ForwardCards : mw.p2ForwardCards;
		for (int i = 0; i < fwds.size(); i++) {
			if (fwds.get(i) != source) continue;
			GameContext ctx = mw.buildGameContext(isP1);
			if (isP1) ctx.returnP1ForwardToDeckBottom(i);
			else      ctx.returnP2ForwardToDeckBottom(i);
			mw.logEntry((isP1 ? "" : "[P2] ") + source.name()
					+ " put at the bottom of its owner's deck (cost)");
			return;
		}
	}

	private boolean executeAbilityPayment(ActionAbility ability, CardData source,
			Runnable applyDull, List<Integer> discardIndices, List<Integer> backupDullIndices,
			List<ForwardTarget> bzTargets, boolean isP1, int xValue, int sCostHandIdx) {
		return executeAbilityPayment(ability, source, applyDull, discardIndices, backupDullIndices,
				bzTargets, isP1, xValue, sCostHandIdx, Map.of());
	}

	/**
	 * @param backupBreaks Backups put into the Break Zone for CP as part of this payment, slot to
	 *                     the Element each produces (Sherlotta 8-053H) — "a CP cost" in her text is
	 *                     any of them, an ability's included
	 * @return {@code true} when the ability reached the stack, {@code false} when the activation
	 *         was abandoned — a cancelled dialog, a lapsed permission, or a cost that turned out
	 *         to be unpayable.  P2's callers must not re-offer an ability that answered
	 *         {@code false}: an abandonment that commits nothing leaves the board exactly as the
	 *         Main Phase scan found it, which is the shape of an infinite loop.
	 */
	private boolean executeAbilityPayment(ActionAbility ability, CardData source,
			Runnable applyDull, List<Integer> discardIndices, List<Integer> backupDullIndices,
			List<ForwardTarget> bzTargets, boolean isP1, int xValue, int sCostHandIdx,
			Map<Integer, String> backupBreaks) {
		List<String> rawCost = ability.cpCost();
		LinkedHashMap<String, Integer> costByElem = new LinkedHashMap<>();
		for (String e : rawCost) if (!e.isEmpty()) costByElem.merge(e, 1, Integer::sum);
		String[] elems = costByElem.keySet().toArray(String[]::new);

		// Special (S) cost: settle which hand card pays it before anything is committed, so a
		// cancelled choice backs out of the whole activation.  Held as a CardData rather than an
		// index because the CP discards below shift the hand.
		CardData sCostCard = null;
		boolean  sCostFromCrystal = false;
		if (ability.isSpecial()) {
			List<CardData> hand = mw.playerHand(isP1);
			// Glaciela Wezette 17-113L: a Crystal pays the 《S》 in place of the discard. Asked again
			// here rather than trusted from the dialog, because she can leave the field between the
			// choice and the payment.
			boolean crystalPays = mw.canPaySpecialCostWithCrystal(source, isP1);
			if (sCostHandIdx == AbilityPaymentDialog.S_COST_CRYSTAL) {
				// The player ticked "pay 《C》". If the permission has since lapsed the activation
				// backs out rather than silently falling back to a discard they did not choose.
				if (!crystalPays) return false;
				sCostFromCrystal = true;
			} else if (sCostHandIdx >= 0 && sCostHandIdx < hand.size()) {
				sCostCard = hand.get(sCostHandIdx);
			} else {
				List<Integer> eligibleIdxs = specialCostCandidateIdxs(source, hand, discardIndices, isP1);
				List<CardData> eligible = new ArrayList<>();
				for (int i : eligibleIdxs) eligible.add(hand.get(i));
				if (eligible.isEmpty()) {
					// No card can pay, so the Crystal is the only reason this activation was legal.
					if (!crystalPays) return false;
					sCostFromCrystal = true;
				} else if (crystalPays && !isP1) {
					// The CPU spends the Crystal rather than the card: a card in hand is CP, a body
					// or an answer, and is the scarcer of the two often enough to be the default.
					// It can cost the CPU a 《C》 cost later; no printing in the corpus makes that
					// trade sharp enough to plan around.
					sCostFromCrystal = true;
				} else if (crystalPays) {
					// Both are open and no dialog settled it (a zero-CP special skips the payment
					// dialog entirely), so P1 is asked outright.
					Object[] options = {"Pay 《C》", "Discard a card"};
					int choice = mw.showEffectOptionDialog("Pay " + source.name()
							+ "'s S cost with a Crystal, or by discarding?", "S Cost", options);
					if (choice < 0) return false; // dismissed — nothing committed yet
					sCostFromCrystal = choice == 0;
				}
				if (!sCostFromCrystal && !eligible.isEmpty()) {
					if (eligible.size() > 1 && isP1) {
						int pick = mw.showCardImageChooser(eligible,
								"S Cost — discard 1 " + specialCostDescription(source, isP1), true);
						if (pick < 0) return false; // cancelled — nothing committed yet
						sCostCard = eligible.get(pick);
					} else {
						// P2, or P1 holding a single candidate: the cheapest copy. P2's planner
						// reserved a slot by that same rule and kept it out of the CP payment, so
						// reading it the same way here is what makes the reservation hold — the slot
						// itself never travels between them.
						sCostCard = hand.get(cheapest(hand, eligibleIdxs));
					}
				}
			}
		}

		// Pre-select discard-cost cards before committing any payment.
		// This lets the player cancel the discard dialog and back out of the entire activation.
		// We exclude indices already committed to CP payment and the S-cost slot to prevent overlap.
		List<List<CardData>> discardCostPicks = Collections.emptyList();
		if (isP1 && !ability.discardCosts().isEmpty()) {
			Set<Integer> reservedIdxs = new HashSet<>(discardIndices);
			if (sCostCard != null) {
				int sIdx = mw.playerHand(isP1).indexOf(sCostCard);
				if (sIdx >= 0) reservedIdxs.add(sIdx);
			}
			List<List<CardData>> picks = new ArrayList<>();
			for (DiscardCost dc : ability.discardCosts()) {
				List<CardData> hand = mw.playerHand(isP1);
				// The picker is offered every candidate — which Forward of three to spend is P1's
				// call — but only opened when a completable selection exists, since the picker
				// enforces "each of a different card type" by refusing clicks and would otherwise
				// strand the player in a dialog they cannot satisfy.
				List<Integer> eligibleIdx = discardCostCandidateIdxs(dc, hand, reservedIdxs);
				if (discardCostPayerIdxs(dc, hand, reservedIdxs).size() < dc.count()) {
					mw.logEntry("[P1] Not enough eligible cards for discard cost.");
					return false;
				}
				List<CardData> eligible = new ArrayList<>();
				for (int i : eligibleIdx) eligible.add(hand.get(i));
				List<Integer> chosen = mw.showCardMultiImageChooser(eligible, "Discard Cost",
						dc.count(), dc.eachDifferentType(), false);
				if (chosen == null || chosen.size() != dc.count()) return false; // cancelled — nothing committed yet
				List<CardData> pickedCards = new ArrayList<>();
				for (int p : chosen) {
					pickedCards.add(eligible.get(p));
					reservedIdxs.add(eligibleIdx.get(p));
				}
				picks.add(pickedCards);
			}
			discardCostPicks = picks;
		}

		CardData[]  bkpCards  = mw.playerBackupCards(isP1);
		CardState[] bkpStates = mw.playerBackupStates(isP1);
		for (int bi : backupDullIndices) {
			bkpStates[bi] = CardState.DULL;
			mw.playerDullBackupSlot(isP1, bi);
			String cpElem = matchesAnyElement(bkpCards[bi], elems)
					? contributingElement(bkpCards[bi], elems) : (elems.length > 0 ? elems[0] : "");
			if (!cpElem.isEmpty()) mw.playerAddCp(isP1, cpElem, 1);
			// Logged for the same reason the cast path logs it (payP2CostViaBackupsAndDiscards):
			// without it an ability's CP payment is invisible, and a CPU that paid from the wrong
			// slot reads exactly like a CPU that paid from the right one.
			mw.logEntry((isP1 ? "" : "[P2] ") + "Dulls " + bkpCards[bi].name() + " for CP");
		}
		// Break-for-CP payments (Sherlotta 8-053H), after the dull step so a Backup paying both
		// ways is still on the field for it. Its Element joins the clear set below, so CP this cost
		// did not need is not left in the bank.
		Set<String> abilityCpToClear = new java.util.LinkedHashSet<>(java.util.Arrays.asList(elems));
		abilityCpToClear.addAll(mw.breakBackupsForCp(isP1, backupBreaks).keySet());
		discardIndices.sort(Collections.reverseOrder());
		for (int di : discardIndices) {
			CardData discarded = mw.playerHand(isP1).get(di);
			String cpElem = matchesAnyElement(discarded, elems)
					? contributingElement(discarded, elems) : (elems.length > 0 ? elems[0] : "");
			if (!cpElem.isEmpty()) mw.playerAddCp(isP1, cpElem, 2);
			mw.playerBreakFromHand(isP1, di);
		}
		for (String e : abilityCpToClear) { mw.playerSpendCp(isP1, e, mw.playerCpForElem(isP1, e)); mw.playerClearCp(isP1, e); }

		// Crystal cost
		if (ability.crystalCost() > 0) {
			mw.playerSpendCrystals(isP1, ability.crystalCost());
			mw.refreshCrystalDisplays();
		}

		// Mark once-per-turn ability as used for this turn
		if (ability.oncePerTurn())
			mw.usedOncePerTurnAbilities.computeIfAbsent(source, k -> new HashSet<>()).add(ability.effectText());

		// Dull source card
		if (ability.requiresDull()) {
			applyDull.run();
			mw.logEntry("Dull cost: \"" + source.name() + "\" dulled");
		}

		// Special: spend the Crystal settled on above, or discard the card it stands in for. The
		// Crystal is spent here rather than at the choice, so a cancel anywhere above leaves it
		// unspent along with everything else.
		if (sCostFromCrystal) {
			mw.playerSpendCrystals(isP1, 1);
			mw.refreshCrystalDisplays();
			mw.logEntry((isP1 ? "" : "[P2] ") + "Special: paid 《C》 instead of discarding");
		}
		// Special: discard the card settled on above (looked up by identity — the CP discards
		// may have shifted the hand since).
		if (sCostCard != null) {
			int sIdx = mw.playerHand(isP1).indexOf(sCostCard);
			if (sIdx >= 0) {
				mw.playerBreakFromHand(isP1, sIdx);
				mw.logEntry("Special: discarded \"" + sCostCard.name() + "\" from hand");
			}
		}

		// Monster Counter-based abilities: read the counter count on the source card NOW, before the
		// BZ cost payment clears it, so the count can be passed as xValue to effect resolution.
		if (ability.counterScaleName() != null) {
			xValue = mw.gameState.getCounters(source, ability.counterScaleName());
			mw.logEntry(ability.counterScaleName() + " Counters on " + source.name() + ": " + xValue);
		}

		// Break-zone costs: process in reverse index order within each zone to avoid index shifting
		List<ForwardTarget> sortedBz = new ArrayList<>(bzTargets);
		sortedBz.sort((a, b) -> a.zone() == b.zone() ? Integer.compare(b.idx(), a.idx()) : 0);
		mw.lastBzCostForwardPower = 0;
		for (ForwardTarget t : sortedBz) {
			mw.pendingCostBreakDestLabel = t.isP1() ? mw.p1BreakLabel : mw.p2BreakLabel;
			if (t.isP1()) {
				if (t.zone() == ForwardTarget.CardZone.FORWARD) {
					CardData bf = mw.p1ForwardCards.get(t.idx());
					if (bf != null) mw.lastBzCostForwardPower += bf.power();
				}
				switch (t.zone()) {
					case FORWARD -> mw.breakP1Forward(t.idx());
					case BACKUP  -> breakP1BackupSlot(t.idx());
					case MONSTER -> breakP1MonsterSlot(t.idx());
				}
			} else {
				if (t.zone() == ForwardTarget.CardZone.FORWARD) {
					CardData bf = mw.p2ForwardCards.get(t.idx());
					if (bf != null) mw.lastBzCostForwardPower += bf.power();
				}
				switch (t.zone()) {
					case FORWARD -> mw.breakP2Forward(t.idx());
					case BACKUP  -> mw.breakP2BackupSlot(t.idx());
					case MONSTER -> mw.breakP2MonsterSlot(t.idx());
				}
			}
		}

		// Discard costs — paid from hand, no CP generated.
		// P1: apply cards pre-selected above (looked up by identity since CP discards may have shifted indices).
		// P2: auto-select now.
		int dcPickIdx = 0;
		for (DiscardCost dc : ability.discardCosts()) {
			List<CardData> hand = mw.playerHand(isP1);
			List<CardData> toDiscard;
			if (isP1) {
				toDiscard = discardCostPicks.get(dcPickIdx++);
			} else {
				// Payers rather than candidates: "each of a different card type" constrains the set,
				// not the card, and P2 used to take the first N eligible and pay a three-different-
				// types cost with three Forwards.
				List<Integer> payerIdx = discardCostPayerIdxs(dc, hand, Set.of());
				if (payerIdx.size() < dc.count()) {
					mw.logEntry("[P2] Not enough eligible cards for discard cost.");
					return false;
				}
				toDiscard = new ArrayList<>();
				for (int p = 0; p < dc.count(); p++) toDiscard.add(hand.get(payerIdx.get(p)));
			}
			List<Integer> handIdxs = new ArrayList<>();
			for (CardData c : toDiscard) {
				int idx = mw.playerHand(isP1).indexOf(c);
				if (idx >= 0) handIdxs.add(idx);
			}
			handIdxs.sort(Collections.reverseOrder());
			for (int handIdx : handIdxs) {
				String discardedName = mw.playerHand(isP1).get(handIdx).name();
				mw.lastDiscardedCostCard = mw.playerBreakFromHand(isP1, handIdx);
				mw.logEntry("Discard cost: \"" + discardedName + "\" discarded");
			}
		}

		// Remove-from-game costs
		mw.lastRfgCostCards.clear();
		for (RemoveFromGameCost rfg : ability.removeFromGameCosts())
			executeRemoveFromGameCost(rfg, isP1);

		// Return-to-hand costs
		for (ReturnToHandCost rth : ability.returnToHandCosts())
			executeReturnToHandCost(rth, isP1);

		// Counter removal costs
		for (CounterCost cc : ability.counterCosts()) {
			// "remove X …" names no amount: the player picks it here, and what they pick becomes the
			// X the effect reads — the same xValue a 《X》 CP cost produces for Zemus 5-108L, which
			// prints Lenna's effect verbatim.
			int toRemove = cc.variable()
					? chooseVariableCounterAmount(cc, source, isP1)
					: cc.count();
			int removed = mw.gameState.removeCounters(source, cc.counterName(), toRemove);
			if (cc.variable()) xValue = removed;
			mw.logEntry(source.name() + " — removed " + removed + " " + cc.counterName()
					+ " Counter(s) (cost)" + (cc.variable() ? " — X = " + removed : "")
					+ "  [remaining: " + mw.gameState.getCounters(source, cc.counterName()) + "]");
		}

		// Dull-forward costs: player picks active forward(s) (and backups when anyChar) to dull
		mw.lastDullForwardCostPower = 0;
		for (DullForwardCost dfc : ability.dullForwardCosts()) {
			boolean anyChar = "Character".equalsIgnoreCase(dfc.cardType());
			List<CardData>  fwds  = isP1 ? mw.p1ForwardCards  : mw.p2ForwardCards;
			List<CardState> fwdSt = isP1 ? mw.p1ForwardStates : mw.p2ForwardStates;
			CardData[]      bkps  = isP1 ? mw.p1BackupCards   : mw.p2BackupCards;
			CardState[]     bkpSt = isP1 ? mw.p1BackupStates  : mw.p2BackupStates;
			List<ForwardTarget> targets = new ArrayList<>();
			for (int i = 0; i < fwds.size(); i++) {
				if (fwdSt.get(i) != CardState.ACTIVE) continue;
				if (!dullForwardCostMatches(dfc, fwds.get(i))) continue;
				targets.add(new ForwardTarget(isP1, i, ForwardTarget.CardZone.FORWARD));
			}
			if (anyChar) {
				for (int i = 0; i < bkps.length; i++) {
					if (bkps[i] == null || bkpSt[i] != CardState.ACTIVE) continue;
					if (!dullForwardCostMatches(dfc, bkps[i])) continue;
					targets.add(new ForwardTarget(isP1, i, ForwardTarget.CardZone.BACKUP));
				}
			}
			if (targets.isEmpty()) { mw.logEntry("No eligible active card for Dull cost."); continue; }
			List<ForwardTarget> picks;
			if (isP1) {
				picks = mw.showForwardSelectDialog(targets, dfc.count(), false, "Dull Cost");
				if (picks.size() < dfc.count()) continue;
			} else {
				picks = new ArrayList<>(targets.subList(0, Math.min(dfc.count(), targets.size())));
				if (picks.size() < dfc.count()) continue;
			}
			for (ForwardTarget pick : picks) {
				if (pick.zone() == ForwardTarget.CardZone.BACKUP) {
					int bi = pick.idx();
					bkpSt[bi] = CardState.DULL;
					mw.playerDullBackupSlot(isP1, bi);
					mw.logEntry("Dull cost: \"" + bkps[bi].name() + "\" (backup) dulled");
				} else {
					int fi = pick.idx();
					int pow = fwds.get(fi).power();
					mw.lastDullForwardCostPower += pow;
					fwdSt.set(fi, CardState.DULL);
					if (isP1) mw.animateDullForward(fi, null); else mw.animateDullP2Forward(fi, null);
					mw.logEntry("Dull cost: \"" + fwds.get(fi).name() + "\" dulled (power " + pow + ")");
				}
			}
		}

		// Self-mill cost
		if (ability.selfMillCost() > 0) {
			int count = ability.selfMillCost();
			java.util.Deque<CardData> deck = isP1 ? mw.gameState.getP1MainDeck() : mw.gameState.getP2MainDeck();
			int available = deck.size();
			boolean milledOut = available < count;
			if (isP1) {
				mw.buildGameContext(true).millCards(count);
			} else {
				mw.buildGameContext(false).opponentMillCards(count);
			}
			if (milledOut) {
				String msg = isP1 ? "P1 milled out — You Lose!" : "P2 milled out — Opponent Loses!";
				if (available > 0) {
					int animMs = ((available - 1) * 5 + CardSlideAnimator.TOTAL_FRAMES) * CardSlideAnimator.FRAME_MS;
					Timer t = new Timer(animMs, e -> mw.triggerGameOver(msg));
					t.setRepeats(false);
					t.start();
				} else {
					mw.triggerGameOver(msg);
				}
				return false;
			}
		}

		// Reveal cost (Rinoa 18-097R), settled before the bottom-of-deck cost below can move the
		// source: the cards shown stay in hand, so the only thing it leaves behind is the power the
		// effect will read, carried to resolution on the stack entry.
		int revealedPower = mw.payRevealCost(ability.revealCost(), isP1);

		// Bottom-of-deck cost (Bartz 19-048C), paid last of all: it takes the source off the field,
		// so every index-based cost above has already been settled against the board it was chosen
		// on, and the effect goes onto the stack with the card gone — which is the printed order.
		payBottomOfDeckCost(ability, source, isP1);

		mw.logEntry("\"" + source.name() + "\" activated ability");

		// Record special abilities used this turn so Gogo's "Mimic" can replay one later.
		if (ability.isSpecial())
			mw.specialAbilitiesUsedThisTurn.add(new UsedSpecialAbility(source, ability));

		// Depth before selection: a "when this is chosen" trigger fired by the selection has to
		// stay above this ability so it resolves first (see GameState.insertStack).
		int depth = mw.gameState.stackSize();
		java.util.List<ForwardTarget> preTargets = ActionResolver.preSelectTargets(
				ability.effectText(), source, xValue, mw.buildGameContext(isP1));
		mw.gameState.insertStack(depth,
				new StackEntry(source, ability, isP1, xValue, preTargets, revealedPower));
		mw.showStackWindow();
		mw.refreshP1HandLabel();
		mw.refreshP1BreakLabel();
		return true;
	}

	/**
	 * Moves {@code c} to the permanent RFP zone as an ability cost and records the instance in
	 * {@link MainWindow#lastRfgCostCards} so "you can cast [X] removed by this ability's cost"
	 * followups (Sephiroth) can find it.
	 */
	private void removeCardAsCost(CardData c) {
		mw.gameState.addToPermanentRfp(c);
		mw.lastRfgCostCards.add(c);
		mw.logEntry(c.name() + " → Removed From Game (cost)");
	}

	private void executeRemoveFromGameCost(RemoveFromGameCost rfg, boolean isP1) {
		switch (rfg.zone()) {
			case "DECK" -> {
				java.util.Deque<CardData> deck = isP1 ? mw.gameState.getP1MainDeck() : mw.gameState.getP2MainDeck();
				for (int i = 0; i < rfg.count() && !deck.isEmpty(); i++) {
					removeCardAsCost(deck.pollFirst());
				}
				if (isP1) mw.refreshP1DeckLabel(); else mw.refreshP2DeckLabel();
			}
			case "HAND" -> {
				int target = rfg.count();
				for (int pick = 0; pick < target; pick++) {
					List<Integer> eligible = eligibleRfgHandIndices(rfg, isP1);
					if (eligible.isEmpty()) { mw.logEntry("No eligible hand card for remove-from-game cost."); break; }
					List<CardData> hand = mw.playerHand(isP1);
					if (eligible.size() == 1 && rfg.cardName() != null) {
						// Named card — auto-select
						CardData c = hand.get(eligible.get(0));
						hand.remove((int) eligible.get(0));
						removeCardAsCost(c);
					} else {
						String[] options = eligible.stream()
								.map(i -> hand.get(i).name() + " (Cost: " + hand.get(i).cost() + ")")
								.toArray(String[]::new);
						String label = "Remove from game (hand)" + (target > 1 ? " (" + (pick + 1) + "/" + target + ")" : "");
						String choice = (String) JOptionPane.showInputDialog(mw.frame,
								"Choose a card to remove from game:", label,
								JOptionPane.PLAIN_MESSAGE, null, options, options[0]);
						if (choice == null) break;
						int listIdx = java.util.Arrays.asList(options).indexOf(choice);
						if (listIdx < 0) break;
						int handIdx = eligible.get(listIdx);
						CardData c = hand.get(handIdx);
						hand.remove(handIdx);
						removeCardAsCost(c);
					}
				}
				mw.refreshP1HandLabel();
			}
			case "BREAK_ZONE" -> {
				List<CardData> bz = isP1 ? mw.gameState.getP1BreakZone() : mw.gameState.getP2BreakZone();
				if (rfg.count() == -1) {
					// Remove all matching cards
					List<Integer> eligible = eligibleRfgBzIndices(rfg, isP1);
					for (int i = eligible.size() - 1; i >= 0; i--) {
						removeCardAsCost(bz.remove((int) eligible.get(i)));
					}
				} else {
					for (int pick = 0; pick < rfg.count(); pick++) {
						List<Integer> eligible = eligibleRfgBzIndices(rfg, isP1);
						if (eligible.isEmpty()) { mw.logEntry("No eligible Break Zone card for remove-from-game cost."); break; }
						if (eligible.size() == 1 && rfg.cardName() != null) {
							removeCardAsCost(bz.remove((int) eligible.get(0)));
						} else {
							String[] options = eligible.stream().map(i -> bz.get(i).name()).toArray(String[]::new);
							String label = "Remove from game (Break Zone)" + (rfg.count() > 1 ? " (" + (pick + 1) + "/" + rfg.count() + ")" : "");
							String choice = (String) JOptionPane.showInputDialog(mw.frame,
									"Choose a card to remove from game:", label,
									JOptionPane.PLAIN_MESSAGE, null, options, options[0]);
							if (choice == null) break;
							int listIdx = java.util.Arrays.asList(options).indexOf(choice);
							if (listIdx < 0) break;
							int bzIdx = eligible.get(listIdx);
							removeCardAsCost(bz.remove(bzIdx));
						}
					}
				}
				mw.refreshP1BreakLabel();
			}
			default -> {
				// FIELD
				GameContext ctx = mw.buildGameContext(isP1);
				if (rfg.cardName() != null) {
					// Auto-find named card(s) and remove
					List<ForwardTarget> eligible = eligibleRfgFieldTargets(rfg, isP1);
					for (int i = 0; i < rfg.count() && i < eligible.size(); i++)
						ctx.removeTargetFromGame(eligible.get(i));
				} else {
					List<ForwardTarget> eligible = eligibleRfgFieldTargets(rfg, isP1);
					if (eligible.isEmpty()) { mw.logEntry("No eligible field card for remove-from-game cost."); }
					else {
						List<ForwardTarget> picks = mw.showForwardSelectDialog(eligible, rfg.count(), false, "Remove from Game (field)");
						mw.applyTargetsHighestIndexFirst(picks, ctx::removeTargetFromGame);
					}
				}
			}
		}
	}
}
