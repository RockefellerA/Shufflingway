package shufflingway;

/**
 * A "during this turn, your next [filter] costs N less" modifier.
 *
 * <p>Created when an ability fires; consumed the first time a matching card is cast,
 * or discarded at end of turn if unused.
 */
public record CostReductionModifier(
        int     amount,
        boolean floorAtOne,      // true = cost cannot be reduced below 1
        boolean inclForwards,
        boolean inclBackups,
        boolean inclMonsters,
        boolean inclSummons,
        String  elementFilter,   // null = any element
        String  jobFilter,       // null = any job
        String  cardNameFilter,  // null = any name
        String  categoryFilter   // null = any category
) {
    /** Returns {@code true} if this modifier can apply to {@code card}. */
    public boolean matches(CardData card) {
        if (card.isForward() && !inclForwards) return false;
        if (card.isBackup()  && !inclBackups)  return false;
        if (card.isMonster() && !inclMonsters) return false;
        if (card.isSummon()  && !inclSummons)  return false;
        if (elementFilter  != null && !card.containsElement(elementFilter))          return false;
        if (jobFilter      != null && !card.job().equalsIgnoreCase(jobFilter))        return false;
        if (cardNameFilter != null && !card.name().equalsIgnoreCase(cardNameFilter))  return false;
        if (categoryFilter != null
                && !categoryFilter.equalsIgnoreCase(card.category1())
                && !categoryFilter.equalsIgnoreCase(card.category2()))               return false;
        return true;
    }

    /** Returns the effective cast cost after this reduction. */
    public int apply(int originalCost) {
        int reduced = originalCost - amount;
        return floorAtOne ? Math.max(1, reduced) : Math.max(0, reduced);
    }
}
