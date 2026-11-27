package shufflingway.graphics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;

import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;

/**
 * A damage tile sits raised until a point of damage presses it in, red, as its card arrives, and
 * comes back out when that card leaves. Each tile here animates as if on screen, and the tests
 * step its frames by hand.
 */
class DamageSlotTest {

	private static final BufferedImage CARD  = new BufferedImage(10, 14, BufferedImage.TYPE_INT_RGB);
	private static final BufferedImage OTHER = new BufferedImage(10, 14, BufferedImage.TYPE_INT_RGB);

	/** A tile that animates as though it were on screen. */
	private static DamageSlot onScreen() {
		return new DamageSlot("D", true) {
			@Override boolean animates() { return true; }
		};
	}

	private static void ticks(DamageSlot s, int n) {
		for (int i = 0; i < n; i++) s.tick();
	}

	@Test
	void aTileGainingItsCardPressesInOverThePopFrames() throws Exception {
		onEdt(() -> {
			DamageSlot s = onScreen();
			s.setCard(CARD);
			assertEquals(0, s.sink(), "it starts raised");
			double before = 0;
			for (int f = 1; f <= DamageSlot.POP_FRAMES; f++) {
				s.tick();
				assertTrue(s.sink() > before, "it goes further in on frame " + f);
				before = s.sink();
			}
			assertEquals(1, s.sink(), 1e-9, "fully in");
		});
	}

	@Test
	void aTileLosingItsCardComesBackOut() throws Exception {
		onEdt(() -> {
			DamageSlot s = onScreen();
			s.setCard(CARD);
			ticks(s, DamageSlot.POP_FRAMES);
			s.setCard(null);
			s.tick();
			assertTrue(s.sink() < 1, "on its way out");
			ticks(s, DamageSlot.POP_FRAMES);
			assertEquals(0, s.sink(), 1e-9, "fully out");
		});
	}

	// A refresh reloads the picture of a tile already in; that is not another point of damage.
	@Test
	void changingTheCardOfATileAlreadyInDoesNotPressItAgain() throws Exception {
		onEdt(() -> {
			DamageSlot s = onScreen();
			s.setCard(CARD);
			ticks(s, DamageSlot.POP_FRAMES);
			s.setCard(OTHER);
			s.tick();
			assertEquals(1, s.sink(), 1e-9);
		});
	}

	// The card leaving mid-press turns the tile round where it is, rather than jumping.
	@Test
	void aPressReversedHalfwayStartsBackFromWhereItIs() throws Exception {
		onEdt(() -> {
			DamageSlot s = onScreen();
			s.setCard(CARD);
			ticks(s, DamageSlot.POP_FRAMES / 2);
			double halfway = s.sink();
			s.setCard(null);
			assertEquals(halfway, s.sink(), 1e-9, "no jump as it turns");
			s.tick();
			assertTrue(s.sink() < halfway, "and heads back out");
		});
	}

	@Test
	void offScreenTheTileSimplyChanges() throws Exception {
		onEdt(() -> {
			DamageSlot s = new DamageSlot("D", true);
			s.setCard(CARD);
			assertEquals(1, s.sink(), 1e-9);
			s.setCard(null);
			assertEquals(0, s.sink(), 1e-9);
		});
	}

	@Test
	void aNewGameEmptiesTheTileAtOnce() throws Exception {
		onEdt(() -> {
			DamageSlot s = onScreen();
			s.setCard(CARD);
			ticks(s, 3);
			s.reset();
			assertEquals(0, s.sink(), 1e-9);
			s.tick();
			assertEquals(0, s.sink(), 1e-9, "no press survives the reset");
			s.setCard(CARD);
			assertEquals(0, s.sink(), "an empty tile presses in afresh");
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
