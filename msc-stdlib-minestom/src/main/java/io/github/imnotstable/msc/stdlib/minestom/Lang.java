package io.github.imnotstable.msc.stdlib.minestom;

import io.github.imnotstable.msc.stdlib.text.Messages;
import io.github.imnotstable.msc.stdlib.text.Text;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.minestom.server.entity.Player;

import java.nio.file.Path;
import java.util.Map;

/**
 * {@link Messages} in each player's language. {@code Lang lang = Lang.load(Path.of("lang"), "en", Map.of(...));}
 * then {@code lang.send(player, "shop.bought", "item", "a sword")}.
 */
public final class Lang {
    private final Messages messages;

    private Lang(Messages messages) {
        this.messages = messages;
    }

    public static Lang load(Path dir, String fallback, Map<String, String> defaults) {
        return new Lang(Messages.load(dir, fallback, defaults));
    }

    public static Lang of(Messages messages) {
        return new Lang(messages);
    }

    public Component get(Audience to, String key, Object... placeholders) {
        String raw = to instanceof Player p && p.getLocale() != null ? messages.get(p.getLocale(), key) : messages.get(key);
        return Text.mm(raw, placeholders);
    }

    public void send(Audience to, String key, Object... placeholders) {
        to.sendMessage(get(to, key, placeholders));
    }
}
