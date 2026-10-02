package shufflingway.server;

/**
 * A token bucket: holds up to {@code capacity} tokens, starts full, and refills continuously at
 * {@code perSecond}. Taking tokens succeeds while enough are left, so a client may burst up to the
 * capacity but cannot sustain more than the refill rate.
 *
 * <p>Time is passed in rather than read, which keeps the arithmetic testable without sleeping.
 * Not thread-safe; each session owns its own buckets and reads on one thread.
 */
final class TokenBucket {

    private final double capacity;
    private final double perNano;
    private double tokens;
    private long lastNanos;

    TokenBucket(double capacity, double perSecond, long nowNanos) {
        this.capacity = capacity;
        this.perNano = perSecond / 1_000_000_000.0;
        this.tokens = capacity;
        this.lastNanos = nowNanos;
    }

    /** Takes {@code amount} tokens if that many are available at {@code nowNanos}. */
    boolean tryTake(double amount, long nowNanos) {
        tokens = Math.min(capacity, tokens + (nowNanos - lastNanos) * perNano);
        lastNanos = nowNanos;
        if (tokens < amount) return false;
        tokens -= amount;
        return true;
    }
}
