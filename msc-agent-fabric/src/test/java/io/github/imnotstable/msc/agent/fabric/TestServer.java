package io.github.imnotstable.msc.agent.fabric;

import io.github.imnotstable.msc.agent.common.AgentServer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** A fake server: an {@code echo} command, a fixed player count, and a record of packs sent. */
final class TestServer implements AgentServer {
    final List<Pack> packsSent = new ArrayList<>();
    int players;

    @Override
    public String platform() {
        return FabricServerAccess.PLATFORM;
    }

    @Override
    public String platformVersion() {
        return "0.20.1-test";
    }

    @Override
    public String minecraftVersion() {
        return "26.3";
    }

    @Override
    public double tps() {
        return 20.0;
    }

    @Override
    public List<String> worldNames() {
        return List.of("world", "world_nether");
    }

    @Override
    public int onlinePlayers() {
        return players;
    }

    @Override
    public boolean runCommand(String line, Consumer<String> feedback) {
        if (line.startsWith("echo ")) {
            feedback.accept("said " + line.substring(5));
            return true;
        }
        feedback.accept("Unknown or incomplete command");
        return false;
    }

    @Override
    public int sendPack(Pack pack) {
        packsSent.add(pack);
        return players;
    }
}
