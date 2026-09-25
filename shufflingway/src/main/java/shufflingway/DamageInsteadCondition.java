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
                DamageInsteadCondition.YouCastCardNamed,
                DamageInsteadCondition.OpponentHasMoreForwards,
                DamageInsteadCondition.IsExBurst,
                DamageInsteadCondition.BreakZoneAtLeast,
                DamageInsteadCondition.OpponentControlsAtLeast,
                DamageInsteadCondition.YouHandAtLeast,
                DamageInsteadCondition.YouControlAtMost,
                DamageInsteadCondition.NamedPowerAtLeast,
                DamageInsteadCondition.YouHaveMoreDamageThanOpponent,
                DamageInsteadCondition.BreakZoneJobOrNameAtLeast {

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

    /**
     * "If you have cast Card Name [X] this turn" — Sazh 1-013H's Brynhildr bonus, the one
     * printing of this family whose condition names a card rather than counting them.
     *
     * <p>One cast is enough, unlike the "other than [Self]" wording elsewhere in the resolver
     * where the source's own cast is one of the ones counted: the card named here is never the
     * card asking.
     */
    record YouCastCardNamed(String name) implements DamageInsteadCondition {}

    /** "If the number of Forwards your opponent controls is greater than the number of Forwards you control" */
    record OpponentHasMoreForwards() implements DamageInsteadCondition {}

    /** "If [card name] results from an EX Burst" */
    record IsExBurst() implements DamageInsteadCondition {}

    /**
     * "If there are N or more cards in your Break Zone" (22-079L, 22-073L) or "If you have N or
     * more Summons in your Break Zone" (16-090R) — the types counted, all four for "cards".
     */
    record BreakZoneAtLeast(int min, boolean forwards, boolean backups, boolean monsters, boolean summons)
            implements DamageInsteadCondition {}

    /**
     * "If your opponent controls N or more [dull] Forwards" (8-082R, 20-026C). {@code state} is
     * "dull", "active" or {@code null} for either.
     */
    record OpponentControlsAtLeast(int min, boolean forwards, boolean backups, boolean monsters, String state)
            implements DamageInsteadCondition {}

    /** "If you have N or more cards in your hand" (10-080H) */
    record YouHandAtLeast(int min) implements DamageInsteadCondition {}

    /** "If you control N or less Backups" (23-116H) */
    record YouControlAtMost(int max, boolean forwards, boolean backups, boolean monsters)
            implements DamageInsteadCondition {}

    /**
     * "If Zell has 10000 power or more" (18-006C, 15-010R Vargas, 22-003R Ayame) — every printing
     * names the card asking; a reader that knows the source should refuse any other name.
     */
    record NamedPowerAtLeast(String name, int min) implements DamageInsteadCondition {}

    /** "If you have received more points of damage than your opponent" (22-059C) */
    record YouHaveMoreDamageThanOpponent() implements DamageInsteadCondition {}

    /**
     * "If you have [a total of] N or more Job X and/or Card Name Y in your Break Zone" (14-014C,
     * 25-002C) — cards that are Job X, Card Name Y, or both, each counted once.
     */
    record BreakZoneJobOrNameAtLeast(int min, String job, String name) implements DamageInsteadCondition {}
}
