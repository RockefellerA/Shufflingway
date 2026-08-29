package shufflingway;

import static shufflingway.ActionResolverPatterns.*;

import static shufflingway.ActionResolver.*;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;

/**
 * Damage parsers split out of {@link ActionResolver}.
 *
 * <p>Bodies only: {@code ActionResolver} keeps every dispatch chain and calls these
 * through a wildcard static import, so call order -- which is load-bearing, because
 * matchers use {@code find()} -- is unchanged.
 */
final class ActionResolverDamage {

	private ActionResolverDamage() {}

    /**
     * Parses "Choose up to N Forwards opponent controls. Deal 1 of them A damage, 1 of them B
     * damage, and 1 of them C damage." — Palom 3-016H's Meteor.
     *
     * <p>Not a Choose-with-followup, which is why it is its own parser rather than a branch of
     * {@code tryParseChooseCharacter}: that parser makes the whole selection in one dialog and
     * hands the followup a set of targets, and this text needs each pick tied to its own amount.
     * The general followup chain had no branch for the wording either, so the ability reached the
     * "followup not yet implemented" fallback — it logged, chose nothing and dealt nothing.
     *
     * <p>The selection is delegated whole to {@link GameContext#selectOppForwardsForTieredDamage},
     * one prompt per amount. Damage is applied only once every pick is in, highest slot first, so
     * a break cannot shift the index a later pick was recorded at.
     *
     * <p>Returns {@code null} when the printed count and the number of amounts disagree, leaving
     * such a text to the regular matchers rather than guessing which of the two to believe.
     */
    static Consumer<GameContext> tryParseChooseTieredDamage(String text) {
        Matcher m = CHOOSE_TIERED_DAMAGE.matcher(text.trim());
        if (!m.matches()) return null;

        List<Integer> tiers = new ArrayList<>();
        Matcher tm = TIERED_DAMAGE_ONE_OF_THEM.matcher(m.group("tiers"));
        while (tm.find()) tiers.add(Integer.parseInt(tm.group("amount")));
        if (tiers.size() != Integer.parseInt(m.group("count"))) return null;

        int[] amounts = tiers.stream().mapToInt(Integer::intValue).toArray();
        String logLabel = tiers.stream().map(String::valueOf).reduce((a, b) -> a + "/" + b).orElse("");
        return ctx -> {
            ctx.logEntry("Effect: Choose up to " + amounts.length
                    + " Forwards opponent controls — deal " + logLabel + " damage");
            List<GameContext.TieredDamagePick> picks = ctx.selectOppForwardsForTieredDamage(amounts);
            if (picks.isEmpty()) {
                ctx.markEffectFizzled();
                return;
            }
            List<ForwardTarget> targets = picks.stream().map(GameContext.TieredDamagePick::target).toList();
            for (boolean side : new boolean[] { true, false })
                sortedByIdxDesc(targets, side).forEach(t -> picks.stream()
                        .filter(p -> p.target().equals(t))
                        .forEach(p -> ctx.damageTarget(p.target(), p.damage())));
        };
    }

    /** Parses "Divide N damage equally among all the [type] [you control|opponent controls]." — no target choice. */
    static Consumer<GameContext> tryParseDivideDamageEquallyAmongAll(String text) {
        Matcher m = DIVIDE_DAMAGE_EQUALLY_AMONG_ALL.matcher(text.trim());
        if (!m.find()) return null;

        int    damage    = Integer.parseInt(m.group("amount"));
        String control    = m.group("control");
        boolean opponentOnly = control != null && !control.equalsIgnoreCase("you control");
        boolean selfOnly     = control != null && control.equalsIgnoreCase("you control");
        boolean unreduced    = CANNOT_BE_REDUCED_PATTERN.matcher(text).find();

        return ctx -> {
            boolean oppIsP2  = opponentOnly && ctx.isP1();
            boolean oppIsP1  = opponentOnly && !ctx.isP1();
            boolean selfIsP1 = selfOnly && ctx.isP1();
            boolean selfIsP2 = selfOnly && !ctx.isP1();
            boolean inclP2   = (!opponentOnly && !selfOnly) || oppIsP2 || selfIsP2;
            boolean inclP1   = (!opponentOnly && !selfOnly) || oppIsP1 || selfIsP1;

            List<ForwardTarget> ts = new ArrayList<>();
            if (inclP2) for (int i = 0; i < ctx.p2ForwardCount(); i++) ts.add(new ForwardTarget(false, i, ForwardTarget.CardZone.FORWARD));
            if (inclP1) for (int i = 0; i < ctx.p1ForwardCount(); i++) ts.add(new ForwardTarget(true, i, ForwardTarget.CardZone.FORWARD));
            if (ts.isEmpty()) return;

            int perTarget = roundUpToThousand(damage, ts.size());
            ctx.logEntry("Effect: Divide " + damage + " damage equally among all Forwards ("
                    + perTarget + " each, rounded up to the nearest 1000)");
            sortedByIdxDesc(ts, true) .forEach(t -> damageTargetMaybeUnreduced(ctx, t, perTarget, unreduced));
            sortedByIdxDesc(ts, false).forEach(t -> damageTargetMaybeUnreduced(ctx, t, perTarget, unreduced));
        };
    }
    static Consumer<GameContext> tryParseDealDamageToForwards(String text) {
        Matcher m = DEAL_DAMAGE_TO_FORWARDS.matcher(text);
        if (!m.find() || m.start() != 0) {
            m = DEAL_DAMAGE_TO_FORWARDS_ALT.matcher(text);
            if (!m.find() || m.start() != 0) return null;
        }

        int    damage        = Integer.parseInt(m.group("amount"));
        String condition     = m.group("condition");   // nullable
        String costStr       = m.group("cost");
        int    costVal       = costStr != null ? Integer.parseInt(costStr) : -1;
        String costCmp       = m.group("costcmp");
        String excludeJob    = m.group("excludejob") != null ? m.group("excludejob").trim() : null;
        // Exclusion by card name, not by Job — 14-011H Susano, Lord of the Revel spares itself and
        // 4-083L Shantotto spares herself. Matched by name, so a second copy is spared too, which
        // is what "all the Forwards other than Shantotto" says.
        String excludeName   = m.group("excludename") != null ? m.group("excludename").trim() : null;
        boolean opponentOnly = m.group("opponent") != null;
        boolean unreduced    = CANNOT_BE_REDUCED_PATTERN.matcher(text).find();

        // Chain any text after the damage clause (e.g. "Philia deals you 1 point of damage.")
        String remainingText = text.substring(m.end()).trim();
        Consumer<GameContext> afterDamage = remainingText.isEmpty() ? null : parse(remainingText, null);

        return ctx -> {
            String condLabel   = condition  != null ? (condition + " ")   : "";
            String costLabel   = costVal >= 0 ? " of cost " + costVal + (costCmp != null ? " or " + costCmp : "") : "";
            String exclLabel   = excludeJob != null ? " [not Job " + excludeJob + "]"
                    : excludeName != null ? " [not " + excludeName + "]" : "";
            boolean oppIsP2    = opponentOnly && ctx.isP1();   // ability owner is P1 → opponent is P2
            boolean oppIsP1    = opponentOnly && !ctx.isP1();  // ability owner is P2 → opponent is P1
            String scopeLabel  = opponentOnly ? "opponent's " : "all ";
            String unredLabel  = unreduced ? " (cannot be reduced)" : "";
            ctx.logEntry("Effect: Deal " + damage + " damage to "
                    + scopeLabel + condLabel + "Forwards" + costLabel + exclLabel + unredLabel);

            // --- P2 forwards (included when not opponent-only, or when opponent IS P2) ---
            if (!opponentOnly || oppIsP2) {
                List<Integer> p2Targets = new ArrayList<>();
                for (int i = 0; i < ctx.p2ForwardCount(); i++) {
                    CardData c = ctx.p2Forward(i);
                    if (c == null) continue;
                    if (!meetsCostFilter(c.cost(), costVal, costCmp)) continue;
                    if (excludeJob != null && c.hasJob(excludeJob)) continue;
                    if (excludeName != null && excludeName.equalsIgnoreCase(c.name())) continue;
                    if (meetsCondition(ctx.p2ForwardState(i), ctx.p2ForwardCurrentDamage(i),
                            ctx.isP2ForwardAttacking(i), ctx.isP2ForwardBlocking(i), condition))
                        p2Targets.add(i);
                }
                for (int i = p2Targets.size() - 1; i >= 0; i--) {
                    int idx = p2Targets.get(i);
                    if (idx < ctx.p2ForwardCount()) {
                        if (unreduced) ctx.damageP2ForwardUnreduced(idx, damage);
                        else           ctx.damageP2Forward(idx, damage);
                    }
                }
            }

            // --- P1 forwards (included when not opponent-only, or when opponent IS P1) ---
            if (!opponentOnly || oppIsP1) {
                List<Integer> p1Targets = new ArrayList<>();
                for (int i = 0; i < ctx.p1ForwardCount(); i++) {
                    CardData c = ctx.p1Forward(i);
                    if (c == null) continue;
                    if (!meetsCostFilter(c.cost(), costVal, costCmp)) continue;
                    if (excludeJob != null && c.hasJob(excludeJob)) continue;
                    if (excludeName != null && excludeName.equalsIgnoreCase(c.name())) continue;
                    if (meetsCondition(ctx.p1ForwardState(i), ctx.p1ForwardCurrentDamage(i),
                            ctx.isP1ForwardAttacking(i), ctx.isP1ForwardBlocking(i), condition))
                        p1Targets.add(i);
                }
                for (int i = p1Targets.size() - 1; i >= 0; i--) {
                    int idx = p1Targets.get(i);
                    if (idx < ctx.p1ForwardCount()) {
                        if (unreduced) ctx.damageP1ForwardUnreduced(idx, damage);
                        else           ctx.damageP1Forward(idx, damage);
                    }
                }
            }
            if (afterDamage != null) afterDamage.accept(ctx);
        };
    }
    /**
     * Parses Shantotto 4-083L: "deal the same amount of damage to all the Forwards other than
     * Shantotto." — the retaliation half of "When Shantotto is dealt damage".
     *
     * <p>Self-named, and the amount comes from {@code xValue}: the dispatcher puts the size of the
     * damage instance that fired the trigger on the stack entry, because the card's text names it
     * rather than stating it. An entry carrying nothing there resolves to no damage rather than to
     * a guess.
     *
     * <p>Sweeps both sides, sparing every copy of the named card — "all the Forwards other than
     * Shantotto", not "all the Forwards opponent controls". Highest index first within each side,
     * because a Forward broken by the damage compacts its row.
     */
    static Consumer<GameContext> tryParseDealSameAmountToAllForwardsExcept(
            String text, CardData source, int xValue) {
        if (source == null) return null;
        Matcher m = DEAL_SAME_AMOUNT_TO_ALL_FORWARDS_EXCEPT.matcher(text.trim());
        if (!m.matches()) return null;
        String excluded = m.group("card").trim();
        if (!excluded.equalsIgnoreCase(source.name())) return null;

        return ctx -> {
            if (xValue <= 0) {
                ctx.logEntry("Effect: " + source.name()
                        + " — no damage recorded for this trigger, nothing dealt");
                return;
            }
            ctx.logEntry("Effect: Deal " + xValue + " damage to all Forwards other than " + excluded);
            for (int i = ctx.p2ForwardCount() - 1; i >= 0; i--) {
                CardData c = ctx.p2Forward(i);
                if (c == null || excluded.equalsIgnoreCase(c.name())) continue;
                ctx.damageP2Forward(i, xValue);
            }
            for (int i = ctx.p1ForwardCount() - 1; i >= 0; i--) {
                CardData c = ctx.p1Forward(i);
                if (c == null || excluded.equalsIgnoreCase(c.name())) continue;
                ctx.damageP1Forward(i, xValue);
            }
        };
    }

    static Consumer<GameContext> tryParseDealDamageToForwardsExceptElement(String text) {
        Matcher m = DEAL_DAMAGE_TO_FORWARDS_EXCEPT_ELEMENT.matcher(text);
        if (!m.find() || m.start() != 0) return null;
        int    damage      = Integer.parseInt(m.group("amount"));
        String excludeElem = m.group("excludeelem").trim();
        return ctx -> {
            ctx.logEntry("Effect: Deal " + damage + " damage to all Forwards of all Elements except " + excludeElem);
            for (int i = ctx.p2ForwardCount() - 1; i >= 0; i--) {
                if (ctx.fieldCardHasElement(ctx.p2Forward(i), excludeElem)) continue;
                ctx.damageP2Forward(i, damage);
            }
            for (int i = ctx.p1ForwardCount() - 1; i >= 0; i--) {
                if (ctx.fieldCardHasElement(ctx.p1Forward(i), excludeElem)) continue;
                ctx.damageP1Forward(i, damage);
            }
        };
    }
    static Consumer<GameContext> tryParseDealDamageToForwardsForEach(String text) {
        Matcher m = DEAL_DAMAGE_TO_FORWARDS_FOR_EACH.matcher(text);
        if (!m.find()) return null;

        int    baseDmg       = Integer.parseInt(m.group("base"));
        String element       = m.group("element");
        String category      = m.group("category");
        String charType      = m.group("chartype");
        String condition     = m.group("condition");
        boolean countOpp     = m.group("oppcount") != null;
        boolean opponentOnly = m.group("opponent") != null;
        boolean unreduced    = CANNOT_BE_REDUCED_PATTERN.matcher(text).find();

        boolean fwd = charType.matches("(?i)Forwards?|Characters?");
        boolean bkp = charType.matches("(?i)Backups?|Characters?");
        boolean mon = charType.matches("(?i)Monsters?|Characters?");
        String elementFilter = element != null ? element.toLowerCase(java.util.Locale.ROOT) : null;

        return ctx -> {
            int n = countOpp
                    ? ctx.countOppFieldCards(fwd, bkp, mon, null, null, category, elementFilter)
                    : ctx.countSelfFieldCards(fwd, bkp, mon, null, null, category, elementFilter);
            int damage = baseDmg * n;
            String controller = countOpp ? "opponent controls" : "you control";
            String multLabel = (element != null ? element + " " : "")
                    + (category != null ? "Category " + category + " " : "")
                    + charType + " " + controller;
            String condLabel = condition != null ? (condition + " ") : "";
            String scopeLabel = opponentOnly ? "opponent's " : "all ";
            String unredLabel = unreduced ? " (cannot be reduced)" : "";
            ctx.logEntry("Effect: Deal " + damage + " damage (" + baseDmg + " x " + n + " "
                    + multLabel + ") to " + scopeLabel + condLabel + "Forwards" + unredLabel);
            if (damage <= 0) return;

            boolean oppIsP2 = opponentOnly && ctx.isP1();
            boolean oppIsP1 = opponentOnly && !ctx.isP1();

            if (!opponentOnly || oppIsP2) {
                List<Integer> p2Targets = new ArrayList<>();
                for (int i = 0; i < ctx.p2ForwardCount(); i++) {
                    if (meetsCondition(ctx.p2ForwardState(i), ctx.p2ForwardCurrentDamage(i),
                            ctx.isP2ForwardAttacking(i), ctx.isP2ForwardBlocking(i), condition))
                        p2Targets.add(i);
                }
                for (int i = p2Targets.size() - 1; i >= 0; i--) {
                    int idx = p2Targets.get(i);
                    if (idx < ctx.p2ForwardCount()) {
                        if (unreduced) ctx.damageP2ForwardUnreduced(idx, damage);
                        else           ctx.damageP2Forward(idx, damage);
                    }
                }
            }
            if (!opponentOnly || oppIsP1) {
                List<Integer> p1Targets = new ArrayList<>();
                for (int i = 0; i < ctx.p1ForwardCount(); i++) {
                    if (meetsCondition(ctx.p1ForwardState(i), ctx.p1ForwardCurrentDamage(i),
                            ctx.isP1ForwardAttacking(i), ctx.isP1ForwardBlocking(i), condition))
                        p1Targets.add(i);
                }
                for (int i = p1Targets.size() - 1; i >= 0; i--) {
                    int idx = p1Targets.get(i);
                    if (idx < ctx.p1ForwardCount()) {
                        if (unreduced) ctx.damageP1ForwardUnreduced(idx, damage);
                        else           ctx.damageP1Forward(idx, damage);
                    }
                }
            }
        };
    }
    static Consumer<GameContext> tryParseForEachJobAndNameDealDamageToForwards(String text) {
        Matcher m = FOR_EACH_JOB_AND_NAME_DEAL_DAMAGE_TO_FORWARDS.matcher(text);
        if (!m.matches()) return null;
        String job           = m.group("job").trim();
        String cardName      = m.group("cardname").trim();
        int    baseDmg       = Integer.parseInt(m.group("amount"));
        boolean opponentOnly = m.group("opponent") != null;
        return ctx -> {
            int count = ctx.countSelfFieldCards(true, true, true, job, null)
                      + ctx.countSelfFieldCards(true, true, true, null, cardName);
            int damage = baseDmg * count;
            boolean oppIsP2 = opponentOnly && ctx.isP1();
            boolean oppIsP1 = opponentOnly && !ctx.isP1();
            String scopeLabel = opponentOnly ? "opponent's " : "all ";
            ctx.logEntry("Effect: For each Job " + job + " and Card name " + cardName
                    + " (" + count + "), deal " + damage + " damage to " + scopeLabel + "Forwards");
            if (damage <= 0) return;
            if (!opponentOnly || oppIsP2) {
                for (int i = ctx.p2ForwardCount() - 1; i >= 0; i--)
                    if (i < ctx.p2ForwardCount()) ctx.damageP2Forward(i, damage);
            }
            if (!opponentOnly || oppIsP1) {
                for (int i = ctx.p1ForwardCount() - 1; i >= 0; i--)
                    if (i < ctx.p1ForwardCount()) ctx.damageP1Forward(i, damage);
            }
        };
    }
    static Consumer<GameContext> tryParseDealBasePlusBzNameDamageToForwards(String text) {
        Matcher m = DEAL_BASE_PLUS_BZ_NAME_DAMAGE_TO_FORWARDS.matcher(text);
        if (!m.matches()) return null;
        int    base          = Integer.parseInt(m.group("base"));
        int    per           = Integer.parseInt(m.group("per"));
        String cardName      = m.group("cardname").trim();
        boolean opponentOnly = m.group("opponent") != null;
        return ctx -> {
            int count  = ctx.countSelfBreakZoneCards(cardName, null);
            int damage = base + per * count;
            boolean oppIsP2 = opponentOnly && ctx.isP1();
            boolean oppIsP1 = opponentOnly && !ctx.isP1();
            String scopeLabel = opponentOnly ? "opponent's " : "all ";
            ctx.logEntry("Effect: Deal " + damage + " damage (" + base + " + " + per + "×" + count
                    + " [" + cardName + "] in BZ) to " + scopeLabel + "Forwards");
            if (damage <= 0) return;
            if (!opponentOnly || oppIsP2) {
                for (int i = ctx.p2ForwardCount() - 1; i >= 0; i--)
                    if (i < ctx.p2ForwardCount()) ctx.damageP2Forward(i, damage);
            }
            if (!opponentOnly || oppIsP1) {
                for (int i = ctx.p1ForwardCount() - 1; i >= 0; i--)
                    if (i < ctx.p1ForwardCount()) ctx.damageP1Forward(i, damage);
            }
        };
    }
    static Consumer<GameContext> tryParseDealHalfPowerDamageToForwards(String text) {
        Matcher m = DEAL_HALF_POWER_DAMAGE_TO_FORWARDS.matcher(text);
        if (!m.find()) return null;

        String  condition    = m.group("condition");
        boolean opponentOnly = m.group("opponent") != null;

        return ctx -> {
            String  condLabel  = condition   != null ? (condition + " ")  : "";
            boolean oppIsP2    = opponentOnly && ctx.isP1();
            boolean oppIsP1    = opponentOnly && !ctx.isP1();
            String  scopeLabel = opponentOnly ? "opponent's " : "all ";
            ctx.logEntry("Effect: Deal each " + scopeLabel + condLabel
                    + "Forward damage equal to half power (round up to nearest 1000)");

            if (!opponentOnly || oppIsP2) {
                List<Integer> p2Targets = new ArrayList<>();
                for (int i = 0; i < ctx.p2ForwardCount(); i++) {
                    if (meetsCondition(ctx.p2ForwardState(i), ctx.p2ForwardCurrentDamage(i),
                            ctx.isP2ForwardAttacking(i), ctx.isP2ForwardBlocking(i), condition))
                        p2Targets.add(i);
                }
                for (int i = p2Targets.size() - 1; i >= 0; i--) {
                    int idx = p2Targets.get(i);
                    if (idx < ctx.p2ForwardCount())
                        ctx.damageP2Forward(idx, halfPowerDamage(ctx.p2Forward(idx).power()));
                }
            }

            if (!opponentOnly || oppIsP1) {
                List<Integer> p1Targets = new ArrayList<>();
                for (int i = 0; i < ctx.p1ForwardCount(); i++) {
                    if (meetsCondition(ctx.p1ForwardState(i), ctx.p1ForwardCurrentDamage(i),
                            ctx.isP1ForwardAttacking(i), ctx.isP1ForwardBlocking(i), condition))
                        p1Targets.add(i);
                }
                for (int i = p1Targets.size() - 1; i >= 0; i--) {
                    int idx = p1Targets.get(i);
                    if (idx < ctx.p1ForwardCount())
                        ctx.damageP1Forward(idx, halfPowerDamage(ctx.p1Forward(idx).power()));
                }
            }
        };
    }
    static Consumer<GameContext> tryParseDealPowerMinusNDamageToForwards(String text) {
        Matcher m = DEAL_POWER_MINUS_N_DAMAGE_TO_FORWARDS.matcher(text);
        if (!m.find()) return null;

        String  condition    = m.group("condition");
        boolean opponentOnly = m.group("opponent") != null;
        int     reduction    = Integer.parseInt(m.group("amount"));

        return ctx -> {
            String  condLabel  = condition != null ? (condition + " ") : "";
            boolean oppIsP2    = opponentOnly && ctx.isP1();
            boolean oppIsP1    = opponentOnly && !ctx.isP1();
            String  scopeLabel = opponentOnly ? "opponent's " : "all ";
            ctx.logEntry("Effect: Deal each " + scopeLabel + condLabel
                    + "Forward damage equal to its power minus " + reduction);

            if (!opponentOnly || oppIsP2) {
                List<Integer> targets = new ArrayList<>();
                for (int i = 0; i < ctx.p2ForwardCount(); i++) {
                    if (meetsCondition(ctx.p2ForwardState(i), ctx.p2ForwardCurrentDamage(i),
                            ctx.isP2ForwardAttacking(i), ctx.isP2ForwardBlocking(i), condition))
                        targets.add(i);
                }
                for (int i = targets.size() - 1; i >= 0; i--) {
                    int idx = targets.get(i);
                    if (idx < ctx.p2ForwardCount())
                        ctx.damageP2Forward(idx, Math.max(0, ctx.p2Forward(idx).power() - reduction));
                }
            }

            if (!opponentOnly || oppIsP1) {
                List<Integer> targets = new ArrayList<>();
                for (int i = 0; i < ctx.p1ForwardCount(); i++) {
                    if (meetsCondition(ctx.p1ForwardState(i), ctx.p1ForwardCurrentDamage(i),
                            ctx.isP1ForwardAttacking(i), ctx.isP1ForwardBlocking(i), condition))
                        targets.add(i);
                }
                for (int i = targets.size() - 1; i >= 0; i--) {
                    int idx = targets.get(i);
                    if (idx < ctx.p1ForwardCount())
                        ctx.damageP1Forward(idx, Math.max(0, ctx.p1Forward(idx).power() - reduction));
                }
            }
        };
    }
    static Consumer<GameContext> tryParseDealHalfSourcePowerDamageToForwards(String text) {
        Matcher m = DEAL_HALF_SOURCE_POWER_DAMAGE_TO_FORWARDS.matcher(text);
        if (!m.find()) return null;

        String  sourceName   = m.group("sourcename").trim();
        String  condition    = m.group("condition");
        boolean opponentOnly = m.group("opponent") != null;
        boolean roundUp      = "up".equalsIgnoreCase(m.group("rounding"));

        return ctx -> {
            int raw       = Math.max(0, ctx.fieldForwardPowerByName(sourceName));
            int damage    = roundUp ? halfPowerDamage(raw) : (raw / 2 / 1000) * 1000;
            String condLabel  = condition   != null ? (condition + " ")   : "";
            boolean oppIsP2   = opponentOnly && ctx.isP1();
            boolean oppIsP1   = opponentOnly && !ctx.isP1();
            String  scopeLabel = opponentOnly ? "opponent's " : "all ";
            String  dir        = roundUp ? "up" : "down";
            ctx.logEntry("Effect: Deal " + damage + " damage (half of " + sourceName
                    + "'s power, round " + dir + ") to " + scopeLabel + condLabel + "Forwards");

            if (!opponentOnly || oppIsP2) {
                List<Integer> p2Targets = new ArrayList<>();
                for (int i = 0; i < ctx.p2ForwardCount(); i++) {
                    if (meetsCondition(ctx.p2ForwardState(i), ctx.p2ForwardCurrentDamage(i),
                            ctx.isP2ForwardAttacking(i), ctx.isP2ForwardBlocking(i), condition))
                        p2Targets.add(i);
                }
                for (int i = p2Targets.size() - 1; i >= 0; i--) {
                    int idx = p2Targets.get(i);
                    if (idx < ctx.p2ForwardCount())
                        ctx.damageP2Forward(idx, damage);
                }
            }

            if (!opponentOnly || oppIsP1) {
                List<Integer> p1Targets = new ArrayList<>();
                for (int i = 0; i < ctx.p1ForwardCount(); i++) {
                    if (meetsCondition(ctx.p1ForwardState(i), ctx.p1ForwardCurrentDamage(i),
                            ctx.isP1ForwardAttacking(i), ctx.isP1ForwardBlocking(i), condition))
                        p1Targets.add(i);
                }
                for (int i = p1Targets.size() - 1; i >= 0; i--) {
                    int idx = p1Targets.get(i);
                    if (idx < ctx.p1ForwardCount())
                        ctx.damageP1Forward(idx, damage);
                }
            }
        };
    }
    static Consumer<GameContext> tryParseDamageToCombatBlocker(String text) {
        Matcher m = DAMAGE_TO_COMBAT_BLOCKER.matcher(text);
        if (!m.find()) return null;
        int    damage = Integer.parseInt(m.group("amount"));
        String name   = m.group("name").trim();
        return ctx -> {
            int blockerIdx = ctx.combatBlockerIdxForAttacker(name, ctx.isP1());
            if (blockerIdx < 0) {
                ctx.logEntry("Effect: Deal " + damage + " damage to blocker of " + name + " — no blocker");
                return;
            }
            ctx.logEntry("Effect: Deal " + damage + " damage to Forward blocking " + name);
            if (ctx.isP1()) ctx.damageP2Forward(blockerIdx, damage);
            else            ctx.damageP1Forward(blockerIdx, damage);
        };
    }
    static Consumer<GameContext> tryParseDoubleOutgoingDamageThisTurn(String text, CardData source) {
        if (source == null) return null;
        Matcher m = DOUBLE_OUTGOING_DAMAGE_THIS_TURN.matcher(text);
        if (!m.find()) return null;
        if (!m.group("subject").trim().equalsIgnoreCase(source.name())) return null;
        return ctx -> ctx.doubleOutgoingDamage(source);
    }
    static Consumer<GameContext> tryParseDoubleOpponentIncomingDamageThisTurn(String text) {
        if (!DOUBLE_OPPONENT_INCOMING_DAMAGE_THIS_TURN.matcher(text).find()) return null;
        return ctx -> ctx.doubleOpponentForwardIncomingDamage();
    }
    static Consumer<GameContext> tryParseSelfOutgoingDmgBoostThisTurn(String text, CardData source) {
        if (source == null) return null;
        Matcher m = SELF_OUTGOING_DMG_BOOST_THIS_TURN.matcher(text.trim());
        if (!m.matches()) return null;
        if (!m.group("subject").trim().equalsIgnoreCase(source.name())) return null;
        int amount = Integer.parseInt(m.group("amount"));
        return ctx -> ctx.boostSelfOutgoingDamageThisTurn(source, amount);
    }
    static Consumer<GameContext> tryParseGainOutgoingDmgBoostUntilEot(String text, CardData source) {
        if (source == null) return null;
        Matcher m = GAINS_OUTGOING_DMG_BOOST_UNTIL_EOT.matcher(text.trim());
        if (!m.matches()) return null;
        if (!m.group("subject").trim().equalsIgnoreCase(source.name())) return null;
        if (!m.group("inner").trim().equalsIgnoreCase(source.name())) return null;
        int amount = Integer.parseInt(m.group("amount"));
        return ctx -> {
            ctx.logEntry(source.name() + " gains \"deals damage to a Forward — damage +" + amount
                    + "\" until end of turn");
            ctx.boostSelfOutgoingDamageThisTurn(source, amount);
        };
    }
    static Consumer<GameContext> tryParseAllForwardIncomingDmgIncreaseThisTurn(String text) {
        Matcher m = ALL_FORWARD_INCOMING_DMG_INCREASE_THIS_TURN.matcher(text);
        if (!m.find()) return null;
        int amount = Integer.parseInt(m.group("amount"));
        return ctx -> ctx.increaseAllForwardIncomingDamage(amount);
    }
    static Consumer<GameContext> tryParseDoubleOutgoingDamageThisTurnAlt(String text, CardData source) {
        if (source == null) return null;
        Matcher m = DOUBLE_OUTGOING_DAMAGE_THIS_TURN_ALT.matcher(text);
        if (!m.find()) return null;
        if (!m.group("subject").trim().equalsIgnoreCase(source.name())) return null;
        return ctx -> ctx.doubleOutgoingDamage(source);
    }
    static Consumer<GameContext> tryParseAllOwnForwardsNullifyAbilityDamage(String text) {
        if (!ALL_OWN_FORWARDS_NULLIFY_ABILITY_DAMAGE_PATTERN.matcher(text.trim()).matches()) return null;
        return ctx -> {
            ctx.logEntry("Effect: All own Forwards — damage from Summons/abilities becomes 0 this turn");
            boolean p1 = ctx.isP1();
            int count = p1 ? ctx.p1ForwardCount() : ctx.p2ForwardCount();
            for (int i = 0; i < count; i++)
                ctx.shieldAbilityDamage(new ForwardTarget(p1, i, ForwardTarget.CardZone.FORWARD));
        };
    }
    /**
     * Parses "During this turn, if a Job [X] or Card Name [Y] you control is dealt damage by a
     * Summon or an ability, the damage becomes 0 instead." — a persistent turn-scoped filter, so
     * it also covers matching Forwards that enter the field after resolution.
     */
    static Consumer<GameContext> tryParseOwnJobOrNameNullifyAbilityDamage(String text) {
        Matcher m = OWN_JOB_OR_NAME_NULLIFY_ABILITY_DAMAGE_PATTERN.matcher(text.trim());
        if (!m.matches()) return null;
        String job = m.group("job").trim();
        String cardName = m.group("cardname").trim();
        return ctx -> {
            ctx.logEntry("Effect: Own Job " + job + " / Card Name " + cardName
                + " — damage from Summons/abilities becomes 0 this turn");
            ctx.shieldOwnForwardsAbilityDamageFilter(
                c -> c.hasJob(job) || c.name().equalsIgnoreCase(cardName));
        };
    }
    /** Parses "[Self] breaks after the attack or the block and doesn't deal any damage." */
    static Consumer<GameContext> tryParseBreaksAfterCombatNoDamage(String text, CardData source) {
        if (source == null) return null;
        Matcher m = SOURCE_BREAKS_AFTER_COMBAT_NO_DAMAGE.matcher(text.trim());
        if (!m.matches()) return null;
        if (!m.group("name").trim().equalsIgnoreCase(source.name())) return null;
        return ctx -> {
            ctx.logEntry("Effect: " + source.name() + " breaks after the battle and deals no damage");
            ctx.breakAfterCombatAndDealNoDamage(source);
        };
    }
    /** Parses "&lt;name&gt; deals your opponent N point(s) of damage." — flips from opponent's deck to their damage zone. */
    static Consumer<GameContext> tryParseDealPlayerDamageToOpponent(String text) {
        Matcher m = DEAL_PLAYER_DAMAGE_TO_OPPONENT.matcher(text);
        if (!m.matches()) return null;
        int amount = Integer.parseInt(m.group("amount"));
        return ctx -> {
            ctx.logEntry("Effect: Deal " + amount + " damage to opponent");
            ctx.dealDamageToOpponent(amount);
        };
    }
    /** Parses "&lt;name&gt; deals you N point(s) of damage." — flips from ability user's deck to their damage zone. */
    static Consumer<GameContext> tryParseDealPlayerDamageToSelf(String text) {
        Matcher m = DEAL_PLAYER_DAMAGE_TO_SELF.matcher(text);
        if (!m.matches()) return null;
        int amount = Integer.parseInt(m.group("amount"));
        return ctx -> {
            ctx.logEntry("Effect: Deal " + amount + " damage to self");
            ctx.dealDamageToSelf(amount);
        };
    }
    /** Parses "Each player selects 1 Forward they control. Deal them N damage." */
    static Consumer<GameContext> tryParseEachPlayerSelectForwardDamage(String text) {
        Matcher m = EACH_PLAYER_SELECT_FORWARD_DAMAGE.matcher(text);
        if (!m.find()) return null;
        int amount = Integer.parseInt(m.group("amount"));
        return ctx -> {
            ctx.logEntry("Effect: Each player selects 1 Forward they control. Deal them " + amount + " damage");
            ctx.eachPlayerSelectForwardAndDamage(amount);
        };
    }
    /** Parses "Choose 1 card with EX Burst in your Damage Zone. You may trigger its EX Burst effect." */
    static Consumer<GameContext> tryParseChooseExBurstFromDamageZone(String text) {
        if (!CHOOSE_EX_BURST_FROM_DAMAGE_ZONE.matcher(text.trim()).find()) return null;
        return ctx -> {
            ctx.logEntry("Effect: Choose EX Burst from Damage Zone — trigger on stack");
            ctx.triggerExBurstFromDamageZone();
        };
    }
    /**
     * Parses the Leviathan/Larsa/Strago Damage-Zone-swap pattern. Pulls one card from the ability
     * user's Damage Zone to their hand, optionally draws 1 (Leviathan), then returns one card
     * from hand to the Damage Zone with its EX Burst suppressed.
     */
    static Consumer<GameContext> tryParseDamageZoneSwap(String text) {
        Matcher m = DAMAGE_ZONE_SWAP_PATTERN.matcher(text.trim());
        if (!m.matches()) return null;
        boolean drawBetween = m.group("draw") != null;
        return ctx -> {
            ctx.logEntry("Effect: Damage Zone swap" + (drawBetween ? " (+ draw 1)" : ""));
            ctx.swapDamageZoneCardWithHandCard(drawBetween);
        };
    }
    /**
     * Parses "At the end of each player's turn, if [CardName] has received N damage or more, draw M card(s)."
     * Fires at the end of every player's turn (both P1's and P2's end phase).
     * The source card must be on the field; the check is against accumulated combat damage on that forward.
     */
    static Consumer<GameContext> tryParseEndOfEachPlayersTurnIfSelfFwdDamage(String text, CardData source) {
        if (source == null) return null;
        Matcher m = AT_END_OF_EACH_PLAYERS_TURN_IF_SELF_FWD_DAMAGE_DRAW.matcher(text.trim());
        if (!m.matches()) return null;
        String targetName = m.group("cardname").trim();
        if (!targetName.equalsIgnoreCase(source.name())) return null;
        int minDamage = Integer.parseInt(m.group("damage"));
        int drawCount = Integer.parseInt(m.group("draw"));
        return ctx -> {
            int fwdCount = ctx.isP1() ? ctx.p1ForwardCount() : ctx.p2ForwardCount();
            for (int i = 0; i < fwdCount; i++) {
                CardData fwd = ctx.isP1() ? ctx.p1Forward(i) : ctx.p2Forward(i);
                if (fwd.name().equalsIgnoreCase(targetName)) {
                    int dmg = ctx.isP1() ? ctx.p1ForwardCurrentDamage(i) : ctx.p2ForwardCurrentDamage(i);
                    if (dmg >= minDamage) {
                        ctx.logEntry("Field: " + source.name() + " — draw " + drawCount + " (" + dmg + " damage)");
                        ctx.drawCards(drawCount);
                    }
                    return;
                }
            }
        };
    }
    /**
     * Parses "if [CardName] has received N damage or more, draw M card(s)." —
     * the inner effect of "At the end of each player's turn, …" auto abilities.
     */
    static Consumer<GameContext> tryParseIfSelfFwdReceivedDamageDraw(String text, CardData source) {
        if (source == null) return null;
        Matcher m = IF_SELF_FWD_RECEIVED_DAMAGE_DRAW.matcher(text.trim());
        if (!m.matches()) return null;
        String targetName = m.group("cardname").trim();
        if (!targetName.equalsIgnoreCase(source.name())) return null;
        int minDamage = Integer.parseInt(m.group("damage"));
        int drawCount = Integer.parseInt(m.group("draw"));
        return ctx -> {
            int fwdCount = ctx.isP1() ? ctx.p1ForwardCount() : ctx.p2ForwardCount();
            for (int i = 0; i < fwdCount; i++) {
                CardData fwd = ctx.isP1() ? ctx.p1Forward(i) : ctx.p2Forward(i);
                if (fwd.name().equalsIgnoreCase(targetName)) {
                    int dmg = ctx.isP1() ? ctx.p1ForwardCurrentDamage(i) : ctx.p2ForwardCurrentDamage(i);
                    if (dmg >= minDamage) {
                        ctx.logEntry(source.name() + " — draw " + drawCount + " (" + dmg + " damage)");
                        ctx.drawCards(drawCount);
                    }
                    return;
                }
            }
        };
    }
    /**
     * Parses "If you have received N points of damage, put [CardName] into the Break Zone."
     * The returned consumer checks {@link GameContext#selfDamageCount()} at fire time against the threshold;
     * callers should invoke it whenever the controlling player's damage zone grows.
     */
    static Consumer<GameContext> tryParseIfSelfDamagePointsPutToBreakZone(String text, CardData source) {
        if (source == null) return null;
        Matcher m = IF_SELF_DAMAGE_POINTS_PUT_TO_BREAK_ZONE.matcher(text.trim());
        if (!m.matches()) return null;
        if (!m.group("name").trim().equalsIgnoreCase(source.name())) return null;
        int threshold = Integer.parseInt(m.group("points"));
        return ctx -> {
            if (ctx.selfDamageCount() < threshold) return;
            ctx.logEntry("Effect: " + source.name() + " — Break Zone (received " + threshold + " damage)");
            ctx.breakSourceCard(source);
        };
    }
    /**
     * Parses "Choose 1 auto-ability. Cancel its effect. If the cancelled auto-ability triggered
     * from a Forward, deal that Forward N damage."
     */
    static Consumer<GameContext> tryParseCancelAutoAbilityAndDamageIfForward(String text) {
        Matcher m = CANCEL_AUTO_ABILITY_DAMAGE_IF_FORWARD.matcher(text);
        if (!m.find()) return null;
        int damage = Integer.parseInt(m.group("amount"));
        return ctx -> {
            ctx.logEntry("Effect: Choose 1 auto-ability — cancel it; if triggered from a Forward, deal " + damage + " damage");
            ctx.cancelAutoAbilityAndDamageSourceIfForward(damage);
        };
    }
    /**
     * Parses the EX Burst compound effect:
     * "Choose up to 1 Forward from your Break Zone of cost ≤ damage dealt → hand;
     *  opponent selects 1 Forward of cost ≤ damage dealt → Break Zone."
     */
    static Consumer<GameContext> tryParseBzFwdToHandOppFwdToBzByDamage(String text) {
        if (!BZ_FWD_TO_HAND_OPP_FWD_TO_BZ_BY_DAMAGE.matcher(text).find()) return null;
        return ctx -> {
            int dmg = ctx.selfDamageCount();
            ctx.logEntry("Effect: own BZ Forward cost ≤ " + dmg + " → hand; opponent Forward cost ≤ " + dmg + " → BZ");
            List<ForwardTarget> bzTs = ctx.selectCharactersFromBreakZone(
                    1, true, false, false, null, null, dmg, "less", -1, null,
                    true, false, false, null, null, null, null, false, null, false);
            sortedByIdxDesc(bzTs, true).forEach(ctx::addTargetToHand);
            List<ForwardTarget> oppTs = ctx.selectCharacters(
                    1, false, true, false, null, null, dmg, "less", -1, null,
                    true, false, false, null, null, null, null, false, null, false);
            sortedByIdxDesc(oppTs, false).forEach(ctx::forceTargetToBreakZone);
        };
    }
    /**
     * Parses "Choose 1 Forward you control. Until the end of the turn, it gains +N power[,
     * keywords] and "&lt;protection&gt;"…. If your opponent has received M points of damage or
     * more, all the Forwards you control gain all previous effects instead." (Black Tortoise
     * EX Burst) — single-target buff that upgrades to all own Forwards at the damage threshold.
     */
    static Consumer<GameContext> tryParseChooseOwnFwdBoostProtectionsOrAllIfDmg(String text) {
        Matcher m = CHOOSE_OWN_FWD_BOOST_PROTECTIONS_OR_ALL_IF_DMG.matcher(text.trim());
        if (!m.matches()) return null;
        int amount    = Integer.parseInt(m.group("amount"));
        int dmgThresh = Integer.parseInt(m.group("dmg"));
        EnumSet<CardData.Trait> traits = m.group("traits") != null
                ? parseTraits(m.group("traits")) : EnumSet.noneOf(CardData.Trait.class);
        if (!addQuotedProtectionTraits(m.group("quotes"), traits)) return null;
        final EnumSet<CardData.Trait> grant = traits;
        return ctx -> {
            if (ctx.opponentDamageCount() >= dmgThresh) {
                ctx.logEntry("Effect: opponent has received " + dmgThresh + "+ damage — all own Forwards gain +"
                        + amount + " power and protections until end of turn");
                ctx.applyMassFieldPowerBoost(amount, true, false, false, true, null, -1, null, null, null);
                ctx.applyMassFieldKeywordGrant(grant, true, false, false, true, null, -1, null, null);
            } else {
                ctx.logEntry("Effect: Choose 1 own Forward — +" + amount + " power and protections until end of turn");
                List<ForwardTarget> ts = selectTargets(ctx, 1, false, false, true, null, null, null, false,
                        -1, null, -1, null, true, false, false, null, null, null, null, false, null, false);
                ts.forEach(t -> ctx.boostTarget(t, amount, grant));
            }
        };
    }
    /**
     * Parses standalone "negate all damage" effects:
     * <ul>
     *   <li>"Negate all damage dealt to all the Forwards you control."</li>
     *   <li>"Activate all the Forwards you control and negate all damage dealt to them."</li>
     * </ul>
     * Must be tried before {@link #tryParseAllFieldEffect} so the compound activate+negate form
     * is not swallowed by the simpler activate-all matcher.
     */
    static Consumer<GameContext> tryParseNegateAllDamage(String text) {
        if (STANDALONE_ACTIVATE_AND_NEGATE_DAMAGE_OWN.matcher(text).find()) {
            return ctx -> {
                ctx.logEntry("Effect: Activate all own Forwards and negate their damage");
                ctx.applyMassFieldEffect(GameContext.MassAction.ACTIVATE,
                        true, false, false, false, true, null, -1, null, -1, null, null);
                ctx.negateAllDamageOwnForwards();
            };
        }
        if (STANDALONE_NEGATE_DAMAGE_OWN.matcher(text).find()) {
            return ctx -> {
                ctx.logEntry("Effect: Negate all damage on own Forwards");
                ctx.negateAllDamageOwnForwards();
            };
        }
        return null;
    }
    static Consumer<GameContext> tryParsePlayerNextDamageZero(String text) {
        if (!PLAYER_NEXT_DAMAGE_ZERO.matcher(text).find()) return null;
        return ctx -> {
            ctx.logEntry("Effect: next damage to you becomes 0 this turn");
            ctx.shieldPlayerNextDamage();
        };
    }
    /**
     * Parses "During this turn, the next damage dealt to you becomes 0 and deal [Name] N damage
     * instead." (Auron) — player shield whose consumption redirects the damage to the named Forward.
     */
    static Consumer<GameContext> tryParsePlayerNextDamageZeroRedirect(String text) {
        Matcher m = PLAYER_NEXT_DAMAGE_ZERO_REDIRECT.matcher(text);
        if (!m.find()) return null;
        String name = m.group("name").trim();
        int    dmg  = Integer.parseInt(m.group("dmg"));
        return ctx -> {
            ctx.logEntry("Effect: next damage to you becomes 0 this turn — " + name
                + " takes " + dmg + " damage instead");
            ctx.shieldPlayerNextDamageRedirect(name, dmg);
        };
    }
    /**
     * Parses the three standalone damage-shield effects that apply globally or to a named card:
     * <ul>
     *   <li>Non-lethal protection for all active-player Forwards.</li>
     *   <li>Global incoming-damage reduction for all active-player Forwards.</li>
     *   <li>Nullify ability/Summon damage for a specific named Forward.</li>
     * </ul>
     */
    static Consumer<GameContext> tryParseStandaloneDamageShields(String text, CardData source) {
        // "During this turn, if a Forward you control is dealt damage less than its power, the damage becomes 0 instead."
        if (STANDALONE_NONLETHAL_PROTECTION.matcher(text).find()) {
            return ctx -> {
                ctx.logEntry("Effect: Non-lethal protection for your Forwards this turn");
                ctx.shieldActivePlayerNonLethal();
            };
        }

        // "During this turn, if a Forward you control is dealt damage, reduce the damage by N instead."
        Matcher globalRedM = STANDALONE_GLOBAL_DMG_REDUCTION.matcher(text);
        if (globalRedM.find()) {
            int reduction = Integer.parseInt(globalRedM.group("reduction"));
            return ctx -> {
                ctx.logEntry("Effect: All your Forwards take " + reduction + " less damage this turn");
                ctx.shieldActivePlayerDamageReduction(reduction);
            };
        }

        // "During this turn, if <cardName> is dealt damage by your opponent's Summons or abilities, the damage becomes 0 instead."
        Matcher nullifyM = STANDALONE_NULLIFY_ABILITY_DAMAGE.matcher(text);
        if (nullifyM.find()) {
            String cardName = nullifyM.group("card").trim();
            return ctx -> {
                ctx.logEntry("Effect: " + cardName + " — ability damage nullified this turn");
                // Find the named forward on the active player's field
                for (int i = 0; i < ctx.p1ForwardCount(); i++) {
                    if (ctx.p1Forward(i).name().equalsIgnoreCase(cardName))
                        ctx.shieldAbilityDamage(new ForwardTarget(true, i, ForwardTarget.CardZone.FORWARD));
                }
            };
        }

        // "The damage dealt to Forwards opponent controls cannot be reduced this turn."
        if (STANDALONE_DISABLE_OPPONENT_DMG_REDUCTION.matcher(text).find()) {
            return ctx -> {
                ctx.logEntry("Effect: Opponent's Forwards cannot benefit from damage reduction this turn");
                ctx.disableOpponentDamageReduction();
            };
        }

        // "This damage cannot be reduced." — modifier on a preceding damage sentence.
        // The actual unreduced routing is handled at the damage call site; this entry
        // prevents the "not yet implemented" log when it appears as a secondary followup.
        if (CANNOT_BE_REDUCED_PATTERN.matcher(text).find()) {
            return ctx -> {};
        }

        // "During this turn, the next damage dealt to [name] becomes 0 instead."
        Matcher namedZeroM = STANDALONE_SHIELD_NEXT_DMG_ZERO_NAMED.matcher(text);
        if (namedZeroM.find()) {
            String cardName = namedZeroM.group("name").trim();
            return ctx -> {
                ctx.logEntry("Effect: " + cardName + " — next damage becomes 0");
                boolean actorIsP1 = ctx.isP1();
                int ownCount = actorIsP1 ? ctx.p1ForwardCount() : ctx.p2ForwardCount();
                int oppCount = actorIsP1 ? ctx.p2ForwardCount() : ctx.p1ForwardCount();
                for (int i = 0; i < ownCount; i++) {
                    CardData c = actorIsP1 ? ctx.p1Forward(i) : ctx.p2Forward(i);
                    if (c.name().equalsIgnoreCase(cardName)) {
                        ctx.shieldNextIncomingDamage(new ForwardTarget(actorIsP1, i, ForwardTarget.CardZone.FORWARD));
                        return;
                    }
                }
                for (int i = 0; i < oppCount; i++) {
                    CardData c = actorIsP1 ? ctx.p2Forward(i) : ctx.p1Forward(i);
                    if (c.name().equalsIgnoreCase(cardName)) {
                        ctx.shieldNextIncomingDamage(new ForwardTarget(!actorIsP1, i, ForwardTarget.CardZone.FORWARD));
                        return;
                    }
                }
                ctx.logEntry("[Warning] " + cardName + " not found on field for next-damage-zero shield");
            };
        }

        // "During this turn, the next damage dealt to [name] is reduced by N instead."
        Matcher namedRedM = STANDALONE_SHIELD_NEXT_DMG_REDUCTION_NAMED.matcher(text);
        if (namedRedM.find()) {
            String cardName = namedRedM.group("name").trim();
            int reduction   = Integer.parseInt(namedRedM.group("reduction"));
            return ctx -> {
                ctx.logEntry("Effect: " + cardName + " — next damage reduced by " + reduction);
                boolean actorIsP1 = ctx.isP1();
                int ownCount  = actorIsP1 ? ctx.p1ForwardCount() : ctx.p2ForwardCount();
                int oppCount  = actorIsP1 ? ctx.p2ForwardCount() : ctx.p1ForwardCount();
                for (int i = 0; i < ownCount; i++) {
                    CardData c = actorIsP1 ? ctx.p1Forward(i) : ctx.p2Forward(i);
                    if (c.name().equalsIgnoreCase(cardName)) {
                        ctx.shieldNextIncomingDamageReduction(
                                new ForwardTarget(actorIsP1, i, ForwardTarget.CardZone.FORWARD), reduction);
                        return;
                    }
                }
                for (int i = 0; i < oppCount; i++) {
                    CardData c = actorIsP1 ? ctx.p2Forward(i) : ctx.p1Forward(i);
                    if (c.name().equalsIgnoreCase(cardName)) {
                        ctx.shieldNextIncomingDamageReduction(
                                new ForwardTarget(!actorIsP1, i, ForwardTarget.CardZone.FORWARD), reduction);
                        return;
                    }
                }
                ctx.logEntry("[Warning] " + cardName + " not found on field for damage reduction");
            };
        }

        return null;
    }
    /**
     * Parses "Choose 1 Forward opponent controls which has been dealt damage this turn.
     * If that Forward has a special ability or an action ability, break it."
     */
    static Consumer<GameContext> tryParseChooseOppDamagedFwdIfHasAbilityBreak(String text) {
        if (!CHOOSE_OPP_DAMAGED_FWD_IF_HAS_ABILITY_BREAK.matcher(text.trim()).matches()) return null;
        return ctx -> {
            ctx.logEntry("Choose 1 damaged opponent Forward — break if has special/action ability");
            List<ForwardTarget> ts = selectTargets(ctx, 1, false, true, false,
                    "damaged", null, null, false, -1, null, -1, null,
                    true, false, false, null, null, null, null, false, null, false);
            if (ts.isEmpty()) return;
            ForwardTarget t = ts.get(0);
            CardData chosen = t.isP1() ? ctx.p1Forward(t.idx()) : ctx.p2Forward(t.idx());
            if (chosen == null) return;
            if (chosen.actionAbilities().isEmpty()) {
                ctx.logEntry(chosen.name() + " has no special/action ability — not broken");
            } else {
                ctx.breakTarget(t);
            }
        };
    }
    /**
     * Parses "Choose 1 Forward. [CardName] deals you N point(s) of damage.
     * If the cost of the Forward is equal to or less than the damage you have received, break it."
     *
     * <p>Chooses any Forward, deals N self-damage, then breaks the chosen Forward if its cost
     * is ≤ the ability user's damage count (measured after the damage is dealt).
     */
    static Consumer<GameContext> tryParseChooseForwardDealSelfDamageBreakIfCostLeDamage(String text) {
        Matcher m = CHOOSE_FORWARD_DEAL_SELF_DAMAGE_BREAK_IF_COST_LE_DAMAGE.matcher(text.trim());
        if (!m.find()) return null;
        final String dealerName = m.group("name").trim();
        final int damageAmount  = Integer.parseInt(m.group("amount"));
        return ctx -> {
            ctx.logEntry("Effect: Choose 1 Forward — " + dealerName + " deals you " + damageAmount + " damage, then break it if cost ≤ damage");
            List<ForwardTarget> ts = selectTargets(ctx, 1, false,
                    false, false, null, null, null, false,
                    -1, null, -1, null,
                    true, false, false,
                    null, null, null, null, false, null, false);
            if (ts.isEmpty()) return;
            ForwardTarget target = ts.get(0);
            ctx.dealDamageToSelf(damageAmount);
            CardData chosen = target.isP1() ? ctx.p1Forward(target.idx()) : ctx.p2Forward(target.idx());
            int chosenCost = chosen != null ? chosen.cost() : -1;
            int dmgCount   = ctx.p1DamageCount();
            ctx.logEntry(dealerName + " damage dealt — own damage zone: " + dmgCount
                    + ", chosen Forward cost: " + chosenCost);
            if (chosenCost >= 0 && chosenCost <= dmgCount) {
                ctx.breakTarget(target);
            }
        };
    }
}
