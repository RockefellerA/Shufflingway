package shufflingway;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** {@link Removal}: which wordings read as a removal the CPU may break one of P1's Forwards with on P1's turn. */
class RemovalTest {

	@Test
	void damageBreaksAndPowerLossAreRead() {
		assertEquals(new Removal(Removal.Kind.DAMAGE, 7000), Removal.of("Choose 1 Forward. Deal it 7000 damage."));
		assertEquals(new Removal(Removal.Kind.DAMAGE, 8000),
				Removal.of("[[ex]]EX BURST[[/]] Choose 1 Forward opponent controls. Deal it 8000 damage. Draw 1 card."));
		assertEquals(new Removal(Removal.Kind.BREAK, 0), Removal.of("Choose 1 dull Forward of cost 4 or less. Break it."));
		assertEquals(new Removal(Removal.Kind.POWER_LOSS, 5000),
				Removal.of("Choose 1 Forward. It loses 5000 power until the end of the turn."));
		assertEquals(new Removal(Removal.Kind.POWER_LOSS, 4000),
				Removal.of("Choose 1 Forward. Until the end of the turn, it loses 4000 power."));
	}

	@Test
	void aForwardKeptFromBlockingIsRead() {
		// 2-008C, 2-011L, and the dulls of the attack triggers.
		assertTrue(Removal.deniesBlock("choose 1 Forward of cost 3 or less opponent controls. It cannot block this turn."));
		assertTrue(Removal.deniesBlock("choose 1 Forward. It cannot block this turn."));
		assertTrue(Removal.deniesBlock("Choose 1 Forward opponent controls. Dull it."));
		assertTrue(Removal.deniesBlock("Choose 1 Forward opponent controls. Dull it and Freeze it."));
		assertFalse(Removal.deniesBlock("Choose 1 Forward opponent controls. Freeze it."), "an active Forward Frozen still blocks");
		assertFalse(Removal.deniesBlock("Choose 1 Forward you control. Dull it."), "its own Forward");
		assertFalse(Removal.deniesBlock("Choose 1 Forward opponent controls. Dull it. If you have cast Card Name Shiva this "
				+ "turn, Freeze it also."), "a sentence this does not read");
	}

	@Test
	void damageToTheBlockingForwardIsRead() {
		// 2-013C, 15-096C Noel, 6-088L Estinien.
		assertEquals(new Removal(Removal.Kind.DAMAGE, 2000), Removal.ofBlocker("deal the blocking Forward 2000 damage."));
		assertEquals(new Removal(Removal.Kind.DAMAGE, 6000), Removal.ofBlocker("deal 6000 damage to the Forward that blocks Noel."));
		assertEquals(new Removal(Removal.Kind.BREAK, 0), Removal.ofBlocker("break the blocking Forward."));
		assertNull(Removal.ofBlocker("deal 4000 damage for each Shuriken Counter placed on Yuffie to the blocking Forward. "
				+ "Then, remove all Shuriken Counters from Yuffie."));
	}

	@Test
	void anythingElseIsNoRemoval() {
		assertNull(Removal.of("Choose 1 Forward you control. Break it."), "its own Forward");
		assertNull(Removal.of("Choose 1 Forward. Deal it 8000 damage. All the Fire Forwards you control gain +2000 power "
				+ "until the end of the turn."), "a sentence this does not read");
		assertNull(Removal.of("Choose 1 Forward. It gains +2000 power until the end of the turn."), "a trick");
		assertNull(Removal.of(null));
	}
}
