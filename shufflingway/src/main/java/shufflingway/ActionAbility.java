package shufflingway;

import java.util.List;

/**
 * A single parsed Action Ability or Special Ability from a card's text_en.
 *
 * <p>Action abilities follow the format:
 * <pre>  CostTokens: EffectText</pre>
 *
 * <p>Special abilities add a named header and additionally require the player
 * to discard a card with the same name as the activating card:
 * <pre>  [[s]]Name[[/]] CostTokens: EffectText</pre>
 *
 * <h3>Cost token semantics</h3>
 * <ul>
 *   <li>{@link #cpCost} — CP elements the player must pay (element names or {@code ""}
 *       for generic CP).  Excludes {@code Dull} and {@code S} tokens.</li>
 *   <li>{@link #requiresDull} — {@code true} when {@code 《Dull》} appears in the cost
 *       (the card itself must be dulled as part of payment).</li>
 *   <li>{@link #isSpecial} — {@code true} when {@code [[s]]…[[/]]} markup is present
 *       or {@code 《S》} appears in the cost.  Requires discarding a same-name card
 *       from hand in addition to the other costs.</li>
 *   <li>{@link #crystalCost} — number of Crystals the player must spend ({@code 《C》} tokens).</li>
 *   <li>{@link #selfMillCost} — {@code > 0}: put the top N cards of the deck into the Break Zone as cost.</li>
 *   <li>{@link #hasXCost} — {@code true} when {@code 《X》} appears in the cost.  The player may
 *       pay any amount of CP beyond the fixed requirements; that surplus becomes the value of X
 *       passed to effect resolution.</li>
 *   <li>{@link #breakZoneCosts} — one entry per "put X into the Break Zone" cost item;
 *       empty when no such cost is present.</li>
 *   <li>{@link #discardCosts} — one entry per "discard X from hand" cost item;
 *       empty when no such cost is present.</li>
 *   <li>{@link #removeFromGameCosts} — one entry per "remove X from the game" cost item;
 *       empty when no such cost is present.</li>
 *   <li>{@link #dullForwardCosts} — one entry per "Dull N [active] [elem] Forward(s)" cost item;
 *       empty when no such cost is present.</li>
 * </ul>
 *
 * <p>{@link #effectText} is stored as a raw string for now and will be parsed
 * into discrete effects in a future iteration.
 */
public record ActionAbility(
        String                  abilityName,          // "" for regular abilities; named (e.g. "Mug") for specials
        boolean                 requiresDull,          // 《Dull》 present in cost
        boolean                 isSpecial,             // [[s]]…[[/]] or 《S》 present — requires same-name hand discard
        int                     crystalCost,           // number of Crystals the player must spend (《C》 tokens)
        int                     selfMillCost,          // > 0: put top N cards of deck into Break Zone as cost
        boolean                 hasXCost,              // 《X》 present — surplus CP beyond fixed costs becomes X
        List<String>            cpCost,                // CP cost elements (element names or "" for generic)
        List<BreakZoneCost>     breakZoneCosts,        // "put X into the Break Zone" costs (may be empty)
        List<DiscardCost>       discardCosts,          // "discard X" hand-card costs (may be empty)
        List<RemoveFromGameCost> removeFromGameCosts,  // "remove X from the game" costs (may be empty)
        List<ReturnToHandCost>   returnToHandCosts,    // "return X to its owner's hand" costs (may be empty)
        List<CounterCost>        counterCosts,         // "remove N [Name] Counter(s) from [CardName]" costs
        List<DullForwardCost>    dullForwardCosts,     // "Dull N [active] [elem] Forward(s)" costs (may be empty)
        boolean                 yourTurnOnly,          // "can only use this ability during your turn" restriction
        boolean                 opponentTurnOnly,      // "can only use this ability during your opponent's turn" restriction
        boolean                 oncePerTurn,           // "can only use this ability once per turn" restriction
        boolean                 mainPhaseOnly,         // "can only use this ability during your Main Phase" restriction
        String                  whileCardAttacking,    // non-null = named card must be in P1's attack selection
        String                  whileCardBlocking,     // non-null = named P1 forward must be the declared blocker
        boolean                 whilePartyAttacking,   // true = P1's attack selection must have ≥ 2 forwards
        boolean                 whileCardInHand,       // true = ability can only be activated while this card is in hand
        boolean                 hasBlockingTargetEffect, // true = effect targets a Forward blocking a named card/job
        String                  effectText,            // raw effect text — future work will parse this further
        int                     damageThreshold,       // > 0: only usable when controlling player has ≥ this many damage counters
        ControlCondition        controlCondition,      // null = no "if you control X" restriction; non-null = must be satisfied
        String                  cpBackupElement,       // null = no restriction; "" = any Backup CP; "Wind" etc. = specific element
        boolean                 sourceInBattle,        // true = source card must be in Battle (attacking or blocking) to activate
        boolean                 requiresOppDiscardedThisTurn, // true = opponent must have discarded from hand via P1's Summons/abilities this turn
        boolean                 requiresCastSummonThisTurn,   // true = controller must have cast a Summon this turn
        String                  requiresElementForwardEnteredThisTurn, // null = no restriction; else = element (lower-case) that must have entered your field this turn
        String                  requiresCardNameEnteredThisTurn,      // null = no restriction; else = card name that must have entered your field this turn
        String                  breakZoneOnly,                // null = usable from field; non-null = card name that must be in the Break Zone (can only activate from BZ)
        boolean                 requiresOpponentEmptyHand,    // true = can only use when opponent has no cards in hand
        String                  requiresNamedCardTookDamageThisTurn, // null = no restriction; non-null = card name that must have received damage this turn
        boolean                 requiresSelfReceivedDamageThisTurn,  // true = controller must have received ≥1 point of game damage this turn
        String                  ownBreakZoneNameRequired,     // null = no restriction; non-null = named card must be in the controller's own BZ (ability used from the field)
        String                  counterScaleName              // null = normal; non-null = counter type name (e.g. "Monster") whose count on the source card becomes xValue at activation, captured before BZ cost is paid
) {
    public ActionAbility {
        cpCost            = List.copyOf(cpCost);
        breakZoneCosts    = List.copyOf(breakZoneCosts);
        discardCosts      = List.copyOf(discardCosts);
        removeFromGameCosts = List.copyOf(removeFromGameCosts);
        returnToHandCosts   = List.copyOf(returnToHandCosts);
        counterCosts        = List.copyOf(counterCosts);
        dullForwardCosts    = List.copyOf(dullForwardCosts);
    }

    /** Creates an action ability whose sole cost is "Put {@code bzCardName} into the Break Zone." */
    public static ActionAbility makeBzCostTempAbility(String bzCardName, String effectText) {
        return new ActionAbility(
            "", false, false, 0, 0, false,
            List.of(),
            List.of(new BreakZoneCost(bzCardName, 1, "")),
            List.of(), List.of(), List.of(), List.of(), List.of(),
            true, false, false, false,
            null, null, false, false, false,
            effectText,
            0, null, null, false, false, false, null, null, null, false, null, false, null, null
        );
    }
}
