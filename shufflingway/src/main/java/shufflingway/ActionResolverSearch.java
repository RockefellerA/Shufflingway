package shufflingway;

import static shufflingway.ActionResolverPatterns.*;

import static shufflingway.ActionResolver.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Search parsers split out of {@link ActionResolver}.
 *
 * <p>Bodies only: {@code ActionResolver} keeps every dispatch chain and calls these
 * through a wildcard static import, so call order -- which is load-bearing, because
 * matchers use {@code find()} -- is unchanged.
 */
final class ActionResolverSearch {

	private ActionResolverSearch() {}

    static Consumer<GameContext> tryParseRfpAllFwdExceptElementsThenTwiceDeck(String text) {
        Matcher m = RFP_ALL_FWD_EXCEPT_ELEMENTS_THEN_TWICE_DECK.matcher(text);
        if (!m.find()) return null;
        String elem1 = m.group("elem1");
        String elem2 = m.group("elem2");
        return ctx -> {
            ctx.logEntry("Effect: Remove from game all Forwards other than " + elem1 + " and " + elem2);
            List<ForwardTarget> toRemove = new ArrayList<>();
            for (int i = 0; i < ctx.p1ForwardCount(); i++) {
                CardData fwd = ctx.p1Forward(i);
                if (!ctx.fieldCardHasElement(fwd, elem1) && !ctx.fieldCardHasElement(fwd, elem2))
                    toRemove.add(new ForwardTarget(true, i, ForwardTarget.CardZone.FORWARD));
            }
            for (int i = 0; i < ctx.p2ForwardCount(); i++) {
                CardData fwd = ctx.p2Forward(i);
                if (!ctx.fieldCardHasElement(fwd, elem1) && !ctx.fieldCardHasElement(fwd, elem2))
                    toRemove.add(new ForwardTarget(false, i, ForwardTarget.CardZone.FORWARD));
            }
            sortedByIdxDesc(toRemove, true) .forEach(ctx::removeTargetFromGame);
            sortedByIdxDesc(toRemove, false).forEach(ctx::removeTargetFromGame);
            int deckRfp = toRemove.size() * 2;
            if (deckRfp > 0) {
                ctx.logEntry("Effect: Remove top " + deckRfp + " card(s) of deck from game (2 × " + toRemove.size() + " removed)");
                ctx.removeTopCardsOfDeckFromGame(deckRfp, null);   // nothing refers back to these
            }
        };
    }
    /** Parses "You may reveal 1 [Element] card from your hand." */
    static Consumer<GameContext> tryParseMayRevealElementFromHand(String text) {
        Matcher m = YOU_MAY_REVEAL_ELEMENT_FROM_HAND.matcher(text.trim());
        if (!m.matches()) return null;
        String element = m.group("element");
        return ctx -> {
            ctx.logEntry("Effect: May reveal 1 " + element + " card from hand");
            ctx.mayRevealCardByElementFromHand(element);
        };
    }
    /** Parses "Your opponent randomly places N card(s) from their hand at the bottom of their deck." */
    static Consumer<GameContext> tryParseOpponentRandomHandToBottomDeck(String text) {
        Matcher m = OPPONENT_RANDOM_HAND_TO_BOTTOM_DECK.matcher(text);
        if (!m.find()) return null;
        int count = Integer.parseInt(m.group(1));
        return ctx -> {
            ctx.logEntry("Effect: Opponent randomly places " + count + " hand card(s) at bottom of their deck");
            ctx.forceOpponentRandomHandToBottomOfDeck(count);
        };
    }
    /**
     * Parses "Your opponent reveals their hand. Select N card(s) in their hand.
     * Your opponent removes it from the game."
     */
    static Consumer<GameContext> tryParseRevealSelectHandRfp(String text) {
        Matcher m = REVEAL_SELECT_HAND_RFP.matcher(text);
        if (!m.find()) return null;
        int count = Integer.parseInt(m.group(1));
        return ctx -> {
            ctx.logEntry("Effect: Opponent reveals hand — select " + count + " to remove from game");
            ctx.selectFromOpponentHandAndRfp(count);
        };
    }
    /**
     * Parses "Your opponent reveals their hand. Select up to N card(s) in their hand.
     * Your opponent removes them from the game. At the end of your opponent's turn, add them
     * to their owner's hand." (29-054R Great Malboro).
     *
     * <p>Must precede {@link #tryParseRevealSelectHandRfp}: the two share a prefix and that one
     * would claim the text, dropping the delayed return and making the removal permanent.
     */
    static Consumer<GameContext> tryParseRevealSelectHandRfpUntilEndOfOppTurn(String text) {
        Matcher m = REVEAL_SELECT_HAND_RFP_UNTIL_END_OF_OPP_TURN.matcher(text);
        if (!m.find()) return null;
        int count = Integer.parseInt(m.group("count"));
        return ctx -> {
            ctx.logEntry("Effect: Opponent reveals hand — select up to " + count
                    + " to remove from game until the end of their turn");
            ctx.selectFromOpponentHandRfpUntilEndOfOpponentTurn(count);
        };
    }
    /**
     * Parses "Your opponent reveals their hand. Select N [restriction] card(s) from their hand.
     * Your opponent discards this card." — the discard sibling of
     * {@link #tryParseRevealSelectHandRfp}.
     */
    static Consumer<GameContext> tryParseRevealSelectHandDiscard(String text) {
        Matcher m = REVEAL_SELECT_HAND_DISCARD.matcher(text);
        if (!m.find()) return null;
        int count = Integer.parseInt(m.group("count"));
        String cardType = m.group("cardtype");
        String costStr  = m.group("cost");
        String excluded = m.group("excl");

        Predicate<CardData> eligible = null;
        String desc = "card";
        if (cardType != null) {
            String type = cardType.replaceFirst("(?i)s$", "");
            // "Character" is not a card type of its own — it is the Forward/Backup/Monster union.
            eligible = type.equalsIgnoreCase("Character")
                    ? c -> !"Summon".equalsIgnoreCase(c.type())
                    : c -> type.equalsIgnoreCase(c.type());
            desc = type;
        } else if (costStr != null) {
            int minCost = Integer.parseInt(costStr);
            eligible = c -> c.cost() >= minCost;
            desc = "card of cost " + minCost + " or more";
        } else if (excluded != null) {
            eligible = c -> !excluded.equalsIgnoreCase(c.type());
            desc = "card other than a " + excluded;
        }
        final Predicate<CardData> filter = eligible;
        final String filterDesc = desc;
        return ctx -> {
            ctx.logEntry("Effect: Opponent reveals hand — select " + count + " " + filterDesc
                    + " to discard");
            ctx.selectFromOpponentHandAndDiscard(count, filter, filterDesc);
        };
    }
    /** Parses "Opponent reveals hand. You may select 1 → opponent discards it and draws 1." */
    static Consumer<GameContext> tryParseRevealHandOptPickDiscardOppDraw(String text) {
        if (!REVEAL_HAND_OPT_PICK_DISCARD_OPP_DRAW.matcher(text).find()) return null;
        return ctx -> {
            ctx.logEntry("Effect: Opponent reveals hand — optionally select 1 to discard, opponent draws 1");
            ctx.revealHandOptPickDiscardOpponentDraws();
        };
    }
    /** Parses "Opponent reveals hand. You may select 1 → remove from game, opponent draws 1." */
    static Consumer<GameContext> tryParseRevealHandOptPickRfpOppDraw(String text) {
        if (!REVEAL_HAND_OPT_PICK_RFP_OPP_DRAW.matcher(text).find()) return null;
        return ctx -> {
            ctx.logEntry("Effect: Opponent reveals hand — optionally select 1 to RFP, opponent draws 1");
            ctx.revealHandOptPickRfpOpponentDraws();
        };
    }
    static Consumer<GameContext> tryParsePutSourceToBottomOfDeck(String text, CardData source) {
        if (source == null) return null;
        Matcher m = PUT_SOURCE_TO_BOTTOM_OF_DECK.matcher(text.trim());
        if (!m.matches()) return null;
        if (!m.group("name").trim().equalsIgnoreCase(source.name())) return null;
        return ctx -> {
            ctx.logEntry("Effect: " + source.name() + " → bottom of its owner's deck");
            ctx.putSourceToBottomOfDeck(source);
        };
    }
    /**
     * The deck-top twin of {@link #tryParsePutSourceToBottomOfDeck} — "Put [Self] on top of its
     * owner's deck." (Fiona 16-118C). Kept adjacent so the pair stays visible as one decision.
     */
    static Consumer<GameContext> tryParsePutSourceOnTopOfDeck(String text, CardData source) {
        if (source == null) return null;
        Matcher m = PUT_SOURCE_ON_TOP_OF_DECK.matcher(text.trim());
        if (!m.matches()) return null;
        if (!m.group("name").trim().equalsIgnoreCase(source.name())) return null;
        return ctx -> {
            ctx.logEntry("Effect: " + source.name() + " → top of its owner's deck");
            ctx.putSourceOnTopOfDeck(source);
        };
    }
    static Consumer<GameContext> tryParseShuffleThenRevealPlayNamedRestBottom(String text, CardData source) {
        Matcher m = SHUFFLE_THEN_REVEAL_PLAY_NAMED_REST_BOTTOM.matcher(text.trim());
        if (!m.matches()) return null;
        int n           = Integer.parseInt(m.group("n"));
        String cardName = m.group("cardname").trim();
        if (source != null && !cardName.equalsIgnoreCase(source.name())) return null;
        return ctx -> {
            ctx.shuffleDeck();
            ctx.revealTopNPlayNamedOntoFieldRestBottom(n, cardName);
        };
    }
    static Consumer<GameContext> tryParseRevealPlayNamedWithMaxCostRestBottom(String text) {
        Matcher m = REVEAL_PLAY_NAMED_MAX_COST_REST_BOTTOM.matcher(text.trim());
        if (!m.matches()) return null;
        int n           = Integer.parseInt(m.group("n"));
        String cardName = m.group("cardname").trim();
        int maxCost     = Integer.parseInt(m.group("maxcost"));
        return ctx -> ctx.revealTopNPlayNamedWithMaxCostOntoFieldRestBottom(n, cardName, maxCost);
    }
    /**
     * Parses "Reveal the top N cards of your deck. Play as many Job [J] [Type]s as you want with a
     * total cost of [C] or less among them onto the field and return the other cards to the bottom
     * of your deck in any order." — Warrior of Light 10-065L.
     */
    static Consumer<GameContext> tryParseRevealPlayAsManyJobTypeTotalCostRestBottom(String text) {
        Matcher m = REVEAL_PLAY_AS_MANY_JOB_TYPE_TOTAL_COST_REST_BOTTOM.matcher(text.trim());
        if (!m.matches()) return null;
        int    n         = Integer.parseInt(m.group("n"));
        String job       = m.group("job").trim();
        String type      = cap(m.group("type").trim());
        int    totalCost = Integer.parseInt(m.group("totalcost"));
        return ctx -> {
            ctx.logEntry("Effect: Reveal top " + n + " — play any number of Job " + job + " "
                    + type + "s totalling cost " + totalCost + " or less");
            ctx.revealTopNPlayAnyJobTypeWithTotalCostOntoFieldRestBottom(n, job, type, totalCost);
        };
    }
    static Consumer<GameContext> tryParseRevealPlayNamedOrJobMaxCostRestBottom(String text) {
        Matcher m = REVEAL_PLAY_NAMED_OR_JOB_MAX_COST_REST_BOTTOM.matcher(text.trim());
        if (!m.matches()) return null;
        int n           = Integer.parseInt(m.group("n"));
        int max         = Integer.parseInt(m.group("max"));
        String cardName = m.group("cardname").trim();
        String job      = m.group("job").trim();
        int maxCost     = Integer.parseInt(m.group("maxcost"));
        return ctx -> ctx.revealTopNPlayUpToNamedOrJobWithMaxCostOntoFieldRestBottom(n, max, cardName, job, maxCost);
    }
    /**
     * Parses "Search for up to 1 [half] and up to 1 [half] and play them onto the field" —
     * 11-124H Relm (two costs) and 19-109H Cherukiki (two card names).
     *
     * <p>Runs as two searches of the one deck, in printed order. Each is its own prompt, which is
     * also what makes "up to" work without a count: declining a search dialog takes nothing, and
     * the second search still happens.
     */
    static Consumer<GameContext> tryParseDualSearchPlayOntoField(String text) {
        Matcher m = DUAL_SEARCH_PLAY_ONTO_FIELD.matcher(text.trim());
        if (!m.matches()) return null;
        SearchHalf first  = SearchHalf.of(m.group("name1"), m.group("type1"), m.group("cost1"));
        SearchHalf second = SearchHalf.of(m.group("name2"), m.group("type2"), m.group("cost2"));
        return ctx -> {
            ctx.logEntry("Effect: Search deck for up to 1 " + first.label()
                    + " and up to 1 " + second.label() + " → field");
            first.search(ctx);
            second.search(ctx);
        };
    }

    /**
     * One half of {@link #tryParseDualSearchPlayOntoField} — a card name, or a type with an exact
     * cost. Exactly one of {@code cardName} and {@code type} is set, which is what the two
     * alternatives in the pattern guarantee.
     */
    private record SearchHalf(String cardName, String type, int cost) {

        static SearchHalf of(String cardName, String typeRaw, String costRaw) {
            if (cardName != null) return new SearchHalf(cardName.trim(), null, -1);
            String t = typeRaw.toLowerCase(java.util.Locale.ROOT).replaceAll("s$", "");
            return new SearchHalf(null, t, Integer.parseInt(costRaw));
        }

        String label() {
            return cardName != null ? "Card Name " + cardName : type + " of cost " + cost;
        }

        /** A name search spans every card type, the way the single-pool parser reads a bare name. */
        void search(GameContext ctx) {
            boolean anyChar = cardName != null || "character".equals(type);
            ctx.searchDeckForCard(anyChar || "forward".equals(type), anyChar || "backup".equals(type),
                    anyChar || "monster".equals(type), cardName != null || "summon".equals(type),
                    cost, null, cardName, null, null, null, null, null, "field", 1, false, null);
        }
    }

    static Consumer<GameContext> tryParseFlipUntilTypeToHandRestShuffleBottom(String text) {
        if (!FLIP_UNTIL_TYPE_TO_HAND_REST_SHUFFLE_BOTTOM.matcher(text.trim()).matches()) return null;
        return GameContext::flipUntilTypeToHandRestShuffleBottom;
    }
    /**
     * Parses "Turn over one card at a time from the top of your deck until [a | N] [Type][s] [of
     * cost C or less] [other than Card Name X] (is|are) revealed. Play (it|them) onto the field.
     * Then, shuffle the other cards [revealed] and return them to the bottom of your deck."
     *
     * <p>The play-onto-field arm of the flip-until family — 7-106L Agrias and 20-001R Ardyn.
     * Its two siblings above put the card into hand; only the destination differs, which is why
     * the whole sentence has to be read rather than composed from parts.
     *
     * <p>Ardyn reaches this as the "If you do so" tail of an optional Break Zone price, so it is
     * dispatched from {@code AutoAbilityTriggers} as well as from {@code parse()}.
     */
    static Consumer<GameContext> tryParseFlipUntilCharactersPlayOntoFieldRestShuffleBottom(String text) {
        Matcher m = FLIP_UNTIL_CHARACTERS_PLAY_ONTO_FIELD_REST_SHUFFLE_BOTTOM.matcher(text.trim());
        if (!m.matches()) return null;
        int    count   = m.group("count")   != null ? Integer.parseInt(m.group("count")) : 1;
        String type    = cap(m.group("type"));
        int    maxCost = m.group("maxcost") != null ? Integer.parseInt(m.group("maxcost")) : -1;
        String exclude = m.group("exclude") != null ? m.group("exclude").trim() : null;
        String label   = "Turn cards over until " + count + " " + type
                + (count == 1 ? "" : "s")
                + (maxCost >= 0 ? " of cost " + maxCost + " or less" : "")
                + (exclude != null ? " other than Card Name " + exclude : "")
                + " revealed — play onto field, rest shuffled to bottom";
        return ctx -> {
            ctx.logEntry("Effect: " + label);
            ctx.flipUntilCharactersPlayOntoFieldRestShuffleBottom(count, type, maxCost, exclude);
        };
    }

    /**
     * Parses "Turn over one card at a time from the top of your deck until a [Element] or [Element]
     * card is revealed. Add it to your hand. Then, shuffle the other cards and return them to the
     * bottom of your deck." — 13-042C White Mage, 13-005C Black Mage and their ten siblings.
     */
    static Consumer<GameContext> tryParseFlipUntilElementToHandRestShuffleBottom(String text) {
        Matcher m = FLIP_UNTIL_ELEMENT_TO_HAND_REST_SHUFFLE_BOTTOM.matcher(text.trim());
        if (!m.matches()) return null;
        String elem1 = m.group("elem1");
        String elem2 = m.group("elem2");
        return ctx -> {
            ctx.logEntry("Effect: Reveal from top of deck until a " + elem1 + " or " + elem2
                    + " card → hand; rest shuffled to bottom");
            ctx.flipUntilElementToHandRestShuffleBottom(elem1, elem2);
        };
    }
    static Consumer<GameContext> tryParseRevealPlayTypeOntoFieldRestBottom(String text) {
        String s = stripRestrictionSentences(text);
        Matcher m = REVEAL_PLAY_TYPE_ONTO_FIELD_REST_BOTTOM.matcher((s.isEmpty() ? text : s).trim());
        if (!m.matches()) return null;
        int n      = Integer.parseInt(m.group("n"));
        int max    = Integer.parseInt(m.group("max"));
        String typeRaw  = m.group("type");
        String normType = Character.toUpperCase(typeRaw.charAt(0))
                + typeRaw.substring(1).toLowerCase();
        String category = m.group("category");
        return ctx -> ctx.revealTopNPlayUpToTypeOntoFieldRestBottom(n, max, normType, category);
    }
    static Consumer<GameContext> tryParseRevealElementCardFromHandIfSoDraw(String text) {
        Matcher m = REVEAL_ELEMENT_CARD_FROM_HAND_IF_SO_DRAW.matcher(text.trim());
        if (!m.matches()) return null;
        String elementRaw = m.group("element");
        String element    = Character.toUpperCase(elementRaw.charAt(0)) + elementRaw.substring(1).toLowerCase();
        int drawCount     = Integer.parseInt(m.group("draw"));
        return ctx -> ctx.revealElementCardFromHandDraw(element, drawCount);
    }
    static Consumer<GameContext> tryParseRevealPlayElementTypeCostOntoFieldRestBottom(String text) {
        return tryParseRevealPlayElementTypeCostOntoFieldRestBottom(text, 0);
    }
    static Consumer<GameContext> tryParseRevealPlayElementTypeCostOntoFieldRestBottom(String text, int xValue) {
        // Strip "You can only cast [CardName] during your Main Phase." restriction prefix.
        String stripped = text.trim().replaceFirst(
                "(?i)You\\s+can\\s+only\\s+cast\\s+[^.]+?during\\s+your\\s+Main\\s+Phase[.!]?\\s*", "").trim();
        Matcher m = REVEAL_PLAY_ELEMENT_TYPE_COST_ONTO_FIELD_REST_BOTTOM.matcher(stripped);
        if (!m.matches()) return null;
        int n           = Integer.parseInt(m.group("n"));
        int max         = Integer.parseInt(m.group("max"));
        String elementRaw = m.group("element");
        String element    = elementRaw != null ? Character.toUpperCase(elementRaw.charAt(0)) + elementRaw.substring(1).toLowerCase() : null;
        String typeRaw  = m.group("type");
        String normType = Character.toUpperCase(typeRaw.charAt(0)) + typeRaw.substring(1).toLowerCase();
        String costStr  = m.group("cost");
        int maxCost     = "X".equalsIgnoreCase(costStr) ? xValue : Integer.parseInt(costStr);
        RevealRest rest = m.group("resthand")    != null ? RevealRest.HAND
                        : m.group("restbz")      != null ? RevealRest.BREAK_ZONE
                        : m.group("restshuffle") != null ? RevealRest.SHUFFLED_BOTTOM
                        : RevealRest.BOTTOM;
        return ctx -> ctx.revealTopNPlayUpToElementTypeCostOntoField(n, max, element, normType, maxCost, rest);
    }
    /**
     * Parses Syldra 29-101H's "Reveal the top N cards of your deck. Play 1 [Type] of cost C or less
     * other than Multi-Element or 1 Card Name X of cost D or less among them onto the field and
     * return the other cards to the bottom of your deck in any order."
     *
     * <p>One card is played, from whichever of the two alternatives it satisfies. Each alternative
     * brings its own cost ceiling, so the Faris branch reaches cards the Forward branch cannot.
     */
    static Consumer<GameContext> tryParseRevealPlayTypeCostOrNamedCostRestBottom(String text) {
        Matcher m = REVEAL_PLAY_TYPE_COST_OR_NAMED_COST_REST_BOTTOM
                .matcher(stripCastTimingPrefix(text));
        if (!m.matches()) return null;
        int n           = Integer.parseInt(m.group("n"));
        String typeRaw  = m.group("type");
        String normType = Character.toUpperCase(typeRaw.charAt(0)) + typeRaw.substring(1).toLowerCase();
        int typeMaxCost = Integer.parseInt(m.group("typecost"));
        boolean exclMulti = m.group("except") != null;
        String cardName = m.group("cardname").trim();
        int nameMaxCost = Integer.parseInt(m.group("namecost"));
        return ctx -> ctx.revealTopNPlayTypeCostOrNamedCostOntoFieldRestBottom(
                n, normType, typeMaxCost, exclMulti, cardName, nameMaxCost);
    }
    /**
     * Parses Banon's "Reveal the top card of your deck. If it is a [Type], cancel all effects
     * choosing [Name]." — reveals the top deck card and cancels the in-progress selection when it
     * is of the given type.
     */
    static Consumer<GameContext> tryParseCancelChosenRevealTopIfType(String text) {
        Matcher m = CANCEL_CHOSEN_REVEAL_TOP_IF_TYPE.matcher(text.trim());
        if (!m.find()) return null;
        String type = m.group("type");
        return ctx -> {
            ctx.logEntry("Effect: reveal top of deck; if a " + type + ", cancel the effect choosing your Character");
            ctx.revealTopDeckCancelChosenIfType(type);
        };
    }
    static Consumer<GameContext> tryParseRandomRevealHandCastIfSummonFree(String text) {
        if (!RANDOM_REVEAL_HAND_CAST_IF_SUMMON_FREE.matcher(text.trim()).find()) return null;
        return ctx -> {
            ctx.logEntry("Effect: Randomly reveal 1 card from hand — cast it for free if it is a Summon");
            ctx.randomRevealHandCastIfSummonFree();
        };
    }
    static Consumer<GameContext> tryParseSearchAndCastSummonFree(String text, CardData source) {
        Matcher m = SEARCH_AND_CAST_SUMMON_FREE_PATTERN.matcher(text.trim());
        if (!m.find()) return null;
        String element = m.group("element");
        String costStr = m.group("cost");
        int maxCost = costStr != null ? Integer.parseInt(costStr) : -1;
        // "If you cast a Summon of cost N or more with this ability, put [Self] into the Break
        // Zone." — 2-142R Lenne. Only honoured when the name is this card's own: read off any other
        // name it would break a card the sentence is not about, so the rider is dropped instead.
        String selfBreakName = m.group("selfname");
        final int selfBreakCost = m.group("selfbreakcost") != null
                && source != null && selfBreakName != null
                && selfBreakName.trim().equalsIgnoreCase(source.name())
                ? Integer.parseInt(m.group("selfbreakcost")) : -1;
        return ctx -> {
            ctx.logEntry("Effect: Search deck for " + (element != null ? element + " " : "") + "Summon"
                    + (maxCost >= 0 ? " (cost " + maxCost + " or less)" : "") + ", cast for free or Break Zone"
                    + (selfBreakCost >= 0 ? " — " + source.name() + " breaks if it cost " + selfBreakCost + " or more" : ""));
            int castCost = ctx.searchAndCastSummonFreeFromDeck(maxCost, element);
            if (selfBreakCost >= 0 && castCost >= selfBreakCost) {
                ctx.logEntry("Effect: cast a Summon of cost " + castCost + " — Break " + source.name());
                ctx.breakSourceCard(source);
            }
        };
    }
    /**
     * Parses "Your opponent reveals N cards from their hand. Select 1 card among them.
     * Your opponent discards this card." (14-035C Don Corneo)
     *
     * <p>Two players decide here — the opponent picks what to reveal, the ability user picks
     * what dies — which is why this does not reuse
     * {@link GameContext#selectFromOpponentHandAndDiscard}: that one exposes the whole hand.
     */
    static Consumer<GameContext> tryParseOpponentRevealNSelectOneDiscard(String text) {
        Matcher m = OPPONENT_REVEAL_N_SELECT_ONE_DISCARD_PATTERN.matcher(text);
        if (!m.find()) return null;
        int count = Integer.parseInt(m.group("count"));
        return ctx -> {
            ctx.logEntry("Effect: Opponent reveals " + count
                    + " cards from hand — select 1 for them to discard");
            ctx.opponentRevealsSelectOneDiscard(count);
        };
    }
    /** Parses "Your opponent shows/reveals his/her hand". */
    static Consumer<GameContext> tryParseOpponentRevealHand(String text) {
        Matcher m = OPPONENT_REVEAL_HAND_PATTERN.matcher(text);
        if (!m.find()) return null;
        return ctx -> {
            ctx.logEntry("Effect: Opponent reveals hand");
            ctx.revealOpponentHand();
        };
    }
    /**
     * Parses one or more "If it is/has [cond], [action]" clauses following a
     * "Reveal the top card of your deck" header.
     * Each action is either a card-referencing op code or a standalone effect
     * parsed by {@link #parse}.
     */
    static Consumer<GameContext> tryParseChooseFwdRevealCostParity(String text) {
        Matcher m = CHOOSE_FWD_REVEAL_COST_PARITY_PATTERN.matcher(text.trim());
        if (!m.matches()) return null;
        return ctx -> {
            ctx.logEntry("Effect: Choose 1 Forward, reveal top card — even cost→bounce, odd cost→4000 damage + dull + freeze");
            List<ForwardTarget> ts = selectTargets(ctx, 1, false, false, false, null, null, null, false,
                    -1, null, -1, null, true, false, false, null, null, null, null, false, null, false);
            if (ts.isEmpty()) return;
            ForwardTarget t = ts.get(0);
            ctx.revealTopDeckCostParityEffect(
                ctx2 -> {
                    ctx2.logEntry("Even cost — returning chosen Forward to owner's hand");
                    if (t.isP1()) ctx2.returnP1ForwardToHand(t.idx());
                    else          ctx2.returnP2ForwardToHand(t.idx());
                },
                ctx2 -> {
                    ctx2.logEntry("Odd cost — dealing 4000 damage, dulling and freezing chosen Forward");
                    ctx2.damageTarget(t, 4000);
                    ctx2.dullAndFreezeTarget(t);
                }
            );
        };
    }
    static Consumer<GameContext> tryParseRevealTopDeck(String text, CardData source) {
        Matcher header = REVEAL_TOP_DECK_HEADER.matcher(text);
        if (!header.find()) return null;
        boolean opponentDeck = header.group("who").toLowerCase(java.util.Locale.ROOT).contains("opponent");
        List<RevealClause> clauses = new ArrayList<>();
        Matcher m = REVEAL_CLAUSE_PATTERN.matcher(text);
        while (m.find()) {
            RevealClause clause = buildRevealClause(
                m.group("cond").trim(), m.group("action").trim(), source);
            if (clause == null) return null;
            clauses.add(clause);
        }
        if (clauses.isEmpty()) return null;
        String whose = opponentDeck ? "opponent's" : "your";
        return ctx -> {
            ctx.logEntry("Effect: Reveal top card of " + whose + " deck (" + clauses.size() + " clause(s))");
            ctx.revealTopDeckCard(clauses, opponentDeck);
        };
    }
    static Consumer<GameContext> tryParseEachPlayerRevealCharacterMayPlay(String text) {
        Matcher m = EACH_PLAYER_REVEAL_CHARACTER_MAY_PLAY.matcher(text);
        if (!m.find()) return null;
        String typeStr = m.group("type").trim();
        java.util.function.Predicate<CardData> eligible = card -> meetsTypeCheck(card, typeStr);
        return ctx -> {
            ctx.logEntry("Effect: Each player reveals top card, may play if " + typeStr);
            ctx.revealEachPlayerTopDeckMayPlay(eligible);
        };
    }
    static Consumer<GameContext> tryParseNameJobOrCategoryRevealAddToHand(String text) {
        Matcher m = NAME_JOB_OR_CATEGORY_REVEAL_ADD_TO_HAND.matcher(text);
        if (!m.find()) return null;
        int reveal = Integer.parseInt(m.group("reveal"));
        int maxAdd = Integer.parseInt(m.group("maxAdd"));
        return ctx -> {
            ctx.logEntry("Effect: Name 1 Job or Category — reveal top " + reveal + ", add up to " + maxAdd + " matching Characters to hand");
            String[] choice = ctx.selectJobOrCategory("Name 1 Job or Category:");
            if (choice == null || choice[1] == null || choice[1].isBlank()) return;
            ctx.logEntry("Named " + choice[0] + ": " + choice[1]);
            String jobFilter = "job".equalsIgnoreCase(choice[0]) ? choice[1] : null;
            String catFilter = "category".equalsIgnoreCase(choice[0]) ? choice[1] : null;
            ctx.revealTopAddUpToMatchingRestBottom(reveal, maxAdd, jobFilter, catFilter, null, null);
        };
    }
    static Consumer<GameContext> tryParseRevealTopNCategoryToHand(String text) {
        String s = stripRestrictionSentences(text);
        Matcher m = REVEAL_TOP_N_CATEGORY_TO_HAND.matcher(s.isEmpty() ? text : s);
        if (!m.find()) return null;
        int n = Integer.parseInt(m.group("n"));
        int max = Integer.parseInt(m.group("max"));
        String cat = m.group("cat");
        return ctx -> {
            ctx.logEntry("Effect: Reveal top " + n + " — add up to " + max + " Category " + cat
                    + " to hand, rest to bottom");
            ctx.revealTopAddUpToMatchingRestBottom(n, max, null, cat, null, null);
        };
    }
    /**
     * Parses "reveal the top N cards … Add 1 Job X [or Card Name Y] … bottom of your deck."
     * Splits the captured filter terms into a job filter and a card-name filter (each
     * bar-separated when multiple terms of the same kind appear) and forwards them to
     * {@link GameContext#revealTopAddUpToMatchingRestBottom}.
     */
    static Consumer<GameContext> tryParseRevealTopNJobOrNameToHand(String text) {
        String s = stripRestrictionSentences(text);
        Matcher m = REVEAL_TOP_N_JOB_OR_NAME_TO_HAND.matcher(s.isEmpty() ? text : s);
        if (!m.find()) return null;
        int n = Integer.parseInt(m.group("n"));
        StringBuilder jobs  = new StringBuilder();
        StringBuilder names = new StringBuilder();
        appendFilterTerm(jobs, names, m.group("first"));
        appendFilterTerm(jobs, names, m.group("second"));
        String jobFilter      = jobs.length()  > 0 ? jobs.toString()  : null;
        String cardNameFilter = names.length() > 0 ? names.toString() : null;
        if (jobFilter == null && cardNameFilter == null) return null;
        // "Add all …" is mandatory, not a cap: every revealed card matching the filters goes to
        // hand, so it routes to the reveal that forces the take rather than to the "add up to"
        // prompt the singular "Add 1" form uses.
        boolean all = m.group("all") != null;
        String  desc = (all ? "all" : "1") + " ("
                + (jobFilter      != null ? "Job " + jobFilter           : "")
                + (jobFilter != null && cardNameFilter != null ? " | " : "")
                + (cardNameFilter != null ? "Card Name " + cardNameFilter : "")
                + ")";
        return ctx -> {
            ctx.logEntry("Effect: Reveal top " + n + " — add " + desc + " to hand, rest to bottom");
            if (all) ctx.revealTopAddAllMatchingRestBottom(n, jobFilter, null, cardNameFilter, null);
            else     ctx.revealTopAddUpToMatchingRestBottom(n, 1, jobFilter, null, cardNameFilter, null);
        };
    }
    static Consumer<GameContext> tryParseRevealTopNTypeToHand(String text) {
        String s = stripRestrictionSentences(text);
        Matcher m = REVEAL_TOP_N_TYPE_TO_HAND.matcher(s.isEmpty() ? text : s);
        if (!m.find()) return null;
        int n = Integer.parseInt(m.group("n"));
        int max = Integer.parseInt(m.group("max"));
        // The untyped arm restricts on cost alone, so it passes no type filter at all — every
        // filter null means "any card" downstream, with maxCost doing the selecting.
        boolean anyCard = m.group("anycard") != null;
        // Normalise plural → singular (e.g. "Monsters" → "Monster")
        String typeFilter = anyCard ? null : m.group("type").replaceAll("(?i)s$", "");
        String costRaw = anyCard ? m.group("anycost") : m.group("cost");
        int maxCost = costRaw != null ? Integer.parseInt(costRaw) : -1;
        return ctx -> {
            ctx.logEntry("Effect: Reveal top " + n + " — add up to " + max + " "
                    + (typeFilter != null ? typeFilter : "card")
                    + (maxCost >= 0 ? " of cost " + maxCost + " or less" : "") + " to hand, rest to bottom");
            ctx.revealTopAddUpToMatchingRestBottom(n, max, null, null, null, typeFilter, maxCost);
        };
    }
    static Consumer<GameContext> tryParseRevealTopNElementToHand(String text) {
        String s = stripRestrictionSentences(text);
        Matcher m = REVEAL_TOP_N_ELEMENT_TO_HAND.matcher(s.isEmpty() ? text : s);
        if (!m.find()) return null;
        int n = Integer.parseInt(m.group("n"));
        int max = Integer.parseInt(m.group("max"));
        String normElement = cap(m.group("element"));
        String cat = m.group("cat");
        if (cat != null) {
            // "Add M [Element] or Category [X] card" — element and category are alternatives.
            // The element is a disjunct (orElementFilter), not an AND-gate — "Water OR Category X".
            return ctx -> {
                ctx.logEntry("Effect: Reveal top " + n + " — add up to " + max + " " + normElement
                        + " or Category " + cat + " to hand, rest to bottom");
                ctx.revealTopAddUpToMatchingRestBottom(n, max, null, cat, null, null, -1, null, normElement);
            };
        }
        String typeRaw = m.group("type");
        String typeFilter = typeRaw != null ? cap(typeRaw.replaceAll("(?i)s$", "")) : null;
        // "Add M [Element] [Type]" — the element is an AND-gate on the type (e.g. "Fire Forward").
        return ctx -> {
            ctx.logEntry("Effect: Reveal top " + n + " — add up to " + max + " " + normElement
                    + (typeFilter != null ? " " + typeFilter : " card") + "(s) to hand, rest to bottom");
            ctx.revealTopAddUpToMatchingRestBottom(n, max, null, null, null, typeFilter, -1, normElement);
        };
    }
    /**
     * Parses "Reveal the top N cards of your deck. Add up to M [filtered] among them to your hand.
     * Then shuffle the other cards and return them to the bottom of your deck." — Bartz 27-110H,
     * Ace 9-003L.
     *
     * <p>The leftovers are randomised rather than ordered by the player; see
     * {@link GameContext#revealTopAddUpToMatchingRestShuffledBottom}.
     */
    /**
     * Parses Chaos Advent 27-006R's "Reveal the top N cards of your deck. Play up to M Category C
     * [Type] among them onto the field. Then, shuffle the other cards revealed and return them to
     * the bottom of your deck. The [Type]'s Element becomes E and it gains Job J."
     */
    static Consumer<GameContext> tryParseRevealPlayCategoryTypeRestShuffledBottomGrantElementJob(String text) {
        Matcher m = REVEAL_PLAY_CATEGORY_TYPE_REST_SHUFFLED_BOTTOM_GRANT_ELEMENT_JOB.matcher(text.trim());
        if (!m.matches()) return null;
        int n    = Integer.parseInt(m.group("n"));
        int max  = Integer.parseInt(m.group("max"));
        String cat     = m.group("cat");
        String type    = cap(m.group("type"));
        String element = cap(m.group("element"));
        String job     = m.group("job").trim();
        return ctx -> {
            ctx.logEntry("Effect: Reveal top " + n + " — play up to " + max + " Category " + cat
                    + " " + type + ", shuffle the rest under the deck; it becomes " + element
                    + " and gains Job " + job);
            ctx.revealTopNPlayCategoryTypeRestShuffledBottomGrantElementJob(n, max, cat, type, element, job);
        };
    }
    /**
     * Parses Shinryu 14-115L's "Reveal the top card of opponent's deck. If it is a Forward, all the
     * Forwards opponent controls lose 7000 power until the end of the turn. If it is not a Forward,
     * draw 2 cards."
     *
     * <p>Returns {@code null} unless both branches parse and both name the same type — a text
     * asking about two different types is two conditions, not one either/or.
     */
    static Consumer<GameContext> tryParseRevealOpponentTopBranchOnType(String text) {
        Matcher m = REVEAL_OPPONENT_TOP_BRANCH_ON_TYPE.matcher(text.trim());
        if (!m.matches()) return null;
        String type = cap(m.group("type"));
        if (!type.equalsIgnoreCase(m.group("type2"))) return null;
        Consumer<GameContext> thenFn = parse(m.group("then").trim(), null);
        Consumer<GameContext> elseFn = parse(m.group("otherwise").trim(), null);
        if (thenFn == null || elseFn == null) return null;
        return ctx -> {
            if (ctx.revealOpponentTopCardIsType(type)) thenFn.accept(ctx);
            else                                       elseFn.accept(ctx);
        };
    }
    /**
     * Parses Setzer 29-103H's "Reveal the top N cards of your deck. Remove 1 card with Warp among
     * them from the game and place M Warp Counter(s) on it. Then shuffle the other cards and
     * return them to the bottom of your deck."
     */
    static Consumer<GameContext> tryParseRevealTopNRemoveWarpCardPlaceCountersRestShuffledBottom(String text) {
        Matcher m = REVEAL_TOP_N_REMOVE_WARP_CARD_PLACE_COUNTERS_REST_SHUFFLED_BOTTOM.matcher(text.trim());
        if (!m.matches()) return null;
        int n        = Integer.parseInt(m.group("n"));
        int counters = Integer.parseInt(m.group("counters"));
        return ctx -> {
            ctx.logEntry("Effect: Reveal top " + n + " — remove 1 card with Warp from the game with "
                    + counters + " Warp Counter(s), shuffle the rest under the deck");
            ctx.revealTopNRemoveWarpCardPlaceCountersRestShuffledBottom(n, counters);
        };
    }
    static Consumer<GameContext> tryParseRevealTopNAddUpToMatchingRestShuffledBottom(String text) {
        Matcher m = REVEAL_TOP_N_ADD_UP_TO_MATCHING_REST_SHUFFLED_BOTTOM.matcher(text.trim());
        if (!m.matches()) return null;
        int n   = Integer.parseInt(m.group("n"));
        int max = Integer.parseInt(m.group("max"));
        String cat     = m.group("cat");
        String job     = m.group("job");
        String exclude = m.group("exclude");
        String typeRaw = m.group("type");
        String type    = typeRaw != null ? cap(typeRaw.replaceAll("(?i)s$", "")) : null;
        String what = (job != null ? "Job " + job + " " : "")
                + (cat != null ? "Category " + cat + " " : "")
                + (type != null ? type : "card")
                + (exclude != null ? " (excl. Card Name " + exclude + ")" : "");
        return ctx -> {
            ctx.logEntry("Effect: Reveal top " + n + " — add up to " + max + " " + what
                    + " to hand, shuffle the rest under the deck");
            ctx.revealTopAddUpToMatchingRestShuffledBottom(n, max, job, cat, type, exclude);
        };
    }
    /**
     * Parses "Reveal the top N cards of your deck. Add up to M [Category X] [Type] among them to
     * your hand and put the rest of the cards into the Break Zone." — Nael 9-014L and family.
     *
     * <p>A plural type is singularised for the filter ("Forwards" is a count, "Forward" is what a
     * card is), and a bare "cards" leaves the filter null so everything revealed may be taken.
     */
    static Consumer<GameContext> tryParseRevealTopNAddUpToMatchingRestBz(String text) {
        Matcher m = REVEAL_TOP_N_ADD_UP_TO_MATCHING_REST_BZ.matcher(text.trim());
        if (!m.matches()) return null;
        int n   = Integer.parseInt(m.group("n"));
        int max = Integer.parseInt(m.group("max"));
        String cat     = m.group("cat");
        String typeRaw = m.group("type");
        String type    = typeRaw != null ? cap(typeRaw.replaceAll("(?i)s$", "")) : null;
        String what = (cat != null ? "Category " + cat + " " : "") + (type != null ? type : "card");
        return ctx -> {
            ctx.logEntry("Effect: Reveal top " + n + " — add up to " + max + " " + what
                    + "(s) to hand, rest to Break Zone");
            ctx.revealTopAddUpToMatchingRestBz(n, max, cat, type);
        };
    }
    static Consumer<GameContext> tryParseRevealTopNAddUpToExcludingNameRestBz(String text) {
        Matcher m = REVEAL_TOP_N_ADD_UP_TO_EXCLUDING_NAME_REST_BZ.matcher(text.trim());
        if (!m.find()) return null;
        int n = Integer.parseInt(m.group("n"));
        int max = Integer.parseInt(m.group("max"));
        String name = m.group("name").trim();
        return ctx -> {
            ctx.logEntry("Effect: Reveal top " + n + " — add up to " + max
                    + " (excl. Card Name " + name + ") to hand, rest to Break Zone");
            ctx.revealTopAddUpToExcludingNameRestBz(n, max, name);
        };
    }
    /**
     * Parses "Reveal the top N cards of your deck. Add 1 [Type], 1 [Type], and 1 [Type] among them
     * to your hand, and put the rest of the cards into the Break Zone." — 10-138S Ramza.
     *
     * <p>One quota per printed card type rather than one count over a single filter: revealing two
     * Forwards and a Backup takes one of each, not two Forwards. The list is kept as printed rather
     * than de-duplicated, so a text naming a type twice would ask for two of it.
     *
     * <p>Returns {@code null} when fewer than two quotas survive, which cannot happen against the
     * pattern (its list requires at least two) but keeps the parser honest about what it needs.
     */
    static Consumer<GameContext> tryParseRevealTopNAddOnePerTypeRestBz(String text) {
        Matcher m = REVEAL_TOP_N_ADD_ONE_PER_TYPE_REST_BZ.matcher(text.trim());
        if (!m.matches()) return null;
        int n = Integer.parseInt(m.group("n"));
        List<String> types = new ArrayList<>();
        Matcher q = REVEAL_ONE_PER_TYPE_QUOTA.matcher(m.group("types"));
        while (q.find()) types.add(cap(q.group("type")));
        if (types.size() < 2) return null;
        return ctx -> {
            ctx.logEntry("Effect: Reveal top " + n + " — add 1 "
                    + String.join(", 1 ", types) + " to hand, rest to Break Zone");
            ctx.revealTopAddOnePerTypeToHandRestBz(n, types);
        };
    }

    static Consumer<GameContext> tryParseRevealAddTypeToHandOrPlayJobTypeOntoFieldRestBottom(String text) {
        Matcher m = REVEAL_ADD_TYPE_TO_HAND_OR_PLAY_JOB_TYPE_ONTO_FIELD_REST_BOTTOM.matcher(text.trim());
        if (!m.matches()) return null;
        int n        = Integer.parseInt(m.group("n"));
        int handMax  = Integer.parseInt(m.group("handmax"));
        String handType  = cap(m.group("handtype"));
        int fieldMax = Integer.parseInt(m.group("fieldmax"));
        String fieldJob  = m.group("fieldjob") != null ? m.group("fieldjob").trim() : null;
        String fieldType = cap(m.group("fieldtype"));
        String logDesc = "Reveal top " + n + " — add up to " + handMax + " " + handType
                + " to hand OR play up to " + fieldMax
                + (fieldJob != null ? " Job " + fieldJob + " " : " ") + fieldType + " onto field; rest to bottom";
        return ctx -> {
            ctx.logEntry("Effect: " + logDesc);
            ctx.revealTopNAddTypeToHandOrPlayJobTypeOntoFieldRestBottom(n, handMax, handType, fieldMax, fieldJob, fieldType);
        };
    }
    static Consumer<GameContext> tryParseLookTopDeckOptionallyBreak(String text) {
        if (!LOOK_TOP_DECK_OPTIONALLY_BREAK.matcher(text).find()) return null;
        return ctx -> {
            ctx.logEntry("Effect: Look at top of deck — may put into Break Zone");
            ctx.lookAtTopDeck(new LookConfig(1, LookConfig.LookAction.BREAK_OR_KEEP));
        };
    }
    static Consumer<GameContext> tryParseLookTopDeckBottomOrKeep(String text) {
        if (!LOOK_TOP_DECK_BOTTOM_OR_KEEP.matcher(text).find()) return null;
        return ctx -> {
            ctx.logEntry("Effect: Look at top of deck — may place at bottom");
            ctx.lookAtTopDeck(new LookConfig(1, LookConfig.LookAction.BOTTOM_OR_KEEP));
        };
    }
    static Consumer<GameContext> tryParseLookTopDeckReturnTopOrdered(String text) {
        Matcher m = LOOK_TOP_DECK_RETURN_TOP_ORDERED.matcher(text);
        if (!m.find()) return null;
        int count = Integer.parseInt(m.group("count"));
        return ctx -> {
            ctx.logEntry("Effect: Look at top " + count + " card(s) — return to top in any order");
            ctx.lookAtTopDeck(new LookConfig(count, LookConfig.LookAction.RETURN_TOP_ORDERED));
        };
    }
    /**
     * Parses 27-053C Lehko Habhoka's board-scaled look: the count is however many qualifying cards
     * the ability's controller has on the field when it resolves.
     *
     * <p>Counted at resolution rather than at parse time, which is what makes the source card
     * itself part of the total -- Lehko Habhoka is a Backup, and its own "enters the field"
     * trigger resolves with it already seated.
     */
    static Consumer<GameContext> tryParseLookSelfFieldScaleAddToHandRestBottom(String text) {
        Matcher m = LOOK_SELF_FIELD_SCALE_ADD_TO_HAND_REST_BOTTOM.matcher(text);
        if (!m.find()) return null;
        boolean reveal  = isRevealWording(m.group("verb"));
        String  element = m.group("element");
        String  typeRaw = m.group("type");
        String  type    = typeRaw.toLowerCase(Locale.ROOT);
        boolean inclFwd = type.startsWith("forward") || type.startsWith("character");
        boolean inclBkp = type.startsWith("backup")  || type.startsWith("character");
        boolean inclMon = type.startsWith("monster") || type.startsWith("character");
        String  label   = (element != null ? element + " " : "") + typeRaw + " you control";
        return ctx -> {
            int count = ctx.countSelfFieldCards(inclFwd, inclBkp, inclMon, null, null, null, element);
            if (count <= 0) {
                ctx.logEntry("Effect: no " + label + " -- nothing to look at");
                return;
            }
            ctx.logEntry("Effect: " + (reveal ? "Reveal" : "Look at") + " top " + count
                    + " card(s) (" + label + ") -- add 1 to hand, return rest to bottom");
            ctx.lookAtTopDeck(new LookConfig(
                    count, LookConfig.LookAction.ADD_TO_HAND_REST_BOTTOM, null, null, reveal));
        };
    }
    static Consumer<GameContext> tryParseLookTopDeckAddToHandRestBottom(String text) {
        Matcher m = LOOK_TOP_DECK_ADD_TO_HAND_REST_BOTTOM.matcher(text);
        if (!m.find()) return null;
        int count = Integer.parseInt(m.group("count"));
        boolean reveal = isRevealWording(m.group("verb"));
        Consumer<GameContext> look = ctx -> {
            ctx.logEntry("Effect: " + (reveal ? "Reveal" : "Look at") + " top " + count
                    + " card(s) — add 1 to hand, return rest to bottom");
            ctx.lookAtTopDeck(new LookConfig(
                    count, LookConfig.LookAction.ADD_TO_HAND_REST_BOTTOM, null, null, reveal));
        };
        String tail = text.substring(m.end()).trim();
        if (tail.isEmpty()) return look;
        // Any other rider on this clause is declined rather than dropped: reporting the whole
        // ability as unparsed beats running half of it. 23-064R Golem's rider — "If you added a
        // Forward to your hand, deal the chosen Forward damage equal to the power of the added
        // Forward" — is read by the Choose chain instead, which claims the card ahead of here
        // because its text opens with the choose.
        if (!ADDED_CARD_EX_BURST_RIDER.matcher(tail).matches()) return null;
        return look.andThen(ctx -> {
            ctx.logEntry("Effect: added card's EX Burst may be put on the stack");
            ctx.triggerExBurstOfCardAddedToHand();
        });
    }
    static Consumer<GameContext> tryParseLookTopDeckAddToHandOneToBreakRestBottom(String text) {
        Matcher m = LOOK_TOP_DECK_ADD_TO_HAND_ONE_TO_BREAK_REST_BOTTOM.matcher(text);
        if (!m.find()) return null;
        int count = Integer.parseInt(m.group("count"));
        return ctx -> {
            ctx.logEntry("Effect: Look at top " + count + " card(s) — add 1 to hand, 1 to Break Zone, return rest to bottom");
            ctx.lookAtTopDeck(new LookConfig(count, LookConfig.LookAction.ADD_TO_HAND_ONE_TO_BREAK_REST_BOTTOM));
        };
    }
    static Consumer<GameContext> tryParseLookTopDeckAddToHandRestBreak(String text) {
        Matcher m = LOOK_TOP_DECK_ADD_TO_HAND_REST_BREAK.matcher(text);
        if (!m.find()) return null;
        int     count    = Integer.parseInt(m.group("count"));
        String  element  = m.group("element");
        String  category = m.group("category");
        boolean reveal   = isRevealWording(m.group("verb"));
        LookConfig config = new LookConfig(
                count, LookConfig.LookAction.ADD_TO_HAND_REST_BREAK, element, category, reveal);
        String label = config.handFilterLabel();
        String filterLabel = label != null ? " (" + label + ")" : "";
        return ctx -> {
            ctx.logEntry("Effect: " + (reveal ? "Reveal" : "Look at") + " top " + count
                    + " card(s) — add 1" + filterLabel + " to hand, rest to Break Zone");
            ctx.lookAtTopDeck(config);
        };
    }
    /**
     * Parses "Reveal the top card of your deck. If it is a [Type], add it to your hand. If it is
     * not a [Type], put it at the top or bottom of your deck." — 16-115H Sarah (MOBIUS).
     *
     * <p>Kept whole rather than composed from its three sentences: the second and third are two
     * branches of one decision on the card the first revealed, not three effects in sequence.
     */
    static Consumer<GameContext> tryParseRevealTopToHandIfTypeElseTopOrBottom(String text) {
        Matcher m = REVEAL_TOP_TO_HAND_IF_TYPE_ELSE_TOP_OR_BOTTOM.matcher(text.trim());
        if (!m.matches()) return null;
        String type = m.group("type").trim();
        return ctx -> {
            ctx.logEntry("Effect: Reveal top card — a " + type + " goes to hand, anything else to "
                    + "the top or bottom of the deck");
            ctx.revealTopAddToHandIfType(type);
        };
    }
    static Consumer<GameContext> tryParseLookTopDeckTopOrBottom(String text, CardData source) {
        Matcher m = LOOK_TOP_DECK_TOP_OR_BOTTOM.matcher(text);
        if (!m.find()) return null;
        int count = Integer.parseInt(m.group("count"));
        Consumer<GameContext> look = ctx -> {
            ctx.logEntry("Effect: Look at top " + count + " card(s) — return to top or bottom in any order");
            ctx.lookAtTopDeck(new LookConfig(count, LookConfig.LookAction.TOP_OR_BOTTOM_ORDERED));
        };
        return appendThenClause(look, text.substring(m.end()), source);
    }
    static Consumer<GameContext> tryParseLookTopDeckPickOneTopRestBottom(String text) {
        Matcher m = LOOK_TOP_DECK_PICK_ONE_TOP_REST_BOTTOM.matcher(text);
        if (!m.find()) return null;
        int count = Integer.parseInt(m.group("count"));
        return ctx -> {
            ctx.logEntry("Effect: Look at top " + count + " card(s) — pick 1 on top, rest to bottom");
            ctx.lookAtTopDeck(new LookConfig(count, LookConfig.LookAction.PICK_ONE_TOP_REST_BOTTOM));
        };
    }
    static Consumer<GameContext> tryParseLookTopDeckPeek(String text) {
        Matcher m = LOOK_TOP_DECK_PEEK.matcher(text);
        if (!m.find()) return null;
        String countStr = m.group("count");
        int count = (countStr != null) ? Integer.parseInt(countStr) : 1;
        return ctx -> {
            ctx.logEntry("Effect: Look at top " + count + " card(s) of deck");
            ctx.lookAtTopDeck(new LookConfig(count, LookConfig.LookAction.PEEK));
        };
    }
    static Consumer<GameContext> tryParseRevealTopBreakSameCostAddToHand(String text) {
        if (!REVEAL_TOP_BREAK_SAME_COST_ADD_TO_HAND.matcher(text.trim()).find()) return null;
        return ctx -> {
            ctx.logEntry("Effect: Reveal top of deck — break all opponent Forwards with same cost, add revealed card to hand");
            ctx.revealTopBreakSameCostAddToHand();
        };
    }
    static Consumer<GameContext> tryParseLookTopDeckCastSummonFreeRestBottom(String text, int xValue) {
        Matcher m = LOOK_TOP_DECK_CAST_SUMMON_FREE_REST_BOTTOM.matcher(text.trim());
        if (!m.find()) return null;
        String countStr = m.group("count");
        String costStr  = m.group("cost");
        final int count   = countStr.equalsIgnoreCase("X") ? xValue : Integer.parseInt(countStr);
        final int maxCost = costStr.equalsIgnoreCase("X")  ? xValue : Integer.parseInt(costStr);
        return ctx -> {
            ctx.logEntry("Effect: Look at top " + count + " card(s) — reveal/cast 1 Summon (cost " + maxCost + " or less) for free, shuffle rest to bottom");
            ctx.lookAtTopDeckCastSummonFreeRestBottom(count, maxCost);
        };
    }
    /**
     * Parses "Reveal the top N cards of your deck. Remove 1 [Category X] card among them from the
     * game and return the other cards to the bottom of your deck in any order. You can cast it at
     * any time you could normally cast it this turn." — Snow 18-109C, Warrior of Light 20-004C.
     *
     * <p>One effect rather than three: the removal exists to make the card castable, and the
     * leftovers going to the bottom is the same interaction the player is already arranging. See
     * the pattern's own note for why this must be dispatched ahead of
     * {@code tryParseRemoveNamedFromGame}.
     */
    static Consumer<GameContext> tryParseRevealTopNRfgOneCastableRestBottom(String text) {
        // End-anchored, so Helena Leonis 22-052H's trailing "You can only use this ability during
        // your turn and only once per turn." would defeat it. The restriction is captured as a flag
        // on the ability and gated at activation, so matching the stripped text loses nothing —
        // the same treatment the field-ability parsers give their own trailing restrictions.
        String matchOn = stripRestrictionSentences(text);
        if (matchOn.isEmpty()) matchOn = text;
        Matcher m = REVEAL_TOP_N_RFG_ONE_CASTABLE_REST_BOTTOM.matcher(matchOn.trim());
        if (!m.matches()) return null;
        int    reveal    = Integer.parseInt(m.group("reveal"));
        String category  = m.group("category") != null ? m.group("category").trim() : null;
        int    reduction = m.group("reduction") != null ? Integer.parseInt(m.group("reduction")) : 0;
        return ctx -> {
            ctx.logEntry("Effect: Reveal top " + reveal + " — remove 1 "
                    + (category != null ? "Category " + category + " " : "")
                    + "card from the game (castable this turn"
                    + (reduction > 0 ? ", cost -" + reduction : "") + "), rest to bottom");
            ctx.revealTopNRemoveOneFromGameCastableThisTurnRestBottom(reveal, category, reduction);
        };
    }
    static Consumer<GameContext> tryParseRemoveTopOfDeckFromGame(String text, CardData source) {
        Matcher m = REMOVE_TOP_OF_DECK_FROM_GAME.matcher(text);
        if (!m.find()) return null;
        String countStr = m.group("count");
        int count = (countStr != null) ? Integer.parseInt(countStr) : 1;
        return ctx -> {
            ctx.logEntry("Effect: Remove top " + count + " card(s) of deck from game");
            // Recorded against the source so a later ability on the same card can call them back
            // ("cards removed by the previous effect" — Libroarian 8-084R).
            ctx.removeTopCardsOfDeckFromGame(count, source);
        };
    }
    static Consumer<GameContext> tryParseShuffleDeck(String text) {
        if (!SHUFFLE_DECK.matcher(text).find()) return null;
        return ctx -> ctx.shuffleDeck();
    }
    static Consumer<GameContext> tryParseOppRfpTopDeckCastable(String text) {
        Matcher m = OPP_RFP_TOPDECK_CASTABLE.matcher(text);
        if (!m.find()) return null;
        String costClause = m.group("cost") != null ? m.group("cost") : "";
        Matcher r = Pattern.compile("(?i)reduced\\s+by\\s+(\\d+)").matcher(costClause);
        final int reduction = r.find() ? Integer.parseInt(r.group(1)) : 0;
        final boolean anyElement = costClause.toLowerCase(java.util.Locale.ROOT).contains("any element");
        return ctx -> {
            ctx.logEntry("Effect: Opponent removes top deck card from game — you may cast it as your own"
                    + (reduction > 0 ? " (cost -" + reduction + ")" : "")
                    + (anyElement ? " [any Element]" : ""));
            ctx.opponentRfpTopDeckMakeCastable(reduction, anyElement);
        };
    }
    static Consumer<GameContext> tryParseOpponentCannotSearchThisTurn(String text) {
        if (!OPPONENT_CANNOT_SEARCH_THIS_TURN.matcher(text).find()) return null;
        return ctx -> ctx.setOpponentCannotSearchThisTurn();
    }
    static Consumer<GameContext> tryParseDualSearchJobAndTypeDontShareElements(String text) {
        Matcher m = DUAL_SEARCH_JOB_AND_TYPE_DONT_SHARE_ELEMENTS.matcher(text);
        if (!m.find()) return null;
        String job  = m.group("job").trim();
        String type = m.group("type").trim();
        return ctx -> {
            ctx.logEntry("Effect: Dual search — Job " + job + " and " + type + " (don't share elements) → hand");
            ctx.searchDeckJobAndTypeDontShareElements(job, type);
        };
    }
    static Consumer<GameContext> tryParseSearchElementOrCategoryCharsDiffCost(String text) {
        Matcher m = SEARCH_ELEMENT_OR_CATEGORY_CHARS_DIFF_COST.matcher(text);
        if (!m.find()) return null;
        String element  = m.group("element").trim();
        String category = m.group("category").trim();
        return ctx -> {
            ctx.logEntry("Effect: Search — 2 " + element + " Characters, 2 Category " + category
                    + " Characters, or 1 of each, each with a different cost → hand");
            ctx.searchDeckElementOrCategoryCharsDifferentCost(element, category);
        };
    }
    /** Parses "Search for N [Element] Summons each with a different cost and add them to your hand." */
    static Consumer<GameContext> tryParseSearchNElementSummonsDiffCost(String text) {
        Matcher m = SEARCH_N_ELEM_SUMMONS_DIFF_COST.matcher(text);
        if (!m.find()) return null;
        int    count   = Integer.parseInt(m.group("count"));
        String element = m.group("element").trim();
        return ctx -> {
            ctx.logEntry("Effect: Search — " + count + " " + element + " Summons, each different cost → hand");
            ctx.searchDeckNElementSummonsDifferentCost(count, element);
        };
    }
    static Consumer<GameContext> tryParseSearchDeck(String text, CardData source, int xValue) {
        // Lifted off before the identity groups read the text — see SEARCH_WITH_DIFFERENT_NAMES.
        PickGate gate = PickGate.ANY;
        if (SEARCH_WITH_DIFFERENT_NAMES.matcher(text).find()) {
            gate = PickGate.DISTINCT_NAMES;
            text = SEARCH_WITH_DIFFERENT_NAMES.matcher(text).replaceFirst("");
        }
        if (SEARCH_EACH_OF_A_DIFFERENT_ELEMENT.matcher(text).find()) {
            // No printing carries both riders, and a search constrained two ways is not something
            // the selection can express, so the second one declines rather than replacing the first.
            if (gate != PickGate.ANY) return null;
            gate = PickGate.DISTINCT_ELEMENTS;
            text = SEARCH_EACH_OF_A_DIFFERENT_ELEMENT.matcher(text).replaceFirst("");
        }
        // Lifted off for the same reason as the two riders above: the phrase sits where
        // SEARCH_DECK_PATTERN expects the destination clause, so the whole search failed to parse
        // rather than parsing without the cap.
        int maxTotalCost = -1;
        Matcher totalCost = SEARCH_WITH_TOTAL_COST.matcher(text);
        if (totalCost.find()) {
            maxTotalCost = Integer.parseInt(totalCost.group("totalcost"));
            text = text.substring(0, totalCost.start()) + text.substring(totalCost.end());
        }
        // "Forward of cost 1 or Monster of cost 1" → "Forward or Monster of cost 1", so the type
        // union and the cost clause each land where SEARCH_DECK_PATTERN expects them. Rewritten
        // only when the two costs agree — see the pattern.
        Matcher perTypeCost = SEARCH_REPEATED_PER_TYPE_COST.matcher(text);
        if (perTypeCost.find() && perTypeCost.group("cost1").equals(perTypeCost.group("cost2"))) {
            text = text.substring(0, perTypeCost.start())
                    + perTypeCost.group("first") + " or " + perTypeCost.group("second")
                    + " of cost " + perTypeCost.group("cost1")
                    + text.substring(perTypeCost.end());
        }
        // Lifted off for a different reason: this one has to be in force before the cards it
        // silences reach the field, and the trailing-clause chain runs after the search.
        boolean suppressAutoAbilities = false;
        Matcher silent = AUTO_ABILITIES_WILL_NOT_TRIGGER.matcher(text);
        if (silent.find() && silent.end() == text.length()) {
            suppressAutoAbilities = true;
            text = text.substring(0, silent.start()).trim();
        }
        Matcher m = SEARCH_DECK_PATTERN.matcher(text);
        if (!m.find()) return null;

        // --- Card name filter ---
        String cardNameFilter = null;
        // Jobs named inside an identity list that also names cards, carried to the job filter below
        // — "Card Name Chloe, Job Chocobo or Card Name Chocobo" (Billy 29-048C).
        String listJobFilter = null;
        // "Card Name X with Job Y" — the one identity phrase meaning both at once rather than
        // either, so the search is told to require them together instead of taking its usual
        // reading of two filled identity filters.
        boolean identityConjunctive = m.group("cnamewithjob") != null;
        String withJobName = m.group("cnamewithjob");
        String bracketName = m.group("bracketname");
        if (withJobName != null) {
            cardNameFilter = withJobName.trim();
        } else if (bracketName != null) {
            Matcher nm = CARD_NAME_BRACKET_PATTERN.matcher(bracketName);
            if (nm.find()) cardNameFilter = nm.group(1).trim();
        } else {
            String writtenNames = m.group("cardnames");
            if (writtenNames != null) {
                String[] split = splitCardNameAndJobList(writtenNames);
                cardNameFilter = split[0];
                listJobFilter  = split[1];
            } else {
                String written = m.group("cardname");
                if (written != null) cardNameFilter = written.trim();
            }
        }

        // --- Job filter ---
        String jobFilter = null;
        String bracketJob = m.group("bracketjob");
        if (identityConjunctive) {
            jobFilter = m.group("jobwithcname").trim();
        } else if (bracketJob != null) {
            Matcher jm = JOB_BRACKET_PATTERN.matcher(bracketJob);
            if (jm.find()) jobFilter = jm.group(1).trim();
        } else {
            String writtenJob = m.group("jobnm");
            if (writtenJob != null) {
                // "Chocobo or Job Moogle or Job Ninja" → "Chocobo|Moogle|Ninja"
                String[] parts = writtenJob.trim().split("(?i)\\s+or\\s+Job\\s+");
                jobFilter = String.join("|", parts);
            } else {
                jobFilter = listJobFilter;
            }
        }

        // --- "Job X or Card Name Y" — sets both filters; OR logic applied at match time ---
        String jobnmOr = m.group("jobnmor");
        if (jobnmOr != null) {
            jobFilter = jobnmOr.trim();
            String cnameOr = m.group("cnameor");
            if (cnameOr != null) cardNameFilter = splitCardNameList(cnameOr);
        }

        // --- "Card Name X [, Card Name Y] or Job Z" — sets both filters; OR logic at match time ---
        String cnameJobnmOr = m.group("cnamejobnmor");
        if (cnameJobnmOr != null) {
            cardNameFilter = splitCardNameList(cnameJobnmOr);
            String jobNmCnameOr = m.group("jobnmcnameor");
            if (jobNmCnameOr != null) jobFilter = jobNmCnameOr.trim();
        }

        // --- Category filter ---
        String categoryFilter = m.group("category") != null ? m.group("category").trim() : null;
        String catAfterJob = m.group("catafterjob");
        if (catAfterJob != null && categoryFilter == null) categoryFilter = catAfterJob.trim();

        // --- "Category X or Job Y" — sets both filters; OR logic applied at match time ---
        String catJobOr = m.group("catjobor");
        if (catJobOr != null) {
            categoryFilter = catJobOr.trim();
            String jobCatOr = m.group("jobcator");
            if (jobCatOr != null) jobFilter = jobCatOr.trim();
        }

        // --- Element filter (e.g. "Fire or Earth" → "Fire|Earth") ---
        // preelems captures elements that precede a Job/Name filter (e.g. "Fire Job Knight");
        // elements captures elements that follow the filter (classic ordering).
        String preElemsRaw = m.group("preelems");
        String postElemsRaw = m.group("elements");
        String elementsRaw = preElemsRaw != null ? preElemsRaw : postElemsRaw;
        String elementFilter = elementsRaw != null
                ? elementsRaw.trim().replaceAll("(?i)\\s+or\\s+", "|") : null;

        // --- Exclude name (other than Card Name X) — stated either side of the cost clause ---
        String excludeNameRaw = m.group("excludename") != null
                ? m.group("excludename") : m.group("excludename2");
        String excludeName = excludeNameRaw != null ? excludeNameRaw.trim() : null;

        // --- Exclude element (other than Light or Dark) ---
        String excludeElemRaw = m.group("excludeelem");
        String excludeElem = excludeElemRaw != null ? excludeElemRaw.trim() : null;

        // --- Type flags ---
        String  targets  = m.group("targets");
        boolean anyType  = targets == null || targets.toLowerCase().startsWith("card");
        String  tgtLower;
        if (anyType || targets == null) { tgtLower = ""; }
        else                            { tgtLower = targets.toLowerCase(); }
        boolean inclForwards = anyType || tgtLower.contains("forward") || tgtLower.contains("character");
        boolean inclBackups  = anyType || tgtLower.contains("backup")  || tgtLower.contains("character");
        boolean inclMonsters = anyType || tgtLower.contains("monster") || tgtLower.contains("character");
        boolean inclSummons  = anyType || tgtLower.contains("summon");

        // --- Type exclusion (e.g. "card other than a Backup") ---
        String excludeTypeRaw = m.group("excludetype");
        if (excludeTypeRaw != null) {
            String etl = excludeTypeRaw.toLowerCase();
            if (etl.equals("forward")   || etl.equals("character")) inclForwards = false;
            if (etl.equals("backup")    || etl.equals("character")) inclBackups  = false;
            if (etl.equals("monster")   || etl.equals("character")) inclMonsters = false;
            if (etl.equals("summon"))                                inclSummons  = false;
        }

        // --- Cost filter ---
        String costStr = m.group("cost");
        // "of cost X" reads the 《X》 this ability was paid with. Exact by default — 25-051L Rem
        // fetches a Cadet costing exactly what was paid — but "of cost X or less" is printed too
        // (5-041R Lightning, 22-049H Bartz), and carries its own comparator.
        boolean costIsX = m.group("costx") != null;
        int    costVal = costIsX ? xValue
                       : costStr == null ? -1 : Integer.parseInt(costStr);
        String costCmpRaw = costIsX ? m.group("costxcmp") : m.group("costcmp");
        // "of cost 5 or 6" — numeric second value → encode as "or_6" for meetsCostConstraint
        String costCmp = (costCmpRaw != null && costCmpRaw.matches("\\d+"))
                ? "or_" + costCmpRaw : costCmpRaw;

        // --- Count ---
        String countStr = m.group("count");
        int count = (countStr != null) ? Integer.parseInt(countStr) : 1;

        // --- Destination ---
        String destText   = m.group("destination").toLowerCase();
        boolean entersDull = destText.contains("dull");
        String destination = destText.contains("hand")     ? "hand"
                           : destText.contains("field")    ? "field"
                           : destText.contains("break")    ? "breakZone"
                           : destText.contains("on top")   ? "deckTop"
                           :                                 "underTop";

        // --- Keyword filter ("1 card with Warp", "1 Forward with Brave") ---
        String traitWord = m.group("withtrait");
        CardData.Trait requireTrait = traitWord == null ? null
                : CardData.Trait.valueOf(traitWord.trim().replaceAll("\\s+", "_").toUpperCase(Locale.ROOT));

        // Build log label
        StringBuilder filterDesc = new StringBuilder();
        if (cardNameFilter  != null) filterDesc.append(" [Name ").append(cardNameFilter).append("]");
        if (jobFilter       != null) filterDesc.append(" [Job ").append(jobFilter).append("]");
        if (categoryFilter  != null) filterDesc.append(" [Cat ").append(categoryFilter).append("]");
        if (elementFilter   != null) filterDesc.append(" [").append(elementsRaw).append("]");
        if (excludeName     != null) filterDesc.append(" [not ").append(excludeName).append("]");
        if (excludeElem     != null) filterDesc.append(" [not ").append(excludeElem).append("]");
        if (requireTrait != null)     filterDesc.append(" [with ").append(requireTrait.displayName()).append("]");
        String typeDesc  = (targets != null && !anyType) ? " " + targets : "";
        String costLabel = CardFilters.formatCostFilterLabel(costVal, costCmp);

        // Secondary effect: text following this search clause (e.g. ". Gain 《C》.")
        String afterSearch = text.substring(m.end()).trim().replaceAll("^[.!,]+\\s*", "").trim();
        Consumer<GameContext> secondary = afterSearch.isEmpty() ? null : parse(afterSearch, source, xValue);

        final String fName = cardNameFilter, fJob = jobFilter, fCat = categoryFilter;
        final String fElem = elementFilter, fExclude = excludeName, fExclElem = excludeElem;
        final boolean fwd = inclForwards, bk = inclBackups, mn = inclMonsters, sm = inclSummons;
        final int fCount = count;
        final boolean fDull = entersDull;
        final CardData.Trait fTrait = requireTrait;
        final boolean fBoth = identityConjunctive;
        final PickGate fGate = gate;
        final boolean fSilent = suppressAutoAbilities;
        final int fTotalCost = maxTotalCost;
        final int fCost = costVal;
        final String fCostCmp = costCmp;
        Consumer<GameContext> search = ctx -> {
            ctx.logEntry("Effect: Search deck for " + fCount + filterDesc + typeDesc + costLabel
                    + (fBoth ? " [name and job together]" : "")
                    + (fGate != PickGate.ANY ? " [" + fGate.hint().replaceAll("^,\\s*", "") + "]" : "")
                    + (fTotalCost >= 0 ? " [total cost " + fTotalCost + " or less]" : "")
                    + (fSilent ? " [no auto-abilities]" : "")
                    + " → " + destination + (fDull ? " dull" : ""));
            if (fGate != PickGate.ANY || fSilent || fTotalCost >= 0) {
                ctx.searchDeckForCardWithRiders(fwd, bk, mn, sm, fCost, fCostCmp, fName, fJob,
                        fCat, fElem, fExclude, fExclElem, destination, fCount, fDull, fTrait,
                        fGate, fSilent, fTotalCost);
            } else if (fBoth) {
                ctx.searchDeckForNamedCardWithJob(fwd, bk, mn, sm, fCost, fCostCmp, fName, fJob,
                        fElem, fExclude, fExclElem, destination, fCount, fDull, fTrait);
            } else {
                ctx.searchDeckForCard(fwd, bk, mn, sm, fCost, fCostCmp, fName, fJob, fCat, fElem, fExclude, fExclElem, destination, fCount, fDull, fTrait);
            }
            if (secondary != null) secondary.accept(ctx);
        };

        // A "You may" immediately before this "Search for …" makes the search optional.
        //
        // The prompt must come before the search runs, not after: searching is a public event
        // that opponents' abilities react to (5-130R Tonberry, 13-034H Remedi, 25-111H The
        // Emperor all punish a search), so a player who declines has to not have searched at
        // all. Declining also fizzles the effect, suppressing any "If you do so" payoff.
        //
        // Anchored to the text right before the match rather than looked for anywhere in the
        // ability: a "you may" attached to some other clause must not make this search optional.
        if (!YOU_MAY_IMMEDIATELY_BEFORE.matcher(text.substring(0, m.start())).find()) return search;
        return ctx -> {
            if (!ctx.promptYouMay("Search your deck? Declining means you did not search.")) {
                ctx.logEntry("Declined to search — no search takes place");
                ctx.markEffectFizzled();
                return;
            }
            search.accept(ctx);
        };
    }

    /**
     * Parses "Search for 1 Card Name X and remove it from the game. [If|When] you do so,
     * <effect>." - 1-093H Vanille, 20-047H Jenova Dreamweaver.
     *
     * <p>The search itself is mandatory; what the "if you do so" gates on is whether it found
     * anything, since the deck may hold no copy of the named card. That distinction is why this
     * cannot be built out of the ordinary search parser plus a followup: the payoff has to see the
     * search's outcome, which only {@link GameContext#searchDeckForCard} reports.
     *
     * <p>The payoff goes back through {@code parse()} so it can be anything. Only when that comes
     * back empty is the local "return <self> onto the field" reading tried - see
     * {@link ActionResolverPatterns#RETURN_SOURCE_ONTO_FIELD} for why that wording is read here
     * rather than added to the shared play-onto-field pattern.
     */
    static Consumer<GameContext> tryParseSearchNamedRfgThenIfDoSo(String text, CardData source) {
        Matcher m = SEARCH_NAMED_RFG_THEN_IF_DO_SO.matcher(text.trim());
        if (!m.matches()) return null;
        String searchName = m.group("name").trim();
        String effectText = m.group("effect").trim();

        Consumer<GameContext> payoff = parse(effectText, source);
        if (payoff == null) payoff = returnSourceOntoFieldPayoff(effectText, source);
        if (payoff == null) return null;

        final Consumer<GameContext> resolvedPayoff = payoff;
        return ctx -> {
            ctx.logEntry("Effect: Search 1 Card Name " + searchName + " -> Removed From Game");
            // Every filter left open but the name: the named card may be of any type.
            boolean removed = ctx.searchDeckForCard(false, false, false, false,
                    -1, null, searchName, null, null, null, null, null,
                    "removedFromGame", 1, false, null);
            if (removed) {
                resolvedPayoff.accept(ctx);
            } else {
                ctx.logEntry("Effect: no " + searchName + " found - \"if you do so\" skipped");
            }
        };
    }

    /** "Return [source] onto the field [dull]" - plays the source back out of its Break Zone. */
    private static Consumer<GameContext> returnSourceOntoFieldPayoff(String text, CardData source) {
        if (source == null) return null;
        Matcher m = RETURN_SOURCE_ONTO_FIELD.matcher(text.trim());
        if (!m.matches()) return null;
        String name = m.group("name").trim();
        if (!name.equalsIgnoreCase(source.name())) return null;
        boolean dull = m.group("dull") != null;
        return ctx -> {
            ctx.logEntry("Effect: Return " + name + " from Break Zone -> field" + (dull ? " dull" : ""));
            ctx.playAllByNameFromOwnBreakZoneDull(name, dull);
        };
    }
}
