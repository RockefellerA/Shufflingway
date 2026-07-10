package shufflingway;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

/**
 * Behavioral tests for the "Divide N damage [equally] among ..." action-ability pattern,
 * exercised against real card texts pulled from shufflingway.db (Cloud, Vivi, Zell, Bahamut,
 * Eden, Strago). These assert on the actual damage amounts/targets dealt via a mocked
 * {@link GameContext}, not just that the text parses.
 */
public class DivideDamageActionTest {

    private static GameContext mockContext(boolean isP1) {
        GameContext ctx = mock(GameContext.class);
        when(ctx.isP1()).thenReturn(isP1);
        // Mockito's default answer returns an empty List (not null) for collection-typed methods;
        // selectTargets() treats a non-null return from consumePreloadedTargets() as "already
        // chosen" and skips selectCharacters() entirely, so this must be explicitly null here.
        when(ctx.consumePreloadedTargets()).thenReturn(null);
        return ctx;
    }

    private static void stubSelectCharacters(GameContext ctx, List<ForwardTarget> result) {
        when(ctx.selectCharacters(
                anyInt(), anyBoolean(), anyBoolean(), anyBoolean(),
                any(), any(), anyInt(), any(), anyInt(), any(),
                anyBoolean(), anyBoolean(), anyBoolean(),
                any(), any(), any(), any(), anyBoolean(), any(), anyBoolean()
        )).thenReturn(result);
    }

    private static Consumer<GameContext> parse(String effectText) {
        Consumer<GameContext> fn = ActionResolver.parse(effectText, null);
        assertNotNull(fn, "Expected \"" + effectText + "\" to parse");
        return fn;
    }

    // --- Cloud: "Choose up to 2 Forwards. Divide 10000 damage among them equally." ---

    @Test
    void cloudSplitsEquallyAcrossTwoTargets() {
        GameContext ctx = mockContext(true);
        ForwardTarget t0 = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);
        ForwardTarget t1 = new ForwardTarget(false, 1, ForwardTarget.CardZone.FORWARD);
        stubSelectCharacters(ctx, List.of(t0, t1));

        parse("Choose up to 2 Forwards. Divide 10000 damage among them equally.").accept(ctx);

        verify(ctx).damageTarget(t0, 5000);
        verify(ctx).damageTarget(t1, 5000);
        verify(ctx, never()).divideDamageAmount(anyInt(), any(), any());
    }

    @Test
    void cloudDealsFullAmountToSingleTarget() {
        GameContext ctx = mockContext(true);
        ForwardTarget t0 = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);
        stubSelectCharacters(ctx, List.of(t0));

        parse("Choose up to 2 Forwards. Divide 10000 damage among them equally.").accept(ctx);

        verify(ctx).damageTarget(t0, 10000);
    }

    // --- Strago: "Divide 12000 damage equally among all the Forwards opponent controls
    //             (round up to the nearest 1000)." — no Choose clause, blanket target. ---

    @Test
    void stragoRoundsUpPerTargetWhenNotEvenlyDivisible() {
        GameContext ctx = mockContext(true);
        when(ctx.p2ForwardCount()).thenReturn(5);
        when(ctx.p1ForwardCount()).thenReturn(0);

        parse("Divide 12000 damage equally among all the Forwards opponent controls (round up to the nearest 1000).")
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
        GameContext ctx = mockContext(true);
        ForwardTarget t0 = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);
        stubSelectCharacters(ctx, List.of(t0));
        when(ctx.controlConditionMetExcluding(any(), eq("Vivi"))).thenReturn(true);

        parse("Choose any number of Forwards opponent controls. Divide 7000 damage among them as you like. "
                + "If you control a Category IX Forward other than Vivi, divide 10000 damage among them instead. "
                + "(Units must be 1000.)").accept(ctx);

        verify(ctx).controlConditionMetExcluding(any(), eq("Vivi"));
        verify(ctx).damageTarget(t0, 10000);
    }

    @Test
    void viviDealsBaseDamageWhenNoOtherCategoryForwardPresent() {
        GameContext ctx = mockContext(true);
        ForwardTarget t0 = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);
        stubSelectCharacters(ctx, List.of(t0));
        when(ctx.controlConditionMetExcluding(any(), eq("Vivi"))).thenReturn(false);

        parse("Choose any number of Forwards opponent controls. Divide 7000 damage among them as you like. "
                + "If you control a Category IX Forward other than Vivi, divide 10000 damage among them instead. "
                + "(Units must be 1000.)").accept(ctx);

        verify(ctx).damageTarget(t0, 7000);
    }

    // --- Zell: "...Divide 5000 damage among them as you like. If you control 4 or more
    //           Category VIII Characters, divide 9000 damage among them as you like instead..." ---

    @Test
    void zellDealsBoostedDamageWhenCountThresholdMet() {
        GameContext ctx = mockContext(true);
        ForwardTarget t0 = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);
        stubSelectCharacters(ctx, List.of(t0));
        when(ctx.controlConditionMet(any())).thenReturn(true);

        parse("Choose any number of Forwards. Divide 5000 damage among them as you like. "
                + "If you control 4 or more Category VIII Characters, divide 9000 damage among them as you like instead. "
                + "(Units must be 1000.)").accept(ctx);

        // No "other than" clause in Zell's condition — must use the non-excluding check.
        verify(ctx).controlConditionMet(any());
        verify(ctx, never()).controlConditionMetExcluding(any(), any());
        verify(ctx).damageTarget(t0, 9000);
    }

    @Test
    void zellDealsBaseDamageWhenCountThresholdNotMet() {
        GameContext ctx = mockContext(true);
        ForwardTarget t0 = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);
        stubSelectCharacters(ctx, List.of(t0));
        when(ctx.controlConditionMet(any())).thenReturn(false);

        parse("Choose any number of Forwards. Divide 5000 damage among them as you like. "
                + "If you control 4 or more Category VIII Characters, divide 9000 damage among them as you like instead. "
                + "(Units must be 1000.)").accept(ctx);

        verify(ctx).damageTarget(t0, 5000);
    }

    // --- Bahamut: "...Divide 10000 damage among them as you like. If you have received 5 points
    //              of damage or more, divide 15000 damage among those instead..." ---

    @Test
    void bahamutDealsBoostedDamageWhenSelfDamageThresholdMet() {
        GameContext ctx = mockContext(true);
        ForwardTarget t0 = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);
        stubSelectCharacters(ctx, List.of(t0));
        when(ctx.selfDamageCount()).thenReturn(5);

        parse("Choose up to 2 Forwards. Divide 10000 damage among them as you like. "
                + "If you have received 5 points of damage or more, divide 15000 damage among those instead. "
                + "(Units must be 1000.)").accept(ctx);

        verify(ctx).damageTarget(t0, 15000);
    }

    @Test
    void bahamutDealsBaseDamageWhenSelfDamageThresholdNotMet() {
        GameContext ctx = mockContext(true);
        ForwardTarget t0 = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);
        stubSelectCharacters(ctx, List.of(t0));
        when(ctx.selfDamageCount()).thenReturn(2);

        parse("Choose up to 2 Forwards. Divide 10000 damage among them as you like. "
                + "If you have received 5 points of damage or more, divide 15000 damage among those instead. "
                + "(Units must be 1000.)").accept(ctx);

        verify(ctx).damageTarget(t0, 10000);
    }

    // --- Eden: "...Divide 30000 damage among them as you like. (Units must be 1000.)
    //           This damage cannot be reduced." ---

    @Test
    void edenUsesUnreducedDamageWhenCannotBeReduced() {
        GameContext ctx = mockContext(true);
        ForwardTarget t0 = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);
        stubSelectCharacters(ctx, List.of(t0));

        parse("Choose up to 2 Forwards. Divide 30000 damage among them as you like. (Units must be 1000.) "
                + "This damage cannot be reduced.").accept(ctx);

        verify(ctx).damageTargetUnreduced(t0, 30000);
        verify(ctx, never()).damageTarget(any(), anyInt());
    }

    // --- Barret: "Choose up to 2 Forwards. Divide 10000 damage among them as you like.
    //             (Units must be 1000.)" — baseline multi-target "as you like" dialog path. ---

    @Test
    void barretInvokesAllocationDialogForMultipleTargets() {
        GameContext ctx = mockContext(true);
        ForwardTarget t0 = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);
        ForwardTarget t1 = new ForwardTarget(false, 1, ForwardTarget.CardZone.FORWARD);
        stubSelectCharacters(ctx, List.of(t0, t1));
        CardData c0 = mock(CardData.class);
        CardData c1 = mock(CardData.class);
        when(ctx.p2Forward(0)).thenReturn(c0);
        when(ctx.p2Forward(1)).thenReturn(c1);
        when(ctx.divideDamageAmount(eq(10000), any(), eq(List.of(c0, c1))))
                .thenReturn(List.of(4000, 6000));

        parse("Choose up to 2 Forwards. Divide 10000 damage among them as you like. (Units must be 1000.)")
                .accept(ctx);

        verify(ctx).damageTarget(t0, 4000);
        verify(ctx).damageTarget(t1, 6000);
    }

    @Test
    void barretSkipsZeroAllocationTargets() {
        GameContext ctx = mockContext(true);
        ForwardTarget t0 = new ForwardTarget(false, 0, ForwardTarget.CardZone.FORWARD);
        ForwardTarget t1 = new ForwardTarget(false, 1, ForwardTarget.CardZone.FORWARD);
        stubSelectCharacters(ctx, List.of(t0, t1));
        when(ctx.p2Forward(anyInt())).thenReturn(mock(CardData.class));
        when(ctx.divideDamageAmount(eq(10000), any(), any()))
                .thenReturn(List.of(10000, 0));

        parse("Choose up to 2 Forwards. Divide 10000 damage among them as you like. (Units must be 1000.)")
                .accept(ctx);

        verify(ctx).damageTarget(t0, 10000);
        verify(ctx, never()).damageTarget(eq(t1), anyInt());
    }
}
