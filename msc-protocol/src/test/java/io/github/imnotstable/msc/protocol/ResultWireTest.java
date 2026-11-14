package io.github.imnotstable.msc.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.imnotstable.msc.protocol.message.MessageCodec;
import io.github.imnotstable.msc.protocol.message.ProtocolError;
import io.github.imnotstable.msc.protocol.message.RawJson;
import io.github.imnotstable.msc.protocol.message.Response;
import io.github.imnotstable.msc.protocol.op.OpSpec;
import io.github.imnotstable.msc.protocol.op.Ops;
import io.github.imnotstable.msc.protocol.world.BlockGrid;
import io.github.imnotstable.msc.protocol.world.Region;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Results are written and read as JSON text (ADR 0030) on the same wire as before: these frames were recorded from
 * main before that change, when results went through a JsonElement tree. Same bytes both ways means an old agent
 * still talks to a new core and the reverse.
 */
class ResultWireTest {
    private static final String READ_FRAME = "{\"type\":\"response\",\"id\":7,\"result\":{\"grid\":{\"region\":{\"world\":"
            + "\"world\",\"min\":{\"x\":0,\"y\":64,\"z\":0},\"max\":{\"x\":1,\"y\":64,\"z\":1}},\"palette\":[\"minecraft:air\","
            + "\"minecraft:oak_sign[rotation=4]\"],\"blocks\":[0,1,0,0]},\"entities\":[{\"type\":\"minecraft:cow\",\"x\":0.5,"
            + "\"y\":64.0,\"z\":1.25}],\"signs\":[{\"x\":1,\"y\":64,\"z\":0,\"front\":[\"Shop \\\"<open>\\\"\",\"é ✓\","
            + "\"\",\"\"]}]}}";
    private static final String INFO_FRAME = "{\"type\":\"response\",\"id\":8,\"result\":{\"platform\":\"paper\","
            + "\"platformVersion\":\"1\",\"minecraftVersion\":\"26.3\",\"worlds\":[\"world\"],\"onlinePlayers\":0,\"tps\":20.0}}";
    private static final String HEIGHTMAP_FRAME = "{\"type\":\"response\",\"id\":9,\"result\":{\"minX\":-3,\"minZ\":4,"
            + "\"sizeX\":1,\"sizeZ\":2,\"heights\":[63,-64]}}";
    private static final String ERROR_FRAME = "{\"type\":\"response\",\"id\":10,\"error\":{\"code\":\"not_found\","
            + "\"message\":\"no world named x\"}}";

    private static Ops.ReadResult read() {
        Region r = Region.of("world", 0, 64, 0, 1, 64, 1);
        return new Ops.ReadResult(new BlockGrid(r, List.of("minecraft:air", "minecraft:oak_sign[rotation=4]"),
                new int[] {0, 1, 0, 0}),
                List.of(new Ops.EntitySummary("minecraft:cow", 0.5, 64.0, 1.25, null)),
                List.of(new Ops.SignText(1, 64, 0, List.of("Shop \"<open>\"", "é ✓"), null)));
    }

    private static <R> String frame(long id, OpSpec<?, R> op, R result) {
        return MessageCodec.encode(Response.ok(id, op.encodeResult(result)));
    }

    private static <R> R decode(OpSpec<?, R> op, String frame) throws ProtocolException {
        return op.decodeResult(((Response) MessageCodec.decode(frame)).result());
    }

    @Test
    void newEncoderWritesTheRecordedBytes() {
        assertThat(frame(7, Ops.WORLD_READ, read())).isEqualTo(READ_FRAME);
        assertThat(frame(8, Ops.SERVER_INFO, new Ops.ServerInfo("paper", "1", "26.3", List.of("world"), 0, 20.0)))
                .isEqualTo(INFO_FRAME);
        assertThat(frame(9, Ops.WORLD_HEIGHTMAP, new Ops.HeightmapResult(-3, 4, 1, 2, new int[] {63, -64})))
                .isEqualTo(HEIGHTMAP_FRAME);
        assertThat(MessageCodec.encode(Response.fail(10, "not_found", "no world named x"))).isEqualTo(ERROR_FRAME);
    }

    @Test
    void newDecoderReadsTheRecordedFrames() throws ProtocolException {
        Ops.ReadResult got = decode(Ops.WORLD_READ, READ_FRAME);
        assertThat(got.grid().blocks()).containsExactly(0, 1, 0, 0);
        assertThat(got.grid().palette()).isEqualTo(read().grid().palette());
        assertThat(got.entities()).isEqualTo(read().entities());
        assertThat(got.signs()).isEqualTo(read().signs());
        assertThat(decode(Ops.SERVER_INFO, INFO_FRAME).tps()).isEqualTo(20.0);
        assertThat(decode(Ops.WORLD_HEIGHTMAP, HEIGHTMAP_FRAME).heights()).containsExactly(63, -64);
        Response error = (Response) MessageCodec.decode(ERROR_FRAME);
        assertThat(error).isEqualTo(new Response(10, null, new ProtocolError("not_found", "no world named x")));
    }

    @Test
    void responseReadsInAnyFieldOrderAndSpacing() throws ProtocolException {
        Response expected = Response.ok(3, new RawJson("{\"a\":[1,\"}]\\\"\"]}"));
        assertThat(MessageCodec.decode("{\"type\":\"response\",\"id\":3,\"result\":{\"a\":[1,\"}]\\\"\"]}}"))
                .isEqualTo(expected);
        assertThat(MessageCodec.decode("{\"type\":\"response\" , \"result\" : {\"a\":[1,\"}]\\\"\"]} , \"id\":3 }"))
                .isEqualTo(expected);
        assertThat(MessageCodec.decode("{\"id\":3,\"type\":\"response\",\"result\":{\"a\": [1, \"}]\\\"\"]}}"))
                .isEqualTo(Response.ok(3, new RawJson("{\"a\":[1,\"}]\\\"\"]}")));
        assertThat(MessageCodec.decode("{\"type\":\"response\",\"id\":4,\"result\":7,\"extra\":true}"))
                .isEqualTo(Response.ok(4, new RawJson("7")));
        assertThat(MessageCodec.decode("{\"type\":\"response\",\"id\":5,\"result\":null}"))
                .isEqualTo(new Response(5, null, null));
    }

    @Test
    void truncatedResponseIsStillABadFrame() {
        for (String bad : List.of("{\"type\":\"response\",\"id\":1,\"result\":{\"a\":1}",
                "{\"type\":\"response\",\"id\":1,\"result\":}", "{\"type\":\"response\",\"id\":1,\"result\":{}} x")) {
            assertThatThrownBy(() -> MessageCodec.decode(bad))
                    .isInstanceOf(ProtocolException.class);
        }
    }
}
