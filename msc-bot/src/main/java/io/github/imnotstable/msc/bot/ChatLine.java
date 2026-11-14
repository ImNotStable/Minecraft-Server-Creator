package io.github.imnotstable.msc.bot;

import io.github.imnotstable.msc.protocol.Limits;
import java.util.ArrayList;
import java.util.List;

/**
 * What a player can type into chat. A client's chat box holds 256 characters and lets no section sign or control
 * character in; servers disconnect a client that sends more (vanilla reads chat with a 256 limit and kicks for
 * illegal characters, Minestom refuses the packet). The test player keeps to the same limits, so a bad action line
 * is an error of that line, not a kick that reads like a failed play-test.
 */
final class ChatLine {
    /** The chat box's limit, the slash of a command included. */
    static final int MAX = 256;
    /** A long say goes out as at most this many messages, well inside the servers' chat spam limit. */
    static final int MAX_SAY_PARTS = 4;

    private ChatLine() {
    }

    /** The command line, slash included, as typed; never split, since half a command is another command. */
    static String command(String line) {
        characters(line);
        if (line.length() > MAX) {
            throw new IllegalArgumentException("a command is at most " + MAX + " characters, as a player's chat box"
                    + " holds no more (this one has " + line.length() + "); it is never split: " + start(line));
        }
        return line;
    }

    /** The chat messages for a {@code say}: one, or a long text split at spaces into parts of at most 256. */
    static List<String> say(String text) {
        String t = text.strip();
        if (t.isEmpty()) {
            throw new IllegalArgumentException("say needs text: say hello");
        }
        if (t.startsWith("/")) {
            throw new IllegalArgumentException("say text cannot start with / (a client sends that as a command);"
                    + " write the command as its own line: " + start(t));
        }
        characters(t);
        List<String> parts = new ArrayList<>();
        while (t.length() > MAX) {
            int cut = t.lastIndexOf(' ', MAX);
            if (cut <= 0) {
                cut = Limits.head(t, MAX).length(); // one word longer than a message; never half an emoji
            }
            parts.add(t.substring(0, cut).strip());
            t = t.substring(cut).strip();
        }
        parts.add(t);
        if (parts.size() > MAX_SAY_PARTS) {
            throw new IllegalArgumentException("say text is at most about " + MAX * MAX_SAY_PARTS + " characters ("
                    + MAX_SAY_PARTS + " chat messages of " + MAX + "); this one has " + text.strip().length());
        }
        return parts;
    }

    /** Throws for what the test player must not send at all: called again just before a chat packet goes out. */
    static void check(String text) {
        characters(text);
        if (text.length() > MAX) {
            throw new IllegalArgumentException("chat over " + MAX + " characters: " + start(text));
        }
    }

    private static void characters(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '§' || c < ' ' || c == 127) {
                throw new IllegalArgumentException("chat and commands cannot contain § or control characters, as a"
                        + " client cannot type them (" + (c == '§' ? "§" : String.format("U+%04X", (int) c))
                        + " at " + (i + 1) + "); for colours use the command's own syntax, such as JSON text: "
                        + start(text));
            }
        }
    }

    private static String start(String text) {
        return Limits.shorten(text, 63);
    }
}
