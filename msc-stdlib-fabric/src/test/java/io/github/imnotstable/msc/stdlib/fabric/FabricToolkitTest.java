package io.github.imnotstable.msc.stdlib.fabric;

import io.github.imnotstable.msc.stdlib.geom.Cuboid;
import io.github.imnotstable.msc.stdlib.geom.Vec3;
import io.github.imnotstable.msc.stdlib.text.Messages;
import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponentInitializers;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FabricToolkitTest {
    private static boolean bound;

    @BeforeAll
    static void boot() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        if (!bound) {
            bound = true;
            BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(VanillaRegistries.createWorldLookup())
                    .forEach(DataComponentInitializers.PendingComponents::apply);
        }
    }

    @Test
    void everySoundPresetIsARealSound() {
        assertThat(java.util.Arrays.stream(Sounds.values()).map(Sounds::key)
                .filter(k -> BuiltInRegistries.SOUND_EVENT.get(Identifier.withDefaultNamespace(k)).isEmpty()).toList())
                .isEmpty();
    }

    @Test
    void customItemsKeepTheirId() {
        CustomItems.register("dash", () -> ItemBuilder.of(Items.FEATHER).name(Component.literal("Dash")).build());
        ItemStack dash = CustomItems.create("dash");
        assertThat(CustomItems.is(dash, "dash")).isTrue();
        assertThat(CustomItems.id(new ItemStack(Items.STONE))).isEmpty();
    }

    @Test
    void regionsAndLanguages() {
        Regions.define("spawn", Level.OVERWORLD, Cuboid.of(Vec3.ZERO, new Vec3(10, 100, 10))).noBuild();
        assertThat(Regions.denied(Level.OVERWORLD, new Vec3(5, 5, 5), a -> true)).isTrue();
        assertThat(Regions.denied(Level.NETHER, new Vec3(5, 5, 5), a -> true)).isFalse();
        Lang lang = Lang.of(Messages.of("en", Map.of("hi", "Hi <name>")));
        assertThat(lang.get("de_de", "hi", "name", "Alex")).isEqualTo("Hi Alex");
    }
}
