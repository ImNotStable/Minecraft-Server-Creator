package io.github.imnotstable.msc.stdlib.fabric;

import io.github.imnotstable.msc.stdlib.sched.Scheduler;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** {@link Scheduler} on the server tick ({@link Stdlib#init} drives {@link #tick}). */
public final class FabricScheduler implements Scheduler {
    private static final ExecutorService ASYNC = Executors.newVirtualThreadPerTaskExecutor();
    private final ConcurrentLinkedQueue<Entry> added = new ConcurrentLinkedQueue<>();
    private final List<Entry> tasks = new ArrayList<>();
    private long now;

    private static final class Entry implements Task {
        final Runnable task;
        final long period;
        long due;
        volatile boolean cancelled;

        Entry(Runnable task, long due, long period) {
            this.task = task;
            this.due = due;
            this.period = period;
        }

        @Override
        public void cancel() {
            cancelled = true;
        }

        @Override
        public boolean cancelled() {
            return cancelled;
        }
    }

    /** Runs on the next tick (Minecraft's server thread). */
    @Override
    public Executor sync() {
        return r -> later(0, r);
    }

    @Override
    public Executor async() {
        return ASYNC;
    }

    @Override
    public Task later(long delayTicks, Runnable task) {
        return add(new Entry(task, delayTicks, -1));
    }

    @Override
    public Task repeat(long delayTicks, long periodTicks, Runnable task) {
        return add(new Entry(task, delayTicks, Math.max(1, periodTicks)));
    }

    private Task add(Entry e) {
        added.add(e);
        return e;
    }

    /** One server tick: runs what is due. Delays count from the tick after scheduling. */
    public void tick() {
        for (Entry e; (e = added.poll()) != null; ) {
            e.due += now;
            tasks.add(e);
        }
        for (Entry e : List.copyOf(tasks)) {
            if (e.cancelled) {
                tasks.remove(e);
            } else if (e.due <= now) {
                e.task.run();
                if (e.period < 0 || e.cancelled) {
                    tasks.remove(e);
                } else {
                    e.due = now + e.period;
                }
            }
        }
        now++;
    }
}
