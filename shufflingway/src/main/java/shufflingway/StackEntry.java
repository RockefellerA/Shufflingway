package shufflingway;

/**
 * A single entry on the resolution stack — a Summon being cast,
 * an Action Ability being activated, or an Auto-ability that has triggered.
 *
 * @param source      the card that owns this effect
 * @param ability     non-null for Action Abilities; {@code null} otherwise
 * @param autoAbility non-null for Auto-abilities; {@code null} otherwise
 * @param isP1        {@code true} when the effect was triggered by Player 1
 * @param xValue      the amount of CP paid into {@code 《X》}; {@code 0} when the ability has no X cost
 */
public record StackEntry(CardData source, ActionAbility ability, AutoAbility autoAbility, boolean isP1, int xValue) {

    /** Convenience constructor for Summons and Action Abilities without an X cost. */
    public StackEntry(CardData source, ActionAbility ability, boolean isP1) {
        this(source, ability, null, isP1, 0);
    }

    /** Convenience constructor for Action Abilities with an X cost. */
    public StackEntry(CardData source, ActionAbility ability, boolean isP1, int xValue) {
        this(source, ability, null, isP1, xValue);
    }

    public boolean isSummon()      { return ability == null && autoAbility == null; }
    public boolean isAutoAbility() { return autoAbility != null; }

    /** The raw effect text that {@link ActionResolver#parse} will run. */
    public String effectText() {
        if (autoAbility != null) return autoAbility.effectText();
        if (ability    != null) return ability.effectText();
        return source.summonEffect();
    }
}
