package shufflingway;

import java.util.Locale;

/**
 * How the board marks a Forward that is not ready yet: one that entered the field too recently to
 * attack or to pay a 《Dull》 cost, and has no Haste. Chosen in Preferences.
 *
 * <ul>
 *   <li>{@link #NONE} — not marked; the rule still applies.
 *   <li>{@link #TAB} — a Preparing trait tab, a golden hourglass, among the card's other tabs.
 *   <li>{@link #ATB} — an ATB gauge off the card's centre-facing edge that fills as the phases of
 *       the wait go by ({@link shufflingway.graphics.NotReadyMarker}).
 * </ul>
 */
public enum NotReadyIndicator {

    NONE("None"),
    TAB("Trait tab"),
    ATB("ATB gauge");

    private final String label;

    NotReadyIndicator(String label) {
        this.label = label;
    }

    /** The name shown in Preferences. */
    public String label() { return label; }

    /** {@link #label}, so a combo box lists the indicators by name. */
    @Override
    public String toString() { return label; }

    /** The name kept in settings; {@link #parse} reads it back. */
    public String id() { return name(); }

    /** The indicator {@code id} names, or {@link #NONE} for none or one this build does not know. */
    public static NotReadyIndicator parse(String id) {
        if (id == null) return NONE;
        try {
            return valueOf(id.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return NONE;
        }
    }
}
