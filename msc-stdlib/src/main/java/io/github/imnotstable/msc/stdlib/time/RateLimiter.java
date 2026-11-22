package io.github.imnotstable.msc.stdlib.time;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * At most {@code permits} uses per {@code per} for each key, refilled smoothly (token bucket): chat spam, command
 * spam, ability use. Unlike {@link Cooldowns} it allows short bursts.
 *
 * <pre>{@code
 * RateLimiter<UUID> chat = new RateLimiter<>(5, Duration.ofSeconds(10));
 * if (!chat.tryAcquire(player.getUniqueId())) { event.setCancelled(true); }
 * }</pre>
 */
public final class RateLimiter<K> {
    private record Bucket(double tokens, long at) {}

    private final double permits;
    private final double perMilli;
    private final Clock clock;
    private final Map<K, Bucket> buckets = new ConcurrentHashMap<>();

    public RateLimiter(int permits, Duration per) {
        this(permits, per, Clock.systemUTC());
    }

    public RateLimiter(int permits, Duration per, Clock clock) {
        this.permits = permits;
        this.perMilli = permits / (double) Math.max(1, per.toMillis());
        this.clock = clock;
    }

    /** Uses one permit; false (and nothing used) when the key has none left. */
    public boolean tryAcquire(K key) {
        long now = clock.millis();
        boolean[] ok = {false};
        buckets.compute(key, (k, b) -> {
            double tokens = b == null ? permits : Math.min(permits, b.tokens() + (now - b.at()) * perMilli);
            if (tokens >= 1) {
                ok[0] = true;
                tokens -= 1;
            }
            return new Bucket(tokens, now);
        });
        return ok[0];
    }

    /** Permits the key has now. */
    public int available(K key) {
        Bucket b = buckets.get(key);
        return b == null ? (int) permits : (int) Math.min(permits, b.tokens() + (clock.millis() - b.at()) * perMilli);
    }

    public void reset(K key) {
        buckets.remove(key);
    }
}
