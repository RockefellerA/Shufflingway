package shufflingway;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

import shufflingway.ComputerPlayer.Fighter;

/** {@link CombatMath}: the worst a block on a party can do, and whether a turn's attacks force lethal. */
class CombatMathTest {

	private static Fighter f(int power) { return new Fighter(power, 0, false, false); }
	private static Fighter firstStrike(int power) { return new Fighter(power, 0, true, false); }

	private static boolean[][] everyoneBlocks(int attackers, int blockers) {
		boolean[][] can = new boolean[attackers][blockers];
		for (boolean[] row : can) java.util.Arrays.fill(row, true);
		return can;
	}

	// ---- Party blocks ---------------------------------------------------------------------

	@Test
	void aPartyThatTradesOneMemberForItsBlockerIsNoLoss() {
		// 10000 breaks the 8000; its 8000 breaks one 5000, not both. 2 for 4.
		assertNull(CombatMath.partyLoss(List.of(f(5000), f(5000)), new int[] { 2, 2 }, f(8000), 4));
	}

	@Test
	void aBlockerThePartyCannotBreakBreaksItsMembers() {
		CombatMath.PartyLoss loss = CombatMath.partyLoss(List.of(f(3000), f(3000)), new int[] { 2, 2 }, f(8000), 4);
		assertEquals(List.of(0, 1), loss.broken());
		assertTrue(loss.blockerSurvives());
	}

	@Test
	void aTradeThatCostsThePartyTheDearerCardIsALoss() {
		CombatMath.PartyLoss loss = CombatMath.partyLoss(List.of(f(5000), f(5000)), new int[] { 5, 5 }, f(8000), 2);
		assertEquals(1, loss.broken().size());
		assertFalse(loss.blockerSurvives());
	}

	@Test
	void aFirstStrikeBlockerBreaksAMemberBeforeTheRestCanBreakIt() {
		// The 8000 strikes first, breaks one 5000, and the 5000 left cannot break it.
		CombatMath.PartyLoss loss = CombatMath.partyLoss(List.of(f(5000), f(5000)), new int[] { 2, 2 }, firstStrike(8000), 4);
		assertTrue(loss.blockerSurvives());
	}

	@Test
	void aPartyWithFirstStrikeThroughoutTakesNothingFromABlockerItBreaks() {
		assertNull(CombatMath.partyLoss(List.of(firstStrike(5000), firstStrike(5000)), new int[] { 5, 5 }, f(8000), 2));
	}

	@Test
	void aMemberThatCannotBeBrokenCannotBeLost() {
		Fighter wall = new Fighter(3000, 0, false, true);
		assertNull(CombatMath.partyLoss(List.of(wall, f(6000)), new int[] { 2, 2 }, f(5000), 3),
				"the 6000 is past the 5000's reach, and the Wall cannot be broken");
	}

	// ---- Lethal ---------------------------------------------------------------------------

	@Test
	void threeAttackersWearDownTheOneBlockerThatBeatsEachOfThem() {
		// One hit from losing, P1 must block all three: 5000 leaves the 8000 on 3000, the second breaks it.
		assertTrue(CombatMath.lethalOpener(List.of(f(5000), f(5000), f(5000)), List.of(f(8000)), everyoneBlocks(3, 1), 1) >= 0);
	}

	@Test
	void twoAttackersAgainstThatBlockerAreNotEnough() {
		assertEquals(-1, CombatMath.lethalOpener(List.of(f(5000), f(5000)), List.of(f(8000)), everyoneBlocks(2, 1), 1));
	}

	@Test
	void aHitToSpareIsTakenRatherThanAFightLost() {
		// Two hits short, P1 takes the first and blocks the second.
		assertEquals(-1, CombatMath.lethalOpener(List.of(f(5000), f(5000)), List.of(f(8000)), everyoneBlocks(2, 1), 2));
	}

	@Test
	void withNothingToBlockEveryAttackLands() {
		assertEquals(0, CombatMath.lethalOpener(List.of(f(5000), f(5000)), List.of(), new boolean[2][0], 2));
		assertEquals(-1, CombatMath.lethalOpener(List.of(f(5000)), List.of(), new boolean[1][0], 2), "one attack, two hits short");
	}

	@Test
	void anAttackerItsBlockerMayNotBlockGetsThrough() {
		boolean[][] can = { { false } };
		assertEquals(0, CombatMath.lethalOpener(List.of(f(1000)), List.of(f(9000)), can, 1));
	}

	@Test
	void chipDamageFromAttackersOfAnySizeAddsUp() {
		// 3000 and 6000 together break the 8000 however they are blocked, and the 1000 then lands.
		assertTrue(CombatMath.lethalOpener(List.of(f(3000), f(6000), f(1000)), List.of(f(8000)), everyoneBlocks(3, 1), 1) >= 0);
		assertEquals(-1, CombatMath.lethalOpener(List.of(f(3000), f(4000), f(1000)), List.of(f(8000)), everyoneBlocks(3, 1), 1),
				"7000 between them leaves it standing for the 1000");
	}

	@Test
	void damageCarriedFromAnEarlierBlockCounts() {
		Fighter afterOne = CombatMath.afterBlocking(f(5000), f(8000));
		assertEquals(5000, afterOne.damage());
		assertNull(CombatMath.afterBlocking(f(5000), afterOne), "5000 more breaks it");
		assertEquals(0, CombatMath.afterBlocking(f(5000), firstStrike(8000)).damage(), "broken first, it deals nothing");
	}
}
