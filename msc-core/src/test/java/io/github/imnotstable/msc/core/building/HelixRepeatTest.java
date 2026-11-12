package io.github.imnotstable.msc.core.building;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.imnotstable.msc.core.tool.ToolException;
import io.github.imnotstable.msc.protocol.world.BlockGrid;
import io.github.imnotstable.msc.protocol.world.Region;
import java.util.List;
import org.junit.jupiter.api.Test;

class HelixRepeatTest {
    private static Canvas draw(String script) throws Exception {
        List<Step> steps = new BuildScript("world", new BuildScript.StructureFiles() {
            @Override
            public BlockGrid load(String path, String world) {
                throw new AssertionError();
            }

            @Override
            public BlockGrid read(Region region) {
                throw new AssertionError();
            }
        }).parse(script);
        Canvas c = new Canvas(new Step.Group(steps).bounds(), r -> {
            throw new AssertionError("no world read expected");
        });
        for (Step s : steps) {
            s.draw(c);
        }
        return c;
    }

    @Test
    void helixClimbsOneLayerPerBlockOfArcByDefault() throws Exception {
        Canvas c = draw("helix 0,64,0 r=4 h=20 stone");
        assertThat(c.used()).isEqualTo(Region.of("world", -4, 64, -4, 4, 83, 4));
        // Starts north of the centre and turns clockwise: east comes before west.
        assertThat(c.planned(0, 64, -4)).isEqualTo("minecraft:stone");
        assertThat(c.planned(0, 64, 0)).isNull();
        for (int y = 64; y <= 83; y++) {
            int layer = 0;
            for (int x = -4; x <= 4; x++) {
                for (int z = -4; z <= 4; z++) {
                    layer += c.planned(x, y, z) == null ? 0 : 1;
                }
            }
            // A walkable stair: every layer has a tread, and none is a long flat run.
            assertThat(layer).as("layer " + y).isBetween(1, 3);
        }
        assertThat(firstLayerAt(c, 4, 0)).isLessThan(firstLayerAt(c, -4, 0));
    }

    @Test
    void helixTakesTurnsWidthAndStart() throws Exception {
        Canvas ccw = draw("helix 0,64,0 r=4 h=20 stone turns=-0.5");
        assertThat(firstLayerAt(ccw, -4, 0)).isBetween(70, 76);
        assertThat(firstLayerAt(ccw, 4, 0)).isEqualTo(-1);
        Canvas wide = draw("helix 0,64,0 r=4 h=8 stone w=3 start=90");
        assertThat(wide.planned(4, 64, 0)).isNotNull();
        assertThat(wide.planned(2, 64, 0)).isNotNull();
        assertThat(wide.planned(1, 64, 0)).isNull();
        assertThatThrownBy(() -> draw("helix 0,64,0 r=4 h=8 stone turns=many")).isInstanceOf(ToolException.class)
                .hasMessageContaining("turns must be a number");
        assertThatThrownBy(() -> draw("helix 0,64,0 r=0 h=8 stone")).hasMessageContaining("r must be 1 to 512");
    }

    @Test
    void repeatCopiesAnyStep() throws Exception {
        Canvas posts = draw("set 0,64,0 oak_fence repeat=4 step=3,0,0\nfill 0,65,0 0,66,0 stone repeat=2 step=0,0,5");
        assertThat(posts.placed()).isEqualTo(4 + 4);
        assertThat(posts.planned(9, 64, 0)).isEqualTo("minecraft:oak_fence");
        assertThat(posts.planned(0, 66, 5)).isEqualTo("minecraft:stone");
        assertThatThrownBy(() -> draw("set 0,64,0 stone repeat=3")).hasMessageContaining("repeat needs step=");
        assertThatThrownBy(() -> draw("set 0,64,0 stone step=1,0,0")).hasMessageContaining("does not take step=");
        assertThatThrownBy(() -> draw("set 0,64,0 stone repeat=0 step=1,0,0")).hasMessageContaining("1 to 1024");
    }

    /** Lowest layer the column x,z is set at, or -1. */
    private static int firstLayerAt(Canvas c, int x, int z) {
        for (int y = 64; y < 100; y++) {
            if (c.bounds().contains(x, y, z) && c.planned(x, y, z) != null) {
                return y;
            }
        }
        return -1;
    }
}
