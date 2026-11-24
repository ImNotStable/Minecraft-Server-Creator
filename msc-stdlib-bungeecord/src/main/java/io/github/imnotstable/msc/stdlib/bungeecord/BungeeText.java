package io.github.imnotstable.msc.stdlib.bungeecord;

import io.github.imnotstable.msc.stdlib.text.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.chat.ComponentSerializer;

/**
 * Adventure components (what msc-stdlib's {@link Text} makes from MiniMessage) as BungeeCord chat, which has no
 * Adventure: the component's JSON, read back by BungeeCord's own serializer, so colours, hover and click survive.
 */
public final class BungeeText {
    private BungeeText() {}

    public static BaseComponent bungee(Component component) {
        return ComponentSerializer.deserialize(GsonComponentSerializer.gson().serialize(component));
    }

    /** MiniMessage as BungeeCord chat: {@code BungeeText.mm("<green>Hi <name>", "name", player.getName())}. */
    public static BaseComponent mm(String miniMessage, Object... placeholders) {
        return bungee(Text.mm(miniMessage, placeholders));
    }

    public static void send(CommandSender to, Component component) {
        to.sendMessage(bungee(component));
    }

    public static void send(CommandSender to, String miniMessage, Object... placeholders) {
        to.sendMessage(mm(miniMessage, placeholders));
    }
}
