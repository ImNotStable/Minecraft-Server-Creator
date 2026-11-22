package io.github.imnotstable.msc.stdlib.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/** Everyday randomness on {@link ThreadLocalRandom}: chances, ranges and picks. */
public final class Randoms {
    private Randoms() {}

    /** True with probability {@code p} (0 to 1). */
    public static boolean chance(double p) {
        return ThreadLocalRandom.current().nextDouble() < p;
    }

    /** {@code min} to {@code max}, both included. */
    public static int between(int min, int max) {
        return ThreadLocalRandom.current().nextInt(min, max + 1);
    }

    public static double between(double min, double max) {
        return ThreadLocalRandom.current().nextDouble(min, max);
    }

    public static <T> T pick(List<T> list) {
        return list.get(ThreadLocalRandom.current().nextInt(list.size()));
    }

    @SafeVarargs
    public static <T> T pick(T... values) {
        return values[ThreadLocalRandom.current().nextInt(values.length)];
    }

    /** {@code n} different elements (all of them when there are fewer). */
    public static <T> List<T> sample(Collection<T> from, int n) {
        List<T> copy = new ArrayList<>(from);
        java.util.Collections.shuffle(copy, ThreadLocalRandom.current());
        return copy.subList(0, Math.min(n, copy.size()));
    }

    public static <T> List<T> shuffled(Collection<T> from) {
        return sample(from, from.size());
    }
}
