package shufflingway;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Image;
import java.awt.Point;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.stream.Collectors;

import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JLayeredPane;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;

import shufflingway.net.ChoiceKind;

import static shufflingway.CardFilters.formatCostFilterLabel;
import static shufflingway.CardFilters.isBlockingTargetFilter;
import static shufflingway.CardFilters.isEnteredThisTurnCondition;
import static shufflingway.CardFilters.isTraitCondition;
import static shufflingway.CardFilters.matchesDiscardType;
import static shufflingway.CardFilters.meetsCardNameFilter;
import static shufflingway.CardFilters.meetsCategoryFilter;
import static shufflingway.CardFilters.meetsCostConstraint;
import static shufflingway.CardFilters.meetsElementExclusion;
import static shufflingway.CardFilters.meetsElementFilter;
import static shufflingway.CardFilters.meetsPowerConstraint;
import static shufflingway.CardFilters.meetsTargetCondition;
import static shufflingway.CardFilters.parseTraitFromCondition;
import shufflingway.graphics.CardAnimation;
import static shufflingway.graphics.CardAnimation.CARD_H;
import static shufflingway.graphics.CardAnimation.CARD_W;

/**
 * Stateless adapter that lets {@link ActionResolver} reach back into {@link MainWindow}
 * to mutate game state. Extracted from MainWindow.buildGameContext to keep MainWindow
 * smaller (JDT memory pressure on a 17K-line file). Holds a back-pointer to MainWindow;
 * MainWindow members accessed through {@code mw.} are package-private rather than private.
 */
final class GameContextImpl implements GameContext {

	private final MainWindow mw;
	private final boolean isP1;
	private final boolean exBurst;
	private List<ForwardTarget> lastChosenTargets = List.of();
	private List<ForwardTarget> preloadedTargets  = null;
	/** Pending "draw N when it leaves the field for the Break Zone" mark, applied at target selection. */
	private int armedBzDrawMark = 0;
	/**
	 * Damage the selection about to be made will be dealt, or {@code 0} when the effect deals none.
	 * Set by {@link ActionResolver#preSelectTargets} and read only by the AI's auto-selection branch
	 * in {@link #selectCharacters}; an instance field rather than MainWindow state because the hint
	 * is only ever true of the one selection this context is being used for.
	 */
	private int aiDamageTargetHint = 0;
	/**
	 * Budget the selection about to be made must keep its chosen Forwards' printed costs within,
	 * or {@code -1} when the selection is unbounded. Set for the length of one call by
	 * {@link #selectForwardsWithTotalCostAtMost} and read by {@link #selectCharacters} -- an
	 * instance field for the same reason {@link #aiDamageTargetHint} is one: it is only ever true
	 * of the single selection this context is being used for.
	 */
	private int totalCostBudget = -1;
	/**
	 * Targets the selection about to be made must not offer, or empty when it may offer anything.
	 * Set for the length of one call by {@link #selectOppForwardsForTieredDamage} and read by
	 * {@link #selectCharacters}, an instance field for the same reason the two above are.
	 *
	 * <p>Identity is by slot, which is only sound because that method makes every one of its picks
	 * before any damage lands: nothing leaves the field mid-selection, so a recorded index still
	 * names the card it was recorded for.
	 */
	private List<ForwardTarget> excludedTargets = List.of();
	/**
	 * Damage the pick about to be made will be dealt, shown in the selection's title so successive
	 * prompts of a tiered selection can be told apart, or {@code 0} when the title says nothing.
	 */
	private int tieredDamageLabel = 0;
	/** Card the most recent {@link #lookAtTopDeck} put into hand; read by riders on that look. */
	private CardData lastLookAddedToHand = null;

	GameContextImpl(MainWindow mw, boolean isP1, boolean exBurst) {
		this.mw = mw;
		this.isP1 = isP1;
		this.exBurst = exBurst;
	}

	/**
	 * Where a card revealed off the top of a deck goes when an effect plays it: onto the field of
	 * whoever is resolving the effect, in the row its type belongs to.
	 *
	 * <p>Shared by the whole reveal-and-play family, and the sharing is the point. Each of those
	 * effects used to build this lambda inline against P1's zones — {@code placeCardInForwardZone}
	 * and its two siblings are P1-only, the P2 seat has its own trio — so every one of them put
	 * P2's revealed card onto P1's board. Silent in a solo game against the AI right up until the
	 * opponent hands you a free Forward.
	 */
	private Consumer<CardData> revealPlacement() {
		return c -> {
			if (c.isBackup())       { if (isP1) mw.placeCardInFirstBackupSlot(c); else mw.placeP2CardInFirstBackupSlot(c); }
			else if (c.isMonster()) { if (isP1) mw.placeCardInMonsterZone(c);     else mw.placeP2CardInMonsterZone(c); }
			else                    { if (isP1) mw.placeCardInForwardZone(c);     else mw.placeP2CardInForwardZone(c); }
		};
	}

	/** "twice" / "3 times" — how a permitted attack count reads inside a granted ability's text. */
	private static String attackCountPhrase(int maxAttacks) {
		return maxAttacks == 2 ? "twice" : maxAttacks + " times";
	}

	/**
	 * True if a modal sub-action removes cards from a Break Zone that can include the
	 * opponent's (i.e. "either/any player's" or "your opponent's" Break Zone). Used by the
	 * AI to skip such an action when the opponent has nothing there to remove.
	 */
	private static boolean removesFromOpponentBreakZone(String action) {
		String a = action.toLowerCase(java.util.Locale.ROOT);
		if (!a.contains("break zone") || !a.contains("remove")) return false;
		return a.contains("either player") || a.contains("any player") || a.contains("opponent");
	}

	/**
	 * Counts the cards in {@code breakZone} whose printed type is one of the enabled ones and that
	 * pass the optional element and cost-ceiling filters. Backs both
	 * {@link GameContext#countP1BreakZoneCardsByType} and {@link GameContext#countP1BreakZoneMatching}.
	 */
	private static int countBreakZoneByType(List<CardData> breakZone, boolean inclForwards,
			boolean inclBackups, boolean inclMonsters, boolean inclSummons,
			String elementFilter, int maxCost) {
		int count = 0;
		for (CardData c : breakZone) {
			if (c == null) continue;
			boolean typeOk = (inclForwards && c.isForward())
					|| (inclBackups  && c.isBackup())
					|| (inclMonsters && c.isMonster())
					|| (inclSummons  && c.isSummon());
			if (!typeOk) continue;
			if (elementFilter != null && !c.containsElement(elementFilter)) continue;
			if (maxCost != -1 && c.cost() > maxCost) continue;
			count++;
		}
		return count;
	}

			@Override public void logEntry(String msg) { mw.logEntry(msg); }
			@Override public boolean isP1() { return isP1; }

			@Override public void recordChosenTargets(List<ForwardTarget> targets) {
				lastChosenTargets = targets == null ? List.of() : List.copyOf(targets);
			}
			@Override public List<ForwardTarget> lastChosenTargets() { return lastChosenTargets; }

			@Override public void preloadTargets(java.util.List<ForwardTarget> targets) { preloadedTargets = targets; }
			@Override public java.util.List<ForwardTarget> consumePreloadedTargets() {
				java.util.List<ForwardTarget> t = preloadedTargets; preloadedTargets = null; return t;
			}

			@Override public void resetEffectProgress() { mw.effectProgress = true; }
			@Override public void markEffectFizzled()   { mw.effectProgress = false; }
			@Override public boolean effectMadeProgress() { return mw.effectProgress; }

			@Override public int p1ForwardCount()                    { return mw.p1ForwardCards.size(); }
			@Override public boolean fieldCardHasElement(CardData card, String elem) {
				return mw.effectiveContainsElement(card, elem);
			}

			@Override public CardData p1Forward(int idx) {
				CardData top = mw.p1ForwardPrimedTop.get(idx);
				return top != null ? top : mw.p1ForwardCards.get(idx);
			}
			@Override public int       p1ForwardCurrentDamage(int idx) { return mw.p1ForwardDamage.get(idx); }
			@Override public CardState p1ForwardState(int idx)          { return mw.p1ForwardStates.get(idx); }
			@Override public void damageP1Forward(int idx, int amount) {
				int scaled = abilityScaled(amount);
				if (idx < mw.p1ForwardCards.size()) scaled = applyOutgoingFieldAbilityMult(scaled, mw.p1ForwardCards.get(idx));
				mw.applyDamageToForward(true, idx, scaled, true, false);
			}

			@Override public int p2ForwardCount()                    { return mw.p2ForwardCards.size(); }
			@Override public CardData p2Forward(int idx)             { return mw.p2ForwardCards.get(idx); }
			@Override public int       p2ForwardCurrentDamage(int idx) { return mw.p2ForwardDamage.get(idx); }
			@Override public CardState p2ForwardState(int idx)          { return mw.p2ForwardStates.get(idx); }
			@Override public void damageP2Forward(int idx, int amount) {
				int scaled = abilityScaled(amount);
				if (idx < mw.p2ForwardCards.size()) scaled = applyOutgoingFieldAbilityMult(scaled, mw.p2ForwardCards.get(idx));
				mw.applyDamageToForward(false, idx, scaled, true, false);
			}

			@Override public void damageP1ForwardUnreduced(int idx, int amount) {
				int scaled = abilityScaled(amount);
				if (idx < mw.p1ForwardCards.size()) scaled = applyOutgoingFieldAbilityMult(scaled, mw.p1ForwardCards.get(idx));
				mw.applyDamageToForward(true, idx, scaled, true, true);
			}
			@Override public void damageP2ForwardUnreduced(int idx, int amount) {
				int scaled = abilityScaled(amount);
				if (idx < mw.p2ForwardCards.size()) scaled = applyOutgoingFieldAbilityMult(scaled, mw.p2ForwardCards.get(idx));
				mw.applyDamageToForward(false, idx, scaled, true, true);
			}

			private int abilityScaled(int amount) {
				if (mw.currentAbilitySource == null) return amount;
				int mult = mw.outgoingDmgMultiplierMap.getOrDefault(mw.currentAbilitySource, 1);
				if (mw.nextOutgoingDmgDoublerSet.remove(mw.currentAbilitySource)) mult *= 2;
				mult *= (mw.turn(isP1).abilityOutgoingDmgMult);
				int flat = mw.outgoingDmgFlatBoostMap.getOrDefault(mw.currentAbilitySource, 0);
				return amount * mult + flat;
			}

			/**
			 * The source's own outgoing multipliers against {@code target} — Warrior of Light
			 * 1-005R's cost-gated doubler and Sophie 16-076R's ability-damage one.
			 *
			 * <p>Read through {@code MainWindow.effectiveFieldAbilities} and gated on
			 * {@code lostAbilitiesCards} and the printing's own "Damage N --" threshold, exactly
			 * as {@code MainWindow.combatDamageFieldAbilityMult} reads the same two patterns for
			 * combat damage. The two are one rule asked at two moments; reading the printed list
			 * here meant a granted copy doubled nothing and a source that had lost its abilities
			 * doubled anyway.
			 */
			private int applyOutgoingFieldAbilityMult(int amount, CardData target) {
				CardData src = mw.currentAbilitySource;
				if (src == null || mw.lostAbilitiesCards.contains(src)) return amount;
				Boolean side = mw.fieldSideOf(src);
				int dmg = side == null ? 0
						: (side ? mw.gameState.getP1DamageZone() : mw.gameState.getP2DamageZone()).size();
				for (FieldAbility fa : mw.effectiveFieldAbilities(src)) {
					if (fa.damageThreshold() > 0 && dmg < fa.damageThreshold()) continue;
					Matcher m = AutoAbilityTriggers.FA_DOUBLE_DAMAGE_VS_COST_THRESHOLD.matcher(fa.effectText());
					if (m.find() && m.group("name").trim().equalsIgnoreCase(src.name())
							&& target.cost() >= Integer.parseInt(m.group("cost")))
						amount *= 2;
					Matcher m2 = AutoAbilityTriggers.FA_DOUBLE_ABILITY_DAMAGE.matcher(fa.effectText());
					if (m2.find() && m2.group("name").trim().equalsIgnoreCase(src.name()))
						amount *= 2;
				}
				return amount;
			}

			@Override public void doubleOutgoingDamage(CardData source) {
				int cur = mw.outgoingDmgMultiplierMap.getOrDefault(source, 1);
				mw.outgoingDmgMultiplierMap.put(source, cur * 2);
				logEntry(source.name() + " — outgoing damage ×" + (cur * 2) + " until end of turn");
			}

			@Override public void boostForwardOutgoingDamageThisTurn(ForwardTarget t, int amount) {
				CardData card = mw.autoAbilityTriggers.fieldCardData(t);
				if (card == null) return;
				mw.outgoingDmgFlatBoostMap.merge(card, amount, Integer::sum);
				logEntry(card.name() + " — outgoing combat damage +" + amount + " vs Forwards until end of turn");
			}

			@Override public void boostSelfOutgoingDamageThisTurn(CardData source, int amount) {
				mw.outgoingDmgFlatBoostMap.merge(source, amount, Integer::sum);
				logEntry(source.name() + " — outgoing combat damage +" + amount + " vs Forwards until end of turn");
			}

			@Override public void chooseAndRemoveWarpCounter() {
				String p = isP1 ? "" : "[P2] ";
				List<GameState.WarpEntry> zone = isP1
						? mw.gameState.getP1WarpZone() : mw.gameState.getP2WarpZone();
				if (zone.isEmpty()) { logEntry(p + "Warp zone is empty — no target."); return; }
				GameState.WarpEntry chosen;
				if (zone.size() == 1) {
					chosen = zone.get(0);
				} else if (!isP1) {
					chosen = zone.get(0); // P2 AI: pick first
				} else {
					List<CardData> cards = new java.util.ArrayList<>();
					for (GameState.WarpEntry e : zone) cards.add(e.card);
					int idx = mw.showCardImageChooser(cards, "Choose 1 card — Remove Warp Counter", false);
					if (idx < 0) return;
					chosen = zone.get(idx);
				}
				logEntry(p + "Remove Warp Counter from \"" + chosen.card.name()
						+ "\" (" + chosen.counters + " → " + (chosen.counters - 1) + ")");
				// Push warp-resolve first (sits below the trigger on the stack) so the
				// counter-removed auto-ability resolves before the card enters the field.
				boolean willResolve = chosen.counters - 1 <= 0;
				if (willResolve) {
					mw.gameState.pushStack(StackEntry.forWarpResolve(chosen.card, isP1));
				}
				mw.autoAbilityTriggers.triggerAutoAbilitiesForWarpCounterRemoved(chosen.card, isP1);
				mw.gameState.removeOneWarpCounterFrom(chosen.card, isP1);
				if (willResolve) {
					if (isP1) mw.refreshP1BreakLabel(); else mw.refreshP2BreakLabel();
				}
				if (isP1) mw.refreshP1WarpZoneUI(); else mw.refreshP2WarpZoneUI();
			}

			@Override public void chooseAndMayRemoveWarpCounter() {
				String p = isP1 ? "" : "[P2] ";
				List<GameState.WarpEntry> zone = isP1
						? mw.gameState.getP1WarpZone() : mw.gameState.getP2WarpZone();
				if (zone.isEmpty()) { logEntry(p + "Warp zone is empty — no target."); return; }
				GameState.WarpEntry chosen;
				if (zone.size() == 1) {
					chosen = zone.get(0);
				} else if (!isP1) {
					chosen = zone.get(0); // P2 AI: pick first
				} else {
					List<CardData> cards = new java.util.ArrayList<>();
					for (GameState.WarpEntry e : zone) cards.add(e.card);
					int idx = mw.showCardImageChooser(cards, "Choose 1 card — Remove Warp Counter", false);
					if (idx < 0) return;
					chosen = zone.get(idx);
				}
				if (!promptYouMay("Remove 1 Warp Counter from \"" + chosen.card.name() + "\" ("
						+ chosen.counters + " → " + (chosen.counters - 1) + ")?")) {
					logEntry(p + "Declined to remove Warp Counter from \"" + chosen.card.name() + "\"");
					return;
				}
				logEntry(p + "Remove Warp Counter from \"" + chosen.card.name()
						+ "\" (" + chosen.counters + " → " + (chosen.counters - 1) + ")");
				boolean willResolve = chosen.counters - 1 <= 0;
				if (willResolve) {
					mw.gameState.pushStack(StackEntry.forWarpResolve(chosen.card, isP1));
				}
				mw.autoAbilityTriggers.triggerAutoAbilitiesForWarpCounterRemoved(chosen.card, isP1);
				mw.gameState.removeOneWarpCounterFrom(chosen.card, isP1);
				if (willResolve) {
					if (isP1) mw.refreshP1BreakLabel(); else mw.refreshP2BreakLabel();
				}
				if (isP1) mw.refreshP1WarpZoneUI(); else mw.refreshP2WarpZoneUI();
			}

			@Override public int warpCountersOnNamed(String cardName) {
				for (GameState.WarpEntry e : isP1 ? mw.gameState.getP1WarpZone() : mw.gameState.getP2WarpZone())
					if (e.card.name().equalsIgnoreCase(cardName)) return e.counters;
				return 0;
			}

			@Override public void removeWarpCountersFromNamed(String cardName, int count) {
				String p = isP1 ? "" : "[P2] ";
				for (int removed = 0; removed < count; removed++) {
					List<GameState.WarpEntry> zone = isP1
							? mw.gameState.getP1WarpZone() : mw.gameState.getP2WarpZone();
					GameState.WarpEntry entry = null;
					for (GameState.WarpEntry e : zone)
						if (e.card.name().equalsIgnoreCase(cardName)) { entry = e; break; }
					if (entry == null) {
						// Either the card was never in the zone or the previous pass took its last
						// counter and resolved it onto the field. Both mean there is nothing left
						// to remove, so stop rather than logging a miss per remaining iteration.
						if (removed == 0) logEntry(p + "No \"" + cardName + "\" in the Warp zone — nothing to remove");
						return;
					}
					logEntry(p + "Remove Warp Counter from \"" + cardName
							+ "\" (" + entry.counters + " → " + (entry.counters - 1) + ")");
					// Same ordering as chooseAndRemoveWarpCounter: the warp-resolve is pushed
					// first so it sits below the counter-removed trigger and the card enters the
					// field after that trigger has resolved.
					boolean willResolve = entry.counters - 1 <= 0;
					if (willResolve) mw.gameState.pushStack(StackEntry.forWarpResolve(entry.card, isP1));
					mw.autoAbilityTriggers.triggerAutoAbilitiesForWarpCounterRemoved(entry.card, isP1);
					mw.gameState.removeOneWarpCounterFrom(entry.card, isP1);
					if (willResolve) {
						if (isP1) mw.refreshP1BreakLabel(); else mw.refreshP2BreakLabel();
					}
					if (isP1) mw.refreshP1WarpZoneUI(); else mw.refreshP2WarpZoneUI();
				}
			}

			@Override public void doubleOpponentForwardIncomingDamage() {
				if (isP1) {
					mw.p2Turn.forwardIncomingDmgMult *= 2;
					logEntry("Opponent's Forwards — incoming damage ×" + mw.p2Turn.forwardIncomingDmgMult + " until end of turn");
				} else {
					mw.p1Turn.forwardIncomingDmgMult *= 2;
					logEntry("Opponent's Forwards — incoming damage ×" + mw.p1Turn.forwardIncomingDmgMult + " until end of turn");
				}
			}
			@Override public void increaseAllForwardIncomingDamage(int amount) {
				mw.globalForwardIncomingDmgIncrease += amount;
				logEntry("All Forwards — incoming damage +" + amount + " until end of turn (total +" + mw.globalForwardIncomingDmgIncrease + ")");
			}
			@Override public void doubleForwardIncomingDamageThisTurn(ForwardTarget t) {
				CardData card = mw.autoAbilityTriggers.fieldCardData(t);
				if (card == null) return;
				int cur = mw.perCardIncomingDmgMultiplierMap.getOrDefault(card, 1);
				mw.perCardIncomingDmgMultiplierMap.put(card, cur * 2);
				logEntry(card.name() + " — incoming damage ×" + (cur * 2) + " until end of turn");
			}
			@Override public void doubleForwardNextOutgoingDamage(ForwardTarget t) {
				CardData card = mw.autoAbilityTriggers.fieldCardData(t);
				if (card == null) return;
				mw.nextOutgoingDmgDoublerSet.add(card);
				logEntry(card.name() + " — next outgoing damage doubled this turn");
			}
			@Override public void doublePlayerAbilityOutgoingDamage() {
				if (isP1) {
					mw.p1Turn.abilityOutgoingDmgMult *= 2;
					logEntry("P1 abilities — outgoing damage ×" + mw.p1Turn.abilityOutgoingDmgMult + " until end of turn");
				} else {
					mw.p2Turn.abilityOutgoingDmgMult *= 2;
					logEntry("P2 abilities — outgoing damage ×" + mw.p2Turn.abilityOutgoingDmgMult + " until end of turn");
				}
			}
			@Override public boolean wasExtraCostPaid()          { return mw.currentSummonPaidExtraCost; }
			@Override public int extraCostRemovedCardPower()     { return mw.currentExtraCostRemovedCardPower; }
			@Override public int revealedForwardPower()          { return mw.currentRevealedForwardPower; }
			@Override public int extraCostDiscardedCardCost()    { return mw.currentExtraCostDiscardedCardCost; }
			@Override public void damageTargetUnreduced(ForwardTarget t, int amount) {
				if (t.zone() == ForwardTarget.CardZone.BACKUP) { mw.applyDamageToBackup(t.isP1(), t.idx(), amount); return; }
				if (t.zone() == ForwardTarget.CardZone.MONSTER) { mw.applyDamageToMonster(t.isP1(), t.idx(), amount); return; }
				if (t.isP1()) damageP1ForwardUnreduced(t.idx(), amount);
				else          damageP2ForwardUnreduced(t.idx(), amount);
			}

			@Override public void shieldNextIncomingDamage(ForwardTarget t) {
				CardData c = mw.autoAbilityTriggers.fieldCardData(t); if (c != null) mw.nextIncomingDmgZeroSet.add(c);
			}
			@Override public void redirectNextIncomingDamage(ForwardTarget from, ForwardTarget to) {
				CardData cFrom = mw.autoAbilityTriggers.fieldCardData(from);
				CardData cTo   = mw.autoAbilityTriggers.fieldCardData(to);
				if (cFrom != null && cTo != null) mw.nextIncomingDmgRedirectMap.put(cFrom, cTo);
			}
			@Override public void shieldNextIncomingDamageReduction(ForwardTarget t, int reduction) {
				CardData c = mw.autoAbilityTriggers.fieldCardData(t); if (c != null) mw.nextIncomingDmgReduceMap.merge(c, reduction, Integer::sum);
			}
			@Override public void shieldNextIncomingDamageReductionKickback(ForwardTarget t, int reduction,
					CardData bearer, int damage) {
				CardData c = mw.autoAbilityTriggers.fieldCardData(t); if (c == null) return;
				mw.nextIncomingDmgReduceMap.merge(c, reduction, Integer::sum);
				mw.nextIncomingDmgReduceKickbackMap.put(c, new MainWindow.ShieldKickback(isP1, bearer, damage));
			}
			@Override public void shieldNextAbilityIncomingDamageReduction(ForwardTarget t, int reduction) {
				CardData c = mw.autoAbilityTriggers.fieldCardData(t); if (c != null) mw.nextAbilityDmgReduceMap.merge(c, reduction, Integer::sum);
			}
			@Override public void debuffIncomingDamageIncrease(ForwardTarget t, int amount) {
				CardData c = mw.autoAbilityTriggers.fieldCardData(t); if (c != null) mw.incomingDmgIncreaseMap.merge(c, amount, Integer::sum);
			}
			@Override public void shieldAbilityDamage(ForwardTarget t) {
				CardData c = mw.autoAbilityTriggers.fieldCardData(t); if (c != null) mw.nullifyAbilityDmgSet.add(c);
			}
			@Override public void shieldOwnForwardsAbilityDamageFilter(Predicate<CardData> filter) {
				(mw.turn(isP1).nullifyAbilityDmgFilters).add(filter);
			}
			@Override public void activateDoublecastFreeSummons() {
				if (isP1) { mw.p1DoublecastFreeSummons = true; mw.p1DoublecastLastSummonCost = -1; }
				else      { mw.p2DoublecastFreeSummons = true; mw.p2DoublecastLastSummonCost = -1; }
			}
			@Override public void removeTargetFromGameWhileNamedCardOnField(ForwardTarget t, String watcherName) {
				if (t.zone() != ForwardTarget.CardZone.FORWARD) return;
				List<CardData> targetFwds = t.isP1() ? mw.p1ForwardCards : mw.p2ForwardCards;
				if (t.idx() >= targetFwds.size()) return;
				CardData exiled = targetFwds.get(t.idx());
				List<CardData> ownFwds = isP1 ? mw.p1ForwardCards : mw.p2ForwardCards;
				CardData watcher = null;
				for (CardData c : ownFwds) if (c.name().equalsIgnoreCase(watcherName)) { watcher = c; break; }
				if (watcher != null) {
					mw.tempExiledCards.put(exiled, watcher);
					logEntry(exiled.name() + " — removed from the game while " + watcher.name() + " is on the field");
				} else {
					logEntry(watcherName + " is not on the field — " + exiled.name() + " removed permanently");
				}
				removeTargetFromGame(t);
			}

			@Override public void putCardRemovedBySourceIntoBreakZone(CardData source) {
				List<CardData> removed = new ArrayList<>();
				for (Map.Entry<CardData, CardData> e : mw.tempExiledCards.entrySet())
					if (e.getValue() == source) removed.add(e.getKey());
				if (removed.isEmpty()) {
					logEntry("No card removed by " + source.name() + "'s ability — effect fizzles");
					markEffectFizzled();
					return;
				}
				CardData pick;
				if (removed.size() == 1) {
					pick = removed.get(0);
				} else if (isP1) {
					int idx = mw.showCardImageChooser(removed,
							"Choose 1 card removed by " + source.name() + "'s ability", true);
					if (idx < 0) { markEffectFizzled(); return; }
					pick = removed.get(idx);
				} else {
					pick = removed.get(0);
				}
				mw.tempExiledCards.remove(pick);
				mw.gameState.removeFromPermanentRfp(pick);
				mw.addToBreakZone(pick);
				boolean ownerP1 = Boolean.TRUE.equals(mw.gameState.getIdentity().get(pick));
				if (ownerP1) mw.refreshP1BreakLabel(); else mw.refreshP2BreakLabel();
				logEntry(pick.name() + " → Break Zone (no longer returns to the field)");
			}

			@Override public void makeRfgCostCardCastableThisTurn(String cardName) {
				var playable = isP1 ? mw.bzPlayableP1 : mw.bzPlayableP2;
				boolean any = false;
				for (CardData c : mw.lastRfgCostCards) {
					if (!c.name().equalsIgnoreCase(cardName)) continue;
					playable.put(c, new PlayableEntry(PlayableEntry.SourceZone.RFP, 0, false, false, false, true));
					logEntry((isP1 ? "" : "[P2] ") + c.name()
							+ " — castable from Removed From Game until end of turn");
					any = true;
				}
				if (!any) logEntry("No " + cardName + " was removed by this ability's cost — nothing to register");
				mw.refreshPlayableCardsButton();
			}
			@Override public void shieldAbilityOnlyDamage(ForwardTarget t) {
				CardData c = mw.autoAbilityTriggers.fieldCardData(t); if (c != null) mw.nullifyAbilityOnlyDmgSet.add(c);
			}
			@Override public void shieldNonLethal(ForwardTarget t) {
				CardData c = mw.autoAbilityTriggers.fieldCardData(t); if (c != null) mw.perCardNonLethalDmgSet.add(c);
			}
			@Override public void shieldPlayerNextDamage() {
				if (isP1) { mw.p1Turn.nextDamageZero = true; if (mw.p1ShieldIcon != null) mw.p1ShieldIcon.reset(); }
				else       { mw.p2Turn.nextDamageZero = true; if (mw.p2ShieldIcon != null) mw.p2ShieldIcon.reset(); }
			}
			@Override public void shieldPlayerNextDamageRedirect(String cardName, int damage) {
				shieldPlayerNextDamage();
				if (isP1) { mw.p1Turn.nextDamageZeroRedirectName = cardName; mw.p1Turn.nextDamageZeroRedirectDmg = damage; }
				else      { mw.p2Turn.nextDamageZeroRedirectName = cardName; mw.p2Turn.nextDamageZeroRedirectDmg = damage; }
			}
			@Override public void disableOpponentDamageReduction() {
				if (isP1) mw.p2Turn.dmgReductionDisabled = true; else mw.p1Turn.dmgReductionDisabled = true;
			}
			@Override public void shieldNextOutgoingDamage(ForwardTarget t) {
				CardData c = mw.autoAbilityTriggers.fieldCardData(t); if (c != null) mw.nextOutgoingDmgZeroSet.add(c);
			}
			@Override public void shieldActivePlayerNonLethal() {
				if (isP1) mw.p1Turn.nonLethalProtection = true; else mw.p2Turn.nonLethalProtection = true;
			}
			@Override public void shieldActivePlayerDamageReduction(int reduction) {
				if (isP1) mw.p1Turn.globalDmgReduction += reduction; else mw.p2Turn.globalDmgReduction += reduction;
			}

			@Override public void negateAllDamage(ForwardTarget t) {
				if (t.zone() != ForwardTarget.CardZone.FORWARD) return;
				if (t.isP1()) {
					int idx = t.idx();
					if (idx < 0 || idx >= mw.p1ForwardCards.size() || mw.p1ForwardDamage.get(idx) == 0) return;
					logEntry(p1Forward(idx).name() + " — all damage negated");
					mw.p1ForwardDamage.set(idx, 0);
					mw.refreshP1ForwardSlot(idx);
				} else {
					int idx = t.idx();
					if (idx < 0 || idx >= mw.p2ForwardCards.size() || mw.p2ForwardDamage.get(idx) == 0) return;
					logEntry("[P2] " + mw.p2ForwardCards.get(idx).name() + " — all damage negated");
					mw.p2ForwardDamage.set(idx, 0);
					mw.refreshP2ForwardSlot(idx);
				}
			}

			@Override public void negateAllDamageOwnForwards() {
				List<CardData> fwds = isP1 ? mw.p1ForwardCards : mw.p2ForwardCards;
				List<Integer>  dmg  = isP1 ? mw.p1ForwardDamage : mw.p2ForwardDamage;
				for (int i = 0; i < fwds.size(); i++) {
					if (dmg.get(i) == 0) continue;
					logEntry((isP1 ? "" : "[P2] ") + fwds.get(i).name() + " — all damage negated");
					dmg.set(i, 0);
					if (isP1) mw.refreshP1ForwardSlot(i); else mw.refreshP2ForwardSlot(i);
				}
			}

			@Override public void shieldCannotBeChosen(ForwardTarget t, boolean bySummons, boolean byAbilities) {
				CardData c = mw.autoAbilityTriggers.fieldCardData(t);
				if (c == null) return;
				if (bySummons)   mw.cannotBeChosenBySummons.add(c);
				if (byAbilities) mw.cannotBeChosenByAbilities.add(c);
			}

			@Override public void shieldCannotBeBroken(ForwardTarget t) {
				if (t.zone() != ForwardTarget.CardZone.FORWARD) return;
				CardData c = mw.autoAbilityTriggers.fieldCardData(t);
				if (c == null) return;
				EnumSet<CardData.Trait> tempTraits = t.isP1()
						? mw.p1ForwardTempTraits.get(t.idx())
						: mw.p2ForwardTempTraits.get(t.idx());
				tempTraits.add(CardData.Trait.CANNOT_BE_BROKEN);
				logEntry((t.isP1() ? "" : "[P2] ") + c.name() + " cannot be broken until end of turn");
			}

			@Override public void shieldCannotBeBrokenByNonDmg(ForwardTarget t) {
				if (t.zone() != ForwardTarget.CardZone.FORWARD) return;
				CardData c = mw.autoAbilityTriggers.fieldCardData(t);
				if (c == null) return;
				EnumSet<CardData.Trait> tempTraits = t.isP1()
						? mw.p1ForwardTempTraits.get(t.idx())
						: mw.p2ForwardTempTraits.get(t.idx());
				tempTraits.add(CardData.Trait.CANNOT_BE_BROKEN_BY_NON_DMG);
				logEntry((t.isP1() ? "" : "[P2] ") + c.name() + " cannot be broken by opposing non-damage Summons or abilities until end of turn");
			}

			@Override public void markTargetRfgInsteadOfBzThisTurn(ForwardTarget t) {
				if (t.zone() != ForwardTarget.CardZone.FORWARD) return;
				CardData c = mw.autoAbilityTriggers.fieldCardData(t);
				if (c == null) return;
				mw.rfgInsteadOfBzThisTurn.add(c);
				logEntry((t.isP1() ? "" : "[P2] ") + c.name()
						+ " — if put from the field into the Break Zone this turn, removed from the game instead");
			}

			@Override public void armDrawOnFieldToBzMark(int count) { armedBzDrawMark = count; }

			@Override public int consumeDrawOnFieldToBzMark() {
				int c = armedBzDrawMark; armedBzDrawMark = 0; return c;
			}

			@Override public void markTargetDrawOnFieldToBzThisTurn(ForwardTarget t, int count) {
				if (t.zone() != ForwardTarget.CardZone.FORWARD) return;
				CardData c = mw.autoAbilityTriggers.fieldCardData(t);
				if (c == null) return;
				// isP1, not t.isP1(): the draw belongs to whoever resolved the ability, while the
				// marked Forward is usually the opponent's.
				mw.drawOnFieldToBzThisTurn
						.computeIfAbsent(c, k -> new ArrayList<>())
						.add(new MainWindow.PendingBzDraw(isP1, count));
				logEntry((t.isP1() ? "" : "[P2] ") + c.name()
						+ " — when put from the field into the Break Zone this turn, "
						+ (isP1 ? "P1" : "P2") + " draws " + count);
			}

			@Override public void markTargetPutSourceToBzOnLeaveThisTurn(ForwardTarget t, CardData source) {
				if (t.zone() != ForwardTarget.CardZone.FORWARD || source == null) return;
				CardData borrower = mw.autoAbilityTriggers.fieldCardData(t);
				if (borrower == null) return;
				mw.putIntoBzWhenLeavesFieldThisTurn
						.computeIfAbsent(borrower, k -> new ArrayList<>())
						.add(source);
				logEntry((t.isP1() ? "" : "[P2] ") + borrower.name()
						+ " — when it leaves the field this turn, " + source.name() + " → Break Zone");
			}

			@Override public void dullSourceForward(CardData source) {
				List<CardData> fwds = isP1 ? mw.p1ForwardCards : mw.p2ForwardCards;
				for (int i = 0; i < fwds.size(); i++) {
					if (fwds.get(i).name().equalsIgnoreCase(source.name())) {
						dullTarget(new ForwardTarget(isP1, i, ForwardTarget.CardZone.FORWARD));
						return;
					}
				}
			}

			@Override public void shieldSourceForward(CardData source) {
				List<CardData> fwds = isP1 ? mw.p1ForwardCards : mw.p2ForwardCards;
				List<EnumSet<CardData.Trait>> tempList =
						isP1 ? mw.p1ForwardTempTraits : mw.p2ForwardTempTraits;
				for (int i = 0; i < fwds.size(); i++) {
					if (fwds.get(i).name().equalsIgnoreCase(source.name())) {
						tempList.get(i).add(CardData.Trait.CANNOT_BE_BROKEN);
						logEntry((isP1 ? "" : "[P2] ") + fwds.get(i).name() + " cannot be broken until end of turn");
						return;
					}
				}
			}

			@Override public void shieldAllOwnForwards() {
				List<CardData> fwds = isP1 ? mw.p1ForwardCards : mw.p2ForwardCards;
				List<EnumSet<CardData.Trait>> tempList =
						isP1 ? mw.p1ForwardTempTraits : mw.p2ForwardTempTraits;
				for (int i = 0; i < fwds.size(); i++) {
					tempList.get(i).add(CardData.Trait.CANNOT_BE_BROKEN);
					logEntry((isP1 ? "" : "[P2] ") + fwds.get(i).name() + " cannot be broken until end of turn");
				}
			}

			@Override public void shieldBreaktouchBattle(ForwardTarget t) {
				CardData c = mw.autoAbilityTriggers.fieldCardData(t);
				if (c == null) return;
				mw.breaktouchBattleSet.add(c);
				logEntry((t.isP1() ? "" : "[P2] ") + c.name() + " — Breaktouch (battle damage) until end of turn");
			}

			@Override public void gainTargetActionAbilitiesUntilEndOfTurn(CardData source, ForwardTarget target) {
				CardData donor = mw.autoAbilityTriggers.fieldCardData(target);
				if (source == null || donor == null) return;
				List<ActionAbility> gained = new ArrayList<>();
				for (ActionAbility aa : donor.actionAbilities()) {
					if (aa.isSpecial() || aa.breakZoneOnly() != null || aa.whileCardInHand()) continue;
					gained.add(aa.withEffectText(ActionResolver.substituteSourceName(
							aa.effectText(), donor.name(), source.name())));
				}
				if (gained.isEmpty()) {
					logEntry(donor.name() + " has no action abilities for " + source.name() + " to gain");
					return;
				}
				// The borrower's own side, not the donor's: the abilities are used by source, and
				// this is the map addAbilityMenuItems reads when source's menu is built.
				Map<CardData, List<ActionAbility>> map = isP1 ? mw.p1TempGrantedAbilities : mw.p2TempGrantedAbilities;
				map.computeIfAbsent(source, k -> new ArrayList<>()).addAll(gained);
				// Dropped at end of turn with the rest of the temp-grant map
				// (MainWindow.clearBackupForwardState), which is what "until the end of the turn"
				// means here; an explicit end-of-turn effect would double up with it.
				for (ActionAbility aa : gained)
					logEntry(source.name() + " gains until end of turn: " + aa.effectText());
			}

			@Override public void grantBreakWhenDealtDamage(ForwardTarget t) {
				CardData c = mw.autoAbilityTriggers.fieldCardData(t);
				if (c == null) return;
				mw.breakWhenDealtDamageSet.add(c);
				logEntry((t.isP1() ? "" : "[P2] ") + c.name()
						+ " gains \"When this Forward is dealt damage, break this Forward.\" until end of turn");
			}

			@Override public void shieldAllOwnForwardsCannotBeChosen(boolean bySummons, boolean byAbilities) {
				List<CardData> fwds = isP1 ? mw.p1ForwardCards : mw.p2ForwardCards;
				for (CardData c : fwds) {
					if (bySummons)   mw.cannotBeChosenBySummons.add(c);
					if (byAbilities) mw.cannotBeChosenByAbilities.add(c);
				}
				logEntry("Effect: all own Forwards cannot be chosen by opponent's" +
						(bySummons && byAbilities ? " Summons or abilities" : bySummons ? " Summons" : " abilities"));
			}

			@Override public void shieldNamedCardCannotBeChosen(String name, boolean bySummons, boolean byAbilities) {
				List<CardData> fwds = isP1 ? mw.p1ForwardCards : mw.p2ForwardCards;
				for (CardData c : fwds) {
					if (!c.name().equalsIgnoreCase(name)) continue;
					if (bySummons)   mw.cannotBeChosenBySummons.add(c);
					if (byAbilities) mw.cannotBeChosenByAbilities.add(c);
				}
			}

			@Override public void shieldNamedCardCannotBeChosenByAnySummon(String name) {
				List<CardData> fwds = isP1 ? mw.p1ForwardCards : mw.p2ForwardCards;
				for (CardData c : fwds) {
					if (c.name().equalsIgnoreCase(name)) mw.cannotBeChosenBySummonsAnyone.add(c);
				}
				logEntry("Effect: " + name + " cannot be chosen by any Summon this turn");
			}

			@Override public void shieldNamedCardCannotBeChosenByElement(String cardName, String element) {
				List<CardData> fwds = isP1 ? mw.p1ForwardCards : mw.p2ForwardCards;
				for (CardData c : fwds) {
					if (c.name().equalsIgnoreCase(cardName)) {
						mw.cannotBeChosenByElement.put(c, element);
						return;
					}
				}
				logEntry("shieldByElement: " + cardName + " not found on field");
			}

			@Override public void nullifyNamedCardDamageByElement(String cardName, String element) {
				List<CardData> fwds = isP1 ? mw.p1ForwardCards : mw.p2ForwardCards;
				for (CardData c : fwds) {
					if (c.name().equalsIgnoreCase(cardName)) {
						mw.nullifyElementDamageMap.put(c, element);
						return;
					}
				}
				logEntry("nullifyDamageByElement: " + cardName + " not found on field");
			}

			@Override public void nullifyNamedCardDamageByElementAbilityOnly(String cardName, String element) {
				List<CardData> fwds = isP1 ? mw.p1ForwardCards : mw.p2ForwardCards;
				for (CardData c : fwds) {
					if (c.name().equalsIgnoreCase(cardName)) {
						mw.nullifyElementDamageAbilityOnlyMap.put(c, element);
						return;
					}
				}
				logEntry("nullifyDamageByElementAbilityOnly: " + cardName + " not found on field");
			}

			@Override public void setCardElement(String cardName, String element) {
				for (boolean p1s : new boolean[]{true, false}) {
					List<CardData> fwds = p1s ? mw.p1ForwardCards : mw.p2ForwardCards;
					for (CardData c : fwds) {
						if (c.name().equalsIgnoreCase(cardName)) {
							mw.elementOverrideMap.put(c, element);
							logEntry("[Field] " + cardName + " → element becomes " + element);
							return;
						}
					}
					CardData[] bkps = p1s ? mw.p1BackupCards : mw.p2BackupCards;
					for (CardData c : bkps) {
						if (c != null && c.name().equalsIgnoreCase(cardName)) {
							mw.elementOverrideMap.put(c, element);
							logEntry("[Field] " + cardName + " → element becomes " + element);
							return;
						}
					}
				}
				logEntry("[Field] setCardElement: " + cardName + " not found");
			}

			@Override public void setTargetElement(ForwardTarget t, String element) {
				CardData card = targetCard(t);
				if (card == null) {
					logEntry("[Field] setTargetElement: no card at " + t);
					return;
				}
				mw.elementOverrideMap.put(card, element);
				logEntry("[Field] " + card.name() + " → element becomes " + element);
				// The element is on the card face and gates who may block whom, so both boards
				// have to redraw — the same refresh setCardElement's callers do by hand.
				mw.refreshAllForwardSlots();
				for (int i = 0; i < mw.p2ForwardCards.size(); i++) mw.refreshP2ForwardSlot(i);
			}

			@Override public String selectElement(String prompt) {
				return selectElement(prompt, Set.of());
			}

			@Override public String selectElement(String prompt, Set<String> excluded) {
				return firstNamed(askToName("Waiting for your opponent to name an Element...",
						interactive -> NamedThing.of(NamedThing.Vocabulary.ELEMENT,
								NameSelectionDialogs.selectElement(mw.frame, prompt, excluded,
										interactive, mw::logEntry))));
			}

			@Override public String selectOption(String prompt, String[] choices) {
				List<Integer> answer = mw.decide(PlayerChoice.by(isP1, ChoiceKind.OPTION)
						.prompting("Waiting for your opponent to choose...")
						.locally(() -> {
							// One button per option rather than a dropdown: these choice lists are
							// short (a pair of traits), and a button apiece is one click not three.
							int idx = mw.showEffectOptionDialog(prompt, "Choose", (Object[]) choices);
							return idx >= 0 && idx < choices.length ? List.of(idx) : List.of();
						})
						.byCpu(() -> List.of((int) (Math.random() * choices.length)))
						.legalWhen(a -> a.stream().allMatch(i -> i >= 0 && i < choices.length),
								"this ability offers " + choices.length + " options here"));
				return answer.isEmpty() ? null : choices[answer.get(0)];
			}

			/**
			 * Asks the seat this effect belongs to to name something, and returns what they named.
			 *
			 * <p>{@code ask} is handed the old {@code interactive} flag, but it no longer means
			 * "am I P1" — {@link MainWindow#decide} calls it with {@code true} to put the dialog in
			 * front of the local human and with {@code false} to run the AI's heuristic, and calls
			 * it not at all when a remote player is the one naming. That is the whole fix: the AI's
			 * random pick used to stand in for <em>every</em> seat that was not this one, so two
			 * clients resolving one ability named two different Jobs and neither ever found out.
			 */
			private List<NamedThing> askToName(String waitPrompt,
					Function<Boolean, List<NamedThing>> ask) {
				List<Integer> answer = mw.decide(PlayerChoice.by(isP1, ChoiceKind.NAMED)
						.prompting(waitPrompt)
						.locally(() -> NamedThing.toAnswer(ask.apply(true),  mw::logEntry))
						.byCpu(()   -> NamedThing.toAnswer(ask.apply(false), mw::logEntry))
						.legalWhen(a -> NamedThing.fromAnswer(a, mw::logEntry) != null,
								"they named something this client has never heard of"));
				List<NamedThing> named = NamedThing.fromAnswer(answer, mw::logEntry);
				return named == null ? List.of() : named;
			}

			/** The first thing named, or null — for the abilities that ask for exactly one. */
			private String firstNamed(List<NamedThing> named) {
				return named.isEmpty() ? null : named.get(0).value();
			}

			/**
			 * Takes {@code count} cards at random out of a pool of {@code poolSize}, and returns
			 * where each one was — rolled once, on the controller's client, and sent.
			 *
			 * <p>Each index is a position in the pool <em>as it stood for that pick</em>, so the
			 * caller must remove them in the order given: the pool is one smaller each time.
			 *
			 * <p>An empty list means either an empty pool or a roll this client rejected, and both
			 * come to the same thing — nothing is taken. Falling back to a local roll would be the
			 * one response guaranteed to desync, which is what this replaced.
			 */
			private List<Integer> randomPicks(int count, int poolSize, String what) {
				int rolls = Math.min(count, poolSize);
				if (rolls <= 0) return List.of();
				List<Integer> answer = mw.decide(PlayerChoice.by(isP1, ChoiceKind.RANDOM)
						.prompting("Waiting for your opponent to determine " + what + "...")
						// Nobody is asked anything: whichever client holds the controller's seat
						// rolls, and the other applies what it is told.
						.locally(() -> RandomPicks.roll(rolls, poolSize))
						.byCpu(()   -> RandomPicks.roll(rolls, poolSize))
						.legalWhen(a -> a.size() == rolls && RandomPicks.fitPool(a, poolSize),
								"there are " + poolSize + " card(s) to pick from here"));
				return answer.size() == rolls ? answer : List.of();
			}

			@Override public void shieldJobForwardsCannotBeChosen(String job, String excludeName,
					boolean bySummons, boolean byAbilities) {
				List<CardData> fwds = isP1 ? mw.p1ForwardCards : mw.p2ForwardCards;
				for (CardData c : fwds) {
					if (!mw.meetsJobFilterEffective(c, job)) continue;
					if (excludeName != null && c.name().equalsIgnoreCase(excludeName)) continue;
					if (bySummons)   mw.cannotBeChosenBySummons.add(c);
					if (byAbilities) mw.cannotBeChosenByAbilities.add(c);
				}
			}

			@Override public void gainControlOfForward(ForwardTarget t, String condition, boolean activate) {
				// Only a Forward the other player controls can be taken.
				if (t == null || t.isP1() == isP1 || t.zone() != ForwardTarget.CardZone.FORWARD) return;
				mw.stealForwardControl(isP1, t.idx(), condition, activate);
			}

			@Override public void giveSourceControlToOpponent(CardData source) {
				mw.giveForwardControlToOpponent(source);
			}

			/** Current effective Forward power of the Character at {@code t}, whichever row it stands in. */
			private int fieldPowerAt(ForwardTarget t) {
				return mw.fieldForwardPower(t.isP1(), t.zone(), t.idx());
			}

			/** {@link MainWindow#fieldForwardBreakableBy} for a whole target. */
			private boolean wouldBreakUnderDamage(ForwardTarget t, int damage) {
				return mw.fieldForwardBreakableBy(t.isP1(), t.zone(), t.idx(), damage);
			}

			/**
			 * Every target a {@link #selectCharacters} call with these filters could offer: the
			 * whole of that method bar the prompt that picks from the list.
			 *
			 * <p>Split out so a cast-legality check can ask what a Summon would be able to choose
			 * without asking anybody to choose it. The rule that needs the answer — a Summon whose
			 * text opens with a mandatory choice cannot be cast while nothing answers it — has to
			 * agree with what the prompt would really offer, and one method building the list is
			 * what makes that agreement structural rather than a second reading of the card text
			 * that can drift from this one.
			 */
			List<ForwardTarget> eligibleCharacters(
					int maxCount, boolean upTo, boolean opponentOnly,
					boolean selfOnly, String condition, String element,
					int costVal, String costCmp, int powerVal, String powerCmp,
					boolean inclForwards, boolean inclBackups, boolean inclMonsters,
					String jobFilter, String cardNameFilter, String categoryFilter, String excludeName, boolean inclSummons,
					String excludeElement, boolean withoutMulticard) {
				List<ForwardTarget> eligible = new ArrayList<>();
				// Build the "cannot be chosen" sets — checked in all four targeting quadrants.
				// They come in two scopes, and the card text is what decides which:
				//   *ImmuneAnyone — "cannot be chosen by Summons" with no qualifier. Blocks whoever
				//       is choosing, the card's own controller included.
				//   *ImmuneFromOpp — "cannot be chosen by YOUR OPPONENT'S Summons or abilities".
				//       Blocks only effects controlled by the target's opponent; its controller may
				//       still choose it, which is the whole point of e.g. buffing your own shielded
				//       Forward with your own ability.
				// Sources: turn-scoped shields (action abilities), standalone field abilities,
				// conditional IfControlBoost grants, and element-based immunity.
				CardData resCard = mw.currentResolutionIsSummon ? mw.currentSummonSource : mw.currentAbilitySource;
				List<String> resElems = (resCard != null) ? mw.effectiveElements(resCard) : List.of();
				final Set<CardData> summonImmuneAnyone;
				final Set<CardData> abilityImmuneAnyone;
				final Set<CardData> summonImmuneFromOpp;
				final Set<CardData> abilityImmuneFromOpp;
				{
					Set<CardData> sumTmp = new HashSet<>(mw.cannotBeChosenBySummonsAnyone);
					Set<CardData> ablTmp = new HashSet<>();
					// The turn-scoped shields are all printed "by your opponent's ...", so they seed
					// the opponent-scoped sets rather than the symmetric ones.
					Set<CardData> sumOpp = new HashSet<>(mw.cannotBeChosenBySummons);
					Set<CardData> ablOpp = new HashSet<>(mw.cannotBeChosenByAbilities);
					// Rubicante-style: "cannot be chosen by [element] Summons/abilities this turn"
					for (java.util.Map.Entry<CardData, String> e : mw.cannotBeChosenByElement.entrySet()) {
						if (resElems.contains(e.getValue())) { sumTmp.add(e.getKey()); ablTmp.add(e.getKey()); }
					}
					for (boolean p1side : new boolean[]{true, false}) {
						List<CardData> zone = new ArrayList<>(p1side ? mw.p1ForwardCards : mw.p2ForwardCards);
						for (CardData b : p1side ? mw.p1BackupCards : mw.p2BackupCards) if (b != null) zone.add(b);
						zone.addAll(p1side ? mw.p1MonsterCards : mw.p2MonsterCards);
						for (CardData c : zone) {
							if (ActionResolver.hasCannotBeChosenByAnySummonFieldAbility(c)) sumTmp.add(c);
							// The opponent-scoped printing (Terra 1-046H, Seiryu 16-049R). Seeds the
							// opponent-scoped sets, so the card's own controller can still choose it.
							if (ActionResolver.hasCannotBeChosenByOppFieldAbility(c, true))  sumOpp.add(c);
							if (ActionResolver.hasCannotBeChosenByOppFieldAbility(c, false)) ablOpp.add(c);
							// Royal Ripeness 5-007H: printed immunity to one named Element, both
							// halves of it — its Summons and its abilities alike.
							String pe = ActionResolver.cannotBeChosenByElementFieldAbility(c);
							if (pe != null && resElems.contains(pe)) { sumTmp.add(c); ablTmp.add(c); }
							if (ActionResolver.hasCannotBeChosenByOwnElementFieldAbility(c)) {
								String ce = mw.effectiveElement(c);
								if (ce != null && resElems.contains(ce)) { sumTmp.add(c); ablTmp.add(c); }
							}
							// Ability-only by construction: the source test rejects a Summon outright,
							// so this seeds the ability set alone.
							if (ActionResolver.hasCannotBeChosenByMultiElementForwardAbility(c)
									&& mw.isMultiElementForwardAbilitySource(resCard, mw.currentResolutionIsSummon))
								ablTmp.add(c);
							if (mw.icbGrantsImmunity(c.name(), p1side, true,  false, resCard)) sumTmp.add(c);
							if (mw.icbGrantsImmunity(c.name(), p1side, false, false, resCard)) ablTmp.add(c);
							if (mw.icbGrantsImmunity(c.name(), p1side, true,  true,  resCard)) sumOpp.add(c);
							if (mw.icbGrantsImmunity(c.name(), p1side, false, true,  resCard)) ablOpp.add(c);
						}
					}
					summonImmuneAnyone   = sumTmp;
					abilityImmuneAnyone  = ablTmp;
					summonImmuneFromOpp  = sumOpp;
					abilityImmuneFromOpp = ablOpp;
				}
				// Two sets for this resolution, one per side of the field relative to the effect's
				// controller. Each targeting quadrant iterates a known side, so it consults exactly
				// one of them and needs no further scope test.
				final Set<CardData> immuneOwn = mw.currentResolutionIsSummon ? summonImmuneAnyone : abilityImmuneAnyone;
				final Set<CardData> immuneOpp = new HashSet<>(immuneOwn);
				immuneOpp.addAll(mw.currentResolutionIsSummon ? summonImmuneFromOpp : abilityImmuneFromOpp);
				// "own" = cards belonging to effect controller; "opp" = other player's cards.
				// isP1 captures the controller's perspective, so the two blocks below must
				// flip which physical side they iterate when isP1 is false (P2 controls).
				if (!opponentOnly) {
					if (isP1) {
						if (inclForwards || inclMonsters) for (int i = 0; i < mw.p1ForwardCards.size(); i++) {
							CardData card = p1Forward(i);
							if (!inclForwards && !card.alsoCountsAsMonster()) continue;
							if (immuneOwn.contains(card)) continue;
							if (element != null && !mw.effectiveContainsElement(card, element)) continue;
							if (!meetsElementExclusion(card, excludeElement)) continue;
							if (!meetsCostConstraint(card.cost(), costVal, costCmp)) continue;
							if (!meetsPowerConstraint(card.power(), powerVal, powerCmp)) continue;
							if (!mw.meetsJobOrCardNameFilter(card, jobFilter, cardNameFilter, mw.p1ForwardCards)) continue;
							if (!meetsCategoryFilter(card, categoryFilter)) continue;
							if (excludeName != null && excludeName.equalsIgnoreCase(card.name())) continue;
							if (withoutMulticard && card.multicard()) continue;
							if (isTraitCondition(condition) && !mw.effectiveP1HasTrait(i, parseTraitFromCondition(condition))) continue;
							if (isBlockingTargetFilter(condition)
									? mw.meetsBlockingTargetFilter(true, i, condition)
									: isEnteredThisTurnCondition(condition)
									? mw.p1ForwardPlayedOnTurn.get(i) == mw.gameState.getTurnNumber()
									: meetsTargetCondition(mw.p1ForwardStates.get(i), mw.p1ForwardDamage.get(i),
											mw.p1AttackSelection.contains(i), false, condition))
								eligible.add(new ForwardTarget(true, i, ForwardTarget.CardZone.FORWARD));
						}
						if (inclBackups || inclForwards) for (int i = 0; i < mw.p1BackupCards.length; i++) {
							if (isBlockingTargetFilter(condition)) continue;
							if (mw.p1BackupCards[i] == null) continue;
							if (!inclBackups && !mw.isP1BackupTemporarilyForward(i)) continue;
							if (immuneOwn.contains(mw.p1BackupCards[i])) continue;
							if (element != null && !mw.effectiveContainsElement(mw.p1BackupCards[i], element)) continue;
							if (!meetsCostConstraint(mw.p1BackupCards[i].cost(), costVal, costCmp)) continue;
							if (!meetsPowerConstraint(mw.p1BackupCards[i].power(), powerVal, powerCmp)) continue;
							if (!mw.meetsJobOrCardNameFilter(mw.p1BackupCards[i], jobFilter, cardNameFilter, mw.p1ForwardCards)) continue;
							if (!meetsCategoryFilter(mw.p1BackupCards[i], categoryFilter)) continue;
							if (excludeName != null && excludeName.equalsIgnoreCase(mw.p1BackupCards[i].name())) continue;
							if (withoutMulticard && mw.p1BackupCards[i].multicard()) continue;
							if (meetsTargetCondition(mw.p1BackupStates[i], 0, false, false, condition))
								eligible.add(new ForwardTarget(true, i, ForwardTarget.CardZone.BACKUP));
						}
						if (inclMonsters || inclForwards) for (int i = 0; i < mw.p1MonsterCards.size(); i++) {
							if (!inclMonsters && !mw.isP1MonsterTemporarilyForward(i)) continue;
							CardData card = mw.p1MonsterCards.get(i);
							if (immuneOwn.contains(card)) continue;
							if (element != null && !mw.effectiveContainsElement(card, element)) continue;
							if (!meetsElementExclusion(card, excludeElement)) continue;
							if (!meetsCostConstraint(card.cost(), costVal, costCmp)) continue;
							if (!meetsPowerConstraint(card.power(), powerVal, powerCmp)) continue;
							if (!mw.meetsJobOrCardNameFilter(card, jobFilter, cardNameFilter, mw.p1ForwardCards)) continue;
							if (!meetsCategoryFilter(card, categoryFilter)) continue;
							if (excludeName != null && excludeName.equalsIgnoreCase(card.name())) continue;
							if (withoutMulticard && card.multicard()) continue;
							if (isEnteredThisTurnCondition(condition)
									? mw.p1MonsterPlayedOnTurn.get(i) == mw.gameState.getTurnNumber()
									: meetsTargetCondition(mw.p1MonsterStates.get(i), 0, false, false, condition))
								eligible.add(new ForwardTarget(true, i, ForwardTarget.CardZone.MONSTER));
						}
					} else {
						if (inclForwards || inclMonsters) for (int i = 0; i < mw.p2ForwardCards.size(); i++) {
							CardData card = mw.p2ForwardCards.get(i);
							if (!inclForwards && !card.alsoCountsAsMonster()) continue;
							if (immuneOwn.contains(card)) continue;
							if (element != null && !mw.effectiveContainsElement(card, element)) continue;
							if (!meetsElementExclusion(card, excludeElement)) continue;
							if (!meetsCostConstraint(card.cost(), costVal, costCmp)) continue;
							if (!meetsPowerConstraint(card.power(), powerVal, powerCmp)) continue;
							if (!mw.meetsJobOrCardNameFilter(card, jobFilter, cardNameFilter, mw.p2ForwardCards)) continue;
							if (!meetsCategoryFilter(card, categoryFilter)) continue;
							if (excludeName != null && excludeName.equalsIgnoreCase(card.name())) continue;
							if (withoutMulticard && card.multicard()) continue;
							if (isTraitCondition(condition) && !mw.effectiveP2HasTrait(i, parseTraitFromCondition(condition))) continue;
							if (isBlockingTargetFilter(condition)
									? mw.meetsBlockingTargetFilter(false, i, condition)
									: isEnteredThisTurnCondition(condition)
									? mw.p2ForwardPlayedOnTurn.get(i) == mw.gameState.getTurnNumber()
									: meetsTargetCondition(mw.p2ForwardStates.get(i), mw.p2ForwardDamage.get(i),
											false, false, condition))
								eligible.add(new ForwardTarget(false, i, ForwardTarget.CardZone.FORWARD));
						}
						if (inclBackups || inclForwards) for (int i = 0; i < mw.p2BackupCards.length; i++) {
							if (isBlockingTargetFilter(condition)) continue;
							if (mw.p2BackupCards[i] == null) continue;
							if (!inclBackups && !mw.isP2BackupTemporarilyForward(i)) continue;
							if (immuneOwn.contains(mw.p2BackupCards[i])) continue;
							if (element != null && !mw.effectiveContainsElement(mw.p2BackupCards[i], element)) continue;
							if (!meetsCostConstraint(mw.p2BackupCards[i].cost(), costVal, costCmp)) continue;
							if (!meetsPowerConstraint(mw.p2BackupCards[i].power(), powerVal, powerCmp)) continue;
							if (!mw.meetsJobOrCardNameFilter(mw.p2BackupCards[i], jobFilter, cardNameFilter, mw.p2ForwardCards)) continue;
							if (!meetsCategoryFilter(mw.p2BackupCards[i], categoryFilter)) continue;
							if (excludeName != null && excludeName.equalsIgnoreCase(mw.p2BackupCards[i].name())) continue;
							if (withoutMulticard && mw.p2BackupCards[i].multicard()) continue;
							if (meetsTargetCondition(mw.p2BackupStates[i], 0, false, false, condition))
								eligible.add(new ForwardTarget(false, i, ForwardTarget.CardZone.BACKUP));
						}
						if (inclMonsters || inclForwards) for (int i = 0; i < mw.p2MonsterCards.size(); i++) {
							if (!inclMonsters && !mw.isP2MonsterTemporarilyForward(i)) continue;
							CardData card = mw.p2MonsterCards.get(i);
							if (immuneOwn.contains(card)) continue;
							if (element != null && !mw.effectiveContainsElement(card, element)) continue;
							if (!meetsElementExclusion(card, excludeElement)) continue;
							if (!meetsCostConstraint(card.cost(), costVal, costCmp)) continue;
							if (!meetsPowerConstraint(card.power(), powerVal, powerCmp)) continue;
							if (!mw.meetsJobOrCardNameFilter(card, jobFilter, cardNameFilter, mw.p2ForwardCards)) continue;
							if (!meetsCategoryFilter(card, categoryFilter)) continue;
							if (excludeName != null && excludeName.equalsIgnoreCase(card.name())) continue;
							if (withoutMulticard && card.multicard()) continue;
							if (isEnteredThisTurnCondition(condition)
									? mw.p2MonsterPlayedOnTurn.get(i) == mw.gameState.getTurnNumber()
									: meetsTargetCondition(mw.p2MonsterStates.get(i), 0, false, false, condition))
								eligible.add(new ForwardTarget(false, i, ForwardTarget.CardZone.MONSTER));
						}
					}
				}
				if (!selfOnly) {
					if (isP1) {
						if (inclForwards || inclMonsters) for (int i = 0; i < mw.p2ForwardCards.size(); i++) {
							CardData card = mw.p2ForwardCards.get(i);
							if (!inclForwards && !card.alsoCountsAsMonster()) continue;
							if (immuneOpp.contains(card)) continue;
							if (element != null && !mw.effectiveContainsElement(card, element)) continue;
							if (!meetsElementExclusion(card, excludeElement)) continue;
							if (!meetsCostConstraint(card.cost(), costVal, costCmp)) continue;
							if (!meetsPowerConstraint(card.power(), powerVal, powerCmp)) continue;
							if (!mw.meetsJobOrCardNameFilter(card, jobFilter, cardNameFilter, mw.p2ForwardCards)) continue;
							if (!meetsCategoryFilter(card, categoryFilter)) continue;
							if (excludeName != null && excludeName.equalsIgnoreCase(card.name())) continue;
							if (withoutMulticard && card.multicard()) continue;
							if (isTraitCondition(condition) && !mw.effectiveP2HasTrait(i, parseTraitFromCondition(condition))) continue;
							if (isBlockingTargetFilter(condition)
									? mw.meetsBlockingTargetFilter(false, i, condition)
									: isEnteredThisTurnCondition(condition)
									? mw.p2ForwardPlayedOnTurn.get(i) == mw.gameState.getTurnNumber()
									: meetsTargetCondition(mw.p2ForwardStates.get(i), mw.p2ForwardDamage.get(i),
											false, false, condition))
								eligible.add(new ForwardTarget(false, i, ForwardTarget.CardZone.FORWARD));
						}
						if (inclBackups || inclForwards) for (int i = 0; i < mw.p2BackupCards.length; i++) {
							if (isBlockingTargetFilter(condition)) continue;
							if (mw.p2BackupCards[i] == null) continue;
							if (!inclBackups && !mw.isP2BackupTemporarilyForward(i)) continue;
							if (immuneOpp.contains(mw.p2BackupCards[i])) continue;
							if (element != null && !mw.effectiveContainsElement(mw.p2BackupCards[i], element)) continue;
							if (!meetsCostConstraint(mw.p2BackupCards[i].cost(), costVal, costCmp)) continue;
							if (!meetsPowerConstraint(mw.p2BackupCards[i].power(), powerVal, powerCmp)) continue;
							if (!mw.meetsJobOrCardNameFilter(mw.p2BackupCards[i], jobFilter, cardNameFilter, mw.p2ForwardCards)) continue;
							if (!meetsCategoryFilter(mw.p2BackupCards[i], categoryFilter)) continue;
							if (excludeName != null && excludeName.equalsIgnoreCase(mw.p2BackupCards[i].name())) continue;
							if (withoutMulticard && mw.p2BackupCards[i].multicard()) continue;
							if (meetsTargetCondition(mw.p2BackupStates[i], 0, false, false, condition))
								eligible.add(new ForwardTarget(false, i, ForwardTarget.CardZone.BACKUP));
						}
						if (inclMonsters || inclForwards) for (int i = 0; i < mw.p2MonsterCards.size(); i++) {
							if (!inclMonsters && !mw.isP2MonsterTemporarilyForward(i)) continue;
							CardData card = mw.p2MonsterCards.get(i);
							if (immuneOpp.contains(card)) continue;
							if (element != null && !mw.effectiveContainsElement(card, element)) continue;
							if (!meetsElementExclusion(card, excludeElement)) continue;
							if (!meetsCostConstraint(card.cost(), costVal, costCmp)) continue;
							if (!meetsPowerConstraint(card.power(), powerVal, powerCmp)) continue;
							if (!mw.meetsJobOrCardNameFilter(card, jobFilter, cardNameFilter, mw.p2ForwardCards)) continue;
							if (!meetsCategoryFilter(card, categoryFilter)) continue;
							if (excludeName != null && excludeName.equalsIgnoreCase(card.name())) continue;
							if (withoutMulticard && card.multicard()) continue;
							if (isEnteredThisTurnCondition(condition)
									? mw.p2MonsterPlayedOnTurn.get(i) == mw.gameState.getTurnNumber()
									: meetsTargetCondition(mw.p2MonsterStates.get(i), 0, false, false, condition))
								eligible.add(new ForwardTarget(false, i, ForwardTarget.CardZone.MONSTER));
						}
					} else {
						// P2 is targeting P1's cards — check "cannot be chosen" protection
						if (inclForwards) for (int i = 0; i < mw.p1ForwardCards.size(); i++) {
							CardData card = p1Forward(i);
							if (immuneOpp.contains(card)) continue;
							if (element != null && !mw.effectiveContainsElement(card, element)) continue;
							if (!meetsElementExclusion(card, excludeElement)) continue;
							if (!meetsCostConstraint(card.cost(), costVal, costCmp)) continue;
							if (!meetsPowerConstraint(card.power(), powerVal, powerCmp)) continue;
							if (!mw.meetsJobOrCardNameFilter(card, jobFilter, cardNameFilter, mw.p1ForwardCards)) continue;
							if (!meetsCategoryFilter(card, categoryFilter)) continue;
							if (excludeName != null && excludeName.equalsIgnoreCase(card.name())) continue;
							if (withoutMulticard && card.multicard()) continue;
							if (isTraitCondition(condition) && !mw.effectiveP1HasTrait(i, parseTraitFromCondition(condition))) continue;
							if (isBlockingTargetFilter(condition)
									? mw.meetsBlockingTargetFilter(true, i, condition)
									: isEnteredThisTurnCondition(condition)
									? mw.p1ForwardPlayedOnTurn.get(i) == mw.gameState.getTurnNumber()
									: meetsTargetCondition(mw.p1ForwardStates.get(i), mw.p1ForwardDamage.get(i),
											mw.p1AttackSelection.contains(i), false, condition))
								eligible.add(new ForwardTarget(true, i, ForwardTarget.CardZone.FORWARD));
						}
						if (inclBackups || inclForwards) for (int i = 0; i < mw.p1BackupCards.length; i++) {
							if (isBlockingTargetFilter(condition)) continue;
							if (mw.p1BackupCards[i] == null) continue;
							if (!inclBackups && !mw.isP1BackupTemporarilyForward(i)) continue;
							if (immuneOpp.contains(mw.p1BackupCards[i])) continue;
							if (element != null && !mw.effectiveContainsElement(mw.p1BackupCards[i], element)) continue;
							if (!meetsCostConstraint(mw.p1BackupCards[i].cost(), costVal, costCmp)) continue;
							if (!meetsPowerConstraint(mw.p1BackupCards[i].power(), powerVal, powerCmp)) continue;
							if (!mw.meetsJobOrCardNameFilter(mw.p1BackupCards[i], jobFilter, cardNameFilter, mw.p1ForwardCards)) continue;
							if (!meetsCategoryFilter(mw.p1BackupCards[i], categoryFilter)) continue;
							if (excludeName != null && excludeName.equalsIgnoreCase(mw.p1BackupCards[i].name())) continue;
							if (withoutMulticard && mw.p1BackupCards[i].multicard()) continue;
							if (meetsTargetCondition(mw.p1BackupStates[i], 0, false, false, condition))
								eligible.add(new ForwardTarget(true, i, ForwardTarget.CardZone.BACKUP));
						}
						if (inclMonsters || inclForwards) for (int i = 0; i < mw.p1MonsterCards.size(); i++) {
							if (!inclMonsters && !mw.isP1MonsterTemporarilyForward(i)) continue;
							CardData card = mw.p1MonsterCards.get(i);
							if (immuneOpp.contains(card)) continue;
							if (element != null && !mw.effectiveContainsElement(card, element)) continue;
							if (!meetsElementExclusion(card, excludeElement)) continue;
							if (!meetsCostConstraint(card.cost(), costVal, costCmp)) continue;
							if (!meetsPowerConstraint(card.power(), powerVal, powerCmp)) continue;
							if (!mw.meetsJobOrCardNameFilter(card, jobFilter, cardNameFilter, null)) continue;
							if (!meetsCategoryFilter(card, categoryFilter)) continue;
							if (excludeName != null && excludeName.equalsIgnoreCase(card.name())) continue;
							if (withoutMulticard && card.multicard()) continue;
							if (isEnteredThisTurnCondition(condition)
									? mw.p1MonsterPlayedOnTurn.get(i) == mw.gameState.getTurnNumber()
									: meetsTargetCondition(mw.p1MonsterStates.get(i), 0, false, false, condition))
								eligible.add(new ForwardTarget(true, i, ForwardTarget.CardZone.MONSTER));
						}
					}
				}
				// "Summons and abilities of your opponent must choose X if possible." — a taunt only
				// binds the player across the field from it, so only the effect controller's
				// opponent's cards are considered. Narrowing the eligible set is what enforces it:
				// every pick the selection can still make is then a compelled one.
				//
				// Only applied while the whole selection has to come from the taunting cards. An
				// effect choosing more cards than there are taunts must include them and is then
				// free with the surplus, which the select dialog has no way to express — such a
				// selection is left unrestricted rather than over-constrained.
				//
				// The redirect path re-chooses an entry's target without coming back through here,
				// so it narrows its own candidate pool — see MainWindow.narrowToCompelledTargets.
				if (!eligible.isEmpty()) {
					List<ForwardTarget> compelled = eligible.stream()
							.filter(t -> t.isP1() != isP1)
							.filter(t -> mw.mustBeChosenByOpponent(cardAtTarget(t), mw.currentResolutionIsSummon))
							.toList();
					if (!compelled.isEmpty() && maxCount <= compelled.size()) eligible.retainAll(compelled);
				}
				// Picks a tiered selection has already made. Removed last, after every other filter
				// and after the taunt narrowing above, so a Forward that is out of the running for
				// any other reason stays out for the reason that actually applies.
				if (!excludedTargets.isEmpty()) eligible.removeAll(excludedTargets);
				return eligible;
			}

			/** {@link #eligibleCharacters} for the filters a {@link TargetSpec} carries. */
			List<ForwardTarget> eligibleCharacters(TargetSpec spec) {
				return eligibleCharacters(spec.maxCount(), spec.upTo(), spec.opponentOnly(), spec.selfOnly(),
						spec.condition(), spec.element(), spec.costVal(), spec.costCmp(), spec.powerVal(),
						spec.powerCmp(), spec.inclForwards(), spec.inclBackups(), spec.inclMonsters(),
						spec.jobFilter(), spec.cardNameFilter(), spec.categoryFilter(), spec.excludeName(),
						spec.inclSummons(), spec.excludeElement(), spec.withoutMulticard());
			}

			@Override
			public List<ForwardTarget> selectCharacters(
					int maxCount, boolean upTo, boolean opponentOnly,
					boolean selfOnly, String condition, String element,
					int costVal, String costCmp, int powerVal, String powerCmp,
					boolean inclForwards, boolean inclBackups, boolean inclMonsters,
					String jobFilter, String cardNameFilter, String categoryFilter, String excludeName, boolean inclSummons,
					String excludeElement, boolean withoutMulticard) {
				List<ForwardTarget> eligible = eligibleCharacters(
						maxCount, upTo, opponentOnly, selfOnly, condition, element,
						costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters,
						jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons,
						excludeElement, withoutMulticard);
				String costLabel  = formatCostFilterLabel(costVal, costCmp);
				String powerLabel = powerVal >= 0 ? " of power " + powerVal + (powerCmp != null ? " or " + powerCmp : "") : "";
				String targetNoun = inclForwards && !inclBackups && !inclMonsters ? "Forward"
						: inclBackups && !inclForwards && !inclMonsters ? "Backup"
						: inclMonsters && !inclForwards && !inclBackups ? "Monster"
						: "Character";
				String preCondLabel  = (condition == null || isTraitCondition(condition)) ? ""
						: " " + condition;
				String postCondLabel = !isTraitCondition(condition) ? ""
						: " with " + condition.substring("trait:".length()).charAt(0)
						  + condition.substring("trait:".length()).substring(1).toLowerCase(java.util.Locale.ROOT).replace("_", " ");
				String strUpto = upTo && maxCount == Integer.MAX_VALUE ? "any number of"
						: upTo ? "up to " + maxCount
						: String.valueOf(maxCount);
				String title = "Choose " + strUpto
						+ preCondLabel
						+ (element != null ? " " + element : "")
						+ " " + targetNoun + (maxCount != 1 ? "s" : "") + postCondLabel + costLabel + powerLabel
						+ (totalCostBudget >= 0 ? " with a total cost of " + totalCostBudget + " or less" : "")
						+ (tieredDamageLabel > 0 ? " to deal " + tieredDamageLabel + " damage" : "")
						+ (opponentOnly ? " (opponent)" : selfOnly ? " (yours)" : "");
				if (!isP1) {
					// AI (P2 controls the effect): auto-select rather than prompting the human.
					if (eligible.isEmpty()) return List.of();
					// A budgeted selection spends rather than counts, so it gets its own pick: the
					// opponent's Forwards, most expensive first, taking each one the budget still
					// covers. Most expensive first because the budget is spent in printed cost and
					// the effect that asks for it is a break -- the dearest board presence the
					// allowance reaches is the one worth removing.
					if (totalCostBudget >= 0) {
						List<ForwardTarget> byCostDesc = eligible.stream()
								.filter(t -> t.isP1() != isP1)
								.sorted(java.util.Comparator.comparingInt(
										(ForwardTarget t) -> cardAtTarget(t).cost()).reversed())
								.toList();
						List<ForwardTarget> affordable = new ArrayList<>();
						int spent = 0;
						for (ForwardTarget t : byCostDesc) {
							int cost = cardAtTarget(t).cost();
							if (spent + cost > totalCostBudget) continue;
							affordable.add(t);
							spent += cost;
						}
						affordable.forEach(t -> logEntry("[AI] chose " + cardAtTarget(t).name()));
						return fireChosenByOpponentTriggers(affordable);
					}
					// For unqualified targeting, prefer whichever side the effect actually helps or
					// hurts: buffs go to the AI's own cards, everything else to the opponent's.
					// Only a preference — if the preferred side has nothing eligible, the AI still
					// picks from the full pool rather than declining to resolve the effect.
					List<ForwardTarget> pool = eligible;
					if (!opponentOnly && !selfOnly) {
						boolean preferOwn = mw.aiPrefersOwnTargets;
						List<ForwardTarget> preferred = eligible.stream()
								.filter(t -> t.isP1() != preferOwn).toList();
						if (!preferred.isEmpty()) pool = preferred;
					}
					// The selection is about to be dealt damage: aim it at an opponent's Character
					// the damage would actually break. Left to the shuffle below, a 5000-damage
					// ability is as likely to pick the 7000-power Forward that shrugs it off as
					// the 5000-power one it would remove — the same cost paid for nothing.
					// Narrowing the pool rather than sorting it keeps the pick random among the
					// choices that are equally the best one.
					boolean orderByPower = false;
					if (aiDamageTargetHint > 0) {
						List<ForwardTarget> lethal = pool.stream()
								.filter(t -> t.isP1() != isP1)
								.filter(t -> wouldBreakUnderDamage(t, aiDamageTargetHint))
								.toList();
						if (!lethal.isEmpty()) { pool = lethal; orderByPower = true; }
					}
					List<ForwardTarget> copy = new ArrayList<>(pool);
					java.util.Collections.shuffle(copy);
					// Biggest of the breakable first — same cost, strictly more removed — so a
					// choose of several fills up from the top of the board downwards. The sort is
					// stable, so the shuffle above still decides between equal Forwards.
					if (orderByPower)
						copy.sort(java.util.Comparator.comparingInt(
								(ForwardTarget t) -> fieldPowerAt(t)).reversed());
					List<ForwardTarget> picked = List.copyOf(copy.subList(0, Math.min(maxCount, copy.size())));
					picked.forEach(t -> {
						CardData c = switch (t.zone()) {
							case BACKUP  -> t.isP1() ? mw.p1BackupCards[t.idx()] : mw.p2BackupCards[t.idx()];
							case MONSTER -> t.isP1() ? mw.p1MonsterCards.get(t.idx()) : mw.p2MonsterCards.get(t.idx());
							default      -> t.isP1() ? p1Forward(t.idx()) : mw.p2ForwardCards.get(t.idx());
						};
						logEntry("[AI] chose " + c.name());
					});
					return fireChosenByOpponentTriggers(picked);
				}
				List<ForwardTarget> chosen = totalCostBudget >= 0
						? mw.showForwardSelectWithinTotalCostDialog(eligible, totalCostBudget, title)
						: mw.showForwardSelectDialog(eligible, maxCount, upTo, title);
				return fireChosenByOpponentTriggers(chosen);
			}

			/**
			 * Runs an ordinary unbounded Forward selection with {@link #totalCostBudget} set, which
			 * is what swaps in the budgeted dialog and the budgeted AI pick. Going through
			 * {@link #selectCharacters} rather than gathering the Forwards here is deliberate: the
			 * "cannot be chosen" sets, the taunt narrowing and the chosen-by-opponent triggers all
			 * live in that method, and a selection that skipped them would be choosing cards the
			 * rest of the engine says are not choosable.
			 *
			 * <p>The budget is cleared in a finally block, so a dialog the player dismisses cannot
			 * leave it set for the next selection this context makes.
			 */
			@Override public List<ForwardTarget> selectForwardsWithTotalCostAtMost(int maxTotalCost) {
				totalCostBudget = Math.max(0, maxTotalCost);
				try {
					return selectCharacters(Integer.MAX_VALUE, true, false, false, null, null,
							-1, null, -1, null, true, false, false,
							null, null, null, null, false, null, false);
				} finally {
					totalCostBudget = -1;
				}
			}

			/**
			 * One prompt per amount, each labelled with the damage it carries and each blind to the
			 * Forwards the earlier prompts took.
			 *
			 * <p>Delegates to {@link #selectCharacters} rather than walking the board itself, for
			 * the same reason {@link #selectForwardsWithTotalCostAtMost} does: every "cannot be
			 * chosen" shield, the must-be-chosen taunt narrowing and the AI's auto-pick all live in
			 * that method. The per-prompt damage is also handed to the AI hint, so an AI controller
			 * aims the 6000 at something 6000 actually breaks rather than spending it on a Forward
			 * the 2000 would have finished.
			 *
			 * <p>Both scratch fields are cleared in a finally block, so a dialog the player
			 * dismisses cannot leave them set for the next selection this context makes.
			 */
			@Override public List<GameContext.TieredDamagePick> selectOppForwardsForTieredDamage(int[] amounts) {
				List<GameContext.TieredDamagePick> picks = new ArrayList<>();
				List<ForwardTarget> taken = new ArrayList<>();
				for (int amount : amounts) {
					List<ForwardTarget> pick;
					excludedTargets  = List.copyOf(taken);
					tieredDamageLabel = amount;
					setAiDamageTargetHint(amount);
					try {
						pick = selectCharacters(1, true, true, false, null, null,
								-1, null, -1, null, true, false, false,
								null, null, null, null, false, null, false);
					} finally {
						excludedTargets   = List.of();
						tieredDamageLabel = 0;
						setAiDamageTargetHint(0);
					}
					if (pick.isEmpty()) continue;
					taken.add(pick.get(0));
					picks.add(new GameContext.TieredDamagePick(pick.get(0), amount));
				}
				return picks;
			}

			/**
			 * Fires the "chosen by opponent's summon" (Summon-only, Forward-only — unchanged scope)
			 * and "chosen by opponent's summon or ability" (Summon or ability, any zone) triggers for
			 * whichever of {@code selected}'s cards belong to this context's opponent, then returns
			 * the selection re-anchored to where those cards now sit — unless the broad trigger
			 * cancels the selection (opponent declined to pay a Dull-style CP tax), in which case an
			 * empty list is returned so the calling effect sees no targets at all and no-ops, per its
			 * usual "nothing eligible" handling.
			 */
			private List<ForwardTarget> fireChosenByOpponentTriggers(List<ForwardTarget> selected) {
				// Only cards on the *other* side are events these triggers can see — they all read
				// "when [something of mine] is chosen by your opponent's …". The cards themselves,
				// not just a yes/no, are handed down: which of them was chosen decides whether a
				// given watcher's subject is satisfied.
				List<CardData> oppCharactersChosen = selected.stream()
						.filter(t -> t.isP1() != isP1)
						.map(this::cardAtTarget)
						.filter(Objects::nonNull)
						.toList();
				List<CardData> oppForwardsChosen = selected.stream()
						.filter(t -> t.zone() == ForwardTarget.CardZone.FORWARD && t.isP1() != isP1)
						.map(this::cardAtTarget)
						.filter(Objects::nonNull)
						.toList();
				if (oppCharactersChosen.isEmpty() && oppForwardsChosen.isEmpty()) return selected;

				// What each slot held before the triggers ran, so the selection can be re-anchored
				// afterwards — the triggers resolve inline and may move the very cards it names.
				List<CardData> chosenCards = selected.stream().map(this::cardAtTarget).toList();

				if (mw.currentResolutionIsSummon && !oppForwardsChosen.isEmpty())
					mw.autoAbilityTriggers.triggerAutoAbilitiesForChosenByOpponentSummon(
							!isP1, oppForwardsChosen);
				if (!oppCharactersChosen.isEmpty()) {
					mw.lastChosenSelectionCancelled = false;
					mw.autoAbilityTriggers.triggerAutoAbilitiesForChosenByOpponentSummonOrAbility(
							!isP1, oppCharactersChosen);
					if (mw.lastChosenSelectionCancelled) {
						logEntry("Selection cancelled — opponent declined to pay");
						return List.of();
					}
				}
				return reanchorSelection(selected, chosenCards);
			}

			/**
			 * Re-points {@code selected} at wherever {@code chosenCards} now sit, dropping the slots
			 * whose card has left the zone it was chosen in.
			 *
			 * <p>A {@link ForwardTarget} is a position, not a card, and the zone lists close up when
			 * a Character leaves them. Emet-Selch (12-024H) removing itself from the game in response
			 * to being chosen therefore invalidates the very selection that named it: the index either
			 * runs off the end or slides onto its neighbour, which would take the damage meant for the
			 * card that just left. Dropping the slot is what makes that damage fizzle, and shifting
			 * the survivors is what keeps a multi-target effect pointed at the cards it picked.
			 */
			private List<ForwardTarget> reanchorSelection(List<ForwardTarget> selected,
					List<CardData> chosenCards) {
				List<ForwardTarget> out = new ArrayList<>(selected.size());
				for (int i = 0; i < selected.size(); i++) {
					ForwardTarget t    = selected.get(i);
					CardData      card = chosenCards.get(i);
					// Break-zone picks and slots that were already empty carry no card to follow.
					if (card == null || t.zone() == ForwardTarget.CardZone.BREAK_ZONE) { out.add(t); continue; }
					if (cardAtTarget(t) == card) { out.add(t); continue; }
					int now = currentIndexOf(card, t.isP1(), t.zone());
					if (now >= 0) out.add(new ForwardTarget(t.isP1(), now, t.zone()));
					else logEntry(card.name() + " is no longer on the field — it is no longer chosen");
				}
				return out;
			}

			/** {@code card}'s current index in the given side's zone list, or -1 once it has left. */
			private int currentIndexOf(CardData card, boolean cardIsP1, ForwardTarget.CardZone zone) {
				switch (zone) {
					case FORWARD -> {
						List<CardData> l = cardIsP1 ? mw.p1ForwardCards : mw.p2ForwardCards;
						for (int i = 0; i < l.size(); i++) if (l.get(i) == card) return i;
					}
					case BACKUP -> {
						CardData[] a = cardIsP1 ? mw.p1BackupCards : mw.p2BackupCards;
						for (int i = 0; i < a.length; i++) if (a[i] == card) return i;
					}
					case MONSTER -> {
						List<CardData> l = cardIsP1 ? mw.p1MonsterCards : mw.p2MonsterCards;
						for (int i = 0; i < l.size(); i++) if (l.get(i) == card) return i;
					}
					default -> { }
				}
				return -1;
			}

			@Override public void dullP1Forward(int idx) {
				if (idx >= mw.p1ForwardStates.size()) return;
				CardData c = p1Forward(idx);
				if (!isP1 && (ActionResolver.hasCannotBeDulledByOppFieldAbility(c)
						|| mw.effectiveP1HasTrait(idx, CardData.Trait.CANNOT_BE_DULLED_BY_OPP))) {
					logEntry(c.name() + " cannot become dull by opponent's effects");
					return;
				}
				// A dull Forward does not "become dull" again: no rotation to replay, and no
				// becomes-dull abilities to fire.
				if (mw.p1ForwardStates.get(idx) == CardState.DULL) {
					logEntry(c.name() + " is already dull");
					return;
				}
				mw.p1ForwardStates.set(idx, CardState.DULL);
				logEntry(c.name() + " is dulled");
				mw.animateDullForward(idx, null);
				mw.autoAbilityTriggers.triggerAutoAbilitiesForBecomesDull(c, true);
			}

			@Override public void dullP2Forward(int idx) {
				if (idx >= mw.p2ForwardStates.size()) return;
				CardData c = mw.p2ForwardCards.get(idx);
				if (isP1 && (ActionResolver.hasCannotBeDulledByOppFieldAbility(c)
						|| mw.effectiveP2HasTrait(idx, CardData.Trait.CANNOT_BE_DULLED_BY_OPP))) {
					logEntry("[P2] " + c.name() + " cannot become dull by opponent's effects");
					return;
				}
				// A dull Forward does not "become dull" again: no rotation to replay, and no
				// becomes-dull abilities to fire.
				if (mw.p2ForwardStates.get(idx) == CardState.DULL) {
					logEntry("[P2] " + c.name() + " is already dull");
					return;
				}
				mw.p2ForwardStates.set(idx, CardState.DULL);
				logEntry("[P2] " + c.name() + " is dulled");
				mw.animateDullP2Forward(idx, null);
				mw.autoAbilityTriggers.triggerAutoAbilitiesForBecomesDull(c, false);
			}

			@Override public void freezeP1Forward(int idx) {
				if (idx >= mw.p1ForwardStates.size()) return;
				mw.p1ForwardFrozen.set(idx, true);
				logEntry(p1Forward(idx).name() + " is frozen");
				mw.refreshP1ForwardSlot(idx);
			}

			@Override public void freezeP2Forward(int idx) {
				if (idx >= mw.p2ForwardStates.size()) return;
				mw.p2ForwardFrozen.set(idx, true);
				logEntry("[P2] " + mw.p2ForwardCards.get(idx).name() + " is frozen");
				mw.refreshP2ForwardSlot(idx);
			}

			// The row-index API is kept because the effects that use it genuinely mean "the Forward
			// in slot N" — several sweep the whole row. Storage is by card instance, so the index
			// is resolved here and never held.
			@Override public void setP1ForwardCannotBlock(int idx) {
				if (idx >= 0 && idx < mw.p1ForwardCards.size()) mw.p1CannotBlock.add(mw.p1ForwardCards.get(idx));
			}
			@Override public void setP2ForwardCannotBlock(int idx) {
				if (idx >= 0 && idx < mw.p2ForwardCards.size()) mw.p2CannotBlock.add(mw.p2ForwardCards.get(idx));
			}
			@Override public void setP1ForwardCannotBeBlocked(int idx) {
				if (idx >= 0 && idx < mw.p1ForwardCards.size()) mw.p1CannotBeBlocked.add(mw.p1ForwardCards.get(idx));
			}
			@Override public void setP2ForwardCannotBeBlocked(int idx) {
				if (idx >= 0 && idx < mw.p2ForwardCards.size()) mw.p2CannotBeBlocked.add(mw.p2ForwardCards.get(idx));
			}
			@Override public void setP1ForwardCannotBeBlockedByCost(int idx, int costVal, boolean isMore) {
				if (idx >= 0 && idx < mw.p1ForwardCards.size())
					mw.p1CannotBeBlockedByCost.put(mw.p1ForwardCards.get(idx), new int[]{costVal, isMore ? 1 : 0});
			}
			@Override public void setP2ForwardCannotBeBlockedByCost(int idx, int costVal, boolean isMore) {
				if (idx >= 0 && idx < mw.p2ForwardCards.size())
					mw.p2CannotBeBlockedByCost.put(mw.p2ForwardCards.get(idx), new int[]{costVal, isMore ? 1 : 0});
			}
			// Instance keying makes the grant a direct write: no row search, so it reaches a source
			// attacking from the Monster or Backup row as readily as one on the Forward row.
			@Override public void grantSelfCannotBeBlockedByCost(CardData source, int costVal, boolean isMore) {
				if (source == null) return;
				(isP1 ? mw.p1CannotBeBlockedByCost : mw.p2CannotBeBlockedByCost)
						.put(source, new int[]{costVal, isMore ? 1 : 0});
				logEntry(source.name() + " gains \"cannot be blocked by a Forward of cost " + costVal
						+ " or " + (isMore ? "more" : "less") + "\" until end of turn");
			}
			@Override public void grantSelfCannotBeBlockedByPower(CardData source, int powerVal, boolean isMore) {
				if (source == null) return;
				(isP1 ? mw.p1CannotBeBlockedByPower : mw.p2CannotBeBlockedByPower)
						.put(source, new int[]{powerVal, isMore ? 1 : 0});
				logEntry(source.name() + " gains \"cannot be blocked by a Forward of power " + powerVal
						+ " or " + (isMore ? "more" : "less") + "\" until end of turn");
			}
			@Override public void grantSelfCannotBlockUntilEndOfTurn(CardData source) {
				boolean applied = false;
				for (int i = 0; i < mw.p1ForwardCards.size() && !applied; i++)
					if (mw.p1ForwardCards.get(i) == source) { setP1ForwardCannotBlock(i); applied = true; }
				for (int i = 0; i < mw.p2ForwardCards.size() && !applied; i++)
					if (mw.p2ForwardCards.get(i) == source) { setP2ForwardCannotBlock(i); applied = true; }
				if (applied)
					logEntry(source.name() + " gains \"" + source.name() + " cannot block.\" until end of turn");
			}
			@Override public void grantMaxAttacksUntilEndOfTurn(CardData source, int maxAttacks) {
				mw.grantedMaxAttacks.merge(source, maxAttacks, Math::max);
				mw.endOfTurnEffects.add(ctx -> mw.grantedMaxAttacks.remove(source));
				logEntry(source.name() + " gains \"can attack " + attackCountPhrase(maxAttacks)
						+ " in the same turn\" until end of turn");
			}
			@Override public boolean grantSelfAutoAbilityPermanently(CardData source, String abilityText) {
				List<AutoAbility> granted = CardData.parseAutoAbilities(abilityText);
				if (granted.isEmpty()) return false;
				mw.grantedAutoAbilities.computeIfAbsent(source, k -> new ArrayList<>()).addAll(granted);
				logEntry(source.name() + " gains \"" + abilityText + "\" (does not end at end of turn)");
				return true;
			}
			@Override public void grantMaxAttacksPermanently(CardData source, int maxAttacks) {
				mw.permanentMaxAttacks.merge(source, maxAttacks, Math::max);
				logEntry(source.name() + " gains \"can attack " + attackCountPhrase(maxAttacks)
						+ " in the same turn\" (does not end at end of turn)");
			}
			@Override public void grantSelfFieldAbilityUntilEndOfTurn(CardData source, String abilityText) {
				FieldAbility granted = new FieldAbility(abilityText, 0);
				mw.grantedFieldAbilities.computeIfAbsent(source, k -> new ArrayList<>()).add(granted);
				mw.endOfTurnEffects.add(ctx -> {
					List<FieldAbility> list = mw.grantedFieldAbilities.get(source);
					if (list != null && list.remove(granted) && list.isEmpty())
						mw.grantedFieldAbilities.remove(source);
				});
				logEntry(source.name() + " gains \"" + abilityText + "\" until end of turn");
			}
			@Override public void grantFieldAbilityUntilEndOfTurn(ForwardTarget target, String abilityText) {
				CardData card = mw.autoAbilityTriggers.fieldCardData(target);
				if (card == null) return;
				grantSelfFieldAbilityUntilEndOfTurn(card, abilityText);
			}
			@Override public void grantAutoAbilityPermanently(ForwardTarget target, String abilityText) {
				CardData card = mw.autoAbilityTriggers.fieldCardData(target);
				if (card == null) return;
				grantSelfAutoAbilityPermanently(card, abilityText);
			}
			@Override public void setOppForwardsCannotBlockInferiorPowerThisTurn() {
				if (isP1()) mw.p2Turn.forwardCannotBlockInferiorPower = true;
				else        mw.p1Turn.forwardCannotBlockInferiorPower = true;
				logEntry("Effect: Opponent Forwards cannot block Forwards with power inferior to their own this turn");
			}
			@Override public void setAllForwardsCannotBeBlockedByHigherCostThisTurn() {
				mw.allForwardsCannotBeBlockedByHigherCostThisTurn = true;
			}
			@Override public void setOppFwdPowerBoostSuppressedThisTurn() {
				if (isP1()) mw.p2Turn.fwdBoostSuppressedThisTurn = true;
				else        mw.p1Turn.fwdBoostSuppressedThisTurn = true;
				logEntry("Effect: Opponent Forwards cannot have their power increased this turn");
			}
			@Override public void oppForwardsLoseAllAbilitiesUntilEndOfTurn() {
				List<CardData> oppFwds = isP1() ? mw.p2ForwardCards : mw.p1ForwardCards;
				for (CardData fwd : oppFwds) {
					if (mw.lostAbilitiesCards.add(fwd)) {
						mw.endOfTurnEffects.add(ctx -> mw.lostAbilitiesCards.remove(fwd));
					}
				}
				logEntry("Effect: All opponent Forwards lose all abilities until end of turn");
			}
			@Override public void targetLoseAllAbilitiesUntilEndOfTurn(ForwardTarget t) {
				List<CardData> fwds = t.isP1() ? mw.p1ForwardCards : mw.p2ForwardCards;
				if (t.idx() < 0 || t.idx() >= fwds.size()) return;
				CardData card = fwds.get(t.idx());
				if (mw.lostAbilitiesCards.add(card)) {
					mw.endOfTurnEffects.add(ctx -> mw.lostAbilitiesCards.remove(card));
				}
				logEntry("Effect: " + card.name() + " loses all abilities until end of turn");
			}
			@Override public void targetLoseAllAbilitiesWhileWardenOnField(ForwardTarget t, CardData warden) {
				CardData card = mw.autoAbilityTriggers.fieldCardData(t);
				if (card == null || warden == null) return;
				mw.abilitiesStrippedWhileWardenOnField.put(card, warden);
				logEntry("Effect: " + card.name() + " loses all abilities while "
						+ warden.name() + " is on the field");
			}
			@Override public boolean wasElementCpPaid(String element) {
				return element != null && mw.lastCastPaymentElements.stream()
						.anyMatch(e -> e.equalsIgnoreCase(element));
			}
			@Override public void setP1ForwardMustBlock(int idx) {
				if (idx >= 0 && idx < mw.p1ForwardCards.size()) mw.p1MustBlock.add(mw.p1ForwardCards.get(idx));
			}
			@Override public void setP2ForwardMustBlock(int idx) {
				if (idx >= 0 && idx < mw.p2ForwardCards.size()) mw.p2MustBlock.add(mw.p2ForwardCards.get(idx));
			}
			@Override public void setP1ForwardCannotAttack(int idx) {
				if (idx >= 0 && idx < mw.p1ForwardCards.size()) mw.p1CannotAttack.add(mw.p1ForwardCards.get(idx));
			}
			@Override public void setP2ForwardCannotAttack(int idx) {
				if (idx >= 0 && idx < mw.p2ForwardCards.size()) mw.p2CannotAttack.add(mw.p2ForwardCards.get(idx));
			}
			@Override public void setP1ForwardMustAttack(int idx) {
				if (idx >= 0 && idx < mw.p1ForwardCards.size()) mw.p1MustAttack.add(mw.p1ForwardCards.get(idx));
			}
			@Override public void setP2ForwardMustAttack(int idx) {
				if (idx >= 0 && idx < mw.p2ForwardCards.size()) mw.p2MustAttack.add(mw.p2ForwardCards.get(idx));
			}
			@Override public void setP1ForwardCannotAttackOrBlockPersistent(int idx) {
				if (idx >= 0 && idx < mw.p1ForwardCards.size()) {
					CardData card = mw.p1ForwardCards.get(idx);
					mw.p1CannotAttackPersistent.add(card);
					mw.p1CannotBlockPersistent.add(card);
				}
			}
			@Override public void setP2ForwardCannotAttackOrBlockPersistent(int idx) {
				if (idx >= 0 && idx < mw.p2ForwardCards.size()) {
					CardData card = mw.p2ForwardCards.get(idx);
					mw.p2CannotAttackPersistent.add(card);
					mw.p2CannotBlockPersistent.add(card);
				}
			}
			@Override public void setTargetCannotAttackOrBlockThisTurn(ForwardTarget t) {
				CardData card = targetCard(t);
				if (card == null) return;
				// The target names the side the Character sits on, which is the side whose end
				// phase clears the restriction.
				if (t.isP1()) { mw.p1CannotAttack.add(card); mw.p1CannotBlock.add(card); }
				else          { mw.p2CannotAttack.add(card); mw.p2CannotBlock.add(card); }
				logEntry(card.name() + " cannot attack or block this turn");
			}
			@Override public void setTargetCannotUseActionAbilitiesThisTurn(ForwardTarget t) {
				CardData card = targetCard(t);
				if (card == null) return;
				mw.cannotUseActionAbilitiesThisTurn.add(card);
				logEntry(card.name() + " cannot use action abilities this turn");
			}
			@Override public void returnP1ForwardToHand(int idx) {
				if (!isP1 && idx >= 0 && idx < mw.p1ForwardCards.size()) {
					CardData c = p1Forward(idx);
					if (leaveFieldProtected(c, true)) return;
					if (ActionResolver.hasCannotBeReturnedToHandByOppFieldAbility(c)
							|| mw.effectiveP1HasTrait(idx, CardData.Trait.CANNOT_BE_RETURNED_TO_HAND_BY_OPP)
							|| mw.charactersProtectedFromOppReturnToHand(true)) {
						logEntry(c.name() + " cannot be returned to its owner's hand by opponent's effects");
						return;
					}
				}
				mw.returnP1ForwardToHand(idx);
			}
			@Override public void returnP2ForwardToHand(int idx) {
				if (isP1 && idx >= 0 && idx < mw.p2ForwardCards.size()) {
					CardData c = mw.p2ForwardCards.get(idx);
					if (leaveFieldProtected(c, false)) return;
					if (ActionResolver.hasCannotBeReturnedToHandByOppFieldAbility(c)
							|| mw.effectiveP2HasTrait(idx, CardData.Trait.CANNOT_BE_RETURNED_TO_HAND_BY_OPP)
							|| mw.charactersProtectedFromOppReturnToHand(false)) {
						logEntry("[P2] " + c.name() + " cannot be returned to its owner's hand by opponent's effects");
						return;
					}
				}
				mw.returnP2ForwardToHand(idx);
			}
			@Override public boolean askTopOrBottom(String cardName) {
					if (!isP1) {
						logEntry("[AI] places " + cardName + " on top of the deck");
						return true;
					}
				Object[] options = { "Top", "Bottom" };
				int result = JOptionPane.showOptionDialog(mw.frame,
						"Place " + cardName + " at the top or bottom of the deck?",
						"Choose Deck Position",
						JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE,
						null, options, options[0]);
				return result != 1;
			}
			@Override public int selectNumber(int min, int max, String prompt) {
					if (!isP1) {
						logEntry("[AI] selected " + max + " (" + prompt + ")");
						return max;
					}
				return mw.showNumberSelectDialog(prompt, min, max);
			}
			@Override public int selectPowerAmount(int maxAmount, String prompt) {
				if (!isP1) {
					logEntry("[AI] selected " + maxAmount + " (" + prompt + ")");
					return maxAmount;
				}
				return mw.showPowerAmountDialog(maxAmount, prompt);
			}

			@Override public List<Integer> divideDamageAmount(int damage, String prompt, List<CardData> cards) {
				return mw.showDivideDamageDialog(damage, prompt, cards);
			}
			@Override public void returnP1ForwardToDeckBottom(int idx)   { mw.returnP1ForwardToDeck(idx, true);  }
			@Override public void returnP2ForwardToDeckBottom(int idx)   { mw.returnP2ForwardToDeck(idx, true);  }
			@Override public void returnP1ForwardToDeckTop(int idx)      { mw.returnP1ForwardToDeck(idx, false); }
			@Override public void returnP2ForwardToDeckTop(int idx)      { mw.returnP2ForwardToDeck(idx, false); }
			@Override public void returnP1ForwardUnderDeckTop(int idx, int position) { mw.returnP1ForwardUnderDeckTop(idx, position); }
			@Override public void returnP2ForwardUnderDeckTop(int idx, int position) { mw.returnP2ForwardUnderDeckTop(idx, position); }
			@Override public boolean searchDeckForCard(boolean inclForwards, boolean inclBackups,
					boolean inclMonsters, boolean inclSummons,
					int costVal, String costCmp, String cardNameFilter, String jobFilter,
					String categoryFilter, String elementFilter, String excludeName, String excludeElem,
					String destination, int count, boolean entersDull, boolean requireWarp) {
				return mw.searchDeckForCard(isP1, inclForwards, inclBackups, inclMonsters, inclSummons,
						costVal, costCmp, cardNameFilter, jobFilter, categoryFilter, elementFilter, excludeName, excludeElem, destination, count, entersDull, requireWarp);
			}
			@Override public boolean searchDeckForNamedCardWithJob(boolean inclForwards, boolean inclBackups,
					boolean inclMonsters, boolean inclSummons,
					int costVal, String costCmp, String cardNameFilter, String jobFilter,
					String elementFilter, String excludeName, String excludeElem,
					String destination, int count, boolean entersDull, boolean requireWarp) {
				return mw.searchDeckForNamedCardWithJob(isP1, inclForwards, inclBackups, inclMonsters, inclSummons,
						costVal, costCmp, cardNameFilter, jobFilter, elementFilter, excludeName, excludeElem, destination, count, entersDull, requireWarp);
			}
			@Override public void searchDeckJobAndTypeDontShareElements(String jobFilter, String typeName) {
				mw.searchDeckJobAndTypeDontShareElements(isP1, jobFilter, typeName);
			}
			@Override public void searchDeckElementOrCategoryCharsDifferentCost(String element, String category) {
				mw.searchDeckElementOrCategoryCharsDifferentCost(isP1, element, category);
			}
			@Override public void searchDeckNElementSummonsDifferentCost(int count, String element) {
				mw.searchDeckNElementSummonsDifferentCost(isP1, count, element);
			}

			@Override public void playAllByNameFromOwnBreakZoneDull(String cardName, boolean dull) {
				List<CardData> bz = isP1 ? mw.gameState.getP1BreakZone() : mw.gameState.getP2BreakZone();
				List<CardData> toPlay = new java.util.ArrayList<>();
				for (int i = bz.size() - 1; i >= 0; i--)
					if (meetsCardNameFilter(bz.get(i), cardName)) toPlay.add(bz.remove(i));
				for (CardData card : toPlay) {
					logEntry(card.name() + " played from Break Zone → field" + (dull ? " dull" : ""));
					if (isP1) {
						if (card.isBackup())       mw.placeCardInFirstBackupSlot(card);
						else if (card.isMonster()) mw.placeCardInMonsterZone(card);
						else {
							mw.placeCardInForwardZone(card);
							if (dull) {
								int idx = mw.p1ForwardCards.size() - 1;
								mw.p1ForwardStates.set(idx, CardState.DULL);
								mw.refreshP1ForwardSlot(idx);
							}
						}
					} else {
						if (card.isBackup())       mw.placeP2CardInFirstBackupSlot(card);
						else if (card.isMonster()) mw.placeP2CardInMonsterZone(card);
						else                       mw.placeP2CardInForwardZone(card);
					}
				}
				if (isP1) mw.refreshP1BreakLabel(); else mw.refreshP2BreakLabel();
			}

			@Override public void returnP1BackupToHand(int idx) {
				CardData c = idx >= 0 && idx < mw.p1BackupCards.length ? mw.p1BackupCards[idx] : null;
				if (!isP1 && c != null && characterReturnToHandProtected(c, true)) return;
				mw.returnP1BackupToHand(idx);
			}
			@Override public void returnP2BackupToHand(int idx) {
				CardData c = idx >= 0 && idx < mw.p2BackupCards.length ? mw.p2BackupCards[idx] : null;
				if (isP1 && c != null && characterReturnToHandProtected(c, false)) return;
				mw.returnP2BackupToHand(idx);
			}
			@Override public void returnP1MonsterToHand(int idx) {
				CardData c = idx >= 0 && idx < mw.p1MonsterCards.size() ? mw.p1MonsterCards.get(idx) : null;
				if (!isP1 && c != null && characterReturnToHandProtected(c, true)) return;
				mw.returnP1MonsterToHand(idx);
			}
			@Override public void returnP2MonsterToHand(int idx) {
				CardData c = idx >= 0 && idx < mw.p2MonsterCards.size() ? mw.p2MonsterCards.get(idx) : null;
				if (isP1 && c != null && characterReturnToHandProtected(c, false)) return;
				mw.returnP2MonsterToHand(idx);
			}

			/**
			 * True when {@code card} (a backup or monster controlled by {@code targetIsP1}) is protected
			 * from being returned to hand by the acting opponent — via its own named field ability or a
			 * controller-wide "Characters you control cannot be returned…" field card. Logs when protected.
			 */
			/**
			 * True when {@code card}, controlled by {@code targetIsP1}, may not leave the field
			 * because this context's effect belongs to the other player. Logs when it applies, so
			 * every exit point reports the block the same way.
			 */
			private boolean leaveFieldProtected(CardData card, boolean targetIsP1) {
				if (!mw.isProtectedFromLeavingField(card, targetIsP1, isP1)) return false;
				logEntry((targetIsP1 ? "" : "[P2] ") + card.name()
						+ " cannot leave the field due to your opponent's Summons or abilities");
				return true;
			}

			private boolean characterReturnToHandProtected(CardData card, boolean targetIsP1) {
				if (leaveFieldProtected(card, targetIsP1)) return true;
				if (ActionResolver.hasCannotBeReturnedToHandByOppFieldAbility(card)
						|| mw.charactersProtectedFromOppReturnToHand(targetIsP1)) {
					logEntry((targetIsP1 ? "" : "[P2] ") + card.name()
							+ " cannot be returned to its owner's hand by opponent's effects");
					return true;
				}
				return false;
			}

			@Override public boolean isP1ForwardAttacking(int idx) { return mw.p1AttackSelection.contains(idx); }
			@Override public boolean isP2ForwardAttacking(int idx) { return false; }
			@Override public boolean isP1ForwardBlocking(int idx)  { return false; }
			@Override public boolean isP2ForwardBlocking(int idx)  { return false; }

			@Override public void breakBlockingForward() {
				if (isP1) {
					// P1's card was blocked — the blocking Forward is on P2's side
					if (mw.p2BlockingIdx >= 0 && mw.p2BlockingIdx < mw.p2ForwardCards.size())
						breakP2Forward(mw.p2BlockingIdx);
				} else {
					// P2's card was blocked — the blocking Forward is on P1's side
					if (mw.p1BlockingIdx >= 0 && mw.p1BlockingIdx < mw.p1ForwardCards.size())
						breakP1Forward(mw.p1BlockingIdx);
				}
			}

			@Override public void breakForwardBlockingAttacker(String attackerName) {
				if (isP1) {
					if (mw.p2BlockingIdx >= 0 && mw.p2BlockingIdx < mw.p2ForwardCards.size()
							&& mw.p2BlockedByAttacker != null
							&& mw.p2BlockedByAttacker.name().equalsIgnoreCase(attackerName))
						breakP2Forward(mw.p2BlockingIdx);
				} else {
					if (mw.p1BlockingIdx >= 0 && mw.p1BlockingIdx < mw.p1ForwardCards.size()
							&& mw.p1BlockedByAttacker != null
							&& mw.p1BlockedByAttacker.name().equalsIgnoreCase(attackerName))
						breakP1Forward(mw.p1BlockingIdx);
				}
			}

			@Override public void breakP1Forward(int idx) {
				if (idx >= 0 && idx < mw.p1ForwardCards.size()
						&& mw.effectiveP1HasTrait(idx, CardData.Trait.CANNOT_BE_BROKEN)) {
					logEntry(mw.p1ForwardCards.get(idx).name() + " cannot be broken");
					return;
				}
				if (!isP1 && idx >= 0 && idx < mw.p1ForwardCards.size()
						&& ActionResolver.hasCannotBePutIntoBzByOppFieldAbility(p1Forward(idx))) {
					logEntry(p1Forward(idx).name() + " cannot be put into the Break Zone by opponent's effects");
					return;
				}
				mw.breakP1Forward(idx);
			}
			@Override public void breakP2Forward(int idx) {
				if (idx >= 0 && idx < mw.p2ForwardCards.size()
						&& mw.effectiveP2HasTrait(idx, CardData.Trait.CANNOT_BE_BROKEN)) {
					logEntry("[P2] " + mw.p2ForwardCards.get(idx).name() + " cannot be broken");
					return;
				}
				if (isP1 && idx >= 0 && idx < mw.p2ForwardCards.size()
						&& ActionResolver.hasCannotBePutIntoBzByOppFieldAbility(mw.p2ForwardCards.get(idx))) {
					logEntry("[P2] " + mw.p2ForwardCards.get(idx).name() + " cannot be put into the Break Zone by opponent's effects");
					return;
				}
				mw.breakP2Forward(idx);
			}

			@Override public void removeP1ForwardFromGame(int idx) {
				if (idx >= mw.p1ForwardCards.size()) return;
				mw.lastRemovedFromGameCardCost  = p1Forward(idx).cost();
				mw.lastRemovedFromGameCardPower = p1Forward(idx).power();
				logEntry(p1Forward(idx).name() + " → Removed From Game");
				mw.startRfpAnim(idx, true);
				List<CardData> bz = mw.gameState.getP1BreakZone();
				int before = bz.size();
				mw.suppressNextBreakAnim = true;
				mw.breakP1Forward(idx);
				while (bz.size() > before)
					mw.gameState.addToPermanentRfp(bz.remove(bz.size() - 1));
				mw.refreshP1BreakLabel();
				mw.refreshP1WarpZoneUI();
			}

			@Override public void removeP2ForwardFromGame(int idx) {
				if (idx >= mw.p2ForwardCards.size()) return;
				mw.lastRemovedFromGameCardCost  = mw.p2ForwardCards.get(idx).cost();
				mw.lastRemovedFromGameCardPower = mw.p2ForwardCards.get(idx).power();
				logEntry("[P2] " + mw.p2ForwardCards.get(idx).name() + " → Removed From Game");
				mw.startRfpAnim(idx, false);
				List<CardData> bz = mw.gameState.getP2BreakZone();
				int before = bz.size();
				mw.suppressNextBreakAnim = true;
				mw.breakP2Forward(idx);
				while (bz.size() > before)
					mw.gameState.addToPermanentRfp(bz.remove(bz.size() - 1));
				mw.refreshP2BreakLabel();
			}

			/**
			 * Whether one Break Zone card answers these filters — the test the gathering loops
			 * below used to spell out a line at a time, once per zone.
			 */
			private boolean breakZoneCardMatches(CardData card, String element,
					int costVal, String costCmp, int powerVal, String powerCmp,
					boolean inclForwards, boolean inclBackups, boolean inclMonsters, boolean inclSummons,
					String jobFilter, String cardNameFilter, String categoryFilter, String excludeName,
					boolean withoutMulticard) {
				if (card.isForward() && !inclForwards) return false;
				if (card.isBackup()  && !inclBackups)  return false;
				if (card.isMonster() && !inclMonsters) return false;
				if (card.isSummon()  && !inclSummons)  return false;
				if (element != null && !card.containsElement(element)) return false;
				if (!meetsCostConstraint(card.cost(), costVal, costCmp)) return false;
				if (!meetsPowerConstraint(card.power(), powerVal, powerCmp)) return false;
				if (!mw.meetsJobOrCardNameFilter(card, jobFilter, cardNameFilter, null)) return false;
				if (!meetsCategoryFilter(card, categoryFilter)) return false;
				if (excludeName != null && excludeName.equalsIgnoreCase(card.name())) return false;
				return !(withoutMulticard && card.multicard());
			}

			/**
			 * Every Break Zone card a {@link #selectCharactersFromBreakZone} call with these filters
			 * could offer, each indexed into the zone it actually sits in.
			 *
			 * <p>The Break Zone twin of {@link #eligibleCharacters}, split out for the same reason: the
			 * cast-legality check has to know whether a Summon opening "Choose 1 Forward in your Break
			 * Zone." would find one, and asking the method that builds the prompt is what keeps that
			 * answer from drifting away from what the prompt would offer.
			 */
			List<ForwardTarget> eligibleCharactersFromBreakZone(
					int maxCount, boolean upTo, boolean opponentZone, boolean bothZones,
					String condition, String element, int costVal, String costCmp,
					int powerVal, String powerCmp,
					boolean inclForwards, boolean inclBackups, boolean inclMonsters,
					String jobFilter, String cardNameFilter, String categoryFilter, String excludeName, boolean inclSummons,
					String excludeElement, boolean withoutMulticard) {
				List<ForwardTarget> eligible = new ArrayList<>();
				// Kalmia 18-090R shields a player's whole Break Zone from the other player's effects.
				// It is opponent-scoped, so only the far half of a choice can close; the chooser's own
				// half stays open to them.
				boolean[] sides = bothZones ? new boolean[]{true, false} : new boolean[]{isP1 != opponentZone};
				for (boolean sideIsP1 : sides) {
					if (sideIsP1 != isP1 && mw.bzCardsProtectedFromOppChoice(sideIsP1)) continue;
					List<CardData> bz = sideIsP1 ? mw.gameState.getP1BreakZone() : mw.gameState.getP2BreakZone();
					for (int i = 0; i < bz.size(); i++) {
						if (!breakZoneCardMatches(bz.get(i), element, costVal, costCmp, powerVal, powerCmp,
								inclForwards, inclBackups, inclMonsters, inclSummons,
								jobFilter, cardNameFilter, categoryFilter, excludeName, withoutMulticard))
							continue;
						eligible.add(new ForwardTarget(sideIsP1, i, ForwardTarget.CardZone.BREAK_ZONE));
					}
				}
				return eligible;
			}

			/** {@link #eligibleCharactersFromBreakZone} for the filters a {@link TargetSpec} carries. */
			List<ForwardTarget> eligibleCharactersFromBreakZone(TargetSpec spec) {
				return eligibleCharactersFromBreakZone(spec.maxCount(), spec.upTo(), spec.opponentZone(),
						spec.bothZones(), spec.condition(), spec.element(), spec.costVal(), spec.costCmp(),
						spec.powerVal(), spec.powerCmp(), spec.inclForwards(), spec.inclBackups(),
						spec.inclMonsters(), spec.jobFilter(), spec.cardNameFilter(), spec.categoryFilter(),
						spec.excludeName(), spec.inclSummons(), spec.excludeElement(), spec.withoutMulticard());
			}

			@Override
			public List<ForwardTarget> selectCharactersFromBreakZone(
					int maxCount, boolean upTo, boolean opponentZone, boolean bothZones,
					String condition, String element, int costVal, String costCmp,
					int powerVal, String powerCmp,
					boolean inclForwards, boolean inclBackups, boolean inclMonsters,
					String jobFilter, String cardNameFilter, String categoryFilter, String excludeName, boolean inclSummons,
					String excludeElement, boolean withoutMulticard) {
				if (bothZones) {
					List<CardData> p1bz = mw.gameState.getP1BreakZone();
					List<CardData> p2bz = mw.gameState.getP2BreakZone();
					List<ForwardTarget> eligible = eligibleCharactersFromBreakZone(
							maxCount, upTo, opponentZone, bothZones, condition, element, costVal, costCmp,
							powerVal, powerCmp, inclForwards, inclBackups, inclMonsters,
							jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons,
							excludeElement, withoutMulticard);
					// Flat list for display, in the same order: an index into it is what the tabbed
					// dialog hands back, and what the mapping at the end of this branch undoes.
					List<CardData> combined = new ArrayList<>();
					for (ForwardTarget t : eligible) combined.add((t.isP1() ? p1bz : p2bz).get(t.idx()));
					String costLabel  = formatCostFilterLabel(costVal, costCmp);
					String powerLabel = powerVal >= 0 ? " of power " + powerVal + (powerCmp != null ? " or " + powerCmp : "") : "";
					String typeLabel  = breakZoneTypeLabel(inclForwards, inclBackups, inclMonsters, inclSummons, maxCount);
					String title = "Choose " + (upTo ? "up to " : "") + maxCount
							+ (element != null ? " " + element : "")
							+ typeLabel + costLabel + powerLabel
							+ " from either player's Break Zone";
					if (!isP1) {
						if (eligible.isEmpty()) return List.of();
						List<ForwardTarget> copy = new ArrayList<>(eligible);
						java.util.Collections.shuffle(copy);
						List<ForwardTarget> picked =
								List.copyOf(copy.subList(0, Math.min(maxCount, copy.size())));
						picked.forEach(t -> logEntry("[AI] chose " + combined.get(eligible.indexOf(t)).name()));
						return picked;
					}
					// For the dialog, we need eligible targets that index into combined[]
					// Re-index eligible so idx refers to combined list position
					List<ForwardTarget> reindexed = new ArrayList<>();
					for (int ci = 0; ci < eligible.size(); ci++) {
						reindexed.add(new ForwardTarget(eligible.get(ci).isP1(), ci, ForwardTarget.CardZone.BREAK_ZONE));
					}
					List<ForwardTarget> chosen = mw.showBreakZoneSelectDialogTabbed(reindexed, combined, maxCount, upTo, title);
					// Map chosen reindexed targets back to original targets so callers use real BZ indices
					List<ForwardTarget> result = new ArrayList<>();
					for (ForwardTarget t : chosen) result.add(eligible.get(t.idx()));
					return result;
				}
				boolean useP1Zone = isP1 != opponentZone;
				List<CardData> bz = useP1Zone
						? mw.gameState.getP1BreakZone() : mw.gameState.getP2BreakZone();
				List<ForwardTarget> eligible = eligibleCharactersFromBreakZone(
						maxCount, upTo, opponentZone, bothZones, condition, element, costVal, costCmp,
						powerVal, powerCmp, inclForwards, inclBackups, inclMonsters,
						jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons,
						excludeElement, withoutMulticard);
				String costLabel  = formatCostFilterLabel(costVal, costCmp);
				String powerLabel = powerVal >= 0 ? " of power " + powerVal + (powerCmp != null ? " or " + powerCmp : "") : "";
				String typeLabel  = breakZoneTypeLabel(inclForwards, inclBackups, inclMonsters, inclSummons, maxCount);
				String title = "Choose " + (upTo ? "up to " : "") + maxCount
						+ (element != null ? " " + element : "")
						+ typeLabel + costLabel + powerLabel
						+ " in " + (opponentZone ? "opponent's" : "your") + " Break Zone";
				if (!isP1) {
					if (eligible.isEmpty()) return List.of();
					List<ForwardTarget> copy = new ArrayList<>(eligible);
					java.util.Collections.shuffle(copy);
					List<ForwardTarget> picked =
							List.copyOf(copy.subList(0, Math.min(maxCount, copy.size())));
					picked.forEach(t -> logEntry("[AI] chose " + bz.get(t.idx()).name()));
					return picked;
				}
				return mw.showBreakZoneSelectDialog(eligible, bz, maxCount, upTo, title);
			}

			@Override public void cancelStackEntry() {
				StackEntry chosen = chooseSummonOrAutoAbilityOnStack("cancel");
				if (chosen == null) return;
				if (mw.cancelStackEntry(chosen)) {
					String type = chosen.isSummon() ? "Summon" : "auto-ability";
					logEntry("Effect: " + chosen.source().name() + "'s " + type + " effect will be cancelled");
				}
			}

			@Override public void chooseStackEntryZeroItsDamageThisTurn() {
				StackEntry chosen = chooseSummonOrAutoAbilityOnStack("blank the damage of");
				if (chosen == null) return;
				// Keyed on the source card, which is how both damage paths identify whatever is
				// dealing right now (mw.currentAbilitySource).
				mw.damageZeroedSourcesThisTurn.add(chosen.source());
				String type = chosen.isSummon() ? "Summon" : "auto-ability";
				logEntry("Effect: " + chosen.source().name() + "'s " + type
						+ " deals 0 damage this turn");
			}

			/**
			 * Prompts for one Summon or auto-ability on the Stack, or returns {@code null} when
			 * there is none to pick or the player cancelled. {@code verb} names the action in the
			 * dialog so the same chooser can serve Y'shtola's cancel and Neon's Runic.
			 *
			 * <p>Action abilities are excluded: every printing of this choice says "Summon or
			 * auto-ability" and means exactly those two.
			 */
			private StackEntry chooseSummonOrAutoAbilityOnStack(String verb) {
				List<StackEntry> targets = mw.gameState.getStack().stream()
						.filter(e -> e.isSummon() || e.isAutoAbility())
						.filter(e -> !mw.stackEntryProtectedFromCancel(e))
						.collect(java.util.stream.Collectors.toList());
				if (targets.isEmpty()) {
					logEntry("No Summons or auto-abilities on the stack to " + verb);
					return null;
				}
				if (targets.size() == 1) return targets.get(0);
				if (isP1) {
					String[] options = new String[targets.size()];
					for (int i = 0; i < targets.size(); i++) {
						StackEntry e = targets.get(i);
						String type  = e.isSummon() ? "Summon" : "Auto";
						String owner = e.isP1() ? "P1" : "P2";
						options[i] = e.source().name() + " (" + type + ", " + owner + ")";
					}
					Object sel = JOptionPane.showInputDialog(mw.frame,
							"Choose 1 Summon or auto-ability to " + verb + ":",
							"Choose Effect", JOptionPane.PLAIN_MESSAGE, null, options, options[0]);
					if (sel == null) return null;
					int idx = java.util.Arrays.asList(options).indexOf(sel.toString());
					return idx < 0 ? null : targets.get(idx);
				}
				// AI: target the most recently pushed opponent (P1) entry
				StackEntry chosen = targets.stream().filter(e -> e.isP1())
						.reduce((a, b) -> b).orElse(targets.get(targets.size() - 1));
				logEntry("[AI] Chose to " + verb + ": " + chosen.source().name());
				return chosen;
			}

			@Override public void cancelTriggeringSummon() {
				List<StackEntry> stack = mw.gameState.getStack();
				// Top down: the trigger resolving now was pushed above the Summon that fired it,
				// so the first Summon found walking back down is that one. A Summon cast in
				// response would sit above it and be found first, which is also correct — that
				// is the Summon whose cast triggered the copy resolving now.
				for (int i = stack.size() - 1; i >= 0; i--) {
					StackEntry e = stack.get(i);
					if (!e.isSummon()) continue;
					if (mw.cancelStackEntry(e))
						logEntry("Effect: \"" + e.source().name() + "\"'s effect will be cancelled");
					return;
				}
				logEntry("No Summon on the stack to cancel");
			}

			@Override public void cancelAutoAbilityAndDamageSourceIfForward(int damage) {
				List<StackEntry> targets = mw.gameState.getStack().stream()
						.filter(StackEntry::isAutoAbility)
						.filter(e -> !mw.stackEntryProtectedFromCancel(e))
						.collect(java.util.stream.Collectors.toList());
				if (targets.isEmpty()) {
					logEntry("No auto-abilities on the stack to cancel");
					return;
				}
				StackEntry chosen;
				if (targets.size() == 1) {
					chosen = targets.get(0);
				} else if (isP1) {
					String[] options = new String[targets.size()];
					for (int i = 0; i < targets.size(); i++) {
						StackEntry e = targets.get(i);
						options[i] = e.source().name() + " (Auto, " + (e.isP1() ? "P1" : "P2") + ")";
					}
					Object sel = JOptionPane.showInputDialog(mw.frame,
							"Choose 1 auto-ability to cancel:",
							"Cancel Effect", JOptionPane.PLAIN_MESSAGE, null, options, options[0]);
					if (sel == null) return;
					int idx = java.util.Arrays.asList(options).indexOf(sel.toString());
					if (idx < 0) return;
					chosen = targets.get(idx);
				} else {
					// AI: prefer the most recently pushed P1 entry
					chosen = targets.stream().filter(e -> e.isP1())
							.reduce((a, b) -> b).orElse(targets.get(targets.size() - 1));
					logEntry("[AI] Chose to cancel: " + chosen.source().name());
				}
				if (mw.cancelStackEntry(chosen))
					logEntry("Effect: " + chosen.source().name() + "'s auto-ability effect will be cancelled");

				if (!chosen.source().isForward()) {
					logEntry(chosen.source().name() + " is not a Forward — no damage");
					return;
				}
				CardData src = chosen.source();
				List<CardData> fwds = chosen.isP1() ? mw.p1ForwardCards : mw.p2ForwardCards;
				int fwdIdx = fwds.indexOf(src);
				if (fwdIdx < 0) {
					logEntry(src.name() + " is no longer on the field — no damage");
					return;
				}
				logEntry(src.name() + " is a Forward — dealing " + damage + " damage");
				if (chosen.isP1()) damageP1Forward(fwdIdx, damage);
				else               damageP2Forward(fwdIdx, damage);
			}

			@Override public void cancelFilteredAbilityOnStack(java.util.function.Predicate<StackEntry> filter, String prompt, boolean requiresControllerTarget) {
				java.util.function.Predicate<StackEntry> fullFilter = requiresControllerTarget
						? ActionResolver.withControllerTargetRequirement(filter, isP1)
						: filter;
				List<StackEntry> targets = mw.gameState.getStack().stream()
						.filter(fullFilter)
						.filter(e -> !mw.stackEntryProtectedFromCancel(e))
						.collect(java.util.stream.Collectors.toList());
				if (targets.isEmpty()) {
					logEntry("No matching abilities on the stack to cancel");
					return;
				}
				StackEntry chosen;
				if (targets.size() == 1) {
					chosen = targets.get(0);
				} else if (isP1) {
					String[] options = new String[targets.size()];
					for (int i = 0; i < targets.size(); i++) options[i] = describeStackEntry(targets.get(i));
					Object sel = JOptionPane.showInputDialog(mw.frame,
							prompt, "Cancel Effect", JOptionPane.PLAIN_MESSAGE, null, options, options[0]);
					if (sel == null) return;
					int idx = java.util.Arrays.asList(options).indexOf(sel.toString());
					if (idx < 0) return;
					chosen = targets.get(idx);
				} else {
					chosen = targets.stream().filter(e -> e.isP1())
							.reduce((a, b) -> b).orElse(targets.get(targets.size() - 1));
					logEntry("[AI] Chose to cancel: " + chosen.source().name());
				}
				if (mw.cancelStackEntry(chosen)) {
					String type = chosen.isSummon() ? "Summon" : chosen.isAutoAbility() ? "auto-ability"
							: chosen.isSpecialAbility() ? "special ability" : "action ability";
					logEntry("Effect: " + chosen.source().name() + "'s " + type + " effect will be cancelled");
				}
			}

			/**
			 * The multi-pick sibling of {@link #cancelFilteredAbilityOnStack}. The list is
			 * multi-selection and starts empty: "any number" includes none, so an empty confirm and
			 * a cancelled dialog both mean nothing is cancelled, and neither falls back to a
			 * default pick the way the single-target form does.
			 *
			 * <p>The AI cancels everything on the far side of the field and nothing of its own —
			 * its entries are there because it wants them to resolve.
			 */
			@Override public void cancelAnyNumberOfAbilitiesOnStack(
					java.util.function.Predicate<StackEntry> filter, String prompt) {
				List<StackEntry> targets = mw.gameState.getStack().stream()
						.filter(filter)
						.filter(e -> !mw.stackEntryProtectedFromCancel(e))
						.collect(java.util.stream.Collectors.toList());
				if (targets.isEmpty()) {
					logEntry("No matching abilities on the stack to cancel");
					return;
				}
				List<StackEntry> chosen;
				if (isP1) {
					String[] options = new String[targets.size()];
					for (int i = 0; i < targets.size(); i++) options[i] = describeStackEntry(targets.get(i));
					JList<String> list = new JList<>(options);
					list.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
					int ok = JOptionPane.showConfirmDialog(mw.frame,
							new Object[]{prompt, new JScrollPane(list)},
							"Cancel Effects", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
					if (ok != JOptionPane.OK_OPTION) { logEntry("Cancel — none chosen"); return; }
					chosen = new ArrayList<>();
					for (int i : list.getSelectedIndices()) chosen.add(targets.get(i));
				} else {
					chosen = targets.stream().filter(e -> e.isP1() != isP1).toList();
					chosen.forEach(e -> logEntry("[AI] Chose to cancel: " + e.source().name()));
				}
				if (chosen.isEmpty()) { logEntry("Cancel — none chosen"); return; }
				for (StackEntry e : chosen) {
					if (mw.cancelStackEntry(e))
						logEntry("Effect: " + e.source().name() + "'s "
								+ stackEntryKind(e) + " effect will be cancelled");
				}
			}

			/** "Name (Kind, seat)" — the one-line label the cancel dialogs list an entry under. */
			private String describeStackEntry(StackEntry e) {
				String type = e.isSummon() ? "Summon" : e.isAutoAbility() ? "Auto"
						: e.isSpecialAbility() ? "Special" : "Action";
				return e.source().name() + " (" + type + ", " + (e.isP1() ? "P1" : "P2") + ")";
			}

			/** The entry's kind as the log spells it out. */
			private String stackEntryKind(StackEntry e) {
				return e.isSummon() ? "Summon" : e.isAutoAbility() ? "auto-ability"
						: e.isSpecialAbility() ? "special ability" : "action ability";
			}

			@Override public void cancelFilteredAbilityOnStackUnlessOpponentPays(java.util.function.Predicate<StackEntry> filter, String prompt, int cost) {
				List<StackEntry> targets = mw.gameState.getStack().stream()
						.filter(filter)
						.filter(e -> !mw.stackEntryProtectedFromCancel(e))
						.collect(java.util.stream.Collectors.toList());
				if (targets.isEmpty()) {
					logEntry("No matching abilities on the stack to threaten");
					return;
				}
				StackEntry chosen;
				if (targets.size() == 1) {
					chosen = targets.get(0);
				} else if (isP1) {
					String[] options = new String[targets.size()];
					for (int i = 0; i < targets.size(); i++) options[i] = describeStackEntry(targets.get(i));
					Object sel = JOptionPane.showInputDialog(mw.frame,
							prompt, "Cancel Effect", JOptionPane.PLAIN_MESSAGE, null, options, options[0]);
					if (sel == null) return;
					int idx = java.util.Arrays.asList(options).indexOf(sel.toString());
					if (idx < 0) return;
					chosen = targets.get(idx);
				} else {
					chosen = targets.stream().filter(e -> e.isP1())
							.reduce((a, b) -> b).orElse(targets.get(targets.size() - 1));
					logEntry("[AI] Chose to threaten: " + chosen.source().name());
				}
				String type = chosen.isSummon() ? "Summon" : chosen.isAutoAbility() ? "auto-ability"
						: chosen.isSpecialAbility() ? "special ability" : "action ability";
				int[] paidHolder = {-1};
				String label = chosen.source().name() + " — pay 《" + cost + "》 or its effect is cancelled";
				mw.autoAbilityTriggers.showAutoAbilityPaymentDialog(label, cost, cost, !isP1, 0, paid -> paidHolder[0] = paid, null);
				if (paidHolder[0] < cost) {
					if (mw.cancelStackEntry(chosen))
						logEntry("Effect: opponent declined to pay 《" + cost + "》 — " + chosen.source().name() + "'s " + type + " effect will be cancelled");
				} else {
					logEntry("Effect: opponent paid 《" + cost + "》 — " + chosen.source().name() + "'s " + type + " effect proceeds");
				}
			}

			@Override public void cancelChosenSelectionUnlessOpponentPays(int cost) {
				String src = mw.currentAbilitySource != null ? mw.currentAbilitySource.name() : "Ability";
				String label = src + " — pay 《" + cost + "》 or the effect choosing your Character(s) is cancelled";
				int[] paidHolder = {-1};
				mw.autoAbilityTriggers.showAutoAbilityPaymentDialog(label, cost, cost, !isP1, 0, paid -> paidHolder[0] = paid, null);
				if (paidHolder[0] < cost) {
					mw.lastChosenSelectionCancelled = true;
					logEntry("Effect: opponent declined to pay 《" + cost + "》 — selection cancelled");
				} else {
					logEntry("Effect: opponent paid 《" + cost + "》 — selection proceeds");
				}
			}

			@Override public void cancelChosenSelectionUnlessOpponentPaysOrCrystal(int cpCost, int crystalCost) {
				String src = mw.currentAbilitySource != null ? mw.currentAbilitySource.name() : "Ability";
				String label = src + " — pay 《" + cpCost + "》 or " + crystalCost + " Crystal"
						+ (crystalCost == 1 ? "" : "s") + " or the effect choosing your Character(s) is cancelled";
				int[] paidHolder = {-1};
				boolean[] paidByCrystal = {false};
				mw.autoAbilityTriggers.showAutoAbilityPaymentDialog(label, cpCost, cpCost, !isP1, crystalCost,
						paid -> paidHolder[0] = paid,
						() -> paidByCrystal[0] = true);
				if (paidByCrystal[0]) {
					logEntry("Effect: opponent paid " + crystalCost + " Crystal" + (crystalCost == 1 ? "" : "s") + " — selection proceeds");
				} else if (paidHolder[0] < cpCost) {
					mw.lastChosenSelectionCancelled = true;
					logEntry("Effect: opponent declined to pay — selection cancelled");
				} else {
					logEntry("Effect: opponent paid 《" + cpCost + "》 — selection proceeds");
				}
			}

			@Override public void cancelChosenSelection() {
				mw.lastChosenSelectionCancelled = true;
				logEntry("Effect: the effect choosing your Character(s) is cancelled");
			}

			@Override public void revealTopDeckCancelChosenIfType(String type) {
				Deque<CardData> deck = isP1 ? mw.gameState.getP1MainDeck() : mw.gameState.getP2MainDeck();
				if (deck.isEmpty()) { logEntry("Reveal: your deck is empty — no cancel"); return; }
				CardData top = deck.peekFirst();   // revealed; stays on top
				logEntry((isP1 ? "" : "[P2] ") + "Revealed top of deck: " + top.name() + " (" + top.type() + ")");
				if (ComputerPlayer.cardMatchesType(top, type)) {
					mw.lastChosenSelectionCancelled = true;
					logEntry("Revealed card is a " + type + " — the effect choosing your Character is cancelled");
				} else {
					logEntry("Revealed card is not a " + type + " — the effect proceeds");
				}
			}

			@Override public void millTopDeckCancelChosenIfNotType(String type) {
				Deque<CardData> deck = isP1 ? mw.gameState.getP1MainDeck() : mw.gameState.getP2MainDeck();
				if (deck.isEmpty()) { logEntry("Your deck is empty — nothing to put into the Break Zone"); return; }
				CardData top = deck.pollFirst();
				logEntry((isP1 ? "" : "[P2] ") + top.name() + " (" + top.type() + ") → Break Zone (top of deck)");
				mw.addToBreakZone(top);
				if (isP1) { mw.refreshP1DeckLabel(); mw.refreshP1BreakLabel(); }
				else      { mw.refreshP2DeckLabel(); mw.refreshP2BreakLabel(); }
				if (!ComputerPlayer.cardMatchesType(top, type)) {
					mw.lastChosenSelectionCancelled = true;
					logEntry("The milled card is not a " + type + " — the effect choosing your Character(s) is cancelled");
				} else {
					logEntry("The milled card is a " + type + " — the effect proceeds");
				}
			}

			@Override public void millTopDeckBothCancelChosenIfSameType() {
				CardData mine  = millTopForCancelCompare(isP1);
				CardData theirs = millTopForCancelCompare(!isP1);
				if (mine == null || theirs == null) {
					logEntry("A deck was empty — no pair to compare, the effect proceeds");
					return;
				}
				if (mine.type() != null && mine.type().equalsIgnoreCase(theirs.type())) {
					mw.lastChosenSelectionCancelled = true;
					logEntry("Both milled cards are " + mine.type() + "s — the effect choosing your "
							+ "Character(s) is cancelled");
				} else {
					logEntry("The milled cards differ in card type — the effect proceeds");
				}
			}

			/**
			 * Mills {@code sideIsP1}'s top deck card into their Break Zone and returns it, or
			 * {@code null} when that deck is empty.
			 */
			private CardData millTopForCancelCompare(boolean sideIsP1) {
				Deque<CardData> deck = sideIsP1 ? mw.gameState.getP1MainDeck() : mw.gameState.getP2MainDeck();
				if (deck.isEmpty()) return null;
				CardData top = deck.pollFirst();
				logEntry((sideIsP1 ? "" : "[P2] ") + top.name() + " (" + top.type() + ") → Break Zone (top of deck)");
				mw.addToBreakZone(top);   // routed to its owner's Break Zone by card identity
				if (sideIsP1) { mw.refreshP1DeckLabel(); mw.refreshP1BreakLabel(); }
				else          { mw.refreshP2DeckLabel(); mw.refreshP2BreakLabel(); }
				return top;
			}

			@Override public void cancelChosenSelectionUnlessOpponentDiscards(int count) {
				String src = mw.currentAbilitySource != null ? mw.currentAbilitySource.name() : "Ability";
				boolean opponentIsP1 = !isP1;   // the player being asked to discard
				List<CardData> oppHand = opponentIsP1 ? mw.gameState.getP1Hand() : mw.gameState.getP2Hand();

				// Can't discard the full amount → automatic cancel, no choice offered.
				if (oppHand.size() < count) {
					mw.lastChosenSelectionCancelled = true;
					logEntry("Effect: opponent cannot discard " + count + " card(s) — selection cancelled");
					return;
				}

				boolean discarded;
				if (opponentIsP1) {
					// Human opponent decides whether to pay the discard cost.
					int choice = mw.showEffectOptionDialog(
							src + " — discard " + count + " card" + (count == 1 ? "" : "s")
									+ " or the effect choosing your Character(s) is cancelled?",
							"Discard or Cancel", new Object[]{"Discard", "Decline"});
					if (choice == 0) {
						mw.showForcedDiscardDialog(count, true);   // modal; discards exactly `count`
						discarded = true;
					} else {
						discarded = false;
					}
				} else {
					// P2 AI opponent: discard its worst `count` cards to keep its effect.
					for (int i = 0; i < count; i++) {
						int idx = MainWindow.pickWorstHandCard0(mw.gameState.getP2Hand());
						CardData d = mw.playerBreakFromHand(false, idx);
						if (d != null) logEntry("[P2] Discards " + d.name() + " to keep its effect");
					}
					mw.refreshP2HandCountLabel();
					mw.refreshP2BreakLabel();
					discarded = true;
				}

				if (!discarded) {
					mw.lastChosenSelectionCancelled = true;
					logEntry("Effect: opponent declined to discard — selection cancelled");
				} else {
					logEntry("Effect: opponent discarded " + count + " card(s) — selection proceeds");
				}
			}

			@Override public void redirectChosenTarget(TargetRedirect spec, CardData source) {
				List<StackEntry> eligible = mw.gameState.getStack().stream()
						.filter(mw.redirectEligibility(spec, source, isP1))
						.collect(java.util.stream.Collectors.toList());
				if (eligible.isEmpty()) {
					logEntry("No Summon or ability on the stack is choosing what " + source.name()
							+ " can redirect");
					return;
				}
				StackEntry chosen = pickRedirectEntry(eligible);
				if (chosen == null) return;

				ForwardTarget replacement = switch (spec.replacement()) {
					case TO_SOURCE              -> sourceAsRedirectTarget(chosen, source);
					case OWN_FORWARD_OF_ELEMENT -> askForRedirectTarget(chosen,
							mw.redirectCandidates(chosen, isP1, spec.newTargetElement(), source),
							"another " + spec.newTargetElement() + " Forward you control");
					case ANY_CHARACTER          -> askForRedirectTarget(chosen,
							mw.redirectCandidatesAnywhere(chosen, currentTargetOf(chosen)),
							"another Character");
				};
				if (replacement == null) return;

				CardData newCard = mw.fieldCardDataOrNull(replacement);
				mw.redirectStackEntryTargets(chosen, List.of(replacement));
				logEntry("Effect: " + chosen.source().name() + "'s " + entryTypeLabel(chosen)
						+ " is now choosing " + (newCard != null ? newCard.name() : "a new target")
						+ " instead");
			}

			/** Lets the player settle which eligible entry to redirect; the AI takes the topmost. */
			private StackEntry pickRedirectEntry(List<StackEntry> eligible) {
				if (eligible.size() == 1) return eligible.get(0);
				if (!isP1) {
					StackEntry pick = eligible.get(eligible.size() - 1);
					logEntry("[AI] Chose to redirect: " + pick.source().name());
					return pick;
				}
				String[] options = new String[eligible.size()];
				for (int i = 0; i < eligible.size(); i++) {
					StackEntry e = eligible.get(i);
					options[i] = e.source().name() + " (" + entryTypeLabel(e) + ", "
							+ (e.isP1() ? "P1" : "P2") + ")";
				}
				int idx = mw.showEffectOptionDialog(
						"Choose 1 Summon or ability to redirect:", "Redirect Target", options);
				return idx >= 0 && idx < eligible.size() ? eligible.get(idx) : null;
			}

			/** The card {@code entry} is currently choosing — what "another" excludes. */
			private CardData currentTargetOf(StackEntry entry) {
				List<ForwardTarget> chosen = entry.preSelectedTargets();
				return chosen == null || chosen.isEmpty() ? null
						: mw.fieldCardDataOrNull(chosen.get(0));
			}

			/**
			 * The source card's own slot, or {@code null} when it cannot legally be chosen by
			 * {@code entry} — the "if possible" on Edge and Calbrena, which covers the source
			 * having left the field as well as protection against being chosen.
			 */
			private ForwardTarget sourceAsRedirectTarget(StackEntry entry, CardData source) {
				ForwardTarget slot = mw.redirectSourceSlot(entry, source, isP1);
				if (slot == null)
					logEntry(source.name() + " cannot be chosen by " + entry.source().name()
							+ "'s effect — no redirect");
				return slot;
			}

			/**
			 * The optional player pick shared by Faris, Wicked Mask and Aemo. Returns {@code null}
			 * when nothing qualifies or the player declines — both legal outcomes of "You may".
			 */
			private ForwardTarget askForRedirectTarget(StackEntry entry,
					List<ForwardTarget> candidates, String poolDescription) {
				if (candidates.isEmpty()) {
					logEntry("No " + poolDescription + " is a valid choice — no redirect");
					return null;
				}
				if (!isP1) {
					ForwardTarget pick = candidates.get(0);
					logEntry("[AI] redirects onto " + mw.fieldCardDataOrNull(pick).name());
					return pick;
				}
				// "You may" — declining is a legal outcome, so the list carries its own opt-out.
				String[] options = new String[candidates.size() + 1];
				for (int i = 0; i < candidates.size(); i++) {
					ForwardTarget t = candidates.get(i);
					options[i] = mw.fieldCardDataOrNull(t).name()
							+ (t.isP1() ? " (yours)" : " (opponent's)");
				}
				options[candidates.size()] = "Don't redirect";
				int idx = mw.showEffectOptionDialog(
						"Choose the new target for " + entry.source().name() + "'s effect:",
						"Redirect Target", options);
				if (idx < 0 || idx >= candidates.size()) {
					logEntry("Declined to redirect " + entry.source().name() + "'s effect");
					return null;
				}
				return candidates.get(idx);
			}

			private String entryTypeLabel(StackEntry e) {
				return e.isSummon() ? "Summon" : e.isAutoAbility() ? "auto-ability"
						: e.isSpecialAbility() ? "special ability" : "action ability";
			}

			@Override public void forceTargetToBreakZone(ForwardTarget t) {
				mw.pendingCostBreakDestLabel = t.isP1() ? mw.p1BreakLabel : mw.p2BreakLabel;
				switch (t.zone()) {
					case FORWARD -> { if (t.isP1()) breakP1Forward(t.idx()); else breakP2Forward(t.idx()); }
					case BACKUP  -> { if (t.isP1()) mw.autoAbilityTriggers.breakP1BackupSlot(t.idx()); else mw.breakP2BackupSlot(t.idx()); }
					case MONSTER -> { if (t.isP1()) mw.autoAbilityTriggers.breakP1MonsterSlot(t.idx()); else mw.breakP2MonsterSlot(t.idx()); }
				}
			}

			/**
			 * The row is taken whole off the board rather than through {@link #selectCharacters}:
			 * the effect divides <em>all</em> the Forwards its opponent controls, so nothing is
			 * chosen, no "cannot be chosen" shield applies and no chosen-by-opponent trigger fires.
			 *
			 * <p>The two questions are put in the order the card prints them and each is answered
			 * by a different seat, so on a networked table one client sends the division and waits
			 * for the selection while the other does the reverse. An answer that does not survive
			 * its legality check comes back empty, and the effect then does nothing at all — a
			 * half-applied division would put the two boards further apart than an abandoned one.
			 */
			@Override public void divideOpponentForwardsIntoGroups(int groupCount) {
				List<CardData> oppForwards =
						new ArrayList<>(isP1 ? mw.p2ForwardCards : mw.p1ForwardCards);
				if (oppForwards.isEmpty()) {
					logEntry("Divide into groups — opponent controls no Forwards");
					return;
				}
				List<Integer> assignment =
						mw.divideForwardsIntoGroups(isP1, oppForwards, groupCount);
				if (!MainWindow.isGroupAssignment(assignment, oppForwards.size(), groupCount)) {
					logEntry("Divide into groups — no division was made");
					return;
				}
				for (int g = 0; g < groupCount; g++) {
					StringBuilder members = new StringBuilder();
					for (int i = 0; i < oppForwards.size(); i++) {
						if (assignment.get(i) != g) continue;
						if (members.length() > 0) members.append(", ");
						members.append(oppForwards.get(i).name());
					}
					logEntry("Group " + (g + 1) + ": "
							+ (members.length() == 0 ? "(no Forwards)" : members));
				}

				int kept = mw.selectGroupToKeep(!isP1, oppForwards, assignment, groupCount);
				if (kept < 0) {
					logEntry("Divide into groups — no group was chosen");
					return;
				}
				logEntry("[Opponent] Keeps group " + (kept + 1));

				mw.putUnkeptForwardGroupsIntoBreakZone(this, !isP1, assignment, groupCount, kept);
			}

			@Override public void opponentMillCards(int count) {
				Deque<CardData> deck = mw.gameState.getP2MainDeck();
				JLayeredPane lp    = mw.frame.getRootPane().getLayeredPane();
				Point start = SwingUtilities.convertPoint(
						mw.p2DeckLabel, mw.p2DeckLabel.getWidth() / 2, mw.p2DeckLabel.getHeight() / 2, lp);
				Point end   = SwingUtilities.convertPoint(
						mw.p2BreakLabel, mw.p2BreakLabel.getWidth() / 2, mw.p2BreakLabel.getHeight() / 2, lp);
				BufferedImage img = CardAnimation.toARGB(
						mw.loadCardbackImage(), CardAnimation.CARD_W, CardAnimation.CARD_H);
				int milled = 0;
				for (int i = 0; i < count && !deck.isEmpty(); i++) {
					CardData card = deck.pop();
					mw.addToBreakZone(card);
					logEntry("[P2] Mill: \"" + card.name() + "\" → Break Zone");
					mw.cardSlideAnimator.startSlide(img, start, end, i * 5);
					milled++;
				}
				if (milled > 0) {
					mw.refreshP2DeckLabel();
					mw.refreshP2BreakLabel();
				}
			}

			@Override public void opponentMillIfSameElementDraw(int millCount, int drawCount) {
				Deque<CardData> oppDeck = isP1 ? mw.gameState.getP2MainDeck() : mw.gameState.getP1MainDeck();
				JLayeredPane lp = mw.frame.getRootPane().getLayeredPane();
				JLabel deckLbl  = isP1 ? mw.p2DeckLabel  : mw.p1DeckLabel;
				JLabel breakLbl = isP1 ? mw.p2BreakLabel : mw.p1BreakLabel;
				Point start = SwingUtilities.convertPoint(deckLbl,  deckLbl.getWidth() / 2,  deckLbl.getHeight() / 2,  lp);
				Point end   = SwingUtilities.convertPoint(breakLbl, breakLbl.getWidth() / 2, breakLbl.getHeight() / 2, lp);
				BufferedImage img = CardAnimation.toARGB(
						mw.loadCardbackImage(), CardAnimation.CARD_W, CardAnimation.CARD_H);
				List<CardData> milled = new ArrayList<>();
				for (int i = 0; i < millCount && !oppDeck.isEmpty(); i++) {
					CardData card = oppDeck.pop();
					(isP1 ? mw.gameState.getP2BreakZone() : mw.gameState.getP1BreakZone()).add(card);
					logEntry((isP1 ? "[P2] " : "[P1] ") + "Mill: \"" + card.name() + "\" → Break Zone");
					mw.cardSlideAnimator.startSlide(img, start, end, i * 5);
					milled.add(card);
				}
				if (!milled.isEmpty()) {
					if (isP1) { mw.refreshP2DeckLabel(); mw.refreshP2BreakLabel(); }
					else      { mw.refreshP1DeckLabel(); mw.refreshP1BreakLabel(); }
				}
				if (milled.size() < 2) return;
				// Check if all milled cards share at least one common element
				boolean sameElement = false;
				for (String e : List.of("fire","ice","wind","earth","lightning","water","light","dark")) {
					boolean allHave = true;
					for (CardData c : milled) if (!c.containsElement(e)) { allHave = false; break; }
					if (allHave) { sameElement = true; break; }
				}
				if (sameElement) {
					logEntry("All milled cards share an element — draw " + drawCount);
					drawCards(drawCount);
				} else {
					logEntry("Milled cards do not share an element — no draw");
				}
			}

			@Override public void millCards(int count) {
				Deque<CardData> deck = mw.gameState.getP1MainDeck();
				JLayeredPane lp    = mw.frame.getRootPane().getLayeredPane();
				Point start = SwingUtilities.convertPoint(
						mw.p1DeckLabel, mw.p1DeckLabel.getWidth() / 2, mw.p1DeckLabel.getHeight() / 2, lp);
				Point end   = SwingUtilities.convertPoint(
						mw.p1BreakLabel, mw.p1BreakLabel.getWidth() / 2, mw.p1BreakLabel.getHeight() / 2, lp);
				BufferedImage img = CardAnimation.toARGB(
						mw.loadCardbackImage(), CardAnimation.CARD_W, CardAnimation.CARD_H);
				int milled = 0;
				for (int i = 0; i < count && !deck.isEmpty(); i++) {
					CardData card = deck.pop();
					mw.addToBreakZone(card);
					logEntry("[P1] Mill: \"" + card.name() + "\" → Break Zone");
					mw.cardSlideAnimator.startSlide(img, start, end, i * 5);
					milled++;
				}
				if (milled > 0) {
					mw.refreshP1DeckLabel();
					mw.refreshP1BreakLabel();
				}
			}

			@Override public void revealOpponentHand() {
				List<CardData> hand = mw.gameState.getP2Hand();
				if (hand.isEmpty()) {
					logEntry("Opponent's hand is empty.");
					return;
				}
				StringBuilder sb = new StringBuilder("Opponent's hand revealed: ");
				for (int i = 0; i < hand.size(); i++) {
					if (i > 0) sb.append(", ");
					sb.append(hand.get(i).name());
				}
				logEntry(sb.toString());

				JDialog dlg = new JDialog(mw.frame, "Opponent's Hand (" + hand.size() + " cards)", false);
				dlg.setResizable(false);
				dlg.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

				JPanel cardsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
				for (CardData cd : hand) {
					JLabel lbl = new JLabel("...", SwingConstants.CENTER);
					lbl.setPreferredSize(new Dimension(CARD_W, CARD_H));
					lbl.setMinimumSize(new Dimension(CARD_W, CARD_H));
					lbl.setOpaque(true);
					lbl.setBackground(Color.DARK_GRAY);
					lbl.setBorder(BorderFactory.createLineBorder(new Color(160, 110, 220), 1));
					lbl.addMouseListener(new MouseAdapter() {
						@Override public void mouseEntered(MouseEvent e) { mw.showZoomAt(cd.imageUrl()); }
						@Override public void mouseExited(MouseEvent e)  { mw.hideZoom(); }
					});
					new SwingWorker<ImageIcon, Void>() {
						@Override protected ImageIcon doInBackground() throws Exception {
							Image img = ImageCache.load(cd.imageUrl());
							return img == null ? null
									: new ImageIcon(img.getScaledInstance(CARD_W, CARD_H, Image.SCALE_SMOOTH));
						}
						@Override protected void done() {
							try {
								ImageIcon icon = get();
								if (icon != null) { lbl.setIcon(icon); lbl.setText(null); }
							} catch (InterruptedException | ExecutionException ignored) {}
						}
					}.execute();

					JPanel wrapper = new JPanel(new BorderLayout(0, 4));
					wrapper.setBackground(cardsPanel.getBackground());
					JLabel nameLabel = new JLabel(cd.name(), SwingConstants.CENTER);
					nameLabel.setFont(FontLoader.loadPixelFont(9));
					nameLabel.setPreferredSize(new Dimension(CARD_W, 18));
					wrapper.add(lbl,       BorderLayout.CENTER);
					wrapper.add(nameLabel, BorderLayout.SOUTH);
					cardsPanel.add(wrapper);
				}

				JScrollPane scrollPane = new JScrollPane(cardsPanel,
						JScrollPane.VERTICAL_SCROLLBAR_NEVER,
						JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
				scrollPane.setPreferredSize(new Dimension(
						Math.min(hand.size() * (CARD_W + 16) + 16, 900), CARD_H + 60));

				int[] countdown = { 10 };
				boolean vsCpu = mw.isP2Cpu();
				JLabel countdownLabel = new JLabel(vsCpu ? "" : "Closing in 10...", SwingConstants.CENTER);
				countdownLabel.setFont(FontLoader.loadPixelFont(10));

				JButton okBtn = new JButton("OK");
				okBtn.setFont(FontLoader.loadPixelFont(11));
				okBtn.addActionListener(ae -> { mw.hideZoom(); dlg.dispose(); });

				JPanel south = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 6));
				if (!vsCpu) south.add(countdownLabel);
				south.add(okBtn);
				south.setBorder(BorderFactory.createEmptyBorder(0, 8, 8, 8));

				dlg.getContentPane().setLayout(new BorderLayout(0, 4));
				dlg.getContentPane().add(scrollPane, BorderLayout.CENTER);
				dlg.getContentPane().add(south,      BorderLayout.SOUTH);
				dlg.pack();
				dlg.setLocationRelativeTo(mw.frame);
				dlg.setVisible(true);

				if (!vsCpu) {
					Timer[] timerRef = { null };
					timerRef[0] = new Timer(1000, null);
					timerRef[0].addActionListener(te -> {
						countdown[0]--;
						if (countdown[0] <= 0) { timerRef[0].stop(); mw.hideZoom(); dlg.dispose(); }
						else countdownLabel.setText("Closing in " + countdown[0] + "...");
					});
					timerRef[0].start();
				}
			}

			@Override public void revealTopDeckCard(List<RevealClause> clauses, boolean opponentDeck) {
				// opponentDeck is relative to the acting player: own deck = isP1 XOR opponentDeck selects P1 deck
				boolean revealFromP1 = isP1 != opponentDeck;
				Deque<CardData> deck = revealFromP1
						? mw.gameState.getP1MainDeck()
						: mw.gameState.getP2MainDeck();
				Runnable refreshDeck = revealFromP1 ? mw::refreshP1DeckLabel : mw::refreshP2DeckLabel;
				String p = isP1 ? "" : "[P2] ";
				String deckLabel = opponentDeck ? "opponent's deck" : "your deck";
				if (deck.isEmpty()) {
					logEntry(p + "Reveal: " + deckLabel + " is empty.");
					return;
				}
				CardData card = deck.pollFirst();
				logEntry(p + "Revealed from " + deckLabel + ": " + card.name() + " (" + card.type() + ")");

				// P2 AI auto-accepts castSummonFree; P1 is prompted via dialog below
				boolean castFreeApplicable = card.isSummon() &&
						clauses.stream().anyMatch(c -> "castSummonFree".equals(c.cardOp()));
				boolean[] activated = {!isP1};

				if (isP1) {
					JDialog dlg = new JDialog(mw.frame, "Reveal", true);
					dlg.setResizable(false);
					dlg.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

					JLabel cardLabel = new JLabel("...", SwingConstants.CENTER);
					cardLabel.setPreferredSize(new Dimension(CARD_W, CARD_H));
					cardLabel.setMinimumSize(new Dimension(CARD_W, CARD_H));
					cardLabel.setOpaque(true);
					cardLabel.setBackground(Color.DARK_GRAY);
					cardLabel.setBorder(BorderFactory.createLineBorder(new Color(160, 110, 220), 1));
					cardLabel.addMouseListener(new MouseAdapter() {
						@Override public void mouseEntered(MouseEvent e) { mw.showZoomAt(card.imageUrl()); }
						@Override public void mouseExited(MouseEvent e)  { mw.hideZoom(); }
					});
					new SwingWorker<ImageIcon, Void>() {
						@Override protected ImageIcon doInBackground() throws Exception {
							Image img = ImageCache.load(card.imageUrl());
							return img == null ? null
									: new ImageIcon(img.getScaledInstance(CARD_W, CARD_H, Image.SCALE_SMOOTH));
						}
						@Override protected void done() {
							try {
								ImageIcon icon = get();
								if (icon != null) { cardLabel.setIcon(icon); cardLabel.setText(null); }
							} catch (InterruptedException | ExecutionException ignored) {}
						}
					}.execute();

					JPanel wrapper = new JPanel(new BorderLayout(0, 4));
					wrapper.setBorder(BorderFactory.createEmptyBorder(8, 8, 0, 8));
					JLabel nameLabel = new JLabel(card.name(), SwingConstants.CENTER);
					nameLabel.setFont(FontLoader.loadPixelFont(9));
					nameLabel.setPreferredSize(new Dimension(CARD_W, 18));
					wrapper.add(cardLabel,  BorderLayout.CENTER);
					wrapper.add(nameLabel,  BorderLayout.SOUTH);

					JPanel south = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 6));
					south.setBorder(BorderFactory.createEmptyBorder(0, 8, 8, 8));
					if (castFreeApplicable) {
						JButton declineBtn = new JButton("Decline");
						declineBtn.setFont(FontLoader.loadPixelFont(11));
						declineBtn.addActionListener(ae -> { mw.hideZoom(); dlg.dispose(); });
						JButton okBtn = new JButton("OK");
						okBtn.setFont(FontLoader.loadPixelFont(11));
						okBtn.addActionListener(ae -> { activated[0] = true; mw.hideZoom(); dlg.dispose(); });
						south.add(declineBtn);
						south.add(okBtn);
					} else {
						JButton okBtn = new JButton("OK");
						okBtn.setFont(FontLoader.loadPixelFont(11));
						okBtn.addActionListener(ae -> { mw.hideZoom(); dlg.dispose(); });
						south.add(okBtn);
					}

					dlg.getContentPane().setLayout(new BorderLayout(0, 4));
					dlg.getContentPane().add(wrapper, BorderLayout.CENTER);
					dlg.getContentPane().add(south,   BorderLayout.SOUTH);
					dlg.pack();
					dlg.setLocationRelativeTo(mw.frame);
					dlg.setVisible(true); // modal — blocks until dismissed
				}

				// Find the first matching clause and execute its action
				for (RevealClause clause : clauses) {
					if (!clause.condition().test(card)) continue;
					logEntry(p + "Condition matched for " + card.name());
					if (clause.cardOp() != null) {
						switch (clause.cardOp()) {
							case "playOntoField" -> {
								logEntry(p + card.name() + " played from reveal onto field");
								if (isP1) {
									if (card.isBackup())       mw.placeCardInFirstBackupSlot(card);
									else if (card.isMonster()) mw.placeCardInMonsterZone(card);
									else                       mw.placeCardInForwardZone(card);
								} else {
									if (card.isBackup())       mw.placeP2CardInFirstBackupSlot(card);
									else if (card.isMonster()) mw.placeP2CardInMonsterZone(card);
									else                       mw.placeP2CardInForwardZone(card);
								}
							}
							case "playOntoFieldDull" -> {
								logEntry(p + card.name() + " played from reveal onto field (dull)");
								if (isP1) {
									if (card.isBackup()) {
										mw.placeCardInFirstBackupSlot(card);
									} else if (card.isMonster()) {
										mw.placeCardInMonsterZone(card);
										int idx = mw.p1MonsterCards.size() - 1;
										mw.p1MonsterStates.set(idx, CardState.DULL);
										mw.refreshP1MonsterSlot(idx);
									} else {
										mw.placeCardInForwardZone(card);
										dullP1Forward(mw.p1ForwardCards.size() - 1);
									}
								} else {
									if (card.isBackup()) {
										mw.placeP2CardInFirstBackupSlot(card);
									} else if (card.isMonster()) {
										mw.placeP2CardInMonsterZone(card);
										int idx = mw.p2MonsterCards.size() - 1;
										mw.p2MonsterStates.set(idx, CardState.DULL);
										mw.refreshP2MonsterSlot(idx);
									} else {
										mw.placeP2CardInForwardZone(card);
										dullP2Forward(mw.p2ForwardCards.size() - 1);
									}
								}
							}
							case "addToHand" -> {
								if (isP1) {
									mw.gameState.getP1Hand().add(card);
									mw.animateCardDraw(true, 1);
									mw.refreshP1HandLabel();
								} else {
									mw.gameState.getP2Hand().add(card);
									mw.refreshP2HandCountLabel();
								}
								logEntry(p + card.name() + " added to hand from reveal");
							}
							case "putToBreakZone" -> {
								mw.addToBreakZone(card);
								logEntry(p + card.name() + " put into Break Zone from reveal");
								if (isP1) mw.refreshP1BreakLabel(); else mw.refreshP2BreakLabel();
							}
							case "castSummonFree" -> {
								if (!activated[0]) {
									logEntry(card.name() + " — free cast declined, returned to top of deck");
									deck.addFirst(card);
									refreshDeck.run();
									return;
								}
								logEntry(p + card.name() + " — cast for free from reveal");
								mw.showSummonOnStack(card, isP1);
							}
						}
					} else {
						// Standalone effect — return card to top of appropriate deck first
						// so any subsequent draw includes it
						deck.addFirst(card);
						refreshDeck.run();
						clause.effect().accept(this);
					}
					refreshDeck.run();
					return;
				}
				// No clause matched — put card back on top
				logEntry(p + "No condition matched — returning " + card.name() + " to top of " + deckLabel);
				deck.addFirst(card);
				refreshDeck.run();
			}

			@Override public void revealTopDeckCostParityEffect(java.util.function.Consumer<GameContext> onEven,
					java.util.function.Consumer<GameContext> onOdd) {
				Deque<CardData> deck = isP1 ? mw.gameState.getP1MainDeck() : mw.gameState.getP2MainDeck();
				if (deck.isEmpty()) { logEntry((isP1 ? "" : "[P2] ") + "Reveal: deck is empty."); return; }
				CardData card = deck.pollFirst();
				if (isP1) mw.refreshP1DeckLabel(); else mw.refreshP2DeckLabel();
				logEntry((isP1 ? "" : "[P2] ") + "Revealed: " + card.name() + " (cost " + card.cost() + ")");

				if (isP1) {
					JDialog dlg = new JDialog(mw.frame, "Reveal", true);
					dlg.setResizable(false);
					dlg.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

					JLabel cardLabel = new JLabel("...", SwingConstants.CENTER);
					cardLabel.setPreferredSize(new Dimension(CARD_W, CARD_H));
					cardLabel.setMinimumSize(new Dimension(CARD_W, CARD_H));
					cardLabel.setOpaque(true);
					cardLabel.setBackground(Color.DARK_GRAY);
					cardLabel.setBorder(BorderFactory.createLineBorder(new Color(160, 110, 220), 1));
					cardLabel.addMouseListener(new MouseAdapter() {
						@Override public void mouseEntered(MouseEvent e) { mw.showZoomAt(card.imageUrl()); }
						@Override public void mouseExited(MouseEvent e)  { mw.hideZoom(); }
					});
					new SwingWorker<ImageIcon, Void>() {
						@Override protected ImageIcon doInBackground() throws Exception {
							Image img = ImageCache.load(card.imageUrl());
							return img == null ? null
									: new ImageIcon(img.getScaledInstance(CARD_W, CARD_H, Image.SCALE_SMOOTH));
						}
						@Override protected void done() {
							try {
								ImageIcon icon = get();
								if (icon != null) { cardLabel.setIcon(icon); cardLabel.setText(null); }
							} catch (InterruptedException | ExecutionException ignored) {}
						}
					}.execute();

					JPanel wrapper = new JPanel(new BorderLayout(0, 4));
					wrapper.setBorder(BorderFactory.createEmptyBorder(8, 8, 0, 8));
					JLabel nameLabel = new JLabel(card.name(), SwingConstants.CENTER);
					nameLabel.setFont(FontLoader.loadPixelFont(9));
					nameLabel.setPreferredSize(new Dimension(CARD_W, 18));
					wrapper.add(cardLabel,  BorderLayout.CENTER);
					wrapper.add(nameLabel,  BorderLayout.SOUTH);

					JPanel south = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 6));
					south.setBorder(BorderFactory.createEmptyBorder(0, 8, 8, 8));
					JButton okBtn = new JButton("OK");
					okBtn.setFont(FontLoader.loadPixelFont(11));
					okBtn.addActionListener(ae -> { mw.hideZoom(); dlg.dispose(); });
					south.add(okBtn);

					dlg.getContentPane().setLayout(new BorderLayout(0, 4));
					dlg.getContentPane().add(wrapper, BorderLayout.CENTER);
					dlg.getContentPane().add(south,   BorderLayout.SOUTH);
					dlg.pack();
					dlg.setLocationRelativeTo(mw.frame);
					dlg.setVisible(true); // modal — blocks until dismissed
				}

				boolean even = (card.cost() % 2 == 0);
				logEntry((isP1 ? "" : "[P2] ") + "Cost " + card.cost() + " is " + (even ? "even" : "odd")
						+ " — applying " + (even ? "even" : "odd") + " branch");
				(even ? onEven : onOdd).accept(this);

				if (isP1) {
					mw.gameState.getP1Hand().add(card);
					mw.animateCardDraw(true, 1);
					mw.refreshP1HandLabel();
				} else {
					mw.gameState.getP2Hand().add(card);
					mw.refreshP2HandCountLabel();
				}
				logEntry((isP1 ? "" : "[P2] ") + card.name() + " added to hand from reveal");
			}

			/**
			 * "Each player reveals the top card of their deck and may play it onto the field."
			 *
			 * <p>Resolved a seat at a time, controller first. That order is what makes the two
			 * clients agree: each seats its own player as P1, so running it as
			 * {@code isP1} then {@code !isP1} has both walk the same two players in the same
			 * order — and it falls out that each client asks its own player exactly once and
			 * waits for the other exactly once.
			 */
			@Override public void revealEachPlayerTopDeckMayPlay(Predicate<CardData> eligibleCondition) {
				revealTopMayPlayForSeat(isP1,  eligibleCondition);
				revealTopMayPlayForSeat(!isP1, eligibleCondition);
			}

			/**
			 * One seat's half of the above: reveal their top card, ask them whether to play it, and
			 * carry out the answer. The card is fixed — the deck decides it — so the only thing
			 * that crosses the wire is the yes or no.
			 */
			private void revealTopMayPlayForSeat(boolean seatIsP1, Predicate<CardData> eligibleCondition) {
				Deque<CardData> deck = seatIsP1 ? mw.gameState.getP1MainDeck() : mw.gameState.getP2MainDeck();
				if (deck.isEmpty()) {
					logEntry("Reveal: " + (seatIsP1 ? "P1" : "P2") + "'s deck is empty.");
					return;
				}
				CardData card = deck.pollFirst();
				if (seatIsP1) mw.refreshP1DeckLabel(); else mw.refreshP2DeckLabel();
				// A reveal is public, so both clients name the card whichever seat turned it up.
				logEntry((seatIsP1 ? "P1" : "P2") + " revealed: " + card.name() + " (" + card.type() + ")");

				boolean eligible = eligibleCondition.test(card);
				// Not a legality gate — the engine nowhere stops a player putting a second copy of
				// a Character on their field, from hand or otherwise. This is the AI declining to,
				// which is what it does for every ordinary play too, so it is scoped to the AI's
				// answer and deliberately absent from legalWhen below. Enforcing uniqueness as a
				// rule is worth doing, but it belongs across the engine, not in this one effect.
				boolean cpuMayPlay = eligible && !aiAvoidsDuplicate(seatIsP1, card);

				List<Integer> answer = mw.decide(PlayerChoice.by(seatIsP1, ChoiceKind.REVEAL_MAY_PLAY)
						.prompting("Waiting for your opponent to decide on the card they revealed...")
						.locally(() -> showRevealMayPlayDialog(card, eligible) ? List.of(1) : List.of(0))
						.byCpu(()   -> List.of(cpuMayPlay ? 1 : 0))
						.legalWhen(a -> a.size() == 1 && (a.get(0) == 0 || (a.get(0) == 1 && eligible)),
								"that card is not one they may play here"));

				boolean play = !answer.isEmpty() && answer.get(0) == 1;
				// The spectator's view of the other seat's reveal. Only worth a dialog against the
				// AI: a remote opponent has already held this client on the wait prompt above.
				if (!seatIsP1 && mw.isP2Cpu()) showRevealSpectatorDialog(card, play);

				if (play) {
					logEntry((seatIsP1 ? "P1" : "P2") + " plays " + card.name() + " onto field from reveal");
					if (seatIsP1) {
						if (card.isBackup())       mw.placeCardInFirstBackupSlot(card);
						else if (card.isMonster()) mw.placeCardInMonsterZone(card);
						else                       mw.placeCardInForwardZone(card);
					} else {
						if (card.isBackup())       mw.placeP2CardInFirstBackupSlot(card);
						else if (card.isMonster()) mw.placeP2CardInMonsterZone(card);
						else                       mw.placeP2CardInForwardZone(card);
					}
				} else {
					logEntry((seatIsP1 ? "P1" : "P2") + " returns " + card.name() + " to top of deck");
					deck.addFirst(card);
				}
				if (seatIsP1) mw.refreshP1DeckLabel(); else mw.refreshP2DeckLabel();
			}

			/**
			 * Whether playing {@code card} would put a second copy of a non-multicard on that
			 * side — the same check {@link ComputerPlayer} makes before any ordinary play, so the
			 * AI behaves the same way here as it does everywhere else.
			 */
			private boolean aiAvoidsDuplicate(boolean seatIsP1, CardData card) {
				return !card.multicard() && (seatIsP1
						? mw.hasCharacterNameOnField(card.name())
						: mw.p2HasCharacterNameOnField(card.name()));
			}

			/**
			 * Shows the local player the card they revealed and returns whether they played it.
			 * When {@code mayPlay} is false there is nothing to decide and the dialog is only
			 * telling them what came up.
			 */
			private boolean showRevealMayPlayDialog(CardData card, boolean mayPlay) {
				boolean[] play = { false };
				JDialog dlg = new JDialog(mw.frame, "Reveal", true);
				dlg.setResizable(false);
				dlg.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

				JPanel south = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 6));
				south.setBorder(BorderFactory.createEmptyBorder(0, 8, 8, 8));
				if (mayPlay) {
					JButton declineBtn = new JButton("Decline");
					declineBtn.setFont(FontLoader.loadPixelFont(11));
					declineBtn.addActionListener(ae -> { mw.hideZoom(); dlg.dispose(); });
					JButton okBtn = new JButton("Play onto field");
					okBtn.setFont(FontLoader.loadPixelFont(11));
					okBtn.addActionListener(ae -> { play[0] = true; mw.hideZoom(); dlg.dispose(); });
					south.add(declineBtn);
					south.add(okBtn);
				} else {
					JButton okBtn = new JButton("OK");
					okBtn.setFont(FontLoader.loadPixelFont(11));
					okBtn.addActionListener(ae -> { mw.hideZoom(); dlg.dispose(); });
					south.add(okBtn);
				}
				showRevealCardDialog(dlg, card, card.name(), south);
				return play[0];
			}

			/** The other seat's reveal, shown to this client after that seat has decided. */
			private void showRevealSpectatorDialog(CardData card, boolean played) {
				JDialog dlg = new JDialog(mw.frame, "Opponent Reveal", true);
				dlg.setResizable(false);
				dlg.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

				JPanel south = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 6));
				south.setBorder(BorderFactory.createEmptyBorder(0, 8, 8, 8));
				JButton okBtn = new JButton("OK");
				okBtn.setFont(FontLoader.loadPixelFont(11));
				okBtn.addActionListener(ae -> { mw.hideZoom(); dlg.dispose(); });
				south.add(okBtn);
				showRevealCardDialog(dlg, card, card.name() + (played ? " → field" : " → deck"), south);
			}

			/** The card art, its caption and the caller's buttons, shown modally. */
			private void showRevealCardDialog(JDialog dlg, CardData card, String caption, JPanel south) {
				JLabel cardLabel = new JLabel("...", SwingConstants.CENTER);
				cardLabel.setPreferredSize(new Dimension(CARD_W, CARD_H));
				cardLabel.setOpaque(true);
				cardLabel.setBackground(Color.DARK_GRAY);
				cardLabel.setBorder(BorderFactory.createLineBorder(new Color(160, 110, 220), 1));
				cardLabel.addMouseListener(new MouseAdapter() {
					@Override public void mouseEntered(MouseEvent e) { mw.showZoomAt(card.imageUrl()); }
					@Override public void mouseExited(MouseEvent e)  { mw.hideZoom(); }
				});
				new SwingWorker<ImageIcon, Void>() {
					@Override protected ImageIcon doInBackground() throws Exception {
						Image img = ImageCache.load(card.imageUrl());
						return img == null ? null : new ImageIcon(img.getScaledInstance(CARD_W, CARD_H, Image.SCALE_SMOOTH));
					}
					@Override protected void done() {
						try { ImageIcon icon = get(); if (icon != null) { cardLabel.setIcon(icon); cardLabel.setText(null); } }
						catch (InterruptedException | ExecutionException ignored) {}
					}
				}.execute();

				JPanel wrapper = new JPanel(new BorderLayout(0, 4));
				wrapper.setBorder(BorderFactory.createEmptyBorder(8, 8, 0, 8));
				JLabel nameLabel = new JLabel(caption, SwingConstants.CENTER);
				nameLabel.setFont(FontLoader.loadPixelFont(9));
				nameLabel.setPreferredSize(new Dimension(CARD_W, 18));
				wrapper.add(cardLabel, BorderLayout.CENTER);
				wrapper.add(nameLabel, BorderLayout.SOUTH);

				dlg.getContentPane().setLayout(new BorderLayout(0, 4));
				dlg.getContentPane().add(wrapper, BorderLayout.CENTER);
				dlg.getContentPane().add(south,   BorderLayout.SOUTH);
				dlg.pack();
				dlg.setLocationRelativeTo(mw.frame);
				dlg.setVisible(true);
			}

			@Override public void revealTopBreakSameCostAddToHand() {
				Deque<CardData> deck = isP1 ? mw.gameState.getP1MainDeck() : mw.gameState.getP2MainDeck();
				if (deck.isEmpty()) {
					logEntry("Deck is empty — effect fizzles");
					markEffectFizzled();
					return;
				}
				CardData card = deck.pollFirst();
				if (isP1) mw.refreshP1DeckLabel(); else mw.refreshP2DeckLabel();
				logEntry((isP1 ? "" : "[P2] ") + "Revealed from top of deck: " + card.name() + " (cost " + card.cost() + ")");

				if (isP1) {
					JDialog dlg = new JDialog(mw.frame, "Reveal", true);
					dlg.setResizable(false);
					dlg.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
					JLabel cardLabel = new JLabel("...", SwingConstants.CENTER);
					cardLabel.setPreferredSize(new Dimension(CARD_W, CARD_H));
					cardLabel.setMinimumSize(new Dimension(CARD_W, CARD_H));
					cardLabel.setOpaque(true);
					cardLabel.setBackground(Color.DARK_GRAY);
					cardLabel.setBorder(BorderFactory.createLineBorder(new Color(160, 110, 220), 1));
					cardLabel.addMouseListener(new MouseAdapter() {
						@Override public void mouseEntered(MouseEvent e) { mw.showZoomAt(card.imageUrl()); }
						@Override public void mouseExited(MouseEvent e)  { mw.hideZoom(); }
					});
					new SwingWorker<ImageIcon, Void>() {
						@Override protected ImageIcon doInBackground() throws Exception {
							Image img = ImageCache.load(card.imageUrl());
							return img == null ? null : new ImageIcon(img.getScaledInstance(CARD_W, CARD_H, Image.SCALE_SMOOTH));
						}
						@Override protected void done() {
							try { ImageIcon icon = get(); if (icon != null) { cardLabel.setIcon(icon); cardLabel.setText(null); } }
							catch (InterruptedException | ExecutionException ignored) {}
						}
					}.execute();
					JPanel wrapper = new JPanel(new BorderLayout(0, 4));
					wrapper.setBorder(BorderFactory.createEmptyBorder(8, 8, 0, 8));
					JLabel nameLabel = new JLabel(card.name() + " (cost " + card.cost() + ")", SwingConstants.CENTER);
					nameLabel.setFont(FontLoader.loadPixelFont(9));
					nameLabel.setPreferredSize(new Dimension(CARD_W, 18));
					wrapper.add(cardLabel, BorderLayout.CENTER);
					wrapper.add(nameLabel, BorderLayout.SOUTH);
					JPanel south = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 6));
					south.setBorder(BorderFactory.createEmptyBorder(0, 8, 8, 8));
					JButton okBtn = new JButton("OK");
					okBtn.setFont(FontLoader.loadPixelFont(11));
					okBtn.addActionListener(ae -> { mw.hideZoom(); dlg.dispose(); });
					south.add(okBtn);
					dlg.getContentPane().setLayout(new BorderLayout(0, 4));
					dlg.getContentPane().add(wrapper, BorderLayout.CENTER);
					dlg.getContentPane().add(south,   BorderLayout.SOUTH);
					dlg.pack();
					dlg.setLocationRelativeTo(mw.frame);
					dlg.setVisible(true);
				}

				// Break all opponent Forwards with the same cost as the revealed card
				applyMassFieldEffect(GameContext.MassAction.BREAK, true, false, false,
						true, false, null, card.cost(), null, -1, null, null);

				// Add the revealed card to hand
				if (isP1) {
					mw.gameState.getP1Hand().add(card);
					mw.animateCardDraw(true, 1);
					mw.refreshP1HandLabel();
				} else {
					mw.gameState.getP2Hand().add(card);
					mw.refreshP2HandCountLabel();
				}
				logEntry((isP1 ? "" : "[P2] ") + card.name() + " added to hand");
			}

			@Override public void playCharacterFromHand(boolean inclForwards, boolean inclBackups,
					boolean inclMonsters, int costVal, String costCmp, int costVal2,
					String jobFilter, String cardNameFilter, String categoryFilter,
					String elementFilter, String excludeName, boolean entersDull, String excludeElement,
					boolean suppressAutoAbility, String withTrait) {
				if (!playCharacterFromHandFor(isP1, inclForwards, inclBackups, inclMonsters,
						costVal, costCmp, costVal2, jobFilter, cardNameFilter, categoryFilter,
						elementFilter, excludeName, entersDull, excludeElement, suppressAutoAbility,
						withTrait)) {
					markEffectFizzled();
				}
			}

			@Override public void eachPlayerMayPlayCharacterFromHand(boolean inclForwards,
					boolean inclBackups, boolean inclMonsters, int costVal, String costCmp,
					int costVal2, String jobFilter, String cardNameFilter, String categoryFilter,
					String elementFilter, String excludeName, boolean entersDull,
					String excludeElement, boolean suppressAutoAbility, String withTrait) {
				// Turn player first — an "each player" effect resolves in turn order, and here the
				// order is visible: whoever goes second sees what the first one played before
				// choosing. Not the controller first: the ability's controller need not be the
				// turn player (28-051R Black Cat can enter on either side's turn via other cards).
				boolean turnPlayerIsP1 = mw.gameState.getCurrentPlayer() == GameState.Player.P1;
				boolean playedFirst = playCharacterFromHandFor(turnPlayerIsP1, inclForwards,
						inclBackups, inclMonsters, costVal, costCmp, costVal2, jobFilter,
						cardNameFilter, categoryFilter, elementFilter, excludeName, entersDull,
						excludeElement, suppressAutoAbility, withTrait);
				boolean playedSecond = playCharacterFromHandFor(!turnPlayerIsP1, inclForwards,
						inclBackups, inclMonsters, costVal, costCmp, costVal2, jobFilter,
						cardNameFilter, categoryFilter, elementFilter, excludeName, entersDull,
						excludeElement, suppressAutoAbility, withTrait);
				// One player passing is not the effect failing; both passing is.
				if (!playedFirst && !playedSecond) markEffectFizzled();
			}

			/**
			 * Plays 1 matching Character from {@code forP1}'s hand onto their field — the body
			 * shared by the single-player {@link #playCharacterFromHand} and the two-player
			 * {@link #eachPlayerMayPlayCharacterFromHand}, so both apply the same eligibility
			 * rules, the same decline path and the same placement.
			 *
			 * <p>Takes the player explicitly rather than reading {@code isP1}, which names the
			 * ability's controller and so is the wrong side for one of the two calls above.
			 *
			 * <p>Optionality lives in the choice itself: P1 gets a cancellable chooser, and the
			 * AI takes the first eligible card. Fizzling is left to the caller, since "neither
			 * player played" and "this player did not play" are different conditions.
			 *
			 * @return {@code true} if a card was actually played.
			 */
			private boolean playCharacterFromHandFor(boolean forP1, boolean inclForwards,
					boolean inclBackups, boolean inclMonsters, int costVal, String costCmp,
					int costVal2, String jobFilter, String cardNameFilter, String categoryFilter,
					String elementFilter, String excludeName, boolean entersDull,
					String excludeElement, boolean suppressAutoAbility, String withTrait) {
				List<CardData> hand = forP1 ? mw.gameState.getP1Hand() : mw.gameState.getP2Hand();
				List<Integer> eligible = new ArrayList<>();
				for (int i = 0; i < hand.size(); i++) {
					CardData card = hand.get(i);
					if (card.isForward()  && !inclForwards) continue;
					if (card.isBackup()   && !inclBackups)  continue;
					if (card.isMonster()  && !inclMonsters) continue;
					if (card.isSummon()) continue;
					boolean costOk = meetsCostConstraint(card.cost(), costVal, costCmp)
					               || (costVal2 >= 0 && card.cost() == costVal2);
					if (!costOk) continue;
					// Job+name: OR when both are set; AND otherwise. This rule started here and now
					// lives in the shared helper, which the two board selections use as well.
					if (!mw.meetsJobOrCardNameFilter(card, jobFilter, cardNameFilter, null)) continue;
					if (!meetsCategoryFilter(card, categoryFilter)) continue;
					if (!meetsElementFilter(card, elementFilter)) continue;
					if (!meetsElementExclusion(card, excludeElement)) continue;
					if (excludeName != null && excludeName.equalsIgnoreCase(card.name())) continue;
					if ("Warp".equalsIgnoreCase(withTrait) && !card.hasWarp()) continue;
					// "You cannot play X from your hand due to Summons or abilities."
					if (card.playByEffectProhibited(true)) continue;
					eligible.add(i);
				}
				if (eligible.isEmpty()) {
					logEntry((forP1 ? "" : "[P2] ") + "No eligible cards in hand to play.");
					return false;
				}
				int handIdx;
				if (forP1) {
					List<CardData> candidates = new ArrayList<>();
					for (int i : eligible) candidates.add(hand.get(i));
					int listIdx = mw.showCardImageChooser(candidates, "Play a card onto the field", true, false);
					if (listIdx < 0) return false; // cancelled — this is how "may" is declined
					handIdx = eligible.get(listIdx);
				} else {
					handIdx = eligible.get(0); // AI: play first eligible card
				}
				CardData card = hand.remove(handIdx);
				logEntry((forP1 ? "" : "[P2] ") + card.name() + " played from hand onto field"
						+ (entersDull ? " (dull)" : "") + (suppressAutoAbility ? " (no ETF auto-ability)" : ""));
				if (suppressAutoAbility) mw.suppressAutoAbilityForNextCard = true;
				if (forP1) {
					if (card.isBackup()) {
						mw.placeCardInFirstBackupSlot(card);
					} else if (card.isMonster()) {
						mw.placeCardInMonsterZone(card);
					} else {
						mw.placeCardInForwardZone(card);
						if (entersDull) {
							int newIdx = mw.p1ForwardCards.size() - 1;
							mw.p1ForwardStates.set(newIdx, CardState.DULL);
							mw.refreshP1ForwardSlot(newIdx);
						}
					}
					mw.refreshP1HandLabel();
				} else {
					if (card.isBackup()) {
						mw.placeP2CardInFirstBackupSlot(card);
					} else if (card.isMonster()) {
						mw.placeP2CardInMonsterZone(card);
					} else {
						mw.placeP2CardInForwardZone(card);
						if (entersDull) {
							int newIdx = mw.p2ForwardCards.size() - 1;
							mw.p2ForwardStates.set(newIdx, CardState.DULL);
							mw.refreshP2ForwardSlot(newIdx);
						}
					}
					mw.refreshP2HandCountLabel();
				}
				return true;
			}

			@Override public void playAnyNumberFromHand(boolean inclForwards, boolean inclBackups,
					boolean inclMonsters, String jobFilter, String cardNameFilter, String categoryFilter,
					String elementFilter) {
				while (true) {
					List<CardData> hand = isP1 ? mw.gameState.getP1Hand() : mw.gameState.getP2Hand();
					List<Integer> eligible = new ArrayList<>();
					for (int i = 0; i < hand.size(); i++) {
						CardData c = hand.get(i);
						if (c.isForward()  && !inclForwards) continue;
						if (c.isBackup()   && !inclBackups)  continue;
						if (c.isMonster()  && !inclMonsters) continue;
						if (c.isSummon()) continue;
						if (!mw.meetsJobOrCardNameFilter(c, jobFilter, cardNameFilter, null)) continue;
						if (!meetsCategoryFilter(c, categoryFilter)) continue;
						if (!meetsElementFilter(c, elementFilter)) continue;
						if (c.playByEffectProhibited(true)) continue;
						eligible.add(i);
					}
					if (eligible.isEmpty()) return;
					int handIdx;
					if (isP1) {
						List<CardData> candidates = new ArrayList<>();
						for (int i : eligible) candidates.add(hand.get(i));
						int listIdx = mw.showCardImageChooser(candidates, "Play a card onto the field (any number)", true, true);
						if (listIdx < 0) return;
						handIdx = eligible.get(listIdx);
					} else {
						handIdx = eligible.get(0);
					}
					CardData card = hand.remove(handIdx);
					logEntry((isP1 ? "" : "[P2] ") + card.name() + " played from hand onto field");
					if (isP1) {
						if (card.isBackup()) mw.placeCardInFirstBackupSlot(card);
						else if (card.isMonster()) mw.placeCardInMonsterZone(card);
						else mw.placeCardInForwardZone(card);
						mw.refreshP1HandLabel();
					} else {
						if (card.isBackup()) mw.placeP2CardInFirstBackupSlot(card);
						else if (card.isMonster()) mw.placeP2CardInMonsterZone(card);
						else mw.placeP2CardInForwardZone(card);
						mw.refreshP2HandCountLabel();
					}
				}
			}

			@Override public void chooseAnyNumberReturnToHand(boolean inclForwards, boolean inclBackups,
					boolean inclMonsters, boolean opponentOnly, boolean selfOnly) {
				// Which player zones to include (from the ability user's perspective)
				boolean includeP1 = opponentOnly ? !isP1 : (selfOnly ? isP1 : true);
				boolean includeP2 = opponentOnly ?  isP1 : (selfOnly ? !isP1 : true);

				if (!isP1) {
					// P2 AI: return all eligible cards from P1's zone (opponent), nothing from own
					if (includeP1) {
						if (inclForwards)
							for (int i = mw.p1ForwardCards.size() - 1; i >= 0; i--) returnP1ForwardToHand(i);
						if (inclBackups)
							for (int i = mw.p1BackupCards.length - 1; i >= 0; i--)
								if (mw.p1BackupCards[i] != null) returnP1BackupToHand(i);
						if (inclMonsters)
							for (int i = mw.p1MonsterCards.size() - 1; i >= 0; i--) returnP1MonsterToHand(i);
					}
					return;
				}

				// P1 human: loop-chooser, rebuilt each iteration so indices stay valid
				while (true) {
					List<CardData> candidates = new ArrayList<>();
					List<int[]>    zoneIdx    = new ArrayList<>(); // [player: 0=P1 1=P2, zone: 0=fwd 1=bkp 2=mon, idx]
					if (includeP1) {
						if (inclForwards)
							for (int i = 0; i < mw.p1ForwardCards.size(); i++) {
								CardData c = mw.p1ForwardCards.get(i);
								if (c != null) { candidates.add(c); zoneIdx.add(new int[]{0, 0, i}); }
							}
						if (inclBackups)
							for (int i = 0; i < mw.p1BackupCards.length; i++) {
								CardData c = mw.p1BackupCards[i];
								if (c != null) { candidates.add(c); zoneIdx.add(new int[]{0, 1, i}); }
							}
						if (inclMonsters)
							for (int i = 0; i < mw.p1MonsterCards.size(); i++) {
								CardData c = mw.p1MonsterCards.get(i);
								if (c != null) { candidates.add(c); zoneIdx.add(new int[]{0, 2, i}); }
							}
					}
					if (includeP2) {
						if (inclForwards)
							for (int i = 0; i < mw.p2ForwardCards.size(); i++) {
								CardData c = mw.p2ForwardCards.get(i);
								if (c != null) { candidates.add(c); zoneIdx.add(new int[]{1, 0, i}); }
							}
						if (inclBackups)
							for (int i = 0; i < mw.p2BackupCards.length; i++) {
								CardData c = mw.p2BackupCards[i];
								if (c != null) { candidates.add(c); zoneIdx.add(new int[]{1, 1, i}); }
							}
						if (inclMonsters)
							for (int i = 0; i < mw.p2MonsterCards.size(); i++) {
								CardData c = mw.p2MonsterCards.get(i);
								if (c != null) { candidates.add(c); zoneIdx.add(new int[]{1, 2, i}); }
							}
					}
					if (candidates.isEmpty()) return;
					int pick = mw.showCardImageChooser(candidates, "Return a Character to hand (cancel when done)", true);
					if (pick < 0) return;
					int[] zi = zoneIdx.get(pick);
					if (zi[0] == 0) { // P1 zone
						switch (zi[1]) {
							case 0 -> returnP1ForwardToHand(zi[2]);
							case 1 -> returnP1BackupToHand(zi[2]);
							case 2 -> returnP1MonsterToHand(zi[2]);
						}
					} else { // P2 zone
						switch (zi[1]) {
							case 0 -> returnP2ForwardToHand(zi[2]);
							case 1 -> returnP2BackupToHand(zi[2]);
							case 2 -> returnP2MonsterToHand(zi[2]);
						}
					}
				}
			}

			@Override public void castSummonFromHandFree(int maxCost, boolean returnToHandAfterUse, String excludeElements) {
				List<CardData> hand = isP1 ? mw.gameState.getP1Hand() : mw.gameState.getP2Hand();
				List<Integer> eligible = new ArrayList<>();
				java.util.Set<String> excludeSet = excludeElements == null ? java.util.Set.of()
						: java.util.Arrays.stream(excludeElements.split("\\|"))
								.map(String::toLowerCase).collect(java.util.stream.Collectors.toSet());
				for (int i = 0; i < hand.size(); i++) {
					CardData c = hand.get(i);
					if (!c.isSummon()) continue;
					if (maxCost >= 0 && c.cost() > maxCost) continue;
					if (!excludeSet.isEmpty() && java.util.Arrays.stream(c.elements())
							.map(String::toLowerCase).anyMatch(excludeSet::contains)) continue;
					eligible.add(i);
				}
				if (eligible.isEmpty()) {
					logEntry("No eligible Summon in hand — effect fizzles");
					markEffectFizzled();
					return;
				}
				int handIdx;
				if (isP1) {
					List<CardData> candidates = new ArrayList<>();
					for (int i : eligible) candidates.add(hand.get(i));
					String title = "Cast 1 Summon from hand for free"
							+ (maxCost >= 0 ? " (cost " + maxCost + " or less)" : "")
							+ (excludeElements != null ? " (not " + excludeElements + ")" : "");
					int listIdx = mw.showCardImageChooser(candidates, title, true);
					if (listIdx < 0) { markEffectFizzled(); return; }
					handIdx = eligible.get(listIdx);
				} else {
					handIdx = eligible.get(0);
				}
				CardData card = hand.remove(handIdx);
				if (isP1) mw.refreshP1HandLabel(); else mw.refreshP2HandCountLabel();
				if (returnToHandAfterUse) mw.returnToHandAfterUseSummons.add(card);
				mw.turn(isP1).summonCastThisTurn = true;
				mw.noteCardCast(card, isP1);
				mw.noteDoublecastSummonCast(isP1, card);
				mw.lastCardWasCast = true;
				logEntry((isP1 ? "" : "[P2] ") + "Cast \"" + card.name() + "\" from hand for free"
						+ (returnToHandAfterUse ? " (return to hand after use)" : ""));
				mw.showSummonOnStack(card, isP1);
				mw.lastCardWasCast = false;
			}

			@Override public void randomRevealHandCastIfSummonFree() {
				List<CardData> hand = isP1 ? mw.gameState.getP1Hand() : mw.gameState.getP2Hand();
				if (hand.isEmpty()) {
					logEntry("Hand is empty — effect fizzles");
					markEffectFizzled();
					return;
				}
				List<Integer> picks = randomPicks(1, hand.size(), "which card is revealed");
				if (picks.isEmpty()) return;
				int idx = picks.get(0);
				CardData revealed = hand.get(idx);
				// A revealed card is public, so it is named for both players whoever revealed it.
				logEntry((isP1 ? "" : "[P2] ") + "Randomly revealed: " + revealed.name());
				if (!revealed.isSummon()) {
					logEntry(revealed.name() + " is not a Summon — no cast");
					return;
				}
				// The AI never turns down a free Summon, which is what its branch used to do.
				List<Integer> answer = mw.decide(PlayerChoice.by(isP1, ChoiceKind.MAY)
						.prompting("Waiting for your opponent to decide on a free Summon...")
						.locally(() -> List.of(mw.showEffectOptionDialog(
								"Randomly revealed: " + revealed.name()
										+ " (Summon)\nCast it without paying the cost?",
								"May Cast Summon", new Object[]{"Cast", "Decline"}) == 0 ? 1 : 0))
						.byCpu(()   -> List.of(1))
						.legalWhen(a -> a.size() == 1 && (a.get(0) == 0 || a.get(0) == 1),
								"a yes or a no is the only answer that fits"));
				if (answer.isEmpty() || answer.get(0) != 1) {
					logEntry("Declined to cast " + revealed.name());
					return;
				}
				hand.remove(idx);
				if (isP1) mw.refreshP1HandLabel(); else mw.refreshP2HandCountLabel();
				mw.turn(isP1).summonCastThisTurn = true;
				mw.noteCardCast(revealed, isP1);
				mw.noteDoublecastSummonCast(isP1, revealed);
				mw.lastCardWasCast = true;
				logEntry((isP1 ? "" : "[P2] ") + "Cast \"" + revealed.name() + "\" from hand for free");
				mw.showSummonOnStack(revealed, isP1);
				mw.lastCardWasCast = false;
			}

			@Override public void castSummonFromHandDiscounted(int discount) {
				List<CardData> hand = isP1 ? mw.gameState.getP1Hand() : mw.gameState.getP2Hand();
				List<Integer> eligible = new ArrayList<>();
				for (int i = 0; i < hand.size(); i++)
					if (hand.get(i).isSummon()) eligible.add(i);
				if (eligible.isEmpty()) {
					logEntry("No Summons in hand — effect fizzles");
					markEffectFizzled();
					return;
				}
				int handIdx;
				if (isP1) {
					List<CardData> candidates = new ArrayList<>();
					for (int i : eligible) candidates.add(hand.get(i));
					java.util.function.ToIntFunction<CardData> costFn =
							c -> Math.max(1, mw.effectiveCastCost(c) - discount);
					int listIdx = mw.showCardImageChooser(candidates,
							"Cast a Summon (cost reduced by " + discount + ", min 1)", true, costFn);
					if (listIdx < 0) { markEffectFizzled(); return; }
					handIdx = eligible.get(listIdx);
				} else {
					handIdx = eligible.get(0);
				}
				CardData card = hand.get(handIdx);
				CostReductionModifier mod = new CostReductionModifier(
						discount, true, true,
						false, false, false, true,
						null, null, card.name().toLowerCase(), null, false);
				mw.activeCostReductions.add(mod);
				if (isP1) {
					mw.showPaymentDialog(card, handIdx);
				} else {
					hand.remove(handIdx);
					mw.refreshP2HandCountLabel();
					mw.p2Turn.summonCastThisTurn = true;
					mw.noteCardCast(card, false);
					mw.noteDoublecastSummonCast(false, card);
					mw.activeCostReductions.remove(mod);
					logEntry("[P2] Cast \"" + card.name() + "\" from hand (cost -" + discount + ")");
					mw.showSummonOnStack(card, false);
				}
			}

			@Override public void searchAndCastSummonFreeFromDeck(int maxCost, String elementFilter) {
				Deque<CardData> deck = isP1 ? mw.gameState.getP1MainDeck() : mw.gameState.getP2MainDeck();
				java.util.List<CardData> matches = new java.util.ArrayList<>();
				for (CardData c : deck) {
					if (!c.isSummon()) continue;
					if (maxCost >= 0 && c.cost() > maxCost) continue;
					if (elementFilter != null && !c.containsElement(elementFilter)) continue;
					matches.add(c);
				}
				if (matches.isEmpty()) {
					mw.shuffleDeck(isP1);
					logEntry("Search: no matching " + (elementFilter != null ? elementFilter + " " : "") + "Summon found — effect fizzles");
					markEffectFizzled();
					return;
				}
				CardData picked;
				if (isP1) {
					picked = mw.cardPickerDialog.pickFromDeckSearch(matches);
				} else {
					java.util.List<CardData> copy = new java.util.ArrayList<>(matches);
					java.util.Collections.shuffle(copy);
					picked = copy.get(0);
					logEntry("[AI] chose " + picked.name());
				}
				if (picked == null) {
					mw.shuffleDeck(isP1);
					logEntry("Search: no card selected");
					return;
				}
				if (isP1) mw.gameState.removeFromP1MainDeck(picked);
				else      deck.remove(picked);
				mw.shuffleDeck(isP1);

				boolean castIt;
				if (isP1) {
					int choice = mw.showEffectOptionDialog(
							"Cast \"" + picked.name() + "\" without paying its cost?",
							"Search — Cast Summon?", new Object[]{"Cast", "Put into Break Zone"});
					castIt = (choice == 0);
				} else {
					castIt = true;
				}

				if (castIt) {
					mw.turn(isP1).summonCastThisTurn = true;
					mw.noteCardCast(picked, isP1);
					mw.noteDoublecastSummonCast(isP1, picked);
					mw.lastCardWasCast = true;
					logEntry((isP1 ? "" : "[P2] ") + "Cast \"" + picked.name() + "\" from deck search for free");
					mw.showSummonOnStack(picked, isP1);
					mw.lastCardWasCast = false;
				} else {
					if (isP1) { mw.addToBreakZone(picked); mw.refreshP1BreakLabel(); }
					else       { mw.addToBreakZone(picked); mw.refreshP2BreakLabel(); }
					logEntry((isP1 ? "" : "[P2] ") + "\"" + picked.name() + "\" put into the Break Zone (chose not to cast)");
				}
			}

			@Override public void damageTarget(ForwardTarget t, int amount) {
				if (t.zone() == ForwardTarget.CardZone.BACKUP) { mw.applyDamageToBackup(t.isP1(), t.idx(), amount); return; }
				if (t.zone() == ForwardTarget.CardZone.MONSTER) { mw.applyDamageToMonster(t.isP1(), t.idx(), amount); return; }
				if (t.isP1()) damageP1Forward(t.idx(), amount);
				else          damageP2Forward(t.idx(), amount);
			}

			@Override public void gainCrystal(int count) {
				if (isP1) mw.gameState.addP1Crystals(count);
				else      mw.gameState.addP2Crystals(count);
				mw.refreshCrystalDisplays();
				// One trigger per Crystal — see triggerAutoAbilitiesForGainCrystal. Fired after
				// the Crystals are on the counter so an ability that reads the total sees it.
				for (int i = 0; i < count; i++)
					mw.autoAbilityTriggers.triggerAutoAbilitiesForGainCrystal(isP1);
			}

			@Override public int crystalCount()         { return mw.playerCrystals(isP1);  }
			@Override public int castPaymentDistinctElements() { return mw.lastCastPaymentDistinctElements; }
			@Override public int opponentCrystalCount() { return mw.playerCrystals(!isP1); }

			@Override public void damageFieldForwardByName(String cardName, int amount) {
				for (int i = 0; i < mw.p1ForwardCards.size(); i++) {
					if (mw.p1ForwardCards.get(i).name().equalsIgnoreCase(cardName)) {
						damageP1Forward(i, amount);
						return;
					}
				}
				for (int i = 0; i < mw.p2ForwardCards.size(); i++) {
					if (mw.p2ForwardCards.get(i).name().equalsIgnoreCase(cardName)) {
						damageP2Forward(i, amount);
						return;
					}
				}
				logEntry("[ActionResolver] damageFieldForwardByName: \"" + cardName + "\" not found on field");
			}

			@Override public void eachPlayerMaySearchForwardMinPowerToHand(int count, int minPower) {
				// P1
				Deque<CardData> p1Deck = mw.gameState.getP1MainDeck();
				List<CardData> p1Matches = new ArrayList<>();
				for (CardData c : p1Deck) if (c.isForward() && c.power() >= minPower) p1Matches.add(c);
				if (p1Matches.isEmpty()) {
					logEntry("P1 search: no Forward of " + minPower + "+ power in deck");
					mw.shuffleDeck(true);
				} else {
					String src = mw.currentAbilitySource != null ? mw.currentAbilitySource.name() : "Ability";
					int choice = mw.showEffectOptionDialog(
							src + " — Search for 1 Forward of power " + minPower + " or more?",
							"You May Search", new Object[]{"Search", "Pass"});
					if (choice == 0) {
						CardData pick = mw.cardPickerDialog.pickFromDeckSearch(p1Matches);
						if (pick != null) {
							mw.gameState.removeFromP1MainDeck(pick);
							mw.gameState.getP1Hand().add(pick);
							logEntry(pick.name() + " → hand (search)");
							mw.refreshP1HandLabel();
							mw.animateCardDraw(true, 1);
						}
					} else {
						logEntry("P1 passes on search");
					}
					mw.shuffleDeck(true);
				}

				// P2
				Deque<CardData> p2Deck = mw.gameState.getP2MainDeck();
				List<CardData> p2Matches = new ArrayList<>();
				for (CardData c : p2Deck) if (c.isForward() && c.power() >= minPower) p2Matches.add(c);
				if (p2Matches.isEmpty()) {
					logEntry("[P2] search: no Forward of " + minPower + "+ power in deck");
					mw.shuffleDeck(false);
				} else {
					p2Matches.sort(java.util.Comparator.comparingInt(CardData::power).reversed());
					CardData pick = p2Matches.get(0);
					mw.gameState.getP2MainDeck().remove(pick);
					mw.gameState.getP2Hand().add(pick);
					logEntry("[P2 AI] " + pick.name() + " → hand (search)");
					mw.refreshP2HandCountLabel();
					mw.shuffleDeck(false);
				}
			}

			/**
			 * Both seats choose one of their own Forwards, so both clients ask their own player and
			 * wait for the other's answer.  The two questions travel in opposite directions and are
			 * asked in the same order on both clients — local first, remote second — so neither side
			 * is waiting when the other is.
			 */
			@Override public void eachPlayerSelectForwardAndDamage(int amount) {
				ForwardTarget p1Pick = eachPlayerForwardPick(true,
						"Each player selects 1 Forward — choose yours",
						() -> mw.aiPickForwardToSurvive(amount));
				ForwardTarget p2Pick = eachPlayerForwardPick(false,
						"Each player selects 1 Forward — choose yours",
						() -> mw.aiPickForwardToSurvive(amount));

				if (p1Pick != null) damageP1Forward(p1Pick.idx(), amount);
				if (p2Pick != null) damageP2Forward(p2Pick.idx(), amount);
			}

			@Override public void eachPlayerSelectForwardAndBreak() {
				ForwardTarget p1Pick = eachPlayerForwardPick(true,
						"Both players select 1 Forward — choose yours to put in Break Zone",
						mw::aiPickForwardForBreak);
				ForwardTarget p2Pick = eachPlayerForwardPick(false,
						"Both players select 1 Forward — choose yours to put in Break Zone",
						mw::aiPickForwardForBreak);

				if (p1Pick != null) forceTargetToBreakZone(p1Pick);
				if (p2Pick != null) forceTargetToBreakZone(p2Pick);
			}

			@Override public void selectControlledForwardAndBreak() {
				List<ForwardTarget> eligible = ownForwards(isP1);
				if (eligible.isEmpty()) {
					logEntry((isP1 ? "P1" : "[P2]") + " has no Forwards — skipping selection");
					return;
				}
				ForwardTarget pick = mw.selectOwnFieldTarget(isP1, eligible,
						"Select 1 Forward you control to put into the Break Zone",
						"Waiting for your opponent to select a Forward to break...",
						mw::aiPickForwardForBreak);
				if (pick != null) {
					logSelectedOwnCard(isP1, pick);
					forceTargetToBreakZone(pick);
				}
			}

			/**
			 * One seat's half of an "each player selects 1 Forward" effect.  A seat with an empty
			 * field is skipped rather than asked, and that is derived on both clients from a board
			 * they already agree on — so it costs no round trip and cannot be answered wrongly.
			 */
			private ForwardTarget eachPlayerForwardPick(boolean seatIsP1, String title,
			                                            Supplier<ForwardTarget> cpuPick) {
				List<ForwardTarget> eligible = ownForwards(seatIsP1);
				if (eligible.isEmpty()) {
					logEntry((seatIsP1 ? "P1" : "[P2]") + " has no Forwards — skipping selection");
					return null;
				}
				ForwardTarget pick = mw.selectOwnFieldTarget(seatIsP1, eligible, title,
						"Waiting for your opponent to select their Forward...", cpuPick);
				if (pick != null) logSelectedOwnCard(seatIsP1, pick);
				return pick;
			}

			/** Every Forward on one seat's side of the board, as targets. */
			private List<ForwardTarget> ownForwards(boolean seatIsP1) {
				List<CardData> forwards = seatIsP1 ? mw.p1ForwardCards : mw.p2ForwardCards;
				List<ForwardTarget> out = new ArrayList<>(forwards.size());
				for (int i = 0; i < forwards.size(); i++)
					out.add(new ForwardTarget(seatIsP1, i, ForwardTarget.CardZone.FORWARD));
				return out;
			}

			/** Names the card a seat picked from its own field, whoever was sitting there. */
			private void logSelectedOwnCard(boolean seatIsP1, ForwardTarget pick) {
				CardData c = mw.fieldCardDataOrNull(pick);
				if (c != null) logEntry("[" + (seatIsP1 ? "P1" : "P2") + "] selected " + c.name());
			}

			/** One seat's Characters in the named zones, as targets. */
			private List<ForwardTarget> ownCharacters(boolean seatIsP1, boolean inclForwards,
					boolean inclBackups, boolean inclMonsters) {
				List<ForwardTarget> out = new ArrayList<>();
				if (inclForwards) out.addAll(ownForwards(seatIsP1));
				if (inclBackups) {
					CardData[] backups = seatIsP1 ? mw.p1BackupCards : mw.p2BackupCards;
					for (int i = 0; i < backups.length; i++)
						if (backups[i] != null)
							out.add(new ForwardTarget(seatIsP1, i, ForwardTarget.CardZone.BACKUP));
				}
				if (inclMonsters) {
					List<CardData> monsters = seatIsP1 ? mw.p1MonsterCards : mw.p2MonsterCards;
					for (int i = 0; i < monsters.size(); i++)
						out.add(new ForwardTarget(seatIsP1, i, ForwardTarget.CardZone.MONSTER));
				}
				return out;
			}

			@Override public boolean opponentMayBreakOwnCharacter(boolean inclForwards, boolean inclBackups,
					boolean inclMonsters, String sourceName) {
				boolean oppIsP1 = !isP1;
				List<ForwardTarget> eligible = ownCharacters(oppIsP1, inclForwards, inclBackups, inclMonsters);
				if (eligible.isEmpty()) {
					logEntry((oppIsP1 ? "P1" : "[P2]") + " has no eligible Characters — no offer made");
					return false;
				}

				// Declining is the point of the offer, so the AI weighs it rather than always taking
				// it: the cheapest Character it can spare buys the opening, but it will not empty its
				// board for one. Returning null from the supplier is how a seat declines.
				Supplier<ForwardTarget> cpuPick = () -> {
					if (eligible.size() < 2) return null;
					ForwardTarget best = null;
					int bestCost = Integer.MAX_VALUE;
					for (ForwardTarget t : eligible) {
						CardData c = mw.fieldCardDataOrNull(t);
						if (c != null && c.cost() < bestCost) { bestCost = c.cost(); best = t; }
					}
					return best;
				};

				ForwardTarget pick = mw.selectOwnFieldTarget(oppIsP1, eligible,
						"You may put 1 Character you control into the Break Zone — if you do, "
								+ sourceName + " cannot block this turn",
						"Waiting for your opponent to decide whether to break a Character...",
						cpuPick);
				if (pick == null) {
					logEntry("Effect: opponent declined — " + sourceName + " may still block");
					return false;
				}
				logSelectedOwnCard(oppIsP1, pick);
				forceTargetToBreakZone(pick);
				return true;
			}

			@Override public void turnPlayerBreaksOwnCharacterOrTakesDamage(boolean inclForwards,
					boolean inclBackups, boolean inclMonsters, int damage, String sourceName) {
				boolean turnIsP1 = mw.gameState.getCurrentPlayer() == GameState.Player.P1;
				List<ForwardTarget> eligible = ownCharacters(turnIsP1, inclForwards, inclBackups, inclMonsters);
				ForwardTarget pick = null;
				if (!eligible.isEmpty()) {
					// The AI pays a Character only to stay alive. A point of damage is cheaper than a
					// body almost every time; the exception is the point that would end the game.
					int taken = (turnIsP1 ? mw.gameState.getP1DamageZone() : mw.gameState.getP2DamageZone()).size();
					Supplier<ForwardTarget> cpuPick = () -> {
						if (taken + damage < 7) return null;
						ForwardTarget cheapest = null;
						int bestCost = Integer.MAX_VALUE;
						for (ForwardTarget t : eligible) {
							CardData c = mw.fieldCardDataOrNull(t);
							if (c != null && c.cost() < bestCost) { bestCost = c.cost(); cheapest = t; }
						}
						return cheapest;
					};
					pick = mw.selectOwnFieldTarget(turnIsP1, eligible,
							"You may put 1 Character you control into the Break Zone — if you do not, "
									+ sourceName + " deals you " + damage + " point(s) of damage",
							"Waiting for your opponent to decide whether to break a Character...",
							cpuPick);
				}
				if (pick != null) {
					logSelectedOwnCard(turnIsP1, pick);
					forceTargetToBreakZone(pick);
					return;
				}
				logEntry((turnIsP1 ? "P1" : "[P2]") + " takes " + damage + " point(s) from "
						+ sourceName + " rather than break a Character");
				for (int i = 0; i < damage; i++) {
					if (turnIsP1) mw.p1TakeDamage(); else mw.p2TakeDamage();
				}
			}

			@Override public void selectControlledTypeAndBreak(boolean inclForwards, boolean inclBackups, boolean inclMonsters) {
				List<ForwardTarget> eligible = ownCharacters(isP1, inclForwards, inclBackups, inclMonsters);
				if (eligible.isEmpty()) {
					logEntry((isP1 ? "P1" : "[P2]") + " has no eligible characters — skipping");
					return;
				}

				// The AI works down the same preference order it always has: cheapest Forward
				// first, then any Backup, then a Monster.
				Supplier<ForwardTarget> cpuPick = () -> {
					if (inclForwards && !mw.p2ForwardCards.isEmpty()) return mw.aiPickForwardForBreak();
					if (inclBackups)
						for (int i = 0; i < mw.p2BackupCards.length; i++)
							if (mw.p2BackupCards[i] != null)
								return new ForwardTarget(false, i, ForwardTarget.CardZone.BACKUP);
					if (inclMonsters && !mw.p2MonsterCards.isEmpty())
						return new ForwardTarget(false, 0, ForwardTarget.CardZone.MONSTER);
					return null;
				};

				ForwardTarget pick = mw.selectOwnFieldTarget(isP1, eligible,
						"Select 1 Character you control to put into the Break Zone",
						"Waiting for your opponent to select a Character to break...",
						cpuPick);
				if (pick != null) {
					logSelectedOwnCard(isP1, pick);
					forceTargetToBreakZone(pick);
				}
			}

			@Override public void eachPlayerSalvageFromBreakZone(int count, boolean fwds, boolean bkps,
					boolean mons, boolean smns) {
				List<CardData> p1Bz = mw.gameState.getP1BreakZone();
				List<CardData> p2Bz = mw.gameState.getP2BreakZone();
				java.util.function.Predicate<CardData> eligibleCard = c ->
						  c.isForward() ? fwds
						: c.isBackup()  ? bkps
						: c.isMonster() ? mons
						:                 smns;
				String what = (fwds && bkps && mons && smns) ? "card" : "eligible card";

				// P1 picks via dialog
				List<ForwardTarget> p1Picks = List.of();
				List<Integer> p1Eligible = new ArrayList<>();
				for (int i = 0; i < p1Bz.size(); i++) if (eligibleCard.test(p1Bz.get(i))) p1Eligible.add(i);
				if (!p1Eligible.isEmpty()) {
					List<ForwardTarget> eligible = new ArrayList<>();
					for (int i : p1Eligible) {
						CardData c = p1Bz.get(i);
						ForwardTarget.CardZone cz = c.isBackup() ? ForwardTarget.CardZone.BACKUP
								: c.isMonster() ? ForwardTarget.CardZone.MONSTER
								: ForwardTarget.CardZone.FORWARD;
						eligible.add(new ForwardTarget(true, i, cz));
					}
					p1Picks = mw.showBreakZoneSelectDialog(eligible, p1Bz, count, false,
							"Each player salvages " + count + " " + what + "(s) — choose from your Break Zone");
				} else {
					logEntry("P1 Break Zone holds no " + what + " — skipping salvage");
				}

				// P2 (AI) auto-picks highest-cost eligible cards
				List<ForwardTarget> p2Picks = new ArrayList<>();
				List<Integer> idxs = new ArrayList<>();
				for (int i = 0; i < p2Bz.size(); i++) if (eligibleCard.test(p2Bz.get(i))) idxs.add(i);
				if (!idxs.isEmpty()) {
					idxs.sort((a, b) -> Integer.compare(p2Bz.get(b).cost(), p2Bz.get(a).cost()));
					for (int i = 0; i < Math.min(count, idxs.size()); i++) {
						int idx = idxs.get(i);
						p2Picks.add(new ForwardTarget(false, idx, ForwardTarget.CardZone.FORWARD));
						logEntry("[AI] salvaged " + p2Bz.get(idx).name() + " from P2 Break Zone");
					}
				} else {
					logEntry("[P2] Break Zone holds no " + what + " — skipping salvage");
				}

				// Apply picks in reverse-index order to preserve indices during removal
				List<ForwardTarget> p1Sorted = new ArrayList<>(p1Picks);
				p1Sorted.sort(java.util.Comparator.comparingInt(ForwardTarget::idx).reversed());
				for (ForwardTarget t : p1Sorted) {
					CardData card = p1Bz.remove(t.idx());
					mw.gameState.getP1Hand().add(card);
					logEntry(card.name() + " → P1 hand from Break Zone");
				}
				List<ForwardTarget> p2Sorted = new ArrayList<>(p2Picks);
				p2Sorted.sort(java.util.Comparator.comparingInt(ForwardTarget::idx).reversed());
				for (ForwardTarget t : p2Sorted) {
					CardData card = p2Bz.remove(t.idx());
					mw.gameState.getP2Hand().add(card);
					logEntry("[AI] " + card.name() + " → P2 hand from Break Zone");
				}

				if (!p1Picks.isEmpty()) { mw.refreshP1BreakLabel(); mw.refreshP1HandLabel(); }
				if (!p2Picks.isEmpty()) { mw.refreshP2BreakLabel(); mw.refreshP2HandCountLabel(); }
				if (!p1Picks.isEmpty()) mw.notifyCardsAddedToHandFromBreakZone(true);
				if (!p2Picks.isEmpty()) mw.notifyCardsAddedToHandFromBreakZone(false);
			}

			@Override public void salvageCharacterFromOwnBreakZone(int count, boolean fwds, boolean bkps, boolean mons) {
				List<CardData> bz = isP1 ? mw.gameState.getP1BreakZone() : mw.gameState.getP2BreakZone();
				List<ForwardTarget> eligible = new ArrayList<>();
				for (int i = 0; i < bz.size(); i++) {
					CardData c = bz.get(i);
					if (c.isForward() && !fwds) continue;
					if (c.isBackup()  && !bkps) continue;
					if (c.isMonster() && !mons) continue;
					ForwardTarget.CardZone cz = c.isBackup()  ? ForwardTarget.CardZone.BACKUP
					                          : c.isMonster() ? ForwardTarget.CardZone.MONSTER
					                          :                 ForwardTarget.CardZone.FORWARD;
					eligible.add(new ForwardTarget(isP1, i, cz));
				}
				if (eligible.isEmpty()) {
					logEntry((isP1 ? "P1" : "P2") + " Break Zone has no eligible character(s) — salvage skipped");
					return;
				}
				List<ForwardTarget> picks;
				if (isP1) {
					picks = mw.showBreakZoneSelectDialog(eligible, bz, count, false,
							"Choose " + count + " Character(s) from your Break Zone to add to hand");
				} else {
					List<ForwardTarget> copy = new ArrayList<>(eligible);
					copy.sort((a, b) -> Integer.compare(bz.get(b.idx()).cost(), bz.get(a.idx()).cost()));
					picks = copy.subList(0, Math.min(count, copy.size()));
					picks.forEach(t -> logEntry("[AI] salvaged " + bz.get(t.idx()).name() + " from Break Zone"));
				}
				List<ForwardTarget> sorted = new ArrayList<>(picks);
				sorted.sort(java.util.Comparator.comparingInt(ForwardTarget::idx).reversed());
				List<CardData> hand = isP1 ? mw.gameState.getP1Hand() : mw.gameState.getP2Hand();
				for (ForwardTarget t : sorted) {
					CardData card = bz.remove(t.idx());
					hand.add(card);
					logEntry(card.name() + " → " + (isP1 ? "P1" : "P2") + " hand from Break Zone");
				}
				if (isP1) { mw.refreshP1BreakLabel(); mw.refreshP1HandLabel(); }
				else       { mw.refreshP2BreakLabel(); mw.refreshP2HandCountLabel(); }
				if (!sorted.isEmpty()) mw.notifyCardsAddedToHandFromBreakZone(isP1);
			}

			@Override public void chooseWarpCardFromBreakZoneToHand() {
				List<CardData> bz = isP1 ? mw.gameState.getP1BreakZone() : mw.gameState.getP2BreakZone();
				List<ForwardTarget> eligible = new ArrayList<>();
				for (int i = 0; i < bz.size(); i++) {
					CardData c = bz.get(i);
					if (!c.hasWarp()) continue;
					ForwardTarget.CardZone cz = c.isBackup()  ? ForwardTarget.CardZone.BACKUP
					                          : c.isMonster() ? ForwardTarget.CardZone.MONSTER
					                          :                 ForwardTarget.CardZone.FORWARD;
					eligible.add(new ForwardTarget(isP1, i, cz));
				}
				if (eligible.isEmpty()) {
					logEntry((isP1 ? "P1" : "P2") + " Break Zone has no card with Warp — skipped");
					return;
				}
				ForwardTarget pick;
				if (isP1) {
					List<ForwardTarget> picks = mw.showBreakZoneSelectDialog(eligible, bz, 1, false,
							"Choose 1 Card with Warp from your Break Zone to add to hand");
					if (picks.isEmpty()) return;
					pick = picks.get(0);
				} else {
					pick = eligible.stream()
							.max(java.util.Comparator.comparingInt(t -> bz.get(t.idx()).cost()))
							.orElse(eligible.get(0));
					logEntry("[AI] chose " + bz.get(pick.idx()).name() + " (with Warp) from Break Zone");
				}
				CardData card = bz.remove(pick.idx());
				List<CardData> hand = isP1 ? mw.gameState.getP1Hand() : mw.gameState.getP2Hand();
				hand.add(card);
				logEntry(card.name() + " → " + (isP1 ? "P1" : "P2") + " hand from Break Zone");
				if (isP1) { mw.refreshP1BreakLabel(); mw.refreshP1HandLabel(); }
				else       { mw.refreshP2BreakLabel(); mw.refreshP2HandCountLabel(); }
				mw.notifyCardsAddedToHandFromBreakZone(isP1);
			}

			@Override public void eachPlayerSelectUpToNAndBreak(int count, boolean inclForwards, boolean inclMonsters) {
				// Build P1 eligible list
				List<ForwardTarget> p1Eligible = new ArrayList<>();
				if (inclForwards)
					for (int i = 0; i < mw.p1ForwardCards.size(); i++)
						p1Eligible.add(new ForwardTarget(true, i, ForwardTarget.CardZone.FORWARD));
				if (inclMonsters)
					for (int i = 0; i < mw.p1MonsterCards.size(); i++)
						p1Eligible.add(new ForwardTarget(true, i, ForwardTarget.CardZone.MONSTER));

				List<ForwardTarget> p1Picks;
				if (p1Eligible.isEmpty()) {
					logEntry("P1 has no eligible targets — skipping selection");
					p1Picks = List.of();
				} else {
					p1Picks = mw.selectFieldTargetsInPlace(p1Eligible, count, true,
							"Select up to " + count + " Forwards/Monsters to put in Break Zone");
				}

				// P2 AI: pick lowest-cost targets up to count
				List<ForwardTarget> p2Picks = mw.aiPickForwardsOrMonstersForBreak(count, inclForwards, inclMonsters);
				for (ForwardTarget t : p2Picks)
					logEntry("[AI] selected " + (t.zone() == ForwardTarget.CardZone.FORWARD
							? mw.p2ForwardCards.get(t.idx()).name()
							: mw.p2MonsterCards.get(t.idx()).name()));

				// Break in descending index order to avoid shifting
				p1Picks.stream().sorted(java.util.Comparator.comparingInt(ForwardTarget::idx).reversed())
						.forEach(this::forceTargetToBreakZone);
				p2Picks.stream().sorted(java.util.Comparator.comparingInt(ForwardTarget::idx).reversed())
						.forEach(this::forceTargetToBreakZone);
			}

			@Override public void activateTarget(ForwardTarget t) {
				switch (t.zone()) {
					case FORWARD -> {
						int i = t.idx();
						if (t.isP1()) { if (i < mw.p1ForwardCards.size()) { mw.p1ForwardStates.set(i, CardState.ACTIVE); logEntry(p1Forward(i).name() + " is activated"); mw.refreshP1ForwardSlot(i); } }
						else          { if (i < mw.p2ForwardCards.size()) { mw.p2ForwardStates.set(i, CardState.ACTIVE); logEntry("[P2] " + mw.p2ForwardCards.get(i).name() + " is activated"); mw.refreshP2ForwardSlot(i); } }
					}
					case BACKUP -> {
						int i = t.idx();
						if (t.isP1()) { if (i < mw.p1BackupCards.length && mw.p1BackupCards[i] != null) { mw.p1BackupStates[i] = CardState.ACTIVE; logEntry(mw.p1BackupCards[i].name() + " is activated"); mw.refreshP1BackupSlot(i); } }
						else          { if (i < mw.p2BackupCards.length && mw.p2BackupCards[i] != null) { mw.p2BackupStates[i] = CardState.ACTIVE; logEntry("[P2] " + mw.p2BackupCards[i].name() + " is activated"); mw.refreshP2BackupSlot(i); } }
					}
					case MONSTER -> {
						int i = t.idx();
						if (t.isP1()) { if (i < mw.p1MonsterCards.size()) { mw.p1MonsterStates.set(i, CardState.ACTIVE); logEntry(mw.p1MonsterCards.get(i).name() + " is activated"); mw.refreshP1MonsterSlot(i); } }
						else          { if (i < mw.p2MonsterCards.size()) { mw.p2MonsterStates.set(i, CardState.ACTIVE); logEntry("[P2] " + mw.p2MonsterCards.get(i).name() + " is activated"); mw.refreshP2MonsterSlot(i); } }
					}
				}
			}

			@Override public void dullTarget(ForwardTarget t) {
				switch (t.zone()) {
					case FORWARD -> { if (t.isP1()) dullP1Forward(t.idx()); else dullP2Forward(t.idx()); }
					case BACKUP  -> {
						int i = t.idx();
						if (t.isP1()) { if (i < mw.p1BackupCards.length && mw.p1BackupCards[i] != null) { mw.p1BackupStates[i] = CardState.DULL; logEntry(mw.p1BackupCards[i].name() + " is dulled"); mw.refreshP1BackupSlot(i); } }
						else          { if (i < mw.p2BackupCards.length && mw.p2BackupCards[i] != null) { mw.p2BackupStates[i] = CardState.DULL; logEntry("[P2] " + mw.p2BackupCards[i].name() + " is dulled"); mw.refreshP2BackupSlot(i); } }
					}
					case MONSTER -> {
						int i = t.idx();
						if (t.isP1()) { if (i < mw.p1MonsterCards.size()) { mw.p1MonsterStates.set(i, CardState.DULL); logEntry(mw.p1MonsterCards.get(i).name() + " is dulled"); mw.refreshP1MonsterSlot(i); } }
						else          { if (i < mw.p2MonsterCards.size()) { mw.p2MonsterStates.set(i, CardState.DULL); logEntry("[P2] " + mw.p2MonsterCards.get(i).name() + " is dulled"); mw.refreshP2MonsterSlot(i); } }
					}
				}
			}

			@Override public void toggleTargetDullActivate(ForwardTarget t) {
				CardState state = switch (t.zone()) {
					case FORWARD -> t.isP1()
							? (t.idx() < mw.p1ForwardStates.size() ? mw.p1ForwardStates.get(t.idx()) : null)
							: (t.idx() < mw.p2ForwardStates.size() ? mw.p2ForwardStates.get(t.idx()) : null);
					case BACKUP  -> t.isP1()
							? (t.idx() < mw.p1BackupCards.length && mw.p1BackupCards[t.idx()] != null ? mw.p1BackupStates[t.idx()] : null)
							: (t.idx() < mw.p2BackupCards.length && mw.p2BackupCards[t.idx()] != null ? mw.p2BackupStates[t.idx()] : null);
					case MONSTER -> t.isP1()
							? (t.idx() < mw.p1MonsterStates.size() ? mw.p1MonsterStates.get(t.idx()) : null)
							: (t.idx() < mw.p2MonsterStates.size() ? mw.p2MonsterStates.get(t.idx()) : null);
					default -> null;
				};
				if (state == null) return;
				if (state == CardState.DULL) activateTarget(t);
				else                         dullTarget(t);
			}

			@Override public void freezeTarget(ForwardTarget t) {
				switch (t.zone()) {
					case FORWARD -> { if (t.isP1()) freezeP1Forward(t.idx()); else freezeP2Forward(t.idx()); }
					case BACKUP  -> {
						int i = t.idx();
						if (t.isP1()) { if (i < mw.p1BackupCards.length && mw.p1BackupCards[i] != null) { mw.p1BackupFrozen[i] = true; logEntry(mw.p1BackupCards[i].name() + " is frozen"); mw.refreshP1BackupSlot(i); } }
						else          { if (i < mw.p2BackupCards.length && mw.p2BackupCards[i] != null) { mw.p2BackupFrozen[i] = true; logEntry("[P2] " + mw.p2BackupCards[i].name() + " is frozen"); mw.refreshP2BackupSlot(i); } }
					}
					case MONSTER -> {
						int i = t.idx();
						if (t.isP1()) { if (i < mw.p1MonsterCards.size()) { mw.p1MonsterFrozen.set(i, true); logEntry(mw.p1MonsterCards.get(i).name() + " is frozen"); mw.refreshP1MonsterSlot(i); } }
						else          { if (i < mw.p2MonsterCards.size()) { mw.p2MonsterFrozen.set(i, true); logEntry("[P2] " + mw.p2MonsterCards.get(i).name() + " is frozen"); mw.refreshP2MonsterSlot(i); } }
					}
				}
			}

			@Override public void dullOrFreezeTarget(ForwardTarget t) {
				CardState state = switch (t.zone()) {
					case FORWARD -> t.isP1()
							? (t.idx() < mw.p1ForwardStates.size() ? mw.p1ForwardStates.get(t.idx()) : null)
							: (t.idx() < mw.p2ForwardStates.size() ? mw.p2ForwardStates.get(t.idx()) : null);
					case BACKUP  -> t.isP1()
							? (t.idx() < mw.p1BackupCards.length && mw.p1BackupCards[t.idx()] != null ? mw.p1BackupStates[t.idx()] : null)
							: (t.idx() < mw.p2BackupCards.length && mw.p2BackupCards[t.idx()] != null ? mw.p2BackupStates[t.idx()] : null);
					case MONSTER -> t.isP1()
							? (t.idx() < mw.p1MonsterStates.size() ? mw.p1MonsterStates.get(t.idx()) : null)
							: (t.idx() < mw.p2MonsterStates.size() ? mw.p2MonsterStates.get(t.idx()) : null);
					default -> null;
				};
				if (state == null) return;
				CardData card = mw.autoAbilityTriggers.fieldCardData(t);
				String name = card != null ? card.name() : "Forward";
				boolean chooseDull;
				if (!isP1) {
					// AI picks the option that actually changes state
					chooseDull = (state != CardState.DULL);
					logEntry("[AI] chooses to " + (chooseDull ? "Dull" : "Freeze") + " " + name);
				} else {
					Object[] options = { "Dull", "Freeze" };
					int result = JOptionPane.showOptionDialog(mw.frame,
							"Dull or Freeze " + name + "?",
							"Dull or Freeze",
							JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE,
							null, options, options[0]);
					chooseDull = (result != 1);
				}
				if (chooseDull) dullTarget(t);
				else            freezeTarget(t);
			}

			@Override public void dullAndFreezeTarget(ForwardTarget t) { dullTarget(t); freezeTarget(t); }

			@Override public void breakTarget(ForwardTarget t) {
				CardData breakCard = mw.autoAbilityTriggers.fieldCardData(t);
				if (leaveFieldProtected(breakCard, t.isP1())) return;
				if (breakCard != null && !mw.lostAbilitiesCards.contains(breakCard)) {
					if (breakCard.hasTrait(CardData.Trait.CANNOT_BE_BROKEN)) {
						logEntry((t.isP1() ? "" : "[P2] ") + breakCard.name() + " cannot be broken (protected until end of turn)");
						return;
					}
					if (breakCard.hasTrait(CardData.Trait.CANNOT_BE_BROKEN_BY_NON_DMG)) {
						logEntry((t.isP1() ? "" : "[P2] ") + breakCard.name() + " cannot be broken by this effect (protected from non-damage breaks)");
						return;
					}
					if (t.zone() == ForwardTarget.CardZone.FORWARD && t.idx() >= 0) {
						EnumSet<CardData.Trait> tmp = t.isP1()
								? mw.p1ForwardTempTraits.get(t.idx())
								: mw.p2ForwardTempTraits.get(t.idx());
						if (tmp.contains(CardData.Trait.CANNOT_BE_BROKEN)
								|| (t.isP1() ? mw.effectiveP1HasTrait(t.idx(), CardData.Trait.CANNOT_BE_BROKEN)
								             : mw.effectiveP2HasTrait(t.idx(), CardData.Trait.CANNOT_BE_BROKEN))) {
							logEntry((t.isP1() ? "" : "[P2] ") + breakCard.name() + " cannot be broken");
							return;
						}
						if (tmp.contains(CardData.Trait.CANNOT_BE_BROKEN_BY_NON_DMG)
								|| (t.isP1() ? mw.effectiveP1HasTrait(t.idx(), CardData.Trait.CANNOT_BE_BROKEN_BY_NON_DMG)
								             : mw.effectiveP2HasTrait(t.idx(), CardData.Trait.CANNOT_BE_BROKEN_BY_NON_DMG))) {
							logEntry((t.isP1() ? "" : "[P2] ") + breakCard.name() + " cannot be broken by this effect (protected from non-damage breaks)");
							return;
						}
					} else {
						// Off the Forward row the traits above are read straight off the printed card,
						// which misses every field-granted one — a Backup could not be protected at all
						// until Auron 1-002R ("The Backups you control cannot be broken by your
						// opponent's Summons or abilities.") needed it to be. The per-slot temp-trait
						// lists are Forward-only, so only the conditional grants are consulted here.
						EnumSet<CardData.Trait> granted = mw.fieldGrantCalculator
								.computeConditionalTraitsForTarget(breakCard, t.isP1());
						if (granted.contains(CardData.Trait.CANNOT_BE_BROKEN)) {
							logEntry((t.isP1() ? "" : "[P2] ") + breakCard.name() + " cannot be broken");
							return;
						}
						if (granted.contains(CardData.Trait.CANNOT_BE_BROKEN_BY_NON_DMG)) {
							logEntry((t.isP1() ? "" : "[P2] ") + breakCard.name() + " cannot be broken by this effect (protected from non-damage breaks)");
							return;
						}
					}
				}
				switch (t.zone()) {
					case FORWARD -> { if (t.isP1()) breakP1Forward(t.idx()); else breakP2Forward(t.idx()); }
					case BACKUP  -> {
						int i = t.idx();
						CardData[] cards = t.isP1() ? mw.p1BackupCards : mw.p2BackupCards;
						CardState[] states = t.isP1() ? mw.p1BackupStates : mw.p2BackupStates;
						if (i >= cards.length || cards[i] == null) return;
						CardData c = cards[i];
						String prefix = t.isP1() ? "" : "[P2] ";
						logEntry(prefix + c.name() + " is broken");
						(t.isP1() ? mw.gameState.getP1BreakZone() : mw.gameState.getP2BreakZone()).add(c);
						JLabel backupLbl = t.isP1() ? mw.p1BackupLabels[i] : mw.p2BackupLabels[i];
						if (backupLbl != null) mw.startBreakAnim(backupLbl);
						cards[i] = null; states[i] = CardState.ACTIVE;
						if (t.isP1()) {
							mw.p1BackupUrls[i] = null;
							if (mw.p1BackupLabels[i] != null) { mw.p1BackupLabels[i].setIcon(null); mw.p1BackupLabels[i].setText(null); }
							mw.refreshP1BreakLabel();
						} else {
							mw.p2BackupUrls[i] = null;
							if (mw.p2BackupLabels[i] != null) { mw.p2BackupLabels[i].setIcon(null); mw.p2BackupLabels[i].setText(null); }
							mw.refreshP2BreakLabel();
						}
					}
					case MONSTER -> {
						int i = t.idx();
						List<CardData> cards = t.isP1() ? mw.p1MonsterCards : mw.p2MonsterCards;
						if (i >= cards.size()) return;
						CardData c = cards.get(i);
						String prefix = t.isP1() ? "" : "[P2] ";
						logEntry(prefix + c.name() + " is broken");
						(t.isP1() ? mw.gameState.getP1BreakZone() : mw.gameState.getP2BreakZone()).add(c);
						cards.remove(i);
						(t.isP1() ? mw.p1MonsterStates : mw.p2MonsterStates).remove(i);
						(t.isP1() ? mw.p1MonsterFrozen : mw.p2MonsterFrozen).remove(i);
						(t.isP1() ? mw.p1MonsterPlayedOnTurn : mw.p2MonsterPlayedOnTurn).remove(i);
						(t.isP1() ? mw.p1MonsterUrls : mw.p2MonsterUrls).remove(i);
						JLabel lbl = (t.isP1() ? mw.p1MonsterLabels : mw.p2MonsterLabels).remove(i);
						mw.startBreakAnim(lbl);
						JPanel panel = t.isP1() ? mw.p1MonsterPanel : mw.p2MonsterPanel;
						panel.remove(lbl); panel.revalidate(); panel.repaint();
						if (t.isP1()) mw.refreshP1BreakLabel(); else mw.refreshP2BreakLabel();
					}
				}
			}

			@Override public void removeTargetFromGame(ForwardTarget t) {
				// A Break Zone card is not on the field, so the shield has nothing to say about it.
				if (t.zone() != ForwardTarget.CardZone.BREAK_ZONE
						&& leaveFieldProtected(mw.autoAbilityTriggers.fieldCardData(t), t.isP1())) return;
				// The Break Zone has a shield of its own (Lenna 18-100L, Ultimecia 22-073L, and
				// Terra 23-011L for Summons). Checked ahead of the crediting below: a removal that
				// never happens must not be credited to the resolving ability, or a later "the
				// cards removed by this ability" wording would call back a card still in the zone.
				if (t.zone() == ForwardTarget.CardZone.BREAK_ZONE && t.isP1() != isP1) {
					CardData shielded = cardAtTarget(t);
					if (shielded != null && mw.bzCardProtectedFromOppRfg(shielded, t.isP1())) {
						logEntry(shielded.name() + " cannot be removed from the game by your opponent's "
								+ "Summons or abilities");
						return;
					}
				}
				// Credit the removal to the ability resolving right now, so wordings like "cards
				// removed by Anima's ability" (19-123H, whose enter-the-field effect removes Break
				// Zone cards) can call them back later. Resolved per zone because the field-card
				// lookup does not cover the Break Zone.
				if (mw.currentAbilitySource != null) {
					CardData removedCard = cardAtTarget(t);
					if (removedCard != null)
						mw.cardsRemovedBySource.computeIfAbsent(mw.currentAbilitySource, k -> new ArrayList<>())
								.add(removedCard);
				}
				switch (t.zone()) {
					case FORWARD -> { if (t.isP1()) removeP1ForwardFromGame(t.idx()); else removeP2ForwardFromGame(t.idx()); }
					case BACKUP  -> {
						int i = t.idx();
						CardData[] cards = t.isP1() ? mw.p1BackupCards : mw.p2BackupCards;
						CardState[] states = t.isP1() ? mw.p1BackupStates : mw.p2BackupStates;
						if (i >= cards.length || cards[i] == null) return;
						logEntry((t.isP1() ? "" : "[P2] ") + cards[i].name() + " → Removed From Game");
						mw.gameState.addToPermanentRfp(cards[i]);
						cards[i] = null; states[i] = CardState.ACTIVE;
						if (t.isP1()) mw.refreshP1BackupSlot(i); else mw.refreshP2BackupSlot(i);
					}
					case MONSTER -> {
						int i = t.idx();
						List<CardData> cards = t.isP1() ? mw.p1MonsterCards : mw.p2MonsterCards;
						if (i >= cards.size()) return;
						CardData c = cards.get(i);
						logEntry((t.isP1() ? "" : "[P2] ") + c.name() + " → Removed From Game");
						mw.gameState.addToPermanentRfp(c);
						cards.remove(i);
						(t.isP1() ? mw.p1MonsterStates : mw.p2MonsterStates).remove(i);
						(t.isP1() ? mw.p1MonsterFrozen : mw.p2MonsterFrozen).remove(i);
						(t.isP1() ? mw.p1MonsterPlayedOnTurn : mw.p2MonsterPlayedOnTurn).remove(i);
						(t.isP1() ? mw.p1MonsterUrls : mw.p2MonsterUrls).remove(i);
						JLabel lbl = (t.isP1() ? mw.p1MonsterLabels : mw.p2MonsterLabels).remove(i);
						JPanel panel = t.isP1() ? mw.p1MonsterPanel : mw.p2MonsterPanel;
						panel.remove(lbl); panel.revalidate(); panel.repaint();
					}
					case BREAK_ZONE -> {
						int i = t.idx();
						List<CardData> bz = t.isP1() ? mw.gameState.getP1BreakZone() : mw.gameState.getP2BreakZone();
						if (i >= bz.size()) return;
						CardData c = bz.remove(i);
						mw.lastRemovedFromGameCardCost  = c.cost();
						mw.lastRemovedFromGameCardPower = c.power();
						logEntry((t.isP1() ? "" : "[P2] ") + c.name() + " → Removed From Game (from Break Zone)");
						mw.gameState.addToPermanentRfp(c);
						if (t.isP1()) { mw.refreshP1BreakLabel(); mw.refreshP1WarpZoneUI(); }
						else          { mw.refreshP2BreakLabel(); mw.refreshP2WarpZoneUI(); }
					}
				}
			}

			@Override public void removeTopCardsOfDeckFromGame(int count, CardData source) {
				Deque<CardData> deck = isP1 ? mw.gameState.getP1MainDeck() : mw.gameState.getP2MainDeck();
				for (int i = 0; i < count && !deck.isEmpty(); i++) {
					CardData c = deck.pollFirst();
					mw.gameState.addToPermanentRfp(c);
					if (source != null)
						mw.cardsRemovedBySource.computeIfAbsent(source, k -> new ArrayList<>()).add(c);
					logEntry(c.name() + " → Removed From Game (top of deck)");
				}
				if (isP1) { mw.refreshP1DeckLabel(); mw.refreshP1WarpZoneUI(); }
				else      { mw.refreshP2DeckLabel(); mw.refreshP2WarpZoneUI(); }
			}

			/** The card {@code t} currently points at, in any zone including the Break Zone; null if none. */
			private CardData cardAtTarget(ForwardTarget t) {
				if (t == null) return null;
				int i = t.idx();
				switch (t.zone()) {
					case FORWARD -> {
						List<CardData> l = t.isP1() ? mw.p1ForwardCards : mw.p2ForwardCards;
						return i >= 0 && i < l.size() ? l.get(i) : null;
					}
					case BACKUP -> {
						CardData[] a = t.isP1() ? mw.p1BackupCards : mw.p2BackupCards;
						return i >= 0 && i < a.length ? a[i] : null;
					}
					case MONSTER -> {
						List<CardData> l = t.isP1() ? mw.p1MonsterCards : mw.p2MonsterCards;
						return i >= 0 && i < l.size() ? l.get(i) : null;
					}
					case BREAK_ZONE -> {
						List<CardData> l = t.isP1() ? mw.gameState.getP1BreakZone() : mw.gameState.getP2BreakZone();
						return i >= 0 && i < l.size() ? l.get(i) : null;
					}
					default -> { return null; }
				}
			}

			@Override public int cardsRemovedBySourceCount(CardData source) {
				List<CardData> removed = source == null ? null : mw.cardsRemovedBySource.get(source);
				return removed == null ? 0 : removed.size();
			}

			@Override public void putCardsRemovedBySourceIntoBreakZone(CardData source) {
				List<CardData> removed = source == null ? null : mw.cardsRemovedBySource.remove(source);
				if (removed == null || removed.isEmpty()) return;
				for (CardData c : removed) {
					mw.gameState.removeFromPermanentRfp(c);
					mw.addToBreakZone(c, false);
					logEntry(c.name() + " → Break Zone (was removed by " + source.name() + ")");
				}
				if (isP1) mw.refreshP1WarpZoneUI(); else mw.refreshP2WarpZoneUI();
				mw.refreshP1BreakLabel();
				mw.refreshP2BreakLabel();
			}

			@Override public int addCardsRemovedBySourceToHand(CardData source, int count) {
				List<CardData> removed = source == null ? null : mw.cardsRemovedBySource.get(source);
				if (removed == null || removed.isEmpty()) {
					logEntry((source != null ? source.name() : "Effect")
							+ " — no cards left removed by its earlier effect");
					return 0;
				}
				String title = (source.name() + " — add 1 removed card to your hand");
				for (int i = 0; i < count && !removed.isEmpty(); i++) {
					int pick;
					if (removed.size() == 1) {
						pick = 0;
					} else if (isP1) {
						pick = mw.cardPickerDialog.pickCardImage(removed, title, false);
						if (pick < 0) pick = 0;   // dialog dismissed — take the first rather than stall
					} else {
						pick = 0;                  // AI takes the costliest of what it set aside
						for (int j = 1; j < removed.size(); j++)
							if (removed.get(j).cost() > removed.get(pick).cost()) pick = j;
					}
					CardData c = removed.remove(pick);
					mw.gameState.removeFromPermanentRfp(c);
					(isP1 ? mw.gameState.getP1Hand() : mw.gameState.getP2Hand()).add(c);
					logEntry((isP1 ? "" : "[P2] ") + c.name() + " → hand (removed by " + source.name() + ")");
				}
				if (isP1) { mw.refreshP1HandLabel();      mw.refreshP1WarpZoneUI(); }
				else      { mw.refreshP2HandCountLabel(); mw.refreshP2WarpZoneUI(); }
				if (removed.isEmpty()) mw.cardsRemovedBySource.remove(source);
				return removed.size();
			}

			@Override public int removeTopCardOfDeckFromGameAndGetCost() {
				Deque<CardData> deck = isP1 ? mw.gameState.getP1MainDeck() : mw.gameState.getP2MainDeck();
				if (deck.isEmpty()) { logEntry("Deck is empty — no card removed"); return 0; }
				CardData c = deck.pollFirst();
				mw.gameState.addToPermanentRfp(c);
				logEntry(c.name() + " → Removed From Game (top of deck, cost=" + c.cost() + ")");
				if (isP1) mw.refreshP1DeckLabel(); else mw.refreshP2DeckLabel();
				return c.cost();
			}

			@Override public boolean removeTopCardOfDeckFromGameIsForward() {
				Deque<CardData> deck = isP1 ? mw.gameState.getP1MainDeck() : mw.gameState.getP2MainDeck();
				if (deck.isEmpty()) { logEntry("Deck is empty — no card removed"); return false; }
				CardData c = deck.pollFirst();
				mw.gameState.addToPermanentRfp(c);
				boolean fwd = c.isForward();
				logEntry(c.name() + " → Removed From Game (top of deck, " + (fwd ? "Forward" : c.type()) + ")");
				if (isP1) mw.refreshP1DeckLabel(); else mw.refreshP2DeckLabel();
				return fwd;
			}

			@Override public int revealTopNAndAddAllToHandGetTotalCP(int n) {
				Deque<CardData> deck = isP1 ? mw.gameState.getP1MainDeck() : mw.gameState.getP2MainDeck();
				List<CardData> hand = isP1 ? mw.gameState.getP1Hand() : mw.gameState.getP2Hand();
				int take = Math.min(n, deck.size());
				if (take == 0) { logEntry("Deck is empty — no cards revealed"); return 0; }
				List<CardData> revealed = new ArrayList<>();
				for (int i = 0; i < take; i++) revealed.add(deck.pollFirst());
				int totalCp = revealed.stream().mapToInt(CardData::cost).sum();
				String prefix = isP1 ? "" : "[P2] ";
				logEntry(prefix + "Reveal top " + take + " card(s): " +
						revealed.stream().map(CardData::name).collect(Collectors.joining(", ")) +
						" (total CP=" + totalCp + ")");
				hand.addAll(revealed);
				if (isP1) { mw.refreshP1DeckLabel(); mw.refreshP1HandLabel(); }
				else       { mw.refreshP2DeckLabel(); mw.refreshP2HandCountLabel(); }
				return totalCp;
			}

			@Override public int revealTopNCountJobPlaceAllAtBottom(int n, String job) {
				Deque<CardData> deck = isP1 ? mw.gameState.getP1MainDeck() : mw.gameState.getP2MainDeck();
				int take = Math.min(n, deck.size());
				if (take == 0) { logEntry("Deck is empty — no cards revealed"); return 0; }
				List<CardData> revealed = new ArrayList<>();
				for (int i = 0; i < take; i++) revealed.add(deck.pollFirst());
				int matchCount = (int) revealed.stream().filter(c -> CardFilters.meetsJobFilter(c, job)).count();
				String prefix = isP1 ? "" : "[P2] ";
				logEntry(prefix + "Reveal top " + take + " card(s): " +
						revealed.stream().map(CardData::name).collect(Collectors.joining(", ")) +
						" (Job " + job + " matches: " + matchCount + ")");
				java.util.Collections.shuffle(revealed);
				for (CardData c : revealed) { deck.addLast(c); logEntry(c.name() + " → bottom of deck"); }
				if (isP1) mw.refreshP1DeckLabel(); else mw.refreshP2DeckLabel();
				return matchCount;
			}

			@Override public void shuffleDeck() {
				Deque<CardData> deck = isP1 ? mw.gameState.getP1MainDeck() : mw.gameState.getP2MainDeck();
				List<CardData> list = new java.util.ArrayList<>(deck);
				java.util.Collections.shuffle(list);
				deck.clear();
				deck.addAll(list);
				if (isP1) mw.refreshP1DeckLabel(); else mw.refreshP2DeckLabel();
				logEntry("Shuffled deck");
			}

			@Override public ForwardTarget playTargetOntoField(ForwardTarget t) {
				List<CardData> bz = t.isP1() ? mw.gameState.getP1BreakZone() : mw.gameState.getP2BreakZone();
				if (t.idx() >= bz.size()) return null;
				// "You cannot play X due to Summons or abilities." — checked before the card leaves
				// the Break Zone, so a blocked play is a no-op rather than a card in limbo.
				if (bz.get(t.idx()).playByEffectProhibited(false)) {
					logEntry(bz.get(t.idx()).name() + " cannot be played onto the field by an ability");
					markEffectFizzled();
					return null;
				}
				CardData card = bz.remove(t.idx());
				String src = t.isP1() ? "Break Zone" : "opponent's Break Zone";
				logEntry(card.name() + " played from " + src + " onto field");
				ForwardTarget landed;
				if (t.isP1()) {
					if (card.isBackup())       { mw.placeCardInFirstBackupSlot(card); landed = ownBackupTargetOf(true, card); }
					else if (card.isMonster()) { mw.placeCardInMonsterZone(card);     landed = new ForwardTarget(true, mw.p1MonsterCards.size() - 1, ForwardTarget.CardZone.MONSTER); }
					else                       { mw.placeCardInForwardZone(card);     landed = new ForwardTarget(true, mw.p1ForwardCards.size() - 1, ForwardTarget.CardZone.FORWARD); }
				} else {
					if (card.isBackup())       { mw.placeP2CardInFirstBackupSlot(card); landed = ownBackupTargetOf(false, card); }
					else if (card.isMonster()) { mw.placeP2CardInMonsterZone(card);     landed = new ForwardTarget(false, mw.p2MonsterCards.size() - 1, ForwardTarget.CardZone.MONSTER); }
					else                       { mw.placeP2CardInForwardZone(card);     landed = new ForwardTarget(false, mw.p2ForwardCards.size() - 1, ForwardTarget.CardZone.FORWARD); }
				}
				if (t.isP1()) mw.refreshP1BreakLabel(); else mw.refreshP2BreakLabel();
				return landed;
			}

			/** The backup slot {@code card} occupies after being placed, or {@code null} if not found. */
			private ForwardTarget ownBackupTargetOf(boolean p1, CardData card) {
				CardData[] slots = p1 ? mw.p1BackupCards : mw.p2BackupCards;
				for (int i = 0; i < slots.length; i++)
					if (slots[i] == card) return new ForwardTarget(p1, i, ForwardTarget.CardZone.BACKUP);
				return null;
			}

			@Override public List<ForwardTarget> selectTwoOwnBreakZoneForwards(
					String element, int maxCost1, int maxCost2) {
				List<CardData> bz = isP1 ? mw.gameState.getP1BreakZone() : mw.gameState.getP2BreakZone();
				ForwardTarget first = pickOwnBzForward(bz, element, maxCost1, -1);
				if (first == null) return List.of();
				ForwardTarget second = pickOwnBzForward(bz, element, maxCost2, first.idx());
				if (second == null) return List.of();
				return List.of(first, second);
			}

			/**
			 * Picks one Break Zone Forward of {@code element} costing at most {@code maxCost},
			 * skipping Break Zone index {@code excludeIdx} ({@code -1} to skip nothing).
			 */
			private ForwardTarget pickOwnBzForward(List<CardData> bz, String element, int maxCost, int excludeIdx) {
				List<ForwardTarget> eligible = new ArrayList<>();
				for (int i = 0; i < bz.size(); i++) {
					if (i == excludeIdx) continue;
					CardData card = bz.get(i);
					if (!card.isForward()) continue;
					if (element != null && !card.containsElement(element)) continue;
					if (card.cost() > maxCost) continue;
					eligible.add(new ForwardTarget(isP1, i, ForwardTarget.CardZone.BREAK_ZONE));
				}
				if (eligible.isEmpty()) return null;
				if (!isP1) {
					ForwardTarget pick = eligible.get(new java.util.Random().nextInt(eligible.size()));
					logEntry("[AI] chose " + bz.get(pick.idx()).name());
					return pick;
				}
				String title = "Choose 1 " + element + " Forward of cost " + maxCost
						+ " or less in your Break Zone";
				List<ForwardTarget> chosen = mw.showBreakZoneSelectDialog(eligible, bz, 1, false, title);
				return chosen.isEmpty() ? null : chosen.get(0);
			}

			@Override public void playTargetOntoFieldDull(ForwardTarget t) {
				List<CardData> bz = t.isP1() ? mw.gameState.getP1BreakZone() : mw.gameState.getP2BreakZone();
				if (t.idx() >= bz.size()) return;
				if (bz.get(t.idx()).playByEffectProhibited(false)) {
					logEntry(bz.get(t.idx()).name() + " cannot be played onto the field by an ability");
					markEffectFizzled();
					return;
				}
				CardData card = bz.remove(t.idx());
				String src = t.isP1() ? "Break Zone" : "opponent's Break Zone";
				logEntry(card.name() + " played from " + src + " onto field (dull)");
				if (t.isP1()) {
					mw.placeCardInForwardZone(card);
					int newIdx = mw.p1ForwardCards.size() - 1;
					mw.p1ForwardStates.set(newIdx, CardState.DULL);
					mw.refreshP1ForwardSlot(newIdx);
				} else {
					mw.placeP2CardInForwardZone(card);
					int newIdx = mw.p2ForwardCards.size() - 1;
					mw.p2ForwardStates.set(newIdx, CardState.DULL);
					mw.refreshP2ForwardSlot(newIdx);
				}
				if (t.isP1()) mw.refreshP1BreakLabel(); else mw.refreshP2BreakLabel();
			}

			@Override public void playTriggeringBrokenCardOntoFieldDull() {
				CardData broken = mw.triggeringBrokenCard;
				if (broken == null) {
					logEntry("No card was placed in the Break Zone by this trigger");
					markEffectFizzled();
					return;
				}
				// Found by identity in the resolving player's own Break Zone: the zone routinely
				// holds several copies of a name, and it is this instance's return that was earned.
				List<CardData> bz = isP1() ? mw.gameState.getP1BreakZone() : mw.gameState.getP2BreakZone();
				int idx = -1;
				for (int i = 0; i < bz.size(); i++) if (bz.get(i) == broken) { idx = i; break; }
				if (idx < 0) {
					logEntry(broken.name() + " is no longer in the Break Zone");
					markEffectFizzled();
					return;
				}
				playTargetOntoFieldDull(new ForwardTarget(isP1(), idx, ForwardTarget.CardZone.FORWARD));
			}

			@Override public void addTargetToHand(ForwardTarget t) {
				List<CardData> bz = t.isP1() ? mw.gameState.getP1BreakZone() : mw.gameState.getP2BreakZone();
				if (t.idx() >= bz.size()) return;
				CardData card = bz.remove(t.idx());
				// "Add it to your hand" — the hand belongs to whoever is resolving the effect, which
				// is the target's own side for a salvage but the other side when the effect reaches
				// into the opponent's Break Zone.
				(isP1 ? mw.gameState.getP1Hand() : mw.gameState.getP2Hand()).add(card);
				logEntry((isP1 ? "" : "[P2] ") + card.name()
						+ (t.isP1() == isP1 ? " returned from Break Zone to hand"
						                    : " taken from opponent's Break Zone to hand"));
				if (t.isP1()) mw.refreshP1BreakLabel(); else mw.refreshP2BreakLabel();
				if (isP1) mw.refreshP1HandLabel(); else mw.refreshP2HandCountLabel();
				mw.notifyCardsAddedToHandFromBreakZone(isP1);
			}

			@Override public void putBreakZoneTargetOnTopOfDeck(ForwardTarget t) {
				List<CardData> bz = t.isP1() ? mw.gameState.getP1BreakZone() : mw.gameState.getP2BreakZone();
				if (t.idx() < 0 || t.idx() >= bz.size()) return;
				CardData card = bz.remove(t.idx());
				(isP1 ? mw.gameState.getP1MainDeck() : mw.gameState.getP2MainDeck()).addFirst(card);
				logEntry((isP1 ? "" : "[P2] ") + card.name() + " → Break Zone to top of deck");
				if (t.isP1()) mw.refreshP1BreakLabel(); else mw.refreshP2BreakLabel();
				if (isP1) mw.refreshP1DeckLabel(); else mw.refreshP2DeckLabel();
			}

			@Override public void putBreakZoneTargetOnBottomOfDeck(ForwardTarget t) {
				List<CardData> bz = t.isP1() ? mw.gameState.getP1BreakZone() : mw.gameState.getP2BreakZone();
				if (t.idx() < 0 || t.idx() >= bz.size()) return;
				CardData card = bz.remove(t.idx());
				(isP1 ? mw.gameState.getP1MainDeck() : mw.gameState.getP2MainDeck()).addLast(card);
				logEntry((isP1 ? "" : "[P2] ") + card.name() + " → Break Zone to bottom of deck");
				if (t.isP1()) mw.refreshP1BreakLabel(); else mw.refreshP2BreakLabel();
				if (isP1) mw.refreshP1DeckLabel(); else mw.refreshP2DeckLabel();
			}

			@Override public CardData p1BreakZoneCard(int idx) {
				List<CardData> bz = mw.gameState.getP1BreakZone();
				return (idx >= 0 && idx < bz.size()) ? bz.get(idx) : null;
			}

			@Override public CardData p2BreakZoneCard(int idx) {
				List<CardData> bz = mw.gameState.getP2BreakZone();
				return (idx >= 0 && idx < bz.size()) ? bz.get(idx) : null;
			}

			@Override public void boostTarget(ForwardTarget t, int amount,
					EnumSet<CardData.Trait> traits) {
				boolean isP1    = t.isP1();
				boolean monster = t.zone() == ForwardTarget.CardZone.MONSTER;
				int     idx     = t.idx();

				// Backups are only valid Forward-targets while they are acting as a Forward.
				if (t.zone() == ForwardTarget.CardZone.BACKUP) {
					boolean asFwd = isP1 ? mw.isP1BackupTemporarilyForward(idx) : mw.isP2BackupTemporarilyForward(idx);
					CardData[] bcards = isP1 ? mw.p1BackupCards : mw.p2BackupCards;
					if (!asFwd || idx < 0 || idx >= bcards.length || bcards[idx] == null) return;
					CardData bcard = bcards[idx];
					(isP1 ? mw.p1BackupForwardBoost : mw.p2BackupForwardBoost).merge(bcard, amount, Integer::sum);
					EnumSet<CardData.Trait> bGranted = EnumSet.noneOf(CardData.Trait.class);
					if (!traits.isEmpty()) {
						(isP1 ? mw.p1BackupTempTraits : mw.p2BackupTempTraits)
								.computeIfAbsent(bcard, k -> EnumSet.noneOf(CardData.Trait.class))
								.addAll(traits);
						bGranted.addAll(traits);
					}
					StringBuilder bsb = new StringBuilder();
					if (!bGranted.isEmpty())
						bsb.append(bGranted.stream().map(CardData.Trait::displayName)
								.collect(Collectors.joining(" and ")));
					if (amount != 0) {
						if (bsb.length() > 0) bsb.append(" and ");
						bsb.append("+").append(amount).append(" power");
					}
					logEntry((isP1 ? "" : "[P2] ") + bcard.name() + " gains " + bsb + " until end of turn");
					if (isP1) mw.refreshP1BackupSlot(idx); else mw.refreshP2BackupSlot(idx);
					return;
				}

				List<CardData> cards = monster ? (isP1 ? mw.p1MonsterCards : mw.p2MonsterCards)
				                               : (isP1 ? mw.p1ForwardCards : mw.p2ForwardCards);
				if (idx < 0 || idx >= cards.size()) return;
				CardData card = cards.get(idx);

				// A Monster only keeps granted traits while it is actually acting as a Forward.
				EnumSet<CardData.Trait> grantedTraits = EnumSet.noneOf(CardData.Trait.class);
				if (monster) {
					(isP1 ? mw.p1MonsterPowerBoost : mw.p2MonsterPowerBoost).merge(card, amount, Integer::sum);
					boolean asForward = isP1 ? mw.isP1MonsterTemporarilyForward(idx)
					                         : mw.isP2MonsterTemporarilyForward(idx);
					if (asForward && !traits.isEmpty()) {
						(isP1 ? mw.p1MonsterTempTraits : mw.p2MonsterTempTraits)
								.computeIfAbsent(card, k -> EnumSet.noneOf(CardData.Trait.class))
								.addAll(traits);
						grantedTraits.addAll(traits);
					}
				} else {
					List<Integer> boost = isP1 ? mw.p1ForwardPowerBoost : mw.p2ForwardPowerBoost;
					int effectiveAmount = amount;
					if (amount > 0 && (mw.oppForwardPowerBoostSuppressedFor(isP1) || mw.oppForwardSelfBoostSuppressedFor(isP1))) {
						logEntry((isP1 ? "" : "[P2] ") + card.name() + " — power boost suppressed (opponent's field ability)");
						effectiveAmount = 0;
					}
					// NOTE: local isP1 is the TARGET's side (shadows the acting player); isP1() is the actor.
					if (amount < 0 && isP1 != isP1()
							&& (isP1 ? mw.effectiveP1HasTrait(idx, CardData.Trait.POWER_CANNOT_BE_DECREASED_BY_OPP)
							         : mw.effectiveP2HasTrait(idx, CardData.Trait.POWER_CANNOT_BE_DECREASED_BY_OPP))) {
						logEntry((isP1 ? "" : "[P2] ") + card.name() + " — power cannot be decreased by opponent's effects");
						effectiveAmount = 0;
					}
					boost.set(idx, boost.get(idx) + effectiveAmount);
					(isP1 ? mw.p1ForwardTempTraits : mw.p2ForwardTempTraits).get(idx).addAll(traits);
					grantedTraits.addAll(traits);
				}

				StringBuilder sb = new StringBuilder();
				if (!grantedTraits.isEmpty()) {
					sb.append(grantedTraits.stream().map(CardData.Trait::displayName)
							.collect(Collectors.joining(" and ")));
				}
				if (amount != 0) {
					if (sb.length() > 0) sb.append(" and ");
					sb.append("+").append(amount).append(" power");
				}

				logEntry((isP1 ? "" : "[P2] ") + card.name() + " gains " + sb + " until end of turn");
				if (monster) { if (isP1) mw.refreshP1MonsterSlot(idx); else mw.refreshP2MonsterSlot(idx); }
				else         { if (isP1) mw.refreshP1ForwardSlot(idx); else mw.refreshP2ForwardSlot(idx); }
			}

			@Override public void removeTraitsUntilEotFromTarget(ForwardTarget t,
					EnumSet<CardData.Trait> traits) {
				if (t.zone() != ForwardTarget.CardZone.FORWARD) return;
				List<EnumSet<CardData.Trait>> removedList =
						t.isP1() ? mw.p1ForwardRemovedTraits : mw.p2ForwardRemovedTraits;
				if (t.idx() >= removedList.size()) return;
				removedList.get(t.idx()).addAll(traits);
				CardData c = mw.autoAbilityTriggers.fieldCardData(t);
				if (c != null) logEntry((t.isP1() ? "" : "[P2] ") + c.name() + " loses "
						+ traits.stream().map(tr -> tr.name().toLowerCase().replace('_', ' '))
						        .collect(Collectors.joining(", "))
						+ " until end of turn");
				if (t.isP1()) mw.refreshP1ForwardSlot(t.idx());
				else           mw.refreshP2ForwardSlot(t.idx());
			}

			@Override public boolean effectiveTargetHasTrait(ForwardTarget t, CardData.Trait trait) {
				if (t.zone() != ForwardTarget.CardZone.FORWARD) return false;
				return t.isP1() ? mw.effectiveP1HasTrait(t.idx(), trait)
				                : mw.effectiveP2HasTrait(t.idx(), trait);
			}

			// Was a row search matching on name(), which granted to the wrong copy when two of the
			// same card were on the field and missed a source attacking from another row entirely.
			// The set is keyed by instance, so the source can just be written in.
			@Override public void setSourceForwardCannotBeBlocked(CardData source) {
				if (source == null) return;
				(isP1 ? mw.p1CannotBeBlocked : mw.p2CannotBeBlocked).add(source);
			}

			@Override public void boostSourceForward(CardData source, int amount,
					EnumSet<CardData.Trait> traits) {
				List<CardData> fwds = isP1 ? mw.p1ForwardCards : mw.p2ForwardCards;
				List<Integer> powerBoost = isP1 ? mw.p1ForwardPowerBoost : mw.p2ForwardPowerBoost;
				List<EnumSet<CardData.Trait>> tempTraits = isP1 ? mw.p1ForwardTempTraits : mw.p2ForwardTempTraits;
				for (int i = 0; i < fwds.size(); i++) {
					if (fwds.get(i).name().equals(source.name())) {
						if (amount > 0 && (mw.oppForwardPowerBoostSuppressedFor(isP1) || mw.oppForwardSelfBoostSuppressedFor(isP1))) {
							logEntry(source.name() + " — power boost suppressed (opponent's field ability)");
							return;
						}
						powerBoost.set(i, powerBoost.get(i) + amount);
						tempTraits.get(i).addAll(traits);
						logEntry(source.name() + " gains +" + amount + " power until end of turn");
						if (isP1) mw.refreshP1ForwardSlot(i); else mw.refreshP2ForwardSlot(i);
						return;
					}
				}
			}

			@Override public void boostSourceForwardPermanently(CardData source, int amount,
					EnumSet<CardData.Trait> traits) {
				List<CardData> fwds = isP1 ? mw.p1ForwardCards : mw.p2ForwardCards;
				for (int i = 0; i < fwds.size(); i++) {
					CardData card = fwds.get(i);
					if (!card.name().equals(source.name())) continue;
					if (amount > 0 && (mw.oppForwardPowerBoostSuppressedFor(isP1) || mw.oppForwardSelfBoostSuppressedFor(isP1))) {
						logEntry(source.name() + " — power boost suppressed (opponent's field ability)");
						return;
					}
					if (amount > 0) mw.permanentPowerBoost.merge(card, amount, Integer::sum);
					if (!traits.isEmpty())
						mw.permanentTraits.computeIfAbsent(card, k -> EnumSet.noneOf(CardData.Trait.class)).addAll(traits);
					logEntry(source.name() + " gains "
							+ (amount > 0 ? "+" + amount + " power" : "")
							+ (amount > 0 && !traits.isEmpty() ? " and " : "")
							+ (traits.isEmpty() ? "" : traits.toString())
							+ " (does not end at end of turn)");
					if (isP1) mw.refreshP1ForwardSlot(i); else mw.refreshP2ForwardSlot(i);
					return;
				}
			}

			@Override public void doubleSourceForwardPower(CardData source,
					EnumSet<CardData.Trait> traits) {
				List<CardData> fwds = isP1 ? mw.p1ForwardCards : mw.p2ForwardCards;
				List<Integer> powerBoost = isP1 ? mw.p1ForwardPowerBoost : mw.p2ForwardPowerBoost;
				List<EnumSet<CardData.Trait>> tempTraits = isP1 ? mw.p1ForwardTempTraits : mw.p2ForwardTempTraits;
				for (int i = 0; i < fwds.size(); i++) {
					if (fwds.get(i).name().equals(source.name())) {
						if (mw.oppForwardPowerBoostSuppressedFor(isP1) || mw.oppForwardSelfBoostSuppressedFor(isP1)) {
							logEntry(source.name() + " — power doubling suppressed (opponent's field ability)");
							return;
						}
						int current = isP1 ? mw.effectiveP1ForwardPower(i) : mw.effectiveP2ForwardPower(i);
						powerBoost.set(i, powerBoost.get(i) + current);
						tempTraits.get(i).addAll(traits);
						logEntry(source.name() + " — power doubled to " + (current * 2) + " until end of turn");
						if (isP1) mw.refreshP1ForwardSlot(i); else mw.refreshP2ForwardSlot(i);
						return;
					}
				}
			}

			@Override public void setSourceForwardBasePower(CardData source, int power,
					EnumSet<CardData.Trait> traits) {
				List<CardData> fwds = isP1 ? mw.p1ForwardCards : mw.p2ForwardCards;
				List<EnumSet<CardData.Trait>> tempTraits = isP1 ? mw.p1ForwardTempTraits : mw.p2ForwardTempTraits;
				for (int i = 0; i < fwds.size(); i++) {
					CardData card = fwds.get(i);
					if (!card.name().equals(source.name())) continue;
					applyBasePowerOverride(card, power);
					tempTraits.get(i).addAll(traits);
					String traitList = ActionResolver.traitNamesOnly(traits);
					logEntry(source.name() + " — base power becomes " + power
							+ (traitList.isEmpty() ? "" : ", gains " + traitList)
							+ " until end of turn");
					if (isP1) mw.refreshP1ForwardSlot(i); else mw.refreshP2ForwardSlot(i);
					mw.enforceForwardBreakRuleProcess();
					return;
				}
			}

			@Override public void setSourceForwardBasePowerPermanently(CardData source, int power,
					EnumSet<CardData.Trait> traits) {
				List<CardData> fwds = isP1 ? mw.p1ForwardCards : mw.p2ForwardCards;
				for (int i = 0; i < fwds.size(); i++) {
					CardData card = fwds.get(i);
					if (!card.name().equals(source.name())) continue;
					// Same map as the end-of-turn override, minus the end-of-turn removal hook —
					// that hook is the only thing that makes the other one temporary.
					mw.basePowerOverrides.put(card, power);
					if (!traits.isEmpty())
						mw.permanentTraits.computeIfAbsent(card, k -> EnumSet.noneOf(CardData.Trait.class)).addAll(traits);
					String traitList = ActionResolver.traitNamesOnly(traits);
					logEntry(source.name() + " — base power becomes " + power
							+ (traitList.isEmpty() ? "" : ", gains " + traitList)
							+ " (does not end at end of turn)");
					if (isP1) mw.refreshP1ForwardSlot(i); else mw.refreshP2ForwardSlot(i);
					mw.enforceForwardBreakRuleProcess();
					return;
				}
			}

			@Override public void grantSelfFieldAbilityPermanently(CardData source, String abilityText) {
				mw.permanentFieldAbilities.computeIfAbsent(source, k -> new ArrayList<>())
						.add(new FieldAbility(abilityText, 0));
				logEntry(source.name() + " gains \"" + abilityText + "\" (does not end at end of turn)");
			}

			@Override public void shieldSelfCannotBeChosenPermanently(CardData source, boolean bySummons,
					boolean byAbilities) {
				if (bySummons)   mw.permanentCannotBeChosenBySummons.add(source);
				if (byAbilities) mw.permanentCannotBeChosenByAbilities.add(source);
				logEntry(source.name() + " cannot be chosen by your opponent's "
						+ (bySummons && byAbilities ? "Summons or abilities"
								: bySummons ? "Summons" : "abilities")
						+ " (does not end at end of turn)");
			}

			@Override public void grantSelfMustAttackOncePerTurnPermanently(CardData source) {
				mw.permanentMustAttackOncePerTurn.add(source);
				logEntry(source.name() + " must attack once per turn if possible"
						+ " (does not end at end of turn)");
			}

			/**
			 * Written into the same set the permanent grant uses, with an end-of-turn hook to take
			 * it out again — the arrangement {@code basePowerOverrides} already uses to hold both
			 * durations of one effect, and what keeps the compulsion's single reader
			 * ({@code MainWindow.p1ForwardCompelledToAttackIdx}) from needing a second set.
			 *
			 * <p>A card already carrying the standing compulsion is left alone: adding it again
			 * would be a no-op on a set, but the removal hook would then strip a grant that was
			 * never meant to lapse.
			 */
			@Override public void grantMustAttackOncePerTurnUntilEndOfTurn(ForwardTarget target) {
				CardData card = mw.autoAbilityTriggers.fieldCardData(target);
				if (card == null) return;
				if (!mw.permanentMustAttackOncePerTurn.add(card)) return;
				mw.endOfTurnEffects.add(ctx -> mw.permanentMustAttackOncePerTurn.remove(card));
				logEntry(card.name() + " must attack once per turn if possible (until end of turn)");
			}

			/**
			 * Records a base-power override for {@code card} and queues its removal at the end of
			 * the turn.  Boosts and reductions are deliberately left alone — they layer on top of
			 * the new base rather than being replaced by it.
			 */
			private void applyBasePowerOverride(CardData card, int power) {
				mw.basePowerOverrides.put(card, power);
				mw.endOfTurnEffects.add(ctx -> {
					mw.basePowerOverrides.remove(card);
					refreshSlotFor(card);
				});
			}

			@Override public void addPendingMainPhase1Effect(Consumer<GameContext> effect) {
				mw.pendingMainPhase1Effects.add(effect);
			}

			@Override public void setTargetBasePower(ForwardTarget t, int power) {
				if (t.zone() != ForwardTarget.CardZone.FORWARD) return;
				List<CardData> fwds = t.isP1() ? mw.p1ForwardCards : mw.p2ForwardCards;
				int idx = t.idx();
				if (idx < 0 || idx >= fwds.size()) return;
				CardData card = fwds.get(idx);
				applyBasePowerOverride(card, power);
				logEntry((t.isP1() ? "" : "[P2] ") + card.name()
						+ " — base power becomes " + power + " until end of turn");
				if (t.isP1()) mw.refreshP1ForwardSlot(idx); else mw.refreshP2ForwardSlot(idx);
				mw.enforceForwardBreakRuleProcess();
			}

			@Override public void placeCounters(CardData card, String counterName, int count) {
				mw.gameState.placeCounters(card, counterName, count);
				Map<String, Integer> all = mw.gameState.getCountersMap(card);
				logEntry(card.name() + " — placed " + count + " " + counterName
						+ " Counter(s)  [now: " + all + "]");
				refreshSlotFor(card);
			}

			@Override public int getCounters(CardData card, String counterName) {
				return mw.gameState.getCounters(card, counterName);
			}

			@Override public void placeCountersOnOwnJobCards(String counterName, int count, String jobFilter) {
				List<CardData> fwds = isP1 ? mw.p1ForwardCards : mw.p2ForwardCards;
				for (int i = 0; i < fwds.size(); i++) {
					if (!CardFilters.meetsJobFilter(fwds.get(i), jobFilter)) continue;
					mw.gameState.placeCounters(fwds.get(i), counterName, count);
					if (isP1) mw.refreshP1ForwardSlot(i); else mw.refreshP2ForwardSlot(i);
					logEntry((isP1 ? "" : "[P2] ") + fwds.get(i).name() + " — " + count + " "
							+ counterName + " Counter(s) placed");
				}
				CardData[] bkps = isP1 ? mw.p1BackupCards : mw.p2BackupCards;
				for (int i = 0; i < bkps.length; i++) {
					if (bkps[i] == null || !CardFilters.meetsJobFilter(bkps[i], jobFilter)) continue;
					mw.gameState.placeCounters(bkps[i], counterName, count);
					if (isP1) mw.refreshP1BackupSlot(i); else mw.refreshP2BackupSlot(i);
					logEntry((isP1 ? "" : "[P2] ") + bkps[i].name() + " — " + count + " "
							+ counterName + " Counter(s) placed");
				}
				List<CardData> mons = isP1 ? mw.p1MonsterCards : mw.p2MonsterCards;
				for (int i = 0; i < mons.size(); i++) {
					if (!CardFilters.meetsJobFilter(mons.get(i), jobFilter)) continue;
					mw.gameState.placeCounters(mons.get(i), counterName, count);
					if (isP1) mw.refreshP1MonsterSlot(i); else mw.refreshP2MonsterSlot(i);
					logEntry((isP1 ? "" : "[P2] ") + mons.get(i).name() + " — " + count + " "
							+ counterName + " Counter(s) placed");
				}
			}

			@Override public void placeCountersOnAllForwards(String counterName, int count,
					boolean opponentOnly, boolean selfOnly) {
				boolean touchP1 = isP1 ? !opponentOnly : !selfOnly;
				boolean touchP2 = isP1 ? !selfOnly     : !opponentOnly;
				int touched = 0;
				if (touchP1) {
					for (int i = 0; i < mw.p1ForwardCards.size(); i++) {
						mw.gameState.placeCounters(mw.p1ForwardCards.get(i), counterName, count);
						mw.refreshP1ForwardSlot(i);
						touched++;
					}
				}
				if (touchP2) {
					for (int i = 0; i < mw.p2ForwardCards.size(); i++) {
						mw.gameState.placeCounters(mw.p2ForwardCards.get(i), counterName, count);
						mw.refreshP2ForwardSlot(i);
						touched++;
					}
				}
				logEntry("Placed " + count + " " + counterName + " Counter(s) on " + touched + " Forward(s)");
			}

			@Override public void grantEotActionAbility(ForwardTarget target, String abilityText) {
				if (target.zone() != ForwardTarget.CardZone.FORWARD) return;
				List<ActionAbility> parsed = CardData.parseActionAbilities(abilityText);
				if (parsed.isEmpty()) return;
				List<CardData> fwds = target.isP1() ? mw.p1ForwardCards : mw.p2ForwardCards;
				if (target.idx() < 0 || target.idx() >= fwds.size()) return;
				CardData fwd = fwds.get(target.idx());
				if (fwd == null) return;
				// Keyed by card identity (not index) so it survives Forward-list compaction; the
				// end-of-turn reset (clearBackupForwardState) wipes the whole temp-grant map.
				Map<CardData, List<ActionAbility>> map = target.isP1()
						? mw.p1TempGrantedAbilities : mw.p2TempGrantedAbilities;
				map.computeIfAbsent(fwd, k -> new ArrayList<>()).add(parsed.get(0));
				logEntry(fwd.name() + " gains until end of turn: " + parsed.get(0).effectText());
			}

			@Override public void removeCounters(CardData card, String counterName, int count) {
				int removed = mw.gameState.removeCounters(card, counterName, count);
				Map<String, Integer> all = mw.gameState.getCountersMap(card);
				logEntry(card.name() + " — removed " + removed + " " + counterName
						+ " Counter(s)  [now: " + all + "]");
				refreshSlotFor(card);
			}

			/** Refreshes whichever field slot currently holds {@code card}, if any. */
			private void refreshSlotFor(CardData card) {
				for (int i = 0; i < mw.p1ForwardCards.size(); i++) {
					if (mw.p1ForwardCards.get(i) == card) { mw.refreshP1ForwardSlot(i); return; }
				}
				for (int i = 0; i < mw.p2ForwardCards.size(); i++) {
					if (mw.p2ForwardCards.get(i) == card) { mw.refreshP2ForwardSlot(i); return; }
				}
				for (int i = 0; i < mw.p1BackupCards.length; i++) {
					if (mw.p1BackupCards[i] == card) { mw.refreshP1BackupSlot(i); return; }
				}
				for (int i = 0; i < mw.p2BackupCards.length; i++) {
					if (mw.p2BackupCards[i] == card) { mw.refreshP2BackupSlot(i); return; }
				}
				for (int i = 0; i < mw.p1MonsterCards.size(); i++) {
					if (mw.p1MonsterCards.get(i) == card) { mw.refreshP1MonsterSlot(i); return; }
				}
				for (int i = 0; i < mw.p2MonsterCards.size(); i++) {
					if (mw.p2MonsterCards.get(i) == card) { mw.refreshP2MonsterSlot(i); return; }
				}
			}

			@Override public void removeOneCounterFromTarget(ForwardTarget t) {
				CardData card = switch (t.zone()) {
					case BACKUP  -> t.isP1() ? mw.p1BackupCards[t.idx()] : mw.p2BackupCards[t.idx()];
					case MONSTER -> t.isP1() ? mw.p1MonsterCards.get(t.idx()) : mw.p2MonsterCards.get(t.idx());
					default      -> t.isP1() ? mw.p1ForwardCards.get(t.idx()) : mw.p2ForwardCards.get(t.idx());
				};
				if (card == null) { logEntry("removeOneCounter — target card not found"); return; }
				Map<String, Integer> counters = mw.gameState.getCountersMap(card);
				if (counters.isEmpty()) {
					logEntry(card.name() + " — no counters to remove (fizzle)");
					return;
				}
				String chosen;
				if (counters.size() == 1) {
					chosen = counters.keySet().iterator().next();
				} else {
					String[] types = counters.keySet().toArray(new String[0]);
					chosen = selectOption("Select a Counter to remove from " + card.name(), types);
					if (chosen == null) chosen = types[0];
				}
				mw.gameState.removeCounters(card, chosen, 1);
				logEntry(card.name() + " — removed 1 " + chosen + " Counter  [remaining: "
						+ mw.gameState.getCountersMap(card) + "]");
				int ridx = t.idx();
				switch (t.zone()) {
					case BACKUP  -> { if (t.isP1()) mw.refreshP1BackupSlot(ridx); else mw.refreshP2BackupSlot(ridx); }
					case MONSTER -> { if (t.isP1()) mw.refreshP1MonsterSlot(ridx); else mw.refreshP2MonsterSlot(ridx); }
					default      -> { if (t.isP1()) mw.refreshP1ForwardSlot(ridx); else mw.refreshP2ForwardSlot(ridx); }
				}
			}

			@Override public void duplicateOneCounterOnTarget(ForwardTarget t) {
				CardData card = mw.autoAbilityTriggers.fieldCardData(t);
				if (card == null) { logEntry("duplicateOneCounter — target card not found"); return; }
				Map<String, Integer> counters = mw.gameState.getCountersMap(card);
				if (counters.isEmpty()) {
					logEntry(card.name() + " — no counters to copy (fizzle)");
					return;
				}
				String chosen;
				if (counters.size() == 1) {
					chosen = counters.keySet().iterator().next();
				} else {
					String[] types = counters.keySet().toArray(new String[0]);
					chosen = selectOption("Select a Counter to duplicate on " + card.name(), types);
					if (chosen == null) chosen = types[0];
				}
				mw.gameState.placeCounters(card, chosen, 1);
				logEntry(card.name() + " — placed 1 additional " + chosen + " Counter  [now: "
						+ mw.gameState.getCountersMap(card) + "]");
				switch (t.zone()) {
					case BACKUP  -> { if (t.isP1()) mw.refreshP1BackupSlot(t.idx()); else mw.refreshP2BackupSlot(t.idx()); }
					case MONSTER -> { if (t.isP1()) mw.refreshP1MonsterSlot(t.idx()); else mw.refreshP2MonsterSlot(t.idx()); }
					default      -> { if (t.isP1()) mw.refreshP1ForwardSlot(t.idx()); else mw.refreshP2ForwardSlot(t.idx()); }
				}
			}

			@Override public void lookAtTopDeck(LookConfig config) {
				lastLookAddedToHand = mw.lookDialogs().show(config, isP1, mw.isP2Cpu());
			}

			@Override public void revealTopAddToHandIfType(String cardType) {
				Deque<CardData> deck = isP1 ? mw.gameState.getP1MainDeck() : mw.gameState.getP2MainDeck();
				if (deck.isEmpty()) { logEntry("Reveal top card: deck is empty."); return; }
				CardData top = deck.peekFirst();
				if (!CardFilters.matchesDiscardType(top, cardType)) {
					// The miss is a player decision, and lookAtTopDeck logs the reveal itself —
					// so no reveal line here, or the card would be announced twice.
					lookAtTopDeck(new LookConfig(1, LookConfig.LookAction.TOP_OR_BOTTOM_ORDERED,
							null, null, true));
					return;
				}
				deck.pollFirst();
				if (isP1) { mw.gameState.getP1Hand().add(top); mw.refreshP1HandLabel();      mw.refreshP1DeckLabel(); }
				else      { mw.gameState.getP2Hand().add(top); mw.refreshP2HandCountLabel(); mw.refreshP2DeckLabel(); }
				logEntry("Reveal top card: " + top.name() + " — a " + cardType + " → hand");
			}

			@Override public void triggerExBurstOfCardAddedToHand() {
				CardData added = lastLookAddedToHand;
				if (added == null) {
					logEntry("[EX Burst] No card was added to hand");
					return;
				}
				if (!added.exBurst() || added.exBurstEffect().isEmpty()) {
					logEntry("[EX Burst] " + added.name() + " has no EX Burst to trigger");
					return;
				}
				// The card stays in hand — only its EX Burst effect goes on the stack, as with
				// Akstar's Damage Zone version. The AI always takes a free EX Burst, which is what
				// it did when this was a P2-only branch.
				if (!askYesNo(isP1, ChoiceKind.EX_BURST,
						"Trigger " + added.name() + "'s EX Burst effect?", "EX Burst",
						"Waiting for your opponent to decide on an EX Burst...", true)) {
					logEntry("[EX Burst] " + added.name() + " — declined");
					return;
				}
				logEntry("[EX Burst] " + added.name() + " — placed on stack");
				mw.gameState.pushStack(new StackEntry(added, isP1, true));
				if (isP1) mw.showStackWindow(); else mw.showStackWindowIfNeeded();
			}

			@Override public void lookAtTopDeckCastSummonFreeRestBottom(int count, int maxCost) {
				Deque<CardData> deck = isP1 ? mw.gameState.getP1MainDeck() : mw.gameState.getP2MainDeck();
				int n = Math.min(count, deck.size());
				if (n == 0) { logEntry("Look at top: deck is empty."); return; }
				List<CardData> peeked = new ArrayList<>();
				for (CardData c : deck) { peeked.add(c); if (peeked.size() >= n) break; }
				logEntry("Look at top " + n + " card(s): " +
						peeked.stream().map(CardData::name).collect(Collectors.joining(", ")));

				List<CardData> eligible = peeked.stream()
						.filter(c -> c.isSummon() && (maxCost < 0 || c.cost() <= maxCost))
						.collect(Collectors.toList());

				CardData picked = null;
				if (eligible.isEmpty()) {
					logEntry("No eligible Summon (cost " + maxCost + " or less) among top " + n + " card(s)");
				} else if (isP1) {
					String title = "Cast 1 Summon (cost " + maxCost + " or less) from top " + n + " for free";
					int listIdx = mw.showCardImageChooser(eligible, title, false);
					if (listIdx >= 0) picked = eligible.get(listIdx);
				} else {
					picked = eligible.stream()
							.max(java.util.Comparator.comparingInt(CardData::cost))
							.orElse(null);
					if (picked != null) logEntry("[AI] chose " + picked.name());
				}

				for (int i = 0; i < n; i++) deck.pollFirst();

				if (picked != null) {
					mw.turn(isP1).summonCastThisTurn = true;
					mw.noteCardCast(picked, isP1);
					mw.noteDoublecastSummonCast(isP1, picked);
					mw.lastCardWasCast = true;
					logEntry((isP1 ? "" : "[P2] ") + "Cast \"" + picked.name() + "\" from top of deck for free");
					mw.showSummonOnStack(picked, isP1);
					mw.lastCardWasCast = false;
				}

				List<CardData> rest = new ArrayList<>(peeked);
				if (picked != null) rest.remove(picked);
				java.util.Collections.shuffle(rest);
				for (CardData c : rest) {
					deck.addLast(c);
					logEntry(c.name() + " → bottom of deck");
				}
				if (isP1) mw.refreshP1DeckLabel(); else mw.refreshP2DeckLabel();
			}

			@Override public void reduceTarget(ForwardTarget t, int amount,
					EnumSet<CardData.Trait> traits) {
				if (t.zone() == ForwardTarget.CardZone.BACKUP) return;
				if (t.isP1()) {
					int idx = t.idx();
					if (idx >= mw.p1ForwardCards.size()) return;
					mw.p1ForwardPowerReduction.set(idx, mw.p1ForwardPowerReduction.get(idx) + amount);
					mw.p1ForwardRemovedTraits.get(idx).addAll(traits);
					logEntry(p1Forward(idx).name() + " loses " + amount + " power"
							+ (!traits.isEmpty() ? (amount > 0 ? " and " : "") + traits : "") + " until end of turn");
					mw.refreshP1ForwardSlot(idx);
				} else {
					int idx = t.idx();
					if (idx >= mw.p2ForwardCards.size()) return;
					mw.p2ForwardPowerReduction.set(idx, mw.p2ForwardPowerReduction.get(idx) + amount);
					mw.p2ForwardRemovedTraits.get(idx).addAll(traits);
					logEntry("[P2] " + mw.p2ForwardCards.get(idx).name() + " loses "
							+ (amount > 0 ? amount + " power" : "")
							+ (!traits.isEmpty() ? (amount > 0 ? " and " : "") + traits : "") + " until end of turn");
					mw.refreshP2ForwardSlot(idx);
				}
				// Covers both the 0-power rule process and a Forward whose existing damage is now lethal.
				mw.enforceForwardBreakRuleProcess();
			}

			@Override public void reduceSourceForward(CardData source, int amount,
					EnumSet<CardData.Trait> traits) {
				List<CardData> fwds = isP1 ? mw.p1ForwardCards : mw.p2ForwardCards;
				for (int i = 0; i < fwds.size(); i++) {
					if (fwds.get(i).name().equals(source.name())) {
						reduceTarget(new ForwardTarget(isP1, i, ForwardTarget.CardZone.FORWARD), amount, traits);
						return;
					}
				}
			}

			@Override public int dullForwardCostPower() { return mw.lastDullForwardCostPower; }
			@Override public int lastDiscardedForwardPower() { return mw.lastDiscardedForwardPower; }
			@Override public int bzCostForwardPower() { return mw.lastBzCostForwardPower; }
			@Override public void suppressExBurstsThisAbility() { mw.suppressExBurstsThisAbility = true; }
			@Override public void setAiPrefersOwnTargets(boolean preferOwn) { mw.aiPrefersOwnTargets = preferOwn; }
			@Override public void setAiDamageTargetHint(int damage) { aiDamageTargetHint = Math.max(0, damage); }
			@Override public void grantSelfExBurstSuppression(CardData source) {
				if (source == null) return;
				// No printed grant wording carries a cost filter, so the grant covers any cost.
				if (mw.exBurstSuppressingSources.put(source, Integer.MAX_VALUE) == null) {
					mw.endOfTurnEffects.add(ctx -> mw.exBurstSuppressingSources.remove(source));
				}
				logEntry(source.name() + " — EX Bursts of cards it puts into the Damage Zone "
						+ "cannot be used until end of turn");
			}
			@Override public String lastDiscardedCardName() { return mw.lastDiscardedCardName; }
			@Override public List<String> lastDiscardedCostCardElements() {
				return mw.lastDiscardedCostCard == null ? List.of() : List.of(mw.lastDiscardedCostCard.elements());
			}
			@Override public boolean lastDiscardedCostCardIsSummon() {
				return mw.lastDiscardedCostCard != null && mw.lastDiscardedCostCard.isSummon();
			}
			@Override public String lastDiscardedCostCardName() {
				return mw.lastDiscardedCostCard == null ? null : mw.lastDiscardedCostCard.name();
			}
			@Override public boolean lastDiscardedCardIsMultiElement() {
				return mw.lastDiscardedCard != null && mw.lastDiscardedCard.containsElement("Multi-Element");
			}
			@Override public int lastRemovedFromGameCardCost()  { return mw.lastRemovedFromGameCardCost; }
			@Override public int lastRemovedFromGameCardPower() { return mw.lastRemovedFromGameCardPower; }
			@Override public int countRemovedFromGame() {
				return mw.gameState.getP1PermanentRfp().size() + mw.gameState.getP2PermanentRfp().size();
			}

			@Override public void retriggerAutoAbility(CardData source, String triggerType) {
				for (AutoAbility fa : source.autoAbilities()) {
					if (fa.trigger().equals(triggerType)) {
						mw.logEntry("[AutoAbility] " + source.name() + " — retriggered (" + triggerType + ")");
						StackEntry retriggered = new StackEntry(source, null, fa, isP1, 0, false, null, false, false, 0, 0);
						mw.gameState.pushStack(retriggered);
						// A retrigger is an auto ability going on the Stack like any other, so
						// Bahamut (XVI) 29-115L sees it — and it is the first one of the turn as
						// readily as a first trigger is.
						mw.cancelFirstOppForwardAuto(retriggered);
						mw.showStackWindowIfNeeded();
						return;
					}
				}
				mw.logEntry("[AutoAbility] " + source.name() + " — no ability with trigger '" + triggerType + "' to retrigger");
			}

			@Override public List<String> chooseActions(CardData source,
					List<String> actions, int selectCount, boolean upTo) {
				if (isP1) return mw.autoAbilityTriggers.showSelectActionsDialog(source, actions, selectCount, upTo);
				// AI: a "remove from either/opponent's Break Zone" action is worth taking only when
				// the opponent has cards there. When they do, prefer it (strips their resources);
				// when they don't, skip it entirely (it would only hit our own cards). All other
				// actions keep their original top-down order behind any preferred removal.
				boolean oppBzEmpty = (isP1 ? mw.gameState.getP2BreakZone()
						: mw.gameState.getP1BreakZone()).isEmpty();
				List<String> preferred = new ArrayList<>();
				List<String> rest      = new ArrayList<>();
				for (String a : actions) {
					if (removesFromOpponentBreakZone(a)) {
						if (!oppBzEmpty) preferred.add(a); // else: nothing to remove — drop it
					} else {
						rest.add(a);
					}
				}
				List<String> ordered = new ArrayList<>(preferred.size() + rest.size());
				ordered.addAll(preferred);
				ordered.addAll(rest);
				int take = Math.min(selectCount, ordered.size());
				return new ArrayList<>(ordered.subList(0, take));
			}

			@Override public int highestP1ForwardPower() {
				int max = 0;
				for (int i = 0; i < mw.p1ForwardCards.size(); i++)
					max = Math.max(max, mw.effectiveP1ForwardPower(i));
				return max;
			}

			@Override public int highestP2ForwardPower() {
				int max = 0;
				for (int i = 0; i < mw.p2ForwardCards.size(); i++)
					max = Math.max(max, mw.effectiveP2ForwardPower(i));
				return max;
			}

			@Override public int lowestP1ForwardPower() {
				int min = Integer.MAX_VALUE;
				for (int i = 0; i < mw.p1ForwardCards.size(); i++)
					min = Math.min(min, mw.effectiveP1ForwardPower(i));
				return min == Integer.MAX_VALUE ? 0 : min;
			}

			@Override public int lowestP2ForwardPower() {
				int min = Integer.MAX_VALUE;
				for (int i = 0; i < mw.p2ForwardCards.size(); i++)
					min = Math.min(min, mw.effectiveP2ForwardPower(i));
				return min == Integer.MAX_VALUE ? 0 : min;
			}

			@Override public int fieldForwardPowerByName(String cardName) {
				for (int i = 0; i < mw.p1ForwardCards.size(); i++)
					if (mw.p1ForwardCards.get(i).name().equalsIgnoreCase(cardName))
						return mw.effectiveP1ForwardPower(i);
				for (int i = 0; i < mw.p2ForwardCards.size(); i++)
					if (mw.p2ForwardCards.get(i).name().equalsIgnoreCase(cardName))
						return mw.effectiveP2ForwardPower(i);
				for (int i = 0; i < mw.p1MonsterCards.size(); i++)
					if (mw.p1MonsterCards.get(i).name().equalsIgnoreCase(cardName))
						return mw.effectiveP1MonsterPower(i);
				for (int i = 0; i < mw.p2MonsterCards.size(); i++)
					if (mw.p2MonsterCards.get(i).name().equalsIgnoreCase(cardName))
						return mw.effectiveP2MonsterPower(i);
				logEntry("[ActionResolver] fieldForwardPowerByName: \"" + cardName + "\" not found on field");
				return -1;
			}

			@Override public int combatBlockerIdxForAttacker(String attackerName, boolean attackerIsP1) {
					if (attackerIsP1) {
						if (mw.p2BlockedByAttacker != null && mw.p2BlockedByAttacker.name().equalsIgnoreCase(attackerName))
							return mw.p2BlockingIdx;
					} else {
						if (mw.p1BlockedByAttacker != null && mw.p1BlockedByAttacker.name().equalsIgnoreCase(attackerName))
							return mw.p1BlockingIdx;
					}
					return -1;
				}

			@Override public ForwardTarget combatBattlePartnerOf(String cardName) {
				// Attacking half: the named card is the attacker one of the blockers points at.
				if (mw.p2BlockedByAttacker != null
						&& mw.p2BlockedByAttacker.name().equalsIgnoreCase(cardName)
						&& mw.p2BlockingIdx >= 0)
					return new ForwardTarget(false, mw.p2BlockingIdx, ForwardTarget.CardZone.FORWARD);
				if (mw.p1BlockedByAttacker != null
						&& mw.p1BlockedByAttacker.name().equalsIgnoreCase(cardName)
						&& mw.p1BlockingIdx >= 0)
					return new ForwardTarget(true, mw.p1BlockingIdx, ForwardTarget.CardZone.FORWARD);

				// Blocking half: the named card is the blocker, so the partner is the attacker it
				// blocks. That attacker is held as a CardData, so its index is looked up on the
				// side opposite the blocker.
				if (mw.p1BlockingIdx >= 0 && mw.p1BlockingIdx < mw.p1ForwardCards.size()
						&& mw.p1ForwardCards.get(mw.p1BlockingIdx).name().equalsIgnoreCase(cardName)
						&& mw.p1BlockedByAttacker != null) {
					int idx = mw.p2ForwardCards.indexOf(mw.p1BlockedByAttacker);
					if (idx >= 0) return new ForwardTarget(false, idx, ForwardTarget.CardZone.FORWARD);
				}
				if (mw.p2BlockingIdx >= 0 && mw.p2BlockingIdx < mw.p2ForwardCards.size()
						&& mw.p2ForwardCards.get(mw.p2BlockingIdx).name().equalsIgnoreCase(cardName)
						&& mw.p2BlockedByAttacker != null) {
					int idx = mw.p1ForwardCards.indexOf(mw.p2BlockedByAttacker);
					if (idx >= 0) return new ForwardTarget(true, idx, ForwardTarget.CardZone.FORWARD);
				}
				return null;
			}

			@Override public int effectiveTargetPower(ForwardTarget t) {
				if (t.zone() == ForwardTarget.CardZone.BACKUP) return 0;
				if (t.zone() == ForwardTarget.CardZone.FORWARD)
					return t.isP1()
							? (t.idx() < mw.p1ForwardCards.size() ? mw.effectiveP1ForwardPower(t.idx()) : 0)
							: (t.idx() < mw.p2ForwardCards.size() ? mw.effectiveP2ForwardPower(t.idx()) : 0);
				return t.isP1()
						? (t.idx() < mw.p1MonsterCards.size() ? mw.effectiveP1MonsterPower(t.idx()) : 0)
						: (t.idx() < mw.p2MonsterCards.size() ? mw.effectiveP2MonsterPower(t.idx()) : 0);
			}

			@Override public void forceOpponentDiscard(int count) {
				if (isP1) {
					List<CardData> hand = mw.gameState.getP2Hand();
					int actual = Math.min(count, hand.size());
					for (int i = 0; i < actual; i++) {
						int idx = MainWindow.pickWorstHandCard0(hand);
						CardData d = mw.playerBreakFromHand(false,idx);
						if (d != null) {
							logEntry("[P2] Discards " + d.name() + " (forced)");
							mw.p2Turn.discardedByEffectThisTurn = true;
							mw.p1Turn.causedOpponentDiscardThisTurn = true;
						}
					}
					mw.refreshP2HandCountLabel();
					mw.refreshP2BreakLabel();
				} else {
					mw.showForcedDiscardDialog(count, true);
					mw.p2Turn.causedOpponentDiscardThisTurn = true;
				}
			}

			@Override public void forceOpponentRandomDiscard(int count) {
				boolean victimIsP1 = !isP1;
				List<CardData> hand = victimIsP1 ? mw.gameState.getP1Hand() : mw.gameState.getP2Hand();
				for (int idx : randomPicks(count, hand.size(), "a random discard")) {
					CardData d = mw.playerBreakFromHand(victimIsP1, idx);
					if (d == null) continue;
					logEntry("[" + (victimIsP1 ? "P1" : "P2") + "] Randomly discards " + d.name());
					(victimIsP1 ? mw.p1Turn : mw.p2Turn).discardedByEffectThisTurn    = true;
					(victimIsP1 ? mw.p2Turn : mw.p1Turn).causedOpponentDiscardThisTurn = true;
				}
				if (victimIsP1) { mw.refreshP1HandLabel();      mw.refreshP1BreakLabel(); }
				else            { mw.refreshP2HandCountLabel(); mw.refreshP2BreakLabel(); }
			}

			@Override public void drawCardsForOpponent(int count) {
				if (isP1) {
					int drew = mw.drawP2Cards(count).size();
					mw.animateCardDraw(false, drew);
					mw.refreshP2DeckLabel();
					mw.refreshP2HandCountLabel();
					// Forcing the opponent to draw more than their deck holds loses the game for them.
					if (drew < count) mw.triggerGameOver("P2 milled out — You Win!");
				} else {
					int drew = mw.drawP1Cards(count).size();
					mw.animateCardDraw(true, drew);
					mw.refreshP1HandLabel();
					mw.refreshP1DeckLabel();
					if (drew < count) mw.triggerGameOver("Milled Out - You Lose!");
				}
			}

			@Override public void forceOpponentRandomHandRfp(int count) {
				boolean victimIsP1 = !isP1;
				List<CardData> hand = victimIsP1 ? mw.gameState.getP1Hand() : mw.gameState.getP2Hand();
				for (int idx : randomPicks(count, hand.size(), "a random card to remove from the game")) {
					// P1's hand goes through GameState so its own bookkeeping runs; P2's is the
					// list itself. Kept as it was rather than made uniform in passing.
					CardData d = victimIsP1 ? mw.gameState.removeFromHand(idx) : hand.remove(idx);
					if (d == null) continue;
					mw.gameState.addToPermanentRfp(d);
					logEntry("[" + (victimIsP1 ? "P1" : "P2") + "] Randomly removed from game: " + d.name());
				}
				if (victimIsP1) { mw.refreshP1HandLabel(); mw.refreshP1WarpZoneUI(); }
				else            { mw.refreshP2HandCountLabel(); }
			}

			@Override public void forceOpponentRandomHandToBottomOfDeck(int count) {
				boolean victimIsP1 = !isP1;
				List<CardData> hand = victimIsP1 ? mw.gameState.getP1Hand() : mw.gameState.getP2Hand();
				for (int idx : randomPicks(count, hand.size(), "a random card to put on the bottom of their deck")) {
					CardData d = victimIsP1 ? mw.gameState.removeFromHand(idx) : hand.remove(idx);
					if (d == null) continue;
					(victimIsP1 ? mw.gameState.getP1MainDeck() : mw.gameState.getP2MainDeck()).addLast(d);
					logEntry("[" + (victimIsP1 ? "P1" : "P2") + "] Randomly placed " + d.name() + " at bottom of deck");
				}
				if (victimIsP1) { mw.refreshP1HandLabel();      mw.refreshP1DeckLabel(); }
				else            { mw.refreshP2HandCountLabel(); mw.refreshP2DeckLabel(); }
			}

			@Override public void selectFromOpponentHandAndRfp(int count) {
				if (isP1) {
					mw.showHandRfpSelectionDialog(mw.gameState.getP2Hand(), count, false);
				} else {
					// AI picks highest-cost cards from P1's hand
					int actual = Math.min(count, mw.gameState.getP1Hand().size());
					for (int i = 0; i < actual; i++) {
						List<CardData> hand = mw.gameState.getP1Hand();
						if (hand.isEmpty()) break;
						int best = 0;
						for (int j = 1; j < hand.size(); j++)
							if (hand.get(j).cost() > hand.get(best).cost()) best = j;
						CardData d = mw.gameState.removeFromHand(best);
						if (d != null) { mw.gameState.addToPermanentRfp(d); logEntry("[P2 AI selects from P1 hand] " + d.name() + " removed from game"); }
					}
					mw.refreshP1HandLabel();
					mw.refreshP1WarpZoneUI();
				}
			}

			@Override public void selectFromOpponentHandAndDiscard(int count, Predicate<CardData> eligible, String eligibleDesc) {
				List<CardData> hand = isP1 ? mw.gameState.getP2Hand() : mw.gameState.getP1Hand();
				List<CardData> choices = new ArrayList<>();
				for (CardData c : hand) if (eligible == null || eligible.test(c)) choices.add(c);
				if (choices.isEmpty()) {
					logEntry("Opponent's hand holds no " + eligibleDesc + " — nothing discarded.");
					return;
				}
				List<CardData> picked;
				if (isP1) {
					picked = mw.showHandSelectionDialog(choices, count, "discard", "Discard");
				} else {
					// AI picks the highest-cost qualifying cards from P1's hand.
					choices.sort((x, y) -> y.cost() - x.cost());
					picked = new ArrayList<>(choices.subList(0, Math.min(count, choices.size())));
				}
				for (CardData d : picked) {
					int idx = indexByIdentity(hand, d);
					if (idx < 0) continue;
					// playerBreakFromHand takes whose hand, which is the opponent's — not the user's.
					if (mw.playerBreakFromHand(!isP1, idx) == null) continue;
					logEntry("[Opponent] Discards " + d.name() + " (selected from revealed hand)");
					mw.turn(!isP1).discardedByEffectThisTurn = true;
					mw.turn(isP1).causedOpponentDiscardThisTurn = true;
				}
				if (isP1) { mw.refreshP2HandCountLabel(); mw.refreshP2BreakLabel(); }
				else      { mw.refreshP1HandLabel();      mw.refreshP1BreakLabel(); }
			}

			@Override public void opponentRevealsSelectOneDiscard(int revealCount) {
				// Read here rather than when the ability went on the Stack: a response can have put
				// cards into their hand in the meantime, and it is this hand that gets revealed.
				List<CardData> oppHand = isP1 ? mw.gameState.getP2Hand() : mw.gameState.getP1Hand();
				if (oppHand.isEmpty()) {
					logEntry("Opponent's hand is empty — nothing revealed, nothing discarded.");
					return;
				}
				// Hand indices, not cards: they survive the trip between clients unchanged, both
				// sides holding the same hand in the same order.
				List<Integer> revealed = mw.revealHandCards(!isP1, revealCount);
				if (revealed.isEmpty()) return;
				StringBuilder shown = new StringBuilder();
				for (int i : revealed) {
					if (i < 0 || i >= oppHand.size()) continue;
					if (shown.length() > 0) shown.append(", ");
					shown.append(oppHand.get(i).name());
				}
				logEntry("[Opponent] Reveals " + revealed.size() + " card(s) from hand: " + shown);

				int chosen = mw.selectRevealedHandCard(isP1, revealed);
				if (chosen < 0) return;
				CardData d = mw.playerBreakFromHand(!isP1, chosen);
				if (d == null) return;
				logEntry("[Opponent] Discards " + d.name() + " (selected from the revealed cards)");
				mw.turn(!isP1).discardedByEffectThisTurn = true;
				mw.turn(isP1).causedOpponentDiscardThisTurn = true;
				if (isP1) { mw.refreshP2HandCountLabel(); mw.refreshP2BreakLabel(); }
				else      { mw.refreshP1HandLabel();      mw.refreshP1BreakLabel(); }
			}

			@Override public void selectFromOpponentHandRfpUntilEndOfOpponentTurn(int count) {
				List<CardData> hand = isP1 ? mw.gameState.getP2Hand() : mw.gameState.getP1Hand();
				if (hand.isEmpty()) { logEntry("Opponent's hand is empty."); return; }
				List<CardData> picked;
				if (isP1) {
					picked = mw.showHandSelectionDialog(new ArrayList<>(hand), count,
							"remove from the game", "Remove From Game");
				} else {
					List<CardData> byCost = new ArrayList<>(hand);
					byCost.sort((x, y) -> y.cost() - x.cost());
					picked = new ArrayList<>(byCost.subList(0, Math.min(count, byCost.size())));
				}
				List<CardData> removed = new ArrayList<>();
				for (CardData d : picked) {
					int idx = indexByIdentity(hand, d);
					if (idx < 0) continue;
					hand.remove(idx);
					mw.gameState.addToPermanentRfp(d);
					removed.add(d);
					logEntry("[Opponent] " + d.name() + " removed from game until the end of their turn");
				}
				if (isP1) { mw.refreshP2HandCountLabel(); mw.refreshP2WarpZoneUI(); }
				else      { mw.refreshP1HandLabel();      mw.refreshP1WarpZoneUI(); }
				if (removed.isEmpty()) return;
				addEndOfOpponentTurnEffect(ctx -> {
					for (CardData d : removed) {
						if (!mw.gameState.removeFromPermanentRfp(d)) continue;
						Boolean ownerIsP1 = mw.gameState.getIdentity().get(d);
						if (ownerIsP1 == null) continue;
						(ownerIsP1 ? mw.gameState.getP1Hand() : mw.gameState.getP2Hand()).add(d);
						ctx.logEntry(d.name() + " returns to its owner's hand");
					}
					mw.refreshP1HandLabel();
					mw.refreshP2HandCountLabel();
					mw.refreshP1WarpZoneUI();
					mw.refreshP2WarpZoneUI();
				});
			}

			/**
			 * The whole hand moves, so there is no selection to put to either seat and nothing to
			 * synchronise -- both clients run this off state they already agree on.
			 *
			 * <p>The returning effect closes over the cards it removed rather than re-reading the
			 * zone, so a card the opponent gets back some other way in the meantime is not returned
			 * twice: {@code removeFromPermanentRfp} reports whether it was still there, and one
			 * that was not is skipped.
			 */
			@Override public void opponentRemovesHandFaceDownUntilEndOfTurn() {
				boolean victimIsP1 = !isP1;
				List<CardData> hand = victimIsP1 ? mw.gameState.getP1Hand() : mw.gameState.getP2Hand();
				if (hand.isEmpty()) { logEntry("Opponent's hand is empty — nothing to remove."); return; }

				List<CardData> removed = new ArrayList<>(hand);
				hand.clear();
				for (CardData d : removed) mw.gameState.addToPermanentRfpFaceDown(d);
				logEntry("[" + (victimIsP1 ? "P1" : "P2") + "] removes their hand ("
						+ removed.size() + " card" + (removed.size() != 1 ? "s" : "")
						+ ") from the game face down until the end of the turn");

				if (victimIsP1) { mw.refreshP1HandLabel();      mw.refreshP1WarpZoneUI(); }
				else            { mw.refreshP2HandCountLabel(); mw.refreshP2WarpZoneUI(); }

				addEndOfTurnEffect(ctx -> {
					for (CardData d : removed) {
						if (!mw.gameState.removeFromPermanentRfp(d)) continue;
						Boolean ownerIsP1 = mw.gameState.getIdentity().get(d);
						if (ownerIsP1 == null) continue;
						(ownerIsP1 ? mw.gameState.getP1Hand() : mw.gameState.getP2Hand()).add(d);
					}
					ctx.logEntry("[" + (victimIsP1 ? "P1" : "P2")
							+ "] takes their removed hand back at the end of the turn");
					mw.refreshP1HandLabel();
					mw.refreshP2HandCountLabel();
					mw.refreshP1WarpZoneUI();
					mw.refreshP2WarpZoneUI();
				});
			}

			@Override public void revealHandOptPickDiscardOpponentDraws() {
				List<CardData> hand = isP1 ? mw.gameState.getP2Hand() : mw.gameState.getP1Hand();
				if (hand.isEmpty()) { logEntry("Opponent's hand is empty."); return; }
				CardData picked;
				if (isP1) {
					picked = mw.showRevealHandOptPickDialog(hand);
				} else {
					int best = 0;
					for (int j = 1; j < hand.size(); j++)
						if (hand.get(j).cost() > hand.get(best).cost()) best = j;
					picked = hand.get(best);
				}
				if (picked == null) return;
				int idx = indexByIdentity(hand, picked);
				if (idx < 0 || mw.playerBreakFromHand(!isP1, idx) == null) return;
				logEntry("[Opponent] Discards " + picked.name() + " (selected from revealed hand)");
				mw.turn(!isP1).discardedByEffectThisTurn = true;
				mw.turn(isP1).causedOpponentDiscardThisTurn = true;
				if (isP1) { mw.refreshP2HandCountLabel(); mw.refreshP2BreakLabel(); }
				else      { mw.refreshP1HandLabel();      mw.refreshP1BreakLabel(); }
				drawCardsForOpponent(1);
			}

			/** Index of {@code card} in {@code list} by identity, or -1. Hands can hold duplicates. */
			private int indexByIdentity(List<CardData> list, CardData card) {
				for (int i = 0; i < list.size(); i++) if (list.get(i) == card) return i;
				return -1;
			}

			@Override public void revealHandOptPickRfpOpponentDraws() {
				if (isP1) {
					List<CardData> hand = mw.gameState.getP2Hand();
					if (hand.isEmpty()) { logEntry("Opponent's hand is empty."); return; }
					CardData picked = mw.showRevealHandOptPickDialog(hand);
					if (picked != null) {
						hand.remove(picked);
						mw.gameState.addToPermanentRfp(picked);
						logEntry("[P2] " + picked.name() + " removed from game by P1");
						mw.refreshP2HandCountLabel();
						mw.refreshP2WarpZoneUI();
						drawCardsForOpponent(1);
					}
				} else {
					List<CardData> hand = mw.gameState.getP1Hand();
					if (hand.isEmpty()) { logEntry("P1 hand is empty."); return; }
					int best = 0;
					for (int j = 1; j < hand.size(); j++)
						if (hand.get(j).cost() > hand.get(best).cost()) best = j;
					CardData d = mw.gameState.removeFromHand(best);
					if (d != null) {
						mw.gameState.addToPermanentRfp(d);
						logEntry("[P2 AI] " + d.name() + " selected from P1 hand — removed from game");
						mw.refreshP1HandLabel();
						mw.refreshP1WarpZoneUI();
						drawCardsForOpponent(1);
					}
				}
			}

			@Override public void forceOpponentHandRfp(int count) {
				if (isP1) {
					List<CardData> hand = mw.gameState.getP2Hand();
					int actual = Math.min(count, hand.size());
					for (int i = 0; i < actual; i++) {
						if (hand.isEmpty()) break;
						int idx = MainWindow.pickWorstHandCard0(hand);
						CardData d = hand.remove(idx);
						mw.gameState.addToPermanentRfp(d);
						logEntry("[P2] Removes from game: " + d.name());
					}
					mw.refreshP2HandCountLabel();
				} else {
					mw.showHandRfpSelectionDialog(mw.gameState.getP1Hand(), count, true);
				}
			}

			@Override public void removeNamedCardFromGame(String cardName) {
				// P1 forwards
				for (int i = 0; i < mw.p1ForwardCards.size(); i++) {
					if (mw.p1ForwardCards.get(i).name().equalsIgnoreCase(cardName)) { removeP1ForwardFromGame(i); return; }
				}
				// P1 backups
				for (int i = 0; i < mw.p1BackupCards.length; i++) {
					if (mw.p1BackupCards[i] != null && mw.p1BackupCards[i].name().equalsIgnoreCase(cardName)) {
						logEntry(cardName + " → Removed From Game");
						mw.gameState.addToPermanentRfp(mw.p1BackupCards[i]);
						mw.p1BackupCards[i] = null; mw.p1BackupStates[i] = CardState.ACTIVE;
						mw.refreshP1BackupSlot(i); mw.refreshP1WarpZoneUI(); return;
					}
				}
				// P1 monsters
				for (int i = 0; i < mw.p1MonsterCards.size(); i++) {
					if (mw.p1MonsterCards.get(i).name().equalsIgnoreCase(cardName)) {
						removeTargetFromGame(new ForwardTarget(true, i, ForwardTarget.CardZone.MONSTER)); return;
					}
				}
				// P2 forwards
				for (int i = 0; i < mw.p2ForwardCards.size(); i++) {
					if (mw.p2ForwardCards.get(i).name().equalsIgnoreCase(cardName)) { removeP2ForwardFromGame(i); return; }
				}
				// P2 backups
				for (int i = 0; i < mw.p2BackupCards.length; i++) {
					if (mw.p2BackupCards[i] != null && mw.p2BackupCards[i].name().equalsIgnoreCase(cardName)) {
						logEntry("[P2] " + cardName + " → Removed From Game");
						mw.gameState.addToPermanentRfp(mw.p2BackupCards[i]);
						mw.p2BackupCards[i] = null; mw.p2BackupStates[i] = CardState.ACTIVE;
						mw.refreshP2BackupSlot(i); return;
					}
				}
				// P2 monsters
				for (int i = 0; i < mw.p2MonsterCards.size(); i++) {
					if (mw.p2MonsterCards.get(i).name().equalsIgnoreCase(cardName)) {
						removeTargetFromGame(new ForwardTarget(false, i, ForwardTarget.CardZone.MONSTER)); return;
					}
				}
				logEntry("[Warning] removeNamedCardFromGame: \"" + cardName + "\" not found on field");
			}

			@Override public void removeAllOpponentBzFromGame() {
				List<CardData> bz = isP1 ? mw.gameState.getP2BreakZone() : mw.gameState.getP1BreakZone();
				while (!bz.isEmpty()) {
					CardData card = bz.remove(bz.size() - 1);
					logEntry((isP1 ? "[P2] " : "") + card.name() + " (opponent BZ) → Removed From Game");
					mw.gameState.addToPermanentRfp(card);
				}
				if (isP1) { mw.refreshP2BreakLabel(); mw.refreshP2WarpZoneUI(); }
				else      { mw.refreshP1BreakLabel(); mw.refreshP1WarpZoneUI(); }
			}

			@Override public void playNamedFromRfpOntoField(String cardName) {
				for (CardData card : mw.gameState.getP1PermanentRfp()) {
					if (card.name().equalsIgnoreCase(cardName)) {
						mw.gameState.removeFromPermanentRfp(card);
						logEntry(card.name() + " returns from RFP → P1 field");
						mw.placeFromRfgWithAnim(card, true, () -> mw.placeCardInForwardZone(card));
						return;
					}
				}
				for (CardData card : mw.gameState.getP2PermanentRfp()) {
					if (card.name().equalsIgnoreCase(cardName)) {
						mw.gameState.removeFromPermanentRfp(card);
						logEntry(card.name() + " returns from RFP → P2 field");
						mw.placeFromRfgWithAnim(card, false, () -> mw.placeP2CardInForwardZone(card));
						return;
					}
				}
				logEntry("[Warning] playNamedFromRfpOntoField: \"" + cardName + "\" not found in RFP");
			}

			@Override public void playNamedFromHoldingZoneOntoField(String cardName) {
				for (CardData card : mw.gameState.getP1PermanentRfp())
					if (card.name().equalsIgnoreCase(cardName)) { playNamedFromRfpOntoField(cardName); return; }
				for (CardData card : mw.gameState.getP2PermanentRfp())
					if (card.name().equalsIgnoreCase(cardName)) { playNamedFromRfpOntoField(cardName); return; }
				List<CardData> bz = isP1 ? mw.gameState.getP1BreakZone() : mw.gameState.getP2BreakZone();
				if (bz.stream().anyMatch(c -> c.name().equalsIgnoreCase(cardName))) {
					playAllByNameFromOwnBreakZoneDull(cardName, false);
					return;
				}
				logEntry("[Warning] playNamedFromHoldingZoneOntoField: \"" + cardName
						+ "\" is in neither the RFG zone nor the Break Zone");
			}

			@Override public void playLastRemovedFromRfpOntoField(boolean dull) {
				List<CardData> rfp = isP1 ? mw.gameState.getP1PermanentRfp() : mw.gameState.getP2PermanentRfp();
				if (rfp.isEmpty()) {
					logEntry("[Warning] playLastRemovedFromRfpOntoField: RFP zone is empty");
					return;
				}
				CardData card = rfp.get(rfp.size() - 1);
				if (isP1) {
					mw.gameState.removeFromPermanentRfp(card);
					logEntry(card.name() + " returns from RFP → P1 field" + (dull ? " (dull)" : ""));
					mw.placeFromRfgWithAnim(card, true, () -> {
						mw.placeCardInForwardZone(card);
						if (dull) {
							int newIdx = mw.p1ForwardCards.size() - 1;
							mw.p1ForwardStates.set(newIdx, CardState.DULL);
							mw.refreshP1ForwardSlot(newIdx);
						}
					});
				} else {
					mw.gameState.removeFromPermanentRfp(card);
					logEntry(card.name() + " returns from RFP → P2 field" + (dull ? " (dull)" : ""));
					mw.placeFromRfgWithAnim(card, false, () -> {
						mw.placeP2CardInForwardZone(card);
						if (dull) {
							int newIdx = mw.p2ForwardCards.size() - 1;
							mw.p2ForwardStates.set(newIdx, CardState.DULL);
							mw.refreshP2ForwardSlot(newIdx);
						}
					});
				}
			}

			@Override public void returnNamedCardToOwnersHand(String cardName) {
				for (int i = 0; i < mw.p1ForwardCards.size(); i++) {
					if (mw.p1ForwardCards.get(i).name().equalsIgnoreCase(cardName)) { returnP1ForwardToHand(i); return; }
				}
				for (int i = 0; i < mw.p1BackupCards.length; i++) {
					if (mw.p1BackupCards[i] != null && mw.p1BackupCards[i].name().equalsIgnoreCase(cardName)) { returnP1BackupToHand(i); return; }
				}
				for (int i = 0; i < mw.p1MonsterCards.size(); i++) {
					if (mw.p1MonsterCards.get(i).name().equalsIgnoreCase(cardName)) { returnP1MonsterToHand(i); return; }
				}
				for (int i = 0; i < mw.p2ForwardCards.size(); i++) {
					if (mw.p2ForwardCards.get(i).name().equalsIgnoreCase(cardName)) { returnP2ForwardToHand(i); return; }
				}
				for (int i = 0; i < mw.p2BackupCards.length; i++) {
					if (mw.p2BackupCards[i] != null && mw.p2BackupCards[i].name().equalsIgnoreCase(cardName)) { returnP2BackupToHand(i); return; }
				}
				for (int i = 0; i < mw.p2MonsterCards.size(); i++) {
					if (mw.p2MonsterCards.get(i).name().equalsIgnoreCase(cardName)) { returnP2MonsterToHand(i); return; }
				}
				logEntry("[Warning] returnNamedCardToOwnersHand: \"" + cardName + "\" not found on field");
			}

			@Override public void grantAttackOnceMore(String cardName) {
				List<CardData> fwds    = isP1 ? mw.p1ForwardCards : mw.p2ForwardCards;
				Set<CardData>  blocked = isP1 ? mw.p1CannotAttack : mw.p2CannotAttack;
				for (int i = 0; i < fwds.size(); i++) {
					if (!fwds.get(i).name().equalsIgnoreCase(cardName)) continue;
					blocked.remove(fwds.get(i));
					mw.grantExtraAttack(isP1 ? mw.effectiveP1Forward(i) : mw.effectiveP2Forward(i));
					logEntry(cardName + " may attack once more this turn");
					if (isP1) mw.refreshP1ForwardSlot(i); else mw.refreshP2ForwardSlot(i);
					return;
				}
				List<CardData> mons = isP1 ? mw.p1MonsterCards : mw.p2MonsterCards;
				for (CardData m : mons)
					if (m.name().equalsIgnoreCase(cardName)) { mw.grantExtraAttack(m); return; }
				logEntry("[Warning] grantAttackOnceMore: \"" + cardName + "\" not found on "
						+ (isP1 ? "P1" : "P2") + "'s field");
			}

			@Override public void limitOpponentAttackDeclarationsThisTurn(int max) {
				if (isP1) {
					mw.p2Turn.attackDeclarationLimit = max;
				} else {
					mw.p1Turn.attackDeclarationLimit = max;
				}
				logEntry("Effect: Opponent may only declare attack " + max + " time(s) this turn");
			}

			@Override public void setOppFieldEntryRemovedFromGameThisTurn() {
				mw.turn(isP1).oppFieldEntryBecomesRfg = true;
				logEntry("Effect: this turn, Characters entering the field by your opponent's "
						+ "Summons or abilities are removed from the game instead");
			}

			@Override public void setOpponentCannotSearchThisTurn() {
				if (isP1) mw.p2Turn.cannotSearchThisTurn = true; else mw.p1Turn.cannotSearchThisTurn = true;
				logEntry("Effect: Opponent cannot search this turn");
			}

			@Override public void returnNamedCardToYourHand(String cardName) {
				if (mw.currentResolutionIsSummon && mw.currentSummonSource != null
						&& mw.currentSummonSource.name().equalsIgnoreCase(cardName)) {
					mw.pendingSummonReturnToHand = true;
					return;
				}
				for (int i = 0; i < mw.p1ForwardCards.size(); i++) {
					if (mw.p1ForwardCards.get(i).name().equalsIgnoreCase(cardName)) { returnP1ForwardToHand(i); return; }
				}
				for (int i = 0; i < mw.p1BackupCards.length; i++) {
					if (mw.p1BackupCards[i] != null && mw.p1BackupCards[i].name().equalsIgnoreCase(cardName)) { returnP1BackupToHand(i); return; }
				}
				for (int i = 0; i < mw.p1MonsterCards.size(); i++) {
					if (mw.p1MonsterCards.get(i).name().equalsIgnoreCase(cardName)) { returnP1MonsterToHand(i); return; }
				}
				// Fallback: search P1's Break Zone (for break-zone-origin abilities)
				List<CardData> bz = mw.gameState.getP1BreakZone();
				for (int i = bz.size() - 1; i >= 0; i--) {
					if (bz.get(i).name().equalsIgnoreCase(cardName)) {
						CardData c = bz.remove(i);
						mw.gameState.getP1Hand().add(c);
						logEntry(cardName + " Break Zone → P1 Hand");
						mw.refreshP1BreakLabel();
						mw.refreshP1HandLabel();
						mw.notifyCardsAddedToHandFromBreakZone(true);
						return;
					}
				}
				logEntry("[Warning] returnNamedCardToYourHand: \"" + cardName + "\" not found on field or Break Zone");
			}

			@Override public void removeFromBattle(String cardName) {
				for (int i = 0; i < mw.p1ForwardCards.size(); i++) {
					if (mw.p1ForwardCards.get(i).name().equalsIgnoreCase(cardName)) {
						mw.escapedFromBattle.add(mw.p1ForwardCards.get(i));
						return;
					}
				}
				for (int i = 0; i < mw.p2ForwardCards.size(); i++) {
					if (mw.p2ForwardCards.get(i).name().equalsIgnoreCase(cardName)) {
						mw.escapedFromBattle.add(mw.p2ForwardCards.get(i));
						return;
					}
				}
				logEntry("[Warning] removeFromBattle: \"" + cardName + "\" not found on field");
			}

			@Override public void takeExtraTurnThenLose() {
				logEntry("Effect: Take 1 more turn — you will lose at the end of that turn");
				mw.p1ExtraTurnThenLose = true;
			}

			@Override public boolean isNamedCardOnField(String name) {
				for (CardData c : mw.p1ForwardCards) if (c.name().equalsIgnoreCase(name)) return true;
				for (CardData c : mw.p2ForwardCards) if (c.name().equalsIgnoreCase(name)) return true;
				for (CardData c : mw.p1BackupCards) if (c != null && c.name().equalsIgnoreCase(name)) return true;
				for (CardData c : mw.p2BackupCards) if (c != null && c.name().equalsIgnoreCase(name)) return true;
				return false;
			}

			@Override public void causeOpponentToLose() {
				mw.triggerGameOver(isP1 ? "Opponent Loses — You Win!" : "Opponent Loses — You Lose!");
			}

			@Override public void scheduleAtEndOfControllerNextTurn(Consumer<GameContext> effect) {
				// Adds a wrapper to endOfTurnEffects. When the current END phase fires that wrapper,
				// it inserts the real effect into scheduledForP1/P2EndTurn AFTER that list was already
				// cleared, so the real effect survives until the controller's next END phase.
				mw.endOfTurnEffects.add(outerCtx ->
					(isP1 ? mw.scheduledForP1EndTurn : mw.scheduledForP2EndTurn).add(effect));
			}

			@Override public void drawCards(int count) {
				mw.drawCardsForPlayer(isP1, count);
			}

			@Override public void selfDiscard(int count) {
				if (isP1) {
					mw.showForcedDiscardDialog(count, false);
				} else {
					List<CardData> hand = mw.gameState.getP2Hand();
					int actual = Math.min(count, hand.size());
					for (int i = 0; i < actual; i++) {
						int idx = MainWindow.pickWorstHandCard0(hand);
						CardData d = mw.playerBreakFromHand(false,idx);
						if (d != null) { logEntry("[P2] Discards " + d.name()); mw.p2Turn.discardedByEffectThisTurn = true; mw.lastDiscardedCardName = d.name(); mw.lastDiscardedCard = d; }
					}
					mw.refreshP2HandCountLabel();
					mw.refreshP2BreakLabel();
				}
			}

			@Override public void selfDiscardByType(String cardType) {
				if (!discardOneFromHandByType(cardType)) markEffectFizzled();
			}

			@Override public void mayDiscardCardOfTypeFromHandOrElse(String cardType,
					java.util.function.Consumer<GameContext> ifDiscarded,
					java.util.function.Consumer<GameContext> ifNot) {
				// No markEffectFizzled() here, unlike selfDiscardByType: declining is not a dead
				// end for these cards, it is the branch ifNot spells out.
				if (offerDiscardOfType(cardType)) ifDiscarded.accept(this);
				else                              ifNot.accept(this);
			}

			@Override public void mayDiscardCardOfTypeFromHand(String cardType) {
				if (!offerDiscardOfType(cardType)) markEffectFizzled();
			}

			/**
			 * Puts one optional discard of a card matching {@code cardType} to the ability user and
			 * reports whether one happened.
			 *
			 * <p>The offer comes first and the picker second, which is the shape the picker is
			 * built for: {@code HandPickDialog}'s chooser has no Pass button and cannot be
			 * dismissed, precisely because the player is taken to have committed by accepting a
			 * "you may?" prompt. Reaching it without that prompt is what left 1-190S Bahamut Fury's
			 * "You may discard 1 card from your hand" with no way to decline — P1 was shown a modal
			 * that only closes by discarding, so the "If not, deal it 5000 damage" branch could
			 * never be taken.
			 *
			 * <p>Eligibility is checked before the offer so a player holding nothing is not asked a
			 * question with one answer.
			 *
			 * <p>P2's AI takes every offer it can afford, which is {@code discardOneFromHandByType}'s
			 * existing behaviour and is left alone here: whether a card is worth the upgrade
			 * (7000 damage rather than 5000) is a valuation question, not part of making the offer
			 * declinable.
			 */
			private boolean offerDiscardOfType(String cardType) {
				if (isP1) {
					List<CardData> hand = mw.gameState.getP1Hand();
					boolean anyEligible = hand.stream().anyMatch(c -> matchesDiscardType(c, cardType));
					if (!anyEligible) {
						logEntry("[Effect] No " + cardType + " in hand — optional discard skipped");
						return false;
					}
					String src = mw.currentAbilitySource != null ? mw.currentAbilitySource.name() : "Ability";
					int choice = mw.showEffectOptionDialog(
							src + " — Discard 1 " + cardType + " from hand?",
							"You May Discard", new Object[]{"Discard", "Pass"});
					if (choice != 0) {
						logEntry("[Effect] Declined to discard a " + cardType);
						return false;
					}
				}
				return discardOneFromHandByType(cardType);
			}

			/**
			 * Offers the ability user one optional discard of a card matching {@code cardType}
			 * ({@code "card"} for any), and reports whether one actually went to the Break Zone.
			 * Shared by the two methods above so they cannot drift in what counts as a discard.
			 */
			private boolean discardOneFromHandByType(String cardType) {
				if (isP1) return mw.showDiscardByTypeDialog(cardType);
				List<CardData> hand = mw.gameState.getP2Hand();
				List<Integer> eligible = new ArrayList<>();
				for (int i = 0; i < hand.size(); i++) {
					if (matchesDiscardType(hand.get(i), cardType)) eligible.add(i);
				}
				if (eligible.isEmpty()) return false;
				List<CardData> eligibleCards = eligible.stream().map(hand::get).collect(Collectors.toList());
				int relIdx = MainWindow.pickWorstHandCard0(eligibleCards);
				int idx = eligible.get(relIdx);
				CardData d = mw.playerBreakFromHand(false, idx);
				if (d != null) {
					logEntry("[P2] Discards " + d.name());
					mw.p2Turn.discardedByEffectThisTurn = true;
					if (d.isForward()) mw.lastDiscardedForwardPower = d.power();
				}
				mw.refreshP2HandCountLabel();
				mw.refreshP2BreakLabel();
				return d != null;
			}

			@Override public void selfDiscardByJob(String jobName) {
				if (isP1) {
					boolean discarded = mw.showDiscardByJobDialog(jobName);
					if (!discarded) markEffectFizzled();
				} else {
					List<CardData> hand = mw.gameState.getP2Hand();
					List<Integer> eligible = new ArrayList<>();
					for (int i = 0; i < hand.size(); i++) {
						if (CardFilters.meetsJobFilter(hand.get(i), jobName)) eligible.add(i);
					}
					if (eligible.isEmpty()) { markEffectFizzled(); return; }
					List<CardData> eligibleCards = eligible.stream().map(hand::get).collect(Collectors.toList());
					int relIdx = MainWindow.pickWorstHandCard0(eligibleCards);
					int idx = eligible.get(relIdx);
					CardData d = mw.playerBreakFromHand(false, idx);
					if (d != null) {
						logEntry("[P2] Discards " + d.name());
						mw.p2Turn.discardedByEffectThisTurn = true;
						if (d.isForward()) mw.lastDiscardedForwardPower = d.power();
					}
					mw.refreshP2HandCountLabel();
					mw.refreshP2BreakLabel();
				}
			}

			@Override public void mayDiscardCardOfJobFromHand(String jobName) {
				List<CardData> hand = isP1 ? mw.gameState.getP1Hand() : mw.gameState.getP2Hand();
				boolean anyEligible = hand.stream().anyMatch(c -> CardFilters.meetsJobFilter(c, jobName));
				if (!anyEligible) {
					logEntry("[Effect] No Job " + jobName + " in hand — optional discard skipped");
					markEffectFizzled();
					return;
				}
				if (isP1) {
					String src = mw.currentAbilitySource != null ? mw.currentAbilitySource.name() : "Ability";
					int choice = mw.showEffectOptionDialog(
							src + " — Discard 1 Job " + jobName + " from hand?",
							"You May Discard", new Object[]{"Discard", "Pass"});
					if (choice != 0) {
						logEntry("[Effect] Declined to discard a Job " + jobName);
						markEffectFizzled();
						return;
					}
					// The pick itself is no longer optional — the offer has been accepted.
					if (!mw.showDiscardByJobDialog(jobName)) markEffectFizzled();
					return;
				}
				selfDiscardByJob(jobName);
			}

			@Override public void selfDiscardByElement(String element) {
				if (isP1) {
					boolean discarded = mw.showDiscardByElementDialog(element);
					if (!discarded) markEffectFizzled();
				} else {
					List<CardData> hand = mw.gameState.getP2Hand();
					List<Integer> eligible = new ArrayList<>();
					for (int i = 0; i < hand.size(); i++) {
						if (hand.get(i).containsElement(element)) eligible.add(i);
					}
					if (eligible.isEmpty()) { markEffectFizzled(); return; }
					List<CardData> eligibleCards = eligible.stream().map(hand::get).collect(Collectors.toList());
					int relIdx = MainWindow.pickWorstHandCard0(eligibleCards);
					int idx = eligible.get(relIdx);
					CardData d = mw.playerBreakFromHand(false, idx);
					if (d != null) {
						logEntry("[P2] Discards " + d.name());
						mw.p2Turn.discardedByEffectThisTurn = true;
						if (d.isForward()) mw.lastDiscardedForwardPower = d.power();
					}
					mw.refreshP2HandCountLabel();
					mw.refreshP2BreakLabel();
				}
			}

			@Override public void mayRevealCardByElementFromHand(String element) {
				List<CardData> hand = isP1 ? mw.gameState.getP1Hand() : mw.gameState.getP2Hand();
				List<Integer> eligible = new ArrayList<>();
				for (int i = 0; i < hand.size(); i++) {
					if (hand.get(i).containsElement(element)) eligible.add(i);
				}
				if (eligible.isEmpty()) { markEffectFizzled(); return; }
				if (isP1) {
					boolean revealed = mw.showRevealByElementFromHandDialog(element);
					if (!revealed) markEffectFizzled();
				} else {
					logEntry("[P2] Reveals " + hand.get(eligible.get(0)).name() + " (a " + element + " card from hand)");
				}
			}

			@Override public void mayPayToReplayAbility(String element, java.util.function.Consumer<GameContext> replayAction) {
				if (!isP1) { logEntry("[P2 AI] Passes on ability replay (pay 《" + element + "》)"); return; }
				String label = "Pay 《" + element + "》 to use this ability again?";
				String src   = mw.currentAbilitySource != null ? mw.currentAbilitySource.name() : "Ability";
				int choice = mw.showEffectOptionDialog(src + " — " + label, "Replay Ability", new Object[]{"Pay", "Pass"});
				if (choice != 0) { logEntry("Replay: declined to pay 《" + element + "》"); return; }
				mw.autoAbilityTriggers.showAutoAbilityPaymentDialog(src + " (replay)", 1, 1, isP1, 0, paid -> {
					if (paid >= 1) { logEntry("Replay: paid 《" + element + "》 — using ability again"); replayAction.accept(this); }
				}, null);
			}

			@Override public void mayPayElementCpToEffect(String element, java.util.function.Consumer<GameContext> onPay) {
				if (!isP1) {
					logEntry("[P2 AI] Pays 《" + element + "》 for optional effect");
					mw.autoAbilityTriggers.showAutoAbilityPaymentDialog("", 1, 1, isP1, 0, paid -> {
						if (paid >= 1) { logEntry("[P2 AI] Paid 《" + element + "》 — applying effect"); onPay.accept(this); }
					}, null);
					return;
				}
				String src    = mw.currentAbilitySource != null ? mw.currentAbilitySource.name() : "Ability";
				String label  = "Pay 《" + element + "》?";
				int choice = mw.showEffectOptionDialog(src + " — " + label, "Optional Cost", new Object[]{"Pay", "Pass"});
				if (choice != 0) { logEntry("Optional pay: declined 《" + element + "》"); return; }
				mw.autoAbilityTriggers.showAutoAbilityPaymentDialog(src, 1, 1, isP1, 0, paid -> {
					if (paid >= 1) { logEntry("Optional pay: paid 《" + element + "》 — applying effect"); onPay.accept(this); }
				}, null);
			}

			@Override public void mayPayCostOrElse(int cp, String element, int crystals, Runnable onNotPaid) {
				String src  = mw.currentAbilitySource != null ? mw.currentAbilitySource.name() : "Ability";
				String cost = crystals > 0 ? "《C》" + (crystals > 1 ? " ×" + crystals : "")
						: element != null ? "《" + element + "》" : "《" + cp + "》";

				if (!mw.canPayOptionalCost(isP1, cp, element, crystals)) {
					logEntry((isP1 ? "" : "[P2] ") + src + " — cannot pay " + cost + "; effect applies");
					onNotPaid.run();
					return;
				}

				if (!isP1) {
					// Every printed consequence (self-break, self-damage, discard) costs the AI more
					// than the cost itself, so it pays whenever it can.
					if (crystals > 0) {
						mw.playerSpendCrystals(false, crystals);
						mw.refreshCrystalDisplays();
						logEntry("[P2] " + src + " — pays " + cost);
						return;
					}
					int need = element != null ? 1 : cp;
					int paid = mw.autoAbilityTriggers.aiPayCp(false, need);
					if (paid >= need) { logEntry("[P2] " + src + " — pays " + cost); return; }
					logEntry("[P2] " + src + " — did not pay " + cost + "; effect applies");
					onNotPaid.run();
					return;
				}

				int choice = mw.showEffectOptionDialog(src + " — pay " + cost + "?",
						"Optional Cost", new Object[]{"Pay", "Decline"});
				if (choice != 0) {
					logEntry(src + " — declined to pay " + cost + "; effect applies");
					onNotPaid.run();
					return;
				}
				if (crystals > 0) {
					mw.playerSpendCrystals(true, crystals);
					mw.refreshCrystalDisplays();
					logEntry(src + " — paid " + cost);
					return;
				}
				int need = element != null ? 1 : cp;
				boolean[] paidInFull = { false };
				mw.autoAbilityTriggers.showAutoAbilityPaymentDialog(src, need, need, true, 0,
						paid -> paidInFull[0] = paid >= need, null);
				if (paidInFull[0]) {
					logEntry(src + " — paid " + cost);
				} else {
					logEntry(src + " — did not pay " + cost + "; effect applies");
					onNotPaid.run();
				}
			}

			@Override public void mayPayCostToEffect(int cp, String element, int crystals,
					java.util.function.Consumer<GameContext> onPay) {
				String src  = mw.currentAbilitySource != null ? mw.currentAbilitySource.name() : "Ability";
				String cost = crystals > 0 ? "《C》" + (crystals > 1 ? " ×" + crystals : "")
						: element != null ? "《" + element + "》" : "《" + cp + "》";

				if (!mw.canPayOptionalCost(isP1, cp, element, crystals)) {
					logEntry((isP1 ? "" : "[P2] ") + src + " — cannot pay " + cost + "; effect skipped");
					return;
				}

				if (!isP1) {
					// The payment buys a strictly positive effect, so the AI takes it when it can.
					if (crystals > 0) {
						mw.playerSpendCrystals(false, crystals);
						mw.refreshCrystalDisplays();
						logEntry("[P2] " + src + " — pays " + cost);
						onPay.accept(this);
						return;
					}
					int need = element != null ? 1 : cp;
					int paid = mw.autoAbilityTriggers.aiPayCp(false, need);
					if (paid >= need) { logEntry("[P2] " + src + " — pays " + cost); onPay.accept(this); }
					else               logEntry("[P2] " + src + " — did not pay " + cost + "; effect skipped");
					return;
				}

				int choice = mw.showEffectOptionDialog(src + " — pay " + cost + "?",
						"Optional Cost", new Object[]{"Pay", "Decline"});
				if (choice != 0) {
					logEntry(src + " — declined to pay " + cost + "; effect skipped");
					return;
				}
				if (crystals > 0) {
					mw.playerSpendCrystals(true, crystals);
					mw.refreshCrystalDisplays();
					logEntry(src + " — paid " + cost);
					onPay.accept(this);
					return;
				}
				int need = element != null ? 1 : cp;
				boolean[] paidInFull = { false };
				mw.autoAbilityTriggers.showAutoAbilityPaymentDialog(src, need, need, true, 0,
						paid -> paidInFull[0] = paid >= need, null);
				if (paidInFull[0]) { logEntry(src + " — paid " + cost); onPay.accept(this); }
				else                 logEntry(src + " — did not pay " + cost + "; effect skipped");
			}

			@Override public void breakAfterCombatAndDealNoDamage(CardData source) {
				if (source == null) return;
				mw.dealsNoCombatDamageSet.add(source);
				mw.breakAfterCombatSet.add(source);
				logEntry((isP1 ? "" : "[P2] ") + source.name()
						+ " deals no damage this battle and breaks once it ends");
			}

			@Override public void opponentMayPayToPreventAction(int cost, Runnable onNotPaid) {
				String src = mw.currentAbilitySource != null ? mw.currentAbilitySource.name() : "Ability";
				String label = src + " — pay 《" + cost + "》 to prevent its effect";
				int[] paidHolder = {-1};
				mw.autoAbilityTriggers.showAutoAbilityPaymentDialog(label, cost, cost, !isP1, 0,
						paid -> paidHolder[0] = paid, null);
				if (paidHolder[0] < cost) {
					logEntry("Effect: opponent declined to pay 《" + cost + "》 — effect applies");
					onNotPaid.run();
				} else {
					logEntry("Effect: opponent paid 《" + cost + "》 — effect prevented");
				}
			}

			@Override public void mayDullActiveCardToReplayAbility(String cardName, java.util.function.Consumer<GameContext> replayAction) {
				// Find an active card of that name on the ability user's side
				int fwdIdx = -1;
				for (int i = 0; i < mw.p1ForwardCards.size(); i++) {
					if (mw.p1ForwardCards.get(i).name().equalsIgnoreCase(cardName)
							&& mw.p1ForwardStates.get(i) == CardState.ACTIVE) { fwdIdx = i; break; }
				}
				int bkpIdx = -1;
				if (fwdIdx < 0) {
					for (int i = 0; i < mw.p1BackupCards.length; i++) {
						if (mw.p1BackupCards[i] != null && mw.p1BackupCards[i].name().equalsIgnoreCase(cardName)
								&& mw.p1BackupStates[i] == CardState.ACTIVE) { bkpIdx = i; break; }
					}
				}
				if (fwdIdx < 0 && bkpIdx < 0) {
					logEntry("Replay: no active " + cardName + " on field — offer skipped");
					return;
				}
				if (!isP1) { logEntry("[P2 AI] Passes on ability replay (dull " + cardName + ")"); return; }
				String src = mw.currentAbilitySource != null ? mw.currentAbilitySource.name() : "Ability";
				int choice = mw.showEffectOptionDialog(
						src + " — Dull active " + cardName + " to use this ability again?",
						"Replay Ability", new Object[]{"Dull", "Pass"});
				if (choice != 0) { logEntry("Replay: declined to dull " + cardName); return; }
				if (fwdIdx >= 0) {
					dullP1Forward(fwdIdx);
				} else {
					mw.p1BackupStates[bkpIdx] = CardState.DULL;
					mw.refreshP1BackupSlot(bkpIdx);
				}
				logEntry("Replay: dulled " + cardName + " — using ability again");
				replayAction.accept(this);
			}

			@Override public void mayDiscardCardNameToReplayAbility(String cardName, java.util.function.Consumer<GameContext> replayAction) {
				List<CardData> hand = isP1 ? mw.gameState.getP1Hand() : mw.gameState.getP2Hand();
				int handIdx = -1;
				for (int i = 0; i < hand.size(); i++) {
					if (hand.get(i).name().equalsIgnoreCase(cardName)) { handIdx = i; break; }
				}
				if (handIdx < 0) { logEntry("Replay: no " + cardName + " in hand — offer skipped"); return; }
				if (!isP1) { logEntry("[P2 AI] Passes on ability replay (discard " + cardName + ")"); return; }
				String src = mw.currentAbilitySource != null ? mw.currentAbilitySource.name() : "Ability";
				int choice = mw.showEffectOptionDialog(
						src + " — Discard " + cardName + " from hand to use this ability again?",
						"Replay Ability", new Object[]{"Discard", "Pass"});
				if (choice != 0) { logEntry("Replay: declined to discard " + cardName); return; }
				CardData d = mw.playerBreakFromHand(true,handIdx);
				if (d != null) { logEntry("Replay: discarded " + d.name()); mw.p1Turn.discardedByEffectThisTurn = true; }
				mw.refreshP1HandLabel();
				mw.refreshP1BreakLabel();
				logEntry("Replay: using ability again");
				replayAction.accept(this);
			}

			@Override public void mayDiscardCardNameFromHandOrElse(String cardName,
					java.util.function.Consumer<GameContext> ifDiscarded,
					java.util.function.Consumer<GameContext> ifNot) {
				List<CardData> hand = isP1 ? mw.gameState.getP1Hand() : mw.gameState.getP2Hand();
				int handIdx = -1;
				for (int i = 0; i < hand.size(); i++) {
					if (hand.get(i).name().equalsIgnoreCase(cardName)) { handIdx = i; break; }
				}
				if (handIdx < 0) { logEntry("[Effect] No " + cardName + " in hand — optional discard skipped"); ifNot.accept(this); return; }
				if (!isP1) { logEntry("[P2 AI] Passes on optional discard of " + cardName); ifNot.accept(this); return; }
				String src = mw.currentAbilitySource != null ? mw.currentAbilitySource.name() : "Ability";
				int choice = mw.showEffectOptionDialog(
						src + " — Discard " + cardName + " from hand?",
						"You May Discard", new Object[]{"Discard", "Pass"});
				if (choice != 0) { logEntry("[Effect] Declined to discard " + cardName); ifNot.accept(this); return; }
				final int idx = handIdx;
				CardData d = mw.playerBreakFromHand(true,idx);
				if (d != null) { logEntry("[Effect] Discarded " + d.name()); mw.p1Turn.discardedByEffectThisTurn = true; }
				mw.refreshP1HandLabel();
				mw.refreshP1BreakLabel();
				ifDiscarded.accept(this);
			}

			@Override public void mayBreakSourceWhenDoSo(CardData source, java.util.function.Consumer<GameContext> whenDoSo) {
				if (!isP1) { logEntry("[P2 AI] Passes on optional break of " + source.name()); return; }
				String title = (mw.currentAbilitySource != null ? mw.currentAbilitySource.name() : source.name());
				int choice = mw.showEffectOptionDialog(
						title + " — Put " + source.name() + " into the Break Zone?",
						"You May", new Object[]{"Break", "Pass"});
				if (choice != 0) { logEntry("[Effect] Declined to break " + source.name()); return; }
				logEntry("[Effect] " + source.name() + " → Break Zone (by choice)");
				breakSourceCard(source);
				whenDoSo.accept(this);
			}

			@Override public void revealElementCardFromHandDraw(String element, int drawCount) {
				List<CardData> hand = isP1 ? mw.gameState.getP1Hand() : mw.gameState.getP2Hand();
				List<CardData> eligible = hand.stream()
						.filter(c -> c.containsElement(element))
						.collect(Collectors.toList());
				if (eligible.isEmpty()) { logEntry("[Effect] No " + element + " card in hand — fizzle"); return; }
				CardData toReveal;
				if (!isP1) {
					toReveal = eligible.get(0);
				} else if (eligible.size() == 1) {
					toReveal = eligible.get(0);
				} else {
					String src = mw.currentAbilitySource != null ? mw.currentAbilitySource.name() : "Ability";
					Object[] names = eligible.stream().map(CardData::name).toArray();
					int pick = mw.showEffectOptionDialog(src + " — Choose " + element + " card to reveal:", "Reveal", names);
					toReveal = eligible.get(Math.max(0, Math.min(pick, eligible.size() - 1)));
				}
				logEntry("[Effect] Reveals " + toReveal.name() + " from hand");
				drawCards(drawCount);
			}

			@Override public void playerMayDoEffect(String prompt, java.util.function.Consumer<GameContext> effect) {
				if (!isP1) { logEntry("[P2 AI] Auto-accepts: " + prompt); effect.accept(this); return; }
				String src = mw.currentAbilitySource != null ? mw.currentAbilitySource.name() : "Ability";
				int choice = mw.showEffectOptionDialog(src + " — " + prompt, "You May", new Object[]{"OK", "Decline"});
				if (choice != 0) { logEntry("[Effect] Declined: " + prompt); return; }
				effect.accept(this);
			}

			@Override public int placeUpToFromHandToBottomOfDeck(int max) {
				if (isP1) return mw.showPlaceToBottomOfDeckDialog(max, true);
				// The AI always cycles as many of its worst cards as it can: the returned cards go
				// under the deck before the redraw, so the deck never gets shorter.
				List<CardData> hand = mw.gameState.getP2Hand();
				int actual = Math.min(max, hand.size());
				for (int i = 0; i < actual; i++) {
					CardData d = hand.remove(MainWindow.pickWorstHandCard0(hand));
					mw.gameState.getP2MainDeck().addLast(d);
					logEntry("[P2] Places " + d.name() + " at bottom of deck");
				}
				if (actual > 0) { mw.refreshP2HandCountLabel(); mw.refreshP2DeckLabel(); }
				return actual;
			}

			@Override public void placeFromHandToBottomOfDeck(int count) {
				if (isP1) {
					mw.showPlaceToBottomOfDeckDialog(count);
				} else {
					List<CardData> hand = mw.gameState.getP2Hand();
					int actual = Math.min(count, hand.size());
					for (int i = 0; i < actual; i++) {
						int idx = MainWindow.pickWorstHandCard0(hand);
						CardData d = hand.remove(idx);
						mw.gameState.getP2MainDeck().addLast(d);
						logEntry("[P2] Places " + d.name() + " at bottom of deck");
					}
					mw.refreshP2HandCountLabel();
					mw.refreshP2DeckLabel();
				}
			}

			@Override public void selfDiscardEntireHand() {
				if (isP1) {
					List<CardData> hand = mw.gameState.getP1Hand();
					for (int i = hand.size() - 1; i >= 0; i--) {
						CardData d = hand.remove(i);
						mw.animateCardDiscard(true, d);
						logEntry("Discards " + d.name());
						mw.p1Turn.discardedByEffectThisTurn = true;
						mw.addToBreakZone(d, false);
					}
					mw.refreshP1HandLabel();
				} else {
					List<CardData> hand = mw.gameState.getP2Hand();
					for (int i = hand.size() - 1; i >= 0; i--) {
						CardData d = hand.remove(i);
						mw.animateCardDiscard(false, d);
						logEntry("[P2] Discards " + d.name());
						mw.p2Turn.discardedByEffectThisTurn = true;
						mw.addToBreakZone(d, false);
					}
					mw.refreshP2HandCountLabel();
				}
			}

			@Override public void dealDamageToOpponent(int amount) {
				// Neon's Runic covers "a Forward or a player", so the player half is blanked here
				// exactly as DamageResolver blanks the Forward half — before any doubling below.
				if (mw.currentAbilitySource != null
						&& mw.damageZeroedSourcesThisTurn.contains(mw.currentAbilitySource)) {
					logEntry(mw.currentAbilitySource.name() + " — its damage becomes 0 this turn");
					return;
				}
				if (mw.currentAbilitySource != null
						&& !mw.lostAbilitiesCards.contains(mw.currentAbilitySource)) {
					// Read the same way DamageResolver.sourceHasOutgoingDmgToOpponentDoubler reads it,
					// so the combat and ability paths agree: a "Damage N --" gate on the printing
					// has to be met, and "a player" is the wider spelling of "your opponent".
					Boolean doublerSide = mw.fieldSideOf(mw.currentAbilitySource);
					int doublerDmg = doublerSide == null ? 0
							: (doublerSide ? mw.gameState.getP1DamageZone() : mw.gameState.getP2DamageZone()).size();
					doubler:
					for (FieldAbility fa : mw.effectiveFieldAbilities(mw.currentAbilitySource)) {
						if (fa.damageThreshold() > 0 && doublerDmg < fa.damageThreshold()) continue;
						// Kefka 23-004R prints his doubler inside a self grant; the clause list carries
						// the printed sentence first, so nothing that used to match stops matching.
						for (String clause : CardData.selfPassiveClauses(fa.effectText(),
								mw.currentAbilitySource.name())) {
							Matcher m = AutoAbilityTriggers.FA_OUTGOING_DAMAGE_DOUBLER.matcher(clause);
							if (!m.find()) continue;
							if (!m.group("card").trim().equalsIgnoreCase(mw.currentAbilitySource.name())) continue;
							String doublerTarget = m.group("target").toLowerCase();
							if (!doublerTarget.contains("opponent") && !doublerTarget.contains("player")) continue;
							logEntry(mw.currentAbilitySource.name() + " — outgoing damage to opponent doubled ("
									+ amount + " → " + (amount * 2) + ")");
							amount *= 2;
							break doubler;
						}
					}
					// A "becomes N instead" replacement wins over the doubler — it sets the damage
					// rather than scaling it, and the wording covers ability damage as well as combat.
					Integer override = mw.outgoingDamageToOpponentOverride(mw.currentAbilitySource);
					if (override != null && override != amount) {
						logEntry(mw.currentAbilitySource.name() + " — damage to opponent becomes "
								+ override + " instead of " + amount);
						amount = override;
					}
				}
				for (int i = 0; i < amount; i++) {
					mw.setPlayerDamageSource(mw.currentAbilitySource);
					if (isP1) mw.p2TakeDamage(); else mw.p1TakeDamage();
				}
			}

			@Override public void dealDamageToSelf(int amount) {
				for (int i = 0; i < amount; i++) {
					if (isP1) mw.p1TakeDamage(); else mw.p2TakeDamage();
				}
			}

			private boolean forwardHasAnyTrait(boolean p1Side, int idx, EnumSet<CardData.Trait> traitFilter) {
				if (traitFilter.isEmpty()) return true;
				List<EnumSet<CardData.Trait>> tempList = p1Side ? mw.p1ForwardTempTraits : mw.p2ForwardTempTraits;
				List<EnumSet<CardData.Trait>> rmList   = p1Side ? mw.p1ForwardRemovedTraits : mw.p2ForwardRemovedTraits;
				CardData c = p1Side ? p1Forward(idx) : mw.p2ForwardCards.get(idx);
				Set<CardData.Trait> base = c.traits();
				EnumSet<CardData.Trait> temp = idx < tempList.size() ? tempList.get(idx) : null;
				EnumSet<CardData.Trait> rem  = idx < rmList.size()   ? rmList.get(idx)   : null;
				for (CardData.Trait t : traitFilter) {
					boolean has = base.contains(t) || (temp != null && temp.contains(t));
					if (has && (rem == null || !rem.contains(t))) return true;
				}
				return false;
			}

			@Override
			public void applyMassFieldEffect(GameContext.MassAction action,
					boolean forwards, boolean backups, boolean monsters,
					boolean opponentOnly, boolean selfOnly,
					String element, int costVal, String costCmp, int excludeCostVal,
					String job, String category, EnumSet<CardData.Trait> traitFilter,
					String counterFilter) {
				boolean touchP1 = isP1 ? !opponentOnly : !selfOnly;
				boolean touchP2 = isP1 ? !selfOnly     : !opponentOnly;
				// Reset for every action, not just ACTIVATE, so a later sweep of any kind cannot
				// leave an earlier one's tally standing to be read as its own.
				mw.lastMassActivateCount = 0;
				if (touchP1) {
					if (forwards || monsters) {
						for (int i = mw.p1ForwardCards.size() - 1; i >= 0; i--) {
							CardData c = p1Forward(i);
							if (!forwards && !c.alsoCountsAsMonster()) continue;
							if (element != null && !mw.effectiveContainsElement(c, element)) continue;
							if (!meetsCostConstraint(c.cost(), costVal, costCmp)) continue;
							if (excludeCostVal >= 0 && c.cost() == excludeCostVal) continue;
							if (counterFilter != null && mw.gameState.getCounters(c, counterFilter) <= 0) continue;
							if (!mw.meetsJobFilterEffective(c, job)) continue;
							if (!meetsCategoryFilter(c, category)) continue;
							if (!forwardHasAnyTrait(true, i, traitFilter)) continue;
							switch (action) {
								case BREAK          -> breakP1Forward(i);
								case DULL           -> dullP1Forward(i);
								case FREEZE         -> freezeP1Forward(i);
								case DULL_AND_FREEZE -> { dullP1Forward(i); freezeP1Forward(i); }
								case ACTIVATE       -> { if (mw.p1ForwardStates.get(i) == CardState.DULL) mw.lastMassActivateCount++;
								                         mw.p1ForwardStates.set(i, CardState.ACTIVE); mw.refreshP1ForwardSlot(i); }
								case RETURN_TO_HAND -> returnP1ForwardToHand(i);
							}
						}
					}
					if (backups) {
						for (int i = 0; i < mw.p1BackupCards.length; i++) {
							if (mw.p1BackupCards[i] == null) continue;
							CardData c = mw.p1BackupCards[i];
							if (element != null && !mw.effectiveContainsElement(c, element)) continue;
							if (!meetsCostConstraint(c.cost(), costVal, costCmp)) continue;
							if (excludeCostVal >= 0 && c.cost() == excludeCostVal) continue;
							if (counterFilter != null && mw.gameState.getCounters(c, counterFilter) <= 0) continue;
							if (!mw.meetsJobFilterEffective(c, job)) continue;
							if (!meetsCategoryFilter(c, category)) continue;
							switch (action) {
								case BREAK -> {
									logEntry(c.name() + " is broken");
									mw.addToBreakZone(c, true);
									mw.p1BackupCards[i] = null;
									mw.p1BackupStates[i] = CardState.ACTIVE;
									mw.refreshP1BackupSlot(i);
									mw.refreshP1BreakLabel();
								}
								case DULL           -> { mw.p1BackupStates[i] = CardState.DULL;   logEntry(c.name() + " is dulled");          mw.refreshP1BackupSlot(i); }
								case FREEZE         -> { mw.p1BackupFrozen[i] = true;              logEntry(c.name() + " is frozen");          mw.refreshP1BackupSlot(i); }
								case DULL_AND_FREEZE -> { mw.p1BackupStates[i] = CardState.DULL; mw.p1BackupFrozen[i] = true; logEntry(c.name() + " is dulled & frozen"); mw.refreshP1BackupSlot(i); }
								case ACTIVATE       -> { if (mw.p1BackupStates[i] == CardState.DULL) mw.lastMassActivateCount++;
								                         mw.p1BackupStates[i] = CardState.ACTIVE; logEntry(c.name() + " is activated");       mw.refreshP1BackupSlot(i); }
								case RETURN_TO_HAND -> returnP1BackupToHand(i);
							}
						}
					}
					if (monsters) {
						for (int i = mw.p1MonsterCards.size() - 1; i >= 0; i--) {
							CardData c = mw.p1MonsterCards.get(i);
							if (element != null && !mw.effectiveContainsElement(c, element)) continue;
							if (!meetsCostConstraint(c.cost(), costVal, costCmp)) continue;
							if (excludeCostVal >= 0 && c.cost() == excludeCostVal) continue;
							if (counterFilter != null && mw.gameState.getCounters(c, counterFilter) <= 0) continue;
							if (!mw.meetsJobFilterEffective(c, job)) continue;
							if (!meetsCategoryFilter(c, category)) continue;
							switch (action) {
								case BREAK -> {
									logEntry(c.name() + " is broken");
									mw.addToBreakZone(c, true);
									mw.p1MonsterTempForwardPower.remove(c);
									mw.p1MonsterCards.remove(i);
									mw.p1MonsterStates.remove(i);
									mw.p1MonsterFrozen.remove(i);
									mw.p1MonsterPlayedOnTurn.remove(i);
									mw.p1MonsterUrls.remove(i);
									JLabel lbl = mw.p1MonsterLabels.remove(i);
									mw.p1MonsterPanel.remove(lbl);
									mw.p1MonsterPanel.revalidate();
									mw.p1MonsterPanel.repaint();
									mw.refreshP1BreakLabel();
								}
								case DULL           -> { mw.p1MonsterStates.set(i, CardState.DULL);   logEntry(c.name() + " is dulled");          mw.refreshP1MonsterSlot(i); }
								case FREEZE         -> { mw.p1MonsterFrozen.set(i, true);              logEntry(c.name() + " is frozen");          mw.refreshP1MonsterSlot(i); }
								case DULL_AND_FREEZE -> { mw.p1MonsterStates.set(i, CardState.DULL); mw.p1MonsterFrozen.set(i, true); logEntry(c.name() + " is dulled & frozen"); mw.refreshP1MonsterSlot(i); }
								case ACTIVATE       -> { if (mw.p1MonsterStates.get(i) == CardState.DULL) mw.lastMassActivateCount++;
								                         mw.p1MonsterStates.set(i, CardState.ACTIVE); logEntry(c.name() + " is activated");       mw.refreshP1MonsterSlot(i); }
								case RETURN_TO_HAND -> returnP1MonsterToHand(i);
							}
						}
					}
				}
				if (touchP2) {
					if (forwards || monsters) {
						for (int i = mw.p2ForwardCards.size() - 1; i >= 0; i--) {
							CardData c = mw.p2ForwardCards.get(i);
							if (!forwards && !c.alsoCountsAsMonster()) continue;
							if (element != null && !mw.effectiveContainsElement(c, element)) continue;
							if (!meetsCostConstraint(c.cost(), costVal, costCmp)) continue;
							if (excludeCostVal >= 0 && c.cost() == excludeCostVal) continue;
							if (counterFilter != null && mw.gameState.getCounters(c, counterFilter) <= 0) continue;
							if (!mw.meetsJobFilterEffective(c, job)) continue;
							if (!meetsCategoryFilter(c, category)) continue;
							if (!forwardHasAnyTrait(false, i, traitFilter)) continue;
							switch (action) {
								case BREAK          -> breakP2Forward(i);
								case DULL           -> dullP2Forward(i);
								case FREEZE         -> freezeP2Forward(i);
								case DULL_AND_FREEZE -> { dullP2Forward(i); freezeP2Forward(i); }
								case ACTIVATE       -> { if (mw.p2ForwardStates.get(i) == CardState.DULL) mw.lastMassActivateCount++;
								                         mw.p2ForwardStates.set(i, CardState.ACTIVE); mw.refreshP2ForwardSlot(i); }
								case RETURN_TO_HAND -> returnP2ForwardToHand(i);
							}
						}
					}
					if (backups) {
						for (int i = 0; i < mw.p2BackupCards.length; i++) {
							if (mw.p2BackupCards[i] == null) continue;
							CardData c = mw.p2BackupCards[i];
							if (element != null && !mw.effectiveContainsElement(c, element)) continue;
							if (!meetsCostConstraint(c.cost(), costVal, costCmp)) continue;
							if (excludeCostVal >= 0 && c.cost() == excludeCostVal) continue;
							if (counterFilter != null && mw.gameState.getCounters(c, counterFilter) <= 0) continue;
							if (!mw.meetsJobFilterEffective(c, job)) continue;
							if (!meetsCategoryFilter(c, category)) continue;
							switch (action) {
								case BREAK -> {
									logEntry("[P2] " + c.name() + " is broken");
									mw.addToBreakZone(c, true);
									mw.p2BackupCards[i] = null;
									mw.p2BackupStates[i] = CardState.ACTIVE;
									mw.refreshP2BackupSlot(i);
									mw.refreshP2BreakLabel();
								}
								case DULL           -> { mw.p2BackupStates[i] = CardState.DULL;   logEntry("[P2] " + c.name() + " is dulled");          mw.refreshP2BackupSlot(i); }
								case FREEZE         -> { mw.p2BackupFrozen[i] = true;              logEntry("[P2] " + c.name() + " is frozen");          mw.refreshP2BackupSlot(i); }
								case DULL_AND_FREEZE -> { mw.p2BackupStates[i] = CardState.DULL; mw.p2BackupFrozen[i] = true; logEntry("[P2] " + c.name() + " is dulled & frozen"); mw.refreshP2BackupSlot(i); }
								case ACTIVATE       -> { if (mw.p2BackupStates[i] == CardState.DULL) mw.lastMassActivateCount++;
								                         mw.p2BackupStates[i] = CardState.ACTIVE; logEntry("[P2] " + c.name() + " is activated");       mw.refreshP2BackupSlot(i); }
								case RETURN_TO_HAND -> returnP2BackupToHand(i);
							}
						}
					}
					if (monsters) {
						for (int i = mw.p2MonsterCards.size() - 1; i >= 0; i--) {
							CardData c = mw.p2MonsterCards.get(i);
							if (element != null && !mw.effectiveContainsElement(c, element)) continue;
							if (!meetsCostConstraint(c.cost(), costVal, costCmp)) continue;
							if (excludeCostVal >= 0 && c.cost() == excludeCostVal) continue;
							if (counterFilter != null && mw.gameState.getCounters(c, counterFilter) <= 0) continue;
							switch (action) {
								case BREAK -> {
									logEntry("[P2] " + c.name() + " is broken");
									mw.addToBreakZone(c, true);
									mw.p2MonsterTempForwardPower.remove(c);
									mw.p2MonsterCards.remove(i);
									mw.p2MonsterStates.remove(i);
									mw.p2MonsterFrozen.remove(i);
									mw.p2MonsterPlayedOnTurn.remove(i);
									mw.p2MonsterUrls.remove(i);
									JLabel lbl = mw.p2MonsterLabels.remove(i);
									mw.p2MonsterPanel.remove(lbl);
									mw.p2MonsterPanel.revalidate();
									mw.p2MonsterPanel.repaint();
									mw.refreshP2BreakLabel();
								}
								case DULL           -> { mw.p2MonsterStates.set(i, CardState.DULL);   logEntry("[P2] " + c.name() + " is dulled");          mw.refreshP2MonsterSlot(i); }
								case FREEZE         -> { mw.p2MonsterFrozen.set(i, true);              logEntry("[P2] " + c.name() + " is frozen");          mw.refreshP2MonsterSlot(i); }
								case DULL_AND_FREEZE -> { mw.p2MonsterStates.set(i, CardState.DULL); mw.p2MonsterFrozen.set(i, true); logEntry("[P2] " + c.name() + " is dulled & frozen"); mw.refreshP2MonsterSlot(i); }
								case ACTIVATE       -> { if (mw.p2MonsterStates.get(i) == CardState.DULL) mw.lastMassActivateCount++;
								                         mw.p2MonsterStates.set(i, CardState.ACTIVE); logEntry("[P2] " + c.name() + " is activated");       mw.refreshP2MonsterSlot(i); }
								case RETURN_TO_HAND -> returnP2MonsterToHand(i);
							}
						}
					}
				}
			}

			@Override public int lastMassActivateCount() { return mw.lastMassActivateCount; }

			@Override
			public void applyMassFieldPowerBoost(int amount, boolean inclForwards, boolean inclMonsters,
					boolean opponentOnly, boolean selfOnly,
					String element, int costVal, String costCmp, String category, String excludeName) {
				boolean touchP1 = isP1 ? !opponentOnly : !selfOnly;
				boolean touchP2 = isP1 ? !selfOnly     : !opponentOnly;
				boolean p1BoostSuppressed = inclForwards && amount > 0 && (mw.oppForwardPowerBoostSuppressedFor(true) || (isP1 && mw.oppForwardSelfBoostSuppressedFor(true)));
				boolean p2BoostSuppressed = inclForwards && amount > 0 && (mw.oppForwardPowerBoostSuppressedFor(false) || (!isP1 && mw.oppForwardSelfBoostSuppressedFor(false)));
				if (touchP1) {
					if (inclForwards) {
						for (int i = 0; i < mw.p1ForwardCards.size(); i++) {
							CardData c = p1Forward(i);
							if (element != null && !mw.effectiveContainsElement(c, element)) continue;
							if (!meetsCostConstraint(c.cost(), costVal, costCmp)) continue;
							if (!CardFilters.meetsCategoryFilter(c, category)) continue;
							if (excludeName != null && CardFilters.meetsCardNameFilter(c, excludeName)) continue;
							if (p1BoostSuppressed) { logEntry(c.name() + " — power boost suppressed"); continue; }
							if (amount < 0 && !isP1
									&& mw.effectiveP1HasTrait(i, CardData.Trait.POWER_CANNOT_BE_DECREASED_BY_OPP)) {
								logEntry(c.name() + " — power cannot be decreased by opponent's effects");
								continue;
							}
							mw.p1ForwardPowerBoost.set(i, mw.p1ForwardPowerBoost.get(i) + amount);
							logEntry(c.name() + " gains +" + amount + " power until end of turn");
							mw.refreshP1ForwardSlot(i);
						}
					}
					if (inclMonsters) {
						for (int i = 0; i < mw.p1MonsterCards.size(); i++) {
							CardData c = mw.p1MonsterCards.get(i);
							if (element != null && !mw.effectiveContainsElement(c, element)) continue;
							if (!meetsCostConstraint(c.cost(), costVal, costCmp)) continue;
							if (!CardFilters.meetsCategoryFilter(c, category)) continue;
							if (excludeName != null && CardFilters.meetsCardNameFilter(c, excludeName)) continue;
							logEntry(c.name() + " gains +" + amount + " power until end of turn");
						}
					}
				}
				if (touchP2) {
					if (inclForwards) {
						for (int i = 0; i < mw.p2ForwardCards.size(); i++) {
							CardData c = mw.p2ForwardCards.get(i);
							if (element != null && !mw.effectiveContainsElement(c, element)) continue;
							if (!meetsCostConstraint(c.cost(), costVal, costCmp)) continue;
							if (!CardFilters.meetsCategoryFilter(c, category)) continue;
							if (excludeName != null && CardFilters.meetsCardNameFilter(c, excludeName)) continue;
							if (p2BoostSuppressed) { logEntry("[P2] " + c.name() + " — power boost suppressed"); continue; }
							if (amount < 0 && isP1
									&& mw.effectiveP2HasTrait(i, CardData.Trait.POWER_CANNOT_BE_DECREASED_BY_OPP)) {
								logEntry("[P2] " + c.name() + " — power cannot be decreased by opponent's effects");
								continue;
							}
							mw.p2ForwardPowerBoost.set(i, mw.p2ForwardPowerBoost.get(i) + amount);
							logEntry("[P2] " + c.name() + " gains +" + amount + " power until end of turn");
							mw.refreshP2ForwardSlot(i);
						}
					}
					if (inclMonsters) {
						for (int i = 0; i < mw.p2MonsterCards.size(); i++) {
							CardData c = mw.p2MonsterCards.get(i);
							if (element != null && !mw.effectiveContainsElement(c, element)) continue;
							if (!meetsCostConstraint(c.cost(), costVal, costCmp)) continue;
							if (!CardFilters.meetsCategoryFilter(c, category)) continue;
							if (excludeName != null && CardFilters.meetsCardNameFilter(c, excludeName)) continue;
							logEntry("[P2] " + c.name() + " gains +" + amount + " power until end of turn");
						}
					}
				}
			}

			@Override public void applyCurrentPartyForwardsPowerBoost(int amount) {
				List<CardData> party = mw.turn(isP1).currentPartyAttackers;
				List<CardData> fwds  = isP1 ? mw.p1ForwardCards : mw.p2ForwardCards;
				// The booster is always the party controller, so self-boost suppression applies too.
				boolean suppressed = amount > 0
						&& (mw.oppForwardPowerBoostSuppressedFor(isP1) || mw.oppForwardSelfBoostSuppressedFor(isP1));
				for (CardData member : party) {
					int i = fwds.indexOf(member);
					if (i < 0) continue; // party member has since left the field
					if (suppressed) { logEntry((isP1 ? "" : "[P2] ") + member.name() + " — power boost suppressed"); continue; }
					if (isP1) {
						mw.p1ForwardPowerBoost.set(i, mw.p1ForwardPowerBoost.get(i) + amount);
						logEntry(member.name() + " gains +" + amount + " power until end of turn");
						mw.refreshP1ForwardSlot(i);
					} else {
						mw.p2ForwardPowerBoost.set(i, mw.p2ForwardPowerBoost.get(i) + amount);
						logEntry("[P2] " + member.name() + " gains +" + amount + " power until end of turn");
						mw.refreshP2ForwardSlot(i);
					}
				}
			}

			@Override public int currentPartyAttackerCount() {
				List<CardData> party = mw.turn(isP1).currentPartyAttackers;
				List<CardData> fwds  = isP1 ? mw.p1ForwardCards : mw.p2ForwardCards;
				int count = 0;
				for (CardData member : party) if (MainWindow.identityIndexOf(fwds, member) >= 0) count++;
				return count;
			}

			@Override public void allForwardsSameElementAsNamedGainPowerUntilEOT(
					String cardName, int amount, boolean opponentOnly, boolean selfOnly) {
				// Find the named card on the caster's own field to determine its element(s)
				List<CardData> myFwds = isP1 ? mw.p1ForwardCards : mw.p2ForwardCards;
				CardData[] myBkps    = isP1 ? mw.p1BackupCards   : mw.p2BackupCards;
				List<CardData> myMons = isP1 ? mw.p1MonsterCards : mw.p2MonsterCards;
				CardData named = null;
				for (CardData c : myFwds) if (CardFilters.meetsCardNameFilter(c, cardName)) { named = c; break; }
				if (named == null) for (CardData c : myBkps) if (c != null && CardFilters.meetsCardNameFilter(c, cardName)) { named = c; break; }
				if (named == null) for (CardData c : myMons) if (CardFilters.meetsCardNameFilter(c, cardName)) { named = c; break; }
				if (named == null) {
					logEntry(cardName + " not found on field — effect fizzles");
					markEffectFizzled();
					return;
				}
				final CardData src = named;
				java.util.function.Predicate<CardData> sharesElement = c -> {
					for (String e : List.of("fire","ice","wind","earth","lightning","water","light","dark"))
						if (mw.effectiveContainsElement(c, e) && mw.effectiveContainsElement(src, e)) return true;
					return false;
				};
				boolean touchP1 = isP1 ? !opponentOnly : !selfOnly;
				boolean touchP2 = isP1 ? !selfOnly     : !opponentOnly;
				if (touchP1) {
					boolean suppressed = amount > 0 && (mw.oppForwardPowerBoostSuppressedFor(true) || (isP1 && mw.oppForwardSelfBoostSuppressedFor(true)));
					for (int i = 0; i < mw.p1ForwardCards.size(); i++) {
						CardData c = p1Forward(i);
						if (!sharesElement.test(c)) continue;
						if (suppressed) { logEntry(c.name() + " — power boost suppressed"); continue; }
						mw.p1ForwardPowerBoost.set(i, mw.p1ForwardPowerBoost.get(i) + amount);
						logEntry(c.name() + " gains +" + amount + " power until end of turn");
						mw.refreshP1ForwardSlot(i);
					}
				}
				if (touchP2) {
					boolean suppressed = amount > 0 && (mw.oppForwardPowerBoostSuppressedFor(false) || (!isP1 && mw.oppForwardSelfBoostSuppressedFor(false)));
					for (int i = 0; i < mw.p2ForwardCards.size(); i++) {
						CardData c = p2Forward(i);
						if (!sharesElement.test(c)) continue;
						if (suppressed) { logEntry("[P2] " + c.name() + " — power boost suppressed"); continue; }
						mw.p2ForwardPowerBoost.set(i, mw.p2ForwardPowerBoost.get(i) + amount);
						logEntry("[P2] " + c.name() + " gains +" + amount + " power until end of turn");
						mw.refreshP2ForwardSlot(i);
					}
				}
			}

			@Override public void applyMassFieldJobCardNamePowerBoost(int amount, boolean inclForwards, boolean inclMonsters,
					boolean opponentOnly, boolean selfOnly, String jobFilter, String cardNameFilter) {
				boolean touchP1 = isP1 ? !opponentOnly : !selfOnly;
				boolean touchP2 = isP1 ? !selfOnly     : !opponentOnly;
				boolean p1JobBoostSuppressed = inclForwards && amount > 0 && (mw.oppForwardPowerBoostSuppressedFor(true) || (isP1 && mw.oppForwardSelfBoostSuppressedFor(true)));
				boolean p2JobBoostSuppressed = inclForwards && amount > 0 && (mw.oppForwardPowerBoostSuppressedFor(false) || (!isP1 && mw.oppForwardSelfBoostSuppressedFor(false)));
				if (touchP1) {
					if (inclForwards) {
						for (int i = 0; i < mw.p1ForwardCards.size(); i++) {
							CardData c = p1Forward(i);
							if (!CardFilters.meetsJobFilter(c, jobFilter) && (cardNameFilter == null || !CardFilters.meetsCardNameFilter(c, cardNameFilter))) continue;
							if (p1JobBoostSuppressed) { logEntry(c.name() + " — power boost suppressed"); continue; }
							mw.p1ForwardPowerBoost.set(i, mw.p1ForwardPowerBoost.get(i) + amount);
							logEntry(c.name() + " gains +" + amount + " power until end of turn");
							mw.refreshP1ForwardSlot(i);
						}
					}
					if (inclMonsters) {
						for (int i = 0; i < mw.p1MonsterCards.size(); i++) {
							CardData c = mw.p1MonsterCards.get(i);
							if (!CardFilters.meetsJobFilter(c, jobFilter) && (cardNameFilter == null || !CardFilters.meetsCardNameFilter(c, cardNameFilter))) continue;
							logEntry(c.name() + " gains +" + amount + " power until end of turn");
						}
					}
				}
				if (touchP2) {
					if (inclForwards) {
						for (int i = 0; i < mw.p2ForwardCards.size(); i++) {
							CardData c = mw.p2ForwardCards.get(i);
							if (!CardFilters.meetsJobFilter(c, jobFilter) && (cardNameFilter == null || !CardFilters.meetsCardNameFilter(c, cardNameFilter))) continue;
							if (p2JobBoostSuppressed) { logEntry("[P2] " + c.name() + " — power boost suppressed"); continue; }
							mw.p2ForwardPowerBoost.set(i, mw.p2ForwardPowerBoost.get(i) + amount);
							logEntry("[P2] " + c.name() + " gains +" + amount + " power until end of turn");
							mw.refreshP2ForwardSlot(i);
						}
					}
					if (inclMonsters) {
						for (int i = 0; i < mw.p2MonsterCards.size(); i++) {
							CardData c = mw.p2MonsterCards.get(i);
							if (!CardFilters.meetsJobFilter(c, jobFilter) && (cardNameFilter == null || !CardFilters.meetsCardNameFilter(c, cardNameFilter))) continue;
							logEntry("[P2] " + c.name() + " gains +" + amount + " power until end of turn");
						}
					}
				}
			}

			@Override public void applyOppFwdsCostScaledPowerDebuff(int powerPerCp) {
				List<CardData> oppFwds  = isP1 ? mw.p2ForwardCards       : mw.p1ForwardCards;
				List<Integer>  oppBoost = isP1 ? mw.p2ForwardPowerBoost   : mw.p1ForwardPowerBoost;
				String prefix = isP1 ? "[P2] " : "";

				// First pass: apply per-cost debuff to every opponent Forward
				for (int i = 0; i < oppFwds.size(); i++) {
					CardData c = oppFwds.get(i);
					int reduction = c.cost() * powerPerCp;
					oppBoost.set(i, oppBoost.get(i) - reduction);
					logEntry(prefix + c.name() + " loses " + reduction + " power until end of turn");
					if (isP1) mw.refreshP2ForwardSlot(i); else mw.refreshP1ForwardSlot(i);
				}

				// Second pass: break any that dropped to 0 or below (iterate backwards to preserve indices)
				for (int i = oppFwds.size() - 1; i >= 0; i--) {
					int effPower = isP1 ? mw.effectiveP2ForwardPower(i) : mw.effectiveP1ForwardPower(i);
					if (effPower <= 0) {
						logEntry(prefix + oppFwds.get(i).name() + " power dropped to " + effPower + " — broken");
						if (isP1) breakP2Forward(i); else breakP1Forward(i);
					}
				}
			}

			@Override public void applyMassFieldKeywordGrant(EnumSet<CardData.Trait> traits,
					boolean inclForwards, boolean inclMonsters,
					boolean opponentOnly, boolean selfOnly,
					String element, int costVal, String costCmp, String category) {
				boolean touchP1 = isP1 ? !opponentOnly : !selfOnly;
				boolean touchP2 = isP1 ? !selfOnly     : !opponentOnly;
				if (touchP1 && inclForwards) {
					for (int i = 0; i < mw.p1ForwardCards.size(); i++) {
						CardData c = p1Forward(i);
						if (element != null && !mw.effectiveContainsElement(c, element)) continue;
						if (!meetsCostConstraint(c.cost(), costVal, costCmp)) continue;
						if (!CardFilters.meetsCategoryFilter(c, category)) continue;
						mw.p1ForwardTempTraits.get(i).addAll(traits);
						logEntry(c.name() + " gains " + traits + " until end of turn");
						mw.refreshP1ForwardSlot(i);
					}
				}
				if (touchP2 && inclForwards) {
					for (int i = 0; i < mw.p2ForwardCards.size(); i++) {
						CardData c = mw.p2ForwardCards.get(i);
						if (element != null && !mw.effectiveContainsElement(c, element)) continue;
						if (!meetsCostConstraint(c.cost(), costVal, costCmp)) continue;
						if (!CardFilters.meetsCategoryFilter(c, category)) continue;
						mw.p2ForwardTempTraits.get(i).addAll(traits);
						logEntry("[P2] " + c.name() + " gains " + traits + " until end of turn");
						mw.refreshP2ForwardSlot(i);
					}
				}
			}

			@Override public void applyMassFieldJobKeywordGrant(EnumSet<CardData.Trait> traits,
					boolean inclForwards, boolean inclMonsters,
					boolean opponentOnly, boolean selfOnly,
					String jobFilter) {
				boolean touchP1 = isP1 ? !opponentOnly : !selfOnly;
				boolean touchP2 = isP1 ? !selfOnly     : !opponentOnly;
				if (touchP1 && inclForwards) {
					for (int i = 0; i < mw.p1ForwardCards.size(); i++) {
						CardData c = p1Forward(i);
						if (!CardFilters.meetsJobFilter(c, jobFilter)) continue;
						mw.p1ForwardTempTraits.get(i).addAll(traits);
						logEntry(c.name() + " gains " + traits + " until end of turn");
						mw.refreshP1ForwardSlot(i);
					}
				}
				if (touchP2 && inclForwards) {
					for (int i = 0; i < mw.p2ForwardCards.size(); i++) {
						CardData c = mw.p2ForwardCards.get(i);
						if (!CardFilters.meetsJobFilter(c, jobFilter)) continue;
						mw.p2ForwardTempTraits.get(i).addAll(traits);
						logEntry("[P2] " + c.name() + " gains " + traits + " until end of turn");
						mw.refreshP2ForwardSlot(i);
					}
				}
			}

			@Override public void addEndOfTurnEffect(Consumer<GameContext> effect) {
				mw.endOfTurnEffects.add(effect);
			}

			@Override public void addEndOfOpponentTurnEffect(Consumer<GameContext> effect) {
				// Schedule to fire when the OTHER player ends their turn.
				if (isP1) mw.scheduledForP2EndTurn.add(effect);
				else      mw.scheduledForP1EndTurn.add(effect);
			}

			@Override public boolean promptYouMay(String prompt) {
				// The AI declines every optional effect. That is not a considered heuristic — it
				// is what the P2 branch used to hardcode — but it is the safe answer of the two,
				// since every caller treats "no" as the effect simply not happening.
				return askYesNo(isP1, ChoiceKind.MAY, prompt, "You May",
						"Waiting for your opponent: " + prompt, false);
			}

			/**
			 * Puts a yes/no question to the seat at {@code seatIsP1} and returns their answer.
			 *
			 * <p>Both of this engine's yes/no questions used to be written as
			 * {@code if (isP1) ask(); else <fixed answer>;}, which quietly meant the opposite of
			 * what it looked like once the other seat could hold a human: their client asked them,
			 * this one assumed, and the two resolved the same ability differently. Routing it
			 * through {@link MainWindow#decide} is what makes the fixed answer the <em>AI's</em>
			 * rather than everyone-who-is-not-me's.
			 *
			 * @param cpuAnswer what the AI says when it holds the seat
			 */
			private boolean askYesNo(boolean seatIsP1, ChoiceKind kind, String prompt, String title,
					String waitPrompt, boolean cpuAnswer) {
				List<Integer> answer = mw.decide(PlayerChoice.by(seatIsP1, kind)
						.prompting(waitPrompt)
						.locally(() -> List.of(
								JOptionPane.showConfirmDialog(mw.frame, prompt, title,
										JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION ? 1 : 0))
						.byCpu(()   -> List.of(cpuAnswer ? 1 : 0))
						// Nothing about the board makes one answer illegal — only the shape.
						.legalWhen(a -> a.size() == 1 && (a.get(0) == 0 || a.get(0) == 1),
								"a yes or a no is the only answer that fits"));
				return !answer.isEmpty() && answer.get(0) == 1;
			}

			@Override public void addTempAttackTrigger(CardData card, Consumer<GameContext> effect) {
				Map<CardData, List<Consumer<GameContext>>> triggers
						= isP1 ? mw.p1TempAttackTriggers : mw.p2TempAttackTriggers;
				triggers.computeIfAbsent(card, k -> new ArrayList<>()).add(effect);
			}

			@Override public void addTempBlockTrigger(CardData card, Consumer<GameContext> effect) {
				Map<CardData, List<Consumer<GameContext>>> triggers
						= isP1 ? mw.p1TempBlockTriggers : mw.p2TempBlockTriggers;
				triggers.computeIfAbsent(card, k -> new ArrayList<>()).add(effect);
			}

			@Override public boolean abilityUserControlsCard(String cardName) {
				List<CardData> fwds = isP1 ? mw.p1ForwardCards : mw.p2ForwardCards;
				List<CardData> mons = isP1 ? mw.p1MonsterCards : mw.p2MonsterCards;
				CardData[]     bkps = isP1 ? mw.p1BackupCards  : mw.p2BackupCards;
				for (CardData c : fwds) if (c != null && c.name().equalsIgnoreCase(cardName)) return true;
				for (CardData c : mons) if (c != null && c.name().equalsIgnoreCase(cardName)) return true;
				for (CardData c : bkps) if (c != null && c.name().equalsIgnoreCase(cardName)) return true;
				return false;
			}

			@Override public void applyNextCastCostReduction(CostReductionModifier modifier) {
				mw.activeCostReductions.add(modifier);
				mw.endOfTurnEffects.add(ctx -> mw.activeCostReductions.remove(modifier));
			}

			@Override public void chooseSummonFromOwnBzToHand() {
				List<CardData> bz = isP1 ? mw.gameState.getP1BreakZone() : mw.gameState.getP2BreakZone();
				List<CardData> candidates = new ArrayList<>();
				for (CardData c : bz) if (c.isSummon()) candidates.add(c);
				if (candidates.isEmpty()) {
					logEntry((isP1 ? "P1" : "P2") + " Break Zone has no Summon — effect fizzles");
					return;
				}
				CardData picked = isP1
						? mw.chooseCardFromBzDialog(candidates, "Choose 1 Summon from your Break Zone")
						: candidates.get(0);
				if (picked == null) return;
				bz.remove(picked);
				List<CardData> hand = isP1 ? mw.gameState.getP1Hand() : mw.gameState.getP2Hand();
				hand.add(picked);
				logEntry(picked.name() + " → " + (isP1 ? "P1" : "P2") + " hand from Break Zone");
				if (isP1) { mw.refreshP1BreakLabel(); mw.refreshP1HandLabel(); }
				else       { mw.refreshP2BreakLabel(); mw.refreshP2HandCountLabel(); }
			}

			@Override public void chooseNamedFromOwnRfgToHand(String cardName) {
				// The RFG zone proper, not the Warp zone — the same reading countP1RfgCards takes
				// of "removed from the game", so the two never disagree about what is in there.
				List<CardData> rfg = isP1 ? mw.gameState.getP1PermanentRfp() : mw.gameState.getP2PermanentRfp();
				List<CardData> candidates = new ArrayList<>();
				for (CardData c : rfg) if (meetsCardNameFilter(c, cardName)) candidates.add(c);
				if (candidates.isEmpty()) {
					logEntry((isP1 ? "P1" : "P2") + " has no Card Name " + cardName
							+ " removed from the game — effect fizzles");
					markEffectFizzled();
					return;
				}
				CardData picked = isP1
						? mw.chooseCardFromBzDialog(candidates,
								"Select 1 Card Name " + cardName + " removed from the game")
						: candidates.get(0);
				if (picked == null) return;
				if (!mw.gameState.removeFromPermanentRfp(picked)) return;
				(isP1 ? mw.gameState.getP1Hand() : mw.gameState.getP2Hand()).add(picked);
				logEntry(picked.name() + " → " + (isP1 ? "P1" : "P2") + " hand from Removed From Game");
				if (isP1) { mw.refreshP1HandLabel();      mw.refreshP1WarpZoneUI(); }
				else      { mw.refreshP2HandCountLabel(); mw.refreshP2WarpZoneUI(); }
			}

			@Override public void chooseSummonsFromBzPickOneToHandRestRfg(int total) {
				List<CardData> bz = isP1 ? mw.gameState.getP1BreakZone() : mw.gameState.getP2BreakZone();
				List<CardData> allSummons = new ArrayList<>();
				for (CardData c : bz) if (c.isSummon()) allSummons.add(c);
				if (allSummons.isEmpty()) {
					logEntry((isP1 ? "P1" : "P2") + " Break Zone has no Summons — effect fizzles");
					return;
				}
				// Build the pool of up to `total` Summons to choose from
				List<CardData> pool;
				if (allSummons.size() <= total) {
					pool = new ArrayList<>(allSummons);
				} else if (isP1) {
					pool = new ArrayList<>();
					List<CardData> remaining = new ArrayList<>(allSummons);
					for (int i = 0; i < total && !remaining.isEmpty(); i++) {
						CardData pick = mw.chooseCardFromBzDialog(remaining,
								"Choose Summon " + (i + 1) + " of " + total + " from your Break Zone");
						if (pick == null) break;
						pool.add(pick);
						remaining.remove(pick);
					}
					if (pool.isEmpty()) return;
				} else {
					pool = new ArrayList<>(allSummons.subList(0, total));
				}
				// Pick 1 from the pool to add to hand
				CardData kept = isP1
						? mw.chooseCardFromBzDialog(pool, "Choose 1 Summon to add to your hand")
						: pool.get(0);
				if (kept == null) return;
				bz.remove(kept);
				List<CardData> hand = isP1 ? mw.gameState.getP1Hand() : mw.gameState.getP2Hand();
				hand.add(kept);
				logEntry((isP1 ? "" : "[P2] ") + kept.name() + " → hand from Break Zone");
				// Remove the rest from the game
				for (CardData c : pool) {
					if (c == kept) continue;
					bz.remove(c);
					mw.gameState.addToPermanentRfp(c);
					logEntry((isP1 ? "" : "[P2] ") + c.name() + " → Removed From Game");
				}
				if (isP1) { mw.refreshP1BreakLabel(); mw.refreshP1HandLabel(); }
				else       { mw.refreshP2BreakLabel(); mw.refreshP2HandCountLabel(); }
			}

			@Override public void chooseSummonInBzMakeCastable(String element, int costReduction) {
				List<CardData> bz = isP1 ? mw.gameState.getP1BreakZone() : mw.gameState.getP2BreakZone();
				List<CardData> candidates = new ArrayList<>();
				for (CardData c : bz)
					if (c.isSummon() && c.containsElement(element)) candidates.add(c);
				if (candidates.isEmpty()) {
					logEntry("[ChooseSummonInBz] No " + element + " Summon in "
							+ (isP1 ? "your" : "P2's") + " Break Zone — effect fizzles");
					return;
				}
				CardData picked = isP1
						? mw.chooseSummonFromBzDialog(candidates, element)
						: candidates.get(0);
				if (picked == null) return;
				mw.registerBorrowedPlayable(isP1, picked, PlayableEntry.bzThisTurn(costReduction));
				logEntry((isP1 ? "" : "[P2] ") + picked.name()
						+ " in Break Zone is castable this turn (cost -" + costReduction + ")");
				if (isP1) mw.refreshP1BreakLabel(); else mw.refreshP2BreakLabel();
			}

			@Override public void opponentRfpTopDeckMakeCastable(int costReduction, boolean anyElement) {
				Deque<CardData> oppDeck = isP1 ? mw.gameState.getP2MainDeck() : mw.gameState.getP1MainDeck();
				if (oppDeck.isEmpty()) { logEntry("Opponent's deck is empty — nothing removed"); return; }
				CardData top = oppDeck.pollFirst();
				mw.gameState.addToPermanentRfp(top);
				if (isP1) mw.refreshP2DeckLabel(); else mw.refreshP1DeckLabel();
				PlayableEntry entry = new PlayableEntry(PlayableEntry.SourceZone.RFP,
						costReduction, anyElement, false, false, false);
				mw.registerBorrowedPlayable(isP1, top, entry);
				logEntry((isP1 ? "" : "[P2] ") + "Opponent's top deck card (" + top.name()
						+ ") removed from game — castable as your own"
						+ (costReduction > 0 ? " (cost -" + costReduction + ")" : "")
						+ (anyElement ? " [any Element]" : ""));
			}

			@Override public void chooseFromOpponentBzMakeCastable(boolean inclForwards,
					boolean inclBackups, boolean inclMonsters) {
				List<CardData> oppBz = mw.bzCardsProtectedFromOppChoice(!isP1)
						? List.<CardData>of()   // Kalmia 18-090R — nothing there may be chosen
						: isP1 ? mw.gameState.getP2BreakZone() : mw.gameState.getP1BreakZone();
				List<CardData> candidates = new ArrayList<>();
				for (CardData c : oppBz) {
					if (c.isForward() && inclForwards) candidates.add(c);
					else if (c.isBackup() && inclBackups) candidates.add(c);
					else if (c.isMonster() && inclMonsters) candidates.add(c);
				}
				if (candidates.isEmpty()) {
					logEntry("No eligible card in opponent's Break Zone — effect fizzles");
					return;
				}
				CardData picked = isP1
						? mw.chooseCardFromBzDialog(candidates, "Choose 1 card in opponent's Break Zone")
						: candidates.get(0);
				if (picked == null) return;
				List<CardData> ownerBz = isP1 ? mw.gameState.getP2BreakZone() : mw.gameState.getP1BreakZone();
				ownerBz.remove(picked);
				mw.gameState.addToPermanentRfp(picked);
				if (isP1) mw.refreshP2BreakLabel(); else mw.refreshP1BreakLabel();
				PlayableEntry entry = new PlayableEntry(PlayableEntry.SourceZone.RFP, 0, false, false, false, false);
				mw.registerBorrowedPlayable(isP1, picked, entry);
				logEntry((isP1 ? "" : "[P2] ") + picked.name()
						+ " removed from opponent's Break Zone — castable as your own during this game");
			}

			@Override public void chooseSummonsFromBzMakeCastable(int count, boolean eitherBz,
					boolean expiresThisTurn, boolean rfgAfterUse, boolean freeCast) {
				for (int picks = 0; picks < count; picks++) {
					List<CardData> candidates = new ArrayList<>();
					List<CardData> ownBz = isP1 ? mw.gameState.getP1BreakZone() : mw.gameState.getP2BreakZone();
					List<CardData> oppBz = isP1 ? mw.gameState.getP2BreakZone() : mw.gameState.getP1BreakZone();
					for (CardData c : ownBz) if (c.isSummon() && !mw.bzPlayableP1.containsKey(c) && !mw.bzPlayableP2.containsKey(c)) candidates.add(c);
					// Two shields on the same half of this choice: one against removal from the
					// game specifically, one against being chosen at all (Kalmia 18-090R).
					if (eitherBz && !mw.bzSummonsProtectedFromOppRfg(!isP1)
							&& !mw.bzCardsProtectedFromOppChoice(!isP1))
						for (CardData c : oppBz) if (c.isSummon() && !mw.bzPlayableP1.containsKey(c) && !mw.bzPlayableP2.containsKey(c)) candidates.add(c);
					if (candidates.isEmpty()) {
						if (picks == 0) logEntry("No eligible Summon in Break Zone — effect fizzles");
						return;
					}
					CardData picked = isP1
							? mw.chooseCardFromBzDialog(candidates, "Choose a Summon from the Break Zone")
							: candidates.get(0);
					if (picked == null) return;
					// The pick may come from either Break Zone; try our own first, then the opponent's.
					// addToPermanentRfp resolves the card's true owner from the identity map.
					boolean inOwnBz = ownBz.remove(picked);
					if (!inOwnBz) oppBz.remove(picked);
					mw.gameState.addToPermanentRfp(picked);
					mw.refreshP1BreakLabel(); mw.refreshP2BreakLabel();
					PlayableEntry entry = new PlayableEntry(PlayableEntry.SourceZone.RFP, 0, false,
							freeCast, rfgAfterUse, expiresThisTurn);
					mw.registerBorrowedPlayable(isP1, picked, entry);
					logEntry((isP1 ? "" : "[P2] ") + picked.name()
							+ " removed from game — castable as your own"
							+ (expiresThisTurn ? " this turn" : " during this game")
							+ (freeCast ? " without paying its cost" : ""));
				}
			}

			@Override public void chooseSummonInBzByMaxCostFreeCastRfgAfterUse(int maxCost,
					Set<String> excludedElements) {
				List<CardData> bz = isP1 ? mw.gameState.getP1BreakZone() : mw.gameState.getP2BreakZone();
				List<CardData> candidates = new ArrayList<>();
				for (CardData c : bz) {
					if (!c.isSummon() || c.cost() > maxCost) continue;
					// A Multi-Element Summon carrying an excluded Element is excluded by it.
					boolean excluded = false;
					for (String e : excludedElements) if (c.containsElement(e)) { excluded = true; break; }
					if (excluded) continue;
					candidates.add(c);
				}
				if (candidates.isEmpty()) {
					logEntry((isP1 ? "" : "[P2] ") + "No Summon of cost ≤ " + maxCost + " in Break Zone — effect fizzles");
					return;
				}
				CardData picked = isP1
						? mw.chooseCardFromBzDialog(candidates, "Choose a Summon of cost ≤ " + maxCost)
						: candidates.get(0);
				if (picked == null) return;
				PlayableEntry entry = new PlayableEntry(PlayableEntry.SourceZone.BREAK_ZONE, 0, false, true, true, true);
				mw.registerBorrowedPlayable(isP1, picked, entry);
				logEntry((isP1 ? "" : "[P2] ") + picked.name()
						+ " in Break Zone is castable this turn (free) — removed from game after use");
				if (isP1) mw.refreshP1BreakLabel(); else mw.refreshP2BreakLabel();
			}

			@Override public List<FieldAbility> getActiveFieldAbilities() {
				List<FieldAbility> active = new ArrayList<>();
				for (CardData c : mw.p1ForwardCards) active.addAll(c.fieldAbilities());
				for (CardData c : mw.p1MonsterCards)  active.addAll(c.fieldAbilities());
				for (CardData c : mw.p1BackupCards)   if (c != null) active.addAll(c.fieldAbilities());
				for (CardData c : mw.p2ForwardCards)  active.addAll(c.fieldAbilities());
				for (CardData c : mw.p2MonsterCards)  active.addAll(c.fieldAbilities());
				for (CardData c : mw.p2BackupCards)   if (c != null) active.addAll(c.fieldAbilities());
				return active;
			}

			@Override public int p1DamageCount() { return mw.gameState.getP1DamageZone().size(); }
			@Override public int p2DamageCount() { return mw.gameState.getP2DamageZone().size(); }

			@Override public int opponentHandSize() {
				return (isP1 ? mw.gameState.getP2Hand() : mw.gameState.getP1Hand()).size();
			}

			@Override public int yourHandSize() {
				return (isP1 ? mw.gameState.getP1Hand() : mw.gameState.getP2Hand()).size();
			}

			@Override public int countP1FieldCards(boolean inclForwards, boolean inclBackups,
					boolean inclMonsters, String jobFilter, String cardNameFilter) {
				return countP1FieldCards(inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, null);
			}

			@Override public int countP1FieldCards(boolean inclForwards, boolean inclBackups,
					boolean inclMonsters, String jobFilter, String cardNameFilter, String categoryFilter) {
				return countP1FieldCards(inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, null);
			}

			@Override public int countP1FieldCards(boolean inclForwards, boolean inclBackups,
					boolean inclMonsters, String jobFilter, String cardNameFilter, String categoryFilter, String elementFilter) {
				return countP1FieldCards(inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, elementFilter, -1);
			}

			/**
			 * Job and card name are ANDed here, unlike in the selections — deliberately, and not
			 * an oversight to "fix" later. A caller wanting the union of the two asks three times
			 * and subtracts: job, name, then both, the last of which is this method's conjunctive
			 * answer (see the "for each Job X or Card Name Y" scaling sources in
			 * {@code ActionResolverChoose}). Making this OR would make that overlap term the union
			 * itself, and the inclusion-exclusion would undercount every time.
			 */
			@Override public int countP1FieldCards(boolean inclForwards, boolean inclBackups,
					boolean inclMonsters, String jobFilter, String cardNameFilter, String categoryFilter, String elementFilter, int costFilter) {
				int count = 0;
				if (inclForwards) for (CardData c : mw.p1ForwardCards) {
					if (!mw.meetsJobFilterEffective(c, jobFilter)) continue;
					if (!meetsCardNameFilter(c, cardNameFilter)) continue;
					if (!meetsCategoryFilter(c, categoryFilter)) continue;
					if (elementFilter != null && !mw.effectiveContainsElement(c, elementFilter)) continue;
					if (costFilter != -1 && c.cost() != costFilter) continue;
					count++;
				}
				if (inclBackups) for (CardData c : mw.p1BackupCards) {
					if (c == null) continue;
					if (!mw.meetsJobFilterEffective(c, jobFilter)) continue;
					if (!meetsCardNameFilter(c, cardNameFilter)) continue;
					if (!meetsCategoryFilter(c, categoryFilter)) continue;
					if (elementFilter != null && !mw.effectiveContainsElement(c, elementFilter)) continue;
					if (costFilter != -1 && c.cost() != costFilter) continue;
					count++;
				}
				if (inclMonsters) for (CardData c : mw.p1MonsterCards) {
					if (!mw.meetsJobFilterEffective(c, jobFilter)) continue;
					if (!meetsCardNameFilter(c, cardNameFilter)) continue;
					if (!meetsCategoryFilter(c, categoryFilter)) continue;
					if (elementFilter != null && !mw.effectiveContainsElement(c, elementFilter)) continue;
					if (costFilter != -1 && c.cost() != costFilter) continue;
					count++;
				}
				return count;
			}

			@Override public int countP1BreakZoneCards(String cardNameFilter, String jobFilter) {
				int count = 0;
				for (CardData c : mw.gameState.getP1BreakZone()) {
					if (!meetsCardNameFilter(c, cardNameFilter)) continue;
					if (!CardFilters.meetsJobFilter(c, jobFilter)) continue;
					count++;
				}
				return count;
			}

			@Override public int countP2BreakZoneCards(String cardNameFilter, String jobFilter) {
				int count = 0;
				for (CardData c : mw.gameState.getP2BreakZone()) {
					if (!meetsCardNameFilter(c, cardNameFilter)) continue;
					if (!CardFilters.meetsJobFilter(c, jobFilter)) continue;
					count++;
				}
				return count;
			}

			@Override public int countP1BreakZoneCardsByType(boolean inclForwards, boolean inclBackups,
					boolean inclMonsters, boolean inclSummons) {
				return countBreakZoneByType(mw.gameState.getP1BreakZone(), inclForwards, inclBackups, inclMonsters, inclSummons, null, -1);
			}

			@Override public int countP2BreakZoneCardsByType(boolean inclForwards, boolean inclBackups,
					boolean inclMonsters, boolean inclSummons) {
				return countBreakZoneByType(mw.gameState.getP2BreakZone(), inclForwards, inclBackups, inclMonsters, inclSummons, null, -1);
			}

			@Override public int countP1BreakZoneMatching(boolean inclForwards, boolean inclBackups,
					boolean inclMonsters, boolean inclSummons, String elementFilter, int maxCost) {
				return countBreakZoneByType(mw.gameState.getP1BreakZone(), inclForwards, inclBackups, inclMonsters, inclSummons, elementFilter, maxCost);
			}

			@Override public int countP2BreakZoneMatching(boolean inclForwards, boolean inclBackups,
					boolean inclMonsters, boolean inclSummons, String elementFilter, int maxCost) {
				return countBreakZoneByType(mw.gameState.getP2BreakZone(), inclForwards, inclBackups, inclMonsters, inclSummons, elementFilter, maxCost);
			}

			@Override public int countP1RfgCards(String cardNameFilter, String jobFilter) {
				int count = 0;
				for (CardData c : mw.gameState.getP1PermanentRfp()) {
					if (!meetsCardNameFilter(c, cardNameFilter)) continue;
					if (!CardFilters.meetsJobFilter(c, jobFilter)) continue;
					count++;
				}
				return count;
			}

			@Override public int countP2RfgCards(String cardNameFilter, String jobFilter) {
				int count = 0;
				for (CardData c : mw.gameState.getP2PermanentRfp()) {
					if (!meetsCardNameFilter(c, cardNameFilter)) continue;
					if (!CardFilters.meetsJobFilter(c, jobFilter)) continue;
					count++;
				}
				return count;
			}

			@Override public int countP2FieldCards(boolean inclForwards, boolean inclBackups,
					boolean inclMonsters, String jobFilter, String cardNameFilter) {
				return countP2FieldCards(inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, null);
			}

			@Override public int countP2FieldCards(boolean inclForwards, boolean inclBackups,
					boolean inclMonsters, String jobFilter, String cardNameFilter, String categoryFilter) {
				return countP2FieldCards(inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, null);
			}

			@Override public int countP2FieldCards(boolean inclForwards, boolean inclBackups,
					boolean inclMonsters, String jobFilter, String cardNameFilter, String categoryFilter, String elementFilter) {
				return countP2FieldCards(inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, elementFilter, -1);
			}

			/** Conjunctive for the reason {@link #countP1FieldCards} is — the overlap term. */
			@Override public int countP2FieldCards(boolean inclForwards, boolean inclBackups,
					boolean inclMonsters, String jobFilter, String cardNameFilter, String categoryFilter, String elementFilter, int costFilter) {
				int count = 0;
				if (inclForwards) for (CardData c : mw.p2ForwardCards) {
					if (!mw.meetsJobFilterEffective(c, jobFilter)) continue;
					if (!meetsCardNameFilter(c, cardNameFilter)) continue;
					if (!meetsCategoryFilter(c, categoryFilter)) continue;
					if (elementFilter != null && !mw.effectiveContainsElement(c, elementFilter)) continue;
					if (costFilter != -1 && c.cost() != costFilter) continue;
					count++;
				}
				if (inclBackups) for (CardData c : mw.p2BackupCards) {
					if (c == null) continue;
					if (!mw.meetsJobFilterEffective(c, jobFilter)) continue;
					if (!meetsCardNameFilter(c, cardNameFilter)) continue;
					if (!meetsCategoryFilter(c, categoryFilter)) continue;
					if (elementFilter != null && !mw.effectiveContainsElement(c, elementFilter)) continue;
					if (costFilter != -1 && c.cost() != costFilter) continue;
					count++;
				}
				if (inclMonsters) for (CardData c : mw.p2MonsterCards) {
					if (!mw.meetsJobFilterEffective(c, jobFilter)) continue;
					if (!meetsCardNameFilter(c, cardNameFilter)) continue;
					if (!meetsCategoryFilter(c, categoryFilter)) continue;
					if (elementFilter != null && !mw.effectiveContainsElement(c, elementFilter)) continue;
					if (costFilter != -1 && c.cost() != costFilter) continue;
					count++;
				}
				return count;
			}

			@Override public boolean controlConditionMet(ControlCondition cond) {
				return mw.controlConditionMet(cond, isP1);
			}

			@Override public boolean controlConditionMetExcluding(ControlCondition cond, String excludeName) {
				return mw.controlConditionMetExcluding(cond, excludeName, isP1);
			}

			@Override public boolean opponentControlsCard(String cardType, String cardCondition) {
				boolean oppIsP1 = !isP1;
				String norm = cardType == null ? null : cardType.toLowerCase().replaceAll("s$", "");
				if (norm == null || norm.equals("forward") || norm.equals("character")) {
					List<CardData>  fwds   = oppIsP1 ? mw.p1ForwardCards  : mw.p2ForwardCards;
					List<Integer>   dmg    = oppIsP1 ? mw.p1ForwardDamage  : mw.p2ForwardDamage;
					List<CardState> states = oppIsP1 ? mw.p1ForwardStates  : mw.p2ForwardStates;
					for (int i = 0; i < fwds.size(); i++) {
						int d = i < dmg.size()    ? dmg.get(i)    : 0;
						CardState s = i < states.size() ? states.get(i) : CardState.ACTIVE;
						if (CardFilters.meetsTargetCondition(s, d, false, false, cardCondition)) return true;
					}
				}
				if (norm == null || norm.equals("monster") || norm.equals("character")) {
					List<CardData>  mons   = oppIsP1 ? mw.p1MonsterCards  : mw.p2MonsterCards;
					List<CardState> states = oppIsP1 ? mw.p1MonsterStates : mw.p2MonsterStates;
					for (int i = 0; i < mons.size(); i++) {
						CardState s = i < states.size() ? states.get(i) : CardState.ACTIVE;
						if (CardFilters.meetsTargetCondition(s, 0, false, false, cardCondition)) return true;
					}
				}
				if (norm == null || norm.equals("backup") || norm.equals("character")) {
					CardData[] bkps = oppIsP1 ? mw.p1BackupCards : mw.p2BackupCards;
					for (CardData c : bkps) {
						if (c != null && CardFilters.meetsTargetCondition(CardState.ACTIVE, 0, false, false, cardCondition)) return true;
					}
				}
				return false;
			}

			@Override public int countOppFieldCardsWithCondition(boolean inclForwards, boolean inclBackups, boolean inclMonsters, String condition) {
				boolean oppIsP1 = !isP1;
				int count = 0;
				if (inclForwards) {
					List<CardData>  fwds   = oppIsP1 ? mw.p1ForwardCards  : mw.p2ForwardCards;
					List<Integer>   dmg    = oppIsP1 ? mw.p1ForwardDamage  : mw.p2ForwardDamage;
					List<CardState> states = oppIsP1 ? mw.p1ForwardStates  : mw.p2ForwardStates;
					for (int i = 0; i < fwds.size(); i++) {
						int d = i < dmg.size()    ? dmg.get(i)    : 0;
						CardState s = i < states.size() ? states.get(i) : CardState.ACTIVE;
						if (CardFilters.meetsTargetCondition(s, d, false, false, condition)) count++;
					}
				}
				if (inclMonsters) {
					List<CardData>  mons   = oppIsP1 ? mw.p1MonsterCards  : mw.p2MonsterCards;
					List<CardState> states = oppIsP1 ? mw.p1MonsterStates : mw.p2MonsterStates;
					for (int i = 0; i < mons.size(); i++) {
						CardState s = i < states.size() ? states.get(i) : CardState.ACTIVE;
						if (CardFilters.meetsTargetCondition(s, 0, false, false, condition)) count++;
					}
				}
				if (inclBackups) {
					CardData[] bkps = oppIsP1 ? mw.p1BackupCards : mw.p2BackupCards;
					for (CardData c : bkps) {
						if (c != null && CardFilters.meetsTargetCondition(CardState.ACTIVE, 0, false, false, condition)) count++;
					}
				}
				return count;
			}

			@Override public boolean selfReceivedDamageThisTurn() {
				return mw.turn(isP1).receivedDamageThisTurn;
			}

			@Override public boolean ownForwardFormedPartyThisTurn() {
				return mw.turn(isP1).formedPartyThisTurn;
			}

			@Override public int ownFieldCount(String cardType) {
				String t = cardType.toLowerCase().replaceAll("s$", "");
				List<CardData> fwds = isP1 ? mw.p1ForwardCards : mw.p2ForwardCards;
				CardData[]     bkps = isP1 ? mw.p1BackupCards  : mw.p2BackupCards;
				List<CardData> mons = isP1 ? mw.p1MonsterCards : mw.p2MonsterCards;
				int count = 0;
				if (t.equals("forward")   || t.equals("character")) count += fwds.size();
				if (t.equals("monster")   || t.equals("character")) count += mons.size();
				if (t.equals("backup")    || t.equals("character")) { for (CardData c : bkps) if (c != null) count++; }
				return count;
			}

			@Override public int ownFieldCountByCategory(String category, String type) {
				String t = type.toLowerCase().replaceAll("s$", "");
				List<CardData> all = new ArrayList<>();
				List<CardData> fwds = isP1 ? mw.p1ForwardCards : mw.p2ForwardCards;
				CardData[]     bkps = isP1 ? mw.p1BackupCards  : mw.p2BackupCards;
				List<CardData> mons = isP1 ? mw.p1MonsterCards : mw.p2MonsterCards;
				if (t.equals("forward")   || t.equals("character")) all.addAll(fwds);
				if (t.equals("monster")   || t.equals("character")) all.addAll(mons);
				if (t.equals("backup")    || t.equals("character")) { for (CardData c : bkps) if (c != null) all.add(c); }
				return (int) all.stream().filter(c -> meetsCategoryFilter(c, category)).count();
			}

			@Override public boolean selfHasSummonInBreakZone() {
				List<CardData> bz = isP1 ? mw.gameState.getP1BreakZone() : mw.gameState.getP2BreakZone();
				return bz.stream().anyMatch(CardData::isSummon);
			}

			@Override public int opponentDamageCount() {
				return (isP1 ? mw.gameState.getP2DamageZone() : mw.gameState.getP1DamageZone()).size();
			}

			@Override public int selfCardsCastThisTurn() { return mw.turn(isP1).cardsCastThisTurn; }

			@Override public int countCardsNamedCastThisTurn(String name) {
				Map<String, Integer> counts = mw.turn(isP1).castCountByNameThisTurn;
				return counts.getOrDefault(name.toLowerCase(java.util.Locale.ROOT), 0);
			}

			@Override public boolean selfSummonCastThisTurn() { return mw.turn(isP1).summonCastThisTurn; }

			@Override public int selfForwardCount() {
				return isP1 ? mw.p1ForwardCards.size() : mw.p2ForwardCards.size();
			}

			@Override public int opponentForwardCount() {
				return isP1 ? mw.p2ForwardCards.size() : mw.p1ForwardCards.size();
			}

			@Override public int selfFieldCount(String element, boolean inclForwards, boolean inclBackups, boolean inclMonsters) {
				List<CardData> fwds = isP1 ? mw.p1ForwardCards : mw.p2ForwardCards;
				CardData[]     bkps = isP1 ? mw.p1BackupCards  : mw.p2BackupCards;
				List<CardData> mons = isP1 ? mw.p1MonsterCards : mw.p2MonsterCards;
				int count = 0;
				if (inclForwards) for (CardData c : fwds) if (element == null || mw.effectiveContainsElement(c, element)) count++;
				if (inclBackups)  for (CardData c : bkps) if (c != null && (element == null || mw.effectiveContainsElement(c, element))) count++;
				if (inclMonsters) for (CardData c : mons) if (element == null || mw.effectiveContainsElement(c, element)) count++;
				return count;
			}

			@Override public int selfDistinctElementCount(boolean inclForwards, boolean inclBackups, boolean inclMonsters) {
				List<CardData> fwds = isP1 ? mw.p1ForwardCards : mw.p2ForwardCards;
				CardData[]     bkps = isP1 ? mw.p1BackupCards  : mw.p2BackupCards;
				List<CardData> mons = isP1 ? mw.p1MonsterCards : mw.p2MonsterCards;
				java.util.Set<String> elems = new java.util.HashSet<>();
				if (inclForwards) for (CardData c : fwds) for (String e : c.element().split("/")) elems.add(e);
				if (inclBackups)  for (CardData c : bkps) { if (c != null) for (String e : c.element().split("/")) elems.add(e); }
				if (inclMonsters) for (CardData c : mons) for (String e : c.element().split("/")) elems.add(e);
				return elems.size();
			}

			@Override public boolean isExBurst() { return exBurst; }
			@Override public boolean castWasPaidByBackupsOnly() { return mw.lastCastWasPaidByBackupsOnly; }
			@Override public boolean sourceEnteredViaWarp() { return mw.lastCardWarpedIn; }

			@Override public void makeMonsterTemporaryForward(CardData source, int power) {
				if (isP1) {
					int idx = mw.p1MonsterCards.indexOf(source);
					if (idx < 0) { mw.makeP1BackupTemporaryForward(source, power); return; }
					mw.p1MonsterTempForwardPower.put(source, power);
					mw.endOfTurnEffects.add(ctx -> {
						mw.p1MonsterTempForwardPower.remove(source);
						int stillIdx = mw.p1MonsterCards.indexOf(source);
						if (stillIdx >= 0) mw.refreshP1MonsterSlot(stillIdx);
					});
					mw.refreshP1MonsterSlot(idx);
				} else {
					int idx = mw.p2MonsterCards.indexOf(source);
					if (idx < 0) { mw.makeP2BackupTemporaryForward(source, power); return; }
					mw.p2MonsterTempForwardPower.put(source, power);
					mw.endOfTurnEffects.add(ctx -> {
						mw.p2MonsterTempForwardPower.remove(source);
						int stillIdx = mw.p2MonsterCards.indexOf(source);
						if (stillIdx >= 0) mw.refreshP2MonsterSlot(stillIdx);
					});
					mw.refreshP2MonsterSlot(idx);
				}
			}

			@Override public void makeTargetTemporaryForward(ForwardTarget t, int power) {
				if (t.zone() != ForwardTarget.CardZone.MONSTER) return;
				if (t.isP1()) {
					CardData card = mw.p1MonsterCards.get(t.idx());
					mw.p1MonsterTempForwardPower.put(card, power);
					logEntry(card.name() + " also becomes a Forward with " + power + " power until end of turn");
					mw.endOfTurnEffects.add(ctx -> {
						mw.p1MonsterTempForwardPower.remove(card);
						int stillIdx = mw.p1MonsterCards.indexOf(card);
						if (stillIdx >= 0) mw.refreshP1MonsterSlot(stillIdx);
					});
					mw.refreshP1MonsterSlot(t.idx());
				} else {
					CardData card = mw.p2MonsterCards.get(t.idx());
					mw.p2MonsterTempForwardPower.put(card, power);
					logEntry("[P2] " + card.name() + " also becomes a Forward with " + power + " power until end of turn");
					mw.endOfTurnEffects.add(ctx -> {
						mw.p2MonsterTempForwardPower.remove(card);
						int stillIdx = mw.p2MonsterCards.indexOf(card);
						if (stillIdx >= 0) mw.refreshP2MonsterSlot(stillIdx);
					});
					mw.refreshP2MonsterSlot(t.idx());
				}
			}

			@Override public void makeAllMonstersTemporaryForwards(int power) {
				List<CardData> monsters = isP1 ? mw.p1MonsterCards : mw.p2MonsterCards;
				Map<CardData, Integer> tempMap = isP1 ? mw.p1MonsterTempForwardPower : mw.p2MonsterTempForwardPower;
				for (int i = 0; i < monsters.size(); i++) {
					CardData card = monsters.get(i);
					tempMap.put(card, power);
					logEntry((isP1 ? "" : "[P2] ") + card.name() + " also becomes a Forward with " + power + " power until end of turn");
					final int idx = i;
					mw.endOfTurnEffects.add(ctx -> {
						tempMap.remove(card);
						int stillIdx = monsters.indexOf(card);
						if (stillIdx >= 0) {
							if (isP1) mw.refreshP1MonsterSlot(stillIdx);
							else      mw.refreshP2MonsterSlot(stillIdx);
						}
					});
					if (isP1) mw.refreshP1MonsterSlot(idx);
					else      mw.refreshP2MonsterSlot(idx);
				}
			}

			@Override public void grantTempBzActionAbility(CardData source, String bzCardName, String effectText) {
				ActionAbility ability = ActionAbility.makeBzCostTempAbility(bzCardName, effectText);
				Map<CardData, List<ActionAbility>> map = isP1 ? mw.p1TempGrantedAbilities : mw.p2TempGrantedAbilities;
				map.computeIfAbsent(source, k -> new ArrayList<>()).add(ability);
				mw.endOfTurnEffects.add(ctx -> {
					List<ActionAbility> list = map.get(source);
					if (list != null) { list.remove(ability); if (list.isEmpty()) map.remove(source); }
				});
				logEntry(source.name() + " gains: Put " + bzCardName + " into the Break Zone: " + effectText);
			}

			@Override public void grantCopiedSpecialAbilityFreeOnce(CardData source, ActionAbility original) {
				ActionAbility copy = new ActionAbility(
					original.abilityName(), false, false, 0, 0, false,
					List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
					false, false, true, false,
					null, null, false, false, false,
					original.effectText(),
					0, null, null, null, false, false, false, null, null, null, false, false, null, false, false, null, null, null, null, 0, null, -1, false, -1, null, null, null, false, false,
					original.requiresSelfPowerAtLeast()
				);
				Map<CardData, List<ActionAbility>> map = isP1 ? mw.p1TempGrantedAbilities : mw.p2TempGrantedAbilities;
				map.computeIfAbsent(source, k -> new ArrayList<>()).add(copy);
				mw.endOfTurnEffects.add(ctx -> {
					List<ActionAbility> list = map.get(source);
					if (list != null) { list.remove(copy); if (list.isEmpty()) map.remove(source); }
				});
				logEntry(source.name() + " gains " + original.abilityName() + " (free, once): " + original.effectText());
			}

			@Override public void useSpecialAbilityUsedThisTurn(CardData mimicSource, String excludedAbilityName) {
				List<UsedSpecialAbility> options = new ArrayList<>();
				for (UsedSpecialAbility u : mw.specialAbilitiesUsedThisTurn) {
					if (excludedAbilityName != null && !excludedAbilityName.isBlank()
							&& excludedAbilityName.equalsIgnoreCase(u.ability().abilityName())) continue;
					options.add(u);
				}
				if (options.isEmpty()) {
					logEntry(mimicSource.name() + " — Mimic: no special ability has been used this turn to copy");
					return;
				}
				UsedSpecialAbility chosen;
				if (isP1) {
					chosen = mw.chooseMimicSpecialAbility(options);
					if (chosen == null) { logEntry(mimicSource.name() + " — Mimic cancelled"); return; }
				} else {
					chosen = options.get(0); // AI: replay the earliest eligible special used this turn
				}
				// Substitute the mimicking card's name for the original user's where the effect names it.
				String substituted = ActionResolver.substituteSourceName(
						chosen.ability().effectText(), chosen.source().name(), mimicSource.name());
				String label = chosen.ability().abilityName().isEmpty()
						? "" : chosen.ability().abilityName() + " ";
				logEntry((isP1 ? "" : "[P2] ") + mimicSource.name() + " — Mimic uses "
						+ chosen.source().name() + "'s " + label + "→ " + substituted);
				Consumer<GameContext> eff = ActionResolver.parse(substituted, mimicSource);
				if (eff != null) eff.accept(this);
				else logEntry("[Mimic] Effect not implemented: " + substituted);
			}

			@Override public void swapDamageZoneCardWithHandCard(boolean drawCardBetween) {
				List<CardData> dz   = isP1 ? mw.gameState.getP1DamageZone() : mw.gameState.getP2DamageZone();
				List<CardData> hand = isP1 ? mw.gameState.getP1Hand()       : mw.gameState.getP2Hand();
				if (dz.isEmpty()) { logEntry("Damage Zone swap — no cards in Damage Zone"); return; }

				int dzIdx;
				if (isP1) {
					dzIdx = mw.showPickOneCardDialog(
							"Choose a card from your Damage Zone",
							"Pick 1 card to add to your hand.",
							dz, "Add to Hand", false);
				} else {
					int worst = 0, worstScore = Integer.MAX_VALUE;
					for (int i = 0; i < dz.size(); i++) {
						CardData c = dz.get(i);
						int score = c.cost() + (c.exBurst() ? -100 : 0);
						if (score < worstScore) { worstScore = score; worst = i; }
					}
					dzIdx = worst;
				}
				if (dzIdx < 0) { logEntry("Damage Zone swap — cancelled"); return; }

				CardData taken = dz.remove(dzIdx);
				hand.add(taken);
				logEntry((isP1 ? "" : "[P2] ") + "Adds " + taken.name() + " from Damage Zone to hand");
				mw.refreshDamageZoneSlots(isP1);
				if (isP1) mw.refreshP1HandLabel(); else mw.refreshP2HandCountLabel();

				if (drawCardBetween) drawCards(1);

				if (hand.isEmpty()) { logEntry("Damage Zone swap — hand empty, no card to return"); return; }

				int handIdx;
				if (isP1) {
					handIdx = mw.showPickOneCardDialog(
							"Choose a card from your hand",
							"Pick 1 card to put into your Damage Zone (its EX Burst will not trigger).",
							hand, "Put into Damage Zone", false);
					if (handIdx < 0) { logEntry("Damage Zone swap — cancelled at return step"); return; }
				} else {
					handIdx = MainWindow.pickWorstHandCard0(hand);
				}

				CardData returned = hand.remove(handIdx);
				dz.add(returned);
				logEntry((isP1 ? "" : "[P2] ") + "Puts " + returned.name()
						+ " from hand into Damage Zone (EX Burst suppressed)");
				mw.refreshDamageZoneSlots(isP1);
				if (isP1) mw.refreshP1HandLabel(); else mw.refreshP2HandCountLabel();

				mw.autoAbilityTriggers.triggerAutoAbilitiesForDamageZone(isP1);
			}

			@Override public void triggerExBurstFromDamageZone() {
				List<CardData> dmg = isP1 ? mw.gameState.getP1DamageZone() : mw.gameState.getP2DamageZone();
				List<CardData> eligible = new ArrayList<>();
				for (CardData c : dmg) {
					if (c.exBurst() && !c.exBurstEffect().isEmpty()) eligible.add(c);
				}
				if (eligible.isEmpty()) {
					logEntry("[EX Burst] No triggerable EX Burst cards in Damage Zone");
					return;
				}
				if (isP1) {
					CardData chosen = mw.showPickExBurstFromDamageZoneDialog(eligible);
					if (chosen == null) return;
					logEntry("[EX Burst] " + chosen.name() + " — placed on stack");
					mw.gameState.pushStack(new StackEntry(chosen, true, true));
					mw.showStackWindow();
				} else {
					CardData chosen = eligible.get(0);
					logEntry("[AI EX Burst] " + chosen.name() + " — placed on stack");
					mw.gameState.pushStack(new StackEntry(chosen, false, true));
					mw.showStackWindowIfNeeded();
				}
			}

			@Override public CardData targetCard(ForwardTarget t) {
				return t == null ? null : mw.autoAbilityTriggers.fieldCardData(t);
			}

			@Override public boolean selfControlsCard(CardData card) {
				if (card == null) return false;
				// Identity, not equals: a second copy of the same card is a different permanent.
				for (CardData c : isP1 ? mw.p1ForwardCards : mw.p2ForwardCards) if (c == card) return true;
				for (CardData c : isP1 ? mw.p1MonsterCards : mw.p2MonsterCards) if (c == card) return true;
				for (CardData b : isP1 ? mw.p1BackupCards  : mw.p2BackupCards)  if (b == card) return true;
				return false;
			}

			@Override public void breakSourceCard(CardData source) {
				List<CardData> fwds = isP1 ? mw.p1ForwardCards : mw.p2ForwardCards;
				for (int fi = 0; fi < fwds.size(); fi++) {
					if (fwds.get(fi) == source) {
						breakTarget(new ForwardTarget(isP1, fi, ForwardTarget.CardZone.FORWARD));
						return;
					}
				}
				List<CardData> mons = isP1 ? mw.p1MonsterCards : mw.p2MonsterCards;
				int mi = mons.indexOf(source);
				if (mi >= 0) breakTarget(new ForwardTarget(isP1, mi, ForwardTarget.CardZone.MONSTER));
			}

			@Override public void breakSourceAtEndOfTurn(CardData source) {
				addEndOfTurnEffect(ctx -> {
					List<CardData> fwds = isP1 ? mw.p1ForwardCards : mw.p2ForwardCards;
					for (int fi = 0; fi < fwds.size(); fi++) {
						if (fwds.get(fi) == source) {
							ctx.breakTarget(new ForwardTarget(isP1, fi, ForwardTarget.CardZone.FORWARD));
							return;
						}
					}
					List<CardData> mons = isP1 ? mw.p1MonsterCards : mw.p2MonsterCards;
					int mi = mons.indexOf(source);
					if (mi >= 0) ctx.breakTarget(new ForwardTarget(isP1, mi, ForwardTarget.CardZone.MONSTER));
				});
			}

			@Override public String selectJobFromDatabase() {
				// The AI's shortlist, not the human's: the dialog offers every Job in the database
				// either way. Both clients could derive it — it is drawn from the naming player's
				// own field — but only the client running the AI ever needs it.
				List<String> candidates = NameSelectionDialogs.collectFieldJobs(
						isP1 ? mw.p1ForwardCards : mw.p2ForwardCards,
						isP1 ? mw.p1BackupCards  : mw.p2BackupCards,
						isP1 ? mw.p1MonsterCards : mw.p2MonsterCards);
				return firstNamed(askToName("Waiting for your opponent to name a Job...",
						interactive -> NamedThing.of(NamedThing.Vocabulary.JOB,
								NameSelectionDialogs.selectJob(mw.frame, candidates, interactive,
										mw::logEntry))));
			}

			@Override public void grantJobUntilEndOfTurn(ForwardTarget t, String job) {
				if (t.zone() != ForwardTarget.CardZone.FORWARD) return;
				if (t.isP1()) {
					int idx = t.idx();
					if (idx < 0 || idx >= mw.p1ForwardCards.size()) return;
					mw.p1ForwardTempJobs.set(idx, job);
					logEntry(p1Forward(idx).name() + " gains the Job [" + job + "] until end of turn");
				} else {
					int idx = t.idx();
					if (idx < 0 || idx >= mw.p2ForwardCards.size()) return;
					mw.p2ForwardTempJobs.set(idx, job);
					logEntry("[P2] " + mw.p2ForwardCards.get(idx).name() + " gains the Job [" + job + "] until end of turn");
				}
			}

			@Override public String[] selectElementAndJob(String prompt, Set<String> excluded) {
				// One question naming two things, so both travel in one answer — an Element pair
				// followed by a Job pair.
				List<NamedThing> named = askToName(
						"Waiting for your opponent to name an Element and a Job...",
						interactive -> {
							String[] pair = NameSelectionDialogs.selectElementAndJob(
									mw.frame, prompt, excluded, interactive, mw::logEntry);
							if (pair == null || pair[0] == null || pair[1] == null) return List.of();
							return List.of(new NamedThing(NamedThing.Vocabulary.ELEMENT, pair[0]),
							               new NamedThing(NamedThing.Vocabulary.JOB,     pair[1]));
						});
				return named.size() < 2 ? null
						: new String[] { named.get(0).value(), named.get(1).value() };
			}

			@Override public String[] selectElementAndJob(String prompt) {
				return selectElementAndJob(prompt, Set.of());
			}

			@Override public void addCardJobPermanently(String cardName, String job) {
				for (boolean p1s : new boolean[]{true, false}) {
					List<CardData> fwds = p1s ? mw.p1ForwardCards : mw.p2ForwardCards;
					for (CardData c : fwds) {
						if (c.name().equalsIgnoreCase(cardName)) {
							mw.permanentExtraJobMap.put(c, job);
							logEntry("[Field] " + cardName + " gains permanent Job [" + job + "]");
							return;
						}
					}
					CardData[] bkps = p1s ? mw.p1BackupCards : mw.p2BackupCards;
					for (CardData c : bkps) {
						if (c != null && c.name().equalsIgnoreCase(cardName)) {
							mw.permanentExtraJobMap.put(c, job);
							logEntry("[Field] " + cardName + " gains permanent Job [" + job + "]");
							return;
						}
					}
				}
				logEntry("[Field] addCardJobPermanently: " + cardName + " not found");
			}

			@Override public String[] selectJobOrElement(String prompt) {
				return namedWithLabel(askToName("Waiting for your opponent to name a Job or Element...",
						interactive -> taggedPair(NameSelectionDialogs.selectJobOrElement(
								mw.frame, prompt, interactive, mw::logEntry))));
			}

			@Override public String[] selectJobOrCategory(String prompt) {
				return namedWithLabel(askToName("Waiting for your opponent to name a Job or Category...",
						interactive -> taggedPair(NameSelectionDialogs.selectJobOrCategory(
								mw.frame, prompt, interactive, mw::logEntry))));
			}

			/**
			 * Reads a {@code {"job"|"element"|"category", value}} answer from the dialogs into the
			 * form the wire takes. Which vocabulary was used is part of what the player decided
			 * here — these abilities let them pick the kind of thing to name, not just the thing.
			 */
			private List<NamedThing> taggedPair(String[] tagged) {
				if (tagged == null || tagged[0] == null || tagged[1] == null) return List.of();
				NamedThing.Vocabulary v = switch (tagged[0].toLowerCase()) {
					case "element"  -> NamedThing.Vocabulary.ELEMENT;
					case "category" -> NamedThing.Vocabulary.CATEGORY;
					default         -> NamedThing.Vocabulary.JOB;
				};
				return List.of(new NamedThing(v, tagged[1]));
			}

			/** Puts a named thing back into the {@code {label, value}} shape the callers expect. */
			private String[] namedWithLabel(List<NamedThing> named) {
				if (named.isEmpty()) return null;
				NamedThing t = named.get(0);
				return new String[] { t.vocabulary().name().toLowerCase(), t.value() };
			}

			@Override public void revealTopAddUpToMatchingRestBottom(int reveal, int maxAdd,
					String jobFilter, String categoryFilter, String cardNameFilter, String typeFilter, int maxCost,
					String elementFilter, String orElementFilter) {
				Deque<CardData> deck = isP1 ? mw.gameState.getP1MainDeck() : mw.gameState.getP2MainDeck();
				int n = Math.min(reveal, deck.size());
				if (n == 0) { logEntry("Reveal top: deck is empty."); return; }
				List<CardData> peeked = new ArrayList<>();
				for (CardData c : deck) { peeked.add(c); if (peeked.size() >= n) break; }
				logEntry("Reveal top " + n + " card(s): " +
						peeked.stream().map(CardData::name).collect(Collectors.joining(", ")));
				mw.lookDialogs().revealAddUpToMatchingRestBottom(peeked, deck, isP1, maxAdd,
						jobFilter, categoryFilter, cardNameFilter, typeFilter, maxCost,
						elementFilter, orElementFilter);
			}

			@Override public void revealTopNRemoveOneFromGameCastableThisTurnRestBottom(
					int reveal, String categoryFilter, int costReduction) {
				Deque<CardData> deck = isP1 ? mw.gameState.getP1MainDeck() : mw.gameState.getP2MainDeck();
				int n = Math.min(reveal, deck.size());
				if (n == 0) { logEntry("Reveal top: deck is empty."); return; }
				List<CardData> peeked = new ArrayList<>();
				for (CardData c : deck) { peeked.add(c); if (peeked.size() >= n) break; }
				logEntry("Reveal top " + n + " card(s): " +
						peeked.stream().map(CardData::name).collect(Collectors.joining(", ")));
				mw.lookDialogs().revealRemoveOneFromGameRestBottom(peeked, deck, isP1, categoryFilter,
						card -> {
							// The reveal has already lifted the card off the deck, so removing it is
							// only a matter of putting it in the removed-from-game zone; the leftovers
							// went to the bottom in the arrangement the player chose.
							mw.gameState.addToPermanentRfp(card);
							mw.registerBorrowedPlayable(isP1, card, new PlayableEntry(
									PlayableEntry.SourceZone.RFP, costReduction, false, false, false, true));
							logEntry((isP1 ? "" : "[P2] ") + card.name()
									+ " — castable from Removed From Game until end of turn"
									+ (costReduction > 0 ? " (cost -" + costReduction + ")" : ""));
						});
			}

			@Override public void revealTopAddUpToExcludingNameRestBz(int reveal, int maxAdd, String excludeName) {
				Deque<CardData> deck = isP1 ? mw.gameState.getP1MainDeck() : mw.gameState.getP2MainDeck();
				int n = Math.min(reveal, deck.size());
				if (n == 0) { logEntry("Reveal top: deck is empty."); return; }
				List<CardData> peeked = new ArrayList<>();
				for (CardData c : deck) { peeked.add(c); if (peeked.size() >= n) break; }
				logEntry("Reveal top " + n + " card(s): " +
						peeked.stream().map(CardData::name).collect(Collectors.joining(", ")));
				mw.lookDialogs().revealAddUpToExcludingNameRestBz(peeked, deck, isP1, maxAdd, excludeName);
			}

			private boolean meetsRevealTypeFilter(CardData c, String type) {
				return switch (type.toLowerCase()) {
					case "monster"   -> c.isMonster();
					case "forward"   -> c.isForward();
					case "backup"    -> c.isBackup();
					case "character" -> c.isForward() || c.isBackup() || c.isMonster();
					case "summon"    -> c.isSummon();
					default          -> false;
				};
			}

			@Override public void putSourceToBottomOfDeck(CardData source) {
				List<CardData> fwds = isP1 ? mw.p1ForwardCards : mw.p2ForwardCards;
				for (int i = fwds.size() - 1; i >= 0; i--) {
					if (fwds.get(i) == source) {
						logEntry("Effect: " + source.name() + " → bottom of its owner's deck");
						if (isP1) mw.returnP1ForwardToDeck(i, true);
						else      mw.returnP2ForwardToDeck(i, true);
						return;
					}
				}
				markEffectFizzled();
				logEntry("Effect: " + source.name() + " not found on field — fizzle");
			}

			/**
			 * Two printings reach this by different routes: Fiona 16-118C is still on the field
			 * when its "chosen by your opponent's Summons or abilities" trigger resolves, while
			 * Ewen 17-080R triggers on being put into the Break Zone and so is already there.
			 * The field is checked first, the Break Zone second; missing both is a real fizzle.
			 */
			@Override public void putSourceOnTopOfDeck(CardData source) {
				List<CardData> fwds = isP1 ? mw.p1ForwardCards : mw.p2ForwardCards;
				for (int i = fwds.size() - 1; i >= 0; i--) {
					if (fwds.get(i) == source) {
						logEntry("Effect: " + source.name() + " → top of its owner's deck");
						if (isP1) mw.returnP1ForwardToDeck(i, false);
						else      mw.returnP2ForwardToDeck(i, false);
						return;
					}
				}
				// The Break Zone the card actually landed in belongs to its owner, which control
				// transfer can make someone other than this context's player — so both are checked.
				for (int pass = 0; pass < 2; pass++) {
					boolean bzIsP1 = (pass == 0) == isP1;
					List<CardData> bz = bzIsP1 ? mw.gameState.getP1BreakZone() : mw.gameState.getP2BreakZone();
					int idx = indexByIdentity(bz, source);
					if (idx < 0) continue;
					bz.remove(idx);
					Boolean ownerIsP1 = mw.gameState.getIdentity().get(source);
					(Boolean.TRUE.equals(ownerIsP1) ? mw.gameState.getP1MainDeck()
					                                : mw.gameState.getP2MainDeck()).addFirst(source);
					logEntry("Effect: " + source.name() + " → Break Zone to top of its owner's deck");
					if (bzIsP1) mw.refreshP1BreakLabel(); else mw.refreshP2BreakLabel();
					if (Boolean.TRUE.equals(ownerIsP1)) mw.refreshP1DeckLabel(); else mw.refreshP2DeckLabel();
					return;
				}
				markEffectFizzled();
				logEntry("Effect: " + source.name() + " not found on field or in the Break Zone — fizzle");
			}

			@Override public void revealTopNPlayUpToTypeOntoFieldRestBottom(int reveal, int maxPlay, String typeFilter, String categoryFilter) {
				Deque<CardData> deck = isP1 ? mw.gameState.getP1MainDeck() : mw.gameState.getP2MainDeck();
				int n = Math.min(reveal, deck.size());
				if (n == 0) { logEntry("Reveal top: deck is empty."); return; }
				List<CardData> peeked = new ArrayList<>();
				for (CardData c : deck) { peeked.add(c); if (peeked.size() >= n) break; }
				logEntry("Reveal top " + n + " card(s): " +
						peeked.stream().map(CardData::name).collect(Collectors.joining(", ")));
				Consumer<CardData> playOntoField = revealPlacement();
				mw.lookDialogs().revealPlayTypeOntoFieldRestBottom(peeked, deck, isP1, maxPlay,
						typeFilter, categoryFilter, playOntoField);
			}

			@Override public void revealTopNPlayUpToElementTypeCostOntoField(int reveal, int maxPlay, String element, String typeFilter, int maxCost, RevealRest rest) {
				Deque<CardData> deck = isP1 ? mw.gameState.getP1MainDeck() : mw.gameState.getP2MainDeck();
				int n = Math.min(reveal, deck.size());
				if (n == 0) { logEntry("Reveal top: deck is empty."); return; }
				List<CardData> peeked = new ArrayList<>();
				for (CardData c : deck) { peeked.add(c); if (peeked.size() >= n) break; }
				logEntry("Reveal top " + n + " card(s): " +
						peeked.stream().map(CardData::name).collect(Collectors.joining(", ")));
				Consumer<CardData> playOntoField = revealPlacement();
				mw.lookDialogs().revealPlayElementTypeCostOntoField(peeked, deck, isP1, maxPlay,
						element, typeFilter, maxCost, rest, playOntoField);
			}

			@Override public void revealTopNPlayUpToNamedOrJobWithMaxCostOntoFieldRestBottom(
					int reveal, int maxPlay, String cardName, String job, int maxCost) {
				Deque<CardData> deck = isP1 ? mw.gameState.getP1MainDeck() : mw.gameState.getP2MainDeck();
				int n = Math.min(reveal, deck.size());
				if (n == 0) { logEntry("Reveal top: deck is empty."); return; }
				List<CardData> peeked = new ArrayList<>();
				for (CardData c : deck) { peeked.add(c); if (peeked.size() >= n) break; }
				logEntry("Reveal top " + n + " card(s): " +
						peeked.stream().map(CardData::name).collect(Collectors.joining(", ")));
				Consumer<CardData> playOntoField = revealPlacement();
				mw.lookDialogs().revealPlayNamedOrJobMaxCostOntoFieldRestBottom(peeked, deck, isP1,
						maxPlay, cardName, job, maxCost, playOntoField);
			}

			@Override public void revealTopNPlayTypeCostOrNamedCostOntoFieldRestBottom(
					int reveal, String typeFilter, int typeMaxCost, boolean excludeMultiElement,
					String cardName, int nameMaxCost) {
				Deque<CardData> deck = isP1 ? mw.gameState.getP1MainDeck() : mw.gameState.getP2MainDeck();
				int n = Math.min(reveal, deck.size());
				if (n == 0) { logEntry("Reveal top: deck is empty."); return; }
				List<CardData> peeked = new ArrayList<>();
				for (CardData c : deck) { peeked.add(c); if (peeked.size() >= n) break; }
				logEntry("Reveal top " + n + " card(s): " +
						peeked.stream().map(CardData::name).collect(Collectors.joining(", ")));
				Consumer<CardData> playOntoField = revealPlacement();
				mw.lookDialogs().revealPlayTypeCostOrNamedCostOntoFieldRestBottom(peeked, deck, isP1,
						typeFilter, typeMaxCost, excludeMultiElement, cardName, nameMaxCost, playOntoField);
			}

			@Override public void revealTopNAddTypeToHandOrPlayJobTypeOntoFieldRestBottom(
					int reveal, int handMax, String handType, int fieldMax, String fieldJob, String fieldType) {
				Deque<CardData> deck = isP1 ? mw.gameState.getP1MainDeck() : mw.gameState.getP2MainDeck();
				int n = Math.min(reveal, deck.size());
				if (n == 0) { logEntry("Reveal top: deck is empty."); return; }
				List<CardData> peeked = new ArrayList<>();
				for (CardData c : deck) { peeked.add(c); if (peeked.size() >= n) break; }
				logEntry("Reveal top " + n + " card(s): " +
						peeked.stream().map(CardData::name).collect(Collectors.joining(", ")));
				Consumer<CardData> playOntoField = revealPlacement();
				mw.lookDialogs().revealAddTypeToHandOrPlayJobTypeOntoFieldRestBottom(
						peeked, deck, isP1, handMax, handType, fieldMax, fieldJob, fieldType, playOntoField);
			}

			@Override public void revealTopNPlayNamedOntoFieldRestBottom(int reveal, String cardName) {
				// "Play 1 Card Name X …" with no cost cap — same effect as the cost-capped variant
				// with an unbounded cap.
				revealTopNPlayNamedWithMaxCostOntoFieldRestBottom(reveal, cardName, -1);
			}

			@Override public void revealTopNPlayNamedWithMaxCostOntoFieldRestBottom(int reveal, String cardName, int maxCost) {
				Deque<CardData> deck = isP1 ? mw.gameState.getP1MainDeck() : mw.gameState.getP2MainDeck();
				int n = Math.min(reveal, deck.size());
				if (n == 0) { logEntry("Reveal top: deck is empty."); return; }
				List<CardData> peeked = new ArrayList<>();
				for (CardData c : deck) { peeked.add(c); if (peeked.size() >= n) break; }
				logEntry("Reveal top " + n + " card(s): " +
						peeked.stream().map(CardData::name).collect(Collectors.joining(", ")));
				Consumer<CardData> playOntoField = revealPlacement();
				mw.lookDialogs().revealPlayNamedOntoFieldRestBottom(peeked, deck, isP1, cardName,
						maxCost, playOntoField);
			}

			@Override public void flipUntilTypeToHandRestShuffleBottom() {
				final String[] TYPES = {"Forward", "Backup", "Monster", "Summon"};
				Deque<CardData> deck = isP1 ? mw.gameState.getP1MainDeck() : mw.gameState.getP2MainDeck();
				String selectedType;
				if (!isP1) {
					selectedType = ComputerPlayer.pickMostCommonCardType(new ArrayList<>(deck));
					logEntry("[AI] selects card type: " + selectedType);
				} else {
					Object sel = javax.swing.JOptionPane.showInputDialog(mw.frame,
							"Select 1 card type:", "Select Card Type",
							javax.swing.JOptionPane.PLAIN_MESSAGE, null, TYPES, TYPES[0]);
					if (sel == null) { logEntry("Card type selection cancelled."); return; }
					selectedType = (String) sel;
					logEntry("Selected card type: " + selectedType);
				}
				List<CardData> revealed = new ArrayList<>();
				CardData found = null;
				while (!deck.isEmpty()) {
					CardData c = deck.pollFirst();
					String typeLabel = c.isForward() ? "Forward" : c.isBackup() ? "Backup"
							: c.isMonster() ? "Monster" : "Summon";
					logEntry("Revealed: " + c.name() + " [" + typeLabel + "]");
					if (ComputerPlayer.cardMatchesType(c, selectedType)) {
						found = c;
						break;
					}
					revealed.add(c);
				}
				List<CardData> hand = isP1 ? mw.gameState.getP1Hand() : mw.gameState.getP2Hand();
				if (found != null) {
					hand.add(found);
					logEntry(found.name() + " → hand");
					if (isP1) mw.refreshP1HandLabel(); else mw.refreshP2HandCountLabel();
				} else {
					logEntry("No " + selectedType + " found in deck — deck exhausted");
				}
				if (!revealed.isEmpty()) {
					java.util.Collections.shuffle(revealed);
					for (CardData c : revealed) deck.addLast(c);
					logEntry(revealed.size() + " revealed card(s) shuffled to bottom of deck");
				}
				if (isP1) mw.refreshP1DeckLabel(); else mw.refreshP2DeckLabel();
			}

			@Override public void flipUntilElementToHandRestShuffleBottom(String elem1, String elem2) {
				Deque<CardData> deck = isP1 ? mw.gameState.getP1MainDeck() : mw.gameState.getP2MainDeck();
				if (deck.isEmpty()) {
					// Not a loss: turning cards over is not drawing, so an empty deck just means
					// there is nothing to reveal.
					logEntry("Reveal from top: deck is empty — nothing revealed");
					return;
				}
				List<CardData> revealed = new ArrayList<>();
				CardData found = null;
				while (!deck.isEmpty()) {
					CardData c = deck.pollFirst();
					logEntry("Revealed: " + c.name() + " [" + c.element() + "]");
					// Multi-Element cards match on any of their Elements, which is what
					// meetsElementFilter's "|" list already means.
					if (meetsElementFilter(c, elem1 + "|" + elem2)) {
						found = c;
						break;
					}
					revealed.add(c);
				}
				if (found != null) {
					List<CardData> hand = isP1 ? mw.gameState.getP1Hand() : mw.gameState.getP2Hand();
					hand.add(found);
					logEntry(found.name() + " → hand");
					if (isP1) mw.refreshP1HandLabel(); else mw.refreshP2HandCountLabel();
				} else {
					logEntry("No " + elem1 + " or " + elem2 + " card found — deck exhausted, nothing added to hand");
				}
				if (!revealed.isEmpty()) {
					java.util.Collections.shuffle(revealed);
					for (CardData c : revealed) deck.addLast(c);
					logEntry(revealed.size() + " revealed card(s) shuffled to bottom of deck");
				}
				if (isP1) mw.refreshP1DeckLabel(); else mw.refreshP2DeckLabel();
			}

			@Override public void nameCardTypeOpponentDiscardDrawIfMatch() {
				final String[] TYPES = {"Forward", "Backup", "Monster", "Summon"};
				// Step 1: Name 1 card type
				String namedType;
				if (isP1) {
					Object sel = javax.swing.JOptionPane.showInputDialog(mw.frame,
							"Name 1 card type:", "Name a Card Type",
							javax.swing.JOptionPane.QUESTION_MESSAGE, null, TYPES, TYPES[0]);
					if (sel == null) { logEntry("Ability cancelled"); return; }
					namedType = (String) sel;
				} else {
					namedType = ComputerPlayer.pickMostCommonCardType(mw.gameState.getP1Hand());
				}
				logEntry((isP1 ? "" : "[P2] ") + "Names card type: " + namedType);

				// Step 2: Opponent discards 1 card
				CardData discarded = null;
				if (isP1) {
					// P2 CPU discards, avoiding the named type if possible
					List<CardData> hand = mw.gameState.getP2Hand();
					if (hand.isEmpty()) { logEntry("[P2] hand is empty — no card to discard"); return; }
					int idx = ComputerPlayer.pickWorstAvoidingType(hand, namedType);
					discarded = mw.playerBreakFromHand(false, idx);
					if (discarded != null) {
						logEntry("[P2] Discards " + discarded.name() + " (forced)");
						mw.p2Turn.discardedByEffectThisTurn = true;
						mw.p1Turn.causedOpponentDiscardThisTurn = true;
					}
					mw.refreshP2HandCountLabel();
					mw.refreshP2BreakLabel();
				} else {
					// P1 must choose a card to discard
					List<CardData> hand = mw.gameState.getP1Hand();
					if (hand.isEmpty()) { logEntry("P1 hand is empty — no card to discard"); return; }
					int idx = mw.showPickOneCardDialog("Discard 1 card",
							"Choose 1 card to discard.", hand, "Discard", false);
					if (idx < 0) { logEntry("Discard cancelled"); return; }
					discarded = mw.playerBreakFromHand(true, idx);
					if (discarded != null) {
						logEntry("[P1] Discards " + discarded.name() + " (forced)");
						mw.p1Turn.discardedByEffectThisTurn = true;
						mw.p2Turn.causedOpponentDiscardThisTurn = true;
					}
					mw.refreshP1HandLabel();
					mw.refreshP1BreakLabel();
				}

				// Step 3: Draw 1 if type matches
				if (discarded != null) {
					if (ComputerPlayer.cardMatchesType(discarded, namedType)) {
						logEntry((isP1 ? "" : "[P2] ") + discarded.name() + " is " + namedType + " — draw 1 card");
						drawCards(1);
					} else {
						logEntry(discarded.name() + " is not " + namedType + " — no draw");
					}
				}
			}

			@Override public void grantAllControlledForwardsJobUntilEOT(String job) {
				List<CardData> fwds     = isP1 ? mw.p1ForwardCards    : mw.p2ForwardCards;
				List<String>   tempJobs = isP1 ? mw.p1ForwardTempJobs : mw.p2ForwardTempJobs;
				String prefix = isP1 ? "" : "[P2] ";
				for (int i = 0; i < fwds.size(); i++) {
					if (i < tempJobs.size()) {
						tempJobs.set(i, job);
						logEntry(prefix + fwds.get(i).name() + " gains the Job [" + job + "] until end of turn");
					}
				}
			}

			@Override public void grantAllControlledForwardsElementUntilEOT(String element) {
				List<CardData> fwds   = isP1 ? mw.p1ForwardCards : mw.p2ForwardCards;
				String prefix = isP1 ? "" : "[P2] ";
				for (CardData c : fwds) {
					final String prev = mw.elementOverrideMap.get(c);
					mw.elementOverrideMap.put(c, element);
					mw.endOfTurnEffects.add(x -> {
						if (prev != null) mw.elementOverrideMap.put(c, prev);
						else              mw.elementOverrideMap.remove(c);
					});
					logEntry(prefix + c.name() + " → element becomes " + element + " until EOT");
				}
			}

			@Override public void changeSourceCardElementAndJobUntilEOT(CardData source, String element, String job) {
				for (boolean p1s : new boolean[]{true, false}) {
					List<CardData> fwds = p1s ? mw.p1ForwardCards : mw.p2ForwardCards;
					for (int i = 0; i < fwds.size(); i++) {
						if (fwds.get(i) != source) continue;
						final String prevElem = mw.elementOverrideMap.get(source);
						mw.elementOverrideMap.put(source, element);
						mw.endOfTurnEffects.add(x -> {
							if (prevElem != null) mw.elementOverrideMap.put(source, prevElem);
							else                  mw.elementOverrideMap.remove(source);
						});
						List<String> tempJobs = p1s ? mw.p1ForwardTempJobs : mw.p2ForwardTempJobs;
						final int idx = i;
						final String prevJob = idx < tempJobs.size() ? tempJobs.get(idx) : null;
						if (idx < tempJobs.size()) tempJobs.set(idx, job);
						mw.endOfTurnEffects.add(x -> { if (idx < tempJobs.size()) tempJobs.set(idx, prevJob); });
						logEntry(source.name() + " → becomes " + element + " element, Job [" + job + "] until end of turn");
						return;
					}
				}
				logEntry("[changeSourceCardElementAndJobUntilEOT] " + source.name() + " not found in forward slots");
			}

			@Override public void changeSourceCardElementUntilEOT(CardData source, String element) {
				for (boolean p1s : new boolean[]{true, false}) {
					List<CardData> fwds = p1s ? mw.p1ForwardCards : mw.p2ForwardCards;
					for (int i = 0; i < fwds.size(); i++) {
						if (fwds.get(i) != source) continue;
						final String prevElem = mw.elementOverrideMap.get(source);
						mw.elementOverrideMap.put(source, element);
						mw.endOfTurnEffects.add(x -> {
							if (prevElem != null) mw.elementOverrideMap.put(source, prevElem);
							else                  mw.elementOverrideMap.remove(source);
						});
						logEntry(source.name() + " → becomes " + element + " element until end of turn");
						return;
					}
				}
				logEntry("[changeSourceCardElementUntilEOT] " + source.name() + " not found in forward slots");
			}

			@Override public void grantForwardsPartyAnyElementThisTurn() {
				if (isP1) {
					mw.p1Turn.partyAnyElementThisTurn = true;
					mw.endOfTurnEffects.add(x -> mw.p1Turn.partyAnyElementThisTurn = false);
				} else {
					mw.p2Turn.partyAnyElementThisTurn = true;
					mw.endOfTurnEffects.add(x -> mw.p2Turn.partyAnyElementThisTurn = false);
				}
				logEntry((isP1 ? "P1" : "[P2]") + " Forwards can form a party with Forwards of any Element this turn");
			}

	/** Returns a display label like " Card(s)", " Forward(s)", " Character(s)", etc. for BZ-selection dialog titles. */
	static String breakZoneTypeLabel(boolean inclForwards, boolean inclBackups,
			boolean inclMonsters, boolean inclSummons, int count) {
		String s = count != 1 ? "s" : "";
		if (inclForwards && inclBackups && inclMonsters && inclSummons) return " Card" + s;
		if (inclSummons && !inclForwards && !inclBackups && !inclMonsters) return " Summon" + s;
		if (inclForwards && !inclBackups && !inclMonsters && !inclSummons) return " Forward" + s;
		if (inclBackups  && !inclForwards && !inclMonsters && !inclSummons) return " Backup" + s;
		if (inclMonsters && !inclForwards && !inclBackups  && !inclSummons) return " Monster" + s;
		return " Character" + s;
	}
}
