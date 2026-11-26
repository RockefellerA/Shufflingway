package shufflingway.graphics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Random;

import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;

/**
 * The shuffle has to hand over cleanly at both ends: its first frame is the deck slot's pile as it
 * stood, so the slot can go blank beneath it unseen, and its last is the same pile back on the slot,
 * which the slot shows again once {@code onDone} runs. In between no card may go missing.
 */
class DeckShuffleAnimatorTest {

	private static final int SIZE = 700;
	private static final int CW = CardAnimation.CARD_W, CH = CardAnimation.CARD_H;
	private static final Dimension PILE = CardPile.size(CW, CH);
	/** Room on both sides, so the cut fits whichever way it goes. */
	private static final Point ORIGIN = new Point(SIZE / 2 - PILE.width / 2, SIZE / 2 - PILE.height / 2);
	private static final Object DECK = new Object();

	@Test
	void theFirstFrameIsTheSlotsPileExactly() throws Exception {
		onEdt(() -> {
			BufferedImage back = cardback(3);
			DeckShuffleAnimator a = started(back, 50, CardPile.Lean.RIGHT, true, null);

			assertSamePixels(placed(CardPile.render(back, 50, CW, CH, CardPile.Lean.RIGHT)), painted(a), "frame 0");
		});
	}

	@Test
	void theLastFrameIsTheSlotsPileExactly() throws Exception {
		onEdt(() -> {
			// Five times the card, which toARGB area-averages rather than scaling in one step.
			BufferedImage back = cardback(5);
			DeckShuffleAnimator a = started(back, 37, CardPile.Lean.LEFT, false, null);
			for (int f = 0; f < DeckShuffleAnimator.TOTAL_FRAMES; f++) a.tick();

			assertSamePixels(placed(CardPile.render(back, 37, CW, CH, CardPile.Lean.LEFT)), painted(a),
					"frame " + DeckShuffleAnimator.TOTAL_FRAMES);
		});
	}

	@Test
	void everyCardIsShownOnEveryFrame() throws Exception {
		onEdt(() -> {
			for (int count : new int[] { 2, 3, 7, 18, 19, 50, 60 }) {
				DeckShuffleAnimator a = started(cardback(1), count, CardPile.Lean.RIGHT, true, null);
				for (int f = 0; f <= DeckShuffleAnimator.TOTAL_FRAMES; f++) {
					assertEquals(count, a.cardsShown(DECK), count + " cards, frame " + f);
					a.tick();
				}
			}
		});
	}

	@Test
	void midRiffleCardsAreInBothHalvesTheMiddleAndTheAir() throws Exception {
		onEdt(() -> {
			DeckShuffleAnimator a = started(cardback(1), 50, CardPile.Lean.RIGHT, true, null);
			BufferedImage before = painted(a);
			for (int f = 0; f < (DeckShuffleAnimator.CUT_END + DeckShuffleAnimator.RIFFLE_END) / 2; f++) a.tick();
			BufferedImage during = painted(a);

			// The cut is set down to the left of the slot, where nothing was drawn before.
			int x = ORIGIN.x - CW / 4, y = ORIGIN.y + PILE.height / 2;
			assertEquals(backdrop().getRGB(x, y), before.getRGB(x, y));
			assertTrue(during.getRGB(x, y) != backdrop().getRGB(x, y), "the cut half beside the slot");
		});
	}

	@Test
	void onDoneRunsOnceWhenThePileIsBack() throws Exception {
		onEdt(() -> {
			int[] done = new int[1];
			DeckShuffleAnimator a = started(cardback(1), 50, CardPile.Lean.RIGHT, true, () -> done[0]++);
			for (int f = 1; f < DeckShuffleAnimator.TOTAL_FRAMES; f++) a.tick();
			assertEquals(0, done[0], "before the last frame");
			a.tick();
			assertEquals(1, done[0], "on the last frame");
			for (int f = 0; f < 10; f++) a.tick();
			assertEquals(1, done[0], "after");
			assertFalse(a.isShuffling(DECK));
		});
	}

	@Test
	void shufflingAgainStartsOverAndOnlyTheLaterOneFinishes() throws Exception {
		onEdt(() -> {
			int[] first = new int[1], second = new int[1];
			DeckShuffleAnimator a = started(cardback(1), 50, CardPile.Lean.RIGHT, true, () -> first[0]++);
			for (int f = 0; f < 20; f++) a.tick();
			a.start(DECK, ORIGIN, cardback(1), 49, CW, CH, CardPile.Lean.RIGHT, true, () -> second[0]++, new Random(2));
			for (int f = 0; f < DeckShuffleAnimator.TOTAL_FRAMES - 1; f++) a.tick();
			assertEquals(0, second[0], "the second shuffle runs its full length");
			for (int f = 0; f < 10; f++) a.tick();
			assertEquals(0, first[0]);
			assertEquals(1, second[0]);
		});
	}

	@Test
	void fewerThanTwoCardsMakeNoShuffle() throws Exception {
		onEdt(() -> {
			int[] done = new int[1];
			DeckShuffleAnimator a = started(cardback(1), 1, CardPile.Lean.RIGHT, true, () -> done[0]++);
			assertEquals(1, done[0]);
			assertFalse(a.isShuffling(DECK));
		});
	}

	@Test
	void theHalvesTakeTurnsAndEveryPacketLandsBeforeTheRiffleEnds() {
		for (int count = 2; count <= 60; count++) {
			List<DeckShuffleAnimator.Drop> plan = DeckShuffleAnimator.plan(count, new Random(count));
			int cut = 0, bottom = 0, lastStart = -1;
			for (int i = 0; i < plan.size(); i++) {
				DeckShuffleAnimator.Drop d = plan.get(i);
				assertEquals(i % 2 == 1, d.fromCut(), count + " cards, packet " + i);
				assertTrue(d.size() >= 1);
				assertTrue(d.start() > DeckShuffleAnimator.CUT_END && d.start() > lastStart, "after the cut, in order");
				assertTrue(d.start() + DeckShuffleAnimator.DROP_FRAMES <= DeckShuffleAnimator.RIFFLE_END, "lands in time");
				lastStart = d.start();
				if (d.fromCut()) cut += d.size(); else bottom += d.size();
			}
			assertEquals(count / 2, cut, count + " cards: the cut");
			assertEquals(count - count / 2, bottom, count + " cards: the bottom half");
			assertTrue(plan.size() <= DeckShuffleAnimator.MAX_DROPS);
		}
	}

	// -------------------------------------------------------------------------

	/** A cardback {@code scale} times the card's size, striped both ways so a misplaced pixel would show. */
	private static BufferedImage cardback(int scale) {
		BufferedImage img = new BufferedImage(CW * scale, CH * scale, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		g.setColor(new Color(40, 70, 160));
		g.fillRect(0, 0, img.getWidth(), img.getHeight());
		g.setColor(new Color(220, 190, 90));
		for (int y = 0; y < img.getHeight(); y += 7 * scale) g.fillRect(0, y, img.getWidth(), 2 * scale);
		g.setColor(new Color(150, 30, 40));
		for (int x = 0; x < img.getWidth(); x += 11 * scale) g.fillRect(x, 0, scale, img.getHeight());
		g.dispose();
		return img;
	}

	private static DeckShuffleAnimator started(BufferedImage back, int count, CardPile.Lean lean,
			boolean cutLeft, Runnable onDone) {
		DeckShuffleAnimator a = new DeckShuffleAnimator();
		a.setSize(SIZE, SIZE);
		a.start(DECK, ORIGIN, back, count, CW, CH, lean, cutLeft, onDone, new Random(1));
		return a;
	}

	/** {@code pile} drawn where the slot draws it, over the backdrop. */
	private static BufferedImage placed(BufferedImage pile) {
		BufferedImage out = backdrop();
		Graphics2D g = out.createGraphics();
		g.drawImage(pile, ORIGIN.x, ORIGIN.y, null);
		g.dispose();
		return out;
	}

	private static BufferedImage painted(DeckShuffleAnimator a) {
		BufferedImage out = backdrop();
		Graphics2D g = out.createGraphics();
		a.paint(g);
		g.dispose();
		return out;
	}

	private static BufferedImage backdrop() {
		BufferedImage out = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = out.createGraphics();
		g.setColor(new Color(28, 34, 44));
		g.fillRect(0, 0, SIZE, SIZE);
		g.dispose();
		return out;
	}

	private static void assertSamePixels(BufferedImage expected, BufferedImage actual, String what) {
		for (int y = 0; y < SIZE; y++)
			for (int x = 0; x < SIZE; x++)
				if (expected.getRGB(x, y) != actual.getRGB(x, y))
					assertEquals(Integer.toHexString(expected.getRGB(x, y)), Integer.toHexString(actual.getRGB(x, y)),
							what + " differs at (" + x + ", " + y + ")");
	}

	/** Runs {@code body} on the EDT, so the animator's own timer cannot step a frame mid-test. */
	private static void onEdt(Runnable body) throws Exception {
		Throwable[] failure = new Throwable[1];
		SwingUtilities.invokeAndWait(() -> {
			try { body.run(); } catch (Throwable t) { failure[0] = t; }
		});
		if (failure[0] instanceof Error e) throw e;
		if (failure[0] instanceof Exception e) throw e;
	}
}
