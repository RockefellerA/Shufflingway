package shufflingway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.EventQueue;
import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.function.Supplier;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * The two target pickers driven for real — opened on the EDT, clicked, confirmed.
 *
 * <p>"Any number of" is an "up to" with no ceiling, so every count is an answer, none included.
 * Both pickers give it one Confirm that says how many are picked, open from the start: the Break
 * Zone dialog (15-140S Rufus), in place of its Confirm and Skip, and the field's in-place target bar
 * (11-126R Rosa; 2-077L Vincent's "as many as you want", beside his running total). A bounded
 * "up to N" keeps the buttons it had.
 */
class AnyNumberPickerTest {

    private MainWindow mw;

    @AfterEach
    void tearDown() throws Exception {
        if (mw == null) return;
        SwingUtilities.invokeAndWait(() -> {
            for (Window w : mw.frame.getOwnedWindows()) w.dispose();
            mw.frame.dispose();
        });
    }

    // ── Break Zone dialog ────────────────────────────────────────────────

    @Test
    void anyNumberCountsItsPicksOnOneConfirm() throws Exception {
        assumeDisplay();
        mw = new MainWindow();
        List<CardData> zone = List.of(turk("Reno", 3), turk("Reno", 3), turk("Rude", 3));
        List<ForwardTarget> eligible = targetsInto(zone);
        BlockingQueue<List<ForwardTarget>> answer = openBreakZone(zone, eligible, Integer.MAX_VALUE,
                PickGate.DISTINCT_NAMES);

        JDialog dlg = awaitDialog();
        JButton confirm = button(dlg, "Confirm (0 chosen)");
        assertTrue(onEdt(confirm::isEnabled), "none is an answer, so Confirm is open before any pick");
        assertTrue(onEdt(() -> buttons(dlg).stream().noneMatch(b -> "Skip".equals(b.getText()))),
                "Confirm (0 chosen) is the skip");

        List<JLabel> renos = labels(dlg, l -> "Reno".equals(l.getText()));
        JLabel rude = labels(dlg, l -> "Rude".equals(l.getText())).get(0);
        click(renos.get(0));
        assertEquals("Confirm (1 chosen)", onEdt(confirm::getText));
        click(renos.get(1));
        assertEquals("Confirm (1 chosen)", onEdt(confirm::getText), "a second Reno shares a name already picked");
        click(rude);
        assertEquals("Confirm (2 chosen)", onEdt(confirm::getText));
        click(renos.get(0));
        assertEquals("Confirm (1 chosen)", onEdt(confirm::getText), "a pick taken back is not counted");

        SwingUtilities.invokeLater(confirm::doClick);
        assertEquals(List.of(eligible.get(2)), answer.poll(5, TimeUnit.SECONDS));
    }

    @Test
    void anyNumberMayConfirmNone() throws Exception {
        assumeDisplay();
        mw = new MainWindow();
        List<CardData> zone = List.of(turk("Reno", 3), turk("Rude", 3));
        BlockingQueue<List<ForwardTarget>> answer = openBreakZone(zone, targetsInto(zone), Integer.MAX_VALUE,
                PickGate.ANY);

        JButton confirm = button(awaitDialog(), "Confirm (0 chosen)");
        SwingUtilities.invokeLater(confirm::doClick);

        assertEquals(List.of(), answer.poll(5, TimeUnit.SECONDS),
                "and the whole zone is not taken for them, as it was when any number read as all");
    }

    @Test
    void aBoundedUpToKeepsItsConfirmAndSkip() throws Exception {
        assumeDisplay();
        mw = new MainWindow();
        List<CardData> zone = List.of(turk("Reno", 3), turk("Rude", 3), turk("Elena", 3));
        BlockingQueue<List<ForwardTarget>> answer = openBreakZone(zone, targetsInto(zone), 2, PickGate.ANY);

        JDialog dlg = awaitDialog();
        JButton confirm = button(dlg, "Confirm");
        JButton skip    = button(dlg, "Skip");
        assertFalse(onEdt(confirm::isEnabled), "unchanged: an up-to-N Confirm opens on the first pick");

        SwingUtilities.invokeLater(skip::doClick);
        assertEquals(List.of(), answer.poll(5, TimeUnit.SECONDS));
    }

    // ── Field target bar ─────────────────────────────────────────────────

    @Test
    void theFieldBarCountsItsPicksToo() throws Exception {
        assumeDisplay();
        mw = new MainWindow();
        List<ForwardTarget> eligible = twoOwnForwards(3, 5);
        BlockingQueue<List<ForwardTarget>> answer = openFieldBar(eligible, Integer.MAX_VALUE, -1);

        JDialog bar = awaitDialog();
        JButton confirm = button(bar, "Confirm (0 chosen)");
        List<JLabel> row = p1ForwardLabels();
        click(row.get(0));
        assertEquals("Confirm (1 chosen)", onEdt(confirm::getText));
        click(row.get(1));
        assertEquals("Confirm (2 chosen)", onEdt(confirm::getText));
        click(row.get(0));
        assertEquals("Confirm (1 chosen)", onEdt(confirm::getText), "a pick taken back is not counted");

        SwingUtilities.invokeLater(confirm::doClick);
        assertEquals(List.of(eligible.get(1)), answer.poll(5, TimeUnit.SECONDS));
    }

    @Test
    void aBudgetedBarKeepsItsRunningTotalBesideTheCount() throws Exception {
        // 2-077L Vincent: "Choose as many Forwards as you want with a total cost of 7 or less."
        assumeDisplay();
        mw = new MainWindow();
        List<ForwardTarget> eligible = twoOwnForwards(3, 5);
        BlockingQueue<List<ForwardTarget>> answer = openFieldBar(eligible, Integer.MAX_VALUE, 7);

        JDialog bar = awaitDialog();
        JButton confirm = button(bar, "Confirm (0 chosen)");
        JLabel total = labels(bar, l -> l.getText() != null && l.getText().startsWith("Total cost")).get(0);
        List<JLabel> row = p1ForwardLabels();
        click(row.get(0));
        click(row.get(1));
        assertEquals("Confirm (2 chosen)", onEdt(confirm::getText));
        assertEquals("Total cost: 8 / 7", onEdt(total::getText));
        assertEquals(Color.RED, onEdt(total::getForeground));
        assertFalse(onEdt(confirm::isEnabled), "the budget still holds Confirm shut while the picks overspend");

        click(row.get(1));
        assertTrue(onEdt(confirm::isEnabled));
        SwingUtilities.invokeLater(confirm::doClick);
        assertEquals(List.of(eligible.get(0)), answer.poll(5, TimeUnit.SECONDS));
    }

    @Test
    void aBoundedFieldUpToKeepsItsPlainConfirm() throws Exception {
        assumeDisplay();
        mw = new MainWindow();
        List<ForwardTarget> eligible = twoOwnForwards(3, 5);
        BlockingQueue<List<ForwardTarget>> answer = openFieldBar(eligible, 2, -1);

        JDialog bar = awaitDialog();
        JButton confirm = button(bar, "Confirm");
        click(p1ForwardLabels().get(0));
        assertEquals("Confirm", onEdt(confirm::getText));

        SwingUtilities.invokeLater(confirm::doClick);
        assertEquals(List.of(eligible.get(0)), answer.poll(5, TimeUnit.SECONDS));
    }

    // ── Boards ───────────────────────────────────────────────────────────

    private static final String TITLE = "Pick Turks";

    private static CardData turk(String name, int cost) {
        return TestCards.makeForwardWithJob(name, "Lightning", cost, 7000, "Member of the Turks");
    }

    private static List<ForwardTarget> targetsInto(List<CardData> zone) {
        List<ForwardTarget> out = new ArrayList<>();
        for (int i = 0; i < zone.size(); i++)
            out.add(new ForwardTarget(true, i, ForwardTarget.CardZone.BREAK_ZONE));
        return out;
    }

    /** Two Forwards of P1's, of the two costs, as the field picker's eligible targets. */
    private List<ForwardTarget> twoOwnForwards(int firstCost, int secondCost) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            TestCards.placeP1Forward(mw, turk("Reno", firstCost));
            TestCards.placeP1Forward(mw, turk("Rude", secondCost));
        });
        return List.of(new ForwardTarget(true, 0, ForwardTarget.CardZone.FORWARD),
                new ForwardTarget(true, 1, ForwardTarget.CardZone.FORWARD));
    }

    /** Opens the Break Zone picker as an "up to {@code max}" on the EDT; its answer lands in the queue. */
    private BlockingQueue<List<ForwardTarget>> openBreakZone(List<CardData> zone, List<ForwardTarget> eligible,
            int max, PickGate gate) {
        BlockingQueue<List<ForwardTarget>> answer = new ArrayBlockingQueue<>(1);
        SwingUtilities.invokeLater(() -> answer.add(
                mw.showBreakZoneSelectDialog(eligible, zone, max, true, TITLE, gate)));
        return answer;
    }

    /** Opens the field's in-place bar as an "up to {@code max}", budgeted when {@code budget} ≥ 0. */
    private BlockingQueue<List<ForwardTarget>> openFieldBar(List<ForwardTarget> eligible, int max, int budget) {
        BlockingQueue<List<ForwardTarget>> answer = new ArrayBlockingQueue<>(1);
        SwingUtilities.invokeLater(() -> answer.add(
                mw.selectFieldTargetsInPlace(eligible, max, true, TITLE, budget)));
        return answer;
    }

    @SuppressWarnings("unchecked")
    private List<JLabel> p1ForwardLabels() throws Exception {
        Field f = MainWindow.class.getDeclaredField("p1ForwardLabels");
        f.setAccessible(true);
        return (List<JLabel>) f.get(mw);
    }

    // ── Windows and clicks ───────────────────────────────────────────────

    private static void assumeDisplay() {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "needs a display");
    }

    private JDialog awaitDialog() throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            JDialog found = onEdt(() -> {
                for (Window w : mw.frame.getOwnedWindows())
                    if (w instanceof JDialog d && d.isShowing() && TITLE.equals(d.getTitle())) return d;
                return null;
            });
            if (found != null) return found;
            Thread.sleep(25);
        }
        fail("the picker never opened");
        return null;
    }

    private static JButton button(JDialog dlg, String text) throws Exception {
        return onEdt(() -> buttons(dlg).stream().filter(b -> text.equals(b.getText())).findFirst()
                .orElseThrow(() -> new AssertionError("no " + text + " button")));
    }

    private static List<JButton> buttons(Container root) {
        List<JButton> out = new ArrayList<>();
        for (Component c : root.getComponents()) {
            if (c instanceof JButton b) out.add(b);
            if (c instanceof Container k) out.addAll(buttons(k));
        }
        return out;
    }

    /** Labels under {@code dlg} that {@code which} accepts — a card cell's name caption takes its clicks. */
    private static List<JLabel> labels(JDialog dlg, Predicate<JLabel> which) throws Exception {
        return onEdt(() -> {
            List<JLabel> out = new ArrayList<>();
            collectLabels(dlg.getContentPane(), which, out);
            return out;
        });
    }

    private static void collectLabels(Container root, Predicate<JLabel> which, List<JLabel> out) {
        for (Component c : root.getComponents()) {
            if (c instanceof JLabel l && which.test(l)) out.add(l);
            if (c instanceof Container k) collectLabels(k, which, out);
        }
    }

    /**
     * A left press on {@code target}, posted to the system event queue the way the OS delivers one,
     * so a modal dialog's event filter sees it; then lets it run.
     */
    private static void click(Component target) throws Exception {
        EventQueue q = Toolkit.getDefaultToolkit().getSystemEventQueue();
        int x = Math.max(1, target.getWidth() / 2), y = Math.max(1, target.getHeight() / 2);
        long now = System.currentTimeMillis();
        q.postEvent(new MouseEvent(target, MouseEvent.MOUSE_PRESSED, now,
                InputEvent.BUTTON1_DOWN_MASK, x, y, 1, false, MouseEvent.BUTTON1));
        q.postEvent(new MouseEvent(target, MouseEvent.MOUSE_RELEASED, now,
                0, x, y, 1, false, MouseEvent.BUTTON1));
        for (int i = 0; i < 3; i++) SwingUtilities.invokeAndWait(() -> {});
    }

    private static <T> T onEdt(Supplier<T> value) throws Exception {
        List<T> out = new ArrayList<>(1);
        SwingUtilities.invokeAndWait(() -> out.add(value.get()));
        return out.get(0);
    }
}
