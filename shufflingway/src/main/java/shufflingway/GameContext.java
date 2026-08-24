package shufflingway;

import java.util.EnumSet;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Bridge interface that gives {@link ActionResolver} controlled access to the
 * live game state without coupling it directly to {@code MainWindow}'s private
 * fields.
 *
 * <p>MainWindow creates an anonymous implementation of this interface when
 * invoking {@link ActionResolver#resolve} and supplies the lambdas that dip
 * into the correct parallel lists.
 */
public interface GameContext {

    /** Appends a timestamped line to the game log. */
    void logEntry(String message);

    /** Returns {@code true} if P1 is the ability user for this context. */
    boolean isP1();

    /**
     * Records {@code targets} as the most-recently chosen target set for this ability's resolution.
     * Used so a follow-up clause (e.g. "That Forward's controller …") can refer back to the
     * controllers of the targets picked by the primary.
     */
    void recordChosenTargets(java.util.List<ForwardTarget> targets);

    /** Returns the targets recorded by the most recent {@link #recordChosenTargets} call, or an empty list. */
    java.util.List<ForwardTarget> lastChosenTargets();

    // ---- Sequential "X. When you do so, Y." progress signalling --------------

    /**
     * Resets the per-effect progress flag to {@code true} before a primary effect runs.
     * Used by sequential "X. When you do so, Y." effects to decide whether the follow-up runs.
     */
    void resetEffectProgress();

    /**
     * Marks the current effect as fizzled (it did nothing — e.g. no eligible target was
     * available or the player declined). Suppresses any "When you do so" follow-up.
     */
    void markEffectFizzled();

    /** Returns {@code true} if the most recent effect made progress (was not fizzled). */
    boolean effectMadeProgress();

    // ---- P1 forwards --------------------------------------------------------

    /** Number of P1 forwards currently on the field. */
    int p1ForwardCount();

    /**
     * The effective {@link CardData} for P1's forward at {@code idx}.
     * Returns the top (primed) card when the slot is in a Primed state.
     */
    CardData p1Forward(int idx);

    /**
     * Whether the field card {@code card} counts as {@code elem} right now — its printed Elements
     * plus any it has gained from the board (Kimahri 1-103C). Use this rather than
     * {@link CardData#containsElement} whenever the card being tested is on the field.
     *
     * <p>The default answers from the printed Elements alone, which is all a caller without a
     * board can know; the live implementation consults the board.
     */
    default boolean fieldCardHasElement(CardData card, String elem) {
        return card != null && card.containsElement(elem);
    }

    /** Accumulated damage on P1's forward at {@code idx}. */
    int p1ForwardCurrentDamage(int idx);

    /** Field state (ACTIVE / DULL) of P1's forward at {@code idx}. */
    CardState p1ForwardState(int idx);

    /**
     * Applies {@code amount} damage to P1's forward at {@code idx}, refreshes
     * the slot, and breaks the forward if its remaining power reaches zero.
     */
    void damageP1Forward(int idx, int amount);

    // ---- P2 forwards --------------------------------------------------------

    /** Number of P2 forwards currently on the field. */
    int p2ForwardCount();

    /** The {@link CardData} for P2's forward at {@code idx}. */
    CardData p2Forward(int idx);

    /** Accumulated damage on P2's forward at {@code idx}. */
    int p2ForwardCurrentDamage(int idx);

    /** Field state of P2's forward at {@code idx}. */
    CardState p2ForwardState(int idx);

    /**
     * Applies {@code amount} damage to P2's forward at {@code idx}, refreshes
     * the slot, and breaks the forward if its remaining power reaches zero.
     */
    void damageP2Forward(int idx, int amount);

    // ---- Targeted selection -------------------------------------------------

    /**
     * Shows a modal dialog letting P1 choose up to {@code maxCount} eligible
     * field cards and returns their targets.
     *
     * @param maxCount     maximum number of cards the player may select
     * @param upTo         if {@code true} the player may confirm with fewer than {@code maxCount}
     * @param opponentOnly if {@code true} only P2's cards are offered as targets
     * @param selfOnly     if {@code true} only P1's cards are eligible
     * @param condition    optional eligibility filter: {@code "active"}, {@code "dull"},
     *                     {@code "damaged"}, {@code "attacking"}, {@code "blocking"},
     *                     or {@code null} for any
     * @param element      optional element name to restrict targets; {@code null} = any
     * @param costVal      CP cost filter value; {@code -1} = no filter
     * @param costCmp      {@code "less"}, {@code "more"}, or {@code null} for exact
     * @param forwards        include Forwards as eligible targets
     * @param backups         include Backups as eligible targets
     * @param monsters        include Monsters as eligible targets
     * @param jobFilter       optional job name(s) to restrict targets; {@code null} = any;
     *                        bar-separated (e.g. {@code "Standard Unit|Warrior of Light"}) for OR
     * @param cardNameFilter  optional exact card name to restrict targets; {@code null} = any
     * @param categoryFilter  optional category substring to restrict targets; {@code null} = any
     * @param excludeName     optional card name to exclude; {@code null} = none excluded
     */
    List<ForwardTarget> selectCharacters(int maxCount, boolean upTo, boolean opponentOnly,
            boolean selfOnly, String condition, String element, int costVal, String costCmp,
            int powerVal, String powerCmp,
            boolean forwards, boolean backups, boolean monsters,
            String jobFilter, String cardNameFilter, String categoryFilter, String excludeName, boolean summons,
            String excludeElement, boolean withoutMulticard);

    /**
     * Lets the resolving player pick any number of Forwards whose printed costs sum to at most
     * {@code maxTotalCost}, and returns them — Vincent 2-077L's Death Penalty.
     *
     * <p>A budget rather than a per-card ceiling, so it cannot be expressed as one of
     * {@link #selectCharacters}'s filters: which Forwards are still affordable depends on what has
     * already been picked. The selection runs on the board like any other, with a running total on
     * the confirm bar and the confirm held shut while the picks overspend.
     *
     * <p>Picking none is a legal answer, and returns an empty list.
     */
    List<ForwardTarget> selectForwardsWithTotalCostAtMost(int maxTotalCost);

    /** One target picked for a tiered-damage selection, paired with the damage its tier carries. */
    record TieredDamagePick(ForwardTarget target, int damage) {}

    /**
     * Lets the resolving player pick the opponent's Forwards for a selection that deals a
     * <em>different</em> amount to each pick — Palom 3-016H's Meteor, "Deal 1 of them 6000 damage,
     * 1 of them 4000 damage, and 1 of them 2000 damage."
     *
     * <p>Cannot be expressed as a single {@link #selectCharacters} call: that returns an unordered
     * set of picks with nothing saying which one takes which amount, so the mapping would fall to
     * selection order — which the board dialog neither shows nor lets the player control. This
     * asks once per amount instead, labelling each prompt with the damage that pick will take, and
     * never offers a Forward already picked. Declining a prompt drops that amount and the sequence
     * carries on, which is what "up to" means here.
     *
     * <p>No damage is dealt — the picks are returned for the caller to apply, so the whole
     * selection is made against an unchanged board. Applying damage between prompts would let the
     * first break shift the indices the remaining picks are recorded at.
     *
     * @param amounts the damage each successive pick will be dealt, in prompt order
     * @return one entry per prompt that was answered, in prompt order; empty when none were
     */
    List<TieredDamagePick> selectOppForwardsForTieredDamage(int[] amounts);

    /**
     * Shows a modal dialog letting P1 choose up to {@code maxCount} eligible
     * cards from a Break Zone and returns their targets.
     *
     * @param opponentZone    if {@code true}, selects from P2's Break Zone; otherwise P1's
     * @param condition       optional eligibility filter; {@code null} = any
     * @param element         optional element name to restrict targets; {@code null} = any
     * @param costVal         CP cost filter value; {@code -1} = no filter
     * @param costCmp         {@code "less"}, {@code "more"}, or {@code null} for exact
     * @param forwards        include Forwards as eligible targets
     * @param backups         include Backups as eligible targets
     * @param monsters        include Monsters as eligible targets
     * @param jobFilter       optional job name(s); {@code null} = any; bar-separated for OR
     * @param cardNameFilter  optional exact card name; {@code null} = any
     * @param categoryFilter  optional category substring; {@code null} = any
     * @param excludeName     optional card name to exclude; {@code null} = none excluded
     */
    List<ForwardTarget> selectCharactersFromBreakZone(int maxCount, boolean upTo,
            boolean opponentZone, boolean bothZones, String condition, String element, int costVal, String costCmp,
            int powerVal, String powerCmp,
            boolean forwards, boolean backups, boolean monsters,
            String jobFilter, String cardNameFilter, String categoryFilter, String excludeName, boolean summons,
            String excludeElement, boolean withoutMulticard);

    /**
     * Presents the ability user with a list of Summons and auto-abilities on the stack
     * and cancels the one they choose, preventing its effect from resolving.
     */
    void cancelStackEntry();

    /**
     * Prompts for one Summon or auto-ability on the Stack — the same choice
     * {@link #cancelStackEntry()} offers — and makes the damage it deals become 0 for the rest of
     * this turn instead of cancelling it: 29-012H Neon's Runic.
     *
     * <p>A replacement, not a reduction, and it covers damage to Forwards and to a player alike,
     * so both damage paths read it before anything that would scale the figure.
     */
    void chooseStackEntryZeroItsDamageThisTurn();

    /**
     * Cancels the Summon whose casting triggered the ability now resolving — Clione 4-125C's
     * "cancel the Summon's effect". Nothing is chosen: "the Summon" is definite, and it is the
     * topmost Summon on the Stack, which is the one this trigger was pushed on top of.
     *
     * <p>Does nothing when no Summon is on the Stack, which is what an ability reaching this from
     * anywhere but its cast-time trigger would find.
     */
    void cancelTriggeringSummon();

    /**
     * Cancels one auto-ability on the stack (chosen by the active player), then if the source
     * card is a Forward currently on the field, deals {@code damage} to it.
     */
    void cancelAutoAbilityAndDamageSourceIfForward(int damage);

    /**
     * Filters the stack to entries matching {@code filter}, presents a selection dialog to the
     * human player (or AI logic), then marks the chosen entry as cancelled so its effect is
     * suppressed when it resolves.  If no entries match the filter, logs a message and returns.
     * {@code prompt} is the dialog header shown to the human player.
     *
     * <p>When {@code requiresControllerTarget} is {@code true}, the filter is further restricted
     * to entries whose {@link StackEntry#preSelectedTargets()} include at least one target owned
     * by the cancelling player ("that is choosing a Forward you control").  Entries with no stored
     * targets are treated as passing the check (permissive fallback).
     */
    void cancelFilteredAbilityOnStack(java.util.function.Predicate<StackEntry> filter, String prompt, boolean requiresControllerTarget);

    /**
     * The plural form of {@link #cancelFilteredAbilityOnStack}: lets the resolving player pick as
     * many matching Stack entries as they like and cancels every one of them — Jecht 14-108H's
     * Jecht Block and Shelke 16-029R's Countertek.
     *
     * <p>Choosing none is a legal answer ("any number" includes zero), so a cancelled dialog
     * cancels nothing rather than falling back to a default pick. Entries protected from being
     * cancelled are never offered.
     */
    void cancelAnyNumberOfAbilitiesOnStack(java.util.function.Predicate<StackEntry> filter, String prompt);

    /**
     * Filters the stack to entries matching {@code filter} and presents a selection dialog the same
     * way as {@link #cancelFilteredAbilityOnStack}, but the chosen entry is only cancelled if the
     * canceller's opponent declines (or is unable) to pay the full {@code cost} in CP. The payment
     * is pay-in-full-or-decline — there is no partial payment that still averts the cancellation.
     */
    void cancelFilteredAbilityOnStackUnlessOpponentPays(java.util.function.Predicate<StackEntry> filter, String prompt, int cost);

    /**
     * Body of a "chosen by opponent's Summons or abilities" auto-ability: gates the *implicit*
     * target — whatever Summon/ability just triggered this reaction, currently mid-selection of a
     * Character this context's controller owns — behind a pay-in-full-or-decline CP tax. If the
     * opponent declines (or is unable) to pay the full {@code cost}, the in-progress selection is
     * cancelled so the triggering Summon/ability ends up choosing nothing.
     */
    void cancelChosenSelectionUnlessOpponentPays(int cost);

    /**
     * Discard-cost sibling of {@link #cancelChosenSelectionUnlessOpponentPays}: the opponent must
     * discard {@code count} cards from hand (in full) to prevent the in-progress selection from
     * being cancelled. Declining — or holding fewer than {@code count} cards — cancels the triggering
     * Summon/ability's choice of this context's controller's Character(s).
     */
    void cancelChosenSelectionUnlessOpponentDiscards(int count);

    /**
     * Crystal-alternative sibling of {@link #cancelChosenSelectionUnlessOpponentPays}: the opponent may
     * prevent the in-progress selection from being cancelled by paying EITHER {@code cpCost} CP the
     * normal way OR {@code crystalCost} Crystals. The Crystal option is unavailable when the opponent
     * holds fewer than {@code crystalCost} Crystals. Declining both cancels the triggering
     * Summon/ability's choice of this context's controller's Character(s).
     */
    void cancelChosenSelectionUnlessOpponentPaysOrCrystal(int cpCost, int crystalCost);

    /**
     * Unconditionally cancels the in-progress selection that triggered this reactive "chosen by
     * opponent's Summons or abilities" auto-ability — used when the controller has already paid the
     * ability's optional cost upstream (Phantasmal Girl's "you may pay 《2》. When you do so, cancel
     * their effects."; Regis/Tama/Yuna's "…put/discard…, cancel its effect."), so no further choice
     * is offered. The triggering Summon/ability ends up choosing nothing.
     */
    void cancelChosenSelection();

    /**
     * Banon: reveals (peeks) the top card of the controller's deck; if it is of {@code type}
     * (e.g. {@code "Backup"}), cancels the in-progress selection. The revealed card stays on top.
     */
    void revealTopDeckCancelChosenIfType(String type);

    /**
     * Siren (V): mills the top card of the controller's deck into their Break Zone; if that card is
     * NOT of {@code type} (e.g. {@code "Forward"}), cancels the in-progress selection.
     */
    void millTopDeckCancelChosenIfNotType(String type);

    /**
     * Colkhab (18-041C): both players mill the top card of their deck; if the two milled cards share
     * a card type, the in-progress selection is cancelled. Two-sided sibling of
     * {@link #millTopDeckCancelChosenIfNotType}, and cancels on a match rather than on a mismatch.
     *
     * <p>An empty deck on either side mills nothing and cancels nothing — with only one card there is
     * no pair to compare.
     */
    void millTopDeckBothCancelChosenIfSameType();

    /**
     * Loads {@code targets} as the pre-selected targets for the ability about to be resolved.
     * Called by {@link MainWindow} just before running the resolution lambda, so that
     * {@link ActionResolver}'s {@code selectTargets} can return them without showing a dialog.
     */
    void preloadTargets(java.util.List<ForwardTarget> targets);

    /**
     * Consumes and returns the targets loaded by {@link #preloadTargets}, or {@code null} if none
     * were loaded.  Subsequent calls return {@code null} until the next {@link #preloadTargets}.
     */
    java.util.List<ForwardTarget> consumePreloadedTargets();

    /**
     * Moves a Stack entry's chosen target, for the five abilities whose criteria {@code spec}
     * describes (see {@link TargetRedirect}). Picks an eligible entry — prompting when more than
     * one qualifies — works out the replacement, and rewrites the entry via
     * {@link StackEntry#preSelectedTargets()} so it resolves against the new permanent. The entry
     * is NOT cancelled; it still resolves, just against something else.
     *
     * <p>Does nothing when no entry qualifies, when no legal replacement exists ("if possible"),
     * or when the player declines an optional redirect.
     *
     * <p>Legality of the replacement is checked as far as the board can answer it: the card must
     * be in the right zone, of the right Element, and not protected from being chosen by an
     * effect of that kind. Restrictions belonging to the redirected effect itself — "choose 1
     * Forward of cost 3 or less" — are not re-derived, matching how the cancel family already
     * treats its own target filters.
     */
    void redirectChosenTarget(TargetRedirect spec, CardData source);

    /**
     * Forces {@code t} directly into the Break Zone, bypassing any
     * "cannot be broken" protection that {@link #breakTarget} would respect.
     */
    void forceTargetToBreakZone(ForwardTarget t);

    /**
     * Kefka 15-071H: the resolving player divides every Forward their opponent controls into
     * {@code groupCount} groups, that opponent keeps one group, and the rest are put into the
     * Break Zone.
     *
     * <p>Two players decide in turn, which is what separates this from every other removal on this
     * interface: the shape of the choice is set by one of them and its outcome by the other, so
     * neither can be reduced to a target list the caller passes in.
     *
     * <p>Nothing is <em>chosen</em> here in the rules' sense — the effect names the whole row — so
     * a Forward that cannot be chosen by Summons or abilities is divided up with the rest, and the
     * cards that leave do so by being put into the Break Zone rather than broken.
     *
     * <p>An opponent with no Forwards is left alone, and a group may legally be empty: keeping the
     * empty one loses the player everything, which is the threat the card trades on.
     */
    void divideOpponentForwardsIntoGroups(int groupCount);

    /**
     * Moves the top {@code count} cards from the opponent's main deck into their Break Zone.
     */
    void opponentMillCards(int count);

    /**
     * Mills the top {@code millCount} cards from the opponent's deck into their Break Zone,
     * then draws {@code drawCount} cards for the ability user if all milled cards share
     * at least one common element.
     */
    void opponentMillIfSameElementDraw(int millCount, int drawCount);

    /**
     * Moves the top {@code count} cards from the ability user's own main deck into their Break Zone,
     * animating each card sliding from deck to break zone.
     */
    void millCards(int count);

    /**
     * Displays the opponent's hand to the ability user in a timed popup window.
     */
    void revealOpponentHand();

    /**
     * Reveals the top card of the specified deck in a modal popup, then evaluates
     * each clause in order against the revealed card.  The first matching clause fires
     * its action; if no clause matches the card is returned to the top of the deck.
     *
     * @param clauses      ordered list of condition/action pairs built by the parser
     * @param opponentDeck {@code true} to reveal from the opponent's deck instead of the ability user's
     */
    void revealTopDeckCard(List<RevealClause> clauses, boolean opponentDeck);

    /**
     * Reveals the top card of the player's deck in a modal popup, applies {@code onEven}
     * or {@code onOdd} to the game context depending on whether the revealed card's CP cost
     * is even or odd, then adds the revealed card to the player's hand.
     */
    void revealTopDeckCostParityEffect(java.util.function.Consumer<GameContext> onEven,
                                       java.util.function.Consumer<GameContext> onOdd);

    /**
     * Each player reveals the top card of their deck. Each player whose revealed card satisfies
     * {@code eligibleCondition} may play it onto the field; otherwise it is returned to the top
     * of their deck. P1 gets a Decline/OK dialog; P2 auto-accepts.
     */
    void revealEachPlayerTopDeckMayPlay(Predicate<CardData> eligibleCondition);

    /**
     * Reveals the top card of the player's deck in a modal popup, breaks all opponent Forwards
     * with the same cost as the revealed card, then adds the revealed card to the player's hand.
     */
    void revealTopBreakSameCostAddToHand();

    /**
     * Lets P1 choose one eligible card from their hand and places it onto the field
     * without paying costs.
     *
     * @param inclForwards include Forwards as eligible choices
     * @param inclBackups  include Backups as eligible choices
     * @param inclMonsters include Monsters as eligible choices
     * @param costVal      maximum (or exact) cost threshold
     * @param costCmp      {@code "less"}, {@code "more"}, or {@code null} for exact match
     */
    /**
     * @param jobFilter      bar-separated job name(s); {@code null} = any
     * @param cardNameFilter exact card name; {@code null} = any
     * @param categoryFilter category substring; {@code null} = any
     *                       When both {@code jobFilter} and {@code cardNameFilter} are non-null
     *                       a card is eligible if it matches <em>either</em> (OR logic).
     */
    /**
     * @param costVal2    second exact cost value for "cost N or M" two-value filter; {@code -1} = unused
     * @param excludeName card name to exclude from eligible choices; {@code null} = none
     * @param entersDull  if {@code true} the placed card enters the field in a dulled state
     */
    void playCharacterFromHand(boolean inclForwards, boolean inclBackups, boolean inclMonsters,
            int costVal, String costCmp, int costVal2,
            String jobFilter, String cardNameFilter, String categoryFilter,
            String elementFilter, String excludeName, boolean entersDull, String excludeElement,
            boolean suppressAutoAbility, String withTrait);

    /**
     * The "each player may play 1 [type] … from their hand onto the field" wording — 28-051R
     * Black Cat. Both players get the same offer, applied to their own hand and their own field,
     * with the same filters and the same meaning for every parameter as
     * {@link #playCharacterFromHand}.
     *
     * <p>Resolves in turn order: the turn player chooses first, then their opponent, who by then
     * can see what was played. The controller of the ability has no priority here — it is "each
     * player", not "you, then your opponent".
     *
     * <p>Either player may decline, and one declining does not stop the other. The effect counts
     * as having fizzled only if neither played.
     */
    void eachPlayerMayPlayCharacterFromHand(boolean inclForwards, boolean inclBackups,
            boolean inclMonsters, int costVal, String costCmp, int costVal2,
            String jobFilter, String cardNameFilter, String categoryFilter,
            String elementFilter, String excludeName, boolean entersDull, String excludeElement,
            boolean suppressAutoAbility, String withTrait);

    /**
     * Repeatedly prompts the ability user to play matching characters from their hand onto the
     * field until no eligible cards remain or they decline.
     */
    void playAnyNumberFromHand(boolean inclForwards, boolean inclBackups, boolean inclMonsters,
            String jobFilter, String cardNameFilter, String categoryFilter, String elementFilter);

    /**
     * "Choose any number of [Forwards/Backups/Monsters/Characters] [opponent/you] control.
     * Return them to their owners' hands."
     *
     * <p>For P1 (human): loops showing a cancellable chooser drawn from the eligible zones;
     * each pick returns one card.  For P2 (AI): returns all eligible opponent-controlled cards
     * automatically; returns none of its own.
     *
     * @param opponentOnly restrict candidates to the ability user's opponent's field
     * @param selfOnly     restrict candidates to the ability user's own field
     */
    void chooseAnyNumberReturnToHand(boolean inclForwards, boolean inclBackups, boolean inclMonsters,
            boolean opponentOnly, boolean selfOnly);

    /**
     * Prompts the ability user to choose 1 Summon from their hand and casts it immediately
     * without paying its cost.
     *
     * @param maxCost            cost ceiling for eligible Summons; {@code -1} = no restriction
     * @param returnToHandAfterUse when {@code true}, the Summon returns to the caster's hand
     *                           after resolving instead of going to the Break Zone
     */
    void castSummonFromHandFree(int maxCost, boolean returnToHandAfterUse, String excludeElements);

    default void castSummonFromHandFree(int maxCost, boolean returnToHandAfterUse) {
        castSummonFromHandFree(maxCost, returnToHandAfterUse, null);
    }

    /**
     * Randomly reveals 1 card from the player's hand.  If it is a Summon, the player
     * may cast it without paying the cost.  The card stays in hand if it is not a Summon
     * or the player declines to cast.
     */
    void randomRevealHandCastIfSummonFree();

    /**
     * Shows all Summons in the player's hand with their effective cast cost reduced by
     * {@code discount} (floored at 1).  The player selects one to cast at that reduced cost,
     * or cancels.  Existing cost modifiers are also applied before the additional discount.
     */
    void castSummonFromHandDiscounted(int discount);

    /**
     * Searches the deck for a Summon matching the element and cost filters, then offers
     * the player a choice to cast it for free.  If the player declines to cast, the Summon
     * is put into the Break Zone.  The deck is shuffled after the search regardless.
     *
     * @param maxCost       cost ceiling; {@code -1} = no restriction
     * @param elementFilter element the Summon must have (e.g. {@code "Fire"}); {@code null} = any
     */
    void searchAndCastSummonFreeFromDeck(int maxCost, String elementFilter);

    // ---- Zone-dispatch single-target effects --------------------------------

    /**
     * Applies {@code amount} damage to the target.
     * Only meaningful for Forwards and Monsters (Backups are ignored).
     */
    void damageTarget(ForwardTarget t, int amount);

    /**
     * Like {@link #damageP1Forward} but the damage bypasses all reduction effects.
     * One-shot shields are still consumed; persistent shields remain but provide no reduction.
     */
    void damageP1ForwardUnreduced(int idx, int amount);

    /**
     * Like {@link #damageP2Forward} but the damage bypasses all reduction effects.
     * One-shot shields are still consumed; persistent shields remain but provide no reduction.
     */
    void damageP2ForwardUnreduced(int idx, int amount);

    /**
     * Like {@link #damageTarget} but the damage bypasses all reduction effects.
     * One-shot shields are still consumed; persistent shields remain but provide no reduction.
     */
    void damageTargetUnreduced(ForwardTarget t, int amount);

    /**
     * Deals {@code amount} damage to the first P1 or P2 Forward whose name matches
     * {@code cardName} (case-insensitive). Logs a warning if no matching card is found.
     */
    void damageFieldForwardByName(String cardName, int amount);

    /**
     * Each player selects 1 Forward they control, then both receive {@code amount} damage.
     * P1 picks via dialog; P2 (AI) picks automatically — preferring a Forward whose effective
     * power exceeds {@code amount} so it survives, otherwise picking the lowest-cost Forward.
     * Skips a side that has no Forwards.
     */
    void eachPlayerSelectForwardAndDamage(int amount);

    /**
     * Each player may search their deck for up to {@code count} Forward(s) with power
     * &ge; {@code minPower} and add the chosen card(s) to their hand.
     * P1 is shown a yes/no offer then a search dialog; P2 AI always searches.
     */
    void eachPlayerMaySearchForwardMinPowerToHand(int count, int minPower);

    /**
     * Both players each select 1 Forward they control and put it into the Break Zone.
     * P1 picks via dialog; P2 (AI) picks automatically (lowest-cost Forward).
     * Skips a side that has no Forwards.
     */
    void eachPlayerSelectForwardAndBreak();

    /**
     * The controller selects 1 Forward they control and puts it into the Break Zone.
     * P1 picks via dialog; P2 (AI) picks automatically (lowest-cost Forward).
     * No-ops if the controller has no Forwards.
     */
    void selectControlledForwardAndBreak();

    /**
     * Prompts the ability user to select 1 controlled card of the specified type(s)
     * and put it into the Break Zone. AI picks the lowest-cost Forward, then Backup, then Monster.
     */
    void selectControlledTypeAndBreak(boolean inclForwards, boolean inclBackups, boolean inclMonsters);

    /**
     * Each player selects up to {@code count} Forwards and/or Monsters they control
     * and puts them into the Break Zone.
     * P1 picks via dialog; P2 (AI) picks lowest-cost eligible targets.
     */
    void eachPlayerSelectUpToNAndBreak(int count, boolean inclForwards, boolean inclMonsters);

    /**
     * Each player selects {@code count} card(s) from their own Break Zone and adds them to their hand.
     * P1 picks via dialog; P2 (AI) picks automatically (highest-cost first). Each player is limited
     * by the size of their own Break Zone, so one side retrieving nothing does not stop the other.
     *
     * @param fwds include Forwards as eligible cards
     * @param bkps include Backups as eligible cards
     * @param mons include Monsters as eligible cards
     * @param smns include Summons as eligible cards — only "1 card" wording is that unrestricted
     */
    void eachPlayerSalvageFromBreakZone(int count, boolean fwds, boolean bkps, boolean mons, boolean smns);

    /**
     * The ability user selects {@code count} Character(s) from their own Break Zone and adds them to their hand.
     * P1 picks via dialog; P2 (AI) picks automatically (highest-cost first).
     *
     * @param fwds include Forwards as eligible targets
     * @param bkps include Backups as eligible targets
     * @param mons include Monsters as eligible targets
     */
    void salvageCharacterFromOwnBreakZone(int count, boolean fwds, boolean bkps, boolean mons);

    /**
     * Ceodore: chooses 1 card with the Warp trait ({@link CardData#hasWarp()}) — of any type — from
     * the controller's own Break Zone and adds it to their hand. P1 picks via dialog; P2 (AI) picks
     * the highest-cost eligible card. No-op if the Break Zone holds no Warp card.
     */
    void chooseWarpCardFromBreakZoneToHand();

    /** Grants the ability user {@code count} Crystals. */
    void gainCrystal(int count);

    /** Returns the number of Crystal tokens (《C》) currently held by the ability user. */
    int crystalCount();

    /** Returns the number of distinct element types used in the CP payment for the most recently cast card. */
    int castPaymentDistinctElements();

    /** Returns the number of Crystal tokens (《C》) currently held by the opponent. */
    int opponentCrystalCount();

    /**
     * Until end of turn, damage dealt by {@code source} to Forwards is doubled.
     * Stacks multiplicatively: a second call for the same source yields ×4 damage.
     */
    void doubleOutgoingDamage(CardData source);

    /**
     * Until end of turn, damage received by any Forward the opponent controls is doubled.
     * Stacks multiplicatively with itself.
     */
    void doubleOpponentForwardIncomingDamage();

    /**
     * Until end of turn, any Forward that receives damage takes {@code amount} additional damage.
     * Applies globally to all Forwards on the field regardless of controller.
     * Stacks additively with itself.
     */
    void increaseAllForwardIncomingDamage(int amount);

    /** Sets the target back to Active state and refreshes its slot. */
    void activateTarget(ForwardTarget t);

    /** Dulls the target and refreshes its slot. */
    void dullTarget(ForwardTarget t);

    /**
     * Toggles the target between Active and Dull. If the target is currently dull it is
     * activated; if active it is dulled. Used by "Dull it or activate it." effects where the
     * action depends on the chosen card's current state.
     */
    void toggleTargetDullActivate(ForwardTarget t);

    /** Freezes the target (skips activation next Active Phase) and refreshes its slot. */
    void freezeTarget(ForwardTarget t);

    /**
     * Prompts the active player to choose between dulling or freezing the target.
     * (The choices are independent — picking Freeze on an active target stacks with
     * any later Dull effect, enabling combined dull+freeze outcomes.)
     * The AI picks whichever option actually changes the target's state.
     */
    void dullOrFreezeTarget(ForwardTarget t);

    /** Dulls and freezes the target. */
    void dullAndFreezeTarget(ForwardTarget t);

    /** Breaks the target (sends to the owning player's Break Zone). */
    void breakTarget(ForwardTarget t);

    /** Removes the target from the game permanently (not to the Break Zone). */
    void removeTargetFromGame(ForwardTarget t);

    /** Removes the top {@code count} cards of the active player's deck from the game. */
    /**
     * @param source the card whose ability is removing them, recorded so a later ability on that
     *               same card can retrieve them via {@link #addCardsRemovedBySourceToHand}; may be
     *               {@code null} when nothing refers back to them
     */
    void removeTopCardsOfDeckFromGame(int count, CardData source);

    /**
     * Moves up to {@code count} of the cards {@code source} removed from the game into the ability
     * user's hand — "cards removed by the previous effect" (Libroarian 8-084R). P1 picks which when
     * there is a choice; the AI takes the costliest.
     *
     * @return how many of {@code source}'s removed cards are still out of the game afterwards
     */
    int addCardsRemovedBySourceToHand(CardData source, int count);

    /** How many cards {@code source} has removed from the game and not yet retrieved. */
    int cardsRemovedBySourceCount(CardData source);

    /**
     * Puts every card {@code source} still has removed from the game into its owner's Break Zone —
     * "put the rest of the cards into the Break Zone" (Cloud of Darkness 10-140S).
     */
    void putCardsRemovedBySourceIntoBreakZone(CardData source);

    /**
     * Removes the top card of the active player's deck from the game and returns its CP cost.
     * Returns 0 if the deck is empty.
     */
    int removeTopCardOfDeckFromGameAndGetCost();

    /**
     * Removes the top card of the active player's deck from the game and returns whether it is a
     * Forward. Returns {@code false} if the deck is empty (nothing removed).
     */
    boolean removeTopCardOfDeckFromGameIsForward();

    /**
     * Reveals the top {@code n} cards of the active player's deck, adds them all to hand,
     * and returns the total CP cost of the revealed cards. Returns 0 if the deck is empty.
     */
    int revealTopNAndAddAllToHandGetTotalCP(int n);

    /**
     * Reveals the top {@code n} cards of the active player's deck, counts how many have the
     * given {@code job}, then places all revealed cards at the bottom of the deck.
     * Returns the count of cards that matched the job.
     */
    int revealTopNCountJobPlaceAllAtBottom(int n, String job);

    /** Shuffles the active player's deck. */
    void shuffleDeck();

    /**
     * Plays the target (chosen from a Break Zone) onto the field without
     * paying costs.  Forwards go to the forward zone, Backups to a backup
     * slot, Monsters to the monster zone.
     *
     * @return where the card landed, so a follow-on effect can act on it ("play them onto the
     *         field. They gain Haste until the end of the turn."), or {@code null} if it could
     *         not be played. Callers that only need the move may ignore it.
     */
    ForwardTarget playTargetOntoField(ForwardTarget t);

    /**
     * Asks for two distinct Forwards of {@code element} in the ability user's own Break Zone, the
     * first costing at most {@code maxCost1} and the second at most {@code maxCost2}. Returns both
     * targets in choice order, or an empty list if either choice could not be made.
     *
     * <p>Its own method because the two picks must be distinct <em>cards</em>. A Break Zone
     * routinely holds several copies of a name, and the general selection can only exclude by
     * name — which would wrongly bar the first pick's twin from the second choice.
     */
    List<ForwardTarget> selectTwoOwnBreakZoneForwards(String element, int maxCost1, int maxCost2);

    /**
     * Like {@link #playTargetOntoField} but the card enters the field in a dulled state.
     * Only meaningful for Forwards; Backups and Monsters enter normally.
     */
    void playTargetOntoFieldDull(ForwardTarget t);

    /**
     * Plays the card whose departure fired the "put into the Break Zone" trigger now resolving back
     * onto the resolving player's field, dull — Lunafreya 8-132L's "play the Forward placed in the
     * Break Zone onto the field dull".
     *
     * <p>The card is named by the event rather than chosen, which is why it takes no target: "the
     * Forward placed in the Break Zone" is the one this very trigger watched arrive there. Does
     * nothing when it has since left that Break Zone, or when no such trigger is resolving.
     */
    void playTriggeringBrokenCardOntoFieldDull();

    /**
     * Moves the target (chosen from either Break Zone) to the resolving player's hand — P1's on a
     * {@link #isP1()} context, P2's otherwise — regardless of which Break Zone it came from.
     */
    void addTargetToHand(ForwardTarget t);

    /**
     * Adds {@code amount} power and optionally grants {@code traits} to the target
     * until the end of the turn.
     */
    void boostTarget(ForwardTarget t, int amount, EnumSet<CardData.Trait> traits);

    /**
     * Removes the specified {@code traits} from the target Forward until end of turn.
     * Traits the Forward does not currently have are silently ignored.
     */
    void removeTraitsUntilEotFromTarget(ForwardTarget t, EnumSet<CardData.Trait> traits);

    /**
     * Returns {@code true} if the target Forward currently has the given trait
     * (accounting for temporary grants and removals).
     */
    boolean effectiveTargetHasTrait(ForwardTarget t, CardData.Trait trait);

    /**
     * Finds {@code source} on P1's forward zone and adds {@code amount} power and
     * optionally grants {@code traits} to it until the end of the turn.
     * No-op if the source card is not found on the field.
     */
    void boostSourceForward(CardData source, int amount, EnumSet<CardData.Trait> traits);

    /**
     * Adds {@code amount} power and grants {@code traits} to {@code source} for as long as it
     * stays on the field — the "(This effect does not end at the end of the turn.)" wording, as
     * printed on 8-147S Fordola.  The outlasts-the-turn counterpart of
     * {@link #boostSourceForward(CardData, EnumSet)}'s end-of-turn boost.
     *
     * <p>Like the end-of-turn version this is a no-op when the source is not on the field, and it
     * respects the same opponent-side power-boost suppression.  The grant is dropped when the card
     * leaves the field, alongside the other permanent grants.
     */
    void boostSourceForwardPermanently(CardData source, int amount, EnumSet<CardData.Trait> traits);

    /**
     * Replaces the source card's base power with {@code power} until the end of the turn and
     * grants it {@code traits} for the same duration — the self-targeted form of
     * {@link #setTargetBasePower}.  Pass an empty set for wordings with no keyword clause
     * (e.g. Mime 4-141C).  No-op if the source card is not found on the field.
     */
    void setSourceForwardBasePower(CardData source, int power, EnumSet<CardData.Trait> traits);

    /**
     * The outlasts-the-turn counterpart of {@link #setSourceForwardBasePower}, for the
     * "[Self] gains [traits] and [Self]'s power becomes N." wording that states no duration
     * (Hyoh 16-097H, Ramza 16-017R) or spells the permanence out in a trailing parenthetical
     * (Roche 29-076H, Young Excenmille 23-100L).
     *
     * <p>Both halves persist: the base power is not withdrawn by the end phase, and the traits go
     * to the permanent trait store rather than the per-turn one. Like every permanent grant it is
     * dropped when the card leaves the field.
     */
    void setSourceForwardBasePowerPermanently(CardData source, int power, EnumSet<CardData.Trait> traits);

    /**
     * The outlasts-the-turn counterpart of {@link #grantSelfFieldAbilityUntilEndOfTurn}. The text
     * is stored verbatim for the same reason, and read back through the same effective-abilities
     * view, so a permanently granted field ability behaves exactly like a printed one.
     */
    void grantSelfFieldAbilityPermanently(CardData source, String abilityText);

    /**
     * Shields {@code source} from being chosen by the opponent's Summons and/or abilities for as
     * long as it stays on the field — the outlasts-the-turn form of
     * {@link #shieldNamedCardCannotBeChosen} (Young Excenmille 23-100L).
     */
    void shieldSelfCannotBeChosenPermanently(CardData source, boolean bySummons, boolean byAbilities);

    /**
     * Puts {@code source} under a standing "must attack once per turn if possible" compulsion
     * (Roche 29-076H). It re-arms every turn and is satisfied once that card has attacked, so
     * unlike the one-turn must-attack instruction it is held by instance rather than slot.
     */
    void grantSelfMustAttackOncePerTurnPermanently(CardData source);

    /**
     * Finds {@code source} on the field and doubles its power (and optionally grants
     * {@code traits}) until end of turn by boosting it by its current effective power.
     */
    void doubleSourceForwardPower(CardData source, java.util.EnumSet<CardData.Trait> traits);

    /**
     * Replaces the target's base power with {@code power} until the end of the turn — the
     * "its power becomes N" wording (Barbariccia, Diablos, Matoya, Yagudo, …).  This substitutes
     * the card's printed power rather than its effective power, so temporary boosts and
     * reductions — whether already applied or applied later this turn — stack on top of it.
     * No-op unless {@code t} names a card in a Forward zone.
     */
    void setTargetBasePower(ForwardTarget t, int power);

    /** Places {@code count} counters named {@code counterName} on {@code card}. */
    void placeCounters(CardData card, String counterName, int count);

    /** Returns the number of counters named {@code counterName} currently on {@code card}. */
    int getCounters(CardData card, String counterName);

    /** Removes up to {@code count} counters named {@code counterName} from {@code card} (no-op if fewer are present). */
    void removeCounters(CardData card, String counterName, int count);

    /**
     * Grants the Forward at {@code target} the single action ability parsed from {@code abilityText}
     * (e.g. "《Dull》: Choose 1 Forward. Deal it 4000 damage.") until the end of the turn. The grant is
     * keyed by the Forward's card identity and cleared with the rest of the end-of-turn state.
     */
    void grantEotActionAbility(ForwardTarget target, String abilityText);

    /**
     * Selects and removes one counter from the character at {@code t}.
     * If the card has no counters the effect fizzles.
     * If it has exactly one counter type the counter is removed silently.
     * If it has multiple counter types the active player is prompted to choose one.
     */
    void removeOneCounterFromTarget(ForwardTarget t);

    /**
     * Places one more Counter of a type already on the character at {@code t} — Gestahlian Empire
     * Cid 11-026H, "Select 1 Counter placed on it, and place 1 additional Counter of the same type
     * as the selected Counter on that Monster."
     *
     * <p>The mirror of {@link #removeOneCounterFromTarget} and it fizzles the same way: a card with
     * no counters has no type to copy. One type is copied silently, several put the choice to the
     * active player.
     */
    void duplicateOneCounterOnTarget(ForwardTarget t);

    /**
     * General "look at the top N cards" effect.  The {@link LookConfig} specifies how
     * many cards to look at and what the player may do with them afterward.
     *
     * <p>The card this puts into hand, if any, is remembered for
     * {@link #triggerExBurstOfCardAddedToHand}.
     */
    void lookAtTopDeck(LookConfig config);

    /**
     * "Reveal the top card of your deck. If it is a [type], add it to your hand. If it is not a
     * [type], put it at the top or bottom of your deck." (16-115H Sarah (MOBIUS))
     *
     * <p>The branch is decided by the revealed card, so only the miss needs a decision from the
     * player; it is delegated to {@link #lookAtTopDeck} with
     * {@link LookConfig.LookAction#TOP_OR_BOTTOM_ORDERED} over the single card, which is what
     * routes the choice correctly for a local seat, the AI and a remote opponent alike.
     */
    void revealTopAddToHandIfType(String cardType);

    /**
     * Offers the EX Burst of the card the preceding {@link #lookAtTopDeck} put into hand, placing
     * it on the stack when the player accepts (Lunafreya 23-129H).  The card itself stays in hand
     * — only its effect goes on the stack, as with {@link #triggerExBurstFromDamageZone}.  Does
     * nothing when that look added no card or the added card has no EX Burst.
     */
    void triggerExBurstOfCardAddedToHand();

    /**
     * Looks at the top {@code count} cards of the player's deck, lets the player reveal and
     * cast 1 Summon of cost ≤ {@code maxCost} for free, then shuffles the remaining cards to
     * the bottom of the deck.
     *
     * @param count   how many cards from the top to look at
     * @param maxCost cost ceiling for eligible Summons; {@code -1} = no restriction
     */
    void lookAtTopDeckCastSummonFreeRestBottom(int count, int maxCost);

    /**
     * Reduces the target's power by {@code amount} and temporarily removes {@code traits}
     * until the end of the turn.  If effective power drops to 0 or below the card is sent
     * to the break zone (not treated as "broken" mechanically — distinction TBD).
     */
    void reduceTarget(ForwardTarget t, int amount, EnumSet<CardData.Trait> traits);

    /**
     * Finds {@code source} on P1's forward zone and applies the same reduction as
     * {@link #reduceTarget}.  No-op if the source card is not found on the field.
     */
    void reduceSourceForward(CardData source, int amount, EnumSet<CardData.Trait> traits);

    // ---- Damage-shield / damage-modifier effects --------------------------------

    /** Next damage received by target becomes 0 (consumed on first hit). */
    void shieldNextIncomingDamage(ForwardTarget t);

    /** The next damage dealt to the effect controller (as a player) becomes 0 (consumed on first hit). */
    void shieldPlayerNextDamage();

    /**
     * Auron: like {@link #shieldPlayerNextDamage} (next damage to the active player becomes 0,
     * shield icon shown), but when the shield consumes, {@code damage} is dealt to the Forward
     * named {@code cardName} on the shield owner's field instead.
     */
    void shieldPlayerNextDamageRedirect(String cardName, int damage);

    /** The next damage dealt to {@code from} is received by {@code to} instead (consumed on first hit). */
    void redirectNextIncomingDamage(ForwardTarget from, ForwardTarget to);

    /** Next damage received by target is reduced by {@code reduction} (consumed on first hit). */
    void shieldNextIncomingDamageReduction(ForwardTarget t, int reduction);

    /**
     * Like {@link #shieldNextIncomingDamageReduction}, but the shield bills whoever lent it: Cecil
     * 9-109H reduces the next damage the chosen Forward would take by {@code reduction} and takes
     * {@code damage} himself when that happens.
     *
     * <p>The kickback is dealt after the shielded Forward's damage has fully resolved, so a
     * kickback that breaks {@code bearer} cannot shift the indices that resolution is using. It is
     * owed only when the reduction is actually applied — damage that cannot be reduced spends the
     * shield without billing anyone.
     */
    void shieldNextIncomingDamageReductionKickback(ForwardTarget t, int reduction,
                                                   CardData bearer, int damage);

    /** Reduces the next damage dealt to {@code t} by abilities or Summons by {@code reduction}. */
    void shieldNextAbilityIncomingDamageReduction(ForwardTarget t, int reduction);

    /** All damage received by target is increased by {@code amount} until end of turn. */
    void debuffIncomingDamageIncrease(ForwardTarget t, int amount);

    /** Damage from the opponent's Summons or abilities to target becomes 0 until end of turn. */
    void shieldAbilityDamage(ForwardTarget t);

    /**
     * Until end of turn: any Forward the active player controls matching {@code filter} takes 0
     * damage from Summons or abilities. Unlike {@link #shieldAbilityDamage}, this is evaluated at
     * damage time, so it also covers Forwards that enter the field after the shield resolves.
     */
    void shieldOwnForwardsAbilityDamageFilter(Predicate<CardData> filter);

    /**
     * Doublecast (Yuna): until end of turn, each time the active player casts a Summon, hand
     * Summons with a printed cost lower than that Summon's printed cost cast for 0. The threshold
     * follows the most recently cast Summon, so successively lower costs can chain for free.
     */
    void activateDoublecastFreeSummons();

    /**
     * Sephiroth: registers the card instance(s) named {@code cardName} that were removed from
     * the game while paying the current ability's costs as castable from the RFP zone until end
     * of turn (at printed cost). They appear in the playable-cards window while registered.
     */
    void makeRfgCostCardCastableThisTurn(String cardName);

    /**
     * Necron: removes the Forward at {@code t} from the game "for as long as [watcherName] is on
     * the field". The watcher is resolved to the first card with that name on the ability user's
     * forward line; when it later leaves the field, the removed card re-enters its owner's field.
     * If no watcher is found the removal is permanent (the watcher already left).
     */
    void removeTargetFromGameWhileNamedCardOnField(ForwardTarget t, String watcherName);

    /**
     * Necron's action ability: choose 1 card removed by {@code source}'s ability (tracked by
     * instance identity) and put it into its owner's Break Zone, cancelling the pending return.
     * Fizzles when {@code source} has no tracked removal.
     */
    void putCardRemovedBySourceIntoBreakZone(CardData source);

    /** Damage from the opponent's abilities (not Summons) to target becomes 0 until end of turn. */
    void shieldAbilityOnlyDamage(ForwardTarget t);

    /** Next damage target deals to a Forward becomes 0 (consumed on first hit). */
    void shieldNextOutgoingDamage(ForwardTarget t);

    /**
     * Until end of turn: if this Forward is dealt damage less than its power, the damage becomes 0
     * instead (per-card variant of {@link #shieldActivePlayerNonLethal}).
     */
    void shieldNonLethal(ForwardTarget t);

    /**
     * Until end of turn: if any Forward the active player controls is dealt damage less than
     * its current effective power, that damage becomes 0 instead.
     */
    void shieldActivePlayerNonLethal();

    /**
     * Until end of turn: all Forwards the active player controls take {@code reduction} less
     * incoming damage (minimum 0).
     */
    void shieldActivePlayerDamageReduction(int reduction);

    /** Until end of turn: damage dealt to all Forwards the opponent controls cannot be reduced. */
    void disableOpponentDamageReduction();

    // ---- Cannot-be-chosen protection -----------------------------------------------

    /**
     * Registers that the Forward at {@code t} cannot be selected as a target by
     * the opponent's Summons (if {@code bySummons}) or abilities (if {@code byAbilities}) this turn.
     */
    void shieldCannotBeChosen(ForwardTarget t, boolean bySummons, boolean byAbilities);

    /**
     * Applies "cannot be chosen" protection to every Forward the active player controls.
     */
    void shieldAllOwnForwardsCannotBeChosen(boolean bySummons, boolean byAbilities);

    /**
     * Finds the named card on the active player's field and applies "cannot be chosen" protection.
     */
    void shieldNamedCardCannotBeChosen(String name, boolean bySummons, boolean byAbilities);

    /** Prevents the named card from being chosen by any Summon (either player's) this turn. */
    void shieldNamedCardCannotBeChosenByAnySummon(String name);

    /**
     * Shields {@code source} from being chosen by any Summon <em>or</em> ability this turn, by
     * either player — 2-065L Balthier's Fires of War, the corpus's only unqualified both-halves
     * immunity.
     *
     * <p>Symmetric, unlike the shields spelled "by your opponent's …": nothing in the sentence
     * names a player, so Balthier's own controller cannot choose him either.
     *
     * <p>By identity rather than by name, as the card names its own printing: a second copy of it
     * on either side is a different card and is not shielded.
     */
    void shieldSelfCannotBeChosenByAnySummonOrAbility(CardData source);

    /**
     * Registers that the named card (on the ability user's field) cannot be chosen by
     * Summons or abilities whose element matches {@code element} this turn.
     */
    void shieldNamedCardCannotBeChosenByElement(String cardName, String element);

    /**
     * Registers that damage dealt to the named card by a Summon or ability whose element
     * matches {@code element} becomes 0 this turn — including AoE effects that do not target.
     */
    void nullifyNamedCardDamageByElement(String cardName, String element);

    /**
     * Registers that damage dealt to the named card by an ability (not a Summon) whose element
     * matches {@code element} becomes 0 this turn — including AoE effects that do not target.
     */
    void nullifyNamedCardDamageByElementAbilityOnly(String cardName, String element);

    /**
     * Finds the named card on the field and stores a permanent element override.
     * While active, the card's effective element is {@code element} instead of its printed element.
     * This override persists across turns until explicitly changed.
     */
    void setCardElement(String cardName, String element);

    /**
     * Stores a permanent element override on the card at {@code t} — the chosen-target twin of
     * {@link #setCardElement(String, String)}, which can only reach a card by name.
     *
     * <p>12-021R Necron ("choose 1 Character other than Necron you control. Its Element becomes
     * Dark.") needs the target form: the chooser picks one of several Characters, and naming is
     * ambiguous once two copies share a name. The override outlives the turn, as the printed
     * "(This effect does not end at the end of the turn.)" says.
     */
    void setTargetElement(ForwardTarget t, String element);

    /**
     * Shows a modal dialog for the ability user to name one Element, or picks randomly for the AI.
     *
     * @param prompt text shown above the picker
     * @return the selected element name, or {@code null} if cancelled
     */
    String selectElement(String prompt);

    /** Like {@link #selectElement(String)} but hides elements in {@code excluded} from the picker. */
    String selectElement(String prompt, java.util.Set<String> excluded);

    /**
     * Presents the ability user with a choice among {@code choices} and returns the selected value.
     * The AI picks randomly.
     *
     * @param prompt  text shown above the picker
     * @param choices the options to present
     * @return the selected option, or {@code null} if cancelled
     */
    String selectOption(String prompt, String[] choices);

    /**
     * Applies "cannot be chosen" protection to all Forwards matching {@code job} that the active
     * player controls, optionally excluding the card named {@code excludeName}.
     */
    void shieldJobForwardsCannotBeChosen(String job, String excludeName, boolean bySummons, boolean byAbilities);

    /**
     * Registers that the Character at {@code t} cannot be broken this turn.
     * Respected by {@link #breakTarget}; bypassed by {@link #forceTargetToBreakZone}.
     */
    void shieldCannotBeBroken(ForwardTarget t);

    /**
     * Registers that the Character at {@code t} cannot be broken this turn by
     * opposing Summons or abilities that don't deal damage.
     * Respected by {@link #breakTarget}; bypassed by damage-based breaks and {@link #forceTargetToBreakZone}.
     */
    void shieldCannotBeBrokenByNonDmg(ForwardTarget t);

    /**
     * Marks the Forward at {@code t} so that, if it is put from the field into the Break Zone at
     * any point this turn (by any effect — battle, another ability, etc.), it is removed from the
     * game instead. Cleared at end of turn if not triggered.
     */
    void markTargetRfgInsteadOfBzThisTurn(ForwardTarget t);

    /**
     * Marks {@code t} so that the player resolving this ability draws {@code count} card(s) when
     * {@code t} is put from the field into the Break Zone during this turn — a delayed trigger,
     * regardless of what later breaks it. Does not fire if the card is removed from the game
     * instead of reaching the Break Zone.
     */
    void markTargetDrawOnFieldToBzThisTurn(ForwardTarget t, int count);

    /**
     * Marks the Forward at {@code t} so that when it leaves the field this turn, {@code source} is
     * put into the Break Zone — 7-055R Chocobo, which lends +3000 power and follows the borrower
     * off the field if it goes.
     *
     * <p>Fires on leaving the field by any route, not just the Break Zone one, which is what the
     * printed "leaves the field" says and what separates it from
     * {@link #markTargetDrawOnFieldToBzThisTurn}. The mark is dropped with the rest of the turn's
     * state, so a borrower that survives the turn costs nothing.
     */
    void markTargetPutSourceToBzOnLeaveThisTurn(ForwardTarget t, CardData source);

    /**
     * Arms {@link #markTargetDrawOnFieldToBzThisTurn} to be applied to the next set of targets
     * chosen through this context, so the mark lands between choosing a target and acting on it.
     */
    void armDrawOnFieldToBzMark(int count);

    /** Returns and clears the count armed by {@link #armDrawOnFieldToBzMark}; {@code 0} when none. */
    int consumeDrawOnFieldToBzMark();

    /** Finds {@code source} on the field by name and dulls it. No-op if not found. */
    void dullSourceForward(CardData source);

    /** Registers that the named source card (found on own field) cannot be broken this turn. */
    void shieldSourceForward(CardData source);

    /** Registers that all own Forwards cannot be broken this turn. */
    void shieldAllOwnForwards();

    /**
     * Grants {@code t} the Breaktouch battle effect until end of turn:
     * when this Forward deals battle damage to a Forward, that Forward is broken.
     */
    void shieldBreaktouchBattle(ForwardTarget t);

    /**
     * Grants {@code t} "When this Forward is dealt damage, break this Forward." until end of turn
     * — Vallaide 22-020R.
     *
     * <p>The mirror image of {@link #shieldBreaktouchBattle}: that one arms the Forward that
     * <em>deals</em> the damage and only in battle, this one arms the Forward that
     * <em>receives</em> it, from any source.
     */
    void grantBreakWhenDealtDamage(ForwardTarget t);

    /**
     * Lends {@code source} every action ability of the card at {@code target} until end of turn —
     * Gogo 9-107C.
     *
     * <p>Action abilities only: Special abilities are a separate kind under rule 6-1-1 and the
     * printing does not name them. Abilities that cannot be used from the field at all (Break Zone
     * and in-hand ones) are left behind for the same reason.
     *
     * <p>Each borrowed ability's text is re-pointed at {@code source} through
     * {@link ActionResolver#substituteSourceName}, so one that names its own card acts on the
     * borrower rather than on the donor still standing across the table. Costs and restrictions
     * are copied as printed.
     */
    void gainTargetActionAbilitiesUntilEndOfTurn(CardData source, ForwardTarget target);

    /**
     * Moves the Forward at {@code t} (currently opponent-controlled) to the active player's field.
     * The card retains its current accumulated damage. No ETF auto-abilities fire.
     *
     * @param t        target — must be opponent-controlled; silently ignored otherwise
     * @param condition {@code "permanent"} to keep the card indefinitely;
     *                  {@code "endOfTurn"} to return it at end of turn;
     *                  {@code "whileCardOnField:Name"} to return it when the named card leaves the field
     * @param activate  {@code true} to force the card to ACTIVE state when it arrives
     */
    void gainControlOfForward(ForwardTarget t, String condition, boolean activate);

    /**
     * Permanently gives the ability user's opponent control of {@code source} (currently a
     * Forward on the field, either side). The reverse direction of {@link #gainControlOfForward}
     * — used for "your opponent gains control of [CardName]" effects (e.g. Leon). Preserves
     * accumulated damage and current state; no ETF auto-abilities fire.
     */
    void giveSourceControlToOpponent(CardData source);

    /**
     * Immediately removes all accumulated damage from the Forward at {@code t}, negating it.
     * Has no effect on non-Forward targets or targets with no damage.
     */
    void negateAllDamage(ForwardTarget t);

    /**
     * Immediately removes all accumulated damage from every Forward the active player controls.
     */
    void negateAllDamageOwnForwards();

    // ---- "For each" scaling queries -------------------------------------------

    /** Returns {@code true} if the "if you control" condition is met by the active player's field. */
    boolean controlConditionMet(ControlCondition cond);

    /**
     * Like {@link #controlConditionMet} but excludes all field cards named {@code excludeName}
     * before evaluating — used for "other than [name]" conditions.
     */
    boolean controlConditionMetExcluding(ControlCondition cond, String excludeName);

    /**
     * Returns {@code true} if the opponent controls at least one card of {@code cardType}
     * ("Forward", "Monster", "Backup", or "Character") satisfying {@code cardCondition}
     * ("damaged", "dull", "active", "attacking", "blocking", or {@code null} for any state).
     */
    boolean opponentControlsCard(String cardType, String cardCondition);

    /**
     * Counts how many of the opponent's field cards satisfy both the type filter and the condition.
     * {@code inclForwards}, {@code inclBackups}, {@code inclMonsters} select which card types to count.
     * {@code condition} is "dull", "damaged", "active", "attacking", "blocking", or {@code null} for any.
     */
    int countOppFieldCardsWithCondition(boolean inclForwards, boolean inclBackups, boolean inclMonsters, String condition);

    /** Returns {@code true} if the active player received at least one point of game damage this turn. */
    boolean selfReceivedDamageThisTurn();

    /** Returns {@code true} if a Forward the active player controls formed a party attack this turn. */
    boolean ownForwardFormedPartyThisTurn();

    /**
     * Whether {@code source} has activated the Special ability named {@code specialName} this turn
     * — 7-059L Bartz's Rapid Fire, which reads back Spellblade and Dual-Wield.
     *
     * <p>Recorded when an activation's costs are paid, so an ability that is cancelled on the stack
     * still counts as used, which is what "used … this turn" means.
     */
    boolean sourceUsedSpecialThisTurn(CardData source, String specialName);

    /**
     * Returns the number of cards of {@code cardType} ("Forward", "Backup", "Monster",
     * or "Character") the active player currently controls on the field.
     */
    int ownFieldCount(String cardType);

    /**
     * Returns the number of cards on the active player's field that match {@code type}
     * (Forward/Backup/Monster/Character) AND belong to {@code category}.
     */
    int ownFieldCountByCategory(String category, String type);

    /** Returns {@code true} if the active player has at least one Summon in their Break Zone. */
    boolean selfHasSummonInBreakZone();

    /** Returns the number of cards in the opponent's damage zone. */
    int opponentDamageCount();

    /** Returns the number of cards the active player has cast from hand this turn. */
    int selfCardsCastThisTurn();

    /**
     * Returns how many times the active player has cast a card with the given name this turn.
     * Used for "If you have cast a Card Name X other than X this turn" conditions.
     */
    int countCardsNamedCastThisTurn(String name);

    /** Returns {@code true} if the active player has cast a Summon this turn. */
    boolean selfSummonCastThisTurn();

    /** Returns the number of Forwards the active player controls. */
    int selfForwardCount();

    /** Returns the number of Forwards the opponent controls. */
    int opponentForwardCount();

    /**
     * Returns the count of field cards the active player controls, filtered by type and element.
     *
     * @param element      optional element filter (e.g. "Fire"); {@code null} = any
     * @param inclForwards  count Forwards
     * @param inclBackups   count Backups
     * @param inclMonsters  count Monsters
     */
    int selfFieldCount(String element, boolean inclForwards, boolean inclBackups, boolean inclMonsters);

    /**
     * Returns the count of distinct elements among the active player's field cards of the given types.
     * Multi-element cards (e.g. Fire/Ice) contribute each of their elements independently.
     */
    int selfDistinctElementCount(boolean inclForwards, boolean inclBackups, boolean inclMonsters);

    /** Returns {@code true} if this ability is resolving as the result of an EX Burst. */
    boolean isExBurst();

    /** Returns the number of cards in P1's damage zone. */
    int p1DamageCount();

    /** Returns the number of cards in P2's damage zone. */
    int p2DamageCount();

    /** Returns the number of cards in the ability user's own damage zone. */
    default int selfDamageCount() { return isP1() ? p1DamageCount() : p2DamageCount(); }

    /** Returns the number of cards in the opponent's hand. */
    int opponentHandSize();

    /** Returns the number of cards in the ability user's hand. */
    int yourHandSize();

    /**
     * Counts P1's field cards matching all supplied filters.
     *
     * @param inclForwards   include Forwards
     * @param inclBackups    include Backups
     * @param inclMonsters   include Monsters
     * @param jobFilter      bar-separated job name(s); {@code null} = any
     * @param cardNameFilter exact card name; {@code null} = any
     */
    int countP1FieldCards(boolean inclForwards, boolean inclBackups, boolean inclMonsters,
            String jobFilter, String cardNameFilter);

    /**
     * Counts P1's field cards matching all supplied filters, including an optional category filter.
     *
     * @param categoryFilter category substring (e.g. {@code "VII"}); {@code null} = any
     */
    int countP1FieldCards(boolean inclForwards, boolean inclBackups, boolean inclMonsters,
            String jobFilter, String cardNameFilter, String categoryFilter);

    /**
     * Counts P1's field cards matching all supplied filters, including an optional element filter.
     *
     * @param elementFilter element name (e.g. {@code "Earth"}); {@code null} = any
     */
    int countP1FieldCards(boolean inclForwards, boolean inclBackups, boolean inclMonsters,
            String jobFilter, String cardNameFilter, String categoryFilter, String elementFilter);

    int countP2FieldCards(boolean inclForwards, boolean inclBackups, boolean inclMonsters,
            String jobFilter, String cardNameFilter);

    int countP2FieldCards(boolean inclForwards, boolean inclBackups, boolean inclMonsters,
            String jobFilter, String cardNameFilter, String categoryFilter);

    int countP2FieldCards(boolean inclForwards, boolean inclBackups, boolean inclMonsters,
            String jobFilter, String cardNameFilter, String categoryFilter, String elementFilter);

    /** Counts the opponent's field cards — routes to P2 or P1 based on {@link #isP1()}. */
    default int countOppFieldCards(boolean inclForwards, boolean inclBackups, boolean inclMonsters,
            String jobFilter, String cardNameFilter, String categoryFilter, String elementFilter) {
        return isP1()
                ? countP2FieldCards(inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, elementFilter)
                : countP1FieldCards(inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, elementFilter);
    }

    /** Counts the ability user's own field cards — routes to P1 or P2 based on {@link #isP1()}. */
    default int countSelfFieldCards(boolean inclForwards, boolean inclBackups, boolean inclMonsters,
            String jobFilter, String cardNameFilter) {
        return isP1()
                ? countP1FieldCards(inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter)
                : countP2FieldCards(inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter);
    }

    default int countSelfFieldCards(boolean inclForwards, boolean inclBackups, boolean inclMonsters,
            String jobFilter, String cardNameFilter, String categoryFilter) {
        return isP1()
                ? countP1FieldCards(inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter)
                : countP2FieldCards(inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter);
    }

    default int countSelfFieldCards(boolean inclForwards, boolean inclBackups, boolean inclMonsters,
            String jobFilter, String cardNameFilter, String categoryFilter, String elementFilter) {
        return isP1()
                ? countP1FieldCards(inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, elementFilter)
                : countP2FieldCards(inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, elementFilter);
    }

    /**
     * Counts the ability user's own field cards with an optional exact-cost filter.
     *
     * @param costFilter exact card cost to match; {@code -1} = any cost
     */
    default int countSelfFieldCards(boolean inclForwards, boolean inclBackups, boolean inclMonsters,
            String jobFilter, String cardNameFilter, String categoryFilter, String elementFilter, int costFilter) {
        return isP1()
                ? countP1FieldCards(inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, elementFilter, costFilter)
                : countP2FieldCards(inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, elementFilter, costFilter);
    }

    int countP1FieldCards(boolean inclForwards, boolean inclBackups, boolean inclMonsters,
            String jobFilter, String cardNameFilter, String categoryFilter, String elementFilter, int costFilter);

    int countP2FieldCards(boolean inclForwards, boolean inclBackups, boolean inclMonsters,
            String jobFilter, String cardNameFilter, String categoryFilter, String elementFilter, int costFilter);

    /**
     * Counts cards in P1's Break Zone matching all supplied filters.
     *
     * @param cardNameFilter exact card name; {@code null} = any
     * @param jobFilter      bar-separated job name(s); {@code null} = any
     */
    int countP1BreakZoneCards(String cardNameFilter, String jobFilter);

    int countP2BreakZoneCards(String cardNameFilter, String jobFilter);

    /** Counts the ability user's own Break Zone cards — routes to P1 or P2 based on {@link #isP1()}. */
    default int countSelfBreakZoneCards(String cardNameFilter, String jobFilter) {
        return isP1()
                ? countP1BreakZoneCards(cardNameFilter, jobFilter)
                : countP2BreakZoneCards(cardNameFilter, jobFilter);
    }

    /**
     * Counts cards of the named types in P1's Break Zone (e.g. Tonberry's "for every 2 Forwards in
     * your Break Zone"). Unlike the field, a Break Zone holds every card type, so each type a count
     * accepts has to be named — including Summons, which have no field equivalent.
     *
     * <p>Types are the card's printed type. A Monster whose field ability makes it "also become a
     * Forward" is a Monster once it is in the Break Zone, so it is not counted as a Forward.
     */
    int countP1BreakZoneCardsByType(boolean inclForwards, boolean inclBackups,
            boolean inclMonsters, boolean inclSummons);

    int countP2BreakZoneCardsByType(boolean inclForwards, boolean inclBackups,
            boolean inclMonsters, boolean inclSummons);

    /** Counts the ability user's own Break Zone cards by type — routes to P1 or P2 based on {@link #isP1()}. */
    default int countSelfBreakZoneCardsByType(boolean inclForwards, boolean inclBackups,
            boolean inclMonsters, boolean inclSummons) {
        return isP1()
                ? countP1BreakZoneCardsByType(inclForwards, inclBackups, inclMonsters, inclSummons)
                : countP2BreakZoneCardsByType(inclForwards, inclBackups, inclMonsters, inclSummons);
    }

    /**
     * Counts Break Zone cards matching a type, element and cost ceiling — what an effect needs to
     * know before offering a Break Zone target ("1 Fire Forward of cost 3 or less in your Break
     * Zone"), without opening the selection dialog to find out.
     *
     * @param elementFilter element the card must contain; {@code null} = any
     * @param maxCost       highest cost accepted; {@code -1} = any
     */
    int countP1BreakZoneMatching(boolean inclForwards, boolean inclBackups, boolean inclMonsters,
            boolean inclSummons, String elementFilter, int maxCost);

    int countP2BreakZoneMatching(boolean inclForwards, boolean inclBackups, boolean inclMonsters,
            boolean inclSummons, String elementFilter, int maxCost);

    /** Counts the ability user's own matching Break Zone cards — routes on {@link #isP1()}. */
    default int countSelfBreakZoneMatching(boolean inclForwards, boolean inclBackups,
            boolean inclMonsters, boolean inclSummons, String elementFilter, int maxCost) {
        return isP1()
                ? countP1BreakZoneMatching(inclForwards, inclBackups, inclMonsters, inclSummons, elementFilter, maxCost)
                : countP2BreakZoneMatching(inclForwards, inclBackups, inclMonsters, inclSummons, elementFilter, maxCost);
    }

    /** Counts cards owned by P1 that are removed from the game (P1's RFP zone), by name/job filter. */
    int countP1RfgCards(String cardNameFilter, String jobFilter);

    /** Counts cards owned by P2 that are removed from the game (P2's RFP zone), by name/job filter. */
    int countP2RfgCards(String cardNameFilter, String jobFilter);

    /**
     * Counts the cards the ability user owns that are removed from the game — their own RFP zone
     * only.  Routes to P1 or P2 based on {@link #isP1()}, which is what separates it from
     * {@link #countRemovedFromGame()}: card text saying "<em>your</em> cards have been removed
     * from the game" is scoped to the owner, not to both players' zones combined.
     */
    default int countSelfRfgCards(String cardNameFilter, String jobFilter) {
        return isP1()
                ? countP1RfgCards(cardNameFilter, jobFilter)
                : countP2RfgCards(cardNameFilter, jobFilter);
    }

    /**
     * Counts the ability user's own cards in their Break Zone plus the ones they own that are
     * removed from the game (e.g. Jill: "the Job Eikon in your Break Zone and/or Job Eikon you
     * own removed from the game"). Routes to P1 or P2 based on {@link #isP1()}.
     */
    default int countSelfBreakZoneAndRfgCards(String cardNameFilter, String jobFilter) {
        return countSelfBreakZoneCards(cardNameFilter, jobFilter)
                + (isP1() ? countP1RfgCards(cardNameFilter, jobFilter)
                          : countP2RfgCards(cardNameFilter, jobFilter));
    }

    /** The {@link CardData} at index {@code idx} in P1's Break Zone, or {@code null} if out of range. */
    CardData p1BreakZoneCard(int idx);

    /** The {@link CardData} at index {@code idx} in P2's Break Zone, or {@code null} if out of range. */
    CardData p2BreakZoneCard(int idx);

    // ---- Computed-damage queries -----------------------------------------------

    /** Returns the highest effective power among all P1 Forwards on the field; {@code 0} if none. */
    int highestP1ForwardPower();

    /** Returns the highest effective power among all P2 Forwards on the field; {@code 0} if none. */
    int highestP2ForwardPower();

    /** Returns the highest effective power among all own (ability-user's) Forwards; {@code 0} if none. */
    default int selfHighestForwardPower() { return isP1() ? highestP1ForwardPower() : highestP2ForwardPower(); }

    /** Returns the lowest effective power among all P1 Forwards on the field; {@code 0} if none. */
    int lowestP1ForwardPower();

    /** Returns the lowest effective power among all P2 Forwards on the field; {@code 0} if none. */
    int lowestP2ForwardPower();

    /** Returns the lowest effective power among the opponent's (of the ability-user) Forwards; {@code 0} if none. */
    default int opponentLowestForwardPower() { return isP1() ? lowestP2ForwardPower() : lowestP1ForwardPower(); }

    /**
     * Returns the effective power of the first field Forward or Monster whose name matches
     * {@code cardName} (case-insensitive), searching P1's zones then P2's.
     * Returns {@code -1} if no matching card is found.
     */
    int fieldForwardPowerByName(String cardName);

    /**
     * Returns the index of the opponent's forward currently blocking the named card in active
     * combat.  {@code attackerIsP1} indicates the side of the named attacker.
     * Returns {@code -1} if there is no current blocker for that card.
     */
    int combatBlockerIdxForAttacker(String attackerName, boolean attackerIsP1);

    /**
     * Returns the Forward paired with {@code cardName} in the current Battle — the one blocking it
     * if it is the attacker, or the one it is blocking if it is the blocker — or {@code null} when
     * the named card is not in a Battle or is unblocked.
     *
     * <p>Both directions, because 2-114C Ninja's "the Forward that blocks <b>or is blocked by</b>
     * Ninja" names one Forward through either role, and the ability can be activated from either
     * side of the pairing. {@link #combatBlockerIdxForAttacker} answers only the attacking half.
     */
    ForwardTarget combatBattlePartnerOf(String cardName);

    /**
     * Returns the effective power of the Forward that was dulled as a "Dull N active Forward"
     * cost payment for the current ability.  Returns {@code 0} if no such payment was made.
     */
    int dullForwardCostPower();

    /**
     * Presents the player with a "Select N of M following actions" modal choice and returns
     * the chosen action texts (each later re-parsed and applied by the caller).
     * The human player picks interactively; the AI picks the first {@code selectCount}.
     *
     * @param actions     the candidate action texts (the quoted sub-actions)
     * @param selectCount how many to choose
     * @param upTo        when {@code true}, the player may choose fewer than {@code selectCount}
     */
    java.util.List<String> chooseActions(CardData source, java.util.List<String> actions,
            int selectCount, boolean upTo);
  
  /**
     * Returns the printed power of the Forward most recently discarded as part of resolving the
     * current ability (e.g. Kolka's "you may discard 1 Forward. When you do so … the discarded
     * Forward's power"). Returns {@code 0} when no Forward has been discarded yet in the chain.
     */
    int lastDiscardedForwardPower();

    /**
     * Returns the total printed power of the Forward(s) put into the Break Zone as a cost
     * for the current ability. Returns {@code 0} when no Forward was put into the BZ as a cost.
     */
    int bzCostForwardPower();

    /**
     * Suppresses EX Burst triggers for all cards put into any Damage Zone
     * due to this ability's resolution. Cleared automatically at the start of the next ability.
     */
    void suppressExBurstsThisAbility();

    /**
     * Marks the effect being resolved as one whose chosen targets only benefit from it, so an AI
     * controller aims an unqualified selection at its own cards.  No effect on a human's choice.
     */
    void setAiPrefersOwnTargets(boolean preferOwn);

    /**
     * Tells this context how much damage the targets it is about to select will be dealt, so an AI
     * controller can aim the selection at a Character the damage would actually break rather than
     * at one that survives it.  {@code 0} clears the hint.  Advisory, and scoped to this context
     * instance: no effect on a human's choice.
     */
    void setAiDamageTargetHint(int damage);

    /**
     * Grants {@code source} "EX Bursts of cards put into the Damage Zone due to [source] cannot be
     * used" until the end of the turn (Shadow Lord 12-071R).  Broader than
     * {@link #suppressExBurstsThisAbility}: it follows the card, so every point of player damage
     * credited to it for the rest of the turn — combat or ability — suppresses the revealed
     * card's EX Burst, not just the damage from this one resolution.
     */
    void grantSelfExBurstSuppression(CardData source);

    /**
     * Returns the name of the card most recently discarded by a self-discard effect in the
     * current ability chain, or {@code null} when no card has been discarded yet.
     */
    String lastDiscardedCardName();

    /**
     * Returns every element of the card most recently discarded as a cost payment, or an empty
     * list if no card has been discarded as a cost yet in the current ability chain. A
     * multi-element card (e.g. Water/Fire) reports both, since it counts as a card "of Water
     * Element" <em>and</em> "of Fire Element" for the conditionals that read this.
     */
    List<String> lastDiscardedCostCardElements();

    /**
     * Returns the name of the card most recently discarded as a cost payment in the current ability
     * chain, or {@code null} if none has been. Used by "If the discarded card is a Card Name X"
     * conditionals attached to a cost-discard ability.
     */
    String lastDiscardedCostCardName();

    /**
     * Whether the card discarded to pay the current ability's cost was a Summon — 29-107C Seer
     * (FFTA2), whose damage doubles when the discard it paid with was one.
     *
     * <p>Reads the cost payment, not the last discard of any kind: an effect that discarded a card
     * earlier in the same ability must not be mistaken for the cost.
     */
    boolean lastDiscardedCostCardIsSummon();

    /**
     * Returns {@code true} when the card most recently discarded by an effect (not a cost) in the
     * current ability chain is a Multi-Element card. Used by "If the discarded card is a
     * Multi-Element card, …" conditionals attached to a draw/discard effect.
     */
    boolean lastDiscardedCardIsMultiElement();

    /**
     * Returns the CP cost of the Forward most recently removed from the game by a
     * "remove it from the game" effect in the current ability chain.
     * Returns {@code 0} if no Forward has been removed yet.
     */
    int lastRemovedFromGameCardCost();

    /**
     * Returns the power of the Forward most recently removed from the game by a
     * "remove it from the game" effect in the current ability chain.
     * Returns {@code 0} if no Forward has been removed yet or the card has no power.
     */
    int lastRemovedFromGameCardPower();

    /** Returns the total number of cards permanently removed from the game (both players' RFP zones combined). */
    int countRemovedFromGame();

    /**
     * Dulls all Forwards the opponent controls whose effective power is less than or equal to
     * {@code source}'s current effective power on the field.
     */
    default void dullOpponentForwardsByPowerAtMost(CardData source) {
        int sourcePower = fieldForwardPowerByName(source.name());
        if (sourcePower <= 0) sourcePower = source.power();
        final int sp = sourcePower;
        logEntry(source.name() + " — Dull all opponent Forwards with power ≤ " + sp);
        boolean p1 = isP1();
        int count = p1 ? p2ForwardCount() : p1ForwardCount();
        for (int i = count - 1; i >= 0; i--) {
            ForwardTarget t = new ForwardTarget(!p1, i, ForwardTarget.CardZone.FORWARD);
            int power = effectiveTargetPower(t);
            if (power > 0 && power <= sp) {
                if (p1) dullP2Forward(i); else dullP1Forward(i);
            }
        }
    }

    /**
     * Pushes a new stack entry for the auto-ability on {@code source} whose trigger matches
     * {@code triggerType} (e.g. {@code "beginning of attack phase"}). Used to retrigger an
     * ability after a conditional self-discard.
     */
    void retriggerAutoAbility(CardData source, String triggerType);

    /**
     * Returns the effective power of the target Forward or Monster.
     * Returns {@code 0} for Backups or out-of-range indices.
     */
    int effectiveTargetPower(ForwardTarget t);

    /**
     * The card currently occupying {@code t}, or {@code null} if the slot is empty. Useful for
     * holding on to a chosen card across an effect that moves it — a target's side and index go
     * stale the moment control of it changes.
     */
    CardData targetCard(ForwardTarget t);

    /** True when the ability user currently controls {@code card} (by identity, any field zone). */
    boolean selfControlsCard(CardData card);

    /**
     * Forces the ability-user's opponent to discard {@code count} cards from hand
     * to their Break Zone.  No CP is generated.
     * When P1 is the ability user, P2 AI discards automatically (worst cards first).
     * When P2 is the ability user, P1 is prompted via a selection dialog.
     */
    void forceOpponentDiscard(int count);

    /**
     * Prompts the active player to name 1 of the 4 card types (Forward/Backup/Monster/Summon),
     * then forces the opponent to discard 1 card.  If the discarded card's type matches the
     * named type, the active player draws 1 card.
     * CPU: when naming, picks the type most common in the opponent's hand.
     * CPU: when discarding, prefers a card whose type does NOT match the named type.
     */
    void nameCardTypeOpponentDiscardDrawIfMatch();

    /**
     * Forces the ability-user's opponent to discard {@code count} randomly chosen cards
     * from hand to their Break Zone.  No CP is generated.  Neither player chooses —
     * cards are selected at random.
     */
    void forceOpponentRandomDiscard(int count);

    /**
     * Randomly removes {@code count} cards from the ability-user's opponent's hand and
     * places them in the permanent RFP zone.  Neither player chooses — selected at random.
     */
    void forceOpponentRandomHandRfp(int count);

    /**
     * Randomly takes {@code count} cards from the ability-user's opponent's hand and
     * places them at the bottom of their deck.  Neither player chooses — selected at random.
     */
    void forceOpponentRandomHandToBottomOfDeck(int count);

    /**
     * Reveals the ability-user's opponent's hand, then lets the ability user select
     * {@code count} cards from it to remove from the game permanently.
     * When P1 is the ability user, P1 is shown a dialog with P2's hand.
     * When P2 is the ability user, the AI picks the highest-value cards from P1's hand.
     */
    void selectFromOpponentHandAndRfp(int count);

    /**
     * Reveals the ability-user's opponent's hand, then lets the ability user select {@code count}
     * cards from it for the opponent to discard to their Break Zone.  The discard is forced but
     * the choice is the ability user's, which is what separates this from
     * {@link #forceOpponentDiscard(int)}.
     *
     * <p>{@code eligible} narrows what may be selected — "1 Forward from their hand", "1 card of
     * cost 4 or more" — and is {@code null} when any card qualifies.  {@code eligibleDesc} names
     * that restriction for the log and the selection prompt.  When no card in hand qualifies,
     * nothing is discarded.
     *
     * <p>When P1 is the ability user, P1 is shown a dialog with the qualifying cards from P2's
     * hand.  When P2 is the ability user, the AI picks the highest-cost qualifying card.
     */
    void selectFromOpponentHandAndDiscard(int count, Predicate<CardData> eligible, String eligibleDesc);

    /**
     * The ability-user's opponent reveals {@code revealCount} cards of <em>their own</em> choosing
     * from hand; the ability user then selects 1 of those to be discarded.  (14-035C Don Corneo.)
     *
     * <p>Two players decide, which is what separates this from
     * {@link #selectFromOpponentHandAndDiscard(int, Predicate, String)}.  There the whole hand is
     * exposed and the ability user picks freely from all of it; here the opponent controls what is
     * ever shown, so a well-played hand only ever offers up its three least valuable cards.
     *
     * <p>Hand size is read when this runs, not when the ability was put on the Stack — a response
     * can add cards to the opponent's hand first.  An opponent holding {@code revealCount} cards or
     * fewer is not asked which to reveal, the choice being forced; one holding none reveals
     * nothing and discards nothing.
     */
    void opponentRevealsSelectOneDiscard(int revealCount);

    /**
     * Reveals the ability-user's opponent's hand, then lets the ability user select up to
     * {@code count} cards to remove from the game <em>until the end of the opponent's next
     * turn</em>, at which point they return to their owner's hand.  The temporary removal is
     * what separates this from {@link #selectFromOpponentHandAndRfp(int)}.
     */
    void selectFromOpponentHandRfpUntilEndOfOpponentTurn(int count);

    /**
     * Reveals the opponent's hand, lets the ability user optionally select 1 card; if one is
     * selected, the opponent discards it and then draws 1 card.
     * (24-046R Leech Bat, 25-042C Zidane — the discard sibling of
     * {@link #revealHandOptPickRfpOpponentDraws()}.)
     */
    void revealHandOptPickDiscardOpponentDraws();

    /**
     * Reveals the opponent's hand, lets the ability user optionally select 1 card to remove from
     * the game permanently; if a card is removed, the opponent then draws 1 card.
     * (Zidane-style: "You may select 1 card. If you do so, remove it from the game and your
     * opponent draws 1 card.")
     */
    void revealHandOptPickRfpOpponentDraws();

    /**
     * Forces the ability-user's opponent to remove {@code count} cards from their own
     * hand from the game permanently.  The opponent (not the ability user) chooses which.
     * When P1 is the ability user, the P2 AI picks automatically.
     * When P2 is the ability user, P1 is prompted via a selection dialog.
     */
    void forceOpponentHandRfp(int count);

    /**
     * Aemo 23-022R: the ability-user's opponent removes their whole hand from the game face down
     * and takes it back at the end of the turn.
     *
     * <p>Nothing is selected and nothing is lost -- the hand comes back intact -- so what the card
     * actually does is deny its use for the rest of the turn: no blocks paid for out of hand, no
     * discard costs, and every "if your opponent has no cards in their hand" clause satisfied while
     * it lasts. That is why it goes to the removed-from-game zone rather than a private holding
     * area: effects that read that zone should see these cards, and hand size should not.
     *
     * <p>Face down means only that the ability user may not look at them; the owner may, at any
     * time, and the removal is public. {@link GameState#addToPermanentRfpFaceDown} carries that,
     * and the return clears it by taking the cards out of the zone.
     *
     * <p>Scheduled through {@link #addEndOfTurnEffect}, so the hand returns at the end of the turn
     * the ability was used in -- which is the turn it names, the card being usable only on its
     * controller's turn. An opponent holding no cards is left alone.
     */
    void opponentRemovesHandFaceDownUntilEndOfTurn();

    /**
     * Searches the field (both players' forwards, backups, and monsters) for a card
     * matching {@code cardName} and removes the first match from the game permanently.
     */
    void removeNamedCardFromGame(String cardName);

    /**
     * Removes all cards currently in the opponent's Break Zone from the game permanently.
     */
    void removeAllOpponentBzFromGame();

    /**
     * Searches P1 and P2 permanent RFP zones for a card matching {@code cardName} and places
     * the first match onto its owner's forward zone (triggering entering-field abilities).
     */
    void playNamedFromRfpOntoField(String cardName);

    /**
     * Plays the card named {@code cardName} onto the field from whichever holding zone currently
     * holds it — the RFG zone first, then the controller's Break Zone.
     *
     * <p>"Play [Name] onto the field at the end of the turn." names no zone, and by the time the
     * delayed effect fires the card's whereabouts depend on what put it there: Lightning 16-124H
     * removed itself from the game in the preceding sentence, while Ardyn B-024 triggers on being
     * put into the Break Zone. Logs a warning and does nothing when neither zone holds it.
     */
    void playNamedFromHoldingZoneOntoField(String cardName);

    /**
     * Plays the most recently removed card from the active player's permanent RFP zone back onto
     * the field. If {@code dull} is true, the card enters dull.
     */
    void playLastRemovedFromRfpOntoField(boolean dull);

    /**
     * Searches the field for a card matching {@code cardName} and returns it to its owner's hand.
     * P1-zone cards go to P1's hand; P2-zone cards go to P2's hand.
     */
    void returnNamedCardToOwnersHand(String cardName);

    /**
     * Searches the field for a card matching {@code cardName} and returns it to your (P1's) hand.
     * If the card is the currently resolving Summon, it returns to hand instead of the Break Zone.
     */
    void returnNamedCardToYourHand(String cardName);

    /**
     * Grants the named card permission to attack once more this turn —
     * clears any "cannot attack" restriction on it for this turn.
     */
    void grantAttackOnceMore(String cardName);

    /** Limits the opponent's attack declarations to {@code max} this turn. */
    void limitOpponentAttackDeclarationsThisTurn(int max);

    /** Prevents the opponent from searching their deck this turn. */
    void setOpponentCannotSearchThisTurn();

    /**
     * Arms Alhanalem 18-018R for the rest of the turn: any Character that would enter the field
     * because of a Summon or ability belonging to this player's opponent is removed from the game
     * instead, never arriving and so never firing its "enters the field" ability.
     *
     * <p>A cast is untouched — the sentence names Summons and abilities, and paying a card's cost
     * to put it on the field is neither.
     */
    void setOppFieldEntryRemovedFromGameThisTurn();

    /**
     * Removes the named card from the current Battle — marks it as having escaped so that
     * {@code resolveCombat} skips damage resolution for that pairing.
     * Only meaningful while the card is in Battle (attacking or blocking).
     */
    void removeFromBattle(String cardName);

    /**
     * Grants the ability user one additional turn immediately after the current turn ends.
     * At the end of that extra turn, the ability user loses the game.
     */
    void takeExtraTurnThenLose();

    /** Returns true if any Forward or Backup named {@code name} is anywhere on either player's field. */
    boolean isNamedCardOnField(String name);

    /** Causes the ability user's opponent to lose the game. */
    void causeOpponentToLose();

    /**
     * Schedules {@code effect} to fire at the end of the ability user's next turn
     * (the turn after the current one, not the current turn's end phase).
     */
    void scheduleAtEndOfControllerNextTurn(Consumer<GameContext> effect);

    /**
     * Draws {@code count} cards from the top of the ability user's deck into their hand.
     */
    void drawCards(int count);

    /**
     * Draws {@code count} cards from the top of the opponent's deck into their hand.
     * When P1 is the ability user the opponent is P2, and vice versa.
     */
    void drawCardsForOpponent(int count);

    /**
     * Prompts the ability user to discard {@code count} cards from their hand to
     * their Break Zone.  No CP is generated.
     * When P1 is the ability user, a selection dialog is shown.
     * When P2 is the ability user, the AI discards automatically (worst cards first).
     */
    void selfDiscard(int count);

    /**
     * Prompts the active player to choose {@code count} card(s) from their hand and place
     * them at the bottom of their deck. The AI places its worst cards automatically.
     */
    void placeFromHandToBottomOfDeck(int count);

    /**
     * Prompts the ability user to place <em>up to</em> {@code max} card(s) from their hand at the
     * bottom of their deck — placing none is a legal choice. The AI cycles its worst cards.
     *
     * @return how many cards were actually placed, for effects that pay out per card returned
     */
    int placeUpToFromHandToBottomOfDeck(int max);

    /**
     * Prompts the ability user to optionally discard exactly 1 card of the given type
     * (e.g. "Summon") from their hand to their Break Zone. No CP is generated.
     * The player may choose to pass (discard nothing). Sets effectMadeProgress only when
     * a card is actually discarded.
     * When P2 is the ability user the AI always passes (never voluntarily discards).
     */
    void selfDiscardByType(String cardType);

    /**
     * The two-branch form of {@link #selfDiscardByType}: offers the same optional discard, then
     * runs {@code ifDiscarded} or {@code ifNot} according to what happened — "You may discard 1
     * card from your hand. If you do so, deal it 7000 damage. If not, deal it 5000 damage."
     * (1-190S Bahamut Fury).
     *
     * <p>{@code cardType} takes the {@code CardFilters.matchesDiscardType} vocabulary, where
     * {@code "card"} means any card. Unlike {@code selfDiscardByType} this does not mark the
     * effect fizzled when nothing is discarded: for these cards declining is not a dead end but
     * the branch {@code ifNot} spells out.
     */
    void mayDiscardCardOfTypeFromHandOrElse(String cardType,
            java.util.function.Consumer<GameContext> ifDiscarded,
            java.util.function.Consumer<GameContext> ifNot);

    /**
     * Offers the ability user one optional discard of a card matching {@code cardType}, and marks
     * the effect fizzled when none happens — so a following "When you do so, …" clause does not
     * run. The type-filtered twin of {@link #mayDiscardCardOfJobFromHand}, and the branchless
     * sibling of {@link #mayDiscardCardOfTypeFromHandOrElse}: for 7-040C Yunalesca the payoff is
     * spelled by the sentence that follows rather than by an {@code ifDiscarded} argument.
     */
    void mayDiscardCardOfTypeFromHand(String cardType);

    /**
     * Prompts the ability user to discard 1 card with Job {@code jobName} from their hand.
     * Sets effectMadeProgress only when a card is actually discarded.
     * When P2 is the ability user the AI discards the worst eligible card automatically.
     */
    void selfDiscardByJob(String jobName);

    /**
     * Offers the ability user one optional discard of a card with Job {@code jobName}, and marks
     * the effect fizzled when none happens — so a following "When you do so, …" clause does not
     * run. The "you may" spelling of {@link #selfDiscardByJob}.
     *
     * <p>P2's AI takes the offer whenever it holds an eligible card, discarding its worst one.
     * Every printing of this clause pairs it with a payoff worth more than the card
     * ({@code draw 2}, {@code deal 8000}, {@code break a Forward}), so passing would only ever
     * mean the ability does nothing. That matches {@code mayDiscardCardOfTypeFromHandOrElse} and
     * differs from {@code mayDiscardCardNameFromHandOrElse}, where the AI always passes.
     */
    void mayDiscardCardOfJobFromHand(String jobName);

    /**
     * Prompts the ability user to optionally discard 1 card matching the given element
     * (e.g. "Multi-Element") from their hand to the Break Zone. No CP is generated.
     * Sets effectMadeProgress only when a card is actually discarded.
     * When P2 is the ability user the AI discards the worst eligible card automatically.
     */
    void selfDiscardByElement(String element);

    /**
     * Lets the ability user optionally reveal 1 card of {@code element} from their hand
     * (card stays in hand). Sets effectMadeProgress only when a card is actually revealed.
     * P2 AI auto-reveals if an eligible card is available.
     */
    void mayRevealCardByElementFromHand(String element);

    // ---- Special-ability replay offers ------------------------------------------

    /**
     * Offers the player the option to pay 1 CP of {@code element} to replay the ability.
     * Skips the offer if the player has no way to pay. Calls {@code replayAction} if accepted.
     */
    void mayPayToReplayAbility(String element, java.util.function.Consumer<GameContext> replayAction);

    /**
     * Offers the player the option to pay 1 CP of {@code element} to apply an optional effect.
     * Skips the offer if the player has no way to pay. Calls {@code onPay} if the player accepts.
     */
    void mayPayElementCpToEffect(String element, java.util.function.Consumer<GameContext> onPay);

    /**
     * Offers the ability controller's <em>opponent</em> the chance to pay {@code cost} CP in full to
     * prevent a pending action (e.g. Arkasodara: "If your opponent doesn't pay 《3》, break it."). If
     * the opponent declines or cannot pay the full amount, {@code onNotPaid} runs; if they pay in
     * full, it does not. Pay-in-full-or-decline — there is no partial payment that still prevents it.
     */
    void opponentMayPayToPreventAction(int cost, Runnable onNotPaid);

    /**
     * Ardyn 8-068L: offers the ability controller's <em>opponent</em> the option to put 1 Character
     * they control into the Break Zone, and reports whether they took it. The cost is the opponent's
     * to weigh — it buys them something the printing card would rather they not have — so the
     * decision goes to them, not to the controller resolving the ability.
     *
     * <p>{@code forwards} / {@code backups} / {@code monsters} narrow which of their Characters are
     * eligible. With none eligible there is no offer to make and this returns {@code false} without
     * prompting. {@code sourceName} names the printing card in the prompt, so the opponent can see
     * what they are buying.
     *
     * @return {@code true} if a Character was actually put into the Break Zone
     */
    boolean opponentMayBreakOwnCharacter(boolean forwards, boolean backups, boolean monsters,
            String sourceName);

    /**
     * Offers the <em>turn player</em> the chance to put one Character they control into the Break
     * Zone, and deals them {@code damage} points if they do not — Ardyn 28-002R's toll, collected
     * at the start of every Main Phase 1 on either player's turn.
     *
     * <p>The chooser is the turn player rather than the resolving player's opponent, which is what
     * separates this from {@link #opponentMayBreakOwnCharacter}: on the carrier's own turn the two
     * name opposite seats. A player with no eligible Character has nothing to decide and simply
     * takes the damage.
     */
    void turnPlayerBreaksOwnCharacterOrTakesDamage(boolean forwards, boolean backups, boolean monsters,
            int damage, String sourceName);

    /**
     * Offers the ability user the chance to pay an optional cost that averts a consequence — the
     * "if you don't pay 《…》, [consequence]" wording (Umaro 15-107H, Cecil 15-073H, Leon 28-056C).
     * Exactly one cost form applies. When the player cannot afford it there is no choice to make,
     * so no prompt is shown and {@code onNotPaid} runs straight away; the same happens when they
     * decline or back out of the payment. The AI pays whenever it can afford to.
     *
     * @param cp       generic CP required (《2》), or 0
     * @param element  element whose single CP is required (《Ice》), or {@code null}
     * @param crystals Crystals required (《C》), or 0
     */
    void mayPayCostOrElse(int cp, String element, int crystals, Runnable onNotPaid);

    /**
     * Offers the ability user an optional cost that unlocks an extra effect — the "you may pay
     * 《…》. If you do so, [effect]" wording (Jed 24-096R). The positive-form counterpart of
     * {@link #mayPayCostOrElse}: exactly one cost form applies, and {@code onPay} runs only when
     * the cost is actually paid in full. When the player cannot afford it there is no choice to
     * make, so no prompt is shown and nothing happens.
     *
     * @param cp       generic CP required (《2》), or 0
     * @param element  element whose single CP is required (《Ice》), or {@code null}
     * @param crystals Crystals required (《C》), or 0
     */
    void mayPayCostToEffect(int cp, String element, int crystals,
            java.util.function.Consumer<GameContext> onPay);

    /**
     * Vincent 2-078R: {@code source} deals no damage for the rest of the battle it is in, and is
     * broken once that battle finishes — whether it was blocked, went unblocked, or survived.
     */
    void breakAfterCombatAndDealNoDamage(CardData source);

    /**
     * Offers the player the option to dull an active card named {@code cardName} to replay
     * the ability. Skips the offer silently if no active card of that name is on the field.
     * Calls {@code replayAction} if the player accepts.
     */
    void mayDullActiveCardToReplayAbility(String cardName, java.util.function.Consumer<GameContext> replayAction);

    /**
     * Offers the player the option to discard a card named {@code cardName} from hand to
     * replay the ability. Skips the offer silently if no such card is in hand.
     * Calls {@code replayAction} if the player accepts.
     */
    void mayDiscardCardNameToReplayAbility(String cardName, java.util.function.Consumer<GameContext> replayAction);

    /**
     * Offers the player the option to discard a card named {@code cardName} from hand, then runs
     * {@code ifDiscarded} or {@code ifNot} according to what happened: "You may discard 1 Card
     * Name Ifrit from your hand. If you do so, deal it 10000 damage. If not, deal it 5000 damage."
     * (5-003C Ifrit).
     *
     * <p>{@code ifNot} runs for every way the discard can fail to happen — no copy in hand, the
     * player declining, the AI passing — because each of them is the card's "if not". Pass a
     * no-op for the printings that end after the "if you do so" sentence; there is deliberately no
     * two-argument convenience overload, since silently having no else branch is what left Ifrit
     * dealing no damage on either branch.
     */
    void mayDiscardCardNameFromHandOrElse(String cardName,
            java.util.function.Consumer<GameContext> ifDiscarded,
            java.util.function.Consumer<GameContext> ifNot);

    /**
     * Prompts the controlling player to optionally put {@code source} into the Break Zone.
     * If the player accepts, the card is broken and {@code whenDoSo} is executed.
     * P2 AI always passes.
     */
    void mayBreakSourceWhenDoSo(CardData source, java.util.function.Consumer<GameContext> whenDoSo);

    /**
     * Reveals 1 card matching {@code element} from the ability user's hand (the card stays in hand),
     * then draws {@code drawCount} cards. The caller is responsible for any prior "you may" gating;
     * this method assumes the reveal-and-draw will happen. If no qualifying card is in hand, the
     * effect fizzles silently.
     */
    void revealElementCardFromHandDraw(String element, int drawCount);

    /**
     * Offers the ability user the option to carry out {@code effect}.
     * P1 is shown a dialog; P2 AI auto-accepts.
     */
    void playerMayDoEffect(String prompt, java.util.function.Consumer<GameContext> effect);

    /**
     * Discards all cards from the ability user's hand to their Break Zone.  No CP is generated.
     * No selection dialog is shown — the entire hand is automatically discarded.
     */
    void selfDiscardEntireHand();

    /**
     * Flips {@code amount} cards from the opponent's deck into their damage zone,
     * using the same mechanic as attack-phase damage (EX Burst triggers included).
     * When P1 is the ability user the opponent is P2, and vice versa.
     */
    void dealDamageToOpponent(int amount);

    /**
     * Flips {@code amount} cards from the ability user's own deck into their damage zone,
     * using the same mechanic as attack-phase damage (EX Burst triggers included).
     * When P1 is the ability user the self is P1, and vice versa.
     */
    void dealDamageToSelf(int amount);

    // ---- Dull effects (used by mass-effect; also available individually) ----

    /** Dulls P1's forward at {@code idx} and refreshes its slot. */
    void dullP1Forward(int idx);

    /** Dulls P2's forward at {@code idx} and refreshes its slot. */
    void dullP2Forward(int idx);

    /** Freezes P1's forward at {@code idx} (blue tint; skips activation next Active Phase). */
    void freezeP1Forward(int idx);

    /** Freezes P2's forward at {@code idx} (blue tint; skips activation next Active Phase). */
    void freezeP2Forward(int idx);

    // ---- Block restrictions -------------------------------------------------

    /** Prevents P1's forward at {@code idx} from being chosen as a blocker this turn. */
    void setP1ForwardCannotBlock(int idx);

    /** Prevents P2's forward at {@code idx} from being chosen as a blocker this turn. */
    void setP2ForwardCannotBlock(int idx);

    /** Finds {@code source} on own forward zone and marks it as unable to be blocked this turn. */
    void setSourceForwardCannotBeBlocked(CardData source);

    /** Marks P1's forward at {@code idx} as unable to be blocked this turn. */
    void setP1ForwardCannotBeBlocked(int idx);

    /** Marks P2's forward at {@code idx} as unable to be blocked this turn. */
    void setP2ForwardCannotBeBlocked(int idx);

    /** Marks P1's forward at {@code idx} as unable to be blocked by Forwards whose cost matches the filter. */
    void setP1ForwardCannotBeBlockedByCost(int idx, int costVal, boolean isMore);

    /** Marks P2's forward at {@code idx} as unable to be blocked by Forwards whose cost matches the filter. */
    void setP2ForwardCannotBeBlockedByCost(int idx, int costVal, boolean isMore);

    /**
     * Grants {@code source} "cannot be blocked by a Forward of cost N or more/less" until end of turn
     * (a temporarily-granted field ability), reusing the per-Forward this-turn block-restriction
     * store. Locates the source by identity on either field; no-op if it isn't on the field.
     */
    void grantSelfCannotBeBlockedByCost(CardData source, int costVal, boolean isMore);

    /**
     * Grants {@code source} "cannot be blocked by a Forward of power N or more/less" until the end
     * of the turn — Iris 12-117R.  The power twin of
     * {@link #grantSelfCannotBeBlockedByCost}: an absolute threshold read against the blocker's
     * effective power, so a pump given to the blocker in response can lift it over the line.
     */
    void grantSelfCannotBeBlockedByPower(CardData source, int powerVal, boolean isMore);

    /**
     * Grants {@code source} "[Self] cannot block." until end of turn (a temporarily-granted field
     * ability), reusing the per-Forward this-turn block-restriction set that
     * {@link #setP1ForwardCannotBlock(int)} writes. Locates the source by identity on either
     * field; no-op if it isn't on the field.
     */
    void grantSelfCannotBlockUntilEndOfTurn(CardData source);

    /**
     * Grants {@code source} "can attack {@code maxAttacks} times in the same turn" until end of turn
     * (a temporarily-granted field ability). The attack code treats the card as if it printed the
     * permission. Raising an existing allowance wins; grants do not stack into a larger total.
     */
    void grantMaxAttacksUntilEndOfTurn(CardData source, int maxAttacks);

    /**
     * Hands {@code source} the field ability {@code abilityText} until end of turn, for the
     * "[Self] gains '&lt;ability&gt;' until the end of the turn" wording. The text is stored verbatim
     * so the checks that read printed field abilities match it the same way — callers must only
     * grant text those checks actually recognise.
     */
    void grantSelfFieldAbilityUntilEndOfTurn(CardData source, String abilityText);

    /**
     * The chosen-target counterpart of {@link #grantSelfFieldAbilityUntilEndOfTurn}, for the
     * "Choose 1 Forward. It gains '&lt;ability&gt;' until the end of the turn" wording (Dio 26-075C).
     * The grant is keyed to the card instance occupying {@code target}, so it survives the
     * re-indexing that happens when another card leaves the field, and is dropped by the same
     * end-of-turn hook. A target no longer on the field is a no-op.
     */
    void grantFieldAbilityUntilEndOfTurn(ForwardTarget target, String abilityText);

    /**
     * Compels the card at {@code target} to attack once this turn if it can — the turn-scoped
     * counterpart of {@link #grantSelfMustAttackOncePerTurnPermanently}, for the wording that
     * grants the compulsion to a chosen Forward rather than to the card printing it (Azul
     * 23-077H: "it gains "This Forward must attack once per turn if possible."").
     *
     * <p>Not routed through {@link #grantFieldAbilityUntilEndOfTurn}: the must-attack rule is
     * driven off a dedicated set rather than by scanning field-ability text, so granting the
     * clause verbatim would be silently inert.
     *
     * <p>"Once per turn" is satisfied by a single attack, and the compulsion lifts at the end of
     * the turn. A target no longer on the field is a no-op.
     */
    void grantMustAttackOncePerTurnUntilEndOfTurn(ForwardTarget target);

    /**
     * Hands the card at {@code target} an auto ability that outlasts the turn — the target-facing
     * twin of {@link #grantSelfAutoAbilityPermanently}, used by the "It gains "…" (This effect does
     * not end at the end of the turn.)" choose followup (Lich 21-079R).
     *
     * <p>The granted ability belongs to the grantee's controller, so a "your turn" trigger inside
     * it fires on <em>their</em> turns, not the granting player's.
     */
    void grantAutoAbilityPermanently(ForwardTarget target, String abilityText);

    /**
     * Adds {@code amount} power and optionally grants {@code traits} to the card at {@code target}
     * for as long as it stays on the field — the target-facing twin of
     * {@link #boostSourceForwardPermanently}, used by the "It gains +N power and "…" (This effect
     * does not end at the end of the turn.)" choose followup (Ellone 27-020R).
     *
     * <p>Additive rather than idempotent: a second application stacks on the first, so a Forward
     * handed the grant twice carries twice the power. That is the whole of what a second copy of
     * the granting card buys, since the permanence means the first grant is still there.
     *
     * <p>Scoped to the Forward row. The permanent power store is only read by the Forward power
     * calculation, so a Backup or Monster target is a no-op rather than a boost that is recorded
     * and never displayed; no printed card grants this outside the Forward row.
     *
     * <p>Respects the same opponent-side power-boost suppression as the end-of-turn
     * {@link #boostTarget}, and is dropped with the other permanent grants when the card leaves
     * the field.
     */
    void boostTargetPermanently(ForwardTarget target, int amount, EnumSet<CardData.Trait> traits);

    /**
     * Grants {@code source} the auto ability written in {@code abilityText} for as long as it stays
     * on the field — the "(This effect does not end at the end of the turn.)" wording, as printed on
     * Odin (XVI) 29-118L / 24-112L's priming payoff.
     *
     * <p>{@code abilityText} is a complete "When … , …" sentence; it is parsed the same way the
     * card's own text is, so the granted trigger fires on exactly the events a printed one would.
     * Returns {@code false} when the text does not parse into any auto ability, leaving the card
     * untouched, so a caller can decline to claim an effect it could not actually apply.
     */
    boolean grantSelfAutoAbilityPermanently(CardData source, String abilityText);

    /**
     * Grants {@code source} "can attack {@code maxAttacks} times in the same turn" for as long as it
     * stays on the field — the outlasts-the-turn counterpart of
     * {@link #grantMaxAttacksUntilEndOfTurn(CardData, int)}.
     */
    void grantMaxAttacksPermanently(CardData source, int maxAttacks);

    /** Marks all opponent Forwards as unable to block Forwards with power inferior to their own this turn. */
    void setOppForwardsCannotBlockInferiorPowerThisTurn();

    /** Sets a global rule this turn: every Forward can only be blocked by a Forward with cost ≤ its own. */
    void setAllForwardsCannotBeBlockedByHigherCostThisTurn();

    /** During this turn, the power of Forwards the opponent controls cannot be increased by Summons or abilities. */
    void setOppFwdPowerBoostSuppressedThisTurn();

    /** Causes all opponent Forwards to lose all abilities until end of turn. */
    void oppForwardsLoseAllAbilitiesUntilEndOfTurn();

    /** Causes the chosen target Forward to lose all abilities until end of turn. */
    void targetLoseAllAbilitiesUntilEndOfTurn(ForwardTarget t);

    /**
     * Silences the Character at {@code t} for as long as {@code warden} stays on the field —
     * 25-035L Aerith and 20-116R Meliadoul, "As long as [Self] is on the field, it loses all its
     * abilities."
     *
     * <p>Not a duration the turn ends: the pairing is held as state and answered live, so the
     * abilities come back the moment {@code warden} leaves and there is no cleanup to schedule.
     *
     * <p>{@code warden} is matched by identity, never by name. The ability names its own printing,
     * so an opposing card with the same name is a different card and must not keep the silence
     * alive — nor end it.
     */
    void targetLoseAllAbilitiesWhileWardenOnField(ForwardTarget t, CardData warden);

    /**
     * Finds the source card on its owner's forward zone and returns it to the bottom of
     * its owner's deck.  Calls {@link #markEffectFizzled()} if the card is not found.
     */
    void putSourceToBottomOfDeck(CardData source);

    /**
     * Finds the source card on its owner's forward zone and returns it to the top of
     * its owner's deck.  Calls {@link #markEffectFizzled()} if the card is not found.
     */
    void putSourceOnTopOfDeck(CardData source);

    /**
     * Reveals the top {@code reveal} cards of the active player's deck.
     * The player plays exactly 1 Card Name {@code cardName} among them onto the field
     * (or the AI auto-selects the first matching card); the remaining cards go to the
     * bottom of the deck in any order.
     */
    void revealTopNPlayNamedOntoFieldRestBottom(int reveal, String cardName);

    /**
     * Reveals the top {@code reveal} cards of the active player's deck.
     * The player plays exactly 1 Card Name {@code cardName} of cost ≤ {@code maxCost} among them
     * onto the field (or the AI auto-selects the first matching card); the remaining cards go to
     * the bottom of the deck in any order.
     */
    void revealTopNPlayNamedWithMaxCostOntoFieldRestBottom(int reveal, String cardName, int maxCost);

    /**
     * The ability user selects 1 card type (Forward/Backup/Monster/Summon), then cards are turned
     * over one at a time from the top of their deck until a card of the selected type is revealed.
     * That card is added to their hand; all other revealed cards are shuffled and returned to the
     * bottom of their deck.
     */
    void flipUntilTypeToHandRestShuffleBottom();

    /**
     * Turns cards over one at a time from the top of the active player's deck until one of
     * {@code elem1} or {@code elem2} Element is revealed. That card is added to their hand; all
     * other revealed cards are shuffled and returned to the bottom of their deck.
     *
     * <p>Running the deck out is not a loss. A player only loses by being unable to draw, and this
     * turns cards over rather than drawing them — so an exhausted deck simply ends the reveal with
     * nothing added to hand, and an already-empty deck reveals nothing at all.
     *
     * <p>A multi-Element card counts if any of its Elements matches.
     */
    void flipUntilElementToHandRestShuffleBottom(String elem1, String elem2);

    /**
     * Reveals the top {@code reveal} cards of the active player's deck.
     * The player plays up to {@code maxPlay} cards matching {@code typeFilter}
     * ("Forward", "Backup", "Monster", or "Character") onto the field for free;
     * all remaining cards go to the bottom of the deck in any order.
     */
    void revealTopNPlayUpToTypeOntoFieldRestBottom(int reveal, int maxPlay, String typeFilter, String categoryFilter);

    default void revealTopNPlayUpToTypeOntoFieldRestBottom(int reveal, int maxPlay, String typeFilter) {
        revealTopNPlayUpToTypeOntoFieldRestBottom(reveal, maxPlay, typeFilter, null);
    }

    /**
     * Reveals the top {@code reveal} cards. The player may play up to {@code maxPlay} cards
     * matching {@code element} (if non-null), {@code typeFilter}, and cost &le; {@code maxCost}
     * (if &ge; 0) onto the field for free. The remaining cards go to the bottom of the deck in any order.
     */
    default void revealTopNPlayUpToElementTypeCostOntoFieldRestBottom(int reveal, int maxPlay, String element, String typeFilter, int maxCost) {
        revealTopNPlayUpToElementTypeCostOntoField(reveal, maxPlay, element, typeFilter, maxCost, RevealRest.BOTTOM);
    }

    /**
     * As above, but {@code rest} decides where the revealed cards that were not played go.
     * Keeping it a parameter rather than a second effect keeps the reveal, the play and the
     * disposal in one interaction, which is what the card describes.
     */
    void revealTopNPlayUpToElementTypeCostOntoField(int reveal, int maxPlay, String element,
            String typeFilter, int maxCost, RevealRest rest);

    /**
     * Reveals the top {@code reveal} cards. The player may play up to {@code maxPlay} cards
     * matching Card Name {@code cardName} OR Job {@code job}, with cost &le; {@code maxCost},
     * onto the field for free. The remaining cards go to the bottom of the deck in any order.
     */
    void revealTopNPlayUpToNamedOrJobWithMaxCostOntoFieldRestBottom(
            int reveal, int maxPlay, String cardName, String job, int maxCost);

    /**
     * Reveals the top {@code reveal} cards. The player plays exactly one of them onto the field
     * for free, satisfying <em>either</em> {@code typeFilter} at cost &le; {@code typeMaxCost}
     * (excluding Multi-Element cards when {@code excludeMultiElement}) <em>or</em> Card Name
     * {@code cardName} at cost &le; {@code nameMaxCost}. The rest go to the bottom of the deck in
     * any order.  (Syldra 29-101H.)
     *
     * <p>Unlike {@link #revealTopNPlayUpToNamedOrJobWithMaxCostOntoFieldRestBottom} the two
     * alternatives carry separate ceilings, so the named branch reaches costs the type branch
     * cannot — which is the whole point of printing it as a second alternative.
     */
    void revealTopNPlayTypeCostOrNamedCostOntoFieldRestBottom(int reveal, String typeFilter,
            int typeMaxCost, boolean excludeMultiElement, String cardName, int nameMaxCost);

    /**
     * Reveals the top {@code reveal} cards. The player may either add up to {@code handMax}
     * cards matching {@code handType} to their hand, OR play up to {@code fieldMax} cards
     * matching {@code fieldJob} (optional) and {@code fieldType} onto the field for free.
     * Only one branch fires; the remaining cards go to the bottom of the deck in any order.
     */
    void revealTopNAddTypeToHandOrPlayJobTypeOntoFieldRestBottom(
            int reveal, int handMax, String handType,
            int fieldMax, String fieldJob, String fieldType);

    /** Returns {@code true} if the specific element CP was included in the payment for the most recently cast card. */
    boolean wasElementCpPaid(String element);

    /** Requires P1's forward at {@code idx} to block this turn if it is eligible to do so. */
    void setP1ForwardMustBlock(int idx);

    /** Requires P2's forward at {@code idx} to block this turn if it is eligible to do so. */
    void setP2ForwardMustBlock(int idx);

    // ---- Return to deck -----------------------------------------------------

    /**
     * Prompts the active player to choose whether {@code cardName} should be placed on top
     * or at the bottom of the deck.
     *
     * @return {@code true} if the player chose "Top", {@code false} for "Bottom"
     */
    boolean askTopOrBottom(String cardName);

    /**
     * Shows a number-picker dialog and returns the chosen value.
     *
     * @param min    minimum selectable value (inclusive)
     * @param max    maximum selectable value (inclusive)
     * @param prompt label text displayed above the picker (e.g. "Select a number:" or
     *               "Opponent selects a number:")
     */
    int selectNumber(int min, int max, String prompt);

    /**
     * Shows a power-amount picker: values 0, 1000, 2000 … {@code maxAmount} in steps of 1000.
     * Displays a 5-digit value label with ▲ / ▼ buttons and an OK button; defaults to
     * {@code maxAmount}.  Returns the chosen amount.
     */
    int selectPowerAmount(int maxAmount, String prompt);

    /** Prompts the player to divide {@code damage} among {@code cards} (multiples of 1000); returns the chosen per-card allocation, parallel to {@code cards}. */
    List<Integer> divideDamageAmount(int damage, String prompt, List<CardData> cards);

    /** Removes P1's forward at {@code idx} from the field and adds it to P1's hand. */
    void returnP1ForwardToHand(int idx);

    /** Removes P2's forward at {@code idx} from the field and adds it to P2's hand. */
    void returnP2ForwardToHand(int idx);

    /** Removes P1's forward at {@code idx} from the field and places it at the bottom of P1's deck. */
    void returnP1ForwardToDeckBottom(int idx);

    /** Removes P2's forward at {@code idx} from the field and places it at the bottom of P2's deck. */
    void returnP2ForwardToDeckBottom(int idx);

    /**
     * Moves {@code t}, a card in a Break Zone, to the top of the ability user's deck.
     * Mirrors {@link #addTargetToHand(ForwardTarget)}: the target names the Break Zone the card
     * is taken from, while the destination deck is the ability user's, matching the card text
     * this serves ("Put it on top of <em>your</em> deck").
     *
     * <p>Callers moving more than one card must supply the targets in descending index order —
     * see {@code ActionResolver.sortedByIdxDesc} — since each removal shifts the ones after it.
     */
    void putBreakZoneTargetOnTopOfDeck(ForwardTarget t);

    /**
     * Moves {@code t}, a card in a Break Zone, to the bottom of the ability user's deck.
     * The bottom-of-deck twin of {@link #putBreakZoneTargetOnTopOfDeck(ForwardTarget)}, with the
     * same split between the zone the card is taken from and the deck it lands in.
     *
     * <p>Callers moving more than one card must supply the targets in descending index order —
     * see {@code ActionResolver.sortedByIdxDesc} — since each removal shifts the ones after it.
     */
    void putBreakZoneTargetOnBottomOfDeck(ForwardTarget t);

    /** Removes P1's forward at {@code idx} from the field and places it on top of P1's deck. */
    void returnP1ForwardToDeckTop(int idx);

    /** Removes P2's forward at {@code idx} from the field and places it on top of P2's deck. */
    void returnP2ForwardToDeckTop(int idx);

    /** Removes P1's forward at {@code idx} from the field and places it {@code position} cards from the top of P1's deck. */
    void returnP1ForwardUnderDeckTop(int idx, int position);

    /** Removes P2's forward at {@code idx} from the field and places it {@code position} cards from the top of P2's deck. */
    void returnP2ForwardUnderDeckTop(int idx, int position);

    /**
     * Searches P1's deck for a card matching the given filters, prompts the player to choose one,
     * moves it to the specified destination, then shuffles the deck.
     *
     * @param inclForwards   include Forwards as eligible search hits
     * @param inclBackups    include Backups as eligible search hits
     * @param inclMonsters   include Monsters as eligible search hits
     * @param inclSummons    include Summons as eligible search hits
     * @param costVal        CP cost filter; {@code -1} = no filter
     * @param costCmp        {@code "less"}, {@code "more"}, or {@code null} for exact
     * @param cardNameFilter exact card name to search for; {@code null} = any
     * @param jobFilter      bar-separated job name(s) to match; {@code null} = any
     * @param categoryFilter category substring to match; {@code null} = any
     * @param elementFilter  bar-separated element(s) — card must contain at least one; {@code null} = any
     * @param excludeName    exact card name to exclude from results; {@code null} = no exclusion
     * @param destination    {@code "hand"} — add to hand, {@code "field"} — play onto field,
     *                       {@code "deckTop"} — place on top of deck,
     *                       {@code "underTop"} — place second from top of deck,
     *                       {@code "breakZone"} — put into the Break Zone,
     *                       {@code "removedFromGame"} — remove it from the game
     * @param requireWarp    if {@code true}, restrict results to cards with the Warp trait
     * @return whether a card was actually found, chosen and moved. False covers all three ways a
     *         search can come up empty — blocked this turn, no match in the deck, or the player
     *         looked and picked nothing — which is what "If you do so, … If not, …" branches on
     *         (29-117H Ark). Callers that only search may ignore it.
     */
    boolean searchDeckForCard(boolean inclForwards, boolean inclBackups, boolean inclMonsters, boolean inclSummons,
            int costVal, String costCmp, String cardNameFilter, String jobFilter,
            String categoryFilter, String elementFilter, String excludeName, String excludeElem,
            String destination, int count, boolean entersDull, boolean requireWarp);

    /**
     * As {@link #searchDeckForCard}, but requiring the name <em>and</em> the job together —
     * "Card Name Cecil with Job Paladin" (20-075L, 28-032H; 4-054L Onion Knight with Job Sage).
     *
     * <p>Its own method rather than a flag on the search above, whose sixteen arguments are passed
     * at some thirty call sites for the sake of one printed phrase. The ordinary search reads two
     * filled identity filters as alternatives, which is right for the thirty-odd "Job X or Card
     * Name X" printings and wrong for this one: it would fetch any Cecil, or any Paladin.
     *
     * <p>Takes no category filter — no printing combines "with Job" with one.
     *
     * @return whether a card was found, chosen and moved, as {@link #searchDeckForCard} does
     */
    boolean searchDeckForNamedCardWithJob(boolean inclForwards, boolean inclBackups,
            boolean inclMonsters, boolean inclSummons,
            int costVal, String costCmp, String cardNameFilter, String jobFilter,
            String elementFilter, String excludeName, String excludeElem,
            String destination, int count, boolean entersDull, boolean requireWarp);

    /**
     * Searches the deck for up to 1 card with {@code jobFilter} job and up to 1 card of {@code typeName} type
     * that don't share any element, adding the selected cards to the active player's hand.
     */
    void searchDeckJobAndTypeDontShareElements(String jobFilter, String typeName);

    /**
     * Searches the deck and adds 2 cards to the active player's hand — the player chooses one of:
     * 2 {@code element} Characters, 2 Category {@code category} Characters, or 1 of each.
     * The two chosen cards must have different costs.
     */
    void searchDeckElementOrCategoryCharsDifferentCost(String element, String category);

    /**
     * Searches the deck and adds up to {@code count} {@code element} Summons to the active
     * player's hand. The chosen Summons must each have a different cost.
     */
    void searchDeckNElementSummonsDifferentCost(int count, String element);

    /**
     * Moves all cards matching {@code cardName} from the active player's Break Zone onto the
     * field, entering dull if {@code dull} is true.
     */
    void playAllByNameFromOwnBreakZoneDull(String cardName, boolean dull);

    /** Removes P1's backup at {@code idx} from the field and adds it to P1's hand. */
    void returnP1BackupToHand(int idx);

    /** Removes P2's backup at {@code idx} from the field and adds it to P2's hand. */
    void returnP2BackupToHand(int idx);

    /** Removes P1's monster at {@code idx} from the field and adds it to P1's hand. */
    void returnP1MonsterToHand(int idx);

    /** Removes P2's monster at {@code idx} from the field and adds it to P2's hand. */
    void returnP2MonsterToHand(int idx);

    // ---- Attack restrictions ------------------------------------------------

    /** Prevents P1's forward at {@code idx} from attacking this turn. */
    void setP1ForwardCannotAttack(int idx);

    /** Prevents P2's forward at {@code idx} from attacking this turn. */
    void setP2ForwardCannotAttack(int idx);

    /** Requires P1's forward at {@code idx} to attack this turn if it is eligible to do so. */
    void setP1ForwardMustAttack(int idx);

    /** Requires P2's forward at {@code idx} to attack this turn if it is eligible to do so. */
    void setP2ForwardMustAttack(int idx);

    /**
     * Prevents P1's forward at {@code idx} from attacking or blocking until the end of P1's turn
     * (survives P2's end-phase clearing, cleared at P1's end phase).
     */
    void setP1ForwardCannotAttackOrBlockPersistent(int idx);

    /**
     * Prevents P2's forward at {@code idx} from attacking or blocking until the end of P2's turn
     * (survives P1's end-phase clearing, cleared at P2's end phase).
     */
    void setP2ForwardCannotAttackOrBlockPersistent(int idx);

    /**
     * Prevents the Character {@code t} names from using action abilities for the rest of the turn
     * (14-064R Kitone).  Special abilities are a separate kind of ability under rule 6-1-1 and are
     * left alone, matching how 14-045H Sin's field-wide lock is scoped.
     *
     * <p>Keyed by the card rather than by row index, because the choose that feeds this names a
     * Character and so may land on a Backup or a Monster as readily as a Forward.
     */
    void setTargetCannotUseActionAbilitiesThisTurn(ForwardTarget t);

    /**
     * Stops the Character {@code t} names from attacking or blocking for the rest of the turn.
     *
     * <p>The zone-agnostic form of {@link #setP1ForwardCannotAttack(int)} and its siblings, for
     * effects that choose a Character rather than a Forward. A Monster, or a Backup that something
     * later turns into a Forward, can still end up in combat, and the restriction has to bind it
     * there — so it is keyed by card rather than by Forward-row index.
     */
    void setTargetCannotAttackOrBlockThisTurn(ForwardTarget t);

    // ---- Attack / block state queries ---------------------------------------

    /** Returns {@code true} if P1's forward at {@code idx} is currently declared as an attacker. */
    boolean isP1ForwardAttacking(int idx);

    /** Returns {@code true} if P2's forward at {@code idx} is currently declared as an attacker. */
    boolean isP2ForwardAttacking(int idx);

    /** Returns {@code true} if P1's forward at {@code idx} is currently declared as a blocker. */
    boolean isP1ForwardBlocking(int idx);

    /** Returns {@code true} if P2's forward at {@code idx} is currently declared as a blocker. */
    boolean isP2ForwardBlocking(int idx);

    // ---- Break / Remove-from-game (forward-specific, used by mass effect) ---

    /** Breaks P1's forward at {@code idx} (sends to P1's Break Zone). */
    void breakP1Forward(int idx);

    /** Breaks P2's forward at {@code idx} (sends to P2's Break Zone). */
    void breakP2Forward(int idx);

    /** Removes P1's forward at {@code idx} from the game permanently. */
    void removeP1ForwardFromGame(int idx);

    /** Removes P2's forward at {@code idx} from the game permanently. */
    void removeP2ForwardFromGame(int idx);

    // ---- Mass field effects -------------------------------------------------

    // ---- End-of-turn delayed effects ----------------------------------------

    /**
     * Registers {@code effect} to execute at the beginning of the end phase this turn,
     * before turn-cleanup clearing.
     */
    void addEndOfTurnEffect(Consumer<GameContext> effect);

    /**
     * Schedules {@code effect} to fire at the end of the opponent's next turn.
     * If the current context is P1, the effect fires at the end of P2's turn; if P2, at P1's.
     */
    void addEndOfOpponentTurnEffect(Consumer<GameContext> effect);

    /**
     * Presents the active player with a yes/no "you may" prompt.
     * Asks the player this effect belongs to whether they take it up, and returns their answer.
     *
     * <p>Whoever is in that seat: the local human answers in a dialog, a remote human answers on
     * their own client and the answer crosses the wire, and the AI declines. Ask before acting —
     * several of these guard events other abilities react to, and a player who declines has to be
     * seen not to have acted at all.
     */
    boolean promptYouMay(String prompt);

    /**
     * Registers {@code effect} as a temporary "when this card attacks" trigger that fires
     * once this turn (cleared at end of turn).  Used by action abilities that grant a
     * temporary attack auto-ability (e.g. "Until end of turn, X gains +N power and
     * 'When X attacks, ...'").
     */
    void addTempAttackTrigger(CardData card, Consumer<GameContext> effect);

    /**
     * Registers {@code effect} as a temporary "when this card blocks or is blocked" trigger
     * that fires once this turn (cleared at end of turn).
     */
    void addTempBlockTrigger(CardData card, Consumer<GameContext> effect);

    /**
     * Registers {@code effect} to fire when {@code card} <em>is blocked</em> this turn — the other
     * half of a granted "When [card] blocks or is blocked, …" trigger (4-142R Malboro).
     *
     * <p>Held apart from {@link #addTempBlockTrigger} because the two events are fired from
     * different places: a grant registered only as a block trigger never reaches a Forward that
     * attacked and was blocked, which is half of what Malboro's clause says.
     */
    void addTempIsBlockedTrigger(CardData card, Consumer<GameContext> effect);

    /**
     * Registers {@code effect} to execute at the start of the player's next Main Phase 1
     * (and persist until end of that turn via normal boost expiry).
     */
    void addPendingMainPhase1Effect(Consumer<GameContext> effect);

    /**
     * Returns {@code true} if the ability user controls a field card whose name
     * matches {@code cardName} (case-insensitive), checking forwards, monsters, and backups.
     */
    boolean abilityUserControlsCard(String cardName);

    // ---- Mass field effects -------------------------------------------------

    /** Action verbs for mass field effects. */
    enum MassAction { BREAK, DULL, FREEZE, DULL_AND_FREEZE, ACTIVATE, RETURN_TO_HAND }

    /**
     * Applies {@code action} to every field card that matches all filters.
     *
     * @param forwards        include Forwards in the sweep
     * @param backups         include Backups in the sweep
     * @param monsters        include Monsters in the sweep
     * @param opponentOnly    only affect P2's cards
     * @param selfOnly        only affect P1's cards
     * @param element         optional element filter; {@code null} = any
     * @param costVal         CP cost filter value; {@code -1} = no filter
     * @param costCmp         {@code "less"}, {@code "more"}, or {@code null} for exact
     * @param excludeCostVal  exact cost to exclude; {@code -1} = no exclusion
     * @param job             optional job filter (bar-separated for OR); {@code null} = any
     * @param category        optional category filter; {@code null} = any
     */
    default void applyMassFieldEffect(MassAction action,
            boolean forwards, boolean backups, boolean monsters,
            boolean opponentOnly, boolean selfOnly,
            String element, int costVal, String costCmp, int excludeCostVal,
            String job, String category) {
        applyMassFieldEffect(action, forwards, backups, monsters,
                opponentOnly, selfOnly, element, costVal, costCmp, excludeCostVal,
                job, category, java.util.EnumSet.noneOf(CardData.Trait.class));
    }

    /**
     * Same as above but only affects Forwards that have at least one trait in {@code traitFilter}
     * (backups and monsters are unaffected by the trait filter; ignored when the set is empty).
     */
    default void applyMassFieldEffect(MassAction action,
            boolean forwards, boolean backups, boolean monsters,
            boolean opponentOnly, boolean selfOnly,
            String element, int costVal, String costCmp, int excludeCostVal,
            String job, String category, java.util.EnumSet<CardData.Trait> traitFilter) {
        applyMassFieldEffect(action, forwards, backups, monsters, opponentOnly, selfOnly,
                element, costVal, costCmp, excludeCostVal, job, category, traitFilter, null);
    }

    /**
     * Same as above but also restricted to cards carrying at least one counter named
     * {@code counterFilter} ("break all the Forwards opponent controls with a Doom Counter on
     * them" — 20-057L The Goddess).  {@code null} applies no counter restriction.
     */
    void applyMassFieldEffect(MassAction action,
            boolean forwards, boolean backups, boolean monsters,
            boolean opponentOnly, boolean selfOnly,
            String element, int costVal, String costCmp, int excludeCostVal,
            String job, String category, java.util.EnumSet<CardData.Trait> traitFilter,
            String counterFilter);

    /**
     * How many dull cards the most recent {@link #applyMassFieldEffect} with
     * {@link MassAction#ACTIVATE} actually activated — cards already active are not counted,
     * because nothing happened to them.
     *
     * <p>For "activate all X. When N or more dull Characters are activated by this effect, …"
     * (19-102L Refia). Counted by the sweep rather than by a matching count taken beforehand so
     * that the payoff can never be measured against a different set of cards than the one the
     * sweep touched. Every {@code applyMassFieldEffect} call resets it, whatever its action, so it
     * only ever reports the sweep that just ran.
     */
    int lastMassActivateCount();

    /**
     * Places {@code count} counters named {@code counterName} on every Forward on the side(s) the
     * flags select — both sides when neither is set.  (20-057L The Goddess opens by putting a Doom
     * Counter on each of the opponent's Forwards.)
     */
    void placeCountersOnAllForwards(String counterName, int count,
            boolean opponentOnly, boolean selfOnly);

    /**
     * Places {@code count} counters named {@code counterName} on every card the ability user
     * controls whose Job matches {@code jobFilter} — Forwards, Backups and Monsters alike
     * (15-011L Palom, 15-119L Porom: "place 1 EXP Counter on each Job Apprentice Mage you
     * control").
     *
     * <p>The printing card is included when it carries the Job itself, which is what the text
     * says and what makes these two grow.
     */
    void placeCountersOnOwnJobCards(String counterName, int count, String jobFilter);

    /**
     * Adds {@code amount} power until end of turn to every matching field card.
     *
     * @param inclForwards  include Forwards in the sweep
     * @param inclMonsters  include Monsters in the sweep
     * @param opponentOnly  only affect opponent's cards
     * @param selfOnly      only affect own cards
     * @param element       optional element filter; {@code null} = any
     * @param costVal       CP cost filter value; {@code -1} = no filter
     * @param costCmp       {@code "less"}, {@code "more"}, or {@code null} for exact
     * @param excludeName   optional card name to exclude; {@code null} = no exclusion
     */
    void applyMassFieldPowerBoost(int amount, boolean inclForwards, boolean inclMonsters,
            boolean opponentOnly, boolean selfOnly,
            String element, int costVal, String costCmp, String category, String excludeName);

    /**
     * Adds {@code amount} power until end of turn to every Forward in the party that most
     * recently formed and attacked on the ability user's side — the "all Forwards in that
     * party" referent of a party-attack auto-ability followup (e.g. Gippal). Party members
     * no longer on the field are skipped, and the boost respects opponent power-boost
     * suppression the same way {@link #applyMassFieldPowerBoost} does.
     */
    void applyCurrentPartyForwardsPowerBoost(int amount);

    /**
     * How many Forwards of the party that most recently formed and attacked on the ability user's
     * side are still on the field — the "each attacking Forward" of 12-105L Yuna's party trigger.
     *
     * <p>Counted at resolution and filtered to the field, the same way
     * {@link #applyCurrentPartyForwardsPowerBoost} treats its party: a member that has already
     * left is no longer an attacking Forward. Only meaningful while a party attack is resolving;
     * outside one it reports 0.
     */
    int currentPartyAttackerCount();

    /**
     * Boosts all Forwards (selected by {@code opponentOnly}/{@code selfOnly}) that share
     * any element with the card named {@code cardName} on the caster's own field.
     * Fizzles if the named card is not found on the field.
     */
    void allForwardsSameElementAsNamedGainPowerUntilEOT(String cardName, int amount,
            boolean opponentOnly, boolean selfOnly);

    /**
     * Applies a power boost until end of turn to all Forwards (and Monsters when
     * {@code inclMonsters} is true) that match {@code jobFilter} OR {@code cardNameFilter}.
     * Both filters use bar-separated OR semantics (see {@link CardFilters}).
     */
    void applyMassFieldJobCardNamePowerBoost(int amount, boolean inclForwards, boolean inclMonsters,
            boolean opponentOnly, boolean selfOnly, String jobFilter, String cardNameFilter);

    /**
     * Applies a power debuff until end of turn to all opponent Forwards, where each Forward
     * loses {@code powerPerCp} × its CP cost. Forwards reduced to 0 or below are broken.
     */
    void applyOppFwdsCostScaledPowerDebuff(int powerPerCp);

    /**
     * Grants {@code traits} until end of turn to every matching Forward (and Monster when
     * {@code inclMonsters} is true) that satisfies the element, cost, and category filters.
     */
    void applyMassFieldKeywordGrant(java.util.EnumSet<CardData.Trait> traits,
            boolean inclForwards, boolean inclMonsters,
            boolean opponentOnly, boolean selfOnly,
            String element, int costVal, String costCmp, String category);

    /**
     * Grants {@code traits} until end of turn to all Forwards (and Monsters when
     * {@code inclMonsters} is true) that have the given job.
     */
    void applyMassFieldJobKeywordGrant(java.util.EnumSet<CardData.Trait> traits,
            boolean inclForwards, boolean inclMonsters,
            boolean opponentOnly, boolean selfOnly,
            String jobFilter);

    /**
     * Returns all {@link FieldAbility} instances currently active — that is, belonging to
     * any card (Forward, Backup, or Monster) on either player's field.
     *
     * <p>Because field abilities are "always on" while their owning card is on the field,
     * the caller can use this list to check whether a particular global effect (e.g.
     * "All Forwards lose Haste") is currently suppressing a game mechanic.
     */
    List<FieldAbility> getActiveFieldAbilities();

    /**
     * Registers a "during this turn, your next [filter] costs N less" modifier.
     * The modifier is consumed the first time a matching card is cast, or discarded
     * automatically at end of turn if unused.
     */
    void applyNextCastCostReduction(CostReductionModifier modifier);

    /**
     * Prompts the ability user to choose 1 Summon from their own Break Zone and adds it to their hand.
     * No-op if the Break Zone contains no Summons.
     */
    void chooseSummonFromOwnBzToHand();

    /**
     * Resolves "Choose {@code total} Summons in your Break Zone. Add 1 of them to your hand,
     * and remove the rest from the game."
     * If fewer than {@code total} Summons are available, treats all available Summons as the pool.
     */
    void chooseSummonsFromBzPickOneToHandRestRfg(int total);

    /**
     * Lets the acting player take one of their own cards named {@code cardName} out of the
     * Removed From Game zone and add it to hand.  Name matching honours "is also Card Name X in
     * all situations" aliases.  No-ops when the zone holds no such card.
     */
    void chooseNamedFromOwnRfgToHand(String cardName);

    /**
     * Resolves a "Choose 1 [Element] Summon in your Break Zone. You can cast it at any time
     * you could normally cast it this turn. The cost required to cast it is reduced by N."
     * effect: prompts the ability user to pick a matching Summon from their own Break Zone,
     * moves it to their hand, and registers a cardname-targeted {@link CostReductionModifier}
     * so the existing hand-cast path applies the discount.  No-op if no Summon matches.
     */
    void chooseSummonInBzMakeCastable(String element, int costReduction);

    /**
     * "Your opponent removes the top card of their deck from the game [face down]. You can [look at
     * it and/or] cast it as though you owned it at any time you could normally cast it. The cost for
     * casting it [is reduced by N and] can be paid using CP of any Element."
     * (Lani 12-018H, Zidane 16-048H)
     *
     * <p>Moves the top card of the opponent's deck into the opponent's removed-from-game zone and
     * registers it as castable by the ability user for the rest of the game, with the given cost
     * reduction and any-element permission.  No-op if the opponent's deck is empty.
     */
    void opponentRfpTopDeckMakeCastable(int costReduction, boolean anyElement);

    /**
     * "Choose 1 [Forward|Character] in your opponent's Break Zone. Remove it from the game.
     * [During this game,] you can cast it as though you owned it at any time you could normally
     * cast it." (Bel Dat 20-056H — Forward; Zidane 24-044H — Character)
     *
     * <p>Prompts the ability user to pick a matching card from the opponent's Break Zone, moves it
     * to the opponent's removed-from-game zone, and registers it as castable by the ability user for
     * the rest of the game.  No-op if no matching card is present.
     */
    void chooseFromOpponentBzMakeCastable(boolean inclForwards, boolean inclBackups,
            boolean inclMonsters);

    /**
     * "Choose N Summon(s) from your [and/or your opponent's] Break Zone. Remove them from the game.
     * [During this game,|this turn,] you can cast them as though you owned them ... [If you cast it,
     * remove that Summon from the game after use instead of putting it in the Break Zone.]"
     * (Shantotto 23-067R — 2, either BZ, this game; Krile 12-061L — 1, either BZ, this turn, RFG after use)
     *
     * <p>Prompts the ability user to pick {@code count} Summons from the eligible Break Zone(s),
     * moves them to their owners' removed-from-game zones, and registers each as castable.
     */
    void chooseSummonsFromBzMakeCastable(int count, boolean eitherBz, boolean expiresThisTurn,
            boolean rfgAfterUse, boolean freeCast);

    /**
     * "Choose 1 Summon of cost N or less in your Break Zone. Cast it without paying the cost.
     * Remove that Summon from the game after use instead of putting it in the Break Zone."
     *
     * <p>Prompts the ability user to pick a matching Summon from their own Break Zone, registers
     * it as castable this turn (free cost, RFG after use). The card remains in the Break Zone
     * until cast; it is removed from the game when it resolves rather than going back to the BZ.
     */
    default void chooseSummonInBzByMaxCostFreeCastRfgAfterUse(int maxCost) {
        chooseSummonInBzByMaxCostFreeCastRfgAfterUse(maxCost, java.util.Set.of());
    }

    /**
     * As above, but skipping Summons of an excluded Element — 29-033L Terra's "of cost 5 or less
     * other than Light or Dark in your Break Zone". An empty set excludes nothing.
     */
    void chooseSummonInBzByMaxCostFreeCastRfgAfterUse(int maxCost, java.util.Set<String> excludedElements);

    /**
     * Returns {@code true} if the most recent card cast by the ability user
     * was paid entirely by dulling Backups (no hand-card discards were used).
     * Used for "If the CP paid to cast X was only produced by Backups" conditionals.
     */
    boolean castWasPaidByBackupsOnly();

    /**
     * Returns {@code true} if the source card of this auto-ability entered the field
     * via its Warp ability (played from the Break Zone), not from hand.
     * Used for "If [card] enters the field due to Warp" conditionals.
     */
    boolean sourceEnteredViaWarp();

    /**
     * Marks {@code source} (a Monster on the ability user's field) as temporarily a Forward
     * with the given {@code power} until the end of the turn.  No-op if the source is not a
     * Monster currently on the field.
     */
    void makeMonsterTemporaryForward(CardData source, int power);

    /**
     * Makes the Monster at {@code t} also a Forward with {@code power} until end of turn.
     * Handles targets on either side of the field. No-op if the target is not a Monster zone.
     */
    void makeTargetTemporaryForward(ForwardTarget t, int power);

    /**
     * Makes all Monsters the ability user controls also become Forwards with {@code power}
     * until end of turn.
     */
    void makeAllMonstersTemporaryForwards(int power);

    /**
     * Grants {@code source} a temporary action ability whose sole cost is
     * "Put {@code bzCardName} into the Break Zone" until end of turn.
     */
    void grantTempBzActionAbility(CardData source, String bzCardName, String effectText);

    /**
     * Grants {@code source} a free, once-per-turn copy of {@code original} (a special ability)
     * until end of turn. All costs are removed; the ability retains its name and effect text.
     */
    void grantCopiedSpecialAbilityFreeOnce(CardData source, ActionAbility original);

    /**
     * Gogo's "Mimic": lets the acting player use one special ability that a Character has used this
     * turn — excluding any whose ability name equals {@code excludedAbilityName} — without paying its
     * cost. Where the copied effect names its original user, {@code mimicSource}'s name is substituted
     * in (e.g. Tidus's "Activate Tidus" becomes "Activate Gogo"). No-op with a log line when nothing
     * eligible has been used this turn.
     */
    void useSpecialAbilityUsedThisTurn(CardData mimicSource, String excludedAbilityName);

    /**
     * Shows the controlling player a picker for EX Burst cards in their own Damage Zone,
     * then places the chosen card's EX Burst effect on the resolution stack.
     * No-op when the Damage Zone has no cards with a parseable EX Burst effect.
     */
    void triggerExBurstFromDamageZone();

    /**
     * Swaps one card from the ability user's Damage Zone with one card from their hand.
     * <ol>
     *   <li>The ability user picks one card in their Damage Zone and moves it to their hand.</li>
     *   <li>If {@code drawCardBetween} is {@code true}, the ability user draws 1 card.</li>
     *   <li>The ability user picks one card from their hand and puts it into the Damage Zone.
     *       The replacement card's EX Burst is suppressed; other "card put into Damage Zone"
     *       auto-ability triggers still fire normally.</li>
     * </ol>
     * No-op when the Damage Zone is empty. Net Damage Zone size is unchanged.
     */
    void swapDamageZoneCardWithHandCard(boolean drawCardBetween);

    /**
     * Immediately breaks {@code source} — searches own forwards then monsters by identity
     * and calls {@link #breakTarget} on the first match.  No-op if already off the field.
     */
    void breakSourceCard(CardData source);

    /** Breaks the Forward currently blocking the source card's controller's attacker. */
    void breakBlockingForward();

    /**
     * Breaks the opponent's Forward that is blocking the named attacker.
     * No-op if no Forward is currently blocking that attacker.
     */
    void breakForwardBlockingAttacker(String attackerName);

    /**
     * Queues an end-of-turn break for {@code source} on the ability user's field.
     * Searches Forwards then Monsters; no-op if the card is no longer on the field at end of turn.
     */
    void breakSourceAtEndOfTurn(CardData source);

    /**
     * Grants the Forward at {@code t} a flat +{@code amount} bonus to outgoing combat damage
     * against Forwards for the rest of this turn.
     */
    void boostForwardOutgoingDamageThisTurn(ForwardTarget t, int amount);

    /** Grants {@code source} a flat +{@code amount} bonus to outgoing combat damage vs Forwards this turn. */
    void boostSelfOutgoingDamageThisTurn(CardData source, int amount);

    /**
     * Shows a selection dialog for the active player to choose 1 card from their Warp zone,
     * removes 1 Warp Counter from it, and enters it onto the field if the counter reaches 0.
     * Auto-chooses if only 1 card is present; no-ops if the zone is empty.
     */
    void chooseAndRemoveWarpCounter();

    /**
     * Like {@link #chooseAndRemoveWarpCounter()}, but the removal itself is optional: after the
     * card is chosen, the active player is asked "You may remove 1 Warp Counter from it." and the
     * counter is only decremented (and the "warp counter removed" trigger only fires) if accepted.
     * No-ops if the Warp zone is empty.
     */
    void chooseAndMayRemoveWarpCounter();

    /**
     * Counts the Warp Counters on the named card in the ability user's Warp zone, or 0 when no
     * card of that name is waiting there.
     *
     * <p>Warp Counters are not ordinary counters: they live on the {@code WarpEntry} in the Warp
     * zone rather than in the counter map {@link #getCounters} reads, and the card they sit on is
     * removed from the game, so it cannot be found on the field either. Gates such as 21-007L
     * Shadow's "if 1 or more Warp Counters are placed on Shadow" have to ask the zone directly.
     */
    int warpCountersOnNamed(String cardName);

    /**
     * Removes up to {@code count} Warp Counters from the named card in the ability user's Warp
     * zone, one at a time — firing the "Warp Counter removed" trigger for each and entering the
     * card onto the field if the last one comes off. No-op when no such card is in the zone, and
     * stops early rather than going negative when fewer than {@code count} counters remain.
     *
     * <p>The named counterpart of {@link #chooseAndRemoveWarpCounter()}: the card is fixed by the
     * ability text ("remove 1 Warp Counter from Shadow"), so there is nothing to choose.
     */
    void removeWarpCountersFromNamed(String cardName, int count);

    /**
     * Shows a modal dialog listing every distinct Job name in the card database and returns
     * the one the player selected, or {@code null} if the dialog was cancelled.
     */
    String selectJobFromDatabase();

    /**
     * Shows a combined dialog for the player to name 1 Element and 1 Job simultaneously.
     * The OK button is disabled until both dropdowns have a valid selection.
     * The AI picks randomly for non-interactive contexts.
     *
     * @param prompt   text shown above the pickers
     * @param excluded element names to hide from the element picker
     * @return {@code {element, job}} array, or {@code null} if cancelled
     */
    String[] selectElementAndJob(String prompt, java.util.Set<String> excluded);

    /** Like {@link #selectElementAndJob(String, java.util.Set)} with no exclusions. */
    String[] selectElementAndJob(String prompt);

    /**
     * Permanently adds {@code job} as an additional job to the named card on the field.
     * The extra job persists across turns and is considered in all job-filter checks.
     */
    void addCardJobPermanently(String cardName, String job);

    /**
     * Shows a modal dialog for the ability user to name either one Job or one Element.
     * Returns {@code {"job", value}} or {@code {"element", value}}, or {@code null} if cancelled.
     */
    String[] selectJobOrElement(String prompt);

    /**
     * Shows a modal dialog for the ability user to name either one Job or one Category.
     * Returns {@code {"job", value}} or {@code {"category", value}}, or {@code null} if cancelled.
     */
    String[] selectJobOrCategory(String prompt);

    /**
     * Reveals the top {@code reveal} cards of the active player's deck.
     * The player may add up to {@code maxAdd} Characters matching {@code jobFilter},
     * {@code categoryFilter}, {@code cardNameFilter}, {@code typeFilter}, or {@code orElementFilter}
     * (treated as a disjunction across the non-null filters) to their hand; the rest go to the
     * bottom of the deck in any order.  Pass {@code null} for unused filters.
     * {@code typeFilter} may be {@code "Monster"}, {@code "Forward"}, {@code "Backup"},
     * or {@code "Character"} (matches any character type).
     * {@code maxCost} restricts eligible cards to those with cost ≤ that value; {@code -1} = no restriction.
     * <p>
     * Note the two element parameters differ: {@code elementFilter} is an AND-gate — an eligible
     * card must contain that element <em>in addition</em> to matching the disjunction (e.g. "Fire
     * Forward"). {@code orElementFilter} is instead one of the disjunction's terms — a card is
     * eligible if it contains that element <em>or</em> matches any other filter (e.g. "Water or
     * Category X card"). Both are bar-separated; pass {@code null} to disable.
     */
    void revealTopAddUpToMatchingRestBottom(int reveal, int maxAdd,
            String jobFilter, String categoryFilter, String cardNameFilter, String typeFilter, int maxCost,
            String elementFilter, String orElementFilter);

    /** Convenience overload without the disjunct element filter (passes {@code null}). */
    default void revealTopAddUpToMatchingRestBottom(int reveal, int maxAdd,
            String jobFilter, String categoryFilter, String cardNameFilter, String typeFilter, int maxCost,
            String elementFilter) {
        revealTopAddUpToMatchingRestBottom(reveal, maxAdd, jobFilter, categoryFilter, cardNameFilter, typeFilter, maxCost, elementFilter, null);
    }

    /** Convenience overload without element filter (passes {@code null}). */
    default void revealTopAddUpToMatchingRestBottom(int reveal, int maxAdd,
            String jobFilter, String categoryFilter, String cardNameFilter, String typeFilter, int maxCost) {
        revealTopAddUpToMatchingRestBottom(reveal, maxAdd, jobFilter, categoryFilter, cardNameFilter, typeFilter, maxCost, null, null);
    }

    /** Convenience overload with no cost or element restriction. */
    default void revealTopAddUpToMatchingRestBottom(int reveal, int maxAdd,
            String jobFilter, String categoryFilter, String cardNameFilter, String typeFilter) {
        revealTopAddUpToMatchingRestBottom(reveal, maxAdd, jobFilter, categoryFilter, cardNameFilter, typeFilter, -1, null, null);
    }

    /**
     * "Reveal the top {@code reveal} cards of your deck. Remove 1 [Category {@code categoryFilter}]
     * card among them from the game and return the other cards to the bottom of your deck in any
     * order. You can cast it at any time you could normally cast it this turn." — Snow 18-109C, and
     * Warrior of Light 20-004C with {@code categoryFilter} null.
     *
     * <p>The removal and the casting permission are one effect, not two: the card is removed so it
     * can be cast, and the permission is what the sentence is for. It is registered as a
     * {@link PlayableEntry} over the removed-from-game zone that expires at end of turn — so an
     * uncast card simply stops being offered and stays where it is, removed from the game.
     *
     * @param categoryFilter category a revealed card must match to be removable, or {@code null}
     *                       to offer every revealed card.
     * @param costReduction  CP knocked off the removed card's cost when it is cast, 0 for none —
     *                       Helena Leonis 22-052H discounts hers by 2.
     */
    void revealTopNRemoveOneFromGameCastableThisTurnRestBottom(int reveal, String categoryFilter,
            int costReduction);

    /**
     * Reveals the top {@code reveal} cards of the player's deck.  The player may add up to
     * {@code maxAdd} of them to hand, excluding any card whose name equals {@code excludeName}.
     * All remaining revealed cards go to the Break Zone.
     */
    void revealTopAddUpToExcludingNameRestBz(int reveal, int maxAdd, String excludeName);

    /**
     * Grants all Forwards controlled by the acting player the given {@code job} until end of turn.
     */
    void grantAllControlledForwardsJobUntilEOT(String job);

    /**
     * Grants all Forwards controlled by the acting player the given {@code element} until end of
     * turn. Temporarily overrides each Forward's element; reverted at end of turn.
     */
    void grantAllControlledForwardsElementUntilEOT(String element);

    /**
     * Grants the Forward at {@code t} the given {@code job} until the end of the turn.
     * No-op for Backup and Monster targets.
     */
    void grantJobUntilEndOfTurn(ForwardTarget t, String job);

    /**
     * Changes {@code source}'s element to {@code element} and grants it Job {@code job}
     * until the end of the turn. {@code source} must currently be in a Forward slot.
     * Both changes are reverted at end of turn.
     */
    void changeSourceCardElementAndJobUntilEOT(CardData source, String element, String job);

    /**
     * Changes {@code source}'s element to {@code element} until the end of the turn.
     * {@code source} must currently be in a Forward slot. Reverted at end of turn.
     */
    void changeSourceCardElementUntilEOT(CardData source, String element);

    /**
     * Grants all Forwards the controller controls the ability to form a party with Forwards of
     * any Element until the end of the turn.
     */
    void grantForwardsPartyAnyElementThisTurn();

    /**
     * Doubles the incoming damage taken by the Forward at {@code t} for the rest of this turn.
     * Stacks multiplicatively if called multiple times on the same target.
     */
    void doubleForwardIncomingDamageThisTurn(ForwardTarget t);

    /**
     * Marks the Forward at {@code t} so that the next damage it deals to a Forward this turn
     * is doubled.  The effect is consumed on the first damage event.
     */
    void doubleForwardNextOutgoingDamage(ForwardTarget t);

    /**
     * Doubles all outgoing ability damage dealt by the active player for the rest of this turn.
     * Stacks multiplicatively if called more than once.
     */
    void doublePlayerAbilityOutgoingDamage();

    /** Returns {@code true} if the summon currently resolving was cast with its optional extra cost paid. */
    boolean wasExtraCostPaid();

    /**
     * Returns the power of the Forward removed from the Break Zone as the extra cost.
     * Used by Titan's "deal it damage equal to the power of the Forward removed by the extra cost."
     * Returns {@code 0} if the extra cost was not paid or no Forward was removed.
     */
    int extraCostRemovedCardPower();

    /**
     * The power of the Forward revealed from hand to pay this ability's {@link RevealCost}, or
     * {@code 0} when none was — Rinoa 18-097R's Angelo Cannon deals damage equal to it.
     */
    int revealedForwardPower();

    /**
     * Returns the cost of the card discarded from hand as the extra cost (Fenrir).
     * Returns {@code 0} if the extra cost was not paid via a hand discard.
     */
    int extraCostDiscardedCardCost();
}
