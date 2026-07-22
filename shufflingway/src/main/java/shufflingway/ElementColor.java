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

    /**
     * Board field-color menu choices: {@code "Default"} followed by each element name in title
     * case (e.g. {@code "Fire"}). Shared by the Preferences field-color dropdowns.
     */
    public static String[] boardColorChoices() {
        String[] items = new String[values().length + 1];
        items[0] = "Default";
        for (int i = 0; i < values().length; i++) {
            String n = values()[i].name();
            items[i + 1] = n.charAt(0) + n.substring(1).toLowerCase();
        }
        return items;
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
