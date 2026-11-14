package shufflingway;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import shufflingway.ComputerPlayer.Fighter;

/**
 * {@link CombatTrick}: which wordings read as a trick the CPU may fight a battle with on P1's turn,
 * and {@link ComputerPlayer#battle}, the outcome it weighs one by.
 */
class CombatTrickTest {

	@Test
	void powerAndFirstStrikeAreReadInEitherOrderOfClauses() {
		CombatTrick plain = CombatTrick.of("Choose 1 Forward. It gains +2000 power until the end of the turn.");
		assertEquals(new CombatTrick("Choose 1 Forward.", 2000, false, false, false), plain);
		// 2-019R Belias: Haste rides along; the draw after it costs nothing.
		CombatTrick belias = CombatTrick.of("[[ex]]EX BURST[[/]] Choose 1 Forward. Until the end of the turn, it gains "
				+ "+1000 power, Haste and First Strike. Draw 1 card.");
		assertEquals(1000, belias.power());
		assertTrue(belias.firstStrike());
		// 19-073C Kain's arrival.
		CombatTrick kain = CombatTrick.of("choose 1 Forward. It gains First Strike until the end of the turn.");
		assertTrue(kain.firstStrike());
		assertEquals(0, kain.power());
	}

	@Test
	void shieldsAreReadAndATrickThatDullsItsForwardSaysSo() {
		CombatTrick siren = CombatTrick.of("[[ex]]EX BURST[[/]] Choose 1 Forward. During this turn, the next damage dealt "
				+ "to it becomes 0 instead. Draw 1 card.");
		assertTrue(siren.shield());
		assertFalse(siren.dulls());
		CombatTrick titan = CombatTrick.of("Choose 1 Forward you control. Dull it. It cannot be broken this turn.");
		assertTrue(titan.shield());
		assertTrue(titan.dulls(), "no use to a Forward that has yet to block");
	}

	@Test
	void aBoostToEveryForwardOfItsOwnHasNoChoice() {
		CombatTrick carbuncle = CombatTrick.of("[[ex]]EX BURST[[/]] All the Forwards you control gain +2000 power until "
				+ "the end of the turn. If all the Backups you control have Earth Element, draw 1 card.");
		assertNull(carbuncle.subject());
		assertEquals(2000, carbuncle.power());
	}

	@Test
	void anythingNotWhollyUnderstoodIsNoTrick() {
		assertNull(CombatTrick.of("Choose 1 Forward. It gains +2000 power until the end of the turn. If it is blocking, "
				+ "it gains +4000 power until the end of the turn instead."), "1-106C Golem: a sentence this does not read");
		assertNull(CombatTrick.of("choose 1 Forward other than Mid you control. Until the end of the turn, it gains "
				+ "+1000 power and \"This Forward cannot be blocked.\""), "13-050R Mid: a quoted grant");
		assertNull(CombatTrick.of("Choose 1 Forward opponent controls. It gains +2000 power until the end of the turn."),
				"aimed at the opponent's Forward");
		assertNull(CombatTrick.of("choose 1 Forward. During this turn, the next damage dealt to it by your opponent's "
				+ "Summons or abilities becomes 0 instead."), "22-001R Auron: no shield against battle damage");
		assertNull(CombatTrick.of("Choose 1 Forward. Deal it 7000 damage."), "removal, not a trick");
		assertNull(CombatTrick.of(null));
	}

	private static Fighter f(int power, int damage, boolean firstStrike, boolean shielded) {
		return new Fighter(power, damage, firstStrike, shielded);
	}

	@Test
	void aBattleIsWeighedByPowerFirstStrikeAndShields() {
		assertArrayEquals(new boolean[] { false, false }, ComputerPlayer.battle(f(7000, 0, false, false), f(7000, 0, false, false)),
				"equal power: both break");
		assertArrayEquals(new boolean[] { true, false }, ComputerPlayer.battle(f(9000, 0, false, false), f(7000, 0, false, false)));
		assertArrayEquals(new boolean[] { true, false }, ComputerPlayer.battle(f(7000, 0, true, false), f(7000, 0, false, false)),
				"First Strike breaks it before it strikes back");
		assertArrayEquals(new boolean[] { false, true }, ComputerPlayer.battle(f(5000, 0, true, false), f(7000, 0, false, false)),
				"too weak to break it first: it strikes back");
		assertArrayEquals(new boolean[] { true, true }, ComputerPlayer.battle(f(5000, 0, false, true), f(7000, 0, false, false)),
				"a shield keeps it whole, and 5000 does not break 7000");
		assertArrayEquals(new boolean[] { true, false }, ComputerPlayer.battle(f(9000, 0, false, false), f(7000, 2000, true, false)),
				"their First Strike does not break it, so its 9000 lands");
		assertArrayEquals(new boolean[] { false, true }, ComputerPlayer.battle(f(7000, 0, false, false), f(7000, 0, true, false)),
				"equal power, but theirs strikes first");
	}
}
