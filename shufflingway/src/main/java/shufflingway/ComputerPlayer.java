package shufflingway;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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

	/**
	 * Hand size at which P2 spends its main phase playing cards rather than activating abilities.
	 *
	 * <p>Five is the end-phase hand limit: from here up, a card held past the end of the turn is a
	 * card discarded, so the board is where it is worth more than any ability effect the same CP
	 * would buy.
	 */
	private static final int HAND_PLAY_PREFERRED_AT = 5;
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
		Timer t = new Timer(PAUSE_MS, e -> {
			if (cancelled) return;
			if (mw.gameState.isP1GameOver()) return;
			if (!mw.isBoardSettled()) { step(r); return; }
			r.run();
		});
		t.setRepeats(false);
		t.start();
	}

	/** Entry point: called when P2's ACTIVE phase begins. */
	@Override
	public void runTurn() {
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
		if (drawn.size() < drawCount) {
			mw.triggerGameOver("P2 milled out — You Win!");
			return;
		}
		mw.logEntry("[P2] Draw Phase — Drew " + drawn.size() + " card(s) (hand: " + mw.gameState.getP2Hand().size() + ")");
		mw.gameState.advancePhase(); // DRAW → MAIN_1
		mw.refreshPhaseTracker();
		mw.logEntry("[P2] Main Phase 1");
		mw.processWarpCounters(false);
		mw.autoAbilityTriggers.triggerAutoAbilitiesForBeginningOfMainPhase1(false);
		mw.autoAbilityTriggers.triggerAutoAbilitiesForBeginningOfMainPhase1EachTurn();
		mw.autoAbilityTriggers.triggerAutoAbilitiesForBeginningOfOppMainPhase1(true);
		step(() -> doMainPhase(() -> {
			mw.gameState.advancePhase(); // MAIN_1 → ATTACK
			mw.refreshPhaseTracker();
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
				mw.gameState.advancePhase(); // ATTACK → MAIN_2
				mw.refreshPhaseTracker();
				mw.refreshCombatGlows();   // attack phase over — the exhausted mark comes off
				mw.logEntry("[P2] Main Phase 2");
				mw.autoAbilityTriggers.triggerAutoAbilitiesForBeginningOfMainPhase2(false);
				step(() -> doMainPhase(this::doEndPhase));
			} else {
				mw.logEntry("[P2] Attack Phase");
				mw.autoAbilityTriggers.triggerAutoAbilitiesForBeginningOfAttackPhase(false);
				mw.autoAbilityTriggers.triggerAutoAbilitiesForBeginningOfAttackPhaseEachTurn(false);
				mw.autoAbilityTriggers.triggerAutoAbilitiesForBeginningOfOppAttackPhase(false);
				mw.refreshAllP2ForwardSlots();
				// Attack Preparation: P2 (turn player) has acted, so P1 holds priority before P2
				// may declare an attacker.
				mw.offerP1AttackPrepPriority(() -> step(() -> doAttackPhase(() -> {
					mw.gameState.advancePhase(); // ATTACK → MAIN_2
					mw.refreshPhaseTracker();
					mw.refreshCombatGlows();   // attack phase over — the exhausted mark comes off
					mw.logEntry("[P2] Main Phase 2");
					mw.autoAbilityTriggers.triggerAutoAbilitiesForBeginningOfMainPhase2(false);
					step(() -> doMainPhase(this::doEndPhase));
				})));
			}
		}));
	}

	// ── Main Phase (shared for Main 1 and Main 2) ────────────────────────

	private void doMainPhase(Runnable onDone) {
		if (mw.gameState.isP1GameOver()) return;

		// Try LB plays first
		int[] lbPlan = findLbPlayPlan();
		if (lbPlan != null) {
			int castIdx = lbPlan[0];
			CardData card = mw.gameState.getP2LbDeck().get(castIdx);
			mw.p2SpentLbIndices.add(castIdx);
			for (int i = 1; i < lbPlan.length; i++) mw.p2SpentLbIndices.add(lbPlan[i]);
			String element = card.elements()[0];
			mw.gameState.spendP2Cp(element, Math.min(card.cost(), mw.gameState.getP2CpForElement(element)));
			mw.refreshP2LimitButton();
			mw.logEntry("[P2] Plays LB \"" + card.name() + "\"");
			mw.lastCastPaymentElements.clear();
			mw.lastCastActualPaymentElements.clear();
			mw.lastCastPaymentElements.add(element);
			mw.lastCastActualPaymentElements.add(element);
			mw.lastCastPaymentCard = card;
			// The AI pays out of a CP pool rather than by dulling named Backups, so no Backup
			// produced this CP. Cleared rather than left alone: a stale record from an earlier
			// cast would let a "CP only produced by Backups" gate pass on a payment that had none.
			mw.lastCastPaymentBackups.clear();
			mw.lastCastWasPaidByBackupsOnly = false;
			mw.lastCardWasCast = true;
			mw.noteCardCast(card, false);
			if (card.isSummon()) { mw.p2Turn.summonCastThisTurn = true; mw.noteDoublecastSummonCast(false, card); }
			if (card.isForward())      mw.placeP2CardInForwardZone(card);
			else if (card.isBackup())  mw.placeP2CardInFirstBackupSlot(card);
			else if (card.isMonster()) mw.placeP2CardInMonsterZone(card);
			else if (card.isSummon())  mw.showSummonOnStack(card, false);
			mw.lastCardWasCast = false;
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
		if (plan.warp()) {
			// executeP2WarpPlay pays the Warp cost itself — it dulls and discards, banks the CP and
			// clears it — so the ordinary payment step is skipped, and it takes the unadjusted hand
			// index because it shifts that index past its own discards.
			mw.executeP2WarpPlay(mw.gameState.getP2Hand().get(plan.cardIdx()), plan.cardIdx(),
					plan.discardIndices(), plan.dullBackups(), plan.backupElements());
			return;
		}
		mw.payP2CostViaBackupsAndDiscards(
				plan.dullBackups(),    plan.backupElements(),
				plan.discardIndices(), plan.discardElements());

		// Adjust source-card hand index for cards removed during discard
		int adjustedIdx = plan.cardIdx();
		for (int di : plan.discardIndices()) if (di < plan.cardIdx()) adjustedIdx--;

		CardData toPlay = mw.gameState.removeP2FromHand(adjustedIdx);
		mw.refreshP2HandCountLabel();
		if (toPlay == null) return;

		String[] elems = toPlay.elements();
		boolean freeCast = plan.reducedCost() <= 0;
		if (!freeCast) {
			int remaining = toPlay.cost();
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
		if (!freeCast)
			for (String e : elems) if (!e.isEmpty()) { mw.lastCastPaymentElements.add(e); mw.lastCastActualPaymentElements.add(e); }
		mw.lastCastPaymentCard = toPlay;
		// See the note on the Limit Break path above: the pool payment names no Backups, and the
		// previous cast's record must not be left standing for a Backup-source gate to read.
		mw.lastCastPaymentBackups.clear();
		mw.lastCastWasPaidByBackupsOnly = false;

		mw.logEntry("[P2] Plays " + toPlay.name()
				+ (freeCast && mw.p2DoublecastFreeSummons ? " (free — Doublecast)" : ""));
		mw.lastCardWasCast = true;
		mw.noteCardCast(toPlay, false);
		if (toPlay.isSummon()) { mw.p2Turn.summonCastThisTurn = true; mw.noteDoublecastSummonCast(false, toPlay); }
		if (toPlay.isForward())      mw.placeP2CardInForwardZone(toPlay);
		else if (toPlay.isBackup())  mw.placeP2CardInFirstBackupSlot(toPlay);
		else if (toPlay.isMonster()) mw.placeP2CardInMonsterZone(toPlay);
		else if (toPlay.isSummon())  mw.showSummonOnStack(toPlay, false);
		mw.lastCardWasCast = false;
	}

	// ── Attack Phase ─────────────────────────────────────────────────────

	/**
	 * Returns a list of P2 forward indices to party-attack with, or null if a party
	 * attack offers no advantage. A party attack is chosen when the combined power of
	 * 2-3 forwards can break a P1 forward that no single P2 forward could kill alone.
	 */
	private List<Integer> p2ChoosePartyAttack() {
		List<Integer> attackable = new ArrayList<>();
		for (int i = 0; i < mw.p2ForwardStates.size(); i++)
			if (p2ForwardCanAttack(i)) attackable.add(i);
		if (attackable.size() < 2) return null;

		for (int p1 = 0; p1 < mw.p1ForwardStates.size(); p1++) {
			CardState s = mw.p1ForwardStates.get(p1);
			if (s != CardState.ACTIVE) continue;
			int p1Hp = mw.effectiveP1ForwardPower(p1) - mw.p1ForwardDamage.get(p1);

			boolean canKillAlone = false;
			for (int i : attackable)
				if (mw.effectiveP2ForwardPower(i) >= p1Hp) { canKillAlone = true; break; }
			if (canKillAlone) continue;

			// Try pairs
			for (int a = 0; a < attackable.size(); a++) {
				for (int b = a + 1; b < attackable.size(); b++) {
					List<Integer> pair = List.of(attackable.get(a), attackable.get(b));
					if (!mw.canFormValidParty(false, pair)) continue;
					if (mw.effectiveP2ForwardPower(attackable.get(a))
							+ mw.effectiveP2ForwardPower(attackable.get(b)) >= p1Hp)
						return pair;
				}
			}
			// Try triples
			for (int a = 0; a < attackable.size(); a++) {
				for (int b = a + 1; b < attackable.size(); b++) {
					for (int c = b + 1; c < attackable.size(); c++) {
						List<Integer> triple = List.of(attackable.get(a), attackable.get(b), attackable.get(c));
						if (!mw.canFormValidParty(false, triple)) continue;
						if (mw.effectiveP2ForwardPower(attackable.get(a))
								+ mw.effectiveP2ForwardPower(attackable.get(b))
								+ mw.effectiveP2ForwardPower(attackable.get(c)) >= p1Hp)
							return triple;
					}
				}
			}
		}
		return null;
	}

	private void executeP2PartyAttack(List<Integer> partyIndices, Runnable onDone) {
		int combinedPower = 0;
		StringBuilder names = new StringBuilder();
		for (int idx : partyIndices) {
			if (!mw.effectiveP2HasTrait(idx, CardData.Trait.BRAVE)) {
				CardState p2PartyBefore = mw.p2ForwardStates.get(idx);
				mw.p2ForwardStates.set(idx, CardState.DULL);
				mw.animateDullP2Forward(idx, null);
				if (p2PartyBefore == CardState.ACTIVE)
					mw.autoAbilityTriggers.triggerAutoAbilitiesForBecomesDull(mw.p2ForwardCards.get(idx), false);
			}
			mw.recordAttackDeclared(mw.effectiveP2Forward(idx));
			combinedPower += mw.effectiveP2ForwardPower(idx);
			if (names.length() > 0) names.append(", ");
			names.append(mw.p2ForwardCards.get(idx).name());
		}
		mw.logEntry("[P2] Party Attack! " + names + " (" + combinedPower + " combined)");
		mw.p2Turn.formedPartyThisTurn = true;
		for (int idx : partyIndices)
			mw.autoAbilityTriggers.triggerAutoAbilitiesForAttack(
					mw.p2ForwardPrimedTop.get(idx) != null ? mw.p2ForwardPrimedTop.get(idx) : mw.p2ForwardCards.get(idx), false);
		List<CardData> p2PartyMembers = partyIndices.stream()
				.map(mw.p2ForwardCards::get).collect(Collectors.toList());
		mw.autoAbilityTriggers.triggerAutoAbilitiesForPartyAttack(false, p2PartyMembers);
		final int fCombined = combinedPower;
		mw.initP1BlockDeclarationVsParty(partyIndices, fCombined, onDone);
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
			executeP2PartyAttack(party, () -> {
				if (!mw.gameState.isP1GameOver()) step(() -> doAttackPhase(onDone));
			});
			return;
		}

		for (int i = 0; i < mw.p2ForwardStates.size(); i++) {
			if (!p2ForwardCanAttack(i)) continue;
			mw.p2Turn.attackDeclarationsThisTurn++;
			CardData attacker = mw.p2ForwardPrimedTop.get(i) != null ? mw.p2ForwardPrimedTop.get(i) : mw.p2ForwardCards.get(i);
			mw.logEntry("[P2] " + attacker.name() + " attacks!");
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
			mw.logEntry("[P2] " + attacker.name() + " attacks! (Forward — " + power + ")");
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
			mw.logEntry("[P2] " + attacker.name() + " attacks! (Forward — " + power + ")");
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
		while (hand.size() > 5) {
			int idx = pickWorstHandCard(hand);
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
	private int[] findLbPlayPlan() {
		List<CardData> lbDeck = mw.gameState.getP2LbDeck();
		boolean p2HasLD = mw.hasLightOrDarkOnField(false);
		for (int i = 0; i < lbDeck.size(); i++) {
			if (mw.p2SpentLbIndices.contains(i)) continue;
			CardData card = lbDeck.get(i);
			if (card.isSummon()) continue; // skip summons — no simple board placement
			if (!card.multicard() && mw.p2HasCharacterNameOnField(card.name())) continue;
			if (card.isLightOrDark() && p2HasLD) continue;
			if (card.isBackup() && !mw.p2HasAvailableBackupSlot()) continue;
			if (!mw.castRestrictionMet(card, false)) continue;
			// Count unspent LB cards available as payment (excluding this card)
			List<Integer> available = new ArrayList<>();
			for (int j = 0; j < lbDeck.size(); j++) {
				if (j != i && !mw.p2SpentLbIndices.contains(j)) available.add(j);
			}
			if (available.size() < card.lbCost()) continue;
			// Check CP
			String element = card.elements()[0];
			if (mw.gameState.getP2CpForElement(element) < card.cost()) continue;
			// Build result: [castIdx, payment…]
			int[] result = new int[1 + card.lbCost()];
			result[0] = i;
			for (int k = 0; k < card.lbCost(); k++) result[k + 1] = available.get(k);
			return result;
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
			boolean                warp                   // pay warpCost() and bank the card rather than cast it
	) {}

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

	private P2Plan findPlayPlan() {
		if (mw.p2CastLimitReached()) return null;
		List<CardData> hand = mw.gameState.getP2Hand();
		if (hand.isEmpty()) return null;

		boolean p2HasLD = mw.hasLightOrDarkOnField(false);

		// Forwards and Monsters — highest cost first
		List<Integer> fieldCands = new ArrayList<>();
		for (int i = 0; i < hand.size(); i++) {
			CardData c = hand.get(i);
			if (!c.isForward() && !c.isMonster()) continue;
			if (!c.multicard() && mw.p2HasCharacterNameOnField(c.name())) continue;
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
				if (!c.isSummon()) continue;
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
			if (!c.isBackup() || !mw.p2HasAvailableBackupSlot()) continue;
			if (!c.multicard() && mw.p2HasCharacterNameOnField(c.name())) continue;
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
			if (p2PlanPayment(card, card.cost(), cardIdx, backups, backupElems, discards, discardElems)) {
				return new P2Plan(cardIdx, card.cost(), backups, backupElems, discards, discardElems, false);
			}
			// P2 could not raise the CP. A card printing a put-into-Break-Zone alternate cost has a
			// second route onto the field that costs no CP at all; P2 does not take it yet, and
			// p2PlanAltPutToBzCost documents what wiring it would need.
			if (card.altPutToBzCost() != null && p2PlanAltPutToBzCost(card)) {
				throw new IllegalStateException("p2PlanAltPutToBzCost is a stub — see its javadoc");
			}
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

		CardData[] backups = mw.cpPayableBackupCards(false);
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
						&& CpPaymentUtils.canDiscardForCp(hand.get(i), ldGrants)) discardable.add(i);
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
			if (i == handIdx || outDiscards.contains(i)) continue;
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
			int reducedCost = pe.effectiveCost(card);

			// Respect uniqueness / Light-Dark / backup-slot legality so borrowed casts can't create field collisions.
			boolean isChar = card.isForward() || card.isBackup() || card.isMonster();
			if (isChar && !card.multicard() && mw.p2HasCharacterNameOnField(card.name())) continue;
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

		CardData[] payable = p2CpBackups();
		for (int bi = 0; bi < payable.length && total < cost; bi++) {
			CardData bk = payable[bi];
			if (bk == null || mw.p2BackupStates[bi] != CardState.ACTIVE || mw.p2BackupFrozen[bi]) continue;
			outBackups.add(bi);
			outBackupElems.put(bi, bk.elements()[0]);
			total += 1;
		}
		if (total >= cost) return true;

		List<CardData> hand = mw.gameState.getP2Hand();
		Set<String> ldGrants = mw.lightDarkDiscardGrants(false);
		List<Integer> discardable = new ArrayList<>();
		for (int i = 0; i < hand.size(); i++)
			if (CpPaymentUtils.canDiscardForCp(hand.get(i), ldGrants)) discardable.add(i);
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
	 * Stub — whether P2 would play {@code card} by paying its put-into-Break-Zone alternate cost
	 * instead of CP: "You can put a total of 3 Forwards or Monsters you control into the Break Zone
	 * to play Kefka from your hand onto the field." (Kefka 4-080L). Always declines, so P2 plays
	 * such a card only when it can raise the printed CP cost the ordinary way.
	 *
	 * <p>This is not merely an unwired predicate — the route is a genuinely different kind of
	 * decision from {@link #p2PlanPayment} and needs four things before it can return true:
	 *
	 * <ul>
	 *   <li>a heuristic for whether giving up {@code count} of P2's own Characters is worth the
	 *       play at all, which is a board evaluation rather than an affordability sum — this cost
	 *       replaces the CP cost outright, so it is available at zero CP and the question is never
	 *       "can P2 pay" but "should it";</li>
	 *   <li>a choice of <em>which</em> cards to hand over (weakest first is the obvious start, but
	 *       a Backup supplying CP or an ability is worth more than its power suggests);</li>
	 *   <li>a field on {@code P2Plan} to carry the picks, since the plan record models only CP
	 *       payment (backups dulled, cards discarded);</li>
	 *   <li>a branch in {@code executeP2HandPlay} that spends them through
	 *       {@code MainWindow.putP2ForwardIntoBreakZone} and the Monster/Backup equivalents — a
	 *       put, not a break.</li>
	 * </ul>
	 *
	 * <p>{@code CostCalculator.canAffordAltCost} and {@code MainWindow.altPutToBzCandidates} are
	 * P1-hardcoded for the same reason and would need their side parameter honoured too.
	 */
	boolean p2PlanAltPutToBzCost(CardData card) {
		return false;
	}

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
		String[] elems = card.elements();
		int[] simCp = new int[elems.length];
		for (int ei = 0; ei < elems.length; ei++)
			simCp[ei] = mw.gameState.getP2CpForElement(elems[ei]);
		int anyCp = 0;

		if (p2CanAfford(reducedCost, elems, simCp, anyCp)) return true;

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
			boolean matches = false;
			for (String e : elems) if (mw.effectiveContainsElement(bk, e)) { matches = true; break; }
			if (matches) matchingBackups.add(bi);
			else offColorBackups.add(bi);
		}
		matchingBackups.sort(Comparator.comparingInt(bi ->
				(int) Arrays.stream(elems)
						.filter(e -> mw.effectiveContainsElement(payable[bi], e)).count()));
		for (int bi : matchingBackups) {
			if (p2CanAfford(reducedCost, elems, simCp, anyCp)) break;
			CardData bk = payable[bi];
			int ei = p2BestDiscardElement(bk, elems, simCp);
			simCp[ei] += 1;
			outBackups.add(bi);
			outBackupElems.put(bi, elems[ei]);
		}
		if (p2CanAfford(reducedCost, elems, simCp, anyCp)) return true;

		// Phase 1b: dull off-color backups — their CP counts toward total but not per-element
		// minimums.  Assign to elems[0] so payP2CostViaBackupsAndDiscards deposits correctly.
		for (int bi : offColorBackups) {
			if (p2CanAfford(reducedCost, elems, simCp, anyCp)) break;
			anyCp += 1;
			outBackups.add(bi);
			outBackupElems.put(bi, elems[0]);
		}
		if (p2CanAfford(reducedCost, elems, simCp, anyCp)) return true;

		// Phase 2: discard cheapest matching-element hand cards (Light/Dark only via field grant).
		List<CardData> hand = mw.gameState.getP2Hand();
		Set<String> ldGrants = mw.lightDarkDiscardGrants(false);
		List<Integer> discardable = new ArrayList<>();
		List<Integer> offColorDiscards = new ArrayList<>();
		for (int i = 0; i < hand.size(); i++) {
			if (i == excludeHandIdx || i == reservedHandIdx) continue;
			CardData c = hand.get(i);
			if (!CpPaymentUtils.canDiscardForCp(c, ldGrants)) continue;
			boolean matches = false;
			for (String e : elems) if (c.containsElement(e)) { matches = true; break; }
			if (matches) discardable.add(i);
			else         offColorDiscards.add(i);
		}
		discardable.sort((a, b) -> hand.get(a).cost() - hand.get(b).cost());
		for (int di : discardable) {
			int ei = p2BestDiscardElement(hand.get(di), elems, simCp);
			simCp[ei] += 2;
			outDiscards.add(di);
			outDiscardElems.put(di, elems[ei]);
			if (p2CanAfford(reducedCost, elems, simCp, anyCp)) return true;
		}

		// Phase 2b: off-color discards — their CP counts toward total but not per-element
		// minimums (mirrors Phase 1b).  Assign to elems[0] so payP2CostViaBackupsAndDiscards
		// deposits correctly; the spend phase drains and clears that bucket.
		offColorDiscards.sort((a, b) -> hand.get(a).cost() - hand.get(b).cost());
		for (int di : offColorDiscards) {
			if (p2CanAfford(reducedCost, elems, simCp, anyCp)) break;
			anyCp += 2;
			outDiscards.add(di);
			outDiscardElems.put(di, elems[0]);
		}
		return p2CanAfford(reducedCost, elems, simCp, anyCp);
	}

	/** Returns true when {@code cpByElemIdx} satisfies the cost and per-element minimums. */
	private static boolean p2CanAfford(int cost, String[] elems, int[] cpByElemIdx, int anyCp) {
		int total = anyCp;
		for (int ei = 0; ei < elems.length; ei++) {
			if (elems.length > 1 && cpByElemIdx[ei] < 1) return false;
			total += cpByElemIdx[ei];
		}
		return total >= cost;
	}

	/**
	 * Returns the index into {@code elems} that {@code dc} should contribute its CP to,
	 * preferring elements that still need their per-element minimum of 1 CP.
	 */
	private static int p2BestDiscardElement(CardData dc, String[] elems, int[] simCp) {
		int bestEi = -1;
		int maxPriority = Integer.MIN_VALUE;
		for (int ei = 0; ei < elems.length; ei++) {
			if (!dc.containsElement(elems[ei])) continue;
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
		onChosen.accept(chooseBlocker(effectiveAttackerPower, attacker, forcedBlock));
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
	public void requestReactiveShields(Runnable onDone) {
		tryP2ReactiveShieldAbilities(onDone);
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
		for (int idx : attackerIndices) {
			if (idx < mw.p1ForwardCards.size())
				minAttackerPower = Math.min(minAttackerPower,
						mw.effectiveP1ForwardPower(idx) - mw.p1ForwardDamage.get(idx));
		}
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
			p1AttackerHigherPower = p1AttackerCard.cannotBeBlockedByHigherPower();
			if (p1AttackerHigherPower) p1AttackerPower = p1AttackerFieldPower;
		}

		// Candidate P2 blockers: Forwards plus Monsters/Backups acting as Forwards.
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

		// Dio 26-075C: a P2 Forward compelled to block this particular attacker has no choice at
		// all — not even the weakest-survivor latitude below — so it is settled before anything
		// else. It only binds while the Forward is a legal blocker, which is what "if possible"
		// means and why the answer is looked for among cands rather than over the whole field.
		CardData attackerCard = attacker != null ? mw.autoAbilityTriggers.fieldCardData(attacker) : null;
		if (attackerCard != null) {
			for (ForwardTarget t : cands) {
				if (t.zone() != ForwardTarget.CardZone.FORWARD) continue;
				if (mw.forwardCompelledToBlock(mw.p2ForwardCards.get(t.idx()), attackerCard)) return t;
			}
		}

		// Honour must-block Forwards first: pick the weakest that can survive.
		if (!mw.p2MustBlock.isEmpty()) {
			ForwardTarget best = null; int bestPow = -1;
			for (ForwardTarget t : cands) {
				if (t.zone() != ForwardTarget.CardZone.FORWARD
						|| !mw.p2MustBlock.contains(mw.p2ForwardCards.get(t.idx()))) continue;
				int p = mw.fieldForwardPower(false, t.zone(), t.idx());
				if (p >= effectiveAttackerPower && (best == null || p < bestPow)) { best = t; bestPow = p; }
			}
			if (best != null) return best;
			// else fall through — constraint lifted if none can survive
		}

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
						|| dc.cardType() != null || dc.category() != null) continue;
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
			final int fi = i;
			if (tryP2UseAbility(eff, mw.p2ForwardFrozen.get(i), mw.p2ForwardStates.get(i),
					mw.p2ForwardPlayedOnTurn.get(i),
					() -> { mw.p2ForwardStates.set(fi, CardState.DULL); mw.refreshP2ForwardSlot(fi); },
					resume)) return;
		}
		for (int i = 0; i < mw.p2BackupCards.length; i++) {
			CardData card = mw.p2BackupCards[i];
			if (card == null) continue;
			final int bi = i;
			if (tryP2UseAbility(card, mw.p2BackupFrozen[i], mw.p2BackupStates[i], 0,
					() -> { mw.p2BackupStates[bi] = CardState.DULL; mw.animateDullP2Backup(bi, true); },
					resume)) return;
		}
		for (int i = 0; i < mw.p2MonsterCards.size(); i++) {
			CardData card = mw.p2MonsterCards.get(i);
			if (card == null) continue;
			final int mi = i;
			if (tryP2UseAbility(card, mw.p2MonsterFrozen.get(i), mw.p2MonsterStates.get(i),
					mw.p2MonsterPlayedOnTurn.get(i),
					() -> { mw.p2MonsterStates.set(mi, CardState.DULL); mw.refreshP2MonsterSlot(mi); },
					resume)) return;
		}
		tryP2BzActionAbilities(() -> tryP2SharedOpponentAbilities(onDone, resume), resume);
	}

	/**
	 * Called from {@link MainWindow#p2AutoPass} when P1 has priority.
	 * Activates any reactive "ability/summon damage becomes 0" shield abilities on P2's forwards
	 * that are not already shielded, then runs {@code onDone}.
	 */
	void tryP2ReactiveShieldAbilities(Runnable onDone) {
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
						() -> { mw.p2ForwardStates.set(fi, CardState.DULL); mw.refreshP2ForwardSlot(fi); },
						backupDullIndices, discardIndices, 0)) {
					logAbandonedActivation(card);
					continue;
				}
				step(() -> tryP2ReactiveShieldAbilities(onDone));
				return;
			}
		}
		onDone.run();
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
				// A shield against the opponent's own Summons/abilities (Krile (XIV) 6-071H) gains
				// nothing when it resolves — it only pays off while their effect is already on the
				// stack, and P2 passes priority rather than responding.
				if (ActionResolver.isOwnForwardProtectionEffect(ability.effectText())) continue;
				// "Opponent cannot search" (e.g. Mog (VI)) only matters if P1 actually has a
				// search option available this turn — otherwise it's a wasted Break Zone activation.
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
			final int fi = i;
			if (tryP2UseOpponentSharedAbility(card, mw.p1ForwardFrozen.get(i), mw.p1ForwardStates.get(i),
					mw.p1ForwardPlayedOnTurn.get(i),
					() -> { mw.p1ForwardStates.set(fi, CardState.DULL); mw.animateDullForward(fi, null); },
					resume)) return;
		}
		for (int i = 0; i < mw.p1BackupCards.length; i++) {
			CardData card = mw.p1BackupCards[i];
			if (card == null) continue;
			final int bi = i;
			if (tryP2UseOpponentSharedAbility(card, mw.p1BackupFrozen[i], mw.p1BackupStates[i], 0,
					() -> { mw.p1BackupStates[bi] = CardState.DULL; mw.refreshP1BackupSlot(bi); },
					resume)) return;
		}
		for (int i = 0; i < mw.p1MonsterCards.size(); i++) {
			CardData card = mw.p1MonsterCards.get(i);
			if (card == null) continue;
			final int mi = i;
			if (tryP2UseOpponentSharedAbility(card, mw.p1MonsterFrozen.get(i), mw.p1MonsterStates.get(i),
					mw.p1MonsterPlayedOnTurn.get(i),
					() -> { mw.p1MonsterStates.set(mi, CardState.DULL); mw.refreshP1MonsterSlot(mi); },
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
			// A shield against the opponent's own Summons/abilities (Krile (XIV) 6-071H) gains
			// nothing when it resolves — it only pays off while their effect is already on the
			// stack, and P2 passes priority rather than responding. Same reasoning as the
			// self-bounce skip below.
			if (ActionResolver.isOwnForwardProtectionEffect(ability.effectText())) continue;
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
			if (p2PlanPayment(starter, starter.cost(), i, reservedIdx,
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
	 * "your opponent cannot search this turn" disruption abilities (e.g. Mog (VI)'s Break Zone
	 * ability) so the CPU doesn't spend one for no effect.
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
	 */
	private CardData[] p2CpBackups() {
		return mw.cpPayableBackupCards(false);
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
		if (ability.isSpecial() && !mw.canPaySpecialCostWithCrystal(source, false)) {
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

		if (p2CanAfford(totalCost, elems, simCp, anyCp)) return true;

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
			if (p2CanAfford(totalCost, elems, simCp, anyCp)) break;
			int ei = elems.length > 0 ? p2BestDiscardElement(payable[bi], elems, simCp) : 0;
			simCp[ei] += 1;
			outBackups.add(bi);
			outBackupElems.put(bi, elems[ei]);
		}
		if (p2CanAfford(totalCost, elems, simCp, anyCp)) return true;

		// Off-color backups can contribute to generic slots
		for (int bi : offColorBackups) {
			if (p2CanAfford(totalCost, elems, simCp, anyCp)) break;
			if (genericCount <= 0) break; // no generic slots available
			anyCp += 1;
			outBackups.add(bi);
			outBackupElems.put(bi, elems.length > 0 ? elems[0] : "");
		}
		if (p2CanAfford(totalCost, elems, simCp, anyCp)) return true;

		List<CardData> hand = mw.gameState.getP2Hand();
		Set<String> ldGrants = mw.lightDarkDiscardGrants(false);
		List<Integer> discardable = new ArrayList<>();
		for (int i = 0; i < hand.size(); i++) {
			if (reservedHandIdxs.contains(i)) continue;
			CardData c = hand.get(i);
			if (!CpPaymentUtils.canDiscardForCp(c, ldGrants)) continue;
			if (elems.length == 0 || genericCount > 0) { discardable.add(i); continue; }
			for (String e : elems) if (c.containsElement(e)) { discardable.add(i); break; }
		}
		discardable.sort((a, b) -> hand.get(a).cost() - hand.get(b).cost());
		for (int di : discardable) {
			if (p2CanAfford(totalCost, elems, simCp, anyCp)) return true;
			// An all-generic cost (Onion Knight 1-181H's 《1》《Dull》) names no Element, so there is
			// no per-element bucket to credit and simCp is zero-length — ei is -1 and the CP can only
			// go to the generic pool. Crediting it to element slot 0 instead threw
			// ArrayIndexOutOfBoundsException, on the one board that reaches here with an empty cost:
			// no active Backup left, so the off-color loop above could not settle the payment first.
			int ei = elems.length > 0 ? p2BestDiscardElement(hand.get(di), elems, simCp) : -1;
			// If off-color but generic slots remain, contribute to any pool
			if (ei < 0 || (!hand.get(di).containsElement(elems[ei]) && genericCount > 0)) {
				anyCp += 2;
			} else {
				simCp[ei] += 2;
			}
			outDiscards.add(di);
			outDiscardElems.put(di, ei >= 0 ? elems[ei] : "");
			if (p2CanAfford(totalCost, elems, simCp, anyCp)) return true;
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
