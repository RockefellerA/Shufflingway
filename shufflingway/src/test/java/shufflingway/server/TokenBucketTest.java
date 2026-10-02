package shufflingway.server;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TokenBucketTest {

    private static final long SECOND = 1_000_000_000L;

    @Test
    void startsFullAndAllowsABurstUpToCapacity() {
        TokenBucket b = new TokenBucket(3, 1, 0);
        assertTrue(b.tryTake(1, 0));
        assertTrue(b.tryTake(1, 0));
        assertTrue(b.tryTake(1, 0));
        assertFalse(b.tryTake(1, 0), "the burst is spent");
    }

    @Test
    void refillsAtTheRateOverTime() {
        TokenBucket b = new TokenBucket(2, 4, 0);
        assertTrue(b.tryTake(2, 0));
        assertFalse(b.tryTake(1, SECOND / 8), "half a token after an eighth of a second");
        assertTrue(b.tryTake(1, SECOND / 4), "one token after a quarter");
    }

    @Test
    void neverHoldsMoreThanItsCapacity() {
        TokenBucket b = new TokenBucket(5, 100, 0);
        assertFalse(b.tryTake(6, 60 * SECOND), "a long idle spell does not bank more than the cap");
        assertTrue(b.tryTake(5, 60 * SECOND));
    }

    @Test
    void aRefusedTakeCostsNothing() {
        TokenBucket b = new TokenBucket(10, 1, 0);
        assertFalse(b.tryTake(11, 0));
        assertTrue(b.tryTake(10, 0), "the refused request left the bucket as it was");
    }
}
