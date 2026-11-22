package io.github.imnotstable.msc.agent.minestom;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.minestom.server.command.CommandManager;
import net.minestom.server.command.ConsoleSender;
import net.minestom.server.command.builder.Command;
import net.minestom.server.command.builder.CommandResult;
import net.minestom.testing.Env;
import net.minestom.testing.EnvTest;
import org.junit.jupiter.api.Test;

/** Console commands run on the tick thread, not on the thread that reads standard input. */
@EnvTest
class ConsoleTest {
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

    @Test
    void aConsoleCommandRunsOnTheNextTickAndRepliesFromThere(Env env) throws InterruptedException {
        CommandManager commands = env.process().command();
        Command hello = new Command("hello");
        hello.setDefaultExecutor((sender, context) -> sender.sendMessage(Component.text("Hello, console!")));
        commands.register(hello);
        Replies console = new Replies();
        AtomicReference<CompletableFuture<CommandResult>> result = new AtomicReference<>();

        Thread reader = Thread.ofPlatform().name("console").start(
                () -> result.set(Console.dispatch(commands, console, "hello", env.process().scheduler())));
        assertThat(reader.join(Duration.ofMinutes(1))).isTrue();

        assertThat(result.get()).isNotDone();
        assertThat(console.lines).isEmpty();

        env.tick(); // the test thread runs the tick here

        assertThat(result.get()).isCompletedWithValueMatching(r -> r.getType() == CommandResult.Type.SUCCESS);
        assertThat(console.lines).containsExactly("Hello, console!");
        assertThat(console.threads).containsExactly(Thread.currentThread().getName());
    }
}
