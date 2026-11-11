package io.github.imnotstable.msc.agent.minestom;

import net.minestom.server.Git;
import net.minestom.server.ping.Status;

/** {@link ServerFacts} from Minestom's build info and the agent's own tick counter. */
final class MinestomServerFacts implements ServerFacts {
    private final TickRate ticks;

    MinestomServerFacts(TickRate ticks) {
        this.ticks = ticks;
    }

    /** Minestom's version (for example {@code 2026.10.07-26.2}), or its commit when the version is unknown. */
    @Override
    public String platformVersion() {
        String version = Git.version();
        if (known(version)) {
            return version;
        }
        String commit = Git.commit();
        return known(commit) ? commit : "unknown";
    }

    private static boolean known(String s) {
        return s != null && !s.isBlank() && !s.startsWith("&");
    }

    @Override
    public String minecraftVersion() {
        return Status.VersionInfo.DEFAULT.name();
    }

    @Override
    public double tps() {
        return ticks.tps(System.nanoTime());
    }
}
