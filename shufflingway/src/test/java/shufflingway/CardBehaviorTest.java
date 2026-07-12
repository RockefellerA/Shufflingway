package shufflingway;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

/**
 * Consolidated behavioral tests for one-off card-specific action-ability logic — each section
 * below targets a single card or narrow bug fix, exercised against a mocked or minimally
 * constructed {@link GameContext}/{@link CardData} rather than just asserting that the text
 * parses. Kept in one file/class so the whole set runs together as a single suite.
 */
public class CardBehaviorTest {

    // =========================================================================================
    // Firion: "If you control 5 or more Characters, Firion gains Haste and 'When Firion attacks,
    // draw 1 card.'  Discard 1 card: If the discarded card is of Fire Element, until the end of
    // the turn, Firion gains +2000 power and First Strike. If the discarded card is of Water
    // Element, Firion gains +2000 power until the end of the turn and activate Firion."
    //
    // The CPU's block-selection logic must consider spending this discard trick, but only when
    // it actually changes the outcome of the block.
    // =========================================================================================

    private static final String FIRION_TEXT =
            "If you control 5 or more Characters, Firion gains Haste and \"When Firion attacks, draw 1 card.\"[[br]]   "
            + "Discard 1 card: If the discarded card is of Fire Element, until the end of the turn, Firion gains +2000 power and First Strike. "
            + "If the discarded card is of Water Element, Firion gains +2000 power until the end of the turn and activate Firion.";

    private static CardData makeForward(String name, String element, int cost, int power) {
        return makeForward(name, element, cost, power, List.of());
    }

    private static CardData makeForward(String name, String element, int cost, int power, List<ActionAbility> abilities) {
        return new CardData(null, name, element, cost, power, "Forward", false, 0, false, false,
                Set.of(), 0, List.of(), null, List.of(),
                abilities, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                false, false, null, false, false, false, false, false, false,
                null, null, null, "");
    }

    private static CardData makeFirion(int power) {
        return makeForward("Firion", "Fire", 2, power, CardData.parseActionAbilities(FIRION_TEXT));
    }

    /** Builds a real MainWindow (no window shown) with Firion on P2's field and a P1 attacker declared. */
    private static MainWindow firionSetUp(CardState firionState, int firionPower, int attackerPower,
            List<CardData> p2HandCards) {
        MainWindow mw = new MainWindow();
        mw.placeCardInForwardZone(makeForward("Attacker", "Water", 3, attackerPower)); // P1 idx 0
        mw.placeP2CardInForwardZone(makeFirion(firionPower));                          // P2 idx 0
        mw.p2ForwardStates.set(0, firionState);
        mw.gameState.getP2Hand().addAll(p2HandCards);
        return mw;
    }

    @Test
    void firionDiscardConditionalElementBranchesParsesCorrectly() {
        List<ActionAbility> abilities = CardData.parseActionAbilities(FIRION_TEXT);
        assertEquals(1, abilities.size());
        List<ActionResolver.DiscardElementBranch> branches =
                ActionResolver.discardConditionalElementBranches(abilities.get(0).effectText());
        assertNotNull(branches);
        assertEquals(2, branches.size());
        assertEquals("Fire", branches.get(0).element());
        assertTrue(branches.get(0).effectText().toLowerCase().contains("first strike"));
        assertEquals("Water", branches.get(1).element());
        assertTrue(branches.get(1).effectText().toLowerCase().contains("activate"));
    }

    @Test
    void firionDeclinesToBlockAndDoesNotDiscardWhenHandHasNoMatchingElement() {
        // Firion (4000) can't survive a 6000-power attacker unblocked-boosted; hand has only an
        // Earth card, so neither branch of the ability can do anything — must not be used.
        MainWindow mw = firionSetUp(CardState.ACTIVE, 4000, 6000,
                List.of(makeForward("Filler", "Earth", 1, 1000)));
        int handSizeBefore = mw.gameState.getP2Hand().size();

        ForwardTarget blk = new ComputerPlayer(mw).chooseBlocker(6000,
                new ForwardTarget(true, 0, ForwardTarget.CardZone.FORWARD));

        assertNull(blk, "should decline to block rather than lose Firion for nothing");
        assertEquals(handSizeBefore, mw.gameState.getP2Hand().size(), "must not waste a card with no benefit");
    }

    @Test
    void firionUsesFireBranchToWinAnOtherwiseLosingBlock() {
        // Firion (4000) alone can't survive/break a 5000-power attacker, but +2000 power and
        // First Strike (6000) does. Hand has the needed Fire card plus an unrelated one.
        CardData fireCard = makeForward("Fire Fodder", "Fire", 1, 1000);
        MainWindow mw = firionSetUp(CardState.ACTIVE, 4000, 5000,
                List.of(makeForward("Filler", "Earth", 1, 1000), fireCard));

        ForwardTarget blk = new ComputerPlayer(mw).chooseBlocker(5000,
                new ForwardTarget(true, 0, ForwardTarget.CardZone.FORWARD));

        assertNotNull(blk, "should use the Fire branch to turn this into a winning block");
        assertEquals(0, blk.idx());
        assertEquals(ForwardTarget.CardZone.FORWARD, blk.zone());
        assertFalse(mw.gameState.getP2Hand().contains(fireCard), "the Fire card should have been discarded");
        assertEquals(6000, mw.effectiveP2ForwardPower(0), "Firion should now be boosted to 6000 power");
        assertTrue(mw.p2ForwardTempTraits.get(0).contains(CardData.Trait.FIRST_STRIKE), "Firion should have gained First Strike");
    }

    @Test
    void firionUsesWaterBranchToActivateADullFirionSoItCanBlock() {
        // Dull Firion is normally not even a candidate; the Water branch both boosts power and
        // activates it, so it should become the chosen blocker against a 5000-power attacker.
        CardData waterCard = makeForward("Water Fodder", "Water", 1, 1000);
        MainWindow mw = firionSetUp(CardState.DULL, 4000, 5000,
                List.of(makeForward("Filler", "Earth", 1, 1000), waterCard));

        ForwardTarget blk = new ComputerPlayer(mw).chooseBlocker(5000,
                new ForwardTarget(true, 0, ForwardTarget.CardZone.FORWARD));

        assertNotNull(blk, "should activate dull Firion via the Water branch so it can block");
        assertEquals(0, blk.idx());
        assertFalse(mw.gameState.getP2Hand().contains(waterCard), "the Water card should have been discarded");
        assertEquals(CardState.ACTIVE, mw.p2ForwardStates.get(0), "Firion should now be active");
        assertEquals(6000, mw.effectiveP2ForwardPower(0), "Firion should now be boosted to 6000 power");
    }

    @Test
    void firionDoesNotUseTrickWhenAnAdequateBlockerAlreadyExists() {
        // Firion (7000, active) already survives/breaks a 5000-power attacker unaided — the
        // ability must not be spent for no reason.
        CardData fireCard = makeForward("Fire Fodder", "Fire", 1, 1000);
        MainWindow mw = firionSetUp(CardState.ACTIVE, 7000, 5000,
                List.of(fireCard));
        int handSizeBefore = mw.gameState.getP2Hand().size();

        ForwardTarget blk = new ComputerPlayer(mw).chooseBlocker(5000,
                new ForwardTarget(true, 0, ForwardTarget.CardZone.FORWARD));

        assertNotNull(blk);
        assertEquals(0, blk.idx());
        assertEquals(handSizeBefore, mw.gameState.getP2Hand().size(), "must not spend the card when already winning");
    }

    // =========================================================================================
    // Llednar: "Discard 2 cards: Remove all Fortune Counters from Llednar. Each player can use
    // this ability." — the "Remove all [Name] Counters from [CardName]." action and the
    // "Each player can use this ability." flag.
    // =========================================================================================

    @Test
    void llednarAbilityParsesDiscardCostAndUsableByEitherPlayerFlag() {
        String text = "When Llednar enters the field due to your cast, place 1 Fortune Counter on Llednar.[[br]]   "
                + "If a Fortune Counter is placed on Llednar, Llednar cannot be broken.[[br]]   "
                + "Discard 2 cards: Remove all Fortune Counters from Llednar. Each player can use this ability.";

        List<ActionAbility> abilities = CardData.parseActionAbilities(text);
        assertEquals(1, abilities.size());
        ActionAbility ability = abilities.get(0);

        assertTrue(ability.usableByEitherPlayer());
        assertEquals(1, ability.discardCosts().size());
        assertEquals(2, ability.discardCosts().get(0).count());
        assertEquals("Remove all Fortune Counters from Llednar. Each player can use this ability.",
                ability.effectText());
    }

    @Test
    void llednarRemoveAllCountersClearsExactCurrentCount() {
        CardData source = mock(CardData.class);
        when(source.name()).thenReturn("Llednar");
        GameContext ctx = mock(GameContext.class);
        when(ctx.getCounters(source, "Fortune")).thenReturn(3);

        Consumer<GameContext> fn = ActionResolver.parse(
                "Remove all Fortune Counters from Llednar. Each player can use this ability.", source);
        assertNotNull(fn);
        fn.accept(ctx);

        verify(ctx).removeCounters(source, "Fortune", 3);
    }

    @Test
    void llednarRemoveAllCountersIsNoOpWhenNonePresent() {
        CardData source = mock(CardData.class);
        when(source.name()).thenReturn("Llednar");
        GameContext ctx = mock(GameContext.class);
        when(ctx.getCounters(source, "Fortune")).thenReturn(0);

        Consumer<GameContext> fn = ActionResolver.parse(
                "Remove all Fortune Counters from Llednar. Each player can use this ability.", source);
        assertNotNull(fn);
        fn.accept(ctx);

        verify(ctx, never()).removeCounters(any(), any(), anyInt());
    }

    @Test
    void llednarRemoveAllCountersOnlyAppliesToNamedTarget() {
        // "Llednar" refers to itself; a differently-named source must not match.
        CardData source = mock(CardData.class);
        when(source.name()).thenReturn("Someone Else");

        Consumer<GameContext> fn = ActionResolver.parse(
                "Remove all Fortune Counters from Llednar. Each player can use this ability.", source);
        assertNull(fn);
    }

    // =========================================================================================
    // Samurai / Bard / Summoner: the CP_FIXED "extra cost" pattern — "If you cast [Name], you
    // may pay 《Element》《N》 as an extra cost." plus the paired "If you paid the extra cost,
    // [effect]" auto-ability clause.
    // =========================================================================================

    private static CardData makeExtraCostCard(String name, String element, String textEn) {
        return new CardData(null, name, element, 1, 4000, "Forward", false, 0, false, false,
                Set.of(), 0, List.of(), null, List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                false, false, null, false, false, false, false, false, false,
                null, null, null, textEn);
    }

    @Test
    void samuraiParsesAsWindPlusTwoGenericFixedCp() {
        CardData samurai = makeExtraCostCard("Samurai", "Fire",
                "If you cast Samurai, you may pay 《Wind》《2》 as an extra cost.[[br]]"
                + "When Samurai enters the field, choose 1 Forward of cost 6 or more. If you paid the extra cost, break it.");
        ExtraCost ec = samurai.extraCost();
        assertNotNull(ec);
        assertEquals(ExtraCost.Type.CP_FIXED, ec.type());
        assertEquals(List.of("Wind", "", ""), ec.cpElements());
    }

    @Test
    void bardParsesAsEarthPlusThreeGenericFixedCp() {
        CardData bard = makeExtraCostCard("Bard", "Ice",
                "If you cast Bard, you may pay 《Earth》《3》 as an extra cost.[[br]]"
                + "When Bard enters the field, choose 1 dull Forward. If you paid the extra cost, break it.");
        ExtraCost ec = bard.extraCost();
        assertNotNull(ec);
        assertEquals(ExtraCost.Type.CP_FIXED, ec.type());
        assertEquals(List.of("Earth", "", "", ""), ec.cpElements());
    }

    @Test
    void samuraiBreaksTargetOnlyWhenExtraCostPaid() {
        String rawEffect = "choose 1 Forward of cost 6 or more. If you paid the extra cost, break it.";

        String paidText = ActionResolver.applyExtraCostPaid(rawEffect);
        String notPaidText = ActionResolver.stripExtraCostClause(rawEffect);

        GameContext ctx = mock(GameContext.class);
        when(ctx.consumePreloadedTargets()).thenReturn(null);
        ForwardTarget t = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);
        when(ctx.selectCharacters(
                anyInt(), anyBoolean(), anyBoolean(), anyBoolean(),
                any(), any(), anyInt(), any(), anyInt(), any(),
                anyBoolean(), anyBoolean(), anyBoolean(),
                any(), any(), any(), any(), anyBoolean(), any(), anyBoolean()
        )).thenReturn(List.of(t));

        Consumer<GameContext> paidFn = ActionResolver.parse(paidText, null);
        assertNotNull(paidFn, "paid-branch text should parse: " + paidText);
        paidFn.accept(ctx);
        verify(ctx).breakTarget(t);

        // Not-paid branch: the clause is stripped entirely, leaving just "choose a Forward" with
        // no follow-up action — nothing should break.
        Consumer<GameContext> notPaidFn = ActionResolver.parse(notPaidText, null);
        if (notPaidFn != null) {
            GameContext ctx2 = mock(GameContext.class);
            notPaidFn.accept(ctx2);
            verify(ctx2, never()).breakTarget(any());
        }
    }

    // Summoner's whole ability is the condition itself — no unconditional lead-in before
    // "If you paid the extra cost" (unlike Samurai's "Choose 1 Forward … If you paid …").
    // This shape previously broke applyExtraCostPaid/stripExtraCostClause (both require a
    // non-empty prefix before the clause), which — worse — meant ActionResolver.parse() was
    // handed the raw, still-conditional text and would NPE on the null-source smoke test,
    // or silently execute the effect unconditionally in the real (non-null source) game path.
    @Test
    void summonerSelectsOpponentForwardOnlyWhenExtraCostPaid() {
        CardData summoner = mock(CardData.class);
        when(summoner.name()).thenReturn("Summoner");
        String rawEffect = "if you paid the extra cost, your opponent selects 1 Forward they control. Put it into the Break Zone.";

        String paidText = ActionResolver.applyExtraCostPaid(rawEffect);
        assertEquals("Your opponent selects 1 Forward they control. Put it into the Break Zone.", paidText);

        GameContext ctx = mock(GameContext.class);
        ForwardTarget t = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);
        when(ctx.selectCharacters(
                anyInt(), anyBoolean(), anyBoolean(), anyBoolean(),
                any(), any(), anyInt(), any(), anyInt(), any(),
                anyBoolean(), anyBoolean(), anyBoolean(),
                any(), any(), any(), any(), anyBoolean(), any(), anyBoolean()
        )).thenReturn(List.of(t));

        Consumer<GameContext> paidFn = ActionResolver.parse(paidText, summoner);
        assertNotNull(paidFn, "paid-branch text should parse: " + paidText);
        paidFn.accept(ctx);
        verify(ctx).forceTargetToBreakZone(t);

        // Not-paid: the whole ability was the condition, so stripping it leaves nothing at all.
        String notPaidText = ActionResolver.stripExtraCostClause(rawEffect);
        assertTrue(notPaidText.isBlank(), "not-paid text should be empty: [" + notPaidText + "]");
    }

    // =========================================================================================
    // Yuffie: regression test for a bug where activating a "Choose any number of [targets]..."
    // action ability (e.g. "Doom of the Living": "Choose any number of Forwards. Divide 24000
    // damage among them as you like.") silently failed — no target prompt, no effect, nothing on
    // the stack — because ActionResolver.preSelectTargets had its own separate copy of the
    // "Choose N" count extraction that didn't know about the "any number of" branch and threw a
    // NumberFormatException parsing a null count group, which aborted activation before the
    // ability ever reached the stack.
    // =========================================================================================

    private static CardData makePreSelectCard(String name) {
        return new CardData(null, name, "Wind", 3, 6000, "Forward", false, 0, false, false,
                Set.of(), 0, List.of(), null, List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                false, false, null, false, false, false, false, false, false,
                null, null, null, "");
    }

    @Test
    void anyNumberChooseDoesNotThrowAndPromptsWithUnboundedMax() {
        CardData yuffie = makePreSelectCard("Yuffie");
        GameContext ctx = mock(GameContext.class);
        ForwardTarget t = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);
        when(ctx.selectCharacters(
                eq(Integer.MAX_VALUE), eq(true), anyBoolean(), anyBoolean(),
                any(), any(), anyInt(), any(), anyInt(), any(),
                anyBoolean(), anyBoolean(), anyBoolean(),
                any(), any(), any(), any(), anyBoolean(), any(), anyBoolean()
        )).thenReturn(List.of(t));

        List<ForwardTarget> result = assertDoesNotThrow(() ->
                ActionResolver.preSelectTargets(
                        "Choose any number of Forwards. Divide 24000 damage among them as you like. (Units must be 1000.)",
                        yuffie, 0, ctx));

        assertEquals(List.of(t), result);
        verify(ctx).selectCharacters(eq(Integer.MAX_VALUE), eq(true), anyBoolean(), anyBoolean(),
                any(), any(), anyInt(), any(), anyInt(), any(),
                anyBoolean(), anyBoolean(), anyBoolean(),
                any(), any(), any(), any(), anyBoolean(), any(), anyBoolean());
    }

    @Test
    void plainChooseCountStillWorks() {
        // Regression guard: the fix must not break the ordinary "Choose N" path.
        CardData card = makePreSelectCard("Barret");
        GameContext ctx = mock(GameContext.class);
        when(ctx.selectCharacters(
                eq(2), eq(true), anyBoolean(), anyBoolean(),
                any(), any(), anyInt(), any(), anyInt(), any(),
                anyBoolean(), anyBoolean(), anyBoolean(),
                any(), any(), any(), any(), anyBoolean(), any(), anyBoolean()
        )).thenReturn(List.of());

        List<ForwardTarget> result = assertDoesNotThrow(() ->
                ActionResolver.preSelectTargets(
                        "Choose up to 2 Forwards. Divide 10000 damage among them as you like. (Units must be 1000.)",
                        card, 0, ctx));

        assertNotNull(result);
    }

    // =========================================================================================
    // Mime: "Mime's power becomes the same as your opponent's weakest Forward until the end of
    // the turn."
    // =========================================================================================

    private static final String MIME_TEXT =
            "Mime's power becomes the same as your opponent's weakest Forward until the end of the turn.";

    @Test
    void mimeSetsSourcePowerToOpponentsLowestForwardPower() {
        CardData mime = mock(CardData.class);
        when(mime.name()).thenReturn("Mime");

        Consumer<GameContext> fn = ActionResolver.parse(MIME_TEXT, mime);
        assertNotNull(fn, "Expected Mime's ability text to parse");

        GameContext ctx = mock(GameContext.class);
        when(ctx.opponentLowestForwardPower()).thenReturn(3000);

        fn.accept(ctx);

        verify(ctx).setSourceForwardPower(mime, 3000);
    }

    @Test
    void mimeDoesNotFireWhenSourceNameDoesNotMatch() {
        CardData other = mock(CardData.class);
        when(other.name()).thenReturn("Not Mime");

        Consumer<GameContext> fn = ActionResolver.parse(MIME_TEXT, other);

        assertNull(fn, "Ability text naming Mime should not resolve for a differently-named source");
    }

    // =========================================================================================
    // "《Dull》, discard 1 card: Choose 3 cards in your opponent's Break Zone. Remove them from
    // the game. If the discarded card is of Water Element, also draw 1 card, then discard 1
    // card." — the single-branch, additive-only discard-element conditional attached as a
    // secondary effect after a "Choose ... Remove them from the game" primary.
    // =========================================================================================

    private static final String DISCARD_RFG_EFFECT_TEXT =
            "Choose 3 cards in your opponent's Break Zone. Remove them from the game. "
            + "If the discarded card is of Water Element, also draw 1 card, then discard 1 card.";

    private static void stubOpponentBzTargets(GameContext ctx, List<ForwardTarget> result) {
        when(ctx.consumePreloadedTargets()).thenReturn(null);
        when(ctx.selectCharactersFromBreakZone(
                eq(3), anyBoolean(), anyBoolean(), anyBoolean(),
                any(), any(), anyInt(), any(), anyInt(), any(),
                anyBoolean(), anyBoolean(), anyBoolean(),
                any(), any(), any(), any(), anyBoolean(), any(), anyBoolean()
        )).thenReturn(result);
    }

    @Test
    void removesThreeChosenCardsFromOpponentBzRegardlessOfDiscardElement() {
        GameContext ctx = mock(GameContext.class);
        ForwardTarget t0 = new ForwardTarget(false, 0, ForwardTarget.CardZone.BREAK_ZONE);
        ForwardTarget t1 = new ForwardTarget(false, 1, ForwardTarget.CardZone.BREAK_ZONE);
        ForwardTarget t2 = new ForwardTarget(false, 2, ForwardTarget.CardZone.BREAK_ZONE);
        stubOpponentBzTargets(ctx, List.of(t0, t1, t2));
        when(ctx.lastDiscardedCostCardElement()).thenReturn("Fire");

        Consumer<GameContext> fn = ActionResolver.parse(DISCARD_RFG_EFFECT_TEXT, null);
        assertNotNull(fn);
        fn.accept(ctx);

        verify(ctx).removeTargetFromGame(t0);
        verify(ctx).removeTargetFromGame(t1);
        verify(ctx).removeTargetFromGame(t2);
        verify(ctx, never()).drawCards(anyInt());
        verify(ctx, never()).selfDiscard(anyInt());
    }

    @Test
    void alsoDrawsThenDiscardsWhenDiscardedCardIsWater() {
        GameContext ctx = mock(GameContext.class);
        ForwardTarget t0 = new ForwardTarget(false, 0, ForwardTarget.CardZone.BREAK_ZONE);
        stubOpponentBzTargets(ctx, List.of(t0));
        when(ctx.lastDiscardedCostCardElement()).thenReturn("Water");

        Consumer<GameContext> fn = ActionResolver.parse(DISCARD_RFG_EFFECT_TEXT, null);
        assertNotNull(fn);
        fn.accept(ctx);

        verify(ctx).removeTargetFromGame(t0);
        verify(ctx).drawCards(1);
        verify(ctx).selfDiscard(1);
    }

    // =========================================================================================
    // Rubicante: "Name 1 Element. During this turn, if Rubicante is dealt damage by abilities of
    // the named Element, the damage becomes 0 instead." — ability-only element damage
    // nullification, distinct from Hein's combined immunity+nullification block.
    // =========================================================================================

    @Test
    void rubicanteNullifiesDamageFromNamedElementAbilitiesOnly() {
        CardData rubicante = mock(CardData.class);
        when(rubicante.name()).thenReturn("Rubicante");

        Consumer<GameContext> fn = ActionResolver.parse(
                "Name 1 Element. During this turn, if Rubicante is dealt damage by abilities of the named Element, "
                + "the damage becomes 0 instead.", rubicante);
        assertNotNull(fn);

        GameContext ctx = mock(GameContext.class);
        when(ctx.selectElement(anyString())).thenReturn("Fire");

        fn.accept(ctx);

        verify(ctx).nullifyNamedCardDamageByElementAbilityOnly("Rubicante", "Fire");
        verify(ctx, never()).shieldNamedCardCannotBeChosenByElement(any(), any());
    }

    @Test
    void rubicanteDoesNotFireWhenSourceNameDoesNotMatch() {
        CardData other = mock(CardData.class);
        when(other.name()).thenReturn("Not Rubicante");

        Consumer<GameContext> fn = ActionResolver.parse(
                "Name 1 Element. During this turn, if Rubicante is dealt damage by abilities of the named Element, "
                + "the damage becomes 0 instead.", other);

        assertNull(fn);
    }

    // =========================================================================================
    // "Choose 1 Summon in your Break Zone. Remove it from the game. During this game, you can
    // cast it at any time you could normally cast it." — the plain-phrasing variant (no "as
    // though you owned it") of the Shantotto-style BZ-Summon-castable-forever pattern.
    // =========================================================================================

    @Test
    void chooseSummonInOwnBzRemoveFromGameCastableDuringGameParses() {
        Consumer<GameContext> fn = ActionResolver.parse(
                "Choose 1 Summon in your Break Zone. Remove it from the game. "
                + "During this game, you can cast it at any time you could normally cast it.", null);
        assertNotNull(fn);

        GameContext ctx = mock(GameContext.class);
        fn.accept(ctx);

        verify(ctx).chooseSummonsFromBzMakeCastable(1, false, false, false, false);
    }

    // =========================================================================================
    // Cloud / Strago / Vivi / Zell / Bahamut / Eden / Barret: the "Divide N damage [equally]
    // among ..." action-ability pattern, exercised against real card texts pulled from
    // shufflingway.db. These assert on the actual damage amounts/targets dealt via a mocked
    // GameContext, not just that the text parses.
    // =========================================================================================

    private static GameContext divideMockContext(boolean isP1) {
        GameContext ctx = mock(GameContext.class);
        when(ctx.isP1()).thenReturn(isP1);
        // Mockito's default answer returns an empty List (not null) for collection-typed methods;
        // selectTargets() treats a non-null return from consumePreloadedTargets() as "already
        // chosen" and skips selectCharacters() entirely, so this must be explicitly null here.
        when(ctx.consumePreloadedTargets()).thenReturn(null);
        return ctx;
    }

    private static void divideStubSelectCharacters(GameContext ctx, List<ForwardTarget> result) {
        when(ctx.selectCharacters(
                anyInt(), anyBoolean(), anyBoolean(), anyBoolean(),
                any(), any(), anyInt(), any(), anyInt(), any(),
                anyBoolean(), anyBoolean(), anyBoolean(),
                any(), any(), any(), any(), anyBoolean(), any(), anyBoolean()
        )).thenReturn(result);
    }

    private static Consumer<GameContext> divideParse(String effectText) {
        Consumer<GameContext> fn = ActionResolver.parse(effectText, null);
        assertNotNull(fn, "Expected \"" + effectText + "\" to parse");
        return fn;
    }

    // --- Cloud: "Choose up to 2 Forwards. Divide 10000 damage among them equally." ---

    @Test
    void cloudSplitsEquallyAcrossTwoTargets() {
        GameContext ctx = divideMockContext(true);
        ForwardTarget t0 = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);
        ForwardTarget t1 = new ForwardTarget(false, 1, ForwardTarget.CardZone.FORWARD);
        divideStubSelectCharacters(ctx, List.of(t0, t1));

        divideParse("Choose up to 2 Forwards. Divide 10000 damage among them equally.").accept(ctx);

        verify(ctx).damageTarget(t0, 5000);
        verify(ctx).damageTarget(t1, 5000);
        verify(ctx, never()).divideDamageAmount(anyInt(), any(), any());
    }

    @Test
    void cloudDealsFullAmountToSingleTarget() {
        GameContext ctx = divideMockContext(true);
        ForwardTarget t0 = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);
        divideStubSelectCharacters(ctx, List.of(t0));

        divideParse("Choose up to 2 Forwards. Divide 10000 damage among them equally.").accept(ctx);

        verify(ctx).damageTarget(t0, 10000);
    }

    // --- Strago: "Divide 12000 damage equally among all the Forwards opponent controls
    //             (round up to the nearest 1000)." — no Choose clause, blanket target. ---

    @Test
    void stragoRoundsUpPerTargetWhenNotEvenlyDivisible() {
        GameContext ctx = divideMockContext(true);
        when(ctx.p2ForwardCount()).thenReturn(5);
        when(ctx.p1ForwardCount()).thenReturn(0);

        divideParse("Divide 12000 damage equally among all the Forwards opponent controls (round up to the nearest 1000).")
                .accept(ctx);

        // 12000 / 5 = 2400 -> rounds up to 3000 per target; total dealt (15000) exceeds the stated 12000.
        for (int i = 0; i < 5; i++) {
            verify(ctx).damageTarget(new ForwardTarget(false, i, ForwardTarget.CardZone.FORWARD), 3000);
        }
    }

    // --- Vivi: "...Divide 7000 damage among them as you like. If you control a Category IX
    //           Forward other than Vivi, divide 10000 damage among them instead..." ---

    @Test
    void viviDealsBoostedDamageWhenOtherCategoryForwardPresent() {
        GameContext ctx = divideMockContext(true);
        ForwardTarget t0 = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);
        divideStubSelectCharacters(ctx, List.of(t0));
        when(ctx.controlConditionMetExcluding(any(), eq("Vivi"))).thenReturn(true);

        divideParse("Choose any number of Forwards opponent controls. Divide 7000 damage among them as you like. "
                + "If you control a Category IX Forward other than Vivi, divide 10000 damage among them instead. "
                + "(Units must be 1000.)").accept(ctx);

        verify(ctx).controlConditionMetExcluding(any(), eq("Vivi"));
        verify(ctx).damageTarget(t0, 10000);
    }

    @Test
    void viviDealsBaseDamageWhenNoOtherCategoryForwardPresent() {
        GameContext ctx = divideMockContext(true);
        ForwardTarget t0 = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);
        divideStubSelectCharacters(ctx, List.of(t0));
        when(ctx.controlConditionMetExcluding(any(), eq("Vivi"))).thenReturn(false);

        divideParse("Choose any number of Forwards opponent controls. Divide 7000 damage among them as you like. "
                + "If you control a Category IX Forward other than Vivi, divide 10000 damage among them instead. "
                + "(Units must be 1000.)").accept(ctx);

        verify(ctx).damageTarget(t0, 7000);
    }

    // --- Zell: "...Divide 5000 damage among them as you like. If you control 4 or more
    //           Category VIII Characters, divide 9000 damage among them as you like instead..." ---

    @Test
    void zellDealsBoostedDamageWhenCountThresholdMet() {
        GameContext ctx = divideMockContext(true);
        ForwardTarget t0 = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);
        divideStubSelectCharacters(ctx, List.of(t0));
        when(ctx.controlConditionMet(any())).thenReturn(true);

        divideParse("Choose any number of Forwards. Divide 5000 damage among them as you like. "
                + "If you control 4 or more Category VIII Characters, divide 9000 damage among them as you like instead. "
                + "(Units must be 1000.)").accept(ctx);

        // No "other than" clause in Zell's condition — must use the non-excluding check.
        verify(ctx).controlConditionMet(any());
        verify(ctx, never()).controlConditionMetExcluding(any(), any());
        verify(ctx).damageTarget(t0, 9000);
    }

    @Test
    void zellDealsBaseDamageWhenCountThresholdNotMet() {
        GameContext ctx = divideMockContext(true);
        ForwardTarget t0 = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);
        divideStubSelectCharacters(ctx, List.of(t0));
        when(ctx.controlConditionMet(any())).thenReturn(false);

        divideParse("Choose any number of Forwards. Divide 5000 damage among them as you like. "
                + "If you control 4 or more Category VIII Characters, divide 9000 damage among them as you like instead. "
                + "(Units must be 1000.)").accept(ctx);

        verify(ctx).damageTarget(t0, 5000);
    }

    // --- Bahamut: "...Divide 10000 damage among them as you like. If you have received 5 points
    //              of damage or more, divide 15000 damage among those instead..." ---

    @Test
    void bahamutDealsBoostedDamageWhenSelfDamageThresholdMet() {
        GameContext ctx = divideMockContext(true);
        ForwardTarget t0 = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);
        divideStubSelectCharacters(ctx, List.of(t0));
        when(ctx.selfDamageCount()).thenReturn(5);

        divideParse("Choose up to 2 Forwards. Divide 10000 damage among them as you like. "
                + "If you have received 5 points of damage or more, divide 15000 damage among those instead. "
                + "(Units must be 1000.)").accept(ctx);

        verify(ctx).damageTarget(t0, 15000);
    }

    @Test
    void bahamutDealsBaseDamageWhenSelfDamageThresholdNotMet() {
        GameContext ctx = divideMockContext(true);
        ForwardTarget t0 = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);
        divideStubSelectCharacters(ctx, List.of(t0));
        when(ctx.selfDamageCount()).thenReturn(2);

        divideParse("Choose up to 2 Forwards. Divide 10000 damage among them as you like. "
                + "If you have received 5 points of damage or more, divide 15000 damage among those instead. "
                + "(Units must be 1000.)").accept(ctx);

        verify(ctx).damageTarget(t0, 10000);
    }

    // --- Eden: "...Divide 30000 damage among them as you like. (Units must be 1000.)
    //           This damage cannot be reduced." ---

    @Test
    void edenUsesUnreducedDamageWhenCannotBeReduced() {
        GameContext ctx = divideMockContext(true);
        ForwardTarget t0 = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);
        divideStubSelectCharacters(ctx, List.of(t0));

        divideParse("Choose up to 2 Forwards. Divide 30000 damage among them as you like. (Units must be 1000.) "
                + "This damage cannot be reduced.").accept(ctx);

        verify(ctx).damageTargetUnreduced(t0, 30000);
        verify(ctx, never()).damageTarget(any(), anyInt());
    }

    // --- Barret: "Choose up to 2 Forwards. Divide 10000 damage among them as you like.
    //             (Units must be 1000.)" — baseline multi-target "as you like" dialog path. ---

    @Test
    void barretInvokesAllocationDialogForMultipleTargets() {
        GameContext ctx = divideMockContext(true);
        ForwardTarget t0 = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);
        ForwardTarget t1 = new ForwardTarget(false, 1, ForwardTarget.CardZone.FORWARD);
        divideStubSelectCharacters(ctx, List.of(t0, t1));
        CardData c0 = mock(CardData.class);
        CardData c1 = mock(CardData.class);
        when(ctx.p2Forward(0)).thenReturn(c0);
        when(ctx.p2Forward(1)).thenReturn(c1);
        when(ctx.divideDamageAmount(eq(10000), any(), eq(List.of(c0, c1))))
                .thenReturn(List.of(4000, 6000));

        divideParse("Choose up to 2 Forwards. Divide 10000 damage among them as you like. (Units must be 1000.)")
                .accept(ctx);

        verify(ctx).damageTarget(t0, 4000);
        verify(ctx).damageTarget(t1, 6000);
    }

    @Test
    void barretSkipsZeroAllocationTargets() {
        GameContext ctx = divideMockContext(true);
        ForwardTarget t0 = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);
        ForwardTarget t1 = new ForwardTarget(false, 1, ForwardTarget.CardZone.FORWARD);
        divideStubSelectCharacters(ctx, List.of(t0, t1));
        when(ctx.p2Forward(anyInt())).thenReturn(mock(CardData.class));
        when(ctx.divideDamageAmount(eq(10000), any(), any()))
                .thenReturn(List.of(10000, 0));

        divideParse("Choose up to 2 Forwards. Divide 10000 damage among them as you like. (Units must be 1000.)")
                .accept(ctx);

        verify(ctx).damageTarget(t0, 10000);
        verify(ctx, never()).damageTarget(eq(t1), anyInt());
    }

    // =========================================================================================
    // Leon: "When Leon enters the field, your opponent gains control of Leon." / "When a
    // Category II Character enters your opponent's field, your opponent gains control of Leon."
    // — permanent control transfer of the source card itself to its own controller's opponent.
    // =========================================================================================

    private static final String LEON_TEXT =
            "When Leon enters the field, your opponent gains control of Leon.[[br]]   "
            + "When a Category II Character enters your opponent's field, your opponent gains control of Leon.";

    @Test
    void leonAutoAbilitiesParseWithExpectedTriggersAndSubjects() {
        List<AutoAbility> autos = CardData.parseAutoAbilities(LEON_TEXT);
        assertEquals(2, autos.size());

        assertEquals("enters the field", autos.get(0).trigger());
        assertEquals("Leon", autos.get(0).triggerCard());
        assertTrue(autos.get(0).effectText().equalsIgnoreCase("your opponent gains control of Leon."));

        assertEquals("enters opponent's field", autos.get(1).trigger());
        assertEquals("a Category II Character", autos.get(1).triggerCard());
        assertTrue(autos.get(1).effectText().equalsIgnoreCase("your opponent gains control of Leon."));
    }

    @Test
    void opponentGainsControlOfSourceParsesAndDelegatesToGameContext() {
        CardData leon = mock(CardData.class);
        when(leon.name()).thenReturn("Leon");

        Consumer<GameContext> fn = ActionResolver.parse("Your opponent gains control of Leon.", leon);
        assertNotNull(fn);

        GameContext ctx = mock(GameContext.class);
        fn.accept(ctx);

        verify(ctx).giveSourceControlToOpponent(leon);
    }

    @Test
    void opponentGainsControlDoesNotFireWhenSourceNameDoesNotMatch() {
        CardData other = mock(CardData.class);
        when(other.name()).thenReturn("Not Leon");

        Consumer<GameContext> fn = ActionResolver.parse("Your opponent gains control of Leon.", other);

        assertNull(fn);
    }

    @Test
    void giveForwardControlToOpponentMovesFromP1ToP2PreservingDamageAndState() {
        MainWindow mw = new MainWindow();
        CardData leon = makeForward("Leon", "Fire", 3, 5000);
        mw.placeCardInForwardZone(leon); // P1 idx 0
        mw.p1ForwardStates.set(0, CardState.DULL);
        mw.p1ForwardDamage.set(0, 2000);

        mw.giveForwardControlToOpponent(leon);

        assertFalse(mw.p1ForwardCards.contains(leon), "Leon should have left P1's field");
        assertEquals(1, mw.p2ForwardCards.size());
        assertSame(leon, mw.p2ForwardCards.get(0));
        assertEquals(CardState.DULL, mw.p2ForwardStates.get(0), "state should carry over");
        assertEquals(2000, (int) mw.p2ForwardDamage.get(0), "damage should carry over");
    }

    @Test
    void giveForwardControlToOpponentMovesFromP2ToP1() {
        MainWindow mw = new MainWindow();
        CardData leon = makeForward("Leon", "Fire", 3, 5000);
        mw.placeP2CardInForwardZone(leon); // P2 idx 0

        mw.giveForwardControlToOpponent(leon);

        assertFalse(mw.p2ForwardCards.contains(leon), "Leon should have left P2's field");
        assertEquals(1, mw.p1ForwardCards.size());
        assertSame(leon, mw.p1ForwardCards.get(0));
    }

    @Test
    void giveForwardControlToOpponentAppliesUniquenessRuleAgainstExistingCopy() {
        // The opponent already controls their own Leon; once control crosses over, the two
        // same-named copies conflict under the uniqueness rule and both go to the Break Zone.
        MainWindow mw = new MainWindow();
        CardData incomingLeon  = makeForward("Leon", "Fire", 3, 5000);
        CardData existingLeon  = makeForward("Leon", "Fire", 3, 5000);
        mw.gameState.getIdentity().put(incomingLeon, true);   // owned by P1
        mw.gameState.getIdentity().put(existingLeon, false);  // owned by P2
        mw.placeCardInForwardZone(incomingLeon);    // P1 idx 0
        mw.placeP2CardInForwardZone(existingLeon);  // P2 idx 0

        mw.giveForwardControlToOpponent(incomingLeon);

        assertFalse(mw.p1ForwardCards.contains(incomingLeon));
        assertFalse(mw.p2ForwardCards.contains(existingLeon));
        assertTrue(mw.gameState.getP1BreakZone().contains(incomingLeon));
        assertTrue(mw.gameState.getP2BreakZone().contains(existingLeon));
    }

    // =========================================================================================
    // Moogle (XIV): "At the end of each of your turns, reveal the top 3 cards of your deck. Play
    // up to 1 Card Name Moogle (XIV) or Job Moogle of cost 3 or less among them onto the field
    // and return the other cards to the bottom of your deck in any order." — the combined
    // Card-Name-or-Job filter with a cost ceiling on a "reveal top N, play up to M" effect.
    // =========================================================================================

    private static final String MOOGLE_XIV_TEXT =
            "At the end of each of your turns, reveal the top 3 cards of your deck. Play up to 1 "
            + "Card Name Moogle (XIV) or Job Moogle of cost 3 or less among them onto the field "
            + "and return the other cards to the bottom of your deck in any order.";

    @Test
    void moogleXivAutoAbilityParsesAsEndOfTurnGlobalTrigger() {
        List<AutoAbility> autos = CardData.parseAutoAbilities(MOOGLE_XIV_TEXT);
        assertEquals(1, autos.size());
        AutoAbility fa = autos.get(0);
        assertEquals("end of your turn", fa.trigger());
        assertEquals("", fa.triggerCard());
        assertTrue(fa.effectText().toLowerCase().startsWith("reveal the top 3 cards of your deck"));
    }

    @Test
    void moogleXivRevealPlayNamedOrJobMaxCostParsesAndResolves() {
        String effectText = CardData.parseAutoAbilities(MOOGLE_XIV_TEXT).get(0).effectText();

        Consumer<GameContext> fn = ActionResolver.parse(effectText, null);
        assertNotNull(fn, "Expected \"" + effectText + "\" to parse");

        GameContext ctx = mock(GameContext.class);
        fn.accept(ctx);

        verify(ctx).revealTopNPlayUpToNamedOrJobWithMaxCostOntoFieldRestBottom(3, 1, "Moogle (XIV)", "Moogle", 3);
    }

    // =========================================================================================
    // Jet Bahamut: "When Jet Bahamut enters the field, choose 1 Forward. Deal it 5000 damage. If
    // it is put from the field into the Break Zone this turn, remove it from the game instead."
    // — the secondary clause must mark the chosen target so that ANY later break-to-BZ this turn
    // (battle, another ability, etc.) redirects it to RFG instead.
    // =========================================================================================

    private static final String JET_BAHAMUT_EFFECT_TEXT =
            "choose 1 Forward. Deal it 5000 damage. If it is put from the field into the Break "
            + "Zone this turn, remove it from the game instead.";

    @Test
    void jetBahamutDealsDamageAndMarksTargetForRfgInstead() {
        GameContext ctx = mock(GameContext.class);
        ForwardTarget t = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);
        when(ctx.consumePreloadedTargets()).thenReturn(null);
        when(ctx.selectCharacters(
                anyInt(), anyBoolean(), anyBoolean(), anyBoolean(),
                any(), any(), anyInt(), any(), anyInt(), any(),
                anyBoolean(), anyBoolean(), anyBoolean(),
                any(), any(), any(), any(), anyBoolean(), any(), anyBoolean()
        )).thenReturn(List.of(t));
        when(ctx.lastChosenTargets()).thenReturn(List.of(t));

        Consumer<GameContext> fn = ActionResolver.parse(JET_BAHAMUT_EFFECT_TEXT, null);
        assertNotNull(fn, "Expected Jet Bahamut's effect text to parse");
        fn.accept(ctx);

        verify(ctx).damageTarget(t, 5000);
        verify(ctx).markTargetRfgInsteadOfBzThisTurn(t);
    }

    @Test
    void rfgInsteadOfBzMarkerRedirectsFieldBreakToRemovedFromGame() {
        MainWindow mw = new MainWindow();
        CardData victim = makeForward("Victim", "Fire", 3, 5000);
        mw.gameState.getIdentity().put(victim, true); // owned by P1
        mw.placeCardInForwardZone(victim); // P1 idx 0

        mw.buildGameContext(true).markTargetRfgInsteadOfBzThisTurn(
                new ForwardTarget(true, 0, ForwardTarget.CardZone.FORWARD));
        mw.breakP1Forward(0);

        assertFalse(mw.gameState.getP1BreakZone().contains(victim), "should not land in the Break Zone");
        assertTrue(mw.gameState.getP1PermanentRfp().contains(victim), "should be removed from the game instead");
    }

    @Test
    void rfgInsteadOfBzMarkerDoesNotAffectUnmarkedForwards() {
        // Regression guard: an ordinary break (no marker set) must still go to the Break Zone.
        MainWindow mw = new MainWindow();
        CardData bystander = makeForward("Bystander", "Fire", 3, 5000);
        mw.gameState.getIdentity().put(bystander, true); // owned by P1
        mw.placeCardInForwardZone(bystander); // P1 idx 0

        mw.breakP1Forward(0);

        assertTrue(mw.gameState.getP1BreakZone().contains(bystander));
        assertFalse(mw.gameState.getP1PermanentRfp().contains(bystander));
    }

    // =========================================================================================
    // Vayne: "When Vayne enters the field or at the beginning of your Main Phase 1 during each
    // of your turns, choose 1 card removed from the game with a Warp Counter on it. You may
    // remove 1 Warp Counter from it.[[br]]   When a Warp Counter is removed from any player's
    // card, draw 1 card. This effect will trigger only once per turn." — the compound
    // ETF-or-phase trigger, plus the optional-removal Warp Counter effect.
    // =========================================================================================

    private static final String VAYNE_TEXT =
            "When Vayne enters the field or at the beginning of your Main Phase 1 during each of "
            + "your turns, choose 1 card removed from the game with a Warp Counter on it. You may "
            + "remove 1 Warp Counter from it.[[br]]   When a Warp Counter is removed from any "
            + "player's card, draw 1 card. This effect will trigger only once per turn.";

    private static final String VAYNE_CHOOSE_EFFECT_TEXT =
            "choose 1 card removed from the game with a Warp Counter on it. You may remove 1 Warp Counter from it.";

    @Test
    void vayneAutoAbilitiesParseAsThreeCleanEntries() {
        List<AutoAbility> autos = CardData.parseAutoAbilities(VAYNE_TEXT);
        assertEquals(3, autos.size(), "expected ETF + phase + warp-counter-removed entries");

        AutoAbility etf = autos.stream().filter(a -> a.trigger().equals("enters the field")).findFirst().orElse(null);
        assertNotNull(etf);
        assertEquals("Vayne", etf.triggerCard());
        assertEquals(VAYNE_CHOOSE_EFFECT_TEXT, etf.effectText());

        AutoAbility phase = autos.stream().filter(a -> a.trigger().equals("beginning of main phase 1")).findFirst().orElse(null);
        assertNotNull(phase);
        assertEquals("", phase.triggerCard());
        assertEquals(VAYNE_CHOOSE_EFFECT_TEXT, phase.effectText());

        AutoAbility warp = autos.stream().filter(a -> a.trigger().equals("warp counter removed")).findFirst().orElse(null);
        assertNotNull(warp);
        assertEquals("draw 1 card", warp.effectText());
        assertTrue(warp.oncePerTurn(), "the once-per-turn restriction must not leak into the ETF/phase abilities");
        assertFalse(etf.oncePerTurn());
        assertFalse(phase.oncePerTurn());
    }

    @Test
    void vayneChooseEffectTextParsesAndDelegates() {
        Consumer<GameContext> fn = ActionResolver.parse(VAYNE_CHOOSE_EFFECT_TEXT, null);
        assertNotNull(fn, "Expected Vayne's choose/may-remove effect text to parse");

        GameContext ctx = mock(GameContext.class);
        fn.accept(ctx);

        verify(ctx).chooseAndMayRemoveWarpCounter();
    }

    @Test
    void chooseAndMayRemoveWarpCounterIsNoOpWhenWarpZoneEmpty() {
        MainWindow mw = new MainWindow();
        mw.buildGameContext(true).chooseAndMayRemoveWarpCounter();
        // No exception, no crash — nothing to assert on an empty zone beyond it staying empty.
        assertTrue(mw.gameState.getP1WarpZone().isEmpty());
    }

    @Test
    void chooseAndMayRemoveWarpCounterAiDeclinesAndLeavesCounterUntouched() {
        // promptYouMay() always declines for the non-P1 (AI/opponent) context, so P2's own
        // "you may" decision here must leave the Warp entry completely untouched.
        MainWindow mw = new MainWindow();
        CardData warped = makeForward("Warped One", "Fire", 3, 5000);
        mw.gameState.addToP2WarpZone(warped, 2);

        mw.buildGameContext(false).chooseAndMayRemoveWarpCounter();

        List<GameState.WarpEntry> zone = mw.gameState.getP2WarpZone();
        assertEquals(1, zone.size());
        assertEquals(2, zone.get(0).counters, "AI must decline, leaving the counter count unchanged");
    }

    // =========================================================================================
    // Cid (II): "When Cid (II) enters the field, you may search for 1 card with Warp and add it
    // to your hand." — the generic "search deck" pattern had no way to restrict results to cards
    // with the Warp trait, so this always failed to parse.
    // =========================================================================================

    private static final String CID_II_TEXT =
            "When Cid (II) enters the field, you may search for 1 card with Warp and add it to "
            + "your hand.[[br]]   《Dull》, put Cid (II) into the Break Zone: Choose 1 card removed "
            + "from the game. Remove 1 Warp Counter from it. You can only use this ability during your turn.";

    private static CardData makeForwardWithWarp(String name, String element, int cost, int power, int warpValue) {
        return new CardData(null, name, element, cost, power, "Forward", false, 0, false, false,
                Set.of(), warpValue, List.of(), null, List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                false, false, null, false, false, false, false, false, false,
                null, null, null, "");
    }

    @Test
    void cidIiEnterTheFieldAutoAbilityParsesAsOptionalSearchWithWarp() {
        List<AutoAbility> autos = CardData.parseAutoAbilities(CID_II_TEXT);
        assertEquals(1, autos.size(), "the 《Dull》 action ability must not be picked up as an auto-ability");
        AutoAbility fa = autos.get(0);
        assertEquals("enters the field", fa.trigger());
        assertEquals("Cid (II)", fa.triggerCard());
        assertTrue(fa.youMay());
        assertEquals("search for 1 card with Warp and add it to your hand.", fa.effectText());
    }

    @Test
    void searchForCardWithWarpParsesAndRequiresWarpTrait() {
        Consumer<GameContext> fn = ActionResolver.parse(
                "search for 1 card with Warp and add it to your hand.", null);
        assertNotNull(fn);

        GameContext ctx = mock(GameContext.class);
        fn.accept(ctx);

        verify(ctx).searchDeckForCard(anyBoolean(), anyBoolean(), anyBoolean(), anyBoolean(),
                anyInt(), any(), any(), any(), any(), any(), any(), any(),
                eq("hand"), eq(1), eq(false), eq(true));
    }

    @Test
    void searchDeckForCardWithWarpFindsOnlyTheWarpCard() {
        // AI (P2) path avoids the modal card-picker dialog used for a single P1 match.
        MainWindow mw = new MainWindow();
        CardData plain  = makeForwardWithWarp("Plain One", "Fire", 3, 5000, 0);
        CardData warped = makeForwardWithWarp("Warp One", "Fire", 3, 5000, 2);
        mw.gameState.getP2MainDeck().add(plain);
        mw.gameState.getP2MainDeck().add(warped);

        mw.searchDeckForCard(false, true, true, true, true,
                -1, null, null, null, null, null, null, null,
                "hand", 1, false, true);

        assertTrue(mw.gameState.getP2Hand().contains(warped), "the Warp card should have been found");
        assertFalse(mw.gameState.getP2Hand().contains(plain), "the non-Warp card must not match");
    }

    // =========================================================================================
    // Job-scoped "put from the field into the Break Zone this turn" restriction: "Choose 1
    // Forward opponent controls. Break it. You can only use this ability if a Job AVALANCHE
    // Operative you controlled has been put from the field into the Break Zone this turn."
    // =========================================================================================

    private static final String JOB_BROKEN_ABILITY_TEXT =
            "《Dull》: Choose 1 Forward opponent controls. Break it. You can only use this "
            + "ability if a Job AVALANCHE Operative you controlled has been put from the field into "
            + "the Break Zone this turn.";

    @Test
    void jobBrokenThisTurnRestrictionParsesJobNameAndStripsFromEffectText() {
        List<ActionAbility> abilities = CardData.parseActionAbilities(JOB_BROKEN_ABILITY_TEXT);
        assertEquals(1, abilities.size());
        ActionAbility ability = abilities.get(0);

        assertEquals("avalanche operative", ability.requiresJobPutToBZThisTurn());
        assertFalse(ability.requiresForwardPutToBZThisTurn(),
                "the generic (non-job) restriction must not also fire for the job-qualified sentence");
    }

    @Test
    void jobBrokenThisTurnRestrictionGatesActivation() {
        List<ActionAbility> abilities = CardData.parseActionAbilities(JOB_BROKEN_ABILITY_TEXT);
        ActionAbility ability = abilities.get(0);

        MainWindow mw = new MainWindow();
        CardData source = makeForward("Source", "Fire", 1, 5000);
        mw.placeCardInForwardZone(source);

        assertFalse(mw.canActivateAbility(ability, false, CardState.ACTIVE, 0, source, true),
                "must not be usable when no Job AVALANCHE Operative has broken this turn");

        mw.p1BrokenJobsThisTurn.add("avalanche operative");
        assertTrue(mw.canActivateAbility(ability, false, CardState.ACTIVE, 0, source, true),
                "must be usable once a Job AVALANCHE Operative was put from the field into the BZ this turn");
    }

    // =========================================================================================
    // Queen's Speedrush special ability: "Queen gains Haste and 'Queen cannot be blocked.' until
    // the end of the turn." — trailing-order sibling of the EOT-prefixed
    // "Until the end of the turn, [name] gains [traits] and '[name] cannot be blocked.'" pattern.
    // =========================================================================================

    @Test
    void queenGainsTraitsAndCannotBeBlockedTrailingParsesAndResolves() {
        CardData queen = makeForward("Queen", "Lightning", 2, 5000);
        String effectText = "Queen gains Haste and \"Queen cannot be blocked\" until the end of the turn.";

        Consumer<GameContext> fn = ActionResolver.parse(effectText, queen);
        assertNotNull(fn, "Expected \"" + effectText + "\" to parse");

        GameContext ctx = mock(GameContext.class);
        fn.accept(ctx);

        verify(ctx).boostSourceForward(queen, 0, java.util.EnumSet.of(CardData.Trait.HASTE));
        verify(ctx).setSourceForwardCannotBeBlocked(queen);
    }

    // =========================================================================================
    // "Cancel unless opponent pays" (Dull-style) — Tier 1: "Choose 1 [Summon/ability]. If your
    // opponent doesn't pay 《N》, cancel its effect." and Tier 2: the standalone body of a
    // "chosen by opponent's Summons or abilities" auto-ability.
    // =========================================================================================

    @Test
    void cancelSummonChoosingMyForwardParsesAndDelegatesToGameContext() {
        // "choosing a Forward you control" variant of the existing "targeting a Character you control" pattern.
        Consumer<GameContext> fn = ActionResolver.parse(
                "Choose 1 Summon choosing a Forward you control. Cancel its effect.", null);
        assertNotNull(fn);

        GameContext ctx = mock(GameContext.class);
        fn.accept(ctx);
        verify(ctx).cancelFilteredAbilityOnStack(any(), any(), eq(true));
    }

    @Test
    void cancelSummonTargetingMyCharacterStillParses() {
        // Regression guard: the original wording must keep working after broadening the pattern.
        Consumer<GameContext> fn = ActionResolver.parse(
                "Choose 1 Summon targeting a Character you control. Cancel its effect.", null);
        assertNotNull(fn);

        GameContext ctx = mock(GameContext.class);
        fn.accept(ctx);
        verify(ctx).cancelFilteredAbilityOnStack(any(), any(), eq(true));
    }

    @Test
    void cancelUnlessPaySummonParsesAndDelegatesToGameContext() {
        Consumer<GameContext> fn = ActionResolver.parse(
                "Choose 1 Summon. If your opponent doesn't pay 《2》, cancel its effect.", null);
        assertNotNull(fn);

        GameContext ctx = mock(GameContext.class);
        fn.accept(ctx);
        verify(ctx).cancelFilteredAbilityOnStackUnlessOpponentPays(any(), any(), eq(2));
    }

    @Test
    void cancelUnlessPayOpponentsAutoAbilityParses() {
        Consumer<GameContext> fn = ActionResolver.parse(
                "Choose 1 opponent's auto-ability. If your opponent doesn't pay 《2》, cancel its effect.", null);
        assertNotNull(fn);

        GameContext ctx = mock(GameContext.class);
        fn.accept(ctx);
        verify(ctx).cancelFilteredAbilityOnStackUnlessOpponentPays(any(), any(), eq(2));
    }

    @Test
    void cancelUnlessPayOpponentsAutoAbilityParsesWithIndefiniteArticle() {
        // Qun'mi/Vanille-style variants use "Choose an opponent's..." instead of "Choose 1 opponent's...".
        Consumer<GameContext> fn = ActionResolver.parse(
                "Choose an opponent's auto-ability. If your opponent doesn't pay 《2》, cancel its effect.", null);
        assertNotNull(fn);
    }

    @Test
    void cancelUnlessPaySummonOrAutoAbilityParsesInsideSelectFollowingActions() {
        String text = "Select 1 of the 3 following actions. "
                + "\"Choose up to 2 Forwards. Dull them.\" "
                + "\"Choose 1 Character. Freeze it.\" "
                + "\"Choose 1 Summon or auto-ability. If your opponent doesn't pay 《1》, cancel its effect.\"";
        Consumer<GameContext> fn = ActionResolver.parse(text, null);
        assertNotNull(fn, "Expected the select-list wrapper to parse");

        GameContext ctx = mock(GameContext.class);
        when(ctx.chooseActions(any(), any(), anyInt(), anyBoolean())).thenReturn(
                List.of("Choose 1 Summon or auto-ability. If your opponent doesn't pay 《1》, cancel its effect."));
        fn.accept(ctx);

        verify(ctx).cancelFilteredAbilityOnStackUnlessOpponentPays(any(), any(), eq(1));
    }

    @Test
    void cancelChosenTargetUnlessPayParsesStandaloneAndDelegatesToGameContext() {
        Consumer<GameContext> fn = ActionResolver.parse(
                "If your opponent doesn't pay 《2》, cancel their effects.", null);
        assertNotNull(fn);

        GameContext ctx = mock(GameContext.class);
        fn.accept(ctx);
        verify(ctx).cancelChosenSelectionUnlessOpponentPays(2);
    }

    @Test
    void cancelChosenTargetUnlessDiscardParsesAndDelegatesToGameContext() {
        // Kuja / Charlotte real card text: discard-cost variant of the pay-to-avoid-cancel mechanic.
        Consumer<GameContext> fn = ActionResolver.parse(
                "If your opponent doesn't discard 1 card, cancel its effect.", null);
        assertNotNull(fn);

        GameContext ctx = mock(GameContext.class);
        fn.accept(ctx);
        verify(ctx).cancelChosenSelectionUnlessOpponentDiscards(1);
    }

    @Test
    void bareCancelChosenTargetParsesToUnconditionalCancel() {
        // Consequent of Phantasmal Girl / Regis / Tama / Yuna after the optional cost is paid upstream:
        // a bare "cancel their effects" / "cancel its effect" just cancels the in-progress selection.
        for (String txt : new String[]{"Cancel their effects.", "Cancel its effect."}) {
            Consumer<GameContext> fn = ActionResolver.parse(txt, null);
            assertNotNull(fn, "Expected \"" + txt + "\" to parse");
            GameContext ctx = mock(GameContext.class);
            fn.accept(ctx);
            verify(ctx).cancelChosenSelection();
        }
    }

    @Test
    void bareCancelDoesNotMatchStackCancelWordings() {
        // Clione ("cancel the Summon's effect") and Hill Gigas ("cancel its effect and break…") must
        // NOT be swallowed by the bare-cancel pattern.
        GameContext ctx = mock(GameContext.class);
        Consumer<GameContext> a = ActionResolver.parse("Cancel the Summon's effect.", null);
        if (a != null) { a.accept(ctx); }
        Consumer<GameContext> b = ActionResolver.parse("Cancel its effect and break that Character.", null);
        if (b != null) { b.accept(ctx); }
        verify(ctx, never()).cancelChosenSelection();
    }

    @Test
    void banonRevealTopIfBackupCancelParsesAndDelegates() {
        // Banon real card text.
        Consumer<GameContext> fn = ActionResolver.parse(
                "Reveal the top card of your deck. If it is a Backup, cancel all effects choosing Banon.", null);
        assertNotNull(fn);

        GameContext ctx = mock(GameContext.class);
        fn.accept(ctx);
        verify(ctx).revealTopDeckCancelChosenIfType("Backup");
    }

    @Test
    void sirenMillTopIfNotForwardCancelParsesAndDelegates() {
        // Siren (V) real card text (post "you may" strip).
        Consumer<GameContext> fn = ActionResolver.parse(
                "Put the top card of your deck into the Break Zone. If the card put into the Break Zone "
                + "is not a Forward, cancel its effects.", null);
        assertNotNull(fn);

        GameContext ctx = mock(GameContext.class);
        fn.accept(ctx);
        verify(ctx).millTopDeckCancelChosenIfNotType("Forward");
    }

    @Test
    void chosenSelectionCancelEffectsAreRecognizedForInlineExecution() {
        // All the reactive-cancel bodies must be flagged so AutoAbilityTriggers runs them inline.
        assertTrue(ActionResolver.isChosenSelectionCancelEffect("If your opponent doesn't pay 《2》, cancel their effects."));
        assertTrue(ActionResolver.isChosenSelectionCancelEffect("If your opponent doesn't pay 《4》 or 《C》, cancel its effects."));
        assertTrue(ActionResolver.isChosenSelectionCancelEffect("If your opponent doesn't discard 1 card, cancel its effect."));
        assertTrue(ActionResolver.isChosenSelectionCancelEffect("Cancel their effects."));
        assertTrue(ActionResolver.isChosenSelectionCancelEffect("Reveal the top card of your deck. If it is a Backup, cancel all effects choosing Banon."));
        assertTrue(ActionResolver.isChosenSelectionCancelEffect("Put the top card of your deck into the Break Zone. If the card put into the Break Zone is not a Forward, cancel its effects."));
        // A non-cancel chosen effect must NOT be flagged (still goes on the stack normally).
        assertFalse(ActionResolver.isChosenSelectionCancelEffect("Your opponent discards 1 card from their hand."));
    }

    @Test
    void cancelChosenTargetUnlessPayOrCrystalParsesAndDelegatesToGameContext() {
        // Zeromus real card text: opponent may pay 4 CP OR 1 Crystal to avoid the cancel.
        Consumer<GameContext> fn = ActionResolver.parse(
                "If your opponent doesn't pay 《4》 or 《C》, cancel its effects.", null);
        assertNotNull(fn);

        GameContext ctx = mock(GameContext.class);
        fn.accept(ctx);
        verify(ctx).cancelChosenSelectionUnlessOpponentPaysOrCrystal(4, 1);
        verify(ctx, never()).cancelChosenSelectionUnlessOpponentPays(anyInt());
    }

    @Test
    void cancelChosenTargetUnlessPayWithoutCrystalStaysPlainPay() {
        // Regression guard: the plain pay form must not route to the crystal variant.
        Consumer<GameContext> fn = ActionResolver.parse(
                "If your opponent doesn't pay 《2》, cancel their effects.", null);
        assertNotNull(fn);

        GameContext ctx = mock(GameContext.class);
        fn.accept(ctx);
        verify(ctx).cancelChosenSelectionUnlessOpponentPays(2);
        verify(ctx, never()).cancelChosenSelectionUnlessOpponentPaysOrCrystal(anyInt(), anyInt());
    }

    @Test
    void cancelChosenTargetUnlessPayParsesReversedClauseOrder() {
        // White Tiger l'Cie Qun'mi's real card text: "its effect is cancelled if your opponent
        // doesn't pay 《N》." instead of "if your opponent doesn't pay 《N》, cancel its effect."
        Consumer<GameContext> fn = ActionResolver.parse(
                "its effect is cancelled if your opponent doesn't pay 《3》.", null);
        assertNotNull(fn);

        GameContext ctx = mock(GameContext.class);
        fn.accept(ctx);
        verify(ctx).cancelChosenSelectionUnlessOpponentPays(3);
    }

    @Test
    void chosenByOpponentSummonOrAbilityTriggerIsExtractedAsDistinctAutoAbility() {
        // Real Cecil text: plural subject ("are chosen") and the "or abilities" suffix previously
        // fell outside AUTO_ABILITY_PATTERN's trigger vocabulary entirely, so this clause was
        // silently dropped rather than merely failing to parse.
        String text = "When Cecil enters the field, draw 1 card.[[br]]"
                + "When 1 or more Characters you control are chosen by your opponent's Summons or abilities, "
                + "if your opponent doesn't pay 《2》, cancel their effects.";
        List<AutoAbility> abilities = CardData.parseAutoAbilities(text);

        AutoAbility chosenFa = abilities.stream()
                .filter(fa -> fa.trigger().equals("chosen by opponent's summon or ability"))
                .findFirst().orElse(null);
        assertNotNull(chosenFa, "Expected a distinct AutoAbility for the chosen-by trigger");
        assertEquals("if your opponent doesn't pay 《2》, cancel their effects.", chosenFa.effectText());

        Consumer<GameContext> fn = ActionResolver.parse(chosenFa.effectText(), null);
        assertNotNull(fn);
    }

    @Test
    void chosenByOpponentSummonOnlyTriggerStaysNarrowWhenAbilitiesNotMentioned() {
        // Existing cards that only say "...Summons" (no "or abilities") must keep the narrow
        // trigger key so they don't start reacting to ability-driven targeting too.
        String text = "First Strike[[br]] When 1 or more Forwards you control are chosen by "
                + "your opponent's Summon, its effect is cancelled if your opponent doesn't pay 《3》.";
        List<AutoAbility> abilities = CardData.parseAutoAbilities(text);

        AutoAbility chosenFa = abilities.stream()
                .filter(fa -> fa.trigger().startsWith("chosen by opponent's summon"))
                .findFirst().orElse(null);
        assertNotNull(chosenFa, "Expected a distinct AutoAbility for the chosen-by trigger");
        assertEquals("chosen by opponent's summon", chosenFa.trigger());
    }

    @Test
    void remediBreakEnteringUnlessOpponentPaysParsesAndDelegates() {
        // Remedi body: "it" is the entering card, supplied via preloaded targets.
        Consumer<GameContext> fn = ActionResolver.parse(
                "If your opponent doesn't pay 《2》, break it.", null);
        assertNotNull(fn);

        GameContext ctx = mock(GameContext.class);
        ForwardTarget entering = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);
        when(ctx.consumePreloadedTargets()).thenReturn(List.of(entering));
        doAnswer(inv -> { ((Runnable) inv.getArgument(1)).run(); return null; })
                .when(ctx).opponentMayPayToPreventAction(eq(2), any());

        fn.accept(ctx);

        verify(ctx).opponentMayPayToPreventAction(eq(2), any());
        verify(ctx).breakTarget(entering);
    }

    @Test
    void ifOppNotPayActionIsGenericOverStandardActions() {
        // The wrapper must reuse standard target actions, not be hardcoded to "break".
        assertTrue(ActionResolver.isIfOppNotPayAction("If your opponent doesn't pay 《2》, break it."));
        assertTrue(ActionResolver.isIfOppNotPayAction("If your opponent doesn't pay 《1》, Freeze it."));
        // Cid Raines' self-sacrifice form is NOT this wrapper (so it isn't inline-fired).
        assertFalse(ActionResolver.isIfOppNotPayAction(
                "put Cid Raines into the Break Zone. When you do so, break it."));

        // Freeze variant delegates to freezeTarget on the preloaded target.
        Consumer<GameContext> fn = ActionResolver.parse("If your opponent doesn't pay 《1》, Freeze it.", null);
        assertNotNull(fn);
        GameContext ctx = mock(GameContext.class);
        ForwardTarget entering = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);
        when(ctx.consumePreloadedTargets()).thenReturn(List.of(entering));
        doAnswer(inv -> { ((Runnable) inv.getArgument(1)).run(); return null; })
                .when(ctx).opponentMayPayToPreventAction(eq(1), any());
        fn.accept(ctx);
        verify(ctx).freezeTarget(entering);
    }

    @Test
    void arkasodaraChooseThenBreakUnlessOpponentPaysParsesAndDelegates() {
        // Arkasodara (20-064C) ETF: choose a dull Forward, then break it unless the opponent pays 3.
        Consumer<GameContext> fn = ActionResolver.parse(
                "choose 1 dull Forward. If your opponent doesn't pay 《3》, break it.", null);
        assertNotNull(fn);

        GameContext ctx = mock(GameContext.class);
        // Selection returns one opponent Forward target.
        ForwardTarget t = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);
        when(ctx.selectCharacters(
                anyInt(), anyBoolean(), anyBoolean(), anyBoolean(),
                any(), any(), anyInt(), any(), anyInt(), any(),
                anyBoolean(), anyBoolean(), anyBoolean(),
                any(), any(), any(), any(), anyBoolean(), any(), anyBoolean()
        )).thenReturn(List.of(t));
        when(ctx.consumePreloadedTargets()).thenReturn(null);
        // Opponent declines to pay → the action runs.
        doAnswer(inv -> { ((Runnable) inv.getArgument(1)).run(); return null; })
                .when(ctx).opponentMayPayToPreventAction(eq(3), any());

        fn.accept(ctx);

        verify(ctx).opponentMayPayToPreventAction(eq(3), any());
        verify(ctx).breakTarget(t);
    }

    @Test
    void ceodoreChooseWarpCardFromBreakZoneParsesAndDelegates() {
        // Ceodore (25-044C) ETF effect body.
        Consumer<GameContext> fn = ActionResolver.parse(
                "choose 1 Card with Warp in your Break Zone. Add it to your hand.", null);
        assertNotNull(fn);

        GameContext ctx = mock(GameContext.class);
        fn.accept(ctx);
        verify(ctx).chooseWarpCardFromBreakZoneToHand();
    }
}
