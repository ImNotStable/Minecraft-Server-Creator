package io.github.imnotstable.msc.stdlib.paper;

import io.github.imnotstable.msc.stdlib.ui.Sounds;
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
    private String[] layout;
    private final java.util.List<java.util.Map.Entry<Long, Consumer<Gui>>> refreshers = new java.util.ArrayList<>();
    private net.kyori.adventure.sound.Sound clickSound = Sounds.CLICK.sound();
    private final java.util.List<io.github.imnotstable.msc.stdlib.sched.Scheduler.Task> tasks = new java.util.ArrayList<>();

    /**
     * Colours and sound shared by menus: {@code Gui.chest(3, title).theme(Theme.DARK)}. {@code filler} goes in empty
     * slots, {@code border} on the outer ring; either may be null.
     */
    public record Theme(ItemStack border, ItemStack filler, net.kyori.adventure.sound.Sound click) {
        public static final Theme DEFAULT = new Theme(null, ItemBuilder.pane(Material.GRAY_STAINED_GLASS_PANE),
                Sounds.CLICK.sound());
        public static final Theme DARK = new Theme(ItemBuilder.pane(Material.BLACK_STAINED_GLASS_PANE),
                ItemBuilder.pane(Material.GRAY_STAINED_GLASS_PANE), Sounds.CLICK.sound());
        public static final Theme GOLD = new Theme(ItemBuilder.pane(Material.ORANGE_STAINED_GLASS_PANE),
                ItemBuilder.pane(Material.YELLOW_STAINED_GLASS_PANE), Sounds.CLICK.sound());
        public static final Theme OCEAN = new Theme(ItemBuilder.pane(Material.BLUE_STAINED_GLASS_PANE),
                ItemBuilder.pane(Material.LIGHT_BLUE_STAINED_GLASS_PANE), Sounds.CLICK.sound());
        public static final Theme NATURE = new Theme(ItemBuilder.pane(Material.GREEN_STAINED_GLASS_PANE),
                ItemBuilder.pane(Material.LIME_STAINED_GLASS_PANE), Sounds.CLICK.sound());
        public static final Theme DANGER = new Theme(ItemBuilder.pane(Material.RED_STAINED_GLASS_PANE),
                ItemBuilder.pane(Material.BLACK_STAINED_GLASS_PANE), Sounds.CLICK.sound());
    }

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

    /** Fills the border and empty slots with the theme's panes and uses its click sound. Call after setting items. */
    public Gui theme(Theme theme) {
        if (theme.border() != null) {
            border(theme.border());
        }
        if (theme.filler() != null) {
            fill(theme.filler());
        }
        this.clickSound = theme.click();
        return this;
    }

    /** The sound a click on a button makes ({@link Sounds#CLICK} unless changed); null for none. */
    public Gui clickSound(net.kyori.adventure.sound.Sound sound) {
        this.clickSound = sound;
        return this;
    }

    /**
     * Places items by a picture of the menu, one string per row of 9: each character stands for a button bound with
     * {@code bind}, a space leaves the slot empty.
     *
     * <pre>{@code
     * Gui.chest(3, title).layout(
     *         "#########",
     *         "# a b c #",
     *         "####x####")
     *     .bind('#', ItemBuilder.filler(), c -> {})
     *     .bind('a', kitA, c -> pick(c.player(), "a")) ...
     * }</pre>
     */
    public Gui layout(String... rows) {
        this.layout = rows;
        return this;
    }

    /** Puts {@code item} with {@code onClick} in every slot of {@code symbol} in the {@link #layout}. */
    public Gui bind(char symbol, ItemStack item, Consumer<Click> onClick) {
        if (layout == null) {
            throw new IllegalStateException("Call layout(...) before bind(...)");
        }
        for (int r = 0; r < layout.length; r++) {
            for (int c = 0; c < Math.min(9, layout[r].length()); c++) {
                if (layout[r].charAt(c) == symbol) {
                    set(r * 9 + c, item, onClick);
                }
            }
        }
        return this;
    }

    /** The slots of {@code symbol} in the {@link #layout}, in order: for lists of entries. */
    public java.util.List<Integer> slots(char symbol) {
        java.util.List<Integer> out = new java.util.ArrayList<>();
        for (int r = 0; layout != null && r < layout.length; r++) {
            for (int c = 0; c < Math.min(9, layout[r].length()); c++) {
                if (layout[r].charAt(c) == symbol) {
                    out.add(r * 9 + c);
                }
            }
        }
        return out;
    }

    /** Changes one slot's item in place (viewers see it at once), keeping its click handler. */
    public Gui update(int slot, ItemStack item) {
        inventory.setItem(slot, item);
        return this;
    }

    /**
     * Calls {@code refresh} every {@code ticks} while anyone has the menu open, for live values (a timer, a balance,
     * a cycling icon). Stops when the last viewer closes it.
     */
    public Gui refreshEvery(long ticks, Consumer<Gui> refresh) {
        refreshers.add(new java.util.AbstractMap.SimpleEntry<>(ticks, refresh));
        return this;
    }

    /** Cycles {@code frames} in {@code slot}, one every {@code ticks}, while the menu is open. */
    public Gui animate(int slot, long ticks, java.util.List<ItemStack> frames) {
        int[] i = {0};
        return refreshEvery(ticks, g -> g.update(slot, frames.get(i[0]++ % frames.size())));
    }

    /** Everyone viewing the menu. */
    public java.util.List<Player> viewers() {
        return inventory.getViewers().stream().filter(h -> h instanceof Player).map(h -> (Player) h).toList();
    }

    /**
     * A yes/no screen: green confirm on the left, red cancel on the right, {@code question} in the middle. Closing it
     * counts as cancel.
     */
    public static Gui confirm(Component title, ItemStack question, Consumer<Player> onYes, Consumer<Player> onNo) {
        boolean[] answered = {false};
        Gui gui = chest(3, title);
        gui.set(13, question)
                .set(11, ItemBuilder.of(Material.LIME_CONCRETE).name("<green><bold>Confirm").build(), c -> {
                    answered[0] = true;
                    c.close();
                    onYes.accept(c.player());
                })
                .set(15, ItemBuilder.of(Material.RED_CONCRETE).name("<red><bold>Cancel").build(), c -> {
                    answered[0] = true;
                    c.close();
                    onNo.accept(c.player());
                })
                .onClose(p -> {
                    if (!answered[0]) {
                        answered[0] = true;
                        onNo.accept(p);
                    }
                })
                .theme(Theme.DEFAULT);
        return gui;
    }

    public Gui open(Player player) {
        if (!refreshers.isEmpty() && tasks.isEmpty()) {
            for (var r : refreshers) {
                tasks.add(Stdlib.scheduler().repeat(r.getKey(), r.getKey(), () -> r.getValue().accept(this)));
            }
        }
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
                if (gui.clickSound != null) {
                    p.playSound(gui.clickSound, net.kyori.adventure.sound.Sound.Emitter.self());
                }
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
                if (gui.inventory.getViewers().size() <= 1) {
                    gui.tasks.forEach(io.github.imnotstable.msc.stdlib.sched.Scheduler.Task::cancel);
                    gui.tasks.clear();
                }
                gui.onClose.accept(p);
            }
        }
    }
}
