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

    // A reprint counts by its newest printing: first printed in set 6, reprinted in 27. The card
    // database lists the reprint first ("13-071R/2-101H"); the check does not depend on the order.
    @Test
    void aReprintInARecentSetIsInTheWindow() {
        assertTrue(DeckFormat.L3.setsAllow(List.of("27-004C/6-022R"), NEWEST));
    }

    @Test
    void aReprintWithEveryPrintingOldIsNot() {
        assertFalse(DeckFormat.L3.setsAllow(List.of("20-004C/6-022R"), NEWEST));
    }

    @Test
    void theNewestSetOfAReprintIsItsLatestPrinting() {
        assertEquals(27, DeckFormat.latestSet("27-004C/6-022R"));
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
    void eachFormatPlaysUnderItsOwnBanlist() {
        assertTrue(DeckFormat.TITLE.available());
        assertEquals("Title", DeckFormat.TITLE.banlistName());
        assertEquals(Banlist.STANDARD, DeckFormat.STANDARD.banlistName());
        assertEquals("L3", DeckFormat.L3.banlistName());
    }

    // L3 and L6 have empty sections in the bundled banlist, so a card Standard bans is legal there.
    @Test
    void theStandardBanlistDoesNotReachL3OrL6() {
        List<Banlist.DeckCard> deck = List.of(new Banlist.DeckCard("1-089H", "Rikku", 1));
        Banlist banlist = Banlist.get();
        assertFalse(banlist.check(DeckFormat.STANDARD.banlistName(), deck).isEmpty(), "banned in Standard");
        assertTrue(banlist.check(DeckFormat.L3.banlistName(), deck).isEmpty());
        assertTrue(banlist.check(DeckFormat.L6.banlistName(), deck).isEmpty());
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
