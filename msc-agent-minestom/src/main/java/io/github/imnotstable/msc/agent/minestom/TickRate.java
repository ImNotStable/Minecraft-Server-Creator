package io.github.imnotstable.msc.agent.minestom;

/**
 * Ticks per second over the last minute, from the times ticks started. Minestom has no TPS
 * counter of its own; the agent records one sample per tick.
 */
final class TickRate {
    private static final long WINDOW_NANOS = 60_000_000_000L;

    private final long[] samples;
    private final int maxTps;
    private int count;
    private int head;

    TickRate(int maxTps) {
        this.maxTps = maxTps;
        this.samples = new long[maxTps * 60 + 1];
    }

    /** Record that a tick started at {@code nanos} ({@code System.nanoTime()}). */
    synchronized void tick(long nanos) {
        samples[head] = nanos;
        head = (head + 1) % samples.length;
        count = Math.min(count + 1, samples.length);
    }

    /** Ticks per second over the last minute (at most {@code maxTps}), or -1 before two ticks. */
    synchronized double tps(long now) {
        int newest = Math.floorMod(head - 1, samples.length);
        int n = 0;
        long oldest = samples[newest];
        for (int i = 0; i < count; i++) {
            long t = samples[Math.floorMod(newest - i, samples.length)];
            if (now - t > WINDOW_NANOS) {
                break;
            }
            oldest = t;
            n++;
        }
        if (n < 2) {
            return -1;
        }
        double seconds = (samples[newest] - oldest) / 1e9;
        return Math.min(maxTps, (n - 1) / seconds);
    }
}
