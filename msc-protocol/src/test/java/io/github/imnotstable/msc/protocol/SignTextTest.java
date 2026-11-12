package io.github.imnotstable.msc.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.protocol.message.MessageCodec;
import io.github.imnotstable.msc.protocol.op.Ops;
import io.github.imnotstable.msc.protocol.world.BlockGrid;
import io.github.imnotstable.msc.protocol.world.BlockPos;
import io.github.imnotstable.msc.protocol.world.Region;
import java.util.List;
import org.junit.jupiter.api.Test;

class SignTextTest {
    @Test
    void recognisesEverySignKind() {
        assertThat(Ops.SignText.isSign("minecraft:oak_sign[rotation=4]")).isTrue();
        assertThat(Ops.SignText.isSign("minecraft:spruce_wall_sign")).isTrue();
        assertThat(Ops.SignText.isSign("minecraft:bamboo_wall_hanging_sign[facing=north]")).isTrue();
        assertThat(Ops.SignText.isSign("minecraft:stone")).isFalse();
        assertThat(Ops.SignText.isSign("minecraft:design[sign=x]")).isFalse();
        assertThat(Ops.SignText.isSign(null)).isFalse();
    }

    @Test
    void findsSignPositionsInAGrid() {
        Region r = Region.of("world", 10, 64, 20, 11, 65, 21);
        // Y-Z-X order: (10,64,20) (11,64,20) (10,64,21) (11,64,21) (10,65,20) ...
        int[] blocks = {0, 1, 0, 0, 0, 0, 0, 2};
        BlockGrid grid = new BlockGrid(r, List.of("minecraft:air", "minecraft:oak_sign", "minecraft:oak_wall_sign[facing=east]"), blocks);

        assertThat(Ops.SignText.positions(grid)).containsExactly(new BlockPos(11, 64, 20), new BlockPos(11, 65, 21));
        assertThat(Ops.SignText.positions(new BlockGrid(r, List.of("minecraft:air"), new int[8]))).isEmpty();
    }

    @Test
    void padsLinesAndDropsABlankBackOrABlankSign() {
        Ops.SignText s = Ops.SignText.of(1, 2, 3, List.of("Shop"), List.of("", " "));
        assertThat(s.front()).containsExactly("Shop", "", "", "");
        assertThat(s.back()).isNull();
        assertThat(Ops.SignText.of(1, 2, 3, List.of("", "", "", ""), null)).isNull();
        assertThat(Ops.SignText.of(1, 2, 3, null, List.of("back only")).front()).containsExactly("", "", "", "");
    }

    @Test
    void oldReadMessagesStillDecode() {
        Ops.ReadParams params = MessageCodec.gson().fromJson("{\"region\":{\"world\":\"w\",\"min\":{\"x\":0,\"y\":0,\"z\":0},"
                + "\"max\":{\"x\":1,\"y\":1,\"z\":1}},\"includeEntities\":true}", Ops.ReadParams.class);
        assertThat(params.includeSigns()).isFalse();
        assertThat(params.includeEntities()).isTrue();

        Ops.ReadResult result = new Ops.ReadResult(null, null, null);
        assertThat(result.signs()).isEmpty();
        assertThat(result.entities()).isEmpty();
    }
}
