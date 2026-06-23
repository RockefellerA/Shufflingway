package shufflingway;

/**
 * Identifies a single field card as a chosen target for an ability.
 *
 * @param isP1 {@code true} if the card belongs to Player 1; {@code false} for Player 2
 * @param idx  index into the owning player's zone list for this card type
 * @param zone which type of field zone this card occupies
 */
public record ForwardTarget(boolean isP1, int idx, CardZone zone) {

    /** The type of field zone a targeted card occupies. */
    public enum CardZone { FORWARD, BACKUP, MONSTER, BREAK_ZONE }
}
