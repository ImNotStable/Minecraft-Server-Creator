package io.github.imnotstable.msc.core.server;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

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

    public synchronized List<String> all() {
        return new ArrayList<>(lines);
    }
}
