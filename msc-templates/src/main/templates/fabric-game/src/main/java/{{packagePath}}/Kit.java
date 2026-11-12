package {{packageName}};

import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** The items a player gets on join and on {@code /game kit}. */
public record Kit(List<Entry> items) {
    /** One kit entry: {@code new Kit.Entry(Items.BREAD, 8)}. */
    public record Entry(Item item, int count) {
        public Entry {
            if (count < 1) {
                throw new IllegalArgumentException("kit: count must be at least 1 for " + item);
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
