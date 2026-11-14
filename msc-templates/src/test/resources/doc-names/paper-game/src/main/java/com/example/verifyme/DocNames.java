package com.example.verifyme;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

/** Uses the names CLAUDE.md lists, so one the pinned Paper API does not have fails the build. Never called. */
final class DocNames {
    private DocNames() {
    }

    static Component greet(String playerName) {
        return MiniMessage.miniMessage().deserialize("<green>Hi <player>", Placeholder.unparsed("player", playerName));
    }
}
