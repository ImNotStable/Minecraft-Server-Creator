package io.github.imnotstable.msc.stdlib.minestom;

import net.minestom.server.entity.GameMode;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.attribute.Attribute;
import net.minestom.server.item.ItemStack;

/** Everyday player chores in one call. */
public final class Players {
    private Players() {}

    /** Gives items; true when all fit (Minestom has no item drops of its own, so the rest is not given). */
    public static boolean give(Player player, ItemStack... items) {
        boolean all = true;
        for (ItemStack i : items) {
            all &= player.getInventory().addItemStack(i);
        }
        return all;
    }

    public static void heal(Player player) {
        player.setHealth((float) player.getAttributeValue(Attribute.MAX_HEALTH));
        player.setFood(20);
        player.setFoodSaturation(20);
        player.setFireTicks(0);
    }

    /** Healed, no effects, empty inventory, no XP, given game mode. */
    public static void reset(Player player, GameMode mode) {
        heal(player);
        player.clearEffects();
        player.getInventory().clear();
        player.setLevel(0);
        player.setExp(0);
        player.setGameMode(mode);
    }
}
