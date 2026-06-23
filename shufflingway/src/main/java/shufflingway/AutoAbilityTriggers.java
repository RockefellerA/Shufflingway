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
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
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

import static shufflingway.CardAnimation.CARD_H;
import static shufflingway.CardAnimation.CARD_W;
import static shufflingway.CardFilters.matchesDiscardType;
import static shufflingway.CardFilters.meetsCardNameFilter;
import static shufflingway.CardFilters.meetsCategoryFilter;
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
				executeAutoAbilityImpl(it.ability(), it.source(), it.controllerIsP1());
			}
		} else {
			// No dialog: preserve historical iteration order (first walked = pushed
			// first = bottom of stack = resolves last).
			for (StackOrderingDialog.Item it : items) {
				executeAutoAbilityImpl(it.ability(), it.source(), it.controllerIsP1());
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
	 * Matches "put N [Job jobname / Card Name name / type] you control into the Break Zone.
	 * When/If you do so, sub-effect."
	 */
	private static final Pattern FA_PUT_INTO_BZ_WHEN_DO_SO =
			Pattern.compile(
				"(?i)^put\\s+(?<count>\\d+)\\s+" +
				"(?:" +
					"Job\\s+(?<job>.+?)\\s+you\\s+control" +
				"|" +
					"Card\\s+Name\\s+(?<cardname>\\S+(?:\\s+\\([^)]+\\))?)\\s+you\\s+control" +
				"|" +
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
	private static final Pattern FA_PUT_SELF_INTO_BZ_IF_DO_SO = Pattern.compile(
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

	/**
	 * Matches a card's own passive field ability text:
	 * "If &lt;cardName&gt; is dealt damage by abilities, reduce the damage by N instead."
	 * Gated by the surrounding {@link FieldAbility#damageThreshold()} when the parser captured
	 * a "Damage N --" prefix. Applied inline in {@link #modifyIncomingDamage} when the damage
	 * source is an ability (not a Summon, not combat).
	 */
	static final Pattern FA_REDUCE_ABILITY_DAMAGE =
			Pattern.compile(
				"(?i)If\\s+(?<card>.+?)\\s+is\\s+dealt\\s+damage\\s+by\\s+abilities,\\s+reduce\\s+the\\s+damage\\s+by\\s+(?<reduction>\\d+)\\s+instead\\.?"
			);

	/** "If [name] is dealt damage by an ability, the damage becomes 0 instead." — persistent passive nullification vs non-Summon abilities. */
	static final Pattern FA_NULLIFY_ABILITY_DAMAGE =
			Pattern.compile(
				"(?i)If\\s+(?<card>.+?)\\s+is\\s+dealt\\s+damage\\s+by\\s+an?\\s+abilit(?:y|ies),\\s+the\\s+damage\\s+becomes\\s+0\\s+instead\\.?"
			);

	/**
	 * General incoming-damage modifier field ability.
	 * Covers "reduce the damage by N", "the damage becomes N", and "the damage increases by N" variants,
	 * with optional source clauses: "by a Forward", "by [your opponent's] Summons [or abilities]",
	 * "by a Summon or an ability", "by [an] abilit[y|ies]", "other than battle damage", or no clause (any source).
	 * Also accepts "receives damage" as a synonym for "is dealt damage", and an optional threshold:
	 * "is dealt N damage or more" (captured in {@code threshold}) to apply the modifier only when damage ≥ N.
	 * Groups: {@code card}, {@code threshold} (optional), {@code sourceclause} (optional),
	 * {@code reduceby} (optional), {@code setsto} (optional), {@code increaseby} (optional).
	 */
	static final Pattern FA_DAMAGE_MODIFIER = Pattern.compile(
		"(?i)^If\\s+(?<card>.+?)\\s+(?:is\\s+dealt|receives)\\s+(?:(?<threshold>\\d+)\\s+damage\\s+or\\s+more|damage)" +
		"(?<sourceclause>" +
			"\\s+by\\s+a\\s+Forward" +
			"|\\s+other\\s+than\\s+battle\\s+damage" +
			"|\\s+by\\s+(?:your\\s+opponent's\\s+)?(?:a\\s+)?Summons?(?:\\s+or\\s+(?:an?\\s+)?abilit(?:y|ies))?" +
			"|\\s+by\\s+(?:a\\s+Summon\\s+or\\s+)?an?\\s+abilit(?:y|ies)" +
			"|\\s+less\\s+than\\s+(?:his|her|its)\\s+power" +
		")?" +
		"\\s*,\\s+" +
		"(?:reduce\\s+the\\s+damage\\s+by\\s+(?<reduceby>\\d+)|the\\s+damage\\s+becomes\\s+(?<setsto>\\d+)|the\\s+damage\\s+increases\\s+by\\s+(?<increaseby>\\d+))" +
		"\\s+instead\\.?$"
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
	 * Field-wide incoming-damage modifier: "If a [Category X | Job Y] Forward [of cost N or less/more]
	 * [other than Z] you control [other than Z] is dealt damage [less than its power | by a Backup],
	 * [reduce the damage by N | the damage becomes N] instead."
	 * Groups: {@code category}, {@code job}, {@code cost}, {@code costcmp},
	 * {@code except1} (before "you control"), {@code except2} (after "you control"),
	 * {@code sourceclause}, {@code reduceby}, {@code setsto}.
	 */
	static final Pattern FA_FIELD_DAMAGE_MODIFIER = Pattern.compile(
		"(?i)^If\\s+a\\s+" +
		"(?:Category\\s+(?<category>\\S+)\\s+|Job\\s+(?<job>.+?)\\s+(?=Forward))?" +
		"Forward(?:\\s+of\\s+cost\\s+(?<cost>\\d+)\\s+or\\s+(?<costcmp>less|more))?" +
		"(?:\\s+other\\s+than\\s+(?<except1>.+?))?" +
		"\\s+you\\s+control" +
		"(?:\\s+other\\s+than\\s+(?<except2>.+?))?" +
		"\\s+is\\s+dealt\\s+damage" +
		"(?<sourceclause>\\s+less\\s+than\\s+its\\s+power|\\s+by\\s+a\\s+Backup)?" +
		"\\s*,\\s+" +
		"(?:reduce\\s+the\\s+damage\\s+by\\s+(?<reduceby>\\d+)|the\\s+damage\\s+becomes\\s+(?<setsto>\\d+))" +
		"\\s+instead\\.?$"
	);

	/**
	 * Party-forming damage protection: "If a Forward forming a party with [CardName] is dealt damage,
	 * the damage becomes 0 instead."
	 * Group: {@code source} — the card name whose party membership triggers the protection.
	 */
	static final Pattern FA_PARTY_DAMAGE_PROTECTION = Pattern.compile(
		"(?i)^If\\s+a\\s+Forward\\s+forming\\s+a\\s+party\\s+with\\s+(?<source>.+?)\\s+is\\s+dealt\\s+damage,\\s+the\\s+damage\\s+becomes\\s+0\\s+instead\\.?$"
	);

	/** "If [name] deals damage to a Forward of cost N or more, double the damage instead." */
	static final Pattern FA_DOUBLE_DAMAGE_VS_COST_THRESHOLD =
			Pattern.compile(
				"(?i)If\\s+(?<name>.+?)\\s+deals?\\s+damage\\s+to\\s+a\\s+Forward\\s+of\\s+cost\\s+(?<cost>\\d+)" +
				"\\s+or\\s+more,\\s+double\\s+the\\s+damage\\s+instead[.!]?"
			);

	/** "If [name] deals damage to a Forward due to an ability, double the damage instead." */
	static final Pattern FA_DOUBLE_ABILITY_DAMAGE =
			Pattern.compile(
				"(?i)If\\s+(?<name>.+?)\\s+deals?\\s+damage\\s+to\\s+a\\s+Forward\\s+due\\s+to\\s+an\\s+ability,\\s+double\\s+the\\s+damage\\s+instead[.!]?"
			);

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

	/** Returns true if {@code card} has an ETF auto-ability with the reveal-summons-conditional pattern. */
	static boolean hasRevealSummonsConditionalEtf(CardData card) {
		for (AutoAbility fa : card.autoAbilities()) {
			if (!fa.trigger().contains("enter")) continue;
			if (FA_REVEAL_SUMMONS_CONDITIONAL.matcher(fa.effectText()).find()) return true;
		}
		return false;
	}

	void triggerAutoAbilitiesForEntersField(CardData card, boolean isP1) {
		if (mw.suppressAutoAbilityForNextCard) {
			mw.suppressAutoAbilityForNextCard = false;
			// Re-evaluate field boosts even when ETF auto-abilities are suppressed
			mw.refreshAllForwardSlots();
			for (int i = 0; i < mw.p2ForwardCards.size(); i++) mw.refreshP2ForwardSlot(i);
			return;
		}
		withBatch(() -> {
			for (AutoAbility fa : card.autoAbilities()) {
				if (!fa.triggerCard().equalsIgnoreCase(card.name())) continue;
				if (!fa.trigger().contains("enter")) continue;
				// "enters your field other than from your hand" — skip when played normally from hand
				if (fa.trigger().equals("enters your field not from hand") && mw.lastCardWasCast) continue;
				executeAutoAbility(fa, card, isP1);
			}
			// Watcher dispatch: "When a <Type> enters your field, ..." abilities live on other field cards
			// on the same side as the entering card.
			fireEntersYourFieldWatchers(card, isP1);
			// Also fire watcher abilities on break-zone cards (only those gated by bzConditionCard).
			fireEntersYourFieldBreakZoneWatchers(card, isP1);
			// Watcher dispatch: "When a <Type> of your opponent enters the field, ..." lives on the
			// opponent's cards and uses trigger "enters opponent's field".
			fireEntersOpponentFieldWatchers(card, isP1);
		});
		// Re-evaluate all conditional field boosts now that the field composition has changed
		mw.refreshAllForwardSlots();
		for (int i = 0; i < mw.p2ForwardCards.size(); i++) mw.refreshP2ForwardSlot(i);
		mw.showStackWindowIfNeeded();
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
		for (AutoAbility fa : watcher.autoAbilities()) {
			if (!fa.trigger().equals("enters your field")) continue;
			if (!matchesEntersFieldSubject(fa.triggerCard(), enteringCard)) continue;
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
			for (AutoAbility fa : c.autoAbilities()) {
				if (!fa.trigger().equals("enters your field")) continue;
				if (fa.bzConditionCard().isEmpty()) continue;
				if (!matchesEntersFieldSubject(fa.triggerCard(), enteringCard)) continue;
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
		List<CardData> fwds = new ArrayList<>(watcherIsP1 ? mw.p1ForwardCards : mw.p2ForwardCards);
		CardData[]     bkps = watcherIsP1 ? mw.p1BackupCards : mw.p2BackupCards;
		List<CardData> mons = new ArrayList<>(watcherIsP1 ? mw.p1MonsterCards : mw.p2MonsterCards);
		for (CardData c : fwds) fireEntersOpponentFieldWatcher(c, enteringCard, watcherIsP1);
		for (CardData c : bkps) if (c != null) fireEntersOpponentFieldWatcher(c, enteringCard, watcherIsP1);
		for (CardData c : mons) fireEntersOpponentFieldWatcher(c, enteringCard, watcherIsP1);
	}

	private void fireEntersOpponentFieldWatcher(CardData watcher, CardData enteringCard, boolean watcherIsP1) {
		for (AutoAbility fa : watcher.autoAbilities()) {
			if (!fa.trigger().equals("enters opponent's field")) continue;
			if (!matchesEntersFieldSubject(fa.triggerCard(), enteringCard)) continue;
			executeAutoAbility(fa, watcher, watcherIsP1);
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
	private boolean matchesEntersFieldSubject(String subject, CardData enteringCard) {
		if (subject == null || subject.isBlank()) return false;
		for (String part : subject.split("(?i)\\s+or\\s+")) {
			if (matchesSingleSubject(part.trim(), enteringCard)) return true;
		}
		return false;
	}

	private boolean matchesSingleSubject(String subject, CardData enteringCard) {
		if (subject.isEmpty()) return false;
		// "a [X] other than [Name]" — match base subject but exclude the named card
		Matcher otherThanM = java.util.regex.Pattern.compile(
				"(?i)^(.+?)\\s+other\\s+than\\s+(.+)$").matcher(subject);
		if (otherThanM.matches())
			return matchesSingleSubject(otherThanM.group(1).trim(), enteringCard)
				&& !CardFilters.meetsCardNameFilter(enteringCard, otherThanM.group(2).trim());
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
			return enteringCard.containsElement(elemTypeM.group("elem"))
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

	void triggerAutoAbilitiesForDealsDamageToOpponent(CardData attacker, boolean attackerIsP1) {
		withBatch(() -> {
			for (AutoAbility fa : attacker.autoAbilities()) {
				if (!fa.triggerCard().equalsIgnoreCase(attacker.name())) continue;
				if (fa.trigger().equals("deals damage to opponent")) executeAutoAbility(fa, attacker, attackerIsP1);
			}
		});
		mw.showStackWindowIfNeeded();
	}

	void triggerAutoAbilitiesForPrimedInto(CardData primingCard, CardData primedCard, boolean primedCardIsP1) {
		withBatch(() -> {
			for (AutoAbility fa : primedCard.autoAbilities()) {
				if (!fa.triggerCard().equalsIgnoreCase(primingCard.name())) continue;
				if (fa.trigger().equals("primed into")) executeAutoAbility(fa, primedCard, primedCardIsP1);
			}
		});
		mw.showStackWindowIfNeeded();
	}

	void triggerAutoAbilitiesForAttack(CardData card, boolean isP1) {
		withBatch(() -> {
			for (AutoAbility fa : card.autoAbilities()) {
				if (!fa.triggerCard().equalsIgnoreCase(card.name())) continue;
				if (fa.trigger().contains("attack")) executeAutoAbility(fa, card, isP1);
			}
			// "When 1 or more Forwards you control attack" — fires on any controller field card
			List<CardData> fwds = new ArrayList<>(isP1 ? mw.p1ForwardCards : mw.p2ForwardCards);
			for (CardData c : fwds)
				for (AutoAbility fa : c.autoAbilities())
					if (fa.trigger().equals("attack")) executeAutoAbility(fa, c, isP1);
			List<CardData> monsters = new ArrayList<>(isP1 ? mw.p1MonsterCards : mw.p2MonsterCards);
			for (CardData c : monsters)
				for (AutoAbility fa : c.autoAbilities())
					if (fa.trigger().equals("attack")) executeAutoAbility(fa, c, isP1);
			CardData[] bkps = isP1 ? mw.p1BackupCards : mw.p2BackupCards;
			for (CardData c : bkps)
				if (c != null)
					for (AutoAbility fa : c.autoAbilities())
						if (fa.trigger().equals("attack")) executeAutoAbility(fa, c, isP1);
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
			for (AutoAbility fa : card.autoAbilities()) {
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
			for (AutoAbility fa : card.autoAbilities()) {
				if (!fa.triggerCard().equalsIgnoreCase(card.name())) continue;
				String t = fa.trigger();
				if (t.equals("is blocked") || t.equals("blocks or is blocked"))
					executeAutoAbility(fa, card, isP1);
			}
		});
		mw.showStackWindowIfNeeded();
	}

	/**
	 * Fires "party attacks" field abilities on every card the controller has on the field,
	 * filtering by any party-composition requirements encoded in the {@link AutoAbility}.
	 *
	 * @param partyMembers the CardData objects that are attacking in the party
	 */
	void triggerAutoAbilitiesForPartyAttack(boolean isP1, List<CardData> partyMembers) {
		withBatch(() -> {
			List<CardData> fwds = new ArrayList<>(isP1 ? mw.p1ForwardCards : mw.p2ForwardCards);
			for (CardData card : fwds) {
				for (AutoAbility fa : card.autoAbilities()) {
					if (!fa.trigger().equals("party attacks")) continue;
					if (!partyAttackMatchesFilter(fa, partyMembers)) continue;
					executeAutoAbility(fa, card, isP1);
				}
			}
			CardData[] bkps = isP1 ? mw.p1BackupCards : mw.p2BackupCards;
			for (CardData card : bkps) {
				if (card == null) continue;
				for (AutoAbility fa : card.autoAbilities()) {
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
					.anyMatch(m -> m.name().equalsIgnoreCase(fa.partyCardName()));
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

	/** Subject pattern for break-zone triggers: "a [Type] [you|opponent] control[s]". */
	private static final Pattern BZ_SUBJECT_TYPE = Pattern.compile(
		"(?i)^a\\s+(?<type>Character|Forward|Backup|Monster)\\s+(?<ctrl>you|opponent)\\s+controls?$"
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

		Matcher m = BZ_SUBJECT_TYPE.matcher(subject);
		if (m.matches()) {
			boolean selfCtrl     = m.group("ctrl").equalsIgnoreCase("you");
			boolean brokenByOwner = (brokenIsP1 == abilityOwnerIsP1);
			if (selfCtrl != brokenByOwner) return false;
			return switch (m.group("type").toLowerCase(java.util.Locale.ROOT)) {
				case "forward"   -> broken.isForward();
				case "backup"    -> broken.isBackup();
				case "monster"   -> broken.isMonster();
				default          -> !broken.isSummon(); // "Character" = any non-Summon field card
			};
		}
		// Fall back to named card match (handles "Geomancer", etc.)
		return broken.name().equalsIgnoreCase(subject);
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
		});
		mw.showStackWindowIfNeeded();
	}

	private void fireBreakZoneTriggers(CardData card, boolean ownerIsP1, CardData broken,
			boolean brokenIsP1, Set<CardData> partyMembers) {
		for (AutoAbility fa : card.autoAbilities()) {
			if (!fa.trigger().equals("put into break zone")) continue;
			if (!matchesBreakZoneSubject(fa, card, broken, brokenIsP1, ownerIsP1, partyMembers)) continue;
			executeAutoAbility(fa, card, ownerIsP1);
		}
	}

	/**
	 * Fires "leaves the field" field abilities that belong to {@code departing} itself.
	 * Call this after the card has been removed from all field tracking lists.
	 */
	void triggerAutoAbilitiesForLeavesField(CardData departing, boolean isP1) {
		withBatch(() -> {
			for (AutoAbility fa : departing.autoAbilities()) {
				if (!fa.trigger().equals("leaves the field")) continue;
				if (!fa.triggerCard().equalsIgnoreCase(departing.name())) continue;
				executeAutoAbility(fa, departing, isP1);
			}
		});
		mw.gameState.clearCounters(departing);
		// Re-evaluate all conditional field boosts now that the field composition has changed
		mw.refreshAllForwardSlots();
		for (int i = 0; i < mw.p2ForwardCards.size(); i++) mw.refreshP2ForwardSlot(i);
		mw.showStackWindowIfNeeded();
		// If a Forward just left, check the other player's field cards for
		// "if your opponent doesn't control Forwards" field abilities
		if (departing.isForward()) mw.fireOppNoForwardsFieldAbilities(!isP1);
	}

	/** Fires "cast summon" field abilities for all field cards belonging to the casting player. */
	void triggerAutoAbilitiesForCastSummon(boolean isP1) {
		triggerAutoAbilitiesForEvent("cast summon", isP1);
	}

	/** Fires "damage zone" field abilities for all field cards belonging to the player who took damage. */
	void triggerAutoAbilitiesForDamageZone(boolean isP1) {
		triggerAutoAbilitiesForEvent("damage zone", isP1);
	}

	/** Fires "beginning of attack phase" auto-abilities on all field cards belonging to the active player. */
	void triggerAutoAbilitiesForBeginningOfAttackPhase(boolean isP1) {
		triggerAutoAbilitiesForEvent("beginning of attack phase", isP1);
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
			nameLabel.setFont(FontLoader.loadPixelNESFont(9));
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
			declineBtn.setFont(FontLoader.loadPixelNESFont(11));
			declineBtn.addActionListener(ae -> { mw.hideZoom(); dlg.dispose(); });
			JButton okBtn = new JButton("OK");
			okBtn.setFont(FontLoader.loadPixelNESFont(11));
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
				for (AutoAbility fa : card.autoAbilities())
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
				for (AutoAbility fa : card.autoAbilities())
					if (fa.trigger().equals("warp counter removed")
							&& (fa.triggerCard().equalsIgnoreCase("any player's card") || fa.triggerCard().equalsIgnoreCase(target.name())))
						executeAutoAbility(fa, card, isP1);
		});
		mw.showStackWindowIfNeeded();
	}

	private void triggerAutoAbilitiesForEvent(String triggerType, boolean isP1) {
		withBatch(() -> {
			List<CardData> fwds = new ArrayList<>(isP1 ? mw.p1ForwardCards : mw.p2ForwardCards);
			CardData[]     bkps = isP1 ? mw.p1BackupCards : mw.p2BackupCards;
			List<CardData> mons = new ArrayList<>(isP1 ? mw.p1MonsterCards : mw.p2MonsterCards);
			for (CardData c : fwds) fireEventTriggers(c, isP1, triggerType);
			for (CardData c : bkps) if (c != null) fireEventTriggers(c, isP1, triggerType);
			for (CardData c : mons) fireEventTriggers(c, isP1, triggerType);
		});
		mw.showStackWindowIfNeeded();
	}

	private void fireEventTriggers(CardData card, boolean isP1, String triggerType) {
		for (AutoAbility fa : card.autoAbilities())
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
		if (mw.lostAbilitiesCards.contains(source)) return;
		if (pendingBatch != null) {
			pendingBatch.add(new StackOrderingDialog.Item(fa, source, isP1));
			return;
		}
		executeAutoAbilityImpl(fa, source, isP1);
	}

	private void executeAutoAbilityImpl(AutoAbility fa, CardData source, boolean isP1) {
		// Damage threshold: skip if the controlling player doesn't have enough damage counters
		if (fa.damageThreshold() > 0) {
			int dmg = isP1 ? mw.gameState.getP1DamageZone().size() : mw.gameState.getP2DamageZone().size();
			if (dmg < fa.damageThreshold()) return;
		}

		// "only during your turn" — skip when the ability owner is not the active player
		if (fa.yourTurnOnly() && !isP1) return;

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
		if (!fa.bzConditionCard().isEmpty()) {
			String cond = fa.bzConditionCard();
			List<CardData> bz = isP1 ? mw.gameState.getP1BreakZone() : mw.gameState.getP2BreakZone();
			if (bz.stream().noneMatch(c -> CardFilters.meetsCardNameFilter(c, cond))) return;
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

		mw.logEntry("[AutoAbility] " + source.name() + " — pushed to stack");
		mw.gameState.pushStack(new StackEntry(source, null, fa, effectIsP1, 0, false));
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
		String subEffect     = m.group("sub").trim();

		String jobFilter      = jobRaw      != null ? jobRaw.trim()      : null;
		String cardNameFilter = cardNameRaw != null ? cardNameRaw.trim() : null;
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
				false, true, null, null, -1, null, -1, null,
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

		// "If you do so" = controller may decline
		if (isP1) {
			int choice = mw.showEffectOptionDialog(source.name() + " — " + fa.effectText(),
					"Auto Ability", new Object[]{"Put into Break Zone", "Decline"});
			if (choice != 0) {
				mw.logEntry("[AutoAbility] " + source.name() + " — self-break declined");
				return;
			}
		} else {
			mw.logEntry("[AutoAbility] [AI] auto-accepts self-break for " + source.name());
		}

		// Find the source Monster on the field and break it directly (no selection dialog needed)
		List<CardData> mons = isP1 ? mw.p1MonsterCards : mw.p2MonsterCards;
		int idx = -1;
		for (int i = 0; i < mons.size(); i++) {
			if (CardFilters.meetsCardNameFilter(mons.get(i), source.name())) { idx = i; break; }
		}
		if (idx < 0) {
			mw.logEntry("[AutoAbility] " + source.name() + " — no longer on field, sub-effect skipped");
			return;
		}
		mw.buildGameContext(isP1).forceTargetToBreakZone(
				new ForwardTarget(isP1, idx, ForwardTarget.CardZone.MONSTER));

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
		showAutoAbilityPaymentDialog(source.name(), fixedCost, maxCp, isP1,
				paid -> applyPayWhenDoSoEffect(finalSubEffect, source, paid, effectIsP1));
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
	private int aiPayCp(boolean payerIsP1, int target) {
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
		Matcher qm = ActionResolver.SELECT_FOLLOWING_QUOTED_ACTION.matcher(actionsRaw);
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
		confirmBtn.setFont(FontLoader.loadPixelNESFont(11));

		if (singlePick) {
			// ── Radio buttons — exactly one action ──
			javax.swing.ButtonGroup group = new javax.swing.ButtonGroup();
			javax.swing.JRadioButton[] radios = new javax.swing.JRadioButton[n];
			for (int i = 0; i < n; i++) {
				javax.swing.JRadioButton rb = new javax.swing.JRadioButton(
						"<html><body style='width:340px'>" + actions.get(i) + "</body></html>");
				rb.setFont(FontLoader.loadPixelNESFont(10));
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
			countLbl.setFont(FontLoader.loadPixelNESFont(10));

			for (int i = 0; i < n; i++) {
				javax.swing.JCheckBox cb = new javax.swing.JCheckBox(
						"<html><body style='width:340px'>" + actions.get(i) + "</body></html>");
				cb.setFont(FontLoader.loadPixelNESFont(10));
				checks[i] = cb;
				cb.addItemListener(ie -> {
					int sel = 0;
					for (javax.swing.JCheckBox c : checks) if (c.isSelected()) sel++;
					countLbl.setText("Selected: " + sel + " / " + selectCount + (upTo ? " (up to)" : ""));
					// For exact selection: disable unchecked boxes once limit is reached
					if (!upTo && sel >= selectCount) {
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
		mw.logEntry("[AutoAbility] Unrecognized condition (defaulting to true): " + condition);
		return true;
	}

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
	 * Shows backup cards (1 CP each) and hand cards to discard (2 CP each).
	 * Calls {@code onConfirm} with total CP paid after dulling backups / discarding cards.
	 */
	void showAutoAbilityPaymentDialog(String cardName, int minCp, int maxCp,
			boolean isP1, java.util.function.IntConsumer onConfirm) {
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
		cpLabel.setFont(FontLoader.loadPixelNESFont(11));
		cpLabel.setHorizontalAlignment(SwingConstants.CENTER);

		JButton confirmBtn = new JButton("Confirm");
		confirmBtn.setFont(FontLoader.loadPixelNESFont(11));

		List<JLabel>  backupLbls  = new ArrayList<>();
		List<Integer> backupSlots = new ArrayList<>();
		List<JLabel>  discardLbls = new ArrayList<>();
		List<Integer> discardIdxs = new ArrayList<>();

		boolean[] canAddBackup  = {true};
		boolean[] canAddDiscard = {true};

		Runnable updateAll = () -> {
			int total  = selectedBackups.size() + selectedDiscards.size() * 2;
			if (minCp == maxCp) {
				// Fixed cost: mirrors showActionAbilityPaymentDialog overpayment rules.
				// Allow up to 1 extra CP if cost is odd (a 2-CP discard can't be split).
				int maxAllowed = maxCp + (maxCp % 2);
				canAddBackup[0]  = total < maxCp;
				canAddDiscard[0] = total < maxCp && total + 2 <= maxAllowed;
			} else {
				// Variable X cost: strict cap at maxCp.
				boolean atMax = maxCp != Integer.MAX_VALUE && total >= maxCp;
				canAddBackup[0]  = !atMax;
				canAddDiscard[0] = maxCp == Integer.MAX_VALUE || total + 2 <= maxCp;
			}
			confirmBtn.setEnabled(total >= minCp);

			String cap = maxCp == Integer.MAX_VALUE ? "∞" : String.valueOf(maxCp);
			cpLabel.setText("CP paid: " + total + " / " + cap
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
			hdr.setFont(FontLoader.loadPixelNESFont(9)); hdr.setAlignmentX(Component.LEFT_ALIGNMENT);
			JPanel bp = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6)); bp.setAlignmentX(Component.LEFT_ALIGNMENT);
			for (int slot : eligibleBackupSlots) {
				JLabel lbl = new JLabel("...", SwingConstants.CENTER);
				lbl.setPreferredSize(new Dimension(CARD_W, CARD_H)); lbl.setMinimumSize(new Dimension(CARD_W, CARD_H));
				lbl.setOpaque(true); lbl.setBackground(Color.DARK_GRAY); lbl.setForeground(Color.WHITE);
				lbl.setFont(FontLoader.loadPixelNESFont(10)); lbl.setBorder(BorderFactory.createLineBorder(Color.GRAY, 1));
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
			JLabel discHdr = new JLabel("Hand — discard for 2 CP each:");
			discHdr.setFont(FontLoader.loadPixelNESFont(9)); discHdr.setAlignmentX(Component.LEFT_ALIGNMENT);
			JPanel dp = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6)); dp.setAlignmentX(Component.LEFT_ALIGNMENT);
			for (int i = 0; i < hand.size(); i++) {
				final int hi = i; CardData hc = hand.get(i); boolean payable = !hc.isLightOrDark();
				JLabel lbl = new JLabel("...", SwingConstants.CENTER);
				lbl.setPreferredSize(new Dimension(CARD_W, CARD_H)); lbl.setMinimumSize(new Dimension(CARD_W, CARD_H));
				lbl.setOpaque(true); lbl.setBackground(payable ? Color.DARK_GRAY : new Color(50, 50, 50));
				lbl.setForeground(Color.WHITE); lbl.setFont(FontLoader.loadPixelNESFont(10));
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
		cancelBtn.setFont(FontLoader.loadPixelNESFont(11));
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
			int paid = selectedBackups.size() + selectedDiscards.size() * 2;
			mw.logEntry("[AutoAbility] " + cardName + " — paid " + paid + " CP");
			mw.refreshP1HandLabel();
			mw.refreshP1BreakLabel();
			onConfirm.accept(paid);
		});

		JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 6));
		buttonPanel.add(confirmBtn); buttonPanel.add(cancelBtn);

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
		List<String> rawCost = ability.cpCost();
		List<BreakZoneCost> bzCosts = ability.breakZoneCosts();

		if (rawCost.isEmpty() && !ability.hasXCost()) {
			List<ForwardTarget> bzTargets = resolveBzCostTargetsForBzAbility(bzCosts, isP1);
			if (bzTargets == null) return;
			executeAbilityPayment(ability, source, () -> {}, new ArrayList<>(), new ArrayList<>(), bzTargets, isP1, 0);
			return;
		}

		new AbilityPaymentDialog(mw.frame, ability, source,
				mw.playerHand(isP1), mw.playerBackupCards(isP1), mw.playerBackupStates(isP1), mw.playerBackupUrls(isP1),
				mw::showZoomAt, mw::hideZoom,
				(discards, backups, xValue) -> {
					List<ForwardTarget> bzTargets = resolveBzCostTargetsForBzAbility(bzCosts, isP1);
					if (bzTargets == null) return;
					executeAbilityPayment(ability, source, () -> {}, discards, backups, bzTargets, isP1, xValue);
				})
			.show();
	}

	/**
	 * Builds the BZ-target list for ability payment by finding the source card's
	 * current field position.  The BZ cost is always "put itself into the Break Zone",
	 * so no player selection is needed — one entry is added per cost item.
	 */
	private List<ForwardTarget> autoResolveBzTargets(CardData source, List<BreakZoneCost> bzCosts, boolean isP1) {
		if (bzCosts.isEmpty()) return List.of();
		List<ForwardTarget> result = new ArrayList<>();

		for (BreakZoneCost bz : bzCosts) {
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

	/** True when {@code source} (the activating card) has enough counters to pay {@code cc}. */
	boolean counterCostSatisfied(CounterCost cc, CardData source) {
		if (!source.name().equalsIgnoreCase(cc.cardName())) return false;
		return mw.gameState.getCounters(source, cc.counterName()) >= cc.count();
	}

	boolean dullForwardCostSatisfied(DullForwardCost dfc, boolean isP1) {
		boolean anyChar = "Character".equalsIgnoreCase(dfc.cardType());
		List<CardData>  fwds    = isP1 ? mw.p1ForwardCards  : mw.p2ForwardCards;
		List<CardState> fwdSt   = isP1 ? mw.p1ForwardStates : mw.p2ForwardStates;
		List<CardData>  mons    = isP1 ? mw.p1MonsterCards  : mw.p2MonsterCards;
		CardData[]      bkps    = isP1 ? mw.p1BackupCards   : mw.p2BackupCards;
		int eligible = 0;
		for (int i = 0; i < fwds.size(); i++) {
			if (fwdSt.get(i) != CardState.ACTIVE) continue;
			if (!dfcCardMatches(dfc, fwds.get(i))) continue;
			eligible++;
		}
		if (anyChar) {
			for (CardData bkp : bkps)
				if (bkp != null && dfcCardMatches(dfc, bkp)) eligible++;
			for (CardData mon : mons)
				if (dfcCardMatches(dfc, mon)) eligible++;
		}
		return eligible >= dfc.count();
	}

	private boolean dfcCardMatches(DullForwardCost dfc, CardData card) {
		if (dfc.cardName() != null && !card.name().equalsIgnoreCase(dfc.cardName())) return false;
		if (dfc.element()  != null && !dfc.element().isEmpty() && !card.containsElement(dfc.element())) return false;
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
				if (elemFilt != null && !fwds.get(i).containsElement(elemFilt)) continue;
				result.add(new ForwardTarget(isP1, i, ForwardTarget.CardZone.FORWARD));
			}
		} else if (last.equalsIgnoreCase("Backup")) {
			for (int i = 0; i < bkps.length; i++) {
				if (bkps[i] == null) continue;
				if (elemFilt != null && !bkps[i].containsElement(elemFilt)) continue;
				result.add(new ForwardTarget(isP1, i, ForwardTarget.CardZone.BACKUP));
			}
		} else if (last.equalsIgnoreCase("Monster")) {
			for (int i = 0; i < mons.size(); i++) {
				if (elemFilt != null && !mons.get(i).containsElement(elemFilt)) continue;
				result.add(new ForwardTarget(isP1, i, ForwardTarget.CardZone.MONSTER));
			}
			for (int i = 0; i < fwds.size(); i++) {
				if (!fwds.get(i).alsoCountsAsMonster()) continue;
				if (elemFilt != null && !fwds.get(i).containsElement(elemFilt)) continue;
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
		if (rfg.element()     != null && !c.containsElement(rfg.element()))           return false;
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
		mw.addToP1BreakZone(c);
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
		mw.refreshP1BreakLabel();
		triggerAutoAbilitiesForLeavesField(c, true);
		triggerAutoAbilitiesForBreakZone(c, true, Collections.emptySet());
	}

	void breakP1MonsterSlot(int idx) {
		if (idx >= mw.p1MonsterCards.size()) return;
		mw.startBreakAnim(mw.p1MonsterLabels.get(idx));
		CardData c = mw.p1MonsterCards.get(idx);
		mw.logEntry(c.name() + " → Break Zone");
		mw.addToP1BreakZone(c);
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
		List<ActionAbility> abilities = card.actionAbilities();
		List<ActionAbility> tempAbilities = (isP1 ? mw.p1TempGrantedAbilities : mw.p2TempGrantedAbilities)
				.getOrDefault(card, List.of());
		if (abilities.isEmpty() && tempAbilities.isEmpty()) return;

		GameState.GamePhase phase = mw.gameState.getCurrentPhase();
		boolean isMainPhase  = phase == GameState.GamePhase.MAIN_1 || phase == GameState.GamePhase.MAIN_2;
		boolean isAttackPhase = phase == GameState.GamePhase.ATTACK;

		for (ActionAbility ability : abilities) {
			if (ability.whileCardInHand()) continue; // only usable from hand, not from the field
			if (ability.breakZoneOnly() != null) continue; // only usable from Break Zone
			boolean hasAttackRestriction = ability.whileCardAttacking() != null
					|| ability.whileCardBlocking() != null || ability.whilePartyAttacking()
					|| ability.hasBlockingTargetEffect();
			boolean phaseOk = hasAttackRestriction ? isAttackPhase : (isMainPhase || (isAttackPhase && mw.attackSubStep == 0));
			boolean abilityEnabled = phaseOk && mw.canActivateAbility(ability, isFrozen, state, playedTurn, card, isP1);
			JMenuItem item = new JMenuItem(abilityEnabled ? mw.buildAbilityMenuLabelHtml(ability) : mw.buildAbilityMenuLabel(ability));
			item.setEnabled(abilityEnabled);
			item.addActionListener(ae ->
					showActionAbilityPaymentDialog(ability, card, applyDull, isP1));
			menu.add(item);
		}

		for (ActionAbility ability : tempAbilities) {
			boolean abilityEnabled = isMainPhase && mw.canActivateAbility(ability, isFrozen, state, playedTurn, card, isP1);
			JMenuItem item = new JMenuItem(abilityEnabled ? mw.buildAbilityMenuLabelHtml(ability) : mw.buildAbilityMenuLabel(ability));
			item.setEnabled(abilityEnabled);
			item.addActionListener(ae ->
					showActionAbilityPaymentDialog(ability, card, () -> {}, isP1));
			menu.add(item);
		}
	}

	/**
	 * Payment dialog for an action ability.  Mirrors the Priming payment dialog
	 * but also handles Dull cost (dulls the source card) and Special cost (discards
	 * a same-name card from hand).  On successful payment calls
	 * {@link ActionResolver#resolve}.
	 */
	void showActionAbilityPaymentDialog(ActionAbility ability, CardData source,
			Runnable applyDull, boolean isP1) {
		List<String> rawCost = ability.cpCost();
		List<BreakZoneCost> bzCosts = ability.breakZoneCosts();

		// Zero CP + no X: confirm immediately
		if (rawCost.isEmpty() && !ability.hasXCost()) {
			executeAbilityPayment(ability, source, applyDull, new ArrayList<>(), new ArrayList<>(),
					autoResolveBzTargets(source, bzCosts, isP1), isP1, 0);
			return;
		}

		new AbilityPaymentDialog(mw.frame, ability, source,
				mw.playerHand(isP1), mw.playerBackupCards(isP1), mw.playerBackupStates(isP1), mw.playerBackupUrls(isP1),
				mw::showZoomAt, mw::hideZoom,
				(discards, backups, xValue) -> executeAbilityPayment(ability, source, applyDull,
						discards, backups, autoResolveBzTargets(source, bzCosts, isP1), isP1, xValue))
			.show();
	}


	/**
	 * Executes a P2 (CPU) action ability with pre-computed payment lists.
	 * Skips all UI dialogs; discard-cost and dull-forward-cost extras are auto-resolved.
	 * {@code xValue} is the chosen X for X-cost abilities (active backups remaining after base
	 * payment, min 1); pass 0 for abilities that have no X in their cost.
	 */
	void executeP2AbilityActivation(ActionAbility ability, CardData source,
			Runnable applyDull, List<Integer> backupDullIndices, List<Integer> discardIndices, int xValue) {
		List<ForwardTarget> bzTargets = autoResolveBzTargets(source, ability.breakZoneCosts(), false);
		executeAbilityPayment(ability, source, applyDull, discardIndices, backupDullIndices, bzTargets, false, xValue);
	}

	/**
	 * Executes the full payment for an action ability: dulls selected backups,
	 * discards hand cards for CP, optionally dulls the source card, optionally
	 * discards a same-name card (Special), then calls {@link ActionResolver#resolve}.
	 */
	private void executeAbilityPayment(ActionAbility ability, CardData source,
			Runnable applyDull, List<Integer> discardIndices, List<Integer> backupDullIndices,
			List<ForwardTarget> bzTargets, boolean isP1, int xValue) {
		List<String> rawCost = ability.cpCost();
		LinkedHashMap<String, Integer> costByElem = new LinkedHashMap<>();
		for (String e : rawCost) if (!e.isEmpty()) costByElem.merge(e, 1, Integer::sum);
		String[] elems = costByElem.keySet().toArray(String[]::new);

		CardData[]  bkpCards  = mw.playerBackupCards(isP1);
		CardState[] bkpStates = mw.playerBackupStates(isP1);
		for (int bi : backupDullIndices) {
			bkpStates[bi] = CardState.DULL;
			mw.playerDullBackupSlot(isP1, bi);
			String cpElem = matchesAnyElement(bkpCards[bi], elems)
					? contributingElement(bkpCards[bi], elems) : (elems.length > 0 ? elems[0] : "");
			if (!cpElem.isEmpty()) mw.playerAddCp(isP1, cpElem, 1);
		}
		discardIndices.sort(Collections.reverseOrder());
		for (int di : discardIndices) {
			CardData discarded = mw.playerHand(isP1).get(di);
			String cpElem = matchesAnyElement(discarded, elems)
					? contributingElement(discarded, elems) : (elems.length > 0 ? elems[0] : "");
			if (!cpElem.isEmpty()) mw.playerAddCp(isP1, cpElem, 2);
			mw.playerBreakFromHand(isP1, di);
		}
		for (String e : elems) { mw.playerSpendCp(isP1, e, mw.playerCpForElem(isP1, e)); mw.playerClearCp(isP1, e); }

		// Crystal cost
		if (ability.crystalCost() > 0) {
			mw.playerSpendCrystals(isP1, ability.crystalCost());
			mw.refreshCrystalDisplays();
		}

		// Mark once-per-turn ability as used for this turn
		if (ability.oncePerTurn())
			mw.usedOncePerTurnAbilities.computeIfAbsent(source, k -> new HashSet<>()).add(ability.effectText());

		// Dull source card
		if (ability.requiresDull()) applyDull.run();

		// Special: discard first same-name card from hand (primer card name also qualifies)
		if (ability.isSpecial()) {
			String primerName = mw.getPrimerCardName(source, isP1);
			List<CardData> hand = mw.playerHand(isP1);
			for (int i = 0; i < hand.size(); i++) {
				CardData hc = hand.get(i);
				if (source.name().equalsIgnoreCase(hc.name()) ||
						(primerName != null && primerName.equalsIgnoreCase(hc.name()))) {
					mw.playerBreakFromHand(isP1, i);
					mw.logEntry("Special: discarded \"" + hc.name() + "\" from hand");
					break;
				}
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
		for (ForwardTarget t : sortedBz) {
			if (t.isP1()) {
				switch (t.zone()) {
					case FORWARD -> mw.breakP1Forward(t.idx());
					case BACKUP  -> breakP1BackupSlot(t.idx());
					case MONSTER -> breakP1MonsterSlot(t.idx());
				}
			} else {
				switch (t.zone()) {
					case FORWARD -> mw.breakP2Forward(t.idx());
					case BACKUP  -> mw.breakP2BackupSlot(t.idx());
					case MONSTER -> mw.breakP2MonsterSlot(t.idx());
				}
			}
		}

		// Discard costs — paid from hand, no CP generated
		for (DiscardCost dc : ability.discardCosts()) {
			List<CardData> hand = mw.playerHand(isP1);
			List<Integer> eligibleIdx = new ArrayList<>();
			for (int i = 0; i < hand.size(); i++) {
				CardData c = hand.get(i);
				if (dc.cardName() != null && !meetsCardNameFilter(c, dc.cardName())) continue;
				if (dc.element() != null && !c.containsElement(dc.element())) continue;
				if (dc.cardType() != null && !matchesDiscardType(c, dc.cardType())) continue;
				if (dc.category() != null && !meetsCategoryFilter(c, dc.category())) continue;
				eligibleIdx.add(i);
			}

			if (eligibleIdx.size() < dc.count()) {
				mw.logEntry((isP1 ? "[P1] " : "[P2] ") + "Not enough eligible cards for discard cost.");
				return;
			}

			List<CardData> eligible = new ArrayList<>();
			for (int i : eligibleIdx) eligible.add(hand.get(i));

			List<Integer> picks;
			if (isP1) {
				picks = mw.showCardMultiImageChooser(eligible, "Discard Cost",
						dc.count(), dc.eachDifferentType(), false);
				if (picks == null || picks.size() != dc.count()) return;
			} else {
				picks = new ArrayList<>();
				for (int p = 0; p < dc.count(); p++) picks.add(p);
			}

			List<Integer> handIdxs = new ArrayList<>();
			for (int p : picks) handIdxs.add(eligibleIdx.get(p));
			handIdxs.sort(Collections.reverseOrder());
			for (int handIdx : handIdxs) {
				String discarded = hand.get(handIdx).name();
				mw.playerBreakFromHand(isP1, handIdx);
				mw.logEntry("Discard cost: \"" + discarded + "\" discarded");
			}
		}

		// Remove-from-game costs
		for (RemoveFromGameCost rfg : ability.removeFromGameCosts())
			executeRemoveFromGameCost(rfg, isP1);

		// Return-to-hand costs
		for (ReturnToHandCost rth : ability.returnToHandCosts())
			executeReturnToHandCost(rth, isP1);

		// Counter removal costs
		for (CounterCost cc : ability.counterCosts()) {
			int removed = mw.gameState.removeCounters(source, cc.counterName(), cc.count());
			mw.logEntry(source.name() + " — removed " + removed + " " + cc.counterName()
					+ " Counter(s) (cost)  [remaining: "
					+ mw.gameState.getCounters(source, cc.counterName()) + "]");
		}

		// Dull-forward costs: player picks an active forward to dull; its power is stored for effect resolution
		mw.lastDullForwardCostPower = 0;
		for (DullForwardCost dfc : ability.dullForwardCosts()) {
			List<CardData> fwds = isP1 ? mw.p1ForwardCards : mw.p2ForwardCards;
			List<CardState> states = isP1 ? mw.p1ForwardStates : mw.p2ForwardStates;
			List<Integer> eligible = new ArrayList<>();
			for (int i = 0; i < fwds.size(); i++) {
				if (states.get(i) != CardState.ACTIVE) continue;
				if (dfc.element() != null && !dfc.element().isEmpty() && !fwds.get(i).containsElement(dfc.element())) continue;
				eligible.add(i);
			}
			if (eligible.isEmpty()) { mw.logEntry("No eligible active Forward for Dull cost."); continue; }
			List<ForwardTarget> targets = eligible.stream()
					.map(i -> new ForwardTarget(isP1, i, ForwardTarget.CardZone.FORWARD)).toList();
			List<ForwardTarget> picks;
			if (isP1) {
				picks = mw.showForwardSelectDialog(targets, dfc.count(), false, "Dull Forward Cost");
				if (picks.size() < dfc.count()) continue;
			} else {
				picks = new ArrayList<>(targets.subList(0, Math.min(dfc.count(), targets.size())));
				if (picks.size() < dfc.count()) continue;
			}
			for (ForwardTarget pick : picks) {
				int fwdIdx = pick.idx();
				int pow = fwds.get(fwdIdx).power();
				mw.lastDullForwardCostPower += pow;
				states.set(fwdIdx, CardState.DULL);
				if (isP1) mw.animateDullForward(fwdIdx, null); else mw.animateDullP2Forward(fwdIdx, null);
				mw.logEntry("Dull cost: \"" + fwds.get(fwdIdx).name() + "\" dulled (power " + pow + ")");
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
				return;
			}
		}

		mw.logEntry("\"" + source.name() + "\" activated ability");

		mw.gameState.pushStack(new StackEntry(source, ability, isP1, xValue));
		mw.showStackWindow();
		mw.refreshP1HandLabel();
		mw.refreshP1BreakLabel();
	}

	private void executeRemoveFromGameCost(RemoveFromGameCost rfg, boolean isP1) {
		switch (rfg.zone()) {
			case "DECK" -> {
				java.util.Deque<CardData> deck = isP1 ? mw.gameState.getP1MainDeck() : mw.gameState.getP2MainDeck();
				for (int i = 0; i < rfg.count() && !deck.isEmpty(); i++) {
					CardData c = deck.pollFirst();
					if (isP1) mw.gameState.addToP1PermanentRfp(c); else mw.gameState.addToP2PermanentRfp(c);
					mw.logEntry(c.name() + " → Removed From Game (cost)");
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
						if (isP1) mw.gameState.addToP1PermanentRfp(c); else mw.gameState.addToP2PermanentRfp(c);
						mw.logEntry(c.name() + " → Removed From Game (cost)");
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
						if (isP1) mw.gameState.addToP1PermanentRfp(c); else mw.gameState.addToP2PermanentRfp(c);
						mw.logEntry(c.name() + " → Removed From Game (cost)");
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
						CardData c = bz.remove((int) eligible.get(i));
						if (isP1) mw.gameState.addToP1PermanentRfp(c); else mw.gameState.addToP2PermanentRfp(c);
						mw.logEntry(c.name() + " → Removed From Game (cost)");
					}
				} else {
					for (int pick = 0; pick < rfg.count(); pick++) {
						List<Integer> eligible = eligibleRfgBzIndices(rfg, isP1);
						if (eligible.isEmpty()) { mw.logEntry("No eligible Break Zone card for remove-from-game cost."); break; }
						if (eligible.size() == 1 && rfg.cardName() != null) {
							CardData c = bz.remove((int) eligible.get(0));
							if (isP1) mw.gameState.addToP1PermanentRfp(c); else mw.gameState.addToP2PermanentRfp(c);
							mw.logEntry(c.name() + " → Removed From Game (cost)");
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
							CardData c = bz.remove(bzIdx);
							if (isP1) mw.gameState.addToP1PermanentRfp(c); else mw.gameState.addToP2PermanentRfp(c);
							mw.logEntry(c.name() + " → Removed From Game (cost)");
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
