package shufflingway;

import java.sql.SQLException;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import scraper.DeckDatabase;

/**
 * The Elements a deck is, as its {@link ElementBarsIcon} shows them wherever decks are listed: the
 * Deck Manager's deck lists and the New Game and lobby deck choices.
 *
 * <p>Those are the Elements of its main-deck cards, in printed order. Both Elements of a
 * multi-Element card count. Light and Dark do not, since they only supplement a deck's Elements,
 * and Limit Break cards are left out for the same reason.
 */
public final class DeckElements {

    private DeckElements() {}

    /**
     * @param rows      the deck as {@link DeckDatabase#getDeckCards} returns it: copies, serial,
     *                  name, type, element, …
     * @param lbSerials the serials of Limit Break cards
     */
    public static EnumSet<ElementColor> of(List<Object[]> rows, Set<String> lbSerials) {
        EnumSet<ElementColor> out = EnumSet.noneOf(ElementColor.class);
        for (Object[] r : rows)
            if (!lbSerials.contains((String) r[1])) addCard(out, (String) r[4]);
        return out;
    }

    /** Each listed deck's Elements, read from {@code db}. */
    public static Map<Integer, Set<ElementColor>> forDecks(DeckDatabase db, Collection<Integer> deckIds)
            throws SQLException {
        Set<String> lbSerials = db.getLbSerials();
        Map<Integer, Set<ElementColor>> out = new HashMap<>();
        for (int id : deckIds) out.put(id, of(db.getDeckCards(id), lbSerials));
        return out;
    }

    /** Adds one main-deck card's Elements, from its Element text, to a deck's, leaving out Light and Dark. */
    public static void addCard(EnumSet<ElementColor> deck, String elementText) {
        for (ElementColor ec : ElementBarsIcon.elementsOf(elementText))
            if (ec != ElementColor.LIGHT && ec != ElementColor.DARK) deck.add(ec);
    }
}
