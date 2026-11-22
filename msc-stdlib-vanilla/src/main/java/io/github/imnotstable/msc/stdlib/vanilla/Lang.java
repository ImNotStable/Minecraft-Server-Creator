package io.github.imnotstable.msc.stdlib.vanilla;

import io.github.imnotstable.msc.stdlib.text.Messages;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Path;
import java.util.Map;

/**
 * {@link Messages} in each player's client language, as plain text with {@code <name>} placeholders filled in.
 * {@code Lang lang = Lang.load(FabricLoader.getInstance().getConfigDir().resolve("mymod/lang"), "en", Map.of(...));}
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

    /** The text for {@code language} with each {@code <name>} replaced by its value; tags are not parsed on Fabric. */
    public String get(String language, String key, Object... placeholders) {
        String s = messages.get(language, key);
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            s = s.replace("<" + placeholders[i] + ">", String.valueOf(placeholders[i + 1]));
        }
        return s;
    }

    public void send(ServerPlayer player, String key, Object... placeholders) {
        player.sendSystemMessage(Component.literal(get(player.clientInformation().language(), key, placeholders)));
    }
}
