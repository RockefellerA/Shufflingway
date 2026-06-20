package shufflingway;

/**
 * An ability cost that requires dulling N cards currently on the field.
 * Represents:
 * <ul>
 *   <li>"Dull N [condition] [element] Forward(s)"</li>
 *   <li>"Dull N [condition] Card Name X Forward"</li>
 *   <li>"Dull N [condition] Job X [Forwards]"</li>
 *   <li>"Dull N [condition] Category X [Characters/Forwards/Backups]"</li>
 * </ul>
 *
 * @param cardName non-null → must dull the named card specifically; null → filtered by type/job/category
 * @param job      non-null → cards must have this job; null → any job
 * @param category non-null → cards must have this category; null → any category
 * @param cardType null/"Forward" = only forwards; "Character" = any field card (fwd/bkp/monster)
 */
public record DullForwardCost(int count, String condition, String element, String cardName,
                               String job, String category, String cardType) {

    /** Compat constructor preserving the original 4-arg signature. */
    public DullForwardCost(int count, String condition, String element, String cardName) {
        this(count, condition, element, cardName, null, null, null);
    }
}
