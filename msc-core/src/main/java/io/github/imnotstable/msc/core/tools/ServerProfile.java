package io.github.imnotstable.msc.core.tools;

import io.github.imnotstable.msc.core.CoreConfig;
import io.github.imnotstable.msc.core.agent.AgentHub;
import io.github.imnotstable.msc.core.server.Profiler;
import io.github.imnotstable.msc.core.server.ServerManager;
import io.github.imnotstable.msc.core.tool.ToolException;
import io.github.imnotstable.msc.protocol.op.Ops;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** {@code server_profile} (ADR 0090): a Java Flight Recorder profile of the running server, read into a few lines. */
final class ServerProfile {
    static final int MAX_SECONDS = 120;

    private ServerProfile() {
    }

    static String run(CoreConfig config, AgentHub hub, ServerManager server, int seconds)
            throws IOException, InterruptedException {
        ProcessHandle process = server.runningProcess().orElseThrow(() -> new ToolException(
                "the server is not running: server_start, load it (player_test, a game), then server_profile"));
        Path file = config.dataDir().resolve("profile").resolve("server.jfr");
        Profiler.Recording r = Profiler.record(process, seconds, file);
        String tps = null;
        if (hub.current() != null) {
            try {
                double t = hub.call(Ops.SERVER_INFO, new Ops.Empty()).tps();
                tps = t > 0 ? String.format(Locale.ROOT, "TPS %.1f", t) : null;
            } catch (Exception e) {
                // The agent's answer is a bonus: the profile stands without it.
            }
        }
        try {
            return Profiler.report(r, config.serverDir(), server.platform(), tps)
                    + "\nrecording: " + config.workspace().relativize(file).toString().replace('\\', '/')
                    + " (JDK Mission Control opens it)";
        } finally {
            if (Files.size(file) > 200L << 20) {
                Files.delete(file); // shortcut: a huge recording is not kept; profile shorter to keep one
            }
        }
    }
}
