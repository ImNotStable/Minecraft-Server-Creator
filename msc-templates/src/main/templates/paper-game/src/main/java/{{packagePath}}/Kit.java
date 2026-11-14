package {{packageName}};

import java.util.List;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** The items a player gets on join and on {@code /game kit}, read from config as {@code item} or {@code item:amount}. */
public record Kit(List<Entry> items) {
    public record Entry(Material material, int amount) {
        public Entry {
            if (amount < 1) {
                throw new IllegalArgumentException("kit: amount must be at least 1 for " + material);
            }
        }
    }

    public Kit {
        items = List.copyOf(items);
    }

    /** Clears the player's inventory and gives the kit. */
    void give(Player player) {
        player.getInventory().clear();
        for (Entry e : items) {
            player.getInventory().addItem(new ItemStack(e.material(), e.amount()));
        }
    }
}
