package io.github.imnotstable.msc.core.tools;

import io.github.imnotstable.msc.core.Core;
import io.github.imnotstable.msc.core.CoreConfig;
import io.github.imnotstable.msc.core.agent.AgentHub;
import io.github.imnotstable.msc.core.metrics.ToolLog;
import io.github.imnotstable.msc.core.server.ServerManager;
import io.github.imnotstable.msc.core.tool.Schema;
import io.github.imnotstable.msc.core.tool.ToolRegistry;
import io.github.imnotstable.msc.protocol.transport.CoreServer;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.temporal.ChronoUnit;

/** Health and usage tools. */
public final class MetaTools {
    private MetaTools() {
    }

    public static void register(ToolRegistry registry, CoreConfig config, AgentHub hub, ServerManager server,
                                ToolLog log, ApiTools api) {
        registry.add("msc_status", "Where things stand: workspace, platform, server process, agent connection, API index.",
                Schema.of(), args -> {
                    StringBuilder out = new StringBuilder();
                    out.append("msc-core ").append(Core.VERSION).append('\n');
                    out.append("workspace: ").append(config.workspace()).append('\n');
                    out.append("platform: ").append(server.platform().id()).append('\n');
                    out.append("server: ").append(server.running() ? "running" : server.serverJar() != null
                            ? "stopped" : "not set up").append(" (").append(config.serverDir()).append(")\n");
                    CoreServer.AgentSession s = hub.current();
                    out.append("agent: ");
                    if (s == null) {
                        out.append("not connected");
                    } else {
                        out.append(s.agent().platform()).append(' ').append(s.agent().platformVersion())
                                .append(", minecraft ").append(s.agent().minecraftVersion()).append(", ")
                                .append(s.agent().operations().size()).append(" operations");
                    }
                    out.append("\napi index: ").append(api.status());
                    return out.toString();
                });
        registry.add("msc_stats", "This session's tool calls: count, errors, time and result tokens per tool.",
                Schema.of(), args -> log.summary());
    }

    /**
     * When the running msc-core jar was built, so a stale install shows: every build reports the same version.
     * Empty when not running from a jar (tests, IDE).
     */
    static String built() {
        try {
            var source = MetaTools.class.getProtectionDomain().getCodeSource();
            Path jar = source == null ? null : Path.of(source.getLocation().toURI());
            if (jar == null || !Files.isRegularFile(jar)) {
                return "";
            }
            var time = Files.getLastModifiedTime(jar).toInstant().truncatedTo(ChronoUnit.MINUTES);
            return " (built " + time + ", " + jar.getParent() + ")";
        } catch (IOException | URISyntaxException | RuntimeException e) {
            return "";
        }
    }
}
