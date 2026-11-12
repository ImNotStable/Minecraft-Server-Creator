package bench.sprint;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Player;
import net.minestom.server.event.player.PlayerMoveEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.server.network.packet.server.play.SystemChatPacket;
import net.minestom.server.network.player.GameProfile;
import net.minestom.testing.Collector;
import net.minestom.testing.Env;
import net.minestom.testing.EnvTest;
import net.minestom.testing.TestConnection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The bench's own checks of the Sky Sprint rules (msc-bench task game-sky-sprint). The agent never sees this file. */
@EnvTest
class BenchSprintTest {
    private static final Pattern SECONDS = Pattern.compile("(\\d+\\.\\d)s");

    @TempDir
    Path dir;

    private Game game;
    private Player steve;
    private Collector<SystemChatPacket> chat;

    private void start(Env env) {
        Instance world = SprintServer.createWorld(env.process().instance());
        for (int cx = -1; cx <= 0; cx++) {
            for (int cz = -1; cz <= 3; cz++) {
                world.loadChunk(cx, cz).join();
            }
        }
        game = new Game(GameConfig.game(), world, new Progress(dir.resolve("progress.properties")), () -> { });
        game.applyWorldRules();
        GameEvents.register(env.process().eventHandler(), game);
        TestConnection connection = env.createConnection(new GameProfile(UUID.randomUUID(), "Steve"));
        chat = connection.trackIncoming(SystemChatPacket.class);
        steve = connection.connect(world, new Pos(0.5, -60, 0.5));
        env.tick();
    }

    /**
     * Moves Steve the way a client move does: the move event first, then, unless the game teleported him, his
     * position, so rules read from either one see it. Then one tick passes.
     */
    private void moveTo(Env env, double x, double y, double z) {
        Pos to = new Pos(x, y, z);
        Pos before = steve.getPosition();
        env.process().eventHandler().call(new PlayerMoveEvent(steve, to, true));
        if (steve.getPosition().samePoint(before)) {
            steve.teleport(to).join();
        }
        env.tick();
    }

    private void ticks(Env env, int n) {
        for (int i = 0; i < n; i++) {
            env.tick();
        }
    }

    private List<String> command(String... args) {
        GameCommand command = new GameCommand(game, name -> name.equalsIgnoreCase("Steve") ? steve : null);
        return command.handle(null, args).stream().map(GameCommand.Reply::text).toList();
    }

    private List<String> messages() {
        return chat.collect().stream()
                .map(p -> PlainTextComponentSerializer.plainText().serialize(p.message()))
                .toList();
    }

    private static double seconds(String text) {
        Matcher m = SECONDS.matcher(text);
        assertThat(m.find()).as("a time like 3.0s in: " + text).isTrue();
        return Double.parseDouble(m.group(1));
    }

    @Test
    void courseHasTheThreeStagesAndItsBlocks(Env env) {
        start(env);
        Instance world = game.world();

        assertThat(GameConfig.game().stages()).extracting(Stage::name).containsExactly("lobby", "run", "done");
        assertThat(GameConfig.game().stages()).extracting(Stage::spawn)
                .containsExactly(new Pos(0.5, -60, 0.5), new Pos(0.5, -60, 10.5), new Pos(0.5, -60, 40.5));
        for (int z : new int[] {4, 5}) {
            assertThat(world.getBlock(0, -61, z).compare(Block.GOLD_BLOCK)).as("gold at z " + z).isTrue();
        }
        for (int z : new int[] {15, 20, 25}) {
            assertThat(world.getBlock(0, -61, z).compare(Block.EMERALD_BLOCK)).as("emerald at z " + z).isTrue();
        }
        assertThat(command("best", "Steve")).containsExactly("Steve has no time yet");
        assertThat(command("top")).containsExactly("no times yet");
    }

    @Test
    void aRunWithTwoCoinsIsTimedInTicks(Env env) {
        start(env);

        moveTo(env, 0.5, -60, 4.5);
        assertThat(game.stage(steve).name()).isEqualTo("run");
        ticks(env, 40);
        moveTo(env, 0.5, -60, 15.5);
        moveTo(env, 0.5, -60, 15.5);
        moveTo(env, 0.5, -60, 20.5);
        assertThat(command("coins", "Steve")).containsExactly("Steve has 2 coins");
        ticks(env, 60);
        moveTo(env, 0.5, -60, 30.5);

        assertThat(game.stage(steve).name()).isEqualTo("done");
        List<String> said = messages();
        assertThat(said).contains("Coin! (1/3)", "Coin! (2/3)").doesNotContain("Coin! (3/3)");
        String finished = said.stream().filter(m -> m.startsWith("Finished in ")).findFirst().orElse("");
        assertThat(finished).matches("Finished in \\d+\\.\\ds with 2 coins");
        // About 104 ticks of running (5.2s) minus 2 coins: allow a few ticks either way for where the count starts.
        assertThat(seconds(finished)).isBetween(2.8, 3.6);
        List<String> best = command("best", "Steve");
        assertThat(best).hasSize(1);
        assertThat(best.getFirst()).matches("Steve best: \\d+\\.\\ds");
        assertThat(seconds(best.getFirst())).isEqualTo(seconds(finished));
        assertThat(command("top")).containsExactly("1. Steve " + best.getFirst().substring("Steve best: ".length()));
    }

    @Test
    void theBestTimeIsTheLowestAndAFreshRunHasNoCoins(Env env) {
        start(env);
        moveTo(env, 0.5, -60, 4.5);
        moveTo(env, 0.5, -60, 15.5);
        moveTo(env, 0.5, -60, 20.5);
        moveTo(env, 0.5, -60, 25.5);
        moveTo(env, 0.5, -60, 30.5);
        double fast = seconds(command("best", "Steve").getFirst());

        command("stage", "run", "Steve");
        env.tick();
        assertThat(command("coins", "Steve")).containsExactly("Steve has 0 coins");
        ticks(env, 100);
        moveTo(env, 0.5, -60, 30.5);

        assertThat(fast).isEqualTo(0.0);
        assertThat(seconds(command("best", "Steve").getFirst())).isEqualTo(0.0);
    }

    @Test
    void timeIsUpAfterSixtySeconds(Env env) {
        start(env);
        moveTo(env, 0.5, -60, 4.5);
        moveTo(env, 0.5, -60, 15.5);
        moveTo(env, 0.5, -60, 22.5);

        ticks(env, 1210);

        assertThat(messages()).contains("Time's up!");
        assertThat(game.stage(steve).name()).isEqualTo("run");
        assertThat(steve.getPosition().distance(new Pos(0.5, -60, 10.5))).isLessThan(1.0);
        assertThat(command("coins", "Steve")).containsExactly("Steve has 0 coins");
        assertThat(command("best", "Steve")).containsExactly("Steve has no time yet");
    }
}
