package io.github.imnotstable.msc.agent.common;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** A fake server: {@code echo} and {@code flood} commands, a fixed player count, and a record of packs sent. */
final class FakeServer implements AgentServer {
    final List<Pack> packsSent = new ArrayList<>();
    int players;

    @Override
    public String platform() {
        return "test";
    }

    @Override
    public String platformVersion() {
        return "1.0-test";
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
        if (line.startsWith("flood ")) { // flood <lines> <chars per line>
            String[] args = line.split(" ");
            String text = "f".repeat(Integer.parseInt(args[2]));
            for (int i = Integer.parseInt(args[1]); i > 0; i--) {
                feedback.accept(text);
            }
            return true;
        }
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
