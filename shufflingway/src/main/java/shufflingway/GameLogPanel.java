package shufflingway;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.Insets;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.function.Predicate;
import java.util.function.Supplier;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.JTextPane;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.Style;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyleContext;
import javax.swing.text.StyledDocument;

import shufflingway.audio.Sound;
import shufflingway.audio.SoundEffects;
import shufflingway.net.ChatText;
import shufflingway.net.MatchSetup;

/**
 * The game log and the chat bar under it, which fill the side panel below the phase tracker.
 *
 * <p>The log holds the game's own timestamped lines and both players' chat, each chat line in its
 * side's colour with the speaker's tag in bold. The chat bar is enabled only while a multiplayer
 * connection is open ({@link #setChatEnabled}); what it sends is logged only once it has gone.
 */
final class GameLogPanel extends JPanel {

	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

	private final JTextPane  log;
	private final JTextField chatInput;
	private final JButton    chatSendBtn;
	private final Supplier<MatchSetup> match;

	/**
	 * @param match    the networked match in progress, or {@code null} in a game against the CPU;
	 *                 read each time a chat line is logged, for the speakers' names
	 * @param sendChat sends a cleaned, non-empty chat message to the opponent and returns whether
	 *                 there was a connection to send it on
	 */
	GameLogPanel(Supplier<MatchSetup> match, Predicate<String> sendChat) {
		super(new BorderLayout());
		this.match = match;

		// Its colours are set rather than left to the look, which would draw it in the panel's colour
		// as a read-only field, so they are set again whenever Preferences switches the theme. The
		// chat styles are recoloured with them (see colourChatStyles). A text pane wraps on word
		// boundaries by itself.
		log = new JTextPane() {
			@Override public void updateUI() {
				super.updateUI();
				setBackground(ThemeColors.logBackground());
				setForeground(ThemeColors.logText());
				setCaretColor(getBackground());
				colourChatStyles(getStyledDocument());
			}
		};
		log.setEditable(false);
		log.setFont(new Font(Font.DIALOG, Font.PLAIN, 12));
		log.setMargin(new Insets(4, 4, 4, 4));
		colourChatStyles(log.getStyledDocument());

		JScrollPane logScrollPane = new JScrollPane(log,
				JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
				JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);

		chatInput = new JTextField();
		chatInput.setFont(new Font("Serif", Font.PLAIN, 11));
		chatInput.setEnabled(false);
		chatInput.setToolTipText("Connect to multiplayer to chat");

		chatSendBtn = new JButton("Send");
		chatSendBtn.setFont(new Font("Serif", Font.PLAIN, 11));
		chatSendBtn.setEnabled(false);

		Runnable send = () -> {
			// Cleaned as the receiver will clean it, so both logs show the same line.
			String text = ChatText.clean(chatInput.getText());
			if (text.isEmpty() || !sendChat.test(text)) return;
			logChat(true, text);
			SoundEffects.play(Sound.CHAT_SEND);
			chatInput.setText("");
		};
		chatInput.addActionListener(e -> send.run());
		chatSendBtn.addActionListener(e -> send.run());

		// The rule over the chat field is Classic's alone. FlatLaf boxes the log and the chat field
		// each in a border of its own, so there the two are set apart by the same gap as the field
		// and its Send button.
		JPanel chatPanel = new JPanel(new BorderLayout(4, 0)) {
			@Override public void updateUI() {
				super.updateUI();
				setBorder(ThemeColors.isFlat()
						? BorderFactory.createEmptyBorder(4, 0, 0, 0)
						: BorderFactory.createMatteBorder(1, 0, 0, 0, Color.GRAY));
			}
		};
		chatPanel.add(chatInput,   BorderLayout.CENTER);
		chatPanel.add(chatSendBtn, BorderLayout.EAST);

		add(logScrollPane, BorderLayout.CENTER);
		add(chatPanel,     BorderLayout.SOUTH);
	}

	/** Enables the chat bar while a multiplayer connection is open, and disables it when it closes. */
	void setChatEnabled(boolean enabled) {
		chatInput.setEnabled(enabled);
		chatSendBtn.setEnabled(enabled);
	}

	/** Appends a timestamped entry to the game log. */
	void logEntry(String text) {
		appendLogLine(log.getStyledDocument().getStyle(StyleContext.DEFAULT_STYLE), "", text);
	}

	/**
	 * Appends a chat line to the game log in its side's colour, with the speaker's tag in bold, so a
	 * glance tells it from the game's own lines. A line is styled as chat by which method logged it,
	 * never by what it says, so a message typed to look like a game line still reads as chat.
	 *
	 * <p>The tag names the speaker as the turn pill does (see {@code MainWindow.applyTurnPillNames}):
	 * both usernames in a networked match, neither in a local game.
	 */
	void logChat(boolean mine, String msg) {
		Style style = log.getStyledDocument().getStyle(chatStyleName(mine));
		MatchSetup m = match.get();
		String myName    = m == null ? null : AppSettings.getUsername();
		String theirName = m == null ? null : m.remoteUsername();
		appendLogLine(style, chatTag(mine, myName, theirName), " " + msg);
	}

	/** Empties the log, for a new game. */
	void clear() {
		log.setText("");
	}

	/** The log's current contents, for tests that assert on what a play wrote. */
	String text() {
		// From the document, not getText(): a text pane writes its text out through the editor kit,
		// which turns every line break into the platform's, \r\n on Windows.
		StyledDocument doc = log.getStyledDocument();
		try {
			return doc.getText(0, doc.getLength());
		} catch (BadLocationException e) {
			throw new IllegalStateException(e);
		}
	}

	/** The log's document, for tests that assert on how a line is styled. */
	StyledDocument document() {
		return log.getStyledDocument();
	}

	/**
	 * The bold tag on a chat line: the speaker's username in brackets, or "[You]" / "[Opponent]"
	 * for a player who set none. Two players who share a name both get the generic tags, so the
	 * log never shows one name speaking to itself.
	 */
	static String chatTag(boolean mine, String myName, String theirName) {
		String me   = myName    == null ? "" : myName.trim();
		String them = theirName == null ? "" : theirName.trim();
		if (!me.isEmpty() && me.equalsIgnoreCase(them)) {
			me   = "";
			them = "";
		}
		String name = mine ? me : them;
		if (name.isEmpty()) return mine ? "[You]" : "[Opponent]";
		return "[" + name + "]";
	}

	/**
	 * Appends {@code "HH:mm:ss  " + tag + text} as one log line, {@code tag} in bold, the whole line
	 * in paragraph style {@code style}. The style is set on game lines too: a cleared log keeps one
	 * empty paragraph carrying the style of the first line it held, which may have been a chat line.
	 */
	private void appendLogLine(Style style, String tag, String text) {
		StyledDocument doc = log.getStyledDocument();
		String prefix = LocalTime.now().format(TIME) + "  ";
		int start = doc.getLength();
		try {
			doc.insertString(start, prefix + tag + text + "\n", null);
		} catch (BadLocationException e) {
			throw new IllegalStateException(e);
		}
		if (!tag.isEmpty()) {
			SimpleAttributeSet bold = new SimpleAttributeSet();
			StyleConstants.setBold(bold, true);
			doc.setCharacterAttributes(start + prefix.length(), tag.length(), bold, false);
		}
		doc.setLogicalStyle(start, style);
		log.setCaretPosition(doc.getLength());
	}

	private static String chatStyleName(boolean mine) {
		return mine ? "chatYou" : "chatOpponent";
	}

	/**
	 * Creates the log's two chat styles if they are missing, and colours them for the current theme.
	 * A chat line takes its colour from its paragraph's style rather than holding one of its own, so
	 * recolouring the style on a theme switch recolours every chat line already in the log. Each is a
	 * child of the default style, which the text pane keeps in step with its font.
	 */
	private static void colourChatStyles(StyledDocument doc) {
		if (doc == null) return;   // updateUI first runs from the constructor, before the document
		Style parent = doc.getStyle(StyleContext.DEFAULT_STYLE);
		for (boolean mine : new boolean[] {true, false}) {
			Style style = doc.getStyle(chatStyleName(mine));
			if (style == null) style = doc.addStyle(chatStyleName(mine), parent);
			StyleConstants.setForeground(style, mine ? ThemeColors.logChatYou() : ThemeColors.logChatOpponent());
		}
	}
}
