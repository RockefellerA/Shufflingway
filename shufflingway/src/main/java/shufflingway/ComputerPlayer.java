package shufflingway;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import javax.swing.Timer;

/**
 * AI controller for Player 2 (the computer opponent).
 * Package-private; instantiated by {@link MainWindow} via {@code new ComputerPlayer(this)}.
 */
class ComputerPlayer {

	final MainWindow mw;

	ComputerPlayer(MainWindow mw) {
		this.mw = mw;
	}

	private static final int PAUSE_MS = 500;
	private boolean cancelled = false;

	/** Permanently stops this ComputerPlayer; all pending and future steps become no-ops. */
	void cancel() { cancelled = true; }

	/** Schedules {@code r} to run after {@link #PAUSE_MS} ms on the EDT, but waits for the stack to be empty first. */
	private void step(Runnable r) {
		Timer t = new Timer(PAUSE_MS, e -> {
			if (cancelled) return;
			if (mw.gameState.isP1GameOver()) return;
			if (!mw.gameState.getStack().isEmpty()) { step(r); return; }
			r.run();
		});
		t.setRepeats(false);
		t.start();
	}

	/** Entry point: called when P2's ACTIVE phase begins. */
	void runTurn() {
		step(this::doActivePhase);
	}

	// ── Active Phase ─────────────────────────────────────────────────────

	private void doActivePhase() {
		mw.p2ReceivedDamageThisTurn = false;
		mw.p2CardsCastThisTurn = 0;
		mw.p2SummonCastThisTurn = false;
		mw.p2CastJobsThisTurn.clear();
		mw.p2CastNamesThisTurn.clear();
		mw.p2CastCountByNameThisTurn.clear();
		mw.p2TurnOpponentFwdBroken = false;
		mw.p2BrokenJobsThisTurn.clear();
		mw.p2BrokenElementsThisTurn.clear();
		mw.p2BrokenCategoriesThisTurn.clear();
		mw.p2CardsDrawnThisTurn = 0;
		mw.p2DiscardedByEffectThisTurn = false;
		mw.p2CausedOpponentDiscardThisTurn = false;
		mw.p2FormedPartyThisTurn = false;
		mw.p2ForwardsLeftFieldThisTurn = 0;
		mw.p2ForwardPutToBZThisTurn = false;
		mw.p2ElementForwardsEnteredThisTurn.clear();
		mw.p2CardsTookDamageThisTurn.clear();
		mw.p2ForwardEnteredViaWarpThisTurn = false;
		mw.p2TurnOpponentCharReturnedToHand = false;
		int activated = 0, thawed = 0;

		// Pass 1: activate DULL/BRAVE_ATTACKED cards; frozen cards are skipped
		for (int i = 0; i < mw.p2BackupStates.length; i++) {
			if (mw.p2BackupCards[i] == null) continue;
			if (mw.p2BackupStates[i] == CardState.DULL && !mw.p2BackupFrozen[i]) {
				mw.p2BackupStates[i] = CardState.ACTIVE;  mw.refreshP2BackupSlot(i); activated++;
			}
		}
		for (int i = 0; i < mw.p2ForwardStates.size(); i++) {
			mw.p2ForwardDamage.set(i, 0);
			CardState fs = mw.p2ForwardStates.get(i);
			if ((fs == CardState.DULL || fs == CardState.BRAVE_ATTACKED) && !mw.p2ForwardFrozen.get(i)) {
				mw.p2ForwardStates.set(i, CardState.ACTIVE); mw.animateActivateP2Forward(i); activated++;
			} else {
				mw.refreshP2ForwardSlot(i);
			}
		}
		for (int i = 0; i < mw.p2MonsterStates.size(); i++) {
			CardState ms = mw.p2MonsterStates.get(i);
			if ((ms == CardState.DULL || ms == CardState.BRAVE_ATTACKED) && !mw.p2MonsterFrozen.get(i)) {
				mw.p2MonsterStates.set(i, CardState.ACTIVE); mw.refreshP2MonsterSlot(i); activated++;
			} else {
				mw.refreshP2MonsterSlot(i);
			}
		}

		// Pass 2: remove freeze — card state is unchanged, only the frozen flag is cleared
		for (int i = 0; i < mw.p2BackupStates.length; i++) {
			if (mw.p2BackupCards[i] == null) continue;
			if (mw.p2BackupFrozen[i]) { mw.p2BackupFrozen[i] = false; mw.refreshP2BackupSlot(i); thawed++; }
		}
		for (int i = 0; i < mw.p2ForwardStates.size(); i++) {
			if (mw.p2ForwardFrozen.get(i)) { mw.p2ForwardFrozen.set(i, false); mw.refreshP2ForwardSlot(i); thawed++; }
		}
		for (int i = 0; i < mw.p2MonsterStates.size(); i++) {
			if (mw.p2MonsterFrozen.get(i)) { mw.p2MonsterFrozen.set(i, false); mw.refreshP2MonsterSlot(i); thawed++; }
		}
		StringBuilder msg = new StringBuilder("Turn " + mw.gameState.getTurnNumber() + " — P2 Active Phase");
		if (activated > 0) msg.append(" (").append(activated).append(" activated");
		if (thawed > 0)    msg.append(activated > 0 ? ", " : " (").append(thawed).append(" thawed");
		if (activated > 0 || thawed > 0) msg.append(")");
		mw.logEntry(msg.toString());

		mw.gameState.advancePhase(); // ACTIVE → DRAW
		mw.refreshPhaseTracker();
		step(this::doDrawPhase);
	}

	// ── Draw Phase ───────────────────────────────────────────────────────

	private void doDrawPhase() {
		int drawCount = mw.gameState.getTurnNumber() == 1 ? 1 : 2;
		List<CardData> drawn = mw.drawP2Cards(drawCount);
		mw.animateCardDraw(false, drawn.size());
		mw.refreshP2DeckLabel();
		mw.refreshP2HandCountLabel();
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
				mw.logEntry("[P2] Main Phase 2");
				mw.autoAbilityTriggers.triggerAutoAbilitiesForBeginningOfMainPhase2(false);
				step(() -> doMainPhase(this::doEndPhase));
			} else {
				mw.logEntry("[P2] Attack Phase");
				mw.autoAbilityTriggers.triggerAutoAbilitiesForBeginningOfAttackPhase(false);
				mw.refreshAllP2ForwardSlots();
				step(() -> doAttackPhase(() -> {
					mw.gameState.advancePhase(); // ATTACK → MAIN_2
					mw.refreshPhaseTracker();
					mw.logEntry("[P2] Main Phase 2");
					mw.autoAbilityTriggers.triggerAutoAbilitiesForBeginningOfMainPhase2(false);
					step(() -> doMainPhase(this::doEndPhase));
				}));
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
			mw.lastCardWasCast = true;
			mw.p2CardsCastThisTurn++;
			for (String j : card.jobs()) mw.p2CastJobsThisTurn.add(j.toLowerCase());
			mw.p2CastNamesThisTurn.add(card.name().toLowerCase());
			mw.p2CastCountByNameThisTurn.merge(card.name().toLowerCase(), 1, Integer::sum);
			if (card.isSummon()) mw.p2SummonCastThisTurn = true;
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

		// Try action abilities before committing to a hand play or passing.
		tryP2ActionAbilities(() -> {
			P2Plan plan = findPlayPlan();
			if (plan == null) {
				// P2 has no more plays — pass priority to P1
				mw.p2AutoPass(() -> mw.offerP1MainPhasePriority(onDone));
				return;
			}
			executeP2HandPlay(plan);
			step(() -> doMainPhase(onDone));
		});
	}

	/** Executes a planned P2 hand-cast: dulls backups, discards for CP, pays cost, plays the card. */
	private void executeP2HandPlay(P2Plan plan) {
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

		mw.lastCastPaymentElements.clear();
		mw.lastCastActualPaymentElements.clear();
		for (String e : elems) if (!e.isEmpty()) { mw.lastCastPaymentElements.add(e); mw.lastCastActualPaymentElements.add(e); }

		mw.logEntry("[P2] Plays " + toPlay.name());
		mw.lastCardWasCast = true;
		mw.p2CardsCastThisTurn++;
		for (String j : toPlay.jobs()) mw.p2CastJobsThisTurn.add(j.toLowerCase());
		mw.p2CastNamesThisTurn.add(toPlay.name().toLowerCase());
		mw.p2CastCountByNameThisTurn.merge(toPlay.name().toLowerCase(), 1, Integer::sum);
		if (toPlay.isSummon()) mw.p2SummonCastThisTurn = true;
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
			if (s != CardState.ACTIVE && s != CardState.BRAVE_ATTACKED) continue;
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
			if (mw.effectiveP2HasTrait(idx, CardData.Trait.BRAVE)) {
				mw.p2ForwardStates.set(idx, CardState.BRAVE_ATTACKED);
				mw.refreshP2ForwardSlot(idx);
			} else {
				CardState p2PartyBefore = mw.p2ForwardStates.get(idx);
				mw.p2ForwardStates.set(idx, CardState.DULL);
				mw.animateDullP2Forward(idx, null);
				if (p2PartyBefore == CardState.ACTIVE)
					mw.autoAbilityTriggers.triggerAutoAbilitiesForBecomesDull(mw.p2ForwardCards.get(idx), false);
			}
			combinedPower += mw.effectiveP2ForwardPower(idx);
			if (names.length() > 0) names.append(", ");
			names.append(mw.p2ForwardCards.get(idx).name());
		}
		mw.logEntry("[P2] Party Attack! " + names + " (" + combinedPower + " combined)");
		mw.p2FormedPartyThisTurn = true;
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
		if (mw.p2AttackDeclarationsThisTurn >= mw.opponentAttackDeclarationLimit) {
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
			tryP2ActionAbilities(() -> doAttackPhaseInner(onDone));
			return;
		}
		doAttackPhaseInner(onDone);
	}

	private void doAttackPhaseInner(Runnable onDone) {
		if (mw.gameState.isP1GameOver()) return;

		List<Integer> party = p2ChoosePartyAttack();
		if (party != null) {
			mw.p2AttackDeclarationsThisTurn++;
			executeP2PartyAttack(party, () -> {
				if (!mw.gameState.isP1GameOver()) step(() -> doAttackPhase(onDone));
			});
			return;
		}

		for (int i = 0; i < mw.p2ForwardStates.size(); i++) {
			if (!p2ForwardCanAttack(i)) continue;
			mw.p2AttackDeclarationsThisTurn++;
			CardData attacker = mw.p2ForwardPrimedTop.get(i) != null ? mw.p2ForwardPrimedTop.get(i) : mw.p2ForwardCards.get(i);
			mw.logEntry("[P2] " + attacker.name() + " attacks!");
			if (mw.effectiveP2HasTrait(i, CardData.Trait.BRAVE)) {
				mw.p2ForwardStates.set(i, CardState.BRAVE_ATTACKED);
				mw.refreshP2ForwardSlot(i);
			} else {
				CardState p2SingleBefore = mw.p2ForwardStates.get(i);
				mw.p2ForwardStates.set(i, CardState.DULL);
				mw.animateDullP2Forward(i, null);
				if (p2SingleBefore == CardState.ACTIVE)
					mw.autoAbilityTriggers.triggerAutoAbilitiesForBecomesDull(mw.p2ForwardCards.get(i), false);
			}
			if (attacker.canAttackTwice()) {
				if (!mw.p2ForwardCanDoSecondAttack.remove(i))
					mw.p2ForwardCanDoSecondAttack.add(i);
			}
			mw.autoAbilityTriggers.triggerAutoAbilitiesForAttack(attacker, false);
			final int fi = i;
			mw.initP1BlockDeclaration(attacker, fi, () -> {
				if (!mw.gameState.isP1GameOver()) step(() -> doAttackPhase(onDone));
			});
			return;
		}
		for (int i = 0; i < mw.p2MonsterStates.size(); i++) {
			if (!mw.p2MonsterCanAttackAsForward(i)) continue;
			mw.p2AttackDeclarationsThisTurn++;
			CardData attacker = mw.p2MonsterCards.get(i);
			int power = mw.p2MonsterForwardPower(i);
			if (mw.effectiveMonsterHasTrait(false, i, CardData.Trait.BRAVE)) {
				mw.p2MonsterStates.set(i, CardState.BRAVE_ATTACKED);
				mw.refreshP2MonsterSlot(i);
			} else {
				mw.p2MonsterStates.set(i, CardState.DULL);
				mw.animateDullP2Monster(i);
			}
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
			mw.p2AttackDeclarationsThisTurn++;
			CardData attacker = mw.p2BackupCards[i];
			int power = mw.p2BackupForwardPower(i);
			if (mw.effectiveBackupHasTrait(false, i, CardData.Trait.BRAVE)) {
				mw.p2BackupStates[i] = CardState.BRAVE_ATTACKED;
				mw.refreshP2BackupSlot(i);
			} else {
				mw.p2BackupStates[i] = CardState.DULL;
				mw.animateDullP2Backup(i, true);
			}
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
		mw.autoAbilityTriggers.triggerAutoAbilitiesForEndOfYourTurn(false);
		mw.autoAbilityTriggers.triggerAutoAbilitiesForEndOfEachPlayersTurn();
		mw.autoAbilityTriggers.triggerAutoAbilitiesForEndOfOpponentTurn(true);
		mw.fireEndOfTurnEffects(false);
		for (int i = 0; i < mw.p2ForwardDamage.size(); i++) mw.p2ForwardDamage.set(i, 0);
		for (int i = 0; i < mw.p2ForwardPowerBoost.size(); i++) mw.p2ForwardPowerBoost.set(i, 0);
		for (int i = 0; i < mw.p2ForwardPowerReduction.size(); i++) mw.p2ForwardPowerReduction.set(i, 0);
		mw.p2ForwardTempTraits.forEach(EnumSet::clear);
		mw.p2ForwardRemovedTraits.forEach(EnumSet::clear);
		for (int i = 0; i < mw.p2ForwardCards.size(); i++) mw.refreshP2ForwardSlot(i);
		for (int i = 0; i < mw.p1ForwardDamage.size(); i++) mw.p1ForwardDamage.set(i, 0);
		for (int i = 0; i < mw.p1ForwardPowerBoost.size(); i++) mw.p1ForwardPowerBoost.set(i, 0);
		for (int i = 0; i < mw.p1ForwardPowerReduction.size(); i++) mw.p1ForwardPowerReduction.set(i, 0);
		mw.p1ForwardTempTraits.forEach(EnumSet::clear);
		mw.p1ForwardRemovedTraits.forEach(EnumSet::clear);
		for (int i = 0; i < mw.p1ForwardCards.size(); i++) mw.refreshP1ForwardSlot(i);
		mw.p1MonsterPowerBoost.clear(); mw.p2MonsterPowerBoost.clear();
		mw.p1MonsterTempTraits.clear(); mw.p2MonsterTempTraits.clear();
		for (int i = 0; i < mw.p1MonsterCards.size(); i++) mw.refreshP1MonsterSlot(i);
		for (int i = 0; i < mw.p2MonsterCards.size(); i++) mw.refreshP2MonsterSlot(i);
		mw.clearBackupForwardState();
		mw.p1ForwardCannotBeBlocked.clear();       mw.p2ForwardCannotBeBlocked.clear();
		mw.p1ForwardCannotBeBlockedByCost.clear(); mw.p2ForwardCannotBeBlockedByCost.clear();
		mw.p1ForwardCannotBlock.clear();           mw.p2ForwardCannotBlock.clear();
		mw.p1ForwardMustBlock.clear();             mw.p2ForwardMustBlock.clear();
		mw.p1ForwardCannotAttack.clear();          mw.p2ForwardCannotAttack.clear();
		mw.p1ForwardMustAttack.clear();            mw.p2ForwardMustAttack.clear();
		mw.p2ForwardCannotAttackPersistent.clear(); mw.p2ForwardCannotBlockPersistent.clear();
		mw.p1ForwardCanDoSecondAttack.clear();     mw.p2ForwardCanDoSecondAttack.clear();
		mw.p1TempAttackTriggers.clear();           mw.p2TempAttackTriggers.clear();
		mw.p1TempBlockTriggers.clear();            mw.p2TempBlockTriggers.clear();
		mw.nextIncomingDmgZeroSet.clear();   mw.nextIncomingDmgReduceMap.clear();   mw.nextAbilityDmgReduceMap.clear();
		mw.incomingDmgIncreaseMap.clear();   mw.globalForwardIncomingDmgIncrease = 0;   mw.nullifyAbilityDmgSet.clear();
		mw.nullifyAbilityOnlyDmgSet.clear(); mw.perCardNonLethalDmgSet.clear();
		mw.cannotBeChosenByElement.clear();  mw.nullifyElementDamageMap.clear();
		mw.nextOutgoingDmgZeroSet.clear();    mw.outgoingDmgMultiplierMap.clear();
		mw.nextOutgoingDmgDoublerSet.clear(); mw.outgoingDmgFlatBoostMap.clear();
		mw.perCardIncomingDmgMultiplierMap.clear();
		mw.p1ForwardIncomingDmgMult = 1;      mw.p2ForwardIncomingDmgMult = 1;
		mw.p1AbilityOutgoingDmgMult = 1;      mw.p2AbilityOutgoingDmgMult = 1;
		mw.p1NonLethalProtection = false;    mw.p2NonLethalProtection = false;
		mw.p1DmgReductionDisabled = false;   mw.p2DmgReductionDisabled = false;
		mw.p1ForwardCannotBlockInferiorPower = false; mw.p2ForwardCannotBlockInferiorPower = false;
		mw.p1GlobalDmgReduction  = 0;        mw.p2GlobalDmgReduction  = 0;
		mw.opponentAttackDeclarationLimit = Integer.MAX_VALUE; mw.p2AttackDeclarationsThisTurn = 0;
		mw.p1AttackDeclarationLimit = Integer.MAX_VALUE;       mw.p1AttackDeclarationsThisTurn = 0;
		mw.p1CannotSearchThisTurn = false; mw.p2CannotSearchThisTurn = false;
		mw.gameState.advancePhase(); // MAIN_2 → END
		mw.refreshPhaseTracker();
		mw.logEntry("[P2] End Phase");
		// Wait for any end-of-turn auto abilities on the stack to resolve before returning priority to P1.
		step(() -> {
			mw.gameState.advancePhase(); // END → ACTIVE (switches to P1, increments turn)
			mw.refreshPhaseTracker();
			step(this::startP1Turn);  // startP1Turn expects phase == ACTIVE
		});
	}

	// ── P1 turn start (Active + Draw, then hand control back to player) ──

	private void startP1Turn() {
		mw.p1ReceivedDamageThisTurn = false;
		mw.p1CardsCastThisTurn = 0;
		mw.p1SummonCastThisTurn = false;
		mw.p1CastJobsThisTurn.clear();
		mw.p1CastNamesThisTurn.clear();
		mw.p1CastCountByNameThisTurn.clear();
		mw.p1TurnOpponentFwdBroken = false;
		mw.p1BrokenJobsThisTurn.clear();
		mw.p1BrokenElementsThisTurn.clear();
		mw.p1BrokenCategoriesThisTurn.clear();
		mw.p1CardsDrawnThisTurn = 0;
		mw.p1DiscardedByEffectThisTurn = false;
		mw.p1CausedOpponentDiscardThisTurn = false;
		mw.p1FormedPartyThisTurn = false;
		mw.p1PartyAnyElementThisTurn = false;
		mw.p2PartyAnyElementThisTurn = false;
		mw.p1ForwardsLeftFieldThisTurn = 0;
		mw.p1ForwardPutToBZThisTurn = false;
		mw.p1ElementForwardsEnteredThisTurn.clear();
		mw.p1CardsTookDamageThisTurn.clear();
		mw.p1ForwardEnteredViaWarpThisTurn = false;
		mw.p1TurnOpponentCharReturnedToHand = false;
		for (int i = 0; i < mw.p1MonsterCards.size(); i++) mw.refreshP1MonsterSlot(i);
		for (int i = 0; i < mw.p2MonsterCards.size(); i++) mw.refreshP2MonsterSlot(i);
		int activated = 0, thawed = 0;

		// Pass 1: activate DULL/BRAVE_ATTACKED cards; frozen cards are skipped
		for (int i = 0; i < mw.p1BackupStates.length; i++) {
			if (mw.p1BackupStates[i] == CardState.DULL && !mw.p1BackupFrozen[i]) {
				mw.p1BackupStates[i] = CardState.ACTIVE; mw.refreshP1BackupSlot(i); activated++;
			}
		}
		for (int i = 0; i < mw.p1ForwardStates.size(); i++) {
			CardState fs = mw.p1ForwardStates.get(i);
			if ((fs == CardState.DULL || fs == CardState.BRAVE_ATTACKED) && !mw.p1ForwardFrozen.get(i)) {
				mw.p1ForwardStates.set(i, CardState.ACTIVE); mw.animateActivateForward(i); activated++;
			}
		}
		for (int i = 0; i < mw.p1MonsterCards.size(); i++) {
			CardState fs = mw.p1MonsterStates.get(i);
			if (mw.p1MonsterFrozen.get(i)) continue;
			if (fs == CardState.DULL) {
				mw.p1MonsterStates.set(i, CardState.ACTIVE); mw.animateActivateMonster(i); activated++;
			} else if (fs == CardState.BRAVE_ATTACKED) {
				mw.p1MonsterStates.set(i, CardState.ACTIVE); mw.refreshP1MonsterSlot(i); activated++;
			}
		}

		// Pass 2: remove freeze — card state is unchanged, only the frozen flag is cleared
		for (int i = 0; i < mw.p1BackupStates.length; i++) {
			if (mw.p1BackupFrozen[i]) { mw.p1BackupFrozen[i] = false; mw.refreshP1BackupSlot(i); thawed++; }
		}
		for (int i = 0; i < mw.p1ForwardStates.size(); i++) {
			if (mw.p1ForwardFrozen.get(i)) { mw.p1ForwardFrozen.set(i, false); mw.refreshP1ForwardSlot(i); thawed++; }
		}
		for (int i = 0; i < mw.p1MonsterStates.size(); i++) {
			if (mw.p1MonsterFrozen.get(i)) { mw.p1MonsterFrozen.set(i, false); mw.refreshP1MonsterSlot(i); thawed++; }
		}

		StringBuilder msg = new StringBuilder("Turn " + mw.gameState.getTurnNumber() + " — Active Phase");
		if (activated > 0) msg.append(" (").append(activated).append(" activated");
		if (thawed > 0)    msg.append(activated > 0 ? ", " : " (").append(thawed).append(" thawed");
		if (activated > 0 || thawed > 0) msg.append(")");
		mw.logEntry(msg.toString());

		mw.gameState.advancePhase(); // ACTIVE → DRAW
		mw.refreshPhaseTracker();

		List<CardData> drawn = mw.drawP1Cards(2);
		mw.animateCardDraw(true, drawn.size());
		mw.refreshP1HandLabel();
		mw.refreshP1DeckLabel();
		if (drawn.size() < 2) {
			mw.triggerGameOver("Milled Out - You Lose!");
			return;
		}
		mw.logEntry("Draw Phase — Drew " + drawn.size() + " card(s)");
		mw.gameState.advancePhase(); // DRAW → MAIN_1
		mw.refreshPhaseTracker();
		mw.logEntry("Main Phase 1");
		mw.processWarpCounters(true);
		mw.nextPhaseButton.setEnabled(true);
	}

	// ── Helpers ──────────────────────────────────────────────────────────

	private boolean p2ForwardCanAttack(int idx) {
		if (mw.p2ForwardCannotAttack.contains(idx)) return false;
		if (mw.p2ForwardCannotAttackPersistent.contains(idx)) return false;
		CardData fwd = mw.p2ForwardCards.get(idx);
		if (fwd.cannotAttackOrBlock()) return false;
		if (mw.isFieldAbilityCannotAttackOrBlock(fwd, false)) return false;
		CardState state = mw.p2ForwardStates.get(idx);
		boolean activeOk = state == CardState.ACTIVE;
		boolean secondOk = state == CardState.DULL && mw.p2ForwardCanDoSecondAttack.contains(idx);
		if (!activeOk && !secondOk) return false;
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
			Map<Integer, String>   discardElements        // hand idx → element of CP contributed
	) {}

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
			fieldCands.add(i);
		}
		fieldCands.sort((a, b) -> hand.get(b).cost() - hand.get(a).cost());

		// Summons — highest cost first; skip when cast-prohibited by a field ability
		List<Integer> summonCands = new ArrayList<>();
		if (!mw.summonCastingProhibited()) {
			boolean p1HasAutoAbilityOnStack = mw.gameState.getStack().stream()
					.anyMatch(e -> e.isAutoAbility() && e.isP1());
			for (int i = 0; i < hand.size(); i++) {
				CardData c = hand.get(i);
				if (!c.isSummon()) continue;
				if (ActionResolver.cancelsAutoAbility(c.summonEffect()) && !p1HasAutoAbilityOnStack) continue;
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
			backupCands.add(i);
		}
		backupCands.sort((a, b) -> hand.get(b).cost() - hand.get(a).cost());

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
				return new P2Plan(cardIdx, card.cost(), backups, backupElems, discards, discardElems);
			}
		}
		return null;
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
			if (card.isSummon() && mw.summonCastingProhibited()) continue;

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
		for (String e : ActionResolver.ELEMENT_NAMES) total += mw.gameState.getP2CpForElement(e);
		if (total >= cost) return true;

		for (int bi = 0; bi < mw.p2BackupCards.length && total < cost; bi++) {
			CardData bk = mw.p2BackupCards[bi];
			if (bk == null || mw.p2BackupStates[bi] != CardState.ACTIVE || mw.p2BackupFrozen[bi]) continue;
			outBackups.add(bi);
			outBackupElems.put(bi, bk.elements()[0]);
			total += 1;
		}
		if (total >= cost) return true;

		List<CardData> hand = mw.gameState.getP2Hand();
		List<Integer> discardable = new ArrayList<>();
		for (int i = 0; i < hand.size(); i++) if (!hand.get(i).isLightOrDark()) discardable.add(i);
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
	 * out-parameters.  Backups whose elements don't match any required element are skipped
	 * since their CP would be wasted.  {@code excludeHandIdx == -1} means no exclusion
	 * (used by BZ-cast where the source isn't in hand).
	 */
	private boolean p2PlanPayment(CardData card, int reducedCost, int excludeHandIdx,
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
		for (int bi = 0; bi < mw.p2BackupCards.length; bi++) {
			CardData bk = mw.p2BackupCards[bi];
			if (bk == null) continue;
			if (mw.p2BackupStates[bi] != CardState.ACTIVE) continue;
			if (mw.p2BackupFrozen[bi]) continue;
			boolean matches = false;
			for (String e : elems) if (bk.containsElement(e)) { matches = true; break; }
			if (matches) matchingBackups.add(bi);
			else offColorBackups.add(bi);
		}
		matchingBackups.sort(java.util.Comparator.comparingInt(bi ->
				(int) java.util.Arrays.stream(elems)
						.filter(e -> mw.p2BackupCards[bi].containsElement(e)).count()));
		for (int bi : matchingBackups) {
			if (p2CanAfford(reducedCost, elems, simCp, anyCp)) break;
			CardData bk = mw.p2BackupCards[bi];
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

		// Phase 2: discard cheapest matching-element non-Light/Dark hand cards.
		List<CardData> hand = mw.gameState.getP2Hand();
		List<Integer> discardable = new ArrayList<>();
		for (int i = 0; i < hand.size(); i++) {
			if (i == excludeHandIdx) continue;
			CardData c = hand.get(i);
			if (c.isLightOrDark()) continue;
			for (String e : elems) if (c.containsElement(e)) { discardable.add(i); break; }
		}
		discardable.sort((a, b) -> hand.get(a).cost() - hand.get(b).cost());
		for (int di : discardable) {
			int ei = p2BestDiscardElement(hand.get(di), elems, simCp);
			simCp[ei] += 2;
			outDiscards.add(di);
			outDiscardElems.put(di, elems[ei]);
			if (p2CanAfford(reducedCost, elems, simCp, anyCp)) return true;
		}
		return false;
	}

	/** Returns true when {@code cpByElemIdx} satisfies the cost and per-element minimums. */
	private static boolean p2CanAfford(int cost, String[] elems, int[] cpByElemIdx) {
		return p2CanAfford(cost, elems, cpByElemIdx, 0);
	}

	/** As {@link #p2CanAfford(int, String[], int[])} but with additional off-color CP. */
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

	// ── Action Ability AI ─────────────────────────────────────────────────

	/**
	 * Tries to activate any available P2 action ability this main phase.
	 * Calls {@code onDone} when no more usable abilities are found.
	 */
	private void tryP2ActionAbilities(Runnable onDone) {
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
					onDone)) return;
		}
		for (int i = 0; i < mw.p2BackupCards.length; i++) {
			CardData card = mw.p2BackupCards[i];
			if (card == null) continue;
			final int bi = i;
			if (tryP2UseAbility(card, mw.p2BackupFrozen[i], mw.p2BackupStates[i], 0,
					() -> { mw.p2BackupStates[bi] = CardState.DULL; mw.animateDullP2Backup(bi, true); },
					onDone)) return;
		}
		for (int i = 0; i < mw.p2MonsterCards.size(); i++) {
			CardData card = mw.p2MonsterCards.get(i);
			if (card == null) continue;
			final int mi = i;
			if (tryP2UseAbility(card, mw.p2MonsterFrozen.get(i), mw.p2MonsterStates.get(i),
					mw.p2MonsterPlayedOnTurn.get(i),
					() -> { mw.p2MonsterStates.set(mi, CardState.DULL); mw.refreshP2MonsterSlot(mi); },
					onDone)) return;
		}
		tryP2BzActionAbilities(() -> tryP2SharedOpponentAbilities(onDone));
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
				if (!p2PlanAbilityPayment(ability, backupDullIndices, backupElems, discardIndices, discardElems)) continue;
				mw.logEntry("[P2] Activates reactive shield: " + card.name() + " — " + ability.effectText());
				mw.autoAbilityTriggers.executeP2AbilityActivation(ability, card,
						() -> { mw.p2ForwardStates.set(fi, CardState.DULL); mw.refreshP2ForwardSlot(fi); },
						backupDullIndices, discardIndices, 0);
				step(() -> tryP2ReactiveShieldAbilities(onDone));
				return;
			}
		}
		onDone.run();
	}

	/**
	 * Tries to activate any available P2 break-zone action abilities.
	 * Called at the end of {@link #tryP2ActionAbilities} before {@code onDone}.
	 */
	private void tryP2BzActionAbilities(Runnable onDone) {
		List<CardData> bz = mw.gameState.getP2BreakZone();
		for (CardData card : bz) {
			for (ActionAbility ability : card.actionAbilities()) {
				if (ability.breakZoneOnly() == null) continue;
				if (!mw.autoAbilityTriggers.canActivateBzAbility(ability, card, false)) continue;
				if (ActionResolver.parse(ability.effectText(), card) == null) continue;
				if (abilityHarmsChosenTarget(ability) && !p1HasAnyForward()) continue;

				List<Integer>        backupDullIndices = new ArrayList<>();
				Map<Integer, String> backupElems       = new LinkedHashMap<>();
				List<Integer>        discardIndices    = new ArrayList<>();
				Map<Integer, String> discardElems      = new LinkedHashMap<>();
				if (!p2PlanAbilityPayment(ability, backupDullIndices, backupElems, discardIndices, discardElems))
					continue;

				mw.logEntry("[P2] Activates BZ ability: " + card.name() + " — " + ability.effectText());
				mw.autoAbilityTriggers.executeP2AbilityActivation(ability, card, () -> {}, backupDullIndices, discardIndices, 0);
				step(() -> doMainPhase(onDone));
				return;
			}
		}
		onDone.run();
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
	 * low-priority fallback after P2 has exhausted its own abilities this main phase.
	 */
	private void tryP2SharedOpponentAbilities(Runnable onDone) {
		for (int i = 0; i < mw.p1ForwardCards.size(); i++) {
			CardData card = mw.p1ForwardCards.get(i);
			if (card == null) continue;
			final int fi = i;
			if (tryP2UseOpponentSharedAbility(card, mw.p1ForwardFrozen.get(i), mw.p1ForwardStates.get(i),
					mw.p1ForwardPlayedOnTurn.get(i),
					() -> { mw.p1ForwardStates.set(fi, CardState.DULL); mw.animateDullForward(fi, null); },
					onDone)) return;
		}
		for (int i = 0; i < mw.p1BackupCards.length; i++) {
			CardData card = mw.p1BackupCards[i];
			if (card == null) continue;
			final int bi = i;
			if (tryP2UseOpponentSharedAbility(card, mw.p1BackupFrozen[i], mw.p1BackupStates[i], 0,
					() -> { mw.p1BackupStates[bi] = CardState.DULL; mw.refreshP1BackupSlot(bi); },
					onDone)) return;
		}
		for (int i = 0; i < mw.p1MonsterCards.size(); i++) {
			CardData card = mw.p1MonsterCards.get(i);
			if (card == null) continue;
			final int mi = i;
			if (tryP2UseOpponentSharedAbility(card, mw.p1MonsterFrozen.get(i), mw.p1MonsterStates.get(i),
					mw.p1MonsterPlayedOnTurn.get(i),
					() -> { mw.p1MonsterStates.set(mi, CardState.DULL); mw.refreshP1MonsterSlot(mi); },
					onDone)) return;
		}
		onDone.run();
	}

	/**
	 * Checks each {@code usableByEitherPlayer} action ability on {@code card} (a P1 card).
	 * If one is usable, affordable without giving up P2's hand advantage, and its effect is
	 * implemented, pays cost from P2's resources and schedules a doMainPhase continuation.
	 * Returns {@code true} if an ability was dispatched.
	 */
	private boolean tryP2UseOpponentSharedAbility(CardData card, boolean isFrozen, CardState state,
			int playedTurn, Runnable applyDull, Runnable onDone) {
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
			if (!p2PlanAbilityPayment(ability, backupDullIndices, backupElems, discardIndices, discardElems))
				continue;

			mw.logEntry("[P2] Activates shared ability on " + card.name() + " — " + ability.effectText());
			mw.autoAbilityTriggers.executeP2AbilityActivation(ability, card, applyDull, backupDullIndices, discardIndices, 0);
			step(() -> doMainPhase(onDone));
			return true;
		}
		return false;
	}

	/**
	 * Checks each field action ability on {@code card}.  If one is usable, affordable,
	 * and its effect is implemented, pays cost and schedules doMainPhase continuation.
	 * Returns {@code true} if an ability was dispatched.
	 */
	private boolean tryP2UseAbility(CardData card, boolean isFrozen, CardState state,
			int playedTurn, Runnable applyDull, Runnable onDone) {
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

			List<Integer>        backupDullIndices = new ArrayList<>();
			Map<Integer, String> backupElems       = new LinkedHashMap<>();
			List<Integer>        discardIndices    = new ArrayList<>();
			Map<Integer, String> discardElems      = new LinkedHashMap<>();
			if (!p2PlanAbilityPayment(ability, backupDullIndices, backupElems, discardIndices, discardElems))
				continue;

			// Determine X value for X-cost abilities
			int xValue = 0;
			if (ability.hasXCost()) {
				// Count active P2 backups not needed for CP payment
				int usedBackups = backupDullIndices.size();
				int totalActiveBackups = 0;
				for (int bi = 0; bi < mw.p2BackupCards.length; bi++) {
					if (mw.p2BackupCards[bi] != null && mw.p2BackupStates[bi] == CardState.ACTIVE && !mw.p2BackupFrozen[bi])
						totalActiveBackups++;
				}
				xValue = totalActiveBackups - usedBackups;
				if (xValue < 1) continue; // skip if no remaining backups for X
			}

			mw.logEntry("[P2] Activates ability: " + card.name() + " — " + ability.effectText());
			mw.autoAbilityTriggers.executeP2AbilityActivation(ability, card, applyDull, backupDullIndices, discardIndices, xValue);
			step(() -> doMainPhase(onDone));
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

	/** True if P1 has at least one Forward (or temp-forward Monster) on the field. */
	private boolean p1HasAnyForward() {
		for (int i = 0; i < mw.p1ForwardCards.size(); i++)
			if (mw.p1ForwardCards.get(i) != null) return true;
		for (int i = 0; i < mw.p1MonsterCards.size(); i++)
			if (mw.p1MonsterCards.get(i) != null && mw.isP1MonsterTemporarilyForward(i)) return true;
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
	 */
	private boolean p2PlanAbilityPayment(ActionAbility ability,
			List<Integer> outBackups, Map<Integer, String> outBackupElems,
			List<Integer> outDiscards, Map<Integer, String> outDiscardElems) {
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
		for (int bi = 0; bi < mw.p2BackupCards.length; bi++) {
			CardData bk = mw.p2BackupCards[bi];
			if (bk == null || mw.p2BackupStates[bi] != CardState.ACTIVE || mw.p2BackupFrozen[bi]) continue;
			boolean matches = false;
			for (String e : elems) if (bk.containsElement(e)) { matches = true; break; }
			if (matches) matchingBackups.add(bi);
			else         offColorBackups.add(bi);
		}
		matchingBackups.sort(java.util.Comparator.comparingInt(bi ->
				(int) java.util.Arrays.stream(elems)
						.filter(e -> mw.p2BackupCards[bi].containsElement(e)).count()));
		for (int bi : matchingBackups) {
			if (p2CanAfford(totalCost, elems, simCp, anyCp)) break;
			int ei = elems.length > 0 ? p2BestDiscardElement(mw.p2BackupCards[bi], elems, simCp) : 0;
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
		List<Integer> discardable = new ArrayList<>();
		for (int i = 0; i < hand.size(); i++) {
			CardData c = hand.get(i);
			if (c.isLightOrDark()) continue;
			if (elems.length == 0 || genericCount > 0) { discardable.add(i); continue; }
			for (String e : elems) if (c.containsElement(e)) { discardable.add(i); break; }
		}
		discardable.sort((a, b) -> hand.get(a).cost() - hand.get(b).cost());
		for (int di : discardable) {
			if (p2CanAfford(totalCost, elems, simCp, anyCp)) return true;
			int ei = elems.length > 0 ? p2BestDiscardElement(hand.get(di), elems, simCp) : 0;
			// If off-color but generic slots remain, contribute to any pool
			if (elems.length > 0 && !hand.get(di).containsElement(elems[ei]) && genericCount > 0) {
				anyCp += 2;
			} else {
				simCp[ei] += 2;
			}
			outDiscards.add(di);
			outDiscardElems.put(di, elems.length > 0 ? elems[ei] : "");
			if (p2CanAfford(totalCost, elems, simCp, anyCp)) return true;
		}
		return false;
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
