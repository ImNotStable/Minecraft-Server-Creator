package io.github.imnotstable.msc.core.world;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.imnotstable.msc.core.tool.ToolException;
import io.github.imnotstable.msc.protocol.world.Region;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class FillShapeTest {
    @Test
    void partsCoverEachShapeExactlyOnce() {
        int[][] sizes = {{1, 1, 1}, {5, 4, 6}, {2, 5, 3}, {1, 3, 4}, {7, 1, 7}, {3, 3, 3}, {4, 2, 4}};
        for (int[] s : sizes) {
            Region r = Region.of("w", 10, -60, -3, 10 + s[0] - 1, -60 + s[1] - 1, -3 + s[2] - 1);
            for (String shape : List.of("solid", "outline", "hollow", "walls")) {
                Set<String> want = new HashSet<>();
                for (int x = r.min().x(); x <= r.max().x(); x++) {
                    for (int y = r.min().y(); y <= r.max().y(); y++) {
                        for (int z = r.min().z(); z <= r.max().z(); z++) {
                            boolean side = x == r.min().x() || x == r.max().x() || z == r.min().z() || z == r.max().z();
                            boolean cap = y == r.min().y() || y == r.max().y();
                            boolean in = switch (shape) {
                                case "solid" -> true;
                                case "walls" -> side;
                                default -> side || cap;
                            };
                            if (in) {
                                want.add(x + "," + y + "," + z);
                            }
                        }
                    }
                }
                List<String> got = cells(WorldService.shapeParts(r, shape));
                assertThat(got).as(shape + " " + s[0] + "x" + s[1] + "x" + s[2]).doesNotHaveDuplicates()
                        .containsExactlyInAnyOrderElementsOf(want);
            }
        }
    }

    @Test
    void hollowClearsOnlyTheInside() {
        assertThat(WorldService.inside(Region.of("w", 0, 0, 0, 4, 3, 4)))
                .isEqualTo(Region.of("w", 1, 1, 1, 3, 2, 3));
        assertThat(WorldService.inside(Region.of("w", 0, 0, 0, 4, 1, 4))).isNull();
        assertThatThrownBy(() -> WorldService.shapeParts(Region.of("w", 0, 0, 0, 1, 1, 1), "box"))
                .isInstanceOf(ToolException.class).hasMessage("shape must be solid, hollow, outline, walls");
    }

    private static List<String> cells(List<Region> parts) {
        List<String> out = new java.util.ArrayList<>();
        for (Region p : parts) {
            for (int x = p.min().x(); x <= p.max().x(); x++) {
                for (int y = p.min().y(); y <= p.max().y(); y++) {
                    for (int z = p.min().z(); z <= p.max().z(); z++) {
                        out.add(x + "," + y + "," + z);
                    }
                }
            }
        }
        return out;
    }
}
