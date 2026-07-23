package shufflingway;

/**
 * A passive, always-on grant conditioned on a named counter:
 * "Each Forward you control with a [counterName] Counter on it gains [+N power | \"ability\"]."
 *
 * <p>Active while the owning card is on the field; it applies to each Forward the same player
 * controls that currently carries at least one counter named {@link #counterName}. Exactly one of
 * {@link #powerBonus} (non-zero) or {@link #grantedAbilityText} (non-null) is populated:
 * <ul>
 *   <li>a power grant — e.g. Legendary Turk: "…gains +5000 power.";</li>
 *   <li>an ability grant — e.g. Kimahri: "…gains \"If this Forward is dealt damage by your
 *       opponent's Summons or abilities, the damage becomes 0 instead.\""</li>
 * </ul>
 */
public record CounterGrant(
        String counterName,        // e.g. "Turks", "Guardian", "Ronso"
        int    powerBonus,         // 0 when this grant is an ability grant
        String grantedAbilityText  // null when this grant is a power grant; else the granted ability text
) {}
