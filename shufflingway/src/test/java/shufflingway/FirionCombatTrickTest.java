package shufflingway;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Behavioral tests for the CPU's handling of Firion's "Discard 1 card: If the discarded card
 * is of Fire Element, ... +2000 power and First Strike. If ... Water Element, ... +2000 power
 * and activate Firion." ability:
 * <ul>
 *   <li>The ability should never be spent when hand has no Fire/Water card to discard.</li>
 *   <li>It should be used to turn a losing block into a won one (Fire: power + First Strike).</li>
 *   <li>It should be used to activate a dull Firion so it can block at all (Water).</li>
 * </ul>
 * Exercised against a real (constructed via the actual DB text) Firion CardData and a real
 * {@link MainWindow}/{@link ComputerPlayer} pair, since the blocking AI reads MainWindow's
 * field-state arrays directly rather than through an interface.
 */
public class FirionCombatTrickTest {

    private static final String FIRION_TEXT =
            "If you control 5 or more Characters, Firion gains Haste and \"When Firion attacks, draw 1 card.\"[[br]]   "
            + "Discard 1 card: If the discarded card is of Fire Element, until the end of the turn, Firion gains +2000 power and First Strike. "
            + "If the discarded card is of Water Element, Firion gains +2000 power until the end of the turn and activate Firion.";

    private static CardData makeForward(String name, String element, int cost, int power) {
        return makeForward(name, element, cost, power, List.of());
    }

    private static CardData makeForward(String name, String element, int cost, int power, List<ActionAbility> abilities) {
        return new CardData(null, name, element, cost, power, "Forward", false, 0, false, false,
                Set.of(), 0, List.of(), null, List.of(),
                abilities, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                false, false, null, false, false, false, false, false, false,
                null, null, null, "");
    }

    private static CardData makeFirion(int power) {
        return makeForward("Firion", "Fire", 2, power, CardData.parseActionAbilities(FIRION_TEXT));
    }

    /** Builds a real MainWindow (no window shown) with Firion on P2's field and a P1 attacker declared. */
    private static MainWindow setUp(CardState firionState, int firionPower, int attackerPower,
            List<CardData> p2HandCards) {
        MainWindow mw = new MainWindow();
        mw.placeCardInForwardZone(makeForward("Attacker", "Water", 3, attackerPower)); // P1 idx 0
        mw.placeP2CardInForwardZone(makeFirion(firionPower));                          // P2 idx 0
        mw.p2ForwardStates.set(0, firionState);
        mw.gameState.getP2Hand().addAll(p2HandCards);
        return mw;
    }

    @Test
    void discardConditionalElementBranchesParsesFirionCorrectly() {
        List<ActionAbility> abilities = CardData.parseActionAbilities(FIRION_TEXT);
        assertEquals(1, abilities.size());
        List<ActionResolver.DiscardElementBranch> branches =
                ActionResolver.discardConditionalElementBranches(abilities.get(0).effectText());
        assertNotNull(branches);
        assertEquals(2, branches.size());
        assertEquals("Fire", branches.get(0).element());
        assertTrue(branches.get(0).effectText().toLowerCase().contains("first strike"));
        assertEquals("Water", branches.get(1).element());
        assertTrue(branches.get(1).effectText().toLowerCase().contains("activate"));
    }

    @Test
    void declinesToBlockAndDoesNotDiscardWhenHandHasNoMatchingElement() {
        // Firion (4000) can't survive a 6000-power attacker unblocked-boosted; hand has only an
        // Earth card, so neither branch of the ability can do anything — must not be used.
        MainWindow mw = setUp(CardState.ACTIVE, 4000, 6000,
                List.of(makeForward("Filler", "Earth", 1, 1000)));
        int handSizeBefore = mw.gameState.getP2Hand().size();

        ForwardTarget blk = new ComputerPlayer(mw).chooseBlocker(6000,
                new ForwardTarget(true, 0, ForwardTarget.CardZone.FORWARD));

        assertNull(blk, "should decline to block rather than lose Firion for nothing");
        assertEquals(handSizeBefore, mw.gameState.getP2Hand().size(), "must not waste a card with no benefit");
    }

    @Test
    void usesFireBranchToWinAnOtherwiseLosingBlock() {
        // Firion (4000) alone can't survive/break a 5000-power attacker, but +2000 power and
        // First Strike (6000) does. Hand has the needed Fire card plus an unrelated one.
        CardData fireCard = makeForward("Fire Fodder", "Fire", 1, 1000);
        MainWindow mw = setUp(CardState.ACTIVE, 4000, 5000,
                List.of(makeForward("Filler", "Earth", 1, 1000), fireCard));

        ForwardTarget blk = new ComputerPlayer(mw).chooseBlocker(5000,
                new ForwardTarget(true, 0, ForwardTarget.CardZone.FORWARD));

        assertNotNull(blk, "should use the Fire branch to turn this into a winning block");
        assertEquals(0, blk.idx());
        assertEquals(ForwardTarget.CardZone.FORWARD, blk.zone());
        assertFalse(mw.gameState.getP2Hand().contains(fireCard), "the Fire card should have been discarded");
        assertEquals(6000, mw.effectiveP2ForwardPower(0), "Firion should now be boosted to 6000 power");
        assertTrue(mw.p2ForwardTempTraits.get(0).contains(CardData.Trait.FIRST_STRIKE), "Firion should have gained First Strike");
    }

    @Test
    void usesWaterBranchToActivateADullFirionSoItCanBlock() {
        // Dull Firion is normally not even a candidate; the Water branch both boosts power and
        // activates it, so it should become the chosen blocker against a 5000-power attacker.
        CardData waterCard = makeForward("Water Fodder", "Water", 1, 1000);
        MainWindow mw = setUp(CardState.DULL, 4000, 5000,
                List.of(makeForward("Filler", "Earth", 1, 1000), waterCard));

        ForwardTarget blk = new ComputerPlayer(mw).chooseBlocker(5000,
                new ForwardTarget(true, 0, ForwardTarget.CardZone.FORWARD));

        assertNotNull(blk, "should activate dull Firion via the Water branch so it can block");
        assertEquals(0, blk.idx());
        assertFalse(mw.gameState.getP2Hand().contains(waterCard), "the Water card should have been discarded");
        assertEquals(CardState.ACTIVE, mw.p2ForwardStates.get(0), "Firion should now be active");
        assertEquals(6000, mw.effectiveP2ForwardPower(0), "Firion should now be boosted to 6000 power");
    }

    @Test
    void doesNotUseTrickWhenAnAdequateBlockerAlreadyExists() {
        // Firion (7000, active) already survives/breaks a 5000-power attacker unaided — the
        // ability must not be spent for no reason.
        CardData fireCard = makeForward("Fire Fodder", "Fire", 1, 1000);
        MainWindow mw = setUp(CardState.ACTIVE, 7000, 5000,
                List.of(fireCard));
        int handSizeBefore = mw.gameState.getP2Hand().size();

        ForwardTarget blk = new ComputerPlayer(mw).chooseBlocker(5000,
                new ForwardTarget(true, 0, ForwardTarget.CardZone.FORWARD));

        assertNotNull(blk);
        assertEquals(0, blk.idx());
        assertEquals(handSizeBefore, mw.gameState.getP2Hand().size(), "must not spend the card when already winning");
    }
}
