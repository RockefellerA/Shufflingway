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
 * @param exceptCardName non-null → a card of that name may not be dulled for this cost
 *     ("other than Steiner"); the exclusion is by name, so a second copy is barred too
 * @param sameElement true → the cards dulled for this cost must all share an Element with one
 *     another ("3 active Backups of the same Element", 7-128H Yuri). Unlike {@link #element} this
 *     names no particular Element, only that one Element must run through the whole set — so it
 *     constrains the set rather than the card, the way {@link DiscardCost#eachDifferentType()}
 *     does, and belongs to whoever assembles the set rather than to a per-card filter.
 * @param sourceReplacesOne true → dulling the ability's own source may stand in for one of the
 *     {@code count} cards. This is how 7-128H Yuri's "or" reads: "3 active Backups of the same
 *     Element <em>or</em> 2 active Backups of the same Element and Yuri" is the same cost with the
 *     source paying for one of the three, and the source is exempt from {@code sameElement} because
 *     the printed alternative asks it of the Backups only.
 */
public record DullForwardCost(int count, String condition, String element, String cardName,
                               String job, String category, String cardType, String orCardName,
                               String exceptCardName, boolean sameElement,
                               boolean sourceReplacesOne) {

    /** Compat constructor for the 9-arg form; defaults the two set-wide flags to off. */
    public DullForwardCost(int count, String condition, String element, String cardName,
                           String job, String category, String cardType, String orCardName,
                           String exceptCardName) {
        this(count, condition, element, cardName, job, category, cardType, orCardName,
                exceptCardName, false, false);
    }

    /** Compat constructor for the 8-arg form; defaults {@code exceptCardName} to none. */
    public DullForwardCost(int count, String condition, String element, String cardName,
                           String job, String category, String cardType, String orCardName) {
        this(count, condition, element, cardName, job, category, cardType, orCardName, null);
    }

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
