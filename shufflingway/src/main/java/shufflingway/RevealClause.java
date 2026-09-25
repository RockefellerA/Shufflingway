package shufflingway;

import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * One conditional branch in a deck-reveal ability.
 *
 * <p>Exactly one of {@link #cardOp} or {@link #effect} is non-null:
 * <ul>
 *   <li>{@code cardOp} — the revealed card is placed according to the op code
 *       ("playOntoField", "mayPlayOntoField", "playOntoFieldDull", "addToHand", "putToBreakZone",
 *       "castSummonFree"). The two optional ops offer the controller a Decline.</li>
 *   <li>{@code effect} — the revealed card is returned to the top of the deck,
 *       then this standalone effect fires.</li>
 * </ul>
 *
 * @param condition predicate evaluated against the revealed card
 * @param cardOp    op code for actions that directly place the revealed card; {@code null} when {@code effect} is used
 * @param effect    standalone game effect; {@code null} when {@code cardOp} is used
 */
public record RevealClause(
    Predicate<CardData> condition,
    String cardOp,
    Consumer<GameContext> effect
) {}
