package io.github.imnotstable.msc.agent.paper;

import io.papermc.paper.ServerBuildInfo;
import org.bukkit.Server;

/** {@link ServerFacts} from Paper's build info, falling back to Bukkit's version string. */
final class PaperServerFacts implements ServerFacts {
    private final Server server;

    PaperServerFacts(Server server) {
        this.server = server;
    }

    @Override
    public String platformVersion() {
        try {
            ServerBuildInfo build = ServerBuildInfo.buildInfo();
            return build.asString(ServerBuildInfo.StringRepresentation.VERSION_SIMPLE);
        } catch (RuntimeException | LinkageError e) {
            return server.getVersion();
        }
    }

    @Override
    public String minecraftVersion() {
        return server.getMinecraftVersion();
    }

    @Override
    public double tps() {
        try {
            return server.getTPS()[0];
        } catch (RuntimeException e) {
            return -1;
        }
    }
}
