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

import static shufflingway.CardAnimation.CARD_H;
import static shufflingway.CardAnimation.CARD_W;
import static shufflingway.CardFilters.isBlockingTargetFilter;
import static shufflingway.CardFilters.isEnteredThisTurnCondition;
import static shufflingway.CardFilters.matchesDiscardType;
import static shufflingway.CardFilters.meetsCardNameFilter;
import static shufflingway.CardFilters.meetsCategoryFilter;
import static shufflingway.CardFilters.meetsCostConstraint;
import static shufflingway.CardFilters.meetsElementExclusion;
import static shufflingway.CardFilters.meetsElementFilter;
import static shufflingway.CardFilters.meetsPowerConstraint;
import static shufflingway.CardFilters.meetsTargetCondition;

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

	GameContextImpl(MainWindow mw, boolean isP1, boolean exBurst) {
		this.mw = mw;
		this.isP1 = isP1;
		this.exBurst = exBurst;
	}

			@Override public void logEntry(String msg) { mw.logEntry(msg); }
			@Override public boolean isP1() { return isP1; }

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

			@Override public void doubleOpponentForwardIncomingDamage() {
				if (isP1) {
					mw.p2ForwardIncomingDmgMult *= 2;
					logEntry("Opponent's Forwards — incoming damage ×" + mw.p2ForwardIncomingDmgMult + " until end of turn");
				} else {
					mw.p1ForwardIncomingDmgMult *= 2;
					logEntry("Opponent's Forwards — incoming damage ×" + mw.p1ForwardIncomingDmgMult + " until end of turn");
				}
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
				CardData c = mw.autoAbilityTriggers.fieldCardData(t);
				if (c == null) return;
				mw.cannotBeBrokenSet.add(c);
				logEntry((t.isP1() ? "" : "[P2] ") + c.name() + " cannot be broken until end of turn");
			}

			@Override public void shieldCannotBeBrokenByNonDmg(ForwardTarget t) {
				CardData c = mw.autoAbilityTriggers.fieldCardData(t);
				if (c == null) return;
				mw.cannotBeBrokenByNonDmgSet.add(c);
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
				for (CardData c : fwds) {
					if (c.name().equalsIgnoreCase(source.name())) {
						mw.cannotBeBrokenSet.add(c);
						logEntry((isP1 ? "" : "[P2] ") + c.name() + " cannot be broken until end of turn");
						return;
					}
				}
			}

			@Override public void shieldAllOwnForwards() {
				List<CardData> fwds = isP1 ? mw.p1ForwardCards : mw.p2ForwardCards;
				for (CardData c : fwds) {
					mw.cannotBeBrokenSet.add(c);
					logEntry((isP1 ? "" : "[P2] ") + c.name() + " cannot be broken until end of turn");
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
			public java.util.List<ForwardTarget> selectCharacters(
					int maxCount, boolean upTo, boolean opponentOnly,
					boolean selfOnly, String condition, String element,
					int costVal, String costCmp, int powerVal, String powerCmp,
					boolean inclForwards, boolean inclBackups, boolean inclMonsters,
					String jobFilter, String cardNameFilter, String categoryFilter, String excludeName, boolean inclSummons,
					String excludeElement, boolean withoutMulticard) {
				java.util.List<ForwardTarget> eligible = new ArrayList<>();
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
				String costLabel  = costVal  >= 0 ? " of cost "  + costVal  + (costCmp  != null ? " or " + costCmp  : "") : "";
				String powerLabel = powerVal >= 0 ? " of power " + powerVal + (powerCmp != null ? " or " + powerCmp : "") : "";
				String targetNoun = inclForwards && !inclBackups && !inclMonsters ? "Forward"
						: inclBackups && !inclForwards && !inclMonsters ? "Backup"
						: inclMonsters && !inclForwards && !inclBackups ? "Monster"
						: "Character";
				String title = "Choose " + (upTo ? "up to " : "") + maxCount
						+ (condition != null ? " " + condition : "")
						+ (element != null ? " " + element : "")
						+ " " + targetNoun + (maxCount != 1 ? "s" : "") + costLabel + powerLabel
						+ (opponentOnly ? " (opponent)" : selfOnly ? " (yours)" : "");
				if (!isP1) {
					// AI (P2 controls the effect): auto-select rather than prompting the human.
					if (eligible.isEmpty()) return java.util.List.of();
					// For unqualified targeting, prefer opponent (P1) targets over own cards.
					java.util.List<ForwardTarget> pool = eligible;
					if (!opponentOnly && !selfOnly) {
						java.util.List<ForwardTarget> oppTargets = eligible.stream()
								.filter(ForwardTarget::isP1).toList();
						if (!oppTargets.isEmpty()) pool = oppTargets;
					}
					java.util.List<ForwardTarget> copy = new ArrayList<>(pool);
					java.util.Collections.shuffle(copy);
					java.util.List<ForwardTarget> picked = java.util.List.copyOf(copy.subList(0, Math.min(maxCount, copy.size())));
					picked.forEach(t -> {
						CardData c = switch (t.zone()) {
							case BACKUP  -> t.isP1() ? mw.p1BackupCards[t.idx()] : mw.p2BackupCards[t.idx()];
							case MONSTER -> t.isP1() ? mw.p1MonsterCards.get(t.idx()) : mw.p2MonsterCards.get(t.idx());
							default      -> t.isP1() ? p1Forward(t.idx()) : mw.p2ForwardCards.get(t.idx());
						};
						logEntry("[AI] chose " + c.name());
					});
					return picked;
				}
				return mw.showForwardSelectDialog(eligible, maxCount, upTo, title);
			}

			@Override public void dullP1Forward(int idx) {
				if (idx >= mw.p1ForwardStates.size()) return;
				mw.p1ForwardStates.set(idx, CardState.DULL);
				logEntry(p1Forward(idx).name() + " is dulled");
				mw.animateDullForward(idx, null);
			}

			@Override public void dullP2Forward(int idx) {
				if (idx >= mw.p2ForwardStates.size()) return;
				mw.p2ForwardStates.set(idx, CardState.DULL);
				logEntry("[P2] " + mw.p2ForwardCards.get(idx).name() + " is dulled");
				mw.animateDullP2Forward(idx, null);
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

			@Override public void playAllByNameFromOwnBreakZoneDull(String cardName, boolean dull) {
				java.util.List<CardData> bz = isP1 ? mw.gameState.getP1BreakZone() : mw.gameState.getP2BreakZone();
				java.util.List<CardData> toPlay = new java.util.ArrayList<>();
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

			@Override public void breakP1Forward(int idx) { mw.breakP1Forward(idx); }
			@Override public void breakP2Forward(int idx) { mw.breakP2Forward(idx); }

			@Override public void removeP1ForwardFromGame(int idx) {
				if (idx >= mw.p1ForwardCards.size()) return;
				logEntry(p1Forward(idx).name() + " → Removed From Game");
				List<CardData> bz = mw.gameState.getP1BreakZone();
				int before = bz.size();
				mw.breakP1Forward(idx);
				while (bz.size() > before)
					mw.gameState.addToP1PermanentRfp(bz.remove(bz.size() - 1));
				mw.refreshP1BreakLabel();
				mw.refreshP1WarpZoneUI();
			}

			@Override public void removeP2ForwardFromGame(int idx) {
				if (idx >= mw.p2ForwardCards.size()) return;
				logEntry("[P2] " + mw.p2ForwardCards.get(idx).name() + " → Removed From Game");
				List<CardData> bz = mw.gameState.getP2BreakZone();
				int before = bz.size();
				mw.breakP2Forward(idx);
				while (bz.size() > before)
					mw.gameState.addToP2PermanentRfp(bz.remove(bz.size() - 1));
				mw.refreshP2BreakLabel();
			}

			@Override
			public java.util.List<ForwardTarget> selectCharactersFromBreakZone(
					int maxCount, boolean upTo, boolean opponentZone,
					String condition, String element, int costVal, String costCmp,
					int powerVal, String powerCmp,
					boolean inclForwards, boolean inclBackups, boolean inclMonsters,
					String jobFilter, String cardNameFilter, String categoryFilter, String excludeName, boolean inclSummons,
					String excludeElement, boolean withoutMulticard) {
				java.util.List<CardData> bz = opponentZone
						? mw.gameState.getP2BreakZone() : mw.gameState.getP1BreakZone();
				java.util.List<ForwardTarget> eligible = new ArrayList<>();
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
					ForwardTarget.CardZone cz = card.isBackup()  ? ForwardTarget.CardZone.BACKUP
					                         : card.isMonster() ? ForwardTarget.CardZone.MONSTER
					                         :                    ForwardTarget.CardZone.FORWARD;
					eligible.add(new ForwardTarget(!opponentZone, i, cz));
				}
				String costLabel  = costVal  >= 0 ? " of cost "  + costVal  + (costCmp  != null ? " or " + costCmp  : "") : "";
				String powerLabel = powerVal >= 0 ? " of power " + powerVal + (powerCmp != null ? " or " + powerCmp : "") : "";
				String title = "Choose " + (upTo ? "up to " : "") + maxCount
						+ (element != null ? " " + element : "")
						+ " Character" + (maxCount != 1 ? "s" : "") + costLabel + powerLabel
						+ " in " + (opponentZone ? "opponent's" : "your") + " Break Zone";
				if (!isP1) {
					if (eligible.isEmpty()) return java.util.List.of();
					java.util.List<ForwardTarget> copy = new ArrayList<>(eligible);
					java.util.Collections.shuffle(copy);
					java.util.List<ForwardTarget> picked =
							java.util.List.copyOf(copy.subList(0, Math.min(maxCount, copy.size())));
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

			@Override public void forceTargetToBreakZone(ForwardTarget t) {
				switch (t.zone()) {
					case FORWARD -> { if (t.isP1()) breakP1Forward(t.idx()); else breakP2Forward(t.idx()); }
					case BACKUP  -> { if (t.isP1()) mw.autoAbilityTriggers.breakP1BackupSlot(t.idx()); else mw.breakP2BackupSlot(t.idx()); }
					case MONSTER -> { if (t.isP1()) mw.autoAbilityTriggers.breakP1MonsterSlot(t.idx()); else mw.breakP2MonsterSlot(t.idx()); }
				}
			}

			@Override public void opponentMillCards(int count) {
				java.util.Deque<CardData> deck = mw.gameState.getP2MainDeck();
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
					mw.addToP2BreakZone(card);
					logEntry("[P2] Mill: \"" + card.name() + "\" → Break Zone");
					mw.cardSlideAnimator.startSlide(img, start, end, i * 5);
					milled++;
				}
				if (milled > 0) {
					mw.refreshP2DeckLabel();
					mw.refreshP2BreakLabel();
				}
			}

			@Override public void millCards(int count) {
				java.util.Deque<CardData> deck = mw.gameState.getP1MainDeck();
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
					mw.addToP1BreakZone(card);
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
				java.util.List<CardData> hand = mw.gameState.getP2Hand();
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
				JLabel countdownLabel = new JLabel("Closing in 10...", SwingConstants.CENTER);
				countdownLabel.setFont(FontLoader.loadPixelNESFont(10));

				JButton okBtn = new JButton("OK");
				okBtn.setFont(FontLoader.loadPixelNESFont(11));
				okBtn.addActionListener(ae -> { mw.hideZoom(); dlg.dispose(); });

				JPanel south = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 6));
				south.add(countdownLabel);
				south.add(okBtn);
				south.setBorder(BorderFactory.createEmptyBorder(0, 8, 8, 8));

				dlg.getContentPane().setLayout(new BorderLayout(0, 4));
				dlg.getContentPane().add(scrollPane, BorderLayout.CENTER);
				dlg.getContentPane().add(south,      BorderLayout.SOUTH);
				dlg.pack();
				dlg.setLocationRelativeTo(mw.frame);
				dlg.setVisible(true);

				Timer[] timerRef = { null };
				timerRef[0] = new Timer(1000, null);
				timerRef[0].addActionListener(te -> {
					countdown[0]--;
					if (countdown[0] <= 0) { timerRef[0].stop(); mw.hideZoom(); dlg.dispose(); }
					else countdownLabel.setText("Closing in " + countdown[0] + "...");
				});
				timerRef[0].start();
			}

			@Override public void revealTopDeckCard(java.util.List<RevealClause> clauses, boolean opponentDeck) {
				if (!isP1) {
					logEntry("[P2] Reveal top deck card — not yet implemented for P2");
					return;
				}
				java.util.Deque<CardData> deck = opponentDeck
						? mw.gameState.getP2MainDeck()
						: mw.gameState.getP1MainDeck();
				String deckLabel = opponentDeck ? "opponent's deck" : "your deck";
				if (deck.isEmpty()) {
					logEntry("Reveal: " + deckLabel + " is empty.");
					return;
				}
				CardData card = deck.pollFirst();
				logEntry("Revealed from " + deckLabel + ": " + card.name() + " (" + card.type() + ")");

				// When the only applicable clause is "castSummonFree" and the card is a Summon,
				// show Decline/OK buttons so the player can choose whether to cast it.
				boolean castFreeApplicable = card.isSummon() &&
						clauses.stream().anyMatch(c -> "castSummonFree".equals(c.cardOp()));
				boolean[] activated = {false};

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

				// Find the first matching clause and execute its action
				for (RevealClause clause : clauses) {
					if (!clause.condition().test(card)) continue;
					logEntry("Condition matched for " + card.name());
					if (clause.cardOp() != null) {
						switch (clause.cardOp()) {
							case "playOntoField" -> {
								logEntry(card.name() + " played from reveal onto field");
								if (card.isBackup())       mw.placeCardInFirstBackupSlot(card);
								else if (card.isMonster()) mw.placeCardInMonsterZone(card);
								else                       mw.placeCardInForwardZone(card);
							}
							case "playOntoFieldDull" -> {
								logEntry(card.name() + " played from reveal onto field (dull)");
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
							}
							case "addToHand" -> {
								mw.gameState.getP1Hand().add(card);
								mw.animateCardDraw(true, 1);
								logEntry(card.name() + " added to hand from reveal");
								mw.refreshP1HandLabel();
							}
							case "putToBreakZone" -> {
								mw.addToP1BreakZone(card);
								logEntry(card.name() + " put into Break Zone from reveal");
								mw.refreshP1BreakLabel();
							}
							case "castSummonFree" -> {
								if (!activated[0]) {
									logEntry(card.name() + " — free cast declined, returned to top of deck");
									deck.addFirst(card);
									if (opponentDeck) mw.refreshP2DeckLabel(); else mw.refreshP1DeckLabel();
									return;
								}
								logEntry(card.name() + " — cast for free from reveal");
								mw.showSummonOnStack(card);
							}
						}
					} else {
						// Standalone effect — return card to top of appropriate deck first
						// so any subsequent draw includes it
						deck.addFirst(card);
						if (opponentDeck) mw.refreshP2DeckLabel(); else mw.refreshP1DeckLabel();
						clause.effect().accept(this);
					}
					if (opponentDeck) mw.refreshP2DeckLabel(); else mw.refreshP1DeckLabel();
					return;
				}
				// No clause matched — put card back on top
				logEntry("No condition matched — returning " + card.name() + " to top of " + deckLabel);
				deck.addFirst(card);
				if (opponentDeck) mw.refreshP2DeckLabel(); else mw.refreshP1DeckLabel();
			}

			@Override public void playCharacterFromHand(boolean inclForwards, boolean inclBackups,
					boolean inclMonsters, int costVal, String costCmp, int costVal2,
					String jobFilter, String cardNameFilter, String categoryFilter,
					String elementFilter, String excludeName, boolean entersDull, String excludeElement,
					boolean suppressAutoAbility) {
				java.util.List<CardData> hand = mw.gameState.getP1Hand();
				java.util.List<Integer> eligible = new ArrayList<>();
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
					eligible.add(i);
				}
				if (eligible.isEmpty()) {
					logEntry("No eligible cards in hand to play.");
					markEffectFizzled();
					return;
				}
				java.util.List<CardData> candidates = new ArrayList<>();
				for (int i : eligible) candidates.add(hand.get(i));
				int listIdx = mw.showCardImageChooser(candidates, "Play a card onto the field", true, false);
				if (listIdx < 0) { markEffectFizzled(); return; }
				int handIdx = eligible.get(listIdx);
				CardData card = hand.remove(handIdx);
				logEntry(card.name() + " played from hand onto field" + (entersDull ? " (dull)" : "")
						+ (suppressAutoAbility ? " (no ETF auto-ability)" : ""));
				if (suppressAutoAbility) mw.suppressAutoAbilityForNextCard = true;
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
				if (breakCard != null && mw.cannotBeBrokenSet.contains(breakCard)) {
					logEntry((t.isP1() ? "" : "[P2] ") + breakCard.name() + " cannot be broken (protected until end of turn)");
					return;
				}
				if (breakCard != null && mw.cannotBeBrokenByNonDmgSet.contains(breakCard)) {
					logEntry((t.isP1() ? "" : "[P2] ") + breakCard.name() + " cannot be broken by this effect (protected from non-damage breaks until end of turn)");
					return;
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
						java.util.List<CardData> cards = t.isP1() ? mw.p1MonsterCards : mw.p2MonsterCards;
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
						if (t.isP1()) mw.gameState.addToP1PermanentRfp(cards[i]); else mw.gameState.addToP2PermanentRfp(cards[i]);
						cards[i] = null; states[i] = CardState.ACTIVE;
						if (t.isP1()) mw.refreshP1BackupSlot(i); else mw.refreshP2BackupSlot(i);
					}
					case MONSTER -> {
						int i = t.idx();
						java.util.List<CardData> cards = t.isP1() ? mw.p1MonsterCards : mw.p2MonsterCards;
						if (i >= cards.size()) return;
						CardData c = cards.get(i);
						logEntry((t.isP1() ? "" : "[P2] ") + c.name() + " → Removed From Game");
						if (t.isP1()) mw.gameState.addToP1PermanentRfp(c); else mw.gameState.addToP2PermanentRfp(c);
						cards.remove(i);
						(t.isP1() ? mw.p1MonsterStates : mw.p2MonsterStates).remove(i);
						(t.isP1() ? mw.p1MonsterFrozen : mw.p2MonsterFrozen).remove(i);
						(t.isP1() ? mw.p1MonsterPlayedOnTurn : mw.p2MonsterPlayedOnTurn).remove(i);
						(t.isP1() ? mw.p1MonsterUrls : mw.p2MonsterUrls).remove(i);
						JLabel lbl = (t.isP1() ? mw.p1MonsterLabels : mw.p2MonsterLabels).remove(i);
						JPanel panel = t.isP1() ? mw.p1MonsterPanel : mw.p2MonsterPanel;
						panel.remove(lbl); panel.revalidate(); panel.repaint();
					}
				}
			}

			@Override public void removeTopCardsOfDeckFromGame(int count) {
				java.util.Deque<CardData> deck = isP1 ? mw.gameState.getP1MainDeck() : mw.gameState.getP2MainDeck();
				for (int i = 0; i < count && !deck.isEmpty(); i++) {
					CardData c = deck.pollFirst();
					if (isP1) mw.gameState.addToP1PermanentRfp(c); else mw.gameState.addToP2PermanentRfp(c);
					logEntry(c.name() + " → Removed From Game (top of deck)");
				}
				if (isP1) mw.refreshP1DeckLabel(); else mw.refreshP2DeckLabel();
			}

			@Override public int removeTopCardOfDeckFromGameAndGetCost() {
				java.util.Deque<CardData> deck = isP1 ? mw.gameState.getP1MainDeck() : mw.gameState.getP2MainDeck();
				if (deck.isEmpty()) { logEntry("Deck is empty — no card removed"); return 0; }
				CardData c = deck.pollFirst();
				if (isP1) mw.gameState.addToP1PermanentRfp(c); else mw.gameState.addToP2PermanentRfp(c);
				logEntry(c.name() + " → Removed From Game (top of deck, cost=" + c.cost() + ")");
				if (isP1) mw.refreshP1DeckLabel(); else mw.refreshP2DeckLabel();
				return c.cost();
			}

			@Override public void shuffleDeck() {
				java.util.Deque<CardData> deck = isP1 ? mw.gameState.getP1MainDeck() : mw.gameState.getP2MainDeck();
				java.util.List<CardData> list = new java.util.ArrayList<>(deck);
				java.util.Collections.shuffle(list);
				deck.clear();
				deck.addAll(list);
				if (isP1) mw.refreshP1DeckLabel(); else mw.refreshP2DeckLabel();
				logEntry("Shuffled deck");
			}

			@Override public void playTargetOntoField(ForwardTarget t) {
				java.util.List<CardData> bz = t.isP1() ? mw.gameState.getP1BreakZone() : mw.gameState.getP2BreakZone();
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

			@Override public void addTargetToHand(ForwardTarget t) {
				java.util.List<CardData> bz = t.isP1() ? mw.gameState.getP1BreakZone() : mw.gameState.getP2BreakZone();
				if (t.idx() >= bz.size()) return;
				CardData card = bz.remove(t.idx());
				mw.gameState.getP1Hand().add(card);
				logEntry(card.name() + (t.isP1() ? " returned from Break Zone to hand" : " taken from opponent's Break Zone to hand"));
				if (t.isP1()) mw.refreshP1BreakLabel(); else mw.refreshP2BreakLabel();
				mw.refreshP1HandLabel();
			}

			@Override public CardData p1BreakZoneCard(int idx) {
				java.util.List<CardData> bz = mw.gameState.getP1BreakZone();
				return (idx >= 0 && idx < bz.size()) ? bz.get(idx) : null;
			}

			@Override public CardData p2BreakZoneCard(int idx) {
				java.util.List<CardData> bz = mw.gameState.getP2BreakZone();
				return (idx >= 0 && idx < bz.size()) ? bz.get(idx) : null;
			}

			@Override public void boostTarget(ForwardTarget t, int amount,
					java.util.EnumSet<CardData.Trait> traits) {
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
					java.util.EnumSet<CardData.Trait> bGranted = java.util.EnumSet.noneOf(CardData.Trait.class);
					if (!traits.isEmpty()) {
						(isP1 ? mw.p1BackupTempTraits : mw.p2BackupTempTraits)
								.computeIfAbsent(bcard, k -> java.util.EnumSet.noneOf(CardData.Trait.class))
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
				java.util.EnumSet<CardData.Trait> grantedTraits = java.util.EnumSet.noneOf(CardData.Trait.class);
				if (monster) {
					(isP1 ? mw.p1MonsterPowerBoost : mw.p2MonsterPowerBoost).merge(card, amount, Integer::sum);
					boolean asForward = isP1 ? mw.isP1MonsterTemporarilyForward(idx)
					                         : mw.isP2MonsterTemporarilyForward(idx);
					if (asForward && !traits.isEmpty()) {
						(isP1 ? mw.p1MonsterTempTraits : mw.p2MonsterTempTraits)
								.computeIfAbsent(card, k -> java.util.EnumSet.noneOf(CardData.Trait.class))
								.addAll(traits);
						grantedTraits.addAll(traits);
					}
				} else {
					List<Integer> boost = isP1 ? mw.p1ForwardPowerBoost : mw.p2ForwardPowerBoost;
					boost.set(idx, boost.get(idx) + amount);
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

			@Override public void setSourceForwardCannotBeBlocked(CardData source) {
				for (int i = 0; i < mw.p1ForwardCards.size(); i++) {
					if (mw.p1ForwardCards.get(i).name().equals(source.name())) {
						setP1ForwardCannotBeBlocked(i);
						return;
					}
				}
			}

			@Override public void boostSourceForward(CardData source, int amount,
					java.util.EnumSet<CardData.Trait> traits) {
				for (int i = 0; i < mw.p1ForwardCards.size(); i++) {
					if (mw.p1ForwardCards.get(i).name().equals(source.name())) {
						mw.p1ForwardPowerBoost.set(i, mw.p1ForwardPowerBoost.get(i) + amount);
						mw.p1ForwardTempTraits.get(i).addAll(traits);
						logEntry(source.name() + " gains +" + amount + " power until end of turn");
						mw.refreshP1ForwardSlot(i);
						return;
					}
				}
			}

			@Override public void doubleSourceForwardPower(CardData source,
					java.util.EnumSet<CardData.Trait> traits) {
				for (int i = 0; i < mw.p1ForwardCards.size(); i++) {
					if (mw.p1ForwardCards.get(i).name().equals(source.name())) {
						int current = mw.effectiveP1ForwardPower(i);
						mw.p1ForwardPowerBoost.set(i, mw.p1ForwardPowerBoost.get(i) + current);
						mw.p1ForwardTempTraits.get(i).addAll(traits);
						logEntry(source.name() + " — power doubled to " + (current * 2) + " until end of turn");
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

			@Override public void lookAtTopDeck(LookConfig config) {
				mw.lookDialogs().show(config, isP1);
			}

			@Override public void reduceTarget(ForwardTarget t, int amount,
					java.util.EnumSet<CardData.Trait> traits) {
				if (t.zone() == ForwardTarget.CardZone.BACKUP) return;
				if (t.isP1()) {
					int idx = t.idx();
					if (idx >= mw.p1ForwardCards.size()) return;
					mw.p1ForwardPowerReduction.set(idx, mw.p1ForwardPowerReduction.get(idx) + amount);
					mw.p1ForwardRemovedTraits.get(idx).addAll(traits);
					int effPow = mw.effectiveP1ForwardPower(idx);
					logEntry(p1Forward(idx).name() + " loses " + (amount > 0 ? amount + " power" : "")
							+ (!traits.isEmpty() ? (amount > 0 ? " and " : "") + traits : "") + " until end of turn");
					if (effPow <= 0) {
						logEntry(p1Forward(idx).name() + " reduced to 0 power → Break Zone");
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
						breakP2Forward(idx);
					} else {
						mw.refreshP2ForwardSlot(idx);
					}
				}
			}

			@Override public void reduceSourceForward(CardData source, int amount,
					java.util.EnumSet<CardData.Trait> traits) {
				for (int i = 0; i < mw.p1ForwardCards.size(); i++) {
					if (mw.p1ForwardCards.get(i).name().equals(source.name())) {
						reduceTarget(new ForwardTarget(true, i, ForwardTarget.CardZone.FORWARD), amount, traits);
						return;
					}
				}
			}

			@Override public int dullForwardCostPower() { return mw.lastDullForwardCostPower; }
			@Override public int lastDiscardedForwardPower() { return mw.lastDiscardedForwardPower; }

			@Override public int highestP1ForwardPower() {
				int max = 0;
				for (int i = 0; i < mw.p1ForwardCards.size(); i++)
					max = Math.max(max, mw.effectiveP1ForwardPower(i));
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
						mw.gameState.addToP2PermanentRfp(d);
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
						if (d != null) { mw.gameState.addToP1PermanentRfp(d); logEntry("[P1] Randomly removed from game: " + d.name()); }
					}
					mw.refreshP1HandLabel();
					mw.refreshP1WarpZoneUI();
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
						if (d != null) { mw.gameState.addToP1PermanentRfp(d); logEntry("[P2 AI selects from P1 hand] " + d.name() + " removed from game"); }
					}
					mw.refreshP1HandLabel();
					mw.refreshP1WarpZoneUI();
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
						mw.gameState.addToP2PermanentRfp(d);
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
						mw.gameState.addToP1PermanentRfp(mw.p1BackupCards[i]);
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
						mw.gameState.addToP2PermanentRfp(mw.p2BackupCards[i]);
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
						if (d != null) { logEntry("[P2] Discards " + d.name()); mw.p2DiscardedByEffectThisTurn = true; }
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
						CardData d = mw.playerBreakFromHand(true,i);
						if (d != null) { logEntry("Discards " + d.name()); mw.p1DiscardedByEffectThisTurn = true; }
					}
					mw.refreshP1HandLabel();
					mw.refreshP1BreakLabel();
				} else {
					List<CardData> hand = mw.gameState.getP2Hand();
					for (int i = hand.size() - 1; i >= 0; i--) {
						CardData d = mw.playerBreakFromHand(false,i);
						if (d != null) { logEntry("[P2] Discards " + d.name()); mw.p2DiscardedByEffectThisTurn = true; }
					}
					mw.refreshP2HandCountLabel();
					mw.refreshP2BreakLabel();
				}
			}

			@Override public void dealDamageToOpponent(int amount) {
				for (int i = 0; i < amount; i++) {
					if (isP1) mw.p2TakeDamage(); else mw.p1TakeDamage();
				}
			}

			@Override public void dealDamageToSelf(int amount) {
				for (int i = 0; i < amount; i++) {
					if (isP1) mw.p1TakeDamage(); else mw.p2TakeDamage();
				}
			}

			private boolean forwardHasAnyTrait(boolean p1Side, int idx, java.util.EnumSet<CardData.Trait> traitFilter) {
				if (traitFilter.isEmpty()) return true;
				java.util.List<java.util.EnumSet<CardData.Trait>> tempList = p1Side ? mw.p1ForwardTempTraits : mw.p2ForwardTempTraits;
				java.util.List<java.util.EnumSet<CardData.Trait>> rmList   = p1Side ? mw.p1ForwardRemovedTraits : mw.p2ForwardRemovedTraits;
				CardData c = p1Side ? p1Forward(idx) : mw.p2ForwardCards.get(idx);
				Set<CardData.Trait> base = c.traits();
				java.util.EnumSet<CardData.Trait> temp = idx < tempList.size() ? tempList.get(idx) : null;
				java.util.EnumSet<CardData.Trait> rem  = idx < rmList.size()   ? rmList.get(idx)   : null;
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
					String job, String category, java.util.EnumSet<CardData.Trait> traitFilter) {
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
									mw.addToP1BreakZone(c);
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
									mw.addToP1BreakZone(c);
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
									mw.addToP2BreakZone(c);
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
									mw.addToP2BreakZone(c);
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
					String element, int costVal, String costCmp, String category) {
				boolean touchP1 = isP1 ? !opponentOnly : !selfOnly;
				boolean touchP2 = isP1 ? !selfOnly     : !opponentOnly;
				if (touchP1) {
					if (inclForwards) {
						for (int i = 0; i < mw.p1ForwardCards.size(); i++) {
							CardData c = p1Forward(i);
							if (element != null && !c.containsElement(element)) continue;
							if (!meetsCostConstraint(c.cost(), costVal, costCmp)) continue;
							if (!CardFilters.meetsCategoryFilter(c, category)) continue;
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
							logEntry("[P2] " + c.name() + " gains +" + amount + " power until end of turn");
						}
					}
				}
			}

			@Override public void applyMassFieldJobCardNamePowerBoost(int amount, boolean inclForwards, boolean inclMonsters,
					boolean opponentOnly, boolean selfOnly, String jobFilter, String cardNameFilter) {
				boolean touchP1 = isP1 ? !opponentOnly : !selfOnly;
				boolean touchP2 = isP1 ? !selfOnly     : !opponentOnly;
				if (touchP1) {
					if (inclForwards) {
						for (int i = 0; i < mw.p1ForwardCards.size(); i++) {
							CardData c = p1Forward(i);
							if (!CardFilters.meetsJobFilter(c, jobFilter) && !CardFilters.meetsCardNameFilter(c, cardNameFilter)) continue;
							mw.p1ForwardPowerBoost.set(i, mw.p1ForwardPowerBoost.get(i) + amount);
							logEntry(c.name() + " gains +" + amount + " power until end of turn");
							mw.refreshP1ForwardSlot(i);
						}
					}
					if (inclMonsters) {
						for (int i = 0; i < mw.p1MonsterCards.size(); i++) {
							CardData c = mw.p1MonsterCards.get(i);
							if (!CardFilters.meetsJobFilter(c, jobFilter) && !CardFilters.meetsCardNameFilter(c, cardNameFilter)) continue;
							logEntry(c.name() + " gains +" + amount + " power until end of turn");
						}
					}
				}
				if (touchP2) {
					if (inclForwards) {
						for (int i = 0; i < mw.p2ForwardCards.size(); i++) {
							CardData c = mw.p2ForwardCards.get(i);
							if (!CardFilters.meetsJobFilter(c, jobFilter) && !CardFilters.meetsCardNameFilter(c, cardNameFilter)) continue;
							mw.p2ForwardPowerBoost.set(i, mw.p2ForwardPowerBoost.get(i) + amount);
							logEntry("[P2] " + c.name() + " gains +" + amount + " power until end of turn");
							mw.refreshP2ForwardSlot(i);
						}
					}
					if (inclMonsters) {
						for (int i = 0; i < mw.p2MonsterCards.size(); i++) {
							CardData c = mw.p2MonsterCards.get(i);
							if (!CardFilters.meetsJobFilter(c, jobFilter) && !CardFilters.meetsCardNameFilter(c, cardNameFilter)) continue;
							logEntry("[P2] " + c.name() + " gains +" + amount + " power until end of turn");
						}
					}
				}
			}

			@Override public void applyMassFieldKeywordGrant(java.util.EnumSet<CardData.Trait> traits,
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

			@Override public void addEndOfTurnEffect(Consumer<GameContext> effect) {
				mw.endOfTurnEffects.add(effect);
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

			@Override public java.util.List<FieldAbility> getActiveFieldAbilities() {
				java.util.List<FieldAbility> active = new ArrayList<>();
				for (CardData c : mw.p1ForwardCards) active.addAll(c.fieldAbilities());
				for (CardData c : mw.p1MonsterCards)  active.addAll(c.fieldAbilities());
				for (CardData c : mw.p1BackupCards)   if (c != null) active.addAll(c.fieldAbilities());
				for (CardData c : mw.p2ForwardCards)  active.addAll(c.fieldAbilities());
				for (CardData c : mw.p2MonsterCards)  active.addAll(c.fieldAbilities());
				for (CardData c : mw.p2BackupCards)   if (c != null) active.addAll(c.fieldAbilities());
				return active;
			}

			@Override public int p1DamageCount() { return mw.gameState.getP1DamageZone().size(); }

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
				int count = 0;
				if (inclForwards) for (CardData c : mw.p1ForwardCards) {
					if (!mw.meetsJobFilterEffective(c, jobFilter)) continue;
					if (!meetsCardNameFilter(c, cardNameFilter)) continue;
					if (!meetsCategoryFilter(c, categoryFilter)) continue;
					if (elementFilter != null && !c.containsElement(elementFilter)) continue;
					count++;
				}
				if (inclBackups) for (CardData c : mw.p1BackupCards) {
					if (c == null) continue;
					if (!mw.meetsJobFilterEffective(c, jobFilter)) continue;
					if (!meetsCardNameFilter(c, cardNameFilter)) continue;
					if (!meetsCategoryFilter(c, categoryFilter)) continue;
					if (elementFilter != null && !c.containsElement(elementFilter)) continue;
					count++;
				}
				if (inclMonsters) for (CardData c : mw.p1MonsterCards) {
					if (!mw.meetsJobFilterEffective(c, jobFilter)) continue;
					if (!meetsCardNameFilter(c, cardNameFilter)) continue;
					if (!meetsCategoryFilter(c, categoryFilter)) continue;
					if (elementFilter != null && !c.containsElement(elementFilter)) continue;
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
				int count = 0;
				if (inclForwards) for (CardData c : mw.p2ForwardCards) {
					if (!mw.meetsJobFilterEffective(c, jobFilter)) continue;
					if (!meetsCardNameFilter(c, cardNameFilter)) continue;
					if (!meetsCategoryFilter(c, categoryFilter)) continue;
					if (elementFilter != null && !c.containsElement(elementFilter)) continue;
					count++;
				}
				if (inclBackups) for (CardData c : mw.p2BackupCards) {
					if (c == null) continue;
					if (!mw.meetsJobFilterEffective(c, jobFilter)) continue;
					if (!meetsCardNameFilter(c, cardNameFilter)) continue;
					if (!meetsCategoryFilter(c, categoryFilter)) continue;
					if (elementFilter != null && !c.containsElement(elementFilter)) continue;
					count++;
				}
				if (inclMonsters) for (CardData c : mw.p2MonsterCards) {
					if (!mw.meetsJobFilterEffective(c, jobFilter)) continue;
					if (!meetsCardNameFilter(c, cardNameFilter)) continue;
					if (!meetsCategoryFilter(c, categoryFilter)) continue;
					if (elementFilter != null && !c.containsElement(elementFilter)) continue;
					count++;
				}
				return count;
			}

			@Override public boolean controlConditionMet(ControlCondition cond) {
				return mw.controlConditionMet(cond, isP1);
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

			@Override public boolean selfHasSummonInBreakZone() {
				List<CardData> bz = isP1 ? mw.gameState.getP1BreakZone() : mw.gameState.getP2BreakZone();
				return bz.stream().anyMatch(CardData::isSummon);
			}

			@Override public int opponentDamageCount() {
				return (isP1 ? mw.gameState.getP2DamageZone() : mw.gameState.getP1DamageZone()).size();
			}

			@Override public int selfCardsCastThisTurn() { return isP1 ? mw.p1CardsCastThisTurn : mw.p2CardsCastThisTurn; }

			@Override public boolean selfSummonCastThisTurn() { return isP1 ? mw.p1SummonCastThisTurn : mw.p2SummonCastThisTurn; }

			@Override public int selfForwardCount() {
				return isP1 ? mw.p1ForwardCards.size() : mw.p2ForwardCards.size();
			}

			@Override public int opponentForwardCount() {
				return isP1 ? mw.p2ForwardCards.size() : mw.p1ForwardCards.size();
			}

			@Override public boolean isExBurst() { return exBurst; }
			@Override public boolean castWasPaidByBackupsOnly() { return mw.lastCastWasPaidByBackupsOnly; }

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
					java.util.List<CardData> fwds = isP1 ? mw.p1ForwardCards : mw.p2ForwardCards;
					for (int fi = 0; fi < fwds.size(); fi++) {
						if (fwds.get(fi) == source) {
							ctx.breakTarget(new ForwardTarget(isP1, fi, ForwardTarget.CardZone.FORWARD));
							return;
						}
					}
					java.util.List<CardData> mons = isP1 ? mw.p1MonsterCards : mw.p2MonsterCards;
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
					String jobFilter, String categoryFilter, String cardNameFilter) {
				Deque<CardData> deck = isP1 ? mw.gameState.getP1MainDeck() : mw.gameState.getP2MainDeck();
				int n = Math.min(reveal, deck.size());
				if (n == 0) { logEntry("Reveal top: deck is empty."); return; }
				List<CardData> peeked = new ArrayList<>();
				for (CardData c : deck) { peeked.add(c); if (peeked.size() >= n) break; }
				logEntry("Reveal top " + n + " card(s): " +
						peeked.stream().map(CardData::name).collect(Collectors.joining(", ")));
				mw.lookDialogs().showRevealAddUpToMatchingRestBottom(peeked, deck, isP1, maxAdd, jobFilter, categoryFilter, cardNameFilter);
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
}
