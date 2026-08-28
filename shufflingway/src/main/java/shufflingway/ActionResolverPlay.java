package shufflingway;

import static shufflingway.ActionResolverPatterns.*;

import static shufflingway.ActionResolver.*;

import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Matcher;

/**
 * Play parsers split out of {@link ActionResolver}.
 *
 * <p>Bodies only: {@code ActionResolver} keeps every dispatch chain and calls these
 * through a wildcard static import, so call order -- which is load-bearing, because
 * matchers use {@code find()} -- is unchanged.
 */
final class ActionResolverPlay {

	private ActionResolverPlay() {}

    static Consumer<GameContext> tryParseChooseWarpCardRemoveCounter(String text) {
        if (!CHOOSE_WARP_CARD_REMOVE_COUNTER.matcher(text).find()) return null;
        return GameContext::chooseAndRemoveWarpCounter;
    }
    static Consumer<GameContext> tryParseChooseWarpCardMayRemoveCounter(String text) {
        if (!CHOOSE_WARP_CARD_MAY_REMOVE_COUNTER.matcher(text).find()) return null;
        return GameContext::chooseAndMayRemoveWarpCounter;
    }
    static Consumer<GameContext> tryParseDoublePlayerAbilityOutgoingThisTurn(String text) {
        if (!DOUBLE_PLAYER_ABILITY_OUTGOING_THIS_TURN.matcher(text).find()) return null;
        return ctx -> ctx.doublePlayerAbilityOutgoingDamage();
    }
    /**
     * Parses Doublecast (Yuna): "When you cast a Summon this turn, you may cast 1 Summon from
     * your hand with a cost inferior to that of the Summon you cast without paying its cost."
     */
    static Consumer<GameContext> tryParseDoublecastFreeSummons(String text) {
        if (!DOUBLECAST_FREE_SUMMONS_PATTERN.matcher(text.trim()).matches()) return null;
        return ctx -> {
            ctx.logEntry("Effect: Doublecast — after each Summon cast this turn, "
                + "lower-cost hand Summons cast free");
            ctx.activateDoublecastFreeSummons();
        };
    }
    static Consumer<GameContext> tryParseIfCastAtLeast(String text, CardData source, int xValue) {
        Matcher m = IF_CAST_AT_LEAST.matcher(text.trim());
        if (!m.matches()) return null;
        int min = Integer.parseInt(m.group("min"));
        Consumer<GameContext> inner = parse(m.group("effect").trim(), source, xValue);
        if (inner == null) return null;
        return ctx -> {
            int cast = ctx.selfCardsCastThisTurn();
            if (cast >= min) {
                inner.accept(ctx);
            } else {
                ctx.logEntry("Effect: only cast " + cast + " card(s) this turn (need " + min + ") — skipped");
            }
        };
    }
    /**
     * Parses "At the end of your opponent's turn, play [CardName] onto the field." — schedules
     * {@link GameContext#playNamedFromRfpOntoField} to fire at the end of the opponent's next turn.
     */
    static Consumer<GameContext> tryParseEndOfOppTurnPlayNamedOntoField(String text) {
        Matcher m = AT_END_OF_OPP_TURN_PLAY_NAMED_ONTO_FIELD.matcher(text.trim());
        if (!m.matches()) return null;
        String name = m.group("name").trim();
        return ctx -> ctx.addEndOfOpponentTurnEffect(ctx2 -> ctx2.playNamedFromRfpOntoField(name));
    }
    /**
     * Parses "Play [CardName] onto the field at the end of the turn." — the delayed half of
     * Lightning 16-124H's Switch Schemata, whose preceding sentence removed the card from the
     * game, and of Ardyn B-024's put-into-the-Break-Zone trigger. The wording names no zone, so
     * this queues {@link GameContext#playNamedFromHoldingZoneOntoField} for the current turn's
     * end phase and lets it find the card wherever the ability's earlier half left it.
     */
    static Consumer<GameContext> tryParseEndOfTurnPlayNamedOntoField(String text) {
        Matcher m = PLAY_NAMED_ONTO_FIELD_AT_END_OF_TURN.matcher(text.trim());
        if (!m.matches()) return null;
        String name = m.group("name").trim();
        // "it"/"them" points at a card chosen earlier in the same ability (Kytes 15-047R,
        // Ghost (VII) 20-046C, Cactuar Conductor 26-049R), not at a name the RFG lookup could
        // find. That form is the choose chain's followup, and must be left to it.
        if (name.equalsIgnoreCase("it") || name.equalsIgnoreCase("them")) return null;
        return ctx -> {
            ctx.logEntry("Effect: Play " + name + " onto the field at the end of the turn");
            ctx.addEndOfTurnEffect(ctx2 -> ctx2.playNamedFromHoldingZoneOntoField(name));
        };
    }
    /**
     * Parses "Remove [Self] from the game. Then, play [Self] onto the field [dull]." — Lightning
     * 4-115L's immediate self-blink. The card leaves for the RFG zone and comes straight back, so
     * the replay reads {@link GameContext#playLastRemovedFromRfpOntoField} (the card the preceding
     * call just put there) rather than the Break-Zone route the second sentence would take alone.
     */
    static Consumer<GameContext> tryParseRemoveSelfThenPlaySelfOntoField(String text, CardData source) {
        if (source == null) return null;
        Matcher m = REMOVE_SELF_THEN_PLAY_SELF_ONTO_FIELD.matcher(text.trim());
        if (!m.matches()) return null;
        // Both halves must name the source: "Remove X from the game. Play Y onto the field" is a
        // different effect, and the RFG-top lookup below would return the wrong card for it.
        String name = m.group("name").trim();
        if (!name.equalsIgnoreCase(source.name())) return null;
        if (!m.group("name2").trim().equalsIgnoreCase(source.name())) return null;
        boolean dull = m.group("dull") != null;
        return ctx -> {
            ctx.logEntry("Effect: Remove " + name + " from the game, then play it onto the field"
                    + (dull ? " dull" : ""));
            ctx.removeNamedCardFromGame(name);
            ctx.playLastRemovedFromRfpOntoField(dull);
        };
    }
    static Consumer<GameContext> tryParseIfEitherPlayerNoForwardsPutSourceToBz(String text, CardData source) {
        if (source == null) return null;
        Matcher m = IF_EITHER_PLAYER_NO_FORWARDS_PUT_SOURCE_TO_BZ.matcher(text.trim());
        if (!m.matches()) return null;
        if (!m.group("name").trim().equalsIgnoreCase(source.name())) return null;
        return ctx -> {
            if (ctx.selfForwardCount() > 0 && ctx.opponentForwardCount() > 0) return;
            ctx.logEntry("Effect: a player controls no Forwards — Break " + source.name());
            ctx.breakSourceCard(source);
        };
    }
    /** No-op recogniser for multi-play grant field abilities handled as static card properties. */
    static Consumer<GameContext> tryParseMultiPlayGrant(String text) {
        if (CardData.MULTI_LIGHT_DARK_PLAY_PATTERN.matcher(text).matches()) return ctx -> {};
        if (CardData.MULTI_NAME_PLAY_PATTERN.matcher(text).matches())       return ctx -> {};
        return null;
    }
    /**
     * Parses "Choose 1 Summon [or ability] targeting/choosing a Character/Forward/Backup you
     * control. Cancel its effect." Only entries whose pre-selected targets include a card the
     * canceler controls are eligible.
     */
    static Consumer<GameContext> tryParseCancelSummonTargetingMyCharacter(String text) {
        Matcher m = CANCEL_SUMMON_TARGETING_MY_CHARACTER.matcher(text);
        if (!m.find()) return null;
        boolean orAbility = m.group("orability") != null;
        // "ability" here is the same union parseAbilityTypeFilter builds for the bare word: any
        // non-Summon entry that is not an EX Burst.
        Predicate<StackEntry> filter = orAbility
                ? e -> !e.isExBurstEntry()
                : StackEntry::isSummon;
        String what = orAbility ? "Summon or ability" : "Summon";
        return ctx -> {
            ctx.logEntry("Effect: Choose 1 " + what + " choosing your Character — cancel its effect");
            ctx.cancelFilteredAbilityOnStack(filter, "Choose 1 " + what + " choosing your Character to cancel:", true);
        };
    }
    /**
     * Recognizes "Players cannot cast Summons." as a known passive field ability.
     * Returns a no-op consumer (the restriction is enforced statically by {@link MainWindow}).
     */
    static Consumer<GameContext> tryParsePlayerCannotCastSummons(String text) {
        if (!PLAYERS_CANNOT_CAST_SUMMONS.matcher(text.trim()).matches()) return null;
        return ctx -> ctx.logEntry("Static: Players cannot cast Summons");
    }
    /**
     * Parses "Choose 1 [Element] Summon in your Break Zone. You can cast it at any time
     * you could normally cast it this turn. The cost required to cast it is reduced by N."
     * At resolution: shows a chooser, moves the picked Summon BZ→hand, and registers a
     * cardname-targeted CostReductionModifier so the existing hand-cast path discounts it.
     */
    static Consumer<GameContext> tryParseChooseSummonInBzCastable(String text) {
        Matcher m = CHOOSE_SUMMON_IN_BZ_CASTABLE.matcher(text);
        if (!m.find()) return null;
        final String element = m.group("element").trim();
        final int    amount  = Integer.parseInt(m.group("amount"));
        return ctx -> {
            ctx.logEntry("Effect: Choose 1 " + element + " Summon in BZ — castable this turn (cost -" + amount + ")");
            ctx.chooseSummonInBzMakeCastable(element, amount);
        };
    }
    static Consumer<GameContext> tryParseChooseFromOppBzCastable(String text) {
        Matcher m = CHOOSE_FROM_OPP_BZ_CASTABLE.matcher(text);
        if (!m.find()) return null;
        String t = m.group("type").toLowerCase(java.util.Locale.ROOT);
        final boolean inclForwards = t.startsWith("forward") || t.startsWith("character");
        final boolean inclBackups  = t.startsWith("backup")  || t.startsWith("character");
        final boolean inclMonsters = t.startsWith("monster") || t.startsWith("character");
        return ctx -> {
            ctx.logEntry("Effect: Choose 1 " + t + " in opponent's BZ, remove from game — castable as your own");
            ctx.chooseFromOpponentBzMakeCastable(inclForwards, inclBackups, inclMonsters);
        };
    }
    static Consumer<GameContext> tryParseChooseSummonsFromBzCastable(String text) {
        Matcher mg = CHOOSE_SUMMONS_FROM_BZ_GAME.matcher(text);
        if (mg.find()) {
            final int count = Integer.parseInt(mg.group("count"));
            final boolean eitherBz = !mg.group("scope").toLowerCase(java.util.Locale.ROOT).equals("your");
            return ctx -> {
                ctx.logEntry("Effect: Choose " + count + " Summon(s) from BZ, remove from game — castable as your own this game");
                ctx.chooseSummonsFromBzMakeCastable(count, eitherBz, false, false, false);
            };
        }
        Matcher mt = CHOOSE_SUMMONS_FROM_BZ_TURN.matcher(text);
        if (mt.find()) {
            final int count = Integer.parseInt(mt.group("count"));
            final boolean eitherBz = !mt.group("scope").toLowerCase(java.util.Locale.ROOT).equals("your");
            String rfgClause = mt.group("rfg") != null ? mt.group("rfg").toLowerCase(java.util.Locale.ROOT) : "";
            final boolean rfgAfterUse = rfgClause.contains("after use");
            return ctx -> {
                ctx.logEntry("Effect: Choose " + count + " Summon(s) from BZ — castable as your own this turn"
                        + (rfgAfterUse ? " (removed from game after use)" : ""));
                ctx.chooseSummonsFromBzMakeCastable(count, eitherBz, true, rfgAfterUse, false);
            };
        }
        return null;
    }
    /**
     * Parses "Play the Forward placed in the Break Zone onto the field dull" — Lunafreya 8-132L.
     *
     * <p>The card is the one whose departure fired the trigger this effect hangs off, so it is
     * neither named nor chosen: the context resolves it from the trigger now resolving.
     */
    static Consumer<GameContext> tryParsePlayBrokenCardOntoFieldDull(String text) {
        if (!PLAY_BROKEN_CARD_ONTO_FIELD_DULL.matcher(text.trim()).matches()) return null;
        return GameContext::playTriggeringBrokenCardOntoFieldDull;
    }
    /**
     * Parses "Add it to your hand" — Gogo 24-022H. The salvage twin of the parser above, and the
     * card is resolved the same way: "it" is the one whose arrival in the Break Zone fired the
     * trigger this effect hangs off, so nothing is named and nothing is chosen.
     */
    static Consumer<GameContext> tryParseAddBrokenCardToHand(String text) {
        if (!ADD_TRIGGERING_BROKEN_CARD_TO_HAND.matcher(text.trim()).matches()) return null;
        return GameContext::addTriggeringBrokenCardToHand;
    }
    /**
     * Parses "Play [name] onto [the] field [dull]" for break-zone-origin abilities where
     * the card name matches the source.  Does not require a "from Break Zone" qualifier —
     * BZ-origin abilities say "Play [itself] onto the field" knowing they start in the BZ.
     */
    /**
     * True when {@code text} is nothing but the imperative {@link #tryParsePlaySourceOntoField}
     * exists for -- "Play [Self] onto the field [dull]" and no more.
     *
     * <p>The naming chains read this rather than calling the parser, because the parser matches
     * with find() and would answer for text it never claims in parse(). Deliberately narrower
     * than parse(), so the abilities that reach the parser only through find() keep reporting no
     * name rather than reporting one this check cannot stand behind.
     */
    static boolean isBarePlaySourceOntoField(String text, CardData source) {
        if (source == null) return false;
        Matcher m = PLAY_SOURCE_ONTO_FIELD_BARE.matcher(text.trim());
        if (!m.matches()) return false;
        String name = m.group("name").trim();
        String resolved = name.equalsIgnoreCase("it") ? source.name() : name;
        return resolved.equalsIgnoreCase(source.name());
    }

    static Consumer<GameContext> tryParsePlaySourceOntoField(String text, CardData source) {
        if (source == null) return null;
        Matcher m = PLAY_SOURCE_ONTO_FIELD_PATTERN.matcher(text);
        if (!m.find()) return null;
        String name = m.group("name").trim();
        // "it" is a self-referential pronoun (e.g. "play it onto the field" in pay-cost abilities)
        String resolvedName = name.equalsIgnoreCase("it") ? source.name() : name;
        if (!resolvedName.equalsIgnoreCase(source.name())) return null;
        boolean dull = m.group("dull") != null;
        return ctx -> {
            ctx.logEntry("Effect: Play " + resolvedName + " from Break Zone → field" + (dull ? " dull" : ""));
            ctx.playAllByNameFromOwnBreakZoneDull(resolvedName, dull);
        };
    }

    /**
     * Parses "Remove [Self] from the game. At the beginning of your next Main Phase 1, play [Self]
     * onto the field." - 23-051L Hope, a self-blink that spans the turn boundary.
     *
     * <p>Read as one sentence pair, and it has to be: on its own the first half is an ordinary
     * remove-from-game, which is what claimed this text before - Hope left the game and never
     * came back.
     *
     * <p>The trailing "You can only use this ability during your turn." is stripped before
     * matching, as the other whole-text parsers do. It is a restriction carried as a flag on the
     * ability and gated at activation, not an effect to resolve here.
     */
    static Consumer<GameContext> tryParseRemoveSelfReturnNextMainPhase1(String text, CardData source) {
        if (source == null) return null;
        String matchOn = stripRestrictionSentences(text);
        if (matchOn.isEmpty()) matchOn = text;
        Matcher m = REMOVE_SELF_RETURN_NEXT_MAIN_PHASE_1.matcher(matchOn.trim());
        if (!m.matches()) return null;
        String name = m.group("name").trim();
        if (!name.equalsIgnoreCase(source.name())) return null;
        if (!m.group("name2").trim().equalsIgnoreCase(source.name())) return null;
        return ctx -> {
            ctx.logEntry("Effect: Remove " + name + " from the game, return at next Main Phase 1");
            ctx.removeNamedCardFromGame(name);
            // playNamedFromHoldingZoneOntoField, not the RFG-only route: by the time this fires a
            // turn later the card may have been moved on by something else, and this one looks in
            // the Break Zone too rather than silently doing nothing.
            ctx.addPendingMainPhase1Effect(later -> later.playNamedFromHoldingZoneOntoField(name));
        };
    }
}
