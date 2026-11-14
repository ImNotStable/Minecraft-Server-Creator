package io.github.imnotstable.msc.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.gson.JsonParser;
import io.github.imnotstable.msc.protocol.message.ErrorCodes;
import io.github.imnotstable.msc.protocol.message.MessageCodec;
import io.github.imnotstable.msc.protocol.message.RawJson;
import io.github.imnotstable.msc.protocol.message.Request;
import io.github.imnotstable.msc.protocol.op.OpSpec;
import io.github.imnotstable.msc.protocol.op.Ops;
import io.github.imnotstable.msc.protocol.transport.OpException;
import io.github.imnotstable.msc.protocol.transport.OpRouter;
import io.github.imnotstable.msc.protocol.world.BlockGrid;
import io.github.imnotstable.msc.protocol.world.BlockPos;
import io.github.imnotstable.msc.protocol.world.Region;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * Request params are written and read as JSON text (ADR 0030) on the same wire as before: these frames were recorded
 * from main before that change, when params went through a JsonElement tree on both sides. Same bytes both ways means
 * an old agent still talks to a new core and the reverse.
 */
class ParamsWireTest {
    private static final Region SMALL = Region.of("world", 0, 64, 0, 1, 64, 1);
    private static final String REGION = "{\"world\":\"world\",\"min\":{\"x\":0,\"y\":64,\"z\":0},\"max\":{\"x\":1,\"y\":64,"
            + "\"z\":1}}";
    private static final List<Recorded<?>> RECORDED = List.of(
            new Recorded<>(1, Ops.WORLD_WRITE, new Ops.WriteParams(new BlockGrid(SMALL,
                    List.of("minecraft:air", "minecraft:oak_sign[rotation=4]"), new int[] {0, 1, 0, 0}), true),
                    "{\"grid\":{\"region\":" + REGION + ",\"palette\":[\"minecraft:air\",\"minecraft:oak_sign[rotation=4]\"],"
                            + "\"blocks\":[0,1,0,0]},\"updatePhysics\":true}"),
            new Recorded<>(2, Ops.SERVER_COMMAND, new Ops.CommandParams("say \"hi\" <b>é ✓\nthere"),
                    "{\"command\":\"say \\\"hi\\\" <b>é ✓\\nthere\"}"),
            new Recorded<>(3, Ops.SERVER_INFO, new Ops.Empty(), "{}"),
            new Recorded<>(4, Ops.WORLD_READ, new Ops.ReadParams(SMALL, true, false),
                    "{\"region\":" + REGION + ",\"includeEntities\":true,\"includeSigns\":false}"),
            new Recorded<>(5, Ops.WORLD_ENTITIES, new Ops.EntitiesParams(SMALL, null, 50),
                    "{\"region\":" + REGION + ",\"limit\":50}"),
            new Recorded<>(6, Ops.WORLD_HEIGHTMAP, new Ops.HeightmapParams("world", new BlockPos(-3, 0, 4),
                    new BlockPos(-3, 0, 5)), "{\"world\":\"world\",\"min\":{\"x\":-3,\"y\":0,\"z\":4},\"max\":{\"x\":-3,\"y\":0,"
                    + "\"z\":5}}"),
            new Recorded<>(7, Ops.WORLD_UNDO, new Ops.UndoParams("e-1"), "{\"editId\":\"e-1\"}"),
            new Recorded<>(8, Ops.PACK_APPLY, new Ops.PackParams("https://x/p.zip?a=1&b=2", "ab", false),
                    "{\"url\":\"https://x/p.zip?a=1&b=2\",\"sha1\":\"ab\",\"required\":false}"));

    private record Recorded<P>(long id, OpSpec<P, ?> op, P params, String paramsJson) {
        String frame() {
            return "{\"type\":\"request\",\"id\":" + id + ",\"op\":\"" + op.name() + "\",\"params\":" + paramsJson + "}";
        }

        String encode() {
            return MessageCodec.encode(new Request(id, op.name(), op.encodeParams(params)));
        }

        P decode(String frame) throws ProtocolException {
            Request request = (Request) MessageCodec.decode(frame);
            assertThat(request.id()).isEqualTo(id);
            assertThat(request.op()).isEqualTo(op.name());
            return op.decodeParams(request.params());
        }
    }

    @Test
    void everyOpWritesTheRecordedBytes() {
        assertThat(Ops.ALL).hasSize(RECORDED.size());
        for (Recorded<?> r : RECORDED) {
            assertThat(r.encode()).as(r.op().name()).isEqualTo(r.frame());
        }
    }

    @Test
    void everyOpReadsTheRecordedFrame() throws ProtocolException {
        for (Recorded<?> r : RECORDED) {
            Object got = r.decode(r.frame());
            if (got instanceof Ops.WriteParams write) {
                Ops.WriteParams want = (Ops.WriteParams) r.params();
                assertThat(write.grid().blocks()).containsExactly(want.grid().blocks());
                assertThat(write.grid().palette()).isEqualTo(want.grid().palette());
                assertThat(write.updatePhysics()).isTrue();
            } else {
                assertThat(got).as(r.op().name()).isEqualTo(r.params());
            }
        }
    }

    /** ADR 0042: the block data flag is in the frame only when on, so a read without it is the recorded bytes. */
    @Test
    void blockDataFlagIsSentOnlyWhenOn() throws ProtocolException {
        assertThat(new Ops.ReadParams(SMALL, true, false, false)).isEqualTo(new Ops.ReadParams(SMALL, true, false));
        Recorded<Ops.ReadParams> on = new Recorded<>(9, Ops.WORLD_READ, new Ops.ReadParams(SMALL, false, false, true),
                "{\"region\":" + REGION + ",\"includeEntities\":false,\"includeSigns\":false,\"includeBlockData\":true}");
        assertThat(on.encode()).isEqualTo(on.frame());
        assertThat(on.decode(on.frame()).blockData()).isTrue();
        assertThat(((Ops.ReadParams) RECORDED.get(3).decode(RECORDED.get(3).frame())).blockData()).isFalse();
    }

    @Test
    void requestReadsInAnyFieldOrderAndSpacing() throws ProtocolException {
        Request expected = new Request(3, "world.undo", new RawJson("{\"editId\":\"}]\\\"\"}"));
        assertThat(MessageCodec.decode("{\"type\":\"request\",\"id\":3,\"op\":\"world.undo\",\"params\":{\"editId\":\"}]\\\"\"}}"))
                .isEqualTo(expected);
        assertThat(MessageCodec.decode("{\"type\":\"request\" , \"params\" : {\"editId\":\"}]\\\"\"} , \"op\":\"world.undo\","
                + "\"id\":3 }")).isEqualTo(expected);
        assertThat(MessageCodec.decode("{\"id\":3,\"type\":\"request\",\"op\":\"world.undo\",\"params\":{\"editId\":\"}]\\\"\"}}"))
                .isEqualTo(expected);
        assertThat(MessageCodec.decode("{\"type\":\"request\",\"id\":4,\"op\":\"w\\u002ex\",\"params\":null,\"extra\":[1]}"))
                .isEqualTo(new Request(4, "w.x", new RawJson("{}")));
    }

    @Test
    void truncatedRequestIsStillABadFrame() {
        for (String bad : List.of("{\"type\":\"request\",\"id\":1,\"op\":\"x\",\"params\":{\"a\":1}",
                "{\"type\":\"request\",\"id\":1,\"params\":}", "{\"type\":\"request\",\"id\":1,\"params\":{}} x")) {
            assertThatThrownBy(() -> MessageCodec.decode(bad)).isInstanceOf(ProtocolException.class);
        }
    }

    @Test
    void malformedParamsFailOnlyThatRequestWithInvalidParams() throws ProtocolException {
        Request request = (Request) MessageCodec.decode(
                "{\"type\":\"request\",\"id\":1,\"op\":\"world.undo\",\"params\":{\"editId\":}}");
        OpRouter router = new OpRouter().on(Ops.WORLD_UNDO, p -> new Ops.EditResult(p.editId(), 0));
        for (RawJson params : List.of(request.params(), new RawJson("[1]"), new RawJson("7"))) {
            assertThatThrownBy(() -> router.handle("world.undo", params))
                    .isInstanceOfSatisfying(OpException.class, e -> assertThat(e.code()).isEqualTo(ErrorCodes.INVALID_PARAMS));
        }
    }

    @Test
    void intArraysStillGoIntoATreeWhenAskedFor() {
        assertThat(MessageCodec.gson().toJsonTree(new int[] {3, -1}).toString()).isEqualTo("[3,-1]");
        assertThat(MessageCodec.gson().toJson(new int[0])).isEqualTo("[]");
        assertThat(MessageCodec.gson().fromJson("[ 1 , 2 ]", int[].class)).containsExactly(1, 2);
        assertThat(MessageCodec.gson().fromJson("[]", int[].class)).isEmpty();
    }

    /**
     * A write's params reach the agent as the frame's own text, never as a tree: a tree would drop the spaces. And the
     * whole agent side (frame to record) allocates well under what a tree of the params alone takes, so a tree that
     * came back anywhere on that path (codec, request record or {@code decodeParams}) fails this.
     */
    @Test
    void aFullRegionWriteIsDecodedWithoutATree() throws ProtocolException {
        Region full = Region.of("world", 0, 0, 0, 127, 63, 127);
        assertThat(full.volume()).isEqualTo(Limits.MAX_REGION_VOLUME);
        int[] blocks = new int[Math.toIntExact(full.volume())];
        Arrays.setAll(blocks, i -> (i * 31) % 7);
        BlockGrid grid = new BlockGrid(full, List.of("minecraft:air", "minecraft:stone", "minecraft:dirt",
                "minecraft:grass_block", "minecraft:oak_log", "minecraft:oak_leaves", "minecraft:water"), blocks);
        String frame = MessageCodec.encode(new Request(1, "world.write",
                Ops.WORLD_WRITE.encodeParams(new Ops.WriteParams(grid, false))));

        String spaced = "{\"type\":\"request\",\"id\":2,\"op\":\"world.write\",\"params\":{ \"updatePhysics\" : true }}";
        assertThat(((Request) MessageCodec.decode(spaced)).params().json()).isEqualTo("{ \"updatePhysics\" : true }");

        String params = ((Request) MessageCodec.decode(frame)).params().json();
        long tree = allocated(() -> JsonParser.parseString(params));
        long direct = allocated(() -> {
            try {
                return Ops.WORLD_WRITE.decodeParams(((Request) MessageCodec.decode(frame)).params());
            } catch (ProtocolException e) {
                throw new AssertionError(e);
            }
        });
        assertThat(direct).as("bytes allocated decoding the write, against a tree of its params").isLessThan(tree / 2);
        long encode = allocated(() -> Ops.WORLD_WRITE.encodeParams(new Ops.WriteParams(grid, false)));
        long treeEncode = allocated(() -> MessageCodec.gson().toJsonTree(new Ops.WriteParams(grid, false)).toString());
        assertThat(encode).as("bytes allocated encoding the write, against a tree of its params").isLessThan(treeEncode / 2);
    }

    /** Bytes this thread allocates running {@code work}, the least of a few runs so JIT warm-up does not count. */
    private static long allocated(Supplier<?> work) {
        com.sun.management.ThreadMXBean bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long least = Long.MAX_VALUE;
        for (int run = 0; run < 3; run++) {
            long before = bean.getCurrentThreadAllocatedBytes();
            Object kept = work.get();
            least = Math.min(least, bean.getCurrentThreadAllocatedBytes() - before);
            assertThat(kept).isNotNull();
        }
        return least;
    }
}
