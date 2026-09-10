package scraper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Covers {@link NonApiCards} validation, and the packaged {@code non_api_cards.json} itself.
 *
 * <p>The file is hand-edited, so the checked-in copy is the thing most likely to break: the
 * loader only runs during a scrape, which is not something a build exercises. These tests make a
 * bad edit fail at {@code mvn test} instead of halfway through an ETL run.
 */
class NonApiCardsTest {

	/** A minimal valid card, as a format string with one {@code %s} slot for extra keys. */
	private static String cardJson(String extraKeys) {
		return """
				{ "cards": [ {
				    "serial": "27-123S",
				    "name": "Zack",
				    "type": "Forward",
				    "element": "Fire",
				    "cost": 5
				    %s
				} ] }
				""".formatted(extraKeys);
	}

	// -------------------------------------------------------------------------
	// The checked-in file
	// -------------------------------------------------------------------------

	@Test
	@DisplayName("the packaged non_api_cards.json loads and every serial is unique")
	void packagedFileIsValid() {
		List<ScrapedCard> cards = NonApiCards.load();
		Set<String> serials = new HashSet<>();
		for (ScrapedCard c : cards) {
			assertTrue(serials.add(c.serial), "duplicate serial " + c.serial);
			assertFalse(c.nameEn == null || c.nameEn.isBlank(), c.serial + " has no name");
		}
	}

	@Test
	@DisplayName("saveNonApiCards writes every card in the file to the database")
	void savedCardsRoundTrip(@TempDir Path tmp) throws SQLException {
		List<ScrapedCard> expected = NonApiCards.load();
		try (CardDatabase db = new CardDatabase(tmp.resolve("roundtrip.db").toString())) {
			assertEquals(expected.size(), db.saveNonApiCards());
			for (ScrapedCard want : expected) {
				ScrapedCard got = db.getCard(want.serial);
				assertNotNull(got, want.serial + " was not written");
				assertEquals(want.nameEn, got.nameEn, want.serial + " name");
				assertEquals(want.typeEn, got.typeEn, want.serial + " type");
				assertEquals(want.element, got.element, want.serial + " element");
				assertEquals(want.cost, got.cost, want.serial + " cost");
				assertEquals(want.power, got.power, want.serial + " power");
				assertEquals(want.textEn, got.textEn, want.serial + " text");
			}
		}
	}

	/**
	 * Card-text regexes match straight {@code '} only. {@code saveCard} normalizes typographic
	 * apostrophes out of {@code text_en}, but not out of {@code name_en} or {@code job_en} — so a
	 * curly quote pasted into a job like {@code Ravager/L'Cie} would survive into the database and
	 * quietly fail to match the corpus's spelling of the same job.
	 */
	@Test
	@DisplayName("no field carries a typographic apostrophe")
	void packagedFieldsUseStraightApostrophes() {
		for (ScrapedCard c : NonApiCards.load()) {
			assertNoCurlyQuote(c.serial, "name", c.nameEn);
			assertNoCurlyQuote(c.serial, "job", c.jobEn);
			assertNoCurlyQuote(c.serial, "text", c.textEn);
		}
	}

	private static void assertNoCurlyQuote(String serial, String field, String value) {
		if (value == null) return;
		assertFalse(value.contains("’") || value.contains("‘"),
				serial + " uses a typographic apostrophe in its " + field);
	}

	// -------------------------------------------------------------------------
	// Parsing
	// -------------------------------------------------------------------------

	@Test
	@DisplayName("optional fields default rather than failing")
	void optionalFieldsDefault() {
		ScrapedCard c = NonApiCards.parse(cardJson("")).get(0);
		assertEquals("27-123S", c.serial);
		assertEquals(5, c.cost);
		assertNull(c.power);
		assertNull(c.rarity);
		assertNull(c.textEn);
		assertFalse(c.exBurst);
		assertFalse(c.multicard);
	}

	@Test
	@DisplayName("text given as an array is joined with single spaces")
	void textArrayIsJoined() {
		ScrapedCard c = NonApiCards.parse(cardJson("""
				, "text": [ "Zack gains Haste.", "[[br]]", "When Zack attacks, draw 1 card." ]
				""")).get(0);
		assertEquals("Zack gains Haste. [[br]] When Zack attacks, draw 1 card.", c.textEn);
	}

	@Test
	@DisplayName("a multi-element card is accepted when every component is an element")
	void multiElementIsAccepted() {
		ScrapedCard c = NonApiCards.parse("""
				{ "cards": [ { "serial": "1-001H", "name": "X", "type": "Forward",
				               "element": "Fire/Ice", "cost": 3 } ] }
				""").get(0);
		assertEquals("Fire/Ice", c.element);
	}

	// -------------------------------------------------------------------------
	// Validation — each of these is a typo that would otherwise reach the database
	// -------------------------------------------------------------------------

	@Test
	@DisplayName("a misspelled key fails rather than silently dropping its value")
	void unknownKeyIsRejected() {
		IllegalStateException e = assertThrows(IllegalStateException.class,
				() -> NonApiCards.parse(cardJson(", \"catagory1\": \"VII\"")));
		assertTrue(e.getMessage().contains("catagory1"), e.getMessage());
	}

	@Test
	void unknownTypeIsRejected() {
		assertThrows(IllegalStateException.class, () -> NonApiCards.parse("""
				{ "cards": [ { "serial": "1-001H", "name": "X", "type": "Forwrad", "cost": 3 } ] }
				"""));
	}

	@Test
	void unknownElementIsRejected() {
		assertThrows(IllegalStateException.class, () -> NonApiCards.parse("""
				{ "cards": [ { "serial": "1-001H", "name": "X", "type": "Forward",
				               "element": "Flame", "cost": 3 } ] }
				"""));
	}

	@Test
	void unknownRarityIsRejected() {
		assertThrows(IllegalStateException.class,
				() -> NonApiCards.parse(cardJson(", \"rarity\": \"Z\"")));
	}

	@Test
	void missingCostIsRejected() {
		assertThrows(IllegalStateException.class, () -> NonApiCards.parse("""
				{ "cards": [ { "serial": "1-001H", "name": "X", "type": "Forward" } ] }
				"""));
	}

	@Test
	void blankSerialIsRejected() {
		assertThrows(IllegalStateException.class, () -> NonApiCards.parse("""
				{ "cards": [ { "serial": "  ", "name": "X", "type": "Forward", "cost": 3 } ] }
				"""));
	}

	@Test
	@DisplayName("a duplicate serial fails — the second would silently overwrite the first")
	void duplicateSerialIsRejected() {
		IllegalStateException e = assertThrows(IllegalStateException.class, () -> NonApiCards.parse("""
				{ "cards": [ { "serial": "1-001H", "name": "X", "type": "Forward", "cost": 3 },
				             { "serial": "1-001H", "name": "Y", "type": "Backup", "cost": 2 } ] }
				"""));
		assertTrue(e.getMessage().contains("duplicate serial"), e.getMessage());
	}

	@Test
	void missingCardsArrayIsRejected() {
		assertThrows(IllegalStateException.class, () -> NonApiCards.parse("{ \"card\": [] }"));
	}

	@Test
	@DisplayName("an error names the serial, so the offending entry is findable in the file")
	void errorMessageNamesTheSerial() {
		IllegalStateException e = assertThrows(IllegalStateException.class,
				() -> NonApiCards.parse(cardJson(", \"rarity\": \"Z\"")));
		assertTrue(e.getMessage().contains("27-123S"), e.getMessage());
	}
}
