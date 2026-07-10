package shufflingway;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

/**
 * Behavioral tests for "Remove all [Name] Counters from [CardName]." and the
 * "Each player can use this ability." flag, exercised against Llednar's real card text:
 * "Discard 2 cards: Remove all Fortune Counters from Llednar. Each player can use this ability."
 */
public class RemoveAllCountersActionTest {

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
    void removeAllCountersClearsExactCurrentCount() {
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
    void removeAllCountersIsNoOpWhenNonePresent() {
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
    void removeAllCountersOnlyAppliesToNamedTarget() {
        // "Llednar" refers to itself; a differently-named source must not match.
        CardData source = mock(CardData.class);
        when(source.name()).thenReturn("Someone Else");

        Consumer<GameContext> fn = ActionResolver.parse(
                "Remove all Fortune Counters from Llednar. Each player can use this ability.", source);
        assertNull(fn);
    }
}
