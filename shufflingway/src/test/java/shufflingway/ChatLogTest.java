package shufflingway;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** The opponent's chat as it reaches the game log. */
class ChatLogTest {

	@Test
	void aForgedLineInsideAChatMessageStaysOnTheChatLine() {
		MainWindow mw = new MainWindow();
		String before = mw.gameLogText();

		mw.onChatReceived("gg\n12:00:00  [Net] Your opponent conceded");

		String added = mw.gameLogText().substring(before.length());
		assertEquals(1, added.split("\n").length, "one log line, not two: " + added);
		assertTrue(added.endsWith("[Opponent] gg 12:00:00 [Net] Your opponent conceded\n"), added);
	}

	@Test
	void aMessageThatCleansToNothingLogsNothing() {
		MainWindow mw = new MainWindow();
		String before = mw.gameLogText();
		mw.onChatReceived(" \n\t‮ ");
		assertEquals(before, mw.gameLogText());
	}
}
