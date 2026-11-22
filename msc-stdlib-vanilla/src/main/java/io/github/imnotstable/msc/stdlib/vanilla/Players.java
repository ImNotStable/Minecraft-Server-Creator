package io.github.imnotstable.msc.stdlib.vanilla;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;

/** Everyday player chores in one call. */
public final class Players {
    private Players() {}

    /** Gives items, dropping what does not fit at the player's feet; true when everything fit. */
    public static boolean give(ServerPlayer player, ItemStack... items) {
        boolean all = true;
        for (ItemStack i : items) {
            ItemStack copy = i.copy();
            if (!player.getInventory().add(copy) || !copy.isEmpty()) {
                all = false;
                player.drop(copy, false, net.minecraft.util.Prediction.SERVER_ONLY);
            }
        }
        return all;
    }

    public static void heal(ServerPlayer player) {
        player.setHealth(player.getMaxHealth());
        player.getFoodData().setFoodLevel(20);
        player.getFoodData().setSaturation(20);
        player.clearFire();
    }

    /** Healed, no effects, empty inventory, no XP, given game mode. */
    public static void reset(ServerPlayer player, GameType mode) {
        heal(player);
        player.removeAllEffects();
        player.getInventory().clearContent();
        player.setExperienceLevels(0);
        player.setExperiencePoints(0);
        player.setGameMode(mode);
    }
}
