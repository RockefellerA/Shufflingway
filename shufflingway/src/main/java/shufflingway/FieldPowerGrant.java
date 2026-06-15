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
        int     costFilter       // -1 = any cost; N = card must have exactly cost N
) {
    public FieldPowerGrant {
        grantedTraits = Set.copyOf(grantedTraits);
        if (exceptCardName == null) exceptCardName = "";
    }

    /** Convenience constructor for the common same-side ("you control") grant form (no cost filter). */
    public FieldPowerGrant(String jobFilter, String categoryFilter,
            boolean inclForwards, boolean inclBackups, boolean inclMonsters,
            String exceptCardName, int powerBonus, Set<CardData.Trait> grantedTraits) {
        this(jobFilter, categoryFilter, inclForwards, inclBackups, inclMonsters,
                exceptCardName, powerBonus, grantedTraits, false, -1);
    }

    /** Convenience constructor for the opponent-debuff form (no cost filter). */
    public FieldPowerGrant(String jobFilter, String categoryFilter,
            boolean inclForwards, boolean inclBackups, boolean inclMonsters,
            String exceptCardName, int powerBonus, Set<CardData.Trait> grantedTraits,
            boolean affectsOpponent) {
        this(jobFilter, categoryFilter, inclForwards, inclBackups, inclMonsters,
                exceptCardName, powerBonus, grantedTraits, affectsOpponent, -1);
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
        if (costFilter >= 0 && card.cost() != costFilter) return false;
        return CardFilters.meetsJobFilter(card, jobFilter)
            && CardFilters.meetsCategoryFilter(card, categoryFilter);
    }
}
