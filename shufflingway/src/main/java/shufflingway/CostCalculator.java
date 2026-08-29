package shufflingway;

import static shufflingway.CardFilters.matchesAltBzType;
import static shufflingway.CpPaymentUtils.matchesAnyElement;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Cost and affordability rules: what a card actually costs to cast after modifiers,
 * and whether a player can pay for it.
 *
 * <p>Extracted from {@link MainWindow}, which keeps a one-line delegator for each
 * method so existing call sites are unaffected.  None of this logic touches Swing;
 * it reads board and turn state through {@code mw}.
 */
class CostCalculator {

	private final MainWindow mw;

	CostCalculator(MainWindow mw) {
		this.mw = mw;
	}

	/**
	 * Returns the effective cast cost of {@code card} after applying any active
	 * cost-reduction modifier that matches it.  Only the first matching modifier
	 * is applied (modifiers stack only when multiple fire independently).
	 */
	int effectiveCastCost(CardData card) {
		// Doublecast (Yuna): Summons with printed cost lower than the last Summon cast this turn cast free
		if (card.isSummon() && mw.p1DoublecastFreeSummons
				&& mw.p1DoublecastLastSummonCost >= 0 && card.cost() < mw.p1DoublecastLastSummonCost)
			return 0;
		int selfRed = 0;
		int selfInc = 0;
		int selfFloor = 0;
		Integer setTo = null;
		for (SelfCostModifier mod : card.selfCostModifiers()) {
			// A "becomes N" modifier replaces the printed cost rather than shifting it, so it is
			// kept out of the delta arithmetic — its scaling type is read only as the on/off test.
			if (mod.setsToCost() >= 0) {
				if (computeSelfCostUnits(mod, true) > 0) setTo = mod.setsToCost();
				continue;
			}
			int units = computeSelfCostUnits(mod, true);
			int delta = mod.amountPerUnit() * units;
			if (mod.isIncrease()) selfInc += delta;
			else {
				selfRed += delta;
				selfFloor = Math.max(selfFloor, mod.minCost());
			}
		}
		// The replacement stands in for the printed cost, then the ordinary reductions run on top —
		// they can only lower it further, so nothing can raise a cost back out of "becomes 0".
		int cost = (setTo != null ? setTo : card.cost()) + selfInc - selfRed;
		cost = Math.max(selfFloor, cost);
		cost = mw.applyFieldReductions(cost, card, true);
		for (CostReductionModifier m : mw.activeCostReductions) {
			if (m.matches(card)) return m.apply(cost);
		}
		return cost;
	}

	/** Returns {@code true} if any card in P1's hand has self-cost modifiers that vary with game state. */
	boolean p1HandHasSelfCostModifiers() {
		for (CardData c : mw.gameState.getP1Hand())
			if (!c.selfCostModifiers().isEmpty()) return true;
		return false;
	}

	int computeSelfCostUnits(SelfCostModifier mod, boolean isP1) {
		List<CardData>   fwds  = isP1 ? mw.p1ForwardCards : mw.p2ForwardCards;
		CardData[]       bkps  = isP1 ? mw.p1BackupCards  : mw.p2BackupCards;
		List<CardData>   bz    = isP1 ? mw.gameState.getP1BreakZone() : mw.gameState.getP2BreakZone();
		List<CardData>   dmg   = isP1 ? mw.gameState.getP1DamageZone() : mw.gameState.getP2DamageZone();
		boolean summonCastFlag = mw.turn(isP1).summonCastThisTurn;

		return switch (mod.scalingType()) {
			case IF_CAST_SUMMON_THIS_TURN ->
				summonCastFlag ? 1 : 0;
			case IF_CAST_JOB_OR_NAME_THIS_TURN -> {
				Set<String> castJobs  = mw.turn(isP1).castJobsThisTurn;
				Set<String> castNames = mw.turn(isP1).castNamesThisTurn;
				yield (castJobs.contains(mod.param1().toLowerCase())
						|| castNames.contains(mod.param2().toLowerCase())) ? 1 : 0;
			}
			case IF_CONTROL_NAME -> {
				String name = mod.param1();
				yield fwds.stream().anyMatch(f -> f.name().equalsIgnoreCase(name))
					|| Arrays.stream(bkps).filter(b -> b != null)
					         .anyMatch(b -> b.name().equalsIgnoreCase(name))
					? 1 : 0;
			}
			case IF_CONTROL_NAME_OR_NAME -> {
				String n1 = mod.param1(), n2 = mod.param2();
				// One unit whichever name answers, and one unit when both do: the printed condition
				// is "if you control", not "for each".
				yield fwds.stream().anyMatch(f -> f.name().equalsIgnoreCase(n1) || f.name().equalsIgnoreCase(n2))
					|| Arrays.stream(bkps).filter(b -> b != null)
					         .anyMatch(b -> b.name().equalsIgnoreCase(n1) || b.name().equalsIgnoreCase(n2))
					? 1 : 0;
			}
			case EACH_FORWARD ->
				fwds.size();
			case EACH_BACKUP ->
				(int) Arrays.stream(bkps).filter(b -> b != null).count();
			case EACH_FORWARD_WITH_CATEGORY -> {
				String cat = mod.param1();
				yield (int) fwds.stream()
						.filter(f -> cat.equalsIgnoreCase(f.category1()) || cat.equalsIgnoreCase(f.category2()))
						.count();
			}
			case EACH_FORWARD_WITH_JOB -> {
				String job = mod.param1();
				yield (int) (fwds.stream().filter(f -> f.hasJob(job)).count()
						+ Arrays.stream(bkps).filter(b -> b != null && b.hasJob(job)).count());
			}
			case EACH_FORWARD_WITH_JOB_OR_NAME -> {
				String job  = mod.param1();
				String name = mod.param2();
				yield (int) (fwds.stream().filter(f -> f.hasJob(job) || name.equalsIgnoreCase(f.name())).count()
						+ Arrays.stream(bkps).filter(b -> b != null
								&& (b.hasJob(job) || name.equalsIgnoreCase(b.name()))).count());
			}
			case EACH_DAMAGE_RECEIVED ->
				dmg.size();
			case EACH_NAME_IN_BZ -> {
				String name = mod.param1();
				yield (int) bz.stream()
						.filter(c -> c.name().equalsIgnoreCase(name))
						.count();
			}
			case PER_N_BZ_CARDS -> {
				int n = Integer.parseInt(mod.param1());
				yield bz.size() / n;
			}
			case EACH_OPPONENT_HAND_CARD ->
				(isP1 ? mw.gameState.getP2Hand() : mw.gameState.getP1Hand()).size();
		case IF_RECEIVED_N_DAMAGE_OR_MORE -> {
				int threshold = Integer.parseInt(mod.param1());
				yield dmg.size() >= threshold ? 1 : 0;
			}
		case IF_OPPONENT_FORWARD_BROKEN_THIS_TURN ->
				(mw.turn(isP1).turnOpponentFwdBroken) ? 1 : 0;
		case IF_CONTROL_N_OR_MORE_CATEGORY_TYPE -> {
				int n = Integer.parseInt(mod.param1());
				String[] parts = mod.param2().split("\\|", 2);
				String cat  = parts[0];
				String type = parts.length > 1 ? parts[1] : "Forward";
				long fwdCount = 0, bkpCount = 0;
				if ("Forward".equalsIgnoreCase(type) || "Character".equalsIgnoreCase(type))
					fwdCount = fwds.stream()
							.filter(f -> cat.equalsIgnoreCase(f.category1()) || cat.equalsIgnoreCase(f.category2()))
							.count();
				if ("Backup".equalsIgnoreCase(type) || "Character".equalsIgnoreCase(type))
					bkpCount = Arrays.stream(bkps)
							.filter(b -> b != null && (cat.equalsIgnoreCase(b.category1()) || cat.equalsIgnoreCase(b.category2())))
							.count();
				yield (fwdCount + bkpCount) >= n ? 1 : 0;
			}
		case EACH_CATEGORY_TYPE_CONTROLLED -> {
				String cat  = mod.param1();
				String type = mod.param2() == null || mod.param2().isBlank() ? "Forward" : mod.param2();
				long fwdCount = fwds.stream()
						.filter(f -> cat.equalsIgnoreCase(f.category1()) || cat.equalsIgnoreCase(f.category2()))
						.count();
				long bkpCount = 0;
				if ("Character".equalsIgnoreCase(type)) {
					bkpCount = Arrays.stream(bkps)
							.filter(b -> b != null)
							.filter(b -> cat.equalsIgnoreCase(b.category1()) || cat.equalsIgnoreCase(b.category2()))
							.count();
				}
				yield (int) (fwdCount + bkpCount);
			}
		case EACH_NAME_OR_NAME_CONTROLLED -> {
				String name1 = mod.param1();
				String name2 = mod.param2();
				yield (int) fwds.stream()
						.filter(f -> f.name().equalsIgnoreCase(name1) || f.name().equalsIgnoreCase(name2))
						.count();
			}
		case PER_N_FILTERED_BZ_CARDS -> {
				int n = Integer.parseInt(mod.param1());
				String spec = mod.param2() == null ? "" : mod.param2();
				String[] parts = spec.split("\\|", 2);
				String elemFilter = parts.length > 0 ? parts[0].trim() : "";
				String typeFilter = parts.length > 1 ? parts[1].trim() : "";
				long filtered = bz.stream().filter(c -> {
					if (!elemFilter.isEmpty() && !elemFilter.equalsIgnoreCase(c.element())) return false;
					if (!typeFilter.isEmpty() && !typeFilter.equalsIgnoreCase(c.type()))   return false;
					return true;
				}).count();
				yield (int) (filtered / n);
			}
		case EACH_CARD_CAST_THIS_TURN ->
				mw.turn(isP1).cardsCastThisTurn;
		case IF_OWN_JOB_BROKEN_THIS_TURN ->
				(mw.turn(isP1).brokenJobsThisTurn)
						.contains(mod.param1().toLowerCase()) ? 1 : 0;
		case IF_CONTROL_NONE_OF_TYPE -> {
				String type = mod.param1() == null ? "Forward" : mod.param1();
				long fwdCount = 0, bkpCount = 0, monCount = 0;
				List<CardData> mons = isP1 ? mw.p1MonsterCards : mw.p2MonsterCards;
				if ("Forward".equalsIgnoreCase(type) || "Character".equalsIgnoreCase(type))
					fwdCount = fwds.size();
				if ("Backup".equalsIgnoreCase(type) || "Character".equalsIgnoreCase(type))
					bkpCount = Arrays.stream(bkps).filter(b -> b != null).count();
				if ("Monster".equalsIgnoreCase(type) || "Character".equalsIgnoreCase(type))
					monCount = mons.size();
				yield (fwdCount + bkpCount + monCount) == 0 ? 1 : 0;
			}
		case IF_OPPONENT_DISCARDED_THIS_TURN ->
				(mw.oppTurn(isP1).discardedByEffectThisTurn) ? 1 : 0;
		case IF_DRAWN_N_OR_MORE_THIS_TURN -> {
				int n = Integer.parseInt(mod.param1());
				yield (mw.turn(isP1).cardsDrawnThisTurn) >= n ? 1 : 0;
			}
		case IF_OPPONENT_DISCARDED_BY_ME_THIS_TURN ->
				(mw.turn(isP1).causedOpponentDiscardThisTurn) ? 1 : 0;
		case IF_OWN_FORWARD_FORMED_PARTY_THIS_TURN ->
				(mw.turn(isP1).formedPartyThisTurn) ? 1 : 0;
		case IF_OPPONENT_HAND_N_OR_LESS -> {
				int n = Integer.parseInt(mod.param1());
				yield (isP1 ? mw.gameState.getP2Hand() : mw.gameState.getP1Hand()).size() <= n ? 1 : 0;
			}
		case EACH_TYPE_WITH_MIN_COST -> {
				int minCostVal = Integer.parseInt(mod.param1());
				String type = mod.param2() == null ? "Character" : mod.param2();
				long fwdCount = 0, bkpCount = 0;
				if ("Forward".equalsIgnoreCase(type) || "Character".equalsIgnoreCase(type))
					fwdCount = fwds.stream().filter(f -> f.cost() >= minCostVal).count();
				if ("Backup".equalsIgnoreCase(type) || "Character".equalsIgnoreCase(type))
					bkpCount = Arrays.stream(bkps)
							.filter(b -> b != null && b.cost() >= minCostVal).count();
				yield (int) (fwdCount + bkpCount);
			}
		case IF_OPPONENT_CONTROLS_MORE_TYPE -> {
				String type = mod.param1() == null ? "Forward" : mod.param1();
				List<CardData> oppFwds = isP1 ? mw.p2ForwardCards : mw.p1ForwardCards;
				CardData[]     oppBkps = isP1 ? mw.p2BackupCards  : mw.p1BackupCards;
				List<CardData> oppMons = isP1 ? mw.p2MonsterCards  : mw.p1MonsterCards;
				long selfCount = 0, oppCount = 0;
				if ("Forward".equalsIgnoreCase(type) || "Character".equalsIgnoreCase(type)) {
					selfCount += fwds.size();
					oppCount  += oppFwds.size();
				}
				if ("Backup".equalsIgnoreCase(type) || "Character".equalsIgnoreCase(type)) {
					selfCount += Arrays.stream(bkps).filter(b -> b != null).count();
					oppCount  += Arrays.stream(oppBkps).filter(b -> b != null).count();
				}
				if ("Monster".equalsIgnoreCase(type)) {
					List<CardData> selfMons = isP1 ? mw.p1MonsterCards : mw.p2MonsterCards;
					selfCount += selfMons.size();
					oppCount  += oppMons.size();
				}
				yield oppCount > selfCount ? 1 : 0;
			}
		case EACH_DISTINCT_OPPONENT_TYPE_ELEMENT -> {
				String type = mod.param1() == null ? "Character" : mod.param1();
				List<CardData> oppFwds = isP1 ? mw.p2ForwardCards : mw.p1ForwardCards;
				CardData[]     oppBkps = isP1 ? mw.p2BackupCards  : mw.p1BackupCards;
				Set<String> elems = new HashSet<>();
				if ("Forward".equalsIgnoreCase(type) || "Character".equalsIgnoreCase(type))
					for (CardData f : oppFwds)
						for (String e : f.element().split("/")) elems.add(e.trim().toLowerCase());
				if ("Backup".equalsIgnoreCase(type) || "Character".equalsIgnoreCase(type))
					for (CardData b : oppBkps)
						if (b != null) for (String e : b.element().split("/")) elems.add(e.trim().toLowerCase());
				yield elems.size();
			}
		case EACH_CRYSTAL_YOU_HAVE ->
				isP1 ? mw.gameState.getP1Crystals() : mw.gameState.getP2Crystals();
		case IF_CONTROL_CATEGORY_TYPE_NOT_ELEMENT -> {
				String[] catType = mod.param1().split("\\|", 2);
				String cat      = catType[0];
				String type     = catType.length > 1 ? catType[1] : "Forward";
				String excluded = mod.param2();
				Predicate<CardData> matches = c ->
						(cat.equalsIgnoreCase(c.category1()) || cat.equalsIgnoreCase(c.category2()))
						&& !mw.effectiveContainsElement(c, excluded);
				boolean found = false;
				if ("Forward".equalsIgnoreCase(type) || "Character".equalsIgnoreCase(type))
					found = fwds.stream().anyMatch(matches);
				if (!found && ("Backup".equalsIgnoreCase(type) || "Character".equalsIgnoreCase(type)))
					found = Arrays.stream(bkps).filter(b -> b != null).anyMatch(matches);
				yield found ? 1 : 0;
			}
		case EACH_DISTINCT_BACKUP_ELEMENT -> {
				Set<String> distinctElems = new HashSet<>();
				for (CardData b : bkps) {
					if (b != null && b.element() != null && !b.element().contains("/"))
						distinctElems.add(b.element().toLowerCase());
				}
				yield distinctElems.size();
			}
		case EACH_ELEMENT_TYPE_CONTROLLED -> {
				String elem = mod.param1();
				String type = mod.param2() == null ? "Forward" : mod.param2();
				long fwdCount = 0, bkpCount = 0, monCount = 0;
				if ("Forward".equalsIgnoreCase(type) || "Character".equalsIgnoreCase(type))
					fwdCount = fwds.stream().filter(f -> elem.equalsIgnoreCase(f.element())).count();
				if ("Backup".equalsIgnoreCase(type) || "Character".equalsIgnoreCase(type))
					bkpCount = Arrays.stream(bkps).filter(b -> b != null && elem.equalsIgnoreCase(b.element())).count();
				if ("Monster".equalsIgnoreCase(type)) {
					List<CardData> mons = isP1 ? mw.p1MonsterCards : mw.p2MonsterCards;
					monCount = mons.stream().filter(mn -> elem.equalsIgnoreCase(mn.element())).count();
				}
				yield (int) (fwdCount + bkpCount + monCount);
			}
		case IF_N_OR_MORE_FORWARDS_LEFT_FIELD_THIS_TURN -> {
				int n = Integer.parseInt(mod.param1());
				yield (mw.turn(isP1).forwardsLeftFieldThisTurn) >= n ? 1 : 0;
			}
		case IF_CONTROL_N_OR_MORE_JOB -> {
				int n    = Integer.parseInt(mod.param1());
				String job = mod.param2();
				long count = fwds.stream().filter(f -> f.hasJob(job)).count()
						+ Arrays.stream(bkps).filter(b -> b != null && b.hasJob(job)).count();
				yield count >= n ? 1 : 0;
			}
		case IF_ELEMENT_FORWARD_ENTERED_FIELD_THIS_TURN -> {
				String elem = mod.param1();
				yield (mw.turn(isP1).elementForwardsEnteredThisTurn)
						.stream().anyMatch(e -> elem.equalsIgnoreCase(e)) ? 1 : 0;
			}
		case IF_OPPONENT_CONTROLS_N_OR_MORE_TYPE -> {
				int n = Integer.parseInt(mod.param1());
				String type = mod.param2() == null ? "Forward" : mod.param2();
				List<CardData> oppFwds = isP1 ? mw.p2ForwardCards : mw.p1ForwardCards;
				CardData[]     oppBkps = isP1 ? mw.p2BackupCards  : mw.p1BackupCards;
				List<CardData> oppMons = isP1 ? mw.p2MonsterCards : mw.p1MonsterCards;
				long count = 0;
				if ("Forward".equalsIgnoreCase(type) || "Character".equalsIgnoreCase(type))
					count += oppFwds.size();
				if ("Backup".equalsIgnoreCase(type) || "Character".equalsIgnoreCase(type))
					count += Arrays.stream(oppBkps).filter(b -> b != null).count();
				if ("Monster".equalsIgnoreCase(type))
					count += oppMons.size();
				yield count >= n ? 1 : 0;
			}
		case IF_FORWARD_ENTERED_VIA_WARP_THIS_TURN ->
				(mw.turn(isP1).forwardEnteredViaWarpThisTurn) ? 1 : 0;
		case IF_N_OR_MORE_JOB_IN_BZ -> {
				int n    = Integer.parseInt(mod.param1());
				String job = mod.param2();
				long count = bz.stream().filter(c -> c.hasJob(job)).count();
				yield count >= n ? 1 : 0;
			}
		case IF_RECEIVED_EXACTLY_N_DAMAGE -> {
				int n = Integer.parseInt(mod.param1());
				yield dmg.size() == n ? 1 : 0;
			}
		case HIGHEST_COST_ELEMENT_FORWARD -> {
				String elem = mod.param1();
				yield fwds.stream()
						.filter(f -> elem.equalsIgnoreCase(f.element()))
						.mapToInt(CardData::cost)
						.max()
						.orElse(0);
			}
		case IF_CONTROL_N_OR_MORE_TYPE -> {
				int n = Integer.parseInt(mod.param1());
				String type = mod.param2() == null ? "Forward" : mod.param2();
				long fwdCount = 0, bkpCount = 0, monCount = 0;
				if ("Forward".equalsIgnoreCase(type) || "Character".equalsIgnoreCase(type))
					fwdCount = fwds.size();
				if ("Backup".equalsIgnoreCase(type) || "Character".equalsIgnoreCase(type))
					bkpCount = Arrays.stream(bkps).filter(b -> b != null).count();
				if ("Monster".equalsIgnoreCase(type)) {
					List<CardData> mons = isP1 ? mw.p1MonsterCards : mw.p2MonsterCards;
					monCount = mons.size();
				}
				yield (fwdCount + bkpCount + monCount) >= n ? 1 : 0;
			}
		case IF_CONTROL_N_OR_MORE_ELEMENT_TYPE -> {
				int n = Integer.parseInt(mod.param1());
				String[] parts = mod.param2().split("\\|", 2);
				String elem = parts[0];
				String type = parts.length > 1 ? parts[1] : "Forward";
				long fwdCount = 0, bkpCount = 0, monCount = 0;
				if ("Forward".equalsIgnoreCase(type) || "Character".equalsIgnoreCase(type))
					fwdCount = fwds.stream().filter(f -> elem.equalsIgnoreCase(f.element())).count();
				if ("Backup".equalsIgnoreCase(type) || "Character".equalsIgnoreCase(type))
					bkpCount = Arrays.stream(bkps)
							.filter(b -> b != null && elem.equalsIgnoreCase(b.element())).count();
				if ("Monster".equalsIgnoreCase(type)) {
					List<CardData> mons = isP1 ? mw.p1MonsterCards : mw.p2MonsterCards;
					monCount = mons.stream().filter(mn -> elem.equalsIgnoreCase(mn.element())).count();
				}
				yield (fwdCount + bkpCount + monCount) >= n ? 1 : 0;
			}
		case IF_BOTH_NAMES_IN_BZ -> {
				String name1 = mod.param1();
				String name2 = mod.param2();
				boolean has1 = bz.stream().anyMatch(c -> c.name().equalsIgnoreCase(name1));
				boolean has2 = bz.stream().anyMatch(c -> c.name().equalsIgnoreCase(name2));
				yield (has1 && has2) ? 1 : 0;
			}
		case PER_N_ELEMENT_TYPE_CONTROLLED -> {
				int n = Integer.parseInt(mod.param1());
				String[] parts = mod.param2().split("\\|", 2);
				String elem = parts[0];
				String type = parts.length > 1 ? parts[1] : "Forward";
				long fwdCount = 0, bkpCount = 0, monCount = 0;
				if ("Forward".equalsIgnoreCase(type) || "Character".equalsIgnoreCase(type))
					fwdCount = fwds.stream().filter(f -> elem.equalsIgnoreCase(f.element())).count();
				if ("Backup".equalsIgnoreCase(type) || "Character".equalsIgnoreCase(type))
					bkpCount = Arrays.stream(bkps)
							.filter(b -> b != null && elem.equalsIgnoreCase(b.element())).count();
				if ("Monster".equalsIgnoreCase(type)) {
					List<CardData> mons = isP1 ? mw.p1MonsterCards : mw.p2MonsterCards;
					monCount = mons.stream().filter(mn -> elem.equalsIgnoreCase(mn.element())).count();
				}
				yield (int) ((fwdCount + bkpCount + monCount) / n);
			}
		case IF_IS_YOUR_TURN ->
				(isP1 == (mw.gameState.getCurrentPlayer() == GameState.Player.P1)) ? 1 : 0;
		case IF_CONTROL_JOB_OR_NAME -> {
				String job  = mod.param1();
				String name = mod.param2();
				boolean found = fwds.stream().anyMatch(f -> f.hasJob(job) || name.equalsIgnoreCase(f.name()))
						|| Arrays.stream(bkps).filter(b -> b != null)
								.anyMatch(b -> b.hasJob(job) || name.equalsIgnoreCase(b.name()));
				yield found ? 1 : 0;
			}
		case EACH_MONSTER -> {
				List<CardData> mons = isP1 ? mw.p1MonsterCards : mw.p2MonsterCards;
				yield mons.size();
			}
		case IF_OPPONENT_CHARACTER_RETURNED_TO_HAND_THIS_TURN ->
				(mw.turn(isP1).turnOpponentCharReturnedToHand) ? 1 : 0;
		case IF_CONTROL_N_OR_MORE_JOB_OR_NAME -> {
				int n = Integer.parseInt(mod.param1());
				String[] parts = mod.param2().split("\\|", 2);
				String job  = parts[0];
				String name = parts.length > 1 ? parts[1] : "";
				long count = fwds.stream()
								.filter(f -> f.hasJob(job) || name.equalsIgnoreCase(f.name())).count()
						+ Arrays.stream(bkps).filter(b -> b != null
								&& (b.hasJob(job) || name.equalsIgnoreCase(b.name()))).count();
				yield count >= n ? 1 : 0;
			}
		case EACH_CARD_DRAWN_THIS_TURN ->
				mw.turn(isP1).cardsDrawnThisTurn;
		case IF_N_OR_MORE_CATEGORY_IN_BZ_AND_RFP -> {
				int n    = Integer.parseInt(mod.param1());
				String cat = mod.param2();
				Predicate<CardData> hasCat = c ->
						cat.equalsIgnoreCase(c.category1()) || cat.equalsIgnoreCase(c.category2());
				long bzCount  = bz.stream().filter(hasCat).count();
				List<CardData> rfp = isP1 ? mw.gameState.getP1PermanentRfp() : mw.gameState.getP2PermanentRfp();
				long rfpCount = rfp.stream().filter(hasCat).count();
				yield (bzCount + rfpCount) >= n ? 1 : 0;
			}
		case IF_OWN_ELEMENT_OR_CATEGORY_BROKEN_THIS_TURN -> {
				String elem = mod.param1();
				String cat  = mod.param2();
				Set<String> elems = mw.turn(isP1).brokenElementsThisTurn;
				Set<String> cats  = mw.turn(isP1).brokenCategoriesThisTurn;
				yield (elem != null && elems.contains(elem.toLowerCase()))
					|| (cat  != null && cats.contains(cat.toLowerCase())) ? 1 : 0;
			}
		case IF_OPPONENT_CONTROLS_N_MORE_THAN_ME -> {
				int n = Integer.parseInt(mod.param1());
				String type = mod.param2() == null ? "Forward" : mod.param2();
				List<CardData> oppFwds = isP1 ? mw.p2ForwardCards : mw.p1ForwardCards;
				CardData[]     oppBkps = isP1 ? mw.p2BackupCards  : mw.p1BackupCards;
				List<CardData> oppMons = isP1 ? mw.p2MonsterCards : mw.p1MonsterCards;
				long selfCount = 0, oppCount = 0;
				if ("Forward".equalsIgnoreCase(type) || "Character".equalsIgnoreCase(type)) {
					selfCount += fwds.size();
					oppCount  += oppFwds.size();
				}
				if ("Backup".equalsIgnoreCase(type) || "Character".equalsIgnoreCase(type)) {
					selfCount += Arrays.stream(bkps).filter(b -> b != null).count();
					oppCount  += Arrays.stream(oppBkps).filter(b -> b != null).count();
				}
				if ("Monster".equalsIgnoreCase(type)) {
					List<CardData> selfMons = isP1 ? mw.p1MonsterCards : mw.p2MonsterCards;
					selfCount += selfMons.size();
					oppCount  += oppMons.size();
				}
				yield (oppCount - selfCount) >= n ? 1 : 0;
			}
		case EACH_JOB_OR_ELEMENT_TYPE_CONTROLLED -> {
				String job = mod.param1();
				String[] parts = mod.param2().split("\\|", 2);
				String elem = parts[0];
				String type = parts.length > 1 ? parts[1] : "Character";
				List<CardData> mons = isP1 ? mw.p1MonsterCards : mw.p2MonsterCards;
				Predicate<CardData> fwdPred = f ->
						f.hasJob(job)
						|| (("Forward".equalsIgnoreCase(type) || "Character".equalsIgnoreCase(type))
							&& elem.equalsIgnoreCase(f.element()));
				Predicate<CardData> bkpPred = b ->
						b.hasJob(job)
						|| (("Backup".equalsIgnoreCase(type) || "Character".equalsIgnoreCase(type))
							&& elem.equalsIgnoreCase(b.element()));
				yield (int) (fwds.stream().filter(fwdPred).count()
						+ Arrays.stream(bkps).filter(b -> b != null).filter(bkpPred).count()
						+ mons.stream().filter(mn -> mn.hasJob(job)
								|| ("Monster".equalsIgnoreCase(type) && elem.equalsIgnoreCase(mn.element()))).count());
			}
		};
	}

	/** {@link #castRestrictionMet(CardData, boolean)} from P1's perspective. */
	boolean castRestrictionMet(CardData card) {
		return castRestrictionMet(card, true);
	}

	/**
	 * Returns {@code true} if the card's "You cannot cast X." / "You can only cast X …" restriction
	 * (if any) is satisfied by the current game state for the player casting it.
	 *
	 * <p>Every zone this reads — Forwards controlled, Break Zone contents, removed-from-game
	 * Summons, the <em>opponent's</em> hand — is taken from {@code isP1}'s side, so the CPU is held
	 * to the same conditions the player is.  The turn checks are likewise relative: "during your
	 * turn" means the caster's own turn, not P1's.
	 */
	boolean castRestrictionMet(CardData card, boolean isP1) {
		// Ahead of the printed restriction, and of the null check that skips it: this ban comes
		// from the board (19-101R Leviathan bounced a copy and barred the name), so a card with no
		// restriction of its own is just as subject to it.
		if (mw.castNameBanned(card.name(), isP1)) return false;

		CastRestriction cr = card.castRestriction();
		if (cr == null) return true;

		// "You cannot cast X." — never castable; only effects that put it onto the field work.
		if (cr.castProhibited()) return false;

		boolean ownTurn = (mw.gameState.getCurrentPlayer() == GameState.Player.P1) == isP1;

		if (cr.opponentTurnOnly() && ownTurn)  return false;
		if ((cr.yourTurnOnly() || cr.mainPhaseOnly()) && !ownTurn) return false;

		List<CardData> ownForwards = isP1 ? mw.p1ForwardCards : mw.p2ForwardCards;
		if (cr.requiresNoForwards() && !ownForwards.isEmpty()) return false;
		if (cr.requiresAForward()   &&  ownForwards.isEmpty()) return false;

		List<CardData> opponentHand = isP1 ? mw.gameState.getP2Hand() : mw.gameState.getP1Hand();
		if (cr.maxOpponentHandSize() >= 0
				&& opponentHand.size() > cr.maxOpponentHandSize()) return false;

		List<CardData> bz = isP1 ? mw.gameState.getP1BreakZone() : mw.gameState.getP2BreakZone();
		if (!cr.requiredBZTypes().isEmpty()) {
			for (String requiredType : cr.requiredBZTypes()) {
				boolean found = switch (requiredType) {
					case "Forward" -> bz.stream().anyMatch(CardData::isForward);
					case "Backup"  -> bz.stream().anyMatch(CardData::isBackup);
					case "Monster" -> bz.stream().anyMatch(CardData::isMonster);
					case "Summon"  -> bz.stream().anyMatch(CardData::isSummon);
					default        -> false;
				};
				if (!found) return false;
			}
		}

		if (cr.minBZAndRfpSummons() > 0) {
			long bzSummons  = bz.stream().filter(CardData::isSummon).count();
			long rfpSummons = (isP1 ? mw.gameState.getP1PermanentRfp() : mw.gameState.getP2PermanentRfp())
					.stream().filter(CardData::isSummon).count();
			if (bzSummons + rfpSummons < cr.minBZAndRfpSummons()) return false;
		}

		// Nox Suzaku 15-130H. The same per-turn flag the ability-level restriction reads, so a
		// Forward lost this turn opens both, and the end of the turn shuts both.
		if (cr.requiresForwardPutToBZThisTurn() && !mw.turn(isP1).forwardPutToBZThisTurn) return false;

		// Sephiroth 11-130L. "Either player" is the larger of the two damage zones, not the caster's
		// own — the opponent taking four opens the cast just as the caster taking four does.
		if (cr.minEitherPlayerDamage() > 0
				&& Math.max(mw.gameState.getP1DamageZone().size(), mw.gameState.getP2DamageZone().size())
					< cr.minEitherPlayerDamage()) return false;

		if (cr.mustControlCondition() != null && !mw.controlConditionMet(cr.mustControlCondition(), isP1))
			return false;

		// "You must control Characters of cost 1, 2, 3, 4, 5 and 6" (Leo 16-126R) — one Character
		// per listed cost. A Character has a single cost, so no card can cover two of them.
		if (!cr.mustControlCosts().isEmpty()) {
			List<CardData> chars = new ArrayList<>(isP1 ? mw.p1ForwardCards : mw.p2ForwardCards);
			for (CardData b : (isP1 ? mw.p1BackupCards : mw.p2BackupCards)) if (b != null) chars.add(b);
			chars.addAll(isP1 ? mw.p1MonsterCards : mw.p2MonsterCards);
			for (int required : cr.mustControlCosts())
				if (chars.stream().noneMatch(c -> c.cost() == required)) return false;
		}

		return true;
	}

	boolean canAffordCard(CardData card, int excludeHandIdx) {
		return canAffordCard(card, excludeHandIdx, null, 0);
	}

	/**
	 * Like {@link #canAffordCard(CardData, int)} but adds {@code extraGenericCost} to the CP
	 * total needed and {@code extraRequiredElems} to the set of elements that must have at
	 * least one available source — used to pre-check a fixed-CP extra cost (e.g. "pay 《Wind》《2》
	 * as an extra cost") on top of the card's own casting cost.
	 */
	boolean canAffordCard(CardData card, int excludeHandIdx, String[] extraRequiredElems, int extraGenericCost) {
		String[] elems = (extraRequiredElems == null || extraRequiredElems.length == 0) ? card.elements()
				: java.util.stream.Stream.concat(Arrays.stream(card.elements()), Arrays.stream(extraRequiredElems))
						.distinct().toArray(String[]::new);
		List<CardData> hand  = mw.gameState.getP1Hand();
		Set<String> ldGrants = mw.lightDarkDiscardGrants(true);
		int totalGenerate = 0;
		int totalCostNeeded = effectiveCastCost(card) + extraGenericCost;

		if (card.isLightOrDark()) {
			// L/D cards accept any element — sum all banked CP and all available sources
			int totalExisting = mw.gameState.getP1CpByElement().values().stream().mapToInt(Integer::intValue).sum();
			for (int i = 0; i < hand.size(); i++) {
				if (i == excludeHandIdx) continue;
				if (CpPaymentUtils.canDiscardForCp(hand.get(i), ldGrants)) totalGenerate += 2;
			}
			if (!mw.backupCpSuppressed(true))
				for (int i = 0; i < mw.p1BackupCards.length; i++) {
					if (mw.p1BackupCards[i] != null && mw.p1BackupStates[i] == CardState.ACTIVE)
						totalGenerate += 1;
				}
			return totalExisting + totalGenerate >= totalCostNeeded;
		}

		boolean[] hasElemSource = new boolean[elems.length];
		int totalExisting = 0;
		for (int ei = 0; ei < elems.length; ei++) {
			int ex = mw.gameState.getP1CpForElement(elems[ei]);
			totalExisting += ex;
			if (ex > 0) hasElemSource[ei] = true;
		}
		for (int i = 0; i < hand.size(); i++) {
			if (i == excludeHandIdx) continue;
			CardData h = hand.get(i);
			if (!CpPaymentUtils.canDiscardForCp(h, ldGrants)) continue;
			totalGenerate += 2;
			for (int ei = 0; ei < elems.length; ei++) {
				if (h.containsElement(elems[ei])) hasElemSource[ei] = true;
			}
		}
		for (int i = 0; i < mw.p1BackupCards.length && !mw.backupCpSuppressed(true); i++) {
			if (mw.p1BackupCards[i] != null && mw.p1BackupStates[i] == CardState.ACTIVE) {
				CardData bkp = mw.p1BackupCards[i];
				String anyElemCat = bkp.backupCpAnyElementCategory();
				boolean isAnyElem = bkp.backupCpAnyElement()
						|| (bkp.backupCpAnyElementOfForwards() && !mw.p1ForwardCards.isEmpty())
						|| (!anyElemCat.isEmpty()
							&& (anyElemCat.equalsIgnoreCase(card.category1())
								|| anyElemCat.equalsIgnoreCase(card.category2())))
						|| mw.isGrantedAnyElementCp(bkp);
				if (isAnyElem) {
					totalGenerate += 1;
					for (int ei = 0; ei < elems.length; ei++) hasElemSource[ei] = true;
				} else {
					List<String> grantedSpecific = mw.getGrantedSpecificElementsCp(bkp);
					for (int ei = 0; ei < elems.length; ei++) {
						if (mw.effectiveContainsElement(bkp, elems[ei]) || grantedSpecific.contains(elems[ei])) {
							totalGenerate += 1;
							hasElemSource[ei] = true;
							break;
						}
					}
				}
			}
		}
		for (boolean hs : hasElemSource) if (!hs) return false;
		return totalExisting + totalGenerate >= totalCostNeeded;
	}

	/** Returns {@code true} when P1 can pay the extra cost. */
	boolean canAffordExtraCost(CardData card, int handIdx, ExtraCost ec) {
		return switch (ec.type()) {
			case BZ_REMOVE    -> mw.gameState.getP1BreakZone().stream().filter(ec::matches).count() >= ec.count();
			case DISCARD_HAND -> mw.gameState.getP1Hand().size() > ec.count(); // must have cards beyond the one being cast
			case CP_X         -> true;
			// Crystals are checked against the player's own stock; the printed CP cost is asked
			// about separately by the menu item's canAffordCard call.
			case CRYSTAL      -> mw.playerCrystals(true) >= ec.count();
			case CP_FIXED     -> {
				String[] extraElems = ec.cpElements().stream().filter(e -> !e.isEmpty())
						.distinct().toArray(String[]::new);
				int extraGeneric = (int) ec.cpElements().stream().filter(String::isEmpty).count();
				yield canAffordCard(card, handIdx, extraElems, extraGeneric);
			}
		};
	}

	/**
	 * Returns true when the player can pay the alternate cast cost for {@code card},
	 * including satisfying any field-presence condition.
	 */
	boolean canAffordAltCost(CardData card, int handIdx) {
		// Condition check: "If you control a Card Name X or a Card Name Y"
		List<String> condNames = card.altConditionCardNames();
		if (!condNames.isEmpty() && condNames.stream().noneMatch(mw::hasCharacterNameOnField)) return false;

		if (mw.playerCrystals(true) < card.altCrystalCost()) return false;

		// Field removal check ("remove 1 Fire Backup you control from the game")
		CardData.AltFieldRemoval fieldRemoval = card.altFieldRemoval();
		if (fieldRemoval != null
				&& mw.altFieldRemovalCandidates(fieldRemoval).size() < fieldRemoval.count()) return false;

		// Dull check ("dull 1 active Fire Job Class Zero Cadet Forward you control and 1 …")
		if (!mw.canPayAltDullCost(card)) return false;

		// Put-into-Break-Zone check ("put a total of 3 Forwards or Monsters you control into the
		// Break Zone"). Kefka 4-080L is the card being played, so it is not on the field to count
		// itself; every eligible card is a legitimate payment.
		CardData.AltPutToBzCost putToBz = card.altPutToBzCost();
		if (putToBz != null && mw.altPutToBzCandidates(putToBz, true).size() < putToBz.count())
			return false;

		// Break Zone removal check
		List<String> bzReqs = card.altBzRemovals();
		if (!bzReqs.isEmpty()) {
			List<CardData> available = new ArrayList<>(mw.gameState.getP1BreakZone());
			for (String req : bzReqs) {
				String[] parts = req.split(" ", 2);
				String elem = parts[0], type = parts.length > 1 ? parts[1] : "";
				boolean found = false;
				for (int i = 0; i < available.size(); i++) {
					CardData c = available.get(i);
					if (c.containsElement(elem) && matchesAltBzType(c, type)) {
						available.remove(i); found = true; break;
					}
				}
				if (!found) return false;
			}
		}

		List<String> altElems = card.altCpElements();
		if (altElems.isEmpty()) return true;

		// Count available CP sources (backup-only restriction respected)
		LinkedHashMap<String, Integer> needed = new LinkedHashMap<>();
		long genericNeeded = 0;
		for (String e : altElems) { if (e.isEmpty()) genericNeeded++; else needed.merge(e, 1, Integer::sum); }
		String[] elems = needed.keySet().toArray(String[]::new);

		int totalSources = 0;
		for (String e : elems) totalSources += mw.gameState.getP1CpForElement(e);
		for (int i = 0; i < mw.p1BackupCards.length && !mw.backupCpSuppressed(true); i++)
			if (mw.p1BackupCards[i] != null && mw.p1BackupStates[i] == CardState.ACTIVE
					&& (genericNeeded > 0 || matchesAnyElement(mw.p1BackupCards[i], elems))) totalSources++;
		if (!card.altBackupOnlyCp()) {
			Set<String> ldGrants = mw.lightDarkDiscardGrants(true);
			for (int i = 0; i < mw.gameState.getP1Hand().size(); i++) {
				if (i == handIdx) continue;
				CardData h = mw.gameState.getP1Hand().get(i);
				if (CpPaymentUtils.canDiscardForCp(h, ldGrants)
						&& (genericNeeded > 0 || matchesAnyElement(h, elems))) totalSources += 2;
			}
		}
		return totalSources >= altElems.size();
	}

	/**
	 * Returns true if the player can afford the Warp alternate cost of {@code card}.
	 * Warp cost is a list of element CP requirements (e.g. ["Lightning"] = 1 Lightning CP).
	 */
	boolean canAffordWarpCost(CardData card, int handIdx) {
		List<String> warpCost = card.warpCost();
		if (warpCost.isEmpty()) return true;

		// Separate element-specific requirements from generic CP (empty-string entries).
		// A Fina-style grant collapses the whole cost to generic: no element requirement survives.
		boolean anyElem    = warpCostAnyElement(true);
		boolean hasGeneric = anyElem || warpCost.contains("");
		LinkedHashMap<String, Integer> needed = new LinkedHashMap<>();
		if (!anyElem) for (String e : warpCost) if (!e.isEmpty()) needed.merge(e, 1, Integer::sum);
		String[] elems = needed.keySet().toArray(String[]::new);
		int total = warpCost.size();

		boolean[] hasSrc = new boolean[elems.length];
		int available = 0;

		// Banked CP (element-specific)
		for (int ei = 0; ei < elems.length; ei++) {
			int b = mw.gameState.getP1CpForElement(elems[ei]);
			available += b;
			if (b > 0) hasSrc[ei] = true;
		}
		// Banked CP of any element counts toward generic
		if (hasGeneric) {
			available += mw.gameState.getP1CpByElement().values().stream().mapToInt(Integer::intValue).sum();
			for (int ei = 0; ei < elems.length; ei++)
				available -= mw.gameState.getP1CpForElement(elems[ei]); // avoid double-counting
		}

		// Undulled backups: matching backups satisfy element requirements;
		// any backup can cover generic CP
		for (int i = 0; i < mw.p1BackupCards.length && !mw.backupCpSuppressed(true); i++) {
			if (mw.p1BackupCards[i] == null || mw.p1BackupStates[i] != CardState.ACTIVE) continue;
			boolean matched = false;
			for (int ei = 0; ei < elems.length; ei++) {
				if (mw.effectiveContainsElement(mw.p1BackupCards[i], elems[ei])) {
					available++;
					hasSrc[ei] = true;
					matched = true;
					break;
				}
			}
			if (!matched && hasGeneric) available++;
		}

		// Non-L/D (or grant-covered L/D) hand cards always contribute 2 CP toward total
		List<CardData> hand = mw.gameState.getP1Hand();
		Set<String> ldGrants = mw.lightDarkDiscardGrants(true);
		for (int i = 0; i < hand.size(); i++) {
			if (i == handIdx) continue;
			CardData h = hand.get(i);
			if (!CpPaymentUtils.canDiscardForCp(h, ldGrants)) continue;
			available += 2;
			for (int ei = 0; ei < elems.length; ei++) {
				if (h.containsElement(elems[ei])) hasSrc[ei] = true;
			}
		}

		for (boolean s : hasSrc) if (!s) return false;
		return available >= total;
	}

	/**
	 * Returns true if the given player controls a card granting "Your Warp cost can be paid with
	 * CP of any Element." (Fina), which makes every element of the Warp cost payable by any CP.
	 */
	boolean warpCostAnyElement(boolean isP1) {
		for (CardData c : mw.playerForwardCards(isP1))
			if (c.warpCostAnyElement() && !mw.lostAbilitiesCards.contains(c)) return true;
		for (CardData c : mw.playerBackupCards(isP1))
			if (c != null && c.warpCostAnyElement() && !mw.lostAbilitiesCards.contains(c)) return true;
		for (CardData c : mw.playerMonsterCards(isP1))
			if (c.warpCostAnyElement() && !mw.lostAbilitiesCards.contains(c)) return true;
		return false;
	}

	/**
	 * True when {@code isP1} can currently pay an optional "if you don't pay 《…》" cost. Exactly one
	 * of the three forms applies: {@code crystals} Crystals, one CP of {@code element}, or
	 * {@code cp} generic CP.
	 */
	boolean canPayOptionalCost(boolean isP1, int cp, String element, int crystals) {
		if (crystals > 0)      return mw.playerCrystals(isP1) >= crystals;
		if (element != null)   return canAffordCpTokens(List.of(element), 1, isP1);
		if (cp > 0)            return canAffordCpTokens(Collections.nCopies(cp, ""), cp, isP1);
		return true;
	}

	/**
	 * Returns {@code true} if the player can afford the CP portion of an action
	 * ability's cost (element and generic CP only; Dull/S requirements are checked
	 * separately in the context-menu enable logic).
	 */
	boolean canAffordAbilityCost(ActionAbility ability, boolean isP1) {
		List<String> cost = ability.cpCost();
		if (cost.isEmpty()) return true;
		int total = cost.size();
		if (ability.inlineCostReductionJob() != null)
			total = Math.max(0, total - mw.computeInlineReduction(ability.inlineCostReductionJob(),
					ability.inlineCostReductionExcludeName(), isP1));
		return canAffordCpTokens(cost, total, isP1);
	}

	/**
	 * True when {@code isP1} could currently assemble a CP payment described by {@code cost} — one
	 * token per CP, {@code ""} for generic CP and an element name for element-specific CP. Counts
	 * active Backups and hand cards that could be discarded for 2 CP, and requires at least one
	 * source for every element named in the cost.
	 *
	 * @param total the number of CP actually owed, which cost reductions may drop below {@code cost.size()}
	 */
	boolean canAffordCpTokens(List<String> cost, int total, boolean isP1) {
		if (cost.isEmpty()) return true;

		boolean hasGeneric = cost.contains("");
		LinkedHashMap<String, Integer> needed = new LinkedHashMap<>();
		for (String e : cost) if (!e.isEmpty()) needed.merge(e, 1, Integer::sum);
		String[] elems = needed.keySet().toArray(String[]::new);

		boolean[] hasSrc = new boolean[elems.length];
		int available = 0;

		for (int ei = 0; ei < elems.length; ei++) {
			int b = mw.playerCpForElem(isP1, elems[ei]);
			available += b;
			if (b > 0) hasSrc[ei] = true;
		}
		if (hasGeneric) {
			available += mw.playerCpByElem(isP1).values().stream().mapToInt(Integer::intValue).sum();
			for (int ei = 0; ei < elems.length; ei++) available -= mw.playerCpForElem(isP1, elems[ei]);
		}
		CardData[]  bkpCards  = mw.playerBackupCards(isP1);
		CardState[] bkpStates = mw.playerBackupStates(isP1);
		for (int i = 0; i < bkpCards.length && !mw.backupCpSuppressed(isP1); i++) {
			if (bkpCards[i] == null || bkpStates[i] != CardState.ACTIVE) continue;
			CardData bkp = bkpCards[i];
			boolean isAnyElem = bkp.backupCpAnyElement()
					|| (bkp.backupCpAnyElementOfForwards() && !mw.playerForwardCards(isP1).isEmpty())
					|| mw.isGrantedAnyElementCp(bkp);
			if (isAnyElem) {
				available++;
				for (int ei = 0; ei < elems.length; ei++) hasSrc[ei] = true;
			} else {
				List<String> grantedSpecific = mw.getGrantedSpecificElementsCp(bkp);
				boolean matched = false;
				for (int ei = 0; ei < elems.length; ei++) {
					if (mw.effectiveContainsElement(bkp, elems[ei]) || grantedSpecific.contains(elems[ei])) {
						available++; hasSrc[ei] = true; matched = true; break;
					}
				}
				if (!matched && hasGeneric) available++;
			}
		}
		Set<String> ldGrants = mw.lightDarkDiscardGrants(isP1);
		for (CardData h : mw.playerHand(isP1)) {
			if (!CpPaymentUtils.canDiscardForCp(h, ldGrants)) continue;
			available += 2;
			for (int ei = 0; ei < elems.length; ei++) if (h.containsElement(elems[ei])) hasSrc[ei] = true;
		}
		for (boolean s : hasSrc) if (!s) return false;
		return available >= total;
	}

	/** Returns true if the player can afford the Priming cost of {@code card} (card is on the field, not in hand). */
	boolean canAffordPrimingCost(CardData card) {
		// The effective cost, not the printed one: Dion 29-106H's discount has to gate the menu item
		// as well as the payment, or it would offer a price the dialog then charges differently.
		List<String> cost = mw.effectivePrimingCost(card, true);
		if (cost.isEmpty()) return true;

		boolean hasGeneric = cost.contains("");
		LinkedHashMap<String, Integer> needed = new LinkedHashMap<>();
		for (String e : cost) if (!e.isEmpty()) needed.merge(e, 1, Integer::sum);
		String[] elems = needed.keySet().toArray(String[]::new);
		int total = cost.size();

		boolean[] hasSrc = new boolean[elems.length];
		int available = 0;

		for (int ei = 0; ei < elems.length; ei++) {
			int b = mw.gameState.getP1CpForElement(elems[ei]);
			available += b;
			if (b > 0) hasSrc[ei] = true;
		}
		if (hasGeneric) {
			available += mw.gameState.getP1CpByElement().values().stream().mapToInt(Integer::intValue).sum();
			for (int ei = 0; ei < elems.length; ei++) available -= mw.gameState.getP1CpForElement(elems[ei]);
		}
		for (int i = 0; i < mw.p1BackupCards.length && !mw.backupCpSuppressed(true); i++) {
			if (mw.p1BackupCards[i] == null || mw.p1BackupStates[i] != CardState.ACTIVE) continue;
			boolean matched = false;
			for (int ei = 0; ei < elems.length; ei++) {
				if (mw.effectiveContainsElement(mw.p1BackupCards[i], elems[ei])) { available++; hasSrc[ei] = true; matched = true; break; }
			}
			if (!matched && hasGeneric) available++;
		}
		List<CardData> hand = mw.gameState.getP1Hand();
		Set<String> ldGrants = mw.lightDarkDiscardGrants(true);
		for (CardData h : hand) {
			if (!CpPaymentUtils.canDiscardForCp(h, ldGrants)) continue;
			available += 2;
			for (int ei = 0; ei < elems.length; ei++) if (h.containsElement(elems[ei])) hasSrc[ei] = true;
		}
		for (boolean s : hasSrc) if (!s) return false;
		return available >= total;
	}
}
