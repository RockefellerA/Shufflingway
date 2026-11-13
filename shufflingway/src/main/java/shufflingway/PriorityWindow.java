package shufflingway;

/**
 * Where the opponent holds priority while the local player (P1) acts — the windows
 * {@link OpponentController#holdPriority} and {@link ComputerPlayer#respondToStack} are asked in.
 *
 * <p>All but {@link #STACK_RESPONSE} are on P1's turn, after P1 has passed: the phase changes and
 * the two combat checkpoints. Named for what has just happened, since that is what a play made in
 * one has to answer.
 */
enum PriorityWindow {

	/** P1 has passed at the end of Main Phase 1; the Attack Phase is next. */
	MAIN_1_END,

	/** P1 has passed Attack Preparation; the Declare Attackers step is next. */
	ATTACK_PREPARATION,

	/** P1 has declared an attack: the attacker is dull and committed, and the block step is next. */
	ATTACK_DECLARED,

	/** The block (or no block) is declared: the battle is fixed, and damage is next. */
	BLOCK_DECLARED,

	/** P1 has passed at the end of Main Phase 2; the End Phase is next. */
	MAIN_2_END,

	/** A window on P1's turn none of the above names. */
	OTHER,

	/** P1 has put an entry on the Stack — on either player's turn — and it waits to resolve. */
	STACK_RESPONSE
}
