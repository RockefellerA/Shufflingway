package shufflingway;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * A conditional passive ability of the form "If you control [X], [target] gains [Z]."
 *
 * <p>Active while the owning card is on the field AND every {@link #conditions} is satisfied.
 * When any condition ceases to be met (e.g., the required card leaves the field) the bonus
 * is immediately removed from the target.
 *
 * <p>The {@link #exceptCardName} handles the "other than" exclusion pattern:
 * "If you control a Job X <b>other than Queen</b>, Queen gains …" — the card named "Queen" is
 * excluded from the pool when evaluating whether a Job-X card is present.
 *
 * <p>The target may be either a specific card by name ({@link #targetCardName}) <i>or</i> a
 * filter ({@link #targetFilter}).  When the filter is non-null it takes precedence: every
 * controlled card that matches the filter receives the bonus.  When the filter is null the
 * legacy {@link #targetCardName} match is used.
 */
public record IfControlBoost(
        List<ControlCondition> conditions,    // all must be satisfied simultaneously (AND)
        String    exceptCardName,             // excluded card name ("other than X"); empty string if none
        String    targetCardName,             // card on the field that receives the bonus (legacy name target)
        FieldPowerGrant targetFilter,         // filter-style target (category/job/type); null = name target
        int       powerBonus,                 // +N power added to the target (0 if no power effect)
        Set<CardData.Trait> grantedTraits,    // traits granted to the target while active
        String    specialText,                // quoted special ability text (display only; empty if none)
        boolean   cannotBeChosenBySummons,    // target cannot be chosen by Summons while active (see chosenImmunityOpponentOnly for whose)
        boolean   cannotBeChosenByAbilities,  // target cannot be chosen by abilities while active (see chosenImmunityOpponentOnly for whose)
        boolean   cannotBeBlocked,            // target cannot be blocked (unconditionally) while active
        int[]     cannotBeBlockedByCost,      // null = no restriction; {costVal, 1} = "or more", {costVal, 0} = "or less"
        int       minRemovedFromGame,         // 0 = unused; >0 = condition requires this many cards total in both RFP zones
        int       minDamageReceived,          // 0 = unused; >0 = condition requires the controlling player to have taken this many damage points
        boolean   instead,                    // true = this effect replaces (rather than stacks with) the base field effect
        int       maxOpponentHandSize,        // 0 = unused; >0 = condition requires opponent hand size to be ≤ this value
        int       minOpponentForwards,        // 0 = unused; >0 = condition requires opponent to control this many Forwards
        int       maxOwnHandSize,            // 0 = unused; >0 = condition requires own hand size to be ≤ this value
        boolean   allBackupsDifferentElements, // true = condition requires all controlled Backups to be different Elements
        boolean   chosenImmunityOpponentOnly, // true = the cannotBeChosen* immunities apply only to the target's opponent ("cannot be chosen by your opponent's ..."); false = to either player
        int       minOwnHandSize,             // 0 = unused; >0 = condition requires own hand size to be >= this value
        int       minDifferentElementBackups, // 0 = unused; >0 = condition requires this many DISTINCT Elements among controlled Backups
        String    chosenImmunitySourceType, // null = any source; else the card type whose abilities the immunity covers ("Backup")
        int       chosenImmunitySourceCost  // 0 = any cost; >0 = the immunity covers only sources of exactly this cost ("Summons of cost 1")
) {
    public IfControlBoost {
        conditions    = List.copyOf(conditions);
        EnumSet<CardData.Trait> traitSet = EnumSet.noneOf(CardData.Trait.class);
        traitSet.addAll(grantedTraits);
        grantedTraits = Collections.unmodifiableSet(traitSet);
        if (exceptCardName == null) exceptCardName = "";
        if (specialText    == null) specialText    = "";
    }

    /**
     * Compatibility constructor preserving the prior 21-arg canonical form; defaults
     * {@code chosenImmunitySourceType} to {@code null} — an immunity that names no source type
     * covers every source, which is what every grant predating the field meant.
     */
    public IfControlBoost(List<ControlCondition> conditions, String exceptCardName,
            String targetCardName, FieldPowerGrant targetFilter, int powerBonus,
            Set<CardData.Trait> grantedTraits, String specialText,
            boolean cannotBeChosenBySummons, boolean cannotBeChosenByAbilities, boolean cannotBeBlocked,
            int[] cannotBeBlockedByCost, int minRemovedFromGame, int minDamageReceived, boolean instead,
            int maxOpponentHandSize, int minOpponentForwards, int maxOwnHandSize,
            boolean allBackupsDifferentElements, boolean chosenImmunityOpponentOnly,
            int minOwnHandSize, int minDifferentElementBackups) {
        this(conditions, exceptCardName, targetCardName, targetFilter, powerBonus, grantedTraits,
                specialText, cannotBeChosenBySummons, cannotBeChosenByAbilities, cannotBeBlocked,
                cannotBeBlockedByCost, minRemovedFromGame, minDamageReceived, instead,
                maxOpponentHandSize, minOpponentForwards, maxOwnHandSize, allBackupsDifferentElements,
                chosenImmunityOpponentOnly, minOwnHandSize, minDifferentElementBackups, null, 0);
    }

    /**
     * A copy whose chosen-immunity covers only abilities belonging to a card of {@code cardType}
     * — Aerith 3-050L, "The Forwards you control cannot be chosen by your opponent's Backup
     * abilities", the only printing in the corpus to narrow the immunity by the source's type.
     *
     * <p>A wither for the same reason as {@link #asOpponentScopedChosenImmunity}: the field sits
     * past a long tail of defaults that a positional call would have to spell out.
     */
    public IfControlBoost withChosenImmunitySourceType(String cardType) {
        return new IfControlBoost(conditions, exceptCardName, targetCardName, targetFilter,
                powerBonus, grantedTraits, specialText, cannotBeChosenBySummons,
                cannotBeChosenByAbilities, cannotBeBlocked, cannotBeBlockedByCost,
                minRemovedFromGame, minDamageReceived, instead, maxOpponentHandSize,
                minOpponentForwards, maxOwnHandSize, allBackupsDifferentElements,
                chosenImmunityOpponentOnly, minOwnHandSize, minDifferentElementBackups, cardType,
                chosenImmunitySourceCost);
    }

    /**
     * A copy whose chosen-immunity covers only sources of exactly {@code cost} — Charlotte 27-128S,
     * "The Forwards you control cannot be chosen by your opponent's Summons of cost 1", the only
     * printing in the corpus to narrow the immunity by what the source cost.
     *
     * <p>Exactly that cost, not "or less": the sentence names a number with no comparator, and a
     * range would shield the party from the cost-2 and cost-3 Summons the card was priced against.
     *
     * <p>The cost read is the printed one. A discount is paid at cast time and changes nothing
     * about what the card is, and by the time a Summon is choosing targets its cost is spent.
     */
    public IfControlBoost withChosenImmunitySourceCost(int cost) {
        return new IfControlBoost(conditions, exceptCardName, targetCardName, targetFilter,
                powerBonus, grantedTraits, specialText, cannotBeChosenBySummons,
                cannotBeChosenByAbilities, cannotBeBlocked, cannotBeBlockedByCost,
                minRemovedFromGame, minDamageReceived, instead, maxOpponentHandSize,
                minOpponentForwards, maxOwnHandSize, allBackupsDifferentElements,
                chosenImmunityOpponentOnly, minOwnHandSize, minDifferentElementBackups,
                chosenImmunitySourceType, cost);
    }

    /**
     * Whether an ability belonging to {@code chooserSource} is one this grant's immunity covers.
     *
     * <p>An immunity naming no source type covers every source, so this answers {@code true}
     * whenever the field is unset — which keeps it usable in the same conjunction as the rest.
     * A {@code null} source is unknown rather than "not a Backup", and is admitted for the same
     * reason: refusing it would silently narrow every existing grant.
     */
    public boolean admitsChooserSource(CardData chooserSource) {
        if (chosenImmunitySourceType == null && chosenImmunitySourceCost == 0) return true;
        if (chooserSource == null) return true;
        if (chosenImmunitySourceCost > 0 && chooserSource.cost() != chosenImmunitySourceCost)
            return false;
        if (chosenImmunitySourceType == null) return true;
        return switch (chosenImmunitySourceType.toLowerCase(java.util.Locale.ROOT)) {
            case "backup"    -> chooserSource.isBackup();
            case "forward"   -> chooserSource.isForward();
            case "monster"   -> chooserSource.isMonster();
            case "character" -> !chooserSource.isSummon();
            default          -> true;
        };
    }

    /**
     * Compatibility constructor preserving the prior 18-arg signature; defaults
     * {@code chosenImmunityOpponentOnly} to {@code false}. That default is the safe one: a grant
     * whose scope was never recorded blocks either player, which is what every caller predating
     * the field already assumed.
     */
    public IfControlBoost(List<ControlCondition> conditions, String exceptCardName,
            String targetCardName, FieldPowerGrant targetFilter, int powerBonus,
            Set<CardData.Trait> grantedTraits, String specialText,
            boolean cannotBeChosenBySummons, boolean cannotBeChosenByAbilities, boolean cannotBeBlocked,
            int[] cannotBeBlockedByCost, int minRemovedFromGame, int minDamageReceived, boolean instead,
            int maxOpponentHandSize, int minOpponentForwards, int maxOwnHandSize,
            boolean allBackupsDifferentElements) {
        this(conditions, exceptCardName, targetCardName, targetFilter, powerBonus, grantedTraits,
                specialText, cannotBeChosenBySummons, cannotBeChosenByAbilities, cannotBeBlocked,
                cannotBeBlockedByCost, minRemovedFromGame, minDamageReceived, instead, maxOpponentHandSize,
                minOpponentForwards, maxOwnHandSize, allBackupsDifferentElements, false, 0, 0);
    }

    /** Compatibility constructor preserving the prior 17-arg signature; defaults allBackupsDifferentElements to false. */
    public IfControlBoost(List<ControlCondition> conditions, String exceptCardName,
            String targetCardName, FieldPowerGrant targetFilter, int powerBonus,
            Set<CardData.Trait> grantedTraits, String specialText,
            boolean cannotBeChosenBySummons, boolean cannotBeChosenByAbilities, boolean cannotBeBlocked,
            int[] cannotBeBlockedByCost, int minRemovedFromGame, int minDamageReceived, boolean instead,
            int maxOpponentHandSize, int minOpponentForwards, int maxOwnHandSize) {
        this(conditions, exceptCardName, targetCardName, targetFilter, powerBonus, grantedTraits,
                specialText, cannotBeChosenBySummons, cannotBeChosenByAbilities, cannotBeBlocked,
                cannotBeBlockedByCost, minRemovedFromGame, minDamageReceived, instead, maxOpponentHandSize,
                minOpponentForwards, maxOwnHandSize, false);
    }

    /** Compatibility constructor preserving the prior 16-arg signature; defaults maxOwnHandSize to 0. */
    public IfControlBoost(List<ControlCondition> conditions, String exceptCardName,
            String targetCardName, FieldPowerGrant targetFilter, int powerBonus,
            Set<CardData.Trait> grantedTraits, String specialText,
            boolean cannotBeChosenBySummons, boolean cannotBeChosenByAbilities, boolean cannotBeBlocked,
            int[] cannotBeBlockedByCost, int minRemovedFromGame, int minDamageReceived, boolean instead,
            int maxOpponentHandSize, int minOpponentForwards) {
        this(conditions, exceptCardName, targetCardName, targetFilter, powerBonus, grantedTraits,
                specialText, cannotBeChosenBySummons, cannotBeChosenByAbilities, cannotBeBlocked,
                cannotBeBlockedByCost, minRemovedFromGame, minDamageReceived, instead, maxOpponentHandSize,
                minOpponentForwards, 0);
    }

    /** Compatibility constructor preserving the prior 15-arg signature; defaults minOpponentForwards to 0. */
    public IfControlBoost(List<ControlCondition> conditions, String exceptCardName,
            String targetCardName, FieldPowerGrant targetFilter, int powerBonus,
            Set<CardData.Trait> grantedTraits, String specialText,
            boolean cannotBeChosenBySummons, boolean cannotBeChosenByAbilities, boolean cannotBeBlocked,
            int[] cannotBeBlockedByCost, int minRemovedFromGame, int minDamageReceived, boolean instead,
            int maxOpponentHandSize) {
        this(conditions, exceptCardName, targetCardName, targetFilter, powerBonus, grantedTraits,
                specialText, cannotBeChosenBySummons, cannotBeChosenByAbilities, cannotBeBlocked,
                cannotBeBlockedByCost, minRemovedFromGame, minDamageReceived, instead, maxOpponentHandSize, 0);
    }

    /** Compatibility constructor preserving the prior 14-arg signature; defaults maxOpponentHandSize to 0. */
    public IfControlBoost(List<ControlCondition> conditions, String exceptCardName,
            String targetCardName, FieldPowerGrant targetFilter, int powerBonus,
            Set<CardData.Trait> grantedTraits, String specialText,
            boolean cannotBeChosenBySummons, boolean cannotBeChosenByAbilities, boolean cannotBeBlocked,
            int[] cannotBeBlockedByCost, int minRemovedFromGame, int minDamageReceived, boolean instead) {
        this(conditions, exceptCardName, targetCardName, targetFilter, powerBonus, grantedTraits,
                specialText, cannotBeChosenBySummons, cannotBeChosenByAbilities, cannotBeBlocked,
                cannotBeBlockedByCost, minRemovedFromGame, minDamageReceived, instead, 0);
    }

    /** Compatibility constructor preserving the prior 12-arg signature; defaults minDamageReceived/instead to 0/false. */
    public IfControlBoost(List<ControlCondition> conditions, String exceptCardName,
            String targetCardName, FieldPowerGrant targetFilter, int powerBonus,
            Set<CardData.Trait> grantedTraits, String specialText,
            boolean cannotBeChosenBySummons, boolean cannotBeChosenByAbilities, boolean cannotBeBlocked,
            int[] cannotBeBlockedByCost, int minRemovedFromGame) {
        this(conditions, exceptCardName, targetCardName, targetFilter, powerBonus, grantedTraits,
                specialText, cannotBeChosenBySummons, cannotBeChosenByAbilities, cannotBeBlocked,
                cannotBeBlockedByCost, minRemovedFromGame, 0, false, 0);
    }

    /** Compatibility constructor preserving the prior 11-arg signature; defaults minRemovedFromGame/minDamageReceived/instead to 0/0/false. */
    public IfControlBoost(List<ControlCondition> conditions, String exceptCardName,
            String targetCardName, FieldPowerGrant targetFilter, int powerBonus,
            Set<CardData.Trait> grantedTraits, String specialText,
            boolean cannotBeChosenBySummons, boolean cannotBeChosenByAbilities, boolean cannotBeBlocked,
            int[] cannotBeBlockedByCost) {
        this(conditions, exceptCardName, targetCardName, targetFilter, powerBonus, grantedTraits,
                specialText, cannotBeChosenBySummons, cannotBeChosenByAbilities, cannotBeBlocked,
                cannotBeBlockedByCost, 0, 0, false, 0);
    }

    /** Compatibility constructor preserving the prior 10-arg signature; defaults cannotBeBlockedByCost/instead to null/false. */
    public IfControlBoost(List<ControlCondition> conditions, String exceptCardName,
            String targetCardName, FieldPowerGrant targetFilter, int powerBonus,
            Set<CardData.Trait> grantedTraits, String specialText,
            boolean cannotBeChosenBySummons, boolean cannotBeChosenByAbilities, boolean cannotBeBlocked) {
        this(conditions, exceptCardName, targetCardName, targetFilter, powerBonus, grantedTraits,
                specialText, cannotBeChosenBySummons, cannotBeChosenByAbilities, cannotBeBlocked, null, 0, 0, false, 0);
    }

    /** Compatibility constructor preserving the prior 9-arg signature; defaults cannotBeBlocked/Cost/instead to false/null/false. */
    public IfControlBoost(List<ControlCondition> conditions, String exceptCardName,
            String targetCardName, FieldPowerGrant targetFilter, int powerBonus,
            Set<CardData.Trait> grantedTraits, String specialText,
            boolean cannotBeChosenBySummons, boolean cannotBeChosenByAbilities) {
        this(conditions, exceptCardName, targetCardName, targetFilter, powerBonus, grantedTraits,
                specialText, cannotBeChosenBySummons, cannotBeChosenByAbilities, false, null, 0, 0, false, 0);
    }

    /** Compatibility constructor preserving the prior 8-arg signature; uses name-target mode, defaults instead to false. */
    public IfControlBoost(List<ControlCondition> conditions, String exceptCardName,
            String targetCardName, int powerBonus, Set<CardData.Trait> grantedTraits,
            String specialText, boolean cannotBeChosenBySummons, boolean cannotBeChosenByAbilities) {
        this(conditions, exceptCardName, targetCardName, null, powerBonus, grantedTraits,
                specialText, cannotBeChosenBySummons, cannotBeChosenByAbilities, false, null, 0, 0, false, 0);
    }

    /**
     * A copy whose {@code cannotBeChosen*} immunities are scoped to the target's opponent.
     * Written as a wither so the parse site stays readable: the flag is the nineteenth record
     * component, and reaching it through a positional constructor there would spell out every
     * default in between.
     */
    public IfControlBoost asOpponentScopedChosenImmunity() {
        return new IfControlBoost(conditions, exceptCardName, targetCardName, targetFilter,
                powerBonus, grantedTraits, specialText, cannotBeChosenBySummons,
                cannotBeChosenByAbilities, cannotBeBlocked, cannotBeBlockedByCost,
                minRemovedFromGame, minDamageReceived, instead, maxOpponentHandSize,
                minOpponentForwards, maxOwnHandSize, allBackupsDifferentElements, true,
                minOwnHandSize, minDifferentElementBackups, chosenImmunitySourceType,
                chosenImmunitySourceCost);
    }

    /**
     * A copy gated on the controller holding at least {@code n} cards in hand (Galuf 3-077H).
     * A wither for the same reason as {@link #asOpponentScopedChosenImmunity}: the field sits past
     * a long tail of defaults that a positional call would have to spell out.
     */
    public IfControlBoost withMinOwnHandSize(int n) {
        return new IfControlBoost(conditions, exceptCardName, targetCardName, targetFilter,
                powerBonus, grantedTraits, specialText, cannotBeChosenBySummons,
                cannotBeChosenByAbilities, cannotBeBlocked, cannotBeBlockedByCost,
                minRemovedFromGame, minDamageReceived, instead, maxOpponentHandSize,
                minOpponentForwards, maxOwnHandSize, allBackupsDifferentElements,
                chosenImmunityOpponentOnly, n, minDifferentElementBackups, chosenImmunitySourceType,
                chosenImmunitySourceCost);
    }

    /**
     * A copy gated on {@code n} distinct Elements among the controller's Backups (Kefka 3-079H).
     *
     * <p>Goes through the canonical constructor like its siblings rather than the compatibility
     * one, which defaults the two source narrowings away: no printing carries this condition and a
     * narrowed immunity at once, but a wither that drops fields it was not asked about is a trap
     * for the one that eventually does.
     */
    public IfControlBoost withMinDifferentElementBackups(int n) {
        return new IfControlBoost(conditions, exceptCardName, targetCardName, targetFilter,
                powerBonus, grantedTraits, specialText, cannotBeChosenBySummons,
                cannotBeChosenByAbilities, cannotBeBlocked, cannotBeBlockedByCost,
                minRemovedFromGame, minDamageReceived, instead, maxOpponentHandSize,
                minOpponentForwards, maxOwnHandSize, allBackupsDifferentElements,
                chosenImmunityOpponentOnly, minOwnHandSize, n, chosenImmunitySourceType,
                chosenImmunitySourceCost);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("ICB[");
        if (minDamageReceived > 0) {
            sb.append("dmg>=").append(minDamageReceived).append(" → ");
        } else if (minRemovedFromGame > 0) {
            sb.append("rfp>=").append(minRemovedFromGame).append(" → ");
        } else if (!conditions.isEmpty()) {
            for (int i = 0; i < conditions.size(); i++) {
                if (i > 0) sb.append(" & ");
                sb.append(conditions.get(i));
            }
            if (!exceptCardName.isEmpty()) sb.append(" excl.").append(exceptCardName);
            sb.append(" → ");
        }
        if (targetFilter != null) sb.append(targetFilter);
        else sb.append(targetCardName != null ? targetCardName : "?");
        if (powerBonus != 0) sb.append(" +").append(powerBonus);
        if (!grantedTraits.isEmpty()) sb.append(' ').append(grantedTraits);
        if (!specialText.isEmpty()) sb.append(" \"").append(specialText).append('"');
        if (cannotBeChosenBySummons)   sb.append(" NCS");
        if (cannotBeChosenByAbilities) sb.append(" NCA");
        if ((cannotBeChosenBySummons || cannotBeChosenByAbilities) && chosenImmunityOpponentOnly)
            sb.append("(opp)");
        if (chosenImmunitySourceType != null || chosenImmunitySourceCost > 0) {
            sb.append("[from:")
              .append(chosenImmunitySourceType != null ? chosenImmunitySourceType : "any");
            if (chosenImmunitySourceCost > 0) sb.append(" cost").append(chosenImmunitySourceCost);
            sb.append(']');
        }
        if (cannotBeBlocked)           sb.append(" unblockable");
        if (cannotBeBlockedByCost != null)
            sb.append(" not-blocked-cost").append(cannotBeBlockedByCost[0])
              .append(cannotBeBlockedByCost[1] == 1 ? "+" : "-");
        if (instead)                   sb.append(" instead");
        if (maxOpponentHandSize > 0)   sb.append(" oppHand<=").append(maxOpponentHandSize);
        if (minOpponentForwards > 0)   sb.append(" oppFwds>=").append(minOpponentForwards);
        if (maxOwnHandSize > 0)        sb.append(" ownHand<=").append(maxOwnHandSize);
        if (minOwnHandSize > 0)        sb.append(" ownHand>=").append(minOwnHandSize);
        if (minDifferentElementBackups > 0)
            sb.append(" diffElemBkps>=").append(minDifferentElementBackups);
        if (allBackupsDifferentElements) sb.append(" allBkpsDiffElem");
        sb.append(']');
        return sb.toString();
    }

    /**
     * Returns {@code true} when {@code card} is a valid target of this boost.
     *
     * @param jobsStripped whether {@code card} has lost its Jobs for the turn (Exdeath 3-100L);
     *                     a name-targeted boost ignores it, a Job-filtered one stops applying
     */
    public boolean appliesToCard(CardData card, boolean jobsStripped) {
        if (targetFilter != null) return targetFilter.appliesToCard(card, jobsStripped);
        return targetCardName != null && targetCardName.equalsIgnoreCase(card.name());
    }

    /**
     * As {@link #appliesToCard(CardData)}, but resolves a target filter's trait requirement against
     * {@code currentTraits} rather than the card's printed set — "The Forwards with Brave … cannot
     * be chosen" (White Tiger l'Cie Nimbus 23-035H) has to cover a Forward that was granted Brave,
     * and stop covering one whose Brave was stripped.
     */
    public boolean appliesToCard(CardData card, java.util.Set<CardData.Trait> currentTraits,
            boolean jobsStripped) {
        if (targetFilter != null) return targetFilter.appliesToCard(card, currentTraits, jobsStripped);
        return targetCardName != null && targetCardName.equalsIgnoreCase(card.name());
    }
}
