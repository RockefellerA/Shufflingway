package shufflingway;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Every decision the opponent (P2) makes, behind one interface.
 *
 * <p>{@link ComputerPlayer} is the local AI implementation; a networked implementation will
 * later answer the same calls by round-tripping to a remote human. That is the reason every
 * decision here is expressed as <em>request + callback</em> rather than as a return value: a
 * remote opponent cannot answer inline on the EDT, so no caller may assume the answer is
 * available when the call returns.
 *
 * <p>{@code ComputerPlayer} deliberately invokes each callback <em>synchronously</em>, before
 * its {@code request…} method returns. That keeps AI games running in exactly the control flow
 * they had before this seam existed. Callers must still not depend on it.
 *
 * <p>Only decisions belong here. Applying a decision to the board stays in {@link MainWindow}
 * with the rest of the rules, so both a local and a remote opponent drive identical state
 * changes through the same {@code placeP2Card…} / {@code resolveCombat} paths.
 */
interface OpponentController {

	/** Drives the opponent's entire turn, beginning at their Active Phase. */
	void runTurn();

	/** Permanently stops this controller; pending and future steps become no-ops. */
	void cancel();

	/** True when this opponent is the built-in AI rather than a remote human. */
	boolean isCpu();

	/**
	 * Asks the opponent to declare a blocker against a single attacker.
	 *
	 * @param effectiveAttackerPower the attacker's power after all modifiers
	 * @param attacker               the attacking card's field position
	 * @param forcedBlock            true when a "must block if possible" ability applies
	 * @param onChosen               receives the chosen blocker, or {@code null} for no block
	 */
	void requestBlocker(int effectiveAttackerPower, ForwardTarget attacker, boolean forcedBlock,
	                    Consumer<ForwardTarget> onChosen);

	/** As {@link #requestBlocker(int, ForwardTarget, boolean, Consumer)} with no forced block. */
	default void requestBlocker(int effectiveAttackerPower, ForwardTarget attacker,
	                            Consumer<ForwardTarget> onChosen) {
		requestBlocker(effectiveAttackerPower, attacker, false, onChosen);
	}

	/**
	 * What {@link #requestPartyBlocker} hands back for a block whose blocker left the field before
	 * damage: the party is still blocked, but there is no slot left to name. Only a remote opponent
	 * sends it, since a remote party block is answered after the blocking client has resolved it.
	 */
	int PARTY_BLOCKER_LEFT = Integer.MIN_VALUE;

	/**
	 * Asks the opponent to declare a blocker against a party attack.
	 *
	 * @param attackerIndices slot codes ({@link ForwardTarget#slotCode()}) of P1's attacking party
	 * @param combinedPower   the party's combined power
	 * @param forcedBlock     true when a party member carries a "must block if possible" ability
	 * @param onChosen        receives the blocker's slot code ({@link ForwardTarget#slotCode()}) — a P2
	 *                        Forward, or a Monster or Backup acting as one — {@code null} for no block,
	 *                        or {@link #PARTY_BLOCKER_LEFT}
	 */
	void requestPartyBlocker(List<Integer> attackerIndices, int combinedPower, boolean forcedBlock,
	                         Consumer<Integer> onChosen);

	/** As {@link #requestPartyBlocker(List, int, boolean, Consumer)} with no forced block. */
	default void requestPartyBlocker(List<Integer> attackerIndices, int combinedPower,
	                                 Consumer<Integer> onChosen) {
		requestPartyBlocker(attackerIndices, combinedPower, false, onChosen);
	}

	/**
	 * Asks the opponent how to spread its blocker's damage across a blocked party.
	 *
	 * @param attackerIndices slot codes ({@link ForwardTarget#slotCode()}) of P1's attacking party
	 * @param blockerPower    the blocker's power, i.e. the damage available to assign
	 * @param onAssigned      receives attacker slot code → damage; an empty map assigns nothing
	 */
	void requestPartyBlockerDamage(List<Integer> attackerIndices, int blockerPower,
	                               Consumer<Map<Integer, Integer>> onAssigned);

	/**
	 * The opponent holds priority in {@code window} — on the local player's turn after they have
	 * passed, and in the combat rounds of its own turn, where it holds priority first — and runs
	 * {@code onPass} once it passes. The AI plays there ({@link ComputerPlayer#holdPriority}); a remote
	 * player acts inside its own window, so for it this only passes.
	 */
	void holdPriority(PriorityWindow window, Runnable onPass);
}
