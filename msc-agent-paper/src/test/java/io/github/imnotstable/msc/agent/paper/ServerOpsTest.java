package io.github.imnotstable.msc.agent.paper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.imnotstable.msc.agent.common.MainThread;
import io.github.imnotstable.msc.agent.common.ServerOps;
import io.github.imnotstable.msc.protocol.op.Ops;
import net.kyori.adventure.text.Component;
import org.bukkit.command.Command;
import org.bukkit.command.CommandException;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.command.ConsoleCommandSenderMock;

class ServerOpsTest {
    private static final ServerFacts FACTS = new ServerFacts() {
        @Override
        public String platformVersion() {
            return "26.3-test";
        }

        @Override
        public String minecraftVersion() {
            return "26.3";
        }

        @Override
        public double tps() {
            return 20.0;
        }
    };

    private ServerMock server;
    private ServerOps ops;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        server.addSimpleWorld("world");
        // MockBukkit lacks Server#createCommandSender, so use a console sender that forwards what it receives.
        ops = new ServerOps(new PaperServer(server, FACTS, feedback -> new ConsoleCommandSenderMock() {
            @Override
            public void sendMessage(Component message) {
                feedback.accept(message);
            }
        }), MainThread.inline());
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void infoListsWorldsAndPlayers() {
        server.addPlayer("Steve");

        Ops.ServerInfo info = ops.info();

        assertThat(info.platform()).isEqualTo("paper");
        assertThat(info.platformVersion()).isEqualTo("26.3-test");
        assertThat(info.worlds()).contains("world");
        assertThat(info.onlinePlayers()).isEqualTo(1);
        assertThat(info.tps()).isEqualTo(20.0);
    }

    @Test
    void commandThatThrowsFailsWithItsCause() {
        server.getCommandMap().register("test", new Command("broken") {
            @Override
            public boolean execute(CommandSender sender, String label, String[] args) {
                // CraftServer wraps what a command throws, a syntax error included, like this.
                throw new CommandException("Unhandled exception executing", new IllegalArgumentException("Can't find element 'x'"));
            }
        });

        Ops.CommandResult result = ops.command(new Ops.CommandParams("broken"));

        assertThat(result.success()).isFalse();
        assertThat(result.output()).containsExactly("Can't find element 'x'");
    }

    @Test
    void commandCapturesFeedback() {
        server.getCommandMap().register("test", new Command("echo") {
            @Override
            public boolean execute(CommandSender sender, String label, String[] args) {
                sender.sendMessage(Component.text("said " + String.join(" ", args)));
                return true;
            }
        });

        Ops.CommandResult result = ops.command(new Ops.CommandParams("/echo hi there"));

        assertThat(result.success()).isTrue();
        assertThat(result.output()).containsExactly("said hi there");
    }

    @Test
    void rejectsAnEmptyCommand() {
        assertThatThrownBy(() -> ops.command(new Ops.CommandParams(" / ")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void packIsSentToOnlinePlayersAndRejectsABadHash() {
        server.addPlayer("Steve");
        server.addPlayer("Alex");

        Ops.PackResult result = ops.applyPack(new Ops.PackParams("http://127.0.0.1:8000/pack.zip",
                "0123456789abcdef0123456789abcdef01234567", true));

        assertThat(result.playersSent()).isEqualTo(2);
        assertThat(ops.currentPack()).hasValueSatisfying(pack -> assertThat(PaperServer.request(pack).replace()).isTrue());
        assertThatThrownBy(() -> ops.applyPack(new Ops.PackParams("http://x/p.zip", "nope", false)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
