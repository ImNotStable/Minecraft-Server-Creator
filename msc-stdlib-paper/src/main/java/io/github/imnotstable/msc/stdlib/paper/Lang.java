package io.github.imnotstable.msc.stdlib.paper;

import io.github.imnotstable.msc.stdlib.text.Messages;
import io.github.imnotstable.msc.stdlib.text.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Map;

/**
 * {@link Messages} sent in each player's own language (their client setting), with the default language for the
 * console. Load once in {@code onEnable}:
 *
 * <pre>{@code
 * Lang lang = Lang.load(this, "en", Map.of("shop.bought", "<green>You bought <item>!", "shop.poor", "<red>Too poor"));
 * lang.send(player, "shop.bought", "item", "a sword");
 * }</pre>
 * Owners edit and add languages in {@code plugins/<name>/lang/messages_<lang>.properties}.
 */
public final class Lang {
    private final Messages messages;

    private Lang(Messages messages) {
        this.messages = messages;
    }

    public static Lang load(org.bukkit.plugin.Plugin plugin, String fallback, Map<String, String> defaults) {
        return new Lang(Messages.load(plugin.getDataFolder().toPath().resolve("lang"), fallback, defaults));
    }

    public static Lang of(Messages messages) {
        return new Lang(messages);
    }

    public Component get(CommandSender to, String key, Object... placeholders) {
        String raw = to instanceof Player p ? messages.get(p.locale(), key) : messages.get(key);
        return Text.mm(raw, placeholders);
    }

    public void send(CommandSender to, String key, Object... placeholders) {
        to.sendMessage(get(to, key, placeholders));
    }

    public Messages messages() {
        return messages;
    }
}
