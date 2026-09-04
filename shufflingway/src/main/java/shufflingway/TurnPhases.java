package shufflingway;

import java.util.EnumSet;
import java.util.List;

/**
 * The mechanical parts of a turn — the work the rules do regardless of who is playing:
 * activating and thawing cards, drawing for the turn, and end-of-turn cleanup.
 *
 * <p>Extracted from {@link ComputerPlayer} because a remote opponent needs exactly the same
 * steps. The AI used to own them only because it was the only thing that ever drove P2's turn;
 * with {@link RemoteOpponent} there are two drivers, and both must apply identical state
 * changes or the clients diverge.
 *
 * <p>Nothing here makes a decision or paces itself. Callers own sequencing — advancing the
 * phase, waiting for the board to settle, and any choice a player has to make (the end-phase
 * discard down to five is a decision, so it stays with the caller).
 */
class TurnPhases {

	private final MainWindow mw;

	TurnPhases(MainWindow mw) {
		this.mw = mw;
	}

	// ── Active Phase ─────────────────────────────────────────────────────

	/**
	 * Runs P2's Active Phase: clears their per-turn tracking, activates their dull cards and
	 * thaws their frozen ones. Does not advance the phase.
	 */
	void runP2ActivePhase() {
		mw.p2Turn.receivedDamageThisTurn = false;
		mw.p2Turn.resetCastTracking();
		mw.p2Turn.turnOpponentFwdBroken = false;
		mw.p2Turn.brokenJobsThisTurn.clear();
		mw.p2Turn.brokenElementsThisTurn.clear();
		mw.p2Turn.brokenCategoriesThisTurn.clear();
		mw.p2Turn.cardsDrawnThisTurn = 0;
		mw.p2Turn.discardedByEffectThisTurn = false;
		mw.p2Turn.causedOpponentDiscardThisTurn = false;
		mw.p2Turn.formedPartyThisTurn = false;
		mw.p2Turn.forwardsLeftFieldThisTurn = 0;
		mw.p2Turn.forwardPutToBZThisTurn = false;
		// Both sides, at both turn boundaries: "during this turn" ends when the turn does, whoever
		// owns the Break Zone. See PlayerTurnState.putToBzFromFieldThisTurn.
		mw.p1Turn.putToBzFromFieldThisTurn.clear();
		mw.p2Turn.putToBzFromFieldThisTurn.clear();
		mw.p2Turn.castRemovedUsedThisTurn.clear();
		mw.p2Turn.elementForwardsEnteredThisTurn.clear();
		mw.p2Turn.cardsTookDamageThisTurn.clear();
		mw.p2Turn.forwardEnteredViaWarpThisTurn = false;
		mw.p2Turn.turnOpponentCharReturnedToHand = false;
		// Both sides: Edge 15-045H's shield is scoped to "each turn", so it returns on the
		// opponent's turn as well as its controller's.
		mw.p1Turn.firstOppEffectDamageZeroedThisTurn.clear();
		mw.p2Turn.firstOppEffectDamageZeroedThisTurn.clear();
		mw.p1Turn.firstOppForwardAutoCancelledThisTurn.clear();
		mw.p2Turn.firstOppForwardAutoCancelledThisTurn.clear();
		int activated = 0, thawed = 0;

		// Pass 1: activate DULL cards; frozen cards are skipped
		for (int i = 0; i < mw.p2BackupStates.length; i++) {
			if (mw.p2BackupCards[i] == null) continue;
			if (mw.p2BackupStates[i] == CardState.DULL && !mw.p2BackupFrozen[i]) {
				mw.p2BackupStates[i] = CardState.ACTIVE;  mw.animateDullP2Backup(i, false); activated++;
			}
		}
		for (int i = 0; i < mw.p2ForwardStates.size(); i++) {
			mw.p2ForwardDamage.set(i, 0);
			CardState fs = mw.p2ForwardStates.get(i);
			if (fs == CardState.DULL && !mw.p2ForwardFrozen.get(i)) {
				mw.p2ForwardStates.set(i, CardState.ACTIVE); mw.animateActivateP2Forward(i); activated++;
			} else {
				mw.refreshP2ForwardSlot(i);
			}
		}
		for (int i = 0; i < mw.p2MonsterStates.size(); i++) {
			CardState ms = mw.p2MonsterStates.get(i);
			if (ms == CardState.DULL && !mw.p2MonsterFrozen.get(i)) {
				mw.p2MonsterStates.set(i, CardState.ACTIVE); mw.animateActivateP2Monster(i); activated++;
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
		mw.logEntry(activePhaseMessage("Turn " + mw.gameState.getTurnNumber() + " — P2 Active Phase",
				activated, thawed));
	}

	/**
	 * Runs P1's turn start: per-turn tracking reset, Active Phase, and the turn's draw, leaving
	 * the game in Main Phase 1 with the Next Phase button live.
	 *
	 * <p>Used both when the AI hands the turn back and when a remote opponent ends theirs.
	 */
	void runP1TurnStart() {
		mw.p1Turn.receivedDamageThisTurn = false;
		// Cleared again here, not only at the end of P1's own turn: anything P1 cast while holding
		// priority during the opponent's turn belonged to that turn, not to the one starting now.
		mw.p1Turn.resetCastTracking();
		mw.p1Turn.turnOpponentFwdBroken = false;
		mw.p1Turn.brokenJobsThisTurn.clear();
		mw.p1Turn.brokenElementsThisTurn.clear();
		mw.p1Turn.brokenCategoriesThisTurn.clear();
		mw.p1Turn.cardsDrawnThisTurn = 0;
		mw.p1Turn.discardedByEffectThisTurn = false;
		mw.p1Turn.causedOpponentDiscardThisTurn = false;
		mw.p1Turn.formedPartyThisTurn = false;
		mw.p1Turn.partyAnyElementThisTurn = false;
		mw.p2Turn.partyAnyElementThisTurn = false;
		mw.p1Turn.forwardsLeftFieldThisTurn = 0;
		mw.p1Turn.forwardPutToBZThisTurn = false;
		// Both sides, as at the other turn boundary above.
		mw.p1Turn.putToBzFromFieldThisTurn.clear();
		mw.p2Turn.putToBzFromFieldThisTurn.clear();
		mw.p1Turn.castRemovedUsedThisTurn.clear();
		mw.p1Turn.elementForwardsEnteredThisTurn.clear();
		mw.p1Turn.cardsTookDamageThisTurn.clear();
		mw.p1Turn.forwardEnteredViaWarpThisTurn = false;
		mw.p1Turn.turnOpponentCharReturnedToHand = false;
		// Both sides, for the same reason as in runP2ActivePhase.
		mw.p1Turn.firstOppEffectDamageZeroedThisTurn.clear();
		mw.p2Turn.firstOppEffectDamageZeroedThisTurn.clear();
		mw.p1Turn.firstOppForwardAutoCancelledThisTurn.clear();
		mw.p2Turn.firstOppForwardAutoCancelledThisTurn.clear();
		for (int i = 0; i < mw.p1MonsterCards.size(); i++) mw.refreshP1MonsterSlot(i);
		for (int i = 0; i < mw.p2MonsterCards.size(); i++) mw.refreshP2MonsterSlot(i);
		int activated = 0, thawed = 0;

		// Pass 1: activate DULL cards; frozen cards are skipped
		for (int i = 0; i < mw.p1BackupStates.length; i++) {
			if (mw.p1BackupStates[i] == CardState.DULL && !mw.p1BackupFrozen[i]) {
				mw.p1BackupStates[i] = CardState.ACTIVE; mw.animateDullBackup(i, false); activated++;
			}
		}
		for (int i = 0; i < mw.p1ForwardStates.size(); i++) {
			CardState fs = mw.p1ForwardStates.get(i);
			if (fs == CardState.DULL && !mw.p1ForwardFrozen.get(i)) {
				mw.p1ForwardStates.set(i, CardState.ACTIVE); mw.animateActivateForward(i); activated++;
			}
		}
		for (int i = 0; i < mw.p1MonsterCards.size(); i++) {
			CardState fs = mw.p1MonsterStates.get(i);
			if (mw.p1MonsterFrozen.get(i)) continue;
			if (fs == CardState.DULL) {
				mw.p1MonsterStates.set(i, CardState.ACTIVE); mw.animateActivateMonster(i); activated++;
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

		mw.logEntry(activePhaseMessage("Turn " + mw.gameState.getTurnNumber() + " — Active Phase",
				activated, thawed));

		// These two advances are the local player's own, so a networked opponent has to see them.
		mw.advanceLocalPhase(); // ACTIVE → DRAW
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
		mw.advanceLocalPhase(); // DRAW → MAIN_1
		mw.refreshPhaseTracker();
		mw.logEntry("Main Phase 1");
		mw.processWarpCounters(true);
		mw.nextPhaseButton.setEnabled(true);
		mw.sendTurnStartChecksum();
	}

	private static String activePhaseMessage(String head, int activated, int thawed) {
		StringBuilder msg = new StringBuilder(head);
		if (activated > 0) msg.append(" (").append(activated).append(" activated");
		if (thawed > 0)    msg.append(activated > 0 ? ", " : " (").append(thawed).append(" thawed");
		if (activated > 0 || thawed > 0) msg.append(")");
		return msg.toString();
	}

	// ── Draw Phase ───────────────────────────────────────────────────────

	/**
	 * Draws P2's cards for the turn and updates their zone labels.
	 * The caller checks the returned count against {@code count} to detect milling out.
	 *
	 * @return the cards actually drawn, which is short of {@code count} if the deck ran out
	 */
	List<CardData> runP2DrawPhase(int count) {
		List<CardData> drawn = mw.drawP2Cards(count);
		mw.animateCardDraw(false, drawn.size());
		mw.refreshP2DeckLabel();
		mw.refreshP2HandCountLabel();
		return drawn;
	}

	// ── End Phase ────────────────────────────────────────────────────────

	/**
	 * End-of-turn cleanup for a turn P2 has just finished: fires end-of-turn triggers and clears
	 * everything that only lasts "until the end of the turn" on both sides of the board.
	 *
	 * <p>Does not discard down to five (a decision) and does not advance the phase.
	 */
	void runP2EndOfTurnCleanup() {
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
		mw.p1CannotBeBlocked.clear();              mw.p2CannotBeBlocked.clear();
		mw.p1CannotBeBlockedByCost.clear();        mw.p2CannotBeBlockedByCost.clear();
		mw.p1CannotBeBlockedByPower.clear();       mw.p2CannotBeBlockedByPower.clear();
		mw.p1CannotBlock.clear();                  mw.p2CannotBlock.clear();
		mw.p1MustBlock.clear();                    mw.p2MustBlock.clear();
		mw.p1CannotAttack.clear();                 mw.p2CannotAttack.clear();
		mw.p1MustAttack.clear();                   mw.p2MustAttack.clear();
		mw.p2CannotAttackPersistent.clear();       mw.p2CannotBlockPersistent.clear();
		// The far side of the pair above: P1's "until the end of your opponent's turn" shield was
		// granted to outlast P1's own end phase, and this is the boundary it expires at.
		mw.p1CannotBeBrokenUntilOppTurnEnd.clear();
		mw.cannotUseActionAbilitiesThisTurn.clear();
		mw.attacksMadeThisTurn.clear();            mw.extraAttacksThisTurn.clear();
		mw.p1TempAttackTriggers.clear();           mw.p2TempAttackTriggers.clear();
		mw.p1TempBlockTriggers.clear();            mw.p2TempBlockTriggers.clear();
		mw.nextIncomingDmgZeroSet.clear();   mw.allIncomingDmgZeroThisTurnSet.clear();   mw.nextOppEffectDmgZeroSet.clear();   mw.nextIncomingDmgReduceMap.clear();   mw.nextAbilityDmgReduceMap.clear();
		mw.nextIncomingDmgReduceKickbackMap.clear();  mw.pendingShieldKickbacks.clear();
		mw.incomingDmgIncreaseMap.clear();   mw.globalForwardIncomingDmgIncrease = 0;   mw.nullifyAbilityDmgSet.clear();
		mw.p1Turn.nullifyAbilityDmgFilters.clear(); mw.p2Turn.nullifyAbilityDmgFilters.clear();
		mw.p1DoublecastFreeSummons = false;  mw.p2DoublecastFreeSummons = false;
		mw.p1DoublecastLastSummonCost = -1;  mw.p2DoublecastLastSummonCost = -1;
		mw.nullifyAbilityOnlyDmgSet.clear(); mw.nullifySummonOnlyDmgSet.clear(); mw.perCardNonLethalDmgSet.clear();
		// The two until-end-of-turn break grants. P1's cleanup has always cleared the Breaktouch
		// one; this side had not, so a grant made on P2's turn outlived it.
		mw.breaktouchBattleSet.clear();      mw.breakWhenDealtDamageSet.clear();
		mw.cannotBeChosenByElement.clear();  mw.nullifyElementDamageMap.clear();
		mw.nextOutgoingDmgZeroSet.clear();    mw.allOutgoingDmgZeroThisTurnSet.clear();    mw.abilityDmgToForwardZeroedThisTurnSet.clear();    mw.outgoingDmgMultiplierMap.clear();
		mw.nextOutgoingDmgDoublerSet.clear(); mw.outgoingDmgFlatBoostMap.clear();
		mw.perCardIncomingDmgMultiplierMap.clear();
		mw.p1Turn.forwardIncomingDmgMult = 1;      mw.p2Turn.forwardIncomingDmgMult = 1;
		mw.p1Turn.abilityOutgoingDmgMult = 1;      mw.p2Turn.abilityOutgoingDmgMult = 1;
		mw.p1Turn.nonLethalProtection = false;    mw.p2Turn.nonLethalProtection = false;
		mw.p1Turn.dmgReductionDisabled = false;   mw.p2Turn.dmgReductionDisabled = false;
		mw.p1Turn.forwardCannotBlockInferiorPower = false; mw.p2Turn.forwardCannotBlockInferiorPower = false;
		mw.p1Turn.globalDmgReduction  = 0;        mw.p2Turn.globalDmgReduction  = 0;
		mw.p2Turn.attackDeclarationLimit = Integer.MAX_VALUE; mw.p2Turn.attackDeclarationsThisTurn = 0;
		mw.p1Turn.attackDeclarationLimit = Integer.MAX_VALUE;       mw.p1Turn.attackDeclarationsThisTurn = 0;
		mw.p1Turn.cannotSearchThisTurn = false; mw.p2Turn.cannotSearchThisTurn = false;
		mw.p1Turn.cannotCastThisTurn = false;   mw.p2Turn.cannotCastThisTurn = false;
		mw.p1Turn.cannotCastSummonsThisTurn = false; mw.p2Turn.cannotCastSummonsThisTurn = false;
		mw.p1Turn.oppFieldEntryBecomesRfg = false; mw.p2Turn.oppFieldEntryBecomesRfg = false;
		// Aged rather than cleared: 19-101R Leviathan's cast ban runs "until the end of the next
		// turn", so it has to survive this boundary and expire at the following one.
		mw.ageCastNameBans();
		// Last, not with the row refreshes above: the exhausted-attacker glow reads
		// attacksMadeThisTurn, which this method has just emptied.
		mw.refreshCombatGlows();
	}
}
