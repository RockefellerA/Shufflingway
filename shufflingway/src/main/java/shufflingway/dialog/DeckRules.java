package shufflingway.dialog;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import scraper.DeckDatabase;
import scraper.DeckDatabase.DeckSummary;
import shufflingway.Banlist;
import shufflingway.DeckFormat;
import shufflingway.TitleRules;

/**
 * Which decks a game's rules refuse, and why — the one place the deck pickers ask, so the CPU
 * game and every lobby refuse the same decks for the same reasons. The 50-card main deck is the
 * pickers' own check and is not repeated here.
 *
 * <p>The format comes first and the banlist second: a deck outside L3's sets is reported as that,
 * whatever else is wrong with it. Each format plays under its own banlist section
 * ({@link DeckFormat#banlistName()}); an empty section, as L3's and L6's are, bans nothing
 * even with the banlist enabled.
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
        Banlist list = Banlist.get();
        if (format == DeckFormat.TITLE) {
            Set<String> lb = db.getLbSerials();
            for (DeckSummary d : decks) {
                List<Object[]> rows = db.getDeckCards(d.id());
                TitleRules.Verdict v = TitleRules.check(TitleRules.fromDeckRows(rows, lb));
                if (!v.legal())
                    out.put(d.id(), "Not legal in Title: " + v.reason());
                else if (banlist && !list.check(format.banlistName(), Banlist.fromDeckRows(rows)).isEmpty())
                    out.put(d.id(), "Breaks the Title banlist");
            }
            return out;
        }
        boolean checksSets = format.hasSetWindow();
        if (!checksSets && !banlist) return out;
        int newest = checksSets ? db.getNewestSet() : 0;
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
            if (banlist && !list.check(format.banlistName(), Banlist.fromDeckRows(rows)).isEmpty())
                out.put(d.id(), "Breaks the banlist");
        }
        return out;
    }
}
