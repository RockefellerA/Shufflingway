package shufflingway;

import java.util.*;

/**
 * Holds all mutable game state for an in-progress FFTCG match.
 * Pure data/logic — no Swing dependencies.
 */
public class GameState {

    /** A card sitting in the Removed-From-Play zone via the Warp trait, with its remaining counter. */
    public static class WarpEntry {
        public final CardData card;
        public int counters;
        public WarpEntry(CardData card, int counters) {
            this.card     = card;
            this.counters = counters;
        }
    }

    // --- Named counters on field cards (e.g. Shuriken Counters on Character) ---
    // IdentityHashMap so different CardData instances with the same name don't alias each other.
    private final java.util.IdentityHashMap<CardData, Map<String, Integer>> cardCounters =
            new java.util.IdentityHashMap<>();

    /** Places {@code count} counters named {@code name} on {@code card}. */
    public void placeCounters(CardData card, String name, int count) {
        if (count <= 0) return;
        cardCounters.computeIfAbsent(card, k -> new java.util.LinkedHashMap<>())
                    .merge(name, count, Integer::sum);
    }

    /** Returns how many counters named {@code name} are currently on {@code card} (0 if none). */
    public int getCounters(CardData card, String name) {
        Map<String, Integer> m = cardCounters.get(card);
        return m == null ? 0 : m.getOrDefault(name, 0);
    }

    /**
     * Removes up to {@code count} counters named {@code name} from {@code card}.
     * @return the number actually removed (may be less than {@code count})
     */
    public int removeCounters(CardData card, String name, int count) {
        Map<String, Integer> m = cardCounters.get(card);
        if (m == null) return 0;
        int current = m.getOrDefault(name, 0);
        int removed = Math.min(current, count);
        if (removed > 0) {
            int remaining = current - removed;
            if (remaining == 0) m.remove(name); else m.put(name, remaining);
            if (m.isEmpty()) cardCounters.remove(card);
        }
        return removed;
    }

    /** Removes all counters from {@code card} (called when it leaves the field). */
    public void clearCounters(CardData card) { cardCounters.remove(card); }

    /** Returns an unmodifiable view of all counter entries for {@code card}. */
    public Map<String, Integer> getCountersMap(CardData card) {
        Map<String, Integer> m = cardCounters.get(card);
        return m == null ? Map.of() : java.util.Collections.unmodifiableMap(m);
    }

    // --- Crystals (persistent resource; does not expire like CP) ---
    private int p1Crystals = 0;
    private int p2Crystals = 0;

    // --- P1 ---
    private final Deque<CardData>          p1MainDeck        = new ArrayDeque<>();
    private final List<CardData>           p1LbDeck          = new ArrayList<>();
    private final List<CardData>           p1Hand            = new ArrayList<>();
    private final List<CardData>           p1BreakZone       = new ArrayList<>();
    private final List<CardData>           p1DamageZone      = new ArrayList<>();
    private final List<WarpEntry>          p1WarpZone        = new ArrayList<>();
    private final List<WarpEntry>          p2WarpZone        = new ArrayList<>();
    private final List<CardData>           p1PermanentRfp    = new ArrayList<>();
    private final List<CardData>           p2PermanentRfp    = new ArrayList<>();
    private final Map<String, Integer>     p1CpByElement     = new HashMap<>();
    private boolean p1OpeningHandPending  = false;
    private boolean p1MulliganUsed        = false;
    private boolean p1GameOver            = false;

    // --- Shared ---
    private final List<StackEntry>         stack        = new ArrayList<>();
    private final IdentityHashMap<CardData, Boolean> identity = new IdentityHashMap<>();

    // --- P2 ---
    private final Deque<CardData>          p2MainDeck    = new ArrayDeque<>();
    private final List<CardData>           p2LbDeck      = new ArrayList<>();
    private final List<CardData>           p2DamageZone  = new ArrayList<>();
    private final List<CardData>           p2Hand        = new ArrayList<>();
    private final List<CardData>           p2BreakZone   = new ArrayList<>();
    private final Map<String, Integer>     p2CpByElement = new HashMap<>();

    // -------------------------------------------------------------------------
    // Player tracking
    // -------------------------------------------------------------------------

    public enum Player { P1, P2 }
    private Player currentPlayer = Player.P1;

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    // -------------------------------------------------------------------------
    // Crystals
    // -------------------------------------------------------------------------

    public int  getP1Crystals()           { return p1Crystals; }
    public void addP1Crystals(int n)      { p1Crystals += n; }
    public boolean spendP1Crystals(int n) { if (p1Crystals < n) return false; p1Crystals -= n; return true; }

    public int  getP2Crystals()           { return p2Crystals; }
    public void addP2Crystals(int n)      { p2Crystals += n; }
    public boolean spendP2Crystals(int n) { if (p2Crystals < n) return false; p2Crystals -= n; return true; }

    // -------------------------------------------------------------------------

    /** Resets all state for a new game. */
    public void reset() {
        p1MainDeck.clear();
        p1LbDeck.clear();
        p1Hand.clear();
        p1BreakZone.clear();
        p1DamageZone.clear();
        p1WarpZone.clear();
        p2WarpZone.clear();
        p1PermanentRfp.clear();
        p2PermanentRfp.clear();
        p1CpByElement.clear();
        p1Crystals    = 0;
        p2Crystals    = 0;
        p1OpeningHandPending = false;
        p1MulliganUsed       = false;
        p1GameOver           = false;
        stack.clear();
        p2MainDeck.clear();
        p2LbDeck.clear();
        p2DamageZone.clear();
        p2Hand.clear();
        p2BreakZone.clear();
        p2CpByElement.clear();
        currentPhase  = null;
        turnNumber    = 0;
        currentPlayer = Player.P1;
    }

    // -------------------------------------------------------------------------
    // Warp zone
    // -------------------------------------------------------------------------

    /** Moves a card into P1's Removed-From-Play zone with {@code counters} Warp counters. */
    public void addToP1WarpZone(CardData card, int counters) {
        p1WarpZone.add(new WarpEntry(card, counters));
    }

    /** Returns an unmodifiable view of P1's Warp zone. */
    public List<WarpEntry> getP1WarpZone() {
        return Collections.unmodifiableList(p1WarpZone);
    }

    /**
     * Decrements the Warp counter of every card in P1's Warp zone by 1.
     * Removes and returns any cards whose counter reached zero.
     */
    public List<CardData> tickP1WarpCounters() {
        return tickWarpCounters(p1WarpZone);
    }

    /** Moves a card into P2's Removed-From-Play zone with {@code counters} Warp counters. */
    public void addToP2WarpZone(CardData card, int counters) {
        p2WarpZone.add(new WarpEntry(card, counters));
    }

    /** Returns an unmodifiable view of P2's Warp zone. */
    public List<WarpEntry> getP2WarpZone() {
        return Collections.unmodifiableList(p2WarpZone);
    }

    /**
     * Decrements the Warp counter of every card in P2's Warp zone by 1.
     * Removes and returns any cards whose counter reached zero.
     */
    public List<CardData> tickP2WarpCounters() {
        return tickWarpCounters(p2WarpZone);
    }

    private static List<CardData> tickWarpCounters(List<WarpEntry> zone) {
        List<CardData> resolved = new ArrayList<>();
        java.util.Iterator<WarpEntry> it = zone.iterator();
        while (it.hasNext()) {
            WarpEntry entry = it.next();
            entry.counters--;
            if (entry.counters <= 0) {
                resolved.add(entry.card);
                it.remove();
            }
        }
        return resolved;
    }

    // -------------------------------------------------------------------------
    // Permanent Removed-From-Game zone (Primed top cards, etc.)
    // -------------------------------------------------------------------------

    public void addToPermanentRfp(CardData card)
    {
        List<CardData> zone = this.identity.get(card) ? this.getP1PermanentRfp() : this.getP2PermanentRfp();
        zone.add(card);
    }

    public boolean removeFromPermanentRfp(CardData card)
    {
        List<CardData> zone = this.identity.get(card) ? this.getP1PermanentRfp() : this.getP2PermanentRfp();

        for (int i = 0; i < zone.size(); i++) {
            if(zone.get(i) == card) {
                zone.remove(i);
                return true;
            }
        }

        return false;
    }

    /** Returns an unmodifiable view of P1's permanently removed cards. */
    public List<CardData> getP1PermanentRfp() {
        return Collections.unmodifiableList(p1PermanentRfp);
    }

    /** Returns an unmodifiable view of P2's permanently removed cards. */
    public List<CardData> getP2PermanentRfp() {
        return Collections.unmodifiableList(p2PermanentRfp);
    }

    // -------------------------------------------------------------------------
    // Deck search
    // -------------------------------------------------------------------------

    /**
     * Returns all cards in P1's main deck whose name matches {@code name}
     * (case-insensitive), without removing them.
     */
    public List<CardData> findMatchingNamesInP1MainDeck(String name) {
        List<CardData> result = new ArrayList<>();
        for (CardData c : p1MainDeck)
            if (name.equalsIgnoreCase(c.name())) result.add(c);
        return result;
    }

    /**
     * Removes the first occurrence of {@code card} (by record equality) from
     * P1's main deck.  Returns {@code true} if the card was found and removed.
     * The caller is responsible for shuffling after searching.
     */
    public boolean removeFromP1MainDeck(CardData card) {
        java.util.Iterator<CardData> it = p1MainDeck.iterator();
        while (it.hasNext()) {
            if (it.next().equals(card)) { it.remove(); return true; }
        }
        return false;
    }

    /**
     * Searches P1's main deck for the first card whose name matches {@code name}
     * (case-insensitive), removes it, and returns it.  Returns {@code null} if
     * no match is found.  The caller is responsible for shuffling after searching.
     */
    public CardData searchAndRemoveFromP1MainDeck(String name) {
        java.util.Iterator<CardData> it = p1MainDeck.iterator();
        while (it.hasNext()) {
            CardData c = it.next();
            if (name.equalsIgnoreCase(c.name())) {
                it.remove();
                return c;
            }
        }
        return null;
    }

    public List<CardData> findMatchingNamesInP2MainDeck(String name) {
        List<CardData> result = new ArrayList<>();
        for (CardData c : p2MainDeck)
            if (name.equalsIgnoreCase(c.name())) result.add(c);
        return result;
    }

    public boolean removeFromP2MainDeck(CardData card) {
        java.util.Iterator<CardData> it = p2MainDeck.iterator();
        while (it.hasNext()) {
            if (it.next().equals(card)) { it.remove(); return true; }
        }
        return false;
    }

    /**
     * Populates decks from lists of CardData (already separated into main / LB).
     * Shuffles the main deck before loading it.
     */
    public void initializeDeck(List<CardData> mainCards, List<CardData> lbCards) {
        List<CardData> shuffled = new ArrayList<>(mainCards);
        Collections.shuffle(shuffled);
        for (CardData c :  shuffled) identity.put(c, true);
        p1MainDeck.addAll(shuffled);
        p1LbDeck.addAll(lbCards);
        p1OpeningHandPending = true;
    }

    // -------------------------------------------------------------------------
    // Opening hand
    // -------------------------------------------------------------------------

    /**
     * Draws up to 5 cards from the top of the main deck.
     *
     * @return the drawn cards in draw order (index 0 = first card drawn)
     */
    public List<CardData> drawOpeningHand() {
        List<CardData> drawn = new ArrayList<>();
        for (int i = 0; i < 5 && !p1MainDeck.isEmpty(); i++) {
            drawn.add(p1MainDeck.poll());
        }
        return drawn;
    }

    /**
     * Mulligan: places {@code bottomOrder} on the bottom of the main deck in
     * that order, marks the mulligan as used, then draws a fresh 5 cards.
     *
     * @param bottomOrder the opening-hand cards in the order the player wants
     *                    them placed on the bottom (index 0 goes first / deepest)
     * @return the newly drawn 5 cards
     */
    public List<CardData> mulligan(List<CardData> bottomOrder) {
        for (CardData card : bottomOrder) {
            p1MainDeck.addLast(card);
        }
        p1MulliganUsed = true;
        return drawOpeningHand();
    }

    /**
     * Finalises the opening hand: moves the given cards into p1Hand and
     * clears the opening-hand-pending flag.
     *
     * @param cards the cards in the order they were kept
     */
    public void keepHand(List<CardData> cards) {
        p1Hand.addAll(cards);
        p1OpeningHandPending = false;
    }

    // -------------------------------------------------------------------------
    // Draw
    // -------------------------------------------------------------------------

    /**
     * Draws {@code count} cards from the top of the main deck into the hand.
     *
     * @return the cards actually drawn (may be fewer than {@code count} if the deck runs out)
     */
    public List<CardData> drawToHand(int count) {
        List<CardData> drawn = new ArrayList<>();
        for (int i = 0; i < count && !p1MainDeck.isEmpty(); i++) {
            CardData card = p1MainDeck.poll();
            p1Hand.add(card);
            drawn.add(card);
        }
        return drawn;
    }

    // -------------------------------------------------------------------------
    // Damage zone
    // -------------------------------------------------------------------------

    /**
     * Draws the top card of the main deck into the damage zone.
     *
     * @return the card placed in the damage zone, or {@code null} if the deck is empty
     */
    public CardData drawToDamageZone() {
        if (p1MainDeck.isEmpty()) return null;
        CardData card = p1MainDeck.poll();
        p1DamageZone.add(card);
        return card;
    }

    /** Draws the top card of P2's deck into P2's damage zone. */
    public CardData drawToP2DamageZone() {
        if (p2MainDeck.isEmpty()) return null;
        CardData card = p2MainDeck.poll();
        p2DamageZone.add(card);
        return card;
    }

    /** Loads P2's LB deck (not shuffled — order is preserved as the hidden zone). */
    public void initializeP2LbDeck(List<CardData> lbCards) {
        p2LbDeck.addAll(lbCards);
    }

    public List<CardData> getP2LbDeck() { return p2LbDeck; }

    /** Shuffles {@code mainCards}, loads them as P2's main deck, and draws P2's 5-card opening hand. */
    public void initializeP2Deck(List<CardData> mainCards) {
        List<CardData> shuffled = new ArrayList<>(mainCards);
        Collections.shuffle(shuffled);
        for (CardData c :  shuffled) identity.put(c, false);
        p2MainDeck.addAll(shuffled);
        for (int i = 0; i < 5 && !p2MainDeck.isEmpty(); i++) {
            p2Hand.add(p2MainDeck.poll());
        }
    }

    // -------------------------------------------------------------------------
    // Hand actions
    // -------------------------------------------------------------------------

    /**
     * Discards a card from the player's hand to the Break Zone and awards 2 CP
     * of the card's element.  Light and Dark cards cannot be discarded for CP
     * (they are still moved to the Break Zone but grant 0 CP).
     *
     * @param idx index into p1Hand
     * @return the discarded CardData, or {@code null} if idx is invalid
     */
    public CardData discardFromHand(int idx) {
        if (idx < 0 || idx >= p1Hand.size()) return null;
        CardData card = p1Hand.remove(idx);
        p1BreakZone.add(card);
        if (!card.isLightOrDark()) {
            addP1Cp(card.element(), 2);
        }
        return card;
    }

    /**
     * Removes a card from the player's hand without sending it to the Break Zone
     * and without granting CP.  Used when playing a card (the card goes to a zone
     * via separate logic).
     *
     * @param idx index into p1Hand
     * @return the removed CardData, or {@code null} if idx is invalid
     */
    public CardData removeFromHand(int idx) {
        if (idx < 0 || idx >= p1Hand.size()) return null;
        return p1Hand.remove(idx);
    }

    /**
     * Sends a card from the player's hand to the Break Zone without granting CP.
     * Used for payment discards (CP is credited separately via addP1Cp with the
     * correct contributing element) and for mandatory end-phase discards.
     *
     * @param idx index into p1Hand
     * @return the discarded CardData, or {@code null} if idx is invalid
     */
    public CardData breakFromHand(int idx) {
        if (idx < 0 || idx >= p1Hand.size()) return null;
        CardData card = p1Hand.remove(idx);
        p1BreakZone.add(card);
        return card;
    }

    /** P2 equivalent of {@link #breakFromHand}: moves a P2 hand card to the P2 Break Zone without granting CP. */
    public CardData breakP2FromHand(int idx) {
        if (idx < 0 || idx >= p2Hand.size()) return null;
        CardData card = p2Hand.remove(idx);
        p2BreakZone.add(card);
        return card;
    }

    // -------------------------------------------------------------------------
    // Crystal Points
    // -------------------------------------------------------------------------

    /**
     * Returns the current CP for the given element (0 if never accumulated).
     */
    public int getP1CpForElement(String element) {
        return p1CpByElement.getOrDefault(element, 0);
    }

    /** Returns an unmodifiable view of all CP buckets. */
    public Map<String, Integer> getP1CpByElement() {
        return Collections.unmodifiableMap(p1CpByElement);
    }

    /** Adds {@code amount} CP to the given element bucket. */
    public void addP1Cp(String element, int amount) {
        p1CpByElement.merge(element, amount, Integer::sum);
    }

    /**
     * Spends {@code amount} CP from the given element bucket if available.
     *
     * @return {@code true} if the spend succeeded, {@code false} if insufficient CP
     */
    public boolean spendP1Cp(String element, int amount) {
        int current = getP1CpForElement(element);
        if (current < amount) return false;
        p1CpByElement.put(element, current - amount);
        return true;
    }

    /** Zeroes the CP bucket for the given element. */
    public void clearP1Cp(String element) {
        p1CpByElement.put(element, 0);
    }

    /** Zeroes the P2 CP bucket for the given element. */
    public void clearP2Cp(String element) {
        p2CpByElement.put(element, 0);
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    /** Pushes an entry onto the top of the Stack. */
    public void pushStack(StackEntry entry)         { stack.add(entry); }

    /** Removes and returns the top entry of the Stack, or {@code null} if empty. */
    public StackEntry popStack() {
        return stack.isEmpty() ? null : stack.remove(stack.size() - 1);
    }

    /** Returns the top entry of the Stack without removing it, or {@code null} if empty. */
    public StackEntry peekStack()                   { return stack.isEmpty() ? null : stack.get(stack.size() - 1); }

    /** Returns an unmodifiable view of the Stack (index 0 = bottom, last = top). */
    public List<StackEntry> getStack()              { return Collections.unmodifiableList(stack); }

    /** Returns IdentityHashMap, Boolean = True: Belongs to P1, False: Belongs to P2/CPU */
    public IdentityHashMap<CardData, Boolean> getIdentity() { return identity; }

    public Deque<CardData> getP1MainDeck()          { return p1MainDeck; }
    public List<CardData>  getP1LbDeck()            { return p1LbDeck; }
    public List<CardData>  getP1Hand()              { return p1Hand; }
    public List<CardData>  getP1BreakZone()         { return p1BreakZone; }
    public List<CardData>  getP1DamageZone()        { return p1DamageZone; }
    public boolean         isP1OpeningHandPending() { return p1OpeningHandPending; }
    public boolean         isP1MulliganUsed()       { return p1MulliganUsed; }
    public boolean         isP1GameOver()           { return p1GameOver; }
    public void            setP1GameOver(boolean v) { p1GameOver = v; }
    public Deque<CardData> getP2MainDeck()          { return p2MainDeck; }
    public List<CardData>  getP2DamageZone()        { return p2DamageZone; }

    // -------------------------------------------------------------------------
    // Phase / turn tracking
    // -------------------------------------------------------------------------

    /**
     * The six phases of a FFTCG turn, in order.
     * Calling {@link #next()} wraps END back to ACTIVE.
     */
    public enum GamePhase {
        ACTIVE ("Active Phase"),
        DRAW   ("Draw Phase"),
        MAIN_1 ("Main Phase 1"),
        ATTACK ("Attack Phase"),
        MAIN_2 ("Main Phase 2"),
        END    ("End Phase");

        public final String displayName;
        GamePhase(String displayName) { this.displayName = displayName; }

        /** Returns the phase that follows this one in turn order. */
        public GamePhase next() {
            GamePhase[] vals = values();
            return vals[(ordinal() + 1) % vals.length];
        }
    }

    private GamePhase currentPhase = null;  // null ⇒ game not yet started
    private int       turnNumber   = 0;

    /** Begins the very first turn: sets phase to Active, turn 1, {@code firstPlayer} to move. */
    public void startFirstTurn(Player firstPlayer) {
        currentPhase  = GamePhase.ACTIVE;
        turnNumber    = 1;
        currentPlayer = firstPlayer;
    }

    /**
     * Advances to the next phase in turn order.
     * When {@link GamePhase#END} wraps back to {@link GamePhase#ACTIVE}
     * the turn number is incremented.
     *
     * @return the newly entered phase
     */
    public GamePhase advancePhase() {
        if (currentPhase == null) {
            currentPhase  = GamePhase.ACTIVE;
            turnNumber    = 1;
            currentPlayer = Player.P1;
        } else {
            if (currentPhase == GamePhase.END) {
                turnNumber++;
                currentPlayer = (currentPlayer == Player.P1) ? Player.P2 : Player.P1;
            }
            currentPhase = currentPhase.next();
        }
        return currentPhase;
    }

    /**
     * Advances the phase from END to ACTIVE for an extra-turn grant: increments the
     * turn number but keeps the current player unchanged.  Only valid when the current
     * phase is {@link GamePhase#END}.
     */
    public GamePhase advancePhaseExtraTurn() {
        turnNumber++;
        currentPhase = currentPhase.next(); // END → ACTIVE
        return currentPhase;
    }

    public GamePhase getCurrentPhase()   { return currentPhase; }
    public int       getTurnNumber()     { return turnNumber; }
    public Player    getCurrentPlayer()  { return currentPlayer; }

    // -------------------------------------------------------------------------
    // P2 hand / draw
    // -------------------------------------------------------------------------

    public List<CardData> drawP2ToHand(int count) {
        List<CardData> drawn = new ArrayList<>();
        for (int i = 0; i < count && !p2MainDeck.isEmpty(); i++) {
            CardData card = p2MainDeck.poll();
            p2Hand.add(card);
            drawn.add(card);
        }
        return drawn;
    }

    public CardData discardP2FromHand(int idx) {
        if (idx < 0 || idx >= p2Hand.size()) return null;
        CardData card = p2Hand.remove(idx);
        p2BreakZone.add(card);
        if (!card.isLightOrDark()) {
            addP2Cp(card.element(), 2);
        }
        return card;
    }

    public CardData removeP2FromHand(int idx) {
        if (idx < 0 || idx >= p2Hand.size()) return null;
        return p2Hand.remove(idx);
    }

    // -------------------------------------------------------------------------
    // P2 Crystal Points
    // -------------------------------------------------------------------------

    public int getP2CpForElement(String element) {
        return p2CpByElement.getOrDefault(element, 0);
    }

    public Map<String, Integer> getP2CpByElement() {
        return Collections.unmodifiableMap(p2CpByElement);
    }

    public void addP2Cp(String element, int amount) {
        p2CpByElement.merge(element, amount, Integer::sum);
    }

    public boolean spendP2Cp(String element, int amount) {
        int current = getP2CpForElement(element);
        if (current < amount) return false;
        p2CpByElement.put(element, current - amount);
        return true;
    }

    // -------------------------------------------------------------------------
    // P2 accessors
    // -------------------------------------------------------------------------

    public List<CardData> getP2Hand()      { return p2Hand; }
    public List<CardData> getP2BreakZone() { return p2BreakZone; }
}
