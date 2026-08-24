package shufflingway;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Matcher;

/**
 * Damage resolution: how much damage a source actually deals and a target actually
 * takes, after every shield, multiplier, reduction and redirect has been applied.
 *
 * <p>Extracted from {@link MainWindow}, which keeps a one-line delegator for each
 * method so existing call sites are unaffected.  None of this logic touches Swing;
 * it reads board and turn state through {@code mw}.
 */
class DamageResolver {

	private final MainWindow mw;

	DamageResolver(MainWindow mw) {
		this.mw = mw;
	}

	/**
	 * Applies all incoming-damage modifiers for {@code idx} and returns the final amount.
	 * One-time shields (next-damage-zero, next-damage-reduction) are consumed here.
	 * {@code fromAbility} is true when the damage source is an effect/summon, false for combat.
	 * {@code unreduced} bypasses all reductions: one-shot shields are still consumed but
	 * their reduction is not applied; persistent shields stay up and also do not reduce.
	 */
	int modifyIncomingDamage(boolean isP1, int idx, int rawAmount, boolean fromAbility, boolean unreduced) {
		return modifyIncomingDamage(isP1, ForwardTarget.CardZone.FORWARD, idx, rawAmount, fromAbility, unreduced);
	}

	/**
	 * Whether the ability damage now resolving is unreducible because its controller has
	 * "The damage dealt by your abilities to Forwards opponent controls cannot be reduced." on the
	 * field — Adelard 17-001H. {@code targetIsP1} is the side taking the damage; the carrier has to
	 * sit opposite it, and the ability has to be that same player's.
	 *
	 * <p>Summons are excluded: the printing says "abilities", and this mirrors the reading
	 * {@code nullifyAbilityOnlyDmgSet} already applies to a bare "ability".
	 */
	private boolean abilityDamageUnreducibleByField(boolean targetIsP1, boolean fromAbility) {
		if (!fromAbility || mw.currentResolutionIsSummon) return false;
		if (mw.currentAbilitySource == null || mw.currentAbilitySourceIsP1 == targetIsP1) return false;
		boolean casterIsP1 = !targetIsP1;
		List<CardData> casterField = new ArrayList<>(casterIsP1 ? mw.p1ForwardCards : mw.p2ForwardCards);
		for (CardData bkp : casterIsP1 ? mw.p1BackupCards : mw.p2BackupCards)
			if (bkp != null) casterField.add(bkp);
		casterField.addAll(casterIsP1 ? mw.p1MonsterCards : mw.p2MonsterCards);
		for (CardData src : casterField) {
			if (mw.lostAbilitiesCards.contains(src)) continue;
			for (FieldAbility fa : mw.effectiveFieldAbilities(src))
				if (AutoAbilityTriggers.FA_ABILITY_DAMAGE_TO_OPP_FORWARDS_UNREDUCIBLE
						.matcher(fa.effectText()).matches()) {
					mw.logEntry(src.name() + " — ability damage to opponent's Forwards cannot be reduced");
					return true;
				}
		}
		return false;
	}

	/**
	 * Zone-aware variant: applies incoming-damage modifiers to a card acting as a Forward from any
	 * zone (a real Forward, or a Monster/Backup temporarily a Forward). A card acting as a Forward
	 * is a Forward for every eligible purpose, so the self- and field-wide protections apply to it.
	 */
	int modifyIncomingDamage(boolean isP1, ForwardTarget.CardZone zone, int idx, int rawAmount,
			boolean fromAbility, boolean unreduced) {
		CardData card = mw.fieldCombatant(isP1, zone, idx);
		if (card == null) return rawAmount;
		// "…or is dealt damage while dull, the damage becomes 0 instead" — a replacement, not a
		// reduction, so it applies to every source and is not lifted by the unreduced flag.
		if (mw.damageZeroedWhileDull(card)) {
			mw.logEntry(card.name() + " is dull — incoming damage becomes 0");
			return 0;
		}
		// 29-012H Neon's Runic, read here for the same reason and in the same way: the chosen
		// effect's damage "becomes 0 instead", so it is settled before any multiplier below and
		// is not lifted by the unreduced flag either.
		if (fromAbility && mw.currentAbilitySource != null
				&& mw.damageZeroedSourcesThisTurn.contains(mw.currentAbilitySource)) {
			mw.logEntry(mw.currentAbilitySource.name() + " — its damage becomes 0 this turn");
			return 0;
		}
		int amount = rawAmount * (mw.turn(isP1).forwardIncomingDmgMult)
		                       * mw.perCardIncomingDmgMultiplierMap.getOrDefault(card, 1);

		// Incoming damage increase (debuff) — applied regardless of reduction-disabled flag
		if (mw.incomingDmgIncreaseMap.containsKey(card))
			amount += mw.incomingDmgIncreaseMap.get(card);
		if (mw.globalForwardIncomingDmgIncrease > 0)
			amount += mw.globalForwardIncomingDmgIncrease;

		// Outgoing damage boost from caster's side field cards (e.g. Caetuna — Fire Summon +1000)
		if (fromAbility) amount = applyCasterSideElementSummonDamageBoosts(amount, isP1);
		// Outgoing damage boost from caster's side field cards when source is an Element Forward
		if (fromAbility) amount = applyCasterSideElementForwardDamageBoosts(amount, isP1);

		// Outgoing damage doubler from the source card's own field ability (ability damage to opponent's Forward)
		if (fromAbility && mw.currentAbilitySource != null && mw.currentAbilitySourceIsP1 != isP1
				&& !mw.lostAbilitiesCards.contains(mw.currentAbilitySource)) {
			int doublerDmg = (mw.currentAbilitySourceIsP1
					? mw.gameState.getP1DamageZone() : mw.gameState.getP2DamageZone()).size();
			for (FieldAbility fa : mw.effectiveFieldAbilities(mw.currentAbilitySource)) {
				// The printing's own "Damage N --" gate, read here where the FieldAbility still
				// carries it — the same check the combat and damage-to-opponent paths make.
				if (fa.damageThreshold() > 0 && doublerDmg < fa.damageThreshold()) continue;
				// The clause list, not the printed text alone: Kefka 23-004R prints his doubler inside
				// a self grant, and a granted copy has to double exactly as a printed one does.
				for (String clause : CardData.selfPassiveClauses(fa.effectText(), mw.currentAbilitySource.name())) {
					Matcher fam = AutoAbilityTriggers.FA_OUTGOING_DAMAGE_DOUBLER.matcher(clause);
					if (!fam.find() || !fam.group("card").trim().equalsIgnoreCase(mw.currentAbilitySource.name())) continue;
					if (!fam.group("target").toLowerCase().contains("forward")) continue;
					int before = amount;
					amount *= 2;
					mw.logEntry(mw.currentAbilitySource.name() + " — outgoing damage doubled (" + before + " → " + amount + ")");
				}
			}
		}

		// Self unconditional outgoing flat boost from the source card's own field ability
		// (ability damage to a Forward), e.g. Foulander's attack-trigger damage.
		if (fromAbility && card.isForward() && mw.currentAbilitySource != null
				&& mw.currentAbilitySourceIsP1 != isP1) {
			int boost = mw.selfOutgoingFlatBoostVsForward(mw.currentAbilitySource, mw.currentAbilitySourceIsP1);
			if (boost > 0) {
				int before = amount;
				amount += boost;
				mw.logEntry(mw.currentAbilitySource.name() + " — outgoing damage +" + boost
						+ " to Forward (" + before + " → " + amount + ")");
			}
		}

		// Source-based nullification (these block damage by type of source, not by reducing amount)
		if (fromAbility) {
			// Nullify all ability/summon damage
			if (mw.nullifyAbilityDmgSet.contains(card)) return 0;
			// Filter-based nullification: covers Forwards that entered the field after the shield resolved
			for (Predicate<CardData> f : (mw.turn(isP1).nullifyAbilityDmgFilters))
				if (f.test(card)) return 0;
			// Nullify ability-only damage (not Summons)
			if (!mw.currentResolutionIsSummon && mw.nullifyAbilityOnlyDmgSet.contains(card)) return 0;
			// Element-scoped nullification (Hein ability): covers both targeted and AoE damage
			String nullifyElem = mw.nullifyElementDamageMap.get(card);
			if (nullifyElem != null) {
				CardData resCard = mw.currentResolutionIsSummon ? mw.currentSummonSource : mw.currentAbilitySource;
				if (resCard != null && mw.effectiveElements(resCard).contains(nullifyElem)) return 0;
			}
			// Element-scoped, ability-only nullification (Rubicante ability): Summons are not covered
			if (!mw.currentResolutionIsSummon) {
				String nullifyAbilityElem = mw.nullifyElementDamageAbilityOnlyMap.get(card);
				if (nullifyAbilityElem != null && mw.currentAbilitySource != null
						&& mw.effectiveElements(mw.currentAbilitySource).contains(nullifyAbilityElem)) return 0;
			}
			// Passive field ability: nullify Summon-only damage
			if (mw.currentResolutionIsSummon) {
				for (FieldAbility fa : card.fieldAbilities()) {
					Matcher m = AutoAbilityTriggers.FA_NULLIFY_SUMMON_DAMAGE.matcher(fa.effectText());
					if (m.find() && m.group("card").trim().equalsIgnoreCase(card.name())) return 0;
				}
			}

			// Passive field ability: nullify ability-source damage entirely (not Summons) — e.g. Philia
			if (!mw.currentResolutionIsSummon) {
				for (FieldAbility fa : card.fieldAbilities()) {
					Matcher m = AutoAbilityTriggers.FA_NULLIFY_ABILITY_DAMAGE.matcher(fa.effectText());
					if (m.find() && m.group("card").trim().equalsIgnoreCase(card.name())) {
						mw.logEntry(card.name() + " — ability damage nullified by field ability (→ 0)");
						return 0;
					}
				}
			}

			// Passive field ability: nullify opponent's non-Summon ability damage
			if (!mw.currentResolutionIsSummon && mw.currentAbilitySourceIsP1 != isP1) {
				for (FieldAbility fa : card.fieldAbilities()) {
					Matcher m = AutoAbilityTriggers.FA_NULLIFY_OPPONENT_ABILITY_DAMAGE.matcher(fa.effectText());
					if (m.find() && m.group("card").trim().equalsIgnoreCase(card.name())) {
						mw.logEntry(card.name() + " — opponent ability damage nullified by field ability (→ 0)");
						return 0;
					}
				}
			}

		}

		// Passive field ability: nullify battle damage from a Forward with specific traits (e.g. Haste, First Strike)
		if (!fromAbility && mw.currentBattleAttacker != null) {
			for (FieldAbility fa : card.fieldAbilities()) {
				Matcher fam = AutoAbilityTriggers.FA_NULLIFY_TRAIT_FORWARD_DAMAGE.matcher(fa.effectText());
				if (!fam.find() || !fam.group("card").trim().equalsIgnoreCase(card.name())) continue;
				String t1 = fam.group("trait1").trim();
				String t2raw = fam.group("trait2");
				String t2 = t2raw != null ? t2raw.trim() : null;
				CardData.Trait trait1 = mw.traitFromName(t1);
				CardData.Trait trait2 = t2 != null ? mw.traitFromName(t2) : null;
				boolean t1Match = trait1 != null && mw.fieldForwardTrait(mw.currentBattleAttackerIsP1, mw.currentBattleAttackerZone, mw.currentBattleAttackerIdx, trait1);
				boolean t2Match = trait2 != null && mw.fieldForwardTrait(mw.currentBattleAttackerIsP1, mw.currentBattleAttackerZone, mw.currentBattleAttackerIdx, trait2);
				if (t1Match || t2Match) {
					mw.logEntry(card.name() + " — battle damage nullified (attacker has " + (t1Match ? t1 : t2) + ")");
					return 0;
				}
			}
		}

		if (unreduced || abilityDamageUnreducibleByField(isP1, fromAbility)) {
			// Consume one-shot shields so they are spent, but do not apply any reduction.
			// Persistent shields ("until end of turn") remain in place unchanged.
			mw.nextIncomingDmgZeroSet.remove(card);
			// The shield is spent but reduced nothing, so its bill is dropped with it rather than
			// charged: Cecil takes damage for a hit he softened, not for one that went through.
			mw.nextIncomingDmgReduceMap.remove(card);
			mw.nextIncomingDmgReduceKickbackMap.remove(card);
			if (fromAbility) mw.nextAbilityDmgReduceMap.remove(card);
			return amount;
		}

		// If damage reductions are disabled for this side, skip all target-side protections
		if (mw.turn(isP1).dmgReductionDisabled) return amount;

		// One-time: next incoming damage = 0
		if (mw.nextIncomingDmgZeroSet.remove(card)) return 0;

		// One-time: next incoming damage reduced by N
		if (mw.nextIncomingDmgReduceMap.containsKey(card)) {
			amount = Math.max(0, amount - mw.nextIncomingDmgReduceMap.remove(card));
			// Cecil 9-109H's shield bills him for softening the hit. Queued, not dealt: this method
			// is arithmetic, and the kickback is damage that can break a Forward.
			MainWindow.ShieldKickback owed = mw.nextIncomingDmgReduceKickbackMap.remove(card);
			if (owed != null) mw.pendingShieldKickbacks.add(owed);
		}

		// One-time: next ability/summon damage reduced by N
		if (fromAbility && mw.nextAbilityDmgReduceMap.containsKey(card))
			amount = Math.max(0, amount - mw.nextAbilityDmgReduceMap.remove(card));

		// Passive field ability: self-targeted incoming damage modifier
		// ("by a Forward / by Summon or ability / other than battle damage / less than power / any source")
		// Read through effectiveFieldAbilities, not the printed list: Sarah (MOBIUS) 16-115H hands
		// herself one of these for the turn, and a grant has to be honoured exactly as a printing is.
		//
		// A "Damage N --" prefix gates the whole printing on its controller's own damage zone
		// (Siren (V) 22-098H, Tidus 26-112H, Brute Bomber 28-019R). It has to be read here, where
		// the FieldAbility is still in hand — applyDamageModifierMatch sees only the matcher, and
		// the "threshold" group it does consult is the unrelated "is dealt N damage or more" form.
		int dmgInZone = (isP1 ? mw.gameState.getP1DamageZone() : mw.gameState.getP2DamageZone()).size();
		for (FieldAbility fa : mw.effectiveFieldAbilities(card)) {
			if (fa.damageThreshold() > 0 && dmgInZone < fa.damageThreshold()) continue;
			Matcher fam = AutoAbilityTriggers.FA_DAMAGE_MODIFIER.matcher(fa.effectText());
			if (fam.find() && fam.group("card").trim().equalsIgnoreCase(card.name())) {
				amount = applyDamageModifierMatch(fam, amount, isP1, zone, idx, fromAbility, card);
				continue;
			}
			// The same modifier spelled as a quoted clause the card hands itself ("Charlotte gains
			// +2000 power and \"The damage dealt to Charlotte is reduced by 2000 instead.\"" —
			// 13-023R). parseSelfGainsQuotedGrant has already rewritten it into the canonical
			// wording, so the matcher above reads it unchanged; what kept it out of reach was the
			// outer sentence it is nested in, which no damage pattern matches.
			CardData.SelfGainsQuotedGrant sgq =
					CardData.parseSelfGainsQuotedGrant(fa.effectText(), card.name());
			if (sgq == null) continue;
			for (String passive : sgq.passiveTexts()) {
				Matcher pm = AutoAbilityTriggers.FA_DAMAGE_MODIFIER.matcher(passive);
				if (pm.find() && pm.group("card").trim().equalsIgnoreCase(card.name()))
					amount = applyDamageModifierMatch(pm, amount, isP1, zone, idx, fromAbility, card);
			}
		}

		// Once-per-turn replacement: "During each turn, if [self] is dealt damage by your opponent's
		// Summons or abilities for the first time in that turn, the damage becomes 0 instead."
		// (Edge 15-045H). Answered after the standing modifiers above so a printing that would have
		// reduced the damage anyway does not burn the turn's one use, and the slot is taken only on
		// a resolution this actually claims.
		if (amount > 0 && fromAbility && firstOppEffectDamageZeroApplies(card, isP1)) {
			mw.turn(isP1).firstOppEffectDamageZeroedThisTurn.add(card);
			mw.logEntry(card.name() + " — first opposing Summon/ability damage this turn becomes 0");
			return 0;
		}

		// Incoming damage modifier granted to this Forward by a counter grant (e.g. Kimahri's
		// Ronso Counter, Tidus's Guardian Counter). "If this Forward is dealt damage …" — the
		// subject is implicit, so no card-name match is required.
		for (String granted : mw.counterGrantedAbilities(card, isP1)) {
			Matcher fam = AutoAbilityTriggers.FA_DAMAGE_MODIFIER.matcher(granted);
			if (fam.find()) amount = applyDamageModifierMatch(fam, amount, isP1, zone, idx, fromAbility, card);
		}

		// Passive field ability: self-targeted incoming damage reduction while dull
		for (FieldAbility fa : card.fieldAbilities()) {
			Matcher m = AutoAbilityTriggers.FA_DAMAGE_WHILE_DULL_REDUCTION.matcher(fa.effectText());
			if (!m.find() || !m.group("card").trim().equalsIgnoreCase(card.name())) continue;
			CardState state = mw.fieldTargetState(new ForwardTarget(isP1, idx, zone));
			if (state == CardState.DULL) {
				int reduction = Integer.parseInt(m.group("amount"));
				int before = amount;
				amount = Math.max(0, amount - reduction);
				mw.logEntry(card.name() + " — damage while dull reduced by " + reduction + " (" + before + " → " + amount + ")");
			}
		}

		// Party-conditioned shield: "If [card] forms a party, the damage dealt to [card | the
		// Forwards forming this party] becomes 0 instead." A replacement rather than a reduction,
		// so it answers before the field-wide modifiers rather than after them.
		if (amount > 0 && partyDamageNullified(card, isP1)) {
			mw.logEntry(card.name() + " — damage becomes 0 (forming a party)");
			return 0;
		}

		// The reduction form of the same idea, covering the protector itself as well as its party
		// (White Mage 3-136C). A reduction rather than a replacement, so unlike the nullification
		// above it stacks with the field-wide modifiers instead of short-circuiting them.
		if (amount > 0) {
			int partyRed = selfOrPartyDamageReduction(card, isP1);
			if (partyRed > 0) {
				int before = amount;
				amount = Math.max(0, amount - partyRed);
				mw.logEntry(card.name() + " — damage reduced by " + partyRed
						+ " (" + before + " → " + amount + ")");
			}
		}

		// Passive field ability on other friendly cards: field-wide incoming damage modifier
		amount = applyFieldWideDamageModifiers(amount, card, isP1, zone, idx, fromAbility);

		// Global per-player damage reduction
		int globalRed = mw.turn(isP1).globalDmgReduction;
		if (globalRed > 0) amount = Math.max(0, amount - globalRed);

		// Per-card non-lethal protection: damage < this card's effective power → becomes 0
		if (mw.perCardNonLethalDmgSet.contains(card)) {
			int power = mw.fieldForwardPower(isP1, zone, idx);
			if (amount < power) return 0;
		}

		// Global non-lethal protection: damage < forward's effective power → becomes 0
		boolean nonLethal = mw.turn(isP1).nonLethalProtection;
		if (nonLethal) {
			int power = mw.fieldForwardPower(isP1, zone, idx);
			if (amount < power) return 0;
		}

		return amount;
	}

	/**
	 * Returns {@code true} when {@code card}'s damage is replaced with 0 because it is in a party
	 * and something in that party says so — {@link AutoAbilityTriggers#FA_PARTY_SELF_DAMAGE_NULLIFY}.
	 *
	 * <p>Two scopes reach this, and both are read off the party rather than off the damaged card
	 * alone. Chocobo 5-060C and Paladin 12-102C protect only themselves, so their printing has to be
	 * on {@code card} and name it twice over. Chelinka 20-049R protects every Forward in the party,
	 * so any member's printing covers {@code card} — including Chelinka's own copy, which is why the
	 * carrier is not excluded from the walk.
	 *
	 * <p>Field abilities are read through {@code effectiveFieldAbilities}, so a granted copy of
	 * either printing is honoured exactly as a printed one is.
	 */
	private boolean partyDamageNullified(CardData card, boolean isP1) {
		if (!mw.isFormingParty(card, isP1)) return false;
		for (FieldAbility fa : mw.effectiveFieldAbilities(card)) {
			Matcher m = AutoAbilityTriggers.FA_PARTY_SELF_DAMAGE_NULLIFY.matcher(fa.effectText());
			if (m.matches() && m.group("wholeparty") == null
					&& m.group("card").trim().equalsIgnoreCase(card.name())
					&& m.group("target").trim().equalsIgnoreCase(card.name())) return true;
		}
		for (CardData member : mw.currentPartyMembers(isP1)) {
			if (mw.lostAbilitiesCards.contains(member)) continue;
			for (FieldAbility fa : mw.effectiveFieldAbilities(member)) {
				Matcher m = AutoAbilityTriggers.FA_PARTY_SELF_DAMAGE_NULLIFY.matcher(fa.effectText());
				if (m.matches() && m.group("wholeparty") != null
						&& m.group("card").trim().equalsIgnoreCase(member.name())) return true;
			}
		}
		return false;
	}

	/**
	 * The total reduction {@code card} gets from {@link AutoAbilityTriggers#FA_SELF_OR_PARTY_DAMAGE_REDUCTION}
	 * printings on its own side — White Mage 3-136C's "If White Mage or a Forward forming a party
	 * with White Mage receives damage, the damage decreases by 3000 instead."
	 *
	 * <p>Two arms, and they differ in what they require of the board. The carrier protects itself
	 * unconditionally, so a White Mage sitting alone still takes 3000 less. Everyone else needs to
	 * be in a party <em>with</em> the carrier, which is why both must be declared attackers in a
	 * party of two or more — read the way {@link MainWindow#isFormingParty} reads it, so this and
	 * the party-conditioned power grants agree about when a party exists.
	 *
	 * <p>Both name captures are checked against the protector, so a quoted copy of the sentence
	 * carried by somebody else does not protect that card's party.
	 */
	private int selfOrPartyDamageReduction(CardData card, boolean isP1) {
		int reduction = 0;
		for (CardData protector : mw.fieldCards(isP1)) {
			if (mw.lostAbilitiesCards.contains(protector)) continue;
			boolean self = protector == card;
			for (FieldAbility fa : mw.effectiveFieldAbilities(protector)) {
				Matcher m = AutoAbilityTriggers.FA_SELF_OR_PARTY_DAMAGE_REDUCTION.matcher(fa.effectText());
				if (!m.matches()) continue;
				if (!m.group("card").trim().equalsIgnoreCase(protector.name())) continue;
				if (!m.group("partner").trim().equalsIgnoreCase(protector.name())) continue;
				if (!self && !(mw.isFormingParty(protector, isP1) && mw.isFormingParty(card, isP1))) continue;
				reduction += Integer.parseInt(m.group("amount"));
			}
		}
		return reduction;
	}

	/**
	 * Whether {@code card} prints Edge 15-045H's once-per-turn shield, has not spent it this turn,
	 * and the resolving Summon or ability belongs to its opponent.
	 *
	 * <p>Read through {@code effectiveFieldAbilities} and name-checked against the carrier, like
	 * every other self-targeted damage passive, and gated on a "Damage N --" threshold the same way.
	 * Only the caller records the use, so asking this question does not spend the shield.
	 */
	private boolean firstOppEffectDamageZeroApplies(CardData card, boolean isP1) {
		if (mw.turn(isP1).firstOppEffectDamageZeroedThisTurn.contains(card)) return false;
		// The damage has to originate on the other side of the board — a friendly Summon or ability
		// is not "your opponent's".
		CardData resCard = mw.currentResolutionIsSummon ? mw.currentSummonSource : mw.currentAbilitySource;
		boolean  resIsP1 = mw.currentResolutionIsSummon ? mw.currentSummonSourceIsP1 : mw.currentAbilitySourceIsP1;
		if (resCard == null || resIsP1 == isP1) return false;
		int dmgInZone = (isP1 ? mw.gameState.getP1DamageZone() : mw.gameState.getP2DamageZone()).size();
		for (FieldAbility fa : mw.effectiveFieldAbilities(card)) {
			if (fa.damageThreshold() > 0 && dmgInZone < fa.damageThreshold()) continue;
			Matcher m = AutoAbilityTriggers.FA_FIRST_OPP_EFFECT_DAMAGE_ZERO_EACH_TURN
					.matcher(fa.effectText().trim());
			if (m.matches() && m.group("card").trim().equalsIgnoreCase(card.name())) return true;
		}
		return false;
	}

	/**
	 * Applies one matched {@link AutoAbilityTriggers#FA_DAMAGE_MODIFIER} effect (reduce/set/increase/
	 * double) to {@code amount}, honoring its optional damage threshold and source clause. Shared by
	 * a Forward's own field ability and abilities granted to it via a counter grant. {@code subject}
	 * is the damaged card — it names the log line, and it is what an optional "remove N [X] Counter
	 * from …" clause spends. Returns the (possibly modified) amount.
	 */
	int applyDamageModifierMatch(Matcher fam, int amount, boolean isP1,
			ForwardTarget.CardZone zone, int idx, boolean fromAbility, CardData subject) {
		String subjectName = subject.name();
		String threshStr = fam.group("threshold");
		if (threshStr != null) {
			// "N damage or more" gates on amount >= N; "or less" (Baigan 9-072H) on amount <= N.
			// Both are inclusive of N, so only the strict comparison on the far side declines.
			int thresh = Integer.parseInt(threshStr);
			boolean orLess = "less".equalsIgnoreCase(fam.group("threshcmp"));
			if (orLess ? amount > thresh : amount < thresh) return amount;
		}
		// "During your turn" (Garland 3-004H) / "during your opponent's turn" (Cagnazzo 3-130R),
		// printed at either end of the sentence and meaning the same either way. "Your" in a
		// card's own text is its controller, so the window is read against the damaged card's side
		// — which for these self-named shields is the carrier's.
		String turnScope = fam.group("turnpre") != null ? fam.group("turnpre") : fam.group("turnpost");
		if (turnScope != null) {
			boolean ownTurn = (mw.gameState.getCurrentPlayer() == GameState.Player.P1) == isP1;
			if (ownTurn == turnScope.toLowerCase().contains("opponent")) return amount;
		}
		String src = fam.group("sourceclause");
		boolean applies;
		if (src == null || src.isBlank()) {
			applies = true;
		} else {
			String srcN = src.trim().toLowerCase();
			if (srcN.startsWith("less than") && srcN.endsWith("power")) {
				int power = mw.fieldForwardPower(isP1, zone, idx);
				applies = amount < power;
			} else if (srcN.startsWith("by a forward's abilit") || srcN.startsWith("by a forwards abilit")) {
				// Gawain 7-107R. Narrower than either neighbour, and checked ahead of the bare
				// "by a Forward" branch below, which would otherwise claim it and return the
				// opposite answer: that one is battle damage, this one is only ability damage.
				// A Summon's damage is not an ability's, and neither is a Backup's.
				applies = fromAbility && !mw.currentResolutionIsSummon
						&& mw.sourceIsActingForward(mw.currentAbilitySource);
			} else if (srcN.startsWith("by a forward")) {
				applies = !fromAbility;
			} else if (srcN.startsWith("by a character")) {
				// The source is a Character rather than a Summon, which covers both the battle
				// damage a Forward deals and the damage a Character's own ability deals. Only a
				// resolving Summon is excluded (Ark Angel EV 4-097H).
				applies = !fromAbility || !mw.currentResolutionIsSummon;
			} else if (srcN.contains("other than special abilit")) {
				// Ghis 2-126R. Special abilities are a separate kind of ability under rule 6-1-1,
				// and this is the one printing that shields against the other kinds while leaving
				// them through. Checked ahead of the general ability branch below, which would
				// answer "yes" to a special.
				applies = fromAbility && !mw.currentResolutionIsSummon && !mw.currentAbilityIsSpecial;
			} else if (srcN.contains("summon") && !srcN.contains("abilit")) {
				applies = fromAbility && mw.currentResolutionIsSummon;
			} else if (!srcN.contains("summon") && !srcN.startsWith("other")) {
				applies = fromAbility && !mw.currentResolutionIsSummon;
			} else {
				applies = fromAbility;
			}
		}
		if (!applies) return amount;
		// "remove N [X] Counter from [self] and …" — the replacement's own cost, spent only now that
		// the modifier has been claimed. The counter is what conditions the grant in the first place
		// (see MainWindow.counterGrantedAbilities), so spending the last one ends the shield.
		String rmCount = fam.group("rmcount");
		if (rmCount != null) {
			String rmName = fam.group("rmcounter").trim();
			int    need   = Integer.parseInt(rmCount);
			// Pay in full or not at all: a partial payment buys nothing, so the counters stay put and
			// the damage lands unmodified.
			if (mw.gameState.getCounters(subject, rmName) < need) return amount;
			mw.gameState.removeCounters(subject, rmName, need);
			mw.logEntry(subjectName + " — removed " + need + " " + rmName + " Counter"
					+ (need == 1 ? "" : "s") + " (" + mw.gameState.getCounters(subject, rmName) + " left)");
		}
		String reduceStr   = fam.group("reduceby");
		String setstoStr   = fam.group("setsto");
		String increaseStr = fam.group("increaseby");
		if (reduceStr != null) {
			int before = amount;
			amount = Math.max(0, amount - Integer.parseInt(reduceStr));
			mw.logEntry(subjectName + " — damage reduced by " + reduceStr + " (" + before + " → " + amount + ")");
		} else if (setstoStr != null) {
			int fixed = Integer.parseInt(setstoStr);
			mw.logEntry(subjectName + " — damage set to " + fixed + " instead");
			amount = fixed;
		} else if (increaseStr != null) {
			int before = amount;
			amount = amount + Integer.parseInt(increaseStr);
			mw.logEntry(subjectName + " — damage increased by " + increaseStr + " (" + before + " → " + amount + ")");
		} else if (fam.group("double") != null) {
			int before = amount;
			amount = amount * 2;
			mw.logEntry(subjectName + " — damage doubled (" + before + " → " + amount + ")");
		} else if (fam.group("half") != null) {
			int before = amount;
			amount = halveRoundedUpToThousand(amount);
			mw.logEntry(subjectName + " — damage halved (" + before + " → " + amount + ")");
		}
		return amount;
	}

	/**
	 * Halves {@code amount} and rounds the result up to a whole 1000, the rule Rosso 2-024R's
	 * parenthetical states ("numbers are rounded up to units of 1000").
	 *
	 * <p>Rounding is applied to the halved figure, not to the input: 5000 halves to 2500 and rounds
	 * to 3000, while 4000 halves to exactly 2000 and stays there. The odd-input step is there because
	 * damage does not have to arrive in whole thousands — a reduction elsewhere in the chain can
	 * leave any figure — and it rounds up for the same reason the printed rule does.
	 */
	static int halveRoundedUpToThousand(int amount) {
		if (amount <= 0) return 0;
		int halved = (amount + 1) / 2;
		return ((halved + 999) / 1000) * 1000;
	}

	/**
	 * Scans every card on the damaged Forward's own side for
	 * {@link AutoAbilityTriggers#FA_FIELD_DAMAGE_MODIFIER} and
	 * {@link AutoAbilityTriggers#FA_REDUCE_DAMAGE_TO_FILTER} abilities and applies any that target
	 * it. Returns the (possibly modified) damage amount.
	 *
	 * <p>Reads the same three things its outgoing-damage counterparts read, and for the same
	 * reasons. Every row rather than Forwards and Backups by hand, so a Monster printing a shield
	 * is not silently exempt. {@code lostAbilitiesCards} skipped, because a card stripped of its
	 * abilities prints nothing and so shields nothing. And {@code effectiveFieldAbilities} rather
	 * than the printed list, so a shield granted until end of turn is as live as a printed one.
	 */
	int applyFieldWideDamageModifiers(int amount, CardData damaged, boolean isP1,
			ForwardTarget.CardZone zone, int idx, boolean fromAbility) {
		int effectivePower = mw.fieldForwardPower(isP1, zone, idx);
		boolean attackerIsBackup = !fromAbility && (isP1 ? mw.pendingP2AttackerIsBackup : mw.p1BackupAttackIdx >= 0);

		for (CardData protector : mw.fieldCards(isP1)) {
			if (mw.lostAbilitiesCards.contains(protector)) continue;
			for (FieldAbility fa : mw.effectiveFieldAbilities(protector)) {
				// The imperative spelling of the same reduction (Warrior of Light 2-145L). Read
				// first and on its own terms: it carries no condition and no source clause, so
				// none of the filtering below applies to it beyond who the damaged card is.
				Matcher red = AutoAbilityTriggers.FA_REDUCE_DAMAGE_TO_FILTER.matcher(fa.effectText().trim());
				if (red.matches()) {
					String rc = red.group("category");
					String rj = red.group("job");
					String re = red.group("element");
					String rt = red.group("types");
					if (rc != null && !CardFilters.meetsCategoryFilter(damaged, rc)) continue;
					if (rj != null && !CardFilters.meetsJobFilter(damaged, rj))      continue;
					if (re != null && !mw.effectiveElements(damaged).contains(re))   continue;
					// "Forwards" binds the type; "Characters" and the bare form do not, and the
					// damaged card is a combatant either way.
					if (rt != null && rt.toLowerCase().startsWith("forward") && !damaged.isForward()) continue;
					int by = Integer.parseInt(red.group("amount"));
					int before = amount;
					amount = Math.max(0, amount - by);
					mw.logEntry(damaged.name() + " — damage reduced by " + by + " ("
							+ protector.name() + ") (" + before + " → " + amount + ")");
					continue;
				}
				// Cecil 2-129L prints the shield as a rider on a power grant; the rider is read
				// here in the canonical wording, and the grant half by parseFieldPowerGrants.
				Matcher m = AutoAbilityTriggers.FA_FIELD_DAMAGE_MODIFIER
						.matcher(CardData.fieldDamageRiderText(fa.effectText()));
				if (!m.find()) continue;

				// Target filter
				String category = m.group("category");
				String job      = AutoAbilityTriggers.fieldDamageModifierJob(m);
				String element  = m.group("element");
				String costStr  = m.group("cost");
				String costcmp  = m.group("costcmp");
				String except   = m.group("except1") != null ? m.group("except1").trim()
				                                             : (m.group("except2") != null ? m.group("except2").trim() : null);

				if (category != null && !CardFilters.meetsCategoryFilter(damaged, category)) continue;
				if (job      != null && !CardFilters.meetsJobFilter(damaged, job))            continue;
				if (element  != null && !mw.effectiveElements(damaged).contains(element))        continue;
				if (costStr  != null) {
					int costVal = Integer.parseInt(costStr);
					boolean orMore = "more".equalsIgnoreCase(costcmp);
					if (orMore ? damaged.cost() < costVal : damaged.cost() > costVal) continue;
				}
				if (except != null && except.equalsIgnoreCase(damaged.name())) continue;

				// Source clause
				String src = m.group("sourceclause");
				if (src != null && !src.isBlank()) {
					String srcN = src.trim().toLowerCase();
					if (srcN.contains("less than its power") && amount >= effectivePower) continue;
					if (srcN.contains("by a backup") && !attackerIsBackup) continue;
					// "by a Forward" names the source of battle damage, so an ability's damage is
					// outside it — the same reading this clause gets on FA_DAMAGE_MODIFIER.
					if (srcN.startsWith("by a forward") && fromAbility) continue;
					if (srcN.contains("abilit") || srcN.contains("summon")) {
						if (!fromAbility) continue;
						boolean namesSummon  = srcN.contains("summon");
						boolean namesAbility = srcN.contains("abilit");
						if (namesSummon && !namesAbility && !mw.currentResolutionIsSummon) continue;
						if (namesAbility && !namesSummon &&  mw.currentResolutionIsSummon) continue;
						// "by your opponent's …" — the damage has to originate on the other side of
						// the board, so a friendly Summon or ability is not covered.
						if (srcN.contains("opponent")) {
							CardData resCard = mw.currentResolutionIsSummon ? mw.currentSummonSource : mw.currentAbilitySource;
							boolean  resIsP1 = mw.currentResolutionIsSummon ? mw.currentSummonSourceIsP1 : mw.currentAbilitySourceIsP1;
							if (resCard == null || resIsP1 == isP1) continue;
						}
					}
				}

				// Apply effect
				String reduceStr = m.group("reduceby");
				String setstoStr = m.group("setsto");
				if (reduceStr != null) {
					int before = amount;
					amount = Math.max(0, amount - Integer.parseInt(reduceStr));
					mw.logEntry(damaged.name() + " — damage reduced by " + reduceStr
							+ " (" + before + " → " + amount + ") [" + protector.name() + "]");
				} else if (setstoStr != null) {
					int fixed = Integer.parseInt(setstoStr);
					mw.logEntry(damaged.name() + " — damage set to " + fixed + " instead [" + protector.name() + "]");
					amount = fixed;
				}
			}

			// Exact-amount nullification: "If a Forward you control receives N damage, the damage becomes 0 instead."
			if (!mw.lostAbilitiesCards.contains(protector)) {
				for (FieldAbility fa : protector.fieldAbilities()) {
					Matcher m = AutoAbilityTriggers.FA_FIELD_DAMAGE_EXACT_NULLIFY.matcher(fa.effectText());
					if (!m.find()) continue;
					int exactAmt = Integer.parseInt(m.group("amount"));
					if (amount == exactAmt) {
						mw.logEntry(damaged.name() + " — " + exactAmt + " damage becomes 0 [" + protector.name() + "]");
						amount = 0;
					}
				}
			}
		}
		return amount;
	}

	/**
	 * Scans the CASTER's side field cards for {@link AutoAbilityTriggers#FA_ELEMENT_SUMMON_DAMAGE_BOOST}
	 * abilities (e.g. Caetuna: "Fire Summon damage +1000") and applies any that match the current
	 * resolving Summon's element.  {@code targetIsP1} is the owner of the Forward being damaged.
	 */
	int applyCasterSideElementSummonDamageBoosts(int amount, boolean targetIsP1) {
		if (!mw.currentResolutionIsSummon || mw.currentSummonSource == null) return amount;
		boolean casterIsP1 = mw.currentSummonSourceIsP1;
		if (casterIsP1 == targetIsP1) return amount;  // Only boosts damage to the opposing side
		for (CardData booster : mw.fieldCards(casterIsP1)) {
			// A card stripped of its abilities prints nothing, so it boosts nothing.
			if (mw.lostAbilitiesCards.contains(booster)) continue;
			List<FieldAbility> fas = mw.effectiveFieldAbilities(booster);
			for (FieldAbility fa : fas) {
				Matcher m = AutoAbilityTriggers.FA_ELEMENT_SUMMON_DAMAGE_BOOST.matcher(fa.effectText());
				if (!m.find()) continue;
				String elem = m.group("element");
				if (!mw.currentSummonSource.containsElement(elem)) continue;
				int boost = Integer.parseInt(m.group("amount"));
				int before = amount;
				amount += boost;
				mw.logEntry(booster.name() + " — " + elem + " Summon damage increased by " + boost
						+ " (" + before + " → " + amount + ")");
			}
			// The unfiltered form: any Summon of yours, Terra 9-029C. No element to match, so the
			// only question is that the resolving source is this player's Summon, which the guards
			// at the top of the method have already settled.
			for (FieldAbility fa : fas) {
				Matcher m = AutoAbilityTriggers.FA_FRIENDLY_SUMMON_DAMAGE_BOOST.matcher(fa.effectText().trim());
				if (!m.matches()) continue;
				int boost = Integer.parseInt(m.group("amount"));
				int before = amount;
				amount += boost;
				mw.logEntry(booster.name() + " — Summon damage increased by " + boost
						+ " (" + before + " → " + amount + ")");
			}
			// The same boost worded from the dealing side (Lehftia 21-020C). Only its Summon arm is
			// read here; the Character arm belongs to the combat and ability paths.
			for (FieldAbility fa : fas) {
				Matcher m = AutoAbilityTriggers.FA_ELEMENT_SUMMON_OR_CHARACTER_DAMAGE_BOOST.matcher(fa.effectText());
				if (!m.matches()) continue;
				// The arm's presence is what this reader needs; its Element is optional, and an
				// unelemented one covers every Summon its controller casts (Ifrit, Lord of the
				// Inferno 14-006R). Reading absence of an Element as absence of the arm is what
				// would have made that printing boost nothing at all.
				if (m.group("summonarm") == null) continue;
				String elem = m.group("summonelement");
				if (elem != null && !mw.currentSummonSource.containsElement(elem)) continue;
				int boost = Integer.parseInt(m.group("amount"));
				int before = amount;
				amount += boost;
				mw.logEntry(booster.name() + " — " + (elem == null ? "" : elem + " ")
						+ "Summon damage increased by " + boost + " (" + before + " → " + amount + ")");
			}
		}
		return amount;
	}

	/**
	 * Scans the caster's side field cards for {@link AutoAbilityTriggers#FA_ELEMENT_FORWARD_DAMAGE_BOOST}
	 * abilities and applies any that match when the resolving ability source is an Element Forward
	 * dealing damage to a Forward on the opposing side.
	 *
	 * <p>Also applies the Character arm of
	 * {@link AutoAbilityTriggers#FA_ELEMENT_SUMMON_OR_CHARACTER_DAMAGE_BOOST}, which reaches wider:
	 * it says "Character" where this one says "Forward", so a Backup's or Monster's ability carries
	 * it too. That is why the Forward guard below is scoped to the narrower pattern rather than
	 * gating the whole method as it used to.
	 *
	 * <p>Two separate things are "Forward" here and must not be conflated: the guard asks what type
	 * the <em>damage source</em> is, which is what the narrower pattern restricts; the scan asks
	 * which of the caster's cards are <em>printing</em> a boost, and that is every card on their
	 * field. Building the scan from the Forward and Backup rows alone is what left Djinn 16-010H,
	 * a Monster, unable to boost anything.
	 */
	int applyCasterSideElementForwardDamageBoosts(int amount, boolean targetIsP1) {
		if (mw.currentAbilitySource == null) return amount;
		if (mw.currentAbilitySourceIsP1 == targetIsP1) return amount;
		boolean sourceIsForward = mw.currentAbilitySource.isForward();
		boolean casterIsP1 = mw.currentAbilitySourceIsP1;
		for (CardData booster : mw.fieldCards(casterIsP1)) {
			// A card stripped of its abilities prints nothing, so it boosts nothing.
			if (mw.lostAbilitiesCards.contains(booster)) continue;
			List<FieldAbility> fas = mw.effectiveFieldAbilities(booster);
			for (FieldAbility fa : fas) {
				Matcher cm = AutoAbilityTriggers.FA_ELEMENT_SUMMON_OR_CHARACTER_DAMAGE_BOOST.matcher(fa.effectText());
				if (!cm.matches()) continue;
				if (!AutoAbilityTriggers.characterArmCovers(cm, mw.currentAbilitySource, mw)) continue;
				int boost = Integer.parseInt(cm.group("amount"));
				int before = amount;
				amount += boost;
				mw.logEntry(booster.name() + " — " + AutoAbilityTriggers.characterArmLabel(cm)
						+ " ability damage increased by " + boost + " (" + before + " → " + amount + ")");
			}
			if (!sourceIsForward) continue;
			for (FieldAbility fa : fas) {
				Matcher m = AutoAbilityTriggers.FA_ELEMENT_FORWARD_DAMAGE_BOOST.matcher(fa.effectText());
				if (!m.find()) continue;
				if (!AutoAbilityTriggers.elementForwardBoostCovers(m, mw.currentAbilitySource, mw)) continue;
				int boost = Integer.parseInt(m.group("amount"));
				int before = amount;
				amount += boost;
				mw.logEntry(booster.name() + " — Forward ability damage increased by "
						+ boost + " (" + before + " → " + amount + ")");
			}
		}
		return amount;
	}

	/** Forward-zone overload — see {@link #modifyOutgoingCombatDamage(boolean, ForwardTarget.CardZone, int, int, CardData)}. */
	int modifyOutgoingCombatDamage(boolean isP1, int idx, int rawAmount, CardData target) {
		return modifyOutgoingCombatDamage(isP1, ForwardTarget.CardZone.FORWARD, idx, rawAmount, target);
	}

	/**
	 * Applies outgoing-damage modifiers for a card that is about to deal combat damage while
	 * acting as a Forward — from any zone (a real Forward, or a Monster/Backup temporarily a
	 * Forward). Checks and consumes the one-time "next outgoing damage = 0" shield.
	 *
	 * <p>The dealing card and its {@code target} are both combatants and so are Forwards for
	 * every eligible purpose; the friendly-element, cost-based, and self "deals damage to a
	 * Forward" boosts therefore apply regardless of the cards' printed card types.
	 */
	int modifyOutgoingCombatDamage(boolean isP1, ForwardTarget.CardZone zone, int idx, int rawAmount, CardData target) {
		CardData card = mw.fieldCombatant(isP1, zone, idx);
		if (card == null) return rawAmount;
		if (mw.nextOutgoingDmgZeroSet.remove(card)) return 0;
		if (mw.dealsNoCombatDamageSet.contains(card)) return 0;   // deals no damage for the whole battle
		// "If [card] deals damage … while dull, the damage becomes 0 instead" — Cagnazzo dulls
		// itself when it blocks, so this can flip mid-battle.
		if (mw.damageZeroedWhileDull(card)) {
			mw.logEntry(card.name() + " is dull — outgoing damage becomes 0");
			return 0;
		}
		int mult = mw.outgoingDmgMultiplierMap.getOrDefault(card, 1);
		if (mw.nextOutgoingDmgDoublerSet.remove(card)) mult *= 2;
		if (target != null) mult *= mw.fieldAbilityCombatOutgoingMult(card, target);
		int flat = (target != null) ? mw.outgoingDmgFlatBoostMap.getOrDefault(card, 0) : 0;

		if (target != null) {
			flat += mw.friendlyElementForwardCombatBoost(card, isP1);
			flat += mw.costBasedCombatFlatAdjustments(card, target);
			flat += mw.selfOutgoingFlatBoostVsForward(card, isP1);
		}
		return rawAmount * mult + flat;
	}

	boolean sourceHasOutgoingDmgToOpponentDoubler(CardData attacker) {
		if (attacker == null || mw.lostAbilitiesCards.contains(attacker)) return false;
		// A "Damage N --" gate belongs to the printing, and this reader holds the FieldAbility that
		// carries it — Ardyn 28-002R doubles nothing until his controller has taken 3.
		Boolean side = mw.fieldSideOf(attacker);
		int dmg = side == null ? 0
				: (side ? mw.gameState.getP1DamageZone() : mw.gameState.getP2DamageZone()).size();
		for (FieldAbility fa : mw.effectiveFieldAbilities(attacker)) {
			if (fa.damageThreshold() > 0 && dmg < fa.damageThreshold()) continue;
			for (String clause : CardData.selfPassiveClauses(fa.effectText(), attacker.name())) {
				Matcher m = AutoAbilityTriggers.FA_OUTGOING_DAMAGE_DOUBLER.matcher(clause);
				if (!m.find() || !m.group("card").trim().equalsIgnoreCase(attacker.name())) continue;
				// "a player" covers the opponent as surely as "your opponent" does; it is the wider
				// wording, not a different one.
				String target = m.group("target").toLowerCase();
				if (target.contains("opponent") || target.contains("player")) return true;
			}
		}
		return false;
	}

	/**
	 * The fixed number of damage points {@code attacker}'s "If [card] deals damage to your opponent,
	 * the damage becomes N instead" ability replaces its damage with, or {@code null} when it has no
	 * such ability. Reads granted abilities too, so an until-end-of-turn grant counts.
	 */
	Integer outgoingDamageToOpponentOverride(CardData attacker) {
		if (attacker == null || mw.lostAbilitiesCards.contains(attacker)) return null;
		for (FieldAbility fa : mw.effectiveFieldAbilities(attacker)) {
			Matcher m = AutoAbilityTriggers.FA_OUTGOING_DAMAGE_TO_OPPONENT_SETS_TO.matcher(fa.effectText());
			if (!m.find()) continue;
			// The subject may qualify the attacker rather than only name it — Lightning 26-098L
			// deals the 2 points only "forming a party", which is a board state read at the moment
			// the damage lands, not a property of the card.
			String subject = m.group("card").trim();
			Matcher partyM = AutoAbilityTriggers.FA_SUBJECT_FORMING_PARTY.matcher(subject);
			boolean partyRequired = partyM.matches();
			if (partyRequired) subject = partyM.group("name").trim();
			if (!subject.equalsIgnoreCase(attacker.name())) continue;
			if (partyRequired) {
				Boolean side = mw.fieldSideOf(attacker);
				if (side == null || !mw.isFormingParty(attacker, side)) continue;
			}
			return Integer.valueOf(m.group("amount"));
		}
		return null;
	}

	/** The points of combat damage {@code attacker} deals to the opposing player. */
	int combatDamagePointsToOpponent(CardData attacker) {
		// "(this includes player damage)" is what puts the while-dull replacement on this path too.
		if (mw.damageZeroedWhileDull(attacker)) return 0;
		Integer override = outgoingDamageToOpponentOverride(attacker);
		if (override != null) return override;
		return sourceHasOutgoingDmgToOpponentDoubler(attacker) ? 2 : 1;
	}

	/** Deals combat damage to the opponent — normally 1 point, but an outgoing-damage doubler or
	 *  "the damage becomes N instead" ability on the attacker changes the count (N may be 0) —
	 *  calling {@code afterDamage} after all damage points and any EX bursts have resolved. */
	void dealCombatDamageToOpponent(CardData attacker, boolean attackerIsP1, Runnable afterDamage) {
		if (mw.dealsNoCombatDamageSet.contains(attacker)) {
			mw.logEntry((attackerIsP1 ? "" : "[P2] ") + attacker.name() + " deals no damage this battle");
			afterDamage.run();
			return;
		}
		int points = combatDamagePointsToOpponent(attacker);
		if (points != 1)
			mw.logEntry((attackerIsP1 ? "" : "[P2] ") + attacker.name()
					+ " — combat damage to opponent is " + points + " instead of 1");
		dealOpponentDamagePoints(attacker, attackerIsP1, points, afterDamage);
	}

	/**
	 * Deals {@code remaining} points of damage to the opposing player one at a time, each point
	 * re-crediting {@code attacker} as the damage source (it is consumed per call) and the next
	 * point dealt from the previous one's completion callback so EX Bursts resolve in order.
	 */
	void dealOpponentDamagePoints(CardData attacker, boolean attackerIsP1, int remaining,
			Runnable afterDamage) {
		if (remaining <= 0) { afterDamage.run(); return; }
		mw.setPlayerDamageSource(attacker);
		Runnable next = () -> dealOpponentDamagePoints(attacker, attackerIsP1, remaining - 1, afterDamage);
		if (attackerIsP1) mw.p2TakeDamage(next); else mw.p1TakeDamage(next);
	}

	/**
	 * Applies incoming-damage modifiers, writes the result to the damage accumulator,
	 * and breaks the forward if accumulated damage reaches its effective power.
	 */
	void applyDamageToMonster(boolean isP1, int idx, int amount) {
		List<CardData> mons    = isP1 ? mw.p1MonsterCards  : mw.p2MonsterCards;
		List<Integer>  dmgList = isP1 ? mw.p1MonsterDamage : mw.p2MonsterDamage;
		if (idx >= mons.size() || amount <= 0) return;
		int accum  = dmgList.get(idx) + amount;
		dmgList.set(idx, accum);
		mw.recordDamagedBy(mons.get(idx), abilityDamageSource());
		boolean asFwd = isP1 ? mw.isP1MonsterTemporarilyForward(idx) : mw.isP2MonsterTemporarilyForward(idx);
		int effPow = asFwd ? (isP1 ? mw.p1MonsterForwardPower(idx) : mw.p2MonsterForwardPower(idx))
		                   : (isP1 ? mw.effectiveP1MonsterPower(idx) : mw.effectiveP2MonsterPower(idx));
		mw.logEntry((isP1 ? "" : "[P2] ") + mons.get(idx).name() + " takes " + amount + " damage"
				+ (effPow > 0 ? " (" + (effPow - accum) + " remaining)" : ""));
		if (effPow > 0 && accum >= effPow) {
			if (isP1) mw.autoAbilityTriggers.breakP1MonsterSlot(idx); else mw.breakP2MonsterSlot(idx);
		} else {
			if (isP1) mw.refreshP1MonsterSlot(idx); else mw.refreshP2MonsterSlot(idx);
		}
	}

	void applyDamageToForward(boolean isP1, int idx, int rawAmount, boolean fromAbility, boolean unreduced) {
		List<CardData>  fwds   = isP1 ? mw.p1ForwardCards   : mw.p2ForwardCards;
		List<Integer>   dmgList = isP1 ? mw.p1ForwardDamage  : mw.p2ForwardDamage;
		if (idx >= fwds.size()) return;
		// One-time damage redirect: "the next damage dealt to A is received by B instead"
		CardData redirectTarget = mw.nextIncomingDmgRedirectMap.remove(fwds.get(idx));
		if (redirectTarget != null) {
			int p1RedirIdx = mw.p1ForwardCards.indexOf(redirectTarget);
			int p2RedirIdx = mw.p2ForwardCards.indexOf(redirectTarget);
			if (p1RedirIdx >= 0) {
				mw.logEntry(fwds.get(idx).name() + " — damage redirected to " + redirectTarget.name());
				applyDamageToForward(true, p1RedirIdx, rawAmount, fromAbility, unreduced);
				return;
			} else if (p2RedirIdx >= 0) {
				mw.logEntry(fwds.get(idx).name() + " — damage redirected to " + redirectTarget.name());
				applyDamageToForward(false, p2RedirIdx, rawAmount, fromAbility, unreduced);
				return;
			}
			// redirect target no longer on field — fall through to normal damage
		}
		// Continuous redirect from a field ability (Daisy 18-060H, Tidus 26-112H). Resolved before
		// modifyIncomingDamage so the stand-in's own protections are the ones that apply, which is
		// the order the replacement happens in: the damage is dealt to the stand-in, and what it
		// then does about that damage is the stand-in's business.
		CardData standIn = mw.damageRedirectStandIn(fwds.get(idx), isP1);
		if (standIn != null) {
			int standInIdx = mw.identityIndexOf(fwds, standIn);
			if (standInIdx >= 0) {
				mw.logEntry(fwds.get(idx).name() + " — damage dealt to " + standIn.name() + " instead");
				applyDamageToForward(isP1, standInIdx, rawAmount, fromAbility, unreduced);
				return;
			}
		}
		int amount = modifyIncomingDamage(isP1, idx, rawAmount, fromAbility, unreduced);
		// try/finally so every one of the early returns below still pays out the shields the
		// reduction above just spent. Dealt here, at the end, because a kickback is damage:
		// it can break a Forward and renumber the zone this call is indexing into.
		try {
			if (amount <= 0) {
				mw.logEntry((isP1 ? "" : "[P2] ") + fwds.get(idx).name() + " — damage blocked");
				return;
			}
			int accum  = dmgList.get(idx) + amount;
			dmgList.set(idx, accum);
			(mw.turn(isP1).cardsTookDamageThisTurn).add(fwds.get(idx).name());
			mw.recordDamagedBy(fwds.get(idx), abilityDamageSource());
			int effPow = isP1 ? mw.effectiveP1ForwardPower(idx) : mw.effectiveP2ForwardPower(idx);
			mw.logEntry((isP1 ? "" : "[P2] ") + fwds.get(idx).name() + " takes " + amount + " damage"
					+ (effPow > 0 ? " (" + (effPow - accum) + " remaining)" : ""));
			// Fires on being dealt damage, so before the break check below — 28-043R Gi Nattak's
			// trigger still resolves when the damage is lethal.
			mw.autoAbilityTriggers.fireIsDealtDamageTriggers(fwds.get(idx), isP1);
			// "When this Forward is dealt damage, break this Forward." — Vallaide 22-020R's grant, on
			// the Forward that just took the damage. Ahead of the lethal check below because any damage
			// at all is enough, and a Forward the damage would have broken anyway leaves by this route.
			if (mw.breakOnDealtDamageGrant(isP1, ForwardTarget.CardZone.FORWARD, idx,
					fwds.get(idx), amount)) return;
			if (effPow > 0 && accum >= effPow) {
				CardData fwd = fwds.get(idx);
				if (isP1 ? mw.effectiveP1HasTrait(idx, CardData.Trait.CANNOT_BE_BROKEN)
				         : mw.effectiveP2HasTrait(idx, CardData.Trait.CANNOT_BE_BROKEN)) {
					mw.logEntry((isP1 ? "" : "[P2] ") + fwd.name() + " survives lethal damage (cannot be broken — damage clears at end of turn)");
					if (isP1) mw.refreshP1ForwardSlot(idx); else mw.refreshP2ForwardSlot(idx);
					if (mw.currentSummonSource != null)
						fireBreaktouchForDamage(mw.currentSummonSource, mw.currentSummonSourceIsP1, isP1, idx);
				} else {
					if (isP1) mw.breakP1Forward(idx); else mw.breakP2Forward(idx);
				}
			} else {
				if (isP1) mw.refreshP1ForwardSlot(idx); else mw.refreshP2ForwardSlot(idx);
				// Fire "deals damage to forward" triggers from tracked ability source (e.g. Ramuh + Lightning Summon)
				if (mw.currentSummonSource != null)
					fireBreaktouchForDamage(mw.currentSummonSource, mw.currentSummonSourceIsP1, isP1, idx);
			}
		} finally {
			mw.fireShieldKickbacks();
		}
	}

	/**
	 * The card credited with a non-combat instance of damage, for the per-turn record the
	 * "[a Forward] damaged by [X] …" printings read (Susano 14-011H, Galuf 15-066C and their
	 * family). The ability source is preferred over the Summon one: while an ability of a Character
	 * resolves, that Character is the damager, and {@code currentSummonSource} may still be holding
	 * an earlier caster. Null when neither is set, which {@code recordDamagedBy} treats as
	 * "nobody to credit".
	 */
	private CardData abilityDamageSource() {
		return mw.currentAbilitySource != null ? mw.currentAbilitySource : mw.currentSummonSource;
	}

	/** Forward-zone overload — see {@link #fireBreaktouchForDamage(CardData, boolean, boolean, ForwardTarget.CardZone, int)}. */
	boolean fireBreaktouchForDamage(CardData source, boolean sourceIsP1,
			boolean damagedIsP1, int damagedIdx) {
		return fireBreaktouchForDamage(source, sourceIsP1, damagedIsP1, ForwardTarget.CardZone.FORWARD, damagedIdx);
	}

	boolean fireBreaktouchForDamage(CardData source, boolean sourceIsP1,
			boolean damagedIsP1, ForwardTarget.CardZone damagedZone, int damagedIdx) {
		CardData damaged = mw.fieldCombatant(damagedIsP1, damagedZone, damagedIdx);
		if (damaged == null) return false;

		// Case 1: source card itself has "deals damage to forward" auto-ability
		for (AutoAbility fa : source.autoAbilities()) {
			if (!fa.trigger().equals("deals damage to forward")) continue;
			if (!fa.triggerCard().equalsIgnoreCase(source.name())) continue;
			// Not every trigger of this shape is Breaktouch. 4-039R Rogue dulls and Freezes the
			// damaged Forward instead, and breaking it here would be plainly wrong rather than
			// merely incomplete. Such effects name no target of their own — "it" is the card just
			// damaged — so they resolve with it preloaded.
			if (ActionResolver.isTriggeredTargetAction(fa.effectText())) {
				runOnDamagedCard(fa, source, sourceIsP1, damagedIsP1, damagedZone, damagedIdx);
				return false;
			}
			mw.logEntry((sourceIsP1 ? "" : "[P2] ") + source.name() + " — Breaktouch! "
					+ (damagedIsP1 ? "" : "[P2] ") + damaged.name() + " is broken.");
			mw.breakFieldCard(damagedIsP1, damagedZone, damagedIdx);
			return true;
		}

		// Case 2: source is a Summon of matching element; check caster's field for the Summon trigger
		if (source.isSummon()) {
			String[] sourceElems = source.elements();
			List<CardData> casterFwds = new ArrayList<>(sourceIsP1 ? mw.p1ForwardCards : mw.p2ForwardCards);
			for (CardData fieldCard : casterFwds) {
				for (AutoAbility fa : fieldCard.autoAbilities()) {
					String trig = fa.trigger();
					if (!trig.endsWith(" summon deals damage to forward")) continue;
					String elemPrefix = trig.substring(0, trig.indexOf(" summon")).toLowerCase(java.util.Locale.ROOT);
					boolean elemMatch = false;
					for (String e : sourceElems) {
						if (e.toLowerCase(java.util.Locale.ROOT).equals(elemPrefix)) { elemMatch = true; break; }
					}
					if (!elemMatch) continue;
					mw.logEntry((sourceIsP1 ? "" : "[P2] ") + fieldCard.name() + " — Breaktouch (Summon)! "
							+ (damagedIsP1 ? "" : "[P2] ") + damaged.name() + " is broken.");
					mw.breakFieldCard(damagedIsP1, damagedZone, damagedIdx);
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Resolves a "deals damage to a Forward" ability whose target is the card just damaged, with
	 * that card preloaded — the same route {@code AutoAbilityTriggers} uses for the watchers whose
	 * effects say "it" rather than naming a target.
	 */
	private void runOnDamagedCard(AutoAbility fa, CardData source, boolean sourceIsP1,
			boolean damagedIsP1, ForwardTarget.CardZone damagedZone, int damagedIdx) {
		Consumer<GameContext> effect = ActionResolver.parse(fa.effectText(), source);
		if (effect == null) return;

		GameContext ctx = mw.buildGameContext(sourceIsP1);
		ctx.preloadTargets(List.of(new ForwardTarget(damagedIsP1, damagedIdx, damagedZone)));
		CardData prevSource  = mw.currentAbilitySource;
		boolean  prevSpecial = mw.currentAbilityIsSpecial;
		mw.currentAbilitySource    = source;
		mw.currentAbilityIsSpecial = false;
		try {
			mw.logEntry((sourceIsP1 ? "" : "[P2] ") + source.name() + " — " + fa.effectText());
			effect.accept(ctx);
		} finally {
			mw.currentAbilitySource    = prevSource;
			mw.currentAbilityIsSpecial = prevSpecial;
		}
	}

	/** Applies ability/combat damage to a backup that is currently acting as a Forward. */
	void applyDamageToBackup(boolean isP1, int idx, int amount) {
		CardData[] backs = isP1 ? mw.p1BackupCards : mw.p2BackupCards;
		if (idx < 0 || idx >= backs.length || backs[idx] == null || amount <= 0) return;
		boolean asFwd = isP1 ? mw.isP1BackupTemporarilyForward(idx) : mw.isP2BackupTemporarilyForward(idx);
		if (!asFwd) return;
		CardData c = backs[idx];
		Map<CardData, Integer> dmgMap = isP1 ? mw.p1BackupForwardDamage : mw.p2BackupForwardDamage;
		int accum = dmgMap.getOrDefault(c, 0) + amount;
		dmgMap.put(c, accum);
		mw.recordDamagedBy(c, abilityDamageSource());
		int effPow = isP1 ? mw.p1BackupForwardPower(idx) : mw.p2BackupForwardPower(idx);
		mw.logEntry((isP1 ? "" : "[P2] ") + c.name() + " takes " + amount + " damage"
				+ (effPow > 0 ? " (" + (effPow - accum) + " remaining)" : ""));
		// See applyDamageToForward: a Backup acting as a Forward can carry the grant too.
		if (mw.breakOnDealtDamageGrant(isP1, ForwardTarget.CardZone.BACKUP, idx, c, amount)) return;
		if (effPow > 0 && accum >= effPow) {
			if (isP1) mw.autoAbilityTriggers.breakP1BackupSlot(idx); else mw.breakP2BackupSlot(idx);
		} else {
			if (isP1) mw.refreshP1BackupSlot(idx); else mw.refreshP2BackupSlot(idx);
		}
	}
}
