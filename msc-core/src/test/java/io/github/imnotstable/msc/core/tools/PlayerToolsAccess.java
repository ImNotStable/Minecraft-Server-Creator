package io.github.imnotstable.msc.core.tools;

import io.github.imnotstable.msc.bot.Bot;

/** Lets core-package tests hand PlayerTools a joined player without a Minecraft server. */
public final class PlayerToolsAccess {
    private PlayerToolsAccess() {
    }

    public static Bot adopt(PlayerTools players, Bot bot) {
        return players.adopt(bot);
    }

    public static boolean joined(PlayerTools players) {
        return players.joined();
    }
}
