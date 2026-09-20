package shufflingway;

import java.util.List;
import java.util.Map;

/**
 * What a player paid to activate an action ability.
 *
 * <p>The third of the payment records, beside {@link AltPayment} and {@link ExtraPayment}, and
 * carried for the same reason: every field here is a choice its payer made, so the other client
 * cannot work it out and has to be told. What the ability <em>costs</em> is read off the card at
 * both ends — including the discounts and surcharges the board applies to it, which both clients
 * compute from the same board.
 *
 * <p>Every index addresses the payer's own zones, so nothing here names a side.
 *
 * @param discards     hand slots discarded for CP
 * @param backupDulls  Backup slots dulled for CP
 * @param bzTargets    field cards put into the Break Zone to pay a "put X into the Break Zone"
 *                     cost. Resolved by the activator before the payment runs — mostly forced,
 *                     but a genuine choice when more cards qualify than the cost takes, which is
 *                     why the answer travels rather than being worked out again at the far end
 * @param xValue       the X chosen for an 《X》 cost, 0 when the ability prints none
 * @param sCostHandIdx the hand slot discarded for a Special ability's 《S》 cost, or -1 when the
 *                     payment did not settle one up front
 * @param backupBreaks Backups put into the Break Zone for CP as part of this payment, slot to the
 *                     Element each produces (Sherlotta 8-053H)
 */
public record AbilityPayment(List<Integer> discards, List<Integer> backupDulls,
        List<ForwardTarget> bzTargets, int xValue, int sCostHandIdx,
        Map<Integer, String> backupBreaks) {

    /** Defensive copies, so a caller cannot mutate a payment after it has been applied or sent. */
    public AbilityPayment {
        discards     = discards     == null ? List.of() : List.copyOf(discards);
        backupDulls  = backupDulls  == null ? List.of() : List.copyOf(backupDulls);
        bzTargets    = bzTargets    == null ? List.of() : List.copyOf(bzTargets);
        backupBreaks = backupBreaks == null ? Map.of()  : Map.copyOf(backupBreaks);
    }
}
