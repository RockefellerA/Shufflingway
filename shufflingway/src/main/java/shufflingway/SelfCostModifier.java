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
 * </ul>
 *
 * @param amountPerUnit  adjustment amount; multiplied by the count from {@link ScalingType}
 * @param floorAtOne     if {@code true}, the total play cost cannot be reduced below 1
 * @param isIncrease     if {@code true}, this modifier raises the cost; otherwise lowers it
 * @param scalingType    how to compute the multiplier unit count from game state
 * @param param1         primary parameter: job name, category, card name, or BZ divisor string
 * @param param2         secondary parameter: second name in "Job X or Card Name Y" patterns; else {@code null}
 */
public record SelfCostModifier(
        int         amountPerUnit,
        boolean     floorAtOne,
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
        /** Scales by the number of Forwards the controller controls. */
        EACH_FORWARD,
        /** Scales by Forwards the controller controls that belong to category {@code param1}. */
        EACH_FORWARD_WITH_CATEGORY,
        /** Scales by field cards the controller controls with job {@code param1}. */
        EACH_FORWARD_WITH_JOB,
        /** Scales by field cards with job {@code param1} OR name {@code param2}. */
        EACH_FORWARD_WITH_JOB_OR_NAME,
        /** Scales by the number of damage points the controller has received. */
        EACH_DAMAGE_RECEIVED,
        /** Scales by the count of cards named {@code param1} in the controller's Break Zone. */
        EACH_NAME_IN_BZ,
        /** Scales by {@code floor(BreakZone.size() / Integer.parseInt(param1))}. */
        PER_N_BZ_CARDS,
        /** Scales by the number of cards in the opponent's hand. */
        EACH_OPPONENT_HAND_CARD,
    }
}
