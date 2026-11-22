package io.github.imnotstable.msc.stdlib.paper;

import io.github.imnotstable.msc.stdlib.text.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * A chest menu. Items cannot be taken or put in except in {@link #editable} slots; clicks go to the slot's handler.
 * Needs {@link Stdlib#init}.
 *
 * <pre>{@code
 * Gui.chest(3, Text.mm("<gold>Shop"))
 *         .fill(ItemBuilder.filler())
 *         .set(13, ItemBuilder.of(Material.DIAMOND).name("<aqua>Buy diamond").build(), click -> buy(click.player()))
 *         .open(player);
 * Gui.paged(6, Text.mm("Warps"), warps, w -> ItemBuilder.of(Material.ENDER_PEARL).name(w.name()).build(),
 *         (w, click) -> click.player().teleport(w.location())).open(player);
 * }</pre>
 */
public final class Gui implements InventoryHolder {
    private final Inventory inventory;
    private final Map<Integer, Consumer<Click>> handlers = new HashMap<>();
    private final Set<Integer> editable = new HashSet<>();
    private Consumer<Player> onClose = p -> {};

    /** A click in the menu: who, which slot, how (left, right, shift...), and the event for anything else. */
    public record Click(Player player, int slot, ClickType type, InventoryClickEvent event) {
        /** Closes the menu on the next tick (closing inside a click event is not allowed). */
        public void close() {
            Stdlib.scheduler().later(1, player::closeInventory);
        }
    }

    private Gui(int rows, Component title) {
        if (rows < 1 || rows > 6) {
            throw new IllegalArgumentException("A chest menu has 1 to 6 rows: " + rows);
        }
        this.inventory = Bukkit.createInventory(this, rows * 9, title);
    }

    public static Gui chest(int rows, Component title) {
        return new Gui(rows, title);
    }

    /**
     * A menu over a list, a page at a time: every row but the last holds entries, the last has back and next arrows.
     */
    public static <T> Gui paged(int rows, Component title, List<T> entries, Function<T, ItemStack> icon,
                                BiConsumer<T, Click> onClick) {
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
                gui.set(perPage, ItemBuilder.of(Material.ARROW).name("<yellow>Back").build(), c -> {
                    page[0]--;
                    render[0].run();
                });
            }
            if (page[0] < pages - 1) {
                gui.set(perPage + 8, ItemBuilder.of(Material.ARROW).name("<yellow>Next").build(), c -> {
                    page[0]++;
                    render[0].run();
                });
            }
            gui.set(perPage + 4, ItemBuilder.of(Material.PAPER)
                    .name("<gray>Page <p>/<n>", "p", page[0] + 1, "n", pages).build());
        };
        render[0].run();
        return gui;
    }

    /** A slot that shows an item and does nothing when clicked. */
    public Gui set(int slot, ItemStack item) {
        return set(slot, item, c -> {});
    }

    public Gui set(int slot, ItemStack item, Consumer<Click> onClick) {
        inventory.setItem(slot, item);
        handlers.put(slot, onClick);
        return this;
    }

    /** Slot from row and column, both from 0. */
    public Gui set(int row, int column, ItemStack item, Consumer<Click> onClick) {
        return set(row * 9 + column, item, onClick);
    }

    /** Puts {@code item} in every empty slot. */
    public Gui fill(ItemStack item) {
        for (int i = 0; i < inventory.getSize(); i++) {
            if (inventory.getItem(i) == null) {
                inventory.setItem(i, item);
            }
        }
        return this;
    }

    /** Puts {@code item} in the empty slots of the outer ring. */
    public Gui border(ItemStack item) {
        int rows = inventory.getSize() / 9;
        for (int i = 0; i < inventory.getSize(); i++) {
            int r = i / 9, c = i % 9;
            if ((r == 0 || r == rows - 1 || c == 0 || c == 8) && inventory.getItem(i) == null) {
                inventory.setItem(i, item);
            }
        }
        return this;
    }

    /** Slots players may take from and put into (an input slot, a reward slot). */
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

    /** Removes every item and handler. */
    public Gui clear() {
        inventory.clear();
        handlers.clear();
        return this;
    }

    public Gui open(Player player) {
        Stdlib.plugin();
        player.openInventory(inventory);
        return this;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    /** Registered by {@link Stdlib#init}. */
    static final class Listener implements org.bukkit.event.Listener {
        @EventHandler(ignoreCancelled = true)
        public void click(InventoryClickEvent e) {
            if (!(e.getInventory().getHolder(false) instanceof Gui gui) || !(e.getWhoClicked() instanceof Player p)) {
                return;
            }
            boolean top = e.getClickedInventory() == gui.inventory;
            if (!top) {
                // Shift-clicks and collecting would move items into the menu.
                if (e.isShiftClick() || e.getClick() == ClickType.DOUBLE_CLICK) {
                    e.setCancelled(true);
                }
                return;
            }
            if (!gui.editable.contains(e.getSlot())) {
                e.setCancelled(true);
            }
            Consumer<Click> h = gui.handlers.get(e.getSlot());
            if (h != null) {
                h.accept(new Click(p, e.getSlot(), e.getClick(), e));
            }
        }

        @EventHandler(ignoreCancelled = true)
        public void drag(InventoryDragEvent e) {
            if (e.getInventory().getHolder(false) instanceof Gui gui
                    && e.getRawSlots().stream().anyMatch(s -> s < gui.inventory.getSize() && !gui.editable.contains(s))) {
                e.setCancelled(true);
            }
        }

        @EventHandler
        public void close(InventoryCloseEvent e) {
            if (e.getInventory().getHolder(false) instanceof Gui gui && e.getPlayer() instanceof Player p) {
                gui.onClose.accept(p);
            }
        }
    }
}
