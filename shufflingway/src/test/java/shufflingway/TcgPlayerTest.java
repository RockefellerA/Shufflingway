package shufflingway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/** The links the Deck Manager's (for now commented-out) Buy Deck button would open. */
class TcgPlayerTest {

    /** A deck row as the Deck Manager holds it: copies, serial, name, type, Element, cost. */
    private static Object[] row(int copies, String serial, String name) {
        return new Object[] { copies, serial, name, "Forward", "Fire", 3, 7000, null, null, null };
    }

    private static List<Object[]> deck(Object[]... rows) {
        return Arrays.asList(rows);
    }

    /** The decoded {@code c} parameter of {@code url}: the Mass Entry list. */
    private static String massEntryList(String url) {
        String query = URI.create(url).getRawQuery();
        for (String part : query.split("&"))
            if (part.startsWith("c=")) return URLDecoder.decode(part.substring(2), StandardCharsets.UTF_8);
        throw new AssertionError("no c parameter in " + url);
    }

    // Serials no card has, so these read the deck's own names, whatever TCGplayer calls real cards.
    @Test
    void aDeckLinkFillsMassEntryWithEachCardAndItsCopies() {
        String url = TcgPlayer.deckUrl(deck(
                row(3, "T-001", "Alpha"),
                row(2, "T-002", "Beta Gamma"),
                row(1, "T-003", "Delta")));
        assertTrue(url.startsWith(TcgPlayer.MASS_ENTRY + "?productline=Final%20Fantasy%20TCG&c="), url);
        assertEquals("3 Alpha||2 Beta Gamma||1 Delta", massEntryList(url));
    }

    // A browser has to be able to open it as it is: no raw spaces, quotes or ampersands from a
    // card's name may reach the query string unencoded.
    @Test
    void namesWithPunctuationAreEncodedIntoAValidLink() {
        String url = TcgPlayer.deckUrl(deck(row(1, "T-001", "Auron & \"Yuna\" (FFX)")));
        assertTrue(url.matches("[\\x21-\\x7e]+"), "only printable, unspaced ASCII: " + url);
        URI.create(url);   // throws on anything a browser could not take as it is
        assertEquals("1 Auron & \"Yuna\" (FFX)", massEntryList(url));
    }

    @Test
    void aRowWithNoCopiesIsLeftOut() {
        assertEquals("2 Alpha", massEntryList(TcgPlayer.deckUrl(deck(row(2, "T-001", "Alpha"), row(0, "T-002", "Gone")))));
    }

    // The two cards Mass Entry could not find under their own names, in a browser on 2026-11-27.
    @Test
    void cardsTcgplayerNamesDifferentlyGoByItsName() {
        assertEquals("Cocytus EX", TcgPlayer.tcgplayerName("8-031R", "Cocytus"));
        // One name, two cards: TCGplayer tells them apart, and the serial says which is which.
        assertEquals("Squire (Female Unit)", TcgPlayer.tcgplayerName("1-025C", "Squire"));
        assertEquals("Squire (Male Unit)",   TcgPlayer.tcgplayerName("1-026C", "Squire"));
        assertEquals("2 Squire (Female Unit)||1 Cocytus EX", massEntryList(TcgPlayer.deckUrl(deck(
                row(2, "1-025C", "Squire"), row(1, "8-031R", "Cocytus")))));
    }

    @Test
    void aCardTcgplayerNamesTheSameKeepsItsOwnName() {
        assertEquals("Machina", TcgPlayer.tcgplayerName("22-013C", "Machina"));
        assertEquals("Alpha", TcgPlayer.tcgplayerName("T-001", "Alpha"));
    }

    // Until the affiliate programme approves Shufflingway, its links pass through untouched.
    @Test
    void theAffiliateWrapperLeavesLinksAsTheyAreForNow() {
        String dest = "https://www.tcgplayer.com/product/132375";
        assertEquals(dest, TcgPlayer.affiliate(dest));
    }
}
