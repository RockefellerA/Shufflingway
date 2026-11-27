package shufflingway.graphics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import org.junit.jupiter.api.Test;

/** The board textures: how they are saved, that their tile wraps without a seam, and the two seats. */
class BoardTextureTest {

	@Test
	void eachTextureReadsBackFromItsSavedId() {
		for (BoardTexture t : BoardTexture.values()) assertEquals(t, BoardTexture.parse(t.id()));
	}

	@Test
	void noneOrAnUnknownIdReadsAsNone() {
		assertEquals(BoardTexture.NONE, BoardTexture.parse(null));
		assertEquals(BoardTexture.NONE, BoardTexture.parse("Velvet"));
		assertEquals(BoardTexture.SAND, BoardTexture.parse(" sand "));
	}

	/** Each surface is the same a tile across or a tile down, so the tile joins without a seam. */
	@Test
	void everySurfaceWrapsWithItsTile() {
		for (BoardTexture t : BoardTexture.values()) {
			int pw = t.periodW(), ph = t.periodH();
			for (int variant = 0; variant < 2; variant++) {
				for (double u = 0.3; u < pw; u += 37.1) {
					for (double v = 0.7; v < ph; v += 23.3) {
						double here = t.shade(u, v, variant);
						assertEquals(here, t.shade(u + pw, v, variant), 1e-6, t + " across at " + u + "," + v);
						assertEquals(here, t.shade(u, v + ph, variant), 1e-6, t + " down at " + u + "," + v);
					}
				}
			}
		}
	}

	/** The two seats grow theirs from different seeds, so neither field is a copy of the other. */
	@Test
	void theTwoSeatsGetDifferentSurfaces() {
		for (BoardTexture t : BoardTexture.values()) {
			if (t == BoardTexture.NONE) continue;
			int differ = 0, points = 0;
			for (double u = 0.3; u < t.periodW(); u += 37.1) {
				for (double v = 0.7; v < t.periodH(); v += 23.3) {
					points++;
					if (Math.abs(t.shade(u, v, 0) - t.shade(u, v, 1)) > 1e-3) differ++;
				}
			}
			assertTrue(differ > points / 2, t + " differs between seats at only " + differ + " of " + points);
		}
	}

	/** However many knots its seed deals, a field shows no more than its share. */
	@Test
	void noFieldHasMoreThanItsShareOfKnots() {
		for (int variant = 0; variant < 2; variant++) {
			int knots = 0;
			for (BoardTexture.Board[] row : BoardTexture.layPlanks(1000 * variant).rows())
				for (BoardTexture.Board b : row) knots += b.knots().size();
			assertTrue(knots <= BoardTexture.MAX_KNOTS, "seat variant " + variant + " has " + knots + " knots");
		}
	}

	@Test
	void noneLeavesTheColourAsItIs() {
		BufferedImage img = filled(Color.ORANGE);
		Graphics2D g = img.createGraphics();
		BoardTexture.NONE.paint(g, img.getWidth(), img.getHeight(), 0);
		g.dispose();
		for (int y = 0; y < img.getHeight(); y++)
			for (int x = 0; x < img.getWidth(); x++) assertEquals(Color.ORANGE.getRGB(), img.getRGB(x, y));
	}

	@Test
	void aTextureMarksTheColour() {
		for (BoardTexture t : BoardTexture.values()) {
			if (t == BoardTexture.NONE) continue;
			BufferedImage img = filled(Color.ORANGE);
			Graphics2D g = img.createGraphics();
			t.paint(g, img.getWidth(), img.getHeight(), 0);
			g.dispose();
			int changed = 0;
			for (int y = 0; y < img.getHeight(); y++)
				for (int x = 0; x < img.getWidth(); x++)
					if (img.getRGB(x, y) != Color.ORANGE.getRGB()) changed++;
			assertNotEquals(0, changed, t + " left the colour untouched");
		}
	}

	private static BufferedImage filled(Color c) {
		BufferedImage img = new BufferedImage(64, 48, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setColor(c);
		g.fillRect(0, 0, img.getWidth(), img.getHeight());
		g.dispose();
		return img;
	}
}
