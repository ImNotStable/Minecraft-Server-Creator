package io.github.imnotstable.msc.core.world;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.protocol.op.Ops;
import io.github.imnotstable.msc.protocol.world.Region;
import java.util.List;
import org.junit.jupiter.api.Test;

class EntityListingTest {
    private static final Ops.EntitiesResult NONE = new Ops.EntitiesResult(List.of(), 0);
    private static final Region REGION = Region.of("world", 0, 60, 0, 1, 61, 1);

    @Test
    void emptyAnswerSuggestsForceloadOnlyWhereChunksMatter() {
        assertThat(WorldService.formatEntities(NONE, "minecraft:pig", false, REGION, true))
                .startsWith("no pig entities in the region. Only loaded chunks").contains("forceload add 0 0 1 1");
        // Minestom searches the whole instance and has no forceload command.
        assertThat(WorldService.formatEntities(NONE, "minecraft:pig", false, REGION, false))
                .isEqualTo("no pig entities in the region");
        assertThat(WorldService.formatEntities(NONE, null, false, REGION, false)).isEqualTo("no entities in the region");
    }
}
