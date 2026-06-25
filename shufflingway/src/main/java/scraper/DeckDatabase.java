package scraper;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * SQLite persistence layer for FFTCG saved decks.
 *
 * Stores decks and their card lists in the same shufflingway.db used by CardDatabase.
 * Tables are created automatically on first use.
 *
 * Usage:
 *   try (DeckDatabase db = new DeckDatabase()) {
 *       int id = db.createDeck("My Deck");
 *       db.setCardCount(id, "1-001H", 3);
 *   }
 */
public class DeckDatabase implements AutoCloseable {

    private static final String DB_URL = AppPaths.dbUrl();

    private final Connection conn;

    public DeckDatabase() throws SQLException {
        conn = DriverManager.getConnection(DB_URL);
        try (Statement s = conn.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("PRAGMA foreign_keys=ON");
        }
        createSchema();
    }

    // -------------------------------------------------------------------------
    // Schema
    // -------------------------------------------------------------------------

    private void createSchema() throws SQLException {
        try (Statement s = conn.createStatement()) {
            s.execute("""
                CREATE TABLE IF NOT EXISTS cards (
                    serial       TEXT PRIMARY KEY,
                    name_en      TEXT,
                    type_en      TEXT,
                    element      TEXT,
                    cost         INTEGER,
                    power        INTEGER,
                    rarity       TEXT,
                    job_en       TEXT,
                    category_1   TEXT,
                    category_2   TEXT,
                    ex_burst     INTEGER NOT NULL DEFAULT 0,
                    multicard    INTEGER NOT NULL DEFAULT 0,
                    text_en      TEXT,
                    thumb_name   TEXT,
                    image_url    TEXT,
                    image_data   BLOB,
                    set_number   TEXT,
                    limit_break  INTEGER NOT NULL DEFAULT 0,
                    lb_cost      INTEGER
                )
                """);
            try { s.execute("ALTER TABLE cards ADD COLUMN image_data BLOB"); } catch (SQLException ignored) {}
            try { s.execute("ALTER TABLE cards ADD COLUMN limit_break INTEGER NOT NULL DEFAULT 0"); } catch (SQLException ignored) {}
            try { s.execute("ALTER TABLE cards ADD COLUMN lb_cost INTEGER"); } catch (SQLException ignored) {}
            s.execute("""
                CREATE TABLE IF NOT EXISTS decks (
                    id         INTEGER PRIMARY KEY AUTOINCREMENT,
                    name       TEXT    NOT NULL,
                    created_at TEXT    DEFAULT (datetime('now')),
                    updated_at TEXT    DEFAULT (datetime('now'))
                )
                """);
            s.execute("""
                CREATE TABLE IF NOT EXISTS deck_cards (
                    deck_id INTEGER NOT NULL REFERENCES decks(id) ON DELETE CASCADE,
                    serial  TEXT    NOT NULL,
                    count   INTEGER NOT NULL DEFAULT 1,
                    PRIMARY KEY (deck_id, serial)
                )
                """);
        }
    }

    // -------------------------------------------------------------------------
    // Deck CRUD
    // -------------------------------------------------------------------------

    /** Creates a new empty deck and returns its generated ID. */
    public int createDeck(String name) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO decks (name) VALUES (?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, name);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                return rs.next() ? rs.getInt(1) : -1;
            }
        }
    }

    /** Renames an existing deck. */
    public void renameDeck(int id, String name) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE decks SET name = ?, updated_at = datetime('now') WHERE id = ?")) {
            ps.setString(1, name);
            ps.setInt(2, id);
            ps.executeUpdate();
        }
    }

    /** Deletes a deck and all its cards (cascades via foreign key). */
    public void deleteDeck(int id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM decks WHERE id = ?")) {
            ps.setInt(1, id);
            ps.executeUpdate();
        }
    }

    /** Creates a copy of an existing deck under a new name and returns the new deck's ID. */
    public int copyDeck(int sourceId, String newName) throws SQLException {
        int newId = createDeck(newName);
        conn.setAutoCommit(false);
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO deck_cards (deck_id, serial, count) "
                + "SELECT ?, serial, count FROM deck_cards WHERE deck_id = ?")) {
            ps.setInt(1, newId);
            ps.setInt(2, sourceId);
            ps.executeUpdate();
            conn.commit();
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(true);
        }
        return newId;
    }

    /** Returns all decks ordered by creation (oldest first). */
    public List<DeckEntry> getDecks() throws SQLException {
        List<DeckEntry> result = new ArrayList<>();
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery("SELECT id, name FROM decks ORDER BY id")) {
            while (rs.next()) result.add(new DeckEntry(rs.getInt("id"), rs.getString("name")));
        }
        return result;
    }

    /** Returns all decks with their total card counts, ordered by creation (oldest first). */
    public List<DeckSummary> getDecksSummary() throws SQLException {
        List<DeckSummary> result = new ArrayList<>();
        String sql = """
                SELECT d.id, d.name,
                    COALESCE(SUM(CASE WHEN c.limit_break = 0 THEN dc.count ELSE 0 END), 0) AS main_total,
                    COALESCE(SUM(CASE WHEN c.limit_break = 1 THEN dc.count ELSE 0 END), 0) AS lb_total
                FROM decks d
                LEFT JOIN deck_cards dc ON d.id = dc.deck_id
                LEFT JOIN cards c ON dc.serial = c.serial
                GROUP BY d.id, d.name
                ORDER BY d.id
                """;
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery(sql)) {
            while (rs.next()) {
                result.add(new DeckSummary(
                        rs.getInt("id"),
                        rs.getString("name"),
                        rs.getInt("main_total"),
                        rs.getInt("lb_total")));
            }
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Deck card operations
    // -------------------------------------------------------------------------

    /**
     * Returns every copy of every card in the deck (count expanded to individual entries).
     * Includes all fields needed to construct a {@link shufflingway.CardData} for game state.
     */
    public List<DeckCardDetail> getDeckCardsDetailed(int deckId) throws SQLException {
        List<DeckCardDetail> result = new ArrayList<>();
        String sql = """
            SELECT dc.count, c.image_url, c.name_en, c.element, c.cost, c.power, c.type_en, c.limit_break, c.lb_cost, c.ex_burst, c.text_en, c.multicard, c.job_en, c.category_1, c.category_2
            FROM deck_cards dc
            LEFT JOIN cards c ON dc.serial = c.serial
            WHERE dc.deck_id = ?
            ORDER BY dc.serial
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, deckId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String  imageUrl  = rs.getString("image_url");
                    String  name      = rs.getString("name_en");
                    String  element   = rs.getString("element");
                    int     cost      = rs.getInt("cost");
                    int     power     = rs.getInt("power");
                    String  type      = rs.getString("type_en");
                    boolean isLb      = rs.getInt("limit_break") == 1;
                    int     lbCost    = rs.getInt("lb_cost");
                    boolean exBurst   = rs.getInt("ex_burst") == 1;
                    String  textEn    = rs.getString("text_en");
                    boolean multicard = rs.getInt("multicard") == 1;
                    String  job       = rs.getString("job_en");
                    String  cat1      = rs.getString("category_1");
                    String  cat2      = rs.getString("category_2");
                    int     count     = rs.getInt("count");
                    for (int i = 0; i < count; i++)
                        result.add(new DeckCardDetail(imageUrl, name, element, cost, power, type, isLb, lbCost, exBurst, textEn, multicard, job, cat1, cat2));
                }
            }
        }
        return result;
    }

    /**
     * Returns the {@link DeckCardDetail} for a single card by serial (independent of any deck),
     * or {@code null} if the serial is not in the {@code cards} table. Used by the debug
     * "spawn card to CPU" tooling to build a {@link shufflingway.CardData} from any card.
     */
    public DeckCardDetail getCardDetailBySerial(String serial) throws SQLException {
        String sql = """
            SELECT image_url, name_en, element, cost, power, type_en, limit_break, lb_cost, ex_burst, text_en, multicard, job_en, category_1, category_2
            FROM cards
            WHERE serial = ?
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, serial);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new DeckCardDetail(
                        rs.getString("image_url"), rs.getString("name_en"), rs.getString("element"),
                        rs.getInt("cost"), rs.getInt("power"), rs.getString("type_en"),
                        rs.getInt("limit_break") == 1, rs.getInt("lb_cost"), rs.getInt("ex_burst") == 1,
                        rs.getString("text_en"), rs.getInt("multicard") == 1,
                        rs.getString("job_en"), rs.getString("category_1"), rs.getString("category_2"));
            }
        }
    }

    /**
     * Returns all cards in the deck joined with card metadata.
     * Each row: [count, serial, name_en, type_en, element, cost, power, job_en, category_1, category_2]
     */
    public List<Object[]> getDeckCards(int deckId) throws SQLException {
        List<Object[]> result = new ArrayList<>();
        String sql = """
            SELECT dc.count, dc.serial, c.name_en, c.type_en, c.element, c.cost, c.power, c.job_en, c.category_1, c.category_2
            FROM deck_cards dc
            LEFT JOIN cards c ON dc.serial = c.serial
            WHERE dc.deck_id = ?
            ORDER BY dc.serial
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, deckId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new Object[]{
                        rs.getInt("count"),
                        rs.getString("serial"),
                        rs.getString("name_en"),
                        rs.getString("type_en"),
                        rs.getString("element"),
                        rs.getObject("cost"),
                        rs.getObject("power"),
                        rs.getString("job_en"),
                        rs.getString("category_1"),
                        rs.getString("category_2")
                    });
                }
            }
        }
        return result;
    }

    /** Returns the total number of cards (sum of counts) in the deck. */
    public int getTotalCardCount(int deckId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COALESCE(SUM(count), 0) FROM deck_cards WHERE deck_id = ?")) {
            ps.setInt(1, deckId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /** Returns how many copies of a specific card are in the deck (0 if none). */
    public int getCardCount(int deckId, String serial) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT count FROM deck_cards WHERE deck_id = ? AND serial = ?")) {
            ps.setInt(1, deckId);
            ps.setString(2, serial);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt("count") : 0;
            }
        }
    }

    /** Returns true if a card with this serial exists in the cards table. */
    public boolean serialExists(String serial) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM cards WHERE serial = ?")) {
            ps.setString(1, serial);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /**
     * Resolves a serial to its canonical form in the DB.
     * Tries exact match first, then a prefix match for dual-serial cards
     * (e.g. FFDecks may give "6-022R" while the DB stores "6-022R/7-004C").
     * Returns null if no match is found.
     */
    public String resolveSerial(String serial) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT serial FROM cards WHERE serial = ?")) {
            ps.setString(1, serial);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString(1);
            }
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT serial FROM cards WHERE serial LIKE ? LIMIT 1")) {
            ps.setString(1, serial + "/%");
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString(1);
            }
        }
        return null;
    }

    /**
     * Sets the copy count for a card in a deck.
     * If count <= 0, the card entry is removed entirely.
     */
    public void setCardCount(int deckId, String serial, int count) throws SQLException {
        if (count <= 0) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM deck_cards WHERE deck_id = ? AND serial = ?")) {
                ps.setInt(1, deckId);
                ps.setString(2, serial);
                ps.executeUpdate();
            }
        } else {
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO deck_cards (deck_id, serial, count) VALUES (?, ?, ?)
                    ON CONFLICT(deck_id, serial) DO UPDATE SET count = excluded.count
                    """)) {
                ps.setInt(1, deckId);
                ps.setString(2, serial);
                ps.setInt(3, count);
                ps.executeUpdate();
            }
        }
        touchDeck(deckId);
    }

    // -------------------------------------------------------------------------
    // Card browser data (reuses the same connection to avoid extra open/close)
    // -------------------------------------------------------------------------

    /** Returns the set of serials whose limit_break flag is 1. */
    public Set<String> getLbSerials() throws SQLException {
        Set<String> result = new HashSet<>();
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery("SELECT serial FROM cards WHERE limit_break = 1")) {
            while (rs.next()) result.add(rs.getString("serial"));
        }
        return result;
    }

    /**
     * Returns all cards from the cards table for the inline browser.
     * Each row: [serial, name_en, type_en, element, cost, power, rarity, job_en, category_1, category_2]
     */
    public List<Object[]> getAllCards() throws SQLException {
        List<Object[]> result = new ArrayList<>();
        String sql = "SELECT serial, name_en, type_en, element, cost, power, rarity, "
                   + "job_en, category_1, category_2, text_en FROM cards "
                   + "WHERE type_en != 'Crystal' AND serial NOT LIKE 'B%' ORDER BY serial";
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery(sql)) {
            while (rs.next()) {
                result.add(new Object[]{
                    rs.getString("serial"),
                    rs.getString("name_en"),
                    rs.getString("type_en"),
                    rs.getString("element"),
                    rs.getObject("cost"),
                    rs.getObject("power"),
                    rs.getString("rarity"),
                    rs.getString("job_en"),
                    rs.getString("category_1"),
                    rs.getString("category_2"),
                    rs.getString("text_en")
                });
            }
        }
        return result;
    }

    /**
     * Returns serial, category_1, category_2, and count for all cards in the deck.
     * Used for format legality checks (Title format).
     */
    public List<Object[]> getDeckCardsWithCategories(int deckId) throws SQLException {
        List<Object[]> result = new ArrayList<>();
        String sql = """
            SELECT dc.serial, c.category_1, c.category_2, dc.count
            FROM deck_cards dc
            LEFT JOIN cards c ON dc.serial = c.serial
            WHERE dc.deck_id = ?
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, deckId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new Object[]{
                        rs.getString("serial"),
                        rs.getString("category_1"),
                        rs.getString("category_2"),
                        rs.getInt("count")
                    });
                }
            }
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void touchDeck(int deckId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE decks SET updated_at = datetime('now') WHERE id = ?")) {
            ps.setInt(1, deckId);
            ps.executeUpdate();
        }
    }

    @Override
    public void close() throws SQLException {
        conn.close();
    }

    // -------------------------------------------------------------------------
    // DeckEntry record
    // -------------------------------------------------------------------------

    /** Lightweight representation of a saved deck for display in the UI. */
    public record DeckEntry(int id, String name) {
        @Override
        public String toString() { return name; }
    }

    /** A single expanded card entry from a deck — one instance per copy. */
    public record DeckCardDetail(String imageUrl, String name, String element, int cost, int power, String type, boolean isLb, int lbCost, boolean exBurst, String textEn, boolean multicard, String job, String category1, String category2) {}

    /** Deck with separate main and LB card counts, used for deck selection. */
    public record DeckSummary(int id, String name, int mainCardCount, int lbCardCount) {
        public int totalCardCount() { return mainCardCount + lbCardCount; }
        @Override
        public String toString() { return name; }
    }
}
