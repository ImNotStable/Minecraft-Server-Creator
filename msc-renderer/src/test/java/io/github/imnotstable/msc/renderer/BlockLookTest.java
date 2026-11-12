package io.github.imnotstable.msc.renderer;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BlockLookTest {
    @Test
    void parsesStatesWithAndWithoutNamespaceAndProperties() {
        BlockState s = BlockState.parse("minecraft:oak_stairs[facing=north,half=top]");
        assertThat(s.namespace()).isEqualTo("minecraft");
        assertThat(s.name()).isEqualTo("oak_stairs");
        assertThat(s.props()).containsEntry("facing", "north").containsEntry("half", "top");
        assertThat(BlockState.parse("stone").name()).isEqualTo("stone");
        assertThat(BlockState.parse("myplugin:ruby_ore").namespace()).isEqualTo("myplugin");
    }

    @Test
    void smallRedstoneAndMetalBlocksHaveAColour() {
        for (String name : new String[] {"heavy_weighted_pressure_plate", "light_weighted_pressure_plate", "lever", "lightning_rod"}) {
            assertThat(BlockColors.find(name, BlockColors.TOP).known()).as(name).isTrue();
        }
    }

    @Test
    void shapedBlocksTakeTheColourOfWhatTheyAreMadeOf() {
        int planks = BlockColors.find("oak_planks", BlockColors.SIDE).argb();
        assertThat(BlockColors.find("oak_slab", BlockColors.SIDE).argb()).isEqualTo(planks);
        assertThat(BlockColors.find("oak_fence_gate", BlockColors.SIDE).argb()).isEqualTo(planks);
        assertThat(BlockColors.find("stripped_oak_log", BlockColors.SIDE).argb()).isEqualTo(planks);
        assertThat(BlockColors.find("stone_brick_stairs", BlockColors.SIDE).argb())
                .isEqualTo(BlockColors.find("stone_bricks", BlockColors.SIDE).argb());
        assertThat(BlockColors.find("waxed_cut_copper_slab", BlockColors.SIDE).argb())
                .isEqualTo(BlockColors.find("cut_copper", BlockColors.SIDE).argb());
        assertThat(BlockColors.find("oak_wood", BlockColors.TOP).argb())
                .isEqualTo(BlockColors.find("oak_log", BlockColors.SIDE).argb());
    }

    @Test
    void facesAndDyesAndUnknownBlocks() {
        assertThat(BlockColors.find("grass_block", BlockColors.TOP).argb())
                .isNotEqualTo(BlockColors.find("grass_block", BlockColors.SIDE).argb());
        assertThat(BlockColors.find("light_blue_wool", BlockColors.SIDE).argb()).isEqualTo(0xff3aafd9);
        assertThat(BlockColors.find("red_carpet", BlockColors.SIDE).argb())
                .isEqualTo(BlockColors.find("red_wool", BlockColors.SIDE).argb());
        assertThat(BlockColors.find("blue_stained_glass", BlockColors.SIDE).argb() >>> 24).isLessThan(0xe6);
        assertThat(BlockColors.find("red_sandstone", BlockColors.SIDE).argb()).isEqualTo(0xffba631d);

        BlockColors.Found unknown = BlockColors.find("zzz_gizmo", BlockColors.SIDE);
        assertThat(unknown.known()).isFalse();
        assertThat(unknown.argb()).isEqualTo(BlockColors.find("zzz_gizmo", BlockColors.TOP).argb());
        assertThat(BlockColors.find("stone", BlockColors.SIDE).known()).isTrue();
    }

    @Test
    void shapes() {
        assertThat(Shapes.of(BlockState.parse("stone"))).containsExactly(Box.FULL);
        assertThat(Shapes.of(BlockState.parse("oak_slab[type=double]"))).containsExactly(Box.FULL);
        assertThat(Shapes.of(BlockState.parse("oak_slab[type=top]"))).containsExactly(new Box(0, 0.5, 0, 1, 1, 1));
        assertThat(Shapes.of(BlockState.parse("oak_stairs[facing=east,half=bottom]")))
                .containsExactly(new Box(0, 0, 0, 1, 0.5, 1), new Box(0.5, 0.5, 0, 1, 1, 1));
        assertThat(Shapes.of(BlockState.parse("oak_fence[east=true,north=false]"))).hasSize(2);
        assertThat(Shapes.of(BlockState.parse("cobblestone_wall[up=true,north=low,south=tall,east=none]"))).hasSize(3);
        // A closed door facing south sits on the north edge of its block.
        assertThat(Shapes.of(BlockState.parse("oak_door[facing=south,open=false]")))
                .containsExactly(new Box(0, 0, 0, 1, 1, 3 / 16.0));
        assertThat(Shapes.of(BlockState.parse("snow[layers=4]"))).containsExactly(new Box(0, 0, 0, 1, 0.5, 1));
    }
}
