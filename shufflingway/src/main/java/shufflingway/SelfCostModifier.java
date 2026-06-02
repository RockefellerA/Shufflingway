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
        /**
         * Flat delta (×1) if the count of field cards with category {@code param1-part} and
         * type {@code param2-part} is ≥ {@code Integer.parseInt(param1)}.
         * Encoding: {@code param1} = threshold string, {@code param2} = "category|type"
         * (type is singular: "Forward", "Backup", "Character", etc.).
         */
        IF_CONTROL_N_OR_MORE_CATEGORY_TYPE,
        /** Flat delta (×1) if the controller has received ≥ {@code Integer.parseInt(param1)} damage; 0 otherwise. */
        IF_RECEIVED_N_DAMAGE_OR_MORE,
        /** Flat delta (×1) if a Forward the opponent controlled was broken this turn; 0 otherwise. */
        IF_OPPONENT_FORWARD_BROKEN_THIS_TURN,
        /** Flat delta (×1) if a card with job {@code param1} the controller controlled was broken this turn; 0 otherwise. */
        IF_OWN_JOB_BROKEN_THIS_TURN,
        /** Flat delta (×1) if the controller controls zero cards of the type in {@code param1}; 0 otherwise.
         *  param1: singular type ("Forward", "Backup", "Monster", "Character"). */
        IF_CONTROL_NONE_OF_TYPE,
        /** Flat delta (×1) if the opponent has discarded a card from their hand this turn due to abilities; 0 otherwise. */
        IF_OPPONENT_DISCARDED_THIS_TURN,
        /** Flat delta (×1) if the opponent has discarded a card due to the controller's own Summons or abilities this turn; 0 otherwise. */
        IF_OPPONENT_DISCARDED_BY_ME_THIS_TURN,
        /** Flat delta (×1) if the controller has drawn ≥ {@code Integer.parseInt(param1)} cards this turn; 0 otherwise. */
        IF_DRAWN_N_OR_MORE_THIS_TURN,
        /** Flat delta (×1) if the opponent controls more cards of type {@code param1} than the controller; 0 otherwise. */
        IF_OPPONENT_CONTROLS_MORE_TYPE,
        /**
         * Flat delta (×1) if the controller controls a card of category {@code param1-part} and
         * type {@code param2-part} whose element is NOT {@code param2-excluded-part}.
         * Encoding: {@code param1} = "category|type", {@code param2} = excluded element.
         */
        IF_CONTROL_CATEGORY_TYPE_NOT_ELEMENT,
        /** Flat delta (×1) if a Forward the controller controlled formed a party attack this turn; 0 otherwise. */
        IF_OWN_FORWARD_FORMED_PARTY_THIS_TURN,
        /** Flat delta (×1) if the opponent's hand size is ≤ {@code Integer.parseInt(param1)}; 0 otherwise. */
        IF_OPPONENT_HAND_N_OR_LESS,
        /** Flat delta (×1) if ≥ {@code Integer.parseInt(param1)} Forwards (either side) left the field this turn; 0 otherwise. */
        IF_N_OR_MORE_FORWARDS_LEFT_FIELD_THIS_TURN,
        /** Flat delta (×1) if the controller controls ≥ {@code Integer.parseInt(param1)} cards with job {@code param2}; 0 otherwise. */
        IF_CONTROL_N_OR_MORE_JOB,
        /** Flat delta (×1) if a Forward with element {@code param1} entered the controller's field this turn; 0 otherwise. */
        IF_ELEMENT_FORWARD_ENTERED_FIELD_THIS_TURN,
        /** Flat delta (×1) if the opponent controls ≥ {@code Integer.parseInt(param1)} cards of type {@code param2}; 0 otherwise. */
        IF_OPPONENT_CONTROLS_N_OR_MORE_TYPE,
        /** Flat delta (×1) if a Forward entered the controller's field via Warp this turn; 0 otherwise. */
        IF_FORWARD_ENTERED_VIA_WARP_THIS_TURN,
        /** Flat delta (×1) if the controller has ≥ {@code Integer.parseInt(param1)} cards with job {@code param2} in their Break Zone; 0 otherwise. */
        IF_N_OR_MORE_JOB_IN_BZ,
        /** Flat delta (×1) if the controller has received exactly {@code Integer.parseInt(param1)} points of damage; 0 otherwise. */
        IF_RECEIVED_EXACTLY_N_DAMAGE,
        /**
         * Scales by the highest printed cost among the controller's field Forwards with element {@code param1}.
         * Yields 0 if the controller controls no Forwards of that element.
         */
        HIGHEST_COST_ELEMENT_FORWARD,
        /** Flat delta (×1) if the controller controls ≥ {@code Integer.parseInt(param1)} cards of type {@code param2}; 0 otherwise. */
        IF_CONTROL_N_OR_MORE_TYPE,
        /** Flat delta (×1) if the controller controls ≥ {@code Integer.parseInt(param1)} field cards with element+type
         *  encoded in {@code param2} as "element|type"; 0 otherwise. */
        IF_CONTROL_N_OR_MORE_ELEMENT_TYPE,
        /** Flat delta (×1) if both a card named {@code param1} and a card named {@code param2} are in the controller's Break Zone; 0 otherwise. */
        IF_BOTH_NAMES_IN_BZ,
        /**
         * Scales by {@code floor(count / Integer.parseInt(param1))}, where count is field cards the controller
         * controls filtered by element+type encoded in {@code param2} as "element|type".
         */
        PER_N_ELEMENT_TYPE_CONTROLLED,
        /** Flat delta (×1) if the opponent controls ≥ {@code Integer.parseInt(param1)} cards of type {@code param2} more than the controller; 0 otherwise. */
        IF_OPPONENT_CONTROLS_N_MORE_THAN_ME,
        /**
         * Scales by field cards the controller controls of type {@code param2}
         * ("Forward", "Backup", "Character") whose printed cost is ≥ {@code Integer.parseInt(param1)}.
         */
        EACH_TYPE_WITH_MIN_COST,
        /** Scales by the number of Crystal tokens (《C》) the controller currently has. */
        EACH_CRYSTAL_YOU_HAVE,
        /** Scales by the number of Forwards the controller controls. */
        EACH_FORWARD,
        /** Scales by the number of Backups the controller controls. */
        EACH_BACKUP,
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
        /**
         * Scales by field cards the controller controls with element {@code param1}
         * and card type matching {@code param2} ("Forward", "Backup", "Monster", "Character").
         * "Character" counts Forwards + Backups.
         */
        EACH_ELEMENT_TYPE_CONTROLLED,
        /**
         * Scales by the number of distinct elements among the opponent's field cards of type {@code param1}
         * ("Forward", "Backup", "Character"). Multi-element cards contribute each of their elements.
         */
        EACH_DISTINCT_OPPONENT_TYPE_ELEMENT,
        /**
         * Scales by the number of distinct single elements among the controller's Backups
         * (multi-element backups, i.e. those with a "/" in their element field, are excluded).
         * Uses base printed element only, not field-granted elements.
         */
        EACH_DISTINCT_BACKUP_ELEMENT,
    }
}
