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

    // ── Wire encoding ────────────────────────────────────────────────────────
    //
    // A networked choice answer travels as a list of small integers, so a target picked by one
    // player has to pack into one. Slot indices already mean the same thing on both clients; the
    // side does not, which is what flipChoiceSide exists for.

    private static final int SIDE_BIT   = 1 << 12;
    private static final int ZONE_SHIFT = 8;
    private static final int ZONE_MASK  = 0xF;
    private static final int IDX_MASK   = 0xFF;

    /**
     * Packs this target into the single integer a choice answer carries it as.
     *
     * <p>Written from the packing client's own point of view: the side bit says "mine" or
     * "theirs" as that client sees it, so a code crossing the wire must be run through
     * {@link #flipChoiceSide} before it is read.
     */
    public int choiceCode() {
        return (isP1 ? SIDE_BIT : 0) | (zone.ordinal() << ZONE_SHIFT) | (idx & IDX_MASK);
    }

    /**
     * Unpacks a {@link #choiceCode()}, or {@code null} when the code names no zone that exists.
     *
     * <p>Nullable rather than throwing because this is the first thing done to a number a remote
     * client sent: a malformed one is a desync for the caller to report, not an exception to
     * unwind an effect that is halfway resolved.
     */
    public static ForwardTarget fromChoiceCode(int code) {
        int ordinal = (code >> ZONE_SHIFT) & ZONE_MASK;
        if (ordinal >= CardZone.values().length) return null;
        return new ForwardTarget((code & SIDE_BIT) != 0, code & IDX_MASK, CardZone.values()[ordinal]);
    }

    /**
     * This slot as one integer with no side: the zone and the index. What an attacking party's
     * member list carries, since a party can mix Forwards with Monsters and Backups acting as
     * Forwards. FORWARD is zone 0, so a Forward's slot code is its plain index, and a party of
     * Forwards reads exactly as the index list it always was — on this client and on the wire.
     */
    public int slotCode() {
        return (zone.ordinal() << ZONE_SHIFT) | (idx & IDX_MASK);
    }

    /** Unpacks a {@link #slotCode()} onto {@code isP1}'s side of the board. */
    public static ForwardTarget fromSlotCode(boolean isP1, int code) {
        int ordinal = (code >> ZONE_SHIFT) & ZONE_MASK;
        CardZone zone = ordinal < CardZone.values().length ? CardZone.values()[ordinal] : CardZone.BREAK_ZONE;
        return new ForwardTarget(isP1, code & IDX_MASK, zone);
    }

    /** The slot code of {@code idx} in {@code zone}; see {@link #slotCode()}. */
    public static int slotCode(CardZone zone, int idx) {
        return (zone.ordinal() << ZONE_SHIFT) | (idx & IDX_MASK);
    }

    /**
     * Reads a code written by the client on the other side of the board. The two sit opposite each
     * other, so what the sender packed as their own side is this client's opponent and vice versa.
     */
    public static int flipChoiceSide(int code) {
        return code ^ SIDE_BIT;
    }
}
