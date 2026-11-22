package io.github.imnotstable.msc.stdlib.fabric;

import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponentInitializers;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.component.DataComponents;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class StdlibFabricTest {
    private static boolean bound;

    @BeforeAll
    static void boot() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        // Item components come from data in 26.x: bind the built-in ones as a server's reload would.
        if (!bound) {
            bound = true;
            BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(VanillaRegistries.createWorldLookup())
                    .forEach(DataComponentInitializers.PendingComponents::apply);
        }
    }

    @Test
    void itemsCarryNameLoreAndData() {
        ItemStack sword = ItemBuilder.of(Items.DIAMOND_SWORD).name(Component.literal("Slayer"))
                .lore(Component.literal("Kills: 3")).glow().data("kit", "slayer").amount(1).build();
        assertThat(sword.get(DataComponents.CUSTOM_NAME).getString()).isEqualTo("Slayer");
        assertThat(sword.get(DataComponents.CUSTOM_NAME).getStyle().isItalic()).isFalse();
        assertThat(sword.get(DataComponents.LORE).lines().getFirst().getString()).isEqualTo("Kills: 3");
        assertThat(ItemBuilder.read(sword, "kit")).isEqualTo("slayer");
        assertThat(ItemBuilder.read(new ItemStack(Items.STONE), "kit")).isNull();
    }

    @Test
    void aGuiKeepsItsItemsExceptEditableSlots() {
        Gui gui = Gui.chest(3, Component.literal("Shop")).set(13, new ItemStack(Items.DIAMOND)).editable(0)
                .fill(ItemBuilder.filler());
        assertThat(gui.allows(13, ContainerInput.PICKUP)).isFalse();
        assertThat(gui.allows(0, ContainerInput.PICKUP)).isTrue();
        assertThat(gui.allows(40, ContainerInput.PICKUP)).isTrue();      // the player's own inventory
        assertThat(gui.allows(40, ContainerInput.QUICK_MOVE)).isFalse(); // shift-click into the menu
        assertThat(gui.item(5).is(Items.STAINED_GLASS_PANE.gray())).isTrue();
    }

    @Test
    void aPagedGuiTurnsPages() {
        List<Integer> picked = new ArrayList<>();
        Gui gui = Gui.paged(2, Component.literal("Numbers"), IntStream.range(0, 20).boxed().toList(),
                i -> new ItemStack(Items.STONE, i + 1), (i, c) -> picked.add(i));
        gui.dispatch(null, 17, 0, ContainerInput.PICKUP);
        gui.dispatch(null, 0, 0, ContainerInput.PICKUP);
        assertThat(picked).containsExactly(9);
        assertThat(gui.item(13).get(DataComponents.CUSTOM_NAME).getString()).isEqualTo("Page 2/3");
    }

    @Test
    void theSchedulerRunsOnTicks() {
        TickScheduler s = new TickScheduler();
        List<String> ran = new ArrayList<>();
        s.later(2, () -> ran.add("later"));
        var repeat = s.repeat(0, 3, () -> ran.add("repeat"));
        s.countdown(1, n -> ran.add("tick" + n), () -> ran.add("done"));
        for (int i = 0; i < 25; i++) {
            s.tick();
            if (i == 6) {
                repeat.cancel();
            }
        }
        assertThat(ran).containsExactly("repeat", "tick1", "later", "repeat", "repeat", "done");
    }
}
