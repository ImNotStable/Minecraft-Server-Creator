package io.github.imnotstable.msc.process;

import io.github.imnotstable.msc.process.ProcessTable.Proc;
import io.github.imnotstable.msc.process.ProcessTable.Row;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The tree-kill decisions, over a {@link ProcessTable} so they run the same against the real system and a fake.
 *
 * <p>The one rule: a process is signalled only when it is proven to descend from the root. A row of a snapshot is
 * proven when its recorded parent pid is the pid of a proven process that is in the same snapshot with its own start
 * instant, and the row started no earlier than that parent. That parent was then alive from its start until the
 * snapshot, so at the child's start it held the pid, and pids are unique among live processes. Nothing else is
 * trusted: not a parent pid alone (on Windows an orphan keeps its dead parent's pid, and the pid is soon reused), not
 * a parent that is missing from the snapshot (the JDK's {@code descendants()} of a dead root drops its start-time
 * guard, #357), and not a process whose start instant is unknown. Every signal goes to a process by its identity.
 *
 * <p>A child that a process forks after the last snapshot that saw that process alive cannot be proven once the
 * process is gone, so the kill orders itself to leave no such window where it can:
 * <ul>
 *   <li>Unix: the tree is frozen top-down first, SIGSTOP to the root and then to each newly proven descendant, until
 *       a snapshot proves no new one. On Linux a fork that has not finished when SIGSTOP is queued is restarted after
 *       the stop, so once {@code kill} returns that process makes no new child. Only then is everything killed, root
 *       last, and nothing is left to sweep. A process that was stopped and survived its kill is continued.</li>
 *   <li>Windows, or where the freeze could not finish: the tree is killed from the leaves up, a snapshot per round,
 *       and the root only once a snapshot proves no live descendant, so a parent is alive in the snapshot that finds
 *       a child it just forked. A tree that keeps forking for {@link #ROUNDS} rounds is killed as last proven.</li>
 * </ul>
 */
final class TreeKill {
    /** Rounds of freezing and of sweeping: far more than any real tree is deep. */
    static final int ROUNDS = 64;

    private final ProcessTable table;
    private final Proc root;
    /** Proven processes and the proven parent of each, parents before children; the root maps to null. */
    private final Map<Proc, Proc> tree = new LinkedHashMap<>();
    private final Set<Proc> killed = new HashSet<>();
    private final List<Proc> stopped = new ArrayList<>();

    private TreeKill(ProcessTable table, Proc root) {
        this.table = table;
        this.root = root;
        tree.put(root, null);
    }

    /** Kills {@code root} and every process proven to descend from it; {@code freeze} where SIGSTOP exists. */
    static void destroy(ProcessTable table, Proc root, boolean freeze) {
        TreeKill kill = new TreeKill(table, root);
        if (!(freeze && kill.freeze())) {
            kill.sweep();
        }
        kill.finish();
    }

    /**
     * The processes of {@code snapshot} proven to descend from one of {@code known}, each mapped to its proven
     * parent, parents first. {@code known} processes absent from the snapshot prove nothing.
     */
    static Map<Proc, Proc> proven(List<Row> snapshot, Collection<Proc> known) {
        Map<Long, List<Proc>> byParent = new HashMap<>();
        Set<Proc> present = new HashSet<>();
        for (Row row : snapshot) {
            present.add(row.proc());
            byParent.computeIfAbsent(row.parent(), k -> new ArrayList<>()).add(row.proc());
        }
        Set<Proc> seen = new HashSet<>(known);
        ArrayDeque<Proc> queue = new ArrayDeque<>();
        known.stream().filter(present::contains).forEach(queue::add);
        Map<Proc, Proc> found = new LinkedHashMap<>();
        for (Proc parent = queue.poll(); parent != null; parent = queue.poll()) {
            for (Proc child : byParent.getOrDefault(parent.pid(), List.of())) {
                if (!child.start().isBefore(parent.start()) && seen.add(child)) {
                    found.put(child, parent);
                    queue.add(child);
                }
            }
        }
        return found;
    }

    /** Stops the tree top-down; true once a snapshot proves no process that is not stopped yet. */
    private boolean freeze() {
        List<Proc> next = List.of(root);
        for (int round = 0; round < ROUNDS; round++) {
            next = next.stream().filter(table::alive).toList();
            if (next.isEmpty()) {
                return true;
            }
            if (!table.stop(next)) {
                return false;
            }
            stopped.addAll(next);
            Map<Proc, Proc> found = proven(table.snapshot(List.copyOf(tree.keySet())), tree.keySet());
            tree.putAll(found);
            next = List.copyOf(found.keySet());
        }
        return false;
    }

    /** Kills the leaves of the proven tree, a snapshot per round, until a snapshot proves no live descendant. */
    private void sweep() {
        for (int round = 0; round < ROUNDS; round++) {
            List<Row> snapshot = table.snapshot(List.copyOf(tree.keySet()));
            tree.putAll(proven(snapshot, tree.keySet()));
            Set<Proc> present = new HashSet<>();
            snapshot.forEach(row -> present.add(row.proc()));
            List<Proc> open = tree.keySet().stream()
                    .filter(p -> p != root && present.contains(p) && !killed.contains(p)).toList();
            if (open.isEmpty()) {
                return;
            }
            Set<Proc> parents = new HashSet<>();
            open.forEach(p -> parents.add(tree.get(p)));
            open.stream().filter(p -> !parents.contains(p)).forEach(this::kill);
        }
    }

    /** Kills what is proven and not killed yet, deepest first and the root last, then continues stopped survivors. */
    private void finish() {
        List<Proc> order = new ArrayList<>(tree.keySet());
        for (int i = order.size() - 1; i >= 0; i--) {
            if (!killed.contains(order.get(i))) {
                kill(order.get(i));
            }
        }
        List<Proc> survivors = stopped.stream().filter(table::alive).toList();
        if (!survivors.isEmpty()) {
            table.resume(survivors);
        }
    }

    private void kill(Proc p) {
        killed.add(p);
        table.kill(p);
    }
}
