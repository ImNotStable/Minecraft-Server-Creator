package io.github.imnotstable.msc.stdlib.time;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cooldowns by key (a player id, {@code id + ":dash"}, a block position...). In memory: they end with a restart.
 *
 * <pre>{@code
 * Cooldowns<UUID> dash = new Cooldowns<>();
 * if (!dash.tryUse(player.getUniqueId(), Duration.ofSeconds(5))) {
 *     player.sendMessage("Wait " + Durations.format(dash.remaining(player.getUniqueId())));
 * }
 * }</pre>
 */
public final class Cooldowns<K> {
    private final Map<K, Instant> until = new ConcurrentHashMap<>();
    private final Clock clock;

    public Cooldowns() {
        this(Clock.systemUTC());
    }

    /** With your own clock, for tests. */
    public Cooldowns(Clock clock) {
        this.clock = clock;
    }

    /** Starts the cooldown and returns true when {@code key} is ready; returns false (and changes nothing) when not. */
    public boolean tryUse(K key, Duration cooldown) {
        Instant now = clock.instant();
        boolean[] used = {false};
        until.compute(key, (k, end) -> {
            if (end != null && end.isAfter(now)) {
                return end;
            }
            used[0] = true;
            return now.plus(cooldown);
        });
        return used[0];
    }

    /** Starts (or restarts) the cooldown whether or not it was ready. */
    public void set(K key, Duration cooldown) {
        until.put(key, clock.instant().plus(cooldown));
    }

    public boolean ready(K key) {
        return remaining(key).isZero();
    }

    /** Time left, {@link Duration#ZERO} when ready. */
    public Duration remaining(K key) {
        Instant end = until.get(key);
        if (end == null) {
            return Duration.ZERO;
        }
        Duration left = Duration.between(clock.instant(), end);
        return left.isNegative() ? Duration.ZERO : left;
    }

    public void reset(K key) {
        until.remove(key);
    }

    public void clear() {
        until.clear();
    }
}
