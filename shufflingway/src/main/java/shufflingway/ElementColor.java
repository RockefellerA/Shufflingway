package shufflingway;

import java.awt.Color;

public enum ElementColor {

    FIRE      ("#E5502F"),
    ICE       ("#64C7F1"),
    WIND      ("#33B371"),
    EARTH     ("#FCCF00"),
    LIGHTNING ("#B177B1"),
    WATER     ("#96B8DD"),
    LIGHT     ("#777B7E"),
    DARK      ("#2E2825");

    public final Color color;

    ElementColor(String hex) {
        this.color = Color.decode(hex);
    }

    /** The element's name in title case, e.g. {@code "Fire"}: how a field color is shown and saved. */
    public String title() {
        String n = name();
        return n.charAt(0) + n.substring(1).toLowerCase();
    }

    /**
     * Board field-color menu choices: each element name in title case (e.g. {@code "Fire"}).
     * Shared by the Preferences field-color dropdowns.
     */
    public static String[] boardColorChoices() {
        String[] items = new String[values().length];
        for (int i = 0; i < values().length; i++) items[i] = values()[i].title();
        return items;
    }

    /**
     * {@code saved} as one of {@link #boardColorChoices()}, or {@code fallback} when it names no
     * element: unset, or {@code "Default"}, which the board offered before every field took an
     * element's color.
     */
    public static String boardColorOr(String saved, String fallback) {
        ElementColor c = fromName(saved);
        return c == null ? fallback : c.title();
    }

    /** Returns the ElementColor for the given element name (case-insensitive), or null if not found. */
    public static ElementColor fromName(String name) {
        if (name == null) return null;
        try {
            return ElementColor.valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
