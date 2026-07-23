package shufflingway;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
    // Necron: ETB "choose 1 Forward of cost 5 or less opponent controls. Remove it from the
    // game for as long as Necron is on the field." + action "《Ice》《Dull》: Choose 1 card
    // removed by Necron's ability. Put it into the Break Zone." — temporary exile that returns
    // when Necron leaves the field, unless first sent to the Break Zone.
    // =========================================================================================

    @Test
    void necronEtbRemovesChosenForwardWhileNecronOnField() {
        CardData necron = mock(CardData.class);
        when(necron.name()).thenReturn("Necron");

        Consumer<GameContext> fn = ActionResolver.parse(
                "Choose 1 Forward of cost 5 or less opponent controls. "
                + "Remove it from the game for as long as Necron is on the field.", necron);
        assertNotNull(fn);

        GameContext ctx = mock(GameContext.class);
        ForwardTarget t = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);
        when(ctx.consumePreloadedTargets()).thenReturn(List.of(t));

        fn.accept(ctx);

        verify(ctx).removeTargetFromGameWhileNamedCardOnField(t, "Necron");
        verify(ctx, never()).removeTargetFromGame(any());
    }

    @Test
    void necronActionPutsRemovedCardIntoBreakZone() {
        CardData necron = mock(CardData.class);
        when(necron.name()).thenReturn("Necron");

        Consumer<GameContext> fn = ActionResolver.parse(
                "Choose 1 card removed by Necron's ability. Put it into the Break Zone.", necron);
        assertNotNull(fn);

        GameContext ctx = mock(GameContext.class);
        fn.accept(ctx);

        verify(ctx).putCardRemovedBySourceIntoBreakZone(necron);
    }

    @Test
    void necronActionDoesNotFireForDifferentlyNamedSource() {
        CardData other = mock(CardData.class);
        when(other.name()).thenReturn("Not Necron");

        assertNull(ActionResolver.parse(
                "Choose 1 card removed by Necron's ability. Put it into the Break Zone.", other));
    }

    // =========================================================================================
    // Auron: "During this turn, the next damage dealt to you becomes 0 and deal Auron 8000
    // damage instead. You can only use this ability once per turn." — player shield whose
    // consumption redirects the damage to Auron.
    // =========================================================================================

    @Test
    void auronPlayerShieldWithRedirectParses() {
        Consumer<GameContext> fn = ActionResolver.parse(
                "During this turn, the next damage dealt to you becomes 0 and deal Auron 8000 damage "
                + "instead. You can only use this ability once per turn.", null);
        assertNotNull(fn);

        GameContext ctx = mock(GameContext.class);
        fn.accept(ctx);

        verify(ctx).shieldPlayerNextDamageRedirect("Auron", 8000);
        verify(ctx, never()).shieldPlayerNextDamage();
    }

    // =========================================================================================
    // Sephiroth (hand ability): "《Ice》《2》, remove Sephiroth in your hand from the game:
    // Choose 1 dull Forward. Break it. Until the end of your turn, you can cast Sephiroth
    // removed by this ability's cost. You can only use this ability if Sephiroth is in your
    // hand." — RFG-from-hand cost + break + RFP-castable-this-turn followup.
    // =========================================================================================

    private static final String SEPHIROTH_EFFECT_TEXT =
            "Choose 1 dull Forward. Break it. Until the end of your turn, you can cast Sephiroth "
            + "removed by this ability's cost. You can only use this ability if Sephiroth is in your hand.";

    @Test
    void sephirothAbilityCostParsesAsHandRfgWithHandRestriction() {
        List<ActionAbility> abilities = CardData.parseActionAbilities(
                "《Ice》《2》, remove Sephiroth in your hand from the game: " + SEPHIROTH_EFFECT_TEXT);
        assertEquals(1, abilities.size());
        ActionAbility a = abilities.get(0);
        assertTrue(a.whileCardInHand(), "hand-only restriction should be set");
        assertEquals(1, a.removeFromGameCosts().size());
        RemoveFromGameCost rfg = a.removeFromGameCosts().get(0);
        assertEquals("HAND", rfg.zone());
        assertEquals("Sephiroth", rfg.cardName());
        assertEquals(1, rfg.count());
    }

    @Test
    void sephirothBreaksDullForwardAndRegistersRfgCostCardCastable() {
        Consumer<GameContext> fn = ActionResolver.parse(SEPHIROTH_EFFECT_TEXT, null);
        assertNotNull(fn);

        GameContext ctx = mock(GameContext.class);
        ForwardTarget t = new ForwardTarget(false, 1, ForwardTarget.CardZone.FORWARD);
        when(ctx.consumePreloadedTargets()).thenReturn(List.of(t));

        fn.accept(ctx);

        verify(ctx).breakTarget(t);
        verify(ctx).makeRfgCostCardCastableThisTurn("Sephiroth");
    }

    // =========================================================================================
    // Chaos: "Choose 1 Forward. Break it. You can only use this ability during your turn and if
    // Chaos is in the Break Zone." — combined your-turn-only + BZ-activation restriction.
    // =========================================================================================

    private static final String CHAOS_ABILITY_TEXT =
            "Choose 1 Forward. Break it. You can only use this ability during your turn "
            + "and if Chaos is in the Break Zone.";

    @Test
    void chaosCombinedRestrictionIsFullyRecognized() {
        // The combined restriction must be stripped as one sentence: the coverage description
        // should see a clean "Choose 1 Forward. Break it." with no "?" (unrecognized) layer.
        String desc = ActionResolver.fullDescription(CHAOS_ABILITY_TEXT, null);
        assertNotNull(desc);
        assertFalse(desc.contains("?"), "restriction fragment left a partially-recognized layer: " + desc);
    }

    @Test
    void chaosEffectBreaksChosenForwardAndKeepsRestrictions() {
        Consumer<GameContext> fn = ActionResolver.parse(CHAOS_ABILITY_TEXT, null);
        assertNotNull(fn);

        GameContext ctx = mock(GameContext.class);
        ForwardTarget t = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);
        when(ctx.consumePreloadedTargets()).thenReturn(List.of(t));

        fn.accept(ctx);

        verify(ctx).breakTarget(t);
    }

    // =========================================================================================
    // Gau: "Dull active Gau: Choose 1 Monster. Until the end of the turn, it also becomes a
    // Forward with 8000 power." — bare-name dull cost + chosen-Monster temporary-Forward effect.
    // =========================================================================================

    @Test
    void gauAbilityCostParsesAsBareNameDull() {
        List<ActionAbility> abilities = CardData.parseActionAbilities(
                "Dull active Gau: Choose 1 Monster. Until the end of the turn, "
                + "it also becomes a Forward with 8000 power.");
        assertEquals(1, abilities.size());
        ActionAbility a = abilities.get(0);
        assertEquals(1, a.dullForwardCosts().size());
        DullForwardCost dc = a.dullForwardCosts().get(0);
        assertEquals("Gau", dc.cardName());
        assertEquals("active", dc.condition());
        assertEquals("Choose 1 Monster. Until the end of the turn, it also becomes a Forward with 8000 power.",
                a.effectText().trim());
    }

    @Test
    void gauChosenMonsterBecomesForwardUntilEot() {
        Consumer<GameContext> fn = ActionResolver.parse(
                "Choose 1 Monster. Until the end of the turn, it also becomes a Forward with 8000 power.", null);
        assertNotNull(fn);

        GameContext ctx = mock(GameContext.class);
        ForwardTarget t = new ForwardTarget(true, 0, ForwardTarget.CardZone.MONSTER);
        when(ctx.consumePreloadedTargets()).thenReturn(List.of(t));

        fn.accept(ctx);

        verify(ctx).makeTargetTemporaryForward(t, 8000);
    }

    // =========================================================================================
    // Yuna Doublecast: "When you cast a Summon this turn, you may cast 1 Summon from your hand
    // with a cost inferior to that of the Summon you cast without paying its cost." — turn-long
    // rolling free-Summon field effect.
    // =========================================================================================

    @Test
    void doublecastActivatesRollingFreeSummonEffect() {
        Consumer<GameContext> fn = ActionResolver.parse(
                "When you cast a Summon this turn, you may cast 1 Summon from your hand with a cost "
                + "inferior to that of the Summon you cast without paying its cost.", null);
        assertNotNull(fn);

        GameContext ctx = mock(GameContext.class);
        fn.accept(ctx);

        verify(ctx).activateDoublecastFreeSummons();
    }

    // =========================================================================================
    // Maat: "Maat gains "Maat cannot be broken by opposing Summons or abilities that don't deal
    // damage." until the end of the turn." — standalone quoted-gains form of the non-damage-only
    // cannot-be-broken shield.
    // =========================================================================================

    private static final String MAAT_SHIELD_TEXT =
            "Maat gains \"Maat cannot be broken by opposing Summons or abilities that don't deal "
            + "damage.\" until the end of the turn.";

    @Test
    void maatGainsNonDamageBreakShieldUntilEndOfTurn() {
        CardData maat = mock(CardData.class);
        when(maat.name()).thenReturn("Maat");

        Consumer<GameContext> fn = ActionResolver.parse(MAAT_SHIELD_TEXT, maat);
        assertNotNull(fn);

        CardData other = mock(CardData.class);
        when(other.name()).thenReturn("Zidane");

        GameContext ctx = mock(GameContext.class);
        when(ctx.isP1()).thenReturn(true);
        when(ctx.p1ForwardCount()).thenReturn(2);
        when(ctx.p1Forward(0)).thenReturn(other);
        when(ctx.p1Forward(1)).thenReturn(maat);

        fn.accept(ctx);

        verify(ctx).shieldCannotBeBrokenByNonDmg(new ForwardTarget(true, 1, ForwardTarget.CardZone.FORWARD));
        verify(ctx, never()).shieldSourceForward(any());
    }

    @Test
    void maatShieldDoesNotFireForDifferentlyNamedSource() {
        CardData other = mock(CardData.class);
        when(other.name()).thenReturn("Not Maat");

        assertNull(ActionResolver.parse(MAAT_SHIELD_TEXT, other));
    }

    // =========================================================================================
    // "During this turn, if a Job Dancer or Card Name Dancer you control is dealt damage by a
    // Summon or an ability, the damage becomes 0 instead." — persistent turn-scoped, filtered
    // own-side Summon/ability damage nullification (also covers Dancers entering later).
    // =========================================================================================

    private static final String DANCER_SHIELD_TEXT =
            "During this turn, if a Job Dancer or Card Name Dancer you control is dealt damage "
            + "by a Summon or an ability, the damage becomes 0 instead.";

    @Test
    void registersPersistentFilterShieldingOnlyDancers() {
        Consumer<GameContext> fn = ActionResolver.parse(DANCER_SHIELD_TEXT, null);
        assertNotNull(fn);

        GameContext ctx = mock(GameContext.class);
        fn.accept(ctx);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Predicate<CardData>> captor =
                ArgumentCaptor.forClass((Class<Predicate<CardData>>) (Class<?>) Predicate.class);
        verify(ctx).shieldOwnForwardsAbilityDamageFilter(captor.capture());
        Predicate<CardData> filter = captor.getValue();

        CardData jobDancer = mock(CardData.class);
        when(jobDancer.hasJob("Dancer")).thenReturn(true);
        CardData namedDancer = mock(CardData.class);
        when(namedDancer.hasJob("Dancer")).thenReturn(false);
        when(namedDancer.name()).thenReturn("Dancer");
        CardData other = mock(CardData.class);
        when(other.hasJob("Dancer")).thenReturn(false);
        when(other.name()).thenReturn("Warrior of Light");

        assertTrue(filter.test(jobDancer), "Job Dancer should be shielded");
        assertTrue(filter.test(namedDancer), "Card Name Dancer should be shielded");
        assertFalse(filter.test(other), "Non-Dancer should not be shielded");
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
    // Sahagin Chief: "When a Water Character other than Sahagin Chief enters your field, place 1
    // Monster Counter on Sahagin Chief." — "other than Sahagin Chief" excludes only the source
    // instance (a card naming itself means that specific card), so another copy of Sahagin Chief
    // entering the field must still place a counter on the existing one.
    // =========================================================================================

    private static final String SAHAGIN_CHIEF_TEXT =
            "When a Water Character other than Sahagin Chief enters your field, place 1 Monster Counter on Sahagin Chief.[[br]]   "
            + "Put Sahagin Chief into the Break Zone: Choose 1 Forward you control. Return it to its owner's hand.[[br]]   "
            + "Put Sahagin Chief into the Break Zone: Choose up to 2 Forwards. Return them to their owners' hands. "
            + "You can only use this ability if 3 or more Monster Counters are placed on Sahagin Chief.";

    private static CardData makeSahaginChief() {
        // multicard = true: Sahagin Chief is exempt from the same-name uniqueness rule, so two
        // copies can share the field.
        return new CardData(null, "Sahagin Chief", "Water", 2, 5000, "Monster", false, 0, false, true,
                Set.of(), 0, List.of(), null, List.of(),
                CardData.parseActionAbilities(SAHAGIN_CHIEF_TEXT), CardData.parseAutoAbilities(SAHAGIN_CHIEF_TEXT),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                false, false, null, false, false, false, false, false, false,
                null, null, null, SAHAGIN_CHIEF_TEXT);
    }

    @Test
    void sahaginChiefCountersFromAnotherCopyButNotItself() {
        MainWindow mw = new MainWindow();
        CardData chiefA = makeSahaginChief();
        CardData chiefB = makeSahaginChief();
        mw.gameState.getIdentity().put(chiefA, true);
        mw.gameState.getIdentity().put(chiefB, true);

        // Placing chiefA fires its own enters-field trigger; its watcher sees chiefA entering and
        // must self-exclude ("other than Sahagin Chief" = this instance), so no counter yet.
        mw.placeCardInMonsterZone(chiefA);
        assertEquals(0, mw.gameState.getCounters(chiefA, "Monster"),
                "Sahagin Chief entering must not counter itself");

        // chiefB enters: chiefA's watcher fires (a different Sahagin Chief instance, still a Water
        // Character "other than" the source), while chiefB self-excludes. Both stay on the field
        // because the multicard is exempt from the same-name uniqueness rule.
        mw.placeCardInMonsterZone(chiefB);
        assertEquals(2, mw.p1MonsterCards.size(), "multicard Sahagin Chiefs both remain on the field");
        assertEquals(1, mw.gameState.getCounters(chiefA, "Monster"),
                "A different Sahagin Chief entering should counter the existing one");
        assertEquals(0, mw.gameState.getCounters(chiefB, "Monster"),
                "The entering Sahagin Chief must not counter itself");
    }

    // Sahagin Chief's "Choose up to 2 Forwards. Return them to their owners' hands." — regression
    // for two return-to-hand bugs:
    //  1) Returning two Forwards controlled by the SAME player must return both. Each return
    //     compacts that player's Forward list, so processing them in selection (ascending) order
    //     left the second target's index stale and only one card came back.
    //  2) A Monster (or Backup) that has become a Forward this turn is a legal target and must be
    //     returned from its actual zone, not silently skipped because its zone isn't FORWARD.
    private static Consumer<GameContext> sahaginReturnEffect() {
        Consumer<GameContext> fn = ActionResolver.parse(
                "Choose up to 2 Forwards. Return them to their owners' hands.", null);
        assertNotNull(fn, "Sahagin Chief return-to-hand effect should parse");
        return fn;
    }

    @Test
    void sahaginChiefReturnsBothForwardsControlledBySamePlayer() {
        MainWindow mw = new MainWindow();
        CardData a = makeForward("Opp A", "Water", 2, 5000);
        CardData b = makeForward("Opp B", "Water", 3, 6000);
        mw.gameState.getIdentity().put(a, false);   // owned by P2
        mw.gameState.getIdentity().put(b, false);
        mw.placeP2CardInForwardZone(a);             // P2 idx 0
        mw.placeP2CardInForwardZone(b);             // P2 idx 1

        GameContext ctx = mw.buildGameContext(true); // P1 activates the ability
        ctx.preloadTargets(List.of(
                new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD),
                new ForwardTarget(false, 1, ForwardTarget.CardZone.FORWARD)));

        sahaginReturnEffect().accept(ctx);

        assertTrue(mw.p2ForwardCards.isEmpty(), "both opponent Forwards should leave the field");
        assertTrue(mw.gameState.getP2Hand().contains(a), "Opp A should be back in P2's hand");
        assertTrue(mw.gameState.getP2Hand().contains(b), "Opp B should be back in P2's hand");
    }

    @Test
    void sahaginChiefReturnsMonsterActingAsForward() {
        MainWindow mw = new MainWindow();
        CardData monster = new CardData(null, "Water Beast", "Water", 2, 5000, "Monster",
                false, 0, false, false, Set.of(), 0, List.of(), null, List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), false, false, null, false, false, false, false, false, false,
                null, null, null, "");
        mw.gameState.getIdentity().put(monster, true);  // owned by P1
        mw.placeCardInMonsterZone(monster);             // P1 monster idx 0
        // The monster becomes a Forward until end of turn — now a legal "Forward" target.
        mw.p1MonsterTempForwardPower.put(monster, 5000);
        assertTrue(mw.isP1MonsterTemporarilyForward(0), "monster should count as a Forward");

        GameContext ctx = mw.buildGameContext(true);
        ctx.preloadTargets(List.of(new ForwardTarget(true, 0, ForwardTarget.CardZone.MONSTER)));

        sahaginReturnEffect().accept(ctx);

        assertTrue(mw.p1MonsterCards.isEmpty(), "the monster-as-Forward should leave the field");
        assertTrue(mw.gameState.getP1Hand().contains(monster), "the monster should be back in P1's hand");
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
    // Wakka: "EX BURST When Wakka enters the field, reveal the top 3 cards of your deck. Add 1
    // Water or Category X card among them to your hand and return the other cards to the bottom
    // of your deck in any order." — the element-OR-category disjunction on the hand-add filter,
    // fired via the "enters the field" auto ability (despite also carrying the EX Burst marker).
    // =========================================================================================

    private static final String WAKKA_TEXT =
            "[[ex]]EX BURST[[/]] When Wakka enters the field, reveal the top 3 cards of your deck. "
            + "Add 1 Water or Category X card among them to your hand and return the other cards to "
            + "the bottom of your deck in any order.";

    @Test
    void wakkaAutoAbilityParsesAsEntersFieldTrigger() {
        List<AutoAbility> autos = CardData.parseAutoAbilities(WAKKA_TEXT);
        assertEquals(1, autos.size());
        AutoAbility fa = autos.get(0);
        assertTrue(fa.trigger().contains("enter"), "Expected an 'enters the field' trigger, got: " + fa.trigger());
        assertEquals("Wakka", fa.triggerCard());
        assertTrue(fa.effectText().toLowerCase().startsWith("reveal the top 3 cards of your deck"),
                "Unexpected effect text: " + fa.effectText());
    }

    @Test
    void wakkaRevealAddWaterOrCategoryXParsesAndResolves() {
        String effectText = CardData.parseAutoAbilities(WAKKA_TEXT).get(0).effectText();

        Consumer<GameContext> fn = ActionResolver.parse(effectText, null);
        assertNotNull(fn, "Expected \"" + effectText + "\" to parse");

        GameContext ctx = mock(GameContext.class);
        fn.accept(ctx);

        // element "Water" is a disjunct (orElementFilter, last arg), NOT the AND-gate elementFilter.
        verify(ctx).revealTopAddUpToMatchingRestBottom(3, 1, null, "X", null, null, -1, null, "Water");
    }

    private static CardData makeWakka() {
        return new CardData(null, "Wakka", "Water", 4, 7000, "Forward", false, 0, true, false,
                Set.of(), 0, List.of(), null, List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                false, false, null, false, false, false, false, false, false,
                null, null, null, WAKKA_TEXT);
    }

    @Test
    void wakkaExBurstStripsWhenClauseAndResolvesReveal() {
        CardData wakka = makeWakka();
        // When revealed as EX Burst damage the "When Wakka enters the field," trigger clause is
        // dropped and the bare reveal action runs.
        String ex = wakka.exBurstEffect();
        assertTrue(ex.toLowerCase().startsWith("reveal the top 3 cards of your deck"),
                "EX Burst effect should drop the 'When … enters the field,' clause: " + ex);

        Consumer<GameContext> fn = ActionResolver.parse(ex, wakka);
        assertNotNull(fn, "Expected EX Burst text to parse: " + ex);
        GameContext ctx = mock(GameContext.class);
        fn.accept(ctx);
        verify(ctx).revealTopAddUpToMatchingRestBottom(3, 1, null, "X", null, null, -1, null, "Water");
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
    // Quina's action ability: "Until the end of the turn, Quina gains +2000 power and Quina
    // cannot be chosen by your opponent's abilities. You can only use this ability once per
    // turn." — EOT power boost + opponent-targeting protection in a single sentence.
    // =========================================================================================

    private static final String QUINA_ABILITY_TEXT =
            "Remove the top 5 cards of your deck from the game: Until the end of the turn, "
            + "Quina gains +2000 power and Quina cannot be chosen by your opponent's abilities. "
            + "You can only use this ability once per turn.";

    @Test
    void quinaPowerBoostAndCannotBeChosenParsesCostAndOncePerTurnRestriction() {
        List<ActionAbility> abilities = CardData.parseActionAbilities(QUINA_ABILITY_TEXT);
        assertEquals(1, abilities.size());
        ActionAbility ability = abilities.get(0);

        assertTrue(ability.oncePerTurn());
        assertEquals(1, ability.removeFromGameCosts().size());
        RemoveFromGameCost cost = ability.removeFromGameCosts().get(0);
        assertEquals("DECK", cost.zone());
        assertEquals(5, cost.count());
    }

    @Test
    void quinaPowerBoostAndCannotBeChosenResolves() {
        CardData quina = makeForward("Quina", "Water", 2, 5000);
        List<ActionAbility> abilities = CardData.parseActionAbilities(QUINA_ABILITY_TEXT);
        assertEquals(1, abilities.size());

        Consumer<GameContext> fn = ActionResolver.parse(abilities.get(0).effectText(), quina);
        assertNotNull(fn, "Expected Quina's effect text to parse");

        GameContext ctx = mock(GameContext.class);
        fn.accept(ctx);

        verify(ctx).boostSourceForward(quina, 2000, java.util.EnumSet.noneOf(CardData.Trait.class));
        verify(ctx).shieldNamedCardCannotBeChosen("Quina", false, true);
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

    // =========================================================================================
    // Cloud of Darkness: "When Cloud of Darkness enters the field, if your opponent has 2 cards
    // or less in their hand, your opponent selects 1 Forward they control. Put it into the Break
    // Zone." — an ETF trigger gated on the opponent's hand size, whose inner effect makes the
    // opponent send one of their own Forwards to the Break Zone. Regression guard for the
    // OPPONENT_HAND_CONDITION_PATTERN bug where "N cards or less in their hand" (the real card
    // wording, one "cards") never matched because the pattern demanded a second "cards".
    // =========================================================================================

    private static final String CLOUD_OF_DARKNESS_TEXT =
            "When Cloud of Darkness enters the field, if your opponent has 2 cards or less in their hand, "
            + "your opponent selects 1 Forward they control. Put it into the Break Zone.";

    private static CardData makeCloudOfDarkness() {
        return new CardData(null, "Cloud of Darkness", "Dark", 5, 9000, "Forward", false, 0, false, false,
                Set.of(), 0, List.of(), null, List.of(),
                List.of(), CardData.parseAutoAbilities(CLOUD_OF_DARKNESS_TEXT),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                false, false, null, false, false, false, false, false, false,
                null, null, null, CLOUD_OF_DARKNESS_TEXT);
    }

    @Test
    void cloudOfDarknessParsesAsEntersFieldTriggerWithHandGatedBreak() {
        CardData cloud = makeCloudOfDarkness();
        List<AutoAbility> autos = cloud.autoAbilities();
        assertEquals(1, autos.size(), "Cloud of Darkness has one auto-ability");
        AutoAbility fa = autos.get(0);
        assertEquals("enters the field", fa.trigger());
        assertEquals("Cloud of Darkness", fa.triggerCard());
        assertNotNull(ActionResolver.parse(fa.effectText(), cloud),
                "the hand-gated opponent-break effect should parse");
    }

    /** Drives the parsed ETF effect against a P1-activated context and returns the MainWindow. */
    private static MainWindow runCloudEffect(int opponentHandSize) {
        MainWindow mw = new MainWindow();
        CardData cloud = makeCloudOfDarkness();
        mw.gameState.getIdentity().put(cloud, true);           // Cloud owned/controlled by P1

        CardData oppForward = makeForward("Opp Forward", "Fire", 2, 5000);
        mw.gameState.getIdentity().put(oppForward, false);     // owned by P2
        mw.placeP2CardInForwardZone(oppForward);               // P2 idx 0

        for (int i = 0; i < opponentHandSize; i++) {
            CardData filler = makeForward("Filler " + i, "Ice", 1, 1000);
            mw.gameState.getIdentity().put(filler, false);
            mw.gameState.getP2Hand().add(filler);
        }

        GameContext ctx = mw.buildGameContext(true);           // P1 activates (opponent = P2)
        ctx.preloadTargets(List.of(new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD)));

        AutoAbility fa = cloud.autoAbilities().get(0);
        ActionResolver.parse(fa.effectText(), cloud).accept(ctx);
        return mw;
    }

    @Test
    void cloudOfDarknessBreaksOpponentForwardWhenHandIsSmall() {
        MainWindow mw = runCloudEffect(2);   // opponent hand size 2 → condition met (≤ 2)
        assertTrue(mw.p2ForwardCards.isEmpty(), "the opponent's Forward should leave the field");
        assertEquals(1, mw.gameState.getP2BreakZone().size(),
                "the opponent's Forward should be in their Break Zone");
    }

    @Test
    void cloudOfDarknessDoesNothingWhenOpponentHandTooLarge() {
        MainWindow mw = runCloudEffect(3);   // opponent hand size 3 → condition fails (> 2)
        assertEquals(1, mw.p2ForwardCards.size(), "the opponent's Forward should remain on the field");
        assertTrue(mw.gameState.getP2BreakZone().isEmpty(),
                "nothing should be put into the Break Zone");
    }

    // =========================================================================================
    // Physalis: "When Physalis enters the field or attacks, if your opponent has 3 cards or less
    // in their hand, select 1 of the 2 following actions. If your opponent has no cards in their
    // hand, select up to 2 of the 2 following actions instead. "Choose 1 Character. Dull it and
    // Freeze it." "Draw 1 card."" plus a "《S》《Ice》《Ice》: Choose 1 Forward. Deal it 10000 damage.
    // That Forward's controller discards 1 card." special ability.
    //
    // Three things had to be wired up:
    //  1) The "Dull it and Freeze it" quoted sub-action used to collide with ACTION_ABILITY_PATTERN
    //     (the case-insensitive bare-name dull-cost branch matched the pronoun "it" and its "and …"
    //     continuation devoured the following [[s]] ability's name and 《S》 cost).
    //  2) The base gate — the modal must only fire when the opponent has ≤ 3 cards in hand.
    //  3) The empty-hand upgrade — with an empty opponent hand the player selects up to 2 (not 1).
    // =========================================================================================

    private static final String PHYSALIS_TEXT =
            "When Physalis enters the field or attacks, if your opponent has 3 cards or less in their hand, "
            + "select 1 of the 2 following actions. If your opponent has no cards in their hand, select up to 2 "
            + "of the 2 following actions instead. \"Choose 1 Character. Dull it and Freeze it.\" \"Draw 1 card.\"[[br]]   "
            + "[[s]]Premium Physalis Bullet [[/]]《S》《Ice》《Ice》: Choose 1 Forward. "
            + "Deal it 10000 damage. That Forward's controller discards 1 card from their hand.";

    @Test
    void physalisParsesModalAutoAbilityAndSpecialAbilityWithoutCollision() {
        List<AutoAbility> autos = CardData.parseAutoAbilities(PHYSALIS_TEXT);
        assertEquals(1, autos.size(), "one auto-ability (the modal ETF/attack trigger)");
        AutoAbility fa = autos.get(0);
        assertEquals("enters the field or attacks", fa.trigger());
        assertEquals("Physalis", fa.triggerCard());
        assertNotNull(ActionResolver.parse(fa.effectText(), null), "the modal effect should parse");

        // The [[s]] special ability must survive parsing alongside the "Dull it and Freeze it" quote.
        List<ActionAbility> actions = CardData.parseActionAbilities(PHYSALIS_TEXT);
        assertEquals(1, actions.size(), "one action ability (the S ability)");
        ActionAbility s = actions.get(0);
        assertEquals("Premium Physalis Bullet", s.abilityName());
        assertTrue(s.isSpecial(), "the 《S》 cost must mark it Special");
        assertEquals(List.of("Ice", "Ice"), s.cpCost());
    }

    @Test
    void physalisSelectsOneNormallyButUpToTwoWhenOpponentHandEmpty() {
        String effect = CardData.parseAutoAbilities(PHYSALIS_TEXT).get(0).effectText();
        Consumer<GameContext> fn = ActionResolver.parse(effect, null);
        assertNotNull(fn);

        // Opponent has cards (2): base modal — select exactly 1.
        GameContext some = mock(GameContext.class);
        when(some.opponentHandSize()).thenReturn(2);
        fn.accept(some);
        verify(some).chooseActions(any(), any(), eq(1), eq(false));

        // Opponent hand empty: upgrade — select up to 2.
        GameContext empty = mock(GameContext.class);
        when(empty.opponentHandSize()).thenReturn(0);
        fn.accept(empty);
        verify(empty).chooseActions(any(), any(), eq(2), eq(true));
    }

    @Test
    void physalisBaseGateChecksOpponentHandSize() throws Exception {
        MainWindow mw = new MainWindow();
        java.lang.reflect.Method check = AutoAbilityTriggers.class
                .getDeclaredMethod("checkAutoAbilityCondition", String.class, boolean.class);
        check.setAccessible(true);
        String cond = "your opponent has 3 cards or less in their hand";

        // P1 owns the ability → opponent is P2.
        setHandSize(mw, false, 3);
        assertEquals(true,  check.invoke(mw.autoAbilityTriggers, cond, true), "3 ≤ 3 — condition met");
        setHandSize(mw, false, 4);
        assertEquals(false, check.invoke(mw.autoAbilityTriggers, cond, true), "4 > 3 — condition fails");
        setHandSize(mw, false, 0);
        assertEquals(true,  check.invoke(mw.autoAbilityTriggers, cond, true), "empty hand — condition met");
    }

    /** Sets the given player's hand to exactly {@code n} filler cards. */
    private static void setHandSize(MainWindow mw, boolean isP1, int n) {
        List<CardData> hand = isP1 ? mw.gameState.getP1Hand() : mw.gameState.getP2Hand();
        hand.clear();
        for (int i = 0; i < n; i++) hand.add(makeForward("Filler " + i, "Ice", 1, 1000));
    }

    // =========================================================================================
    // Mog (VI) 9-117C "Dusk Requiem" (《S》《Water》《Water》《Dull》): "Choose 1 Forward. Reveal the top
    // card of your deck. If the revealed card's CP cost is an even number, return chosen Forward to
    // its owner's hand … If … odd …, deal the chosen Forward 4000 damage, dull it and Freeze it …"
    //
    // Guards two bugs that broke this wired-up ability:
    //  1) The preceding "…leaves the field, discard 2 cards from your hand." auto-ability let the
    //     discard-cost group of ACTION_ABILITY_PATTERN run across the [[br]][[s]] markup to the S
    //     ability's colon, swallowing its name and 《S》 cost (name lost, isSpecial cleared).
    //  2) The dedicated reveal-cost-parity parser sat AFTER tryParseChooseCharacter in the parse
    //     chain, so the generic ChooseCharacter parser claimed the effect and only partially
    //     handled it — the parity branch has to be tried first.
    // =========================================================================================

    private static final String MOG_VI_TEXT =
            "When Mog (VI) enters the field, draw 2 cards.[[br]] When Mog (VI) leaves the field, discard 2 cards from your hand.[[br]]"
            + "[[s]]Dusk Requiem[[/]] 《S》《Water》《Water》《Dull》: Choose 1 Forward. Reveal the top card of your deck. "
            + "If the revealed card's CP cost is an even number, return chosen Forward to its owner's hand. Add the revealed card to your hand. "
            + "If the revealed card's CP cost is an odd number, deal the chosen Forward 4000 damage, dull it and Freeze it. Add the revealed card to your hand.";

    @Test
    void mogViDuskRequiemKeepsSpecialIdentityAndRoutesToParityParser() {
        List<ActionAbility> actions = CardData.parseActionAbilities(MOG_VI_TEXT);
        assertEquals(1, actions.size(), "one action ability (the S ability)");
        ActionAbility s = actions.get(0);

        // (1) The [[s]] identity must survive the neighbouring discard-cost auto-ability.
        assertEquals("Dusk Requiem", s.abilityName());
        assertTrue(s.isSpecial(), "《S》 must mark it Special");
        assertTrue(s.requiresDull(), "《Dull》 is part of the cost");
        assertEquals(List.of("Water", "Water"), s.cpCost());

        // (2) The effect must reach the dedicated reveal-cost-parity parser, not generic ChooseCharacter.
        assertEquals("ChooseFwdRevealCostParity",
                ActionResolver.matchedPatternName(s.effectText(), null));
        assertNotNull(ActionResolver.parse(s.effectText(), null));
    }

    // =========================================================================================
    // Ability-granting cards. These grant a quoted "《cost》: effect" ability to a chosen Forward.
    // The quoted grant used to (a) truncate the ETB auto-ability effect at the 《cost》: inside the
    // quote and (b) be mis-parsed as the granting card's OWN action ability. Now the grant text is
    // captured whole, the card exposes no spurious own-abilities, and the grant is applied.
    //   • Machinist 12-057C — grants an EOT ability to up to 2 Forwards.
    //   • Medusa 22-034H     — places a Petrification Counter (drives a cannot-attack/block
    //                          restriction + a "《5》: remove counters" ability off the counter).
    //   • Innocence 13-137S  — a Break-Zone-gated SELF grant; must stay intact (regression guard).
    // =========================================================================================

    private static final String MACHINIST_TEXT =
            "When Machinist enters the field, choose up to 2 Forwards. Until the end of the turn, "
            + "they gain \"《Dull》: Choose 1 Forward. Deal it 4000 damage.\"";
    private static final String MEDUSA_TEXT =
            "When Medusa enters the field, choose 1 Forward. Place 1 Petrification Counter on it and it gains "
            + "\"If a Petrification Counter is placed on this Forward, this Forward cannot attack or block.\" and "
            + "\"《5》: Remove all Petrification Counters from this Forward.\" (These effects do not end at the end of the turn.)";
    private static final String INNOCENCE_TEXT =
            "Brave[[br]]If you have a Card Name Innocence in your Break Zone, Innocence gains "
            + "\"《Fire》《Dull》: Choose 1 Forward. Deal it 10000 damage.\" and "
            + "\"《Ice》《Dull》: Your opponent discards 2 cards from their hand. You can only use this ability during your turn and only once per turn.\"";

    @Test
    void grantingCardsExposeNoOwnAbilitiesAndTheirEtbEffectsParse() {
        // Neither granting card should surface the quoted grant as its OWN action ability.
        assertTrue(CardData.parseActionAbilities(MACHINIST_TEXT).isEmpty(), "Machinist has no own action ability");
        assertTrue(CardData.parseActionAbilities(MEDUSA_TEXT).isEmpty(),    "Medusa has no own action ability");

        // The full ETB effect (grant text no longer truncated at the 《cost》: inside the quote) parses.
        String machEtb = CardData.parseAutoAbilities(MACHINIST_TEXT).get(0).effectText();
        assertEquals("ChooseForwardsGainAbilityEot", ActionResolver.matchedPatternName(machEtb, null));
        String medusaEtb = CardData.parseAutoAbilities(MEDUSA_TEXT).get(0).effectText();
        assertEquals("ChooseForwardPlacePetrification", ActionResolver.matchedPatternName(medusaEtb, null));

        // Innocence's Break-Zone-gated self-grant must remain two intact, gated action abilities.
        List<ActionAbility> inno = CardData.parseActionAbilities(INNOCENCE_TEXT);
        assertEquals(2, inno.size(), "Innocence keeps its two self-granted abilities");
        assertTrue(inno.stream().allMatch(a -> "Innocence".equalsIgnoreCase(a.ownBreakZoneNameRequired())),
                "both abilities stay gated on Innocence being in the Break Zone");
    }

    @Test
    void machinistGrantsEotAbilityToChosenForward() {
        MainWindow mw = new MainWindow();
        CardData machinist = makeForward("Machinist", "Fire", 2, 5000);
        CardData target    = makeForward("Target", "Ice", 3, 7000);
        mw.gameState.getIdentity().put(target, true);
        mw.placeCardInForwardZone(target);   // P1 idx 0

        GameContext ctx = mw.buildGameContext(true);
        ctx.preloadTargets(List.of(new ForwardTarget(true, 0, ForwardTarget.CardZone.FORWARD)));
        String etb = CardData.parseAutoAbilities(MACHINIST_TEXT).get(0).effectText();
        ActionResolver.parse(etb, machinist).accept(ctx);

        List<ActionAbility> granted = mw.p1TempGrantedAbilities.get(target);
        assertNotNull(granted, "the chosen Forward should have a granted ability");
        assertEquals(1, granted.size());
        assertEquals("Choose 1 Forward. Deal it 4000 damage.", granted.get(0).effectText());
        assertTrue(granted.get(0).requiresDull(), "the granted ability keeps its 《Dull》 cost");
    }

    @Test
    void medusaPetrifiesForwardAndTheFiveCostAbilityRemovesIt() {
        MainWindow mw = new MainWindow();
        CardData medusa = makeForward("Medusa", "Earth", 4, 8000);
        CardData target = makeForward("Victim", "Fire", 3, 7000);
        mw.gameState.getIdentity().put(target, true);
        mw.placeCardInForwardZone(target);   // P1 idx 0

        GameContext ctx = mw.buildGameContext(true);
        ctx.preloadTargets(List.of(new ForwardTarget(true, 0, ForwardTarget.CardZone.FORWARD)));
        String etb = CardData.parseAutoAbilities(MEDUSA_TEXT).get(0).effectText();
        ActionResolver.parse(etb, medusa).accept(ctx);

        assertEquals(1, mw.gameState.getCounters(target, "Petrification"), "target is petrified");
        assertTrue(mw.isFieldAbilityCannotAttackOrBlock(target, true),
                "a petrified Forward cannot attack or block");

        // The granted "《5》: Remove all Petrification Counters from this Forward." lifts it.
        ActionResolver.parse("Remove all Petrification Counters from this Forward.", target)
                .accept(mw.buildGameContext(true));
        assertEquals(0, mw.gameState.getCounters(target, "Petrification"), "counters removed");
        assertFalse(mw.isFieldAbilityCannotAttackOrBlock(target, true), "restriction lifted");
    }

    // =========================================================================================
    // Gippal (12-058C): "When a party you control attacks, all Forwards in that party gain +5000
    // power until the end of the turn."  The party-attack trigger must record the attacking party
    // and the followup must boost exactly those Forwards (not other Forwards on the field).
    // =========================================================================================

    private static final String GIPPAL_TEXT =
            "The Forwards forming a party you control gain Brave.[[br]]   "
            + "When a party you control attacks, all Forwards in that party gain +5000 power until the end of the turn.";

    private static CardData makeGippal(int power) {
        return new CardData(null, "Gippal", "Lightning", 5, power, "Forward", false, 0, false, false,
                Set.of(), 0, List.of(), null, List.of(),
                List.of(), CardData.parseAutoAbilities(GIPPAL_TEXT), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(),
                false, false, null, false, false, false, false, false, false,
                null, null, null, GIPPAL_TEXT);
    }

    @Test
    void gippalPartyAttackBoostsAllPartyForwardsBy5000() {
        MainWindow mw = new MainWindow();
        CardData gippal = makeGippal(9000);
        CardData ally   = makeForward("Ally", "Lightning", 3, 7000);
        CardData bench  = makeForward("Bench", "Lightning", 2, 5000);
        mw.placeCardInForwardZone(gippal); // P1 idx 0
        mw.placeCardInForwardZone(ally);   // P1 idx 1
        mw.placeCardInForwardZone(bench);  // P1 idx 2

        // Gippal + Ally form a party and attack; Bench stays home. P1 acted, so the CPU has
        // priority and Gippal's auto-ability resolves off the stack immediately.
        List<CardData> party = List.of(gippal, ally);
        mw.autoAbilityTriggers.triggerAutoAbilitiesForPartyAttack(true, party);

        // The attacking party is recorded, and every Forward in it gained +5000 power.
        assertEquals(party, mw.p1CurrentPartyAttackers);
        assertEquals(14000, mw.effectiveP1ForwardPower(0), "Gippal (in party) should be +5000");
        assertEquals(12000, mw.effectiveP1ForwardPower(1), "Ally (in party) should be +5000");
        assertEquals(5000,  mw.effectiveP1ForwardPower(2), "Bench (not in party) should be unchanged");
    }

    // =========================================================================================
    // Chocobo (9-050C): "When a Card Name Chocobo you control forms a party and attacks, all
    // Forwards in that party gain +1000 power until the end of the turn."  The "a Card Name X you
    // control" subject must resolve to a card-name party filter (partyCardName = "Chocobo") so the
    // followup fires only when a Chocobo is actually in the attacking party.
    // =========================================================================================

    private static final String CHOCOBO_TEXT =
            "When a Card Name Chocobo you control forms a party and attacks, all Forwards in that party gain +1000 power until the end of the turn.";

    private static CardData makeChocobo(int power) {
        return new CardData(null, "Chocobo", "Wind", 2, power, "Forward", false, 0, false, false,
                Set.of(), 0, List.of(), null, List.of(),
                List.of(), CardData.parseAutoAbilities(CHOCOBO_TEXT), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(),
                false, false, null, false, false, false, false, false, false,
                null, null, null, CHOCOBO_TEXT);
    }

    @Test
    void chocoboPartyAttackFiresOnlyWhenAChocoboIsInTheParty() {
        // Positive: Chocobo + ally attack together — the Card-Name filter matches, both get +1000.
        MainWindow mw = new MainWindow();
        CardData chocobo = makeChocobo(3000);
        CardData ally    = makeForward("Ally", "Wind", 3, 7000);
        mw.placeCardInForwardZone(chocobo); // P1 idx 0
        mw.placeCardInForwardZone(ally);    // P1 idx 1
        mw.autoAbilityTriggers.triggerAutoAbilitiesForPartyAttack(true, List.of(chocobo, ally));
        assertEquals(4000, mw.effectiveP1ForwardPower(0), "Chocobo (in party) should be +1000");
        assertEquals(8000, mw.effectiveP1ForwardPower(1), "Ally (in party) should be +1000");

        // Negative: two non-Chocobo Forwards attack while Chocobo sits out — filter fails, no boost.
        MainWindow mw2 = new MainWindow();
        CardData benched = makeChocobo(3000);
        CardData a1 = makeForward("A1", "Wind", 3, 7000);
        CardData a2 = makeForward("A2", "Wind", 3, 6000);
        mw2.placeCardInForwardZone(benched); // P1 idx 0 — owns the ability but does not attack
        mw2.placeCardInForwardZone(a1);      // P1 idx 1
        mw2.placeCardInForwardZone(a2);      // P1 idx 2
        mw2.autoAbilityTriggers.triggerAutoAbilitiesForPartyAttack(true, List.of(a1, a2));
        assertEquals(7000, mw2.effectiveP1ForwardPower(1), "A1 unchanged — no Chocobo in the party");
        assertEquals(6000, mw2.effectiveP1ForwardPower(2), "A2 unchanged — no Chocobo in the party");
    }

    // =========================================================================================
    // "Cannot be returned to its owner's hand by your opponent's Summons or abilities" family:
    //   • Krile (6-071H)     — action ability followup granting EOT return protection
    //   • Gilgamesh (1-207S) — named permanent field ability
    //   • Ritz (4-072H)      — blanket "Characters you control" field ability
    //   • Black Tortoise l'Cie Gilgamesh (10-069R) — compound dull/return/BZ protection clauses
    //   • Exodus (11-070R)   — EX Burst single-target buff upgrading to all Forwards at 5+ damage
    //   • Asura (23-039R)    — activate-all + return/power-decrease protection grants
    // =========================================================================================

    private static CardData makeForwardWithText(String name, String element, int cost, int power, String textEn) {
        return new CardData(null, name, element, cost, power, "Forward", false, 0, false, false,
                Set.of(), 0, List.of(), null, List.of(),
                CardData.parseActionAbilities(textEn), CardData.parseAutoAbilities(textEn),
                CardData.parseFieldAbilities(textEn, "Forward"),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                false, false, null, false, false, false, false, false, false,
                null, null, null, textEn);
    }

    private static final String KRILE_TEXT =
            "《Earth》《1》《Dull》: Choose 1 Forward you control. During this turn, it cannot be "
            + "returned to its owner's hand by your opponent's Summons or abilities.";

    @Test
    void krileFollowupGrantsReturnToHandProtectionUntilEot() {
        List<ActionAbility> abilities = CardData.parseActionAbilities(KRILE_TEXT);
        assertEquals(1, abilities.size());
        ActionAbility ability = abilities.get(0);
        assertTrue(ability.requiresDull());
        assertEquals(List.of("Earth", ""), ability.cpCost());

        Consumer<GameContext> fn = ActionResolver.parse(ability.effectText(), null);
        assertNotNull(fn, "Krile's followup should parse");

        GameContext ctx = mock(GameContext.class);
        when(ctx.consumePreloadedTargets()).thenReturn(null);
        ForwardTarget t = new ForwardTarget(true, 0, ForwardTarget.CardZone.FORWARD);
        when(ctx.selectCharacters(
                anyInt(), anyBoolean(), anyBoolean(), anyBoolean(),
                any(), any(), anyInt(), any(), anyInt(), any(),
                anyBoolean(), anyBoolean(), anyBoolean(),
                any(), any(), any(), any(), anyBoolean(), any(), anyBoolean()
        )).thenReturn(List.of(t));
        fn.accept(ctx);
        verify(ctx).boostTarget(t, 0,
                java.util.EnumSet.of(CardData.Trait.CANNOT_BE_RETURNED_TO_HAND_BY_OPP));
    }

    private static final String GILGAMESH_TEXT =
            "Gilgamesh cannot be returned to its owner's hand by opponent's Summons or abilities.[[br]] "
            + "《Lightning》《Lightning》: Gilgamesh gains +1000 power until the end of the turn.";

    @Test
    void gilgameshNamedFieldAbilityBlocksOnlyOpponentReturnToHand() {
        CardData gilgamesh = makeForwardWithText("Gilgamesh", "Lightning", 4, 8000, GILGAMESH_TEXT);
        assertTrue(ActionResolver.hasCannotBeReturnedToHandByOppFieldAbility(gilgamesh));

        MainWindow mw = new MainWindow();
        mw.gameState.getIdentity().put(gilgamesh, true);
        mw.placeCardInForwardZone(gilgamesh);

        // Opponent (P2) attempts the return — must be prevented.
        mw.buildGameContext(false).returnP1ForwardToHand(0);
        assertEquals(1, mw.p1ForwardCards.size(), "Gilgamesh must still be on the field");
        assertTrue(mw.gameState.getP1Hand().isEmpty());

        // The controller's own effect may still return it.
        mw.buildGameContext(true).returnP1ForwardToHand(0);
        assertTrue(mw.p1ForwardCards.isEmpty(), "own effects may return Gilgamesh to hand");
        assertEquals(1, mw.gameState.getP1Hand().size());
    }

    private static final String RITZ_TEXT =
            "Characters you control cannot be returned to their owner's hand by your opponent's "
            + "Summons or abilities. [[br]] If you control Card Name Shara, Ritz gains +2000 power.";

    @Test
    void ritzBlanketFieldAbilityProtectsAllOwnCharacters() {
        CardData ritz = makeForwardWithText("Ritz", "Wind", 4, 7000, RITZ_TEXT);
        assertTrue(ActionResolver.hasCharactersCannotBeReturnedFieldAbility(ritz));

        MainWindow mw = new MainWindow();
        CardData ally = makeForward("Ally", "Wind", 2, 5000);
        mw.gameState.getIdentity().put(ritz, true);
        mw.gameState.getIdentity().put(ally, true);
        mw.placeCardInForwardZone(ritz);   // P1 idx 0
        mw.placeCardInForwardZone(ally);   // P1 idx 1

        // Opponent cannot return the ally while Ritz is on the field.
        mw.buildGameContext(false).returnP1ForwardToHand(1);
        assertEquals(2, mw.p1ForwardCards.size(), "ally must still be on the field");

        // P2's own characters are unaffected by P1's Ritz.
        CardData oppFwd = makeForward("Opp Forward", "Fire", 2, 5000);
        mw.gameState.getIdentity().put(oppFwd, false);
        mw.placeP2CardInForwardZone(oppFwd);
        mw.buildGameContext(true).returnP2ForwardToHand(0);
        assertTrue(mw.p2ForwardCards.isEmpty(), "P1 may still return P2's characters");
    }

    private static final String BLACK_TORTOISE_TEXT =
            "Brave[[br]]   Black Tortoise l'Cie Gilgamesh cannot become dull by your opponent's "
            + "Summons or abilities, cannot be returned to its owner's hand by your opponent's Summons "
            + "or abilities, and cannot be put into the Break Zone by your opponent's Summons or "
            + "abilities (If Black Tortoise l'Cie Gilgamesh is broken, put it into the Break Zone).";

    @Test
    void blackTortoiseCompoundClausesSplitIntoIndividualFieldAbilities() {
        List<FieldAbility> fas = CardData.parseFieldAbilities(BLACK_TORTOISE_TEXT, "Forward");
        assertEquals(3, fas.size(), "the compound sentence must split into three individual clauses: " + fas);

        CardData tortoise = makeForwardWithText("Black Tortoise l'Cie Gilgamesh", "Earth", 5, 9000, BLACK_TORTOISE_TEXT);
        assertTrue(ActionResolver.hasCannotBeDulledByOppFieldAbility(tortoise));
        assertTrue(ActionResolver.hasCannotBeReturnedToHandByOppFieldAbility(tortoise));
        assertTrue(ActionResolver.hasCannotBePutIntoBzByOppFieldAbility(tortoise));
    }

    @Test
    void blackTortoiseProtectionsBlockOpponentDullReturnAndBreak() {
        CardData tortoise = makeForwardWithText("Black Tortoise l'Cie Gilgamesh", "Earth", 5, 9000, BLACK_TORTOISE_TEXT);
        MainWindow mw = new MainWindow();
        mw.gameState.getIdentity().put(tortoise, true);
        mw.placeCardInForwardZone(tortoise);

        GameContext opp = mw.buildGameContext(false);
        opp.dullP1Forward(0);
        assertEquals(CardState.ACTIVE, mw.p1ForwardStates.get(0), "opponent's effects cannot dull it");
        opp.returnP1ForwardToHand(0);
        assertEquals(1, mw.p1ForwardCards.size(), "opponent's effects cannot return it to hand");
        opp.breakP1Forward(0);
        assertEquals(1, mw.p1ForwardCards.size(), "opponent's effects cannot put it into the Break Zone");

        // The controller's own effects are unrestricted.
        GameContext own = mw.buildGameContext(true);
        own.dullP1Forward(0);
        assertEquals(CardState.DULL, mw.p1ForwardStates.get(0), "own effects may still dull it");
        own.breakP1Forward(0);
        assertTrue(mw.p1ForwardCards.isEmpty(), "own effects may still break it");
    }

    private static final String EXODUS_TEXT =
            "[[ex]]EX BURST[[/]] Choose 1 Forward you control. Until the end of the turn, it gains "
            + "+3000 power, Brave and \"This Forward cannot become dull by your opponent's Summons or "
            + "abilities.\" and \"This Forward cannot be returned to its owner's hand by your opponent's "
            + "Summons or abilities.\" If your opponent has received 5 points of damage or more, all the "
            + "Forwards you control gain all previous effects instead.";

    @Test
    void exodusBuffsSingleForwardBelowDamageThreshold() {
        Consumer<GameContext> fn = ActionResolver.parse(EXODUS_TEXT, null);
        assertNotNull(fn, "Exodus's EX Burst should parse");

        GameContext ctx = mock(GameContext.class);
        when(ctx.consumePreloadedTargets()).thenReturn(null);
        when(ctx.opponentDamageCount()).thenReturn(4);
        ForwardTarget t = new ForwardTarget(true, 0, ForwardTarget.CardZone.FORWARD);
        when(ctx.selectCharacters(
                anyInt(), anyBoolean(), anyBoolean(), anyBoolean(),
                any(), any(), anyInt(), any(), anyInt(), any(),
                anyBoolean(), anyBoolean(), anyBoolean(),
                any(), any(), any(), any(), anyBoolean(), any(), anyBoolean()
        )).thenReturn(List.of(t));
        fn.accept(ctx);

        verify(ctx).boostTarget(t, 3000, java.util.EnumSet.of(
                CardData.Trait.BRAVE,
                CardData.Trait.CANNOT_BE_DULLED_BY_OPP,
                CardData.Trait.CANNOT_BE_RETURNED_TO_HAND_BY_OPP));
        verify(ctx, never()).applyMassFieldPowerBoost(anyInt(), anyBoolean(), anyBoolean(),
                anyBoolean(), anyBoolean(), any(), anyInt(), any(), any(), any());
    }

    @Test
    void exodusBuffsAllOwnForwardsAtDamageThreshold() {
        Consumer<GameContext> fn = ActionResolver.parse(EXODUS_TEXT, null);
        assertNotNull(fn);

        GameContext ctx = mock(GameContext.class);
        when(ctx.opponentDamageCount()).thenReturn(5);
        fn.accept(ctx);

        verify(ctx).applyMassFieldPowerBoost(3000, true, false, false, true, null, -1, null, null, null);
        verify(ctx).applyMassFieldKeywordGrant(java.util.EnumSet.of(
                CardData.Trait.BRAVE,
                CardData.Trait.CANNOT_BE_DULLED_BY_OPP,
                CardData.Trait.CANNOT_BE_RETURNED_TO_HAND_BY_OPP),
                true, false, false, true, null, -1, null, null);
        verify(ctx, never()).boostTarget(any(), anyInt(), any());
    }

    private static final String ASURA_TEXT =
            "Activate all the Forwards you control. Until the end of the turn, all the Forwards you "
            + "control gain \"This Forward cannot be returned to its owner's hand by your opponent's "
            + "Summons or abilities.\" and \"The power of this Forward cannot be decreased by your "
            + "opponent's Summons or abilities.\"";

    @Test
    void asuraActivatesAllAndGrantsReturnAndPowerDecreaseProtection() {
        Consumer<GameContext> fn = ActionResolver.parse(ASURA_TEXT, null);
        assertNotNull(fn, "Asura's effect should parse");

        GameContext ctx = mock(GameContext.class);
        fn.accept(ctx);

        verify(ctx).applyMassFieldEffect(GameContext.MassAction.ACTIVATE, true, false, false, false, true,
                null, -1, null, -1, null, null);
        verify(ctx).applyMassFieldKeywordGrant(java.util.EnumSet.of(
                CardData.Trait.CANNOT_BE_RETURNED_TO_HAND_BY_OPP,
                CardData.Trait.POWER_CANNOT_BE_DECREASED_BY_OPP),
                true, false, false, true, null, -1, null, null);
    }

    @Test
    void grantedTraitsBlockOpponentReturnAndPowerDecreaseUntilEot() {
        MainWindow mw = new MainWindow();
        CardData fwd = makeForward("Warrior of Light", "Light", 4, 7000);
        mw.gameState.getIdentity().put(fwd, true);
        mw.placeCardInForwardZone(fwd);
        mw.p1ForwardTempTraits.get(0).add(CardData.Trait.CANNOT_BE_RETURNED_TO_HAND_BY_OPP);
        mw.p1ForwardTempTraits.get(0).add(CardData.Trait.POWER_CANNOT_BE_DECREASED_BY_OPP);

        GameContext opp = mw.buildGameContext(false);
        opp.returnP1ForwardToHand(0);
        assertEquals(1, mw.p1ForwardCards.size(), "granted trait must block the opponent's return");

        ForwardTarget t = new ForwardTarget(true, 0, ForwardTarget.CardZone.FORWARD);
        opp.boostTarget(t, -2000, java.util.EnumSet.noneOf(CardData.Trait.class));
        assertEquals(0, mw.p1ForwardPowerBoost.get(0),
                "granted trait must block the opponent's power decrease");

        // The controller's own debuff still applies (protection is opponent-only).
        mw.buildGameContext(true).boostTarget(t, -2000, java.util.EnumSet.noneOf(CardData.Trait.class));
        assertEquals(-2000, mw.p1ForwardPowerBoost.get(0));
    }

    // =========================================================================================
    // Cecil: "When Cecil enters the field, if you have a Card Name Cecil with Job Dark Knight
    // in your Break Zone, draw 1 card." — the "if you have a Card Name X with Job Y in your
    // Break Zone" prefix becomes a bzConditionCard + bzConditionJob firing gate.
    // =========================================================================================

    private static final String CECIL_BZ_COND_TEXT =
            "When Cecil enters the field, if you have a Card Name Cecil with Job Dark Knight "
            + "in your Break Zone, draw 1 card.";

    @Test
    void cecilBzNameAndJobConditionParsesAsFiringGate() {
        List<AutoAbility> autos = CardData.parseAutoAbilities(CECIL_BZ_COND_TEXT);
        assertEquals(1, autos.size());
        AutoAbility fa = autos.get(0);
        assertEquals("enters the field", fa.trigger());
        assertEquals("Cecil", fa.triggerCard());
        assertEquals("Cecil", fa.bzConditionCard());
        assertEquals("Dark Knight", fa.bzConditionJob());
        assertEquals("draw 1 card.", fa.effectText());
        assertNotNull(ActionResolver.parse(fa.effectText(), null), "stripped effect should parse");
    }

    @Test
    void bzNameConditionWithoutJobLeavesJobEmpty() {
        List<AutoAbility> autos = CardData.parseAutoAbilities(
                "When Cecil enters the field, if you have a Card Name Golbez in your Break Zone, draw 1 card.");
        assertEquals(1, autos.size());
        AutoAbility fa = autos.get(0);
        assertEquals("Golbez", fa.bzConditionCard());
        assertEquals("", fa.bzConditionJob());
        assertEquals("draw 1 card.", fa.effectText());
    }

    // =========================================================================================
    // Yugiri: "If your opponent doesn't control Forwards, Yugiri gains Haste." — a conditional
    // field boost gated on the opponent controlling exactly zero Forwards.
    // =========================================================================================

    private static final String YUGIRI_TEXT =
            "If your opponent doesn't control Forwards, Yugiri gains Haste.";

    @Test
    void yugiriNoOpponentForwardsHasteParsesAsIfControlBoost() {
        List<IfControlBoost> boosts = CardData.parseIfControlBoosts(YUGIRI_TEXT, "Forward");
        assertEquals(1, boosts.size());
        IfControlBoost icb = boosts.get(0);
        assertEquals("Yugiri", icb.targetCardName());
        assertEquals(Set.of(CardData.Trait.HASTE), icb.grantedTraits());
        assertEquals(0, icb.powerBonus());
        assertEquals(1, icb.conditions().size());
        ControlCondition cond = icb.conditions().get(0);
        assertTrue(cond.opponentControls(), "condition must check the opponent's field");
        assertTrue(cond.exactCount(), "condition must be an exact-count check");
        assertEquals(0, cond.minCount(), "condition must require exactly zero Forwards");
        assertEquals("Forward", cond.cardType());
    }

    @Test
    void yugiriHasteConditionTracksOpponentForwardCount() {
        IfControlBoost icb = CardData.parseIfControlBoosts(YUGIRI_TEXT, "Forward").get(0);
        MainWindow mw = new MainWindow();
        assertTrue(mw.icbConditionsMet(icb, true), "no opponent Forwards — condition met");
        mw.placeP2CardInForwardZone(makeForward("Amon", "Lightning", 3, 7000));
        assertFalse(mw.icbConditionsMet(icb, true), "opponent Forward present — condition unmet");
    }

    // =========================================================================================
    // Other "If your opponent doesn't control …" instances. Famed Mimic Gogo's self-break and
    // King/Queen of Eblan's attack restriction ride pre-existing ActionResolver machinery;
    // Kelger's cost-qualified variant extends the IfControlBoost condition with a minCost filter.
    // =========================================================================================

    /** Builds a Forward whose fieldAbilities are parsed from {@code text}. */
    private static CardData makeFieldAbilityForward(String name, String text) {
        return new CardData(null, name, "Fire", 3, 7000, "Forward", false, 0, false, false,
                Set.of(), 0, List.of(), null, List.of(),
                List.of(), List.of(), CardData.parseFieldAbilities(text, "Forward"),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                false, false, null, false, false, false, false, false, false,
                null, null, null, text);
    }

    @Test
    void famedMimicGogoSelfBreakParsesWithSourceCard() {
        CardData gogo = makeForward("Famed Mimic Gogo", "Fire", 5, 9000);
        assertNotNull(ActionResolver.parse(
                "If your opponent doesn't control Forwards, put Famed Mimic Gogo into the Break Zone.", gogo),
                "Gogo's conditional self-break should be recognized");
        // Verbiage variant: "any Forwards" must parse the same way
        assertNotNull(ActionResolver.parse(
                "If your opponent doesn't control any Forwards, put Famed Mimic Gogo into the Break Zone.", gogo),
                "the 'any Forwards' wording should also be recognized");
    }

    @Test
    void kingOfEblanCannotAttackGatesOnOpponentForwards() {
        String text = "If your opponent doesn't control any Forwards, King of Eblan cannot attack.";
        CardData king = makeFieldAbilityForward("King of Eblan", text);
        assertNotNull(ActionResolver.parse(text, king), "restriction sentence should be recognized");

        MainWindow mw = new MainWindow();
        assertTrue(mw.isFieldAbilityCannotAttack(king, true), "no opponent Forwards — cannot attack");
        mw.placeP2CardInForwardZone(makeForward("Amon", "Lightning", 3, 7000));
        assertFalse(mw.isFieldAbilityCannotAttack(king, true), "opponent Forward present — attack allowed");

        // Verbiage variant: without "any" must gate the same way
        CardData queen = makeFieldAbilityForward("Queen of Eblan",
                "If your opponent doesn't control Forwards, Queen of Eblan cannot attack.");
        MainWindow mw2 = new MainWindow();
        assertTrue(mw2.isFieldAbilityCannotAttack(queen, true),
                "the wording without 'any' should also be recognized");
    }

    private static final String KELGER_TEXT =
            "If your opponent doesn't control a Forward of cost 5 or more, "
            + "Kelger gains Haste, First Strike, and Brave.";

    @Test
    void kelgerCostQualifiedBoostParsesWithMinCostCondition() {
        List<IfControlBoost> boosts = CardData.parseIfControlBoosts(KELGER_TEXT, "Forward");
        assertEquals(1, boosts.size());
        IfControlBoost icb = boosts.get(0);
        assertEquals("Kelger", icb.targetCardName());
        assertEquals(Set.of(CardData.Trait.HASTE, CardData.Trait.FIRST_STRIKE, CardData.Trait.BRAVE),
                icb.grantedTraits());
        assertEquals(1, icb.conditions().size());
        ControlCondition cond = icb.conditions().get(0);
        assertTrue(cond.opponentControls());
        assertTrue(cond.exactCount());
        assertEquals(0, cond.minCount());
        assertEquals(5, cond.minCost(), "cost qualifier must carry into the condition");
        assertEquals("Forward", cond.cardType());
    }

    @Test
    void kelgerConditionIgnoresCheapOpponentForwards() {
        IfControlBoost icb = CardData.parseIfControlBoosts(KELGER_TEXT, "Forward").get(0);
        MainWindow mw = new MainWindow();
        assertTrue(mw.icbConditionsMet(icb, true), "empty opponent field — condition met");
        mw.placeP2CardInForwardZone(makeForward("Cheap", "Fire", 3, 5000));
        assertTrue(mw.icbConditionsMet(icb, true), "cost-3 Forward doesn't break the condition");
        mw.placeP2CardInForwardZone(makeForward("Big", "Fire", 5, 9000));
        assertFalse(mw.icbConditionsMet(icb, true), "cost-5 Forward breaks the condition");
    }
}
