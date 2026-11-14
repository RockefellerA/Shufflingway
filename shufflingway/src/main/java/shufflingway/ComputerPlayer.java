package shufflingway;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntUnaryOperator;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.Timer;

/**
 * AI controller for Player 2 (the computer opponent).
 * Package-private; instantiated by {@link MainWindow} via {@code new ComputerPlayer(this)}.
 *
 * <p>Implements {@link OpponentController}, the seam a networked opponent will also sit behind.
 * The {@code request…} methods answer synchronously — see {@link #requestBlocker}.
 */
class ComputerPlayer implements OpponentController {

	final MainWindow mw;

	ComputerPlayer(MainWindow mw) {
		this.mw = mw;
	}

	private static final int PAUSE_MS = 500;

	/** How long {@link #step} waits before each move; tests shorten it. */
	int pauseMs = PAUSE_MS;

	/**
	 * Hand size at which P2 spends its main phase playing cards rather than activating abilities.
	 *
	 * <p>Five is the end-phase hand limit: from here up, a card held past the end of the turn is a
	 * card discarded, so the board is where it is worth more than any ability effect the same CP
	 * would buy.
	 */
	private static final int HAND_PLAY_PREFERRED_AT = 5;

	/**
	 * How many LB cards P2 will play in one turn. A placeholder, and deliberately a cheap one.
	 *
	 * <p>P2 looks at its LB deck first and unconditionally, so with no cap it plays LB cards until
	 * it can no longer raise the CP — dulling every Backup and emptying its hand into a row of
	 * them before considering anything it drew. That is not a judgement it is making; it is the
	 * absence of one, and it only became visible when the LB path started producing plays at all.
	 *
	 * <p>One per turn is a stopgap that keeps the rest of the turn intact, not a strategy. A real
	 * heuristic would weigh an LB card against what the same Backups and discards would buy from
	 * hand, and against holding the LB deck for a turn when the board actually needs it — neither
	 * of which this asks. The cap is not a rule: the human is not limited, and nothing in
	 * {@link PlayerTurnState} records it, because it belongs to this planner rather than to the
	 * game.
	 */
	private static final int MAX_LB_PLAYS_PER_TURN = 1;

	/** LB cards played so far this turn; cleared by {@link #resetTurnAllowances()}. */
	int lbPlaysThisTurn = 0;

	private boolean cancelled = false;

	/** Permanently stops this ComputerPlayer; all pending and future steps become no-ops. */
	@Override
	public void cancel() { cancelled = true; }

	@Override
	public boolean isCpu() { return true; }

	/**
	 * Schedules {@code r} to run after {@link #PAUSE_MS} ms on the EDT, but waits for the board to
	 * settle first — an empty stack, nothing mid-resolution, and no card still arriving on the
	 * field (see {@link MainWindow#isBoardSettled}).
	 */
	private void step(Runnable r) {
		Timer t = new Timer(pauseMs, e -> {
			if (cancelled) return;
			if (mw.gameState.isP1GameOver()) return;
			if (!mw.isBoardSettled()) { step(r); return; }
			r.run();
		});
		t.setRepeats(false);
		t.start();
	}

	/**
	 * Clears the allowances this planner gives itself for one turn — currently just the LB play
	 * count. One controller lasts the whole game, so these are cleared here rather than by a fresh
	 * instance, and both Main Phases share {@code doMainPhase}, so an allowance spans the turn
	 * rather than the phase.
	 *
	 * <p>Separate from {@link #runTurn()} so it can be read, and asserted on, without starting a
	 * turn's worth of scheduled work.
	 */
	void resetTurnAllowances() {
		lbPlaysThisTurn = 0;
	}

	/** Entry point: called when P2's ACTIVE phase begins. */
	@Override
	public void runTurn() {
		resetTurnAllowances();
		step(this::doActivePhase);
	}

	// ── Active Phase ─────────────────────────────────────────────────────

	private void doActivePhase() {
		mw.turnPhases().runP2ActivePhase();
		mw.gameState.advancePhase(); // ACTIVE → DRAW
		mw.refreshPhaseTracker();
		step(this::doDrawPhase);
	}

	// ── Draw Phase ───────────────────────────────────────────────────────

	private void doDrawPhase() {
		int drawCount = mw.gameState.getTurnNumber() == 1 ? 1 : 2;
		List<CardData> drawn = mw.turnPhases().runP2DrawPhase(drawCount);
		if (drawn.size() < drawCount && mw.playerLoses(false, "P2 milled out — You Win!")) return;
		mw.logEntry("[P2] Draw Phase — Drew " + drawn.size() + " card(s) (hand: " + mw.gameState.getP2Hand().size() + ")");
		mw.gameState.advancePhase(); // DRAW → MAIN_1
		mw.refreshPhaseTracker();
		mw.logEntry("[P2] Main Phase 1");
		// Asked before the phase's own triggers, because a phase that is skipped is not a phase
		// that began. The AI's half of the check the button-driven flow makes (16-037R Babus).
		if (mw.consumePhaseSkip(false, GameState.GamePhase.MAIN_1)) {
			mw.logEntry("[P2] Main Phase 1 skipped");
			step(this::enterP2AttackPhase);
			return;
		}
		mw.processWarpCounters(false);
		mw.firePendingMainPhase1(false);
		mw.autoAbilityTriggers.triggerAutoAbilitiesForBeginningOfMainPhase1(false);
		mw.autoAbilityTriggers.triggerAutoAbilitiesForBeginningOfMainPhase1EachTurn();
		mw.autoAbilityTriggers.triggerAutoAbilitiesForBeginningOfOppMainPhase1(true);
		step(() -> doMainPhase(this::enterP2AttackPhase));
	}

	/**
	 * MAIN_1 → ATTACK for P2, and everything that follows from it.
	 *
	 * <p>Named rather than inlined as it once was, because two callers reach it now: the ordinary
	 * end of Main Phase 1, and a Main Phase 1 the opponent has taken away (16-037R Babus), which
	 * has to arrive here without having played the phase it skipped.
	 */
	private void enterP2AttackPhase() {
		mw.gameState.advancePhase(); // MAIN_1 → ATTACK
		mw.refreshPhaseTracker();
		// Before the phase's own triggers, for the reason given at the Main Phase 1 check above
		// (6-127L Hraesvelgr).
		if (mw.consumePhaseSkip(false, GameState.GamePhase.ATTACK)) {
			mw.logEntry("[P2] Attack Phase skipped");
			enterP2MainPhase2();
			return;
		}
		boolean canAttack = false;
		for (int i = 0; i < mw.p2ForwardStates.size(); i++) {
			if (p2ForwardCanAttack(i)) { canAttack = true; break; }
		}
		if (!canAttack) {
			for (int i = 0; i < mw.p2MonsterStates.size(); i++) {
				if (mw.p2MonsterCanAttackAsForward(i)) { canAttack = true; break; }
			}
		}
		if (!canAttack) {
			mw.logEntry("[P2] Attack Phase — No attackers, skipping");
			enterP2MainPhase2();
		} else {
			mw.logEntry("[P2] Attack Phase");
			mw.autoAbilityTriggers.triggerAutoAbilitiesForBeginningOfAttackPhase(false);
			mw.autoAbilityTriggers.triggerAutoAbilitiesForBeginningOfAttackPhaseEachTurn(false);
			mw.autoAbilityTriggers.triggerAutoAbilitiesForBeginningOfOppAttackPhase(false);
			mw.refreshAllP2ForwardSlots();
			// Attack Preparation: P2 (turn player) has acted, so P1 holds priority before P2
			// may declare an attacker.
			mw.offerP1AttackPrepPriority(() -> step(() -> doAttackPhase(this::enterP2MainPhase2)));
		}
	}

	/**
	 * ATTACK → MAIN_2 for P2. One copy of what the two attack outcomes each used to spell out, so
	 * the skip check added for 16-037R Babus is made once and cannot drift between them.
	 */
	private void enterP2MainPhase2() {
		mw.gameState.advancePhase(); // ATTACK → MAIN_2
		mw.refreshPhaseTracker();
		mw.refreshCombatGlows();   // attack phase over — the exhausted mark comes off
		mw.logEntry("[P2] Main Phase 2");
		if (mw.consumePhaseSkip(false, GameState.GamePhase.MAIN_2)) {
			mw.logEntry("[P2] Main Phase 2 skipped");
			step(this::doEndPhase);
			return;
		}
		mw.autoAbilityTriggers.triggerAutoAbilitiesForBeginningOfMainPhase2(false);
		step(() -> doMainPhase(this::doEndPhase));
	}

	// ── Main Phase (shared for Main 1 and Main 2) ────────────────────────

	private void doMainPhase(Runnable onDone) {
		if (mw.gameState.isP1GameOver()) return;

		// Try LB plays first
		// One implementation of the LB play, shared with the human's dialog and with a networked
		// opponent's replay. This block used to be a third copy of it, keeping its own payment
		// record by hand and drifting: it never set lastCastPaymentDistinctElements, so a "CP of N
		// or more different Elements" gate read whatever the previous cast had left there.
		P2LbPlan lbPlan = findLbPlayPlan();
		if (lbPlan != null) {
			CardData card = mw.gameState.getP2LbDeck().get(lbPlan.castIdx());
			mw.logEntry("[P2] Plays LB \"" + card.name() + "\"");
			lbPlaysThisTurn++;
			mw.executeLbPlay(false, card, lbPlan.castIdx(), Set.copyOf(lbPlan.payment()),
					lbPlan.discardIndices(), lbPlan.dullBackups(), Map.of());
			step(() -> doMainPhase(onDone));
			return;
		}

		// Sync any "cast Forwards from BZ" field ability entries before attempting BZ plays.
		mw.syncBzForwardPlayables(false);

		// Try casting a Break-Zone-playable Summon (registered by a "Choose 1 [Element] Summon
		// in your Break Zone" effect) before normal hand plays — the discount makes it
		// strictly better value than discarding-for-CP from a fresh hand cast.
		if (tryP2BzPlay()) { step(() -> doMainPhase(onDone)); return; }

		// With a full hand, a card on the board beats anything an ability would buy: the hand is
		// about to cost cards at the end-phase limit of five, and the CP an ability spends is CP
		// the play needed. So while P2 holds five or more and has something it can cast, the
		// ability sweep is skipped and the play is made instead.
		//
		// Conditional on a play actually being available. A hand of five unaffordable cards is not
		// a reason to do nothing at all, and skipping the sweep outright would leave P2 passing
		// with abilities it could have used.
		P2Plan fullHandPlay = p2PrefersHandPlayOverAbilities() ? findPlayPlan() : null;
		if (fullHandPlay != null) {
			executeP2HandPlay(fullHandPlay);
			step(() -> doMainPhase(onDone));
			return;
		}

		// Try action abilities before committing to a hand play or passing. The resume is built
		// from onDone, not from the continuation below: an activation restarts the main phase, and
		// it has to restart carrying the callback that ends it.
		tryP2ActionAbilities(() -> {
			P2Plan plan = findPlayPlan();
			if (plan == null) {
				// P2 has no more plays — pass priority to P1
				mw.p2AutoPass(() -> mw.offerP1MainPhasePriority(onDone));
				return;
			}
			executeP2HandPlay(plan);
			step(() -> doMainPhase(onDone));
		}, () -> doMainPhase(onDone));
	}

	/** Executes a planned P2 hand-cast: dulls backups, discards for CP, pays cost, plays the card. */
	private void executeP2HandPlay(P2Plan plan) {
		if (plan.discardCast()) {
			// The same payment P1's menu item makes: the discards first, then a cast with no CP.
			CardData card = mw.gameState.getP2Hand().get(plan.cardIdx());
			mw.discardThenCastFree(false, card, plan.cardIdx(), plan.discardIndices());
			return;
		}
		if (plan.altPayment() != null) {
			// The cards handed over are the whole price: MainWindow.executeAltPlay puts them into
			// the Break Zone and plays the card, the same code a networked opponent's alternate
			// cast replays through, with nothing dulled or discarded for CP.
			CardData card = mw.gameState.getP2Hand().get(plan.cardIdx());
			mw.logEntry("[P2] Plays " + card.name() + " by putting " + plan.altPayment().putToBz().size()
					+ " of its cards into the Break Zone");
			mw.executeAltPlay(false, card, plan.cardIdx(), plan.altPayment(), List.of(), List.of(), Map.of(),
					null, false);
			return;
		}
		if (plan.warp()) {
			// executeWarpPlay pays the Warp cost itself — it dulls and discards, banks the CP and
			// clears it — so the ordinary payment step is skipped, and it takes the unadjusted hand
			// index because it shifts that index past its own discards. No break-for-CP payments:
			// the planner never produces one, so the map it would name is empty.
			mw.executeWarpPlay(false, mw.gameState.getP2Hand().get(plan.cardIdx()), plan.cardIdx(),
					plan.discardIndices(), plan.dullBackups(), plan.backupElements(), Map.of());
			return;
		}
		// What the payment produces, read before the discards leave the hand: the Element of CP
		// each dull and discard contributes, and the Element of the card that contributed it. The
		// same two records executePlay writes for a human cast — 6-074C Cactuar counts the first.
		// The plan's Element is the pool bucket the CP is deposited into, not always the CP made:
		// an off-colour source is booked to the card's own Element, and a generic one can be left
		// blank. Where the source cannot produce the booked Element, it paid its own.
		CardData casting = mw.gameState.getP2Hand().get(plan.cardIdx());
		Set<String> paidCp   = new LinkedHashSet<>();
		Set<String> paidFrom = new LinkedHashSet<>();
		for (int bi : plan.dullBackups()) {
			CardData bk  = mw.p2BackupCards[bi];
			String booked = plan.backupElements().get(bi);
			paidCp.add(booked != null && !booked.isEmpty() && p2BackupProduces(bk, booked, casting)
					? booked : bk.elements()[0]);
			paidFrom.add(bk.elements()[0]);
		}
		for (int di : plan.discardIndices()) {
			CardData d   = mw.gameState.getP2Hand().get(di);
			String booked = plan.discardElements().get(di);
			paidCp.add(booked != null && !booked.isEmpty() && d.containsElement(booked)
					? booked : d.elements()[0]);
			paidFrom.add(d.elements()[0]);
		}
		paidCp.removeIf(e -> e == null || e.isEmpty());
		paidFrom.removeIf(e -> e == null || e.isEmpty());

		mw.payP2CostViaBackupsAndDiscards(
				plan.dullBackups(),    plan.backupElements(),
				plan.discardIndices(), plan.discardElements());

		// Adjust source-card hand index for cards removed during discard
		int adjustedIdx = plan.cardIdx();
		for (int di : plan.discardIndices()) if (di < plan.cardIdx()) adjustedIdx--;

		Point handOrigin = mw.handCardOrigin(false, adjustedIdx);
		CardData toPlay = mw.gameState.removeP2FromHand(adjustedIdx);
		mw.refreshP2HandCountLabel();
		if (toPlay == null) return;

		String[] elems = toPlay.elements();
		boolean freeCast = plan.reducedCost() <= 0;
		if (!freeCast) {
			// The planned cost, not the printed one: the plan raised only what the reductions left.
			int remaining = plan.reducedCost();
			if (elems.length > 1) {
				for (String e : elems) { mw.gameState.spendP2Cp(e, 1); remaining--; }
			}
			for (String e : elems) {
				if (remaining <= 0) break;
				int avail = mw.gameState.getP2CpForElement(e);
				int toSpend = Math.min(remaining, avail);
				if (toSpend > 0) { mw.gameState.spendP2Cp(e, toSpend); remaining -= toSpend; }
			}
			for (String e : elems) mw.gameState.clearP2Cp(e);
		}

		mw.lastCastPaymentElements.clear();
		mw.lastCastActualPaymentElements.clear();
		if (!freeCast && !paidCp.isEmpty()) {
			mw.lastCastPaymentElements.addAll(paidCp);
			mw.lastCastActualPaymentElements.addAll(paidFrom);
		} else if (!freeCast) {
			// Paid wholly out of CP already in the pool, which records no source: the card's own
			// Elements are the most that payment can be said to have included.
			for (String e : elems) if (!e.isEmpty()) { mw.lastCastPaymentElements.add(e); mw.lastCastActualPaymentElements.add(e); }
		}
		mw.lastCastPaymentDistinctElements = mw.lastCastPaymentElements.size();
		mw.lastCastPaymentCard = toPlay;
		// See the note on the Limit Break path above: the pool payment names no Backups, and the
		// previous cast's record must not be left standing for a Backup-source gate to read.
		mw.lastCastPaymentBackups.clear();
		mw.lastCastWasPaidByBackupsOnly = false;
		mw.lastCastPaymentDiscardCount = 0;
		mw.lastCastPaymentDiscardTotalCost = 0;
		mw.lastCastPaymentDiscards.clear();

		mw.logEntry("[P2] Plays " + toPlay.name()
				+ (freeCast && mw.p2DoublecastFreeSummons ? " (free — Doublecast)"
						: plan.reducedCost() < toPlay.cost() ? " (cost " + plan.reducedCost() + ")" : ""));
		mw.lastCardWasCast = true;
		mw.noteCardCast(toPlay, false);
		if (toPlay.isSummon()) { mw.p2Turn.summonCastThisTurn = true; mw.noteDoublecastSummonCast(false, toPlay); }
		if (toPlay.isForward())      mw.placeFromHandWithAnim(toPlay, false, handOrigin, () -> mw.placeP2CardInForwardZone(toPlay));
		else if (toPlay.isBackup())  mw.placeFromHandWithAnim(toPlay, false, handOrigin, () -> mw.placeP2CardInFirstBackupSlot(toPlay));
		else if (toPlay.isMonster()) mw.placeFromHandWithAnim(toPlay, false, handOrigin, () -> mw.placeP2CardInMonsterZone(toPlay));
		else if (toPlay.isSummon())  mw.showSummonOnStack(toPlay, false);
		mw.lastCardWasCast = false;
	}

	// ── Attack Phase ─────────────────────────────────────────────────────

	/**
	 * Returns the slot codes ({@link ForwardTarget#slotCode()}) of P2 Characters to party-attack
	 * with, or null if a party attack offers no advantage. A party attack is chosen when the
	 * combined power of 2-3 attackers — Forwards, or Monsters and Backups acting as Forwards — can
	 * break a P1 forward that no single P2 attacker could kill alone.
	 */
	private List<Integer> p2ChoosePartyAttack() {
		List<Integer> attackable = new ArrayList<>();
		for (int i = 0; i < mw.p2ForwardStates.size(); i++)
			if (p2ForwardCanAttack(i)) attackable.add(ForwardTarget.slotCode(ForwardTarget.CardZone.FORWARD, i));
		for (int i = 0; i < mw.p2MonsterStates.size(); i++)
			if (mw.p2MonsterCanAttackAsForward(i)) attackable.add(ForwardTarget.slotCode(ForwardTarget.CardZone.MONSTER, i));
		for (int i = 0; i < mw.p2BackupCards.length; i++)
			if (mw.p2BackupCanAttackAsForward(i)) attackable.add(ForwardTarget.slotCode(ForwardTarget.CardZone.BACKUP, i));
		if (attackable.size() < 2) return null;

		for (int p1 = 0; p1 < mw.p1ForwardStates.size(); p1++) {
			CardState s = mw.p1ForwardStates.get(p1);
			if (s != CardState.ACTIVE) continue;
			int p1Hp = mw.effectiveP1ForwardPower(p1) - mw.p1ForwardDamage.get(p1);

			boolean canKillAlone = false;
			for (int code : attackable)
				if (mw.partyMemberPower(false, code) >= p1Hp) { canKillAlone = true; break; }
			if (canKillAlone) continue;

			// Try pairs
			for (int a = 0; a < attackable.size(); a++) {
				for (int b = a + 1; b < attackable.size(); b++) {
					List<Integer> pair = List.of(attackable.get(a), attackable.get(b));
					if (!mw.canFormValidParty(false, pair)) continue;
					if (mw.partyPower(false, pair) >= p1Hp) return pair;
				}
			}
			// Try triples
			for (int a = 0; a < attackable.size(); a++) {
				for (int b = a + 1; b < attackable.size(); b++) {
					for (int c = b + 1; c < attackable.size(); c++) {
						List<Integer> triple = List.of(attackable.get(a), attackable.get(b), attackable.get(c));
						if (!mw.canFormValidParty(false, triple)) continue;
						if (mw.partyPower(false, triple) >= p1Hp) return triple;
					}
				}
			}
		}
		return null;
	}

	private void doAttackPhase(Runnable onDone) {
		if (mw.gameState.isP1GameOver()) return;
		if (mw.attackDeclarationsExhausted(false)) {
			mw.logEntry("[P2] Attack declaration limit reached — ending attack phase.");
			onDone.run();
			return;
		}
		mw.pendingP2AttackerIsMonster = false;
		mw.pendingP2AttackerIsBackup  = false;
		mw.pendingP2AttackerPower     = 0;

		// Allow P2 to use Main-Phase-compatible abilities during the attack-preparation window
		// (attackSubStep == 0) before declaring any attacker.
		if (mw.attackSubStep == 0) {
			Runnable declareAttack = () -> doAttackPhaseInner(onDone);
			tryP2ActionAbilities(declareAttack, () -> doMainPhase(declareAttack));
			return;
		}
		doAttackPhaseInner(onDone);
	}

	private void doAttackPhaseInner(Runnable onDone) {
		if (mw.gameState.isP1GameOver()) return;

		List<Integer> party = p2ChoosePartyAttack();
		if (party != null) {
			mw.p2Turn.attackDeclarationsThisTurn++;
			mw.declareP2PartyAttack(party, () -> {
				if (!mw.gameState.isP1GameOver()) step(() -> doAttackPhase(onDone));
			});
			return;
		}

		for (int i = 0; i < mw.p2ForwardStates.size(); i++) {
			if (!p2ForwardCanAttack(i)) continue;
			mw.p2Turn.attackDeclarationsThisTurn++;
			CardData attacker = mw.p2ForwardPrimedTop.get(i) != null ? mw.p2ForwardPrimedTop.get(i) : mw.p2ForwardCards.get(i);
			CardState p2SingleBefore = mw.p2ForwardStates.get(i);
			if (!mw.effectiveP2HasTrait(i, CardData.Trait.BRAVE)) {
				mw.p2ForwardStates.set(i, CardState.DULL);
				mw.animateDullP2Forward(i, null);
				if (p2SingleBefore == CardState.ACTIVE)
					mw.autoAbilityTriggers.triggerAutoAbilitiesForBecomesDull(mw.p2ForwardCards.get(i), false);
			}
			mw.recordAttackDeclared(attacker);
			mw.autoAbilityTriggers.triggerAutoAbilitiesForAttack(attacker, false);
			final int fi = i;
			mw.initP1BlockDeclaration(attacker, fi, () -> {
				if (!mw.gameState.isP1GameOver()) step(() -> doAttackPhase(onDone));
			});
			return;
		}
		for (int i = 0; i < mw.p2MonsterStates.size(); i++) {
			if (!mw.p2MonsterCanAttackAsForward(i)) continue;
			mw.p2Turn.attackDeclarationsThisTurn++;
			CardData attacker = mw.p2MonsterCards.get(i);
			int power = mw.p2MonsterForwardPower(i);
			if (!mw.effectiveMonsterHasTrait(false, i, CardData.Trait.BRAVE)) {
				mw.p2MonsterStates.set(i, CardState.DULL);
				mw.animateDullP2Monster(i);
			}
			mw.recordAttackDeclared(attacker);
			mw.autoAbilityTriggers.triggerAutoAbilitiesForAttack(attacker, false);
			mw.pendingP2AttackerIsMonster = true;
			mw.pendingP2AttackerPower     = power;
			final int mi = i;
			mw.initP1BlockDeclaration(attacker, mi, () -> {
				if (!mw.gameState.isP1GameOver()) step(() -> doAttackPhase(onDone));
			});
			return;
		}
		for (int i = 0; i < mw.p2BackupCards.length; i++) {
			if (!mw.p2BackupCanAttackAsForward(i)) continue;
			mw.p2Turn.attackDeclarationsThisTurn++;
			CardData attacker = mw.p2BackupCards[i];
			int power = mw.p2BackupForwardPower(i);
			if (!mw.effectiveBackupHasTrait(false, i, CardData.Trait.BRAVE)) {
				mw.p2BackupStates[i] = CardState.DULL;
				mw.animateDullP2Backup(i, true);
			}
			mw.recordAttackDeclared(attacker);
			mw.autoAbilityTriggers.triggerAutoAbilitiesForAttack(attacker, false);
			mw.pendingP2AttackerIsBackup = true;
			mw.pendingP2AttackerPower    = power;
			final int bi = i;
			mw.initP1BlockDeclaration(attacker, bi, () -> {
				if (!mw.gameState.isP1GameOver()) step(() -> doAttackPhase(onDone));
			});
			return;
		}
		onDone.run();
	}

	// ── End Phase ────────────────────────────────────────────────────────

	private void doEndPhase() {
		List<CardData> hand = mw.gameState.getP2Hand();
		HeldCard held = heldBackAttack();
		while (hand.size() > 5) {
			// The held Back Attack Character is kept for P1's turn, not thrown away for the hand limit.
			List<CardData> pool = new ArrayList<>(hand);
			if (held != null && pool.size() > 1) pool.removeIf(c -> c == held.card());
			CardData worst = pool.get(pickWorstHandCard(pool));
			int idx = 0;
			while (hand.get(idx) != worst) idx++;   // by identity: two copies of a printing are equal
			CardData d = mw.gameState.discardP2FromHand(idx);
			if (d != null) mw.logEntry("[P2] End Phase — discards " + d.name());
		}
		mw.refreshP2BreakLabel();
		mw.refreshP2HandCountLabel();
		mw.turnPhases().runP2EndOfTurnCleanup();
		mw.gameState.advancePhase(); // MAIN_2 → END
		mw.refreshPhaseTracker();
		mw.logEntry("[P2] End Phase");
		// Wait for any end-of-turn auto abilities on the stack to resolve before returning priority to P1.
		step(() -> {
			// P2's turn is over — refresh their cast allowance for the turn now beginning, the
			// mirror of what MainWindow's End Phase does for P1.
			mw.p2Turn.resetCastTracking();
			mw.gameState.advancePhase(); // END → ACTIVE (switches to P1, increments turn)
			mw.refreshPhaseTracker();
			step(() -> mw.turnPhases().runP1TurnStart());  // expects phase == ACTIVE
		});
	}


	// ── Helpers ──────────────────────────────────────────────────────────

	/**
	 * Whether P2 would rather spend this main phase playing a card than activating an ability.
	 *
	 * <p>True from {@link #HAND_PLAY_PREFERRED_AT} cards in hand up. The caller still has to find
	 * a play it can afford — this says which it prefers, not that one exists.
	 */
	boolean p2PrefersHandPlayOverAbilities() {
		return mw.gameState.getP2Hand().size() >= HAND_PLAY_PREFERRED_AT;
	}

	private boolean p2ForwardCanAttack(int idx) {
		CardData fwd = mw.p2ForwardCards.get(idx);
		if (mw.p2CannotAttack.contains(fwd)) return false;
		if (mw.p2CannotAttackPersistent.contains(fwd)) return false;
		if (fwd.cannotAttackOrBlock()) return false;
		if (mw.isFieldAbilityCannotAttackOrBlock(fwd, false)) return false;
		CardState state = mw.p2ForwardStates.get(idx);
		if (state != CardState.ACTIVE) return false;
		if (!mw.hasAttackRemaining(mw.effectiveP2Forward(idx))) return false;
		return mw.effectiveP2HasTrait(idx, CardData.Trait.HASTE)
			|| mw.p2ForwardPlayedOnTurn.get(idx) != mw.gameState.getTurnNumber();
	}

	int pickWorstHandCard(List<CardData> hand) { return MainWindow.pickWorstHandCard0(hand); }

	/**
	 * Finds the best card P2 can play from hand, along with the minimum
	 * discards needed to afford it.
	 *
	 * @return {@code int[]} where {@code [0]} is the hand index of the card to
	 *         play and {@code [1..n]} are hand indices to discard first (sorted
	 *         ascending), or {@code null} if nothing is playable.
	 */
	/** Returns [castIdx, payment…] if any unspent LB card is affordable, else null. */
	/**
	 * P2's chosen LB play: which card to play, which face-down LB cards to turn face up paying its
	 * LB cost, and the Backups and hand cards that raise its CP cost.
	 */
	record P2LbPlan(int castIdx, List<Integer> payment,
			List<Integer> dullBackups, List<Integer> discardIndices) {}

	/**
	 * P2's LB play, or null when nothing in the LB deck can be played.
	 *
	 * <p>The CP cost is raised the way every other P2 play raises one — through
	 * {@link #p2PlanPayment}, which dulls Backups and discards for CP. It used to be tested
	 * against banked CP alone:
	 *
	 * <pre>if (mw.gameState.getP2CpForElement(element) &lt; card.cost()) continue;</pre>
	 *
	 * <p>which no LB card could ever pass. P2 banks no standing CP — it generates CP only while
	 * paying for a play it has already chosen, and spends and clears it in the same step — so the
	 * pool is empty every time, and this is the first thing the main phase looks at. That, and not
	 * anything about the LB deck, is why P2 never played an LB card.
	 *
	 * <p>Printed cost rather than an effective one, which is what {@link #findPlayPlan} passes for
	 * a hand cast too. P2 ignoring its own cost reductions is a standing simplification of this
	 * planner; making the LB path alone honour them would make P2 play LB cards under discounts it
	 * would not use on anything else.
	 */
	P2LbPlan findLbPlayPlan() {
		// This planner's own allowance, not a rule — see MAX_LB_PLAYS_PER_TURN. Asked before any
		// of the rules below, because once it is spent none of them can change the answer.
		if (lbPlaysThisTurn >= MAX_LB_PLAYS_PER_TURN) return null;
		// An LB card is cast, so a cast limit binds it — "you can only cast 2 cards per turn", and
		// the effects that forbid casting outright. Checked here as findPlayPlan checks it for a
		// hand cast. It was missing while no LB play could happen at all; it matters now that one
		// can.
		if (mw.p2CastLimitReached()) return null;
		List<CardData> lbDeck = mw.gameState.getP2LbDeck();
		boolean p2HasLD = mw.hasLightOrDarkOnField(false);
		for (int i = 0; i < lbDeck.size(); i++) {
			if (mw.p2SpentLbIndices.contains(i)) continue;
			CardData card = lbDeck.get(i);
			if (card.isSummon()) continue; // skip summons — no simple board placement
			if (!card.multicard() && mw.hasUniquenessClashOnField(card, false)) continue;
			if (card.isLightOrDark() && p2HasLD) continue;
			if (card.isBackup() && !mw.p2HasAvailableBackupSlot()) continue;
			if (!mw.castRestrictionMet(card, false)) continue;
			// Face-down LB cards available to pay the LB cost, this one excluded.
			List<Integer> available = new ArrayList<>();
			for (int j = 0; j < lbDeck.size(); j++) {
				if (j != i && !mw.p2SpentLbIndices.contains(j)) available.add(j);
			}
			if (available.size() < card.lbCost()) continue;

			List<Integer>        backups      = new ArrayList<>();
			Map<Integer, String> backupElems  = new LinkedHashMap<>();
			List<Integer>        discards     = new ArrayList<>();
			Map<Integer, String> discardElems = new LinkedHashMap<>();
			// No hand index to exclude: an LB card is played out of the LB deck, so every card in
			// hand is available to be discarded for CP.
			// Priced as P1's LB cast is, so a field reduction lightens this payment too.
			if (!p2PlanPayment(card, mw.castCostFor(card, false), -1, backups, backupElems, discards, discardElems))
				continue;

			return new P2LbPlan(i, List.copyOf(available.subList(0, card.lbCost())),
					List.copyOf(backups), List.copyOf(discards));
		}
		return null;
	}

	/**
	 * P2's chosen play for one cast: which card (hand idx for a hand-cast; -1 with
	 * {@code bzCard} set for a Break-Zone cast), what reduced cost to pay, and the
	 * chosen backup-dull / hand-discard contributions toward that cost.
	 */
	private record P2Plan(
			int cardIdx,                                  // -1 = BZ-cast; otherwise index into P2 hand
			int reducedCost,                              // effective cost after discounts
			List<Integer>          dullBackups,           // P2 backup indices to dull (1 CP each)
			Map<Integer, String>   backupElements,        // backup idx → element of CP contributed
			List<Integer>          discardIndices,        // P2 hand indices to discard (2 CP each)
			Map<Integer, String>   discardElements,       // hand idx → element of CP contributed
			boolean                warp,                  // pay warpCost() and bank the card rather than cast it
			AltPayment             altPayment,            // cast under an alternate cost instead; null = none
			boolean                discardCast            // discardIndices are a discard-to-cast cost, not CP
	) {
		/** An ordinary cast or a Warp, with no alternate cost. */
		P2Plan(int cardIdx, int reducedCost, List<Integer> dullBackups, Map<Integer, String> backupElements,
				List<Integer> discardIndices, Map<Integer, String> discardElements, boolean warp) {
			this(cardIdx, reducedCost, dullBackups, backupElements, discardIndices, discardElements, warp, null, false);
		}

		/** A cast under an alternate cost that hands cards over. */
		P2Plan(int cardIdx, int reducedCost, List<Integer> dullBackups, Map<Integer, String> backupElements,
				List<Integer> discardIndices, Map<Integer, String> discardElements, boolean warp,
				AltPayment altPayment) {
			this(cardIdx, reducedCost, dullBackups, backupElements, discardIndices, discardElements, warp,
					altPayment, false);
		}

		/** A discard-to-cast: {@code discards} are the whole price (King 9-010R, 30-001H Auron). */
		static P2Plan discardCast(int cardIdx, List<Integer> discards) {
			return new P2Plan(cardIdx, 0, List.of(), Map.of(), discards, Map.of(), false, null, true);
		}
	}

	/**
	 * Whether P2 currently has any legal hand-cast available. Exposed for tests, which need to
	 * assert on the planner's legality filtering without naming the private plan record.
	 */
	boolean hasLegalHandCast() {
		return findPlayPlan() != null;
	}

	/**
	 * Plans and performs a Warp cast for P2 if one is available, returning whether it did. The
	 * main phase reaches this route through {@link #findPlayPlan}, which tries it only once the
	 * ordinary casts are exhausted; this seam lets tests drive it on its own without naming the
	 * private plan record.
	 */
	boolean warpCastIfAble() {
		P2Plan plan = findWarpPlan();
		if (plan == null) return false;
		executeP2HandPlay(plan);
		return true;
	}

	/**
	 * Plans and performs P2's next hand cast, returning whether it made one — the step the main
	 * phase takes after its ability sweep. A seam for tests, like {@link #warpCastIfAble}, so a
	 * cast's payment record can be checked without driving the timer-paced main phase.
	 */
	boolean castFromHandIfAble() {
		P2Plan plan = findPlayPlan();
		if (plan == null) return false;
		executeP2HandPlay(plan);
		return true;
	}

	private P2Plan findPlayPlan() {
		if (mw.p2CastLimitReached()) return null;
		List<CardData> hand = mw.gameState.getP2Hand();
		if (hand.isEmpty()) return null;

		boolean p2HasLD = mw.hasLightOrDarkOnField(false);
		// Kept for P1's turn, with the Backups to cast it then (decision 4); and the trick kept for
		// P2's attacks (heldTrick).
		HeldCard held = heldBackAttack();
		CardData heldCard = held != null ? held.card() : null;
		HeldCard trick = heldTrick();
		CardData heldTrickCard = trick != null ? trick.card() : null;

		// Forwards and Monsters — highest cost first
		List<Integer> fieldCands = new ArrayList<>();
		for (int i = 0; i < hand.size(); i++) {
			CardData c = hand.get(i);
			if (c == heldCard) continue;
			if (!c.isForward() && !c.isMonster()) continue;
			if (!c.multicard() && mw.hasUniquenessClashOnField(c, false)) continue;
			if (c.isLightOrDark() && p2HasLD) continue;
			if (!mw.castRestrictionMet(c, false)) continue;
			fieldCands.add(i);
		}
		fieldCands.sort((a, b) -> hand.get(b).cost() - hand.get(a).cost());

		// Summons — highest cost first; skip while Summon casting is banned outright, by a field
		// ability or for the turn. The per-card check below asks the same question again, so this
		// is only the short-circuit; dropping it would cost the plan, not its correctness.
		List<Integer> summonCands = new ArrayList<>();
		if (!mw.summonCastingBanned(false)) {
			boolean p1HasAutoAbilityOnStack = mw.gameState.getStack().stream()
					.anyMatch(e -> e.isAutoAbility() && e.isP1());
			for (int i = 0; i < hand.size(); i++) {
				CardData c = hand.get(i);
				if (!c.isSummon() || c == heldTrickCard) continue;
				if (ActionResolver.cancelsAutoAbility(c.summonEffect()) && !p1HasAutoAbilityOnStack) continue;
				if (!mw.castRestrictionMet(c, false)) continue;
				// The rule: a mandatory "Choose 1 …" with nothing to choose makes the cast illegal.
				// summonHasSomethingToHit below is the separate question of whether casting is worth
				// it, and answers it for texts this one deliberately passes.
				if (mw.summonCastBlocked(c, false)) continue;
				if (!summonHasSomethingToHit(c)) continue;
				summonCands.add(i);
			}
			summonCands.sort((a, b) -> hand.get(b).cost() - hand.get(a).cost());
		}

		// Backups — highest cost first
		List<Integer> backupCands = new ArrayList<>();
		for (int i = 0; i < hand.size(); i++) {
			CardData c = hand.get(i);
			if (c == heldCard) continue;
			if (!c.isBackup() || !mw.p2HasAvailableBackupSlot()) continue;
			if (!c.multicard() && mw.hasUniquenessClashOnField(c, false)) continue;
			if (c.isLightOrDark() && p2HasLD) continue;
			if (!mw.castRestrictionMet(c, false)) continue;
			backupCands.add(i);
		}
		backupCands.sort((a, b) -> hand.get(b).cost() - hand.get(a).cost());

		// Doublecast: any hand Summon with printed cost under the current threshold casts free —
		// take the most expensive one first (keeps the chain of successively lower costs alive)
		// before spending backups/discards on anything else.
		if (mw.p2DoublecastFreeSummons && mw.p2DoublecastLastSummonCost >= 0) {
			for (int i : summonCands) {
				if (hand.get(i).cost() < mw.p2DoublecastLastSummonCost)
					return new P2Plan(i, 0, List.of(), Map.of(), List.of(), Map.of(), false);
			}
		}

		List<Integer> candidates = new ArrayList<>(fieldCands);
		candidates.addAll(summonCands);
		candidates.addAll(backupCands);

		for (int cardIdx : candidates) {
			CardData card = hand.get(cardIdx);
			// Skip cards with a "reveal summons" ETF ability when no summons are available in hand.
			if (AutoAbilityTriggers.hasRevealSummonsConditionalEtf(card)) {
				boolean hasSummon = false;
				for (int j = 0; j < hand.size(); j++) {
					if (j == cardIdx) continue;
					if (hand.get(j).isSummon()) { hasSummon = true; break; }
				}
				if (!hasSummon) continue;
			}
			List<Integer>        backups       = new ArrayList<>();
			Map<Integer, String> backupElems   = new LinkedHashMap<>();
			List<Integer>        discards      = new ArrayList<>();
			Map<Integer, String> discardElems  = new LinkedHashMap<>();
			// Priced as P1's hand is: the card's own modifiers and P2's field reductions.
			int cost = mw.castCostFor(card, false);
			if (p2PlanPayment(card, cost, cardIdx, backups, backupElems, discards, discardElems)) {
				return new P2Plan(cardIdx, cost, backups, backupElems, discards, discardElems, false);
			}
			// P2 could not raise the CP. A card printing a put-into-Break-Zone alternate cost has a
			// second route onto the field that costs no CP at all.
			AltPayment alt = card.altPutToBzCost() != null ? p2PlanAltPutToBzCost(card) : null;
			if (alt != null) return new P2Plan(cardIdx, 0, List.of(), Map.of(), List.of(), Map.of(), false, alt);
			// A third: a discard-to-cast cost, whose cards are the whole price.
			List<Integer> discardCast = p2PlanDiscardCast(card, cardIdx);
			if (discardCast != null) return P2Plan.discardCast(cardIdx, discardCast);
		}
		// Nothing casts outright. A Warp card offers a second, usually far cheaper route: pay the
		// Warp cost now and the card enters the field by itself once its counters run out. Tried
		// only after the ordinary plays are exhausted, so warping spends what is left over rather
		// than competing with a card that would reach the field this turn.
		return findWarpPlan();
	}

	/**
	 * P2's Warp cast, or null when no card in hand can be warped.
	 *
	 * <p>Gated like the P1 hand menu's Warp item: the cast-timing restriction and the cast limit
	 * (checked by the caller) apply, but the name-uniqueness and Light/Dark field rules do not — a
	 * Warp cast puts the card in the Removed-From-Play zone rather than on the field, so those are
	 * the business of the later turn when its counters run out and it enters.
	 *
	 * <p>Prefers the highest printed cost among the affordable ones. A Warp cost bears little
	 * relation to the printed one, so printed cost stands in for what the card is worth once it
	 * lands.
	 */
	private P2Plan findWarpPlan() {
		List<CardData> hand = mw.gameState.getP2Hand();
		List<Integer> cands = new ArrayList<>();
		for (int i = 0; i < hand.size(); i++) {
			CardData c = hand.get(i);
			if (!c.hasWarp()) continue;
			if (mw.summonCastBlocked(c, false)) continue;
			if (!mw.castRestrictionMet(c, false)) continue;
			cands.add(i);
		}
		cands.sort((a, b) -> hand.get(b).cost() - hand.get(a).cost());

		for (int cardIdx : cands) {
			List<Integer>        backups     = new ArrayList<>();
			Map<Integer, String> backupElems = new LinkedHashMap<>();
			List<Integer>        discards    = new ArrayList<>();
			if (p2PlanWarpPayment(hand.get(cardIdx), cardIdx, backups, backupElems, discards))
				return new P2Plan(cardIdx, 0, backups, backupElems, discards, Map.of(), true);
		}
		return null;
	}

	/**
	 * Plans P2's payment of {@code card}'s Warp cost into the out-params — backup slots to dull
	 * (1 CP each, with the element each is booked to) and hand indices to discard (2 CP each).
	 * Returns false, leaving the out-params in an unspecified state, when the cost cannot be
	 * raised.
	 *
	 * <p>A Warp cost is a list of per-element requirements plus generic entries rather than a
	 * single number, which is why it needs its own planner instead of {@link #p2PlanPayment}: only
	 * a Backup carrying the element settles an element requirement, while any Backup settles a
	 * generic one. Element requirements are therefore filled first — spending a matching Backup on
	 * the generic part would strand the requirement it was the only source for.
	 *
	 * <p>Banked CP counts toward an element requirement because
	 * {@link MainWindow#executeP2WarpPlay} clears that element's pool as part of the payment. CP
	 * banked in an element the cost does not name is left alone there, so it is not counted here
	 * either — the effect is that P2 warps slightly less often than the rules would strictly
	 * allow, which is the safe direction to be wrong in.
	 */
	private boolean p2PlanWarpPayment(CardData card, int handIdx,
			List<Integer> outBackups, Map<Integer, String> outBackupElems, List<Integer> outDiscards) {
		List<String> warpCost = card.warpCost();
		if (warpCost.isEmpty()) return true;   // 《0》 — Mist 20-115R, Tidus 24-048L

		// A Fina-style grant ("Your Warp cost can be paid with CP of any Element") collapses every
		// element requirement into a generic one.
		boolean anyElement = mw.warpCostAnyElement(false);
		LinkedHashMap<String, Integer> need = new LinkedHashMap<>();
		int generic = 0;
		for (String e : warpCost) {
			if (anyElement || e.isEmpty()) generic++;
			else need.merge(e, 1, Integer::sum);
		}
		for (Map.Entry<String, Integer> en : need.entrySet())
			en.setValue(Math.max(0, en.getValue() - mw.gameState.getP2CpForElement(en.getKey())));

		CardData[] backups = p2CpBackups();
		List<CardData> kept = keptInHand();
		String[] costElems = need.keySet().toArray(String[]::new);
		Set<String> ldGrants = mw.lightDarkDiscardGrants(false);
		List<CardData> hand = mw.gameState.getP2Hand();

		// Element requirements: matching Backups first (a dull costs P2 nothing it keeps), then
		// matching discards. A discard books its whole 2 CP to one element via
		// CpPaymentUtils.contributingElement, so an odd CP is wasted rather than spilling into the
		// generic part — the plan is scored the same way so it cannot over-count.
		for (Map.Entry<String, Integer> en : need.entrySet()) {
			String elem = en.getKey();
			int deficit = en.getValue();
			if (deficit <= 0) continue;

			List<Integer> matching = new ArrayList<>();
			for (int bi = 0; bi < backups.length; bi++)
				if (p2BackupCanPay(backups, bi) && !outBackups.contains(bi)
						&& mw.effectiveContainsElement(backups[bi], elem)) matching.add(bi);
			// Least versatile first, so a mono-element Backup is spent before a dual one that a
			// later requirement may be the only other source for.
			matching.sort(Comparator.comparingInt(bi ->
					(int) Arrays.stream(costElems)
							.filter(e -> mw.effectiveContainsElement(backups[bi], e)).count()));
			for (int bi : matching) {
				if (deficit <= 0) break;
				outBackups.add(bi);
				outBackupElems.put(bi, elem);
				deficit--;
			}

			List<Integer> discardable = new ArrayList<>();
			for (int i = 0; i < hand.size(); i++)
				if (i != handIdx && !outDiscards.contains(i) && hand.get(i).containsElement(elem)
						&& !isKept(kept, hand.get(i)) && CpPaymentUtils.canDiscardForCp(hand.get(i), ldGrants)) discardable.add(i);
			discardable.sort((a, b) -> hand.get(a).cost() - hand.get(b).cost());
			for (int di : discardable) {
				if (deficit <= 0) break;
				outDiscards.add(di);
				deficit -= 2;
			}
			if (deficit > 0) return false;
		}

		// Generic requirements: any remaining Backup, then any remaining discardable hand card.
		for (int bi = 0; bi < backups.length && generic > 0; bi++) {
			if (!p2BackupCanPay(backups, bi) || outBackups.contains(bi)) continue;
			outBackups.add(bi);
			outBackupElems.put(bi, "");
			generic--;
		}
		for (int i = 0; i < hand.size() && generic > 0; i++) {
			if (i == handIdx || outDiscards.contains(i) || isKept(kept, hand.get(i))) continue;
			if (!CpPaymentUtils.canDiscardForCp(hand.get(i), ldGrants)) continue;
			outDiscards.add(i);
			generic -= 2;
		}
		return generic <= 0;
	}

	/** Whether P2's Backup in {@code slot} can currently be dulled for CP. */
	private boolean p2BackupCanPay(CardData[] backups, int slot) {
		return backups[slot] != null && mw.p2BackupStates[slot] == CardState.ACTIVE
				&& !mw.p2BackupFrozen[slot];
	}

	/**
	 * Whether casting {@code summon} would accomplish anything, given what is on the board.
	 *
	 * <p>A Summon whose whole effect is "Choose 1 Forward. Deal it 4000 damage." resolves into an
	 * empty row as a no-op: the CP is spent, the card goes to the Break Zone, and nothing happens.
	 * Left unchecked P2 casts such a Summon every turn it can afford one, because nothing else in
	 * the plan looks at whether the effect has anywhere to land.
	 *
	 * <p>Only Summons that need a target are held back — see
	 * {@link ActionResolver#summonTargetRequirement}, which reports nothing for a Summon that is
	 * worth casting regardless, and for one it cannot read.
	 */
	private boolean summonHasSomethingToHit(CardData summon) {
		ActionResolver.SummonTargetNeed need =
				ActionResolver.summonTargetRequirement(summon.summonEffect());
		if (need == null) return true;
		// "you control" / "opponent controls" are read from P2's seat, since P2 is the caster.
		boolean own = !need.oppOnly() && countFieldCards(false, need) > 0;
		boolean opp = !need.ownOnly() && countFieldCards(true,  need) > 0;
		return own || opp;
	}

	/** Counts the cards on one player's field that would answer {@code need}. */
	private int countFieldCards(boolean isP1, ActionResolver.SummonTargetNeed need) {
		int n = 0;
		if (need.forwards()) n += (isP1 ? mw.p1ForwardCards : mw.p2ForwardCards).size();
		if (need.monsters()) n += (isP1 ? mw.p1MonsterCards : mw.p2MonsterCards).size();
		if (need.backups())
			for (CardData b : isP1 ? mw.p1BackupCards : mw.p2BackupCards) if (b != null) n++;
		return n;
	}

	/**
	 * If P2 has any Break-Zone-playable Summon (registered by an effect like
	 * "Choose 1 [Element] Summon in your Break Zone") that they can afford at the reduced
	 * cost via backup-dulling and/or hand discards, picks the most expensive one (best
	 * discount value) and executes the cast.  Returns {@code true} if a cast was performed.
	 */
	private boolean tryP2BzPlay() {
		if (mw.bzPlayableP2.isEmpty()) return false;
		if (mw.p2CastLimitReached()) return false;

		// Borrowed casts from any source zone (Break Zone or removed-from-game), most expensive first.
		List<Map.Entry<CardData, PlayableEntry>> entries = new ArrayList<>(mw.bzPlayableP2.entrySet());
		entries.sort((a, b) -> b.getKey().cost() - a.getKey().cost());

		for (Map.Entry<CardData, PlayableEntry> entry : entries) {
			CardData card = entry.getKey();
			PlayableEntry pe = entry.getValue();
			if (!mw.borrowedHandConditionMet(card, false)) continue;
			int reducedCost = mw.borrowedCastCost(card, pe, false);

			// Respect uniqueness / Light-Dark / backup-slot legality so borrowed casts can't create field collisions.
			boolean isChar = card.isForward() || card.isBackup() || card.isMonster();
			if (isChar && !card.multicard() && mw.hasUniquenessClashOnField(card, false)) continue;
			if (isChar && mw.isP2LightDarkConflict(card)) continue;
			if (card.isBackup() && !mw.p2HasAvailableBackupSlot()) continue;
			if (mw.summonCastBlocked(card, false)) continue;

			List<Integer>        backups      = new ArrayList<>();
			Map<Integer, String> backupElems  = new LinkedHashMap<>();
			List<Integer>        discards     = new ArrayList<>();
			Map<Integer, String> discardElems = new LinkedHashMap<>();

			if (!pe.freeCast() && reducedCost > 0) {
				boolean affordable = pe.anyElement()
						? p2PlanPaymentAnyElement(card, reducedCost, backups, backupElems, discards, discardElems)
						: p2PlanPayment(card, reducedCost, -1, backups, backupElems, discards, discardElems);
				if (!affordable) continue;
			}

			mw.executePlayFromBzP2(card, pe, reducedCost, discards, discardElems, backups, backupElems);
			return true;
		}
		return false;
	}

	/**
	 * Payment planner for an any-element borrowed cast: any CP (active backup or hand discard)
	 * counts toward {@code cost} with no per-element minimums.  Records the chosen backups/discards
	 * (each tagged with its own element so payment deposits real CP); {@link MainWindow#executePlayFromBzP2}
	 * then drains CP across all elements.  Returns {@code true} when the cost can be covered.
	 */
	private boolean p2PlanPaymentAnyElement(CardData card, int cost,
			List<Integer> outBackups, Map<Integer, String> outBackupElems,
			List<Integer> outDiscards, Map<Integer, String> outDiscardElems) {
		int total = 0;
		for (String e : Elements.ALL) total += mw.gameState.getP2CpForElement(e);
		if (total >= cost) return true;

		// Any Element may pay, but a Backups-only card still refuses discards and off-Element Backups.
		String backupElemOnly = card.cpBackupElement();
		CardData[] payable = p2CpBackups();
		for (int bi = 0; bi < payable.length && total < cost; bi++) {
			CardData bk = payable[bi];
			if (bk == null || mw.p2BackupStates[bi] != CardState.ACTIVE || mw.p2BackupFrozen[bi]) continue;
			if (!mw.costs.backupMayPay(bk, backupElemOnly)) continue;
			outBackups.add(bi);
			outBackupElems.put(bi, bk.elements()[0]);
			total += 1;
		}
		if (total >= cost || backupElemOnly != null) return total >= cost;

		List<CardData> hand = mw.gameState.getP2Hand();
		Set<String> ldGrants = mw.lightDarkDiscardGrants(false);
		List<CardData> kept = keptInHand();
		List<Integer> discardable = new ArrayList<>();
		for (int i = 0; i < hand.size(); i++)
			if (!isKept(kept, hand.get(i)) && CpPaymentUtils.canDiscardForCp(hand.get(i), ldGrants)) discardable.add(i);
		discardable.sort((a, b) -> hand.get(a).cost() - hand.get(b).cost());
		for (int di : discardable) {
			if (total >= cost) break;
			outDiscards.add(di);
			outDiscardElems.put(di, hand.get(di).elements()[0]);
			total += 2;
		}
		return total >= cost;
	}

	/**
	 * Greedy payment planner for P2: starting from P2's current CP, tries dulling eligible
	 * backups (preferring less-versatile ones first) and then discarding hand cards
	 * (cheapest matching-element first) until the simulated CP covers {@code reducedCost}.
	 * Returns {@code true} when affordable; the chosen plan is written into the four
	 * out-parameters.  Off-color backups and hand discards are used last: their CP counts
	 * toward the total but cannot satisfy per-element minimums.  {@code excludeHandIdx == -1}
	 * means no exclusion (used by BZ-cast where the source isn't in hand).
	 */
	/**
	 * P2's payment for {@code card}'s put-into-Break-Zone alternate cost, or {@code null} when it
	 * should not take that route: "You can put a total of 3 Forwards or Monsters you control into
	 * the Break Zone to play Kefka from your hand onto the field." (Kefka 4-080L, the only such
	 * card). Asked only once P2 cannot raise the CP: the cost replaces the CP cost outright, so
	 * the question is never whether P2 can pay but whether it should.
	 *
	 * <p>Which cards: the cheapest that may pay, by printed cost
	 * ({@link MainWindow#aiCheapestOwnTargets}), the measure every other payment the AI makes with
	 * its own cards uses.
	 *
	 * <p>Whether at all, in the same measure. P2 gives up those cards' printed cost and gains the
	 * card itself plus the opposing Forward or Monster its arrival breaks. That gain is read as the
	 * cheapest one there, since the AI's pick among them is not otherwise steered. So the route is
	 * taken only when the arrival has something to break and the gain outweighs what is handed
	 * over; three cards for one body and nothing else is never worth it.
	 */
	/**
	 * The hand cards P2 would discard to cast {@code card} (at {@code cardIdx}) for free under a
	 * discard-to-cast cost — the least valuable that qualify, as every other AI discard picks — or
	 * {@code null} when no such cost is open to it. Only reached once the CP route has failed, so a
	 * card P2 can pay for is never bought with cards instead.
	 */
	List<Integer> p2PlanDiscardCast(CardData card, int cardIdx) {
		List<CardData> hand = mw.gameState.getP2Hand();
		for (MainWindow.FieldDiscardCastEntry grant : mw.findDiscardCastGrants(card, false)) {
			List<Integer> pool = new ArrayList<>(mw.discardCastEligible(grant, cardIdx, false));
			if (pool.size() < grant.count()) continue;
			List<Integer> picks = new ArrayList<>();
			while (picks.size() < grant.count()) {
				List<CardData> cards = new ArrayList<>();
				for (int i : pool) cards.add(hand.get(i));
				picks.add(pool.remove(MainWindow.pickWorstHandCard0(cards)));
			}
			return picks;
		}
		return null;
	}

	AltPayment p2PlanAltPutToBzCost(CardData card) {
		CardData.AltPutToBzCost cost = card.altPutToBzCost();
		if (cost == null || !entersBreakingAnOpposingCharacter(card)) return null;
		int cheapestOpposing = Integer.MAX_VALUE;
		for (CardData c : mw.p1ForwardCards) cheapestOpposing = Math.min(cheapestOpposing, c.cost());
		for (CardData c : mw.p1MonsterCards) cheapestOpposing = Math.min(cheapestOpposing, c.cost());
		if (cheapestOpposing == Integer.MAX_VALUE) return null;
		List<ForwardTarget> eligible = mw.altPutToBzCandidates(cost, false);
		if (eligible.size() < cost.count()) return null;
		List<ForwardTarget> picks = mw.aiCheapestOwnTargets(eligible, cost.count(), null);
		int givenUp = 0;
		for (ForwardTarget t : picks) givenUp += mw.autoAbilityTriggers.fieldCardData(t).cost();
		if (givenUp >= card.cost() + cheapestOpposing) return null;
		return new AltPayment(0, List.of(), List.of(), picks, List.of());
	}

	/** Whether {@code card}'s own arrival breaks a card its controller's opponent controls — Kefka 4-080L's. */
	private static boolean entersBreakingAnOpposingCharacter(CardData card) {
		for (AutoAbility fa : card.autoAbilities()) {
			if (!fa.triggerCard().equalsIgnoreCase(card.name()) || !fa.trigger().contains("enter")) continue;
			if (ENTERS_BREAKING_OPPOSING.matcher(fa.effectText()).find()) return true;
		}
		return false;
	}

	private static final Pattern ENTERS_BREAKING_OPPOSING =
			Pattern.compile("(?i)\\bopponent\\s+controls\\.\\s+Break\\s+it\\b");

	private boolean p2PlanPayment(CardData card, int reducedCost, int excludeHandIdx,
			List<Integer> outBackups, Map<Integer, String> outBackupElems,
			List<Integer> outDiscards, Map<Integer, String> outDiscardElems) {
		return p2PlanPayment(card, reducedCost, excludeHandIdx, -1,
				outBackups, outBackupElems, outDiscards, outDiscardElems);
	}

	/**
	 * Variant of {@link #p2PlanPayment} with a second excluded hand index —
	 * {@code reservedHandIdx} is held back from discard-for-CP (e.g. a same-name copy reserved
	 * to pay a special ability's 《S》 discard cost). Pass -1 to reserve nothing.
	 */
	boolean p2PlanPayment(CardData card, int reducedCost, int excludeHandIdx, int reservedHandIdx,
			List<Integer> outBackups, Map<Integer, String> outBackupElems,
			List<Integer> outDiscards, Map<Integer, String> outDiscardElems) {
		// The printed payment restrictions, read as the payment dialog and P1's affordability check
		// read them. "You can only pay with Lightning CP to cast Sakura." leaves Lightning the only
		// Element and no off-Element source; "… produced by [Lightning] Backups …" rules out every
		// discard and, when an Element is named, every Backup not of it.
		String   elemOnly       = card.castElementOnly();
		String[] elems          = elemOnly != null ? new String[]{ elemOnly } : card.elements();
		boolean  offElementPays = elemOnly == null;
		String   backupElemOnly = card.cpBackupElement();
		boolean  discardsPay    = backupElemOnly == null;
		int[] simCp = new int[elems.length];
		for (int ei = 0; ei < elems.length; ei++)
			simCp[ei] = mw.gameState.getP2CpForElement(elems[ei]);
		int anyCp = 0;
		// Every cast needs at least 1 CP of each of the card's Elements, except a Light or Dark card
		// and a card a field ability lets be paid with any Element (26-059R Llymlaen), which any CP
		// pays for — unless it is held to one Element as well.
		boolean needsEachElement = !(card.isLightOrDark() || mw.isAnyElementCast(card, false)) || !offElementPays;

		if (p2CanAfford(reducedCost, elems, simCp, anyCp, needsEachElement)) return true;

		// Phase 1a: dull backups whose element matches at least one required element.
		// Prefer less-versatile (fewer matching elements) backups first.
		List<Integer> matchingBackups = new ArrayList<>();
		List<Integer> offColorBackups = new ArrayList<>();
		CardData[] payable = p2CpBackups();
		for (int bi = 0; bi < payable.length; bi++) {
			CardData bk = payable[bi];
			if (bk == null) continue;
			if (mw.p2BackupStates[bi] != CardState.ACTIVE) continue;
			if (mw.p2BackupFrozen[bi]) continue;
			if (!mw.costs.backupMayPay(bk, backupElemOnly)) continue;
			boolean matches = false;
			for (String e : elems) if (p2BackupProduces(bk, e, card)) { matches = true; break; }
			if (matches) matchingBackups.add(bi);
			else if (offElementPays) offColorBackups.add(bi);
		}
		matchingBackups.sort(Comparator.comparingInt(bi ->
				(int) Arrays.stream(elems)
						.filter(e -> p2BackupProduces(payable[bi], e, card)).count()));
		for (int bi : matchingBackups) {
			if (p2CanAfford(reducedCost, elems, simCp, anyCp, needsEachElement)) break;
			CardData bk = payable[bi];
			int ei = p2BestElementFor(e -> p2BackupProduces(bk, e, card), elems, simCp);
			simCp[ei] += 1;
			outBackups.add(bi);
			outBackupElems.put(bi, elems[ei]);
		}
		if (p2CanAfford(reducedCost, elems, simCp, anyCp, needsEachElement)) return true;

		// Phase 1b: dull off-color backups — their CP counts toward total but not per-element
		// minimums.  Assign to elems[0] so payP2CostViaBackupsAndDiscards deposits correctly.
		for (int bi : offColorBackups) {
			if (p2CanAfford(reducedCost, elems, simCp, anyCp, needsEachElement)) break;
			anyCp += 1;
			outBackups.add(bi);
			outBackupElems.put(bi, elems[0]);
		}
		if (p2CanAfford(reducedCost, elems, simCp, anyCp, needsEachElement)) return true;

		// Phase 2: discard cheapest matching-element hand cards (Light/Dark only via field grant).
		List<CardData> hand = mw.gameState.getP2Hand();
		Set<String> ldGrants = mw.lightDarkDiscardGrants(false);
		List<Integer> discardable = new ArrayList<>();
		List<Integer> offColorDiscards = new ArrayList<>();
		List<CardData> kept = keptInHand();
		for (int i = 0; i < hand.size() && discardsPay; i++) {
			if (i == excludeHandIdx || i == reservedHandIdx) continue;
			CardData c = hand.get(i);
			if (isKept(kept, c)) continue;
			if (!CpPaymentUtils.canDiscardForCp(c, ldGrants)) continue;
			boolean matches = false;
			for (String e : elems) if (c.containsElement(e)) { matches = true; break; }
			if (matches)             discardable.add(i);
			else if (offElementPays) offColorDiscards.add(i);
		}
		discardable.sort((a, b) -> hand.get(a).cost() - hand.get(b).cost());
		for (int di : discardable) {
			CardData dc = hand.get(di);
			int ei = p2BestElementFor(dc::containsElement, elems, simCp);
			simCp[ei] += 2;
			outDiscards.add(di);
			outDiscardElems.put(di, elems[ei]);
			if (p2CanAfford(reducedCost, elems, simCp, anyCp, needsEachElement)) return true;
		}

		// Phase 2b: off-color discards — their CP counts toward total but not per-element
		// minimums (mirrors Phase 1b).  Assign to elems[0] so payP2CostViaBackupsAndDiscards
		// deposits correctly; the spend phase drains and clears that bucket.
		offColorDiscards.sort((a, b) -> hand.get(a).cost() - hand.get(b).cost());
		for (int di : offColorDiscards) {
			if (p2CanAfford(reducedCost, elems, simCp, anyCp, needsEachElement)) break;
			anyCp += 2;
			outDiscards.add(di);
			outDiscardElems.put(di, elems[0]);
		}
		return p2CanAfford(reducedCost, elems, simCp, anyCp, needsEachElement);
	}

	/**
	 * Returns true when {@code cpByElemIdx} (CP of each of the card's Elements) plus
	 * {@code anyCp} (CP of other Elements) covers {@code cost}.
	 *
	 * <p>With {@code needsEachElement} every one of the card's Elements must also have at least
	 * 1 CP of its own. That holds for a single-Element card as much as a multi-Element one: a
	 * cost-2 Lightning Forward cannot be paid by discarding one Water card for 2 Water CP. The
	 * minimum used to be applied to multi-Element cards only, which let P2 do exactly that.
	 */
	static boolean p2CanAfford(int cost, String[] elems, int[] cpByElemIdx, int anyCp,
			boolean needsEachElement) {
		if (cost <= 0) return true;
		int total = anyCp;
		for (int ei = 0; ei < elems.length; ei++) {
			if (needsEachElement && cpByElemIdx[ei] < 1) return false;
			total += cpByElemIdx[ei];
		}
		return total >= cost;
	}

	/**
	 * Whether P2's Backup {@code bk} can produce CP of {@code elem} towards casting {@code card}:
	 * its own Element, or a printed "CP of any Element" — outright, while P2 controls a Forward,
	 * or for cards of a named category. The same sources P1's affordability check counts.
	 */
	private boolean p2BackupProduces(CardData bk, String elem, CardData card) {
		if (mw.effectiveContainsElement(bk, elem)) return true;
		if (bk.backupCpAnyElement()) return true;
		if (bk.backupCpAnyElementOfForwards() && !mw.p2ForwardCards.isEmpty()) return true;
		String cat = bk.backupCpAnyElementCategory();
		return !cat.isEmpty() && (cat.equalsIgnoreCase(card.category1()) || cat.equalsIgnoreCase(card.category2()));
	}

	/**
	 * Returns the index into {@code elems} that a CP source should contribute to, among the
	 * Elements {@code produces} accepts, preferring those still short of their minimum of 1 CP.
	 */
	private static int p2BestElementFor(java.util.function.Predicate<String> produces, String[] elems, int[] simCp) {
		int bestEi = -1;
		int maxPriority = Integer.MIN_VALUE;
		for (int ei = 0; ei < elems.length; ei++) {
			if (!produces.test(elems[ei])) continue;
			// Deficit below minimum gets positive priority; surplus gets negative
			int priority = simCp[ei] < 1 ? (1 - simCp[ei]) : -simCp[ei];
			if (priority > maxPriority) { maxPriority = priority; bestEi = ei; }
		}
		return bestEi >= 0 ? bestEi : 0;
	}

	// ── OpponentController: decision requests ────────────────────────────
	//
	// The AI knows its answer immediately, so each of these runs its callback before returning.
	// A networked opponent will answer these same calls later, off a socket read.

	@Override
	public void requestBlocker(int effectiveAttackerPower, ForwardTarget attacker, boolean forcedBlock,
	                           Consumer<ForwardTarget> onChosen) {
		ForwardTarget planned = takePlannedBlock(attacker);
		onChosen.accept(planned != null ? planned : chooseBlocker(effectiveAttackerPower, attacker, forcedBlock));
	}

	/**
	 * The block a combat trick readied against {@code attacker} ({@link #makeWinningBlock}), while
	 * its Forward is still a legal blocker and no Forward is compelled to block instead — or
	 * {@code null}. Taken once, whatever the answer.
	 */
	private ForwardTarget takePlannedBlock(ForwardTarget attacker) {
		PlannedBlock plan = plannedBlock;
		plannedBlock = null;
		if (plan == null || attacker == null || mw.autoAbilityTriggers.fieldCardData(attacker) != plan.attacker()) return null;
		ForwardTarget slot = mw.findFieldSlot(plan.blocker(), false);
		List<ForwardTarget> cands = blockCandidates(attacker);
		if (slot == null || !cands.contains(slot)) return null;
		for (ForwardTarget t : cands)
			if (t.zone() == ForwardTarget.CardZone.FORWARD && mw.forwardCompelledToBlock(mw.p2ForwardCards.get(t.idx()),
					plan.attacker())) return null;
		return slot;
	}

	@Override
	public void requestPartyBlocker(List<Integer> attackerIndices, int combinedPower,
	                                boolean forcedBlock, Consumer<Integer> onChosen) {
		onChosen.accept(choosePartyBlocker(attackerIndices, forcedBlock));
	}

	@Override
	public void requestPartyBlockerDamage(List<Integer> attackerIndices, int blockerPower,
	                                      Consumer<Map<Integer, Integer>> onAssigned) {
		onAssigned.accept(mw.p2AiBuildDamageMap(attackerIndices, blockerPower));
	}

	@Override
	public void holdPriority(PriorityWindow window, Runnable onPass) {
		if (cancelled || mw.gameState.isP1GameOver()) return;
		if (playInWindow(window, null)) {
			// The play goes on the Stack, or onto the field with abilities of its own to trigger:
			// asked again once the board has settled, as the Main Phase loop asks again after a play.
			step(() -> holdPriority(window, onPass));
			return;
		}
		// A trick's aim not taken by now was never going to be: it must not reach a later choice.
		mw.aiAimedTarget = null;
		onPass.run();
	}

	// ── Priority outside its own Main Phase ──────────────────────────────

	/** Set while P2 answers one of P1's Stack entries, which the held trick ({@link #heldTrick}) does not stand in the way of. */
	private boolean answeringStack = false;

	/**
	 * Makes at most one play outside P2's own Main Phase loop — in {@code window} on P1's turn, in a
	 * combat window of P2's own turn, or on P1's Stack {@code entry} on either turn — and returns
	 * whether it made one. The one place every such play is chosen; the caller asks again once the
	 * board settles ({@link #holdPriority}) or the Stack comes back to the entry
	 * ({@link #respondToStack}), until this answers {@code false}.
	 *
	 * <p>On P1's turn: a reactive "the damage becomes 0" shield ({@link #activateReactiveShield}), Back
	 * Attack, combat tricks, removal. On P2's own turn: combat tricks after P1 blocks its attacker
	 * ({@link #playOwnCombatTrick}). On a Stack entry: the self-bounce save
	 * ({@link #saveFromStackEntry}), the answers of decision 7, and the answer to P1's trick in a
	 * battle on P2's turn ({@link #answerBattleTrick}). A play that leaves nothing on the Stack — a
	 * Back Attack Character, say — is a play all the same, and the Stack window asks again after it.
	 *
	 * @param entry the P1 Stack entry being answered, for {@link PriorityWindow#STACK_RESPONSE};
	 *              {@code null} for every other window
	 */
	boolean playInWindow(PriorityWindow window, StackEntry entry) {
		if (window == PriorityWindow.STACK_RESPONSE) {
			answeringStack = true;
			try {
				return saveFromStackEntry(entry) || answerStackEntry(entry) || answerBattleTrick(entry);
			} finally {
				answeringStack = false;
			}
		}
		if (mw.gameState.getCurrentPlayer() == GameState.Player.P2) return playOwnCombatTrick(window);
		return activateReactiveShield() || playBackAttack(window) || playCombatTrick(window) || playRemoval(window);
	}

	// ── Combat tricks (HANDOFF-cpu-opponents-turn.md decision 5) ─────────

	/**
	 * A P2 Forward a combat trick has readied to block {@code attacker}: the block
	 * {@link #requestBlocker} makes when the attack reaches the block step, which
	 * {@link #chooseBlocker} would not see — it reads power alone, not First Strike or a shield.
	 */
	private record PlannedBlock(CardData blocker, CardData attacker) {}
	private PlannedBlock plannedBlock = null;

	/** One side of a battle, as it stands or as a trick would leave it. */
	record Fighter(int power, int damage, boolean firstStrike, boolean shielded) {
		Fighter with(CombatTrick t) {
			return new Fighter(power + t.power(), damage, firstStrike || t.firstStrike(), shielded || t.shield());
		}

		/** As {@code r} leaves it: damage on it, power lost, or broken — unless it cannot be. */
		Fighter weakenedBy(Removal r) {
			return switch (r.kind()) {
				case DAMAGE     -> new Fighter(power, damage + r.amount(), firstStrike, shielded);
				case POWER_LOSS -> new Fighter(power - r.amount(), damage, firstStrike, shielded);
				case BREAK      -> shielded ? this : new Fighter(power, Math.max(damage, power), firstStrike, false);
			};
		}

		/**
		 * Whether it has left the field before any battle: no power left (put into the Break Zone,
		 * which "cannot be broken" does not stop), or damage up to its power.
		 */
		boolean gone() {
			return power <= 0 || (!shielded && damage >= power);
		}
	}

	/** The Character at {@code t} as a fighter: power, damage on it, First Strike, "cannot be broken". */
	private Fighter fighterAt(ForwardTarget t) {
		boolean forward = t.zone() == ForwardTarget.CardZone.FORWARD;
		return new Fighter(mw.fieldForwardPower(t.isP1(), t.zone(), t.idx()), mw.fieldCombatDamage(t.isP1(), t.zone(), t.idx()),
				forward && mw.effectiveHasTrait(t.isP1(), t.idx(), CardData.Trait.FIRST_STRIKE),
				forward && mw.effectiveHasTrait(t.isP1(), t.idx(), CardData.Trait.CANNOT_BE_BROKEN));
	}

	/**
	 * Who comes out of a battle between P2's {@code mine} and P1's {@code theirs}:
	 * {@code [mine survives, theirs survives]}. Each deals its power to the other; one with First
	 * Strike the other lacks deals it first, and a Forward it breaks deals nothing back. A shielded
	 * Forward — "cannot be broken", or a "damage becomes 0" trick — is not broken, on either side.
	 */
	static boolean[] battle(Fighter mine, Fighter theirs) {
		boolean mineFirst = mine.firstStrike() && !theirs.firstStrike();
		boolean theirsFirst = theirs.firstStrike() && !mine.firstStrike();
		boolean theirsBroken, mineBroken;
		if (mineFirst) {
			theirsBroken = !theirs.shielded() && theirs.damage() + mine.power() >= theirs.power();
			mineBroken = !theirsBroken && !mine.shielded() && mine.damage() + theirs.power() >= mine.power();
		} else if (theirsFirst) {
			mineBroken = !mine.shielded() && mine.damage() + theirs.power() >= mine.power();
			theirsBroken = !mineBroken && !theirs.shielded() && theirs.damage() + mine.power() >= theirs.power();
		} else {
			mineBroken = !mine.shielded() && mine.damage() + theirs.power() >= mine.power();
			theirsBroken = !theirs.shielded() && theirs.damage() + mine.power() >= theirs.power();
		}
		return new boolean[] { !mineBroken, !theirsBroken };
	}

	/** A trick P2 could play now: what it is, what it does, how many cards it discards, and the play. */
	private record TrickPlay(String what, CombatTrick trick, int discards, int cost, Runnable play) {}

	/**
	 * Plays a combat trick in {@code window} on P1's turn when it decides a battle, and returns
	 * whether it did (decision 5):
	 * <ul>
	 *   <li>after P1 declares an attack P2 has no surviving blocker for — a trick that lets one of its
	 *       Forwards block and survive ({@link #makeWinningBlock});</li>
	 *   <li>after the block — one that saves the blocker the battle would break, or one that breaks
	 *       the attacker the battle would leave standing ({@link #winTheBlock}).</li>
	 * </ul>
	 * A trick is a Summon from hand, an action ability on P2's field, or a Back Attack Character whose
	 * arrival is the trick, cheapest first; it may discard to pay, since it decides the battle.
	 */
	private boolean playCombatTrick(PriorityWindow window) {
		if (mw.gameState.getCurrentPlayer() != GameState.Player.P1) return false;
		if (window == PriorityWindow.ATTACK_DECLARED) return makeWinningBlock();
		if (window == PriorityWindow.BLOCK_DECLARED) return winTheBlock();
		return false;
	}

	private boolean makeWinningBlock() {
		ForwardTarget attacker = declaredP1Attacker();
		if (attacker == null) return false;
		CardData attackerCard = mw.autoAbilityTriggers.fieldCardData(attacker);
		if (plannedBlock != null && plannedBlock.attacker() == attackerCard) return false;
		int attackPower = mw.fieldForwardPower(true, attacker.zone(), attacker.idx());
		if (chooseBlocker(attackPower, attacker) != null) return false;
		Fighter theirs = fighterAt(attacker);
		List<ForwardTarget> cands = blockCandidates(attacker);
		// A Forward that survives the block already — by First Strike, or because it cannot be broken,
		// neither of which chooseBlocker reads — blocks for nothing: no trick is spent on it.
		for (ForwardTarget f : cands) {
			if (f.zone() != ForwardTarget.CardZone.FORWARD || !battle(fighterAt(f), theirs)[0]) continue;
			CardData blocker = mw.autoAbilityTriggers.fieldCardData(f);
			mw.logEntry("[P2] " + blocker.name() + " will block " + attackerCard.name() + ": it survives the battle");
			plannedBlock = new PlannedBlock(blocker, attackerCard);
			return false;
		}
		for (ForwardTarget f : cands) {
			if (f.zone() != ForwardTarget.CardZone.FORWARD) continue;
			Fighter mine = fighterAt(f);
			for (TrickPlay tp : availableTricks(f, true)) {
				Fighter readied = mine.with(tp.trick());
				if (!battle(readied, theirs)[0]) continue;
				if (mw.attackerPowerFilterExcludes(attackerCard, true, readied.power())) continue;
				if (mw.carriesHigherPowerBlockShield(attackerCard, true) && readied.power() > attackPower) continue;
				CardData blocker = mw.autoAbilityTriggers.fieldCardData(f);
				playTrick(tp, blocker, "so " + blocker.name() + " can block " + attackerCard.name() + " and survive");
				plannedBlock = new PlannedBlock(blocker, attackerCard);
				return true;
			}
		}
		return false;
	}

	private boolean winTheBlock() {
		if (mw.p2BlockingIdx < 0 || mw.p2BlockedByAttacker == null) return false;
		ForwardTarget attacker = mw.findFieldSlot(mw.p2BlockedByAttacker, true);
		if (attacker == null || mw.p2BlockingIdx >= mw.p2ForwardCards.size()) return false;
		ForwardTarget blocker = new ForwardTarget(false, mw.p2BlockingIdx, ForwardTarget.CardZone.FORWARD);
		Fighter mine = fighterAt(blocker), theirs = fighterAt(attacker);
		boolean[] now = battle(mine, theirs);
		String blockerName = mw.p2ForwardCards.get(mw.p2BlockingIdx).name();
		for (TrickPlay tp : availableTricks(blocker, false)) {
			boolean[] after = battle(mine.with(tp.trick()), theirs);
			String why = !now[0] && after[0] ? "to save " + blockerName
					: now[0] && now[1] && after[0] && !after[1] ? "to break " + mw.p2BlockedByAttacker.name() : null;
			if (why == null) continue;
			playTrick(tp, mw.p2ForwardCards.get(mw.p2BlockingIdx), why);
			return true;
		}
		return false;
	}

	/** Plays {@code tp} on {@code forward}, aiming its choice there ({@link MainWindow#aiAimedTarget}). */
	private void playTrick(TrickPlay tp, CardData forward, String why) {
		mw.logEntry("[P2] Combat trick — " + tp.what() + " " + why);
		mw.aiAimedTarget = forward;
		tp.play().run();
	}

	/**
	 * The combat tricks P2 could play now that reach its Forward at {@code aimedAt}, cheapest first —
	 * fewest cards discarded, then lowest printed cost. With {@code beforeBlock}, none that dulls the
	 * Forward it is for: a dull Forward cannot block.
	 */
	private List<TrickPlay> availableTricks(ForwardTarget aimedAt, boolean beforeBlock) {
		List<TrickPlay> out = new ArrayList<>();
		List<CardData> hand = mw.gameState.getP2Hand();
		if (!mw.p2CastLimitReached()) {
			for (int i = 0; i < hand.size(); i++) {
				CardData c = hand.get(i);
				String text = c.isSummon() ? c.summonEffect() : null;
				if (c.isSummon()) {
					if (mw.summonCastingBanned(false) || mw.summonCastBlocked(c, false) || !mw.castRestrictionMet(c, false)) continue;
				} else if (c.hasTrait(CardData.Trait.BACK_ATTACK) && castableOnOpponentsTurn(c)) {
					text = AutoAbilityTriggers.ownArrivalEffects(c).stream()
							.filter(e -> CombatTrick.of(e) != null).findFirst().orElse(null);
				}
				CombatTrick trick = CombatTrick.of(text);
				if (trick == null || (beforeBlock && trick.dulls()) || !trickReaches(text, trick, c, aimedAt, false)) continue;
				P2Plan plan = planCast(c, i, false);
				if (plan == null) continue;
				out.add(new TrickPlay(c.name(), trick, plan.discardIndices().size(), c.cost(), () -> executeP2HandPlay(plan)));
			}
		}
		addFieldAbilityTricks(out, aimedAt, beforeBlock);
		out.sort(Comparator.comparingInt(TrickPlay::discards).thenComparingInt(TrickPlay::cost));
		return out;
	}

	/** The action abilities on P2's field, usable now, whose effect is a trick reaching {@code aimedAt}. */
	private void addFieldAbilityTricks(List<TrickPlay> out, ForwardTarget aimedAt, boolean beforeBlock) {
		for (int i = 0; i < mw.p2ForwardCards.size(); i++)
			addFieldAbilityTricks(out, aimedAt, beforeBlock, new ForwardTarget(false, i, ForwardTarget.CardZone.FORWARD),
					mw.p2ForwardCards.get(i), mw.p2ForwardFrozen.get(i), mw.p2ForwardStates.get(i), mw.p2ForwardPlayedOnTurn.get(i));
		for (int i = 0; i < mw.p2BackupCards.length; i++)
			if (mw.p2BackupCards[i] != null)
				addFieldAbilityTricks(out, aimedAt, beforeBlock, new ForwardTarget(false, i, ForwardTarget.CardZone.BACKUP),
						mw.p2BackupCards[i], mw.p2BackupFrozen[i], mw.p2BackupStates[i], 0);
		for (int i = 0; i < mw.p2MonsterCards.size(); i++)
			addFieldAbilityTricks(out, aimedAt, beforeBlock, new ForwardTarget(false, i, ForwardTarget.CardZone.MONSTER),
					mw.p2MonsterCards.get(i), mw.p2MonsterFrozen.get(i), mw.p2MonsterStates.get(i), mw.p2MonsterPlayedOnTurn.get(i));
	}

	private void addFieldAbilityTricks(List<TrickPlay> out, ForwardTarget aimedAt, boolean beforeBlock,
			ForwardTarget slot, CardData card, boolean frozen, CardState state, int playedOn) {
		{
			if (card == null || mw.lostAbilitiesCards.contains(card)) return;
			for (ActionAbility ability : card.actionAbilities()) {
				CombatTrick trick = CombatTrick.of(ability.effectText());
				if (trick == null || (beforeBlock && trick.dulls())) continue;
				// Dulling the Forward that has yet to block for its own trick leaves it unable to block.
				if (beforeBlock && ability.requiresDull() && slot.equals(aimedAt)) continue;
				if (!mw.canActivateAbility(ability, frozen, state, playedOn, card, false)) continue;
				if (!trickReaches(ability.effectText(), trick, card, aimedAt, ability.isSpecial())) continue;
				List<Integer>        backups     = new ArrayList<>();
				Map<Integer, String> backupElems = new LinkedHashMap<>();
				List<Integer>        discards    = new ArrayList<>();
				Map<Integer, String> discElems   = new LinkedHashMap<>();
				if (!p2PlanAbilityPayment(ability, card, backups, backupElems, discards, discElems)) continue;
				out.add(new TrickPlay(card.name() + "'s ability", trick, discards.size(), ability.cpCost().size(),
						() -> mw.autoAbilityTriggers.executeP2AbilityActivation(ability, card, mw.abilityCostDull(slot),
								backups, discards, 0)));
			}
		}
	}

	/** Whether {@code trick}, read off {@code text} on {@code source}, can be aimed at P2's Character at {@code aimedAt}. */
	private boolean trickReaches(String text, CombatTrick trick, CardData source, ForwardTarget aimedAt, boolean special) {
		if (trick.subject() == null) return true;
		return choiceReaches(text, source, aimedAt, special);
	}

	/** Whether the choice leading {@code text}, made for {@code source}'s side, may take the card at {@code target}. */
	private boolean choiceReaches(String text, CardData source, ForwardTarget target, boolean special) {
		TargetSpec spec = ActionResolver.targetSpec(text, source);
		if (spec == null || spec.zone() != null) return false;
		return mw.eligibleChoiceTargets(spec, source, false, special).contains(target);
	}

	// ── Removal (HANDOFF-cpu-opponents-turn.md decision 6) ───────────────

	/** A removal P2 could play now on one of P1's Forwards: what it is, what it does, how many cards it discards, and the play. */
	private record RemovalPlay(String what, Removal removal, int discards, int cost, Runnable play) {}

	/**
	 * Breaks one of P1's Forwards with a removal on P1's turn, and returns whether it did
	 * (decision 6): the declared attacker, after P1 declares it, when P2 has no block that survives it —
	 * so its damage never lands; and as P1's Main Phase 2 ends, the dearest Forward it can break, so
	 * it cannot block on P2's turn. Only a Forward the removal breaks outright, as the board stands.
	 * A removal is a Summon from hand or an action ability on P2's field ({@link Removal}), cheapest
	 * first; it may discard to pay. (A Back Attack Character whose arrival removes is
	 * {@link #playBackAttack}'s.)
	 */
	private boolean playRemoval(PriorityWindow window) {
		if (mw.gameState.getCurrentPlayer() != GameState.Player.P1) return false;
		List<ForwardTarget> aims = new ArrayList<>();
		if (window == PriorityWindow.ATTACK_DECLARED) {
			ForwardTarget attacker = declaredP1Attacker();
			if (attacker == null) return false;
			CardData attackerCard = mw.autoAbilityTriggers.fieldCardData(attacker);
			int attackPower = mw.fieldForwardPower(true, attacker.zone(), attacker.idx());
			// A block that survives deals with the attacker already.
			if (chooseBlocker(attackPower, attacker) != null
					|| (plannedBlock != null && plannedBlock.attacker() == attackerCard)) return false;
			aims.add(attacker);
		} else if (window == PriorityWindow.MAIN_2_END) {
			for (int i = 0; i < mw.p1ForwardCards.size(); i++)
				if (mw.p1ForwardCards.get(i) != null) aims.add(new ForwardTarget(true, i, ForwardTarget.CardZone.FORWARD));
			aims.sort(Comparator.comparingInt((ForwardTarget t) -> mw.autoAbilityTriggers.fieldCardData(t).cost()).reversed());
		} else {
			return false;
		}
		for (ForwardTarget t : aims) {
			List<RemovalPlay> plays = availableRemoval(t);
			if (plays.isEmpty()) continue;
			RemovalPlay rp = plays.get(0);
			CardData target = mw.autoAbilityTriggers.fieldCardData(t);
			mw.logEntry("[P2] Removal — " + rp.what() + " breaks " + target.name()
					+ (window == PriorityWindow.ATTACK_DECLARED ? " before it deals damage" : " before P2's turn"));
			mw.aiAimedTarget = target;
			rp.play().run();
			return true;
		}
		return false;
	}

	/** The removals P2 could play now that break P1's Forward at {@code target} outright, cheapest first. */
	private List<RemovalPlay> availableRemoval(ForwardTarget target) {
		return availableRemoval(target, r -> removalBreaks(r, target, 0));
	}

	/**
	 * The removals ({@link Removal}) P2 could play now whose choice can take P1's Character at
	 * {@code target} and that {@code worth} accepts, cheapest first — fewest cards discarded, then
	 * lowest printed cost.
	 */
	private List<RemovalPlay> availableRemoval(ForwardTarget target, Predicate<Removal> worth) {
		List<RemovalPlay> out = new ArrayList<>();
		List<CardData> hand = mw.gameState.getP2Hand();
		if (!mw.p2CastLimitReached() && !mw.summonCastingBanned(false)) {
			for (int i = 0; i < hand.size(); i++) {
				CardData c = hand.get(i);
				if (!c.isSummon() || mw.summonCastBlocked(c, false) || !mw.castRestrictionMet(c, false)) continue;
				Removal r = Removal.of(c.summonEffect());
				if (r == null || !worth.test(r) || !choiceReaches(c.summonEffect(), c, target, false)) continue;
				P2Plan plan = planCast(c, i, false);
				if (plan == null) continue;
				out.add(new RemovalPlay(c.name(), r, plan.discardIndices().size(), c.cost(), () -> executeP2HandPlay(plan)));
			}
		}
		for (int i = 0; i < mw.p2ForwardCards.size(); i++)
			addFieldRemoval(out, target, worth, new ForwardTarget(false, i, ForwardTarget.CardZone.FORWARD), mw.p2ForwardCards.get(i),
					mw.p2ForwardFrozen.get(i), mw.p2ForwardStates.get(i), mw.p2ForwardPlayedOnTurn.get(i));
		for (int i = 0; i < mw.p2BackupCards.length; i++)
			if (mw.p2BackupCards[i] != null)
				addFieldRemoval(out, target, worth, new ForwardTarget(false, i, ForwardTarget.CardZone.BACKUP), mw.p2BackupCards[i],
						mw.p2BackupFrozen[i], mw.p2BackupStates[i], 0);
		for (int i = 0; i < mw.p2MonsterCards.size(); i++)
			addFieldRemoval(out, target, worth, new ForwardTarget(false, i, ForwardTarget.CardZone.MONSTER), mw.p2MonsterCards.get(i),
					mw.p2MonsterFrozen.get(i), mw.p2MonsterStates.get(i), mw.p2MonsterPlayedOnTurn.get(i));
		out.sort(Comparator.comparingInt(RemovalPlay::discards).thenComparingInt(RemovalPlay::cost));
		return out;
	}

	private void addFieldRemoval(List<RemovalPlay> out, ForwardTarget target, Predicate<Removal> worth, ForwardTarget slot,
			CardData card, boolean frozen, CardState state, int playedOn) {
		if (card == null || mw.lostAbilitiesCards.contains(card)) return;
		for (ActionAbility ability : card.actionAbilities()) {
			if (!mw.canActivateAbility(ability, frozen, state, playedOn, card, false)) continue;
			Removal r = Removal.of(ability.effectText());
			if (r == null || !worth.test(r) || !choiceReaches(ability.effectText(), card, target, ability.isSpecial())) continue;
			List<Integer>        backups     = new ArrayList<>();
			Map<Integer, String> backupElems = new LinkedHashMap<>();
			List<Integer>        discards    = new ArrayList<>();
			Map<Integer, String> discElems   = new LinkedHashMap<>();
			if (!p2PlanAbilityPayment(ability, card, backups, backupElems, discards, discElems)) continue;
			out.add(new RemovalPlay(card.name() + "'s ability", r, discards.size(), ability.cpCost().size(),
					() -> mw.autoAbilityTriggers.executeP2AbilityActivation(ability, card, mw.abilityCostDull(slot),
							backups, discards, 0)));
		}
	}

	/**
	 * Whether {@code text}, made for {@code source}'s side, is a removal ({@link Removal}) whose choice
	 * can take P1's Forward at {@code target} and that would break it outright as the board stands:
	 * damage, or power lost, of at least what it has left — not one its damage already dooms — and
	 * for a break or damage, not one that cannot be broken. Printed amounts, so a boost is not counted.
	 */
	private boolean removalBreaks(String text, CardData source, ForwardTarget target, boolean special) {
		return removalBreaks(text, source, target, special, 0);
	}

	/** {@link #removalBreaks} with {@code extraDamage} more already on the Forward. */
	private boolean removalBreaks(String text, CardData source, ForwardTarget target, boolean special, int extraDamage) {
		Removal r = Removal.of(text);
		return r != null && choiceReaches(text, source, target, special) && removalBreaks(r, target, extraDamage);
	}

	/** Whether {@code r}, once it reaches P1's Forward at {@code target}, breaks it outright: {@link #removalBreaks}. */
	private boolean removalBreaks(Removal r, ForwardTarget target, int extraDamage) {
		if (mw.fieldForwardBreakableBy(true, target.zone(), target.idx(), extraDamage)) return false;
		boolean unbreakable = target.zone() == ForwardTarget.CardZone.FORWARD
				&& mw.effectiveHasTrait(true, target.idx(), CardData.Trait.CANNOT_BE_BROKEN);
		return switch (r.kind()) {
			case BREAK      -> !unbreakable;
			case DAMAGE     -> !unbreakable && mw.fieldForwardBreakableBy(true, target.zone(), target.idx(), r.amount() + extraDamage);
			case POWER_LOSS -> mw.fieldForwardBreakableBy(true, target.zone(), target.idx(), r.amount() + extraDamage);
		};
	}

	// ── Combat tricks on its own attacks (HANDOFF-cpu-own-attack-tricks.md) ──

	/** The battle P1's block has made on P2's turn: P2's attacker, and P1's blocker. */
	private record OwnBattle(ForwardTarget attacker, CardData attackerCard, ForwardTarget blocker, CardData blockerCard) {}

	/**
	 * The battle in progress on P2's turn, once P1 has blocked its one attacker — or {@code null}: no
	 * block, a party, either Character gone from the field, or not the block step.
	 */
	private OwnBattle ownBattle() {
		if (mw.gameState.getCurrentPlayer() != GameState.Player.P2 || mw.attackSubStep != 2) return null;
		if (mw.p2DeclaredAttackers.size() != 1 || mw.p1DeclaredBlocker == null) return null;
		CardData attackerCard = mw.p2DeclaredAttackers.get(0);
		ForwardTarget attacker = mw.findFieldSlot(attackerCard, false);
		// A primed Forward attacks as its top card, which the Forward row does not hold.
		if (attacker == null) {
			for (int i = 0; i < mw.p2ForwardPrimedTop.size(); i++)
				if (mw.p2ForwardPrimedTop.get(i) == attackerCard) attacker = new ForwardTarget(false, i, ForwardTarget.CardZone.FORWARD);
		}
		ForwardTarget blocker = mw.findFieldSlot(mw.p1DeclaredBlocker, true);
		if (attacker == null || blocker == null) return null;
		return new OwnBattle(attacker, attackerCard, blocker, mw.autoAbilityTriggers.fieldCardData(blocker));
	}

	/**
	 * A play that changes a battle on P2's turn: a {@code boost} to its attacker ({@link CombatTrick}),
	 * or a {@code weaken} of P1's blocker ({@link Removal}) — exactly one — with the card its choice is
	 * {@code aim}ed at, what it costs, and the play.
	 */
	private record BattlePlay(String what, CombatTrick boost, Removal weaken, CardData aim, int discards, int cost, Runnable play) {}

	/**
	 * The plays that could change {@code b} now, cheapest first: tricks that reach its attacker — from
	 * hand, Back Attack arrivals, abilities on its field ({@link #availableTricks}) — and removals whose
	 * choice reaches P1's blocker ({@link #availableRemoval}).
	 */
	private List<BattlePlay> battlePlays(OwnBattle b) {
		List<BattlePlay> out = new ArrayList<>();
		for (TrickPlay tp : availableTricks(b.attacker(), false))
			out.add(new BattlePlay(tp.what(), tp.trick(), null, b.attackerCard(), tp.discards(), tp.cost(), tp.play()));
		for (RemovalPlay rp : availableRemoval(b.blocker(), r -> true))
			out.add(new BattlePlay(rp.what(), null, rp.removal(), b.blockerCard(), rp.discards(), rp.cost(), rp.play()));
		out.sort(Comparator.comparingInt(BattlePlay::discards).thenComparingInt(BattlePlay::cost));
		return out;
	}

	/**
	 * How the battle between P2's {@code mine} and P1's {@code theirs} comes out once {@code play}
	 * resolves and then {@code pending} — P1's trick under it on the Stack — if any:
	 * {@code [mine survives, theirs survives]}. A blocker {@code play} takes off the field leaves the
	 * attacker standing, and the trick it was waiting for with nothing to land on.
	 */
	static boolean[] outcome(Fighter mine, Fighter theirs, BattlePlay play, CombatTrick pending) {
		if (play.boost() != null) mine = mine.with(play.boost());
		if (play.weaken() != null) {
			theirs = theirs.weakenedBy(play.weaken());
			if (theirs.gone()) return new boolean[] { true, false };
		}
		if (pending != null) theirs = theirs.with(pending);
		return battle(mine, theirs);
	}

	/**
	 * After P1 blocks P2's attacker on P2's turn, plays the cheapest trick that decides the battle,
	 * and returns whether it did: one that saves the attacker the battle would break ("to save …"), or
	 * one that breaks a blocker the battle would leave standing beside a surviving attacker ("to
	 * break …"). A trick boosts the attacker ({@link CombatTrick}) or weakens the blocker — damage or
	 * power loss that the battle's damage then finishes, or a removal that takes it first
	 * ({@link Removal}). It may discard to pay, since it decides the battle (decision 5c of
	 * HANDOFF-cpu-opponents-turn, applied to P2's own attacks).
	 */
	private boolean playOwnCombatTrick(PriorityWindow window) {
		if (window != PriorityWindow.BLOCK_DECLARED) return false;
		OwnBattle b = ownBattle();
		if (b == null) return false;
		Fighter mine = fighterAt(b.attacker()), theirs = fighterAt(b.blocker());
		boolean[] now = battle(mine, theirs);
		for (BattlePlay p : battlePlays(b)) {
			boolean[] after = outcome(mine, theirs, p, null);
			String why = !now[0] && after[0] ? "to save " + b.attackerCard().name()
					: now[0] && now[1] && after[0] && !after[1] ? "to break " + b.blockerCard().name() : null;
			if (why == null) continue;
			mw.logEntry("[P2] Combat trick — " + p.what() + " " + why);
			mw.aiAimedTarget = p.aim();
			p.play().run();
			return true;
		}
		return false;
	}

	/**
	 * Answers P1's trick on its blocker, cast after P2's attacker was blocked on P2's turn, with a
	 * trick of its own that undoes what it would do, and returns whether it did: when P1's boost would
	 * break the attacker that was safe ("to keep … alive"), or save the blocker the battle was
	 * breaking ("so … still breaks …"). The answer resolves first — so a blocker it takes off the
	 * field never receives P1's trick. Only a boost to the blocker is read ({@link CombatTrick}): a
	 * choice that took it, or every Forward P1 controls. (Breaking the blocker outright is
	 * {@link #answerStackEntry}'s, as for any Forward P1's buff is aimed at.)
	 */
	private boolean answerBattleTrick(StackEntry entry) {
		if (entry == null || !entry.isP1()) return false;
		OwnBattle b = ownBattle();
		if (b == null) return false;
		String text = mw.resolvingEffectText(entry);
		CombatTrick pending = CombatTrick.of(text);
		if (pending == null) return false;
		List<ForwardTarget> targets = mw.liveTargets(entry, false);
		if (pending.subject() != null && (targets == null || !targets.contains(b.blocker()))) return false;
		Fighter mine = fighterAt(b.attacker()), theirs = fighterAt(b.blocker());
		boolean[] before = battle(mine, theirs), threatened = battle(mine, theirs.with(pending));
		String attackerName = b.attackerCard().name(), blockerName = b.blockerCard().name();
		for (BattlePlay p : battlePlays(b)) {
			boolean[] after = outcome(mine, theirs, p, pending);
			String why = before[0] && !threatened[0] && after[0] ? "to keep " + attackerName + " alive"
					: !before[1] && threatened[1] && after[0] && !after[1] ? "so " + attackerName + " still breaks " + blockerName
					: null;
			if (why == null) continue;
			mw.logEntry("[P2] Answers " + entry.source().name() + " — " + p.what() + " " + why);
			mw.aiAimedTarget = p.aim();
			p.play().run();
			return true;
		}
		return false;
	}

	// ── Follow-up damage (HANDOFF-cpu-you-may.md decision 16) ────────────

	/** The card a sacrifice is about to spend, whose own CP no follow-up may count on ({@link #p2CpBackups}). */
	private CardData spentByTheOffer = null;

	/**
	 * Whether, with {@code extraDamage} dealt to P1's Forward at {@code target} — what a sacrifice
	 * buys (28-010R Jack Garland's 9000) — P2 could finish it later this turn: a damage or power-loss
	 * removal, a Summon from hand or an ability on its field, that would break it then and would not
	 * now, which it can pay for without {@code spent}, while a window it plays removal in is still ahead
	 * — the end of P1's Main Phase 2 on P1's turn (decision 6), its own Main Phase on its own.
	 */
	boolean followUpFinishes(ForwardTarget target, int extraDamage, CardData spent) {
		GameState.GamePhase phase = mw.gameState.getCurrentPhase();
		boolean ahead = mw.gameState.getCurrentPlayer() == GameState.Player.P1
				? phase != GameState.GamePhase.END
				: phase == GameState.GamePhase.MAIN_1 || phase == GameState.GamePhase.ATTACK
						|| phase == GameState.GamePhase.MAIN_2;
		if (!ahead) return false;
		spentByTheOffer = spent;
		try {
			List<CardData> hand = mw.gameState.getP2Hand();
			for (int i = 0; i < hand.size(); i++) {
				CardData c = hand.get(i);
				if (!c.isSummon() || mw.summonCastingBanned(false) || !mw.castRestrictionMet(c, false)) continue;
				if (!removalBreaks(c.summonEffect(), c, target, false, extraDamage)
						|| removalBreaks(c.summonEffect(), c, target, false, 0)) continue;
				if (planCast(c, i, false) != null) return true;
			}
			boolean[] found = { false };
			forEachFieldAbility((slot, card, ability) -> {
				if (found[0] || card == spent) return;
				if (!removalBreaks(ability.effectText(), card, target, ability.isSpecial(), extraDamage)
						|| removalBreaks(ability.effectText(), card, target, ability.isSpecial(), 0)) return;
				List<Integer> backups = new ArrayList<>(), discards = new ArrayList<>();
				if (p2PlanAbilityPayment(ability, card, backups, new LinkedHashMap<>(), discards, new LinkedHashMap<>()))
					found[0] = true;
			});
			return found[0];
		} finally {
			spentByTheOffer = null;
		}
	}

	// ── Back Attack (HANDOFF-cpu-opponents-turn.md decision 4) ───────────

	/**
	 * A card P2 keeps in hand for a play later — the Back Attack Character for P1's turn
	 * ({@link #heldBackAttack}), the combat trick for its attacks ({@link #heldTrick}) — and the
	 * active Backups set aside to cast it then.
	 */
	record HeldCard(CardData card, Set<Integer> backups) {}

	/** Set while {@link #heldBackAttack} plans the held card's own payment, which reads the whole row. */
	private boolean planningHeldCard = false;

	/** Set while {@link #heldTrick} plans the trick's own payment, which reads the row the Back Attack reserve leaves. */
	private boolean planningHeldTrick = false;

	/**
	 * The Back Attack Character P2 keeps in hand for P1's turn, with the active Backups set aside to
	 * cast it then — or {@code null}. Asked on P2's own turn only; on P1's turn the reserve is what pays.
	 *
	 * <p>Held: a Character with an ambush use (a Forward, which can block) or an arrival use (an
	 * enters-the-field removal, {@link AutoAbilityTriggers#arrivalIsRemoval}) that the active Backups
	 * can pay for in full, nothing discarded — a card they cannot cover is cast or kept as ever, so
	 * nothing is held that could not be played. The dearest such card, and only one.
	 */
	HeldCard heldBackAttack() {
		if (planningHeldCard || mw.gameState.getCurrentPlayer() != GameState.Player.P2) return null;
		planningHeldCard = true;
		try {
			List<CardData> hand = mw.gameState.getP2Hand();
			HeldCard best = null;
			for (int i = 0; i < hand.size(); i++) {
				CardData c = hand.get(i);
				if (!c.hasTrait(CardData.Trait.BACK_ATTACK) || c.isSummon()) continue;
				if (!c.isForward() && !AutoAbilityTriggers.arrivalIsRemoval(c)) continue;
				if (best != null && c.cost() <= best.card().cost()) continue;
				List<Integer>        backups     = new ArrayList<>();
				Map<Integer, String> backupElems = new LinkedHashMap<>();
				List<Integer>        discards    = new ArrayList<>();
				Map<Integer, String> discElems   = new LinkedHashMap<>();
				if (!p2PlanPayment(c, mw.castCostFor(c, false), i, backups, backupElems, discards, discElems)) continue;
				if (!discards.isEmpty()) continue;
				best = new HeldCard(c, Set.copyOf(backups));
			}
			return best;
		} finally {
			planningHeldCard = false;
		}
	}

	/**
	 * The combat trick P2 keeps for its attacks, with the active Backups set aside to cast it once P1
	 * blocks ({@link #playOwnCombatTrick}) — or {@code null}. Held on P2's turn from Main Phase 1 to
	 * the end of the Attack Phase, while P2 has a Forward that can attack and P1 an active Forward that
	 * could block it; not inside a battle, where it is spent, nor while P2 answers one of P1's Stack
	 * entries. Main Phase 2 has it back: a card it kept from Main Phase 1 would not have attacked this
	 * turn anyway, unless it had Haste.
	 *
	 * <p>The cheapest Summon in hand that reads as a trick ({@link CombatTrick}) able to reach one of
	 * those Forwards, and that the Backups the Back Attack reserve leaves can pay for in full, nothing
	 * discarded — so nothing is held that could not be played. Only one.
	 */
	HeldCard heldTrick() {
		if (planningHeldCard || planningHeldTrick || answeringStack) return null;
		if (mw.gameState.getCurrentPlayer() != GameState.Player.P2) return null;
		GameState.GamePhase phase = mw.gameState.getCurrentPhase();
		if (phase != GameState.GamePhase.MAIN_1 && !(phase == GameState.GamePhase.ATTACK && mw.attackSubStep <= 0)) return null;
		if (mw.p2CastLimitReached() || mw.summonCastingBanned(false)) return null;
		if (!mw.p1ForwardStates.contains(CardState.ACTIVE)) return null;
		List<ForwardTarget> attackers = new ArrayList<>();
		for (int i = 0; i < mw.p2ForwardCards.size(); i++)
			if (p2ForwardCanAttack(i)) attackers.add(new ForwardTarget(false, i, ForwardTarget.CardZone.FORWARD));
		if (attackers.isEmpty()) return null;
		planningHeldTrick = true;
		try {
			List<CardData> hand = mw.gameState.getP2Hand();
			HeldCard best = null;
			for (int i = 0; i < hand.size(); i++) {
				CardData c = hand.get(i);
				if (!c.isSummon() || (best != null && c.cost() >= best.card().cost())) continue;
				if (mw.summonCastBlocked(c, false) || !mw.castRestrictionMet(c, false)) continue;
				String text = c.summonEffect();
				CombatTrick trick = CombatTrick.of(text);
				if (trick == null || attackers.stream().noneMatch(a -> trickReaches(text, trick, c, a, false))) continue;
				List<Integer>        backups     = new ArrayList<>();
				Map<Integer, String> backupElems = new LinkedHashMap<>();
				List<Integer>        discards    = new ArrayList<>();
				Map<Integer, String> discElems   = new LinkedHashMap<>();
				if (!p2PlanPayment(c, mw.castCostFor(c, false), i, backups, backupElems, discards, discElems)) continue;
				if (!discards.isEmpty()) continue;
				best = new HeldCard(c, Set.copyOf(backups));
			}
			return best;
		} finally {
			planningHeldTrick = false;
		}
	}

	/** The held cards — the Back Attack Character, the trick — which no payment discards for CP. */
	private List<CardData> keptInHand() {
		List<CardData> kept = new ArrayList<>(2);
		HeldCard backAttack = heldBackAttack(), trick = heldTrick();
		if (backAttack != null) kept.add(backAttack.card());
		if (trick != null) kept.add(trick.card());
		return kept;
	}

	/** Whether {@code c} is one of {@code kept}, by identity: two copies of a printing are equal. */
	private static boolean isKept(List<CardData> kept, CardData c) {
		for (CardData k : kept) if (k == c) return true;
		return false;
	}

	/** Whether Backup slot {@code bi} is set aside for a held card. */
	private boolean reservedBackup(int bi) {
		HeldCard backAttack = heldBackAttack(), trick = heldTrick();
		return (backAttack != null && backAttack.backups().contains(bi)) || (trick != null && trick.backups().contains(bi));
	}

	/**
	 * Casts a Back Attack Character from hand in {@code window} on P1's turn, when one is worth it
	 * there, and returns whether it did. Three reasons, in order (decision 4):
	 * <ul>
	 *   <li>its arrival removes an opposing Forward now ({@link AutoAbilityTriggers#arrivalRemoves});</li>
	 *   <li>after P1 declares an attack, it is a blocker that survives it and P2 has none
	 *       ({@link #ambushBlocks});</li>
	 *   <li>as P1's Main Phase 2 ends, on the field before P2's turn — paid with Backups alone, which
	 *       are back at P2's Active Phase: the same card is cheaper cast then than paid for in cards
	 *       now.</li>
	 * </ul>
	 * The first two may discard to pay: the play is worth the cards.
	 */
	private boolean playBackAttack(PriorityWindow window) {
		if (mw.gameState.getCurrentPlayer() != GameState.Player.P1 || mw.p2CastLimitReached()) return false;
		List<CardData> hand = mw.gameState.getP2Hand();
		List<Integer> order = new ArrayList<>();
		for (int i = 0; i < hand.size(); i++) order.add(i);
		order.sort((a, b) -> hand.get(b).cost() - hand.get(a).cost());
		ForwardTarget attacker = window == PriorityWindow.ATTACK_DECLARED ? declaredP1Attacker() : null;
		for (int i : order) {
			CardData c = hand.get(i);
			if (!c.hasTrait(CardData.Trait.BACK_ATTACK) || c.isSummon() || !castableOnOpponentsTurn(c)) continue;
			String why = window != PriorityWindow.OTHER ? mw.autoAbilityTriggers.arrivalRemoves(c, false) : null;
			boolean backupsOnly = false;
			if (why == null && attacker != null && ambushBlocks(c, attacker))
				why = "it blocks " + mw.autoAbilityTriggers.fieldCardData(attacker).name() + " and survives";
			if (why == null && window == PriorityWindow.MAIN_2_END) {
				why = "on the field before its own turn";
				backupsOnly = true;
			}
			if (why == null) continue;
			P2Plan plan = planCast(c, i, backupsOnly);
			if (plan == null) continue;
			mw.logEntry("[P2] Back Attack — " + c.name() + ": " + why);
			executeP2HandPlay(plan);
			return true;
		}
		return false;
	}

	/** P1's one declared attacker as a field slot, or {@code null}: none, or a party. */
	private ForwardTarget declaredP1Attacker() {
		if (mw.p1DeclaredAttackers.size() != 1) return null;
		return mw.findFieldSlot(mw.p1DeclaredAttackers.get(0), true);
	}

	/**
	 * Whether Back Attack Forward {@code card}, played now, would block {@code attacker} and survive
	 * it while nothing P2 already has would ({@link #chooseBlocker}) — the blocker the AI then picks.
	 * Read with the same attacker-side restrictions {@link #chooseBlocker} applies, on the printed power.
	 */
	private boolean ambushBlocks(CardData card, ForwardTarget attacker) {
		if (!card.isForward()) return false;
		CardData a = mw.autoAbilityTriggers.fieldCardData(attacker);
		if (a == null) return false;
		int attackPower = mw.fieldForwardPower(true, attacker.zone(), attacker.idx());
		if (chooseBlocker(attackPower, attacker) != null) return false;
		if (mw.p1CannotBeBlocked.contains(a) || mw.hasSelfCannotBeBlockedFieldAbility(a, true)
				|| mw.attackerConditionallyUnblockable(a, true)) return false;
		if (mw.p1AttackerCostFiltersExclude(a, card.cost())) return false;
		if (mw.attackerPowerFilterExcludes(a, true, card.power())) return false;
		if (mw.carriesHigherPowerBlockShield(a, true) && card.power() > attackPower) return false;
		return card.power() >= attackPower;
	}

	/** Whether {@code card} may be cast from P2's hand now, on P1's turn — timing aside, which Back Attack settles. */
	private boolean castableOnOpponentsTurn(CardData card) {
		if (!card.castsAtSummonSpeed()) return false;
		if (!card.multicard() && mw.hasUniquenessClashOnField(card, false)) return false;
		if (card.isLightOrDark() && mw.hasLightOrDarkOnField(false)) return false;
		if (card.isBackup() && !mw.p2HasAvailableBackupSlot()) return false;
		return mw.castRestrictionMet(card, false);
	}

	/** P2's cast of hand card {@code cardIdx}, or {@code null} when it cannot be paid — or not without discarding, when {@code backupsOnly}. */
	private P2Plan planCast(CardData card, int cardIdx, boolean backupsOnly) {
		List<Integer>        backups     = new ArrayList<>();
		Map<Integer, String> backupElems = new LinkedHashMap<>();
		List<Integer>        discards    = new ArrayList<>();
		Map<Integer, String> discElems   = new LinkedHashMap<>();
		int cost = mw.castCostFor(card, false);
		if (!p2PlanPayment(card, cost, cardIdx, backups, backupElems, discards, discElems)) return null;
		if (backupsOnly && !discards.isEmpty()) return null;
		return new P2Plan(cardIdx, cost, backups, backupElems, discards, discElems, false);
	}

	// ── Blocking AI ──────────────────────────────────────────────────────

	/**
	 * Picks the P2 Forward that blocks a party attack: the highest-power active Forward that
	 * survives the weakest attacker's damage. Returns {@code null} when none qualifies — unless
	 * {@code forcedBlock} is set, in which case a block is mandatory and the weakest active
	 * Forward is thrown in front of the party instead. Mirrors the single-attacker fallback in
	 * {@link #chooseBlocker}.
	 */
	private Integer choosePartyBlocker(List<Integer> attackerIndices, boolean forcedBlock) {
		int minAttackerPower = Integer.MAX_VALUE;
		for (int code : attackerIndices) {
			if (mw.partyMemberBaseCard(true, code) != null)
				minAttackerPower = Math.min(minAttackerPower,
						mw.partyMemberPower(true, code) - mw.partyMemberDamage(true, code));
		}
		// A compelled Forward that can block takes the block, as in chooseBlocker; a compulsion
		// naming any member binds against the whole party.
		List<Integer> compelled = new ArrayList<>();
		for (int i = 0; i < mw.p2ForwardStates.size(); i++) {
			CardData fwd = mw.p2ForwardCards.get(i);
			if (mw.p2ForwardStates.get(i) != CardState.ACTIVE || fwd.cannotBlockParty()
					|| mw.p2CannotBlock.contains(fwd) || mw.p2CannotBlockPersistent.contains(fwd)
					|| mw.blockBarredByFieldCostLock(fwd)) continue;
			for (int code : attackerIndices)
				if (mw.forwardCompelledToBlock(fwd, mw.partyMemberBaseCard(true, code))) { compelled.add(i); break; }
		}
		if (!compelled.isEmpty())
			return bestCompelledBlocker(compelled, minAttackerPower, mw::effectiveP2ForwardPower);
		int bestBlockerIdx = -1, bestBlockerPower = 0;
		for (int i = 0; i < mw.p2ForwardStates.size(); i++) {
			if (mw.p2ForwardStates.get(i) != CardState.ACTIVE) continue;
			int pw = mw.effectiveP2ForwardPower(i);
			if (pw >= minAttackerPower && pw > bestBlockerPower) {
				bestBlockerPower = pw;
				bestBlockerIdx   = i;
			}
		}
		if (bestBlockerIdx >= 0) return bestBlockerIdx;

		// "Opponent must block [X] if possible" on any party member: no Forward survives the
		// block, but declining is not on offer, so the cheapest loss is the weakest one.
		if (forcedBlock) {
			int weakest = -1, weakestPower = Integer.MAX_VALUE;
			for (int i = 0; i < mw.p2ForwardStates.size(); i++) {
				if (mw.p2ForwardStates.get(i) != CardState.ACTIVE) continue;
				int pw = mw.effectiveP2ForwardPower(i);
				if (pw < weakestPower) { weakestPower = pw; weakest = i; }
			}
			if (weakest >= 0) return weakest;
		}
		return null;
	}

	/**
	 * Which of {@code compelled}, P2 Forward indices all bound to block, takes the block: the
	 * strongest that survives {@code attackPower}, as an unforced block would pick, and failing
	 * that the weakest, the cheapest loss.
	 */
	private static int bestCompelledBlocker(List<Integer> compelled, int attackPower, IntUnaryOperator power) {
		int best = -1, bestPow = -1;
		for (int i : compelled) {
			int p = power.applyAsInt(i);
			if (p >= attackPower && p > bestPow) { best = i; bestPow = p; }
		}
		if (best >= 0) return best;
		int weakestPow = Integer.MAX_VALUE;
		for (int i : compelled) {
			int p = power.applyAsInt(i);
			if (p < weakestPow) { best = i; weakestPow = p; }
		}
		return best;
	}

	/**
	 * The P2 Characters that may block {@code attacker} as the board stands — its Forwards, and its
	 * Monsters and Backups acting as Forwards — with every restriction the attacker carries applied;
	 * none when it cannot be blocked at all. What {@link #chooseBlocker} picks from, and what a combat
	 * trick on P1's turn may turn into a blocker (decision 5).
	 */
	List<ForwardTarget> blockCandidates(ForwardTarget attacker) {
		CardData p1AttackerCard = attacker != null && attacker.isP1() ? mw.autoAbilityTriggers.fieldCardData(attacker) : null;
		boolean  p1AttackerHigherPower = false;
		int      p1AttackerPower       = 0;
		int      p1AttackerFieldPower  = 0;
		if (p1AttackerCard != null) {
			if (mw.p1CannotBeBlocked.contains(p1AttackerCard) || mw.hasSelfCannotBeBlockedFieldAbility(p1AttackerCard, true)
					|| mw.attackerConditionallyUnblockable(p1AttackerCard, true)) return List.of();
			p1AttackerFieldPower  = mw.fieldForwardPower(true, attacker.zone(), attacker.idx());
			p1AttackerHigherPower = mw.carriesHigherPowerBlockShield(p1AttackerCard, true);
			if (p1AttackerHigherPower) p1AttackerPower = p1AttackerFieldPower;
		}
		List<ForwardTarget> cands = new ArrayList<>();
		for (int i = 0; i < mw.p2ForwardStates.size(); i++) {
			CardData blocker = mw.p2ForwardCards.get(i);
			if (mw.p2CannotBlock.contains(blocker) || mw.p2CannotBlockPersistent.contains(blocker)) continue;
			if (mw.blockBarredByFieldCostLock(blocker)) continue;
			if (mw.p2ForwardStates.get(i) != CardState.ACTIVE) continue;
			if (mw.p1AttackerCostFiltersExclude(p1AttackerCard, blocker.cost())) continue;
			if (mw.attackerPowerFilterExcludes(p1AttackerCard, true,
					mw.fieldForwardPower(false, ForwardTarget.CardZone.FORWARD, i))) continue;
			if (p1AttackerHigherPower && mw.fieldForwardPower(false, ForwardTarget.CardZone.FORWARD, i) > p1AttackerPower) continue;
			if (mw.p2Turn.forwardCannotBlockInferiorPower && p1AttackerCard != null &&
				mw.fieldForwardPower(false, ForwardTarget.CardZone.FORWARD, i) > p1AttackerFieldPower) continue;
			cands.add(new ForwardTarget(false, i, ForwardTarget.CardZone.FORWARD));
		}
		for (int i = 0; i < mw.p2MonsterCards.size(); i++) {
			if (!mw.p2MonsterCanBlockAsForward(i)) continue;
			// Jack Garland 29-123R bars Monster blockers outright, so it gates the zone rather than
			// any one candidate — checked here for the same reason the human side checks it in
			// isMonsterBlockSelectable: this loop is how a Monster becomes a blocker for P2.
			if (p1AttackerCard != null && mw.barsMonsterForwardBlockers(p1AttackerCard)) continue;
			if (mw.p1AttackerCostFiltersExclude(p1AttackerCard, mw.p2MonsterCards.get(i).cost())) continue;
			if (mw.attackerPowerFilterExcludes(p1AttackerCard, true,
					mw.fieldForwardPower(false, ForwardTarget.CardZone.MONSTER, i))) continue;
			if (p1AttackerHigherPower && mw.fieldForwardPower(false, ForwardTarget.CardZone.MONSTER, i) > p1AttackerPower) continue;
			if (mw.p2Turn.forwardCannotBlockInferiorPower && p1AttackerCard != null &&
				mw.fieldForwardPower(false, ForwardTarget.CardZone.MONSTER, i) > p1AttackerFieldPower) continue;
			cands.add(new ForwardTarget(false, i, ForwardTarget.CardZone.MONSTER));
		}
		for (int i = 0; i < mw.p2BackupCards.length; i++) {
			if (!mw.p2BackupCanBlockAsForward(i)) continue;
			if (mw.p1AttackerCostFiltersExclude(p1AttackerCard, mw.p2BackupCards[i].cost())) continue;
			if (mw.attackerPowerFilterExcludes(p1AttackerCard, true,
					mw.fieldForwardPower(false, ForwardTarget.CardZone.BACKUP, i))) continue;
			if (p1AttackerHigherPower && mw.fieldForwardPower(false, ForwardTarget.CardZone.BACKUP, i) > p1AttackerPower) continue;
			if (mw.p2Turn.forwardCannotBlockInferiorPower && p1AttackerCard != null &&
				mw.fieldForwardPower(false, ForwardTarget.CardZone.BACKUP, i) > p1AttackerFieldPower) continue;
			cands.add(new ForwardTarget(false, i, ForwardTarget.CardZone.BACKUP));
		}
		return cands;
	}

	ForwardTarget chooseBlocker(int effectiveAttackerPower, ForwardTarget attacker) {
		return chooseBlocker(effectiveAttackerPower, attacker, false);
	}

	ForwardTarget chooseBlocker(int effectiveAttackerPower, ForwardTarget attacker, boolean forcedBlock) {
		// Attacker-side restrictions are read off the attacking card, so they bind an attacker
		// acting as a Forward from the Monster or Backup row just as they do one on the Forward row.
		CardData p1AttackerCard       = null;
		boolean  p1AttackerHigherPower = false;
		int      p1AttackerPower       = 0;
		if (attacker != null && attacker.isP1()) {
			p1AttackerCard = mw.autoAbilityTriggers.fieldCardData(attacker);
		}
		int      p1AttackerFieldPower = 0;
		if (p1AttackerCard != null) {
			if (mw.p1CannotBeBlocked.contains(p1AttackerCard)) return null;
			// The standing, damage-gated spelling of the same restriction (Ritz 11-063L), which is
			// re-read per block rather than recorded in the set above.
			if (mw.hasSelfCannotBeBlockedFieldAbility(p1AttackerCard, true)) return null;
			// The conditional spelling (Zidane 8-115L's hand-size gate, the "If you control X"
			// printings). P2's own attackers get this through attackerConditionallyUnblockable;
			// this is the same check on the side where the AI is the one declining to block.
			if (mw.attackerConditionallyUnblockable(p1AttackerCard, true)) return null;
			p1AttackerFieldPower  = mw.fieldForwardPower(true, attacker.zone(), attacker.idx());
			// Printed or granted alike — Lann 1-027H prints it, Deathgaze 5-063H hands it to
			// himself for the turn, and the AI has to decline the block either way.
			p1AttackerHigherPower = mw.carriesHigherPowerBlockShield(p1AttackerCard, true);
			if (p1AttackerHigherPower) p1AttackerPower = p1AttackerFieldPower;
		}

		List<ForwardTarget> cands = blockCandidates(attacker);

		// A P2 Forward compelled to block (MainWindow.forwardCompelledToBlock: Dio 26-075C's
		// attacker-specific grant, Ricard 6-103H's printing, a turn's "must block" such as Mika
		// 29-122R's) leaves the AI no choice but which compelled Forward blocks (rule 10.1.3.1.3),
		// so it is settled before anything else — and whether the block is survivable does not
		// lift it. Only a legal blocker is bound, which is what "if possible" means and why the
		// compelled Forwards are looked for among cands rather than over the whole field.
		CardData attackerCard = attacker != null ? mw.autoAbilityTriggers.fieldCardData(attacker) : null;
		List<Integer> compelled = new ArrayList<>();
		for (ForwardTarget t : cands)
			if (t.zone() == ForwardTarget.CardZone.FORWARD
					&& mw.forwardCompelledToBlock(mw.p2ForwardCards.get(t.idx()), attackerCard))
				compelled.add(t.idx());
		if (!compelled.isEmpty())
			return new ForwardTarget(false, bestCompelledBlocker(compelled, effectiveAttackerPower,
					i -> mw.fieldForwardPower(false, ForwardTarget.CardZone.FORWARD, i)),
					ForwardTarget.CardZone.FORWARD);

		// Otherwise pick the strongest blocker that survives the attack.
		ForwardTarget best = null; int bestPow = -1;
		for (ForwardTarget t : cands) {
			int p = mw.fieldForwardPower(false, t.zone(), t.idx());
			if (p >= effectiveAttackerPower && p > bestPow) { best = t; bestPow = p; }
		}

		// No active candidate survives — see if a "discard 1 card: if Elem1..., if Elem2..."
		// combat-trick ability (e.g. Firion) can create a winning block: either boosting an
		// existing candidate over the line (Fire: +power/First Strike), or activating a dull
		// Forward that would otherwise not be a legal blocker at all (Water: +power/activate).
		if (best == null) {
			ForwardTarget trick = findDiscardCombatTrickBlocker(
					effectiveAttackerPower, p1AttackerCard, p1AttackerHigherPower, p1AttackerPower);
			if (trick != null) return trick;
		}

		// Forced block (e.g. "Opponent must block X if possible"): no survivor found — pick weakest candidate.
		if (best == null && forcedBlock && !cands.isEmpty()) {
			int minPow = Integer.MAX_VALUE;
			for (ForwardTarget t : cands) {
				int p = mw.fieldForwardPower(false, t.zone(), t.idx());
				if (p < minPow) { best = t; minPow = p; }
			}
		}
		return best;
	}

	/** +N in "+N power" style effect text; 0 if no such fragment is present. */
	private static final Pattern BRANCH_POWER_BOOST = Pattern.compile("\\+(\\d+)\\s*power", Pattern.CASE_INSENSITIVE);

	private static int branchPowerBoost(String effectText) {
		Matcher m = BRANCH_POWER_BOOST.matcher(effectText);
		return m.find() ? Integer.parseInt(m.group(1)) : 0;
	}

	private static boolean branchGrantsFirstStrike(String effectText) {
		return effectText.toLowerCase().contains("first strike");
	}

	/**
	 * True when {@code effectText} activates {@code source} by name ("Activate Ghido."). Read both
	 * by the combat-trick reader, where activating turns a dull Forward into a legal blocker, and by
	 * {@link #tryP2UseAbility}, where it is the shape that must not be used on an already-active
	 * source.
	 */
	static boolean effectActivatesSelf(String effectText, CardData source) {
		return effectText.toLowerCase().contains("activate " + source.name().toLowerCase());
	}

	/** Index of the first hand card of {@code element}, or -1 if none. */
	private static int findHandCardIndex(List<CardData> hand, String element) {
		for (int i = 0; i < hand.size(); i++)
			if (hand.get(i).containsElement(element)) return i;
		return -1;
	}

	/**
	 * Looks for a P2 Forward — active or dull — whose "Discard 1 card: If the discarded card
	 * is of Elem1 Element, eff1. If ... Elem2 ..., eff2." ability, if paid with a matching card
	 * from hand, would let it survive and break {@code attackerPower} (or, for a dull Forward,
	 * become a legal blocker at all via an "activate" branch). If a viable trick is found, pays
	 * the discard and applies that branch's effect immediately, returning the now-eligible
	 * blocker. This is a same-instant tactical decision with no meaningful window for P1 to
	 * respond, so the effect is applied directly rather than round-tripped through the stack.
	 * Returns {@code null} if no such trick exists or no matching-element card is in hand.
	 */
	private ForwardTarget findDiscardCombatTrickBlocker(int effectiveAttackerPower, CardData p1AttackerCard,
			boolean p1AttackerHigherPower, int p1AttackerPower) {
		List<CardData> hand = mw.gameState.getP2Hand();
		if (hand.isEmpty()) return null;

		for (int i = 0; i < mw.p2ForwardStates.size(); i++) {
			CardData card = mw.p2ForwardCards.get(i);
			if (card == null) continue;
			if (mw.lostAbilitiesCards.contains(card)) continue;
			if (mw.p2CannotBlock.contains(card) || mw.p2CannotBlockPersistent.contains(card)) continue;
			if (mw.blockBarredByFieldCostLock(card)) continue;
			if (mw.p2ForwardFrozen.get(i)) continue; // frozen forwards can't become legal blockers regardless
			CardState state = mw.p2ForwardStates.get(i);
			boolean isDull = state != CardState.ACTIVE;
			if (mw.p1AttackerCostFiltersExclude(p1AttackerCard, card.cost())) continue;

			for (ActionAbility ability : card.actionAbilities()) {
				if (ability.discardCosts().size() != 1) continue;
				DiscardCost dc = ability.discardCosts().get(0);
				// Must be a plain "discard 1 card" cost — no filter, since either element could pay it.
				if (dc.count() != 1 || dc.cardName() != null || dc.element() != null
						|| dc.cardType() != null || dc.category() != null
						|| dc.job() != null) continue;
				List<ActionResolver.DiscardElementBranch> branches =
						ActionResolver.discardConditionalElementBranches(ability.effectText());
				if (branches == null) continue;
				if (!mw.canActivateAbility(ability, false, state, mw.p2ForwardPlayedOnTurn.get(i), card, false)) continue;

				for (ActionResolver.DiscardElementBranch branch : branches) {
					boolean activates = effectActivatesSelf(branch.effectText(), card);
					if (isDull && !activates) continue; // dull needs the activating branch specifically

					int basePower = mw.effectiveP2ForwardPower(i);
					int postPower = basePower + branchPowerBoost(branch.effectText());
					if (p1AttackerHigherPower && postPower > p1AttackerPower) continue;
					if (mw.p2Turn.forwardCannotBlockInferiorPower && p1AttackerCard != null
							&& postPower > p1AttackerPower) continue;
					// Equal power breaks BOTH characters — not "survives" per the user's ask — unless
					// First Strike breaks the attacker before it can deal damage back, in which
					// case reaching (not exceeding) the threshold is enough.
					boolean firstStrike = branchGrantsFirstStrike(branch.effectText());
					boolean survivesAndBreaks = firstStrike
							? postPower >= effectiveAttackerPower
							: postPower > effectiveAttackerPower;
					if (!survivesAndBreaks) continue;

					int handIdx = findHandCardIndex(hand, branch.element());
					if (handIdx < 0) continue; // no matching card to discard — no benefit possible

					CardData discarded = mw.playerBreakFromHand(false, handIdx);
					mw.lastDiscardedCostCard = discarded;
					mw.logEntry("[P2] " + card.name() + " — Discard cost: \""
							+ (discarded != null ? discarded.name() : "?") + "\" discarded");
					mw.refreshP2HandCountLabel();
					GameContext ctx = mw.buildGameContext(false);
					// The branch above only decided whether the trick is worth paying for; resolve the
					// whole ability so a multi-element discard gets every branch it satisfies, not just
					// the one that motivated the discard.
					Consumer<GameContext> effect = ActionResolver.parse(ability.effectText(), card);
					if (effect != null) {
						mw.logEntry("[P2] " + card.name() + " — " + branch.element() + " branch: " + branch.effectText());
						effect.accept(ctx);
					}
					if (ability.oncePerTurn())
						mw.usedOncePerTurnAbilities.computeIfAbsent(card, k -> new java.util.HashSet<>()).add(ability.effectText());
					return new ForwardTarget(false, i, ForwardTarget.CardZone.FORWARD);
				}
			}
		}
		return null;
	}

	// ── Action Ability AI ─────────────────────────────────────────────────

	/**
	 * Tries to activate any available P2 action ability this main phase.
	 *
	 * @param onDone run when no more usable abilities are found
	 * @param resume run <em>instead</em> after one is activated: activating changes what else is
	 *               affordable, so the phase restarts from the top rather than continuing down the
	 *               board.
	 *
	 * <p>{@code resume} is supplied by the caller rather than derived from {@code onDone}, and the
	 * distinction is the whole point of the second parameter. These helpers used to restart with
	 * {@code doMainPhase(onDone)} — re-entering the main phase carrying the continuation of the
	 * pass that was still running, so each activation wrapped another layer around the callback
	 * that ends the phase. Unwinding those layers cost the player one full priority round each:
	 * P2 auto-passed, P1 clicked Next, and instead of the phase advancing another identical
	 * handoff appeared. Three activations meant three rounds of clicking Next before Main 1 would
	 * end.
	 */
	private void tryP2ActionAbilities(Runnable onDone, Runnable resume) {
		if (mw.gameState.isP1GameOver()) return;
		GameState.GamePhase phase = mw.gameState.getCurrentPhase();
		boolean isAttackPrep = phase == GameState.GamePhase.ATTACK && mw.attackSubStep == 0;
		if (phase != GameState.GamePhase.MAIN_1 && phase != GameState.GamePhase.MAIN_2 && !isAttackPrep) {
			onDone.run();
			return;
		}
		for (int i = 0; i < mw.p2ForwardCards.size(); i++) {
			CardData card = mw.p2ForwardCards.get(i);
			if (card == null) continue;
			CardData eff = mw.p2ForwardPrimedTop.get(i) != null ? mw.p2ForwardPrimedTop.get(i) : card;
			if (tryP2UseAbility(eff, mw.p2ForwardFrozen.get(i), mw.p2ForwardStates.get(i),
					mw.p2ForwardPlayedOnTurn.get(i),
					mw.abilityCostDull(new ForwardTarget(false, i, ForwardTarget.CardZone.FORWARD)),
					resume)) return;
		}
		for (int i = 0; i < mw.p2BackupCards.length; i++) {
			CardData card = mw.p2BackupCards[i];
			if (card == null) continue;
			// Set aside to pay for a held card — the Back Attack Character on P1's turn, the trick in
			// P2's battles: not dulled for an ability of its own either.
			if (reservedBackup(i)) continue;
			if (tryP2UseAbility(card, mw.p2BackupFrozen[i], mw.p2BackupStates[i], 0,
					mw.abilityCostDull(new ForwardTarget(false, i, ForwardTarget.CardZone.BACKUP)),
					resume)) return;
		}
		for (int i = 0; i < mw.p2MonsterCards.size(); i++) {
			CardData card = mw.p2MonsterCards.get(i);
			if (card == null) continue;
			if (tryP2UseAbility(card, mw.p2MonsterFrozen.get(i), mw.p2MonsterStates.get(i),
					mw.p2MonsterPlayedOnTurn.get(i),
					mw.abilityCostDull(new ForwardTarget(false, i, ForwardTarget.CardZone.MONSTER)),
					resume)) return;
		}
		tryP2BzActionAbilities(() -> tryP2SharedOpponentAbilities(onDone, resume), resume);
	}

	/**
	 * Activates one reactive "ability/summon damage becomes 0" shield ability on a P2 Forward not
	 * already shielded, and returns whether it did — a play of {@link #playInWindow}'s.
	 */
	private boolean activateReactiveShield() {
		for (int i = 0; i < mw.p2ForwardCards.size(); i++) {
			CardData card = mw.p2ForwardCards.get(i);
			if (card == null) continue;
			if (mw.nullifyAbilityDmgSet.contains(card)) continue; // already shielded this turn
			if (mw.lostAbilitiesCards.contains(card)) continue;
			boolean isFrozen  = mw.p2ForwardFrozen.get(i);
			CardState state   = mw.p2ForwardStates.get(i);
			int playedTurn    = mw.p2ForwardPlayedOnTurn.get(i);
			final int fi      = i;
			for (ActionAbility ability : card.actionAbilities()) {
				if (!ActionResolver.isReactiveDamageShield(ability.effectText(), card)) continue;
				if (!mw.canActivateAbility(ability, isFrozen, state, playedTurn, card, false)) continue;
				if (ActionResolver.parse(ability.effectText(), card) == null) continue;
				List<Integer>        backupDullIndices = new ArrayList<>();
				Map<Integer, String> backupElems       = new LinkedHashMap<>();
				List<Integer>        discardIndices    = new ArrayList<>();
				Map<Integer, String> discardElems      = new LinkedHashMap<>();
				if (!p2PlanAbilityPayment(ability, card, backupDullIndices, backupElems, discardIndices, discardElems)) continue;
				mw.logEntry("[P2] Activates reactive shield: " + card.name() + " — " + ability.effectText());
				if (!mw.autoAbilityTriggers.executeP2AbilityActivation(ability, card,
						mw.abilityCostDull(new ForwardTarget(false, fi, ForwardTarget.CardZone.FORWARD)),
						backupDullIndices, discardIndices, 0)) {
					logAbandonedActivation(card);
					continue;
				}
				return true;
			}
		}
		return false;
	}

	/**
	 * Tries to activate any available P2 break-zone action abilities.
	 * Called at the end of {@link #tryP2ActionAbilities} before {@code onDone}, and takes the same
	 * pair: {@code onDone} when nothing fires, {@code resume} when something does.
	 */
	private void tryP2BzActionAbilities(Runnable onDone, Runnable resume) {
		List<CardData> bz = mw.gameState.getP2BreakZone();
		for (CardData card : bz) {
			for (ActionAbility ability : card.actionAbilities()) {
				if (ability.breakZoneOnly() == null) continue;
				if (!mw.autoAbilityTriggers.canActivateBzAbility(ability, card, false)) continue;
				if (ActionResolver.parse(ability.effectText(), card) == null) continue;
				if (abilityHarmsChosenTarget(ability) && !p1HasAnyForward()) continue;
				// Mirror of the above for the other side: an ability that can only choose a Forward P2
				// controls does nothing while P2 has none, so paying its cost is pure waste.
				if (ActionResolver.targetsOnlyOwnForwards(ability.effectText()) && !p2HasAnyForward()) continue;
				// And the other direction: a pure buff with nothing of P2's own to land on would
				// only make one of P1's Characters bigger.
				if (buffWouldOnlyHelpP1(ability, card)) continue;
				// A shield against the opponent's own Summons/abilities (Krile (XIV) 6-071H) gains
				// nothing when it resolves — it only pays off while their effect is already on the
				// stack, and P2 passes priority rather than responding.
				if (ActionResolver.isOwnForwardProtectionEffect(ability.effectText())) continue;
				// "Opponent cannot search" only matters if P1 actually has a search option available
				// this turn — otherwise it's a wasted Break Zone activation. The field-scan twin in
				// tryP2UseAbility is the one Mog (VI) 17-124H reaches.
				if (ActionResolver.isOpponentCannotSearchAbility(ability.effectText()) && !p1HasSearchOption()) continue;

				List<Integer>        backupDullIndices = new ArrayList<>();
				Map<Integer, String> backupElems       = new LinkedHashMap<>();
				List<Integer>        discardIndices    = new ArrayList<>();
				Map<Integer, String> discardElems      = new LinkedHashMap<>();
				if (!p2PlanAbilityPayment(ability, card, backupDullIndices, backupElems, discardIndices, discardElems))
					continue;

				mw.logEntry("[P2] Activates BZ ability: " + card.name() + " — " + ability.effectText());
				if (!mw.autoAbilityTriggers.executeP2AbilityActivation(ability, card, () -> {},
						backupDullIndices, discardIndices, 0)) {
					logAbandonedActivation(card);
					continue;
				}
				step(resume);
				return;
			}
		}
		onDone.run();
	}

	/**
	 * Logs a P2 activation the payment abandoned.  The "[P2] Activates …" line has already claimed
	 * something happened, so this says otherwise rather than leaving the log reading as though the
	 * ability resolved.
	 *
	 * <p>Every caller pairs it with {@code continue} rather than {@code step(resume)}, and that is
	 * the part that matters: an abandonment which commits nothing leaves the board exactly as the
	 * Main Phase scan found it, so restarting the scan picks the same ability again, forever. Moving
	 * on to the next ability instead makes the loop impossible regardless of why the payment bailed.
	 */
	private void logAbandonedActivation(CardData card) {
		mw.logEntry("[P2] " + card.name() + " — cost could not be paid; activation abandoned");
	}

	/**
	 * Minimum P2 hand size to keep after paying a "usableByEitherPlayer" ability's discard
	 * cost.  Activating one of P1's shared-use abilities only ever helps P2 indirectly (e.g.
	 * stripping a protective counter from P1's card), so it's not worth spending down P2's own
	 * hand advantage to do it.
	 */
	private static final int MIN_HAND_AFTER_SHARED_ABILITY = 2;

	/**
	 * "Each player can use this ability." — tries to activate a {@code usableByEitherPlayer}
	 * ability on one of P1's field cards, paying costs from P2's own resources.  Called as a
	 * low-priority fallback after P2 has exhausted its own abilities this main phase.  Same
	 * {@code onDone}/{@code resume} pair as {@link #tryP2ActionAbilities}.
	 */
	private void tryP2SharedOpponentAbilities(Runnable onDone, Runnable resume) {
		for (int i = 0; i < mw.p1ForwardCards.size(); i++) {
			CardData card = mw.p1ForwardCards.get(i);
			if (card == null) continue;
			if (tryP2UseOpponentSharedAbility(card, mw.p1ForwardFrozen.get(i), mw.p1ForwardStates.get(i),
					mw.p1ForwardPlayedOnTurn.get(i),
					mw.abilityCostDull(new ForwardTarget(true, i, ForwardTarget.CardZone.FORWARD)),
					resume)) return;
		}
		for (int i = 0; i < mw.p1BackupCards.length; i++) {
			CardData card = mw.p1BackupCards[i];
			if (card == null) continue;
			if (tryP2UseOpponentSharedAbility(card, mw.p1BackupFrozen[i], mw.p1BackupStates[i], 0,
					mw.abilityCostDull(new ForwardTarget(true, i, ForwardTarget.CardZone.BACKUP)),
					resume)) return;
		}
		for (int i = 0; i < mw.p1MonsterCards.size(); i++) {
			CardData card = mw.p1MonsterCards.get(i);
			if (card == null) continue;
			if (tryP2UseOpponentSharedAbility(card, mw.p1MonsterFrozen.get(i), mw.p1MonsterStates.get(i),
					mw.p1MonsterPlayedOnTurn.get(i),
					mw.abilityCostDull(new ForwardTarget(true, i, ForwardTarget.CardZone.MONSTER)),
					resume)) return;
		}
		onDone.run();
	}

	/**
	 * Checks each {@code usableByEitherPlayer} action ability on {@code card} (a P1 card).
	 * If one is usable, affordable without giving up P2's hand advantage, and its effect is
	 * implemented, pays its cost from P2's resources and schedules {@code resume}.
	 * Returns {@code true} if an ability was dispatched.
	 */
	private boolean tryP2UseOpponentSharedAbility(CardData card, boolean isFrozen, CardState state,
			int playedTurn, Runnable applyDull, Runnable resume) {
		if (mw.lostAbilitiesCards.contains(card)) return false;
		for (ActionAbility ability : card.actionAbilities()) {
			if (!ability.usableByEitherPlayer()) continue;
			if (ability.whileCardInHand()) continue;
			if (ability.breakZoneOnly() != null) continue;
			if (!mw.canActivateAbility(ability, isFrozen, state, playedTurn, card, false)) continue;
			if (ActionResolver.parse(ability.effectText(), card) == null) continue;
			if (!ability.discardCosts().isEmpty()) {
				int totalDiscard = ability.discardCosts().stream().mapToInt(DiscardCost::count).sum();
				if (mw.gameState.getP2Hand().size() - totalDiscard < MIN_HAND_AFTER_SHARED_ABILITY) continue;
			}

			List<Integer>        backupDullIndices = new ArrayList<>();
			Map<Integer, String> backupElems       = new LinkedHashMap<>();
			List<Integer>        discardIndices    = new ArrayList<>();
			Map<Integer, String> discardElems      = new LinkedHashMap<>();
			if (!p2PlanAbilityPayment(ability, card, backupDullIndices, backupElems, discardIndices, discardElems))
				continue;

			mw.logEntry("[P2] Activates shared ability on " + card.name() + " — " + ability.effectText());
			if (!mw.autoAbilityTriggers.executeP2AbilityActivation(ability, card, applyDull,
					backupDullIndices, discardIndices, 0)) {
				logAbandonedActivation(card);
				continue;
			}
			step(resume);
			return true;
		}
		return false;
	}

	/**
	 * Checks each field action ability on {@code card}.  If one is usable, affordable,
	 * and its effect is implemented, pays its cost and schedules {@code resume}.
	 * Returns {@code true} if an ability was dispatched.
	 */
	private boolean tryP2UseAbility(CardData card, boolean isFrozen, CardState state,
			int playedTurn, Runnable applyDull, Runnable resume) {
		if (mw.lostAbilitiesCards.contains(card)) return false;
		for (ActionAbility ability : card.actionAbilities()) {
			if (ability.whileCardInHand()) continue;
			if (ability.breakZoneOnly() != null) continue;
			if (ability.whileCardAttacking() != null || ability.whileCardBlocking() != null
					|| ability.whilePartyAttacking() || ability.hasBlockingTargetEffect()
					|| ability.blockerForAttacker() != null) continue;
			if (!mw.canActivateAbility(ability, isFrozen, state, playedTurn, card, false)) continue;
			if (ActionResolver.parse(ability.effectText(), card) == null) continue;
			if (abilityHarmsChosenTarget(ability) && !p1HasAnyForward()) continue;
			// Mirror of the above for the other side: an ability that can only choose a Forward P2
			// controls does nothing while P2 has none, so paying its cost is pure waste.
			if (ActionResolver.targetsOnlyOwnForwards(ability.effectText()) && !p2HasAnyForward()) continue;
			// And the other direction: a pure buff with nothing of P2's own to land on — once the
			// cost has taken the source off the field — would only make one of P1's bigger.
			if (buffWouldOnlyHelpP1(ability, card)) continue;
			// A shield against the opponent's own Summons/abilities (Krile (XIV) 6-071H) gains
			// nothing when it resolves — it only pays off while their effect is already on the
			// stack, and P2 passes priority rather than responding. Same reasoning as the
			// self-bounce skip below.
			if (ActionResolver.isOwnForwardProtectionEffect(ability.effectText())) continue;
			// "Your opponent cannot search" (Mog (VI) 17-124H, paid by putting Mog into the Break
			// Zone) buys nothing unless P1 has a search it could make this turn.
			if (ActionResolver.isOpponentCannotSearchAbility(ability.effectText()) && !p1HasSearchOption()) continue;
			// A "return Forward to hand" bounce paid by sacrificing your own card(s) to the Break
			// Zone is only worth it when it removes an opponent's Forward. A self-only bounce
			// ("Forward you control") is never a proactive gain — it's a defensive save best left for
			// a reactive window — and an any-target bounce does nothing when P1 has no Forward to
			// return. Skipping both stops plays like Sahagin Chief sacrificing itself to bounce the
			// very Forward P2 just cast.
			if (!ability.breakZoneCosts().isEmpty()
					&& ActionResolver.isReturnForwardToHandEffect(ability.effectText())
					&& (ActionResolver.isReturnOwnForwardToHandEffect(ability.effectText()) || !p1HasAnyForward()))
				continue;
			// Don't waste a once-per-turn become-Forward ability on a Monster played this turn:
			// the resulting Forward can't attack yet, so hold it for blocking on P1's turn instead.
			if (card.isMonster() && ability.oncePerTurn()
					&& playedTurn == mw.gameState.getTurnNumber()
					&& ActionResolver.isBecomeForwardUntilEotEffect(ability.effectText(), card))
				continue;
			// Don't spend hand cards on a "discard → self power boost until EOT" ability unless
			// a P1 Forward already outclasses the source, making the boost potentially life-saving.
			if (!ability.discardCosts().isEmpty()
					&& ActionResolver.isTempSelfPowerBoostEffect(ability.effectText(), card)
					&& !p1ThreatensCard(card))
				continue;
			// Reactive shields ("if [card] is dealt damage by Summons/abilities, damage becomes 0")
			// are only useful on the opponent's turn; skip them here and let p2AutoPass handle them.
			if (ActionResolver.isReactiveDamageShield(ability.effectText(), card)) continue;
			// Dulling an opponent's Forward buys one thing: it cannot block. With no attacker of
			// P2's own to walk past it, that is a cost paid for nothing — 1-113R Lightning was
			// spending her once-per-turn dull on a Dadaluma with nothing behind it to attack.
			// The dull-or-activate toggle survives the check when P2 has a dull Forward of its own,
			// since the other half of the toggle is worth the cost on its own terms.
			if (ActionResolver.isOffensiveDullEffect(ability.effectText())
					&& !p2HasAnyAttacker()
					&& !(ActionResolver.isDullOrActivateToggle(ability.effectText()) && p2HasDullForward()))
				continue;
			// Doublecast is only worth its cost when a chain can actually fire: a payable hand
			// Summon with a strictly cheaper Summon alongside it (which would then cast free).
			if (ActionResolver.isDoublecastFreeSummonsEffect(ability.effectText())
					&& (mw.p2DoublecastFreeSummons || !p2CanStartAffordableDoublecastChain(card))) continue;
			// "Discard 1 card: If Elem1..., if Elem2..." combat tricks (e.g. Firion) are reserved
			// for chooseBlocker's combat-trick evaluation: activating one here would either waste
			// a card outright (no matching-element card in hand) or grant a boost with no relevant
			// combat to use it in (e.g. no Haste to attack with the turn it enters the field).
			if (ActionResolver.discardConditionalElementBranches(ability.effectText()) != null) continue;
			// A self-boost with nothing to show for it — a keyword the source already carries, or
			// a boost no block on the board would turn.
			if (p2ShouldHoldSelfBoost(ability, card)) continue;
			// A card-for-damage trade that breaks nothing is worth holding for a later board.
			if (p2ShouldHoldDamageAbility(ability)) continue;
			// Activating an already-active source changes nothing, and can now loop forever.
			if (p2ShouldHoldActivateSelf(ability, card, state)) continue;
			// Bouncing the source to play a card from hand is a swap, worth only a dearer card.
			if (p2ShouldHoldSelfBounceSwap(ability, card)) continue;
			// Gogo's "Mimic" replays a special ability a Character used this turn — pointless (and a
			// wasted S + Dull cost) when none other than Mimic itself has been used yet.
			if (ActionResolver.isUseSpecialAbilityUsedThisTurnEffect(ability.effectText())
					&& !hasMimicableSpecialAbility(ability))
				continue;

			List<Integer>        backupDullIndices = new ArrayList<>();
			Map<Integer, String> backupElems       = new LinkedHashMap<>();
			List<Integer>        discardIndices    = new ArrayList<>();
			Map<Integer, String> discardElems      = new LinkedHashMap<>();
			if (!p2PlanAbilityPayment(ability, card, backupDullIndices, backupElems, discardIndices, discardElems))
				continue;

			// Determine X value for X-cost abilities
			int xValue = 0;
			if (ability.hasXCost()) {
				// Count active P2 backups not needed for CP payment
				int usedBackups = backupDullIndices.size();
				int totalActiveBackups = 0;
				CardData[] payable = p2CpBackups();
				for (int bi = 0; bi < payable.length; bi++) {
					if (payable[bi] != null && mw.p2BackupStates[bi] == CardState.ACTIVE && !mw.p2BackupFrozen[bi])
						totalActiveBackups++;
				}
				xValue = totalActiveBackups - usedBackups;
				if (xValue < 1) continue; // skip if no remaining backups for X
			}

			mw.logEntry("[P2] Activates ability: " + card.name() + " — " + ability.effectText());
			if (!mw.autoAbilityTriggers.executeP2AbilityActivation(ability, card, applyDull,
					backupDullIndices, discardIndices, xValue)) {
				logAbandonedActivation(card);
				continue;
			}
			step(resume);
			return true;
		}
		return false;
	}

	/**
	 * Returns true if P2 can actually start a Doublecast chain this turn: some hand Summon that
	 * (a) has a strictly cheaper Summon alongside it in hand (which would then cast free), and
	 * (b) P2 can afford right now per {@link #p2PlanPayment} — with one hand copy of
	 * {@code source}'s name reserved for the special's 《S》 discard cost and therefore not
	 * counted as a CP source.  Payment planning here is speculative (the plan lists are
	 * discarded); it prevents burning the ability when no chain could ever fire.
	 */
	private boolean p2CanStartAffordableDoublecastChain(CardData source) {
		if (mw.summonCastingBanned(false)) return false;
		List<CardData> hand = mw.gameState.getP2Hand();
		// Reserve the copy that will pay the 《S》 discard cost. Read through the shared rule rather
		// than matched on the name here: a primed Forward answers to its primer's name too, and a
		// proxy substitute (Tifa 26-076H) accepts a card of another name entirely, so a name test
		// alone called chains unaffordable that the payment would have paid for.
		int reservedIdx = p2SpecialCostPayerSlot(source);
		for (int i = 0; i < hand.size(); i++) {
			if (i == reservedIdx) continue;
			CardData starter = hand.get(i);
			if (!starter.isSummon()) continue;
			if (mw.summonCastBlocked(starter, false)) continue;
			boolean hasCheaper = false;
			for (int j = 0; j < hand.size(); j++) {
				if (j == i || j == reservedIdx) continue;
				CardData o = hand.get(j);
				if (o.isSummon() && o.cost() < starter.cost()) { hasCheaper = true; break; }
			}
			if (!hasCheaper) continue;
			List<Integer>        backups      = new ArrayList<>();
			Map<Integer, String> backupElems  = new LinkedHashMap<>();
			List<Integer>        discards     = new ArrayList<>();
			Map<Integer, String> discardElems = new LinkedHashMap<>();
			if (p2PlanPayment(starter, mw.castCostFor(starter, false), i, reservedIdx,
					backups, backupElems, discards, discardElems))
				return true;
		}
		return false;
	}

	/**
	 * Returns true if any P1 Forward (or temp-forward Monster) has effective power strictly
	 * greater than {@code card}'s current effective power — i.e. {@code card} would lose a
	 * combat with at least one P1 attacker without help.  Used to decide whether a
	 * discard-to-power-boost ability is worth activating.
	 */
	private boolean p1ThreatensCard(CardData card) {
		int sourcePower = p2EffectivePowerOf(card);
		for (int i = 0; i < mw.p1ForwardCards.size(); i++) {
			if (mw.p1ForwardCards.get(i) == null) continue;
			if (mw.effectiveP1ForwardPower(i) > sourcePower) return true;
		}
		for (int i = 0; i < mw.p1MonsterCards.size(); i++) {
			if (mw.p1MonsterCards.get(i) == null || !mw.isP1MonsterTemporarilyForward(i)) continue;
			if (mw.effectiveP1MonsterPower(i) > sourcePower) return true;
		}
		return false;
	}

	/** Effective power of a P2 field card (Forward or Monster); falls back to base power. */
	private int p2EffectivePowerOf(CardData card) {
		for (int i = 0; i < mw.p2ForwardCards.size(); i++)
			if (mw.p2ForwardCards.get(i) == card) return mw.effectiveP2ForwardPower(i);
		for (int i = 0; i < mw.p2MonsterCards.size(); i++)
			if (mw.p2MonsterCards.get(i) == card) return mw.effectiveP2MonsterPower(i);
		return card.power();
	}

	/**
	 * True when P2 should hold an "Activate [self]" ability rather than use it, because the source
	 * is already active and the effect would change nothing.
	 *
	 * <p>Wasted CP was the whole cost of this before; it is a hang now. A Backup may fund its own
	 * ability by dulling for CP ({@link CpPaymentUtils#sourceCanFundOwnAbility}), so Ghido 3-131H's
	 * "《Water》: Activate Ghido." pays the 《Water》 with its own dull and is handed that same dull
	 * straight back — no net cost, an identical board, and a Main Phase that restarts and does it
	 * again forever. The ability exists to undo a dull, which is exactly why holding it while the
	 * source is active loses nothing.
	 *
	 * <p>Ghido is the only card in the corpus that can reach this state (the only Backup ability
	 * with a CP cost, no 《Dull》 cost, and an effect that re-activates its own source), but any
	 * future effect that hands its source's dull back at no net cost belongs here too.
	 */
	boolean p2ShouldHoldActivateSelf(ActionAbility ability, CardData source, CardState state) {
		return state == CardState.ACTIVE && effectActivatesSelf(ability.effectText(), source);
	}

	/**
	 * True when P2 should hold an ability that returns its own source to hand and plays a card from
	 * hand — Onion Knight 1-181H ("《1》《Dull》, return Onion Knight to its owner's hand: You may play
	 * 1 Card Name Onion Knight from your hand"), Onion Knight 17-044R. Used proactively that is a
	 * swap: the source's cost goes back to hand and the ability's CP is spent, so it gains only when
	 * the card it would play is dearer than both together — a 4-cost Onion Knight for 1-181H's 2 + 1.
	 *
	 * <p>Without this 1-181H played itself straight back — the only Onion Knight in hand once it had
	 * returned, and active again with Haste — and repeated that for 1 CP a time until nothing was
	 * left to pay with. The source is on the field while this is asked, so it is never the card
	 * weighed, and a second copy of it is no dearer than the first. Its other use, pulling the source
	 * out of a battle that would break it, is {@link #p2SelfBounceSave}'s and does not come here.
	 */
	boolean p2ShouldHoldSelfBounceSwap(ActionAbility ability, CardData source) {
		if (!returnsSourceToHand(ability, source)) return false;
		String play = ability.effectText().trim().replaceFirst("(?i)^you\\s+may\\s+", "");
		Function<GameContext, CardData> pick = ActionResolverHand.bestPlayableFromHand(play, 0);
		if (pick == null) return false;
		CardData best = pick.apply(mw.buildGameContext(false));
		return best == null || best.cost() <= source.cost() + ability.cpCost().size();
	}

	/**
	 * P1 has put {@code entry} on the Stack and P2 holds priority on it: responds if anything is
	 * worth responding with ({@link #playInWindow}). Returns {@code true} when P2 did — a response on
	 * the Stack is then on top of {@code entry} and resolving ahead of it, and P2 is asked again when
	 * the Stack comes back round to the entry.
	 */
	boolean respondToStack(StackEntry entry) {
		return playInWindow(PriorityWindow.STACK_RESPONSE, entry);
	}

	/**
	 * Saves a Forward {@code entry} would break by returning it to hand with its own ability
	 * ({@link #p2SelfBounceSave}). Only the choices {@code entry} has already made are weighed, read
	 * through {@link MainWindow#liveTargets} so a target that has since moved is still recognised.
	 */
	private boolean saveFromStackEntry(StackEntry entry) {
		List<ForwardTarget> targets = mw.liveTargets(entry, false);
		String text = mw.resolvingEffectText(entry);
		if (targets == null || text == null) return false;
		for (ForwardTarget t : targets) {
			if (t.isP1() || t.zone() != ForwardTarget.CardZone.FORWARD) continue;
			if (!p2EffectWouldBreak(text, t)) continue;
			if (p2SelfBounceSave(t)) return true;
		}
		return false;
	}

	// ── Stack responses (HANDOFF-cpu-opponents-turn.md decision 7) ───────

	/** What a P1 entry would do to one of P2's Characters it has chosen. */
	private enum Threat { BREAK, DAMAGE, REMOVE, POWER_LOSS }

	/** "… remove it/them from the game." / "… put it/them into the Break Zone." — gone either way. */
	private static final Pattern REMOVES_CHOSEN = Pattern.compile(
			"(?i)\\b(?:remove\\s+(?:it|them)\\s+from\\s+the\\s+game|put\\s+(?:it|them)\\s+into\\s+the\\s+Break\\s+Zone)\\b");
	/** "… it loses N power" / "they lose N power" — to 0 or less, and it is put into the Break Zone. */
	private static final Pattern LOSES_POWER_CHOSEN = Pattern.compile("(?i)\\b(?:it\\s+loses|they\\s+lose)\\s+(\\d+)\\s+power\\b");

	/**
	 * What P1's entry, reading {@code text}, would do to the P2 Character at {@code t} that it has
	 * chosen — break it, deal it lethal damage, remove it, take its power to 0 — or {@code null}
	 * when it would leave it on the field. Read as {@link #p2EffectWouldBreak} reads it: narrowly,
	 * so nothing is paid for on a guess. A break or damage aimed at a Forward that cannot be broken
	 * threatens nothing.
	 */
	private Threat threatTo(String text, ForwardTarget t) {
		boolean unbreakable = t.zone() == ForwardTarget.CardZone.FORWARD
				&& mw.effectiveHasTrait(false, t.idx(), CardData.Trait.CANNOT_BE_BROKEN);
		if (REMOVES_CHOSEN.matcher(text).find()) return Threat.REMOVE;
		Matcher loses = LOSES_POWER_CHOSEN.matcher(text);
		if (loses.find() && t.zone() != ForwardTarget.CardZone.BACKUP
				&& mw.fieldForwardBreakableBy(false, t.zone(), t.idx(), Integer.parseInt(loses.group(1)))) return Threat.POWER_LOSS;
		if (unbreakable) return null;
		if (BREAKS_CHOSEN.matcher(text).find()) return Threat.BREAK;
		Matcher dmg = DAMAGES_CHOSEN.matcher(text);
		if (dmg.find() && t.zone() != ForwardTarget.CardZone.BACKUP
				&& mw.fieldForwardBreakableBy(false, t.zone(), t.idx(), Integer.parseInt(dmg.group(1)))) return Threat.DAMAGE;
		return null;
	}

	/** A response P2 could make now: what it is, what it does, how many cards it discards, and the play. */
	private record ResponsePlay(String what, int discards, int cost, Runnable play) {}

	/**
	 * Answers P1's Stack {@code entry} (decision 7), and returns whether P2 did — after the
	 * self-bounce save, which it keeps. Only the choices the entry has already made are weighed
	 * ({@link MainWindow#liveTargets}):
	 * <ul>
	 *   <li>an entry that would break or remove one of P2's Characters is <b>cancelled</b> — a Summon,
	 *       ability or Back Attack arrival whose cancel takes it ({@link ActionResolver#stackCancelFilter})
	 *       — or the Character is <b>shielded</b> — a "damage becomes 0" trick against damage, a "cannot
	 *       be broken" one against damage or a break — whichever is cheapest;</li>
	 *   <li>an entry that buffs one of P1's Forwards ({@link CombatTrick}) is answered by <b>breaking
	 *       that Forward</b> with a removal that breaks it outright now ({@link #availableRemoval}).</li>
	 * </ul>
	 * It may discard to pay: the response saves or breaks a card.
	 */
	private boolean answerStackEntry(StackEntry entry) {
		if (entry == null || !entry.isP1()) return false;
		String text = mw.resolvingEffectText(entry);
		List<ForwardTarget> targets = mw.liveTargets(entry, false);
		if (text == null || targets == null) return false;
		String source = entry.source().name();

		List<ResponsePlay> saves = new ArrayList<>(cancelsFor(entry));
		for (ForwardTarget t : targets) {
			if (t.isP1() || t.zone() == ForwardTarget.CardZone.BREAK_ZONE) continue;
			Threat threat = threatTo(text, t);
			if (threat != null) saves.addAll(shieldsFor(t, threat));
		}
		boolean threatens = targets.stream().anyMatch(t -> !t.isP1() && t.zone() != ForwardTarget.CardZone.BREAK_ZONE
				&& threatTo(text, t) != null);
		if (threatens && !saves.isEmpty()) {
			saves.sort(Comparator.comparingInt(ResponsePlay::discards).thenComparingInt(ResponsePlay::cost));
			ResponsePlay best = saves.get(0);
			mw.logEntry("[P2] Answers " + source + " — " + best.what());
			best.play().run();
			return true;
		}

		if (CombatTrick.of(text) != null) {
			for (ForwardTarget t : targets) {
				if (!t.isP1() || t.zone() != ForwardTarget.CardZone.FORWARD) continue;
				List<RemovalPlay> plays = availableRemoval(t);
				if (plays.isEmpty()) continue;
				CardData buffed = mw.autoAbilityTriggers.fieldCardData(t);
				mw.logEntry("[P2] Answers " + source + " — " + plays.get(0).what() + " breaks " + buffed.name()
						+ " before the buff lands");
				mw.aiAimedTarget = buffed;
				plays.get(0).play().run();
				return true;
			}
		}
		return false;
	}

	/** "… of cost N or less/more" in a cancel's choice — 4-136C Summoner's bound. */
	private static final Pattern CANCEL_COST_BOUND = Pattern.compile("(?i)\\bof\\s+cost\\s+(\\d+)\\s+or\\s+(less|more)\\b");

	/**
	 * Whether the cancel reading {@code text}, made for P2, takes {@code entry}: its own filter
	 * ({@link ActionResolver#stackCancelFilter}), a printed cost bound, and the entry not protected
	 * from cancels. A cancel that lets P1 pay or discard to keep the entry is no answer — P1 decides it.
	 */
	private boolean cancels(String text, StackEntry entry) {
		if (text == null || text.matches("(?is).*\\b(?:doesn't|does\\s+not|unless)\\b.*")) return false;
		Predicate<StackEntry> filter = ActionResolver.stackCancelFilter(text, false);
		if (filter == null || !filter.test(entry) || mw.stackEntryProtectedFromCancel(entry)) return false;
		Matcher bound = CANCEL_COST_BOUND.matcher(text);
		if (bound.find()) {
			int n = Integer.parseInt(bound.group(1));
			int cost = entry.source().cost();
			if (bound.group(2).equalsIgnoreCase("less") ? cost > n : cost < n) return false;
		}
		return true;
	}

	/** The cancels P2 could play now that take {@code entry}: Summons, Back Attack arrivals, abilities on its field. */
	private List<ResponsePlay> cancelsFor(StackEntry entry) {
		List<ResponsePlay> out = new ArrayList<>();
		List<CardData> hand = mw.gameState.getP2Hand();
		if (!mw.p2CastLimitReached()) {
			for (int i = 0; i < hand.size(); i++) {
				CardData c = hand.get(i);
				String text = null;
				if (c.isSummon()) {
					if (mw.summonCastingBanned(false) || mw.summonCastBlocked(c, false) || !mw.castRestrictionMet(c, false)) continue;
					text = c.summonEffect();
				} else if (c.hasTrait(CardData.Trait.BACK_ATTACK) && castableOnOpponentsTurn(c)) {
					text = AutoAbilityTriggers.ownArrivalEffects(c).stream().filter(e -> cancels(e, entry)).findFirst().orElse(null);
				}
				if (!cancels(text, entry)) continue;
				P2Plan plan = planCast(c, i, false);
				if (plan == null) continue;
				out.add(new ResponsePlay("cancel with " + c.name(), plan.discardIndices().size(), c.cost(),
						() -> executeP2HandPlay(plan)));
			}
		}
		forEachFieldAbility((slot, card, ability) -> {
			if (!cancels(ability.effectText(), entry)) return;
			addAbilityResponse(out, "cancel with " + card.name() + "'s ability", slot, card, ability);
		});
		return out;
	}

	/** The shields P2 could play now that keep its Character at {@code t} from what {@code threat} would do. */
	private List<ResponsePlay> shieldsFor(ForwardTarget t, Threat threat) {
		List<ResponsePlay> out = new ArrayList<>();
		if (t.zone() != ForwardTarget.CardZone.FORWARD || (threat != Threat.DAMAGE && threat != Threat.BREAK)) return out;
		CardData forward = mw.autoAbilityTriggers.fieldCardData(t);
		for (TrickPlay tp : availableTricks(t, false)) {
			CombatTrick trick = tp.trick();
			if (!trick.shield() || (threat == Threat.BREAK && !trick.unbreakable())) continue;
			out.add(new ResponsePlay("shields " + forward.name() + " with " + tp.what(), tp.discards(), tp.cost(), () -> {
				mw.aiAimedTarget = forward;
				tp.play().run();
			}));
		}
		return out;
	}

	/** Something on P2's field that may have an ability to use: its slot, the card, and the ability. */
	@FunctionalInterface
	private interface FieldAbilityVisitor { void visit(ForwardTarget slot, CardData card, ActionAbility ability); }

	/** Every action ability on P2's field usable now, as {@link MainWindow#canActivateAbility} judges it. */
	private void forEachFieldAbility(FieldAbilityVisitor visitor) {
		for (int i = 0; i < mw.p2ForwardCards.size(); i++)
			visitAbilities(visitor, new ForwardTarget(false, i, ForwardTarget.CardZone.FORWARD), mw.p2ForwardCards.get(i),
					mw.p2ForwardFrozen.get(i), mw.p2ForwardStates.get(i), mw.p2ForwardPlayedOnTurn.get(i));
		for (int i = 0; i < mw.p2BackupCards.length; i++)
			if (mw.p2BackupCards[i] != null)
				visitAbilities(visitor, new ForwardTarget(false, i, ForwardTarget.CardZone.BACKUP), mw.p2BackupCards[i],
						mw.p2BackupFrozen[i], mw.p2BackupStates[i], 0);
		for (int i = 0; i < mw.p2MonsterCards.size(); i++)
			visitAbilities(visitor, new ForwardTarget(false, i, ForwardTarget.CardZone.MONSTER), mw.p2MonsterCards.get(i),
					mw.p2MonsterFrozen.get(i), mw.p2MonsterStates.get(i), mw.p2MonsterPlayedOnTurn.get(i));
	}

	private void visitAbilities(FieldAbilityVisitor visitor, ForwardTarget slot, CardData card, boolean frozen,
			CardState state, int playedOn) {
		if (card == null || mw.lostAbilitiesCards.contains(card)) return;
		for (ActionAbility ability : card.actionAbilities())
			if (mw.canActivateAbility(ability, frozen, state, playedOn, card, false)) visitor.visit(slot, card, ability);
	}

	/** Adds {@code ability} as a response when P2 can pay for it. */
	private void addAbilityResponse(List<ResponsePlay> out, String what, ForwardTarget slot, CardData card, ActionAbility ability) {
		List<Integer>        backups     = new ArrayList<>();
		Map<Integer, String> backupElems = new LinkedHashMap<>();
		List<Integer>        discards    = new ArrayList<>();
		Map<Integer, String> discElems   = new LinkedHashMap<>();
		if (!p2PlanAbilityPayment(ability, card, backups, backupElems, discards, discElems)) return;
		out.add(new ResponsePlay(what, discards.size(), ability.cpCost().size(),
				() -> mw.autoAbilityTriggers.executeP2AbilityActivation(ability, card, mw.abilityCostDull(slot),
						backups, discards, 0)));
	}

	/** "… break it/them." — the chosen are broken outright. */
	private static final Pattern BREAKS_CHOSEN = Pattern.compile("(?i)\\bbreak\\s+(?:it|them)\\b");
	/** "… deal it/them N damage." */
	private static final Pattern DAMAGES_CHOSEN = Pattern.compile("(?i)\\bdeal\\s+(?:it|them)\\s+(\\d+)\\s+damage\\b");

	/**
	 * Whether an effect reading {@code text} breaks P2's Forward at {@code t}: it breaks what it
	 * chose, or deals it at least the damage it has left. Deliberately narrow — an effect it cannot
	 * read is taken to spare the Forward, so a response is never paid for on a guess.
	 */
	private boolean p2EffectWouldBreak(String text, ForwardTarget t) {
		if (BREAKS_CHOSEN.matcher(text).find()) return true;
		Matcher m = DAMAGES_CHOSEN.matcher(text);
		if (!m.find()) return false;
		int left = mw.fieldForwardPower(false, t.zone(), t.idx()) - mw.fieldCombatDamage(false, t.zone(), t.idx());
		return Integer.parseInt(m.group(1)) >= left;
	}

	/**
	 * Saves P2's Forward at {@code t} from an effect about to break it by using an ability of its own
	 * whose cost returns it to hand — Onion Knight 1-181H, the other half of what
	 * {@link #p2ShouldHoldSelfBounceSwap} holds it for. The card goes back to hand rather than to the
	 * Break Zone, and whatever the ability then plays is a new card the waiting effect never chose.
	 * Bound by the ability's own timing ("only during your turn"), as any activation is.
	 */
	boolean p2SelfBounceSave(ForwardTarget t) {
		int i = t.idx();
		CardData card = mw.p2ForwardPrimedTop.get(i) != null ? mw.p2ForwardPrimedTop.get(i) : mw.p2ForwardCards.get(i);
		if (mw.lostAbilitiesCards.contains(card)) return false;
		for (ActionAbility ability : card.actionAbilities()) {
			if (!returnsSourceToHand(ability, card)) continue;
			if (!mw.canActivateAbility(ability, mw.p2ForwardFrozen.get(i), mw.p2ForwardStates.get(i),
					mw.p2ForwardPlayedOnTurn.get(i), card, false)) continue;
			if (ActionResolver.parse(ability.effectText(), card) == null) continue;
			List<Integer>        backupDullIndices = new ArrayList<>();
			Map<Integer, String> backupElems       = new LinkedHashMap<>();
			List<Integer>        discardIndices    = new ArrayList<>();
			Map<Integer, String> discardElems      = new LinkedHashMap<>();
			if (!p2PlanAbilityPayment(ability, card, backupDullIndices, backupElems, discardIndices, discardElems))
				continue;
			mw.logEntry("[P2] Saves " + card.name() + " from the effect on the Stack: " + ability.effectText());
			if (!mw.autoAbilityTriggers.executeP2AbilityActivation(ability, card, mw.abilityCostDull(t),
					backupDullIndices, discardIndices, 0)) {
				logAbandonedActivation(card);
				continue;
			}
			return true;
		}
		return false;
	}

	/** Whether {@code ability}'s cost includes returning {@code source} itself to its owner's hand. */
	private static boolean returnsSourceToHand(ActionAbility ability, CardData source) {
		return ability.returnToHandCosts().stream()
				.anyMatch(r -> source.name().equalsIgnoreCase(r.cardName()));
	}

	/**
	 * True when P2 should hold a self-boost ability — "[self] gains +N power / First Strike / Haste
	 * until the end of the turn" — rather than pay for it. Two reasons, both from watching Firion
	 * 1-022R buy First Strike over and over until its CP ran out:
	 *
	 * <ul>
	 *   <li><b>The grant is already standing.</b> A keyword the source carries changes nothing when
	 *       it is granted again, and these abilities carry a CP cost and no once-per-turn limit, so
	 *       nothing else stopped the AI buying the same keyword on every pass through the main
	 *       phase. Asked of the <em>effective</em> traits, so a keyword the card prints or is being
	 *       granted from elsewhere counts as standing too.</li>
	 *   <li><b>Nothing on the board would turn on it.</b> Power and First Strike are bought to
	 *       carry a Forward through a block, so they are worth CP only while some Forward P1 could
	 *       block with would break this one as it stands and would not once the boost is up.
	 *       Haste and Brave are not weighed this way: what they buy is the attack itself, not
	 *       surviving it, and the first clause already stops them being bought twice.</li>
	 * </ul>
	 *
	 * <p>Only asked of a Forward P2 controls. A Backup or Monster carrying one of these has no
	 * combat to spend it in, and the first clause holds it after a single use either way.
	 */
	boolean p2ShouldHoldSelfBoost(ActionAbility ability, CardData card) {
		ActionResolver.SelfBoost boost = ActionResolver.selfBoostGrant(ability.effectText(), card);
		if (boost == null) return false;
		int idx = mw.p2ForwardCards.indexOf(card);
		if (idx < 0) return !boost.traits().isEmpty();   // off the Forward row, one use is all there is
		boolean newTrait = false;
		for (CardData.Trait t : boost.traits())
			if (!mw.effectiveP2HasTrait(idx, t)) { newTrait = true; break; }
		if (boost.power() == 0 && !newTrait) return true;
		boolean grantsFirstStrike = boost.traits().contains(CardData.Trait.FIRST_STRIKE);
		if (boost.power() == 0 && !grantsFirstStrike) return false;   // Haste or Brave, not yet held
		return !p2BoostSavesAnAttacker(idx, boost.power(), grantsFirstStrike);
	}

	/**
	 * Whether adding {@code addedPower} and, optionally, First Strike would carry P2's Forward at
	 * {@code idx} through a block it would not survive as it stands.
	 *
	 * <p>Measured against every Forward P1 could block with, one at a time: the boost is worth
	 * paying for as soon as it turns one of those blocks, since P1 chooses the blocker and the AI
	 * cannot know which. Damage already on either Forward counts — a blow that was survivable on
	 * a fresh Forward is lethal on a damaged one, which is exactly when the boost earns its cost.
	 *
	 * <p>A Forward that cannot attack is never saved by this: the block it would survive is one it
	 * will not be in.
	 */
	private boolean p2BoostSavesAnAttacker(int idx, int addedPower, boolean addsFirstStrike) {
		if (!p2ForwardCanAttack(idx)) return false;
		int attackerPower = mw.effectiveP2ForwardPower(idx);
		int attackerDamage = mw.p2ForwardDamage.get(idx);
		boolean firstStrikeNow = mw.effectiveP2HasTrait(idx, CardData.Trait.FIRST_STRIKE);
		for (int i = 0; i < mw.p1ForwardCards.size(); i++) {
			if (!p1CouldBlockWith(i)) continue;
			int blockerPower  = mw.effectiveP1ForwardPower(i);
			int blockerDamage = mw.p1ForwardDamage.get(i);
			boolean survivesNow = survivesBlock(attackerPower, attackerDamage, firstStrikeNow,
					blockerPower, blockerDamage);
			boolean survivesBoosted = survivesBlock(attackerPower + addedPower, attackerDamage,
					firstStrikeNow || addsFirstStrike, blockerPower, blockerDamage);
			if (!survivesNow && survivesBoosted) return true;
		}
		return false;
	}

	/**
	 * Whether an attacker of {@code attackerPower} already carrying {@code attackerDamage} comes
	 * out of a block by a Forward of {@code blockerPower} still on the field.
	 *
	 * <p>Two ways to survive: break the blocker before it strikes back, which is what First Strike
	 * buys and needs only enough power to finish it; or outlast its blow, which needs the
	 * attacker's remaining power to exceed what the blocker deals.
	 */
	private static boolean survivesBlock(int attackerPower, int attackerDamage, boolean firstStrike,
			int blockerPower, int blockerDamage) {
		if (firstStrike && blockerDamage + attackerPower >= blockerPower) return true;
		return attackerDamage + blockerPower < attackerPower;
	}

	/**
	 * Whether P1's Forward at {@code idx} could block an attack P2 has not declared yet.
	 *
	 * <p>Deliberately not {@code MainWindow}'s block-legality check, which answers about the block
	 * in front of it and returns false while no attack is waiting on one. This is the prospective
	 * question the main phase can ask: the standing restrictions only, with everything that
	 * depends on which Forward is attacking left to the real check when the attack is made.
	 */
	private boolean p1CouldBlockWith(int idx) {
		CardData blocker = mw.p1ForwardCards.get(idx);
		if (blocker == null) return false;
		if (mw.p1ForwardStates.get(idx) != CardState.ACTIVE) return false;
		if (mw.p1ForwardFrozen.get(idx)) return false;
		if (mw.p1CannotBlock.contains(blocker) || mw.p1CannotBlockPersistent.contains(blocker)) return false;
		if (blocker.cannotBlockAtAll() || blocker.cannotAttackOrBlock()) return false;
		if (mw.blockBarredByFieldCostLock(blocker)) return false;
		return !mw.isFieldAbilityCannotAttackOrBlock(blocker, true);
	}

	/**
	 * True when {@code ability} deals a fixed amount of damage to a Forward it chooses, pays for it
	 * by putting a card into the Break Zone, and no P1 Forward on the board would break under that
	 * damage — in which case P2 holds the ability rather than activating it.
	 *
	 * <p>The trade is what makes it worth holding: damage short of lethal is wiped at the end of the
	 * turn, so a card has been spent to change nothing, while the same number becomes lethal on a
	 * later board (a power boost expiring, a Forward already damaged in combat). Only the
	 * irrecoverable costs are gated this way — an ability that costs no more than a Dull is free to
	 * soften a Forward up for the attack phase that follows, and a Dull spent is a Dull recovered
	 * next turn.
	 *
	 * <p>Targeting is judged by {@link #abilityHarmsChosenTarget}, which already excludes the
	 * "Forward you control" printings: an ability aimed at P2's own board is not one being held back
	 * for a better target.
	 */
	boolean p2ShouldHoldDamageAbility(ActionAbility ability) {
		int damage = ActionResolver.chooseTargetDamageAmount(ability.effectText());
		if (damage <= 0 || ability.breakZoneCosts().isEmpty()) return false;
		if (!abilityHarmsChosenTarget(ability)) return false;
		return !p1HasForwardBreakableBy(damage);
	}

	/**
	 * True if {@code damage} would break at least one P1 Forward (or Monster acting as one) as the
	 * board stands.  Optimistic by design: it reads raw power and damage
	 * ({@link MainWindow#fieldForwardBreakableBy}), so a shielded Forward still counts as a kill.
	 */
	private boolean p1HasForwardBreakableBy(int damage) {
		for (int i = 0; i < mw.p1ForwardCards.size(); i++) {
			if (mw.p1ForwardCards.get(i) == null) continue;
			if (mw.fieldForwardBreakableBy(true, ForwardTarget.CardZone.FORWARD, i, damage)) return true;
		}
		for (int i = 0; i < mw.p1MonsterCards.size(); i++) {
			if (mw.p1MonsterCards.get(i) == null || !mw.isP1MonsterTemporarilyForward(i)) continue;
			if (mw.fieldForwardBreakableBy(true, ForwardTarget.CardZone.MONSTER, i, damage)) return true;
		}
		return false;
	}

	/** True if P1 has at least one Forward (or temp-forward Monster) on the field. */
	private boolean p1HasAnyForward() {
		for (int i = 0; i < mw.p1ForwardCards.size(); i++)
			if (mw.p1ForwardCards.get(i) != null) return true;
		for (int i = 0; i < mw.p1MonsterCards.size(); i++)
			if (mw.p1MonsterCards.get(i) != null && mw.isP1MonsterTemporarilyForward(i)) return true;
		return false;
	}

	/** Mirror of {@link #p1HasAnyForward} for P2's own side, counting Monsters acting as Forwards. */
	/**
	 * Whether P2 has anything that could declare an attack this turn — Forwards, plus the Monsters
	 * and Backups that are currently acting as Forwards.
	 *
	 * <p>Asks the same three predicates the attack phase itself loops over, so "the AI thinks it can
	 * attack" and "the AI then attacks" cannot disagree. That matters for the dull guard above: the
	 * whole value of dulling a blocker is the attack that follows it.
	 */
	private boolean p2HasAnyAttacker() {
		for (int i = 0; i < mw.p2ForwardStates.size(); i++)
			if (p2ForwardCanAttack(i)) return true;
		for (int i = 0; i < mw.p2MonsterStates.size(); i++)
			if (mw.p2MonsterCanAttackAsForward(i)) return true;
		for (int i = 0; i < mw.p2BackupCards.length; i++)
			if (mw.p2BackupCanAttackAsForward(i)) return true;
		return false;
	}

	/** Whether P2 controls a dull Forward — something the activate half of a toggle could turn. */
	private boolean p2HasDullForward() {
		for (int i = 0; i < mw.p2ForwardStates.size(); i++)
			if (mw.p2ForwardCards.get(i) != null && mw.p2ForwardStates.get(i) == CardState.DULL) return true;
		return false;
	}

	private boolean p2HasAnyForward() {
		for (int i = 0; i < mw.p2ForwardCards.size(); i++)
			if (mw.p2ForwardCards.get(i) != null) return true;
		for (int i = 0; i < mw.p2MonsterCards.size(); i++)
			if (mw.p2MonsterCards.get(i) != null && mw.isP2MonsterTemporarilyForward(i)) return true;
		return false;
	}

	private static final Pattern SEARCH_WORD = Pattern.compile("(?i)\\bsearch\\b");

	/** True if the card's text mentions searching (deck search, either as a cast/ETB effect or an ability). */
	private static boolean mentionsSearch(String text) {
		return text != null && SEARCH_WORD.matcher(text).find();
	}

	/** True if any action or auto ability on {@code card} can search. */
	private static boolean hasSearchAbility(CardData card) {
		for (ActionAbility a : card.actionAbilities())
			if (mentionsSearch(a.effectText())) return true;
		for (AutoAbility a : card.autoAbilities())
			if (mentionsSearch(a.effectText())) return true;
		return false;
	}

	/**
	 * True if P1 currently has either a hand card they can afford to play whose text can search
	 * the deck, or a field Forward/Backup/Monster with a search-capable ability. Used to gate
	 * "your opponent cannot search this turn" disruption abilities (Mog (VI) 17-124H) so the CPU
	 * doesn't spend one for no effect.
	 */
	private boolean p1HasSearchOption() {
		List<CardData> hand = mw.gameState.getP1Hand();
		for (int i = 0; i < hand.size(); i++) {
			CardData c = hand.get(i);
			String text = c.isSummon() ? c.summonEffect() : c.textEn();
			if (mentionsSearch(text) && mw.canAffordCard(c, i)) return true;
		}
		for (int i = 0; i < mw.p1ForwardCards.size(); i++) {
			CardData c = mw.p1ForwardCards.get(i);
			if (c != null && !mw.lostAbilitiesCards.contains(c) && hasSearchAbility(c)) return true;
		}
        for (CardData c : mw.p1BackupCards) {
            if (c != null && !mw.lostAbilitiesCards.contains(c) && hasSearchAbility(c)) return true;
        }
		for (int i = 0; i < mw.p1MonsterCards.size(); i++) {
			CardData c = mw.p1MonsterCards.get(i);
			if (c != null && !mw.lostAbilitiesCards.contains(c) && hasSearchAbility(c)) return true;
		}
		return false;
	}

	/**
	 * Returns true if the ability harms a chosen character target (deals damage, breaks, etc.)
	 * without restricting that target to own units ("you control").
	 * Used to skip activation when no opponent forwards are present — legally the AI could
	 * target its own forwards, but doing so is never beneficial.
	 */
	private static boolean abilityHarmsChosenTarget(ActionAbility ability) {
		String t = ability.effectText().toLowerCase();
		if (t.contains("you control") || t.contains("forward you")) return false;
		// Damage to a chosen forward / character (single-target or quantity-qualified)
		return t.contains("deal") && (t.contains("forward") || t.contains("character") || t.contains(" it "));
	}

	/**
	 * True when {@code ability}'s "choose" only benefits what it picks and P2 will control nothing
	 * for it to pick — so every Character left to aim at is P1's, and paying the cost buys the
	 * opponent a bigger Forward.
	 *
	 * <p>The mirror of {@link #abilityHarmsChosenTarget}, which skips a harmful ability while P1
	 * has no Forward on the same reasoning. This half has to look past the cost as well, because
	 * the Forward that makes the ability look worth using can be the one the cost spends: Undead
	 * Princess 19-052C prints "Put Undead Princess into the Break Zone: Choose 1 Forward. It gains
	 * +4000 power until the end of the turn.", and when she is P2's last Forward the choice is made
	 * after she is already gone.
	 *
	 * <p>An opponent-only choice is left alone: a buff that names the opponent's cards is pointed
	 * there on purpose, and nothing here is a better judge of the printing than the printing.
	 */
	boolean buffWouldOnlyHelpP1(ActionAbility ability, CardData source) {
		if (!ActionResolver.chooseEffectBenefitsTarget(ability.effectText())) return false;
		TargetSpec spec = ActionResolver.targetSpec(ability.effectText(), source);
		if (spec == null || spec.zone() != null || spec.opponentOnly()) return false;
		boolean sourceIsSpent = costSpendsSource(ability, source);
		for (ForwardTarget t : mw.eligibleAbilityChoiceTargets(spec, ability, source, false)) {
			if (t.isP1()) continue;
			if (sourceIsSpent && mw.fieldCardDataOrNull(t) == source) continue;
			return false;
		}
		return true;
	}

	/**
	 * Whether {@code ability}'s cost takes {@code source} itself off the field — the "Put [self]
	 * into the Break Zone:" and "Remove [self] from the game:" shape. Only the costs that name a
	 * card are read: a type-based cost ("put 2 Earth Backups into the Break Zone") is answered by
	 * whichever cards the payment planner picks, and guessing at that here would be a second
	 * planner to keep in step with the first.
	 */
	private static boolean costSpendsSource(ActionAbility ability, CardData source) {
		for (BreakZoneCost bz : ability.breakZoneCosts())
			if (source.name().equalsIgnoreCase(bz.name())) return true;
		for (RemoveFromGameCost rfg : ability.removeFromGameCosts())
			if ("FIELD".equals(rfg.zone()) && source.name().equalsIgnoreCase(rfg.cardName())) return true;
		return false;
	}

	/**
	 * True if any special ability used this turn (other than {@code mimic} itself, matched by name)
	 * is available for Gogo's "Mimic" to replay.
	 */
	private boolean hasMimicableSpecialAbility(ActionAbility mimic) {
		for (UsedSpecialAbility u : mw.specialAbilitiesUsedThisTurn)
			if (!u.ability().abilityName().equalsIgnoreCase(mimic.abilityName())) return true;
		return false;
	}

	/**
	 * P2's Backup row <em>as a CP source</em>: the live row, or an all-null copy while Titan (XVI)
	 * 29-068L's "During your turn, the Backups opponent controls cannot produce CP." is binding P2.
	 *
	 * <p>Every P2 payment planner reads the row through here, exactly as the payment dialogs take
	 * {@link MainWindow#cpPayableBackupCards} rather than the raw row. The planners all skip null
	 * slots already, so one masked row suppresses the whole set without four separate gates that
	 * could drift apart. They read the raw row until now, which let P2 pay for a reactive shield on
	 * P1's turn with CP its Backups were barred from producing — the suppression only binds while
	 * the card's controller has the turn, so P1's turn is the only window in which P2 can hit it.
	 *
	 * <p>Only for deciding what may be dulled for CP. The Backups are still on the field for
	 * everything else, which is why the row is masked rather than emptied.
	 *
	 * <p>On P2's own turn the Backups set aside for a held card — the Back Attack Character
	 * ({@link #heldBackAttack}), the trick for its attacks ({@link #heldTrick}) — are masked too, so
	 * nothing else P2 does on its turn spends them.
	 */
	private CardData[] p2CpBackups() {
		CardData[] row = mw.cpPayableBackupCards(false);
		HeldCard held = heldBackAttack(), trick = heldTrick();
		boolean spending = spentByTheOffer != null && Arrays.asList(row).contains(spentByTheOffer);
		if ((held == null || held.backups().isEmpty()) && (trick == null || trick.backups().isEmpty()) && !spending) return row;
		CardData[] open = row.clone();
		if (held != null) for (int bi : held.backups()) if (bi < open.length) open[bi] = null;
		if (trick != null) for (int bi : trick.backups()) if (bi < open.length) open[bi] = null;
		// A Backup a sacrifice is about to put into the Break Zone pays for nothing after it.
		for (int bi = 0; bi < open.length; bi++) if (open[bi] == spentByTheOffer) open[bi] = null;
		return open;
	}

	/** Returns unique non-empty CP cost elements, in encounter order. */
	private static String[] p2AbilityElements(ActionAbility ability) {
		LinkedHashSet<String> seen = new LinkedHashSet<>();
		for (String e : ability.cpCost()) if (!e.isEmpty()) seen.add(e);
		return seen.toArray(String[]::new);
	}

	/**
	 * Greedy CP planner for action ability payment — same logic as {@link #p2PlanPayment}
	 * but driven by the ability's element list and total cost.  Handles generic (empty-string)
	 * CP elements by allowing any active backup or any non-Light/Dark hand card to contribute.
	 *
	 * <p>{@code source} is the card whose ability is being paid for, and whether it may be dulled
	 * for CP toward that ability is {@link CpPaymentUtils#sourceCanFundOwnAbility}'s call: only a
	 * 《Dull》 cost rules it out, because that cost is already spending the dull. Without the source
	 * the planner had no way to know, and paid the 《Fire》 half of a 《Fire》《Dull》 cost by dulling
	 * the very card the 《Dull》 was about to dull — Yotsuyu activating for free while every other
	 * Backup stayed active. Matched by identity, not equality: {@link CardData} is a record, so a
	 * second copy of the same Backup on the row is {@code equals} to the source and would be
	 * excluded along with it.
	 */
	boolean p2PlanAbilityPayment(ActionAbility rawAbility, CardData source,
			List<Integer> outBackups, Map<Integer, String> outBackupElems,
			List<Integer> outDiscards, Map<Integer, String> outDiscardElems) {
		// Plan against the cost P2 will actually be charged, tax included (The Emperor 20-092R).
		// Planning off the printed cost would have P2 commit to abilities it cannot pay for.
		ActionAbility ability = rawAbility.withIncreasedCp(mw.actionAbilityCostIncreaseFor(false));
		// Hand costs are settled before the CP planner below is allowed to spend the hand, because
		// it would otherwise spend the very cards they need. Two cards in hand, one of them the
		// same-named copy a 《S》 wants, and the copy goes to CP; the payment then finds no payer and
		// backs out. A 《S》 backs out having committed nothing, which the Main Phase loop reads as
		// "the ability is still available" and retries forever (Cloud 10-006R's Cross-slash); a
		// discard cost backs out having already dulled Backups and burned the hand for CP, which
		// terminates but pays in full for nothing. Both are answered the same way — reserve the
		// payers up front, and call the ability unaffordable when they cannot all be found.
		List<CardData> handNow = mw.gameState.getP2Hand();
		Set<Integer> reservedHandIdxs = new LinkedHashSet<>();
		if (ability.isSpecial() && !mw.canPaySpecialCostWithCrystal(source, false)
				&& !mw.specialSCostWaivedThisTurn.contains(source)) {
			int sCostSlot = p2SpecialCostPayerSlot(source);
			if (sCostSlot < 0) return false;
			reservedHandIdxs.add(sCostSlot);
		}
		// The 《S》 first, so the two never claim the same slot: the payment discards the 《S》 card
		// before it selects for the discard costs, and by then that slot is gone from the hand.
		for (DiscardCost dc : ability.discardCosts()) {
			List<Integer> payers = mw.autoAbilityTriggers.discardCostPayerIdxs(dc, handNow, reservedHandIdxs);
			if (payers.size() < dc.count()) return false;
			reservedHandIdxs.addAll(payers.subList(0, dc.count()));
		}
		String[] elems = p2AbilityElements(ability);
		long genericCount = ability.cpCost().stream().filter(String::isEmpty).count();
		int totalCost = ability.cpCost().size();
		if (totalCost == 0) return true;
		int[] simCp = new int[elems.length];
		for (int ei = 0; ei < elems.length; ei++)
			simCp[ei] = mw.gameState.getP2CpForElement(elems[ei]);
		int anyCp = 0;

		if (p2CanAfford(totalCost, elems, simCp, anyCp, true)) return true;

		List<Integer> matchingBackups = new ArrayList<>();
		List<Integer> offColorBackups = new ArrayList<>();
		CardData[] payable = p2CpBackups();
		for (int bi = 0; bi < payable.length; bi++) {
			CardData bk = payable[bi];
			if (bk == null || (bk == source && !CpPaymentUtils.sourceCanFundOwnAbility(ability))) continue;
			if (mw.p2BackupStates[bi] != CardState.ACTIVE || mw.p2BackupFrozen[bi]) continue;
			boolean matches = false;
			for (String e : elems) if (mw.effectiveContainsElement(bk, e)) { matches = true; break; }
			if (matches) matchingBackups.add(bi);
			else         offColorBackups.add(bi);
		}
		matchingBackups.sort(Comparator.comparingInt(bi ->
				(int) Arrays.stream(elems)
						.filter(e -> mw.effectiveContainsElement(payable[bi], e)).count()));
		for (int bi : matchingBackups) {
			if (p2CanAfford(totalCost, elems, simCp, anyCp, true)) break;
			int ei = elems.length > 0 ? p2BestElementFor(e -> mw.effectiveContainsElement(payable[bi], e), elems, simCp) : 0;
			simCp[ei] += 1;
			outBackups.add(bi);
			outBackupElems.put(bi, elems[ei]);
		}
		if (p2CanAfford(totalCost, elems, simCp, anyCp, true)) return true;

		// Off-color backups can contribute to generic slots
		for (int bi : offColorBackups) {
			if (p2CanAfford(totalCost, elems, simCp, anyCp, true)) break;
			if (genericCount <= 0) break; // no generic slots available
			anyCp += 1;
			outBackups.add(bi);
			outBackupElems.put(bi, elems.length > 0 ? elems[0] : "");
		}
		if (p2CanAfford(totalCost, elems, simCp, anyCp, true)) return true;

		List<CardData> hand = mw.gameState.getP2Hand();
		Set<String> ldGrants = mw.lightDarkDiscardGrants(false);
		List<CardData> kept = keptInHand();
		List<Integer> discardable = new ArrayList<>();
		for (int i = 0; i < hand.size(); i++) {
			if (reservedHandIdxs.contains(i)) continue;
			CardData c = hand.get(i);
			if (isKept(kept, c)) continue;
			if (!CpPaymentUtils.canDiscardForCp(c, ldGrants)) continue;
			if (elems.length == 0 || genericCount > 0) { discardable.add(i); continue; }
			for (String e : elems) if (c.containsElement(e)) { discardable.add(i); break; }
		}
		discardable.sort((a, b) -> hand.get(a).cost() - hand.get(b).cost());
		for (int di : discardable) {
			if (p2CanAfford(totalCost, elems, simCp, anyCp, true)) return true;
			// An all-generic cost (Onion Knight 1-181H's 《1》《Dull》) names no Element, so there is
			// no per-element bucket to credit and simCp is zero-length — ei is -1 and the CP can only
			// go to the generic pool. Crediting it to element slot 0 instead threw
			// ArrayIndexOutOfBoundsException, on the one board that reaches here with an empty cost:
			// no active Backup left, so the off-color loop above could not settle the payment first.
			int ei = elems.length > 0 ? p2BestElementFor(hand.get(di)::containsElement, elems, simCp) : -1;
			// If off-color but generic slots remain, contribute to any pool
			if (ei < 0 || (!hand.get(di).containsElement(elems[ei]) && genericCount > 0)) {
				anyCp += 2;
			} else {
				simCp[ei] += 2;
			}
			outDiscards.add(di);
			outDiscardElems.put(di, ei >= 0 ? elems[ei] : "");
			if (p2CanAfford(totalCost, elems, simCp, anyCp, true)) return true;
		}
		return false;
	}

	/**
	 * The P2 hand slot set aside to pay {@code source}'s 《S》 cost, or -1 when no card in hand can
	 * pay it.  Eligibility is {@code AutoAbilityTriggers.specialCostCandidateIdxs}' call, so the
	 * slot reserved here is one the payment will actually accept.
	 *
	 * <p>The cheapest of the eligible copies, and the payment picks the cheapest too
	 * ({@code AutoAbilityTriggers.cheapest}) — the reservation is only honoured because both ends
	 * answer "which copy pays" the same way, since the slot itself is never handed to the payment.
	 *
	 * <p>Cheapest rather than dearest because a 《S》 payer and a CP discard are wanted by the same
	 * cost ordering: every hand card is worth 2 CP whatever it cost to play, so which copy is set
	 * aside cannot change what P2 can afford — only which cards it keeps. Spending the cheap copy
	 * on the 《S》 leaves the dear one in hand; the earlier reading had this backwards, and reserved
	 * the dear copy to protect an affordability that was never at risk.
	 */
	private int p2SpecialCostPayerSlot(CardData source) {
		List<CardData> hand = mw.gameState.getP2Hand();
		return AutoAbilityTriggers.cheapest(hand,
				mw.autoAbilityTriggers.specialCostCandidateIdxs(source, hand, Set.of(), false));
	}

	/**
	 * From {@code hand}, picks the index of the worst card whose type does NOT match
	 * {@code avoidType} ("Forward"/"Backup"/"Monster"/"Summon").  Falls back to
	 * {@link MainWindow#pickWorstHandCard0} when every card in hand is of the avoided type.
	 * "Worst" uses the same low-cost-first heuristic as {@code pickWorstHandCard0}.
	 */
	static int pickWorstAvoidingType(List<CardData> hand, String avoidType) {
		int bestIdx = -1, bestScore = Integer.MAX_VALUE;
		for (int i = 0; i < hand.size(); i++) {
			CardData c = hand.get(i);
			if (cardMatchesType(c, avoidType)) continue;
			int score = c.cost() + (c.isForward() ? 10 : 0);
			if (score < bestScore) { bestScore = score; bestIdx = i; }
		}
		return bestIdx >= 0 ? bestIdx : MainWindow.pickWorstHandCard0(hand);
	}

	/**
	 * Returns the card type ("Forward"/"Backup"/"Monster"/"Summon") that appears most
	 * often in {@code hand}.  Used by the CPU to name a type when Setzer enters the field.
	 * Ties are broken by the order Forward → Backup → Monster → Summon.
	 * Returns {@code "Forward"} when {@code hand} is empty.
	 */
	static String pickMostCommonCardType(List<CardData> hand) {
		if (hand.isEmpty()) return "Forward";
		String[] types = {"Forward", "Backup", "Monster", "Summon"};
		String best = types[0];
		long bestCount = 0;
		for (String t : types) {
			long count = hand.stream().filter(c -> cardMatchesType(c, t)).count();
			if (count > bestCount) { bestCount = count; best = t; }
		}
		return best;
	}

	static boolean cardMatchesType(CardData c, String type) {
		return switch (type) {
			case "Forward" -> c.isForward();
			case "Backup"  -> c.isBackup();
			case "Monster" -> c.isMonster();
			case "Summon"  -> c.isSummon();
			default        -> false;
		};
	}
}
