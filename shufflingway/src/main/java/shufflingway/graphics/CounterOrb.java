package shufflingway.graphics;

/**
 * One orb of a card's counter die: how many counters it stands for — those of one name that one
 * player placed — and that player's counter color, as "#rrggbb".
 */
public record CounterOrb(int count, String hex) {}
