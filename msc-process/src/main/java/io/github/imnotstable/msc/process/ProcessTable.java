package io.github.imnotstable.msc.process;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * The view of the system's processes that {@link TreeKill} decides on. {@link LiveProcessTable} is the real one; tests
 * use a fake that models Windows and Unix semantics.
 */
interface ProcessTable {

    /**
     * A process's identity. A pid alone is not one: the system gives a dead process's pid to a new process, and on
     * Windows soon. Pid and start instant together name one process.
     */
    record Proc(long pid, Instant start) {
        public Proc {
            Objects.requireNonNull(start, "start");
        }
    }

    /** One process in a snapshot, with the pid it records as its parent (which may be stale, see {@link #snapshot}). */
    record Row(Proc proc, long parent) {
    }

    /**
     * Live processes with their parent pid. It holds at least every live descendant of each seed that is alive and
     * may hold other processes too, including ones whose recorded parent pid is stale: on Windows a process keeps its
     * parent's pid after the parent dies and that pid can name a newer process. A process whose start instant is
     * unknown is left out, since nothing can be proven about it.
     */
    List<Row> snapshot(Collection<Proc> seeds);

    /** True while {@code p} (that pid with that start instant) is alive. */
    boolean alive(Proc p);

    /** Forcibly ends {@code p} if it is still that process; never a process that reused its pid. */
    boolean kill(Proc p);

    /** Stops (SIGSTOP) those of {@code ps} still alive; false when stopping is not available or could not run. */
    boolean stop(List<Proc> ps);

    /** Continues (SIGCONT) those of {@code ps} still alive. */
    void resume(List<Proc> ps);
}
