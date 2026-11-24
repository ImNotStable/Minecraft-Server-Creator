package io.github.imnotstable.msc.stdlib.vanilla;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

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
 * A server-side chest menu (vanilla clients see a normal chest). Items cannot be taken or put in except in
 * {@link #editable} slots; clicks go to the slot's handler.
 *
 * <pre>{@code
 * Gui.chest(3, Component.literal("Shop"))
 *         .fill(ItemBuilder.pane(myPane))
 *         .set(13, ItemBuilder.of(Items.DIAMOND).name(Component.literal("Buy")).build(), click -> buy(click.player()))
 *         .open(player);
 * }</pre>
 */
public final class Gui {
    private static final List<MenuType<ChestMenu>> TYPES = List.of(MenuType.GENERIC_9x1, MenuType.GENERIC_9x2,
            MenuType.GENERIC_9x3, MenuType.GENERIC_9x4, MenuType.GENERIC_9x5, MenuType.GENERIC_9x6);

    private final int rows;
    private final Component title;
    private final SimpleContainer container;
    private final Map<Integer, Consumer<GuiClick>> handlers = new HashMap<>();
    private final Set<Integer> editable = new HashSet<>();
    private Consumer<ServerPlayer> onClose = p -> {};

    /** A click in the menu: who, which slot, the button (0 left, 1 right) and the kind of click. */
    public record GuiClick(ServerPlayer player, int slot, int button, ContainerInput input) {
        public void close() {
            Stdlib.scheduler().sync().execute(player::closeContainer);
        }
    }

    private Gui(int rows, Component title) {
        if (rows < 1 || rows > 6) {
            throw new IllegalArgumentException("A chest menu has 1 to 6 rows: " + rows);
        }
        this.rows = rows;
        this.title = title;
        this.container = new SimpleContainer(rows * 9);
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
        container.setItem(slot, item);
        handlers.put(slot, onClick);
        return this;
    }

    public Gui set(int row, int column, ItemStack item, Consumer<GuiClick> onClick) {
        return set(row * 9 + column, item, onClick);
    }

    public Gui fill(ItemStack item) {
        for (int i = 0; i < container.getContainerSize(); i++) {
            if (container.getItem(i).isEmpty()) {
                container.setItem(i, item.copy());
            }
        }
        return this;
    }

    public Gui border(ItemStack item) {
        for (int i = 0; i < container.getContainerSize(); i++) {
            int r = i / 9, c = i % 9;
            if ((r == 0 || r == rows - 1 || c == 0 || c == 8) && container.getItem(i).isEmpty()) {
                container.setItem(i, item.copy());
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

    public Gui onClose(Consumer<ServerPlayer> action) {
        this.onClose = action;
        return this;
    }

    public Gui clear() {
        container.clearContent();
        handlers.clear();
        return this;
    }

    public ItemStack item(int slot) {
        return container.getItem(slot);
    }

    public Gui open(ServerPlayer player) {
        player.openMenu(new SimpleMenuProvider((id, inventory, p) -> new Menu(id, inventory), title));
        return this;
    }

    /**
     * Whether a click goes through to vanilla (moves items): only in editable slots, and in the player's own inventory
     * except for clicks that move items into the menu (shift-click, collect all).
     */
    boolean allows(int slot, ContainerInput input) {
        if (slot >= 0 && slot < container.getContainerSize()) {
            return editable.contains(slot);
        }
        return input != ContainerInput.QUICK_MOVE && input != ContainerInput.PICKUP_ALL;
    }

    /** Runs the handler of a click on {@code slot}, if it has one. */
    void dispatch(ServerPlayer player, int slot, int button, ContainerInput input) {
        Consumer<GuiClick> h = handlers.get(slot);
        if (h != null) {
            h.accept(new GuiClick(player, slot, button, input));
        }
    }

    private final class Menu extends ChestMenu {
        Menu(int id, Inventory inventory) {
            super(TYPES.get(rows - 1), id, inventory, container, rows);
        }

        @Override
        public void clicked(int slot, int button, ContainerInput input, Player player) {
            if (allows(slot, input)) {
                super.clicked(slot, button, input, player);
            } else {
                sendAllDataToRemote(); // undo what the client already showed
            }
            if (player instanceof ServerPlayer sp && slot >= 0 && slot < container.getContainerSize()) {
                dispatch(sp, slot, button, input);
            }
        }

        @Override
        public ItemStack quickMoveStack(Player player, int slot) {
            return ItemStack.EMPTY;
        }

        @Override
        public boolean stillValid(Player player) {
            return true;
        }

        @Override
        public void removed(Player player) {
            super.removed(player);
            if (player instanceof ServerPlayer sp) {
                onClose.accept(sp);
            }
        }
    }
}
