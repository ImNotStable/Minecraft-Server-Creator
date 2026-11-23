package io.github.imnotstable.msc.stdlib.game;

import io.github.imnotstable.msc.stdlib.geom.Vec3;
import io.github.imnotstable.msc.stdlib.sched.Scheduler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * One match in an arena, the part every minigame repeats: players join a lobby, a countdown starts once enough are in
 * (and stops if they leave), teams fill evenly, everyone is sent to a spawn, lives and scores are kept, the last team
 * or player standing, the first to a score, or the best when time runs out wins, and after a pause the game resets for
 * the next round. Platform-neutral: what a player sees and where they go is the {@link Listener}'s job (teleport,
 * titles, kits, sidebars), so the same rules run on Paper, Fabric, Forge, NeoForge and Minestom.
 *
 * <pre>{@code
 * Game game = Game.builder(arena, scheduler)
 *         .teams(new Team("red", "red", 8), new Team("blue", "blue", 8))
 *         .countdown(15).lives(3).scoreToWin(25).timeLimit(600)
 *         .listener(new Game.Listener() {
 *             public void teleport(UUID p, Vec3 to) { ... }                 // the platform moves the player
 *             public void countdown(int left) { broadcast("Starting in " + left); }
 *             public void ended(Game.Result r) { broadcast(r.winnerTeam() + " wins!"); }
 *         })
 *         .build();
 * game.join(player.getUniqueId());
 * // in your death listener:   game.eliminate(victim, killer);
 * // in your objective code:   game.addScore(player, 1);
 * }</pre>
 *
 * Not thread-safe: call it from the main thread, as the scheduler does.
 */
public final class Game {
    /** Where a game is in its round. */
    public enum Phase {
        /** In the lobby, waiting for enough players. */
        WAITING,
        /** Counting down to the start. */
        STARTING,
        /** Being played. */
        RUNNING,
        /** Over: the winner is shown, nobody can join as a player. */
        ENDING
    }

    /** Why a game ended. */
    public enum Reason {
        LAST_STANDING, SCORE, TIME, STOPPED
    }

    /**
     * How a game ended: the winning team (null in a free-for-all or a draw), the winning players, every player's
     * score and why.
     */
    public record Result(String winnerTeam, List<UUID> winners, Map<UUID, Long> scores, Reason reason) {
    }

    /** What a game asks of the platform and tells it; every method is optional. */
    public interface Listener {
        /** Move a player to a point in the arena's world. */
        default void teleport(UUID player, Vec3 to) {
        }

        default void phase(Phase phase) {
        }

        default void joined(UUID player, Team team) {
        }

        default void left(UUID player) {
        }

        /** Each second of the countdown: n, n-1 ... 1. */
        default void countdown(int secondsLeft) {
        }

        /** The countdown stopped because players left. */
        default void countdownCancelled() {
        }

        /** The game started: hand out kits, show the objective. */
        default void started() {
        }

        /** Each second while running: the seconds left, or -1 without a time limit. */
        default void second(int secondsLeft) {
        }

        /** A player respawned with {@code livesLeft}. */
        default void respawned(UUID player, int livesLeft) {
        }

        /** A player is out of lives; {@code by} who did it, or null. */
        default void eliminated(UUID player, UUID by) {
        }

        default void scored(UUID player, long total) {
        }

        default void ended(Result result) {
        }

        /** The game is ready for the next round; players stay in the lobby. */
        default void reset() {
        }
    }

    private final Arena arena;
    private final Scheduler scheduler;
    private final Map<String, Team> teams = new LinkedHashMap<>();
    private final Set<UUID> players = new LinkedHashSet<>();
    private final Set<UUID> spectators = new LinkedHashSet<>();
    private final Map<UUID, Integer> lives = new HashMap<>();
    private final Map<UUID, Long> scores = new HashMap<>();
    private final Set<UUID> out = new LinkedHashSet<>();
    private final int countdownSeconds;
    private final int maxLives;
    private final long scoreToWin;
    private final int timeLimit;
    private final int endSeconds;
    private final boolean spectatorsAllowed;
    private final Listener listener;
    private Phase phase = Phase.WAITING;
    private Scheduler.Task timer;
    private int secondsLeft;
    private int spawnCursor;

    private Game(Builder b) {
        this.arena = b.arena;
        this.scheduler = b.scheduler;
        b.teams.forEach(t -> teams.put(t.name(), t));
        this.countdownSeconds = b.countdown;
        this.maxLives = b.lives;
        this.scoreToWin = b.scoreToWin;
        this.timeLimit = b.timeLimit;
        this.endSeconds = b.endSeconds;
        this.spectatorsAllowed = b.spectators;
        this.listener = b.listener;
    }

    public static Builder builder(Arena arena, Scheduler scheduler) {
        return new Builder(arena, scheduler);
    }

    public Arena arena() {
        return arena;
    }

    public Phase phase() {
        return phase;
    }

    public Set<UUID> players() {
        return Collections.unmodifiableSet(players);
    }

    public Set<UUID> spectators() {
        return Collections.unmodifiableSet(spectators);
    }

    /** Players still in the round (not out of lives). */
    public Set<UUID> alive() {
        Set<UUID> a = new LinkedHashSet<>(players);
        a.removeAll(out);
        return a;
    }

    public Map<String, Team> teams() {
        return Collections.unmodifiableMap(teams);
    }

    public Team teamOf(UUID player) {
        for (Team t : teams.values()) {
            if (t.contains(player)) {
                return t;
            }
        }
        return null;
    }

    public long score(UUID player) {
        return scores.getOrDefault(player, 0L);
    }

    public int lives(UUID player) {
        return lives.getOrDefault(player, 0);
    }

    public int secondsLeft() {
        return secondsLeft;
    }

    public boolean contains(UUID player) {
        return players.contains(player) || spectators.contains(player);
    }

    public boolean full() {
        return players.size() >= arena.maxPlayers();
    }

    /**
     * A player joins: into the lobby and the smallest team while the game has not started (the countdown starts once
     * the arena's minimum is in), as a spectator once it runs. False when it is full and takes no spectators.
     */
    public boolean join(UUID player) {
        if (contains(player)) {
            return true;
        }
        if (phase == Phase.RUNNING || phase == Phase.ENDING || full()) {
            if (!spectatorsAllowed) {
                return false;
            }
            spectators.add(player);
            listener.teleport(player, arena.spectate());
            listener.joined(player, null);
            return true;
        }
        Team team = null;
        if (!teams.isEmpty()) {
            team = teams.values().stream().filter(t -> !t.full()).min(Comparator.comparingInt(Team::size)).orElse(null);
            if (team == null) {
                return false;
            }
            team.add(player);
        }
        players.add(player);
        listener.teleport(player, arena.lobby());
        listener.joined(player, team);
        if (phase == Phase.WAITING && players.size() >= arena.minPlayers()) {
            startCountdown();
        }
        return true;
    }

    /** Move a waiting player to another team; false once the game has started or the team is full. */
    public boolean switchTeam(UUID player, String team) {
        Team to = teams.get(team);
        if (to == null || to.full() || !players.contains(player) || phase == Phase.RUNNING || phase == Phase.ENDING) {
            return false;
        }
        Team from = teamOf(player);
        if (from != null) {
            from.remove(player);
        }
        return to.add(player);
    }

    /** A player leaves (quit, or a leave command). The countdown stops below the minimum; the round may end. */
    public void leave(UUID player) {
        boolean was = players.remove(player) | spectators.remove(player);
        if (!was) {
            return;
        }
        Team t = teamOf(player);
        if (t != null) {
            t.remove(player);
        }
        out.remove(player);
        lives.remove(player);
        listener.left(player);
        if (phase == Phase.STARTING && players.size() < arena.minPlayers()) {
            cancelTimer();
            phase(Phase.WAITING);
            listener.countdownCancelled();
        } else if (phase == Phase.RUNNING) {
            checkLastStanding();
        }
    }

    /** Start the countdown now, whatever the player count (an admin's force start). */
    public void startCountdown() {
        if (phase != Phase.WAITING) {
            return;
        }
        phase(Phase.STARTING);
        cancelTimer();
        if (countdownSeconds <= 0) {
            start();
            return;
        }
        timer = scheduler.countdown(countdownSeconds, listener::countdown, this::start);
    }

    /** Start the round now: everyone to a spawn, lives set, the clock running. */
    public void start() {
        if (phase == Phase.RUNNING || phase == Phase.ENDING) {
            return;
        }
        cancelTimer();
        phase(Phase.RUNNING);
        for (UUID p : players) {
            lives.put(p, maxLives);
            scores.putIfAbsent(p, 0L);
            listener.teleport(p, nextSpawn(p));
        }
        listener.started();
        secondsLeft = timeLimit > 0 ? timeLimit : -1;
        timer = scheduler.repeat(20, 20, this::second);
    }

    private void second() {
        if (phase != Phase.RUNNING) {
            return;
        }
        if (secondsLeft > 0) {
            secondsLeft--;
        }
        listener.second(secondsLeft);
        if (secondsLeft == 0) {
            end(best(), Reason.TIME);
        }
    }

    private Vec3 nextSpawn(UUID player) {
        Team t = teamOf(player);
        List<Vec3> spawns = arena.spawnsFor(t == null ? null : t.name());
        return spawns.get(spawnCursor++ % spawns.size());
    }

    /**
     * A player died or fell out. With lives left they respawn at a spawn; without, they become a spectator, and the
     * round ends when one team (or one player) is left. {@code by} gets a point when given.
     */
    public void eliminate(UUID player, UUID by) {
        if (phase != Phase.RUNNING || !players.contains(player) || out.contains(player)) {
            return;
        }
        if (by != null && !by.equals(player) && players.contains(by)) {
            addScore(by, 1);
            if (phase != Phase.RUNNING) {
                return;
            }
        }
        int left = lives.merge(player, -1, Integer::sum);
        if (left > 0) {
            listener.teleport(player, nextSpawn(player));
            listener.respawned(player, left);
            return;
        }
        out.add(player);
        listener.teleport(player, arena.spectate());
        listener.eliminated(player, by);
        checkLastStanding();
    }

    /** Add points to a player (and their team); reaching the score to win ends the round. */
    public void addScore(UUID player, long points) {
        if (phase != Phase.RUNNING || !players.contains(player)) {
            return;
        }
        long total = scores.merge(player, points, Long::sum);
        Team t = teamOf(player);
        if (t != null) {
            t.addScore(points);
        }
        listener.scored(player, total);
        if (scoreToWin > 0) {
            if (t != null && t.score() >= scoreToWin) {
                end(new Winner(t.name(), List.copyOf(t.members())), Reason.SCORE);
            } else if (t == null && total >= scoreToWin) {
                end(new Winner(null, List.of(player)), Reason.SCORE);
            }
        }
    }

    private record Winner(String team, List<UUID> players) {
    }

    private void checkLastStanding() {
        if (phase != Phase.RUNNING) {
            return;
        }
        Set<UUID> alive = alive();
        if (!teams.isEmpty()) {
            List<Team> standing = new ArrayList<>();
            for (Team t : teams.values()) {
                if (t.members().stream().anyMatch(alive::contains)) {
                    standing.add(t);
                }
            }
            if (standing.size() <= 1 && players.size() > 0) {
                Team w = standing.isEmpty() ? null : standing.getFirst();
                end(new Winner(w == null ? null : w.name(), w == null ? List.of() : List.copyOf(w.members())),
                        Reason.LAST_STANDING);
            }
        } else if (alive.size() <= 1 && arena.minPlayers() > 1 || alive.isEmpty()) {
            end(new Winner(null, List.copyOf(alive)), Reason.LAST_STANDING);
        }
    }

    /** The best when time runs out: the top team by score, or the top players; a tie is a draw. */
    private Winner best() {
        if (!teams.isEmpty()) {
            List<Team> sorted = new ArrayList<>(teams.values());
            sorted.sort(Comparator.comparingLong(Team::score).reversed());
            if (sorted.size() > 1 && sorted.get(0).score() == sorted.get(1).score()) {
                return new Winner(null, List.of());
            }
            return new Winner(sorted.getFirst().name(), List.copyOf(sorted.getFirst().members()));
        }
        long top = players.stream().mapToLong(this::score).max().orElse(0);
        List<UUID> best = players.stream().filter(p -> score(p) == top).toList();
        return new Winner(null, best);
    }

    /** End the round now (an admin's stop): no winner. */
    public void stop() {
        if (phase == Phase.RUNNING || phase == Phase.STARTING) {
            end(new Winner(null, List.of()), Reason.STOPPED);
        }
    }

    private void end(Winner w, Reason reason) {
        cancelTimer();
        phase(Phase.ENDING);
        Map<UUID, Long> all = new LinkedHashMap<>();
        for (UUID p : players) {
            all.put(p, score(p));
        }
        listener.ended(new Result(w.team(), w.players(), all, reason));
        if (endSeconds <= 0) {
            reset();
        } else {
            timer = scheduler.later(endSeconds * 20L, this::reset);
        }
    }

    /** Back to the lobby for the next round: lives, scores and eliminations cleared, teams kept. */
    public void reset() {
        cancelTimer();
        out.clear();
        lives.clear();
        scores.clear();
        teams.values().forEach(t -> t.addScore(-t.score()));
        for (UUID s : spectators) {
            listener.teleport(s, arena.lobby());
        }
        List<UUID> watchers = new ArrayList<>(spectators);
        spectators.clear();
        for (UUID p : players) {
            listener.teleport(p, arena.lobby());
        }
        phase(Phase.WAITING);
        listener.reset();
        // Spectators queue for the next round.
        for (UUID w : watchers) {
            join(w);
        }
        if (players.size() >= arena.minPlayers()) {
            startCountdown();
        }
    }

    /** Run {@code action} for every player and spectator, such as a message. */
    public void forEach(Consumer<UUID> action) {
        players.forEach(action);
        spectators.forEach(action);
    }

    private void phase(Phase next) {
        phase = next;
        listener.phase(next);
    }

    private void cancelTimer() {
        if (timer != null) {
            timer.cancel();
            timer = null;
        }
    }

    public static final class Builder {
        private final Arena arena;
        private final Scheduler scheduler;
        private final List<Team> teams = new ArrayList<>();
        private int countdown = 10;
        private int lives = 1;
        private long scoreToWin;
        private int timeLimit;
        private int endSeconds = 5;
        private boolean spectators = true;
        private Listener listener = new Listener() {
        };

        private Builder(Arena arena, Scheduler scheduler) {
            this.arena = arena;
            this.scheduler = scheduler;
        }

        /** Teams to fill evenly; none for a free-for-all. */
        public Builder teams(Team... teams) {
            this.teams.addAll(List.of(teams));
            return this;
        }

        /** Seconds from enough players to the start; 0 starts at once. Default 10. */
        public Builder countdown(int seconds) {
            this.countdown = seconds;
            return this;
        }

        /** Lives each player has; default 1 (out on the first death). */
        public Builder lives(int lives) {
            if (lives < 1) {
                throw new IllegalArgumentException("lives must be at least 1");
            }
            this.lives = lives;
            return this;
        }

        /** Points that win the round (team points with teams); 0 for none. */
        public Builder scoreToWin(long points) {
            this.scoreToWin = points;
            return this;
        }

        /** Seconds a round lasts before the best wins; 0 for no limit. */
        public Builder timeLimit(int seconds) {
            this.timeLimit = seconds;
            return this;
        }

        /** Seconds between the end and the reset; default 5. */
        public Builder endPause(int seconds) {
            this.endSeconds = seconds;
            return this;
        }

        /** Whether players joining a running or full game watch it; default true. */
        public Builder spectators(boolean allowed) {
            this.spectators = allowed;
            return this;
        }

        public Builder listener(Listener listener) {
            this.listener = listener;
            return this;
        }

        public Game build() {
            return new Game(this);
        }
    }
}
