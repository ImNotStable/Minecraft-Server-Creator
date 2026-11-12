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
        if (c == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        append(c, out);
        return out.toString().strip();
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
