package shufflingway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.awt.Component;
import java.awt.Container;
import java.awt.EventQueue;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.InputEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JLayeredPane;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import shufflingway.net.ActionType;
import shufflingway.net.ChoiceKind;
import shufflingway.net.ConnectionListener;
import shufflingway.net.GameAction;
import shufflingway.net.GameConnection;
import shufflingway.net.MatchSetup;

/**
 * A response arriving while this client waits for the opponent's pass on its own Stack entry.
 *
 * <p>Reported from a two-window playtest: "Waiting for your opponent to respond to Reeve..." up,
 * Y'shtola's Stack window beside it, and nothing on screen would take a click. The sequence is
 * Reeve 16-104R entering this player's field, his auto-ability going on the Stack, and the opponent
 * answering it with Y'shtola 5-068L: "Put Y'shtola into the Break Zone: Choose 1 Summon or
 * auto-ability. Cancel its effect."
 *
 * <p>The wait used to be a modal dialog, which blocked the response's Stack window and the board
 * behind it alike, so each client waited on the other. It is a secondary loop now, with the board
 * blocked by {@link MainWindow#refreshOpponentWaitBlock} only while the decision is the opponent's.
 *
 * <p>The window tests need a realised frame. They post real mouse events through the system event
 * queue rather than calling {@code doClick}, which would walk straight past whatever blocks input —
 * the thing under test. A click on the board is posted to the frame, where the OS delivers one, so
 * Swing routes it to whatever lies on top at that point. A control test clicks the Stack window with
 * no wait open, so a failure is the wait, not the synthesised click. They are skipped headless.
 */
class StackResponseWindowTest {

    private ServerSocket   listener;
    private Socket         hostSide, joinerSide;
    private GameConnection host,     joiner;

    /** What this client sent to the far side, oldest first. */
    private final BlockingQueue<GameAction> sent = new ArrayBlockingQueue<>(64);

    private MainWindow     mw;
    private RemoteOpponent remote;

    @BeforeEach
    void connect() throws IOException {
        listener   = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        joinerSide = new Socket(InetAddress.getLoopbackAddress(), listener.getLocalPort());
        hostSide   = listener.accept();
        host   = new GameConnection(hostSide);
        joiner = new GameConnection(joinerSide);
        joiner.addListener(new ConnectionListener() {
            @Override public void onActionReceived(GameAction action) { sent.add(action); }
            @Override public void onDisconnected(String reason) {}
        });
        joiner.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (mw != null) {
            // The cancel takes down every wait still parked on the EDT, so the nested loops unwind
            // before the windows they belong to go.
            SwingUtilities.invokeAndWait(() -> {
                remote.cancel();
                for (Window w : mw.frame.getOwnedWindows()) w.dispose();
                mw.frame.dispose();
            });
        }
        if (host     != null) host.close();
        if (joiner   != null) joiner.close();
        if (listener != null) listener.close();
    }

    // =========================================================================================
    // Realised windows
    // =========================================================================================

    @Test
    void aPostedClickReachesTheStackWindowWhenNothingIsWaiting() throws Exception {
        // The control: the opponent's Reeve is on top and this player holds priority on it, with
        // no wait of this client's own open. OK has to pass, or the tests below prove nothing.
        assumeDisplay();
        Cards c = cards();
        onEdt(() -> {
            seat();
            mw.gameState.getIdentity().put(c.reeve, false);
            mw.gameState.pushStack(reeveTrigger(c.reeve, false));
            mw.frame.setVisible(true);
            mw.showStackWindow();
        });
        JButton ok = awaitButton("OK");

        click(ok);

        awaitTrue(() -> onEdtGet(() -> mw.gameState.stackSize() == 0), "OK resolved Reeve's trigger");
        assertStackPassSent();
    }

    @Test
    void theOpponentsResponseCanBePassedWhileTheirPassOnYourEntryIsAwaited() throws Exception {
        assumeDisplay();
        Cards c = cards();
        StackEntry reeve = openReeveWaitAndRespondWithYshtola(c);
        JButton ok = awaitButton("OK");

        click(ok);
        settle();

        assertEquals(0, (int) onEdtGet(() -> mw.gameState.stackSize()), "OK on Y'shtola's window "
                + "landed: she resolved and Reeve's trigger left cancelled. The opponent's client is "
                + "parked on this player's pass on her, so if it does not, neither side can move.");
        assertStackPassSent();
        assertFalse(onEdtGet(() -> remote.isAwaitingStackPass(reeve)),
                "Reeve's trigger was cancelled, so the wait for a pass on it is released");
        assertFalse(onEdtGet(this::waitDialogShowing));
    }

    @Test
    void theBoardTakesClicksWhileRespondingToTheOpponentsResponse() throws Exception {
        // Respond hands control to the board: a counter-response is cast from hand or used off the
        // field. So the frame has to take clicks too, not just the Stack window.
        assumeDisplay();
        Cards c = cards();
        openReeveWaitAndRespondWithYshtola(c);

        assertTrue(boardTakesAClick(), "a click on the board was dropped while the wait for Reeve "
                + "is open, so Respond can never be followed by a response");
    }

    @Test
    void theBoardIsBlockedWhileTheOpponentHoldsPriorityOnYourEntry() throws Exception {
        // What the modal dialog used to do, and the block now has to: with Reeve's trigger waiting on
        // their pass, the decision is theirs, and a click here would act out of turn.
        assumeDisplay();
        Cards c = cards();
        openReeveWait(c);

        assertFalse(boardTakesAClick(), "the board is blocked while the opponent holds priority");
        assertTrue(onEdtGet(this::waitDialogShowing), "and the wait says what it is waiting for");
    }

    @Test
    void hoverStillReachesTheCardsWhileTheBoardIsBlocked() throws Exception {
        // A card's zoom is its mouseEntered and mouseExited, so a player waiting on the opponent can
        // still read the board. Probed on a stand-in card of the test's own, laid over the board:
        // the zoom itself loads the card image off the EDT, which is no state a test can wait on.
        assumeDisplay();
        Cards c = cards();
        openReeveWait(c);
        List<Integer> heard = new ArrayList<>();
        Point over = onEdtGet(() -> {
            JLabel card = standInCard();
            card.addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { heard.add(e.getID()); }
                @Override public void mouseExited(MouseEvent e)  { heard.add(e.getID()); }
            });
            return SwingUtilities.convertPoint(card, card.getWidth() / 2, card.getHeight() / 2, mw.frame);
        });

        move(over);
        move(new Point(1, 1));   // the title bar: off the board altogether

        assertEquals(List.of(MouseEvent.MOUSE_ENTERED, MouseEvent.MOUSE_EXITED), heard,
                "the pointer crossing the card is heard by it, blocked or not");
    }

    @Test
    void theControlsLookDisabledWhileTheBoardIsBlocked() throws Exception {
        assumeDisplay();
        Cards c = cards();
        openReeveWait(c);
        List<Integer> heard = new ArrayList<>();
        Point over = onEdtGet(() -> {
            JButton next = mw.nextPhaseButton;
            next.setEnabled(true);   // as turn flow might have left it
            next.addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { heard.add(e.getID()); }
            });
            return SwingUtilities.convertPoint(next, next.getWidth() / 2, next.getHeight() / 2, mw.frame);
        });

        move(over);

        HoverOnlyGlassPane pane = onEdtGet(() -> (HoverOnlyGlassPane) mw.frame.getGlassPane());
        List<Rectangle> faded = onEdtGet(() ->
                pane.fadedControls().stream().map(HoverOnlyGlassPane.Faded::bounds).toList());
        Rectangle next = onEdtGet(() -> SwingUtilities.convertRectangle(
                mw.nextPhaseButton.getParent(), mw.nextPhaseButton.getBounds(), pane));
        Rectangle menu = onEdtGet(() -> SwingUtilities.convertRectangle(
                mw.frame.getJMenuBar().getParent(), mw.frame.getJMenuBar().getBounds(), pane));
        assertTrue(faded.contains(next), "the Next button is faded");
        assertTrue(faded.contains(menu), "and so is the menu bar");
        assertTrue(onEdtGet(() -> mw.nextPhaseButton.isEnabled()),
                "by painting over it: what turn flow set is left as it was");
        assertEquals(List.of(), heard, "and it does not light up under the pointer");

        onEdt(() -> remote.onActionReceived(yshtolaCancel(c.yshtola)));

        assertFalse(onEdtGet(pane::isVisible), "with this player to answer, nothing is faded");
    }

    @Test
    void theBoardBlocksAgainOnceTheResponseHasResolved() throws Exception {
        // Y'shtola resolves without touching the wait's entry here: a response that does not cancel
        // Reeve returns priority to him, and the wait for the opponent's pass on him resumes.
        assumeDisplay();
        Cards c = cards();
        StackEntry reeve = openReeveWait(c);
        CardData bolt = vanillaSummon("Bolt");
        onEdt(() -> {
            mw.gameState.getIdentity().put(bolt, false);
            mw.gameState.pushStack(new StackEntry(bolt, null, false));
            mw.showStackWindow();
        });
        assertTrue(boardTakesAClick(), "their response is this player's to answer");

        click(awaitButton("OK"));
        awaitTrue(() -> onEdtGet(() -> mw.gameState.stackSize() == 1), "the response resolved");

        assertTrue(onEdtGet(() -> remote.isAwaitingStackPass(reeve)), "still waiting on Reeve");
        assertFalse(boardTakesAClick(), "and the board is blocked again");
        assertTrue(onEdtGet(this::waitDialogShowing));
    }

    // =========================================================================================
    // Stack bookkeeping, both seats, with no modal wait
    //
    // Controls for the tests above: if these pass, the two clients agree on the sequence and the
    // only thing in the way is the window that should have taken the click.
    // =========================================================================================

    @Test
    void reevesControllerPassingOnYshtolaCancelsReeveAndReleasesTheWait() throws Exception {
        // On screen, though no wait is started: off screen the Stack window treats this player's
        // own entry as it would against the CPU and resolves it, and paying Y'shtola's cost asks
        // for that window before her ability is pushed.
        assumeDisplay();
        Cards c = cards();
        onEdt(() -> {
            seat();
            mw.frame.setVisible(true);
            mw.gameState.getIdentity().put(c.yshtola, false);
            mw.placeP2CardInForwardZone(c.yshtola);
            mw.gameState.getIdentity().put(c.reeve, true);
            mw.gameState.pushStack(reeveTrigger(c.reeve, true));
            mw.desyncReported = true;   // a desync dialog would hold the run open
            remote.onActionReceived(yshtolaCancel(c.yshtola));
            assertEquals(2, mw.gameState.stackSize(),
                    () -> "her ability went on the Stack above Reeve's\n" + mw.gameLogText());
            assertSame(c.yshtola, mw.gameState.peekStack().source());

            mw.passStackPriority();

            assertEquals(0, mw.gameState.stackSize(), "Y'shtola resolved and Reeve's trigger left cancelled");
            assertTrue(mw.gameState.getP2BreakZone().contains(c.yshtola), () -> "her cost put her there;"
                    + " still on P2's field = " + mw.p2ForwardCards.contains(c.yshtola)
                    + ", in P1's Break Zone = " + mw.gameState.getP1BreakZone().contains(c.yshtola)
                    + "\n" + mw.gameLogText());
        });
        assertStackPassSent();
    }

    @Test
    void yshtolasControllerResolvesOnThePassAndNeverWaitsOnReeve() throws Exception {
        // The other seat: Reeve is the opponent's, Y'shtola's ability is this player's own.
        Cards c = cards();
        onEdt(() -> {
            seat();
            mw.gameState.getIdentity().put(c.reeve, false);
            StackEntry reeve = reeveTrigger(c.reeve, false);
            mw.gameState.pushStack(reeve);
            mw.gameState.getIdentity().put(c.yshtola, true);
            ActionAbility cancel = c.yshtola.actionAbilities().get(cancelAbilityIndex(c.yshtola, true));
            StackEntry mine = new StackEntry(c.yshtola, cancel, true);
            mw.gameState.pushStack(mine);
            remote.onActionReceived(RemoteOpponent.choiceAction(ChoiceKind.STACK_PASS, List.of()));

            mw.resolveOnOpponentsPass(remote, mine);

            assertEquals(0, mw.gameState.stackSize());
            assertFalse(remote.isAwaitingStackPass(reeve), "a cancelled entry is waited on by nobody");
        });
    }

    // ── Scenario ─────────────────────────────────────────────────────────

    private record Cards(CardData reeve, CardData yshtola) {}

    private static Cards cards() throws Exception {
        CardData reeve = null, yshtola = null;
        for (CardCorpus.Entry e : CardCorpus.load()) {
            if (e.serial().equals("16-104R")) reeve   = e.card();
            if (e.serial().equals("5-068L"))  yshtola = e.card();
        }
        Assumptions.assumeTrue(reeve != null && yshtola != null, "needs shufflingway.db");
        return new Cards(reeve, yshtola);
    }

    private void seat() {
        mw = new MainWindow();
        remote = new RemoteOpponent(mw, host, new MatchSetup(1, List.of("h1"), "Deck", "Host", 7L, true, true));
        mw.opponent = remote;
    }

    /** Reeve's enters-the-field auto-ability, as the trigger layer puts it on the Stack. */
    private static StackEntry reeveTrigger(CardData reeve, boolean isP1) {
        AutoAbility etf = reeve.autoAbilities().stream()
                .filter(a -> a.trigger().contains("enter")).findFirst().orElseThrow();
        return new StackEntry(reeve, null, etf, isP1, 0, false, null, false, false, 0, 0);
    }

    private int cancelAbilityIndex(CardData yshtola, boolean ownerIsP1) {
        List<ActionAbility> catalogue = mw.autoAbilityTriggers.abilityCatalogue(yshtola, ownerIsP1);
        for (int i = 0; i < catalogue.size(); i++)
            if (catalogue.get(i).effectText().contains("Cancel its effect")) return i;
        throw new AssertionError("Y'shtola's cancel is not in her ability catalogue: " + catalogue);
    }

    /** The opponent's activation of Y'shtola's cancel, off their first Forward slot, as they send it. */
    private GameAction yshtolaCancel(CardData yshtola) {
        int idx = cancelAbilityIndex(yshtola, false);
        ActionAbility ability = mw.autoAbilityTriggers.abilityCatalogue(yshtola, false).get(idx);
        // Her own slot is both where the ability comes from and what its Break Zone cost puts
        // there; the sender's client settles that before committing, as autoResolveBzTargets does.
        ForwardTarget self = new ForwardTarget(true, mw.p2ForwardCards.indexOf(yshtola),
                ForwardTarget.CardZone.FORWARD);
        return RemoteOpponent.activateAbilityAction(yshtola, AbilitySource.onField(self), idx, ability,
                new AbilityPayment(List.of(), List.of(), List.of(self), 0, -1, Map.of()));
    }

    /**
     * Shows the board with the opponent's Y'shtola on their field and this player's Reeve trigger on
     * the Stack, and starts the wait for the opponent's pass on it.
     */
    private StackEntry openReeveWait(Cards c) throws Exception {
        StackEntry[] reeve = new StackEntry[1];
        onEdt(() -> {
            seat();
            mw.gameState.getIdentity().put(c.yshtola, false);
            mw.placeP2CardInForwardZone(c.yshtola);   // first, for the reason given above
            mw.gameState.getIdentity().put(c.reeve, true);
            reeve[0] = reeveTrigger(c.reeve, true);
            mw.gameState.pushStack(reeve[0]);
            mw.desyncReported = true;   // a desync dialog would be one more window over the board
            mw.frame.setVisible(true);
        });
        // Parks the EDT in the wait's loop; everything after this runs inside it.
        SwingUtilities.invokeLater(() -> mw.resolveOnOpponentsPass(remote, reeve[0]));
        awaitTrue(() -> onEdtGet(this::waitDialogShowing), "the wait for a pass on Reeve is up",
                () -> onEdtGet(mw::gameLogText));
        return reeve[0];
    }

    /**
     * {@link #openReeveWait}, then delivers the opponent's Y'shtola response inside the wait, exactly
     * as the socket reader would.
     */
    private StackEntry openReeveWaitAndRespondWithYshtola(Cards c) throws Exception {
        StackEntry reeve = openReeveWait(c);

        onEdt(() -> remote.onActionReceived(yshtolaCancel(c.yshtola)));

        assertEquals(2, (int) onEdtGet(() -> mw.gameState.stackSize()),
                "Y'shtola's response went on the Stack above Reeve's trigger");
        assertSame(c.yshtola, onEdtGet(() -> mw.gameState.peekStack().source()));
        assertTrue(onEdtGet(() -> remote.isAwaitingStackPass(reeve)), "and the wait for Reeve holds under it");
        assertFalse(onEdtGet(this::waitDialogShowing), "though it stands aside while this player answers");
        return reeve;
    }

    /** A label laid over the board above everything else, listening to the mouse as a card slot does. */
    private JLabel standInCard() {
        JLabel card = new JLabel("card");
        card.setBounds(300, 300, 60, 80);
        mw.frame.getLayeredPane().add(card, JLayeredPane.PALETTE_LAYER);
        return card;
    }

    /** A Summon with no text: it resolves to nothing, which is all a placeholder response needs. */
    private static CardData vanillaSummon(String name) {
        return new CardData(null, name, "Fire", 2, 0, "Summon", false, 0, false, false,
                Set.of(), 0, List.of(), null, List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(),
                false, false, null, false, false, false, false, false, 1,
                null, null, null, "");
    }

    /**
     * Whether a press on the board's Next button gets to it. Posted to the frame at the button's
     * position, which is where the OS delivers one, so it reaches whatever is on top there. The
     * button is disabled meanwhile — a disabled component still hears the mouse, as its tooltip
     * does, but cannot advance the phase — and only pressed, never released.
     */
    private boolean boardTakesAClick() throws Exception {
        AtomicBoolean reached = new AtomicBoolean();
        MouseAdapter probe = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { reached.set(true); }
        };
        boolean[] wasEnabled = new boolean[1];
        Point at = onEdtGet(() -> {
            JButton next = mw.nextPhaseButton;
            wasEnabled[0] = next.isEnabled();
            next.setEnabled(false);
            next.addMouseListener(probe);
            return SwingUtilities.convertPoint(next, next.getWidth() / 2, next.getHeight() / 2, mw.frame);
        });
        try {
            Toolkit.getDefaultToolkit().getSystemEventQueue().postEvent(new MouseEvent(mw.frame,
                    MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(), InputEvent.BUTTON1_DOWN_MASK,
                    at.x, at.y, 1, false, MouseEvent.BUTTON1));
            settle();
            return reached.get();
        } finally {
            onEdt(() -> {
                mw.nextPhaseButton.removeMouseListener(probe);
                mw.nextPhaseButton.setEnabled(wasEnabled[0]);
            });
        }
    }

    // ── Windows and clicks ───────────────────────────────────────────────

    private static void assumeDisplay() {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "needs a display");
    }

    private boolean waitDialogShowing() {
        for (Window w : mw.frame.getOwnedWindows())
            if (w instanceof JDialog d && d.isShowing() && "Waiting for opponent".equals(d.getTitle()))
                return true;
        return false;
    }

    /** The button labelled {@code text} on the showing Stack window, once it is up. */
    private JButton awaitButton(String text) throws Exception {
        JButton[] found = new JButton[1];
        awaitTrue(() -> onEdtGet(() -> {
            for (Window w : mw.frame.getOwnedWindows())
                if (w instanceof JWindow jw && jw.isShowing())
                    for (JButton b : buttons(jw.getContentPane()))
                        if (text.equals(b.getText())) { found[0] = b; return true; }
            return false;
        }), "the Stack window with its " + text + " button is up");
        return found[0];
    }

    private static List<JButton> buttons(Container root) {
        List<JButton> out = new ArrayList<>();
        for (Component c : root.getComponents()) {
            if (c instanceof JButton b) out.add(b);
            if (c instanceof Container k) out.addAll(buttons(k));
        }
        return out;
    }

    /**
     * A left click on {@code target}, posted to the system event queue the way the OS delivers one,
     * so a modal dialog's event filter sees it before the component does.
     */
    private static void click(Component target) {
        EventQueue q = Toolkit.getDefaultToolkit().getSystemEventQueue();
        int x = target.getWidth() / 2, y = target.getHeight() / 2;
        long now = System.currentTimeMillis();
        q.postEvent(new MouseEvent(target, MouseEvent.MOUSE_PRESSED, now,
                InputEvent.BUTTON1_DOWN_MASK, x, y, 1, false, MouseEvent.BUTTON1));
        q.postEvent(new MouseEvent(target, MouseEvent.MOUSE_RELEASED, now,
                0, x, y, 1, false, MouseEvent.BUTTON1));
        q.postEvent(new MouseEvent(target, MouseEvent.MOUSE_CLICKED, now,
                0, x, y, 1, false, MouseEvent.BUTTON1));
    }

    /** The pointer moving to {@code at} in the frame, posted where the OS delivers it. */
    private void move(Point at) throws Exception {
        Component frame = onEdtGet(() -> mw.frame);
        Toolkit.getDefaultToolkit().getSystemEventQueue().postEvent(new MouseEvent(frame,
                MouseEvent.MOUSE_MOVED, System.currentTimeMillis(), 0, at.x, at.y, 0, false,
                MouseEvent.NOBUTTON));
        settle();
    }

    /** Lets the posted events and anything they queued run. */
    private static void settle() throws Exception {
        for (int i = 0; i < 5; i++) {
            SwingUtilities.invokeAndWait(() -> {});
            Thread.sleep(50);
        }
    }

    private void assertStackPassSent() throws InterruptedException {
        GameAction action = sent.poll(5, TimeUnit.SECONDS);
        assertNotNull(action, "no pass went out - the opponent's client would still be waiting");
        assertEquals(ActionType.CHOICE, action.type());
        assertEquals(ChoiceKind.STACK_PASS.name(), action.payload().getString("kind"));
    }

    // ── EDT plumbing ─────────────────────────────────────────────────────

    private interface EdtBody { void run() throws Exception; }
    private interface EdtValue<T> { T get() throws Exception; }

    private static void onEdt(EdtBody body) throws Exception {
        Exception[] thrown = new Exception[1];
        Error[] error = new Error[1];
        SwingUtilities.invokeAndWait(() -> {
            try { body.run(); } catch (Exception e) { thrown[0] = e; } catch (Error e) { error[0] = e; }
        });
        if (error[0]  != null) throw error[0];
        if (thrown[0] != null) throw thrown[0];
    }

    private static <T> T onEdtGet(EdtValue<T> value) throws Exception {
        List<T> out = new ArrayList<>(1);
        onEdt(() -> out.add(value.get()));
        return out.get(0);
    }

    private interface Check { boolean ok() throws Exception; }

    private static void awaitTrue(Check check, String what) throws Exception {
        awaitTrue(check, what, () -> "");
    }

    /** {@code detail} is read only on a timeout, and appended to the failure. */
    private static void awaitTrue(Check check, String what, EdtValue<String> detail) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if (check.ok()) return;
            Thread.sleep(25);
        }
        fail("timed out waiting until " + what + "\n" + detail.get());
    }
}
