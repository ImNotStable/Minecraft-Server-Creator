package {{packageName}};

import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** The items a player gets on join and on {@code /game kit}. */
public record Kit(List<Entry> items) {
    /** The most of one item a kit entry gives: a full inventory, 36 stacks of 64. The same in every game template. */
    public static final int MAX_AMOUNT = 36 * 64;

    /** One kit entry: {@code new Kit.Entry(Items.BREAD, 8)}. */
    public record Entry(Item item, int count) {
        public Entry {
            if (count < 1 || count > MAX_AMOUNT) {
                throw new IllegalArgumentException("kit: count must be a whole number from 1 to " + MAX_AMOUNT + " for "
                        + item + ", got " + count);
            }
        }
    }

    public Kit {
        items = List.copyOf(items);
    }

    public static Kit of(Entry... items) {
        return new Kit(List.of(items));
    }

    /** Clears the player's inventory and gives the kit. */
    void give(ServerPlayer player) {
        player.getInventory().clearContent();
        for (Entry e : items) {
            player.getInventory().add(new ItemStack(e.item(), e.count()));
        }
    }
}
