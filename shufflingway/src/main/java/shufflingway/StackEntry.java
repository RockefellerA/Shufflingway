package shufflingway;

import java.util.List;

/**
 * A single entry on the resolution stack — a Summon being cast,
 * an Action Ability being activated, an Auto-ability that has triggered,
 * or a Warp card that is about to enter the field.
 *
 * @param source               the card that owns this effect
 * @param ability              non-null for Action Abilities; {@code null} otherwise
 * @param autoAbility          non-null for Auto-abilities; {@code null} otherwise
 * @param isP1                 {@code true} when the effect was triggered by Player 1
 * @param xValue               the amount of CP paid into {@code 《X》}; {@code 0} when the ability has no X cost
 * @param isExBurst            {@code true} when this entry represents an EX Burst effect put on the stack
 *                             (e.g. by Akstar); causes {@link #effectText()} to use {@link CardData#exBurstEffect()}
 *                             and skips the Summon post-resolution steps
 * @param preSelectedTargets   targets chosen at activation time (before the entry goes on the stack);
 *                             {@code null} when the ability doesn't pre-select targets
 * @param isWarpResolve              {@code true} when this entry represents a Warp card entering the field
 *                                   after its last counter was removed; resolution calls
 *                                   {@link MainWindow#resolveWarpCard}
 * @param paidExtraCost              {@code true} when the summon was cast with its optional extra cost paid
 * @param extraCostRemovedCardPower  power of the Forward removed by the extra cost (Titan); {@code 0} otherwise
 * @param revealedForwardPower       power of the Forward revealed to pay a {@link RevealCost}
 *                                   (Rinoa 18-097R); {@code 0} otherwise.  Carried on the entry
 *                                   rather than read off a field at resolution time for the reason
 *                                   {@code extraCostRemovedCardPower} is: the cost is paid at
 *                                   activation, and another activation can happen before this one
 *                                   comes off the stack
 * @param triggerCard                for an auto ability fired by a card's departure, the card whose
 *                                   event fired it — what "it" and "the Forward placed in the Break
 *                                   Zone" point at; {@code null} for every other entry. Carried on
 *                                   the entry because the field the trigger dispatcher sets is
 *                                   restored as soon as the ability is <em>pushed</em>, which is
 *                                   long before it resolves: read off that field, the effect saw
 *                                   nothing and fizzled.
 */
public record StackEntry(CardData source, ActionAbility ability, AutoAbility autoAbility, boolean isP1, int xValue, boolean isExBurst, List<ForwardTarget> preSelectedTargets, boolean isWarpResolve, boolean paidExtraCost, int extraCostRemovedCardPower, int revealedForwardPower, CardData triggerCard) {

    /** Compatibility constructor for the entries that carry no triggering card, which is most of them. */
    public StackEntry(CardData source, ActionAbility ability, AutoAbility autoAbility, boolean isP1,
            int xValue, boolean isExBurst, List<ForwardTarget> preSelectedTargets,
            boolean isWarpResolve, boolean paidExtraCost, int extraCostRemovedCardPower,
            int revealedForwardPower) {
        this(source, ability, autoAbility, isP1, xValue, isExBurst, preSelectedTargets,
                isWarpResolve, paidExtraCost, extraCostRemovedCardPower, revealedForwardPower, null);
    }

    /** Convenience constructor for Summons and Action Abilities without an X cost. */
    public StackEntry(CardData source, ActionAbility ability, boolean isP1) {
        this(source, ability, null, isP1, 0, false, null, false, false, 0, 0);
    }

    /** Convenience constructor for Action Abilities with an X cost. */
    public StackEntry(CardData source, ActionAbility ability, boolean isP1, int xValue) {
        this(source, ability, null, isP1, xValue, false, null, false, false, 0, 0);
    }

    /** Convenience constructor for Action Abilities with pre-selected targets. */
    public StackEntry(CardData source, ActionAbility ability, boolean isP1, int xValue, List<ForwardTarget> preSelectedTargets) {
        this(source, ability, null, isP1, xValue, false, preSelectedTargets, false, false, 0, 0);
    }

    /** Convenience constructor for an Action Ability whose cost revealed a Forward (Rinoa 18-097R). */
    public StackEntry(CardData source, ActionAbility ability, boolean isP1, int xValue,
            List<ForwardTarget> preSelectedTargets, int revealedForwardPower) {
        this(source, ability, null, isP1, xValue, false, preSelectedTargets, false, false, 0, revealedForwardPower);
    }

    /** Convenience constructor for EX Burst effects placed on the stack. */
    public StackEntry(CardData source, boolean isP1, boolean isExBurst) {
        this(source, null, null, isP1, 0, isExBurst, null, false, false, 0, 0);
    }

    /** Creates a stack entry that, when it resolves, places {@code card} on the field via Warp. */
    public static StackEntry forWarpResolve(CardData card, boolean isP1) {
        return new StackEntry(card, null, null, isP1, 0, false, null, true, false, 0, 0);
    }

    /** Creates a stack entry for a summon cast with its extra cost paid. */
    public static StackEntry forSummonWithExtraCost(CardData card, boolean isP1, int removedCardPower) {
        return new StackEntry(card, null, null, isP1, 0, false, null, false, true, removedCardPower, 0);
    }

    /** Creates a stack entry for a summon cast with its extra cost paid, including an X value (e.g. Valefor). */
    public static StackEntry forSummonWithExtraCost(CardData card, boolean isP1, int removedCardPower, int xValue) {
        return new StackEntry(card, null, null, isP1, xValue, false, null, false, true, removedCardPower, 0);
    }

    /**
     * A copy of this entry choosing {@code newTargets} instead of its current selection —
     * how a redirect ("the Summon or ability is now choosing X instead") takes effect, since
     * the entry is otherwise unchanged and must keep its place in the resolution order.
     */
    public StackEntry withPreSelectedTargets(List<ForwardTarget> newTargets) {
        return new StackEntry(source, ability, autoAbility, isP1, xValue, isExBurst,
                newTargets, isWarpResolve, paidExtraCost, extraCostRemovedCardPower, revealedForwardPower);
    }

    public boolean isSummon()        { return ability == null && autoAbility == null && !isExBurst && !isWarpResolve; }
    public boolean isAutoAbility()   { return autoAbility != null; }
    public boolean isActionAbility() { return ability != null; }
    public boolean isSpecialAbility(){ return ability != null && ability.isSpecial(); }
    public boolean isExBurstEntry()  { return isExBurst; }

    /** The raw effect text that {@link ActionResolver#parse} will run. */
    public String effectText() {
        if (autoAbility != null) return autoAbility.effectText();
        if (ability    != null) return ability.effectText();
        if (isExBurst)          return source.exBurstEffect();
        return source.summonEffect();
    }
}
