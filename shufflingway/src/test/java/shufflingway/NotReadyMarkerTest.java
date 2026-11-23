package shufflingway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import shufflingway.graphics.CardAnimation;
import shufflingway.graphics.FieldCardIcon;
import shufflingway.graphics.NotReadyGaugeOverlay;
import shufflingway.graphics.NotReadyMarker;
import shufflingway.graphics.TraitTab;

/**
 * Covers how the board marks a character that cannot attack or use 《Dull》 abilities yet: the
 * engine's window for that and which Forwards, Backups and Monsters the board marks; marking none, the
 * default; the Preparing trait tab; and the ATB gauge — how far it has filled, where the overlay draws
 * it and how it rises and clears, and the drawing itself.
 */
class NotReadyMarkerTest {

	/** The indicator in settings before each test, put back after it: tests choose the one they read. */
	private NotReadyIndicator savedIndicator;

	@BeforeEach
	void rememberIndicator() {
		savedIndicator = AppSettings.getNotReadyIndicator();
	}

	@AfterEach
	void restoreIndicator() {
		AppSettings.setNotReadyIndicator(savedIndicator);
	}

	private static final int H = CardAnimation.CARD_H;
	private static final int FULL = NotReadyMarker.PHASES;

	private static final String DULL_ABILITY = "《Dull》: Choose 1 Forward. Deal it 1000 damage.";

	// ── Fixtures ─────────────────────────────────────────────────────────

	/** A character of {@code type} with a 《Dull》 ability, and no art, so nothing is loaded for it. */
	private static CardData card(String name, String type) {
		return new CardData(null, name, "Water", 2, 5000, type, false, 0, false, false,
				Set.of(), 0, List.of(), "", List.of(),
				CardData.parseActionAbilities(DULL_ABILITY), List.of(), List.of(), List.of(), List.of(), List.of(),
				List.of(), List.of(), List.of(), List.of(),
				false, false, null, false, false, false, false, false, 1,
				null, null, null, DULL_ABILITY);
	}

	private static CardData forward(String name) { return card(name, "Forward"); }
	private static CardData backup(String name)  { return card(name, "Backup"); }
	private static CardData monster(String name) { return card(name, "Monster"); }

	private static final ForwardTarget.CardZone BACKUP  = ForwardTarget.CardZone.BACKUP;
	private static final ForwardTarget.CardZone MONSTER = ForwardTarget.CardZone.MONSTER;

	private static MainWindow inP1Main1() {
		MainWindow mw = new MainWindow();
		mw.gameState.startFirstTurn(GameState.Player.P1);
		advanceTo(mw, GameState.GamePhase.MAIN_1);
		return mw;
	}

	private static void advanceTo(MainWindow mw, GameState.GamePhase phase) {
		while (mw.gameState.getCurrentPhase() != phase) mw.gameState.advancePhase();
	}

	/** Advances through the rest of this turn and into the next one's Active Phase. */
	private static void nextTurn(MainWindow mw) {
		int turn = mw.gameState.getTurnNumber();
		while (mw.gameState.getTurnNumber() == turn) mw.gameState.advancePhase();
	}

	/** Whether P1's Forward {@code card} could use its 《Dull》 ability now, as it stands on the field. */
	private static boolean canUseDullAbility(MainWindow mw, CardData card) {
		int idx = mw.p1ForwardCards.indexOf(card);
		return mw.canActivateAbility(card.actionAbilities().get(0), false, CardState.ACTIVE,
				mw.p1ForwardPlayedOnTurn.get(idx), card, true);
	}

	// ── The engine's window ──────────────────────────────────────────────

	// CR 11.6.2.2: a 《Dull》 cost waits until the Forward has been controlled since the start of its
	// controller's most recent turn — through the opponent's turn, lifting as its controller's next
	// turn begins. The attack restriction's window lies inside that one, so the gauge follows it.
	@Test
	void aForwardIsHeldBackFromItsEntryUntilItsControllersNextTurnBegins() {
		MainWindow mw = inP1Main1();
		CardData fwd = forward("Newcomer");
		mw.placeCardInForwardZone(fwd);
		assertNotNull(mw.forwardNotReadyPhases(true, 0), "entered this turn: not ready");
		assertFalse(canUseDullAbility(mw, fwd));

		nextTurn(mw);
		assertNotNull(mw.forwardNotReadyPhases(true, 0),
				"still not ready through the opponent's turn: its 《Dull》 cost cannot be paid there");
		assertFalse(canUseDullAbility(mw, fwd));

		nextTurn(mw);
		assertNull(mw.forwardNotReadyPhases(true, 0), "ready once its controller's next turn begins");
		assertTrue(canUseDullAbility(mw, fwd));
	}

	@Test
	void oneThatEntersOnTheOpponentsTurnIsReadyWhenItsControllersTurnBegins() {
		MainWindow mw = inP1Main1();
		nextTurn(mw);
		advanceTo(mw, GameState.GamePhase.MAIN_1);
		CardData fwd = forward("Ambusher");
		mw.placeCardInForwardZone(fwd);
		assertNotNull(mw.forwardNotReadyPhases(true, 0), "not controlled since its controller's turn began");
		assertFalse(canUseDullAbility(mw, fwd));

		nextTurn(mw);
		assertNull(mw.forwardNotReadyPhases(true, 0),
				"controlled since the beginning of this turn, which is its controller's most recent one");
		assertTrue(canUseDullAbility(mw, fwd));
	}

	@Test
	void anExtraTurnIsATurnLikeAnyOther() {
		MainWindow mw = inP1Main1();
		CardData fwd = forward("Hurried");
		mw.placeCardInForwardZone(fwd);
		advanceTo(mw, GameState.GamePhase.END);
		mw.gameState.advancePhaseExtraTurn();
		assertSame(GameState.Player.P1, mw.gameState.getCurrentPlayer());
		assertNull(mw.forwardNotReadyPhases(true, 0), "the extra turn is its controller's most recent turn");
		assertTrue(canUseDullAbility(mw, fwd));
	}

	@Test
	void anOpponentsForwardIsMarkedTheSameWay() {
		MainWindow mw = inP1Main1();
		mw.placeP2CardInForwardZone(forward("Theirs"));
		assertNotNull(mw.forwardNotReadyPhases(false, 0), "before the opponent's first turn");
		nextTurn(mw);
		assertNull(mw.forwardNotReadyPhases(false, 0), "ready as the opponent's own turn begins");
	}

	@Test
	void aForwardOfUnknownEntryIsNeverMarked() {
		MainWindow mw = inP1Main1();
		mw.placeCardInForwardZone(forward("Veteran"));
		mw.p1ForwardPlayedOnTurn.set(0, 0);
		assertNull(mw.forwardNotReadyPhases(true, 0));
	}

	// ── Haste ────────────────────────────────────────────────────────────

	@Test
	void hasteMeansNoGauge() {
		MainWindow mw = inP1Main1();
		mw.placeCardInForwardZone(forward("Quick"));
		mw.p1ForwardTempTraits.get(0).add(CardData.Trait.HASTE);
		assertNull(mw.forwardNotReadyPhases(true, 0));
	}

	// Haste only excuses the restriction while it is there: a Forward that entered this turn, was
	// given Haste and then lost it is held back again — and wears the gauge again — until Haste
	// comes back or its controller's next turn begins.
	@Test
	void aForwardThatLosesItsHasteIsHeldBackAndMarkedAgain() {
		MainWindow mw = inP1Main1();
		CardData fwd = forward("Borrowed Speed");
		mw.placeCardInForwardZone(fwd);
		advanceTo(mw, GameState.GamePhase.ATTACK);

		mw.p1ForwardTempTraits.get(0).add(CardData.Trait.HASTE);
		assertNull(mw.forwardNotReadyPhases(true, 0));
		assertTrue(mw.hasAttackableForward(), "Haste: it may attack");
		assertTrue(canUseDullAbility(mw, fwd), "and pay its 《Dull》 cost");

		mw.p1ForwardRemovedTraits.get(0).add(CardData.Trait.HASTE);
		assertEquals(2, mw.forwardNotReadyPhases(true, 0), "Haste lost: the gauge is back, as far along as the wait");
		assertFalse(mw.hasAttackableForward(), "it may not attack");
		assertFalse(canUseDullAbility(mw, fwd), "nor pay its 《Dull》 cost");

		mw.p1ForwardRemovedTraits.get(0).remove(CardData.Trait.HASTE);
		assertNull(mw.forwardNotReadyPhases(true, 0), "Haste back: ready again");
		assertTrue(mw.hasAttackableForward());

		mw.p1ForwardRemovedTraits.get(0).add(CardData.Trait.HASTE);
		nextTurn(mw);
		nextTurn(mw);
		assertNull(mw.forwardNotReadyPhases(true, 0), "without Haste, ready at its controller's next turn");
		assertTrue(canUseDullAbility(mw, fwd));
	}

	// ── The gauge's level ────────────────────────────────────────────────

	// An eighth for each phase from the start of the controller's turn: Main Phase 1, the Attack
	// Phase, Main Phase 2 and the End Phase of theirs, then the same four of the opponent's. The
	// Active and Draw Phases count with the turn before.
	@Test
	void theGaugeFillsAnEighthForEachPhaseGoneBy() {
		MainWindow mw = new MainWindow();
		mw.gameState.startFirstTurn(GameState.Player.P1);
		mw.placeCardInForwardZone(forward("Early"));
		int[] expected = { 0, 0, 1, 2, 3, 4, 4, 4, 5, 6, 7, 8 };   // P1's Active … P2's End
		for (int step = 0; step < expected.length; step++) {
			assertEquals(expected[step], mw.forwardNotReadyPhases(true, 0),
					"turn " + mw.gameState.getTurnNumber() + ", " + mw.gameState.getCurrentPhase());
			mw.gameState.advancePhase();
		}
		assertNull(mw.forwardNotReadyPhases(true, 0), "cleared as P1's next turn begins");
	}

	@Test
	void theGaugeShowsHowMuchOfTheWaitIsLeftWheneverTheForwardEntered() {
		MainWindow mw = inP1Main1();
		advanceTo(mw, GameState.GamePhase.MAIN_2);
		mw.placeCardInForwardZone(forward("Late"));
		assertEquals(3, mw.forwardNotReadyPhases(true, 0), "cast in Main Phase 2: three phases already gone");

		nextTurn(mw);
		advanceTo(mw, GameState.GamePhase.MAIN_1);
		mw.placeCardInForwardZone(forward("Ambusher"));
		assertEquals(5, mw.forwardNotReadyPhases(true, 1), "on the opponent's turn: past the halfway mark");
		assertEquals(1, mw.phasesSinceTurnBegan(false), "and the opponent's own count is their turn's");
	}

	@Test
	void beforeItsControllersFirstTurnTheOpponentsTurnIsTheSecondHalf() {
		MainWindow mw = inP1Main1();
		mw.placeP2CardInForwardZone(forward("Theirs"));
		assertEquals(5, mw.forwardNotReadyPhases(false, 0));
	}

	@Test
	void anOpponentsExtraTurnLeavesTheGaugeFull() {
		MainWindow mw = inP1Main1();
		mw.placeCardInForwardZone(forward("Waiting"));
		nextTurn(mw);
		advanceTo(mw, GameState.GamePhase.END);
		mw.gameState.advancePhaseExtraTurn();
		advanceTo(mw, GameState.GamePhase.MAIN_1);
		assertEquals(FULL, mw.forwardNotReadyPhases(true, 0), "full, and waiting on its controller's turn");
	}

	// ── The board keeps the gauges in step ───────────────────────────────

	@Test
	void theBoardShowsEachForwardsGaugeAsThePhasesGoBy() {
		AppSettings.setNotReadyIndicator(NotReadyIndicator.ATB);
		MainWindow mw = inP1Main1();
		CardData fwd = forward("Watched");
		mw.placeCardInForwardZone(fwd);
		assertEquals(1, mw.notReadyGauges.phasesShown(fwd), "shown as it enters in Main Phase 1");

		mw.gameState.advancePhase();
		mw.refreshPhaseTracker();
		assertEquals(2, mw.notReadyGauges.phasesShown(fwd), "moved on with the phase, no redraw of the card needed");

		mw.p1ForwardTempTraits.get(0).add(CardData.Trait.HASTE);
		mw.refreshP1ForwardSlot(0);
		assertNull(mw.notReadyGauges.phasesShown(fwd), "Haste: the gauge clears");

		mw.p1ForwardRemovedTraits.get(0).add(CardData.Trait.HASTE);
		mw.refreshP1ForwardSlot(0);
		assertEquals(2, mw.notReadyGauges.phasesShown(fwd), "Haste lost: back again");
	}

	// ── Backups and Monsters ─────────────────────────────────────────────

	// The 《Dull》 half of the restriction holds for every character, so a Backup or a Monster waits
	// out the same window as a Forward. A Backup enters dull, but an effect can activate it early.
	@Test
	void backupsAndMonstersAreHeldBackUntilTheirControllersNextTurnBegins() {
		MainWindow mw = inP1Main1();
		mw.placeCardInFirstBackupSlot(backup("Support"));
		mw.placeCardInMonsterZone(monster("Familiar"));
		assertEquals(1, mw.notReadyPhases(true, BACKUP, 0), "the Backup, entering in Main Phase 1");
		assertEquals(1, mw.notReadyPhases(true, MONSTER, 0), "and the Monster");

		nextTurn(mw);
		assertNotNull(mw.notReadyPhases(true, BACKUP, 0), "through the opponent's turn");
		assertNotNull(mw.notReadyPhases(true, MONSTER, 0));
		nextTurn(mw);
		assertNull(mw.notReadyPhases(true, BACKUP, 0), "ready as its controller's next turn begins");
		assertNull(mw.notReadyPhases(true, MONSTER, 0));
	}

	@Test
	void theOpponentsBackupsAndMonstersAreMarkedTheSameWay() {
		MainWindow mw = inP1Main1();
		mw.placeP2CardInFirstBackupSlot(backup("Their Support"));
		mw.placeP2CardInMonsterZone(monster("Their Familiar"));
		assertEquals(5, mw.notReadyPhases(false, BACKUP, 0), "before the opponent's first turn");
		assertEquals(5, mw.notReadyPhases(false, MONSTER, 0));
		nextTurn(mw);
		assertNull(mw.notReadyPhases(false, BACKUP, 0), "ready as the opponent's own turn begins");
		assertNull(mw.notReadyPhases(false, MONSTER, 0));
	}

	// P2's Backups used to keep no entry turn, and the engine read each as on the field since before
	// the turn: one an effect activated could pay its 《Dull》 cost the turn it entered.
	@Test
	void theOpponentsBackupWaitsToPayADullCostToo() {
		MainWindow mw = inP1Main1();
		mw.placeCardInForwardZone(forward("Target"));   // something for its ability to choose
		nextTurn(mw);
		advanceTo(mw, GameState.GamePhase.MAIN_1);
		CardData bk = backup("Their Support");
		ActionAbility dull = bk.actionAbilities().get(0);
		mw.placeP2CardInFirstBackupSlot(bk);
		assertEquals(mw.gameState.getTurnNumber(), mw.p2BackupPlayedOnTurn[0]);
		assertTrue(mw.canActivateAbility(dull, false, CardState.ACTIVE, 0, bk, false),
				"one on the field since before the turn could use it now");
		assertFalse(mw.canActivateAbility(dull, false, CardState.ACTIVE, mw.p2BackupPlayedOnTurn[0], bk, false),
				"activated the turn it entered: still waiting");

		nextTurn(mw);
		nextTurn(mw);
		advanceTo(mw, GameState.GamePhase.MAIN_1);
		assertTrue(mw.canActivateAbility(dull, false, CardState.ACTIVE, mw.p2BackupPlayedOnTurn[0], bk, false),
				"ready on its controller's next turn");
	}

	@Test
	void aBackupOrMonsterWithHasteIsNotMarked() {
		MainWindow mw = inP1Main1();
		CardData bk = backup("Swift Support"), mon = monster("Swift Familiar");
		mw.placeCardInFirstBackupSlot(bk);
		mw.placeCardInMonsterZone(mon);
		mw.p1BackupTempTraits.put(bk, EnumSet.of(CardData.Trait.HASTE));
		mw.p1MonsterTempTraits.put(mon, EnumSet.of(CardData.Trait.HASTE));
		assertNull(mw.notReadyPhases(true, BACKUP, 0));
		assertNull(mw.notReadyPhases(true, MONSTER, 0));
	}

	@Test
	void anEmptyBackupSlotIsNeverMarked() {
		MainWindow mw = inP1Main1();
		assertNull(mw.notReadyPhases(true, BACKUP, 0));
		assertNull(mw.notReadyPhases(false, BACKUP, 4));
	}

	@Test
	void backupsAndMonstersWearThePreparingTab() {
		AppSettings.setNotReadyIndicator(NotReadyIndicator.TAB);
		MainWindow mw = inP1Main1();
		mw.placeCardInFirstBackupSlot(backup("Support"));
		mw.placeCardInMonsterZone(monster("Familiar"));
		mw.placeP2CardInFirstBackupSlot(backup("Their Support"));
		mw.placeP2CardInMonsterZone(monster("Their Familiar"));
		for (boolean isP1 : new boolean[] { true, false }) {
			assertTrue(mw.visibleBackupTraitTabs(isP1, 0, mw.backupStatusDetails(isP1, 0))
					.contains(CardData.Trait.PREPARING), (isP1 ? "P1" : "P2") + "'s Backup");
			assertTrue(mw.visibleMonsterTraitTabs(isP1, 0, Map.of())
					.contains(CardData.Trait.PREPARING), (isP1 ? "P1" : "P2") + "'s Monster");
		}
		assertEquals(Boolean.TRUE, mw.p1BackupLabels[0].getClientProperty(MainWindow.SLOT_PREPARING), "drawn with it");
		assertEquals(Boolean.TRUE, mw.p1MonsterLabels.get(0).getClientProperty(MainWindow.SLOT_PREPARING));

		nextTurn(mw);
		nextTurn(mw);
		assertEquals(Boolean.TRUE, mw.p1BackupLabels[0].getClientProperty(MainWindow.SLOT_PREPARING),
				"the engine moving on redraws nothing");
		mw.refreshPhaseTracker();
		assertFalse(mw.visibleBackupTraitTabs(true, 0, mw.backupStatusDetails(true, 0)).contains(CardData.Trait.PREPARING));
		assertEquals(Boolean.FALSE, mw.p1BackupLabels[0].getClientProperty(MainWindow.SLOT_PREPARING),
				"redrawn without it at the turn boundary");
		assertEquals(Boolean.FALSE, mw.p1MonsterLabels.get(0).getClientProperty(MainWindow.SLOT_PREPARING));
	}

	@Test
	void backupsAndMonstersShowTheGauge() {
		AppSettings.setNotReadyIndicator(NotReadyIndicator.ATB);
		MainWindow mw = inP1Main1();
		CardData bk = backup("Support"), mon = monster("Familiar"), theirs = backup("Their Support");
		mw.placeCardInFirstBackupSlot(bk);
		mw.placeCardInMonsterZone(mon);
		mw.placeP2CardInFirstBackupSlot(theirs);
		assertEquals(1, mw.notReadyGauges.phasesShown(bk));
		assertEquals(1, mw.notReadyGauges.phasesShown(mon));
		assertEquals(5, mw.notReadyGauges.phasesShown(theirs));

		mw.gameState.advancePhase();
		mw.refreshPhaseTracker();
		assertEquals(2, mw.notReadyGauges.phasesShown(bk), "moved on with the phase");
		assertEquals(2, mw.notReadyGauges.phasesShown(mon));

		nextTurn(mw);
		nextTurn(mw);
		mw.refreshPhaseTracker();
		assertNull(mw.notReadyGauges.phasesShown(bk), "ready: cleared");
		assertNull(mw.notReadyGauges.phasesShown(mon));
	}

	@Test
	void switchingIndicatorsReachesBackupsAndMonsters() {
		AppSettings.setNotReadyIndicator(NotReadyIndicator.ATB);
		MainWindow mw = inP1Main1();
		CardData bk = backup("Support"), mon = monster("Familiar");
		mw.placeCardInFirstBackupSlot(bk);
		mw.placeCardInMonsterZone(mon);

		AppSettings.setNotReadyIndicator(NotReadyIndicator.TAB);
		mw.notReadyIndicatorChanged();
		assertNull(mw.notReadyGauges.phasesShown(bk), "the gauges are gone");
		assertNull(mw.notReadyGauges.phasesShown(mon));
		assertEquals(Boolean.TRUE, mw.p1BackupLabels[0].getClientProperty(MainWindow.SLOT_PREPARING), "and the tabs drawn");
		assertEquals(Boolean.TRUE, mw.p1MonsterLabels.get(0).getClientProperty(MainWindow.SLOT_PREPARING));

		AppSettings.setNotReadyIndicator(NotReadyIndicator.NONE);
		mw.notReadyIndicatorChanged();
		assertEquals(Boolean.FALSE, mw.p1BackupLabels[0].getClientProperty(MainWindow.SLOT_PREPARING), "None: no tab");
		assertEquals(Boolean.FALSE, mw.p1MonsterLabels.get(0).getClientProperty(MainWindow.SLOT_PREPARING));
		assertNull(mw.notReadyGauges.phasesShown(bk), "and no gauge");

		AppSettings.setNotReadyIndicator(NotReadyIndicator.ATB);
		mw.notReadyIndicatorChanged();
		assertEquals(1, mw.notReadyGauges.phasesShown(bk), "the gauges back at their level");
		assertEquals(1, mw.notReadyGauges.phasesShown(mon));
	}

	// ── The Preparing trait tab ──────────────────────────────────────────

	private static final String PREPARING_TEXT = "This unit cannot attack or use action abilities that "
			+ "require it to dull itself until the player's next turn.";

	private static boolean wearsPreparingTab(MainWindow mw, boolean isP1, int idx) {
		return mw.visibleTraitTabs(isP1, idx, mw.forwardStatusDetails(isP1, idx)).contains(CardData.Trait.PREPARING);
	}

	/** Whether P1's Forward slot {@code idx} was last drawn with the Preparing tab. */
	private static Object drawnPreparing(MainWindow mw, int idx) throws Exception {
		Field f = MainWindow.class.getDeclaredField("p1ForwardLabels");
		f.setAccessible(true);
		@SuppressWarnings("unchecked")
		List<JLabel> labels = (List<JLabel>) f.get(mw);
		return labels.get(idx).getClientProperty(MainWindow.SLOT_PREPARING);
	}

	@Test
	void markingNoneIsTheDefault() {
		assertSame(NotReadyIndicator.NONE, NotReadyIndicator.parse(null), "nothing saved");
		assertSame(NotReadyIndicator.NONE, NotReadyIndicator.parse("mist"), "a name this build does not know");
		for (NotReadyIndicator indicator : NotReadyIndicator.values())
			assertSame(indicator, NotReadyIndicator.parse(indicator.id()), "read back as saved");
	}

	// The rule still holds; only the marking is off.
	@Test
	void withNoIndicatorANotReadyForwardShowsNeitherTabNorGauge() throws Exception {
		AppSettings.setNotReadyIndicator(NotReadyIndicator.NONE);
		MainWindow mw = inP1Main1();
		CardData fwd = forward("Unmarked");
		mw.placeCardInForwardZone(fwd);
		mw.placeP2CardInForwardZone(forward("Theirs"));
		assertNotNull(mw.forwardNotReadyPhases(true, 0), "still not ready");
		assertFalse(wearsPreparingTab(mw, true, 0));
		assertFalse(wearsPreparingTab(mw, false, 0));
		assertEquals(Boolean.FALSE, drawnPreparing(mw, 0));
		assertNull(mw.notReadyGauges.phasesShown(fwd));

		mw.gameState.advancePhase();
		mw.refreshPhaseTracker();
		assertNull(mw.notReadyGauges.phasesShown(fwd), "no gauge as the phases go by");
		assertEquals(Boolean.FALSE, drawnPreparing(mw, 0));
	}

	@Test
	void switchingToNoneTakesDownWhicheverIndicatorWasShowing() throws Exception {
		AppSettings.setNotReadyIndicator(NotReadyIndicator.ATB);
		MainWindow mw = inP1Main1();
		CardData fwd = forward("Switched off");
		mw.placeCardInForwardZone(fwd);
		assertEquals(1, mw.notReadyGauges.phasesShown(fwd));

		AppSettings.setNotReadyIndicator(NotReadyIndicator.NONE);
		mw.notReadyIndicatorChanged();
		assertNull(mw.notReadyGauges.phasesShown(fwd), "the gauge is gone");
		assertEquals(Boolean.FALSE, drawnPreparing(mw, 0), "and no tab in its place");

		AppSettings.setNotReadyIndicator(NotReadyIndicator.TAB);
		mw.notReadyIndicatorChanged();
		assertEquals(Boolean.TRUE, drawnPreparing(mw, 0));

		AppSettings.setNotReadyIndicator(NotReadyIndicator.NONE);
		mw.notReadyIndicatorChanged();
		assertEquals(Boolean.FALSE, drawnPreparing(mw, 0), "redrawn without the tab");
		assertNull(mw.notReadyGauges.phasesShown(fwd));
	}

	@Test
	void aForwardThatIsNotReadyWearsThePreparingTabUntilItsControllersNextTurn() {
		AppSettings.setNotReadyIndicator(NotReadyIndicator.TAB);
		MainWindow mw = inP1Main1();
		CardData fwd = forward("Preparer");
		mw.placeCardInForwardZone(fwd);
		assertTrue(wearsPreparingTab(mw, true, 0), "entered this turn");
		assertNull(mw.notReadyGauges.phasesShown(fwd), "the tab instead of the gauge");

		nextTurn(mw);
		assertTrue(wearsPreparingTab(mw, true, 0), "still preparing through the opponent's turn");
		nextTurn(mw);
		assertFalse(wearsPreparingTab(mw, true, 0), "ready as its controller's next turn begins");
	}

	@Test
	void theOpponentsForwardWearsThePreparingTabToo() {
		AppSettings.setNotReadyIndicator(NotReadyIndicator.TAB);
		MainWindow mw = inP1Main1();
		mw.placeP2CardInForwardZone(forward("Theirs"));
		assertTrue(wearsPreparingTab(mw, false, 0));
		nextTurn(mw);
		assertFalse(wearsPreparingTab(mw, false, 0), "ready as the opponent's own turn begins");
	}

	// Haste lets it act at once, so it is not preparing; losing Haste before its controller's next
	// turn puts the restriction, and the tab, back.
	@Test
	void hasteTakesThePreparingTabDownAndLosingHastePutsItBack() {
		AppSettings.setNotReadyIndicator(NotReadyIndicator.TAB);
		MainWindow mw = inP1Main1();
		mw.placeCardInForwardZone(forward("Quick"));
		mw.p1ForwardTempTraits.get(0).add(CardData.Trait.HASTE);
		assertFalse(wearsPreparingTab(mw, true, 0));
		assertTrue(mw.visibleTraitTabs(true, 0, mw.forwardStatusDetails(true, 0)).contains(CardData.Trait.HASTE));

		mw.p1ForwardRemovedTraits.get(0).add(CardData.Trait.HASTE);
		assertTrue(wearsPreparingTab(mw, true, 0), "Haste lost: preparing again");
	}

	@Test
	void theATBGaugeShowsNoPreparingTab() {
		AppSettings.setNotReadyIndicator(NotReadyIndicator.ATB);
		MainWindow mw = inP1Main1();
		mw.placeCardInForwardZone(forward("Gauged"));
		assertFalse(wearsPreparingTab(mw, true, 0));
	}

	@Test
	void theTabsTooltipSaysWhatPreparingMeans() {
		MainWindow mw = new MainWindow();
		List<CardData.Trait> tabs = List.of(CardData.Trait.PREPARING);
		JLabel slot = new JLabel(new ImageIcon(new BufferedImage(H, H, BufferedImage.TYPE_INT_ARGB)));
		slot.setSize(H, H);
		mw.applyFieldSlotTooltip(slot, CardState.ACTIVE, tabs, false, Map.of(), List.of());
		Rectangle2D shown = TraitTab.layout(CardState.ACTIVE, tabs).get(0).bounds()
				.createIntersection(TraitTab.visibleStrip(CardState.ACTIVE));
		assertEquals("<html><b>Preparing</b><br>" + PREPARING_TEXT + "</html>",
				mw.fieldSlotTooltipAt(slot, (int) shown.getCenterX(), (int) shown.getCenterY()));
	}

	@Test
	void thePreparingGlyphIsGold() {
		BufferedImage canvas = new BufferedImage(H, H, BufferedImage.TYPE_INT_ARGB);
		TraitTab.renderTraitTabs(canvas, CardState.ACTIVE, List.of(CardData.Trait.PREPARING), false);
		int gold = 0;
		for (int y = 0; y < H; y++)
			for (int x = 0; x < H; x++) {
				int rgb = canvas.getRGB(x, y);
				int r = (rgb >> 16) & 0xff, g = (rgb >> 8) & 0xff, b = rgb & 0xff;
				if ((rgb >>> 24) == 0xff && r > 200 && g > 150 && b < 120) gold++;
			}
		assertTrue(gold > 20, "frame and sand in gold, found " + gold + " gold pixels");
	}

	// The tab is part of the card's art. A turn boundary, where the Forward becomes ready, redraws
	// nothing on its own account, so the phase tracker's refresh has to.
	@Test
	void theTabComesOffAtTheTurnBoundaryThoughNothingElseRedrawsTheCard() throws Exception {
		AppSettings.setNotReadyIndicator(NotReadyIndicator.TAB);
		MainWindow mw = inP1Main1();
		mw.placeCardInForwardZone(forward("Patient"));
		assertEquals(Boolean.TRUE, drawnPreparing(mw, 0), "drawn with the tab as it enters");

		nextTurn(mw);
		nextTurn(mw);
		assertEquals(Boolean.TRUE, drawnPreparing(mw, 0), "the engine moving on redraws nothing");
		mw.refreshPhaseTracker();
		assertEquals(Boolean.FALSE, drawnPreparing(mw, 0), "redrawn without it");
	}

	@Test
	void switchingIndicatorsSwapsTheGaugeAndTheTabOnTheCardsInPlay() throws Exception {
		AppSettings.setNotReadyIndicator(NotReadyIndicator.ATB);
		MainWindow mw = inP1Main1();
		CardData fwd = forward("Switched");
		mw.placeCardInForwardZone(fwd);
		assertEquals(1, mw.notReadyGauges.phasesShown(fwd));
		assertEquals(Boolean.FALSE, drawnPreparing(mw, 0));

		AppSettings.setNotReadyIndicator(NotReadyIndicator.TAB);
		mw.notReadyIndicatorChanged();
		assertNull(mw.notReadyGauges.phasesShown(fwd), "the gauge is gone");
		assertEquals(Boolean.TRUE, drawnPreparing(mw, 0), "and the card redrawn with the tab");

		AppSettings.setNotReadyIndicator(NotReadyIndicator.ATB);
		mw.notReadyIndicatorChanged();
		assertEquals(1, mw.notReadyGauges.phasesShown(fwd), "back at its level");
		assertEquals(Boolean.FALSE, drawnPreparing(mw, 0));
	}

	// ── Where the overlay draws it, and how it moves ─────────────────────

	/** Two Forward slots and the gauge overlay over them: P2's near the top, P1's below it. */
	private static final class Board {
		static final int MARGIN = 40;
		final JPanel root = new JPanel(null);
		final JLabel p2 = slot(MARGIN), p1 = slot(2 * H);
		int p1Lift;
		final NotReadyGaugeOverlay overlay = new NotReadyGaugeOverlay(s -> s == p1 ? p1Lift : 0);

		Board() {
			root.setSize(H + 2 * MARGIN, 3 * H + MARGIN);
			overlay.setBounds(0, 0, root.getWidth(), root.getHeight());
			root.add(overlay);
			root.add(p1);
			root.add(p2);
		}

		private static JLabel slot(int y) {
			JLabel slot = new JLabel(new ImageIcon(new BufferedImage(H, H, BufferedImage.TYPE_INT_ARGB)));
			slot.setBounds(MARGIN, y, H, H);
			return slot;
		}

		/** Where P1's bar goes, above its slot, when the slot is drawn {@code lift} above its place. */
		Rectangle2D p1Bar(int lift) {
			return NotReadyMarker.bar(p1.getX(), p1.getY() - lift, false);
		}

		Rectangle2D p1Bar() {
			return p1Bar(0);
		}

		Rectangle2D p2Bar() {
			return NotReadyMarker.bar(p2.getX(), p2.getY() + p2.getHeight(), true);
		}

		BufferedImage painted() {
			BufferedImage img = new BufferedImage(root.getWidth(), root.getHeight(), BufferedImage.TYPE_INT_ARGB);
			Graphics2D g = img.createGraphics();
			overlay.paint(g);
			g.dispose();
			return img;
		}
	}

	/** The pixel {@code fill} of the way along the bar's fill, on its centre line. */
	private static int[] along(Rectangle2D bar, double fill) {
		return new int[] { (int) Math.round(bar.getX() + (1 + 120 * fill) * bar.getWidth() / 122),
				(int) bar.getCenterY() };
	}

	private static boolean drawnAt(BufferedImage img, Rectangle2D bar, double fill) {
		int[] p = along(bar, fill);
		return (img.getRGB(p[0], p[1]) >>> 24) > 0;
	}

	/** Whether the bar is lit gold {@code fill} of the way along, rather than showing its dark navy track. */
	private static boolean litAt(BufferedImage img, Rectangle2D bar, double fill) {
		int[] p = along(bar, fill);
		int rgb = img.getRGB(p[0], p[1]);
		return ((rgb >> 16) & 0xff) > (rgb & 0xff);
	}

	private static boolean blankWithin(BufferedImage img, Rectangle r) {
		for (int y = r.y; y < r.y + r.height; y++)
			for (int x = r.x; x < r.x + r.width; x++)
				if ((img.getRGB(x, y) >>> 24) != 0) return false;
		return true;
	}

	@Test
	void theGaugeSitsOffTheCardsCentreFacingEdge() {
		Board b = new Board();
		b.overlay.show("mine", b.p1, 4, false);
		b.overlay.show("theirs", b.p2, 4, true);
		BufferedImage img = b.painted();
		assertTrue(drawnAt(img, b.p1Bar(), 0.5), "above P1's Forward");
		assertTrue(drawnAt(img, b.p2Bar(), 0.5), "below P2's");
		assertTrue(blankWithin(img, b.p1.getBounds()), "nothing over P1's card");
		assertTrue(blankWithin(img, b.p2.getBounds()), "nor over P2's");
		assertTrue(b.p1Bar().getMaxY() < b.p1.getY() && b.p2Bar().getMinY() > b.p2.getY() + H);
	}

	// A Forward that is not ready may still block, and a blocker is drawn raised out of its row.
	@Test
	void aRaisedBlockerTakesItsGaugeWithIt() {
		Board b = new Board();
		b.p1Lift = 2 * NotReadyMarker.reach(b.p1Bar()).height;
		b.overlay.show("mine", b.p1, 4, false);
		BufferedImage img = b.painted();
		assertTrue(drawnAt(img, b.p1Bar(b.p1Lift), 0.5), "up with the card");
		assertFalse(drawnAt(img, b.p1Bar(), 0.5), "not left behind in the row");
	}

	@Test
	void aPhaseGoingByRisesFromTheOldLevel() {
		Board b = new Board();
		b.overlay.show("card", b.p1, 2, false);
		b.overlay.show("card", b.p1, 3, false);
		assertEquals(3, b.overlay.phasesShown("card"));
		BufferedImage rising = b.painted();
		assertFalse(litAt(rising, b.p1Bar(), 2.5 / FULL), "starting from the level shown before");
		assertTrue(litAt(rising, b.p1Bar(), 1.5 / FULL));
	}

	@Test
	void aRiseStillRunningHandsOverWithoutAJump() {
		Board b = new Board();
		b.overlay.show("card", b.p1, 2, false);
		b.overlay.show("card", b.p1, 3, false);
		b.overlay.show("card", b.p1, 4, false);
		assertFalse(litAt(b.painted(), b.p1Bar(), 2.5 / FULL), "from where the first rise had got to, not from 3/8");
	}

	@Test
	void showingWhatIsShownLeavesTheRiseRunning() {
		Board b = new Board();
		b.overlay.show("card", b.p1, 2, false);
		b.overlay.show("card", b.p1, 3, false);
		b.overlay.show("card", b.p1, 3, false);
		assertFalse(litAt(b.painted(), b.p1Bar(), 2.5 / FULL), "still rising, not jumped to 3/8");
	}

	@Test
	void aCardBecomingReadyClearsItsGaugeFromWhereItStood() {
		Board b = new Board();
		b.overlay.show("card", b.p1, FULL, false);
		b.overlay.show("card", b.p1, null, false);
		assertNull(b.overlay.phasesShown("card"));
		assertTrue(litAt(b.painted(), b.p1Bar(), 0.95), "a full gauge flashes from full");

		b.overlay.show("card", b.p1, null, false);
		assertTrue(drawnAt(b.painted(), b.p1Bar(), 0.5), "showing it ready again leaves the clearing running");
	}

	// Switching to the tab takes every gauge down at once: a clearing would read as each waiting
	// Forward becoming ready.
	@Test
	void clearingTheOverlayDrawsNoClearing() {
		Board b = new Board();
		b.overlay.show("mine", b.p1, 4, false);
		b.overlay.show("theirs", b.p2, FULL, true);
		b.overlay.clear();
		assertNull(b.overlay.phasesShown("mine"));
		BufferedImage img = b.painted();
		assertFalse(drawnAt(img, b.p1Bar(), 0.25), "nothing left above P1's Forward");
		assertFalse(drawnAt(img, b.p2Bar(), 0.95), "nor a flash below P2's");

		b.overlay.show("mine", b.p1, 4, false);
		assertTrue(drawnAt(b.painted(), b.p1Bar(), 0.25), "a gauge shown afterwards starts afresh");
	}

	@Test
	void aClearingDuringARiseStartsFromWhereTheRiseHadGot() {
		Board b = new Board();
		b.overlay.show("card", b.p1, FULL - 1, false);
		b.overlay.show("card", b.p1, FULL, false);
		b.overlay.show("card", b.p1, null, false);
		assertFalse(litAt(b.painted(), b.p1Bar(), 0.95), "the clearing fills the last of it itself");
	}

	@Test
	void theGaugeFollowsItsCardToANewSlot() {
		Board b = new Board();
		b.overlay.show("card", b.p2, 2, true);
		b.overlay.show("card", b.p1, 3, false);
		BufferedImage img = b.painted();
		assertFalse(drawnAt(img, b.p2Bar(), 0.5), "gone from the old slot");
		assertTrue(drawnAt(img, b.p1Bar(), 0.5), "drawn at the new one");
		assertFalse(litAt(img, b.p1Bar(), 2.5 / FULL), "still rising from where it was");
	}

	@Test
	void aGaugeGoesWithItsCard() {
		Board b = new Board();
		b.overlay.show("card", b.p1, 3, false);
		b.root.remove(b.p1);
		assertFalse(drawnAt(b.painted(), b.p1Bar(), 0.5), "its slot has left the board");
	}

	/** Draws {@code slot}'s card as the board draws a field card in {@code state}. */
	private static void drawIn(JLabel slot, CardState state) {
		slot.setIcon(new FieldCardIcon(new BufferedImage(H, H, BufferedImage.TYPE_INT_ARGB), state));
	}

	/** Where a gauge hugging the dull card in {@code slot} goes: off the art, inside the slot. */
	private static Rectangle2D offDullArt(JLabel slot, boolean below) {
		int artTop = slot.getY() + CardAnimation.LEFT_GUTTER;
		return NotReadyMarker.bar(slot.getX(), below ? artTop + CardAnimation.CARD_W : artTop, below);
	}

	// A Backup lies dull across its square slot. Off the slot's edge its gauge would sit against the
	// Forward row, so it hugs the card, in the free canvas above or below the art.
	@Test
	void aDullBackupsGaugeHugsTheCardInsideItsSlot() {
		Board b = new Board();
		drawIn(b.p1, CardState.DULL);
		drawIn(b.p2, CardState.DULL);
		b.overlay.show("mine", b.p1, 4, false, true);
		b.overlay.show("theirs", b.p2, 4, true, true);
		BufferedImage img = b.painted();
		assertTrue(drawnAt(img, offDullArt(b.p1, false), 0.5), "above P1's Backup's art");
		assertTrue(drawnAt(img, offDullArt(b.p2, true), 0.5), "below P2's");
		assertFalse(drawnAt(img, b.p1Bar(), 0.5), "not off the slot's edge");
		assertTrue(b.p1.getBounds().contains(offDullArt(b.p1, false)), "inside P1's slot");
		assertTrue(b.p2.getBounds().contains(offDullArt(b.p2, true)), "inside P2's");
	}

	@Test
	void anUprightBackupFillsItsSlotSoItsGaugeSitsOffTheSlot() {
		Board b = new Board();
		drawIn(b.p1, CardState.ACTIVE);
		b.overlay.show("mine", b.p1, 4, false, true);
		assertTrue(drawnAt(b.painted(), b.p1Bar(), 0.5));
	}

	// The frames of a dull or activate turn carry no state; the gauge keeps to where it was.
	@Test
	void aHuggingGaugeKeepsItsPlaceThroughATurnsFrames() {
		Board b = new Board();
		drawIn(b.p1, CardState.DULL);
		b.overlay.show("mine", b.p1, 4, false, true);
		b.p1.setIcon(new ImageIcon(new BufferedImage(H, H, BufferedImage.TYPE_INT_ARGB)));
		assertTrue(drawnAt(b.painted(), offDullArt(b.p1, false), 0.5));
	}

	// A Backup's slot stays when its card leaves and empties instead. The gauge goes with the card,
	// with no clearing, which would read as the card becoming ready.
	@Test
	void anEmptiedSlotDropsItsGaugeWithNoClearing() throws Exception {
		Board b = new Board();
		b.overlay.show("gone", b.p1, FULL, false, true);
		b.p1.setIcon(null);
		SwingUtilities.invokeAndWait(() -> { });
		assertNull(b.overlay.phasesShown("gone"));
		assertTrue(blankWithin(b.painted(), new Rectangle(0, 0, b.root.getWidth(), b.root.getHeight())));
	}

	@Test
	void aNewCardInTheSlotTakesTheOldOnesGaugeDownWithNoClearing() {
		Board b = new Board();
		b.overlay.show("old", b.p1, FULL, false, true);
		b.overlay.show("new", b.p1, null, false, true);
		assertNull(b.overlay.phasesShown("old"));
		assertTrue(blankWithin(b.painted(), new Rectangle(0, 0, b.root.getWidth(), b.root.getHeight())));
	}

	@Test
	void aCardThatNeverWaitedShowsNothing() {
		Board b = new Board();
		b.overlay.show("card", b.p1, null, false);
		assertNull(b.overlay.phasesShown("card"));
		assertTrue(blankWithin(b.painted(), new Rectangle(0, 0, b.root.getWidth(), b.root.getHeight())));
	}

	@Test
	void aGaugeComingBackWhenHasteIsLostIsSimplyShown() throws InterruptedException {
		Board b = new Board();
		b.overlay.show("card", b.p1, 2, false);
		b.overlay.show("card", b.p1, null, false);
		b.overlay.show("card", b.p1, 2, false);
		assertEquals(2, b.overlay.phasesShown("card"));
		Thread.sleep(300);   // a clearing still running would have filled the bar by now
		BufferedImage img = b.painted();
		assertTrue(litAt(img, b.p1Bar(), 1.5 / FULL));
		assertFalse(litAt(img, b.p1Bar(), 2.5 / FULL), "at rest at 2/8");
	}

	// ── The drawing ──────────────────────────────────────────────────────

	private static final Rectangle2D BAR = NotReadyMarker.bar(40, 60, true);

	private static BufferedImage canvas() {
		return new BufferedImage(H + 80, 140, BufferedImage.TYPE_INT_ARGB);
	}

	private static BufferedImage resting(double fill) {
		BufferedImage img = canvas();
		Graphics2D g = img.createGraphics();
		NotReadyMarker.paint(g, BAR, fill);
		g.dispose();
		return img;
	}

	private static BufferedImage clearing(double from, double ms) {
		BufferedImage img = canvas();
		Graphics2D g = img.createGraphics();
		NotReadyMarker.paintClearing(g, BAR, from, ms);
		g.dispose();
		return img;
	}

	@Test
	void theClearingStartsAtTheRestingGaugeAndEndsInNothing() {
		for (int phases : new int[] { 0, 3, FULL }) {
			double from = NotReadyMarker.fill(phases);
			BufferedImage rest = resting(from), first = clearing(from, 0);
			for (int y = 0; y < rest.getHeight(); y++)
				for (int x = 0; x < rest.getWidth(); x++)
					assertEquals(rest.getRGB(x, y), first.getRGB(x, y), phases + "/8: frame 0 is the resting gauge");
			assertTrue(blankWithin(clearing(from, NotReadyMarker.clearingMs(from)), new Rectangle(0, 0, H + 80, 140)),
					phases + "/8: gone at the end");
		}
	}

	// What the overlay repaints is the bar's reach; anything drawn outside it would be left behind.
	@Test
	void everythingTheClearingDrawsIsWithinTheBarsReach() {
		Rectangle reach = NotReadyMarker.reach(BAR);
		for (double ms : new double[] { 0, 30, 60, 100, 200 }) {
			BufferedImage img = clearing(1, ms);
			for (int y = 0; y < img.getHeight(); y++)
				for (int x = 0; x < img.getWidth(); x++)
					if ((img.getRGB(x, y) >>> 24) != 0)
						assertTrue(reach.contains(x, y), ms + " ms: drawn at (" + x + ", " + y + "), outside " + reach);
		}
	}

	@Test
	void aRiseEasesFromTheOldLevelToTheNew() {
		assertEquals(0.25, NotReadyMarker.riseFill(0.25, 0.375, 0));
		assertEquals(0.375, NotReadyMarker.riseFill(0.25, 0.375, NotReadyMarker.RISE_MS));
		assertEquals(0.3125, NotReadyMarker.riseFill(0.25, 0.375, NotReadyMarker.RISE_MS / 2.0), 1e-9);
	}

	@Test
	void aFullGaugeClearsSoonerThanOneWithFillingLeftToDo() {
		assertTrue(NotReadyMarker.clearingMs(1) < NotReadyMarker.clearingMs(0));
	}

	@Test
	void theRestingGaugeIsLitAsFarAsThePhasesGoneBy() {
		BufferedImage half = resting(NotReadyMarker.fill(4));
		assertTrue(litAt(half, BAR, 0.2), "lit along its first half");
		assertFalse(litAt(half, BAR, 0.8), "dark along its second");
		assertFalse(litAt(resting(0), BAR, 0.2), "empty at entry");
		assertTrue(litAt(resting(1), BAR, 0.95), "full at the end");
	}
}
