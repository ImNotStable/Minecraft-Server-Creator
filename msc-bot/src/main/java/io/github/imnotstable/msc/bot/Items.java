package io.github.imnotstable.msc.bot;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponents;

/**
 * Item names for one Minecraft version. Items travel as registry numbers, and only the components a server changed
 * travel with them, so the vanilla name comes from {@code items/<version>.txt}: the item registry in protocol order,
 * one name per line. It is made from the server's own report, see {@code ItemsTest} for the command.
 */
final class Items {
    private final List<String> names;

    private Items(List<String> names) {
        this.names = names;
    }

    /** The table for a Minecraft version; an unknown version gives numbers only. */
    static Items forVersion(String minecraft) {
        try (InputStream in = Items.class.getResourceAsStream("items/" + minecraft + ".txt")) {
            if (in == null) {
                return new Items(List.of());
            }
            return new Items(new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)).lines().toList());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    int size() {
        return names.size();
    }

    String name(int id) {
        return id >= 0 && id < names.size() ? names.get(id) : "item#" + id;
    }

    /** One item as a player would read it: name, count, and the custom name, model and lore a plugin gave it. */
    String describe(ItemStack item) {
        StringBuilder out = new StringBuilder(name(item.getId()));
        if (item.getAmount() != 1) {
            out.append(" x").append(item.getAmount());
        }
        DataComponents patch = item.getDataComponentsPatch();
        if (patch == null) {
            return out.toString();
        }
        Component named = patch.get(DataComponentTypes.CUSTOM_NAME);
        if (named == null) {
            named = patch.get(DataComponentTypes.ITEM_NAME);
        }
        if (named != null) {
            out.append(" \"").append(Text.plain(named)).append('"');
        }
        Key model = patch.get(DataComponentTypes.ITEM_MODEL);
        if (model != null) {
            out.append(" model ").append(model.asString().toLowerCase(Locale.ROOT));
        }
        List<Component> lore = patch.get(DataComponentTypes.LORE);
        if (lore != null && !lore.isEmpty()) {
            String text = String.join(" / ", lore.stream().map(Text::plain).filter(s -> !s.isEmpty()).toList());
            if (!text.isEmpty()) {
                out.append(" lore \"").append(text.length() > 80 ? text.substring(0, 77) + "..." : text).append('"');
            }
        }
        return out.toString();
    }
}
