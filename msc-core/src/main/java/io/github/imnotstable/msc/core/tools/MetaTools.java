package io.github.imnotstable.msc.core.tools;

import io.github.imnotstable.msc.core.Core;
import io.github.imnotstable.msc.core.CoreConfig;
import io.github.imnotstable.msc.core.SetupCheck;
import io.github.imnotstable.msc.core.agent.AgentHub;
import io.github.imnotstable.msc.core.metrics.ToolLog;
import io.github.imnotstable.msc.core.server.ServerManager;
import io.github.imnotstable.msc.core.tool.Budget;
import io.github.imnotstable.msc.core.tool.Schema;
import io.github.imnotstable.msc.core.tool.ToolException;
import io.github.imnotstable.msc.core.tool.ToolRegistry;
import io.github.imnotstable.msc.protocol.transport.CoreServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Health and usage tools. */
public final class MetaTools {
    /** The guides msc_status guide= serves (ADR 0068): read on demand, so they cost nothing until needed. */
    static final List<String> GUIDES = List.of("build", "textures", "pack", "ux");

    private MetaTools() {
    }

    /** A guide page's size: the result cap less room for the line that points to the next page. */
    static final int GUIDE_PAGE_CHARS = Budget.DEFAULT_CHARS - 200;

    static String guide(String topic) {
        return guide(topic, 1);
    }

    /**
     * Page {@code page} of a guide (ADR 0079). A guide longer than a result's cap is cut into pages at its section
     * headings (an upper-case line after a blank one), never mid-section, so no part is cut off unseen: each page but
     * the last ends by naming the next.
     */
    static String guide(String topic, int page) {
        if (!GUIDES.contains(topic)) {
            throw new ToolException("guide is one of " + String.join(", ", GUIDES) + ", not \"" + topic + "\"");
        }
        String text;
        try (InputStream in = MetaTools.class.getResourceAsStream("/io/github/imnotstable/msc/core/guides/" + topic + ".md")) {
            text = new String(Objects.requireNonNull(in, topic).readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        List<String> pages = new java.util.ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String section : text.split("\n\n(?=[A-Z]{2}[A-Z ]*(?:[ (:][^\n]*)?\n)")) {
            if (!current.isEmpty() && current.length() + 2 + section.length() > GUIDE_PAGE_CHARS) {
                pages.add(current.toString());
                current.setLength(0);
            }
            current.append(current.isEmpty() ? "" : "\n\n").append(section);
        }
        pages.add(current.toString());
        if (page > pages.size()) {
            throw new ToolException("the " + topic + " guide has " + pages.size() + (pages.size() == 1 ? " page" : " pages"));
        }
        String out = pages.get(page - 1);
        return page < pages.size()
                ? out + "\n\n[page " + page + " of " + pages.size() + "; next: msc_status guide=" + topic + " page=" + (page + 1) + "]"
                : out;
    }

    /**
     * How msc_status and msc_workspace name a workspace whose bench is closed: a switch failed and could not reopen
     * it, so no workspace is open until a switch opens one (#727).
     */
    public static String closedWorkspace(Path workspace) {
        return "none open (" + workspace + " was closed; msc_workspace path=" + workspace + " opens it again)";
    }

    public static void register(ToolRegistry registry, CoreConfig config, AgentHub hub, ServerManager server,
                                ToolLog log, ApiTools api, BooleanSupplier closed) {
        registry.add("msc_status", "Where things stand: workspace, platform, server, agent, API index. stats=true: this "
                        + "session's calls, errors, time and result tokens per tool instead. guide=build, textures, pack or ux: how "
                        + "to do that work well.",
                Schema.of().opt("stats", "boolean", "Show call stats")
                        .opt("guide", "string", String.join(", ", GUIDES))
                        .range("page", 1, 20, "Guide page"), args -> {
                    if (args.has("guide")) {
                        return guide(args.str("guide"), args.integer("page", 1));
                    }
                    if (args.has("page")) {
                        throw new ToolException("page is for guide; add guide= or leave it out");
                    }
                    if (args.bool("stats", false)) {
                        return log.summary();
                    }
                    StringBuilder out = new StringBuilder();
                    out.append("msc-core ").append(Core.VERSION).append(built()).append('\n');
                    out.append("workspace: ").append(closed.getAsBoolean() ? closedWorkspace(config.workspace())
                            : config.workspace()).append('\n');
                    out.append("platform: ").append(server.platform().id()).append('\n');
                    out.append("server: ").append(serverState(server)).append(" (").append(config.serverDir())
                            .append(")\n");
                    CoreServer.AgentSession s = hub.current();
                    out.append("agent: ");
                    if (s == null) {
                        out.append("not connected");
                    } else {
                        out.append(s.agent().platform()).append(' ').append(s.agent().platformVersion())
                                .append(", minecraft ").append(s.agent().minecraftVersion()).append(", ")
                                .append(s.agent().operations().size()).append(" operations");
                        if (!server.running()) {
                            // Not a server this session runs: one attached through MSC_CORE_ADDRESS (ADR 0005).
                            out.append(", of a server started outside this session");
                        }
                    }
                    int refused = hub.refusedAgents();
                    if (refused > 0) {
                        // ADR 0050: say why an agent that knocked is not the one world tools reach. Whose it is the
                        // hub cannot know: an earlier session's leftover here carries that session's token (#734).
                        out.append("; refused ").append(refused).append(refused == 1 ? " agent" : " agents")
                                .append(" without this session's token (a server another workspace or an earlier "
                                        + "session started")
                                .append(server.leftoverPid() >= 0 ? "; server_start or dev_cycle restarts this "
                                        + "workspace's leftover under this session)" : ")");
                    }
                    out.append("\napi index: ").append(api.status());
                    for (String w : SetupCheck.warnings(config)) {
                        out.append('\n').append(w);
                    }
                    return out.toString();
                });
    }

    /** Running, also when an earlier session detached it: world tools then fail until a start takes it over. */
    static String serverState(ServerManager server) {
        if (server.running()) {
            return server.reportsStopping() ? "stopping" : server.detached() ? "running, detached" : "running";
        }
        var other = server.otherSession();
        if (other.isPresent()) {
            return "running under another session that is still running (server pid " + other.get().serverPid()
                    + ", MSC core pid " + other.get().corePid() + "; use that session, or end it)";
        }
        long leftover = server.leftoverPid();
        if (leftover >= 0) {
            return "running, left by an earlier session (pid " + leftover + "; server_start or dev_cycle restarts it "
                    + "under this session, server_stop stops it)";
        }
        return server.serverJar() != null ? "stopped" : "not set up";
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
