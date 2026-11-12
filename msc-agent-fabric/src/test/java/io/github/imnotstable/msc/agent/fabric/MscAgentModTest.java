package io.github.imnotstable.msc.agent.fabric;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.agent.vanilla.Loader;
import net.fabricmc.loader.api.FabricLoader;
import org.junit.jupiter.api.Test;

class MscAgentModTest {
    @Test
    void namesFabricWhenQuiltIsAbsent() {
        Loader loader = MscAgentMod.loader(FabricLoader.getInstance());
        assertThat(loader.platform()).isEqualTo("fabric");
        assertThat(loader.version()).isNotEqualTo("unknown");
    }
}
