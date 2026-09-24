package shufflingway;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Tests for the Main Phase auto-advance: the timing rule in {@link MainPhaseAutoAdvance}, and
 * {@code MainWindow.p1HasAnyPlay()}, the verdict that decides whether P1 has anything left
 * to do. The Swing timer that joins the two is not driven here; it only feeds one to the other.
 */
public class MainPhaseAutoAdvanceTest {

    // ── Timing ───────────────────────────────────────────────────────────

    @Test
    void waitsOutTheDelayBeforeAdvancing() {
        MainPhaseAutoAdvance a = new MainPhaseAutoAdvance(900);
        assertFalse(a.poll(true, 1_000), "the first ready poll only starts the clock");
        assertFalse(a.poll(true, 1_500));
        assertTrue(a.poll(true, 1_900), "ready for the whole delay: advance");
    }

    @Test
    void aSingleNotReadyPollRestartsTheClock() {
        MainPhaseAutoAdvance a = new MainPhaseAutoAdvance(900);
        a.poll(true, 1_000);
        a.poll(false, 1_800);   // e.g. the player opened a card's menu
        assertFalse(a.poll(true, 1_900), "the run was broken, so the delay starts over");
        assertFalse(a.poll(true, 2_700));
        assertTrue(a.poll(true, 2_800));
    }

    @Test
    void eachAdvanceNeedsAFreshRun() {
        MainPhaseAutoAdvance a = new MainPhaseAutoAdvance(900);
        a.poll(true, 0);
        assertTrue(a.poll(true, 900));
        assertFalse(a.poll(true, 950),
                "a later phase that is also idle waits its own delay rather than advancing at once");
        assertTrue(a.poll(true, 1_850));
    }

    @Test
    void resetDropsARunInProgress() {
        MainPhaseAutoAdvance a = new MainPhaseAutoAdvance(900);
        a.poll(true, 0);
        a.reset();
        assertFalse(a.poll(true, 900));
    }

    // ── What counts as a play ────────────────────────────────────────────

    private static CardData forward(String name, String element, int cost, String text) {
        return new CardData(null, name, element, cost, 5000, "Forward", false, 0, false, false,
                Set.of(), 0, List.of(), "", List.of(),
                CardData.parseActionAbilities(text), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(),
                false, false, null, false, false, false, false, false, 1,
                null, null, null, text);
    }

    private static CardData backup(String name, String element, int cost, String text) {
        return new CardData(null, name, element, cost, 0, "Backup", false, 0, false, false,
                Set.of(), 0, List.of(), "", List.of(),
                CardData.parseActionAbilities(text), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(),
                false, false, null, false, false, false, false, false, 1,
                null, null, null, text);
    }

    private static MainWindow inP1Main1() {
        MainWindow mw = new MainWindow();
        mw.gameState.startFirstTurn(GameState.Player.P1);
        while (mw.gameState.getCurrentPhase() != GameState.GamePhase.MAIN_1) mw.gameState.advancePhase();
        mw.refreshPhaseTracker();
        return mw;
    }

    @Test
    void anEmptyBoardAndHandHasNoPlay() {
        assertFalse(inP1Main1().p1HasAnyPlay());
    }

    @Test
    void anAffordableCardInHandIsAPlay() {
        MainWindow mw = inP1Main1();
        // Discarding either card makes 2 Fire CP, enough to cast the other.
        mw.gameState.getP1Hand().add(forward("Alpha", "Fire", 2, ""));
        mw.gameState.getP1Hand().add(forward("Beta", "Fire", 2, ""));
        assertTrue(mw.p1HasAnyPlay());
    }

    @Test
    void aCardThatCannotBePaidForIsNotAPlay() {
        MainWindow mw = inP1Main1();
        // The only card in hand cannot pay for itself, and there is no Backup to dull.
        mw.gameState.getP1Hand().add(forward("Alpha", "Fire", 3, ""));
        assertFalse(mw.p1HasAnyPlay());
    }

    @Test
    void aUsableFieldAbilityIsAPlayAndAnUnusableOneIsNot() {
        MainWindow mw = inP1Main1();
        mw.placeCardInForwardZone(forward("Drawer", "Water", 2, "《Dull》: Draw 1 card."));
        mw.p1ForwardPlayedOnTurn.set(0, 0);   // on the field since before this turn
        assertTrue(mw.p1HasAnyPlay(), "an active Forward with a 《Dull》 ability can use it");

        mw.p1ForwardStates.set(0, CardState.DULL);
        assertFalse(mw.p1HasAnyPlay(), "dulled, it can no longer pay the 《Dull》 cost");
    }

    // ── Priority windows on the opponent's turn ─────────────────────────
    //
    // These used to pass only when P1 had no action ability on the field at all, so a Backup
    // whose one ability was a Special stopped every window even with no same-named card in hand
    // to pay the 《S》. They now ask p1HasAnyPlay, the menus' own answer.

    private static final String SPECIAL_BACKUP_TEXT = "《S》《Dull》: Draw 1 card.";

    private static MainWindow inP2Phase(GameState.GamePhase phase) {
        MainWindow mw = new MainWindow();
        mw.gameState.startFirstTurn(GameState.Player.P2);
        while (mw.gameState.getCurrentPhase() != phase) mw.gameState.advancePhase();
        mw.refreshPhaseTracker();
        mw.p1BackupCards[0] = backup("Scholar", "Water", 2, SPECIAL_BACKUP_TEXT);
        mw.p1BackupStates[0] = CardState.ACTIVE;
        return mw;
    }

    @Test
    void anOpponentsMainPhaseWindowPassesWhenTheOnlyAbilityIsAnUnpayableSpecial() {
        boolean saved = AppSettings.isAutoAdvanceMainPhases();
        AppSettings.setAutoAdvanceMainPhases(true);
        try {
            MainWindow mw = inP2Phase(GameState.GamePhase.MAIN_1);
            assertTrue(mw.p1BackupCards[0].actionAbilities().get(0).isSpecial(), "fixture must parse as a Special");
            boolean[] passed = { false };
            mw.offerP1MainPhasePriority(() -> passed[0] = true);
            assertTrue(passed[0], "no same-named card to discard for 《S》: nothing to wait for");
            assertNull(mw.p1PriorityInP2MainOnDone, "a window that passed itself is not left open");
        } finally {
            AppSettings.setAutoAdvanceMainPhases(saved);
        }
    }

    @Test
    void anOpponentsMainPhaseWindowWaitsWhenTheSpecialCanBePaid() {
        boolean saved = AppSettings.isAutoAdvanceMainPhases();
        AppSettings.setAutoAdvanceMainPhases(true);
        try {
            MainWindow mw = inP2Phase(GameState.GamePhase.MAIN_1);
            mw.gameState.getP1Hand().add(backup("Scholar", "Water", 2, SPECIAL_BACKUP_TEXT));
            boolean[] passed = { false };
            mw.offerP1MainPhasePriority(() -> passed[0] = true);
            assertFalse(passed[0], "the same-named card in hand pays the 《S》, so P1 may use it");
            assertNotNull(mw.p1PriorityInP2MainOnDone);
        } finally {
            AppSettings.setAutoAdvanceMainPhases(saved);
        }
    }

    @Test
    void anOpponentsAttackPreparationPassesWhenTheOnlyAbilityIsAnUnpayableSpecial() {
        MainWindow mw = inP2Phase(GameState.GamePhase.ATTACK);
        boolean[] passed = { false };
        mw.offerP1AttackPrepPriority(() -> passed[0] = true);
        assertTrue(passed[0]);
    }

    @Test
    void anOpponentsAttackPreparationWaitsWhenTheSpecialCanBePaid() {
        MainWindow mw = inP2Phase(GameState.GamePhase.ATTACK);
        mw.gameState.getP1Hand().add(backup("Scholar", "Water", 2, SPECIAL_BACKUP_TEXT));
        boolean[] passed = { false };
        mw.offerP1AttackPrepPriority(() -> passed[0] = true);
        assertFalse(passed[0]);
    }
}
