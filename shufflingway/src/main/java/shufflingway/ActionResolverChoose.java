package shufflingway;

import static shufflingway.ActionResolverPatterns.*;

import static shufflingway.ActionResolver.*;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Choose parsers split out of {@link ActionResolver}.
 *
 * <p>Bodies only: {@code ActionResolver} keeps every dispatch chain and calls these
 * through a wildcard static import, so call order -- which is load-bearing, because
 * matchers use {@code find()} -- is unchanged.
 */
final class ActionResolverChoose {

	private ActionResolverChoose() {}


    // =========================================================================================
    // Two Break-Zone Forwards; select following actions
    // =========================================================================================
    /**
     * Parses Mont Leonis 22-113L's Break Zone recursion — see
     * {@link ActionResolverPatterns#CHOOSE_TWO_BZ_FWD_PLAY_IF_CONTROL}.
     *
     * <p>Two rulings shape this, both from the official FAQ:
     * <ul>
     *   <li>Unless <em>both</em> Forwards can be chosen the auto-ability is never placed on the
     *       stack, so the effect checks the Break Zone up front and does nothing at all rather
     *       than opening a dialog for a single target.</li>
     *   <li>The control condition governs the whole tail: too few Backups means no play, no trait
     *       grant, and no sacrifice either.</li>
     * </ul>
     *
     * <p>Targets are still chosen before the condition is tested, which is the printed order —
     * the condition sits in a later sentence than the choose.
     */
    static Consumer<GameContext> tryParseChooseTwoBzFwdPlayIfControl(String text, CardData source) {
        Matcher m = CHOOSE_TWO_BZ_FWD_PLAY_IF_CONTROL.matcher(text.trim());
        if (!m.find()) return null;

        ControlCondition cc = CardData.parseControlCondition(m.group("cond").trim());
        if (cc == null) return null;

        CardData.Trait trait = switch (m.group("trait").toLowerCase(java.util.Locale.ROOT).replace(" ", "_")) {
            case "haste"        -> CardData.Trait.HASTE;
            case "brave"        -> CardData.Trait.BRAVE;
            case "first_strike" -> CardData.Trait.FIRST_STRIKE;
            default             -> null;
        };
        if (trait == null) return null;

        String elem1 = m.group("elem1").toLowerCase(java.util.Locale.ROOT);
        String elem2 = m.group("elem2").toLowerCase(java.util.Locale.ROOT);
        int    cost1 = Integer.parseInt(m.group("cost1"));
        int    cost2 = Integer.parseInt(m.group("cost2"));

        int     sacCount = m.group("sacn") != null ? Integer.parseInt(m.group("sacn")) : 0;
        String  sacType  = m.group("sactype") != null ? m.group("sactype") : null;
        boolean sacFwd = sacType != null && sacType.matches("(?i)Forwards?|Characters?");
        boolean sacBkp = sacType != null && sacType.matches("(?i)Backups?|Characters?");
        boolean sacMon = sacType != null && sacType.matches("(?i)Monsters?|Characters?");

        String label = "Choose 1 " + m.group("elem1") + " Forward of cost " + cost1 + " or less and 1 "
                + m.group("elem2") + " Forward of cost " + cost2 + " or less in your Break Zone";

        return ctx -> {
            // The ability only goes on the stack when both Forwards are available, so a Break Zone
            // that cannot supply both produces no dialog at all.
            int narrowPool = ctx.countSelfBreakZoneMatching(true, false, false, false, elem1, cost1);
            int widePool   = ctx.countSelfBreakZoneMatching(true, false, false, false, elem2, cost2);
            // With cost1 <= cost2 on the same element the narrow pool is a subset of the wide one,
            // so two distinct cards need the wide pool to hold at least two.
            if (narrowPool == 0 || widePool < 2) {
                ctx.logEntry(label + " — cannot choose both, ability does not trigger");
                return;
            }

            ctx.logChooseHeader(label);
            List<ForwardTarget> chosen = ctx.selectTwoOwnBreakZoneForwards(elem1, cost1, cost2);
            if (chosen.size() < 2) return;

            if (!ctx.controlConditionMet(cc)) {
                ctx.logEntry("Effect: control condition not met — no play, no "
                        + trait.displayName() + ", and no Break Zone cost");
                return;
            }

            // Play the higher Break Zone index first: playing removes the card, which would shift
            // any lower index that had not been played yet.
            List<ForwardTarget> order = new ArrayList<>(chosen);
            order.sort((a, b) -> Integer.compare(b.idx(), a.idx()));
            EnumSet<CardData.Trait> granted = EnumSet.of(trait);
            for (ForwardTarget t : order) {
                ForwardTarget landed = ctx.playTargetOntoField(t);
                if (landed != null) ctx.boostTarget(landed, 0, granted);
            }

            if (sacCount > 0) {
                List<ForwardTarget> sacrifice = selectTargets(ctx, sacCount, false, false, true,
                        null, null, null, false, -1, null, -1, null,
                        sacFwd, sacBkp, sacMon, null, null, null, null, false, null, false);
                sortedByIdxDesc(sacrifice, true) .forEach(ctx::forceTargetToBreakZone);
                sortedByIdxDesc(sacrifice, false).forEach(ctx::forceTargetToBreakZone);
            }
        };
    }

    /**
     * Parses "[if cond,] Select N of the M following actions. "a" "b" ...".
     * Returns an effect that asks the player to choose {@code select} of the quoted
     * sub-actions (via {@link GameContext#chooseActions}), then re-parses and applies
     * each chosen sub-action. Returns {@code null} if the text is not this shape.
     */
    static Consumer<GameContext> tryParseSelectFollowingActions(String text, CardData source) {
        Matcher m = SELECT_FOLLOWING_ACTIONS.matcher(text);
        if (!m.find()) return null;

        final boolean baseUpTo      = m.group("upTo") != null;
        final int     baseSelect    = Integer.parseInt(m.group("select"));
        String actionsRaw = m.group("actions");

        // "If you selected N actions, the cost required to cast [Self] is increased by 《C》《C》."
        // -- Bahamut SIN 28-087H. The text reads as though the actions were picked first and
        // billed afterwards, but a cast pays before it resolves: the surcharge is offered on the
        // play menu as an ordinary extra cost, and what arrives here is whether it was paid.
        // Paid buys exactly N actions, since that is what the payment was for; unpaid leaves the
        // player free to take up to N-1 of them.
        final SelectActionsSurcharge surcharge = source != null
                ? selectActionsSurcharge(actionsRaw, source.name()) : null;

        // Detect inline conditional upgrade:
        // "If you control N or more [E] [T], select [up to] M of the K following actions instead."
        final boolean hasCondUpgrade;
        final int     condMinCount;
        final String  condElem;
        final boolean condInclFwd, condInclBkp, condInclMon;
        final boolean condUpTo;
        final int     condSelect;

        Matcher upgradeM = SELECT_FOLLOWING_ACTIONS_CONDITIONAL_UPGRADE.matcher(actionsRaw);
        if (upgradeM.find()) {
            hasCondUpgrade = true;
            condMinCount   = Integer.parseInt(upgradeM.group("condCount"));
            condElem       = upgradeM.group("condElement");
            String ct      = upgradeM.group("condType").toLowerCase();
            condInclFwd    = ct.startsWith("forward") || ct.startsWith("character");
            condInclBkp    = ct.startsWith("backup")  || ct.startsWith("character");
            condInclMon    = ct.startsWith("monster")  || ct.startsWith("character");
            condUpTo       = upgradeM.group("condUpTo") != null;
            condSelect     = Integer.parseInt(upgradeM.group("condSelect"));
            actionsRaw     = actionsRaw.substring(upgradeM.end());
        } else {
            hasCondUpgrade = false;
            condMinCount   = 0; condElem = null;
            condInclFwd    = false; condInclBkp = false; condInclMon = false;
            condUpTo       = false; condSelect   = 0;
        }

        // Detect an opponent-hand-size upgrade:
        // "If your opponent has [no|N cards or less] cards in their hand, select [up to] M ... instead."
        final boolean hasHandUpgrade;
        final int     handUpgThreshold;
        final boolean handUpgUpTo;
        final int     handUpgSelect;

        Matcher handUpM = SELECT_FOLLOWING_ACTIONS_HAND_UPGRADE.matcher(actionsRaw);
        if (handUpM.find()) {
            hasHandUpgrade   = true;
            handUpgThreshold = handUpM.group("handCount") != null ? Integer.parseInt(handUpM.group("handCount")) : 0;
            handUpgUpTo      = handUpM.group("handUpTo") != null;
            handUpgSelect    = Integer.parseInt(handUpM.group("handSelect"));
            actionsRaw       = actionsRaw.substring(handUpM.end());
        } else {
            hasHandUpgrade   = false;
            handUpgThreshold = 0; handUpgUpTo = false; handUpgSelect = 0;
        }

        Matcher qm = SELECT_FOLLOWING_QUOTED_ACTION.matcher(actionsRaw);
        List<String> actions = new ArrayList<>();
        while (qm.find()) actions.add(qm.group(1).trim());
        if (actions.isEmpty()) return null;

        return ctx -> {
            int     effSelect = baseSelect;
            boolean effUpTo   = baseUpTo;
            if (surcharge != null) {
                boolean paid = ctx.wasExtraCostPaid();
                effSelect = paid ? surcharge.actions() : surcharge.actions() - 1;
                effUpTo   = !paid;
                ctx.logEntry("Select actions — extra cost " + (paid ? "paid" : "not paid")
                        + ", may select " + (paid ? "" : "up to ") + effSelect);
                if (effSelect <= 0) return;
            }
            if (hasCondUpgrade
                    && ctx.selfFieldCount(condElem, condInclFwd, condInclBkp, condInclMon) >= condMinCount) {
                effSelect = condSelect;
                effUpTo   = condUpTo;
            }
            if (hasHandUpgrade && ctx.opponentHandSize() <= handUpgThreshold) {
                effSelect = handUpgSelect;
                effUpTo   = handUpgUpTo;
            }
            List<String> chosen = ctx.chooseActions(source, actions, effSelect, effUpTo);
            if (chosen == null || chosen.isEmpty()) {
                ctx.logEntry("Select actions — none chosen");
                return;
            }
            for (String actionText : chosen) {
                Consumer<GameContext> effect = parse(actionText, source);
                if (effect == null) {
                    ctx.logEntry("Select actions — unrecognized: " + actionText);
                } else {
                    ctx.logEntry((ctx.isP1() ? "Selected: " : "AI selected ") + actionText);
                    effect.accept(ctx);
                }
            }
        };
    }

    // =========================================================================================
    // Choose one each; "the former / the latter"
    // =========================================================================================
    /**
     * Parses "Choose [up to] N [condition] [element] [targets] [of cost X] [control] [zone]
     * [sep] followup".
     *
     * <p>Supported target types: Forward(s), Forward(s) or Monster(s), Backup(s), Character(s).
     * <p>Supported followup actions:
     * <ul>
     *   <li>"Deal [it|them] N damage"                        — fixed damage to each chosen target</li>
     *   <li>"Deal it damage equal to the highest power Forward you control" — damage = highest P1 forward power</li>
     *   <li>"Deal it damage equal to &lt;name&gt;'s power"          — damage = named field card's power</li>
     *   <li>"Deal it damage equal to half of &lt;name&gt;'s power"  — damage = floor(named power / 2) to nearest 1000</li>
     *   <li>"Deal it damage equal to its power [minus N]"    — damage = target's own power (minus N)</li>
     *   <li>"Dull it/them"                 — dulls each chosen target</li>
     *   <li>"Freeze it/them"               — freezes each chosen target</li>
     *   <li>"Dull it/them and freeze…"     — dulls and freezes each chosen target</li>
     *   <li>"Break it/them"                — breaks each chosen target</li>
     *   <li>"Remove it/them from the game" — removes each chosen target from the game</li>
     *   <li>"Play it/them onto the field"  — moves chosen targets from their zone onto the field</li>
     *   <li>"Add it/them to your hand"     — moves chosen targets to P1's hand</li>
     *   <li>"Return it to its owner's hand" — returns chosen forward to its owner's hand</li>
     *   <li>"Return it to your hand"        — returns chosen forward to P1's hand</li>
     *   <li>"it cannot block this turn"    — marks chosen forward as ineligible to block this turn</li>
     *   <li>"If possible, it must block this turn" — marks chosen forward as required to block if eligible</li>
     *   <li>"Put it at the top or bottom of its owner's deck" — player chooses placement</li>
     * </ul>
     */
    static Consumer<GameContext> tryParseChooseOneEach(String text, CardData source) {
        Matcher m = CHOOSE_ONE_EACH_PATTERN.matcher(text);
        if (!m.find()) return null;

        int    count1     = Integer.parseInt(m.group("count1"));
        String targets1   = m.group("targets1");
        String tgt1Lower  = targets1.toLowerCase();
        boolean fwd1 = tgt1Lower.contains("forward") || tgt1Lower.contains("character");
        boolean bak1 = tgt1Lower.contains("backup")  || tgt1Lower.contains("character");
        boolean mon1 = tgt1Lower.contains("monster") || tgt1Lower.contains("character");

        int    count2     = Integer.parseInt(m.group("count2"));
        String targets2   = m.group("targets2");
        String tgt2Lower  = targets2.toLowerCase();
        boolean fwd2 = tgt2Lower.contains("forward") || tgt2Lower.contains("character");
        boolean bak2 = tgt2Lower.contains("backup")  || tgt2Lower.contains("character");
        boolean mon2 = tgt2Lower.contains("monster") || tgt2Lower.contains("character");

        // "Job Monk Forward or Card Name Monk Forward" — supplied together, the selection layer
        // reads these as an either/or rather than an and, which is the wording's meaning.
        String job1  = m.group("job1")  != null ? m.group("job1").trim()  : null;
        String name1 = m.group("name1") != null ? m.group("name1").trim() : null;

        String followup  = m.group("followup").trim();
        String qualifier = job1 != null ? "Job " + job1 + " or Card Name " + name1 + " " : "";
        String logPrefix = "Choose " + count1 + " " + qualifier + targets1 + " (yours) and "
                + count2 + " " + targets2 + " (opponent)";

        if (FOLLOWUP_RETURN_TO_OWNERS_HAND.matcher(followup).find()) {
            return ctx -> {
                ctx.logEntry(logPrefix + " — Return to owner's hand");
                List<ForwardTarget> selfTs = selectTargets(ctx, count1, false,
                        false, true, null, null, null, false, -1, null, -1, null,
                        fwd1, bak1, mon1, job1, name1, null, null, false, null, false);
                List<ForwardTarget> oppTs = selectTargets(ctx, count2, false,
                        true, false, null, null, null, false, -1, null, -1, null,
                        fwd2, bak2, mon2, null, null, null, null, false, null, false);
                List<ForwardTarget> all = new ArrayList<>(selfTs);
                all.addAll(oppTs);
                returnTargetsToOwnersHand(ctx, all);
            };
        }

        if (FOLLOWUP_EACH_FORWARD_MUTUAL_POWER_DAMAGE.matcher(followup).find()) {
            return ctx -> {
                ctx.logEntry(logPrefix + " — Each deals damage equal to its power to the other");
                List<ForwardTarget> selfTs = selectTargets(ctx, count1, false,
                        false, true, null, null, null, false, -1, null, -1, null,
                        fwd1, bak1, mon1, job1, name1, null, null, false, null, false);
                List<ForwardTarget> oppTs = selectTargets(ctx, count2, false,
                        true, false, null, null, null, false, -1, null, -1, null,
                        fwd2, bak2, mon2, null, null, null, null, false, null, false);
                if (selfTs.isEmpty() || oppTs.isEmpty()) return;
                ForwardTarget selfT = selfTs.get(0);
                ForwardTarget oppT  = oppTs.get(0);
                // Snapshot both powers before either damage is applied
                int selfPower = Math.max(0, ctx.effectiveTargetPower(selfT));
                int oppPower  = Math.max(0, ctx.effectiveTargetPower(oppT));
                ctx.logEntry("Mutual damage: self Forward (" + selfPower + ") ↔ opp Forward (" + oppPower + ")");
                ctx.damageTarget(selfT, oppPower);
                ctx.damageTarget(oppT,  selfPower);
            };
        }

        Matcher btpM = FORMER_BOOST_THEN_POWER_DAMAGE_TO_LATTER.matcher(followup);
        if (btpM.find()) {
            int boost = Integer.parseInt(btpM.group("boost"));
            EnumSet<CardData.Trait> noTraits = EnumSet.noneOf(CardData.Trait.class);
            return ctx -> {
                ctx.logEntry(logPrefix + " — boost former +" + boost + ", deal its power to latter");
                List<ForwardTarget> selfTs = selectTargets(ctx, count1, false,
                        false, true, null, null, null, false, -1, null, -1, null,
                        fwd1, bak1, mon1, job1, name1, null, null, false, null, false);
                List<ForwardTarget> oppTs = selectTargets(ctx, count2, false,
                        true, false, null, null, null, false, -1, null, -1, null,
                        fwd2, bak2, mon2, null, null, null, null, false, null, false);
                if (selfTs.isEmpty() || oppTs.isEmpty()) return;
                ctx.boostTarget(selfTs.get(0), boost, noTraits);
                int power = Math.max(0, ctx.effectiveTargetPower(selfTs.get(0)));
                ctx.logEntry("Former power after boost: " + power + " → dealing to latter");
                ctx.damageTarget(oppTs.get(0), power);
            };
        }

        return null;
    }
    static Consumer<GameContext> tryParseChooseFormerLatter(String text, CardData source) {
        Matcher m = CHOOSE_FORMER_LATTER_PATTERN.matcher(text);
        if (!m.find()) return null;

        String effects      = m.group("effects").trim();
        String effectsLower = effects.toLowerCase(java.util.Locale.ROOT);
        // The two chosen groups are referred to by pronoun. Almost every card says "the former" /
        // "the latter"; 2-093H Raubahn says "the first one" / "the second" for the same two groups.
        // Accepting the alias here only gives the anchored special cases below their turn — the
        // generic split at the end of this method still recognises former/latter alone and returns
        // null for anything else, so no text reaches it that it cannot read.
        boolean formerLatter = effectsLower.contains("the former") && effectsLower.contains("the latter");
        boolean firstSecond  = effectsLower.contains("the first")  && effectsLower.contains("the second");
        if (!formerLatter && !firstSecond) return null;

        // Parse target descriptors (shared for all effect paths below)
        boolean upTo1  = m.group("upTo1") != null;
        int     count1 = Integer.parseInt(m.group("count1"));
        String  desc1  = m.group("desc1").trim();

        boolean upTo2    = m.group("upTo2") != null;
        int     count2   = Integer.parseInt(m.group("count2"));
        String  desc2Raw = m.group("desc2").trim();

        boolean excludeFirstChosen = false;
        String  desc2 = desc2Raw;
        if (desc2Raw.toLowerCase(java.util.Locale.ROOT).startsWith("other ")) {
            excludeFirstChosen = true;
            desc2 = desc2Raw.substring(6).trim();
        }

        TargetDesc td1 = parseTargetDesc(desc1);
        TargetDesc td2 = parseTargetDesc(desc2);

        // Special case: desc2 has a dynamic cost constraint on a BZ Backup that TARGET_DESC_PATTERN
        // cannot represent (e.g. "Backup with a cost equal to or less than that Forward in your BZ").
        // Parse effects normally and supply the cost filter at execution time.
        if (td2 == null && td1 != null && DESC_BZ_BACKUP_COST_RELATIVE.matcher(desc2).matches()) {
            String kLabel = "Choose " + (upTo1 ? "up to " : "") + count1 + " " + desc1
                          + " and " + (upTo2 ? "up to " : "") + count2 + " " + desc2Raw;
            int kLatterIdx = effectsLower.indexOf("the latter");
            int kAndIdx    = effects.lastIndexOf(" and ", kLatterIdx);
            if (kAndIdx >= 0) {
                String kFmrEff = effects.substring(0, kAndIdx).trim()
                        .replaceAll("(?i)\\bthe\\s+former\\b", "it").replaceAll("\\.$", "").trim();
                String kLtrEff = effects.substring(kAndIdx + 5).trim()
                        .replaceAll("(?i)\\bthe\\s+latter\\b", "it").replaceAll("\\.$", "").trim();
                BiConsumer<GameContext, List<ForwardTarget>> kFmrAct =
                        parseFormerLatterGroupAction(kFmrEff);
                BiConsumer<GameContext, List<ForwardTarget>> kLtrAct =
                        parseFormerLatterGroupAction(kLtrEff);
                if (kFmrAct != null && kLtrAct != null) {
                    final TargetDesc kTd1 = td1;
                    final BiConsumer<GameContext, List<ForwardTarget>>
                            fkFmr = kFmrAct, fkLtr = kLtrAct;
                    return ctx -> {
                        ctx.logEntry(kLabel);
                        List<ForwardTarget> ts1 = selectTargets(ctx, count1, upTo1,
                                kTd1.opponentOnly(), kTd1.selfOnly(),
                                kTd1.condition(), kTd1.element(), null, false,
                                kTd1.costVal(), kTd1.costCmp(), -1, null,
                                kTd1.fwd(), kTd1.bkp(), kTd1.mon(),
                                null, null, null, kTd1.excludeName(), false, null, false);
                        if (ts1.isEmpty()) return;
                        ForwardTarget fwdTgt = ts1.get(0);
                        CardData fwdCard = fwdTgt.isP1()
                                ? ctx.p1Forward(fwdTgt.idx()) : ctx.p2Forward(fwdTgt.idx());
                        int formerCost = fwdCard.cost();
                        List<ForwardTarget> ts2 = selectTargets(ctx, count2, upTo2,
                                false, true, null, null, "in your Break Zone", false,
                                formerCost, "less", -1, null,
                                false, true, false,
                                null, null, null, null, false, null, false);
                        fkFmr.accept(ctx, ts1);
                        fkLtr.accept(ctx, ts2);
                    };
                }
            }
            return null;
        }

        if (td1 == null || td2 == null) return null;

        boolean fExcludeFirst = excludeFirstChosen;
        String  fDesc2Static  = td2.excludeName();
        String label = "Choose " + (upTo1 ? "up to " : "") + count1 + " " + desc1
                     + " and " + (upTo2 ? "up to " : "") + count2 + " " + desc2Raw;

        // Special case: "The former gains +N power until end of turn. Then, the former deals
        // damage equal to its power to the latter." — boost, then deal boosted power as damage.
        Matcher btpM = FORMER_BOOST_THEN_POWER_DAMAGE_TO_LATTER.matcher(effects);
        if (btpM.find()) {
            int boost = Integer.parseInt(btpM.group("boost"));
            EnumSet<CardData.Trait> noTraits = EnumSet.noneOf(CardData.Trait.class);
            return ctx -> {
                ctx.logChooseHeader(label);
                String zone1 = td1.fromBreakZone()
                        ? "in " + (td1.opponentBz() ? "your opponent's" : "your") + " Break Zone" : null;
                List<ForwardTarget> ts1 = selectTargets(ctx, count1, upTo1,
                        td1.opponentOnly(), td1.selfOnly(),
                        td1.condition(), td1.element(), zone1, td1.opponentBz(),
                        td1.costVal(), td1.costCmp(), -1, null,
                        td1.fwd(), td1.bkp(), td1.mon(),
                        null, null, null, td1.excludeName(), false, null, false);

                String excludeForTs2a = fExcludeFirst && !ts1.isEmpty()
                        ? getTargetCardName(ctx, ts1.get(0)) : fDesc2Static;
                String zone2 = td2.fromBreakZone()
                        ? "in " + (td2.opponentBz() ? "your opponent's" : "your") + " Break Zone" : null;
                List<ForwardTarget> ts2 = selectTargets(ctx, count2, upTo2,
                        td2.opponentOnly(), td2.selfOnly(),
                        td2.condition(), td2.element(), zone2, td2.opponentBz(),
                        td2.costVal(), td2.costCmp(), -1, null,
                        td2.fwd(), td2.bkp(), td2.mon(),
                        null, null, null, excludeForTs2a, false, null, false);

                ts1.forEach(t -> ctx.boostTarget(t, boost, noTraits));
                if (!ts1.isEmpty() && !ts2.isEmpty()) {
                    int formerPower = ctx.effectiveTargetPower(ts1.get(0));
                    ts2.forEach(t -> ctx.damageTarget(t, formerPower));
                }
            };
        }

        // Special case: "During this turn, the next damage dealt to the former is [received by|dealt to] the latter instead."
        Matcher redirectM = FORMER_LATTER_DAMAGE_REDIRECT.matcher(effects);
        if (redirectM.find()) {
            String redirectSuffix = redirectM.group("suffix").trim();
            Consumer<GameContext> redirectBonus = redirectSuffix.isEmpty() ? null : parse(redirectSuffix, source);
            return ctx -> {
                ctx.logChooseHeader(label);
                String zone1 = td1.fromBreakZone()
                        ? "in " + (td1.opponentBz() ? "your opponent's" : "your") + " Break Zone" : null;
                List<ForwardTarget> ts1 = selectTargets(ctx, count1, upTo1,
                        td1.opponentOnly(), td1.selfOnly(),
                        td1.condition(), td1.element(), zone1, td1.opponentBz(),
                        td1.costVal(), td1.costCmp(), -1, null,
                        td1.fwd(), td1.bkp(), td1.mon(),
                        null, null, null, td1.excludeName(), false, null, false);

                String excludeForTs2r = fExcludeFirst && !ts1.isEmpty()
                        ? getTargetCardName(ctx, ts1.get(0)) : fDesc2Static;
                String zone2 = td2.fromBreakZone()
                        ? "in " + (td2.opponentBz() ? "your opponent's" : "your") + " Break Zone" : null;
                List<ForwardTarget> ts2 = selectTargets(ctx, count2, upTo2,
                        td2.opponentOnly(), td2.selfOnly(),
                        td2.condition(), td2.element(), zone2, td2.opponentBz(),
                        td2.costVal(), td2.costCmp(), -1, null,
                        td2.fwd(), td2.bkp(), td2.mon(),
                        null, null, null, excludeForTs2r, false, null, false);

                if (!ts1.isEmpty() && !ts2.isEmpty())
                    ctx.redirectNextIncomingDamage(ts1.get(0), ts2.get(0));
                if (redirectBonus != null) redirectBonus.accept(ctx);
            };
        }

        // Special case: "Until the end of the turn, the former gains +N power [and Traits]. Deal the latter N damage."
        Matcher fbtldM = FORMER_BOOST_TRAITS_LATTER_DIRECT_DAMAGE.matcher(effects);
        if (fbtldM.matches()) {
            int boost = Integer.parseInt(fbtldM.group("boost"));
            EnumSet<CardData.Trait> boostTraits = parseTraits(fbtldM.group("traits"));
            int damage = Integer.parseInt(fbtldM.group("damage"));
            String fbtldSuffix = fbtldM.group("suffix").trim();
            Consumer<GameContext> fbtldBonus = fbtldSuffix.isEmpty() ? null : parse(fbtldSuffix, source);
            return ctx -> {
                ctx.logChooseHeader(label);
                String zone1 = td1.fromBreakZone()
                        ? "in " + (td1.opponentBz() ? "your opponent's" : "your") + " Break Zone" : null;
                List<ForwardTarget> ts1 = selectTargets(ctx, count1, upTo1,
                        td1.opponentOnly(), td1.selfOnly(),
                        td1.condition(), td1.element(), zone1, td1.opponentBz(),
                        td1.costVal(), td1.costCmp(), -1, null,
                        td1.fwd(), td1.bkp(), td1.mon(),
                        null, null, null, td1.excludeName(), false, null, false);

                String excl2fbtld = fExcludeFirst && !ts1.isEmpty()
                        ? getTargetCardName(ctx, ts1.get(0)) : fDesc2Static;
                String zone2 = td2.fromBreakZone()
                        ? "in " + (td2.opponentBz() ? "your opponent's" : "your") + " Break Zone" : null;
                List<ForwardTarget> ts2 = selectTargets(ctx, count2, upTo2,
                        td2.opponentOnly(), td2.selfOnly(),
                        td2.condition(), td2.element(), zone2, td2.opponentBz(),
                        td2.costVal(), td2.costCmp(), -1, null,
                        td2.fwd(), td2.bkp(), td2.mon(),
                        null, null, null, excl2fbtld, false, null, false);

                ts1.forEach(t -> ctx.boostTarget(t, boost, boostTraits));
                ts2.forEach(t -> ctx.damageTarget(t, damage));
                if (fbtldBonus != null) fbtldBonus.accept(ctx);
            };
        }

        // Special case: "Until the end of the turn, the former loses [traits]. Then, the latter
        // gains all the abilities lost by the previous effect until the end of the turn."
        Matcher fltgM = FORMER_LOSES_TRAITS_LATTER_GAINS.matcher(effects);
        if (fltgM.matches()) {
            EnumSet<CardData.Trait> traitsToLose = parseTraits(fltgM.group("traits"));
            if (!traitsToLose.isEmpty()) {
                return ctx -> {
                    ctx.logChooseHeader(label);
                    String zone1 = td1.fromBreakZone()
                            ? "in " + (td1.opponentBz() ? "your opponent's" : "your") + " Break Zone" : null;
                    List<ForwardTarget> ts1 = selectTargets(ctx, count1, upTo1,
                            td1.opponentOnly(), td1.selfOnly(),
                            td1.condition(), td1.element(), zone1, td1.opponentBz(),
                            td1.costVal(), td1.costCmp(), -1, null,
                            td1.fwd(), td1.bkp(), td1.mon(),
                            null, null, null, td1.excludeName(), false, null, false);

                    String excl2flt = fExcludeFirst && !ts1.isEmpty()
                            ? getTargetCardName(ctx, ts1.get(0)) : fDesc2Static;
                    String zone2 = td2.fromBreakZone()
                            ? "in " + (td2.opponentBz() ? "your opponent's" : "your") + " Break Zone" : null;
                    List<ForwardTarget> ts2 = selectTargets(ctx, count2, upTo2,
                            td2.opponentOnly(), td2.selfOnly(),
                            td2.condition(), td2.element(), zone2, td2.opponentBz(),
                            td2.costVal(), td2.costCmp(), -1, null,
                            td2.fwd(), td2.bkp(), td2.mon(),
                            null, null, null, excl2flt, false, null, false);

                    if (!ts1.isEmpty()) {
                        ForwardTarget former = ts1.get(0);
                        EnumSet<CardData.Trait> actuallyLost = EnumSet.noneOf(CardData.Trait.class);
                        for (CardData.Trait tr : traitsToLose)
                            if (ctx.effectiveTargetHasTrait(former, tr)) actuallyLost.add(tr);
                        ctx.removeTraitsUntilEotFromTarget(former, traitsToLose);
                        if (!ts2.isEmpty() && !actuallyLost.isEmpty())
                            ctx.boostTarget(ts2.get(0), 0, actuallyLost);
                    }
                };
            }
        }

        // Special case: escalating BZ-count conditionals (dull former; ≥N1 dull latter; ≥N2 freeze; ≥N3 discard).
        Matcher bzEscM = FORMER_DULL_LATTER_BZ_NAME_ESCALATE.matcher(effects);
        if (bzEscM.matches()) {
            int n1 = Integer.parseInt(bzEscM.group("n1"));
            String bzCardName = bzEscM.group("cardname").trim();
            int n2 = Integer.parseInt(bzEscM.group("n2"));
            int n3 = Integer.parseInt(bzEscM.group("n3"));
            int discardN = Integer.parseInt(bzEscM.group("discardN"));
            return ctx -> {
                ctx.logChooseHeader(label);
                String zone1 = td1.fromBreakZone()
                        ? "in " + (td1.opponentBz() ? "your opponent's" : "your") + " Break Zone" : null;
                List<ForwardTarget> ts1 = selectTargets(ctx, count1, upTo1,
                        td1.opponentOnly(), td1.selfOnly(),
                        td1.condition(), td1.element(), zone1, td1.opponentBz(),
                        td1.costVal(), td1.costCmp(), -1, null,
                        td1.fwd(), td1.bkp(), td1.mon(),
                        null, null, null, td1.excludeName(), false, null, false);

                String excl2bz = fExcludeFirst && !ts1.isEmpty()
                        ? getTargetCardName(ctx, ts1.get(0)) : fDesc2Static;
                String zone2 = td2.fromBreakZone()
                        ? "in " + (td2.opponentBz() ? "your opponent's" : "your") + " Break Zone" : null;
                List<ForwardTarget> ts2 = selectTargets(ctx, count2, upTo2,
                        td2.opponentOnly(), td2.selfOnly(),
                        td2.condition(), td2.element(), zone2, td2.opponentBz(),
                        td2.costVal(), td2.costCmp(), -1, null,
                        td2.fwd(), td2.bkp(), td2.mon(),
                        null, null, null, excl2bz, false, null, false);

                ts1.forEach(ctx::dullTarget);
                int bzCount = ctx.countSelfBreakZoneCards(bzCardName, null);
                if (bzCount >= n1) ts2.forEach(ctx::dullTarget);
                if (bzCount >= n2) {
                    ts1.forEach(ctx::freezeTarget);
                    ts2.forEach(ctx::freezeTarget);
                }
                if (bzCount >= n3) ctx.forceOpponentDiscard(discardN);
            };
        }

        // Special case: "+N power and cannot-dull-by-opp; conditional damage to latter = highest own Forward power."
        Matcher bdicM = FORMER_BOOST_DULL_IMMUNITY_COND_DAMAGE_LATTER.matcher(effects);
        if (bdicM.matches()) {
            int boost = Integer.parseInt(bdicM.group("boost"));
            int dmgThresh = Integer.parseInt(bdicM.group("dmgthresh"));
            EnumSet<CardData.Trait> dullImmunity = EnumSet.of(CardData.Trait.CANNOT_BE_DULLED_BY_OPP);
            return ctx -> {
                ctx.logChooseHeader(label);
                String zone1 = td1.fromBreakZone()
                        ? "in " + (td1.opponentBz() ? "your opponent's" : "your") + " Break Zone" : null;
                List<ForwardTarget> ts1 = selectTargets(ctx, count1, upTo1,
                        td1.opponentOnly(), td1.selfOnly(),
                        td1.condition(), td1.element(), zone1, td1.opponentBz(),
                        td1.costVal(), td1.costCmp(), -1, null,
                        td1.fwd(), td1.bkp(), td1.mon(),
                        null, null, null, td1.excludeName(), false, null, false);

                String excl2di = fExcludeFirst && !ts1.isEmpty()
                        ? getTargetCardName(ctx, ts1.get(0)) : fDesc2Static;
                String zone2 = td2.fromBreakZone()
                        ? "in " + (td2.opponentBz() ? "your opponent's" : "your") + " Break Zone" : null;
                List<ForwardTarget> ts2 = selectTargets(ctx, count2, upTo2,
                        td2.opponentOnly(), td2.selfOnly(),
                        td2.condition(), td2.element(), zone2, td2.opponentBz(),
                        td2.costVal(), td2.costCmp(), -1, null,
                        td2.fwd(), td2.bkp(), td2.mon(),
                        null, null, null, excl2di, false, null, false);

                ts1.forEach(t -> ctx.boostTarget(t, boost, dullImmunity));
                if (ctx.selfDamageCount() >= dmgThresh && !ts2.isEmpty()) {
                    int highestPower = ctx.selfHighestForwardPower();
                    ctx.damageTarget(ts2.get(0), highestPower);
                }
            };
        }

        // Special case: "Break the former. If [card] enters the field due to Warp, also break the latter."
        if (FORMER_BREAK_COND_WARP_LATTER_BREAK.matcher(effects).matches()) {
            return ctx -> {
                ctx.logChooseHeader(label);
                String zone1 = td1.fromBreakZone()
                        ? "in " + (td1.opponentBz() ? "your opponent's" : "your") + " Break Zone" : null;
                List<ForwardTarget> ts1 = selectTargets(ctx, count1, upTo1,
                        td1.opponentOnly(), td1.selfOnly(),
                        td1.condition(), td1.element(), zone1, td1.opponentBz(),
                        td1.costVal(), td1.costCmp(), -1, null,
                        td1.fwd(), td1.bkp(), td1.mon(),
                        null, null, null, td1.excludeName(), false, null, false);

                String excl2bw = fExcludeFirst && !ts1.isEmpty()
                        ? getTargetCardName(ctx, ts1.get(0)) : fDesc2Static;
                String zone2 = td2.fromBreakZone()
                        ? "in " + (td2.opponentBz() ? "your opponent's" : "your") + " Break Zone" : null;
                List<ForwardTarget> ts2 = selectTargets(ctx, count2, upTo2,
                        td2.opponentOnly(), td2.selfOnly(),
                        td2.condition(), td2.element(), zone2, td2.opponentBz(),
                        td2.costVal(), td2.costCmp(), -1, null,
                        td2.fwd(), td2.bkp(), td2.mon(),
                        null, null, null, excl2bw, false, null, false);

                sortedByIdxDesc(ts1, true) .forEach(ctx::breakTarget);
                sortedByIdxDesc(ts1, false).forEach(ctx::breakTarget);
                if (ctx.sourceEnteredViaWarp()) {
                    sortedByIdxDesc(ts2, true) .forEach(ctx::breakTarget);
                    sortedByIdxDesc(ts2, false).forEach(ctx::breakTarget);
                }
            };
        }

        // Special case: "Deal the former N damage. If you control M or more Backups, also deal the latter N damage."
        Matcher bkpDmgM = FORMER_DAMAGE_COND_BACKUP_COUNT_LATTER_DAMAGE.matcher(effects);
        if (bkpDmgM.matches()) {
            int dmg1 = Integer.parseInt(bkpDmgM.group("dmg1"));
            int bkpThresh = Integer.parseInt(bkpDmgM.group("n"));
            int dmg2 = Integer.parseInt(bkpDmgM.group("dmg2"));
            return ctx -> {
                ctx.logChooseHeader(label);
                String zone1 = td1.fromBreakZone()
                        ? "in " + (td1.opponentBz() ? "your opponent's" : "your") + " Break Zone" : null;
                List<ForwardTarget> ts1 = selectTargets(ctx, count1, upTo1,
                        td1.opponentOnly(), td1.selfOnly(),
                        td1.condition(), td1.element(), zone1, td1.opponentBz(),
                        td1.costVal(), td1.costCmp(), -1, null,
                        td1.fwd(), td1.bkp(), td1.mon(),
                        null, null, null, td1.excludeName(), false, null, false);

                String excl2bd = fExcludeFirst && !ts1.isEmpty()
                        ? getTargetCardName(ctx, ts1.get(0)) : fDesc2Static;
                String zone2 = td2.fromBreakZone()
                        ? "in " + (td2.opponentBz() ? "your opponent's" : "your") + " Break Zone" : null;
                List<ForwardTarget> ts2 = selectTargets(ctx, count2, upTo2,
                        td2.opponentOnly(), td2.selfOnly(),
                        td2.condition(), td2.element(), zone2, td2.opponentBz(),
                        td2.costVal(), td2.costCmp(), -1, null,
                        td2.fwd(), td2.bkp(), td2.mon(),
                        null, null, null, excl2bd, false, null, false);

                ts1.forEach(t -> ctx.damageTarget(t, dmg1));
                if (ctx.countSelfFieldCards(false, true, false, null, null) >= bkpThresh)
                    ts2.forEach(t -> ctx.damageTarget(t, dmg2));
            };
        }

        // Special case: "The former deals damage equal to its power to the latter."
        if (FORMER_DEALS_POWER_DAMAGE_TO_LATTER.matcher(effects).matches()) {
            return ctx -> {
                ctx.logChooseHeader(label);
                String zone1 = td1.fromBreakZone()
                        ? "in " + (td1.opponentBz() ? "your opponent's" : "your") + " Break Zone" : null;
                List<ForwardTarget> ts1 = selectTargets(ctx, count1, upTo1,
                        td1.opponentOnly(), td1.selfOnly(),
                        td1.condition(), td1.element(), zone1, td1.opponentBz(),
                        td1.costVal(), td1.costCmp(), -1, null,
                        td1.fwd(), td1.bkp(), td1.mon(),
                        null, null, null, td1.excludeName(), false, null, false);

                String excl2fp = fExcludeFirst && !ts1.isEmpty()
                        ? getTargetCardName(ctx, ts1.get(0)) : fDesc2Static;
                String zone2 = td2.fromBreakZone()
                        ? "in " + (td2.opponentBz() ? "your opponent's" : "your") + " Break Zone" : null;
                List<ForwardTarget> ts2 = selectTargets(ctx, count2, upTo2,
                        td2.opponentOnly(), td2.selfOnly(),
                        td2.condition(), td2.element(), zone2, td2.opponentBz(),
                        td2.costVal(), td2.costCmp(), -1, null,
                        td2.fwd(), td2.bkp(), td2.mon(),
                        null, null, null, excl2fp, false, null, false);

                if (!ts1.isEmpty() && !ts2.isEmpty()) {
                    int formerPower = ctx.effectiveTargetPower(ts1.get(0));
                    ctx.damageTarget(ts2.get(0), formerPower);
                }
            };
        }

        // Generic split: prefer comma-after-former when it precedes the " and " split point,
        // since some cards use ", Action the latter" instead of "and Action the latter".
        // (e.g. "Break the former, dull and Freeze the latter.")
        int latterIdx = effectsLower.indexOf("the latter");
        int andIdx    = effects.lastIndexOf(" and ", latterIdx);
        int formerIdx = effectsLower.indexOf("the former");

        int splitIdx = andIdx, splitLen = 5;
        if (formerIdx >= 0) {
            // Look for ", " after the end of the "the former" phrase
            int commaAfterFormer = effects.indexOf(", ", formerIdx + 10);
            if (commaAfterFormer >= 0 && commaAfterFormer < latterIdx
                    && (andIdx < 0 || commaAfterFormer < andIdx)) {
                // Guard: don't use comma split if the latter portion starts with "and "
                // (that's an Oxford comma before the real "and", not a true split point)
                String afterComma = effects.substring(commaAfterFormer + 2).trim().toLowerCase(java.util.Locale.ROOT);
                if (!afterComma.startsWith("and ")) {
                    splitIdx = commaAfterFormer;
                    splitLen = 2;
                }
            }
        }
        if (splitIdx < 0) return null;

        String formerRaw = effects.substring(0, splitIdx).trim();
        String latterRaw = effects.substring(splitIdx + splitLen).trim();

        // Substitute pronouns and strip any trailing period
        String formerEff = formerRaw.replaceAll("(?i)\\bthe\\s+former\\b", "it").replaceAll("\\.$", "").trim();
        String latterEff = latterRaw.replaceAll("(?i)\\bthe\\s+latter\\b", "it").replaceAll("\\.$", "").trim();

        BiConsumer<GameContext, List<ForwardTarget>> formerAction =
                parseFormerLatterGroupAction(formerEff);
        BiConsumer<GameContext, List<ForwardTarget>> latterAction =
                parseFormerLatterGroupAction(latterEff);
        if (formerAction == null || latterAction == null) return null;

        BiConsumer<GameContext, List<ForwardTarget>> fFormerAction = formerAction;
        BiConsumer<GameContext, List<ForwardTarget>> fLatterAction = latterAction;

        return ctx -> {
            ctx.logChooseHeader(label);
            String zone1 = td1.fromBreakZone()
                    ? "in " + (td1.opponentBz() ? "your opponent's" : "your") + " Break Zone" : null;
            List<ForwardTarget> ts1 = selectTargets(ctx, count1, upTo1,
                    td1.opponentOnly(), td1.selfOnly(),
                    td1.condition(), td1.element(), zone1, td1.opponentBz(),
                    td1.costVal(), td1.costCmp(), -1, null,
                    td1.fwd(), td1.bkp(), td1.mon(),
                    null, null, null, td1.excludeName(), false, null, false);

            String excludeForTs2 = fExcludeFirst && !ts1.isEmpty()
                    ? getTargetCardName(ctx, ts1.get(0))
                    : fDesc2Static;

            String zone2 = td2.fromBreakZone()
                    ? "in " + (td2.opponentBz() ? "your opponent's" : "your") + " Break Zone" : null;
            List<ForwardTarget> ts2 = selectTargets(ctx, count2, upTo2,
                    td2.opponentOnly(), td2.selfOnly(),
                    td2.condition(), td2.element(), zone2, td2.opponentBz(),
                    td2.costVal(), td2.costCmp(), -1, null,
                    td2.fwd(), td2.bkp(), td2.mon(),
                    null, null, null, excludeForTs2, false, null, false);

            fFormerAction.accept(ctx, ts1);
            fLatterAction.accept(ctx, ts2);
        };
    }

    // =========================================================================================
    // Redirects, mixed types and joint actions
    // =========================================================================================
    /**
     * Parses "Choose 1 Forward you control other than [CardName]. During this turn, the next
     * damage dealt to it is dealt to [CardName] instead." — one-shot damage redirect where the
     * player picks a Forward to shield and a named card on the field absorbs the damage.
     */
    static Consumer<GameContext> tryParseChooseForwardRedirectToNamed(String text) {
        Matcher m = CHOOSE_FORWARD_REDIRECT_TO_NAMED.matcher(text);
        if (!m.find()) return null;

        String shieldName   = m.group("shield").trim();
        String redirectName = m.group("redirect").trim();
        if (!shieldName.equalsIgnoreCase(redirectName)) return null;

        String logMsg = "Choose 1 Forward you control other than " + shieldName
                + " → redirect next incoming damage to " + shieldName;

        return ctx -> {
            ctx.logEntry("Effect: " + logMsg);
            List<ForwardTarget> targets = selectTargets(ctx, 1, false,
                    false, true,
                    null, null, null, false,
                    -1, null, -1, null,
                    true, false, false,
                    null, null, null, shieldName,
                    false, null, false);
            if (targets.isEmpty()) return;

            List<ForwardTarget> redirectTargets = selectTargets(ctx, 1, false,
                    false, true,
                    null, null, null, false,
                    -1, null, -1, null,
                    true, false, false,
                    null, redirectName, null, null,
                    false, null, false);
            if (redirectTargets.isEmpty()) return;

            ctx.redirectNextIncomingDamage(targets.get(0), redirectTargets.get(0));
        };
    }
    static Consumer<GameContext> tryParseChooseTwoMixedTypes(String text, CardData source) {
        Matcher m = CHOOSE_TWO_MIXED_TYPES_PATTERN.matcher(text);
        if (!m.find()) return null;

        int count1 = Integer.parseInt(m.group("count1"));
        String tgt1 = m.group("type1").toLowerCase();
        boolean fwd1 = tgt1.contains("forward") || tgt1.contains("character");
        boolean bak1 = tgt1.contains("backup")  || tgt1.contains("character");
        boolean mon1 = tgt1.contains("monster") || tgt1.contains("character");

        int count2 = Integer.parseInt(m.group("count2"));
        String tgt2 = m.group("type2").toLowerCase();
        boolean fwd2 = tgt2.contains("forward") || tgt2.contains("character");
        boolean bak2 = tgt2.contains("backup")  || tgt2.contains("character");
        boolean mon2 = tgt2.contains("monster") || tgt2.contains("character");

        String control = m.group("control");
        boolean opponentOnly = control != null && !control.toLowerCase().contains("you control");
        boolean selfOnly     = control != null &&  control.toLowerCase().contains("you control");

        String followup = m.group("followup").trim();
        BiConsumer<GameContext, List<ForwardTarget>> action = parseTargetAction(followup, 0);
        if (action == null) return null;

        String label = "Choose " + count1 + " " + m.group("type1") + " and " + count2 + " " + m.group("type2");
        return ctx -> {
            ctx.logChooseHeader(label);
            List<ForwardTarget> ts1 = selectTargets(ctx, count1, false, opponentOnly, selfOnly,
                    null, null, null, false, -1, null, -1, null,
                    fwd1, bak1, mon1, null, null, null, null, false, null, false);
            List<ForwardTarget> ts2 = selectTargets(ctx, count2, false, opponentOnly, selfOnly,
                    null, null, null, false, -1, null, -1, null,
                    fwd2, bak2, mon2, null, null, null, null, false, null, false);
            List<ForwardTarget> all = new ArrayList<>(ts1);
            all.addAll(ts2);
            action.accept(ctx, all);
        };
    }
    /**
     * Matches "Choose [up to] N [desc1] and [up to] M [desc2]. [effect]" where the effect acts on
     * both chosen groups at once — "Break them.", "Dull them and Freeze them.", "Return them to
     * their owners' hands." Each descriptor carries its own qualifiers (cost band, control side,
     * Break Zone), which is what separates this from {@link #tryParseChooseTwoMixedTypes}: that
     * parser reads a bare card type on each side and nothing else, so it cannot see the cost bands
     * in 19-114L Cloud's "up to 1 Forward of cost 4 or less and up to 1 Forward of cost 5 or more".
     *
     * <p>Shares {@link ActionResolverPatterns#CHOOSE_FORMER_LATTER_PATTERN} with
     * {@link #tryParseChooseFormerLatter} — the same two-clause selection, a different effect half.
     * That parser runs far earlier in {@code parse()} and returns null unless the effect names the
     * two groups separately ("the former" / "the latter"), so the two never compete for a text.
     *
     * <p>Deliberately placed last, immediately ahead of {@link #tryParseChooseCharacter}, rather
     * than beside the other two-clause parsers: the pattern is broad enough to claim texts that
     * {@code tryParseChooseTwoBzFwdPlayIfControl} and the other specific two-clause parsers own
     * (22-113L Mont Leonis reads as two descriptors plus a "play them onto the field" effect), so
     * every one of them gets first refusal. It must still precede {@code tryParseChooseCharacter},
     * which matches the first clause alone and applies the effect to it — that is what left Cloud
     * breaking a Forward of cost 4 or less and never one of cost 5 or more.
     */
    static Consumer<GameContext> tryParseChooseTwoJointAction(String text, CardData source) {
        Matcher m = CHOOSE_FORMER_LATTER_PATTERN.matcher(text);
        if (!m.find()) return null;

        boolean upTo1  = m.group("upTo1") != null;
        int     count1 = Integer.parseInt(m.group("count1"));
        String  desc1  = m.group("desc1").trim();

        boolean upTo2  = m.group("upTo2") != null;
        int     count2 = Integer.parseInt(m.group("count2"));
        String  desc2  = m.group("desc2").trim();

        TargetDesc td1 = parseTargetDesc(desc1);
        TargetDesc td2 = parseTargetDesc(desc2);
        if (td1 == null || td2 == null) return null;

        BiConsumer<GameContext, List<ForwardTarget>> action =
                parseTargetAction(m.group("effects").trim(), 0);
        if (action == null) return null;

        String label = "Choose " + (upTo1 ? "up to " : "") + count1 + " " + desc1
                     + " and " + (upTo2 ? "up to " : "") + count2 + " " + desc2;
        return ctx -> {
            ctx.logChooseHeader(label);
            List<ForwardTarget> all = new ArrayList<>(selectByTargetDesc(ctx, td1, count1, upTo1));
            all.addAll(selectByTargetDesc(ctx, td2, count2, upTo2));
            action.accept(ctx, all);
        };
    }
    /** Runs one {@link TargetDesc} through {@link #selectTargets}, rebuilding its zone string. */
    private static List<ForwardTarget> selectByTargetDesc(
            GameContext ctx, TargetDesc td, int count, boolean upTo) {
        String zone = td.fromBreakZone()
                ? "in " + (td.opponentBz() ? "your opponent's" : "your") + " Break Zone" : null;
        return selectTargets(ctx, count, upTo, td.opponentOnly(), td.selfOnly(),
                td.condition(), td.element(), zone, td.opponentBz(),
                td.costVal(), td.costCmp(), -1, null,
                td.fwd(), td.bkp(), td.mon(),
                null, null, null, td.excludeName(), false, null, false);
    }
    static Consumer<GameContext> tryParseChooseThreeMixedTypes(String text, CardData source) {
        Matcher m = CHOOSE_THREE_MIXED_TYPES_PATTERN.matcher(text);
        if (!m.find()) return null;

        int count1 = Integer.parseInt(m.group("count1"));
        String tgt1 = m.group("type1").toLowerCase();
        boolean fwd1 = tgt1.contains("forward") || tgt1.contains("character");
        boolean bak1 = tgt1.contains("backup")  || tgt1.contains("character");
        boolean mon1 = tgt1.contains("monster") || tgt1.contains("character");
        boolean opp1 = m.group("opp1") != null;

        int count2 = Integer.parseInt(m.group("count2"));
        String tgt2 = m.group("type2").toLowerCase();
        boolean fwd2 = tgt2.contains("forward") || tgt2.contains("character");
        boolean bak2 = tgt2.contains("backup")  || tgt2.contains("character");
        boolean mon2 = tgt2.contains("monster") || tgt2.contains("character");
        boolean opp2 = m.group("opp2") != null;

        int count3 = Integer.parseInt(m.group("count3"));
        String tgt3 = m.group("type3").toLowerCase();
        boolean fwd3 = tgt3.contains("forward") || tgt3.contains("character");
        boolean bak3 = tgt3.contains("backup")  || tgt3.contains("character");
        boolean mon3 = tgt3.contains("monster") || tgt3.contains("character");
        boolean opp3 = m.group("opp3") != null;

        String followup = m.group("followup").trim();
        String label = "Choose up to " + count1 + " " + m.group("type1") + (opp1 ? " (opponent)" : "")
                + ", up to " + count2 + " " + m.group("type2") + (opp2 ? " (opponent)" : "")
                + ", and up to " + count3 + " " + m.group("type3") + (opp3 ? " (opponent)" : "");

        if (FOLLOWUP_REMOVE_FROM_GAME.matcher(followup).find()) {
            return ctx -> {
                ctx.logChooseHeader(label + " — Remove From Game");
                List<ForwardTarget> ts1 = selectTargets(ctx, count1, true, opp1, false,
                        null, null, null, false, -1, null, -1, null,
                        fwd1, bak1, mon1, null, null, null, null, false, null, false);
                List<ForwardTarget> ts2 = selectTargets(ctx, count2, true, opp2, false,
                        null, null, null, false, -1, null, -1, null,
                        fwd2, bak2, mon2, null, null, null, null, false, null, false);
                List<ForwardTarget> ts3 = selectTargets(ctx, count3, true, opp3, false,
                        null, null, null, false, -1, null, -1, null,
                        fwd3, bak3, mon3, null, null, null, null, false, null, false);
                List<ForwardTarget> all = new ArrayList<>(ts1);
                all.addAll(ts2);
                all.addAll(ts3);
                sortedByIdxDesc(all, true) .forEach(t -> ctx.removeTargetFromGame(t));
                sortedByIdxDesc(all, false).forEach(t -> ctx.removeTargetFromGame(t));
            };
        }

        BiConsumer<GameContext, List<ForwardTarget>> action = parseTargetAction(followup, 0);
        if (action == null) return null;

        return ctx -> {
            ctx.logChooseHeader(label);
            List<ForwardTarget> ts1 = selectTargets(ctx, count1, true, opp1, false,
                    null, null, null, false, -1, null, -1, null,
                    fwd1, bak1, mon1, null, null, null, null, false, null, false);
            List<ForwardTarget> ts2 = selectTargets(ctx, count2, true, opp2, false,
                    null, null, null, false, -1, null, -1, null,
                    fwd2, bak2, mon2, null, null, null, null, false, null, false);
            List<ForwardTarget> ts3 = selectTargets(ctx, count3, true, opp3, false,
                    null, null, null, false, -1, null, -1, null,
                    fwd3, bak3, mon3, null, null, null, null, false, null, false);
            List<ForwardTarget> all = new ArrayList<>(ts1);
            all.addAll(ts2);
            all.addAll(ts3);
            action.accept(ctx, all);
        };
    }

    // =========================================================================================
    // Gated boosts and Break-Zone / RFG searches
    // =========================================================================================
    /**
     * Strips a trailing "When it is put from the field into the Break Zone this turn, draw N"
     * delayed trigger, parses the rest as an ordinary choose-and-act effect, and arms the mark so
     * {@link #selectTargets} applies it to the chosen targets. Arming before the inner effect runs
     * is what makes the trigger survive a lethal primary: the mark is on the Forward before the
     * damage that breaks it.
     */
    /**
     * Routes the "Choose … . You may search for 1 X and remove it from the game. If you do so, … .
     * If not, … ." shape to {@link #tryParseChooseCharacter} early, without duplicating any of it.
     *
     * <p>Exists purely for call order. The followup branch that handles this family lives inside
     * the choose parser, which {@code parse()} reaches long after
     * {@code tryParseWhenYouDoSoSequence} — and that parser splits on "If you do so" and resolves
     * the halves independently, so 29-116H Madeen's "remove the chosen Forward from the game" was
     * read as a bare remove-by-name and the optional search was never offered. 29-117H Ark escaped
     * only because neither of its branches parses standalone.
     */
    /**
     * Routes "Choose … . It gains +N power … . If you control X, it gains +M power … instead."
     * to {@link #tryParseChooseCharacter} early, without duplicating any of it — 4-090R Biggs.
     *
     * <p>Exists purely for call order, like {@link #tryParseChooseMaySearchRfgThenElse}.
     * {@code tryParseControlGatedInsteadUpgrade} sits far earlier in {@code parse()} and splits
     * the text into a base and an alternative it resolves independently; the alternative here is
     * "it gains +2000 power", whose "it" is the Forward the base half chose, so on its own it has
     * nothing to attach to and the upgrade quietly did nothing.
     */
    static Consumer<GameContext> tryParseChooseGatedBoostInstead(String text, CardData source, int xValue) {
        Matcher m = CHOOSE_CHARACTER_PATTERN.matcher(escapePeriodInName(text, source));
        if (!m.find()) return null;
        String followup = restorePeriodInName(m.group("followup").trim(), source);
        if (!FOLLOWUP_POWER_BOOST_CONTROL_GATED_INSTEAD.matcher(followup).matches()) return null;
        return tryParseChooseCharacter(text, source, xValue);
    }
    static Consumer<GameContext> tryParseChooseMaySearchRfgThenElse(String text, CardData source, int xValue) {
        Matcher m = CHOOSE_CHARACTER_PATTERN.matcher(escapePeriodInName(text, source));
        if (!m.find()) return null;
        String followup = restorePeriodInName(m.group("followup").trim(), source);
        if (!FOLLOWUP_MAY_SEARCH_RFG_THEN_ELSE.matcher(followup).matches()) return null;
        return tryParseChooseCharacter(text, source, xValue);
    }

    // =========================================================================================
    // Choose Character: entry point and shared helpers
    // =========================================================================================
    static Consumer<GameContext> tryParseChooseCharacter(String text, CardData source, int xValue) {
        Matcher bzDrawM = CHOOSE_THEN_WHEN_PUT_TO_BZ_DRAW.matcher(text.trim());
        if (bzDrawM.matches()) {
            int drawCount = Integer.parseInt(bzDrawM.group("count"));
            Consumer<GameContext> inner = tryParseChooseCharacterInner(bzDrawM.group("head").trim(), source, xValue);
            if (inner == null) return null;
            return ctx -> {
                ctx.armDrawOnFieldToBzMark(drawCount);
                inner.accept(ctx);
                ctx.consumeDrawOnFieldToBzMark();   // clear if the effect never selected a target
            };
        }
        return tryParseChooseCharacterInner(text, source, xValue);
    }
    /**
     * Builds Porom 15-119L's second sentence: "If N or more [X] Counters are placed on [Self], its
     * power also becomes P until the end of the turn."
     *
     * <p>Lives here rather than in the general chain because "its" is the Forward the first
     * sentence chose — read back through {@code lastChosenTargets()} — while the counters counted
     * are the ability source's own. Returns null when the counters are named on any card but the
     * source, so such a text falls through to the general chain instead of being read as if it
     * said "Self".
     *
     * <p>The gate is evaluated at resolution, not at parse: the counters accumulate over turns.
     */
    static Consumer<GameContext> secondaryCounterGatedPowerBecomes(String secondaryText, CardData source) {
        if (source == null) return null;
        Matcher m = SECONDARY_IF_SOURCE_COUNTERS_POWER_BECOMES.matcher(secondaryText.trim());
        if (!m.matches()) return null;
        if (!m.group("name").trim().equalsIgnoreCase(source.name())) return null;
        final int    required    = Integer.parseInt(m.group("count"));
        final String counterName = m.group("countername").trim();
        final int    power       = Integer.parseInt(m.group("power"));
        return ctx -> {
            int held = ctx.getCounters(source, counterName);
            if (held < required) {
                ctx.logEntry(source.name() + " has " + held + " " + counterName + " Counter(s), needs "
                        + required + " — power unchanged");
                return;
            }
            ctx.logEntry(source.name() + " has " + held + " " + counterName
                    + " Counter(s) — the chosen Forward's power becomes " + power + " until end of turn");
            List<ForwardTarget> chosen = ctx.lastChosenTargets();
            // Descending order, as everywhere else that sets a power: dropping to the new value can
            // break a Forward, which shifts the indices of every target above it in the same zone.
            sortedByIdxDesc(chosen, true) .forEach(t -> ctx.setTargetBasePower(t, power));
            sortedByIdxDesc(chosen, false).forEach(t -> ctx.setTargetBasePower(t, power));
        };
    }

    /**
     * True when {@code followupText} is Tulien 21-072H's grant of both compulsions — the outer
     * two-quotation shape, and each quotation a clause this engine can enforce on whoever receives
     * it.
     *
     * <p>Shared by the choose chain and the followup-naming chain so the two cannot disagree: the
     * outer pattern alone matches any pair of quotations, and reporting a name for a pair the
     * parser then declines would hide the gap rather than show it.
     *
     * <p>Restrictions are stripped first. Tulien prints "You can only use this ability once per
     * turn." right after the closing quotation, with no sentence break outside the quotes for the
     * choose chain's split to find — so the restriction arrives as part of the primary followup,
     * where an anchored pattern would trip over it.
     */
    static boolean isMustAttackAndMustBlockGrant(String followupText) {
        String core = stripRestrictionSentences(followupText);
        if (core.isEmpty()) core = followupText;
        Matcher m = FOLLOWUP_GAINS_TWO_QUOTED_EOT.matcher(core.trim());
        if (!m.matches()) return false;
        Matcher mustAtk = GRANTED_MUST_ATTACK_ONCE_PER_TURN.matcher(m.group("first").trim());
        Matcher mustBlk = GRANTED_MUST_BLOCK_IF_POSSIBLE.matcher(m.group("second").trim());
        if (!mustAtk.matches() || !mustBlk.matches()) return false;
        return grantedClauseNamesItsCarrier(mustAtk.group("subj"))
                && grantedClauseNamesItsCarrier(
                        mustBlk.group("subj") != null ? mustBlk.group("subj") : mustBlk.group("subj2"));
    }

    /**
     * Where {@code card} currently sits on {@code isP1}'s Forward row, or -1 when it is no longer
     * there.
     *
     * <p>By identity, never by {@code equals}: {@link CardData} is a record, so two copies of one
     * card compare equal and a sweep would keep finding the first of them.
     */
    private static int forwardIndexByIdentity(GameContext ctx, boolean isP1, CardData card) {
        int n = isP1 ? ctx.p1ForwardCount() : ctx.p2ForwardCount();
        for (int i = 0; i < n; i++)
            if ((isP1 ? ctx.p1Forward(i) : ctx.p2Forward(i)) == card) return i;
        return -1;
    }

    /**
     * True when a granted clause's subject is the card receiving it ("This Forward", "It", …)
     * rather than some other card named outright. A quoted grant that names a third party is a
     * different effect, and applying it to the grantee would act on the wrong card.
     */
    private static boolean grantedClauseNamesItsCarrier(String subject) {
        return subject != null && GRANTED_CLAUSE_SELF_SUBJECT.matcher(subject.trim()).matches();
    }

    /**
     * Plays the Break Zone card at {@code t} onto the field when its cost is exactly {@code cost}
     * — the payoff of "If its cost is X, play it onto the field." A card that misses says so in
     * the log rather than vanishing quietly, since the player has already spent the X.
     */
    private static void playFromBzIfCostIs(GameContext ctx, ForwardTarget t, int cost) {
        CardData card = t.isP1() ? ctx.p1BreakZoneCard(t.idx()) : ctx.p2BreakZoneCard(t.idx());
        if (card == null) return;
        if (card.cost() == cost) ctx.playTargetOntoField(t);
        else ctx.logEntry(card.name() + " costs " + card.cost() + ", not " + cost + " — not played");
    }


    // =========================================================================================
    // tryParseChooseCharacterInner: the followup chain
    // =========================================================================================
    static Consumer<GameContext> tryParseChooseCharacterInner(String text, CardData source, int xValue) {
        text = ELEM_TYPE_OR_ELEM_TYPE.matcher(text).replaceAll("$1 or $3 $2");
        text = escapePeriodInName(text, source);
        Matcher m = CHOOSE_CHARACTER_PATTERN.matcher(text);
        if (!m.find()) return null;

        boolean any          = m.group("anycount") != null;
        boolean upTo         = m.group("upto") != null;
        int     maxCount     = any ? Integer.MAX_VALUE : Integer.parseInt(m.group("count"));
        String  rawElement   = m.group("element");
        String  element      = rawElement != null && rawElement.contains(" or ")
                ? rawElement.replaceAll("(?i)\\s+or\\s+", "|") : rawElement;
        // Resolve condition: "blocking [Name]"/"blocking a Job [Job]" overrides the standard condition.
        // Post-target qualifiers ("that entered the field this turn") are normalized to the same string.
        String  rawCondition  = m.group("condition");
        String  postCondition = m.group("postcondition");
        String  blockingName  = m.group("blockingname");
        String  blockingJob   = m.group("blockingjob");
        String  traitGroup    = m.group("trait");
        // "put in your Break Zone from the field during this turn" states the zone and a condition
        // in one phrase, so it fills both slots — the zone below, and the condition here.
        String  bzFieldZone   = m.group("bzfieldzone");
        // "that is also a Forward" rides the condition slot, like the Break-Zone-from-field
        // filter below: both are pools the card kind alone cannot name.
        if (m.group("alsoforward") != null && rawCondition == null)
            rawCondition = CardFilters.MONSTER_ALSO_FORWARD;
        // "with 《LB》" (26-087R Odin) rides it for the same reason — a Limit Break card is a
        // printing, not a card kind or a state. Any other keyword in that slot is declined rather
        // than ignored: an unread filter would widen the choice to every Forward on the table,
        // which is the failure the without-《…》 arm beside it was written to avoid.
        String rawWithKw = m.group("withkw");
        if (rawWithKw != null) {
            if (!"LB".equalsIgnoreCase(rawWithKw.trim()) || rawCondition != null) return null;
            rawCondition = CardFilters.LIMIT_BREAK_CONDITION;
        }
        String  condition     = bzFieldZone   != null ? CardFilters.PUT_TO_BZ_FROM_FIELD_THIS_TURN
                              : blockingName  != null ? "blocking:"     + blockingName.trim()
                              : blockingJob   != null ? "blocking-job:" + blockingJob.trim()
                              : postCondition != null ? "entered the field this turn"
                              : traitGroup    != null ? "trait:"        + traitGroup.trim().replace(" ", "_").toUpperCase(java.util.Locale.ROOT)
                              : rawCondition;
        String  targets      = m.group("targets");
        String  tgtLower = targets.toLowerCase();
        String  jobFilter;
        String  cardNameFilter;
        boolean inclForwards;
        boolean inclBackups;
        boolean inclMonsters;

        if (tgtLower.startsWith("[job ")) {
            Matcher jm = JOB_BRACKET_PATTERN.matcher(targets);
            jobFilter      = jm.find() ? jm.group(1).trim() : null;
            cardNameFilter = null;
            inclForwards   = true;
            inclBackups    = false;
            inclMonsters   = false;
        } else if (tgtLower.startsWith("[card name ")) {
            Matcher nm = CARD_NAME_BRACKET_PATTERN.matcher(targets);
            cardNameFilter = nm.find() ? nm.group(1).trim() : null;
            jobFilter      = null;
            inclForwards   = true;
            inclBackups    = true;
            inclMonsters   = true;
        } else if (tgtLower.startsWith("card name ") && tgtLower.contains(" or job ")) {
            // "Card Name X Forward or Job Y Forward" — mixed card-name + job filter, both typed
            int orJobIdx = tgtLower.indexOf(" or job ");
            String cardNamePart = targets.substring("Card Name ".length(), orJobIdx).trim();
            cardNameFilter = cardNamePart.replaceAll("(?i)\\s+(?:Forwards?|Backups?|Monsters?|Characters?)$", "").trim();
            String jobPart = targets.substring(orJobIdx + " or job ".length()).trim();
            jobFilter    = jobPart.replaceAll("(?i)\\s+(?:Forwards?|Backups?|Monsters?|Characters?)$", "").trim();
            inclForwards = tgtLower.contains("forward");
            inclBackups  = tgtLower.contains("backup");
            inclMonsters = tgtLower.contains("monster");
        } else if (tgtLower.startsWith("card name ")) {
            // Support "Card Name X" and "Card Name X or Card Name Y [or …]"
            String rest = targets.substring("Card Name ".length());
            String[] nameParts = rest.split("(?i)\\s+or\\s+Card\\s+Name\\s+");
            cardNameFilter = String.join("|", nameParts).trim();
            jobFilter      = null;
            inclForwards   = true;
            inclBackups    = true;
            inclMonsters   = true;
        } else if (tgtLower.startsWith("job ") && tgtLower.contains("or card name ")) {
            int orCnIdx    = tgtLower.indexOf("or card name ");
            String rawJob  = targets.substring("Job ".length(), orCnIdx)
                                    .trim().replaceAll("(?i)\\s*and\\s*/\\s*$", "").trim();
            // Either branch of the union may carry the type noun — "Job Warrior Forward or Card
            // Name Warrior Forward" (21-009C) puts it on both, "Job Chocobo and/or Card Name
            // Chocobo Characters" (Bartz 29-052H) on the last only. It names the rows to search,
            // not part of the job or the name: left on, the filters looked for a job called
            // "Warrior Forward" and a card called "Chocobo Characters", and matched nothing.
            List<String> jobParts = new ArrayList<>();
            for (String p : rawJob.split("(?i)\\s+or\\s+Job\\s+"))
                jobParts.add(p.trim().replaceAll(TYPE_NOUN_SUFFIX, "").trim());
            jobFilter      = String.join("|", jobParts);
            String rawName = targets.substring(orCnIdx + "or card name ".length()).trim();
            cardNameFilter = rawName.replaceAll(TYPE_NOUN_SUFFIX, "").trim();
            // Whichever branch stated it decides the rows; with none stated every row is eligible,
            // which is what this branch did unconditionally before.
            Matcher typeM  = UNION_TYPE_NOUN.matcher(targets);
            String  rowType = typeM.find() ? typeM.group(1).toLowerCase(Locale.ROOT) : null;
            inclForwards   = rowType == null || rowType.startsWith("forward") || rowType.startsWith("character");
            inclBackups    = rowType == null || rowType.startsWith("backup")  || rowType.startsWith("character");
            inclMonsters   = rowType == null || rowType.startsWith("monster") || rowType.startsWith("character");
        } else if (tgtLower.startsWith("job ")) {
            List<String> jobs = new ArrayList<>();
            Matcher wm = JOB_WRITTEN_SEGMENT.matcher(targets);
            while (wm.find()) jobs.add(wm.group(1).trim());
            boolean bareJob = jobs.isEmpty();
            if (bareJob)
                for (String p : targets.substring("Job ".length()).trim().split("(?i)\\s+or\\s+Job\\s+"))
                    jobs.add(p.trim());
            // A job phrase can name the card type it filters, and JOB_WRITTEN_SEGMENT reads only the
            // Forward spelling of that — so for "Job Class Zero Cadet Backups" (Queen 25-037H) the
            // phrase falls through to the bare split with the type word still stuck on the end of
            // the Job name, matching nothing. The word comes off the name here and decides the rows
            // instead: a Backup phrase searches the Backup row rather than the Forward row it
            // silently searched before. A bare "Job X" still means every row, as it did.
            Matcher typeM = JOB_PHRASE_TRAILING_TYPE.matcher(targets);
            String typedRow = bareJob && typeM.find() ? typeM.group(1).toLowerCase() : null;
            if (typedRow != null)
                jobs.replaceAll(j -> JOB_PHRASE_TRAILING_TYPE.matcher(j).replaceAll("").trim());
            jobFilter      = String.join("|", jobs);
            cardNameFilter = null;
            boolean anyRow = bareJob && typedRow == null;
            inclForwards   = anyRow || typedRow == null
                    || typedRow.startsWith("forward") || typedRow.startsWith("character");
            inclBackups    = anyRow
                    || (typedRow != null && (typedRow.startsWith("backup") || typedRow.startsWith("character")));
            inclMonsters   = anyRow
                    || (typedRow != null && (typedRow.startsWith("monster") || typedRow.startsWith("character")));
        } else {
            jobFilter      = null;
            cardNameFilter = null;
            boolean isGenericCard = tgtLower.equals("card") || tgtLower.equals("cards");
            inclForwards   = isGenericCard || tgtLower.contains("forward") || tgtLower.contains("character");
            inclBackups    = isGenericCard || tgtLower.contains("backup")  || tgtLower.contains("character");
            inclMonsters   = isGenericCard || tgtLower.contains("monster") || tgtLower.contains("character");
        }
        boolean inclSummons  = tgtLower.contains("summon")
                           || tgtLower.equals("card") || tgtLower.equals("cards");
        String  categoryFilter = m.group("category");
        String  excludeName      = restorePeriodInName(m.group("excludename") != null ? m.group("excludename").trim() : null, source);
        String  rawExcludeKw     = m.group("excludekw");
        boolean withoutMulticard = "Multicard".equalsIgnoreCase(rawExcludeKw != null ? rawExcludeKw.trim() : null);
        // Two spellings of the same constraint: "of any Element except X and Y" stands alone,
        // while "other than Card Name Z, X or Y" hangs off the name exclusion. Either fills the
        // one exclusion string the selection layer reads.
        String  rawExcludeElem = m.group("excludeelem") != null
                ? m.group("excludeelem") : m.group("excludeelemlist");
        final String fExcludeElem = rawExcludeElem != null ? rawExcludeElem.trim() : null;
        String  costStr      = m.group("cost");
        String  costListStr  = m.group("costlist");
        String  rawCostCmp   = m.group("costcmp");
        int     costVal      = costStr != null ? Integer.parseInt(costStr) : -1;
        // Convert digit-valued costcmp into the "or_…" sentinel understood by meetsCostConstraint.
        // Supports single ("cost N or M") and list ("cost A, B, … or Z") forms.
        String  costCmp;
        if (rawCostCmp != null && rawCostCmp.matches("\\d+")) {
            String tail = costListStr != null
                    ? costListStr.replaceAll("\\s+", "") + "," + rawCostCmp
                    : rawCostCmp;
            costCmp = "or_" + tail;
        } else {
            costCmp = rawCostCmp;
        }
        String  powerStr     = m.group("power");
        String  powerCmp     = m.group("powercmp");
        int     powerVal     = powerStr != null ? Integer.parseInt(powerStr) : -1;
        // Either slot may hold it — see the control2 comment on CHOOSE_CHARACTER_PATTERN.
        String  control      = m.group("control") != null ? m.group("control") : m.group("control2");
        boolean opponentOnly = control != null && !control.equalsIgnoreCase("you control");
        boolean selfOnly     = "you control".equalsIgnoreCase(control);
        String  zone         = m.group("zone") != null ? m.group("zone") : bzFieldZone;
        boolean bothZones    = zone != null && (zone.toLowerCase(java.util.Locale.ROOT).contains("either player")
                                             || zone.toLowerCase(java.util.Locale.ROOT).contains("any player"));
        boolean opponentZone = zone != null && !bothZones && zone.toLowerCase(java.util.Locale.ROOT).contains("opponent");

        String  followup     = restorePeriodInName(m.group("followup").trim(), source);
        boolean unreduced    = CANNOT_BE_REDUCED_PATTERN.matcher(followup).find();

        // If the followup contains ". " (sentence boundary), split into a primary effect
        // (applied to selected targets) and a secondary standalone effect that follows.
        // E.g. "Break it. <name> deals you 1 damage." → primary="Break it", secondary parsed separately.
        final String primaryFollowup;
        final String secondaryText;
        final Consumer<GameContext> secondary;
        {
            int dotSpaceIdx = sentenceBreakOutsideQuotes(followup);
            if (dotSpaceIdx >= 0) {
                primaryFollowup = followup.substring(0, dotSpaceIdx).trim();
                String stripped = stripRestrictionSentences(followup.substring(dotSpaceIdx + 2).trim());
                secondaryText = stripped.isEmpty() ? null : stripped;
                if (secondaryText == null) {
                    secondary = null;
                } else {
                    // Special case: "You may [cost]. When/If you do so, use this ability again."
                    // Captured here so the replay Consumer closes over the full original effect text.
                    Matcher replayM = MAY_COST_REPLAY_ABILITY.matcher(secondaryText);
                    if (replayM.find()) {
                        String payCost     = replayM.group("payCost");
                        String dullName    = replayM.group("dullName");
                        String discardName = replayM.group("discardName");
                        final String capturedText = text;
                        Consumer<GameContext> replayEffect =
                                ctx2 -> { Consumer<GameContext> inner = parse(capturedText, source, 0); if (inner != null) inner.accept(ctx2); };
                        if (payCost != null) {
                            final String elem = payCost.trim();
                            secondary = ctx -> ctx.mayPayToReplayAbility(elem, replayEffect);
                        } else if (dullName != null) {
                            final String name = dullName.trim();
                            secondary = ctx -> ctx.mayDullActiveCardToReplayAbility(name, replayEffect);
                        } else {
                            final String name = discardName.trim();
                            secondary = ctx -> ctx.mayDiscardCardNameToReplayAbility(name, replayEffect);
                        }
                    } else {
                        // Special case: "That Forward's controller discards N card(s) from their hand."
                        // The discarder depends on the chosen target's controller, which is read back
                        // from GameContext.lastChosenTargets() (populated by selectTargets).
                        Matcher ctrlDiscM = FOLLOWUP_TARGET_CONTROLLER_DISCARDS.matcher(secondaryText);
                        if (ctrlDiscM.matches()) {
                            final int discardCount = Integer.parseInt(ctrlDiscM.group("count"));
                            secondary = ctx -> {
                                List<ForwardTarget> chosen = ctx.lastChosenTargets();
                                for (ForwardTarget t : chosen) {
                                    if (t.isP1() == ctx.isP1()) ctx.selfDiscard(discardCount);
                                    else                        ctx.forceOpponentDiscard(discardCount);
                                }
                            };
                        } else if (FOLLOWUP_BREAK.matcher(secondaryText).find()) {
                            // "Break it." as a secondary applies to the same targets chosen for the primary.
                            secondary = ctx -> {
                                List<ForwardTarget> chosen = ctx.lastChosenTargets();
                                sortedByIdxDesc(chosen, true) .forEach(ctx::breakTarget);
                                sortedByIdxDesc(chosen, false).forEach(ctx::breakTarget);
                            };
                        } else if (FOLLOWUP_CANNOT_BE_BROKEN.matcher(secondaryText).find()
                                || FOLLOWUP_CANNOT_BE_BROKEN_SIMPLE.matcher(secondaryText).find()) {
                            secondary = ctx -> ctx.lastChosenTargets().forEach(ctx::shieldCannotBeBroken);
                        } else if (FOLLOWUP_CANNOT_BE_BROKEN_BY_NON_DMG.matcher(secondaryText).find()) {
                            secondary = ctx -> ctx.lastChosenTargets().forEach(ctx::shieldCannotBeBrokenByNonDmg);
                        } else if (ITS_AUTO_ABILITY_WILL_NOT_TRIGGER.matcher(secondaryText).matches()) {
                            // Not an effect of its own: it says how the primary's "Play it onto the
                            // field" resolves, and the PlayOntoField branch below reads it there.
                            // Left to the generic parse it becomes an unimplemented-followup log
                            // line and the played card's ETF trigger fires anyway — which for
                            // 22-058H Qator Bashtar is the whole of what the sentence forbids.
                            secondary = null;
                        } else if (FOLLOWUP_IF_PUT_TO_BZ_THIS_TURN_RFG_INSTEAD.matcher(secondaryText).find()) {
                            secondary = ctx -> ctx.lastChosenTargets().forEach(ctx::markTargetRfgInsteadOfBzThisTurn);
                        } else if (source != null
                                && SECONDARY_WHEN_TARGET_LEAVES_PUT_SELF_TO_BZ.matcher(secondaryText).matches()) {
                            // "When that Forward leaves the field this turn, put [Self] into the
                            // Break Zone." — 7-055R Chocobo's drawback, armed on the Forward the
                            // primary just lent power to. Without it Chocobo handed out a free
                            // +3000 and kept its side of the bargain to itself.
                            Matcher leaveM = SECONDARY_WHEN_TARGET_LEAVES_PUT_SELF_TO_BZ.matcher(secondaryText);
                            leaveM.matches();
                            String lender = leaveM.group("name").trim();
                            secondary = lender.equalsIgnoreCase(source.name())
                                    ? ctx -> ctx.lastChosenTargets()
                                            .forEach(t -> ctx.markTargetPutSourceToBzOnLeaveThisTurn(t, source))
                                    : null;
                        } else {
                            Matcher rfpM = SECONDARY_PLAY_REMOVED_ONTO_FIELD.matcher(secondaryText);
                            if (rfpM.find()) {
                                boolean dullIt = rfpM.group("dull") != null;
                                secondary = ctx -> ctx.playLastRemovedFromRfpOntoField(dullIt);
                            } else {
                                // Tried ahead of the general chain: this sentence's "its" is the
                                // Forward the primary chose, which a standalone parse cannot see.
                                Consumer<GameContext> parsed =
                                        secondaryCounterGatedPowerBecomes(secondaryText, source);
                                if (parsed == null) parsed = parse(secondaryText, source);
                                secondary = (parsed != null) ? parsed
                                        : ctx -> ctx.logEntry("[ActionResolver] Secondary followup not yet implemented: " + secondaryText);
                            }
                        }
                    }
                }
            } else {
                primaryFollowup = followup;
                secondaryText   = null;
                secondary = null;
            }
        }

        // Detect "You may [followup]" — followup is optional; player may decline the action after choosing the target
        final boolean followupIsOptional = primaryFollowup.toLowerCase(java.util.Locale.ROOT).startsWith("you may ");
        final String strippedPrimaryFollowup = followupIsOptional
                ? primaryFollowup.substring("You may ".length()).trim() : primaryFollowup;

        // Shared log prefix helper (captured once, reused in all lambdas)
        String costLabel     = CardFilters.formatCostFilterLabel(costVal, costCmp);
        String powerLabel    = powerVal >= 0
                ? " of power " + powerVal + (powerCmp != null ? " or " + powerCmp : "") : "";
        String controlLabel  = opponentOnly ? " (opponent)" : selfOnly ? " (yours)" : "";
        String categoryLabel = categoryFilter != null ? " Category " + categoryFilter : "";
        String excludeLabel  = excludeName != null ? " (excl. " + excludeName + ")" : "";
        String zoneLabel     = zone != null
                ? " in " + (bothZones ? "either player's" : opponentZone ? "opponent's" : "your") + " Break Zone" : "";
        String choosePrefix = "Choose " + (upTo ? "up to " : any ? "any number of " : "") + (maxCount < Integer.MAX_VALUE ? maxCount : "")
                + (condition != null ? " " + condition : "")
                + (element   != null ? " " + element   : "")
                + categoryLabel + " " + targets + costLabel + powerLabel + controlLabel + excludeLabel + zoneLabel;


        // =====================================================================================
        // Cast-payment gate over the followup
        // =====================================================================================
        // --- "Choose … . If the cost paid to cast [Self] included [E] CP, <followup>" ---------
        // Two whole cycles print the gate here rather than ahead of the choose — Opus 7's
        // "cost paid to play" (7-011C Summoner, 7-058C Ninja, …) and Opus 13's "cost paid to
        // cast" (13-004C Clavat, 13-099C Yuke, …), sixteen abilities between them. Every followup
        // parser below matches with find(), so each of them found its own verb inside the gate
        // clause and ran it unconditionally: Clavat froze two Forwards whatever the cost was paid
        // with. Settled here, ahead of all of them, for that reason.
        //
        // The gate is stripped and the rest re-parsed from the top, so every followup the chain
        // already understands is gated without being taught the gate. That subsumed the one branch
        // that had read a gate for itself — 7-105C Dragoon's "cannot be blocked" — so that branch
        // and its pattern are gone; the plain FOLLOWUP_CANNOT_BE_BLOCKED it now lands on carries
        // the same cost qualifier and sets the same flags.
        //
        // The choose itself is NOT gated: only the effect is conditional. Targets are chosen
        // whatever the cost was paid with, and being chosen is an event of its own — 1-037H Kuja
        // and 12-024H Emet-Selch trigger on it, and selectTargets fires those. Skipping the
        // selection would silently swallow their triggers.
        //
        // A gate this cannot honour — one naming another card's cast, or guarding a followup the
        // chain does not read — gives up the whole text rather than falling through, which would
        // hand the followup straight back to the find() matchers below and run it unconditionally.
        // That is the bug being fixed here, so it is not an acceptable fallback.
        Matcher castPaidM = CAST_PAYMENT_ELEMENT_CP_GATE_CLAUSE.matcher(followup);
        if (castPaidM.lookingAt()) {
            if (source == null || !castPaidM.group("name").trim().equalsIgnoreCase(source.name()))
                return null;
            final String gateElement = cap(castPaidM.group("element"));
            String ungated = text.substring(0, m.start("followup")) + followup.substring(castPaidM.end());
            Consumer<GameContext> gatedEffect = tryParseChooseCharacterInner(ungated, source, xValue);
            if (gatedEffect == null) return null;
            return ctx -> {
                if (ctx.wasElementCpPaid(gateElement)) {
                    gatedEffect.accept(ctx);
                    return;
                }
                ctx.logChooseHeader(choosePrefix + " — " + gateElement + " CP was not paid to cast "
                        + source.name() + "; choosing anyway, no effect");
                selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- "Choose … . If the cost to play [Self] was only paid with [E] CP, <followup>" -----
        // The strict sibling of the gate above, and the other half of the Opus 7 cycle: 7-014R
        // Baugauven, 7-064R Asmodai and 7-113R Styx print it here, 19-056C Graff prints the Opus
        // 19 reprint of the same wording. "Only paid with Fire CP" is a stronger claim than
        // "included Fire CP" — a payment that mixed in a second Element fails it — so it goes
        // through castPaymentWasOnlyElement rather than wasElementCpPaid.
        //
        // Everything the note above says about why this is settled here applies unchanged: each
        // of these four had its verb found inside the gate clause by a followup matcher below and
        // run on every cast, so Baugauven dealt 7000 damage whatever the CP had been. The choose
        // still happens either way, and a gate this cannot honour gives up the whole text.
        //
        // Read after the "included" clause because the two wordings are disjoint and this one is
        // the narrower: neither can claim the other's text, and keeping the older, larger cycle
        // first leaves its sixteen abilities on the matcher they already resolved through.
        Matcher onlyPaidM = CAST_PAYMENT_ONLY_ELEMENT_CP_GATE_CLAUSE.matcher(followup);
        if (onlyPaidM.lookingAt()) {
            if (source == null || !onlyPaidM.group("name").trim().equalsIgnoreCase(source.name()))
                return null;
            final String gateElement = cap(onlyPaidM.group("element"));
            String ungated = text.substring(0, m.start("followup")) + followup.substring(onlyPaidM.end());
            Consumer<GameContext> gatedEffect = tryParseChooseCharacterInner(ungated, source, xValue);
            if (gatedEffect == null) return null;
            return ctx -> {
                if (ctx.castPaymentWasOnlyElement(source, gateElement)) {
                    gatedEffect.accept(ctx);
                    return;
                }
                ctx.logChooseHeader(choosePrefix + " — " + source.name() + " was not paid for with "
                        + gateElement + " CP alone; choosing anyway, no effect");
                selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- "Choose … . If you have cast N or more cards this turn, <followup>" ----------------
        // The third gate settled here, and settled here for the reason the two above are: it sits
        // between the choose and its followup, and every followup matcher below scans with find(),
        // so each of 12-043C White Mage, 18-113H Cid Haze and 20-052C Gnash found its own verb
        // inside the gate clause and ran it unconditionally — Gnash broke a Backup of cost 5 or
        // more whatever had been cast that turn.
        //
        // Stripped and re-parsed from the top like its siblings, so every followup the chain
        // already reads is gated without being taught the gate. The choose still happens when the
        // condition fails: being chosen is an event of its own, and only the effect is conditional.
        //
        // Not the same shape as CAST_COUNT_GATE, which reads this condition as a trailing sentence
        // over an effect that has already resolved. That one cannot reach these three, because
        // here the condition arrives before the followup rather than after a complete effect.
        Matcher castCountM = CAST_COUNT_GATE_CLAUSE.matcher(followup);
        if (castCountM.lookingAt()) {
            final int required = Integer.parseInt(castCountM.group("count"));
            String ungated = text.substring(0, m.start("followup")) + followup.substring(castCountM.end());
            Consumer<GameContext> gatedEffect = tryParseChooseCharacterInner(ungated, source, xValue);
            if (gatedEffect == null) return null;
            return ctx -> {
                int cast = ctx.selfCardsCastThisTurn();
                if (cast >= required) {
                    gatedEffect.accept(ctx);
                    return;
                }
                ctx.logChooseHeader(choosePrefix + " — only " + cast + " card(s) cast this turn (need "
                        + required + "); choosing anyway, no effect");
                selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // =====================================================================================
        // Quoted grants, optional payments and search payoffs
        // =====================================================================================
        // --- Multi-sentence quoted-ability grants ---------------------------------------
        // Settled here, ahead of every followup parser below, because those all match with
        // find(): the effects printed inside a quotation belong to the ability being granted, not
        // to this choose, and a quotation long enough to hold a sentence break is long enough for
        // one of them to reach in and claim a clause. 12-013C Ninja grants "When this Forward
        // attacks, choose 1 Forward. Deal it 5000 damage." and was resolving as the choose itself
        // dealing 5000 damage.
        //
        // Scoped to quotations that span a sentence on purpose. The single-sentence grants
        // (breaktouch, cannot-be-chosen, must-block, ability-damage shield) have handlers further
        // down that nothing in between has ever claimed, and are left on that path untouched.
        {
            Matcher anyGrantM = FOLLOWUP_GAINS_QUOTED_ABILITY.matcher(primaryFollowup.trim());
            if (anyGrantM.matches() && anyGrantM.group("quoted").contains(". ")) {
                // Permanent grant ("(This effect does not end at the end of the turn.)"), the one
                // shape this engine can apply: it goes into the permanent granted-ability map and
                // is dropped only when the grantee leaves the field (21-079R Lich).
                Matcher permM = FOLLOWUP_GAINS_QUOTED_ABILITY_PERMANENT.matcher(primaryFollowup.trim());
                if (permM.matches()) {
                    String granted = PERMANENCE_REMINDER.matcher(permM.group("quoted").trim())
                            .replaceFirst("").trim();
                    // A clause parseAutoAbilities does not recognise would be granted inert, so it
                    // declines to the warning below instead — permanentGrantForClause's reasoning.
                    if (!CardData.parseAutoAbilities(granted).isEmpty()) {
                        return ctx -> {
                            ctx.logChooseHeader(choosePrefix + " — gains \"" + granted
                                    + "\" (does not end at end of turn)");
                            List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                                    opponentOnly, selfOnly, condition, element, zone, opponentZone,
                                    costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                            ts.forEach(t -> ctx.grantAutoAbilityPermanently(t, granted));
                            if (secondary != null) secondary.accept(ctx);
                        };
                    }
                }
                final String unhandled = primaryFollowup;
                Consumer<GameContext> warn = ctx -> ctx.logEntry(
                        "[ActionResolver] Choose effect — granted ability not yet implemented: " + unhandled);
                return secondary == null ? warn : warn.andThen(secondary);
            }
        }

        // --- "It gains +N power and "<clause>" (This effect does not end at the end of the turn.)" ---
        // Ellone 27-020R. Settled beside the quoted-grant block above and ahead of every find()
        // parser below for the same reason that one is: "gains +2000 power" is exactly what
        // FOLLOWUP_POWER_BOOST scans for, and the "draw 1 card" printed inside the quotation is what
        // the draw parsers scan for. Left to the chain, the sentence resolved as neither half of what
        // the card does — a boost that expires at end of turn, plus an immediate draw for the caster.
        //
        // Read off the whole followup rather than the primary half: the reminder sits outside the
        // quotation here, and the followup still carries the trailing "You can only use this ability
        // during your turn." because it holds no ". " anywhere to split on. Stripping the restriction
        // sentences first is what isMustAttackAndMustBlockGrant does with the same problem.
        {
            String grantCore = stripRestrictionSentences(followup);
            if (grantCore.isEmpty()) grantCore = followup;
            Matcher permBoostM =
                    FOLLOWUP_GAINS_POWER_AND_QUOTED_ABILITY_PERMANENT.matcher(grantCore.trim());
            if (permBoostM.matches()) {
                int    boost   = Integer.parseInt(permBoostM.group("amount"));
                String granted = permBoostM.group("quoted").trim();
                // Both halves or neither, the rule the quoted-grant block above follows: a clause
                // parseAutoAbilities cannot read would be granted inert, and handing out the power
                // while quietly dropping the ability reports the card as handled when it is not.
                if (!CardData.parseAutoAbilities(granted).isEmpty()) {
                    return ctx -> {
                        ctx.logChooseHeader(choosePrefix + " — gains +" + boost + " power and \"" + granted
                                + "\" (does not end at end of turn)");
                        List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                                opponentOnly, selfOnly, condition, element, zone, opponentZone,
                                costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters,
                                jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                        // Both stores are additive, so a Forward handed this twice ends up with twice
                        // the power and two copies of the trigger — see boostTargetPermanently.
                        ts.forEach(t -> {
                            ctx.boostTargetPermanently(t, boost, EnumSet.noneOf(CardData.Trait.class));
                            ctx.grantAutoAbilityPermanently(t, granted);
                        });
                    };
                }
            }
        }

        // --- "You may pay 《Element》. If you do so, [target action]." ---
        // Checked against the full followup before the primary/secondary split so the conditional is not lost.
        {
            Matcher youMayPayM = FOLLOWUP_YOU_MAY_PAY_ELEMENT_IF_DO_SO.matcher(followup);
            if (youMayPayM.matches()) {
                String cpElem    = youMayPayM.group("element").trim();
                String cpEffText = youMayPayM.group("effect").trim();
                BiConsumer<GameContext, List<ForwardTarget>> cpAction =
                        parseTargetAction(cpEffText, xValue);
                if (cpAction != null) {
                    return ctx -> {
                        ctx.logChooseHeader(choosePrefix + " — You may pay 《" + cpElem + "》; if so: " + cpEffText);
                        List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                                opponentOnly, selfOnly, condition, element, zone, opponentZone,
                                costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters,
                                jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                        ctx.mayPayElementCpToEffect(cpElem, ctx2 -> cpAction.accept(ctx2, ts));
                    };
                }
            }
        }

        // --- "[You may] search for N … (with the same name | of the same Element as the chosen
        //      Character) and add it to your hand." ---
        // 12-106R Relm, 23-078C Alisaie, 23-130H Luso. The search's filter is not written in the
        // text: it is a property of whatever the player just chose, so it can only be built while
        // resolving. Placed ahead of every other followup branch because those all act on the
        // chosen target, and this one does not — the destination clause it ends with is where the
        // *searched* card goes. Left to the generic dispatch, FOLLOWUP_ADD_TO_HAND finds the
        // trailing "add it to your hand" and returns the chosen Character from the field to hand,
        // searching nothing.
        {
            Matcher searchMatchM = FOLLOWUP_SEARCH_MATCHING_CHOSEN.matcher(strippedPrimaryFollowup);
            if (searchMatchM.matches()) {
                int    count       = Integer.parseInt(searchMatchM.group("count"));
                String searchJob   = searchMatchM.group("job")      != null ? searchMatchM.group("job").trim()      : null;
                String searchCat   = searchMatchM.group("category") != null ? searchMatchM.group("category").trim() : null;
                boolean bySameName = searchMatchM.group("samename") != null;

                String  searchType = searchMatchM.group("searchtype") != null
                        ? searchMatchM.group("searchtype").toLowerCase(java.util.Locale.ROOT) : "";
                boolean anyType = searchType.isEmpty() || searchType.startsWith("card");
                boolean srchFwd = anyType || searchType.startsWith("forward") || searchType.startsWith("character");
                boolean srchBk  = anyType || searchType.startsWith("backup")  || searchType.startsWith("character");
                boolean srchMn  = anyType || searchType.startsWith("monster") || searchType.startsWith("character");
                boolean srchSm  = anyType || searchType.startsWith("summon");

                String destination = searchMatchM.group("destination")
                        .toLowerCase(java.util.Locale.ROOT).contains("hand") ? "hand" : "field";

                String filterLabel = (searchJob != null ? " [Job " + searchJob + "]" : "")
                        + (searchCat != null ? " [Cat " + searchCat + "]" : "");
                return ctx -> {
                    ctx.logChooseHeader(choosePrefix + " — then search deck for " + count + filterLabel
                            + " of the same " + (bySameName ? "name" : "Element") + " → " + destination);
                    List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                            opponentOnly, selfOnly, condition, element, zone, opponentZone,
                            costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters,
                            jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                    if (ts.isEmpty()) {
                        ctx.logEntry("Nothing chosen — no search takes place");
                        ctx.markEffectFizzled();
                        return;
                    }
                    ForwardTarget t = ts.get(0);
                    // Break-zone targets are not on the field, so targetCard cannot resolve them.
                    CardData picked = zone != null
                            ? (t.isP1() ? ctx.p1BreakZoneCard(t.idx()) : ctx.p2BreakZoneCard(t.idx()))
                            : ctx.targetCard(t);
                    if (picked == null) { ctx.markEffectFizzled(); return; }

                    // "of the same Element as" — a multi-element card is each of its Elements, so
                    // sharing any one of them qualifies.
                    String nameFilter = bySameName ? picked.name() : null;
                    String elemFilter = bySameName ? null : picked.element().replace("/", "|");

                    // The prompt comes before the search, not after: searching is a public event
                    // opponents' abilities react to, so a player who declines must not have searched.
                    if (followupIsOptional && !ctx.promptYouMay("Search your deck for a "
                            + (bySameName ? "card named " + picked.name()
                                          : picked.element() + " card")
                            + "? Declining means you did not search.")) {
                        ctx.logEntry("Declined to search — no search takes place");
                        ctx.markEffectFizzled();
                        return;
                    }
                    ctx.logEntry("Chosen: " + picked.name() + " (" + picked.element() + ")");
                    ctx.searchDeckForCard(srchFwd, srchBk, srchMn, srchSm, -1, null,
                            nameFilter, searchJob, searchCat, elemFilter, null, null,
                            destination, count, false, false);
                    if (secondary != null) secondary.accept(ctx);
                };
            }
        }

        // --- "If your opponent doesn't pay 《N》, [target action]." (Arkasodara) ---
        // The opponent may pay to prevent the action against the chosen target(s).
        {
            Matcher notPayM = FOLLOWUP_IF_OPP_NOT_PAY_ACTION.matcher(followup);
            if (notPayM.matches()) {
                int notPayCost = Integer.parseInt(notPayM.group("cost").trim());
                String notPayEffText = notPayM.group("effect").trim();
                BiConsumer<GameContext, List<ForwardTarget>> notPayAction =
                        parseTargetAction(notPayEffText, xValue);
                if (notPayAction != null) {
                    return ctx -> {
                        ctx.logChooseHeader(choosePrefix + " — unless opponent pays 《" + notPayCost + "》: " + notPayEffText);
                        List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                                opponentOnly, selfOnly, condition, element, zone, opponentZone,
                                costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters,
                                jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                        if (ts.isEmpty()) return;
                        ctx.opponentMayPayToPreventAction(notPayCost, () -> notPayAction.accept(ctx, ts));
                    };
                }
            }
        }

        // --- "[action]. Then, if you don't pay 《1》 per CP of the chosen card's cost, break it." ---
        // Checked against the full followup before the primary/secondary split, since the split
        // would drop the trailing clause and leave the primary action unconditional.
        {
            Matcher perCpM = FOLLOWUP_THEN_PAY_PER_TARGET_COST_OR_BREAK.matcher(followup);
            if (perCpM.matches()) {
                String primaryText = perCpM.group("primary").trim();
                BiConsumer<GameContext, List<ForwardTarget>> primaryAction =
                        parseTargetAction(primaryText, xValue);
                // "You gain control of it" is not one of parseTargetAction's verbs, and it is the
                // primary the only printed card (Ultimecia 27-092H) uses.
                if (primaryAction == null && FOLLOWUP_GAIN_CONTROL.matcher(primaryText).find())
                    primaryAction = (c2, ts2) -> ts2.forEach(t -> c2.gainControlOfForward(t, "permanent", false));
                if (primaryAction != null) {
                    final BiConsumer<GameContext, List<ForwardTarget>> fPrimary = primaryAction;
                    return ctx -> {
                        ctx.logChooseHeader(choosePrefix + " — " + primaryText
                                + ", then pay 《1》 per CP of its cost or put it into the Break Zone");
                        List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                                opponentOnly, selfOnly, condition, element, zone, opponentZone,
                                costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters,
                                jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                        if (ts.isEmpty()) return;
                        // Resolve the cards up front: the primary action may change control of them,
                        // which invalidates the side/index a ForwardTarget carries.
                        List<CardData> chosen = new ArrayList<>();
                        for (ForwardTarget t : ts) {
                            CardData c = ctx.targetCard(t);
                            if (c != null) chosen.add(c);
                        }
                        fPrimary.accept(ctx, ts);
                        for (CardData c : chosen) {
                            // Only charge for a card the primary actually handed over — a steal that
                            // did not go through leaves nothing to pay for or break.
                            if (!ctx.selfControlsCard(c)) continue;
                            ctx.mayPayCostOrElse(c.cost(), null, 0, () -> ctx.breakSourceCard(c));
                        }
                    };
                }
            }
        }

        // --- "It gains +N power ... If you control X, it gains +M power ... instead." (4-090R Biggs) ---
        // Read off the whole followup: split, the primary is a plain PowerBoost and the upgrade
        // lands in the secondary as a control gate whose inner "it" has no target to attach to.
        // "Instead" means one figure or the other, never both, so the condition picks the amount.
        Matcher gatedBoostM = FOLLOWUP_POWER_BOOST_CONTROL_GATED_INSTEAD.matcher(followup);
        if (gatedBoostM.matches()) {
            ControlCondition cc = CardData.parseControlCondition(gatedBoostM.group("cond").trim());
            if (cc != null) {
                int baseBoost = Integer.parseInt(gatedBoostM.group("base"));
                int altBoost  = Integer.parseInt(gatedBoostM.group("alt"));
                EnumSet<CardData.Trait> noTraits = EnumSet.noneOf(CardData.Trait.class);
                return ctx -> {
                    List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                            opponentOnly, selfOnly, condition, element, zone, opponentZone,
                            costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters,
                            jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                    boolean upgraded = ctx.controlConditionMet(cc);
                    int boost = upgraded ? altBoost : baseBoost;
                    ctx.logChooseHeader(choosePrefix + " +" + boost + " power until EOT"
                            + (upgraded ? " (you control " + cc + ")" : ""));
                    sortedByIdxDesc(ts, true) .forEach(t -> ctx.boostTarget(t, boost, noTraits));
                    sortedByIdxDesc(ts, false).forEach(t -> ctx.boostTarget(t, boost, noTraits));
                };
            }
        }

        // --- "Deal it damage equal to [Self]'s power. If you discarded a Summon to pay this
        //      ability's cost, deal it double ... instead." (29-107C Seer (FFTA2)) ---
        Matcher seerM = FOLLOWUP_DAMAGE_SELF_POWER_DOUBLED_IF_SUMMON_DISCARD.matcher(followup);
        if (source != null && seerM.matches()
                && seerM.group("name").trim().equalsIgnoreCase(source.name())
                && seerM.group("name2").trim().equalsIgnoreCase(source.name())) {
            return ctx -> {
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters,
                        jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                // The source's power now, not its printed power: a boosted Seer hits harder.
                int power = ctx.fieldForwardPowerByName(source.name());
                boolean doubled = ctx.lastDiscardedCostCardIsSummon();
                int damage = doubled ? power * 2 : power;
                ctx.logChooseHeader(choosePrefix + " — deal " + damage + " ("
                        + source.name() + "'s power" + (doubled ? ", doubled: Summon discarded" : "") + ")");
                if (damage <= 0) return;
                sortedByIdxDesc(ts, true) .forEach(t -> ctx.damageTarget(t, damage));
                sortedByIdxDesc(ts, false).forEach(t -> ctx.damageTarget(t, damage));
            };
        }

        // --- "You may discard 1 Card Name X from your hand. If you do so, deal it N damage." ---
        // Checked against the full followup before the primary/secondary split.
        Matcher mayDiscardNamedM = FOLLOWUP_MAY_DISCARD_NAMED_DEAL_DAMAGE.matcher(followup);
        if (mayDiscardNamedM.matches()) {
            // Exactly one of the two is present — the pattern's alternation guarantees it.
            String discardName = mayDiscardNamedM.group("cardname") != null
                    ? mayDiscardNamedM.group("cardname").trim() : null;
            String discardType = mayDiscardNamedM.group("cardtype") != null
                    ? mayDiscardNamedM.group("cardtype").toLowerCase(java.util.Locale.ROOT)
                            .replaceAll("s$", "") : null;
            int    damage      = Integer.parseInt(mayDiscardNamedM.group("amount"));
            // 0 when the card prints no "If not" sentence, in which case declining does nothing.
            int    elseDamage  = mayDiscardNamedM.group("elseamount") != null
                    ? Integer.parseInt(mayDiscardNamedM.group("elseamount")) : 0;
            String discardLabel = discardName != null ? "Card Name " + discardName : "1 " + discardType;
            return ctx -> {
                ctx.logChooseHeader(choosePrefix + " — May discard " + discardLabel
                        + ", if so deal " + damage + " damage"
                        + (elseDamage > 0 ? ", if not deal " + elseDamage : ""));
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters,
                        jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                Consumer<GameContext> ifDiscarded = ctx2 -> {
                    sortedByIdxDesc(ts, true) .forEach(t -> ctx2.damageTarget(t, damage));
                    sortedByIdxDesc(ts, false).forEach(t -> ctx2.damageTarget(t, damage));
                };
                Consumer<GameContext> ifNot = ctx2 -> {
                    if (elseDamage <= 0) return;
                    sortedByIdxDesc(ts, true) .forEach(t -> ctx2.damageTarget(t, elseDamage));
                    sortedByIdxDesc(ts, false).forEach(t -> ctx2.damageTarget(t, elseDamage));
                };
                if (discardName != null) ctx.mayDiscardCardNameFromHandOrElse(discardName, ifDiscarded, ifNot);
                else                     ctx.mayDiscardCardOfTypeFromHandOrElse(discardType, ifDiscarded, ifNot);
            };
        }

        // --- "You may discard 1 [type]" with the payoff in the next sentence (7-040C Yunalesca) ---
        // Must follow the branch above, which reads the whole followup while this one reads only
        // its first sentence: checked first, this claimed 1-190S Bahamut Fury's opening clause
        // and dropped the two damage branches that give the offer its point.
        // The card's own optionality, printed mid-ability: CardData's youMay flag is set only from
        // a leading "you may", so nothing above this honours it. Left unhandled, the choose logged
        // an unimplemented followup and the "If you do so" payoff ran with no discard demanded.
        Matcher mayDiscardTypeM = FOLLOWUP_MAY_DISCARD_TYPE_BARE.matcher(primaryFollowup.trim());
        if (mayDiscardTypeM.matches()) {
            final String discardType = mayDiscardTypeM.group("cardtype")
                    .toLowerCase(java.util.Locale.ROOT).replaceAll("s$", "");
            return ctx -> {
                ctx.logChooseHeader(choosePrefix + " — may discard 1 " + discardType);
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters,
                        jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                if (ts.isEmpty()) return;
                // Last, so the fizzle it marks on a declined offer is the one the enclosing
                // "When you do so, …" reads.
                ctx.mayDiscardCardOfTypeFromHand(discardType);
                if (secondary != null) secondary.accept(ctx);
            };
        }


        // --- "You may search for 1 [Elem] [Type] and remove it from the game. If you do so, break
        //      the chosen Forwards. If not, deal N damage to the chosen Forwards." (29-117H Ark) ---
        // Also read off the full followup: after the split its first sentence reaches the generic
        // chain, where "remove it from the game" is taken as removing the chosen Forwards.
        Matcher maySearchRfgM = FOLLOWUP_MAY_SEARCH_RFG_THEN_ELSE.matcher(followup);
        if (maySearchRfgM.matches()) {
            BiConsumer<GameContext, List<ForwardTarget>> thenAction =
                    parseChosenTargetsAction(maySearchRfgM.group("thenact"));
            BiConsumer<GameContext, List<ForwardTarget>> elseAction =
                    parseChosenTargetsAction(maySearchRfgM.group("elseact"));
            // Both branches or nothing — resolving one and dropping the other is worse than
            // letting the text fall through to the generic chain.
            if (thenAction != null && elseAction != null) {
                String  searchElem  = maySearchRfgM.group("element");
                String  typeRaw     = maySearchRfgM.group("type");
                String  typeLower   = typeRaw.toLowerCase(java.util.Locale.ROOT);
                boolean wantFwd     = typeLower.startsWith("forward") || typeLower.startsWith("character");
                boolean wantBkp     = typeLower.startsWith("backup")  || typeLower.startsWith("character");
                boolean wantMon     = typeLower.startsWith("monster") || typeLower.startsWith("character");
                String  searchLabel = (searchElem != null ? searchElem + " " : "") + typeRaw;
                return ctx -> {
                    ctx.logChooseHeader(choosePrefix + " — May search/RFG 1 " + searchLabel
                            + ": if so \"" + maySearchRfgM.group("thenact")
                            + "\", if not \"" + maySearchRfgM.group("elseact") + "\"");
                    List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                            opponentOnly, selfOnly, condition, element, zone, opponentZone,
                            costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters,
                            jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                    // Declining and searching in vain are both "if not": the "if you do so" branch
                    // is taken only when a card was really found and removed.
                    boolean removed = ctx.promptYouMay(
                                    "Search for 1 " + searchLabel + " and remove it from the game?")
                            && ctx.searchDeckForCard(wantFwd, wantBkp, wantMon, false,
                                    -1, null, null, null, null, searchElem, null, null,
                                    "removedFromGame", 1, false, false);
                    if (removed) thenAction.accept(ctx, ts);
                    else         elseAction.accept(ctx, ts);
                };
            }
        }


        // =====================================================================================
        // Damage followups
        // =====================================================================================
        // --- "Divide N damage" ---
        Matcher divideM = DIVIDE_DAMAGE_PATTERN.matcher(followup);
        if (divideM.find())
        {
            int baseDamage = Integer.parseInt(divideM.group("amount"));
            final boolean equally = divideM.group("mode") != null;

            int dotSpaceIdxCond = followup.indexOf(". ");
            String followup_cond = dotSpaceIdxCond >= 0 ? followup.substring(dotSpaceIdxCond + 2) : "";
            Matcher divideCondM = DIVIDE_DAMAGE_INSTEAD_COND.matcher(followup_cond);
            final DamageInsteadCondition insteadCond;
            final int altDamage;
            if (divideCondM.find()) {
                DamageInsteadCondition parsedCond = parseDamageInsteadCondition(divideCondM.group("cond").trim());
                // Anchored to "divide N damage" specifically — a bare \d+ search would wrongly
                // grab a digit embedded in the condition text itself (e.g. "Category FFTA2").
                Matcher mAmp = DIVIDE_DAMAGE_PATTERN.matcher(followup_cond);
                insteadCond = parsedCond;
                altDamage   = (parsedCond != null && mAmp.find()) ? Integer.parseInt(mAmp.group("amount")) : baseDamage;
            } else {
                insteadCond = null;
                altDamage   = baseDamage;
            }

            final boolean fUnreduced = unreduced;
            final int fBaseDamage = baseDamage;
            return ctx -> {
                int fDamage = fBaseDamage;
                if (insteadCond != null && insteadConditionMet(ctx, insteadCond)) fDamage = altDamage;

                List<ForwardTarget> ts = selectTargets(ctx, maxCount, any || upTo,
                        opponentOnly, selfOnly, null, null, null, false,
                        -1, null, -1, null,
                        true, false, false,
                        null, null, null, null, false, null, false);
                if (ts.isEmpty()) return;

                if (equally) {
                    int perTarget = roundUpToThousand(fDamage, ts.size());
                    sortedByIdxDesc(ts, true) .forEach(t -> damageTargetMaybeUnreduced(ctx, t, perTarget, fUnreduced));
                    sortedByIdxDesc(ts, false).forEach(t -> damageTargetMaybeUnreduced(ctx, t, perTarget, fUnreduced));
                } else if (ts.size() == 1) {
                    // Nothing to divide — skip the allocation dialog and deal it all.
                    damageTargetMaybeUnreduced(ctx, ts.get(0), fDamage, fUnreduced);
                } else {
                    List<CardData> cards = new ArrayList<>();
                    for (ForwardTarget t : ts) {
                        cards.add(t.isP1() ? ctx.p1Forward(t.idx()) : ctx.p2Forward(t.idx()));
                    }
                    List<Integer> allocation = ctx.divideDamageAmount(fDamage, "Divide Damage: ", cards);
                    Map<ForwardTarget, Integer> amountByTarget = new HashMap<>();
                    for (int i = 0; i < ts.size(); i++) amountByTarget.put(ts.get(i), allocation.get(i));
                    sortedByIdxDesc(ts, true) .forEach(t -> { int amt = amountByTarget.get(t); if (amt > 0) damageTargetMaybeUnreduced(ctx, t, amt, fUnreduced); });
                    sortedByIdxDesc(ts, false).forEach(t -> { int amt = amountByTarget.get(t); if (amt > 0) damageTargetMaybeUnreduced(ctx, t, amt, fUnreduced); });
                }
            };
        }

        // --- "Draw N card(s). Then, until EOT, it loses M power for each card in your hand." ---
        // Read off the full followup, like the branches below it: the sentence split puts the draw
        // in the primary and the reduction in the secondary, and neither half means anything alone
        // — "it" in the second names the card the first sentence never chose. Order is the point,
        // so the two are resolved together rather than as a followup and a tail.
        Matcher drawThenHandM = FOLLOWUP_DRAW_THEN_POWER_REDUCE_FOR_EACH_HAND.matcher(followup);
        if (drawThenHandM.matches()) {
            int draws   = Integer.parseInt(drawThenHandM.group("draw"));
            int perCard = Integer.parseInt(drawThenHandM.group("amount"));
            return ctx -> {
                ctx.logChooseHeader(choosePrefix + " — Draw " + draws + ", then -" + perCard
                        + "x[your hand] until EOT");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                // Drawn before the hand is counted, which is what "Draw 1 card. Then, … for each
                // card in your hand" says and what makes the draw worth another perCard of it.
                ctx.drawCards(draws);
                int n = ctx.yourHandSize();
                int reduction = perCard * n;
                ctx.logEntry("Effect: hand is " + n + " card(s) — reduce by " + reduction);
                EnumSet<CardData.Trait> noTraits = EnumSet.noneOf(CardData.Trait.class);
                sortedByIdxDesc(ts, true) .forEach(t -> ctx.reduceTarget(t, reduction, noTraits));
                sortedByIdxDesc(ts, false).forEach(t -> ctx.reduceTarget(t, reduction, noTraits));
            };
        }

        // --- "Deal it N damage. If <cond>, deal it M damage instead." ---
        // Matched against the full followup before the primary/secondary split to avoid losing the condition.
        Matcher insteadM = FOLLOWUP_DAMAGE_INSTEAD.matcher(followup);
        if (insteadM.find()) {
            int    baseDmg   = Integer.parseInt(insteadM.group("base"));
            int    altDmg    = Integer.parseInt(insteadM.group("alt"));
            String condText  = insteadM.group("cond").trim();
            DamageInsteadCondition insteadCond = parseDamageInsteadCondition(condText);
            if (insteadCond != null) {
                return ctx -> {
                    ctx.logChooseHeader(choosePrefix + " — Deal " + baseDmg + "/" + altDmg + " damage (if " + condText + ")");
                    List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                            opponentOnly, selfOnly, condition, element, zone, opponentZone,
                            costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                    sortedByIdxDesc(ts, true) .forEach(t -> ctx.damageTarget(t, resolveInsteadDamage(ctx, t, insteadCond, baseDmg, altDmg)));
                    sortedByIdxDesc(ts, false).forEach(t -> ctx.damageTarget(t, resolveInsteadDamage(ctx, t, insteadCond, baseDmg, altDmg)));
                };
            }
        }

        // --- General EX Burst instead ("P. If [name] results from an EX Burst, A instead.") ---
        // Checked before the for-each and fixed-damage handlers so the condition isn't lost.
        // FOLLOWUP_DAMAGE_INSTEAD already covers fixed-damage EX burst cases above; this handles
        // the for-each damage and non-damage EX burst instead variants.
        Matcher exBurstM = FOLLOWUP_INSTEAD_EXBURST.matcher(followup);
        if (exBurstM.find()) {
            String primaryText = exBurstM.group("primary").trim();
            String altText     = exBurstM.group("alt").trim();
            BiConsumer<GameContext, List<ForwardTarget>> primaryAction =
                    parseTargetAction(primaryText, xValue);
            BiConsumer<GameContext, List<ForwardTarget>> altAction =
                    parseTargetAction(altText, xValue);
            if (primaryAction != null && altAction != null) {
                return ctx -> {
                    // Named after the branch is settled, not before it. Announcing both readings
                    // up front described the card doing something it was not about to do, and
                    // repeated what the "Resolving" line had already said a moment earlier.
                    boolean burst = ctx.isExBurst();
                    ctx.logChooseHeader(choosePrefix + " — " + (burst ? altText : primaryText));
                    List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                            opponentOnly, selfOnly, condition, element, zone, opponentZone,
                            costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                    (burst ? altAction : primaryAction).accept(ctx, ts);
                };
            }
        }

        // --- "If opponent has N cards or less…, [action1]. If no cards…, [action2] instead." ---
        // Two-tier hand condition — checked against the full followup before the dot-split.
        Matcher dblHandM = OPPONENT_HAND_DOUBLE_CONDITION_PATTERN.matcher(followup);
        if (dblHandM.matches()) {
            int    threshold  = Integer.parseInt(dblHandM.group("n"));
            String eff1Text   = dblHandM.group("effect1").trim();
            String eff2Text   = dblHandM.group("effect2").trim();
            BiConsumer<GameContext, List<ForwardTarget>> action1 = parseTargetAction(eff1Text, xValue);
            BiConsumer<GameContext, List<ForwardTarget>> action2 = parseTargetAction(eff2Text, xValue);
            if (action1 != null && action2 != null) {
                return ctx -> {
                    ctx.logChooseHeader(choosePrefix + " — hand condition (≤" + threshold + "/0)");
                    List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                            opponentOnly, selfOnly, condition, element, zone, opponentZone,
                            costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters,
                            jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                    int hs = ctx.opponentHandSize();
                    if (hs == 0)           action2.accept(ctx, ts);
                    else if (hs <= threshold) action1.accept(ctx, ts);
                };
            }
        }

        // --- "If opponent has [no|N cards or less] cards in hand, [action]" as single followup ---
        Matcher handM = OPPONENT_HAND_CONDITION_PATTERN.matcher(primaryFollowup);
        if (handM.matches()) {
            String nStr      = handM.group("n");
            int    threshold = nStr != null ? Integer.parseInt(nStr) : 0;
            String effText   = handM.group("effect").trim();
            BiConsumer<GameContext, List<ForwardTarget>> action = parseTargetAction(effText, xValue);
            if (action != null) {
                return ctx -> {
                    ctx.logChooseHeader(choosePrefix + " — hand condition");
                    List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                            opponentOnly, selfOnly, condition, element, zone, opponentZone,
                            costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters,
                            jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                    int hs = ctx.opponentHandSize();
                    boolean condMet = (nStr != null) ? hs <= threshold : hs == 0;
                    if (condMet) action.accept(ctx, ts);
                    if (secondary != null) secondary.accept(ctx);
                };
            }
        }

        // --- "Select 1 number and reveal the top card of your deck.
        //      If the revealed card is of the same cost as the selected number, break it." ---
        // "it" = the chosen Forward selected in the choose step, not the revealed card.
        // Checked against the full followup (not primaryFollowup) so the compound text isn't split.
        if (FOLLOWUP_SELECT_NUMBER_REVEAL_BREAK.matcher(followup).find()) {
            return ctx -> {
                ctx.logChooseHeader(choosePrefix + " — Select number + reveal, break if cost matches");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters,
                        jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                if (ts.isEmpty()) return;
                ForwardTarget target = ts.get(0);
                int n = ctx.selectNumber(0, 11, "Select a number:");
                ctx.logEntry("Selected number: " + n);
                ctx.revealTopDeckCard(java.util.List.of(
                        new RevealClause(card -> card.cost() == n, null,
                                rCtx -> rCtx.breakTarget(target))), false);
            };
        }

        // --- "Remove the top card of your deck from the game. Deal it N damage for each CP required to play the removed card." ---
        Matcher rfpTopDeckPerCpM = FOLLOWUP_RFP_TOP_DECK_AND_DAMAGE_PER_CP.matcher(followup);
        if (rfpTopDeckPerCpM.find()) {
            int baseDmg = Integer.parseInt(rfpTopDeckPerCpM.group("base"));
            return ctx -> {
                int cpCost = ctx.removeTopCardOfDeckFromGameAndGetCost();
                int damage = baseDmg * cpCost;
                ctx.logChooseHeader(choosePrefix + " — Deal " + damage + " damage (RFP top of deck, " + baseDmg + "×CP=" + cpCost + ")");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                sortedByIdxDesc(ts, true) .forEach(t -> ctx.damageTarget(t, damage));
                sortedByIdxDesc(ts, false).forEach(t -> ctx.damageTarget(t, damage));
            };
        }

        // --- "Remove the top card of your deck from the game. If the removed card is a Forward, break it. If not, deal it N damage." ---
        Matcher rfpTopDeckIfFwdM = FOLLOWUP_RFP_TOP_DECK_IF_FORWARD_BREAK_ELSE_DAMAGE.matcher(followup);
        if (rfpTopDeckIfFwdM.find()) {
            int dmg = Integer.parseInt(rfpTopDeckIfFwdM.group("dmg"));
            return ctx -> {
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                if (ctx.removeTopCardOfDeckFromGameIsForward()) {
                    ctx.logChooseHeader(choosePrefix + " — removed card is a Forward: break the chosen Forward");
                    sortedByIdxDesc(ts, true) .forEach(ctx::breakTarget);
                    sortedByIdxDesc(ts, false).forEach(ctx::breakTarget);
                } else {
                    ctx.logChooseHeader(choosePrefix + " — removed card is not a Forward: deal the chosen Forward " + dmg + " damage");
                    sortedByIdxDesc(ts, true) .forEach(t -> ctx.damageTarget(t, dmg));
                    sortedByIdxDesc(ts, false).forEach(t -> ctx.damageTarget(t, dmg));
                }
            };
        }

        // --- "Reveal the top N cards of your deck. Deal it M damage for each CP required to play the revealed cards. Add all the revealed cards to your hand." ---
        Matcher revealDmgPerCpM = FOLLOWUP_REVEAL_TOP_N_DAMAGE_PER_CP_ADD_ALL_TO_HAND.matcher(followup);
        if (revealDmgPerCpM.find()) {
            int revealCount = Integer.parseInt(revealDmgPerCpM.group("n"));
            int baseDmg     = Integer.parseInt(revealDmgPerCpM.group("base"));
            return ctx -> {
                int totalCp = ctx.revealTopNAndAddAllToHandGetTotalCP(revealCount);
                int damage  = baseDmg * totalCp;
                ctx.logChooseHeader(choosePrefix + " — Deal " + damage + " damage (reveal top " + revealCount + ", " + baseDmg + "×totalCP=" + totalCp + ")");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                sortedByIdxDesc(ts, true) .forEach(t -> ctx.damageTarget(t, damage));
                sortedByIdxDesc(ts, false).forEach(t -> ctx.damageTarget(t, damage));
            };
        }

        // --- "Reveal the top N cards of your deck. For each Job [Job] revealed this way, deal it M damage. Then, place the revealed cards at the bottom of your deck in any order." ---
        Matcher revealJobDmgM = FOLLOWUP_REVEAL_TOP_N_JOB_DEAL_DMG_PLACE_BOTTOM.matcher(followup);
        if (revealJobDmgM.find()) {
            int    revealCount  = Integer.parseInt(revealJobDmgM.group("n"));
            String revealJob    = revealJobDmgM.group("job").trim();
            int    dmgPerMatch  = Integer.parseInt(revealJobDmgM.group("dmg"));
            return ctx -> {
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters,
                        jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                int matchCount = ctx.revealTopNCountJobPlaceAllAtBottom(revealCount, revealJob);
                if (ts.isEmpty() || matchCount == 0) {
                    ctx.logChooseHeader(choosePrefix + " — 0 Job " + revealJob + " revealed, no damage");
                    return;
                }
                int totalDmg = matchCount * dmgPerMatch;
                ctx.logChooseHeader(choosePrefix + " — Deal " + totalDmg + " damage (" + matchCount + "×" + dmgPerMatch + " for Job " + revealJob + ")");
                sortedByIdxDesc(ts, true) .forEach(t -> ctx.damageTarget(t, totalDmg));
                sortedByIdxDesc(ts, false).forEach(t -> ctx.damageTarget(t, totalDmg));
            };
        }

        // --- "Reveal the top N cards of your deck. Add 1 card among them to your hand and return the other cards to the bottom of your deck in any order. If you added a Forward to your hand, deal the chosen Forward damage equal to the power of the added Forward." ---
        // Read off the whole followup: the amount is the added card's power, so the reveal and the
        // burn are one clause and the ". " split would leave the burn pointing at nothing. This is
        // the only parser that reads it — LOOK_TOP_DECK_ADD_TO_HAND_REST_BOTTOM claims the middle
        // sentence over in parse()'s own chain, but it declines any rider it does not recognise
        // rather than reveal and drop the burn, and 23-064R Golem reaches here first anyway
        // because its text opens with the choose.
        Matcher revealAddBurnM = FOLLOWUP_REVEAL_ADD_TO_HAND_IF_FORWARD_DAMAGE_ADDED_POWER.matcher(followup);
        if (revealAddBurnM.matches()) {
            int     revealCount = Integer.parseInt(revealAddBurnM.group("n"));
            boolean reveal      = isRevealWording(revealAddBurnM.group("verb"));
            return ctx -> {
                // Chosen first, as the text reads: the Forward is picked before anything is
                // revealed, so a card that leaves the field in between is still the one burnt.
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters,
                        jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                ctx.lookAtTopDeck(new LookConfig(revealCount,
                        LookConfig.LookAction.ADD_TO_HAND_REST_BOTTOM, null, null, reveal));
                CardData added = ctx.cardAddedToHandByLook();
                if (added == null || !added.isForward()) {
                    ctx.logChooseHeader(choosePrefix + " — no Forward added to hand, no damage");
                    return;
                }
                // The printed power of the card in hand. It is not on the field, so there is no
                // board state to read it from and nothing can be buffing it.
                int dmg = added.power();
                ctx.logChooseHeader(choosePrefix + " — Deal " + dmg + " damage (power of the added "
                        + added.name() + ")");
                sortedByIdxDesc(ts, true) .forEach(t -> ctx.damageTarget(t, dmg));
                sortedByIdxDesc(ts, false).forEach(t -> ctx.damageTarget(t, dmg));
            };
        }

        // --- "Deal it N damage for each [Name] Counter placed on [card]." (counter-scaled xValue) ---
        // Must be checked before FOLLOWUP_DAMAGE_FOR_EACH, which would match on the flat N and drop the for-each.
        Matcher dmgForEachCounterM = FOLLOWUP_DAMAGE_FOR_EACH_COUNTER.matcher(primaryFollowup);
        if (dmgForEachCounterM.find()) {
            int perUnit = Integer.parseInt(dmgForEachCounterM.group("perunit"));
            String counterName = dmgForEachCounterM.group("counterName").trim();
            return ctx -> {
                int damage = perUnit * xValue;
                ctx.logChooseHeader(choosePrefix + " — " + perUnit + " damage ×" + xValue + " " + counterName + " Counter(s) = " + damage + " damage");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters,
                        jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                sortedByIdxDesc(ts, true) .forEach(t -> ctx.damageTarget(t, damage));
                sortedByIdxDesc(ts, false).forEach(t -> ctx.damageTarget(t, damage));
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- "Deal it N damage for each [source]" followup ---
        Matcher forEachM = FOLLOWUP_DAMAGE_FOR_EACH.matcher(primaryFollowup);
        if (forEachM.find()) {
            int    baseDmg        = Integer.parseInt(forEachM.group("base"));
            String perStr         = forEachM.group("per");
            int    perDmg         = perStr != null ? Integer.parseInt(perStr) : 0;
            boolean subtract      = "minus".equalsIgnoreCase(forEachM.group("op"));
            // "for every N" counts groups of N, rounding down; "for each" is group size 1.
            int    groupSize      = forEachM.group("group") != null ? Integer.parseInt(forEachM.group("group")) : 1;
            boolean srcSelfDmg    = forEachM.group("selfdmg")  != null;
            String  srcJobBracket = forEachM.group("jobbname") != null ? forEachM.group("jobbname").trim() : null;
            String  srcJobWritten = forEachM.group("jobwname") != null ? forEachM.group("jobwname").trim() : null;
            String  srcJobWType   = forEachM.group("jobwtype") != null ? forEachM.group("jobwtype").trim() : null;
            String  srcJobUnion   = forEachM.group("jobuname")    != null ? forEachM.group("jobuname").trim() : null;
            String  srcJobUElem   = forEachM.group("jobuelement") != null ? forEachM.group("jobuelement").toLowerCase(java.util.Locale.ROOT) : null;
            String  srcJobUType   = forEachM.group("jobutype")    != null ? forEachM.group("jobutype")    : null;
            String  srcJobCJob    = forEachM.group("jobcname") != null ? forEachM.group("jobcname").trim() : null;
            String  srcJobCCard   = forEachM.group("jobccard") != null ? forEachM.group("jobccard").trim() : null;
            String  srcJobZJob    = forEachM.group("jobzname") != null ? forEachM.group("jobzname").trim() : null;
            String  srcJobZCard   = forEachM.group("jobzcard") != null ? forEachM.group("jobzcard").trim() : null;
            String  srcJobXJob    = forEachM.group("jobxname") != null ? forEachM.group("jobxname").trim() : null;
            String  srcJobXExcl   = forEachM.group("jobxexcl") != null ? forEachM.group("jobxexcl").trim() : null;
            String  srcJobRJob    = forEachM.group("jobrname") != null ? forEachM.group("jobrname").trim() : null;
            String  srcCharType   = forEachM.group("chartype");
            String  srcCategory   = srcCharType != null && forEachM.group("category") != null ? forEachM.group("category").trim() : null;
            String  srcElement    = srcCharType != null && forEachM.group("element")  != null ? forEachM.group("element").toLowerCase(java.util.Locale.ROOT) : null;
            int     srcCostFilter = srcCharType != null && forEachM.group("costfilter") != null ? Integer.parseInt(forEachM.group("costfilter")) : -1;
            String  srcBzName     = forEachM.group("bzname")   != null ? forEachM.group("bzname").trim()   : null;
            String  srcBzType     = forEachM.group("bztype")   != null ? forEachM.group("bztype").trim()   : null;
            boolean srcOppHand    = forEachM.group("opphand")   != null;
            boolean srcCrystal    = forEachM.group("crystal")   != null;
            boolean srcCpDiffElem = forEachM.group("cpDiffElem") != null;
            // if none of the above → xpaid
            boolean charFwd = srcCharType != null && (srcCharType.equalsIgnoreCase("forward")   || srcCharType.equalsIgnoreCase("forwards")   || srcCharType.equalsIgnoreCase("character") || srcCharType.equalsIgnoreCase("characters"));
            boolean charBkp = srcCharType != null && (srcCharType.equalsIgnoreCase("backup")    || srcCharType.equalsIgnoreCase("backups")    || srcCharType.equalsIgnoreCase("character") || srcCharType.equalsIgnoreCase("characters"));
            boolean charMon = srcCharType != null && (srcCharType.equalsIgnoreCase("monster")   || srcCharType.equalsIgnoreCase("monsters")   || srcCharType.equalsIgnoreCase("character") || srcCharType.equalsIgnoreCase("characters"));
            // Break Zone type counts use the printed type; "Characters" spans Forward/Backup/Monster.
            // "card" is not a type at all — it counts the whole zone, so it takes the unfiltered
            // count rather than asking for every type by name.
            boolean bzAll   = srcBzType != null && srcBzType.matches("(?i)Cards?");
            boolean bzChar  = srcBzType != null && srcBzType.matches("(?i)Characters?");
            boolean bzFwd   = srcBzType != null && (bzChar || srcBzType.matches("(?i)Forwards?"));
            boolean bzBkp   = srcBzType != null && (bzChar || srcBzType.matches("(?i)Backups?"));
            boolean bzMon   = srcBzType != null && (bzChar || srcBzType.matches("(?i)Monsters?"));
            boolean bzSmn   = srcBzType != null && srcBzType.matches("(?i)Summons?");
            // Element half of the union source: which card types its type noun spans.
            boolean unionFwd = srcJobUType != null && srcJobUType.matches("(?i)Forwards?|Characters?");
            boolean unionBkp = srcJobUType != null && srcJobUType.matches("(?i)Backups?|Characters?");
            boolean unionMon = srcJobUType != null && srcJobUType.matches("(?i)Monsters?|Characters?");
            String sourceLabel;
            if      (srcSelfDmg)           sourceLabel = "P1 damage";
            else if (srcJobBracket != null) sourceLabel = "[Job (" + srcJobBracket + ")] you control";
            else if (srcJobUnion   != null) sourceLabel = "Job " + srcJobUnion + " or " + forEachM.group("jobuelement") + " " + srcJobUType + " you control";
            else if (srcJobCJob    != null) sourceLabel = "Job " + srcJobCJob + " or Card Name " + srcJobCCard + " you control";
            else if (srcJobZJob    != null) sourceLabel = "Job " + srcJobZJob + " or Card Name " + srcJobZCard + " in BZ";
            else if (srcJobXJob    != null) sourceLabel = "Job " + srcJobXJob + " (excl. " + srcJobXExcl + ") you control";
            else if (srcJobRJob    != null) sourceLabel = "Job " + srcJobRJob + " in BZ or RFP";
            else if (srcJobWritten != null) sourceLabel = "Job " + srcJobWritten + (srcJobWType != null ? " " + srcJobWType : "") + " you control";
            else if (srcCharType   != null) sourceLabel = (srcCategory != null ? "Category " + srcCategory + " " : "") + (srcElement != null ? srcElement + " " : "") + srcCharType + (srcCostFilter != -1 ? " of cost " + srcCostFilter : "") + " you control";
            else if (srcBzName     != null) sourceLabel = "Card Name " + srcBzName + " in BZ";
            else if (srcBzType     != null) sourceLabel = srcBzType + " in BZ";
            else if (srcOppHand)           sourceLabel = "opponent hand";
            else if (srcCrystal)           sourceLabel = "《C》 you have";
            else if (srcCpDiffElem)        sourceLabel = "CP of a different Element paid to cast";
            else                            sourceLabel = "X CP paid";
            String op = subtract ? " - " : " + ";
            String unitLabel = groupSize > 1 ? "every " + groupSize + " " + sourceLabel : sourceLabel;
            String logLabel = perDmg > 0
                    ? baseDmg + op + perDmg + "×[" + unitLabel + "]"
                    : baseDmg + "×[" + unitLabel + "]";
            return ctx -> {
                int n;
                if      (srcSelfDmg)           n = ctx.p1DamageCount();
                else if (srcJobBracket != null) n = ctx.countSelfFieldCards(true, true, true, srcJobBracket, null);
                // The three union sources all count distinct cards, not matches: one card can
                // satisfy both halves (a Fire Warrior of Light; a card named Dragoon that was
                // granted the Job Dragoon), so each asks for the overlap and subtracts it rather
                // than summing the two counts. Every term goes through the same filter chain,
                // which is what makes the subtraction exact.
                else if (srcJobUnion   != null) {
                    int jobN  = ctx.countSelfFieldCards(true, true, true, srcJobUnion, null);
                    int elemN = ctx.countSelfFieldCards(unionFwd, unionBkp, unionMon, null, null, null, srcJobUElem, -1);
                    int bothN = ctx.countSelfFieldCards(unionFwd, unionBkp, unionMon, srcJobUnion, null, null, srcJobUElem, -1);
                    n = jobN + elemN - bothN;
                }
                else if (srcJobCJob    != null) {
                    int jobN  = ctx.countSelfFieldCards(true, true, true, srcJobCJob, null);
                    int nameN = ctx.countSelfFieldCards(true, true, true, null, srcJobCCard);
                    int bothN = ctx.countSelfFieldCards(true, true, true, srcJobCJob, srcJobCCard);
                    n = jobN + nameN - bothN;
                }
                else if (srcJobZJob    != null) {
                    int jobN  = ctx.countSelfBreakZoneCards(null, srcJobZJob);
                    int nameN = ctx.countSelfBreakZoneCards(srcJobZCard, null);
                    int bothN = ctx.countSelfBreakZoneCards(srcJobZCard, srcJobZJob);
                    n = jobN + nameN - bothN;
                }
                // "other than <name>" is a subtraction of the same shape: drop the cards that
                // carry both the job and the excluded name. It excludes every copy of that name,
                // not just the ability's source, which is what the printed text says.
                else if (srcJobXJob    != null)
                    n = ctx.countSelfFieldCards(true, true, true, srcJobXJob, null)
                      - ctx.countSelfFieldCards(true, true, true, srcJobXJob, srcJobXExcl);
                // The Break Zone and the removed-from-game zone are disjoint — a card is in one or
                // the other — so this union needs no overlap term, unlike the three above.
                else if (srcJobRJob    != null) n = ctx.countSelfBreakZoneAndRfgCards(null, srcJobRJob);
                else if (srcJobWritten != null) {
                    boolean jwFwd = srcJobWType == null || srcJobWType.matches("(?i)Forwards?");
                    boolean jwBkp = srcJobWType == null || srcJobWType.matches("(?i)Backups?");
                    boolean jwMon = srcJobWType == null || srcJobWType.matches("(?i)Monsters?");
                    n = ctx.countSelfFieldCards(jwFwd, jwBkp, jwMon, srcJobWritten, null);
                }
                else if (srcCharType   != null) n = ctx.countSelfFieldCards(charFwd, charBkp, charMon, null, null, srcCategory, srcElement, srcCostFilter);
                else if (srcBzName     != null) n = ctx.countSelfBreakZoneCards(srcBzName, null);
                else if (srcBzType     != null) n = bzAll ? ctx.countSelfBreakZoneCards(null, null)
                                                          : ctx.countSelfBreakZoneCardsByType(bzFwd, bzBkp, bzMon, bzSmn);
                else if (srcOppHand)           n = ctx.opponentHandSize();
                else if (srcCrystal)           n = ctx.crystalCount();
                else if (srcCpDiffElem)        n = ctx.castPaymentDistinctElements();
                else                            n = xValue;
                int units = n / groupSize;
                int damage = perDmg > 0
                        ? (subtract ? Math.max(0, baseDmg - perDmg * units) : baseDmg + perDmg * units)
                        : baseDmg * units;
                String countNote = groupSize > 1 ? ", n=" + n + "→" + units : ", n=" + n;
                ctx.logChooseHeader(choosePrefix + " — Deal " + damage + " damage (" + logLabel + countNote + ")");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                sortedByIdxDesc(ts, true) .forEach(t -> ctx.damageTarget(t, damage));
                sortedByIdxDesc(ts, false).forEach(t -> ctx.damageTarget(t, damage));
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Dull + Damage followup ---
        Matcher dullDmgM = FOLLOWUP_DULL_AND_DAMAGE.matcher(primaryFollowup);
        if (dullDmgM.find()) {
            int damage = Integer.parseInt(dullDmgM.group("amount"));
            return ctx -> {
                ctx.logChooseHeader(choosePrefix + " — Dull & Deal " + damage + " damage");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                sortedByIdxDesc(ts, true) .forEach(t -> { ctx.dullTarget(t); ctx.damageTarget(t, damage); });
                sortedByIdxDesc(ts, false).forEach(t -> { ctx.dullTarget(t); ctx.damageTarget(t, damage); });
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- "If your opponent controls N or more Forwards, deal it X damage" followup ---
        Matcher oppFwdCondM = FOLLOWUP_IF_OPPONENT_CONTROLS_FORWARDS_DAMAGE.matcher(primaryFollowup);
        if (oppFwdCondM.matches()) {
            int minCount = Integer.parseInt(oppFwdCondM.group("count"));
            int damage   = Integer.parseInt(oppFwdCondM.group("amount"));
            return ctx -> {
                ctx.logChooseHeader(choosePrefix + " — If opponent controls ≥" + minCount + " Forwards, deal " + damage + " damage");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters,
                        jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                if (ctx.opponentForwardCount() >= minCount) {
                    sortedByIdxDesc(ts, true) .forEach(t -> ctx.damageTarget(t, damage));
                    sortedByIdxDesc(ts, false).forEach(t -> ctx.damageTarget(t, damage));
                }
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- "If you control N or more [Element] [Type], deal it X damage" followup ---
        Matcher selfFieldCondM = FOLLOWUP_IF_SELF_CONTROLS_N_ELEMENT_TYPE_DAMAGE.matcher(primaryFollowup);
        if (selfFieldCondM.matches()) {
            int    minCount    = Integer.parseInt(selfFieldCondM.group("count"));
            int    damage      = Integer.parseInt(selfFieldCondM.group("amount"));
            String condElement  = selfFieldCondM.group("element");  // null if absent
            String condTypeRaw  = selfFieldCondM.group("type");
            String condType     = condTypeRaw.toLowerCase();
            boolean cFwd = condType.startsWith("forward") || condType.startsWith("character");
            boolean cBkp = condType.startsWith("backup")  || condType.startsWith("character");
            boolean cMon = condType.startsWith("monster")  || condType.startsWith("character");
            return ctx -> {
                String label = "If you control ≥" + minCount + " "
                        + (condElement != null ? condElement + " " : "")
                        + condTypeRaw + ", deal " + damage + " damage";
                ctx.logChooseHeader(choosePrefix + " — " + label);
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters,
                        jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                if (ctx.selfFieldCount(condElement, cFwd, cBkp, cMon) >= minCount) {
                    sortedByIdxDesc(ts, true) .forEach(t -> ctx.damageTarget(t, damage));
                    sortedByIdxDesc(ts, false).forEach(t -> ctx.damageTarget(t, damage));
                }
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- "If you control N or more [Element] [Type], <action> it/them" followup ---
        // Must precede the plain action handlers below: those scan for their verb with find(), so
        // they would match straight through this condition and apply the action unconditionally.
        Matcher selfFieldActionM = FOLLOWUP_IF_SELF_CONTROLS_N_ELEMENT_TYPE_ACTION.matcher(primaryFollowup);
        if (selfFieldActionM.matches()) {
            String actionText = selfFieldActionM.group("action").trim();
            BiConsumer<GameContext, List<ForwardTarget>> condAction =
                    parseTargetAction(actionText, xValue);
            if (condAction != null) {
                int    minCount    = Integer.parseInt(selfFieldActionM.group("count"));
                String condElement = selfFieldActionM.group("element");  // null if absent
                String condTypeRaw = selfFieldActionM.group("type");
                String condType    = condTypeRaw.toLowerCase();
                boolean cFwd = condType.startsWith("forward") || condType.startsWith("character");
                boolean cBkp = condType.startsWith("backup")  || condType.startsWith("character");
                boolean cMon = condType.startsWith("monster") || condType.startsWith("character");
                return ctx -> {
                    String label = "If you control ≥" + minCount + " "
                            + (condElement != null ? condElement + " " : "")
                            + condTypeRaw + ", " + actionText;
                    ctx.logChooseHeader(choosePrefix + " — " + label);
                    List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                            opponentOnly, selfOnly, condition, element, zone, opponentZone,
                            costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters,
                            jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                    if (ctx.selfFieldCount(condElement, cFwd, cBkp, cMon) >= minCount)
                        condAction.accept(ctx, ts);
                    else
                        ctx.logEntry("Condition not met — " + actionText + " skipped");
                    if (secondary != null) secondary.accept(ctx);
                };
            }
        }

        // --- Split effect: [action A] the first [type] … and [action B] the other ---
        Matcher foM = FOLLOWUP_FIRST_AND_OTHER.matcher(primaryFollowup);
        if (foM.find()) {
            final String firstpfx    = foM.group("firstpfx").trim();
            final String firstsfx    = foM.group("firstsfx").trim().toLowerCase();
            final String othereffect = foM.group("othereffect").trim().toLowerCase();
            Matcher dmgAmt = Pattern.compile("(?i)deal\\s+(?<n>\\d+)\\s+damage").matcher(firstpfx);
            final int firstDamage = dmgAmt.find() ? Integer.parseInt(dmgAmt.group("n")) : 0;
            return ctx -> {
                ctx.logChooseHeader(choosePrefix + " — " + firstpfx + " first; " + othereffect + " other");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters,
                        jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                if (!ts.isEmpty()) {
                    ForwardTarget first = ts.get(0);
                    if      (firstsfx.contains("from the game"))  ctx.removeTargetFromGame(first);
                    else if (firstsfx.contains("to its owner")) {
                        if (first.zone() == ForwardTarget.CardZone.FORWARD) {
                            if (first.isP1()) ctx.returnP1ForwardToHand(first.idx());
                            else              ctx.returnP2ForwardToHand(first.idx());
                        }
                    }
                    else if (firstDamage > 0)                          ctx.damageTarget(first, firstDamage);
                    else if (firstpfx.equalsIgnoreCase("dull"))        ctx.dullTarget(first);
                    else if (firstpfx.equalsIgnoreCase("break"))       ctx.breakTarget(first);
                    else if (firstpfx.equalsIgnoreCase("freeze"))      ctx.freezeTarget(first);
                    else if (firstpfx.equalsIgnoreCase("activate"))    ctx.activateTarget(first);
                }
                if (ts.size() > 1) {
                    ForwardTarget other = ts.get(1);
                    if      (othereffect.contains("freeze") && othereffect.contains("dull")) ctx.dullAndFreezeTarget(other);
                    else if (othereffect.equals("activate"))                                  ctx.activateTarget(other);
                    else if (othereffect.equals("break"))                                     ctx.breakTarget(other);
                    else if (othereffect.equals("dull"))                                      ctx.dullTarget(other);
                    else if (othereffect.equals("freeze"))                                    ctx.freezeTarget(other);
                    else if (othereffect.contains("from the game"))                           ctx.removeTargetFromGame(other);
                    else if (othereffect.contains("to its owner")) {
                        if (other.zone() == ForwardTarget.CardZone.FORWARD) {
                            if (other.isP1()) ctx.returnP1ForwardToHand(other.idx());
                            else              ctx.returnP2ForwardToHand(other.idx());
                        }
                    }
                }
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Damage to the chosen Forward plus a splash over the opponent's other Forwards ---
        // Must precede the plain damage branch below, which find()s the first clause and drops the
        // splash; see the note on the pattern.
        Matcher splashM = FOLLOWUP_DAMAGE_AND_SPLASH_OTHER_OPP_FORWARDS.matcher(strippedPrimaryFollowup.trim());
        if (splashM.matches()) {
            int damage = Integer.parseInt(splashM.group("amount"));
            int splash = Integer.parseInt(splashM.group("splash"));
            return ctx -> {
                ctx.logChooseHeader(choosePrefix + " — Deal " + damage + " damage, and " + splash
                        + " damage to the opponent's other Forwards");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters,
                        jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                if (ts.isEmpty()) { if (secondary != null) secondary.accept(ctx); return; }

                // "The other Forwards" is settled before any damage is dealt, and held as cards
                // rather than indices: the blow below can break the chosen Forward, which renumbers
                // the row every later index would be read against.
                boolean oppIsP1 = !ctx.isP1();
                List<CardData> chosen = new ArrayList<>();
                for (ForwardTarget t : ts)
                    if (t.zone() == ForwardTarget.CardZone.FORWARD)
                        chosen.add(t.isP1() ? ctx.p1Forward(t.idx()) : ctx.p2Forward(t.idx()));
                List<CardData> others = new ArrayList<>();
                int oppCount = oppIsP1 ? ctx.p1ForwardCount() : ctx.p2ForwardCount();
                for (int i = 0; i < oppCount; i++) {
                    CardData c = oppIsP1 ? ctx.p1Forward(i) : ctx.p2Forward(i);
                    boolean isChosen = false;
                    for (CardData ch : chosen) if (ch == c) { isChosen = true; break; }
                    if (!isChosen) others.add(c);
                }

                sortedByIdxDesc(ts, true) .forEach(t -> ctx.damageTarget(t, damage));
                sortedByIdxDesc(ts, false).forEach(t -> ctx.damageTarget(t, damage));

                for (CardData other : others) {
                    int idx = forwardIndexByIdentity(ctx, oppIsP1, other);
                    if (idx < 0) continue;   // broken by the blow above, or gone some other way
                    if (oppIsP1) ctx.damageP1Forward(idx, splash);
                    else         ctx.damageP2Forward(idx, splash);
                }
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Damage + controller damage followup ("Deal it N damage and M point(s) of damage to that Forward's controller") ---
        Matcher ctrlDmgM = FOLLOWUP_DAMAGE_AND_CONTROLLER_DAMAGE.matcher(strippedPrimaryFollowup);
        if (ctrlDmgM.find()) {
            int damage        = Integer.parseInt(ctrlDmgM.group("amount"));
            int controllerDmg = Integer.parseInt(ctrlDmgM.group("controllerdmg"));
            return ctx -> {
                ctx.logChooseHeader(choosePrefix + " — Deal " + damage + " damage + " + controllerDmg + " to controller");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                sortedByIdxDesc(ts, true) .forEach(t -> ctx.damageTarget(t, damage));
                sortedByIdxDesc(ts, false).forEach(t -> ctx.damageTarget(t, damage));
                for (ForwardTarget t : ts) {
                    if (t.isP1()) ctx.dealDamageToSelf(controllerDmg);
                    else          ctx.dealDamageToOpponent(controllerDmg);
                }
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Damage followup (fixed amount) ---
        Matcher dmgM = FOLLOWUP_DAMAGE.matcher(strippedPrimaryFollowup);
        if (dmgM.find()) {
            int damage = Integer.parseInt(dmgM.group("amount"));
            String alsoCard = dmgM.group("also") != null ? dmgM.group("also").trim() : null;
            return ctx -> {
                String unredSuffix = unreduced ? " (cannot be reduced)" : "";
                ctx.logEntry(alsoCard != null
                        ? choosePrefix + " — Deal " + damage + " damage (and to " + alsoCard + ")" + unredSuffix
                        : choosePrefix + " — Deal " + damage + " damage" + unredSuffix);
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                Consumer<GameContext> doDamage = ctx2 -> {
                    if (unreduced) {
                        sortedByIdxDesc(ts, true) .forEach(t -> ctx2.damageTargetUnreduced(t, damage));
                        sortedByIdxDesc(ts, false).forEach(t -> ctx2.damageTargetUnreduced(t, damage));
                    } else {
                        sortedByIdxDesc(ts, true) .forEach(t -> ctx2.damageTarget(t, damage));
                        sortedByIdxDesc(ts, false).forEach(t -> ctx2.damageTarget(t, damage));
                    }
                    // "Deal it and <Self> N damage" is one effect: the named source only burns
                    // alongside a Forward that was actually chosen. With an empty selection —
                    // no eligible target, or an "up to" text taken at zero — nothing is dealt.
                    if (alsoCard != null && !ts.isEmpty()) ctx2.damageFieldForwardByName(alsoCard, damage);
                    if (secondary != null) secondary.accept(ctx2);
                };
                if (followupIsOptional && !ts.isEmpty()) ctx.playerMayDoEffect("Deal it " + damage + " damage?", doDamage);
                else if (!followupIsOptional) doDamage.accept(ctx);
            };
        }

        // --- Mutual power-as-damage between source and chosen Forward ---
        if (source != null) {
            Matcher mutM = FOLLOWUP_MUTUAL_POWER_DAMAGE.matcher(primaryFollowup);
            if (mutM.find() && mutM.group("srcname").trim().equalsIgnoreCase(source.name())) {
                String srcName = source.name();
                return ctx -> {
                    List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                            opponentOnly, selfOnly, condition, element, zone, opponentZone,
                            costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                    if (ts.isEmpty()) { if (secondary != null) secondary.accept(ctx); return; }
                    int srcPower = Math.max(0, ctx.fieldForwardPowerByName(srcName));
                    for (ForwardTarget t : ts) {
                        int tgtPower = Math.max(0, ctx.effectiveTargetPower(t));
                        ctx.logChooseHeader(choosePrefix + " — Mutual power damage: " + srcName + " (" + srcPower
                                + ") ↔ chosen Forward (" + tgtPower + ")");
                        ctx.damageTarget(t, srcPower);
                        ctx.damageFieldForwardByName(srcName, tgtPower);
                    }
                    if (secondary != null) secondary.accept(ctx);
                };
            }
        }

        // --- Damage followup (computed amount) ---
        Matcher exprM = FOLLOWUP_DAMAGE_EXPR.matcher(primaryFollowup);
        if (exprM.find()) {
            if (exprM.group("highest") != null) {
                return ctx -> {
                    int damage = ctx.highestP1ForwardPower();
                    ctx.logChooseHeader(choosePrefix + " — Deal " + damage + " damage (highest Forward power)");
                    List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                            opponentOnly, selfOnly, condition, element, zone, opponentZone,
                            costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                    sortedByIdxDesc(ts, true) .forEach(t -> ctx.damageTarget(t, damage));
                    sortedByIdxDesc(ts, false).forEach(t -> ctx.damageTarget(t, damage));
                    if (secondary != null) secondary.accept(ctx);
                };
            } else if (exprM.group("halfcard") != null) {
                String  cardName = exprM.group("halfcard").trim();
                boolean roundUp  = "up".equalsIgnoreCase(exprM.group("halfrounding"));
                return ctx -> {
                    int raw    = Math.max(0, ctx.fieldForwardPowerByName(cardName));
                    int damage = roundUp ? halfPowerDamage(raw) : (raw / 2 / 1000) * 1000;
                    String dir = roundUp ? "up" : "down";
                    ctx.logChooseHeader(choosePrefix + " — Deal " + damage + " damage (half of " + cardName + "'s power, round " + dir + ")");
                    List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                            opponentOnly, selfOnly, condition, element, zone, opponentZone,
                            costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                    sortedByIdxDesc(ts, true) .forEach(t -> ctx.damageTarget(t, damage));
                    sortedByIdxDesc(ts, false).forEach(t -> ctx.damageTarget(t, damage));
                    if (secondary != null) secondary.accept(ctx);
                };
            } else if (exprM.group("halfitspower") != null) {
                boolean roundUp = "up".equalsIgnoreCase(exprM.group("halfitsrounding"));
                String dir = roundUp ? "up" : "down";
                return ctx -> {
                    ctx.logChooseHeader(choosePrefix + " — Deal damage equal to half of its power (round " + dir + ")");
                    List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                            opponentOnly, selfOnly, condition, element, zone, opponentZone,
                            costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                    sortedByIdxDesc(ts, true) .forEach(t -> {
                        int raw = Math.max(0, ctx.effectiveTargetPower(t));
                        ctx.damageTarget(t, roundUp ? halfPowerDamage(raw) : (raw / 2 / 1000) * 1000);
                    });
                    sortedByIdxDesc(ts, false).forEach(t -> {
                        int raw = Math.max(0, ctx.effectiveTargetPower(t));
                        ctx.damageTarget(t, roundUp ? halfPowerDamage(raw) : (raw / 2 / 1000) * 1000);
                    });
                    if (secondary != null) secondary.accept(ctx);
                };
            } else if (exprM.group("itspower") != null) {
                int subtract = exprM.group("minus") != null ? Integer.parseInt(exprM.group("minus")) : 0;
                String logSuffix = subtract > 0 ? " — Deal damage equal to its power minus " + subtract
                                                 : " — Deal damage equal to its power";
                return ctx -> {
                    ctx.logChooseHeader(choosePrefix + logSuffix);
                    List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                            opponentOnly, selfOnly, condition, element, zone, opponentZone,
                            costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                    sortedByIdxDesc(ts, true) .forEach(t -> ctx.damageTarget(t, Math.max(0, ctx.effectiveTargetPower(t) - subtract)));
                    sortedByIdxDesc(ts, false).forEach(t -> ctx.damageTarget(t, Math.max(0, ctx.effectiveTargetPower(t) - subtract)));
                    if (secondary != null) secondary.accept(ctx);
                };
            } else if (exprM.group("dullforward") != null) {
                return ctx -> {
                    int damage = Math.max(0, ctx.dullForwardCostPower());
                    ctx.logChooseHeader(choosePrefix + " — Deal " + damage + " damage (dull Forward cost power)");
                    List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                            opponentOnly, selfOnly, condition, element, zone, opponentZone,
                            costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                    sortedByIdxDesc(ts, true) .forEach(t -> ctx.damageTarget(t, damage));
                    sortedByIdxDesc(ts, false).forEach(t -> ctx.damageTarget(t, damage));
                    if (secondary != null) secondary.accept(ctx);
                };
            } else if (exprM.group("discardedfwd") != null) {
                return ctx -> {
                    int damage = Math.max(0, ctx.lastDiscardedForwardPower());
                    ctx.logChooseHeader(choosePrefix + " — Deal " + damage + " damage (discarded Forward's power)");
                    List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                            opponentOnly, selfOnly, condition, element, zone, opponentZone,
                            costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                    sortedByIdxDesc(ts, true) .forEach(t -> ctx.damageTarget(t, damage));
                    sortedByIdxDesc(ts, false).forEach(t -> ctx.damageTarget(t, damage));
                    if (secondary != null) secondary.accept(ctx);
                };
            } else if (exprM.group("bzcostfwd") != null) {
                return ctx -> {
                    int damage = Math.max(0, ctx.bzCostForwardPower());
                    ctx.logChooseHeader(choosePrefix + " — Deal " + damage + " damage (BZ-cost Forward's power)");
                    List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                            opponentOnly, selfOnly, condition, element, zone, opponentZone,
                            costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                    sortedByIdxDesc(ts, true) .forEach(t -> ctx.damageTarget(t, damage));
                    sortedByIdxDesc(ts, false).forEach(t -> ctx.damageTarget(t, damage));
                    if (secondary != null) secondary.accept(ctx);
                };
            } else if (exprM.group("card") != null) {
                String cardName = exprM.group("card").trim();
                return ctx -> {
                    int damage = Math.max(0, ctx.fieldForwardPowerByName(cardName));
                    ctx.logChooseHeader(choosePrefix + " — Deal " + damage + " damage (" + cardName + "'s power)");
                    List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                            opponentOnly, selfOnly, condition, element, zone, opponentZone,
                            costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                    sortedByIdxDesc(ts, true) .forEach(t -> ctx.damageTarget(t, damage));
                    sortedByIdxDesc(ts, false).forEach(t -> ctx.damageTarget(t, damage));
                    if (secondary != null) secondary.accept(ctx);
                };
            }
        }


        // =====================================================================================
        // Control changes and cannot-be-chosen protections
        // =====================================================================================
        // --- Activate + Gain control (EOT) followup (must precede plain Activate) ---
        if (FOLLOWUP_ACTIVATE_AND_GAIN_CONTROL_EOT.matcher(primaryFollowup).find()) {
            return ctx -> {
                ctx.logChooseHeader(choosePrefix + " — Activate & Gain control until EOT");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                sortedByIdxDesc(ts, true) .forEach(t -> ctx.activateTarget(t));
                sortedByIdxDesc(ts, false).forEach(t -> ctx.activateTarget(t));
                ts.forEach(t -> ctx.gainControlOfForward(t, "endOfTurn", true));
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Gain control while named card on field ---
        Matcher gcWhileM = FOLLOWUP_GAIN_CONTROL_WHILE_CARD.matcher(primaryFollowup);
        if (gcWhileM.find()) {
            String condCard = gcWhileM.group("condCard").trim();
            return ctx -> {
                ctx.logChooseHeader(choosePrefix + " — Gain control while " + condCard + " is on field");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                ts.forEach(t -> ctx.gainControlOfForward(t, "whileCardOnField:" + condCard, false));
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Gain control until EOT ---
        if (FOLLOWUP_GAIN_CONTROL_EOT.matcher(primaryFollowup).find()) {
            return ctx -> {
                ctx.logChooseHeader(choosePrefix + " — Gain control until EOT");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                ts.forEach(t -> ctx.gainControlOfForward(t, "endOfTurn", false));
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Gain control (permanent) ---
        if (FOLLOWUP_GAIN_CONTROL.matcher(primaryFollowup).find()) {
            return ctx -> {
                ctx.logChooseHeader(choosePrefix + " — Gain control");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                ts.forEach(t -> ctx.gainControlOfForward(t, "permanent", false));
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Cannot-be-chosen followups (gains form, then both, Summons, abilities) ---
        {   // scoped block so scope-parsing locals don't leak
            String fp = primaryFollowup;
            Matcher gcM = FOLLOWUP_GAINS_CANNOT_BE_CHOSEN.matcher(fp);
            if (!gcM.find()) gcM = null;
            boolean chosenBoth      = gcM != null || FOLLOWUP_CANNOT_BE_CHOSEN_BOTH.matcher(fp).find();
            boolean chosenSummons   = chosenBoth  || (gcM == null && FOLLOWUP_CANNOT_BE_CHOSEN_SUMMONS.matcher(fp).find());
            boolean chosenAbilities = chosenBoth  || (gcM == null && FOLLOWUP_CANNOT_BE_CHOSEN_ABILITIES.matcher(fp).find());
            if (chosenSummons || chosenAbilities) {
                final boolean bs = chosenSummons, ba = chosenAbilities;
                return ctx -> {
                    ctx.logChooseHeader(choosePrefix + " — Cannot be chosen by opponent's"
                            + (bs && ba ? " Summons or abilities" : bs ? " Summons" : " abilities"));
                    List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                            opponentOnly, selfOnly, condition, element, zone, opponentZone,
                            costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                    ts.forEach(t -> ctx.shieldCannotBeChosen(t, bs, ba));
                    if (secondary != null) secondary.accept(ctx);
                };
            }
        }

        // --- Cannot-be-returned-to-hand followup ("During this turn, it cannot be returned…") ---
        if (FOLLOWUP_CANNOT_BE_RETURNED_TO_HAND.matcher(primaryFollowup).find()) {
            return ctx -> {
                ctx.logChooseHeader(choosePrefix + " — Cannot be returned to owner's hand by opponent this turn");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                ts.forEach(t -> ctx.boostTarget(t, 0,
                        EnumSet.of(CardData.Trait.CANNOT_BE_RETURNED_TO_HAND_BY_OPP)));
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Cannot-become-dull followup ("It cannot become dull by your opponent's … this turn") ---
        if (FOLLOWUP_CANNOT_BECOME_DULL_BY_OPP.matcher(primaryFollowup).find()) {
            return ctx -> {
                ctx.logChooseHeader(choosePrefix + " — Cannot become dull by opponent's Summons or abilities this turn");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                ts.forEach(t -> ctx.boostTarget(t, 0,
                        EnumSet.of(CardData.Trait.CANNOT_BE_DULLED_BY_OPP)));
                if (secondary != null) secondary.accept(ctx);
            };
        }


        // =====================================================================================
        // Activate, dull, freeze and element change
        // =====================================================================================
        // --- Activate + Negate damage followup (must precede plain Activate to avoid partial match) ---
        if (FOLLOWUP_ACTIVATE_AND_NEGATE_DAMAGE.matcher(primaryFollowup).find()) {
            return ctx -> {
                ctx.logChooseHeader(choosePrefix + " — Activate & Negate damage");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                sortedByIdxDesc(ts, true) .forEach(t -> ctx.activateTarget(t));
                sortedByIdxDesc(ts, false).forEach(t -> ctx.activateTarget(t));
                ts.forEach(ctx::negateAllDamage);
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Negate all damage followup ---
        if (FOLLOWUP_NEGATE_DAMAGE.matcher(primaryFollowup).find()) {
            return ctx -> {
                ctx.logChooseHeader(choosePrefix + " — Negate damage");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                ts.forEach(ctx::negateAllDamage);
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Dull-or-Activate toggle followup (must precede FOLLOWUP_ACTIVATE/DULL since it contains both) ---
        if (FOLLOWUP_DULL_OR_ACTIVATE.matcher(primaryFollowup).find()) {
            return ctx -> {
                ctx.logChooseHeader(choosePrefix + " — Dull or Activate (toggle)");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                sortedByIdxDesc(ts, true) .forEach(t -> ctx.toggleTargetDullActivate(t));
                sortedByIdxDesc(ts, false).forEach(t -> ctx.toggleTargetDullActivate(t));
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Activate followup ---
        if (FOLLOWUP_ACTIVATE.matcher(primaryFollowup).find()) {
            // Detect "It gains +N power [traits] until end of turn" secondary and apply inline.
            final int activateBoost;
            final EnumSet<CardData.Trait> activateTraits;
            final Consumer<GameContext> activateSecondary;
            {
                Matcher bm = secondaryText != null ? FOLLOWUP_POWER_BOOST.matcher(secondaryText) : null;
                if (bm == null) { bm = secondaryText != null ? FOLLOWUP_POWER_BOOST_UNTIL.matcher(secondaryText) : null; }
                if (bm != null && bm.find()) {
                    activateBoost      = Integer.parseInt(bm.group(1));
                    activateTraits     = parseTraits(bm.group(2));
                    activateSecondary  = null;
                } else {
                    activateBoost      = 0;
                    activateTraits     = EnumSet.noneOf(CardData.Trait.class);
                    activateSecondary  = secondary;
                }
            }
            String activateLogSuffix = activateBoost > 0 ? boostLogSuffix(activateBoost, activateTraits) : "";
            return ctx -> {
                ctx.logChooseHeader(choosePrefix + " — Activate" + activateLogSuffix);
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                sortedByIdxDesc(ts, true) .forEach(t -> ctx.activateTarget(t));
                sortedByIdxDesc(ts, false).forEach(t -> ctx.activateTarget(t));
                if (activateBoost > 0) {
                    sortedByIdxDesc(ts, true) .forEach(t -> ctx.boostTarget(t, activateBoost, activateTraits));
                    sortedByIdxDesc(ts, false).forEach(t -> ctx.boostTarget(t, activateBoost, activateTraits));
                } else if (activateSecondary != null) {
                    activateSecondary.accept(ctx);
                }
            };
        }

        // --- "Its Element becomes X." followup (12-021R Necron) ---
        // The change is permanent, so nothing is registered for the end-of-turn sweep.
        //
        // Read off the whole followup rather than the primary half, as the reveal/cost followups
        // above are: the ". " split puts Necron's "(This effect does not end at the end of the
        // turn.)" in the secondary, where it parses as nothing and shows up as a dangling "+ ?".
        // The pattern is anchored and admits only that reminder after the sentence, so a match
        // here proves there is no real secondary to run. Anything else still falls to the split.
        Matcher elemBecomesM = FOLLOWUP_ELEMENT_BECOMES.matcher(followup);
        if (!elemBecomesM.matches()) elemBecomesM = FOLLOWUP_ELEMENT_BECOMES.matcher(primaryFollowup);
        if (elemBecomesM.matches()) {
            String newElement = elemBecomesM.group("element");
            return ctx -> {
                ctx.logChooseHeader(choosePrefix + " — Element becomes " + newElement);
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                ts.forEach(t -> ctx.setTargetElement(t, newElement));
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Turn-long outgoing-damage replacement, standalone (17-027R Shiva, 24-056C Cu Sith) ---
        // The same act the dull branch below carries as its second sentence, printed on its own.
        Matcher outZeroM = FOLLOWUP_OUTGOING_DMG_ZERO_THIS_TURN.matcher(primaryFollowup.trim());
        if (outZeroM.matches()) {
            final boolean nonBattleOnly = outZeroM.group("nonbattle") != null;
            return ctx -> {
                ctx.logChooseHeader(choosePrefix + " — "
                        + (nonBattleOnly ? "non-battle damage it deals to a Forward"
                                         : "damage it deals")
                        + " becomes 0 for the rest of the turn");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters,
                        jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                for (ForwardTarget t : ts) {
                    if (nonBattleOnly) ctx.zeroOutgoingAbilityDamageToForwardsThisTurn(t);
                    else               ctx.zeroAllOutgoingDamageThisTurn(t);
                }
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Return to hand, then bar every copy of it until the end of the next turn (19-101R Leviathan) ---
        // Read off the whole followup: "it" in the second sentence names the card the first has
        // already put in hand, so split, the ban reaches the secondary parser with no referent and
        // the bounce runs alone. The name is read before the return for the same reason it is read
        // at all — after it, the card is in a hand and no longer at the target's coordinates.
        if (FOLLOWUP_RETURN_TO_HAND_THEN_BAN_COPIES.matcher(followup.trim()).matches()) {
            return ctx -> {
                ctx.logChooseHeader(choosePrefix
                        + " — Return to owner's hand; no copies castable until the end of the next turn");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters,
                        jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                List<String> banned = new ArrayList<>();
                for (ForwardTarget t : ts) banned.add(getTargetCardName(ctx, t));
                returnTargetsToOwnersHand(ctx, ts);
                banned.forEach(ctx::barOpponentFromCastingName);
            };
        }

        // --- Dull [and Freeze], then a turn-long damage shield (9-068H Mist Dragon, 23-024R Shiva) ---
        // Read off the whole followup and ahead of every dull branch below, for the reason the
        // Cockatrice branch gives: those scan primaryFollowup with find(), so each would claim the
        // dull and leave the shield to the secondary parser, where "it" names nothing.
        Matcher dullShieldM = FOLLOWUP_DULL_THEN_DAMAGE_SHIELD.matcher(followup.trim());
        if (dullShieldM.matches()) {
            final boolean freeze   = FOLLOWUP_FREEZE.matcher(dullShieldM.group()).find();
            final boolean incoming = dullShieldM.group("incoming") != null;
            return ctx -> {
                ctx.logChooseHeader(choosePrefix + " — Dull" + (freeze ? " & Freeze" : "")
                        + ", and " + (incoming ? "damage dealt to it" : "damage it deals")
                        + " becomes 0 for the rest of the turn");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters,
                        jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                for (ForwardTarget t : ts) {
                    if (freeze) ctx.dullAndFreezeTarget(t); else ctx.dullTarget(t);
                    // Both shields are keyed by card, so they hold wherever the chosen Character
                    // sits — the same route the Cockatrice branch takes for the same reason.
                    if (incoming) ctx.shieldAllIncomingDamageThisTurn(t);
                    else          ctx.zeroAllOutgoingDamageThisTurn(t);
                }
            };
        }

        // --- Dull-or-Freeze followup (must precede FOLLOWUP_DULL since it contains "Dull it") ---
        if (FOLLOWUP_DULL_OR_FREEZE.matcher(primaryFollowup).find()) {
            return ctx -> {
                ctx.logChooseHeader(choosePrefix + " — Dull or Freeze");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                sortedByIdxDesc(ts, true) .forEach(t -> ctx.dullOrFreezeTarget(t));
                sortedByIdxDesc(ts, false).forEach(t -> ctx.dullOrFreezeTarget(t));
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Dull followup ---
        if (FOLLOWUP_DULL.matcher(primaryFollowup).find()
                && !FOLLOWUP_DULL_AND_FREEZE.matcher(primaryFollowup).find()
                && !FOLLOWUP_DULL_OR_FREEZE.matcher(primaryFollowup).find()) {
            return ctx -> {
                ctx.logChooseHeader(choosePrefix + " — Dull");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                sortedByIdxDesc(ts, true) .forEach(t -> ctx.dullTarget(t));
                sortedByIdxDesc(ts, false).forEach(t -> ctx.dullTarget(t));
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Dull + Freeze followup ---
        if (FOLLOWUP_DULL_AND_FREEZE.matcher(primaryFollowup).find()) {
            return ctx -> {
                ctx.logChooseHeader(choosePrefix + " — Dull & Freeze");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                sortedByIdxDesc(ts, true) .forEach(t -> ctx.dullAndFreezeTarget(t));
                sortedByIdxDesc(ts, false).forEach(t -> ctx.dullAndFreezeTarget(t));
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Freeze followup ---
        if (FOLLOWUP_FREEZE.matcher(primaryFollowup).find()) {
            return ctx -> {
                ctx.logChooseHeader(choosePrefix + " — Freeze");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                sortedByIdxDesc(ts, true) .forEach(t -> ctx.freezeTarget(t));
                sortedByIdxDesc(ts, false).forEach(t -> ctx.freezeTarget(t));
                if (secondary != null) secondary.accept(ctx);
            };
        }


        // =====================================================================================
        // Extra-cost payoffs; break and ability loss
        // =====================================================================================
        // --- Extra-cost payoffs: 24-065H Fenrir and 18-136S Titan ---
        // These two Summons put their whole effect behind an optional extra cost and then refer
        // back to the card that paid it, rather than saying "If you paid the extra cost" like the
        // other sixteen. That wording is what applyExtraCostPaid/stripExtraCostClause rewrite, so
        // neither of these reaches the resolver pre-decided: the payoff has to read the payment at
        // resolution time, through extraCostDiscardedCardCost()/extraCostRemovedCardPower().
        //
        // Fenrir must precede the Break followup below: "break it and draw 1 card" contains
        // "break it", so FOLLOWUP_BREAK claimed it and broke the Forward outright, skipping the
        // cost comparison that is the entire card.
        Matcher extraCostMatchM = FOLLOWUP_IF_COST_EQUALS_DISCARD_BREAK_DRAW.matcher(primaryFollowup);
        if (extraCostMatchM.find()) {
            int draw = Integer.parseInt(extraCostMatchM.group("draw"));
            BiConsumer<GameContext, List<ForwardTarget>> action = extraCostCostMatchBreakDraw(draw);
            return ctx -> {
                ctx.logChooseHeader(choosePrefix + " — break and draw " + draw + " if cost matches the extra-cost discard");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters,
                        jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                action.accept(ctx, ts);
                if (secondary != null) secondary.accept(ctx);
            };
        }

        if (FOLLOWUP_DAMAGE_EXTRA_COST_POWER.matcher(primaryFollowup).find()) {
            BiConsumer<GameContext, List<ForwardTarget>> action = extraCostPowerDamage();
            return ctx -> {
                ctx.logChooseHeader(choosePrefix + " — damage equal to the power of the extra-cost Forward");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters,
                        jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                action.accept(ctx, ts);
                if (secondary != null) secondary.accept(ctx);
            };
        }

        if (FOLLOWUP_DAMAGE_REVEALED_FORWARD_POWER.matcher(primaryFollowup).find()) {
            BiConsumer<GameContext, List<ForwardTarget>> action = revealedForwardPowerDamage();
            return ctx -> {
                ctx.logChooseHeader(choosePrefix + " — damage equal to the power of the revealed Forward");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters,
                        jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                action.accept(ctx, ts);
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- "If its power has been increased or decreased, break it." (12-049H Diabolos) ---
        // Must precede the plain break below: that one scans with find() and matched "break it"
        // inside this sentence, dropping the condition and breaking whatever was chosen.
        //
        // The choose happens either way — being chosen is an event of its own, and only the break
        // is conditional — so a Forward at its printed power is picked and left standing.
        if (FOLLOWUP_BREAK_IF_POWER_CHANGED.matcher(primaryFollowup.trim()).matches()) {
            return ctx -> {
                ctx.logChooseHeader(choosePrefix + " — Break if its power has been changed");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                // Tested before any of them is broken: breaking one can end a field grant that was
                // holding another's power away from its printed value, which would answer the
                // question differently for a target chosen at the same moment.
                List<ForwardTarget> changed = new ArrayList<>();
                for (ForwardTarget t : ts) {
                    if (ctx.targetPowerHasChanged(t)) changed.add(t);
                    else ctx.logEntry("Effect: its power is unchanged — not broken");
                }
                sortedByIdxDesc(changed, true) .forEach(ctx::breakTarget);
                sortedByIdxDesc(changed, false).forEach(ctx::breakTarget);
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Break followup ---
        if (FOLLOWUP_BREAK.matcher(primaryFollowup).find()) {
            return ctx -> {
                ctx.logChooseHeader(choosePrefix + " — Break");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                sortedByIdxDesc(ts, true) .forEach(t -> ctx.breakTarget(t));
                sortedByIdxDesc(ts, false).forEach(t -> ctx.breakTarget(t));
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- "Loses all its abilities and its power becomes N until end of turn" (Wakka 1-216S) ---
        Matcher loseAndBecomeM = FOLLOWUP_LOSE_ABILITIES_AND_POWER_BECOMES.matcher(primaryFollowup);
        if (loseAndBecomeM.find()) {
            int targetPower = Integer.parseInt(loseAndBecomeM.group("power"));
            return ctx -> {
                ctx.logChooseHeader(choosePrefix + " — Lose all abilities, base power becomes "
                        + targetPower + " until end of turn");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                sortedByIdxDesc(ts, true) .forEach(ctx::targetLoseAllAbilitiesUntilEndOfTurn);
                sortedByIdxDesc(ts, false).forEach(ctx::targetLoseAllAbilitiesUntilEndOfTurn);
                // Descending order: dropping to the new power can break a Forward, which shifts
                // the indices of every target above it in the same zone.
                sortedByIdxDesc(ts, true) .forEach(t -> ctx.setTargetBasePower(t, targetPower));
                sortedByIdxDesc(ts, false).forEach(t -> ctx.setTargetBasePower(t, targetPower));
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Lose all abilities for as long as the source stays on the field ---
        // Ahead of the until-end-of-turn branch below, which reads the same "loses all its
        // abilities" phrase; see the note on the pattern.
        Matcher silenceM = FOLLOWUP_LOSES_ABILITIES_WHILE_NAMED_ON_FIELD.matcher(primaryFollowup.trim());
        if (source != null && silenceM.matches()
                && silenceM.group("name").trim().equalsIgnoreCase(source.name())) {
            return ctx -> {
                ctx.logChooseHeader(choosePrefix + " — loses all abilities while " + source.name() + " is on the field");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                ts.forEach(t -> ctx.targetLoseAllAbilitiesWhileWardenOnField(t, source));
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Lose all abilities until end of turn followup ---
        if (FOLLOWUP_LOSE_ALL_ABILITIES_EOT.matcher(primaryFollowup).find()) {
            return ctx -> {
                ctx.logChooseHeader(choosePrefix + " — Lose all abilities until end of turn");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                sortedByIdxDesc(ts, true) .forEach(ctx::targetLoseAllAbilitiesUntilEndOfTurn);
                sortedByIdxDesc(ts, false).forEach(ctx::targetLoseAllAbilitiesUntilEndOfTurn);
                if (secondary != null) secondary.accept(ctx);
            };
        }


        // =====================================================================================
        // Remove from game
        // =====================================================================================
        // --- "Remove them from the game. If these cards are of the same card type, also draw N card(s)." ---
        Matcher rfpSameTypeDrawM = FOLLOWUP_RFP_IF_SAME_TYPE_DRAW.matcher(followup);
        if (rfpSameTypeDrawM.find()) {
            int drawCount = Integer.parseInt(rfpSameTypeDrawM.group("count"));
            return ctx -> {
                ctx.logChooseHeader(choosePrefix + " — Remove From Game (if same type, draw " + drawCount + ")");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters,
                        jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                java.util.Set<String> typesSeen = new java.util.HashSet<>();
                for (ForwardTarget t : ts) {
                    CardData card = t.isP1() ? ctx.p1BreakZoneCard(t.idx()) : ctx.p2BreakZoneCard(t.idx());
                    if (card != null) typesSeen.add(card.type().toLowerCase(java.util.Locale.ROOT));
                }
                sortedByIdxDesc(ts, true) .forEach(ctx::removeTargetFromGame);
                sortedByIdxDesc(ts, false).forEach(ctx::removeTargetFromGame);
                if (!ts.isEmpty() && typesSeen.size() == 1) ctx.drawCards(drawCount);
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Remove from game + named card followup (e.g. "Remove it and Shuyin from the game") ---
        Matcher rfgNamedM = FOLLOWUP_REMOVE_FROM_GAME_AND_NAMED.matcher(primaryFollowup);
        if (rfgNamedM.find()) {
            String alsoNamed = rfgNamedM.group("named").trim();
            return ctx -> {
                ctx.logChooseHeader(choosePrefix + " — Remove From Game (+ " + alsoNamed + ")");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                sortedByIdxDesc(ts, true) .forEach(t -> ctx.removeTargetFromGame(t));
                sortedByIdxDesc(ts, false).forEach(t -> ctx.removeTargetFromGame(t));
                ctx.removeNamedCardFromGame(alsoNamed);
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- "Remove it from the game for as long as [Name] is on the field." (Necron) ---
        // Must precede the plain remove-from-game followup, whose pattern is a prefix of this one.
        Matcher rfgWhileM = FOLLOWUP_REMOVE_FROM_GAME_WHILE_ON_FIELD.matcher(primaryFollowup);
        if (rfgWhileM.find()) {
            String watcherName = rfgWhileM.group("name").trim();
            return ctx -> {
                ctx.logChooseHeader(choosePrefix + " — Remove from game while " + watcherName + " is on the field");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                sortedByIdxDesc(ts, true) .forEach(t -> ctx.removeTargetFromGameWhileNamedCardOnField(t, watcherName));
                sortedByIdxDesc(ts, false).forEach(t -> ctx.removeTargetFromGameWhileNamedCardOnField(t, watcherName));
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Optional remove from game followup ---
        // Must precede the plain remove-from-game followup, whose pattern is a suffix of this one
        // and would remove the card without asking. Declining fizzles the effect so that an
        // enclosing "If you do so, …" sequence correctly skips its payoff (8-147S Fordola).
        if (FOLLOWUP_MAY_REMOVE_FROM_GAME.matcher(primaryFollowup).find()) {
            return ctx -> {
                ctx.logChooseHeader(choosePrefix + " — You may Remove From Game");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone, bothZones,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                if (ts.isEmpty() || !ctx.promptYouMay("Remove the chosen card from the game?")) {
                    ctx.logEntry("  declined — nothing removed");
                    ctx.markEffectFizzled();
                    return;
                }
                sortedByIdxDesc(ts, true) .forEach(t -> ctx.removeTargetFromGame(t));
                sortedByIdxDesc(ts, false).forEach(t -> ctx.removeTargetFromGame(t));
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Remove from game followup ---
        if (FOLLOWUP_REMOVE_FROM_GAME.matcher(primaryFollowup).find()) {
            return ctx -> {
                ctx.logChooseHeader(choosePrefix + " — Remove From Game");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone, bothZones,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                sortedByIdxDesc(ts, true) .forEach(t -> ctx.removeTargetFromGame(t));
                sortedByIdxDesc(ts, false).forEach(t -> ctx.removeTargetFromGame(t));
                if (secondary != null) secondary.accept(ctx);
            };
        }


        // =====================================================================================
        // Play onto the field; add to hand; return to hand
        // =====================================================================================
        // --- Play onto field followup ---
        // --- "If its cost is equal to or less than the number of Job X you control, play it onto the field." ---
        // Must be checked before the generic PlayOntoField handler so the condition is enforced.
        Matcher costLeJobM = FOLLOWUP_PLAY_IF_COST_LE_JOB_COUNT.matcher(primaryFollowup);
        if (costLeJobM.matches()) {
            String condJob = costLeJobM.group("job").trim();
            return ctx -> {
                ctx.logChooseHeader(choosePrefix + " — Play onto Field if cost ≤ count of Job " + condJob + " you control");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters,
                        jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                int jobCount = ctx.countSelfFieldCards(true, true, true, condJob, null);
                for (ForwardTarget t : sortedByIdxDesc(ts, true) .collect(java.util.stream.Collectors.toList())) {
                    CardData card = t.isP1() ? ctx.p1BreakZoneCard(t.idx()) : ctx.p2BreakZoneCard(t.idx());
                    if (card != null && card.cost() <= jobCount) ctx.playTargetOntoField(t);
                }
                for (ForwardTarget t : sortedByIdxDesc(ts, false).collect(java.util.stream.Collectors.toList())) {
                    CardData card = t.isP1() ? ctx.p1BreakZoneCard(t.idx()) : ctx.p2BreakZoneCard(t.idx());
                    if (card != null && card.cost() <= jobCount) ctx.playTargetOntoField(t);
                }
            };
        }

        // --- "If its cost is X, play it onto the field." (Leo 13-067L) ---
        // Ahead of the generic PlayOntoField handler for the reason the job-count form above is:
        // that one matches with find() and would play the chosen card whatever it cost. X is what
        // the ability's variable counter cost removed, carried here as xValue.
        if (FOLLOWUP_PLAY_IF_COST_IS_X.matcher(primaryFollowup).matches()) {
            final int requiredCost = xValue;
            return ctx -> {
                ctx.logChooseHeader(choosePrefix + " — Play onto Field if cost is " + requiredCost);
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters,
                        jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                sortedByIdxDesc(ts, true) .forEach(t -> playFromBzIfCostIs(ctx, t, requiredCost));
                sortedByIdxDesc(ts, false).forEach(t -> playFromBzIfCostIs(ctx, t, requiredCost));
                if (secondary != null) secondary.accept(ctx);
            };
        }

        if (FOLLOWUP_PLAY_ONTO_FIELD.matcher(primaryFollowup).find()) {
            // "Its auto-ability will not trigger." qualifies the play rather than following it, so
            // it is read here and not run as a secondary — see the guard that nulls it out above.
            final boolean noAutoAbility = secondaryText != null
                    && ITS_AUTO_ABILITY_WILL_NOT_TRIGGER.matcher(secondaryText).matches();
            // Check for "When it enters the field, if it is [cond], [inner]" conditional secondary.
            // Peek at the chosen card's data before playing so we can evaluate the condition after.
            final Predicate<CardData> etfCond;
            final Consumer<GameContext> etfInner;
            final String etfInnerText;
            if (secondaryText != null) {
                Matcher etfM = FOLLOWUP_PLAY_ONTO_FIELD_WHEN_ENTERS_CONDITIONAL.matcher(secondaryText);
                if (etfM.matches()) {
                    Predicate<CardData> parsedCond = parseRevealCondition(etfM.group("cond").trim());
                    String innerTxt = etfM.group("inner").trim();
                    Consumer<GameContext> inner = parsedCond != null ? parse(innerTxt, source) : null;
                    etfCond      = (parsedCond != null && inner != null) ? parsedCond : null;
                    etfInner     = (parsedCond != null && inner != null) ? inner      : null;
                    etfInnerText = (parsedCond != null && inner != null) ? innerTxt   : null;
                } else {
                    etfCond = null; etfInner = null; etfInnerText = null;
                }
            } else {
                etfCond = null; etfInner = null; etfInnerText = null;
            }
            return ctx -> {
                ctx.logChooseHeader(choosePrefix + " — Play onto Field"
                        + (noAutoAbility ? " (its auto-ability will not trigger)" : ""));
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                List<CardData> chosenCards = new ArrayList<>();
                if (etfCond != null) {
                    for (ForwardTarget t : ts) {
                        CardData c = zone != null
                                ? (t.isP1() ? ctx.p1BreakZoneCard(t.idx()) : ctx.p2BreakZoneCard(t.idx()))
                                : null;
                        if (c != null) chosenCards.add(c);
                    }
                }
                sortedByIdxDesc(ts, true) .forEach(t -> {
                    if (noAutoAbility) ctx.playTargetOntoFieldNoAutoAbility(t);
                    else               ctx.playTargetOntoField(t);
                });
                sortedByIdxDesc(ts, false).forEach(t -> {
                    if (noAutoAbility) ctx.playTargetOntoFieldNoAutoAbility(t);
                    else               ctx.playTargetOntoField(t);
                });
                if (etfCond != null && etfInner != null) {
                    boolean anyMatched = chosenCards.stream().anyMatch(etfCond);
                    if (anyMatched) {
                        ctx.logEntry("ETF Condition met — " + etfInnerText);
                        etfInner.accept(ctx);
                    }
                } else if (secondary != null) {
                    secondary.accept(ctx);
                }
            };
        }

        // --- Add to hand followup ---
        if (FOLLOWUP_ADD_TO_HAND.matcher(primaryFollowup).find()) {
            // Detect a conditional secondary that depends on the added card, e.g.
            // "If it is a Card Name Tifa, …" or "If the added card is not a Category II card, …".
            // When matched, the inner effect runs only if the chosen card satisfies the condition,
            // and the generic secondary parse is suppressed.
            final Predicate<CardData> addedCardCond;
            final Consumer<GameContext> conditionalInner;
            final String conditionalInnerText;
            if (secondaryText != null) {
                Matcher condM = FOLLOWUP_ADD_TO_HAND_CONDITIONAL_SECONDARY.matcher(secondaryText);
                if (condM.matches()) {
                    Predicate<CardData> cond = parseRevealCondition(condM.group("cond").trim());
                    String innerTxt = condM.group("inner").trim();
                    Consumer<GameContext> inner = cond != null ? parse(innerTxt, source) : null;
                    addedCardCond       = (cond != null && inner != null) ? cond  : null;
                    conditionalInner    = (cond != null && inner != null) ? inner : null;
                    conditionalInnerText = (cond != null && inner != null) ? innerTxt : null;
                } else {
                    addedCardCond        = null;
                    conditionalInner     = null;
                    conditionalInnerText = null;
                }
            } else {
                addedCardCond        = null;
                conditionalInner     = null;
                conditionalInnerText = null;
            }

            return ctx -> {
                ctx.logChooseHeader(choosePrefix + " — Add to Hand");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                // Peek at chosen cards before they leave the Break Zone so the conditional
                // secondary can inspect them.
                List<CardData> chosenCards = new ArrayList<>();
                if (addedCardCond != null) {
                    for (ForwardTarget t : ts) {
                        CardData c = t.isP1() ? ctx.p1BreakZoneCard(t.idx()) : ctx.p2BreakZoneCard(t.idx());
                        if (c != null) chosenCards.add(c);
                    }
                }
                sortedByIdxDesc(ts, true) .forEach(t -> ctx.addTargetToHand(t));
                sortedByIdxDesc(ts, false).forEach(t -> ctx.addTargetToHand(t));

                if (addedCardCond != null && conditionalInner != null) {
                    boolean anyMatched = chosenCards.stream().anyMatch(addedCardCond);
                    if (anyMatched) {
                        ctx.logEntry("Condition met (added card) — " + conditionalInnerText);
                        conditionalInner.accept(ctx);
                    }
                } else if (secondary != null) {
                    secondary.accept(ctx);
                }
            };
        }

        // --- Return it and [NamedCard] to their owners' hands ---
        Matcher retNamedM = FOLLOWUP_RETURN_AND_NAMED_TO_OWNERS_HAND.matcher(primaryFollowup);
        if (retNamedM.find()) {
            String alsoNamed = retNamedM.group("named").trim();
            return ctx -> {
                ctx.logChooseHeader(choosePrefix + " — Return to owner's hand (+ " + alsoNamed + ")");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                returnTargetsToOwnersHand(ctx, ts);
                ctx.returnNamedCardToOwnersHand(alsoNamed);
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- "If its cost ≤ number of cards in your hand, return to owner's hand" (Leviathan EX Burst) ---
        if (FOLLOWUP_RETURN_IF_COST_LE_HAND.matcher(strippedPrimaryFollowup).matches()) {
            return ctx -> {
                int handSize = ctx.yourHandSize();
                ctx.logChooseHeader(choosePrefix + " — Return to owner's hand if cost ≤ hand size (" + handSize + ")");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters,
                        jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                // Filter to eligible-by-cost targets first (indices are still valid here, before any
                // removal), then return them highest-index-first per side to avoid index shifting.
                List<ForwardTarget> toReturn = new ArrayList<>();
                for (ForwardTarget t : ts) {
                    if (t.zone() != ForwardTarget.CardZone.FORWARD) continue;
                    CardData card = t.isP1() ? ctx.p1Forward(t.idx()) : ctx.p2Forward(t.idx());
                    if (card == null || card.cost() > handSize) {
                        if (card != null) ctx.logEntry("Cost " + card.cost() + " > hand size " + handSize + " — condition not met");
                        continue;
                    }
                    ctx.logEntry("Cost " + card.cost() + " ≤ hand size " + handSize + " — returning to hand");
                    toReturn.add(t);
                }
                returnTargetsToOwnersHand(ctx, toReturn);
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Return to owner's hand followup ---
        if (FOLLOWUP_RETURN_TO_OWNERS_HAND.matcher(strippedPrimaryFollowup).find()) {
            return ctx -> {
                ctx.logChooseHeader(choosePrefix + " — Return to owner's hand");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                Consumer<GameContext> doReturn = ctx2 -> {
                    returnTargetsToOwnersHand(ctx2, ts);
                    if (secondary != null) secondary.accept(ctx2);
                };
                if (followupIsOptional && !ts.isEmpty()) ctx.playerMayDoEffect("Return it to its owner's hand?", doReturn);
                else if (!followupIsOptional) doReturn.accept(ctx);
            };
        }

        // --- Return to your hand followup ---
        if (FOLLOWUP_RETURN_TO_YOUR_HAND.matcher(primaryFollowup).find()) {
            return ctx -> {
                ctx.logChooseHeader(choosePrefix + " — Return to your hand");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                sortedByIdxDesc(ts, true)
                        .filter(t -> t.zone() == ForwardTarget.CardZone.FORWARD)
                        .forEach(t -> ctx.returnP1ForwardToHand(t.idx()));
                if (secondary != null) secondary.accept(ctx);
            };
        }


        // =====================================================================================
        // Put on the top or bottom of a deck
        // =====================================================================================
        // --- Put at top or bottom of owner's deck followup (player chooses) ---
        if (FOLLOWUP_PUT_TOP_OR_BOTTOM_OF_DECK.matcher(primaryFollowup).find()) {
            return ctx -> {
                ctx.logChooseHeader(choosePrefix + " — Put at top or bottom of owner's deck (player chooses)");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                for (ForwardTarget t : ts) {
                    if (t.zone() != ForwardTarget.CardZone.FORWARD) continue;
                    if (t.isP1()) {
                        String cardName = ctx.p1Forward(t.idx()).name();
                        boolean toTop = ctx.askTopOrBottom(cardName);
                        if (toTop) ctx.returnP1ForwardToDeckTop(t.idx());
                        else       ctx.returnP1ForwardToDeckBottom(t.idx());
                    } else {
                        String cardName = ctx.p2Forward(t.idx()).name();
                        boolean toTop = ctx.askTopOrBottom(cardName);
                        if (toTop) ctx.returnP2ForwardToDeckTop(t.idx());
                        else       ctx.returnP2ForwardToDeckBottom(t.idx());
                    }
                }
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Put at bottom of owner's deck followup ---
        if (FOLLOWUP_PUT_BOTTOM_OF_DECK.matcher(primaryFollowup).find()) {
            return ctx -> {
                ctx.logChooseHeader(choosePrefix + " — Put at bottom of owner's deck");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                for (ForwardTarget t : ts) {
                    if (t.zone() != ForwardTarget.CardZone.FORWARD) continue;
                    if (t.isP1()) ctx.returnP1ForwardToDeckBottom(t.idx());
                    else          ctx.returnP2ForwardToDeckBottom(t.idx());
                }
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Conditional power-vs-source "put on top of deck" followup (e.g. Wakka) ---
        Matcher ifPowerCmpSourceM = FOLLOWUP_IF_POWER_CMP_SOURCE_PUT_ON_DECK_TOP.matcher(primaryFollowup);
        if (ifPowerCmpSourceM.find()) {
            boolean wantLessOrEqual = "less".equalsIgnoreCase(ifPowerCmpSourceM.group("cmp"));
            return ctx -> {
                ctx.logChooseHeader(choosePrefix + " — Conditional power check vs source, put on top of owner's deck");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                // Find source card's current effective power on the field
                int sourcePower = source.power();
                outer:
                for (int pi = 0; pi <= 1; pi++) {
                    boolean p1 = pi == 0;
                    int cnt = p1 ? ctx.p1ForwardCount() : ctx.p2ForwardCount();
                    for (int i = 0; i < cnt; i++) {
                        if ((p1 ? ctx.p1Forward(i) : ctx.p2Forward(i)) == source) {
                            sourcePower = ctx.effectiveTargetPower(
                                    new ForwardTarget(p1, i, ForwardTarget.CardZone.FORWARD));
                            break outer;
                        }
                    }
                }
                final int sp = sourcePower;
                for (ForwardTarget t : ts) {
                    if (t.zone() != ForwardTarget.CardZone.FORWARD) continue;
                    int targetPower = ctx.effectiveTargetPower(t);
                    boolean condMet = wantLessOrEqual ? targetPower <= sp : targetPower >= sp;
                    if (condMet) {
                        ctx.logEntry("  power " + targetPower + (wantLessOrEqual ? " ≤ " : " ≥ ") + sp + " — bounced to deck top");
                        if (t.isP1()) ctx.returnP1ForwardToDeckTop(t.idx());
                        else          ctx.returnP2ForwardToDeckTop(t.idx());
                    } else {
                        ctx.logEntry("  power " + targetPower + (wantLessOrEqual ? " > " : " < ") + sp + " — condition not met, no effect");
                    }
                }
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Put on top of your own deck followup (Break Zone salvage) ---
        // Must precede the owner's-deck followup below only in spirit — the two phrasings are
        // disjoint ("your deck" vs "its owner's deck") — but they are kept adjacent so the pair
        // stays visible as one decision.
        Matcher topOwnDeckM = FOLLOWUP_PUT_TOP_OF_YOUR_DECK.matcher(primaryFollowup);
        if (topOwnDeckM.find()) {
            boolean optional = topOwnDeckM.group("may") != null;
            return ctx -> {
                ctx.logChooseHeader(choosePrefix + " — Put on top of your deck");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                if (!ts.isEmpty() && optional
                        && !ctx.promptYouMay("Put the chosen card on top of your deck?")) {
                    ctx.logEntry("  declined — card stays in the Break Zone");
                } else {
                    // Descending index order: each removal shifts the Break Zone entries after it.
                    sortedByIdxDesc(ts, true).forEach(ctx::putBreakZoneTargetOnTopOfDeck);
                    sortedByIdxDesc(ts, false).forEach(ctx::putBreakZoneTargetOnTopOfDeck);
                }
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Put at bottom of your own deck, with a second effect in the same clause (Scholar) ---
        // Kept ahead of the plain form below for readability; the two cannot both match, since that
        // pattern's lookahead declines any text continuing with "and".
        Matcher bottomThenM = FOLLOWUP_PUT_BOTTOM_OF_YOUR_DECK_AND_THEN.matcher(primaryFollowup);
        if (bottomThenM.find()) {
            String alsoText = bottomThenM.group("also").trim();
            Consumer<GameContext> alsoFn = parse(alsoText, source);
            // Falling through when the trailing effect has no parser is deliberate: burying the
            // chosen card and dropping the rest is a half-applied effect, and the unimplemented
            // followup warning at the end of this method is the honest outcome.
            if (alsoFn != null) {
                return ctx -> {
                    ctx.logChooseHeader(choosePrefix + " — Put at bottom of your deck, then: " + alsoText);
                    List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                            opponentOnly, selfOnly, condition, element, zone, opponentZone,
                            costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                    // Descending index order: each removal shifts the Break Zone entries after it.
                    sortedByIdxDesc(ts, true).forEach(ctx::putBreakZoneTargetOnBottomOfDeck);
                    sortedByIdxDesc(ts, false).forEach(ctx::putBreakZoneTargetOnBottomOfDeck);
                    // Runs even when nothing was chosen: Scholar's "choose up to 1" permits zero,
                    // and the trailing clause is a second instruction of the same effect, not a
                    // consequence of the first ("when you do so" would be the conditional wording).
                    alsoFn.accept(ctx);
                    if (secondary != null) secondary.accept(ctx);
                };
            }
        }

        // --- Put at bottom of your own deck followup (Break Zone burial) ---
        // The twin of the block above, kept adjacent for the same reason: "your deck" and "its
        // owner's deck" are disjoint phrasings that read as one decision.
        Matcher bottomOwnDeckM = FOLLOWUP_PUT_BOTTOM_OF_YOUR_DECK.matcher(primaryFollowup);
        if (bottomOwnDeckM.find()) {
            boolean optional = bottomOwnDeckM.group("may") != null;
            return ctx -> {
                ctx.logChooseHeader(choosePrefix + " — Put at bottom of your deck");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                if (!ts.isEmpty() && optional
                        && !ctx.promptYouMay("Put the chosen card at the bottom of your deck?")) {
                    ctx.logEntry("  declined — card stays in the Break Zone");
                } else {
                    // Descending index order: each removal shifts the Break Zone entries after it.
                    sortedByIdxDesc(ts, true).forEach(ctx::putBreakZoneTargetOnBottomOfDeck);
                    sortedByIdxDesc(ts, false).forEach(ctx::putBreakZoneTargetOnBottomOfDeck);
                }
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Put on top of owner's deck followup ---
        if (FOLLOWUP_PUT_TOP_OF_DECK.matcher(primaryFollowup).find()) {
            return ctx -> {
                ctx.logChooseHeader(choosePrefix + " — Put on top of owner's deck");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                for (ForwardTarget t : ts) {
                    if (t.zone() != ForwardTarget.CardZone.FORWARD) continue;
                    if (t.isP1()) ctx.returnP1ForwardToDeckTop(t.idx());
                    else          ctx.returnP2ForwardToDeckTop(t.idx());
                }
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Put under top N cards of owner's deck followup ---
        Matcher underTopM = FOLLOWUP_PUT_UNDER_TOP_OF_DECK.matcher(primaryFollowup);
        if (underTopM.find()) {
            int underPos = underTopM.group("numword") != null ? 4 : 1;
            return ctx -> {
                ctx.logChooseHeader(choosePrefix + " — Put under top " + underPos + " card(s) of owner's deck");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                for (ForwardTarget t : ts) {
                    if (t.zone() != ForwardTarget.CardZone.FORWARD) continue;
                    if (t.isP1()) ctx.returnP1ForwardUnderDeckTop(t.idx(), underPos);
                    else          ctx.returnP2ForwardUnderDeckTop(t.idx(), underPos);
                }
                if (secondary != null) secondary.accept(ctx);
            };
        }


        // =====================================================================================
        // Block restrictions
        // =====================================================================================
        // --- Cannot block followup ---
        if (FOLLOWUP_CANNOT_BLOCK.matcher(primaryFollowup).find()) {
            return ctx -> {
                ctx.logChooseHeader(choosePrefix + " — Cannot block this turn");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                for (ForwardTarget t : ts) {
                    if (t.zone() != ForwardTarget.CardZone.FORWARD) continue;
                    if (t.isP1()) ctx.setP1ForwardCannotBlock(t.idx());
                    else          ctx.setP2ForwardCannotBlock(t.idx());
                }
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Cannot be blocked followup ---
        if (FOLLOWUP_CANNOT_BE_BLOCKED.matcher(primaryFollowup).find()) {
            Matcher bm = FOLLOWUP_CANNOT_BE_BLOCKED.matcher(primaryFollowup);
            bm.find();
            String bCostStr  = bm.group("costval");
            String bCostCmp  = bm.group("costcmp");
            final int   bCostVal = bCostStr != null ? Integer.parseInt(bCostStr) : -1;
            final boolean bIsMore = "more".equalsIgnoreCase(bCostCmp);
            String bCostLabel = bCostVal >= 0 ? " by cost " + bCostVal + " or " + bCostCmp : "";
            return ctx -> {
                ctx.logChooseHeader(choosePrefix + " — Cannot be blocked" + bCostLabel + " this turn");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                for (ForwardTarget t : ts) {
                    if (t.zone() != ForwardTarget.CardZone.FORWARD) continue;
                    if (bCostVal >= 0) {
                        if (t.isP1()) ctx.setP1ForwardCannotBeBlockedByCost(t.idx(), bCostVal, bIsMore);
                        else          ctx.setP2ForwardCannotBeBlockedByCost(t.idx(), bCostVal, bIsMore);
                    } else {
                        if (t.isP1()) ctx.setP1ForwardCannotBeBlocked(t.idx());
                        else          ctx.setP2ForwardCannotBeBlocked(t.idx());
                    }
                }
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Only blocked by Forward of cost ≤ own cost followup ---
        if (FOLLOWUP_ONLY_BLOCKED_BY_COST_LE_OWN.matcher(primaryFollowup).find()) {
            return ctx -> {
                ctx.logChooseHeader(choosePrefix + " — Can only be blocked by a Forward of cost ≤ its own this turn");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                for (ForwardTarget t : ts) {
                    if (t.zone() != ForwardTarget.CardZone.FORWARD) continue;
                    int ownCost = (t.isP1() ? ctx.p1Forward(t.idx()) : ctx.p2Forward(t.idx())).cost();
                    if (t.isP1()) ctx.setP1ForwardCannotBeBlockedByCost(t.idx(), ownCost + 1, true);
                    else          ctx.setP2ForwardCannotBeBlockedByCost(t.idx(), ownCost + 1, true);
                }
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- "Until the end of the turn, it gains "[quoted ability]"" (Behemoth 24-084R) ---
        // Granted verbatim, the way the must-block branches below store their sentence, so the
        // reader sees exactly what a printing of the same ability would say — including the
        // "this Forward" self-reference, which DamageResolver resolves against the holder.
        //
        // Deliberately narrow: only a quotation the engine actually reads is claimed here. A grant
        // of anything else falls through the chain and stays visibly unhandled, rather than being
        // accepted and resolving as a no-op.
        Matcher grantQuoted = FOLLOWUP_GAINS_QUOTED_ABILITY_UNTIL_EOT.matcher(primaryFollowup);
        if (grantQuoted.find()) {
            String granted = grantQuoted.group("granted").trim();
            if (AutoAbilityTriggers.FA_OUTGOING_DAMAGE_TO_OPPONENT_SETS_TO.matcher(granted).matches()) {
                return ctx -> {
                    ctx.logChooseHeader(choosePrefix + " — gains \"" + granted + "\" this turn");
                    List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                            opponentOnly, selfOnly, condition, element, zone, opponentZone,
                            costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                    for (ForwardTarget t : ts) {
                        if (t.zone() != ForwardTarget.CardZone.FORWARD) continue;
                        ctx.grantFieldAbilityUntilEndOfTurn(t, granted);
                    }
                    if (secondary != null) secondary.accept(ctx);
                };
            }
        }

        // --- Must block a named attacker followup (Dio 26-075C) ---
        // Kept ahead of the plain must-block branch below: that one compels the chosen Forward
        // against every attacker, which is a strictly harsher reading of this text. The two
        // quoted wordings do not currently overlap, but the ordering makes the intent explicit.
        Matcher mbNamed = FOLLOWUP_GAINS_MUST_BLOCK_NAMED_UNTIL_EOT.matcher(primaryFollowup);
        if (mbNamed.find()) {
            String attackerName = mbNamed.group("cardname").trim();
            // Stored verbatim so MainWindow's block rules read it exactly as they read a printed
            // "This Forward must block [name] if possible." — see FA_THIS_FORWARD_MUST_BLOCK_NAMED.
            String granted = "This Forward must block " + attackerName + " if possible.";
            return ctx -> {
                ctx.logChooseHeader(choosePrefix + " — Must block " + attackerName + " if possible this turn");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                for (ForwardTarget t : ts) {
                    if (t.zone() != ForwardTarget.CardZone.FORWARD) continue;
                    ctx.grantFieldAbilityUntilEndOfTurn(t, granted);
                }
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- "Select 1 Counter placed on it, and place 1 additional Counter of the same type." ---
        // Gestahlian Empire Cid 11-026H. Which counter is copied is the player's call, so the
        // primitive asks whenever the Monster carries more than one kind.
        if (FOLLOWUP_SELECT_COUNTER_AND_ADD_SAME_TYPE.matcher(primaryFollowup.trim()).matches()) {
            return ctx -> {
                ctx.logChooseHeader(choosePrefix + " — duplicate 1 Counter already on it");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                ts.forEach(ctx::duplicateOneCounterOnTarget);
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Must block a named attacker, stated inline (Lightning 1-141L, Galuf 7-067L) ---
        // Same compulsion the branch above grants through a quotation, so it stores the same
        // sentence and lands in the same place; only the printed wording differs. Kept ahead of
        // the plain must-block branch for the reason that one is: this reading is the narrower.
        Matcher mbInline = FOLLOWUP_MUST_BLOCK_NAMED_INLINE.matcher(primaryFollowup);
        if (mbInline.find()) {
            String attackerName = mbInline.group("cardname").trim();
            String granted = "This Forward must block " + attackerName + " if possible.";
            return ctx -> {
                ctx.logChooseHeader(choosePrefix + " — Must block " + attackerName + " if possible this turn");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                for (ForwardTarget t : ts) {
                    if (t.zone() != ForwardTarget.CardZone.FORWARD) continue;
                    ctx.grantFieldAbilityUntilEndOfTurn(t, granted);
                }
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Must block followup ---
        if (FOLLOWUP_MUST_BLOCK.matcher(primaryFollowup).find()) {
            return ctx -> {
                ctx.logChooseHeader(choosePrefix + " — Must block if possible this turn");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                for (ForwardTarget t : ts) {
                    if (t.zone() != ForwardTarget.CardZone.FORWARD) continue;
                    if (t.isP1()) ctx.setP1ForwardMustBlock(t.idx());
                    else          ctx.setP2ForwardMustBlock(t.idx());
                }
                if (secondary != null) secondary.accept(ctx);
            };
        }


        // =====================================================================================
        // Attack restrictions and quoted attack locks
        // =====================================================================================
        // --- Cannot attack (this turn) followup ---
        if (FOLLOWUP_CANNOT_ATTACK.matcher(primaryFollowup).find()) {
            return ctx -> {
                ctx.logChooseHeader(choosePrefix + " — Cannot attack this turn");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                for (ForwardTarget t : ts) {
                    if (t.zone() != ForwardTarget.CardZone.FORWARD) continue;
                    if (t.isP1()) ctx.setP1ForwardCannotAttack(t.idx());
                    else          ctx.setP2ForwardCannotAttack(t.idx());
                }
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- "Until the end of the turn, it gains "<clause>" and <Self> gains +N power." ---
        // Azul 23-077H. Must precede the must-attack branch below: the compulsion it hands out is
        // spelled "must attack once per turn", which that branch's pattern does not read, so left
        // to the chain the whole followup fell through to the unimplemented warning and the choose
        // resolved as a bare target selection.
        //
        // Declines rather than half-applying when either half does not check out — a quoted clause
        // this engine cannot enforce, or a power clause naming a card other than the source — for
        // the reason permanentGrantForClause gives: an inert grant reports as handled, while an
        // unparsed one stays visible.
        Matcher grantAndPayM = FOLLOWUP_GAINS_QUOTED_EOT_AND_SELF_POWER_BOOST.matcher(primaryFollowup.trim());
        if (source != null && grantAndPayM.matches()
                && grantAndPayM.group("self").trim().equalsIgnoreCase(source.name())) {
            String  quoted    = grantAndPayM.group("quoted").trim();
            int     selfBoost = Integer.parseInt(grantAndPayM.group("amount"));
            Matcher mustAtkM  = GRANTED_MUST_ATTACK_ONCE_PER_TURN.matcher(quoted);
            if (mustAtkM.matches()
                    && GRANTED_CLAUSE_SELF_SUBJECT.matcher(mustAtkM.group("subj").trim()).matches()) {
                return ctx -> {
                    ctx.logChooseHeader(choosePrefix + " — must attack once per turn this turn; "
                            + source.name() + " gains +" + selfBoost + " power");
                    List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                            opponentOnly, selfOnly, condition, element, zone, opponentZone,
                            costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters,
                            jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                    ts.forEach(ctx::grantMustAttackOncePerTurnUntilEndOfTurn);
                    // Paid whether or not a Forward was there to be compelled: the sentence promises
                    // the boost outright, and Azul is the one card printing it.
                    ctx.boostSourceForward(source, selfBoost, EnumSet.noneOf(CardData.Trait.class));
                    if (secondary != null) secondary.accept(ctx);
                };
            }
        }

        // --- "Until the end of the turn, it gains "<clause>" and "<clause>"." ---
        // Tulien 21-072H, handing the chosen Forward both compulsions at once. Beside the Azul
        // branch above and ahead of the plain must-attack branch below for the same reason: the
        // compulsions are spelled the way a printed field ability spells them ("This Forward must
        // attack once per turn if possible."), which neither of the plain branches reads, so the
        // whole followup fell through to the unimplemented warning and the choose resolved as a
        // bare target selection.
        //
        // Declines rather than half-applying when either clause fails to check out, as the Azul
        // branch does: a grant this engine cannot enforce reports as handled while an unparsed one
        // stays visible.
        if (isMustAttackAndMustBlockGrant(primaryFollowup)) {
            return ctx -> {
                ctx.logChooseHeader(choosePrefix + " — must attack once per turn and must block, until end of turn");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters,
                        jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                for (ForwardTarget t : ts) {
                    if (t.zone() != ForwardTarget.CardZone.FORWARD) continue;
                    ctx.grantMustAttackOncePerTurnUntilEndOfTurn(t);
                    if (t.isP1()) ctx.setP1ForwardMustBlock(t.idx());
                    else          ctx.setP2ForwardMustBlock(t.idx());
                }
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Must attack (this turn) followup ---
        if (FOLLOWUP_MUST_ATTACK.matcher(primaryFollowup).find()) {
            return ctx -> {
                ctx.logChooseHeader(choosePrefix + " — Must attack if possible this turn");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                for (ForwardTarget t : ts) {
                    if (t.zone() != ForwardTarget.CardZone.FORWARD) continue;
                    if (t.isP1()) ctx.setP1ForwardMustAttack(t.idx());
                    else          ctx.setP2ForwardMustAttack(t.idx());
                }
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Cannot attack or block, and damage becomes 0, this turn (5-081C Cockatrice) ---
        // Ahead of both halves' own branches, for the reason the Kitone note below gives: each of
        // them finds its own clause inside this sentence and would silently drop the other.
        if (FOLLOWUP_CANNOT_ATTACK_OR_BLOCK_AND_NEGATE_DAMAGE.matcher(primaryFollowup.trim()).matches()) {
            return ctx -> {
                ctx.logChooseHeader(choosePrefix + " — Cannot attack or block, and damage becomes 0, this turn");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                for (ForwardTarget t : ts) {
                    // Keyed by card so the lock holds wherever the chosen Character sits — the
                    // same reason the Kitone branch below uses this route rather than the row
                    // indices the plain cannot-attack-or-block branch sets.
                    ctx.setTargetCannotAttackOrBlockThisTurn(t);
                    ctx.shieldAllIncomingDamageThisTurn(t);
                }
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Cannot attack or block (this turn) followup ---
        // --- Cannot attack or block, and cannot use action abilities, this turn (Kitone) ---
        // Must precede the plain cannot-attack-or-block branch below: both scan with find(), and
        // that one would claim the first clause and drop the action-ability lock.
        if (FOLLOWUP_CANNOT_ATTACK_OR_BLOCK_AND_NO_ACTION_ABILITIES.matcher(primaryFollowup).find()) {
            return ctx -> {
                ctx.logChooseHeader(choosePrefix + " — Cannot attack, block or use action abilities this turn");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                for (ForwardTarget t : ts) {
                    // Both halves are keyed by card, so they hold wherever the chosen Character
                    // sits — including a Backup or Monster that only becomes a Forward later.
                    ctx.setTargetCannotAttackOrBlockThisTurn(t);
                    ctx.setTargetCannotUseActionAbilitiesThisTurn(t);
                }
                if (secondary != null) secondary.accept(ctx);
            };
        }

        if (FOLLOWUP_CANNOT_ATTACK_OR_BLOCK.matcher(primaryFollowup).find()) {
            return ctx -> {
                ctx.logChooseHeader(choosePrefix + " — Cannot attack or block this turn");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                for (ForwardTarget t : ts) {
                    if (t.zone() != ForwardTarget.CardZone.FORWARD) continue;
                    if (t.isP1()) { ctx.setP1ForwardCannotAttack(t.idx()); ctx.setP1ForwardCannotBlock(t.idx()); }
                    else          { ctx.setP2ForwardCannotAttack(t.idx()); ctx.setP2ForwardCannotBlock(t.idx()); }
                }
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Cannot attack or block until end of opponent's/next turn (persistent) followup ---
        if (FOLLOWUP_CANNOT_ATTACK_OR_BLOCK_PERSISTENT.matcher(primaryFollowup).find()) {
            return ctx -> {
                ctx.logChooseHeader(choosePrefix + " — Cannot attack or block until end of next turn");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                for (ForwardTarget t : ts) {
                    if (t.zone() != ForwardTarget.CardZone.FORWARD) continue;
                    if (t.isP1()) ctx.setP1ForwardCannotAttackOrBlockPersistent(t.idx());
                    else          ctx.setP2ForwardCannotAttackOrBlockPersistent(t.idx());
                }
                if (secondary != null) secondary.accept(ctx);
            };
        }


        // =====================================================================================
        // Power boosts
        // =====================================================================================
        // --- Board-wide power-becomes: "All the Forwards' power become N until end of turn" ---
        // Must precede the single-target branch below, which scans with find(). 15-053H Diabolos
        // reaches this as the upgraded half of its cast-count gate, where the choose still happens
        // — the card names a target and then overrides every Forward's power, so the selection is
        // kept for the "when chosen" triggers that watch it.
        Matcher allBecomesM = FOLLOWUP_ALL_FORWARDS_POWER_BECOMES.matcher(primaryFollowup.trim());
        if (allBecomesM.matches()) {
            int allPower = Integer.parseInt(allBecomesM.group("power"));
            return ctx -> {
                ctx.logChooseHeader(choosePrefix + " → all Forwards' base power becomes " + allPower);
                selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                ctx.setAllForwardsBasePower(allPower);
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Power-becomes followup: "Its power becomes N until end of turn", either word order ---
        Matcher becomesM = FOLLOWUP_POWER_BECOMES.matcher(primaryFollowup);
        if (becomesM.find()) {
            int targetPower = powerBecomesAmount(becomesM);
            return ctx -> {
                ctx.logChooseHeader(choosePrefix + " → base power becomes " + targetPower);
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                // Descending order: dropping to the new power can break a Forward, which shifts
                // the indices of every target above it in the same zone.
                sortedByIdxDesc(ts, true) .forEach(t -> ctx.setTargetBasePower(t, targetPower));
                sortedByIdxDesc(ts, false).forEach(t -> ctx.setTargetBasePower(t, targetPower));
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Power boost followup (standard order: "it/they gains +N power [, traits] until…") ---
        Matcher boostM = FOLLOWUP_POWER_BOOST.matcher(primaryFollowup);
        if (boostM.find()) {
            int boost = Integer.parseInt(boostM.group(1));
            EnumSet<CardData.Trait> traits = parseTraits(boostM.group(2));
            String logSuffix = boostLogSuffix(boost, traits);
            return ctx -> {
                ctx.logChooseHeader(choosePrefix + logSuffix);
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                sortedByIdxDesc(ts, true) .forEach(t -> ctx.boostTarget(t, boost, traits));
                sortedByIdxDesc(ts, false).forEach(t -> ctx.boostTarget(t, boost, traits));
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Power boost for each [element | Category X] [type] you control (must precede plain UNTIL boost) ---
        Matcher boostForEachM = FOLLOWUP_POWER_BOOST_UNTIL_FOR_EACH.matcher(primaryFollowup);
        if (boostForEachM.find()) {
            boolean untilPrefix = boostForEachM.group("amount") != null;
            int    perUnit    = Integer.parseInt(untilPrefix ? boostForEachM.group("amount") : boostForEachM.group("amount2"));
            String srcElem    = untilPrefix ? boostForEachM.group("element")  : boostForEachM.group("element2");
            String srcCat     = untilPrefix ? boostForEachM.group("category") : boostForEachM.group("category2");
            String srcTypeRaw = untilPrefix ? boostForEachM.group("chartype") : boostForEachM.group("chartype2");
            String srcType    = srcTypeRaw.toLowerCase();
            boolean cntFwd    = srcType.startsWith("forward") || srcType.startsWith("character");
            boolean cntBkp    = srcType.startsWith("backup")  || srcType.startsWith("character");
            boolean cntMon    = srcType.startsWith("monster")  || srcType.startsWith("character");
            String logSuffix  = " +" + perUnit + "×[" + (srcElem != null ? srcElem + " " : "")
                              + (srcCat != null ? "Category " + srcCat + " " : "") + srcTypeRaw + " you control] until EOT";
            return ctx -> {
                int n      = ctx.countSelfFieldCards(cntFwd, cntBkp, cntMon, null, null, srcCat, srcElem);
                int boost  = perUnit * n;
                ctx.logChooseHeader(choosePrefix + logSuffix + " (n=" + n + ", boost=" + boost + ")");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                EnumSet<CardData.Trait> noTraits = EnumSet.noneOf(CardData.Trait.class);
                sortedByIdxDesc(ts, true) .forEach(t -> ctx.boostTarget(t, boost, noTraits));
                sortedByIdxDesc(ts, false).forEach(t -> ctx.boostTarget(t, boost, noTraits));
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Power boost for each Job [name] you control (must precede plain UNTIL boost) ---
        Matcher boostForEachJobM = FOLLOWUP_POWER_BOOST_UNTIL_FOR_EACH_JOB.matcher(primaryFollowup);
        if (boostForEachJobM.find()) {
            boolean untilPrefixJ = boostForEachJobM.group("amount") != null;
            int    perUnitJ  = Integer.parseInt(untilPrefixJ ? boostForEachJobM.group("amount") : boostForEachJobM.group("amount2"));
            String jobBracket = untilPrefixJ ? boostForEachJobM.group("jobb") : boostForEachJobM.group("jobb2");
            String jobWritten = untilPrefixJ ? boostForEachJobM.group("jobw") : boostForEachJobM.group("jobw2");
            String jobTypeStr = untilPrefixJ ? boostForEachJobM.group("jobt") : boostForEachJobM.group("jobt2");
            String jobNameJ   = (jobBracket != null ? jobBracket : jobWritten).trim();
            boolean jwFwd = jobTypeStr == null || jobTypeStr.matches("(?i)Forwards?");
            boolean jwBkp = jobTypeStr == null || jobTypeStr.matches("(?i)Backups?");
            boolean jwMon = jobTypeStr == null || jobTypeStr.matches("(?i)Monsters?");
            String logSuffixJ = " +" + perUnitJ + "×[Job " + jobNameJ + " you control] until EOT";
            return ctx -> {
                int n     = ctx.countSelfFieldCards(jwFwd, jwBkp, jwMon, jobNameJ, null);
                int boost = perUnitJ * n;
                ctx.logChooseHeader(choosePrefix + logSuffixJ + " (n=" + n + ", boost=" + boost + ")");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                EnumSet<CardData.Trait> noTraits = EnumSet.noneOf(CardData.Trait.class);
                sortedByIdxDesc(ts, true) .forEach(t -> ctx.boostTarget(t, boost, noTraits));
                sortedByIdxDesc(ts, false).forEach(t -> ctx.boostTarget(t, boost, noTraits));
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- "Until…, it gains +N power for each [Name] Counter placed on [card]." (counter-scaled xValue) ---
        // Must be checked before FOLLOWUP_POWER_BOOST_UNTIL, which would match only the +N and drop the for-each.
        Matcher boostForEachCounterM = FOLLOWUP_POWER_BOOST_UNTIL_FOR_EACH_COUNTER.matcher(primaryFollowup);
        if (boostForEachCounterM.find()) {
            int perUnit = Integer.parseInt(boostForEachCounterM.group("perunit"));
            String counterName = boostForEachCounterM.group("counterName").trim();
            return ctx -> {
                int boost = perUnit * xValue;
                ctx.logChooseHeader(choosePrefix + " — +" + perUnit + " power ×" + xValue + " " + counterName + " Counter(s) = +" + boost + " until EOT");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters,
                        jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                EnumSet<CardData.Trait> noTraits = EnumSet.noneOf(CardData.Trait.class);
                sortedByIdxDesc(ts, true) .forEach(t -> ctx.boostTarget(t, boost, noTraits));
                sortedByIdxDesc(ts, false).forEach(t -> ctx.boostTarget(t, boost, noTraits));
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- "Until…, it gains +N power for each point of damage you have received." ---
        // Must be checked before FOLLOWUP_POWER_BOOST_UNTIL, which matches on the +N and drops the for-each.
        Matcher boostUntilSelfDmgM = FOLLOWUP_POWER_BOOST_UNTIL_FOR_EACH_SELF_DMG.matcher(primaryFollowup);
        if (boostUntilSelfDmgM.find()) {
            int perUnit = Integer.parseInt(boostUntilSelfDmgM.group("perunit"));
            return ctx -> {
                int dmgCount = ctx.p1DamageCount();
                int boost    = perUnit * dmgCount;
                ctx.logChooseHeader(choosePrefix + " — +"+perUnit+" power ×" + dmgCount + " damage = +" + boost + " power until EOT");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters,
                        jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                EnumSet<CardData.Trait> noTraits = EnumSet.noneOf(CardData.Trait.class);
                sortedByIdxDesc(ts, true) .forEach(t -> ctx.boostTarget(t, boost, noTraits));
                sortedByIdxDesc(ts, false).forEach(t -> ctx.boostTarget(t, boost, noTraits));
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Power boost followup (until-prefix order: "Until…, it/they gains +N power [and traits]") ---
        Matcher boostUntilM = FOLLOWUP_POWER_BOOST_UNTIL.matcher(primaryFollowup);
        if (boostUntilM.find()) {
            int boost = Integer.parseInt(boostUntilM.group(1));
            EnumSet<CardData.Trait> traits = parseTraits(boostUntilM.group(2));
            String logSuffix = boostLogSuffix(boost, traits);

            // Detect "If its power has become N or less/more, return [name] to hand" secondary
            // and handle it inline so we have access to the target list for the power check.
            final String    crCard;
            final int       crThreshold;
            final boolean   crOrLess;
            final boolean   crToOwner;
            final Consumer<GameContext> boostSecondary;
            {
                Matcher crM = secondaryText != null ? CONDITIONAL_POWER_RETURN.matcher(secondaryText) : null;
                if (crM != null && crM.find()) {
                    crCard       = crM.group("name").trim();
                    crThreshold  = Integer.parseInt(crM.group("threshold"));
                    crOrLess     = "less".equalsIgnoreCase(crM.group("cmp"));
                    crToOwner    = crM.group("toowner") != null;
                    boostSecondary = null;
                } else {
                    crCard       = null;
                    crThreshold  = 0;
                    crOrLess     = false;
                    crToOwner    = false;
                    boostSecondary = secondary;
                }
            }

            return ctx -> {
                ctx.logChooseHeader(choosePrefix + logSuffix);
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                sortedByIdxDesc(ts, true) .forEach(t -> ctx.boostTarget(t, boost, traits));
                sortedByIdxDesc(ts, false).forEach(t -> ctx.boostTarget(t, boost, traits));
                if (crCard != null) {
                    boolean condMet = ts.stream().anyMatch(t -> {
                        int p = ctx.effectiveTargetPower(t);
                        return crOrLess ? p <= crThreshold : p >= crThreshold;
                    });
                    if (condMet) {
                        ctx.logEntry("Condition met (power " + (crOrLess ? "≤ " : "≥ ") + crThreshold + "): return " + crCard + " to " + (crToOwner ? "owner's" : "your") + " hand");
                        if (crToOwner) ctx.returnNamedCardToOwnersHand(crCard);
                        else           ctx.returnNamedCardToYourHand(crCard);
                    } else {
                        ctx.logEntry("Condition not met: " + crCard + " stays (power " + (crOrLess ? "> " : "< ") + crThreshold + ")");
                    }
                } else if (boostSecondary != null) {
                    boostSecondary.accept(ctx);
                }
            };
        }

        // --- Trait-choice grant followup: "it gains [T1] or [T2] until end of turn" ---
        Matcher choiceM = FOLLOWUP_KEYWORD_GRANT_CHOICE.matcher(primaryFollowup);
        if (choiceM.find()) {
            String t1Name = choiceM.group("t1").trim();
            String t2Name = choiceM.group("t2").trim();
            EnumSet<CardData.Trait> t1Traits = parseTraits(t1Name);
            EnumSet<CardData.Trait> t2Traits = parseTraits(t2Name);
            return ctx -> {
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters,
                        jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                if (ts.isEmpty()) return;
                String chosen = ctx.selectOption("Grant " + t1Name + " or " + t2Name + "?",
                        new String[]{t1Name, t2Name});
                EnumSet<CardData.Trait> traits = (chosen != null && chosen.equalsIgnoreCase(t2Name)) ? t2Traits : t1Traits;
                String logLabel = chosen != null ? chosen : t1Name;
                ctx.logChooseHeader(choosePrefix + " — grants " + logLabel);
                sortedByIdxDesc(ts, true) .forEach(t -> ctx.boostTarget(t, 0, traits));
                sortedByIdxDesc(ts, false).forEach(t -> ctx.boostTarget(t, 0, traits));
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Keyword-only grant followup: "it/they gains Haste [and …] until end of turn" ---
        Matcher keywordM = FOLLOWUP_KEYWORD_GRANT.matcher(primaryFollowup);
        if (keywordM.find()) {
            EnumSet<CardData.Trait> traits = parseTraits(keywordM.group(1));
            String logSuffix = boostLogSuffix(0, traits);
            return ctx -> {
                ctx.logChooseHeader(choosePrefix + logSuffix);
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                sortedByIdxDesc(ts, true) .forEach(t -> ctx.boostTarget(t, 0, traits));
                sortedByIdxDesc(ts, false).forEach(t -> ctx.boostTarget(t, 0, traits));
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Keyword-only grant followup (EOT prefix: "Until end of turn, it gains Haste [and …]") ---
        Matcher keywordUntilM = FOLLOWUP_KEYWORD_GRANT_UNTIL.matcher(primaryFollowup);
        if (keywordUntilM.find()) {
            EnumSet<CardData.Trait> traits = parseTraits(keywordUntilM.group(1));
            String logSuffix = boostLogSuffix(0, traits);
            return ctx -> {
                ctx.logChooseHeader(choosePrefix + logSuffix);
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                sortedByIdxDesc(ts, true) .forEach(t -> ctx.boostTarget(t, 0, traits));
                sortedByIdxDesc(ts, false).forEach(t -> ctx.boostTarget(t, 0, traits));
                if (secondary != null) secondary.accept(ctx);
            };
        }


        // =====================================================================================
        // Power reduction
        // =====================================================================================
        // --- Halve power followup ("Halve its power until EOT (round down to the nearest 1000)") ---
        // Expressed as a reduction rather than a new base power, because that is what it is: a
        // Forward halved while carrying a +3000 lend keeps the lend, and the lend is part of what
        // gets halved. So the amount is read per target, off the power it has when this resolves.
        if (FOLLOWUP_HALVE_POWER.matcher(primaryFollowup.trim()).matches()) {
            return ctx -> {
                ctx.logChooseHeader(choosePrefix + " — Halve power until EOT (round down to nearest 1000)");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters,
                        jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                // Descending order, as the plain reduce branch below does: losing power can break
                // a Forward, which shifts the indices of every target above it in the same zone.
                EnumSet<CardData.Trait> none = EnumSet.noneOf(CardData.Trait.class);
                Consumer<ForwardTarget> halve = t -> {
                    int power = ctx.effectiveTargetPower(t);
                    if (power > 0) ctx.reduceTarget(t, power - halfPowerRoundedDown(power), none);
                };
                sortedByIdxDesc(ts, true) .forEach(halve);
                sortedByIdxDesc(ts, false).forEach(halve);
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Power / trait reduce followup (standard order: "it/they loses N power [, traits] until…") ---
        Matcher reduceM = FOLLOWUP_POWER_REDUCE.matcher(primaryFollowup);
        if (reduceM.find()) {
            int reduction = reduceM.group(1) != null ? Integer.parseInt(reduceM.group(1)) : 0;
            EnumSet<CardData.Trait> traits = parseTraits(reduceM.group(2));
            String logSuffix = reduceLogSuffix(reduction, traits);
            return ctx -> {
                ctx.logChooseHeader(choosePrefix + logSuffix);
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                sortedByIdxDesc(ts, true) .forEach(t -> ctx.reduceTarget(t, reduction, traits));
                sortedByIdxDesc(ts, false).forEach(t -> ctx.reduceTarget(t, reduction, traits));
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Power reduce for each card in your hand ("Until…, it/they loses N power for each card in your hand") ---
        Matcher reduceForEachHandM = FOLLOWUP_POWER_REDUCE_UNTIL_FOR_EACH_HAND.matcher(primaryFollowup);
        if (reduceForEachHandM.find()) {
            int perCard = Integer.parseInt(reduceForEachHandM.group(1));
            // "If its power has become 0 or less by the previous effect, draw N card." (10-110C
            // Cúchulainn). Handled here rather than as a detached secondary because reduceTarget
            // runs the 0-power rule process on the spot: by the time a separate clause could look
            // at the Forward it is already in the Break Zone. The test is therefore made against
            // the power the reduction is about to produce, before applying it.
            final int drawIfZeroed;
            {
                Matcher zeroDrawM = secondaryText != null
                        ? FOLLOWUP_IF_POWER_BECAME_ZERO_DRAW.matcher(secondaryText) : null;
                drawIfZeroed = zeroDrawM != null && zeroDrawM.matches()
                        ? Integer.parseInt(zeroDrawM.group("draw")) : 0;
            }
            return ctx -> {
                int n = ctx.yourHandSize();
                int reduction = perCard * n;
                ctx.logChooseHeader(choosePrefix + " -" + perCard + "×[your hand] until EOT (n=" + n + ", reduction=" + reduction + ")");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                EnumSet<CardData.Trait> noTraits = EnumSet.noneOf(CardData.Trait.class);
                boolean anyZeroed = false;
                if (drawIfZeroed > 0)
                    for (ForwardTarget t : ts)
                        if (ctx.effectiveTargetPower(t) - reduction <= 0) { anyZeroed = true; break; }
                sortedByIdxDesc(ts, true) .forEach(t -> ctx.reduceTarget(t, reduction, noTraits));
                sortedByIdxDesc(ts, false).forEach(t -> ctx.reduceTarget(t, reduction, noTraits));
                if (anyZeroed) {
                    ctx.logEntry("Effect: power reduced to 0 or less — draw " + drawIfZeroed);
                    ctx.drawCards(drawIfZeroed);
                }
                // The draw clause is consumed above; anything else still runs as the secondary.
                if (drawIfZeroed == 0 && secondary != null) secondary.accept(ctx);
            };
        }

        // --- Power reduce for each [state] [element] [type] you control / opponent controls
        //     (must precede plain UNTIL reduce) ---
        // --- Power reduction scaled by the attacking party (12-105L Yuna) ---
        // Ahead of every other reduction branch: they all find() a bare "it loses N power" inside
        // this sentence and would drop the multiplier.
        Matcher reduceForEachAtkM = FOLLOWUP_POWER_REDUCE_UNTIL_FOR_EACH_ATTACKER.matcher(primaryFollowup.trim());
        if (reduceForEachAtkM.matches()) {
            int perAttacker = Integer.parseInt(reduceForEachAtkM.group("amount"));
            return ctx -> {
                int attackers = ctx.currentPartyAttackerCount();
                int reduction = perAttacker * attackers;
                ctx.logChooseHeader(choosePrefix + " -" + perAttacker + "×[attacking Forwards] until EOT (n="
                        + attackers + ", reduction=" + reduction + ")");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                EnumSet<CardData.Trait> noTraits = EnumSet.noneOf(CardData.Trait.class);
                // Descending order: the reduction can break a Forward, which shifts the indices of
                // every target above it in the same zone.
                sortedByIdxDesc(ts, true) .forEach(t -> ctx.reduceTarget(t, reduction, noTraits));
                sortedByIdxDesc(ts, false).forEach(t -> ctx.reduceTarget(t, reduction, noTraits));
                if (secondary != null) secondary.accept(ctx);
            };
        }

        Matcher reduceForEachM = FOLLOWUP_POWER_REDUCE_UNTIL_FOR_EACH.matcher(primaryFollowup);
        // A state adjective is only countable on the opponent's side: the counting surface has an
        // opponent call taking one but no self-side equivalent, so a self-side "for each dull …"
        // would silently count every card of the type. Fall through instead — no printing has it.
        if (reduceForEachM.find() && !(reduceForEachSelfState(reduceForEachM))) {
            boolean untilPrefix = reduceForEachM.group("amount") != null;
            int    perUnit    = Integer.parseInt(untilPrefix ? reduceForEachM.group("amount") : reduceForEachM.group("amount2"));
            String srcElem    = untilPrefix ? reduceForEachM.group("element") : reduceForEachM.group("element2");
            String srcState   = untilPrefix ? reduceForEachM.group("state")   : reduceForEachM.group("state2");
            boolean srcOpp    = (untilPrefix ? reduceForEachM.group("opp")    : reduceForEachM.group("opp2")) != null;
            String srcType    = (untilPrefix ? reduceForEachM.group("chartype") : reduceForEachM.group("chartype2")).toLowerCase();
            boolean cntFwd    = srcType.startsWith("forward") || srcType.startsWith("character");
            boolean cntBkp    = srcType.startsWith("backup")  || srcType.startsWith("character");
            boolean cntMon    = srcType.startsWith("monster")  || srcType.startsWith("character");
            String typeLabel  = untilPrefix ? reduceForEachM.group("chartype") : reduceForEachM.group("chartype2");
            String logSuffix  = " -" + perUnit + "×[" + (srcState != null ? srcState + " " : "")
                    + (srcElem != null ? srcElem + " " : "") + typeLabel
                    + (srcOpp ? " opponent controls" : " you control") + "] until EOT";
            return ctx -> {
                int n         = countForEachPowerSource(ctx, srcOpp, srcState, srcElem, cntFwd, cntBkp, cntMon);
                int reduction = perUnit * n;
                ctx.logChooseHeader(choosePrefix + logSuffix + " (n=" + n + ", reduction=" + reduction + ")");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                EnumSet<CardData.Trait> noTraits = EnumSet.noneOf(CardData.Trait.class);
                sortedByIdxDesc(ts, true) .forEach(t -> ctx.reduceTarget(t, reduction, noTraits));
                sortedByIdxDesc(ts, false).forEach(t -> ctx.reduceTarget(t, reduction, noTraits));
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Power / trait reduce followup (until-prefix order: "Until…, it/they loses N power [and traits]") ---
        Matcher reduceUntilM = FOLLOWUP_POWER_REDUCE_UNTIL.matcher(primaryFollowup);
        if (reduceUntilM.find()) {
            int reduction = reduceUntilM.group(1) != null ? Integer.parseInt(reduceUntilM.group(1)) : 0;
            EnumSet<CardData.Trait> traits = parseTraits(reduceUntilM.group(2));
            String logSuffix = reduceLogSuffix(reduction, traits);
            return ctx -> {
                ctx.logChooseHeader(choosePrefix + logSuffix);
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                sortedByIdxDesc(ts, true) .forEach(t -> ctx.reduceTarget(t, reduction, traits));
                sortedByIdxDesc(ts, false).forEach(t -> ctx.reduceTarget(t, reduction, traits));
                if (secondary != null) secondary.accept(ctx);
            };
        }


        // =====================================================================================
        // Opponent discard, self boost and cancel
        // =====================================================================================
        // --- Opponent discard followup ---
        Matcher discardM = OPPONENT_DISCARD.matcher(primaryFollowup);
        if (discardM.find()) {
            int count = Integer.parseInt(discardM.group(1));
            return ctx -> {
                ctx.logChooseHeader(choosePrefix + " — Opponent discards " + count);
                ctx.forceOpponentDiscard(count);
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Self-referential boost followup: "<cardName> gains [+N power] [traits] until end of turn" ---
        if (source != null) {
            Matcher selfM = SELF_POWER_BOOST.matcher(primaryFollowup);
            if (selfM.find() && selfM.group("selfsubject").trim().equalsIgnoreCase(source.name())) {
                int boost = selfM.group("selfamount") != null ? Integer.parseInt(selfM.group("selfamount")) : 0;
                EnumSet<CardData.Trait> traits = parseTraits(selfM.group("selftraits"));
                String logSuffix = boostLogSuffix(boost, traits);
                return ctx -> {
                    ctx.logChooseHeader(choosePrefix + " — " + source.name() + logSuffix);
                    ctx.boostSourceForward(source, boost, traits);
                    if (secondary != null) secondary.accept(ctx);
                };
            }
        }

        // --- Cancel effect followup (counters a Summon on the stack) ---
        if (FOLLOWUP_CANCEL_EFFECT.matcher(primaryFollowup).find()) {
            return ctx -> {
                ctx.logChooseHeader(choosePrefix + " — Cancel its effect");
                ctx.cancelStackEntry();
                if (secondary != null) secondary.accept(ctx);
            };
        }


        // =====================================================================================
        // Damage shields and cannot-be-broken
        // =====================================================================================
        // --- Next damage from the opponent's Summons or abilities = 0 followup (Auron 22-001R) ---
        // Must precede the unqualified shield below: that pattern ends at "dealt to it" and would
        // claim this sentence under find(), shielding combat damage the printing does not mention.
        if (FOLLOWUP_SHIELD_NEXT_OPP_EFFECT_DMG_ZERO.matcher(primaryFollowup).find()) {
            return ctx -> {
                ctx.logChooseHeader(choosePrefix + " — Shield: next damage from opponent's Summons or abilities becomes 0");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                ts.forEach(ctx::shieldNextOpponentEffectDamage);
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Next incoming damage = 0 followup ---
        if (FOLLOWUP_SHIELD_NEXT_DMG_ZERO.matcher(primaryFollowup).find()) {
            return ctx -> {
                ctx.logChooseHeader(choosePrefix + " — Shield: next damage becomes 0");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                ts.forEach(ctx::shieldNextIncomingDamage);
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Next ability/summon damage reduced by N followup ---
        Matcher shieldAbilRedM = FOLLOWUP_SHIELD_NEXT_ABILITY_DMG_REDUCTION.matcher(primaryFollowup);
        if (shieldAbilRedM.find()) {
            int reduction = Integer.parseInt(shieldAbilRedM.group("reduction"));
            return ctx -> {
                ctx.logChooseHeader(choosePrefix + " — Shield: next ability/summon damage reduced by " + reduction);
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                ts.forEach(t -> ctx.shieldNextAbilityIncomingDamageReduction(t, reduction));
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Next incoming damage reduced by N, and the grantor takes M for it ---
        // Ahead of the plain reduction below: see the note on the pattern.
        Matcher shieldKickM = FOLLOWUP_SHIELD_NEXT_DMG_REDUCTION_KICKBACK.matcher(primaryFollowup);
        if (source != null && shieldKickM.find()
                && shieldKickM.group("name").trim().equalsIgnoreCase(source.name())) {
            int reduction = Integer.parseInt(shieldKickM.group("reduction"));
            int kickback  = Integer.parseInt(shieldKickM.group("dmg"));
            return ctx -> {
                ctx.logChooseHeader(choosePrefix + " — Shield: next damage reduced by " + reduction
                        + ", " + source.name() + " takes " + kickback + " for it");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                ts.forEach(t -> ctx.shieldNextIncomingDamageReductionKickback(
                        t, reduction, source, kickback));
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Next incoming damage reduced by N followup ---
        Matcher shieldRedM = FOLLOWUP_SHIELD_NEXT_DMG_REDUCTION.matcher(primaryFollowup);
        if (shieldRedM.find()) {
            int reduction = Integer.parseInt(shieldRedM.group("reduction"));
            return ctx -> {
                ctx.logChooseHeader(choosePrefix + " — Shield: next damage reduced by " + reduction);
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                ts.forEach(t -> ctx.shieldNextIncomingDamageReduction(t, reduction));
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Incoming damage increased by N followup ---
        Matcher dmgIncM = FOLLOWUP_DEBUFF_INCOMING_DMG_INCREASE.matcher(primaryFollowup);
        if (dmgIncM.find()) {
            int amount = Integer.parseInt(dmgIncM.group("amount"));
            return ctx -> {
                ctx.logChooseHeader(choosePrefix + " — Debuff: incoming damage increased by " + amount);
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                ts.forEach(t -> ctx.debuffIncomingDamageIncrease(t, amount));
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Next outgoing damage doubled followup ---
        if (FOLLOWUP_DOUBLE_NEXT_OUTGOING.matcher(primaryFollowup).find()) {
            return ctx -> {
                ctx.logChooseHeader(choosePrefix + " — next outgoing damage doubled this turn");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                ts.forEach(ctx::doubleForwardNextOutgoingDamage);
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Next outgoing damage = 0 followup ---
        if (FOLLOWUP_SHIELD_NEXT_OUTGOING_ZERO.matcher(primaryFollowup).find()) {
            return ctx -> {
                ctx.logChooseHeader(choosePrefix + " — Shield: next outgoing damage becomes 0");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                ts.forEach(ctx::shieldNextOutgoingDamage);
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Per-card non-lethal protection followup ---
        if (FOLLOWUP_SHIELD_NONLETHAL.matcher(primaryFollowup).find()) {
            return ctx -> {
                ctx.logChooseHeader(choosePrefix + " — Shield: damage less than power becomes 0 this turn");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                ts.forEach(ctx::shieldNonLethal);
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- "It gains ability-damage shield" followup ---
        if (FOLLOWUP_GAINS_SHIELD_ABILITY_ONLY.matcher(primaryFollowup).find()) {
            return ctx -> {
                ctx.logChooseHeader(choosePrefix + " — Shield: gains ability-damage nullification until end of turn");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                ts.forEach(ctx::shieldAbilityOnlyDamage);
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- "Cannot be broken" until end of turn ---
        if (FOLLOWUP_CANNOT_BE_BROKEN.matcher(primaryFollowup).find()) {
            return ctx -> {
                ctx.logChooseHeader(choosePrefix + " — Shield: cannot be broken until end of turn");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                ts.forEach(ctx::shieldCannotBeBroken);
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- "It cannot be broken this turn." (simple form) ---
        if (FOLLOWUP_CANNOT_BE_BROKEN_SIMPLE.matcher(primaryFollowup).find()) {
            return ctx -> {
                ctx.logChooseHeader(choosePrefix + " — Shield: cannot be broken this turn");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                ts.forEach(ctx::shieldCannotBeBroken);
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- "Cannot be broken by opposing Summons or abilities that don't deal damage" ---
        if (FOLLOWUP_CANNOT_BE_BROKEN_BY_NON_DMG.matcher(primaryFollowup).find()) {
            return ctx -> {
                ctx.logChooseHeader(choosePrefix + " — Shield: cannot be broken by opposing non-damage effects this turn");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                ts.forEach(ctx::shieldCannotBeBrokenByNonDmg);
                if (secondary != null) secondary.accept(ctx);
            };
        }


        // =====================================================================================
        // Granted abilities and end-of-turn riders
        // =====================================================================================
        // --- "[Self] gains his/her action abilities until EOT." (Gogo 9-107C) ---
        // The one followup here that acts on the source rather than on what was chosen: the
        // chosen Forward is the donor, and its abilities are re-pointed at the borrower on the
        // way across, exactly as Gogo's Mimic re-points a copied special.
        Matcher gainActionsM = FOLLOWUP_SOURCE_GAINS_TARGET_ACTION_ABILITIES.matcher(primaryFollowup);
        if (source != null && gainActionsM.matches()
                && restorePeriodInName(gainActionsM.group("name").trim(), source)
                        .equalsIgnoreCase(source.name())) {
            final CardData borrower = source;
            return ctx -> {
                ctx.logChooseHeader(choosePrefix + " — " + borrower.name()
                        + " gains its action abilities until end of turn");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                ts.forEach(t -> ctx.gainTargetActionAbilitiesUntilEndOfTurn(borrower, t));
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- "When this Forward is dealt damage, break this Forward." until EOT (Vallaide 22-020R) ---
        if (FOLLOWUP_GAINS_BREAK_WHEN_DEALT_DAMAGE.matcher(primaryFollowup).find()) {
            return ctx -> {
                ctx.logChooseHeader(choosePrefix + " — breaks when dealt damage, until end of turn");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                ts.forEach(ctx::grantBreakWhenDealtDamage);
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- Breaktouch battle: "When this Forward deals battle damage to a Forward, break that Forward" until EOT ---
        if (FOLLOWUP_GAINS_BREAKTOUCH_BATTLE.matcher(primaryFollowup).find()) {
            return ctx -> {
                ctx.logChooseHeader(choosePrefix + " — Breaktouch (battle damage) until end of turn");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                ts.forEach(ctx::shieldBreaktouchBattle);
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- End-of-turn conditional damage followup ---
        // e.g. "At the end of this turn, if you control <name>, deal it N damage."
        Matcher eotDmgM = FOLLOWUP_END_OF_TURN_COND_DAMAGE.matcher(primaryFollowup);
        if (eotDmgM.find()) {
            String condCard = eotDmgM.group("cardName").trim();
            int damage      = Integer.parseInt(eotDmgM.group("damage"));
            return ctx -> {
                ctx.logChooseHeader(choosePrefix + " — End of turn: if you control " + condCard + ", deal " + damage + " damage");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                if (!ts.isEmpty()) {
                    ctx.addEndOfTurnEffect(endCtx -> {
                        if (endCtx.abilityUserControlsCard(condCard)) {
                            sortedByIdxDesc(ts, true) .forEach(t -> endCtx.damageTarget(t, damage));
                            sortedByIdxDesc(ts, false).forEach(t -> endCtx.damageTarget(t, damage));
                        } else {
                            endCtx.logEntry("End-of-turn damage skipped: " + condCard + " not on field");
                        }
                    });
                }
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- "Select a Job. It gains that Job until the end of the turn." ---
        // Checked against the full followup (before dot-split) so both sentences are seen together.
        if (FOLLOWUP_SELECT_JOB_GRANT.matcher(followup).find()) {
            return ctx -> {
                ctx.logChooseHeader(choosePrefix + " — Select a Job, grant until end of turn");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters,
                        jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                if (ts.isEmpty()) return;
                String job = ctx.selectJobFromDatabase();
                if (job == null || job.isBlank()) return;
                ts.forEach(t -> ctx.grantJobUntilEndOfTurn(t, job));
            };
        }

        // --- "If it deals damage to a Forward this turn, the damage increases by N instead." ---
        Matcher outBoostM = FOLLOWUP_OUTGOING_DMG_BOOST_THIS_TURN.matcher(primaryFollowup);
        if (outBoostM.find()) {
            int amount = Integer.parseInt(outBoostM.group("amount"));
            return ctx -> {
                ctx.logChooseHeader(choosePrefix + " — Outgoing damage +" + amount + " to Forwards this turn");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                ts.forEach(t -> ctx.boostForwardOutgoingDamageThisTurn(t, amount));
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // --- "Until the end of the turn, it also becomes a Forward with N power." (Gau) ---
        Matcher becomeFwdM = BECOME_FORWARD_UNTIL_EOT_PATTERN.matcher(primaryFollowup);
        if (becomeFwdM.find()) {
            int power = Integer.parseInt(becomeFwdM.group("power"));
            return ctx -> {
                ctx.logChooseHeader(choosePrefix + " — becomes a Forward with " + power + " power until end of turn");
                List<ForwardTarget> ts = selectTargets(ctx, maxCount, upTo,
                        opponentOnly, selfOnly, condition, element, zone, opponentZone,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, fExcludeElem, withoutMulticard);
                ts.forEach(t -> ctx.makeTargetTemporaryForward(t, power));
                if (secondary != null) secondary.accept(ctx);
            };
        }

        // Recognised "Choose" header but followup not yet implemented
        Consumer<GameContext> warnEffect = ctx -> ctx.logEntry(
                "[ActionResolver] Choose effect — followup not yet implemented: " + followup);
        return secondary == null ? warnEffect : warnEffect.andThen(secondary);
    }

    // =========================================================================================
    // Standalone choose parsers
    // =========================================================================================
    /**
     * Index of the first ". " sentence boundary of {@code text} that lies outside every quoted
     * span, or -1 when there is none.
     *
     * <p>Quote-awareness is what makes a two-sentence <em>quotation</em> survive the primary /
     * secondary split. Lich 21-079R's followup is
     * {@code It gains "At the end of each of your turns, break this Forward. (This effect does not
     * end at the end of the turn.)"} — a plain {@code indexOf(". ")} cut it at the period inside
     * the quotes, leaving a primary with an unbalanced quote that no followup pattern could claim
     * and the reminder text stranded as the secondary.
     */
    static int sentenceBreakOutsideQuotes(String text) {
        boolean inQuotes = false;
        for (int i = 0; i < text.length() - 1; i++) {
            char c = text.charAt(i);
            if (c == '"') { inQuotes = !inQuotes; continue; }
            if (!inQuotes && c == '.' && text.charAt(i + 1) == ' ') return i;
        }
        return -1;
    }
    static Consumer<GameContext> tryParseChooseForwardDoubleIncomingThisTurn(String text) {
        if (!CHOOSE_FORWARD_DOUBLE_INCOMING_THIS_TURN.matcher(text).find()) return null;
        return ctx -> {
            ctx.logEntry("Choose 1 Forward — incoming damage doubled this turn");
            List<ForwardTarget> ts = ctx.selectCharacters(1, false, false, false,
                    null, null, -1, null, -1, null, true, false, false,
                    null, null, null, null, false, null, false);
            if (!ts.isEmpty()) ctx.doubleForwardIncomingDamageThisTurn(ts.get(0));
        };
    }
    static Consumer<GameContext> tryParseChooseForwardDoubleNextOutgoing(String text) {
        Matcher m = CHOOSE_FORWARD_DOUBLE_NEXT_OUTGOING.matcher(text);
        if (!m.find()) return null;
        String rawJob = m.group("job");
        final String jobFilter = rawJob != null ? rawJob.trim() : null;
        return ctx -> {
            String label = jobFilter != null ? "Job " + jobFilter + " " : "";
            ctx.logEntry("Choose 1 " + label + "Forward — next outgoing damage doubled this turn");
            List<ForwardTarget> ts = ctx.selectCharacters(1, false, false, false,
                    null, null, -1, null, -1, null, true, false, false,
                    jobFilter, null, null, null, false, null, false);
            if (!ts.isEmpty()) ctx.doubleForwardNextOutgoingDamage(ts.get(0));
        };
    }
    /**
     * Parses "Choose 1 card removed by [SourceName]'s ability. Put it into the Break Zone." —
     * requires the named card to be the ability source so the exile tracking can be looked up
     * by instance identity.
     */
    static Consumer<GameContext> tryParseChooseCardRemovedBySourceToBz(String text, CardData source) {
        Matcher m = CHOOSE_CARD_REMOVED_BY_SOURCE_TO_BZ.matcher(text.trim());
        if (!m.matches()) return null;
        if (source == null || !m.group("name").trim().equalsIgnoreCase(source.name())) return null;
        return ctx -> ctx.putCardRemovedBySourceIntoBreakZone(source);
    }
    /** Parses "select 1 [Forward|Backup|Monster|Character] you control. Put it into the Break Zone." */
    static Consumer<GameContext> tryParseSelectControlledCharacterToBz(String text) {
        Matcher m = SELECT_1_CHARACTER_YOU_CONTROL_TO_BZ.matcher(text.trim());
        if (!m.matches()) return null;
        String type    = m.group("type");
        boolean inclFwd = type.matches("(?i)Forward|Character");
        boolean inclBkp = type.matches("(?i)Backup|Character");
        boolean inclMon = type.matches("(?i)Monster|Character");
        return ctx -> {
            ctx.logEntry("Effect: select 1 " + type + " you control → Break Zone");
            ctx.selectControlledTypeAndBreak(inclFwd, inclBkp, inclMon);
        };
    }
    /**
     * Parses a bare "Cancel its/their effect(s)." — the consequent of a reactive "chosen by opponent's
     * Summons or abilities" auto-ability whose optional cost was already paid upstream (Phantasmal
     * Girl, Regis, Tama, Yuna). Unconditionally cancels the in-progress selection.
     */
    static Consumer<GameContext> tryParseCancelChosenTargetBare(String text) {
        if (!CANCEL_CHOSEN_TARGET_BARE.matcher(text.trim()).find()) return null;
        return ctx -> {
            ctx.logEntry("Effect: cancel the effect choosing your Character(s)");
            ctx.cancelChosenSelection();
        };
    }
    /**
     * Parses a bare "Cancel the Summon's effect." — the sub-effect of Clione 4-125C, split off its
     * "put Clione into the Break Zone. If you do so, …" wrapper by
     * {@code AutoAbilityTriggers.executePutSelfIntoBzIfDoSoAutoAbility} before it reaches here.
     *
     * <p>The named sibling of {@link #tryParseCancelChosenTargetBare}: that one answers a selection
     * still in progress, this one cancels the Summon on the Stack that the ability triggered off.
     */
    static Consumer<GameContext> tryParseCancelTriggeringSummon(String text) {
        if (!CANCEL_TRIGGERING_SUMMON.matcher(text.trim()).matches()) return null;
        return ctx -> {
            ctx.logEntry("Effect: cancel the Summon's effect");
            ctx.cancelTriggeringSummon();
        };
    }
    /**
     * Parses the two abilities that move a Stack entry's chosen target onto a different
     * permanent — Faris 21-114L and Edge 15-045H. See {@link TargetRedirect} for the two shapes.
     *
     * <p>All the deciding happens in {@link GameContext#redirectChosenTarget}: which entries
     * qualify and which replacements are legal both depend on resolving stored targets back to
     * cards, which only the board can do.
     */
    static Consumer<GameContext> tryParseRedirectChosenTarget(String text, CardData source) {
        TargetRedirect spec = targetRedirect(text, source);
        if (spec == null) return null;
        return ctx -> ctx.redirectChosenTarget(spec, source);
    }
    /**
     * Parses "Select 1 number." abilities where the selected number is used as a cost filter
     * for a follow-on mass-field effect, damage sweep, or attack restriction.
     *
     * <p>Supported inner effects (appearing after "Select 1 number."):
     * <ul>
     *   <li>Any mass field action (Break/Dull/Freeze/Dull and Freeze) "of that cost" or
     *       "of the same cost as the selected number" — delegates to
     *       {@link GameContext#applyMassFieldEffect} with the chosen number as {@code costVal}.</li>
     *   <li>"All Forwards of that cost cannot attack this turn."</li>
     *   <li>"Deal N damage to all the Forwards of the same cost as the selected number [opponent controls]."</li>
     * </ul>
     * <p>Dual-selection variant: when "Your opponent selects 1 number." follows immediately,
     * both P1's and P2's numbers are obtained and the inner "Break all Forwards of cost equal
     * to either number." is applied for each.
     */
    static Consumer<GameContext> tryParseSelectNumber(String text, CardData source) {
        Matcher hm = SELECT_NUMBER_HEADER.matcher(text);
        if (!hm.find()) return null;

        String rest = text.substring(hm.end()).trim();

        // Dual-selection variant: "Your opponent selects 1 number."
        Matcher om = SELECT_NUMBER_OPPONENT_ALSO.matcher(rest);
        boolean dualSelect = om.find();
        if (dualSelect) rest = rest.substring(om.end()).trim();

        final String innerText = rest;

        // --- Dual variant: "Break all Forwards of cost equal to either number." ---
        // P1 selects via dialog; the opponent AI picks the cost most common among P1's forwards.
        if (dualSelect && SELECT_NUMBER_INNER_EITHER_BREAK.matcher(innerText).find()) {
            return ctx -> {
                int n1 = ctx.selectNumber(0, 11, "Select a number:");
                ctx.logEntry("Effect: Player selects number " + n1);
                int n2 = aiMostCommonP1ForwardCost(ctx);
                ctx.logEntry("Effect: Opponent selects number " + n2 + " (AI)");
                ctx.logEntry("Effect: Break all Forwards of cost " + n1
                        + (n1 != n2 ? " or " + n2 : ""));
                ctx.applyMassFieldEffect(GameContext.MassAction.BREAK,
                        true, false, false, false, false, null, n1, null, -1, null, null);
                if (n1 != n2)
                    ctx.applyMassFieldEffect(GameContext.MassAction.BREAK,
                            true, false, false, false, false, null, n2, null, -1, null, null);
            };
        }

        // --- "All Forwards of that cost cannot attack this turn." ---
        if (SELECT_NUMBER_INNER_CANNOT_ATTACK.matcher(innerText).find()) {
            return ctx -> {
                int n = ctx.selectNumber(0, 11, "Select a number:");
                ctx.logEntry("Effect: Select number " + n
                        + " — all Forwards of cost " + n + " cannot attack this turn");
                for (int i = 0; i < ctx.p1ForwardCount(); i++)
                    if (ctx.p1Forward(i).cost() == n) ctx.setP1ForwardCannotAttack(i);
                for (int i = 0; i < ctx.p2ForwardCount(); i++)
                    if (ctx.p2Forward(i).cost() == n) ctx.setP2ForwardCannotAttack(i);
            };
        }

        // --- General case: substitute the selected number into the inner text and re-parse. ---
        // Supported placeholders:
        //   "of that cost"                         → "of cost N"
        //   "the same cost as the selected number" → "cost N"  (e.g. inside DEAL_DAMAGE_TO_FORWARDS)
        String probeText = innerText
                .replaceAll("(?i)of\\s+that\\s+cost\\b", "of cost 3")
                .replaceAll("(?i)the\\s+same\\s+cost\\s+as\\s+the\\s+selected\\s+number", "cost 3");
        if (parse(probeText, source) == null) return null;  // inner effect not yet supported

        return ctx -> {
            int n = ctx.selectNumber(0, 11, "Select a number:");
            ctx.logEntry("Effect: Select number " + n);
            String resolved = innerText
                    .replaceAll("(?i)of\\s+that\\s+cost\\b", "of cost " + n)
                    .replaceAll("(?i)the\\s+same\\s+cost\\s+as\\s+the\\s+selected\\s+number",
                            "cost " + n);
            Consumer<GameContext> effect = parse(resolved, source);
            if (effect != null) {
                effect.accept(ctx);
            } else {
                ctx.logEntry("[ActionResolver] SelectNumber: inner effect not parseable: " + resolved);
            }
        };
    }
    /**
     * Parses Ardyn 8-068L's "Your opponent selects 1 Character he/she controls. He/she may put it
     * into the Break Zone. If he/she does so, [Self] cannot block this turn."
     *
     * <p>Self-named: the block restriction lands on the card printing the ability, so a text naming
     * anything else is declined rather than silently applied to the carrier. Ordered ahead of
     * {@link #tryParseOpponentSelects} — see the note on
     * {@link ActionResolverPatterns#OPP_SELECTS_MAY_BREAK_ELSE_SELF_CANNOT_BLOCK}.
     */
    /**
     * Parses Ardyn 28-002R's toll: "If that player doesn't put 1 Character they control into the
     * Break Zone, [Self] deals that player 1 point of damage."
     *
     * <p>Both halves fall to the context, because both name the turn player — the seat the trigger
     * this effect hangs off is firing for, which is not always the resolving player's opponent.
     */
    static Consumer<GameContext> tryParseTurnPlayerBreaksOrTakesDamage(String text, CardData source) {
        if (source == null) return null;
        Matcher m = TURN_PLAYER_BREAKS_OR_TAKES_DAMAGE.matcher(text.trim());
        if (!m.matches()) return null;
        if (!m.group("card").trim().equalsIgnoreCase(source.name())) return null;

        String  targets  = m.group("targets");
        String  tgtLower = targets.toLowerCase();
        boolean inclForwards = tgtLower.contains("forward") || tgtLower.contains("character");
        boolean inclBackups  = tgtLower.contains("backup")  || tgtLower.contains("character");
        boolean inclMonsters = tgtLower.contains("monster") || tgtLower.contains("character");
        int     damage   = Integer.parseInt(m.group("amount"));
        String  name     = source.name();
        return ctx -> {
            ctx.logEntry("Effect: the turn player puts 1 " + targets + " they control into the Break "
                    + "Zone, or takes " + damage + " point(s) of damage from " + name);
            ctx.turnPlayerBreaksOwnCharacterOrTakesDamage(inclForwards, inclBackups, inclMonsters, damage, name);
        };
    }

    static Consumer<GameContext> tryParseOppSelectsMayBreakElseSelfCannotBlock(String text, CardData source) {
        if (source == null) return null;
        Matcher m = OPP_SELECTS_MAY_BREAK_ELSE_SELF_CANNOT_BLOCK.matcher(text.trim());
        if (!m.matches()) return null;
        if (!m.group("card").trim().equalsIgnoreCase(source.name())) return null;

        String  targets  = m.group("targets");
        String  tgtLower = targets.toLowerCase();
        boolean inclForwards = tgtLower.contains("forward") || tgtLower.contains("character");
        boolean inclBackups  = tgtLower.contains("backup")  || tgtLower.contains("character");
        boolean inclMonsters = tgtLower.contains("monster") || tgtLower.contains("character");
        String  name     = source.name();
        return ctx -> {
            ctx.logEntry("Effect: opponent may put 1 " + targets + " they control into the Break Zone — "
                    + "if they do, " + name + " cannot block this turn");
            if (ctx.opponentMayBreakOwnCharacter(inclForwards, inclBackups, inclMonsters, name))
                ctx.grantSelfCannotBlockUntilEndOfTurn(source);
        };
    }

    /**
     * Parses "Your opponent selects N [condition] [type] [of cost C or less/more] they control
     * [sep] followup". Supported followups: "Put it into the Break Zone" and "dull/dulls it".
     */
    static Consumer<GameContext> tryParseOpponentSelects(String text) {
        Matcher m = OPPONENT_SELECTS_PATTERN.matcher(text);
        if (!m.find()) return null;

        int     count     = Integer.parseInt(m.group("count"));
        String  condition = m.group("condition");
        String  element   = m.group("element");
        String  targets   = m.group("targets");
        String  tgtLower  = targets.toLowerCase();
        boolean inclForwards = tgtLower.contains("forward") || tgtLower.contains("character");
        boolean inclBackups  = tgtLower.contains("backup")  || tgtLower.contains("character");
        boolean inclMonsters = tgtLower.contains("monster") || tgtLower.contains("character");
        String  followup  = m.group("followup").trim();
        int     costVal   = m.group("cost") != null ? Integer.parseInt(m.group("cost")) : -1;
        String  costCmp   = m.group("costcmp") != null ? m.group("costcmp").toLowerCase() : null;

        String prefix = "Opponent selects " + count
                + (condition != null ? " " + condition : "")
                + (element   != null ? " " + element   : "")
                + " " + targets
                + (costVal >= 0 ? " of cost " + costVal + " or " + costCmp : "")
                + " (opponent)";

        if (FOLLOWUP_PUT_TO_BREAK_ZONE.matcher(followup).find()) {
            return ctx -> {
                ctx.logEntry(prefix + " — Force to Break Zone");
                List<ForwardTarget> ts = ctx.selectCharacters(count, false, true, false,
                        condition, element, costVal, costCmp, -1, null,
                        inclForwards, inclBackups, inclMonsters, null, null, null, null, false, null, false);
                sortedByIdxDesc(ts, false).forEach(ctx::forceTargetToBreakZone);
            };
        }

        if (FOLLOWUP_DULL.matcher(followup).find()) {
            return ctx -> {
                ctx.logEntry(prefix + " — Dull");
                List<ForwardTarget> ts = ctx.selectCharacters(count, false, true, false,
                        condition, element, costVal, costCmp, -1, null,
                        inclForwards, inclBackups, inclMonsters, null, null, null, null, false, null, false);
                sortedByIdxDesc(ts, false).forEach(ctx::dullTarget);
            };
        }

        if (FOLLOWUP_RETURN_TO_OWNERS_HAND.matcher(followup).find()) {
            return ctx -> {
                ctx.logEntry(prefix + " — Return to owner's hand");
                List<ForwardTarget> ts = ctx.selectCharacters(count, false, true, false,
                        condition, element, costVal, costCmp, -1, null,
                        inclForwards, inclBackups, inclMonsters, null, null, null, null, false, null, false);
                sortedByIdxDesc(ts, false).forEach(t -> {
                    switch (t.zone()) {
                        case FORWARD -> { if (t.isP1()) ctx.returnP1ForwardToHand(t.idx());
                                          else          ctx.returnP2ForwardToHand(t.idx()); }
                        case BACKUP  -> { if (t.isP1()) ctx.returnP1BackupToHand(t.idx());
                                          else          ctx.returnP2BackupToHand(t.idx()); }
                        case MONSTER -> { if (t.isP1()) ctx.returnP1MonsterToHand(t.idx());
                                          else          ctx.returnP2MonsterToHand(t.idx()); }
                    }
                });
            };
        }

        return ctx -> ctx.logEntry(
                "[ActionResolver] Opponent selects — followup not yet implemented: " + followup);
    }
    static Consumer<GameContext> tryParseChooseForwardsGainAbilityEot(String text) {
        Matcher m = CHOOSE_FORWARDS_GAIN_ABILITY_EOT.matcher(text.trim());
        if (!m.matches()) return null;
        boolean upTo  = m.group("upto") != null;
        int     count = Integer.parseInt(m.group("count"));
        String  ability = m.group("ability").trim();
        return ctx -> {
            ctx.logEntry("Effect: choose " + (upTo ? "up to " : "") + count
                    + " Forward(s) — grant until end of turn: " + ability);
            List<ForwardTarget> ts = selectTargets(ctx, count, upTo, false, false, null, null, null, false,
                    -1, null, -1, null, true, false, false, null, null, null, null, false, null, false);
            for (ForwardTarget t : ts) ctx.grantEotActionAbility(t, ability);
        };
    }
    static Consumer<GameContext> tryParseChooseForwardPlacePetrification(String text) {
        if (!CHOOSE_FORWARD_PLACE_PETRIFICATION.matcher(text.trim()).matches()) return null;
        return ctx -> {
            ctx.logEntry("Effect: choose 1 Forward — place 1 Petrification Counter (cannot attack/block; 《5》 to remove)");
            List<ForwardTarget> ts = selectTargets(ctx, 1, false, false, false, null, null, null, false,
                    -1, null, -1, null, true, false, false, null, null, null, null, false, null, false);
            if (ts.isEmpty()) return;
            ForwardTarget t = ts.get(0);
            CardData fwd = t.isP1() ? ctx.p1Forward(t.idx()) : ctx.p2Forward(t.idx());
            if (fwd != null) ctx.placeCounters(fwd, "Petrification", 1);
        };
    }
    /**
     * Parses "Choose 1 Forward opponent controls. [Name] gains its Special Ability until the end of the turn.
     * You can use this ability without paying any cost but only once."
     * Copies every isSpecial() ability from the chosen Forward to {@code source} as a free, once-per-turn
     * temp ability (all costs removed) that expires at end of turn.
     */
    static Consumer<GameContext> tryParseChooseOppFwdGainsSpecialAbilityFreeOnce(
            String text, CardData source) {
        Matcher m = CHOOSE_OPP_FWD_GAINS_SPECIAL_ABILITY_FREE_ONCE.matcher(text.trim());
        if (!m.matches()) return null;
        String logName = m.group("sourceName");
        return ctx -> {
            ctx.logEntry(logName + " — Choose 1 Forward opponent controls to copy its Special Ability");
            List<ForwardTarget> ts = selectTargets(ctx, 1, false, true, false,
                    null, null, null, false, -1, null, -1, null,
                    true, false, false, null, null, null, null, false, null, false);
            if (ts.isEmpty()) return;
            ForwardTarget t = ts.get(0);
            CardData chosen = t.isP1() ? ctx.p1Forward(t.idx()) : ctx.p2Forward(t.idx());
            if (chosen == null) return;
            List<ActionAbility> specials = chosen.actionAbilities().stream()
                    .filter(ActionAbility::isSpecial)
                    .collect(java.util.stream.Collectors.toList());
            if (specials.isEmpty()) {
                ctx.logEntry(chosen.name() + " has no Special Ability to copy");
                return;
            }
            // Re-pointed at Kimahri on the way across, through the rewrite Gogo's Mimic and
            // Clive's borrowed Eikon specials both use: a Special that names its own card — Tidus's
            // "Activate Tidus", Odin (XVI)'s Iron Flash — would otherwise act on the donor still
            // standing across the table. Costs are stripped by the grant itself, so only the effect
            // needs changing.
            for (ActionAbility original : specials)
                ctx.grantCopiedSpecialAbilityFreeOnce(source, original.withEffectText(
                        substituteSourceName(original.effectText(), chosen.name(), source.name())));
        };
    }
    /**
     * Parses "Choose as many [Type] [opponent controls] as [the] [CountSource] you control. [Dull/Activate] them."
     * The count is computed at resolution time from the acting player's field cards matching the count source.
     */
    static Consumer<GameContext> tryParseChooseAsManyAsFieldCount(String text, CardData source) {
        Matcher m = CHOOSE_AS_MANY_AS_FIELD_COUNT.matcher(text.trim());
        if (!m.matches()) return null;

        String targetTypeRaw = m.group("targetType").trim();
        String targetSide    = m.group("targetSide");
        String countSrc      = m.group("countSrc").trim();
        String followupText  = m.group("followup").trim();

        String tgtLow = targetTypeRaw.toLowerCase();
        boolean inclForwards = tgtLow.startsWith("forward") || tgtLow.startsWith("character");
        boolean inclBackups  = tgtLow.startsWith("backup")  || tgtLow.startsWith("character");
        boolean inclMonsters = tgtLow.startsWith("monster") || tgtLow.startsWith("character");

        boolean opponentOnly = targetSide != null && targetSide.toLowerCase().contains("opponent");
        boolean selfOnly     = !opponentOnly;

        String  countJobFilter = null;
        String  countCatFilter = null;
        boolean countFwds = true, countBkps = true, countMons = true;

        Matcher jbm = JOB_BRACKET_PATTERN.matcher(countSrc);
        if (jbm.find()) {
            countJobFilter = jbm.group(1).trim();
        } else if (countSrc.toLowerCase().startsWith("category ")) {
            String rest = countSrc.substring("category ".length()).trim();
            int sp = rest.indexOf(' ');
            if (sp >= 0) {
                countCatFilter = rest.substring(0, sp);
                String csType = rest.substring(sp + 1).trim().toLowerCase();
                countFwds = csType.startsWith("forward") || csType.startsWith("character");
                countBkps = csType.startsWith("backup")  || csType.startsWith("character");
                countMons = csType.startsWith("monster") || csType.startsWith("character");
            } else {
                countCatFilter = rest;
            }
        } else if (countSrc.toLowerCase().startsWith("job ")) {
            String rest = countSrc.substring("job ".length()).trim();
            countJobFilter = rest.replaceAll("(?i)\\s+(Forwards?|Backups?|Monsters?|Characters?)\\s*$", "").trim();
        } else {
            String csTypeLow = countSrc.toLowerCase().replaceAll("s$", "");
            if (csTypeLow.equals("forward") || csTypeLow.equals("backup")
                    || csTypeLow.equals("monster") || csTypeLow.equals("character")) {
                countFwds = csTypeLow.equals("forward") || csTypeLow.equals("character");
                countBkps = csTypeLow.equals("backup")  || csTypeLow.equals("character");
                countMons = csTypeLow.equals("monster") || csTypeLow.equals("character");
            } else {
                return null;
            }
        }

        boolean doActivate = FOLLOWUP_ACTIVATE.matcher(followupText).find();
        boolean doDull     = FOLLOWUP_DULL.matcher(followupText).find();
        boolean doFreeze   = !doActivate && !doDull && FOLLOWUP_FREEZE.matcher(followupText).find();
        if (!doActivate && !doDull && !doFreeze) return null;

        final String  fJob = countJobFilter, fCat = countCatFilter;
        final boolean fCFwds = countFwds, fCBkps = countBkps, fCMons = countMons;
        final boolean fOppOnly = opponentOnly, fSelfOnly = selfOnly;
        final boolean fFwds = inclForwards, fBkps = inclBackups, fMons = inclMonsters;
        final String  action = doActivate ? "Activate" : doDull ? "Dull" : "Freeze";
        final String  logPfx = "Choose up to as many " + targetTypeRaw
                + (targetSide != null ? " " + targetSide : " you control")
                + " as " + countSrc + " you control";

        return ctx -> {
            int count = ctx.countSelfFieldCards(fCFwds, fCBkps, fCMons, fJob, null, fCat);
            if (count <= 0) {
                ctx.logEntry(logPfx + " — count=0, nothing to choose");
                ctx.markEffectFizzled();
                return;
            }
            ctx.logEntry(logPfx + " (count=" + count + ") — " + action);
            List<ForwardTarget> ts = selectTargets(ctx, count, true,
                    fOppOnly, fSelfOnly, null, null, null, false,
                    -1, null, -1, null,
                    fFwds, fBkps, fMons, null, null, null, null, false, null, false);
            if (doActivate) {
                sortedByIdxDesc(ts, true) .forEach(ctx::activateTarget);
                sortedByIdxDesc(ts, false).forEach(ctx::activateTarget);
            } else if (doDull) {
                sortedByIdxDesc(ts, true) .forEach(ctx::dullTarget);
                sortedByIdxDesc(ts, false).forEach(ctx::dullTarget);
            } else {
                sortedByIdxDesc(ts, true) .forEach(ctx::freezeTarget);
                sortedByIdxDesc(ts, false).forEach(ctx::freezeTarget);
            }
        };
    }
    /**
     * Parses "Choose up to the same number of Characters as the Job X in your Break Zone and/or
     * Job X you own removed from the game. [Dull/Activate/Freeze] them." (Jill 26-034L). The count
     * is computed at resolution time from the acting player's Break Zone and removed-from-game zone.
     */
    static Consumer<GameContext> tryParseChooseAsManyAsBzRfgJobCount(String text) {
        Matcher m = CHOOSE_AS_MANY_AS_BZ_RFG_JOB.matcher(text.trim());
        if (!m.matches()) return null;

        String targetTypeRaw = m.group("targetType").trim();
        String job           = m.group("job").trim();
        String followupText  = m.group("followup").trim();

        String tgtLow = targetTypeRaw.toLowerCase();
        final boolean inclForwards = tgtLow.startsWith("forward") || tgtLow.startsWith("character");
        final boolean inclBackups  = tgtLow.startsWith("backup")  || tgtLow.startsWith("character");
        final boolean inclMonsters = tgtLow.startsWith("monster") || tgtLow.startsWith("character");

        boolean doActivate = FOLLOWUP_ACTIVATE.matcher(followupText).find();
        boolean doDull     = FOLLOWUP_DULL.matcher(followupText).find();
        boolean doFreeze   = !doActivate && !doDull && FOLLOWUP_FREEZE.matcher(followupText).find();
        if (!doActivate && !doDull && !doFreeze) return null;

        final String  action = doActivate ? "Activate" : doDull ? "Dull" : "Freeze";
        final boolean fActivate = doActivate, fDull = doDull;
        final String  logPfx = "Choose up to as many " + targetTypeRaw
                + " as Job " + job + " in your Break Zone and/or removed from the game";
        return ctx -> {
            int count = ctx.countSelfBreakZoneAndRfgCards(null, job);
            if (count <= 0) {
                ctx.logEntry(logPfx + " — count=0, nothing to choose");
                ctx.markEffectFizzled();
                return;
            }
            ctx.logEntry(logPfx + " (count=" + count + ") — " + action);
            List<ForwardTarget> ts = selectTargets(ctx, count, true,
                    false, false, null, null, null, false,
                    -1, null, -1, null,
                    inclForwards, inclBackups, inclMonsters, null, null, null, null, false, null, false);
            if (fActivate) {
                sortedByIdxDesc(ts, true) .forEach(ctx::activateTarget);
                sortedByIdxDesc(ts, false).forEach(ctx::activateTarget);
            } else if (fDull) {
                sortedByIdxDesc(ts, true) .forEach(ctx::dullTarget);
                sortedByIdxDesc(ts, false).forEach(ctx::dullTarget);
            } else {
                sortedByIdxDesc(ts, true) .forEach(ctx::freezeTarget);
                sortedByIdxDesc(ts, false).forEach(ctx::freezeTarget);
            }
        };
    }

    /**
     * Parses "Choose &lt;target&gt;. At the end of your opponent's turn, &lt;action&gt; it."
     * (28-043R Gi Nattak) — the target is picked when the ability resolves, the action lands at
     * the end of the opponent's next turn.
     *
     * <p>Neither half stands alone: the choose clause on its own silently drops the delayed
     * action, which is how this card used to resolve, and the delayed clause on its own has no
     * target. The chosen targets are captured from {@link GameContext#lastChosenTargets} and
     * closed over, so the queued effect acts on the cards picked now rather than re-selecting
     * later.
     */
    static Consumer<GameContext> tryParseChooseThenEndOfOppTurnAction(
            String text, CardData source, int xValue) {
        Matcher m = CHOOSE_THEN_END_OF_OPP_TURN_ACTION.matcher(text.trim());
        if (!m.find()) return null;

        final boolean upTo = m.group("upto") != null;
        final int count = Integer.parseInt(m.group("count"));
        final String actionText = m.group("action").trim();

        BiConsumer<GameContext, List<ForwardTarget>> action = parseTargetAction(actionText, xValue);
        if (action == null) return null;

        return ctx -> {
            ctx.logEntry("Choose " + (upTo ? "up to " : "") + count
                    + " Forward(s) opponent controls — " + actionText
                    + " at the end of your opponent's turn");
            List<ForwardTarget> chosen = List.copyOf(ctx.selectCharacters(count, upTo, true, false,
                    null, null, -1, null, -1, null,
                    true, false, false, null, null, null, null, false, null, false));
            if (chosen.isEmpty()) {
                ctx.logEntry("End-of-opponent-turn effect: nothing chosen — not queued");
                return;
            }
            ctx.addEndOfOpponentTurnEffect(later -> action.accept(later, chosen));
        };
    }
}
