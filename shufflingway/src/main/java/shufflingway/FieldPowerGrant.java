package shufflingway;

import java.util.Set;

/**
 * A passive always-on grant: "The [Element / Job X / Category Y / Card Name Z] [type] [other than W]
 * you control gain +N power [and Trait]." or its opponent-side counterpart "The [filter] [type]
 * opponent controls lose N power."
 *
 * <p>Active while the owning card is on the field. When {@link #affectsOpponent} is {@code false}
 * the grant scopes to the same player's side (the default); when {@code true} the grant scopes
 * to the opposing player's matching cards (in which case {@link #powerBonus} is typically negative).
 * When the owning card leaves the field the bonus ceases immediately.
 */
public record FieldPowerGrant(
        String  jobFilter,           // null = any job
        String  categoryFilter,      // null = any category
        boolean inclForwards,
        boolean inclBackups,
        boolean inclMonsters,
        String  exceptCardName,      // excluded target name ("other than X"); "" = none
        int     powerBonus,
        Set<CardData.Trait> grantedTraits,
        boolean affectsOpponent,
        int     costFilter,          // -1 = any cost; N = filter applies at value N (exact, or less/more per costCmp)
        String  costCmp,             // null = exact match; "less" = cost ≤ N; "more" = cost ≥ N
        String  elementFilter,       // null = any element; e.g. "Ice", "Fire|Water"
        String  inclCardName,        // null = any name; non-null = only cards whose name matches this
        int     minBzSize,           // 0 = no restriction; >0 = grant applies only when own BZ has ≥ this many total cards
        int     minBzFilterCount,    // 0 = no restriction; >0 = requires this many filtered BZ cards (see bzFilterJob/bzFilterFwds)
        String  bzFilterJob,         // job filter for BZ cards counted by minBzFilterCount; null = any job
        boolean bzFilterFwds,        // true = only count Forwards when evaluating minBzFilterCount
        boolean yourTurnOnly,        // true = trait/power grant applies only during the controller's turn
        int     minDistinctElements, // 0 = no restriction; >0 = grant applies only when controller has ≥ this many distinct elements among the target type
        int     exBurstDmgPerGroup,  // 0 = unused; >0 = bonus per group of exBurstDmgGroupSize EX Burst cards in own damage zone
        int     exBurstDmgGroupSize, // group size for EX Burst damage zone scaling (e.g. 3 means +N per 3 EX Burst cards)
        int     minDamageThreshold,  // 0 = no restriction; >0 = grant applies only when controller's damage zone has ≥ this many cards
        int     maxDamageThreshold   // 0 = no restriction; >0 = grant applies only when controller's damage zone has < this many cards
) {
    public FieldPowerGrant {
        grantedTraits = Set.copyOf(grantedTraits);
        if (exceptCardName == null) exceptCardName = "";
    }

    /** Compatibility constructor preserving the prior 21-arg form; defaults {@code minDamageThreshold/maxDamageThreshold} to 0. */
    public FieldPowerGrant(String jobFilter, String categoryFilter,
            boolean inclForwards, boolean inclBackups, boolean inclMonsters,
            String exceptCardName, int powerBonus, Set<CardData.Trait> grantedTraits,
            boolean affectsOpponent, int costFilter, String costCmp, String elementFilter,
            String inclCardName, int minBzSize, int minBzFilterCount, String bzFilterJob,
            boolean bzFilterFwds, boolean yourTurnOnly, int minDistinctElements,
            int exBurstDmgPerGroup, int exBurstDmgGroupSize) {
        this(jobFilter, categoryFilter, inclForwards, inclBackups, inclMonsters,
                exceptCardName, powerBonus, grantedTraits, affectsOpponent, costFilter, costCmp, elementFilter,
                inclCardName, minBzSize, minBzFilterCount, bzFilterJob, bzFilterFwds, yourTurnOnly,
                minDistinctElements, exBurstDmgPerGroup, exBurstDmgGroupSize, 0, 0);
    }

    /** Compatibility constructor preserving the prior 19-arg form; defaults {@code exBurstDmgPerGroup/GroupSize} to 0/1. */
    public FieldPowerGrant(String jobFilter, String categoryFilter,
            boolean inclForwards, boolean inclBackups, boolean inclMonsters,
            String exceptCardName, int powerBonus, Set<CardData.Trait> grantedTraits,
            boolean affectsOpponent, int costFilter, String costCmp, String elementFilter,
            String inclCardName, int minBzSize, int minBzFilterCount, String bzFilterJob,
            boolean bzFilterFwds, boolean yourTurnOnly, int minDistinctElements) {
        this(jobFilter, categoryFilter, inclForwards, inclBackups, inclMonsters,
                exceptCardName, powerBonus, grantedTraits, affectsOpponent, costFilter, costCmp, elementFilter,
                inclCardName, minBzSize, minBzFilterCount, bzFilterJob, bzFilterFwds, yourTurnOnly,
                minDistinctElements, 0, 1, 0, 0);
    }

    /** Compatibility constructor preserving the prior 18-arg form; defaults {@code minDistinctElements} to 0. */
    public FieldPowerGrant(String jobFilter, String categoryFilter,
            boolean inclForwards, boolean inclBackups, boolean inclMonsters,
            String exceptCardName, int powerBonus, Set<CardData.Trait> grantedTraits,
            boolean affectsOpponent, int costFilter, String costCmp, String elementFilter,
            String inclCardName, int minBzSize, int minBzFilterCount, String bzFilterJob,
            boolean bzFilterFwds, boolean yourTurnOnly) {
        this(jobFilter, categoryFilter, inclForwards, inclBackups, inclMonsters,
                exceptCardName, powerBonus, grantedTraits, affectsOpponent, costFilter, costCmp, elementFilter,
                inclCardName, minBzSize, minBzFilterCount, bzFilterJob, bzFilterFwds, yourTurnOnly, 0);
    }

    /** Convenience constructor without BZ conditions; defaults {@code minBzSize/minBzFilterCount} to 0. */
    public FieldPowerGrant(String jobFilter, String categoryFilter,
            boolean inclForwards, boolean inclBackups, boolean inclMonsters,
            String exceptCardName, int powerBonus, Set<CardData.Trait> grantedTraits,
            boolean affectsOpponent, int costFilter, String costCmp, String elementFilter,
            String inclCardName) {
        this(jobFilter, categoryFilter, inclForwards, inclBackups, inclMonsters,
                exceptCardName, powerBonus, grantedTraits, affectsOpponent, costFilter, costCmp, elementFilter,
                inclCardName, 0, 0, null, false, false, 0);
    }

    /** Convenience constructor without {@code inclCardName} or any BZ conditions. */
    public FieldPowerGrant(String jobFilter, String categoryFilter,
            boolean inclForwards, boolean inclBackups, boolean inclMonsters,
            String exceptCardName, int powerBonus, Set<CardData.Trait> grantedTraits,
            boolean affectsOpponent, int costFilter, String costCmp, String elementFilter) {
        this(jobFilter, categoryFilter, inclForwards, inclBackups, inclMonsters,
                exceptCardName, powerBonus, grantedTraits, affectsOpponent, costFilter, costCmp, elementFilter,
                null, 0, 0, null, false, false, 0);
    }

    /** Convenience constructor without {@code elementFilter} or {@code inclCardName}. */
    public FieldPowerGrant(String jobFilter, String categoryFilter,
            boolean inclForwards, boolean inclBackups, boolean inclMonsters,
            String exceptCardName, int powerBonus, Set<CardData.Trait> grantedTraits,
            boolean affectsOpponent, int costFilter, String costCmp) {
        this(jobFilter, categoryFilter, inclForwards, inclBackups, inclMonsters,
                exceptCardName, powerBonus, grantedTraits, affectsOpponent, costFilter, costCmp, null, null,
                0, 0, null, false, false, 0);
    }

    /** Convenience constructor without {@code costCmp}, {@code elementFilter}, or {@code inclCardName}. */
    public FieldPowerGrant(String jobFilter, String categoryFilter,
            boolean inclForwards, boolean inclBackups, boolean inclMonsters,
            String exceptCardName, int powerBonus, Set<CardData.Trait> grantedTraits,
            boolean affectsOpponent, int costFilter) {
        this(jobFilter, categoryFilter, inclForwards, inclBackups, inclMonsters,
                exceptCardName, powerBonus, grantedTraits, affectsOpponent, costFilter, null, null, null,
                0, 0, null, false, false, 0);
    }

    /** Convenience constructor for the common same-side ("you control") grant form (no cost filter). */
    public FieldPowerGrant(String jobFilter, String categoryFilter,
            boolean inclForwards, boolean inclBackups, boolean inclMonsters,
            String exceptCardName, int powerBonus, Set<CardData.Trait> grantedTraits) {
        this(jobFilter, categoryFilter, inclForwards, inclBackups, inclMonsters,
                exceptCardName, powerBonus, grantedTraits, false, -1, null, null, null,
                0, 0, null, false, false, 0);
    }

    /** Convenience constructor for the opponent-debuff form (no cost filter). */
    public FieldPowerGrant(String jobFilter, String categoryFilter,
            boolean inclForwards, boolean inclBackups, boolean inclMonsters,
            String exceptCardName, int powerBonus, Set<CardData.Trait> grantedTraits,
            boolean affectsOpponent) {
        this(jobFilter, categoryFilter, inclForwards, inclBackups, inclMonsters,
                exceptCardName, powerBonus, grantedTraits, affectsOpponent, -1, null, null, null,
                0, 0, null, false, false, 0);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        if (affectsOpponent) sb.append("opp:");
        if (elementFilter  != null) sb.append(elementFilter).append(' ');
        if (jobFilter      != null) sb.append(jobFilter).append(' ');
        if (categoryFilter != null) sb.append(categoryFilter).append(' ');
        if (inclCardName   != null) sb.append("CardName:").append(inclCardName).append(' ');
        if (costFilter >= 0) {
            sb.append("cost").append(costFilter);
            if      ("less".equalsIgnoreCase(costCmp)) sb.append('-');
            else if ("more".equalsIgnoreCase(costCmp)) sb.append('+');
            sb.append(' ');
        }
        if      ( inclForwards && !inclBackups && !inclMonsters) sb.append("Fwds");
        else if (!inclForwards &&  inclBackups && !inclMonsters) sb.append("Bkps");
        else if (!inclForwards && !inclBackups &&  inclMonsters) sb.append("Mons");
        else if ( inclForwards &&  inclBackups &&  inclMonsters) sb.append("cards");
        else {
            if (inclForwards) sb.append("Fwd/");
            if (inclBackups)  sb.append("Bkp/");
            if (inclMonsters) sb.append("Mon/");
            if (!sb.isEmpty() && sb.charAt(sb.length() - 1) == '/') sb.deleteCharAt(sb.length() - 1);
        }
        if (!exceptCardName.isEmpty()) sb.append(" excl.").append(exceptCardName);
        if (powerBonus != 0) sb.append(" +").append(powerBonus);
        if (!grantedTraits.isEmpty()) sb.append(' ').append(grantedTraits);
        if (minBzSize > 0) sb.append(" ifBZ>=").append(minBzSize);
        if (minBzFilterCount > 0) {
            sb.append(" ifBZ(");
            if (bzFilterJob != null) sb.append("Job:").append(bzFilterJob).append(' ');
            if (bzFilterFwds) sb.append("Fwd");
            sb.append(">=").append(minBzFilterCount).append(')');
        }
        if (yourTurnOnly) sb.append(" yourTurnOnly");
        if (minDistinctElements > 0) sb.append(" ifDistinctElem>=").append(minDistinctElements);
        if (exBurstDmgPerGroup > 0) sb.append(" +").append(exBurstDmgPerGroup).append("/").append(exBurstDmgGroupSize).append("EXBurstDmg");
        if (minDamageThreshold > 0) sb.append(" ifDmg>=").append(minDamageThreshold);
        if (maxDamageThreshold > 0) sb.append(" ifDmg<").append(maxDamageThreshold);
        return sb.toString();
    }

    /**
     * Returns {@code true} if this grant applies to {@code card}.
     * Does not check which side of the field the card is on — callers must ensure
     * the card and the grant source belong to the same player when {@link #affectsOpponent} is
     * {@code false}, or to opposing players when {@code true}.
     */
    public boolean appliesToCard(CardData card) {
        if (!exceptCardName.isEmpty() && CardFilters.meetsCardNameFilter(card, exceptCardName)) return false;
        boolean typeOk = (inclForwards && card.isForward())
                      || (inclBackups  && card.isBackup())
                      || (inclMonsters && (card.isMonster() || card.alsoCountsAsMonster()));
        if (!typeOk) return false;
        if (costFilter >= 0) {
            int c = card.cost();
            if      (costCmp == null)                    { if (c != costFilter) return false; }
            else if ("less".equalsIgnoreCase(costCmp))   { if (c >  costFilter) return false; }
            else                                         { if (c <  costFilter) return false; }
        }
        return CardFilters.meetsElementFilter(card, elementFilter)
            && CardFilters.meetsJobFilter(card, jobFilter)
            && CardFilters.meetsCategoryFilter(card, categoryFilter)
            && CardFilters.meetsCardNameFilter(card, inclCardName);
    }
}
