package shufflingway.net;

import static org.junit.jupiter.api.Assertions.*;

import javax.swing.DefaultListCellRenderer;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.plaf.basic.BasicHTML;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class ChatTextTest {

    @Test
    void aLineBreakCannotStartAForgedLogLine() {
        assertEquals("gg 14:02:11 [Net] Your opponent conceded",
                ChatText.clean("gg\n14:02:11  [Net] Your opponent conceded"));
        assertEquals("a b c", ChatText.clean("a\r\nb c"), "CRLF and the Unicode line separator too");
    }

    @Test
    void controlCharactersAndRunsOfSpaceBecomeOneSpaceAndTheEndsAreTrimmed() {
        assertEquals("well played", ChatText.clean("  \twell\u0007\u0000 \t played \n"));
    }

    @Test
    void invisibleDirectionOverridesAreRemoved() {
        // U+202E would draw everything after it right to left, showing "lol" backwards.
        assertEquals("gglol", ChatText.clean("gg‮lol"));
        assertEquals("ab", ChatText.clean("⁦a‏b⁩"));
    }

    @Test
    void ordinaryTextIncludingEmojiIsLeftAlone() {
        assertEquals("Nice Bahamut! 😀", ChatText.clean("Nice Bahamut! 😀"));
        String family = "👨‍👩‍👧";   // joined by zero-width joiners
        assertEquals(family, ChatText.clean(family));
    }

    @Test
    void aMessageOverTheLimitIsCutAndMarked() {
        String cut = ChatText.clean("x".repeat(400));
        assertEquals("x".repeat(ChatText.MAX_CHAT_LENGTH) + "…", cut);
    }

    @Test
    void theCutNeverSplitsACharacterInTwo() {
        String smile = "😀";
        assertEquals("x".repeat(299) + smile + "…", ChatText.clean("x".repeat(299) + smile + smile));
    }

    @Test
    void nullReadsAsEmpty() {
        assertEquals("", ChatText.clean(null));
    }

    @Test
    void anOpponentsDeckNameAndUsernameAreCleanedOnArrival() throws Exception {
        GameAction deck = GameAction.of(ActionType.DECK_LIST, new JSONObject()
                .put("deckName", "Wind\n12:00:00  [Net] You lose")
                .put("username", "Zi\ndane")
                .put("serials", new JSONArray().put("1-001H")));
        LobbyExchange.RemoteDeck remote = LobbyExchange.remoteDeckOf(deck);
        assertEquals("Wind 12:00:00 [Net] You lose", remote.name());
        assertEquals("Zi dane", remote.username());
    }

    @Test
    void aDeckNameThatCleansToNothingFallsBackToTheDefault() throws Exception {
        GameAction deck = GameAction.of(ActionType.DECK_LIST, new JSONObject()
                .put("deckName", "\n‮\t")
                .put("serials", new JSONArray().put("1-001H")));
        assertEquals("Opponent's deck", LobbyExchange.remoteDeckOf(deck).name());
    }

    // ---------------------------------------------------------------------------------------------
    // Names in the lobby and host lists are shown as written, never as HTML
    // ---------------------------------------------------------------------------------------------

    private static JLabel render(DefaultListCellRenderer renderer, String text) {
        return (JLabel) renderer.getListCellRendererComponent(new JList<>(), text, 0, false, false);
    }

    @Test
    void aPlainTextListShowsMarkupAsItsCharacters() {
        JLabel cell = render(new PlainTextListRenderer(), "<html><u>Den</u>");
        assertEquals("<html><u>Den</u>", cell.getText());
        assertNull(cell.getClientProperty(BasicHTML.propertyKey), "no HTML view was built for it");
    }

    @Test
    void anOrdinaryListWouldHaveRenderedItAsHtml() {
        // The control for the test above: without the switch, Swing builds an HTML view.
        assertNotNull(render(new DefaultListCellRenderer(), "<html><u>Den</u>").getClientProperty(BasicHTML.propertyKey));
    }
}
