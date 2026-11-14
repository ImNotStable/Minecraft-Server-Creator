package {{packageName}};

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minestom.server.command.builder.CommandResult;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.GameMode;
import net.minestom.server.entity.Player;
import net.minestom.server.event.player.PlayerMoveEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.Weather;
import net.minestom.server.item.Material;
import net.minestom.server.network.player.GameProfile;
import net.minestom.testing.Env;
import net.minestom.testing.EnvTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Game tests in Minestom's test environment: a real server process without a network.
 * The pattern for your own game tests: build the world and game, connect a player, act, check.
 */
@EnvTest
class GameTest {
    @TempDir
    Path dir;

    private final AtomicInteger saves = new AtomicInteger();

    private Game game(Env env) {
        Instance world = {{name}}Server.createWorld(env.process().instance());
        GameConfig config = TestGames.twoStages();
        // Load the chunks every stage spawn is in, so teleports land at once.
        for (Stage s : config.stages()) {
            world.loadChunk(s.spawn()).join();
        }
        // The test thread stands in for the tick thread: it fires events and runs commands. Anything else throws.
        Thread test = Thread.currentThread();
        Game game = new Game(config, world, new Progress(dir.resolve("progress.properties")), saves::incrementAndGet)
                .confineTo(t -> t == test || Game.isTickThread(t));
        game.applyWorldRules();
        GameEvents.register(env.process().eventHandler(), game);
        return game;
    }

    private static Player join(Env env, Game game, String name) {
        return env.createConnection(new GameProfile(UUID.randomUUID(), name)).connect(game.world(), new Pos(0, -60, 0));
    }

    @Test
    void joiningGivesTheKitAndTheFirstStage(Env env) {
        Game game = game(env);

        Player player = join(env, game, "Steve");

        assertThat(player.getGameMode()).isEqualTo(GameMode.ADVENTURE);
        assertThat(player.getInventory().getItemStack(0).material()).isEqualTo(Material.WOODEN_SWORD);
        assertThat(player.getInventory().getItemStack(1).amount()).isEqualTo(8);
        assertThat(game.stage(player).name()).isEqualTo("start");
        assertThat(player.getRespawnPoint()).isEqualTo(new Pos(0.5, -60, 0.5));
    }

    @Test
    void steppingIntoTheGoalAdvances(Env env) {
        Game game = game(env);
        Player player = join(env, game, "Steve");

        env.process().eventHandler().call(new PlayerMoveEvent(player, new Pos(0.5, -60, 11.5), true));

        assertThat(game.stage(player).name()).isEqualTo("arena");
        assertThat(player.getPosition().samePoint(new Pos(0.5, -60, 40.5))).isTrue();
        assertThat(player.getRespawnPoint()).isEqualTo(new Pos(0.5, -60, 40.5));
        assertThat(saves).hasValue(1);
    }

    @Test
    void worldRulesFreezeTimeAndClearWeather(Env env) {
        Game game = game(env);

        env.tick();

        assertThat(game.world().getTime()).isEqualTo(6000);
        assertThat(game.world().getWeather()).isEqualTo(Weather.CLEAR);
    }

    @Test
    void gameCommandJumpsAndReports(Env env) {
        Game game = game(env);
        Player player = join(env, game, "Steve");
        GameCommand command = new GameCommand(game, name -> name.equalsIgnoreCase("Steve") ? player : null);

        List<GameCommand.Reply> moved = command.handle(null, new String[] {"stage", "arena", "Steve"});
        List<GameCommand.Reply> status = command.handle(null, new String[] {"status"});
        List<GameCommand.Reply> missing = command.handle(null, new String[] {"next"});

        assertThat(moved).containsExactly(new GameCommand.Reply(true, "Steve is on stage 2 arena"));
        assertThat(status.get(0).text()).startsWith("game: 2 stages");
        assertThat(status.get(1).text()).startsWith("Steve: stage 2/2 arena, at 0 -60 40, adventure");
        assertThat(missing).containsExactly(new GameCommand.Reply(false, "name a player: next <player>"));
    }

    @Test
    void gameCommandNeedsTheExactPlayerName(Env env) {
        Game game = game(env);
        Player steve = join(env, game, "Steve");
        GameCommand command = new GameCommand(game, {{name}}Server.playerLookup(env.process().connection()));

        List<GameCommand.Reply> typo = command.handle(null, new String[] {"stage", "arena", "Stev"});
        List<GameCommand.Reply> exact = command.handle(null, new String[] {"stage", "arena", "steve"});

        assertThat(typo).containsExactly(new GameCommand.Reply(false, "no online player Stev"));
        assertThat(exact).containsExactly(new GameCommand.Reply(true, "Steve is on stage 2 arena"));
        assertThat(game.stage(steve).name()).isEqualTo("arena");
    }

    @Test
    void gameCommandRunsFromTheConsole(Env env) {
        Game game = game(env);
        env.process().command().register(new GameCommand(game, name -> null));

        CommandResult result = env.process().command().executeServerCommand("game status");

        assertThat(result.getType()).isEqualTo(CommandResult.Type.SUCCESS);
    }
}
