package shufflingway;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    /**
     * Parses "if there are [exactly] N [or more] different Elements among [type] you control,
     * [effect]."
     *
     * <p>The exact reading has an upper bound as well as a lower one: 19-037R Wol asks for three
     * Elements and a fourth takes the search away again, which is the whole point of the wording.
     */
    static Consumer<GameContext> tryParseIfNDiffElements(String text, CardData source, int xValue) {
        Matcher m = IF_N_DIFF_ELEMENTS_AMONG.matcher(text.trim());
        if (!m.matches()) return null;
        boolean exact   = m.group("exactly") != null;
        int    min     = Integer.parseInt(exact ? m.group("mine") : m.group("minm"));
        String typeRaw = m.group("type").trim();
        String typeLow = typeRaw.toLowerCase(java.util.Locale.ROOT);
        boolean inclFwd = typeLow.startsWith("forward") || typeLow.startsWith("character");
        boolean inclBkp = typeLow.startsWith("backup")  || typeLow.startsWith("character");
        boolean inclMon = typeLow.startsWith("monster")  || typeLow.startsWith("character");
        Consumer<GameContext> inner = parse(m.group("effect").trim(), source, xValue);
        if (inner == null) return null;
        String want = (exact ? "exactly " : "") + min;
        return ctx -> {
            int distinct = ctx.selfDistinctElementCount(inclFwd, inclBkp, inclMon);
            if (exact ? distinct == min : distinct >= min) {
                ctx.logEntry("Effect: " + distinct + " distinct element(s) among " + typeRaw + "s — condition met");
                inner.accept(ctx);
            } else {
                ctx.logEntry("Effect: " + distinct + " distinct element(s) among " + typeRaw + "s (need " + want + ") — skipped");
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
    /**
     * Parses "[you may] remove any number of [Name] Counters from [Self]. When you do so, choose
     * up to the same number of [noun] as the [Name] Counters you removed. [effect]" — 20-008H
     * Kefka's end-of-turn burn, which spends the Magic Counters his enter-the-field ability banked.
     *
     * <p>Resolved by rewriting the payoff with the count the removal actually produced and handing
     * that to {@code parse()}: "choose up to 2 Forwards. Deal them 9000 damage." is a sentence the
     * choose chain already reads in full — target selection, the "up to" ceiling, and every noun
     * the payoff can name. Restating that here would be a second copy of it to keep in step.
     *
     * <p>Both halves are parsed together because the second cannot stand alone: "the same number"
     * is a quantity that exists only inside one resolution, so
     * {@code ActionResolver.tryParseWhenYouDoSoSequence}'s independent halves cannot express it.
     * <b>Must precede that parser in every dispatch chain</b> — today it declines this text for want
     * of a parseable followup, but a later wiring of the payoff sentence on its own would let it
     * claim Kefka and split the count away from the removal that sets it.
     *
     * <p>The template is proved at parse time against a plural and a singular count, so an ability
     * whose payoff this engine could not actually resolve is reported unparsed rather than wired
     * and silently doing nothing on the turn it fires.
     *
     * <p>Declines a text naming a card other than the one printing it: the counters come off the
     * printing card, and no corpus wording takes them off another.
     */
    static Consumer<GameContext> tryParseRemoveAnyCountersThenChooseSameNumber(String text, CardData source) {
        if (source == null) return null;
        Matcher m = REMOVE_ANY_COUNTERS_THEN_CHOOSE_SAME_NUMBER.matcher(text.trim());
        if (!m.matches()) return null;

        String counter = m.group("counter").trim();
        if (!m.group("counter2").trim().equalsIgnoreCase(counter)) return null;
        if (!m.group("card").trim().equalsIgnoreCase(source.name())
                && !isSelfReference(m.group("card").trim())) return null;

        String noun = m.group("noun").trim();
        String tail = m.group("tail").trim();
        // Proved for both a plural and a singular count: the payoff's verb agreement is printed for
        // the plural ("Deal them"), and a one-counter turn has to resolve too.
        if (parse(payoffText(2, noun, tail), source) == null) return null;
        if (parse(payoffText(1, noun, tail), source) == null) return null;

        return ctx -> {
            int held = ctx.getCounters(source, counter);
            if (held <= 0) {
                ctx.logEntry(source.name() + " has no " + counter + " Counter to remove");
                ctx.markEffectFizzled();
                return;
            }
            int take = ctx.selectNumber(0, held,
                    "Remove how many " + counter + " Counters from " + source.name() + "?");
            if (take <= 0) {
                ctx.logEntry(source.name() + " — no " + counter + " Counter removed");
                ctx.markEffectFizzled();
                return;
            }
            ctx.removeCounters(source, counter, take);
            ctx.logEntry(source.name() + " — removed " + take + " " + counter + " Counter(s); "
                    + "choose up to " + take + " " + noun);
            Consumer<GameContext> payoff = parse(payoffText(take, noun, tail), source);
            if (payoff != null) payoff.accept(ctx);
        };
    }

    /** The payoff sentence of {@link #tryParseRemoveAnyCountersThenChooseSameNumber} for a count. */
    private static String payoffText(int count, String noun, String tail) {
        return "choose up to " + count + " " + noun + ". " + tail;
    }

    /**
     * One Break Zone removal's filters, as {@link #tryParseRemoveFromBreakZoneFromGame} reads them
     * off the captured filter phrase. {@code null} for a phrase this engine cannot express.
     */
    private record BzRemovalFilters(String element, int costVal, String costCmp,
            boolean forwards, boolean backups, boolean monsters, boolean summons,
            String job, String cardName, String category, PickGate gate) {}

    /** The elements a card can print, as the filter phrases spell them. */
    private static final String BZ_ELEMENTS = "Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark";

    /**
     * Takes the filter phrase of a Break Zone removal apart — "Job Warring Triad with different
     * names", "Characters of cost 5 or more", "Category MBM Characters", "Fire cards".
     *
     * <p>Read from the outside in: the riders ("with different names", "of cost N or more") are
     * lifted off first because they can sit either side of the noun, then the trailing card type,
     * then a leading element, and whatever is left has to be a single {@code Job}/{@code Category}/
     * {@code Card Name} filter or nothing at all. Anything else is declined rather than dropped —
     * a removal that quietly ignores half its filter takes cards the card text protects.
     *
     * @return the filters, or {@code null} when the phrase is not one this engine can honour
     */
    private static BzRemovalFilters parseBzRemovalFilters(String raw) {
        String f = raw == null ? "" : raw.trim();
        // Two filters joined by "and/or" name one selection drawn from two pools, which the Break
        // Zone selection primitive has no way to express. Declined whole rather than half-honoured.
        if (f.toLowerCase(Locale.ROOT).contains("and/or")
                || f.toLowerCase(Locale.ROOT).contains("break zone")) return null;

        PickGate gate = PickGate.ANY;
        Matcher names = Pattern.compile("(?i),?\\s*with\\s+different\\s+names\\b").matcher(f);
        if (names.find()) { gate = PickGate.DISTINCT_NAMES; f = names.replaceFirst(" ").trim(); }
        Matcher elems = Pattern.compile("(?i),?\\s*each\\s+of\\s+a\\s+different\\s+Element\\b").matcher(f);
        if (elems.find()) {
            if (gate != PickGate.ANY) return null;   // no printing carries both riders
            gate = PickGate.DISTINCT_ELEMENTS;
            f = elems.replaceFirst(" ").trim();
        }

        int costVal = -1; String costCmp = null;
        Matcher cost = Pattern.compile("(?i),?\\s*of\\s+cost\\s+(?<cost>\\d+)(?:\\s+or\\s+(?<cmp>more|less))?\\b")
                .matcher(f);
        if (cost.find()) {
            costVal = Integer.parseInt(cost.group("cost"));
            costCmp = cost.group("cmp") != null ? cost.group("cmp").toLowerCase(Locale.ROOT) : null;
            f = cost.replaceFirst(" ").trim();
        }
        f = f.replaceAll("\\s{2,}", " ").replaceAll("[,\\s]+$", "").trim();

        boolean forwards = false, backups = false, monsters = false, summons = false;
        Matcher type = Pattern.compile("(?i)\\s*(?<type>Forwards?|Backups?|Monsters?|Summons?|Characters?|cards?)$")
                .matcher(f);
        boolean sawType = type.find();
        if (sawType) {
            switch (type.group("type").toLowerCase(Locale.ROOT).replaceAll("s$", "")) {
                case "forward"   -> forwards = true;
                case "backup"    -> backups  = true;
                case "monster"   -> monsters = true;
                case "summon"    -> summons  = true;
                // A Character is a Forward, a Backup or a Monster; a card is any of those or a Summon.
                case "character" -> { forwards = true; backups = true; monsters = true; }
                case "card"      -> { forwards = true; backups = true; monsters = true; summons = true; }
                default          -> { return null; }
            }
            f = f.substring(0, type.start()).trim();
        }

        String element = null;
        Matcher el = Pattern.compile("(?i)^(?<el>" + BZ_ELEMENTS + ")\\b").matcher(f);
        if (el.find()) { element = el.group("el"); f = f.substring(el.end()).trim(); }

        String job = null, cardName = null, category = null;
        if (!f.isEmpty()) {
            Matcher jm = Pattern.compile("(?i)^Job\\s+(?<job>.+)$").matcher(f);
            Matcher cm = Pattern.compile("(?i)^Category\\s+(?<cat>.+)$").matcher(f);
            Matcher nm = Pattern.compile("(?i)^Card\\s+Name\\s+(?<name>.+)$").matcher(f);
            if (nm.matches())      cardName = nm.group("name").trim();
            else if (jm.matches()) job      = jm.group("job").trim();
            else if (cm.matches()) category = cm.group("cat").trim();
            else return null;   // an unrecognised filter, not an unfiltered removal
        }
        // No noun at all ("remove 3 Job Captain") means every card type the filter admits.
        if (!sawType) { forwards = true; backups = true; monsters = true; summons = true; }
        return new BzRemovalFilters(element, costVal, costCmp,
                forwards, backups, monsters, summons, job, cardName, category, gate);
    }

    /**
     * Parses "[you may] remove [N] [filters] in your Break Zone from the game" and the payoff that
     * can trail it — 20-008H Kefka's "Then, place 1 Magic Counter on Kefka for each card you
     * removed due to this ability."
     *
     * <p>Until this existed the whole family fell through to
     * {@code ActionResolver.tryParseRemoveNamedFromGame}, whose lazy name group happily read
     * "up to 3 Job Warring Triad with different names in your Break Zone" as a card name. That
     * parser searches the <em>field</em> for a literal name, so every one of these removed nothing
     * and logged a warning, and any "Then, …" or "When you do so, …" payoff hanging off the
     * sentence was paid out for free.
     *
     * <p><b>Must precede {@code tryParseRemoveNamedFromGame} in every dispatch chain</b>, for that
     * reason. It must also <b>follow {@code tryParseRemoveAllOppBzFromGame}</b>: "remove all the
     * cards in your opponent's Break Zone from the game" is that parser's, and this one would
     * otherwise claim it and route a whole-zone wipe through a selection dialog.
     *
     * <p>The counter payoff is read here rather than left to the generic "Then, …" chaining because
     * its multiplier is how many cards <em>this</em> removal put out of the game — see
     * {@link ActionResolverPatterns#THEN_PLACE_COUNTERS_PER_CARD_REMOVED}. Any other trailing
     * sentence still goes to {@code appendThenClause}, and a trailing sentence that cannot be
     * parsed declines the whole ability rather than silently dropping half of it.
     */
    static Consumer<GameContext> tryParseRemoveFromBreakZoneFromGame(String text, CardData source) {
        String trimmed = text.trim();
        Matcher m = REMOVE_FROM_BREAK_ZONE_FROM_GAME.matcher(trimmed);
        if (!m.lookingAt()) return null;

        BzRemovalFilters f = parseBzRemovalFilters(m.group("filters"));
        if (f == null) return null;

        String qty = m.group("qty") == null ? "" : m.group("qty").toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        final int maxCount;
        final boolean upTo;
        if (qty.startsWith("all"))                { maxCount = Integer.MAX_VALUE; upTo = false; }
        else if (qty.startsWith("any number of")) { maxCount = Integer.MAX_VALUE; upTo = true;  }
        else if (qty.startsWith("up to"))         { maxCount = Integer.parseInt(qty.replaceAll("\\D+", "")); upTo = true; }
        else if (!qty.isEmpty())                  { maxCount = Integer.parseInt(qty); upTo = false; }
        else                                      { maxCount = 1; upTo = false; }

        String zone = m.group("zone").toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        boolean opponentZone = zone.startsWith("your opponent");
        boolean bothZones    = zone.startsWith("each player") || zone.startsWith("either player");

        // Shared between the removal and the payoff that reads it. A single-element holder rather
        // than a field: the parsed Consumer is a long-lived singleton the engine reuses, so the
        // count has to be written and read inside one resolution.
        int[] removed = new int[1];
        Consumer<GameContext> base = ctx -> removed[0] = ctx.removeCardsFromBreakZoneFromGame(
                maxCount, upTo, opponentZone, bothZones, f.element(), f.costVal(), f.costCmp(),
                f.forwards(), f.backups(), f.monsters(), f.summons(),
                f.job(), f.cardName(), f.category(), f.gate());

        String tail = trimmed.substring(m.end()).trim();
        if (tail.isEmpty()) return base;

        Matcher payoff = THEN_PLACE_COUNTERS_PER_CARD_REMOVED.matcher(tail);
        if (payoff.matches()) {
            if (source == null) return null;
            String onCard = payoff.group("oncard").trim();
            if (!onCard.equalsIgnoreCase(source.name()) && !isSelfReference(onCard)) return null;
            int    per     = Integer.parseInt(payoff.group("amount"));
            String counter = payoff.group("counter").trim();
            return base.andThen(ctx -> {
                int total = per * removed[0];
                if (total <= 0) {
                    ctx.logEntry("Effect: no cards removed — no " + counter + " Counter placed on "
                            + source.name());
                    return;
                }
                ctx.logEntry("Effect: Place " + total + " " + counter + " Counter(s) on "
                        + source.name() + " (" + per + " per card removed, " + removed[0] + " removed)");
                ctx.placeCounters(source, counter, total);
            });
        }
        // A trailing sentence this parser cannot account for declines the whole ability. Handing it
        // to appendThenClause is not safe here: that helper returns the base unchanged when the
        // tail is not a "Then, …" clause at all, which would drop Sephiroth 11-138S's "or put
        // Sephiroth into the Break Zone" and the "When you do so, …" payoffs that
        // tryParseWhenYouDoSoSequence had already declined for want of a parseable followup.
        if (!TRAILING_THEN_CLAUSE.matcher(tail).matches()) return null;
        return appendThenClause(base, tail, source);
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
     * Parses "[Self] (will|does) not activate during your next Active Phase." — the self-imposed
     * cost nine printings attach to an oversized effect. See
     * {@link ActionResolverPatterns#SELF_SKIP_NEXT_ACTIVE_PHASE} for the roster.
     *
     * <p>Self-named and checked by equality against the printing card, which is what makes the
     * effect land on the right copy: two Kains on the board are two cards, and only the one whose
     * ability was used sits out the phase. No corpus wording puts this sentence on anything but the
     * card that prints it, so a text naming another card is declined rather than guessed at.
     */
    static Consumer<GameContext> tryParseSelfSkipNextActivePhase(String text, CardData source) {
        if (source == null) return null;
        Matcher m = SELF_SKIP_NEXT_ACTIVE_PHASE.matcher(text.trim());
        if (!m.matches()) return null;
        if (!m.group("name").trim().equalsIgnoreCase(source.name())) return null;
        return ctx -> ctx.sourceSkipsNextActivePhase(source);
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

    /**
     * Parses "Deal it N damage. If the Forward entered play without paying for its CP cost, deal it
     * M damage instead." — Cid (FFBE) 10-052L.
     *
     * <p>Like {@link #tryParseTriggeredTargetAction} it acts on the card the trigger preloaded;
     * unlike it, the amount depends on how that card arrived rather than on the sentence alone.
     */
    static Consumer<GameContext> tryParseTriggeredDamageInsteadIfEnteredUnpaid(String text) {
        Matcher m = TRIGGERED_DAMAGE_INSTEAD_IF_ENTERED_UNPAID.matcher(text.trim());
        if (!m.matches()) return null;
        final int base = Integer.parseInt(m.group("base"));
        final int alt  = Integer.parseInt(m.group("alt"));
        return ctx -> {
            List<ForwardTarget> ts = ctx.consumePreloadedTargets();
            if (ts == null || ts.isEmpty()) {
                ctx.logEntry("Triggered damage: no preloaded target — skipped");
                return;
            }
            boolean unpaid = ctx.triggeringCardEnteredWithoutPayingCost();
            int damage = unpaid ? alt : base;
            ctx.logEntry("Effect: the arriving Forward " + (unpaid ? "paid no CP" : "was cast")
                    + " — dealing it " + damage + " damage");
            for (ForwardTarget t : ts) ctx.damageTarget(t, damage);
        };
    }

    static Consumer<GameContext> tryParseTriggeredTargetAction(String text, int xValue) {
        String t = text.trim();
        if (!TRIGGERED_TARGET_ACTION_BARE.matcher(t).matches()) return null;

        // "That Forward gains ..." and "It gains ..." are one sentence about one card; only the
        // trigger form has to name the type, having no earlier clause to point back at. Rewritten
        // so the followup vocabulary reads it without a demonstrative arm of its own.
        String action_t = TRIGGERED_TARGET_DEMONSTRATIVE_SUBJECT.matcher(t).replaceFirst("It ");
        BiConsumer<GameContext, List<ForwardTarget>> action = parseTargetAction(action_t, xValue);
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
