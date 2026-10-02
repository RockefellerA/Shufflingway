package shufflingway.net;

/**
 * Text another player typed, made safe to show in the game log: chat messages, and the deck name
 * and username that arrive with a deck list.
 *
 * <p>The log is plain text, so there is no markup or code to guard against here. What a modified
 * client could still do is forge log lines by embedding line breaks ("gg\n14:02:11  [Net] Your
 * opponent conceded"), scramble the display with invisible direction overrides, or flood the log
 * with one enormous message. Cleaning on the receiving side undoes each of those whatever the
 * sender's build does.
 */
public final class ChatText {

    private ChatText() {}

    /** Longest chat message shown, in characters; a longer one is cut and ends in "…". */
    public static final int MAX_CHAT_LENGTH = 300;

    /** {@link #clean(String, int)} at the chat length. */
    public static String clean(String text) {
        return clean(text, MAX_CHAT_LENGTH);
    }

    /**
     * {@code text} as one line: every run of line breaks, tabs, other control characters and spaces
     * becomes a single space, the ends are trimmed, and the invisible characters that reverse or
     * reorder text direction are removed. Longer than {@code maxLength} characters, it is cut there
     * and ends in "…". {@code null} reads as empty.
     */
    public static String clean(String text, int maxLength) {
        if (text == null) return "";
        StringBuilder sb = new StringBuilder(Math.min(text.length(), maxLength + 1));
        int count = 0;
        boolean pendingSpace = false;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (isDirectionControl(cp)) continue;
            if (Character.isISOControl(cp) || Character.isWhitespace(cp)) {
                pendingSpace = count > 0;
                continue;
            }
            int needed = pendingSpace ? 2 : 1;
            if (count + needed > maxLength) {
                sb.append('…');
                break;
            }
            if (pendingSpace) {
                sb.append(' ');
                count++;
                pendingSpace = false;
            }
            sb.appendCodePoint(cp);
            count++;
        }
        return sb.toString();
    }

    /**
     * The Unicode bidirectional controls: marks, embeddings, overrides and isolates. None prints;
     * each changes the direction in which the text after it is laid out.
     */
    private static boolean isDirectionControl(int cp) {
        return cp == 0x061C || cp == 0x200E || cp == 0x200F
                || (cp >= 0x202A && cp <= 0x202E)
                || (cp >= 0x2066 && cp <= 0x2069);
    }
}
