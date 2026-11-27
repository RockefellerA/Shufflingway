package shufflingway;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * What a deck's main-deck cards add up to, for the Deck Manager's charts: how many cards of each
 * Element, and how many at each cost. Every copy counts.
 *
 * <p>A multi-Element card counts once toward each of its Elements, as the deck lists' Element bars
 * read them, so the Element counts can add up to more than the deck. Light and Dark are counted
 * like any other Element: the chart shows what the deck is made of. Limit Break cards are not in
 * the main deck, and the caller leaves them out.
 *
 * @param cards    the main-deck cards counted
 * @param elements cards of each Element, in the game's Element order; Elements with none are absent
 * @param costs    cards at each cost, cheapest first; costs with none are absent
 */
record DeckStats(int cards, Map<ElementColor, Integer> elements, SortedMap<Integer, Integer> costs) {

    /** The cost axis always reaches at least this far, so a cheap deck's curve keeps its width. */
    static final int MIN_TOP_COST = 6;

    static final DeckStats EMPTY = of(List.of());

    /**
     * @param rows main-deck rows as the Deck Manager holds them: copies, serial, name, type, Element
     *             text ("Fire/Water"), cost, …
     */
    static DeckStats of(Collection<Object[]> rows) {
        int cards = 0;
        Map<ElementColor, Integer> elements = new EnumMap<>(ElementColor.class);
        SortedMap<Integer, Integer> costs = new TreeMap<>();
        for (Object[] r : rows) {
            int copies = r[0] instanceof Number n ? n.intValue() : 0;
            if (copies <= 0) continue;
            cards += copies;
            for (ElementColor ec : ElementBarsIcon.elementsOf((String) r[4]))
                elements.merge(ec, copies, Integer::sum);
            if (r[5] instanceof Number cost) costs.merge(cost.intValue(), copies, Integer::sum);
        }
        return new DeckStats(cards, Collections.unmodifiableMap(elements), Collections.unmodifiableSortedMap(costs));
    }

    /**
     * The costs the cost curve lays out, cheapest first: from 1 up to the dearest card, or to
     * {@link #MIN_TOP_COST} if that is further, with 0 in front only when some card costs 0.
     */
    List<Integer> costAxis() {
        int from = costs.containsKey(0) ? 0 : 1;
        int to   = Math.max(MIN_TOP_COST, costs.isEmpty() ? 0 : costs.lastKey());
        List<Integer> axis = new ArrayList<>();
        for (int c = from; c <= to; c++) axis.add(c);
        return axis;
    }

    /** Cards at {@code cost}, 0 when there are none. */
    int atCost(int cost) {
        return costs.getOrDefault(cost, 0);
    }

    /** The most cards at any one cost, which sets the curve's height; 0 for an empty deck. */
    int tallestCost() {
        int max = 0;
        for (int n : costs.values()) max = Math.max(max, n);
        return max;
    }
}
