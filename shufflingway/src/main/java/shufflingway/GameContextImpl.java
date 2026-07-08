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
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.stream.Collectors;

import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JLayeredPane;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;

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

	GameContextImpl(MainWindow mw, boolean isP1, boolean exBurst) {
		this.mw = mw;
		this.isP1 = isP1;
		this.exBurst = exBurst;
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
				mult *= (isP1 ? mw.p1AbilityOutgoingDmgMult : mw.p2AbilityOutgoingDmgMult);
				int flat = mw.outgoingDmgFlatBoostMap.getOrDefault(mw.currentAbilitySource, 0);
				return amount * mult + flat;
			}

			private int applyOutgoingFieldAbilityMult(int amount, CardData target) {
				if (mw.currentAbilitySource == null) return amount;
				for (FieldAbility fa : mw.currentAbilitySource.fieldAbilities()) {
					Matcher m = AutoAbilityTriggers.FA_DOUBLE_DAMAGE_VS_COST_THRESHOLD.matcher(fa.effectText());
					if (m.find() && m.group("name").trim().equalsIgnoreCase(mw.currentAbilitySource.name())
							&& target.cost() >= Integer.parseInt(m.group("cost")))
						amount *= 2;
					Matcher m2 = AutoAbilityTriggers.FA_DOUBLE_ABILITY_DAMAGE.matcher(fa.effectText());
					if (m2.find() && m2.group("name").trim().equalsIgnoreCase(mw.currentAbilitySource.name()))
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

			@Override public void doubleOpponentForwardIncomingDamage() {
				if (isP1) {
					mw.p2ForwardIncomingDmgMult *= 2;
					logEntry("Opponent's Forwards — incoming damage ×" + mw.p2ForwardIncomingDmgMult + " until end of turn");
				} else {
					mw.p1ForwardIncomingDmgMult *= 2;
					logEntry("Opponent's Forwards — incoming damage ×" + mw.p1ForwardIncomingDmgMult + " until end of turn");
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
					mw.p1AbilityOutgoingDmgMult *= 2;
					logEntry("P1 abilities — outgoing damage ×" + mw.p1AbilityOutgoingDmgMult + " until end of turn");
				} else {
					mw.p2AbilityOutgoingDmgMult *= 2;
					logEntry("P2 abilities — outgoing damage ×" + mw.p2AbilityOutgoingDmgMult + " until end of turn");
				}
			}
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
			@Override public void shieldNextAbilityIncomingDamageReduction(ForwardTarget t, int reduction) {
				CardData c = mw.autoAbilityTriggers.fieldCardData(t); if (c != null) mw.nextAbilityDmgReduceMap.merge(c, reduction, Integer::sum);
			}
			@Override public void debuffIncomingDamageIncrease(ForwardTarget t, int amount) {
				CardData c = mw.autoAbilityTriggers.fieldCardData(t); if (c != null) mw.incomingDmgIncreaseMap.merge(c, amount, Integer::sum);
			}
			@Override public void shieldAbilityDamage(ForwardTarget t) {
				CardData c = mw.autoAbilityTriggers.fieldCardData(t); if (c != null) mw.nullifyAbilityDmgSet.add(c);
			}
			@Override public void shieldAbilityOnlyDamage(ForwardTarget t) {
				CardData c = mw.autoAbilityTriggers.fieldCardData(t); if (c != null) mw.nullifyAbilityOnlyDmgSet.add(c);
			}
			@Override public void shieldNonLethal(ForwardTarget t) {
				CardData c = mw.autoAbilityTriggers.fieldCardData(t); if (c != null) mw.perCardNonLethalDmgSet.add(c);
			}
			@Override public void shieldPlayerNextDamage() {
				if (isP1) { mw.p1NextDamageZero = true; if (mw.p1ShieldIcon != null) mw.p1ShieldIcon.reset(); }
				else       { mw.p2NextDamageZero = true; if (mw.p2ShieldIcon != null) mw.p2ShieldIcon.reset(); }
			}
			@Override public void disableOpponentDamageReduction() {
				if (isP1) mw.p2DmgReductionDisabled = true; else mw.p1DmgReductionDisabled = true;
			}
			@Override public void shieldNextOutgoingDamage(ForwardTarget t) {
				CardData c = mw.autoAbilityTriggers.fieldCardData(t); if (c != null) mw.nextOutgoingDmgZeroSet.add(c);
			}
			@Override public void shieldActivePlayerNonLethal() {
				if (isP1) mw.p1NonLethalProtection = true; else mw.p2NonLethalProtection = true;
			}
			@Override public void shieldActivePlayerDamageReduction(int reduction) {
				if (isP1) mw.p1GlobalDmgReduction += reduction; else mw.p2GlobalDmgReduction += reduction;
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

			@Override public String selectElement(String prompt) {
				return NameSelectionDialogs.selectElement(mw.frame, prompt, isP1, mw::logEntry);
			}

			@Override public String selectElement(String prompt, Set<String> excluded) {
				return NameSelectionDialogs.selectElement(mw.frame, prompt, excluded, isP1, mw::logEntry);
			}

			@Override public String selectOption(String prompt, String[] choices) {
				if (!isP1) {
					String picked = choices[(int)(Math.random() * choices.length)];
					logEntry("[AI] chose: " + picked);
					return picked;
				}
				return (String) javax.swing.JOptionPane.showInputDialog(
						mw.frame, prompt, "Choose",
						javax.swing.JOptionPane.PLAIN_MESSAGE,
						null, choices, choices[0]);
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
				// Only supported for P1 stealing from P2 in the current implementation
				if (!isP1 || t.isP1() || t.zone() != ForwardTarget.CardZone.FORWARD) return;
				mw.stealForwardFromP2ToP1(t.idx(), condition, activate);
			}

			@Override
			public List<ForwardTarget> selectCharacters(
					int maxCount, boolean upTo, boolean opponentOnly,
					boolean selfOnly, String condition, String element,
					int costVal, String costCmp, int powerVal, String powerCmp,
					boolean inclForwards, boolean inclBackups, boolean inclMonsters,
					String jobFilter, String cardNameFilter, String categoryFilter, String excludeName, boolean inclSummons,
					String excludeElement, boolean withoutMulticard) {
				List<ForwardTarget> eligible = new ArrayList<>();
				// Build symmetric "cannot be chosen" sets — checked in all four targeting quadrants.
				// summonImmuneAnyone: blocked from Summon targeting regardless of which player casts.
				// abilityImmuneAnyone: blocked from ability targeting regardless of which player uses.
				// Sources: turn-scoped shields (action abilities), standalone field abilities,
				// conditional IfControlBoost grants, and element-based immunity.
				CardData resCard = mw.currentResolutionIsSummon ? mw.currentSummonSource : mw.currentAbilitySource;
				List<String> resElems = (resCard != null) ? mw.effectiveElements(resCard) : List.of();
				final Set<CardData> summonImmuneAnyone;
				final Set<CardData> abilityImmuneAnyone;
				{
					Set<CardData> sumTmp = new HashSet<>(mw.cannotBeChosenBySummonsAnyone);
					Set<CardData> ablTmp = new HashSet<>();
					// Rubicante-style: "cannot be chosen by [element] Summons/abilities this turn"
					for (java.util.Map.Entry<CardData, String> e : mw.cannotBeChosenByElement.entrySet()) {
						if (resElems.contains(e.getValue())) { sumTmp.add(e.getKey()); ablTmp.add(e.getKey()); }
					}
					for (boolean p1side : new boolean[]{true, false}) {
						List<CardData> fwds = p1side ? mw.p1ForwardCards : mw.p2ForwardCards;
						CardData[]     bkps = p1side ? mw.p1BackupCards  : mw.p2BackupCards;
						List<CardData> mons = p1side ? mw.p1MonsterCards : mw.p2MonsterCards;
						for (CardData c : fwds) {
							if (ActionResolver.hasCannotBeChosenByAnySummonFieldAbility(c)) sumTmp.add(c);
							if (ActionResolver.hasCannotBeChosenByOwnElementFieldAbility(c)) {
								String ce = mw.effectiveElement(c);
								if (ce != null && resElems.contains(ce)) { sumTmp.add(c); ablTmp.add(c); }
							}
							if (mw.icbGrantsImmunity(c.name(), p1side, true))  sumTmp.add(c);
							if (mw.icbGrantsImmunity(c.name(), p1side, false)) ablTmp.add(c);
						}
						for (CardData c : bkps) {
							if (c == null) continue;
							if (ActionResolver.hasCannotBeChosenByAnySummonFieldAbility(c)) sumTmp.add(c);
							if (ActionResolver.hasCannotBeChosenByOwnElementFieldAbility(c)) {
								String ce = mw.effectiveElement(c);
								if (ce != null && resElems.contains(ce)) { sumTmp.add(c); ablTmp.add(c); }
							}
							if (mw.icbGrantsImmunity(c.name(), p1side, true))  sumTmp.add(c);
							if (mw.icbGrantsImmunity(c.name(), p1side, false)) ablTmp.add(c);
						}
						for (CardData c : mons) {
							if (ActionResolver.hasCannotBeChosenByAnySummonFieldAbility(c)) sumTmp.add(c);
							if (ActionResolver.hasCannotBeChosenByOwnElementFieldAbility(c)) {
								String ce = mw.effectiveElement(c);
								if (ce != null && resElems.contains(ce)) { sumTmp.add(c); ablTmp.add(c); }
							}
							if (mw.icbGrantsImmunity(c.name(), p1side, true))  sumTmp.add(c);
							if (mw.icbGrantsImmunity(c.name(), p1side, false)) ablTmp.add(c);
						}
					}
					summonImmuneAnyone  = sumTmp;
					abilityImmuneAnyone = ablTmp;
				}
				// Unified set for this resolution: whichever immunity type applies.
				// Used in all four targeting quadrants with no per-site condition check needed.
				final Set<CardData> immuneAnyone = mw.currentResolutionIsSummon ? summonImmuneAnyone : abilityImmuneAnyone;
				// "own" = cards belonging to effect controller; "opp" = other player's cards.
				// isP1 captures the controller's perspective, so the two blocks below must
				// flip which physical side they iterate when isP1 is false (P2 controls).
				if (!opponentOnly) {
					if (isP1) {
						if (inclForwards || inclMonsters) for (int i = 0; i < mw.p1ForwardCards.size(); i++) {
							CardData card = p1Forward(i);
							if (!inclForwards && !card.alsoCountsAsMonster()) continue;
							if (immuneAnyone.contains(card)) continue;
							if (element != null && !card.containsElement(element)) continue;
							if (!meetsElementExclusion(card, excludeElement)) continue;
							if (!meetsCostConstraint(card.cost(), costVal, costCmp)) continue;
							if (!meetsPowerConstraint(card.power(), powerVal, powerCmp)) continue;
							if (!mw.meetsJobFilterEffective(card, jobFilter, mw.p1ForwardCards)) continue;
							if (!meetsCardNameFilter(card, cardNameFilter)) continue;
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
							if (element != null && !mw.p1BackupCards[i].containsElement(element)) continue;
							if (!meetsCostConstraint(mw.p1BackupCards[i].cost(), costVal, costCmp)) continue;
							if (!meetsPowerConstraint(mw.p1BackupCards[i].power(), powerVal, powerCmp)) continue;
							if (!mw.meetsJobFilterEffective(mw.p1BackupCards[i], jobFilter, mw.p1ForwardCards)) continue;
							if (!meetsCardNameFilter(mw.p1BackupCards[i], cardNameFilter)) continue;
							if (!meetsCategoryFilter(mw.p1BackupCards[i], categoryFilter)) continue;
							if (excludeName != null && excludeName.equalsIgnoreCase(mw.p1BackupCards[i].name())) continue;
							if (withoutMulticard && mw.p1BackupCards[i].multicard()) continue;
							if (meetsTargetCondition(mw.p1BackupStates[i], 0, false, false, condition))
								eligible.add(new ForwardTarget(true, i, ForwardTarget.CardZone.BACKUP));
						}
						if (inclMonsters || inclForwards) for (int i = 0; i < mw.p1MonsterCards.size(); i++) {
							if (!inclMonsters && !mw.isP1MonsterTemporarilyForward(i)) continue;
							CardData card = mw.p1MonsterCards.get(i);
							if (immuneAnyone.contains(card)) continue;
							if (element != null && !card.containsElement(element)) continue;
							if (!meetsElementExclusion(card, excludeElement)) continue;
							if (!meetsCostConstraint(card.cost(), costVal, costCmp)) continue;
							if (!meetsPowerConstraint(card.power(), powerVal, powerCmp)) continue;
							if (!mw.meetsJobFilterEffective(card, jobFilter, mw.p1ForwardCards)) continue;
							if (!meetsCardNameFilter(card, cardNameFilter)) continue;
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
							if (immuneAnyone.contains(card)) continue;
							if (element != null && !card.containsElement(element)) continue;
							if (!meetsElementExclusion(card, excludeElement)) continue;
							if (!meetsCostConstraint(card.cost(), costVal, costCmp)) continue;
							if (!meetsPowerConstraint(card.power(), powerVal, powerCmp)) continue;
							if (!mw.meetsJobFilterEffective(card, jobFilter, mw.p2ForwardCards)) continue;
							if (!meetsCardNameFilter(card, cardNameFilter)) continue;
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
							if (immuneAnyone.contains(mw.p2BackupCards[i])) continue;
							if (element != null && !mw.p2BackupCards[i].containsElement(element)) continue;
							if (!meetsCostConstraint(mw.p2BackupCards[i].cost(), costVal, costCmp)) continue;
							if (!meetsPowerConstraint(mw.p2BackupCards[i].power(), powerVal, powerCmp)) continue;
							if (!mw.meetsJobFilterEffective(mw.p2BackupCards[i], jobFilter, mw.p2ForwardCards)) continue;
							if (!meetsCardNameFilter(mw.p2BackupCards[i], cardNameFilter)) continue;
							if (!meetsCategoryFilter(mw.p2BackupCards[i], categoryFilter)) continue;
							if (excludeName != null && excludeName.equalsIgnoreCase(mw.p2BackupCards[i].name())) continue;
							if (withoutMulticard && mw.p2BackupCards[i].multicard()) continue;
							if (meetsTargetCondition(mw.p2BackupStates[i], 0, false, false, condition))
								eligible.add(new ForwardTarget(false, i, ForwardTarget.CardZone.BACKUP));
						}
						if (inclMonsters || inclForwards) for (int i = 0; i < mw.p2MonsterCards.size(); i++) {
							if (!inclMonsters && !mw.isP2MonsterTemporarilyForward(i)) continue;
							CardData card = mw.p2MonsterCards.get(i);
							if (immuneAnyone.contains(card)) continue;
							if (element != null && !card.containsElement(element)) continue;
							if (!meetsElementExclusion(card, excludeElement)) continue;
							if (!meetsCostConstraint(card.cost(), costVal, costCmp)) continue;
							if (!meetsPowerConstraint(card.power(), powerVal, powerCmp)) continue;
							if (!mw.meetsJobFilterEffective(card, jobFilter, mw.p2ForwardCards)) continue;
							if (!meetsCardNameFilter(card, cardNameFilter)) continue;
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
							if (immuneAnyone.contains(card)) continue;
							if (element != null && !card.containsElement(element)) continue;
							if (!meetsElementExclusion(card, excludeElement)) continue;
							if (!meetsCostConstraint(card.cost(), costVal, costCmp)) continue;
							if (!meetsPowerConstraint(card.power(), powerVal, powerCmp)) continue;
							if (!mw.meetsJobFilterEffective(card, jobFilter, mw.p2ForwardCards)) continue;
							if (!meetsCardNameFilter(card, cardNameFilter)) continue;
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
							if (immuneAnyone.contains(mw.p2BackupCards[i])) continue;
							if (element != null && !mw.p2BackupCards[i].containsElement(element)) continue;
							if (!meetsCostConstraint(mw.p2BackupCards[i].cost(), costVal, costCmp)) continue;
							if (!meetsPowerConstraint(mw.p2BackupCards[i].power(), powerVal, powerCmp)) continue;
							if (!mw.meetsJobFilterEffective(mw.p2BackupCards[i], jobFilter, mw.p2ForwardCards)) continue;
							if (!meetsCardNameFilter(mw.p2BackupCards[i], cardNameFilter)) continue;
							if (!meetsCategoryFilter(mw.p2BackupCards[i], categoryFilter)) continue;
							if (excludeName != null && excludeName.equalsIgnoreCase(mw.p2BackupCards[i].name())) continue;
							if (withoutMulticard && mw.p2BackupCards[i].multicard()) continue;
							if (meetsTargetCondition(mw.p2BackupStates[i], 0, false, false, condition))
								eligible.add(new ForwardTarget(false, i, ForwardTarget.CardZone.BACKUP));
						}
						if (inclMonsters || inclForwards) for (int i = 0; i < mw.p2MonsterCards.size(); i++) {
							if (!inclMonsters && !mw.isP2MonsterTemporarilyForward(i)) continue;
							CardData card = mw.p2MonsterCards.get(i);
							if (immuneAnyone.contains(card)) continue;
							if (element != null && !card.containsElement(element)) continue;
							if (!meetsElementExclusion(card, excludeElement)) continue;
							if (!meetsCostConstraint(card.cost(), costVal, costCmp)) continue;
							if (!meetsPowerConstraint(card.power(), powerVal, powerCmp)) continue;
							if (!mw.meetsJobFilterEffective(card, jobFilter, mw.p2ForwardCards)) continue;
							if (!meetsCardNameFilter(card, cardNameFilter)) continue;
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
						Set<CardData> noChoose = mw.currentResolutionIsSummon ? mw.cannotBeChosenBySummons : mw.cannotBeChosenByAbilities;
						if (inclForwards) for (int i = 0; i < mw.p1ForwardCards.size(); i++) {
							CardData card = p1Forward(i);
							if (noChoose.contains(card)) continue;
							if (immuneAnyone.contains(card)) continue;
							if (element != null && !card.containsElement(element)) continue;
							if (!meetsElementExclusion(card, excludeElement)) continue;
							if (!meetsCostConstraint(card.cost(), costVal, costCmp)) continue;
							if (!meetsPowerConstraint(card.power(), powerVal, powerCmp)) continue;
							if (!mw.meetsJobFilterEffective(card, jobFilter, mw.p1ForwardCards)) continue;
							if (!meetsCardNameFilter(card, cardNameFilter)) continue;
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
							if (noChoose.contains(mw.p1BackupCards[i])) continue;
							if (immuneAnyone.contains(mw.p1BackupCards[i])) continue;
							if (element != null && !mw.p1BackupCards[i].containsElement(element)) continue;
							if (!meetsCostConstraint(mw.p1BackupCards[i].cost(), costVal, costCmp)) continue;
							if (!meetsPowerConstraint(mw.p1BackupCards[i].power(), powerVal, powerCmp)) continue;
							if (!mw.meetsJobFilterEffective(mw.p1BackupCards[i], jobFilter, mw.p1ForwardCards)) continue;
							if (!meetsCardNameFilter(mw.p1BackupCards[i], cardNameFilter)) continue;
							if (!meetsCategoryFilter(mw.p1BackupCards[i], categoryFilter)) continue;
							if (excludeName != null && excludeName.equalsIgnoreCase(mw.p1BackupCards[i].name())) continue;
							if (withoutMulticard && mw.p1BackupCards[i].multicard()) continue;
							if (meetsTargetCondition(mw.p1BackupStates[i], 0, false, false, condition))
								eligible.add(new ForwardTarget(true, i, ForwardTarget.CardZone.BACKUP));
						}
						if (inclMonsters || inclForwards) for (int i = 0; i < mw.p1MonsterCards.size(); i++) {
							if (!inclMonsters && !mw.isP1MonsterTemporarilyForward(i)) continue;
							CardData card = mw.p1MonsterCards.get(i);
							if (noChoose.contains(card)) continue;
							if (immuneAnyone.contains(card)) continue;
							if (element != null && !card.containsElement(element)) continue;
							if (!meetsElementExclusion(card, excludeElement)) continue;
							if (!meetsCostConstraint(card.cost(), costVal, costCmp)) continue;
							if (!meetsPowerConstraint(card.power(), powerVal, powerCmp)) continue;
							if (!mw.meetsJobFilterEffective(card, jobFilter)) continue;
							if (!meetsCardNameFilter(card, cardNameFilter)) continue;
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
				String title = "Choose " + (upTo ? "up to " : "") + maxCount
						+ preCondLabel
						+ (element != null ? " " + element : "")
						+ " " + targetNoun + (maxCount != 1 ? "s" : "") + postCondLabel + costLabel + powerLabel
						+ (opponentOnly ? " (opponent)" : selfOnly ? " (yours)" : "");
				if (!isP1) {
					// AI (P2 controls the effect): auto-select rather than prompting the human.
					if (eligible.isEmpty()) return List.of();
					// For unqualified targeting, prefer opponent (P1) targets over own cards.
					List<ForwardTarget> pool = eligible;
					if (!opponentOnly && !selfOnly) {
						List<ForwardTarget> oppTargets = eligible.stream()
								.filter(ForwardTarget::isP1).toList();
						if (!oppTargets.isEmpty()) pool = oppTargets;
					}
					List<ForwardTarget> copy = new ArrayList<>(pool);
					java.util.Collections.shuffle(copy);
					List<ForwardTarget> picked = List.copyOf(copy.subList(0, Math.min(maxCount, copy.size())));
					picked.forEach(t -> {
						CardData c = switch (t.zone()) {
							case BACKUP  -> t.isP1() ? mw.p1BackupCards[t.idx()] : mw.p2BackupCards[t.idx()];
							case MONSTER -> t.isP1() ? mw.p1MonsterCards.get(t.idx()) : mw.p2MonsterCards.get(t.idx());
							default      -> t.isP1() ? p1Forward(t.idx()) : mw.p2ForwardCards.get(t.idx());
						};
						logEntry("[AI] chose " + c.name());
					});
					if (mw.currentResolutionIsSummon)
						for (ForwardTarget t : picked)
							if (t.zone() == ForwardTarget.CardZone.FORWARD && t.isP1() != isP1)
								mw.autoAbilityTriggers.triggerAutoAbilitiesForChosenByOpponentSummon(t.isP1());
					return picked;
				}
				List<ForwardTarget> chosen = mw.showForwardSelectDialog(eligible, maxCount, upTo, title);
				if (mw.currentResolutionIsSummon)
					for (ForwardTarget t : chosen)
						if (t.zone() == ForwardTarget.CardZone.FORWARD && t.isP1() != isP1)
							mw.autoAbilityTriggers.triggerAutoAbilitiesForChosenByOpponentSummon(t.isP1());
				return chosen;
			}

			@Override public void dullP1Forward(int idx) {
				if (idx >= mw.p1ForwardStates.size()) return;
				CardData c = p1Forward(idx);
				if (!isP1 && (ActionResolver.hasCannotBeDulledByOppFieldAbility(c)
						|| mw.effectiveP1HasTrait(idx, CardData.Trait.CANNOT_BE_DULLED_BY_OPP))) {
					logEntry(c.name() + " cannot become dull by opponent's effects");
					return;
				}
				CardState before = mw.p1ForwardStates.get(idx);
				mw.p1ForwardStates.set(idx, CardState.DULL);
				logEntry(c.name() + " is dulled");
				mw.animateDullForward(idx, null);
				if (before == CardState.ACTIVE)
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
				CardState before = mw.p2ForwardStates.get(idx);
				mw.p2ForwardStates.set(idx, CardState.DULL);
				logEntry("[P2] " + c.name() + " is dulled");
				mw.animateDullP2Forward(idx, null);
				if (before == CardState.ACTIVE)
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

			@Override public void setP1ForwardCannotBlock(int idx) {
				if (idx >= 0 && idx < mw.p1ForwardCards.size()) mw.p1ForwardCannotBlock.add(idx);
			}
			@Override public void setP2ForwardCannotBlock(int idx) {
				if (idx >= 0 && idx < mw.p2ForwardCards.size()) mw.p2ForwardCannotBlock.add(idx);
			}
			@Override public void setP1ForwardCannotBeBlocked(int idx) {
				if (idx >= 0 && idx < mw.p1ForwardCards.size()) mw.p1ForwardCannotBeBlocked.add(idx);
			}
			@Override public void setP2ForwardCannotBeBlocked(int idx) {
				if (idx >= 0 && idx < mw.p2ForwardCards.size()) mw.p2ForwardCannotBeBlocked.add(idx);
			}
			@Override public void setP1ForwardCannotBeBlockedByCost(int idx, int costVal, boolean isMore) {
				if (idx >= 0 && idx < mw.p1ForwardCards.size())
					mw.p1ForwardCannotBeBlockedByCost.put(idx, new int[]{costVal, isMore ? 1 : 0});
			}
			@Override public void setP2ForwardCannotBeBlockedByCost(int idx, int costVal, boolean isMore) {
				if (idx >= 0 && idx < mw.p2ForwardCards.size())
					mw.p2ForwardCannotBeBlockedByCost.put(idx, new int[]{costVal, isMore ? 1 : 0});
			}
			@Override public void setOppForwardsCannotBlockInferiorPowerThisTurn() {
				if (isP1()) mw.p2ForwardCannotBlockInferiorPower = true;
				else        mw.p1ForwardCannotBlockInferiorPower = true;
				logEntry("Effect: Opponent Forwards cannot block Forwards with power inferior to their own this turn");
			}
			@Override public void setAllForwardsCannotBeBlockedByHigherCostThisTurn() {
				mw.allForwardsCannotBeBlockedByHigherCostThisTurn = true;
			}
			@Override public void setOppFwdPowerBoostSuppressedThisTurn() {
				if (isP1()) mw.p2FwdBoostSuppressedThisTurn = true;
				else        mw.p1FwdBoostSuppressedThisTurn = true;
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
			@Override public boolean wasElementCpPaid(String element) {
				return element != null && mw.lastCastPaymentElements.stream()
						.anyMatch(e -> e.equalsIgnoreCase(element));
			}
			@Override public void setP1ForwardMustBlock(int idx) {
				if (idx >= 0 && idx < mw.p1ForwardCards.size()) mw.p1ForwardMustBlock.add(idx);
			}
			@Override public void setP2ForwardMustBlock(int idx) {
				if (idx >= 0 && idx < mw.p2ForwardCards.size()) mw.p2ForwardMustBlock.add(idx);
			}
			@Override public void setP1ForwardCannotAttack(int idx) {
				if (idx >= 0 && idx < mw.p1ForwardCards.size()) mw.p1ForwardCannotAttack.add(idx);
			}
			@Override public void setP2ForwardCannotAttack(int idx) {
				if (idx >= 0 && idx < mw.p2ForwardCards.size()) mw.p2ForwardCannotAttack.add(idx);
			}
			@Override public void setP1ForwardMustAttack(int idx) {
				if (idx >= 0 && idx < mw.p1ForwardCards.size()) mw.p1ForwardMustAttack.add(idx);
			}
			@Override public void setP2ForwardMustAttack(int idx) {
				if (idx >= 0 && idx < mw.p2ForwardCards.size()) mw.p2ForwardMustAttack.add(idx);
			}
			@Override public void setP1ForwardCannotAttackOrBlockPersistent(int idx) {
				if (idx >= 0 && idx < mw.p1ForwardCards.size()) {
					mw.p1ForwardCannotAttackPersistent.add(idx);
					mw.p1ForwardCannotBlockPersistent.add(idx);
				}
			}
			@Override public void setP2ForwardCannotAttackOrBlockPersistent(int idx) {
				if (idx >= 0 && idx < mw.p2ForwardCards.size()) {
					mw.p2ForwardCannotAttackPersistent.add(idx);
					mw.p2ForwardCannotBlockPersistent.add(idx);
				}
			}
			@Override public void returnP1ForwardToHand(int idx) { mw.returnP1ForwardToHand(idx); }
			@Override public void returnP2ForwardToHand(int idx) { mw.returnP2ForwardToHand(idx); }
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
			@Override public void returnP1ForwardToDeckBottom(int idx)   { mw.returnP1ForwardToDeck(idx, true);  }
			@Override public void returnP2ForwardToDeckBottom(int idx)   { mw.returnP2ForwardToDeck(idx, true);  }
			@Override public void returnP1ForwardToDeckTop(int idx)      { mw.returnP1ForwardToDeck(idx, false); }
			@Override public void returnP2ForwardToDeckTop(int idx)      { mw.returnP2ForwardToDeck(idx, false); }
			@Override public void returnP1ForwardUnderDeckTop(int idx, int position) { mw.returnP1ForwardUnderDeckTop(idx, position); }
			@Override public void returnP2ForwardUnderDeckTop(int idx, int position) { mw.returnP2ForwardUnderDeckTop(idx, position); }
			@Override public void searchDeckForCard(boolean inclForwards, boolean inclBackups,
					boolean inclMonsters, boolean inclSummons,
					int costVal, String costCmp, String cardNameFilter, String jobFilter,
					String categoryFilter, String elementFilter, String excludeName, String excludeElem,
					String destination, int count, boolean entersDull) {
				mw.searchDeckForCard(isP1, inclForwards, inclBackups, inclMonsters, inclSummons,
						costVal, costCmp, cardNameFilter, jobFilter, categoryFilter, elementFilter, excludeName, excludeElem, destination, count, entersDull);
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

			@Override public void returnP1BackupToHand(int idx) { mw.returnP1BackupToHand(idx); }
			@Override public void returnP2BackupToHand(int idx) { mw.returnP2BackupToHand(idx); }
			@Override public void returnP1MonsterToHand(int idx) { mw.returnP1MonsterToHand(idx); }
			@Override public void returnP2MonsterToHand(int idx) { mw.returnP2MonsterToHand(idx); }

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
				mw.breakP1Forward(idx);
			}
			@Override public void breakP2Forward(int idx) {
				if (idx >= 0 && idx < mw.p2ForwardCards.size()
						&& mw.effectiveP2HasTrait(idx, CardData.Trait.CANNOT_BE_BROKEN)) {
					logEntry("[P2] " + mw.p2ForwardCards.get(idx).name() + " cannot be broken");
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

			@Override
			public List<ForwardTarget> selectCharactersFromBreakZone(
					int maxCount, boolean upTo, boolean opponentZone, boolean bothZones,
					String condition, String element, int costVal, String costCmp,
					int powerVal, String powerCmp,
					boolean inclForwards, boolean inclBackups, boolean inclMonsters,
					String jobFilter, String cardNameFilter, String categoryFilter, String excludeName, boolean inclSummons,
					String excludeElement, boolean withoutMulticard) {
				if (bothZones) {
					// Combine eligible cards from both break zones.
					List<CardData> p1bz = mw.gameState.getP1BreakZone();
					List<CardData> p2bz = mw.gameState.getP2BreakZone();
					List<ForwardTarget> eligible = new ArrayList<>();
					// combined flat list for display; index = offset into combined list
					List<CardData> combined = new ArrayList<>();
					for (int i = 0; i < p1bz.size(); i++) {
						CardData card = p1bz.get(i);
						if (card.isForward()  && !inclForwards) continue;
						if (card.isBackup()   && !inclBackups)  continue;
						if (card.isMonster()  && !inclMonsters) continue;
						if (card.isSummon()   && !inclSummons)  continue;
						if (element != null && !card.containsElement(element)) continue;
						if (!meetsCostConstraint(card.cost(), costVal, costCmp)) continue;
						if (!meetsPowerConstraint(card.power(), powerVal, powerCmp)) continue;
						if (!mw.meetsJobFilterEffective(card, jobFilter)) continue;
						if (!meetsCardNameFilter(card, cardNameFilter)) continue;
						if (!meetsCategoryFilter(card, categoryFilter)) continue;
						if (excludeName != null && excludeName.equalsIgnoreCase(card.name())) continue;
						if (withoutMulticard && card.multicard()) continue;
						eligible.add(new ForwardTarget(true, i, ForwardTarget.CardZone.BREAK_ZONE));
						combined.add(card);
					}
					for (int i = 0; i < p2bz.size(); i++) {
						CardData card = p2bz.get(i);
						if (card.isForward()  && !inclForwards) continue;
						if (card.isBackup()   && !inclBackups)  continue;
						if (card.isMonster()  && !inclMonsters) continue;
						if (card.isSummon()   && !inclSummons)  continue;
						if (element != null && !card.containsElement(element)) continue;
						if (!meetsCostConstraint(card.cost(), costVal, costCmp)) continue;
						if (!meetsPowerConstraint(card.power(), powerVal, powerCmp)) continue;
						if (!mw.meetsJobFilterEffective(card, jobFilter)) continue;
						if (!meetsCardNameFilter(card, cardNameFilter)) continue;
						if (!meetsCategoryFilter(card, categoryFilter)) continue;
						if (excludeName != null && excludeName.equalsIgnoreCase(card.name())) continue;
						if (withoutMulticard && card.multicard()) continue;
						eligible.add(new ForwardTarget(false, i, ForwardTarget.CardZone.BREAK_ZONE));
						combined.add(card);
					}
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
					List<ForwardTarget> chosen = mw.showBreakZoneSelectDialog(reindexed, combined, maxCount, upTo, title);
					// Map chosen reindexed targets back to original targets so callers use real BZ indices
					List<ForwardTarget> result = new ArrayList<>();
					for (ForwardTarget t : chosen) result.add(eligible.get(t.idx()));
					return result;
				}
				List<CardData> bz = opponentZone
						? mw.gameState.getP2BreakZone() : mw.gameState.getP1BreakZone();
				List<ForwardTarget> eligible = new ArrayList<>();
				for (int i = 0; i < bz.size(); i++) {
					CardData card = bz.get(i);
					if (card.isForward()  && !inclForwards) continue;
					if (card.isBackup()   && !inclBackups)  continue;
					if (card.isMonster()  && !inclMonsters) continue;
					if (card.isSummon()   && !inclSummons)  continue;
					if (element != null && !card.containsElement(element)) continue;
					if (!meetsCostConstraint(card.cost(), costVal, costCmp)) continue;
					if (!meetsPowerConstraint(card.power(), powerVal, powerCmp)) continue;
					if (!mw.meetsJobFilterEffective(card, jobFilter)) continue;
					if (!meetsCardNameFilter(card, cardNameFilter)) continue;
					if (!meetsCategoryFilter(card, categoryFilter)) continue;
					if (excludeName != null && excludeName.equalsIgnoreCase(card.name())) continue;
					if (withoutMulticard && card.multicard()) continue;
					eligible.add(new ForwardTarget(!opponentZone, i, ForwardTarget.CardZone.BREAK_ZONE));
				}
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
				// Y'shtola can only cancel Summons and auto-abilities, not action abilities.
				List<StackEntry> targets = mw.gameState.getStack().stream()
						.filter(e -> e.isSummon() || e.isAutoAbility())
						.collect(java.util.stream.Collectors.toList());
				if (targets.isEmpty()) {
					logEntry("No Summons or auto-abilities on the stack to cancel");
					return;
				}
				StackEntry chosen;
				if (targets.size() == 1) {
					chosen = targets.get(0);
				} else if (isP1) {
					String[] options = new String[targets.size()];
					for (int i = 0; i < targets.size(); i++) {
						StackEntry e = targets.get(i);
						String type  = e.isSummon() ? "Summon" : "Auto";
						String owner = e.isP1() ? "P1" : "P2";
						options[i] = e.source().name() + " (" + type + ", " + owner + ")";
					}
					Object sel = JOptionPane.showInputDialog(mw.frame,
							"Choose 1 Summon or auto-ability to cancel:",
							"Cancel Effect", JOptionPane.PLAIN_MESSAGE, null, options, options[0]);
					if (sel == null) return;
					int idx = java.util.Arrays.asList(options).indexOf(sel.toString());
					if (idx < 0) return;
					chosen = targets.get(idx);
				} else {
					// AI: target the most recently pushed opponent (P1) entry
					chosen = targets.stream().filter(e -> e.isP1())
							.reduce((a, b) -> b).orElse(targets.get(targets.size() - 1));
					logEntry("[AI] Chose to cancel: " + chosen.source().name());
				}
				mw.cancelledStackEntries.add(chosen);
				String type = chosen.isSummon() ? "Summon" : "auto-ability";
				logEntry("Effect: " + chosen.source().name() + "'s " + type + " effect will be cancelled");
			}

			@Override public void cancelAutoAbilityAndDamageSourceIfForward(int damage) {
				List<StackEntry> targets = mw.gameState.getStack().stream()
						.filter(StackEntry::isAutoAbility)
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
				mw.cancelledStackEntries.add(chosen);
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
				boolean cancellerIsP1 = isP1;
				java.util.function.Predicate<StackEntry> fullFilter = requiresControllerTarget
						? filter.and(e -> {
							java.util.List<ForwardTarget> stored = e.preSelectedTargets();
							if (stored == null || stored.isEmpty()) return true;
							return stored.stream().anyMatch(t -> t.isP1() == cancellerIsP1);
						})
						: filter;
				List<StackEntry> targets = mw.gameState.getStack().stream()
						.filter(fullFilter)
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
					for (int i = 0; i < targets.size(); i++) {
						StackEntry e = targets.get(i);
						String type = e.isSummon() ? "Summon" : e.isAutoAbility() ? "Auto" : e.isSpecialAbility() ? "Special" : "Action";
						options[i] = e.source().name() + " (" + type + ", " + (e.isP1() ? "P1" : "P2") + ")";
					}
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
				mw.cancelledStackEntries.add(chosen);
				String type = chosen.isSummon() ? "Summon" : chosen.isAutoAbility() ? "auto-ability"
						: chosen.isSpecialAbility() ? "special ability" : "action ability";
				logEntry("Effect: " + chosen.source().name() + "'s " + type + " effect will be cancelled");
			}

			@Override public void redirectAbilityTarget(java.util.function.Predicate<StackEntry> filter, String prompt) {
				List<StackEntry> targets = mw.gameState.getStack().stream()
						.filter(filter)
						.collect(java.util.stream.Collectors.toList());
				if (targets.isEmpty()) {
					logEntry("No matching abilities on the stack to redirect");
					return;
				}
				StackEntry chosen;
				if (targets.size() == 1) {
					chosen = targets.get(0);
				} else if (isP1) {
					String[] options = new String[targets.size()];
					for (int i = 0; i < targets.size(); i++) {
						StackEntry e = targets.get(i);
						String type = e.isSummon() ? "Summon" : e.isAutoAbility() ? "Auto" : e.isSpecialAbility() ? "Special" : "Action";
						options[i] = e.source().name() + " (" + type + ", " + (e.isP1() ? "P1" : "P2") + ")";
					}
					Object sel = JOptionPane.showInputDialog(mw.frame,
							prompt, "Redirect Target", JOptionPane.PLAIN_MESSAGE, null, options, options[0]);
					if (sel == null) return;
					int idx = java.util.Arrays.asList(options).indexOf(sel.toString());
					if (idx < 0) return;
					chosen = targets.get(idx);
				} else {
					chosen = targets.stream().filter(e -> e.isP1())
							.reduce((a, b) -> b).orElse(targets.get(targets.size() - 1));
					logEntry("[AI] Chose to redirect: " + chosen.source().name());
				}
				String type = chosen.isSummon() ? "Summon" : chosen.isAutoAbility() ? "auto-ability"
						: chosen.isSpecialAbility() ? "special ability" : "action ability";
				logEntry("Effect: " + chosen.source().name() + "'s " + type + " target redirected — choose a new valid target for it");
			}

			@Override public void forceTargetToBreakZone(ForwardTarget t) {
				mw.pendingCostBreakDestLabel = t.isP1() ? mw.p1BreakLabel : mw.p2BreakLabel;
				switch (t.zone()) {
					case FORWARD -> { if (t.isP1()) breakP1Forward(t.idx()); else breakP2Forward(t.idx()); }
					case BACKUP  -> { if (t.isP1()) mw.autoAbilityTriggers.breakP1BackupSlot(t.idx()); else mw.breakP2BackupSlot(t.idx()); }
					case MONSTER -> { if (t.isP1()) mw.autoAbilityTriggers.breakP1MonsterSlot(t.idx()); else mw.breakP2MonsterSlot(t.idx()); }
				}
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
					nameLabel.setFont(FontLoader.loadPixelNESFont(9));
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
				countdownLabel.setFont(FontLoader.loadPixelNESFont(10));

				JButton okBtn = new JButton("OK");
				okBtn.setFont(FontLoader.loadPixelNESFont(11));
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
					nameLabel.setFont(FontLoader.loadPixelNESFont(9));
					nameLabel.setPreferredSize(new Dimension(CARD_W, 18));
					wrapper.add(cardLabel,  BorderLayout.CENTER);
					wrapper.add(nameLabel,  BorderLayout.SOUTH);

					JPanel south = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 6));
					south.setBorder(BorderFactory.createEmptyBorder(0, 8, 8, 8));
					if (castFreeApplicable) {
						JButton declineBtn = new JButton("Decline");
						declineBtn.setFont(FontLoader.loadPixelNESFont(11));
						declineBtn.addActionListener(ae -> { mw.hideZoom(); dlg.dispose(); });
						JButton okBtn = new JButton("OK");
						okBtn.setFont(FontLoader.loadPixelNESFont(11));
						okBtn.addActionListener(ae -> { activated[0] = true; mw.hideZoom(); dlg.dispose(); });
						south.add(declineBtn);
						south.add(okBtn);
					} else {
						JButton okBtn = new JButton("OK");
						okBtn.setFont(FontLoader.loadPixelNESFont(11));
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
					nameLabel.setFont(FontLoader.loadPixelNESFont(9));
					nameLabel.setPreferredSize(new Dimension(CARD_W, 18));
					wrapper.add(cardLabel,  BorderLayout.CENTER);
					wrapper.add(nameLabel,  BorderLayout.SOUTH);

					JPanel south = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 6));
					south.setBorder(BorderFactory.createEmptyBorder(0, 8, 8, 8));
					JButton okBtn = new JButton("OK");
					okBtn.setFont(FontLoader.loadPixelNESFont(11));
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

			@Override public void revealEachPlayerTopDeckMayPlay(java.util.function.Predicate<CardData> eligibleCondition) {
				// --- P1 reveal ---
				Deque<CardData> p1Deck = mw.gameState.getP1MainDeck();
				if (p1Deck.isEmpty()) {
					logEntry("Reveal: P1's deck is empty.");
				} else {
					CardData p1Card = p1Deck.pollFirst();
					mw.refreshP1DeckLabel();
					logEntry("P1 revealed: " + p1Card.name() + " (" + p1Card.type() + ")");
					boolean p1Eligible = eligibleCondition.test(p1Card);
					boolean[] p1Play = {false};
					JDialog p1Dlg = new JDialog(mw.frame, "P1 Reveal", true);
					p1Dlg.setResizable(false);
					p1Dlg.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
					JLabel p1CardLabel = new JLabel("...", SwingConstants.CENTER);
					p1CardLabel.setPreferredSize(new Dimension(CARD_W, CARD_H));
					p1CardLabel.setOpaque(true);
					p1CardLabel.setBackground(Color.DARK_GRAY);
					p1CardLabel.setBorder(BorderFactory.createLineBorder(new Color(160, 110, 220), 1));
					p1CardLabel.addMouseListener(new MouseAdapter() {
						@Override public void mouseEntered(MouseEvent e) { mw.showZoomAt(p1Card.imageUrl()); }
						@Override public void mouseExited(MouseEvent e)  { mw.hideZoom(); }
					});
					new SwingWorker<ImageIcon, Void>() {
						@Override protected ImageIcon doInBackground() throws Exception {
							Image img = ImageCache.load(p1Card.imageUrl());
							return img == null ? null : new ImageIcon(img.getScaledInstance(CARD_W, CARD_H, Image.SCALE_SMOOTH));
						}
						@Override protected void done() {
							try { ImageIcon icon = get(); if (icon != null) { p1CardLabel.setIcon(icon); p1CardLabel.setText(null); } }
							catch (InterruptedException | ExecutionException ignored) {}
						}
					}.execute();
					JPanel p1Wrapper = new JPanel(new BorderLayout(0, 4));
					p1Wrapper.setBorder(BorderFactory.createEmptyBorder(8, 8, 0, 8));
					JLabel p1NameLabel = new JLabel(p1Card.name(), SwingConstants.CENTER);
					p1NameLabel.setFont(FontLoader.loadPixelNESFont(9));
					p1NameLabel.setPreferredSize(new Dimension(CARD_W, 18));
					p1Wrapper.add(p1CardLabel, BorderLayout.CENTER);
					p1Wrapper.add(p1NameLabel, BorderLayout.SOUTH);
					JPanel p1South = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 6));
					p1South.setBorder(BorderFactory.createEmptyBorder(0, 8, 8, 8));
					if (p1Eligible) {
						JButton declineBtn = new JButton("Decline");
						declineBtn.setFont(FontLoader.loadPixelNESFont(11));
						declineBtn.addActionListener(ae -> { mw.hideZoom(); p1Dlg.dispose(); });
						JButton okBtn = new JButton("Play onto field");
						okBtn.setFont(FontLoader.loadPixelNESFont(11));
						okBtn.addActionListener(ae -> { p1Play[0] = true; mw.hideZoom(); p1Dlg.dispose(); });
						p1South.add(declineBtn);
						p1South.add(okBtn);
					} else {
						JButton okBtn = new JButton("OK");
						okBtn.setFont(FontLoader.loadPixelNESFont(11));
						okBtn.addActionListener(ae -> { mw.hideZoom(); p1Dlg.dispose(); });
						p1South.add(okBtn);
					}
					p1Dlg.getContentPane().setLayout(new BorderLayout(0, 4));
					p1Dlg.getContentPane().add(p1Wrapper, BorderLayout.CENTER);
					p1Dlg.getContentPane().add(p1South,   BorderLayout.SOUTH);
					p1Dlg.pack();
					p1Dlg.setLocationRelativeTo(mw.frame);
					p1Dlg.setVisible(true);
					if (p1Eligible && p1Play[0]) {
						logEntry("P1 plays " + p1Card.name() + " onto field from reveal");
						if (p1Card.isBackup())       mw.placeCardInFirstBackupSlot(p1Card);
						else if (p1Card.isMonster()) mw.placeCardInMonsterZone(p1Card);
						else                         mw.placeCardInForwardZone(p1Card);
					} else {
						logEntry("P1 returns " + p1Card.name() + " to top of deck");
						p1Deck.addFirst(p1Card);
					}
					mw.refreshP1DeckLabel();
				}

				// --- P2 reveal ---
				Deque<CardData> p2Deck = mw.gameState.getP2MainDeck();
				if (p2Deck.isEmpty()) {
					logEntry("Reveal: P2's deck is empty.");
				} else {
					CardData p2Card = p2Deck.pollFirst();
					mw.refreshP2DeckLabel();
					boolean p2Eligible = eligibleCondition.test(p2Card);
					logEntry("P2 revealed: " + p2Card.name() + " (" + p2Card.type() + ")"
							+ (p2Eligible ? " — plays onto field" : " — returned to deck"));
					JDialog p2Dlg = new JDialog(mw.frame, "P2 Reveal", true);
					p2Dlg.setResizable(false);
					p2Dlg.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
					JLabel p2CardLabel = new JLabel("...", SwingConstants.CENTER);
					p2CardLabel.setPreferredSize(new Dimension(CARD_W, CARD_H));
					p2CardLabel.setOpaque(true);
					p2CardLabel.setBackground(Color.DARK_GRAY);
					p2CardLabel.setBorder(BorderFactory.createLineBorder(new Color(160, 110, 220), 1));
					p2CardLabel.addMouseListener(new MouseAdapter() {
						@Override public void mouseEntered(MouseEvent e) { mw.showZoomAt(p2Card.imageUrl()); }
						@Override public void mouseExited(MouseEvent e)  { mw.hideZoom(); }
					});
					new SwingWorker<ImageIcon, Void>() {
						@Override protected ImageIcon doInBackground() throws Exception {
							Image img = ImageCache.load(p2Card.imageUrl());
							return img == null ? null : new ImageIcon(img.getScaledInstance(CARD_W, CARD_H, Image.SCALE_SMOOTH));
						}
						@Override protected void done() {
							try { ImageIcon icon = get(); if (icon != null) { p2CardLabel.setIcon(icon); p2CardLabel.setText(null); } }
							catch (InterruptedException | ExecutionException ignored) {}
						}
					}.execute();
					JPanel p2Wrapper = new JPanel(new BorderLayout(0, 4));
					p2Wrapper.setBorder(BorderFactory.createEmptyBorder(8, 8, 0, 8));
					JLabel p2NameLabel = new JLabel(p2Card.name() + (p2Eligible ? " → field" : " → deck"), SwingConstants.CENTER);
					p2NameLabel.setFont(FontLoader.loadPixelNESFont(9));
					p2NameLabel.setPreferredSize(new Dimension(CARD_W, 18));
					p2Wrapper.add(p2CardLabel, BorderLayout.CENTER);
					p2Wrapper.add(p2NameLabel, BorderLayout.SOUTH);
					JPanel p2South = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 6));
					p2South.setBorder(BorderFactory.createEmptyBorder(0, 8, 8, 8));
					JButton p2OkBtn = new JButton("OK");
					p2OkBtn.setFont(FontLoader.loadPixelNESFont(11));
					p2OkBtn.addActionListener(ae -> { mw.hideZoom(); p2Dlg.dispose(); });
					p2South.add(p2OkBtn);
					p2Dlg.getContentPane().setLayout(new BorderLayout(0, 4));
					p2Dlg.getContentPane().add(p2Wrapper, BorderLayout.CENTER);
					p2Dlg.getContentPane().add(p2South,   BorderLayout.SOUTH);
					p2Dlg.pack();
					p2Dlg.setLocationRelativeTo(mw.frame);
					p2Dlg.setVisible(true);
					boolean p2WouldViolateUniqueness = p2Eligible && !p2Card.multicard()
							&& (mw.p2ForwardCards.stream().anyMatch(c -> p2Card.name().equalsIgnoreCase(c.name()))
							   || java.util.Arrays.stream(mw.p2BackupCards).anyMatch(c -> c != null && p2Card.name().equalsIgnoreCase(c.name())));
					if (p2Eligible && mw.isP2Cpu() && !p2WouldViolateUniqueness) {
						if (p2Card.isBackup())       mw.placeP2CardInFirstBackupSlot(p2Card);
						else if (p2Card.isMonster()) mw.placeP2CardInMonsterZone(p2Card);
						else                         mw.placeP2CardInForwardZone(p2Card);
					} else {
						if (p2Eligible && !mw.isP2Cpu())
							logEntry("[P2] Each player reveal — multiplayer P2 choice not yet implemented; returning to deck");
						p2Deck.addFirst(p2Card);
					}
					mw.refreshP2DeckLabel();
				}
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
					nameLabel.setFont(FontLoader.loadPixelNESFont(9));
					nameLabel.setPreferredSize(new Dimension(CARD_W, 18));
					wrapper.add(cardLabel, BorderLayout.CENTER);
					wrapper.add(nameLabel, BorderLayout.SOUTH);
					JPanel south = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 6));
					south.setBorder(BorderFactory.createEmptyBorder(0, 8, 8, 8));
					JButton okBtn = new JButton("OK");
					okBtn.setFont(FontLoader.loadPixelNESFont(11));
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
				List<CardData> hand = isP1 ? mw.gameState.getP1Hand() : mw.gameState.getP2Hand();
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
					// Job+name: OR when both are set; AND otherwise
					boolean passesNameJob = (jobFilter == null && cardNameFilter == null)
						|| (jobFilter != null && cardNameFilter != null
							? mw.meetsJobFilterEffective(card, jobFilter) || meetsCardNameFilter(card, cardNameFilter)
							: mw.meetsJobFilterEffective(card, jobFilter) && meetsCardNameFilter(card, cardNameFilter));
					if (!passesNameJob) continue;
					if (!meetsCategoryFilter(card, categoryFilter)) continue;
					if (!meetsElementFilter(card, elementFilter)) continue;
					if (!meetsElementExclusion(card, excludeElement)) continue;
					if (excludeName != null && excludeName.equalsIgnoreCase(card.name())) continue;
					if ("Warp".equalsIgnoreCase(withTrait) && !card.hasWarp()) continue;
					eligible.add(i);
				}
				if (eligible.isEmpty()) {
					logEntry("No eligible cards in hand to play.");
					markEffectFizzled();
					return;
				}
				int handIdx;
				if (isP1) {
					List<CardData> candidates = new ArrayList<>();
					for (int i : eligible) candidates.add(hand.get(i));
					int listIdx = mw.showCardImageChooser(candidates, "Play a card onto the field", true, false);
					if (listIdx < 0) { markEffectFizzled(); return; }
					handIdx = eligible.get(listIdx);
				} else {
					handIdx = eligible.get(0); // AI: play first eligible card
				}
				CardData card = hand.remove(handIdx);
				logEntry((isP1 ? "" : "[P2] ") + card.name() + " played from hand onto field"
						+ (entersDull ? " (dull)" : "") + (suppressAutoAbility ? " (no ETF auto-ability)" : ""));
				if (suppressAutoAbility) mw.suppressAutoAbilityForNextCard = true;
				if (isP1) {
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
						if (!mw.meetsJobFilterEffective(c, jobFilter)) continue;
						if (!meetsCardNameFilter(c, cardNameFilter)) continue;
						if (!meetsCategoryFilter(c, categoryFilter)) continue;
						if (!meetsElementFilter(c, elementFilter)) continue;
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
				if (isP1) {
					mw.p1CardsCastThisTurn++;
					mw.p1SummonCastThisTurn = true;
					for (String j : card.jobs()) mw.p1CastJobsThisTurn.add(j.toLowerCase());
					mw.p1CastNamesThisTurn.add(card.name().toLowerCase());
					mw.p1CastCountByNameThisTurn.merge(card.name().toLowerCase(), 1, Integer::sum);
				} else {
					mw.p2CardsCastThisTurn++;
					mw.p2SummonCastThisTurn = true;
					for (String j : card.jobs()) mw.p2CastJobsThisTurn.add(j.toLowerCase());
					mw.p2CastNamesThisTurn.add(card.name().toLowerCase());
					mw.p2CastCountByNameThisTurn.merge(card.name().toLowerCase(), 1, Integer::sum);
				}
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
				int idx = (int) (Math.random() * hand.size());
				CardData revealed = hand.get(idx);
				logEntry((isP1 ? "" : "[P2] ") + "Randomly revealed: " + revealed.name());
				if (!revealed.isSummon()) {
					logEntry(revealed.name() + " is not a Summon — no cast");
					return;
				}
				boolean cast;
				if (isP1) {
					int choice = mw.showEffectOptionDialog(
							"Randomly revealed: " + revealed.name() + " (Summon)\nCast it without paying the cost?",
							"May Cast Summon", new Object[]{"Cast", "Decline"});
					cast = (choice == 0);
				} else {
					cast = true;
					logEntry("[P2 AI] Auto-casts " + revealed.name());
				}
				if (!cast) {
					logEntry("Declined to cast " + revealed.name());
					return;
				}
				hand.remove(idx);
				if (isP1) mw.refreshP1HandLabel(); else mw.refreshP2HandCountLabel();
				if (isP1) {
					mw.p1CardsCastThisTurn++;
					mw.p1SummonCastThisTurn = true;
					for (String j : revealed.jobs()) mw.p1CastJobsThisTurn.add(j.toLowerCase());
					mw.p1CastNamesThisTurn.add(revealed.name().toLowerCase());
					mw.p1CastCountByNameThisTurn.merge(revealed.name().toLowerCase(), 1, Integer::sum);
				} else {
					mw.p2CardsCastThisTurn++;
					mw.p2SummonCastThisTurn = true;
					for (String j : revealed.jobs()) mw.p2CastJobsThisTurn.add(j.toLowerCase());
					mw.p2CastNamesThisTurn.add(revealed.name().toLowerCase());
					mw.p2CastCountByNameThisTurn.merge(revealed.name().toLowerCase(), 1, Integer::sum);
				}
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
					mw.p2CardsCastThisTurn++;
					mw.p2SummonCastThisTurn = true;
					for (String j : card.jobs()) mw.p2CastJobsThisTurn.add(j.toLowerCase());
					mw.p2CastNamesThisTurn.add(card.name().toLowerCase());
					mw.p2CastCountByNameThisTurn.merge(card.name().toLowerCase(), 1, Integer::sum);
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
					if (isP1) {
						mw.p1CardsCastThisTurn++;
						mw.p1SummonCastThisTurn = true;
						for (String j : picked.jobs()) mw.p1CastJobsThisTurn.add(j.toLowerCase());
						mw.p1CastNamesThisTurn.add(picked.name().toLowerCase());
						mw.p1CastCountByNameThisTurn.merge(picked.name().toLowerCase(), 1, Integer::sum);
					} else {
						mw.p2CardsCastThisTurn++;
						mw.p2SummonCastThisTurn = true;
						for (String j : picked.jobs()) mw.p2CastJobsThisTurn.add(j.toLowerCase());
						mw.p2CastNamesThisTurn.add(picked.name().toLowerCase());
						mw.p2CastCountByNameThisTurn.merge(picked.name().toLowerCase(), 1, Integer::sum);
					}
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

			@Override public void eachPlayerSelectForwardAndDamage(int amount) {
				ForwardTarget p1Pick = null;
				if (!mw.p1ForwardCards.isEmpty()) {
					List<ForwardTarget> p1Eligible = new ArrayList<>();
					for (int i = 0; i < mw.p1ForwardCards.size(); i++)
						p1Eligible.add(new ForwardTarget(true, i, ForwardTarget.CardZone.FORWARD));
					// Bypass the single-eligible auto-pick in mw.showForwardSelectDialog — the card text
					// explicitly says "each player selects", so the choice must be explicit even when
					// only one Forward is eligible (e.g. Brute Bomber alone on the field).
					List<ForwardTarget> picks = mw.selectFieldTargetsInPlace(p1Eligible, 1, false,
							"Each player selects 1 Forward — choose yours");
					if (!picks.isEmpty()) p1Pick = picks.get(0);
				} else {
					logEntry("P1 has no Forwards — skipping selection");
				}

				ForwardTarget p2Pick = null;
				if (!mw.p2ForwardCards.isEmpty()) {
					p2Pick = mw.aiPickForwardToSurvive(amount);
					if (p2Pick != null)
						logEntry("[AI] selected " + mw.p2ForwardCards.get(p2Pick.idx()).name());
				} else {
					logEntry("[P2] has no Forwards — skipping selection");
				}

				if (p1Pick != null) damageP1Forward(p1Pick.idx(), amount);
				if (p2Pick != null) damageP2Forward(p2Pick.idx(), amount);
			}

			@Override public void eachPlayerSelectForwardAndBreak() {
				ForwardTarget p1Pick = null;
				if (!mw.p1ForwardCards.isEmpty()) {
					List<ForwardTarget> p1Eligible = new ArrayList<>();
					for (int i = 0; i < mw.p1ForwardCards.size(); i++)
						p1Eligible.add(new ForwardTarget(true, i, ForwardTarget.CardZone.FORWARD));
					List<ForwardTarget> picks = mw.selectFieldTargetsInPlace(p1Eligible, 1, false,
							"Both players select 1 Forward — choose yours to put in Break Zone");
					if (!picks.isEmpty()) p1Pick = picks.get(0);
				} else {
					logEntry("P1 has no Forwards — skipping selection");
				}

				ForwardTarget p2Pick = null;
				if (!mw.p2ForwardCards.isEmpty()) {
					p2Pick = mw.aiPickForwardForBreak();
					if (p2Pick != null)
						logEntry("[AI] selected " + mw.p2ForwardCards.get(p2Pick.idx()).name());
				} else {
					logEntry("[P2] has no Forwards — skipping selection");
				}

				if (p1Pick != null) forceTargetToBreakZone(p1Pick);
				if (p2Pick != null) forceTargetToBreakZone(p2Pick);
			}

			@Override public void selectControlledForwardAndBreak() {
				if (isP1) {
					if (mw.p1ForwardCards.isEmpty()) {
						logEntry("P1 has no Forwards — skipping selection");
						return;
					}
					List<ForwardTarget> eligible = new ArrayList<>();
					for (int i = 0; i < mw.p1ForwardCards.size(); i++)
						eligible.add(new ForwardTarget(true, i, ForwardTarget.CardZone.FORWARD));
					List<ForwardTarget> picks = mw.selectFieldTargetsInPlace(eligible, 1, false,
							"Select 1 Forward you control to put into the Break Zone");
					if (!picks.isEmpty()) forceTargetToBreakZone(picks.get(0));
				} else {
					if (mw.p2ForwardCards.isEmpty()) {
						logEntry("[AI] P2 has no Forwards — skipping selection");
						return;
					}
					ForwardTarget pick = mw.aiPickForwardForBreak();
					if (pick != null) {
						logEntry("[AI] selected " + mw.p2ForwardCards.get(pick.idx()).name());
						forceTargetToBreakZone(pick);
					}
				}
			}

			@Override public void selectControlledTypeAndBreak(boolean inclForwards, boolean inclBackups, boolean inclMonsters) {
				if (isP1) {
					List<ForwardTarget> eligible = new ArrayList<>();
					if (inclForwards)
						for (int i = 0; i < mw.p1ForwardCards.size(); i++)
							eligible.add(new ForwardTarget(true, i, ForwardTarget.CardZone.FORWARD));
					if (inclBackups)
						for (int i = 0; i < mw.p1BackupCards.length; i++)
							if (mw.p1BackupCards[i] != null)
								eligible.add(new ForwardTarget(true, i, ForwardTarget.CardZone.BACKUP));
					if (inclMonsters)
						for (int i = 0; i < mw.p1MonsterCards.size(); i++)
							eligible.add(new ForwardTarget(true, i, ForwardTarget.CardZone.MONSTER));
					if (eligible.isEmpty()) { logEntry("P1 has no eligible characters — skipping"); return; }
					List<ForwardTarget> picks = mw.selectFieldTargetsInPlace(eligible, 1, false,
							"Select 1 Character you control to put into the Break Zone");
					if (!picks.isEmpty()) forceTargetToBreakZone(picks.get(0));
				} else {
					List<ForwardTarget> eligible = new ArrayList<>();
					if (inclForwards)
						for (int i = 0; i < mw.p2ForwardCards.size(); i++)
							eligible.add(new ForwardTarget(false, i, ForwardTarget.CardZone.FORWARD));
					if (inclBackups)
						for (int i = 0; i < mw.p2BackupCards.length; i++)
							if (mw.p2BackupCards[i] != null)
								eligible.add(new ForwardTarget(false, i, ForwardTarget.CardZone.BACKUP));
					if (inclMonsters)
						for (int i = 0; i < mw.p2MonsterCards.size(); i++)
							eligible.add(new ForwardTarget(false, i, ForwardTarget.CardZone.MONSTER));
					if (eligible.isEmpty()) { logEntry("[AI] P2 has no eligible characters — skipping"); return; }
					ForwardTarget pick = null;
					if (inclForwards && !mw.p2ForwardCards.isEmpty()) pick = mw.aiPickForwardForBreak();
					if (pick == null && inclBackups) {
						for (int i = 0; i < mw.p2BackupCards.length; i++)
							if (mw.p2BackupCards[i] != null) { pick = new ForwardTarget(false, i, ForwardTarget.CardZone.BACKUP); break; }
					}
					if (pick == null && inclMonsters && !mw.p2MonsterCards.isEmpty())
						pick = new ForwardTarget(false, 0, ForwardTarget.CardZone.MONSTER);
					if (pick != null) {
						String name = switch (pick.zone()) {
							case FORWARD   -> mw.p2ForwardCards.get(pick.idx()).name();
							case BACKUP    -> mw.p2BackupCards[pick.idx()].name();
							case MONSTER   -> mw.p2MonsterCards.get(pick.idx()).name();
							default        -> "?";
						};
						logEntry("[AI] selected " + name);
						forceTargetToBreakZone(pick);
					}
				}
			}

			@Override public void eachPlayerSalvageFromBreakZone(int count) {
				List<CardData> p1Bz = mw.gameState.getP1BreakZone();
				List<CardData> p2Bz = mw.gameState.getP2BreakZone();

				// P1 picks via dialog
				List<ForwardTarget> p1Picks = List.of();
				if (!p1Bz.isEmpty()) {
					List<ForwardTarget> eligible = new ArrayList<>();
					for (int i = 0; i < p1Bz.size(); i++) {
						CardData c = p1Bz.get(i);
						ForwardTarget.CardZone cz = c.isBackup() ? ForwardTarget.CardZone.BACKUP
								: c.isMonster() ? ForwardTarget.CardZone.MONSTER
								: ForwardTarget.CardZone.FORWARD;
						eligible.add(new ForwardTarget(true, i, cz));
					}
					p1Picks = mw.showBreakZoneSelectDialog(eligible, p1Bz, count, false,
							"Each player salvages " + count + " card(s) — choose from your Break Zone");
				} else {
					logEntry("P1 Break Zone is empty — skipping salvage");
				}

				// P2 (AI) auto-picks highest-cost cards
				List<ForwardTarget> p2Picks = new ArrayList<>();
				if (!p2Bz.isEmpty()) {
					List<Integer> idxs = new ArrayList<>();
					for (int i = 0; i < p2Bz.size(); i++) idxs.add(i);
					idxs.sort((a, b) -> Integer.compare(p2Bz.get(b).cost(), p2Bz.get(a).cost()));
					for (int i = 0; i < Math.min(count, idxs.size()); i++) {
						int idx = idxs.get(i);
						p2Picks.add(new ForwardTarget(false, idx, ForwardTarget.CardZone.FORWARD));
						logEntry("[AI] salvaged " + p2Bz.get(idx).name() + " from P2 Break Zone");
					}
				} else {
					logEntry("[P2] Break Zone is empty — skipping salvage");
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

			@Override public void removeTopCardsOfDeckFromGame(int count) {
				Deque<CardData> deck = isP1 ? mw.gameState.getP1MainDeck() : mw.gameState.getP2MainDeck();
				for (int i = 0; i < count && !deck.isEmpty(); i++) {
					CardData c = deck.pollFirst();
					mw.gameState.addToPermanentRfp(c);
					logEntry(c.name() + " → Removed From Game (top of deck)");
				}
				if (isP1) mw.refreshP1DeckLabel(); else mw.refreshP2DeckLabel();
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

			@Override public void playTargetOntoField(ForwardTarget t) {
				List<CardData> bz = t.isP1() ? mw.gameState.getP1BreakZone() : mw.gameState.getP2BreakZone();
				if (t.idx() >= bz.size()) return;
				CardData card = bz.remove(t.idx());
				String src = t.isP1() ? "Break Zone" : "opponent's Break Zone";
				logEntry(card.name() + " played from " + src + " onto field");
				if (t.isP1()) {
					if (card.isBackup())       mw.placeCardInFirstBackupSlot(card);
					else if (card.isMonster()) mw.placeCardInMonsterZone(card);
					else                       mw.placeCardInForwardZone(card);
				} else {
					if (card.isBackup())       mw.placeP2CardInFirstBackupSlot(card);
					else if (card.isMonster()) mw.placeP2CardInMonsterZone(card);
					else                       mw.placeP2CardInForwardZone(card);
				}
				if (t.isP1()) mw.refreshP1BreakLabel(); else mw.refreshP2BreakLabel();
			}

			@Override public void playTargetOntoFieldDull(ForwardTarget t) {
				List<CardData> bz = t.isP1() ? mw.gameState.getP1BreakZone() : mw.gameState.getP2BreakZone();
				if (t.idx() >= bz.size()) return;
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

			@Override public void addTargetToHand(ForwardTarget t) {
				List<CardData> bz = t.isP1() ? mw.gameState.getP1BreakZone() : mw.gameState.getP2BreakZone();
				if (t.idx() >= bz.size()) return;
				CardData card = bz.remove(t.idx());
				mw.gameState.getP1Hand().add(card);
				logEntry(card.name() + (t.isP1() ? " returned from Break Zone to hand" : " taken from opponent's Break Zone to hand"));
				if (t.isP1()) mw.refreshP1BreakLabel(); else mw.refreshP2BreakLabel();
				mw.refreshP1HandLabel();
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
						bsb.append(bGranted.stream().map(Enum::name)
								.map(s -> s.substring(0, 1).toUpperCase() + s.substring(1).toLowerCase())
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
					boost.set(idx, boost.get(idx) + effectiveAmount);
					(isP1 ? mw.p1ForwardTempTraits : mw.p2ForwardTempTraits).get(idx).addAll(traits);
					grantedTraits.addAll(traits);
				}

				StringBuilder sb = new StringBuilder();
				if (!grantedTraits.isEmpty()) {
					sb.append(grantedTraits.stream().map(Enum::name)
							.map(s -> s.substring(0, 1).toUpperCase() + s.substring(1).toLowerCase())
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

			@Override public void setSourceForwardCannotBeBlocked(CardData source) {
				for (int i = 0; i < mw.p1ForwardCards.size(); i++) {
					if (mw.p1ForwardCards.get(i).name().equals(source.name())) {
						setP1ForwardCannotBeBlocked(i);
						return;
					}
				}
			}

			@Override public void boostSourceForward(CardData source, int amount,
					EnumSet<CardData.Trait> traits) {
				for (int i = 0; i < mw.p1ForwardCards.size(); i++) {
					if (mw.p1ForwardCards.get(i).name().equals(source.name())) {
						if (amount > 0 && (mw.oppForwardPowerBoostSuppressedFor(true) || mw.oppForwardSelfBoostSuppressedFor(true))) {
							logEntry(source.name() + " — power boost suppressed (opponent's field ability)");
							return;
						}
						mw.p1ForwardPowerBoost.set(i, mw.p1ForwardPowerBoost.get(i) + amount);
						mw.p1ForwardTempTraits.get(i).addAll(traits);
						logEntry(source.name() + " gains +" + amount + " power until end of turn");
						mw.refreshP1ForwardSlot(i);
						return;
					}
				}
			}

			@Override public void doubleSourceForwardPower(CardData source,
					EnumSet<CardData.Trait> traits) {
				for (int i = 0; i < mw.p1ForwardCards.size(); i++) {
					if (mw.p1ForwardCards.get(i).name().equals(source.name())) {
						if (mw.oppForwardPowerBoostSuppressedFor(true) || mw.oppForwardSelfBoostSuppressedFor(true)) {
							logEntry(source.name() + " — power doubling suppressed (opponent's field ability)");
							return;
						}
						int current = mw.effectiveP1ForwardPower(i);
						mw.p1ForwardPowerBoost.set(i, mw.p1ForwardPowerBoost.get(i) + current);
						mw.p1ForwardTempTraits.get(i).addAll(traits);
						logEntry(source.name() + " — power doubled to " + (current * 2) + " until end of turn");
						mw.refreshP1ForwardSlot(i);
						return;
					}
				}
			}

			@Override public void setSourceForwardPower(CardData source, int power) {
				for (int i = 0; i < mw.p1ForwardCards.size(); i++) {
					if (mw.p1ForwardCards.get(i).name().equals(source.name())) {
						int base = mw.p1ForwardCards.get(i).power();
						mw.p1ForwardPowerReduction.set(i, 0);
						mw.p1ForwardPowerBoost.set(i, power - base);
						logEntry(source.name() + " — power becomes " + power + " until end of turn");
						mw.refreshP1ForwardSlot(i);
						return;
					}
				}
			}

			@Override public void addPendingMainPhase1Effect(Consumer<GameContext> effect) {
				mw.pendingMainPhase1Effects.add(effect);
			}

			@Override public void setTargetPower(ForwardTarget t, int power) {
				if (t.zone() != ForwardTarget.CardZone.FORWARD) return;
				int idx = t.idx();
				if (t.isP1()) {
					if (idx >= mw.p1ForwardCards.size()) return;
					int base = mw.p1ForwardCards.get(idx).power();
					mw.p1ForwardPowerReduction.set(idx, 0);
					mw.p1ForwardPowerBoost.set(idx, power - base);
					logEntry(p1Forward(idx).name() + " power becomes " + power + " until end of turn");
					mw.refreshP1ForwardSlot(idx);
				} else {
					if (idx >= mw.p2ForwardCards.size()) return;
					int base = mw.p2ForwardCards.get(idx).power();
					mw.p2ForwardPowerReduction.set(idx, 0);
					mw.p2ForwardPowerBoost.set(idx, power - base);
					logEntry("[P2] " + mw.p2ForwardCards.get(idx).name() + " power becomes " + power + " until end of turn");
					mw.refreshP2ForwardSlot(idx);
				}
			}

			@Override public void placeCounters(CardData card, String counterName, int count) {
				mw.gameState.placeCounters(card, counterName, count);
				Map<String, Integer> all = mw.gameState.getCountersMap(card);
				logEntry(card.name() + " — placed " + count + " " + counterName
						+ " Counter(s)  [now: " + all + "]");
			}

			@Override public int getCounters(CardData card, String counterName) {
				return mw.gameState.getCounters(card, counterName);
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
			}

			@Override public void lookAtTopDeck(LookConfig config) {
				mw.lookDialogs().show(config, isP1);
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
					if (isP1) {
						mw.p1CardsCastThisTurn++;
						mw.p1SummonCastThisTurn = true;
						for (String j : picked.jobs()) mw.p1CastJobsThisTurn.add(j.toLowerCase());
						mw.p1CastNamesThisTurn.add(picked.name().toLowerCase());
						mw.p1CastCountByNameThisTurn.merge(picked.name().toLowerCase(), 1, Integer::sum);
					} else {
						mw.p2CardsCastThisTurn++;
						mw.p2SummonCastThisTurn = true;
						for (String j : picked.jobs()) mw.p2CastJobsThisTurn.add(j.toLowerCase());
						mw.p2CastNamesThisTurn.add(picked.name().toLowerCase());
						mw.p2CastCountByNameThisTurn.merge(picked.name().toLowerCase(), 1, Integer::sum);
					}
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
					int effPow = mw.effectiveP1ForwardPower(idx);
					logEntry(p1Forward(idx).name() + " loses " + amount + " power"
							+ (!traits.isEmpty() ? (amount > 0 ? " and " : "") + traits : "") + " until end of turn");
					if (effPow <= 0) {
						logEntry(p1Forward(idx).name() + " reduced to 0 power → Break Zone");
						mw.pendingCostBreakDestLabel = mw.p1BreakLabel;
						breakP1Forward(idx);
					} else {
						mw.refreshP1ForwardSlot(idx);
					}
				} else {
					int idx = t.idx();
					if (idx >= mw.p2ForwardCards.size()) return;
					mw.p2ForwardPowerReduction.set(idx, mw.p2ForwardPowerReduction.get(idx) + amount);
					mw.p2ForwardRemovedTraits.get(idx).addAll(traits);
					int effPow = mw.effectiveP2ForwardPower(idx);
					logEntry("[P2] " + mw.p2ForwardCards.get(idx).name() + " loses "
							+ (amount > 0 ? amount + " power" : "")
							+ (!traits.isEmpty() ? (amount > 0 ? " and " : "") + traits : "") + " until end of turn");
					if (effPow <= 0) {
						logEntry("[P2] " + mw.p2ForwardCards.get(idx).name() + " reduced to 0 power → Break Zone");
						mw.pendingCostBreakDestLabel = mw.p2BreakLabel;
						breakP2Forward(idx);
					} else {
						mw.refreshP2ForwardSlot(idx);
					}
				}
			}

			@Override public void reduceSourceForward(CardData source, int amount,
					EnumSet<CardData.Trait> traits) {
				for (int i = 0; i < mw.p1ForwardCards.size(); i++) {
					if (mw.p1ForwardCards.get(i).name().equals(source.name())) {
						reduceTarget(new ForwardTarget(true, i, ForwardTarget.CardZone.FORWARD), amount, traits);
						return;
					}
				}
			}

			@Override public int dullForwardCostPower() { return mw.lastDullForwardCostPower; }
			@Override public int lastDiscardedForwardPower() { return mw.lastDiscardedForwardPower; }
			@Override public int bzCostForwardPower() { return mw.lastBzCostForwardPower; }
			@Override public void suppressExBurstsThisAbility() { mw.suppressExBurstsThisAbility = true; }
			@Override public String lastDiscardedCardName() { return mw.lastDiscardedCardName; }
			@Override public String lastDiscardedCostCardElement() {
				return mw.lastDiscardedCostCard == null ? null : mw.lastDiscardedCostCard.elements()[0];
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
						mw.gameState.pushStack(new StackEntry(source, null, fa, isP1, 0, false, null, false));
						mw.showStackWindowIfNeeded();
						return;
					}
				}
				mw.logEntry("[AutoAbility] " + source.name() + " — no ability with trigger '" + triggerType + "' to retrigger");
			}

			@Override public List<String> chooseActions(CardData source,
					List<String> actions, int selectCount, boolean upTo) {
				if (isP1) return mw.autoAbilityTriggers.showSelectActionsDialog(source, actions, selectCount, upTo);
				int take = Math.min(selectCount, actions.size());
				return new ArrayList<>(actions.subList(0, take));
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
							mw.p2DiscardedByEffectThisTurn = true;
							mw.p1CausedOpponentDiscardThisTurn = true;
						}
					}
					mw.refreshP2HandCountLabel();
					mw.refreshP2BreakLabel();
				} else {
					mw.showForcedDiscardDialog(count, true);
					mw.p2CausedOpponentDiscardThisTurn = true;
				}
			}

			@Override public void forceOpponentRandomDiscard(int count) {
				if (isP1) {
					List<CardData> hand = mw.gameState.getP2Hand();
					int actual = Math.min(count, hand.size());
					for (int i = 0; i < actual; i++) {
						int idx = (int) (Math.random() * mw.gameState.getP2Hand().size());
						CardData d = mw.playerBreakFromHand(false,idx);
						if (d != null) {
							logEntry("[P2] Randomly discards " + d.name());
							mw.p2DiscardedByEffectThisTurn = true;
							mw.p1CausedOpponentDiscardThisTurn = true;
						}
					}
					mw.refreshP2HandCountLabel();
					mw.refreshP2BreakLabel();
				} else {
					List<CardData> hand = mw.gameState.getP1Hand();
					int actual = Math.min(count, hand.size());
					for (int i = 0; i < actual; i++) {
						int idx = (int) (Math.random() * mw.gameState.getP1Hand().size());
						CardData d = mw.playerBreakFromHand(true,idx);
						if (d != null) {
							logEntry("[P1] Randomly discards " + d.name());
							mw.p1DiscardedByEffectThisTurn = true;
							mw.p2CausedOpponentDiscardThisTurn = true;
						}
					}
					mw.refreshP1HandLabel();
					mw.refreshP1BreakLabel();
				}
			}

			@Override public void drawCardsForOpponent(int count) {
				if (isP1) {
					mw.drawP2Cards(count);
					mw.animateCardDraw(false, count);
					mw.refreshP2DeckLabel();
					mw.refreshP2HandCountLabel();
				} else {
					mw.drawP1Cards(count);
					mw.animateCardDraw(true, count);
					mw.refreshP1HandLabel();
					mw.refreshP1DeckLabel();
				}
			}

			@Override public void forceOpponentRandomHandRfp(int count) {
				if (isP1) {
					List<CardData> hand = mw.gameState.getP2Hand();
					int actual = Math.min(count, hand.size());
					for (int i = 0; i < actual; i++) {
						if (hand.isEmpty()) break;
						int idx = (int) (Math.random() * hand.size());
						CardData d = hand.remove(idx);
						mw.gameState.addToPermanentRfp(d);
						logEntry("[P2] Randomly removed from game: " + d.name());
					}
					mw.refreshP2HandCountLabel();
				} else {
					List<CardData> hand = mw.gameState.getP1Hand();
					int actual = Math.min(count, hand.size());
					for (int i = 0; i < actual; i++) {
						if (hand.isEmpty()) break;
						int idx = (int) (Math.random() * hand.size());
						CardData d = mw.gameState.removeFromHand(idx);
						if (d != null) { mw.gameState.addToPermanentRfp(d); logEntry("[P1] Randomly removed from game: " + d.name()); }
					}
					mw.refreshP1HandLabel();
					mw.refreshP1WarpZoneUI();
				}
			}

			@Override public void forceOpponentRandomHandToBottomOfDeck(int count) {
				if (isP1) {
					List<CardData> hand = mw.gameState.getP2Hand();
					int actual = Math.min(count, hand.size());
					for (int i = 0; i < actual; i++) {
						if (hand.isEmpty()) break;
						int idx = (int) (Math.random() * hand.size());
						CardData d = hand.remove(idx);
						mw.gameState.getP2MainDeck().addLast(d);
						logEntry("[P2] Randomly placed " + d.name() + " at bottom of deck");
					}
					mw.refreshP2HandCountLabel();
					mw.refreshP2DeckLabel();
				} else {
					List<CardData> hand = mw.gameState.getP1Hand();
					int actual = Math.min(count, hand.size());
					for (int i = 0; i < actual; i++) {
						if (hand.isEmpty()) break;
						int idx = (int) (Math.random() * hand.size());
						CardData d = mw.gameState.removeFromHand(idx);
						if (d != null) {
							mw.gameState.getP1MainDeck().addLast(d);
							logEntry("[P1] Randomly placed " + d.name() + " at bottom of deck");
						}
					}
					mw.refreshP1HandLabel();
					mw.refreshP1DeckLabel();
				}
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
						mw.placeCardInForwardZone(card);
						return;
					}
				}
				for (CardData card : mw.gameState.getP2PermanentRfp()) {
					if (card.name().equalsIgnoreCase(cardName)) {
						mw.gameState.removeFromPermanentRfp(card);
						logEntry(card.name() + " returns from RFP → P2 field");
						mw.placeP2CardInForwardZone(card);
						return;
					}
				}
				logEntry("[Warning] playNamedFromRfpOntoField: \"" + cardName + "\" not found in RFP");
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
					mw.placeCardInForwardZone(card);
					if (dull) {
						int newIdx = mw.p1ForwardCards.size() - 1;
						mw.p1ForwardStates.set(newIdx, CardState.DULL);
						mw.refreshP1ForwardSlot(newIdx);
					}
				} else {
					mw.gameState.removeFromPermanentRfp(card);
					logEntry(card.name() + " returns from RFP → P2 field" + (dull ? " (dull)" : ""));
					mw.placeP2CardInForwardZone(card);
					if (dull) {
						int newIdx = mw.p2ForwardCards.size() - 1;
						mw.p2ForwardStates.set(newIdx, CardState.DULL);
						mw.refreshP2ForwardSlot(newIdx);
					}
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
				for (int i = 0; i < mw.p1ForwardCards.size(); i++) {
					if (mw.p1ForwardCards.get(i).name().equalsIgnoreCase(cardName)) {
						mw.p1ForwardCannotAttack.remove(i);
						mw.refreshP1ForwardSlot(i);
						return;
					}
				}
				for (int i = 0; i < mw.p1MonsterCards.size(); i++) {
					if (mw.p1MonsterCards.get(i).name().equalsIgnoreCase(cardName)) {
						return;
					}
				}
				logEntry("[Warning] grantAttackOnceMore: \"" + cardName + "\" not found on P1's field");
			}

			@Override public void limitOpponentAttackDeclarationsThisTurn(int max) {
				if (isP1) {
					mw.opponentAttackDeclarationLimit = max;
				} else {
					mw.p1AttackDeclarationLimit = max;
				}
				logEntry("Effect: Opponent may only declare attack " + max + " time(s) this turn");
			}

			@Override public void setOpponentCannotSearchThisTurn() {
				if (isP1) mw.p2CannotSearchThisTurn = true; else mw.p1CannotSearchThisTurn = true;
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
				if (isP1) {
					mw.drawP1Cards(count);
					mw.animateCardDraw(true, count);
					mw.refreshP1HandLabel();
					mw.refreshP1DeckLabel();
				} else {
					mw.drawP2Cards(count);
					mw.animateCardDraw(false, count);
					mw.refreshP2DeckLabel();
					mw.refreshP2HandCountLabel();
				}
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
						if (d != null) { logEntry("[P2] Discards " + d.name()); mw.p2DiscardedByEffectThisTurn = true; mw.lastDiscardedCardName = d.name(); }
					}
					mw.refreshP2HandCountLabel();
					mw.refreshP2BreakLabel();
				}
			}

			@Override public void selfDiscardByType(String cardType) {
				if (isP1) {
					boolean discarded = mw.showDiscardByTypeDialog(cardType);
					if (!discarded) markEffectFizzled();
				} else {
					List<CardData> hand = mw.gameState.getP2Hand();
					List<Integer> eligible = new ArrayList<>();
					for (int i = 0; i < hand.size(); i++) {
						if (matchesDiscardType(hand.get(i), cardType)) eligible.add(i);
					}
					if (eligible.isEmpty()) { markEffectFizzled(); return; }
					List<CardData> eligibleCards = eligible.stream().map(hand::get).collect(Collectors.toList());
					int relIdx = MainWindow.pickWorstHandCard0(eligibleCards);
					int idx = eligible.get(relIdx);
					CardData d = mw.playerBreakFromHand(false, idx);
					if (d != null) {
						logEntry("[P2] Discards " + d.name());
						mw.p2DiscardedByEffectThisTurn = true;
						if (d.isForward()) mw.lastDiscardedForwardPower = d.power();
					}
					mw.refreshP2HandCountLabel();
					mw.refreshP2BreakLabel();
				}
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
						mw.p2DiscardedByEffectThisTurn = true;
						if (d.isForward()) mw.lastDiscardedForwardPower = d.power();
					}
					mw.refreshP2HandCountLabel();
					mw.refreshP2BreakLabel();
				}
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
						mw.p2DiscardedByEffectThisTurn = true;
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
				mw.autoAbilityTriggers.showAutoAbilityPaymentDialog(src + " (replay)", 1, 1, isP1, paid -> {
					if (paid >= 1) { logEntry("Replay: paid 《" + element + "》 — using ability again"); replayAction.accept(this); }
				});
			}

			@Override public void mayPayElementCpToEffect(String element, java.util.function.Consumer<GameContext> onPay) {
				if (!isP1) {
					logEntry("[P2 AI] Pays 《" + element + "》 for optional effect");
					mw.autoAbilityTriggers.showAutoAbilityPaymentDialog("", 1, 1, isP1, paid -> {
						if (paid >= 1) { logEntry("[P2 AI] Paid 《" + element + "》 — applying effect"); onPay.accept(this); }
					});
					return;
				}
				String src    = mw.currentAbilitySource != null ? mw.currentAbilitySource.name() : "Ability";
				String label  = "Pay 《" + element + "》?";
				int choice = mw.showEffectOptionDialog(src + " — " + label, "Optional Cost", new Object[]{"Pay", "Pass"});
				if (choice != 0) { logEntry("Optional pay: declined 《" + element + "》"); return; }
				mw.autoAbilityTriggers.showAutoAbilityPaymentDialog(src, 1, 1, isP1, paid -> {
					if (paid >= 1) { logEntry("Optional pay: paid 《" + element + "》 — applying effect"); onPay.accept(this); }
				});
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
				if (d != null) { logEntry("Replay: discarded " + d.name()); mw.p1DiscardedByEffectThisTurn = true; }
				mw.refreshP1HandLabel();
				mw.refreshP1BreakLabel();
				logEntry("Replay: using ability again");
				replayAction.accept(this);
			}

			@Override public void mayDiscardCardNameFromHand(String cardName, java.util.function.Consumer<GameContext> ifDiscarded) {
				List<CardData> hand = isP1 ? mw.gameState.getP1Hand() : mw.gameState.getP2Hand();
				int handIdx = -1;
				for (int i = 0; i < hand.size(); i++) {
					if (hand.get(i).name().equalsIgnoreCase(cardName)) { handIdx = i; break; }
				}
				if (handIdx < 0) { logEntry("[Effect] No " + cardName + " in hand — optional discard skipped"); return; }
				if (!isP1) { logEntry("[P2 AI] Passes on optional discard of " + cardName); return; }
				String src = mw.currentAbilitySource != null ? mw.currentAbilitySource.name() : "Ability";
				int choice = mw.showEffectOptionDialog(
						src + " — Discard " + cardName + " from hand?",
						"You May Discard", new Object[]{"Discard", "Pass"});
				if (choice != 0) { logEntry("[Effect] Declined to discard " + cardName); return; }
				final int idx = handIdx;
				CardData d = mw.playerBreakFromHand(true,idx);
				if (d != null) { logEntry("[Effect] Discarded " + d.name()); mw.p1DiscardedByEffectThisTurn = true; }
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
						mw.p1DiscardedByEffectThisTurn = true;
						mw.addToBreakZone(d, false);
					}
					mw.refreshP1HandLabel();
				} else {
					List<CardData> hand = mw.gameState.getP2Hand();
					for (int i = hand.size() - 1; i >= 0; i--) {
						CardData d = hand.remove(i);
						mw.animateCardDiscard(false, d);
						logEntry("[P2] Discards " + d.name());
						mw.p2DiscardedByEffectThisTurn = true;
						mw.addToBreakZone(d, false);
					}
					mw.refreshP2HandCountLabel();
				}
			}

			@Override public void dealDamageToOpponent(int amount) {
				if (mw.currentAbilitySource != null
						&& !mw.lostAbilitiesCards.contains(mw.currentAbilitySource)) {
					for (FieldAbility fa : mw.currentAbilitySource.fieldAbilities()) {
						Matcher m = AutoAbilityTriggers.FA_OUTGOING_DAMAGE_DOUBLER.matcher(fa.effectText());
						if (!m.find()) continue;
						if (!m.group("card").trim().equalsIgnoreCase(mw.currentAbilitySource.name())) continue;
						if (!m.group("target").toLowerCase().contains("opponent")) continue;
						logEntry(mw.currentAbilitySource.name() + " — outgoing damage to opponent doubled ("
								+ amount + " → " + (amount * 2) + ")");
						amount *= 2;
						break;
					}
				}
				for (int i = 0; i < amount; i++) {
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
					String job, String category, EnumSet<CardData.Trait> traitFilter) {
				boolean touchP1 = isP1 ? !opponentOnly : !selfOnly;
				boolean touchP2 = isP1 ? !selfOnly     : !opponentOnly;
				if (touchP1) {
					if (forwards || monsters) {
						for (int i = mw.p1ForwardCards.size() - 1; i >= 0; i--) {
							CardData c = p1Forward(i);
							if (!forwards && !c.alsoCountsAsMonster()) continue;
							if (element != null && !c.containsElement(element)) continue;
							if (!meetsCostConstraint(c.cost(), costVal, costCmp)) continue;
							if (excludeCostVal >= 0 && c.cost() == excludeCostVal) continue;
							if (!mw.meetsJobFilterEffective(c, job)) continue;
							if (!meetsCategoryFilter(c, category)) continue;
							if (!forwardHasAnyTrait(true, i, traitFilter)) continue;
							switch (action) {
								case BREAK          -> breakP1Forward(i);
								case DULL           -> dullP1Forward(i);
								case FREEZE         -> freezeP1Forward(i);
								case DULL_AND_FREEZE -> { dullP1Forward(i); freezeP1Forward(i); }
								case ACTIVATE       -> { mw.p1ForwardStates.set(i, CardState.ACTIVE); mw.refreshP1ForwardSlot(i); }
								case RETURN_TO_HAND -> returnP1ForwardToHand(i);
							}
						}
					}
					if (backups) {
						for (int i = 0; i < mw.p1BackupCards.length; i++) {
							if (mw.p1BackupCards[i] == null) continue;
							CardData c = mw.p1BackupCards[i];
							if (element != null && !c.containsElement(element)) continue;
							if (!meetsCostConstraint(c.cost(), costVal, costCmp)) continue;
							if (excludeCostVal >= 0 && c.cost() == excludeCostVal) continue;
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
								case ACTIVATE       -> { mw.p1BackupStates[i] = CardState.ACTIVE; logEntry(c.name() + " is activated");       mw.refreshP1BackupSlot(i); }
								case RETURN_TO_HAND -> returnP1BackupToHand(i);
							}
						}
					}
					if (monsters) {
						for (int i = mw.p1MonsterCards.size() - 1; i >= 0; i--) {
							CardData c = mw.p1MonsterCards.get(i);
							if (element != null && !c.containsElement(element)) continue;
							if (!meetsCostConstraint(c.cost(), costVal, costCmp)) continue;
							if (excludeCostVal >= 0 && c.cost() == excludeCostVal) continue;
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
								case ACTIVATE       -> { mw.p1MonsterStates.set(i, CardState.ACTIVE); logEntry(c.name() + " is activated");       mw.refreshP1MonsterSlot(i); }
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
							if (element != null && !c.containsElement(element)) continue;
							if (!meetsCostConstraint(c.cost(), costVal, costCmp)) continue;
							if (excludeCostVal >= 0 && c.cost() == excludeCostVal) continue;
							if (!mw.meetsJobFilterEffective(c, job)) continue;
							if (!meetsCategoryFilter(c, category)) continue;
							if (!forwardHasAnyTrait(false, i, traitFilter)) continue;
							switch (action) {
								case BREAK          -> breakP2Forward(i);
								case DULL           -> dullP2Forward(i);
								case FREEZE         -> freezeP2Forward(i);
								case DULL_AND_FREEZE -> { dullP2Forward(i); freezeP2Forward(i); }
								case ACTIVATE       -> { mw.p2ForwardStates.set(i, CardState.ACTIVE); mw.refreshP2ForwardSlot(i); }
								case RETURN_TO_HAND -> returnP2ForwardToHand(i);
							}
						}
					}
					if (backups) {
						for (int i = 0; i < mw.p2BackupCards.length; i++) {
							if (mw.p2BackupCards[i] == null) continue;
							CardData c = mw.p2BackupCards[i];
							if (element != null && !c.containsElement(element)) continue;
							if (!meetsCostConstraint(c.cost(), costVal, costCmp)) continue;
							if (excludeCostVal >= 0 && c.cost() == excludeCostVal) continue;
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
								case ACTIVATE       -> { mw.p2BackupStates[i] = CardState.ACTIVE; logEntry("[P2] " + c.name() + " is activated");       mw.refreshP2BackupSlot(i); }
								case RETURN_TO_HAND -> returnP2BackupToHand(i);
							}
						}
					}
					if (monsters) {
						for (int i = mw.p2MonsterCards.size() - 1; i >= 0; i--) {
							CardData c = mw.p2MonsterCards.get(i);
							if (element != null && !c.containsElement(element)) continue;
							if (!meetsCostConstraint(c.cost(), costVal, costCmp)) continue;
							if (excludeCostVal >= 0 && c.cost() == excludeCostVal) continue;
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
								case ACTIVATE       -> { mw.p2MonsterStates.set(i, CardState.ACTIVE); logEntry("[P2] " + c.name() + " is activated");       mw.refreshP2MonsterSlot(i); }
								case RETURN_TO_HAND -> returnP2MonsterToHand(i);
							}
						}
					}
				}
			}

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
							if (element != null && !c.containsElement(element)) continue;
							if (!meetsCostConstraint(c.cost(), costVal, costCmp)) continue;
							if (!CardFilters.meetsCategoryFilter(c, category)) continue;
							if (excludeName != null && CardFilters.meetsCardNameFilter(c, excludeName)) continue;
							if (p1BoostSuppressed) { logEntry(c.name() + " — power boost suppressed"); continue; }
							mw.p1ForwardPowerBoost.set(i, mw.p1ForwardPowerBoost.get(i) + amount);
							logEntry(c.name() + " gains +" + amount + " power until end of turn");
							mw.refreshP1ForwardSlot(i);
						}
					}
					if (inclMonsters) {
						for (int i = 0; i < mw.p1MonsterCards.size(); i++) {
							CardData c = mw.p1MonsterCards.get(i);
							if (element != null && !c.containsElement(element)) continue;
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
							if (element != null && !c.containsElement(element)) continue;
							if (!meetsCostConstraint(c.cost(), costVal, costCmp)) continue;
							if (!CardFilters.meetsCategoryFilter(c, category)) continue;
							if (excludeName != null && CardFilters.meetsCardNameFilter(c, excludeName)) continue;
							if (p2BoostSuppressed) { logEntry("[P2] " + c.name() + " — power boost suppressed"); continue; }
							mw.p2ForwardPowerBoost.set(i, mw.p2ForwardPowerBoost.get(i) + amount);
							logEntry("[P2] " + c.name() + " gains +" + amount + " power until end of turn");
							mw.refreshP2ForwardSlot(i);
						}
					}
					if (inclMonsters) {
						for (int i = 0; i < mw.p2MonsterCards.size(); i++) {
							CardData c = mw.p2MonsterCards.get(i);
							if (element != null && !c.containsElement(element)) continue;
							if (!meetsCostConstraint(c.cost(), costVal, costCmp)) continue;
							if (!CardFilters.meetsCategoryFilter(c, category)) continue;
							if (excludeName != null && CardFilters.meetsCardNameFilter(c, excludeName)) continue;
							logEntry("[P2] " + c.name() + " gains +" + amount + " power until end of turn");
						}
					}
				}
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
						if (c.containsElement(e) && src.containsElement(e)) return true;
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
						if (element != null && !c.containsElement(element)) continue;
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
						if (element != null && !c.containsElement(element)) continue;
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
				if (!isP1) return false;
				int result = JOptionPane.showConfirmDialog(mw.frame, prompt, "You May", JOptionPane.YES_NO_OPTION);
				return result == JOptionPane.YES_OPTION;
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
				List<CardData> oppBz = isP1 ? mw.gameState.getP2BreakZone() : mw.gameState.getP1BreakZone();
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
					if (eitherBz && !mw.bzSummonsProtectedFromOppRfg(!isP1))
						for (CardData c : oppBz) if (c.isSummon() && !mw.bzPlayableP1.containsKey(c) && !mw.bzPlayableP2.containsKey(c)) candidates.add(c);
					if (candidates.isEmpty()) {
						if (picks == 0) logEntry("No eligible Summon in Break Zone — effect fizzles");
						return;
					}
					CardData picked = isP1
							? mw.chooseCardFromBzDialog(candidates, "Choose a Summon from the Break Zone")
							: candidates.get(0);
					if (picked == null) return;
					// Determine the card's true owner by which Break Zone it sits in.
					boolean inOwnBz = ownBz.remove(picked);
					if (!inOwnBz) oppBz.remove(picked);
					boolean cardOwnerIsP1 = inOwnBz ? isP1 : !isP1;
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

			@Override public void chooseSummonInBzByMaxCostFreeCastRfgAfterUse(int maxCost) {
				List<CardData> bz = isP1 ? mw.gameState.getP1BreakZone() : mw.gameState.getP2BreakZone();
				List<CardData> candidates = new ArrayList<>();
				for (CardData c : bz)
					if (c.isSummon() && c.cost() <= maxCost) candidates.add(c);
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

			@Override public int countP1FieldCards(boolean inclForwards, boolean inclBackups,
					boolean inclMonsters, String jobFilter, String cardNameFilter, String categoryFilter, String elementFilter, int costFilter) {
				int count = 0;
				if (inclForwards) for (CardData c : mw.p1ForwardCards) {
					if (!mw.meetsJobFilterEffective(c, jobFilter)) continue;
					if (!meetsCardNameFilter(c, cardNameFilter)) continue;
					if (!meetsCategoryFilter(c, categoryFilter)) continue;
					if (elementFilter != null && !c.containsElement(elementFilter)) continue;
					if (costFilter != -1 && c.cost() != costFilter) continue;
					count++;
				}
				if (inclBackups) for (CardData c : mw.p1BackupCards) {
					if (c == null) continue;
					if (!mw.meetsJobFilterEffective(c, jobFilter)) continue;
					if (!meetsCardNameFilter(c, cardNameFilter)) continue;
					if (!meetsCategoryFilter(c, categoryFilter)) continue;
					if (elementFilter != null && !c.containsElement(elementFilter)) continue;
					if (costFilter != -1 && c.cost() != costFilter) continue;
					count++;
				}
				if (inclMonsters) for (CardData c : mw.p1MonsterCards) {
					if (!mw.meetsJobFilterEffective(c, jobFilter)) continue;
					if (!meetsCardNameFilter(c, cardNameFilter)) continue;
					if (!meetsCategoryFilter(c, categoryFilter)) continue;
					if (elementFilter != null && !c.containsElement(elementFilter)) continue;
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

			@Override public int countP2FieldCards(boolean inclForwards, boolean inclBackups,
					boolean inclMonsters, String jobFilter, String cardNameFilter, String categoryFilter, String elementFilter, int costFilter) {
				int count = 0;
				if (inclForwards) for (CardData c : mw.p2ForwardCards) {
					if (!mw.meetsJobFilterEffective(c, jobFilter)) continue;
					if (!meetsCardNameFilter(c, cardNameFilter)) continue;
					if (!meetsCategoryFilter(c, categoryFilter)) continue;
					if (elementFilter != null && !c.containsElement(elementFilter)) continue;
					if (costFilter != -1 && c.cost() != costFilter) continue;
					count++;
				}
				if (inclBackups) for (CardData c : mw.p2BackupCards) {
					if (c == null) continue;
					if (!mw.meetsJobFilterEffective(c, jobFilter)) continue;
					if (!meetsCardNameFilter(c, cardNameFilter)) continue;
					if (!meetsCategoryFilter(c, categoryFilter)) continue;
					if (elementFilter != null && !c.containsElement(elementFilter)) continue;
					if (costFilter != -1 && c.cost() != costFilter) continue;
					count++;
				}
				if (inclMonsters) for (CardData c : mw.p2MonsterCards) {
					if (!mw.meetsJobFilterEffective(c, jobFilter)) continue;
					if (!meetsCardNameFilter(c, cardNameFilter)) continue;
					if (!meetsCategoryFilter(c, categoryFilter)) continue;
					if (elementFilter != null && !c.containsElement(elementFilter)) continue;
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
				return isP1 ? mw.p1ReceivedDamageThisTurn : mw.p2ReceivedDamageThisTurn;
			}

			@Override public boolean ownForwardFormedPartyThisTurn() {
				return isP1 ? mw.p1FormedPartyThisTurn : mw.p2FormedPartyThisTurn;
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

			@Override public int selfCardsCastThisTurn() { return isP1 ? mw.p1CardsCastThisTurn : mw.p2CardsCastThisTurn; }

			@Override public int countCardsNamedCastThisTurn(String name) {
				Map<String, Integer> counts = isP1 ? mw.p1CastCountByNameThisTurn : mw.p2CastCountByNameThisTurn;
				return counts.getOrDefault(name.toLowerCase(java.util.Locale.ROOT), 0);
			}

			@Override public boolean selfSummonCastThisTurn() { return isP1 ? mw.p1SummonCastThisTurn : mw.p2SummonCastThisTurn; }

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
				if (inclForwards) for (CardData c : fwds) if (element == null || c.containsElement(element)) count++;
				if (inclBackups)  for (CardData c : bkps) if (c != null && (element == null || c.containsElement(element))) count++;
				if (inclMonsters) for (CardData c : mons) if (element == null || c.containsElement(element)) count++;
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
					0, null, null, null, false, false, false, null, null, null, false, false, null, false, false, null, null, null, 0, null, -1, false, -1, null, null, null, false
				);
				Map<CardData, List<ActionAbility>> map = isP1 ? mw.p1TempGrantedAbilities : mw.p2TempGrantedAbilities;
				map.computeIfAbsent(source, k -> new ArrayList<>()).add(copy);
				mw.endOfTurnEffects.add(ctx -> {
					List<ActionAbility> list = map.get(source);
					if (list != null) { list.remove(copy); if (list.isEmpty()) map.remove(source); }
				});
				logEntry(source.name() + " gains " + original.abilityName() + " (free, once): " + original.effectText());
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
				List<String> candidates = NameSelectionDialogs.collectFieldJobs(
						isP1 ? mw.p1ForwardCards : mw.p2ForwardCards,
						isP1 ? mw.p1BackupCards  : mw.p2BackupCards,
						isP1 ? mw.p1MonsterCards : mw.p2MonsterCards);
				return NameSelectionDialogs.selectJob(mw.frame, candidates, isP1, mw::logEntry);
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
				return NameSelectionDialogs.selectElementAndJob(mw.frame, prompt, excluded, isP1, mw::logEntry);
			}

			@Override public String[] selectElementAndJob(String prompt) {
				return selectElementAndJob(prompt, java.util.Collections.emptySet());
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
				return NameSelectionDialogs.selectJobOrElement(mw.frame, prompt, isP1, mw::logEntry);
			}

			@Override public String[] selectJobOrCategory(String prompt) {
				return NameSelectionDialogs.selectJobOrCategory(mw.frame, prompt, isP1, mw::logEntry);
			}

			@Override public void revealTopAddUpToMatchingRestBottom(int reveal, int maxAdd,
					String jobFilter, String categoryFilter, String cardNameFilter, String typeFilter, int maxCost,
					String elementFilter) {
				Deque<CardData> deck = isP1 ? mw.gameState.getP1MainDeck() : mw.gameState.getP2MainDeck();
				int n = Math.min(reveal, deck.size());
				if (n == 0) { logEntry("Reveal top: deck is empty."); return; }
				List<CardData> peeked = new ArrayList<>();
				for (CardData c : deck) { peeked.add(c); if (peeked.size() >= n) break; }
				logEntry("Reveal top " + n + " card(s): " +
						peeked.stream().map(CardData::name).collect(Collectors.joining(", ")));
				if (!isP1 && mw.isP2Cpu()) {
					// CPU P2: auto-select — no dialog. Pick up to maxAdd eligible cards
					// (highest cost first, matching any of the filters), rest go to bottom.
					List<CardData> eligible = peeked.stream()
							.filter(c -> {
								if (maxCost >= 0 && c.cost() > maxCost) return false;
								if (elementFilter != null && !CardFilters.meetsElementFilter(c, elementFilter)) return false;
								boolean noFilters = jobFilter == null && categoryFilter == null
										&& cardNameFilter == null && typeFilter == null;
								if (noFilters) return true;
								return (jobFilter      != null && CardFilters.meetsJobFilter(c, jobFilter))
								    || (categoryFilter != null && CardFilters.meetsCategoryFilter(c, categoryFilter))
								    || (cardNameFilter != null && CardFilters.meetsCardNameFilter(c, cardNameFilter))
								    || (typeFilter     != null && meetsRevealTypeFilter(c, typeFilter));
							})
							.sorted(java.util.Comparator.comparingInt(CardData::cost).reversed())
							.collect(Collectors.toList());
					List<CardData> chosen = eligible.subList(0, Math.min(maxAdd, eligible.size()));
					Set<CardData> chosenSet = new java.util.LinkedHashSet<>(chosen);
					for (int i = 0; i < n; i++) deck.pollFirst();
					for (CardData c : chosenSet) {
						mw.gameState.getP2Hand().add(c);
						logEntry("[AI] " + c.name() + " → [P2] hand");
					}
					if (!chosenSet.isEmpty()) mw.refreshP2HandCountLabel();
					for (CardData c : peeked) {
						if (!chosenSet.contains(c)) { deck.addLast(c); logEntry("[AI] " + c.name() + " → [P2] bottom of deck"); }
					}
					mw.refreshP2DeckLabel();
				} else {
					mw.lookDialogs().showRevealAddUpToMatchingRestBottom(peeked, deck, isP1, maxAdd, jobFilter, categoryFilter, cardNameFilter, typeFilter, maxCost, elementFilter);
				}
			}

			@Override public void revealTopAddUpToExcludingNameRestBz(int reveal, int maxAdd, String excludeName) {
				Deque<CardData> deck = isP1 ? mw.gameState.getP1MainDeck() : mw.gameState.getP2MainDeck();
				int n = Math.min(reveal, deck.size());
				if (n == 0) { logEntry("Reveal top: deck is empty."); return; }
				List<CardData> peeked = new ArrayList<>();
				for (CardData c : deck) { peeked.add(c); if (peeked.size() >= n) break; }
				logEntry("Reveal top " + n + " card(s): " +
						peeked.stream().map(CardData::name).collect(Collectors.joining(", ")));
				if (!isP1 && mw.isP2Cpu()) {
					// CPU P2: auto-add up to maxAdd cards (highest cost first, skipping excluded name)
					List<CardData> eligible = peeked.stream()
							.filter(c -> !c.name().equalsIgnoreCase(excludeName))
							.sorted(java.util.Comparator.comparingInt(CardData::cost).reversed())
							.collect(Collectors.toList());
					Set<CardData> chosen = new java.util.LinkedHashSet<>(
							eligible.subList(0, Math.min(maxAdd, eligible.size())));
					for (int i = 0; i < n; i++) deck.pollFirst();
					for (CardData c : chosen) {
						mw.gameState.getP2Hand().add(c);
						logEntry("[AI] " + c.name() + " → [P2] hand");
					}
					if (!chosen.isEmpty()) mw.refreshP2HandCountLabel();
					for (CardData c : peeked) {
						if (!chosen.contains(c)) {
							mw.gameState.getP2BreakZone().add(c);
							logEntry("[AI] " + c.name() + " → [P2] Break Zone");
						}
					}
					mw.refreshP2DeckLabel();
				} else {
					mw.lookDialogs().showRevealAddUpToExcludingNameRestBz(peeked, deck, isP1, maxAdd, excludeName);
				}
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

			@Override public void revealTopNPlayUpToTypeOntoFieldRestBottom(int reveal, int maxPlay, String typeFilter, String categoryFilter) {
				Deque<CardData> deck = isP1 ? mw.gameState.getP1MainDeck() : mw.gameState.getP2MainDeck();
				int n = Math.min(reveal, deck.size());
				if (n == 0) { logEntry("Reveal top: deck is empty."); return; }
				List<CardData> peeked = new ArrayList<>();
				for (CardData c : deck) { peeked.add(c); if (peeked.size() >= n) break; }
				logEntry("Reveal top " + n + " card(s): " +
						peeked.stream().map(CardData::name).collect(Collectors.joining(", ")));
				Consumer<CardData> playOntoField = c -> {
					if (c.isBackup())       mw.placeCardInFirstBackupSlot(c);
					else if (c.isMonster()) mw.placeCardInMonsterZone(c);
					else                    mw.placeCardInForwardZone(c);
				};
				if (!isP1 && mw.isP2Cpu()) {
					List<CardData> eligible = peeked.stream()
							.filter(c -> meetsRevealTypeFilter(c, typeFilter)
									&& CardFilters.meetsCategoryFilter(c, categoryFilter))
							.sorted(java.util.Comparator.comparingInt(CardData::cost).reversed())
							.collect(Collectors.toList());
					List<CardData> chosen = eligible.subList(0, Math.min(maxPlay, eligible.size()));
					Set<CardData> chosenSet = new java.util.LinkedHashSet<>(chosen);
					for (int i = 0; i < n; i++) deck.pollFirst();
					for (CardData c : peeked) {
						if (!chosenSet.contains(c)) { deck.addLast(c); logEntry("[AI] " + c.name() + " → [P2] bottom of deck"); }
					}
					mw.refreshP2DeckLabel();
					for (CardData c : chosenSet) {
						logEntry("[AI] " + c.name() + " played onto field");
						playOntoField.accept(c);
					}
				} else {
					mw.lookDialogs().showRevealPlayTypeOntoFieldRestBottom(peeked, deck, isP1, maxPlay, typeFilter, categoryFilter, playOntoField);
				}
			}

			@Override public void revealTopNPlayUpToElementTypeCostOntoFieldRestBottom(int reveal, int maxPlay, String element, String typeFilter, int maxCost) {
				Deque<CardData> deck = isP1 ? mw.gameState.getP1MainDeck() : mw.gameState.getP2MainDeck();
				int n = Math.min(reveal, deck.size());
				if (n == 0) { logEntry("Reveal top: deck is empty."); return; }
				List<CardData> peeked = new ArrayList<>();
				for (CardData c : deck) { peeked.add(c); if (peeked.size() >= n) break; }
				logEntry("Reveal top " + n + " card(s): " +
						peeked.stream().map(CardData::name).collect(Collectors.joining(", ")));
				Consumer<CardData> playOntoField = c -> {
					if (c.isBackup())       mw.placeCardInFirstBackupSlot(c);
					else if (c.isMonster()) mw.placeCardInMonsterZone(c);
					else                    mw.placeCardInForwardZone(c);
				};
				java.util.function.Predicate<CardData> eligible = c ->
						meetsRevealTypeFilter(c, typeFilter)
						&& (element == null || c.containsElement(element))
						&& (maxCost < 0 || c.cost() <= maxCost);
				if (!isP1 && mw.isP2Cpu()) {
					List<CardData> chosen = peeked.stream().filter(eligible)
							.sorted(java.util.Comparator.comparingInt(CardData::cost).reversed())
							.limit(maxPlay)
							.collect(Collectors.toList());
					Set<CardData> chosenSet = new java.util.LinkedHashSet<>(chosen);
					for (int i = 0; i < n; i++) deck.pollFirst();
					for (CardData c : peeked) {
						if (!chosenSet.contains(c)) { deck.addLast(c); logEntry("[AI] " + c.name() + " → [P2] bottom of deck"); }
					}
					mw.refreshP2DeckLabel();
					for (CardData c : chosenSet) {
						logEntry("[AI] " + c.name() + " played onto field");
						playOntoField.accept(c);
					}
				} else {
					mw.lookDialogs().showRevealPlayElementTypeCostOntoFieldRestBottom(peeked, deck, isP1, maxPlay, element, typeFilter, maxCost, playOntoField);
				}
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
				Consumer<CardData> playOntoField = c -> {
					if (c.isBackup())       mw.placeCardInFirstBackupSlot(c);
					else if (c.isMonster()) mw.placeCardInMonsterZone(c);
					else                    mw.placeCardInForwardZone(c);
				};
				if (!isP1 && mw.isP2Cpu()) {
					// CPU: prefer playing onto field (more tempo), fall back to adding to hand
					List<CardData> fieldEligible = peeked.stream()
							.filter(c -> meetsRevealTypeFilter(c, fieldType)
									&& (fieldJob == null || CardFilters.meetsJobFilter(c, fieldJob)))
							.sorted(java.util.Comparator.comparingInt(CardData::cost).reversed())
							.collect(Collectors.toList());
					CardData chosen = fieldEligible.isEmpty() ? null : fieldEligible.get(0);
					String chosenDest = chosen != null ? "field" : null;
					if (chosen == null) {
						List<CardData> handEligible = peeked.stream()
								.filter(c -> meetsRevealTypeFilter(c, handType))
								.sorted(java.util.Comparator.comparingInt(CardData::cost).reversed())
								.collect(Collectors.toList());
						if (!handEligible.isEmpty()) { chosen = handEligible.get(0); chosenDest = "hand"; }
					}
					for (int i = 0; i < n; i++) deck.pollFirst();
					for (CardData c : peeked) {
						if (c == chosen) continue;
						deck.addLast(c); logEntry("[AI] " + c.name() + " → [P2] bottom of deck");
					}
					mw.refreshP2DeckLabel();
					if (chosen != null && "field".equals(chosenDest)) {
						logEntry("[AI] " + chosen.name() + " played onto field"); playOntoField.accept(chosen);
					} else if (chosen != null) {
						mw.gameState.getP2Hand().add(chosen);
						mw.refreshP2HandCountLabel();
						logEntry("[AI] " + chosen.name() + " → [P2] hand");
					}
				} else {
					mw.lookDialogs().showRevealAddTypeToHandOrPlayJobTypeOntoFieldRestBottom(
							peeked, deck, isP1, handMax, handType, fieldMax, fieldJob, fieldType, playOntoField);
				}
			}

			@Override public void revealTopNPlayNamedOntoFieldRestBottom(int reveal, String cardName) {
				Deque<CardData> deck = isP1 ? mw.gameState.getP1MainDeck() : mw.gameState.getP2MainDeck();
				int n = Math.min(reveal, deck.size());
				if (n == 0) { logEntry("Reveal top: deck is empty."); return; }
				List<CardData> peeked = new ArrayList<>();
				for (CardData c : deck) { peeked.add(c); if (peeked.size() >= n) break; }
				logEntry("Reveal top " + n + " card(s): " +
						peeked.stream().map(CardData::name).collect(Collectors.joining(", ")));
				if (!isP1 && mw.isP2Cpu()) {
					CardData chosen = peeked.stream()
							.filter(c -> c.name().equalsIgnoreCase(cardName))
							.findFirst().orElse(null);
					for (int i = 0; i < n; i++) deck.pollFirst();
					for (CardData c : peeked) {
						if (c == chosen) continue;
						deck.addLast(c);
						logEntry("[AI] " + c.name() + " → [P2] bottom of deck");
					}
					mw.refreshP2DeckLabel();
					if (chosen != null) {
						logEntry("[AI] " + chosen.name() + " played onto field");
						mw.placeCardInForwardZone(chosen);
					} else {
						logEntry("[AI] No Card Name " + cardName + " found — all cards to bottom");
					}
				} else {
					Consumer<CardData> playOntoField = c -> {
					if (c.isBackup())       mw.placeCardInFirstBackupSlot(c);
					else if (c.isMonster()) mw.placeCardInMonsterZone(c);
					else                    mw.placeCardInForwardZone(c);
				};
				mw.lookDialogs().showRevealPlayNamedOntoFieldRestBottom(peeked, deck, isP1, cardName, playOntoField);
				}
			}

			@Override public void revealTopNPlayNamedWithMaxCostOntoFieldRestBottom(int reveal, String cardName, int maxCost) {
				Deque<CardData> deck = isP1 ? mw.gameState.getP1MainDeck() : mw.gameState.getP2MainDeck();
				int n = Math.min(reveal, deck.size());
				if (n == 0) { logEntry("Reveal top: deck is empty."); return; }
				List<CardData> peeked = new ArrayList<>();
				for (CardData c : deck) { peeked.add(c); if (peeked.size() >= n) break; }
				logEntry("Reveal top " + n + " card(s): " +
						peeked.stream().map(CardData::name).collect(Collectors.joining(", ")));
				if (!isP1 && mw.isP2Cpu()) {
					CardData chosen = peeked.stream()
							.filter(c -> c.name().equalsIgnoreCase(cardName) && c.cost() <= maxCost)
							.findFirst().orElse(null);
					for (int i = 0; i < n; i++) deck.pollFirst();
					for (CardData c : peeked) {
						if (c == chosen) continue;
						deck.addLast(c);
						logEntry("[AI] " + c.name() + " → [P2] bottom of deck");
					}
					mw.refreshP2DeckLabel();
					if (chosen != null) {
						logEntry("[AI] " + chosen.name() + " played onto field");
						mw.placeCardInForwardZone(chosen);
					} else {
						logEntry("[AI] No Card Name " + cardName + " of cost ≤ " + maxCost + " found — all cards to bottom");
					}
				} else {
					Consumer<CardData> playOntoField = c -> {
						if (c.isBackup())       mw.placeCardInFirstBackupSlot(c);
						else if (c.isMonster()) mw.placeCardInMonsterZone(c);
						else                    mw.placeCardInForwardZone(c);
					};
					mw.lookDialogs().showRevealPlayNamedOntoFieldRestBottom(peeked, deck, isP1, cardName, maxCost, playOntoField);
				}
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
						mw.p2DiscardedByEffectThisTurn = true;
						mw.p1CausedOpponentDiscardThisTurn = true;
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
						mw.p1DiscardedByEffectThisTurn = true;
						mw.p2CausedOpponentDiscardThisTurn = true;
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
					mw.p1PartyAnyElementThisTurn = true;
					mw.endOfTurnEffects.add(x -> mw.p1PartyAnyElementThisTurn = false);
				} else {
					mw.p2PartyAnyElementThisTurn = true;
					mw.endOfTurnEffects.add(x -> mw.p2PartyAnyElementThisTurn = false);
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
