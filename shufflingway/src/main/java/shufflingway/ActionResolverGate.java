package shufflingway;

import static shufflingway.ActionResolverPatterns.*;

import static shufflingway.ActionResolver.*;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;

/**
 * Gate parsers split out of {@link ActionResolver}.
 *
 * <p>Bodies only: {@code ActionResolver} keeps every dispatch chain and calls these
 * through a wildcard static import, so call order -- which is load-bearing, because
 * matchers use {@code find()} -- is unchanged.
 */
final class ActionResolverGate {

	private ActionResolverGate() {}

    /**
     * Parses "If you [do not] control X, Y" — resolves Y only when the control condition is
     * (un)met at resolution time. Returns {@code null} when the condition or inner effect cannot
     * be parsed so the text falls through to the regular matchers (preserving prior behaviour).
     */
    static Consumer<GameContext> tryParseIfControlCondOtherThan(String text, CardData source, int xValue) {
        Matcher m = IF_CONTROL_COND_OTHER_THAN.matcher(text.trim());
        if (!m.matches()) return null;
        ControlCondition cc = CardData.parseControlCondition(m.group("cond").trim());
        if (cc == null) return null;
        boolean negated    = m.group("neg") != null;
        String excludeName = m.group("exclude").trim();
        Consumer<GameContext> inner = parse(m.group("effect").trim(), source, xValue);
        if (inner == null) return null;
        return ctx -> {
            boolean met = ctx.controlConditionMetExcluding(cc, excludeName);
            if (met != negated) {
                inner.accept(ctx);
            } else {
                ctx.logEntry("Effect: control condition (excl. " + excludeName + ") not met — skipped");
            }
        };
    }
    /**
     * Parses "If N or more Warp Counters are placed on [CardName], [effect]" — resolves the inner
     * effect only when the named card is still waiting in the ability user's Warp zone with at
     * least N counters on it. 21-007L Shadow, whose end-of-turn ability is a no-op on the turn its
     * last counter comes off.
     *
     * <p>Returns {@code null} when the inner effect does not parse, so the text falls through to
     * the regular matchers exactly as the other gates here do.
     */
    static Consumer<GameContext> tryParseWarpCounterCountGate(String text, CardData source, int xValue) {
        Matcher m = WARP_COUNTER_COUNT_GATE.matcher(text.trim());
        if (!m.matches()) return null;
        String cardName = m.group("card").trim();
        int    required = Integer.parseInt(m.group("count"));
        Consumer<GameContext> inner = parse(m.group("effect").trim(), source, xValue);
        if (inner == null) return null;
        return ctx -> {
            int have = ctx.warpCountersOnNamed(cardName);
            if (have >= required) {
                inner.accept(ctx);
            } else {
                ctx.logEntry("Effect: " + cardName + " has " + have + " Warp Counter(s), needs "
                        + required + " — skipped");
            }
        };
    }
    static Consumer<GameContext> tryParseControlConditionGate(String text, CardData source, int xValue) {
        Matcher m = CONTROL_CONDITION_GATE.matcher(text.trim());
        if (!m.matches()) return null;
        ControlCondition cc = CardData.parseControlCondition(m.group("cond").trim());
        if (cc == null) return null;
        boolean negated = m.group("neg") != null;
        Consumer<GameContext> inner = parse(m.group("effect").trim(), source, xValue);
        if (inner == null) return null;
        return ctx -> {
            if (ctx.controlConditionMet(cc) != negated) {
                inner.accept(ctx);
            } else {
                ctx.logEntry("Effect: control condition not met — skipped");
            }
        };
    }
    /**
     * Parses "&lt;base&gt;. If you control X, &lt;alternative&gt; instead." — resolves exactly one of the
     * two branches, never both. Returns {@code null} when the condition or either branch cannot be
     * parsed, so the text falls through to the regular matchers.
     */
    static Consumer<GameContext> tryParseControlGatedInsteadUpgrade(String text, CardData source, int xValue) {
        Matcher m = CONTROL_GATED_INSTEAD_UPGRADE.matcher(text.trim());
        if (!m.matches()) return null;
        ControlCondition cc = CardData.parseControlCondition(m.group("cond").trim());
        if (cc == null) return null;
        // A bare count carries no filters and would be tested against every field card. It means the
        // wording elided the noun from the preceding clause ("… if you control 3 or more Category
        // FFTA Characters, draw 1 card. If you control 5 or more, draw 2 cards instead." — Marche
        // 16-122R), which this parser cannot recover, so leave such text to the other matchers.
        if (!cc.isNamedMode() && !cc.requiresCrystal() && cc.orAlternatives().isEmpty()
                && cc.cardType() == null && cc.element() == null
                && cc.job() == null && cc.category() == null && cc.orCardNames().isEmpty())
            return null;

        String rest    = m.group("rest").trim();
        String altText = m.group("alt").trim() + "." + (rest.isEmpty() ? "" : " " + rest);
        Consumer<GameContext> baseFn = parse(m.group("base").trim(), source, xValue);
        Consumer<GameContext> altFn  = parse(altText, source, xValue);
        if (baseFn == null || altFn == null) return null;

        return ctx -> {
            if (ctx.controlConditionMet(cc)) {
                ctx.logEntry("Effect: you control " + cc + " — replacement effect applies instead");
                altFn.accept(ctx);
            } else {
                baseFn.accept(ctx);
            }
        };
    }
    /**
     * Parses "&lt;base&gt;. If the cost to cast &lt;name&gt; was paid with CP of &lt;n&gt; or
     * more/less different Elements, &lt;tail&gt;." — the base always resolves, and the tail is
     * added (or substituted, when it ends in "instead") when the payment met the threshold.
     *
     * <p>The condition is settled before the effect ever runs — the CP was spent to cast this
     * card — so it is read straight off {@link GameContext#castPaymentDistinctElements()}, the
     * same counter the auto-ability form of this condition tests.
     *
     * <p>Returns {@code null} when either half fails to parse, so unsupported wordings fall
     * through to the regular matchers rather than losing the base effect.
     */
    /**
     * Parses "If the cost paid to cast [Self] included [Element] CP, [effect]" — Selkie 13-044C,
     * and its older "cost paid to play" wording — 7-003C Red Mage.
     *
     * <p>Self-named and checked by equality, like the rest of this family: the gate asks about the
     * cast of the card printing it, so a text naming some other card is not this.
     *
     * <p>Reads a <em>chain</em> of gates, not only one. 9-123L Chaos (MOBIUS) prints three in a
     * row, one per Element, each guarding its own effect; the anchored pattern's greedy inner
     * group swallows the second and third as part of the first one's effect, and the whole text
     * then fell through to a matcher that found the middle clause's discard and ran it
     * unconditionally. Splitting on {@link ActionResolverPatterns#CAST_PAYMENT_ELEMENT_CP_GATE_CLAUSE}
     * gives one (Element, effect) pair per gate, each tested independently at resolution — which
     * is what the card says: paying Fire and Lightning fires the first and third and not the
     * second. A single gate is the one-element case of the same loop.
     *
     * <p>Returns {@code null} when any gated effect does not parse, rather than a consumer that
     * checks the payment and then does nothing — an unimplemented payoff has to stay visible.
     */
    static Consumer<GameContext> tryParseCastPaymentElementCpGate(String text, CardData source, int xValue) {
        if (source == null) return null;
        String trimmed = text.trim();
        // The whole text has to be gates: the anchored pattern settles that the first clause
        // starts at the head, and the split below settles that nothing sits between two of them.
        if (!CAST_PAYMENT_ELEMENT_CP_GATE.matcher(trimmed).matches()) return null;

        List<String> elements = new ArrayList<>();
        List<Consumer<GameContext>> effects = new ArrayList<>();
        Matcher clause = CAST_PAYMENT_ELEMENT_CP_GATE_CLAUSE.matcher(trimmed);
        int effectStart = -1;
        while (clause.find()) {
            if (!clause.group("name").trim().equalsIgnoreCase(source.name())) return null;
            if (effectStart >= 0) {
                Consumer<GameContext> prev = parse(trimmed.substring(effectStart, clause.start()).trim(), source, xValue);
                if (prev == null) return null;
                effects.add(prev);
            }
            elements.add(cap(clause.group("element")));
            effectStart = clause.end();
        }
        Consumer<GameContext> last = parse(trimmed.substring(effectStart).trim(), source, xValue);
        if (last == null) return null;
        effects.add(last);

        List<String> fElements = List.copyOf(elements);
        List<Consumer<GameContext>> fEffects = List.copyOf(effects);
        return ctx -> {
            for (int i = 0; i < fElements.size(); i++) {
                String element = fElements.get(i);
                if (!ctx.wasElementCpPaid(element)) {
                    ctx.logEntry("Effect: " + element + " CP was not paid to cast "
                            + source.name() + " — skipped");
                    continue;
                }
                ctx.logEntry("Effect: " + element + " CP was paid to cast " + source.name());
                fEffects.get(i).accept(ctx);
            }
        };
    }

    static Consumer<GameContext> tryParseCastPaymentElementsGate(String text, CardData source, int xValue) {
        Matcher m = CAST_PAYMENT_ELEMENTS_GATE.matcher(text.trim());
        if (!m.matches()) return null;
        int     required = Integer.parseInt(m.group("count"));
        boolean atLeast  = m.group("cmp").equalsIgnoreCase("more");
        String  baseText = m.group("base").trim();
        String  tailText = m.group("tail").trim();

        Consumer<GameContext> baseFn = parse(baseText, source, xValue);
        if (baseFn == null) return null;

        String label = "cast paid with CP of " + required + " or "
                + (atLeast ? "more" : "less") + " different Element(s)";

        Matcher inst = CAST_PAYMENT_ELEMENTS_TAIL_INSTEAD.matcher(tailText);
        if (inst.matches()) {
            String altText = insteadVariant(baseText, inst.group("alt").trim(), source);
            Consumer<GameContext> altFn = altText == null
                    ? null : parse(gateTailText(altText, source, xValue), source, xValue);
            if (altFn == null) return null;
            return ctx -> {
                int paid = ctx.castPaymentDistinctElements();
                if (atLeast ? paid >= required : paid <= required) {
                    ctx.logEntry("Effect: " + label + " (paid " + paid + ") — replacement effect applies instead");
                    altFn.accept(ctx);
                } else {
                    baseFn.accept(ctx);
                }
            };
        }

        Consumer<GameContext> tailFn = parse(gateTailText(tailText, source, xValue), source, xValue);
        if (tailFn == null) return null;
        return ctx -> {
            baseFn.accept(ctx);
            int paid = ctx.castPaymentDistinctElements();
            if (atLeast ? paid >= required : paid <= required) {
                ctx.logEntry("Effect: " + label + " (paid " + paid + ") — condition met");
                tailFn.accept(ctx);
            } else {
                ctx.logEntry("Effect: " + label + " — paid " + paid + ", condition not met — skipped");
            }
        };
    }

    /**
     * The text a gate's conditional tail is actually resolved from: as printed, or with the
     * additive "also" removed when that is the only reading that parses.
     *
     * <p>"…, all the Forwards you control <em>also</em> gain +2000 power…" (16-046C Chocobo Chick
     * (VII), 16-086C Ixion) puts the connector between subject and verb, where {@link #parse}'s
     * leading-"also" strip cannot reach it, and the clause parses as nothing at all. The word
     * carries no effect of its own — it marks the clause as additional to the base, which is what
     * the gate already encodes.
     *
     * <p>Returned as text rather than as a parsed effect so the description chain can report the
     * same clause the parser resolved. Tried second, so a tail that reads correctly as printed is
     * never rewritten.
     */
    static String gateTailText(String tail, CardData source, int xValue) {
        if (parse(tail, source, xValue) != null) return tail;
        String withoutAlso = tail.replaceFirst("(?i)\\s+also\\b", "");
        return !withoutAlso.equals(tail) && parse(withoutAlso, source, xValue) != null
                ? withoutAlso : tail;
    }

    /**
     * Builds the replacement text for an "… instead" tail: the alternative replaces the base's
     * final sentence, so that sentence is dropped and the alternative put in its place.
     *
     * <p>Everything before it has to stay. Bahamut 16-016C's "deal it 12000 damage instead" reads
     * "it" as the Forward the base's "Choose 1 Forward." picked, so parsing the alternative on its
     * own leaves it with nothing to attach to — the trap {@code tryParseChooseGatedBoostInstead}
     * exists to work around for {@code tryParseControlGatedInsteadUpgrade}.
     *
     * <p>Returns {@code null} for a single-sentence base, where there is no earlier clause to keep
     * and the caller should fall through instead.
     */
    static String insteadVariant(String baseText, String alt, CardData source) {
        String escaped = escapePeriodInName(baseText, source);
        String body    = escaped.replaceAll("[.!]\\s*$", "");
        int cut = Math.max(body.lastIndexOf('.'), body.lastIndexOf('!'));
        if (cut < 0) return null;
        String head = restorePeriodInName(escaped.substring(0, cut + 1).trim(), source);
        return head + " " + Character.toUpperCase(alt.charAt(0)) + alt.substring(1) + ".";
    }

    static Consumer<GameContext> tryParseOpponentControlsCardGate(String text, CardData source, int xValue) {
        Matcher m = OPP_CONTROL_CARD_GATE.matcher(text.trim());
        if (!m.matches()) return null;
        String cond    = m.group("cond").toLowerCase();
        String typeRaw = m.group("type");
        String normType = Character.toUpperCase(typeRaw.charAt(0))
                + typeRaw.substring(1).toLowerCase().replaceAll("s$", "");
        Consumer<GameContext> inner = parse(m.group("effect").trim(), source, xValue);
        if (inner == null) return null;
        return ctx -> {
            if (ctx.opponentControlsCard(normType, cond)) {
                inner.accept(ctx);
            } else {
                ctx.logEntry("Effect: opponent has no " + cond + " " + normType + " — skipped");
            }
        };
    }
    static Consumer<GameContext> tryParseIfOppControlsNOrMoreCondTypeGate(String text, CardData source, int xValue) {
        Matcher m = IF_OPP_CONTROLS_N_OR_MORE_COND_TYPE_GATE.matcher(text.trim());
        if (!m.matches()) return null;
        int    threshold = Integer.parseInt(m.group("count"));
        String cond      = m.group("cond").toLowerCase();
        String typeRaw   = m.group("type");
        String normType  = Character.toUpperCase(typeRaw.charAt(0))
                + typeRaw.substring(1).toLowerCase().replaceAll("s$", "");
        boolean inclFwds = normType.equals("Forward")   || normType.equals("Character");
        boolean inclBkps = normType.equals("Backup")    || normType.equals("Character");
        boolean inclMons = normType.equals("Monster")   || normType.equals("Character");
        Consumer<GameContext> inner = parse(m.group("effect").trim(), source, xValue);
        if (inner == null) return null;
        return ctx -> {
            int cnt = ctx.countOppFieldCardsWithCondition(inclFwds, inclBkps, inclMons, cond);
            if (cnt >= threshold) {
                inner.accept(ctx);
            } else {
                ctx.logEntry("Effect: " + threshold + "+ " + cond + " " + normType + "(s) required, opponent has " + cnt + " — skipped");
            }
        };
    }
    static Consumer<GameContext> tryParseIfControlAtMost(String text, CardData source, int xValue) {
        Matcher m = IF_CONTROL_AT_MOST.matcher(text.trim());
        if (!m.matches()) return null;
        int max          = Integer.parseInt(m.group("max"));
        String category  = m.group("category");
        String type      = m.group("type").trim();
        Consumer<GameContext> inner = parse(m.group("effect").trim(), source, xValue);
        if (inner == null) return null;
        String label = (category != null ? "Category " + category + " " : "") + type;
        return ctx -> {
            int count = category != null
                    ? ctx.ownFieldCountByCategory(category, type)
                    : ctx.ownFieldCount(type);
            if (count <= max) {
                inner.accept(ctx);
            } else {
                ctx.logEntry("Effect: control " + count + " " + label + " (max " + max + ") — skipped");
            }
        };
    }
    /**
     * Parses Siren (V)'s "Put the top card of your deck into the Break Zone. If the card put into
     * the Break Zone is not a [Type], cancel its/their effect(s)." — mills the top deck card and
     * cancels the in-progress selection when it is not of the given type.
     */
    static Consumer<GameContext> tryParseCancelChosenMillTopIfNotType(String text) {
        Matcher m = CANCEL_CHOSEN_MILL_TOP_IF_NOT_TYPE.matcher(text.trim());
        if (!m.find()) return null;
        String type = m.group("type");
        return ctx -> {
            ctx.logEntry("Effect: mill top of deck; if not a " + type + ", cancel the effect choosing your Character(s)");
            ctx.millTopDeckCancelChosenIfNotType(type);
        };
    }

    /**
     * Parses Colkhab 18-041C's "Each player puts the top card of their deck into the Break Zone. If
     * both cards are of the same card type, cancel its effect." — both players mill one card and the
     * in-progress selection is cancelled when the two match on card type.
     */
    static Consumer<GameContext> tryParseCancelChosenMillBothIfSameType(String text) {
        if (!CANCEL_CHOSEN_MILL_BOTH_IF_SAME_TYPE.matcher(text.trim()).find()) return null;
        return ctx -> {
            ctx.logEntry("Effect: each player mills top of deck; if the two share a card type, "
                    + "cancel the effect choosing your Character(s)");
            ctx.millTopDeckBothCancelChosenIfSameType();
        };
    }
}
