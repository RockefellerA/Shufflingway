package shufflingway;

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

    private void collectFieldTraits(CardData src, CardData target, boolean isP1,
            EnumSet<CardData.Trait> out) {
        if (mw.lostAbilitiesCards.contains(src)) return;
        for (IfControlBoost icb : src.ifControlBoosts())
            if (icb.appliesToCard(target) && mw.icbConditionsMet(icb, isP1))
                out.addAll(icb.grantedTraits());
        for (FieldPowerGrant fpg : src.fieldPowerGrants())
            if (!fpg.affectsOpponent() && fpg.appliesToCard(target) && mw.fpgBzConditionMet(fpg, isP1)
                    && (!fpg.yourTurnOnly() || isP1 == (mw.gameState.getCurrentPlayer() == GameState.Player.P1)))
                out.addAll(fpg.grantedTraits());
        // Self-targeted trait grants, optionally gated on damage threshold or job count.
        if (src == target) {
            int dmg = isP1 ? mw.gameState.getP1DamageZone().size() : mw.gameState.getP2DamageZone().size();
            for (FieldAbility fa : src.fieldAbilities()) {
                // Damage-gated (e.g., "Damage 1 -- Desch gains First Strike.")
                if (fa.damageThreshold() > 0 && dmg < fa.damageThreshold()) continue;
                out.addAll(CardData.parseSelfTraitGrant(fa.effectText(), src.name()));
                if (CardData.parseSelfNonDmgBreakShield(fa.effectText(), src.name())
                        || CardData.parseSelfNonDmgBreakShieldDirect(fa.effectText(), src.name()))
                    out.add(CardData.Trait.CANNOT_BE_BROKEN_BY_NON_DMG);
                // Job-count conditional ("If [name] has N Jobs or more, gains [traits].")
                int threshold = CardData.parseIfSelfJobCountTraitGrantThreshold(fa.effectText(), src.name());
                if (threshold >= 0 && countEffectiveJobs(src, isP1) >= threshold)
                    out.addAll(CardData.parseIfSelfJobCountTraitGrantTraits(fa.effectText()));
                // LB face-up count conditional ("If there are N or more face-up cards in your LB deck, [name] gains [traits].")
                int lbThreshold = CardData.parseIfSelfLbFaceUpCountTraitGrantThreshold(fa.effectText(), src.name());
                if (lbThreshold >= 0 && countFaceUpLbCards(isP1) >= lbThreshold)
                    out.addAll(CardData.parseIfSelfLbFaceUpCountTraitGrantTraits(fa.effectText()));
            }
        }
    }

    /** True if any field card on either side is suppressing Haste globally. */
    boolean isHasteSuppressedGlobally() {
        for (int side = 0; side < 2; side++) {
            boolean isP1 = side == 0;
            List<CardData> fwds = isP1 ? mw.p1ForwardCards : mw.p2ForwardCards;
            CardData[]     bkps = isP1 ? mw.p1BackupCards  : mw.p2BackupCards;
            List<CardData> mons = isP1 ? mw.p1MonsterCards : mw.p2MonsterCards;
            for (CardData c : fwds) if (!mw.lostAbilitiesCards.contains(c) && cardHasHasteSuppression(c)) return true;
            for (CardData c : bkps) if (c != null && !mw.lostAbilitiesCards.contains(c) && cardHasHasteSuppression(c)) return true;
            for (CardData c : mons) if (!mw.lostAbilitiesCards.contains(c) && cardHasHasteSuppression(c)) return true;
        }
        return false;
    }

    private boolean cardHasHasteSuppression(CardData card) {
        for (FieldAbility fa : card.fieldAbilities()) {
            if (AutoAbilityTriggers.FA_ALL_FORWARDS_LOSE_HASTE.matcher(fa.effectText()).find()) return true;
            if (AutoAbilityTriggers.FA_FORWARDS_CANNOT_GAIN_HASTE.matcher(fa.effectText()).find()) return true;
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
