package io.github.imnotstable.msc.agent.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.imnotstable.msc.protocol.Limits;
import io.github.imnotstable.msc.protocol.message.MessageCodec;
import io.github.imnotstable.msc.protocol.message.Response;
import io.github.imnotstable.msc.protocol.op.Ops;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ServerOpsTest {
    private FakeServer server;
    private ServerOps ops;

    @BeforeEach
    void setUp() {
        server = new FakeServer();
        ops = new ServerOps(server, MainThread.inline());
    }

    @Test
    void infoListsWorldsAndPlayers() {
        server.players = 1;

        Ops.ServerInfo info = ops.info();

        assertThat(info.platform()).isEqualTo("test");
        assertThat(info.platformVersion()).isEqualTo("1.0-test");
        assertThat(info.minecraftVersion()).isEqualTo("26.3");
        assertThat(info.worlds()).containsExactly("world", "world_nether");
        assertThat(info.onlinePlayers()).isEqualTo(1);
        assertThat(info.tps()).isEqualTo(20.0);
    }

    @Test
    void commandStripsTheSlashAndCapturesFeedback() {
        Ops.CommandResult result = ops.command(new Ops.CommandParams("/echo hi there"));

        assertThat(result.success()).isTrue();
        assertThat(result.output()).containsExactly("said hi there");

        Ops.CommandResult unknown = ops.command(new Ops.CommandParams("nope"));
        assertThat(unknown.success()).isFalse();
        assertThat(unknown.output()).isNotEmpty();
    }

    @Test
    void hugeOutputIsCutWithAMarkerAndFitsInAFrame() {
        for (String command : List.of("flood 20000 1000", "flood 1 20000000", "flood 5000000 0")) {
            Ops.CommandResult result = ops.command(new Ops.CommandParams(command));

            long chars = result.output().stream().mapToLong(l -> l.length() + 1).sum();
            assertThat(chars).as(command).isLessThanOrEqualTo(Limits.MAX_RESULT_TEXT_CHARS);
            assertThat(result.output().getLast()).as(command).containsAnyOf(" more chars]", " not shown]");
            String frame = MessageCodec.encode(Response.ok(1, Ops.SERVER_COMMAND.encodeResult(result)));
            assertThat(frame.getBytes(StandardCharsets.UTF_8).length).as(command).isLessThan(Limits.MAX_FRAME_BYTES);
        }
        assertThat(ops.command(new Ops.CommandParams("flood 3 10")).output()).containsExactly(
                "ffffffffff", "ffffffffff", "ffffffffff");
    }

    @Test
    void rejectsAnEmptyCommand() {
        assertThatThrownBy(() -> ops.command(new Ops.CommandParams(" / ")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void packIsSentToOnlinePlayersKeptForJoinersAndRejectsABadHash() {
        server.players = 2;

        Ops.PackResult result = ops.applyPack(new Ops.PackParams("http://127.0.0.1:8000/pack.zip",
                "0123456789abcdef0123456789abcdef01234567", true));

        assertThat(result.playersSent()).isEqualTo(2);
        assertThat(server.packsSent).hasSize(1);
        assertThat(ops.currentPack()).contains(server.packsSent.getFirst());
        assertThat(server.packsSent.getFirst().required()).isTrue();
        assertThatThrownBy(() -> ops.applyPack(new Ops.PackParams("http://x/p.zip", "nope", false)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
