package {{packageName}};

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.minestom.server.command.CommandManager;
import net.minestom.server.command.ConsoleSender;
import net.minestom.server.command.builder.CommandResult;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.GameMode;
import net.minestom.server.entity.Player;
import net.minestom.server.event.player.PlayerMoveEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.network.player.GameProfile;
import net.minestom.server.world.Difficulty;
import net.minestom.testing.Env;
import net.minestom.testing.EnvTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Game state lives on the tick thread. Here the test thread is the tick thread (it fires events and calls
 * {@code env.tick()}), and a thread named "console" types commands the way {@link Console} does.
 */
@EnvTest
class GameThreadsTest {
    private static final Stage START = new Stage("start", new Pos(0.5, -60, 0.5), Box.of(-2, -61, 10, 2, -58, 12));
    private static final Stage ARENA = new Stage("arena", new Pos(0.5, -60, 40.5), Box.of(-2, -61, 50, 2, -58, 52));
    private static final Stage END = new Stage("end", new Pos(0.5, -60, 80.5));
    private static final Duration GENEROUS = Duration.ofMinutes(1);

    @TempDir
    Path dir;

    /** The console, keeping what commands reply and on which thread. */
    static final class Replies extends ConsoleSender {
        final List<String> lines = Collections.synchronizedList(new ArrayList<>());
        final List<String> threads = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void sendMessage(Component message) {
            lines.add(PlainTextComponentSerializer.plainText().serialize(message));
            threads.add(Thread.currentThread().getName());
        }
    }

    private Game game(Env env, Runnable onProgressChange) {
        Instance world = {{name}}Server.createWorld(env.process().instance());
        GameConfig config = new GameConfig(GameMode.ADVENTURE, Difficulty.NORMAL, 6000, Kit.of(),
                List.of(START, ARENA, END), Set.of());
        for (Stage s : config.stages()) {
            world.loadChunk(s.spawn()).join();
        }
        Thread tick = Thread.currentThread();
        Game game = new Game(config, world, new Progress(dir.resolve("progress.properties")), onProgressChange)
                .confineTo(t -> t == tick || Game.isTickThread(t));
        GameEvents.register(env.process().eventHandler(), game);
        env.process().command().register(new GameCommand(game, {{name}}Server.playerLookup(env.process().connection())));
        return game;
    }

    /** Runs {@code command} from a thread named "console", as {@link Console} does, and returns its result. */
    private static CompletableFuture<CommandResult> typeOnConsole(Env env, Replies console, String command)
            throws InterruptedException {
        CommandManager commands = env.process().command();
        AtomicReference<CompletableFuture<CommandResult>> result = new AtomicReference<>();
        Thread thread = Thread.ofPlatform().name("console").start(
                () -> result.set(Console.dispatch(commands, console, command, env.process().scheduler())));
        assertThat(thread.join(GENEROUS)).as("console thread finished").isTrue();
        return result.get();
    }

    @Test
    void aConsoleCommandTypedDuringAGoalAdvanceRunsAfterItSoNoStageIsSkipped(Env env) throws InterruptedException {
        Replies console = new Replies();
        AtomicReference<CompletableFuture<CommandResult>> next = new AtomicReference<>();
        AtomicReference<InterruptedException> interrupted = new AtomicReference<>();
        AtomicBoolean first = new AtomicBoolean(true);
        // The goal advance has saved "arena" and not yet teleported when the console types "game next Steve".
        // Run on the console thread right then, the command would read "arena", save "end" and teleport there,
        // and the advance would then put Steve back in the arena: saved past a stage he never played.
        Game game = game(env, () -> {
            if (first.getAndSet(false)) {
                try {
                    next.set(typeOnConsole(env, console, "game next Steve"));
                } catch (InterruptedException e) {
                    interrupted.set(e);
                }
            }
        });
        Player steve = env.createConnection(new GameProfile(UUID.randomUUID(), "Steve"))
                .connect(game.world(), START.spawn());

        env.process().eventHandler().call(new PlayerMoveEvent(steve, new Pos(0.5, -60, 11.5), true));

        assertThat(interrupted.get()).isNull();
        assertThat(game.stage(steve)).as("saved stage after the goal").isEqualTo(ARENA);
        assertThat(steve.getPosition().samePoint(ARENA.spawn())).isTrue();
        assertThat(next.get()).as("console command waits for the tick").isNotDone();

        env.tick();

        assertThat(next.get()).isCompletedWithValueMatching(r -> r.getType() == CommandResult.Type.SUCCESS);
        assertThat(game.stage(steve)).isEqualTo(END);
        assertThat(steve.getPosition().samePoint(END.spawn())).as("standing where the saved stage starts").isTrue();
        assertThat(steve.getRespawnPoint()).isEqualTo(END.spawn());
        assertThat(console.lines).containsExactly("Steve is on stage 3 end");
        assertThat(console.threads).containsOnly(Thread.currentThread().getName());
    }

    @Test
    void changingGameStateOffTheTickThreadThrows(Env env) throws InterruptedException {
        Game game = game(env, () -> { });
        Player steve = env.createConnection(new GameProfile(UUID.randomUUID(), "Steve"))
                .connect(game.world(), START.spawn());
        AtomicReference<Throwable> thrown = new AtomicReference<>();

        Thread other = Thread.ofPlatform().name("not-the-tick").start(() -> {
            try {
                game.moveTo(steve, 1, "test");
            } catch (RuntimeException e) {
                thrown.set(e);
            }
        });
        assertThat(other.join(GENEROUS)).isTrue();

        assertThat(thrown.get()).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not-the-tick").hasMessageContaining("hop to the tick thread");
        assertThat(game.stage(steve)).isEqualTo(START);
    }
}
