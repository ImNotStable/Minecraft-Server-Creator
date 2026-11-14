package io.github.imnotstable.msc.bot;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.VillagerTrade;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundSetHeldSlotPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerClosePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerSetContentPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerSetSlotPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundMerchantOffersPacket;
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
    private static final Pattern GENERIC = Pattern.compile("GENERIC_(\\d)X(\\d)");
    private static final String[] ARMOR = {"head", "chest", "legs", "feet"};

    private final Items items;
    private final ItemStack[] own = new ItemStack[SIZE];
    private int held;
    private int screenId = -1;
    private String screen;
    private int screenSize = -1;
    private ItemStack[] screenItems = new ItemStack[0];
    private List<String> trades = List.of();

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
                    for (int i = 0; i < screenItems.length; i++) {
                        putOwn(i, screenItems[i]);
                    }
                }
            }
            case ClientboundContainerSetSlotPacket p -> {
                if (p.getContainerId() == 0) {
                    put(own, p.getSlot(), p.getItem());
                } else if (p.getContainerId() == screenId) {
                    put(screenItems, p.getSlot(), p.getItem());
                    putOwn(p.getSlot(), p.getItem());
                }
            }
            case ClientboundSetPlayerInventoryPacket p -> put(own, menuSlot(p.getSlot()), p.getContents());
            case ClientboundSetHeldSlotPacket p -> held = p.getSlot();
            case ClientboundOpenScreenPacket p -> {
                screenId = p.getContainerId();
                screen = p.getType().name().toLowerCase(Locale.ROOT) + " \"" + Text.title(p.getTitle()) + "\"";
                screenSize = menuSize(p.getType().name());
                screenItems = new ItemStack[0];
                trades = List.of();
            }
            case ClientboundMerchantOffersPacket p -> {
                if (p.getContainerId() == screenId) {
                    trades = p.getOffers().stream().map(this::trade).toList();
                }
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
        screenSize = -1;
        screenItems = new ItemStack[0];
        trades = List.of();
    }

    /** One trade as the merchant menu shows it: what it costs, what it gives, and whether it is used up. */
    private String trade(VillagerTrade t) {
        StringBuilder out = new StringBuilder(cost(t.getItemCostA()));
        if (t.getItemCostB() != null) {
            out.append(" + ").append(cost(t.getItemCostB()));
        }
        out.append(" -> ").append(t.getResult() == null ? "nothing" : items.describe(t.getResult()));
        if (t.isOutOfStock() || t.getUses() >= t.getMaxUses()) {
            out.append(" (out of stock)");
        }
        return out.toString();
    }

    private String cost(VillagerTrade.ItemCost c) {
        return items.name(c.itemId()) + (c.count() != 1 ? " x" + c.count() : "");
    }

    /**
     * How many slots a menu has before the player's own inventory, or -1 when unknown. Vanilla servers send the
     * player's 36 slots after the menu's; Minestom sends only the menu's, so the count cannot come from the packet.
     */
    static int menuSize(String type) {
        Matcher m = GENERIC.matcher(type);
        if (m.matches()) {
            return Integer.parseInt(m.group(1)) * Integer.parseInt(m.group(2));
        }
        return switch (type) {
            case "HOPPER" -> 5;
            case "SHULKER_BOX" -> 27;
            case "CRAFTER_3x3" -> 9;
            case "BREWING_STAND" -> 5;
            case "FURNACE", "BLAST_FURNACE", "SMOKER", "MERCHANT", "ANVIL", "GRINDSTONE", "CARTOGRAPHY" -> 3;
            case "SMITHING", "LOOM" -> 4;
            case "CRAFTING" -> 10;
            case "BEACON", "LECTERN" -> 1;
            case "ENCHANTMENT", "STONECUTTER" -> 2;
            default -> -1;
        };
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

    /**
     * The open menu's slot {@code slot} into the player's own inventory when it is one of the player's 36 slots,
     * which vanilla-family servers send under the menu's id after the menu's own slots: backpack, then hotbar.
     */
    private void putOwn(int slot, ItemStack item) {
        int menuSlots = screenSize >= 0 ? screenSize : screenItems.length > 36 ? screenItems.length - 36 : -1;
        int k = slot - menuSlots;
        if (menuSlots >= 0 && k >= 0 && k < 36) {
            own[9 + k] = item;
        }
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
            int menuSlots = screenSize >= 0 ? Math.min(screenSize, screenItems.length)
                    : Math.max(0, screenItems.length - 36);
            parts.clear();
            for (int i = 0; i < menuSlots; i++) {
                if (!empty(screenItems[i])) {
                    parts.add(i + " " + items.describe(screenItems[i]));
                }
            }
            lines.add("open menu " + screen + ": " + (parts.isEmpty() ? "empty" : String.join("; ", parts)));
            if (!trades.isEmpty()) {
                lines.add("trades: " + String.join("; ", trades));
            }
        }
        return String.join("\n", lines);
    }
}
