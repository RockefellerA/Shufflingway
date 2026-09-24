package shufflingway;

/**
 * Decides when P1's own Main Phase should advance by itself because there is nothing left they
 * could do in it.
 *
 * <p>The window polls this with a verdict — "the board is settled, Next is live, and P1 has no
 * play" — and it answers whether to press Next now. The verdict has to hold unbroken for
 * {@link #delayMs} first, for two reasons:
 *
 * <ul>
 *   <li>Feel. A phase that changes the instant it begins reads as a skipped phase. The pause
 *       lets the player see where the turn is before it moves on.</li>
 *   <li>Transients. A verdict can flicker true between two steps of a longer action — a payment
 *       dialog closing before the card lands, a trigger about to be queued. Requiring it to stay
 *       true across several polls means a single stale reading never advances the phase.</li>
 * </ul>
 *
 * <p>Pressing Next is the only thing this ever leads to, and the window does that through the
 * same {@code onNextPhase} a click runs. So a networked opponent sees exactly the messages a
 * click would send — the priority offer, then the wait for their pass — and no new protocol is
 * involved that could leave either client waiting on the other.
 *
 * <p>Not thread-safe: every caller is on the EDT.
 */
final class MainPhaseAutoAdvance {

	/** How long the "no play" verdict must hold before the phase advances. */
	final long delayMs;

	/** When the current unbroken run of "ready" verdicts began, or -1 when there is none. */
	private long readySince = -1;

	MainPhaseAutoAdvance(long delayMs) {
		this.delayMs = delayMs;
	}

	/**
	 * Records one poll.
	 *
	 * @param ready whether the phase could advance right now with nothing lost
	 * @param nowMs the current time, in milliseconds
	 * @return true when the phase should advance now. The run is then used up, so the next
	 *         advance needs a fresh unbroken run of its own.
	 */
	boolean poll(boolean ready, long nowMs) {
		if (!ready) {
			readySince = -1;
			return false;
		}
		if (readySince < 0) readySince = nowMs;
		if (nowMs - readySince < delayMs) return false;
		readySince = -1;
		return true;
	}

	/** Drops any run in progress. Called when a game is torn down. */
	void reset() {
		readySince = -1;
	}
}
