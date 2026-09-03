package shufflingway;

/**
 * What a "reveal the top N and take some of them" effect does with the cards the player picks out,
 * and the words the dialog and the log use for it.
 *
 * <p>{@link #FIELD} is what the whole reveal family did before Snow 18-109C: the picked cards enter
 * play. Snow takes its pick to the removed-from-game zone instead and makes it castable from there,
 * which is a different destination but the same interaction — one set of revealed cards, some taken
 * and the rest arranged into a pile. So it rides the same dialog rather than getting one of its own,
 * with this carrying the three strings that would otherwise be hard-coded to "field".
 *
 * <p>What each does with the taken card is <em>not</em> here: that is the caller's consumer, which
 * already varied per effect. This is only what the two seats are told is happening.
 */
enum RevealTake {
    /** The card enters play. */
    FIELD("Play up to ", " onto Field", "→ Field", "play", "played onto field"),
    /**
     * The card is removed from the game and registered castable from there — Snow 18-109C, whose
     * "You can cast it at any time you could normally cast it this turn" is the point of the removal.
     */
    RFG_CASTABLE("Remove ", " from the Game", "→ Remove", "remove from the game", "removed from the game"),
    /**
     * The card is removed from the game with a Warp Counter placed on it — Setzer 29-103H. It
     * lands in the Warp zone and ticks down from there like any warped card, so what the player is
     * told names the counter as well as the removal.
     */
    RFG_WARP_COUNTER("Remove ", " from the Game (Warp Counter)", "→ Remove",
            "remove from the game with a Warp Counter", "removed from the game with a Warp Counter");

    private final String titlePrefix;
    private final String titleVerb;
    private final String buttonLabel;
    private final String takeVerb;
    private final String logVerb;

    RevealTake(String titlePrefix, String titleVerb, String buttonLabel, String takeVerb,
            String logVerb) {
        this.titlePrefix = titlePrefix;
        this.titleVerb   = titleVerb;
        this.buttonLabel = buttonLabel;
        this.takeVerb    = takeVerb;
        this.logVerb     = logVerb;
    }

    /** The toggle a player clicks to take a card. */
    String buttonLabel() { return buttonLabel; }

    /**
     * What the dialog's instruction line says the click does: "… to play", "… to remove from the
     * game". Kept beside {@link #buttonLabel()} because the two are read as one sentence, and the
     * instruction used to name the field while the button beside it said Remove.
     */
    String takeVerb() { return takeVerb; }

    /** How the shared log names a taken card: "[name] [logVerb]". */
    String logVerb() { return logVerb; }

    /** The dialog's title bar, naming what may be taken and where the leftovers go. */
    String title(int maxTake, String typeLabel, RevealRest rest) {
        return "Reveal — " + titlePrefix + maxTake + " " + typeLabel + titleVerb
                + switch (rest) {
                    case HAND       -> ", Rest to Hand";
                    case BREAK_ZONE -> ", Rest to Break Zone";
                    case BOTTOM     -> ", Rest to Bottom";
                    case SHUFFLED_BOTTOM -> ", Rest Shuffled Under Deck";
                };
    }
}
