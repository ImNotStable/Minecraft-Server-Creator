package io.github.imnotstable.msc.agent.vanilla;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

/** Loads Minecraft's registries once, so tests can parse and compare real block states. */
final class Minecraft {
    private Minecraft() {
    }

    static synchronized void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }
}
