package shufflingway;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** The field colors Preferences offers, and how a saved one reads back. */
class ElementColorTest {

	@Test
	void theFieldColorsAreTheEightElements() {
		assertArrayEquals(new String[] {"Fire", "Ice", "Wind", "Earth", "Lightning", "Water", "Light", "Dark"},
				ElementColor.boardColorChoices());
	}

	@Test
	void aSavedElementReadsBackAsItsChoice() {
		assertEquals("Fire", ElementColor.boardColorOr("Fire", "Light"));
		assertEquals("Lightning", ElementColor.boardColorOr(" lightning ", "Light"));
	}

	/** "Default" was offered before every field took an element's color. */
	@Test
	void defaultUnsetOrUnknownReadsAsTheFallback() {
		assertEquals("Light", ElementColor.boardColorOr("Default", "Light"));
		assertEquals("Dark", ElementColor.boardColorOr(null, "Dark"));
		assertEquals("Dark", ElementColor.boardColorOr("Mauve", "Dark"));
	}
}
