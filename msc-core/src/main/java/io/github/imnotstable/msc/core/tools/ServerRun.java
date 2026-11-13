package io.github.imnotstable.msc.core.tools;

import io.github.imnotstable.msc.core.CoreConfig;
import io.github.imnotstable.msc.core.agent.AgentHub;
import io.github.imnotstable.msc.core.server.LogCondenser;
import io.github.imnotstable.msc.core.server.MachineEula;
import io.github.imnotstable.msc.core.server.Platform;
import io.github.imnotstable.msc.core.server.ServerManager;
import io.github.imnotstable.msc.core.server.ServerProperties;
import io.github.imnotstable.msc.core.texture.PackProperties;
import io.github.imnotstable.msc.core.texture.ResourcePack;
import io.github.imnotstable.msc.core.tool.Args;
import io.github.imnotstable.msc.core.tool.Budget;
import io.github.imnotstable.msc.core.tool.ToolException;
import io.github.imnotstable.msc.protocol.op.Ops;
import io.github.imnotstable.msc.protocol.transport.CoreServer;
import io.github.imnotstable.msc.protocol.world.BlockPos;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Starting, stopping and detaching the dev server, and the start report the AI reads. */
final class ServerRun {
    private static final int GROUND_RADIUS = 32;

    private final CoreConfig config;
    private final AgentHub hub;
    private final ServerManager server;
    private final ResourcePack pack;
    private final ServerSetup setup;
    private final EulaPrompts eula;
    private final LogMarks marks;

    /** A start report, and how long the start took and when the agent connected (for dev_cycle's timings). */
    record Report(String text, ServerManager.StartResult result) {
    }

    ServerRun(CoreConfig config, AgentHub hub, ServerManager server, ResourcePack pack, ServerSetup setup,
              EulaPrompts eula, LogMarks marks) {
        this.config = config;
        this.hub = hub;
        this.server = server;
        this.pack = pack;
        this.setup = setup;
        this.eula = eula;
        this.marks = marks;
    }

    String start(Args args) throws IOException, InterruptedException {
        String accepted = "";
        if (args.bool("acceptEula", false) && server.platform().needsEula()) {
            Path file = config.serverDir().resolve("eula.txt");
            if (!ServerManager.eulaAccepted(file)) {
                Files.createDirectories(config.serverDir());
                Files.writeString(file, "# Minecraft EULA (" + MachineEula.EULA_URL + ") accepted by the user\neula=true\n");
            }
            accepted = eula.recordForMachine();
        }
        return accepted + startAndReport(args.integer("timeoutSeconds", 180), Budget.DEFAULT_CHARS).text();
    }

    String stop(Args args) throws InterruptedException {
        String host = pack.stopLeftoverHost();
        if (server.stop(Duration.ofSeconds(30))) {
            // Detached earlier in this session: its pack host goes with it, as server_detach promises.
            return pack.stopOwnHost() ? "server stopped, and the pack host it kept running" : "server stopped";
        }
        String leftover = server.stopLeftover();
        return leftover != null ? leftover : host != null ? host : "server was not running";
    }

    String detach(Args args) throws IOException, InterruptedException {
        if (!server.detach()) {
            throw new ToolException("the server is not running. Start it with server_start first.");
        }
        String java = ProcessHandle.current().info().command().orElse(config.javaCommand());
        boolean hosting;
        try {
            hosting = pack.keepServing(java, System.getProperty("java.class.path"));
        } catch (ToolException e) {
            return "server detached: it keeps running after this session ends\n" + e.getMessage();
        }
        String properties = "";
        ResourcePack.Built built = pack.lastBuilt();
        ResourcePack.Applied applied = pack.applied();
        if (hosting && built != null && PackProperties.write(serverProperties(), built.url(), built.sha1(),
                applied != null && applied.required())) {
            properties = "\nserver.properties now offers the pack too, so a restart outside MSC keeps it.";
        }
        return "server detached: it keeps running after this session ends"
                + (hosting ? ", and so does the resource pack host" : " (no pack built yet, so no pack host)")
                + "." + properties + "\nWorld tools and server_command work until this session ends; the next session's "
                + "server_start, dev_cycle or server_stop stops the server (world saved) and the pack host.";
    }

    Report startAndReport(int timeoutSeconds, int maxChars) throws IOException, InterruptedException {
        String leftover = LogText.join(server.stopLeftover(), pack.stopLeftoverHost());
        String packNote = syncPackProperties();
        boolean agentUpdated = refreshAgent();
        ServerManager.StartResult r = server.start(Duration.ofSeconds(timeoutSeconds));
        marks.started(r.logMark());
        List<String> lines = server.log().since(r.logMark());
        LogCondenser.Condensed analysis = LogCondenser.analyze(lines, maxChars);
        StringBuilder out = new StringBuilder();
        if (leftover != null) {
            out.append(leftover).append('\n');
        }
        if (packNote != null) {
            out.append(packNote).append('\n');
        }
        if (r.ready()) {
            out.append("server ready in ").append(LogText.seconds(r.millis()));
            CoreServer.AgentSession s = hub.current();
            out.append(s != null ? ", agent connected" : ", no agent connected (world tools unavailable)");
            if (agentUpdated) {
                out.append(", agent jar updated");
            }
            if (s != null) {
                out.append(groundLine());
            }
            String mode = modeLine();
            if (mode != null) {
                out.append('\n').append(mode);
            }
            if (s != null) {
                out.append(resendPack());
            }
            out.append('\n').append(analysis.clean() ? "no errors or warnings"
                    + (analysis.hidden() > 0 ? " (" + analysis.hidden() + " known-harmless hidden)" : "")
                    : analysis.text());
        } else if (r.exited()) {
            out.append(r.sawDone() ? "server exited right after it was ready, " : "server exited during startup ")
                    .append("after ").append(LogText.seconds(r.millis())).append('\n')
                    .append(analysis.text());
            if (analysis.clean()) {
                out.append("\nlast lines:\n").append(LogText.tail(lines, 15));
            }
        } else {
            out.append("server not ready after ").append(timeoutSeconds).append("s (still starting)\n")
                    .append(analysis.text());
        }
        return new Report(out.toString(), r);
    }

    /**
     * Where the ground is, so the AI does not build in the air: dev worlds are superflat with the surface far below
     * y=64. It is the most common top block within 32 blocks of 0,0, so a build at 0,0 does not count as ground.
     * Best effort; a start never fails because of it.
     */
    private String groundLine() {
        try {
            Ops.ServerInfo info = hub.call(Ops.SERVER_INFO, new Ops.Empty());
            if (info.worlds().isEmpty()) {
                return "";
            }
            String world = info.worlds().getFirst();
            Ops.HeightmapResult h = hub.call(Ops.WORLD_HEIGHTMAP,
                    new Ops.HeightmapParams(world, new BlockPos(-GROUND_RADIUS, 0, -GROUND_RADIUS),
                            new BlockPos(GROUND_RADIUS - 1, 0, GROUND_RADIUS - 1)));
            int y = groundLevel(h.heights());
            return "\nworld " + world + ": ground is y=" + y + " (most common top block around 0,0; build from y="
                    + (y + 1) + ")";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** The most common height; the lower one on a tie, since builds stand on the ground. */
    static int groundLevel(int[] heights) {
        if (heights.length == 0) {
            throw new IllegalArgumentException("no heights");
        }
        Map<Integer, Integer> counts = new HashMap<>();
        int best = heights[0];
        for (int y : heights) {
            int n = counts.merge(y, 1, Integer::sum);
            int top = counts.get(best);
            if (n > top || (n == top && y < best)) {
                best = y;
            }
        }
        return best;
    }

    /**
     * A restarted server has forgotten the pack that the pack tool sent, and a detached pack host was just stopped,
     * so serve and send it again. Best effort, like the ground line. Also used when dev_cycle keeps the server
     * running: a changed pack still reaches the players.
     */
    String resendPack() {
        ResourcePack.Applied applied = pack.applied();
        if (applied == null) {
            return "";
        }
        try {
            ResourcePack.Built built = pack.build(applied.description());
            hub.call(Ops.PACK_APPLY, new Ops.PackParams(built.url(), built.sha1(), applied.required()));
            return "\nresource pack sent again: players who join get it";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        } catch (IOException | RuntimeException e) {
            return "\nresource pack not sent again (" + e.getMessage() + "): run the pack tool";
        }
    }

    private String modeLine() {
        try {
            return ServerProperties.modeLine(serverProperties());
        } catch (IOException e) {
            return null;
        }
    }

    private Path serverProperties() {
        return config.serverDir().resolve("server.properties");
    }

    /**
     * A detached session left server.properties offering its pack, and the next start just stopped that pack host.
     * Serve the pack again, on the same port when it is free, and point server.properties at it before the server
     * reads the file; if the pack can no longer be built, stop offering it. Returns a note, or null.
     */
    private String syncPackProperties() {
        Path file = serverProperties();
        if (!pack.ours(PackProperties.url(file))) {
            return null;
        }
        try {
            ResourcePack.Applied applied = pack.applied();
            ResourcePack.Built built = pack.build(applied != null ? applied.description()
                    : LifecycleTools.DEFAULT_PACK_DESCRIPTION);
            PackProperties.write(file, built.url(), built.sha1(),
                    applied != null ? applied.required() : PackProperties.required(file));
            return null;
        } catch (IOException | RuntimeException e) {
            try {
                PackProperties.clear(file);
            } catch (IOException ignored) {
                // Best effort: the server then shows players a failed pack download.
            }
            return "server.properties no longer offers the resource pack (" + e.getMessage() + ")";
        }
    }

    /**
     * Before a start, replace an installed agent jar that differs from the one on offer, so a rebuilt agent
     * is picked up without another server_setup. Does nothing for a server that was never set up.
     */
    private boolean refreshAgent() throws IOException {
        Platform platform = server.platform();
        if (platform.addonDir() == null || server.running()) {
            return false;
        }
        Path installed = config.serverDir().resolve(platform.addonDir()).resolve(platform.agentJar());
        Path agent = setup.findAgentJar(platform);
        if (agent == null || !Files.exists(installed) || Files.mismatch(agent, installed) == -1) {
            return false;
        }
        Files.copy(agent, installed, StandardCopyOption.REPLACE_EXISTING);
        return true;
    }
}
