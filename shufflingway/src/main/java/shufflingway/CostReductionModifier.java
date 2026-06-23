package shufflingway;

/**
 * A "this turn, [filter] costs N less" modifier.
 *
 * <p>Created when an ability fires; removed at end of turn.
 * When {@link #consumeOnUse} is {@code true} it is also consumed the first time a matching
 * card is cast ("your next X" semantics); when {@code false} it applies to every matching
 * play this turn ("this turn" semantics, e.g. Tilika).
 */
public record CostReductionModifier(
        int     amount,
        boolean floorAtOne,      // true = cost cannot be reduced below 1
        boolean consumeOnUse,    // true = consumed after first matching cast; false = persists until EOT
        boolean inclForwards,
        boolean inclBackups,
        boolean inclMonsters,
        boolean inclSummons,
        String  elementFilter,   // null = any element
        String  jobFilter,       // null = any job
        String  cardNameFilter,  // null = any name
        String  categoryFilter,  // null = any category
        boolean jobOrName        // when true: jobFilter and cardNameFilter use OR instead of AND
) {
    /** Returns {@code true} if this modifier can apply to {@code card}. */
    public boolean matches(CardData card) {
        if (card.isForward() && !inclForwards) return false;
        if (card.isBackup()  && !inclBackups)  return false;
        if (card.isMonster() && !inclMonsters) return false;
        if (card.isSummon()  && !inclSummons)  return false;
        if (elementFilter  != null && !card.containsElement(elementFilter))          return false;
        if (jobOrName) {
            boolean jobOk  = jobFilter      == null || card.hasJob(jobFilter);
            boolean nameOk = cardNameFilter == null || card.name().equalsIgnoreCase(cardNameFilter);
            if (!jobOk && !nameOk) return false;
        } else {
            if (jobFilter      != null && !card.hasJob(jobFilter))                        return false;
            if (cardNameFilter != null && !card.name().equalsIgnoreCase(cardNameFilter))  return false;
        }
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
