package io.github.imnotstable.msc.bot;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.TranslationArgument;
import net.kyori.adventure.text.event.ClickEvent;

/**
 * Chat components as plain text: literal text as is, the chat translations as the client shows them, other
 * translatable text as its key and arguments.
 */
final class Text {
    /** The client's English text of the chat translations, which every platform's chat should read alike. */
    private static final java.util.Map<String, String> CHAT = java.util.Map.of(
            "chat.type.text", "<%s> %s",
            "chat.type.announcement", "[%s] %s",
            "chat.type.emote", "* %s %s");

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

    /**
     * The parts of a chat line that run a command when clicked, each with its own text (and its children's), as the
     * player sees it to click.
     */
    static java.util.List<TestPlayer.Clickable> clickables(Component c) {
        java.util.List<TestPlayer.Clickable> out = new java.util.ArrayList<>();
        collect(c, out);
        return out;
    }

    private static void collect(Component c, java.util.List<TestPlayer.Clickable> out) {
        if (c == null) {
            return;
        }
        ClickEvent<?> click = c.clickEvent();
        if (click != null && click.action() == ClickEvent.Action.RUN_COMMAND
                && click.payload() instanceof ClickEvent.Payload.Text command) {
            out.add(new TestPlayer.Clickable(plain(c), command.value()));
            return; // a child inherits its parent's click
        }
        for (Component child : c.children()) {
            collect(child, out);
        }
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
        } else if (c instanceof TranslatableComponent t && CHAT.containsKey(t.key()) && t.fallback() == null) {
            // Chat lines some servers (Minestom) send as translations: render them as the client does.
            String[] parts = CHAT.get(t.key()).split("%s", -1);
            for (int i = 0; i < parts.length; i++) {
                out.append(parts[i]);
                if (i < parts.length - 1 && i < t.arguments().size()) {
                    append(t.arguments().get(i).asComponent(), out);
                }
            }
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
