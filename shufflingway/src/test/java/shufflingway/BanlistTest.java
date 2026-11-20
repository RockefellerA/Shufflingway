package shufflingway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.StringReader;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import shufflingway.Banlist.DeckCard;
import shufflingway.Banlist.NameLimit;
import shufflingway.Banlist.Violations;

class BanlistTest {

    private static final String SAMPLE = String.join("\n",
            "**Standard**",
            "",
            "Banned",
            "[1-089H] Rikku",
            "[13-120H] Doga ",
            "",
            "Restricted [1]",
            "[13-119L] Sophie ",
            "",
            "Conditional [Name Limit 3]",
            "[23-117L] Chaos (Chaos, Feral Chaos)",
            "",
            "**Title**",
            "",
            "Banned",
            "[6-062R] ",
            "[10-098L]");

    private static Banlist sample() throws IOException {
        return Banlist.parse(new StringReader(SAMPLE));
    }

    private static Violations checkStandard(DeckCard... deck) throws IOException {
        return sample().check(Banlist.STANDARD, List.of(deck));
    }

    @Test
    void readsTheBannedListOfEachFormat() throws IOException {
        Banlist b = sample();
        assertEquals(List.of("1-089H", "13-120H"), List.copyOf(b.banned(Banlist.STANDARD)));
        assertEquals(List.of("6-062R", "10-098L"), List.copyOf(b.banned("Title")));
    }

    @Test
    void restrictedAndConditionalCardsAreNotBanned() throws IOException {
        Banlist b = sample();
        assertFalse(b.isBannedInStandard("13-119L"));
        assertFalse(b.isBannedInStandard("23-117L"));
    }

    @Test
    void banIsPerFormat() throws IOException {
        Banlist b = sample();
        assertFalse(b.isBannedInStandard("6-062R"));
        assertTrue(b.isBanned("title", "6-062R"));
        assertFalse(b.isBanned("L3", "1-089H"));
    }

    @Test
    void aReprintIsBannedWhenAnyPrintingIs() throws IOException {
        Banlist b = sample();
        assertTrue(b.isBannedInStandard("1-089H"));
        assertTrue(b.isBannedInStandard("27-001C / 1-089H"));
        assertFalse(b.isBannedInStandard("1-089"));
        assertFalse(b.isBannedInStandard(null));
    }

    @Test
    void restrictedAndConditionalTriggerCardsAreLimited() throws IOException {
        Banlist b = sample();
        assertTrue(b.isLimitedInStandard("13-119L"));
        assertTrue(b.isLimitedInStandard("23-117L"));
        assertTrue(b.isLimitedInStandard("30-001L / 13-119L"));
        assertFalse(b.isLimitedInStandard("16-129L"), "a Chaos that is only counted is not listed");
        assertFalse(b.isLimitedInStandard("1-089H"), "banned, not limited");
        assertFalse(b.isLimited("Title", "13-119L"));
    }

    @Test
    void bannedCardInDeckIsAViolation() throws IOException {
        Violations v = checkStandard(new DeckCard("1-089H", "Rikku", 1));
        assertEquals(Set.of("1-089H"), v.banned());
        assertTrue(v.flags("1-089H"));
    }

    @Test
    void restrictedListCarriesItsCopyLimit() throws IOException {
        assertEquals(Map.of("13-119L", 1), sample().restricted(Banlist.STANDARD));
        Banlist two = Banlist.parse(new StringReader("**Standard**\nRestricted [2]\n[1-001H] A\n"));
        assertEquals(Map.of("1-001H", 2), two.restricted(Banlist.STANDARD));
    }

    @Test
    void restrictedCardIsOnlyFlaggedOverItsLimit() throws IOException {
        assertTrue(checkStandard(new DeckCard("13-119L", "Sophie", 1)).isEmpty());
        Violations v = checkStandard(new DeckCard("13-119L", "Sophie", 2));
        assertEquals(Map.of("13-119L", 1), v.overRestricted());
    }

    @Test
    void reprintCopiesCountAgainstTheSameLimit() throws IOException {
        Violations v = checkStandard(
                new DeckCard("13-119L", "Sophie", 1),
                new DeckCard("30-001L / 13-119L", "Sophie", 1));
        assertEquals(Set.of("13-119L", "30-001L / 13-119L"), v.overRestricted().keySet());
    }

    @Test
    void conditionalRowReadsItsLimitAndNames() throws IOException {
        assertEquals(List.of(new NameLimit("23-117L", List.of("Chaos", "Feral Chaos"), 3)),
                sample().nameLimits(Banlist.STANDARD));
        assertEquals("Chaos or Feral Chaos", sample().nameLimits(Banlist.STANDARD).get(0).describeNames());
    }

    @Test
    void conditionalWithoutANameLimitIsIgnored() throws IOException {
        Banlist b = Banlist.parse(new StringReader(
                "**Standard**\nConditional\n[23-117L] Chaos\nTotal of 3 named Chaos in deck\n"));
        assertEquals(List.of(), b.nameLimits(Banlist.STANDARD));
    }

    @Test
    void nameLimitAppliesOnlyWithTheTriggerCard() throws IOException {
        assertTrue(checkStandard(
                new DeckCard("16-129L", "Chaos", 3),
                new DeckCard("3-148H", "Feral Chaos", 3)).isEmpty());
    }

    @Test
    void triggerCardCountsTowardItsNameLimit() throws IOException {
        assertTrue(checkStandard(
                new DeckCard("23-117L", "Chaos", 1),
                new DeckCard("16-129L", "Chaos", 1),
                new DeckCard("3-148H", "Feral Chaos", 1)).isEmpty());
        Violations v = checkStandard(
                new DeckCard("23-117L", "Chaos", 1),
                new DeckCard("16-129L", "Chaos", 2),
                new DeckCard("3-148H", "Feral Chaos", 1),
                new DeckCard("9-123L", "Chaos (MOBIUS)", 3));
        List<String> counted = v.overNameLimit().values().iterator().next();
        assertEquals(List.of("23-117L", "16-129L", "3-148H"), counted);
        assertFalse(v.flags("9-123L"), "a different card name is not counted");
    }

    @Test
    void listingsNameEachCategoryAndTheFormatAsPrinted() throws IOException {
        Banlist b = sample();
        assertEquals(List.of("Banned (Standard)"), labels(b, "1-089H"));
        assertEquals(List.of("Restricted [1] (Standard)"), labels(b, "13-119L"));
        assertEquals(List.of("Conditional [Name Limit 3] (Standard)"), labels(b, "23-117L"));
        assertEquals(List.of("Banned (Title)"), labels(b, "10-098L"), "the format keeps its printed case");
        assertEquals(List.of("Banned (Standard)"), labels(b, "27-001C / 1-089H"), "a reprint is listed as its original");
        assertEquals(List.of(), labels(b, "1-001H"));
    }

    @Test
    void listingsExplainTheirRule() throws IOException {
        Banlist b = sample();
        assertEquals("In Standard, a deck may not hold this card.",
                b.listings("1-089H").get(0).explanation());
        assertEquals("In Standard, a deck may hold at most 1 copy of this card.",
                b.listings("13-119L").get(0).explanation());
        assertEquals("In Standard, a deck holding this card may hold at most 3 cards named Chaos or Feral Chaos.",
                b.listings("23-117L").get(0).explanation());
        assertTrue(b.listings("1-089H").get(0).banned());
        assertFalse(b.listings("13-119L").get(0).banned());
    }

    private static List<String> labels(Banlist b, String serial) {
        return b.listings(serial).stream().map(Banlist.Listing::label).toList();
    }

    @Test
    void bundledFileLoads() {
        Set<String> standard = Banlist.get().banned(Banlist.STANDARD);
        assertFalse(standard.isEmpty(), "Standard banlist read from /data/banlist.txt");
        for (String serial : standard)
            assertTrue(serial.matches("[\\w-]+"), "malformed serial: " + serial);
    }
}
