package shufflingway;

import java.util.List;

/**
 * What a player actually paid for a card's optional extra cost.
 *
 * <p>{@link ExtraCost} says what the surcharge <em>is</em>; this says what was <em>paid</em>, and
 * exists for the same reason {@link AltPayment} does — the choices behind it are the caster's, so
 * no other client can reproduce them and they have to travel with the play.
 *
 * <p>The CP half of a surcharge needs nothing here. A fixed or 《X》 amount is charged through the
 * ordinary payment dialog, so the Backups dulled and cards discarded for it are already in the
 * PLAY_CARD payload like any other payment; what is recorded here is only the size of the
 * surcharge, which the effects downstream read back ({@code xValue} is Valefor 24-038H's X).
 *
 * <p>Every index addresses the payer's own zones <em>as they stood when the cost was chosen</em>,
 * before the play spent anything. The zones move during the play — cards discarded for CP land in
 * the Break Zone, the cast card leaves the hand — so both clients resolve these to cards up front
 * and hand those over, rather than indexing a zone that has since shifted under them.
 *
 * @param type         which surcharge this paid, so "nothing was selected" stays distinguishable
 *                     from "there was nothing to select"; checked against the card on arrival
 * @param crystals     Crystals spent (Bahamut SIN 28-087H and the five beside it)
 * @param xValue       the X chosen for a 《X》 surcharge, 0 when none was
 * @param bzRemovals   Break Zone indices removed from the game (the Opus XVIII Summon cycle)
 * @param handDiscards hand indices discarded (Fenrir 24-065H), indexed into the whole hand
 *                     including the card being cast, so both clients count the same slots
 */
public record ExtraPayment(ExtraCost.Type type, int crystals, int xValue,
        List<Integer> bzRemovals, List<Integer> handDiscards) {

    /** Defensive copies, so a caller cannot mutate a payment after it has been applied or sent. */
    public ExtraPayment {
        bzRemovals   = bzRemovals   == null ? List.of() : List.copyOf(bzRemovals);
        handDiscards = handDiscards == null ? List.of() : List.copyOf(handDiscards);
    }

    public static ExtraPayment bzRemovals(List<Integer> indices) {
        return new ExtraPayment(ExtraCost.Type.BZ_REMOVE, 0, 0, indices, List.of());
    }

    public static ExtraPayment handDiscards(List<Integer> indices) {
        return new ExtraPayment(ExtraCost.Type.DISCARD_HAND, 0, 0, List.of(), indices);
    }

    public static ExtraPayment cpX(int x) {
        return new ExtraPayment(ExtraCost.Type.CP_X, 0, x, List.of(), List.of());
    }

    /** The card's printed fixed surcharge, confirmed and charged through the payment dialog. */
    public static ExtraPayment cpFixed() {
        return new ExtraPayment(ExtraCost.Type.CP_FIXED, 0, 0, List.of(), List.of());
    }

    public static ExtraPayment crystals(int count) {
        return new ExtraPayment(ExtraCost.Type.CRYSTAL, count, 0, List.of(), List.of());
    }

    /**
     * Whether this counts as having paid the extra cost — what every "if you paid the extra cost"
     * clause downstream is asking about.
     *
     * <p>Not simply "a payment exists". A 《X》 surcharge of nothing is the player declining it,
     * and Valefor 24-038H cast for X=0 has paid no extra cost at all. The Crystal answer here is
     * the intent to pay; whether the Crystals were still there to spend is settled at spend time.
     */
    public boolean counts() {
        return switch (type) {
            case CP_X    -> xValue > 0;
            case CRYSTAL -> crystals > 0;
            default      -> true;
        };
    }

    /** The extra generic CP this surcharge adds to the cost the payment dialog asks for. */
    public int extraGenericCp(ExtraCost cost) {
        return switch (type) {
            case CP_X     -> xValue;
            case CP_FIXED -> (int) cost.cpElements().stream().filter(String::isEmpty).count();
            default       -> 0;
        };
    }
}
