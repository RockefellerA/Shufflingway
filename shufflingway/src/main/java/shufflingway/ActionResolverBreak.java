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
    /** Parses "Both players select 1 Forward they control and put it into the Break Zone." */
    static Consumer<GameContext> tryParseBothPlayersSelectForwardToBreakZone(String text) {
        Matcher m = BOTH_PLAYERS_SELECT_FORWARD_TO_BREAK_ZONE.matcher(text);
        if (!m.find()) return null;
        return ctx -> {
            ctx.logEntry("Effect: Both players select 1 Forward they control and put it into the Break Zone");
            ctx.eachPlayerSelectForwardAndBreak();
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
