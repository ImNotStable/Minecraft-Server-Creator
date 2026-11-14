package io.github.imnotstable.msc.bench.check;

import io.github.imnotstable.msc.process.rcon.RconClient;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/** What checks can reach: the workspace, placeholder values and (lazily) the server console. */
public interface CheckContext {

    Path workspace();

    /** Values for {@code {name}} placeholders in check arguments, such as {@code gradlew}. */
    Map<String, String> placeholders();

    /** An RCON connection to the task's server, starting the server first if it is not running. */
    RconClient rcon() throws IOException;

    /**
     * Starts a process for a check. A run tracks it, so a run cut short (Ctrl-C) kills it with the agent and server
     * instead of leaving it behind.
     */
    default Process start(ProcessBuilder builder) throws IOException {
        return builder.start();
    }
}
