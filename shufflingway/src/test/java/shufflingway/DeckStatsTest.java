package shufflingway;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** The figures behind the Deck Manager's Element pie and cost curve. */
class DeckStatsTest {

    /** A main-deck row as the Deck Manager holds it: copies, serial, name, type, Element, cost. */
    private static Object[] row(int copies, String element, Object cost) {
        return new Object[] { copies, "1-001C", "Card", "Forward", element, cost, 5000, null, null, null };
    }

    private static List<Object[]> deck(Object[]... rows) {
        return Arrays.asList(rows);
    }

    @Test
    void everyCopyCountsAndAMultiElementCardCountsTowardEachOfItsElements() {
        DeckStats s = DeckStats.of(deck(
                row(3, "Fire", 2),
                row(2, "Fire/Ice", 3),
                row(1, "Light", 5)));
        assertEquals(6, s.cards());
        assertEquals(Map.of(ElementColor.FIRE, 5, ElementColor.ICE, 2, ElementColor.LIGHT, 1), s.elements());
        assertEquals(Map.of(2, 3, 3, 2, 5, 1), s.costs());
        assertEquals(3, s.tallestCost());
    }

    // The pie's slices and legend run in the game's Element order, whatever order the deck lists.
    @Test
    void theElementsKeepTheGamesOrder() {
        DeckStats s = DeckStats.of(deck(row(1, "Dark", 1), row(1, "Water", 1), row(1, "Fire", 1)));
        assertEquals(List.of(ElementColor.FIRE, ElementColor.WATER, ElementColor.DARK), List.copyOf(s.elements().keySet()));
    }

    @Test
    void theCostAxisRunsFromOneToAtLeastSixAndAsFarAsTheDearestCard() {
        assertEquals(List.of(1, 2, 3, 4, 5, 6), DeckStats.of(deck(row(1, "Fire", 2), row(1, "Fire", 3))).costAxis());
        assertEquals(List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11), DeckStats.of(deck(row(1, "Fire", 11))).costAxis());
    }

    @Test
    void costZeroIsOnTheAxisOnlyWhenACardCostsZero() {
        assertEquals(0, (int) DeckStats.of(deck(row(1, "Fire", 0))).costAxis().get(0));
        assertEquals(1, (int) DeckStats.of(deck(row(1, "Fire", 1))).costAxis().get(0));
    }

    @Test
    void anEmptyDeckHasNothingToChartButKeepsItsAxis() {
        assertEquals(0, DeckStats.EMPTY.cards());
        assertEquals(Map.of(), DeckStats.EMPTY.elements());
        assertEquals(0, DeckStats.EMPTY.tallestCost());
        assertEquals(List.of(1, 2, 3, 4, 5, 6), DeckStats.EMPTY.costAxis());
    }

    // The database hands the cost back as whatever number type the driver chose, or none at all.
    @Test
    void aCostOfAnyNumberTypeCountsAndAMissingOneIsLeftOffTheCurve() {
        DeckStats s = DeckStats.of(deck(row(2, "Wind", 4L), row(1, "Wind", null)));
        assertEquals(3, s.cards());
        assertEquals(Map.of(ElementColor.WIND, 3), s.elements());
        assertEquals(Map.of(4, 2), s.costs());
    }
}
