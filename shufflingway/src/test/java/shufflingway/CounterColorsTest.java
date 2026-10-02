package shufflingway;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

import shufflingway.net.MatchSetup;

class CounterColorsTest {

	@Test
	void theInverseFlipsEachChannel() {
		assertEquals("#c94f95", CounterColors.inverse("#36b06a"));
		assertEquals("#ffffff", CounterColors.inverse("#000000"));
		assertEquals("#00ff00", CounterColors.inverse("#ff00ff"));
	}

	@Test
	void aMidGreyWhoseInverseIsItselfTakesTheFarEndOfTheScaleInstead() {
		// #808080 inverts to #7f7f7f, the same color to the eye.
		assertEquals("#202020", CounterColors.inverse("#808080"));
		assertEquals("#e0e0e0", CounterColors.inverse("#707070"));
	}

	@Test
	void onlyWellFormedHexIsAcceptedOffTheWire() {
		assertEquals("#36b06a", CounterColors.validOrNull(" #36B06A "));
		assertNull(CounterColors.validOrNull(null));
		assertNull(CounterColors.validOrNull("red"));
		assertNull(CounterColors.validOrNull("#36b06"));
		assertNull(CounterColors.validOrNull("#36b06a; drop"));
		assertEquals(CounterColors.DEFAULT, CounterColors.validOrDefault("not a color"));
	}

	@Test
	void theOpponentKeepsTheirOwnChoiceWhenItIsDistinct() {
		assertEquals("#3060e0", CounterColors.forOpponent("#36b06a", "#3060e0"));
	}

	@Test
	void theOpponentTakesTheInverseWhenTheySentNoColor() {
		// The CPU, and an older client that does not send one.
		assertEquals("#c94f95", CounterColors.forOpponent("#36b06a", null));
		assertEquals("#c94f95", CounterColors.forOpponent("#36b06a", "garbage"));
	}

	@Test
	void theOpponentTakesTheInverseWhenBothChoseTheSameOrNearlyTheSameColor() {
		// Both on the default green, the likeliest case of all.
		assertEquals("#c94f95", CounterColors.forOpponent("#36b06a", "#36b06a"));
		assertEquals("#c94f95", CounterColors.forOpponent("#36b06a", "#38b26c"),
				"two shades no one could tell apart on the board count as the same");
	}

	@Test
	void aMalformedLocalSettingFallsBackToTheDefault() {
		assertEquals(CounterColors.inverse(CounterColors.DEFAULT), CounterColors.forOpponent("oops", null));
	}

	// ---------------------------------------------------------------------------------------------
	// Which color each side of the board draws its counters in
	// ---------------------------------------------------------------------------------------------

	private static MatchSetup matchAgainst(String remoteCounterColor) {
		return new MatchSetup(1, List.of("1-001H"), "Deck", "Opponent", 7L, true, true, false, false,
				remoteCounterColor);
	}

	@Test
	void inANetworkedMatchEachSideDrawsItsCountersInItsOwnPlayersColor() {
		MatchSetup match = matchAgainst("#3060e0");
		assertEquals("#36b06a", MainWindow.counterColorFor(true, "#36b06a", match), "the counters on your Characters");
		assertEquals("#3060e0", MainWindow.counterColorFor(false, "#36b06a", match), "the counters on theirs");
	}

	@Test
	void anOpponentOnTheSameColorIsShownInTheInverse() {
		assertEquals("#c94f95", MainWindow.counterColorFor(false, "#36b06a", matchAgainst("#36b06a")));
	}

	@Test
	void anOpponentOnAnOlderClientIsShownInTheInverse() {
		assertEquals("#c94f95", MainWindow.counterColorFor(false, "#36b06a", matchAgainst(null)));
	}

	@Test
	void againstTheCpuTheOpponentsCountersAreTheInverseOfYours() {
		String saved = AppSettings.getCounterColor();
		try {
			AppSettings.setCounterColor("#3060e0");
			MainWindow mw = new MainWindow();   // no match set up: a game against the CPU
			assertEquals("#3060e0", mw.counterColorFor(true));
			assertEquals("#cf9f1f", mw.counterColorFor(false));
		} finally {
			AppSettings.setCounterColor(saved);
		}
	}
}
