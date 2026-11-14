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
	void anythingElseIsNoRemoval() {
		assertNull(Removal.of("Choose 1 Forward you control. Break it."), "its own Forward");
		assertNull(Removal.of("Choose 1 Forward. Deal it 8000 damage. All the Fire Forwards you control gain +2000 power "
				+ "until the end of the turn."), "a sentence this does not read");
		assertNull(Removal.of("Choose 1 Forward. It gains +2000 power until the end of the turn."), "a trick");
		assertNull(Removal.of(null));
	}
}
