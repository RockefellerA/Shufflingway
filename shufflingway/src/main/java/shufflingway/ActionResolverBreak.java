package shufflingway;

import static shufflingway.ActionResolverPatterns.*;

import static shufflingway.ActionResolver.*;

import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;

/**
 * Break parsers split out of {@link ActionResolver}.
 *
 * <p>Bodies only: {@code ActionResolver} keeps every dispatch chain and calls these
 * through a wildcard static import, so call order -- which is load-bearing, because
 * matchers use {@code find()} -- is unchanged.
 */
final class ActionResolverBreak {

	private ActionResolverBreak() {}

    /**
     * Edea: "Choose 1 Forward opponent controls with a cost inferior or equal to the number
     * of [Element] [Backups/Forwards] you control. Break it."
     */
    static Consumer<GameContext> tryParseChooseOppFwdDynCostBreak(String text) {
        Matcher m = CHOOSE_OPP_FWD_DYN_COST_BREAK.matcher(text);
        if (!m.find()) return null;
        String element  = m.group("element");
        String cardtype = m.group("cardtype").toLowerCase();
        boolean inclFwd = cardtype.startsWith("forward");
        boolean inclBkp = !inclFwd;
        String followupText = m.group("followup").trim();
        if (!followupText.toLowerCase().contains("break it")) return null;
        return ctx -> {
            int ceiling = ctx.selfFieldCount(element, inclFwd, inclBkp, false);
            ctx.logEntry("Choose 1 Forward opponent controls with cost ≤ " + ceiling
                    + " (# " + element + " " + cardtype + " you control)");
            List<ForwardTarget> ts = ctx.selectCharacters(1, false, true, false,
                    null, null, ceiling, "less", -1, null,
                    true, false, false, null, null, null, null, false, null, false);
            ts.forEach(ctx::breakTarget);
        };
    }
    /**
     * Vincent 2-077L: "Choose as many Forwards as you want with a total cost of N or less. Break
     * them."
     *
     * <p>Kept out of the choose chain because its bound is on the selection as a whole: every
     * filter that chain carries decides a card in or out on its own merits, while this one is a
     * budget the player spends across the picks. The selection primitive it calls is what shows
     * the running total and holds the confirm shut until the picks fit inside it.
     *
     * <p>Breaks highest index first per side, as every multi-target break does, so removing one
     * Forward cannot shift the row index of another still waiting.
     */
    static Consumer<GameContext> tryParseChooseForwardsTotalCostBreak(String text) {
        Matcher m = CHOOSE_FORWARDS_TOTAL_COST_BREAK.matcher(text.trim());
        if (!m.matches()) return null;
        int maxTotal = Integer.parseInt(m.group("max"));
        return ctx -> {
            ctx.logEntry("Effect: Choose as many Forwards as you want with a total cost of "
                    + maxTotal + " or less — break them");
            List<ForwardTarget> ts = ctx.selectForwardsWithTotalCostAtMost(maxTotal);
            ctx.recordChosenTargets(ts);
            sortedByIdxDesc(ts, true) .forEach(ctx::breakTarget);
            sortedByIdxDesc(ts, false).forEach(ctx::breakTarget);
        };
    }
    /**
     * Gnash 7-057R: "Choose 1 Forward of cost 1 opponent controls or 1 Monster of cost 2 or less
     * opponent controls. Break it."
     *
     * <p>Two alternative descriptions of one target, which the choose chain has no way to say — its
     * filters narrow a single pool by conjunction. Each half becomes a {@link TargetSpec} and
     * {@link GameContext#selectCharactersEitherSpec} offers their union, so both halves keep every
     * eligibility rule an ordinary choose would apply and the player picks once from one prompt.
     *
     * <p>Only the one break, so no index-descending sort is needed: that guard exists to stop one
     * removal shifting the row index of another still waiting, and there is never another.
     */
    static Consumer<GameContext> tryParseChooseEitherCostSpecBreak(String text) {
        Matcher m = CHOOSE_EITHER_COST_SPEC_BREAK.matcher(text.trim());
        if (!m.matches()) return null;
        TargetSpec first  = costSpec(m.group("noun1"), m.group("cost1"), m.group("cmp1"));
        TargetSpec second = costSpec(m.group("noun2"), m.group("cost2"), m.group("cmp2"));
        String label = describeCostHalf(m.group("noun1"), m.group("cost1"), m.group("cmp1"))
                + " or " + describeCostHalf(m.group("noun2"), m.group("cost2"), m.group("cmp2"));
        return ctx -> {
            ctx.logEntry("Effect: Choose 1 " + label + " opponent controls — break it");
            List<ForwardTarget> ts = ctx.selectCharactersEitherSpec(first, second,
                    "Choose 1 " + label + " (opponent)");
            ctx.recordChosenTargets(ts);
            ts.forEach(ctx::breakTarget);
        };
    }

    /** One half of {@link #tryParseChooseEitherCostSpecBreak}'s choice, as an opponent-only spec. */
    private static TargetSpec costSpec(String noun, String cost, String cmp) {
        String n = noun.toLowerCase(java.util.Locale.ROOT);
        boolean character = n.startsWith("character");
        return new TargetSpec(1, false, true, false, null, null,
                Integer.parseInt(cost), cmp == null ? null : cmp.toLowerCase(java.util.Locale.ROOT),
                -1, null,
                character || n.startsWith("forward"), character || n.startsWith("backup"),
                character || n.startsWith("monster"),
                null, null, null, null, false, null, false, null, false, false);
    }

    /** "Forward of cost 1" / "Monster of cost 2 or less", for the prompt and the log. */
    private static String describeCostHalf(String noun, String cost, String cmp) {
        return noun + " of cost " + cost + (cmp == null ? "" : " or " + cmp);
    }
    /**
     * Kefka 15-071H's Crystal ability. The whole three-sentence text resolves through one
     * primitive, because the two decisions it describes belong to different players and have to be
     * put to them in order — see {@link GameContext#divideOpponentForwardsIntoGroups}.
     *
     * <p>Must precede {@code tryParseIndependentSentences}: no sentence after the first carries a
     * pronoun back to it that the splitter recognises, so left alone that rule would take the three
     * apart and resolve whichever of them it could — which is the last one, putting an unbounded
     * "all the Forwards" into the Break Zone with no division and no choice in front of it.
     */
    static Consumer<GameContext> tryParseDivideOppForwardsIntoGroups(String text) {
        Matcher m = DIVIDE_OPP_FORWARDS_INTO_GROUPS.matcher(text.trim());
        if (!m.matches()) return null;
        int groups = Integer.parseInt(m.group("groups"));
        if (groups < 2) return null;   // "divide into 1 group" would remove nothing and choose nothing
        return ctx -> {
            ctx.logEntry("Effect: Divide the Forwards opponent controls into " + groups
                    + " groups — they keep 1, the rest go to the Break Zone");
            ctx.divideOpponentForwardsIntoGroups(groups);
        };
    }
    /** Parses "Each player selects N [type](s) from their Break Zone and adds it/them to their hand." */
    static Consumer<GameContext> tryParseEachPlayerSalvageFromBreakZone(String text) {
        Matcher m = EACH_PLAYER_SALVAGE_FROM_BREAK_ZONE.matcher(text);
        if (!m.find()) return null;
        int count   = Integer.parseInt(m.group("count"));
        String type = m.group("type");
        String tl   = type.toLowerCase(java.util.Locale.ROOT);
        boolean anyCard = tl.equals("card");
        boolean fwds = anyCard || tl.equals("forward") || tl.equals("character");
        boolean bkps = anyCard || tl.equals("backup")  || tl.equals("character");
        boolean mons = anyCard || tl.equals("monster") || tl.equals("character");
        boolean smns = anyCard;   // "1 card" is unrestricted; every named type excludes Summons
        return ctx -> {
            ctx.logEntry("Effect: Each player salvages " + count + " " + type
                    + "(s) from their Break Zone to hand");
            ctx.eachPlayerSalvageFromBreakZone(count, fwds, bkps, mons, smns);
        };
    }
    /**
     * Parses "Both players select 1 Forward [of cost N or less/more] they control and put it into
     * the Break Zone", including the "… they control. Break them." wording of 28-077R Wicked Thunder.
     */
    static Consumer<GameContext> tryParseBothPlayersSelectForwardToBreakZone(String text) {
        Matcher m = BOTH_PLAYERS_SELECT_FORWARD_TO_BREAK_ZONE.matcher(text);
        if (!m.find()) return null;
        final int    costVal = m.group("cost") != null ? Integer.parseInt(m.group("cost")) : -1;
        final String costCmp = m.group("costcmp");
        String costLabel = costVal < 0 ? "" : " of cost " + costVal + " or " + costCmp;
        return ctx -> {
            ctx.logEntry("Effect: Both players select 1 Forward" + costLabel
                    + " they control and put it into the Break Zone");
            ctx.eachPlayerSelectForwardAndBreak(costVal, costCmp);
        };
    }
    /** Parses "Each player selects up to N Forwards or Monsters they control (select as many as possible). Put them into the Break Zone." */
    static Consumer<GameContext> tryParseEachPlayerSelectUpToNToBreakZone(String text) {
        Matcher m = EACH_PLAYER_SELECT_UP_TO_N_TO_BREAK_ZONE.matcher(text);
        if (!m.find()) return null;
        int    count    = Integer.parseInt(m.group("count"));
        String tgtLower = m.group("targets").toLowerCase();
        boolean inclForwards = tgtLower.contains("forward") || tgtLower.contains("character");
        boolean inclMonsters = tgtLower.contains("monster") || tgtLower.contains("character");
        return ctx -> {
            ctx.logEntry("Effect: Each player selects up to " + count + " Forwards/Monsters and puts them in Break Zone");
            ctx.eachPlayerSelectUpToNAndBreak(count, inclForwards, inclMonsters);
        };
    }
    /**
     * Parses "Your opponent selects up to N Forwards they control. Then, put all the Forwards
     * opponent controls other than the selected Forwards into the Break Zone." — 25-092C Cloud of
     * Darkness.
     *
     * <p>Must precede {@code tryParseIndependentSentences}: the two sentences are tied together
     * only by "the selected Forwards", which that rule does not read as a backward reference, so
     * it took them apart and resolved the second alone — every Forward the opponent controls into
     * the Break Zone with no selection made at all.
     */
    static Consumer<GameContext> tryParseOppSelectsUpToNForwardsBreakRest(String text) {
        Matcher m = OPP_SELECTS_UP_TO_N_FORWARDS_BREAK_REST.matcher(text.trim());
        if (!m.matches()) return null;
        int count = Integer.parseInt(m.group("count"));
        return ctx -> {
            ctx.logEntry("Effect: opponent selects up to " + count
                    + " Forward(s) they control — the rest go to the Break Zone");
            ctx.opponentSelectsUpToNForwardsBreakRest(count);
        };
    }
    /**
     * Parses the two-sided form of the parser above: "Each player selects N Forwards they control."
     * plus a sweep of everything that was not selected — 1-158H and 18-091R Cloud of Darkness, in
     * the imperative and the passive respectively.
     *
     * <p>Must precede {@code tryParseIndependentSentences} for the same reason its sibling does,
     * and the cost of getting it wrong is larger here: the sweep names no side, so resolved on its
     * own it takes every Forward in play rather than only the opponent's.
     */
    static Consumer<GameContext> tryParseEachPlayerSelectsForwardsBreakRest(String text) {
        Matcher m = EACH_PLAYER_SELECTS_FORWARDS_BREAK_REST.matcher(text.trim());
        if (!m.matches()) return null;
        int count = Integer.parseInt(m.group("count"));
        return ctx -> {
            ctx.logEntry("Effect: each player selects " + count
                    + " Forward(s) they control — every other Forward goes to the Break Zone");
            ctx.eachPlayerSelectForwardsBreakRest(count);
        };
    }
    /** Parses "Your opponent randomly removes N card(s) in their hand from the game." */
    static Consumer<GameContext> tryParseOpponentRandomHandRfp(String text) {
        Matcher m = OPPONENT_RANDOM_HAND_RFP.matcher(text);
        if (!m.find()) return null;
        int count = Integer.parseInt(m.group(1));
        return ctx -> {
            ctx.logEntry("Effect: Opponent randomly removes " + count + " hand card(s) from the game");
            ctx.forceOpponentRandomHandRfp(count);
        };
    }
    /**
     * Parses "Your opponent removes N card(s) in their hand from the game."
     * (opponent chooses which cards, not random).
     */
    static Consumer<GameContext> tryParseOpponentHandRfp(String text) {
        Matcher m = OPPONENT_HAND_RFP.matcher(text);
        if (!m.find()) return null;
        int count = Integer.parseInt(m.group(1));
        return ctx -> {
            ctx.logEntry("Effect: Opponent removes " + count + " hand card(s) from the game");
            ctx.forceOpponentHandRfp(count);
        };
    }
    /** Parses "Break [CardName]." when CardName is the source card — breaks the source forward/monster. */
    static Consumer<GameContext> tryParseBreakSourceCard(String text, CardData source) {
        if (source == null) return null;   // the pattern is keyed to the source card
        Matcher m = BREAK_SOURCE_CARD.matcher(text.trim());
        if (!m.matches()) return null;
        String name = m.group("name").trim();
        if (!name.equalsIgnoreCase(source.name()) && !isSelfReference(name)) return null;
        return ctx -> {
            ctx.logEntry("Effect: Break " + source.name());
            ctx.breakSourceCard(source);
        };
    }
    static Consumer<GameContext> tryParseBreakBlockingForward(String text) {
        if (!BREAK_BLOCKING_FORWARD.matcher(text.trim()).matches()) return null;
        return ctx -> {
            ctx.logEntry("Effect: Break the blocking Forward");
            ctx.breakBlockingForward();
        };
    }
    static Consumer<GameContext> tryParseBreakForwardThatBlocksCard(String text) {
        Matcher m = BREAK_FORWARD_THAT_BLOCKS_CARD.matcher(text.trim());
        if (!m.matches()) return null;
        String attackerName = m.group("name").trim();
        return ctx -> {
            ctx.logEntry("Effect: Break the Forward that blocks " + attackerName);
            ctx.breakForwardBlockingAttacker(attackerName);
        };
    }
    static Consumer<GameContext> tryParsePutSourceIntoBreakZone(String text, CardData source) {
        if (source == null) return null;   // the pattern is keyed to the source card's own name
        Matcher m = PUT_SOURCE_INTO_BREAK_ZONE.matcher(text.trim());
        if (!m.matches()) return null;
        if (!m.group("name").trim().equalsIgnoreCase(source.name())) return null;
        return ctx -> {
            ctx.logEntry("Effect: Break " + source.name());
            ctx.breakSourceCard(source);
        };
    }
    /**
     * Parses "&lt;effect&gt; or put &lt;Self&gt; into the Break Zone." — 11-138S Sephiroth's
     * end-of-turn upkeep, the only printing.
     *
     * <p>Before this parser existed the sentence fell through to
     * {@code tryParseRemoveNamedFromGame}, which read "3 cards from your Break Zone" as the name
     * of a card on the field and logged {@code removeNamedCardFromGame: "3 cards from your Break
     * Zone" not found on field}. Neither branch ever ran: the upkeep was free and Sephiroth never
     * left play.
     *
     * <p>The alternative is offered through {@link GameContext#chooseActions}, and the fallback is
     * driven by the effect-progress flag rather than by asking in advance whether the alternative
     * can be met: a player who picks it and turns out not to have the cards has still not met the
     * upkeep, and the card goes. That keeps the branch general — it reads whatever the alternative
     * reports about itself instead of knowing that this one happens to be a Break Zone removal.
     */
    static Consumer<GameContext> tryParseEffectOrPutSelfToBreakZone(String text, CardData source) {
        if (source == null) return null;   // the pattern is keyed to the source card's own name
        Matcher m = EFFECT_OR_PUT_SELF_TO_BZ.matcher(text.trim());
        if (!m.find()) return null;
        if (!m.group("name").trim().equalsIgnoreCase(source.name())) return null;

        String altText = m.group("alt").trim();
        Consumer<GameContext> alt = parse(altText.endsWith(".") ? altText : altText + ".", source);
        if (alt == null) return null;

        // The two options as the player sees them, and the labels chooseActions returns.
        String altLabel = altText.endsWith(".") ? altText : altText + ".";
        String putLabel = "Put " + source.name() + " into the Break Zone.";
        return ctx -> {
            List<String> picked = ctx.chooseActions(source, List.of(altLabel, putLabel), 1, false);
            if (picked != null && !picked.isEmpty() && picked.get(0).equalsIgnoreCase(altLabel)) {
                ctx.resetEffectProgress();
                alt.accept(ctx);
                if (ctx.effectMadeProgress()) return;
                ctx.logEntry(source.name() + " — the alternative could not be met");
            }
            // Reuses the same primitive as tryParsePutSourceIntoBreakZone, which likewise reads
            // the printed "put into the Break Zone" as a break.
            ctx.logEntry("Effect: Break " + source.name());
            ctx.breakSourceCard(source);
        };
    }

    static Consumer<GameContext> tryParseIfOppNoForwardsPutToBreakZone(String text, CardData source) {
        if (source == null) return null;
        Matcher m = IF_OPP_NO_FORWARDS_PUT_TO_BREAK_ZONE.matcher(text.trim());
        if (!m.matches()) return null;
        if (!m.group("name").trim().equalsIgnoreCase(source.name())) return null;
        return ctx -> {
            if (ctx.opponentForwardCount() > 0) return;
            ctx.logEntry("Effect: opponent controls no Forwards — Break " + source.name());
            ctx.breakSourceCard(source);
        };
    }
    /**
     * Parses "If there are N or more cards removed from the game, &lt;effect&gt;".
     * The inner effect only fires when the combined permanent-RFP count of both players meets the threshold.
     */
    static Consumer<GameContext> tryParseIfRfpCount(String text, CardData source) {
        Matcher m = IF_RFP_COUNT_INNER.matcher(text.trim());
        if (!m.find()) return null;
        int minRfp = Integer.parseInt(m.group("count"));
        String innerText = m.group("inner").trim();
        Consumer<GameContext> innerEffect = parse(innerText, source);
        if (innerEffect == null) return null;
        return ctx -> {
            int totalRfp = ctx.countRemovedFromGame();
            if (totalRfp >= minRfp) innerEffect.accept(ctx);
            else ctx.logEntry("Condition not met: need " + minRfp + "+ cards RFP, have " + totalRfp);
        };
    }
    /**
     * Parses "If N or more of your cards have been removed from the game, &lt;effect&gt;" and its
     * relatives — the owner-scoped counterpart of {@link #tryParseIfRfpCount}.
     *
     * <p>The two are disjoint on their wording, but they differ in what they count: this one reads
     * only the ability user's own RFP zone, so an opponent filling theirs does not satisfy it.
     */
    static Consumer<GameContext> tryParseIfSelfRfgCount(String text, CardData source) {
        Matcher m = IF_SELF_RFG_COUNT_INNER.matcher(text.trim());
        if (!m.find()) return null;
        // "any …" is the same test with a threshold of 1.
        int threshold = m.group("count") != null ? Integer.parseInt(m.group("count")) : 1;
        String rawJob = m.group("job");
        // "Job Eikon or Job Dominant" (24-006C Clive) is a disjunction; meetsJobFilter reads it
        // bar-separated, so the printed "or [Job] " joiners have to be rewritten.
        final String jobFilter = rawJob != null
                ? rawJob.trim().replaceAll("(?i)\\s+or\\s+(?:Job\\s+)?", "|")
                : null;
        Consumer<GameContext> innerEffect = parse(m.group("inner").trim(), source);
        if (innerEffect == null) return null;
        return ctx -> {
            int owned = ctx.countSelfRfgCards(null, jobFilter);
            if (owned >= threshold) {
                innerEffect.accept(ctx);
            } else {
                ctx.logEntry("Condition not met: need " + threshold + "+ "
                        + (jobFilter != null ? "Job " + jobFilter + " " : "")
                        + "of your cards removed from the game, have " + owned);
            }
        };
    }
    /**
     * The subject of a "put from the field into the Break Zone this turn" gate, read off the
     * match once at parse time: how many cards the gate wants, of what type, and whose.
     *
     * <p>{@code type} is the filter {@link GameContext#countP1PutFromFieldToBzThisTurn} takes, so
     * {@code null} there means "any Character" — which is what the printed "a Character" asks for.
     * {@code scope} is {@code null} when the card names no controller at all.
     */
    private record PutToBzGate(int threshold, String type, String scope) {

        /** Reads the shared subject groups off a matched gate pattern. */
        static PutToBzGate of(Matcher m) {
            int threshold = m.group("count") != null ? Integer.parseInt(m.group("count")) : 1;
            String rawType = m.group("type").toLowerCase();
            // "Character" spans all three field rows, so it becomes the null type filter; the
            // named types narrow to themselves.
            String type = rawType.startsWith("character") ? null
                    : rawType.startsWith("forward") ? "Forward"
                    : rawType.startsWith("backup")  ? "Backup"
                    : "Monster";
            return new PutToBzGate(threshold, type, m.group("scope"));
        }

        /**
         * Whether the gate is satisfied, logging why not when it is not.
         *
         * <p>The scope is what decides which Break Zone is counted, and the printed wording is
         * deliberate in all three directions: "you controlled" is the ability user's own losses,
         * "opponent controlled" is theirs, and no scope at all — 15-100R Ragelise's "if a Forward
         * has been put from the field into the Break Zone this turn" — means either player's,
         * because the card names no controller.
         */
        boolean met(GameContext ctx) {
            int actual = scope == null ? ctx.countEitherPutFromFieldToBzThisTurn(type)
                    : "opponent".equalsIgnoreCase(scope) ? ctx.countOpponentPutFromFieldToBzThisTurn(type)
                    : ctx.countSelfPutFromFieldToBzThisTurn(type);
            if (actual >= threshold) return true;
            ctx.logEntry("Condition not met: need " + threshold + "+ "
                    + (scope == null ? "" : scope.toLowerCase() + "-controlled ")
                    + (type == null ? "Character" : type)
                    + " put from the field into the Break Zone this turn, have " + actual);
            return false;
        }
    }

    /**
     * Parses "If &lt;N or more&gt; &lt;type&gt; [you|opponent] controlled put from the field into
     * the Break Zone this turn, &lt;effect&gt;" — a gate wrapping an arbitrary effect.
     *
     * <p>Must be dispatched ahead of the inner-effect parsers, for the reason spelled out at its
     * call site in {@link ActionResolver#parse}: they match with {@code find()}, so any of them
     * would claim the gated tail and run it unconditionally. That is exactly what happened to all
     * three of these cards before this parser existed — 24-024R Shiva (XVI) discarded a card at
     * the end of every one of its controller's turns, and 15-035H Setzer and 15-100R Ragelise
     * gained 《C》 unconditionally.
     */
    static Consumer<GameContext> tryParseIfPutFromFieldToBzThisTurn(String text, CardData source) {
        Matcher m = IF_PUT_FROM_FIELD_TO_BZ_THIS_TURN_INNER.matcher(text.trim());
        if (!m.find()) return null;
        Consumer<GameContext> innerEffect = parse(m.group("inner").trim(), source);
        if (innerEffect == null) return null;
        PutToBzGate gate = PutToBzGate.of(m);
        return ctx -> {
            if (gate.met(ctx)) innerEffect.accept(ctx);
        };
    }

    /**
     * Parses the replacement form of the same gate — "&lt;base&gt;. If &lt;gate&gt;, &lt;alt&gt;
     * instead." (22-060H Ghido).
     *
     * <p>Unlike its sibling above this one failed closed rather than open: the whole compound
     * parsed as the base effect alone, so Ghido always placed 1 Knowledge Counter and the
     * 3-counter branch was unreachable. Both halves have to parse for the pair to be claimed, so a
     * compound whose "instead" half is not yet supported still falls through to the base effect
     * rather than being swallowed and silently reduced to nothing.
     */
    static Consumer<GameContext> tryParseIfPutFromFieldToBzThisTurnInstead(String text, CardData source) {
        Matcher m = PUT_FROM_FIELD_TO_BZ_THIS_TURN_INSTEAD.matcher(text.trim());
        if (!m.find()) return null;
        Consumer<GameContext> baseEffect = parse(m.group("base").trim(), source);
        Consumer<GameContext> altEffect  = parse(m.group("alt").trim(), source);
        if (baseEffect == null || altEffect == null) return null;
        PutToBzGate gate = PutToBzGate.of(m);
        return ctx -> {
            if (gate.met(ctx)) altEffect.accept(ctx);
            else baseEffect.accept(ctx);
        };
    }

    /**
     * Parses the mid-sentence form of the gate — "&lt;lead&gt;. If &lt;gate&gt;, &lt;tail&gt;."
     * (16-021C Rain).
     *
     * <p>The two halves are rejoined rather than parsed separately: the tail's "it" names what the
     * lead chose, so only the combined sentence is something {@link ActionResolver#parse} can
     * make sense of. The gate then wraps the pair, which also decides the question the printed
     * text leaves open — whether the choice still happens when the condition fails. It does not,
     * and nothing observable turns on it, since choosing a target and then doing nothing to it is
     * the same board either way.
     */
    static Consumer<GameContext> tryParseIfPutFromFieldToBzThisTurnMidGate(String text, CardData source) {
        Matcher m = PUT_FROM_FIELD_TO_BZ_THIS_TURN_MIDGATE.matcher(text.trim());
        if (!m.find()) return null;
        Consumer<GameContext> effect = parse(m.group("lead").trim() + " " + m.group("tail").trim(), source);
        if (effect == null) return null;
        PutToBzGate gate = PutToBzGate.of(m);
        return ctx -> {
            if (gate.met(ctx)) effect.accept(ctx);
        };
    }

    static Consumer<GameContext> tryParseOpponentPutsForwardToBreakZone(String text) {
        Matcher m = OPPONENT_PUTS_FORWARD_TO_BREAK_ZONE_PATTERN.matcher(text);
        if (!m.find()) return null;

        int     count     = Integer.parseInt(m.group("count"));
        String  condition = m.group("condition");
        String  targets   = m.group("targets");
        String  tgtLower  = targets.toLowerCase();
        boolean inclForwards = tgtLower.contains("forward") || tgtLower.contains("character");
        boolean inclMonsters = tgtLower.contains("character");

        String condLabel = condition != null ? " " + condition : "";
        String logLabel  = "Opponent puts " + count + condLabel + " " + targets
                         + " they control → Break Zone";

        return ctx -> {
            ctx.logEntry("Effect: " + logLabel);
            List<ForwardTarget> ts = ctx.selectCharacters(count, false, true, false,
                    condition, null, -1, null, -1, null,
                    inclForwards, false, inclMonsters, null, null, null, null, false, null, false);
            sortedByIdxDesc(ts, false).forEach(ctx::forceTargetToBreakZone);
        };
    }
    static Consumer<GameContext> tryParsePlayAllByNameFromBreakZone(String text) {
        Matcher m = PLAY_ALL_FROM_BREAK_ZONE_PATTERN.matcher(text.trim());
        if (!m.find()) return null;
        String cardName = m.group("cardname").trim();
        boolean dull = m.group("dull") != null;
        return ctx -> {
            ctx.logEntry("Effect: Play all Card Name " + cardName + " from Break Zone → field" + (dull ? " dull" : ""));
            ctx.playAllByNameFromOwnBreakZoneDull(cardName, dull);
        };
    }
    static Consumer<GameContext> tryParsePlaySourceFromBreakZone(String text, CardData source) {
        if (source == null) return null;
        Matcher m = PLAY_SOURCE_FROM_BREAK_ZONE.matcher(text.trim());
        if (!m.matches()) return null;
        String name = m.group("name").trim();
        if (!name.equalsIgnoreCase(source.name())) return null;
        boolean dull = m.group("dull") != null;
        return ctx -> {
            ctx.logEntry("Effect: Play " + name + " from Break Zone → field" + (dull ? " dull" : ""));
            ctx.playAllByNameFromOwnBreakZoneDull(name, dull);
        };
    }

    /**
     * Parses "Break [Self] as well as the Forward that blocks or is blocked by [Self]." -
     * 2-114C Ninja, a Forward that trades itself for whatever it is in Battle with.
     *
     * <p>The partner is resolved at resolution time rather than chosen: the text names it by its
     * role in the current Battle, and that pairing can change between activation and resolution.
     * Both halves of the sentence must name the source, which is what keeps this off any text
     * describing some other card's Battle.
     *
     * <p>Ninja breaks whether or not it is in a Battle - "break [Self]" is unconditional, and
     * only the second half depends on there being a partner.
     */
    static Consumer<GameContext> tryParseBreakSelfAndBattlePartner(String text, CardData source) {
        if (source == null) return null;
        Matcher m = BREAK_SELF_AND_BATTLE_PARTNER.matcher(text.trim());
        if (!m.matches()) return null;
        String name = m.group("name").trim();
        if (!name.equalsIgnoreCase(source.name())) return null;
        if (!m.group("name2").trim().equalsIgnoreCase(source.name())) return null;
        return ctx -> {
            ForwardTarget partner = ctx.combatBattlePartnerOf(name);
            if (partner == null) {
                ctx.logEntry("Effect: " + name + " is not in a Battle - only " + name + " breaks");
            } else {
                ctx.logEntry("Effect: Break " + name + " and the Forward it is in Battle with");
                ctx.breakTarget(partner);
            }
            // After the partner: breaking the source first would shift the indices the partner
            // target was resolved against when both are on the same side of the field.
            ctx.breakSourceCard(source);
        };
    }
}
