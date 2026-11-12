package io.github.imnotstable.msc.bot;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.TranslationArgument;

/** Chat components as plain text: literal text as is, translatable text as its key and arguments. */
final class Text {
    private Text() {
    }

    static String plain(Component c) {
        return raw(c).strip();
    }

    /**
     * A menu title. The client turns a translation key into words from its language file, which the bot does not
     * have, so a bare key such as {@code entity.minecraft.villager.farmer} becomes its last part, "Farmer".
     */
    static String title(Component c) {
        if (c instanceof TranslatableComponent t && t.fallback() == null && c.children().isEmpty()) {
            String key = t.key();
            String word = key.substring(key.lastIndexOf('.') + 1).replace('_', ' ');
            return word.isEmpty() ? key : Character.toUpperCase(word.charAt(0)) + word.substring(1);
        }
        return plain(c);
    }

    /** Plain text with its spaces kept, for pieces joined later such as a team prefix. */
    static String raw(Component c) {
        if (c == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        append(c, out);
        return out.toString();
    }

    private static void append(Component c, StringBuilder out) {
        if (c instanceof TextComponent t) {
            out.append(t.content());
        } else if (c instanceof TranslatableComponent t) {
            String fallback = t.fallback();
            out.append(fallback != null ? fallback : t.key());
            if (!t.arguments().isEmpty()) {
                out.append(" (");
                boolean first = true;
                for (TranslationArgument a : t.arguments()) {
                    if (!first) {
                        out.append(", ");
                    }
                    first = false;
                    append(a.asComponent(), out);
                }
                out.append(')');
            }
        }
        for (Component child : c.children()) {
            append(child, out);
        }
    }
}
