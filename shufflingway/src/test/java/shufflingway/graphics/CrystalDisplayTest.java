package shufflingway.graphics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;

import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;

/**
 * A Crystal count changes with a flash, white for a gain and yellow for a spend, and the number
 * changes only at the flash's brightest frame. Each badge here animates as if on screen, and the
 * tests step its frames by hand.
 */
class CrystalDisplayTest {

	/** A badge showing {@code count} that animates as though it were on screen. */
	private static CrystalDisplay onScreen(int count) {
		return new CrystalDisplay(count, Color.BLUE) {
			@Override boolean animates() { return true; }
		};
	}

	private static void ticks(CrystalDisplay d, int n) {
		for (int i = 0; i < n; i++) d.tick();
	}

	@Test
	void aGainFlashesWhiteAndTheNumberChangesAtTheBrightestFrame() throws Exception {
		onEdt(() -> {
			CrystalDisplay d = onScreen(2);
			d.setCount(3);
			assertEquals(CrystalDisplay.GAIN_FLASH, d.flashColor());
			double before = -1;
			for (int f = 0; f < CrystalDisplay.APEX_FRAME; f++) {
				assertEquals(2, d.shownCount(), "frame " + f + ", before the brightest");
				assertTrue(d.flashLevel() > before, "the flash brightens on frame " + f);
				before = d.flashLevel();
				d.tick();
			}
			assertEquals(3, d.shownCount(), "the brightest frame");
			assertEquals(1.0, d.flashLevel(), 1e-9);
			ticks(d, CrystalDisplay.FLASH_FRAMES - CrystalDisplay.APEX_FRAME);
			assertNull(d.flashColor(), "the flash is over");
			assertEquals(0, d.flashLevel());
			assertEquals(3, d.shownCount());
		});
	}

	@Test
	void aSpendFlashesYellow() throws Exception {
		onEdt(() -> {
			CrystalDisplay d = onScreen(3);
			d.setCount(1);
			assertEquals(CrystalDisplay.SPEND_FLASH, d.flashColor());
			ticks(d, CrystalDisplay.APEX_FRAME - 1);
			assertEquals(3, d.shownCount());
			d.tick();
			assertEquals(1, d.shownCount());
			ticks(d, CrystalDisplay.FLASH_FRAMES);
		});
	}

	// Gaining a Crystal and spending it at once is two changes, and each is seen.
	@Test
	void aChangeDuringAFlashWaitsForItToFinish() throws Exception {
		onEdt(() -> {
			CrystalDisplay d = onScreen(2);
			d.setCount(3);
			d.tick();
			d.setCount(2);
			assertEquals(CrystalDisplay.GAIN_FLASH, d.flashColor(), "the first flash runs on");
			ticks(d, CrystalDisplay.FLASH_FRAMES - 1);
			assertEquals(3, d.shownCount(), "the gain has landed");
			assertEquals(CrystalDisplay.SPEND_FLASH, d.flashColor(), "then the spend flashes");
			ticks(d, CrystalDisplay.APEX_FRAME);
			assertEquals(2, d.shownCount());
			ticks(d, CrystalDisplay.FLASH_FRAMES - CrystalDisplay.APEX_FRAME);
			assertNull(d.flashColor());
		});
	}

	// The window refreshes the count far more often than it changes.
	@Test
	void settingTheCountItIsShowingOrAboutToShowFlashesNothingMore() throws Exception {
		onEdt(() -> {
			CrystalDisplay d = onScreen(2);
			d.setCount(2);
			assertNull(d.flashColor());
			d.setCount(3);
			d.setCount(3);
			ticks(d, CrystalDisplay.FLASH_FRAMES);
			assertNull(d.flashColor(), "one change, one flash");
			assertEquals(3, d.shownCount());
		});
	}

	@Test
	void offScreenTheNumberSimplyChanges() throws Exception {
		onEdt(() -> {
			CrystalDisplay d = new CrystalDisplay(2, Color.BLUE);
			d.setCount(5);
			assertEquals(5, d.shownCount());
			assertNull(d.flashColor());
		});
	}

	@Test
	void aNewGameResetsAtOnceAndDropsAnyFlash() throws Exception {
		onEdt(() -> {
			CrystalDisplay d = onScreen(2);
			d.setCount(4);
			d.tick();
			d.setCount(1);
			d.hardReset();
			assertEquals(0, d.shownCount());
			assertNull(d.flashColor());
			d.tick();
			assertEquals(0, d.shownCount(), "nothing queued survives the reset");
		});
	}

	private static void onEdt(Runnable body) throws Exception {
		Throwable[] failure = new Throwable[1];
		SwingUtilities.invokeAndWait(() -> {
			try { body.run(); } catch (Throwable t) { failure[0] = t; }
		});
		if (failure[0] instanceof Error e) throw e;
		if (failure[0] instanceof Exception e) throw e;
	}
}
