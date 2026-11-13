package io.github.imnotstable.msc.agent.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class BlockNamesTest {
    private static final List<String> KNOWN = List.of("iron_chain", "chain_command_block", "diorite_wall",
            "polished_diorite", "polished_blackstone_wall", "stone", "andesite_wall");

    @Test
    void suggestsBlocksSharingTheMostWords() {
        assertThat(BlockNames.suggest("chain", KNOWN)).first().isEqualTo("iron_chain");
        assertThat(BlockNames.suggest("minecraft:polished_diorite_wall", KNOWN))
                .containsExactly("polished_diorite", "diorite_wall", "polished_blackstone_wall");
        assertThat(BlockNames.suggest("glorp", KNOWN)).isEmpty();
    }
}
