package io.github.imnotstable.msc.stdlib.velocity;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import io.github.imnotstable.msc.stdlib.text.Messages;
import io.github.imnotstable.msc.stdlib.text.Text;
import net.kyori.adventure.text.Component;

import java.nio.file.Path;
import java.util.Map;

/** {@link Messages} in each player's language (their client setting). Load from your plugin's data directory. */
public final class Lang {
    private final Messages messages;

    private Lang(Messages messages) {
        this.messages = messages;
    }

    public static Lang load(Path dataDirectory, String fallback, Map<String, String> defaults) {
        return new Lang(Messages.load(dataDirectory.resolve("lang"), fallback, defaults));
    }

    public static Lang of(Messages messages) {
        return new Lang(messages);
    }

    public Component get(CommandSource to, String key, Object... placeholders) {
        String raw = to instanceof Player p && p.getEffectiveLocale() != null ? messages.get(p.getEffectiveLocale(), key)
                : messages.get(key);
        return Text.mm(raw, placeholders);
    }

    public void send(CommandSource to, String key, Object... placeholders) {
        to.sendMessage(get(to, key, placeholders));
    }
}
