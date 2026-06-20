package shufflingway;

/**
 * Describes a card a player is permitted to cast even though it is not in their hand —
 * the generalized "you can cast it as though you owned it" primitive shared by Lani 12-018H,
 * Zidane 16-048H/24-044H, Bel Dat 20-056H, Shantotto 23-067R, Krile 12-061L,
 * Nanaa Mihgo 22-048H, Mind Flayer 15-120H, and Zidane 14-127H.
 *
 * <p>The entry is keyed by {@link CardData} identity in {@code MainWindow.bzPlayableP1/P2} and
 * drives, in a data-driven (never per-card) way, how the borrowed card is paid for and where it
 * goes after resolving:
 * <ul>
 *   <li>{@link #source} — the zone the card currently sits in (and is removed from when cast).</li>
 *   <li>{@link #costReduction} — CP subtracted from the printed cost ("cost reduced by N").</li>
 *   <li>{@link #anyElement} — the cost may be paid using CP of any Element.</li>
 *   <li>{@link #freeCast} — cast "without paying the cost".</li>
 *   <li>{@link #rfgAfterUse} — after a Summon resolves, remove it from the game instead of
 *       putting it into the Break Zone (Krile/Nanaa).</li>
 *   <li>{@link #expiresThisTurn} — registration lasts only "this turn"; {@code false} means
 *       "at any time you could normally cast it" / "during this game" (persists).</li>
 * </ul>
 */
public record PlayableEntry(
        SourceZone source,
        int costReduction,
        boolean anyElement,
        boolean freeCast,
        boolean rfgAfterUse,
        boolean expiresThisTurn) {

    /** The zone a borrowed card is drawn from when cast. */
    public enum SourceZone { BREAK_ZONE, RFP }

    /** Legacy "Choose 1 Summon in your Break Zone, cast it this turn" registration. */
    public static PlayableEntry bzThisTurn(int costReduction) {
        return new PlayableEntry(SourceZone.BREAK_ZONE, costReduction, false, false, false, true);
    }

    /** Effective CP cost for {@code card} under this entry (0 when {@link #freeCast}). */
    public int effectiveCost(CardData card) {
        return freeCast ? 0 : Math.max(0, card.cost() - costReduction);
    }
}
