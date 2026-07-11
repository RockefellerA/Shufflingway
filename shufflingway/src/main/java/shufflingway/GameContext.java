package shufflingway;

import java.util.EnumSet;
import java.util.List;
import java.util.function.Consumer;

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

    /** Accumulated damage on P1's forward at {@code idx}. */
    int p1ForwardCurrentDamage(int idx);

    /** Field state (ACTIVE / DULL / BRAVE_ATTACKED) of P1's forward at {@code idx}. */
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
     * Filters the stack to entries matching {@code filter} and presents a selection dialog.
     * The chosen ability's target is redirected — logged as an instruction for the player to
     * manually apply, since the app does not store per-ability targets in the stack entry.
     * The ability itself is NOT cancelled; it will still resolve.
     */
    void redirectAbilityTarget(java.util.function.Predicate<StackEntry> filter, String prompt);

    /**
     * Forces {@code t} directly into the Break Zone, bypassing any
     * "cannot be broken" protection that {@link #breakTarget} would respect.
     */
    void forceTargetToBreakZone(ForwardTarget t);

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
    void revealEachPlayerTopDeckMayPlay(java.util.function.Predicate<CardData> eligibleCondition);

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
     * P1 picks via dialog; P2 (AI) picks automatically (highest-cost first).
     */
    void eachPlayerSalvageFromBreakZone(int count);

    /**
     * The ability user selects {@code count} Character(s) from their own Break Zone and adds them to their hand.
     * P1 picks via dialog; P2 (AI) picks automatically (highest-cost first).
     *
     * @param fwds include Forwards as eligible targets
     * @param bkps include Backups as eligible targets
     * @param mons include Monsters as eligible targets
     */
    void salvageCharacterFromOwnBreakZone(int count, boolean fwds, boolean bkps, boolean mons);

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
    void removeTopCardsOfDeckFromGame(int count);

    /**
     * Removes the top card of the active player's deck from the game and returns its CP cost.
     * Returns 0 if the deck is empty.
     */
    int removeTopCardOfDeckFromGameAndGetCost();

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
     */
    void playTargetOntoField(ForwardTarget t);

    /**
     * Like {@link #playTargetOntoField} but the card enters the field in a dulled state.
     * Only meaningful for Forwards; Backups and Monsters enter normally.
     */
    void playTargetOntoFieldDull(ForwardTarget t);

    /** Moves the target (chosen from a Break Zone) to P1's hand. */
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
     * Sets the source card's power to {@code power} until the end of the turn by zeroing
     * existing boosts/reductions and applying the required offset from the card's base power.
     * No-op if the source card is not found on the field.
     */
    void setSourceForwardPower(CardData source, int power);

    /**
     * Finds {@code source} on the field and doubles its power (and optionally grants
     * {@code traits}) until end of turn by boosting it by its current effective power.
     */
    void doubleSourceForwardPower(CardData source, java.util.EnumSet<CardData.Trait> traits);

    /**
     * Sets the target's effective power to exactly {@code power} until the end of the turn,
     * overriding any existing temporary boosts or reductions.
     */
    void setTargetPower(ForwardTarget t, int power);

    /** Places {@code count} counters named {@code counterName} on {@code card}. */
    void placeCounters(CardData card, String counterName, int count);

    /** Returns the number of counters named {@code counterName} currently on {@code card}. */
    int getCounters(CardData card, String counterName);

    /** Removes up to {@code count} counters named {@code counterName} from {@code card} (no-op if fewer are present). */
    void removeCounters(CardData card, String counterName, int count);

    /**
     * Selects and removes one counter from the character at {@code t}.
     * If the card has no counters the effect fizzles.
     * If it has exactly one counter type the counter is removed silently.
     * If it has multiple counter types the active player is prompted to choose one.
     */
    void removeOneCounterFromTarget(ForwardTarget t);

    /**
     * General "look at the top N cards" effect.  The {@link LookConfig} specifies how
     * many cards to look at and what the player may do with them afterward.
     */
    void lookAtTopDeck(LookConfig config);

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

    /** The next damage dealt to {@code from} is received by {@code to} instead (consumed on first hit). */
    void redirectNextIncomingDamage(ForwardTarget from, ForwardTarget to);

    /** Next damage received by target is reduced by {@code reduction} (consumed on first hit). */
    void shieldNextIncomingDamageReduction(ForwardTarget t, int reduction);

    /** Reduces the next damage dealt to {@code t} by abilities or Summons by {@code reduction}. */
    void shieldNextAbilityIncomingDamageReduction(ForwardTarget t, int reduction);

    /** All damage received by target is increased by {@code amount} until end of turn. */
    void debuffIncomingDamageIncrease(ForwardTarget t, int amount);

    /** Damage from the opponent's Summons or abilities to target becomes 0 until end of turn. */
    void shieldAbilityDamage(ForwardTarget t);

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
     * Returns the name of the card most recently discarded by a self-discard effect in the
     * current ability chain, or {@code null} when no card has been discarded yet.
     */
    String lastDiscardedCardName();

    /**
     * Returns the primary element of the card most recently discarded as a cost payment,
     * or {@code null} if no card has been discarded as a cost yet in the current ability chain.
     */
    String lastDiscardedCostCardElement();

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
     * Prompts the ability user to optionally discard exactly 1 card of the given type
     * (e.g. "Summon") from their hand to their Break Zone. No CP is generated.
     * The player may choose to pass (discard nothing). Sets effectMadeProgress only when
     * a card is actually discarded.
     * When P2 is the ability user the AI always passes (never voluntarily discards).
     */
    void selfDiscardByType(String cardType);

    /**
     * Prompts the ability user to discard 1 card with Job {@code jobName} from their hand.
     * Sets effectMadeProgress only when a card is actually discarded.
     * When P2 is the ability user the AI discards the worst eligible card automatically.
     */
    void selfDiscardByJob(String jobName);

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
     * Offers the player the option to discard a card named {@code cardName} from hand.
     * Skips the offer silently if no such card is in hand.
     * Calls {@code ifDiscarded} if the player accepts and the card is discarded.
     */
    void mayDiscardCardNameFromHand(String cardName, java.util.function.Consumer<GameContext> ifDiscarded);

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
     * Finds the source card on its owner's forward zone and returns it to the bottom of
     * its owner's deck.  Calls {@link #markEffectFizzled()} if the card is not found.
     */
    void putSourceToBottomOfDeck(CardData source);

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
    void revealTopNPlayUpToElementTypeCostOntoFieldRestBottom(int reveal, int maxPlay, String element, String typeFilter, int maxCost);

    /**
     * Reveals the top {@code reveal} cards. The player may play up to {@code maxPlay} cards
     * matching Card Name {@code cardName} OR Job {@code job}, with cost &le; {@code maxCost},
     * onto the field for free. The remaining cards go to the bottom of the deck in any order.
     */
    void revealTopNPlayUpToNamedOrJobWithMaxCostOntoFieldRestBottom(
            int reveal, int maxPlay, String cardName, String job, int maxCost);

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
     *                       {@code "breakZone"} — put into the Break Zone
     */
    void searchDeckForCard(boolean inclForwards, boolean inclBackups, boolean inclMonsters, boolean inclSummons,
            int costVal, String costCmp, String cardNameFilter, String jobFilter,
            String categoryFilter, String elementFilter, String excludeName, String excludeElem,
            String destination, int count, boolean entersDull);

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
     * For P1 (human), shows a dialog and returns true if they accept.
     * For P2 (CPU), always returns false (declines).
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
    void applyMassFieldEffect(MassAction action,
            boolean forwards, boolean backups, boolean monsters,
            boolean opponentOnly, boolean selfOnly,
            String element, int costVal, String costCmp, int excludeCostVal,
            String job, String category, java.util.EnumSet<CardData.Trait> traitFilter);

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
    void chooseSummonInBzByMaxCostFreeCastRfgAfterUse(int maxCost);

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
     * {@code categoryFilter}, {@code cardNameFilter}, or {@code typeFilter} (treated as a
     * disjunction across the non-null filters) to their hand; the rest go to the bottom of
     * the deck in any order.  Pass {@code null} for unused filters.
     * {@code typeFilter} may be {@code "Monster"}, {@code "Forward"}, {@code "Backup"},
     * or {@code "Character"} (matches any character type).
     * {@code maxCost} restricts eligible cards to those with cost ≤ that value; {@code -1} = no restriction.
     */
    void revealTopAddUpToMatchingRestBottom(int reveal, int maxAdd,
            String jobFilter, String categoryFilter, String cardNameFilter, String typeFilter, int maxCost,
            String elementFilter);

    /** Convenience overload without element filter (passes {@code null}). */
    default void revealTopAddUpToMatchingRestBottom(int reveal, int maxAdd,
            String jobFilter, String categoryFilter, String cardNameFilter, String typeFilter, int maxCost) {
        revealTopAddUpToMatchingRestBottom(reveal, maxAdd, jobFilter, categoryFilter, cardNameFilter, typeFilter, maxCost, null);
    }

    /** Convenience overload with no cost or element restriction. */
    default void revealTopAddUpToMatchingRestBottom(int reveal, int maxAdd,
            String jobFilter, String categoryFilter, String cardNameFilter, String typeFilter) {
        revealTopAddUpToMatchingRestBottom(reveal, maxAdd, jobFilter, categoryFilter, cardNameFilter, typeFilter, -1, null);
    }

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
     * Returns the cost of the card discarded from hand as the extra cost (Fenrir).
     * Returns {@code 0} if the extra cost was not paid via a hand discard.
     */
    int extraCostDiscardedCardCost();
}
