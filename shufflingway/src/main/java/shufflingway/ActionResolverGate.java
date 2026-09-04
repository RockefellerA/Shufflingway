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
     * Parses "If &lt;Self&gt; is [dull|active|attacking], &lt;effect&gt;" — an ability that only
     * resolves while the card printing it stands in that state. 15-046C Dancer, whose end-of-turn
     * sweep is free on a turn it attacked and nothing on a turn it did not.
     *
     * <p>Gated on the source's <em>name</em>, and the name has to be the source's own: the same
     * words appear as a followup about a card the sentence just chose ("choose 1 Forward. If it is
     * dull, deal it 8000 damage" — 10-033L Sephiroth), and testing the ability's own carrier there
     * would answer about the wrong card. Anything that is not the source falls through with
     * {@code null}, exactly as an unreadable condition does.
     *
     * <p>The state itself is asked through {@link ControlCondition#forNamedCardState}, the same
     * question 17-100C Mog's standing boost asks, so the two cannot disagree about what "dull"
     * means. That reads the field rather than the card object, which is also what makes a Dancer
     * that has left the field before the trigger resolves correctly do nothing.
     */
    static Consumer<GameContext> tryParseIfSelfIsStateGate(String text, CardData source, int xValue) {
        if (source == null) return null;
        Matcher m = IF_SELF_IS_STATE_GATE.matcher(text.trim());
        if (!m.matches()) return null;
        String name = m.group("name").trim();
        if (!CardFilters.meetsCardNameFilter(source, name)) return null;
        ControlCondition cc = ControlCondition.forNamedCardState(name,
                ControlCondition.NamedCardState.valueOf(
                        m.group("state").toUpperCase(java.util.Locale.ROOT)));
        String innerText = m.group("effect").trim();
        Consumer<GameContext> inner = parse(innerText, source, xValue);
        if (inner == null) return null;
        final String stateLabel = m.group("state").toLowerCase(java.util.Locale.ROOT);
        return ctx -> {
            if (!ctx.controlConditionMet(cc)) {
                ctx.logEntry("Effect: " + name + " is not " + stateLabel + " — skipped");
                return;
            }
            ctx.logEntry("Effect: " + name + " is " + stateLabel + " — " + innerText);
            inner.accept(ctx);
        };
    }

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
        if (baseFn == null) {
            // A base that is not an effect on its own is not half of two effects — it is one
            // sentence the condition sits inside. "Choose 1 Forward." parses as nothing, because
            // choosing with no payoff does nothing; the payoff is the tail.
            //
            // Reattaching the tail and gating the pair is what stops that payoff firing whatever
            // was paid. Ten abilities print this shape (14-055C Lezaford, 19-013C Lilty, 19-067C
            // Monk and their siblings) and every one fell through to the Choose family, whose
            // followup matchers are unanchored: they found "break it" and "deal it 8000 damage"
            // *inside* the conditional clause and ran them with the condition never read.
            //
            // The choose is gated along with its payoff, where the printed sentence chooses first
            // and checks after. That difference is visible only to a "when chosen" trigger on a
            // Forward that was going to be spared anyway — far smaller than breaking a Forward
            // whose condition was never met.
            Consumer<GameContext> wholeFn = parse(baseText + " " + tailText, source, xValue);
            if (wholeFn == null) return null;
            String gateLabel = "cast paid with CP of " + required + " or "
                    + (atLeast ? "more" : "less") + " different Element(s)";
            return ctx -> {
                int paid = ctx.castPaymentDistinctElements();
                if (atLeast ? paid >= required : paid <= required) {
                    ctx.logEntry("Effect: " + gateLabel + " (paid " + paid + ") — condition met");
                    wholeFn.accept(ctx);
                } else {
                    ctx.logEntry("Effect: " + gateLabel + " — paid " + paid
                            + ", condition not met — skipped");
                }
            };
        }

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
     * Parses "If the cost to play &lt;Self&gt; didn't include CP of &lt;n&gt; or more different
     * Elements, &lt;effect&gt;." — 9-099R Livia, the one negated printing of the cast-payment
     * family, whose effect is putting herself straight back into the Break Zone.
     *
     * <p>Self-named and checked by equality, like the rest of the family: the gate asks about the
     * payment that put <em>this</em> card on the field, so a text naming another card is not this.
     * That is also why the count is read through
     * {@link GameContext#castPaymentDistinctElementsFor(CardData)} rather than the seat-wide
     * {@link GameContext#castPaymentDistinctElements()} — a Livia put onto the field by some other
     * card's effect was paid for by nothing at all, and the plain counter would still be holding
     * whatever the last cast happened to spend. Failing that test is the whole point of the card:
     * she goes to the Break Zone unless her own arrival was bought with enough Elements.
     *
     * <p>Returns {@code null} when the guarded effect does not parse, so an unimplemented payoff
     * stays visible rather than becoming a gate that checks the payment and then does nothing.
     */
    static Consumer<GameContext> tryParseCastPaymentElementsNotIncludedGate(
            String text, CardData source, int xValue) {
        if (source == null) return null;
        Matcher m = CAST_PAYMENT_ELEMENTS_NOT_INCLUDED_GATE.matcher(text.trim());
        if (!m.matches()) return null;
        if (!m.group("card").trim().equalsIgnoreCase(source.name())) return null;

        int required = Integer.parseInt(m.group("count"));
        Consumer<GameContext> effect = parse(m.group("effect").trim(), source, xValue);
        if (effect == null) return null;

        String label = "cost to play " + source.name() + " did not include CP of "
                + required + " or more different Element(s)";
        return ctx -> {
            int paid = ctx.castPaymentDistinctElementsFor(source);
            if (paid >= required) {
                ctx.logEntry("Effect: " + source.name() + " was paid for with " + paid
                        + " different Element(s) — condition not met, skipped");
                return;
            }
            ctx.logEntry("Effect: " + label + " (paid " + paid + ") — condition met");
            effect.accept(ctx);
        };
    }

    /**
     * Parses "&lt;base&gt;. If you have cast &lt;n&gt; or more cards this turn, &lt;tail&gt;." —
     * the trailing form of the cast-count condition, where the base always resolves and the tail is
     * added, or substituted for the base's last clause when it ends in "instead".
     *
     * <p>Built like {@link #tryParseCastPaymentElementsGate}, whose shape this is, and settled in
     * the same place for the same reason: the condition is the last sentence of the text, so every
     * parser below matched the base under {@code find()}, claimed the whole ability and dropped the
     * condition. 12-039C Alexander drew one card however many had been cast.
     *
     * <p>Read after {@link ActionResolverPatterns#FOLLOWUP_DAMAGE_INSTEAD}'s cards, which state the
     * same condition inside a choose followup and already resolve it per target — this declines a
     * text that one claims rather than taking it onto a second route to the same answer.
     *
     * <p>Returns {@code null} when either half fails to parse, so an unsupported wording falls
     * through to the regular matchers rather than losing the base effect.
     */
    static Consumer<GameContext> tryParseCastCountGate(String text, CardData source, int xValue) {
        String trimmed = text.trim();
        Matcher m = CAST_COUNT_GATE.matcher(trimmed);
        if (!m.matches()) return null;
        // The damage-instead family reads this condition where it is printed, inside the followup,
        // and picks the amount as it damages. Leaving those cards on it keeps one route per shape.
        Matcher dmgInstead = FOLLOWUP_DAMAGE_INSTEAD.matcher(trimmed);
        if (dmgInstead.find() && parseDamageInsteadCondition(dmgInstead.group("cond").trim()) != null)
            return null;

        int    required = Integer.parseInt(m.group("count"));
        String baseText = m.group("base").trim();
        String tailText = m.group("tail").trim();

        Consumer<GameContext> baseFn = parse(baseText, source, xValue);
        if (baseFn == null) return null;

        String label = "cast " + required + " or more cards this turn";

        Matcher inst = CAST_PAYMENT_ELEMENTS_TAIL_INSTEAD.matcher(tailText);
        if (inst.matches()) {
            String altText = castCountInsteadVariant(baseText, inst.group("alt").trim(), source);
            Consumer<GameContext> altFn = altText == null
                    ? null : parse(gateTailText(altText, source, xValue), source, xValue);
            if (altFn == null) return null;
            return ctx -> {
                int cast = ctx.selfCardsCastThisTurn();
                if (cast >= required) {
                    ctx.logEntry("Effect: " + label + " (cast " + cast
                            + ") — replacement effect applies instead");
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
            int cast = ctx.selfCardsCastThisTurn();
            if (cast >= required) {
                ctx.logEntry("Effect: " + label + " (cast " + cast + ") — condition met");
                tailFn.accept(ctx);
            } else {
                ctx.logEntry("Effect: " + label + " — cast " + cast + ", condition not met — skipped");
            }
        };
    }

    /**
     * The replacement text for a cast-count gate's "… instead" tail: the alternative takes the
     * place of the base's final sentence, or of the whole base when that is the only sentence
     * there is.
     *
     * <p>{@link #insteadVariant} answers {@code null} for a single-sentence base, because for its
     * own family that means there is an earlier clause the caller must keep and cannot find. This
     * family has printings where there genuinely is nothing to keep — 12-039C Alexander's whole
     * effect is "Draw 1 card", and "draw 2 cards instead" replaces it outright — so a null there
     * is the substitution rather than a reason to give up.
     */
    static String castCountInsteadVariant(String baseText, String alt, CardData source) {
        String swapped = insteadVariant(baseText, alt, source);
        if (swapped != null) return swapped;
        return Character.toUpperCase(alt.charAt(0)) + alt.substring(1) + ".";
    }

    /**
     * Parses "If the cost to play/cast &lt;Self&gt; was paid with CP of exactly &lt;n&gt; different
     * Elements, &lt;effect&gt;." — 7-029H Kefka and 9-021R Varis.
     *
     * <p>Self-named and read through {@link GameContext#castPaymentDistinctElementsFor(CardData)},
     * like the rest of the family: the gate asks what put <em>this</em> copy on the field, and a
     * Character that arrived by some other card's effect was paid for by nothing at all. The
     * seat-wide counter would still be holding whatever the last cast spent.
     *
     * <p>Returns {@code null} when the guarded effect does not parse, so an unimplemented payoff
     * stays visible rather than becoming a gate that checks the payment and then does nothing.
     */
    static Consumer<GameContext> tryParseCastPaymentExactElementsGate(
            String text, CardData source, int xValue) {
        if (source == null) return null;
        Matcher m = CAST_PAYMENT_EXACT_ELEMENTS_GATE.matcher(text.trim());
        if (!m.matches()) return null;
        if (!m.group("card").trim().equalsIgnoreCase(source.name())) return null;

        int required = Integer.parseInt(m.group("count"));
        Consumer<GameContext> effect = parse(m.group("effect").trim(), source, xValue);
        if (effect == null) return null;

        return ctx -> {
            int paid = ctx.castPaymentDistinctElementsFor(source);
            if (paid != required) {
                ctx.logEntry("Effect: " + source.name() + " was paid for with " + paid
                        + " different Element(s), not exactly " + required + " — skipped");
                return;
            }
            ctx.logEntry("Effect: cost to play " + source.name() + " was paid with CP of exactly "
                    + required + " different Elements — condition met");
            effect.accept(ctx);
        };
    }

    /**
     * Parses "If the cost to play/cast &lt;Self&gt; was only paid with &lt;Element&gt; CP,
     * &lt;effect&gt;." — 7-029H Kefka, 7-046R Vata.
     *
     * <p>The strict sibling of {@link #tryParseCastPaymentElementCpGate}, which reads the
     * "included [Element] CP" wording: this one fails on a payment that mixed in any other
     * Element, so it goes through {@link GameContext#castPaymentWasOnlyElement} rather than
     * {@code wasElementCpPaid}. Self-named and identity-checked for the same reason the rest of
     * the family is.
     *
     * <p>Returns {@code null} when the guarded effect does not parse, so an unimplemented payoff
     * stays visible rather than resolving unconditionally with its condition dropped — which is
     * what the general matchers did with these texts before this gate existed.
     */
    static Consumer<GameContext> tryParseCastPaymentOnlyElementCpGate(
            String text, CardData source, int xValue) {
        if (source == null) return null;
        Matcher m = CAST_PAYMENT_ONLY_ELEMENT_CP_GATE.matcher(text.trim());
        if (!m.matches()) return null;
        if (!m.group("card").trim().equalsIgnoreCase(source.name())) return null;

        String element = cap(m.group("element"));
        Consumer<GameContext> effect = parse(m.group("effect").trim(), source, xValue);
        if (effect == null) return null;

        return ctx -> {
            if (!ctx.castPaymentWasOnlyElement(source, element)) {
                ctx.logEntry("Effect: " + source.name() + " was not paid for with " + element
                        + " CP alone — skipped");
                return;
            }
            ctx.logEntry("Effect: cost to play " + source.name() + " was only paid with "
                    + element + " CP — condition met");
            effect.accept(ctx);
        };
    }

    /**
     * Parses "[Until the end of the turn, ]if the CP paid to play/cast &lt;Self&gt; was only
     * produced by [Category &lt;X&gt; ]Backups, &lt;effect&gt;." — 7-092C Thancred.
     *
     * <p>Thancred prints the duration ahead of the condition, so the captured "Until the end of
     * the turn," is put back on the front of the effect before it is parsed: the guarded clause
     * ("Thancred gains +2000 power, Haste and First Strike") is a boost that only reads as
     * temporary with the duration attached, and dropping it would make the grant permanent.
     *
     * <p>Ordered after {@code tryParseBackupCpDraw}, which claims the unqualified Summon wording
     * ("If the CP paid to cast Shiva was only produced by Backups, also draw 1 card") — that one
     * hangs off a Summon's whole effect block rather than naming a Character on the field, and
     * keeping it ahead of this leaves the six printings that use it on the parser they already
     * resolved through.
     *
     * <p>Returns {@code null} when the guarded effect does not parse.
     */
    static Consumer<GameContext> tryParseCastCpProducedByBackupsGate(
            String text, CardData source, int xValue) {
        if (source == null) return null;
        Matcher m = CAST_CP_PRODUCED_BY_BACKUPS_GATE.matcher(text.trim());
        if (!m.matches()) return null;
        if (!m.group("card").trim().equalsIgnoreCase(source.name())) return null;

        String category = m.group("category") != null ? m.group("category").trim() : null;
        String until    = m.group("until");
        String effectTxt = (until != null ? until : "") + m.group("effect").trim();
        Consumer<GameContext> effect = parse(effectTxt, source, xValue);
        if (effect == null) return null;

        String label = "CP paid to play " + source.name() + " was only produced by "
                + (category != null ? "Category " + category + " " : "") + "Backups";
        return ctx -> {
            if (!ctx.castCpOnlyFromBackups(source, category)) {
                ctx.logEntry("Effect: " + label + " — condition not met, skipped");
                return;
            }
            ctx.logEntry("Effect: " + label + " — condition met");
            effect.accept(ctx);
        };
    }

    /**
     * Parses "Your opponent may discard &lt;n&gt; cards. If he/she doesn't, &lt;effect&gt;."
     * — 7-029H Kefka.
     *
     * <p>Both halves are read together on purpose. The consequence sentence matches the ordinary
     * mass-effect matchers on its own, and letting it resolve that way applies the punishment
     * whether or not the opponent bought their way out of it — which is what happened to this text
     * before the pattern existed.
     *
     * <p>Returns {@code null} when the consequence does not parse, so an unimplemented payoff
     * stays visible rather than becoming an offer with nothing behind it.
     */
    static Consumer<GameContext> tryParseOpponentMayDiscardElseEffect(
            String text, CardData source, int xValue) {
        Matcher m = OPPONENT_MAY_DISCARD_ELSE_EFFECT.matcher(text.trim());
        if (!m.matches()) return null;
        int count = Integer.parseInt(m.group("count"));
        Consumer<GameContext> effect = parse(m.group("effect").trim(), source, xValue);
        if (effect == null) return null;

        String sourceName = source != null ? source.name() : "Ability";
        return ctx -> {
            ctx.logEntry("Effect: opponent may discard " + count + " card(s) to avoid the effect");
            if (ctx.opponentMayDiscardCards(count, sourceName)) return;
            effect.accept(ctx);
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
