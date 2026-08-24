package shufflingway;

import static shufflingway.ActionResolverPatterns.*;

import static shufflingway.ActionResolver.*;

import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;

/**
 * Hand parsers split out of {@link ActionResolver}.
 *
 * <p>Bodies only: {@code ActionResolver} keeps every dispatch chain and calls these
 * through a wildcard static import, so call order -- which is load-bearing, because
 * matchers use {@code find()} -- is unchanged.
 */
final class ActionResolverHand {

	private ActionResolverHand() {}

    /**
     * Parses "If your opponent has [no | N cards or less] cards in his/her hand, [effect]."
     * The inner effect is parsed recursively; returns {@code null} if the inner effect is
     * not yet supported.
     */
    static Consumer<GameContext> tryParseConditionalOpponentHand(
            String text, CardData source, int xValue) {
        Matcher m = OPPONENT_HAND_CONDITION_PATTERN.matcher(text.trim());
        if (!m.matches()) return null;
        String nStr      = m.group("n");
        int    threshold = nStr != null ? Integer.parseInt(nStr) : 0;
        String innerText = m.group("effect").trim();
        Consumer<GameContext> inner = parse(innerText, source, xValue);
        if (inner == null) return null;
        return ctx -> {
            int hs = ctx.opponentHandSize();
            boolean condMet = (nStr != null) ? hs <= threshold : hs == 0;
            if (!condMet) return;
            ctx.logEntry("[Hand condition] opponent has " + hs
                    + " card(s) — " + innerText);
            inner.accept(ctx);
        };
    }
    static Consumer<GameContext> tryParseConditionalOpponentHandMin(
            String text, CardData source, int xValue) {
        Matcher m = OPPONENT_HAND_MIN_CONDITION_PATTERN.matcher(text.trim());
        if (!m.matches()) return null;
        int minThreshold = Integer.parseInt(m.group("n"));
        String innerText = m.group("effect").trim();
        Consumer<GameContext> inner = parse(innerText, source, xValue);
        if (inner == null) return null;
        return ctx -> {
            int hs = ctx.opponentHandSize();
            if (hs < minThreshold) return;
            ctx.logEntry("[Hand condition] opponent has " + hs + " card(s) — " + innerText);
            inner.accept(ctx);
        };
    }
    static Consumer<GameContext> tryParseDiscardConditionalElement(String text, CardData source, int xValue) {
        Matcher m = DISCARD_CONDITIONAL_ELEMENT.matcher(text.trim());
        if (!m.find()) return null;
        String elem1 = m.group("elem1").trim();
        String eff1  = m.group("eff1").trim();
        String elem2 = m.group("elem2").trim();
        String eff2  = m.group("eff2").trim();
        Consumer<GameContext> effect1 = parse(eff1, source, xValue);
        Consumer<GameContext> effect2 = parse(eff2, source, xValue);
        if (effect1 == null && effect2 == null) return null;
        final Consumer<GameContext> e1 = effect1;
        final Consumer<GameContext> e2 = effect2;
        return ctx -> {
            List<String> discarded = ctx.lastDiscardedCostCardElements();
            if (discarded.isEmpty()) {
                ctx.logEntry("Discard conditional: no cost card recorded");
                return;
            }
            // The two branches are independent conditions, not an if/else: a multi-element discard
            // (e.g. Water/Fire) is a card of both elements, so it satisfies — and triggers — both.
            boolean matched = false;
            if (discardedIsOfElement(discarded, elem1)) {
                matched = true;
                if (e1 != null) e1.accept(ctx);
                else ctx.logEntry("Discard conditional: " + elem1 + " branch not implemented");
            }
            if (discardedIsOfElement(discarded, elem2)) {
                matched = true;
                if (e2 != null) e2.accept(ctx);
                else ctx.logEntry("Discard conditional: " + elem2 + " branch not implemented");
            }
            if (!matched)
                ctx.logEntry("Discard conditional: element " + String.join("/", discarded) + " matches neither branch");
        };
    }
    static Consumer<GameContext> tryParseDiscardConditionalElementSingle(String text, CardData source, int xValue) {
        Matcher m = DISCARD_CONDITIONAL_ELEMENT_SINGLE.matcher(text.trim());
        if (!m.matches()) return null;
        String elem   = m.group("elem").trim();
        String effTxt = m.group("effect").trim();
        Consumer<GameContext> effect = parse(effTxt, source, xValue);
        if (effect == null) return null;
        return ctx -> {
            List<String> discarded = ctx.lastDiscardedCostCardElements();
            if (discarded.isEmpty()) {
                ctx.logEntry("Discard conditional: no cost card recorded");
                return;
            }
            String discardedElem = String.join("/", discarded);
            if (discardedIsOfElement(discarded, elem)) {
                ctx.logEntry("Discard conditional: discarded " + discardedElem + " card — bonus applies");
                effect.accept(ctx);
            } else {
                ctx.logEntry("Discard conditional: discarded card is " + discardedElem + ", not " + elem + " — no bonus");
            }
        };
    }
    static Consumer<GameContext> tryParseDiscardConditionalTargetLoseAbilities(String text) {
        Matcher m = DISCARD_CONDITIONAL_TARGET_LOSE_ABILITIES.matcher(text.trim());
        if (!m.matches()) return null;
        final String needElem = m.group("elem").trim();
        return ctx -> {
            List<String> discarded = ctx.lastDiscardedCostCardElements();
            if (discarded.isEmpty()) { ctx.logEntry("Discard conditional: no cost card recorded"); return; }
            String de = String.join("/", discarded);
            if (discardedIsOfElement(discarded, needElem)) {
                ctx.logEntry("Discard conditional: discarded " + de
                        + " card — chosen Forward loses all abilities until end of turn");
                for (ForwardTarget t : ctx.lastChosenTargets()) ctx.targetLoseAllAbilitiesUntilEndOfTurn(t);
            } else {
                ctx.logEntry("Discard conditional: discarded card is " + de + ", not " + needElem + " — no ability loss");
            }
        };
    }
    static Consumer<GameContext> tryParseDrawDiscardIfMultiElement(String text) {
        Matcher m = DRAW_DISCARD_IF_MULTI_ELEMENT.matcher(text.trim());
        if (!m.matches()) return null;
        int d1 = Integer.parseInt(m.group("d1"));
        int x1 = Integer.parseInt(m.group("x1"));
        int d2 = Integer.parseInt(m.group("d2"));
        int x2 = Integer.parseInt(m.group("x2"));
        return ctx -> {
            ctx.logEntry("Effect: Draw " + d1 + ", then discard " + x1);
            ctx.drawCards(d1);
            ctx.selfDiscard(x1);
            if (ctx.lastDiscardedCardIsMultiElement()) {
                ctx.logEntry("Effect: discarded a Multi-Element card — Draw " + d2 + ", then discard " + x2 + " again");
                ctx.drawCards(d2);
                ctx.selfDiscard(x2);
            } else {
                ctx.logEntry("Effect: discarded card is not Multi-Element — no repeat");
            }
        };
    }
    /**
     * Parses "if each player has no cards in their hand(s), [effect]." — the inner effect resolves
     * only when both the controller's and the opponent's hands are empty at resolution time.
     */
    static Consumer<GameContext> tryParseIfEachPlayerEmptyHand(String text, CardData source, int xValue) {
        Matcher m = IF_EACH_PLAYER_EMPTY_HAND_GATE.matcher(text.trim());
        if (!m.matches()) return null;
        Consumer<GameContext> inner = parse(m.group("effect").trim(), source, xValue);
        if (inner == null) return null;
        return ctx -> {
            int yours = ctx.yourHandSize();
            int theirs = ctx.opponentHandSize();
            if (yours == 0 && theirs == 0) {
                ctx.logEntry("Effect: both players have empty hands — condition met");
                inner.accept(ctx);
            } else {
                ctx.logEntry("Effect: hands not empty (you " + yours + ", opponent " + theirs + ") — skipped");
            }
        };
    }
    /**
     * Parses "place up to N cards from your hand at the bottom of your deck in any order. Then, draw
     * the same number of cards as were returned to your deck." Returning nothing is a legal choice,
     * in which case no cards are drawn.
     */
    static Consumer<GameContext> tryParsePlaceUpToHandToBottomThenRedraw(String text) {
        Matcher m = PLACE_UP_TO_HAND_TO_BOTTOM_THEN_REDRAW.matcher(text);
        if (!m.find()) return null;
        int max = Integer.parseInt(m.group("max"));
        return ctx -> {
            ctx.logEntry("Effect: Place up to " + max
                    + " card(s) at bottom of deck, then draw that many");
            int placed = ctx.placeUpToFromHandToBottomOfDeck(max);
            if (placed > 0) ctx.drawCards(placed);
            else            ctx.logEntry("Effect: No cards returned — no cards drawn");
        };
    }
    static Consumer<GameContext> tryParseDrawThenPlaceHandToBottom(String text) {
        Matcher m = DRAW_THEN_PLACE_HAND_TO_BOTTOM.matcher(text);
        if (!m.find()) return null;
        int drawCount  = Integer.parseInt(m.group(1));
        int placeCount = Integer.parseInt(m.group(2));
        return ctx -> {
            ctx.logEntry("Effect: Draw " + drawCount + " card(s), then place " + placeCount + " card(s) at bottom of deck");
            ctx.drawCards(drawCount);
            ctx.placeFromHandToBottomOfDeck(placeCount);
        };
    }
    static Consumer<GameContext> tryParseDrawDiscardRetriggerIfCardName(String text, CardData source) {
        Matcher m = DRAW_DISCARD_RETRIGGER_IF_CARD_NAME.matcher(text);
        if (!m.find()) return null;
        int drawCount    = Integer.parseInt(m.group("draw"));
        int discardCount = Integer.parseInt(m.group("discard"));
        String cardName  = m.group("name").trim();
        return ctx -> {
            ctx.logEntry("Effect: Draw " + drawCount + ", then discard " + discardCount);
            ctx.drawCards(drawCount);
            ctx.selfDiscard(discardCount);
            if (cardName.equalsIgnoreCase(ctx.lastDiscardedCardName())) {
                ctx.logEntry("Effect: Discarded Card Name " + cardName + " — triggering auto-ability again");
                ctx.retriggerAutoAbility(source, "beginning of attack phase");
            }
        };
    }
    static Consumer<GameContext> tryParseDrawOnePerForwardCapped(String text) {
        Matcher m = DRAW_ONE_PER_FORWARD_CAPPED.matcher(text.trim());
        if (!m.matches()) return null;
        int cap = Integer.parseInt(m.group("cap"));
        return ctx -> {
            int forwards = ctx.selfForwardCount();
            int draws = Math.min(forwards, cap);
            ctx.logEntry("Effect: Draw 1 per Forward you control (" + forwards + "), up to " + cap
                    + " → draw " + draws);
            if (draws > 0) ctx.drawCards(draws);
        };
    }
    /**
     * Composes "&lt;effect&gt;. [Then,] draw N card(s)." from the leading effect plus the draw.
     *
     * <p>Without this the trailing draw is lost: some pattern matches the leading sentences, and
     * because it matched, {@code parse()} returns before reaching the sentence-splitting fallback
     * that would have picked the draw up. Recursing through {@code parse()} for the head keeps the
     * leading effect resolving exactly as it does on its own, so this only ever adds the draw.
     *
     * <p>Returns {@code null} when the head does not parse, leaving such texts to the existing
     * chain rather than half-resolving them.
     */
    /**
     * The leading effect of "&lt;effect&gt;. [Then,] draw N card(s).", or {@code null} if the text
     * is not that shape. Shared so parse() and both reporting chains split it identically.
     *
     * <p>A use-restriction sentence often sits after the draw ("… Draw 1 card. You can only use
     * this ability once per turn." — 26-123L Zodiark), which would defeat the end-anchor.
     * Restrictions are captured as flags on the ability rather than executed here, so matching
     * against the stripped text loses nothing.
     */
    static String trailingDrawHead(String text) {
        String matchOn = stripRestrictionSentences(text);
        if (matchOn.isEmpty()) matchOn = text;

        Matcher m = TRAILING_DRAW_SUFFIX.matcher(matchOn.trim());
        if (!m.find()) return null;

        String head = m.group("head").trim();
        return head.isEmpty() ? null : head;
    }

    static Consumer<GameContext> tryParseTrailingDraw(String text, CardData source, int xValue) {
        String matchOn = stripRestrictionSentences(text);
        if (matchOn.isEmpty()) matchOn = text;

        Matcher m = TRAILING_DRAW_SUFFIX.matcher(matchOn.trim());
        if (!m.find()) return null;

        String head = m.group("head").trim();
        if (head.isEmpty()) return null;

        Consumer<GameContext> headEffect = parse(head, source, xValue);
        if (headEffect == null) return null;

        Consumer<GameContext> draw = tryParseDrawCards(m.group("draw"));
        if (draw == null) return null;

        return ctx -> {
            headEffect.accept(ctx);
            draw.accept(ctx);
        };
    }

    static Consumer<GameContext> tryParseDrawCards(String text) {
        Matcher m = DRAW_CARDS.matcher(text);
        if (!m.find()) return null;
        int drawCount = Integer.parseInt(m.group(1));
        String discardStr = m.group(2);
        if (discardStr == null) {
            return ctx -> {
                ctx.logEntry("Effect: Draw " + drawCount + " card(s)");
                ctx.drawCards(drawCount);
            };
        }
        int discardCount = Integer.parseInt(discardStr);
        return ctx -> {
            ctx.logEntry("Effect: Draw " + drawCount + ", then discard " + discardCount);
            ctx.drawCards(drawCount);
            ctx.selfDiscard(discardCount);
        };
    }
    /** Parses "Discard N card(s), then draw M card(s)" as a standalone effect. */
    static Consumer<GameContext> tryParseDiscardThenDraw(String text) {
        Matcher m = DISCARD_THEN_DRAW.matcher(text);
        if (!m.find()) return null;
        int discardCount = Integer.parseInt(m.group(1));
        int drawCount    = Integer.parseInt(m.group(2));
        return ctx -> {
            ctx.logEntry("Effect: Discard " + discardCount + ", then draw " + drawCount);
            ctx.selfDiscard(discardCount);
            ctx.drawCards(drawCount);
        };
    }
    /** Parses "Discard your hand. Then, draw N card(s)" as a standalone effect. */
    static Consumer<GameContext> tryParseDiscardHandThenDraw(String text) {
        Matcher m = DISCARD_HAND_THEN_DRAW.matcher(text);
        if (!m.find()) return null;
        int drawCount = Integer.parseInt(m.group(1));
        return ctx -> {
            ctx.logEntry("Effect: Discard hand, then draw " + drawCount);
            ctx.selfDiscardEntireHand();
            ctx.drawCards(drawCount);
        };
    }
    /** Parses "Discard your hand." as a standalone effect. */
    static Consumer<GameContext> tryParseDiscardHand(String text) {
        if (!DISCARD_HAND.matcher(text).find()) return null;
        return ctx -> {
            ctx.logEntry("Effect: Discard hand");
            ctx.selfDiscardEntireHand();
        };
    }
    /**
     * Parses "discard 1 &lt;Type&gt;." — player must discard one card of that type from hand.
     * Fizzles (marks no progress) when no eligible card is available.
     * The "you may" qualifier is handled at the AutoAbility layer before this is reached.
     */
    static Consumer<GameContext> tryParseYouMayDiscardType(String text) {
        Matcher m = DISCARD_TYPE.matcher(text);
        if (!m.find()) return null;
        String type = m.group("type");
        return ctx -> {
            ctx.logEntry("Effect: Discard 1 " + type);
            ctx.selfDiscardByType(type);
        };
    }
    /**
     * Parses "[You may] discard 1 Job [X] [from your hand]." — one card carrying that Job leaves
     * the hand.
     *
     * <p>The "you may" spelling routes to the offered discard, which is what the six printings
     * using it need: each pairs the clause with a "When you do so, …" payoff, and a discard the
     * player cannot decline would make the offer a cost rather than a choice.
     */
    static Consumer<GameContext> tryParseDiscardJobFromHand(String text) {
        Matcher m = DISCARD_JOB_FROM_HAND.matcher(text.trim());
        if (!m.matches()) return null;
        String job = m.group("job").trim();
        boolean optional = m.group("optional") != null;
        return ctx -> {
            ctx.logEntry("Effect: " + (optional ? "May discard" : "Discard") + " 1 Job " + job + " from hand");
            if (optional) ctx.mayDiscardCardOfJobFromHand(job);
            else          ctx.selfDiscardByJob(job);
        };
    }
    /** Parses "You may discard 1 &lt;element&gt; card" — player may optionally discard a card matching the element. */
    static Consumer<GameContext> tryParseDiscardElementFromHand(String text) {
        Matcher m = DISCARD_ELEMENT_FROM_HAND.matcher(text.trim());
        if (!m.matches()) return null;
        String element = m.group("element");
        return ctx -> {
            ctx.logEntry("Effect: May discard 1 " + element + " card from hand");
            ctx.selfDiscardByElement(element);
        };
    }
    /** Parses "Discard N cards." as a standalone effect. */
    static Consumer<GameContext> tryParseDiscardNCards(String text) {
        Matcher m = DISCARD_N_CARDS.matcher(text.trim());
        if (!m.matches()) return null;
        int count = Integer.parseInt(m.group("count"));
        return ctx -> {
            ctx.logEntry("Effect: Discard " + count + " card(s)");
            ctx.selfDiscard(count);
        };
    }
    /** Parses "Your opponent discards N card(s) [from his/her/their hand]" as a standalone effect. */
    static Consumer<GameContext> tryParseNameCardTypeOpponentDiscardDrawIfMatch(String text) {
        if (!NAME_CARD_TYPE_OPP_DISCARD_DRAW_IF_MATCH.matcher(text).find()) return null;
        return ctx -> {
            ctx.logEntry("Effect: Name 1 card type, opponent discards 1, draw 1 if type matches");
            ctx.nameCardTypeOpponentDiscardDrawIfMatch();
        };
    }
    static Consumer<GameContext> tryParseOpponentDiscard(String text) {
        Matcher m = OPPONENT_DISCARD.matcher(text);
        if (!m.find()) return null;
        int count = Integer.parseInt(m.group(1));
        return ctx -> {
            ctx.logEntry("Effect: Opponent discards " + count + " card(s)");
            ctx.forceOpponentDiscard(count);
        };
    }
    /** Parses "Each player discards N card(s) [from his/her/their hand]" — both players discard. */
    static Consumer<GameContext> tryParseEachPlayerDiscard(String text) {
        String stripped = stripRestrictionSentences(text);
        if (stripped.isEmpty()) return null;

        // Conditional per-player form: "each player who doesn't control N or more Forwards discards M card(s)"
        Matcher condFwdM = EACH_PLAYER_WHO_DOESNT_CONTROL_FORWARDS_DISCARD.matcher(stripped);
        if (condFwdM.matches()) {
            int min   = Integer.parseInt(condFwdM.group("min"));
            int count = Integer.parseInt(condFwdM.group("count"));
            return ctx -> {
                if (ctx.selfForwardCount() < min) {
                    ctx.logEntry("Effect: Self discards " + count + " (controls fewer than " + min + " Forwards)");
                    ctx.selfDiscard(count);
                }
                if (ctx.opponentForwardCount() < min) {
                    ctx.logEntry("Effect: Opponent discards " + count + " (controls fewer than " + min + " Forwards)");
                    ctx.forceOpponentDiscard(count);
                }
            };
        }

        // Compound form: "each player discards N. If you control [Card Name (X)], opponent discards M more."
        Matcher compM = EACH_PLAYER_DISCARD_WITH_CONDITIONAL.matcher(stripped);
        if (compM.matches()) {
            int count        = Integer.parseInt(compM.group("count"));
            String cardName  = compM.group("bracketname") != null
                               ? compM.group("bracketname") : compM.group("plainname");
            ControlCondition cc = new ControlCondition(
                    List.of(cardName), 0, false, null, null, null, null, 0, List.of());
            int extra = Integer.parseInt(compM.group("extra"));
            return ctx -> {
                ctx.logEntry("Effect: Each player discards " + count + " card(s)");
                ctx.selfDiscard(count);
                ctx.forceOpponentDiscard(count);
                if (ctx.controlConditionMet(cc)) {
                    ctx.logEntry("Effect: Opponent discards " + extra + " more (controlling " + cardName + ")");
                    ctx.forceOpponentDiscard(extra);
                }
            };
        }

        // Simple form: "each player discards N card(s) [from his/her/their hand]"
        Matcher m = EACH_PLAYER_DISCARD.matcher(stripped);
        if (!m.matches()) return null;
        int count = Integer.parseInt(m.group("count"));
        return ctx -> {
            ctx.logEntry("Effect: Each player discards " + count + " card(s)");
            ctx.selfDiscard(count);
            ctx.forceOpponentDiscard(count);
        };
    }
    /** Parses "Each player draws N card(s)." — both players draw. */
    static Consumer<GameContext> tryParseEachPlayerDraw(String text) {
        Matcher m = EACH_PLAYER_DRAW.matcher(text);
        if (!m.find()) return null;
        int count = Integer.parseInt(m.group("count"));
        return ctx -> {
            ctx.logEntry("Effect: Each player draws " + count + " card(s)");
            ctx.drawCards(count);
            ctx.drawCardsForOpponent(count);
        };
    }
    /** Parses "select N [type] in/from your Break Zone and add it to your hand." */
    static Consumer<GameContext> tryParseSelectCharacterFromBzToHand(String text) {
        Matcher m = SELECT_CHARACTER_FROM_BZ_TO_HAND.matcher(text);
        if (!m.find()) return null;
        int count = Integer.parseInt(m.group("count"));
        String tl = m.group("type").toLowerCase(java.util.Locale.ROOT);
        boolean fwds = tl.contains("forward")   || tl.contains("character");
        boolean bkps = tl.contains("backup")    || tl.contains("character");
        boolean mons = tl.contains("monster")   || tl.contains("character");
        return ctx -> {
            ctx.logEntry("Effect: Select " + count + " " + m.group("type") + "(s) from own Break Zone → hand");
            ctx.salvageCharacterFromOwnBreakZone(count, fwds, bkps, mons);
        };
    }
    /** Ceodore: "Choose 1 Card with Warp in your Break Zone. Add it to your hand." */
    static Consumer<GameContext> tryParseChooseWarpCardFromBzToHand(String text) {
        if (!CHOOSE_WARP_CARD_FROM_BZ_TO_HAND.matcher(text.trim()).find()) return null;
        return ctx -> {
            ctx.logEntry("Effect: Choose 1 Card with Warp from own Break Zone → hand");
            ctx.chooseWarpCardFromBreakZoneToHand();
        };
    }
    /** Parses "Return [name] to its owner's hand." or "Return [name] to your hand." */
    static Consumer<GameContext> tryParseReturnNamedToHand(String text) {
        Matcher m = RETURN_NAMED_TO_OWNERS_HAND.matcher(text);
        if (m.find()) {
            String named = m.group("named").trim();
            return ctx -> {
                ctx.logEntry("Effect: Return " + named + " to its owner's hand");
                ctx.returnNamedCardToOwnersHand(named);
            };
        }
        m = RETURN_NAMED_TO_YOUR_HAND_STANDALONE.matcher(text);
        if (m.find()) {
            String named = m.group("named").trim();
            return ctx -> {
                ctx.logEntry("Effect: Return " + named + " to your hand");
                ctx.returnNamedCardToYourHand(named);
            };
        }
        // Also handle "Add [name] to your hand" — used by break-zone-origin abilities.
        m = ADD_NAMED_TO_YOUR_HAND.matcher(text);
        if (m.find()) {
            String named = m.group("named").trim();
            return ctx -> {
                ctx.logEntry("Effect: Add " + named + " to your hand");
                ctx.returnNamedCardToYourHand(named);
            };
        }
        return null;
    }
    /**
     * Aemo 23-022R: "Your opponent removes all their hand from the game face down. Your opponent
     * can look at these removed cards at any time. At the end of the turn, your opponent adds them
     * back to their hand."
     *
     * <p>All three sentences resolve through one primitive. The removal and the return are the two
     * halves of a single loan and have to be scheduled together -- see
     * {@link GameContext#opponentRemovesHandFaceDownUntilEndOfTurn} -- and the middle sentence is
     * a permission rather than an effect, carried by removing the cards face down in the first
     * place.
     *
     * <p>Must precede {@code tryParseIndependentSentences}: nothing after the first sentence names
     * what it is talking about, so the splitter takes the three apart and the last one, read alone,
     * is a return of cards that were never removed.
     *
     * <p>The trailing "You can only use this ability during your turn." is stripped before
     * matching, as the other whole-text parsers do -- it is a flag on the ability, gated at
     * activation, and the pattern is anchored at both ends.
     */
    static Consumer<GameContext> tryParseOppRfgWholeHandFaceDown(String text) {
        String matchOn = stripRestrictionSentences(text);
        if (matchOn.isEmpty()) matchOn = text;
        if (!OPP_RFG_WHOLE_HAND_FACE_DOWN_RETURN_EOT.matcher(matchOn.trim()).matches()) return null;
        return ctx -> {
            ctx.logEntry("Effect: Opponent removes their whole hand from the game face down "
                    + "until the end of the turn");
            ctx.opponentRemovesHandFaceDownUntilEndOfTurn();
        };
    }
    /** Parses "Your opponent randomly discards N card(s)" as a standalone effect. */
    static Consumer<GameContext> tryParseOpponentRandomDiscard(String text) {
        Matcher m = OPPONENT_RANDOM_DISCARD.matcher(text);
        if (!m.find()) return null;
        int count = Integer.parseInt(m.group(1));
        return ctx -> {
            ctx.logEntry("Effect: Opponent randomly discards " + count + " card(s)");
            ctx.forceOpponentRandomDiscard(count);
        };
    }
    /** Parses "Your opponent draws N card(s), then randomly discards M card(s)" as a standalone effect. */
    static Consumer<GameContext> tryParseOpponentDrawThenRandomDiscard(String text) {
        Matcher m = OPPONENT_DRAW_THEN_RANDOM_DISCARD.matcher(text);
        if (!m.find()) return null;
        int drawCount    = Integer.parseInt(m.group(1));
        int discardCount = Integer.parseInt(m.group(2));
        return ctx -> {
            ctx.logEntry("Effect: Opponent draws " + drawCount + ", then randomly discards " + discardCount);
            ctx.drawCardsForOpponent(drawCount);
            ctx.forceOpponentRandomDiscard(discardCount);
        };
    }
    /** Parses "Your opponent draws N card(s)." as a standalone effect. */
    static Consumer<GameContext> tryParseOpponentDraw(String text) {
        Matcher m = OPPONENT_DRAW.matcher(text);
        if (!m.find()) return null;
        int count = Integer.parseInt(m.group(1));
        return ctx -> {
            ctx.logEntry("Effect: Opponent draws " + count);
            ctx.drawCardsForOpponent(count);
        };
    }
    /** No-op recogniser for the Light/Dark hand-discard CP grant handled as a static card property. */
    static Consumer<GameContext> tryParseLightDarkDiscardCpGrant(String text) {
        if (CardData.LIGHT_DARK_DISCARD_CP_PATTERN.matcher(text).matches()) return ctx -> {};
        return null;
    }
    /**
     * Parses "Return all [the] [element] [targets] [control] to their owners' hands."
     */
    static Consumer<GameContext> tryParseReturnAllToHand(String text) {
        Matcher m = ALL_RETURN_TO_HAND_PATTERN.matcher(text);
        if (!m.find()) return null;

        String element  = m.group("element");
        String targets  = m.group("targets");
        boolean inclForwards, inclBackups, inclMonsters;
        if (targets == null) {
            inclForwards = true; inclBackups = true; inclMonsters = true;
        } else {
            String tgtLower = targets.toLowerCase();
            inclForwards = tgtLower.contains("forward") || tgtLower.contains("character");
            inclBackups  = tgtLower.contains("backup")  || tgtLower.contains("character");
            inclMonsters = tgtLower.contains("monster") || tgtLower.contains("character");
        }

        String control       = m.group("control");
        boolean opponentOnly = control != null && !control.toLowerCase().contains("you control");
        boolean selfOnly     = control != null && control.toLowerCase().contains("you control");

        String elemLabel    = element != null ? element + " " : "";
        String tgtLabel     = targets != null ? targets : "all";
        String controlLabel = opponentOnly ? " (opponent)" : selfOnly ? " (yours)" : "";
        String logMsg       = "Return all " + elemLabel + tgtLabel + controlLabel + " to hand";

        return ctx -> {
            ctx.logEntry("Effect: " + logMsg);
            ctx.applyMassFieldEffect(GameContext.MassAction.RETURN_TO_HAND,
                    inclForwards, inclBackups, inclMonsters,
                    opponentOnly, selfOnly, element, -1, null, -1, null, null);
        };
    }
    static Consumer<GameContext> tryParseChooseAnyNumberReturnToHand(String text) {
        Matcher m = CHOOSE_ANY_NUMBER_RETURN_TO_HAND.matcher(text);
        if (!m.matches()) return null;
        String typesRaw = m.group("types").toLowerCase(java.util.Locale.ROOT);
        boolean inclForwards = typesRaw.contains("forward") || typesRaw.contains("character");
        boolean inclBackups  = typesRaw.contains("backup")  || typesRaw.contains("character");
        boolean inclMonsters = typesRaw.contains("monster") || typesRaw.contains("character");
        String controlRaw    = m.group("control");
        boolean opponentOnly = controlRaw != null && !controlRaw.toLowerCase(java.util.Locale.ROOT).contains("you control");
        boolean selfOnly     = controlRaw != null &&  controlRaw.toLowerCase(java.util.Locale.ROOT).contains("you control");
        String typeLabel     = m.group("types");
        String controlLabel  = opponentOnly ? " (opponent's)" : selfOnly ? " (yours)" : "";
        return ctx -> {
            ctx.logEntry("Effect: Choose any number of " + typeLabel + controlLabel + " — return to hand");
            ctx.chooseAnyNumberReturnToHand(inclForwards, inclBackups, inclMonsters, opponentOnly, selfOnly);
        };
    }
    /**
     * Discard-cost sibling of {@link #tryParseCancelChosenTargetUnlessPay}: parses "If your opponent
     * doesn't discard N card(s), cancel its/their effect(s)." and cancels the in-progress selection
     * unless the opponent discards the full number of cards from hand.
     */
    static Consumer<GameContext> tryParseCancelChosenTargetUnlessDiscard(String text) {
        Matcher m = CANCEL_CHOSEN_TARGET_UNLESS_DISCARD.matcher(text.trim());
        if (!m.find()) return null;
        int count = Integer.parseInt(m.group("count"));
        return ctx -> {
            ctx.logEntry("Effect: cancel unless opponent discards " + count + " card(s)");
            ctx.cancelChosenSelectionUnlessOpponentDiscards(count);
        };
    }
    static Consumer<GameContext> tryParseCastSummonFromHandDiscounted(String text) {
        Matcher m = CAST_SUMMON_FROM_HAND_DISCOUNTED.matcher(text.trim());
        if (!m.find()) return null;
        final int amount = Integer.parseInt(m.group("amount"));
        return ctx -> {
            ctx.logEntry("Effect: Cast a Summon from hand (cost reduced by " + amount + ", floor 1)");
            ctx.castSummonFromHandDiscounted(amount);
        };
    }
    static Consumer<GameContext> tryParseCastSummonFromHandFree(String text, int xValue) {
        Matcher m = CAST_SUMMON_FROM_HAND_FREE.matcher(text.trim());
        if (!m.find()) return null;
        String costStr    = m.group("cost");
        String counterRaw = m.group("counterName");
        boolean returnToHand = m.group("returnToHand") != null;
        // A counter-scaled ceiling arrives the same way a literal "X" does: the activation path
        // reads the counter count off the source card into xValue before the effect resolves.
        final int maxCost;
        if (counterRaw != null) {
            maxCost = xValue;
        } else if (costStr == null) {
            maxCost = -1;
        } else if (costStr.equalsIgnoreCase("X")) {
            maxCost = xValue;
        } else {
            maxCost = Integer.parseInt(costStr);
        }
        final String counterName = counterRaw != null ? counterRaw.trim() : null;
        String excludeRaw = m.group("excludeelems");
        String excludeElements = excludeRaw != null
                ? excludeRaw.trim().replaceAll("(?i)\\s+or\\s+", "|") : null;
        return ctx -> {
            String costDesc = maxCost < 0 ? "any cost" : "cost " + maxCost + " or less";
            ctx.logEntry("Effect: Cast 1 Summon (" + costDesc
                    + (counterName != null ? ", from " + counterName + " Counters" : "")
                    + ") from hand for free"
                    + (excludeElements != null ? " (not " + excludeElements + ")" : "")
                    + (returnToHand ? " (return to hand after use)" : ""));
            ctx.castSummonFromHandFree(maxCost, returnToHand, excludeElements);
        };
    }
    /**
     * Parses "play any number of Job X from your hand onto the field".
     */
    static Consumer<GameContext> tryParsePlayAnyNumberFromHand(String text, CardData source) {
        Matcher m = PLAY_ANY_NUMBER_FROM_HAND_PATTERN.matcher(text.trim());
        if (!m.find()) return null;

        String jobFilter = m.group("jobnm") != null ? m.group("jobnm").trim() : null;
        String targets   = m.group("targets");
        boolean anyType  = targets == null;
        String tgtLower  = anyType ? "" : targets.toLowerCase();
        boolean inclForwards = anyType || tgtLower.contains("forward") || tgtLower.contains("character");
        boolean inclBackups  = anyType || tgtLower.contains("backup")  || tgtLower.contains("character");
        boolean inclMonsters = anyType || tgtLower.contains("monster") || tgtLower.contains("character");

        final String fJob = jobFilter;
        return ctx -> {
            ctx.logEntry("Effect: Play any number of" + (fJob != null ? " Job " + fJob : "") + " from hand → field");
            ctx.playAnyNumberFromHand(inclForwards, inclBackups, inclMonsters, fJob, null, null, null);
        };
    }
    /**
     * Parses "Play 1 [type] of cost N [or less|more] from [your|his/her|their] hand onto the field".
     *
     * <p>Resolves for whichever player the context belongs to, so the possessive carries no meaning
     * here: on "your opponent may play 1 … from his/her hand" (1-060H Leon, 12-071R Shadow Lord)
     * {@code AutoAbility.opponentMay} has already flipped the context to the opponent.
     */
    static Consumer<GameContext> tryParsePlayFromHand(String text, CardData source, int xValue) {
        // "each player may play …" is two plays, one per player; this reading resolves a single
        // play. tryParseEachPlayerMayPlayFromHand takes that wording instead.
        if (EACH_PLAYER_MAY_PLAY_FROM_HAND.matcher(text).find()) return null;
        return parsePlayFromHand(text, source, xValue, false);
    }

    /**
     * Parses "each player may play 1 [type] [of cost N or less] from their hand onto the field"
     * — 28-051R Black Cat.
     *
     * <p>Both players get the offer, on their own hand and their own field, so this cannot go
     * through {@link #tryParsePlayFromHand}: that one resolves for whichever player the context
     * belongs to, which would silently drop the other player's half of the effect.
     *
     * <p>Everything after "each player may" is the ordinary single-player wording, so the filters
     * are read by the same pattern rather than a second copy of it.
     */
    static Consumer<GameContext> tryParseEachPlayerMayPlayFromHand(String text, CardData source, int xValue) {
        Matcher g = EACH_PLAYER_MAY_PLAY_FROM_HAND.matcher(text);
        if (!g.find()) return null;
        return parsePlayFromHand(text.substring(g.end()), source, xValue, true);
    }

    /**
     * Shared body of the two readings above.
     *
     * @param eachPlayer {@code true} to dispatch to the both-players primitive rather than the
     *                   single-player one; the filters are parsed identically either way.
     */
    private static Consumer<GameContext> parsePlayFromHand(String text, CardData source, int xValue,
            boolean eachPlayer) {
        Matcher m = PLAY_FROM_HAND_PATTERN.matcher(text);
        if (!m.find()) return null;

        // --- Resolve filter groups ---
        String jobFilter      = null;
        String cardNameFilter = null;
        String categoryFilter = m.group("category") != null ? m.group("category").trim() : null;

        String writtenCardName = m.group("cardname");
        String writtenJob      = m.group("jobnm");
        String writtenJobOnly  = m.group("jobnmonly");
        String writtenJobOr    = m.group("jobnmor");
        String writtenCnameOr  = m.group("cnameor");
        if (writtenCardName != null) {
            cardNameFilter = writtenCardName.trim();
        } else if (writtenJobOr != null) {
            jobFilter      = writtenJobOr.trim();
            cardNameFilter = writtenCnameOr != null ? writtenCnameOr.trim() : null;
        } else if (writtenJob != null) {
            jobFilter = writtenJob.trim();
        } else if (writtenJobOnly != null) {
            jobFilter = writtenJobOnly.trim();
        } else {
            String f1 = m.group("f1");
            String f2 = m.group("f2");
            if (f1 != null) {
                Matcher jm = JOB_BRACKET_PATTERN.matcher(f1);
                Matcher nm = CARD_NAME_BRACKET_PATTERN.matcher(f1);
                if      (jm.find()) jobFilter      = jm.group(1).trim();
                else if (nm.find()) cardNameFilter = nm.group(1).trim();
            }
            if (f2 != null) {
                Matcher jm = JOB_BRACKET_PATTERN.matcher(f2);
                Matcher nm = CARD_NAME_BRACKET_PATTERN.matcher(f2);
                if (jm.find()) {
                    String j2 = jm.group(1).trim();
                    jobFilter = jobFilter != null ? jobFilter + "|" + j2 : j2;
                } else if (nm.find()) {
                    cardNameFilter = nm.group(1).trim();
                }
            }
        }

        // --- Resolve type ---
        String  targets      = m.group("targets");
        boolean hasFilter    = jobFilter != null || cardNameFilter != null || categoryFilter != null;
        if (targets == null && !hasFilter) return null;
        String  tgtLower     = targets != null ? targets.toLowerCase() : "";
        boolean inclForwards = tgtLower.isEmpty() || tgtLower.contains("forward") || tgtLower.contains("character");
        boolean inclBackups  = tgtLower.isEmpty() || tgtLower.contains("backup")  || tgtLower.contains("character");
        boolean inclMonsters = tgtLower.isEmpty() || tgtLower.contains("monster") || tgtLower.contains("character");

        // --- Resolve cost ---
        String dynFilterRaw = m.group("dynfilter");
        boolean isDynamic   = dynFilterRaw != null;
        String dynJob = null, dynName = null;
        if (isDynamic) {
            Matcher djm = java.util.regex.Pattern.compile(
                "(?i)Job\\s+(.+?)(?:\\s+and/or\\s+|$)").matcher(dynFilterRaw);
            Matcher dnm = java.util.regex.Pattern.compile(
                "(?i)Card\\s+Name\\s+(\\S+(?:\\s+\\([^)]+\\))?)").matcher(dynFilterRaw);
            if (djm.find()) dynJob  = djm.group(1).trim();
            if (dnm.find()) dynName = dnm.group(1).trim();
        }

        String costStr  = m.group("cost");
        String costAlt  = m.group("costalt");
        int    costVal  = -1;
        String costCmp  = null;
        int    costVal2 = -1;
        if (!isDynamic && costStr != null) {
            costVal = costStr.equalsIgnoreCase("X") ? xValue : Integer.parseInt(costStr);
            if (costAlt != null) {
                if (costAlt.equalsIgnoreCase("less") || costAlt.equalsIgnoreCase("more"))
                    costCmp = costAlt.toLowerCase();
                else
                    costVal2 = Integer.parseInt(costAlt);  // "cost 3 or 4"
            }
        }

        String  excludeName = m.group("excludename") != null ? m.group("excludename").trim() : null;
        boolean entersDull  = m.group("dull") != null;

        // --- Element filter ---
        String elemsRaw     = m.group("preelems");
        String elementFilter = elemsRaw != null
                ? elemsRaw.trim().replaceAll("(?i)\\s+or\\s+", "|") : null;

        // Build log label
        StringBuilder filterDesc = new StringBuilder();
        if (elementFilter  != null) filterDesc.append(" [").append(elemsRaw).append("]");
        if (jobFilter      != null) filterDesc.append(" [Job ").append(jobFilter).append("]");
        if (cardNameFilter != null) filterDesc.append(" [Name ").append(cardNameFilter).append("]");
        if (categoryFilter != null) filterDesc.append(" [Cat ").append(categoryFilter).append("]");
        String tgtLabel  = targets != null ? " " + targets : "";
        String costLabel = isDynamic ? " of cost ≤count[" + dynFilterRaw + "]"
                         : costVal2 >= 0 ? " of cost " + costVal + " or " + costVal2
                         : costVal >= 0  ? " of cost " + costVal + (costCmp != null ? " or " + costCmp : "") : "";
        String exclLabel = excludeName != null ? " excl." + excludeName : "";
        String dullLabel = entersDull ? " dull" : "";

        final String fJob = jobFilter, fName = cardNameFilter, fCat = categoryFilter;
        final String fElem = elementFilter;
        final String fExclude = excludeName, fDynJob = dynJob, fDynName = dynName;
        final int fCostVal = costVal, fCostVal2 = costVal2;
        final String fCostCmp = costCmp;
        final boolean fEntersDull = entersDull;
        String rawExcludeElem = m.group("excludeelem");
        final String fExcludeElem = rawExcludeElem != null ? rawExcludeElem.trim() : null;
        final boolean fSuppressAuto = ITS_AUTO_ABILITY_WILL_NOT_TRIGGER.matcher(text).find();
        final String fWithTrait = m.group("trait");

        return ctx -> {
            int resolvedCost = fCostVal;
            String resolvedCmp = fCostCmp;
            if (isDynamic) {
                int n;
                if (fDynJob != null && fDynName != null) {
                    n = ctx.countSelfFieldCards(true, true, true, fDynJob, null)
                      + ctx.countSelfFieldCards(true, true, true, null, fDynName)
                      - ctx.countSelfFieldCards(true, true, true, fDynJob, fDynName);
                } else {
                    n = ctx.countSelfFieldCards(true, true, true, fDynJob, fDynName);
                }
                resolvedCost = n;
                resolvedCmp  = "less";
            }
            ctx.logEntry("Effect: " + (eachPlayer ? "Each player may play 1" : "Play 1")
                    + filterDesc + tgtLabel + costLabel + exclLabel + dullLabel + " from hand"
                    + (fSuppressAuto ? " (no ETF auto-ability)" : ""));
            if (eachPlayer) {
                ctx.eachPlayerMayPlayCharacterFromHand(inclForwards, inclBackups, inclMonsters,
                        resolvedCost, resolvedCmp, fCostVal2,
                        fJob, fName, fCat, fElem, fExclude, fEntersDull, fExcludeElem, fSuppressAuto, fWithTrait);
            } else {
                ctx.playCharacterFromHand(inclForwards, inclBackups, inclMonsters,
                        resolvedCost, resolvedCmp, fCostVal2,
                        fJob, fName, fCat, fElem, fExclude, fEntersDull, fExcludeElem, fSuppressAuto, fWithTrait);
            }
        };
    }
    static Consumer<GameContext> tryParseOpponentMillIfSameElementDraw(String text) {
        Matcher m = OPPONENT_MILL_IF_SAME_ELEMENT_DRAW.matcher(text);
        if (!m.find()) return null;
        String countStr = m.group("count");
        if (countStr == null) countStr = m.group("count2");
        int mill = countStr != null ? Integer.parseInt(countStr) : 2;
        int draw = Integer.parseInt(m.group("draw"));
        return ctx -> {
            ctx.logEntry("Effect: Opponent mills " + mill + " — draw " + draw + " if all same element");
            ctx.opponentMillIfSameElementDraw(mill, draw);
        };
    }
    /**
     * Parses "[CardName] cannot be returned to its owner's hand by [your] opponent's Summons or
     * abilities." Enforcement is handled in the {@link GameContextImpl} return-to-hand wrappers
     * via {@link #hasCannotBeReturnedToHandByOppFieldAbility}.
     */
    static Consumer<GameContext> tryParseCannotBeReturnedToHandOpp(String text, CardData source) {
        Matcher m = STANDALONE_NAMED_CANNOT_BE_RETURNED_TO_HAND_OPP.matcher(text);
        if (!m.find() || source == null) return null;
        String nm = m.group("name").trim();
        if (!nm.equalsIgnoreCase(source.name())) return null;
        return ctx -> ctx.logEntry("Field ability: " + nm + " cannot be returned to its owner's hand by opponent's Summons or abilities");
    }
    /**
     * Parses "Characters you control cannot be returned to their owner's hand by your opponent's
     * Summons or abilities." Enforcement is handled in the {@link GameContextImpl} return-to-hand
     * wrappers via {@link #hasCharactersCannotBeReturnedFieldAbility}.
     */
    static Consumer<GameContext> tryParseCharactersCannotBeReturnedToHandOpp(String text) {
        if (!STANDALONE_CHARACTERS_CANNOT_BE_RETURNED_TO_HAND_OPP.matcher(text).find()) return null;
        return ctx -> ctx.logEntry("Field ability: Characters you control cannot be returned to their owner's hand by opponent's Summons or abilities");
    }
    static Consumer<GameContext> tryParseCounterScaleLookAddToHand(String text, int xValue) {
        Matcher m = LOOK_COUNTER_SCALE_ADD_TO_HAND_REST_BOTTOM.matcher(text);
        if (!m.find()) return null;
        final int    count       = xValue;
        final String counterName = m.group("counterName").trim();
        return ctx -> {
            if (count <= 0) {
                ctx.logEntry("Effect: " + counterName + " Counter look — 0 counters, nothing to do");
                return;
            }
            ctx.logEntry("Effect: Look at top " + count + " card(s) (" + counterName + " Counters) — add 1 to hand, shuffle rest to bottom");
            ctx.lookAtTopDeck(new LookConfig(count, LookConfig.LookAction.ADD_TO_HAND_REST_BOTTOM));
        };
    }
    /** Parses the "cards removed by [CardName]'s ability" retrieval wordings. */
    static Consumer<GameContext> tryParseAddRemovedBySourceAbilityToHand(String text, CardData source) {
        if (source == null) return null;
        Matcher m = ADD_REMOVED_BY_SOURCE_ABILITY_TO_HAND.matcher(text.trim());
        if (!m.matches()) return null;
        String named = m.group("name").trim();
        if (!named.equalsIgnoreCase(source.name()) && !isSelfReference(named)) return null;
        boolean all      = m.group("all")  != null;
        boolean restToBz = m.group("rest") != null;
        return ctx -> {
            ctx.logEntry("Effect: Add " + (all ? "all cards" : "1 card") + " removed by "
                    + source.name() + "'s ability to hand" + (restToBz ? ", rest to Break Zone" : ""));
            ctx.addCardsRemovedBySourceToHand(source, all ? Integer.MAX_VALUE : 1);
            if (restToBz) ctx.putCardsRemovedBySourceIntoBreakZone(source);
        };
    }
    /** Parses "add N card(s) removed by the previous effect to your hand. [Then, …]" (Libroarian 8-084R). */
    static Consumer<GameContext> tryParseAddRemovedByPreviousEffectToHand(String text, CardData source) {
        if (source == null) return null;   // "the previous effect" is this card's own earlier ability
        Matcher m = ADD_REMOVED_BY_PREVIOUS_EFFECT_TO_HAND.matcher(text.trim());
        if (!m.matches()) return null;
        int count = Integer.parseInt(m.group("count"));
        String breakName = m.group("name");
        if (breakName != null) {
            String n = breakName.trim();
            if (!n.equalsIgnoreCase(source.name()) && !isSelfReference(n)) return null;
        }
        boolean breakWhenEmpty = breakName != null;
        return ctx -> {
            ctx.logEntry("Effect: Add " + count + " card(s) removed by " + source.name() + " to hand");
            int remaining = ctx.addCardsRemovedBySourceToHand(source, count);
            if (breakWhenEmpty && remaining == 0) {
                ctx.logEntry("Effect: no removed cards left — " + source.name() + " is broken");
                ctx.breakSourceCard(source);
            }
        };
    }
    static Consumer<GameContext> tryParseBackupCpDraw(String text) {
        Matcher m = BACKUP_CP_DRAW.matcher(text);
        if (!m.find()) return null;
        int count = Integer.parseInt(m.group("count"));
        return ctx -> {
            if (ctx.castWasPaidByBackupsOnly()) {
                ctx.logEntry("BackupCpDraw — CP was only from Backups, draw " + count);
                ctx.drawCards(count);
            }
        };
    }
    /**
     * Parses "Choose 1 Summon in your Break Zone. Add it to your hand. During this turn,
     * the cost required to cast your next Summon is reduced by N [(it cannot become 0)]."
     */
    static Consumer<GameContext> tryParseChooseSummonFromBzToHandWithCostReduction(String text) {
        Matcher m = CHOOSE_SUMMON_FROM_BZ_TO_HAND_WITH_COST_REDUCTION.matcher(text);
        if (!m.find()) return null;
        int amount = Integer.parseInt(m.group("amount"));
        boolean floorAtOne = m.group("floorone") != null;
        CostReductionModifier modifier = new CostReductionModifier(
                amount, floorAtOne, true,
                false, false, false, true,
                null, null, null, null, false);
        String logDesc = "Choose 1 Summon from own Break Zone → hand; next Summon costs "
                + amount + " less" + (floorAtOne ? " (min 1)" : "");
        return ctx -> {
            ctx.logEntry("Effect: " + logDesc);
            ctx.chooseSummonFromOwnBzToHand();
            ctx.applyNextCastCostReduction(modifier);
        };
    }
    /** Parses "Choose N Summons in your Break Zone. Add 1 of them to your hand, and remove the rest from the game." */
    static Consumer<GameContext> tryParseChooseNSummonsBzPickOneHandRestRfg(String text) {
        Matcher m = CHOOSE_N_SUMMONS_BZ_PICK_ONE_HAND_REST_RFG.matcher(text);
        if (!m.find()) return null;
        int total = Integer.parseInt(m.group("total"));
        return ctx -> {
            ctx.logEntry("Effect: Choose " + total + " Summons from own BZ — add 1 to hand, remove rest");
            ctx.chooseSummonsFromBzPickOneToHandRestRfg(total);
        };
    }
    /**
     * Parses "Select 1 of your Card Name X removed from the game. Add it to your hand."
     * (Feral Chaos B-010).
     *
     * <p>The name is matched through {@code CardFilters.meetsCardNameFilter} at resolution, so a
     * card that is "also Card Name X in all situations" qualifies — which is the whole point here:
     * Feral Chaos is itself a Chaos, and is normally the card it retrieves.
     */
    static Consumer<GameContext> tryParseSelectNamedFromRfgToHand(String text) {
        Matcher m = SELECT_NAMED_FROM_RFG_TO_HAND.matcher(text.trim());
        if (!m.matches()) return null;
        String cardName = m.group("name").trim();
        return ctx -> {
            ctx.logEntry("Effect: Select 1 Card Name " + cardName + " removed from the game → hand");
            ctx.chooseNamedFromOwnRfgToHand(cardName);
        };
    }
}
