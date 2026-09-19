package shufflingway;

import java.util.List;

/**
 * What a player actually handed over to cast a card under one of its alternate costs.
 *
 * <p>The card says what the cost <em>is</em>; this says what was <em>paid</em>. Everything here is
 * a choice the caster made and no other client can reproduce — which Forwards were dulled, which
 * Backup was removed, which cards went to the Break Zone — which is exactly what has to travel
 * with the play so both clients spend the same board. The parts that are not choices (the CP
 * still owed, the drawback the cost arms) are read off the card at both ends instead.
 *
 * <p>Every index addresses the <em>payer's own</em> zones, so nothing here names a side: the
 * caster applies it to their board and the receiver to the board they hold that player on, the
 * same way the hand and Backup indices in a PLAY_CARD already work.
 *
 * @param crystals      Crystals spent, which the card's own {@code altCrystalCost} prices — carried
 *                      anyway so a payment is one object rather than a payload field and a lookup
 * @param dullForwards  Forward slots dulled (Nine 13-123L and the Summon printings beside it)
 * @param removeBackups Backup slots removed from the game (Ifrit 25-004H and its five counterparts)
 * @param putToBz       field cards handed to the Break Zone, whether to buy the play outright
 *                      (Kefka 4-080L) or to reduce its cost (Kain 9-084H) — the two are paid the
 *                      same way, so they are one list
 * @param bzRemovals    Break Zone indices removed from the game (Darkness Manifest 21-123H),
 *                      resolved to indices by the payer rather than re-matched by the receiver so
 *                      that both clients remove the same cards even if their Break Zones disagree
 */
public record AltPayment(int crystals, List<Integer> dullForwards, List<Integer> removeBackups,
        List<ForwardTarget> putToBz, List<Integer> bzRemovals) {

    /** Defensive copies, so a caller cannot mutate a payment after it has been applied or sent. */
    public AltPayment {
        dullForwards  = dullForwards  == null ? List.of() : List.copyOf(dullForwards);
        removeBackups = removeBackups == null ? List.of() : List.copyOf(removeBackups);
        putToBz       = putToBz       == null ? List.of() : List.copyOf(putToBz);
        bzRemovals    = bzRemovals    == null ? List.of() : List.copyOf(bzRemovals);
    }

    /**
     * A cast that took an alternate cost but handed nothing over for it — Golbez 17-140S's
     * reduction, whose price is the drawback it arms rather than anything on the board.
     *
     * <p>Not the same as no alternate cost at all, which is a {@code null} payment. The
     * distinction is the whole signal on the wire: a present-but-empty payment is what tells the
     * receiver to raise the alternate-cost flag and let the drawback fire.
     */
    public static final AltPayment NOTHING_HANDED_OVER =
            new AltPayment(0, List.of(), List.of(), List.of(), List.of());

    /** Whether anything at all was handed over, which decides if there is a board change to apply. */
    public boolean isEmpty() {
        return crystals == 0 && dullForwards.isEmpty() && removeBackups.isEmpty()
                && putToBz.isEmpty() && bzRemovals.isEmpty();
    }
}
