package io.github.imnotstable.msc.stdlib.game;

import io.github.imnotstable.msc.stdlib.geom.Vec3;
import io.github.imnotstable.msc.stdlib.sched.Scheduler;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;

class GameTest {
    /** Runs tasks by game tick, repeats included. */
    static final class Ticks implements Scheduler {
        private final List<Object[]> tasks = new ArrayList<>(); // due, period, runnable, cancelled flag
        long now;

        @Override
        public Executor sync() {
            return Runnable::run;
        }

        @Override
        public Executor async() {
            return Runnable::run;
        }

        @Override
        public Task later(long delay, Runnable task) {
            return add(delay, 0, task);
        }

        @Override
        public Task repeat(long delay, long period, Runnable task) {
            return add(delay, period, task);
        }

        private Task add(long delay, long period, Runnable r) {
            boolean[] off = {false};
            tasks.add(new Object[]{now + delay, period, r, off});
            return new Task() {
                @Override
                public void cancel() {
                    off[0] = true;
                }

                @Override
                public boolean cancelled() {
                    return off[0];
                }
            };
        }

        void seconds(int n) {
            for (int i = 0; i < n * 20; i++) {
                for (Object[] t : List.copyOf(tasks)) {
                    boolean[] off = (boolean[]) t[3];
                    if (!off[0] && (long) t[0] <= now) {
                        ((Runnable) t[2]).run();
                        if ((long) t[1] > 0) {
                            t[0] = now + (long) t[1];
                        } else {
                            off[0] = true;
                        }
                    }
                }
                for (Iterator<Object[]> it = tasks.iterator(); it.hasNext(); ) {
                    if (((boolean[]) it.next()[3])[0]) {
                        it.remove();
                    }
                }
                now++;
            }
        }
    }

    private final Ticks ticks = new Ticks();
    private final Map<UUID, Vec3> where = new HashMap<>();
    private final List<String> log = new ArrayList<>();
    private final Arena arena = Arena.builder("ruins").lobby(new Vec3(0, 100, 0)).spectate(new Vec3(0, 120, 0))
            .spawn("red", new Vec3(-10, 64, 0)).spawn("blue", new Vec3(10, 64, 0)).players(2, 4).build();

    private Game.Listener listener() {
        return new Game.Listener() {
            @Override
            public void teleport(UUID p, Vec3 to) {
                where.put(p, to);
            }

            @Override
            public void countdown(int left) {
                log.add("count " + left);
            }

            @Override
            public void countdownCancelled() {
                log.add("cancelled");
            }

            @Override
            public void started() {
                log.add("started");
            }

            @Override
            public void ended(Game.Result r) {
                log.add("won " + r.winnerTeam() + " " + r.reason());
            }
        };
    }

    @Test
    void lobbyCountdownTeamsLivesAndLastTeamStanding() {
        Game g = Game.builder(arena, ticks).teams(new Team("red", "red", 2), new Team("blue", "blue", 2)).countdown(3)
                .lives(2).endPause(2).listener(listener()).build();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        g.join(a);
        assertThat(g.phase()).isEqualTo(Game.Phase.WAITING);
        assertThat(where.get(a)).isEqualTo(arena.lobby());
        g.join(b);
        assertThat(g.phase()).isEqualTo(Game.Phase.STARTING);
        g.leave(b);
        assertThat(log).contains("cancelled");
        assertThat(g.phase()).isEqualTo(Game.Phase.WAITING);
        g.join(b);
        g.join(c);
        assertThat(g.teamOf(a).name()).isNotEqualTo(g.teamOf(b).name());
        ticks.seconds(4);
        assertThat(log).contains("count 3", "count 1", "started");
        assertThat(g.phase()).isEqualTo(Game.Phase.RUNNING);
        assertThat(where.get(a)).isIn(new Vec3(-10, 64, 0), new Vec3(10, 64, 0));
        // A late joiner watches.
        UUID late = UUID.randomUUID();
        g.join(late);
        assertThat(g.spectators()).contains(late);
        // Wipe out the team without a or c's partner: find b's team and eliminate everyone on it twice.
        String loser = g.teamOf(b).name();
        for (UUID p : g.teams().get(loser).members()) {
            g.eliminate(p, null);
            assertThat(g.lives(p)).isEqualTo(1);
        }
        for (UUID p : List.copyOf(g.teams().get(loser).members())) {
            g.eliminate(p, null);
        }
        String winner = loser.equals("red") ? "blue" : "red";
        assertThat(log).contains("won " + winner + " LAST_STANDING");
        assertThat(g.phase()).isEqualTo(Game.Phase.ENDING);
        ticks.seconds(3);
        // Reset: back in the lobby, the spectator now a player, a new countdown running.
        assertThat(g.players()).contains(late);
        assertThat(g.phase()).isEqualTo(Game.Phase.STARTING);
        assertThat(where.get(a)).isEqualTo(arena.lobby());
    }

    @Test
    void scoreToWinAndTimeLimitInAFreeForAll() {
        Game g = Game.builder(arena, ticks).countdown(0).scoreToWin(3).timeLimit(5).endPause(0).listener(listener())
                .build();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        g.join(a);
        g.join(b);
        assertThat(g.phase()).isEqualTo(Game.Phase.RUNNING);
        g.eliminate(b, a); // a kill is a point, lives 1 puts b out: a is last standing
        assertThat(log).contains("won null LAST_STANDING");
        // Next round: reset put them back in and started again (countdown 0).
        assertThat(g.phase()).isEqualTo(Game.Phase.RUNNING);
        g.addScore(b, 1);
        ticks.seconds(6);
        assertThat(log).contains("won null TIME");
    }

    @Test
    void matchmakerFillsTheFullestOpenGameFirst() {
        Matchmaker m = new Matchmaker(() -> Game.builder(arena, ticks).countdown(30).listener(listener()).build(), 2);
        for (int i = 0; i < 4; i++) {
            assertThat(m.join(UUID.randomUUID())).isTrue();
        }
        assertThat(m.games()).hasSize(1);
        assertThat(m.join(UUID.randomUUID())).isTrue(); // full: a second game opens
        assertThat(m.games()).hasSize(2);
    }
}
