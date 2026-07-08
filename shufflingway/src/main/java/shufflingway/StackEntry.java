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
 * @param isWarpResolve        {@code true} when this entry represents a Warp card entering the field
 *                             after its last counter was removed; resolution calls
 *                             {@link MainWindow#resolveWarpCard}
 */
public record StackEntry(CardData source, ActionAbility ability, AutoAbility autoAbility, boolean isP1, int xValue, boolean isExBurst, List<ForwardTarget> preSelectedTargets, boolean isWarpResolve) {

    /** Convenience constructor for Summons and Action Abilities without an X cost. */
    public StackEntry(CardData source, ActionAbility ability, boolean isP1) {
        this(source, ability, null, isP1, 0, false, null, false);
    }

    /** Convenience constructor for Action Abilities with an X cost. */
    public StackEntry(CardData source, ActionAbility ability, boolean isP1, int xValue) {
        this(source, ability, null, isP1, xValue, false, null, false);
    }

    /** Convenience constructor for Action Abilities with pre-selected targets. */
    public StackEntry(CardData source, ActionAbility ability, boolean isP1, int xValue, List<ForwardTarget> preSelectedTargets) {
        this(source, ability, null, isP1, xValue, false, preSelectedTargets, false);
    }

    /** Convenience constructor for EX Burst effects placed on the stack. */
    public StackEntry(CardData source, boolean isP1, boolean isExBurst) {
        this(source, null, null, isP1, 0, isExBurst, null, false);
    }

    /** Creates a stack entry that, when it resolves, places {@code card} on the field via Warp. */
    public static StackEntry forWarpResolve(CardData card, boolean isP1) {
        return new StackEntry(card, null, null, isP1, 0, false, null, true);
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
