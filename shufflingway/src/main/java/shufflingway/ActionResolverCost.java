package shufflingway;

import static shufflingway.ActionResolverPatterns.*;

import static shufflingway.ActionResolver.*;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.regex.Matcher;

/**
 * Cost parsers split out of {@link ActionResolver}.
 *
 * <p>Bodies only: {@code ActionResolver} keeps every dispatch chain and calls these
 * through a wildcard static import, so call order -- which is load-bearing, because
 * matchers use {@code find()} -- is unchanged.
 */
final class ActionResolverCost {

	private ActionResolverCost() {}

    static Consumer<GameContext> tryParseNoForwardCostCannotAttack(String text) {
        Matcher m = NO_FORWARD_COST_CANNOT_ATTACK.matcher(text.trim());
        if (!m.matches()) return null;
        String costStr = m.group("cost");
        int    costVal = costStr != null ? Integer.parseInt(costStr) : -1;
        String costCmp = m.group("costcmp");
        return ctx -> {
            String label = costVal >= 0
                    ? "cost " + costVal + (costCmp != null ? " or " + costCmp : "")
                    : "any cost";
            ctx.logEntry("Effect: No Forward of " + label + " can attack this turn");
            for (int i = 0; i < ctx.p1ForwardCount(); i++)
                if (meetsCostFilter(ctx.p1Forward(i).cost(), costVal, costCmp)) ctx.setP1ForwardCannotAttack(i);
            for (int i = 0; i < ctx.p2ForwardCount(); i++)
                if (meetsCostFilter(ctx.p2Forward(i).cost(), costVal, costCmp)) ctx.setP2ForwardCannotAttack(i);
        };
    }
    /**
     * Hojo followup: "Choose 1 Forward in your Break Zone with a cost inferior to that of the
     * removed Forward. Play it onto the field."
     * Reads {@link GameContext#lastRemovedFromGameCardCost()} to determine the cost ceiling.
     */
    static Consumer<GameContext> tryParseChooseFwdBzCostInferiorToRemovedPlay(String text) {
        if (!CHOOSE_FWD_BZ_COST_INFERIOR_TO_REMOVED_PLAY.matcher(text).find()) return null;
        return ctx -> {
            int removedCost = ctx.lastRemovedFromGameCardCost();
            if (removedCost <= 0) { ctx.logEntry("No removed Forward cost tracked — cannot play from BZ"); return; }
            int costCeiling = removedCost - 1; // "inferior to N" = cost ≤ N-1
            ctx.logEntry("Choose 1 Forward from own Break Zone with cost < " + removedCost);
            List<ForwardTarget> ts = ctx.selectCharactersFromBreakZone(1, false, false, false,
                    null, null, costCeiling, "less", -1, null,
                    true, false, false, null, null, null, null, false, null, false);
            ts.forEach(ctx::playTargetOntoField);
        };
    }
    /**
     * Parses "Until the end of your turn, you can cast [CardName] removed by this ability's
     * cost." — reachable both standalone and as a choose-effect secondary (the secondary
     * fallback re-enters {@link #parse}).
     */
    static Consumer<GameContext> tryParseCastRfgCostCardThisTurn(String text) {
        Matcher m = CAST_RFG_COST_CARD_THIS_TURN.matcher(text.trim());
        if (!m.matches()) return null;
        String name = m.group("name").trim();
        return ctx -> ctx.makeRfgCostCardCastableThisTurn(name);
    }
    static Consumer<GameContext> tryParseForwardsOfCostCannotBlock(String text) {
        Matcher m = STANDALONE_FORWARDS_OF_COST_CANNOT_BLOCK.matcher(text);
        if (!m.matches()) return null;
        int costVal = Integer.parseInt(m.group("costval"));
        boolean orLess = m.group("cmp").equalsIgnoreCase("less");
        return ctx -> {
            ctx.logEntry("Effect: All Forwards of cost " + costVal + " or " + (orLess ? "less" : "more") + " cannot block this turn");
            for (int i = 0; i < ctx.p1ForwardCount(); i++)
                if (orLess ? ctx.p1Forward(i).cost() <= costVal : ctx.p1Forward(i).cost() >= costVal)
                    ctx.setP1ForwardCannotBlock(i);
            for (int i = 0; i < ctx.p2ForwardCount(); i++)
                if (orLess ? ctx.p2Forward(i).cost() <= costVal : ctx.p2Forward(i).cost() >= costVal)
                    ctx.setP2ForwardCannotBlock(i);
        };
    }
    static Consumer<GameContext> tryParseAllFwdsBlockedOnlyByLowerCostThisTurn(String text) {
        if (!ALL_FWDS_BLOCKED_ONLY_BY_LOWER_COST_THIS_TURN.matcher(text).matches()) return null;
        return ctx -> {
            ctx.logEntry("Effect: Each Forward can only be blocked by a Forward with cost ≤ its own this turn");
            ctx.setAllForwardsCannotBeBlockedByHigherCostThisTurn();
        };
    }
    static Consumer<GameContext> tryParseMayPayCostThenEffect(String text, CardData source, int xValue) {
        Matcher m = MAY_PAY_COST_THEN_EFFECT.matcher(text.trim());
        if (!m.matches()) return null;
        Object[] tally = tallyCostRun(m.group("costs"));
        if (tally == null) return null;
        final int    cp       = (Integer) tally[0];
        final String element  = (String)  tally[1];
        final int    crystals = (Integer) tally[2];
        final String effectText = m.group("effect").trim();
        Consumer<GameContext> effect = parse(effectText, source, xValue);
        if (effect == null) return null;
        return ctx -> ctx.mayPayCostToEffect(cp, element, crystals, effect);
    }
    /**
     * Parses "[you may pay 《X》.] if you don't pay 《X》, [consequence]". The consequence must itself
     * be a supported effect — otherwise the whole ability stays unparsed rather than silently
     * resolving as an unconditional consequence, which is what the bare consequence patterns would
     * do if this gate let the text through.
     */
    static Consumer<GameContext> tryParseIfNotPayOrElse(String text, CardData source, int xValue) {
        Matcher m = IF_NOT_PAY_OR_ELSE.matcher(text.trim());
        if (!m.matches()) return null;
        String cost            = m.group("cost").trim();
        String consequenceText = m.group("consequence").trim();
        Consumer<GameContext> consequence = parse(consequenceText, source, xValue);
        if (consequence == null) return null;

        int    cp       = 0;
        int    crystals = 0;
        String element  = null;
        if (cost.equalsIgnoreCase("C"))        crystals = 1;
        else if (cost.matches("\\d+"))         cp = Integer.parseInt(cost);
        else                                   element = cost;

        final int    fCp = cp, fCrystals = crystals;
        final String fElement = element;
        return ctx -> {
            ctx.logEntry("Effect: Pay 《" + cost + "》, or else: " + consequenceText);
            ctx.mayPayCostOrElse(fCp, fElement, fCrystals, () -> consequence.accept(ctx));
        };
    }
    /**
     * Parses "pay 《…》. When you do so, [followup]." — the payment sentence an auto-ability's
     * effect text can begin with.
     *
     * <p>{@code xValue} reaches the followup, which is where the 《X》 in these texts is spent:
     * "of cost X" on the eight search printings, and "choose X dull Forwards" on 25-057R Cutter.
     * The payment itself is not charged here — {@code AutoAbilityTriggers} intercepts these ahead
     * of {@code parse()} and runs the dialog — so this arm is what names and describes them.
     */
    static Consumer<GameContext> tryParsePayCpWhenDoSo(String text, CardData source, int xValue) {
        Matcher m = PAY_CP_WHEN_DO_SO.matcher(text);
        if (!m.find()) return null;
        String costDesc    = m.group("cost").trim();
        String followupText = m.group("followup").trim();
        Consumer<GameContext> followup = parse(followupText, source, xValue);
        if (followup == null) return null;
        return ctx -> {
            ctx.logEntry("Effect: Pay " + costDesc + " CP, then: " + followupText);
            followup.accept(ctx);
        };
    }
    /** Parses "select 1 [type] of cost N or less other than [name] you control. Put it into the Break Zone." */
    static Consumer<GameContext> tryParseSelectCharCostLeExclToBz(String text) {
        Matcher m = SELECT_1_CHAR_COST_LE_EXCL_TO_BZ.matcher(text.trim());
        if (!m.matches()) return null;
        String type        = m.group("type");
        int    costVal     = Integer.parseInt(m.group("costval"));
        String excludeName = m.group("excludename").trim();
        boolean inclFwd = type.matches("(?i)Forward|Character");
        boolean inclBkp = type.matches("(?i)Backup|Character");
        boolean inclMon = type.matches("(?i)Monster|Character");
        return ctx -> {
            ctx.logEntry("Effect: select 1 " + type + " of cost ≤ " + costVal + " other than " + excludeName + " you control → Break Zone");
            List<ForwardTarget> targets = ctx.selectCharacters(1, false, false, true,
                    null, null, costVal, "less", -1, null,
                    inclFwd, inclBkp, inclMon,
                    null, null, null, excludeName, false, null, false);
            for (ForwardTarget t : targets) ctx.breakTarget(t);
        };
    }
    /**
     * Parses the "Choose 1 [Summon/ability type(s)] [optional 'opponent's']. If your opponent
     * doesn't pay 《N》, cancel its effect." family (Dull's active/action-ability cost form). Builds
     * the target filter the same way as {@link #tryParseCancelAbilityOnStack}, additionally
     * restricting to the opponent's entries when "opponent's" qualifies the type — composed at
     * resolution time since the canceller's side is only known once the effect actually runs.
     */
    static Consumer<GameContext> tryParseCancelStackEntryUnlessPay(String text) {
        Matcher m = CANCEL_STACK_ENTRY_UNLESS_PAY.matcher(text.trim());
        if (!m.find()) return null;
        String types = m.group("types").trim();
        boolean opponentsOnly = m.group("opponents") != null;
        int cost = Integer.parseInt(m.group("cost"));
        java.util.function.Predicate<StackEntry> baseFilter = parseAbilityTypeFilter(types);
        String prompt = "Choose 1 " + (opponentsOnly ? "opponent's " : "") + types + " to threaten:";
        return ctx -> {
            java.util.function.Predicate<StackEntry> filter = opponentsOnly
                    ? baseFilter.and(e -> e.isP1() != ctx.isP1())
                    : baseFilter;
            ctx.logEntry("Effect: Choose 1 " + types + " — cancel unless opponent pays 《" + cost + "》");
            ctx.cancelFilteredAbilityOnStackUnlessOpponentPays(filter, prompt, cost);
        };
    }
    /**
     * Parses the standalone "If your opponent doesn't pay 《N》[ or 《C》…], cancel its/their effect(s)."
     * body of a "chosen by opponent's Summons or abilities" auto-ability. The target is implicit —
     * whatever Summon/ability just triggered this reaction — so it just cancels that in-progress
     * selection. When a Crystal alternative is present (Zeromus), the opponent may pay CP or Crystals.
     */
    static Consumer<GameContext> tryParseCancelChosenTargetUnlessPay(String text) {
        String trimmed = text.trim();
        Matcher m = CANCEL_CHOSEN_TARGET_UNLESS_PAY.matcher(trimmed);
        boolean forward = m.find();
        if (!forward) {
            m = CANCEL_CHOSEN_TARGET_UNLESS_PAY_REVERSED.matcher(trimmed);
            if (!m.find()) return null;
        }
        int cost = Integer.parseInt(m.group("cost"));
        // Only the forward pattern captures the optional Crystal alternative.
        String crystalGroup = forward ? m.group("crystal") : null;
        int crystalCost = crystalGroup == null ? 0 : (int) crystalGroup.chars().filter(c -> c == 'C' || c == 'c').count();
        if (crystalCost > 0) {
            return ctx -> {
                ctx.logEntry("Effect: cancel unless opponent pays 《" + cost + "》 or 《C》×" + crystalCost);
                ctx.cancelChosenSelectionUnlessOpponentPaysOrCrystal(cost, crystalCost);
            };
        }
        return ctx -> {
            ctx.logEntry("Effect: cancel unless opponent pays 《" + cost + "》");
            ctx.cancelChosenSelectionUnlessOpponentPays(cost);
        };
    }
    /**
     * Parses a standalone "If your opponent doesn't pay 《N》, [target action]." (e.g. Remedi's
     * "break it") into an effect that applies the action to the preloaded target(s) — the entering
     * card — unless the opponent pays {@code cost} in full. The inner action is resolved via
     * {@link #parseTargetAction}, so any standard action ("break it", "dull it", "Freeze it", …) works.
     */
    static Consumer<GameContext> tryParseIfOppNotPayAction(String text) {
        Matcher m = IF_OPP_NOT_PAY_ACTION.matcher(text.trim());
        if (!m.find()) return null;
        int cost = Integer.parseInt(m.group("cost"));
        String effText = m.group("effect").trim();
        BiConsumer<GameContext, List<ForwardTarget>> action = parseTargetAction(effText, 0);
        if (action == null) return null;
        return ctx -> {
            List<ForwardTarget> ts = ctx.consumePreloadedTargets();
            if (ts == null || ts.isEmpty()) { ctx.logEntry("If-opp-not-pay: no preloaded target — skipped"); return; }
            ctx.logEntry("Effect: unless opponent pays 《" + cost + "》: " + effText);
            ctx.opponentMayPayToPreventAction(cost, () -> action.accept(ctx, ts));
        };
    }
    static Consumer<GameContext> tryParseGainCrystal(String text) {
        Matcher m = GAIN_CRYSTAL.matcher(text);
        if (!m.find()) return null;
        String crystalRun = m.group("crystals");
        int count = (crystalRun.length()) / "《C》".length();
        return ctx -> {
            ctx.logEntry("Effect: Gain " + count + " Crystal(s)");
            ctx.gainCrystal(count);
        };
    }
    /**
     * The leading effect of "&lt;effect&gt;. Gain 《C》[《C》…].", or {@code null} if the text is not
     * that shape. Shared so parse() and both reporting chains split it identically.
     *
     * <p>Restrictions are stripped first, for the reason {@link #trailingGainCrystalHead}'s
     * counterpart {@code trailingDrawHead} strips them: a use-restriction sentence sitting after
     * the gain would defeat the end-anchor, and restrictions are carried as flags on the ability
     * rather than executed here.
     */
    static String trailingGainCrystalHead(String text) {
        String matchOn = stripRestrictionSentences(text);
        if (matchOn.isEmpty()) matchOn = text;

        Matcher m = TRAILING_GAIN_CRYSTAL_SUFFIX.matcher(matchOn.trim());
        if (!m.find()) return null;

        String head = m.group("head").trim();
        return head.isEmpty() ? null : head;
    }

    /**
     * Composes "&lt;effect&gt;. Gain 《C》[《C》…]." from the leading effect plus the crystal gain.
     *
     * <p>Recurses through {@code parse()} for the head, so the leading effect resolves exactly as
     * it does on its own and this only ever adds the crystals. Returns {@code null} when the head
     * does not parse, leaving such texts to the existing chain rather than half-resolving them.
     */
    static Consumer<GameContext> tryParseTrailingGainCrystal(String text, CardData source, int xValue) {
        String head = trailingGainCrystalHead(text);
        if (head == null) return null;

        String matchOn = stripRestrictionSentences(text);
        if (matchOn.isEmpty()) matchOn = text;
        Matcher m = TRAILING_GAIN_CRYSTAL_SUFFIX.matcher(matchOn.trim());
        if (!m.find()) return null;

        Consumer<GameContext> headEffect = parse(head, source, xValue);
        if (headEffect == null) return null;

        int count = m.group("crystals").length() / "《C》".length();
        return ctx -> {
            headEffect.accept(ctx);
            ctx.logEntry("Effect: Gain " + count + " Crystal(s)");
            ctx.gainCrystal(count);
        };
    }

    static Consumer<GameContext> tryParseGainCrystalIfOpponentHas(String text) {
        if (!GAIN_CRYSTAL_IF_OPPONENT_HAS.matcher(text).find()) return null;
        return ctx -> {
            int opp = ctx.opponentCrystalCount();
            if (opp <= 0) return;
            ctx.logEntry("Effect: Opponent has " + opp + " 《C》 — gain 1 Crystal");
            ctx.gainCrystal(1);
        };
    }
    static Consumer<GameContext> tryParseGainCrystalPerX(String text, int xValue) {
        if (!GAIN_CRYSTAL_PER_X.matcher(text).find()) return null;
        return ctx -> {
            ctx.logEntry("Effect: Gain " + xValue + " Crystal(s) (for each CP paid as X)");
            ctx.gainCrystal(xValue);
        };
    }
    static Consumer<GameContext> tryParseChooseSummonInBzMaxCostFreeCastRfg(String text) {
        Matcher m = CHOOSE_SUMMON_IN_BZ_MAX_COST_FREE_CAST_RFG.matcher(text);
        if (!m.find()) return null;
        final int maxCost = Integer.parseInt(m.group("cost"));
        final java.util.Set<String> excluded = m.group("exclude") != null
                ? parseExcludeElements(m.group("exclude")) : java.util.Set.of();
        String excludeLabel = excluded.isEmpty() ? "" : " other than " + excluded;
        return ctx -> {
            ctx.logEntry("Effect: Choose Summon (cost ≤ " + maxCost + excludeLabel
                    + ") from BZ — cast free, RFG after use");
            ctx.chooseSummonInBzByMaxCostFreeCastRfgAfterUse(maxCost, excluded);
        };
    }
    static Consumer<GameContext> tryParseCostReductionThisTurn(String text) {
        Matcher m = COST_REDUCTION_THIS_TURN.matcher(text);
        if (!m.find()) return null;

        String elementRaw  = m.group("element");
        String categoryRaw = m.group("category");
        // Combined "Job X or Card Name Y" case
        String jobOrRaw    = m.group("joborg");
        String cnameOrRaw  = m.group("cnameborg");
        boolean jobOrName  = jobOrRaw != null;
        String jobRaw      = jobOrName ? jobOrRaw    : m.group("job");
        String cardnameRaw = jobOrName ? cnameOrRaw  : m.group("cardname");
        String typeRaw     = m.group("type");
        int    amount      = Integer.parseInt(m.group("amount"));
        boolean floorAtOne = m.group("floorone") != null;

        boolean inclForwards, inclBackups, inclMonsters, inclSummons;
        if (cardnameRaw != null) {
            inclForwards = inclBackups = inclMonsters = inclSummons = true;
        } else {
            String t = typeRaw != null ? typeRaw.toLowerCase(java.util.Locale.ROOT) : "card";
            inclForwards = t.matches("forwards?|characters?|card");
            inclBackups  = t.matches("backups?|characters?|card");
            inclMonsters = t.matches("monsters?|characters?|card");
            inclSummons  = t.matches("summons?|card");
        }

        final String element  = elementRaw  != null ? elementRaw.trim()  : null;
        final String category = categoryRaw != null ? categoryRaw.trim() : null;
        final String job      = jobRaw      != null ? jobRaw.trim()      : null;
        final String cardname = cardnameRaw != null ? cardnameRaw.trim() : null;
        final String typeDesc = jobOrName   ? "or Card Name " + cardname
                              : cardname    != null ? "Card Name " + cardname
                              : typeRaw     != null ? typeRaw : "card";

        CostReductionModifier modifier = new CostReductionModifier(
                amount, floorAtOne, true,
                inclForwards, inclBackups, inclMonsters, inclSummons,
                element, job, cardname, category, jobOrName);

        String logDesc = "During this turn, next "
                + (element  != null ? element + " " : "")
                + (category != null ? "Category " + category + " " : "")
                + (job      != null ? "Job " + job + " " : "")
                + typeDesc + " costs " + amount + " less" + (floorAtOne ? " (min 1)" : "");

        return ctx -> {
            ctx.logEntry("Effect: " + logDesc);
            ctx.applyNextCastCostReduction(modifier);
        };
    }
    static Consumer<GameContext> tryParsePlayCostReductionThisTurn(String text) {
        Matcher m = PLAY_COST_REDUCTION_THIS_TURN.matcher(text);
        if (!m.find()) return null;

        String elementRaw  = m.group("element");
        String categoryRaw = m.group("category");
        String jobRaw      = m.group("job");
        String cardnameRaw = m.group("cardname");
        String typeRaw     = m.group("type");
        int    amount      = Integer.parseInt(m.group("amount"));
        boolean floorAtOne = m.group("floorone") != null;

        boolean inclForwards, inclBackups, inclMonsters;
        if (cardnameRaw != null) {
            inclForwards = inclBackups = inclMonsters = true;
        } else {
            String t = typeRaw != null ? typeRaw.toLowerCase(java.util.Locale.ROOT) : "characters";
            inclForwards = t.matches("forwards?|characters?");
            inclBackups  = t.matches("backups?|characters?");
            inclMonsters = t.matches("monsters?|characters?");
        }

        final String element  = elementRaw  != null ? elementRaw.trim()  : null;
        final String category = categoryRaw != null ? categoryRaw.trim() : null;
        final String job      = jobRaw      != null ? jobRaw.trim()      : null;
        final String cardname = cardnameRaw != null ? cardnameRaw.trim() : null;
        final String typeDesc = cardname != null ? "Card Name " + cardname
                              : typeRaw  != null ? typeRaw : "Characters";

        CostReductionModifier modifier = new CostReductionModifier(
                amount, floorAtOne, false,
                inclForwards, inclBackups, inclMonsters, false,
                element, job, cardname, category, false);

        String logDesc = "This turn, your "
                + (element  != null ? element + " " : "")
                + (category != null ? "Category " + category + " " : "")
                + (job      != null ? "Job " + job + " " : "")
                + typeDesc + " cost " + amount + " less to play onto the field"
                + (floorAtOne ? " (min 1)" : "");

        return ctx -> {
            ctx.logEntry("Effect: " + logDesc);
            ctx.applyNextCastCostReduction(modifier);
        };
    }
}
