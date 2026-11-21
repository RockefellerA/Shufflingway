package scraper;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import scraper.DeckDatabase.DeckEntry;
import scraper.DeckDatabase.DeckSummary;

/**
 * The deck list's order, which the player sets by dragging in the deck manager: saved, read back
 * by both deck listings, and kept for a deck database from before the order existed.
 */
class DeckDatabaseOrderTest {

    @TempDir Path dir;

    private String url() {
        return "jdbc:sqlite:" + dir.resolve("decks.db");
    }

    private static List<String> names(DeckDatabase db) throws SQLException {
        return db.getDecks().stream().map(DeckEntry::name).toList();
    }

    @Test
    void decksListInTheOrderTheyWereMadeUntilMoved() throws SQLException {
        try (DeckDatabase db = new DeckDatabase(url())) {
            db.createDeck("Ice");
            db.createDeck("Fire");
            db.createDeck("Wind");
            assertEquals(List.of("Ice", "Fire", "Wind"), names(db));
        }
    }

    @Test
    void aSavedOrderIsWhatBothListingsReadAndItOutlastsTheConnection() throws SQLException {
        int ice, fire, wind;
        try (DeckDatabase db = new DeckDatabase(url())) {
            ice  = db.createDeck("Ice");
            fire = db.createDeck("Fire");
            wind = db.createDeck("Wind");
            db.reorderDecks(List.of(wind, ice, fire));

            assertEquals(List.of("Wind", "Ice", "Fire"), names(db));
            assertEquals(List.of("Wind", "Ice", "Fire"),
                    db.getDecksSummary().stream().map(DeckSummary::name).toList(),
                    "the deck chooser and the deck-select dialog read this listing");
        }
        try (DeckDatabase db = new DeckDatabase(url())) {
            assertEquals(List.of("Wind", "Ice", "Fire"), names(db), "saved, not just shown");
        }
    }

    @Test
    void aNewDeckOrACopyJoinsAtTheBottom() throws SQLException {
        try (DeckDatabase db = new DeckDatabase(url())) {
            int ice  = db.createDeck("Ice");
            int fire = db.createDeck("Fire");
            db.reorderDecks(List.of(fire, ice));

            db.createDeck("Wind");
            db.copyDeck(fire, "Fire (Copy)");

            assertEquals(List.of("Fire", "Ice", "Wind", "Fire (Copy)"), names(db));
        }
    }

    @Test
    void decksFromBeforeTheOrderExistedKeepTheirCreationOrder() throws SQLException {
        // A decks table as it was before the position column: the next launch adds it.
        try (Connection conn = DriverManager.getConnection(url()); Statement s = conn.createStatement()) {
            s.execute("""
                CREATE TABLE decks (
                    id         INTEGER PRIMARY KEY AUTOINCREMENT,
                    name       TEXT    NOT NULL,
                    created_at TEXT    DEFAULT (datetime('now')),
                    updated_at TEXT    DEFAULT (datetime('now'))
                )
                """);
            s.execute("INSERT INTO decks (name) VALUES ('Ice'), ('Fire'), ('Wind')");
        }

        try (DeckDatabase db = new DeckDatabase(url())) {
            assertEquals(List.of("Ice", "Fire", "Wind"), names(db));
            db.createDeck("Earth");
            assertEquals(List.of("Ice", "Fire", "Wind", "Earth"), names(db), "a new deck still goes last");
        }
    }
}
