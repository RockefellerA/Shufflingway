package shufflingway;

/**
 * A passive always-on cost reduction: while this card is on the field, the cost to cast matching
 * cards is reduced by {@code amountPerUnit} and/or may be paid with CP of any element.
 *
 * <p>Examples:
 * <ul>
 *   <li>"The cost required to cast [Card Name (<cardName>)] is reduced by 2 (it cannot become 0)."</li>
 *   <li>"The cost required to cast your Water Summons is reduced by 1 (it cannot become 0)."</li>
 *   <li>"The cost required to cast your Summons is reduced by 1 for each Job Summoner forward you control (it cannot become 0)."</li>
 *   <li>"The cost required to cast your Job <jobName> is reduced by 1."</li>
 *   <li>"The cost required to cast your Summons can be paid with CP of any Element."</li>
 *   <li>"The cost required to play your Job Ninja or Card Name Ninja onto the field is reduced by 1 and can be paid with CP of any Element."</li>
 *   <li>"The cost required to cast your Category IV Forwards can be paid with CP of any Element."</li>
 * </ul>
 *
 * @param amountPerUnit   flat reduction amount; if {@code scalingJobFilter} is set, this is
 *                        multiplied by the count of qualifying forwards the caster controls
 * @param floorAtOne      if {@code true}, cost cannot be reduced below 1
 * @param ownerOnly       if {@code true}, only the owning player's casts are affected;
 *                        if {@code false}, applies to both players
 * @param inclForwards    applies when the card being cast is a Forward
 * @param inclBackups     applies when the card being cast is a Backup
 * @param inclMonsters    applies when the card being cast is a Monster
 * @param inclSummons     applies when the card being cast is a Summon
 * @param elementFilter   required element on the card being cast; {@code null} = any
 * @param jobFilter       required job on the card being cast; {@code null} = any
 * @param cardNameFilter  required name of the card being cast; {@code null} = any
 * @param categoryFilter  required category on the card being cast; {@code null} = any
 * @param scalingJobFilter if non-null, multiply {@code amountPerUnit} by the number of active
 *                         forwards with this job the casting player controls
 * @param anyElement      if {@code true}, the cost may be paid with CP of any element
 */
public record FieldCostReduction(
        int     amountPerUnit,
        boolean floorAtOne,
        boolean ownerOnly,
        boolean inclForwards,
        boolean inclBackups,
        boolean inclMonsters,
        boolean inclSummons,
        String  elementFilter,
        String  jobFilter,
        String  cardNameFilter,
        String  categoryFilter,
        String  scalingJobFilter,
        boolean anyElement
) {
    /** Returns {@code true} if this modifier can apply to {@code card}. */
    public boolean matchesCard(CardData card) {
        if (card.isForward() && !inclForwards) return false;
        if (card.isBackup()  && !inclBackups)  return false;
        if (card.isMonster() && !inclMonsters) return false;
        if (card.isSummon()  && !inclSummons)  return false;
        if (elementFilter  != null && !card.containsElement(elementFilter))         return false;
        if (jobFilter      != null && !card.job().equalsIgnoreCase(jobFilter))       return false;
        if (cardNameFilter != null && !card.name().equalsIgnoreCase(cardNameFilter)) return false;
        if (categoryFilter != null
                && !categoryFilter.equalsIgnoreCase(card.category1())
                && !categoryFilter.equalsIgnoreCase(card.category2()))               return false;
        return true;
    }

    /** Applies the flat reduction (ignores any scaling job). */
    public int apply(int originalCost) {
        return apply(originalCost, 1);
    }

    /** Applies the reduction scaled by {@code units} (use 1 for non-scaling reductions). */
    public int apply(int originalCost, int units) {
        int reduced = originalCost - amountPerUnit * units;
        return floorAtOne ? Math.max(1, reduced) : Math.max(0, reduced);
    }
}
