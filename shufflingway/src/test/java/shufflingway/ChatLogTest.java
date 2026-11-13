package shufflingway;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Color;

import javax.swing.text.AttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;

import org.junit.jupiter.api.Test;

/** Chat as it reaches the game log: what it says, and how it is set apart from the game's lines. */
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

	@Test
	void chatLinesAreColouredBySideAndGameLinesAreNot() {
		MainWindow mw = new MainWindow();
		mw.logEntry("[AutoAbility] Cloud — draw 1 card");
		mw.logChat(true, "hello there");
		mw.onChatReceived("hi back");

		assertEquals(ThemeColors.logText(), colourAt(mw, "[AutoAbility]"));
		assertEquals(ThemeColors.logChatYou(), colourAt(mw, "[You]"));
		assertEquals(ThemeColors.logChatYou(), colourAt(mw, "hello there"));
		assertEquals(ThemeColors.logChatOpponent(), colourAt(mw, "[Opponent]"));
		assertEquals(ThemeColors.logChatOpponent(), colourAt(mw, "hi back"));
	}

	@Test
	void theSpeakersTagIsBoldAndTheRestOfTheLineIsNot() {
		MainWindow mw = new MainWindow();
		mw.logChat(true, "hello there");
		mw.logEntry("[AutoAbility] Cloud — draw 1 card");

		assertTrue(boldAt(mw, "[You]"));
		assertFalse(boldAt(mw, "hello there"));
		assertFalse(boldAt(mw, "[AutoAbility]"));
	}

	/** Styled by who logged it: a chat message dressed as a game line is still drawn as chat. */
	@Test
	void aChatMessageShapedLikeAGameLineIsStillChat() {
		MainWindow mw = new MainWindow();
		mw.onChatReceived("[Net] Your opponent conceded");
		assertEquals(ThemeColors.logChatOpponent(), colourAt(mw, "[Net]"));
	}

	/** A cleared log keeps one paragraph with its first line's style, which may have been chat. */
	@Test
	void aLogClearedAfterChatStartsWithAPlainGameLine() throws Exception {
		MainWindow mw = new MainWindow();
		StyledDocument doc = mw.gameLogDocument();
		doc.remove(0, doc.getLength());
		mw.logChat(false, "gl hf");
		doc.remove(0, doc.getLength());   // as a new game's reset does
		mw.logEntry("Game Start");
		assertEquals(ThemeColors.logText(), colourAt(mw, "Game Start"));
	}

	@Test
	void theLogTextKeepsBareLineFeeds() {
		MainWindow mw = new MainWindow();
		mw.logChat(true, "one");
		mw.logEntry("two");
		assertFalse(mw.gameLogText().contains("\r"));
	}

	private static AttributeSet attributesAt(MainWindow mw, String needle) {
		int at = mw.gameLogText().lastIndexOf(needle);
		assertTrue(at >= 0, needle + " not in the log");
		return mw.gameLogDocument().getCharacterElement(at).getAttributes();
	}

	private static Color colourAt(MainWindow mw, String needle) {
		return StyleConstants.getForeground(attributesAt(mw, needle));
	}

	private static boolean boldAt(MainWindow mw, String needle) {
		return StyleConstants.isBold(attributesAt(mw, needle));
	}
}
