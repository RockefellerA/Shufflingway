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
    /**
     * Parses the imperative "dull N active [filter] you control" — the self-dull price paid in
     * front of a "When you do so, …" payoff (29-050C Chocobo, 12-033R Snow, 24-072C Leo, …).
     *
     * <p>Resolves by delegating to the equivalent "choose N active [filter] you control. Dull it.",
     * which the choose chain already parses in full — target selection, the {@code active} state
     * gate, and every element/job/category filter shape these nine cards use. Restating that here
     * would be a second, divergent copy of the same targeting rules.
     *
     * <p>Until this parsed, {@link ActionResolver#tryParseWhenYouDoSoSequence} could not resolve
     * its primary half and gave up, and the whole text fell through to the choose chain — which
     * matched the <em>payoff</em> alone and dropped the price. Snow dulled nothing and still froze
     * a Forward.
     */
    static Consumer<GameContext> tryParseDullActiveYouControl(String text, CardData source, int xValue) {
        Matcher m = DULL_N_ACTIVE_YOU_CONTROL.matcher(text.trim());
        if (!m.matches()) return null;
        String count  = m.group("count");
        String filter = m.group("filter").trim();
        // "Dull it." reads as "dull each chosen card" downstream, so one clause covers any count.
        Consumer<GameContext> inner = parse(
                "choose " + count + " active " + filter + " you control. Dull it.", source, xValue);
        if (inner == null) return null;
        return ctx -> {
            inner.accept(ctx);
            // This clause is a price, so an empty selection has to stop the "When you do so"
            // payoff. The choose chain leaves the progress flag alone when nothing is picked —
            // correct for the "up to" effects that share it, where taking zero is a legal play —
            // so the fizzle is marked here rather than in that shared path.
            if (ctx.lastChosenTargets().isEmpty()) {
                ctx.logEntry("Nothing dulled — no eligible active card, or none chosen");
                ctx.markEffectFizzled();
            }
        };
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
    /**
     * Parses an effect that refers back to a Job the source named earlier — Jack Garland 27-111L's
     * "At the end of each of your turns, choose 1 Forward with the named Job. Remove it from the
     * game." He is the only printing that separates the naming from the use: his entry trigger
     * names a Job and this fires every one of his controller's end phases afterwards.
     *
     * <p>The Job is a fact of the game state, not of the text, so it is substituted at resolution
     * and the rewritten sentence goes through the ordinary chain — the same treatment Gulool Ja
     * Ja's "that Forward" and "the same amount" get. Nothing here has to know what "choose 1
     * Forward … . Remove it from the game." means; the choose chain already does, and every
     * filter and followup it understands comes along for free.
     *
     * <p>The shape is still checked at parse time, with a placeholder Job standing in for the one
     * that will be named, so an effect the chain cannot read stays visibly unparsed rather than
     * becoming a consumer that resolves to nothing.
     *
     * <p>Declines when the text names a Job itself: those printings spend it in the same sentence
     * and have nothing recorded to read back.
     */
    /**
     * Parses "all Characters opponent controls lose their Jobs until the end of the turn." —
     * Exdeath 3-100L's attack trigger, and the corpus's only Job removal.
     */
    static Consumer<GameContext> tryParseOppLoseJobsUntilEot(String text) {
        Matcher m = ALL_OPP_LOSE_JOBS_UNTIL_EOT.matcher(text.trim());
        if (!m.matches()) return null;
        String t = m.group("targets").toLowerCase(java.util.Locale.ROOT);
        boolean inclForwards = t.startsWith("forward") || t.startsWith("character");
        boolean inclBackups  = t.startsWith("backup")  || t.startsWith("character");
        boolean inclMonsters = t.startsWith("monster") || t.startsWith("character");
        return ctx -> {
            ctx.logEntry("Effect: All " + m.group("targets")
                    + " opponent controls lose their Jobs until end of turn");
            ctx.opponentCharactersLoseJobsUntilEndOfTurn(inclForwards, inclBackups, inclMonsters);
        };
    }

    /**
     * Parses "[Self] loses all its abilities until the end of the turn." standing alone —
     * Airborne Trooper 9-024C.
     *
     * <p>Self-named and checked by equality: every other printing of this sentence is a choose
     * followup whose "it" is the card the choice named, and those must keep reaching the followup
     * chain rather than stripping the card that printed them.
     */
    static Consumer<GameContext> tryParseStandaloneSelfLosesAllAbilities(String text, CardData source) {
        if (source == null) return null;
        Matcher m = STANDALONE_SELF_LOSES_ALL_ABILITIES.matcher(text.trim());
        if (!m.matches()) return null;
        if (!m.group("name").trim().equalsIgnoreCase(source.name())) return null;
        return ctx -> ctx.selfLoseAllAbilitiesUntilEndOfTurn(source);
    }

    /**
     * Parses "if you [don't ]have a 《C》, [effect]" — Kain 15-048L.
     *
     * <p>A gate over an effect the chain already reads, like the cast-payment gates: the Crystal
     * count is settled at resolution and the guarded half goes through {@code parse()} as itself.
     * Returns {@code null} when that half does not parse, so an unimplemented payoff stays visible
     * rather than becoming a check that does nothing.
     */
    static Consumer<GameContext> tryParseCrystalHeldGate(String text, CardData source, int xValue) {
        Matcher m = CRYSTAL_HELD_GATE.matcher(text.trim());
        if (!m.matches()) return null;
        boolean negated = m.group("negated") != null;
        Consumer<GameContext> inner = parse(m.group("inner").trim(), source, xValue);
        if (inner == null) return null;
        return ctx -> {
            boolean holds = ctx.crystalCount() > 0;
            if (holds == negated) {
                ctx.logEntry("Effect: " + (negated ? "you hold a 《C》" : "you hold no 《C》")
                        + " — skipped");
                return;
            }
            inner.accept(ctx);
        };
    }

    /**
     * Parses "if N or more [X] Counters are placed on [Self], [effect]" — Number 24 20-036H.
     *
     * <p>Self-named like the rest of this card's counter machinery, and a gate over an effect the
     * chain already reads — "dull Number 24" is the standalone self-dull. Counters are held per
     * copy, so the count is asked of the card that printed the sentence rather than of its name.
     */
    static Consumer<GameContext> tryParseCountersOnSelfGate(String text, CardData source, int xValue) {
        if (source == null) return null;
        Matcher m = COUNTERS_ON_SELF_GATE.matcher(text.trim());
        if (!m.matches()) return null;
        if (!m.group("name").trim().equalsIgnoreCase(source.name())) return null;
        // One sentence only. A text that goes on to say something further about the same counters
        // — Aerith 16-067L's "Then, if there are no Reraise Counters on Aerith, play Aerith onto
        // the field." — is a shape this gate cannot see the whole of, and has a parser of its own.
        if (ActionResolverChoose.sentenceBreakOutsideQuotes(m.group("inner").trim()) >= 0) return null;
        int    required = Integer.parseInt(m.group("count"));
        String counter  = m.group("counter").trim();
        Consumer<GameContext> inner = parse(m.group("inner").trim(), source, xValue);
        if (inner == null) return null;
        return ctx -> {
            int held = ctx.getCounters(source, counter);
            if (held < required) {
                ctx.logEntry("Effect: " + held + " " + counter + " Counter(s) on " + source.name()
                        + ", " + required + " needed — skipped");
                return;
            }
            ctx.logEntry("Effect: " + held + " " + counter + " Counter(s) on " + source.name());
            inner.accept(ctx);
        };
    }

    static Consumer<GameContext> tryParseNamedJobReference(String text, CardData source, int xValue) {
        if (source == null) return null;
        if (!NAMED_JOB_REFERENCE.matcher(text).find()) return null;
        if (NAMES_A_JOB_ITSELF.matcher(text).find()) return null;
        if (parse(namedJobText(text, PLACEHOLDER_JOB), source, xValue) == null) return null;
        return ctx -> {
            String job = ctx.namedJob(source);
            if (job == null || job.isBlank()) {
                ctx.logEntry("Effect: " + source.name() + " has named no Job — nothing to choose");
                return;
            }
            Consumer<GameContext> inner = parse(namedJobText(text, job), source, xValue);
            if (inner == null) {
                ctx.logEntry("[ActionResolver] Named Job effect not yet implemented: " + text);
                return;
            }
            ctx.logEntry("Effect: the named Job is " + job);
            inner.accept(ctx);
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
    /**
     * Parses "each player selects up to N active Characters he/she controls (select as many as
     * possible). Dull them and Freeze them." — Cloud of Darkness 10-028L's attack trigger.
     *
     * <p>Symmetric, and it costs its own controller too: Cloud of Darkness attacks and both sides
     * lose the use of two Characters. The parenthetical is what makes the count mandatory, so
     * neither player can decline by selecting none.
     */
    static Consumer<GameContext> tryParseEachPlayerSelectUpToNActiveDullFreeze(String text) {
        Matcher m = EACH_PLAYER_SELECT_UP_TO_N_ACTIVE_DULL_FREEZE.matcher(text);
        if (!m.find()) return null;
        int    count    = Integer.parseInt(m.group("count"));
        String tgtLower = m.group("targets").toLowerCase(java.util.Locale.ROOT);
        boolean inclForwards = tgtLower.startsWith("forward") || tgtLower.startsWith("character");
        boolean inclBackups  = tgtLower.startsWith("backup")  || tgtLower.startsWith("character");
        boolean inclMonsters = tgtLower.startsWith("monster") || tgtLower.startsWith("character");
        return ctx -> {
            ctx.logEntry("Effect: Each player selects up to " + count
                    + " active Character(s) they control — dull and Freeze");
            ctx.eachPlayerSelectUpToNActiveAndDullFreeze(count, inclForwards, inclBackups, inclMonsters);
        };
    }

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
