package shufflingway;

/**
 * Parsed condition for "Deal it N damage. If &lt;condition&gt;, deal it M damage instead."
 */
public sealed interface DamageInsteadCondition
        permits DamageInsteadCondition.TargetIsActive,
                DamageInsteadCondition.TargetIsMultiElement,
                DamageInsteadCondition.YouControl,
                DamageInsteadCondition.YouReceivedDamageThisTurn,
                DamageInsteadCondition.YouReceivedDamageAtLeast,
                DamageInsteadCondition.YouHaveSummonInBreakZone,
                DamageInsteadCondition.OpponentDamageAtLeast,
                DamageInsteadCondition.OpponentHandAtMost,
                DamageInsteadCondition.YouCastAtLeast,
                DamageInsteadCondition.OpponentHasMoreForwards,
                DamageInsteadCondition.IsExBurst {

    /** "If it is active" */
    record TargetIsActive() implements DamageInsteadCondition {}

    /** "If it is a Multi-Element Forward" (or similar card type) */
    record TargetIsMultiElement() implements DamageInsteadCondition {}

    /** "If you control [ControlCondition][ other than excludeName]" */
    record YouControl(ControlCondition cond, String excludeName) implements DamageInsteadCondition {}

    /** "If you have received a point of damage this turn" */
    record YouReceivedDamageThisTurn() implements DamageInsteadCondition {}

    /** "If you have received N points of damage or more" */
    record YouReceivedDamageAtLeast(int min) implements DamageInsteadCondition {}

    /** "If you have a Summon in your Break Zone" */
    record YouHaveSummonInBreakZone() implements DamageInsteadCondition {}

    /** "If your opponent has received N points of damage or more" */
    record OpponentDamageAtLeast(int min) implements DamageInsteadCondition {}

    /** "If your opponent has N cards or less in their hand" */
    record OpponentHandAtMost(int max) implements DamageInsteadCondition {}

    /** "If you have cast N or more cards this turn" */
    record YouCastAtLeast(int min) implements DamageInsteadCondition {}

    /** "If the number of Forwards your opponent controls is greater than the number of Forwards you control" */
    record OpponentHasMoreForwards() implements DamageInsteadCondition {}

    /** "If [card name] results from an EX Burst" */
    record IsExBurst() implements DamageInsteadCondition {}
}
