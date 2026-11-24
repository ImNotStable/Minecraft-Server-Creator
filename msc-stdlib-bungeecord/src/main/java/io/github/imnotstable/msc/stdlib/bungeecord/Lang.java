package io.github.imnotstable.msc.stdlib.bungeecord;

import io.github.imnotstable.msc.stdlib.text.Messages;
import io.github.imnotstable.msc.stdlib.text.Text;
import java.nio.file.Path;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.connection.ProxiedPlayer;

/** {@link Messages} in each player's language (their client setting). Load from your plugin's data folder. */
public final class Lang {
    private final Messages messages;

    private Lang(Messages messages) {
        this.messages = messages;
    }

    public static Lang load(Path dataFolder, String fallback, Map<String, String> defaults) {
        return new Lang(Messages.load(dataFolder.resolve("lang"), fallback, defaults));
    }

    public static Lang of(Messages messages) {
        return new Lang(messages);
    }

    public Component get(CommandSender to, String key, Object... placeholders) {
        String raw = to instanceof ProxiedPlayer p && p.getLocale() != null ? messages.get(p.getLocale(), key)
                : messages.get(key);
        return Text.mm(raw, placeholders);
    }

    public void send(CommandSender to, String key, Object... placeholders) {
        BungeeText.send(to, get(to, key, placeholders));
    }
}
