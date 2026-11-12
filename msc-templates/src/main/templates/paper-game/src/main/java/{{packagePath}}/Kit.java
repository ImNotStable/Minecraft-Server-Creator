package {{packageName}};

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** The items a player gets on join and on {@code /game kit}, read from config as {@code item} or {@code item:amount}. */
public record Kit(List<Entry> items) {
    public record Entry(Material material, int amount) {
    }

    static Kit parse(List<String> lines) {
        List<Entry> items = new ArrayList<>();
        for (String line : lines) {
            String[] parts = line.strip().split(":(?=\\d+$)");
            Material material = Material.matchMaterial(parts[0]);
            if (material == null || !material.isItem()) {
                throw new IllegalArgumentException("kit: unknown item " + parts[0]);
            }
            int amount = parts.length > 1 ? Integer.parseInt(parts[1]) : 1;
            if (amount < 1) {
                throw new IllegalArgumentException("kit: amount must be at least 1 in " + line);
            }
            items.add(new Entry(material, amount));
        }
        return new Kit(List.copyOf(items));
    }

    /** Clears the player's inventory and gives the kit. */
    void give(Player player) {
        player.getInventory().clear();
        for (Entry e : items) {
            player.getInventory().addItem(new ItemStack(e.material(), e.amount()));
        }
    }
}
