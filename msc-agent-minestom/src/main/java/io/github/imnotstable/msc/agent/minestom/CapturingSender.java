package io.github.imnotstable.msc.agent.minestom;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.kyori.adventure.identity.Identity;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.minestom.server.command.CommandSender;
import net.minestom.server.tag.TagHandler;

/** A console-like command sender that keeps every message a command sends it, as plain text. */
final class CapturingSender implements CommandSender {
    private final TagHandler tags = TagHandler.newHandler();
    private final List<String> output = Collections.synchronizedList(new ArrayList<>());

    @Override
    public void sendMessage(Component message) {
        output.add(PlainTextComponentSerializer.plainText().serialize(message));
    }

    @Override
    public TagHandler tagHandler() {
        return tags;
    }

    @Override
    public Identity identity() {
        return Identity.nil();
    }

    List<String> output() {
        return List.copyOf(output);
    }
}
