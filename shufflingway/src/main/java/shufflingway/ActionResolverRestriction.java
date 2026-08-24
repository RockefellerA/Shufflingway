package shufflingway;

import java.util.EnumSet;
import java.util.function.Consumer;
import java.util.regex.Matcher;

import static shufflingway.ActionResolver.*;
import static shufflingway.ActionResolverPatterns.*;

/**
 * Restriction parsers split out of {@link ActionResolver}.
 *
 * <p>Bodies only: {@code ActionResolver} keeps every dispatch chain and calls these
 * through a wildcard static import, so call order -- which is load-bearing, because
 * matchers use {@code find()} -- is unchanged.
 */
final class ActionResolverRestriction {

	private ActionResolverRestriction() {}

    static Consumer<GameContext> tryParseOwnForwardsCannotBeChosenByExBurst(String text) {
        if (!OWN_FORWARDS_CANNOT_BE_CHOSEN_BY_EX_BURST.matcher(text.trim()).matches()) return null;
        return ctx -> ctx.shieldAllOwnForwardsCannotBeChosen(true, false);
    }
    static Consumer<GameContext> tryParseExBurstSuppression(String text) {
        if (!EX_BURST_SUPPRESSION_PATTERN.matcher(text.trim()).matches()) return null;
        return ctx -> {
            ctx.logEntry("Effect: EX Bursts due to this ability are suppressed");
            ctx.suppressExBurstsThisAbility();
        };
    }
    static Consumer<GameContext> tryParseStandaloneGainsTraitsAndCannotBeBlocked(
            String text, CardData source) {
        if (source == null) return null;
        Matcher m = STANDALONE_GAINS_TRAITS_AND_CANNOT_BE_BLOCKED.matcher(text);
        if (!m.find()) return null;
        String subject = m.group("subject").trim();
        if (!subject.equalsIgnoreCase(source.name())) return null;
        EnumSet<CardData.Trait> traits = parseTraits(m.group("traits"));
        String logSuffix = boostLogSuffix(0, traits) + " and cannot be blocked until end of turn";
        return ctx -> {
            ctx.logEntry(source.name() + logSuffix);
            ctx.boostSourceForward(source, 0, traits);
            ctx.setSourceForwardCannotBeBlocked(source);
        };
    }
    static Consumer<GameContext> tryParseStandaloneGainsTraitsAndCannotBeBlockedTrailing(
            String text, CardData source) {
        if (source == null) return null;
        Matcher m = STANDALONE_GAINS_TRAITS_AND_CANNOT_BE_BLOCKED_TRAILING.matcher(text);
        if (!m.find()) return null;
        String subject = m.group("subject").trim();
        if (!subject.equalsIgnoreCase(source.name())) return null;
        EnumSet<CardData.Trait> traits = parseTraits(m.group("traits"));
        String logSuffix = boostLogSuffix(0, traits) + " and cannot be blocked until end of turn";
        return ctx -> {
            ctx.logEntry(source.name() + logSuffix);
            ctx.boostSourceForward(source, 0, traits);
            ctx.setSourceForwardCannotBeBlocked(source);
        };
    }
    static Consumer<GameContext> tryParseStandaloneGainsCannotBeBlocked(
            String text, CardData source) {
        if (source == null) return null;
        Matcher m = STANDALONE_GAINS_CANNOT_BE_BLOCKED.matcher(text.trim());
        if (!m.matches()) return null;
        if (!m.group("subject").trim().equalsIgnoreCase(source.name())) return null;
        return ctx -> {
            ctx.logEntry(source.name() + " cannot be blocked until end of turn");
            ctx.setSourceForwardCannotBeBlocked(source);
        };
    }
    /**
     * Parses standalone "cannot be broken until end of turn" grants:
     * <ul>
     *   <li>"[CardName] gains '[...] cannot be broken.' until end of turn." — self-shield</li>
     *   <li>"[CardName] gains '[...] cannot be broken by opposing Summons or abilities that
     *       don't deal damage.' until the end of the turn." — self-shield vs non-damage breaks</li>
     *   <li>"All [the] Forwards you control gain '[...] cannot be broken.' until end of turn." — all own</li>
     * </ul>
     */
    static Consumer<GameContext> tryParseStandaloneShieldCannotBeBroken(
            String text, CardData source) {
        if (STANDALONE_ALL_FORWARDS_SHIELD_CANNOT_BE_BROKEN.matcher(text).find()) {
            return ctx -> {
                ctx.logEntry("Effect: All own Forwards cannot be broken until end of turn");
                ctx.shieldAllOwnForwards();
            };
        }
        if (source == null) return null;
        // Non-damage-only variant first: its quoted body would not satisfy the plain pattern,
        // but checking it first keeps the two from ever competing.
        Matcher nd = STANDALONE_SELF_SHIELD_CANNOT_BE_BROKEN_BY_NON_DMG.matcher(text);
        if (nd.find() && nd.group("subject").trim().equalsIgnoreCase(source.name())) {
            return ctx -> {
                boolean p1 = ctx.isP1();
                int count = p1 ? ctx.p1ForwardCount() : ctx.p2ForwardCount();
                for (int i = 0; i < count; i++) {
                    CardData c = p1 ? ctx.p1Forward(i) : ctx.p2Forward(i);
                    if (c.name().equalsIgnoreCase(source.name())) {
                        ctx.shieldCannotBeBrokenByNonDmg(new ForwardTarget(p1, i, ForwardTarget.CardZone.FORWARD));
                        return;
                    }
                }
            };
        }
        Matcher m = STANDALONE_SELF_SHIELD_CANNOT_BE_BROKEN.matcher(text);
        if (!m.find()) {
            m = STANDALONE_SELF_SHIELD_CANNOT_BE_BROKEN_SIMPLE.matcher(text);
            if (!m.find()) return null;
        }
        String subject = m.group("subject").trim();
        if (!subject.equalsIgnoreCase(source.name())) return null;
        return ctx -> {
            ctx.logEntry(source.name() + " cannot be broken until end of turn");
            ctx.shieldSourceForward(source);
        };
    }
    static Consumer<GameContext> tryParseAllForwardsCannotBlock(String text) {
        if (!STANDALONE_ALL_FORWARDS_CANNOT_BLOCK.matcher(text).matches()) return null;
        return ctx -> {
            ctx.logEntry("Effect: All Forwards cannot block this turn");
            for (int i = 0; i < ctx.p1ForwardCount(); i++) ctx.setP1ForwardCannotBlock(i);
            for (int i = 0; i < ctx.p2ForwardCount(); i++) ctx.setP2ForwardCannotBlock(i);
        };
    }
    static Consumer<GameContext> tryParseStandaloneCannotBeBlocked(String text, CardData source) {
        if (source == null) return null;
        Matcher m = STANDALONE_SELF_CANNOT_BE_BLOCKED.matcher(text);
        if (!m.find()) return null;
        String subject = m.group("subject").trim();
        if (subject.equalsIgnoreCase("it") || subject.equalsIgnoreCase("they")) return null;
        if (!subject.equalsIgnoreCase(source.name())) return null;
        return ctx -> {
            ctx.logEntry(source.name() + " cannot be blocked this turn");
            ctx.setSourceForwardCannotBeBlocked(source);
        };
    }
    /**
     * Parses standalone "cannot be chosen" protection effects:
     * <ul>
     *   <li>"Activate all the Forwards you control. They cannot be chosen by your opponent's Summons."</li>
     *   <li>"This Forward/Character cannot be chosen by your opponent's Summons or abilities."</li>
     *   <li>"[CardName] cannot be chosen by your opponent's Summons." (name must match {@code source})</li>
     *   <li>"The Job X [other than Y] Forwards you control cannot be chosen by your opponent's Summons."</li>
     * </ul>
     * Registered before {@link #tryParseNegateAllDamage} and {@link #tryParseAllFieldEffect}.
     */
    static Consumer<GameContext> tryParseCannotBeChosenStandalone(String text, CardData source) {
        // 1. Compound: "Activate all Forwards + They cannot be chosen"
        Matcher actM = STANDALONE_ACTIVATE_AND_CANNOT_BE_CHOSEN.matcher(text);
        if (actM.find()) {
            boolean bs = actM.group("scope").toLowerCase(java.util.Locale.ROOT).contains("summon");
            boolean ba = actM.group("scope").toLowerCase(java.util.Locale.ROOT).contains("abilit");
            return ctx -> {
                ctx.logEntry("Effect: Activate all own Forwards + cannot be chosen by opponent");
                ctx.applyMassFieldEffect(GameContext.MassAction.ACTIVATE, true, false, false, false, true, null, -1, null, -1, null, null);
                ctx.shieldAllOwnForwardsCannotBeChosen(bs, ba);
            };
        }

        // 2. Job filter: "The Job X [other than Y] Forwards cannot be chosen"
        Matcher jobM = STANDALONE_JOB_CANNOT_BE_CHOSEN.matcher(text);
        if (jobM.find()) {
            String job  = jobM.group("job").trim();
            String excl = jobM.group("excl") != null ? jobM.group("excl").trim() : null;
            boolean bs  = jobM.group("scope").toLowerCase(java.util.Locale.ROOT).contains("summon");
            boolean ba  = jobM.group("scope").toLowerCase(java.util.Locale.ROOT).contains("abilit");
            return ctx -> {
                ctx.logEntry("Effect: Job " + job + " Forwards cannot be chosen by opponent");
                ctx.shieldJobForwardsCannotBeChosen(job, excl, bs, ba);
            };
        }

        // 3. Self-referential: "This Forward/Character cannot be chosen"
        Matcher selfM = STANDALONE_SELF_CANNOT_BE_CHOSEN.matcher(text);
        if (selfM.find() && source != null) {
            boolean bs = selfM.group("scope").toLowerCase(java.util.Locale.ROOT).contains("summon");
            boolean ba = selfM.group("scope").toLowerCase(java.util.Locale.ROOT).contains("abilit");
            String  nm = source.name();
            return ctx -> {
                ctx.logEntry("Effect: " + nm + " cannot be chosen by opponent");
                ctx.shieldNamedCardCannotBeChosen(nm, bs, ba);
            };
        }

        // 4. Named card: "[Name] cannot be chosen by your opponent's Summons/abilities"
        Matcher nameM = STANDALONE_NAMED_CANNOT_BE_CHOSEN.matcher(text);
        if (nameM.find() && source != null) {
            String nm   = nameM.group("name").trim();
            boolean bs  = nameM.group("scope").toLowerCase(java.util.Locale.ROOT).contains("summon");
            boolean ba  = nameM.group("scope").toLowerCase(java.util.Locale.ROOT).contains("abilit");
            if (nm.equalsIgnoreCase(source.name()))
                return ctx -> {
                    ctx.logEntry("Effect: " + nm + " cannot be chosen by opponent");
                    ctx.shieldNamedCardCannotBeChosen(nm, bs, ba);
                };
        }

        // 5. Named card, no "your opponent's" qualifier: "[Name] cannot be chosen by Summons" — either player
        Matcher anyM = STANDALONE_NAMED_CANNOT_BE_CHOSEN_ANY_SUMMON.matcher(text);
        if (anyM.find() && source != null) {
            String nm = anyM.group("name").trim();
            if (nm.equalsIgnoreCase(source.name()))
                return ctx -> ctx.shieldNamedCardCannotBeChosenByAnySummon(nm);
        }

        // 6. "Name 1 Element. [Name] cannot be chosen … and if [Name] is dealt damage … becomes 0."
        //    (Hein-style: targeting immunity + damage nullification for the named element)
        Matcher heinM = STANDALONE_NAME_ELEMENT_IMMUNE_AND_NULLIFY_DAMAGE.matcher(text);
        if (heinM.find() && source != null) {
            String nm = heinM.group("name").trim();
            if (nm.equalsIgnoreCase(source.name()))
                return ctx -> {
                    String elem = ctx.selectElement("Name 1 Element (" + nm + " full protection):");
                    if (elem != null) {
                        ctx.logEntry("Effect: " + nm + " cannot be chosen by " + elem + " Summons/abilities; damage from them → 0 this turn");
                        ctx.shieldNamedCardCannotBeChosenByElement(nm, elem);
                        ctx.nullifyNamedCardDamageByElement(nm, elem);
                    }
                };
        }

        // 6b. "Name 1 Element. During this turn, if [Name] is dealt damage by abilities of the named
        //     Element, the damage becomes 0 instead." (Rubicante-style: ability-only damage
        //     nullification, no targeting immunity — unlike Hein's combined block above.)
        Matcher rubiM = STANDALONE_NAME_ELEMENT_NULLIFY_ABILITY_DAMAGE_ONLY.matcher(text);
        if (rubiM.find() && source != null) {
            String nm = rubiM.group("name").trim();
            if (nm.equalsIgnoreCase(source.name()))
                return ctx -> {
                    String elem = ctx.selectElement("Name 1 Element (" + nm + " damage nullification):");
                    if (elem != null) {
                        ctx.logEntry("Effect: " + nm + " — damage from " + elem + " abilities becomes 0 this turn");
                        ctx.nullifyNamedCardDamageByElementAbilityOnly(nm, elem);
                    }
                };
        }

        // 7. "Name 1 Element. [Name] cannot be chosen by Summons or abilities of the named Element this turn."
        Matcher elemM = STANDALONE_NAME_ELEMENT_AND_IMMUNE.matcher(text);
        if (elemM.find() && source != null) {
            String nm = elemM.group("name").trim();
            if (nm.equalsIgnoreCase(source.name()))
                return ctx -> {
                    String elem = ctx.selectElement("Name 1 Element (" + nm + " immunity):");
                    if (elem != null) {
                        ctx.logEntry("Effect: " + nm + " cannot be chosen by " + elem + " Summons/abilities this turn");
                        ctx.shieldNamedCardCannotBeChosenByElement(nm, elem);
                    }
                };
        }

        return null;
    }
    /**
     * Parses "[CardName] cannot become dull by your opponent's Summons or abilities."
     * Enforcement is handled in {@link GameContextImpl#dullP1Forward} / {@code dullP2Forward}
     * via {@link #hasCannotBeDulledByOppFieldAbility}.
     */
    static Consumer<GameContext> tryParseCannotBecomeDullOpp(String text, CardData source) {
        Matcher m = STANDALONE_NAMED_CANNOT_BECOME_DULL_OPP.matcher(text);
        if (!m.find() || source == null) return null;
        String nm = m.group("name").trim();
        if (!nm.equalsIgnoreCase(source.name())) return null;
        return ctx -> ctx.logEntry("Field ability: " + nm + " cannot become dull by opponent's Summons or abilities");
    }
    /**
     * Parses "[CardName] cannot be put into the Break Zone by [your] opponent's Summons or
     * abilities." Enforcement is handled in the {@link GameContextImpl} break wrappers via
     * {@link #hasCannotBePutIntoBzByOppFieldAbility}.
     */
    static Consumer<GameContext> tryParseCannotBePutIntoBzOpp(String text, CardData source) {
        Matcher m = STANDALONE_NAMED_CANNOT_BE_PUT_INTO_BZ_OPP.matcher(text);
        if (!m.find() || source == null) return null;
        String nm = m.group("name").trim();
        if (!nm.equalsIgnoreCase(source.name())) return null;
        return ctx -> ctx.logEntry("Field ability: " + nm + " cannot be put into the Break Zone by opponent's Summons or abilities");
    }
    /**
     * Parses "Activate all the Forwards you control. Until the end of the turn, all the
     * Forwards you control gain "&lt;protection&gt;" and "&lt;protection&gt;"." — mass activate
     * plus EOT protection grants for every own Forward.
     */
    static Consumer<GameContext> tryParseActivateAllOwnFwdsGainProtections(String text) {
        Matcher m = ACTIVATE_ALL_OWN_FWDS_GAIN_PROTECTIONS.matcher(text.trim());
        if (!m.matches()) return null;
        EnumSet<CardData.Trait> traits = EnumSet.noneOf(CardData.Trait.class);
        if (!addQuotedProtectionTraits(m.group("quotes"), traits)) return null;
        final EnumSet<CardData.Trait> grant = traits;
        return ctx -> {
            ctx.logEntry("Effect: Activate all own Forwards + protections until end of turn");
            ctx.applyMassFieldEffect(GameContext.MassAction.ACTIVATE, true, false, false, false, true,
                    null, -1, null, -1, null, null);
            ctx.applyMassFieldKeywordGrant(grant, true, false, false, true, null, -1, null, null);
        };
    }
    /**
     * Parses permanent and conditional "cannot attack or block" field ability texts:
     * <ol>
     *   <li>"[CardName] cannot attack or block." — unconditional; enforced via
     *       {@link CardData#cannotAttackOrBlock()}.</li>
     *   <li>"[CardName] cannot attack." — unconditional attack-only; enforced via field-ability check.</li>
     *   <li>"If you don't control a Card Name [X] Forward, [CardName] cannot attack or block."</li>
     *   <li>"If [N] or less [Name] Counter(s) are placed on [CardName], [CardName] cannot attack or block."</li>
     * </ol>
     * The consumer only logs; enforcement for cases 2–5 is handled in the game loop.
     */
    static Consumer<GameContext> tryParseStandaloneCannotAttackOrBlock(String text, CardData source) {
        if (source == null) return null;
        // 1. Simple: "[CardName] cannot attack or block."
        Matcher m1 = STANDALONE_CANNOT_ATTACK_OR_BLOCK.matcher(text);
        if (m1.find()) {
            String nm = m1.group("cardname").trim();
            if (nm.equalsIgnoreCase(source.name()))
                return ctx -> ctx.logEntry(nm + " — cannot attack or block (permanent field restriction)");
        }
        // 1b. Attack-only: "[CardName] cannot attack."
        Matcher m1b = STANDALONE_CANNOT_ATTACK.matcher(text);
        if (m1b.find()) {
            String nm = m1b.group("cardname").trim();
            if (nm.equalsIgnoreCase(source.name()))
                return ctx -> ctx.logEntry(nm + " — cannot attack (permanent field restriction)");
        }
        // 2. Conditional: "If you don't control Card Name X Forward, [subject] cannot attack or block."
        Matcher m2 = IF_DONT_CONTROL_CARD_NAME_FWD_CANNOT_ATTACK_OR_BLOCK.matcher(text);
        if (m2.find()) {
            String subject  = m2.group("subject").trim();
            String required = m2.group("required").trim();
            if (subject.equalsIgnoreCase(source.name()))
                return ctx -> ctx.logEntry(subject + " — cannot attack or block unless you control Card Name " + required + " Forward");
        }
        // 3. Counter-conditional: "If N or less [Name] Counters are placed on [target], [subject] cannot attack or block."
        Matcher m3 = IF_COUNTER_LIMIT_CANNOT_ATTACK_OR_BLOCK.matcher(text);
        if (m3.find()) {
            String subject     = m3.group("subject").trim();
            String counterName = m3.group("countername").trim();
            int    limit       = Integer.parseInt(m3.group("count"));
            if (subject.equalsIgnoreCase(source.name()))
                return ctx -> ctx.logEntry(subject + " — cannot attack or block if " + counterName + " Counters ≤ " + limit);
        }
        // 4. Opponent-no-forwards: "If your opponent doesn't control any Forwards, [CardName] cannot attack."
        Matcher m4 = IF_OPP_NO_FORWARDS_CANNOT_ATTACK.matcher(text);
        if (m4.find()) {
            String subject = m4.group("subject").trim();
            if (subject.equalsIgnoreCase(source.name()))
                return ctx -> ctx.logEntry(subject + " — cannot attack if opponent controls no Forwards");
        }
        return null;
    }
    /**
     * Parses "[Self] cannot be chosen by a Summon or an ability this turn and gains [traits] until
     * the end of the turn." — 2-065L Balthier's Fires of War.
     *
     * <p>The immunity binds both players, not just the opponent: nothing in the sentence names one,
     * so Balthier's own controller cannot target him either. That is the whole reason it does not
     * go through the opponent-scoped shield the rest of this family uses.
     *
     * <p>Declines when the sentence names a card other than the source — every primitive it calls
     * acts on the source, so a mismatch would shield the wrong Forward.
     */
    static Consumer<GameContext> tryParseSelfCannotBeChosenByAnyAndGainsTraits(String text, CardData source) {
        if (source == null) return null;
        Matcher m = SELF_CANNOT_BE_CHOSEN_BY_ANY_AND_GAINS_TRAITS.matcher(text.trim());
        if (!m.matches()) return null;
        if (!m.group("name").trim().equalsIgnoreCase(source.name())) return null;
        EnumSet<CardData.Trait> traits = parseTraits(m.group("traits"));
        if (traits.isEmpty()) return null;
        return ctx -> {
            ctx.logEntry(source.name() + " cannot be chosen by any Summon or ability this turn");
            ctx.shieldSelfCannotBeChosenByAnySummonOrAbility(source);
            ctx.logEntry(source.name() + " gains " + traitNamesOnly(traits) + " until end of turn");
            ctx.boostSourceForward(source, 0, traits);
        };
    }
}
