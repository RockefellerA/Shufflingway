package shufflingway;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.regex.Matcher;

import static shufflingway.ActionResolver.*;
import static shufflingway.ActionResolverPatterns.*;

/**
 * State parsers split out of {@link ActionResolver}.
 *
 * <p>Bodies only: {@code ActionResolver} keeps every dispatch chain and calls these
 * through a wildcard static import, so call order -- which is load-bearing, because
 * matchers use {@code find()} -- is unchanged.
 */
final class ActionResolverState {

	private ActionResolverState() {}

    /**
     * Parses Alhanalem 18-018R's "During this turn, if a Character enters the field by your
     * opponent's Summons or abilities, remove it from the game instead."
     *
     * <p>Nothing is chosen and nothing happens on resolution: the effect arms a replacement that
     * the field-entry paths consult for the rest of the turn.
     */
    static Consumer<GameContext> tryParseOppFieldEntryRfgInstead(String text) {
        if (!STANDALONE_OPP_FIELD_ENTRY_RFG_INSTEAD.matcher(text.trim()).find()) return null;
        return GameContext::setOppFieldEntryRemovedFromGameThisTurn;
    }
    /** Parses "Dull [CardName]." — dulls the source card with no other effect. */
    static Consumer<GameContext> tryParseStandaloneSelfDull(String text, CardData source) {
        if (source == null) return null;
        Matcher m = STANDALONE_SELF_DULL.matcher(text.trim());
        if (!m.find()) return null;
        String subject = m.group("subject").trim();
        if (!subject.equalsIgnoreCase(source.name())) return null;
        return ctx -> {
            ctx.logEntry(source.name() + " — dulled");
            ctx.dullSourceForward(source);
        };
    }
    /**
     * Parses "Dull [CardName]. [CardName] gains '[...] cannot be broken.' until end of turn."
     * Dulls the source then shields it. Must be tried before {@link #tryParseStandaloneShieldCannotBeBroken}
     * so the dull step is not silently dropped.
     */
    static Consumer<GameContext> tryParseStandaloneSelfDullAndShield(String text, CardData source) {
        if (source == null) return null;
        Matcher m = STANDALONE_SELF_DULL_AND_SHIELD_CANNOT_BE_BROKEN.matcher(text);
        if (!m.find()) return null;
        String subject = m.group("subject").trim();
        if (!subject.equalsIgnoreCase(source.name())) return null;
        return ctx -> {
            ctx.logEntry(source.name() + " — Dull self and cannot be broken until end of turn");
            ctx.dullSourceForward(source);
            ctx.shieldSourceForward(source);
        };
    }
    /**
     * Parses "If all the [Type] you control have [Element] Element, [effect]." —
     * resolves the inner effect only when every controlled card of that type has the element.
     */
    static Consumer<GameContext> tryParseIfAllHaveElement(String text, CardData source, int xValue) {
        Matcher m = IF_ALL_HAVE_ELEMENT_GATE.matcher(text.trim());
        if (!m.matches()) return null;
        String typeRaw  = m.group("type").trim();
        String element  = m.group("element").trim();
        String normType = typeRaw.replaceAll("(?i)s$", "");
        normType = Character.toUpperCase(normType.charAt(0)) + normType.substring(1).toLowerCase();
        Consumer<GameContext> inner = parse(m.group("effect").trim(), source, xValue);
        if (inner == null) return null;
        ControlCondition cc = ControlCondition.forAllHave(normType, element, null);
        String logType = typeRaw;
        String logElem = element;
        return ctx -> {
            if (ctx.controlConditionMet(cc)) {
                ctx.logEntry("Effect: all " + logType + " have " + logElem + " Element — condition met");
                inner.accept(ctx);
            } else {
                ctx.logEntry("Effect: not all " + logType + " have " + logElem + " Element — skipped");
            }
        };
    }
    /** Parses "if there are N or more different Elements among [type] you control, [effect]." */
    static Consumer<GameContext> tryParseIfNDiffElements(String text, CardData source, int xValue) {
        Matcher m = IF_N_DIFF_ELEMENTS_AMONG.matcher(text.trim());
        if (!m.matches()) return null;
        int    min     = Integer.parseInt(m.group("min"));
        String typeRaw = m.group("type").trim();
        String typeLow = typeRaw.toLowerCase(java.util.Locale.ROOT);
        boolean inclFwd = typeLow.startsWith("forward") || typeLow.startsWith("character");
        boolean inclBkp = typeLow.startsWith("backup")  || typeLow.startsWith("character");
        boolean inclMon = typeLow.startsWith("monster")  || typeLow.startsWith("character");
        Consumer<GameContext> inner = parse(m.group("effect").trim(), source, xValue);
        if (inner == null) return null;
        return ctx -> {
            int distinct = ctx.selfDistinctElementCount(inclFwd, inclBkp, inclMon);
            if (distinct >= min) {
                ctx.logEntry("Effect: " + distinct + " distinct element(s) among " + typeRaw + "s — condition met");
                inner.accept(ctx);
            } else {
                ctx.logEntry("Effect: only " + distinct + " distinct element(s) among " + typeRaw + "s (need " + min + ") — skipped");
            }
        };
    }
    static Consumer<GameContext> tryParseRemoveAllCountersFromSelf(String text, CardData source) {
        Matcher m = REMOVE_ALL_COUNTERS_FROM_SELF.matcher(text.trim());
        if (!m.matches() || source == null) return null;
        String counterName = m.group("name").trim();
        return ctx -> {
            int n = ctx.getCounters(source, counterName);
            if (n > 0) ctx.removeCounters(source, counterName, n);
        };
    }
    /**
     * Parses "select 1 Element. &lt;CardName&gt; becomes that Element[.]" — the named card's
     * element is permanently overridden via {@link GameContext#setCardElement}.  Returns
     * {@code null} unless {@code source} is non-null and its name equals the captured name,
     * preventing accidental matches when this parser appears in the general {@link #parse} chain.
     */
    static Consumer<GameContext> tryParseElementChange(String text, CardData source) {
        Matcher m = ELEMENT_CHANGE_PATTERN.matcher(text);
        if (!m.find()) return null;
        String cardName = m.group("name").trim();
        if (source == null || !cardName.equalsIgnoreCase(source.name())) return null;
        return ctx -> {
            String elem = ctx.selectElement("Select 1 Element (" + cardName + " becomes that Element):");
            if (elem != null) ctx.setCardElement(cardName, elem);
        };
    }
    static Consumer<GameContext> tryParseGrantPartyAnyElementThisTurn(String text) {
        if (!GRANT_PARTY_ANY_ELEMENT_THIS_TURN.matcher(text).find()) return null;
        return ctx -> {
            ctx.logEntry("Effect: Forwards you control can form a party with Forwards of any Element this turn");
            ctx.grantForwardsPartyAnyElementThisTurn();
        };
    }
    static Consumer<GameContext> tryParseNameElementOnlySelfBecomes(String text, CardData source) {
        if (source == null) return null;
        Matcher m = NAME_ELEMENT_ONLY_SELF_BECOMES.matcher(text);
        if (!m.find()) return null;
        if (!m.group("name").trim().equalsIgnoreCase(source.name())) return null;
        java.util.Set<String> excluded = parseExcludeElements(m.group("exclude"));
        return ctx -> {
            ctx.logEntry("Effect: Name 1 Element — " + source.name() + " becomes named Element until end of turn");
            String elem = ctx.selectElement("Name 1 Element (" + source.name() + " becomes it):", excluded);
            if (elem == null) return;
            ctx.changeSourceCardElementUntilEOT(source, elem);
        };
    }
    static Consumer<GameContext> tryParseNameElementAndJobSelfBecomes(String text, CardData source) {
        if (source == null) return null;
        Matcher m = NAME_ELEMENT_AND_JOB_SELF_BECOMES.matcher(text);
        if (!m.find()) return null;
        if (!m.group("name").trim().equalsIgnoreCase(source.name())) return null;
        java.util.Set<String> excluded = parseExcludeElements(m.group("exclude"));
        return ctx -> {
            ctx.logEntry("Effect: Name 1 Element and 1 Job — " + source.name() + " becomes both until end of turn");
            String[] choice = ctx.selectElementAndJob("Name 1 Element and 1 Job (" + source.name() + " becomes both):", excluded);
            if (choice == null || choice[0] == null || choice[1] == null) return;
            ctx.changeSourceCardElementAndJobUntilEOT(source, choice[0], choice[1]);
        };
    }
    static Consumer<GameContext> tryParseNameJobAndElementSelfGainsPermanent(String text, CardData source) {
        if (source == null) return null;
        Matcher m = NAME_JOB_AND_ELEMENT_SELF_GAINS_PERMANENT.matcher(text);
        if (!m.find()) return null;
        if (!m.group("name").trim().equalsIgnoreCase(source.name())) return null;
        java.util.Set<String> excluded = parseExcludeElements(m.group("exclude"));
        return ctx -> {
            ctx.logEntry("Effect: Name 1 Job and 1 Element — " + source.name() + " gains both permanently");
            String[] choice = ctx.selectElementAndJob("Name 1 Job and 1 Element (" + source.name() + " gains both):", excluded);
            if (choice == null || choice[0] == null || choice[1] == null) return;
            ctx.setCardElement(source.name(), choice[0]);
            ctx.addCardJobPermanently(source.name(), choice[1]);
        };
    }
    static Consumer<GameContext> tryParseChooseCounterScaleCharsActivate(String text, int xValue) {
        Matcher m = CHOOSE_COUNTER_SCALE_CHARS_ACTIVATE.matcher(text);
        if (!m.find()) return null;
        final int    count       = xValue;
        final String counterName = m.group("counterName").trim();
        return ctx -> {
            if (count <= 0) {
                ctx.logEntry("Effect: " + counterName + " Counter choose/activate — 0 counters, nothing to do");
                return;
            }
            ctx.logEntry("Effect: Choose up to " + count + " Characters (" + counterName + " Counters) — Activate");
            List<ForwardTarget> ts = selectTargets(ctx, count, true,
                    false, true, null, null, null, false,
                    -1, null, -1, null,
                    true, true, true, null, null, null, null, false, null, false);
            sortedByIdxDesc(ts, true) .forEach(t -> ctx.activateTarget(t));
            sortedByIdxDesc(ts, false).forEach(t -> ctx.activateTarget(t));
        };
    }
    static Consumer<GameContext> tryParseRemoveAllCounters(String text, CardData source) {
        Matcher m = REMOVE_ALL_COUNTERS.matcher(text);
        if (!m.find()) return null;
        String name   = m.group("name").trim();
        String target = m.group("target").trim();
        // Only handle self-removal (target matches the source card's name)
        if (source == null || !source.name().equalsIgnoreCase(target)) return null;
        return ctx -> {
            int current = ctx.getCounters(source, name);
            if (current <= 0) {
                ctx.logEntry("Effect: Remove all " + name + " Counters from " + source.name() + " — none present");
                return;
            }
            ctx.removeCounters(source, name, current);
        };
    }
    static Consumer<GameContext> tryParsePlaceCounters(String text, CardData source) {
        Matcher m = PLACE_COUNTERS.matcher(text);
        if (!m.find()) return null;
        int    count      = Integer.parseInt(m.group("count"));
        String name       = m.group("name").trim();
        String target     = m.group("target").trim();
        // Only handle self-placement (target matches the source card's name)
        if (source == null || !source.name().equalsIgnoreCase(target)) return null;
        return ctx -> {
            ctx.logEntry("Effect: Place " + count + " " + name + " Counter(s) on " + source.name());
            ctx.placeCounters(source, name, count);
        };
    }
    /**
     * Parses "Place N [Name] Counter(s) on each Job [X] you control." — 15-011L Palom and 15-119L
     * Porom's end-of-turn tick, which grows every Apprentice Mage on their side and not just
     * themselves.
     *
     * <p>Takes no source: the sweep is defined by the Job filter, so the printing card is included
     * only if it carries the Job, which is exactly what the text says.
     */
    static Consumer<GameContext> tryParsePlaceCountersOnEachJob(String text) {
        Matcher m = PLACE_COUNTERS_ON_EACH_JOB.matcher(text.trim());
        if (!m.matches()) return null;
        int    count = Integer.parseInt(m.group("count"));
        String name  = m.group("name").trim();
        String job   = m.group("job").trim();
        return ctx -> {
            ctx.logEntry("Effect: Place " + count + " " + name + " Counter(s) on each Job " + job + " you control");
            ctx.placeCountersOnOwnJobCards(name, count, job);
        };
    }
    static Consumer<GameContext> tryParsePlaceCountersForEach(String text, CardData source) {
        Matcher m = PLACE_COUNTERS_FOR_EACH.matcher(text.trim());
        if (!m.matches()) return null;
        int    baseCount  = Integer.parseInt(m.group("count"));
        String name       = m.group("name").trim();
        String target     = m.group("target").trim();
        if (source == null || !source.name().equalsIgnoreCase(target)) return null;
        String typeRaw    = m.group("type");
        String cardType   = Character.toUpperCase(typeRaw.charAt(0))
                + typeRaw.substring(1).toLowerCase().replaceAll("s$", "");
        return ctx -> {
            int total = baseCount * ctx.ownFieldCount(cardType);
            ctx.logEntry("Effect: Place " + baseCount + " " + name + " Counter(s) per " + cardType
                    + " you control (" + total + " total) on " + source.name());
            if (total > 0) ctx.placeCounters(source, name, total);
        };
    }
    /**
     * Parses "Remove N Warp Counter(s) from [CardName][ for each [filter] you control]" —
     * 21-007L Shadow's end-of-turn thaw, which comes off faster the wider its Category VI board is.
     *
     * <p>The card is required to be the source, as the sibling counter parsers above require it:
     * the printed texts all name the card the ability is on, and a Warp Counter can only be
     * removed from a card in its own controller's Warp zone.
     *
     * <p>Must precede {@code tryParseRemoveNamedFromGame}, whose lazy {@code named} group and
     * {@code find()} otherwise read "Remove <b>1 Warp Counter from Shadow …</b> from the game" out
     * of this sentence and hand that whole string to {@code removeNamedCardFromGame}.
     */
    static Consumer<GameContext> tryParseRemoveWarpCountersFromNamed(String text, CardData source) {
        Matcher m = REMOVE_WARP_COUNTERS_FROM_NAMED.matcher(text.trim());
        if (!m.matches()) return null;
        String name = m.group("name").trim();
        if (source == null || !source.name().equalsIgnoreCase(name)) return null;

        int    perUnit  = Integer.parseInt(m.group("count"));
        String typeRaw  = m.group("type");
        String element  = m.group("element");
        String category = m.group("category");
        String job      = m.group("job") != null ? m.group("job").trim() : null;

        if (typeRaw == null) {
            // Flat removal, no multiplier.
            return ctx -> {
                ctx.logEntry("Effect: Remove " + perUnit + " Warp Counter(s) from " + name);
                ctx.removeWarpCountersFromNamed(name, perUnit);
            };
        }

        String tgtLower = typeRaw.toLowerCase();
        boolean inclForwards = tgtLower.startsWith("forward")  || tgtLower.startsWith("character");
        boolean inclBackups  = tgtLower.startsWith("backup")   || tgtLower.startsWith("character");
        boolean inclMonsters = tgtLower.startsWith("monster")  || tgtLower.startsWith("character");
        String label = (element != null ? element + " " : "")
                + (category != null ? "Category " + category + " " : "")
                + (job != null ? "Job " + job + " " : "") + typeRaw;

        return ctx -> {
            int units = ctx.countSelfFieldCards(inclForwards, inclBackups, inclMonsters,
                    job, null, category, element);
            int total = perUnit * units;
            ctx.logEntry("Effect: Remove " + perUnit + " Warp Counter(s) per " + label
                    + " you control (" + units + " → " + total + ") from " + name);
            if (total > 0) ctx.removeWarpCountersFromNamed(name, total);
        };
    }
    /**
     * Parses "Activate &lt;cardName&gt;[.]" — activates named card(s) the ability user controls.
     * Handles single plain names ("Activate <cardName>"), "Card Name X" notation, and
     * "Card Name X and Card Name Y [you control]" multi-target form.
     */
    static Consumer<GameContext> tryParseActivateNamedCard(String text) {
        Matcher m = ACTIVATE_NAMED_CARD.matcher(text);
        if (!m.find()) return null;

        String raw = m.group("card").trim();
        // Strip optional trailing "you control"
        raw = raw.replaceAll("(?i)\\s+you\\s+control$", "").trim();

        // Build list of card names, handling "Card Name X [and Card Name Y]" form
        List<String> names = new ArrayList<>();
        if (raw.matches("(?i)Card\\s+Name.*")) {
            String[] parts = ACTIVATE_AND_CARD_NAME_SPLIT.split(raw);
            for (String part : parts)
                names.add(part.replaceAll("(?i)^Card\\s+Name\\s+", "").trim());
        } else {
            names.add(raw);
        }

        return ctx -> {
            ctx.logEntry("Effect: Activate " + String.join(", ", names));
            for (String name : names) {
                List<ForwardTarget> ts = ctx.selectCharacters(
                        1, false, false, true, null, null, -1, null, -1, null,
                        true, true, true, null, name, null, null, false, null, false);
                ts.forEach(ctx::activateTarget);
            }
        };
    }

    /**
     * A bare followup action whose target is the card that fired the trigger, not one the player
     * chooses — 26-032L Charlotte, "When a Character enters your opponent's field, dull it and
     * Freeze it."
     *
     * <p>The action itself is resolved by {@link ActionResolver#parseTargetAction}, the same
     * builder the Choose family uses for its followups; only the target differs. The trigger side
     * supplies it via {@link GameContext#preloadTargets}, exactly as the Remedi-style
     * "enters opponent's field not from hand" watchers already do.
     */
    static Consumer<GameContext> tryParseTriggeredTargetAction(String text, int xValue) {
        String t = text.trim();
        if (!TRIGGERED_TARGET_ACTION_BARE.matcher(t).matches()) return null;

        BiConsumer<GameContext, List<ForwardTarget>> action = parseTargetAction(t, xValue);
        if (action == null) return null;

        return ctx -> {
            List<ForwardTarget> ts = ctx.consumePreloadedTargets();
            if (ts == null || ts.isEmpty()) {
                ctx.logEntry("Triggered action: no preloaded target — skipped");
                return;
            }
            ctx.logEntry("Effect: " + t + " (on the triggering card)");
            action.accept(ctx, ts);
        };
    }
}
