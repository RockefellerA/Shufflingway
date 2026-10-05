package shufflingway.dialog;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import scraper.DeckDatabase;
import scraper.DeckDatabase.DeckSummary;
import shufflingway.Banlist;
import shufflingway.DeckFormat;

/**
 * Which decks a game's rules refuse, and why — the one place the deck pickers ask, so the CPU
 * game and every lobby refuse the same decks for the same reasons. The 50-card main deck is the
 * pickers' own check and is not repeated here.
 *
 * <p>The format comes first and the banlist second: a deck outside L3's sets is reported as that,
 * whatever else is wrong with it. The banlist is the Standard one for every format that has a set
 * window, as in the Deck Manager.
 */
final class DeckRules {

    private DeckRules() {}

    /**
     * The decks among {@code decks} that {@code format} and, if on, the banlist refuse, mapped to
     * a one-line reason for the tooltip. A deck absent from the map is allowed.
     */
    static Map<Integer, String> refusals(DeckDatabase db, List<DeckSummary> decks,
            DeckFormat format, boolean banlist) throws SQLException {
        Map<Integer, String> out = new HashMap<>();
        boolean checksSets = format.hasSetWindow();
        if (!checksSets && !banlist) return out;
        int newest = checksSets ? db.getNewestSet() : 0;
        Banlist list = Banlist.get();
        for (DeckSummary d : decks) {
            List<Object[]> rows = db.getDeckCards(d.id());
            if (checksSets) {
                List<String> serials = new ArrayList<>(rows.size());
                for (Object[] r : rows) serials.add((String) r[1]);
                if (!format.setsAllow(serials, newest)) {
                    out.put(d.id(), "Not legal in " + format.label() + ": has cards from outside the latest sets");
                    continue;
                }
            }
            if (banlist && !list.check(Banlist.STANDARD, Banlist.fromDeckRows(rows)).isEmpty())
                out.put(d.id(), "Breaks the banlist");
        }
        return out;
    }
}
