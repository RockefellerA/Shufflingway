package shufflingway;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.GraphicsEnvironment;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;

/**
 * The side-panel divider is draggable from the moment the window opens, within the same limits it
 * has once a game is running, and nothing moves it but the player.
 *
 * <p>Three separate defects sat behind that, each hidden by the one above it:
 * <ul>
 *   <li>the drag handler bailed on {@code nativeImgW == 0}, and the clamp bounds it needed were
 *       both 0 — all set only by {@code sizePreviewPanel}, which runs when the first card image
 *       loads into the preview, i.e. once a game is under way and a card has been hovered;</li>
 *   <li>with that fixed, the drag updated {@code sidePanelW} and the preferred sizes but the panel
 *       never moved: {@code setPreferredSize} invalidates nothing, so {@code frame.validate()} had
 *       nothing marked invalid and returned at once;</li>
 *   <li>and the bounds seeded before the first card were guessed from a UI-scaled board
 *       measurement rather than the stored image, so the panel could be dragged half again wider
 *       before a game than after one, then snapped back when a game started.</li>
 * </ul>
 *
 * <p>Reads {@link MainWindow}'s private members reflectively. They have no accessors and want none:
 * this is the only thing that asks about them, and widening them for a test would put the panel's
 * sizing state on the class's surface.
 */
public class SidePanelResizeTest {

	/** The size every card image is stored at; {@link MainWindow} seeds its limits from this. */
	private static final int NATIVE_CARD_W = 429, NATIVE_CARD_H = 600;

	private static int intField(MainWindow mw, String name) throws Exception {
		Field f = MainWindow.class.getDeclaredField(name);
		f.setAccessible(true);
		return f.getInt(mw);
	}

	private static JPanel panelField(MainWindow mw, String name) throws Exception {
		Field f = MainWindow.class.getDeclaredField(name);
		f.setAccessible(true);
		return (JPanel) f.get(mw);
	}

	private static void invoke(MainWindow mw, String name, Class<?>[] types, Object... args)
			throws Exception {
		Method m = MainWindow.class.getDeclaredMethod(name, types);
		m.setAccessible(true);
		m.invoke(mw, args);
	}

	/** A mouse event carrying explicit screen coordinates, so no component need be showing. */
	private static MouseEvent at(JPanel source, int id, int screenX) {
		return new MouseEvent(source, id, System.currentTimeMillis(), 0,
				0, 0, screenX, 0, 0, false, MouseEvent.NOBUTTON);
	}

	/** Presses on the divider and drags {@code travel} px across the screen; returns the new width. */
	private static int dragTo(MainWindow mw, JPanel handle, int travel) throws Exception {
		for (MouseListener l : handle.getMouseListeners())
			l.mousePressed(at(handle, MouseEvent.MOUSE_PRESSED, 0));
		for (MouseMotionListener l : handle.getMouseMotionListeners())
			l.mouseDragged(at(handle, MouseEvent.MOUSE_DRAGGED, travel));
		return intField(mw, "sidePanelW");
	}

	@Test
	void resizeBoundsAreUsableBeforeAnyCardHasBeenPreviewed() throws Exception {
		MainWindow mw = new MainWindow();
		assertEquals(0, intField(mw, "nativeImgW"), "no card has been previewed yet");
		int min = intField(mw, "minSidePanelW");
		int max = intField(mw, "maxSidePanelW");
		assertTrue(min > 0, "a zero minimum is what disabled the divider until the first hover");
		assertTrue(max > min, "the clamp range has to be non-empty for a drag to move anything");
	}

	@Test
	void theLimitsAreTheSameBeforeAndAfterTheFirstCardLoads() throws Exception {
		MainWindow mw = new MainWindow();
		int minBefore = intField(mw, "minSidePanelW");
		int maxBefore = intField(mw, "maxSidePanelW");

		// What happens when a game starts and the first card image finishes loading.
		invoke(mw, "sizePreviewPanel", new Class<?>[]{int.class, int.class},
				NATIVE_CARD_W, NATIVE_CARD_H);

		assertEquals(minBefore, intField(mw, "minSidePanelW"),
				"the minimum must not change when a card is finally measured");
		assertEquals(maxBefore, intField(mw, "maxSidePanelW"),
				"the maximum must not change either; guessing it from a UI-scaled board measurement "
				+ "let the panel go half again wider before a game than after one");
	}

	@Test
	void startingAGameDoesNotResizeThePanel() throws Exception {
		MainWindow mw = new MainWindow();
		// The player drags the divider all the way out before starting anything.
		invoke(mw, "setSidePanelWidth", new Class<?>[]{int.class}, intField(mw, "maxSidePanelW"));
		int chosen = intField(mw, "sidePanelW");

		invoke(mw, "sizePreviewPanel", new Class<?>[]{int.class, int.class},
				NATIVE_CARD_W, NATIVE_CARD_H);

		assertEquals(chosen, intField(mw, "sidePanelW"),
				"the panel opens the game at the width the player chose, not one of its own");
	}

	@Test
	void draggingTheDividerResizesThePanelWithNoGameInProgress() throws Exception {
		MainWindow mw = new MainWindow();
		JPanel handle = panelField(mw, "resizeHandle");
		if (handle.getMouseMotionListeners().length == 0) {
			// Resizing is deliberately not installed on a scaled-down UI, where growing the preview
			// would push the log and the hand off-screen. Nothing to assert on such a display.
			assertTrue(UiScale.factor < 1.0, "the drag handler is only omitted for a scaled UI");
			return;
		}

		int min = intField(mw, "minSidePanelW");
		int max = intField(mw, "maxSidePanelW");
		assertTrue(max > min, "the two ends have to differ for the assertions below to mean anything");
		boolean dockedRight = "right".equals(AppSettings.getSidePanelSide());

		// Dragged to each end in turn rather than once. Landing on a clamp is only evidence the
		// handler ran if the width was somewhere else beforehand, and a single drag could pass by
		// happening to start at the end it was aimed at.
		assertEquals(min, dragTo(mw, handle, dockedRight ? 10_000 : -10_000),
				"dragging inwards should shrink the panel to its minimum");
		assertEquals(max, dragTo(mw, handle, dockedRight ? -10_000 : 10_000),
				"dragging outwards should grow it to its maximum; before the fix the handler "
				+ "returned immediately and neither drag moved anything");
	}

	@Test
	void theDragActuallyRelaysOutThePanelAndNotJustItsPreferredSize() throws Exception {
		// One layer under the test above: the drag updated sidePanelW and the preferred sizes, and
		// the panel on screen never moved. It looked fixed from the fields alone, which is exactly
		// all the tests above check.
		if (GraphicsEnvironment.isHeadless()) return;

		MainWindow mw = new MainWindow();
		JPanel handle = panelField(mw, "resizeHandle");
		if (handle.getMouseMotionListeners().length == 0) return;   // resizing off on a scaled UI

		Field ff = MainWindow.class.getDeclaredField("frame");
		ff.setAccessible(true);
		JFrame frame = (JFrame) ff.get(mw);
		JPanel side = panelField(mw, "sidePanel");
		try {
			SwingUtilities.invokeAndWait(() -> { frame.setVisible(true); frame.validate(); });
			assertTrue(side.getWidth() > 0, "the panel should have a width once the frame is shown");

			boolean dockedRight = "right".equals(AppSettings.getSidePanelSide());
			SwingUtilities.invokeAndWait(() -> {
				try {
					dragTo(mw, handle, dockedRight ? -10_000 : 10_000);
				} catch (Exception e) {
					throw new IllegalStateException(e);
				}
			});
			SwingUtilities.invokeAndWait(() -> { });   // let the queued validation run

			assertEquals(intField(mw, "maxSidePanelW"), side.getWidth(),
					"the panel on screen has to follow the drag, not just its preferred size");
		} finally {
			SwingUtilities.invokeAndWait(frame::dispose);
		}
	}

	@Test
	void eachResolutionRemembersItsOwnWidth() {
		// Written straight into the in-memory settings and read back; save() is never called, so
		// nothing here touches the file on disk.
		int wide = AppSettings.getSidePanelWidth("2560x1440", -1);
		int narrow = AppSettings.getSidePanelWidth("1280x720", -1);
		try {
			AppSettings.setSidePanelWidth("2560x1440", 420);
			AppSettings.setSidePanelWidth("1280x720", 300);

			assertEquals(420, AppSettings.getSidePanelWidth("2560x1440", -1));
			assertEquals(300, AppSettings.getSidePanelWidth("1280x720", -1),
					"a width chosen at one resolution must not follow the player to another");
			// A resolution with nothing saved falls back to the single pre-per-resolution value if
			// the settings file still carries one, and only then to the caller's default. Either
			// way it must not pick up a width filed against some other resolution.
			int unsetA = AppSettings.getSidePanelWidth("999x111", -1);
			int unsetB = AppSettings.getSidePanelWidth("888x222", -1);
			assertEquals(unsetA, unsetB, "every unset resolution shares one fallback");
			assertNotEquals(420, unsetA, "and it is not the width saved for 2560x1440");
			assertNotEquals(300, unsetA, "nor the one saved for 1280x720");
		} finally {
			if (wide   >= 0) AppSettings.setSidePanelWidth("2560x1440", wide);
			if (narrow >= 0) AppSettings.setSidePanelWidth("1280x720", narrow);
		}
	}
}
