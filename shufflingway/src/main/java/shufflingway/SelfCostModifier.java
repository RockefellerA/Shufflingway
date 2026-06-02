package shufflingway;

/**
 * A cost modifier printed on a card that adjusts its own play/cast cost based on current
 * game state.  These differ from {@link FieldCostReduction} (which is on a field card
 * affecting other cards' costs) in that this modifier lives on the card itself and is
 * evaluated at play time.
 *
 * <p>Examples:
 * <ul>
 *   <li>"The cost required to play <cardName> onto the field is reduced by 1 for each [Category (VII)] Forward you control."</li>
 *   <li>"The cost required to play <cardName> onto the field is reduced by 1 for each point of damage you have received."</li>
 *   <li>"The cost required to play <cardName> onto the field is reduced by 1 for each Forward you control. (it cannot become 0)."</li>
 *   <li>"If you have cast a Summon this turn, the cost required to play <cardName> onto the field is reduced by 2 (it cannot become 0)."</li>
 *   <li>"If you control Card Name Leo, the cost required to play <cardName> onto the field is reduced by 2."</li>
 *   <li>"The cost required to play <cardName> onto the field is reduced by 1 for each Card Name <cardName> in your Break Zone."</li>
 *   <li>"The cost required to play <cardName> onto the field is reduced by 1 for every 5 cards in your Break Zone."</li>
 *   <li>"The cost required to play <cardName> onto the field is reduced by 1 for each Job Guardian you control."</li>
 *   <li>"The cost required to play <cardName> onto the field is reduced by 1 for each Job Dragoon or Card Name Dragoon you control (it cannot become 0)."</li>
 *   <li>"The cost required to cast <cardName> is increased by 1 for each Forward you control."</li>
 *   <li>"If you have received 5 points of damage or more, the cost required to cast <cardName> is reduced by 2."</li>
 *   <li>"If a Forward opponent controlled was put from the field into the Break Zone this turn, the cost required to play <cardName> onto the field is reduced by 3."</li>
 *   <li>"The cost required to play <cardName> onto the field is reduced by 1 for each Card Name Lann or Card Name Reynn you control."</li>
 *   <li>"The cost required to play <cardName> onto the field is reduced by 1 for each Category WOFF Character you control."</li>
 *   <li>"The cost required to play <cardName> onto the field is reduced by 1 for every 2 Lightning Summons in your Break Zone (it cannot become 0)."</li>
 *   <li>"The cost required to cast <cardName> is reduced by 2 for each card you have cast this turn (it cannot become 1 or less)."</li>
 * </ul>
 *
 * @param amountPerUnit  adjustment amount; multiplied by the count from {@link ScalingType}
 * @param minCost        minimum effective cost after this modifier; 0 = no floor beyond 0,
 *                       1 = "it cannot become 0", 2 = "it cannot become 1 or less"
 * @param isIncrease     if {@code true}, this modifier raises the cost; otherwise lowers it
 * @param scalingType    how to compute the multiplier unit count from game state
 * @param param1         primary parameter: job name, category, card name, BZ divisor string, or
 *                       damage threshold; encoding varies per {@link ScalingType}
 * @param param2         secondary parameter: second name in name-or-name / job-or-name patterns,
 *                       or pipe-separated "element|type" filter for {@link ScalingType#PER_N_FILTERED_BZ_CARDS}
 */
public record SelfCostModifier(
        int         amountPerUnit,
        int         minCost,
        boolean     isIncrease,
        ScalingType scalingType,
        String      param1,
        String      param2
) {
    public enum ScalingType {
        /** Flat delta (×1) if a Summon was cast by the controller this turn; 0 otherwise. */
        IF_CAST_SUMMON_THIS_TURN,
        /** Flat delta (×1) if you control a field card named {@code param1}; 0 otherwise. */
        IF_CONTROL_NAME,
        /** Flat delta (×1) if the controller has received ≥ {@code Integer.parseInt(param1)} damage; 0 otherwise. */
        IF_RECEIVED_N_DAMAGE_OR_MORE,
        /** Flat delta (×1) if a Forward the opponent controlled was broken this turn; 0 otherwise. */
        IF_OPPONENT_FORWARD_BROKEN_THIS_TURN,
        /** Scales by the number of Forwards the controller controls. */
        EACH_FORWARD,
        /** Scales by Forwards the controller controls that belong to category {@code param1}. */
        EACH_FORWARD_WITH_CATEGORY,
        /**
         * Scales by field cards the controller controls with category {@code param1}
         * and type matching {@code param2} ({@code null}/empty/"Forward" = Forwards only,
         * "Character" = Forwards + Backups).
         */
        EACH_CATEGORY_TYPE_CONTROLLED,
        /** Scales by field cards the controller controls with job {@code param1}. */
        EACH_FORWARD_WITH_JOB,
        /** Scales by field cards with job {@code param1} OR name {@code param2}. */
        EACH_FORWARD_WITH_JOB_OR_NAME,
        /** Scales by field Forwards the controller controls with name {@code param1} OR name {@code param2}. */
        EACH_NAME_OR_NAME_CONTROLLED,
        /** Scales by the number of points of damage the controller has received. */
        EACH_DAMAGE_RECEIVED,
        /** Scales by the count of cards named {@code param1} in the controller's Break Zone. */
        EACH_NAME_IN_BZ,
        /** Scales by {@code floor(BreakZone.size() / Integer.parseInt(param1))}. */
        PER_N_BZ_CARDS,
        /**
         * Scales by {@code floor(filteredBZ.size() / Integer.parseInt(param1))}, where the BZ
         * is filtered by the spec in {@code param2} (pipe-separated "element|type",
         * e.g. {@code "Lightning|Summon"}; element may be empty for type-only filtering).
         */
        PER_N_FILTERED_BZ_CARDS,
        /** Scales by the number of cards in the opponent's hand. */
        EACH_OPPONENT_HAND_CARD,
        /** Scales by the number of cards the controller has cast this turn (before this card). */
        EACH_CARD_CAST_THIS_TURN,
    }
}
