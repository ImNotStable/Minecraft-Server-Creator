package io.github.imnotstable.msc.stdlib.util;

import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * Picks entries by weight: loot tables, mob spawns, crate rewards.
 *
 * <pre>{@code
 * WeightedRandom<String> loot = new WeightedRandom<String>().add("diamond", 1).add("iron", 10).add("dirt", 89);
 * String drop = loot.pick(ThreadLocalRandom.current());
 * }</pre>
 */
public final class WeightedRandom<T> {
    private final List<T> items = new ArrayList<>();
    private final List<Double> cumulative = new ArrayList<>();
    private double total;

    /** Adds an entry; weights need not sum to anything. Non-positive weights are refused. */
    public WeightedRandom<T> add(T item, double weight) {
        if (!(weight > 0)) {
            throw new IllegalArgumentException("Weight must be > 0: " + weight);
        }
        total += weight;
        items.add(item);
        cumulative.add(total);
        return this;
    }

    public T pick(RandomGenerator random) {
        if (items.isEmpty()) {
            throw new IllegalStateException("No entries to pick from");
        }
        double r = random.nextDouble(total);
        int lo = 0, hi = cumulative.size() - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (cumulative.get(mid) > r) {
                hi = mid;
            } else {
                lo = mid + 1;
            }
        }
        return items.get(lo);
    }

    /** The chance of {@code item}, 0 to 1. */
    public double chance(T item) {
        double w = 0, prev = 0;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).equals(item)) {
                w += cumulative.get(i) - prev;
            }
            prev = cumulative.get(i);
        }
        return total == 0 ? 0 : w / total;
    }

    public int size() {
        return items.size();
    }
}
