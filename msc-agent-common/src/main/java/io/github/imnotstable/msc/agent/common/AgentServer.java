package io.github.imnotstable.msc.agent.common;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * What {@link ServerOps} needs from the running server. Every method except {@link #platform()}
 * and the version getters runs on the main thread.
 */
public interface AgentServer {
    /** The platform id the core sees, for example {@code paper}. */
    String platform();

    String platformVersion();

    String minecraftVersion();

    /** Ticks per second over recent ticks, or -1 when unknown. */
    double tps();

    /** Every world's name, main world first. */
    List<String> worldNames();

    int onlinePlayers();

    /**
     * Run a command line (no leading slash) with full permissions, passing every line of text the
     * command sends back to {@code feedback}.
     *
     * @return whether the command was found and reported success
     */
    boolean runCommand(String line, Consumer<String> feedback);

    /** Send a resource pack to every online player, replacing what they have, and return how many got it. */
    int sendPack(Pack pack);

    /** A resource pack push. The id is stable per URL, so a re-sent pack replaces the old one. */
    record Pack(UUID id, String url, String sha1, boolean required) {
    }
}
