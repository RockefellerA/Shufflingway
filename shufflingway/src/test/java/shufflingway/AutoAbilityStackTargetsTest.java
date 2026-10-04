package shufflingway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * What an auto ability carries onto the Stack from its push-time target selection — see
 * {@link AutoAbilityTriggers#targetsForStack}. A {@code null} result means "choose again as it
 * resolves"; an empty list means "this ability has no targets".
 */
class AutoAbilityStackTargetsTest {

    private static CardData makeForward(String name, String element, int cost, int power) {
        return new CardData(null, name, element, cost, power, "Forward", false, 0, false, false,
                Set.of(), 0, List.of(), null, List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                false, false, null, false, false, false, false, false, 1,
                null, null, null, "");
    }

    private static MainWindow boardWithGilgameshAndShiva() {
        MainWindow mw = new MainWindow();
        CardData gilgamesh = makeForward("Gilgamesh", "Lightning", 5, 9000);
        mw.gameState.getIdentity().put(gilgamesh, true);
        mw.placeCardInForwardZone(gilgamesh);
        CardData shiva = makeForward("Shiva", "Ice", 3, 7000);
        mw.gameState.getIdentity().put(shiva, false);
        mw.placeP2CardInForwardZone(shiva);
        return mw;
    }

    // Gilgamesh 27-079H: "choose up to 2 Forwards. Dull them." The player was offered Forwards and
    // picked none. That is the choice; asking again at resolution made them decline it twice.
    @Test
    void aDeclinedUpToChoiceIsKeptAsAChoiceOfNone() {
        MainWindow mw = boardWithGilgameshAndShiva();
        CardData gilgamesh = mw.p1ForwardCards.get(0);
        assertEquals(List.of(), AutoAbilityTriggers.targetsForStack(new ArrayList<>(),
                "Choose up to 2 Forwards. Dull them.", gilgamesh, mw.buildGameContext(true)));
    }

    // Nothing eligible as the ability went on the Stack: resolution still gets to look again.
    @Test
    void nothingEligibleStillChoosesAtResolution() {
        MainWindow mw = new MainWindow();
        CardData gilgamesh = makeForward("Gilgamesh", "Lightning", 5, 9000);
        mw.gameState.getIdentity().put(gilgamesh, true);
        mw.placeCardInForwardZone(gilgamesh);
        assertNull(AutoAbilityTriggers.targetsForStack(new ArrayList<>(),
                "Choose up to 2 Forwards opponent controls. Dull them.", gilgamesh, mw.buildGameContext(true)));
    }

    // A choice that is not "up to" cannot be declined, so an empty one keeps the old fallback.
    @Test
    void anEmptyRequiredChoiceStillChoosesAtResolution() {
        MainWindow mw = boardWithGilgameshAndShiva();
        CardData gilgamesh = mw.p1ForwardCards.get(0);
        assertNull(AutoAbilityTriggers.targetsForStack(new ArrayList<>(),
                "Choose 1 Forward. Dull it.", gilgamesh, mw.buildGameContext(true)));
    }

    @Test
    void picksAndNoSelectionPassThroughUnchanged() {
        MainWindow mw = boardWithGilgameshAndShiva();
        CardData gilgamesh = mw.p1ForwardCards.get(0);
        List<ForwardTarget> picked = List.of(new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD));
        assertEquals(picked, AutoAbilityTriggers.targetsForStack(picked,
                "Choose up to 2 Forwards. Dull them.", gilgamesh, mw.buildGameContext(true)));
        assertNull(AutoAbilityTriggers.targetsForStack(null,
                "Draw 1 card.", gilgamesh, mw.buildGameContext(true)));
    }
}
