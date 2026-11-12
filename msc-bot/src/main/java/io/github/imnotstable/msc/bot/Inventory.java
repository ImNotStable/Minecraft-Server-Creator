package io.github.imnotstable.msc.bot;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundSetHeldSlotPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerClosePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerSetContentPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerSetSlotPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundOpenScreenPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundSetPlayerInventoryPacket;

/**
 * What the test player carries, and the menu a server opened for it (a chest GUI, a shop, a villager). Kept from the
 * packets a client gets; reported only by the {@code inventory} action.
 */
final class Inventory {
    /** Container 0 is the player's own inventory, in the vanilla menu layout. */
    private static final int SIZE = 46;
    private static final int HOTBAR = 36;
    private static final int OFFHAND = 45;
    private static final String[] ARMOR = {"head", "chest", "legs", "feet"};

    private final Items items;
    private final ItemStack[] own = new ItemStack[SIZE];
    private int held;
    private int screenId = -1;
    private String screen;
    private ItemStack[] screenItems = new ItemStack[0];

    Inventory(Items items) {
        this.items = items;
    }

    /** Take in a packet; false when it is not about items. */
    synchronized boolean accept(Packet packet) {
        switch (packet) {
            case ClientboundContainerSetContentPacket p -> {
                if (p.getContainerId() == 0) {
                    System.arraycopy(p.getItems(), 0, own, 0, Math.min(SIZE, p.getItems().length));
                } else if (p.getContainerId() == screenId) {
                    screenItems = p.getItems().clone();
                }
            }
            case ClientboundContainerSetSlotPacket p -> {
                if (p.getContainerId() == 0) {
                    put(own, p.getSlot(), p.getItem());
                } else if (p.getContainerId() == screenId) {
                    put(screenItems, p.getSlot(), p.getItem());
                }
            }
            case ClientboundSetPlayerInventoryPacket p -> put(own, menuSlot(p.getSlot()), p.getContents());
            case ClientboundSetHeldSlotPacket p -> held = p.getSlot();
            case ClientboundOpenScreenPacket p -> {
                screenId = p.getContainerId();
                screen = p.getType().name().toLowerCase(Locale.ROOT) + " \"" + Text.plain(p.getTitle()) + "\"";
                screenItems = new ItemStack[0];
            }
            case ClientboundContainerClosePacket p -> {
                if (p.getContainerId() == screenId) {
                    closed();
                }
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    synchronized void select(int hotbarSlot) {
        held = hotbarSlot;
    }

    /** The open menu's id, or -1. */
    synchronized int screenId() {
        return screenId;
    }

    synchronized void closed() {
        screenId = -1;
        screen = null;
        screenItems = new ItemStack[0];
    }

    /** The inventory slot numbers of {@code ClientboundSetPlayerInventoryPacket}, as menu slots. */
    static int menuSlot(int slot) {
        if (slot >= 0 && slot < 9) {
            return HOTBAR + slot;
        }
        if (slot >= 36 && slot < 40) {
            return 8 - (slot - 36); // feet, legs, chest, head
        }
        return slot == 40 ? OFFHAND : slot;
    }

    private static void put(ItemStack[] slots, int slot, ItemStack item) {
        if (slot >= 0 && slot < slots.length) {
            slots[slot] = item;
        }
    }

    private static boolean empty(ItemStack item) {
        return item == null || item.getId() == 0 || item.getAmount() <= 0;
    }

    synchronized String report() {
        List<String> lines = new ArrayList<>();
        ItemStack hand = own[HOTBAR + held];
        lines.add("holding slot " + (held + 1) + ": " + (empty(hand) ? "nothing" : items.describe(hand)));
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            if (!empty(own[HOTBAR + i])) {
                parts.add((i + 1) + " " + items.describe(own[HOTBAR + i]));
            }
        }
        lines.add("hotbar: " + (parts.isEmpty() ? "empty" : String.join("; ", parts)));
        parts.clear();
        for (int i = 9; i < HOTBAR; i++) {
            if (!empty(own[i])) {
                parts.add(items.describe(own[i]));
            }
        }
        if (!parts.isEmpty()) {
            lines.add("backpack: " + String.join("; ", parts));
        }
        parts.clear();
        for (int i = 0; i < ARMOR.length; i++) {
            if (!empty(own[5 + i])) {
                parts.add(ARMOR[i] + " " + items.describe(own[5 + i]));
            }
        }
        if (!empty(own[OFFHAND])) {
            parts.add("offhand " + items.describe(own[OFFHAND]));
        }
        if (!parts.isEmpty()) {
            lines.add("worn: " + String.join("; ", parts));
        }
        if (screen != null) {
            // The menu's own slots come first; the player's inventory below them is already reported.
            int menuSlots = Math.max(0, screenItems.length - 36);
            parts.clear();
            for (int i = 0; i < menuSlots; i++) {
                if (!empty(screenItems[i])) {
                    parts.add(i + " " + items.describe(screenItems[i]));
                }
            }
            lines.add("open menu " + screen + ": " + (parts.isEmpty() ? "empty" : String.join("; ", parts)));
        }
        return String.join("\n", lines);
    }
}
