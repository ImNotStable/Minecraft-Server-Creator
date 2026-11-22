package io.github.imnotstable.msc.stdlib.paper;

import org.bukkit.GameMode;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;

import java.util.Map;

/** Everyday player chores in one call. */
public final class Players {
    private Players() {}

    /** Gives items, dropping what does not fit at the player's feet. Returns true when everything fit. */
    public static boolean give(Player player, ItemStack... items) {
        Map<Integer, ItemStack> left = player.getInventory().addItem(items);
        left.values().forEach(i -> player.getWorld().dropItemNaturally(player.getLocation(), i));
        return left.isEmpty();
    }

    /** Full health, food and saturation, no fire, no fall distance. */
    public static void heal(Player player) {
        var max = player.getAttribute(Attribute.MAX_HEALTH);
        player.setHealth(max == null ? 20 : max.getValue());
        player.setFoodLevel(20);
        player.setSaturation(20);
        player.setFireTicks(0);
        player.setFallDistance(0);
    }

    /** Ready for a fresh round: healed, no effects, empty inventory, no XP, given game mode. */
    public static void reset(Player player, GameMode mode) {
        heal(player);
        player.getActivePotionEffects().stream().map(PotionEffect::getType).forEach(player::removePotionEffect);
        player.getInventory().clear();
        player.setLevel(0);
        player.setExp(0);
        player.setGameMode(mode);
    }

    /** Free slots in the main inventory. */
    public static int freeSlots(Player player) {
        int free = 0;
        for (ItemStack i : player.getInventory().getStorageContents()) {
            if (i == null || i.isEmpty()) {
                free++;
            }
        }
        return free;
    }
}
