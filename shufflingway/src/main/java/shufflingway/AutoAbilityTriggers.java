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
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.IntUnaryOperator;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;
import javax.swing.SwingWorker;
import javax.swing.Timer;

import shufflingway.dialog.StackOrderingDialog;
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
import shufflingway.net.ChoiceKind;

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


	// =========================================================================================
	// Batching and ordered dispatch
	// =========================================================================================
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
			for (int i = ordered.size() - 1; i >= 0; i--) runBatchItem(ordered.get(i));
		} else {
			// No dialog: preserve historical iteration order (first walked = pushed
			// first = bottom of stack = resolves last).
			for (StackOrderingDialog.Item it : items) runBatchItem(it);
		}
	}

	/** Runs one collected trigger with the arriving card it was collected under standing again. */
	private void runBatchItem(StackOrderingDialog.Item it) {
		CardData previousEntered = mw.triggeringEnteredCard;
		mw.triggeringEnteredCard = it.enteredCard();
		try {
			executeAutoAbilityImpl(it.ability(), it.source(), it.controllerIsP1(), it.paidExtraCost(),
					it.triggerCard());
		} finally {
			mw.triggeringEnteredCard = previousEntered;
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
				// 26-026R Kuja's "1 Ice Backup", 22-015C Meeth's "1 Backup other than Meeth".
				"(?:(?<element>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+)?" +
				"(?<targets>Backups?|Forwards?|Monsters?|Characters?)\\s+" +
				"(?:without\\s+《(?<excludekw>[^》]+)》\\s+)?" +
				"(?:other\\s+than\\s+(?<except>.+?)\\s+)?" +
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
	 * Matches "dull [CardName] if it is active. If/When you do so, [sub-effect]" -- Yuna 1-214S and
	 * Mira 4-137L, the corpus's two printings.
	 *
	 * <p>A self-dull cost, in the same family as {@link #FA_PUT_SELF_INTO_BZ_IF_DO_SO}: the source
	 * pays with its own state and the payoff follows only if it could. "if it is active" is not a
	 * separate clause to parse but the whole of what makes the cost payable, so it is required
	 * here rather than optional -- a text that dulls unconditionally is a different sentence.
	 *
	 * <p>Groups: {@code cardname} (checked against the source at execution time), {@code sub}.
	 */
	static final Pattern FA_DULL_SELF_IF_DO_SO = Pattern.compile(
			"(?i)^dull\\s+(?<cardname>.+?)\\s+if\\s+it\\s+is\\s+active[.,]?\\s+" +
			"(?:When|If)\\s+you\\s+do\\s+so[,.]?\\s+(?<sub>.+?)$",
			Pattern.DOTALL
	);

	/**
	 * Matches "put [CardName] into the Break Zone. If/When you do so, [sub-effect]"
	 * where [CardName] is the source card itself (self-break with conditional follow-up).
	 * Distinct from {@link #FA_PUT_INTO_BZ_WHEN_DO_SO} which requires a numeric count and "you control".
	 *
	 * <p>{@code cardname} may not open with a quantity. "put the top 5 cards of your deck" (18-009H,
	 * 22-005R, 22-079L, 26-117R) and "put any number of Forwards and/or Monsters you control"
	 * (24-033L Bhunivelze) are no card's name, and claimed here the handler refused them as not
	 * naming the source — so all five did nothing at all, although parse() reads each whole.
	 */
	static final Pattern FA_PUT_SELF_INTO_BZ_IF_DO_SO = Pattern.compile(
			"(?i)^put\\s+(?!the\\s+top\\b|any\\s+number\\b|up\\s+to\\b|all\\b|\\d)" +
			"(?<cardname>.+?)\\s+into\\s+the\\s+Break\\s+Zone[.,]?\\s+" +
			"(?:When|If)\\s+you\\s+do\\s+so[,.]?\\s+(?<sub>.+?)$",
			Pattern.DOTALL
	);

	/**
	 * "if [condition], you may [put … | pay …]" — a gate in front of an optional cost this layer
	 * already pays. Two conditions are read, and only these, so an unrecognised one never reaches
	 * the handler to be waved through:
	 * <ul>
	 *   <li>"you have cast N or M / N or more cards this turn" — 15-061H Lehko Habhoka's two
	 *       end-of-turn abilities ({@code castmin}, {@code castmax});</li>
	 *   <li>"you control N or more Forwards and/or Backups" — 22-122L Tidus ({@code ctrlmin},
	 *       {@code ctrltypes}).</li>
	 * </ul>
	 * Only a "you may put"/"you may pay" continuation is claimed: the other "if you have cast"
	 * printings are plain effects {@link ActionResolver#parse} reads with their gate.
	 * Group {@code rest} — the cost and its payoff, handed back to the table.
	 */
	static final Pattern FA_CONDITION_GATE_MAY = Pattern.compile(
			"(?i)^if\\s+you\\s+(?:" +
				"have\\s+cast\\s+(?<castmin>\\d+)\\s+or\\s+(?:(?<castmax>\\d+)|more)\\s+cards\\s+this\\s+turn" +
			"|" +
				"control\\s+(?<ctrlmin>\\d+)\\s+or\\s+more\\s+(?<ctrltypes>(?:Forwards|Backups|Monsters|Characters)" +
				"(?:\\s+and/or\\s+(?:Forwards|Backups|Monsters))*)" +
			"),\\s+you\\s+may\\s+(?<rest>(?:put|pay)\\s.+)$",
			Pattern.DOTALL
	);

	/**
	 * Matches "choose 1 &lt;target&gt;. You may put 1 &lt;price&gt; into the Break Zone. If you do
	 * so, &lt;payoff on the chosen card&gt;." — 4-087R Delita and 7-020C Lulu, the only two
	 * printings of this shape.
	 *
	 * <ul>
	 *   <li>{@code count}/{@code tgttype}/{@code tgtctl} — the card the ability chooses first.
	 *       Delita is restricted to "opponent controls"; Lulu's bare "1 Forward" is either side.</li>
	 *   <li>{@code ofyour}/{@code youcontrol} — the two ways the price says it comes off your own
	 *       field ("1 of your Forwards", "1 Backup … you control"). One of them must be present;
	 *       {@link #executeChooseThenMayPutIntoBzAutoAbility} refuses the match otherwise rather
	 *       than guessing an ownership the text did not state.</li>
	 *   <li>{@code samecost} — Delita's "of the same cost", read against the <em>chosen</em>
	 *       Forward rather than against Delita: the sentence has just named one card, and that is
	 *       the cost the price has to match.</li>
	 *   <li>{@code excludename} — "other than Delita" / "other than Lulu", the source naming
	 *       itself so it cannot pay its own price.</li>
	 *   <li>{@code sub} — the payoff, which acts on the already-chosen card and so is resolved
	 *       against it rather than selecting for itself.</li>
	 * </ul>
	 *
	 * <p>Dispatched here rather than left to {@link ActionResolver#parse}: the Choose family reads
	 * the opening "choose 1 Forward …" and stops, silently dropping the price and the payoff, so
	 * both cards used to resolve as a bare, consequence-free targeting.
	 */
	static final Pattern FA_CHOOSE_THEN_MAY_PUT_INTO_BZ =
			Pattern.compile(
				"(?i)^choose\\s+(?<count>\\d+)\\s+" +
				"(?<tgttype>Forwards?|Backups?|Monsters?|Characters?)" +
				"(?:\\s+(?<tgtctl>(?:your\\s+)?opponent\\s+controls|you\\s+control))?[.,]" +
				"\\s+You\\s+may\\s+put\\s+(?<price>\\d+)\\s+" +
				"(?<ofyour>of\\s+your\\s+)?" +
				"(?:(?<active>active)\\s+)?" +
				"(?:(?<element>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+)?" +
				"(?<pricetype>Forwards?|Backups?|Monsters?|Characters?)" +
				"(?<samecost>\\s+of\\s+the\\s+same\\s+cost)?" +
				"(?:\\s+other\\s+than\\s+(?<excludename>[^,.]+?))?" +
				"(?<youcontrol>\\s+you\\s+control)?" +
				"\\s+into\\s+the\\s+Break\\s+Zone[.,]\\s+" +
				"If\\s+you\\s+do\\s+so[,.]?\\s+(?<sub>.+?)$",
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
			// "by a Dark card" — Ozma 5-124H, the one printing that names the source's ELEMENT
			// rather than the kind of effect. Every other arm here answers "what sort of thing
			// dealt this", and the answer decides which routes are in scope; this one answers
			// "what colour was it", which leaves every route in scope, battle damage included.
			// Its own group so the reader does not have to re-parse the clause it matched.
			"|\\s+(?:by|from)\\s+an?\\s+(?<srcelement>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)" +
			"\\s+card" +
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
	 * Groups: {@code card}, {@code target} (contains "Forward", "opponent" and/or "player"), and
	 * {@code telem} — the Element the damaged Forward must have, or {@code null} for any Forward.
	 *
	 * <p>{@code telem} sits <em>inside</em> {@code target} on purpose, so every reader that asks
	 * {@code target.contains("forward")} keeps answering as it did. Only the two readers that can
	 * double damage to a Forward — {@code DamageResolver.modifyIncomingDamage} and
	 * {@code MainWindow.fieldAbilityCombatOutgoingMult} — have to test it. The two that ask about
	 * damage to a <em>player</em> never see it, because an element-qualified clause names no player.
	 * <b>A reader that doubles to a Forward and ignores {@code telem} doubles against every
	 * Element</b>, which is strictly stronger than any card that prints this.
	 */
	static final Pattern FA_OUTGOING_DAMAGE_DOUBLER = Pattern.compile(
		"(?i)^If\\s+(?<card>.+?)\\s+deals\\s+damage\\s+to\\s+" +
		// Both orders of the two-target wording. Every printing but one says "a Forward or your
		// opponent"; Snovlinka 27-112H grants itself the same clause with the halves the other way
		// round. The reversed arm is listed ahead of the bare "your opponent" so the longer read
		// is tried first — every reader tests this group with contains(), so an arm naming both
		// answers to the Forward question and the opponent question alike.
		//
		// The optional Element belongs to 17-133S Scarmiglione, who names one on entering the
		// field and is granted this clause with it spelled in. No printing states an Element here
		// and also names a player, so the qualified arm carries no "or your opponent" tail.
		// "an" for the two Elements that need it — the clause is generated from the printed
		// template when the Element is named, and it is logged to the player as granted text.
		"(?<target>an?\\s+(?:(?<telem>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)\\s+)?" +
		"Forward(?:\\s+or\\s+your\\s+opponent)?" +
		"|your\\s+opponent\\s+or\\s+a\\s+Forward|your\\s+opponent|a\\s+player)" +
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
	 * conditions it would silently drop. Lightning 26-098L ("If Lightning <em>forming a party</em>
	 * deals damage…") reaches the pattern all the same: {@code card} is lazy but unrestricted, so it
	 * absorbs the qualifier and the match succeeds with {@code card = "Lightning forming a party"}.
	 * What excludes an unread qualifier is the caller comparing {@code card} against the carrier's
	 * own name — every reader of this pattern must make that check, not assume the anchors did it.
	 * {@link #FA_SUBJECT_FORMING_PARTY} is how a reader that does honour the party qualifier takes
	 * it off first.
	 *
	 * <p>{@code notbyability} is Behemoth 24-084R's "other than by its ability", the one qualifier
	 * read here rather than left to the caller's name check — it narrows <em>which damage</em> the
	 * replacement covers rather than which card, so no name test could catch it. Present, it means
	 * combat damage only; the ability path has to consult
	 * {@link DamageResolver#abilityDamageToOpponentOverride} instead, which drops these.
	 * Groups: {@code card}, {@code notbyability} (optional), {@code amount}.
	 */
	static final Pattern FA_OUTGOING_DAMAGE_TO_OPPONENT_SETS_TO = Pattern.compile(
		"(?i)^If\\s+(?<card>.+?)\\s+deals\\s+damage\\s+to\\s+your\\s+opponent" +
		"(?<notbyability>\\s+other\\s+than\\s+by\\s+its\\s+ability)?,\\s+" +
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


	// =========================================================================================
	// Field-ability queries: boosts, arms and damage modifiers
	// =========================================================================================
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
		return job != null && mw.meetsJobFilterEffective(dealer, job.trim());
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
		if (job != null && mw.meetsJobFilterEffective(dealer, job.trim())) return true;
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


	// =========================================================================================
	// Field-ability queries: suppressions, cast permissions and locks
	// =========================================================================================
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

	/**
	 * "[Self] does not activate during your Active Phase." — the standing self-lock printed by
	 * Larkeicus 13-014R, Broden 25-098R, Alys the Ensorceled 17-118R, Ryid 5-023C, Unei 5-027R and
	 * Ghido 3-131H, who writes "the Active Phase" where the rest write "your".
	 *
	 * <p>A passive read off the card, not an instruction: nothing resolves it, {@code MainWindow
	 * .blockedFromActivating} asks it of every card the Active Phase walks. That makes it the
	 * permanent member of the family whose other two are held elsewhere — the warden-held lock in
	 * {@code MainWindow.nonActivatingWhileWardenOnField}, laid on a card a choice picked, and the
	 * one-shot {@code skipNextActivePhase}, spent by the phase it is charged for.
	 *
	 * <p>It gates the Active Phase only. Four of the six print an activate of their own — Ghido's
	 * 《Water》, Ryid's break-zone trigger — and those still turn the card back over.
	 *
	 * <p>Anchored end to end and name-checked against the carrier by {@link #hasSelfNeverActivates},
	 * which is what keeps the three apart: "your <em>next</em> Active Phase" fails the alternation,
	 * and both "As long as …" wordings — Vincent 16-024H's lock on someone else's card and Reeve
	 * 16-104R's lock on his own, held while a borrowed Forward stands — leave their prefix in
	 * {@code name} and fail the name check. Reeve is a real effect this does not implement; failing
	 * closed leaves him visibly unread rather than silently locked forever.
	 */
	static final Pattern FA_SELF_NEVER_ACTIVATES = Pattern.compile(
		"(?i)^(?<name>.+?)\\s+does\\s+not\\s+activate\\s+during\\s+(?:your|the)\\s+" +
		"Active\\s+Phase[.!]?$"
	);

	/**
	 * "If you don't control any Forwards, [Self] does not activate during your Active Phase." —
	 * Aria (III) 10-108R, the one printing of {@link #FA_SELF_NEVER_ACTIVATES} that names a
	 * condition, and the price her 《Dull》 power pump pays for costing 1.
	 *
	 * <p>Checked ahead of the unconditional pattern, which would otherwise take the whole sentence
	 * with the condition swallowed into {@code name} — where the name check turns it down, so the
	 * order is what makes her readable rather than what keeps her safe.
	 *
	 * <p>"You" is Aria's controller, so the query is asked of that side's Forwards; and it is asked
	 * live, because a Forward breaking mid-phase is exactly when the answer changes.
	 */
	static final Pattern FA_SELF_NEVER_ACTIVATES_WITHOUT_FORWARDS = Pattern.compile(
		"(?i)^If\\s+you\\s+don'?t\\s+control\\s+any\\s+Forwards,\\s+(?<name>.+?)\\s+does\\s+not\\s+" +
		"activate\\s+during\\s+your\\s+Active\\s+Phase[.!]?$"
	);

	/** Returns true if {@code card} prints the unconditional standing "does not activate" lock. */
	static boolean hasSelfNeverActivates(CardData card) {
		return namesSelf(card, FA_SELF_NEVER_ACTIVATES);
	}

	/** Returns true if {@code card} prints Aria (III) 10-108R's Forward-less arm of that lock. */
	static boolean hasSelfNeverActivatesWithoutForwards(CardData card) {
		return namesSelf(card, FA_SELF_NEVER_ACTIVATES_WITHOUT_FORWARDS);
	}

	/**
	 * Whether any of {@code card}'s field abilities matches {@code p} end to end with its
	 * {@code name} group naming {@code card} itself.
	 */
	private static boolean namesSelf(CardData card, Pattern p) {
		if (card == null || card.name() == null) return false;
		for (FieldAbility fa : card.fieldAbilities()) {
			Matcher m = p.matcher(fa.effectText().trim());
			if (m.matches() && m.group("name").trim().equalsIgnoreCase(card.name())) return true;
		}
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

	/**
	 * The same replacement written as a sentence inside an auto ability instead of as a field
	 * ability of its own, and scoped to that ability rather than to the card: "If a Forward damaged
	 * by this ability is put into the Break Zone this turn, remove it from the game instead." —
	 * Shantotto 4-083L.
	 *
	 * <p>Honoured as "damaged by this card", which is what {@code damagedBySourcesThisTurn}
	 * records — it keys on the source card, not on which of its abilities dealt the blow. The two
	 * readings differ only for a Shantotto who also deals battle damage in the same turn, where
	 * this is the more generous one. Narrowing it would mean tracking damage per ability, which
	 * nothing else in the corpus asks for.
	 */
	static final Pattern FA_DAMAGED_BY_THIS_ABILITY_TO_BZ_RFG = Pattern.compile(
		"(?i)If\\s+(?:a|the)\\s+Forward\\s+damaged\\s+by\\s+this\\s+ability\\s+is\\s+put\\s+" +
		"(?:from\\s+the\\s+field\\s+)?into\\s+the\\s+Break\\s+Zone\\s+" +
		"(?:this\\s+turn|(?:on|during)\\s+the\\s+same\\s+turn),\\s+" +
		"remove\\s+it\\s+from\\s+the\\s+game\\s+instead[.!]?"
	);

	/**
	 * Whether {@code card} carries the damaged-Forward remove-from-game replacement — as a field
	 * ability naming itself (14-011H Susano, Lord of the Revel), or as a sentence inside an auto
	 * ability saying "this ability" (4-083L Shantotto).
	 *
	 * <p>Both spellings describe one continuous replacement read as the damaged Forward leaves the
	 * field, so both are answered here rather than one of them being resolved as a step of its
	 * ability. Searched rather than matched whole for the auto form: it is one sentence of an
	 * effect whose other sentence is what the ability actually does.
	 */
	static boolean hasDamagedBySelfFieldToBzRfg(CardData card) {
		for (FieldAbility fa : card.fieldAbilities()) {
			Matcher m = FA_DAMAGED_BY_SELF_FIELD_TO_BZ_RFG.matcher(fa.effectText().trim());
			if (m.matches() && m.group("card").trim().equalsIgnoreCase(card.name())) return true;
		}
		for (AutoAbility fa : card.autoAbilities())
			if (FA_DAMAGED_BY_THIS_ABILITY_TO_BZ_RFG.matcher(fa.effectText()).find()) return true;
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
	 * "[During your opponent's turn, ]the Forwards opponent controls cannot use action abilities."
	 * — Sin 14-045H with the turn clause, Charlotte 27-128S without it.
	 *
	 * <p>Both clauses name the same player, and it is not the carrier's controller: the lock lands
	 * on the opposing player's Forwards. Sin adds that it bites only while that player is the one
	 * taking the turn, which shuts down responses to the carrier's own attacks rather than the
	 * opponent's whole game; Charlotte's holds on every turn, so an opposing Forward's action
	 * ability is dead for as long as she stands.
	 *
	 * <p>Group {@code turngate} is present only for Sin's printing, and
	 * {@link #oppForwardsActionAbilityLock} is what turns it into the window each one means. Left
	 * as one pattern rather than two because the lock and its scoping are identical and only the
	 * window differs — and reading it as a group is what keeps a missing clause from silently
	 * inheriting the other card's timing.
	 *
	 * <p>Action abilities only. Under rule 6-1-1 a Special Ability is its own kind of ability
	 * rather than a form of action ability, so it is not caught here; nor are auto or field
	 * abilities, which nobody "uses".
	 */
	static final Pattern FA_OPP_FORWARDS_CANNOT_USE_ACTION_ABILITIES = Pattern.compile(
		"(?i)^(?<turngate>During\\s+your\\s+opponent.?s\\s+turn,\\s+)?the\\s+Forwards?\\s+" +
		"(?:your\\s+)?opponent\\s+controls?\\s+cannot\\s+use\\s+action\\s+abilit(?:y|ies)[.!]?$"
	);

	/** When an opposing-Forwards action-ability lock bites. */
	enum ActionAbilityLockWindow {
		/** The card prints no such lock. */
		NONE,
		/** Sin 14-045H: only while the locked player is the one taking the turn. */
		LOCKED_PLAYERS_TURN,
		/** Charlotte 27-128S: on every turn, for as long as the carrier is on the field. */
		ALWAYS
	}

	/**
	 * The window in which {@code card} locks the opposing player's Forwards out of action
	 * abilities, or {@link ActionAbilityLockWindow#NONE} when it prints no such ability.
	 *
	 * <p>A card printing both would be bound by the wider of the two, so {@code ALWAYS} returns as
	 * soon as it is found. No printing does, but the alternative — first match wins — would make
	 * the answer depend on the order the abilities happen to be listed in.
	 */
	static ActionAbilityLockWindow oppForwardsActionAbilityLock(CardData card) {
		if (card == null) return ActionAbilityLockWindow.NONE;
		ActionAbilityLockWindow found = ActionAbilityLockWindow.NONE;
		for (FieldAbility fa : card.fieldAbilities()) {
			Matcher m = FA_OPP_FORWARDS_CANNOT_USE_ACTION_ABILITIES.matcher(fa.effectText().trim());
			if (!m.matches()) continue;
			if (m.group("turngate") == null) return ActionAbilityLockWindow.ALWAYS;
			found = ActionAbilityLockWindow.LOCKED_PLAYERS_TURN;
		}
		return found;
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

		/**
		 * True when {@code c} is inside the filtered set, using the shared field-filter rules.
		 *
		 * @param jobsStripped whether {@code c} has lost its Jobs for the turn (Exdeath 3-100L)
		 */
		boolean coversCard(CardData c, boolean jobsStripped) {
			if (c == null) return false;
			boolean typeOk = (inclForwards && c.isForward())
			              || (inclBackups  && c.isBackup())
			              || (inclMonsters && (c.isMonster() || c.alsoCountsAsMonster()));
			return typeOk
				&& CardFilters.meetsCardNameFilter(c, cardName)
				&& CardFilters.meetsCategoryFilter(c, category)
				&& CardFilters.meetsJobFilter(c, job, jobsStripped);
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
	/**
	 * "[If you control &lt;cond&gt;, ]you can't lose the game." — PR-143 Garnet. Groups: {@code cond}
	 * (a {@link ControlCondition} phrase, or {@code null} when the protection is unconditional).
	 */
	static final Pattern FA_CANNOT_LOSE_THE_GAME = Pattern.compile(
		"(?i)^(?:If\\s+you\\s+control\\s+(?<cond>.+?),\\s+)?you\\s+(?:can't|cannot)\\s+lose\\s+the\\s+game[.!]?$"
	);

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
	 *   <li>{@code total}    — total number of options listed, absent on the "from the following"
	 *       spelling, which prints no count (2-109H Golbez)</li>
	 *   <li>{@code actions}  — the remainder containing the quoted action strings</li>
	 * </ul>
	 */
	private static final Pattern FA_SELECT_FOLLOWING_ACTIONS =
		Pattern.compile(
			"(?i)^(?:if\\s+(?<condition>[^,]+),\\s+)?(?<opp>your\\s+opponent\\s+)?selects?\\s+(?<upTo>up\\s+to\\s+)?" +
			"(?<select>\\d+)\\s+(?:of\\s+the\\s+(?<total>\\d+)\\s+following\\s+actions?|from\\s+the\\s+following)" +
			"[.!]?\\s*(?<actions>.+)$",
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

	/**
	 * Matches "pay 《cost》…[.] When/If you do so, sub-effect[. The maximum you can pay for 《X》 is N]".
	 *
	 * <p>The cost is a <em>run</em> of tokens, not one: 25-057R Cutter prints 《X》《X》, meaning two
	 * CP for every 1 it buys of X. Written as a single token the pattern stopped at the first 》,
	 * found no whitespace before the second 《, and the card went unwired. Group 1 is the whole run
	 * — {@link #executePayWhenDoSoAutoAbility} tallies it; group 2 is the sub-effect.
	 */
	private static final Pattern FA_PAY_WHEN_DO_SO = Pattern.compile(
		"(?i)^pay\\s+((?:《[^》]+》)+)[.,]?\\s+(?:When|If)\\s+you\\s+do\\s+so[,.]?\\s+(.+?)(?:[.,]?\\s+The\\s+maximum\\s+you\\s+can\\s+pay\\s+for\\s+《X》\\s+is\\s+\\d+\\.?)?$",
		Pattern.DOTALL
	);
	/**
	 * "pay 《CP run》 or 《C》《C》. When/If you do so, sub-effect" — 25-010H Salamander (III): the
	 * price may be paid in CP or in Crystals, the payer's choice. Group 1 is the CP run, group 2
	 * the Crystal run, group 3 the sub-effect.
	 */
	private static final Pattern FA_PAY_OR_CRYSTALS_WHEN_DO_SO = Pattern.compile(
		"(?i)^pay\\s+((?:《[^》]+》)+)\\s+or\\s+((?:《C》)+)[.,]?\\s+(?:When|If)\\s+you\\s+do\\s+so[,.]?\\s+(.+?)$",
		Pattern.DOTALL
	);
	/**
	 * "pay 《cost》 and discard 1 &lt;type&gt;. When you do so, sub-effect" — 20-102L Mira. Group 1 is
	 * the cost run, group 2 the discard type, group 3 the sub-effect.
	 */
	private static final Pattern FA_PAY_AND_DISCARD_WHEN_DO_SO = Pattern.compile(
		"(?i)^pay\\s+((?:《[^》]+》)+)\\s+and\\s+discard\\s+1\\s+(Summon|Forward|Backup|Monster|Character|card)[.,]?\\s+" +
		"(?:When|If)\\s+you\\s+do\\s+so[,.]?\\s+(.+?)$",
		Pattern.DOTALL
	);
	/** One 《…》 token of a {@link #FA_PAY_WHEN_DO_SO} cost run. */
	private static final Pattern FA_COST_TOKEN = Pattern.compile("《([^》]+)》");
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


	// =========================================================================================
	// Enters-the-field triggers
	// =========================================================================================
	void triggerAutoAbilitiesForEntersField(CardData card, boolean isP1) {
		triggerAutoAbilitiesForEntersField(card, isP1, false);
	}

	/**
	 * Whether the card entering {@code isP1}'s field is doing so "due to" what {@code trigger} names
	 * (see {@code CardData.entersByEffectTrigger}). A Summon resolving — or its EX Burst, which runs
	 * under the Summon as ability source — is a Summon, not an ability.
	 */
	private boolean enteredByEffect(String trigger, boolean isP1) {
		CardData abil = mw.currentAbilitySource;
		boolean bySummon  = (mw.currentResolutionIsSummon && mw.currentSummonSource != null)
				|| (abil != null && abil.isSummon());
		boolean byAbility = abil != null && !abil.isSummon() && !mw.currentResolutionIsSummon;
		boolean summonIsP1 = mw.currentSummonSource != null ? mw.currentSummonSourceIsP1 : mw.currentAbilitySourceIsP1;
		switch (trigger) {
			case "enters the field by ability":            return byAbility;
			case "enters the field by summon or ability":  return byAbility || bySummon;
			case "enters the field by own summon or ability":
				return (byAbility && mw.currentAbilitySourceIsP1 == isP1) || (bySummon && summonIsP1 == isP1);
			default:
				String prefix = "enters the field by ability of ";
				return trigger.startsWith(prefix) && byAbility
						&& CardFilters.meetsCardNameFilter(abil, trigger.substring(prefix.length()));
		}
	}

	/** @param paidExtraCost whether {@code card}'s optional extra cost was paid when it was cast (threaded to its own "enters the field" trigger only, not to watcher abilities on other cards). */
	void triggerAutoAbilitiesForEntersField(CardData card, boolean isP1, boolean paidExtraCost) {
		// Recorded ahead of the suppression below: a card whose abilities do not trigger has still
		// entered the field.
		mw.turn(isP1).charactersEnteredThisTurn.add(card);
		// Why it entered, for "entered the field due to an ability of …" (26-035R Snow). An
		// ability resolving right now is the cause; a Summon is not an ability.
		CardData cause = mw.currentAbilitySource;
		if (cause != null && cause != card && !cause.isSummon()) mw.enteredFieldByAbilityOf.put(card, cause);
		else mw.enteredFieldByAbilityOf.remove(card);
		if (mw.lastCardWarpedIn) mw.enteredViaWarp.add(card); else mw.enteredViaWarp.remove(card);
		// Consumed here whether or not anything fires: the next arrival of this card is a new one.
		MainWindow.EntryOrigin origin = mw.entryOrigin.remove(card);
		// From a hand only if its last move was out of one and nothing else claims the entry
		// (28-064H Cactuar's "other than from any player's hand").
		boolean fromHand = mw.leftHandAwaitingArrival.remove(card) && origin == null && !mw.lastCardWarpedIn;
		if (fromHand) mw.enteredOtherThanFromHandThisTurn.remove(card);
		else          mw.enteredOtherThanFromHandThisTurn.add(card);
		// An arrival can start a can't-lose condition (PR-143 Garnet's eighth Forward) or end one
		// (a card whose field ability takes hers away).
		mw.refreshCannotLoseTheGame();
		if (mw.suppressAutoAbilityForNextCards > 0) {
			mw.suppressAutoAbilityForNextCards--;
			// Re-evaluate field boosts even when ETF auto-abilities are suppressed
			mw.refreshAllForwardSlots();
			for (int i = 0; i < mw.p2ForwardCards.size(); i++) mw.refreshP2ForwardSlot(i);
			mw.enforceForwardBreakRuleProcess();
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
					// And its inverse, "enters the field from your hand" (Kain 13-073H, G'raha Tia
					// 27-044L), read off the same signal. That signal is really "cast from hand", so
					// a card *played* from hand without being cast — Leo 16-126R, Mind Flayer
					// 15-120H, Nanaa Mihgo 22-048H can each do it — reads as not from hand here.
					// The engine draws that line in one place on purpose; see
					// GameContext.triggeringCardEnteredWithoutPayingCost, which answers the same way.
					if (fa.trigger().equals("enters the field from hand") && !mw.lastCardWasCast) continue;
					// "enters the field due to an ability / a Summon or an ability / your Summons or
					// abilities / an ability of Card Name X" — answered by what is resolving as it arrives.
					if (fa.trigger().startsWith("enters the field by ") && !enteredByEffect(fa.trigger(), isP1)) continue;
					// "enters the field from the Break Zone / from the deck" (20-130L Zenos, 15-109R Ultros).
					if (fa.trigger().equals("enters the field from break zone") && origin != MainWindow.EntryOrigin.BREAK_ZONE) continue;
					if (fa.trigger().equals("enters the field from deck") && origin != MainWindow.EntryOrigin.DECK) continue;
					executeAutoAbility(fa, card, isP1, paidExtraCost);
				}
				// Watcher dispatch: "When a <Type> enters your field, ..." abilities live on other field cards
				// on the same side as the entering card.
				fireEntersYourFieldWatchers(card, isP1);
				// Also fire watcher abilities on break-zone cards (only those gated by bzConditionCard).
				fireEntersYourFieldBreakZoneWatchers(card, isP1);
				// And on removed cards, for the abilities they use from there (23-060L Vincent).
				for (CardData w : removedFromGameResidents(isP1))
					for (AutoAbility fa : warpZoneAbilities(w))
						if (fa.trigger().equals("enters your field")
								&& matchesEntersFieldSubject(fa.triggerCard(), card, w))
							executeAutoAbility(fa, w, isP1);
			}
			// Watcher dispatch: "When a <Type> of your opponent enters the field, ..." lives on the
			// opponent's cards and uses trigger "enters opponent's field".
			// Not suppressed — the controller still gets their own triggers.
			fireEntersOpponentFieldWatchers(card, isP1);
			fireEntersEitherFieldWatchers(card);
		});
		// Remedi-style watchers ("a Character enters your opponent's field other than from their
		// hand") — only when the entering card was NOT played from hand. Run inline (outside the
		// batch) with the entering card supplied as the target, so "break it" can act on it.
		if (!mw.lastCardWasCast) fireEntersOpponentFieldNotFromHandWatchers(card, isP1);
		// Re-evaluate all conditional field boosts now that the field composition has changed
		mw.refreshAllForwardSlots();
		for (int i = 0; i < mw.p2ForwardCards.size(); i++) mw.refreshP2ForwardSlot(i);
		// A Forward can enter already at 0 power — PR-171 Warrior of Light without a Crystal.
		mw.enforceForwardBreakRuleProcess();
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
			// The self-sacrifice form (26-031H Cid Raines, 28-010R Jack Garland: "you may put [Self]
			// into the Break Zone. When you do so, break it / deal that Forward 9000 damage") goes
			// through the trigger layer with the entering card standing behind "it".
			if ("PutSelfIntoBzIfDoSo".equals(inlineShapeOf(fa.effectText()))) {
				executeWithEnteredCard(fa, watcher, watcherIsP1, enteringCard);
				continue;
			}
			// Otherwise only the "if your opponent doesn't pay 《N》, [action]" form (Remedi) is wired,
			// resolved inline with the entering card as its target; any other watcher stays dormant.
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
	 * Fires a card's own "When a &lt;Name&gt; Counter is placed on [Self]" abilities — 16-031R
	 * Scarlet, the corpus's one counter-placed trigger.
	 *
	 * <p>The trigger's subject is the counter rather than a card, so {@link AutoAbility#triggerCard()}
	 * holds "a Development Counter" and the counter's name is read back out of it. An ability whose
	 * name does not match the counter just placed is not this placement's trigger and does not fire.
	 *
	 * <p>Called from the single-card placement route, which is the only one that can put a counter
	 * on a named card: the mass routes beside it place one counter name across a whole field, and
	 * nothing in the corpus places a watched counter that way.
	 */
	void fireCounterPlacedWatchers(CardData card, String counterName) {
		if (card == null || counterName == null) return;
		Boolean side = mw.fieldSideOf(card);
		if (side == null) return;
		for (AutoAbility fa : mw.effectiveAutoAbilities(card)) {
			if (!fa.trigger().equals("counter placed")) continue;
			Matcher cm = FA_COUNTER_PLACED_SUBJECT.matcher(fa.triggerCard().trim());
			if (!cm.matches() || !cm.group("counter").trim().equalsIgnoreCase(counterName)) continue;
			executeAutoAbility(fa, card, side);
		}
	}

	/** The counter-placed trigger's subject — "a Development Counter". */
	private static final Pattern FA_COUNTER_PLACED_SUBJECT = Pattern.compile(
		"(?i)^(?:an?\\s+)?(?<counter>.+?)\\s+Counters?$"
	);

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

	/**
	 * "When a Monster enters either player's field" — 23-103C Quina. Watchers on both sides, each
	 * resolving under its own controller.
	 */
	private void fireEntersEitherFieldWatchers(CardData enteringCard) {
		for (boolean watcherIsP1 : new boolean[] { true, false })
			for (CardData watcher : fieldCards(watcherIsP1))
				for (AutoAbility fa : mw.effectiveAutoAbilities(watcher))
					if (fa.trigger().equals("enters either player's field")
							&& matchesEntersFieldSubject(fa.triggerCard(), enteringCard, watcher))
						executeAutoAbility(fa, watcher, watcherIsP1);
	}

	private void fireEntersYourFieldWatcher(CardData watcher, CardData enteringCard, boolean enteringIsP1) {
		for (AutoAbility fa : mw.effectiveAutoAbilities(watcher)) {
			if (!fa.trigger().equals("enters your field")) continue;
			if (!matchesEntersFieldSubject(fa.triggerCard(), enteringCard, watcher)) continue;
			// "that Forward gains +4000 power until the end of the turn" (8-097H Jake) names no
			// target of its own — it means the card that just arrived. Run it inline with that card
			// preloaded, exactly as the "enters opponent's field" watchers already do for their own
			// pronoun forms; everything else keeps the normal stack path.
			if (ActionResolver.isTriggeredTargetAction(fa.effectText())) {
				runWithEnteringCardAsTarget(fa, watcher, enteringIsP1,
						enteringCardTarget(enteringCard, enteringIsP1));
				continue;
			}
			// The arriving card stands as the trigger's subject for the whole resolution, so an
			// effect can name it alongside a target of its own — Noctis 18-139S.
			CardData previousEntered = mw.triggeringEnteredCard;
			mw.triggeringEnteredCard = enteringCard;
			try {
				executeAutoAbility(fa, watcher, enteringIsP1);
			} finally {
				mw.triggeringEnteredCard = previousEntered;
			}
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
			//
			// The "if your opponent doesn't pay 《N》, [action]" form (4-035R Cid Randell) is the
			// same shape and takes the same route: its action is applied to the preloaded target
			// unless the opponent buys it off.
			if (ActionResolver.isTriggeredTargetAction(fa.effectText())
					|| ActionResolver.isIfOppNotPayAction(fa.effectText())
					|| ActionResolver.isEnteredUnpaidDamage(fa.effectText())) {
				runWithEnteringCardAsTarget(fa, watcher, watcherIsP1, enteringTarget);
				continue;
			}
			// A watcher that points at the entering card in some other way is left dormant rather
			// than run on the stack, where it has no target to point at — unless an inline shape
			// runs it with the entering card standing behind "that Forward": the self-sacrifice
			// form, and 20-102L Mira's "pay 《1》 and discard 1 Monster. When you do so, break that
			// Forward."
			String shape = AutoAbilityTriggers.inlineShapeOf(fa.effectText());
			if (REFERS_TO_ENTERING_CARD.matcher(fa.effectText()).find()
					&& !"PutSelfIntoBzIfDoSo".equals(shape) && !"PayAndDiscardWhenDoSo".equals(shape)) {
				mw.logEntry("[AutoAbility] " + watcher.name()
						+ " — not wired to act on the entering card; skipped");
				continue;
			}
			executeWithEnteredCard(fa, watcher, watcherIsP1, enteringCard);
		}
	}

	/**
	 * Runs {@code fa} with {@code enteringCard} standing as the card whose arrival fired it, as
	 * {@link #fireEntersYourFieldWatcher} does. The self-sacrifice payoffs name it as "it" or "that
	 * Forward" — see {@link #readSelfSacrificePayoff}.
	 */
	private void executeWithEnteredCard(AutoAbility fa, CardData watcher, boolean watcherIsP1,
			CardData enteringCard) {
		CardData previousEntered = mw.triggeringEnteredCard;
		mw.triggeringEnteredCard = enteringCard;
		try {
			executeAutoAbility(fa, watcher, watcherIsP1);
		} finally {
			mw.triggeringEnteredCard = previousEntered;
		}
	}

	/**
	 * A watcher sentence pointing at the card that just entered, rather than at something it
	 * chooses for itself. What such a sentence needs is the entering card preloaded as its target.
	 */
	private static final java.util.regex.Pattern REFERS_TO_ENTERING_CARD =
			java.util.regex.Pattern.compile("(?i)\\bthat\\s+(?:Forward|Character|Backup|Monster)\\b");

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


	// =========================================================================================
	// Trigger-subject matching
	// =========================================================================================
	/**
	 * A subject ending in a cost or power bound: "… of cost 4 or less", "… of cost 1", "… with 8000
	 * power or less", "… of power 9000 or more". Group {@code base} is the rest of the subject.
	 */
	private static final Pattern SUBJECT_COST_OR_POWER = Pattern.compile(
			"(?i)^(?<base>.+?)\\s+(?:of\\s+cost\\s+(?<cost>\\d+)"
			+ "|with\\s+(?<power>\\d+)\\s+power|of\\s+power\\s+(?<power2>\\d+))"
			+ "(?:\\s+or\\s+(?<cmp>less|more))?$");

	/** "a Forward of your opponent" — group 1 is the subject without the side. */
	private static final Pattern SUBJECT_OF_YOUR_OPPONENT = Pattern.compile(
			"(?i)^(.+?)\\s+of\\s+your\\s+opponent$");

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
	/**
	 * "a Wind or Earth Forward other than Noctis" (18-139S) — an Element alternation sharing one
	 * type and one tail. Split on "or" as written, it came apart into "a Wind", which names no type
	 * and matched nothing, and "Earth Forward …": a Wind Forward never fired the trigger.
	 */
	private static final Pattern SUBJECT_ELEMENT_ALTERNATION = Pattern.compile(
			"(?i)^(?:an?\\s+)?(?<elems>(?:" + String.join("|", Elements.ALL) + ")"
			+ "(?:\\s*,\\s*|\\s+or\\s+)(?:(?:" + String.join("|", Elements.ALL) + ")(?:\\s*,\\s*|\\s+or\\s+))*"
			+ "(?:" + String.join("|", Elements.ALL) + "))\\s+(?<rest>(?:Forward|Backup|Character|Monster)s?\\b.*)$");

	private boolean matchesEntersFieldSubject(String subject, CardData enteringCard, CardData self) {
		if (subject == null || subject.isBlank()) return false;
		Matcher alt = SUBJECT_ELEMENT_ALTERNATION.matcher(subject.trim());
		if (alt.matches()) {
			for (String elem : alt.group("elems").split("(?i)\\s*,\\s*(?:or\\s+)?|\\s+or\\s+"))
				if (matchesSingleSubject("a " + elem.trim() + " " + alt.group("rest").trim(), enteringCard, self))
					return true;
			return false;
		}
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
			for (String e : Elements.ALL)
				if (e.equalsIgnoreCase(part.trim())) { name = e; break; }
			if (name == null) return null;
			out.add(name);
		}
		return out;
	}

	private boolean matchesSingleSubject(String subject, CardData enteringCard, CardData self) {
		if (subject.isEmpty()) return false;
		// A cost or power qualifier on any other subject — "a Forward of your opponent with 8000
		// power or less" (5-008R Grenade), "a Character of cost 6 or more" (7-070R), eight printings.
		// Without it the whole phrase fell through to the card-name match below and never fired.
		Matcher qualM = SUBJECT_COST_OR_POWER.matcher(subject);
		if (qualM.matches()) {
			if (!matchesSingleSubject(qualM.group("base").trim(), enteringCard, self)) return false;
			boolean orLess = "less".equalsIgnoreCase(qualM.group("cmp"));
			boolean exact  = qualM.group("cmp") == null;
			int value, bound;
			if (qualM.group("cost") != null) {
				value = enteringCard.cost();
				bound = Integer.parseInt(qualM.group("cost"));
			} else {
				boolean side = Boolean.TRUE.equals(mw.gameState.getIdentity().get(enteringCard));
				ForwardTarget at = enteringCardTarget(enteringCard, side);
				value = at != null ? mw.fieldForwardPower(side, at.zone(), at.idx()) : enteringCard.power();
				bound = Integer.parseInt(qualM.group("power") != null ? qualM.group("power") : qualM.group("power2"));
			}
			return exact ? value == bound : orLess ? value <= bound : value >= bound;
		}
		// "of your opponent" says only which side, which the trigger itself already says.
		Matcher sideM = SUBJECT_OF_YOUR_OPPONENT.matcher(subject);
		if (sideM.matches()) return matchesSingleSubject(sideM.group(1).trim(), enteringCard, self);
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
		// "a Card Name X Forward" — name and type (1-213S Tidus's "the Forward Card Name Yuna"). Tried
		// only when the name part names the card, so a card whose own name ends in a type word
		// still falls through to the plain name arm below.
		Matcher nameTypeM = java.util.regex.Pattern.compile(
				"(?i)^an?\\s+Card\\s+Name\\s+(?<name>.+?)\\s+(?<type>Forwards?|Backups?|Monsters?|Characters?)$").matcher(subject);
		if (nameTypeM.matches() && CardFilters.meetsCardNameFilter(enteringCard, nameTypeM.group("name").trim()))
			return meetsSubjectTypeFilter(enteringCard, nameTypeM.group("type"));
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
		Matcher em = CardData.ELEMENT_FORWARD_SUBJECT.matcher(triggerCard.trim());
		if (em.matches()) {
			if (!attacker.isForward()) return false;
			String exclude = em.group("exclude");
			if (exclude != null && CardFilters.meetsCardNameFilter(attacker, exclude.trim())) return false;
			for (String elem : em.group("elems").split("(?i)\\s+or\\s+"))
				if (attacker.containsElement(elem.trim())) return true;
			return false;
		}
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


	// =========================================================================================
	// Attack, block and party triggers
	// =========================================================================================
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

	/** "this Forward" and friends — a self-reference spelled without the card's name. */
	private static final Pattern DAMAGE_TO_OPPONENT_SUBJECT_SELF =
			Pattern.compile("(?i)^this\\s+(?:forward|backup|monster|character)$");

	void triggerAutoAbilitiesForDealsDamageToOpponent(CardData attacker, boolean attackerIsP1) {
		withBatch(() -> {
			for (AutoAbility fa : mw.effectiveAutoAbilities(attacker)) {
				// The same self-reference triggerAutoAbilitiesForAttack accepts, and for the same
				// reason: a granted ability spells its subject "this Forward" rather than naming a
				// card, so the name test alone drops it. Ninja 27-104C hands out "When this Forward
				// deals damage to your opponent, draw 1 card."; without this the grant landed and
				// then sat inert. No further identity check is needed — effectiveAutoAbilities has
				// already scoped this list to the attacking card.
				if (!fa.triggerCard().equalsIgnoreCase(attacker.name())
						&& !DAMAGE_TO_OPPONENT_SUBJECT_SELF.matcher(fa.triggerCard().trim()).matches()) continue;
				if (fa.trigger().equals("deals damage to opponent")
						|| fa.trigger().equals("deals damage to opponent or forward"))
					executeAutoAbility(fa, attacker, attackerIsP1);
			}
		});
		mw.showStackWindowIfNeeded();
	}

	/**
	 * Fires "When [Self] deals damage to your opponent or to a Forward" (10-001H Ignacio, 7-013R
	 * Berserker, 7-018L Lann) for battle damage {@code dealer} has just dealt to a Forward — once
	 * per damage instance, broken or not. Not Breaktouch's "deals damage to forward", whose payoff
	 * acts on the damaged card and which {@code DamageResolver} resolves itself.
	 */
	void triggerAutoAbilitiesForDealsDamageToForward(CardData dealer, boolean dealerIsP1) {
		if (dealer == null) return;
		withBatch(() -> {
			for (AutoAbility fa : mw.effectiveAutoAbilities(dealer)) {
				if (!fa.trigger().equals("deals damage to opponent or forward")) continue;
				if (!fa.triggerCard().equalsIgnoreCase(dealer.name())
						&& !DAMAGE_TO_OPPONENT_SUBJECT_SELF.matcher(fa.triggerCard().trim()).matches()) continue;
				executeAutoAbility(fa, dealer, dealerIsP1);
			}
			// Watchers on the dealer's side — 20-014R Tifa's "a Category VII Character you control
			// deals damage to a Forward opponent controls". Every caller is battle damage to an
			// opposing Forward.
			for (CardData watcher : fieldCards(dealerIsP1))
				for (AutoAbility fa : mw.effectiveAutoAbilities(watcher)) {
					if (!fa.trigger().equals("watched character deals damage to forward")) continue;
					String subject = fa.triggerCard().replaceFirst("(?i)\\s+you\\s+control$", "").trim();
					if (matchesEntersFieldSubject(subject, dealer, watcher)) executeAutoAbility(fa, watcher, dealerIsP1);
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

	/**
	 * Breaktouch proper: "break it." and nothing else, as the whole effect of a "deals damage to a
	 * Forward" trigger — Tonberry 19-097C and its two reprints. 17-082R Lich prints it as "break
	 * that Forward.", the damaged Forward all the same.
	 *
	 * <p>The break used to be the <em>fallback</em> for that trigger, taken by anything the damaged-
	 * card path did not claim. Only three shapes are printed, and the third is Gulool Ja Ja 27-007H's
	 * choice — which was therefore breaking the Forward it damaged, a Breaktouch it does not have.
	 * Naming the effect is what confines the break to the cards that print it.
	 */
	static final Pattern FA_BREAKTOUCH_BREAK_IT =
			Pattern.compile("(?i)^break\\s+(?:it|that\\s+Forward)\\s*[.!]?$");

	/**
	 * "choose 1 Forward opponent controls other than that Forward. Deal it the same amount of
	 * damage." — Gulool Ja Ja 27-007H, the echo half of a "deals damage to a Forward" trigger.
	 *
	 * <p>Neither of the two things this sentence points at is in the text: "that Forward" is the
	 * card the trigger just damaged, and "the same amount" is how much it took. Both are facts of
	 * the event, so {@code DamageResolver} substitutes them and hands the result to the ordinary
	 * chain rather than a parser here reinventing the choice and the damage.
	 * Group: {@code head} — the selection with the pronoun exclusion still to be filled in.
	 */
	static final Pattern FA_DAMAGE_ECHO_TO_OTHER_FORWARD = Pattern.compile(
		"(?i)^(?<head>choose\\s+1\\s+Forward\\s+(?:your\\s+)?opponent\\s+controls)\\s+other\\s+than\\s+" +
		"that\\s+Forward[.,]\\s+Deal\\s+it\\s+the\\s+same\\s+amount\\s+of\\s+damage\\s*[.!]?$"
	);

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
				// "party attacks" contains "attack" too, but it belongs to triggerAutoAbilitiesForPartyAttack,
				// which checks the party's make-up. Firing it here as well skipped that check: 12-044R
				// Shikaree X dealt 3 twice when X, Y and Z attacked together, and Lenne 2-142R fired
				// when she attacked alone.
				if (fa.trigger().equals("party attacks")) continue;
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
					if (!icb.appliesToCard(card, mw.jobsStripped(card))) continue;
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
				// The combat half of Ifrit (XVI) 26-003R's compound trigger; its other half is
				// fired by triggerAutoAbilitiesForChosenByOpponentAbility.
				if (t.equals("is blocked") || t.equals("blocks or is blocked")
						|| t.equals("is blocked or chosen by opponent's ability"))
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

	/**
	 * Returns true when the party composition satisfies all filter fields of a "party attacks"
	 * ability. Package-private so the tests can put a composition to it directly — the alternative
	 * is standing up a whole attack, and the question here is about the filter, not about combat.
	 */
	boolean partyAttackMatchesFilter(AutoAbility fa, List<CardData> partyMembers) {
		// Every listed name has to be in the party, not just one of them: "forms a party with Card
		// Name Shikaree Y and Card Name Shikaree Z" (12-044R) names two partners, and the carrier's
		// own name is on the list too — the ability says the carrier forms the party, so a copy
		// sitting at home while its partners attack is not what triggered.
		for (String required : fa.partyCardNames()) {
			boolean found = partyMembers.stream().anyMatch(m -> meetsCardNameFilter(m, required));
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
		"(?i)\\s+(?<ctrl>you|opponent|either\\s+player)\\s+controls?$"
	);
	/** "Chocobo forming a party" — fires when the named card itself was in a party when broken. */
	private static final Pattern BZ_SUBJECT_SELF_PARTY = Pattern.compile(
		"(?i)^(?<name>.+?)\\s+forming\\s+a\\s+party$"
	);
	/** "a Forward forming a party with Bobby Corwen" — fires when another party member of the source card is broken. */
	private static final Pattern BZ_SUBJECT_PARTY_MEMBER = Pattern.compile(
		"(?i)^a\\s+Forward\\s+forming\\s+a\\s+party\\s+with\\s+(?<name>.+?)$"
	);


	// =========================================================================================
	// Break Zone and leaves-field triggers
	// =========================================================================================
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
			// "either player controls" (11-101H Meia) names no side.
			if (!ctrlM.group("ctrl").toLowerCase(Locale.ROOT).startsWith("either")) {
				boolean selfCtrl      = ctrlM.group("ctrl").equalsIgnoreCase("you");
				boolean brokenByOwner = (brokenIsP1 == abilityOwnerIsP1);
				if (selfCtrl != brokenByOwner) return false;
			}
			// Same "or" split as before, with "a Light or Dark Character" read as one Element list
			// over a shared type first — split as written it came apart into "a Light", nothing.
			return matchesEntersFieldSubject(subject.substring(0, ctrlM.start()), broken, source);
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
			// "… by your opponent's Summons or abilities" (11-065H Ardyn): the cause is whatever is
			// resolving as the card leaves; battle, costs and rules have none.
			Boolean causeSide = mw.resolvingEffectSide();
			boolean byOpponentsEffect = causeSide != null && causeSide != brokenIsP1;
			for (AutoAbility fa : mw.effectiveAutoAbilities(broken)) {
				if (fa.trigger().equals("put into break zone by opponent's effect")
						|| fa.trigger().equals("put into break zone any way by opponent's effect")) {
					if (byOpponentsEffect && subjectNamesItsOwnCard(fa, broken))
						executeAutoAbility(fa, broken, brokenIsP1);
					continue;
				}
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
			// Then the delayed ones an action ability left on this card for the turn. Removed as
			// they fire: "when … during this turn" answers the first time, and a card returned to
			// the field by its own trigger must not come back again from the same activation.
			// Both maps are asked because the registrant is whoever used the ability, which need
			// not be the side the card was on when it broke.
			for (boolean registrantIsP1 : new boolean[] { true, false }) {
				List<Consumer<GameContext>> pending = (registrantIsP1
						? mw.p1TempBreakZoneTriggers : mw.p2TempBreakZoneTriggers).remove(broken);
				if (pending == null) continue;
				GameContext ctx = mw.buildGameContext(registrantIsP1);
				for (Consumer<GameContext> effect : pending) effect.accept(ctx);
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
		// "this Forward" is a granted copy's name for its carrier — 14-029R Shivalry's grant.
		String damager = m.group("name").trim();
		if (!damager.matches("(?i)this\\s+(?:Forward|Character)")
				&& !CardFilters.meetsCardNameFilter(watcher, damager)) return false;
		if (!mw.wasDamagedBy(broken, watcher)) return false;

		// The half ahead of "damaged by" is an ordinary break-zone subject: an optional controller
		// clause over a type word.
		String subject = m.group("subject").trim();
		Matcher ctrlM = BZ_SUBJECT_CTRL.matcher(subject);
		if (ctrlM.find()) {
			boolean eitherPlayer = ctrlM.group("ctrl").toLowerCase(Locale.ROOT).startsWith("either");
			boolean selfCtrl = ctrlM.group("ctrl").equalsIgnoreCase("you");
			if (!eitherPlayer && selfCtrl != (brokenIsP1 == watcherIsP1)) return false;
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
				if (!fa.trigger().equals("leaves the field")
						&& !fa.trigger().equals("enters the field or leaves the field")) continue;
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
		// Garland / Lunafreya: what the departing card was granting "as long as [it] is on the
		// field" goes with it. Ahead of the refresh and the break sweep below, which is what
		// resolves a Forward the withdrawn power has left at or below its damage.
		mw.revokeWardenHeldGrantsOnLeave(departing);
		mw.gameState.clearCounters(departing);
		// Jack Garland 27-111L's named Job, on the same footing as the counters above: it is a
		// property of this copy's stay on the field, not of the card.
		mw.gameState.clearNamedJob(departing);
		mw.enteredFieldByAbilityOf.remove(departing);
		mw.enteredViaWarp.remove(departing);
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


	// =========================================================================================
	// Cast, chosen-by-opponent and search triggers
	// =========================================================================================
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
	 * Fires "When you cast a card removed from the game, …" — 29-008L Zidane, the corpus's only
	 * printing, and one whose other ability is what stocks the zone it watches.
	 *
	 * <p>Only the caster's own field is walked: the printing says "you", so the event and the
	 * ability watching it belong to the same player. Every route out of the removed-from-game zone
	 * goes through the borrowed-cast path, which is where this is called from — a card cast out of
	 * a Break Zone or off the top of a deck reaches the same code and is deliberately not this
	 * event, because the zone is what the trigger names.
	 */
	/**
	 * Fires "When you cast a/an &lt;filter&gt;, …" (10-078H Doga, 16-044L Wol, 27-014H Terra …) on the
	 * caster's own field, for each ability whose filter {@code cast} satisfies.
	 */
	void triggerAutoAbilitiesForFilteredCast(CardData cast, boolean casterIsP1) {
		withBatch(() -> {
			for (CardData c : fieldCards(casterIsP1)) {
				if (c == null) continue;
				for (AutoAbility fa : mw.effectiveAutoAbilities(c))
					if (fa.trigger().startsWith("you cast ")
							&& CardData.castFilterMatches(fa.trigger().substring("you cast ".length()), cast))
						executeAutoAbility(fa, c, casterIsP1);
			}
		});
	}

	void triggerAutoAbilitiesForCastRemovedCard(boolean casterIsP1) {
		withBatch(() -> collectEventTriggers("cast removed card", casterIsP1));
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
		triggerChosenByOpponentEvent(chosenSideIsP1, chosen, "chosen by opponent's summon");
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
		triggerChosenByOpponentEvent(chosenSideIsP1, chosen, "chosen by opponent's summon or ability");
	}

	/**
	 * Fires the ability-only chosen-by triggers on {@code chosenSideIsP1}'s side — called when that
	 * player's Character was selected by the opponent's action or auto ability, and <em>not</em>
	 * when a Summon selected it. A Summon's effect is not an ability, the line
	 * {@link DamageResolver#applyDamageModifierMatch} already draws for the damage shields worded
	 * the same way.
	 *
	 * <p>Two triggers, because Ifrit (XVI) 26-003R prints this event joined to being blocked and is
	 * carried as one compound trigger; this is the half of it that watches targeting.
	 *
	 * @param chosen the Characters actually selected, all on {@code chosenSideIsP1}'s side
	 */
	void triggerAutoAbilitiesForChosenByOpponentAbility(boolean chosenSideIsP1, List<CardData> chosen) {
		triggerChosenByOpponentEvent(chosenSideIsP1, chosen,
				"chosen by opponent's ability", "is blocked or chosen by opponent's ability");
	}

	/**
	 * Fires 3-088L Delita's "when [Self] is chosen by an ability of a Character your opponent
	 * controls" — the one chosen-by trigger that cares <em>which card</em> did the choosing, because
	 * its effect is "break that Character".
	 *
	 * <p>Kept apart from {@link #triggerAutoAbilitiesForChosenByOpponentAbility} rather than folded
	 * into it for that reason: the acting card has to be carried down and preloaded as the effect's
	 * target, the same way {@link #triggerAutoAbilitiesForSearch} carries the searching Character to
	 * 5-130R Tonberry's identical payoff. The broad watcher takes no such argument, and adding one
	 * there would oblige every caller of it to know something none of its printings ask about.
	 *
	 * <p>{@code actingCard} not being on {@code actingIsP1}'s field is how "a Character your opponent
	 * controls" is enforced: an ability resolving from hand, from the Break Zone or off a Summon has
	 * no Character on the board to break, so the trigger declines rather than breaking something else.
	 *
	 * @param chosen     the Characters the ability selected, all on {@code chosenSideIsP1}'s side
	 * @param actingCard the card whose ability made the selection, or {@code null} when none is known
	 * @param actingIsP1 which side {@code actingCard} is controlled by
	 */
	void triggerAutoAbilitiesForChosenByOpponentCharacterAbility(boolean chosenSideIsP1,
			List<CardData> chosen, CardData actingCard, boolean actingIsP1) {
		if (chosen.isEmpty() || actingCard == null) return;
		ForwardTarget actingTarget = findFieldTarget(actingCard, actingIsP1);
		if (actingTarget == null) return;
		withBatch(() -> {
			for (CardData watcher : fieldCards(chosenSideIsP1))
				for (AutoAbility fa : mw.effectiveAutoAbilities(watcher)) {
					if (!fa.trigger().equals("chosen by opponent's character ability")) continue;
					if (chosenSubjectMatch(fa.triggerCard(), watcher, chosen) == null) continue;
					runWithPreloadedTarget(fa, watcher, chosenSideIsP1, actingTarget);
				}
		});
		mw.showStackWindowIfNeeded();
	}

	/**
	 * Fires the chosen-by triggers that do not care whose effect did the choosing, on
	 * {@code chosenSideIsP1}'s side: "chosen by Summons or abilities" (17-120H Princess Sarah,
	 * 14-032R Proto fal'Cie Adam, 4-087R Delita, 21-076C Qun'mi, 22-068R Prishe), "chosen by a
	 * Summon" (2-017R Bergan) and "chosen by a Forward's ability" (13-079L Behemoth K, 26-066L
	 * Vincent), whose payoff acts on the Forward that chose — it is applied to that card directly.
	 *
	 * @param chosen     the Characters selected, all on {@code chosenSideIsP1}'s side
	 * @param bySummon   whether a Summon is what chose them
	 * @param actingCard the card whose ability chose them, or {@code null}
	 * @param actingIsP1 which side {@code actingCard} is controlled by
	 */
	void triggerAutoAbilitiesForChosenByAnyone(boolean chosenSideIsP1, List<CardData> chosen,
			boolean bySummon, CardData actingCard, boolean actingIsP1) {
		if (chosen.isEmpty()) return;
		if (bySummon) triggerChosenByOpponentEvent(chosenSideIsP1, chosen, "chosen by summon or ability", "chosen by summon");
		else          triggerChosenByOpponentEvent(chosenSideIsP1, chosen, "chosen by summon or ability");
		if (bySummon || actingCard == null || !actingCard.isForward()) return;
		ForwardTarget actingTarget = findFieldTarget(actingCard, actingIsP1);
		if (actingTarget == null) return;
		for (CardData watcher : fieldCards(chosenSideIsP1))
			for (AutoAbility fa : mw.effectiveAutoAbilities(watcher)) {
				if (!fa.trigger().equals("chosen by forward ability")) continue;
				if (chosenSubjectMatch(fa.triggerCard(), watcher, chosen) == null) continue;
				BiConsumer<GameContext, List<ForwardTarget>> payoff = chosenByForwardPayoff(fa.effectText());
				if (payoff == null) {
					mw.logEntry("[AutoAbility] Unrecognized effect: " + fa.effectText());
					continue;
				}
				mw.logEntry("[AutoAbility] " + watcher.name() + " — chosen by " + actingCard.name()
						+ "'s ability: " + fa.effectText());
				withAbilitySource(watcher, () -> {
					payoff.accept(mw.buildGameContext(chosenSideIsP1), List.of(actingTarget));
					return true;
				});
			}
		mw.showStackWindowIfNeeded();
	}

	/**
	 * The payoff of a "chosen by a Forward's ability" trigger, applied to the Forward that chose:
	 * "break that Forward" / "deal that Forward 9000 damage" read as a target action on "it", so
	 * Breaktouch's own sentence never becomes a triggered-target form. {@code null} when unread.
	 */
	static BiConsumer<GameContext, List<ForwardTarget>> chosenByForwardPayoff(String effectText) {
		String action = effectText.trim().replaceAll("[.!]+$", "")
				.replaceAll("(?i)\\bthat\\s+Forward\\b", "it");
		return ActionResolver.parseTargetAction(action, 0);
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
	private void triggerChosenByOpponentEvent(boolean isP1, List<CardData> chosen,
			String... triggerTypes) {
		if (chosen.isEmpty()) return;
		Set<String> types = Set.of(triggerTypes);
		// Held for the whole walk, batch drain included, so executeAutoAbilityImpl can tell that a
		// compound trigger is firing on its chosen-by half and must resolve inline — see the
		// inline-resolution guard there. withBatch dispatches what it collects before returning,
		// so the flag is still up when each ability actually runs.
		boolean outer = resolvingChosenBySelection;
		resolvingChosenBySelection = true;
		try {
			withBatch(() -> {
				List<CardData> fwds = new ArrayList<>(isP1 ? mw.p1ForwardCards : mw.p2ForwardCards);
				CardData[]     bkps = isP1 ? mw.p1BackupCards : mw.p2BackupCards;
				List<CardData> mons = new ArrayList<>(isP1 ? mw.p1MonsterCards : mw.p2MonsterCards);
				for (CardData c : fwds) fireChosenByOpponentTriggers(c, isP1, types, chosen);
				for (CardData c : bkps) if (c != null) fireChosenByOpponentTriggers(c, isP1, types, chosen);
				for (CardData c : mons) fireChosenByOpponentTriggers(c, isP1, types, chosen);
			});
		} finally {
			resolvingChosenBySelection = outer;
		}
		mw.showStackWindowIfNeeded();
	}

	private void fireChosenByOpponentTriggers(CardData watcher, boolean isP1, Set<String> triggerTypes,
			List<CardData> chosen) {
		for (AutoAbility fa : mw.effectiveAutoAbilities(watcher)) {
			if (!triggerTypes.contains(fa.trigger()) && !cheapChosenByMatches(fa.trigger(), triggerTypes)) continue;
			CardData subject = chosenSubjectMatch(fa.triggerCard(), watcher, chosen);
			if (subject == null) continue;
			// "you may return it to its owner's hand" (2-136R Porom) names no target of its own:
			// "it" is the card the opponent chose, which is the watcher on most printings and is
			// not on Porom's, who watches Palom as well. Resolved inline with that card preloaded,
			// the way this class already handles the other pronoun-only watcher effects.
			if (ActionResolver.isTriggeredTargetAction(fa.effectText())) {
				ForwardTarget t = findFieldTarget(subject, isP1);
				if (t == null) {
					mw.logEntry("[AutoAbility] " + watcher.name()
							+ " — the chosen card has left the field; skipped");
					continue;
				}
				runWithPreloadedTarget(fa, watcher, isP1, t);
				continue;
			}
			executeAutoAbility(fa, watcher, isP1);
		}
	}

	/** 10-098L Feolthanos's trigger name, as CardData builds it. */
	private static final Pattern CHOSEN_BY_CHEAP_TRIGGER =
			Pattern.compile("^chosen by opponent's summon or ability of cost (?<cap>\\d+) or less$");

	/**
	 * Whether a cost-capped chosen-by trigger (10-098L Feolthanos: "your opponent's Summon of cost 5
	 * or less or an ability of their Character of cost 5 or less") answers this walk: the walk is the
	 * uncapped "summon or ability" one, and what is choosing — the resolving Summon, or the Character
	 * whose ability it is — costs no more than the cap. An ability of anything but a Character (an
	 * EX Burst resolving off a Summon card) does not count.
	 */
	private boolean cheapChosenByMatches(String trigger, Set<String> triggerTypes) {
		Matcher m = CHOSEN_BY_CHEAP_TRIGGER.matcher(trigger);
		if (!m.matches() || !triggerTypes.contains("chosen by opponent's summon or ability")) return false;
		int cap = Integer.parseInt(m.group("cap"));
		CardData actor;
		if (mw.currentResolutionIsSummon) actor = mw.currentSummonSource;
		else {
			actor = mw.currentAbilitySource;
			if (actor != null && !(actor.isForward() || actor.isBackup() || actor.isMonster())) return false;
		}
		return actor != null && actor.cost() <= cap;
	}

	/**
	 * True while a chosen-by-opponent walk is running, including the batch drain that resolves what
	 * it collected. Read only by the inline-resolution guard in {@link #executeAutoAbilityImpl},
	 * which needs to know <em>which event</em> fired a trigger that watches two of them.
	 */
	private boolean resolvingChosenBySelection;

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
		return chosenSubjectMatch(subject, watcher, chosen) != null;
	}

	/**
	 * As {@link #matchesChosenSubject}, but returns <em>which</em> chosen card satisfied the
	 * subject rather than only that one did.
	 *
	 * <p>Split out for 2-136R Porom, whose effect is "you may return it to its owner's hand" — "it"
	 * is the card the opponent chose, and Porom's subject is a disjunction ("Porom or the Card Name
	 * Palom you control"), so the chosen card is not always the watcher. Answering with a boolean
	 * left nothing to return but the watcher, which would have bounced Porom when Palom was
	 * targeted.
	 */
	private CardData chosenSubjectMatch(String subject, CardData watcher, List<CardData> chosen) {
		// A subject-less printing can only be about the watcher, so fall back to identity rather
		// than to firing unconditionally — the latter is the bug this method exists to prevent.
		if (subject == null || subject.isBlank())
			return chosen.stream().filter(c -> c == watcher).findFirst().orElse(null);

		String stripped = CHOSEN_SUBJECT_COUNT.matcher(subject.trim()).replaceFirst("");
		for (String rawPart : stripped.split("(?i)\\s+or\\s+")) {
			String part = TRIGGER_SUBJECT_CTRL.matcher(rawPart.trim()).replaceFirst("").trim();
			// "the Card Name Palom" — normalise the article so the shared subject matcher, which
			// expects "a"/"an", recognises it.
			part = part.replaceAll("(?i)^the\\s+", "a ");
			if (part.isEmpty()) continue;
			if (CHOSEN_SUBJECT_SELF.matcher(part).matches()
					|| CardFilters.meetsCardNameFilter(watcher, part)) {
				if (chosen.stream().anyMatch(c -> c == watcher)) return watcher;
				continue;
			}
			for (CardData c : chosen)
				if (matchesSingleSubject(part, c, watcher)) return c;
		}
		return null;
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
		// The offer, for the printings that print one — 2-136R Porom's "you may return it to its
		// owner's hand". The stack path makes it for every other optional auto-ability; this one
		// resolves inline, so it has to make it here or an optional effect would be compulsory.
		if (!acceptsOptional(fa, watcherIsP1, watcher.name() + " — " + optionalPrompt(fa, fa.effectText()),
				() -> aiAccepts("optional ability"))) {
			mw.logEntry("[AutoAbility] " + watcher.name() + " — optional effect declined");
			return;
		}
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

	/**
	 * Puts an auto ability's "you may" or "your opponent may" to the player it names, and reports
	 * whether they took it up; {@code true} outright when the ability offers no choice.
	 *
	 * <p>The offer used to go to the local player whenever it was theirs and be accepted for anyone
	 * else — right against the AI, wrong against a remote human, whose client asked them while this
	 * one assumed a yes. One declined offer was enough to part the two boards.
	 *
	 * @param controllerIsP1 the ability's controller; "your opponent may" asks the other seat
	 * @param question       the whole question, as the dialog shows it
	 * @param cpuAnswer      the AI's answer
	 */
	private boolean acceptsOptional(AutoAbility fa, boolean controllerIsP1, String question,
			BooleanSupplier cpuAnswer) {
		return acceptsOptional(fa, controllerIsP1, question, "OK", "Decline", cpuAnswer);
	}

	/** As above, with the buttons worded for the offer — "Pay" and "Decline". */
	private boolean acceptsOptional(AutoAbility fa, boolean controllerIsP1, String question,
			String yes, String no, BooleanSupplier cpuAnswer) {
		if (!fa.youMay() && !fa.opponentMay()) return true;
		boolean chooserIsP1 = fa.youMay() ? controllerIsP1 : !controllerIsP1;
		return mw.decideYesNo(chooserIsP1, "Waiting for your opponent to decide: " + question,
				() -> mw.showEffectOptionDialog(question, "Auto Ability", new Object[]{yes, no}) == 0,
				cpuAnswer);
	}

	/** "You may: …" or "Your opponent may: …", as the offer dialogs word it. */
	private static String optionalPrompt(AutoAbility fa, String what) {
		return (fa.youMay() ? "You may: " : "Your opponent may: ") + what;
	}

	/** The AI's usual answer to an offer: it takes it, and says so. */
	private boolean aiAccepts(String what) {
		mw.logEntry("[AutoAbility] [AI] auto-accepts " + what);
		return true;
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


	// =========================================================================================
	// Phase, damage and end-of-turn triggers
	// =========================================================================================
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
		for (String label : labels) {
			triggerAutoAbilitiesForEvent(label, causerIsP1);
			// The "1 or more" printings: once per resolution per label, however many cards go.
			String once = label + " (1 or more)";
			Map<String, Integer> fired = oneOrMoreDiscardSerial.computeIfAbsent(causerIsP1, k -> new HashMap<>());
			if (!Integer.valueOf(mw.resolutionSerial).equals(fired.get(once))) {
				fired.put(once, mw.resolutionSerial);
				triggerAutoAbilitiesForEvent(once, causerIsP1);
			}
		}
	}

	/** The resolution each "1 or more" discard label last fired in, by the causing side. */
	private final Map<Boolean, Map<String, Integer>> oneOrMoreDiscardSerial = new HashMap<>();

	/**
	 * Fires "When you discard 1 or more cards due to Summons or abilities" (16-114C White Mage) on
	 * {@code discarderIsP1}'s own field. The caller fires it once per effect, not per card.
	 */
	void triggerAutoAbilitiesForOwnDiscardByEffect(boolean discarderIsP1, boolean byAbility) {
		withBatch(() -> {
			collectEventTriggers("you discard by effect", discarderIsP1);
			// 29-040H Adelle: "due to an ability" — not a Summon.
			if (byAbility) collectEventTriggers("you discard by ability", discarderIsP1);
		});
		mw.showStackWindowIfNeeded();
	}

	/**
	 * Fires the draw watchers for {@code count} cards {@code drawerIsP1} has just drawn, once per
	 * card: "When you draw a card" (15-063C Romaa Mihgo) on the drawer's field, and "When your
	 * opponent draws a card outside of his/her Draw Phase" (5-036L The Emperor) on the other side,
	 * except while the Draw Phase is running.
	 */
	void triggerAutoAbilitiesForDraw(boolean drawerIsP1, int count) {
		if (count <= 0) return;
		boolean inDrawPhase = mw.gameState.getCurrentPhase() == GameState.GamePhase.DRAW;
		withBatch(() -> {
			for (int i = 0; i < count; i++) {
				collectEventTriggers("you draw a card", drawerIsP1);
				if (!inDrawPhase) collectEventTriggers("opponent draws outside draw phase", !drawerIsP1);
			}
		});
		mw.showStackWindowIfNeeded();
	}

	/**
	 * Fires a card's own "When [Self] in any zone is removed from the game" (28-115L Lightning).
	 * Registered on {@link GameState#setRemovedFromGameListener}, so every removal reaches it —
	 * from the field, the Break Zone, the hand or the deck. The card resolves from where it now is.
	 */
	void triggerAutoAbilitiesForRemovedFromGame(CardData card) {
		Boolean ownerIsP1 = mw.gameState.getIdentity().get(card);
		if (ownerIsP1 == null) return;
		fireOwnRemovalTriggers(card, ownerIsP1, "removed from game");
	}

	/**
	 * Fires a Forward's own "When [Self] on the field is removed from the game" (29-063R Exdeath),
	 * from the field exit that files it there ({@code MainWindow.removeP1ForwardToRfg}).
	 */
	void triggerAutoAbilitiesForRemovedFromField(CardData card, boolean controllerIsP1) {
		fireOwnRemovalTriggers(card, controllerIsP1, "removed from game from field");
	}

	/**
	 * Fires a Forward's own "When [Self] is put from the field into its owner's deck" (16-116L
	 * Tidus), from the four field → deck moves. It resolves from the deck, under its owner.
	 */
	void triggerAutoAbilitiesForPutIntoDeck(CardData card) {
		Boolean ownerIsP1 = mw.gameState.getIdentity().get(card);
		if (ownerIsP1 == null) return;
		fireOwnRemovalTriggers(card, ownerIsP1, "put from field into deck");
	}

	/**
	 * The hand and deck routes of 12-074H Argy's "put into the Break Zone in any situation by your
	 * opponent's Summons or abilities" — called by the discard and the mill for each card they move
	 * while an effect of the owner's opponent is resolving. The field route is the break-zone dispatch.
	 */
	void triggerAutoAbilitiesForPutIntoBzByOpponent(CardData card, boolean ownerIsP1) {
		fireOwnRemovalTriggers(card, ownerIsP1, "put into break zone any way by opponent's effect");
	}

	/**
	 * 29-043R Aerith's "When a Category VII Character is put from your hand or your deck into the
	 * Break Zone due to Summons or abilities, add it to your hand" — watchers on the owner's field,
	 * called by the discard and the mill for each card they move while any Summon or ability is
	 * resolving. The card travels as the trigger card, which "add it to your hand" returns by identity.
	 */
	void triggerAutoAbilitiesForOwnCardToBzByEffect(CardData card, boolean ownerIsP1) {
		withBatch(() -> {
			for (CardData watcher : fieldCards(ownerIsP1))
				for (AutoAbility fa : mw.effectiveAutoAbilities(watcher))
					if (fa.trigger().equals("own card to break zone from hand or deck by effect")
							&& matchesEntersFieldSubject(fa.triggerCard(), card, watcher))
						executeAutoAbility(fa, watcher, ownerIsP1, false, card);
		});
		mw.showStackWindowIfNeeded();
	}

	/**
	 * 23-029R Zenos's "When a card in your opponent's Break Zone leaves the Break Zone, your
	 * opponent discards 1 card." Registered on {@link GameState#setBreakZoneLeftListener}, so every
	 * departure reaches it — a cast, a cost, a recovery to hand or field, a removal from the game.
	 * The watchers are on the field of the Break Zone owner's opponent.
	 */
	void triggerAutoAbilitiesForBreakZoneLeft(CardData card, boolean zoneIsP1) {
		boolean watcherIsP1 = !zoneIsP1;
		withBatch(() -> {
			for (CardData watcher : fieldCards(watcherIsP1))
				for (AutoAbility fa : mw.effectiveAutoAbilities(watcher))
					if (fa.trigger().equals("opponent card leaves break zone"))
						executeAutoAbility(fa, watcher, watcherIsP1, false, card);
		});
		mw.showStackWindowIfNeeded();
	}

	/**
	 * 2-041H Doctor Cid's "When a Backup you control is broken by your opponent's Summon or ability,
	 * your opponent puts 1 Character from his field into the Break Zone." Called by the paths that
	 * break a Backup — {@code breakTarget}, the mass break, damage to a Backup acting as a Forward —
	 * and not by the put-into-the-Break-Zone ones, which are not a break. Fires only while the
	 * opponent's Summon or ability is resolving: a battle or a cost has no effect side.
	 *
	 * <p>The broken card watches too, from the Break Zone: a Doctor Cid broken this way is itself "a
	 * Backup you control" as it leaves.
	 */
	void triggerAutoAbilitiesForBrokenByOpponent(CardData broken, boolean controllerIsP1) {
		Boolean effectSide = mw.resolvingEffectSide();
		if (effectSide == null || effectSide == controllerIsP1) return;
		List<CardData> watchers = new ArrayList<>(fieldCards(controllerIsP1));
		watchers.add(broken);
		withBatch(() -> {
			for (CardData watcher : watchers)
				for (AutoAbility fa : mw.effectiveAutoAbilities(watcher))
					if (fa.trigger().equals("broken by opponent's effect") && matchesEntersFieldSubject(
							fa.triggerCard().replaceFirst("(?i)\\s+you\\s+control$", "").trim(), broken, watcher))
						executeAutoAbility(fa, watcher, controllerIsP1, false, broken);
		});
		mw.showStackWindowIfNeeded();
	}

	private void fireOwnRemovalTriggers(CardData card, boolean isP1, String trigger) {
		withBatch(() -> {
			for (AutoAbility fa : mw.effectiveAutoAbilities(card))
				if (fa.trigger().equals(trigger) && meetsCardNameFilter(card, fa.triggerCard()))
					executeAutoAbility(fa, card, isP1);
		});
		mw.showStackWindowIfNeeded();
	}

	/**
	 * Fires a card's own "When [Self] is added to your hand from the Break Zone" (14-079R Aphmau,
	 * 9-091H Nero (XIV)), "… from the deck due to a search effect" (16-140S Sin), or both (27-059C
	 * Galuf). The card is in its owner's hand and resolves from there.
	 *
	 * @param fromBreakZone the Break Zone; otherwise a search took it from the deck
	 */
	void triggerAutoAbilitiesForAddedToHand(CardData card, boolean ownerIsP1, boolean fromBreakZone) {
		withBatch(() -> {
			for (AutoAbility fa : mw.effectiveAutoAbilities(card)) {
				boolean fires = switch (fa.trigger()) {
					case "added to hand from break zone"           -> fromBreakZone;
					case "added to hand by search"                 -> !fromBreakZone;
					case "added to hand from break zone or search" -> true;
					default                                        -> false;
				};
				if (fires && meetsCardNameFilter(card, fa.triggerCard())) executeAutoAbility(fa, card, ownerIsP1);
			}
		});
		mw.showStackWindowIfNeeded();
	}

	/**
	 * Fires the triggers on one card put from {@code ownerIsP1}'s deck into the Break Zone: its own
	 * "When [Self] is put from the deck into the Break Zone" (22-084R Fujin, 22-087R Raijin),
	 * resolved from the Break Zone, and the opponent's "When a card is put from your opponent's deck
	 * into the Break Zone" watchers (10-050C Thief). Once per card.
	 */
	void triggerAutoAbilitiesForMilled(CardData milled, boolean ownerIsP1) {
		withBatch(() -> {
			for (AutoAbility fa : mw.effectiveAutoAbilities(milled))
				if (fa.trigger().equals("milled") && meetsCardNameFilter(milled, fa.triggerCard()))
					executeAutoAbility(fa, milled, ownerIsP1);
			collectEventTriggers("opponent card milled", !ownerIsP1);
		});
		mw.showStackWindowIfNeeded();
	}

	/**
	 * Fires the discarded card's own "When [Self] is discarded from your hand due to …" — 16-027C /
	 * 16-007R / 16-088L Black Waltz 1-3 ("an ability") and 19-039R Emerald Weapon ("your
	 * opponent's Summons or abilities"). The card is in its owner's Break Zone by now and resolves
	 * from there.
	 *
	 * @param byAbility    whether an ability (not a Summon) is what made it be discarded
	 * @param byOpponent   whether that Summon or ability is the owner's opponent's
	 */
	void triggerAutoAbilitiesForSelfDiscarded(CardData discarded, boolean ownerIsP1,
			boolean byAbility, boolean byOpponent) {
		withBatch(() -> {
			for (AutoAbility fa : mw.effectiveAutoAbilities(discarded)) {
				boolean fires = fa.trigger().equals("discarded by ability") ? byAbility
						: fa.trigger().equals("discarded by opponent's effect") && byOpponent;
				if (!fires || !meetsCardNameFilter(discarded, fa.triggerCard())) continue;
				executeAutoAbility(fa, discarded, ownerIsP1);
			}
		});
		mw.showStackWindowIfNeeded();
	}

	/**
	 * Fires the watchers of an opposing card returning from the field to its owner's hand, on the
	 * side opposite the one it was on: "a Forward opponent controls returns / is returned …"
	 * (7-111R Geosgaeno, 16-117H Tros, 21-133S / 27-121R) for a Forward, and "a Character opponent
	 * controls is returned …" (24-095C Jecht, 24-101C Tidus) for any Character.
	 */
	void triggerAutoAbilitiesForCharacterReturnedToHand(boolean returnedFromP1Field, boolean wasForward) {
		withBatch(() -> {
			if (wasForward) collectEventTriggers("opponent forward returns to hand", !returnedFromP1Field);
			collectEventTriggers("opponent character returns to hand", !returnedFromP1Field);
			// "When a Character is returned …" with no side named — 14-042L Bismarck, 14-102L Leviathan.
			collectEventTriggers("character returns to hand", true);
			collectEventTriggers("character returns to hand", false);
		});
		mw.showStackWindowIfNeeded();
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
		fireIsDealtDamageTriggers(damaged, damagedIsP1, 0);
	}

	/**
	 * @param amount the size of this one damage instance, for the effects whose text names it
	 *     rather than a number — Shantotto 4-083L's "deal the same amount of damage". Held on
	 *     {@link MainWindow#lastDealtDamageAmount} for the length of the dispatch, and restored
	 *     afterwards so a damage dealt inside one of these triggers cannot overwrite the amount the
	 *     outer one is still reading.
	 */
	void fireIsDealtDamageTriggers(CardData damaged, boolean damagedIsP1, int amount) {
		fireIsDealtDamageTriggers(damaged, damagedIsP1, amount, null);
	}

	/**
	 * Who dealt one instance of damage, for the triggers that say — "is dealt damage by a Forward
	 * opponent controls" (15-077H Dadaluma), "by a Character" (5-037R Zeid), "by your opponent's
	 * Summons or abilities" (21-073R Zazarg).
	 *
	 * @param card     the dealing card: the other combatant in battle, else the Summon or the card
	 *     whose ability is resolving
	 * @param isP1     which side controls {@code card}
	 * @param byEffect whether a Summon or ability dealt it, rather than battle
	 */
	record DamageDealer(CardData card, boolean isP1, boolean byEffect) {}

	/**
	 * @param dealer who dealt the damage, or {@code null} when unknown — a trigger that names its
	 *     dealer then declines rather than guessing.
	 */
	void fireIsDealtDamageTriggers(CardData damaged, boolean damagedIsP1, int amount, DamageDealer dealer) {
		if (damaged == null) return;
		int previousAmount = mw.lastDealtDamageAmount;
		mw.lastDealtDamageAmount = amount;
		try {
			// Batched: one damage can meet the trigger on more than one card — the Forward's own
			// printing and a Faris watching it — and those are simultaneous, so their controller picks
			// the order they go on the stack.
			withBatch(() -> {
				for (CardData watcher : fieldCards(damagedIsP1))
					fireIsDealtDamageTriggers(watcher, damagedIsP1, damaged, amount, dealer);
			});
		} finally {
			mw.lastDealtDamageAmount = previousAmount;
		}
		mw.showStackWindowIfNeeded();
	}

	/**
	 * The qualifiers CardData carries on an "is dealt damage" subject: a size floor ("Baigan 4000
	 * damage or more") and the dealer clause ("Zeid by a character opponent controls").
	 */
	private static final Pattern DEALT_DAMAGE_QUALIFIER = Pattern.compile(
			"(?i)^(?<subject>.*?)(?:\\s+(?<min>\\d+)\\s+damage\\s+or\\s+more)?(?:\\s+by\\s+(?<by>.+))?$");

	private void fireIsDealtDamageTriggers(CardData watcher, boolean watcherIsP1, CardData damaged,
			int amount, DamageDealer dealer) {
		for (AutoAbility fa : mw.effectiveAutoAbilities(watcher)) {
			if (!fa.trigger().equals("is dealt damage")) continue;
			Matcher q = DEALT_DAMAGE_QUALIFIER.matcher(fa.triggerCard() == null ? "" : fa.triggerCard().trim());
			if (!q.matches()) continue;
			if (!matchesDamagedSubject(q.group("subject"), watcher, damaged)) continue;
			if (q.group("min") != null && amount < Integer.parseInt(q.group("min"))) continue;
			if (q.group("by") != null && !dealerMatches(q.group("by"), dealer, watcherIsP1)) continue;
			if (paysOffAtDealer(fa)) {
				executeAutoAbility(atDealer(fa, dealer, watcherIsP1), watcher, watcherIsP1, false, dealer.card());
				continue;
			}
			executeAutoAbility(fa, watcher, watcherIsP1);
		}
	}

	/**
	 * Whether {@code dealer} satisfies a trigger's "by …" clause. "Opponent" is relative to the
	 * watcher, which is on the damaged side. A Forward's or Character's ability counts as dealt by
	 * that card, the same reading {@link MainWindow#recordDamagedBy} takes; a Summon is neither.
	 */
	private static boolean dealerMatches(String by, DamageDealer dealer, boolean watcherIsP1) {
		if (dealer == null || dealer.card() == null) return false;
		String b = by.toLowerCase(Locale.ROOT);
		if (b.contains("opponent") && dealer.isP1() == watcherIsP1) return false;
		if (b.contains("summons or abilities")) return dealer.byEffect();
		CardData c = dealer.card();
		if (b.contains("forward")) return c.isForward();
		return c.isForward() || c.isBackup() || c.isMonster();
	}

	/** A payoff that acts on the card that dealt the damage — "deal that Forward 5000 damage". */
	private static final Pattern REFERS_TO_DEALER = Pattern.compile(
			"(?i)\\bthat\\s+(?:Forward|Character)(?:'s)?\\b");

	/**
	 * A dealt-damage payoff about the dealer, in the form the Stack resolves: 26-083H Elena's "deal
	 * that Forward 5000 damage", 17-082R Lich's "break that Forward", 5-037R Zeid's "that
	 * Character's controller discards 1 card from his/her hand". The dealer travels as the entry's
	 * trigger card and is preloaded as the target when the ability goes on the Stack (see
	 * {@link #dealerTarget}); the controller form needs only the side, so it is settled here.
	 *
	 * <p>"break that Forward" is Breaktouch's wording, which must not become a triggered-target
	 * form, so it goes on as "break that Character" — the same card, as 20-102L Mira's does.
	 */
	private static AutoAbility atDealer(AutoAbility fa, DamageDealer dealer, boolean watcherIsP1) {
		String text = fa.effectText().trim();
		Matcher controller = DEALER_CONTROLLER_DISCARDS.matcher(text);
		if (controller.matches())
			return fa.withEffectText(dealer.isP1() == watcherIsP1
					? "discard " + controller.group("count") + " from your hand."
					: "your opponent discards " + controller.group("count") + " from his/her hand.");
		return fa.withEffectText(text.replaceAll("(?i)\\bbreak\\s+that\\s+Forward\\b", "break that Character"));
	}

	/**
	 * The preloaded target of an "is dealt damage by …" payoff about the dealer — {@code dealer}
	 * where it stands now, or {@code null} when it is off the field (or never stood there — a
	 * Summon), in which case the payoff finds no target and does nothing.
	 */
	private List<ForwardTarget> dealerTarget(CardData dealer) {
		Boolean side = mw.fieldSideOf(dealer);
		ForwardTarget t = side == null ? null : findFieldTarget(dealer, side);
		return t == null ? null : List.of(t);
	}

	/** "that Character's controller discards 1 card from his/her hand." — 5-037R Zeid. */
	private static final Pattern DEALER_CONTROLLER_DISCARDS = Pattern.compile(
			"(?i)^that\\s+(?:Forward|Character)'s\\s+controller\\s+discards\\s+(?<count>\\d+\\s+cards?)\\s+from\\s+"
			+ "(?:his/her|his|her|their)\\s+hand[.!]?$");

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
	 * The qualifying clause this dispatch has already settled: "you control", since the walk only
	 * visits the damaged card's own side. (A "by …" dealer clause is peeled off earlier, by
	 * {@link #DEALT_DAMAGE_QUALIFIER}.)
	 *
	 * <p>Deliberately not "opponent controls": the walk cannot satisfy that, so such a subject is
	 * left intact and declines on the filter below rather than being read as its own side.
	 */
	private static final Pattern DAMAGED_SUBJECT_TAIL =
			Pattern.compile("(?i)\\s+you\\s+control$");

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
	 * Fires "When you receive a fifth point of damage" — 17-019R Marilith, 17-054R Tiamat, 17-082R
	 * Lich, 17-112R Kraken. Called as a point of damage takes {@code isP1}'s Damage Zone to 5.
	 *
	 * <p>Every printing says "This effect will trigger only if [Self] is in the Break Zone", so only
	 * the Break Zone is walked, and only abilities carrying that condition.
	 */
	void triggerAutoAbilitiesForFifthDamage(boolean isP1) {
		withBatch(() -> {
			for (CardData c : new ArrayList<>(isP1 ? mw.gameState.getP1BreakZone() : mw.gameState.getP2BreakZone()))
				for (AutoAbility fa : mw.effectiveAutoAbilities(c))
					if (fa.trigger().equals("you receive fifth damage") && !fa.bzConditionCard().isEmpty())
						executeAutoAbility(fa, c, isP1);
		});
		mw.showStackWindowIfNeeded();
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


	// =========================================================================================
	// Becomes-dull, EX Burst and Warp triggers
	// =========================================================================================
	/**
	 * Fires "becomes dull" auto abilities on {@code card} (owned by {@code isP1}) after it
	 * transitions from ACTIVE to DULL.  Only abilities whose {@code triggerCard} matches the
	 * card's name are executed.
	 */
	void triggerAutoAbilitiesForBecomesDull(CardData card, boolean isP1) {
		withBatch(() -> collectBecomesDullTriggers(card, isP1));
		mw.showStackWindowIfNeeded();
	}

	/**
	 * The self-naming half of the dull event, split out of
	 * {@link #triggerAutoAbilitiesForBecomesDull} so
	 * {@link #triggerAutoAbilitiesForBecomesDullByEffect} can gather it and the watcher half inside
	 * a single {@link #withBatch} — they are simultaneous triggers on one event, and two batches
	 * would ask their controller to order them in two separate dialogs.
	 */
	private void collectBecomesDullTriggers(CardData card, boolean isP1) {
		for (AutoAbility fa : mw.effectiveAutoAbilities(card)) {
			if (!fa.trigger().equals("becomes dull")) continue;
			if (!fa.triggerCard().equalsIgnoreCase(card.name())) continue;
			executeAutoAbility(fa, card, isP1);
		}
	}

	/**
	 * The dull event as caused by a Summon or an ability, rather than by an attack declaration or a
	 * 《Dull》 cost payment. Fires the self-naming "becomes dull" abilities on {@code dulled} exactly
	 * as {@link #triggerAutoAbilitiesForBecomesDull} does, and additionally the watcher printings —
	 * "When an active Forward opponent controls becomes dull due to your Summon or ability, …"
	 * (PR-156 Zack) — which live on the causing player's field and react to some other card dulling.
	 *
	 * <p>Only the two {@code GameContext} dull primitives call this. The other ten call sites are
	 * attack declarations, 《Dull》 costs and damage-negation costs, and a cost is not an ability
	 * effect, so the watcher must not see them. Mass sweeps need no separate wiring: the Forward
	 * arms of {@code applyMassFieldEffect} route through those same two primitives.
	 *
	 * <p>The "active" in the printed subject needs no check of its own — both primitives return
	 * early on a card that is already dull, so only an ACTIVE→DULL transition reaches this.
	 *
	 * @param dulledIsP1 the side the dulled Forward is on
	 * @param causerIsP1 the side whose Summon or ability caused it, and so whose field is walked
	 *     for watchers — "due to <em>your</em> Summon or ability"
	 */
	void triggerAutoAbilitiesForBecomesDullByEffect(CardData dulled, boolean dulledIsP1, boolean causerIsP1) {
		if (dulled == null) return;
		ForwardTarget dulledTarget = findFieldTarget(dulled, dulledIsP1);
		withBatch(() -> {
			collectBecomesDullTriggers(dulled, dulledIsP1);
			for (CardData watcher : fieldCards(causerIsP1))
				for (AutoAbility fa : mw.effectiveAutoAbilities(watcher)) {
					if (!fa.trigger().equals("becomes dull by effect")) continue;
					if (!matchesStateChangeSubject(fa.triggerCard(), dulled, dulledIsP1, watcher, causerIsP1))
						continue;
					// Hope 13-109R prints "Freeze it." and nothing else: the whole effect is about
					// the card that just dulled, so it is resolved inline with that card preloaded
					// as its target. Zack and Reno choose targets of their own and must not be
					// preloaded — handing them one would aim their choice for them.
					if (ActionResolver.isTriggeredTargetAction(fa.effectText())) {
						if (dulledTarget == null) {
							mw.logEntry("[AutoAbility] " + watcher.name()
									+ " — the dulled card has left the field; skipped");
							continue;
						}
						runWithPreloadedTarget(fa, watcher, causerIsP1, dulledTarget);
						continue;
					}
					executeAutoAbility(fa, watcher, causerIsP1);
				}
		});
		mw.showStackWindowIfNeeded();
	}

	/** The side clause closing a state-change watcher's subject, and what it demands of the card. */
	private static final Pattern STATE_CHANGE_SUBJECT_SIDE =
			Pattern.compile("(?i)\\s+(?<side>(?:your\\s+)?opponent\\s+controls|you\\s+control)$");

	/** The state qualifier opening it — "an active Forward …", "a dull Character …" — article and all. */
	private static final Pattern STATE_CHANGE_SUBJECT_STATE =
			Pattern.compile("(?i)^(?:an?\\s+)?(?:active|dull)\\s+");

	/**
	 * True when {@code changed} satisfies the subject of a "becomes dull / becomes active by effect"
	 * watcher carried by {@code watcher}.
	 *
	 * <p>The side clause is read rather than assumed. Zack's "a Forward opponent controls" and
	 * Hope's "a Character you control" are the printings today, and the caller already knows the
	 * watcher sits on the causing side, so it would be tempting to hard-code the relation — but the
	 * two cards demand opposite ones, and Hope carries both at once. A subject with no side clause
	 * is left unconstrained, matching either.
	 *
	 * <p>The opening state qualifier is dropped rather than checked. It says which transition the
	 * card has to have made ("an <em>active</em> Forward becomes dull"), and every caller has
	 * already established that by comparing the state before and after, so re-reading it here would
	 * only be a second chance to get it wrong.
	 */
	private boolean matchesStateChangeSubject(String subject, CardData changed, boolean changedIsP1,
			CardData watcher, boolean watcherIsP1) {
		if (subject == null || subject.isBlank()) return false;
		String part = subject.trim();
		Matcher side = STATE_CHANGE_SUBJECT_SIDE.matcher(part);
		if (side.find()) {
			boolean wantsOpponent = !side.group("side").toLowerCase(Locale.ROOT).startsWith("you ");
			if (wantsOpponent != (changedIsP1 != watcherIsP1)) return false;
			part = part.substring(0, side.start()).trim();
		}
		// "1 or more dull Backups" names the same single card a watcher checks as "a dull Backup";
		// how often it fires is the dispatcher's business.
		Matcher oneOrMore = ONE_OR_MORE_SUBJECT.matcher(part);
		if (oneOrMore.lookingAt())
			part = "a " + part.substring(oneOrMore.end())
					.replaceFirst("(?i)\\b(Forward|Backup|Monster|Character)s\\b", "$1");
		part = STATE_CHANGE_SUBJECT_STATE.matcher(part).replaceFirst("a ").trim();
		return matchesSingleSubject(part, changed, watcher);
	}

	/**
	 * The mirror of {@link #triggerAutoAbilitiesForBecomesDullByEffect} — "When a dull Character you
	 * control becomes active due to your Summons or abilities, …" (13-109R Hope, the corpus's only
	 * printing, which carries this and the dull watcher as its two halves).
	 *
	 * <p>Hooked at {@code activateTarget}, the single primitive every effect-driven activation goes
	 * through, Backups and Monsters included. Unlike the dull primitives it has no already-in-state
	 * guard of its own, so the caller establishes the DULL→ACTIVE transition and only then calls
	 * this: activating an already-active card is not something "becomes active" describes.
	 *
	 * @param causerIsP1 the side whose Summon or ability caused it — "due to <em>your</em> …"
	 */
	void triggerAutoAbilitiesForBecomesActiveByEffect(CardData activated, boolean activatedIsP1,
			boolean causerIsP1) {
		if (activated == null) return;
		withBatch(() -> {
			for (CardData watcher : fieldCards(causerIsP1))
				for (AutoAbility fa : mw.effectiveAutoAbilities(watcher)) {
					if (!fa.trigger().equals("becomes active by effect")) continue;
					if (!matchesStateChangeSubject(fa.triggerCard(), activated, activatedIsP1,
							watcher, causerIsP1)) continue;
					// "1 or more dull Backups … is activated" (12-114R Baralai): one event per effect,
					// however many it wakes — the White Mage reading of "1 or more".
					if (ONE_OR_MORE_SUBJECT.matcher(fa.triggerCard()).lookingAt()) {
						Integer last = oneOrMoreActivatedSerial.get(watcher);
						if (last != null && last == mw.resolutionSerial) continue;
						oneOrMoreActivatedSerial.put(watcher, mw.resolutionSerial);
					}
					executeAutoAbility(fa, watcher, causerIsP1);
				}
		});
		mw.showStackWindowIfNeeded();
	}

	/** "1 or more …" opening a subject — a trigger that fires once for however many cards. */
	private static final Pattern ONE_OR_MORE_SUBJECT = Pattern.compile("(?i)^1\\s+or\\s+more\\s+");

	/** The resolution each "1 or more … is activated" watcher last fired in, by watcher. */
	private final Map<CardData, Integer> oneOrMoreActivatedSerial = new java.util.IdentityHashMap<>();

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
		try {
			triggerExBurstImpl(card, isP1);
		} finally {
			// Every exit from the resolution passes through here, including one that throws part
			// way through an effect — a glow left spinning on a settled board is worse than none.
			if (isP1) mw.stopExBurstGlow();
		}
	}

	private void triggerExBurstImpl(CardData card, boolean isP1) {
		String effect = card.exBurstEffect();
		if (effect.isEmpty()) {
			mw.logEntry("[EX BURST] " + card.name() + " — no parseable effect");
			return;
		}
		// "Damage 3 -- EX BURST …" (17-080R Ewen): counted with the card that was just revealed,
		// which is already in the Damage Zone.
		int needed = card.exBurstDamageThreshold();
		int received = (isP1 ? mw.gameState.getP1DamageZone() : mw.gameState.getP2DamageZone()).size();
		if (received < needed) {
			mw.logEntry("[EX BURST] " + card.name() + " — needs " + needed + " points of damage (has " + received + ")");
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
		// Lit from here rather than from the top of the method: a burst with no effect this engine
		// can run resolves to a log line, and a glow that blinks on and straight off reads as a
		// glitch.  From this point there is always a dialog and possibly target picks to make.
		if (isP1) mw.startExBurstGlow(exBurstDamageSlot(card));
		// Asked of the damaged player, whoever holds that seat; the AI always activates.
		String shownEffect = effect;
		boolean activate = mw.decideYesNoAs(isP1, shufflingway.net.ChoiceKind.EX_BURST,
				"Waiting for your opponent to decide on " + card.name() + "'s EX Burst...",
				() -> askExBurst(card, shownEffect),
				() -> {
					mw.logEntry("[EX BURST] [AI] " + card.name() + " — auto-activates");
					return true;
				});
		if (!activate) {
			mw.logEntry("[EX BURST] " + card.name() + " — declined");
			return;
		}
		mw.logEntry("[EX BURST] " + card.name() + " — " + effect);
		if (card.isSummon()) { mw.currentResolutionIsSummon = true; mw.currentSummonSource = card; }
		try { fn.accept(mw.buildGameContext(isP1, true)); } finally { mw.currentResolutionIsSummon = false; mw.currentSummonSource = null; }
		triggerAutoAbilitiesForOpponentUsesExBurst(isP1);
	}

	/** The local player's EX Burst prompt: the card, its effect, OK or Decline. */
	private boolean askExBurst(CardData card, String effect) {
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
		return activated[0];
	}

	/**
	 * P1 damage-zone index of the card whose EX Burst is being resolved, or -1 if it is not there.
	 *
	 * <p>Searched by identity from the back, not by {@code indexOf}: {@link CardData} is a record,
	 * so a second copy of the same printing already in the Damage Zone is {@code equals()} to this
	 * one and would light the wrong slot.
	 */
	private int exBurstDamageSlot(CardData card) {
		List<CardData> dz = mw.gameState.getP1DamageZone();
		for (int i = dz.size() - 1; i >= 0; i--) if (dz.get(i) == card) return i;
		return -1;
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
			all.addAll(isP1 ? mw.p1ForwardCards : mw.p2ForwardCards);
			for (CardData c : (isP1 ? mw.p1BackupCards : mw.p2BackupCards)) if (c != null) all.add(c);
			all.addAll(isP1 ? mw.p1MonsterCards : mw.p2MonsterCards);
			for (CardData card : all)
				for (AutoAbility fa : mw.effectiveAutoAbilities(card))
					if (fa.trigger().equals("warp counter removed") && warpCounterSubjectMatches(fa.triggerCard(), target))
						executeAutoAbility(fa, card, isP1);
			// Warp-zone residents on both sides, each for its own owner: "any player's card"
			// (24-048L Tidus) reaches across the table, which the warping side's walk above does not.
			for (boolean ownerIsP1 : new boolean[] { true, false })
				for (CardData card : warpZoneResidents(ownerIsP1))
					for (AutoAbility fa : mw.effectiveAutoAbilities(card))
						if (fa.trigger().equals("warp counter removed")
								&& (ownerIsP1 == isP1 || warpCounterSubjectIsAnyPlayers(fa.triggerCard()))
								&& warpCounterSubjectMatches(fa.triggerCard(), target))
							executeAutoAbility(fa, card, ownerIsP1);
		});
		mw.showStackWindowIfNeeded();
	}

	/** "any player's card[ other than Card Name X]" — the subject that is not a single named card. */
	private static final Pattern WARP_COUNTER_ANY_PLAYERS_SUBJECT = Pattern.compile(
			"(?i)^any\\s+player's\\s+card(?:\\s+other\\s+than\\s+(?:Card\\s+Name\\s+)?(?<except>.+))?$");

	private static boolean warpCounterSubjectIsAnyPlayers(String subject) {
		return WARP_COUNTER_ANY_PLAYERS_SUBJECT.matcher(subject.trim()).matches();
	}

	/**
	 * Whether a "warp counter removed" subject covers the card that lost a counter: its own name,
	 * or "any player's card", less the card an "other than Card Name X" spares. The exclusion is
	 * what keeps 24-048L Tidus from answering its own removal and taking its counters off one after
	 * another in a single chain.
	 */
	private static boolean warpCounterSubjectMatches(String subject, CardData target) {
		Matcher any = WARP_COUNTER_ANY_PLAYERS_SUBJECT.matcher(subject.trim());
		if (any.matches())
			return any.group("except") == null || !any.group("except").trim().equalsIgnoreCase(target.name());
		return subject.equalsIgnoreCase(target.name());
	}


	// =========================================================================================
	// Generic event dispatch and ability execution
	// =========================================================================================
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
		for (CardData c : removedFromGameResidents(isP1))
			for (AutoAbility fa : warpZoneAbilities(c))
				if (fa.trigger().equals(triggerType)) executeAutoAbility(fa, c, isP1);
	}

	/** The cards in {@code isP1}'s Warp zone, copied so a trigger that warps one in cannot disturb the walk. */
	private List<CardData> warpZoneResidents(boolean isP1) {
		List<CardData> out = new ArrayList<>();
		for (GameState.WarpEntry we : isP1 ? mw.gameState.getP1WarpZone() : mw.gameState.getP2WarpZone())
			if (we != null) out.add(we.card);
		return out;
	}

	/**
	 * Every card {@code isP1} has removed from the game: the Warp zone and the permanent RFP pile,
	 * copied for the same reason. Walked with {@link #warpZoneAbilities}, so only the abilities a
	 * card says it uses while removed can fire — 16-067L Aerith's Reraise countdown ticks from the
	 * permanent pile, where no Warp entry holds her.
	 */
	private List<CardData> removedFromGameResidents(boolean isP1) {
		List<CardData> out = warpZoneResidents(isP1);
		out.addAll(isP1 ? mw.gameState.getP1PermanentRfp() : mw.gameState.getP2PermanentRfp());
		return out;
	}

	/**
	 * The abilities {@code card} uses from the Warp zone: those that say they trigger only while it
	 * is removed from the game — 23-050H Noel, 23-060L Vincent, 24-048L Tidus, 29-086H Shadow,
	 * 21-007L Shadow. A card in the Warp zone is not on the field, so none of the field walks reach
	 * it; every other ability it prints is for when it arrives, and must stay silent until then.
	 */
	private List<AutoAbility> warpZoneAbilities(CardData card) {
		List<AutoAbility> out = new ArrayList<>();
		for (AutoAbility fa : card.autoAbilities())
			if (fa.rfpConditionCard().equalsIgnoreCase(card.name())) out.add(fa);
		return out;
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
		// Checked before batching as well as at dispatch: an arrival gate that fails did not trigger
		// at all, so it must not appear in the stack-ordering dialog beside the triggers that did
		// (17-140S Golbez's discount drawback on a full-price cast).
		if (arrivalGateFails(fa)) return;
		if (pendingBatch != null) {
			pendingBatch.add(new StackOrderingDialog.Item(fa, source, isP1, paidExtraCost, triggerCard,
					mw.triggeringEnteredCard));
			return;
		}
		executeAutoAbilityImpl(fa, source, isP1, paidExtraCost, triggerCard);
	}

	private void executeAutoAbilityImpl(AutoAbility fa, CardData source, boolean isP1) {
		executeAutoAbilityImpl(fa, source, isP1, false);
	}

	/**
	 * Whether {@code fa} is gated on how its card arrived and the arrival in progress is not that
	 * one. Reads MainWindow's arrival flags, which hold while the card's enter-the-field triggers
	 * are collected and dispatched ({@link FieldEntryAnimator#fireEntersField} restores them for a
	 * queued run).
	 */
	private boolean arrivalGateFails(AutoAbility fa) {
		// "due to your cast" — only fires when the card entered the field by being cast from hand
		if (fa.castOnly() && !mw.lastCardWasCast) return true;
		// "due to Warp" — only fires when the card entered the field via Warp resolution
		if (fa.warpOnly() && !mw.lastCardWarpedIn) return true;
		// "If you do so" — only fires when the card was cast under its own optional cost
		// reduction. The discount is what buys this drawback, so a full-price cast skips it.
		return fa.altCostOnly() && !mw.lastCardCastViaAltCost;
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

	/**
	 * Whether this dispatcher resolves {@code fa}'s effect itself, rather than handing it to
	 * {@link ActionResolver#parse}.
	 *
	 * <p>Exists for the coverage reports. {@code parse()} is what every other check asks, and it
	 * answers {@code null} for these shapes — most are dispatched by {@link #executeAutoAbilityImpl}
	 * before it ever reaches the parseability check, because each pays a cost or makes a choice
	 * before the payoff can run. Asking {@code parse()} alone therefore reported working cards as
	 * unimplemented: Noctis 20-078H trades a Character to return from the Break Zone and has done
	 * so all along.
	 *
	 * <p>Every arm mirrors what the matching executor actually requires, so this cannot claim a card
	 * the engine would reject. The inline shapes are read off {@link #INLINE_SHAPES} through
	 * {@link #inlineClaimOf} and {@link #inlinePayoffReadable}, the table and the readers the
	 * runtime dispatches with, so the two cannot drift.
	 */
	static boolean dispatchedByTriggers(AutoAbility fa, CardData source) {
		String text = fa.effectText();
		// Dispatchers that match the effect themselves and never call executeAutoAbility.
		switch (fa.trigger()) {
			case "opponent character uses action ability" -> {
				Matcher m = FA_SACRIFICE_CANCEL_AND_BREAK_USER.matcher(text.trim());
				return m.matches() && m.group("name").trim().equalsIgnoreCase(source.name());
			}
			case "own character uses action ability" -> {
				Matcher m = FA_USES_SAME_ACTION_ABILITY.matcher(text.trim());
				return m.matches() && m.group("name").trim().equalsIgnoreCase(source.name());
			}
			case "chosen by forward ability" -> { return chosenByForwardPayoff(text) != null; }
			default -> { }
		}
		// A payoff about the dealer reaches executeAutoAbility rewritten by the dealer's side
		// (atDealer), so both rewrites are what has to resolve.
		if (paysOffAtDealer(fa)) {
			for (boolean dealerOnWatcherSide : new boolean[]{ true, false }) {
				String rewritten = atDealer(fa, new DamageDealer(null, dealerOnWatcherSide, false), true).effectText();
				Boolean inline = inlineClaimResolves(rewritten, fa, source);
				if (!(inline != null ? inline : ActionResolver.parse(rewritten, source) != null)) return false;
			}
			return true;
		}
		Boolean inline = inlineClaimResolves(text, fa, source);
		if (inline != null) return inline;
		// "If you paid the extra cost, …". executeAutoAbilityImpl rewrites the effect into the
		// branch that was actually taken before it asks parse() anything, so the printed text is
		// the wrong thing to ask about — parse() declines it on purpose, because reading it loosely
		// would fire the payoff whether or not the cost was paid.
		//
		// Only the paid branch is checked. The unpaid one is either an unconditional lead-in, in
		// which case the ordinary parse() check on the printed text has already claimed the card
		// and this arm never decides anything (Samurai 27-009C and its four siblings), or nothing
		// at all — Summoner 27-064C's whole ability sits behind the condition, and the executor
		// answers a blank rewrite with "extra cost not paid, no effect", which is implemented
		// behaviour rather than a gap.
		//
		// Phrased to only ever claim, never disclaim: unlike the arms above, parse() on the printed
		// text is not known to be null here, so returning this comparison directly would let a card
		// the ordinary check recognises be reported as unimplemented.
		String paidBranch = ActionResolver.applyExtraCostPaid(text);
		if (!paidBranch.equals(text) && ActionResolver.parse(paidBranch, source) != null) return true;
		// Dispatched by DamageResolver rather than by this class, but for the same reason and with
		// the same consequence for the reports: Gulool Ja Ja 27-007H's echo names the damaged card
		// and the amount it took, neither of which is in the text, so it is resolved where the
		// event is and parse() answers null for it.
		if (FA_DAMAGE_ECHO_TO_OTHER_FORWARD.matcher(text.trim()).matches()) return true;
		// Breaktouch, the other DamageResolver-dispatched shape. "break it." carries no target of
		// its own — "it" is the card just damaged, which only the damage event knows — so parse()
		// answers null and the reports called Tonberry 19-097C and Ramuh 14-090R unimplemented
		// while they had been breaking Forwards all along.
		//
		// Gated on the trigger as well as the text, mirroring what fireBreaktouchForDamage
		// requires: a "break it." hanging off any other trigger reaches no executor at all, and
		// claiming it here would report a card as working on the strength of two words.
		return isBreaktouchTrigger(fa.trigger())
				&& FA_BREAKTOUCH_BREAK_IT.matcher(text.trim()).matches();
	}

	/**
	 * Whether the {@link #INLINE_SHAPES} entry that claims {@code text} can resolve it, asked the
	 * way its handler asks; {@code null} when no shape claims it and it goes to the Stack.
	 */
	private static Boolean inlineClaimResolves(String text, AutoAbility fa, CardData source) {
		InlineClaim c = inlineClaimOf(text);
		if (c == null) return null;
		// A gate whose rest no shape takes is logged as unrecognised by its handler.
		if (c.shape().equals("ConditionGateMay")) return false;
		if (c.shape().endsWith("PutSelfIntoBzIfDoSo") && !arrivalPayoffResolves(text, fa)) return false;
		return c.payoffs().stream().allMatch(p -> inlinePayoffReadable(c.shape(), p, source));
	}

	/**
	 * The arrival sentence of a self-sacrifice payoff ("deal it 8000 damage" — 5-008R Grenade), which
	 * {@link #selfSacrificePayoffs} leaves out because {@link #readSelfSacrificePayoff} reads it
	 * itself: only against an entering card, so only behind an enters-field trigger. True when the
	 * payoff has no such sentence.
	 */
	private static boolean arrivalPayoffResolves(String text, AutoAbility fa) {
		Matcher m = FA_PUT_SELF_INTO_BZ_IF_DO_SO.matcher(text);
		if (!m.find()) return true;
		String first = m.group("sub").trim().split("(?<=[.!])\\s+(?=[A-Z])", 2)[0].trim();
		Matcher em = SACRIFICE_PAYOFF_ON_ARRIVAL.matcher(first);
		if (!em.matches()) return true;
		return fa.trigger().contains("enters") && ActionResolver.parseTargetAction(arrivalAction(em), 0) != null;
	}

	/** A {@link #SACRIFICE_PAYOFF_ON_ARRIVAL} match as the target action it performs: "Deal it 8000 damage". */
	private static String arrivalAction(Matcher em) {
		String verb = em.group("verb").trim();
		return Character.toUpperCase(verb.charAt(0)) + verb.substring(1).toLowerCase(Locale.ROOT)
				+ " it" + (em.group("tail") != null ? em.group("tail") : "");
	}

	/**
	 * Whether an "is dealt damage by …" trigger's payoff is about the dealer ("break that Forward",
	 * "that Character's controller discards …"), which {@link #atDealer} rewrites before it goes on
	 * the Stack. Only a trigger that names its dealer means the dealer by "that Forward": in the
	 * unqualified form (27-007H Gulool Ja Ja's echo) it is the damaged card.
	 */
	private static boolean paysOffAtDealer(AutoAbility fa) {
		if (!fa.trigger().equals("is dealt damage")) return false;
		Matcher q = DEALT_DAMAGE_QUALIFIER.matcher(fa.triggerCard() == null ? "" : fa.triggerCard().trim());
		return q.matches() && q.group("by") != null && REFERS_TO_DEALER.matcher(fa.effectText()).find();
	}

	/**
	 * "dull [CardName] if it is active. If you do so, [sub-effect]" -- Yuna 1-214S, Mira 4-137L.
	 *
	 * <p>The self-dull twin of {@link #executePutSelfIntoBzIfDoSoAutoAbility}, and it reads its
	 * clauses the same way: a printed "you may" is the declinable part (the parser has already
	 * lifted it into {@code youMay}/{@code opponentMay}), while "if you do so" only gates the
	 * payoff on a cost that has by then been paid.
	 *
	 * <p>"if it is active" is checked before the offer rather than after it. A dull source cannot
	 * pay, so there is nothing to decline, and prompting anyway would put a choice in front of a
	 * player who has none -- Yuna's trigger fires on every Summon cast and would ask every time.
	 *
	 * <p>The sub-effect resolves after the dull, so an effect that reads the board sees the cost
	 * already paid. Mira's payoff reads the trigger's own card instead
	 * ({@link GameContext#searchDeckMatchingTriggeringBrokenCardName}), which
	 * {@code executeAutoAbilityImpl} has standing for the whole of this call.
	 */
	private void executeDullSelfIfDoSoAutoAbility(AutoAbility fa, CardData source,
			boolean isP1, boolean effectIsP1, Matcher m) {
		String cardName  = m.group("cardname").trim();
		String subEffect = m.group("sub").trim();

		if (!CardFilters.meetsCardNameFilter(source, cardName)) {
			mw.logEntry("[AutoAbility] " + source.name() + " — self-dull: '" + cardName
					+ "' does not match source, skipping");
			return;
		}

		// Located by identity, as the self-break sibling locates its own: the text names the card
		// but it is this copy of it that is paying.
		ForwardTarget slot = mw.findFieldSlot(source, isP1);
		if (slot == null) {
			mw.logEntry("[AutoAbility] " + source.name() + " — no longer on field, sub-effect skipped");
			return;
		}
		if (mw.fieldTargetState(slot) != CardState.ACTIVE) {
			mw.logEntry("[AutoAbility] " + source.name() + " — already dull, sub-effect skipped");
			return;
		}

		Consumer<GameContext> effect = ActionResolver.parse(subEffect, source);
		if (effect == null) {
			mw.logEntry("[AutoAbility] Unrecognized sub-effect: " + subEffect);
			return;
		}

		if (!acceptsOptional(fa, isP1, source.name() + " — " + fa.effectText(),
				"Dull " + source.name(), "Decline", () -> aiAccepts("self-dull for " + source.name()))) {
			mw.logEntry("[AutoAbility] " + source.name() + " — self-dull declined");
			return;
		}

		mw.buildGameContext(isP1).dullTarget(slot);
		mw.logEntry("[AutoAbility] " + source.name() + " — if you do so: " + subEffect);
		effect.accept(mw.buildGameContext(effectIsP1));
	}

	/**
	 * The two triggers {@code DamageResolver.fireBreaktouchForDamage} answers to: the card dealing
	 * the damage itself, and — for 14-090R Ramuh, Lord of Levin, the one printing that names both —
	 * a Summon of a given Element the card's controller cast.
	 */
	private static boolean isBreaktouchTrigger(String trigger) {
		return trigger.equals("deals damage to forward")
				|| trigger.endsWith(" summon deals damage to forward");
	}

	private void executeAutoAbilityImpl(AutoAbility fa, CardData source, boolean isP1, boolean paidExtraCost) {
		// Damage threshold: skip if the controlling player doesn't have enough damage counters
		if (fa.damageThreshold() > 0) {
			int dmg = isP1 ? mw.gameState.getP1DamageZone().size() : mw.gameState.getP2DamageZone().size();
			if (dmg < fa.damageThreshold()) return;
		}

		// "only during your turn" — skip when the ability owner is not the active player. Read off the
		// turn, as the mirror below is: a P2 ability used to never fire, and a P1 one fired on both turns.
		if (fa.yourTurnOnly()
				&& (mw.gameState.getCurrentPlayer() == GameState.Player.P1) != isP1) {
			mw.logEntry("[AutoAbility] " + source.name() + " — only triggers during your turn");
			return;
		}

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

		if (arrivalGateFails(fa)) return;

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

		// The text this ability will actually resolve, which is not always the text it prints: an
		// "If you paid the extra cost, …" clause is rewritten into the branch that was taken. Worked
		// out once here, ahead of the inline shapes as well as the push below, so every route
		// resolves the branch that was taken — Fina 8-060L's "select 2 of the 2" upgrade is read by
		// the inline select shape, which on the printed text only ever offered 1.
		String resolvedText = paidExtraCost
				? ActionResolver.applyExtraCostPaid(fa.effectText())
				: ActionResolver.stripExtraCostClause(fa.effectText());

		// Nothing left after stripping means the whole ability was the condition and the condition
		// was not met — 27-064C Summoner, the only printing with no unconditional lead-in in front
		// of its clause. Not a parse failure, so it is not reported as one.
		if (resolvedText.isBlank()) {
			mw.logEntry("[AutoAbility] " + source.name() + " — extra cost not paid, no effect");
			return;
		}
		AutoAbility resolvedFa = resolvedText.equals(fa.effectText()) ? fa : fa.withEffectText(resolvedText);

		// The "when you do so" family and its siblings resolve here and now rather than going on the
		// Stack, so the ability source they run under has to be established here — the Stack route
		// sets it from the entry (MainWindow's isAutoAbility() arm) and these never reach it.
		if (withAbilitySource(source, () -> dispatchInlineAutoAbility(resolvedFa, source, isP1, effectIsP1)))
			return;

		// Verify the effect is parseable before putting it on the stack. Asked of the resolved text
		// rather than the printed text: Summoner's prints as "if you paid the extra cost, your
		// opponent selects …", which parse() declines on purpose — it cannot know whether the cost
		// was paid, and reading the conditional loosely would fire the effect either way. Checking
		// the raw text here rejected the ability before the rewrite that makes it readable ever ran,
		// so it never reached the Stack however the cost was paid.
		if (ActionResolver.parse(resolvedText, source) == null) {
			mw.logEntry("[AutoAbility] Unrecognized effect: " + resolvedText);
			return;
		}

		// "you may remove 3 … When you do so, …" with fewer than 3 there to take: resolution would
		// refuse the price, so there is nothing to offer — to P1 or to the AI.
		if ((fa.youMay() || fa.opponentMay())
				&& ActionResolver.whenYouDoSoPriceUnpayable(resolvedText, mw.buildGameContext(effectIsP1))) {
			mw.logEntry("[AutoAbility] " + source.name() + " — cannot pay the removal, offer skipped");
			return;
		}

		// youMay / opponentMay: player decides at trigger time whether to put ability on stack.
		if (fa.youMay() || fa.opponentMay()) {
			// If the effect requires discarding a card of a specific type, skip offering
			// when the player has no eligible cards in hand — nothing to choose from. Read off the
			// board, so both clients skip together whoever is being offered.
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
			if (!acceptsOptional(fa, isP1, source.name() + " — " + optionalPrompt(fa, fa.effectText()),
					() -> aiAccepts("optional ability"))) {
				mw.logEntry("[AutoAbility] " + source.name() + " — optional effect declined");
				return;
			}
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
		//
		// The compound trigger Ifrit (XVI) 26-003R carries watches two events, and only one of them
		// is a selection in progress: fired by being blocked it is an ordinary combat trigger and
		// belongs on the Stack, so the flag — not the trigger name — is what decides.
		if (fa.trigger().startsWith("chosen by opponent's")
				|| (resolvingChosenBySelection
						&& fa.trigger().equals("is blocked or chosen by opponent's ability"))) {
			Consumer<GameContext> effect = ActionResolver.parse(fa.effectText(), source);
			mw.logEntry("[AutoAbility] " + source.name() + " — " + fa.effectText());
			// Inline, so the source is established here rather than by the Stack entry.
			withAbilitySource(source, () -> { effect.accept(mw.buildGameContext(effectIsP1)); return true; });
			return;
		}

		mw.logEntry("[AutoAbility] " + source.name() + " — pushed to stack");
		// An ability chooses its targets as it goes on the Stack, not when it resolves, so the
		// opponent can respond to what it is pointed at. The text is transformed the same way
		// resolution will transform it, or a conditional clause could change the eligible set.
		// The depth is taken first so any "when this is chosen" trigger the selection fires lands
		// above this entry and resolves before it (see GameState.insertStack).
		int depth = mw.gameState.stackSize();
		String effectText = resolvedText;
		// The size of the damage instance that fired an "is dealt damage" trigger travels as the
		// entry's xValue, which is what that field means: the number this activation supplied, not
		// one the text names. Shantotto 4-083L's "deal the same amount of damage" is the effect
		// that reads it, and it reads it at resolution — long after the dispatcher has restored
		// MainWindow.lastDealtDamageAmount, which is why the value cannot be left on that field.
		// Auto abilities have no X cost of their own, so nothing else is competing for it.
		int entryX = fa.trigger().equals("is dealt damage") ? mw.lastDealtDamageAmount : 0;
		GameContext selectCtx = mw.buildGameContext(effectIsP1);
		List<ForwardTarget> preTargets = effectText.isBlank() ? null
				: targetsForStack(ActionResolver.preSelectTargets(effectText, source, entryX, selectCtx),
						effectText, source, selectCtx);
		// "When X is dealt damage by a Forward …, deal that Forward …": the dealer came down as the
		// trigger card (fireIsDealtDamageTriggers), and "that Forward" is it.
		if (fa.trigger().equals("is dealt damage") && mw.triggeringBrokenCard != null
				&& ActionResolver.isTriggeredTargetAction(effectText))
			preTargets = dealerTarget(mw.triggeringBrokenCard);
		// The trigger's own card travels with the entry. The field it is read from here is unwound
		// the moment this push returns — resolution comes later, off the Stack — so an effect that
		// names the card back ("add it to your hand") found nothing there and fizzled.
		StackEntry entry = new StackEntry(source, null, fa, effectIsP1, entryX, false, preTargets, false,
				paidExtraCost, 0, 0, mw.triggeringBrokenCard, mw.triggeringEnteredCard);
		mw.gameState.insertStack(depth, entry);
		mw.cancelFirstOppForwardAuto(entry);
		// "When your opponent's auto-ability is put on the stack" — 10-074C Suzuhisa. Only the ones
		// that actually go on it: the shapes this layer resolves inline never do. Collected, not
		// shown: this runs mid-push, and showing the Stack here would start resolving it under the
		// caller that is still putting things on it.
		withBatch(() -> collectEventTriggers("opponent auto-ability put on stack", !isP1));
	}


	/**
	 * What an auto ability carries onto the Stack from its push-time target selection: the picks,
	 * or {@code null} to choose again as it resolves.
	 *
	 * <p>An empty selection can mean two things. Nothing eligible when the ability went on the
	 * Stack falls back to choosing at resolution. An "up to" choice the player was offered and
	 * declined — Gilgamesh 27-079H's "choose up to 2 Forwards" with none picked — is a choice
	 * made, and asking again at resolution would make them decline it twice.
	 */
	static List<ForwardTarget> targetsForStack(List<ForwardTarget> picked, String effectText,
			CardData source, GameContext ctx) {
		if (picked == null || !picked.isEmpty()) return picked;
		TargetSpec spec = ActionResolver.targetSpec(effectText, source);
		boolean declined = spec != null && spec.upTo() && spec.zone() == null
				&& ctx instanceof GameContextImpl impl && !impl.eligibleCharacters(spec).isEmpty();
		return declined ? picked : null;
	}


	// =========================================================================================
	// "When you do so" auto abilities
	// =========================================================================================

	/** The handler an {@link InlineShape} hands its match to. */
	@FunctionalInterface
	private interface InlineHandler {
		void run(AutoAbilityTriggers self, AutoAbility fa, CardData source, boolean isP1,
				boolean effectIsP1, Matcher m);
	}

	/**
	 * One shape {@link #dispatchInlineAutoAbility} resolves itself, found with {@code find()}.
	 *
	 * <p>{@code payoffs} names the text(s) the handler parses for the same match, so the
	 * partial-parse report can measure what the layer actually runs; {@link #inlinePayoffReadable}
	 * says which reader that is. {@code null} when {@link #inlineClaimOf} supplies them itself.
	 */
	private record InlineShape(String name, Pattern pattern, InlineHandler handler,
			Function<Matcher, List<String>> payoffs) {}

	/** The payoff of the many shapes that parse their {@code sub} group. */
	private static List<String> subPayoff(Matcher m) {
		return List.of(m.group("sub").trim());
	}

	/**
	 * What {@link #readSelfSacrificePayoff} hands to {@link ActionResolver#parse}: the whole payoff,
	 * or only the sentences after an arrival sentence it reads itself.
	 */
	private static List<String> selfSacrificePayoffs(Matcher m) {
		String sub = m.group("sub").trim();
		String[] parts = sub.split("(?<=[.!])\\s+(?=[A-Z])", 2);
		if (!SACRIFICE_PAYOFF_ON_ARRIVAL.matcher(parts[0].trim()).matches()) return List.of(sub);
		return parts.length > 1 ? List.of(parts[1].trim()) : List.of();
	}

	/**
	 * The shapes {@code executeAutoAbilityImpl} resolves itself instead of pushing onto the Stack —
	 * the ones that have to charge a cost, take a choice or read a revealed hand before their
	 * sub-effect can be parsed at all.
	 *
	 * <p>Ordering is load-bearing in the same way the resolver's chains are: every matcher here uses
	 * {@code find()}, so a broader shape placed ahead of a narrower one claims its text. One table
	 * serves both the runtime ({@link #dispatchInlineAutoAbility}) and the partial-parse report
	 * ({@link #inlineShapeOf}), so the two cannot disagree about which texts this layer takes.
	 */
	private static final List<InlineShape> INLINE_SHAPES = List.of(
		// "remove N [Name] Counter(s) from [CardName]. When you do so, [effect]"
		new InlineShape("RemoveCounterWhenDoSo", FA_REMOVE_COUNTER_WHEN_DO_SO,
				AutoAbilityTriggers::executeCounterRemovalWhenDoSoAutoAbility,
				AutoAbilityTriggers::subPayoff),
		// "pay 《X/N》. When you do so, [effect]" — requires a payment dialog before resolving.
		new InlineShape("PayWhenDoSo", FA_PAY_WHEN_DO_SO,
				AutoAbilityTriggers::executePayWhenDoSoAutoAbility,
				m -> List.of(withoutXPaymentSource(m.group(2).trim()).replaceAll("[.!,]+$", ""))),
		// "pay 《…》 and discard 1 <type>. When you do so, [effect]" — the discard joins the payoff,
		// behind a check that the payer holds one, so the CP is never spent on a discard that
		// cannot happen.
		new InlineShape("PayAndDiscardWhenDoSo", FA_PAY_AND_DISCARD_WHEN_DO_SO,
				AutoAbilityTriggers::executePayAndDiscardWhenDoSoAutoAbility,
				m -> List.of(m.group(3).trim().replaceAll("[.!,]+$", ""))),
		// "pay 《…》 or 《C》《C》. When you do so, [effect]" — CP or Crystals, the payer's choice
		new InlineShape("PayOrCrystalsWhenDoSo", FA_PAY_OR_CRYSTALS_WHEN_DO_SO,
				AutoAbilityTriggers::executePayOrCrystalsWhenDoSoAutoAbility,
				m -> List.of(m.group(3).trim().replaceAll("[.!,]+$", ""))),
		// "remove N [type] [without 《Keyword》] you control from the game. When you do so, [effect]"
		new InlineShape("RemoveFieldWhenDoSo", FA_REMOVE_FIELD_WHEN_DO_SO,
				AutoAbilityTriggers::executeRemoveFieldWhenDoSoAutoAbility,
				AutoAbilityTriggers::subPayoff),
		// "put N [Job/CardName/type] you control into the Break Zone. When you do so, [effect]"
		new InlineShape("PutIntoBzWhenDoSo", FA_PUT_INTO_BZ_WHEN_DO_SO,
				AutoAbilityTriggers::executePutIntoBzWhenDoSoAutoAbility,
				AutoAbilityTriggers::subPayoff),
		// "dull [CardName] if it is active. If/When you do so, [effect]" (self-dull)
		new InlineShape("DullSelfIfDoSo", FA_DULL_SELF_IF_DO_SO,
				AutoAbilityTriggers::executeDullSelfIfDoSoAutoAbility,
				AutoAbilityTriggers::subPayoff),
		// "put [CardName] into the Break Zone. If/When you do so, [effect]" (self-break)
		new InlineShape("PutSelfIntoBzIfDoSo", FA_PUT_SELF_INTO_BZ_IF_DO_SO,
				AutoAbilityTriggers::executePutSelfIntoBzIfDoSoAutoAbility,
				AutoAbilityTriggers::selfSacrificePayoffs),
		// "choose 1 <target>. You may put 1 <price> into the Break Zone. If you do so, <payoff>"
		// The payoff is read by parseFormerLatterGroupAction; see inlinePayoffReadable.
		new InlineShape("ChooseThenMayPutIntoBz", FA_CHOOSE_THEN_MAY_PUT_INTO_BZ,
				AutoAbilityTriggers::executeChooseThenMayPutIntoBzAutoAbility,
				AutoAbilityTriggers::subPayoff),
		// "select [up to] N of the M following actions. "..." "..."..." — parse() reads the whole
		// text, which inlineClaimOf supplies itself
		new InlineShape("SelectFollowingActions", FA_SELECT_FOLLOWING_ACTIONS,
				AutoAbilityTriggers::executeSelectFollowingActionsAutoAbility,
				m -> null),
		// "reveal any number of Summons from your hand. When you reveal no Summons, [effect0]. When you reveal N or more Summons, [effectN]."
		new InlineShape("RevealSummonsConditional", FA_REVEAL_SUMMONS_CONDITIONAL,
				AutoAbilityTriggers::executeRevealSummonsConditionalAutoAbility,
				m -> List.of(m.group("effect0").trim(), m.group("effectN").trim())),
		// "reveal any number of Summons from your hand. When you do so, [effect on up to the same number of Characters]."
		new InlineShape("RevealSummonsSameNumber", FA_REVEAL_SUMMONS_SAME_NUMBER,
				AutoAbilityTriggers::executeRevealSummonsSameNumberAutoAbility,
				m -> List.of(withRevealedCount(m.group("effect").trim(), 2))),
		// "select the following actions from top to bottom up to the same number of Elements other than X as the cost you paid to cast [CardName]."
		new InlineShape("SelectFollowingActionsDynamicElements", FA_SELECT_FOLLOWING_ACTIONS_DYNAMIC_ELEMENTS,
				AutoAbilityTriggers::executeSelectFollowingActionsDynamicElements,
				m -> ActionResolver.selectFollowingOptions(m.group("actions"))),
		// "if [cast count | control count], you may put/pay …" — gate, then the rest re-dispatched
		new InlineShape("ConditionGateMay", FA_CONDITION_GATE_MAY,
				AutoAbilityTriggers::executeConditionGateMayAutoAbility,
				m -> {
					InlineClaim inner = inlineClaimOf(m.group("rest").trim());
					return inner == null ? List.of() : inner.payoffs();
				}));

	/**
	 * What {@link #INLINE_SHAPES} does with one auto-ability's text, for the partial-parse report:
	 * the shape that claims it, the payoff text(s) its handler parses ({@code null} when the handler
	 * does not use {@link ActionResolver#parse}), and the text either side of the shape's
	 * {@code find()} match, which no handler reads.
	 */
	record InlineClaim(String shape, List<String> payoffs, String before, String after) {}

	/**
	 * Whether a payoff {@link #inlineClaimOf} reported is one its handler can read, asked the way
	 * the handler asks. The pay shapes resolve through {@link #applyPayWhenDoSoEffect}, which knows X
	 * (one unit here, as the AI buys) and falls back to {@link ActionResolver#parsePayGatedFollowup}.
	 */
	/**
	 * 20-102L Mira's payoff as her handler resolves it. "break that Forward" is Breaktouch's
	 * wording, which the resolver keeps off the triggered-target form on purpose; here it means the
	 * Forward whose arrival fired the trigger, so it is handed over in the "that Character" form that
	 * reads it that way (5-130R Tonberry).
	 */
	static String payAndDiscardPayoff(String payoff) {
		return payoff.trim().replaceAll("(?i)\\bbreak\\s+that\\s+Forward\\b", "break that Character");
	}

	/**
	 * Triggers whose dispatcher matches the effect itself instead of calling {@link #executeAutoAbility},
	 * so no inline shape runs for them: 5-090R Hill Gigas and 15-028H Gogo. The partial-parse report
	 * reads this to leave them out of the shapes it credits.
	 */
	static final Set<String> OWN_DISPATCHER_TRIGGERS =
			Set.of("opponent character uses action ability", "own character uses action ability");

	static boolean inlinePayoffReadable(String shape, String payoff, CardData source) {
		if (shape.endsWith("PayAndDiscardWhenDoSo"))
			return ActionResolver.parsePayGatedFollowup(payAndDiscardPayoff(payoff), source, 1) != null
					|| ActionResolver.parse(payAndDiscardPayoff(payoff), source) != null;
		// A per-target action on the card chosen up front (4-087R Delita, 7-020C Lulu).
		if (shape.endsWith("ChooseThenMayPutIntoBz"))
			return ActionResolver.parseFormerLatterGroupAction(payoff) != null;
		if (shape.endsWith("PayWhenDoSo") || shape.endsWith("PayOrCrystalsWhenDoSo"))
			return ActionResolver.isGainCrystalPerX(payoff)
					|| ActionResolver.parsePayGatedFollowup(payoff, source, 1) != null;
		return ActionResolver.parse(payoff, source) != null;
	}

	/** The {@link InlineClaim} for {@code effectText}, or {@code null} when no shape takes it. */
	static InlineClaim inlineClaimOf(String effectText) {
		for (InlineShape s : INLINE_SHAPES) {
			Matcher m = s.pattern().matcher(effectText);
			if (!m.find()) continue;
			// Its handler hands parse() the whole text, so nothing lies outside what it reads.
			if (s.name().equals("SelectFollowingActions"))
				return new InlineClaim(s.name(), List.of(effectText.trim()), "", "");
			String before = effectText.substring(0, m.start());
			String after  = effectText.substring(m.end());
			// The gate re-dispatches its rest, so what lies outside the inner match is outside too.
			if (s.name().equals("ConditionGateMay")) {
				InlineClaim inner = inlineClaimOf(m.group("rest").trim());
				if (inner != null)
					return new InlineClaim(s.name() + ">" + inner.shape(), inner.payoffs(),
							before + inner.before(), inner.after() + after);
			}
			return new InlineClaim(s.name(), s.payoffs().apply(m), before, after);
		}
		return null;
	}

	/**
	 * The name of the inline shape that claims {@code effectText}, or {@code null} when it goes to
	 * the Stack and {@link ActionResolver#parse}. For the partial-parse report: an auto-ability this
	 * layer takes charges its own cost before parsing the rest, so a cost sentence the resolver never
	 * reads is not a dropped one.
	 */
	static String inlineShapeOf(String effectText) {
		for (InlineShape s : INLINE_SHAPES)
			if (s.pattern().matcher(effectText).find()) return s.name();
		return null;
	}

	/** Resolves {@code fa} through the first {@link #INLINE_SHAPES} entry that claims it. */
	private boolean dispatchInlineAutoAbility(AutoAbility fa, CardData source, boolean isP1,
			boolean effectIsP1) {
		for (InlineShape s : INLINE_SHAPES) {
			Matcher m = s.pattern().matcher(fa.effectText());
			if (m.find()) {
				s.handler().run(this, fa, source, isP1, effectIsP1, m);
				return true;
			}
		}
		return false;
	}

	/**
	 * Runs {@code body} with {@code source} standing as the ability source, restoring whatever was
	 * there before. Every effect that asks "who is dealing this?" reads
	 * {@link MainWindow#currentAbilitySource} — the self outgoing boosts and doublers in
	 * {@link DamageResolver}, the damage credit the "damaged by [X]" printings need, and the card
	 * name the logs and dialogs label an effect with — so an ability that resolves without it
	 * silently loses all of them. Saved and restored rather than cleared, because these resolutions
	 * nest.
	 */
	private boolean withAbilitySource(CardData source, BooleanSupplier body) {
		CardData prevSource  = mw.currentAbilitySource;
		boolean  prevSpecial = mw.currentAbilityIsSpecial;
		mw.currentAbilitySource    = source;
		mw.currentAbilityIsSpecial = false;
		int prevSerial = mw.resolutionSerial;
		mw.resolutionSerial = mw.nextResolutionSerial();
		try {
			return body.getAsBoolean();
		} finally {
			mw.currentAbilitySource    = prevSource;
			mw.currentAbilityIsSpecial = prevSpecial;
			mw.resolutionSerial        = prevSerial;
		}
	}

	/**
	 * 15-061H Lehko Habhoka and 22-122L Tidus. The gate is settled here; past it, the rest is an
	 * optional cost this table already pays ("put Lehko Habhoka into the Break Zone. If you do so,
	 * …", "pay 《Water》《Water》《Water》. When you do so, …"), so it is handed back to the table
	 * rather than resolved here. A remainder the table does not claim is left unread rather than
	 * run without its cost.
	 */
	private void executeConditionGateMayAutoAbility(AutoAbility fa, CardData source,
			boolean isP1, boolean effectIsP1, Matcher m) {
		boolean met;
		String  why;
		if (m.group("castmin") != null) {
			int cast = mw.turn(isP1).cardsCastThisTurn;
			int min  = Integer.parseInt(m.group("castmin"));
			int max  = m.group("castmax") != null ? Integer.parseInt(m.group("castmax")) : Integer.MAX_VALUE;
			met = cast >= min && cast <= max;
			why = cast + " card(s) cast this turn";
		} else {
			String types = m.group("ctrltypes").toLowerCase(Locale.ROOT);
			boolean any = types.contains("character");
			int count = 0;
			if (any || types.contains("forward"))
				for (CardData c : isP1 ? mw.p1ForwardCards : mw.p2ForwardCards) if (c != null) count++;
			if (any || types.contains("backup"))
				for (CardData c : isP1 ? mw.p1BackupCards : mw.p2BackupCards) if (c != null) count++;
			if (any || types.contains("monster"))
				for (CardData c : isP1 ? mw.p1MonsterCards : mw.p2MonsterCards) if (c != null) count++;
			met = count >= Integer.parseInt(m.group("ctrlmin"));
			why = "controls " + count + " " + m.group("ctrltypes");
		}
		if (!met) {
			mw.logEntry("[AutoAbility] " + source.name() + " — " + why + "; condition not met");
			return;
		}
		AutoAbility rest = fa.withOptionalEffectText(m.group("rest").trim());
		if (!dispatchInlineAutoAbility(rest, source, isP1, effectIsP1))
			mw.logEntry("[AutoAbility] Unrecognized effect after condition: " + rest.effectText());
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

		if (!acceptsOptional(fa, isP1, source.name() + " — " + optionalPrompt(fa, fa.effectText()),
				() -> aiAccepts("optional ability"))) {
			mw.logEntry("[AutoAbility] " + source.name() + " — optional effect declined");
			return;
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

		if (!acceptsOptional(fa, isP1, source.name() + " — " + optionalPrompt(fa, fa.effectText()),
				() -> aiAccepts("optional ability"))) {
			mw.logEntry("[AutoAbility] " + source.name() + " — optional effect declined");
			return;
		}

		// Select the card(s) to remove from the field
		GameContext ctx = mw.buildGameContext(effectIsP1);
		String element = m.group("element");
		String except  = m.group("except") != null ? m.group("except").trim() : null;
		java.util.List<ForwardTarget> targets = ctx.selectCharacters(count, false,
				opponentOnly, selfOnly, null, element, -1, null, -1, null,
				inclForwards, inclBackups, inclMonsters, null, null, null, except, false, null, withoutMulticard);
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

		if (!acceptsOptional(fa, isP1, source.name() + " — " + optionalPrompt(fa, fa.effectText()),
				() -> aiAccepts("optional ability"))) {
			mw.logEntry("[AutoAbility] " + source.name() + " — optional effect declined");
			return;
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

		// Read before anything is paid: a payoff nothing reads leaves the card where it is rather
		// than breaking it for no effect (5-008R Grenade and 5-106R Black Knight, which are not
		// optional, used to do exactly that on every trigger).
		Consumer<GameContext> effect = readSelfSacrificePayoff(subEffect, source);
		if (effect == null) {
			mw.logEntry("[AutoAbility] " + source.name() + " — unrecognized sub-effect, not paying for it: " + subEffect);
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
		if (!acceptsOptional(fa, isP1, source.name() + " — " + fa.effectText(),
				"Put into Break Zone", "Decline", () -> aiAccepts("self-break for " + source.name()))) {
			mw.logEntry("[AutoAbility] " + source.name() + " — self-break declined");
			return;
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

		mw.logEntry("[AutoAbility] " + source.name() + " — if you do so: " + subEffect);
		effect.accept(mw.buildGameContext(effectIsP1));
	}

	/**
	 * The payoff of "put [Self] into the Break Zone. If/When you do so, [payoff]".
	 *
	 * <p>Every printing whose first payoff sentence says "it" or "that Forward" is a watcher of a
	 * card entering the field — 5-008R Grenade, 5-106R Black Knight, 19-008R Buffasaur, 26-031H Cid
	 * Raines, 28-010R Jack Garland — and the source is already in the Break Zone, so the card meant
	 * is the one that arrived. That sentence is read here, against the arriving card, and never
	 * reaches {@link ActionResolver#parse}, whose chain reads "it" as a card a Choose picked (see
	 * {@link ActionResolverPatterns#TRIGGERED_TARGET_ACTION_BARE}). Any sentences after it parse as
	 * usual and must all be read, so Buffasaur's self-damage cannot run without its 8000.
	 *
	 * <p>{@code null} when any part is unread, or when no arriving card stands behind the trigger.
	 */
	private Consumer<GameContext> readSelfSacrificePayoff(String sub, CardData source) {
		String[] parts = sub.trim().split("(?<=[.!])\\s+(?=[A-Z])", 2);
		Matcher em = SACRIFICE_PAYOFF_ON_ARRIVAL.matcher(parts[0].trim());
		if (!em.matches()) return ActionResolver.parse(sub, source);

		// Set by the enters-field watchers around the dispatch. Absent (a trigger resolved from a
		// batch, or some other event), the payoff is declined rather than guessed at.
		CardData arrived = mw.triggeringEnteredCard;
		if (arrived == null) return null;
		boolean arrivedIsP1 = Boolean.TRUE.equals(mw.gameState.getIdentity().get(arrived));
		if (enteringCardTarget(arrived, arrivedIsP1) == null) return null;
		BiConsumer<GameContext, List<ForwardTarget>> onArrival = ActionResolver.parseTargetAction(arrivalAction(em), 0);
		if (onArrival == null) return null;
		Consumer<GameContext> rest = null;
		if (parts.length > 1) {
			rest = ActionResolver.parse(parts[1].trim(), source);
			if (rest == null) return null;
		}
		Consumer<GameContext> then = rest;
		return ctx -> {
			// Looked up when the payoff runs, after the source has left: it may have shared a row.
			ForwardTarget t = enteringCardTarget(arrived, arrivedIsP1);
			if (t == null) ctx.logEntry(arrived.name() + " is no longer on the field");
			else onArrival.accept(ctx, List.of(t));
			if (then != null) then.accept(ctx);
		};
	}

	/**
	 * The first sentence of a self-sacrifice payoff that acts on the card whose arrival fired the
	 * trigger: "deal it 8000 damage", "break it", "deal that Forward 9000 damage". Group
	 * {@code verb} is the verb, and {@code tail} whatever follows the object.
	 */
	private static final Pattern SACRIFICE_PAYOFF_ON_ARRIVAL = Pattern.compile(
			"(?i)^(?<verb>deal|break)\\s+(?:it|that\\s+(?:Forward|Character))(?<tail>\\s+\\d+\\s+damage)?\\s*[.!]?$");

	/**
	 * "Choose 1 &lt;target&gt;. You may put 1 &lt;price&gt; into the Break Zone. If you do so,
	 * &lt;payoff&gt;." — 4-087R Delita and 7-020C Lulu.
	 *
	 * <p>Three steps in the order the card prints them, which is also the order they depend on each
	 * other: the target is chosen first and unconditionally (it is not part of the offer, and
	 * "when chosen by abilities" watchers fire off it either way), the price is then offered, and
	 * the payoff lands only if the price was paid.
	 *
	 * <p>Delita's "of the same cost" is read against the card just chosen, so the price cannot be
	 * filtered until the choice is made — which is why the offer cannot be hoisted above it.
	 *
	 * <p>The chosen card is re-located by identity after the price is paid rather than reusing the
	 * target picked up front: paying can move slots underneath it, and a stale index would land the
	 * payoff on whatever slid into that seat.
	 */
	private void executeChooseThenMayPutIntoBzAutoAbility(AutoAbility fa, CardData source,
			boolean isP1, boolean effectIsP1, Matcher m) {
		String subEffect = m.group("sub").trim();
		BiConsumer<GameContext, List<ForwardTarget>> payoff =
				ActionResolver.parseFormerLatterGroupAction(subEffect);
		if (payoff == null) {
			mw.logEntry("[AutoAbility] Unrecognized sub-effect: " + subEffect);
			return;
		}

		// The price always comes off your own field. Both printings say so, one with "1 of your
		// Forwards" and one with "1 Backup ... you control"; a text stating neither is not this
		// effect, and guessing an ownership would let the ability eat the opponent's board.
		if (m.group("ofyour") == null && m.group("youcontrol") == null) {
			mw.logEntry("[AutoAbility] " + source.name() + " — price does not say whose field it comes from, skipping");
			return;
		}

		int     count        = Integer.parseInt(m.group("count"));
		String  tgtType      = m.group("tgttype").toLowerCase(java.util.Locale.ROOT);
		String  tgtCtl       = m.group("tgtctl");
		boolean tgtOpponent  = tgtCtl != null && tgtCtl.toLowerCase(java.util.Locale.ROOT).contains("opponent");
		boolean tgtSelf      = tgtCtl != null && !tgtOpponent;

		GameContext ctx = mw.buildGameContext(effectIsP1);
		List<ForwardTarget> chosen = ctx.selectCharacters(count, false, tgtOpponent, tgtSelf,
				null, null, -1, null, -1, null,
				includesRow(tgtType, "forward"), includesRow(tgtType, "backup"), includesRow(tgtType, "monster"),
				null, null, null, null, false, null, false);
		if (chosen.isEmpty()) {
			mw.logEntry("[AutoAbility] " + source.name() + " — nothing to choose, ability does nothing");
			return;
		}
		List<CardData> chosenCards = new ArrayList<>();
		List<Boolean>  chosenSides = new ArrayList<>();
		for (ForwardTarget t : chosen) { chosenCards.add(ctx.targetCard(t)); chosenSides.add(t.isP1()); }

		// Delita's "of the same cost" — the cost of the card just chosen, not of Delita.
		int priceCost = m.group("samecost") != null ? chosenCards.get(0).cost() : -1;

		int     priceCount = Integer.parseInt(m.group("price"));
		String  priceType  = m.group("pricetype").toLowerCase(java.util.Locale.ROOT);
		String  condition  = m.group("active") != null ? "active" : null;
		String  element    = m.group("element") != null ? m.group("element").trim() : null;
		String  exclude    = m.group("excludename") != null ? m.group("excludename").trim() : null;
		boolean priceFwd   = includesRow(priceType, "forward");
		boolean priceBkp   = includesRow(priceType, "backup");
		boolean priceMon   = includesRow(priceType, "monster");

		// The offer belongs to the ability's controller; the AI takes the trade, the same answer
		// every other optional cost in this class gives it.
		//
		// Asked before the price is picked rather than after checking that a price exists, which
		// is the order the sibling executors use: the selection below reports an empty pick as a
		// skipped payoff, so an accepted offer with nothing to hand over costs nothing.
		String offer = source.name() + " — " + fa.effectText();
		if (!mw.decideYesNo(effectIsP1, "Waiting for your opponent to decide: " + offer,
				() -> mw.showEffectOptionDialog(offer, "Auto Ability",
						new Object[]{"Put into Break Zone", "Decline"}) == 0,
				() -> aiAccepts("optional cost for " + source.name()))) {
			mw.logEntry("[AutoAbility] " + source.name() + " — optional cost declined, payoff skipped");
			return;
		}

		GameContext priceCtx = mw.buildGameContext(effectIsP1);
		List<ForwardTarget> price = priceCtx.selectCharacters(priceCount, false, false, true,
				condition, element, priceCost, null, -1, null,
				priceFwd, priceBkp, priceMon, null, null, null, exclude, false, null, false);
		if (price.isEmpty()) {
			mw.logEntry("[AutoAbility] " + source.name() + " — no card handed over, payoff skipped");
			return;
		}
		GameContext payCtx = mw.buildGameContext(effectIsP1);
		price.forEach(t -> payCtx.forceTargetToBreakZone(t));

		GameContext payoffCtx = mw.buildGameContext(effectIsP1);
		List<ForwardTarget> stillThere = new ArrayList<>();
		for (int k = 0; k < chosenCards.size(); k++) {
			ForwardTarget slot = findFieldTarget(chosenCards.get(k), chosenSides.get(k));
			if (slot != null) stillThere.add(slot);
		}
		if (stillThere.isEmpty()) {
			mw.logEntry("[AutoAbility] " + source.name() + " — chosen card has left the field, payoff skipped");
			return;
		}
		mw.logEntry("[AutoAbility] " + source.name() + " — if you do so: " + subEffect);
		payoff.accept(payoffCtx, stillThere);
	}

	/** Whether a printed card-type word covers {@code row}; "Character" covers all three. */
	private static boolean includesRow(String typeLower, String row) {
		return typeLower.startsWith(row) || typeLower.startsWith("character");
	}

	private void executeRevealSummonsConditionalAutoAbility(AutoAbility fa, CardData source,
			boolean isP1, boolean effectIsP1, Matcher m) {
		String effect0 = m.group("effect0").trim();
		int    minN    = Integer.parseInt(m.group("n"));
		String effectN = m.group("effectN").trim();

		List<CardData> hand = effectIsP1 ? mw.gameState.getP1Hand() : mw.gameState.getP2Hand();
		List<CardData> summonsInHand = new ArrayList<>();
		for (CardData c : hand) if (c.isSummon()) summonsInHand.add(c);

		List<CardData> revealed;
		if (summonsInHand.isEmpty()) {
			// Nothing to choose, so nobody is asked: revealing none is the only reveal there is, and
			// both clients read that off the same hand.
			mw.logEntry("[AutoAbility] " + source.name() + " — no Summons in hand, reveals 0");
			revealed = Collections.emptyList();
		} else {
			if (!acceptsOptional(fa, isP1, source.name() + " — " + optionalPrompt(fa, fa.effectText()),
					"Reveal...", "Decline", () -> true)) {
				mw.logEntry("[AutoAbility] " + source.name() + " — optional effect declined");
				return;
			}
			// CPU logic: reveal 1 if fewer than minN are held, else exactly minN.
			revealed = revealSummons(effectIsP1, hand, summonsInHand,
					() -> mw.showRevealSummonsFromHandDialog(summonsInHand, source.name(), minN),
					() -> new ArrayList<>(summonsInHand.subList(0,
							summonsInHand.size() < minN ? 1 : minN)),
					source);
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
	 * The seat at {@code revealerIsP1} reveals any number of {@code summons} from their own
	 * {@code hand}; returns the cards shown. Crosses as {@link ChoiceKind#REVEAL_HAND} hand indices.
	 *
	 * @param localPick asks the local human, answering with cards out of {@code summons}
	 * @param cpuPick   the AI's reveal, out of {@code summons}
	 */
	private List<CardData> revealSummons(boolean revealerIsP1, List<CardData> hand, List<CardData> summons,
			Supplier<List<CardData>> localPick, Supplier<List<CardData>> cpuPick, CardData source) {
		List<Integer> eligible = new ArrayList<>();
		for (CardData c : summons) eligible.add(MainWindow.identityIndexOf(hand, c));
		List<Integer> shown = mw.decide(PlayerChoice.by(revealerIsP1, ChoiceKind.REVEAL_HAND)
				.prompting("Waiting for your opponent to reveal Summons...")
				.locally(() -> handIndicesOf(hand, localPick.get()))
				.byCpu(() -> handIndicesOf(hand, cpuPick.get()))
				.legalWhen(a -> eligible.containsAll(a) && new HashSet<>(a).size() == a.size(),
						"only Summons in their hand can be revealed here"));
		List<CardData> revealed = new ArrayList<>(shown.size());
		for (int i : shown) revealed.add(hand.get(i));
		mw.noteShownInHand(revealerIsP1, revealed);
		mw.logEntry("[AutoAbility] " + (revealerIsP1 ? "" : "[P2] ") + source.name() + " — reveals "
				+ revealed.size() + " Summon(s)"
				+ (revealed.isEmpty() ? "" : ": " + revealed.stream().map(CardData::name)
						.collect(java.util.stream.Collectors.joining(", "))));
		return revealed;
	}

	/** Where each of {@code cards} sits in {@code hand}, by identity; cards not there are dropped. */
	private static List<Integer> handIndicesOf(List<CardData> hand, List<CardData> cards) {
		List<Integer> out = new ArrayList<>();
		if (cards == null) return out;
		for (CardData c : cards) {
			int i = MainWindow.identityIndexOf(hand, c);
			if (i >= 0 && !out.contains(i)) out.add(i);
		}
		return out;
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

		if (summonsInHand.isEmpty()) {
			mw.logEntry("[AutoAbility] " + source.name() + " — no Summons in hand, reveals 0");
			return;
		}
		if (!acceptsOptional(fa, isP1, source.name() + " — " + optionalPrompt(fa, fa.effectText()),
				"Reveal...", "Decline", () -> true)) {
			mw.logEntry("[AutoAbility] " + source.name() + " — optional effect declined");
			return;
		}
		// The AI reveals everything it can: the count is the target count, and a revealed Summon
		// stays in hand, so there is nothing to weigh against taking the maximum.
		List<CardData> revealed = revealSummons(effectIsP1, hand, summonsInHand,
				() -> mw.showRevealSummonsFromHandDialog(summonsInHand, source.name(),
						"Reveal any number — you then get that many targets."),
				() -> new ArrayList<>(summonsInHand),
				source);

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

	/**
	 * Prices a {@link #FA_PAY_WHEN_DO_SO} cost run as {@code {fixedCp, cpPerUnitOfX}}, or
	 * {@code null} when it holds a token the payment dialog cannot charge (《C》 for a Crystal).
	 *
	 * <p>Every printing but one is a single token, and a lone 《X》 prices at one CP per unit — the
	 * reading the single-token code had. 25-057R Cutter prints 《X》《X》, two CP for each 1 of X.
	 * An element token counts as the one CP it is here; which element is
	 * {@link #payRunElementNeeds}'s to say.
	 */
	static int[] tallyPayRun(String costRun) {
		int fixedCp = 0, cpPerUnitOfX = 0;
		Matcher tok = FA_COST_TOKEN.matcher(costRun);
		while (tok.find()) {
			String costToken = tok.group(1).trim();
			if (costToken.equalsIgnoreCase("X")) { cpPerUnitOfX++; continue; }
			String lower = costToken.toLowerCase(Locale.ROOT);
			if (ELEMENT_NAMES.stream().anyMatch(lower::contains)) { fixedCp++; continue; }
			try { fixedCp += Integer.parseInt(costToken); }
			catch (NumberFormatException e) { return null; }
		}
		return new int[]{ fixedCp, cpPerUnitOfX };
	}

	/**
	 * The Element CP a cost run insists on, e.g. {@code {"Fire": 1}} for 《Fire》, in printed order;
	 * empty when every token is generic or 《X》. The payment has to include these, not merely add
	 * up to {@link #tallyPayRun}'s total.
	 */
	static Map<String, Integer> payRunElementNeeds(String costRun) {
		Map<String, Integer> needs = new LinkedHashMap<>();
		Matcher tok = FA_COST_TOKEN.matcher(costRun);
		while (tok.find()) {
			String costToken = tok.group(1).trim();
			if (ELEMENT_NAMES.contains(costToken.toLowerCase(Locale.ROOT))) needs.merge(costToken, 1, Integer::sum);
		}
		return needs;
	}

	/**
	 * The X that {@code paid} CP buys, given a cost run of {@code fixedCp} plus {@code cpPerUnitOfX}
	 * for each 1 of X. CP left over after the fixed part and the whole units is overpayment and
	 * buys nothing. A run with no 《X》 in it passes the amount straight through, which is what the
	 * sub-effects of the fixed-cost printings are resolved with.
	 */
	static int xBoughtBy(int paid, int fixedCp, int cpPerUnitOfX) {
		if (cpPerUnitOfX == 0) return paid;
		return Math.max(0, paid - fixedCp) / cpPerUnitOfX;
	}

	/**
	 * A rule on which cards may produce the CP paid for 《X》, as a test of the card producing it —
	 * the Backup dulled or the card discarded. {@code null} when the text states none.
	 * <ul>
	 *   <li>"You can only use Ice CP to pay 《X》." — 17-020R Montblanc;</li>
	 *   <li>"You can only pay 《X》 with CP produced by Job The Twelve Backups and/or discarding Job
	 *       The Twelve cards." — 26-040R Menphina.</li>
	 * </ul>
	 */
	static Predicate<CardData> xPaymentSource(String text) {
		Matcher elem = FA_X_ONLY_ELEMENT_CP.matcher(text);
		if (elem.find()) {
			String e = elem.group("elem");
			return c -> c.containsElement(e);
		}
		Matcher job = FA_X_ONLY_JOB_CP.matcher(text);
		if (job.find() && job.group("job").trim().equalsIgnoreCase(job.group("job2").trim())) {
			String j = job.group("job").trim();
			return c -> c.hasJob(j);
		}
		return null;
	}

	/** {@code text} without the sentence {@link #xPaymentSource} reads, which is enforced at payment. */
	static String withoutXPaymentSource(String text) {
		String t = FA_X_ONLY_ELEMENT_CP.matcher(text).replaceAll("");
		return FA_X_ONLY_JOB_CP.matcher(t).replaceAll("").trim();
	}

	private static final Pattern FA_X_ONLY_ELEMENT_CP = Pattern.compile(
			"(?i)\\s*You\\s+can\\s+only\\s+use\\s+(?<elem>Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)"
			+ "\\s+CP\\s+to\\s+pay\\s+《X》[.!]?");

	private static final Pattern FA_X_ONLY_JOB_CP = Pattern.compile(
			"(?i)\\s*You\\s+can\\s+only\\s+pay\\s+《X》\\s+with\\s+CP\\s+produced\\s+by\\s+Job\\s+(?<job>.+?)"
			+ "\\s+Backups\\s+and/or\\s+discarding\\s+Job\\s+(?<job2>.+?)\\s+cards[.!]?");

	private void executePayWhenDoSoAutoAbility(AutoAbility fa, CardData source, boolean isP1,
			boolean effectIsP1, Matcher payM) {
		executePayWhenDoSoAutoAbility(fa, source, isP1, effectIsP1, payM, null);
	}

	/**
	 * As above, with {@code heldBack} naming the cards the CP may come from when the payoff still
	 * needs one of the payer's hand cards — 20-102L Mira's Monster, which discarding for CP would
	 * spend before the discard that follows could take it. {@code null} holds nothing back.
	 */
	private void executePayWhenDoSoAutoAbility(AutoAbility fa, CardData source, boolean isP1,
			boolean effectIsP1, Matcher payM, Predicate<CardData> heldBack) {
		String costRun   = payM.group(1).trim();
		Predicate<CardData> xSource = xPaymentSource(payM.group(2));
		String subEffect = withoutXPaymentSource(payM.group(2).trim()).replaceAll("[.!,]+$", "");

		int[] tally = tallyPayRun(costRun);
		if (tally == null) {
			// A token this dialog cannot charge (e.g. 《C》 for crystal) — resolve normally.
			Consumer<GameContext> effect = ActionResolver.parse(fa.effectText(), source);
			if (effect != null) { mw.logEntry("[AutoAbility] " + source.name() + " — " + fa.effectText()); effect.accept(mw.buildGameContext(effectIsP1)); }
			else mw.logEntry("[AutoAbility] Unrecognized effect: " + fa.effectText());
			return;
		}
		final int fixedCost = tally[0];
		final int xPerUnit  = tally[1];
		final boolean isXCost = xPerUnit > 0;
		// The rule covers 《X》 only. Both printings pay nothing else, so the whole payment is X; a
		// run with a fixed part as well would need the two halves sourced apart, which nothing does.
		if (xSource != null && fixedCost > 0) {
			mw.logEntry("[AutoAbility] " + source.name() + " — 《X》 source rule beside a fixed cost is not supported, skipping");
			return;
		}
		Predicate<CardData> cpSource = xSource == null ? heldBack
				: heldBack == null ? xSource : xSource.and(heldBack);

		Matcher maxM = FA_MAX_X.matcher(fa.effectText());
		// "The maximum you can pay for 《X》 is N" bounds X, so the CP ceiling is the fixed part plus
		// N units of it.
		int maxCp = isXCost
				? (maxM.find() ? fixedCost + Integer.parseInt(maxM.group(1)) * xPerUnit : Integer.MAX_VALUE)
				: fixedCost;

		Map<String, Integer> elementNeeds = payRunElementNeeds(costRun);

		// For fixed CP costs, check whether the paying player can actually generate enough CP, of
		// the right Element where the cost names one. effectIsP1 identifies the player who would
		// pay (already accounts for opponentMay). Skip the ability entirely if they cannot.
		if (!isXCost && fixedCost > 0) {
			List<String> tokens = new ArrayList<>();
			elementNeeds.forEach((elem, n) -> tokens.addAll(Collections.nCopies(n, elem)));
			while (tokens.size() < fixedCost) tokens.add("");
			if (!mw.canAffordCpTokens(tokens, fixedCost, effectIsP1)) {
				mw.logEntry("[AutoAbility] " + source.name() + " — cannot afford " + costRun + ", skipping");
				return;
			}
		}

		// The payer is asked, then pays. The AI declines when the effect aims at Forwards and its
		// opponent has none; otherwise it takes the offer.
		String finalSubEffect = subEffect;
		if (!acceptsOptional(fa, isP1, source.name() + " — " + optionalPrompt(fa, fa.effectText()), () -> {
			boolean effectNeedsForward = finalSubEffect.toLowerCase(java.util.Locale.ROOT).contains("forward");
			if (effectNeedsForward && mw.playerForwardCards(!effectIsP1).isEmpty()) {
				mw.logEntry("[AutoAbility] [AI] declines optional ability — no opponent Forwards to target");
				return false;
			}
			return aiAccepts("optional ability");
		})) {
			mw.logEntry("[AutoAbility] " + source.name() + " — optional effect declined");
			return;
		}

		IntUnaryOperator xFromPaid = paid -> xBoughtBy(paid, fixedCost, xPerUnit);
		// The AI buys one unit of X, which is what it has always done for a plain 《X》, and pays
		// nothing when it cannot reach the fixed part.
		int aiTarget = isXCost ? fixedCost + xPerUnit : fixedCost;
		payCpAtResolution(source.name(), fixedCost, maxCp, effectIsP1, 0, elementNeeds, cpSource,
				() -> {
					CpPlan plan = aiPlanCp(effectIsP1, aiTarget, elementNeeds, cpSource, false);
					if (plan == null || plan.produced() < fixedCost) {
						mw.logEntry("[AutoAbility] " + source.name() + " — [AI] could not pay " + costRun);
						return null;
					}
					return plan;
				},
				paid -> applyPayWhenDoSoEffect(finalSubEffect, source, xFromPaid.applyAsInt(paid), effectIsP1), null);
	}

	/**
	 * 20-102L Mira — see {@link #FA_PAY_AND_DISCARD_WHEN_DO_SO}. Both halves are the price, so the
	 * payer must hold a card to discard before any CP is asked for; the discard then runs as the
	 * first step after payment, and the payoff only when it happened.
	 */
	private void executePayAndDiscardWhenDoSoAutoAbility(AutoAbility fa, CardData source, boolean isP1,
			boolean effectIsP1, Matcher m) {
		String type = m.group(2);
		CardData kept = mw.playerHand(effectIsP1).stream()
				.filter(c -> CardFilters.matchesDiscardType(c, type)).findFirst().orElse(null);
		if (kept == null) {
			mw.logEntry("[AutoAbility] " + source.name() + " — no " + type + " in hand to discard, skipping");
			return;
		}
		String payoff = payAndDiscardPayoff(m.group(3));
		String rewritten = "pay " + m.group(1) + ". When you do so, discard 1 " + type
				+ ". When you do so, " + payoff;
		Matcher payM = FA_PAY_WHEN_DO_SO.matcher(rewritten);
		if (!payM.matches()) return;
		// One of the Monsters is held back from the CP payment, so paying cannot spend the card
		// the discard needs. Any others may still be discarded for CP.
		executePayWhenDoSoAutoAbility(fa, source, isP1, effectIsP1, payM, c -> c != kept);
	}

	/**
	 * 25-010H Salamander (III) — see {@link #FA_PAY_OR_CRYSTALS_WHEN_DO_SO}. Offers whichever of
	 * the two prices the payer can meet; the AI spends CP when it can and keeps its Crystals.
	 */
	private void executePayOrCrystalsWhenDoSoAutoAbility(AutoAbility fa, CardData source, boolean isP1,
			boolean effectIsP1, Matcher m) {
		String costRun   = m.group(1).trim();
		int    crystals  = m.group(2).split("》", -1).length - 1;
		String subEffect = m.group(3).trim().replaceAll("[.!,]+$", "");
		int[] tally = tallyPayRun(costRun);
		if (tally == null || tally[1] > 0) {
			mw.logEntry("[AutoAbility] Unrecognized cost run: " + costRun);
			return;
		}
		int fixedCost = tally[0];
		Map<String, Integer> elementNeeds = payRunElementNeeds(costRun);
		List<String> tokens = new ArrayList<>();
		elementNeeds.forEach((elem, n) -> tokens.addAll(Collections.nCopies(n, elem)));
		while (tokens.size() < fixedCost) tokens.add("");
		boolean canCp      = mw.canAffordCpTokens(tokens, fixedCost, effectIsP1);
		int     held       = effectIsP1 ? mw.gameState.getP1Crystals() : mw.gameState.getP2Crystals();
		boolean canCrystal = held >= crystals;
		if (!canCp && !canCrystal) {
			mw.logEntry("[AutoAbility] " + source.name() + " — cannot pay " + costRun + " or " + crystals + " Crystal(s)");
			return;
		}

		// Whoever pays chooses how; effectIsP1 is the payer, "your opponent may" included. Three
		// answers — CP, Crystals, decline — as a position in the options offered, which both
		// clients build from the same board. The AI spends CP when it can and keeps its Crystals.
		List<String> options = new ArrayList<>();
		if (canCp)      options.add("Pay " + costRun);
		if (canCrystal) options.add("Pay " + crystals + " Crystal" + (crystals == 1 ? "" : "s"));
		options.add("Decline");
		int choice = mw.decideOption(effectIsP1, options.size(),
				"Waiting for your opponent to decide how to pay for " + source.name() + "...",
				() -> mw.showEffectOptionDialog(source.name() + " — " + fa.effectText(),
						"Auto Ability", options.toArray()),
				() -> {
					if (subEffect.toLowerCase(Locale.ROOT).contains("forward")
							&& mw.playerForwardCards(!effectIsP1).isEmpty()) {
						mw.logEntry("[AutoAbility] [AI] declines optional ability — no opponent Forwards to target");
						return options.size() - 1;
					}
					return 0;
				});
		if (choice < 0 || choice == options.size() - 1) {
			mw.logEntry("[AutoAbility] " + source.name() + " — optional effect declined");
			return;
		}

		if (options.get(choice).contains("Crystal")) {
			mw.playerSpendCrystals(effectIsP1, crystals);
			mw.refreshCrystalDisplays();
			mw.logEntry((effectIsP1 ? "" : "[P2] ") + "Paid " + crystals + " Crystal(s)");
			applyPayWhenDoSoEffect(subEffect, source, 0, effectIsP1);
		} else {
			payCpAtResolution(source.name(), fixedCost, fixedCost, effectIsP1, 0, elementNeeds, null,
					() -> {
						CpPlan plan = aiPlanCp(effectIsP1, fixedCost, elementNeeds, null, true);
						if (plan == null) mw.logEntry("[AutoAbility] " + source.name() + " — [AI] could not pay " + costRun);
						return plan;
					},
					paid -> applyPayWhenDoSoEffect(subEffect, source, 0, effectIsP1), null);
		}
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
		// "… break that Forward" — 20-102L Mira: the payoff names the card whose arrival fired the
		// trigger, so it stands as the preloaded target, as for the self-sacrifice shapes.
		if (effect != null && mw.triggeringEnteredCard != null
				&& REFERS_TO_ENTERING_CARD.matcher(subEffect).find()) {
			// Either side: Mira watches the opponent's field.
			ForwardTarget entered = enteringCardTarget(mw.triggeringEnteredCard, true);
			if (entered == null) entered = enteringCardTarget(mw.triggeringEnteredCard, false);
			if (entered == null) {
				mw.logEntry("[AutoAbility] " + source.name() + " — entering card no longer on field; skipped");
				return;
			}
			ctx.preloadTargets(List.of(entered));
		}
		if (effect == null) {
			// "Until the end of the turn, it gains …" — 13-009H Selphie, whose "it" is the Forward
			// whose arrival fired the trigger. Preloaded here rather than by the caller because this
			// is where the wording is recognised; the cost has already been charged by the time a
			// sub-effect is read, so a grant left unwired would be paid for and dropped.
			effect = ActionResolver.parsePayGatedFollowup(subEffect, source, xValue);
			if (effect != null) {
				ForwardTarget entered = mw.triggeringEnteredCard != null
						? enteringCardTarget(mw.triggeringEnteredCard, effectIsP1) : null;
				if (entered == null) {
					mw.logEntry("[AutoAbility] " + source.name()
							+ " — entering card no longer on field; skipped");
					return;
				}
				ctx.preloadTargets(List.of(entered));
			}
		}
		if (effect == null) {
			// A sub-effect measured in X has nothing to do at X = 0, and declines rather than
			// resolving as an empty one. That is a payment of nothing, not an unread ability.
			if (xValue == 0 && subEffect.matches("(?i).*\\bX\\b.*"))
				mw.logEntry("[AutoAbility] " + source.name() + " — nothing paid for 《X》; no effect");
			else
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
		CpPlan plan = aiPlanCp(payerIsP1, target);
		applyCpPlan(payerIsP1, plan, "[AI] Pay CP: ");
		return Math.min(plan.produced(), target);
	}

	/**
	 * Which Backups a payer dulls and which hand cards they discard to produce CP — a payment
	 * decided but not yet made. {@code crystals} stands for the whole cost paid in Crystals instead,
	 * where a cost offers that.
	 *
	 * <p>Kept apart from the spending because the two happen on different clients' say-so: the
	 * payer decides, and both clients spend what was decided.
	 */
	record CpPlan(boolean crystals, List<Integer> dulls, List<Integer> discards) {
		static final CpPlan CRYSTALS = new CpPlan(true, List.of(), List.of());

		static CpPlan of(List<Integer> dulls, List<Integer> discards) {
			return new CpPlan(false, List.copyOf(dulls), List.copyOf(discards));
		}

		/** CP this plan produces: 1 per Backup dulled, 2 per card discarded. */
		int produced() { return dulls.size() + discards.size() * 2; }

		/** The {@link ChoiceKind#CP_PAYMENT} answer: {@code [-1]}, or {@code [n, dulls…, discards…]}. */
		List<Integer> toAnswer() {
			if (crystals) return List.of(-1);
			List<Integer> out = new ArrayList<>(1 + dulls.size() + discards.size());
			out.add(dulls.size());
			out.addAll(dulls);
			out.addAll(discards);
			return out;
		}

		/** Reads back a {@link #toAnswer}; {@code null} for an empty answer — a payment declined. */
		static CpPlan fromAnswer(List<Integer> answer) {
			if (answer.isEmpty()) return null;
			if (answer.size() == 1 && answer.get(0) == -1) return CRYSTALS;
			int n = answer.get(0);
			if (n < 0 || n > answer.size() - 1) return null;
			return of(answer.subList(1, 1 + n), answer.subList(1 + n, answer.size()));
		}
	}

	/** The plain plan {@link #aiPayCp(boolean, int)} spends: active Backups in slot order, then hand cards from the end. */
	private CpPlan aiPlanCp(boolean payerIsP1, int target) {
		CardData[]  bkpCards  = mw.playerBackupCards(payerIsP1);
		CardState[] bkpStates = mw.playerBackupStates(payerIsP1);
		List<Integer> dulls = new ArrayList<>();
		int planned = 0;
		for (int i = 0; i < bkpCards.length && planned < target; i++) {
			if (bkpCards[i] != null && bkpStates[i] == CardState.ACTIVE) { dulls.add(i); planned++; }
		}
		List<Integer> discards = new ArrayList<>();
		List<CardData> hand = mw.playerHand(payerIsP1);
		for (int i = hand.size() - 1; i >= 0 && planned < target; i--) { discards.add(i); planned += 2; }
		return CpPlan.of(dulls, discards);
	}

	/**
	 * Spends {@code plan} from {@code payerIsP1}'s side: dulls its Backups, then discards its hand
	 * cards highest index first so each index still names the card it was picked as. Returns the
	 * CP produced. The Crystal route is left to the caller, which knows how many to spend.
	 */
	private int applyCpPlan(boolean payerIsP1, CpPlan plan, String logPrefix) {
		CardData[]  bkpCards  = mw.playerBackupCards(payerIsP1);
		CardState[] bkpStates = mw.playerBackupStates(payerIsP1);
		for (int i : plan.dulls()) {
			bkpStates[i] = CardState.DULL;
			mw.playerDullBackupSlot(payerIsP1, i);
			mw.logEntry(logPrefix + "dull " + bkpCards[i].name());
		}
		List<CardData> hand = mw.playerHand(payerIsP1);
		List<Integer> discards = new ArrayList<>(plan.discards());
		discards.sort(Comparator.reverseOrder());
		for (int di : discards) {
			mw.logEntry(logPrefix + "discard " + hand.get(di).name() + " from hand");
			mw.playerBreakFromHand(payerIsP1, di);
		}
		return plan.produced();
	}

	/**
	 * {@link #aiPayCp(boolean, int)} with per-element minimums ({@code {"Fire": 1}} for 《Fire》),
	 * the AI half of {@link #showAutoAbilityPaymentDialog}'s element check. The whole payment is
	 * planned before anything is dulled or discarded: element needs first, from matching Backups and
	 * then matching hand cards, the rest of {@code target} from whatever is left. If an element
	 * cannot be covered nothing is spent and 0 is returned — the cost is not partly payable.
	 */
	int aiPayCp(boolean payerIsP1, int target, Map<String, Integer> elementNeeds) {
		return aiPayCp(payerIsP1, target, elementNeeds, null);
	}

	/**
	 * As above, with {@code cpSource} limiting which Backups may be dulled and which cards discarded
	 * ({@link #xPaymentSource}); {@code null} for any.
	 */
	int aiPayCp(boolean payerIsP1, int target, Map<String, Integer> elementNeeds, Predicate<CardData> cpSource) {
		return aiPayCp(payerIsP1, target, elementNeeds, cpSource, false);
	}

	/**
	 * As above; with {@code allOrNothing}, a plan short of {@code target} spends nothing and returns
	 * 0. The optional-cost payers want that — a cost they cannot meet in full buys nothing — where an
	 * 《X》 payment keeps what it could buy.
	 */
	int aiPayCp(boolean payerIsP1, int target, Map<String, Integer> elementNeeds, Predicate<CardData> cpSource,
			boolean allOrNothing) {
		if (elementNeeds.isEmpty() && cpSource == null && !allOrNothing) return aiPayCp(payerIsP1, target);
		CpPlan plan = aiPlanCp(payerIsP1, target, elementNeeds, cpSource, allOrNothing);
		if (plan == null) return 0;
		applyCpPlan(payerIsP1, plan, "[AI] Pay CP: ");
		return Math.min(plan.produced(), target);
	}

	/**
	 * The payment {@link #aiPayCp(boolean, int, Map, Predicate, boolean)} would make, without
	 * making it; {@code null} when it would pay nothing — an Element it cannot produce, or, with
	 * {@code allOrNothing}, a plan short of {@code target}.
	 */
	CpPlan aiPlanCp(boolean payerIsP1, int target, Map<String, Integer> elementNeeds, Predicate<CardData> cpSource,
			boolean allOrNothing) {
		if (elementNeeds.isEmpty() && cpSource == null && !allOrNothing) return aiPlanCp(payerIsP1, target);
		Predicate<CardData> may = cpSource != null ? cpSource : c -> true;
		CardData[]     bkpCards  = mw.playerBackupCards(payerIsP1);
		CardState[]    bkpStates = mw.playerBackupStates(payerIsP1);
		List<CardData> hand      = mw.playerHand(payerIsP1);
		List<Integer>  dulls     = new ArrayList<>();
		List<Integer>  discards  = new ArrayList<>();
		int planned = 0;
		for (Map.Entry<String, Integer> need : elementNeeds.entrySet()) {
			int shortBy = need.getValue();
			for (int i = 0; i < bkpCards.length && shortBy > 0; i++) {
				if (bkpCards[i] == null || bkpStates[i] != CardState.ACTIVE || dulls.contains(i)) continue;
				if (!bkpCards[i].containsElement(need.getKey()) || !may.test(bkpCards[i])) continue;
				dulls.add(i); shortBy--; planned++;
			}
			for (int i = hand.size() - 1; i >= 0 && shortBy > 0; i--) {
				if (discards.contains(i) || !hand.get(i).containsElement(need.getKey())) continue;
				if (!CpPaymentUtils.canDiscardForCp(hand.get(i), Set.of()) || !may.test(hand.get(i))) continue;
				discards.add(i); shortBy -= 2; planned += 2;
			}
			if (shortBy > 0) {
				mw.logEntry("[AI] Cannot produce 《" + need.getKey() + "》 — pays nothing");
				return null;
			}
		}
		for (int i = 0; i < bkpCards.length && planned < target; i++) {
			if (bkpCards[i] == null || bkpStates[i] != CardState.ACTIVE || dulls.contains(i)) continue;
			if (!may.test(bkpCards[i])) continue;
			dulls.add(i); planned++;
		}
		for (int i = hand.size() - 1; i >= 0 && planned < target; i--) {
			if (discards.contains(i)) continue;
			if (cpSource != null && (!CpPaymentUtils.canDiscardForCp(hand.get(i), Set.of()) || !may.test(hand.get(i))))
				continue;
			discards.add(i); planned += 2;
		}
		if (allOrNothing && planned < target) {
			mw.logEntry("[AI] Cannot cover 《" + target + "》 — pays nothing");
			return null;
		}
		return CpPlan.of(dulls, discards);
	}

	// ─── "Select N of M following actions" auto-ability ─────────────────────────


	// =========================================================================================
	// Select-following-actions abilities
	// =========================================================================================
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
		// "Select 1 from the following" prints no option count — the menu is the count.
		int     totalCount  = m.group("total") != null ? Integer.parseInt(m.group("total"))
				: ActionResolver.selectFollowingOptions(m.group("actions")).size();

		// youMay / opponentMay decline dialog (the select dialog itself is the interaction,
		// but we still honour an explicit "you may" decline option)
		String prompt = "Select " + (upTo ? "up to " : "") + selectCount + " of "
				+ totalCount + " actions for " + source.name() + "?";
		if (!acceptsOptional(fa, isP1, prompt, "Choose Actions", "Decline",
				() -> aiAccepts("select ability"))) {
			mw.logEntry("[AutoAbility] " + source.name() + " — optional select declined");
			return;
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

		List<String> actions = ActionResolver.selectFollowingOptions(actionsRaw);
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

		// How many to take, from 0 to maxCount — the answer is the number itself, a position in that
		// range. The AI takes them all.
		int max = maxCount;
		int chosenCount = Math.max(0, mw.decideOption(isP1, maxCount + 1,
				"Waiting for your opponent to choose how many actions to take...",
				() -> showChooseActionCountDialog(source, actions, max, excludeElem),
				() -> {
					mw.logEntry("[AutoAbility] [AI] " + source.name() + " takes " + max + " action(s) from top");
					return max;
				}));

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


	// =========================================================================================
	// Activation conditions and payment dialogs
	// =========================================================================================
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
		showAutoAbilityPaymentDialog(cardName, minCp, maxCp, isP1, crystalAltCost, Map.of(),
				onConfirm, onCrystalPaid);
	}

	/**
	 * As above, with per-element minimums: {@code elementNeeds} maps an element to the CP of it the
	 * payment must include ({@code {"Fire": 1}} for 《Fire》). Confirm stays disabled until the
	 * selection meets every one, whatever its total — 28-001R Ursula's 《Fire》 used to accept any
	 * 1 CP. The element CP counts toward {@code minCp}, which is the whole cost.
	 */
	void showAutoAbilityPaymentDialog(String cardName, int minCp, int maxCp,
			boolean isP1, int crystalAltCost, Map<String, Integer> elementNeeds,
			java.util.function.IntConsumer onConfirm, Runnable onCrystalPaid) {
		showAutoAbilityPaymentDialog(cardName, minCp, maxCp, isP1, crystalAltCost, elementNeeds, null,
				onConfirm, onCrystalPaid);
	}

	/**
	 * As above, with {@code cpSource} limiting which cards may produce the CP — the Backup dulled or
	 * the card discarded ({@link #xPaymentSource}); {@code null} for any card.
	 */
	void showAutoAbilityPaymentDialog(String cardName, int minCp, int maxCp,
			boolean isP1, int crystalAltCost, Map<String, Integer> elementNeeds, Predicate<CardData> cpSource,
			java.util.function.IntConsumer onConfirm, Runnable onCrystalPaid) {
		payCpAtResolution(cardName, minCp, maxCp, isP1, crystalAltCost, elementNeeds, cpSource,
				() -> aiCpPaymentPlan(isP1, minCp, crystalAltCost, elementNeeds, cpSource),
				onConfirm, onCrystalPaid);
	}

	/**
	 * The AI's answer to a payment it did not plan for itself: the whole of {@code minCp} if it can
	 * produce it, else the Crystals when the cost offers them and it holds enough, else nothing.
	 * A cost paid for its effect is one the AI takes whenever it can, which is what the optional-
	 * cost payers beside this one already decide.
	 */
	private CpPlan aiCpPaymentPlan(boolean payerIsP1, int minCp, int crystalAltCost,
			Map<String, Integer> elementNeeds, Predicate<CardData> cpSource) {
		CpPlan plan = aiPlanCp(payerIsP1, minCp, elementNeeds, cpSource, true);
		if (plan != null) return plan;
		return crystalAltCost > 0 && mw.playerCrystals(payerIsP1) >= crystalAltCost ? CpPlan.CRYSTALS : null;
	}

	/**
	 * Has the seat at {@code payerIsP1} pay CP as an effect resolves, and spends what they paid on
	 * both clients.
	 *
	 * <p>The payer's own client shows the payment dialog; the other waits for the answer under
	 * {@link ChoiceKind#CP_PAYMENT}; the AI answers with {@code cpuPlan}. Nothing is spent until the
	 * answer is in, and then the same plan is spent on either client — which is what the dialog
	 * alone could not do. It used to dull and discard as it confirmed, so the far client learned
	 * nothing, and it had no AI half at all: a payment the CPU owed was put to the local human, over
	 * the CPU's own Backups and hand.
	 *
	 * @param cpuPlan the AI's payment; {@code null} declines
	 */
	void payCpAtResolution(String cardName, int minCp, int maxCp, boolean payerIsP1, int crystalAltCost,
			Map<String, Integer> elementNeeds, Predicate<CardData> cpSource, Supplier<CpPlan> cpuPlan,
			java.util.function.IntConsumer onConfirm, Runnable onCrystalPaid) {
		List<Integer> answer = mw.decide(PlayerChoice.by(payerIsP1, ChoiceKind.CP_PAYMENT)
				.prompting("Waiting for your opponent to pay for " + cardName + "...")
				.locally(() -> askCpPayment(cardName, minCp, maxCp, payerIsP1, crystalAltCost,
						elementNeeds, cpSource))
				.byCpu(() -> {
					CpPlan plan = cpuPlan.get();
					return plan == null ? List.of() : plan.toAnswer();
				})
				.legalWhen(a -> cpPaymentProblem(a, minCp, payerIsP1, crystalAltCost, elementNeeds,
						cpSource) == null, "that payment does not cover the cost here"));
		CpPlan plan = CpPlan.fromAnswer(answer);
		String who = payerIsP1 ? "" : "[P2] ";
		if (plan == null) {
			mw.logEntry("[AutoAbility] " + who + cardName + " — payment declined");
			return;
		}
		if (plan.crystals()) {
			mw.playerSpendCrystals(payerIsP1, crystalAltCost);
			mw.refreshCrystalDisplays();
			mw.logEntry("[AutoAbility] " + who + cardName + " — paid " + crystalAltCost + " Crystal"
					+ (crystalAltCost == 1 ? "" : "s"));
			if (onCrystalPaid != null) onCrystalPaid.run();
			return;
		}
		int produced = applyCpPlan(payerIsP1, plan, "[AutoAbility] " + who + cardName + " — pay CP: ");
		// Producing CP beyond the cost is legal but the surplus is not part of the payment:
		// clamp so an odd fixed cost paid with a 2-CP discard can't inflate X.
		int paid = maxCp == Integer.MAX_VALUE ? produced : Math.min(produced, maxCp);
		mw.logEntry("[AutoAbility] " + who + cardName + " — paid " + paid + " CP"
				+ (produced > paid ? " (" + (produced - paid) + " excess CP wasted)" : ""));
		if (payerIsP1) { mw.refreshP1HandLabel();      mw.refreshP1BreakLabel(); }
		else           { mw.refreshP2HandCountLabel(); mw.refreshP2BreakLabel(); }
		onConfirm.accept(paid);
	}

	/**
	 * Why {@code answer} is not a payment {@code payerIsP1} could have made here, or {@code null}
	 * when it is. The dialog enforces all of this as it is filled in; a remote answer is the one
	 * thing that reaches the spending without having gone through it.
	 */
	private String cpPaymentProblem(List<Integer> answer, int minCp, boolean payerIsP1, int crystalAltCost,
			Map<String, Integer> elementNeeds, Predicate<CardData> cpSource) {
		if (answer.isEmpty()) return null;
		CpPlan plan = CpPlan.fromAnswer(answer);
		if (plan == null) return "malformed payment";
		if (plan.crystals())
			return crystalAltCost > 0 && mw.playerCrystals(payerIsP1) >= crystalAltCost
					? null : "no Crystal alternative they can afford";
		CardData[]  bkpCards  = mw.playerBackupCards(payerIsP1);
		CardState[] bkpStates = mw.playerBackupStates(payerIsP1);
		List<CardData> hand   = mw.playerHand(payerIsP1);
		if (new HashSet<>(plan.dulls()).size() != plan.dulls().size()
				|| new HashSet<>(plan.discards()).size() != plan.discards().size()) return "a card paid twice";
		List<CardData> dulled = new ArrayList<>();
		for (int slot : plan.dulls()) {
			if (slot < 0 || slot >= bkpCards.length || bkpCards[slot] == null
					|| bkpStates[slot] != CardState.ACTIVE) return "no active Backup in slot " + slot;
			if (cpSource != null && !cpSource.test(bkpCards[slot])) return bkpCards[slot].name() + " cannot pay this";
			dulled.add(bkpCards[slot]);
		}
		Set<String> ldGrants = mw.lightDarkDiscardGrants(payerIsP1);
		List<CardData> discarded = new ArrayList<>();
		for (int hi : plan.discards()) {
			if (hi < 0 || hi >= hand.size()) return "no card at hand index " + hi;
			CardData c = hand.get(hi);
			if (!CpPaymentUtils.canDiscardForCp(c, ldGrants) || (cpSource != null && !cpSource.test(c)))
				return c.name() + " cannot be discarded for CP";
			discarded.add(c);
		}
		if (plan.produced() < minCp) return "only " + plan.produced() + " CP of " + minCp;
		if (!CpPaymentUtils.elementNeedsMet(dulled, discarded, elementNeeds)) return "the Elements the cost names are not all paid";
		return null;
	}

	/**
	 * The local half of {@link #payCpAtResolution}: shows the payer their Backups and hand, and
	 * returns what they chose as a {@link ChoiceKind#CP_PAYMENT} answer. Spends nothing.
	 */
	private List<Integer> askCpPayment(String cardName, int minCp, int maxCp,
			boolean isP1, int crystalAltCost, Map<String, Integer> elementNeeds, Predicate<CardData> cpSource) {
		List<List<Integer>> result = new ArrayList<>(List.of(List.of()));
		CardData[]     bkpCards  = mw.playerBackupCards(isP1);
		CardState[]    bkpStates = mw.playerBackupStates(isP1);
		String[]       bkpUrls  = mw.playerBackupUrls(isP1);
		List<CardData> hand      = mw.playerHand(isP1);

		String elemLabel = elementNeeds.entrySet().stream()
				.map(e -> ("《" + e.getKey() + "》").repeat(e.getValue()))
				.collect(java.util.stream.Collectors.joining());
		String title = !elemLabel.isEmpty() && maxCp == minCp
				? cardName + " — Pay " + elemLabel
						+ (minCp > elementNeeds.values().stream().mapToInt(Integer::intValue).sum()
								? " (" + minCp + " CP total)" : "")
				: (maxCp == minCp)
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
			List<CardData> dulled    = selectedBackups.stream().map(i -> bkpCards[i]).toList();
			List<CardData> discarded = selectedDiscards.stream().map(hand::get).toList();
			Map<String, Integer> elemPaid = CpPaymentUtils.elementCpPaid(dulled, discarded, elementNeeds);
			confirmBtn.setEnabled(total >= minCp
					&& CpPaymentUtils.elementNeedsMet(dulled, discarded, elementNeeds));

			String cap = maxCp == Integer.MAX_VALUE ? "" : " / " + maxCp;
			StringBuilder elemProgress = new StringBuilder();
			for (Map.Entry<String, Integer> need : elementNeeds.entrySet())
				elemProgress.append("  ").append(need.getKey()).append(": ")
						.append(Math.min(elemPaid.getOrDefault(need.getKey(), 0), need.getValue()))
						.append("/").append(need.getValue());
			cpLabel.setText("CP produced: " + total + cap
					+ (minCp > 0 ? "  (min " + minCp + ")" : "") + elemProgress);

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
			if (bkpCards[i] != null && bkpStates[i] == CardState.ACTIVE
					&& (cpSource == null || cpSource.test(bkpCards[i]))) eligibleBackupSlots.add(i);

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
				boolean payable = CpPaymentUtils.canDiscardForCp(hc, ldGrants)
						&& (cpSource == null || cpSource.test(hc));
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
		cancelBtn.addActionListener(ev -> dlg.dispose());
		confirmBtn.addActionListener(ev -> {
			dlg.dispose();
			result.set(0, CpPlan.of(selectedBackups, selectedDiscards).toAnswer());
		});

		JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 6));
		buttonPanel.add(confirmBtn);
		if (crystalAltCost > 0) {
			JButton crystalBtn = new JButton("Pay " + crystalAltCost + " Crystal" + (crystalAltCost == 1 ? "" : "s"));
			crystalBtn.setFont(FontLoader.loadPixelFont(11));
			crystalBtn.setEnabled(mw.playerCrystals(isP1) >= crystalAltCost);
			crystalBtn.addActionListener(ev -> {
				dlg.dispose();
				result.set(0, CpPlan.CRYSTALS.toAnswer());
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
		return result.get(0);
	}

	boolean canActivateHandAbility(ActionAbility ability, CardData source, boolean isP1) {
		if (ability.yourTurnOnly() || ability.mainPhaseOnly()) {
			GameState.Player activePlayer = isP1 ? GameState.Player.P1 : GameState.Player.P2;
			if (mw.gameState.getCurrentPlayer() != activePlayer) return false;
		}
		if (ability.oncePerTurn()
				&& mw.usedOncePerTurnAbilities.getOrDefault(source, Set.of()).contains(ability.effectText()))
			return false;
		GameState.GamePhase p = mw.gameState.getCurrentPhase();
		if (p != GameState.GamePhase.MAIN_1 && p != GameState.GamePhase.MAIN_2
				&& !(p == GameState.GamePhase.ATTACK && mw.attackSubStep == 0)) return false;
		// "during your Main Phase" (28-116H Louise) rules out the Attack Phase window above.
		if (ability.mainPhaseOnly() && p == GameState.GamePhase.ATTACK) return false;
		if (ability.crystalCost() > 0 && mw.playerCrystals(isP1) < ability.crystalCost()) return false;
		for (BreakZoneCost bz : ability.breakZoneCosts())
			if (!bzCostSatisfied(bz, isP1)) return false;
		for (RemoveFromGameCost rfg : ability.removeFromGameCosts())
			if (!rfgCostSatisfied(rfg, isP1)) return false;
		for (ReturnToHandCost rth : ability.returnToHandCosts())
			if (!rfthCostSatisfied(rth, isP1)) return false;
		for (CounterCost cc : ability.counterCosts())
			if (!counterCostSatisfied(cc, source)) return false;
		if (!UseConditions.met(mw, ability, source, isP1)) return false;
		if (!mw.abilityHasActivationTarget(ability, source, isP1)) return false;
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
		// The same use conditions a field ability answers to — "Damage N --" (Ardyn 26-122H), a
		// control condition (25-017R, 21-134S), a card that entered this turn (21-057R Fran).
		if (!UseConditions.met(mw, ability, source, isP1)) return false;
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
			if (!dullForwardCostSatisfied(dfc, isP1, source)) return false;
		// An ability used from the Break Zone is an action ability like any other, so rule 11.6.5
		// binds it too: Undead Princess 19-052C's "Choose 1 Earth Forward." cannot be used while
		// no Earth Forward is on the board, and removing her from the game to find that out is
		// exactly the cost the rule exists to stop being paid.
		if (!mw.abilityHasActivationTarget(ability, source, isP1)) return false;
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
			payAndReport(ability, eff, source, () -> {},
					new AbilityPayment(List.of(), List.of(), bzTargets, 0, -1, Map.of()), isP1);
			return;
		}

		new AbilityPaymentDialog(mw.frame, eff, source,
				mw.playerHand(isP1), mw.cpPayableBackupCards(isP1), mw.playerBackupStates(isP1), mw.playerBackupUrls(isP1),
				mw::showZoomAt, mw::hideZoom, null, null, mw.lightDarkDiscardGrants(isP1),
				eff.isSpecial() && mw.canPaySpecialCostWithCrystal(source, isP1),
				(discards, backups, xValue, sCostIdx, breaks) -> {
					List<ForwardTarget> bzTargets = resolveBzCostTargetsForBzAbility(bzCosts, isP1);
					if (bzTargets == null) return;
					payAndReport(ability, eff, source, () -> {},
							new AbilityPayment(discards, backups, bzTargets, xValue, sCostIdx, breaks), isP1);
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
				CardData top = mw.p2ForwardPrimedTop.get(i);
				if (top == source || mw.p2ForwardCards.get(i) == source)
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


	// =========================================================================================
	// Cost satisfaction checks
	// =========================================================================================
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
	 *
	 * <p>Crosses as an {@link ChoiceKind#OPTION}, a position in that list: both clients build it
	 * from the same Break Zone, and a list of one is taken on both without asking.
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

		if (useful.size() == 1) return useful.get(0);

		List<Integer> amounts = useful;
		List<Integer> answer = mw.decide(PlayerChoice.by(isP1, ChoiceKind.OPTION)
				.prompting("Waiting for your opponent to choose how many " + cc.counterName()
						+ " Counters to remove...")
				.locally(() -> {
					Object[] options = amounts.stream().map(n -> "X = " + n).toArray();
					int choice = mw.showEffectOptionDialog(
							"<html><body style='width:320px'>" + source.name() + " has <b>" + available + "</b> "
							+ cc.counterName() + " Counter(s).<br><br>Remove how many? The number removed is the "
							+ "cost this ability can play back from your Break Zone.</body></html>",
							source.name() + " — remove " + cc.counterName() + " Counters", options);
					return List.of(choice >= 0 && choice < amounts.size() ? choice : 0);
				})
				// AI: the biggest it can actually use
				.byCpu(() -> List.of(amounts.size() - 1))
				.legalWhen(a -> a.size() == 1 && a.get(0) >= 0 && a.get(0) < amounts.size(),
						"only " + amounts + " are amounts it can remove here"));
		return answer.isEmpty() ? amounts.get(0) : amounts.get(answer.get(0));
	}

	/** True when {@code source} (the activating card) has enough counters to pay {@code cc}. */
	boolean counterCostSatisfied(CounterCost cc, CardData source) {
		if (!source.name().equalsIgnoreCase(cc.cardName())) return false;
		// A variable cost names no amount, so what it needs is something to spend: X = 0 buys
		// nothing, since no Forward in the corpus costs 0.
		int required = cc.variable() ? 1 : cc.count();
		return mw.gameState.getCounters(source, cc.counterName()) >= required;
	}

	/**
	 * Which zones may pay a dull cost. {@code null}/"Forward" is the Forward row, "Character"
	 * every field card, and "Backup" the Backup row alone — 8-096L Sakura's "Dull 5 active
	 * Lightning Backups", which reported "Character" until the parser learned to tell the two
	 * apart and so accepted Forwards for a cost that never offered them.
	 *
	 * <p>Paired with {@link #dullCostWantsBackups}, and read by both the availability check and the
	 * payment: an ability offered on a pool the payment then refuses is an ability that cannot be
	 * used, so the two have to ask the same question.
	 */
	private static boolean dullCostWantsForwards(DullForwardCost dfc) {
		String t = dfc.cardType();
		return t == null || "Forward".equalsIgnoreCase(t) || "Character".equalsIgnoreCase(t);
	}

	/** Whether a Backup may pay {@code dfc} — see {@link #dullCostWantsForwards}. */
	private static boolean dullCostWantsBackups(DullForwardCost dfc) {
		String t = dfc.cardType();
		return "Backup".equalsIgnoreCase(t) || "Character".equalsIgnoreCase(t);
	}

	/** Overload for callers with no source card in hand; the source can then never stand in. */
	boolean dullForwardCostSatisfied(DullForwardCost dfc, boolean isP1) {
		return dullForwardCostSatisfied(dfc, isP1, null);
	}

	boolean dullForwardCostSatisfied(DullForwardCost dfc, boolean isP1, CardData source) {
		List<CardData> payers = dullCostPayerPool(dfc, isP1);
		// 7-128H Yuri may dull himself in place of one of the cards asked for, so the pool needs
		// one fewer from the field. The source is exempt from the same-Element rule: the printed
		// alternative asks it of the Backups, not of him.
		int needed = dfc.count();
		if (dfc.sourceReplacesOne() && activeFieldSlotOf(source, isP1) != null) needed--;
		if (needed <= 0) return true;
		if (!dfc.sameElement()) return payers.size() >= needed;

		// One Element has to run through the whole set, so the pool is only as deep as its best
		// Element — six Backups across six Elements pay for nothing.
		return largestSameElementGroup(payers) >= needed;
	}

	/** Every active card that matches {@code dfc} and sits in a zone the cost accepts. */
	List<CardData> dullCostPayerPool(DullForwardCost dfc, boolean isP1) {
		List<CardData>  fwds    = isP1 ? mw.p1ForwardCards  : mw.p2ForwardCards;
		List<CardState> fwdSt   = isP1 ? mw.p1ForwardStates : mw.p2ForwardStates;
		List<CardData>  mons    = isP1 ? mw.p1MonsterCards  : mw.p2MonsterCards;
		CardData[]      bkps    = isP1 ? mw.p1BackupCards   : mw.p2BackupCards;
		CardState[]     bkpSt   = isP1 ? mw.p1BackupStates  : mw.p2BackupStates;
		List<CardData> pool = new ArrayList<>();
		if (dullCostWantsForwards(dfc)) {
			for (int i = 0; i < fwds.size(); i++)
				if (fwdSt.get(i) == CardState.ACTIVE && dullForwardCostMatches(dfc, fwds.get(i)))
					pool.add(fwds.get(i));
		}
		if (dullCostWantsBackups(dfc)) {
			for (int i = 0; i < bkps.length; i++)
				if (bkps[i] != null && bkpSt[i] == CardState.ACTIVE && dullForwardCostMatches(dfc, bkps[i]))
					pool.add(bkps[i]);
		}
		if ("Character".equalsIgnoreCase(dfc.cardType())) {
			for (CardData mon : mons)
				if (dullForwardCostMatches(dfc, mon)) pool.add(mon);
		}
		return pool;
	}

	/**
	 * The cards to dull for {@code dfc}, or {@code null} when the board cannot pay or P1 backed
	 * out. {@code targets} is every field card that matches the cost's per-card filters.
	 *
	 * <p>Most costs are a plain counted pick out of that pool. 7-128H Yuri is not: his picks must
	 * share an Element <em>with one another</em>, and dulling Yuri himself may stand in for one of
	 * them. A constraint between picks is not something one counted dialog can express, so P1
	 * takes them one at a time and the pool narrows to the Elements still in play after each.
	 *
	 * <p>Asked after the payment has committed, so each pick goes through {@link MainWindow#decide}:
	 * a remote payer's client sends it, and this one — replaying the same payment — waits for it.
	 * A pool with no choice in it (exactly as many cards as the cost takes, or one left for a step)
	 * is taken on both clients without asking, so nothing crosses for it.
	 */
	private List<ForwardTarget> selectDullCostTargets(DullForwardCost dfc, List<ForwardTarget> targets,
			Map<ForwardTarget, CardData> cardOf, CardData source, boolean isP1) {
		ForwardTarget sourceTarget = dfc.sourceReplacesOne() ? activeFieldSlotOf(source, isP1) : null;
		String waitPrompt = "Waiting for your opponent to choose what to dull for "
				+ source.name() + "'s cost...";
		if (!dfc.sameElement() && sourceTarget == null) {
			if (targets.size() < dfc.count()) return null;
			if (targets.size() == dfc.count()) return new ArrayList<>(targets);
			List<ForwardTarget> picks = mw.selectOwnFieldTargets(isP1, targets, dfc.count(), false,
					"Dull Cost", waitPrompt, () -> new ArrayList<>(targets.subList(0, dfc.count())));
			return picks.size() < dfc.count() ? null : picks;
		}
		// The CPU plans the whole set up front and answers each step from the plan.
		List<ForwardTarget> plan = isP1 ? null : planP2DullCostTargets(dfc, targets, cardOf, sourceTarget);

		List<ForwardTarget> chosen = new ArrayList<>();
		Set<String> shared = null;            // null until the first field pick fixes the Elements
		for (int n = 0; n < dfc.count(); n++) {
			List<ForwardTarget> step = new ArrayList<>();
			for (ForwardTarget t : targets) {
				if (chosen.contains(t)) continue;
				if (shared != null && Collections.disjoint(shared, mw.effectiveElements(cardOf.get(t))))
					continue;
				step.add(t);
			}
			// The source is offered alongside, and is exempt from the shared-Element rule: the
			// printed alternative asks that of the Backups, not of Yuri.
			if (sourceTarget != null && !chosen.contains(sourceTarget)) step.add(sourceTarget);
			if (step.isEmpty()) return null;
			int stepIdx = n;
			ForwardTarget t = step.size() == 1 ? step.get(0)
					: mw.selectOwnFieldTarget(isP1, step, "Dull Cost", waitPrompt,
							() -> plan == null || plan.size() <= stepIdx ? null : plan.get(stepIdx));
			if (t == null) return null;
			chosen.add(t);
			if (t.equals(sourceTarget)) continue;
			List<String> elems = mw.effectiveElements(cardOf.get(t));
			if (shared == null) shared = new LinkedHashSet<>(elems);
			else shared.retainAll(elems);
		}
		return chosen;
	}

	/**
	 * P2's version: take the deepest single-Element group outright, and fall back on dulling the
	 * source only when the field is one card short of paying on its own.
	 */
	private List<ForwardTarget> planP2DullCostTargets(DullForwardCost dfc, List<ForwardTarget> targets,
			Map<ForwardTarget, CardData> cardOf, ForwardTarget sourceTarget) {
		List<ForwardTarget> group = dfc.sameElement()
				? deepestSameElementGroup(targets, cardOf) : targets;
		if (group.size() >= dfc.count()) return new ArrayList<>(group.subList(0, dfc.count()));
		if (sourceTarget != null && group.size() >= dfc.count() - 1) {
			List<ForwardTarget> out = new ArrayList<>(group.subList(0, dfc.count() - 1));
			out.add(sourceTarget);
			return out;
		}
		return null;
	}

	/** The largest set of {@code targets} sharing one Element, as targets rather than a count. */
	private List<ForwardTarget> deepestSameElementGroup(List<ForwardTarget> targets,
			Map<ForwardTarget, CardData> cardOf) {
		Map<String, List<ForwardTarget>> byElement = new LinkedHashMap<>();
		for (ForwardTarget t : targets)
			for (String e : mw.effectiveElements(cardOf.get(t)))
				byElement.computeIfAbsent(e, k -> new ArrayList<>()).add(t);
		return byElement.values().stream()
				.max(Comparator.comparingInt(List::size)).orElse(List.of());
	}

	/** The field slot {@code source} occupies while active, or {@code null} if it cannot be dulled. */
	ForwardTarget activeFieldSlotOf(CardData source, boolean isP1) {
		if (source == null) return null;
		List<CardData>  fwds  = isP1 ? mw.p1ForwardCards  : mw.p2ForwardCards;
		List<CardState> fwdSt = isP1 ? mw.p1ForwardStates : mw.p2ForwardStates;
		for (int i = 0; i < fwds.size(); i++)
			if (fwds.get(i) == source && fwdSt.get(i) == CardState.ACTIVE)
				return new ForwardTarget(isP1, i, ForwardTarget.CardZone.FORWARD);
		CardData[]  bkps  = isP1 ? mw.p1BackupCards  : mw.p2BackupCards;
		CardState[] bkpSt = isP1 ? mw.p1BackupStates : mw.p2BackupStates;
		for (int i = 0; i < bkps.length; i++)
			if (bkps[i] == source && bkpSt[i] == CardState.ACTIVE)
				return new ForwardTarget(isP1, i, ForwardTarget.CardZone.BACKUP);
		return null;
	}

	/**
	 * The size of the largest subset of {@code pool} sharing one Element. A multi-element card
	 * counts towards every Element it carries, which is what lets it join whichever group the
	 * player is assembling.
	 */
	private int largestSameElementGroup(List<CardData> pool) {
		Map<String, Integer> byElement = new LinkedHashMap<>();
		for (CardData c : pool)
			for (String e : mw.effectiveElements(c))
				byElement.merge(e, 1, Integer::sum);
		return byElement.values().stream().mapToInt(Integer::intValue).max().orElse(0);
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

	/**
	 * Every field card of {@code type} the given player controls — "N Backups you control", with
	 * no filter beyond the type. "Character" means Forwards and Backups, the way it does elsewhere.
	 */
	List<ForwardTarget> ownFieldCardsOfType(String type, boolean isP1) {
		List<ForwardTarget> result = new ArrayList<>();
		String t = type == null ? "" : type.replaceAll("(?i)s$", "");
		boolean wantsCharacter = t.equalsIgnoreCase("Character");
		if (wantsCharacter || t.equalsIgnoreCase("Forward")) {
			List<CardData> fwds = mw.playerForwardCards(isP1);
			for (int i = 0; i < fwds.size(); i++)
				result.add(new ForwardTarget(isP1, i, ForwardTarget.CardZone.FORWARD));
		}
		if (wantsCharacter || t.equalsIgnoreCase("Backup")) {
			CardData[] bkps = mw.playerBackupCards(isP1);
			for (int i = 0; i < bkps.length; i++)
				if (bkps[i] != null) result.add(new ForwardTarget(isP1, i, ForwardTarget.CardZone.BACKUP));
		}
		if (t.equalsIgnoreCase("Monster")) {
			List<CardData> mons = mw.playerMonsterCards(isP1);
			for (int i = 0; i < mons.size(); i++)
				result.add(new ForwardTarget(isP1, i, ForwardTarget.CardZone.MONSTER));
		}
		return result;
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


	// =========================================================================================
	// Return-to-hand costs and field helpers
	// =========================================================================================
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
			// Asked after the payment committed, so through decide — see selectDullCostTargets.
			List<ForwardTarget> picks = eligible.size() <= rth.count() ? eligible
					: mw.selectOwnFieldTargets(isP1, eligible, rth.count(), false, "Return to Hand (cost)",
							"Waiting for your opponent to choose what to return to their hand...",
							() -> new ArrayList<>(eligible.subList(0, rth.count())));
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
		mw.dropFromAttackSelection(ForwardTarget.CardZone.BACKUP, idx);
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


	// =========================================================================================
	// Field abilities and activation
	// =========================================================================================
	/** One action ability P1 can reach on a field card, and whether it could be used right now. */
	record FieldAbilityChoice(ActionAbility ability, boolean enabled) {}

	/**
	 * The action abilities P1 can reach on {@code card} from the field, in order, each with whether
	 * it could be used right now — what the card's action buttons are built from.
	 *
	 * <p>The human only ever acts as P1. P2's own abilities are P2's to use — the CPU's or the
	 * remote player's — so a P2 card offers only its "each player can use this ability" abilities,
	 * which P1 pays for.
	 */
	List<FieldAbilityChoice> fieldAbilityChoices(CardData card, boolean isFrozen, CardState state,
			int playedTurn, boolean isP1) {
		List<FieldAbilityChoice> out = new ArrayList<>();
		forEachFieldAbility(card, isFrozen, state, playedTurn, isP1, (ability, activatorIsP1, enabled) -> {
			if (activatorIsP1) out.add(new FieldAbilityChoice(ability, enabled));
		});
		return out;
	}

	/**
	 * Whether P1 could use any action ability {@code card} offers from the field right now — the
	 * question {@link #fieldAbilityChoices} answers per ability, asked of the card as a whole. For a
	 * P2 card only its "each player can use this ability" abilities count, since those are the
	 * only ones P1 can reach.
	 */
	boolean hasUsableFieldAbility(CardData card, boolean isFrozen, CardState state, int playedTurn, boolean isP1) {
		boolean[] found = { false };
		forEachFieldAbility(card, isFrozen, state, playedTurn, isP1, (ability, activatorIsP1, enabled) -> {
			if (enabled && activatorIsP1) found[0] = true;
		});
		return found[0];
	}

	/** One of a card's field abilities: the ability, who pays for it, and whether it is usable now. */
	@FunctionalInterface
	private interface FieldAbilityVisitor {
		void visit(ActionAbility ability, boolean activatorIsP1, boolean enabled);
	}

	/**
	 * Walks every action ability {@code card} offers from the field, in order, with the
	 * activation verdict for each. Shared by the action buttons and {@link #hasUsableFieldAbility}
	 * so the two cannot disagree about what the player can do.
	 */
	/**
	 * The three groups a card's action abilities come in, in the order {@link #abilityCatalogue}
	 * lists them: its own and those it borrows, those granted to it for the turn, and the
	 * Petrification removal it is offered while petrified ({@code null} when it is not).
	 */
	private record AbilityGroups(List<ActionAbility> ownAndBorrowed, List<ActionAbility> granted,
	                             ActionAbility petrifyRemoval) {
		List<ActionAbility> all() {
			List<ActionAbility> out = new ArrayList<>(ownAndBorrowed);
			out.addAll(granted);
			if (petrifyRemoval != null) out.add(petrifyRemoval);
			return out;
		}
	}

	private AbilityGroups abilityGroups(CardData card, boolean ownerIsP1) {
		// Printed abilities first, then the ones borrowed from the removed-from-game zone (Clive
		// 26-005H). Borrowed specials join this list rather than the temp-granted one below so they
		// go through the same phase and 《S》-cost checks a printed special does; the temp list is
		// for cost-free once-per-turn copies, which these are not.
		List<ActionAbility> abilities = new ArrayList<>(card.actionAbilities());
		abilities.addAll(mw.rfgJobSpecialAbilities(card, ownerIsP1));
		List<ActionAbility> tempAbilities = (ownerIsP1 ? mw.p1TempGrantedAbilities : mw.p2TempGrantedAbilities)
				.getOrDefault(card, List.of());
		// Medusa grants a petrified Forward "《5》: Remove all Petrification Counters from this Forward."
		// It's driven off the counter's presence rather than a stored grant (which wouldn't survive the
		// turn), so synthesize the menu item whenever the card carries a Petrification Counter.
		boolean petrified = mw.gameState.getCounters(card, "Petrification") > 0;
		return new AbilityGroups(abilities, tempAbilities, petrified ? petrificationRemovalAbility() : null);
	}

	/**
	 * Every action ability {@code card} offers as it stands, in a fixed order: printed, borrowed,
	 * granted for the turn, and the Petrification removal. An activation crosses the wire as a
	 * position in this list, which both clients build from state they hold alike — the card's
	 * text, the removed-from-game zone the borrowed ones come from, the grants effects have made,
	 * and its counters. The printed abilities come first, so their positions are the card's own.
	 *
	 * @param ownerIsP1 whose card it is, which decides whose zone and grants are read
	 */
	List<ActionAbility> abilityCatalogue(CardData card, boolean ownerIsP1) {
		return abilityGroups(card, ownerIsP1).all();
	}

	private void forEachFieldAbility(CardData card, boolean isFrozen, CardState state, int playedTurn,
			boolean isP1, FieldAbilityVisitor visitor) {
		if (mw.lostAbilitiesCards.contains(card)) return;
		AbilityGroups groups = abilityGroups(card, isP1);
		List<ActionAbility> abilities = groups.ownAndBorrowed();
		List<ActionAbility> tempAbilities = groups.granted();
		boolean petrified = groups.petrifyRemoval() != null;
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

			visitor.visit(ability, activatorIsP1,
					phaseOk && mw.canActivateAbility(ability, isFrozen, state, playedTurn, card, activatorIsP1));
		}

		for (ActionAbility ability : tempAbilities)
			visitor.visit(ability, isP1,
					isMainPhase && mw.canActivateAbility(ability, isFrozen, state, playedTurn, card, isP1));

		ActionAbility petrifyRemoval = groups.petrifyRemoval();
		if (petrifyRemoval != null)
			visitor.visit(petrifyRemoval, isP1,
					isMainPhase && mw.canActivateAbility(petrifyRemoval, isFrozen, state, playedTurn, card, isP1));
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
				spendCounterWaiver(source, waiver);
				// A cost-stripped copy rather than a flag threaded through the payment: the waiver
				// says "without paying the cost", and an ability with no costs is exactly that.
				// Its restrictions travel with it, so a once-per-turn or your-turn-only ability is
				// no more usable than it was.
				payAndReport(ability, eff.withCostsWaived(), source, () -> {},
						AbilityPayment.none().waivedByCounters(), isP1);
				return;
			}
		}

		// Zero CP + no X: confirm immediately.  Any S cost is resolved inside executeAbilityPayment,
		// which prompts when more than one hand card can pay it.
		if (paysWithoutAWindow(eff)) {
			payAndReport(ability, eff, source, applyDull, new AbilityPayment(List.of(), List.of(),
					autoResolveBzTargets(source, bzCosts, isP1), 0, -1, Map.of()), isP1);
			return;
		}

		CardData.SpecialAbilityProxy proxy = eff.isSpecial()
				? mw.effectiveSpecialAbilityProxy(source, isP1) : null;
		String primerName = eff.isSpecial() ? mw.priming.getPrimerCardName(source, isP1) : null;
		// 17-002L Edgar's "without paying 《S》": the dialog is shown the ability with no S slot to
		// fill, and the payment below skips the discard on the same waiver.
		ActionAbility shown = eff.isSpecial() && mw.specialSCostWaivedThisTurn.contains(source)
				? eff.withSpecialCostWaived() : eff;
		new AbilityPaymentDialog(mw.frame, shown, source,
				mw.playerHand(isP1), mw.cpPayableBackupCards(isP1), mw.playerBackupStates(isP1), mw.playerBackupUrls(isP1),
				mw::showZoomAt, mw::hideZoom, proxy, primerName, mw.lightDarkDiscardGrants(isP1),
				eff.isSpecial() && mw.canPaySpecialCostWithCrystal(source, isP1),
				(discards, backups, xValue, sCostIdx, breaks) -> payAndReport(ability, eff, source, applyDull,
						new AbilityPayment(discards, backups, autoResolveBzTargets(source, bzCosts, isP1),
								xValue, sCostIdx, breaks), isP1),
				mw.breakForCpBackupSlots(isP1))
			.show();
	}


	/** Whether {@link #showActionAbilityPaymentDialog} pays for {@code eff} at once, with no payment dialog. */
	private static boolean paysWithoutAWindow(ActionAbility eff) {
		return eff.cpCost().isEmpty() && !eff.hasXCost();
	}

	/**
	 * Whether {@link #showActionAbilityPaymentDialog} would put a window in front of the player
	 * before anything is paid — the CP payment dialog, or the offer to waive a 《S》 cost with
	 * counters — so they can still back out. When it would not, using the ability commits at once.
	 */
	boolean paymentOffersAWayBack(ActionAbility ability, CardData source, boolean isP1) {
		ActionAbility eff = mw.effectiveAbilityCost(ability, isP1);
		if (eff.isSpecial() && isP1 && mw.specialCostCounterWaiver(source, isP1) != null) return true;
		return !paysWithoutAWindow(eff);
	}

	/** Removes the counters a 《S》-cost waiver spends (Wakka 16-138S), as both clients do. */
	private void spendCounterWaiver(CardData source, CardData.SpecialCostCounterWaiver waiver) {
		int removed = mw.gameState.removeCounters(source, waiver.counterName(), waiver.count());
		mw.logEntry(source.name() + " — removed " + removed + " " + waiver.counterName()
				+ " Counter(s): special ability used without paying the cost"
				+ "  [remaining: " + mw.gameState.getCounters(source, waiver.counterName()) + "]");
	}

	/**
	 * Pays for an activation and, when it was the local player's, tells the opponent about it.
	 *
	 * <p>Sent from the payment's commit point, not after it returns. An activation the player
	 * backs out of never reaches it, so an abandoned ability is never sent; and a cost that takes
	 * the source off the field — "put [self] into the Break Zone", Bartz 19-048C's bottom-of-deck —
	 * has not been paid yet, so the source is still in the slot the message names. Everything the
	 * payment asks after the commit crosses as a {@code CHOICE} to a far client already running the
	 * same payment.
	 *
	 * <p>{@code offered} is the ability as the card offers it — printed, borrowed or granted —
	 * which is what the index on the wire addresses; {@code eff} is that ability with the board's
	 * discounts and surcharges applied. Only the first is transmitted — the receiver derives the
	 * second from its own copy of the board, the same way it derives every other cost.
	 */
	private boolean payAndReport(ActionAbility offered, ActionAbility eff, CardData source,
			Runnable applyDull, AbilityPayment payment, boolean isP1) {
		return executeAbilityPayment(eff, source, applyDull, payment, isP1, settled -> {
			if (isP1) mw.sendAbilityActivation(offered, source, settled);
		});
	}

	/**
	 * Replays a remote player's activation: the same payment, run against the board this client
	 * holds them on, dulling the source at {@code at} for a 《Dull》 cost exactly as their client did.
	 *
	 * <p>The effective cost is recomputed here rather than taken from the wire, so the discount a
	 * card on their field gives them is read off that field as this client sees it. The choices
	 * they settled before committing are checked against that board before any of it is spent —
	 * see {@link #remotePaymentProblem}.
	 *
	 * @param at where the source sits on this board — almost always one of their zones, but their
	 *           own use of an "each player can use this ability" ability of one of this player's
	 *           cards sits on this side
	 */
	boolean executeRemoteAbilityActivation(ActionAbility ability, CardData source, AbilitySource at,
			AbilityPayment payment) {
		ActionAbility eff = mw.effectiveAbilityCost(ability, false);
		if (payment.counterWaiver()) {
			CardData.SpecialCostCounterWaiver waiver = eff.isSpecial()
					? mw.specialCostCounterWaiver(source, false) : null;
			if (waiver == null) {
				mw.reportDesync("opponent used \"" + source.name() + "\"'s special ability by removing "
						+ "counters, but it has no such waiver open here");
				return false;
			}
			spendCounterWaiver(source, waiver);
			eff = eff.withCostsWaived();
		}
		String problem = remotePaymentProblem(eff, source, payment);
		if (problem != null) {
			mw.reportDesync("opponent paid for \"" + source.name() + "\"'s ability with " + problem);
			return false;
		}
		return executeAbilityPayment(eff, source, mw.abilityCostDull(at), payment, false, settled -> {});
	}

	/**
	 * What is wrong with the choices a remote player settled before committing an activation, as
	 * this board sees them, or {@code null} when every one of them is a choice they could have
	 * made here. Only the pre-commit choices are checked: each post-commit answer is checked by
	 * {@link MainWindow#decide} as it arrives.
	 *
	 * <p>Checked before the payment runs rather than inside it, because a refusal part-way through
	 * would leave a half-paid cost on one board and none on the other.
	 */
	private String remotePaymentProblem(ActionAbility ability, CardData source, AbilityPayment payment) {
		List<CardData> hand = mw.playerHand(false);
		Set<Integer> reserved = new HashSet<>(payment.discards());
		int s = payment.sCostHandIdx();
		boolean sOwed = ability.isSpecial() && !mw.specialSCostWaivedThisTurn.contains(source);
		if (sOwed) {
			if (s == AbilityPaymentDialog.S_COST_CRYSTAL) {
				if (!mw.canPaySpecialCostWithCrystal(source, false))
					return "a Crystal for the 《S》, which nothing lets them do here";
			} else if (!specialCostCandidateIdxs(source, hand, payment.discards(), false).contains(s)) {
				return "hand slot " + s + " for the 《S》, which cannot pay it here";
			} else {
				reserved.add(s);
			}
		}
		List<DiscardCost> costs = ability.discardCosts();
		List<List<Integer>> picks = payment.discardCostPicks();
		if (picks.isEmpty()) return costs.isEmpty() ? null : "no cards for its discard cost";
		if (picks.size() != costs.size())
			return picks.size() + " discard-cost picks for " + costs.size() + " discard cost(s)";
		for (int d = 0; d < costs.size(); d++) {
			DiscardCost dc = costs.get(d);
			List<Integer> pick = picks.get(d);
			if (pick.size() != dc.count())
				return pick.size() + " card(s) for a discard cost of " + dc.count();
			List<Integer> eligible = discardCostCandidateIdxs(dc, hand, reserved);
			Set<String> types = new HashSet<>();
			for (int slot : pick) {
				if (!eligible.contains(slot) || !reserved.add(slot))
					return "hand slot " + slot + " for a discard cost it cannot pay here";
				if (dc.eachDifferentType() && !types.add(discardTypeKey(hand.get(slot))))
					return "two cards of one type for a cost that wants each of a different type";
			}
		}
		return null;
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
		return executeAbilityPayment(ability, source, applyDull,
				new AbilityPayment(discardIndices, backupDullIndices, bzTargets, xValue, -1, Map.of()),
				false, settled -> {});
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


	// =========================================================================================
	// Paying an ability's costs
	// =========================================================================================
	/**
	 * Pays for an activation in two halves split by a commit point.
	 *
	 * <p>Before it, nothing is spent: the choices a player can still back out of are settled — the
	 * card or Crystal that pays a 《S》 cost, and the cards a discard cost takes — and a cancel
	 * abandons the whole activation. At it, {@code onCommit} is told the payment with those
	 * choices filled in. After it, every cost is spent in printed order, and any choice left (which
	 * Forwards to dull, which card to remove from the game) is put through {@link MainWindow#decide}.
	 *
	 * <p>The split is what lets the activation cross the wire. The payment {@code onCommit} sees is
	 * everything the far client cannot work out for itself up to that point, and it is sent from
	 * there — before any cost has moved the source off the field, so the slot it names is still
	 * the source's — and the far client then runs this same method over the same board, standing at
	 * the same post-commit questions as the activator and waiting for each answer.
	 *
	 * @param payment  what the activator settled before calling: CP, X, Break Zone costs, and any
	 *                 《S》 payer or discard-cost picks already made. The last two are settled here
	 *                 when the payment leaves them open
	 * @param onCommit told the settled payment once, when the activation stops being abandonable
	 *                 and before anything is spent; never told when it is abandoned
	 * @return {@code true} when the ability reached the stack, {@code false} when the activation
	 *         was abandoned — a cancelled dialog, a lapsed permission, or a cost that turned out
	 *         to be unpayable.  P2's callers must not re-offer an ability that answered
	 *         {@code false}: an abandonment that commits nothing leaves the board exactly as the
	 *         Main Phase scan found it, which is the shape of an infinite loop.
	 */
	private boolean executeAbilityPayment(ActionAbility ability, CardData source, Runnable applyDull,
			AbilityPayment payment, boolean isP1, Consumer<AbilityPayment> onCommit) {
		List<Integer> discardIndices      = payment.discards();
		List<Integer> backupDullIndices   = payment.backupDulls();
		List<ForwardTarget> bzTargets     = payment.bzTargets();
		int xValue                        = payment.xValue();
		int sCostHandIdx                  = payment.sCostHandIdx();
		Map<Integer, String> backupBreaks = payment.backupBreaks();
		List<String> rawCost = ability.cpCost();
		LinkedHashMap<String, Integer> costByElem = new LinkedHashMap<>();
		for (String e : rawCost) if (!e.isEmpty()) costByElem.merge(e, 1, Integer::sum);
		String[] elems = costByElem.keySet().toArray(String[]::new);

		// Special (S) cost: settle which hand card pays it before anything is committed, so a
		// cancelled choice backs out of the whole activation.  Held as a CardData rather than an
		// index because the CP discards below shift the hand.
		CardData sCostCard = null;
		int      sCostSlot = -1;          // sCostCard's slot in the hand as it stands now
		boolean  sCostFromCrystal = false;
		// 17-002L Edgar's waiver: no 《S》 at all this time. Spent below, once the activation commits,
		// so an activation backed out of partway keeps it.
		final boolean sCostWaived = ability.isSpecial() && mw.specialSCostWaivedThisTurn.contains(source);
		if (ability.isSpecial() && !sCostWaived) {
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
				sCostSlot = sCostHandIdx;
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
						sCostSlot = eligibleIdxs.get(pick);
					} else {
						// P2, or P1 holding a single candidate: the cheapest copy. P2's planner
						// reserved a slot by that same rule and kept it out of the CP payment, so
						// reading it the same way here is what makes the reservation hold — the slot
						// itself never travels between them.
						sCostSlot = cheapest(hand, eligibleIdxs);
						sCostCard = hand.get(sCostSlot);
					}
				}
			}
		}

		// Pre-select discard-cost cards before committing any payment.
		// This lets the player cancel the discard dialog and back out of the entire activation.
		// We exclude indices already committed to CP payment and the S-cost slot to prevent overlap.
		// A payment that arrives with its picks already made (a remote player's) takes them as given;
		// the CPU picks its own after the commit, below.
		List<List<CardData>> discardCostPicks = Collections.emptyList();
		List<List<Integer>>  discardCostSlots = List.of();
		boolean picksGiven = !payment.discardCostPicks().isEmpty();
		if ((isP1 || picksGiven) && !ability.discardCosts().isEmpty()) {
			Set<Integer> reservedIdxs = new HashSet<>(discardIndices);
			if (sCostSlot >= 0) reservedIdxs.add(sCostSlot);
			List<List<CardData>> picks = new ArrayList<>();
			List<List<Integer>>  slots = new ArrayList<>();
			for (int d = 0; d < ability.discardCosts().size(); d++) {
				DiscardCost dc = ability.discardCosts().get(d);
				List<CardData> hand = mw.playerHand(isP1);
				List<Integer> pickedSlots;
				if (picksGiven) {
					pickedSlots = payment.discardCostPicks().get(d);
				} else {
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
					pickedSlots = new ArrayList<>();
					for (int p : chosen) pickedSlots.add(eligibleIdx.get(p));
				}
				List<CardData> pickedCards = new ArrayList<>();
				for (int slot : pickedSlots) {
					pickedCards.add(hand.get(slot));
					reservedIdxs.add(slot);
				}
				picks.add(pickedCards);
				slots.add(pickedSlots);
			}
			discardCostPicks = picks;
			discardCostSlots = slots;
		}

		// ── Commit point ── nothing above has been spent, and nothing below can be taken back.
		onCommit.accept(payment.settled(
				sCostFromCrystal ? AbilityPaymentDialog.S_COST_CRYSTAL : sCostSlot, discardCostSlots));

		CardData[]  bkpCards  = mw.playerBackupCards(isP1);
		CardState[] bkpStates = mw.playerBackupStates(isP1);
		// Logged for the same reason the cast path logs it (payP2CostViaBackupsAndDiscards):
		// without it an ability's CP payment is invisible, and a CPU that paid from the wrong slot
		// reads exactly like a CPU that paid from the right one. Collected and written as one line
		// rather than one per Backup, which is what MainWindow.logCpPayment is for.
		List<String> dulledForCp = new ArrayList<>();
		for (int bi : backupDullIndices) {
			bkpStates[bi] = CardState.DULL;
			mw.playerDullBackupSlot(isP1, bi);
			String cpElem = matchesAnyElement(bkpCards[bi], elems)
					? contributingElement(bkpCards[bi], elems) : (elems.length > 0 ? elems[0] : "");
			if (!cpElem.isEmpty()) mw.playerAddCp(isP1, cpElem, 1);
			dulledForCp.add(bkpCards[bi].name());
		}
		mw.logCpPayment(isP1, dulledForCp, List.of());
		// Break-for-CP payments (Sherlotta 8-053H), after the dull step so a Backup paying both
		// ways is still on the field for it. Its Element joins the clear set below, so CP this cost
		// did not need is not left in the bank.
		Set<String> abilityCpToClear = new java.util.LinkedHashSet<>(java.util.Arrays.asList(elems));
		abilityCpToClear.addAll(mw.breakBackupsForCp(isP1, backupBreaks).keySet());
		// Copied before being reordered. The caller still holds the list it passed and the wire
		// action is built from it afterwards, so reordering it here would send the other client
		// a different payment order than the one just spent — and an immutable one would throw.
		List<Integer> discardRemovalOrder = new ArrayList<>(discardIndices);
		discardRemovalOrder.sort(Collections.reverseOrder());
		for (int di : discardRemovalOrder) {
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
		mw.abilityUsesThisTurn.computeIfAbsent(source, k -> new LinkedHashMap<>())
				.merge(ability.effectText(), 1, Integer::sum);

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
		mw.lastBzCostForwards.clear();
		for (ForwardTarget t : sortedBz) {
			mw.pendingCostBreakDestLabel = t.isP1() ? mw.p1BreakLabel : mw.p2BreakLabel;
			if (t.isP1()) {
				if (t.zone() == ForwardTarget.CardZone.FORWARD) {
					CardData bf = mw.p1ForwardCards.get(t.idx());
					if (bf != null) { mw.lastBzCostForwardPower += bf.power(); mw.lastBzCostForwards.add(bf); }
				}
				switch (t.zone()) {
					case FORWARD -> mw.breakP1Forward(t.idx());
					case BACKUP  -> breakP1BackupSlot(t.idx());
					case MONSTER -> breakP1MonsterSlot(t.idx());
				}
			} else {
				if (t.zone() == ForwardTarget.CardZone.FORWARD) {
					CardData bf = mw.p2ForwardCards.get(t.idx());
					if (bf != null) { mw.lastBzCostForwardPower += bf.power(); mw.lastBzCostForwards.add(bf); }
				}
				switch (t.zone()) {
					case FORWARD -> mw.breakP2Forward(t.idx());
					case BACKUP  -> mw.breakP2BackupSlot(t.idx());
					case MONSTER -> mw.breakP2MonsterSlot(t.idx());
				}
			}
		}

		// Discard costs — paid from hand, no CP generated.
		// Picked before the commit: apply the cards settled above (looked up by identity since CP
		// discards may have shifted indices). The CPU: auto-select now.
		int dcPickIdx = 0;
		for (DiscardCost dc : ability.discardCosts()) {
			List<CardData> hand = mw.playerHand(isP1);
			List<CardData> toDiscard;
			if (!discardCostPicks.isEmpty()) {
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
		// Cards dulled to pay this cost still "become dull", so their auto abilities owe a
		// trigger — 29-051C Stray Chocobo activating itself off 29-056R Lucil's dull cost. They
		// are collected here and fired once the whole cost is paid rather than mid-loop, so an
		// ability that resolves off the trigger cannot disturb a payment still in progress.
		List<CardData> dulledPayingCost = new ArrayList<>();
		for (DullForwardCost dfc : ability.dullForwardCosts()) {
			List<CardData>  fwds  = isP1 ? mw.p1ForwardCards  : mw.p2ForwardCards;
			List<CardState> fwdSt = isP1 ? mw.p1ForwardStates : mw.p2ForwardStates;
			CardData[]      bkps  = isP1 ? mw.p1BackupCards   : mw.p2BackupCards;
			CardState[]     bkpSt = isP1 ? mw.p1BackupStates  : mw.p2BackupStates;
			List<ForwardTarget> targets = new ArrayList<>();
			Map<ForwardTarget, CardData> cardOf = new LinkedHashMap<>();
			if (dullCostWantsForwards(dfc)) {
				for (int i = 0; i < fwds.size(); i++) {
					if (fwdSt.get(i) != CardState.ACTIVE) continue;
					if (!dullForwardCostMatches(dfc, fwds.get(i))) continue;
					ForwardTarget t = new ForwardTarget(isP1, i, ForwardTarget.CardZone.FORWARD);
					targets.add(t);
					cardOf.put(t, fwds.get(i));
				}
			}
			if (dullCostWantsBackups(dfc)) {
				for (int i = 0; i < bkps.length; i++) {
					if (bkps[i] == null || bkpSt[i] != CardState.ACTIVE) continue;
					if (!dullForwardCostMatches(dfc, bkps[i])) continue;
					ForwardTarget t = new ForwardTarget(isP1, i, ForwardTarget.CardZone.BACKUP);
					targets.add(t);
					cardOf.put(t, bkps[i]);
				}
			}
			if (targets.isEmpty() && !dfc.sourceReplacesOne()) {
				mw.logEntry("No eligible active card for Dull cost."); continue;
			}
			List<ForwardTarget> picks = selectDullCostTargets(dfc, targets, cardOf, source, isP1);
			if (picks == null || picks.size() < dfc.count()) continue;
			for (ForwardTarget pick : picks) {
				if (pick.zone() == ForwardTarget.CardZone.BACKUP) {
					int bi = pick.idx();
					bkpSt[bi] = CardState.DULL;
					dulledPayingCost.add(bkps[bi]);
					mw.playerDullBackupSlot(isP1, bi);
					mw.logEntry("Dull cost: \"" + bkps[bi].name() + "\" (backup) dulled");
				} else {
					int fi = pick.idx();
					int pow = fwds.get(fi).power();
					mw.lastDullForwardCostPower += pow;
					fwdSt.set(fi, CardState.DULL);
					dulledPayingCost.add(fwds.get(fi));
					if (isP1) mw.animateDullForward(fi, null); else mw.animateDullP2Forward(fi, null);
					mw.logEntry("Dull cost: \"" + fwds.get(fi).name() + "\" dulled (power " + pow + ")");
				}
			}
		}
		for (CardData dulled : dulledPayingCost)
			triggerAutoAbilitiesForBecomesDull(dulled, isP1);

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
					Timer t = new Timer(animMs, e -> mw.playerLoses(isP1, msg));
					t.setRepeats(false);
					t.start();
				} else {
					mw.playerLoses(isP1, msg);
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
		if (sCostWaived) {
			mw.specialSCostWaivedThisTurn.remove(source);
			mw.logEntry(source.name() + " — special ability used without paying 《S》");
		}

		// Record special abilities used this turn so Gogo's "Mimic" can replay one later.
		if (ability.isSpecial())
			mw.specialAbilitiesUsedThisTurn.add(new UsedSpecialAbility(source, ability));

		// Depth before selection: a "when this is chosen" trigger fired by the selection has to
		// stay above this ability so it resolves first (see GameState.insertStack).
		int depth = mw.gameState.stackSize();
		java.util.List<ForwardTarget> preTargets = ActionResolver.preSelectTargets(
				ability.effectText(), source, xValue, mw.buildGameContext(isP1));
		StackEntry pushed = new StackEntry(source, ability, isP1, xValue, preTargets, revealedPower);
		mw.gameState.insertStack(depth, pushed);
		triggerAutoAbilitiesForOpponentUsesActionAbility(pushed, source, isP1);
		triggerAutoAbilitiesForOwnUsesActionAbility(pushed, source, isP1);
		mw.showStackWindow();
		// The payer's zones. Every cost above spends from one seat's hand and files into one seat's
		// Break Zone, and repainting P1's regardless left P2's activations invisible until the next
		// thing that happened to touch them.
		if (isP1) { mw.refreshP1HandLabel();      mw.refreshP1BreakLabel(); }
		else      { mw.refreshP2HandCountLabel(); mw.refreshP2BreakLabel(); }
		return true;
	}

	/**
	 * "When a Character opponent controls uses an action ability, put Hill Gigas into the Break
	 * Zone. If you do so, cancel its effect and break that Character." — 5-090R Hill Gigas, the one
	 * printing. Called right after the ability goes on the Stack.
	 *
	 * <p>Resolved here rather than put on the Stack: its payoff names the entry just pushed ("its
	 * effect") and the card that used it ("that Character"), and a Stack entry can carry neither.
	 * The outcome is the one the rules give — the trigger would sit above the ability and resolve
	 * first. The cancel goes through {@link MainWindow#cancelStackEntry}, so a protected ability
	 * refuses it; the price is paid either way, as "put … If you do so" prints it.
	 *
	 * <p>The user must be a Character on the field: an ability used from the Break Zone or the hand
	 * is not a Character's the opponent controls.
	 */
	private void triggerAutoAbilitiesForOpponentUsesActionAbility(StackEntry entry, CardData user, boolean userIsP1) {
		if (!Boolean.valueOf(userIsP1).equals(mw.fieldSideOf(user))) return;
		boolean watcherIsP1 = !userIsP1;
		for (CardData watcher : fieldCards(watcherIsP1)) {
			if (mw.lostAbilitiesCards.contains(watcher)) continue;
			for (AutoAbility fa : mw.effectiveAutoAbilities(watcher)) {
				if (!fa.trigger().equals("opponent character uses action ability")) continue;
				String subject = fa.triggerCard().replaceFirst("(?i)\\s+opponent\\s+controls$", "").trim();
				if (!matchesEntersFieldSubject(subject, user, watcher)) continue;
				Matcher m = FA_SACRIFICE_CANCEL_AND_BREAK_USER.matcher(fa.effectText().trim());
				if (!m.matches() || !m.group("name").trim().equalsIgnoreCase(watcher.name())) {
					mw.logEntry("[AutoAbility] Unrecognized effect: " + fa.effectText());
					continue;
				}
				ForwardTarget self = findFieldTarget(watcher, watcherIsP1);
				if (self == null) continue;
				mw.logEntry("[AutoAbility] " + watcher.name() + " — " + user.name() + " used an action ability");
				withAbilitySource(watcher, () -> {
					mw.buildGameContext(watcherIsP1).forceTargetToBreakZone(self);
					boolean paid = (watcherIsP1 ? mw.gameState.getP1BreakZone() : mw.gameState.getP2BreakZone())
							.stream().anyMatch(c -> c == watcher);
					if (!paid) return true;
					if (mw.cancelStackEntry(entry))
						mw.logEntry(watcher.name() + " — cancelled " + user.name() + "'s ability");
					ForwardTarget userAt = findFieldTarget(user, userIsP1);
					if (userAt != null) mw.buildGameContext(watcherIsP1).breakTarget(userAt);
					return true;
				});
				return;   // the watcher has left the field; nothing else of its fires
			}
		}
	}

	/**
	 * "When a Forward or Monster you control uses an action ability, Gogo uses the same action
	 * ability without paying the cost. This effect will trigger only once per turn." — 15-028H Gogo,
	 * the one printing. Called right after the ability goes on the Stack.
	 *
	 * <p>The copy goes on the Stack above the original, so it resolves first — the order the rules
	 * give, with the trigger itself skipped: a Stack entry could not carry the ability it copies.
	 * Resolved with Gogo as its source and his name put in for the user's, as Mimic does. Costs are
	 * not paid, so X is 0. A copy is itself a use (Hill Gigas can answer it) but does not trigger
	 * this again.
	 */
	private void triggerAutoAbilitiesForOwnUsesActionAbility(StackEntry entry, CardData user, boolean userIsP1) {
		if (!Boolean.valueOf(userIsP1).equals(mw.fieldSideOf(user))) return;
		for (CardData watcher : fieldCards(userIsP1)) {
			if (mw.lostAbilitiesCards.contains(watcher)) continue;
			for (AutoAbility fa : mw.effectiveAutoAbilities(watcher)) {
				if (!fa.trigger().equals("own character uses action ability")) continue;
				String subject = fa.triggerCard().replaceFirst("(?i)\\s+you\\s+control$", "").trim();
				if (!matchesEntersFieldSubject(subject, user, watcher)) continue;
				Matcher m = FA_USES_SAME_ACTION_ABILITY.matcher(fa.effectText().trim());
				if (!m.matches() || !m.group("name").trim().equalsIgnoreCase(watcher.name())) {
					mw.logEntry("[AutoAbility] Unrecognized effect: " + fa.effectText());
					continue;
				}
				Set<String> used = mw.usedOncePerTurnAbilities.getOrDefault(watcher, Set.of());
				if (fa.oncePerTurn() && used.contains(fa.effectText())) continue;
				if (fa.oncePerTurn())
					mw.usedOncePerTurnAbilities.computeIfAbsent(watcher, k -> new HashSet<>()).add(fa.effectText());

				String text = ActionResolver.substituteSourceName(entry.ability().effectText(), user.name(), watcher.name());
				ActionAbility copy = entry.ability().withEffectText(text);
				mw.logEntry("[AutoAbility] " + watcher.name() + " uses " + user.name() + "'s action ability → " + text);
				int depth = mw.gameState.stackSize();
				List<ForwardTarget> preTargets = ActionResolver.preSelectTargets(
						text, watcher, 0, mw.buildGameContext(userIsP1));
				StackEntry pushed = new StackEntry(watcher, copy, userIsP1, 0, preTargets, entry.revealedForwardPower());
				mw.gameState.insertStack(depth, pushed);
				if (copy.isSpecial()) mw.specialAbilitiesUsedThisTurn.add(new UsedSpecialAbility(watcher, copy));
				triggerAutoAbilitiesForOpponentUsesActionAbility(pushed, watcher, userIsP1);
			}
		}
	}

	/** "[Self] uses the same action ability without paying the cost." */
	private static final Pattern FA_USES_SAME_ACTION_ABILITY = Pattern.compile(
			"(?i)^(?<name>.+?)\\s+uses\\s+the\\s+same\\s+action\\s+ability\\s+without\\s+paying\\s+the\\s+cost[.!]?$");

	/** "put [Self] into the Break Zone. If you do so, cancel its effect and break that Character." */
	private static final Pattern FA_SACRIFICE_CANCEL_AND_BREAK_USER = Pattern.compile(
			"(?i)^put\\s+(?<name>.+?)\\s+into\\s+the\\s+Break\\s+Zone\\.\\s+If\\s+you\\s+do\\s+so,\\s+cancel\\s+its\\s+effect\\s+"
			+ "and\\s+break\\s+that\\s+Character[.!]?$");

	/**
	 * Moves {@code c} to the permanent RFP zone as an ability cost and records the instance in
	 * {@link MainWindow#lastRfgCostCards} so "you can cast [X] removed by this ability's cost"
	 * followups (Sephiroth) can find it.
	 *
	 * <p>{@code payerIsP1} is only the fallback owner: the zone is kept by owner, and a card that
	 * reached play the ordinary way is already in the identity map, which wins. It matters for a
	 * card the map has never heard of, where the old single-argument call filed it under P1 by
	 * default — so an ability P2 paid for put the card in the player's removed zone.
	 *
	 * <p>The label is refreshed here rather than by the callers because every route into the RFP
	 * zone is one of them, and the zone is on screen without being asked for: a removal none of
	 * them repainted left the card visibly still wherever it came from.
	 */
	private void removeCardAsCost(CardData c, boolean payerIsP1) {
		mw.gameState.addToPermanentRfp(c, payerIsP1);
		mw.lastRfgCostCards.add(c);
		mw.logEntry(c.name() + " → Removed From Game (cost)");
		mw.refreshP1WarpZoneUI();
		mw.refreshP2WarpZoneUI();
	}

	private void executeRemoveFromGameCost(RemoveFromGameCost rfg, boolean isP1) {
		switch (rfg.zone()) {
			case "DECK" -> {
				java.util.Deque<CardData> deck = isP1 ? mw.gameState.getP1MainDeck() : mw.gameState.getP2MainDeck();
				for (int i = 0; i < rfg.count() && !deck.isEmpty(); i++) {
					removeCardAsCost(deck.pollFirst(), isP1);
				}
				if (isP1) mw.refreshP1DeckLabel(); else mw.refreshP2DeckLabel();
			}
			case "HAND" -> {
				List<Integer> eligible = eligibleRfgHandIndices(rfg, isP1);
				List<CardData> hand = mw.playerHand(isP1);
				if (eligible.isEmpty()) mw.logEntry("No eligible hand card for remove-from-game cost.");
				List<Integer> slots = eligible.size() <= rfg.count() ? eligible
						: mw.decide(PlayerChoice.by(isP1, ChoiceKind.HAND_CARDS)
							.prompting("Waiting for your opponent to choose what to remove from the game...")
							.locally(() -> {
								List<CardData> pool = new ArrayList<>();
								for (int i : eligible) pool.add(hand.get(i));
								List<Integer> chosen = mw.showCardMultiImageChooser(pool,
										"Remove from game (cost)", rfg.count(), false, true);
								List<Integer> out = new ArrayList<>();
								if (chosen != null) for (int p : chosen) out.add(eligible.get(p));
								return out;
							})
							// The CPU spends its cheapest cards.
							.byCpu(() -> eligible.stream()
									.sorted(Comparator.comparingInt(i -> hand.get(i).cost()))
									.limit(rfg.count()).toList())
							.legalWhen(sel -> sel.size() <= rfg.count() && eligible.containsAll(sel)
									&& new HashSet<>(sel).size() == sel.size(),
									"only a matching card in hand can pay a remove-from-game cost"));
				List<Integer> descending = new ArrayList<>(slots);
				descending.sort(Collections.reverseOrder());
				for (int handIdx : descending) removeCardAsCost(hand.remove(handIdx), isP1);
				if (isP1) mw.refreshP1HandLabel(); else mw.refreshP2HandCountLabel();
			}
			case "BREAK_ZONE" -> {
				List<CardData> bz = isP1 ? mw.gameState.getP1BreakZone() : mw.gameState.getP2BreakZone();
				List<Integer> eligible = eligibleRfgBzIndices(rfg, isP1);
				List<Integer> slots;
				if (rfg.count() == -1 || eligible.size() <= rfg.count()) {
					// All matching cards, or no more than the cost takes: nothing to choose.
					if (eligible.isEmpty()) mw.logEntry("No eligible Break Zone card for remove-from-game cost.");
					slots = eligible;
				} else {
					List<ForwardTarget> pool = new ArrayList<>();
					for (int i : eligible) pool.add(new ForwardTarget(isP1, i, ForwardTarget.CardZone.BREAK_ZONE));
					slots = mw.selectOwnBreakZoneTargets(isP1, pool, bz, rfg.count(),
							"Remove from game (cost)",
							"Waiting for your opponent to choose what to remove from the game...",
							() -> new ArrayList<>(pool.subList(0, rfg.count())))
						.stream().map(ForwardTarget::idx).toList();
				}
				List<Integer> descending = new ArrayList<>(slots);
				descending.sort(Collections.reverseOrder());
				for (int bzIdx : descending) removeCardAsCost(bz.remove(bzIdx), isP1);
				// The payer's zone, not P1's: an ability P2 used from its own Break Zone left the
				// card it had just removed still showing on top of that pile.
				if (isP1) mw.refreshP1BreakLabel(); else mw.refreshP2BreakLabel();
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
						List<ForwardTarget> picks = eligible.size() <= rfg.count() ? eligible
								: mw.selectOwnFieldTargets(isP1, eligible, rfg.count(), false,
										"Remove from Game (field)",
										"Waiting for your opponent to choose what to remove from the game...",
										() -> new ArrayList<>(eligible.subList(0, rfg.count())));
						mw.applyTargetsHighestIndexFirst(picks, ctx::removeTargetFromGame);
					}
				}
			}
		}
	}
}
