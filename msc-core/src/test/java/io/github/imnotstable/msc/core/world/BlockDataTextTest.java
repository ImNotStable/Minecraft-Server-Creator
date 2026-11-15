package io.github.imnotstable.msc.core.world;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.imnotstable.msc.core.tool.ToolException;
import io.github.imnotstable.msc.protocol.world.BlockGrid;
import io.github.imnotstable.msc.protocol.world.BlockPos;
import io.github.imnotstable.msc.protocol.world.Region;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Block entity data as text (ADR 0042): SNBT, block names, slices and schematics. */
class BlockDataTextTest {
    @TempDir
    Path dir;

    @Test
    void everyPlatformsSpellingReadsAsTheCoresOne() {
        String core = "{Items:[{Slot:0b,id:\"minecraft:diamond\",count:3}]}";
        // The mod loaders (CompoundTag.toString), Paper's /data get (with id and position), Minestom (TagStringIO).
        for (String agent : List.of(core, "{Items: [{Slot: 0B, id: \"minecraft:diamond\", count: 3}], id: "
                + "\"minecraft:chest\", x: 4, y: 64, z: -2, components: {}}", "{Items:[{Slot:0b,id:'minecraft:diamond',count:3}]}")) {
            assertThat(Snbt.blockData(agent)).as(agent).isEqualTo(core);
        }
        assertThat(Snbt.blockData("{Items:[],components:{}}")).as("an empty chest holds nothing").isNull();
    }

    @Test
    void everyNbtTypeRoundTrips() {
        String all = "{b:1b,s:2s,i:3,l:4L,f:1.5f,d:2.5d,t:\"a\\\"b\\\\c\",\"odd key\":[I;1,2],ba:[B;1b,-2b],la:[L;5L],"
                + "list:[{x:1},{y:2}],e:[]}";
        assertThat(Snbt.write(Snbt.parse(all))).isEqualTo(all);
        assertThat(Snbt.parse("{a:true,b:1e3,c:-1.5F,d:abc}")).isEqualTo(Map.of("a", (byte) 1, "b", 1000.0, "c", -1.5f,
                "d", "abc"));
    }

    @Test
    void badSnbtSaysWhere() {
        for (String bad : List.of("{Items:[}", "{a:1", "{a:1} x", "{a:\"open}", "{a:999999999999}", "[B;x]")) {
            assertThatThrownBy(() -> Snbt.parse(bad)).as(bad).isInstanceOf(ToolException.class)
                    .hasMessageStartingWith("bad SNBT at character");
        }
    }

    @Test
    void blockNamesSplitDataFromTheState() {
        assertThat(BlockNames.full("chest[facing=north]{ Lock: \"k\" , x: 1 }")).isEqualTo("minecraft:chest[facing=north]{Lock:\"k\"}");
        assertThat(BlockNames.full("chest{x:1}")).as("nothing but the position").isEqualTo("minecraft:chest");
        assertThat(BlockNames.id("minecraft:chest{Lock:\"a[b\"}")).isEqualTo("minecraft:chest");
        assertThat(BlockNames.isAir("air{}")).isTrue();
    }

    @Test
    void aSliceShowsDataLinesThatAStructureReadsBack() {
        Region r = Region.of("w", 5, 64, 7, 6, 64, 7);
        BlockGrid grid = new BlockGrid(r, List.of("minecraft:chest{Lock:\"k\"}", "minecraft:stone"), new int[] {1, 0});
        String text = TextGrid.render(grid, false, 6000);
        assertThat(text).contains("legend: #=").endsWith("\ndata 6,64,7 {Lock:\"k\"}");

        BlockGrid back = TextGrid.parse(text, "w", new BlockPos(0, 0, 0));
        assertThat(back.stateAt(1, 0, 0)).isEqualTo("minecraft:chest{Lock:\"k\"}");
        assertThat(back.stateAt(0, 0, 0)).isEqualTo("minecraft:stone");
        // Without a region line, data counts columns and rows from 0 and layers by their number.
        BlockGrid bare = TextGrid.parse("legend: c=chest\ny=3\n.c\ndata 1,3,0 {Lock:\"q\"}", "w", new BlockPos(0, 0, 0));
        assertThat(bare.stateAt(1, 0, 0)).isEqualTo("minecraft:chest{Lock:\"q\"}");
        assertThatThrownBy(() -> TextGrid.parse("legend: c=chest\ny=3\n_c\ndata 0,3,0 {a:1}", "w", new BlockPos(0, 0, 0)))
                .hasMessageContaining("needs a block there");
        assertThatThrownBy(() -> TextGrid.parse("legend: c=chest\ny=3\n.c\ndata 5,3,0 {a:1}", "w", new BlockPos(0, 0, 0)))
                .hasMessageContaining("outside the structure");
    }

    @Test
    void aSchematicKeepsDataAndReadsVersionThreeData() throws Exception {
        Region r = Region.of("w", 0, 0, 0, 1, 0, 0);
        BlockGrid grid = new BlockGrid(r, List.of("minecraft:chest{Items:[{Slot:0b,id:\"minecraft:diamond\",count:3}]}",
                "minecraft:chest"), new int[] {1, 0});
        Path file = dir.resolve("a.schem");
        Schematic.save(grid, file);
        BlockGrid back = Schematic.load(file, "w", new BlockPos(0, 0, 0));
        assertThat(back.stateAt(1, 0, 0)).isEqualTo(grid.palette().get(0));
        assertThat(back.stateAt(0, 0, 0)).isEqualTo("minecraft:chest");

        Map<String, Object> entity = new LinkedHashMap<>();
        entity.put("Pos", new int[] {0, 0, 0});
        entity.put("Id", "minecraft:sign");
        entity.put("Data", Map.of("is_waxed", (byte) 1, "id", "minecraft:sign"));
        Map<String, Object> blocks = new LinkedHashMap<>();
        blocks.put("Palette", Map.of("minecraft:oak_sign", 0));
        blocks.put("Data", new byte[] {0});
        blocks.put("BlockEntities", List.of(entity));
        Map<String, Object> v3 = new LinkedHashMap<>();
        v3.put("Version", 3);
        v3.put("Width", (short) 1);
        v3.put("Height", (short) 1);
        v3.put("Length", (short) 1);
        v3.put("Blocks", blocks);
        Path three = dir.resolve("b.schem");
        try (var out = Files.newOutputStream(three)) {
            Nbt.write(out, "", Map.of("Schematic", v3));
        }
        assertThat(Schematic.load(three, "w", new BlockPos(0, 0, 0)).stateAt(0, 0, 0))
                .isEqualTo("minecraft:oak_sign{is_waxed:1b}");
    }
}
