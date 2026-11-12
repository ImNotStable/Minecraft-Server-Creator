package {{packageName}};

import static org.assertj.core.api.Assertions.assertThat;

import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.testing.Env;
import net.minestom.testing.EnvTest;
import org.junit.jupiter.api.Test;

/** Server tests in Minestom's test environment: a real server process without a network. */
@EnvTest
class {{name}}ServerTest {
    @Test
    void worldIsFlat(Env env) {
        Instance world = {{name}}Server.createWorld(env.process().instance());
        world.loadChunk(0, 0).join();

        assertThat(world.getBlock(3, -64, 3)).isEqualTo(Block.BEDROCK);
        assertThat(world.getBlock(3, -62, 3)).isEqualTo(Block.DIRT);
        assertThat(world.getBlock(3, -61, 3)).isEqualTo(Block.GRASS_BLOCK);
        assertThat(world.getBlock(3, -60, 3)).isEqualTo(Block.AIR);
    }
}
