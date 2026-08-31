package shufflingway;

import static shufflingway.ActionResolverPatterns.*;

import static shufflingway.ActionResolver.*;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.regex.Matcher;

/**
 * Power parsers split out of {@link ActionResolver}.
 *
 * <p>Bodies only: {@code ActionResolver} keeps every dispatch chain and calls these
 * through a wildcard static import, so call order -- which is load-bearing, because
 * matchers use {@code find()} -- is unchanged.
 */
final class ActionResolverPower {

	private ActionResolverPower() {}

    /**
     * Parses "Choose 1 Forward with N power or less and up to 1 Forward in your opponent's
     * Break Zone. Remove them from the game."
     * <p>
     * Selects one field Forward (either player) with power ≤ N, plus optionally one Forward
     * from the opponent's Break Zone, then removes both from the game.
     */
    static Consumer<GameContext> tryParseChooseFwdPowerLeAndOptOppBzFwdRfp(String text) {
        Matcher m = CHOOSE_FWD_POWER_LE_AND_OPT_OPP_BZ_FWD_RFP.matcher(text);
        if (!m.find()) return null;

        final int powerCeil = Integer.parseInt(m.group("power"));

        return ctx -> {
            ctx.logEntry("Choose 1 Forward with power ≤ " + powerCeil
                    + " and up to 1 Forward from opponent's Break Zone — Remove from game");
            List<ForwardTarget> fieldTs = selectTargets(ctx, 1, false, false, false,
                    null, null, null, false,
                    -1, null, powerCeil, "less",
                    true, false, false, null, null, null, null, false, null, false);
            List<ForwardTarget> bzTs = selectTargets(ctx, 1, true, false, false,
                    null, null, "in your opponent's Break Zone", true,
                    -1, null, -1, null,
                    true, false, false, null, null, null, null, false, null, false);
            List<ForwardTarget> all = new ArrayList<>(fieldTs);
            all.addAll(bzTs);
            sortedByIdxDesc(all, true) .forEach(t -> ctx.removeTargetFromGame(t));
            sortedByIdxDesc(all, false).forEach(t -> ctx.removeTargetFromGame(t));
        };
    }
    /**
     * Parses "Until end of turn, &lt;subject&gt; gains +N power and
     * 'When &lt;subject&gt; attacks, &lt;effect&gt;.'"
     * Applies the power boost and registers a temporary one-turn attack trigger.
     * Must be tried before {@link #tryParseStandalonePowerBoostUntil} because it is more specific.
     */
    static Consumer<GameContext> tryParseStandalonePowerBoostAndAttackTrigger(
            String text, CardData source) {
        if (source == null) return null;
        Matcher m = STANDALONE_POWER_BOOST_AND_ATTACK_TRIGGER.matcher(text);
        if (!m.find()) return null;
        String subject = m.group("subject").trim();
        if (!subject.equalsIgnoreCase(source.name())) return null;
        int boost = Integer.parseInt(m.group("amount"));
        String attackEffectText = m.group("attackEffect").trim();
        Consumer<GameContext> attackEffect = parse(attackEffectText, source);
        if (attackEffect == null) return null;
        return ctx -> {
            ctx.logEntry(source.name() + " — +" + boost + " power until end of turn"
                    + " and gains 'When attacks: " + attackEffectText + "'");
            ctx.boostSourceForward(source, boost, EnumSet.noneOf(CardData.Trait.class));
            ctx.addTempAttackTrigger(source, attackEffect);
        };
    }
    /**
     * Parses "Until the end of the turn, [Name] gains +N power and [Name]/it cannot be chosen
     * by your opponent's Summons/abilities." (Quina) — applies an EOT power boost plus
     * opponent-targeting protection to the source card.
     */
    static Consumer<GameContext> tryParseStandalonePowerBoostAndCannotBeChosen(
            String text, CardData source) {
        if (source == null) return null;
        Matcher m = STANDALONE_POWER_BOOST_AND_CANNOT_BE_CHOSEN.matcher(text);
        if (!m.find()) return null;
        if (!m.group("subject").trim().equalsIgnoreCase(source.name())) return null;
        String second = m.group("subject2").trim();
        if (!second.equalsIgnoreCase(source.name()) && !second.equalsIgnoreCase("it")) return null;
        int boost = Integer.parseInt(m.group("amount"));
        String scope = m.group("scope").toLowerCase(java.util.Locale.ROOT);
        boolean bySummons   = scope.contains("summon");
        boolean byAbilities = scope.contains("abilit");
        String scopeDesc = bySummons && byAbilities ? "Summons or abilities"
                         : bySummons ? "Summons" : "abilities";
        return ctx -> {
            ctx.logEntry(source.name() + " — +" + boost + " power and cannot be chosen by opponent's "
                    + scopeDesc + " until end of turn");
            ctx.boostSourceForward(source, boost, EnumSet.noneOf(CardData.Trait.class));
            ctx.shieldNamedCardCannotBeChosen(source.name(), bySummons, byAbilities);
        };
    }
    static Consumer<GameContext> tryParseStandalonePowerBoostUntil(
            String text, CardData source) {
        if (source == null) return null;
        Matcher m = STANDALONE_POWER_BOOST_UNTIL.matcher(text);
        if (!m.find()) return null;
        String subject = m.group("subject").trim();
        if (subject.equalsIgnoreCase("it") || subject.equalsIgnoreCase("they")) return null;
        if (!subject.equalsIgnoreCase(source.name())) return null;
        int boost = m.group("amount") != null ? Integer.parseInt(m.group("amount")) : 0;
        EnumSet<CardData.Trait> traits = parseTraits(m.group("traits"));
        if (boost == 0 && traits.isEmpty()) return null;
        String logSuffix = boostLogSuffix(boost, traits);
        return ctx -> {
            ctx.logEntry(source.name() + logSuffix);
            ctx.boostSourceForward(source, boost, traits);
        };
    }
    /**
     * Parses "Double the power of &lt;cardName&gt; until end of turn" as a standalone self-buff.
     * The subject must match {@code source.name()} (case-insensitive).
     */
    static Consumer<GameContext> tryParseStandaloneDoublePowerUntil(
            String text, CardData source) {
        if (source == null) return null;
        Matcher m = STANDALONE_DOUBLE_POWER_UNTIL.matcher(text);
        if (!m.find()) return null;
        String subject = m.group("subject").trim();
        if (!subject.equalsIgnoreCase(source.name())) return null;
        return ctx -> {
            ctx.logEntry(source.name() + " — power doubled until end of turn");
            ctx.doubleSourceForwardPower(source, EnumSet.noneOf(CardData.Trait.class));
        };
    }
    /**
     * Parses "Until the end of the turn, &lt;cardName&gt; doubles its power [and gains traits]",
     * and the multi-attack sentence 1-128R Gilgamesh prints after it.
     * Subject must match {@code source.name()}.
     *
     * <p>The trailing sentence is read here rather than left to the chain because this parser
     * claims the whole ability: its traits group stops at the first "." and everything past that
     * was silently discarded, so Gilgamesh doubled and gained its keywords but never got the second
     * attack that is the point of the ability.
     *
     * <p>Only one specific, anchored sentence is looked for, not "whatever follows dispatched
     * through {@code parse()}". The traits group stops at the first "." <em>wherever it is</em>,
     * including inside a quotation: on 17-084C Lorenzo the tail is the back half of a quoted
     * ability ({@code Add it to your hand."}), and handing that to the general chain would resolve
     * a fragment as if it were an effect of its own. Lorenzo's quoted grant is still dropped, which
     * is wrong but visibly so.
     */
    static Consumer<GameContext> tryParseStandaloneDoublesItsPowerUntil(
            String text, CardData source) {
        if (source == null) return null;
        Matcher m = STANDALONE_DOUBLES_ITS_POWER_UNTIL.matcher(text);
        if (!m.find()) return null;
        String subject = m.group("subject").trim();
        if (!subject.equalsIgnoreCase(source.name())) return null;
        EnumSet<CardData.Trait> traits = parseTraits(m.group("traits"));

        // Restrictions are stripped, not parsed: "You can use this ability only during your turn."
        // is carried as a flag on the ability and gated at activation.
        String tail = stripRestrictionSentences(
                text.substring(m.end()).replaceFirst("^\\s*[.!]\\s*", "").trim()).trim();
        int attacks = 0;
        Matcher am = SELF_CAN_ATTACK_N_TIMES_THIS_TURN.matcher(tail);
        if (am.matches() && am.group("subj").trim().equalsIgnoreCase(source.name()))
            attacks = am.group("count") != null ? Integer.parseInt(am.group("count")) : 2;
        final int maxAttacks = attacks;

        String trailPart = traitNamesOnly(traits);
        String logSuffix = " — power doubled" + (trailPart.isEmpty() ? "" : ", gains " + trailPart) + " until end of turn";
        return ctx -> {
            ctx.logEntry(source.name() + logSuffix);
            ctx.doubleSourceForwardPower(source, traits);
            if (maxAttacks > 0) {
                ctx.logEntry(source.name() + " can attack " + maxAttacks + " times this turn");
                ctx.grantMaxAttacksUntilEndOfTurn(source, maxAttacks);
            }
        };
    }
    /**
     * Parses "At the beginning of your next turn's Main Phase 1 and until the end of the same
     * turn, &lt;cardName&gt;'s power will double." — defers doubling to the start of next Main Phase 1.
     * Subject must match {@code source.name()}.
     */
    static Consumer<GameContext> tryParseStandaloneDoublePowerMainPhaseNextTurn(
            String text, CardData source) {
        if (source == null) return null;
        Matcher m = STANDALONE_DOUBLE_POWER_MAIN_PHASE_NEXT_TURN.matcher(text);
        if (!m.find()) return null;
        String subject = m.group("subject").trim();
        if (!subject.equalsIgnoreCase(source.name())) return null;
        return ctx -> {
            ctx.logEntry(source.name() + " — power will double at the start of next Main Phase 1");
            ctx.addPendingMainPhase1Effect(innerCtx -> {
                innerCtx.logEntry(source.name() + " — power doubled until end of turn (deferred)");
                innerCtx.doubleSourceForwardPower(source, EnumSet.noneOf(CardData.Trait.class));
            });
        };
    }
    /**
     * Parses "Until the end of the turn, &lt;cardName&gt; loses [N power] [and traits]" as a
     * standalone self-debuff on the source card.  Pronoun subjects are ignored here.
     */
    static Consumer<GameContext> tryParseStandalonePowerReduceUntil(
            String text, CardData source) {
        if (source == null) return null;
        Matcher m = STANDALONE_POWER_REDUCE_UNTIL.matcher(text);
        if (!m.find()) return null;
        String subject = m.group("subject").trim();
        if (subject.equalsIgnoreCase("it") || subject.equalsIgnoreCase("they")) return null;
        if (!subject.equalsIgnoreCase(source.name())) return null;
        String amountStr = m.group("amount");
        int reduction = amountStr != null ? Integer.parseInt(amountStr) : 0;
        EnumSet<CardData.Trait> traits = parseTraits(m.group("traits"));
        String logSuffix = reduceLogSuffix(reduction, traits);
        return ctx -> {
            ctx.logEntry(source.name() + logSuffix);
            ctx.reduceSourceForward(source, reduction, traits);
        };
    }
    /**
     * Parses "&lt;cardName&gt; gains +N power." (no duration clause) as a permanent passive
     * field-ability self-boost.  Subject must match {@code source.name()}.
     */
    static Consumer<GameContext> tryParseFieldSelfPowerBoost(String text, CardData source) {
        if (source == null) return null;
        Matcher m = FIELD_SELF_POWER_BOOST.matcher(text);
        if (!m.find()) return null;
        String subject = m.group("subject").trim();
        if (!subject.equalsIgnoreCase(source.name())) return null;
        int boost = Integer.parseInt(m.group("amount"));
        EnumSet<CardData.Trait> traits = parseTraits(m.group("traits"));
        return ctx -> {
            String traitDesc = traits.isEmpty() ? "" : " and " + traitNamesOnly(traits);
            ctx.logEntry(source.name() + " — Gain +" + boost + " power" + traitDesc + " (field)");
            ctx.boostSourceForward(source, boost, traits);
        };
    }
    static Consumer<GameContext> tryParseUntilEotGainsPowerTraitsAndQuoted(String text, CardData source) {
        if (source == null) return null;
        Matcher m = UNTIL_EOT_GAINS_POWER_TRAITS_AND_QUOTED.matcher(text.trim());
        if (!m.matches()) return null;
        if (!m.group("subject").trim().equalsIgnoreCase(source.name())) return null;
        Consumer<GameContext> abilityGrant = grantedSelfFieldAbilityEffect(m.group("quoted").trim(), source);
        if (abilityGrant == null) return null;
        String boosts = m.group("boosts").trim();
        Matcher pm = POWER_AMOUNT_PLUS.matcher(boosts);
        int amount = pm.find() ? Integer.parseInt(pm.group(1)) : 0;
        EnumSet<CardData.Trait> traits = parseTraits(boosts);
        return ctx -> {
            if (amount > 0 || !traits.isEmpty()) ctx.boostSourceForward(source, amount, traits);
            abilityGrant.accept(ctx);
        };
    }
    /**
     * Shuyin: "Choose 1 Forward [control?] with a power inferior to [source]'s. [followup]"
     * The power ceiling is computed at runtime as sourcePower − 1000 (strictly inferior,
     * and FFTCG powers step in multiples of 1000).
     */
    static Consumer<GameContext> tryParseChooseFwdPowerInferiorToSource(String text, CardData source) {
        if (source == null) return null;
        Matcher m = CHOOSE_FWD_POWER_INFERIOR_TO_SOURCE.matcher(text);
        if (!m.find()) return null;
        if (!m.group("sourcename").trim().equalsIgnoreCase(source.name())) return null;
        String control   = m.group("control");
        boolean oppOnly  = control != null && !control.equalsIgnoreCase("you control");
        boolean selfOnly = "you control".equalsIgnoreCase(control);
        String followupText = m.group("followup").trim();
        // Detect gain-control-EOT as the followup (handles "this Forward" phrasing)
        boolean gainControlEot = followupText.toLowerCase().contains("gain control")
                && followupText.toLowerCase().contains("end of");
        Consumer<GameContext> parsedFollowup = gainControlEot ? null : parse(followupText, source);
        if (!gainControlEot && parsedFollowup == null) return null;
        return ctx -> {
            int sp = ctx.fieldForwardPowerByName(source.name());
            if (sp <= 0) sp = source.power();
            int powerCeiling = sp - 1000;
            ctx.logEntry("Choose 1 Forward with power < " + sp);
            if (powerCeiling <= 0) { ctx.logEntry("No eligible targets — source power too low."); return; }
            List<ForwardTarget> ts = ctx.selectCharacters(1, false, oppOnly, selfOnly,
                    null, null, -1, null, powerCeiling, "less",
                    true, false, false, null, null, null, null, false, null, false);
            if (gainControlEot) ts.forEach(t -> ctx.gainControlOfForward(t, "endOfTurn", true));
            else { ctx.recordChosenTargets(ts); parsedFollowup.accept(ctx); }
        };
    }
    /**
     * Alphinaud: "Dull all the Forwards with a power equal or inferior to [source]'s
     * opponent controls."
     */
    static Consumer<GameContext> tryParseDullAllOppFwdsPowerLeSource(String text, CardData source) {
        if (source == null) return null;
        Matcher m = DULL_ALL_OPP_FWDS_POWER_LE_SOURCE.matcher(text);
        if (!m.find()) return null;
        if (!m.group("sourcename").trim().equalsIgnoreCase(source.name())) return null;
        return ctx -> ctx.dullOpponentForwardsByPowerAtMost(source);
    }
    /**
     * Parses "it [gains +N power] [traits] until end of turn" as a standalone boost applied to
     * {@code source}. Used when "it" refers to the source card — e.g. in watcher attack abilities
     * where the source is the attacking Forward, not the card that owns the ability.
     */
    static Consumer<GameContext> tryParseStandaloneItPowerBoostUntil(String text, CardData source) {
        if (source == null) return null;
        Matcher m = SELF_POWER_BOOST.matcher(text);
        if (!m.find()) return null;
        String subject = m.group("selfsubject").trim();
        if (!subject.equalsIgnoreCase("it") && !subject.equalsIgnoreCase("they")) return null;
        int boost = m.group("selfamount") != null ? Integer.parseInt(m.group("selfamount")) : 0;
        EnumSet<CardData.Trait> traits = parseTraits(m.group("selftraits"));
        if (boost == 0 && traits.isEmpty()) return null;
        String logSuffix = boostLogSuffix(boost, traits);
        return ctx -> {
            ctx.logEntry(source.name() + logSuffix);
            ctx.boostSourceForward(source, boost, traits);
        };
    }
    /**
     * Parses "&lt;cardName&gt; gains [+N power] [, traits] until end of turn" as a standalone
     * self-boost on the source card (standard order, no "Until" prefix).
     * Pronoun subjects ("it", "they") are skipped — they are followup pronouns.
     */
    /**
     * Parses the duration-first wording of a self grant — "Until the end of the turn [Self] gains
     * Brave." (Tidus 1-163L). The trailing-duration order is
     * {@link #tryParseStandaloneSelfBoost}'s.
     */
    static Consumer<GameContext> tryParseSelfBoostEotPrefix(String text, CardData source) {
        if (source == null) return null;
        Matcher m = SELF_BOOST_EOT_PREFIX.matcher(text.trim());
        if (!m.matches()) return null;
        if (!m.group("subject").trim().equalsIgnoreCase(source.name())) return null;
        int boost = m.group("amount") != null ? Integer.parseInt(m.group("amount")) : 0;
        EnumSet<CardData.Trait> traits = parseTraits(m.group("traits"));
        // "gains" with neither a power amount nor a keyword grants nothing — leave such a sentence
        // to whatever parser owns the rest of its wording.
        if (boost == 0 && traits.isEmpty()) return null;
        String logSuffix = boostLogSuffix(boost, traits);
        return ctx -> {
            ctx.logEntry(source.name() + logSuffix);
            ctx.boostSourceForward(source, boost, traits);
        };
    }

    /**
     * Parses "[Self] can attack as many times as your points of damage this turn." (Tidus 1-163L).
     *
     * <p>The count is read when the ability resolves, not tracked live: this is a resolved special
     * ability, so it grants a permission of a fixed size rather than one that keeps pace with
     * damage taken later in the turn.
     */
    static Consumer<GameContext> tryParseSelfAttacksPerOwnDamage(String text, CardData source) {
        if (source == null) return null;
        Matcher m = SELF_ATTACKS_PER_OWN_DAMAGE.matcher(text.trim());
        if (!m.matches()) return null;
        if (!m.group("subject").trim().equalsIgnoreCase(source.name())) return null;
        return ctx -> {
            int damage = ctx.selfDamageCount();
            ctx.logEntry(source.name() + " can attack " + damage
                    + " time(s) this turn (your points of damage)");
            ctx.grantMaxAttacksUntilEndOfTurn(source, damage);
        };
    }

    static Consumer<GameContext> tryParseStandaloneSelfBoost(String text, CardData source) {
        if (source == null) return null;
        Matcher m = SELF_POWER_BOOST.matcher(text);
        if (!m.find()) return null;
        String subject = m.group("selfsubject").trim();
        if (subject.equalsIgnoreCase("it") || subject.equalsIgnoreCase("they")) return null;
        if (!subject.equalsIgnoreCase(source.name())) return null;
        int boost = m.group("selfamount") != null ? Integer.parseInt(m.group("selfamount")) : 0;
        EnumSet<CardData.Trait> traits = parseTraits(m.group("selftraits"));
        String logSuffix = boostLogSuffix(boost, traits);
        return ctx -> {
            ctx.logEntry(source.name() + logSuffix);
            ctx.boostSourceForward(source, boost, traits);
        };
    }
    /**
     * Parses "if you have N or more cards in your hand, [subject] gains [traits/power] until end of turn"
     * with an optional higher-threshold power boost clause for the same subject.
     */
    static Consumer<GameContext> tryParseIfHandSizeSelfBoost(String text, CardData source) {
        if (source == null) return null;
        Matcher m = IF_HAND_SIZE_SELF_BOOST.matcher(text.trim());
        if (!m.find()) return null;
        String subject = m.group("subject").trim();
        if (!subject.equalsIgnoreCase(source.name())) return null;
        int min1 = Integer.parseInt(m.group("min1"));
        int amount1 = m.group("amount1") != null ? Integer.parseInt(m.group("amount1")) : 0;
        EnumSet<CardData.Trait> traits1 = parseTraits(m.group("traits1"));
        if (amount1 == 0 && traits1.isEmpty()) return null;
        int min2   = m.group("min2")   != null ? Integer.parseInt(m.group("min2"))   : -1;
        int amount2 = m.group("amount2") != null ? Integer.parseInt(m.group("amount2")) : 0;
        String logSuffix1 = boostLogSuffix(amount1, traits1);
        String logSuffix2 = min2 > 0 ? boostLogSuffix(amount2, EnumSet.noneOf(CardData.Trait.class)) : "";
        return ctx -> {
            int handSize = ctx.yourHandSize();
            if (handSize >= min1) {
                ctx.logEntry(source.name() + logSuffix1 + " (hand ≥ " + min1 + ")");
                ctx.boostSourceForward(source, amount1, traits1);
            }
            if (min2 > 0 && handSize >= min2) {
                ctx.logEntry(source.name() + logSuffix2 + " (hand ≥ " + min2 + ")");
                ctx.boostSourceForward(source, amount2, EnumSet.noneOf(CardData.Trait.class));
            }
        };
    }
    static Consumer<GameContext> tryParseStandaloneSelfBoostForEachCrystal(String text, CardData source) {
        if (source == null) return null;
        Matcher m = SELF_POWER_BOOST_FOR_EACH_CRYSTAL.matcher(text);
        if (!m.find()) return null;
        String subject = m.group("subject").trim();
        if (!subject.equalsIgnoreCase(source.name())) return null;
        int perCrystal = Integer.parseInt(m.group("amount"));
        return ctx -> {
            int n = ctx.crystalCount();
            int boost = perCrystal * n;
            ctx.logEntry(source.name() + " gains +" + boost + " power (" + perCrystal + "×" + n + " 《C》) until end of turn");
            ctx.boostSourceForward(source, boost, EnumSet.noneOf(CardData.Trait.class));
        };
    }
    /**
     * Parses the self-targeted "gains +N power for each [Element | Category X] [Type] you control"
     * boost — 19-136S Noel. The multiplier is read when the ability resolves, not when it is
     * parsed, so a Character entering between the trigger and the resolution counts.
     */
    static Consumer<GameContext> tryParseStandaloneSelfBoostForEachControlled(String text, CardData source) {
        if (source == null) return null;
        Matcher m = SELF_POWER_BOOST_FOR_EACH_CONTROLLED.matcher(text.trim());
        if (!m.find()) return null;
        // Which alternative matched decides which group set carries the values.
        boolean untilFirst = m.group("subject") != null;
        String subject  = (untilFirst ? m.group("subject")  : m.group("subject2")).trim();
        if (!subject.equalsIgnoreCase(source.name())) return null;
        int    perUnit  = Integer.parseInt(untilFirst ? m.group("amount") : m.group("amount2"));
        String element  = untilFirst ? m.group("element")  : m.group("element2");
        String category = untilFirst ? m.group("category") : m.group("category2");
        String type     = normalizeCountedType(untilFirst ? m.group("chartype") : m.group("chartype2"));
        String label    = (element != null ? element + " " : "")
                        + (category != null ? "Category " + category + " " : "") + type;
        return ctx -> {
            int n = countControlled(ctx, element, category, type);
            int boost = perUnit * n;
            ctx.logEntry(source.name() + " gains +" + boost + " power ("
                    + perUnit + "×" + n + " " + label + ") until end of turn");
            ctx.boostSourceForward(source, boost, EnumSet.noneOf(CardData.Trait.class));
        };
    }

    /**
     * Parses the self-targeted "gains +N power for each different Element among [Type] you control"
     * boost — 16-002H Ace. The multiplier counts distinct Elements, not cards, so a single
     * Fire/Ice Character is worth 2; see {@link GameContext#selfDistinctElementCount}.
     */
    static Consumer<GameContext> tryParseStandaloneSelfBoostForEachDistinctElement(String text, CardData source) {
        if (source == null) return null;
        Matcher m = SELF_POWER_BOOST_FOR_EACH_DISTINCT_ELEMENT.matcher(text.trim());
        if (!m.find()) return null;
        // Which alternative matched decides which group set carries the values.
        boolean untilFirst = m.group("subject") != null;
        String subject = (untilFirst ? m.group("subject") : m.group("subject2")).trim();
        if (!subject.equalsIgnoreCase(source.name())) return null;
        int    perElement = Integer.parseInt(untilFirst ? m.group("amount") : m.group("amount2"));
        String type       = normalizeCountedType(untilFirst ? m.group("chartype") : m.group("chartype2"));
        boolean all     = type.equals("Character");
        boolean inclFwd = all || type.equals("Forward");
        boolean inclBkp = all || type.equals("Backup");
        boolean inclMon = all || type.equals("Monster");
        return ctx -> {
            int n = ctx.selfDistinctElementCount(inclFwd, inclBkp, inclMon);
            int boost = perElement * n;
            ctx.logEntry(source.name() + " gains +" + boost + " power ("
                    + perElement + "×" + n + " different Element(s) among " + type + "s you control) until end of turn");
            ctx.boostSourceForward(source, boost, EnumSet.noneOf(CardData.Trait.class));
        };
    }

    /** "Characters"/"Forwards" → the singular form the counting primitives take. */
    private static String normalizeCountedType(String raw) {
        String t = raw.trim().toLowerCase(Locale.ROOT).replaceAll("s$", "");
        return Character.toUpperCase(t.charAt(0)) + t.substring(1);
    }

    /**
     * Counts the active player's field cards matching an optional element, an optional category and
     * a type. Each qualifier has its own primitive on {@link GameContext}; no printing in the corpus
     * carries both an element and a category, so the two are read as alternatives.
     */
    private static int countControlled(GameContext ctx, String element, String category, String type) {
        if (category != null) return ctx.ownFieldCountByCategory(category, type);
        if (element  == null) return ctx.ownFieldCount(type);
        boolean all = type.equals("Character");
        return ctx.selfFieldCount(element,
                all || type.equals("Forward"),
                all || type.equals("Backup"),
                all || type.equals("Monster"));
    }

    /** Parses "[subject] gains +N power until the end of the turn and activate [name]." */
    static Consumer<GameContext> tryParseSelfPowerBoostAndActivate(String text, CardData source) {
        if (source == null) return null;
        Matcher m = SELF_POWER_BOOST_AND_ACTIVATE.matcher(text.trim());
        if (!m.find()) return null;
        String subject = m.group("subject").trim();
        if (!subject.equalsIgnoreCase(source.name())) return null;
        int boost = Integer.parseInt(m.group("amount"));
        String activateName = m.group("activateName").trim();
        return ctx -> {
            // boostSourceForward logs the boost itself (and logs the suppressed case instead when
            // the opponent is blocking power gains), so announcing it here just duplicates the line.
            ctx.boostSourceForward(source, boost, EnumSet.noneOf(CardData.Trait.class));
            ctx.logEntry("Effect: Activate " + activateName);
            List<ForwardTarget> ts = ctx.selectCharacters(
                    1, false, false, true, null, null, -1, null, -1, null,
                    true, true, true, null, activateName, null, null, false, null, false);
            ts.forEach(ctx::activateTarget);
        };
    }
    /**
     * Parses "[CardName]'s power becomes the same as that Forward's power until the end of the turn."
     * Sets the source card's power to the power of the Forward most recently removed from the game.
     */
    static Consumer<GameContext> tryParseSourcePowerBecomesRemovedForwardPower(
            String text, CardData source) {
        if (source == null) return null;
        Matcher m = SOURCE_POWER_BECOMES_SAME_AS_REMOVED_FORWARD.matcher(text.trim());
        if (!m.matches()) return null;
        if (!m.group("name").trim().equalsIgnoreCase(source.name())) return null;
        return ctx -> {
            int power = ctx.lastRemovedFromGameCardPower();
            ctx.logEntry(source.name() + " — base power becomes " + power + " (removed Forward's power) until end of turn");
            ctx.setSourceForwardBasePower(source, power, EnumSet.noneOf(CardData.Trait.class));
        };
    }
    /**
     * Parses "[CardName]'s power becomes the same as your opponent's weakest Forward until the
     * end of the turn." Sets the source card's power to the lowest effective power among the
     * opponent's Forwards on the field.
     */
    static Consumer<GameContext> tryParseSourcePowerBecomesOpponentWeakestForward(
            String text, CardData source) {
        if (source == null) return null;
        Matcher m = SOURCE_POWER_BECOMES_OPPONENT_WEAKEST_FORWARD.matcher(text.trim());
        if (!m.matches()) return null;
        if (!m.group("name").trim().equalsIgnoreCase(source.name())) return null;
        return ctx -> {
            int power = ctx.opponentLowestForwardPower();
            ctx.logEntry(source.name() + " — base power becomes " + power + " (opponent's weakest Forward) until end of turn");
            ctx.setSourceForwardBasePower(source, power, EnumSet.noneOf(CardData.Trait.class));
        };
    }
    /**
     * Parses "Until the end of the turn, [CardName] gains [traits] and [CardName]'s power becomes N."
     * (and the trait-less "Until the end of the turn, [CardName]'s power becomes N.").  The power
     * clause replaces the source card's base power rather than its effective power, so temporary
     * boosts and reductions — whether already applied or applied later this turn — stack on top of it.
     */
    static Consumer<GameContext> tryParseSelfBasePowerBecomesUntil(String text, CardData source) {
        if (source == null) return null;
        Matcher m = STANDALONE_SELF_BASE_POWER_BECOMES_UNTIL.matcher(text.trim());
        if (!m.matches()) return null;
        if (!m.group("powersubject").trim().equalsIgnoreCase(source.name())) return null;
        String subject = m.group("subject");
        if (subject != null && !subject.trim().equalsIgnoreCase(source.name())) return null;
        int power = Integer.parseInt(m.group("power"));
        EnumSet<CardData.Trait> traits = parseTraits(m.group("traits"));
        return ctx -> ctx.setSourceForwardBasePower(source, power, traits);
    }
    static Consumer<GameContext> tryParseOppFwdsCannotBlockInferiorPower(String text) {
        if (!OPP_FWDS_CANNOT_BLOCK_INFERIOR_POWER_THIS_TURN.matcher(text).matches()) return null;
        return ctx -> ctx.setOppForwardsCannotBlockInferiorPowerThisTurn();
    }
    static Consumer<GameContext> tryParseOppFwdPowerBoostSuppressedThisTurn(String text) {
        if (!OPP_FWD_POWER_BOOST_SUPPRESSED_THIS_TURN.matcher(text).matches()) return null;
        return ctx -> ctx.setOppFwdPowerBoostSuppressedThisTurn();
    }
    static Consumer<GameContext> tryParseOppFwdsLosePowerPerPlayCost(String text) {
        Matcher m = OPP_FWDS_LOSE_POWER_PER_PLAY_COST.matcher(text);
        if (!m.find()) return null;
        int powerPerCp = Integer.parseInt(m.group("amount"));
        return ctx -> {
            ctx.logEntry("Effect: All opponent Forwards lose " + powerPerCp + "×cost power until end of turn");
            ctx.applyOppFwdsCostScaledPowerDebuff(powerPerCp);
        };
    }
    static Consumer<GameContext> tryParseDiscardConditionalSelfBoostInstead(String text, CardData source, int xValue) {
        Matcher m = DISCARD_CONDITIONAL_SELF_BOOST_INSTEAD.matcher(text.trim());
        if (!m.matches()) return null;
        Consumer<GameContext> primary = parse(m.group("primary").trim(), source, xValue);
        Consumer<GameContext> alt     = parse(m.group("alt").trim(), source, xValue);
        if (primary == null || alt == null) return null;
        final String needName = m.group("name").trim();
        return ctx -> {
            String dn = ctx.lastDiscardedCostCardName();
            if (dn != null && dn.equalsIgnoreCase(needName)) {
                ctx.logEntry("Discard conditional: discarded Card Name " + needName + " — applying the \"instead\" effect");
                alt.accept(ctx);
            } else {
                primary.accept(ctx);
            }
        };
    }
    /**
     * Recognises passive field grants applied by the engine via {@link CardData#fieldPowerGrants()};
     * returns a no-op lambda so that {@link #parse} does not report these as unrecognised.
     */
    static Consumer<GameContext> tryParseFieldPowerGrantPassive(String text) {
        String trimmed = text.trim();
        if (FIELD_GRANT_BARE_PASSIVE.matcher(trimmed).matches()
                || FIELD_GRANT_JOB_CAT_PASSIVE.matcher(trimmed).matches()
                || FIELD_OPPONENT_DEBUFF_PASSIVE.matcher(trimmed).matches()
                || FIELD_GRANT_BZ_COND_PASSIVE.matcher(trimmed).find()
                || FIELD_GRANT_DIFF_ELEM_COND_PASSIVE.matcher(trimmed).find()) {
            return ctx -> { /* passive field grant — applied via fieldPowerGrants() */ };
        }
        return null;
    }
    /**
     * Parses "all Forwards in that party gain/lose +N power until end of turn." — the party-attack
     * followup that boosts every Forward in the party that just formed and attacked.
     */
    static Consumer<GameContext> tryParsePartyForwardsPowerBoost(String text) {
        Matcher m = PARTY_FORWARDS_POWER_BOOST_PATTERN.matcher(text);
        if (!m.find()) return null;
        boolean isLose = m.group("verb").toLowerCase().startsWith("lose");
        int amount = Integer.parseInt(m.group("amount")) * (isLose ? -1 : 1);
        return ctx -> {
            ctx.logEntry("Effect: All Forwards in that party " + (isLose ? "-" : "+") + Math.abs(amount)
                    + " power until end of turn");
            ctx.applyCurrentPartyForwardsPowerBoost(amount);
        };
    }
    /**
     * Parses "All [the] [element] [targets] [of cost N] [control] gain +N power until end of turn."
     */
    static Consumer<GameContext> tryParseAllFieldPowerBoost(String text) {
        Matcher m = ALL_FIELD_POWER_BOOST_PATTERN.matcher(text);
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

        boolean isLose = m.group("verb").toLowerCase().startsWith("lose");
        int amount = Integer.parseInt(m.group("amount")) * (isLose ? -1 : 1);

        String elemLabel    = element != null ? element + " " : "";
        String catLabel     = category != null ? "Category " + category + " " : "";
        String costLabel    = costVal >= 0 ? " of cost " + costVal + (costCmp != null ? " or " + costCmp : "") : "";
        String controlLabel = opponentOnly ? " (opponent)" : selfOnly ? " (yours)" : "";
        String change       = isLose ? "-" + Math.abs(amount) : "+" + amount;
        String excludeName = m.group("excludename") != null ? m.group("excludename").trim() : null;
        String excludeLabel = excludeName != null ? " other than " + excludeName : "";

        String trailingRaw = text.substring(m.end()).trim().replaceAll("^[.!,]+\\s*", "").trim();
        Consumer<GameContext> secondary = trailingRaw.isEmpty() ? null : parse(trailingRaw, null);

        String logMsg = "All " + elemLabel + catLabel + targets + costLabel + excludeLabel + controlLabel
                + " " + change + " power until end of turn";

        return ctx -> {
            ctx.logEntry("Effect: " + logMsg);
            ctx.applyMassFieldPowerBoost(amount, inclForwards, inclMonsters,
                    opponentOnly, selfOnly, element, costVal, costCmp, category, excludeName);
            if (secondary != null) secondary.accept(ctx);
        };
    }
    static Consumer<GameContext> tryParseAllForwardsSameElementAsNamedPowerBoost(String text) {
        Matcher m = ALL_FORWARDS_SAME_ELEMENT_AS_NAMED_POWER_BOOST.matcher(text);
        if (!m.find()) return null;
        String name    = m.group("name").trim();
        boolean isLose = m.group("verb").toLowerCase().startsWith("lose");
        int amount     = Integer.parseInt(m.group("amount")) * (isLose ? -1 : 1);
        String control = m.group("control");
        boolean opponentOnly = control != null && !control.toLowerCase().contains("you control");
        boolean selfOnly     = control != null &&  control.toLowerCase().contains("you control");
        return ctx -> {
            ctx.logEntry("Effect: All Forwards same element as " + name
                    + (selfOnly ? " (yours)" : opponentOnly ? " (opponent's)" : "")
                    + " " + (isLose ? "-" : "+") + Math.abs(amount) + " power until end of turn");
            ctx.allForwardsSameElementAsNamedGainPowerUntilEOT(name, amount, opponentOnly, selfOnly);
        };
    }
    /**
     * Parses "All Job X and Card Name Y [you control | opponent controls] gain +N power
     * until end of turn." — matches cards that have Job X OR are Card Name Y.
     */
    static Consumer<GameContext> tryParseAllFieldJobCardNamePowerBoost(String text) {
        Matcher m = ALL_FIELD_JOB_CARDNAME_POWER_BOOST_PATTERN.matcher(text);
        if (!m.find()) return null;

        String job      = m.group("job").trim();
        String cardName = m.group("cardname").trim();
        String control  = m.group("control");
        boolean opponentOnly = control != null && !control.toLowerCase().contains("you control");
        boolean selfOnly     = control != null &&  control.toLowerCase().contains("you control");

        boolean isLose = m.group("verb").toLowerCase().startsWith("lose");
        int amount = Integer.parseInt(m.group("amount")) * (isLose ? -1 : 1);
        String change = isLose ? "-" + Math.abs(amount) : "+" + amount;
        String controlLabel = opponentOnly ? " (opponent)" : selfOnly ? " (yours)" : "";
        String logMsg = "All Job " + job + " and Card Name " + cardName + controlLabel
                + " " + change + " power until end of turn";

        String trailingRaw = text.substring(m.end()).trim().replaceAll("^[.!,]+\\s*", "").trim();
        Consumer<GameContext> secondary = trailingRaw.isEmpty() ? null : parse(trailingRaw, null);

        return ctx -> {
            ctx.logEntry("Effect: " + logMsg);
            ctx.applyMassFieldJobCardNamePowerBoost(amount, true, true,
                    opponentOnly, selfOnly, job, cardName);
            if (secondary != null) secondary.accept(ctx);
        };
    }
    /**
     * Parses "[The] Card Name X [Forward] and Card Name Y [Forward] [you control] gain +N power
     * until end of turn." — boosts both named cards (OR logic via pipe-separated filter).
     */
    static Consumer<GameContext> tryParseTwoCardNamesPowerBoost(String text) {
        Matcher m = TWO_CARD_NAMES_POWER_BOOST_PATTERN.matcher(text.trim());
        if (!m.find()) return null;

        String name1   = m.group("name1").trim();
        String name2   = m.group("name2").trim();
        String cardNameFilter = name1 + "|" + name2;
        String control = m.group("control");
        boolean opponentOnly = control != null && !control.toLowerCase().contains("you control");
        boolean selfOnly     = control != null &&  control.toLowerCase().contains("you control");
        boolean isLose = m.group("verb").toLowerCase().startsWith("lose");
        int amount = Integer.parseInt(m.group("amount")) * (isLose ? -1 : 1);
        String change = isLose ? "-" + Math.abs(amount) : "+" + amount;
        String controlLabel = opponentOnly ? " (opponent)" : selfOnly ? " (yours)" : "";
        String logMsg = "Card Name " + name1 + " and Card Name " + name2 + controlLabel
                + " " + change + " power until end of turn";

        return ctx -> {
            ctx.logEntry("Effect: " + logMsg);
            ctx.applyMassFieldJobCardNamePowerBoost(amount, true, true,
                    opponentOnly, selfOnly, null, cardNameFilter);
        };
    }
    /**
     * Parses "All [the] Job X Forwards [you control] gain +N power until end of turn."
     */
    static Consumer<GameContext> tryParseAllFieldJobPowerBoost(String text) {
        Matcher m = ALL_FIELD_JOB_POWER_BOOST_PATTERN.matcher(text);
        if (!m.find()) return null;

        String job      = m.group("job").trim();
        String targets  = m.group("targets");
        String tgtLower = targets.toLowerCase();
        boolean inclForwards = tgtLower.contains("forward") || tgtLower.contains("character");
        boolean inclMonsters = tgtLower.contains("monster") || tgtLower.contains("character");

        String control       = m.group("control");
        boolean opponentOnly = control != null && !control.toLowerCase().contains("you control");
        boolean selfOnly     = control != null &&  control.toLowerCase().contains("you control");

        boolean isLose = m.group("verb").toLowerCase().startsWith("lose");
        int amount = Integer.parseInt(m.group("amount")) * (isLose ? -1 : 1);

        String controlLabel = opponentOnly ? " (opponent)" : selfOnly ? " (yours)" : "";
        String change       = isLose ? "-" + Math.abs(amount) : "+" + amount;
        String logMsg       = "All Job " + job + " " + targets + controlLabel + " " + change + " power until end of turn";

        String trailingRaw = text.substring(m.end()).trim().replaceAll("^[.!,]+\\s*", "").trim();
        Consumer<GameContext> secondary = trailingRaw.isEmpty() ? null : parse(trailingRaw, null);

        return ctx -> {
            ctx.logEntry("Effect: " + logMsg);
            ctx.applyMassFieldJobCardNamePowerBoost(amount, inclForwards, inclMonsters,
                    opponentOnly, selfOnly, job, null);
            if (secondary != null) secondary.accept(ctx);
        };
    }
    /**
     * Parses "All the Forwards you control gain "[ability]" until the end of the turn." — 23-049C
     * Ninja's replacement clause.
     *
     * <p>Deliberately narrow, like the choose followup it is the mass form of: only a quotation the
     * engine actually reads is claimed, so a grant of anything else keeps falling through the chain
     * and stays visibly unhandled rather than resolving as a silent no-op. Today that is the
     * cost-based block restriction and nothing else.
     */
    static Consumer<GameContext> tryParseAllOwnForwardsGainQuotedAbilityEot(String text) {
        Matcher m = ALL_OWN_FORWARDS_GAIN_QUOTED_ABILITY_EOT.matcher(text.trim());
        if (!m.matches()) return null;
        String granted = (m.group("granted") != null ? m.group("granted") : m.group("gq")).trim();
        int[] nb = grantedThisForwardCannotBeBlockedByCost(granted);
        if (nb == null) return null;
        final int     cost   = nb[0];
        final boolean isMore = nb[1] == 1;
        return ctx -> {
            ctx.logEntry("Effect: all Forwards you control gain \"" + granted + "\" until end of turn");
            ctx.grantOwnForwardsCannotBeBlockedByCost(cost, isMore);
        };
    }
    /**
     * Parses "Until end of turn, all [the] [element] [targets] [you control] gain [Keywords and]
     * +N power for each point of damage you have received." — 23-058C Dark Knight.
     *
     * <p>Must be tried ahead of {@link #tryParseUntilEotAllFieldPowerBoost}, which matches the
     * "+N power" prefix of this sentence under {@code find()} and would hand out the flat amount
     * with the multiplier silently dropped.
     */
    static Consumer<GameContext> tryParseUntilEotAllFieldPowerPerSelfDamage(String text) {
        Matcher m = UNTIL_EOT_ALL_FIELD_POWER_PER_SELF_DMG_PATTERN.matcher(text);
        if (!m.find()) return null;

        String element  = m.group("element");
        String targets  = m.group("targets");
        String tgtLower = targets.toLowerCase();
        boolean inclForwards = tgtLower.contains("forward") || tgtLower.contains("character");
        boolean inclMonsters = tgtLower.contains("monster") || tgtLower.contains("character");

        String control       = m.group("control");
        boolean opponentOnly = control != null && !control.toLowerCase().contains("you control");
        boolean selfOnly     = control != null &&  control.toLowerCase().contains("you control");

        int perUnit = Integer.parseInt(m.group("perunit"));
        EnumSet<CardData.Trait> traits = parseTraits(m.group("keywords"));

        String elemLabel    = element != null ? element + " " : "";
        String controlLabel = opponentOnly ? " (opponent)" : selfOnly ? " (yours)" : "";
        String traitStr     = traits.isEmpty() ? "" : " and " + traitNamesOnly(traits);

        return ctx -> {
            // The ability user's own damage zone, whichever seat they are in.
            int damage = ctx.selfDamageCount();
            int boost  = perUnit * damage;
            ctx.logEntry("Effect: Until EOT all " + elemLabel + targets + controlLabel
                    + " +" + perUnit + " power ×" + damage + " damage = +" + boost + " power" + traitStr);
            ctx.applyMassFieldPowerBoost(boost, inclForwards, inclMonsters,
                    opponentOnly, selfOnly, element, -1, null, null, null);
            if (!traits.isEmpty())
                ctx.applyMassFieldKeywordGrant(traits, inclForwards, inclMonsters,
                        opponentOnly, selfOnly, element, -1, null, null);
        };
    }
    /**
     * Parses "Until end of turn, all [the] [element] [Category X] [targets] [you control]
     * gain +N power [and Keywords]."
     * Must be tried AFTER {@link #tryParseUntilEotDualPowerShift} to avoid partial matches.
     */
    static Consumer<GameContext> tryParseUntilEotAllFieldPowerBoost(String text) {
        if (UNTIL_EOT_DUAL_POWER_SHIFT_PATTERN.matcher(text).find()) return null;

        Matcher m = UNTIL_EOT_ALL_FIELD_POWER_BOOST_PATTERN.matcher(text);
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

        String verb = m.group("verb");
        boolean isLoss = verb != null && verb.toLowerCase().startsWith("lose");
        int amount = Integer.parseInt(m.group("amount"));
        int signedAmount = isLoss ? -amount : amount;

        String keywordsStr = m.group("keywords");
        EnumSet<CardData.Trait> traits = keywordsStr != null
                ? parseTraits(keywordsStr) : EnumSet.noneOf(CardData.Trait.class);

        String elemLabel    = element != null ? element + " " : "";
        String catLabel     = category != null ? "Category " + category + " " : "";
        String costLabel    = costVal >= 0 ? " of cost " + costVal + (costCmp != null ? " or " + costCmp : "") : "";
        String controlLabel = opponentOnly ? " (opponent)" : selfOnly ? " (yours)" : "";
        String traitStr     = traits.isEmpty() ? "" : " and " + traitNamesOnly(traits);
        String sign         = isLoss ? "-" : "+";
        String logMsg = "Until EOT all " + elemLabel + catLabel + targets + costLabel
                + controlLabel + " " + sign + amount + " power" + traitStr;

        return ctx -> {
            ctx.logEntry("Effect: " + logMsg);
            ctx.applyMassFieldPowerBoost(signedAmount, inclForwards, inclMonsters,
                    opponentOnly, selfOnly, element, costVal, costCmp, category, null);
            if (!traits.isEmpty())
                ctx.applyMassFieldKeywordGrant(traits, inclForwards, inclMonsters,
                        opponentOnly, selfOnly, element, costVal, costCmp, category);
        };
    }
    /**
     * Parses "Until end of turn, all [the] [targets] [you control] gain +N power
     * and all [the] [targets] [opponent controls] lose N power."
     */
    static Consumer<GameContext> tryParseUntilEotDualPowerShift(String text) {
        Matcher m = UNTIL_EOT_DUAL_POWER_SHIFT_PATTERN.matcher(text);
        if (!m.find()) return null;

        String targets1  = m.group("targets1");
        String tgt1Lower = targets1.toLowerCase();
        boolean inclFwd1 = tgt1Lower.contains("forward") || tgt1Lower.contains("character");
        boolean inclMon1 = tgt1Lower.contains("monster") || tgt1Lower.contains("character");

        String control1  = m.group("control1");
        boolean opp1     = control1 != null && !control1.toLowerCase().contains("you control");
        boolean self1    = control1 != null && control1.toLowerCase().contains("you control");
        int amount1      = Integer.parseInt(m.group("amount1"));

        String targets2  = m.group("targets2");
        String tgt2Lower = targets2.toLowerCase();
        boolean inclFwd2 = tgt2Lower.contains("forward") || tgt2Lower.contains("character");
        boolean inclMon2 = tgt2Lower.contains("monster") || tgt2Lower.contains("character");

        String control2  = m.group("control2");
        boolean opp2     = control2 != null && !control2.toLowerCase().contains("you control");
        boolean self2    = control2 != null && control2.toLowerCase().contains("you control");
        int amount2      = Integer.parseInt(m.group("amount2"));

        String ctrl1Label = opp1 ? " (opponent)" : self1 ? " (yours)" : "";
        String ctrl2Label = opp2 ? " (opponent)" : self2 ? " (yours)" : "";
        String logMsg = "Until EOT all " + targets1 + ctrl1Label + " +" + amount1
                + " power, all " + targets2 + ctrl2Label + " -" + amount2 + " power";

        return ctx -> {
            ctx.logEntry("Effect: " + logMsg);
            ctx.applyMassFieldPowerBoost( amount1, inclFwd1, inclMon1, opp1, self1, null, -1, null, null, null);
            ctx.applyMassFieldPowerBoost(-amount2, inclFwd2, inclMon2, opp2, self2, null, -1, null, null, null);
        };
    }
    static Consumer<GameContext> tryParseEachPlayerMaySearchForwardMinPower(String text) {
        Matcher m = EACH_PLAYER_MAY_SEARCH_FORWARD_MIN_POWER.matcher(text.trim());
        if (!m.matches()) return null;
        int count    = Integer.parseInt(m.group("count"));
        int minPower = Integer.parseInt(m.group("power"));
        return ctx -> {
            ctx.logEntry("Effect: Each player may search for " + count + " Forward(s) power " + minPower + "+");
            ctx.eachPlayerMaySearchForwardMinPowerToHand(count, minPower);
        };
    }
    static Consumer<GameContext> tryParseNameJobOrElementAllForwardsBoost(String text) {
        Matcher m = NAME_JOB_OR_ELEMENT_ALL_FORWARDS_BOOST.matcher(text);
        if (!m.find()) return null;
        int amount = Integer.parseInt(m.group("amount"));
        return ctx -> {
            ctx.logEntry("Effect: Name 1 Job or Element — all controlled Forwards +" + amount + " power and named until EOT");
            String[] choice = ctx.selectJobOrElement("Name 1 Job or 1 Element:");
            if (choice == null || choice[1] == null) return;
            ctx.applyMassFieldPowerBoost(amount, true, false, false, true, null, -1, null, null, null);
            if ("job".equalsIgnoreCase(choice[0]))
                ctx.grantAllControlledForwardsJobUntilEOT(choice[1]);
            else
                ctx.grantAllControlledForwardsElementUntilEOT(choice[1]);
        };
    }
    /**
     * Parses "Choose 1 Forward other than [CardName]. Until the end of the turn, [CardName]
     * and the chosen Forward lose power of any value less than [CardName]'s power. (Units must be 1000.)"
     *
     * <p>Shows a Forward picker (excluding the named card), then a power-amount picker
     * (0 … named card's current power − 1000, in 1000 steps, defaulting to the max).
     * Both the named card and the chosen Forward lose the selected amount until EOT.
     */
    static Consumer<GameContext> tryParseChooseForwardSharedPowerLoss(String text, CardData source) {
        Matcher m = CHOOSE_FORWARD_SHARED_POWER_LOSS_PATTERN.matcher(text.trim());
        if (!m.find()) return null;
        String card1 = m.group("card").trim();
        String card2 = m.group("card2").trim();
        String card3 = m.group("card3").trim();
        if (!card1.equalsIgnoreCase(card2) || !card1.equalsIgnoreCase(card3)) return null;
        final String cardName = card1;
        final EnumSet<CardData.Trait> noTraits = EnumSet.noneOf(CardData.Trait.class);
        return ctx -> {
            ctx.logEntry("Effect: Choose 1 Forward other than " + cardName + ", then choose shared power loss");
            List<ForwardTarget> ts = selectTargets(ctx, 1, false,
                    false, false, null, null, null, false,
                    -1, null, -1, null,
                    true, false, false,
                    null, null, null, cardName, false, null, false);
            if (ts.isEmpty()) return;
            int sourcePower = ctx.fieldForwardPowerByName(cardName);
            int maxLoss = sourcePower > 0 ? ((sourcePower - 1) / 1000) * 1000 : 0;
            int amount = ctx.selectPowerAmount(maxLoss,
                    "Power loss (0–" + maxLoss + ") for " + cardName + " and chosen Forward:");
            if (amount <= 0) return;
            ctx.reduceTarget(ts.get(0), amount, noTraits);
            if (source != null && source.name().equalsIgnoreCase(cardName))
                ctx.reduceSourceForward(source, amount, noTraits);
        };
    }

    /**
     * Parses "Until the end of the turn, all the Forwards opponent controls lose [traits]." -
     * 15-022C Amidatelion, which strips keywords off the whole opposing board.
     *
     * <p>Built out of the per-target trait removal rather than a new mass primitive:
     * {@code reduceTarget} already takes the trait set, and a 0 power change makes it a
     * trait-only edit. Iterating backwards keeps the indices valid, in step with every other
     * whole-board effect here - though nothing removed by a trait strip can leave the field, so
     * the ordering is belt and braces.
     */
    static Consumer<GameContext> tryParseAllOppForwardsLoseTraitsEot(String text) {
        Matcher m = ALL_OPP_FORWARDS_LOSE_TRAITS_EOT.matcher(text.trim());
        if (!m.matches()) return null;
        EnumSet<CardData.Trait> traits = parseTraits(m.group("traits"));
        if (traits.isEmpty()) return null;
        return ctx -> {
            ctx.logEntry("Effect: all opponent Forwards lose " + traits + " until end of turn");
            boolean oppIsP1 = !ctx.isP1();
            int count = oppIsP1 ? ctx.p1ForwardCount() : ctx.p2ForwardCount();
            for (int i = count - 1; i >= 0; i--) {
                ctx.reduceTarget(new ForwardTarget(oppIsP1, i, ForwardTarget.CardZone.FORWARD), 0, traits);
            }
        };
    }
}
