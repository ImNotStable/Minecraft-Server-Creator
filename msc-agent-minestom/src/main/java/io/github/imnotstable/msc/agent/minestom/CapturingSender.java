package io.github.imnotstable.msc.agent.minestom;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.minestom.server.command.ConsoleSender;

/**
 * The console, except that it keeps every message a command sends it, as plain text, instead of
 * logging it. It is a {@link ConsoleSender} so console-only commands run.
 */
final class CapturingSender extends ConsoleSender {
    private final List<String> output = Collections.synchronizedList(new ArrayList<>());

    @Override
    public void sendMessage(String message) {
        output.add(message);
    }

    @Override
    public void sendMessage(Component message) {
        output.add(PlainTextComponentSerializer.plainText().serialize(message));
    }

    List<String> output() {
        return List.copyOf(output);
    }
}
