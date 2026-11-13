package io.github.imnotstable.msc.process;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.process.FakeTable.P;
import io.github.imnotstable.msc.process.ProcessTable.Proc;
import io.github.imnotstable.msc.process.ProcessTable.Row;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class TreeKillTest {

    private static void destroy(FakeTable t) {
        TreeKill.destroy(t, t.root.proc(), !t.windows);
    }

    /**
     * #357: on Windows an orphan keeps its dead parent's pid, and the root got that pid later. The orphan, and its
     * child, record the root's pid but started before the root: they survive, while the root's real tree dies.
     */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void aProcessWithAStaleParentPidEqualToTheRootsSurvives(boolean windows) {
        FakeTable t = new FakeTable(windows);
        P launcher = t.spawn(100, null);
        P stale = t.spawn(200, launcher);
        P staleChild = t.spawn(201, stale);
        t.exit(launcher);
        P root = t.root(100);
        P child = t.spawn(101, root);
        P grandchild = t.spawn(102, child);

        destroy(t);

        assertThat(t.violations).isEmpty();
        assertThat(List.of(root.alive, child.alive, grandchild.alive)).containsOnly(false);
        assertThat(stale.alive).isTrue();
        assertThat(staleChild.alive).isTrue();
    }

    /**
     * #357's trigger: the root dies while the tree is killed, and its pid goes to an unrelated process that forks. No
     * snapshot after that may treat the newcomer, or its child that records the old root pid, as the root's.
     */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void aRootThatDiesMidKillLeavesItsReusedPidAlone(boolean windows) {
        FakeTable t = new FakeTable(windows);
        P other = t.spawn(5, null);
        P root = t.root(100);
        P child = t.spawn(101, root);
        P grandchild = t.spawn(102, child);
        List<P> strangers = new ArrayList<>();
        t.onSnapshot = f -> {
            if (f.snapshots == 2 && root.alive) {
                f.exit(root);
                P reuser = f.spawn(100, other);
                strangers.add(reuser);
                strangers.add(f.spawn(300, reuser));
            }
        };
        if (!windows) {
            // On Unix the first snapshot comes after the root is stopped; make it exit then anyway.
            t.onSnapshot = f -> {
                if (f.snapshots == 1 && root.alive) {
                    f.exit(root);
                    P reuser = f.spawn(100, other);
                    strangers.add(reuser);
                    strangers.add(f.spawn(300, reuser));
                }
            };
        }

        destroy(t);

        assertThat(t.violations).isEmpty();
        assertThat(strangers).hasSize(2).allMatch(p -> p.alive);
        assertThat(other.alive).isTrue();
        if (windows) {
            // Proven while the root was alive, so killed by identity after it died.
            assertThat(List.of(child.alive, grandchild.alive)).containsOnly(false);
        }
    }

    /** A child whose parent died and whose parent's pid now names another process is not provable: left alone. */
    @Test
    void anOrphanOfADeadChildIsNotTiedToTheProcessThatReusedThatChildsPid() {
        FakeTable t = new FakeTable(true);
        P other = t.spawn(5, null);
        P root = t.root(100);
        P child = t.spawn(101, root);
        t.spawn(102, child);
        t.exit(child);
        P reuser = t.spawn(101, other);
        P reusersChild = t.spawn(103, reuser);

        destroy(t);

        assertThat(t.violations).isEmpty();
        assertThat(root.alive).isFalse();
        assertThat(List.of(reuser.alive, reusersChild.alive)).containsOnly(true);
    }

    /** A child the root forks while its other children are killed is found, because the root dies last. */
    @Test
    void windowsKillsAChildForkedDuringTheKillBeforeTheRoot() {
        FakeTable t = new FakeTable(true);
        P root = t.root(100);
        t.spawn(101, root);
        List<P> forked = new ArrayList<>();
        t.onKill = f -> {
            if (root.alive && forked.size() < 5) {
                forked.add(f.spawn(200 + forked.size(), root));
            }
        };

        destroy(t);

        assertThat(t.violations).isEmpty();
        assertThat(forked).hasSize(5);
        assertThat(t.live()).isEmpty();
    }

    /** Unix: the tree is stopped top-down while it forks, then killed; a killed process is never continued. */
    @Test
    void unixFreezesAForkingTreeThenKillsIt() {
        FakeTable t = new FakeTable(false);
        P root = t.root(100);
        P child = t.spawn(101, root);
        int[] next = {200};
        t.onSnapshot = f -> f.live().stream().filter(p -> p.inTree() && !p.stopped).toList()
                .forEach(p -> f.spawn(next[0]++, p));

        destroy(t);

        assertThat(t.violations).isEmpty();
        assertThat(t.live()).isEmpty();
        assertThat(child.alive).isFalse();
    }

    /** A stopped process that survives its kill (no permission) is continued, so it is not left stopped. */
    @Test
    void unixContinuesAStoppedProcessThatSurvivedItsKill() {
        FakeTable t = new FakeTable(false);
        P root = t.root(100);
        P child = t.spawn(101, root);
        child.unkillable = true;

        destroy(t);

        assertThat(root.alive).isFalse();
        assertThat(child.alive).isTrue();
        assertThat(child.stopped).isFalse();
    }

    /** A process whose start instant is unknown proves nothing, so neither it nor its children are signalled. */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void aProcessWithAnUnknownStartIsLeftAlone(boolean windows) {
        FakeTable t = new FakeTable(windows);
        P root = t.root(100);
        P hidden = t.spawn(101, root);
        hidden.startUnknown = true;
        P below = t.spawn(102, hidden);

        destroy(t);

        assertThat(root.alive).isFalse();
        assertThat(List.of(hidden.alive, below.alive)).containsOnly(true);
        assertThat(hidden.stopped || below.stopped).isFalse();
    }

    @Test
    void provenNeedsTheParentInTheSnapshotAndAStartNoEarlierThanIt() {
        Proc root = new Proc(100, Instant.ofEpochMilli(50));
        Proc child = new Proc(101, Instant.ofEpochMilli(50));
        Proc older = new Proc(102, Instant.ofEpochMilli(49));
        Proc grandchild = new Proc(103, Instant.ofEpochMilli(60));

        assertThat(TreeKill.proven(List.of(new Row(root, 1), new Row(child, 100), new Row(older, 100),
                new Row(grandchild, 101)), List.of(root))).containsExactly(
                java.util.Map.entry(child, root), java.util.Map.entry(grandchild, child));
        // The root gone from the snapshot: nothing that records its pid is proven (#357).
        assertThat(TreeKill.proven(List.of(new Row(child, 100), new Row(grandchild, 101)), List.of(root))).isEmpty();
    }

    /**
     * Random trees under random churn: members fork, exit on their own and have their pids reused at once by
     * unrelated processes that fork too; old processes record the root's and members' pids as stale parents. No
     * signal may reach a process outside the root's true tree, on either system. On Unix, with no member exiting on
     * its own, the freeze also leaves no member alive.
     */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void neverSignalsAProcessOutsideTheTree(boolean windows) {
        for (int seed = 0; seed < 1500; seed++) {
            Random rnd = new Random(seed);
            boolean exits = windows || rnd.nextBoolean();
            FakeTable t = new FakeTable(windows);
            long[] pid = {1000};
            P system = t.spawn(4, null);
            // Old launchers with the pids the tree will get, each leaving a stale child behind.
            for (long reused = 100; reused < 110; reused++) {
                P launcher = t.spawn(reused, system);
                t.spawn(pid[0]++, launcher);
                t.exit(launcher);
            }
            P root = t.root(100);
            List<P> members = new ArrayList<>(List.of(root));
            for (int i = 0; i < rnd.nextInt(8); i++) {
                P parent = members.get(rnd.nextInt(members.size()));
                long p = 101 + i;
                if (t.pidFree(p)) {
                    members.add(t.spawn(p, parent));
                }
            }
            java.util.function.Consumer<FakeTable> churn = f -> {
                List<P> live = f.live();
                for (int k = 0; k < 3; k++) {
                    P p = live.get(rnd.nextInt(live.size()));
                    switch (rnd.nextInt(4)) {
                        case 0 -> {
                            if (p.alive && !p.stopped) {
                                f.spawn(pid[0]++, p);
                            }
                        }
                        case 1 -> {
                            if (exits && p.alive && p.inTree() && !p.stopped) {
                                f.exit(p);
                            }
                        }
                        case 2 -> {
                            // Reuse a dead pid at once, from outside the tree, and fork below it.
                            P dead = f.procs.get(rnd.nextInt(f.procs.size()));
                            if (!dead.alive && f.pidFree(dead.pid) && system.alive) {
                                P reuser = f.spawn(dead.pid, system);
                                f.spawn(pid[0]++, reuser);
                            }
                        }
                        default -> {
                        }
                    }
                }
            };
            t.onSnapshot = churn;
            t.onKill = churn;

            destroy(t);

            assertThat(t.violations).as("seed %d", seed).isEmpty();
            assertThat(root.alive).as("seed %d", seed).isFalse();
            if (!windows && !exits) {
                assertThat(t.live().stream().filter(P::inTree)).as("seed %d", seed).isEmpty();
            }
        }
    }
}
