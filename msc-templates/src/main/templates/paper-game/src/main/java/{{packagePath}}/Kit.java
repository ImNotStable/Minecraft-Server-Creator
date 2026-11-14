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
            int amount = parts.length > 1 ? amount(parts[1], line) : 1;
            items.add(new Entry(material, amount));
        }
        return new Kit(List.copyOf(items));
    }

    private static int amount(String text, String line) {
        try {
            int amount = Integer.parseInt(text);
            if (amount >= 1) {
                return amount;
            }
        } catch (NumberFormatException e) {
            // too big for an int
        }
        throw new IllegalArgumentException("kit: amount must be between 1 and " + Integer.MAX_VALUE + " in " + line);
    }

    /** Clears the player's inventory and gives the kit. */
    void give(Player player) {
        player.getInventory().clear();
        for (Entry e : items) {
            player.getInventory().addItem(new ItemStack(e.material(), e.amount()));
        }
    }
}
