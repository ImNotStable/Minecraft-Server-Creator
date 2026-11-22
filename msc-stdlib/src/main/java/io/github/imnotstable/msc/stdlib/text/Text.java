package io.github.imnotstable.msc.stdlib.text;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentLike;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.ArrayList;
import java.util.List;

/**
 * MiniMessage with placeholders in one call, for platforms with Adventure (Paper, Minestom, Velocity; it is not
 * bundled here: each adapter brings it or the server ships it).
 *
 * <pre>{@code
 * player.sendMessage(Text.mm("<green>Welcome <name>, you have <coins> coins", "name", player.getName(), "coins", 5));
 * }</pre>
 */
public final class Text {
    private Text() {}

    /**
     * Parses MiniMessage. {@code placeholders} are name, value pairs: a {@link ComponentLike} value is inserted as is,
     * anything else as plain text (so player input cannot inject tags).
     */
    public static Component mm(String miniMessage, Object... placeholders) {
        if (placeholders.length % 2 != 0) {
            throw new IllegalArgumentException("Placeholders come in name, value pairs");
        }
        List<TagResolver> resolvers = new ArrayList<>();
        for (int i = 0; i < placeholders.length; i += 2) {
            String name = String.valueOf(placeholders[i]);
            Object v = placeholders[i + 1];
            resolvers.add(v instanceof ComponentLike c ? Placeholder.component(name, c)
                    : Placeholder.unparsed(name, String.valueOf(v)));
        }
        return MiniMessage.miniMessage().deserialize(miniMessage, TagResolver.resolver(resolvers));
    }

    /** Like {@link #mm} without the italics Minecraft puts on item names and lore. */
    public static Component item(String miniMessage, Object... placeholders) {
        return mm(miniMessage, placeholders).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    /** Escapes tags in text before it goes into a MiniMessage string. */
    public static String escape(String text) {
        return MiniMessage.miniMessage().escapeTags(text);
    }

    /** The text without formatting. */
    public static String plain(Component c) {
        return PlainTextComponentSerializer.plainText().serialize(c);
    }

    /** {@code &a} style colour codes, for old configs. */
    public static Component legacy(String ampersandCodes) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(ampersandCodes);
    }
}
