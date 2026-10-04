package shufflingway;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

/** The AI's picks from a "select N of the following actions" menu. */
class AiActionPickerTest {

    // Seymour 27-028H: "select up to 2 of the 4 following actions."
    private static final String BREAK    = "Choose 1 dull Forward. Break it.";
    private static final String DULL     = "Choose up to 2 Characters. Dull them and Freeze them.";
    private static final String DISCOUNT = "During this turn, the cost required to cast your next Summon is reduced by 4.";
    private static final String DISCARD  = "Your opponent discards 1 card.";
    private static final List<String> SEYMOUR = List.of(BREAK, DULL, DISCOUNT, DISCARD);

    private static AiActionPicker.Board board(int activeForwards, int dullForwards, int activeOthers,
            int oppHand, boolean ownSummon) {
        return new AiActionPicker.Board(true, activeForwards, dullForwards,
                activeForwards + activeOthers, dullForwards, oppHand, ownSummon);
    }

    // The game this came from: an active Shiva and no dull Forward. Breaking first found nothing;
    // dulling her first leaves her there to break.
    @Test
    void dullsAnActiveForwardBeforeBreakingIt() {
        assertEquals(List.of(DULL, BREAK), AiActionPicker.pick(SEYMOUR, 2, true, board(1, 0, 0, 3, false)));
    }

    @Test
    void anExistingDullForwardIsBrokenAndTheDullStillComesFirst() {
        assertEquals(List.of(DULL, BREAK), AiActionPicker.pick(SEYMOUR, 2, true, board(1, 1, 0, 3, false)));
    }

    // No Forwards at all: nothing can be broken, so the discard is taken instead.
    @Test
    void withNoForwardsTheOpponentDiscards() {
        assertEquals(List.of(DULL, DISCARD), AiActionPicker.pick(SEYMOUR, 2, true, board(0, 0, 2, 3, false)));
        assertEquals(List.of(DISCARD), AiActionPicker.pick(SEYMOUR, 2, true, board(0, 0, 0, 3, false)));
    }

    @Test
    void anEmptyHandIsNotWorthADiscard() {
        assertEquals(List.of(), AiActionPicker.pick(SEYMOUR, 2, true, board(0, 0, 0, 0, false)));
    }

    @Test
    void theSummonDiscountIsTakenOnlyWithASummonInHand() {
        assertEquals(List.of(DULL, DISCOUNT), AiActionPicker.pick(SEYMOUR, 2, true, board(0, 0, 2, 3, true)));
    }

    // One pick: a break that needs the dull option ahead of it cannot have it, so it is passed over.
    @Test
    void aBreakThatNeedsTheDullOptionIsSkippedWhenOnlyOnePickRemains() {
        assertEquals(List.of(DULL), AiActionPicker.pick(SEYMOUR, 1, true, board(1, 0, 0, 3, false)));
    }

    // An exact count still has to be met, dead options and all.
    @Test
    void anExactCountIsFilledWithDeadOptions() {
        assertEquals(List.of(DULL, BREAK), AiActionPicker.pick(SEYMOUR, 2, false, board(0, 0, 0, 0, false)));
    }

    @Test
    void anOpponentBreakZoneRemovalIsPreferredWhenItHoldsCardsAndDroppedWhenEmpty() {
        String remove = "Remove 1 card in your opponent's Break Zone from the game.";
        String draw = "Draw 1 card.";
        List<String> menu = List.of(draw, remove);
        AiActionPicker.Board full = new AiActionPicker.Board(false, 0, 0, 0, 0, 0, false);
        AiActionPicker.Board empty = new AiActionPicker.Board(true, 0, 0, 0, 0, 0, false);
        assertEquals(List.of(remove), AiActionPicker.pick(menu, 1, true, full));
        assertEquals(List.of(draw), AiActionPicker.pick(menu, 1, true, empty));
    }
}
