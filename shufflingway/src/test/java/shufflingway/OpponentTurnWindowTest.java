package shufflingway;

import static org.junit.jupiter.api.Assertions.*;
import static shufflingway.TestCards.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;

import scraper.DeckDatabase.DeckCardDetail;

/**
 * The windows the CPU holds priority in while P1 acts (tools/HANDOFF-cpu-opponents-turn.md), driven
 * through the real turn flow: P1 passes, the CPU makes a play in the window, and the turn carries on.
 *
 * <p>The plays are scripted ({@link ScriptedCpu}) — which play is worth making is not these tests'
 * business — but each is made the way the CPU makes it on its own turn, paid for out of its own
 * Backups ({@link ComputerPlayer#castFromHandIfAble}). The turn flow runs on Swing timers, so each
 * test drives it on the EDT and waits for where it should end up; P1's Respond window, which a bare
 * {@link MainWindow} never shows, is stood in for by passing on whatever the CPU puts on the Stack.
 */
class OpponentTurnWindowTest {

	/**
	 * The real CPU, recording every window it is asked in, and that P2's turn began rather than
	 * playing it. {@link #playing} turns it into a scripted one: the first time it holds priority in
	 * that window it makes that play, and it makes none of its own anywhere — the engine-readiness
	 * tests are about the window, not about what the CPU would choose.
	 */
	private static final class ScriptedCpu extends ComputerPlayer {
		final List<PriorityWindow> held = new ArrayList<>();
		private PriorityWindow window;
		private BooleanSupplier play;
		private boolean scriptOnly;
		boolean turnBegan;

		ScriptedCpu(MainWindow mw) {
			super(mw);
			pauseMs = 1;
		}

		ScriptedCpu playing(PriorityWindow window, BooleanSupplier play) {
			this.window = window;
			this.play = play;
			this.scriptOnly = true;
			return this;
		}

		@Override boolean playInWindow(PriorityWindow w, StackEntry entry) {
			held.add(w);
			if (w == window && play != null) {
				BooleanSupplier p = play;
				play = null;
				if (p.getAsBoolean()) return true;
			}
			return !scriptOnly && super.playInWindow(w, entry);
		}

		@Override public void runTurn() { turnBegan = true; }
	}

	/** A board at {@code phase} of P1's turn, against {@code cpu}'s seat. */
	private static MainWindow p1sTurnAt(GameState.GamePhase phase) {
		MainWindow mw = new MainWindow();
		mw.p2AutoPassMs = 1;
		mw.gameState.startFirstTurn(GameState.Player.P1);
		while (mw.gameState.getCurrentPhase() != phase) mw.gameState.advancePhase();
		for (int i = 0; i < 5; i++) {
			CardData filler = makeForward("Deck " + i, "Ice", 1, 1000);
			mw.gameState.getIdentity().put(filler, false);
			mw.gameState.getP2MainDeck().add(filler);
		}
		return mw;
	}

	/** P2 holds {@code card} and {@code backups} active Fire Backups to pay for it with. */
	private static void p2Holds(MainWindow mw, CardData card, int backups) {
		for (int i = 0; i < backups; i++) placeP2Backup(mw, makeBackup("Aide " + i, "Fire", 1));
		mw.gameState.getIdentity().put(card, false);
		mw.gameState.getP2Hand().add(card);
	}

	/** A Fire Summon that breaks whatever opposing Forward 9000 damage reaches. */
	private static CardData bolt() {
		return makeSummon("Bolt", "Fire", 1, "Choose 1 Forward opponent controls. Deal it 9000 damage.");
	}

	/** A Fire Forward with Back Attack, cost 3, power 9000. */
	private static CardData ambusher() {
		return makeForwardWithTraits("Ambusher", "Fire", 9000, EnumSet.of(CardData.Trait.BACK_ATTACK));
	}

	private static void onEdt(Runnable r) throws Exception {
		SwingUtilities.invokeAndWait(r);
	}

	/**
	 * Lets the turn flow run until {@code done} holds, passing — as P1 would in the Respond window —
	 * on each entry the CPU puts on the Stack. Fails after 15 seconds, naming {@code what}.
	 */
	private static void playOut(MainWindow mw, BooleanSupplier done, String what) throws Exception {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
		while (System.nanoTime() < deadline) {
			boolean[] reached = { false };
			onEdt(() -> {
				StackEntry top = mw.gameState.peekStack();
				if (top != null && !top.isP1()) mw.passStackPriority();
				reached[0] = done.getAsBoolean();
			});
			if (reached[0]) return;
			Thread.sleep(20);
		}
		fail("timed out waiting for " + what + "; log:\n" + mw.gameLogText());
	}

	/** P1's Forward, on the field since an earlier turn so it may attack. */
	private static CardData p1Attacker(MainWindow mw, int power) {
		CardData attacker = makeForward("Attacker", "Water", 3, power);
		placeP1Forward(mw, attacker);
		mw.p1ForwardPlayedOnTurn.set(mw.p1ForwardCards.indexOf(attacker), 0);
		return attacker;
	}

	// ---- The phase changes ----------------------------------------------------------------

	@Test
	void aSummonCastAsMainPhase1EndsResolvesAndTheAttackPhaseFollows() throws Exception {
		MainWindow mw = p1sTurnAt(GameState.GamePhase.MAIN_1);
		CardData target = makeForward("Target", "Water", 3, 7000);
		placeP1Forward(mw, target);
		p2Holds(mw, bolt(), 1);
		ScriptedCpu cpu = new ScriptedCpu(mw);
		mw.opponent = cpu.playing(PriorityWindow.MAIN_1_END, cpu::castFromHandIfAble);

		onEdt(mw::onNextPhase);
		playOut(mw, () -> mw.gameState.getCurrentPhase() != GameState.GamePhase.MAIN_1, "Main Phase 1 to end");

		assertTrue(mw.gameState.getP1BreakZone().contains(target), "the Bolt resolved");
		assertTrue(cpu.held.contains(PriorityWindow.MAIN_1_END));
		assertTrue(mw.gameState.getCurrentPhase() == GameState.GamePhase.ATTACK
				|| mw.gameState.getCurrentPhase() == GameState.GamePhase.MAIN_2, "the turn moved on");
	}

	@Test
	void aSummonCastAtAttackPreparationResolvesAndDeclareAttackersFollows() throws Exception {
		MainWindow mw = p1sTurnAt(GameState.GamePhase.ATTACK);
		mw.attackSubStep = 0;
		p1Attacker(mw, 7000);
		CardData target = makeForward("Target", "Water", 3, 7000);
		placeP1Forward(mw, target);
		p2Holds(mw, bolt(), 1);
		ScriptedCpu cpu = new ScriptedCpu(mw);
		mw.opponent = cpu.playing(PriorityWindow.ATTACK_PREPARATION, cpu::castFromHandIfAble);

		onEdt(mw::onNextPhase);
		playOut(mw, () -> mw.attackSubStep == 1, "Declare Attackers");

		assertTrue(cpu.held.contains(PriorityWindow.ATTACK_PREPARATION));
		assertEquals(1, mw.gameState.getP1BreakZone().size(), "the Bolt broke one of the two");
		assertEquals(GameState.GamePhase.ATTACK, mw.gameState.getCurrentPhase());
	}

	@Test
	void aSummonCastAsMainPhase2EndsResolvesAndTheTurnPasses() throws Exception {
		MainWindow mw = p1sTurnAt(GameState.GamePhase.MAIN_2);
		CardData target = makeForward("Target", "Water", 3, 7000);
		placeP1Forward(mw, target);
		p2Holds(mw, bolt(), 1);
		ScriptedCpu cpu = new ScriptedCpu(mw);
		mw.opponent = cpu.playing(PriorityWindow.MAIN_2_END, cpu::castFromHandIfAble);

		onEdt(mw::onNextPhase);
		playOut(mw, () -> cpu.turnBegan, "P2's turn to begin");
		onEdt(() -> mw.gameState.setP1GameOver(true));

		assertTrue(mw.gameState.getP1BreakZone().contains(target), "the Bolt resolved before the End Phase");
		assertTrue(cpu.held.contains(PriorityWindow.MAIN_2_END));
	}

	// ---- The combat checkpoints -----------------------------------------------------------

	@Test
	void aSummonThatBreaksTheDeclaredAttackerEndsTheBattleUndamaged() throws Exception {
		MainWindow mw = p1sTurnAt(GameState.GamePhase.ATTACK);
		mw.attackSubStep = 1;
		CardData attacker = p1Attacker(mw, 7000);
		p2Holds(mw, bolt(), 1);
		ScriptedCpu cpu = new ScriptedCpu(mw);
		mw.opponent = cpu.playing(PriorityWindow.ATTACK_DECLARED, cpu::castFromHandIfAble);

		onEdt(() -> mw.executeP1Attack(List.of(0)));
		playOut(mw, mw.p1DeclaredAttackers::isEmpty, "the battle to end");

		assertTrue(mw.gameState.getP1BreakZone().contains(attacker));
		assertTrue(mw.gameState.getP2DamageZone().isEmpty(), "no attacker left to deal damage");
		assertTrue(cpu.held.contains(PriorityWindow.ATTACK_DECLARED));
	}

	@Test
	void aSummonThatBreaksAnUnblockedAttackerAfterTheBlockStepStopsItsDamage() throws Exception {
		MainWindow mw = p1sTurnAt(GameState.GamePhase.ATTACK);
		mw.attackSubStep = 1;
		CardData attacker = p1Attacker(mw, 7000);
		p2Holds(mw, bolt(), 1);
		ScriptedCpu cpu = new ScriptedCpu(mw);
		mw.opponent = cpu.playing(PriorityWindow.BLOCK_DECLARED, cpu::castFromHandIfAble);

		onEdt(() -> mw.executeP1Attack(List.of(0)));
		playOut(mw, mw.p1DeclaredAttackers::isEmpty, "the battle to end");

		assertEquals(List.of(PriorityWindow.ATTACK_DECLARED, PriorityWindow.BLOCK_DECLARED),
				cpu.held.stream().distinct().toList(), "asked at both checkpoints, in order");
		assertTrue(mw.gameState.getP1BreakZone().contains(attacker));
		assertTrue(mw.gameState.getP2DamageZone().isEmpty(), "broken before damage, so none is dealt");
	}

	@Test
	void anActionAbilityUsedAfterTheAttackIsDeclaredResolvesAndTheBattleEnds() throws Exception {
		MainWindow mw = p1sTurnAt(GameState.GamePhase.ATTACK);
		mw.attackSubStep = 1;
		CardData attacker = p1Attacker(mw, 7000);
		CardData gunner = makeTextCard("Gunner", "Fire", "Forward", 3, 5000, null,
				"《Dull》: Choose 1 Forward opponent controls. Deal it 9000 damage.");
		placeP2Forward(mw, gunner);
		mw.p2ForwardPlayedOnTurn.set(0, 0);   // on the field since an earlier turn, so its 《Dull》 is payable
		ScriptedCpu cpu = new ScriptedCpu(mw);
		mw.opponent = cpu.playing(PriorityWindow.ATTACK_DECLARED, () -> mw.autoAbilityTriggers.executeP2AbilityActivation(
				gunner.actionAbilities().get(0), gunner,
				mw.abilityCostDull(new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD)), List.of(), List.of(), 0));

		onEdt(() -> mw.executeP1Attack(List.of(0)));
		playOut(mw, mw.p1DeclaredAttackers::isEmpty, "the battle to end");

		assertTrue(mw.gameState.getP1BreakZone().contains(attacker));
		assertEquals(CardState.DULL, mw.p2ForwardStates.get(0), "the Gunner paid with its 《Dull》");
		assertTrue(mw.gameState.getP2DamageZone().isEmpty());
	}

	@Test
	void aBackAttackForwardPlayedAfterTheAttackIsDeclaredCanBlockIt() throws Exception {
		MainWindow mw = p1sTurnAt(GameState.GamePhase.ATTACK);
		mw.attackSubStep = 1;
		CardData attacker = p1Attacker(mw, 7000);
		CardData ambusher = ambusher();
		p2Holds(mw, ambusher, 3);
		ScriptedCpu cpu = new ScriptedCpu(mw);
		mw.opponent = cpu.playing(PriorityWindow.ATTACK_DECLARED, cpu::castFromHandIfAble);

		onEdt(() -> mw.executeP1Attack(List.of(0)));
		playOut(mw, mw.p1DeclaredAttackers::isEmpty, "the battle to end");

		assertTrue(mw.p2ForwardCards.contains(ambusher), "it entered, and survived the block");
		assertTrue(mw.gameState.getP1BreakZone().contains(attacker), "9000 breaks the 7000 attacker");
		assertTrue(mw.gameState.getP2DamageZone().isEmpty(), "blocked, so no damage");
	}

	// ---- P1's Stack entries ----------------------------------------------------------------

	/** P1 casts {@code summon}; returns once the CPU has had its say on it. */
	private static void p1Casts(MainWindow mw, CardData summon) throws Exception {
		onEdt(() -> {
			mw.gameState.getIdentity().put(summon, true);
			mw.pushSummonOnStack(summon, true, 0, 0, false, null, false);
			mw.showStackWindow();
		});
	}

	@Test
	void aSummonCastInResponseResolvesFirstAndP1sEntryThenResolves() throws Exception {
		MainWindow mw = p1sTurnAt(GameState.GamePhase.MAIN_1);
		CardData target = makeForward("Target", "Water", 3, 7000);
		placeP1Forward(mw, target);
		CardData top = makeForward("Top", "Water", 1, 1000);
		mw.gameState.getIdentity().put(top, true);
		mw.gameState.getP1MainDeck().add(top);
		p2Holds(mw, bolt(), 1);
		ScriptedCpu cpu = new ScriptedCpu(mw);
		mw.opponent = cpu.playing(PriorityWindow.STACK_RESPONSE, cpu::castFromHandIfAble);

		p1Casts(mw, makeSummon("Insight", "Water", 1, "Draw 1 card."));
		playOut(mw, () -> mw.gameState.getStack().isEmpty(), "the Stack to empty");

		assertTrue(mw.gameState.getP1BreakZone().contains(target), "the response resolved");
		assertTrue(mw.gameState.getP1Hand().contains(top), "and P1's Summon after it");
		assertEquals(List.of(PriorityWindow.STACK_RESPONSE, PriorityWindow.STACK_RESPONSE), cpu.held,
				"asked again when the Stack came back round to P1's entry");
	}

	@Test
	void aBackAttackForwardPlayedInResponseLeavesP1sEntryToResolve() throws Exception {
		// Nothing goes on the Stack for a Character: the response is made, and P1's entry is still
		// on top waiting for the CPU to pass on it.
		MainWindow mw = p1sTurnAt(GameState.GamePhase.MAIN_1);
		CardData top = makeForward("Top", "Water", 1, 1000);
		mw.gameState.getIdentity().put(top, true);
		mw.gameState.getP1MainDeck().add(top);
		CardData ambusher = ambusher();
		p2Holds(mw, ambusher, 3);
		ScriptedCpu cpu = new ScriptedCpu(mw);
		mw.opponent = cpu.playing(PriorityWindow.STACK_RESPONSE, cpu::castFromHandIfAble);

		p1Casts(mw, makeSummon("Insight", "Water", 1, "Draw 1 card."));
		playOut(mw, () -> mw.gameState.getStack().isEmpty(), "P1's Summon to resolve");

		assertTrue(mw.p2ForwardCards.contains(ambusher));
		assertTrue(mw.gameState.getP1Hand().contains(top));
	}

	// ---- Back Attack (decision 4): the real CPU's own plays ---------------------------------

	/** Built as a dealt card is, so "Back Attack" in its text becomes the trait and its abilities parse. */
	private static CardData dealt(String name, String element, String type, int cost, int power, String text) {
		return MainWindow.buildCardData(new DeckCardDetail(null, name, element, cost, power, type,
				false, 0, false, text, false, null, null, null));
	}

	/** A Back Attack Forward whose arrival deals 4000 damage to an opposing Forward — 8-100R Jinnai's shape. */
	private static CardData sniper() {
		return dealt("Sniper", "Fire", "Forward", 3, 5000, "Back Attack[[br]]When Sniper enters the field, choose 1 "
				+ "Forward opponent controls. Deal it 4000 damage.");
	}

	@Test
	void theCpuAmbushesAnAttackerItHasNoBlockerFor() throws Exception {
		MainWindow mw = p1sTurnAt(GameState.GamePhase.ATTACK);
		mw.attackSubStep = 1;
		CardData attacker = p1Attacker(mw, 7000);
		CardData ambusher = ambusher();
		p2Holds(mw, ambusher, 3);
		mw.opponent = new ScriptedCpu(mw);

		onEdt(() -> mw.executeP1Attack(List.of(0)));
		playOut(mw, mw.p1DeclaredAttackers::isEmpty, "the battle to end");

		assertTrue(mw.p2ForwardCards.contains(ambusher));
		assertTrue(mw.gameState.getP1BreakZone().contains(attacker), "it blocked, and its 9000 broke the 7000");
		assertTrue(mw.gameState.getP2DamageZone().isEmpty());
		assertTrue(mw.gameLogText().contains("[P2] Back Attack — Ambusher: it blocks Attacker and survives"));
	}

	@Test
	void theCpuKeepsItsAmbusherWhenAForwardItAlreadyHasBlocks() throws Exception {
		MainWindow mw = p1sTurnAt(GameState.GamePhase.ATTACK);
		mw.attackSubStep = 1;
		p1Attacker(mw, 7000);
		placeP2Forward(mw, makeForward("Guard", "Fire", 4, 9000));
		CardData ambusher = ambusher();
		p2Holds(mw, ambusher, 3);
		mw.opponent = new ScriptedCpu(mw);

		onEdt(() -> mw.executeP1Attack(List.of(0)));
		playOut(mw, mw.p1DeclaredAttackers::isEmpty, "the battle to end");

		assertTrue(mw.gameState.getP2Hand().contains(ambusher), "the Guard blocks; nothing to ambush for");
		assertTrue(mw.gameState.getP2DamageZone().isEmpty());
	}

	@Test
	void theCpuKeepsItsAmbusherWhenItWouldNotSurviveTheBlock() throws Exception {
		MainWindow mw = p1sTurnAt(GameState.GamePhase.ATTACK);
		mw.attackSubStep = 1;
		p1Attacker(mw, 10000);
		CardData ambusher = ambusher();
		p2Holds(mw, ambusher, 3);
		mw.opponent = new ScriptedCpu(mw);

		onEdt(() -> mw.executeP1Attack(List.of(0)));
		playOut(mw, mw.p1DeclaredAttackers::isEmpty, "the battle to end");

		assertTrue(mw.gameState.getP2Hand().contains(ambusher), "9000 does not survive 10000");
		assertEquals(1, mw.gameState.getP2DamageZone().size());
	}

	@Test
	void theCpuPlaysABackAttackArrivalThatBreaksAForward() throws Exception {
		MainWindow mw = p1sTurnAt(GameState.GamePhase.MAIN_1);
		CardData target = makeForward("Target", "Water", 2, 4000);
		placeP1Forward(mw, target);
		CardData sniper = sniper();
		p2Holds(mw, sniper, 3);
		mw.opponent = new ScriptedCpu(mw);

		onEdt(mw::onNextPhase);
		playOut(mw, () -> mw.gameState.getCurrentPhase() != GameState.GamePhase.MAIN_1, "Main Phase 1 to end");

		assertTrue(mw.p2ForwardCards.contains(sniper));
		assertTrue(mw.gameState.getP1BreakZone().contains(target), "its 4000 broke the 4000 Forward");
		assertTrue(mw.gameLogText().contains("[P2] Back Attack — Sniper: its arrival's 4000 damage breaks a Forward"));
	}

	@Test
	void theCpuHoldsABackAttackArrivalThatWouldBreakNothing() throws Exception {
		MainWindow mw = p1sTurnAt(GameState.GamePhase.MAIN_1);
		placeP1Forward(mw, makeForward("Big", "Water", 3, 7000));
		CardData sniper = sniper();
		p2Holds(mw, sniper, 3);
		mw.opponent = new ScriptedCpu(mw);

		onEdt(mw::onNextPhase);
		playOut(mw, () -> mw.gameState.getCurrentPhase() != GameState.GamePhase.MAIN_1, "Main Phase 1 to end");

		assertTrue(mw.gameState.getP2Hand().contains(sniper), "4000 breaks no 7000 Forward");
	}

	@Test
	void theCpuFlashesItsBackAttackForwardInAsYourTurnEnds() throws Exception {
		MainWindow mw = p1sTurnAt(GameState.GamePhase.MAIN_2);
		CardData ambusher = ambusher();
		p2Holds(mw, ambusher, 3);
		ScriptedCpu cpu = new ScriptedCpu(mw);
		mw.opponent = cpu;

		onEdt(mw::onNextPhase);
		playOut(mw, () -> cpu.turnBegan, "P2's turn to begin");
		onEdt(() -> mw.gameState.setP1GameOver(true));

		assertTrue(mw.p2ForwardCards.contains(ambusher), "on the field before its own turn");
		assertTrue(mw.gameLogText().contains("[P2] Back Attack — Ambusher: on the field before its own turn"));
	}

	@Test
	void theCpuWillNotDiscardToFlashInAsYourTurnEnds() throws Exception {
		MainWindow mw = p1sTurnAt(GameState.GamePhase.MAIN_2);
		CardData ambusher = ambusher();
		p2Holds(mw, ambusher, 0);
		for (int i = 0; i < 2; i++) {
			CardData spare = makeForward("Spare " + i, "Fire", 1, 1000);
			mw.gameState.getIdentity().put(spare, false);
			mw.gameState.getP2Hand().add(spare);
		}
		ScriptedCpu cpu = new ScriptedCpu(mw);
		mw.opponent = cpu;

		onEdt(mw::onNextPhase);
		playOut(mw, () -> cpu.turnBegan, "P2's turn to begin");
		onEdt(() -> mw.gameState.setP1GameOver(true));

		assertTrue(mw.gameState.getP2Hand().contains(ambusher), "cheaper cast on its own turn, with its Backups back");
		assertEquals(3, mw.gameState.getP2Hand().size(), "nothing discarded");
	}

	// ---- Back Attack on the CPU's own turn: held, with the Backups to cast it ---------------

	/** P2's own Main Phase 1, with {@code backups} active Fire Backups. */
	private static MainWindow p2sMainPhaseWith(int backups) {
		MainWindow mw = new MainWindow();
		mw.gameState.startFirstTurn(GameState.Player.P2);
		while (mw.gameState.getCurrentPhase() != GameState.GamePhase.MAIN_1) mw.gameState.advancePhase();
		for (int i = 0; i < backups; i++) placeP2Backup(mw, makeBackup("Aide " + i, "Fire", 1));
		return mw;
	}

	private static void p2Draws(MainWindow mw, CardData card) {
		mw.gameState.getIdentity().put(card, false);
		mw.gameState.getP2Hand().add(card);
	}

	private static long activeP2Backups(MainWindow mw) {
		long n = 0;
		for (int i = 0; i < mw.p2BackupCards.length; i++)
			if (mw.p2BackupCards[i] != null && mw.p2BackupStates[i] == CardState.ACTIVE) n++;
		return n;
	}

	@Test
	void theCpuKeepsItsBackAttackForwardAndTheBackupsToCastItOnYourTurn() {
		MainWindow mw = p2sMainPhaseWith(5);
		CardData ambusher = ambusher();
		CardData plain = makeForward("Plain", "Fire", 2, 5000);
		p2Draws(mw, ambusher);
		p2Draws(mw, plain);
		ComputerPlayer cpu = new ComputerPlayer(mw);

		assertTrue(cpu.castFromHandIfAble(), "the Plain Forward, with the two Backups not set aside");
		assertFalse(cpu.castFromHandIfAble(), "the Ambusher is kept");

		assertTrue(mw.p2ForwardCards.contains(plain));
		assertTrue(mw.gameState.getP2Hand().contains(ambusher));
		assertEquals(3, activeP2Backups(mw), "the three that cast it on P1's turn");
	}

	@Test
	void theCpuDoesNotDiscardItsHeldCardToPayForAnother() {
		MainWindow mw = p2sMainPhaseWith(3);
		CardData ambusher = ambusher();
		p2Draws(mw, ambusher);
		p2Draws(mw, makeForward("Plain", "Fire", 2, 5000));
		ComputerPlayer cpu = new ComputerPlayer(mw);

		assertFalse(cpu.castFromHandIfAble(), "Plain could only be paid by discarding the Ambusher");
		assertTrue(mw.gameState.getP2Hand().contains(ambusher));
		assertEquals(3, activeP2Backups(mw));
	}

	@Test
	void theCpuCastsABackAttackForwardItsBackupsCouldNotCoverOnYourTurn() {
		// Not held: two Backups cannot pay its 3 alone, so it is cast as any Forward is.
		MainWindow mw = p2sMainPhaseWith(2);
		CardData ambusher = ambusher();
		p2Draws(mw, ambusher);
		p2Draws(mw, makeForward("Fodder", "Fire", 1, 1000));
		ComputerPlayer cpu = new ComputerPlayer(mw);

		assertTrue(cpu.castFromHandIfAble());
		assertTrue(mw.p2ForwardCards.contains(ambusher));
	}

	// ---- Combat tricks (decision 5) ---------------------------------------------------------

	/** A Fire Summon, cost 1: "Choose 1 Forward. It gains +2000 power until the end of the turn." */
	private static CardData might() {
		return makeSummon("Might", "Fire", 1, "Choose 1 Forward. It gains +2000 power until the end of the turn.");
	}

	/** P2's active Forward {@code name}, on the field since an earlier turn. */
	private static CardData p2Forward(MainWindow mw, String name, int power) {
		CardData f = makeForward(name, "Fire", 3, power);
		placeP2Forward(mw, f);
		mw.p2ForwardPlayedOnTurn.set(mw.p2ForwardCards.indexOf(f), 0);
		return f;
	}

	/** P1 attacks with {@code attacker}; returns once the battle is over. */
	private static void p1AttacksWith(MainWindow mw) throws Exception {
		onEdt(() -> mw.executeP1Attack(List.of(0)));
		playOut(mw, mw.p1DeclaredAttackers::isEmpty, "the battle to end");
	}

	@Test
	void aTrickLetsAForwardBlockThatOtherwiseCouldNotSurvive() throws Exception {
		MainWindow mw = p1sTurnAt(GameState.GamePhase.ATTACK);
		mw.attackSubStep = 1;
		CardData attacker = p1Attacker(mw, 8000);
		CardData guard = p2Forward(mw, "Guard", 7000);
		p2Holds(mw, might(), 1);
		mw.opponent = new ScriptedCpu(mw);

		p1AttacksWith(mw);

		assertTrue(mw.gameState.getP1BreakZone().contains(attacker), "9000 breaks the 8000 attacker");
		assertTrue(mw.p2ForwardCards.contains(guard), "and survives it");
		assertTrue(mw.gameState.getP2DamageZone().isEmpty());
		assertTrue(mw.gameLogText().contains("[P2] Combat trick — Might so Guard can block Attacker and survive"));
	}

	@Test
	void theTrickIsAimedAtTheForwardThatCanBlock() throws Exception {
		// The stronger Forward is dull, so it cannot block; the buff picker's own order would hand it the
		// +2000 and leave the Guard to watch.
		MainWindow mw = p1sTurnAt(GameState.GamePhase.ATTACK);
		mw.attackSubStep = 1;
		CardData attacker = p1Attacker(mw, 8000);
		CardData resting = p2Forward(mw, "Resting", 7500);
		mw.p2ForwardStates.set(mw.p2ForwardCards.indexOf(resting), CardState.DULL);
		CardData guard = p2Forward(mw, "Guard", 7000);
		p2Holds(mw, might(), 1);
		mw.opponent = new ScriptedCpu(mw);

		p1AttacksWith(mw);

		assertTrue(mw.gameState.getP1BreakZone().contains(attacker));
		assertTrue(mw.p2ForwardCards.contains(guard));
		assertEquals(7500, mw.effectiveP2ForwardPower(mw.p2ForwardCards.indexOf(resting)), "the boost went to the Guard");
	}

	@Test
	void noTrickIsSpentOnABattleItWouldNotDecide() throws Exception {
		MainWindow mw = p1sTurnAt(GameState.GamePhase.ATTACK);
		mw.attackSubStep = 1;
		p1Attacker(mw, 10000);
		p2Forward(mw, "Guard", 7000);
		CardData might = might();
		p2Holds(mw, might, 1);
		mw.opponent = new ScriptedCpu(mw);

		p1AttacksWith(mw);

		assertTrue(mw.gameState.getP2Hand().contains(might), "9000 does not survive 10000");
		assertEquals(1, mw.gameState.getP2DamageZone().size());
	}

	@Test
	void anAbilityOnItsFieldSavesABlockerAnEvenBattleWouldBreak() throws Exception {
		// 7000 blocks 7000: chooseBlocker takes the trade. After the block the Drillmaster's +1000 makes
		// it a win.
		MainWindow mw = p1sTurnAt(GameState.GamePhase.ATTACK);
		mw.attackSubStep = 1;
		CardData attacker = p1Attacker(mw, 7000);
		CardData guard = p2Forward(mw, "Guard", 7000);
		CardData drillmaster = makeTextCard("Drillmaster", "Fire", "Backup", 2, 0, null,
				"《Dull》: Choose 1 Forward you control. It gains +1000 power until the end of the turn.");
		placeP2Backup(mw, drillmaster);
		mw.opponent = new ScriptedCpu(mw);

		p1AttacksWith(mw);

		assertTrue(mw.p2ForwardCards.contains(guard), "8000 survives 7000");
		assertTrue(mw.gameState.getP1BreakZone().contains(attacker));
		assertTrue(mw.gameLogText().contains("[P2] Combat trick — Drillmaster's ability to save Guard"));
	}

	@Test
	void aBlockerThatCannotBeBrokenBlocksAndATrickFinishesTheAttacker() throws Exception {
		// The Wall survives any battle, which chooseBlocker (power alone) does not see; it blocks for
		// nothing, and after the block +2000 turns its 5000 into enough to break the 7000 attacker.
		MainWindow mw = p1sTurnAt(GameState.GamePhase.ATTACK);
		mw.attackSubStep = 1;
		CardData attacker = p1Attacker(mw, 7000);
		CardData wall = makeForwardWithTraits("Wall", "Fire", 5000, EnumSet.of(CardData.Trait.CANNOT_BE_BROKEN));
		placeP2Forward(mw, wall);
		mw.p2ForwardPlayedOnTurn.set(0, 0);
		p2Holds(mw, might(), 1);
		mw.opponent = new ScriptedCpu(mw);

		p1AttacksWith(mw);

		assertTrue(mw.gameLogText().contains("[P2] Wall will block Attacker: it survives the battle"));
		assertTrue(mw.gameLogText().contains("[P2] Combat trick — Might to break Attacker"));
		assertTrue(mw.gameState.getP1BreakZone().contains(attacker));
		assertTrue(mw.p2ForwardCards.contains(wall));
		assertTrue(mw.gameState.getP2DamageZone().isEmpty());
	}

	@Test
	void aBackAttackArrivalIsATrickToo() throws Exception {
		// A Back Attack Backup whose arrival is the +2000 — Kain 19-073C's shape with power instead.
		MainWindow mw = p1sTurnAt(GameState.GamePhase.ATTACK);
		mw.attackSubStep = 1;
		CardData attacker = p1Attacker(mw, 8000);
		CardData guard = p2Forward(mw, "Guard", 7000);
		CardData coach = dealt("Coach", "Fire", "Backup", 2, 0, "Back Attack[[br]]When Coach enters the field, choose 1 "
				+ "Forward. It gains +2000 power until the end of the turn.");
		p2Holds(mw, coach, 2);
		mw.opponent = new ScriptedCpu(mw);

		p1AttacksWith(mw);

		assertTrue(Arrays.asList(mw.p2BackupCards).contains(coach));
		assertTrue(mw.gameState.getP1BreakZone().contains(attacker));
		assertTrue(mw.p2ForwardCards.contains(guard));
	}

	// ---- Removal (decision 6) ---------------------------------------------------------------

	@Test
	void theCpuBreaksYourAttackerBeforeItDealsDamage() throws Exception {
		MainWindow mw = p1sTurnAt(GameState.GamePhase.ATTACK);
		mw.attackSubStep = 1;
		CardData attacker = p1Attacker(mw, 7000);
		p2Holds(mw, bolt(), 1);
		mw.opponent = new ScriptedCpu(mw);

		p1AttacksWith(mw);

		assertTrue(mw.gameState.getP1BreakZone().contains(attacker));
		assertTrue(mw.gameState.getP2DamageZone().isEmpty());
		assertTrue(mw.gameLogText().contains("[P2] Removal — Bolt breaks Attacker before it deals damage"));
	}

	@Test
	void theRemovalIsAimedAtTheAttackerNotABiggerForwardBesideIt() throws Exception {
		// The damage picker's own order (the strongest it breaks) would take the Big Forward.
		MainWindow mw = p1sTurnAt(GameState.GamePhase.ATTACK);
		mw.attackSubStep = 1;
		CardData attacker = p1Attacker(mw, 7000);
		CardData big = makeForward("Big", "Water", 5, 8000);
		placeP1Forward(mw, big);
		p2Holds(mw, bolt(), 1);
		mw.opponent = new ScriptedCpu(mw);

		p1AttacksWith(mw);

		assertTrue(mw.gameState.getP1BreakZone().contains(attacker));
		assertTrue(mw.p1ForwardCards.contains(big));
	}

	@Test
	void theCpuKeepsItsRemovalWhenABlockAlreadyDealsWithTheAttacker() throws Exception {
		MainWindow mw = p1sTurnAt(GameState.GamePhase.ATTACK);
		mw.attackSubStep = 1;
		CardData attacker = p1Attacker(mw, 7000);
		p2Forward(mw, "Guard", 9000);
		CardData bolt = bolt();
		p2Holds(mw, bolt, 1);
		mw.opponent = new ScriptedCpu(mw);

		p1AttacksWith(mw);

		assertTrue(mw.gameState.getP2Hand().contains(bolt));
		assertTrue(mw.gameState.getP1BreakZone().contains(attacker), "the Guard's block broke it");
	}

	@Test
	void theCpuKeepsRemovalThatWouldNotBreakTheAttacker() throws Exception {
		MainWindow mw = p1sTurnAt(GameState.GamePhase.ATTACK);
		mw.attackSubStep = 1;
		p1Attacker(mw, 10000);
		CardData bolt = bolt();
		p2Holds(mw, bolt, 1);
		mw.opponent = new ScriptedCpu(mw);

		p1AttacksWith(mw);

		assertTrue(mw.gameState.getP2Hand().contains(bolt), "9000 does not break 10000");
		assertEquals(1, mw.gameState.getP2DamageZone().size());
	}

	@Test
	void anAbilityOnItsFieldBreaksYourAttacker() throws Exception {
		MainWindow mw = p1sTurnAt(GameState.GamePhase.ATTACK);
		mw.attackSubStep = 1;
		CardData attacker = p1Attacker(mw, 7000);
		placeP2Backup(mw, makeTextCard("Gunner", "Fire", "Backup", 2, 0, null,
				"《Dull》: Choose 1 Forward opponent controls. Deal it 8000 damage."));
		mw.opponent = new ScriptedCpu(mw);

		p1AttacksWith(mw);

		assertTrue(mw.gameState.getP1BreakZone().contains(attacker));
		assertEquals(CardState.DULL, mw.p2BackupStates[0], "paid with the Gunner's 《Dull》");
	}

	@Test
	void asYourTurnEndsTheCpuBreaksTheDearestForwardItCan() throws Exception {
		MainWindow mw = p1sTurnAt(GameState.GamePhase.MAIN_2);
		CardData cheap = makeForward("Cheap", "Water", 2, 5000);
		CardData dear = makeForward("Dear", "Water", 5, 8000);
		CardData tooBig = makeForward("Too Big", "Water", 7, 10000);
		placeP1Forward(mw, cheap);
		placeP1Forward(mw, dear);
		placeP1Forward(mw, tooBig);
		p2Holds(mw, bolt(), 1);
		ScriptedCpu cpu = new ScriptedCpu(mw);
		mw.opponent = cpu;

		onEdt(mw::onNextPhase);
		playOut(mw, () -> cpu.turnBegan, "P2's turn to begin");
		onEdt(() -> mw.gameState.setP1GameOver(true));

		assertTrue(mw.gameState.getP1BreakZone().contains(dear), "the dearest one 9000 breaks");
		assertTrue(mw.p1ForwardCards.containsAll(List.of(cheap, tooBig)));
		assertTrue(mw.gameLogText().contains("[P2] Removal — Bolt breaks Dear before P2's turn"));
	}

	@Test
	void noRemovalIsSpentOnAForwardThatCannotBeBroken() throws Exception {
		MainWindow mw = p1sTurnAt(GameState.GamePhase.MAIN_2);
		CardData wall = makeForwardWithTraits("Wall", "Water", 5000, EnumSet.of(CardData.Trait.CANNOT_BE_BROKEN));
		placeP1Forward(mw, wall);
		CardData bolt = bolt();
		p2Holds(mw, bolt, 1);
		ScriptedCpu cpu = new ScriptedCpu(mw);
		mw.opponent = cpu;

		onEdt(mw::onNextPhase);
		playOut(mw, () -> cpu.turnBegan, "P2's turn to begin");
		onEdt(() -> mw.gameState.setP1GameOver(true));

		assertTrue(mw.gameState.getP2Hand().contains(bolt));
		assertTrue(mw.p1ForwardCards.contains(wall));
	}

	// ---- Stack responses (decision 7) --------------------------------------------------------

	/** P1 casts {@code summon} with its targets already chosen; returns once the CPU has had its say. */
	private static void p1CastsAt(MainWindow mw, CardData summon, ForwardTarget... targets) throws Exception {
		onEdt(() -> {
			mw.gameState.getIdentity().put(summon, true);
			mw.pushSummonOnStack(summon, true, 0, 0, false, List.of(targets), true);
			mw.showStackWindow();
		});
	}

	private static CardData p1Bolt() {
		return makeSummon("Thunder", "Lightning", 2, "Choose 1 Forward opponent controls. Deal it 9000 damage.");
	}

	private static ForwardTarget p2Slot(MainWindow mw, CardData card) {
		return new ForwardTarget(false, mw.p2ForwardCards.indexOf(card), ForwardTarget.CardZone.FORWARD);
	}

	@Test
	void theCpuCancelsYourSummonThatWouldBreakItsForward() throws Exception {
		MainWindow mw = p1sTurnAt(GameState.GamePhase.MAIN_1);
		CardData guard = p2Forward(mw, "Guard", 7000);
		// 2-080C Carbuncle's wording.
		CardData counter = makeSummon("Counter", "Fire", 1,
				"Choose 1 Summon targeting a Character you control. Cancel its effect.");
		p2Holds(mw, counter, 1);
		mw.opponent = new ScriptedCpu(mw);

		p1CastsAt(mw, p1Bolt(), p2Slot(mw, guard));
		playOut(mw, () -> mw.gameState.getStack().isEmpty(), "the Stack to empty");

		assertTrue(mw.p2ForwardCards.contains(guard));
		assertEquals(0, (int) mw.p2ForwardDamage.get(mw.p2ForwardCards.indexOf(guard)), "Thunder was cancelled");
		assertTrue(mw.gameState.getP2BreakZone().contains(counter));
		assertTrue(mw.gameLogText().contains("[P2] Answers Thunder — cancel with Counter"));
	}

	@Test
	void theCpuLetsAnEntryThatTakesNothingOfItsOwnResolve() throws Exception {
		MainWindow mw = p1sTurnAt(GameState.GamePhase.MAIN_1);
		CardData top = makeForward("Top", "Water", 1, 1000);
		mw.gameState.getIdentity().put(top, true);
		mw.gameState.getP1MainDeck().add(top);
		CardData counter = makeSummon("Counter", "Fire", 1,
				"Choose 1 Summon targeting a Character you control. Cancel its effect.");
		p2Holds(mw, counter, 1);
		mw.opponent = new ScriptedCpu(mw);

		p1Casts(mw, makeSummon("Insight", "Water", 1, "Draw 1 card."));
		playOut(mw, () -> mw.gameState.getStack().isEmpty(), "the Stack to empty");

		assertTrue(mw.gameState.getP2Hand().contains(counter));
		assertTrue(mw.gameState.getP1Hand().contains(top));
	}

	@Test
	void theCpuShieldsItsForwardFromYourDamage() throws Exception {
		MainWindow mw = p1sTurnAt(GameState.GamePhase.MAIN_1);
		CardData guard = p2Forward(mw, "Guard", 7000);
		p2Holds(mw, makeSummon("Veil", "Fire", 1,
				"Choose 1 Forward. During this turn, the next damage dealt to it becomes 0 instead."), 1);
		mw.opponent = new ScriptedCpu(mw);

		p1CastsAt(mw, p1Bolt(), p2Slot(mw, guard));
		playOut(mw, () -> mw.gameState.getStack().isEmpty(), "the Stack to empty");

		assertTrue(mw.p2ForwardCards.contains(guard), "the 9000 became 0");
		assertTrue(mw.gameLogText().contains("[P2] Answers Thunder — shields Guard with Veil"));
	}

	@Test
	void aShieldAgainstDamageIsNotSpentOnABreak() throws Exception {
		MainWindow mw = p1sTurnAt(GameState.GamePhase.MAIN_1);
		CardData guard = p2Forward(mw, "Guard", 7000);
		CardData veil = makeSummon("Veil", "Fire", 1,
				"Choose 1 Forward. During this turn, the next damage dealt to it becomes 0 instead.");
		p2Holds(mw, veil, 1);
		mw.opponent = new ScriptedCpu(mw);

		p1CastsAt(mw, makeSummon("Doom", "Dark", 3, "Choose 1 Forward opponent controls. Break it."), p2Slot(mw, guard));
		playOut(mw, () -> mw.gameState.getStack().isEmpty(), "the Stack to empty");

		assertTrue(mw.gameState.getP2Hand().contains(veil), "it stops damage, not a break");
		assertTrue(mw.gameState.getP2BreakZone().contains(guard));
	}

	@Test
	void theCpuBreaksTheForwardYourBuffIsAimedAt() throws Exception {
		MainWindow mw = p1sTurnAt(GameState.GamePhase.MAIN_1);
		CardData brute = makeForward("Brute", "Water", 3, 6000);
		placeP1Forward(mw, brute);
		p2Holds(mw, bolt(), 1);
		mw.opponent = new ScriptedCpu(mw);

		p1CastsAt(mw, makeSummon("Pump", "Water", 1, "Choose 1 Forward. It gains +3000 power until the end of the turn."),
				new ForwardTarget(true, mw.p1ForwardCards.indexOf(brute), ForwardTarget.CardZone.FORWARD));
		playOut(mw, () -> mw.gameState.getStack().isEmpty(), "the Stack to empty");

		assertTrue(mw.gameState.getP1BreakZone().contains(brute));
		assertTrue(mw.gameLogText().contains("[P2] Answers Pump — Bolt breaks Brute before the buff lands"));
	}

	@Test
	void aBackAttackArrivalThatCancelsAnswersYourSummon() throws Exception {
		// The arrival's trigger is pushed while the response is being built, when the Stack window is
		// held back — the window must still open for it afterwards.
		MainWindow mw = p1sTurnAt(GameState.GamePhase.MAIN_1);
		CardData guard = p2Forward(mw, "Guard", 7000);
		CardData seven = dealt("Seven", "Fire", "Backup", 2, 0, "Back Attack[[br]]When Seven enters the field, choose "
				+ "1 Summon. Cancel its effect.");
		p2Holds(mw, seven, 2);
		mw.opponent = new ScriptedCpu(mw);

		p1CastsAt(mw, p1Bolt(), p2Slot(mw, guard));
		playOut(mw, () -> mw.gameState.getStack().isEmpty(), "the Stack to empty");

		assertTrue(Arrays.asList(mw.p2BackupCards).contains(seven));
		assertTrue(mw.p2ForwardCards.contains(guard), "Thunder was cancelled");
	}

	// ---- Sacrifice for damage, counting a follow-up (HANDOFF-cpu-you-may decision 16) ---------

	private static final String JACK_28_010R = "When a Forward enters your opponent's field other than from their hand, "
			+ "you may put Jack Garland into the Break Zone. When you do so, deal that Forward 9000 damage.";

	/** A Fire Summon, cost 1, dealing 4000 to an opposing Forward — a follow-up. */
	private static CardData spark() {
		return makeSummon("Spark", "Fire", 1, "Choose 1 Forward opponent controls. Deal it 4000 damage.");
	}

	/** A CPU Jack Garland (a Fire Backup) and {@code aides} more Fire Backups; P1's Forward of {@code power} then arrives other than from hand. */
	private static CardData arrivalUnderJack(MainWindow mw, int power, int aides) {
		placeP2Backup(mw, makeTextCard("Jack Garland", "Fire", "Backup", 4, 0, null, JACK_28_010R));
		for (int i = 0; i < aides; i++) placeP2Backup(mw, makeBackup("Aide " + i, "Fire", 1));
		CardData arrival = makeForward("Arrival", "Water", 5, power);
		mw.lastCardWasCast = false;
		placeP1Forward(mw, arrival);
		while (!mw.gameState.getStack().isEmpty()) mw.resolveTopOfStack();
		return arrival;
	}

	private static boolean jackPaid(MainWindow mw) {
		return mw.gameState.getP2BreakZone().stream().anyMatch(c -> c.name().equals("Jack Garland"));
	}

	@Test
	void jackPaysForDamageThatBreaksTheArrivalByItself() {
		MainWindow mw = p1sTurnAt(GameState.GamePhase.MAIN_1);
		mw.opponent = new ScriptedCpu(mw);
		CardData arrival = arrivalUnderJack(mw, 9000, 0);
		assertTrue(jackPaid(mw));
		assertTrue(mw.gameState.getP1BreakZone().contains(arrival));
	}

	@Test
	void jackKeepsHimselfWhenHisDamageBreaksNothingAndNothingFollowsUp() {
		MainWindow mw = p1sTurnAt(GameState.GamePhase.MAIN_1);
		mw.opponent = new ScriptedCpu(mw);
		CardData arrival = arrivalUnderJack(mw, 12000, 0);
		assertFalse(jackPaid(mw));
		assertTrue(mw.p1ForwardCards.contains(arrival));
		assertTrue(mw.gameLogText().contains("[CPU] declines (its damage would break nothing, and nothing it can "
				+ "still play this turn would finish the Forward)"));
	}

	@Test
	void jackPaysWhenAFollowUpWouldFinishTheArrivalAndItDoesAsYourTurnEnds() throws Exception {
		MainWindow mw = p1sTurnAt(GameState.GamePhase.MAIN_2);
		ScriptedCpu cpu = new ScriptedCpu(mw);
		mw.opponent = cpu;
		CardData spark = spark();
		mw.gameState.getIdentity().put(spark, false);
		mw.gameState.getP2Hand().add(spark);
		CardData arrival = arrivalUnderJack(mw, 12000, 1);

		assertTrue(jackPaid(mw), "9000 now, and Spark's 4000 finishes the 3000 left");
		assertEquals(9000, (int) mw.p1ForwardDamage.get(mw.p1ForwardCards.indexOf(arrival)));

		onEdt(mw::onNextPhase);
		playOut(mw, () -> cpu.turnBegan, "P2's turn to begin");
		onEdt(() -> mw.gameState.setP1GameOver(true));

		assertTrue(mw.gameState.getP1BreakZone().contains(arrival), "the follow-up landed as P1's turn ended");
	}

	@Test
	void aFollowUpJackHimselfWouldHaveToPayForIsNoFollowUp() {
		// The only Fire CP for Spark is Jack's, and Jack is what the offer puts into the Break Zone.
		MainWindow mw = p1sTurnAt(GameState.GamePhase.MAIN_1);
		mw.opponent = new ScriptedCpu(mw);
		CardData spark = spark();
		mw.gameState.getIdentity().put(spark, false);
		mw.gameState.getP2Hand().add(spark);
		arrivalUnderJack(mw, 12000, 0);
		assertFalse(jackPaid(mw));
	}

	@Test
	void noFollowUpIsCountedWithNoWindowLeftThisTurn() {
		MainWindow mw = p1sTurnAt(GameState.GamePhase.END);
		mw.opponent = new ScriptedCpu(mw);
		CardData spark = spark();
		mw.gameState.getIdentity().put(spark, false);
		mw.gameState.getP2Hand().add(spark);
		arrivalUnderJack(mw, 12000, 1);
		assertFalse(jackPaid(mw), "the damage would be gone before the CPU could follow it up");
	}

	@Test
	void vincentPutsABackupInForDamageAFollowUpWouldFinish() {
		// 23-119R Vincent's shape on the CPU's own turn: 9000 into a 10000 Forward breaks nothing, but a
		// 2000 Summon in hand finishes it in the Main Phase still under way.
		MainWindow mw = p2sMainPhaseWith(2);
		CardData theirs = makeForward("Theirs", "Water", 6, 10000);
		placeP1Forward(mw, theirs);
		CardData nudge = makeSummon("Nudge", "Fire", 1, "Choose 1 Forward opponent controls. Deal it 2000 damage.");
		p2Draws(mw, nudge);
		mw.opponent = new ScriptedCpu(mw);
		placeP2Forward(mw, makeTextCard("Vincent", "Fire", "Forward", 5, 8000, null, "When Vincent enters the field, "
				+ "you may put 1 Fire Backup you control into the Break Zone. When you do so, choose 1 Forward opponent "
				+ "controls. Deal it 9000 damage."));
		while (!mw.gameState.getStack().isEmpty()) mw.resolveTopOfStack();

		assertEquals(9000, (int) mw.p1ForwardDamage.get(mw.p1ForwardCards.indexOf(theirs)));
		assertEquals(1, mw.gameState.getP2BreakZone().size(), "a Fire Backup paid for it");
	}

	// ---- The seam itself --------------------------------------------------------------------

	@Test
	void eachCheckpointIsNamedForWhatHasJustHappened() {
		MainWindow mw = p1sTurnAt(GameState.GamePhase.MAIN_1);
		assertEquals(PriorityWindow.MAIN_1_END, mw.cpuPriorityWindow());
		mw.gameState.advancePhase();
		mw.attackSubStep = 0;
		assertEquals(PriorityWindow.ATTACK_PREPARATION, mw.cpuPriorityWindow());
		mw.attackSubStep = 1;
		assertEquals(PriorityWindow.ATTACK_DECLARED, mw.cpuPriorityWindow());
		mw.attackSubStep = 2;
		assertEquals(PriorityWindow.BLOCK_DECLARED, mw.cpuPriorityWindow());
		mw.attackSubStep = 3;
		assertEquals(PriorityWindow.OTHER, mw.cpuPriorityWindow());
		mw.gameState.advancePhase();
		assertEquals(PriorityWindow.MAIN_2_END, mw.cpuPriorityWindow());
	}

	@Test
	void withNothingToPlayTheCpuPassesEveryWindowOnce() throws Exception {
		MainWindow mw = p1sTurnAt(GameState.GamePhase.ATTACK);
		mw.attackSubStep = 1;
		p1Attacker(mw, 7000);
		ScriptedCpu cpu = new ScriptedCpu(mw);
		mw.opponent = cpu;

		onEdt(() -> mw.executeP1Attack(List.of(0)));
		playOut(mw, mw.p1DeclaredAttackers::isEmpty, "the battle to end");

		assertEquals(List.of(PriorityWindow.ATTACK_DECLARED, PriorityWindow.BLOCK_DECLARED), cpu.held);
		assertEquals(1, mw.gameState.getP2DamageZone().size(), "unblocked, the attacker's point lands");
	}
}
