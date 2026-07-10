package shufflingway;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

/**
 * Behavioral tests for the CP_FIXED "extra cost" pattern — "If you cast [Name], you may pay
 * 《Element》《N》 as an extra cost." plus the paired "If you paid the extra cost, [effect]"
 * auto-ability clause — exercised against real card texts (Samurai, Bard, Summoner).
 */
public class ExtraCostAbilityTest {

    private static CardData makeCard(String name, String element, String textEn) {
        return new CardData(null, name, element, 1, 4000, "Forward", false, 0, false, false,
                Set.of(), 0, List.of(), null, List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                false, false, null, false, false, false, false, false, false,
                null, null, null, textEn);
    }

    // --- Parsing: CardData.extraCost() ---

    @Test
    void samuraiParsesAsWindPlusTwoGenericFixedCp() {
        CardData samurai = makeCard("Samurai", "Fire",
                "If you cast Samurai, you may pay 《Wind》《2》 as an extra cost.[[br]]"
                + "When Samurai enters the field, choose 1 Forward of cost 6 or more. If you paid the extra cost, break it.");
        ExtraCost ec = samurai.extraCost();
        assertNotNull(ec);
        assertEquals(ExtraCost.Type.CP_FIXED, ec.type());
        assertEquals(List.of("Wind", "", ""), ec.cpElements());
    }

    @Test
    void bardParsesAsEarthPlusThreeGenericFixedCp() {
        CardData bard = makeCard("Bard", "Ice",
                "If you cast Bard, you may pay 《Earth》《3》 as an extra cost.[[br]]"
                + "When Bard enters the field, choose 1 dull Forward. If you paid the extra cost, break it.");
        ExtraCost ec = bard.extraCost();
        assertNotNull(ec);
        assertEquals(ExtraCost.Type.CP_FIXED, ec.type());
        assertEquals(List.of("Earth", "", "", ""), ec.cpElements());
    }

    // --- Resolution: paid vs not-paid changes the effect ---

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
}
