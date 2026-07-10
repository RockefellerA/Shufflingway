package shufflingway;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Regression test for a bug where activating a "Choose any number of [targets]..." action
 * ability (e.g. Yuffie's "Doom of the Living": "Choose any number of Forwards. Divide 24000
 * damage among them as you like.") silently failed — no target prompt, no effect, nothing on
 * the stack — because {@link ActionResolver#preSelectTargets} had its own separate copy of the
 * "Choose N" count extraction that didn't know about the "any number of" branch and threw a
 * {@code NumberFormatException} parsing a null count group, which aborted activation before the
 * ability ever reached the stack.
 */
public class PreSelectTargetsAnyNumberTest {

    private static CardData makeCard(String name) {
        return new CardData(null, name, "Wind", 3, 6000, "Forward", false, 0, false, false,
                Set.of(), 0, List.of(), null, List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                false, false, null, false, false, false, false, false, false,
                null, null, null, "");
    }

    @Test
    void anyNumberChooseDoesNotThrowAndPromptsWithUnboundedMax() {
        CardData yuffie = makeCard("Yuffie");
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
        CardData card = makeCard("Barret");
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
}
