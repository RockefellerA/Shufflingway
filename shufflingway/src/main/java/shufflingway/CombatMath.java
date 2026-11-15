package shufflingway;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import shufflingway.ComputerPlayer.Fighter;

/**
 * The battle arithmetic the CPU plans its attacks with, kept apart from any board: what a block on a
 * party can cost it, and whether its attacks force lethal through every way of blocking them
 * (HANDOFF-cpu-backlog.md item 6, and the gaps it left). Battles are fought with
 * {@link ComputerPlayer#battle}, which favours neither side, so the same search serves P1's attacks
 * on P2 as P2's on P1.
 */
final class CombatMath {

	private CombatMath() {}

	/**
	 * A block on a party that goes wrong for the party's side: the members the blocker's damage
	 * breaks, by index, and whether the blocker comes through it.
	 */
	record PartyLoss(List<Integer> broken, boolean blockerSurvives) {}

	/**
	 * The worst a block by {@code blocker} can do to the party {@code members}, when the blocker's
	 * controller spreads its damage — or {@code null} when no spread goes wrong. A spread goes wrong
	 * when it breaks a member and the blocker survives, or breaks members whose costs add up to more
	 * than the blocker's: a trade, but one that loses the dearer cards.
	 *
	 * <p>The rules of {@link MainWindow#resolvePartyBlockDamage}: the party deals its combined power
	 * as one blow; a party has First Strike only when every member does, and then a blocker it breaks
	 * deals nothing back; a blocker with First Strike against a party without it deals its spread
	 * first, and only the members left strike back. Of the bad spreads, the one returned leaves the
	 * blocker standing if any does, then breaks the most cost.
	 */
	static PartyLoss partyLoss(List<Fighter> members, int[] costs, Fighter blocker, int blockerCost) {
		int n = members.size();
		boolean partyFirst = members.stream().allMatch(Fighter::firstStrike) && !blocker.firstStrike();
		boolean blockerFirst = blocker.firstStrike() && !members.stream().allMatch(Fighter::firstStrike);
		int total = members.stream().mapToInt(Fighter::power).sum();
		PartyLoss worst = null;
		int worstCost = -1;
		for (int mask = 1; mask < (1 << n); mask++) {
			int need = 0, lost = 0, standing = total;
			boolean reachable = true;
			for (int i = 0; i < n; i++) {
				if ((mask & (1 << i)) == 0) continue;
				Fighter m = members.get(i);
				if (m.shielded()) { reachable = false; break; }
				need += Math.max(0, m.power() - m.damage());
				lost += costs[i];
				standing -= m.power();
			}
			if (!reachable || need > blocker.power()) continue;
			int blow = blockerFirst ? standing : total;
			boolean blockerBroken = !blocker.shielded() && blocker.damage() + blow >= blocker.power();
			if (partyFirst && blockerBroken) continue;   // it never strikes back
			if (blockerBroken && lost <= blockerCost) continue;
			boolean better = worst == null || (!blockerBroken && !worst.blockerSurvives())
					|| (!blockerBroken == worst.blockerSurvives() && lost > worstCost);
			if (!better) continue;
			List<Integer> broken = new ArrayList<>();
			for (int i = 0; i < n; i++) if ((mask & (1 << i)) != 0) broken.add(i);
			worst = new PartyLoss(broken, !blockerBroken);
			worstCost = lost;
		}
		return worst;
	}

	/** How far {@link #lethalOpener} searches before it gives up and reports no lethal. */
	static final int SEARCH_BUDGET = 200_000;

	/**
	 * The attacker to lead with that forces {@code hitsNeeded} unblocked attacks through
	 * {@code blockers} whatever the defender does, by index — or {@code -1} when the defender can always
	 * stop short of it, or the search ran past {@link #SEARCH_BUDGET}.
	 *
	 * <p>Each attacker attacks once, one at a time, in the order the attacker picks; against each,
	 * the defender takes the hit or blocks with any Forward still standing that {@code canBlock} allows
	 * ({@code canBlock[attacker][blocker]}). Blocking does not dull, so a blocker that survives can
	 * block again — carrying the damage it took, which is what lets a weaker attacker sent first
	 * leave it for a later one to break. Tricks, triggers and parties are not in the search.
	 */
	static int lethalOpener(List<Fighter> attackers, List<Fighter> blockers, boolean[][] canBlock, int hitsNeeded) {
		if (hitsNeeded <= 0 || attackers.size() < hitsNeeded || attackers.size() > 30) return -1;
		Search s = new Search(attackers.toArray(new Fighter[0]), canBlock);
		Fighter[] standing = blockers.toArray(new Fighter[0]);
		try {
			for (int a = 0; a < s.attackers.length; a++)
				if (!s.refuted(a, 0, standing, hitsNeeded)) return a;
		} catch (Search.OutOfBudget e) {
			return -1;
		}
		return -1;
	}

	private static final class Search {
		static final class OutOfBudget extends RuntimeException {
			OutOfBudget() { super(null, null, false, false); }
		}

		final Fighter[] attackers;
		final boolean[][] canBlock;
		final Map<String, Boolean> memo = new HashMap<>();
		int nodes = 0;

		Search(Fighter[] attackers, boolean[][] canBlock) {
			this.attackers = attackers;
			this.canBlock = canBlock;
		}

		/** Whether the attackers not in {@code used} force {@code hits} more through {@code standing} (a gone blocker is null). */
		boolean forces(int used, Fighter[] standing, int hits) {
			if (hits <= 0) return true;
			if (attackers.length - Integer.bitCount(used) < hits) return false;
			if (++nodes > SEARCH_BUDGET) throw new OutOfBudget();
			String key = key(used, standing, hits);
			Boolean known = memo.get(key);
			if (known != null) return known;
			boolean wins = false;
			for (int a = 0; a < attackers.length && !wins; a++)
				if ((used & (1 << a)) == 0 && !refuted(a, used, standing, hits)) wins = true;
			memo.put(key, wins);
			return wins;
		}

		/** Whether the defender has an answer to attacker {@code a} that leaves the rest short. */
		boolean refuted(int a, int used, Fighter[] standing, int hits) {
			int next = used | (1 << a);
			if (!forces(next, standing, hits - 1)) return true;   // the hit is taken
			for (int b = 0; b < standing.length; b++) {
				if (standing[b] == null || !canBlock[a][b]) continue;
				Fighter[] after = standing.clone();
				after[b] = afterBlocking(attackers[a], standing[b]);
				if (!forces(next, after, hits)) return true;
			}
			return false;
		}

		private static String key(int used, Fighter[] standing, int hits) {
			StringBuilder sb = new StringBuilder().append(used).append(':').append(hits);
			for (Fighter f : standing) sb.append(':').append(f == null ? -1 : f.damage());
			return sb.toString();
		}
	}

	/**
	 * {@code blocker} after blocking {@code attacker}: {@code null} when the battle breaks it, else
	 * carrying the damage the attacker dealt — none from an attacker its First Strike broke first.
	 */
	static Fighter afterBlocking(Fighter attacker, Fighter blocker) {
		boolean[] fought = ComputerPlayer.battle(attacker, blocker);
		if (!fought[1]) return null;
		boolean struckFirst = blocker.firstStrike() && !attacker.firstStrike();
		int dealt = struckFirst && !fought[0] ? 0 : attacker.power();
		return new Fighter(blocker.power(), blocker.damage() + dealt, blocker.firstStrike(), blocker.shielded());
	}
}
