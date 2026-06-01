package shufflingway;

/**
 * A passive always-on grant: while this card is on the field, the Priming cost of matching
 * cards the owning player controls may be paid with CP of any element.
 *
 * <p>Examples:
 * <ul>
 *   <li>"The Priming cost of the Forwards you control can be paid with CP of any Element."</li>
 *   <li>"The Priming cost of the Characters you control can be paid with CP of any Element."</li>
 * </ul>
 */
public record FieldPrimingAnyElement(
        boolean inclForwards,
        boolean inclBackups,
        boolean inclMonsters
) {
    /** Returns {@code true} if this grant applies to {@code card}. */
    public boolean appliesToCard(CardData card) {
        if (card.isForward() && !inclForwards) return false;
        if (card.isBackup()  && !inclBackups)  return false;
        if (card.isMonster() && !inclMonsters) return false;
        return true;
    }
}
