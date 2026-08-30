package shufflingway;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static shufflingway.ActionResolverBreak.*;
import static shufflingway.ActionResolverChoose.*;
import static shufflingway.ActionResolverCost.*;
import static shufflingway.ActionResolverDamage.*;
import static shufflingway.ActionResolverFieldAbility.*;
import static shufflingway.ActionResolverGate.*;
import static shufflingway.ActionResolverHand.*;
import static shufflingway.ActionResolverPatterns.*;
import static shufflingway.ActionResolverPlay.*;
import static shufflingway.ActionResolverPower.*;
import static shufflingway.ActionResolverRestriction.*;
import static shufflingway.ActionResolverSearch.*;
import static shufflingway.ActionResolverState.*;

/**
 * Parses Action Ability effect text into executable game effects and resolves
 * them against the live game state via a {@link GameContext}.
 *
 * <h3>Adding new effect types</h3>
 * <ol>
 *   <li>Add a {@code static final Pattern} for the new text pattern.</li>
 *   <li>Add a {@code tryParse*} method that returns a {@code Consumer<GameContext>}
 *       (or {@code null} if the text does not match).</li>
 *   <li>Call it from {@link #parse(String)}.</li>
 * </ol>
 */
public class ActionResolver {

    // -------------------------------------------------------------------------
    // Patterns
    // -------------------------------------------------------------------------

    /**
     * Number of top-of-deck cards {@code effectText} removes from the game (1 for "the top card…",
     * N for "the top N cards…"), or {@code 0} if it has no such removal. Used to gate activation:
     * you cannot remove the top card(s) of an empty (or too-small) deck, so the ability is illegal then.
     */
    public static int topDeckRemovalCount(String effectText) {
        if (effectText == null) return 0;
        Matcher m = REMOVE_TOP_OF_DECK_FROM_GAME.matcher(effectText);
        if (!m.find()) return 0;
        String c = m.group("count");
        return c != null ? Integer.parseInt(c) : 1;
    }

    // ---- Standalone damage-shield patterns (apply globally or to a named card) --------

    /**
     * Returns {@code true} if the effect grants the source card itself immunity to ability/summon
     * damage for the turn ("if [cardName] is dealt damage by Summons or abilities, it becomes 0").
     * These are reactive defensive abilities: the CPU should use them in response to opponent
     * actions, not proactively during its own main phase.
     */
    public static boolean isReactiveDamageShield(String effectText, CardData source) {
        if (source == null || effectText == null) return false;
        Matcher m = STANDALONE_NULLIFY_ABILITY_DAMAGE.matcher(effectText);
        return m.find() && m.group("card").trim().equalsIgnoreCase(source.name());
    }

    /** Returns {@code true} if the effect is "During this turn, your opponent cannot search." */
    public static boolean isOpponentCannotSearchAbility(String effectText) {
        return effectText != null && OPPONENT_CANNOT_SEARCH_THIS_TURN.matcher(effectText).find();
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Matches the "If you paid the extra cost" conditional clause in effect text. {@code base}
     * may be empty when the condition is the entire ability (e.g. Summoner: "If you paid the
     * extra cost, your opponent selects …") rather than a suffix on an unconditional lead-in
     * (e.g. Samurai: "Choose 1 Forward … If you paid the extra cost, break it.").
     * Groups: {@code base}, {@code also}, {@code effect}, {@code instead}.
     */
    private static final Pattern IF_PAID_EXTRA_COST = Pattern.compile(
        "(?i)(?<base>.*?)\\s*\\b[Ii]f\\s+you\\s+paid\\s+the\\s+extra\\s+cost(?:\\s+and\\s+[^,]+)?,\\s+" +
        "(?<also>also\\s+)?" +
        "(?<effect>.+?)" +
        "(?<instead>\\s+instead)?" +
        "\\.?\\s*$",
        Pattern.DOTALL
    );

    /**
     * Transforms summon effect text to apply the paid branch of an extra-cost conditional.
     * <ul>
     *   <li><b>Additive</b> ("also …"): appends the paid effect to the base.</li>
     *   <li><b>Replacement without "it"</b>: replaces the entire base text.</li>
     *   <li><b>Replacement with "it"</b>: keeps earlier base sentences, replaces the last.</li>
     *   <li><b>No conditional</b> (Titan): text is returned unchanged.</li>
     * </ul>
     */
    public static String applyExtraCostPaid(String text) {
        java.util.regex.Matcher m = IF_PAID_EXTRA_COST.matcher(text);
        if (!m.find()) return text;

        String base      = m.group("base").trim();
        boolean isAlso   = m.group("also") != null;
        String effect    = m.group("effect").trim();
        boolean isInstead = m.group("instead") != null;

        String cap = Character.toUpperCase(effect.charAt(0)) + effect.substring(1);
        if (!cap.endsWith(".")) cap += ".";

        if (isAlso) {
            return base.isEmpty() ? cap : base + " " + cap;
        }
        if (isInstead) {
            if (Pattern.compile("(?i)\\bit\\b").matcher(effect).find()) {
                String withoutLast = removeLastSentence(base);
                return withoutLast.isEmpty() ? cap : withoutLast + " " + cap;
            }
            return cap;
        }
        // Additive without "also" keyword (Leviathan-style): append
        return base.isEmpty() ? cap : base + " " + cap;
    }

    /** Strips the "If you paid the extra cost, … ." clause (single- or multi-sentence) from the end of {@code text}. */
    public static String stripExtraCostClause(String text) {
        return text.replaceAll("(?i)\\s*If\\s+you\\s+paid\\s+the\\s+extra\\s+cost.*$", "").trim();
    }

    private static String removeLastSentence(String text) {
        int last = text.lastIndexOf('.');
        if (last <= 0) return "";
        int prev = text.lastIndexOf('.', last - 1);
        return prev < 0 ? "" : text.substring(0, prev + 1).trim();
    }

    /**
     * Attempts to parse {@code effectText} into a ready-to-execute
     * {@link Consumer}{@code <GameContext>}.
     *
     * @return the effect consumer, or {@code null} if the text is not yet supported
     */
    public static Consumer<GameContext> parse(String effectText) {
        return parse(effectText, null, 0);
    }

    /**
     * Attempts to parse {@code effectText} into a ready-to-execute
     * {@link Consumer}{@code <GameContext>}.
     *
     * @param source the card that owns this ability; required for standalone self-buff effects
     * @return the effect consumer, or {@code null} if the text is not yet supported
     */
    public static Consumer<GameContext> parse(String effectText, CardData source) {
        return parse(effectText, source, 0);
    }

    /**
     * @param xValue the CP amount paid into {@code 《X》}; {@code 0} when the ability has no X cost
     */
    public static Consumer<GameContext> parse(String effectText, CardData source, int xValue) {
        effectText = stripExBurstPrefix(effectText);
        // Strip leading "Then, " connector that appears when this text is a secondary clause.
        effectText = effectText.replaceFirst("(?i)^Then,?\\s+", "").trim();
        // Strip a leading "also" the same way — purely additive phrasing carried over from the
        // clause this text follows ("…, also draw 1 card." — Odin 21-084H), never a verb of its own.
        effectText = effectText.replaceFirst("(?i)^also\\s+", "").trim();
        Consumer<GameContext> result;

        // Must precede every effect pattern, tryParseTrailingDraw included. The condition is the
        // last sentence of the text, so every parser ahead of it matched the base under find(),
        // claimed the whole ability and dropped the condition: Shiva 16-028C discarded from the
        // opponent's hand unconditionally, Ixion 16-086C lost its board-wide half entirely, and
        // Bahamut 16-016C dealt 9000 with the "12000 instead" never applied. Leviathan 16-125C is
        // the reason this sits ahead of the trailing-draw rule specifically: its conditional half
        // is "also draw 1 card, then discard 1 card", which that rule would otherwise split off
        // the end of the sentence carrying the condition.
        //
        // Anchored with matches() over the whole text, so it claims nothing else.
        result = tryParseCastPaymentElementsGate(effectText, source, xValue);
        if (result != null) return result;

        // The same trailing shape with a different condition, and here for the same reason: the
        // gate is the last sentence, so every parser below matched the base under find(), claimed
        // the whole ability and dropped it. 12-039C Alexander drew one card on any turn.
        result = tryParseCastCountGate(effectText, source, xValue);
        if (result != null) return result;

        // The negated member of the same family, and a prefix like the two below rather than the
        // trailing sentence above: 9-099R Livia's payoff is "put Livia into the Break Zone", which
        // every parser below claims off the gate's tail and runs however she was paid for — the
        // one reading of the card under which she never stays on the field at all.
        result = tryParseCastPaymentElementsNotIncludedGate(effectText, source, xValue);
        if (result != null) return result;

        // Beside its sibling and for the same reason: the gate is a prefix, so every parser
        // below would claim the effect off its tail and run it whatever the cast was paid with.
        result = tryParseCastPaymentElementCpGate(effectText, source, xValue);
        if (result != null) return result;

        // The strict sibling of the gate above — "only paid with Ice CP" rather than "included
        // Lightning CP" — and here for the same reason. Left to the general matchers, 7-029H
        // Kefka froze the opponent's board and 7-046R Vata activated its Wind Backups on every
        // cast, whatever the CP had actually been.
        result = tryParseCastPaymentOnlyElementCpGate(effectText, source, xValue);
        if (result != null) return result;

        // The "exactly N different Elements" member of the same family. CardData's
        // FA_CAST_PAYMENT_ELEMENTS strips only the "N or more" wording into
        // AutoAbility.castPaymentMinElements, so this one survives as a prefix and had its guarded
        // half claimed off its tail — 9-021R Varis searched unconditionally.
        result = tryParseCastPaymentExactElementsGate(effectText, source, xValue);
        if (result != null) return result;

        // Beside the gate above and read for the same reason: both are prefixes, and every parser
        // below matches with find(), so a gate left for later has its guarded half claimed off its
        // tail and run whatever the condition says.
        result = tryParseCrystalHeldGate(effectText, source, xValue);
        if (result != null) return result;

        // Both halves of the offer read together. The consequence sentence matches the mass-effect
        // matchers on its own, so leaving it to them applied the punishment whether or not the
        // opponent paid the discard to avoid it (7-029H Kefka).
        result = tryParseOpponentMayDiscardElseEffect(effectText, source, xValue);
        if (result != null) return result;

        // Must precede every effect pattern: a trailing "Draw 1 card." rides along behind a
        // complete effect, and whichever pattern matches the leading sentences claims the whole
        // text with find() and returns, so the sentence-splitting fallback at the end of this
        // method is never reached and the draw is dropped. Recurses for the head, so the leading
        // effect still resolves through the normal chain below.
        result = tryParseTrailingDraw(effectText, source, xValue);
        if (result != null) return result;

        // Must precede tryParseIndependentSentences: its two sentences carry no pronoun back to
        // each other, so that rule accepts them and resolves the replay through the Break Zone —
        // but the removal in front of it has just put the card in the RFG zone.
        result = tryParseRemoveSelfThenPlaySelfOntoField(effectText, source);
        if (result != null) return result;

        // Must precede tryParseIndependentSentences for the same reason as the parser above, and
        // it is the same shape a turn later: both of 23-051L Hope's sentences name Hope outright,
        // so nothing refers back, the splitter accepts them and resolves them separately -- the
        // removal happened and the clause returning Hope at the next Main Phase 1 was discarded.
        result = tryParseRemoveSelfReturnNextMainPhase1(effectText, source);
        if (result != null) return result;

        // Must precede tryParseIndependentSentences for the same reason: its three sentences
        // refer back to each other only through "them" and "the other groups", neither of which
        // that rule reads as a backward reference, so it took the ability apart and resolved the
        // last sentence on its own -- every Forward on the board into the Break Zone.
        result = tryParseDivideOppForwardsIntoGroups(effectText);
        if (result != null) return result;

        // Must precede tryParseIndependentSentences for the same reason again: "these removed
        // cards" and "them" are all that tie Aemo's three sentences together, and neither counts
        // as a backward reference, so the splitter resolved the last one alone -- a hand handed
        // back that had never been taken away.
        result = tryParseOppRfgWholeHandFaceDown(effectText);
        if (result != null) return result;

        // Must precede tryParseIndependentSentences: the two sentences of a "Choose any number of
        // [types]. Cancel their effects." refer to each other only through "their", which that rule
        // does not read as a backward reference, so it split the ability and resolved the halves
        // apart -- a selection of field Characters, then a cancel with nothing chosen.
        result = tryParseCancelAnyNumberAbilitiesOnStack(effectText);
        if (result != null) return result;

        // Same reason, generalised: whichever sentence a pattern happens to match claims the whole
        // ability and the rest is discarded. Where every sentence stands alone, resolve them all.
        // Must stay ahead of the effect patterns for the same reason tryParseTrailingDraw does.
        result = tryParseIndependentSentences(effectText, source, xValue);
        if (result != null) return result;

        // "Cast it as though you owned it" family — matched early because the highly specific
        // borrowed-cast phrasing would otherwise be intercepted by generic Choose/Remove matchers.
        result = tryParseOppRfpTopDeckCastable(effectText);
        if (result != null) return result;

        result = tryParseChooseFromOppBzCastable(effectText);
        if (result != null) return result;

        result = tryParseChooseSummonsFromBzCastable(effectText);
        if (result != null) return result;

        result = tryParseChooseSummonInBzMaxCostFreeCastRfg(effectText);
        if (result != null) return result;

        result = tryParseSelectFollowingActions(effectText, source);
        if (result != null) return result;

        // Must precede tryParseWhenYouDoSoSequence: Zidane-style text contains "If you do so"
        // which that parser would split, causing it to match first via OPPONENT_DRAW on the tail.
        result = tryParseRevealHandOptPickDiscardOppDraw(effectText);
        if (result != null) return result;

        result = tryParseRevealHandOptPickRfpOppDraw(effectText);
        if (result != null) return result;

        // Must precede tryParseWhenYouDoSoSequence: that parser resolves both halves independently,
        // and a bare "pay 《…》" is not an effect it can resolve, so the optional cost would be lost.
        result = tryParseMayPayCostThenEffect(effectText, source, xValue);
        if (result != null) return result;

        // Must precede tryParseWhenYouDoSoSequence: that parser splits on "If you do so" and
        // resolves the halves independently, which turns 29-116H Madeen's "remove the chosen
        // Forward from the game" into a bare remove-by-name and loses the search that gates it.
        result = tryParseChooseMaySearchRfgThenElse(effectText, source, xValue);
        if (result != null) return result;

        // Must precede tryParseWhenYouDoSoSequence: that parser resolves both halves independently,
        // and this payoff's "the same number" is however many counters the first half took off — a
        // quantity that exists only inside the one resolution and cannot survive the split.
        result = tryParseRemoveAnyCountersThenChooseSameNumber(effectText, source);
        if (result != null) return result;

        result = tryParseWhenYouDoSoSequence(effectText, source, xValue);
        if (result != null) return result;

        // Must precede every consequence pattern: those match with find(), so left alone they would
        // claim the text after the gate and resolve the consequence unconditionally.
        result = tryParseIfNotPayOrElse(effectText, source, xValue);
        if (result != null) return result;

        // Same reasoning: the mass-break matcher would find "break all the Forwards opponent
        // controls" in the tail and apply it with no regard for the pile threshold in front of it.
        result = tryParseRemoveTopThenPileThreshold(effectText, source);
        if (result != null) return result;

        result = tryParseAddRemovedBySourceAbilityToHand(effectText, source);
        if (result != null) return result;

        // Beside the other resolution-time gates, and ahead of every parser that could match its
        // inner effect on its own: the gate is a prefix, so a find()-based reader of the payoff
        // would resolve it unconditionally.
        result = tryParseIfSourceUsedSpecialsThisTurn(effectText, source, xValue);
        if (result != null) return result;

        result = tryParseIfOwnForwardFormedParty(effectText, source, xValue);
        if (result != null) return result;

        result = tryParseIfOppDiscardedThisTurn(effectText, source, xValue);
        if (result != null) return result;

        result = tryParseIfControlAtMost(effectText, source, xValue);
        if (result != null) return result;

        result = tryParseIfAllHaveElement(effectText, source, xValue);
        if (result != null) return result;

        // Must precede the generic damage/draw matchers: their leading ".+?" would otherwise
        // swallow the "if each player has no cards…" clause and drop the condition entirely.
        result = tryParseIfEachPlayerEmptyHand(effectText, source, xValue);
        if (result != null) return result;

        result = tryParseIfNDiffElements(effectText, source, xValue);
        if (result != null) return result;

        result = tryParseIfControlCondOtherThan(effectText, source, xValue);
        if (result != null) return result;

        result = tryParseControlConditionGate(effectText, source, xValue);
        if (result != null) return result;

        result = tryParseWarpCounterCountGate(effectText, source, xValue);
        if (result != null) return result;

        // Must precede tryParseControlGatedInsteadUpgrade: that parser resolves the base and the
        // alternative independently, and 4-090R Biggs' alternative is "it gains +2000 power" -- an
        // "it" belonging to the Forward the base half chose, so alone it has nothing to boost.
        result = tryParseChooseGatedBoostInstead(effectText, source, xValue);
        if (result != null) return result;

        result = tryParseControlGatedInsteadUpgrade(effectText, source, xValue);
        if (result != null) return result;

        result = tryParseOpponentControlsCardGate(effectText, source, xValue);
        if (result != null) return result;

        result = tryParseIfOppControlsNOrMoreCondTypeGate(effectText, source, xValue);
        if (result != null) return result;

        result = tryParseDiscardConditionalElement(effectText, source, xValue);
        if (result != null) return result;

        result = tryParseDiscardConditionalElementSingle(effectText, source, xValue);
        if (result != null) return result;

        result = tryParseDiscardConditionalTargetLoseAbilities(effectText);
        if (result != null) return result;

        result = tryParseDiscardConditionalSelfBoostInstead(effectText, source, xValue);
        if (result != null) return result;

        result = tryParseDrawDiscardIfMultiElement(effectText);
        if (result != null) return result;

        result = tryParseIfCastAtLeast(effectText, source, xValue);
        if (result != null) return result;

        result = tryParseSelectNumber(effectText, source);
        if (result != null) return result;

        result = tryParseAllMonstersTemporaryForward(effectText);
        if (result != null) return result;

        result = tryParseBecomeForwardUntilEot(effectText, source);
        if (result != null) return result;

        result = tryParseForEachJobAndNameDealDamageToForwards(effectText);
        if (result != null) return result;

        result = tryParseDealNForEachJobOrNameToOppForwards(effectText);
        if (result != null) return result;

        result = tryParseDealBasePlusBzNameDamageToForwards(effectText);
        if (result != null) return result;

        result = tryParseSelfGainsWhenAttacksEOT(effectText, source);
        if (result != null) return result;

        result = tryParseDealDamageToForwardsForEach(effectText);
        if (result != null) return result;

        result = tryParseDealDamageToForwardsExceptElement(effectText);
        if (result != null) return result;

        result = tryParseRfpAllFwdExceptElementsThenTwiceDeck(effectText);
        if (result != null) return result;

        // Must precede tryParseDealDamageToForwards: that one reads a stated number and matches
        // with find(), so it never claimed this text — but it is the parser this belongs beside,
        // and the amount here comes from the trigger rather than from the words.
        result = tryParseDealSameAmountToAllForwardsExcept(effectText, source, xValue);
        if (result != null) return result;

        result = tryParseDealDamageToForwards(effectText);
        if (result != null) return result;

        result = tryParseDivideDamageEquallyAmongAll(effectText);
        if (result != null) return result;

        result = tryParseNoForwardCostCannotAttack(effectText);
        if (result != null) return result;

        result = tryParseOwnForwardsCannotBeChosenByExBurst(effectText);
        if (result != null) return result;

        result = tryParseExBurstSuppression(effectText);
        if (result != null) return result;

        result = tryParseDealHalfPowerDamageToForwards(effectText);
        if (result != null) return result;

        result = tryParseDealPowerMinusNDamageToForwards(effectText);
        if (result != null) return result;

        result = tryParseDealHalfSourcePowerDamageToForwards(effectText);
        if (result != null) return result;

        result = tryParseDamageToCombatBlocker(effectText);
        if (result != null) return result;

        result = tryParseChooseOneEach(effectText, source);
        if (result != null) return result;

        result = tryParseChooseForwardRedirectToNamed(effectText);
        if (result != null) return result;

        result = tryParseChooseFormerLatter(effectText, source);
        if (result != null) return result;

        result = tryParseChooseFwdPowerLeAndOptOppBzFwdRfp(effectText);
        if (result != null) return result;

        result = tryParseChooseThreeMixedTypes(effectText, source);
        if (result != null) return result;

        result = tryParseChooseTwoMixedTypes(effectText, source);
        if (result != null) return result;

        result = tryParseChooseForwardDealSelfDamageBreakIfCostLeDamage(effectText);
        if (result != null) return result;

        result = tryParseChooseForwardSharedPowerLoss(effectText, source);
        if (result != null) return result;

        result = tryParseChooseOppFwdDynCostBreak(effectText);
        if (result != null) return result;

        result = tryParseChooseFwdPowerInferiorToSource(effectText, source);
        if (result != null) return result;

        result = tryParseChooseFwdBzCostInferiorToRemovedPlay(effectText);
        if (result != null) return result;

        result = tryParseChooseOppFwdGainsSpecialAbilityFreeOnce(effectText, source);
        if (result != null) return result;

        result = tryParseUseSpecialAbilityUsedThisTurn(effectText, source);
        if (result != null) return result;

        result = tryParseChooseOppDamagedFwdIfHasAbilityBreak(effectText);
        if (result != null) return result;

        result = tryParseChooseAsManyAsFieldCount(effectText, source);
        if (result != null) return result;

        result = tryParseChooseAsManyAsBzRfgJobCount(effectText);
        if (result != null) return result;

        result = tryParseChooseCounterScaleCharsActivate(effectText, xValue);
        if (result != null) return result;

        result = tryParseChooseAnyNumberReturnToHand(effectText);
        if (result != null) return result;

        // Checked ahead of tryParseChooseCharacter: its "Summons?" target noun would otherwise
        // match bare "Choose 1 Summon. If your opponent doesn't pay..." text first, and its generic
        // followup dispatch's unanchored "Cancel its effect" substring match would misfire on the
        // conditional-pay clause as if it were a plain unconditional cancel.
        result = tryParseCancelStackEntryUnlessPay(effectText);
        if (result != null) return result;

        // Checked ahead of tryParseChooseCharacter: these "choose … Forward(s) …" compounds would
        // otherwise be claimed by ChooseCharacter's generic followup dispatch, which only partially
        // handles them (the reveal-cost-parity branch, and the ability-granting forms).
        result = tryParseChooseFwdRevealCostParity(effectText);
        if (result != null) return result;

        result = tryParseChooseForwardsGainAbilityEot(effectText);
        if (result != null) return result;

        result = tryParseChooseForwardPlacePetrification(effectText);
        if (result != null) return result;

        result = tryParseChooseOwnFwdBoostProtectionsOrAllIfDmg(effectText);
        if (result != null) return result;

        result = tryParseActivateAllOwnFwdsGainProtections(effectText);
        if (result != null) return result;

        result = tryParseRemoveAllCountersFromSelf(effectText, source);
        if (result != null) return result;

        // Must precede tryParseChooseCharacter: that parser matches the choose half and treats the
        // control gate as a detached secondary, which leaves both the play and the sacrifice
        // unresolved (it described this card as "ChooseCharacter / ? + IfControl(…: ?)").
        result = tryParseChooseTwoBzFwdPlayIfControl(effectText, source);
        if (result != null) return result;

        // Must precede tryParseChooseCharacter: that parser matches the choose half alone and
        // returns, silently discarding the "At the end of your opponent's turn, …" clause.
        result = tryParseChooseThenEndOfOppTurnAction(effectText, source, xValue);
        if (result != null) return result;

        // Must precede tryParseChooseCharacter: that parser matches the first of the two choose
        // clauses alone and applies the effect to it, silently dropping the second (19-114L Cloud
        // broke a Forward of cost 4 or less and never one of cost 5 or more). Placed here, after
        // every specific two-clause parser, because its descriptors are broad enough to claim
        // their texts.
        result = tryParseChooseTwoJointAction(effectText, source);
        if (result != null) return result;

        // Must precede tryParseChooseCharacter: that chain reads "as many … as you want" as an
        // unbounded choose and has nowhere to put the total-cost budget, so it would offer every
        // Forward on the board and break the lot.
        result = tryParseChooseForwardsTotalCostBreak(effectText);
        if (result != null) return result;

        // Must precede tryParseChooseCharacter for a related reason: that chain makes the whole
        // selection in one dialog and hands its followup an unordered set, with nothing to say
        // which pick takes which of the three amounts. It claimed this text and then found no
        // followup branch for the wording, so Palom's Meteor logged "followup not yet implemented"
        // and dealt nothing at all.
        result = tryParseChooseTieredDamage(effectText);
        if (result != null) return result;

        // Must precede tryParseChooseCharacter: that chain reads one filter per sentence, so it
        // claimed Xande 10-008L's first pick and left the second's cost with nothing to attach to.
        result = tryParseChooseTwoCostsFromBzPlayBoth(effectText);
        if (result != null) return result;

        result = tryParseChooseCharacter(effectText, source, xValue);
        if (result != null) return withAiTargetPreference(effectText, result);

        result = tryParseIfSelfFwdReceivedDamageDraw(effectText, source);
        if (result != null) return result;

        result = tryParseElementChange(effectText, source);
        if (result != null) return result;

        result = tryParseDelayedEffect(effectText);
        if (result != null) return result;

        result = tryParsePlayerCannotCastSummons(effectText);
        if (result != null) return result;

        // Must precede tryParseCannotBeChosenStandalone: that parser matches with find() and would
        // claim this text off the protection clause quoted inside it, applying the grant and
        // silently dropping the "power becomes N" half (23-100L Young Excenmille).
        result = tryParseSelfGainsAndBasePowerBecomesPermanent(effectText, source);
        if (result != null) return result;

        // Alongside the parser above for the same reason: the quoted permission it hands out is
        // what tryParseCannotBeChosenStandalone and the multi-attack readers would each claim a
        // piece of. Order between the two is free -- one ends in a power clause, the other in the
        // quote itself -- but they read as a pair.
        result = tryParseSelfGainsTraitsAndQuotedPermanent(effectText, source);
        if (result != null) return result;

        // Must precede tryParseCannotBeChosenStandalone: that parser matches with find() and would
        // claim this text off its opening clause, applying an opponent-scoped shield in place of the
        // symmetric one the sentence asks for and dropping the trait grant that follows.
        result = tryParseSelfCannotBeChosenByAnyAndGainsTraits(effectText, source);
        if (result != null) return result;

        result = tryParseCannotBeChosenStandalone(effectText, source);
        if (result != null) return result;

        result = tryParseCannotBecomeDullOpp(effectText, source);
        if (result != null) return result;

        result = tryParseCannotBeReturnedToHandOpp(effectText, source);
        if (result != null) return result;

        result = tryParseCharactersCannotBeReturnedToHandOpp(effectText);
        if (result != null) return result;

        result = tryParseCannotBePutIntoBzOpp(effectText, source);
        if (result != null) return result;

        result = tryParseStandaloneCannotAttackOrBlock(effectText, source);
        if (result != null) return result;

        result = tryParseNegateAllDamage(effectText);
        if (result != null) return result;

        result = tryParsePlayerNextDamageZeroRedirect(effectText);
        if (result != null) return result;

        result = tryParsePlayerNextDamageZero(effectText);
        if (result != null) return result;

        result = tryParseCancelAutoAbilityAndDamageIfForward(effectText);
        if (result != null) return result;

        result = tryParseRedirectChosenTarget(effectText, source);
        if (result != null) return result;

        // Ahead of the cancel family it borrows its shape from: this text ends in "triggers the
        // same auto-ability" rather than "Cancel its effect", so neither claims the other, but the
        // two belong together.
        result = tryParseCopyChosenAutoAbilityOnStack(effectText, source);
        if (result != null) return result;

        result = tryParseCancelAbilityOnStack(effectText);
        if (result != null) return result;

        result = tryParseCancelChosenTargetUnlessPay(effectText);
        if (result != null) return result;

        result = tryParseCancelChosenTargetUnlessDiscard(effectText);
        if (result != null) return result;

        // Anchored, so it only claims an ability that is nothing but the action; the target is
        // the triggering card, preloaded by AutoAbilityTriggers.
        result = tryParseTriggeredTargetAction(effectText, xValue);
        if (result != null) return result;

        result = tryParseCancelChosenTargetBare(effectText);
        if (result != null) return result;

        result = tryParseCancelTriggeringSummon(effectText);
        if (result != null) return result;

        result = tryParseIfOppNotPayAction(effectText);
        if (result != null) return result;

        // Shares its opening two sentences with tryParseCancelChosenRevealTopIfType; both are
        // end-anchored on their own tail, so either order is safe, but they belong together.
        result = tryParseRevealTopToHandIfTypeElseTopOrBottom(effectText);
        if (result != null) return result;

        result = tryParseCancelChosenRevealTopIfType(effectText);
        if (result != null) return result;

        result = tryParseCancelChosenMillTopIfNotType(effectText);
        if (result != null) return result;

        result = tryParseCancelChosenMillBothIfSameType(effectText);
        if (result != null) return result;

        result = tryParseCancelSummonTargetingMyCharacter(effectText);
        if (result != null) return result;

        // Must precede tryParseCancelStackEntry: the two share their first sentence, and that
        // parser find()s on it, so it would cancel 29-012H Neon's chosen effect outright instead
        // of letting it resolve with its damage blanked.
        result = tryParseChooseStackEntryZeroItsDamage(effectText);
        if (result != null) return result;

        result = tryParseCancelStackEntry(effectText);
        if (result != null) return result;

        result = tryParseDullAllOppFwdsPowerLeSource(effectText, source);
        if (result != null) return result;

        result = tryParseRevealTopBreakSameCostAddToHand(effectText);
        if (result != null) return result;

        // Must precede tryParseAllFieldEffect. Every parser below matches with find(), so the
        // "break all …" tail of a delayed clause would be claimed here and run immediately,
        // silently discarding the "at the end of your opponent's turn" that governs it.
        result = tryParseEndOfOppTurnDelayedEffect(effectText, source);
        if (result != null) return result;

        result = tryParsePlaceCounterOnAllForwards(effectText);
        if (result != null) return result;

        // Must precede tryParseAllFieldEffect: that one matches with find() and would claim the
        // sweep sentence on its own, silently dropping the draw that counts what the sweep woke up.
        result = tryParseAllFieldActivateThenDraw(effectText);
        if (result != null) return result;

        // Must precede tryParseAllFieldEffect: that one refuses a power filter rather than
        // dropping it, so this is the only parser that reads 14-062L's sweep and the payoff
        // counting what it broke.
        result = tryParseBreakForwardsBelowSelfPower(effectText, source);
        if (result != null) return result;

        result = tryParseAllFieldEffect(effectText);
        if (result != null) return result;

        result = tryParseFieldPowerGrantPassive(effectText);
        if (result != null) return result;

        result = tryParseAllForwardsSameElementAsNamedPowerBoost(effectText);
        if (result != null) return result;

        result = tryParsePartyForwardsPowerBoost(effectText);
        if (result != null) return result;

        // Must precede tryParseAllFieldPowerBoost only for tidiness -- that pattern needs a power
        // figure and cannot claim a trait-only strip -- but the two describe the same board, so
        // they are kept together.
        result = tryParseAllOppForwardsLoseTraitsEot(effectText);
        if (result != null) return result;

        result = tryParseAllFieldPowerBoost(effectText);
        if (result != null) return result;

        result = tryParseAllFieldJobCardNamePowerBoost(effectText);
        if (result != null) return result;

        result = tryParseTwoCardNamesPowerBoost(effectText);
        if (result != null) return result;

        result = tryParseAllFieldJobPowerBoost(effectText);
        if (result != null) return result;

        result = tryParseAllFieldJobKeywordGrant(effectText);
        if (result != null) return result;

        result = tryParseAllFieldKeywordGrant(effectText);
        if (result != null) return result;

        // The quoted-protection twin of the grant above. Ordered after it and not before: the
        // two share their whole opening and differ only in what follows "gain", so whichever
        // runs first has to be the one that cannot claim the other's text — this one is anchored
        // whole and requires a quote, the keyword one scans with find().
        result = tryParseAllFieldQuotedProtectionGrant(effectText);
        if (result != null) return result;

        result = tryParseUntilEotDualPowerShift(effectText);
        if (result != null) return result;

        result = tryParseUntilEotAllFieldPowerBoost(effectText);
        if (result != null) return result;

        result = tryParseReturnAllToHand(effectText);
        if (result != null) return result;

        result = tryParseStandalonePowerBoostAndAttackTrigger(effectText, source);
        if (result != null) return result;

        result = tryParseStandalonePowerBoostAndCannotBeChosen(effectText, source);
        if (result != null) return result;

        result = tryParseStandaloneGainsTraitsAndCannotBeBlocked(effectText, source);
        if (result != null) return result;

        result = tryParseStandaloneGainsTraitsAndCannotBeBlockedTrailing(effectText, source);
        if (result != null) return result;

        result = tryParseStandaloneGainsCannotBeBlocked(effectText, source);
        if (result != null) return result;

        result = tryParseSelfBasePowerBecomesUntil(effectText, source);
        if (result != null) return result;

        result = tryParseStandalonePowerBoostUntil(effectText, source);
        if (result != null) return result;

        result = tryParseStandaloneDoublePowerUntil(effectText, source);
        if (result != null) return result;

        result = tryParseStandaloneDoublesItsPowerUntil(effectText, source);
        if (result != null) return result;

        result = tryParseStandaloneDoublePowerMainPhaseNextTurn(effectText, source);
        if (result != null) return result;

        result = tryParseStandalonePowerReduceUntil(effectText, source);
        if (result != null) return result;

        result = tryParseFieldSelfPowerBoost(effectText, source);
        if (result != null) return result;

        result = tryParseDoubleOutgoingDamageThisTurn(effectText, source);
        if (result != null) return result;

        result = tryParseDoubleOutgoingDamageThisTurnAlt(effectText, source);
        if (result != null) return result;

        result = tryParseSelfOutgoingDmgBoostThisTurn(effectText, source);
        if (result != null) return result;

        result = tryParseGainOutgoingDmgBoostUntilEot(effectText, source);
        if (result != null) return result;

        result = tryParseGainsQuotedFieldAbilityUntilEot(effectText, source);
        if (result != null) return result;

        result = tryParseGainsQuotedAbilitiesPermanent(effectText, source);
        if (result != null) return result;

        result = tryParseSelfPowerBoostPermanent(effectText, source);
        if (result != null) return result;

        result = tryParseUntilEotGainsPowerTraitsAndQuoted(effectText, source);
        if (result != null) return result;

        result = tryParseDoubleOpponentIncomingDamageThisTurn(effectText);
        if (result != null) return result;

        result = tryParseAllForwardIncomingDmgIncreaseThisTurn(effectText);
        if (result != null) return result;

        result = tryParseChooseForwardDoubleIncomingThisTurn(effectText);
        if (result != null) return result;

        result = tryParseChooseForwardDoubleNextOutgoing(effectText);
        if (result != null) return result;

        result = tryParseDoublePlayerAbilityOutgoingThisTurn(effectText);
        if (result != null) return result;

        result = tryParseStandaloneSelfBoostForEachCrystal(effectText, source);
        if (result != null) return result;

        // Must precede tryParseSelfBoostEotPrefix and tryParseStandaloneSelfBoost below: both read
        // the same "<Name> gains +N power … until end of turn" frame and would hand out a flat
        // boost, dropping the "for each …" multiplier that is the whole effect.
        result = tryParseStandaloneSelfBoostForEachControlled(effectText, source);
        if (result != null) return result;

        // Same frame, distinct-Element multiplier. Mutually exclusive with the parser above, but
        // subject to the same "must precede the flat self-boost parsers" constraint.
        result = tryParseStandaloneSelfBoostForEachDistinctElement(effectText, source);
        if (result != null) return result;

        result = tryParseStandaloneItPowerBoostUntil(effectText, source);
        if (result != null) return result;

        result = tryParseSelfPowerBoostAndActivate(effectText, source);
        if (result != null) return result;

        result = tryParseIfHandSizeSelfBoost(effectText, source);
        if (result != null) return result;

        result = tryParseSelfBoostEotPrefix(effectText, source);
        if (result != null) return result;

        result = tryParseSelfAttacksPerOwnDamage(effectText, source);
        if (result != null) return result;

        result = tryParseStandaloneSelfBoost(effectText, source);
        if (result != null) return result;

        result = tryParseOppFieldEntryRfgInstead(effectText);
        if (result != null) return result;

        result = tryParseStandaloneSelfDullAndShield(effectText, source);
        if (result != null) return result;

        result = tryParseStandaloneSelfLosesAllAbilities(effectText, source);
        if (result != null) return result;

        result = tryParseOppLoseJobsUntilEot(effectText);
        if (result != null) return result;

        result = tryParseStandaloneSelfDull(effectText, source);
        if (result != null) return result;

        // The self-dull price in front of a "When you do so" payoff. Anchored over the whole
        // clause, so it claims nothing but that bare imperative; it exists so the sequence
        // parser above can resolve its primary half instead of dropping the cost.
        result = tryParseDullActiveYouControl(effectText, source, xValue);
        if (result != null) return result;

        result = tryParseStandaloneShieldCannotBeBroken(effectText, source);
        if (result != null) return result;

        result = tryParseAllOwnForwardsNullifyAbilityDamage(effectText);
        if (result != null) return result;

        result = tryParseOwnJobOrNameNullifyAbilityDamage(effectText);
        if (result != null) return result;

        result = tryParseDoublecastFreeSummons(effectText);
        if (result != null) return result;

        result = tryParseCastRfgCostCardThisTurn(effectText);
        if (result != null) return result;

        result = tryParseChooseCardRemovedBySourceToBz(effectText, source);
        if (result != null) return result;

        result = tryParseAllForwardsCannotBlock(effectText);
        if (result != null) return result;

        result = tryParseForwardsOfCostCannotBlock(effectText);
        if (result != null) return result;

        result = tryParseEndOfNextTurnIfCardOnFieldOppLoses(effectText);
        if (result != null) return result;

        result = tryParseOppFwdsCannotBlockInferiorPower(effectText);
        if (result != null) return result;

        result = tryParseAllFwdsBlockedOnlyByLowerCostThisTurn(effectText);
        if (result != null) return result;

        result = tryParseOppFwdsLoseAllAbilitiesEot(effectText);
        if (result != null) return result;

        result = tryParseOppFwdPowerBoostSuppressedThisTurn(effectText);
        if (result != null) return result;

        result = tryParseOppFwdsLosePowerPerPlayCost(effectText);
        if (result != null) return result;

        result = tryParseStandaloneCannotBeBlocked(effectText, source);
        if (result != null) return result;

        // Must precede tryParseRevealSelectHandRfp: shares its three-sentence prefix and adds the
        // delayed return that makes the removal temporary.
        result = tryParseRevealSelectHandRfpUntilEndOfOppTurn(effectText);
        if (result != null) return result;

        result = tryParseRevealSelectHandRfp(effectText);
        if (result != null) return result;

        result = tryParseRevealSelectHandDiscard(effectText);
        if (result != null) return result;

        result = tryParseOpponentRandomHandRfp(effectText);
        if (result != null) return result;

        result = tryParseOpponentRandomHandToBottomDeck(effectText);
        if (result != null) return result;

        result = tryParseOpponentHandRfp(effectText);
        if (result != null) return result;

        result = tryParseRevealTopNAddOnePerTypeRestBz(effectText);
        if (result != null) return result;

        result = tryParseRevealTopNAddUpToExcludingNameRestBz(effectText);
        if (result != null) return result;

        result = tryParseRevealTopNTypeToHand(effectText);
        if (result != null) return result;

        result = tryParseRevealTopNCategoryToHand(effectText);
        if (result != null) return result;

        result = tryParseRevealTopNJobOrNameToHand(effectText);
        if (result != null) return result;

        result = tryParseRevealTopNElementToHand(effectText);
        if (result != null) return result;

        result = tryParseRevealAddTypeToHandOrPlayJobTypeOntoFieldRestBottom(effectText);
        if (result != null) return result;

        // Must precede tryParseReturnNamedToHand. 26-053L Bartz ends "and add the other cards to
        // your hand", which ADD_NAMED_TO_YOUR_HAND reads as a card literally named "the other
        // cards" — the whole reveal-and-play was discarded and Bartz did nothing. Safe this early
        // because this parser is fully anchored: it matches complete texts of one exact shape and
        // cannot claim a prefix of anything else.
        result = tryParseRevealPlayElementTypeCostOntoFieldRestBottom(effectText, xValue);
        if (result != null) return result;

        // Sits beside the parser above and is anchored the same way, so neither can take the
        // other's text: that one ends at "among them", this one is still reading a second
        // alternative there.
        result = tryParseRevealPlayTypeCostOrNamedCostRestBottom(effectText);
        if (result != null) return result;

        result = tryParseReturnNamedToHand(effectText);
        if (result != null) return result;

        result = tryParseYouMayRemoveNamedFromGame(effectText, source);
        if (result != null) return result;

        result = tryParseEndOfOppTurnPlayNamedOntoField(effectText);
        if (result != null) return result;

        // Must precede tryParsePlaySourceOntoField: that parser matches with find() and its
        // expression ends at "onto the field", so it would claim this text as an immediate
        // Break-Zone play. Its pattern also carries a lookahead against the same wording.
        result = tryParseEndOfTurnPlayNamedOntoField(effectText);
        if (result != null) return result;

        result = tryParseRemoveAllOppBzFromGame(effectText);
        if (result != null) return result;

        // Must precede tryParseRemoveNamedFromGame: that parser find()s a lazy name group and
        // claims this text off its middle clause, leaving the reveal and the cast permission behind.
        result = tryParseRevealTopNRfgOneCastableRestBottom(effectText);
        if (result != null) return result;

        // Must precede tryParseRemoveNamedFromGame for the same reason as the parser above: on
        // "Remove 1 Warp Counter from Shadow for each …" its lazy name group reads the counter
        // clause as the thing being removed from the game.
        result = tryParseRemoveWarpCountersFromNamed(effectText, source);
        if (result != null) return result;

        // Must precede tryParseRemoveNamedFromGame: that parser's lazy name group reads the whole
        // filter phrase ("up to 3 Job Warring Triad with different names in your Break Zone") as a
        // card name and then searches the field for it, so the family removed nothing. Must follow
        // tryParseRemoveAllOppBzFromGame, whose whole-zone wipe this would route through a dialog.
        result = tryParseRemoveFromBreakZoneFromGame(effectText, source);
        if (result != null) return result;

        result = tryParseRemoveNamedFromGame(effectText, source);
        if (result != null) return result;

        // Must precede tryParseBreakSourceCard: "Break Ninja as well as ..." opens with exactly
        // the self-break that parser reads, so it would break Ninja and drop the partner.
        result = tryParseBreakSelfAndBattlePartner(effectText, source);
        if (result != null) return result;

        result = tryParseBreakSourceCard(effectText, source);
        if (result != null) return result;

        result = tryParsePutSourceIntoBreakZone(effectText, source);
        if (result != null) return result;

        result = tryParseBreaksAfterCombatNoDamage(effectText, source);
        if (result != null) return result;

        result = tryParseYouMayPutSelfToBZWhenDoSo(effectText, source);
        if (result != null) return result;

        result = tryParseIfOppNoForwardsPutToBreakZone(effectText, source);
        if (result != null) return result;

        result = tryParseIfEitherPlayerNoForwardsPutSourceToBz(effectText, source);
        if (result != null) return result;

        result = tryParseIfSelfDamagePointsPutToBreakZone(effectText, source);
        if (result != null) return result;

        result = tryParsePutSourceToBottomOfDeck(effectText, source);
        if (result != null) return result;

        result = tryParsePutSourceOnTopOfDeck(effectText, source);
        if (result != null) return result;

        result = tryParseBreakBlockingForward(effectText);
        if (result != null) return result;

        result = tryParseBreakForwardThatBlocksCard(effectText);
        if (result != null) return result;

        result = tryParseChooseExBurstFromDamageZone(effectText);
        if (result != null) return result;

        result = tryParseDamageZoneSwap(effectText);
        if (result != null) return result;

        result = tryParseOpponentDrawThenRandomDiscard(effectText);
        if (result != null) return result;

        result = tryParseOpponentDraw(effectText);
        if (result != null) return result;

        result = tryParseOpponentRandomDiscard(effectText);
        if (result != null) return result;

        result = tryParseEachPlayerSelectForwardDamage(effectText);
        if (result != null) return result;

        result = tryParseBothPlayersSelectForwardToBreakZone(effectText);
        if (result != null) return result;

        result = tryParseSelectCharCostLeExclToBz(effectText);
        if (result != null) return result;

        result = tryParseSelectControlledCharacterToBz(effectText);
        if (result != null) return result;

        result = tryParseEachPlayerSelectUpToNToBreakZone(effectText);
        if (result != null) return result;

        result = tryParseEachPlayerSelectUpToNActiveDullFreeze(effectText);
        if (result != null) return result;

        // Must precede tryParseIndependentSentences: its second sentence reads on its own as an
        // unbounded "put all the Forwards opponent controls into the Break Zone", so the splitter
        // resolved it with no selection in front of it and took the whole row.
        result = tryParseOppSelectsUpToNForwardsBreakRest(effectText);
        if (result != null) return result;

        // Its two-sided sibling, and here for the same reason — more so, because this one's sweep
        // names no side at all, so the splitter took every Forward in play and not just one row.
        result = tryParseEachPlayerSelectsForwardsBreakRest(effectText);
        if (result != null) return result;

        result = tryParseEachPlayerDiscard(effectText);
        if (result != null) return result;

        result = tryParseEachPlayerSalvageFromBreakZone(effectText);
        if (result != null) return result;

        result = tryParseSelectCharacterFromBzToHand(effectText);
        if (result != null) return result;

        result = tryParseChooseWarpCardFromBzToHand(effectText);
        if (result != null) return result;

        result = tryParseEachPlayerDraw(effectText);
        if (result != null) return result;

        result = tryParseNameCardTypeOpponentDiscardDrawIfMatch(effectText);
        if (result != null) return result;

        // Must precede tryParseOpponentDiscard and the other inner-effect parsers below: this
        // is a gate wrapping an arbitrary effect, and those match with find(), so one of them
        // would claim the gated tail (28-022L's "your opponent discards 2 cards") and run it
        // unconditionally. Disjoint from tryParseIfRfpCount, which needs a literal "there are"
        // and counts both players' RFP zones rather than only the ability user's.
        result = tryParseIfSelfRfgCount(effectText, source);
        if (result != null) return result;

        result = tryParseOpponentDiscard(effectText);
        if (result != null) return result;

        result = tryParseDiscardHandThenDraw(effectText);
        if (result != null) return result;

        result = tryParseDrawThenPlaceHandToBottom(effectText);
        if (result != null) return result;

        result = tryParsePlaceUpToHandToBottomThenRedraw(effectText);
        if (result != null) return result;

        result = tryParsePayCpWhenDoSo(effectText, source);
        if (result != null) return result;

        result = tryParseDrawDiscardRetriggerIfCardName(effectText, source);
        if (result != null) return result;

        result = tryParseDrawOnePerForwardCapped(effectText);
        if (result != null) return result;

        result = tryParseDrawCards(effectText);
        if (result != null) return result;

        result = tryParseYouMayDiscardType(effectText);
        if (result != null) return result;

        result = tryParseDiscardElementFromHand(effectText);
        if (result != null) return result;

        result = tryParseMayRevealElementFromHand(effectText);
        if (result != null) return result;

        result = tryParseDiscardHand(effectText);
        if (result != null) return result;

        result = tryParseDiscardNCards(effectText);
        if (result != null) return result;

        result = tryParseDiscardJobFromHand(effectText);
        if (result != null) return result;

        result = tryParseDiscardThenDraw(effectText);
        if (result != null) return result;

        result = tryParseDealPlayerDamageToOpponent(effectText);
        if (result != null) return result;

        result = tryParseDealPlayerDamageToSelf(effectText);
        if (result != null) return result;

        result = tryParseRandomRevealHandCastIfSummonFree(effectText);
        if (result != null) return result;

        result = tryParseCastSummonFromHandDiscounted(effectText);
        if (result != null) return result;

        result = tryParseCastSummonFromHandFree(effectText, xValue);
        if (result != null) return result;

        result = tryParseSearchAndCastSummonFree(effectText);
        if (result != null) return result;

        result = tryParsePlayAnyNumberFromHand(effectText, source);
        if (result != null) return result;

        // Must precede tryParsePlayFromHand: that one declines the "each player may" wording by
        // guard, so this is the only reading of it, and the pair is clearer kept adjacent.
        result = tryParseEachPlayerMayPlayFromHand(effectText, source, xValue);
        if (result != null) return result;

        result = tryParsePlayFromHand(effectText, source, xValue);
        if (result != null) return result;


        // Ardyn 28-002R's toll. Ahead of the opponent-selects family for the same reason the
        // line below it is: its own sentence names a seat those parsers would read as the
        // resolving player's opponent.
        result = tryParseTurnPlayerBreaksOrTakesDamage(effectText, source);
        if (result != null) return result;

        // Must precede OpponentSelects, which claims the same text and drops both the option and the
        // block restriction — see OPP_SELECTS_MAY_BREAK_ELSE_SELF_CANNOT_BLOCK.
        result = tryParseOppSelectsMayBreakElseSelfCannotBlock(effectText, source);
        if (result != null) return result;

        result = tryParseOpponentSelects(effectText);
        if (result != null) return result;

        result = tryParseBzFwdToHandOppFwdToBzByDamage(effectText);
        if (result != null) return result;

        result = tryParseIfRfpCount(effectText, source);
        if (result != null) return result;

        result = tryParseOpponentPutsForwardToBreakZone(effectText);
        if (result != null) return result;

        result = tryParseOpponentMillIfSameElementDraw(effectText);
        if (result != null) return result;

        result = tryParseOpponentMill(effectText);
        if (result != null) return result;

        result = tryParseSelfMill(effectText);
        if (result != null) return result;

        // Must precede tryParseOpponentRevealHand: both open with "Your opponent reveals ...",
        // and the whole-hand parser would claim this text's opening clause under find().
        result = tryParseOpponentRevealNSelectOneDiscard(effectText);
        if (result != null) return result;

        result = tryParseOpponentRevealHand(effectText);
        if (result != null) return result;

        result = tryParseEachPlayerRevealCharacterMayPlay(effectText);
        if (result != null) return result;

        result = tryParseEachPlayerMaySearchForwardMinPower(effectText);
        if (result != null) return result;

        result = tryParseRevealTopDeck(effectText, source);
        if (result != null) return result;

        result = tryParseStandaloneDamageShields(effectText, source);
        if (result != null) return result;

        result = tryParseDualSearchJobAndTypeDontShareElements(effectText);
        if (result != null) return result;

        result = tryParseSearchElementOrCategoryCharsDiffCost(effectText);
        if (result != null) return result;

        result = tryParseSearchNElementSummonsDiffCost(effectText);
        if (result != null) return result;

        // Must precede tryParseSearchDeck: that parser reads a single pool, so on a two-cost text
        // it claims the first half and the second search is lost.
        result = tryParseDualSearchPlayOntoField(effectText);
        if (result != null) return result;

        // Must precede tryParseSearchDeck: that parser resolves the search alone and leaves the
        // "If you do so, ..." payoff behind, which is the whole point of 1-093H Vanille.
        result = tryParseSearchNamedRfgThenIfDoSo(effectText, source);
        if (result != null) return result;

        result = tryParseSearchDeck(effectText, source, xValue);
        if (result != null) return result;

        result = tryParsePlayAllByNameFromBreakZone(effectText);
        if (result != null) return result;

        result = tryParsePlaySourceFromBreakZone(effectText, source);
        if (result != null) return result;

        // Must precede tryParsePlaySourceOntoField: that parser find()s a name group that
        // happily spans "the Forward placed in the Break Zone", and only its name-equals-source
        // check keeps it off this text — a check a future widening could relax.
        result = tryParsePlayBrokenCardOntoFieldDull(effectText);
        if (result != null) return result;

        result = tryParseAddBrokenCardToHand(effectText);
        if (result != null) return result;

        // Must precede tryParsePlaySourceOntoField, which matches with find(): it took the
        // "Play it onto the field" out of the middle of this sentence, resolved the "it" to the
        // ability's own source and tried to return that card from the Break Zone. 7-106L Agrias
        // did that instead of digging for a Character for as long as the parser has existed.
        result = tryParseFlipUntilCharactersPlayOntoFieldRestShuffleBottom(effectText);
        if (result != null) return result;

        result = tryParsePlaySourceOntoField(effectText, source);
        if (result != null) return result;

        result = tryParseActivateNamedCard(effectText);
        if (result != null) return result;

        result = tryParseAttackOnceMore(effectText);
        if (result != null) return result;

        result = tryParseOpponentAttackOnceThisTurn(effectText);
        if (result != null) return result;

        result = tryParseOpponentCannotSearchThisTurn(effectText);
        if (result != null) return result;

        result = tryParseOpponentCannotCastAnyCardsThisTurn(effectText);
        if (result != null) return result;

        result = tryParseRemoveFromBattle(effectText);
        if (result != null) return result;

        result = tryParseChooseSummonFromBzToHandWithCostReduction(effectText);
        if (result != null) return result;

        result = tryParseChooseNSummonsBzPickOneHandRestRfg(effectText);
        if (result != null) return result;

        result = tryParseSelectNamedFromRfgToHand(effectText);
        if (result != null) return result;

        result = tryParseChooseWarpCardRemoveCounter(effectText);
        if (result != null) return result;

        result = tryParseChooseWarpCardMayRemoveCounter(effectText);
        if (result != null) return result;

        result = tryParseChooseSummonInBzCastable(effectText);
        if (result != null) return result;

        result = tryParseCostReductionThisTurn(effectText);
        if (result != null) return result;

        result = tryParsePlayCostReductionThisTurn(effectText);
        if (result != null) return result;

        result = tryParseExtraTurnThenLose(effectText);
        if (result != null) return result;

        // Must precede tryParseGainCrystal: a trailing "Gain 《C》." sentence rides along behind a
        // complete effect, and the bare parser matches it with find() and claims the whole ability,
        // dropping everything in front of it (28-102R Princess Sarah gained the crystal and neither
        // looked nor drew). Deliberately here rather than at the top of the chain next to
        // tryParseTrailingDraw: most of this family — the Choose-then-effect printings — already
        // composes the gain inside its own parser, and hoisting this above them would reroute a
        // dozen working abilities through a different code path to reach the same result.
        result = tryParseTrailingGainCrystal(effectText, source, xValue);
        if (result != null) return result;

        result = tryParseGainCrystal(effectText);
        if (result != null) return result;

        result = tryParseGainCrystalIfOpponentHas(effectText);
        if (result != null) return result;

        // Must precede tryParsePlaceCounters: that parser is read with find() and reads
        // "each Job Apprentice Mage you control" as the card name being counted on, with only its
        // source-name check keeping it off this text.
        result = tryParsePlaceCountersOnEachJob(effectText);
        if (result != null) return result;

        result = tryParsePlaceCountersForEach(effectText, source);
        if (result != null) return result;

        result = tryParsePlaceCounters(effectText, source);
        if (result != null) return result;

        result = tryParseRemoveAllCounters(effectText, source);
        if (result != null) return result;

        // Last of the counter parsers on purpose. This is the generic reading of "if N or more X
        // Counters are placed on [Self], …", and every counter with a parser of its own has to get
        // there first: Warp Counters do not live in the counter map at all, and Aerith 16-067L's
        // Reraise gate carries a "Then, if there are none left" tail this one cannot see. What is
        // left for it is the counters nothing else claims — Number 24 20-036H's Barrier.
        result = tryParseCountersOnSelfGate(effectText, source, xValue);
        if (result != null) return result;

        result = tryParseLookTopDeckOptionallyBreak(effectText);
        if (result != null) return result;

        result = tryParseLookTopDeckBottomOrKeep(effectText);
        if (result != null) return result;

        result = tryParseCounterScaleLookAddToHand(effectText, xValue);
        if (result != null) return result;

        result = tryParseLookTopDeckAddToHandRestBottom(effectText);
        if (result != null) return result;

        result = tryParseLookTopDeckAddToHandOneToBreakRestBottom(effectText);
        if (result != null) return result;

        result = tryParseLookTopDeckAddToHandRestBreak(effectText);
        if (result != null) return result;

        result = tryParseLookTopDeckTopOrBottom(effectText, source);
        if (result != null) return result;

        result = tryParseLookTopDeckReturnTopOrdered(effectText);
        if (result != null) return result;

        result = tryParseLookTopDeckPickOneTopRestBottom(effectText);
        if (result != null) return result;

        result = tryParseLookTopDeckCastSummonFreeRestBottom(effectText, xValue);
        if (result != null) return result;

        result = tryParseLookTopDeckPeek(effectText);
        if (result != null) return result;

        result = tryParseRemoveTopOfDeckFromGame(effectText, source);
        if (result != null) return result;

        result = tryParseAddRemovedByPreviousEffectToHand(effectText, source);
        if (result != null) return result;

        result = tryParseRevealPlayNamedWithMaxCostRestBottom(effectText);
        if (result != null) return result;

        result = tryParseRevealPlayAsManyJobTypeTotalCostRestBottom(effectText);
        if (result != null) return result;

        result = tryParseRevealPlayNamedOrJobMaxCostRestBottom(effectText);
        if (result != null) return result;

        result = tryParseFlipUntilTypeToHandRestShuffleBottom(effectText);
        if (result != null) return result;

        result = tryParseFlipUntilElementToHandRestShuffleBottom(effectText);
        if (result != null) return result;

        result = tryParseShuffleThenRevealPlayNamedRestBottom(effectText, source);
        if (result != null) return result;

        result = tryParseRevealPlayTypeOntoFieldRestBottom(effectText);
        if (result != null) return result;

        result = tryParseRevealElementCardFromHandIfSoDraw(effectText);
        if (result != null) return result;

        result = tryParseShuffleDeck(effectText);
        if (result != null) return result;

        result = tryParseBackupCpDraw(effectText);
        if (result != null) return result;

        // Must follow tryParseBackupCpDraw: that one reads the unqualified Summon wording ("If the
        // CP paid to cast Shiva was only produced by Backups, also draw 1 card") with find(), and
        // this gate would claim the same sentence when the fallback split hands it over alone.
        // The general form is what 7-092C Thancred needs — a Category qualifier on the paying
        // Backups, and an arbitrary effect behind the gate rather than a fixed draw.
        result = tryParseCastCpProducedByBackupsGate(effectText, source, xValue);
        if (result != null) return result;

        result = tryParseNameElementOnlySelfBecomes(effectText, source);
        if (result != null) return result;

        result = tryParseNameElementAndJobSelfBecomes(effectText, source);
        if (result != null) return result;

        result = tryParseNameJobAndElementSelfGainsPermanent(effectText, source);
        if (result != null) return result;

        result = tryParseNameJobOrElementAllForwardsBoost(effectText);
        if (result != null) return result;

        result = tryParseNameJobOrCategoryRevealAddToHand(effectText);
        if (result != null) return result;

        // Beside its sibling below rather than ahead of it: the two are the halves of Jack
        // Garland 27-111L, one naming a Job and the other reading it back, and neither text can
        // match the other's pattern.
        result = tryParseNamedJobReference(effectText, source, xValue);
        if (result != null) return result;

        result = tryParseNameJob(effectText, source);
        if (result != null) return result;

        result = tryParseGrantPartyAnyElementThisTurn(effectText);
        if (result != null) return result;

        result = tryParseSourcePowerBecomesRemovedForwardPower(effectText, source);
        if (result != null) return result;

        result = tryParseSourcePowerBecomesOpponentWeakestForward(effectText, source);
        if (result != null) return result;

        result = tryParseOpponentGainsControlOfSource(effectText, source);
        if (result != null) return result;

        result = tryParseMayGiveSourceControlToOpponent(effectText, source);
        if (result != null) return result;

        // Compound-sentence fallback: split on ". " between sentences and compose effects.
        // Handles "Activate <cardName>. <cardName> gains +2000 power until the end of the turn." etc.
        // Sentences that don't parse are silently skipped so that implemented parts still fire.
        String[] sentences = effectText.split("(?<=\\.)\\s+(?=[A-Z])");
        if (sentences.length > 1) {
            List<Consumer<GameContext>> consumers = new ArrayList<>();
            for (String s : sentences) {
                String trimmed = s.trim().replaceAll("(?i)^Then\\s+", "");
                // A bare "Break it." reached here is the followup of a Choose in an earlier
                // sentence (7-057R Gnash, 2-099L Edea), not an action on a trigger's preloaded
                // target. Resolving it standalone would act on nothing while making the whole
                // ability report as handled — worse than leaving it unparsed.
                if (isTriggeredTargetAction(trimmed)) continue;
                // "Add it to your hand." is the same trap one card later: standing alone it is
                // Gogo 24-022H's whole effect and names the card the trigger watched break, but
                // reached *here* it is the followup of a Choose the chain above could not read
                // (14-073R Muraga Fennes), and salvaging the triggering card instead of the chosen
                // one is both wrong and invisible. Gogo's own text is a single sentence and never
                // arrives at this fallback.
                if (isTriggeringBrokenCardSalvage(trimmed)) continue;
                Consumer<GameContext> c = parse(trimmed, source, xValue);
                if (c != null) { consumers.add(c); continue; }
                // Dropping an unparsed sentence is safe while the sentences are independent, but
                // an unresolved "When you do so, …" gates everything after it. Composing past it
                // would grant that payoff for free — 20-078H Noctis would take +2000 power without
                // paying the cost in the sentence before. Stop here and keep only what came first.
                if (DO_SO_CONDITIONAL_SENTENCE.matcher(trimmed).find()) break;
            }
            if (!consumers.isEmpty()) return ctx -> consumers.forEach(c -> c.accept(ctx));
        }

        result = tryParseConditionalOpponentHand(effectText, source, xValue);
        if (result != null) return result;

        result = tryParseConditionalOpponentHandMin(effectText, source, xValue);
        if (result != null) return result;

        if (CardData.HAS_ALL_ELEMENTS_PATTERN.matcher(effectText.trim()).matches()) return ctx -> {};

        result = tryParseMultiPlayGrant(effectText);
        if (result != null) return result;

        result = tryParseLightDarkDiscardCpGrant(effectText);
        if (result != null) return result;

        return null;
    }

    /**
     * Resolves an ability as the sum of its sentences, when every sentence resolves on its own and
     * none of them refers back to another.
     *
     * <p>Fixes the general form of a bug that otherwise needs a bespoke pattern per shape: matchers
     * run with {@code find()}, so a pattern anchored on one sentence claims the entire ability and
     * {@code parse()} returns, discarding every other sentence. 16-036C Devout — "gain 《C》. Your
     * opponent discards 1 card." — gained no crystal, because the discard pattern matched the back
     * half and the front was thrown away. {@code parse()} already composes sentences this way, but
     * only as a last resort once nothing at all has matched, which is exactly the case that does
     * not arise here.
     *
     * <p>Two conditions keep this off text that is genuinely one effect:
     * <ul>
     *   <li><b>Every sentence must parse alone.</b> A pattern that legitimately spans sentences
     *       leaves at least one of them unresolvable in isolation — the second half of
     *       CastSummonFromHandDiscounted is a cost clause, not an effect — so the rule declines.</li>
     *   <li><b>No sentence may refer to another.</b> "Choose 1 Forward. Deal it 3000 damage." would
     *       otherwise split into two unlinked effects and the damage would miss its target. See
     *       {@link ActionResolverPatterns#DEPENDS_ON_PREVIOUS_SENTENCE}.</li>
     * </ul>
     *
     * <p>Recursion terminates: each sentence is passed back through {@code parse()}, where a
     * single-sentence text fails the length check immediately.
     */
    /**
     * The sentences of {@code text} when it qualifies for independent composition, else
     * {@code null}. Shared so {@code parse()} and both reporting chains split identically —
     * the conditions are documented on {@link #tryParseIndependentSentences}.
     *
     * <p>Only the structural conditions are checked here: two or more sentences, none after the
     * first referring backwards. Whether each sentence actually resolves is left to the caller,
     * since the reporting chains ask for a name or a description rather than a {@code Consumer}.
     */
    private static List<String> independentSentencesOf(String text) {
        String core = stripRestrictionSentences(text);
        if (core.isEmpty()) core = text;

        String[] sentences = SENTENCE_BREAK.split(core.trim());
        if (sentences.length < 2) return null;

        List<String> out = new ArrayList<>();
        for (int i = 0; i < sentences.length; i++) {
            String s = sentences[i].trim();
            if (s.isEmpty()) return null;
            // The first sentence has nothing to refer back to; a later one carrying a reference
            // means the ability is a linked whole and must stay with the normal chain.
            if (i > 0 && DEPENDS_ON_PREVIOUS_SENTENCE.matcher(s).find()) return null;
            out.add(s);
        }
        return out;
    }

    /**
     * Applies {@code describe} to each sentence of an independently-composed ability and joins the
     * results with {@code " + "}, matching the composite form the description layer already uses.
     * A sentence the caller cannot label becomes {@code "?"}, as elsewhere.
     */
    private static String composeOverSentences(String text, UnaryOperator<String> describe) {
        List<String> sentences = independentSentencesOf(text);
        if (sentences == null) return null;

        List<String> parts = new ArrayList<>();
        for (String s : sentences) {
            String part = describe.apply(s);
            parts.add(part != null && !part.isBlank() ? part : "?");
        }
        return String.join(" + ", parts);
    }

    private static Consumer<GameContext> tryParseIndependentSentences(
            String text, CardData source, int xValue) {
        List<String> sentences = independentSentencesOf(text);
        if (sentences == null) return null;

        List<Consumer<GameContext>> parts = new ArrayList<>();
        for (String s : sentences) {
            Consumer<GameContext> part = parse(s, source, xValue);
            if (part == null) return null;
            parts.add(part);
        }
        return ctx -> parts.forEach(p -> p.accept(ctx));
    }

    /** Returns the name of the first pattern that matches {@code effectText}, or {@code null}. */
    public static String matchedPatternName(String effectText, CardData source) {
        // Leading normalisations parse() applies before dispatching. This method had no preamble
        // at all, so it decided the name from different text than parse() matched against.
        // parse()'s EX BURST strip is deliberately not mirrored, and is now merely redundant rather
        // than load-bearing: it used to cost the ExBurstSuppression name on a sub-clause opening
        // "EX Bursts of cards …", which stripExBurstPrefix's word boundary now leaves alone.
        effectText = effectText.replaceFirst("(?i)^Then,?\\s+", "").trim();
        effectText = effectText.replaceFirst("(?i)^also\\s+", "").trim();

        String name = matchedPatternNameOn(effectText, source);
        if (name != null) return name;

        // Retry without trailing use-restriction sentences — "You can only use this ability during
        // your turn." and friends, which defeat the anchored parsers. Deliberately a fallback and
        // not a preamble: parse() does not strip them, so stripping up front lets an earlier
        // pattern claim text parse() resolves differently, and the reported name stops naming the
        // parser that actually ran. Restricting the retry to texts nothing matched mirrors how
        // parse() reaches these itself — through its own trailing sentence-split fallback.
        String noRestriction = stripRestrictionSentences(effectText);
        if (!noRestriction.isEmpty() && !noRestriction.equals(effectText))
            return matchedPatternNameOn(noRestriction, source);
        return null;
    }

    /** One ordered pass of the name chain over {@code effectText} exactly as given. */
    private static String matchedPatternNameOn(String effectText, CardData source) {
        // Mirrors parse(): ahead of the trailing-draw rule, which would otherwise split Leviathan
        // 16-125C's conditional half off the end of the sentence carrying the condition.
        if (tryParseCastPaymentElementsGate(effectText, source, 0) != null)
            return "CastPaymentElementsGate";
        if (tryParseCastCountGate(effectText, source, 0) != null)
            return "CastCountGate";
        if (tryParseCastPaymentElementsNotIncludedGate(effectText, source, 0) != null)
            return "CastPaymentElementsNotIncludedGate";
        if (tryParseCastPaymentElementCpGate(effectText, source, 0) != null)
            return "CastPaymentElementCpGate";
        if (tryParseCastPaymentOnlyElementCpGate(effectText, source, 0) != null)
            return "CastPaymentOnlyElementCpGate";
        if (tryParseCastPaymentExactElementsGate(effectText, source, 0) != null)
            return "CastPaymentExactElementsGate";
        // Mirrors parse(), where these are read beside the gate above.
        if (tryParseCrystalHeldGate(effectText, source, 0) != null)   return "CrystalHeldGate";
        if (tryParseOpponentMayDiscardElseEffect(effectText, source, 0) != null)
            return "OpponentMayDiscardElseEffect";
        // Mirrors parse()'s first dispatch. Reported as a composite so the leading effect still
        // names itself rather than being hidden behind a "TrailingDraw" label.
        if (tryParseTrailingDraw(effectText, source, 0) != null) {
            String tdHead = trailingDrawHead(effectText);
            if (tdHead != null) {
                String headName = matchedPatternName(tdHead, source);
                return (headName != null ? headName : "?") + " + DrawCards";
            }
        }
        // Mirrors parse(): claimed whole, ahead of the sentence composition that would split it.
        if (tryParseRemoveSelfThenPlaySelfOntoField(effectText, source) != null) return "RemoveSelfThenPlaySelfOntoField";
        // Mirrors parse()'s independent-sentence composition, so a composed ability is named for
        // every sentence that runs rather than for whichever single pattern this chain finds first.
        // Must precede IndependentSentences, mirroring parse(): the splitter reports the removal
        // alone and drops the return clause.
        if (tryParseRemoveSelfReturnNextMainPhase1(effectText, source) != null)
            return "RemoveSelfReturnNextMainPhase1";
        // Mirrors parse(): claimed whole, ahead of the splitter that would report it in halves.
        if (tryParseDivideOppForwardsIntoGroups(effectText) != null) return "DivideOppForwardsIntoGroups";
        // Mirrors parse(): claimed whole, ahead of the splitter that would report it in thirds.
        if (tryParseOppRfgWholeHandFaceDown(effectText) != null) return "OppRfgWholeHandFaceDown";
        // Mirrors parse(): claimed whole, ahead of the splitter that would report it in halves.
        if (cancelAnyNumberFilter(effectText) != null) return "CancelAnyNumberAbilitiesOnStack";
        if (tryParseIndependentSentences(effectText, source, 0) != null) {
            String composed = composeOverSentences(effectText, s -> matchedPatternName(s, source));
            if (composed != null) return composed;
        }
        // Mirrors parse(): the pay-or-else gate is reported ahead of its consequence's own pattern.
        if (tryParseIfNotPayOrElse(effectText, source, 0)               != null) return "IfNotPayOrElse";
        if (tryParseRemoveTopThenPileThreshold(effectText, source)          != null) return "RemoveTopThenPileThreshold";
        if (tryParseAddRemovedBySourceAbilityToHand(effectText, source)     != null) return "AddRemovedBySourceAbilityToHand";
        if (tryParseOppRfpTopDeckCastable(effectText)                   != null) return "OppRfpTopDeckCastable";
        if (tryParseChooseFromOppBzCastable(effectText)                 != null) return "ChooseFromOppBzCastable";
        if (tryParseChooseSummonsFromBzCastable(effectText)             != null) return "ChooseSummonsFromBzCastable";
        if (tryParseChooseSummonInBzMaxCostFreeCastRfg(effectText)      != null) return "ChooseSummonInBzMaxCostFreeCastRfg";
        // Mirrors parse(), where this is the 5th call site. It must precede the ChooseCharacter
        // family: a modal "select 1 of the 3 following actions" carries its options as quoted text,
        // and those match the general choose/search patterns, so left to the late
        // SELECT_FOLLOWING_ACTIONS_DETECT fallback the ability is reported as whichever option
        // happens to match first rather than as the choice it is.
        if (tryParseSelectFollowingActions(effectText, source)          != null) return "SelectFollowingActions";
        // Must precede tryParseWhenYouDoSoSequence: Zidane-style text contains "If you do so",
        // which that parser would otherwise claim. Mirrors parse().
        if (tryParseRevealHandOptPickDiscardOppDraw(effectText) != null) return "RevealHandOptPickDiscardOppDraw";
        if (tryParseRevealHandOptPickRfpOppDraw(effectText)    != null) return "RevealHandOptPickRfpOppDraw";
        // Must precede tryParseWhenYouDoSoSequence: that parser resolves both halves
        // independently, so it would claim the pay-then-effect shape first. Mirrors parse().
        if (tryParseMayPayCostThenEffect(effectText, source, 0) != null) return "MayPayCostThenEffect";
        // Must precede WhenYouDoSo, mirroring parse(): that parser claims the whole sentence and
        // names itself over a card the choose parser actually resolves.
        if (tryParseChooseMaySearchRfgThenElse(effectText, source, 0) != null)
            return "ChooseCharacter";
        // Must precede WhenYouDoSo, mirroring parse(): the payoff's count comes from the removal in
        // the same sentence and cannot survive that parser's split.
        if (tryParseRemoveAnyCountersThenChooseSameNumber(effectText, source) != null)
            return "RemoveAnyCountersThenChooseSameNumber";
        if (tryParseWhenYouDoSoSequence(effectText, source, 0) != null) return "WhenYouDoSo";
        if (tryParseSelectNumber(effectText, source)                    != null) return "SelectNumber";
        if (tryParseAllMonstersTemporaryForward(effectText) != null) return "AllMonstersTemporaryForward";
        if (tryParseBecomeForwardUntilEot(effectText, source) != null) return "BecomeForwardUntilEot";
        if (tryParseForEachJobAndNameDealDamageToForwards(effectText)   != null) return "ForEachJobAndNameDealDamageToForwards";
        if (tryParseDealNForEachJobOrNameToOppForwards(effectText)      != null) return "DealNForEachJobOrNameToOppForwards";
        if (tryParseSelfGainsWhenAttacksEOT(effectText, source)        != null) return "SelfGainsWhenAttacksEOT";
        if (tryParseDealDamageToForwardsForEach(effectText)             != null) return "DealDamageToForwardsForEach";
        if (tryParseDealDamageToForwardsExceptElement(effectText)       != null) return "DealDamageToForwardsExceptElement";
        // Mirrors parse(): read beside the fixed-amount family it cannot use.
        if (tryParseDealSameAmountToAllForwardsExcept(effectText, source, 0) != null)
            return "DealSameAmountToAllForwardsExcept";
        if (tryParseDealDamageToForwards(effectText)                    != null) return "DealDamageToForwards";
        if (tryParseDivideDamageEquallyAmongAll(effectText)             != null) return "DivideDamageEquallyAmongAll";
        if (tryParseNoForwardCostCannotAttack(effectText)               != null) return "NoForwardCostCannotAttack";
        if (tryParseOwnForwardsCannotBeChosenByExBurst(effectText)      != null) return "OwnForwardsCannotBeChosenByExBurst";
        if (tryParseExBurstSuppression(effectText)                      != null) return "ExBurstSuppression";
        if (tryParseDealHalfPowerDamageToForwards(effectText)           != null) return "DealHalfPowerDamageToForwards";
        if (tryParseDealPowerMinusNDamageToForwards(effectText)         != null) return "DealPowerMinusNDamageToForwards";
        if (tryParseDealHalfSourcePowerDamageToForwards(effectText)     != null) return "DealHalfSourcePowerDamageToForwards";
        if (tryParseDamageToCombatBlocker(effectText)                   != null) return "DamageToCombatBlocker";
        if (tryParseChooseOppFwdDynCostBreak(effectText)                   != null) return "ChooseOppFwdDynCostBreak";
        if (tryParseChooseFwdPowerInferiorToSource(effectText, source)     != null) return "ChooseFwdPowerInferiorToSource";
        if (tryParseChooseFwdBzCostInferiorToRemovedPlay(effectText)       != null) return "ChooseFwdBzCostInferiorToRemovedPlay";
        if (tryParseChooseOppFwdGainsSpecialAbilityFreeOnce(effectText, source) != null) return "ChooseOppFwdGainsSpecialAbilityFreeOnce";
        if (tryParseUseSpecialAbilityUsedThisTurn(effectText, source) != null) return "UseSpecialAbilityUsedThisTurn";
        if (tryParseChooseOppDamagedFwdIfHasAbilityBreak(effectText)     != null) return "ChooseOppDamagedFwdIfHasAbilityBreak";
        if (tryParseChooseAsManyAsFieldCount(effectText, source)         != null) return "ChooseAsManyAsFieldCount";
        if (tryParseChooseAsManyAsBzRfgJobCount(effectText)             != null) return "ChooseAsManyAsBzRfgJobCount";
        if (tryParseChooseCounterScaleCharsActivate(effectText, 1)    != null) return "ChooseCounterScaleCharsActivate";
        if (tryParseChooseAnyNumberReturnToHand(effectText)    != null) return "ChooseAnyNumberReturnToHand";
        if (tryParseCancelStackEntryUnlessPay(effectText)      != null) return "CancelStackEntryUnlessPay";
        if (tryParseChooseFwdRevealCostParity(effectText)             != null) return "ChooseFwdRevealCostParity";
        if (tryParseChooseForwardsGainAbilityEot(effectText)          != null) return "ChooseForwardsGainAbilityEot";
        if (tryParseChooseForwardPlacePetrification(effectText)       != null) return "ChooseForwardPlacePetrification";
        if (tryParseRemoveAllCountersFromSelf(effectText, source)     != null) return "RemoveAllCountersFromSelf";
        // Must precede ChooseCharacter, mirroring parse(): it claims the choose half and leaves
        // the control-gated play and sacrifice undescribed.
        if (tryParseChooseTwoBzFwdPlayIfControl(effectText, source) != null)
            return "ChooseTwoBzFwdPlayIfControl";
        // Must precede ChooseCharacter: it matches the choose half alone and returns, dropping
        // the delayed action that the rest of the ability consists of.
        if (tryParseChooseThenEndOfOppTurnAction(effectText, source, 0) != null)
            return "ChooseThenEndOfOppTurnAction";
        // Must precede ChooseCharacter, mirroring parse(): it claims the first choose clause and
        // leaves the second one out of both the name and the effect.
        //
        // The mixed-types guards are not here to be named — they are checked so this one does not
        // answer for a text they win in parse(), where they are called ~80 call sites earlier.
        // Their own naming gap ("Choose 1 Forward and 1 Backup. Break them." still reports
        // ChooseCharacter) is separate, outstanding Phase 2 work.
        if (tryParseChooseThreeMixedTypes(effectText, source) == null
                && tryParseChooseTwoMixedTypes(effectText, source) == null
                && tryParseChooseTwoJointAction(effectText, source) != null)
            return "ChooseTwoJointAction";
        // Mirrors parse(): ahead of ChooseCharacter, which reads the budget clause as an
        // unbounded choose.
        if (tryParseChooseForwardsTotalCostBreak(effectText) != null) return "ChooseForwardsTotalCostBreak";
        // Mirrors parse(): ahead of ChooseCharacter, which claims the text and then has no
        // followup branch for the per-pick amounts.
        if (tryParseChooseTieredDamage(effectText) != null) return "ChooseTieredDamage";
        // Mirrors parse(): ahead of ChooseCharacter, which claims the first of the two picks.
        if (tryParseChooseTwoCostsFromBzPlayBoth(effectText) != null) return "ChooseTwoCostsFromBzPlayBoth";
        if (tryParseChooseCharacter(effectText, source, 0)              != null) return "ChooseCharacter";
        if (tryParseIfSelfFwdReceivedDamageDraw(effectText, source)          != null) return "IfSelfFwdReceivedDamageDraw";
        if (tryParseIfRfpCount(effectText, source)               != null) return "IfRfpCount";
        if (tryParseIfSelfRfgCount(effectText, source)           != null) return "IfSelfRfgCount";
        if (tryParseElementChange(effectText, source) != null) return "ElementChange";
        if (tryParseDelayedEffect(effectText)                 != null) return "DelayedEffect";
        if (tryParsePlayerCannotCastSummons(effectText)                != null) return "PlayerCannotCastSummons";
        // Mirrors parse(): ahead of CannotBeChosen, which would claim it off the quoted clause.
        if (tryParseSelfGainsAndBasePowerBecomesPermanent(effectText, source) != null) return "SelfGainsAndBasePowerBecomesPermanent";
        if (tryParseSelfGainsTraitsAndQuotedPermanent(effectText, source) != null) return "SelfGainsTraitsAndQuotedPermanent";
        // Mirrors parse(): ahead of CannotBeChosen, which claims this off its opening clause.
        if (tryParseSelfCannotBeChosenByAnyAndGainsTraits(effectText, source) != null)
            return "SelfCannotBeChosenByAnyAndGainsTraits";
        if (tryParseCannotBeChosenStandalone(effectText, source) != null) return "CannotBeChosen";
        if (tryParseCannotBecomeDullOpp(effectText, source) != null)     return "CannotBecomeDullOpp";
        if (tryParseCannotBeReturnedToHandOpp(effectText, source) != null) return "CannotBeReturnedToHandOpp";
        if (tryParseCharactersCannotBeReturnedToHandOpp(effectText) != null) return "CharactersCannotBeReturnedToHandOpp";
        if (tryParseCannotBePutIntoBzOpp(effectText, source) != null)    return "CannotBePutIntoBzOpp";
        if (tryParseChooseOwnFwdBoostProtectionsOrAllIfDmg(effectText) != null) return "ChooseOwnFwdBoostProtectionsOrAllIfDmg";
        if (tryParseActivateAllOwnFwdsGainProtections(effectText) != null) return "ActivateAllOwnFwdsGainProtections";
        if (tryParseStandaloneCannotAttackOrBlock(effectText, source) != null) return "CannotAttackOrBlock";
        if (tryParseNegateAllDamage(effectText)                != null) return "NegateDamage";
        if (tryParsePlayerNextDamageZeroRedirect(effectText)   != null) return "PlayerNextDamageZeroRedirect";
        if (tryParsePlayerNextDamageZero(effectText)           != null) return "PlayerNextDamageZero";
        if (tryParseCancelAutoAbilityAndDamageIfForward(effectText) != null) return "CancelAutoAbilityAndDamageIfForward";
        // Must precede CancelSummonOrAutoAbility, mirroring parse(): they share a first sentence.
        if (tryParseChooseStackEntryZeroItsDamage(effectText) != null) return "ChooseStackEntryZeroItsDamage";
        if (tryParseCancelStackEntry(effectText)               != null) return "CancelSummonOrAutoAbility";
        // Mirrors parse(): ahead of the general redirect, which would otherwise claim the name.
        if (tryParseRedirectChosenTarget(effectText, source)   != null) return "RedirectChosenTarget";
        if (tryParseCopyChosenAutoAbilityOnStack(effectText, source) != null) return "CopyChosenAutoAbilityOnStack";
        if (tryParseCancelAbilityOnStack(effectText)           != null) return "CancelAbilityOnStack";
        if (tryParseCancelChosenTargetUnlessPay(effectText)    != null) return "CancelChosenTargetUnlessPay";
        if (tryParseCancelChosenTargetUnlessDiscard(effectText) != null) return "CancelChosenTargetUnlessDiscard";
        if (tryParseTriggeredTargetAction(effectText, 0)      != null) return "TriggeredTargetAction";
        if (tryParseCancelChosenTargetBare(effectText)         != null) return "CancelChosenTargetBare";
        if (tryParseCancelTriggeringSummon(effectText)         != null) return "CancelTriggeringSummon";
        if (tryParseIfOppNotPayAction(effectText)             != null) return "IfOppNotPayAction";
        // Mirrors parse(): checked alongside its sentence-sharing sibling below.
        if (tryParseRevealTopToHandIfTypeElseTopOrBottom(effectText) != null) return "RevealTopToHandIfTypeElseTopOrBottom";
        if (tryParseCancelChosenRevealTopIfType(effectText)    != null) return "CancelChosenRevealTopIfType";
        if (tryParseCancelChosenMillTopIfNotType(effectText)   != null) return "CancelChosenMillTopIfNotType";
        if (tryParseCancelChosenMillBothIfSameType(effectText) != null) return "CancelChosenMillBothIfSameType";
        if (tryParseCancelSummonTargetingMyCharacter(effectText) != null) return "CancelSummonTargetingMyCharacter";
        if (tryParseSelectNumber(effectText, source)          != null) return "SelectNumber";
        if (tryParseDullAllOppFwdsPowerLeSource(effectText, source)        != null) return "DullAllOppFwdsPowerLeSource";
        if (tryParseRevealTopBreakSameCostAddToHand(effectText)           != null) return "RevealTopBreakSameCostAddToHand";
        // Must precede AllFieldEffect — see the ordering note in parse().
        if (tryParseEndOfOppTurnDelayedEffect(effectText, source) != null) return "EndOfOppTurnDelayed";
        if (tryParsePlaceCounterOnAllForwards(effectText)     != null) return "PlaceCounterOnAllForwards";
        // Must precede AllFieldEffect — see the ordering note in parse().
        if (tryParseAllFieldActivateThenDraw(effectText)      != null) return "AllFieldActivateThenDraw";
        // Mirrors parse(): read ahead of the general sweep, which declines this text.
        if (tryParseBreakForwardsBelowSelfPower(effectText, source) != null)
            return "BreakForwardsBelowSelfPower";
        if (tryParseAllFieldEffect(effectText)                != null) return "AllFieldEffect";
        if (tryParseFieldPowerGrantPassive(effectText)        != null) {
            String trimmed = effectText.trim();
            return FIELD_OPPONENT_DEBUFF_PASSIVE.matcher(trimmed).matches()
                    ? "FieldOpponentPowerDebuff" : "FieldPowerGrant";
        }
        if (tryParseAllForwardsSameElementAsNamedPowerBoost(effectText) != null) return "AllForwardsSameElementAsNamedPowerBoost";
        if (tryParsePartyForwardsPowerBoost(effectText) != null) return "PartyForwardsPowerBoost";
        if (tryParseAllOppForwardsLoseTraitsEot(effectText) != null) return "AllOppForwardsLoseTraitsEot";
        if (tryParseAllFieldPowerBoost(effectText) != null) return "AllFieldPowerBoost";
        if (tryParseAllFieldJobCardNamePowerBoost(effectText) != null) return "AllFieldJobCardNamePowerBoost";
        if (tryParseTwoCardNamesPowerBoost(effectText) != null) return "TwoCardNamesPowerBoost";
        if (tryParseAllFieldJobPowerBoost(effectText) != null) return "AllFieldJobPowerBoost";
        if (tryParseAllFieldJobKeywordGrant(effectText) != null) return "AllFieldJobKeywordGrant";
        if (tryParseAllFieldKeywordGrant(effectText) != null) return "AllFieldKeywordGrant";
        if (tryParseAllFieldQuotedProtectionGrant(effectText) != null) return "AllFieldQuotedProtectionGrant";
        if (tryParseUntilEotDualPowerShift(effectText) != null) return "UntilEotDualPowerShift";
        if (tryParseUntilEotAllFieldPowerBoost(effectText) != null) return "UntilEotAllFieldPowerBoost";
        if (tryParseStandalonePowerBoostAndAttackTrigger(effectText, source) != null) return "StandalonePowerBoostAndAttackTrigger";
        if (tryParseStandalonePowerBoostAndCannotBeChosen(effectText, source) != null) return "StandalonePowerBoostAndCannotBeChosen";
        if (tryParseStandaloneGainsTraitsAndCannotBeBlocked(effectText, source) != null) return "StandaloneGainsTraitsAndCannotBeBlocked";
        if (tryParseStandaloneGainsTraitsAndCannotBeBlockedTrailing(effectText, source) != null) return "StandaloneGainsTraitsAndCannotBeBlockedTrailing";
        if (tryParseStandaloneGainsCannotBeBlocked(effectText, source) != null) return "StandaloneGainsCannotBeBlocked";
        if (tryParseSelfBasePowerBecomesUntil(effectText, source) != null) return "SelfBasePowerBecomesUntil";
        if (tryParseStandalonePowerBoostUntil(effectText, source) != null) return "StandalonePowerBoostUntil";
        if (tryParseStandaloneDoublePowerUntil(effectText, source) != null) return "StandaloneDoublePowerUntil";
        if (tryParseStandaloneDoublesItsPowerUntil(effectText, source) != null) return "StandaloneDoublesItsPowerUntil";
        if (tryParseStandaloneDoublePowerMainPhaseNextTurn(effectText, source) != null) return "StandaloneDoublePowerMainPhaseNextTurn";
        if (tryParseStandalonePowerReduceUntil(effectText, source) != null) return "StandalonePowerReduceUntil";
        if (tryParseFieldSelfPowerBoost(effectText, source)    != null) return "FieldSelfPowerBoost";
        if (tryParseDoubleOutgoingDamageThisTurn(effectText, source) != null)    return "DoubleOutgoingDamageThisTurn";
        if (tryParseDoubleOutgoingDamageThisTurnAlt(effectText, source) != null) return "DoubleOutgoingDamageThisTurnAlt";
        if (tryParseSelfOutgoingDmgBoostThisTurn(effectText, source) != null)   return "SelfOutgoingDmgBoostThisTurn";
        if (tryParseGainOutgoingDmgBoostUntilEot(effectText, source) != null)   return "GainOutgoingDmgBoostUntilEot";
        if (tryParseGainsQuotedFieldAbilityUntilEot(effectText, source) != null) return "GainsQuotedFieldAbilityUntilEot";
        if (tryParseGainsQuotedAbilitiesPermanent(effectText, source) != null)  return "GainsQuotedAbilitiesPermanent";
        if (tryParseSelfPowerBoostPermanent(effectText, source) != null)        return "SelfPowerBoostPermanent";
        if (tryParseUntilEotGainsPowerTraitsAndQuoted(effectText, source) != null) return "UntilEotGainsPowerTraitsAndQuoted";
        if (tryParseDoubleOpponentIncomingDamageThisTurn(effectText) != null)   return "DoubleOpponentIncomingDamageThisTurn";
        if (tryParseAllForwardIncomingDmgIncreaseThisTurn(effectText) != null)  return "AllForwardIncomingDmgIncreaseThisTurn";
        if (tryParseChooseForwardDoubleIncomingThisTurn(effectText) != null)    return "ChooseForwardDoubleIncomingThisTurn";
        if (tryParseChooseForwardDoubleNextOutgoing(effectText) != null)        return "ChooseForwardDoubleNextOutgoing";
        if (tryParseDoublePlayerAbilityOutgoingThisTurn(effectText) != null)   return "DoublePlayerAbilityOutgoingThisTurn";
        if (tryParseStandaloneSelfBoostForEachCrystal(effectText, source) != null) return "StandaloneSelfBoostForEachCrystal";
        // Mirrors parse(): ahead of the two flat self-boost parsers, which share its frame.
        if (tryParseStandaloneSelfBoostForEachControlled(effectText, source) != null) return "StandaloneSelfBoostForEachControlled";
        if (tryParseStandaloneSelfBoostForEachDistinctElement(effectText, source) != null) return "StandaloneSelfBoostForEachDistinctElement";
        if (tryParseIfHandSizeSelfBoost(effectText, source)               != null) return "IfHandSizeSelfBoost";
        if (tryParseSelfBoostEotPrefix(effectText, source)    != null) return "SelfBoostUntilEot";
        if (tryParseSelfAttacksPerOwnDamage(effectText, source) != null) return "SelfAttacksPerOwnDamage";
        if (tryParseStandaloneSelfBoost(effectText, source)   != null) return "StandaloneSelfBoost";
        if (tryParseOppFieldEntryRfgInstead(effectText)                   != null) return "OppFieldEntryRfgInstead";
        if (tryParseStandaloneSelfLosesAllAbilities(effectText, source) != null) return "StandaloneSelfLosesAllAbilities";
        if (tryParseOppLoseJobsUntilEot(effectText) != null) return "OppLoseJobsUntilEot";
        if (tryParseStandaloneSelfDullAndShield(effectText, source) != null) return "StandaloneSelfDullAndShield";
        if (tryParseStandaloneSelfDull(effectText, source) != null)          return "StandaloneSelfDull";
        if (tryParseDullActiveYouControl(effectText, source, 0) != null)     return "DullActiveYouControl";
        if (tryParseStandaloneShieldCannotBeBroken(effectText, source) != null) return "StandaloneShieldCannotBeBroken";
        if (tryParseAllOwnForwardsNullifyAbilityDamage(effectText)        != null) return "AllOwnForwardsNullifyAbilityDamage";
        if (tryParseOwnJobOrNameNullifyAbilityDamage(effectText)          != null) return "OwnJobOrNameNullifyAbilityDamage";
        if (tryParseDoublecastFreeSummons(effectText)                     != null) return "DoublecastFreeSummons";
        if (tryParseCastRfgCostCardThisTurn(effectText)                   != null) return "CastRfgCostCardThisTurn";
        if (tryParseChooseCardRemovedBySourceToBz(effectText, source)     != null) return "ChooseCardRemovedBySourceToBz";
        if (tryParseAllForwardsCannotBlock(effectText)                    != null) return "AllForwardsCannotBlock";
        if (tryParseForwardsOfCostCannotBlock(effectText)                 != null) return "ForwardsOfCostCannotBlock";
        if (tryParseEndOfNextTurnIfCardOnFieldOppLoses(effectText)        != null) return "EndOfNextTurnIfCardOnFieldOppLoses";
        if (tryParseOppFwdsCannotBlockInferiorPower(effectText)           != null) return "OppFwdsCannotBlockInferiorPower";
        if (tryParseAllFwdsBlockedOnlyByLowerCostThisTurn(effectText)    != null) return "AllFwdsBlockedOnlyByLowerCost";
        if (tryParseOppFwdsLoseAllAbilitiesEot(effectText)         != null) return "OppFwdsLoseAllAbilitiesEot";
        if (tryParseOppFwdPowerBoostSuppressedThisTurn(effectText) != null) return "OppFwdPowerBoostSuppressedThisTurn";
        if (tryParseOppFwdsLosePowerPerPlayCost(effectText)        != null) return "OppFwdsLosePowerPerPlayCost";
        if (tryParseStandaloneGainsCannotBeBlocked(effectText, source) != null) return "StandaloneGainsCannotBeBlocked";
        if (tryParseStandaloneCannotBeBlocked(effectText, source) != null) return "StandaloneCannotBeBlocked";
        // Must precede RevealSelectHandRfp — see the same guard in parse().
        if (tryParseRevealSelectHandRfpUntilEndOfOppTurn(effectText) != null) return "RevealSelectHandRfpUntilEndOfOppTurn";
        if (tryParseRevealSelectHandRfp(effectText)            != null) return "RevealSelectHandRfp";
        if (tryParseRevealSelectHandDiscard(effectText)        != null) return "RevealSelectHandDiscard";
        if (tryParseOpponentRandomHandRfp(effectText)            != null) return "OpponentRandomHandRfp";
        if (tryParseOpponentRandomHandToBottomDeck(effectText)   != null) return "OpponentRandomHandToBottomDeck";
        if (tryParseOpponentHandRfp(effectText)               != null) return "OpponentHandRfp";
        if (tryParseRevealTopNAddOnePerTypeRestBz(effectText) != null) return "RevealTopNAddOnePerTypeRestBz";
        if (tryParseRevealTopNAddUpToExcludingNameRestBz(effectText) != null) return "RevealTopNAddUpToExcludingNameRestBz";
        if (tryParseRevealTopNTypeToHand(effectText) != null) return "RevealTopNTypeToHand";
        if (tryParseRevealTopNCategoryToHand(effectText) != null) return "RevealTopNCategoryToHand";
        if (tryParseRevealTopNJobOrNameToHand(effectText) != null) return "RevealTopNJobOrNameToHand";
        if (tryParseRevealTopNElementToHand(effectText) != null) return "RevealTopNElementToHand";
        if (tryParseRevealAddTypeToHandOrPlayJobTypeOntoFieldRestBottom(effectText) != null) return "RevealAddTypeToHandOrPlayJobTypeOntoFieldRestBottom";
        // Must precede ReturnNamedToHand — see the ordering note in parse().
        if (tryParseRevealPlayElementTypeCostOntoFieldRestBottom(effectText, 0) != null) return "RevealPlayElementTypeCostOntoFieldRestBottom";
        if (tryParseRevealPlayTypeCostOrNamedCostRestBottom(effectText) != null) return "RevealPlayTypeCostOrNamedCostRestBottom";
        if (tryParseReturnNamedToHand(effectText) != null) return "ReturnNamedToHand";
        if (tryParseYouMayRemoveNamedFromGame(effectText, source) != null) return "YouMayRemoveNamedFromGame";
        if (tryParseEndOfOppTurnPlayNamedOntoField(effectText) != null) return "EndOfOppTurnPlayNamedOntoField";
        if (tryParseEndOfTurnPlayNamedOntoField(effectText)    != null) return "EndOfTurnPlayNamedOntoField";
        if (tryParseRemoveAllOppBzFromGame(effectText)         != null) return "RemoveAllOppBzFromGame";
        if (tryParseRevealTopNRfgOneCastableRestBottom(effectText) != null) return "RevealTopNRfgOneCastableRestBottom";
        // Must precede RemoveNamedFromGame, mirroring parse(): it reads the counter clause as the
        // thing being removed from the game and would answer in this parser's place.
        if (tryParseRemoveWarpCountersFromNamed(effectText, source) != null) return "RemoveWarpCountersFromNamed";
        // Must precede RemoveNamedFromGame, mirroring parse(): that parser reads this family's whole
        // filter phrase as a card name.
        if (tryParseRemoveFromBreakZoneFromGame(effectText, source) != null)
            return removeFromBreakZonePatternName(effectText);
        if (tryParseRemoveNamedFromGame(effectText, source)   != null) return "RemoveNamedFromGame";
        // Must precede BreakSourceCard, mirroring parse(): the sentence opens with the plain
        // self-break that parser reads.
        if (tryParseBreakSelfAndBattlePartner(effectText, source) != null)
            return "BreakSelfAndBattlePartner";
        if (tryParseBreakSourceCard(effectText, source)        != null) return "BreakSourceCard";
        if (tryParsePutSourceIntoBreakZone(effectText, source) != null) return "PutSourceIntoBreakZone";
        if (tryParseBreaksAfterCombatNoDamage(effectText, source) != null) return "BreaksAfterCombatNoDamage";
        if (tryParseYouMayPutSelfToBZWhenDoSo(effectText, source)    != null) return "YouMayPutSelfToBZWhenDoSo";
        if (tryParseIfOppNoForwardsPutToBreakZone(effectText, source)          != null) return "IfOppNoForwardsPutToBreakZone";
        if (tryParseIfEitherPlayerNoForwardsPutSourceToBz(effectText, source)  != null) return "IfEitherPlayerNoForwardsPutSourceToBz";
        if (tryParseIfSelfDamagePointsPutToBreakZone(effectText, source)      != null) return "IfSelfDamagePointsPutToBreakZone";
        if (tryParsePutSourceToBottomOfDeck(effectText, source) != null) return "PutSourceToBottomOfDeck";
        if (tryParsePutSourceOnTopOfDeck(effectText, source)   != null) return "PutSourceOnTopOfDeck";
        if (tryParseBreakBlockingForward(effectText)           != null) return "BreakBlockingForward";
        if (tryParseBreakForwardThatBlocksCard(effectText)     != null) return "BreakForwardThatBlocksCard";
        if (tryParseChooseExBurstFromDamageZone(effectText)    != null) return "ChooseExBurstFromDamageZone";
        if (tryParseExBurstSuppression(effectText)             != null) return "ExBurstSuppression";
        if (tryParseDamageZoneSwap(effectText)                 != null) {
            Matcher m = DAMAGE_ZONE_SWAP_PATTERN.matcher(effectText.trim());
            return m.matches() && m.group("draw") != null ? "DamageZoneSwap + DrawCards" : "DamageZoneSwap";
        }
        if (tryParseOpponentDrawThenRandomDiscard(effectText)  != null) return "OpponentDrawThenRandomDiscard";
        if (tryParseOpponentDraw(effectText)                   != null) return "OpponentDraw";
        if (tryParseOpponentRandomDiscard(effectText)         != null) return "OpponentRandomDiscard";
        if (tryParseEachPlayerSelectForwardDamage(effectText)  != null) return "EachPlayerSelectForwardDamage";
        if (tryParseBothPlayersSelectForwardToBreakZone(effectText) != null) return "BothPlayersSelectForwardToBreakZone";
        if (tryParseSelectCharCostLeExclToBz(effectText)             != null) return "SelectCharCostLeExclToBz";
        if (tryParseSelectControlledCharacterToBz(effectText)        != null) return "SelectControlledCharacterToBz";
        if (tryParseEachPlayerSelectUpToNToBreakZone(effectText)   != null) return "EachPlayerSelectUpToNToBreakZone";
        if (tryParseEachPlayerSelectUpToNActiveDullFreeze(effectText) != null)
            return "EachPlayerSelectUpToNActiveDullFreeze";
        if (tryParseOppSelectsUpToNForwardsBreakRest(effectText) != null)
            return "OppSelectsUpToNForwardsBreakRest";
        if (tryParseEachPlayerSelectsForwardsBreakRest(effectText) != null)
            return "EachPlayerSelectsForwardsBreakRest";
        if (tryParseEachPlayerDiscard(effectText)              != null) return "EachPlayerDiscard";
        if (tryParseEachPlayerSalvageFromBreakZone(effectText) != null) return "EachPlayerSalvageFromBreakZone";
        if (tryParseSelectCharacterFromBzToHand(effectText)    != null) return "SelectCharacterFromBzToHand";
        if (tryParseChooseWarpCardFromBzToHand(effectText)     != null) return "ChooseWarpCardFromBzToHand";
        if (tryParseEachPlayerDraw(effectText)                 != null) return "EachPlayerDraw";
        if (tryParseNameCardTypeOpponentDiscardDrawIfMatch(effectText) != null) return "NameCardTypeOpponentDiscardDrawIfMatch";
        if (tryParseOpponentDiscard(effectText)               != null) return "OpponentDiscard";
        if (tryParseDiscardHandThenDraw(effectText)           != null) return "DiscardHandThenDraw";
        if (tryParseDrawThenPlaceHandToBottom(effectText)     != null) return "DrawThenPlaceHandToBottom";
        if (tryParsePlaceUpToHandToBottomThenRedraw(effectText) != null) return "PlaceUpToHandToBottomThenRedraw";
        if (tryParsePayCpWhenDoSo(effectText, source)         != null) return "PayCpWhenDoSo";
        if (tryParseDrawDiscardRetriggerIfCardName(effectText, source) != null) return "DrawDiscardRetriggerIfCardName";
        if (tryParseDrawCards(effectText)                     != null) return "DrawCards";
        if (tryParseYouMayDiscardType(effectText)             != null) return "YouMayDiscardType";
        if (tryParseMayRevealElementFromHand(effectText)      != null) return "MayRevealElementFromHand";
        if (tryParseDiscardHand(effectText)                   != null) return "DiscardHand";
        if (tryParseDiscardNCards(effectText)                 != null) return "DiscardNCards";
        if (tryParseDiscardJobFromHand(effectText)            != null) return "DiscardJobFromHand";
        if (tryParseDiscardThenDraw(effectText)               != null) return "DiscardThenDraw";
        // Mirrors parse(), where this gate sits immediately ahead of IfEachPlayerEmptyHand.
        if (tryParseIfAllHaveElement(effectText, source, 0)   != null) return "IfAllHaveElement";
        if (tryParseIfEachPlayerEmptyHand(effectText, source, 0) != null) return "IfEachPlayerEmptyHand";
        if (tryParseDealPlayerDamageToOpponent(effectText)    != null) return "DealPlayerDamageToOpponent";
        if (tryParseDealPlayerDamageToSelf(effectText)        != null) return "DealPlayerDamageToSelf";
        if (tryParseRandomRevealHandCastIfSummonFree(effectText) != null) return "RandomRevealHandCastIfSummonFree";
        if (tryParseCastSummonFromHandDiscounted(effectText)     != null) return "CastSummonFromHandDiscounted";
        if (tryParseCastSummonFromHandFree(effectText, 0)     != null) return "CastSummonFromHandFree";
        if (tryParseSearchAndCastSummonFree(effectText)       != null) return "SearchAndCastSummonFree";
        if (tryParsePlayAnyNumberFromHand(effectText, source) != null) return "PlayAnyNumberFromHand";
        if (tryParseEachPlayerMayPlayFromHand(effectText, source, 0) != null) return "EachPlayerMayPlayFromHand";
        if (tryParsePlayFromHand(effectText, source, 0)       != null) return "PlayFromHand";
        // Checked ahead of OpponentSelects: an "…, X instead." upgrade wraps a base clause the
        // OpponentSelects matcher would otherwise claim on its own, dropping the replacement.
        // Must precede ControlGatedInsteadUpgrade, mirroring parse().
        if (tryParseChooseGatedBoostInstead(effectText, source, 0) != null) return "ChooseCharacter";
        if (tryParseControlGatedInsteadUpgrade(effectText, source, 0) != null) return "ControlGatedInsteadUpgrade";
        // Mirrors parse(): ahead of OpponentSelects, which would otherwise claim it.
        if (tryParseTurnPlayerBreaksOrTakesDamage(effectText, source) != null) return "TurnPlayerBreaksOrTakesDamage";
        if (tryParseOppSelectsMayBreakElseSelfCannotBlock(effectText, source) != null)
            return "OppSelectsMayBreakElseSelfCannotBlock";
        if (tryParseOpponentSelects(effectText)               != null) return "OpponentSelects";
        if (tryParseBzFwdToHandOppFwdToBzByDamage(effectText)  != null) return "BzFwdToHandOppFwdToBzByDamage";
        if (tryParseOpponentPutsForwardToBreakZone(effectText) != null) return "OpponentPutsForwardToBreakZone";
        if (tryParseOpponentMillIfSameElementDraw(effectText)  != null) return "OpponentMillIfSameElementDraw";
        if (tryParseOpponentMill(effectText)                  != null) return "OpponentMill";
        if (tryParseSelfMill(effectText)                      != null) return "SelfMill";
        // Must precede OpponentRevealHand — see the ordering note in parse().
        if (tryParseOpponentRevealNSelectOneDiscard(effectText) != null) return "OpponentRevealNSelectOneDiscard";
        if (tryParseOpponentRevealHand(effectText)            != null) return "OpponentRevealHand";
        if (tryParseEachPlayerRevealCharacterMayPlay(effectText)      != null) return "EachPlayerRevealMayPlay";
        if (tryParseEachPlayerMaySearchForwardMinPower(effectText)     != null) return "EachPlayerMaySearchForwardMinPower";
        if (tryParseRevealTopDeck(effectText, source)         != null) return "RevealTopDeck";
        if (tryParseStandaloneDamageShields(effectText, source) != null) return "StandaloneDamageShields";
        if (tryParseDualSearchJobAndTypeDontShareElements(effectText)      != null) return "DualSearchDontShareElements";
        if (tryParseSearchElementOrCategoryCharsDiffCost(effectText)       != null) return "SearchElementOrCategoryCharsDiffCost";
        if (tryParseSearchNElementSummonsDiffCost(effectText)              != null) return "SearchNElementSummonsDiffCost";
        // Mirrors parse(): ahead of the single-pool search, whose prefix it shares.
        if (tryParseDualSearchPlayOntoField(effectText)            != null) return "DualSearchPlayOntoField";
        // Must precede SearchDeck, mirroring parse(): that parser names the search alone and
        // leaves the "If you do so, ..." payoff out of the report.
        if (tryParseSearchNamedRfgThenIfDoSo(effectText, source) != null) return "SearchNamedRfgThenIfDoSo";
        if (tryParseSearchDeck(effectText, source, 0)                      != null) return "SearchDeck";
        if (tryParsePlayAllByNameFromBreakZone(effectText)      != null) return "PlayAllByNameFromBreakZone";
        if (tryParsePlaySourceFromBreakZone(effectText, source) != null) return "PlaySourceFromBreakZone";
        if (tryParsePlayBrokenCardOntoFieldDull(effectText) != null) return "PlayBrokenCardOntoFieldDull";
        if (tryParseAddBrokenCardToHand(effectText) != null) return "AddBrokenCardToHand";
        // Reads the anchored helper, not tryParsePlaySourceOntoField itself: that parser matches
        // with find(), so it reports a hit from the middle of texts an earlier parser claims in
        // parse() ("...search for 1 Forward ... and play it onto the field"). Naming off the loose
        // form moved 9 abilities onto this name and away from the one that actually runs them.
        if (isBarePlaySourceOntoField(effectText, source))              return "PlaySourceOntoField";
        if (tryParseActivateNamedCard(effectText)               != null) return "ActivateNamedCard";
        if (tryParseAttackOnceMore(effectText)                  != null) return "AttackOnceMore";
        if (tryParseOpponentCannotSearchThisTurn(effectText)    != null) return "OpponentCannotSearch";
        if (tryParseOpponentCannotCastAnyCardsThisTurn(effectText) != null) return "OpponentCannotCastAnyCards";
        if (tryParseRemoveFromBattle(effectText)                != null) return "RemoveFromBattle";
        if (tryParseChooseSummonFromBzToHandWithCostReduction(effectText) != null) return "ChooseSummonFromBzToHandWithCostReduction";
        if (tryParseChooseNSummonsBzPickOneHandRestRfg(effectText)        != null) return "ChooseNSummonsBzPickOneHandRestRfg";
        if (tryParseSelectNamedFromRfgToHand(effectText)                  != null) return "SelectNamedFromRfgToHand";
        if (tryParseChooseWarpCardRemoveCounter(effectText)               != null) return "ChooseWarpCardRemoveCounter";
        if (tryParseChooseWarpCardMayRemoveCounter(effectText)            != null) return "ChooseWarpCardMayRemoveCounter";
        if (tryParseChooseSummonInBzCastable(effectText)              != null) return "ChooseSummonInBzCastable";
        if (tryParseChooseSummonInBzMaxCostFreeCastRfg(effectText)    != null) return "ChooseSummonInBzMaxCostFreeCastRfg";
        if (tryParseCostReductionThisTurn(effectText)                 != null) return "CostReductionThisTurn";
        if (tryParsePlayCostReductionThisTurn(effectText)        != null) return "PlayCostReductionThisTurn";
        // Strict form on purpose: genuine self-cost text is a card-level property stripped in
        // parseFieldAbilities, so it never reaches here as an ability. The loose predicate only
        // ever fired on abilities that merely end in a cost-reduction clause.
        if (CardData.yieldsSelfCostModifier(effectText))                  return "SelfCostModifier";
        if (CardData.FIELD_CAST_COST_INCREASE_PATTERN.matcher(effectText).find()) return "CastCostIncrease";
        if (AutoAbilityTriggers.FA_DISCARD_JOB_TO_CAST.matcher(effectText).find()) return "DiscardJobToCast";
        if (tryParseExtraTurnThenLose(effectText)               != null) return "ExtraTurnThenLose";
        if (tryParseGainCrystalPerX(effectText, 0)               != null) return "GainCrystalPerX";
        // Mirrors parse()'s position for this guard; composite so the leading effect still names
        // itself rather than being hidden behind a bare "GainCrystal" label.
        if (tryParseTrailingGainCrystal(effectText, source, 0)   != null) {
            String gcHead = trailingGainCrystalHead(effectText);
            if (gcHead != null) {
                String headName = matchedPatternName(gcHead, source);
                return (headName != null ? headName : "?") + " + GainCrystal";
            }
        }
        if (tryParseGainCrystal(effectText)                      != null) return "GainCrystal";
        if (tryParseGainCrystalIfOpponentHas(effectText)         != null) return "GainCrystalIfOpponentHas";
        // Mirrors parse(): ahead of PlaceCounters, which reads "each Job Apprentice Mage you
        // control" as the card name the counters are placed on.
        if (tryParsePlaceCountersOnEachJob(effectText)           != null) return "PlaceCountersOnEachJob";
        if (tryParsePlaceCountersForEach(effectText, source)     != null) return "PlaceCountersForEach";
        if (tryParsePlaceCounters(effectText, source)            != null) return "PlaceCounters";
        if (tryParseRemoveAllCounters(effectText, source)         != null) return "RemoveAllCounters";
        if (tryParseLookTopDeckOptionallyBreak(effectText)        != null) return "LookTopDeckOptionallyBreak";
        if (tryParseLookTopDeckBottomOrKeep(effectText)           != null) return "LookTopDeckBottomOrKeep";
        if (tryParseCounterScaleLookAddToHand(effectText, 1)               != null) return "CounterScaleLookAddToHand";
        if (tryParseLookTopDeckAddToHandRestBottom(effectText)          != null) return lookAddToHandRestBottomPatternName(effectText);
        if (tryParseLookTopDeckAddToHandOneToBreakRestBottom(effectText) != null) return "LookTopDeckAddToHandOneToBreakRestBottom";
        if (tryParseLookTopDeckAddToHandRestBreak(effectText)           != null) return "LookTopDeckAddToHandRestBreak";
        if (tryParseLookTopDeckTopOrBottom(effectText, source)          != null) {
            String then = trailingThenText(effectText, LOOK_TOP_DECK_TOP_OR_BOTTOM);
            return then == null ? "LookTopDeckTopOrBottom"
                    : "LookTopDeckTopOrBottom + " + matchedPatternName(then, source);
        }
        if (tryParseLookTopDeckReturnTopOrdered(effectText)             != null) return "LookTopDeckReturnTopOrdered";
        if (tryParseLookTopDeckPickOneTopRestBottom(effectText)              != null) return "LookTopDeckPickOneTopRestBottom";
        if (tryParseLookTopDeckCastSummonFreeRestBottom(effectText, 0)       != null) return "LookTopDeckCastSummonFreeRestBottom";
        if (tryParseLookTopDeckPeek(effectText)                              != null) return "LookTopDeckPeek";
        if (tryParseAddRemovedByPreviousEffectToHand(effectText, source)    != null) return "AddRemovedByPreviousEffectToHand";
        if (tryParseRemoveTopOfDeckFromGame(effectText, source)             != null) return "RemoveTopOfDeckFromGame";
        if (tryParseRevealPlayNamedWithMaxCostRestBottom(effectText)         != null) return "RevealPlayNamedWithMaxCostRestBottom";
        if (tryParseRevealPlayAsManyJobTypeTotalCostRestBottom(effectText)   != null) return "RevealPlayAsManyJobTypeTotalCost";
        if (tryParseRevealPlayNamedOrJobMaxCostRestBottom(effectText)        != null) return "RevealPlayNamedOrJobMaxCostRestBottom";
        // Mirrors parse(), where this is read ahead of tryParsePlaySourceOntoField rather than
        // beside its own family; the position here only has to keep it off its two siblings,
        // which it cannot collide with anyway (they end in "Add it to your hand").
        if (tryParseFlipUntilCharactersPlayOntoFieldRestShuffleBottom(effectText) != null) return "FlipUntilCharactersPlayOntoFieldRestShuffleBottom";
        if (tryParseFlipUntilTypeToHandRestShuffleBottom(effectText)         != null) return "FlipUntilTypeToHandRestShuffleBottom";
        if (tryParseFlipUntilElementToHandRestShuffleBottom(effectText)      != null) return "FlipUntilElementToHandRestShuffleBottom";
        if (tryParseRevealPlayTypeOntoFieldRestBottom(effectText) != null) return "RevealPlayTypeOntoFieldRestBottom";
        if (tryParseRevealElementCardFromHandIfSoDraw(effectText) != null) return "RevealElementCardFromHandIfSoDraw";
        if (tryParseShuffleDeck(effectText)                                  != null) return "ShuffleDeck";
        if (tryParseNameElementOnlySelfBecomes(effectText, source) != null) return "NameElementOnlySelfBecomes";
        if (tryParseNameElementAndJobSelfBecomes(effectText, source) != null) return "NameElementAndJobSelfBecomes";
        // Mirrors parse(), where these are the two halves of Jack Garland 27-111L. NameJob had no
        // entry here at all and reported as unnamed while fullDescription named it.
        if (tryParseNamedJobReference(effectText, source, 0) != null) return "NamedJobReference";
        if (tryParseNameJob(effectText, source)             != null) return "NameJob";
        if (tryParseGrantPartyAnyElementThisTurn(effectText) != null) return "GrantPartyAnyElementThisTurn";
        if (tryParseSourcePowerBecomesRemovedForwardPower(effectText, source) != null) return "SourcePowerBecomesRemovedPower";
        if (tryParseSourcePowerBecomesOpponentWeakestForward(effectText, source) != null) return "SourcePowerBecomesOpponentWeakestForward";
        if (tryParseOpponentGainsControlOfSource(effectText, source) != null) return "OpponentGainsControlOfSource";
        if (tryParseMayGiveSourceControlToOpponent(effectText, source) != null) return "MayGiveSourceControlToOpponent";
        if (tryParseIfSourceUsedSpecialsThisTurn(effectText, source, 0)  != null) return "IfSourceUsedSpecialsThisTurn";
        if (tryParseIfOwnForwardFormedParty(effectText, source, 0)       != null) return "IfOwnForwardFormedParty";
        if (tryParseIfOppDiscardedThisTurn(effectText, source, 0)        != null) return "IfOppDiscardedThisTurn";
        if (tryParseIfControlAtMost(effectText, source, 0)             != null) return "IfControlAtMost";
        if (tryParseIfCastAtLeast(effectText, source, 0)               != null) return "IfCastAtLeast";
        if (tryParseIfControlCondOtherThan(effectText, source, 0)      != null) return "IfControlCondOtherThan";
        // Reports the gate itself, not the effect behind it. Without an entry here the whole
        // gated sentence falls through to RemoveNamedFromGame, which find()s a name out of the
        // counter clause and would answer for a parser that never runs.
        if (tryParseWarpCounterCountGate(effectText, source, 0)        != null) return "WarpCounterCountGate";
        // Mirrors parse(): the generic counter gate is read after every counter that has a parser
        // of its own, so it names only what those leave.
        if (tryParseCountersOnSelfGate(effectText, source, 0) != null) return "CountersOnSelfGate";
        if (tryParseIfOppControlsNOrMoreCondTypeGate(effectText, source, 0) != null) return "IfOppControlsNOrMoreCondType";
        if (tryParseDiscardConditionalElement(effectText, source, 0)   != null) return "DiscardConditionalElement";
        if (tryParseDiscardConditionalElementSingle(effectText, source, 0) != null) return "DiscardConditionalElementSingle";
        if (tryParseDiscardConditionalTargetLoseAbilities(effectText) != null) return "DiscardConditionalTargetLoseAbilities";
        if (tryParseDiscardConditionalSelfBoostInstead(effectText, source, 0) != null) return "DiscardConditionalSelfBoostInstead";
        if (tryParseDrawDiscardIfMultiElement(effectText) != null) return "DrawDiscardIfMultiElement";
        if (tryParseConditionalOpponentHand(effectText, source, 0)     != null) return "ConditionalOpponentHand";
        if (tryParseConditionalOpponentHandMin(effectText, source, 0) != null) return "ConditionalOpponentHandMin";
        if (tryParseYouMayPutSelfToBZWhenDoSo(effectText, source)    != null) return "YouMayPutSelfToBZWhenDoSo";
        if (SELECT_FOLLOWING_ACTIONS_DETECT.matcher(effectText).find())        return "SelectFollowingActions";
        if (CardData.HAS_ALL_ELEMENTS_PATTERN.matcher(effectText.trim()).matches()) return "HasAllElements";
        if (tryParseMultiPlayGrant(effectText) != null)                         return "MultiPlayGrant";
        if (tryParseLightDarkDiscardCpGrant(effectText) != null)                return "LightDarkDiscardCpGrant";
        // Mirrors parse()'s position for this gate, which sits behind tryParseBackupCpDraw so the
        // unqualified Summon wording keeps its own parser. That one has no entry in this chain, so
        // the mirroring position here is simply "late": nothing ahead claims the qualified form.
        if (tryParseCastCpProducedByBackupsGate(effectText, source, 0) != null)
            return "CastCpProducedByBackupsGate";
        return null;
    }

    /**
     * Returns the name of the first followup pattern that matches {@code followupText}, or
     * {@code null} if no followup pattern recognises it.  The ordering mirrors the precedence
     * used inside {@link #tryParseChooseCharacter}.
     */
    public static String matchedFollowupName(String followupText, CardData source) {
        // Kept for the handful of checks below that must see the "You may": the strip that follows
        // is what lets an optional followup be identified by its effect, but it also erases the
        // difference between an offer and an order.
        final String rawFollowup = followupText.trim();
        // Strip leading "You may " so optional-followup effects are identified correctly
        if (followupText.toLowerCase(Locale.ROOT).startsWith("you may "))
            followupText = followupText.substring("You may ".length()).trim();
        // Mirrors parseChooseFollowup: a quoted grant spanning a sentence is settled here, ahead
        // of every find() check below, so the name cannot come from a clause printed inside the
        // quotation. The permanent shape is the only one that resolves, and only when its clause
        // is a real ability — anything else falls through to the unimplemented-followup warning
        // there and so must go unnamed here too.
        {
            Matcher anyGrantM = FOLLOWUP_GAINS_QUOTED_ABILITY.matcher(followupText.trim());
            if (anyGrantM.matches() && anyGrantM.group("quoted").contains(". ")) {
                Matcher permM = FOLLOWUP_GAINS_QUOTED_ABILITY_PERMANENT.matcher(followupText.trim());
                if (permM.matches() && !CardData.parseAutoAbilities(
                        PERMANENCE_REMINDER.matcher(permM.group("quoted").trim())
                                .replaceFirst("").trim()).isEmpty())
                    return "GainsQuotedAbilityPermanent";
                return null;
            }
        }
        // Mirrors the block parseChooseFollowup runs next, and for the same precedence reason: the
        // power clause and the clause quoted inside the grant are both things the find() checks
        // below would claim, which named Ellone 27-020R "PowerBoost" over a permanent double grant.
        {
            String grantCore = stripRestrictionSentences(followupText);
            if (grantCore.isEmpty()) grantCore = followupText;
            Matcher permBoostM =
                    FOLLOWUP_GAINS_POWER_AND_QUOTED_ABILITY_PERMANENT.matcher(grantCore.trim());
            if (permBoostM.matches()
                    && !CardData.parseAutoAbilities(permBoostM.group("quoted").trim()).isEmpty())
                return "GainsPowerAndQuotedAbilityPermanent";
        }
        // Mirrors the choose chain, where the two-tier attacker gate is read before every plain
        // action branch: both of its arms end in ordinary followups, so a find() check below would
        // claim one arm and report it as though it were unconditional. Guarded on both arms having
        // a parser, exactly as the dispatch is, so text the executor declines goes unnamed here too.
        {
            Matcher tieredAtkM = FOLLOWUP_TIERED_ATTACKERS_THIS_TURN.matcher(followupText.trim());
            if (tieredAtkM.matches()) {
                String lowTxt  = tieredAtkM.group("base").trim().replaceAll("(?i)\\bthe\\s+chosen\\s+Forward\\b", "it");
                String highTxt = tieredAtkM.group("upgrade").trim().replaceAll("(?i)\\bthe\\s+chosen\\s+Forward\\b", "it");
                if (parseTargetAction(lowTxt, 0) != null && parseTargetAction(highTxt, 0) != null)
                    return "TieredAttackersThisTurn";
            }
        }
        if (FOLLOWUP_TARGET_CONTROLLER_DISCARDS.matcher(followupText).matches()) return "TargetControllerDiscards";
        if (source != null) {
            Matcher mutM = FOLLOWUP_MUTUAL_POWER_DAMAGE.matcher(followupText);
            if (mutM.find() && mutM.group("srcname").trim().equalsIgnoreCase(source.name())) return "MutualPowerDamage";
        }
        // The sibling wording, where neither Forward is named because both were just chosen
        // ("Each Forward deals damage equal to its power to the other" — 19-062R Nacht and family).
        // tryParseChooseOneEach has always executed this correctly; only the name was missing, which
        // left the description reading "ChooseCharacter / ? + ?" as though nothing resolved.
        // Must precede the plain FOLLOWUP_DAMAGE check below, which would claim it with find().
        if (FOLLOWUP_EACH_FORWARD_MUTUAL_POWER_DAMAGE.matcher(followupText).find())
            return "EachForwardMutualPowerDamage";
        if (FOLLOWUP_DAMAGE_FOR_EACH_COUNTER.matcher(followupText).find())             return "DamageForEachCounter";
        if (FOLLOWUP_DAMAGE_FOR_EACH.matcher(followupText).find())                    return "DamageForEach";
        if (FOLLOWUP_DULL_AND_DAMAGE.matcher(followupText).find())                   return "DullAndDamage";
        if (FOLLOWUP_FIRST_AND_OTHER.matcher(followupText).find())                    return "FirstAndOther";
        if (FOLLOWUP_DAMAGE_AND_CONTROLLER_DAMAGE.matcher(followupText).find())       return "DamageAndControllerDamage";
        // Mirrors the choose chain: ahead of the plain damage name, which the first clause of this
        // sentence matches on its own.
        if (FOLLOWUP_DAMAGE_AND_SPLASH_OTHER_OPP_FORWARDS.matcher(followupText.trim()).matches())
                                                                                      return "DamageAndSplashOthers";
        if (FOLLOWUP_DAMAGE.matcher(followupText).find())                             return "Damage";
        if (FOLLOWUP_DAMAGE_EXPR.matcher(followupText).find())                        return "DamageExpr";
        if (FOLLOWUP_DIVIDE_DAMAGE_AMONG_CHOSEN.matcher(followupText).find())         return "DivideDamageAmongChosen";
        if (FOLLOWUP_ACTIVATE_AND_GAIN_CONTROL_EOT.matcher(followupText).find())        return "ActivateAndGainControlEOT";
        // The "If you control N or more …, <action>" gate comes before the plain action checks
        // below, which scan for their verb with find() and would otherwise claim the gated form as
        // unconditional. Guarded on parseTargetAction exactly as the dispatch is, so texts whose
        // action is not a recognised target action still fall through to their own handler.
        Matcher selfCondActionM = FOLLOWUP_IF_SELF_CONTROLS_N_ELEMENT_TYPE_ACTION.matcher(followupText);
        if (selfCondActionM.matches()
                && parseTargetAction(selfCondActionM.group("action").trim(), 0) != null)
            return "IfSelfControlsNElementTypeAction";
        if (FOLLOWUP_ACTIVATE_AND_NEGATE_DAMAGE.matcher(followupText).find())          return "ActivateAndNegateDamage";
        if (FOLLOWUP_NEGATE_DAMAGE.matcher(followupText).find())                      return "NegateDamage";
        if (FOLLOWUP_GAIN_CONTROL_WHILE_CARD.matcher(followupText).find())            return "GainControlWhileCard";
        if (FOLLOWUP_GAIN_CONTROL_EOT.matcher(followupText).find())                   return "GainControlEOT";
        if (FOLLOWUP_GAIN_CONTROL.matcher(followupText).find())                       return "GainControl";
        if (FOLLOWUP_SELF_AND_TARGET_GAIN_QUOTE_UNTIL_OPP_TURN.matcher(followupText).find()) return "SelfAndTargetGainUntilOppTurn";
        if (FOLLOWUP_TARGET_NEXT_SPECIAL_FREE.matcher(followupText).find())              return "TargetNextSpecialFree";
        if (FOLLOWUP_CAST_IT_FROM_BZ_ANYTIME_NO_HAND.matcher(followupText).find())      return "CastItFromBzAnytime";
        if (FOLLOWUP_GAINS_CANNOT_BE_CHOSEN.matcher(followupText).find())             return "GainsCannotBeChosen";
        if (FOLLOWUP_CANNOT_BE_BROKEN.matcher(followupText).find())                  return "CannotBeBroken";
        if (FOLLOWUP_CANNOT_BE_BROKEN_SIMPLE.matcher(followupText).find())           return "CannotBeBrokenSimple";
        if (FOLLOWUP_CANNOT_BE_BROKEN_BY_NON_DMG.matcher(followupText).find())      return "CannotBeBrokenByNonDmg";
        if (FOLLOWUP_IF_PUT_TO_BZ_THIS_TURN_RFG_INSTEAD.matcher(followupText).find()) return "IfPutToBzThisTurnRfgInstead";
        // The Choose chain resolves this secondary; without an entry here 7-055R Chocobo reported
        // "PowerBoost + ?" and read like a card handing out power for nothing.
        if (SECONDARY_WHEN_TARGET_LEAVES_PUT_SELF_TO_BZ.matcher(followupText).matches())
            return "WhenTargetLeavesPutSelfToBz";
        if (FOLLOWUP_SOURCE_GAINS_TARGET_ACTION_ABILITIES.matcher(followupText).matches())
                                                                                      return "GainsTargetActionAbilities";
        if (FOLLOWUP_GAINS_BREAK_WHEN_DEALT_DAMAGE.matcher(followupText).find())     return "BreakWhenDealtDamage";
        if (FOLLOWUP_GAINS_BREAKTOUCH_BATTLE.matcher(followupText).find())           return "BreaktouchBattle";
        if (FOLLOWUP_CANNOT_BE_CHOSEN_BOTH.matcher(followupText).find())              return "CannotBeChosenBoth";
        if (FOLLOWUP_CANNOT_BE_CHOSEN_SUMMONS.matcher(followupText).find())           return "CannotBeChosenSummons";
        if (FOLLOWUP_CANNOT_BE_CHOSEN_ABILITIES.matcher(followupText).find())         return "CannotBeChosenAbilities";
        if (FOLLOWUP_CANNOT_BE_RETURNED_TO_HAND.matcher(followupText).find())         return "CannotBeReturnedToHand";
        if (FOLLOWUP_CANNOT_BECOME_DULL_BY_OPP.matcher(followupText).find())          return "CannotBecomeDullByOpp";
        if (FOLLOWUP_DULL_OR_ACTIVATE.matcher(followupText).find())                   return "DullOrActivate";
        if (FOLLOWUP_DULL_OR_FREEZE.matcher(followupText).find())                     return "DullOrFreeze";
        if (FOLLOWUP_ACTIVATE.matcher(followupText).find())                           return "Activate";
        if (FOLLOWUP_ELEMENT_BECOMES.matcher(followupText).matches())                  return "ElementBecomes";
        if (FOLLOWUP_DULL.matcher(followupText).find()
                && !FOLLOWUP_DULL_AND_FREEZE.matcher(followupText).find()
                && !FOLLOWUP_DULL_OR_FREEZE.matcher(followupText).find())             return "Dull";
        if (FOLLOWUP_DULL_AND_FREEZE.matcher(followupText).find())                    return "DullAndFreeze";
        if (FOLLOWUP_FREEZE.matcher(followupText).find())                             return "Freeze";
        // Must precede Break, mirroring the Choose dispatch: "break it and draw 1 card" contains
        // "break it", so the plain Break name hid 24-065H Fenrir's cost comparison entirely.
        if (FOLLOWUP_IF_COST_EQUALS_DISCARD_BREAK_DRAW.matcher(followupText).find())
            return "IfCostEqualsExtraCostDiscardBreakDraw";
        if (FOLLOWUP_DAMAGE_EXTRA_COST_POWER.matcher(followupText).find())
            return "DamageEqualToExtraCostPower";
        if (FOLLOWUP_DAMAGE_REVEALED_FORWARD_POWER.matcher(followupText).find())
            return "DamageEqualToRevealedForwardPower";
        // Mirrors the choose chain: read ahead of the plain break, which find()s "break it" inside
        // this sentence and would report an unconditional one.
        if (FOLLOWUP_BREAK_IF_POWER_CHANGED.matcher(followupText.trim()).matches())
            return "BreakIfPowerChanged";
        if (FOLLOWUP_BREAK.matcher(followupText).find())                              return "Break";
        if (FOLLOWUP_LOSE_ABILITIES_AND_POWER_BECOMES.matcher(followupText).find())    return "LoseAllAbilitiesAndPowerBecomes";
        // Mirrors the choose chain: the standing silence is checked ahead of the until-end-of-turn
        // one, and carries the same source check — the branch there only fires when the card named
        // is the ability's own printing, so naming it without that would report an effect parse
        // declines.
        if (source != null) {
            Matcher wardenM = FOLLOWUP_LOSES_ABILITIES_WHILE_NAMED_ON_FIELD.matcher(followupText.trim());
            if (wardenM.matches() && wardenM.group("name").trim().equalsIgnoreCase(source.name()))
                return "LosesAbilitiesWhileSourceOnField";
        }
        if (FOLLOWUP_LOSE_ALL_ABILITIES_EOT.matcher(followupText).find())              return "LoseAllAbilitiesEot";
        if (FOLLOWUP_REMOVE_FROM_GAME_AND_NAMED.matcher(followupText).find())          return "RemoveFromGameAndNamed";
        if (FOLLOWUP_REMOVE_FROM_GAME.matcher(followupText).find())                   return "RemoveFromGame";
        if (SECONDARY_PLAY_REMOVED_ONTO_FIELD.matcher(followupText).find())           return "PlayRemovedOntoField";
        if (FOLLOWUP_PLAY_IF_COST_LE_JOB_COUNT.matcher(followupText).matches())       return "PlayIfCostLeJobCount";
        if (FOLLOWUP_PLAY_IF_COST_IS_X.matcher(followupText).matches())               return "PlayIfCostIsX";
        if (FOLLOWUP_RETURN_IF_COST_LE_HAND.matcher(followupText).matches())          return "ReturnIfCostLeHand";
        // Must precede PlayOntoField and AddToHand: this followup ends in the destination they
        // scan for, and both use find(), so either would claim it and report a search of the deck
        // as an action taken on the chosen target.
        if (FOLLOWUP_SEARCH_MATCHING_CHOSEN.matcher(followupText).matches())          return "SearchMatchingChosen";
        if (FOLLOWUP_PLAY_ONTO_FIELD.matcher(followupText).find())                    return "PlayOntoField";
        if (FOLLOWUP_ADD_TO_HAND.matcher(followupText).find())                        return "AddToHand";
        if (FOLLOWUP_RETURN_AND_NAMED_TO_OWNERS_HAND.matcher(followupText).find())    return "ReturnAndNamedToOwnersHand";
        if (FOLLOWUP_RETURN_TO_OWNERS_HAND.matcher(followupText).find())              return "ReturnToOwnersHand";
        if (FOLLOWUP_RETURN_TO_YOUR_HAND.matcher(followupText).find())                return "ReturnToYourHand";
        if (FOLLOWUP_PUT_TOP_OR_BOTTOM_OF_DECK.matcher(followupText).find())          return "PutTopOrBottomOfDeck";
        if (FOLLOWUP_PUT_BOTTOM_OF_DECK.matcher(followupText).find())                 return "PutBottomOfDeck";
        // The two Break-Zone-salvage forms, mirroring their adjacency in the choose chain. Both are
        // disjoint from the owner's-deck patterns around them ("your deck" vs "its owner's deck").
        if (FOLLOWUP_PUT_TOP_OF_YOUR_DECK.matcher(followupText).find())               return "PutTopOfYourDeck";
        // The compound form is guarded on its trailing effect having a parser, exactly as the
        // dispatch is, so a text the executor declines goes unnamed here too.
        {
            Matcher bottomThenM = FOLLOWUP_PUT_BOTTOM_OF_YOUR_DECK_AND_THEN.matcher(followupText);
            if (bottomThenM.find()) {
                String alsoTxt  = bottomThenM.group("also").trim();
                String alsoName = parse(alsoTxt, source) != null ? matchedPatternName(alsoTxt, source) : null;
                if (alsoName != null) return "PutBottomOfYourDeckThen[" + alsoName + "]";
            }
        }
        if (FOLLOWUP_PUT_BOTTOM_OF_YOUR_DECK.matcher(followupText).find())            return "PutBottomOfYourDeck";
        if (FOLLOWUP_PUT_TOP_OF_DECK.matcher(followupText).find())                    return "PutTopOfDeck";
        if (FOLLOWUP_PUT_UNDER_TOP_OF_DECK.matcher(followupText).find())              return "PutUnderTopOfDeck";
        if (FOLLOWUP_CANNOT_BLOCK.matcher(followupText).find())                       return "CannotBlock";
        if (FOLLOWUP_ONLY_BLOCKED_BY_COST_LE_OWN.matcher(followupText).find())        return "OnlyBlockedByCostLeOwn";
        if (FOLLOWUP_CANNOT_BE_BLOCKED.matcher(followupText).find())                  return "CannotBeBlocked";
        // Mirrors the choose chain, including its guard: the grant is only claimed when the quoted
        // ability is one the engine reads, so a quotation nothing implements still reports as "?".
        Matcher fuQuoted = FOLLOWUP_GAINS_QUOTED_ABILITY_UNTIL_EOT.matcher(followupText);
        if (fuQuoted.find()
                && AutoAbilityTriggers.FA_OUTGOING_DAMAGE_TO_OPPONENT_SETS_TO
                        .matcher(fuQuoted.group("granted").trim()).matches())
                                                                                      return "GainsDamageToOpponentSetsTo";
        // Mirrors the choose chain: the two counter-selection followups, then the two named
        // must-block forms ahead of the unqualified one.
        if (FOLLOWUP_SELECT_COUNTER_AND_ADD_SAME_TYPE.matcher(followupText.trim()).matches())
                                                                                      return "DuplicateCounter";
        if (FOLLOWUP_SELECT_COUNTER_AND_DOUBLE_SAME_TYPE.matcher(followupText.trim()).matches())
                                                                                      return "DoubleCounterType";
        if (FOLLOWUP_GAINS_MUST_BLOCK_NAMED_UNTIL_EOT.matcher(followupText).find())   return "MustBlockNamed";
        if (FOLLOWUP_MUST_BLOCK_NAMED_INLINE.matcher(followupText).find())            return "MustBlockNamed";
        if (FOLLOWUP_MUST_BLOCK.matcher(followupText).find())                         return "MustBlock";
        if (FOLLOWUP_CANNOT_ATTACK.matcher(followupText).find())                      return "CannotAttack";
        // Mirrors the choose chain: the compound grant is read before the plain compulsion.
        if (FOLLOWUP_GAINS_QUOTED_EOT_AND_SELF_POWER_BOOST.matcher(followupText.trim()).matches())
            return "GainsQuotedEotAndSelfPowerBoost";
        if (isMustAttackAndMustBlockGrant(followupText))                              return "MustAttackAndMustBlock";
        if (FOLLOWUP_MUST_ATTACK.matcher(followupText).find())                        return "MustAttack";
        // Must precede the plain form, mirroring the choose chain: that pattern's find() would take
        // the first clause and lose the action-ability half.
        if (FOLLOWUP_CANNOT_ATTACK_OR_BLOCK_AND_NEGATE_DAMAGE.matcher(followupText.trim()).matches())
            return "CannotAttackOrBlockAndNegateDamage";
        if (FOLLOWUP_CANNOT_ATTACK_OR_BLOCK_AND_NO_ACTION_ABILITIES.matcher(followupText).find())
            return "CannotAttackOrBlockOrUseActionAbilities";
        if (FOLLOWUP_CANNOT_ATTACK_OR_BLOCK.matcher(followupText).find())             return "CannotAttackOrBlock";
        if (FOLLOWUP_CANNOT_ATTACK_OR_BLOCK_PERSISTENT.matcher(followupText).find())  return "CannotAttackOrBlockPersistent";
        // Mirrors the choose chain: the board-wide scope is read ahead of the single-target one.
        if (FOLLOWUP_ALL_FORWARDS_POWER_BECOMES.matcher(followupText.trim()).matches())
            return "AllForwardsPowerBecomes";
        if (FOLLOWUP_POWER_BECOMES.matcher(followupText).find())                      return "PowerBecomes";
        if (FOLLOWUP_POWER_BOOST.matcher(followupText).find())                        return "PowerBoost";
        if (FOLLOWUP_POWER_BOOST_UNTIL_FOR_EACH.matcher(followupText).find())              return "PowerBoostUntilForEach";
        if (FOLLOWUP_POWER_BOOST_UNTIL_FOR_EACH_JOB.matcher(followupText).find())         return "PowerBoostUntilForEachJob";
        if (FOLLOWUP_POWER_BOOST_UNTIL_FOR_EACH_COUNTER.matcher(followupText).find())      return "PowerBoostUntilForEachCounter";
        if (FOLLOWUP_POWER_BOOST_UNTIL_FOR_EACH_SELF_DMG.matcher(followupText).find())    return "PowerBoostUntilForEachSelfDmg";
        if (FOLLOWUP_POWER_BOOST_UNTIL.matcher(followupText).find())                      return "PowerBoostUntil";
        // Must precede the two plain keyword grants, mirroring the parse order in
        // ActionResolverChoose — "First Strike or Brave" is a choice, not a pair.
        if (FOLLOWUP_KEYWORD_GRANT_CHOICE.matcher(followupText).find())               return "KeywordGrantChoice";
        if (FOLLOWUP_KEYWORD_GRANT.matcher(followupText).find())                      return "KeywordGrant";
        if (FOLLOWUP_KEYWORD_GRANT_UNTIL.matcher(followupText).find())               return "KeywordGrant";
        if (FOLLOWUP_HALVE_POWER.matcher(followupText.trim()).matches())              return "HalvePower";
        {
            // Named by scope: 17-027R Shiva stops only what its target deals to a Forward outside
            // combat, where 23-024R Shiva and 24-056C Cu Sith stop everything it deals.
            Matcher outZeroM = FOLLOWUP_OUTGOING_DMG_ZERO_THIS_TURN.matcher(followupText.trim());
            if (outZeroM.matches())
                return outZeroM.group("nonbattle") != null
                        ? "ZeroOutgoingAbilityDamageToForwards" : "ZeroAllOutgoingDamage";
        }
        if (FOLLOWUP_POWER_REDUCE.matcher(followupText).find())                       return "PowerReduce";
        if (FOLLOWUP_POWER_REDUCE_UNTIL_FOR_EACH_HAND.matcher(followupText).find())  return "PowerReduceUntilForEachHand";
        // The payoff half of the clause above, which the ". " split reports separately even though
        // the parser resolves the two together.
        if (FOLLOWUP_IF_POWER_BECAME_ZERO_DRAW.matcher(followupText).matches())      return "IfPowerBecameZeroDraw";
        // Mirrors the choose chain, where this precedes every other reduction branch: they all
        // find() a bare "it loses N power" inside this sentence and would drop the multiplier.
        if (FOLLOWUP_POWER_REDUCE_UNTIL_FOR_EACH_ATTACKER.matcher(followupText.trim()).matches())
            return "PowerReduceForEachAttacker";
        // Mirrors the two handlers: a self-side state count is declined there, so naming it here
        // would report a branch that never ran.
        Matcher pruferM = FOLLOWUP_POWER_REDUCE_UNTIL_FOR_EACH.matcher(followupText);
        if (pruferM.find() && !reduceForEachSelfState(pruferM))                       return "PowerReduceUntilForEach";
        if (FOLLOWUP_POWER_REDUCE_UNTIL.matcher(followupText).find())                 return "PowerReduceUntil";
        if (OPPONENT_DISCARD.matcher(followupText).find())                            return "OpponentDiscard";
        // Mirrors the choose chain, which reads this off the primary followup: the offer whose
        // payoff is the sentence after it, as opposed to MayDiscardNamedDealDamage's self-contained
        // form (named on the whole followup, in the ChooseCharacter block of fullDescription).
        // Read from rawFollowup — after the "You may " strip this text is a plain order to discard,
        // which is a different effect and reaches a different primitive.
        if (FOLLOWUP_MAY_DISCARD_TYPE_BARE.matcher(rawFollowup).matches())             return "MayDiscardType";
        if (source != null) {
            Matcher selfM = SELF_POWER_BOOST.matcher(followupText);
            if (selfM.find() && selfM.group("selfsubject").trim().equalsIgnoreCase(source.name()))
                return "SelfPowerBoost";
        }
        if (FOLLOWUP_PLACE_COUNTER_ON_IT.matcher(followupText).find())                 return "PlaceCounterOnIt";
        if (FOLLOWUP_REMOVE_ONE_COUNTER.matcher(followupText).find())                  return "RemoveOneCounter";
        if (BECOME_FORWARD_UNTIL_EOT_PATTERN.matcher(followupText).find())             return "BecomeForwardUntilEot";
        if (FOLLOWUP_CANCEL_EFFECT.matcher(followupText).find())                      return "CancelEffect";
        // Mirrors the choose chain: the source-scoped shield ahead of the unqualified one it
        // would otherwise be claimed by.
        if (FOLLOWUP_SHIELD_NEXT_OPP_EFFECT_DMG_ZERO.matcher(followupText).find())
                                                                                      return "ShieldNextOppEffectDmgZero";
        if (FOLLOWUP_SHIELD_NEXT_DMG_ZERO.matcher(followupText).find())               return "ShieldNextDmgZero";
        if (FOLLOWUP_SHIELD_NEXT_ABILITY_DMG_REDUCTION.matcher(followupText).find())   return "ShieldNextAbilityDmgReduction";
        // Mirrors the choose chain: the billed form is checked ahead of the plain reduction, and
        // carries the same source check — the branch there only fires when the card being billed is
        // the ability's own source, so naming it without that would report an effect parse declines.
        if (source != null) {
            Matcher kickM = FOLLOWUP_SHIELD_NEXT_DMG_REDUCTION_KICKBACK.matcher(followupText);
            if (kickM.find() && kickM.group("name").trim().equalsIgnoreCase(source.name()))
                return "ShieldNextDmgReductionKickback";
        }
        if (FOLLOWUP_SHIELD_NEXT_DMG_REDUCTION.matcher(followupText).find())          return "ShieldNextDmgReduction";
        if (FOLLOWUP_DEBUFF_INCOMING_DMG_INCREASE.matcher(followupText).find())       return "DebuffIncomingDmgIncrease";
        if (FOLLOWUP_DOUBLE_NEXT_OUTGOING.matcher(followupText).find())               return "DoubleNextOutgoingDamage";
        if (FOLLOWUP_SHIELD_NEXT_OUTGOING_ZERO.matcher(followupText).find())          return "ShieldNextOutgoingZero";
        if (FOLLOWUP_OUTGOING_DMG_BOOST_THIS_TURN.matcher(followupText).find())       return "OutgoingDmgBoostThisTurn";
        if (FOLLOWUP_SHIELD_NONLETHAL.matcher(followupText).find())                   return "ShieldNonLethal";
        if (FOLLOWUP_GAINS_SHIELD_ABILITY_ONLY.matcher(followupText).find())          return "GainsShieldAbilityOnly";
        if (FOLLOWUP_PUT_TO_BREAK_ZONE.matcher(followupText).find())                  return "PutToBreakZone";
        if (FOLLOWUP_SELECT_NUMBER_REVEAL_BREAK.matcher(followupText).find())         return "SelectNumberRevealBreak";
        if (FOLLOWUP_IF_OPPONENT_CONTROLS_FORWARDS_DAMAGE.matcher(followupText).matches()) return "IfOppControlsForwardsDamage";
        if (FOLLOWUP_IF_SELF_CONTROLS_N_ELEMENT_TYPE_DAMAGE.matcher(followupText).matches()) return "IfSelfControlsNElementTypeDamage";
        if (FOLLOWUP_REVEAL_TOP_N_DAMAGE_PER_CP_ADD_ALL_TO_HAND.matcher(followupText).find()) return "RevealTopNDamagePerCpAddAllToHand";
        if (FOLLOWUP_REVEAL_TOP_N_JOB_DEAL_DMG_PLACE_BOTTOM.matcher(followupText).find())    return "RevealTopNJobDealDmgPlaceBottom";
        return null;
    }

    /**
     * Returns a full description of which patterns cover {@code effectText}, including
     * primary, followup, and secondary layers.  A {@code "?"} in the result means that
     * layer has no matching pattern yet.  Returns {@code null} if no primary pattern matches.
     */
    /**
     * Describes one clause of a composite ability, degrading the way the gate descriptions do:
     * the full description when there is one, the pattern name when there is only that, and "?"
     * — this report's marker for an undescribed layer — when the clause is unrecognised.
     */
    private static String describeOrName(String clause, CardData source) {
        if (clause == null) return "?";
        String desc = fullDescription(clause, source);
        if (desc != null) return desc;
        String name = matchedPatternName(clause, source);
        return name != null ? name : "?";
    }

    public static String fullDescription(String effectText, CardData source) {
        // Through the shared helper rather than a second copy of its regex: the two had already
        // drifted, and this one still ate the plural in a clause opening "EX Bursts of cards …".
        effectText = stripExBurstPrefix(effectText);
        effectText = effectText.replaceFirst("(?i)^Then,?\\s+", "").trim();
        effectText = effectText.replaceFirst("(?i)^also\\s+", "").trim();
        // Strip trailing use-restriction sentences so they don't short-circuit before effect patterns match
        String noRestriction = stripRestrictionSentences(effectText);
        if (!noRestriction.isEmpty()) effectText = noRestriction;
        // Mirrors parse(); see the matching guard in matchedPatternNameOn(). Described like the
        // control gates below: the condition is named, the effect it guards described inside it.
        if (tryParseCastPaymentElementsGate(effectText, source, 0) != null) {
            Matcher cpg = CAST_PAYMENT_ELEMENTS_GATE.matcher(effectText.trim());
            if (!cpg.matches()) return "CastPaymentElementsGate";
            String baseTxt = cpg.group("base").trim();
            String tailTxt = cpg.group("tail").trim();
            String gate    = "IfCastPaidElements(" + cpg.group("count")
                    + (cpg.group("cmp").equalsIgnoreCase("more") ? "+" : "-") + ": ";
            Matcher inst = CAST_PAYMENT_ELEMENTS_TAIL_INSTEAD.matcher(tailTxt);
            if (inst.matches()) {
                String altTxt = insteadVariant(baseTxt, inst.group("alt").trim(), source);
                if (altTxt != null) altTxt = gateTailText(altTxt, source, 0);
                return gate + describeOrName(altTxt, source) + " | else "
                        + describeOrName(baseTxt, source) + ")";
            }
            // Same normalisation the parser applied, so the report names the clause that ran.
            return describeOrName(baseTxt, source) + " + " + gate
                    + describeOrName(gateTailText(tailTxt, source, 0), source) + ")";
        }
        // Mirrors parse(): the trailing cast-count gate, described the same way as the sibling
        // above — the base named as itself, the condition named around what it guards.
        if (tryParseCastCountGate(effectText, source, 0) != null) {
            Matcher ccg = CAST_COUNT_GATE.matcher(effectText.trim());
            if (!ccg.matches()) return "CastCountGate";
            String baseTxt = ccg.group("base").trim();
            String tailTxt = ccg.group("tail").trim();
            String gate    = "IfCastAtLeast(" + ccg.group("count") + ": ";
            Matcher inst = CAST_PAYMENT_ELEMENTS_TAIL_INSTEAD.matcher(tailTxt);
            if (inst.matches()) {
                // Same substitution the parser made, whole-base case included, so the report names
                // the clause that will actually run.
                String altTxt = castCountInsteadVariant(baseTxt, inst.group("alt").trim(), source);
                if (altTxt != null) altTxt = gateTailText(altTxt, source, 0);
                return gate + describeOrName(altTxt, source) + " | else "
                        + describeOrName(baseTxt, source) + ")";
            }
            return describeOrName(baseTxt, source) + " + " + gate
                    + describeOrName(gateTailText(tailTxt, source, 0), source) + ")";
        }
        // Mirrors parse(): the negated sibling of the gate above, described the same way.
        if (tryParseCastPaymentElementsNotIncludedGate(effectText, source, 0) != null) {
            Matcher ncpg = CAST_PAYMENT_ELEMENTS_NOT_INCLUDED_GATE.matcher(effectText.trim());
            if (!ncpg.matches()) return "CastPaymentElementsNotIncludedGate";
            return "IfCastNotPaidElements(" + ncpg.group("count") + "+: "
                    + describeOrName(ncpg.group("effect").trim(), source) + ")";
        }
        // Mirrors parse(): described like the gates above, with the guarded effect inside.
        if (tryParseCrystalHeldGate(effectText, source, 0) != null) {
            Matcher cg = CRYSTAL_HELD_GATE.matcher(effectText.trim());
            if (!cg.matches()) return "CrystalHeldGate";
            return (cg.group("negated") != null ? "IfNoCrystal(" : "IfCrystal(")
                    + describeOrName(cg.group("inner").trim(), source) + ")";
        }
        if (tryParseCastPaymentElementCpGate(effectText, source, 0) != null) {
            // One entry per gate, split the way the parser splits: 9-123L Chaos (MOBIUS) chains
            // three, and reading only the anchored pattern named the first and hid the rest
            // inside its greedy inner group.
            String gateText = effectText.trim();
            Matcher cpe = CAST_PAYMENT_ELEMENT_CP_GATE_CLAUSE.matcher(gateText);
            List<String> gates = new ArrayList<>();
            String element = null;
            int effectStart = -1;
            while (cpe.find()) {
                if (effectStart >= 0)
                    gates.add("IfCastPaid" + element + "Cp("
                            + describeOrName(gateText.substring(effectStart, cpe.start()).trim(), source) + ")");
                element = cap(cpe.group("element"));
                effectStart = cpe.end();
            }
            if (effectStart < 0) return "CastPaymentElementCpGate";
            gates.add("IfCastPaid" + element + "Cp("
                    + describeOrName(gateText.substring(effectStart).trim(), source) + ")");
            return String.join(" + ", gates);
        }
        // Mirrors parse(): the strict sibling of the gate above — the whole payment had to be that
        // Element, not merely include it — described the same way, with the guarded effect inside.
        if (tryParseCastPaymentOnlyElementCpGate(effectText, source, 0) != null) {
            Matcher oe = CAST_PAYMENT_ONLY_ELEMENT_CP_GATE.matcher(effectText.trim());
            if (!oe.matches()) return "CastPaymentOnlyElementCpGate";
            return "IfCastPaidOnly" + cap(oe.group("element")) + "Cp("
                    + describeOrName(oe.group("effect").trim(), source) + ")";
        }
        // Mirrors parse(): the "exactly N Elements" member of the same family.
        if (tryParseCastPaymentExactElementsGate(effectText, source, 0) != null) {
            Matcher ee = CAST_PAYMENT_EXACT_ELEMENTS_GATE.matcher(effectText.trim());
            if (!ee.matches()) return "CastPaymentExactElementsGate";
            return "IfCastPaidExactElements(" + ee.group("count") + ": "
                    + describeOrName(ee.group("effect").trim(), source) + ")";
        }
        // Mirrors parse(): the offer and its consequence are one clause, so both are named.
        if (tryParseOpponentMayDiscardElseEffect(effectText, source, 0) != null) {
            Matcher od = OPPONENT_MAY_DISCARD_ELSE_EFFECT.matcher(effectText.trim());
            if (!od.matches()) return "OpponentMayDiscardElseEffect";
            return "OpponentMayDiscard(" + od.group("count") + ") | else "
                    + describeOrName(od.group("effect").trim(), source);
        }
        // Mirrors parse()'s first dispatch; see the matching guard in matchedPatternNameOn().
        if (tryParseTrailingDraw(effectText, source, 0) != null) {
            String tdHead = trailingDrawHead(effectText);
            if (tdHead != null) {
                String headDesc = fullDescription(tdHead, source);
                return (headDesc != null ? headDesc : "?") + " + DrawCards";
            }
        }
        // Mirrors parse(); see the matching guard in matchedPatternNameOn().
        if (tryParseRemoveSelfThenPlaySelfOntoField(effectText, source) != null) return "RemoveSelfThenPlaySelfOntoField";
        // Mirrors parse(); see the matching guard in matchedPatternNameOn().
        // Must precede IndependentSentences, mirroring parse(): the splitter reports the removal
        // alone and drops the return clause.
        if (tryParseRemoveSelfReturnNextMainPhase1(effectText, source) != null)
            return "RemoveSelfReturnNextMainPhase1";
        // Mirrors parse(): claimed whole, ahead of the splitter that would report it in halves.
        if (tryParseDivideOppForwardsIntoGroups(effectText) != null) return "DivideOppForwardsIntoGroups";
        // Mirrors parse(); see the matching guard in matchedPatternNameOn().
        if (tryParseOppRfgWholeHandFaceDown(effectText) != null) return "OppRfgWholeHandFaceDown";
        // Mirrors parse(); see the matching guard in matchedPatternNameOn().
        if (cancelAnyNumberFilter(effectText) != null) return "CancelAnyNumberAbilitiesOnStack";
        if (tryParseIndependentSentences(effectText, source, 0) != null) {
            String composed = composeOverSentences(effectText, s -> fullDescription(s, source));
            if (composed != null) return composed;
        }
        if (tryParseChooseSummonInBzCastable(effectText)              != null) return "ChooseSummonInBzCastable";
        if (tryParseChooseSummonFromBzToHandWithCostReduction(effectText) != null) return "ChooseSummonFromBzToHandWithCostReduction";
        if (tryParseChooseNSummonsBzPickOneHandRestRfg(effectText)    != null) return "ChooseNSummonsBzPickOneHandRestRfg";
        if (tryParseSelectNamedFromRfgToHand(effectText)              != null) return "SelectNamedFromRfgToHand";
        if (tryParseOppRfpTopDeckCastable(effectText)                != null) return "OppRfpTopDeckCastable";
        if (tryParseChooseFromOppBzCastable(effectText)              != null) return "ChooseFromOppBzCastable";
        if (tryParseChooseSummonsFromBzCastable(effectText)          != null) return "ChooseSummonsFromBzCastable";
        if (tryParseChooseSummonInBzMaxCostFreeCastRfg(effectText)   != null) return "ChooseSummonInBzMaxCostFreeCastRfg";
        // See the matching guard in matchedPatternName(): ahead of the choose/search families so a
        // modal ability is described as the choice it is, not as one of its quoted options.
        if (tryParseSelectFollowingActions(effectText, source)       != null)
            return selectFollowingActionsDescription(effectText, source);
        // Strict form: see the matching guard in matchedPatternName(). Sitting this early in the
        // chain, the loose predicate claimed the description of any ability whose text ends in a
        // cost-reduction clause, masking the real one.
        if (CardData.yieldsSelfCostModifier(effectText))                        return "SelfCostModifier";
        if (CardData.FIELD_CAST_COST_INCREASE_PATTERN.matcher(effectText).find()) return "CastCostIncrease";
        if (AutoAbilityTriggers.FA_DISCARD_JOB_TO_CAST.matcher(effectText).find()) return "DiscardJobToCast";
        if (CardData.YOUR_TURN_ONLY_PATTERN.matcher(effectText).matches())  return "YourTurnOnly";
        if (CardData.ONCE_PER_TURN_PATTERN.matcher(effectText).matches())   return "OncePerTurn";
        if (CardData.YOUR_TURN_ONLY_PATTERN.matcher(effectText).find()
                && CardData.ONCE_PER_TURN_PATTERN.matcher(effectText).find()) return "YourTurnOnly+OncePerTurn";
        if (CardData.MAIN_PHASE_ONLY_PATTERN.matcher(effectText).matches())        return "MainPhaseOnly";
        if (CardData.WHILE_PARTY_ATTACKING_PATTERN.matcher(effectText).matches()) return "WhilePartyAttacking";
        if (CardData.WHILE_CARD_ATTACKING_PATTERN.matcher(effectText).matches())  return "WhileCardAttacking";
        if (CardData.WHILE_CARD_BLOCKING_PATTERN.matcher(effectText).matches())   return "WhileCardBlocking";
        if (CardData.WHILE_CARD_IN_HAND_PATTERN.matcher(effectText).matches())   return "WhileCardInHand";
        if (CardData.CONTROL_IF_PATTERN.matcher(effectText).find())                  return "UseRestriction";
        if (CardData.YOUR_TURN_AND_CONTROL_IF_PATTERN.matcher(effectText).find())  return "UseRestriction";
        if (CardData.CONTROL_IF_NOT_ANY_PATTERN.matcher(effectText).find())        return "UseRestriction";
        if (CardData.OPPONENT_CONTROLS_N_OR_MORE_PATTERN.matcher(effectText).find()) return "UseRestriction";
        if (tryParseMayPayCostThenEffect(effectText, source, 0)         != null) return "MayPayCostThenEffect";
        // Must precede WhenYouDoSo, mirroring parse() and matchedPatternName(). The description
        // itself is produced by the ChooseCharacter block further down, which reads the followup.
        if (tryParseChooseMaySearchRfgThenElse(effectText, source, 0) != null)
            return "ChooseCharacter / MaySearchRfgThenElse";
        // Must precede WhenYouDoSo, mirroring parse() and matchedPatternName().
        if (tryParseRemoveAnyCountersThenChooseSameNumber(effectText, source) != null)
            return removeAnyCountersDescription(effectText, source);
        if (tryParseWhenYouDoSoSequence(effectText, source, 0)          != null) return "WhenYouDoSo";
        if (tryParseIfNotPayOrElse(effectText, source, 0)               != null) return "IfNotPayOrElse";
        if (tryParseRemoveTopThenPileThreshold(effectText, source)          != null) return "RemoveTopThenPileThreshold";
        if (tryParseAddRemovedBySourceAbilityToHand(effectText, source)     != null) return "AddRemovedBySourceAbilityToHand";
        // Mirrors parse(), where this gate precedes every reader of its inner effect. Described by
        // the gate plus what it unlocks, so the report shows both halves.
        if (tryParseIfSourceUsedSpecialsThisTurn(effectText, source, 0) != null)
            return "IfSourceUsedSpecialsThisTurn(" + ifSourceUsedSpecialsInnerDescription(effectText, source) + ")";
        // Described by the gate plus what it unlocks, for the reason the gate above is: the inner
        // effect is an ordinary one this chain can already name, and a bare gate name would hide it.
        Matcher oppDiscM = IF_OPP_DISCARDED_FROM_HAND_THIS_TURN.matcher(effectText.trim());
        if (oppDiscM.matches() && tryParseIfOppDiscardedThisTurn(effectText, source, 0) != null)
            return "IfOppDiscardedThisTurn(" + fullDescription(oppDiscM.group("effect").trim(), source) + ")";
        if (tryParseIfCastAtLeast(effectText, source, 0)                != null) return "IfCastAtLeast";
        if (tryParseIfControlCondOtherThan(effectText, source, 0)      != null) return "IfControlCondOtherThan";
        // Must precede ControlGatedInsteadUpgrade, mirroring parse(): the description belongs to
        // the ChooseCharacter block, which reads the whole followup.
        if (tryParseChooseGatedBoostInstead(effectText, source, 0) != null)
            return "ChooseCharacter / PowerBoostControlGatedInstead";
        if (tryParseControlGatedInsteadUpgrade(effectText, source, 0)  != null) return "ControlGatedInsteadUpgrade";
        // Mirrors parse(), where the gate sits beside the control gates. Described like
        // IfControl(…) below: the gate is named, the effect it guards described inside it.
        if (tryParseWarpCounterCountGate(effectText, source, 0)        != null) {
            Matcher wcg = WARP_COUNTER_COUNT_GATE.matcher(effectText.trim());
            if (!wcg.matches()) return "WarpCounterCountGate";
            String innerTxt  = wcg.group("effect").trim();
            String innerDesc = fullDescription(innerTxt, source);
            if (innerDesc == null) innerDesc = matchedPatternName(innerTxt, source);
            return "IfWarpCounters(" + wcg.group("count") + "+ on " + wcg.group("card").trim()
                    + ": " + (innerDesc != null ? innerDesc : "?") + ")";
        }
        // Mirrors parse() and matchedPatternName(): the generic counter gate is read after the
        // Warp one above, which owns the counters that do not live in the counter map.
        if (tryParseCountersOnSelfGate(effectText, source, 0) != null) {
            Matcher cg = COUNTERS_ON_SELF_GATE.matcher(effectText.trim());
            if (!cg.matches()) return "CountersOnSelfGate";
            return "IfSelfCounters(" + cg.group("count") + "+ " + cg.group("counter").trim() + ": "
                    + describeOrName(cg.group("inner").trim(), source) + ")";
        }
        if (tryParseControlConditionGate(effectText, source, 0)        != null) {
            Matcher ccg = CONTROL_CONDITION_GATE.matcher(effectText.trim());
            if (!ccg.matches()) return "ControlConditionGate";
            String innerTxt  = ccg.group("effect").trim();
            String innerDesc = fullDescription(innerTxt, source);
            if (innerDesc == null) innerDesc = matchedPatternName(innerTxt, source);
            // Plain ASCII separator: "?" is this report's marker for an undescribed layer, and a
            // "→" degrades to "?" on a cp1252 console, which would read as exactly that.
            return "IfControl(" + (ccg.group("neg") != null ? "not " : "")
                    + CardData.parseControlCondition(ccg.group("cond").trim())
                    + ": " + (innerDesc != null ? innerDesc : "?") + ")";
        }
        if (tryParseIfOppControlsNOrMoreCondTypeGate(effectText, source, 0) != null) return "IfOppControlsNOrMoreCondTypeDraw";
        if (tryParseDiscardConditionalElement(effectText, source, 0)    != null) return "DiscardConditionalElement";
        if (tryParseDiscardConditionalElementSingle(effectText, source, 0) != null) return "DiscardConditionalElementSingle";
        if (tryParseDiscardConditionalTargetLoseAbilities(effectText) != null) return "DiscardConditionalTargetLoseAbilities";
        if (tryParseDiscardConditionalSelfBoostInstead(effectText, source, 0) != null) return "DiscardConditionalSelfBoostInstead";
        if (tryParseDrawDiscardIfMultiElement(effectText) != null) return "DrawDiscardIfMultiElement";
        if (tryParseSelectNumber(effectText, source)          != null) return "SelectNumber";
        if (tryParseForEachJobAndNameDealDamageToForwards(effectText)   != null) return "ForEachJobAndNameDealDamageToForwards";
        // Mirrors its position in parse() and matchedPatternName(), both of which check it
        // immediately after the sibling above — it is the same effect in the other word order.
        if (tryParseDealNForEachJobOrNameToOppForwards(effectText)      != null) return "DealNForEachJobOrNameToOppForwards";
        if (tryParseSelfGainsWhenAttacksEOT(effectText, source)        != null) return "SelfGainsWhenAttacksEOT";
        if (tryParseDealDamageToForwardsForEach(effectText)         != null) return "DealDamageToForwardsForEach";
        if (tryParseDealDamageToForwardsExceptElement(effectText)          != null) return "DealDamageToForwardsExceptElement";
        if (tryParseRfpAllFwdExceptElementsThenTwiceDeck(effectText)       != null) return "RfpAllFwdExceptElementsThenTwiceDeck";
        // Mirrors parse(); see the matching guard in matchedPatternNameOn().
        if (tryParseDealSameAmountToAllForwardsExcept(effectText, source, 0) != null)
            return "DealSameAmountToAllForwardsExcept";
        if (tryParseDealDamageToForwards(effectText)                       != null) return "DealDamageToForwards";
        if (tryParseDivideDamageEquallyAmongAll(effectText)                != null) return "DivideDamageEquallyAmongAll";
        if (tryParseNoForwardCostCannotAttack(effectText)           != null) return "NoForwardCostCannotAttack";
        if (tryParseOwnForwardsCannotBeChosenByExBurst(effectText)  != null) return "OwnForwardsCannotBeChosenByExBurst";
        if (tryParseExBurstSuppression(effectText)                  != null) return "ExBurstSuppression";
        if (tryParseDealHalfPowerDamageToForwards(effectText)       != null) return "DealHalfPowerDamageToForwards";
        if (tryParseDealPowerMinusNDamageToForwards(effectText)     != null) return "DealPowerMinusNDamageToForwards";
        if (tryParseDealHalfSourcePowerDamageToForwards(effectText) != null) return "DealHalfSourcePowerDamageToForwards";
        if (tryParseDamageToCombatBlocker(effectText)               != null) return "DamageToCombatBlocker";
        if (MAY_COST_REPLAY_ABILITY.matcher(effectText).find())               return "MayReplayAbility";

        String normalizedEffectText = ELEM_TYPE_OR_ELEM_TYPE.matcher(effectText).replaceAll("$1 or $3 $2");
        String escapedEffectText = escapePeriodInName(normalizedEffectText, source);
        Matcher oneEachM = CHOOSE_ONE_EACH_PATTERN.matcher(normalizedEffectText);
        if (oneEachM.find()) {
            String followupName = matchedFollowupName(oneEachM.group("followup").trim(), source);
            if (followupName != null) return "ChooseOneEach / " + followupName;
            // followup not describable by matchedFollowupName — fall through to tryParseChooseFormerLatter
        }
        if (tryParseChooseForwardRedirectToNamed(normalizedEffectText) != null) return "ChooseForwardRedirectToNamed";
        if (tryParseChooseFormerLatter(normalizedEffectText, source) != null) return "ChooseFormerLatter";
        if (tryParseChooseForwardDealSelfDamageBreakIfCostLeDamage(normalizedEffectText) != null)
            return "ChooseForwardDealSelfDamageBreakIfCostLeDamage";
        if (tryParseChooseForwardSharedPowerLoss(normalizedEffectText, source) != null)
            return "ChooseForwardSharedPowerLoss";
        if (tryParseChooseFwdPowerLeAndOptOppBzFwdRfp(normalizedEffectText) != null)
            return "ChooseFwdPowerLeAndOptOppBzFwdRfp";
        if (tryParseChooseAnyNumberReturnToHand(normalizedEffectText) != null)
            return "ChooseAnyNumberReturnToHand";
        Matcher threeMixedM = CHOOSE_THREE_MIXED_TYPES_PATTERN.matcher(normalizedEffectText);
        if (threeMixedM.find()) {
            String followupName = matchedFollowupName(threeMixedM.group("followup").trim(), source);
            return "ChooseThreeMixedTypes / " + (followupName != null ? followupName : "?");
        }
        Matcher mixedM = CHOOSE_TWO_MIXED_TYPES_PATTERN.matcher(normalizedEffectText);
        if (mixedM.find()) {
            String followupName = matchedFollowupName(mixedM.group("followup").trim(), source);
            return "ChooseTwoMixedTypes / " + (followupName != null ? followupName : "?");
        }
        // Checked ahead of the ChooseCharacter block: these "choose … Forward(s) …" compounds would
        // otherwise be described as "ChooseCharacter / ?" (their branches aren't recognised followups),
        // keeping the card stuck in "partially parsed" coverage.
        if (tryParseChooseFwdRevealCostParity(effectText) != null) return "ChooseFwdRevealCostParity";
        if (tryParseChooseForwardsGainAbilityEot(effectText) != null) return "ChooseForwardsGainAbilityEot";
        if (tryParseChooseForwardPlacePetrification(effectText) != null) return "ChooseForwardPlacePetrification";
        if (tryParseRemoveAllCountersFromSelf(effectText, source) != null) return "RemoveAllCountersFromSelf";
        // Mirrors parse() and matchedPatternName(): must precede the ChooseCharacter block, whose
        // followup naming has nothing for either sentence and describes this as
        // "ChooseCharacter / ? + ?" (Kimahri 1-102H).
        if (tryParseChooseOppFwdGainsSpecialAbilityFreeOnce(effectText, source) != null)
            return "ChooseOppFwdGainsSpecialAbilityFreeOnce";
        // Mirrors parse() and matchedPatternName(): must precede the ChooseCharacter block, which
        // describes this as "ChooseCharacter / ? + IfControl(…: ?)".
        if (tryParseChooseTwoBzFwdPlayIfControl(effectText, source) != null)
            return "ChooseTwoBzFwdPlayIfControl";
        // Mirrors parse() and matchedPatternName(): must precede the ChooseCharacter block, which
        // describes only the first of the two choose clauses.
        if (tryParseChooseTwoJointAction(effectText, source) != null) {
            Matcher jointM = CHOOSE_FORMER_LATTER_PATTERN.matcher(effectText);
            String followupName = jointM.find()
                    ? matchedFollowupName(jointM.group("effects").trim(), source) : null;
            return "ChooseTwoJointAction / " + (followupName != null ? followupName : "?");
        }
        // Mirrors parse() and matchedPatternName(): must precede the ChooseCharacter block.
        if (tryParseChooseForwardsTotalCostBreak(effectText) != null) return "ChooseForwardsTotalCostBreak";
        // Same, and the amounts go in the description: they are the whole of what this parser
        // decides, and the ChooseCharacter block below would report the followup as "?".
        if (tryParseChooseTieredDamage(effectText) != null) {
            Matcher tiered = CHOOSE_TIERED_DAMAGE.matcher(effectText.trim());
            if (!tiered.matches()) return "ChooseTieredDamage";
            Matcher tiers = TIERED_DAMAGE_ONE_OF_THEM.matcher(tiered.group("tiers"));
            StringBuilder amounts = new StringBuilder();
            while (tiers.find())
                amounts.append(amounts.length() == 0 ? "" : "/").append(tiers.group("amount"));
            return "ChooseTieredDamage(" + amounts + ")";
        }
        // Mirrors parse() and matchedPatternName(): must precede the ChooseCharacter block, which
        // describes Xande 10-008L as "ChooseCharacter / ? + PlayOntoField" — one pick, and the
        // filter that decides the other reported as unread.
        if (tryParseChooseTwoCostsFromBzPlayBoth(effectText) != null) return "ChooseTwoCostsFromBzPlayBoth";
        // Mirrors tryParseChooseCharacter, which strips this trailing delayed trigger and parses
        // the rest as an ordinary choose-and-act. Without the same strip here the clause fell past
        // the choose block's sentence split and was reported as an unread tail — 15-014H Brynhildr
        // read as "ChooseCharacter / Damage + ?" while its draw had been arming all along.
        Matcher bzDrawM = CHOOSE_THEN_WHEN_PUT_TO_BZ_DRAW.matcher(effectText.trim());
        if (bzDrawM.matches() && tryParseChooseCharacter(effectText, source, 0) != null) {
            String headDesc = fullDescription(bzDrawM.group("head").trim(), source);
            return (headDesc != null ? headDesc : "ChooseCharacter")
                    + " + DrawOnFieldToBz(" + bzDrawM.group("count") + ")";
        }
        Matcher chooseM = CHOOSE_CHARACTER_PATTERN.matcher(escapedEffectText);
        if (chooseM.find()) {
            String followup      = restorePeriodInName(chooseM.group("followup").trim(), source);
            // Mirrors the choose chain's cast-payment gate, which is settled ahead of every
            // followup parser: the condition sits between the choose and its followup, so name
            // the followup with the gate around it rather than as an effect that always happens.
            // Gulool Ja Ja 27-007H's echo, whose amount is not in the text — it is however much
            // the trigger dealt. Named off the whole sentence rather than the followup, because
            // the exclusion is what makes it the echo AutoAbilityTriggers dispatches and
            // DamageResolver resolves: 23-077H Azul and 20-065H Antlion (IV) print the same
            // followup with no exclusion, nothing implements theirs, and they keep their "?".
            if (AutoAbilityTriggers.FA_DAMAGE_ECHO_TO_OTHER_FORWARD.matcher(effectText.trim()).matches())
                return "ChooseCharacter / DamageSameAmount";
            Matcher castPaidM = CAST_PAYMENT_ELEMENT_CP_GATE_CLAUSE.matcher(followup);
            if (source != null && castPaidM.lookingAt()
                    && castPaidM.group("name").trim().equalsIgnoreCase(source.name())) {
                String gatedName = matchedFollowupName(followup.substring(castPaidM.end()).trim(), source);
                return "ChooseCharacter / IfCastPaid" + cap(castPaidM.group("element")) + "Cp("
                        + (gatedName != null ? gatedName : "?") + ")";
            }
            // Mirrors the choose chain, where the strict sibling of the gate above is read
            // immediately after it and for the same reason.
            Matcher onlyPaidM = CAST_PAYMENT_ONLY_ELEMENT_CP_GATE_CLAUSE.matcher(followup);
            if (source != null && onlyPaidM.lookingAt()
                    && onlyPaidM.group("name").trim().equalsIgnoreCase(source.name())) {
                String gatedName = matchedFollowupName(followup.substring(onlyPaidM.end()).trim(), source);
                return "ChooseCharacter / IfCastPaidOnly" + cap(onlyPaidM.group("element")) + "Cp("
                        + (gatedName != null ? gatedName : "?") + ")";
            }
            // Mirrors the choose chain, where the third gate is read immediately after those two.
            Matcher castCountM = CAST_COUNT_GATE_CLAUSE.matcher(followup);
            if (castCountM.lookingAt()) {
                String gatedName = matchedFollowupName(followup.substring(castCountM.end()).trim(), source);
                return "ChooseCharacter / IfCastAtLeast(" + castCountM.group("count") + ": "
                        + (gatedName != null ? gatedName : "?") + ")";
            }
            // Mirrors the choose chain, where this is read off the full followup: the ". " split
            // puts the draw in one half and the reduction in the other, and describing them apart
            // reports a card that draws and a clause pointing at nothing.
            if (FOLLOWUP_DRAW_THEN_POWER_REDUCE_FOR_EACH_HAND.matcher(followup).matches())
                return "ChooseCharacter / DrawThenPowerReduceForEachHand";
            // Check damage-instead on the full followup before the ". " split eats the condition clause.
            // This mirrors what tryParseChooseAndFollowup does.
            Matcher insteadM = FOLLOWUP_DAMAGE_INSTEAD.matcher(followup);
            if (insteadM.find() && parseDamageInsteadCondition(insteadM.group("cond").trim()) != null)
                return "ChooseCharacter / DamageInstead";
            // The divide-damage sibling of the check above, read off the whole followup for the
            // same reason: the ". " split puts the alternate amount in the secondary, which
            // described 17-014R Bahamut's larger divide as an unread tail over a card whose
            // parser had been reading it all along.
            if (isDivideDamageInstead(followup))
                return "ChooseCharacter / DivideDamageInstead";
            // Read off the whole followup, mirroring the Choose chain: the amount is the power of
            // the card the reveal put into hand, so the reveal and the burn are one clause.
            if (FOLLOWUP_REVEAL_ADD_TO_HAND_IF_FORWARD_DAMAGE_ADDED_POWER.matcher(followup).matches())
                return "ChooseCharacter / RevealAddToHandIfForwardDamageAddedPower";
            // Also read off the whole followup, mirroring the Choose chain, and named by which way
            // the shield points: 9-068H Mist Dragon protects what it dulls, 23-024R Shiva disarms
            // what it freezes. Split, the shield sentence lands in the secondary and is reported
            // unread over a card that has both halves wired.
            // Read off the whole followup, mirroring the Choose chain: the second sentence lifts
            // the first's power gate rather than breaking a second time, and the ". " split
            // described 3-102R Odin as "Break + Break" — two unconditional breaks, which is also
            // what it was doing.
            {
                Matcher breakGateM = FOLLOWUP_BREAK_IF_POWER_CONTROL_GATED_INSTEAD.matcher(followup.trim());
                if (breakGateM.matches()) {
                    ControlCondition lift = CardData.parseControlCondition(breakGateM.group("cond").trim());
                    if (lift != null)
                        return "ChooseCharacter / BreakIfPower(" + breakGateM.group("power")
                                + "-) | IfControl(" + lift + ": Break)";
                }
            }
            // Read off the whole followup, mirroring the Choose chain: "it" in the ban names the
            // card the bounce has already put in hand, so the two sentences are one clause.
            if (FOLLOWUP_RETURN_TO_HAND_THEN_BAN_COPIES.matcher(followup.trim()).matches())
                return "ChooseCharacter / ReturnToOwnersHandAndBanCopies";
            {
                Matcher dullShieldM = FOLLOWUP_DULL_THEN_DAMAGE_SHIELD.matcher(followup.trim());
                if (dullShieldM.matches())
                    return "ChooseCharacter / " + (FOLLOWUP_FREEZE.matcher(dullShieldM.group()).find()
                                    ? "DullAndFreeze" : "Dull")
                            + (dullShieldM.group("incoming") != null
                                    ? "AndShieldAllIncoming" : "AndZeroAllOutgoing");
            }
            if (FOLLOWUP_SELECT_JOB_GRANT.matcher(followup).find())
                return "ChooseCharacter / SelectJobGrant";
            // Both read off the whole followup, mirroring the Choose chain: the ". " split turns
            // 4-090R Biggs' upgrade into a control gate over a targetless "it", and 29-107C Seer
            // (FFTA2)'s doubling clause into an unrecognised tail.
            if (FOLLOWUP_POWER_BOOST_CONTROL_GATED_INSTEAD.matcher(followup).matches())
                return "ChooseCharacter / PowerBoostControlGatedInstead";
            if (FOLLOWUP_DAMAGE_SELF_POWER_DOUBLED_IF_SUMMON_DISCARD.matcher(followup).matches())
                return "ChooseCharacter / DamageSelfPowerDoubledIfSummonDiscard";
            if (FOLLOWUP_MAY_DISCARD_NAMED_DEAL_DAMAGE.matcher(followup).matches())
                return "ChooseCharacter / MayDiscardNamedDealDamage";
            // Read off the whole followup, mirroring the parser: split, its first sentence is
            // described as a plain RemoveFromGame over the chosen Forwards.
            if (FOLLOWUP_MAY_SEARCH_RFG_THEN_ELSE.matcher(followup).matches())
                return "ChooseCharacter / MaySearchRfgThenElse";
            // Naming gap only — the Choose parser has resolved this followup all along (1-129C
            // Gilgamesh breaks or burns correctly), but with no entry here the ". " split below
            // described it as "? + Damage", which reads like a card doing half its text.
            if (FOLLOWUP_RFP_TOP_DECK_IF_FORWARD_BREAK_ELSE_DAMAGE.matcher(followup).find())
                return "ChooseCharacter / RfpTopDeckIfForwardBreakElseDamage";
            if (FOLLOWUP_RFP_TOP_DECK_AND_DAMAGE_PER_CP.matcher(followup).find())
                return "ChooseCharacter / RfpTopDeckDamagePerCp";
            if (FOLLOWUP_REVEAL_TOP_N_DAMAGE_PER_CP_ADD_ALL_TO_HAND.matcher(followup).find())
                return "ChooseCharacter / RevealTopNDamagePerCpAddAllToHand";
            if (FOLLOWUP_RFP_IF_SAME_TYPE_DRAW.matcher(followup).find())
                return "ChooseCharacter / RfpIfSameTypeDraw";
            if (FOLLOWUP_REVEAL_TOP_N_JOB_DEAL_DMG_PLACE_BOTTOM.matcher(followup).find())
                return "ChooseCharacter / RevealTopNJobDealDmgPlaceBottom";
            // Read off the whole followup, as parse() does — the ". " split below would otherwise
            // separate the reveal from the cost test that consumes it, describing 7-065H Vanille
            // as "? + Break" and losing the condition on the break.
            if (FOLLOWUP_SELECT_NUMBER_REVEAL_BREAK.matcher(followup).find())
                return "ChooseCharacter / SelectNumberRevealBreak";
            // Also read off the whole followup, mirroring the Choose parser: the ". " split puts
            // 12-021R Necron's "(This effect does not end at the end of the turn.)" in the
            // secondary, which described the card as "ElementBecomes + ?" over a reminder.
            if (FOLLOWUP_ELEMENT_BECOMES.matcher(followup).matches())
                return "ChooseCharacter / ElementBecomes";
            {
                Matcher youMayPayM = FOLLOWUP_YOU_MAY_PAY_ELEMENT_IF_DO_SO.matcher(followup);
                if (youMayPayM.matches()) {
                    String innerEff  = youMayPayM.group("effect").trim();
                    String innerDesc = matchedFollowupName(innerEff, source);
                    return "ChooseCharacter / YouMayPayElement[" + (innerDesc != null ? innerDesc : "?") + "]";
                }
            }
            // Followups that span sentences by design, named off the whole text before the split
            // below can cut them in half. Mirrors the choose chain, where the branches for these
            // two match the unsplit followup for the same reason: each is one effect written as two
            // sentences (Jecht 14-108H's two tiers, Naja Salaheem 14-050R's select-then-double), and
            // either half alone describes something the card does not do.
            {
                Matcher wholeM = FOLLOWUP_TIERED_ATTACKERS_THIS_TURN.matcher(followup.trim());
                if (wholeM.matches()) {
                    String wholeName = matchedFollowupName(followup, source);
                    if (wholeName != null) return "ChooseCharacter / " + wholeName;
                }
            }
            if (FOLLOWUP_SELECT_COUNTER_AND_DOUBLE_SAME_TYPE.matcher(followup.trim()).matches())
                return "ChooseCharacter / DoubleCounterType";
            // Same quote-aware split parse() uses, so the two cannot disagree about where the
            // primary followup ends when a granted ability is quoted across two sentences.
            int    dotIdx        = sentenceBreakOutsideQuotes(followup);
            String primaryPart   = dotIdx >= 0 ? followup.substring(0, dotIdx).trim() : followup;
            String secondaryRaw  = dotIdx >= 0 ? followup.substring(dotIdx + 2).trim() : null;
            String secondaryTxt  = secondaryRaw != null ? stripRestrictionSentences(secondaryRaw) : null;
            if (secondaryTxt != null && secondaryTxt.isEmpty()) secondaryTxt = null;
            String followupName  = matchedFollowupName(primaryPart, source);
            String secondaryDesc = null;
            // For AddToHand primaries, prefer the conditional-on-added-card form
            // ("If (it|the added card) (is|has) X, Y") over the generic flat description,
            // because the inner effect would otherwise be reported as if it ran unconditionally.
            if ("AddToHand".equals(followupName) && secondaryTxt != null && !secondaryTxt.isEmpty()) {
                Matcher condM = FOLLOWUP_ADD_TO_HAND_CONDITIONAL_SECONDARY.matcher(secondaryTxt);
                if (condM.matches()
                        && parseRevealCondition(condM.group("cond").trim()) != null) {
                    String innerTxt  = condM.group("inner").trim();
                    String innerDesc = fullDescription(innerTxt, source);
                    if (innerDesc == null) innerDesc = matchedPatternName(innerTxt, source);
                    if (innerDesc == null) innerDesc = matchedFollowupName(innerTxt, source);
                    secondaryDesc = "IfAddedCard(" + (innerDesc != null ? innerDesc : "?") + ")";
                }
            }
            // Mirrors the choose chain: "Its auto-ability will not trigger." is part of how the
            // play resolves rather than an effect after it, so it is folded into the followup's
            // name instead of being described as a second clause.
            if ("PlayOntoField".equals(followupName) && secondaryTxt != null
                    && ITS_AUTO_ABILITY_WILL_NOT_TRIGGER.matcher(secondaryTxt).matches()) {
                followupName = "PlayOntoFieldNoAutoAbility";
                secondaryTxt = null;
            }
            if ("PlayOntoField".equals(followupName) && secondaryTxt != null && !secondaryTxt.isEmpty()) {
                Matcher etfM = FOLLOWUP_PLAY_ONTO_FIELD_WHEN_ENTERS_CONDITIONAL.matcher(secondaryTxt);
                if (etfM.matches() && parseRevealCondition(etfM.group("cond").trim()) != null) {
                    String innerTxt  = etfM.group("inner").trim();
                    String innerDesc = fullDescription(innerTxt, source);
                    if (innerDesc == null) innerDesc = matchedPatternName(innerTxt, source);
                    if (innerDesc == null) innerDesc = matchedFollowupName(innerTxt, source);
                    secondaryDesc = "IfETF(" + (innerDesc != null ? innerDesc : "?") + ")";
                }
            }
            // Mirrors the choose chain, where this is read first among the secondaries: the
            // sentence adds an action to the cards the primary chose, and the generic fallback
            // below described 1-059R Laguna's as unread and 1-043H Snow's as an unconditional
            // Freeze over a card that only freezes while it controls Shiva's caster.
            if (secondaryDesc == null && secondaryTxt != null && !secondaryTxt.isEmpty())
                secondaryDesc = secondaryConditionGatedActionAlsoName(secondaryTxt, source);
            // Mirrors the choose chain, where this is tried ahead of the general parse: the
            // sentence reads as a bare conditional on its own and no chain entry claims it.
            if (secondaryDesc == null && secondaryTxt != null && !secondaryTxt.isEmpty()
                    && secondaryCounterGatedPowerBecomes(secondaryTxt, source) != null)
                secondaryDesc = "IfSourceCounters(PowerBecomes)";
            if (secondaryDesc == null && secondaryTxt != null && !secondaryTxt.isEmpty())
                secondaryDesc = fullDescription(secondaryTxt, source);
            if (secondaryDesc == null && secondaryTxt != null && !secondaryTxt.isEmpty())
                secondaryDesc = matchedFollowupName(secondaryTxt, source);
            // Compound-sentence fallback: split secondary on ". " and describe each sentence.
            if (secondaryDesc == null && secondaryTxt != null && !secondaryTxt.isEmpty()) {
                String[] secSentences = secondaryTxt.split("(?<=\\.)\\s+(?=[A-Z])");
                if (secSentences.length > 1) {
                    List<String> parts = new ArrayList<>();
                    for (String s : secSentences) {
                        String d = fullDescription(s.trim(), source);
                        if (d == null) d = matchedPatternName(s.trim(), source);
                        if (d == null) d = matchedFollowupName(s.trim(), source);
                        parts.add(d != null ? d : "?");
                    }
                    secondaryDesc = String.join("+", parts);
                }
            }
            StringBuilder sb = new StringBuilder("ChooseCharacter / ")
                    .append(followupName != null ? followupName : "?");
            if (secondaryDesc != null) sb.append(" + ").append(secondaryDesc);
            else if (secondaryTxt != null && !secondaryTxt.isEmpty()) sb.append(" + ?");
            return sb.toString();
        }

        if (tryParsePlayerCannotCastSummons(effectText)                != null) return "PlayerCannotCastSummons";
        // Mirrors parse(): ahead of CannotBeChosen, which would claim it off the quoted clause.
        if (tryParseSelfGainsAndBasePowerBecomesPermanent(effectText, source) != null) return "SelfGainsAndBasePowerBecomesPermanent";
        if (tryParseSelfGainsTraitsAndQuotedPermanent(effectText, source) != null)     return "SelfGainsTraitsAndQuotedPermanent";
        // Mirrors parse(); see the matching guard in matchedPatternName().
        if (tryParseSelfCannotBeChosenByAnyAndGainsTraits(effectText, source) != null)
            return "SelfCannotBeChosenByAnyAndGainsTraits";
        if (tryParseCannotBeChosenStandalone(effectText, source) != null)       return "CannotBeChosen";
        if (tryParseCannotBecomeDullOpp(effectText, source) != null)            return "CannotBecomeDullOpp";
        if (tryParseCannotBeReturnedToHandOpp(effectText, source) != null)      return "CannotBeReturnedToHandOpp";
        if (tryParseCharactersCannotBeReturnedToHandOpp(effectText) != null)    return "CharactersCannotBeReturnedToHandOpp";
        if (tryParseCannotBePutIntoBzOpp(effectText, source) != null)           return "CannotBePutIntoBzOpp";
        if (tryParseChooseOwnFwdBoostProtectionsOrAllIfDmg(effectText) != null) return "ChooseOwnFwdBoostProtectionsOrAllIfDmg";
        if (tryParseActivateAllOwnFwdsGainProtections(effectText) != null)      return "ActivateAllOwnFwdsGainProtections";
        if (tryParseStandaloneCannotAttackOrBlock(effectText, source) != null) return "CannotAttackOrBlock";
        if (tryParseNegateAllDamage(effectText) != null)                       return "NegateDamage";
        if (tryParsePlayerNextDamageZeroRedirect(effectText) != null)          return "PlayerNextDamageZeroRedirect";
        if (tryParsePlayerNextDamageZero(effectText) != null)                  return "PlayerNextDamageZero";
        if (tryParseCancelAutoAbilityAndDamageIfForward(effectText) != null) return "CancelAutoAbilityAndDamageIfForward";
        // Must precede CancelSummonOrAutoAbility, mirroring parse(): they share a first sentence.
        if (tryParseChooseStackEntryZeroItsDamage(effectText) != null) return "ChooseStackEntryZeroItsDamage";
        if (tryParseCancelStackEntry(effectText)              != null) return "CancelSummonOrAutoAbility";
        // Mirrors parse(): ahead of the general redirect, which would otherwise claim the name.
        if (tryParseRedirectChosenTarget(effectText, source)  != null) return "RedirectChosenTarget";
        if (tryParseCopyChosenAutoAbilityOnStack(effectText, source) != null) return "CopyChosenAutoAbilityOnStack";
        if (tryParseCancelAbilityOnStack(effectText)          != null) return "CancelAbilityOnStack";
        if (tryParseCancelStackEntryUnlessPay(effectText)     != null) return "CancelStackEntryUnlessPay";
        if (tryParseCancelChosenTargetUnlessPay(effectText)   != null) return "CancelChosenTargetUnlessPay";
        if (tryParseCancelChosenTargetUnlessDiscard(effectText) != null) return "CancelChosenTargetUnlessDiscard";
        if (tryParseTriggeredTargetAction(effectText, 0)      != null) return "TriggeredTargetAction";
        if (tryParseCancelChosenTargetBare(effectText)         != null) return "CancelChosenTargetBare";
        if (tryParseCancelTriggeringSummon(effectText)         != null) return "CancelTriggeringSummon";
        if (tryParseIfOppNotPayAction(effectText)             != null) return "IfOppNotPayAction";
        // Mirrors parse(): checked alongside its sentence-sharing sibling below.
        if (tryParseRevealTopToHandIfTypeElseTopOrBottom(effectText) != null) return "RevealTopToHandIfTypeElseTopOrBottom";
        if (tryParseCancelChosenRevealTopIfType(effectText)    != null) return "CancelChosenRevealTopIfType";
        if (tryParseCancelChosenMillTopIfNotType(effectText)   != null) return "CancelChosenMillTopIfNotType";
        if (tryParseCancelChosenMillBothIfSameType(effectText) != null) return "CancelChosenMillBothIfSameType";
        if (tryParseCancelSummonTargetingMyCharacter(effectText) != null) return "CancelSummonTargetingMyCharacter";
        if (tryParseSelectNumber(effectText, source) != null)               return "SelectNumber";
        if (tryParseChooseOppFwdDynCostBreak(effectText)               != null) return "ChooseOppFwdDynCostBreak";
        if (tryParseChooseFwdPowerInferiorToSource(effectText, source) != null) return "ChooseFwdPowerInferiorToSource";
        if (tryParseChooseFwdBzCostInferiorToRemovedPlay(effectText)   != null) return "ChooseFwdBzCostInferiorToRemovedPlay";
        if (tryParseDullAllOppFwdsPowerLeSource(effectText, source)    != null) return "DullAllOppFwdsPowerLeSource";
        if (tryParseRevealTopBreakSameCostAddToHand(effectText)       != null) return "RevealTopBreakSameCostAddToHand";
        if (tryParseIfSelfFwdReceivedDamageDraw(effectText, source)            != null) return "IfSelfFwdReceivedDamageDraw";
        if (tryParseIfRfpCount(effectText, source)                     != null) return "IfRfpCount";
        if (tryParseIfSelfRfgCount(effectText, source)                 != null) return "IfSelfRfgCount";
        // Must precede AllFieldEffect — see the ordering note in parse().
        if (tryParseEndOfOppTurnDelayedEffect(effectText, source) != null) {
            Matcher delayed = AT_END_OF_OPP_TURN_DELAY_PREFIX.matcher(effectText.trim());
            String inner = delayed.matches() ? delayed.group("inner").trim() : effectText;
            return "At the end of your opponent's turn: " + fullDescription(inner, source);
        }
        if (tryParsePlaceCounterOnAllForwards(effectText) != null)          return "PlaceCounterOnAllForwards";
        // Must precede AllFieldEffect — see the ordering note in parse().
        if (tryParseAllFieldActivateThenDraw(effectText) != null)           return "AllFieldEffect + DrawCards";
        // Mirrors parse(); see the matching guard in matchedPatternNameOn().
        if (tryParseBreakForwardsBelowSelfPower(effectText, source) != null)
            return "BreakForwardsBelowSelfPower";
        if (tryParseAllFieldEffect(effectText) != null)                     return "AllFieldEffect";
        if (tryParseFieldPowerGrantPassive(effectText) != null) {
            String trimmed = effectText.trim();
            return FIELD_OPPONENT_DEBUFF_PASSIVE.matcher(trimmed).matches()
                    ? "FieldOpponentPowerDebuff" : "FieldPowerGrant";
        }
        // Mirrors parse() and matchedPatternName(): kept beside the mass power effect it shares a
        // board with, though the pattern below needs a power figure and could not claim it.
        if (tryParseAllOppForwardsLoseTraitsEot(effectText) != null) return "AllOppForwardsLoseTraitsEot";
        // Mirrors parse(), where this sits far above the power-boost readers below. It has to: the
        // boost pattern is read with find() and 4-142R Malboro quotes one inside the ability it
        // grants itself, so left in its old place further down this chain the card was described as
        // "AllFieldPowerBoost + ?" — a sweep it does not perform, and no mention of the grant that
        // is the whole ability. Named here for every printing of the family, as parse() resolves it.
        if (tryParseBecomeForwardUntilEot(effectText, source) != null) return "BecomeForwardUntilEot";
        {
            Matcher bm = ALL_FIELD_POWER_BOOST_PATTERN.matcher(effectText);
            if (bm.find()) {
                String trailing = effectText.substring(bm.end()).trim().replaceAll("^[.!,]+\\s*", "").trim();
                if (!trailing.isEmpty()) {
                    String secDesc = fullDescription(trailing, source);
                    return "AllFieldPowerBoost + " + (secDesc != null ? secDesc : "?");
                }
                return "AllFieldPowerBoost";
            }
        }
        if (tryParseAllForwardsSameElementAsNamedPowerBoost(effectText) != null) return "AllForwardsSameElementAsNamedPowerBoost";
        if (tryParsePartyForwardsPowerBoost(effectText) != null)            return "PartyForwardsPowerBoost";
        if (tryParseAllFieldJobCardNamePowerBoost(effectText) != null)       return "AllFieldJobCardNamePowerBoost";
        if (tryParseTwoCardNamesPowerBoost(effectText) != null)             return "TwoCardNamesPowerBoost";
        if (tryParseAllFieldJobPowerBoost(effectText) != null)              return "AllFieldJobPowerBoost";
        if (tryParseAllFieldJobKeywordGrant(effectText) != null)            return "AllFieldJobKeywordGrant";
        if (tryParseAllFieldKeywordGrant(effectText) != null)               return "AllFieldKeywordGrant";
        if (tryParseAllFieldQuotedProtectionGrant(effectText) != null)      return "AllFieldQuotedProtectionGrant";
        if (tryParseUntilEotDualPowerShift(effectText) != null)            return "UntilEotDualPowerShift";
        if (tryParseUntilEotAllFieldPowerBoost(effectText) != null)        return "UntilEotAllFieldPowerBoost";
        if (tryParseStandalonePowerBoostAndAttackTrigger(effectText, source) != null) return "StandalonePowerBoostAndAttackTrigger";
        if (tryParseStandalonePowerBoostAndCannotBeChosen(effectText, source) != null) return "StandalonePowerBoostAndCannotBeChosen";
        if (tryParseStandaloneGainsTraitsAndCannotBeBlocked(effectText, source) != null) return "StandaloneGainsTraitsAndCannotBeBlocked";
        if (tryParseStandaloneGainsCannotBeBlocked(effectText, source) != null) return "StandaloneGainsCannotBeBlocked";
        if (tryParseSelfBasePowerBecomesUntil(effectText, source) != null)  return "SelfBasePowerBecomesUntil";
        if (tryParseStandalonePowerBoostUntil(effectText, source) != null)  return "StandalonePowerBoostUntil";
        if (tryParseStandaloneDoublePowerUntil(effectText, source) != null) return "StandaloneDoublePowerUntil";
        if (tryParseStandaloneDoublesItsPowerUntil(effectText, source) != null) return "StandaloneDoublesItsPowerUntil";
        if (tryParseStandaloneDoublePowerMainPhaseNextTurn(effectText, source) != null) return "StandaloneDoublePowerMainPhaseNextTurn";
        if (tryParseStandalonePowerReduceUntil(effectText, source) != null) return "StandalonePowerReduceUntil";
        if (tryParseDoubleOutgoingDamageThisTurn(effectText, source) != null)    return "DoubleOutgoingDamageThisTurn";
        if (tryParseDoubleOutgoingDamageThisTurnAlt(effectText, source) != null) return "DoubleOutgoingDamageThisTurnAlt";
        if (tryParseSelfOutgoingDmgBoostThisTurn(effectText, source) != null)   return "SelfOutgoingDmgBoostThisTurn";
        if (tryParseGainOutgoingDmgBoostUntilEot(effectText, source) != null)   return "GainOutgoingDmgBoostUntilEot";
        if (tryParseGainsQuotedFieldAbilityUntilEot(effectText, source) != null) return "GainsQuotedFieldAbilityUntilEot";
        if (tryParseGainsQuotedAbilitiesPermanent(effectText, source) != null)  return "GainsQuotedAbilitiesPermanent";
        if (tryParseSelfPowerBoostPermanent(effectText, source) != null)        return "SelfPowerBoostPermanent";
        if (tryParseUntilEotGainsPowerTraitsAndQuoted(effectText, source) != null) return "UntilEotGainsPowerTraitsAndQuoted";
        if (tryParseDoubleOpponentIncomingDamageThisTurn(effectText) != null)   return "DoubleOpponentIncomingDamageThisTurn";
        if (tryParseAllForwardIncomingDmgIncreaseThisTurn(effectText) != null)  return "AllForwardIncomingDmgIncreaseThisTurn";
        if (tryParseChooseForwardDoubleIncomingThisTurn(effectText) != null)    return "ChooseForwardDoubleIncomingThisTurn";
        if (tryParseChooseForwardDoubleNextOutgoing(effectText) != null)        return "ChooseForwardDoubleNextOutgoing";
        if (tryParseDoublePlayerAbilityOutgoingThisTurn(effectText) != null)   return "DoublePlayerAbilityOutgoingThisTurn";
        if (tryParseStandaloneSelfBoostForEachCrystal(effectText, source) != null) return "StandaloneSelfBoostForEachCrystal";
        // Mirrors parse(): ahead of the two flat self-boost parsers, which share its frame.
        if (tryParseStandaloneSelfBoostForEachControlled(effectText, source) != null) return "StandaloneSelfBoostForEachControlled";
        if (tryParseStandaloneSelfBoostForEachDistinctElement(effectText, source) != null) return "StandaloneSelfBoostForEachDistinctElement";
        if (tryParseIfHandSizeSelfBoost(effectText, source)               != null) return "IfHandSizeSelfBoost";
        if (tryParseSelfBoostEotPrefix(effectText, source) != null)         return "SelfBoostUntilEot";
        if (tryParseSelfAttacksPerOwnDamage(effectText, source) != null)    return "SelfAttacksPerOwnDamage";
        if (tryParseStandaloneSelfBoost(effectText, source) != null)        return "StandaloneSelfBoost";
        if (tryParseOppFieldEntryRfgInstead(effectText)                   != null) return "OppFieldEntryRfgInstead";
        if (tryParseStandaloneSelfLosesAllAbilities(effectText, source) != null) return "StandaloneSelfLosesAllAbilities";
        if (tryParseOppLoseJobsUntilEot(effectText) != null) return "OppLoseJobsUntilEot";
        if (tryParseStandaloneSelfDullAndShield(effectText, source) != null) return "StandaloneSelfDullAndShield";
        if (tryParseStandaloneSelfDull(effectText, source) != null)          return "StandaloneSelfDull";
        if (tryParseDullActiveYouControl(effectText, source, 0) != null)     return "DullActiveYouControl";
        if (tryParseStandaloneShieldCannotBeBroken(effectText, source) != null) return "StandaloneShieldCannotBeBroken";
        if (tryParseAllOwnForwardsNullifyAbilityDamage(effectText)        != null) return "AllOwnForwardsNullifyAbilityDamage";
        if (tryParseOwnJobOrNameNullifyAbilityDamage(effectText)          != null) return "OwnJobOrNameNullifyAbilityDamage";
        if (tryParseDoublecastFreeSummons(effectText)                     != null) return "DoublecastFreeSummons";
        if (tryParseCastRfgCostCardThisTurn(effectText)                   != null) return "CastRfgCostCardThisTurn";
        if (tryParseChooseCardRemovedBySourceToBz(effectText, source)     != null) return "ChooseCardRemovedBySourceToBz";
        if (tryParseAllForwardsCannotBlock(effectText)                    != null) return "AllForwardsCannotBlock";
        if (tryParseForwardsOfCostCannotBlock(effectText)                 != null) return "ForwardsOfCostCannotBlock";
        if (tryParseEndOfNextTurnIfCardOnFieldOppLoses(effectText)        != null) return "EndOfNextTurnIfCardOnFieldOppLoses";
        if (tryParseOppFwdsCannotBlockInferiorPower(effectText)           != null) return "OppFwdsCannotBlockInferiorPower";
        if (tryParseAllFwdsBlockedOnlyByLowerCostThisTurn(effectText)    != null) return "AllFwdsBlockedOnlyByLowerCost";
        if (tryParseOppFwdsLoseAllAbilitiesEot(effectText)         != null) return "OppFwdsLoseAllAbilitiesEot";
        if (tryParseOppFwdPowerBoostSuppressedThisTurn(effectText) != null) return "OppFwdPowerBoostSuppressedThisTurn";
        if (tryParseOppFwdsLosePowerPerPlayCost(effectText)        != null) return "OppFwdsLosePowerPerPlayCost";
        if (tryParseStandaloneGainsCannotBeBlocked(effectText, source) != null) return "StandaloneGainsCannotBeBlocked";
        if (tryParseStandaloneCannotBeBlocked(effectText, source) != null) return "StandaloneCannotBeBlocked";
        if (tryParseRevealHandOptPickDiscardOppDraw(effectText) != null)    return "RevealHandOptPickDiscardOppDraw";
        if (tryParseRevealHandOptPickRfpOppDraw(effectText) != null)        return "RevealHandOptPickRfpOppDraw";
        // Must precede RevealSelectHandRfp — see the same guard in parse().
        if (tryParseRevealSelectHandRfpUntilEndOfOppTurn(effectText) != null) return "RevealSelectHandRfpUntilEndOfOppTurn";
        if (tryParseRevealSelectHandRfp(effectText) != null)               return "RevealSelectHandRfp";
        if (tryParseRevealSelectHandDiscard(effectText) != null)           return "RevealSelectHandDiscard";
        if (tryParseOpponentRandomHandRfp(effectText) != null)              return "OpponentRandomHandRfp";
        if (tryParseOpponentRandomHandToBottomDeck(effectText) != null)     return "OpponentRandomHandToBottomDeck";
        if (tryParseOpponentHandRfp(effectText) != null)                   return "OpponentHandRfp";
        if (tryParseRevealTopNAddOnePerTypeRestBz(effectText) != null)         return "RevealTopNAddOnePerTypeRestBz";
        if (tryParseRevealTopNAddUpToExcludingNameRestBz(effectText) != null)  return "RevealTopNAddUpToExcludingNameRestBz";
        if (tryParseRevealTopNTypeToHand(effectText)       != null)           return "RevealTopNTypeToHand";
        if (tryParseRevealTopNCategoryToHand(effectText)   != null)          return "RevealTopNCategoryToHand";
        if (tryParseRevealTopNJobOrNameToHand(effectText)  != null)          return "RevealTopNJobOrNameToHand";
        if (tryParseRevealTopNElementToHand(effectText)    != null)           return "RevealTopNElementToHand";
        if (tryParseRevealAddTypeToHandOrPlayJobTypeOntoFieldRestBottom(effectText) != null) return "RevealAddTypeToHandOrPlayJobTypeOntoFieldRestBottom";
        // Must precede ReturnNamedToHand — see the ordering note in parse().
        if (tryParseRevealPlayElementTypeCostOntoFieldRestBottom(effectText)     != null) return "RevealPlayElementTypeCostOntoFieldRestBottom";
        if (tryParseRevealPlayTypeCostOrNamedCostRestBottom(effectText)         != null) return "RevealPlayTypeCostOrNamedCostRestBottom";
        if (tryParseReturnNamedToHand(effectText) != null)                   return "ReturnNamedToHand";
        if (tryParseYouMayRemoveNamedFromGame(effectText, source) != null)   return "YouMayRemoveNamedFromGame";
        if (tryParseEndOfOppTurnPlayNamedOntoField(effectText) != null)     return "EndOfOppTurnPlayNamedOntoField";
        if (tryParseEndOfTurnPlayNamedOntoField(effectText)  != null)      return "EndOfTurnPlayNamedOntoField";
        if (tryParseRemoveAllOppBzFromGame(effectText)       != null)      return "RemoveAllOppBzFromGame";
        if (tryParseRevealTopNRfgOneCastableRestBottom(effectText) != null) return "RevealTopNRfgOneCastableRestBottom";
        // Must precede RemoveNamedFromGame, mirroring parse() and matchedPatternName().
        if (tryParseRemoveWarpCountersFromNamed(effectText, source) != null) return "RemoveWarpCountersFromNamed";
        // Must precede RemoveNamedFromGame, mirroring parse() and matchedPatternName().
        if (tryParseRemoveFromBreakZoneFromGame(effectText, source) != null)
            return removeFromBreakZoneDescription(effectText, source);
        if (tryParseRemoveNamedFromGame(effectText, source) != null)        return "RemoveNamedFromGame";
        // Must precede BreakSourceCard, mirroring parse() and matchedPatternName().
        if (tryParseBreakSelfAndBattlePartner(effectText, source) != null)
            return "BreakSelfAndBattlePartner";
        if (tryParseBreakSourceCard(effectText, source)        != null)     return "BreakSourceCard";
        if (tryParsePutSourceIntoBreakZone(effectText, source) != null)     return "PutSourceIntoBreakZone";
        if (tryParseYouMayPutSelfToBZWhenDoSo(effectText, source)    != null) return "YouMayPutSelfToBZWhenDoSo";
        if (tryParseIfOppNoForwardsPutToBreakZone(effectText, source)          != null) return "IfOppNoForwardsPutToBreakZone";
        if (tryParseIfEitherPlayerNoForwardsPutSourceToBz(effectText, source)  != null) return "IfEitherPlayerNoForwardsPutSourceToBz";
        if (tryParseIfSelfDamagePointsPutToBreakZone(effectText, source) != null) return "IfSelfDamagePointsPutToBreakZone";
        if (tryParsePutSourceToBottomOfDeck(effectText, source) != null)   return "PutSourceToBottomOfDeck";
        if (tryParsePutSourceOnTopOfDeck(effectText, source)   != null)     return "PutSourceOnTopOfDeck";
        if (tryParseBreakBlockingForward(effectText)           != null)     return "BreakBlockingForward";
        if (tryParseBreakForwardThatBlocksCard(effectText)     != null)     return "BreakForwardThatBlocksCard";
        if (tryParseChooseExBurstFromDamageZone(effectText)    != null)     return "ChooseExBurstFromDamageZone";
        if (tryParseExBurstSuppression(effectText)             != null)     return "ExBurstSuppression";
        if (tryParseDamageZoneSwap(effectText)              != null) {
            Matcher m = DAMAGE_ZONE_SWAP_PATTERN.matcher(effectText.trim());
            return m.matches() && m.group("draw") != null ? "DamageZoneSwap + DrawCards" : "DamageZoneSwap";
        }
        if (tryParseOpponentDrawThenRandomDiscard(effectText) != null)      return "OpponentDrawThenRandomDiscard";
        if (tryParseOpponentDraw(effectText) != null)                       return "OpponentDraw";
        if (tryParseOpponentRandomDiscard(effectText) != null)              return "OpponentRandomDiscard";
        if (tryParseEachPlayerSelectForwardDamage(effectText) != null)      return "EachPlayerSelectForwardDamage";
        if (tryParseBothPlayersSelectForwardToBreakZone(effectText) != null) return "BothPlayersSelectForwardToBreakZone";
        if (tryParseSelectCharCostLeExclToBz(effectText)             != null)  return "SelectCharCostLeExclToBz";
        if (tryParseSelectControlledCharacterToBz(effectText)        != null)  return "SelectControlledCharacterToBz";
        if (tryParseEachPlayerSelectUpToNToBreakZone(effectText) != null)   return "EachPlayerSelectUpToNToBreakZone";
        if (tryParseEachPlayerSelectUpToNActiveDullFreeze(effectText) != null)
            return "EachPlayerSelectUpToNActiveDullFreeze";
        if (tryParseOppSelectsUpToNForwardsBreakRest(effectText) != null)
            return "OppSelectsUpToNForwardsBreakRest";
        if (tryParseEachPlayerSelectsForwardsBreakRest(effectText) != null)
            return "EachPlayerSelectsForwardsBreakRest";
        if (tryParseEachPlayerDiscard(effectText) != null)                  return "EachPlayerDiscard";
        if (tryParseEachPlayerSalvageFromBreakZone(effectText) != null)     return "EachPlayerSalvageFromBreakZone";
        if (tryParseEachPlayerDraw(effectText) != null)                     return "EachPlayerDraw";
        if (tryParseNameCardTypeOpponentDiscardDrawIfMatch(effectText) != null) return "NameCardTypeOpponentDiscardDrawIfMatch";
        if (tryParseOpponentDiscard(effectText) != null)                    return "OpponentDiscard";
        if (tryParseDiscardHandThenDraw(effectText) != null)                return "DiscardHandThenDraw";
        if (tryParseDrawDiscardRetriggerIfCardName(effectText, source) != null) return "DrawDiscardRetriggerIfCardName";
        if (tryParsePlaceUpToHandToBottomThenRedraw(effectText) != null)    return "PlaceUpToHandToBottomThenRedraw";
        if (tryParseDrawCards(effectText) != null)                          return "DrawCards";
        if (tryParseYouMayDiscardType(effectText) != null)                  return "YouMayDiscardType";
        if (tryParseMayRevealElementFromHand(effectText) != null)           return "MayRevealElementFromHand";
        if (tryParseDiscardHand(effectText) != null)                        return "DiscardHand";
        if (tryParseDiscardNCards(effectText) != null)                      return "DiscardNCards";
        if (tryParseDiscardJobFromHand(effectText) != null)                 return "DiscardJobFromHand";
        if (tryParseDiscardThenDraw(effectText) != null)                    return "DiscardThenDraw";
        // Mirrors parse(), where this gate sits immediately ahead of IfEachPlayerEmptyHand.
        // Described like the control gates: the condition is named, the effect it guards inside it.
        if (tryParseIfAllHaveElement(effectText, source, 0) != null) {
            Matcher ahe = IF_ALL_HAVE_ELEMENT_GATE.matcher(effectText.trim());
            if (!ahe.matches()) return "IfAllHaveElement";
            return "IfAllHaveElement(" + ahe.group("type").trim() + "=" + ahe.group("element").trim()
                    + ": " + describeOrName(ahe.group("effect").trim(), source) + ")";
        }
        if (tryParseIfEachPlayerEmptyHand(effectText, source, 0) != null)   return "IfEachPlayerEmptyHand";
        if (tryParseDealPlayerDamageToOpponent(effectText) != null)         return "DealPlayerDamageToOpponent";
        if (tryParseDealPlayerDamageToSelf(effectText) != null)             return "DealPlayerDamageToSelf";
        if (tryParseRandomRevealHandCastIfSummonFree(effectText) != null)   return "RandomRevealHandCastIfSummonFree";
        if (tryParseCastSummonFromHandDiscounted(effectText) != null)       return "CastSummonFromHandDiscounted";
        if (tryParseCastSummonFromHandFree(effectText, 0) != null)          return "CastSummonFromHandFree";
        if (tryParseSearchAndCastSummonFree(effectText) != null)            return "SearchAndCastSummonFree";
        if (tryParsePlayAnyNumberFromHand(effectText, source) != null)      return "PlayAnyNumberFromHand";
        if (tryParseEachPlayerMayPlayFromHand(effectText, source, 0) != null) return "EachPlayerMayPlayFromHand";
        if (tryParsePlayFromHand(effectText, source, 0) != null)            return "PlayFromHand";

        // Mirrors parse(): ahead of OPPONENT_SELECTS_PATTERN, which would otherwise claim it.
        if (tryParseTurnPlayerBreaksOrTakesDamage(effectText, source) != null) return "TurnPlayerBreaksOrTakesDamage";
        if (tryParseOppSelectsMayBreakElseSelfCannotBlock(effectText, source) != null)
            return "Your opponent may put 1 Character they control into the Break Zone; if they do, "
                    + source.name() + " cannot block this turn";

        Matcher opSelM = OPPONENT_SELECTS_PATTERN.matcher(effectText);
        if (opSelM.find()) {
            String followup     = opSelM.group("followup").trim();
            String followupName = matchedFollowupName(followup, source);
            return "OpponentSelects / " + (followupName != null ? followupName : "?");
        }

        if (tryParseBzFwdToHandOppFwdToBzByDamage(effectText) != null)      return "BzFwdToHandOppFwdToBzByDamage";
        if (tryParseOpponentMillIfSameElementDraw(effectText) != null)      return "OpponentMillIfSameElementDraw";
        if (tryParseOpponentMill(effectText) != null)                       return "OpponentMill";
        if (tryParseSelfMill(effectText) != null)                           return "SelfMill";
        // Must precede OpponentRevealHand — see the ordering note in parse().
        if (tryParseOpponentRevealNSelectOneDiscard(effectText) != null)
            return "Opponent reveals cards from their hand; you select 1 for them to discard";
        if (tryParseOpponentRevealHand(effectText) != null)                 return "OpponentRevealHand";
        if (tryParseEachPlayerRevealCharacterMayPlay(effectText) != null)   return "EachPlayerRevealMayPlay";
        if (tryParseEachPlayerMaySearchForwardMinPower(effectText) != null) return "EachPlayerMaySearchForwardMinPower";
        if (tryParseRevealTopDeck(effectText, source) != null)
            return revealTopDeckDescription(effectText, source) + restrictionDesc(effectText);
        if (tryParseStandaloneDamageShields(effectText, source) != null)    return "StandaloneDamageShields";
        if (tryParseDualSearchJobAndTypeDontShareElements(effectText) != null) return "DualSearchDontShareElements";
        if (tryParseSearchNElementSummonsDiffCost(effectText)         != null) return "SearchNElementSummonsDiffCost";
        // Mirrors parse(): ahead of the single-pool search, whose prefix it shares.
        if (tryParseDualSearchPlayOntoField(effectText)       != null) return "DualSearchPlayOntoField";
        // Must precede SearchDeck, mirroring parse(): that parser names the search alone and
        // leaves the "If you do so, ..." payoff out of the report.
        if (tryParseSearchNamedRfgThenIfDoSo(effectText, source) != null) return "SearchNamedRfgThenIfDoSo";
        if (tryParseSearchDeck(effectText, source, 0) != null)              return "SearchDeck";
        if (tryParsePlayAllByNameFromBreakZone(effectText) != null)         return "PlayAllByNameFromBreakZone";
        if (tryParsePlaySourceFromBreakZone(effectText, source) != null)    return "PlaySourceFromBreakZone";
        if (tryParsePlayBrokenCardOntoFieldDull(effectText) != null) return "PlayBrokenCardOntoFieldDull";
        if (tryParseAddBrokenCardToHand(effectText) != null) return "AddBrokenCardToHand";
        // See the matching guard in matchedPatternName(): the anchored helper, not the find()-based
        // parser, so this cannot claim a clause sitting inside a longer ability.
        if (isBarePlaySourceOntoField(effectText, source))                  return "PlaySourceOntoField";
        if (tryParseActivateNamedCard(effectText) != null)                  return "ActivateNamedCard";
        if (tryParseAttackOnceMore(effectText) != null)                     return "AttackOnceMore";
        if (tryParseOpponentCannotSearchThisTurn(effectText) != null)       return "OpponentCannotSearch";
        if (tryParseOpponentCannotCastAnyCardsThisTurn(effectText) != null) return "OpponentCannotCastAnyCards";
        if (tryParseExtraTurnThenLose(effectText) != null)                  return "ExtraTurnThenLose";
        if (tryParseGainCrystalPerX(effectText, 0) != null)                 return "GainCrystalPerX";
        // Mirrors parse(); see the matching guard in matchedPatternNameOn().
        if (tryParseTrailingGainCrystal(effectText, source, 0) != null) {
            String gcHead = trailingGainCrystalHead(effectText);
            if (gcHead != null) {
                String headDesc = fullDescription(gcHead, source);
                return (headDesc != null ? headDesc : "?") + " + GainCrystal";
            }
        }
        if (tryParseGainCrystal(effectText)        != null)                  return "GainCrystal";
        if (tryParseGainCrystalIfOpponentHas(effectText) != null)            return "GainCrystalIfOpponentHas";
        // Mirrors parse(); see the matching guard in matchedPatternName().
        if (tryParsePlaceCountersOnEachJob(effectText) != null)              return "PlaceCountersOnEachJob";
        if (tryParsePlaceCountersForEach(effectText, source) != null)        return "PlaceCountersForEach";
        if (tryParsePlaceCounters(effectText, source) != null)               return "PlaceCounters";
        if (tryParseRemoveAllCounters(effectText, source) != null)           return "RemoveAllCounters";
        if (tryParseLookTopDeckOptionallyBreak(effectText)        != null) return "LookTopDeckOptionallyBreak";
        if (tryParseLookTopDeckBottomOrKeep(effectText)           != null) return "LookTopDeckBottomOrKeep";
        if (tryParseChooseOppFwdGainsSpecialAbilityFreeOnce(effectText, source) != null) return "ChooseOppFwdGainsSpecialAbilityFreeOnce";
        if (tryParseUseSpecialAbilityUsedThisTurn(effectText, source) != null) return "UseSpecialAbilityUsedThisTurn";
        if (tryParseChooseOppDamagedFwdIfHasAbilityBreak(effectText)       != null) return "ChooseOppDamagedFwdIfHasAbilityBreak";
        if (tryParseChooseAsManyAsFieldCount(effectText, source)           != null) return "ChooseAsManyAsFieldCount";
        if (tryParseChooseAsManyAsBzRfgJobCount(effectText)               != null) return "ChooseAsManyAsBzRfgJobCount";
        if (tryParseChooseCounterScaleCharsActivate(effectText, 1)         != null) return "ChooseCounterScaleCharsActivate";
        if (tryParseCounterScaleLookAddToHand(effectText, 1)               != null) return "CounterScaleLookAddToHand";
        if (tryParseLookTopDeckAddToHandRestBottom(effectText)          != null) return lookAddToHandRestBottomPatternName(effectText);
        if (tryParseLookTopDeckAddToHandOneToBreakRestBottom(effectText) != null) return "LookTopDeckAddToHandOneToBreakRestBottom";
        if (tryParseLookTopDeckAddToHandRestBreak(effectText)           != null) return "LookTopDeckAddToHandRestBreak";
        if (tryParseLookTopDeckTopOrBottom(effectText, source)          != null) {
            String then = trailingThenText(effectText, LOOK_TOP_DECK_TOP_OR_BOTTOM);
            return then == null ? "LookTopDeckTopOrBottom"
                    : "LookTopDeckTopOrBottom + " + fullDescription(then, source);
        }
        if (tryParseLookTopDeckReturnTopOrdered(effectText)             != null) return "LookTopDeckReturnTopOrdered";
        if (tryParseLookTopDeckPickOneTopRestBottom(effectText)              != null) return "LookTopDeckPickOneTopRestBottom";
        if (tryParseLookTopDeckCastSummonFreeRestBottom(effectText, 0)       != null) return "LookTopDeckCastSummonFreeRestBottom";
        if (tryParseLookTopDeckPeek(effectText)                              != null) return "LookTopDeckPeek";
        if (tryParseAddRemovedByPreviousEffectToHand(effectText, source)    != null) return "AddRemovedByPreviousEffectToHand";
        if (tryParseRemoveTopOfDeckFromGame(effectText, source)             != null) return "RemoveTopOfDeckFromGame";
        if (tryParseRevealPlayNamedWithMaxCostRestBottom(effectText)           != null) return "RevealPlayNamedWithMaxCostRestBottom";
        if (tryParseRevealPlayAsManyJobTypeTotalCostRestBottom(effectText)     != null) return "RevealPlayAsManyJobTypeTotalCost";
        if (tryParseRevealPlayNamedOrJobMaxCostRestBottom(effectText)          != null) return "RevealPlayNamedOrJobMaxCostRestBottom";
        // Mirrors parse() and matchedPatternName(); see the note there about its real position.
        if (tryParseFlipUntilCharactersPlayOntoFieldRestShuffleBottom(effectText) != null) return "FlipUntilCharactersPlayOntoFieldRestShuffleBottom";
        if (tryParseFlipUntilTypeToHandRestShuffleBottom(effectText)           != null) return "FlipUntilTypeToHandRestShuffleBottom";
        if (tryParseFlipUntilElementToHandRestShuffleBottom(effectText)        != null) return "FlipUntilElementToHandRestShuffleBottom";
        if (tryParseShuffleThenRevealPlayNamedRestBottom(effectText, source) != null) return "ShuffleThenRevealPlayNamedRestBottom";
        if (tryParseRevealPlayTypeOntoFieldRestBottom(effectText)                != null) return "RevealPlayTypeOntoFieldRestBottom";
        if (tryParseRevealElementCardFromHandIfSoDraw(effectText)                != null) return "RevealElementCardFromHandIfSoDraw";
        if (tryParseShuffleDeck(effectText)                              != null) return "ShuffleDeck";
        if (tryParseBackupCpDraw(effectText)                             != null) return "BackupCpDraw";
        // Mirrors parse(): must follow BackupCpDraw, which claims the unqualified Summon wording.
        // Described like the other cast-payment gates, with the guarded effect named inside it —
        // and from the same text the parser resolves, duration prefix reattached.
        if (tryParseCastCpProducedByBackupsGate(effectText, source, 0) != null) {
            Matcher bg = CAST_CP_PRODUCED_BY_BACKUPS_GATE.matcher(effectText.trim());
            if (!bg.matches()) return "CastCpProducedByBackupsGate";
            String inner = (bg.group("until") != null ? bg.group("until") : "") + bg.group("effect").trim();
            return "IfCastCpFrom" + (bg.group("category") != null ? bg.group("category") : "")
                    + "Backups(" + describeOrName(inner, source) + ")";
        }
        if (tryParseAllMonstersTemporaryForward(effectText)            != null) return "AllMonstersTemporaryForward";
        if (tryParseNameElementOnlySelfBecomes(effectText, source)      != null) return "NameElementOnlySelfBecomes";
        if (tryParseNameElementAndJobSelfBecomes(effectText, source)   != null) return "NameElementAndJobSelfBecomes";
        if (tryParseNamedJobReference(effectText, source, 0) != null)
            return "NamedJob(" + describeOrName(namedJobText(effectText, PLACEHOLDER_JOB), source) + ")";
        if (tryParseNameJob(effectText, source)                        != null) return "NameJob";
        if (tryParseGrantPartyAnyElementThisTurn(effectText)           != null) return "GrantPartyAnyElementThisTurn";
        if (tryParseSourcePowerBecomesRemovedForwardPower(effectText, source) != null) return "SourcePowerBecomesRemovedPower";
        if (tryParseSourcePowerBecomesOpponentWeakestForward(effectText, source) != null) return "SourcePowerBecomesOpponentWeakestForward";
        if (tryParseOpponentGainsControlOfSource(effectText, source) != null) return "OpponentGainsControlOfSource";
        if (tryParseMayGiveSourceControlToOpponent(effectText, source) != null) return "MayGiveSourceControlToOpponent";
        if (tryParseConditionalOpponentHand(effectText, source, 0)    != null) return "ConditionalOpponentHand";
        if (tryParseConditionalOpponentHandMin(effectText, source, 0) != null) return "ConditionalOpponentHandMin";
        if (tryParseYouMayPutSelfToBZWhenDoSo(effectText, source)    != null) return "YouMayPutSelfToBZWhenDoSo";
        if (SELECT_FOLLOWING_ACTIONS_DETECT.matcher(effectText).find())    return "SelectFollowingActions";
        if (CardData.HAS_ALL_ELEMENTS_PATTERN.matcher(effectText.trim()).matches()) return "HasAllElements";
        if (tryParseMultiPlayGrant(effectText) != null)                     return "MultiPlayGrant";
        if (tryParseLightDarkDiscardCpGrant(effectText) != null)            return "LightDarkDiscardCpGrant";
        // Must follow every pattern above: a trailing "during this turn, the cost required to cast
        // your next X is reduced by N" clause rides along with a primary effect on many cards, so
        // placing these earlier claims descriptions belonging to SearchDeck, ChooseCharacter and
        // the RemoveFromGame family. Until the self-cost guard above was tightened it matched the
        // same texts and stood in for these two, which is why they were never needed here before.
        if (tryParseCostReductionThisTurn(effectText)                != null) return "CostReductionThisTurn";
        if (tryParsePlayCostReductionThisTurn(effectText)            != null) return "PlayCostReductionThisTurn";
        return null;
    }

    /**
     * Describes a modal "select N of the M following actions" ability by enumerating its options,
     * e.g. {@code SelectFollowingActions(1 of 3: ChooseCharacter / Dull | DrawCards | ?)}.
     *
     * <p>A bare "SelectFollowingActions" would say less than the pre-existing behaviour did: before
     * this pattern was reported at all, the chain fell through to whichever option matched first and
     * at least named that one. Recursing into each quoted option keeps that detail while making the
     * modal structure explicit. An option the resolver cannot describe shows as {@code ?}, the same
     * placeholder the compound descriptions use.
     */
    private static String selectFollowingActionsDescription(String text, CardData source) {
        Matcher m = SELECT_FOLLOWING_ACTIONS.matcher(text);
        if (!m.find()) return "SelectFollowingActions";

        List<String> options = new ArrayList<>();
        Matcher q = SELECT_FOLLOWING_QUOTED_ACTION.matcher(m.group("actions"));
        while (q.find()) {
            String desc = fullDescription(q.group(1).trim(), source);
            options.add(desc != null && !desc.isBlank() ? desc : "?");
        }
        if (options.isEmpty()) return "SelectFollowingActions";

        String upTo = m.group("upTo") != null ? "up to " : "";
        return "SelectFollowingActions(" + upTo + m.group("select") + " of " + m.group("total")
                + ": " + String.join(" | ", options) + ")";
    }

    private static String revealTopDeckDescription(String text, CardData source) {
        Matcher m = REVEAL_CLAUSE_PATTERN.matcher(text);
        List<String> clauseDescs = new ArrayList<>();
        while (m.find()) {
            String action = m.group("action").trim();
            String op = normalizeRevealOp(action);
            if (op != null) {
                clauseDescs.add(op);
            } else {
                String effName = matchedPatternName(action, source);
                clauseDescs.add(effName != null ? effName : "?");
            }
        }
        return clauseDescs.isEmpty() ? "RevealTopDeck"
                : "RevealTopDeck / " + String.join(", ", clauseDescs);
    }

    private static String restrictionDesc(String effectText) {
        List<String> parts = new ArrayList<>();
        if (CardData.YOUR_TURN_ONLY_PATTERN.matcher(effectText).find())        parts.add("yourTurnOnly");
        if (CardData.ONCE_PER_TURN_PATTERN.matcher(effectText).find())         parts.add("oncePerTurn");
        if (CardData.MAIN_PHASE_ONLY_PATTERN.matcher(effectText).find())       parts.add("mainPhaseOnly");
        if (CardData.WHILE_PARTY_ATTACKING_PATTERN.matcher(effectText).find()) {
            parts.add("whilePartyAttacking");
        } else {
            Matcher wAtkM = CardData.WHILE_CARD_ATTACKING_PATTERN.matcher(effectText);
            if (wAtkM.find()) parts.add("whileCardAttacking:" + wAtkM.group("card"));
        }
        Matcher wBlkM = CardData.WHILE_CARD_BLOCKING_PATTERN.matcher(effectText);
        if (wBlkM.find()) parts.add("whileCardBlocking:" + wBlkM.group("card"));
        if (CardData.WHILE_CARD_IN_HAND_PATTERN.matcher(effectText).find()) parts.add("whileCardInHand");
        Matcher elemFwdM = CardData.ELEMENT_FORWARD_ENTERED_THIS_TURN_PATTERN.matcher(effectText);
        if (elemFwdM.find()) parts.add("elemFwdEntered:" + elemFwdM.group("element"));
        return parts.isEmpty() ? "" : " [" + String.join(", ", parts) + "]";
    }

    /**
     * Resolves an activated Action Ability:
     * <ol>
     *   <li>Logs the ability being pushed to the stack.</li>
     *   <li>AI (P2) automatically passes priority (no response implemented yet).</li>
     *   <li>Pops and executes the effect; logs an info message if unparsed.</li>
     * </ol>
     *
     * @param ability   the ability being activated
     * @param source    the card that used the ability
     * @param gameState current game state
     * @param ctx       live context for applying effects to the field
     */
    public static void resolve(ActionAbility ability, CardData source,
            GameState gameState, GameContext ctx) {
        resolve(ability, source, gameState, ctx, 0);
    }

    public static void resolve(ActionAbility ability, CardData source,
            GameState gameState, GameContext ctx, int xValue) {
        ctx.logEntry("[Stack] \"" + source.name() + "\" → " + ability.effectText());
        ctx.logEntry("[Stack] P2 passes — resolving");

        Consumer<GameContext> effect = parse(ability.effectText(), source, xValue);
        if (effect != null) {
            effect.accept(ctx);
        } else {
            ctx.logEntry("[ActionResolver] Effect not yet implemented: " + ability.effectText());
        }
    }

    // -------------------------------------------------------------------------
    // Effect parsers
    // -------------------------------------------------------------------------

    /**
     * Parses "Deal X damage to all [condition] Forwards [your opponent controls]".
     *
     * <ul>
     *   <li>No condition — all Forwards (P1 and P2, or opponent only if stated)</li>
     *   <li>condition=dull — only Dulled Forwards</li>
     *   <li>condition=damaged — only Forwards that have already taken damage</li>
     * </ul>
     *
     * Targets are collected before damage is applied.  Forwards are damaged in
     * reverse-index order so that breaks (which shift the list) do not corrupt
     * subsequent indices.
     */

    private static Consumer<GameContext> tryParseDealNForEachJobOrNameToOppForwards(String text) {
        Matcher m = DEAL_N_FOR_EACH_JOB_OR_NAME_TO_OPP_FORWARDS.matcher(text.trim());
        if (!m.matches()) return null;
        String job      = m.group("job").trim();
        String cardName = m.group("cardname").trim();
        int    baseDmg  = Integer.parseInt(m.group("amount"));
        return ctx -> {
            int count = ctx.countSelfFieldCards(true, true, true, job, null)
                      + ctx.countSelfFieldCards(true, true, true, null, cardName);
            int damage = baseDmg * count;
            ctx.logEntry("Effect: Deal " + baseDmg + " × " + count
                    + " (Job " + job + "/Name " + cardName + ") = " + damage + " to all opponent Forwards");
            if (damage <= 0) return;
            if (ctx.isP1()) {
                for (int i = ctx.p2ForwardCount() - 1; i >= 0; i--)
                    if (i < ctx.p2ForwardCount()) ctx.damageP2Forward(i, damage);
            } else {
                for (int i = ctx.p1ForwardCount() - 1; i >= 0; i--)
                    if (i < ctx.p1ForwardCount()) ctx.damageP1Forward(i, damage);
            }
        };
    }

    /**
     * Parses "Until the end of the turn, [Self] gains [keywords and] "When [Self] attacks,
     * [effect]"." — Heretical Knight Garland 9-061R for the bare form, Lightning 1-141L's Army of
     * One for the one that hands over Haste and First Strike in the same breath.
     *
     * <p>The inner effect is read by the damage parser first and by the full chain second. The
     * order is deliberate rather than redundant: Garland's "deal 6000 damage to all the Forwards
     * opponent controls" has resolved through the damage parser since this was written, and the
     * fallback is there for the inner texts that one declines — Lightning's is a choose whose
     * followup compels the chosen Forward to block her.
     */
    private static Consumer<GameContext> tryParseSelfGainsWhenAttacksEOT(String text, CardData source) {
        if (source == null) return null;
        Matcher m = SELF_GAINS_WHEN_ATTACKS_EOT.matcher(text);
        if (!m.matches()) return null;
        if (!m.group("subject").trim().equalsIgnoreCase(source.name())) return null;
        String innerText = m.group("inner").trim();
        Consumer<GameContext> innerEffect = tryParseDealDamageToForwards(innerText);
        if (innerEffect == null) innerEffect = parse(innerText, source);
        if (innerEffect == null) return null;
        final Consumer<GameContext> attackEffect = innerEffect;
        EnumSet<CardData.Trait> traits = parseTraits(m.group("traits"));
        return ctx -> {
            if (!traits.isEmpty()) {
                ctx.logEntry(source.name() + " gains " + traitNamesOnly(traits) + " until end of turn");
                ctx.boostSourceForward(source, 0, traits);
            }
            ctx.logEntry("Effect: " + source.name() + " gains 'When attacks, [effect]' until EOT");
            ctx.addTempAttackTrigger(source, attackEffect);
        };
    }

    static int halfPowerDamage(int power) {
        return (int)(Math.ceil(power / 2.0 / 1000) * 1000);
    }

    /**
     * Half of {@code power}, rounded <em>down</em> to the nearest 1000 — the other way the corpus
     * halves, spelled out by 5-133H Bismarck's "(round down to the nearest 1000)" and by the
     * damage printings that say the same.
     */
    static int halfPowerRoundedDown(int power) {
        return power / 2 / 1000 * 1000;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Returns {@code true} if {@code cardCost} satisfies the cost constraint, or if {@code costVal < 0} (no filter). */
    static boolean meetsCostFilter(int cardCost, int costVal, String costCmp) {
        if (costVal < 0) return true;
        if (costCmp == null) return cardCost == costVal;
        return costCmp.equalsIgnoreCase("less") ? cardCost <= costVal : cardCost >= costVal;
    }

    /**
     * Returns {@code true} if a forward satisfies {@code condition}.
     *
     * @param condition {@code "active"}, {@code "dull"}, {@code "damaged"},
     *                  {@code "attacking"}, {@code "blocking"}, or {@code null} (any)
     */
    static boolean meetsCondition(CardState state, int currentDamage,
            boolean isAttacking, boolean isBlocking, String condition) {
        if (condition == null) return true;
        return switch (condition.toLowerCase()) {
            case "active"         -> state == CardState.ACTIVE;
            case "dull"           -> state == CardState.DULL;
            case "damaged"        -> currentDamage > 0;
            case "attacking"      -> isAttacking;
            case "blocking"       -> isBlocking;
            default               -> true;
        };
    }

    /**
     * The power named by a {@link ActionResolverPatterns#FOLLOWUP_POWER_BECOMES} match, whichever
     * of its two word orders matched.
     */
    static int powerBecomesAmount(Matcher m) {
        String trailing = m.group("power");
        return Integer.parseInt(trailing != null ? trailing : m.group("powerFront"));
    }

    // -------------------------------------------------------------------------
    // Damage-instead condition helpers
    // -------------------------------------------------------------------------

    /**
     * True when {@code followup} is the "Divide N damage among them … . If &lt;cond&gt;, divide M
     * damage among those instead." pair that the Choose parser's divide block reads as one unit
     * (17-014R Bahamut). Mirrors that block exactly — the same ". " split, the same requirement
     * that the condition itself be understood — so the description cannot disagree with the effect
     * about whether the alternate amount was read.
     */
    static boolean isDivideDamageInstead(String followup) {
        if (!DIVIDE_DAMAGE_PATTERN.matcher(followup).find()) return false;
        int dotSpaceIdx = followup.indexOf(". ");
        if (dotSpaceIdx < 0) return false;
        Matcher condM = DIVIDE_DAMAGE_INSTEAD_COND.matcher(followup.substring(dotSpaceIdx + 2));
        return condM.find() && parseDamageInsteadCondition(condM.group("cond").trim()) != null;
    }

    static DamageInsteadCondition parseDamageInsteadCondition(String cond) {
        String s = cond.trim();

        // Target-state conditions
        if (s.equalsIgnoreCase("it is active"))
            return new DamageInsteadCondition.TargetIsActive();
        if (s.matches("(?i)it is a Multi-Element (?:Forward|Monster|Character|Backup)?\\s*"))
            return new DamageInsteadCondition.TargetIsMultiElement();

        // Self-state conditions
        if (s.equalsIgnoreCase("you have received a point of damage this turn"))
            return new DamageInsteadCondition.YouReceivedDamageThisTurn();
        if (s.equalsIgnoreCase("you have a Summon in your Break Zone"))
            return new DamageInsteadCondition.YouHaveSummonInBreakZone();

        // Self damage count: "you have received N points of damage or more"
        Matcher selfDmgM = Pattern
                .compile("(?i)you have received (\\d+) points? of damage or more").matcher(s);
        if (selfDmgM.find())
            return new DamageInsteadCondition.YouReceivedDamageAtLeast(Integer.parseInt(selfDmgM.group(1)));

        // Opponent damage count: "your opponent has received N points of damage or more"
        Matcher oppDmgM = Pattern
                .compile("(?i)your opponent has received (\\d+) points? of damage or more").matcher(s);
        if (oppDmgM.find())
            return new DamageInsteadCondition.OpponentDamageAtLeast(Integer.parseInt(oppDmgM.group(1)));

        // Opponent hand size: "your opponent has N cards or less in their hand"
        Matcher oppHandM = Pattern
                .compile("(?i)your opponent has (\\d+) cards? or (?:less|fewer) in their hand").matcher(s);
        if (oppHandM.find())
            return new DamageInsteadCondition.OpponentHandAtMost(Integer.parseInt(oppHandM.group(1)));

        // Cards cast this turn: "you have cast N or more cards this turn"
        Matcher castM = Pattern
                .compile("(?i)you have cast (\\d+) or more cards this turn").matcher(s);
        if (castM.find())
            return new DamageInsteadCondition.YouCastAtLeast(Integer.parseInt(castM.group(1)));

        // Forward count comparison
        if (s.equalsIgnoreCase("the number of Forwards your opponent controls is greater than the number of Forwards you control"))
            return new DamageInsteadCondition.OpponentHasMoreForwards();

        // EX Burst: "<name> results from an EX Burst"
        if (s.matches("(?i).+ results from an EX Burst"))
            return new DamageInsteadCondition.IsExBurst();

        // "If you control … [other than <name>]" — delegate to ControlCondition parser
        if (s.toLowerCase().startsWith("you control ")) {
            String rest = s.substring("you control ".length()).trim();
            String excludeName = null;
            Matcher otherThanM = Pattern
                    .compile("(?i)^(?<cond>.+?)\\s+other\\s+than\\s+(?<name>.+)$").matcher(rest);
            if (otherThanM.matches()) {
                excludeName = otherThanM.group("name").trim();
                rest = otherThanM.group("cond").trim();
            }
            ControlCondition cc = CardData.parseControlCondition(rest);
            if (cc != null) return new DamageInsteadCondition.YouControl(cc, excludeName);
        }
        return null;
    }

    /**
     * Parses one branch of a "If you do so, X. If not, Y." pair whose clauses refer back to the
     * cards already chosen ("break the chosen Forwards", "remove the chosen Forward from the
     * game", "deal 8000 damage to the chosen Forwards") into an action over those targets.
     *
     * <p>Separate from {@link #parseTargetAction} because the vocabulary is different — these
     * clauses name their subject rather than saying "it"/"them" — and deliberately narrow:
     * anything but the three printed verbs returns {@code null}, which fails the enclosing match
     * and lets the text fall through rather than resolving as half the card.
     */
    /**
     * "Deal it damage equal to the power of the Forward removed by the extra cost." — 18-136S
     * Titan, whose whole effect is the payoff for an optional extra cost.
     *
     * <p>Deals nothing when the extra cost was not paid. The power reads back as 0 there, and
     * dealing 0 damage is not the same as dealing none: it would still count as having dealt
     * damage for everything watching for that.
     */
    static BiConsumer<GameContext, List<ForwardTarget>> extraCostPowerDamage() {
        return (ctx, ts) -> {
            int power = ctx.extraCostRemovedCardPower();
            if (power <= 0) {
                ctx.logEntry("Effect: no Forward removed by the extra cost — no damage dealt");
                return;
            }
            ctx.logEntry("Effect: Deal it " + power + " damage (Extra Cost removed Forward power)");
            sortedByIdxDesc(ts, true) .forEach(ft -> ctx.damageTarget(ft, power));
            sortedByIdxDesc(ts, false).forEach(ft -> ctx.damageTarget(ft, power));
        };
    }

    /**
     * Damage equal to the power of the Forward revealed to pay the ability's cost — Rinoa 18-097R.
     *
     * <p>A revealed Forward of 0 power is a real card, unlike the extra-cost case above where 0
     * means "nothing was removed": the reveal cost must have been paid for the ability to have been
     * activated at all. It is still a no-op, so it is reported as one rather than dealing 0.
     */
    static BiConsumer<GameContext, List<ForwardTarget>> revealedForwardPowerDamage() {
        return (ctx, ts) -> {
            int power = ctx.revealedForwardPower();
            if (power <= 0) {
                ctx.logEntry("Effect: the revealed Forward has no power — no damage dealt");
                return;
            }
            ctx.logEntry("Effect: Deal it " + power + " damage (revealed Forward's power)");
            sortedByIdxDesc(ts, true) .forEach(ft -> ctx.damageTarget(ft, power));
            sortedByIdxDesc(ts, false).forEach(ft -> ctx.damageTarget(ft, power));
        };
    }

    /**
     * "If its cost is equal to the cost of the card discarded by the extra cost, break it and draw
     * N card(s)." — 24-065H Fenrir. Nothing happens on an unpaid extra cost: the discarded cost
     * reads back as 0, and a Forward of cost 0 is not a card that exists.
     */
    static BiConsumer<GameContext, List<ForwardTarget>> extraCostCostMatchBreakDraw(int draw) {
        return (ctx, ts) -> {
            int discardCost = ctx.extraCostDiscardedCardCost();
            if (discardCost <= 0) {
                ctx.logEntry("Effect: no card discarded for the extra cost — nothing to match");
                return;
            }
            for (ForwardTarget ft : new ArrayList<>(ts)) {
                CardData fwd = ft.isP1() ? ctx.p1Forward(ft.idx()) : ctx.p2Forward(ft.idx());
                if (fwd == null) continue;
                if (fwd.cost() != discardCost) {
                    ctx.logEntry("Effect: " + fwd.name() + " costs " + fwd.cost()
                            + ", discarded card cost " + discardCost + " — no match");
                    continue;
                }
                ctx.breakTarget(ft);
                ctx.drawCards(draw);
            }
        };
    }

    static BiConsumer<GameContext, List<ForwardTarget>> parseChosenTargetsAction(String clause) {
        String t = clause.trim();

        if (CHOSEN_TARGETS_BREAK.matcher(t).matches())
            return (ctx, ts) -> {
                sortedByIdxDesc(ts, true) .forEach(ctx::breakTarget);
                sortedByIdxDesc(ts, false).forEach(ctx::breakTarget);
            };

        if (CHOSEN_TARGETS_REMOVE_FROM_GAME.matcher(t).matches())
            return (ctx, ts) -> {
                sortedByIdxDesc(ts, true) .forEach(ctx::removeTargetFromGame);
                sortedByIdxDesc(ts, false).forEach(ctx::removeTargetFromGame);
            };

        Matcher dmg = CHOSEN_TARGETS_DEAL_DAMAGE.matcher(t);
        if (dmg.matches()) {
            int amount = Integer.parseInt(dmg.group("amount"));
            return (ctx, ts) -> {
                sortedByIdxDesc(ts, true) .forEach(x -> ctx.damageTarget(x, amount));
                sortedByIdxDesc(ts, false).forEach(x -> ctx.damageTarget(x, amount));
            };
        }
        return null;
    }

    /**
     * Parses an action-text string (a followup without target-selection) into a
     * {@code BiConsumer} that applies the action to an already-selected target list.
     * Returns {@code null} if the text is not recognised.
     * Handles: Freeze, Dull+Freeze, Break, Return-to-hand (+draw), Reduce power,
     * and "Deal N damage for each [Category X] Type you control".
     */
    static BiConsumer<GameContext, List<ForwardTarget>>
            parseTargetAction(String text, int xValue) {
        String t = text.trim();

        // Dull+Freeze must precede plain Freeze (Freeze matches as a substring)
        if (FOLLOWUP_DULL_AND_FREEZE.matcher(t).find())
            return (ctx, ts) -> {
                sortedByIdxDesc(ts, true) .forEach(ctx::dullAndFreezeTarget);
                sortedByIdxDesc(ts, false).forEach(ctx::dullAndFreezeTarget);
            };

        if (FOLLOWUP_FREEZE.matcher(t).find())
            return (ctx, ts) -> {
                sortedByIdxDesc(ts, true) .forEach(ctx::freezeTarget);
                sortedByIdxDesc(ts, false).forEach(ctx::freezeTarget);
            };

        // Break + draw must precede plain break (the draw extends the break text), the same
        // ordering the return pair below needs and for the same reason.
        Matcher breakDrawM = FOLLOWUP_BREAK_AND_DRAW.matcher(t);
        if (breakDrawM.find()) {
            int draws = Integer.parseInt(breakDrawM.group("draw"));
            return (ctx, ts) -> {
                sortedByIdxDesc(ts, true) .forEach(ctx::breakTarget);
                sortedByIdxDesc(ts, false).forEach(ctx::breakTarget);
                ctx.drawCards(draws);
            };
        }

        if (FOLLOWUP_BREAK.matcher(t).find() || FOLLOWUP_BREAK_DEMONSTRATIVE.matcher(t).matches()
                || FOLLOWUP_BREAK_CHOSEN.matcher(t).matches())
            return (ctx, ts) -> {
                sortedByIdxDesc(ts, true) .forEach(ctx::breakTarget);
                sortedByIdxDesc(ts, false).forEach(ctx::breakTarget);
            };

        if (FOLLOWUP_ACTIVATE.matcher(t).find())
            return (ctx, ts) -> {
                sortedByIdxDesc(ts, true) .forEach(ctx::activateTarget);
                sortedByIdxDesc(ts, false).forEach(ctx::activateTarget);
            };

        Matcher elemBecomesM = FOLLOWUP_ELEMENT_BECOMES.matcher(t);
        if (elemBecomesM.matches()) {
            String newElement = elemBecomesM.group("element");
            return (ctx, ts) -> ts.forEach(ft -> ctx.setTargetElement(ft, newElement));
        }

        // Return + draw must precede plain return (draw extends the return text)
        Matcher retDrawM = FOLLOWUP_RETURN_AND_DRAW.matcher(t);
        if (retDrawM.find()) {
            int draws = Integer.parseInt(retDrawM.group("draw"));
            return (ctx, ts) -> {
                for (ForwardTarget ft : ts) {
                    if (ft.zone() != ForwardTarget.CardZone.FORWARD) continue;
                    if (ft.isP1()) ctx.returnP1ForwardToHand(ft.idx());
                    else           ctx.returnP2ForwardToHand(ft.idx());
                }
                ctx.drawCards(draws);
            };
        }

        // Dispatched by zone rather than skipping everything that is not a Forward. The selections
        // that reach here are not Forward-only: 14-102L Leviathan, Lord of the Whorl chooses a
        // Forward, a Backup and a Monster and returns all three, and the Forward-only reading
        // chose the Backup and the Monster and then left them on the board.
        if (FOLLOWUP_RETURN_TO_OWNERS_HAND.matcher(t).find())
            return ActionResolver::returnTargetsToOwnersHand;

        // Bury a chosen Break Zone card at the bottom of the ability user's deck. Reached through
        // the "If you control N or more …" gate (24-094C Corsair), which is why it lives here
        // rather than only as a followup branch in ActionResolverChoose.
        if (FOLLOWUP_PUT_BOTTOM_OF_YOUR_DECK.matcher(t).find())
            return (ctx, ts) -> {
                sortedByIdxDesc(ts, true) .forEach(ctx::putBreakZoneTargetOnBottomOfDeck);
                sortedByIdxDesc(ts, false).forEach(ctx::putBreakZoneTargetOnBottomOfDeck);
            };

        // Power reduce — both word orders
        Matcher reduceM = FOLLOWUP_POWER_REDUCE.matcher(t);
        if (reduceM.find()) {
            int reduction = reduceM.group(1) != null ? Integer.parseInt(reduceM.group(1)) : 0;
            EnumSet<CardData.Trait> traits = parseTraits(reduceM.group(2));
            return (ctx, ts) -> {
                sortedByIdxDesc(ts, true) .forEach(ft -> ctx.reduceTarget(ft, reduction, traits));
                sortedByIdxDesc(ts, false).forEach(ft -> ctx.reduceTarget(ft, reduction, traits));
            };
        }
        // Power reduce for each [state] [element] [type] you control / opponent controls
        // (must precede plain reduce-until). Self-side state adjectives fall through — see
        // reduceForEachSelfState.
        Matcher reduceForEachM = FOLLOWUP_POWER_REDUCE_UNTIL_FOR_EACH.matcher(t);
        if (reduceForEachM.find() && !reduceForEachSelfState(reduceForEachM)) {
            boolean untilPrefix = reduceForEachM.group("amount") != null;
            int    perUnit = Integer.parseInt(untilPrefix ? reduceForEachM.group("amount") : reduceForEachM.group("amount2"));
            String srcElem = untilPrefix ? reduceForEachM.group("element") : reduceForEachM.group("element2");
            String srcState = untilPrefix ? reduceForEachM.group("state")  : reduceForEachM.group("state2");
            boolean srcOpp  = (untilPrefix ? reduceForEachM.group("opp")   : reduceForEachM.group("opp2")) != null;
            String srcType = (untilPrefix ? reduceForEachM.group("chartype") : reduceForEachM.group("chartype2")).toLowerCase();
            boolean cntFwd = srcType.startsWith("forward") || srcType.startsWith("character");
            boolean cntBkp = srcType.startsWith("backup")  || srcType.startsWith("character");
            boolean cntMon = srcType.startsWith("monster")  || srcType.startsWith("character");
            return (ctx, ts) -> {
                int n = countForEachPowerSource(ctx, srcOpp, srcState, srcElem, cntFwd, cntBkp, cntMon);
                int reduction = perUnit * n;
                EnumSet<CardData.Trait> noTraits = EnumSet.noneOf(CardData.Trait.class);
                sortedByIdxDesc(ts, true) .forEach(ft -> ctx.reduceTarget(ft, reduction, noTraits));
                sortedByIdxDesc(ts, false).forEach(ft -> ctx.reduceTarget(ft, reduction, noTraits));
            };
        }
        Matcher reduceUntilM = FOLLOWUP_POWER_REDUCE_UNTIL.matcher(t);
        if (reduceUntilM.find()) {
            int reduction = reduceUntilM.group(1) != null ? Integer.parseInt(reduceUntilM.group(1)) : 0;
            EnumSet<CardData.Trait> traits = parseTraits(reduceUntilM.group(2));
            return (ctx, ts) -> {
                sortedByIdxDesc(ts, true) .forEach(ft -> ctx.reduceTarget(ft, reduction, traits));
                sortedByIdxDesc(ts, false).forEach(ft -> ctx.reduceTarget(ft, reduction, traits));
            };
        }
        // Bare power reduce with no timing qualifier — used in former/latter splits (implied EOT)
        Matcher reduceBareM = FOLLOWUP_POWER_REDUCE_BARE.matcher(t);
        if (reduceBareM.find()) {
            int reduction = Integer.parseInt(reduceBareM.group(1));
            EnumSet<CardData.Trait> noTraits = EnumSet.noneOf(CardData.Trait.class);
            return (ctx, ts) -> {
                sortedByIdxDesc(ts, true) .forEach(ft -> ctx.reduceTarget(ft, reduction, noTraits));
                sortedByIdxDesc(ts, false).forEach(ft -> ctx.reduceTarget(ft, reduction, noTraits));
            };
        }

        // Until EOT, it also becomes a Forward with N power
        Matcher becomeForwardM = BECOME_FORWARD_UNTIL_EOT_PATTERN.matcher(t);
        if (becomeForwardM.find()) {
            int power = Integer.parseInt(becomeForwardM.group("power"));
            return (ctx, ts) -> ts.forEach(ft -> ctx.makeTargetTemporaryForward(ft, power));
        }

        // Place N [Name] Counter(s) on it
        Matcher placeCounterM = FOLLOWUP_PLACE_COUNTER_ON_IT.matcher(t);
        if (placeCounterM.find()) {
            int    count       = Integer.parseInt(placeCounterM.group("count"));
            String counterName = placeCounterM.group("name").trim();
            return (ctx, ts) -> {
                for (ForwardTarget ft : ts) {
                    CardData card = ft.isP1() ? ctx.p1Forward(ft.idx()) : ctx.p2Forward(ft.idx());
                    ctx.placeCounters(card, counterName, count);
                }
            };
        }

        // Select and remove one counter from the chosen character (dialog if multiple types)
        if (FOLLOWUP_REMOVE_ONE_COUNTER.matcher(t).find()) {
            return (ctx, ts) -> ts.forEach(ctx::removeOneCounterFromTarget);
        }

        // Deal N damage [and/minus M [more] damage] for each/every M [Category X] [Element] Type [of cost N] you control
        Matcher forEachM = FOLLOWUP_DAMAGE_FOR_EACH.matcher(t);
        if (forEachM.find() && forEachM.group("chartype") != null) {
            int    baseDmg  = Integer.parseInt(forEachM.group("base"));
            String perStr   = forEachM.group("per");
            int    perDmg   = perStr != null ? Integer.parseInt(perStr) : 0;
            boolean subtract = "minus".equalsIgnoreCase(forEachM.group("op"));
            // "for every N" counts groups of N, rounding down; "for each" is group size 1.
            int    groupSize = forEachM.group("group") != null ? Integer.parseInt(forEachM.group("group")) : 1;
            String charType = forEachM.group("chartype");
            String category = forEachM.group("category") != null ? forEachM.group("category").trim() : null;
            String element  = forEachM.group("element") != null ? forEachM.group("element").toLowerCase(Locale.ROOT) : null;
            int    costFilter = forEachM.group("costfilter") != null ? Integer.parseInt(forEachM.group("costfilter")) : -1;
            boolean fwd = charType.matches("(?i)Forwards?|Characters?");
            boolean bkp = charType.matches("(?i)Backups?|Characters?");
            boolean mon = charType.matches("(?i)Monsters?|Characters?");
            return (ctx, ts) -> {
                int units = ctx.countSelfFieldCards(fwd, bkp, mon, null, null, category, element, costFilter) / groupSize;
                int damage = perDmg > 0
                        ? (subtract ? Math.max(0, baseDmg - perDmg * units) : baseDmg + perDmg * units)
                        : baseDmg * units;
                sortedByIdxDesc(ts, true) .forEach(ft -> ctx.damageTarget(ft, damage));
                sortedByIdxDesc(ts, false).forEach(ft -> ctx.damageTarget(ft, damage));
            };
        }

        // Plain dull, last of all. Everything above already answers for the sentences that pair
        // dulling with something else — "dull it and Freeze it" is read at the top of this method
        // — so this only ever sees the bare imperative, and putting it here rather than beside its
        // Freeze sibling is what keeps it from claiming a longer sentence off the "dull it" in its
        // middle. 4-035R Cid Randell prints the demonstrative form, which is matched whole.
        if (FOLLOWUP_DULL.matcher(t).find() || FOLLOWUP_DULL_DEMONSTRATIVE.matcher(t).matches())
            return (ctx, ts) -> {
                sortedByIdxDesc(ts, true) .forEach(ctx::dullTarget);
                sortedByIdxDesc(ts, false).forEach(ctx::dullTarget);
            };

        return null;
    }

    static int resolveInsteadDamage(GameContext ctx, ForwardTarget t,
            DamageInsteadCondition cond, int base, int alt) {
        boolean condMet = switch (cond) {
            case DamageInsteadCondition.TargetIsActive() ->
                (t.isP1() ? ctx.p1ForwardState(t.idx()) : ctx.p2ForwardState(t.idx())) == CardState.ACTIVE;
            case DamageInsteadCondition.TargetIsMultiElement() ->
                ctx.fieldCardHasElement(t.isP1() ? ctx.p1Forward(t.idx()) : ctx.p2Forward(t.idx()), "Multi-Element");
            default -> insteadConditionMet(ctx, cond);
        };
        return condMet ? alt : base;
    }

    /**
     * Evaluates a {@link DamageInsteadCondition} that does not depend on a specific target
     * (i.e. every variant except {@code TargetIsActive}/{@code TargetIsMultiElement}, which
     * require a {@link ForwardTarget} and must go through {@link #resolveInsteadDamage}).
     */
    static boolean insteadConditionMet(GameContext ctx, DamageInsteadCondition cond) {
        return switch (cond) {
            case DamageInsteadCondition.TargetIsActive() ->
                throw new IllegalArgumentException("TargetIsActive requires resolveInsteadDamage(ctx, target, ...)");
            case DamageInsteadCondition.TargetIsMultiElement() ->
                throw new IllegalArgumentException("TargetIsMultiElement requires resolveInsteadDamage(ctx, target, ...)");
            case DamageInsteadCondition.YouControl(ControlCondition cc, String excludeName) ->
                excludeName != null ? ctx.controlConditionMetExcluding(cc, excludeName) : ctx.controlConditionMet(cc);
            case DamageInsteadCondition.YouReceivedDamageThisTurn() ->
                ctx.selfReceivedDamageThisTurn();
            case DamageInsteadCondition.YouReceivedDamageAtLeast(int min) ->
                ctx.selfDamageCount() >= min;
            case DamageInsteadCondition.YouHaveSummonInBreakZone() ->
                ctx.selfHasSummonInBreakZone();
            case DamageInsteadCondition.OpponentDamageAtLeast(int min) ->
                ctx.opponentDamageCount() >= min;
            case DamageInsteadCondition.OpponentHandAtMost(int max) ->
                ctx.opponentHandSize() <= max;
            case DamageInsteadCondition.YouCastAtLeast(int min) ->
                ctx.selfCardsCastThisTurn() >= min;
            case DamageInsteadCondition.OpponentHasMoreForwards() ->
                ctx.opponentForwardCount() > ctx.selfForwardCount();
            case DamageInsteadCondition.IsExBurst() ->
                ctx.isExBurst();
        };
    }

    // -------------------------------------------------------------------------
    // Choose-character effect parser
    // -------------------------------------------------------------------------

    // -------------------------------------------------------------------------
    // Former/Latter dual-selection parser
    // -------------------------------------------------------------------------

    record TargetDesc(
            boolean fwd, boolean bkp, boolean mon,
            boolean opponentOnly, boolean selfOnly,
            String condition, String element,
            int costVal, String costCmp,
            String excludeName,
            boolean fromBreakZone, boolean opponentBz) {}

    static TargetDesc parseTargetDesc(String desc) {
        Matcher m = TARGET_DESC_PATTERN.matcher(desc.trim());
        if (!m.matches()) return null;

        String ct = m.group("cardtype").toLowerCase(Locale.ROOT);
        boolean fwd = ct.startsWith("forward") || ct.startsWith("character");
        boolean bkp = ct.startsWith("backup")  || ct.startsWith("character");
        boolean mon = ct.startsWith("monster") || ct.startsWith("character");

        String control      = m.group("control");
        boolean opponentOnly = control != null && control.toLowerCase(Locale.ROOT).contains("opponent");
        boolean selfOnly     = control != null && control.toLowerCase(Locale.ROOT).contains("you control");

        int    costVal = m.group("cost") != null ? Integer.parseInt(m.group("cost")) : -1;
        String costCmp = m.group("costcmp");

        String  zone       = m.group("zone");
        boolean fromBz     = zone != null;
        boolean opponentBz = fromBz && zone.toLowerCase(Locale.ROOT).contains("opponent");

        return new TargetDesc(fwd, bkp, mon, opponentOnly, selfOnly,
                m.group("condition"), m.group("element"),
                costVal, costCmp, m.group("excludename"),
                fromBz, opponentBz);
    }

    static BiConsumer<GameContext, List<ForwardTarget>>
            parseFormerLatterGroupAction(String text) {
        String t = text.trim();

        // "Play it onto the field dull" must precede plain "Play it onto the field"
        if (FOLLOWUP_PLAY_ONTO_FIELD_DULL.matcher(t).find())
            return (ctx, ts) -> {
                sortedByIdxDesc(ts, true) .forEach(ctx::playTargetOntoFieldDull);
                sortedByIdxDesc(ts, false).forEach(ctx::playTargetOntoFieldDull);
            };

        if (FOLLOWUP_PLAY_ONTO_FIELD.matcher(t).find())
            return (ctx, ts) -> {
                sortedByIdxDesc(ts, true) .forEach(ctx::playTargetOntoField);
                sortedByIdxDesc(ts, false).forEach(ctx::playTargetOntoField);
            };

        // "Dull or Freeze it" — compact form must precede plain FOLLOWUP_DULL
        if (FOLLOWUP_DULL_OR_FREEZE_COMPACT.matcher(t).find())
            return (ctx, ts) -> {
                sortedByIdxDesc(ts, true) .forEach(ctx::dullOrFreezeTarget);
                sortedByIdxDesc(ts, false).forEach(ctx::dullOrFreezeTarget);
            };

        if (FOLLOWUP_DULL.matcher(t).find())
            return (ctx, ts) -> {
                sortedByIdxDesc(ts, true) .forEach(ctx::dullTarget);
                sortedByIdxDesc(ts, false).forEach(ctx::dullTarget);
            };

        // Power boost variants (UNTIL must precede plain BOOST since text may omit the trailing "until")
        Matcher boostUntilM = FOLLOWUP_POWER_BOOST_UNTIL.matcher(t);
        if (boostUntilM.find()) {
            int boost = Integer.parseInt(boostUntilM.group(1));
            EnumSet<CardData.Trait> traits = parseTraits(boostUntilM.group(2));
            return (ctx, ts) -> {
                sortedByIdxDesc(ts, true) .forEach(ft -> ctx.boostTarget(ft, boost, traits));
                sortedByIdxDesc(ts, false).forEach(ft -> ctx.boostTarget(ft, boost, traits));
            };
        }

        Matcher boostM = FOLLOWUP_POWER_BOOST.matcher(t);
        if (boostM.find()) {
            int boost = Integer.parseInt(boostM.group(1));
            EnumSet<CardData.Trait> traits = parseTraits(boostM.group(2));
            return (ctx, ts) -> {
                sortedByIdxDesc(ts, true) .forEach(ft -> ctx.boostTarget(ft, boost, traits));
                sortedByIdxDesc(ts, false).forEach(ft -> ctx.boostTarget(ft, boost, traits));
            };
        }

        // "If its cost equals the cost of the card discarded by the extra cost, break it and draw N" (Fenrir)
        Matcher fenrirM = FOLLOWUP_IF_COST_EQUALS_DISCARD_BREAK_DRAW.matcher(t);
        if (fenrirM.find())
            return extraCostCostMatchBreakDraw(Integer.parseInt(fenrirM.group("draw")));

        // "Deal it damage equal to the power of the Forward removed by the extra cost" (Titan)
        if (FOLLOWUP_DAMAGE_EXTRA_COST_POWER.matcher(t).find())
            return extraCostPowerDamage();

        // "Deal it N damage" — check for a "If you have cast Card Name X other than X this turn" bonus
        Matcher dmgM = FOLLOWUP_DAMAGE.matcher(t);
        if (dmgM.find()) {
            int damage = Integer.parseInt(dmgM.group("amount"));
            Consumer<GameContext> bonus = parseCardNameCastOtherBonusEffect(t.substring(dmgM.end()));
            return (ctx, ts) -> {
                sortedByIdxDesc(ts, true) .forEach(ft -> ctx.damageTarget(ft, damage));
                sortedByIdxDesc(ts, false).forEach(ft -> ctx.damageTarget(ft, damage));
                if (bonus != null) bonus.accept(ctx);
            };
        }

        return parseTargetAction(t, 0);
    }

    private static Consumer<GameContext> parseCardNameCastOtherBonusEffect(String suffix) {
        if (suffix == null || suffix.isBlank()) return null;
        Matcher m = CAST_CARD_NAME_OTHER_BONUS.matcher(suffix.trim());
        if (!m.find()) return null;
        String cardName  = m.group("name").trim();
        String bonusText = m.group("effect").trim().replaceAll("\\.$", "");
        Consumer<GameContext> bonusEffect = parse(bonusText, null);
        if (bonusEffect == null) return null;
        return ctx -> {
            if (ctx.countCardsNamedCastThisTurn(cardName) > 1)
                bonusEffect.accept(ctx);
        };
    }

    static String getTargetCardName(GameContext ctx, ForwardTarget t) {
        if (t.zone() == ForwardTarget.CardZone.FORWARD)
            return (t.isP1() ? ctx.p1Forward(t.idx()) : ctx.p2Forward(t.idx())).name();
        return null;
    }

    /**
     * True when a "choose …" effect's followup only benefits the cards it picks, so an AI
     * controller should aim it at its own side.  A deliberately conservative heuristic: it must
     * match a known buff wording and contain no harmful verb, otherwise the existing
     * prefer-the-opponent behaviour stands.
     */
    static boolean chooseEffectBenefitsTarget(String effectText) {
        if (effectText == null) return false;
        if (CHOOSE_FOLLOWUP_HARMS_TARGET.matcher(effectText).find()) return false;
        return CHOOSE_FOLLOWUP_BENEFITS_TARGET.matcher(effectText).find();
    }

    /**
     * Wraps a "choose …" effect so an AI controller prefers its own cards when the effect is a
     * pure buff.  The flag is advisory and is read only by the AI's auto-selection branch; a
     * human player still picks freely.
     */
    private static Consumer<GameContext> withAiTargetPreference(String effectText, Consumer<GameContext> fn) {
        if (!chooseEffectBenefitsTarget(effectText)) return fn;
        return ctx -> {
            ctx.setAiPrefersOwnTargets(true);
            fn.accept(ctx);
        };
    }

    /** Returns targets belonging to {@code isP1} sorted by descending index (safe for list removal). */
    static java.util.stream.Stream<ForwardTarget> sortedByIdxDesc(
            List<ForwardTarget> targets, boolean isP1) {
        return targets.stream()
                .filter(t -> t.isP1() == isP1)
                .sorted((a, b) -> Integer.compare(b.idx(), a.idx()));
    }

    /**
     * Returns {@code t} to its owner's hand, dispatching by zone so a Monster or Backup that has
     * become a Forward this turn is returned from its actual zone rather than being silently skipped.
     */
    private static void returnTargetToOwnersHand(GameContext ctx, ForwardTarget t) {
        switch (t.zone()) {
            case FORWARD -> { if (t.isP1()) ctx.returnP1ForwardToHand(t.idx()); else ctx.returnP2ForwardToHand(t.idx()); }
            case MONSTER -> { if (t.isP1()) ctx.returnP1MonsterToHand(t.idx()); else ctx.returnP2MonsterToHand(t.idx()); }
            case BACKUP  -> { if (t.isP1()) ctx.returnP1BackupToHand(t.idx());  else ctx.returnP2BackupToHand(t.idx()); }
        }
    }

    /**
     * Returns every target in {@code ts} to its owner's hand. Cards are processed highest-index-first
     * within each side, because returning one card compacts its zone list and would otherwise
     * invalidate a later same-zone target's index (so a second same-controller card is missed).
     */
    static void returnTargetsToOwnersHand(GameContext ctx, List<ForwardTarget> ts) {
        sortedByIdxDesc(ts, true) .forEach(t -> returnTargetToOwnersHand(ctx, t));
        sortedByIdxDesc(ts, false).forEach(t -> returnTargetToOwnersHand(ctx, t));
    }

    /** Deals {@code amount} damage to {@code t}, bypassing reduction effects when {@code unreduced}. */
    static void damageTargetMaybeUnreduced(GameContext ctx, ForwardTarget t, int amount, boolean unreduced) {
        if (unreduced) ctx.damageTargetUnreduced(t, amount);
        else           ctx.damageTarget(t, amount);
    }

    /**
     * Splits {@code damage} evenly across {@code count} targets, rounding each target's share
     * up to the nearest 1000 (per official card rulings, e.g. "divide 12000 damage equally...
     * round up to the nearest 1000") — the total dealt may exceed {@code damage} when it doesn't
     * divide evenly.
     */
    static int roundUpToThousand(int damage, int count) {
        if (count <= 0) return 0;
        return ((damage + count * 1000 - 1) / (count * 1000)) * 1000;
    }

    /** Builds a log suffix like " — Gain +1000 power, Haste, and First Strike until end of turn". */
    static String boostLogSuffix(int amount, EnumSet<CardData.Trait> traits) {
        List<String> parts = new ArrayList<>();
        if (amount != 0)                                  parts.add("+" + amount + " power");
        if (traits.contains(CardData.Trait.HASTE))        parts.add("Haste");
        if (traits.contains(CardData.Trait.FIRST_STRIKE)) parts.add("First Strike");
        if (traits.contains(CardData.Trait.BRAVE))        parts.add("Brave");
        StringBuilder sb = new StringBuilder(" — Gain ");
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                if (parts.size() == 2)            sb.append(" and ");
                else if (i == parts.size() - 1)   sb.append(", and ");
                else                              sb.append(", ");
            }
            sb.append(parts.get(i));
        }
        sb.append(" until end of turn");
        return sb.toString();
    }

    /**
     * Replaces literal periods in {@code source}'s name with the middle-dot character (·) so that
     * lazy regex quantifiers inside CHOOSE_CHARACTER_PATTERN do not mistake a mid-name period-space
     * sequence (e.g. "Dr. Mog") for the sentence delimiter ". ".  Restore with
     * {@link #restorePeriodInName}.
     */
    /**
     * True when a {@link ActionResolverPatterns#FOLLOWUP_POWER_REDUCE_UNTIL_FOR_EACH} match asks
     * to count a card state on the ability user's own side.
     *
     * <p>{@link GameContext} exposes a state-filtered count for the opponent's field only
     * ({@code countOppFieldCardsWithCondition}), so reading such a match on the self side would
     * drop the adjective and count every card of the type instead. No printing needs it, so the
     * two handlers skip the branch rather than the surface growing a self-side twin.
     */
    static boolean reduceForEachSelfState(Matcher m) {
        boolean untilPrefix = m.group("amount") != null;
        String  state = untilPrefix ? m.group("state") : m.group("state2");
        boolean opp   = (untilPrefix ? m.group("opp") : m.group("opp2")) != null;
        return state != null && !opp;
    }

    /**
     * Counts the "for each …" source of a per-unit power followup on the requested side. The
     * element filter and the state filter never co-occur in print, which is why the opponent
     * branch picks one call or the other rather than combining them.
     */
    static int countForEachPowerSource(GameContext ctx, boolean opp, String state, String element,
            boolean inclFwd, boolean inclBkp, boolean inclMon) {
        if (!opp) return ctx.countSelfFieldCards(inclFwd, inclBkp, inclMon, null, null, null, element);
        return state != null
                ? ctx.countOppFieldCardsWithCondition(inclFwd, inclBkp, inclMon, state.toLowerCase(Locale.ROOT))
                : ctx.countOppFieldCards(inclFwd, inclBkp, inclMon, null, null, null, element);
    }

    static String escapePeriodInName(String text, CardData source) {
        if (source == null || !source.name().contains(".")) return text;
        return text.replace(source.name(), source.name().replace('.', '·'));
    }

    /** Inverse of {@link #escapePeriodInName}: restores middle-dots back to periods. */
    static String restorePeriodInName(String text, CardData source) {
        if (source == null || !source.name().contains(".")) return text;
        return text.replace(source.name().replace('.', '·'), source.name());
    }

    /**
     * Removes any trailing/embedded restriction-only sentences already captured as boolean flags
     * (once-per-turn, main-phase-only, your-turn-only, while-attacking, etc.) from {@code text},
     * then strips leftover leading/trailing punctuation.  Returns an empty string if nothing
     * remains after stripping.
     */
    static String stripRestrictionSentences(String text) {
        if (text == null || text.isBlank()) return "";
        String s = text;
        s = CardData.ONCE_PER_TURN_PATTERN               .matcher(s).replaceAll("").trim();
        // Strip the combined "during your Main Phase and if X is in the Break Zone" form before
        // MAIN_PHASE_ONLY_PATTERN so the whole sentence is removed as a unit rather than leaving
        // "and if X is in the Break Zone." as an unparsed secondary fragment.
        s = CardData.OWN_BZ_NAME_REQUIRED_RESTRICTION  .matcher(s).replaceAll("").trim();
        // Same for the "during your turn and if X is in the Break Zone" combined form (Chaos),
        // which must go before YOUR_TURN_ONLY_PATTERN for the same reason.
        s = CardData.YOUR_TURN_AND_BZ_RESTRICTION      .matcher(s).replaceAll("").trim();
        s = CardData.MAIN_PHASE_ONLY_PATTERN              .matcher(s).replaceAll("").trim();
        // Captured as a ControlCondition on the ability, so it must not be left in the effect text
        // — 23-053R Meteion's trailing draw is stranded behind it otherwise.
        s = CardData.CONTROL_IF_NEITHER_PLAYER_PATTERN    .matcher(s).replaceAll("").trim();
        s = CardData.YOUR_TURN_AND_CONTROL_IF_PATTERN    .matcher(s).replaceAll("").trim();
        // Strip "during your turn and if X is in your hand" before YOUR_TURN_ONLY_PATTERN so the
        // whole sentence is removed as a unit rather than leaving "and if X is in your hand." as a fragment.
        s = CardData.WHILE_CARD_IN_HAND_PATTERN           .matcher(s).replaceAll("").trim();
        s = CardData.YOUR_TURN_ONLY_PATTERN               .matcher(s).replaceAll("").trim();
        s = CardData.OPP_TURN_ONLY_PATTERN                .matcher(s).replaceAll("").trim();
        s = CardData.OPP_NO_CARDS_IN_HAND_RESTRICTION     .matcher(s).replaceAll("").trim();
        s = CardData.WHILE_PARTY_ATTACKING_PATTERN.matcher(s).replaceAll("").trim();
        s = CardData.WHILE_CARD_ATTACKING_PATTERN .matcher(s).replaceAll("").trim();
        s = CardData.WHILE_CARD_BLOCKING_PATTERN  .matcher(s).replaceAll("").trim();
        s = CardData.WHILE_CARD_IN_HAND_PATTERN   .matcher(s).replaceAll("").trim();
        s = CardData.SOURCE_IN_BATTLE_PATTERN     .matcher(s).replaceAll("").trim();
        s = CardData.OPP_DISCARD_THIS_TURN_PATTERN .matcher(s).replaceAll("").trim();
        s = CardData.CAST_SUMMON_THIS_TURN_PATTERN .matcher(s).replaceAll("").trim();
        s = CardData.OWN_DAMAGE_THRESHOLD_RESTRICTION.matcher(s).replaceAll("").trim();
        s = CardData.SELF_POWER_AT_LEAST_RESTRICTION .matcher(s).replaceAll("").trim();
        s = CardData.NAMED_CARD_TOOK_DAMAGE_THIS_TURN_RESTRICTION.matcher(s).replaceAll("").trim();
        s = CardData.SELF_RECEIVED_DAMAGE_THIS_TURN_RESTRICTION   .matcher(s).replaceAll("").trim();
        s = CardData.FORWARD_PUT_TO_BZ_THIS_TURN_RESTRICTION      .matcher(s).replaceAll("").trim();
        s = CardData.JOB_PUT_TO_BZ_THIS_TURN_RESTRICTION          .matcher(s).replaceAll("").trim();
        s = CardData.ELEMENT_FORWARD_ENTERED_THIS_TURN_PATTERN.matcher(s).replaceAll("").trim();
        s = CardData.COUNTER_MINIMUM_RESTRICTION              .matcher(s).replaceAll("").trim();
        s = CardData.OPP_HAND_AT_MOST_RESTRICTION             .matcher(s).replaceAll("").trim();
        s = CardData.SELF_NO_CARDS_IN_HAND_RESTRICTION        .matcher(s).replaceAll("").trim();
        s = CardData.CP_BACKUP_ONLY_ABILITY                   .matcher(s).replaceAll("").trim();
        s = CardData.CP_ELEMENTS_ONLY_ABILITY                 .matcher(s).replaceAll("").trim();
        s = CardData.CONTROL_IF_PATTERN                    .matcher(s).replaceAll("").trim();
        s = CardData.CONTROL_IF_NOT_ANY_PATTERN            .matcher(s).replaceAll("").trim();
        s = CardData.OPPONENT_CONTROLS_N_OR_MORE_PATTERN   .matcher(s).replaceAll("").trim();
        s = CardData.COUNTER_ZERO_RESTRICTION              .matcher(s).replaceAll("").trim();
        s = CardData.EACH_PLAYER_CAN_USE_PATTERN           .matcher(s).replaceAll("").trim();
        // Boilerplate divide-damage rounding clarification — restates a fixed game rule
        // (damage is always allocated in increments of 1000), carries no extra info to describe.
        s = DAMAGE_INCREMENT_CLARIFICATION.matcher(s).replaceAll("").trim();
        // Strip leftover leading/trailing ", and" / "," / "." artifacts
        s = s.replaceAll("^[,.;\\s]+|[,.;\\s]+$", "").trim();
        return s;
    }

    static String traitNamesOnly(EnumSet<CardData.Trait> traits) {
        List<String> names = new ArrayList<>();
        if (traits.contains(CardData.Trait.HASTE))        names.add("Haste");
        if (traits.contains(CardData.Trait.FIRST_STRIKE)) names.add("First Strike");
        if (traits.contains(CardData.Trait.BRAVE))        names.add("Brave");
        return switch (names.size()) {
            case 0  -> "";
            case 1  -> names.get(0);
            case 2  -> names.get(0) + " and " + names.get(1);
            default -> names.get(0) + ", " + names.get(1) + ", and " + names.get(2);
        };
    }

    /** Parses a traits string (e.g. {@code ", Haste, and First Strike"}) into a set of traits. */
    static EnumSet<CardData.Trait> parseTraits(String traitStr) {
        EnumSet<CardData.Trait> traits = EnumSet.noneOf(CardData.Trait.class);
        if (traitStr == null || traitStr.isEmpty()) return traits;
        String s = traitStr.toLowerCase();
        if (s.contains("haste"))         traits.add(CardData.Trait.HASTE);
        if (s.contains("first strike"))  traits.add(CardData.Trait.FIRST_STRIKE);
        if (s.contains("brave"))         traits.add(CardData.Trait.BRAVE);
        return traits;
    }

    /**
     * Builds a log suffix like " — Lose 1000 power, Haste, and First Strike until end of turn".
     * Power and traits are listed in order; either may be absent.
     */
    static String reduceLogSuffix(int amount, EnumSet<CardData.Trait> traits) {
        List<String> parts = new ArrayList<>();
        if (amount > 0) parts.add(amount + " power");
        if (traits.contains(CardData.Trait.HASTE))        parts.add("Haste");
        if (traits.contains(CardData.Trait.FIRST_STRIKE)) parts.add("First Strike");
        if (traits.contains(CardData.Trait.BRAVE))        parts.add("Brave");
        StringBuilder sb = new StringBuilder(" — Lose ");
        if (parts.size() == 1) {
            sb.append(parts.get(0));
        } else if (parts.size() == 2) {
            sb.append(parts.get(0)).append(" and ").append(parts.get(1));
        } else if (parts.size() >= 3) {
            for (int i = 0; i < parts.size() - 1; i++) sb.append(parts.get(i)).append(", ");
            sb.append("and ").append(parts.get(parts.size() - 1));
        }
        return sb.append(" until end of turn").toString();
    }

    // ---- Granted field abilities (self "gains \"…\" until the end of the turn") --------------------

    /**
     * Routes a quoted field-ability text (the contents of {@code "…"} in a "gains" grant) to the
     * primitive that applies it to {@code source} until end of turn. Returns {@code null} when the
     * quoted ability isn't a supported self-grant (letting other parsers try). The subject named
     * inside the quotes must be the source card.
     */
    /** The permitted attack count from a matched {@link ActionResolverPatterns#GRANTED_CAN_ATTACK_TWICE}. */
    private static int grantedAttackCount(Matcher m) {
        return m.group("count") != null ? Integer.parseInt(m.group("count")) : 2;
    }

    static Consumer<GameContext> grantedSelfFieldAbilityEffect(String quoted, CardData source) {
        if (source == null) return null;
        Matcher at = GRANTED_CAN_ATTACK_TWICE.matcher(quoted);
        if (at.matches() && at.group("subj").trim().equalsIgnoreCase(source.name())) {
            int max = grantedAttackCount(at);
            return ctx -> ctx.grantMaxAttacksUntilEndOfTurn(source, max);
        }
        Matcher nb = GRANTED_CANNOT_BE_BLOCKED_BY_COST.matcher(quoted);
        if (nb.matches() && nb.group("subj").trim().equalsIgnoreCase(source.name())) {
            int cost = Integer.parseInt(nb.group("cost"));
            boolean more = "more".equalsIgnoreCase(nb.group("cmp"));
            return ctx -> ctx.grantSelfCannotBeBlockedByCost(source, cost, more);
        }
        // Both are anchored on their own noun, so the order between them is free; kept adjacent
        // because the two sentences read alike and a reader looking for one will find the other.
        Matcher np = GRANTED_CANNOT_BE_BLOCKED_BY_POWER.matcher(quoted);
        if (np.matches() && np.group("subj").trim().equalsIgnoreCase(source.name())) {
            int power = Integer.parseInt(np.group("power"));
            boolean more = !"less".equalsIgnoreCase(np.group("cmp"));   // default "or more"
            return ctx -> ctx.grantSelfCannotBeBlockedByPower(source, power, more);
        }
        // Must follow GRANTED_CANNOT_BE_BLOCKED_BY_COST: both are anchored, but a "cannot be
        // blocked by …" text would only reach here on a wording that one does not cover, and
        // "cannot block" must not claim it.
        Matcher cb = GRANTED_CANNOT_BLOCK.matcher(quoted);
        if (cb.matches() && cb.group("subj").trim().equalsIgnoreCase(source.name()))
            return ctx -> ctx.grantSelfCannotBlockUntilEndOfTurn(source);
        if (exBurstSuppressionMaxCost(quoted, source.name()) != null)
            return ctx -> ctx.grantSelfExBurstSuppression(source);
        // "If [Self] deals damage to a Forward or your opponent, double the damage instead."
        // (Caius 18-108H). Granted verbatim — the damage paths already recognise this wording on a
        // printed field ability, and read granted ones through the same effective-abilities view.
        Matcher dd = AutoAbilityTriggers.FA_OUTGOING_DAMAGE_DOUBLER.matcher(quoted);
        if (dd.matches() && dd.group("card").trim().equalsIgnoreCase(source.name())) {
            final String granted = quoted;
            return ctx -> ctx.grantSelfFieldAbilityUntilEndOfTurn(source, granted);
        }
        // "If [Self] deals damage to your opponent, the damage becomes N instead."
        // (Ramada 17-125R, Cecil 15-073H, Fang 19-131S) — granted verbatim, same as the doubler.
        Matcher setTo = AutoAbilityTriggers.FA_OUTGOING_DAMAGE_TO_OPPONENT_SETS_TO.matcher(quoted);
        if (setTo.matches() && setTo.group("card").trim().equalsIgnoreCase(source.name())) {
            final String granted = quoted;
            return ctx -> ctx.grantSelfFieldAbilityUntilEndOfTurn(source, granted);
        }
        // The incoming-damage counterpart: "If [Self] is dealt damage <clause>, <modifier> instead."
        // Sarah (MOBIUS) 16-115H grants herself the "less than her power → 0" form, which several
        // other cards print outright (Y'shtola 12-119L, Barret 14-121L, Aymeric 6-106H). Granted
        // verbatim, and DamageResolver reads it off the effective view exactly as a printed one.
        // Checked last of the damage clauses: this pattern is the broad one of the family.
        Matcher inc = AutoAbilityTriggers.FA_DAMAGE_MODIFIER.matcher(quoted);
        if (inc.matches() && inc.group("card").trim().equalsIgnoreCase(source.name())) {
            final String granted = quoted;
            return ctx -> ctx.grantSelfFieldAbilityUntilEndOfTurn(source, granted);
        }
        return null;
    }

    /**
     * Returns the highest card cost whose EX Burst {@code text} suppresses when the damage is
     * credited to {@code sourceName}, or {@code null} when {@code text} is not a source-scoped
     * EX Burst suppression naming that card.  {@link Integer#MAX_VALUE} means "any cost".
     */
    static Integer exBurstSuppressionMaxCost(String text, String sourceName) {
        if (text == null || sourceName == null) return null;
        Matcher m = EX_BURST_SUPPRESSION_BY_SOURCE.matcher(text.trim());
        if (!m.matches()) return null;
        String subj = m.group("subj1") != null ? m.group("subj1") : m.group("subj2");
        if (subj == null || !subj.trim().equalsIgnoreCase(sourceName)) return null;
        String cost = m.group("cost1") != null ? m.group("cost1") : m.group("cost2");
        return cost == null ? Integer.MAX_VALUE : Integer.valueOf(cost);
    }

    /**
     * Builds the permanent counterpart of {@link #grantedSelfFieldAbilityEffect} for one quoted
     * clause, or {@code null} when the clause is not a grant this engine can apply.
     *
     * <p>A clause is either a complete "When … , …" auto ability — granted by parsing it exactly as
     * the card's own text is parsed — or the "can attack twice in the same turn" permission.
     */
    static Consumer<GameContext> permanentGrantForClause(String quoted, CardData source) {
        Matcher at = GRANTED_CAN_ATTACK_TWICE.matcher(quoted);
        if (at.matches() && at.group("subj").trim().equalsIgnoreCase(source.name())) {
            int max = grantedAttackCount(at);
            return ctx -> ctx.grantMaxAttacksPermanently(source, max);
        }
        // A trigger-bearing clause is granted whole; parseAutoAbilities is the authority on whether
        // it is one, so an unrecognised sentence declines here rather than being silently dropped.
        if (CardData.parseAutoAbilities(quoted).isEmpty()) return null;
        final String granted = quoted;
        return ctx -> ctx.grantSelfAutoAbilityPermanently(source, granted);
    }

    /**
     * The permanent counterpart of {@link #grantedSelfFieldAbilityEffect}: a quoted clause a card
     * hands <em>itself</em> for good, rather than for the turn.
     *
     * <p>Delegates to {@link #permanentGrantForClause} first, so the trigger-bearing and
     * attack-permission clauses keep the one implementation, then adds the field-ability texts
     * whose enforcement reads {@link MainWindow#effectiveFieldAbilities} — those can be granted
     * verbatim and are recognised on a granted copy exactly as on a printed one.
     *
     * <p>Returns {@code null} for anything else <em>on purpose</em>. A clause like Roche 29-076H's
     * "must attack once per turn if possible" has no field-ability reader at all — the must-attack
     * rule is driven off an index set filled by the choose chain — so granting it verbatim would
     * be silently inert, and declining leaves the ability visibly unparsed instead.
     */
    static Consumer<GameContext> permanentGrantForSelfClause(String quoted, CardData source) {
        if (source == null) return null;
        Consumer<GameContext> shared = permanentGrantForClause(quoted, source);
        if (shared != null) return shared;

        // "If [Self] deals damage to your opponent, the damage becomes N instead." (Hyoh 16-097H)
        // — read back by DamageResolver.outgoingDamageToOpponentOverride off the effective view.
        Matcher setTo = AutoAbilityTriggers.FA_OUTGOING_DAMAGE_TO_OPPONENT_SETS_TO.matcher(quoted);
        if (setTo.matches() && setTo.group("card").trim().equalsIgnoreCase(source.name())) {
            final String granted = quoted;
            return ctx -> ctx.grantSelfFieldAbilityPermanently(source, granted);
        }
        // The doubler sibling, for the same reason — kept in step with the end-of-turn version.
        Matcher dd = AutoAbilityTriggers.FA_OUTGOING_DAMAGE_DOUBLER.matcher(quoted);
        if (dd.matches() && dd.group("card").trim().equalsIgnoreCase(source.name())) {
            final String granted = quoted;
            return ctx -> ctx.grantSelfFieldAbilityPermanently(source, granted);
        }
        // "[Self] cannot be chosen by your opponent's Summons/abilities." (Young Excenmille
        // 23-100L). Not granted as field-ability text: the targeting rules read dedicated sets
        // rather than scanning abilities, so this routes to the permanent shield primitive.
        Matcher cbc = STANDALONE_NAMED_CANNOT_BE_CHOSEN.matcher(quoted);
        if (cbc.matches() && cbc.group("name").trim().equalsIgnoreCase(source.name())) {
            String scope = cbc.group("scope").toLowerCase(Locale.ROOT);
            boolean bySummons   = scope.contains("summon");
            boolean byAbilities = scope.contains("abilit");
            return ctx -> ctx.shieldSelfCannotBeChosenPermanently(source, bySummons, byAbilities);
        }
        // "[Self] must attack once per turn if possible." (Roche 29-076H) — likewise a rules
        // compulsion rather than a readable field ability.
        Matcher ma = GRANTED_MUST_ATTACK_ONCE_PER_TURN.matcher(quoted);
        if (ma.matches() && ma.group("subj").trim().equalsIgnoreCase(source.name()))
            return ctx -> ctx.grantSelfMustAttackOncePerTurnPermanently(source);
        return null;
    }

    /**
     * Parses "Your opponent gains control of [CardName]." — permanently transfers the source
     * card itself to its controller's opponent.
     */
    /**
     * Parses "You may give control of [Self] to your opponent." — Leslie 16-084R.
     *
     * <p>The optional form of {@link #tryParseOpponentGainsControlOfSource}, and self-named the
     * same way: the card offers itself, so a text naming another card is not this.
     */
    private static Consumer<GameContext> tryParseMayGiveSourceControlToOpponent(String text, CardData source) {
        if (source == null) return null;
        Matcher m = MAY_GIVE_SOURCE_CONTROL_TO_OPPONENT.matcher(text.trim());
        if (!m.matches()) return null;
        if (!m.group("name").trim().equalsIgnoreCase(source.name())) return null;
        return ctx -> {
            if (!ctx.promptYouMay("Give control of " + source.name() + " to your opponent?")) {
                ctx.logEntry(source.name() + " — control not given");
                return;
            }
            ctx.giveSourceControlToOpponent(source);
        };
    }

    private static Consumer<GameContext> tryParseOpponentGainsControlOfSource(String text, CardData source) {
        if (source == null) return null;
        Matcher m = STANDALONE_OPPONENT_GAINS_CONTROL.matcher(text.trim());
        if (!m.matches()) return null;
        if (!m.group("name").trim().equalsIgnoreCase(source.name())) return null;
        return ctx -> {
            ctx.logEntry(source.name() + " — control given to opponent");
            ctx.giveSourceControlToOpponent(source);
        };
    }

    /**
     * Returns {@code true} if {@code text} is the Doublecast free-Summons field effect.
     * Used by the AI to gate activation on actually having a Summon chain to exploit.
     */
    static boolean isDoublecastFreeSummonsEffect(String text) {
        return DOUBLECAST_FREE_SUMMONS_PATTERN.matcher(text.trim()).matches();
    }

    /**
     * What a modal Summon charges for taking every one of its options: {@code actions} — how many
     * the surcharge buys — and {@code crystals} — what it costs. Bahamut SIN 28-087H is the only
     * printing.
     */
    record SelectActionsSurcharge(int actions, int crystals) {}

    /**
     * The surcharge {@code text} rides on, or {@code null} when it carries none.
     *
     * <p>{@code cardName} is checked against the name the sentence prints: the clause names the
     * card whose cost goes up, and a text quoting some <em>other</em> card's rider is not this
     * card's price to pay.
     *
     * <p>A rider whose cost is not Crystals is turned down rather than read as free. Nothing else
     * prints one yet, and a silent zero would put a "pay nothing extra" item on the play menu.
     */
    static SelectActionsSurcharge selectActionsSurcharge(String text, String cardName) {
        if (text == null || cardName == null) return null;
        Matcher m = SELECT_FOLLOWING_ACTIONS_COST_INCREASE.matcher(text);
        if (!m.find()) return null;
        if (!m.group("name").trim().equalsIgnoreCase(cardName.trim())) return null;
        String cost = m.group("cost");
        int crystals = (cost.length() - cost.replace("《C》", "").length()) / "《C》".length();
        if (crystals == 0) return null;
        return new SelectActionsSurcharge(Integer.parseInt(m.group("actions")), crystals);
    }

    private static Consumer<GameContext> tryParseEndOfNextTurnIfCardOnFieldOppLoses(String text) {
        Matcher m = END_OF_NEXT_TURN_IF_CARD_ON_FIELD_OPP_LOSES.matcher(text);
        if (!m.matches()) return null;
        String cardName = m.group("name").trim();
        return ctx -> {
            ctx.logEntry("Effect: Scheduled — at end of next turn, if " + cardName + " is on field, opponent loses");
            ctx.scheduleAtEndOfControllerNextTurn(innerCtx -> {
                if (innerCtx.isNamedCardOnField(cardName)) {
                    innerCtx.logEntry(cardName + " is on the field — opponent loses the game");
                    innerCtx.causeOpponentToLose();
                } else {
                    innerCtx.logEntry(cardName + " is NOT on the field — Sin condition not met");
                }
            });
        };
    }

    /**
     * Tallies a run of 《…》 cost tokens into the {cp, crystals} pair plus a single element, the
     * shape {@link GameContext#mayPayCostToEffect} takes. Returns {@code null} for a run this
     * engine cannot price — an 《X》 variable, or more than one distinct element, neither of which
     * the payment primitive can express.
     */
    static Object[] tallyCostRun(String costs) {
        int cp = 0, crystals = 0;
        String element = null;
        Matcher t = COST_TOKEN.matcher(costs);
        while (t.find()) {
            String tok = t.group(1).trim();
            if (tok.equalsIgnoreCase("C"))      crystals++;
            else if (tok.matches("\\d+"))       cp += Integer.parseInt(tok);
            else if (tok.equalsIgnoreCase("X")) return null;
            else if (element == null)           element = tok;
            else if (element.equalsIgnoreCase(tok)) return null;  // 《Wind》《Wind》 — two of one element
            else                                return null;      // mixed elements
        }
        // Elements and generic CP together (《Fire》《1》) would need a compound payment the
        // primitive does not model, so decline rather than under-charge.
        if (element != null && (cp > 0 || crystals > 0)) return null;
        if (crystals > 0 && cp > 0) return null;
        if (cp == 0 && crystals == 0 && element == null) return null;
        return new Object[]{ cp, element, crystals };
    }

    /**
     * Parses "X. When/If you do so, Y." into a sequence: resolve X, then resolve Y only if
     * X made progress (see {@link GameContext#effectMadeProgress()}). Returns {@code null} if
     * either half cannot be parsed, so non-sequence text falls through to the regular matchers.
     */
    private static Consumer<GameContext> tryParseWhenYouDoSoSequence(String text, CardData source, int xValue) {
        Matcher m = WHEN_YOU_DO_SO_SEQUENCE.matcher(text);
        if (!m.find()) return null;
        Consumer<GameContext> primary  = parse(m.group("primary").trim(),  source, xValue);
        Consumer<GameContext> followup = parse(m.group("followup").trim(), source, xValue);
        if (primary == null || followup == null) return null;
        return ctx -> {
            ctx.resetEffectProgress();
            primary.accept(ctx);
            if (ctx.effectMadeProgress()) followup.accept(ctx);
        };
    }

    /**
     * Returns {@code true} when a card whose elements are {@code discarded} counts as a card "of
     * {@code elem} Element". Every element of a multi-element card qualifies it independently.
     */
    static boolean discardedIsOfElement(List<String> discarded, String elem) {
        for (String e : discarded)
            if (e.trim().equalsIgnoreCase(elem)) return true;
        return false;
    }

    /**
     * One branch of a "discard conditional element" ability, e.g. {@code element="Fire"},
     * {@code effectText="until the end of the turn, Firion gains +2000 power and First Strike."}.
     */
    record DiscardElementBranch(String element, String effectText) {}

    /**
     * Exposes the two branches of a "Discard 1 card: If the discarded card is of Elem1 Element,
     * eff1. If the discarded card is of Elem2 Element, eff2." ability for AI evaluation —
     * lets a caller check which element a discard needs to actually produce a benefit before
     * committing to the cost. Returns {@code null} if {@code effectText} isn't this shape.
     */
    static List<DiscardElementBranch> discardConditionalElementBranches(String effectText) {
        Matcher m = DISCARD_CONDITIONAL_ELEMENT.matcher(effectText.trim());
        if (!m.find()) return null;
        return List.of(
            new DiscardElementBranch(m.group("elem1").trim(), m.group("eff1").trim()),
            new DiscardElementBranch(m.group("elem2").trim(), m.group("eff2").trim())
        );
    }

    /**
     * Parses "If [Self] used [SpecialA] and [SpecialB] this turn, [effect]" — 7-059L Bartz's Rapid
     * Fire, whose payoff is unlocked by his two other Special abilities.
     *
     * <p>A resolution-time gate, not an activation restriction: the printing lets the ability be
     * used at any time and simply does nothing when the condition is unmet, which is what the
     * sentence says and how the rest of this family behaves.
     *
     * <p>Declines when the sentence names a card other than the source, and when the inner effect
     * cannot be parsed — a gate over an unimplemented payoff would report the ability as wired.
     */
    /**
     * The description of what {@link #tryParseIfSourceUsedSpecialsThisTurn} unlocks, or {@code "?"}
     * when that inner effect has no description of its own.
     */
    private static String ifSourceUsedSpecialsInnerDescription(String text, CardData source) {
        Matcher m = IF_SOURCE_USED_SPECIALS_THIS_TURN.matcher(text.trim());
        if (!m.matches()) return "?";
        String inner = fullDescription(m.group("effect").trim(), source);
        if (inner == null) inner = matchedPatternName(m.group("effect").trim(), source);
        return inner != null ? inner : "?";
    }

    private static Consumer<GameContext> tryParseIfSourceUsedSpecialsThisTurn(
            String text, CardData source, int xValue) {
        if (source == null) return null;
        Matcher m = IF_SOURCE_USED_SPECIALS_THIS_TURN.matcher(text.trim());
        if (!m.matches()) return null;
        if (!m.group("name").trim().equalsIgnoreCase(source.name())) return null;
        final String first  = m.group("first").trim();
        final String second = m.group("second").trim();
        Consumer<GameContext> inner = parse(m.group("effect").trim(), source, xValue);
        if (inner == null) return null;
        return ctx -> {
            if (!ctx.sourceUsedSpecialThisTurn(source, first)
                    || !ctx.sourceUsedSpecialThisTurn(source, second)) {
                ctx.logEntry(source.name() + " has not used both " + first + " and " + second
                        + " this turn — no effect");
                return;
            }
            ctx.logEntry(source.name() + " used " + first + " and " + second + " this turn");
            inner.accept(ctx);
        };
    }

    private static Consumer<GameContext> tryParseIfOwnForwardFormedParty(String text, CardData source, int xValue) {
        Matcher m = IF_OWN_FORWARD_FORMED_PARTY.matcher(text.trim());
        if (!m.matches()) return null;
        Consumer<GameContext> inner = parse(m.group("effect").trim(), source, xValue);
        if (inner == null) return null;
        return ctx -> {
            if (ctx.ownForwardFormedPartyThisTurn()) {
                inner.accept(ctx);
            } else {
                ctx.logEntry("Effect: no party formed this turn — skipped");
            }
        };
    }

    /**
     * "If your opponent has discarded a card from their hand due to your Summons or abilities this
     * turn, [effect]" — Werei 15-023R, read off the end-of-turn trigger its printing carries.
     *
     * <p>Shaped exactly like {@link #tryParseIfOwnForwardFormedParty}: one turn fact the engine
     * already records, asked of the context rather than reconstructed here. The action-ability half
     * of the same condition (Kazusa 15-026C) is a usability restriction rather than an effect, and
     * stays where {@code CardData} reads it into {@code requiresOppDiscardedThisTurn}.
     */
    private static Consumer<GameContext> tryParseIfOppDiscardedThisTurn(String text, CardData source, int xValue) {
        Matcher m = IF_OPP_DISCARDED_FROM_HAND_THIS_TURN.matcher(text.trim());
        if (!m.matches()) return null;
        Consumer<GameContext> inner = parse(m.group("effect").trim(), source, xValue);
        if (inner == null) return null;
        return ctx -> {
            if (ctx.opponentDiscardedFromHandDueToYourEffectsThisTurn()) {
                inner.accept(ctx);
            } else {
                ctx.logEntry("Effect: opponent discarded nothing to your Summons or abilities this turn — skipped");
            }
        };
    }

    /**
     * A field ability that continuously grants a quoted ability to Forwards while its own card is
     * on the field (Vayne 9-022L).
     *
     * @param affectsOpponent {@code true} for "Forwards opponent controls", {@code false} for
     *                        "Forwards you control" — relative to the granting card's controller
     * @param abilityText     the granted ability, exactly as quoted on the card
     */
    record ForwardAbilityGrant(boolean affectsOpponent, String abilityText) {}

    /**
     * True when {@code effectText} is an "if you don't pay 《…》" gate. Such text carries its own
     * pay-or-decline choice, so callers must not also treat a printed "you may" as an offer to skip
     * the ability outright.
     */
    public static boolean isPayOrElseGate(String effectText) {
        return effectText != null && IF_NOT_PAY_OR_ELSE.matcher(effectText.trim()).matches();
    }

    /**
     * Returns the card type (e.g. "Summon") when the effect text begins with a
     * "discard 1 &lt;Type&gt;" clause, or {@code null} if no such clause is present.
     * Used by {@code executeAutoAbility} to skip offering the "you may?" dialog
     * when the player has no eligible cards in hand.
     */
    public static String youMayDiscardType(String effectText) {
        Matcher m = DISCARD_TYPE.matcher(effectText);
        if (!m.find()) return null;
        return m.group("type");
    }

    /**
     * Returns the discard count when the effect text begins with "discard N cards",
     * or -1 if it doesn't match.
     * Used by {@code executeAutoAbility} to skip offering the "you may?" dialog
     * when the player has fewer cards in hand than required.
     */
    public static int youMayDiscardCount(String effectText) {
        Matcher m = DISCARD_N_CARDS_PREFIX.matcher(effectText.trim());
        if (!m.find()) return -1;
        return Integer.parseInt(m.group("count"));
    }

    /** Parses "Remove all the cards in your opponent's Break Zone from the game." */
    private static Consumer<GameContext> tryParseRemoveAllOppBzFromGame(String text) {
        if (!REMOVE_ALL_OPP_BZ_FROM_GAME.matcher(text.trim()).matches()) return null;
        return ctx -> {
            ctx.logEntry("Effect: Remove all cards in opponent's Break Zone from the game");
            ctx.removeAllOpponentBzFromGame();
        };
    }

    /** Parses "Remove [CardName] from the game." — removes a named card from the field. */
    private static Consumer<GameContext> tryParseRemoveNamedFromGame(String text, CardData source) {
        Matcher m = REMOVE_NAMED_FROM_GAME.matcher(text);
        if (!m.find()) return null;
        String named = m.group("named").trim();
        return ctx -> {
            ctx.logEntry("Effect: Remove " + named + " from the game");
            ctx.removeNamedCardFromGame(named);
        };
    }

    /**
     * Parses "You may remove [CardName] from the game." — shows a yes/no prompt; if accepted,
     * calls {@link GameContext#removeNamedCardFromGame}; if declined, calls
     * {@link GameContext#markEffectFizzled()} so any "If you do so" followup is suppressed.
     */
    private static Consumer<GameContext> tryParseYouMayRemoveNamedFromGame(String text, CardData source) {
        if (source == null) return null;
        Matcher m = YOU_MAY_REMOVE_NAMED_FROM_GAME.matcher(text.trim());
        if (!m.matches()) return null;
        String name = m.group("name").trim();
        if (!name.equalsIgnoreCase(source.name())) return null;
        return ctx -> {
            if (!ctx.promptYouMay("Remove " + name + " from the game?")) {
                ctx.markEffectFizzled();
                return;
            }
            ctx.logEntry("Effect: Remove " + name + " from the game");
            ctx.removeNamedCardFromGame(name);
        };
    }

    /**
     * True for wording that points back at the card carrying the ability rather than naming it —
     * "this Forward", "this Character". Granted abilities are written this way, since the text is
     * printed on the granting card but resolves for whichever card received it.
     */
    static boolean isSelfReference(String name) {
        return name.matches("(?i)this\\s+(?:Forward|Backup|Monster|Character|card)");
    }

    private static Consumer<GameContext> tryParseYouMayPutSelfToBZWhenDoSo(String text, CardData source) {
        if (source == null) return null;
        Matcher m = YOU_MAY_PUT_SELF_TO_BZ_WHEN_DO_SO.matcher(text.trim());
        if (!m.matches()) return null;
        if (!m.group("name").trim().equalsIgnoreCase(source.name())) return null;
        String followupText = m.group("effect").trim();
        Consumer<GameContext> followup = parse(followupText, source);
        if (followup == null) return null;
        return ctx -> ctx.mayBreakSourceWhenDoSo(source, followup);
    }

    // -------------------------------------------------------------------------
    // Delayed ("at the end of this turn") and recurring end-of-turn field parsers
    // -------------------------------------------------------------------------

    /**
     * Parses "At the end of this turn, &lt;effect&gt;" — wraps any supported mass-field
     * effect so it fires at the beginning of the end phase instead of immediately.
     */
    private static Consumer<GameContext> tryParseDelayedEffect(String text) {
        Matcher m = AT_END_OF_TURN_PATTERN.matcher(text);
        if (!m.find()) return null;
        String rest = m.group("rest");
        Consumer<GameContext> inner = tryParseAllFieldEffect(rest);
        if (inner == null) return null;
        return ctx -> {
            ctx.logEntry("End-of-turn effect queued: " + rest);
            ctx.addEndOfTurnEffect(inner);
        };
    }

    // -------------------------------------------------------------------------
    // All-field-cards effect parser
    // -------------------------------------------------------------------------

    /**
     * Parses "Choose 1 Summon or auto-ability. Cancel its effect." (Y'shtola).
     * The player selects a stack entry; its effect is suppressed when it resolves.
     */
    /**
     * Parses 29-012H Neon's Runic: "Choose 1 Summon or auto-ability. During this turn, if it deals
     * damage to a Forward or a player, the damage becomes 0 instead."
     *
     * <p>Must precede {@link #tryParseCancelStackEntry}: that parser find()s on the shared first
     * sentence, so it would claim this text and cancel the effect outright — strictly better than
     * what Neon prints, which lets the effect resolve and only blanks its damage.
     */
    private static Consumer<GameContext> tryParseChooseStackEntryZeroItsDamage(String text) {
        if (!CHOOSE_STACK_ENTRY_ZERO_ITS_DAMAGE.matcher(text.trim()).matches()) return null;
        return ctx -> {
            ctx.logEntry("Effect: Choose 1 Summon or auto-ability - its damage becomes 0 this turn");
            ctx.chooseStackEntryZeroItsDamageThisTurn();
        };
    }

    private static Consumer<GameContext> tryParseCancelStackEntry(String text) {
        if (!STANDALONE_CANCEL_STACK_ENTRY_PATTERN.matcher(text).find()) return null;
        return ctx -> {
            ctx.logEntry("Effect: Choose 1 Summon or auto-ability — cancel its effect");
            ctx.cancelStackEntry();
        };
    }

    /**
     * The entry filter of a "Choose any number of [types]. Cancel their effects." text, or
     * {@code null} when {@code effectText} is not one.
     *
     * <p>Every term in the list has to be a kind of Stack entry: the pattern's type group is
     * deliberately loose, so this is where a list naming Forwards or anything else the Stack does
     * not hold is turned down. Shared by the parser, the two reporting chains and
     * {@link #stackCancelFilter}, so the gate that decides the ability may be activated and the
     * resolution that acts on it can never disagree about what is eligible.
     */
    static Predicate<StackEntry> cancelAnyNumberFilter(String effectText) {
        if (effectText == null) return null;
        Matcher m = CANCEL_ANY_NUMBER_ABILITIES_ON_STACK.matcher(effectText.trim());
        if (!m.matches()) return null;
        String types = m.group("types").trim();
        for (String term : types.split("(?i)\\s*,\\s*|\\s+or\\s+")) {
            if (term.isBlank()) continue;
            if (!CANCELLABLE_ENTRY_TYPE.matcher(term.trim()).matches()) return null;
        }
        return parseAbilityTypeFilter(types);
    }

    /**
     * Parses "Choose any number of [types]. Cancel their effects." — Jecht 14-108H and Shelke
     * 16-029R. The player picks as many matching entries off the Stack as they like and every one
     * of them is cancelled.
     *
     * <p>Must precede {@code tryParseIndependentSentences}: neither sentence refers back to the
     * other, so that rule accepted the pair and resolved them apart — the choose was read as a
     * selection of field Characters and the cancel as an answer to a selection in progress, which
     * between them cancelled something nobody had chosen. Shelke's shorter list did not even
     * survive that far: its first sentence parses as nothing, so the whole ability fell through to
     * the compound-sentence fallback and resolved as the bare cancel alone.
     */
    private static Consumer<GameContext> tryParseCancelAnyNumberAbilitiesOnStack(String text) {
        Predicate<StackEntry> filter = cancelAnyNumberFilter(text);
        if (filter == null) return null;
        Matcher m = CANCEL_ANY_NUMBER_ABILITIES_ON_STACK.matcher(text.trim());
        m.matches();
        String types  = m.group("types").trim();
        String prompt = "Choose any number of " + types + " to cancel:";
        return ctx -> {
            ctx.logEntry("Effect: Cancel any number of " + types + " on stack");
            ctx.cancelAnyNumberOfAbilitiesOnStack(filter, prompt);
        };
    }

    /**
     * Parses the general "Choose 1 [ability type(s)] [optional filter]. Cancel its effect." family.
     * Builds a {@link java.util.function.Predicate} over {@link StackEntry} from the parsed type string.
     */
    /**
     * Parses Gogo 27-099H's "choose 1 auto-ability triggered from your opponent's Forward of cost
     * 4 or less. Gogo triggers the same auto-ability."
     *
     * <p>Self-named, like the rest of the effects that act on their own card: the copy is
     * triggered from the card printing the sentence.
     *
     * <p>The filter is built here and enforced, where the cancel family captures the same
     * qualifier and lets it go. The two are not the same risk: a cancel offered too widely still
     * only removes something, while a copy offered too widely hands its controller an ability the
     * card never reached. "Your opponent's" is read against the entry's controller, and the cost
     * against the printed cost of the card the trigger came from — which is deliberately not a
     * check that the card is still on the field, since a Forward that has since left it triggered
     * from one all the same.
     */
    private static Consumer<GameContext> tryParseCopyChosenAutoAbilityOnStack(String text, CardData source) {
        if (source == null) return null;
        Matcher m = COPY_CHOSEN_AUTO_ABILITY_ON_STACK.matcher(text.trim());
        if (!m.find()) return null;
        if (!m.group("name").trim().equalsIgnoreCase(source.name())) return null;
        boolean opponentsOnly = m.group("opponents") != null;
        String  typeLower     = m.group("type").toLowerCase(Locale.ROOT);
        int     costVal       = m.group("cost") != null ? Integer.parseInt(m.group("cost")) : -1;
        boolean costIsMore    = "more".equalsIgnoreCase(m.group("cmp"));
        String  described     = (opponentsOnly ? "your opponent's " : "a ") + m.group("type")
                + (costVal >= 0 ? " of cost " + costVal + " or " + (costIsMore ? "more" : "less") : "");
        return ctx -> {
            boolean mine = ctx.isP1();
            java.util.function.Predicate<StackEntry> filter = e -> {
                CardData from = e.source();
                if (from == null) return false;
                if (opponentsOnly && e.isP1() == mine) return false;
                if (!matchesCardKind(from, typeLower)) return false;
                if (costVal < 0) return true;
                return costIsMore ? from.cost() >= costVal : from.cost() <= costVal;
            };
            ctx.logEntry("Effect: " + source.name() + " triggers an auto-ability of " + described);
            ctx.copyChosenAutoAbilityOnStack(filter,
                    "Choose an auto-ability for " + source.name() + " to trigger:", source);
        };
    }

    /** Whether {@code card} is the card kind {@code typeLower} names ("forward", "character", …). */
    private static boolean matchesCardKind(CardData card, String typeLower) {
        return switch (typeLower) {
            case "forward"   -> card.isForward();
            case "backup"    -> card.isBackup();
            case "monster"   -> card.isMonster();
            case "character" -> card.isForward() || card.isBackup() || card.isMonster();
            default          -> false;
        };
    }

    private static Consumer<GameContext> tryParseCancelAbilityOnStack(String text) {
        Matcher m = CANCEL_ABILITY_ON_STACK.matcher(text.trim());
        if (!m.find()) return null;
        String types = m.group("types").trim();
        String tgtFilterText = m.group("tgtFilter");
        boolean requiresControllerTarget = tgtFilterText != null
                && tgtFilterText.toLowerCase(Locale.ROOT).contains("you control");
        java.util.function.Predicate<StackEntry> filter = parseAbilityTypeFilter(types);
        String prompt = "Choose 1 " + types + " to cancel:";
        return ctx -> {
            ctx.logEntry("Effect: Cancel " + types + " on stack");
            ctx.cancelFilteredAbilityOnStack(filter, prompt, requiresControllerTarget);
        };
    }

    /**
     * True if {@code text} is a standalone "If your opponent doesn't pay 《N》, [target action]."
     * whose action is a recognised target action — the reactive "enters opponent's field not from
     * hand" watcher effects (e.g. Remedi) that {@code AutoAbilityTriggers} runs inline with the
     * entering card preloaded as the target.
     */
    static boolean isIfOppNotPayAction(String text) {
        return tryParseIfOppNotPayAction(text) != null;
    }

    /**
     * True if {@code text} is a bare target action applied to the card that fired the trigger —
     * the form {@code AutoAbilityTriggers} must run inline with that card preloaded as the target,
     * since the effect names no target of its own.
     */
    static boolean isTriggeredTargetAction(String text) {
        return tryParseTriggeredTargetAction(text, 0) != null;
    }

    /**
     * True if {@code text} is the bare "Add it to your hand." that Gogo 24-022H prints as a whole
     * effect, where "it" is the card whose arrival in the Break Zone fired the trigger.
     *
     * <p>Read by the compound-sentence fallback, which must never resolve this sentence out of a
     * longer ability: there the pronoun points at a card an earlier sentence chose, not at the
     * trigger's own.
     */
    static boolean isTriggeringBrokenCardSalvage(String text) {
        return tryParseAddBrokenCardToHand(text) != null;
    }

    /**
     * Converts an ability-type string captured by {@link #CANCEL_ABILITY_ON_STACK} or
     * {@link #REDIRECT_ABILITY_TARGET} into a predicate over stack entries.
     * <ul>
     *   <li>"auto-ability" / "auto ability" → auto-abilities only</li>
     *   <li>"action ability" → action abilities (regular and special)</li>
     *   <li>"special ability" → special (named) action abilities only</li>
     *   <li>"ability" → any non-summon, non-EX-burst entry</li>
     *   <li>"summon" → Summons only</li>
     *   <li>Two types joined by " or " → union of the two individual predicates</li>
     * </ul>
     */
    static java.util.function.Predicate<StackEntry> parseAbilityTypeFilter(String types) {
        String t = types.trim().toLowerCase(Locale.ROOT);
        if (t.equals("ability")) return e -> !e.isSummon() && !e.isExBurstEntry();
        boolean wantsSummon  = t.contains("summon");
        boolean wantsAuto    = t.contains("auto");
        boolean wantsSpecial = t.contains("special");
        boolean wantsAction  = t.contains("action");
        return e -> (wantsSummon  && e.isSummon())
                 || (wantsAuto    && e.isAutoAbility())
                 || (wantsSpecial && e.isSpecialAbility())
                 || (wantsAction  && e.isActionAbility());
    }

    /**
     * Restricts {@code filter} to entries whose recorded targets include a card the canceller
     * controls — the "choosing a Character you control" qualifier.
     *
     * <p>An entry that recorded no targets stays eligible: target pre-selection is not modelled
     * for every effect, so excluding those would make the qualifier stricter than the engine can
     * actually verify.
     */
    static Predicate<StackEntry> withControllerTargetRequirement(Predicate<StackEntry> filter,
            boolean cancellerIsP1) {
        return filter.and(e -> {
            List<ForwardTarget> stored = e.preSelectedTargets();
            if (stored == null || stored.isEmpty()) return true;
            return stored.stream().anyMatch(t -> t.isP1() == cancellerIsP1);
        });
    }

    /**
     * The stack entries {@code effectText} could legally cancel, or {@code null} when it is not a
     * "choose an entry on the stack and cancel it" effect.
     *
     * <p>Used to gate activation: an ability whose only effect is a cancel has nothing to do with
     * an empty or type-mismatched stack, and activating it anyway pays its cost — often putting
     * the card itself into the Break Zone — for no effect at all.
     *
     * <p>Each branch returns the filter its own resolution path applies, so the gate and the
     * resolution can never disagree about what is eligible. Branch order mirrors {@code parse()}'s
     * among these parsers; the final branch is the choose-chain's "Cancel its effect." followup,
     * which resolves through {@link GameContext#cancelStackEntry()} and is therefore always
     * Summons and auto-abilities regardless of any cost or element wording in the choose clause.
     */
    /**
     * The redirect criteria of {@code effectText}, or {@code null} when it is not one of the
     * "the Summon or ability is now choosing X instead" abilities — see {@link TargetRedirect}.
     *
     * <p>Both members of this family name their own card as the fixed end of the redirect:
     * Faris in its eligibility ("choosing only Faris"), Edge in its destination ("now choosing
     * Edge instead"). Checking that name against {@code source} rather than taking the text at
     * face value is what stops a future card that names a <em>different</em> card from being
     * read as one of these and silently redirecting to the wrong permanent.
     *
     * <p>Used both to gate activation (an ability with no eligible Stack entry has nothing to do
     * and would pay its cost for nothing) and to drive resolution.
     */
    static TargetRedirect targetRedirect(String effectText, CardData source) {
        if (effectText == null || effectText.isBlank() || source == null) return null;
        String text = effectText.trim();

        Matcher self = REDIRECT_CHOOSING_ONLY_SELF.matcher(text);
        if (self.find() && namesSource(self.group("self"), source))
            return TargetRedirect.toChosenForward(self.group("newelem").trim());

        Matcher toSelf = REDIRECT_MY_FORWARD_TO_SELF.matcher(text);
        if (toSelf.find() && namesSource(toSelf.group("newtarget"), source))
            return TargetRedirect.toSource(toSelf.group("elem").trim());

        Matcher onField = REDIRECT_ON_FIELD_TO_SELF.matcher(text);
        if (onField.find() && namesSource(onField.group("newtarget"), source))
            return TargetRedirect.onFieldToSource();

        Matcher freePick = REDIRECT_SINGLE_TARGET_TO_CHOSEN.matcher(text);
        if (freePick.find())
            return TargetRedirect.toAnyChosenCharacter(
                    freePick.group("types").toLowerCase(Locale.ROOT).startsWith("summon")
                            ? TargetRedirect.EntryKind.SUMMON
                            : TargetRedirect.EntryKind.ABILITY);

        return null;
    }

    /** True when {@code printedName} is the source card's own name. */
    private static boolean namesSource(String printedName, CardData source) {
        return printedName != null && printedName.trim().equalsIgnoreCase(source.name());
    }

    static Predicate<StackEntry> stackCancelFilter(String effectText, boolean cancellerIsP1) {
        if (effectText == null || effectText.isBlank()) return null;
        String text = effectText.trim();

        Matcher unlessPay = CANCEL_STACK_ENTRY_UNLESS_PAY.matcher(text);
        if (unlessPay.find()) {
            Predicate<StackEntry> filter = parseAbilityTypeFilter(unlessPay.group("types").trim());
            return unlessPay.group("opponents") != null
                    ? filter.and(e -> e.isP1() != cancellerIsP1)
                    : filter;
        }
        Predicate<StackEntry> anyNumber = cancelAnyNumberFilter(text);
        if (anyNumber != null) return anyNumber;
        Matcher onStack = CANCEL_ABILITY_ON_STACK.matcher(text);
        if (onStack.find()) {
            Predicate<StackEntry> filter = parseAbilityTypeFilter(onStack.group("types").trim());
            String tgtFilter = onStack.group("tgtFilter");
            return tgtFilter != null && tgtFilter.toLowerCase(Locale.ROOT).contains("you control")
                    ? withControllerTargetRequirement(filter, cancellerIsP1)
                    : filter;
        }
        Matcher targeting = CANCEL_SUMMON_TARGETING_MY_CHARACTER.matcher(text);
        if (targeting.find()) {
            Predicate<StackEntry> filter = targeting.group("orability") != null
                    ? e -> !e.isExBurstEntry()
                    : StackEntry::isSummon;
            return withControllerTargetRequirement(filter, cancellerIsP1);
        }
        if (STANDALONE_CANCEL_STACK_ENTRY_PATTERN.matcher(text).find()
                || FOLLOWUP_CANCEL_EFFECT.matcher(text).find())
            return e -> e.isSummon() || e.isAutoAbility();
        return null;
    }

    /**
     * Parses "Your opponent puts the top N card(s) of his/her deck into the Break Zone
     * [. Draw M card(s)]".
     */
    private static Consumer<GameContext> tryParseOpponentMill(String text) {
        Matcher m = OPPONENT_MILL_PATTERN.matcher(text);
        if (!m.find()) return null;

        String countStr = m.group("count");
        if (countStr == null) countStr = m.group("count2");
        int    mill     = countStr != null ? Integer.parseInt(countStr) : 1;
        String drawStr  = m.group("draw");
        int    draw     = drawStr  != null ? Integer.parseInt(drawStr)  : 0;

        return ctx -> {
            ctx.logEntry("Effect: Opponent mills " + mill + " card(s)"
                    + (draw > 0 ? ", draw " + draw : ""));
            ctx.opponentMillCards(mill);
            if (draw > 0) ctx.drawCards(draw);
        };
    }

    /** Parses "Put the top N card(s) of your deck into the Break Zone." */
    private static Consumer<GameContext> tryParseSelfMill(String text) {
        Matcher m = SELF_MILL_PATTERN.matcher(text);
        if (!m.find()) return null;

        String countStr = m.group("count");
        int    mill     = countStr != null ? Integer.parseInt(countStr) : 1;

        return ctx -> {
            ctx.logEntry("Effect: Mill " + mill + " card(s) into own Break Zone");
            ctx.millCards(mill);
        };
    }

    /**
     * Builds a single {@link RevealClause} from a parsed condition string and
     * action string.  Returns {@code null} if either the condition or the action
     * is not recognised.
     */
    static RevealClause buildRevealClause(String condText, String actionText, CardData source) {
        Predicate<CardData> condition = parseRevealCondition(condText);
        if (condition == null) return null;
        String cardOp = normalizeRevealOp(actionText);
        if (cardOp != null) return new RevealClause(condition, cardOp, null);
        Consumer<GameContext> effect = parse(actionText, source);
        if (effect != null) return new RevealClause(condition, null, effect);
        return null;
    }

    /**
     * Converts a raw condition string (captured from "If it is/has [cond],") into a
     * {@link Predicate} that tests a {@link CardData} against that condition.
     * Supported forms (article and negation handled first):
     * <ul>
     *   <li>"[not] a/an Forward|Backup|Character|Summon|Monster"</li>
     *   <li>"[not] a/an [Element] [type|card]" — element alone, element+type, element+card</li>
     *   <li>"[not] a/an Job X [or Card Name Y]"</li>
     *   <li>"[not] a/an Card Name X"</li>
     *   <li>"[not] a/an Category X [type]"</li>
     * </ul>
     * Returns {@code null} for unrecognised patterns.
     */
    static Predicate<CardData> parseRevealCondition(String cond) {
        cond = cond.trim();
        boolean negated = false;

        Matcher negM = Pattern.compile("(?i)^not\\s+an?\\s+(.+)$").matcher(cond);
        if (negM.matches()) {
            negated = true;
            cond = negM.group(1).trim();
        } else {
            Matcher artM = Pattern.compile("(?i)^an?\\s+(.+)$").matcher(cond);
            if (artM.matches()) cond = artM.group(1).trim();
        }

        Predicate<CardData> pred;

        // 1. "Job X [or [a/an] Card Name Y]"
        Matcher jobM = Pattern.compile(
            "(?i)^Job\\s+(.+?)(?:\\s+or\\s+(?:an?\\s+)?Card\\s+Name\\s+(.+))?$"
        ).matcher(cond);
        if (jobM.matches()) {
            String job  = jobM.group(1).trim();
            String name = jobM.group(2) != null ? jobM.group(2).trim() : null;
            pred = card -> card.hasJob(job)
                    || (name != null && card.name().equalsIgnoreCase(name));
            return negated ? pred.negate() : pred;
        }

        // 2. "Card Name X"
        Matcher nameM = Pattern.compile("(?i)^Card\\s+Name\\s+(.+)$").matcher(cond);
        if (nameM.matches()) {
            String name = nameM.group(1).trim();
            pred = card -> card.name().equalsIgnoreCase(name);
            return negated ? pred.negate() : pred;
        }

        // 3. "Category X [type|card]"
        Matcher catM = Pattern.compile(
            "(?i)^Category\\s+(\\S+)(?:\\s+(Forward|Character|Backup|Summon|Monster|card))?$"
        ).matcher(cond);
        if (catM.matches()) {
            String cat     = catM.group(1).trim();
            String catType = catM.group(2);
            pred = card -> {
                String cl = cat.toLowerCase(Locale.ROOT);
                if (!card.category1().toLowerCase(Locale.ROOT).contains(cl)
                        && !card.category2().toLowerCase(Locale.ROOT).contains(cl))
                    return false;
                return catType == null || catType.equalsIgnoreCase("card")
                        || meetsTypeCheck(card, catType);
            };
            return negated ? pred.negate() : pred;
        }

        // 4. "[Element] [type|card]" — element alone, element+type, or element+"card"
        Matcher elemM = Pattern.compile(
            "(?i)^(Fire|Ice|Wind|Earth|Lightning|Water|Light|Dark)" +
            "(?:\\s+(Forward|Character|Backup|Summon|Monster|card))?$"
        ).matcher(cond);
        if (elemM.matches()) {
            String elem     = elemM.group(1);
            String elemType = elemM.group(2);
            pred = card -> {
                if (!card.containsElement(elem)) return false;
                return elemType == null || elemType.equalsIgnoreCase("card")
                        || meetsTypeCheck(card, elemType);
            };
            return negated ? pred.negate() : pred;
        }

        // 5. Simple type
        Matcher typeM = Pattern.compile(
            "(?i)^(Forward|Character|Backup|Summon|Monster)$"
        ).matcher(cond);
        if (typeM.matches()) {
            String type = typeM.group(1);
            pred = card -> meetsTypeCheck(card, type);
            return negated ? pred.negate() : pred;
        }

        return null;
    }

    static boolean meetsTypeCheck(CardData card, String type) {
        return switch (type.toLowerCase(Locale.ROOT)) {
            case "forward"   -> card.isForward();
            case "backup"    -> card.isBackup();
            case "character" -> card.isForward() || card.isBackup() || card.isMonster();
            case "summon"    -> card.isSummon();
            case "monster"   -> card.isMonster();
            default          -> false;
        };
    }

    /**
     * Returns a card-op code if {@code raw} is an action that directly places the
     * revealed card ("play it onto the field [dull]", "add it to your hand",
     * "put it into the Break Zone").  Returns {@code null} for all other actions
     * (standalone effects like "draw N cards", "deal X damage …"), which are then
     * parsed by the main {@link #parse} chain.
     */
    private static String normalizeRevealOp(String raw) {
        if (raw == null) return null;
        String lo = raw.trim().toLowerCase(Locale.ROOT);
        // Compound actions that involve selecting another card first are handled by parse(),
        // not treated as simple "place revealed card" ops.
        if (lo.contains("select") || lo.contains("choose") || lo.startsWith("your opponent")) return null;
        if (lo.contains("field") && lo.contains("dull")) return "playOntoFieldDull";
        if (lo.contains("field"))  return "playOntoField";
        if (lo.contains("hand"))   return "addToHand";
        if (lo.contains("break"))  return "putToBreakZone";
        if (lo.contains("cast") && lo.contains("cost")) return "castSummonFree";
        return null;
    }

    /**
     * Returns {@code true} if the card has a permanent field ability of the form
     * "[CardName] cannot become dull by your opponent's Summons or abilities."
     */
    static boolean hasCannotBeDulledByOppFieldAbility(CardData card) {
        for (FieldAbility fa : card.fieldAbilities()) {
            Matcher m = STANDALONE_NAMED_CANNOT_BECOME_DULL_OPP.matcher(fa.effectText());
            if (m.find() && m.group("name").trim().equalsIgnoreCase(card.name())) return true;
        }
        return false;
    }

    /**
     * Returns {@code true} if the card has a permanent field ability of the form
     * "[CardName] cannot be returned to its owner's hand by [your] opponent's Summons or abilities."
     */
    static boolean hasCannotBeReturnedToHandByOppFieldAbility(CardData card) {
        for (FieldAbility fa : card.fieldAbilities()) {
            Matcher m = STANDALONE_NAMED_CANNOT_BE_RETURNED_TO_HAND_OPP.matcher(fa.effectText());
            if (m.find() && m.group("name").trim().equalsIgnoreCase(card.name())) return true;
        }
        return false;
    }

    /**
     * Returns {@code true} if the card has the blanket field ability
     * "Characters you control cannot be returned to their owner's hand by your opponent's
     * Summons or abilities." (protects every character its controller controls).
     */
    static boolean hasCharactersCannotBeReturnedFieldAbility(CardData card) {
        for (FieldAbility fa : card.fieldAbilities()) {
            if (STANDALONE_CHARACTERS_CANNOT_BE_RETURNED_TO_HAND_OPP.matcher(fa.effectText()).find()) return true;
        }
        return false;
    }

    /**
     * Returns {@code true} if the card has a permanent field ability of the form
     * "[CardName] cannot be put into the Break Zone by [your] opponent's Summons or abilities."
     */
    static boolean hasCannotBePutIntoBzByOppFieldAbility(CardData card) {
        for (FieldAbility fa : card.fieldAbilities()) {
            Matcher m = STANDALONE_NAMED_CANNOT_BE_PUT_INTO_BZ_OPP.matcher(fa.effectText());
            if (m.find() && m.group("name").trim().equalsIgnoreCase(card.name())) return true;
        }
        return false;
    }

    /**
     * Maps a quoted granted-ability string to the trait that enforces it, or {@code null}
     * when the quote is not a recognized protection grant.
     */
    private static CardData.Trait quotedProtectionTrait(String quote) {
        String q = quote.toLowerCase(Locale.ROOT);
        if (q.contains("cannot become dull"))                 return CardData.Trait.CANNOT_BE_DULLED_BY_OPP;
        if (q.contains("cannot be returned to its owner"))    return CardData.Trait.CANNOT_BE_RETURNED_TO_HAND_BY_OPP;
        if (q.contains("cannot be decreased"))                return CardData.Trait.POWER_CANNOT_BE_DECREASED_BY_OPP;
        return null;
    }

    /**
     * Parses each quote in {@code quotesRaw} into a protection trait and adds it to
     * {@code traits}. Returns {@code false} (leaving the text unparsed) when any quote
     * is not a recognized protection grant.
     */
    /**
     * The traits enforcing every granted ability in {@code grants} — the quoted blob of an
     * "… gain &lt;grants&gt; until the end of the turn" clause, delimiters included — or
     * {@code null} when any of them is not a recognized protection.
     *
     * <p>Two quoting styles are in the corpus and they cannot be read the same way. Double quotes
     * delimit reliably, so a blob containing one is split with {@link ActionResolverPatterns#QUOTED_GRANT}
     * (23-039R Asura grants two that way). 10-076H Titan quotes with {@code '}, which is also the
     * apostrophe in "your opponent's" — there is no delimiter to split on, so that blob is taken
     * whole with its outer quotes trimmed. No printing mixes the two.
     *
     * <p>Declining beats granting part of the clause: "cannot be broken" and "cannot be chosen"
     * are printed in the same shape and have no trait here, and a card that silently granted
     * nothing would look like it had resolved.
     */
    static EnumSet<CardData.Trait> grantedProtectionTraits(String grants) {
        String blob = grants.trim();
        EnumSet<CardData.Trait> traits = EnumSet.noneOf(CardData.Trait.class);
        if (blob.indexOf('"') >= 0) {
            if (!addQuotedProtectionTraits(blob, traits)) return null;
        } else {
            CardData.Trait tr = quotedProtectionTrait(blob.replaceAll("^'|'$", "").trim());
            if (tr == null) return null;
            traits.add(tr);
        }
        return traits.isEmpty() ? null : traits;
    }

    static boolean addQuotedProtectionTraits(String quotesRaw, EnumSet<CardData.Trait> traits) {
        Matcher qm = QUOTED_GRANT.matcher(quotesRaw);
        while (qm.find()) {
            CardData.Trait tr = quotedProtectionTrait(qm.group(1));
            if (tr == null) return false;
            traits.add(tr);
        }
        return true;
    }

    /**
     * Drops a leading EX Burst marker from {@code effectText}, in either form card text carries it:
     * the tagged {@code [[ex]]EX BURST[[/]]} and the bare {@code EX BURST} that survives when
     * {@link CardData#summonEffect()} strips only the opening tag.
     *
     * <p>The marker says how a Summon <em>may</em> resolve, not what it does, so it is never part of
     * the effect. {@code parse()} drops it before matching; the game log drops it before printing,
     * but only when the card did not in fact resolve off an EX Burst — there it is the whole story.
     */
    /**
     * {@code effectText} with its "If [name] results from an EX Burst, &lt;alt&gt; instead."
     * sentence resolved for the resolution actually happening: dropped when {@code isExBurst} is
     * false, and folded in over the clause it replaces when it is true.
     *
     * <p>For display only — the log line and the stack window's summary. Resolution goes through
     * {@link ActionResolverPatterns#FOLLOWUP_INSTEAD_EXBURST} and picks its branch at run time;
     * this says the same thing in the one place the player reads. Printing the card as-written
     * named both readings at once, so 14-108C Leviathan announced "Return it to its owner's hand"
     * and "return it to its owner's hand and draw 1 card instead" on a cast that was only ever
     * going to do the first.
     *
     * <p>Returns {@code effectText} unchanged when there is no such sentence, which is all but
     * seven cards in the corpus, and also when the sentence is not the last thing in the text.
     * 7-084C Yojimbo is the one printing of that shape — "…the former gains +3000 power until the
     * end of the turn instead. Then, each Forward deals damage equal to its power to the other." —
     * and which earlier clause the alternative replaces is not recoverable from the wording: the
     * trailing "Then, …" restates a sentence the base already has. Rewriting it either duplicated
     * that sentence or dropped the wrong one, so it is shown as printed and the player reads the
     * card. The other six all end on the alternative and rewrite cleanly.
     *
     * @param source the card, for the name-with-a-period escaping the sentence split needs
     */
    public static String resolveExBurstInstead(String effectText, CardData source, boolean isExBurst) {
        if (effectText == null) return null;
        Matcher m = EX_BURST_INSTEAD_SENTENCE.matcher(effectText);
        if (!m.find()) return effectText;
        if (!effectText.substring(m.end()).isBlank()) return effectText;

        String head = effectText.substring(0, m.start()).trim();
        if (!isExBurst) return head;

        String alt = m.group("alt").trim();
        String swapped = insteadVariant(head, alt, source);
        // A single-sentence head has no earlier clause to keep, so the alternative stands alone.
        return swapped != null ? swapped
                : Character.toUpperCase(alt.charAt(0)) + alt.substring(1) + ".";
    }

    public static String stripExBurstPrefix(String effectText) {
        if (effectText == null) return null;
        // \b after BURST is what keeps this a marker strip. Without it the plural in a clause that
        // opens "EX Bursts of cards put into the Damage Zone …" (6-017C Bahamut's second half) was
        // read as the marker plus a stray "s", and parse() went on to match nothing at all —
        // while matchedPatternName(), which does not strip, named the parser that should have run.
        return effectText
                .replaceFirst("(?i)^(?:\\[\\[ex\\]\\])?\\s*EX\\s+BURST\\b\\s*(?:\\[\\[/\\]\\])?\\s*", "")
                .trim();
    }

    /**
     * What a Summon must be able to choose on the field for casting it to accomplish anything —
     * or {@code null} when casting it is worth something no matter what is out there.
     *
     * <p>Answered for the AI, which otherwise spends CP on a Summon that resolves into an empty
     * board and does nothing at all. It is deliberately conservative in one direction: a Summon it
     * cannot read is reported as {@code null} and stays castable, because declining to cast a card
     * that would have worked is a worse mistake than the one being fixed.
     *
     * <p>That is also why the follow-up sentences are examined. Of the Summons opening with a
     * mandatory choice, roughly a third carry a clause that stands on its own — "Draw 1 card", "All
     * the Fire Forwards you control gain +2000 power" — and those are still worth casting into an
     * empty board. Only a Summon whose every remaining sentence points back at the chosen card is
     * reported here.
     */
    public static SummonTargetNeed summonTargetRequirement(String effectText) {
        if (effectText == null) return null;
        String text = stripExBurstPrefix(effectText);
        Matcher m = OPENING_MANDATORY_CHOICE.matcher(text);
        if (!m.find()) return null;

        String what = m.group("what");
        // A choice naming a zone other than the field ("in your Break Zone", "from your hand") is
        // not answered by what is on the board, so this cannot speak to it.
        if (what.matches("(?i).*\\b(?:Break\\s+Zone|hand|deck|removed\\s+from\\s+the\\s+game)\\b.*"))
            return null;

        Matcher kind = CHOSEN_CARD_KIND.matcher(what);
        boolean forwards = false, backups = false, monsters = false;
        while (kind.find()) {
            String k = kind.group(1).toLowerCase();
            if (k.startsWith("character")) { forwards = backups = monsters = true; }
            else if (k.startsWith("forward")) forwards = true;
            else if (k.startsWith("backup"))  backups  = true;
            else if (k.startsWith("monster")) monsters = true;
        }
        if (!forwards && !backups && !monsters) return null;

        // Every sentence after the choice has to be about what was chosen. One that is not happens
        // regardless, and the Summon is worth casting for it alone.
        for (String sentence : text.substring(m.end()).split("(?<=[.!])\\s+")) {
            if (sentence.isBlank()) continue;
            if (!REFERS_TO_CHOSEN.matcher(sentence).find()) return null;
        }

        boolean ownOnly = what.matches("(?i).*\\byou\\s+control\\b.*");
        boolean oppOnly = what.matches("(?i).*\\bopponent\\s+controls?\\b.*");
        return new SummonTargetNeed(forwards, backups, monsters,
                ownOnly && !oppOnly, oppOnly && !ownOnly);
    }

    /**
     * The field cards that would satisfy a Summon's mandatory choice.
     *
     * @param forwards  a Forward would answer the choice
     * @param backups   a Backup would
     * @param monsters  a Monster would
     * @param ownOnly   the choice is restricted to the caster's own field
     * @param oppOnly   the choice is restricted to their opponent's field
     */
    public record SummonTargetNeed(boolean forwards, boolean backups, boolean monsters,
            boolean ownOnly, boolean oppOnly) {}

    /** Returns {@code true} if the effect text matches a "cancel 1 auto-ability" summon effect. */
    public static boolean cancelsAutoAbility(String effectText) {
        return CANCEL_AUTO_ABILITY_DAMAGE_IF_FORWARD.matcher(effectText.trim()).find();
    }

    public static boolean hasPlayerCannotCastSummonsFieldAbility(CardData card) {
        for (FieldAbility fa : card.fieldAbilities()) {
            if (PLAYERS_CANNOT_CAST_SUMMONS.matcher(fa.effectText().trim()).matches()) return true;
        }
        return false;
    }

    /** Returns {@code true} if the card has the "BZ Summons cannot be removed by opponent" field ability. */
    public static boolean hasBzSummonRfgProtection(CardData card) {
        for (FieldAbility fa : card.fieldAbilities())
            if (FA_BZ_SUMMONS_PROTECTED_FROM_OPP_RFG.matcher(fa.effectText()).find()) return true;
        return false;
    }

    /**
     * Returns {@code true} if the card has the "all cards in your Break Zone cannot be removed from
     * the game by your opponent's Summons or abilities" field ability (Lenna 18-100L,
     * Ultimecia 22-073L) — the every-card widening of {@link #hasBzSummonRfgProtection}.
     */
    public static boolean hasBzCardRfgProtection(CardData card) {
        for (FieldAbility fa : card.fieldAbilities())
            if (FA_BZ_CARDS_PROTECTED_FROM_OPP_RFG.matcher(fa.effectText()).find()) return true;
        return false;
    }

    /**
     * Returns {@code true} if the card has the "all cards in your Break Zone cannot be chosen by
     * your opponent's Summons or abilities" field ability (Kalmia 18-090R).
     */
    public static boolean hasBzCardChoiceProtection(CardData card) {
        for (FieldAbility fa : card.fieldAbilities())
            if (FA_BZ_CARDS_PROTECTED_FROM_OPP_CHOICE.matcher(fa.effectText()).find()) return true;
        return false;
    }

    /**
     * Returns {@code true} if the card has a field ability of the form
     * "[CardName] cannot be chosen by Summons." — i.e., a permanent self-targeting
     * immunity to any Summon while the card is on the field.
     */
    static boolean hasCannotBeChosenByAnySummonFieldAbility(CardData card) {
        for (FieldAbility fa : card.fieldAbilities()) {
            Matcher m = STANDALONE_NAMED_CANNOT_BE_CHOSEN_ANY_SUMMON.matcher(fa.effectText());
            if (m.find() && m.group("name").trim().equalsIgnoreCase(card.name())) return true;
        }
        return false;
    }

    /**
     * Whether {@code card} prints "[Self] cannot be chosen by [your] opponent's Summons/abilities."
     * as a standing field ability covering {@code bySummon} — Terra 1-046H, Seiryu 16-049R and the
     * 23 other printings of the same sentence.
     *
     * <p>The opponent-scoped sibling of {@link #hasCannotBeChosenByAnySummonFieldAbility}. Both are
     * read per choice rather than applied once, because the immunity lasts exactly as long as the
     * card is on the field. The scope word decides which half of the question it answers: a
     * printing naming only Summons does not stop an ability, and the other way round.
     *
     * <p>Self-named, checked by equality: a card's own name in its own text means that card, so a
     * second printing sharing the name is not covered by this one.
     */
    static boolean hasCannotBeChosenByOppFieldAbility(CardData card, boolean bySummon) {
        if (card == null) return false;
        for (FieldAbility fa : card.fieldAbilities()) {
            Matcher m = FA_SELF_CANNOT_BE_CHOSEN_BY_OPP.matcher(fa.effectText().trim());
            if (!m.matches() || !m.group("name").trim().equalsIgnoreCase(card.name())) continue;
            String scope = m.group("scope").toLowerCase(Locale.ROOT);
            if (bySummon ? scope.contains("summon") : scope.contains("abilit")) return true;
        }
        return false;
    }

    /**
     * Returns {@code true} if the card has a field ability of the form
     * "[CardName] cannot be chosen by Summons or abilities that share its Element."
     * Immunity is evaluated dynamically against the resolving card's element.
     */
    static boolean hasCannotBeChosenByOwnElementFieldAbility(CardData card) {
        for (FieldAbility fa : card.fieldAbilities()) {
            Matcher m = STANDALONE_NAMED_CANNOT_BE_CHOSEN_BY_OWN_ELEMENT.matcher(fa.effectText());
            if (m.find() && m.group("name").trim().equalsIgnoreCase(card.name())) return true;
        }
        return false;
    }

    /**
     * The Element whose Summons and abilities may not choose {@code card}, read off a field
     * ability of the form "[CardName] cannot be chosen by [Element] Summons or [Element]
     * abilities" (Royal Ripeness 5-007H); {@code null} when the card prints no such shield.
     *
     * <p>Self-named like the rest of this family: the text has to name its own carrier, so a
     * granted copy naming someone else does not shield the card holding it.
     */
    static String cannotBeChosenByElementFieldAbility(CardData card) {
        for (FieldAbility fa : card.fieldAbilities()) {
            Matcher m = STANDALONE_NAMED_CANNOT_BE_CHOSEN_BY_ELEMENT.matcher(fa.effectText());
            if (m.find() && m.group("name").trim().equalsIgnoreCase(card.name()))
                return cap(m.group("element"));
        }
        return null;
    }

    /**
     * Returns {@code true} if the card has a field ability of the form
     * "[CardName] cannot be chosen by a Multi-Element Forward's ability."
     * Whether the immunity applies is decided per resolution, against the resolving card.
     */
    static boolean hasCannotBeChosenByMultiElementForwardAbility(CardData card) {
        for (FieldAbility fa : card.fieldAbilities()) {
            Matcher m = STANDALONE_NAMED_CANNOT_BE_CHOSEN_BY_MULTI_ELEMENT_FORWARD.matcher(fa.effectText());
            if (m.find() && m.group("name").trim().equalsIgnoreCase(card.name())) return true;
        }
        return false;
    }

    /**
     * Strips a leading "You can only cast [Name] during your turn / Main Phase." sentence.
     *
     * <p>The restriction is read off the card into a {@link CastRestriction} and enforced when the
     * card is cast, so by the time the effect resolves it is a sentence the effect parsers have to
     * step over rather than anything they act on. Leaving it in front is what made Syldra 29-101H
     * unparseable while the identical effect on a card without the restriction parsed fine.
     */
    static String stripCastTimingPrefix(String text) {
        if (text == null) return null;
        return text.trim().replaceFirst(
                "(?i)^You\\s+can\\s+only\\s+cast\\s+[^.]+?\\s+during\\s+your\\s+(?:turn|Main\\s+Phase)[.!]?\\s*",
                "").trim();
    }

    static String cap(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase();
    }

    /** Appends {@code term} ("Job X" or "Card Name X") to the appropriate pipe-separated list. */
    static void appendFilterTerm(StringBuilder jobs, StringBuilder names, String term) {
        if (term == null || term.isBlank()) return;
        String trimmed = term.trim();
        Matcher jm = Pattern.compile("(?i)^Job\\s+(?<val>.+)$").matcher(trimmed);
        Matcher nm = Pattern.compile("(?i)^Card\\s+Name\\s+(?<val>.+)$").matcher(trimmed);
        if (jm.matches()) {
            if (jobs.length() > 0)  jobs.append('|');
            jobs.append(jm.group("val").trim());
        } else if (nm.matches()) {
            if (names.length() > 0) names.append('|');
            names.append(nm.group("val").trim());
        }
    }

    /**
     * Parses the standalone "Name 1 Job" — Jack Garland 27-111L's entry trigger.
     *
     * <p>The named Job is recorded against {@code source}, which is the whole point of this
     * printing: Jack Garland names a Job here and reads it back in two later abilities, where
     * every other printing of "name 1 Job" spends it in the sentence that names it.
     * {@link ActionResolverState#tryParseNamedJobReference} is the reader.
     */
    /**
     * A Job token used only to check, at parse time, that the text would read once a Job is
     * substituted into it. Never reaches a log or the board: the consumer built with it is
     * discarded, and the one that runs is built from the Job actually named.
     */
    static final String PLACEHOLDER_JOB = "Knight";

    /**
     * {@code text} with "&lt;noun&gt; with the named Job" rewritten to "Job &lt;job&gt;
     * &lt;noun&gt;" — the spelling the choose grammar already reads.
     */
    static String namedJobText(String text, String job) {
        return NAMED_JOB_REFERENCE.matcher(text)
                .replaceAll("Job " + Matcher.quoteReplacement(job) + " ${noun}");
    }

    private static Consumer<GameContext> tryParseNameJob(String text, CardData source) {
        if (!NAME_JOB_STANDALONE.matcher(text.trim()).find()) return null;
        return ctx -> {
            ctx.logEntry("Effect: Name 1 Job");
            // The naming card is the one that punishes the named Job, so an AI shortlists the
            // Jobs across the table rather than its own — see selectJobNamedAgainstOpponent.
            String job = ctx.selectJobNamedAgainstOpponent();
            if (job == null || job.isBlank()) return;
            ctx.logEntry("Named Job: " + job);
            if (source != null) ctx.recordNamedJob(source, job);
        };
    }

    static java.util.Set<String> parseExcludeElements(String excludeStr) {
        if (excludeStr == null || excludeStr.isBlank()) return java.util.Collections.emptySet();
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        for (String part : excludeStr.split("(?i)\\s+(?:or|and)\\s+|,\\s*"))
            out.add(part.trim());
        return out;
    }

    /**
     * Parses Gogo's "Mimic" — delegates to {@link GameContext#useSpecialAbilityUsedThisTurn}, which
     * lets the acting player replay a special ability used this turn (name-substituted to the mimic).
     */
    private static Consumer<GameContext> tryParseUseSpecialAbilityUsedThisTurn(String text, CardData source) {
        Matcher m = USE_SPECIAL_ABILITY_USED_THIS_TURN.matcher(text.trim());
        if (!m.matches()) return null;
        String excluded = m.group("excluded");
        String excludedName = excluded == null ? null : excluded.trim();
        return ctx -> ctx.useSpecialAbilityUsedThisTurn(source, excludedName);
    }

    /**
     * {@code effectText} re-pointed from the card that printed it at the card now using it: every
     * mention of {@code printedName} becomes {@code userName}.
     *
     * <p>An ability lent across cards keeps naming its original owner — Odin (XVI) 24-112L's Iron
     * Flash reads "Activate Odin (XVI). Odin (XVI) can attack once more this turn.", and a card
     * that borrowed it would activate an Odin that is not on the field. Both borrowers do the same
     * rewrite: Gogo's Mimic, replaying a special used this turn, and Clive 26-005H, holding the
     * specials of the Eikons removed from the game.
     *
     * <p>Literal replacement, not word-bounded: the printed names this sees are full card names
     * (parentheses and all), and the resolver matches them the same way.
     *
     * <p>Returns the text unchanged when the two names are equal, so a card borrowing from a copy
     * of itself is a no-op rather than a self-substitution.
     */
    static String substituteSourceName(String effectText, String printedName, String userName) {
        if (effectText == null || printedName == null || userName == null) return effectText;
        if (printedName.equals(userName)) return effectText;
        return effectText.replace(printedName, userName);
    }

    /** True when {@code text} is Gogo's "Mimic" effect (see {@link #USE_SPECIAL_ABILITY_USED_THIS_TURN}). */
    static boolean isUseSpecialAbilityUsedThisTurnEffect(String text) {
        return text != null && USE_SPECIAL_ABILITY_USED_THIS_TURN.matcher(text.trim()).matches();
    }

    /**
     * True when a captured {@code verb} group is the "Reveal" wording rather than "Look at".
     * Reveal shows the cards to both players; look at keeps them private to the controller.
     */
    static boolean isRevealWording(String verb) {
        return verb != null && verb.trim().toLowerCase(Locale.ROOT).startsWith("reveal");
    }

    /**
     * Names the add-1-to-hand look for the pattern-reporting helpers.  Callers check the parser
     * first, so any text left after the clause is the EX Burst rider it accepted.
     */
    private static String lookAddToHandRestBottomPatternName(String text) {
        Matcher m = LOOK_TOP_DECK_ADD_TO_HAND_REST_BOTTOM.matcher(text);
        return m.find() && !text.substring(m.end()).trim().isEmpty()
                ? "LookTopDeckAddToHandRestBottom + AddedCardExBurst"
                : "LookTopDeckAddToHandRestBottom";
    }

    /**
     * Chains a trailing "Then, [effect]" sentence onto an already-parsed primary effect.
     * The ordering dialogs this follows are modal, so the follow-on effect runs only once the
     * player has finished with the primary one.
     *
     * @param base   the parsed primary effect
     * @param tail   the text following the primary clause's match
     * @param source the card that owns the ability
     * @return {@code base} when {@code tail} holds no "Then," sentence; {@code base} followed by
     *         the parsed sentence when it is understood; {@code null} when it is not — a
     *         half-understood ability is reported as unparsed rather than silently dropping half
     *         of what the card does
     */
    static Consumer<GameContext> appendThenClause(
            Consumer<GameContext> base, String tail, CardData source) {
        Matcher m = TRAILING_THEN_CLAUSE.matcher(tail);
        if (!m.matches()) return base;
        Consumer<GameContext> then = parse(m.group("rest").trim(), source);
        return then == null ? null : base.andThen(then);
    }

    /**
     * Describes Kefka's counter burn with the payoff's own description, which is what says what the
     * chosen Forwards are actually dealt. Read at a representative count — the sentence the payoff
     * chain sees differs from turn to turn only in that number.
     */
    private static String removeAnyCountersDescription(String effectText, CardData source) {
        Matcher m = REMOVE_ANY_COUNTERS_THEN_CHOOSE_SAME_NUMBER.matcher(effectText.trim());
        if (!m.matches()) return "RemoveAnyCountersThenChooseSameNumber";
        String inner = fullDescription(
                "choose up to 2 " + m.group("noun").trim() + ". " + m.group("tail").trim(), source);
        return "RemoveAnyCountersThenChooseSameNumber"
                + (inner != null ? " / " + inner : "");
    }

    /**
     * Names the Break Zone removal, telling the bare removal apart from the two payoffs that can
     * trail it — Kefka's counter rider, and any other "Then, …" sentence the chain understands.
     */
    private static String removeFromBreakZonePatternName(String effectText) {
        Matcher m = REMOVE_FROM_BREAK_ZONE_FROM_GAME.matcher(effectText.trim());
        if (!m.lookingAt()) return "RemoveFromBreakZoneFromGame";
        String tail = effectText.trim().substring(m.end()).trim();
        if (tail.isEmpty()) return "RemoveFromBreakZoneFromGame";
        if (THEN_PLACE_COUNTERS_PER_CARD_REMOVED.matcher(tail).matches())
            return "RemoveFromBreakZoneFromGame + PlaceCountersPerCardRemoved";
        return "RemoveFromBreakZoneFromGame + Then";
    }

    /** {@link #removeFromBreakZonePatternName} with the trailing sentence's own description. */
    private static String removeFromBreakZoneDescription(String effectText, CardData source) {
        Matcher m = REMOVE_FROM_BREAK_ZONE_FROM_GAME.matcher(effectText.trim());
        if (!m.lookingAt()) return "RemoveFromBreakZoneFromGame";
        String tail = effectText.trim().substring(m.end()).trim();
        if (tail.isEmpty() || THEN_PLACE_COUNTERS_PER_CARD_REMOVED.matcher(tail).matches())
            return removeFromBreakZonePatternName(effectText);
        Matcher then = TRAILING_THEN_CLAUSE.matcher(tail);
        String inner = then.matches() ? fullDescription(then.group("rest").trim(), source) : null;
        return "RemoveFromBreakZoneFromGame + " + (inner != null ? inner : "Then");
    }

    /**
     * Returns the effect text of a "Then, [effect]" sentence trailing {@code primary}'s match
     * within {@code text}, or {@code null} when {@code primary} does not match or nothing follows
     * it.  Used by the pattern-reporting helpers to name both halves of a chained ability.
     */
    private static String trailingThenText(String text, Pattern primary) {
        Matcher m = primary.matcher(text);
        if (!m.find()) return null;
        Matcher then = TRAILING_THEN_CLAUSE.matcher(text.substring(m.end()));
        return then.matches() ? then.group("rest").trim() : null;
    }

    /** Parses Anima 19-123H's "remove the top card… Then, if there are N or more removed…" compound. */
    private static Consumer<GameContext> tryParseRemoveTopThenPileThreshold(String text, CardData source) {
        if (source == null) return null;
        Matcher m = REMOVE_TOP_THEN_IF_PILE_AT_LEAST.matcher(text.trim());
        if (!m.matches()) return null;
        String named = m.group("name").trim();
        if (!named.equalsIgnoreCase(source.name()) && !isSelfReference(named)) return null;
        String removedStr = m.group("removed");
        int removeCount = removedStr != null ? Integer.parseInt(removedStr) : 1;
        int threshold   = Integer.parseInt(m.group("threshold"));
        return ctx -> {
            ctx.logEntry("Effect: Remove top " + removeCount + " card(s) of deck from game, then check for "
                    + threshold + "+ removed by " + source.name());
            ctx.removeTopCardsOfDeckFromGame(removeCount, source);
            int pile = ctx.cardsRemovedBySourceCount(source);
            if (pile < threshold) {
                ctx.logEntry("Effect: only " + pile + " card(s) removed by " + source.name()
                        + " (need " + threshold + ") — no payoff");
                return;
            }
            ctx.logEntry("Effect: " + pile + " cards removed by " + source.name()
                    + " — adding them to hand and breaking all opposing Forwards");
            ctx.addCardsRemovedBySourceToHand(source, Integer.MAX_VALUE);
            ctx.applyMassFieldEffect(GameContext.MassAction.BREAK, true, false, false,
                    true, false, null, -1, null, -1, null, null);
        };
    }

    private static Consumer<GameContext> tryParseAllMonstersTemporaryForward(String text) {
        Matcher m = ALL_MONSTERS_BECOME_FORWARDS_UNTIL_EOT_PATTERN.matcher(text.trim());
        if (!m.find()) return null;
        int power = Integer.parseInt(m.group("power"));
        return ctx -> {
            ctx.logEntry("Effect: All Monsters you control become Forwards with " + power + " power until end of turn");
            ctx.makeAllMonstersTemporaryForwards(power);
        };
    }

    private static Consumer<GameContext> tryParseBecomeForwardUntilEot(String text, CardData source) {
        if (source == null) return null;

        Matcher mAtk = BECOME_FORWARD_AND_ATTACK_TRIGGER.matcher(text);
        if (mAtk.find()) {
            int power = Integer.parseInt(mAtk.group("power"));
            String attackEffectText = mAtk.group("attackEffect").trim();
            Consumer<GameContext> attackEffect = parse(attackEffectText, source);
            if (attackEffect != null) {
                return ctx -> {
                    ctx.logEntry(source.name() + " becomes a Forward with " + power + " power until end of turn");
                    ctx.makeMonsterTemporaryForward(source, power);
                    ctx.logEntry(source.name() + " gains 'When attacks: " + attackEffectText + "'");
                    ctx.addTempAttackTrigger(source, attackEffect);
                };
            }
        }

        Matcher mBlk = BECOME_FORWARD_AND_BLOCK_TRIGGER.matcher(text);
        if (mBlk.find()) {
            int power = Integer.parseInt(mBlk.group("power"));
            String blockEffectText = mBlk.group("blockEffect").trim();
            Consumer<GameContext> blockEffect = parse(blockEffectText, source);
            boolean alsoWhenBlocked = mBlk.group("isblocked") != null;
            if (blockEffect != null) {
                return ctx -> {
                    ctx.logEntry(source.name() + " becomes a Forward with " + power + " power until end of turn");
                    ctx.makeMonsterTemporaryForward(source, power);
                    ctx.logEntry(source.name() + " gains 'When "
                            + (alsoWhenBlocked ? "blocks or is blocked" : "blocks")
                            + ": " + blockEffectText + "'");
                    ctx.addTempBlockTrigger(source, blockEffect);
                    // Registered on both events when the clause names both: they fire from
                    // different places, and the block map alone never sees a Forward that
                    // attacked and was blocked.
                    if (alsoWhenBlocked) ctx.addTempIsBlockedTrigger(source, blockEffect);
                };
            }
        }

        Matcher mBz = BECOME_FORWARD_AND_BZ_ACTION.matcher(text);
        if (mBz.find()) {
            int power = Integer.parseInt(mBz.group("power"));
            String bzName = mBz.group("bzName").trim();
            String bzEffectText = mBz.group("bzEffect").trim();
            if (parse(bzEffectText, source) != null) {
                return ctx -> {
                    ctx.logEntry(source.name() + " becomes a Forward with " + power + " power until end of turn");
                    ctx.makeMonsterTemporaryForward(source, power);
                    ctx.grantTempBzActionAbility(source, bzName, bzEffectText);
                };
            }
        }

        Matcher m = BECOME_FORWARD_UNTIL_EOT_PATTERN.matcher(text);
        if (!m.find()) return null;
        int power = Integer.parseInt(m.group("power"));
        boolean breakAtEot = AT_END_OF_TURN_BREAK_SOURCE.matcher(text).find();
        return ctx -> {
            ctx.logEntry(source.name() + " becomes a Forward with " + power + " power until end of turn");
            ctx.makeMonsterTemporaryForward(source, power);
            if (breakAtEot) ctx.breakSourceAtEndOfTurn(source);
        };
    }

    /** Returns {@code true} when {@code text} is an "until EOT, becomes a Forward" action-ability effect. */
    static boolean isBecomeForwardUntilEotEffect(String text, CardData source) {
        return tryParseBecomeForwardUntilEot(text, source) != null;
    }

    /**
     * Returns {@code true} when {@code text} is a standalone "source gains +N power until end of
     * turn" self-boost (named subject, not a pronoun like "it"/"they").  Used by the CPU to avoid
     * wasting hand cards on a power boost that provides no combat benefit.
     */
    static boolean isTempSelfPowerBoostEffect(String text, CardData source) {
        if (source == null) return false;
        Matcher m = SELF_POWER_BOOST.matcher(text);
        if (!m.find()) return false;
        String subject = m.group("selfsubject").trim();
        if (subject.equalsIgnoreCase("it") || subject.equalsIgnoreCase("they")) return false;
        return subject.equalsIgnoreCase(source.name());
    }

    /** Returns true when {@code text} is a "gain 《C》 for each CP paid as X" effect. */
    static boolean isGainCrystalPerX(String text) {
        return GAIN_CRYSTAL_PER_X.matcher(text).find();
    }

    /**
     * Returns {@code true} when {@code text} returns Forward(s) to their owner's hand — a bounce
     * such as "Choose 1 Forward. Return it to its owner's hand." — regardless of whether the target
     * is the controller's own or any Forward. Used by the CPU to gate self-sacrifice bounce abilities.
     */
    static boolean isReturnForwardToHandEffect(String text) {
        if (text == null) return false;
        String t = text.toLowerCase(Locale.ROOT);
        return t.contains("forward") && t.contains("return") && t.contains("hand");
    }

    /**
     * Returns {@code true} when {@code text} bounces only Forward(s) the controller controls — a
     * self-bounce such as "Choose 1 Forward you control. Return it to its owner's hand." Implies
     * {@link #isReturnForwardToHandEffect}. Such a play is never proactively useful (it costs a card
     * for no board gain), so the CPU only performs it reactively to save a Forward from removal.
     */
    static boolean isReturnOwnForwardToHandEffect(String text) {
        if (!isReturnForwardToHandEffect(text)) return false;
        String t = text.toLowerCase(Locale.ROOT);
        return t.contains("forward you control") || t.contains("forwards you control");
    }

    /**
     * Returns {@code true} when every character {@code text} can choose is a Forward its controller
     * controls, so the whole effect no-ops while that player has none on the field.  Lets the CPU
     * avoid paying an activation cost for nothing.
     */
    static boolean targetsOnlyOwnForwards(String text) {
        if (text == null) return false;
        String t = text.toLowerCase(Locale.ROOT);
        if (!t.contains("forward you control") && !t.contains("forwards you control")) return false;
        if (t.contains("opponent controls") || t.contains("opponent's field")) return false;
        // Must act on the Forwards standing right now.  A turn-long conditional ("During this turn,
        // if a Forward you control is dealt damage…") can still pay off for a Forward played later
        // in the same turn, so an empty board is not proof it will do nothing.
        return t.contains("choose") || t.contains("all the forwards you control")
                || t.contains("all forwards you control");
    }

    /**
     * Returns {@code true} when {@code text}'s only benefit is shielding a Forward its controller
     * controls from the opponent's interaction (Krile (XIV) 6-071H: "Choose 1 Forward you control.
     * During this turn, it cannot be returned to its owner's hand by your opponent's Summons or
     * abilities.").
     *
     * <p>Such a shield gains nothing at the moment it resolves — it only pays off while an
     * opponent's effect is already on the stack.  The CPU passes priority rather than responding,
     * so activating one proactively is a wasted cost.  Same reasoning as
     * {@link #isReturnOwnForwardToHandEffect}.
     */

    static boolean isOwnForwardProtectionEffect(String text) {
        if (!targetsOnlyOwnForwards(text)) return false;
        // A shield bundled with an immediate benefit (20-109H's "+1000 power and …") is still
        // worth using proactively — only a pure shield is reactive-only.
        if (IMMEDIATE_OWN_BENEFIT.matcher(text).find()) return false;
        return OWN_FORWARD_PROTECTION.matcher(text).find();
    }

    /**
     * The fixed damage a "…Deal it/them N damage" effect deals to each Character it chooses, or
     * {@code 0} when {@code text} carries no such clause — including the amounts computed at
     * resolution time from board state ("damage equal to its power"), which nothing here can
     * predict.
     *
     * <p>Read two ways, both advisory: {@link #preSelectTargets} passes it to the context so the
     * AI aims its selection at a Character the damage would break, and {@link ComputerPlayer}
     * weighs it against the board to decide whether an ability is worth its cost at all. Where
     * the text raises the amount conditionally ("…deal it 9000 damage instead"), the base is what
     * gets reported: it is the amount every branch is sure to deal.
     */
    static int chooseTargetDamageAmount(String text) {
        if (text == null) return 0;
        Matcher m = FOLLOWUP_DAMAGE.matcher(text);
        return m.find() ? Integer.parseInt(m.group("amount")) : 0;
    }

    private static Consumer<GameContext> tryParseExtraTurnThenLose(String text) {
        if (!EXTRA_TURN_THEN_LOSE.matcher(text).find()) return null;
        return ctx -> {
            ctx.logEntry("Effect: Take 1 more turn — you lose at the end of that turn");
            ctx.takeExtraTurnThenLose();
        };
    }

    /** Parses "[name] can attack once more this turn." */
    private static Consumer<GameContext> tryParseAttackOnceMore(String text) {
        Matcher m = ATTACK_ONCE_MORE.matcher(text);
        if (!m.find()) return null;
        String name = m.group("name").trim();
        return ctx -> {
            ctx.logEntry("Effect: " + name + " can attack once more this turn");
            ctx.grantAttackOnceMore(name);
        };
    }

    /** Parses "During this turn, your opponent may only declare attack once." */
    private static Consumer<GameContext> tryParseOpponentAttackOnceThisTurn(String text) {
        if (!OPPONENT_ATTACK_ONCE_THIS_TURN.matcher(text).find()) return null;
        return ctx -> ctx.limitOpponentAttackDeclarationsThisTurn(1);
    }

    /**
     * Parses "Remove &lt;cardName&gt; from [the] Battle." — removes the named card from the current
     * combat before damage resolves (Escape-type ability).
     */
    private static Consumer<GameContext> tryParseRemoveFromBattle(String text) {
        Matcher m = REMOVE_FROM_BATTLE.matcher(text);
        if (!m.find()) return null;
        String cardName = m.group("card").trim();
        return ctx -> {
            ctx.logEntry("Effect: " + cardName + " escapes from the Battle");
            ctx.removeFromBattle(cardName);
        };
    }

    /**
     * Turns a printed card-name list into the pipe-separated form the filters use downstream:
     * "Alisaie or Card Name Alphinaud" → {@code "Alisaie|Alphinaud"}. One separator covers every
     * printed joiner — ", ", ", or ", " or ", " and/or " — and a single name passes through unchanged.
     */
    static String splitCardNameList(String printedNames) {
        return String.join("|",
                printedNames.trim().split("(?i)\\s*,?\\s*(?:(?:and/)?or\\s+)?Card\\s+Name\\s+"));
    }

    /**
     * Splits a comma/or-separated identity list that mixes the two kinds of term — "Chloe, Job
     * Chocobo or Card Name Chocobo" (Billy 29-048C) — into its card names and its jobs.
     *
     * <p>{@link #splitCardNameList} reads every term as a name, so the job in the middle became
     * part of one: Billy searched for a card called "Chloe, Job Chocobo" and for one called
     * "Chocobo", and never for the Job. The two halves come back separately because the search
     * takes them as separate filters and reads two filled filters as alternatives.
     *
     * <p>Splits only where a separator is followed by a "Card Name"/"Job" marker, so a comma
     * inside a printed name ("Cid, Lord of Levin") does not split the list. The leading term is
     * unmarked — the pattern consumed the "Card Name" that introduced it — so one is put back
     * before splitting.
     *
     * @return {@code {cardNames, jobs}}, each bar-joined, either {@code null} when that kind of
     *         term did not appear
     */
    static String[] splitCardNameAndJobList(String printedList) {
        List<String> names = new ArrayList<>();
        List<String> jobs  = new ArrayList<>();
        // The joiner is a comma, an "or", or the two together (Refia 10-128L prints "Arc,Card Name
        // Ingus, or Card Name Luneth" — no space after the first, an Oxford comma before the last).
        // Both alternatives consume a real character, so neither can split at a zero-width point.
        String[] terms = ("Card Name " + printedList.trim()).split(
                "(?i)\\s*,\\s*(?:(?:and/)?or\\s+)?(?=(?:Card\\s+Name|Job)\\s)"
                + "|\\s+(?:and/)?or\\s+(?=(?:Card\\s+Name|Job)\\s)");
        for (String raw : terms) {
            String term = raw.trim();
            if (term.regionMatches(true, 0, "Card Name ", 0, 10)) names.add(term.substring(10).trim());
            else if (term.regionMatches(true, 0, "Job ", 0, 4))   jobs.add(term.substring(4).trim());
        }
        return new String[] {
                names.isEmpty() ? null : String.join("|", names),
                jobs.isEmpty()  ? null : String.join("|", jobs) };
    }

    /**
     * Returns the cost value that appears most frequently among P1's current Forwards.
     * Used by the opponent AI in dual-number selection to target the ability user's cards.
     * Returns 0 when P1 has no Forwards on the field.
     */
    static int aiMostCommonP1ForwardCost(GameContext ctx) {
        java.util.Map<Integer, Integer> freq = new java.util.HashMap<>();
        for (int i = 0; i < ctx.p1ForwardCount(); i++)
            freq.merge(ctx.p1Forward(i).cost(), 1, Integer::sum);
        return freq.entrySet().stream()
                .max(java.util.Map.Entry.comparingByValue())
                .map(java.util.Map.Entry::getKey)
                .orElse(0);
    }

    /**
     * Prompts the activating player to choose targets for a "Choose N [targets]…" effect
     * <em>before</em> the ability is placed on the stack, so the selections can be stored in
     * {@link StackEntry#preSelectedTargets()} and later inspected (e.g. to enforce "that is
     * choosing a Forward you control" cancel filters).
     *
     * <p>Returns {@code null} when {@code effectText} does not match
     * {@link #CHOOSE_CHARACTER_PATTERN}, or when only break-zone selections would be needed
     * (those are deferred to resolution time since the zone state may change).
     */
    public static List<ForwardTarget> preSelectTargets(String effectText, CardData source, int xValue, GameContext ctx) {
        TargetSpec spec = targetSpec(effectText, source);
        if (spec == null || spec.zone() != null) return null;
        // Every activation-time selection funnels through here — action abilities, Summons and auto
        // abilities alike — so it is the one place that can tell the context what the cards it is
        // about to pick are in for. Ignored unless the picker is the AI.
        ctx.setAiDamageTargetHint(chooseTargetDamageAmount(effectText));
        return ctx.selectCharacters(spec.maxCount(), spec.upTo(), spec.opponentOnly(), spec.selfOnly(),
                spec.condition(), spec.element(), spec.costVal(), spec.costCmp(), spec.powerVal(),
                spec.powerCmp(), spec.inclForwards(), spec.inclBackups(), spec.inclMonsters(),
                spec.jobFilter(), spec.cardNameFilter(), spec.categoryFilter(), spec.excludeName(),
                spec.inclSummons(), spec.excludeElement(), spec.withoutMulticard());
    }

    /**
     * Decodes the target constraints of a "Choose N [targets]…" effect into a {@link TargetSpec},
     * or {@code null} when {@code effectText} does not match {@link #CHOOSE_CHARACTER_PATTERN}.
     *
     * <p>A choice naming a Break Zone is decoded like any other and reported through the spec's
     * {@code zone}. Refusing it here is what this used to do, which left the two callers that must
     * defer such a choice unable to tell "names the Break Zone" from "cannot be read at all" — and
     * the cast-legality check, which needs to tell them apart, unable to ask.
     *
     * <p>Split out of {@link #preSelectTargets} so the redirect path can replay the same
     * constraints against a replacement target instead of re-deriving them: two decodings of one
     * card text that could disagree is exactly the drift that makes "must be a valid choice"
     * enforceable in one place and not the other.
     */
    static TargetSpec targetSpec(String effectText, CardData source) {
        if (effectText == null || effectText.isBlank()) return null;
        String text = ELEM_TYPE_OR_ELEM_TYPE.matcher(effectText).replaceAll("$1 or $3 $2");
        text = escapePeriodInName(text, source);
        Matcher m = CHOOSE_CHARACTER_PATTERN.matcher(text);
        if (!m.find()) return null;

        boolean any          = m.group("anycount") != null;
        boolean upTo         = any || m.group("upto") != null;
        int     maxCount     = any ? Integer.MAX_VALUE : Integer.parseInt(m.group("count"));
        String  rawElement   = m.group("element");
        String  element      = rawElement != null && rawElement.contains(" or ")
                ? rawElement.replaceAll("(?i)\\s+or\\s+", "|") : rawElement;
        String  rawCondition  = m.group("condition");
        String  postCondition = m.group("postcondition");
        String  blockingName  = m.group("blockingname");
        String  blockingJob   = m.group("blockingjob");
        String  condition     = blockingName  != null ? "blocking:"     + blockingName.trim()
                              : blockingJob   != null ? "blocking-job:" + blockingJob.trim()
                              : postCondition != null ? "entered the field this turn"
                              : rawCondition;
        String  targets      = m.group("targets");
        String  tgtLower = targets.toLowerCase();
        String  jobFilter;
        String  cardNameFilter;
        boolean inclForwards;
        boolean inclBackups;
        boolean inclMonsters;

        if (tgtLower.startsWith("[job ")) {
            Matcher jm = JOB_BRACKET_PATTERN.matcher(targets);
            jobFilter      = jm.find() ? jm.group(1).trim() : null;
            cardNameFilter = null;
            inclForwards   = true;
            inclBackups    = false;
            inclMonsters   = false;
        } else if (tgtLower.startsWith("[card name ")) {
            Matcher nm = CARD_NAME_BRACKET_PATTERN.matcher(targets);
            cardNameFilter = nm.find() ? nm.group(1).trim() : null;
            jobFilter      = null;
            inclForwards   = true;
            inclBackups    = true;
            inclMonsters   = true;
        } else if (tgtLower.startsWith("card name ") && tgtLower.contains(" or job ")) {
            int orJobIdx = tgtLower.indexOf(" or job ");
            String cardNamePart = targets.substring("Card Name ".length(), orJobIdx).trim();
            cardNameFilter = cardNamePart.replaceAll("(?i)\\s+(?:Forwards?|Backups?|Monsters?|Characters?)$", "").trim();
            String jobPart = targets.substring(orJobIdx + " or job ".length()).trim();
            jobFilter    = jobPart.replaceAll("(?i)\\s+(?:Forwards?|Backups?|Monsters?|Characters?)$", "").trim();
            inclForwards = tgtLower.contains("forward");
            inclBackups  = tgtLower.contains("backup");
            inclMonsters = tgtLower.contains("monster");
        } else if (tgtLower.startsWith("card name ")) {
            String rest = targets.substring("Card Name ".length());
            String[] nameParts = rest.split("(?i)\\s+or\\s+Card\\s+Name\\s+");
            cardNameFilter = String.join("|", nameParts).trim();
            jobFilter      = null;
            inclForwards   = true;
            inclBackups    = true;
            inclMonsters   = true;
        } else if (tgtLower.startsWith("job ") && tgtLower.contains("or card name ")) {
            int orCnIdx    = tgtLower.indexOf("or card name ");
            String rawJob  = targets.substring("Job ".length(), orCnIdx)
                                    .trim().replaceAll("(?i)\\s*and\\s*/\\s*$", "").trim();
            List<String> jobParts = new ArrayList<>();
            for (String p : rawJob.split("(?i)\\s+or\\s+Job\\s+")) jobParts.add(p.trim());
            jobFilter      = String.join("|", jobParts);
            cardNameFilter = targets.substring(orCnIdx + "or card name ".length()).trim();
            inclForwards   = true;
            inclBackups    = true;
            inclMonsters   = true;
        } else if (tgtLower.startsWith("job ")) {
            List<String> jobs = new ArrayList<>();
            Matcher wm = JOB_WRITTEN_SEGMENT.matcher(targets);
            while (wm.find()) jobs.add(wm.group(1).trim());
            boolean bareJob = jobs.isEmpty();
            if (bareJob)
                for (String p : targets.substring("Job ".length()).trim().split("(?i)\\s+or\\s+Job\\s+"))
                    jobs.add(p.trim());
            jobFilter      = String.join("|", jobs);
            cardNameFilter = null;
            inclForwards   = true;
            inclBackups    = bareJob;
            inclMonsters   = bareJob;
        } else {
            jobFilter      = null;
            cardNameFilter = null;
            boolean isGenericCard = tgtLower.equals("card") || tgtLower.equals("cards");
            inclForwards   = isGenericCard || tgtLower.contains("forward") || tgtLower.contains("character");
            inclBackups    = isGenericCard || tgtLower.contains("backup")  || tgtLower.contains("character");
            inclMonsters   = isGenericCard || tgtLower.contains("monster") || tgtLower.contains("character");
        }
        boolean inclSummons    = tgtLower.contains("summon")
                              || tgtLower.equals("card") || tgtLower.equals("cards");
        String  categoryFilter = m.group("category");
        String  excludeName    = restorePeriodInName(m.group("excludename") != null ? m.group("excludename").trim() : null, source);
        String  rawExcludeKw   = m.group("excludekw");
        boolean withoutMulticard = "Multicard".equalsIgnoreCase(rawExcludeKw != null ? rawExcludeKw.trim() : null);
        String  rawExcludeElem = m.group("excludeelem");
        String  excludeElem    = rawExcludeElem != null ? rawExcludeElem.trim() : null;
        String  costStr        = m.group("cost");
        String  costListStr    = m.group("costlist");
        String  rawCostCmp     = m.group("costcmp");
        int     costVal2       = costStr != null ? Integer.parseInt(costStr) : -1;
        String  costCmp;
        if (rawCostCmp != null && rawCostCmp.matches("\\d+")) {
            String tail = costListStr != null
                    ? costListStr.replaceAll("\\s+", "") + "," + rawCostCmp
                    : rawCostCmp;
            costCmp = "or_" + tail;
        } else {
            costCmp = rawCostCmp;
        }
        String  powerStr    = m.group("power");
        String  powerCmp    = m.group("powercmp");
        int     powerVal    = powerStr != null ? Integer.parseInt(powerStr) : -1;
        // Either slot may hold it — see the control2 comment on CHOOSE_CHARACTER_PATTERN.
        String  control     = m.group("control") != null ? m.group("control") : m.group("control2");
        boolean opponentOnly = control != null && !control.equalsIgnoreCase("you control");
        boolean selfOnly     = "you control".equalsIgnoreCase(control);
        String  zone        = m.group("zone");
        String  zoneLower   = zone == null ? null : zone.toLowerCase(Locale.ROOT);
        boolean bothZones   = zoneLower != null
                && (zoneLower.contains("either player") || zoneLower.contains("any player"));
        boolean opponentZone = zoneLower != null && !bothZones && zoneLower.contains("opponent");

        return new TargetSpec(maxCount, upTo, opponentOnly, selfOnly, condition, element,
                costVal2, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters,
                jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, excludeElem, withoutMulticard,
                zone, opponentZone, bothZones);
    }

    /**
     * The targets a Summon has to be able to choose for its cast to be legal, or {@code null} when
     * its text demands none.
     *
     * <p>A Summon that opens "Choose 1 Forward." cannot be cast while no Forward can be chosen —
     * unlike a Character, whose auto-abilities are free to enter and find nothing to fire at. Only
     * a mandatory opening choice counts, which is what {@link #OPENING_MANDATORY_CHOICE} says and
     * why this leans on it rather than on {@link #targetSpec} alone:
     *
     * <ul>
     *   <li>"Choose up to 2 Forwards" demands nothing — choosing none is a legal choice.
     *   <li>"Select 1 of the 2 following actions" picks a mode, not a target; its quoted actions
     *       do their own choosing when the Summon resolves, so the cast stands either way.
     *   <li>A "Choose" buried inside a granted ability ("gains \"When this Forward attacks, choose
     *       1 Forward…\"") belongs to that ability, not to this cast. The pattern is anchored, so
     *       neither of the last two reaches {@code targetSpec} here.
     * </ul>
     *
     * <p>A choice naming a Break Zone comes back like any other, reported through the spec's
     * {@code zone}: whether one is there is as answerable now as at resolution, even though
     * <em>which</em> one is picked has to wait. {@code null} is kept for a choice
     * {@code targetSpec} cannot decode, so an effect this cannot read imposes no restriction
     * rather than a wrongly empty one — the same choice {@link TargetSpec} documents for its
     * own {@code null}. Choices answered by the Stack or the Damage Zone are read by
     * {@link #mandatoryCastStackChoice} and {@link #mandatoryCastNeedsOwnDamageZoneCard}.
     */
    public static TargetSpec mandatoryCastTargetSpec(String summonEffect, CardData source) {
        if (summonEffect == null) return null;
        String text = stripExBurstPrefix(summonEffect).trim();
        if (!OPENING_MANDATORY_CHOICE.matcher(text).find()) return null;
        TargetSpec spec = targetSpec(text, source);
        return spec == null || spec.upTo() ? null : spec;
    }

    /**
     * The Stack entries a Summon has to be able to choose for its cast to be legal, or
     * {@code null} when its opening choice does not name one.
     *
     * <p>The Stack half of {@link #mandatoryCastTargetSpec}: "Choose 1 auto-ability. Cancel its
     * effect." is as unanswerable with an empty Stack as "Choose 1 Forward." is with an empty
     * board. {@link ActionResolverPatterns#CHOOSE_CHARACTER_PATTERN} cannot read these at all —
     * its list of things a choice can name is card kinds, and an ability waiting to resolve is
     * not a card in any zone that pattern scans.
     *
     * <p>{@link #stackCancelFilter} decodes the cancels among them and is preferred where it
     * does, since it also reads the "choosing a Character you control" qualifier. It is consulted
     * only once the opening choice is known to name a Stack entry: it is unanchored, and a text
     * that chooses a Forward and cancels something later would otherwise be read as choosing off
     * the Stack. What is left is the choice that does something other than cancel (Zalera 25-088H
     * puts the triggering Forward into the Break Zone), which the entry kind alone answers.
     *
     * <p>A qualifier past the kind ("triggered from a Forward") is not enforced, matching what
     * {@code CANCEL_ABILITY_ON_STACK} does with the same phrase: this decides whether a cast may
     * happen, and being narrower than the engine can actually verify would refuse legal ones.
     */
    static Predicate<StackEntry> mandatoryCastStackChoice(String summonEffect, boolean casterIsP1) {
        if (summonEffect == null) return null;
        String text = stripExBurstPrefix(summonEffect).trim();
        Matcher opening = OPENING_MANDATORY_CHOICE.matcher(text);
        if (!opening.find()) return null;
        Matcher kind = OPENING_CHOICE_ON_STACK.matcher(opening.group("what").trim());
        if (!kind.find()) return null;
        Predicate<StackEntry> cancel = stackCancelFilter(text, casterIsP1);
        if (cancel != null) return cancel;
        String types = kind.group("types");
        return types != null ? parseAbilityTypeFilter(types) : null;
    }

    /**
     * Whether the Summon's opening choice is answered by a card in the caster's own Damage Zone
     * — Ark 23-113R, which cannot be cast before its caster has taken any damage.
     */
    static boolean mandatoryCastNeedsOwnDamageZoneCard(String summonEffect) {
        return summonEffect != null
                && OPENING_CHOICE_FROM_DAMAGE_ZONE.matcher(stripExBurstPrefix(summonEffect).trim()).find();
    }

    static List<ForwardTarget> selectTargets(GameContext ctx,
            int maxCount, boolean upTo, boolean opponentOnly, boolean selfOnly,
            String condition, String element, String zone, boolean opponentZone,
            int costVal, String costCmp, int powerVal, String powerCmp,
            boolean inclForwards, boolean inclBackups, boolean inclMonsters,
            String jobFilter, String cardNameFilter, String categoryFilter, String excludeName, boolean inclSummons,
            String excludeElement, boolean withoutMulticard) {
        return selectTargets(ctx, maxCount, upTo, opponentOnly, selfOnly, condition, element, zone, opponentZone, false,
                costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters,
                jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, excludeElement, withoutMulticard);
    }

    static List<ForwardTarget> selectTargets(GameContext ctx,
            int maxCount, boolean upTo, boolean opponentOnly, boolean selfOnly,
            String condition, String element, String zone, boolean opponentZone, boolean bothZones,
            int costVal, String costCmp, int powerVal, String powerCmp,
            boolean inclForwards, boolean inclBackups, boolean inclMonsters,
            String jobFilter, String cardNameFilter, String categoryFilter, String excludeName, boolean inclSummons,
            String excludeElement, boolean withoutMulticard) {
        List<ForwardTarget> preloaded = ctx.consumePreloadedTargets();
        if (preloaded != null) {
            ctx.recordChosenTargets(preloaded);
            return applyArmedMarks(ctx, preloaded);
        }
        List<ForwardTarget> result = zone != null
                ? ctx.selectCharactersFromBreakZone(maxCount, upTo, opponentZone, bothZones, condition, element,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, excludeElement, withoutMulticard)
                : ctx.selectCharacters(maxCount, upTo, opponentOnly, selfOnly, condition, element,
                        costVal, costCmp, powerVal, powerCmp, inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, excludeName, inclSummons, excludeElement, withoutMulticard);
        ctx.recordChosenTargets(result);
        return applyArmedMarks(ctx, result);
    }

    /**
     * Applies any delayed-trigger mark armed for this selection to {@code targets}, then returns
     * them unchanged. Applying it here — between choosing the targets and the ability acting on
     * them — is what lets "When it is put from the field into the Break Zone this turn, …" survive
     * a primary that breaks the target outright.
     */
    private static List<ForwardTarget> applyArmedMarks(GameContext ctx, List<ForwardTarget> targets) {
        int bzDraw = ctx.consumeDrawOnFieldToBzMark();
        if (bzDraw > 0) targets.forEach(t -> ctx.markTargetDrawOnFieldToBzThisTurn(t, bzDraw));
        return targets;
    }
}
