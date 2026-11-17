package shufflingway.graphics;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.image.BufferedImage;

import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;

import shufflingway.CardState;

/**
 * The Priming burn has to hand over cleanly at both ends: its first frame is the slot as it stood,
 * so the slot can go blank beneath it unseen, and its last is the primed card's slot render, which
 * {@code FieldEntryAnimator} then puts into the slot. Between them the fire spreads from the centre.
 */
class CardPrimingAnimatorTest {

	private static final int SIZE = 400;
	private static final Point CENTER = new Point(SIZE / 2, SIZE / 2);

	@Test
	void theFirstFrameIsTheOutgoingCardExactly() throws Exception {
		onEdt(() -> {
			BufferedImage from = slot(new Color(200, 40, 30)), to = slot(new Color(30, 60, 200));
			CardPrimingAnimator a = started(from, to, "Fire");

			assertSamePixels(placed(from), painted(a), "frame 0");
		});
	}

	@Test
	void theLastFrameIsTheIncomingCardExactly() throws Exception {
		onEdt(() -> {
			BufferedImage from = slot(new Color(200, 40, 30)), to = slot(new Color(30, 60, 200));
			CardPrimingAnimator a = started(from, to, "Water/Fire");
			for (int f = 0; f < CardPrimingAnimator.TOTAL_FRAMES; f++) a.tick();

			assertSamePixels(placed(to), painted(a), "frame " + CardPrimingAnimator.TOTAL_FRAMES);
		});
	}

	@Test
	void halfwayThroughTheCentreHasAlreadyBurnedThrough() throws Exception {
		onEdt(() -> {
			BufferedImage from = slot(new Color(200, 40, 30)), to = slot(new Color(30, 60, 200));
			CardPrimingAnimator a = started(from, to, "Ice");
			for (int f = 0; f < CardPrimingAnimator.TOTAL_FRAMES * 2 / 5; f++) a.tick();

			// The card's centre, which on an ACTIVE slot canvas sits right of the tab gutter.
			int x = CENTER.x - from.getWidth()  / 2 + CardAnimation.LEFT_GUTTER + CardAnimation.CARD_W / 2;
			int y = CENTER.y - from.getHeight() / 2 + CardAnimation.CARD_H / 2;
			assertEquals(placed(to).getRGB(x, y), painted(a).getRGB(x, y));
		});
	}

	// -------------------------------------------------------------------------

	/** A slot render: a card of {@code color}, striped so a misplaced pixel would show. */
	private static BufferedImage slot(Color color) {
		BufferedImage card = new BufferedImage(CardAnimation.CARD_W, CardAnimation.CARD_H, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = card.createGraphics();
		g.setColor(color);
		g.fillRect(0, 0, card.getWidth(), card.getHeight());
		g.setColor(color.darker());
		for (int y = 0; y < card.getHeight(); y += 6) g.fillRect(0, y, card.getWidth(), 2);
		g.dispose();
		return CardAnimation.renderBackupCard(card, CardState.ACTIVE);
	}

	private static CardPrimingAnimator started(BufferedImage from, BufferedImage to, String elements) {
		CardPrimingAnimator a = new CardPrimingAnimator();
		a.setSize(SIZE, SIZE);
		a.start(from, to, CENTER, elements.split("/"));
		return a;
	}

	/** {@code img} drawn where the animator puts its canvas, over the backdrop. */
	private static BufferedImage placed(BufferedImage img) {
		BufferedImage out = backdrop();
		Graphics2D g = out.createGraphics();
		g.drawImage(img, CENTER.x - img.getWidth() / 2, CENTER.y - img.getHeight() / 2, null);
		g.dispose();
		return out;
	}

	private static BufferedImage painted(CardPrimingAnimator a) {
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
