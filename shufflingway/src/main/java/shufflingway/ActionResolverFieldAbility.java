package shufflingway;

import static shufflingway.ActionResolverPatterns.*;

import static shufflingway.ActionResolver.*;

import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;

/**
 * FieldAbility parsers split out of {@link ActionResolver}.
 *
 * <p>Bodies only: {@code ActionResolver} keeps every dispatch chain and calls these
 * through a wildcard static import, so call order -- which is load-bearing, because
 * matchers use {@code find()} -- is unchanged.
 */
final class ActionResolverFieldAbility {

	private ActionResolverFieldAbility() {}

    /**
     * The pattern is end-anchored, so a trailing use-restriction sentence would defeat it — Layle
     * 28-076C's grant is followed by "Each player can use this ability.".  Restrictions are
     * captured as flags on the ability rather than executed here, so matching against the stripped
     * text loses nothing.  {@code fullDescription}/{@code matchedPatternName} already strip before
     * dispatching; {@code parse()} does not, which is why it happens here.
     */
    static Consumer<GameContext> tryParseGainsQuotedFieldAbilityUntilEot(String text, CardData source) {
        if (source == null) return null;
        String matchOn = stripRestrictionSentences(text);
        if (matchOn.isEmpty()) matchOn = text;
        Matcher m = GAINS_QUOTED_FIELD_ABILITY_UNTIL_EOT.matcher(matchOn.trim());
        if (!m.matches()) return null;
        if (!m.group("subject").trim().equalsIgnoreCase(source.name())) return null;
        return grantedSelfFieldAbilityEffect(m.group("quoted").trim(), source);
    }
    /**
     * "Name 1 Element. [Self] gains "&lt;clause naming the Element&gt;." (This effect does not end
     * at the end of the turn.)" — 17-133S Scarmiglione.
     *
     * <p>The Element is substituted into the clause when the ability resolves, so what reaches
     * {@link ActionResolver#permanentGrantForSelfClause} is ordinary field-ability text and the
     * existing readers need no notion of "which Element did this card name". Scarmiglione's own
     * clause lands on {@code FA_OUTGOING_DAMAGE_DOUBLER}, whose {@code telem} group carries the
     * Element through to the two readers that can double damage to a Forward.
     *
     * <p>Every Element the picker can return is checked at parse time, not when it resolves. A
     * clause no grant primitive reads back would be granted silently and do nothing — and this
     * grant is permanent, so it would stay wrong for the rest of the game. Declining here leaves
     * the ability visibly unparsed instead, which is the fail-closed reading.
     */
    static Consumer<GameContext> tryParseNameElementThenGainsQuotedPermanent(String text, CardData source) {
        if (source == null) return null;
        String matchOn = stripRestrictionSentences(text);
        if (matchOn.isEmpty()) matchOn = text;
        Matcher m = NAME_ELEMENT_THEN_GAINS_QUOTED_PERMANENT.matcher(matchOn.trim());
        if (!m.matches()) return null;
        if (!m.group("subject").trim().equalsIgnoreCase(source.name())) return null;

        final String template = m.group("quoted").trim();
        for (String e : Elements.ALL)
            if (permanentGrantForSelfClause(namedElementClause(template, e), source) == null) return null;

        return ctx -> {
            String elem = ctx.selectElement("Name 1 Element (" + source.name() + "):");
            if (elem == null) return;
            Consumer<GameContext> grant =
                    permanentGrantForSelfClause(namedElementClause(template, elem), source);
            if (grant == null) return;
            ctx.logEntry("Effect: " + source.name() + " names " + elem);
            grant.accept(ctx);
        };
    }

    /**
     * Writes the named Element into a granted clause — "a Forward of the named Element" becomes
     * "a Fire Forward", "an Ice Forward".
     *
     * <p>The article is corrected because the result is granted text: it is logged to the player
     * and read back as an ordinary field ability, so "a Ice Forward" would be visible.
     */
    private static String namedElementClause(String template, String element) {
        String article = "AEIOU".indexOf(Character.toUpperCase(element.charAt(0))) >= 0 ? "an" : "a";
        return template.replaceAll(
                "(?i)\\ba\\s+(Forward|Backup|Monster|Character)\\s+of\\s+the\\s+named\\s+Element\\b",
                article + " " + element + " $1");
    }

    /**
     * Snovlinka 27-112H's first option: "[Self] gains [keywords] and "[ability]" (This effect
     * does not end at the end of the turn.)" — a keyword and a quoted field ability handed over
     * together, both outlasting the turn.
     *
     * <p>Both payloads or neither. The quoted clause goes through
     * {@link ActionResolver#permanentGrantForSelfClause} and the ability is declined outright if
     * that returns null: the two halves are one sentence, and granting the keyword alone would be
     * a weaker effect reported as the whole one — the failure the fail-closed rule exists for.
     *
     * <p>The keywords ride on {@code boostSourceForwardPermanently} with an amount of zero, which
     * is the permanent trait store every other outlasting keyword grant writes to. No new
     * primitive: what is new here is the pairing, not either payload.
     */
    static Consumer<GameContext> tryParseGainsKeywordsAndQuotedAbilityPermanent(String text, CardData source) {
        if (source == null) return null;
        Matcher m = GAINS_KEYWORDS_AND_QUOTED_ABILITY_PERMANENT.matcher(text.trim());
        if (!m.matches()) return null;
        if (!m.group("subject").trim().equalsIgnoreCase(source.name())) return null;

        EnumSet<CardData.Trait> traits = parseTraits(m.group("keywords"));
        if (traits.isEmpty()) return null;
        Consumer<GameContext> granted = permanentGrantForSelfClause(m.group("quoted").trim(), source);
        if (granted == null) return null;

        return ctx -> {
            ctx.boostSourceForwardPermanently(source, 0, traits);
            granted.accept(ctx);
        };
    }

    /**
     * The subject is the source itself, so the clauses go through
     * {@link ActionResolver#permanentGrantForSelfClause} rather than the narrower
     * {@link ActionResolver#permanentGrantForClause} it delegates to: a self-grant can hand over a
     * readable field ability as well as a trigger-bearing auto ability, which is what Ifrit (XVI)
     * 29-001R / 26-003R's priming payoff does with its damage shield.
     */
    static Consumer<GameContext> tryParseGainsQuotedAbilitiesPermanent(String text, CardData source) {
        if (source == null) return null;
        Matcher m = GAINS_QUOTED_ABILITIES_PERMANENT.matcher(text.trim());
        if (!m.matches()) return null;
        if (!m.group("subject").trim().equalsIgnoreCase(source.name())) return null;

        Consumer<GameContext> first = permanentGrantForSelfClause(m.group("q1").trim(), source);
        if (first == null) return null;
        String second = m.group("q2");
        if (second == null) return first;
        Consumer<GameContext> rest = permanentGrantForSelfClause(second.trim(), source);
        // Both halves or neither — a half-applied grant is worse than an unrecognised one.
        if (rest == null) return null;
        return ctx -> { first.accept(ctx); rest.accept(ctx); };
    }
    /**
     * Parses "[Self] gains +N power[, traits] (This effect does not end at the end of the turn.)"
     * — 8-147S Fordola's payoff, the outlasts-the-turn twin of the {@code SELF_POWER_BOOST}
     * followup handled inside the choose chain.
     *
     * <p>Rejects a match that grants neither power nor a trait, which the all-optional groups
     * would otherwise allow for a bare "X gains (This effect …)".
     */
    static Consumer<GameContext> tryParseSelfPowerBoostPermanent(String text, CardData source) {
        if (source == null) return null;
        Matcher m = SELF_POWER_BOOST_PERMANENT.matcher(text.trim());
        if (!m.matches()) return null;
        if (!m.group("subject").trim().equalsIgnoreCase(source.name())) return null;
        int amount = m.group("amount") != null ? Integer.parseInt(m.group("amount")) : 0;
        EnumSet<CardData.Trait> traits = parseTraits(m.group("traits"));
        if (amount == 0 && traits.isEmpty()) return null;
        return ctx -> ctx.boostSourceForwardPermanently(source, amount, traits);
    }
    /**
     * Parses "[Self] gains [traits | "[quoted]"] and [Self]'s power becomes N." — the no-duration
     * grant that lasts while the card stays on the field (Hyoh 16-097H, Ramza 16-017R, and the
     * printings that add the "(This effect does not end…)" reminder, Roche 29-076H and Young
     * Excenmille 23-100L).
     *
     * <p>Matched against the restriction-stripped text so a trailing "You can only use this
     * ability if …" sentence does not defeat the end anchor — the same treatment
     * {@link #tryParseGainsQuotedFieldAbilityUntilEot} gives it, and for the same reason: the
     * restriction is captured as a flag on the ability and gated at activation, not executed here.
     *
     * <p>A quoted clause that no grant primitive recognises declines the whole match rather than
     * applying the power half alone — half an ability is worse than an unparsed one.
     */
    static Consumer<GameContext> tryParseSelfGainsAndBasePowerBecomesPermanent(String text, CardData source) {
        if (source == null) return null;
        String matchOn = stripRestrictionSentences(text);
        if (matchOn.isEmpty()) matchOn = text;
        Matcher m = SELF_GAINS_AND_BASE_POWER_BECOMES_PERMANENT.matcher(matchOn.trim());
        if (!m.matches()) return null;
        if (!m.group("subject").trim().equalsIgnoreCase(source.name())) return null;
        if (!m.group("powersubject").trim().equalsIgnoreCase(source.name())) return null;
        int power = Integer.parseInt(m.group("power"));

        String quoted = m.group("quoted");
        EnumSet<CardData.Trait> traits = quoted != null
                ? EnumSet.noneOf(CardData.Trait.class)
                : parseTraits(m.group("traits"));
        Consumer<GameContext> grant = null;
        if (quoted != null) {
            grant = permanentGrantForSelfClause(quoted.trim(), source);
            if (grant == null) return null;
        }
        final Consumer<GameContext> quotedGrant = grant;
        return ctx -> {
            if (quotedGrant != null) quotedGrant.accept(ctx);
            ctx.setSourceForwardBasePowerPermanently(source, power, traits);
        };
    }
    /**
     * Parses "[Self] gains [traits] and "[quoted]"." — Ramza 16-017R's second Crystal ability,
     * which hands itself First Strike and Brave alongside a quoted multi-attack permission.
     *
     * <p>The trait-list twin of {@link #tryParseSelfGainsAndBasePowerBecomesPermanent}, and treated
     * the same way in every respect that matters: matched against the restriction-stripped text so
     * a trailing "You can only use this ability if …" cannot defeat the end anchor, and declining
     * the whole match when the quoted clause is one no grant primitive recognises rather than
     * applying the traits alone.
     *
     * <p>The traits go through {@code boostSourceForwardPermanently} with a zero power amount:
     * that primitive's power half is optional, and routing through it is what puts the traits in
     * the same permanent map every other outlasts-the-turn grant writes to.
     */
    static Consumer<GameContext> tryParseSelfGainsTraitsAndQuotedPermanent(String text, CardData source) {
        if (source == null) return null;
        String matchOn = stripRestrictionSentences(text);
        if (matchOn.isEmpty()) matchOn = text;
        Matcher m = SELF_GAINS_TRAITS_AND_QUOTED_PERMANENT.matcher(matchOn.trim());
        if (!m.matches()) return null;
        if (!m.group("subject").trim().equalsIgnoreCase(source.name())) return null;

        EnumSet<CardData.Trait> traits = parseTraits(m.group("traits"));
        if (traits.isEmpty()) return null;
        // The same sentence is printed by cards that grant it as a standing field ability —
        // Gilgamesh 18-074L behind a Damage gate, Firion 18-130L and 21-099H behind a board
        // condition — and those are enforced by the readers that scan field abilities
        // (MainWindow.maxAttacksPerTurn, FieldGrantCalculator), never by executing them. Only
        // the printed source separates the two readings, so it is the source that is asked:
        // where the card prints this wording as a field ability, this is that ability rather
        // than an effect to run, and the grant is neither permanent nor ours to apply.
        if (printsAsFieldAbility(matchOn.trim(), source)) return null;
        Consumer<GameContext> grant = permanentGrantForSelfClause(m.group("quoted").trim(), source);
        if (grant == null) return null;
        return ctx -> {
            ctx.boostSourceForwardPermanently(source, 0, traits);
            grant.accept(ctx);
        };
    }
    /**
     * True when {@code source} prints {@code text} inside one of its field abilities — the
     * standing grants whose wording overlaps an executable one. Containment rather than
     * equality, because a gate the field ability prints ("If you control 5 or more
     * Characters, …") is stripped before the grant behind it reaches a parser.
     */
    private static boolean printsAsFieldAbility(String text, CardData source) {
        for (FieldAbility fa : source.fieldAbilities())
            if (fa.effectText() != null
                    && fa.effectText().toLowerCase(Locale.ROOT).contains(text.toLowerCase(Locale.ROOT)))
                return true;
        return false;
    }

    static Consumer<GameContext> tryParseOppFwdsLoseAllAbilitiesEot(String text) {
        if (!OPP_FWDS_LOSE_ALL_ABILITIES_EOT.matcher(text).matches()) return null;
        return ctx -> ctx.oppForwardsLoseAllAbilitiesUntilEndOfTurn();
    }

    /**
     * Parses "All [the] Characters [other than &lt;Elements&gt;] opponent controls lose all their
     * abilities until the end of the turn." — 2-138L Yuna's attack trigger, and the same sweep
     * unfiltered on 22-027R Shiva.
     *
     * <p>Sweeps all three field zones, which is what "Characters" means: the Forward-only sibling
     * above left Yuna's opponent their Backups and Monsters, so her ability was worth considerably
     * less than it prints. Anchored end to end like that sibling, and for the same reason — a sweep
     * of the whole board is the last effect that should be read out of the middle of a sentence.
     *
     * <p>The spared Elements are split here rather than in the context so the parse happens once
     * per printing instead of once per resolution; the set is fixed at parse time and never
     * written again, which is what lets the returned Consumer be reused.
     */
    static Consumer<GameContext> tryParseOppCharactersLoseAllAbilitiesEot(String text) {
        Matcher m = OPP_CHARACTERS_LOSE_ALL_ABILITIES_EOT.matcher(text.trim());
        if (!m.matches()) return null;
        Set<String> excluded = new LinkedHashSet<>();
        if (m.group("excludeelems") != null)
            for (String e : m.group("excludeelems").split("(?i)\\s+(?:and|or)\\s+"))
                excluded.add(e.trim());
        final Set<String> spared = Collections.unmodifiableSet(excluded);
        return ctx -> {
            ctx.logEntry("Effect: All Characters opponent controls"
                    + (spared.isEmpty() ? "" : " other than " + String.join(" and ", spared))
                    + " lose all their abilities until end of turn");
            ctx.opponentCharactersLoseAllAbilitiesUntilEndOfTurn(true, true, true, spared);
        };
    }

    /**
     * Parses "[Until the end of the turn,] all the Forwards opponent controls lose all their
     * abilities and N power[ until the end of the turn]." — 24-105R Malboro's parting shot.
     *
     * <p>Two sweeps over the same Forwards, run as one effect because the card prints them as one
     * sentence. Neither existing parser would take it: the ability-loss sibling above anchors with
     * {@code matches()} and the mass power sweep wants a number straight after "lose". Malboro had
     * been costing its controller a Monster for nothing.
     */
    static Consumer<GameContext> tryParseOppFwdsLoseAllAbilitiesAndPowerEot(String text) {
        Matcher m = OPP_FWDS_LOSE_ALL_ABILITIES_AND_POWER_EOT.matcher(text.trim());
        if (!m.matches()) return null;
        final int amount = Integer.parseInt(m.group("amount"));
        return ctx -> {
            ctx.logEntry("Effect: All Forwards opponent controls lose all abilities and "
                    + amount + " power until end of turn");
            ctx.oppForwardsLoseAllAbilitiesUntilEndOfTurn();
            ctx.applyMassFieldPowerBoost(-amount, true, false, true, false, null, -1, null, null, null);
        };
    }
    /** Reads a Forward-ability grant out of a field-ability text, or {@code null} if it is not one. */
    static ForwardAbilityGrant tryParseForwardAbilityGrant(String fieldText) {
        if (fieldText == null) return null;
        Matcher m = FIELD_GRANT_ABILITY_TO_FORWARDS.matcher(fieldText.trim());
        if (!m.matches()) return null;
        boolean affectsOpponent = m.group("who").toLowerCase(java.util.Locale.ROOT).startsWith("opponent");
        return new ForwardAbilityGrant(affectsOpponent, m.group("ability").trim());
    }

    /**
     * Recognises "The [Job X | Category Y | Element] Forwards [other than Z] you control gain
     * [Trait[s] and] "&lt;quotation&gt;."" — Yuna &amp; Tidus PR-111 and Snow &amp; Lightning
     * PR-158 — and returns a no-op, so {@link ActionResolver#parse} reports the sentence as read
     * without running anything. Both halves are continuous and reach the engine off
     * {@link CardData}: the keyword through {@code FieldGrantCalculator}, the quotation through
     * {@code MainWindow.filteredGrantedAutoAbilities} or {@code MainWindow.maxAttacksPerTurn}.
     *
     * <p>Must be dispatched ahead of the choose chain, which is the whole point of the guard rather
     * than a side effect of it. PR-158's quotation contains "choose 1 Character. Dull it and Freeze
     * it.", and {@code tryParseChooseCharacter} matches it with {@code find()} — claiming an effect
     * out of the middle of the sentence and running it with neither the trigger that gates it nor
     * the grantee it belongs to. The sentence had been reported as {@code ChooseCharacter /
     * DullAndFreeze} for exactly that reason.
     *
     * <p>Claims only what {@link CardData} honours: a quotation neither grant parser accepts leaves
     * the sentence unread rather than granting the keyword and dropping the rest.
     */
    static Consumer<GameContext> tryParseFilteredForwardsQuotedGrant(String text) {
        if (text == null) return null;
        if (CardData.parseFilteredAbilityGrant(text) == null
                && CardData.parseFilteredMaxAttacksGrant(text) == null) return null;
        return ctx -> { /* continuous field grant — applied off CardData, not run as an effect */ };
    }
    /**
     * Parses the "At the end of your turn, …" half of a granted ability into an effect that runs for
     * {@code grantee} — the Forward that received it, which is what self-references like "this
     * Forward" resolve to. Returns {@code null} when the grant is not an end-of-turn ability or its
     * effect is not supported.
     */
    static Consumer<GameContext> tryParseGrantedEndOfTurnEffect(String abilityText, CardData grantee) {
        if (abilityText == null || grantee == null) return null;
        Matcher m = GRANTED_AT_END_OF_YOUR_TURN.matcher(abilityText.trim());
        if (!m.matches()) return null;
        return parse(m.group("effect").trim(), grantee);
    }
    /**
     * Parses "At the end of each of your turns, &lt;effect&gt;" — a recurring field-ability
     * trigger.  Returns a consumer that executes the inner effect directly; the caller
     * ({@code fireFieldEndOfTurnAbilities}) is responsible for invoking it each end phase.
     * The inner effect is resolved via the full {@link #parse} dispatcher so all supported
     * effect types work.
     */
    static Consumer<GameContext> tryParseEndOfEachTurnFieldAbility(String text, CardData source) {
        Matcher m = AT_END_OF_EACH_TURN_PATTERN.matcher(text);
        if (!m.find()) return null;
        String inner = m.group("inner").trim();
        Consumer<GameContext> innerEffect = parse(inner, source);
        if (innerEffect == null) return null;
        return innerEffect;
    }
    /**
     * Parses "At the end of your opponent's turn, &lt;effect&gt;" appearing inside an ability that
     * resolves now — 20-057L The Goddess's "When The Goddess enters the field, at the end of your
     * opponent's turn, break all the Forwards opponent controls with a Doom Counter on them."
     * The inner effect is queued as a one-shot rather than run.
     *
     * <p>Must be tried before any parser that would match the inner effect on its own: they all
     * use {@code find()}, so {@link #tryParseAllFieldEffect} would happily claim the "break all …"
     * tail and drop the delay, breaking the Forwards the moment the ability resolved.
     *
     * <p>Returns null for a bare-pronoun action ("break it"), which has no target of its own and
     * belongs to a preceding choose — {@code tryParseChooseThenEndOfOppTurnAction} owns that shape.
     *
     * <p>Also defers to {@code tryParseEndOfOppTurnPlayNamedOntoField}, which sits further down
     * the chain and cannot defend itself from here. 26-025C Kadaj removes itself from the game and
     * plays back "at the end of your opponent's turn" — from the <em>RFG zone</em>, which only that
     * parser knows. Resolving its inner text through the general chain would return Kadaj from the
     * wrong zone.
     */
    static Consumer<GameContext> tryParseEndOfOppTurnDelayedEffect(String text, CardData source) {
        Matcher m = AT_END_OF_OPP_TURN_DELAY_PREFIX.matcher(text.trim());
        if (!m.matches()) return null;
        if (AT_END_OF_OPP_TURN_PLAY_NAMED_ONTO_FIELD.matcher(text.trim()).matches()) return null;
        String inner = m.group("inner").trim();
        if (DELAYED_BARE_PRONOUN_ACTION.matcher(inner).find()) return null;
        Consumer<GameContext> innerEffect = parse(inner, source);
        if (innerEffect == null) return null;
        return ctx -> {
            ctx.logEntry("Queued for the end of your opponent's turn: " + inner);
            ctx.addEndOfOpponentTurnEffect(innerEffect);
        };
    }
    /**
     * Parses "&lt;base&gt;. If &lt;condition&gt;, &lt;upgrade&gt; instead." — a whole effect and the
     * whole effect that replaces it. 16-140S Sin, 17-048C Thief, 17-111C Chemist.
     *
     * <p>Replaces, never appends. All three were claimed by an ordinary {@code find()} parser off
     * the base sentence, which discarded the replacement outright — Sin broke its own controller's
     * Forwards at 6 damage where the card says it should spare them. The tempting repair, letting
     * the chain run the trailing sentence too, is strictly worse: that sentence parses on its own
     * only by dropping the condition in front of it, so the upgrade would land unconditionally
     * <em>on top of</em> the base.
     *
     * <p>Declines unless every part is understood — the condition, the base and the replacement.
     * That is what keeps it off the two much larger families sharing this wording. A replacement
     * pointing back at a chosen target ("deal it 8000 damage instead") does not parse standalone,
     * and a modal one ("select up to 2 of the 3 following actions instead") is resolved by
     * {@code AutoAbilityTriggers} rather than by a {@code Consumer}; both fail a check here and are
     * left to the parsers that already read them correctly.
     *
     * <p>The two target-state conditions are refused explicitly. {@link
     * ActionResolver#insteadConditionMet} throws on them by contract — they need a
     * {@link ForwardTarget} — and this parser has no target to offer.
     */
    static Consumer<GameContext> tryParseEffectThenConditionalInstead(String text, CardData source, int xValue) {
        Matcher m = EFFECT_THEN_CONDITIONAL_INSTEAD.matcher(text.trim());
        if (!m.matches()) return null;

        DamageInsteadCondition cond = parseDamageInsteadCondition(m.group("cond").trim());
        if (cond == null) return null;
        // "If you control …" is the older {@code tryParseControlGatedInsteadUpgrade}'s family, and
        // that parser sits four hundred lines earlier in parse(). Claiming those texts here would
        // have no effect on what runs and would still change what the name chain reports, which is
        // exactly the drift between the two chains this repo keeps having to reconcile. It carries
        // its own guard for 16-122R Marche's elided noun, so that case stays refused either way.
        if (cond instanceof DamageInsteadCondition.YouControl) return null;
        if (cond instanceof DamageInsteadCondition.TargetIsActive
                || cond instanceof DamageInsteadCondition.TargetIsMultiElement) return null;

        String baseText    = m.group("base").trim();
        String upgradeText = m.group("upgrade").trim();
        Consumer<GameContext> base    = parse(baseText, source, xValue);
        Consumer<GameContext> upgrade = parse(upgradeText, source, xValue);
        if (base == null || upgrade == null) return null;

        return ctx -> {
            boolean met = insteadConditionMet(ctx, cond);
            ctx.logEntry("Effect: " + (met ? upgradeText + " (instead)" : baseText));
            (met ? upgrade : base).accept(ctx);
        };
    }

    /**
     * Parses "Place N [Name] Counter(s) on all [the] Forwards [opponent controls|you control]."
     * (20-057L The Goddess.)
     */
    static Consumer<GameContext> tryParsePlaceCounterOnAllForwards(String text) {
        Matcher m = PLACE_COUNTER_ON_ALL_FORWARDS.matcher(text);
        if (!m.matches()) return null;
        int    count       = Integer.parseInt(m.group("count"));
        String counterName = m.group("name").trim();
        String control     = m.group("control");
        boolean opponentOnly = control != null && !control.toLowerCase().contains("you control");
        boolean selfOnly     = control != null &&  control.toLowerCase().contains("you control");
        String controlLabel  = opponentOnly ? " (opponent)" : selfOnly ? " (yours)" : "";
        return ctx -> {
            ctx.logEntry("Effect: Place " + count + " " + counterName
                    + " Counter(s) on all Forwards" + controlLabel);
            ctx.placeCountersOnAllForwards(counterName, count, opponentOnly, selfOnly);
        };
    }
    /**
     * Parses "Activate all [the] X. When N or more dull Characters are activated by this effect,
     * draw M card(s)." — 19-102L Refia.
     *
     * <p>The sweep half is delegated to {@link #tryParseAllFieldEffect}, so this parser owns only
     * the payoff. The count comes from {@link GameContext#lastMassActivateCount()} — what the
     * sweep woke up — rather than from a count of eligible cards taken beforehand: "activated by
     * this effect" excludes the ones that were already active, and a card can only be counted by
     * the code that changed it.
     */
    static Consumer<GameContext> tryParseAllFieldActivateThenDraw(String text) {
        Matcher m = ALL_FIELD_ACTIVATE_THEN_DRAW.matcher(text.trim());
        if (!m.matches()) return null;
        Consumer<GameContext> sweep = tryParseAllFieldEffect(m.group("sweep"));
        if (sweep == null) return null;
        int threshold = Integer.parseInt(m.group("threshold"));
        int draw      = Integer.parseInt(m.group("draw"));
        return ctx -> {
            sweep.accept(ctx);
            int activated = ctx.lastMassActivateCount();
            if (activated < threshold) {
                ctx.logEntry("Effect: " + activated + " dull Character(s) activated — fewer than "
                        + threshold + ", no card drawn");
                return;
            }
            ctx.logEntry("Effect: " + activated + " dull Character(s) activated — draw " + draw);
            ctx.drawCards(draw);
        };
    }

    /**
     * The unconditional twin of {@link #tryParseAllFieldActivateThenDraw}: "[sweep] and draw N
     * cards", joined by a plain "and" with no threshold counting what the sweep did — 11-035R
     * Setzer's even branch and 17-102L Hooded Man.
     *
     * <p>The sweep half is delegated to {@link #tryParseAllFieldEffect}, as it is above, so a
     * sweep that parser declines is declined here too rather than resolving as a bare draw.
     * <b>Must precede {@link #tryParseAllFieldEffect} in every dispatch chain</b>: that one
     * matches with {@code find()} and claims the sweep on its own, dropping the draw.
     */
    static Consumer<GameContext> tryParseAllFieldEffectAndDraw(String text) {
        Matcher m = ALL_FIELD_EFFECT_AND_DRAW.matcher(text.trim());
        if (!m.matches()) return null;
        Consumer<GameContext> sweep = tryParseAllFieldEffect(m.group("sweep"));
        if (sweep == null) return null;
        int draw = Integer.parseInt(m.group("draw"));
        return ctx -> {
            sweep.accept(ctx);
            ctx.logEntry("Effect: Draw " + draw + " card(s)");
            ctx.drawCards(draw);
        };
    }

    /**
     * Parses 14-062L Titan, Lord of Crags: "Break all the Forwards with power less than [Self].
     * When N or more Forwards are put from the field into the Break Zone by this effect, [Self]
     * deals your opponent M point(s) of damage."
     *
     * <p>Kept out of the general mass-effect parser because its filter is a comparison against a
     * card on the field rather than a printed value, and because the payoff counts what the sweep
     * did. {@code applyMassFieldEffect} can express neither, which is why
     * {@link #tryParseAllFieldEffect} refuses a power filter instead of dropping it.
     *
     * <p>Self-named in both halves: the threshold is the carrier's own power and the damage is
     * dealt by the carrier, so a text naming another card is declined rather than quietly applied
     * to whatever printed it.
     *
     * <p><b>Must precede {@link #tryParseAllFieldEffect} in every dispatch chain.</b> That one now
     * declines this text, so the order is not load-bearing for correctness — but a future widening
     * that made it accept a power filter would silently take this back.
     */
    static Consumer<GameContext> tryParseBreakForwardsBelowSelfPower(String text, CardData source) {
        if (source == null) return null;
        Matcher m = BREAK_FORWARDS_BELOW_SELF_POWER.matcher(text.trim());
        if (!m.matches()) return null;
        if (!m.group("card").trim().equalsIgnoreCase(source.name())) return null;

        boolean hasPayoff = m.group("threshold") != null;
        if (hasPayoff && !m.group("dmgcard").trim().equalsIgnoreCase(source.name())) return null;
        int threshold = hasPayoff ? Integer.parseInt(m.group("threshold")) : 0;
        int amount    = hasPayoff ? Integer.parseInt(m.group("amount"))    : 0;

        return ctx -> {
            int broken = ctx.breakForwardsWithPowerBelow(source);
            if (!hasPayoff) return;
            if (broken < threshold) {
                ctx.logEntry("Effect: " + broken + " Forward(s) broken — fewer than " + threshold
                        + ", no damage dealt");
                return;
            }
            ctx.logEntry("Effect: " + broken + " Forward(s) broken — " + source.name()
                    + " deals your opponent " + amount + " point(s) of damage");
            ctx.dealDamageToOpponent(amount);
        };
    }

    /**
     * Parses "Remove all [the] &lt;types&gt; [on the field] [&lt;control&gt;] [other than &lt;name&gt;]
     * [and all cards in &lt;whose&gt; Break Zone] from the game."
     *
     * <p>The sweep goes through the same {@code applyMassFieldEffect} the Break and Dull sweeps
     * use, with {@link GameContext.MassAction#REMOVE_FROM_GAME} — so the leave-the-field shields
     * hold against it exactly as they do against a single removal, which is what the rules say and
     * what {@code find()}-claiming it as a card-name removal never gave it.
     *
     * <p>The Break Zone half, where a printing has one, runs after the field half. That is the
     * printed order, and it matters: the field sweep puts its cards out of the game rather than
     * into a Break Zone, so nothing it touches can arrive in time to be swept twice — but a reader
     * coming to this later should not have to work that out from the code.
     */
    static Consumer<GameContext> tryParseRemoveAllFieldFromGame(String text) {
        Matcher m = REMOVE_ALL_FIELD_FROM_GAME.matcher(text.trim());
        if (!m.matches()) return null;

        String  tgtLower     = m.group("targets").toLowerCase();
        boolean inclForwards = tgtLower.contains("forward") || tgtLower.contains("character");
        boolean inclBackups  = tgtLower.contains("character");
        boolean inclMonsters = tgtLower.contains("monster")  || tgtLower.contains("character");

        String  control      = m.group("control");
        boolean opponentOnly = control != null && !control.toLowerCase().contains("you control");
        boolean selfOnly     = control != null &&  control.toLowerCase().contains("you control");
        String  excludeName  = m.group("exclude") != null ? m.group("exclude").trim() : null;
        String  bz           = m.group("bz");
        boolean bzOpponentOnly = bz != null && bz.toLowerCase().contains("opponent");

        return ctx -> {
            ctx.logEntry("Effect: Remove all " + m.group("targets")
                    + (control != null ? " " + control : "")
                    + (excludeName != null ? " other than " + excludeName : "")
                    + " from the game");
            ctx.applyMassFieldEffect(GameContext.MassAction.REMOVE_FROM_GAME,
                    inclForwards, inclBackups, inclMonsters, opponentOnly, selfOnly,
                    null, -1, null, -1, null, null,
                    EnumSet.noneOf(CardData.Trait.class), null, excludeName);
            if (bz == null) return;
            if (bzOpponentOnly) ctx.removeAllOpponentBzFromGame();
            else                ctx.removeAllBreakZonesFromGame();
        };
    }

    /**
     * Parses Baron Guardsman 17-072H's "name 1 card type. Remove all the cards of named card type
     * in your opponent's Break Zone from the game." Both sentences are one decision, so they are
     * one primitive; see {@link GameContext#nameCardTypeRemoveAllOfTypeFromOppBzFromGame}.
     */
    static Consumer<GameContext> tryParseNameCardTypeRemoveOppBzFromGame(String text) {
        if (!NAME_CARD_TYPE_REMOVE_OPP_BZ_FROM_GAME.matcher(text.trim()).matches()) return null;
        return ctx -> {
            ctx.logEntry("Effect: Name 1 card type, remove all of it from opponent's Break Zone");
            ctx.nameCardTypeRemoveAllOfTypeFromOppBzFromGame();
        };
    }

    /**
     * Parses "[action] all [the] [element] [targets] [of cost X] [control]".
     *
     * <p>Supported actions: Break, dull, freeze, dull and freeze, Activate.
     * <p>Supported targets: Forwards, Backups, Forwards and Monsters, Characters.
     */
    static Consumer<GameContext> tryParseAllFieldEffect(String text) {
        Matcher m = ALL_FIELD_EFFECT_PATTERN.matcher(text);
        if (!m.find()) return null;
        // A power filter is captured only so it can be refused here: applyMassFieldEffect has no
        // power parameter to carry it, and honouring the sweep without it is the board wipe this
        // guard exists to prevent. tryParseBreakForwardsBelowSelfPower reads the one printing.
        if (m.group("powercmp") != null) return null;
        // A sweep that names nothing it sweeps can only be a misparse. Every filter group in the
        // pattern is optional and its end is unanchored, so a filter word the regex cannot read
        // ends the match early at "all the " — and because "control" trails "targets", the side
        // restriction is dropped with it and the sweep takes both players' whole boards. Five
        // printings were doing exactly that; the state and Card Name arms now read four of the
        // words involved, and this guard is what makes the next unread one fail closed instead of
        // becoming the sixth. The corpus check is tools/probes/SweepScan.java, which must print
        // nothing.
        if (m.group("targets") == null && m.group("job") == null
                && m.group("category") == null && m.group("name") == null) return null;

        String rawAction = m.group("action").toLowerCase().replaceAll("\\s+", " ");
        GameContext.MassAction action = switch (rawAction) {
            case "break"          -> GameContext.MassAction.BREAK;
            case "dull"           -> GameContext.MassAction.DULL;
            case "freeze"         -> GameContext.MassAction.FREEZE;
            case "dull and freeze"-> GameContext.MassAction.DULL_AND_FREEZE;
            case "activate"       -> GameContext.MassAction.ACTIVATE;
            default               -> null;
        };
        if (action == null) return null;

        String element   = m.group("element");
        String job       = m.group("job") != null ? m.group("job").trim() : null;
        String category  = m.group("category");
        String targets   = m.group("targets");
        // When no explicit type is given (job-only or category-only), sweep all card types
        boolean inclForwards, inclBackups, inclMonsters;
        if (targets == null) {
            inclForwards = true; inclBackups = true; inclMonsters = (job == null && category == null);
        } else {
            String tgtLower  = targets.toLowerCase();
            inclForwards = tgtLower.contains("forward") || tgtLower.contains("character");
            inclBackups  = tgtLower.contains("backup")  || tgtLower.contains("character");
            inclMonsters = tgtLower.contains("monster") || tgtLower.contains("character");
        }

        String costStr = m.group("cost");
        String costCmp = m.group("costcmp");
        int    costVal = costStr != null ? Integer.parseInt(costStr) : -1;

        String excludeCostStr = m.group("excludecost");
        int    excludeCostVal = excludeCostStr != null ? Integer.parseInt(excludeCostStr) : -1;

        String control      = m.group("control");
        boolean opponentOnly = control != null && !control.toLowerCase().contains("you control");
        boolean selfOnly     = control != null && control.toLowerCase().contains("you control");

        String traitStr     = m.group("trait");
        EnumSet<CardData.Trait> traitFilter = parseTraits(traitStr);

        String counterRaw    = m.group("counter");
        String counterFilter = counterRaw != null ? counterRaw.trim() : null;

        String stateRaw    = m.group("state");
        String stateFilter = stateRaw != null ? stateRaw.trim().toLowerCase() : null;
        String nameRaw     = m.group("name");
        String nameFilter  = nameRaw != null ? nameRaw.trim() : null;

        String excludeRaw  = m.group("excludename");
        String excludeName = excludeRaw != null ? excludeRaw.trim() : null;
        // "other than Job Mage" spares a job, not a card name — 29-027L Shantotto, the corpus's
        // one printing of it. Routed to its own parameter rather than to excludeName, which
        // compares names and would spare only a card actually called "Job Mage": nothing, leaving
        // the sweep to take every Mage the printing protects. A Category exclusion has no printing
        // and no parameter, so it is still declined rather than half-applied.
        Matcher xj = excludeName != null ? SWEEP_EXCLUDE_JOB.matcher(excludeName) : null;
        boolean excludesJob = xj != null && xj.matches();
        if (excludeName != null && !excludesJob
                && excludeName.matches("(?i)^Category\\b.*")) return null;
        final String excludeJob      = excludesJob ? xj.group("job").trim() : null;
        final String excludeCardName = excludesJob ? null : excludeName;

        String actionLabel = switch (action) {
            case BREAK           -> "Break";
            case DULL            -> "Dull";
            case FREEZE          -> "Freeze";
            case DULL_AND_FREEZE -> "Dull & Freeze";
            case ACTIVATE        -> "Activate";
            case RETURN_TO_HAND  -> "Return to hand";
            // Reachable only through the choose chain's sweep followup (21-074L Neo Exdeath), not
            // through this parser's own action words — the label is here because the switch is
            // exhaustive over the enum, which is what made the compiler point at this line.
            case REMOVE_FROM_GAME -> "Remove from the game";
        };
        String tgtLabel     = targets != null ? targets
                : job != null ? "Job " + job
                : category != null ? "Cat " + category
                : "Card Name " + nameFilter;
        String stateLabel   = stateFilter != null ? " " + stateFilter : "";
        String nameLabel    = nameFilter != null && targets != null ? " named " + nameFilter : "";
        String costLabel    = costVal >= 0
                ? " of cost " + costVal + (costCmp != null ? " or " + costCmp : "") : "";
        String exclLabel    = excludeCostVal >= 0 ? " [not cost " + excludeCostVal + "]" : "";
        String exclNameLbl  = excludeCardName != null ? " [not " + excludeCardName + "]" : "";
        String exclJobLbl   = excludeJob != null ? " [not Job " + excludeJob + "]" : "";
        String controlLabel = opponentOnly ? " (opponent)" : selfOnly ? " (yours)" : "";
        String traitLabel   = traitStr != null ? " with " + traitStr.trim() : "";
        String counterLabel = counterFilter != null ? " with a " + counterFilter + " Counter" : "";
        String logMsg = actionLabel + " all" + stateLabel + " " + tgtLabel + nameLabel
                + traitLabel + costLabel + exclLabel + exclNameLbl + exclJobLbl + controlLabel + counterLabel;

        return ctx -> {
            ctx.logEntry("Effect: " + logMsg);
            ctx.applyMassFieldEffect(action, inclForwards, inclBackups, inclMonsters,
                    opponentOnly, selfOnly, element, costVal, costCmp, excludeCostVal, job, category,
                    traitFilter, counterFilter, excludeCardName, stateFilter, nameFilter, excludeJob);
        };
    }
    /**
     * Parses "All [the] Job X [targets] [you control] gain Keyword[, ...] until end of turn."
     */
    static Consumer<GameContext> tryParseAllFieldJobKeywordGrant(String text) {
        Matcher m = ALL_FIELD_JOB_KEYWORD_GRANT_PATTERN.matcher(text);
        if (!m.find()) return null;

        String job      = m.group("job").trim();
        String targets  = m.group("targets");
        boolean inclForwards = targets == null || targets.toLowerCase().contains("forward")
                            || targets.toLowerCase().contains("character");
        boolean inclMonsters = targets == null || targets.toLowerCase().contains("monster")
                            || targets.toLowerCase().contains("character");

        String control       = m.group("control");
        boolean opponentOnly = control != null && !control.toLowerCase().contains("you control");
        boolean selfOnly     = control != null &&  control.toLowerCase().contains("you control");

        EnumSet<CardData.Trait> traits = parseTraits(m.group("keywords"));
        if (traits.isEmpty()) return null;

        String traitNames = traitNamesOnly(traits);
        String typeLabel  = targets != null ? " " + targets : "";
        String controlLabel = opponentOnly ? " (opponent)" : selfOnly ? " (yours)" : "";
        String logMsg = "All Job " + job + typeLabel + controlLabel + " gain " + traitNames + " until end of turn";

        return ctx -> {
            ctx.logEntry("Effect: " + logMsg);
            ctx.applyMassFieldJobKeywordGrant(traits, inclForwards, inclMonsters,
                    opponentOnly, selfOnly, job);
        };
    }
    /**
     * Parses "All [the] [element] [targets] [of cost N or less/more] [you control] gain
     * Keyword[, Keyword2, ...] until end of turn."
     */
    static Consumer<GameContext> tryParseAllFieldKeywordGrant(String text) {
        Matcher m = ALL_FIELD_KEYWORD_GRANT_PATTERN.matcher(text);
        if (!m.find()) return null;

        String element  = m.group("element");
        String category = m.group("category");
        String targets  = m.group("targets");
        String tgtLower = targets.toLowerCase();
        boolean inclForwards = tgtLower.contains("forward") || tgtLower.contains("character");
        boolean inclMonsters = tgtLower.contains("monster") || tgtLower.contains("character");

        String costStr = m.group("cost");
        String costCmp = m.group("costcmp");
        int    costVal = costStr != null ? Integer.parseInt(costStr) : -1;

        String control       = m.group("control");
        boolean opponentOnly = control != null && !control.toLowerCase().contains("you control");
        boolean selfOnly     = control != null && control.toLowerCase().contains("you control");

        EnumSet<CardData.Trait> traits = parseTraits(m.group("keywords"));
        if (traits.isEmpty()) return null;

        String elemLabel    = element != null ? element + " " : "";
        String catLabel     = category != null ? "Category " + category + " " : "";
        String costLabel    = costVal >= 0 ? " of cost " + costVal + (costCmp != null ? " or " + costCmp : "") : "";
        String controlLabel = opponentOnly ? " (opponent)" : selfOnly ? " (yours)" : "";
        String traitNames   = traitNamesOnly(traits);
        String logMsg = "All " + elemLabel + catLabel + targets + costLabel + controlLabel + " gain " + traitNames + " until end of turn";

        return ctx -> {
            ctx.logEntry("Effect: " + logMsg);
            ctx.applyMassFieldKeywordGrant(traits, inclForwards, inclMonsters,
                    opponentOnly, selfOnly, element, costVal, costCmp, category);
        };
    }
    /**
     * Parses the quoted-protection twin of {@link #tryParseAllFieldKeywordGrant}: "[Until the end
     * of the turn,] all [the] [element] [targets] [you control] gain "&lt;protection&gt;"[ and
     * "&lt;another&gt;"] [until the end of the turn]." — 10-076H Titan's third option and 23-039R
     * Asura.
     *
     * <p>Granted the same way a keyword is, because that is what the quoted sentences amount to
     * here: each one is a protection with a {@link CardData.Trait} behind it, and the traits are
     * temporary for the turn either way. A quote with no such trait — "cannot be broken",
     * "cannot be chosen", a whole triggered ability — leaves the text unparsed rather than
     * granting the ones that were understood and dropping the rest.
     */
    static Consumer<GameContext> tryParseAllFieldQuotedProtectionGrant(String text) {
        Matcher m = ALL_FIELD_QUOTED_PROTECTION_GRANT.matcher(text.trim());
        if (!m.matches()) return null;
        // One end or the other has to carry the duration. A printing with neither is a permanent
        // field ability, which this must not shorten to a turn.
        if (m.group("pre") == null && m.group("post") == null) return null;

        EnumSet<CardData.Trait> traits = grantedProtectionTraits(m.group("grants"));
        if (traits == null) return null;

        String targets = m.group("targets");
        boolean inclForwards = true;   // both arms of the pattern name Forwards or Characters
        boolean inclMonsters = targets.toLowerCase(Locale.ROOT).contains("character");

        String element       = m.group("element");
        String control       = m.group("control");
        boolean opponentOnly = control != null && !control.toLowerCase(Locale.ROOT).contains("you control");
        boolean selfOnly     = control != null &&  control.toLowerCase(Locale.ROOT).contains("you control");

        String logMsg = "All " + (element != null ? element + " " : "") + targets
                + (opponentOnly ? " (opponent)" : selfOnly ? " (yours)" : "")
                + " gain " + traitNamesOnly(traits) + " until end of turn";
        return ctx -> {
            ctx.logEntry("Effect: " + logMsg);
            ctx.applyMassFieldKeywordGrant(traits, inclForwards, inclMonsters,
                    opponentOnly, selfOnly, element, -1, null, null);
        };
    }
    /**
     * Parses "At the beginning of your Main Phase 1, &lt;effect&gt;" — a recurring
     * field-ability trigger.  Strips the trigger prefix and dispatches the inner effect
     * through the full {@link #parse} chain so any supported effect can follow.
     * {@code fireFieldMainPhase1Abilities} is responsible for invoking it each Main Phase 1 start.
     */
    static Consumer<GameContext> tryParseBeginningOfMainPhase1FieldAbility(String text, CardData source) {
        Matcher m = AT_BEGINNING_OF_MAIN_PHASE_1_PATTERN.matcher(text);
        if (!m.find()) return null;
        return parse(m.group("inner").trim(), source);
    }
    /**
     * Parses "At the beginning of your Main Phase 2, &lt;effect&gt;" — same as
     * {@link #tryParseBeginningOfMainPhase1FieldAbility} but for Main Phase 2.
     */
    static Consumer<GameContext> tryParseBeginningOfMainPhase2FieldAbility(String text, CardData source) {
        Matcher m = AT_BEGINNING_OF_MAIN_PHASE_2_PATTERN.matcher(text);
        if (!m.find()) return null;
        return parse(m.group("inner").trim(), source);
    }
    /**
     * Parses "Each turn, at the beginning of Main Phase 1, &lt;effect&gt;" — fires at the start of
     * BOTH players' Main Phase 1 for all cards the controller has on the field.
     * {@code fireFieldMainPhase1EachTurnAbilities} is responsible for invoking it.
     */
    static Consumer<GameContext> tryParseBeginningOfMainPhase1EachTurnFieldAbility(String text, CardData source) {
        Matcher m = AT_BEGINNING_OF_MAIN_PHASE_1_EACH_TURN_PATTERN.matcher(text);
        if (!m.find()) return null;
        return parse(m.group("inner").trim(), source);
    }
    /**
     * Parses "At the beginning of your opponent's Main Phase 1, &lt;effect&gt;" — fires at the start of
     * the controller's opponent's Main Phase 1.
     * {@code fireFieldOppMainPhase1Abilities} is responsible for invoking it.
     */
    static Consumer<GameContext> tryParseBeginningOfOppMainPhase1FieldAbility(String text, CardData source) {
        Matcher m = AT_BEGINNING_OF_OPP_MAIN_PHASE_1_PATTERN.matcher(text);
        if (!m.find()) return null;
        return parse(m.group("inner").trim(), source);
    }
    /**
     * Parses "At the end of your opponent's turn, &lt;effect&gt;" — fires when the controlling
     * player's opponent ends their turn.
     * {@code fireFieldEndOfOpponentTurnAbilities} is responsible for invoking it.
     */
    static Consumer<GameContext> tryParseEndOfOpponentTurnFieldAbility(String text, CardData source) {
        Matcher m = AT_END_OF_OPP_TURN_PATTERN.matcher(text);
        if (!m.find()) return null;
        return parse(m.group("inner").trim(), source);
    }
}
