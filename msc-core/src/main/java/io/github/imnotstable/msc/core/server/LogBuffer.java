package io.github.imnotstable.msc.core.server;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/** The last lines the server printed, numbered so a caller can ask for "everything since". */
public final class LogBuffer {
    private final int capacity;
    private final ArrayDeque<String> lines = new ArrayDeque<>();
    private long next;

    public LogBuffer(int capacity) {
        this.capacity = capacity;
    }

    public synchronized void add(String line) {
        if (lines.size() == capacity) {
            lines.removeFirst();
        }
        lines.addLast(line);
        next++;
        notifyAll();
    }

    /** Sequence number of the next line; pass to {@link #since}. */
    public synchronized long mark() {
        return next;
    }

    public synchronized List<String> since(long mark) {
        long first = next - lines.size();
        List<String> out = new ArrayList<>();
        long i = first;
        for (String line : lines) {
            if (i++ >= mark) {
                out.add(line);
            }
        }
        return out;
    }

    /** A line found by {@link #await}: its text and its sequence number. */
    public record Match(String line, long seq) {
    }

    /**
     * Wait for the first line from {@code mark} on that {@code test} accepts. Returns null when {@code timeout}
     * passes or {@code alive} turns false first. Lines that already scrolled out of the buffer are not seen.
     */
    public synchronized Match await(long mark, Predicate<String> test, Duration timeout,
                                    BooleanSupplier alive) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        long checked = mark;
        while (true) {
            long first = next - lines.size();
            long i = first;
            for (String line : lines) {
                if (i >= checked && test.test(line)) {
                    return new Match(line, i);
                }
                i++;
            }
            checked = next;
            long left = deadline - System.nanoTime();
            if (left <= 0 || !alive.getAsBoolean()) {
                return null;
            }
            wait(Math.max(1, Math.min(TimeUnit.NANOSECONDS.toMillis(left), 250)));
        }
    }

    public synchronized List<String> all() {
        return new ArrayList<>(lines);
    }
}
