package io.github.imnotstable.msc.agent.vanilla;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData.DataValue;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** A text display's words, which world.entities reports as its name like the Paper and Minestom agents do. */
class EntityNamesTest {
    @BeforeAll
    static void boot() {
        Minecraft.bootstrap();
    }

    @Test
    void aTextDisplayIsNamedByItsTextAndNotByItsOtherData() {
        List<DataValue<?>> data = List.of(
                new DataValue<>(2, EntityDataSerializers.OPTIONAL_COMPONENT, Optional.<Component>empty()),
                new DataValue<>(8, EntityDataSerializers.INT, 5),
                new DataValue<>(23, EntityDataSerializers.COMPONENT, Component.literal("Wave ").append("3")));

        assertThat(VanillaLevel.displayText(data)).isEqualTo("Wave 3");
    }

    @Test
    void aTextDisplayWithDefaultTextHasNoName() {
        assertThat(VanillaLevel.displayText(null)).isNull();
        assertThat(VanillaLevel.displayText(List.of(new DataValue<>(8, EntityDataSerializers.INT, 5)))).isNull();
    }
}
