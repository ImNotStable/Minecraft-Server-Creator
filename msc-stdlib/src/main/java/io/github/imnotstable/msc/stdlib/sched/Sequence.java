package io.github.imnotstable.msc.stdlib.sched;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * Steps with waits between them, on the game thread: intros, round phases, cutscenes, boss attack patterns.
 *
 * <pre>{@code
 * Sequence.of(scheduler)
 *         .run(() -> title("Get ready"))
 *         .pause(40)
 *         .repeat(3, 20, n -> title(String.valueOf(3 - n)))   // 3, 2, 1 a second apart
 *         .run(game::start)
 *         .start();
 * }</pre>
 */
public final class Sequence {
    private record Step(long delay, Runnable action) {}

    private final Scheduler scheduler;
    private final List<Step> steps = new ArrayList<>();
    private long pendingDelay;

    private Sequence(Scheduler scheduler) {
        this.scheduler = scheduler;
    }

    public static Sequence of(Scheduler scheduler) {
        return new Sequence(scheduler);
    }

    public Sequence run(Runnable action) {
        steps.add(new Step(pendingDelay, action));
        pendingDelay = 0;
        return this;
    }

    /** Waits {@code ticks} before the next step. */
    public Sequence pause(long ticks) {
        pendingDelay += ticks;
        return this;
    }

    /** {@code action(0)}, then {@code action(1)} {@code every} ticks later, {@code times} in all. */
    public Sequence repeat(int times, long every, IntConsumer action) {
        for (int i = 0; i < times; i++) {
            int n = i;
            if (i > 0) {
                pause(every);
            }
            run(() -> action.accept(n));
        }
        return this;
    }

    /** Starts it; cancel the task to stop the remaining steps. */
    public Scheduler.Task start() {
        List<Step> plan = List.copyOf(steps);
        Scheduler.Task[] current = new Scheduler.Task[1];
        boolean[] cancelled = {false};
        Runnable[] next = new Runnable[1];
        int[] index = {0};
        next[0] = () -> {
            if (cancelled[0] || index[0] >= plan.size()) {
                return;
            }
            Step s = plan.get(index[0]++);
            current[0] = scheduler.later(s.delay(), () -> {
                if (!cancelled[0]) {
                    s.action().run();
                    next[0].run();
                }
            });
        };
        next[0].run();
        return new Scheduler.Task() {
            @Override
            public void cancel() {
                cancelled[0] = true;
                if (current[0] != null) {
                    current[0].cancel();
                }
            }

            @Override
            public boolean cancelled() {
                return cancelled[0];
            }
        };
    }
}
