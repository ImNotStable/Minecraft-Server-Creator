package io.github.imnotstable.msc.stdlib.game;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * A queue over many games of one kind: a player joining goes to the game with the most players that still takes them
 * (so lobbies fill up and start), and a new game is made from {@code factory} when every one is full or running, up to
 * {@code maxGames}.
 *
 * <pre>{@code
 * Matchmaker queue = new Matchmaker(() -> Game.builder(arenas.next(), scheduler).listener(ui).build(), 4);
 * queue.join(player);              // in a /play command or a lobby NPC's click
 * queue.gameOf(player).ifPresent(g -> g.eliminate(player, killer));
 * }</pre>
 */
public final class Matchmaker {
    private final Supplier<Game> factory;
    private final int maxGames;
    private final List<Game> games = new ArrayList<>();

    public Matchmaker(Supplier<Game> factory, int maxGames) {
        if (maxGames < 1) {
            throw new IllegalArgumentException("maxGames must be at least 1");
        }
        this.factory = factory;
        this.maxGames = maxGames;
    }

    public List<Game> games() {
        return Collections.unmodifiableList(games);
    }

    public Optional<Game> gameOf(UUID player) {
        return games.stream().filter(g -> g.contains(player)).findFirst();
    }

    /** Put a player in a game as a player; false when every game is full or running and no more may be made. */
    public boolean join(UUID player) {
        if (gameOf(player).isPresent()) {
            return true;
        }
        Game best = null;
        for (Game g : games) {
            boolean open = (g.phase() == Game.Phase.WAITING || g.phase() == Game.Phase.STARTING) && !g.full();
            if (open && (best == null || g.players().size() > best.players().size())) {
                best = g;
            }
        }
        if (best == null && games.size() < maxGames) {
            best = factory.get();
            games.add(best);
        }
        return best != null && best.join(player);
    }

    /** Take a player out of whatever game they are in. */
    public void leave(UUID player) {
        gameOf(player).ifPresent(g -> g.leave(player));
    }

    /** Drop games with nobody in them that are waiting, keeping at least one. */
    public void prune() {
        games.removeIf(g -> games.size() > 1 && g.phase() == Game.Phase.WAITING && g.players().isEmpty()
                && g.spectators().isEmpty());
    }
}
