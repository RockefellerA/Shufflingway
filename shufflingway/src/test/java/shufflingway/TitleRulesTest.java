package shufflingway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import shufflingway.TitleRules.Card;
import shufflingway.TitleRules.Verdict;

class TitleRulesTest {

    /** {@code copies} of a Forward of the given categories. */
    private static Card forward(String serial, String cat1, String cat2, int copies) {
        return new Card(serial, "Forward", "Warrior", cat1, cat2, copies, false);
    }

    /** A legal VII core: 30 VII cards, as ten cards of three copies. */
    private static List<Card> seventhCore() {
        List<Card> deck = new ArrayList<>();
        for (int i = 1; i <= 10; i++) deck.add(forward("7-" + i, "VII", "", 3));
        return deck;
    }

    @Test
    void thirtyCardsOfOneCategoryIsLegalUnderIt() {
        Verdict v = TitleRules.check(seventhCore());
        assertTrue(v.legal());
        assertEquals("VII", v.category());
    }

    @Test
    void fewerThanThirtyIsNot() {
        List<Card> deck = seventhCore();
        deck.remove(0);   // 27 VII
        assertFalse(TitleRules.check(deck).legal());
    }

    @Test
    void aCardOfAnotherCategoryIsNotAllowed() {
        List<Card> deck = seventhCore();
        deck.add(forward("10-1", "X", "", 1));
        Verdict v = TitleRules.check(deck);
        assertFalse(v.legal());
        assertTrue(v.reason().contains("10-1"), v.reason());
    }

    // A card of two categories ("MOBIUS · VII") belongs to both.
    @Test
    void aTwoCategoryCardCountsTowardEither() {
        List<Card> deck = seventhCore();
        deck.remove(0);
        deck.add(forward("M-1", "MOBIUS", "VII", 3));
        assertEquals("VII", TitleRules.check(deck).category());
    }

    @Test
    void standardUnitBackupsGoInAnyDeck() {
        List<Card> deck = seventhCore();
        deck.add(new Card("SU-1", "Backup", "Standard Unit", "", "", 3, false));
        assertTrue(TitleRules.check(deck).legal());
    }

    @Test
    void aStandardUnitForwardIsNoException() {
        List<Card> deck = seventhCore();
        deck.add(new Card("SU-2", "Forward", "Standard Unit", "", "", 1, false));
        assertFalse(TitleRules.check(deck).legal());
    }

    @Test
    void specialCardsGoInAnyDeck() {
        List<Card> deck = seventhCore();
        deck.add(forward("S-1", "Special", "", 3));
        assertTrue(TitleRules.check(deck).legal());
    }

    @Test
    void anExceptionIsCappedAtThreeCopies() {
        List<Card> deck = seventhCore();
        deck.add(forward("S-1", "Special", "", 4));
        assertFalse(TitleRules.check(deck).legal());
    }

    @Test
    void specialCannotBeTheDecksCategory() {
        List<Card> deck = new ArrayList<>();
        for (int i = 1; i <= 10; i++) deck.add(forward("S-" + i, "Special", "", 3));
        assertFalse(TitleRules.check(deck).legal());
    }

    @Test
    void limitBreakCardsAreNotAllowed() {
        List<Card> deck = seventhCore();
        deck.add(new Card("LB-1", "Forward", "", "VII", "", 1, true));
        assertFalse(TitleRules.check(deck).legal());
    }
}
