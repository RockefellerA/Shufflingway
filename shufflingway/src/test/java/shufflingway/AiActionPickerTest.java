package shufflingway;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
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
        return new AiActionPicker.Board(true, forwards(activeForwards, dullForwards),
                activeForwards + activeOthers, dullForwards, 0, oppHand, ownSummon);
    }

    /** {@code active} active and {@code dull} dull opposing Forwards of cost 3, none damaged. */
    private static List<AiActionPicker.OpposingForward> forwards(int active, int dull) {
        List<AiActionPicker.OpposingForward> out = new ArrayList<>();
        for (int i = 0; i < active; i++) out.add(new AiActionPicker.OpposingForward(3, false, false));
        for (int i = 0; i < dull; i++)   out.add(new AiActionPicker.OpposingForward(3, true, false));
        return out;
    }

    /** A board whose only opposing Characters are {@code forwards}. */
    private static AiActionPicker.Board facing(AiActionPicker.OpposingForward... forwards) {
        int dull = 0;
        for (AiActionPicker.OpposingForward f : forwards) if (f.dull()) dull++;
        return new AiActionPicker.Board(true, List.of(forwards), forwards.length - dull, dull, 0, 0, false);
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
        AiActionPicker.Board full = new AiActionPicker.Board(false, List.of(), 0, 0, 0, 0, false);
        AiActionPicker.Board empty = new AiActionPicker.Board(true, List.of(), 0, 0, 0, 0, false);
        assertEquals(List.of(remove), AiActionPicker.pick(menu, 1, true, full));
        assertEquals(List.of(draw), AiActionPicker.pick(menu, 1, true, empty));
    }

    // Tidus 22-122L: "select up to 3 of the 3 following actions." Each removal names no side, so
    // with no opposing Character of its kind the AI's own would be the one taken off the field.
    private static final String TIDUS_FORWARD = "Choose 1 Forward. Put it at the bottom of its owner's deck.";
    private static final String TIDUS_BACKUP  = "Choose 1 Backup. Put it on top of its owner's deck.";
    private static final String TIDUS_DRAW    = "Draw 2 cards.";
    private static final List<String> TIDUS = List.of(TIDUS_FORWARD, TIDUS_BACKUP, TIDUS_DRAW);

    @Test
    void aRemovalIsTakenOnlyWhenTheOpponentHasSomethingOfThatKind() {
        AiActionPicker.Board both = new AiActionPicker.Board(true, forwards(1, 0), 2, 0, 1, 0, false);
        AiActionPicker.Board noBackup = new AiActionPicker.Board(true, forwards(1, 0), 1, 0, 0, 0, false);
        AiActionPicker.Board nothing = new AiActionPicker.Board(true, List.of(), 0, 0, 0, 0, false);
        assertEquals(TIDUS, AiActionPicker.pick(TIDUS, 3, true, both));
        assertEquals(List.of(TIDUS_FORWARD, TIDUS_DRAW), AiActionPicker.pick(TIDUS, 3, true, noBackup));
        assertEquals(List.of(TIDUS_DRAW), AiActionPicker.pick(TIDUS, 3, true, nothing));
    }

    // Live options first, then the dead ones the count still demands.
    @Test
    void anExactCountStillTakesADeadRemoval() {
        AiActionPicker.Board nothing = new AiActionPicker.Board(true, List.of(), 0, 0, 0, 0, false);
        assertEquals(List.of(TIDUS_DRAW, TIDUS_FORWARD, TIDUS_BACKUP),
                AiActionPicker.pick(TIDUS, 3, false, nothing));
    }

    // A chosen Forward named by its state or cost is worth choosing only when an opposing Forward
    // meets every qualifier. Read as plain "something to choose", each of these was taken against a
    // board that had nothing it could reach, and the select resolved to nothing.
    private static final String TONBERRY_BREAK = "Choose 1 active Forward of cost 3 or less. Break it.";
    private static final String TONBERRY_LOOK  = "Look at the top 2 cards of your deck. Add 1 card among them "
            + "to your hand and return the other card to the bottom of your deck.";
    private static final String IRON_GIANT_CHEAP = "Choose 1 Forward of cost 2 or less. Deal it 5000 damage.";
    private static final String IRON_GIANT_DULL  = "Choose 1 dull Forward. Deal it 7000 damage.";
    private static final String WU_BREAK = "Choose 1 damaged Forward. Break it.";
    private static final String WU_FIGHT = "Choose 1 Forward opponent controls. Vajradhara Wu and the chosen "
            + "Forward deal damage equal to their respective power to the other.";

    @Test
    void anActiveCost3ForwardIsBrokenAndADullOneIsNot() {
        List<String> menu = List.of(TONBERRY_BREAK, TONBERRY_LOOK);
        assertEquals(List.of(TONBERRY_BREAK),
                AiActionPicker.pick(menu, 1, false, facing(new AiActionPicker.OpposingForward(3, false, false))));
        assertEquals(List.of(TONBERRY_LOOK),
                AiActionPicker.pick(menu, 1, false, facing(new AiActionPicker.OpposingForward(2, true, false))),
                "dull, so not \"active\"");
        assertEquals(List.of(TONBERRY_LOOK),
                AiActionPicker.pick(menu, 1, false, facing(new AiActionPicker.OpposingForward(4, false, false))),
                "cost 4, so not \"3 or less\"");
    }

    @Test
    void aCostCeilingTheBoardCannotMeetGivesWayToTheDullTarget() {
        List<String> menu = List.of(IRON_GIANT_CHEAP, IRON_GIANT_DULL);
        assertEquals(List.of(IRON_GIANT_DULL),
                AiActionPicker.pick(menu, 1, false, facing(new AiActionPicker.OpposingForward(5, true, false))));
        assertEquals(List.of(IRON_GIANT_CHEAP),
                AiActionPicker.pick(menu, 1, false, facing(new AiActionPicker.OpposingForward(2, false, false))));
    }

    @Test
    void aDamagedForwardIsBrokenAndWithoutOneTheFightIsTaken() {
        List<String> menu = List.of(WU_BREAK, WU_FIGHT);
        assertEquals(List.of(WU_BREAK),
                AiActionPicker.pick(menu, 1, false, facing(new AiActionPicker.OpposingForward(6, false, true))));
        assertEquals(List.of(WU_FIGHT),
                AiActionPicker.pick(menu, 1, false, facing(new AiActionPicker.OpposingForward(3, false, false))));
    }

    @Test
    void anExactCostIsReadAsThatCostAlone() {
        String breakOne = "Choose 1 Forward of cost 1. Break it.";
        String draw = "Draw 1 card.";
        List<String> menu = List.of(breakOne, draw);
        assertEquals(List.of(breakOne),
                AiActionPicker.pick(menu, 1, false, facing(new AiActionPicker.OpposingForward(1, false, false))));
        assertEquals(List.of(draw),
                AiActionPicker.pick(menu, 1, false, facing(new AiActionPicker.OpposingForward(2, false, false))));
    }
}
