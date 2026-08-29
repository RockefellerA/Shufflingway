package shufflingway;

import static shufflingway.ActionResolverPatterns.*;

import static shufflingway.ActionResolver.*;

import java.util.EnumSet;
import java.util.Locale;
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
    static Consumer<GameContext> tryParseGainsQuotedAbilitiesPermanent(String text, CardData source) {
        if (source == null) return null;
        Matcher m = GAINS_QUOTED_ABILITIES_PERMANENT.matcher(text.trim());
        if (!m.matches()) return null;
        if (!m.group("subject").trim().equalsIgnoreCase(source.name())) return null;

        Consumer<GameContext> first = permanentGrantForClause(m.group("q1").trim(), source);
        if (first == null) return null;
        String second = m.group("q2");
        if (second == null) return first;
        Consumer<GameContext> rest = permanentGrantForClause(second.trim(), source);
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
    /** Reads a Forward-ability grant out of a field-ability text, or {@code null} if it is not one. */
    static ForwardAbilityGrant tryParseForwardAbilityGrant(String fieldText) {
        if (fieldText == null) return null;
        Matcher m = FIELD_GRANT_ABILITY_TO_FORWARDS.matcher(fieldText.trim());
        if (!m.matches()) return null;
        boolean affectsOpponent = m.group("who").toLowerCase(java.util.Locale.ROOT).startsWith("opponent");
        return new ForwardAbilityGrant(affectsOpponent, m.group("ability").trim());
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

        String actionLabel = switch (action) {
            case BREAK           -> "Break";
            case DULL            -> "Dull";
            case FREEZE          -> "Freeze";
            case DULL_AND_FREEZE -> "Dull & Freeze";
            case ACTIVATE        -> "Activate";
            case RETURN_TO_HAND  -> "Return to hand";
        };
        String tgtLabel     = targets != null ? targets : (job != null ? "Job " + job : category != null ? "Cat " + category : "all");
        String costLabel    = costVal >= 0
                ? " of cost " + costVal + (costCmp != null ? " or " + costCmp : "") : "";
        String exclLabel    = excludeCostVal >= 0 ? " [not cost " + excludeCostVal + "]" : "";
        String controlLabel = opponentOnly ? " (opponent)" : selfOnly ? " (yours)" : "";
        String traitLabel   = traitStr != null ? " with " + traitStr.trim() : "";
        String counterLabel = counterFilter != null ? " with a " + counterFilter + " Counter" : "";
        String logMsg = actionLabel + " all " + tgtLabel + traitLabel + costLabel + exclLabel
                + controlLabel + counterLabel;

        return ctx -> {
            ctx.logEntry("Effect: " + logMsg);
            ctx.applyMassFieldEffect(action, inclForwards, inclBackups, inclMonsters,
                    opponentOnly, selfOnly, element, costVal, costCmp, excludeCostVal, job, category,
                    traitFilter, counterFilter);
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
