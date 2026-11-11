package shufflingway;

import java.util.List;

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
                DamageInsteadCondition.DamageLeadAtLeast,
                DamageInsteadCondition.BreakZoneJobOrNameAtLeast,
                DamageInsteadCondition.YouCastAtLeastOtherThan,
                DamageInsteadCondition.PaidExtraCost,
                DamageInsteadCondition.NamedEnteredViaWarp,
                DamageInsteadCondition.PartyAtLeast,
                DamageInsteadCondition.EnteredCardNamed,
                DamageInsteadCondition.NamedEnteredByAbilityOfCategory,
                DamageInsteadCondition.BreakZoneNamesBeforePayingAtLeast,
                DamageInsteadCondition.YouHaveCrystal,
                DamageInsteadCondition.DiscardedCostCardOfElement,
                DamageInsteadCondition.SourceCountersAtLeast,
                DamageInsteadCondition.BreakZoneHasCardNamed,
                DamageInsteadCondition.DiscardedNamedToCast,
                DamageInsteadCondition.CostRemovedCardMatches,
                DamageInsteadCondition.YouDrewAtLeast {

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
     * "If you have at least N points of damage more than your opponent" (7-010L Jecht): your
     * damage less theirs is N or more. {@link YouHaveMoreDamageThanOpponent} is the lead of one.
     */
    record DamageLeadAtLeast(int min) implements DamageInsteadCondition {}

    /**
     * "If you have [a total of] N or more Job X and/or Card Name Y in your Break Zone" (14-014C,
     * 25-002C) — cards that are Job X, Card Name Y, or both, each counted once.
     */
    record BreakZoneJobOrNameAtLeast(int min, String job, String name) implements DamageInsteadCondition {}

    /**
     * "If you have cast N or more cards other than [Name] this turn" (21-054H Pandemonium) — the
     * turn's casts less those of the named card.
     */
    record YouCastAtLeastOtherThan(int min, String name) implements DamageInsteadCondition {}

    /** "If you paid the extra cost" (8-060L). */
    record PaidExtraCost() implements DamageInsteadCondition {}

    /**
     * "If [Name] enters the field due to Warp" (20-027C Genesis). Names the card asking; a caller
     * that knows the source checks the name, as for {@link NamedPowerAtLeast}.
     */
    record NamedEnteredViaWarp(String name) implements DamageInsteadCondition {}

    /** "If N or more Forwards form the party" (15-128L) — the attacking party's size. */
    record PartyAtLeast(int min) implements DamageInsteadCondition {}

    /** "If the Forward is Card Name X" (9-038R Rinoa) — the card whose arrival fired the trigger. */
    record EnteredCardNamed(String name) implements DamageInsteadCondition {}

    /**
     * "If [Name] entered the field due to an ability of a Category X Character" (26-035R Snow).
     * {@code source} is the card asking, bound by the caller that knows it (parsed as {@code null},
     * which never holds).
     */
    record NamedEnteredByAbilityOfCategory(String name, String category, CardData source)
            implements DamageInsteadCondition {}

    /**
     * "If you have a total of N or more Card Name X and/or Card Name Y in your Break Zone (before
     * paying the cost for [Name])" (9-002H Ifrita) — the Break Zone less what was discarded to pay
     * for {@code payer}. {@code payer} is bound by the caller, like the record above.
     */
    record BreakZoneNamesBeforePayingAtLeast(int min, List<String> names, String payerName, CardData payer)
            implements DamageInsteadCondition {}

    /** "If you have a 《C》" (24-002C Warrior of Light) — at least one Crystal. */
    record YouHaveCrystal() implements DamageInsteadCondition {}

    /**
     * "If the discarded card is of X Element" (26-010C Onion Knight, 26-024C Musician, 26-068C
     * Gladiator) — the card discarded to pay the ability's cost. A Multi-Element card is of each.
     */
    record DiscardedCostCardOfElement(String element) implements DamageInsteadCondition {}

    /**
     * "If there are N or more X Counters placed on [Name]" (15-011L Palom) — counters on the card
     * asking. {@code source} is bound by the caller, like {@link NamedEnteredByAbilityOfCategory}.
     */
    record SourceCountersAtLeast(int min, String counter, String name, CardData source)
            implements DamageInsteadCondition {}

    /** "If you have a Card Name X in your Break Zone" (25-026C SOLDIER Candidate). */
    record BreakZoneHasCardNamed(String name) implements DamageInsteadCondition {}

    /**
     * "If you discarded a Card Name X to cast [Name]" (29-094H Ramuh) — among the cards discarded
     * for CP to cast the card asking. {@code payer} is bound by the caller, like
     * {@link BreakZoneNamesBeforePayingAtLeast}.
     */
    record DiscardedNamedToCast(String name, String payerName, CardData payer) implements DamageInsteadCondition {}

    /**
     * "If a &lt;filter&gt; card is removed by this ability's cost" (29-098R Quina). {@code filter}
     * is read by {@code parseRevealCondition}.
     */
    record CostRemovedCardMatches(String filter) implements DamageInsteadCondition {}

    /** "If you have drawn N or more cards [this turn]" (15-122L Mog (VI)). */
    record YouDrewAtLeast(int min) implements DamageInsteadCondition {}
}
