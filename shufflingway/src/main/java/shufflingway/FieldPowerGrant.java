package shufflingway;

import java.util.Set;

/**
 * A passive always-on grant: "The [Job X / Category Y] [type] [other than Z] you control
 * gain +N power [and Trait]." or its opponent-side counterpart "The [filter] [type] opponent
 * controls lose N power."
 *
 * <p>Active while the owning card is on the field. When {@link #affectsOpponent} is {@code false}
 * the grant scopes to the same player's side (the default); when {@code true} the grant scopes
 * to the opposing player's matching cards (in which case {@link #powerBonus} is typically negative).
 * When the owning card leaves the field the bonus ceases immediately.
 */
public record FieldPowerGrant(
        String  jobFilter,       // null = any job
        String  categoryFilter,  // null = any category
        boolean inclForwards,
        boolean inclBackups,
        boolean inclMonsters,
        String  exceptCardName,  // excluded target name ("other than X"); "" = none
        int     powerBonus,
        Set<CardData.Trait> grantedTraits,
        boolean affectsOpponent,
        int     costFilter,      // -1 = any cost; N = filter applies at value N (exact, or less/more per costCmp)
        String  costCmp          // null = exact match; "less" = cost ≤ N; "more" = cost ≥ N
) {
    public FieldPowerGrant {
        grantedTraits = Set.copyOf(grantedTraits);
        if (exceptCardName == null) exceptCardName = "";
    }

    /** Convenience constructor without {@code costCmp}; defaults it to {@code null} (exact match). */
    public FieldPowerGrant(String jobFilter, String categoryFilter,
            boolean inclForwards, boolean inclBackups, boolean inclMonsters,
            String exceptCardName, int powerBonus, Set<CardData.Trait> grantedTraits,
            boolean affectsOpponent, int costFilter) {
        this(jobFilter, categoryFilter, inclForwards, inclBackups, inclMonsters,
                exceptCardName, powerBonus, grantedTraits, affectsOpponent, costFilter, null);
    }

    /** Convenience constructor for the common same-side ("you control") grant form (no cost filter). */
    public FieldPowerGrant(String jobFilter, String categoryFilter,
            boolean inclForwards, boolean inclBackups, boolean inclMonsters,
            String exceptCardName, int powerBonus, Set<CardData.Trait> grantedTraits) {
        this(jobFilter, categoryFilter, inclForwards, inclBackups, inclMonsters,
                exceptCardName, powerBonus, grantedTraits, false, -1, null);
    }

    /** Convenience constructor for the opponent-debuff form (no cost filter). */
    public FieldPowerGrant(String jobFilter, String categoryFilter,
            boolean inclForwards, boolean inclBackups, boolean inclMonsters,
            String exceptCardName, int powerBonus, Set<CardData.Trait> grantedTraits,
            boolean affectsOpponent) {
        this(jobFilter, categoryFilter, inclForwards, inclBackups, inclMonsters,
                exceptCardName, powerBonus, grantedTraits, affectsOpponent, -1, null);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        if (affectsOpponent) sb.append("opp:");
        if (jobFilter      != null) sb.append(jobFilter).append(' ');
        if (categoryFilter != null) sb.append(categoryFilter).append(' ');
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
        return CardFilters.meetsJobFilter(card, jobFilter)
            && CardFilters.meetsCategoryFilter(card, categoryFilter);
    }
}
