package io.github.imnotstable.msc.agent.minestom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.imnotstable.msc.agent.common.MainThread;
import io.github.imnotstable.msc.agent.common.ServerOps;
import io.github.imnotstable.msc.protocol.op.Ops;
import java.time.Duration;
import net.minestom.server.command.builder.Command;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.command.builder.condition.Conditions;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.instance.Instance;
import net.minestom.server.network.packet.server.common.ResourcePackPushPacket;
import net.minestom.testing.Env;
import net.minestom.testing.EnvTest;
import org.junit.jupiter.api.Test;

@EnvTest
class ServerOpsTest {
    private static final ServerFacts FACTS = new ServerFacts() {
        @Override
        public String platformVersion() {
            return "2026.10.07-26.2";
        }

        @Override
        public String minecraftVersion() {
            return "26.2";
        }

        @Override
        public double tps() {
            return 20.0;
        }
    };

    private static ServerOps ops(Env env) {
        return new ServerOps(new MinestomServer(env.process().command(),
                new Worlds(() -> env.process().instance().getInstances(), Duration.ofSeconds(10)),
                () -> env.process().connection().getOnlinePlayers(), FACTS), MainThread.inline());
    }

    @Test
    void infoListsNamedWorldsFirst(Env env) {
        Instance unnamed = env.createEmptyInstance();
        MscAgent.name(env.createEmptyInstance(), "world");

        Ops.ServerInfo info = ops(env).info();

        assertThat(info.platform()).isEqualTo("minestom");
        assertThat(info.minecraftVersion()).isEqualTo("26.2");
        assertThat(info.worlds()).containsExactly("world", unnamed.getUuid().toString());
        assertThat(info.onlinePlayers()).isZero();
    }

    @Test
    void commandCapturesTheMessagesItSends(Env env) {
        Command echo = new Command("echo");
        var word = ArgumentType.Word("word");
        echo.addSyntax((sender, context) -> sender.sendMessage("echo: " + context.get(word)), word);
        env.process().command().register(echo);

        Ops.CommandResult result = ops(env).command(new Ops.CommandParams("/echo hello"));

        assertThat(result.success()).isTrue();
        assertThat(result.output()).containsExactly("echo: hello");
    }

    @Test
    void consoleOnlyCommandsRun(Env env) {
        Command stop = new Command("halt");
        stop.setCondition(Conditions::consoleOnly);
        stop.setDefaultExecutor((sender, context) -> sender.sendMessage("halting"));
        env.process().command().register(stop);

        Ops.CommandResult result = ops(env).command(new Ops.CommandParams("halt"));

        assertThat(result.success()).isTrue();
        assertThat(result.output()).containsExactly("halting");
    }

    @Test
    void cancelledCommandsFailWithAReason(Env env) {
        Command boom = new Command("boom");
        boom.setDefaultExecutor((sender, context) -> {
            throw new IllegalStateException("boom");
        });
        env.process().command().register(boom);

        Ops.CommandResult result = ops(env).command(new Ops.CommandParams("boom"));

        assertThat(result.success()).isFalse();
        assertThat(result.output()).singleElement().asString().startsWith("Command was cancelled");
    }

    @Test
    void unknownCommandsFailWithAReason(Env env) {
        ServerOps ops = ops(env);

        Ops.CommandResult result = ops.command(new Ops.CommandParams("time set day"));

        assertThat(result.success()).isFalse();
        assertThat(result.output()).containsExactly("Unknown command: time");
        assertThatThrownBy(() -> ops.command(new Ops.CommandParams("  /  ")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void packGoesToOnlinePlayers(Env env) {
        Instance world = env.createFlatInstance();
        ServerOps ops = ops(env);
        var connection = env.createConnection();
        var pushes = connection.trackIncoming(ResourcePackPushPacket.class);
        connection.connect(world, new Pos(0, 42, 0));

        assertThatThrownBy(() -> ops.applyPack(new Ops.PackParams("http://127.0.0.1/p.zip", "ABC", false)))
                .isInstanceOf(IllegalArgumentException.class);
        Ops.PackResult result = ops.applyPack(
                new Ops.PackParams("http://127.0.0.1:8123/pack.zip", "0123456789abcdef0123456789abcdef01234567", true));

        assertThat(result.playersSent()).isEqualTo(1);
        pushes.assertSingle(p -> assertThat(p.url()).isEqualTo("http://127.0.0.1:8123/pack.zip"));
    }
}
