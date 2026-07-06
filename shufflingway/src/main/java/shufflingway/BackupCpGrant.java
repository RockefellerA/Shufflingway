package shufflingway;

import java.util.List;

/**
 * A field ability that grants CP production ability to matching Backups.
 *
 * <p>Active while the source card is on the field.  Null filter fields mean "no restriction":
 * a {@code BackupCpGrant(null, null, null, null)} applies to every Backup with any-element CP.
 *
 * <p>Examples:
 * <ul>
 *   <li>"Backups you control can produce CP of any Element."       → all filters null, grantedElements null</li>
 *   <li>"The Job Knight Backups you control can produce CP …"      → jobFilter = "Knight", grantedElements null</li>
 *   <li>"The Category VI Backups you control can produce CP …"     → categoryFilter = "VI", grantedElements null</li>
 *   <li>"The Earth Backups you control can produce CP …"           → elementFilter = "Earth", grantedElements null</li>
 *   <li>"The Job Sky Pirate Backups … can produce Wind or Water CP." → jobFilter = "Sky Pirate", grantedElements = ["Wind","Water"]</li>
 * </ul>
 *
 * @param jobFilter       specific job name, or {@code null} for any job
 * @param categoryFilter  specific category substring, or {@code null} for any category
 * @param elementFilter   specific element name (of the backup itself), or {@code null} for any element
 * @param grantedElements specific elements this backup may produce, or {@code null} for any element
 */
public record BackupCpGrant(String jobFilter, String categoryFilter, String elementFilter, List<String> grantedElements) {

    /** Returns true if this grant allows the backup to produce CP of any element. */
    public boolean isAnyElementGrant() { return grantedElements == null; }

    /** Returns true if this grant applies to {@code backup}. */
    public boolean appliesTo(CardData backup) {
        return CardFilters.meetsJobFilter(backup, jobFilter)
            && CardFilters.meetsCategoryFilter(backup, categoryFilter)
            && CardFilters.meetsElementFilter(backup, elementFilter);
    }
}
