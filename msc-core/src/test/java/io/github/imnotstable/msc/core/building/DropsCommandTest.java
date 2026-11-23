package io.github.imnotstable.msc.core.building;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.protocol.world.Region;
import org.junit.jupiter.api.Test;

class DropsCommandTest {
    @Test
    void killsItemsInTheEditsBoxGrownByOneInItsDimension() {
        assertThat(Builder.dropsCommand(Region.of("msc:havenfall", 0, 60, 0, 9, 69, 4), "world"))
                .isEqualTo("execute in msc:havenfall run kill @e[type=item,x=-1,y=59,z=-1,dx=11,dy=11,dz=6]");
        assertThat(Builder.dropsCommand(Region.of("world", 0, 60, 0, 0, 60, 0), "world"))
                .startsWith("execute in minecraft:overworld run kill");
        assertThat(Builder.dropsCommand(Region.of("world_nether", 0, 60, 0, 0, 60, 0), "world")).isNull();
    }
}
