package com.example.verifyme;

import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

/** Uses the names CLAUDE.md lists, so one the pinned Paper API does not have fails the build. Never called. */
final class DocNames {
    private DocNames() {
    }

    static Component greet(String playerName) {
        return MiniMessage.miniMessage().deserialize("<green>Hi <player>", Placeholder.unparsed("player", playerName));
    }

    /** The NMS handles CLAUDE.md names; a test that reaches them under MockBukkit fails with NoClassDefFoundError. */
    static Object[] handles(Player player, World world, Entity entity) {
        try {
            return new Object[] {((CraftPlayer) player).getHandle(), ((CraftWorld) world).getHandle(),
                    ((CraftEntity) entity).getHandle()};
        } catch (NoClassDefFoundError e) {
            return new Object[0];
        }
    }
}
