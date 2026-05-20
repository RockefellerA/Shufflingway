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
            String jobFilter, String cardNameFilter, String categoryFilter, String excludeName, boolean summons);

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
            String jobFilter, String cardNameFilter, String categoryFilter, String excludeName, boolean summons);

    /**
     * Presents the ability user with Summons currently on the stack and cancels
     * the one they choose, preventing it from resolving.
     */
    void cancelSummonOnStack();

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
            String excludeName, boolean entersDull);

    // ---- Zone-dispatch single-target effects --------------------------------

    /**
     * Applies {@code amount} damage to the target.
     * Only meaningful for Forwards and Monsters (Backups are ignored).
     */
    void damageTarget(ForwardTarget t, int amount);

    /**
     * Deals {@code amount} damage to the first P1 or P2 Forward whose name matches
     * {@code cardName} (case-insensitive). Logs a warning if no matching card is found.
     */
    void damageFieldForwardByName(String cardName, int amount);

    /** Grants the ability user {@code count} Crystals. */
    void gainCrystal(int count);

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
     * Sets the target's effective power to exactly {@code power} until the end of the turn,
     * overriding any existing temporary boosts or reductions.
     */
    void setTargetPower(ForwardTarget t, int power);

    /** Places {@code count} counters named {@code counterName} on {@code card}. */
    void placeCounters(CardData card, String counterName, int count);

    /** Returns the number of counters named {@code counterName} currently on {@code card}. */
    int getCounters(CardData card, String counterName);

    /**
     * Reveals the top card of the ability owner's deck in a dialog.  The player may then
     * choose to put it into the Break Zone; if they decline the card stays on top of the deck.
     */
    void lookAtTopDeckAndOptionallyBreak();

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

    /**
     * Applies "cannot be chosen" protection to all Forwards matching {@code job} that the active
     * player controls, optionally excluding the card named {@code excludeName}.
     */
    void shieldJobForwardsCannotBeChosen(String job, String excludeName, boolean bySummons, boolean byAbilities);

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

    /** Returns the number of cards in P1's damage zone. */
    int p1DamageCount();

    /** Returns the number of cards in the opponent's hand. */
    int opponentHandSize();

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
     * Counts cards in P1's Break Zone matching all supplied filters.
     *
     * @param cardNameFilter exact card name; {@code null} = any
     * @param jobFilter      bar-separated job name(s); {@code null} = any
     */
    int countP1BreakZoneCards(String cardNameFilter, String jobFilter);

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
     * Grants the ability user one additional turn immediately after the current turn ends.
     * At the end of that extra turn, the ability user loses the game.
     */
    void takeExtraTurnThenLose();

    /**
     * Draws {@code count} cards from the top of the ability user's deck into their hand.
     */
    void drawCards(int count);

    /**
     * Prompts the ability user to discard {@code count} cards from their hand to
     * their Break Zone.  No CP is generated.
     * When P1 is the ability user, a selection dialog is shown.
     * When P2 is the ability user, the AI discards automatically (worst cards first).
     */
    void selfDiscard(int count);

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
            String categoryFilter, String elementFilter, String excludeName, String excludeElem, String destination);

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
     * Returns {@code true} if the ability user controls a field card whose name
     * matches {@code cardName} (case-insensitive), checking forwards, monsters, and backups.
     */
    boolean abilityUserControlsCard(String cardName);

    // ---- Mass field effects -------------------------------------------------

    /** Action verbs for mass field effects. */
    enum MassAction { BREAK, DULL, FREEZE, DULL_AND_FREEZE, ACTIVATE }

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
     */
    void applyMassFieldEffect(MassAction action,
            boolean forwards, boolean backups, boolean monsters,
            boolean opponentOnly, boolean selfOnly,
            String element, int costVal, String costCmp, int excludeCostVal);

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
            String element, int costVal, String costCmp);

    /**
     * Returns all {@link FieldAbility} instances currently active — that is, belonging to
     * any card (Forward, Backup, or Monster) on either player's field.
     *
     * <p>Because field abilities are "always on" while their owning card is on the field,
     * the caller can use this list to check whether a particular global effect (e.g.
     * "All Forwards lose Haste") is currently suppressing a game mechanic.
     */
    List<FieldAbility> getActiveFieldAbilities();
}
