package shufflingway;

/**
 * Where the cards a "reveal the top N and play some of them" effect did not put onto the field
 * end up.
 *
 * <p>Printed as part of the same sentence as the reveal, so it travels with it rather than being
 * a second effect: the player is arranging one set of revealed cards, and which pile the leftovers
 * join is the last step of that one interaction.
 *
 * <p>Only {@link #BOTTOM} lets the player order the leftovers. {@link #SHUFFLED_BOTTOM} reaches
 * the same pile but randomises it, and the other two destinations have no order to set at all —
 * which is why the dialog's swap controls are live for {@code BOTTOM} alone.
 */
enum RevealRest {
    /** The bottom of the deck, in an order the player chooses — every card in this family but two. */
    BOTTOM,
    /** The ability user's hand — 26-053L Bartz. */
    HAND,
    /** The ability user's Break Zone — 15-130H Nox Suzaku. */
    BREAK_ZONE,
    /**
     * The bottom of the deck in a random order — the "Then, shuffle the other cards and return
     * them to the bottom of your deck" ending (Setzer 29-103H, Vaan 10-133S, Chaos Advent 27-006R).
     *
     * <p>The same pile as {@link #BOTTOM} and deliberately not the same constant: there the player
     * arranges the leftovers and therefore knows the order, here nobody does. Sharing one constant
     * would have handed the player an arrangement the card says they may not have.
     */
    SHUFFLED_BOTTOM
}
