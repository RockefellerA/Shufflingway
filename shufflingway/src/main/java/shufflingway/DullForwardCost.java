package shufflingway;

/**
 * An ability cost that requires dulling N cards currently on the field.
 * Represents:
 * <ul>
 *   <li>"Dull N [condition] [element] Forward(s)"</li>
 *   <li>"Dull N [condition] Card Name X Forward"</li>
 *   <li>"Dull N [condition] Job X [Forwards]"</li>
 *   <li>"Dull N [condition] Category X [Characters/Forwards/Backups]"</li>
 *   <li>"Dull N [condition] Job X and/or Card Name Y"</li>
 * </ul>
 *
 * @param cardName   non-null → must dull the named card specifically; null → filtered by type/job/category
 * @param job        non-null → cards must have this job; null → any job
 * @param category   non-null → cards must have this category; null → any category
 * @param cardType   null/"Forward" = only forwards; "Character" = any field card (fwd/bkp/monster)
 * @param orCardName non-null → card also matches if its name equals this (used with job for "Job X and/or Card Name Y")
 */
public record DullForwardCost(int count, String condition, String element, String cardName,
                               String job, String category, String cardType, String orCardName) {

    /** Compat constructor preserving the original 4-arg signature. */
    public DullForwardCost(int count, String condition, String element, String cardName) {
        this(count, condition, element, cardName, null, null, null, null);
    }

    /** Compat constructor preserving the 7-arg signature. */
    public DullForwardCost(int count, String condition, String element, String cardName,
                           String job, String category, String cardType) {
        this(count, condition, element, cardName, job, category, cardType, null);
    }
}
