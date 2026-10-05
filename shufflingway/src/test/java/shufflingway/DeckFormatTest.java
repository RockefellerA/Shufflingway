package shufflingway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class DeckFormatTest {

    // With set 28 the newest, L3 is sets 26-28 and L6 is 23-28.
    private static final int NEWEST = 28;

    @Test
    void standardAllowsEverySet() {
        assertTrue(DeckFormat.STANDARD.setsAllow(List.of("1-001H", "28-001C"), NEWEST));
    }

    @Test
    void l3AllowsOnlyTheLatestThreeSets() {
        assertTrue(DeckFormat.L3.setsAllow(List.of("26-001H", "27-050C", "28-100L"), NEWEST));
        assertFalse(DeckFormat.L3.setsAllow(List.of("26-001H", "25-001H"), NEWEST));
    }

    @Test
    void l6ReachesBackSixSets() {
        assertTrue(DeckFormat.L6.setsAllow(List.of("23-001H", "28-001C"), NEWEST));
        assertFalse(DeckFormat.L6.setsAllow(List.of("22-001H"), NEWEST));
    }

    @Test
    void promosAreInEveryWindow() {
        assertTrue(DeckFormat.L3.setsAllow(List.of("PR-001", "27-001H"), NEWEST));
    }

    // A reprint counts by its newest printing: first printed in set 6, reprinted in 27.
    @Test
    void aReprintInARecentSetIsInTheWindow() {
        assertTrue(DeckFormat.L3.setsAllow(List.of("6-022R/27-004C"), NEWEST));
    }

    @Test
    void aReprintWithEveryPrintingOldIsNot() {
        assertFalse(DeckFormat.L3.setsAllow(List.of("6-022R/20-004C"), NEWEST));
    }

    @Test
    void theNewestSetOfAReprintIsItsLatestPrinting() {
        assertEquals(27, DeckFormat.latestSet("6-022R/27-004C"));
        assertEquals(0, DeckFormat.latestSet("PR-001"));
    }

    @Test
    void aWindowAllowsNothingWithoutAKnownNewestSet() {
        assertFalse(DeckFormat.L3.setsAllow(List.of("28-001C"), 0));
    }

    @Test
    void anUnknownOrMissingFormatReadsAsStandard() {
        assertEquals(DeckFormat.STANDARD, DeckFormat.parse(null));
        assertEquals(DeckFormat.STANDARD, DeckFormat.parse("Modern"));
        assertEquals(DeckFormat.L6, DeckFormat.parse("l6"));
    }

    @Test
    void titleIsListedButNotAvailable() {
        assertFalse(DeckFormat.TITLE.available());
        assertTrue(DeckFormat.L3.available());
    }

    // A lobby voids the chosen decks only when the new rules could refuse one the old allowed.
    @Test
    void narrowingTheFormatOrAddingTheBanlistTightens() {
        assertTrue(DeckFormat.tightens(DeckFormat.STANDARD, false, DeckFormat.L6, false));
        assertTrue(DeckFormat.tightens(DeckFormat.L6, false, DeckFormat.L3, false));
        assertTrue(DeckFormat.tightens(DeckFormat.L3, false, DeckFormat.L3, true));
    }

    @Test
    void wideningTheFormatOrDroppingTheBanlistDoesNot() {
        assertFalse(DeckFormat.tightens(DeckFormat.L3, false, DeckFormat.STANDARD, false));
        assertFalse(DeckFormat.tightens(DeckFormat.L3, true, DeckFormat.L3, false));
        assertFalse(DeckFormat.tightens(DeckFormat.L6, true, DeckFormat.L6, true));
    }
}
