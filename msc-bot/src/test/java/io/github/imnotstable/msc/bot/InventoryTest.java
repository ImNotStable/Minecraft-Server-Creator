package io.github.imnotstable.msc.bot;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import net.kyori.adventure.text.Component;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerType;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.VillagerTrade;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponents;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundSetHeldSlotPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerClosePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerSetContentPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerSetSlotPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundMerchantOffersPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundOpenScreenPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundSetPlayerInventoryPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.title.ClientboundSetTitleTextPacket;
import org.junit.jupiter.api.Test;

class InventoryTest {
    private final Inventory inv = new Inventory(Items.forVersion("26.3"));

    @Test
    void reportsHeldItemHotbarBackpackAndArmour() {
        assertThat(inv.report()).isEqualTo("holding slot 1: nothing\nhotbar: empty");
        ItemStack[] slots = new ItemStack[46];
        slots[36] = new ItemStack(1, 1); // hotbar 1
        slots[20] = new ItemStack(2, 16); // backpack
        slots[5] = new ItemStack(3, 1); // head
        assertThat(inv.accept(new ClientboundContainerSetContentPacket(0, 1, slots, null))).isTrue();
        DataComponents patch = new DataComponents(new HashMap<>());
        patch.put(DataComponentTypes.CUSTOM_NAME, Component.text("Key"));
        inv.accept(new ClientboundContainerSetSlotPacket(0, 2, 37, new ItemStack(4, 1, patch)));
        inv.accept(new ClientboundSetPlayerInventoryPacket(40, new ItemStack(5, 2))); // offhand
        inv.accept(new ClientboundSetHeldSlotPacket(1));
        assertThat(inv.report()).isEqualTo("""
                holding slot 2: diorite "Key"
                hotbar: 1 stone; 2 diorite "Key"
                backpack: granite x16
                worn: head polished_granite; offhand polished_diorite x2""");
        inv.select(0);
        assertThat(inv.report()).startsWith("holding slot 1: stone\n");
        assertThat(inv.accept(new ClientboundSetTitleTextPacket(Component.text("hi")))).isFalse();
    }

    @Test
    void reportsAnOpenMenuUntilItCloses() {
        inv.accept(new ClientboundOpenScreenPacket(3, ContainerType.GENERIC_9X3, Component.text("Shop")));
        ItemStack[] menu = new ItemStack[27 + 36];
        menu[13] = new ItemStack(1, 64);
        menu[30] = new ItemStack(2, 1); // the player's own inventory, shown below the menu
        inv.accept(new ClientboundContainerSetContentPacket(3, 1, menu, null));
        assertThat(inv.screenId()).isEqualTo(3);
        assertThat(inv.report()).endsWith("open menu generic_9x3 \"Shop\": 13 stone x64");
        inv.accept(new ClientboundContainerClosePacket(3));
        assertThat(inv.screenId()).isEqualTo(-1);
        assertThat(inv.report()).doesNotContain("open menu");
    }

    @Test
    void appliesThePlayersSlotsSentThroughAnOpenMenu() {
        inv.accept(new ClientboundOpenScreenPacket(3, ContainerType.GENERIC_9X3, Component.text("Shop")));
        ItemStack[] menu = new ItemStack[27 + 36];
        menu[30] = new ItemStack(2, 1); // the 4th backpack slot
        inv.accept(new ClientboundContainerSetContentPacket(3, 1, menu, null));
        inv.accept(new ClientboundContainerSetSlotPacket(3, 2, 27 + 27, new ItemStack(1, 3))); // hotbar 1
        assertThat(inv.report()).startsWith("""
                holding slot 1: stone x3
                hotbar: 1 stone x3
                backpack: granite
                open menu generic_9x3 "Shop": empty""");
        inv.accept(new ClientboundContainerClosePacket(3));
        assertThat(inv.report()).isEqualTo("holding slot 1: stone x3\nhotbar: 1 stone x3\nbackpack: granite");
    }

    @Test
    void smithingAndLoomMenusHaveFourSlotsBeforeThePlayers() {
        for (ContainerType type : new ContainerType[] {ContainerType.SMITHING, ContainerType.LOOM}) {
            Inventory fresh = new Inventory(Items.forVersion("26.3"));
            fresh.accept(new ClientboundOpenScreenPacket(5, type, Component.text("T")));
            ItemStack[] menu = new ItemStack[4 + 36];
            menu[3] = new ItemStack(3, 1); // the menu's result slot
            menu[4 + 27] = new ItemStack(1, 1); // hotbar 1
            menu[4 + 35] = new ItemStack(2, 1); // hotbar 9
            fresh.accept(new ClientboundContainerSetContentPacket(5, 1, menu, null));
            assertThat(fresh.report()).as(type.name()).startsWith("""
                    holding slot 1: stone
                    hotbar: 1 stone; 9 granite
                    open menu""");
        }
    }

    @Test
    void readsMenusSentWithoutThePlayersSlots() {
        // Minestom sends only the menu's own slots.
        inv.accept(new ClientboundOpenScreenPacket(4, ContainerType.GENERIC_9X6, Component.text("Big")));
        ItemStack[] menu = new ItemStack[54];
        menu[50] = new ItemStack(1, 1);
        inv.accept(new ClientboundContainerSetContentPacket(4, 1, menu, null));
        assertThat(inv.report()).endsWith("open menu generic_9x6 \"Big\": 50 stone");
        assertThat(Inventory.menuSize("HOPPER")).isEqualTo(5);
        assertThat(Inventory.menuSize("SOMETHING_NEW")).isEqualTo(-1);
    }

    @Test
    void listsAMerchantsTradesUnderItsTitle() {
        // A vanilla villager's menu title is a translation key the client looks up; the bot keeps its last word.
        inv.accept(new ClientboundOpenScreenPacket(5, ContainerType.MERCHANT,
                Component.translatable("entity.minecraft.villager.farmer")));
        VillagerTrade bread = new VillagerTrade(new VillagerTrade.ItemCost(2, 12, java.util.Map.of()),
                new ItemStack(1, 6), null, false, 0, 16, 2, 0, 0.05f, 0);
        VillagerTrade used = new VillagerTrade(new VillagerTrade.ItemCost(1, 1, java.util.Map.of()),
                new ItemStack(3, 1), new VillagerTrade.ItemCost(4, 2, java.util.Map.of()), false, 12, 12, 2, 0, 0.05f,
                0);
        inv.accept(new ClientboundMerchantOffersPacket(5, java.util.List.of(bread, used), 1, 0, true, true));
        assertThat(inv.report()).endsWith("""
                open menu merchant "Farmer": empty
                trades: granite x12 -> stone x6; stone + diorite x2 -> polished_granite (out of stock)""");
        inv.accept(new ClientboundMerchantOffersPacket(9, java.util.List.of(bread), 1, 0, true, true));
        assertThat(inv.report()).contains("trades: granite x12 -> stone x6; stone");
        inv.closed();
        assertThat(inv.report()).doesNotContain("trades");
    }

    @Test
    void mapsInventorySlotsToMenuSlots() {
        assertThat(Inventory.menuSlot(0)).isEqualTo(36);
        assertThat(Inventory.menuSlot(8)).isEqualTo(44);
        assertThat(Inventory.menuSlot(9)).isEqualTo(9);
        assertThat(Inventory.menuSlot(36)).isEqualTo(8); // feet
        assertThat(Inventory.menuSlot(39)).isEqualTo(5); // head
        assertThat(Inventory.menuSlot(40)).isEqualTo(45);
    }
}
