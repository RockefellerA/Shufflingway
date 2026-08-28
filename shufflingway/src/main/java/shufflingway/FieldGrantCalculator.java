package shufflingway;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Computes field-granted traits for Forwards.
 *
 * <p>Handles conditional trait grants from {@link IfControlBoost}, {@link FieldPowerGrant},
 * and self-targeted {@link FieldAbility} text, including damage-threshold, job-count, and
 * LB-deck face-up count gates.  Also owns the global Haste-suppression check.
 */
class FieldGrantCalculator {

    private final MainWindow mw;

    FieldGrantCalculator(MainWindow mw) {
        this.mw = mw;
    }

    /**
     * Collects all traits conditionally granted to {@code target} on the given player's side
     * by any active {@link IfControlBoost} or {@link FieldPowerGrant} on the field.
     */
    EnumSet<CardData.Trait> computeConditionalTraitsForTarget(CardData target, boolean isP1) {
        EnumSet<CardData.Trait> out = EnumSet.noneOf(CardData.Trait.class);
        List<CardData> fwds = isP1 ? mw.p1ForwardCards : mw.p2ForwardCards;
        CardData[]     bkps = isP1 ? mw.p1BackupCards  : mw.p2BackupCards;
        List<CardData> mons = isP1 ? mw.p1MonsterCards : mw.p2MonsterCards;
        for (CardData src : fwds) collectFieldTraits(src, target, isP1, out);
        for (CardData bkp : bkps) if (bkp != null) collectFieldTraits(bkp, target, isP1, out);
        for (CardData src : mons) collectFieldTraits(src, target, isP1, out);
        return out;
    }

    /**
     * Which of {@code candidates} appear on at least one Forward in the given player's Break Zone.
     *
     * <p>Printed traits only. A card in the Break Zone is not on the field, so nothing is granting
     * it anything — what it "has" is what it was printed with, which is also the only thing a
     * {@link CardData} in a zone list carries.
     */
    private EnumSet<CardData.Trait> traitsOnBreakZoneForwards(
            EnumSet<CardData.Trait> candidates, boolean isP1) {
        EnumSet<CardData.Trait> found = EnumSet.noneOf(CardData.Trait.class);
        for (CardData bz : isP1 ? mw.gameState.getP1BreakZone() : mw.gameState.getP2BreakZone()) {
            if (bz == null || !bz.isForward()) continue;
            for (CardData.Trait t : candidates)
                if (bz.traits().contains(t)) found.add(t);
            if (found.size() == candidates.size()) break;
        }
        return found;
    }

    private void collectFieldTraits(CardData src, CardData target, boolean isP1,
            EnumSet<CardData.Trait> out) {
        if (mw.lostAbilitiesCards.contains(src)) return;
        for (IfControlBoost icb : src.ifControlBoosts())
            if (icb.appliesToCard(target, mw.jobsStripped(target)) && mw.icbConditionsMet(icb, isP1))
                out.addAll(icb.grantedTraits());
        for (FieldPowerGrant fpg : src.fieldPowerGrants())
            if (!fpg.affectsOpponent()
                    && fpg.appliesToCard(target, mw.fpgTargetTraits(fpg, target, isP1),
                            mw.jobsStripped(target))
                    && mw.fpgBzConditionMet(fpg, isP1)
                    && mw.fpgPartyConditionMet(fpg, src, target, isP1)
                    && mw.fpgTurnWindowOpen(fpg, isP1))
                out.addAll(fpg.grantedTraits());
        int dmg = isP1 ? mw.gameState.getP1DamageZone().size() : mw.gameState.getP2DamageZone().size();
        // "The [filter] you control cannot be broken by … that don't deal damage." — a grant to a
        // filtered set, so it runs for every target including the printing card when it matches
        // its own filter (Celestia is a Water Character; Rasler is not named Ashe).
        for (FieldAbility fa : src.fieldAbilities()) {
            if (fa.damageThreshold() > 0 && dmg < fa.damageThreshold()) continue;
            // Tifa 11-071L prints this shield inside a quoted ability she hands herself, so the
            // wrapper comes off before the grant is read. The gate the wrapper carried is already
            // on the FieldAbility and was checked above.
            String text = fa.effectText();
            String granted = CardData.selfGrantedFieldAbility(text, src.name());
            if (granted != null) text = granted;
            CardData.NonDmgBreakShieldGrant g = CardData.parseFieldNonDmgBreakShieldGrant(text);
            if (g != null && g.appliesToCard(target, mw.jobsStripped(target)))
                out.add(CardData.Trait.CANNOT_BE_BROKEN_BY_NON_DMG);
        }
        // Self-targeted trait grants, optionally gated on damage threshold or job count.
        if (src == target) {
            for (FieldAbility fa : src.fieldAbilities()) {
                // Damage-gated (e.g., "Damage 1 -- Desch gains First Strike.")
                if (fa.damageThreshold() > 0 && dmg < fa.damageThreshold()) continue;
                out.addAll(CardData.parseSelfTraitGrant(fa.effectText(), src.name()));
                // Gogo 4-127H: the sentence lists keywords to look for, and he gains whichever of
                // them a Forward in his controller's Break Zone actually has. Resolved here rather
                // than at parse time because the answer is board state and changes as the Break
                // Zone does.
                EnumSet<CardData.Trait> bzCandidates =
                        CardData.parseBreakZoneTraitGrantCandidates(fa.effectText(), src.name());
                if (!bzCandidates.isEmpty()) out.addAll(traitsOnBreakZoneForwards(bzCandidates, isP1));
                // The same grant spelled with a quoted ability alongside the traits
                // ("Yumcax gains Brave and \"When Yumcax …\"") — only the trait half is a trait.
                // Machina 15-017H gates the identical shape on a Forward count instead of on
                // damage; the gate comes off here and the remainder is read as any other grant.
                String grantText = fa.effectText();
                CardData.MaxForwardsGatedGrant gate = CardData.parseMaxForwardsGatedGrant(grantText);
                if (gate != null) {
                    if (mw.forwardCount(isP1) > gate.maxForwards()) continue;
                    grantText = gate.remainder();
                }
                // Firion 21-099H's gate, read the same way: strip it, and let the parsers below
                // take the remainder. Null means the opposing board does not meet it.
                grantText = mw.oppDullCharsGrantRemainder(grantText, isP1);
                if (grantText == null) continue;
                out.addAll(CardData.parseSelfTraitGrant(grantText, src.name()));
                CardData.SelfGainsQuotedGrant quoted =
                        CardData.parseSelfGainsQuotedGrant(grantText, src.name());
                if (quoted != null) out.addAll(quoted.traits());
                if (CardData.parseSelfNonDmgBreakShield(fa.effectText(), src.name())
                        || CardData.parseSelfNonDmgBreakShieldDirect(fa.effectText(), src.name()))
                    out.add(CardData.Trait.CANNOT_BE_BROKEN_BY_NON_DMG);
                // Job-count conditional ("If [name] has N Jobs or more, gains [traits].")
                int threshold = CardData.parseIfSelfJobCountTraitGrantThreshold(fa.effectText(), src.name());
                if (threshold >= 0 && countEffectiveJobs(src, isP1) >= threshold)
                    out.addAll(CardData.parseIfSelfJobCountTraitGrantTraits(fa.effectText()));
                // Self-power conditional ("If Ramza has 4000 power or more, Ramza gains Haste.")
                // — Ramza 7-104H, who prints three of these at rising thresholds. Read against
                // current power, so his own 《Lightning》 boost is what unlocks them.
                int powerThreshold = CardData.parseIfSelfPowerTraitGrantThreshold(fa.effectText(), src.name());
                if (powerThreshold >= 0 && currentPower(src, isP1) >= powerThreshold)
                    out.addAll(CardData.parseIfSelfPowerTraitGrantTraits(fa.effectText()));
                // LB face-up count conditional ("If there are N or more face-up cards in your LB deck, [name] gains [traits].")
                int lbThreshold = CardData.parseIfSelfLbFaceUpCountTraitGrantThreshold(fa.effectText(), src.name());
                if (lbThreshold >= 0 && countFaceUpLbCards(isP1) >= lbThreshold)
                    out.addAll(CardData.parseIfSelfLbFaceUpCountTraitGrantTraits(fa.effectText()));
                // Opponent-hand-size conditional ("If your opponent has N cards or less in their hand, [name] cannot be broken.")
                int oppHandThreshold = CardData.parseIfOpponentHandSizeCannotBeBrokenThreshold(fa.effectText(), src.name());
                if (oppHandThreshold >= 0) {
                    int oppHandSize = isP1 ? mw.gameState.getP2Hand().size() : mw.gameState.getP1Hand().size();
                    if (oppHandSize <= oppHandThreshold) out.add(CardData.Trait.CANNOT_BE_BROKEN);
                }
                // Hand-size conditional ("If either player has 2 cards or less in their hands,
                // [name] gains Haste.") — Squall 16-011L. The multi-attack permission the same
                // grant can carry is read separately, by MainWindow.maxAttacksPerTurn.
                CardData.HandSizeSelfGrant handGrant =
                        CardData.parseHandSizeSelfGrant(fa.effectText(), src.name());
                if (handGrant != null && handGrant.conditionMet(
                        (isP1 ? mw.gameState.getP1Hand() : mw.gameState.getP2Hand()).size(),
                        (isP1 ? mw.gameState.getP2Hand() : mw.gameState.getP1Hand()).size()))
                    out.addAll(handGrant.traits());
                // Own-turn conditional ("Galuf cannot be broken during your turn.")
                if (CardData.parseSelfCannotBeBrokenDuringYourTurn(fa.effectText(), src.name())
                        && isP1 == (mw.gameState.getCurrentPlayer() == GameState.Player.P1))
                    out.add(CardData.Trait.CANNOT_BE_BROKEN);
                // Attack-Phase conditional ("During each Attack Phase, Galuf cannot be broken.")
                // "each" covers both players' Attack Phases, so this is not gated on whose turn it is.
                if (CardData.parseSelfCannotBeBrokenDuringAttackPhase(fa.effectText(), src.name())
                        && mw.gameState.getCurrentPhase() == GameState.GamePhase.ATTACK)
                    out.add(CardData.Trait.CANNOT_BE_BROKEN);
                // Counter conditional ("If a Fortune Counter is placed on Llednar, Llednar cannot be broken.")
                String cbbCounter = CardData.parseSelfCannotBeBrokenWithCounter(fa.effectText(), src.name());
                if (cbbCounter != null
                        && mw.gameState.getCountersMap(src).getOrDefault(cbbCounter, 0) > 0)
                    out.add(CardData.Trait.CANNOT_BE_BROKEN);
                // Control-count conditional ("If you control 5 or more Water Characters, [name]
                // cannot be broken by …") — Gilgamesh (XI) 10-111H, Gilgamesh 22-061L.
                ControlCondition shieldCond =
                        CardData.parseIfControlNonDmgBreakShield(fa.effectText(), src.name());
                if (shieldCond != null && mw.controlConditionMet(shieldCond, isP1))
                    out.add(CardData.Trait.CANNOT_BE_BROKEN_BY_NON_DMG);
            }
        }
    }

    /**
     * True when Haste is suppressed for the Forwards {@code targetIsP1} controls.
     *
     * <p>Two scopes reach this. Edward 2-031C's "All Forwards lose Haste." names no player, so a
     * copy on either side binds both. The Magus Sisters (XIV) 20-083R's "The Forwards opponent
     * controls lose Haste." binds one side only — the side facing whoever controls it — so it is
     * read off the opposing field rather than off both.
     *
     * <p>"lose Haste" and "cannot gain Haste" are treated as one suppression. Both printings that
     * carry either sentence carry both of them together, so no corpus card distinguishes stripping
     * a printed Haste from barring a granted one; splitting them would be modelling a case that
     * does not exist yet.
     */
    boolean isHasteSuppressedFor(boolean targetIsP1) {
        for (int side = 0; side < 2; side++) {
            boolean srcIsP1 = side == 0;
            // A card only suppresses its opponent's Forwards, so its own side is exempt from the
            // one-sided sentence while the unqualified one still reaches everyone.
            boolean oppScopeApplies = srcIsP1 != targetIsP1;
            for (CardData c : fieldCards(srcIsP1))
                if (c != null && !mw.lostAbilitiesCards.contains(c)
                        && cardHasHasteSuppression(c, oppScopeApplies)) return true;
        }
        return false;
    }

    /**
     * {@code card}'s power as it stands on {@code isP1}'s field, for a grant that gates itself on
     * its own power.
     *
     * <p>Current power rather than printed, the same reading {@code MainWindow.canActivateAbility}
     * gives Hyoh 16-097H's "You can only use this ability if Hyoh has 7000 power or more" — the
     * wording only earns its place on a card that expects to be boosted past it.
     *
     * <p>Safe against the recursion this would otherwise invite: the power path never consults
     * {@link #computeConditionalTraitsForTarget}, which is exactly why
     * {@code MainWindow.fpgTargetTraits} resolves its trait filter off the raw maps.
     *
     * <p>Only Forwards print the wording today. The Monster arm is here because
     * {@code fieldForwardPower} would answer 0 for a Monster that is not currently acting as a
     * Forward, and a wrong number is worse than a longer switch.
     */
    private int currentPower(CardData card, boolean isP1) {
        ForwardTarget slot = mw.findFieldSlot(card, isP1);
        if (slot == null) return card.power();
        return switch (slot.zone()) {
            case FORWARD -> mw.fieldForwardPower(isP1, slot.zone(), slot.idx());
            case MONSTER -> isP1 ? mw.effectiveP1MonsterPower(slot.idx())
                                 : mw.effectiveP2MonsterPower(slot.idx());
            default      -> card.power();
        };
    }

    /** Every card on {@code isP1}'s field: Forwards, Backups and Monsters. */
    private List<CardData> fieldCards(boolean isP1) {
        List<CardData> out = new ArrayList<>(isP1 ? mw.p1ForwardCards : mw.p2ForwardCards);
        for (CardData b : (isP1 ? mw.p1BackupCards : mw.p2BackupCards)) if (b != null) out.add(b);
        out.addAll(isP1 ? mw.p1MonsterCards : mw.p2MonsterCards);
        return out;
    }

    /**
     * @param inclOpponentScoped whether the card's one-sided sentences count for the side being
     *     asked about; false when the question is about the card's own controller
     */
    private boolean cardHasHasteSuppression(CardData card, boolean inclOpponentScoped) {
        for (FieldAbility fa : card.fieldAbilities()) {
            if (AutoAbilityTriggers.FA_ALL_FORWARDS_LOSE_HASTE.matcher(fa.effectText()).find()) return true;
            if (AutoAbilityTriggers.FA_FORWARDS_CANNOT_GAIN_HASTE.matcher(fa.effectText()).find()) return true;
            if (!inclOpponentScoped) continue;
            if (AutoAbilityTriggers.FA_OPP_FORWARDS_LOSE_HASTE.matcher(fa.effectText()).find()) return true;
            if (AutoAbilityTriggers.FA_OPP_FORWARDS_CANNOT_GAIN_HASTE.matcher(fa.effectText()).find()) return true;
        }
        return false;
    }

    /** Face-up LB deck cards = spent indices minus any LB card still on the field (which hasn't returned yet). */
    private int countFaceUpLbCards(boolean isP1) {
        Set<Integer> spent = isP1 ? mw.spentLbIndices : mw.p2SpentLbIndices;
        List<CardData> fwds = isP1 ? mw.p1ForwardCards : mw.p2ForwardCards;
        List<CardData> mons = isP1 ? mw.p1MonsterCards : mw.p2MonsterCards;
        long onField = fwds.stream().filter(CardData::isLb).count()
                     + mons.stream().filter(CardData::isLb).count();
        return (int) (spent.size() - onField);
    }

    private int countEffectiveJobs(CardData card, boolean isP1) {
        Set<String> jobs = new HashSet<>(card.jobs());
        if (card.hasJobsOfControlledForwards()) {
            for (CardData fwd : (isP1 ? mw.p1ForwardCards : mw.p2ForwardCards))
                jobs.addAll(fwd.jobs());
        }
        return jobs.size();
    }
}
