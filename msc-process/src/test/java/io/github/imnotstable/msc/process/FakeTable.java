package io.github.imnotstable.msc.process;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;

/**
 * A process table in memory. In {@code windows} mode an orphan keeps its dead parent's pid and there is no stop
 * signal; otherwise an orphan's parent pid becomes 1. Pids are whatever the test gives, so it can reuse them at once.
 * Every signal is checked against the true ancestry: one that reaches a process outside the root's tree is recorded in
 * {@link #violations}.
 */
final class FakeTable implements ProcessTable {
    final boolean windows;
    final List<String> violations = new ArrayList<>();
    final List<P> procs = new ArrayList<>();
    /** Runs at the start of every snapshot, and after every kill, so a test can fork, exit and reuse pids then. */
    Consumer<FakeTable> onSnapshot = t -> { };
    Consumer<FakeTable> onKill = t -> { };
    int snapshots;
    P root;
    private long clock = 1_000;

    FakeTable(boolean windows) {
        this.windows = windows;
    }

    final class P {
        final long pid;
        final Instant start;
        final P parent;
        long ppid;
        boolean alive = true;
        boolean stopped;
        boolean unkillable;
        boolean startUnknown;

        P(long pid, P parent) {
            this.pid = pid;
            this.parent = parent;
            this.ppid = parent == null ? 0 : parent.pid;
            this.start = Instant.ofEpochMilli(clock++);
        }

        Proc proc() {
            return new Proc(pid, start);
        }

        boolean inTree() {
            for (P p = this; p != null; p = p.parent) {
                if (p == root) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public String toString() {
            return "pid " + pid + "@" + start.toEpochMilli();
        }
    }

    /** Starts process {@code pid} as a child of {@code parent} (null for none); the pid must be free. */
    P spawn(long pid, P parent) {
        if (procs.stream().anyMatch(p -> p.alive && p.pid == pid)) {
            throw new IllegalStateException("pid " + pid + " is in use");
        }
        if (parent != null && (!parent.alive || parent.stopped)) {
            throw new IllegalStateException(parent + " cannot fork");
        }
        P p = new P(pid, parent);
        procs.add(p);
        return p;
    }

    /** Starts the root, the process the test kills the tree of. */
    P root(long pid) {
        root = spawn(pid, null);
        return root;
    }

    void exit(P p) {
        p.alive = false;
        p.stopped = false;
        if (!windows) {
            procs.stream().filter(c -> c.alive && c.ppid == p.pid && c.parent == p).forEach(c -> c.ppid = 1);
        }
    }

    boolean pidFree(long pid) {
        return procs.stream().noneMatch(p -> p.alive && p.pid == pid);
    }

    List<P> live() {
        return procs.stream().filter(p -> p.alive).toList();
    }

    private P find(Proc q) {
        return procs.stream().filter(p -> p.alive && p.pid == q.pid() && p.start.equals(q.start())).findFirst()
                .orElse(null);
    }

    private P signalled(Proc q, String what) {
        P p = find(q);
        if (p != null && !p.inTree()) {
            violations.add(what + " " + p);
        }
        return p;
    }

    @Override
    public List<Row> snapshot(Collection<Proc> seeds) {
        snapshots++;
        onSnapshot.accept(this);
        // Every live process, unrelated ones and stale parent pids included: harsher than the real table.
        return procs.stream().filter(p -> p.alive && !p.startUnknown).map(p -> new Row(p.proc(), p.ppid)).toList();
    }

    @Override
    public boolean alive(Proc q) {
        return find(q) != null;
    }

    @Override
    public boolean kill(Proc q) {
        P p = signalled(q, "kill");
        if (p == null || p.unkillable) {
            return false;
        }
        exit(p);
        onKill.accept(this);
        return true;
    }

    @Override
    public boolean stop(List<Proc> ps) {
        if (windows) {
            return false;
        }
        ps.forEach(q -> {
            P p = signalled(q, "stop");
            if (p != null) {
                p.stopped = true;
            }
        });
        return true;
    }

    @Override
    public void resume(List<Proc> ps) {
        ps.forEach(q -> {
            P p = signalled(q, "resume");
            if (p != null) {
                p.stopped = false;
            }
        });
    }
}
