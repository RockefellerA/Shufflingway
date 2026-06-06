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
            boolean opponentZone, String condition, String element, int costVal, String costCmp,
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
     * Forces {@code t} directly into the Break Zone, bypassing any
     * "cannot be broken" protection that {@link #breakTarget} would respect.
     */
    void forceTargetToBreakZone(ForwardTarget t);

    /**
     * Moves the top {@code count} cards from the opponent's main deck into their Break Zone.
     */
    void opponentMillCards(int count);

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
            boolean suppressAutoAbility);

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

    /** Grants the ability user {@code count} Crystals. */
    void gainCrystal(int count);

    /** Returns the number of Crystal tokens (《C》) currently held by the ability user. */
    int crystalCount();

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

    /** Sets the target back to Active state and refreshes its slot. */
    void activateTarget(ForwardTarget t);

    /** Dulls the target and refreshes its slot. */
    void dullTarget(ForwardTarget t);

    /** Freezes the target (skips activation next Active Phase) and refreshes its slot. */
    void freezeTarget(ForwardTarget t);

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

    /** Shuffles the active player's deck. */
    void shuffleDeck();

    /**
     * Plays the target (chosen from a Break Zone) onto the field without
     * paying costs.  Forwards go to the forward zone, Backups to a backup
     * slot, Monsters to the monster zone.
     */
    void playTargetOntoField(ForwardTarget t);

    /** Moves the target (chosen from a Break Zone) to P1's hand. */
    void addTargetToHand(ForwardTarget t);

    /**
     * Adds {@code amount} power and optionally grants {@code traits} to the target
     * until the end of the turn.
     */
    void boostTarget(ForwardTarget t, int amount, EnumSet<CardData.Trait> traits);

    /**
     * Finds {@code source} on P1's forward zone and adds {@code amount} power and
     * optionally grants {@code traits} to it until the end of the turn.
     * No-op if the source card is not found on the field.
     */
    void boostSourceForward(CardData source, int amount, EnumSet<CardData.Trait> traits);

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

    /**
     * General "look at the top N cards" effect.  The {@link LookConfig} specifies how
     * many cards to look at and what the player may do with them afterward.
     */
    void lookAtTopDeck(LookConfig config);

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

    /** Returns {@code true} if the active player received at least one point of game damage this turn. */
    boolean selfReceivedDamageThisTurn();

    /** Returns {@code true} if the active player has at least one Summon in their Break Zone. */
    boolean selfHasSummonInBreakZone();

    /** Returns the number of cards in the opponent's damage zone. */
    int opponentDamageCount();

    /** Returns the number of cards the active player has cast from hand this turn. */
    int selfCardsCastThisTurn();

    /** Returns {@code true} if the active player has cast a Summon this turn. */
    boolean selfSummonCastThisTurn();

    /** Returns the number of Forwards the active player controls. */
    int selfForwardCount();

    /** Returns the number of Forwards the opponent controls. */
    int opponentForwardCount();

    /** Returns {@code true} if this ability is resolving as the result of an EX Burst. */
    boolean isExBurst();

    /** Returns the number of cards in P1's damage zone. */
    int p1DamageCount();

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

    // ---- Computed-damage queries -----------------------------------------------

    /** Returns the highest effective power among all P1 Forwards on the field; {@code 0} if none. */
    int highestP1ForwardPower();

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
     * Reveals the ability-user's opponent's hand, then lets the ability user select
     * {@code count} cards from it to remove from the game permanently.
     * When P1 is the ability user, P1 is shown a dialog with P2's hand.
     * When P2 is the ability user, the AI picks the highest-value cards from P1's hand.
     */
    void selectFromOpponentHandAndRfp(int count);

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
     *                       {@code "underTop"} — place second from top of deck
     */
    void searchDeckForCard(boolean inclForwards, boolean inclBackups, boolean inclMonsters, boolean inclSummons,
            int costVal, String costCmp, String cardNameFilter, String jobFilter,
            String categoryFilter, String elementFilter, String excludeName, String excludeElem,
            String destination, int count, boolean entersDull);

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
     */
    void applyMassFieldPowerBoost(int amount, boolean inclForwards, boolean inclMonsters,
            boolean opponentOnly, boolean selfOnly,
            String element, int costVal, String costCmp, String category);

    /**
     * Grants {@code traits} until end of turn to every matching Forward (and Monster when
     * {@code inclMonsters} is true) that satisfies the element, cost, and category filters.
     */
    void applyMassFieldKeywordGrant(java.util.EnumSet<CardData.Trait> traits,
            boolean inclForwards, boolean inclMonsters,
            boolean opponentOnly, boolean selfOnly,
            String element, int costVal, String costCmp, String category);

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
     * Returns {@code true} if the most recent card cast by the ability user
     * was paid entirely by dulling Backups (no hand-card discards were used).
     * Used for "If the CP paid to cast X was only produced by Backups" conditionals.
     */
    boolean castWasPaidByBackupsOnly();

    /**
     * Marks {@code source} (a Monster on the ability user's field) as temporarily a Forward
     * with the given {@code power} until the end of the turn.  No-op if the source is not a
     * Monster currently on the field.
     */
    void makeMonsterTemporaryForward(CardData source, int power);

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
     * @param prompt text shown above the pickers
     * @return {@code {element, job}} array, or {@code null} if cancelled
     */
    String[] selectElementAndJob(String prompt);

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
}
