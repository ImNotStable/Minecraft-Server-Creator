package io.github.imnotstable.msc.stdlib.minestom;

import io.github.imnotstable.msc.stdlib.text.Text;
import net.kyori.adventure.text.Component;
import net.minestom.server.entity.Player;
import net.minestom.server.event.Event;
import net.minestom.server.event.EventNode;
import net.minestom.server.event.inventory.InventoryCloseEvent;
import net.minestom.server.event.inventory.InventoryPreClickEvent;
import net.minestom.server.inventory.Inventory;
import net.minestom.server.inventory.InventoryType;
import net.minestom.server.inventory.click.Click;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * A chest menu. Items cannot be taken or put in except in {@link #editable} slots; clicks go to the slot's handler.
 * Needs {@link Stdlib#init}.
 *
 * <pre>{@code
 * Gui.chest(3, Text.mm("<gold>Shop"))
 *         .fill(ItemBuilder.pane(Material.BROWN_STAINED_GLASS_PANE))
 *         .set(13, ItemBuilder.of(Material.DIAMOND).name("<aqua>Buy").build(), click -> buy(click.player()))
 *         .open(player);
 * }</pre>
 */
public final class Gui {
    private static final InventoryType[] CHESTS = {InventoryType.CHEST_1_ROW, InventoryType.CHEST_2_ROW,
            InventoryType.CHEST_3_ROW, InventoryType.CHEST_4_ROW, InventoryType.CHEST_5_ROW, InventoryType.CHEST_6_ROW};

    private final Menu inventory;
    private final Map<Integer, Consumer<GuiClick>> handlers = new HashMap<>();
    private final Set<Integer> editable = new HashSet<>();
    private Consumer<Player> onClose = p -> {};

    /** A click in the menu: who, which slot, which click (left, right, shift...), and the event. */
    public record GuiClick(Player player, int slot, Click click, InventoryPreClickEvent event) {
        public void close() {
            Stdlib.scheduler().sync().execute(player::closeInventory);
        }
    }

    /** The inventory that knows its menu, so the listener can find it from the player's open inventory. */
    private static final class Menu extends Inventory {
        private final Gui gui;

        Menu(InventoryType type, Component title, Gui gui) {
            super(type, title);
            this.gui = gui;
        }
    }

    private Gui(int rows, Component title) {
        if (rows < 1 || rows > 6) {
            throw new IllegalArgumentException("A chest menu has 1 to 6 rows: " + rows);
        }
        this.inventory = new Menu(CHESTS[rows - 1], title, this);
    }

    public static Gui chest(int rows, Component title) {
        return new Gui(rows, title);
    }

    /**
     * {@link #paged}'s buttons, made by you: {@code back} and {@code next} arrows, and {@code page} (may be null) for the
     * middle of the last row, given the page and the page count, both counted from 1. No stock look (ADR 0106).
     */
    public record Nav(ItemStack back, ItemStack next, BiFunction<Integer, Integer, ItemStack> page) {}

    /** A menu over a list, a page at a time: the last row has {@code nav}'s buttons. */
    public static <T> Gui paged(int rows, Component title, List<T> entries, Function<T, ItemStack> icon,
                                BiConsumer<T, GuiClick> onClick, Nav nav) {
        Gui gui = new Gui(Math.max(2, rows), title);
        List<T> list = List.copyOf(entries);
        int perPage = (Math.max(2, rows) - 1) * 9;
        int pages = Math.max(1, (list.size() + perPage - 1) / perPage);
        int[] page = {0};
        Runnable[] render = new Runnable[1];
        render[0] = () -> {
            gui.clear();
            int from = page[0] * perPage;
            for (int i = 0; i < perPage && from + i < list.size(); i++) {
                T entry = list.get(from + i);
                gui.set(i, icon.apply(entry), c -> onClick.accept(entry, c));
            }
            if (page[0] > 0) {
                gui.set(perPage, nav.back(), c -> {
                    page[0]--;
                    render[0].run();
                });
            }
            if (page[0] < pages - 1) {
                gui.set(perPage + 8, nav.next(), c -> {
                    page[0]++;
                    render[0].run();
                });
            }
            if (nav.page() != null) {
                gui.set(perPage + 4, nav.page().apply(page[0] + 1, pages));
            }
        };
        render[0].run();
        return gui;
    }

    public Gui set(int slot, ItemStack item) {
        return set(slot, item, c -> {});
    }

    public Gui set(int slot, ItemStack item, Consumer<GuiClick> onClick) {
        inventory.setItemStack(slot, item);
        handlers.put(slot, onClick);
        return this;
    }

    public Gui set(int row, int column, ItemStack item, Consumer<GuiClick> onClick) {
        return set(row * 9 + column, item, onClick);
    }

    /** Puts {@code item} in every empty slot. */
    public Gui fill(ItemStack item) {
        for (int i = 0; i < inventory.getSize(); i++) {
            if (inventory.getItemStack(i).isAir()) {
                inventory.setItemStack(i, item);
            }
        }
        return this;
    }

    /** Puts {@code item} in the empty slots of the outer ring. */
    public Gui border(ItemStack item) {
        int rows = inventory.getSize() / 9;
        for (int i = 0; i < inventory.getSize(); i++) {
            int r = i / 9, c = i % 9;
            if ((r == 0 || r == rows - 1 || c == 0 || c == 8) && inventory.getItemStack(i).isAir()) {
                inventory.setItemStack(i, item);
            }
        }
        return this;
    }

    public Gui editable(int... slots) {
        for (int s : slots) {
            editable.add(s);
        }
        return this;
    }

    public Gui onClose(Consumer<Player> action) {
        this.onClose = action;
        return this;
    }

    public Gui clear() {
        inventory.clear();
        handlers.clear();
        return this;
    }

    public Gui open(Player player) {
        Stdlib.events();
        player.openInventory(inventory);
        return this;
    }

    public Inventory inventory() {
        return inventory;
    }

    static void listen(EventNode<Event> node) {
        node.addListener(InventoryPreClickEvent.class, e -> {
            if (!(e.getPlayer().getOpenInventory() instanceof Menu menu)) {
                return;
            }
            Gui gui = menu.gui;
            if (e.getInventory() != menu) {
                // The player's own inventory: shift-clicks and double-clicks would move items into the menu.
                if (e.getClick() instanceof Click.LeftShift || e.getClick() instanceof Click.RightShift
                        || e.getClick() instanceof Click.Double) {
                    e.setCancelled(true);
                }
                return;
            }
            int slot = e.getSlot();
            if (!gui.editable.contains(slot)) {
                e.setCancelled(true);
            }
            Consumer<GuiClick> h = gui.handlers.get(slot);
            if (h != null) {
                h.accept(new GuiClick(e.getPlayer(), slot, e.getClick(), e));
            }
        });
        node.addListener(InventoryCloseEvent.class, e -> {
            if (e.getInventory() instanceof Menu menu) {
                menu.gui.onClose.accept(e.getPlayer());
            }
        });
    }
}
