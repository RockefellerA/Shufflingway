package shufflingway.graphics;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

/**
 * A card's counters are drawn as the face of a die: one orb per named counter, each carrying its
 * count. Each face is written here as its 3 × 3 grid, top row first, {@code o} for a pip: the
 * reference layout the faces were built from.
 */
class CounterDiceTest {

	private static final String[] REFERENCE = {
		"... ... ...",   // 0
		"... .o. ...",   // 1
		"..o ... o..",   // 2
		"..o .o. o..",   // 3
		"o.o ... o.o",   // 4
		"o.o .o. o.o",   // 5
		"o.o o.o o.o",   // 6
		"o.o ooo o.o",   // 7
		"ooo o.o ooo",   // 8
		"ooo ooo ooo",   // 9
	};

	@Test
	void eachNumberOfNamesShowsItsDieFace() {
		for (int names = 0; names <= 9; names++)
			assertEquals(cells(REFERENCE[names]), cells(CounterDice.pips(names)), "face for " + names);
	}

	@Test
	void tenOrMoreNamesShowAllNinePips() {
		for (int names : new int[] { CounterDice.OVERFLOW, 12, 99 })
			assertEquals(cells(REFERENCE[9]), cells(CounterDice.pips(names)), "face for " + names);
	}

	@Test
	void theTwoAndFourFacesSitAsCloseAsAFivesCornerToItsCentre() {
		double fiveDiagonal = distance(CounterDice.offsets(5)[0], CounterDice.offsets(5)[2]);   // top left to centre
		double[][] two = CounterDice.offsets(2), four = CounterDice.offsets(4);
		assertEquals(fiveDiagonal, distance(two[0], two[1]), 1e-9, "the two");
		assertEquals(fiveDiagonal, distance(four[0], four[3]), 1e-9, "the four, top left to bottom right");
		assertEquals(fiveDiagonal, distance(four[1], four[2]), 1e-9, "the four, top right to bottom left");
	}

	@Test
	void everyOtherFaceKeepsItsPipsAtTheCorners() {
		for (int names : new int[] { 3, 5, 6, 7, 8, 9 })
			for (double[] pip : CounterDice.offsets(names))
				for (double v : pip)
					assertEquals(0.0, Math.abs(v) % 1.0, 1e-9, "face for " + names + " stays on the grid");
	}

	private static double distance(double[] a, double[] b) {
		return Math.hypot(a[0] - b[0], a[1] - b[1]);
	}

	@Test
	void eachOrbCarriesItsNamesCountInPlacementOrder() {
		// Poison ×2 then Shuriken ×3: the two-pip face, top right first.
		assertEquals(List.of("2", "3"), CounterDice.labels(List.of(2, 3)));
		assertEquals(List.of(2, 0), List.of(CounterDice.pips(2)[0][0], CounterDice.pips(2)[0][1]));
	}

	@Test
	void manyCountersOfOneNameAreStillOneOrb() {
		assertEquals(List.of("12"), CounterDice.labels(List.of(12)));
	}

	@Test
	void tenOrMoreNamesMarkTheCentreOrbAndShowTheFirstEightAroundIt() {
		List<Integer> tenNames = List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
		assertEquals(List.of("1", "2", "3", "4", CounterDice.MORE, "5", "6", "7", "8"),
				CounterDice.labels(tenNames));
		int[] centre = CounterDice.pips(tenNames.size())[4];
		assertEquals(List.of(1, 1), List.of(centre[0], centre[1]), "the \"...\" lands on the centre pip");
	}

	/** The pips of a face as "col,row" strings. */
	private static Set<String> cells(int[][] pips) {
		Set<String> out = new TreeSet<>();
		for (int[] p : pips) out.add(p[0] + "," + p[1]);
		return out;
	}

	private static Set<String> cells(String face) {
		String[] rows = face.split(" ");
		Set<String> out = new TreeSet<>();
		for (int r = 0; r < 3; r++)
			for (int c = 0; c < 3; c++)
				if (rows[r].charAt(c) == 'o') out.add(c + "," + r);
		return out;
	}
}
