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

    static final String ACCEPT_EULA = "write the user's EULA acceptance";
    static final String LEFTOVER_SERVER = "stop the server an earlier session left running";
    static final String TAKE_OVER_PACK = "take over the pack an earlier session served";
    static final String REFRESH_AGENT = "update the installed agent jar";
    static final String START = "start the server";
    static final String DETACH = "detach the server";
    static final String KEEP_SERVING = "hand the pack to a standalone host";
    static final String OFFER_PACK = "offer the pack in server.properties";

    /** Checked first: not running, eula.txt writable. Then: accept the EULA, then {@link #startAndReport}. */
    String start(Args args) throws IOException, InterruptedException {
        if (server.running()) {
            throw new ToolException("server is already running. Use server_stop or dev_cycle.");
        }
        Steps steps = new Steps("server_start");
        String accepted = "";
        if (args.bool("acceptEula", false) && server.platform().needsEula()) {
            Path file = OutputFile.check(config.serverDir().resolve("eula.txt"), "eula.txt");
            steps.then(ACCEPT_EULA);
            plan(steps);
            accepted = steps.run(ACCEPT_EULA, () -> {
                if (!ServerManager.eulaAccepted(file)) {
                    Files.createDirectories(config.serverDir());
                    Files.writeString(file, "# Minecraft EULA (" + MachineEula.EULA_URL + ") accepted by the user\neula=true\n");
                }
                return eula.recordForMachine();
            });
        }
        return accepted + startAndReport(steps, args.integer("timeoutSeconds", 180), Budget.DEFAULT_CHARS).text();
    }

    /** Declares the steps of {@link #startAndReport}, with what a failure of each leaves. */
    static Steps plan(Steps steps) {
        return steps.then(LEFTOVER_SERVER).then(TAKE_OVER_PACK).then(REFRESH_AGENT).then(START)
                .ifFails(TAKE_OVER_PACK, "the server is not started; if the earlier session's pack host was stopped, "
                        + "server.properties may offer a pack no one serves: server_start again serves it")
                .ifFails(REFRESH_AGENT, "the server is not started; the installed agent jar is still the earlier "
                        + "one: server_start again updates it")
                .ifFails(START, "the server is not running; server_start starts it");
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

    /**
     * Checked first: running, server.properties writable when a pack was built. Then: detach the server, hand the
     * pack to a standalone host, offer it in server.properties. A host that does not come up leaves the pack served
     * by this session, which the message says; the call still detaches.
     */
    String detach(Args args) throws IOException, InterruptedException {
        if (!server.running()) {
            throw new ToolException("the server is not running. Start it with server_start first.");
        }
        ResourcePack.Built built = pack.lastBuilt();
        if (built != null && Files.exists(serverProperties())) {
            OutputFile.check(serverProperties(), "server.properties");
        }
        Steps steps = new Steps("server_detach").then(DETACH).then(KEEP_SERVING).then(OFFER_PACK)
                .ifFails(KEEP_SERVING, "the server is detached, but the pack is served by this session only, until it "
                        + "ends; server_stop then server_start and server_detach again keeps it up")
                .ifFails(OFFER_PACK, "the server and the pack host run on, but a restart outside MSC offers no pack");
        if (!steps.run(DETACH, server::detach)) {
            throw new ToolException("the server is not running. Start it with server_start first.");
        }
        String java = ProcessHandle.current().info().command().orElse(config.javaCommand());
        // A host that does not come up is an answer, not a failure: this session keeps serving the pack.
        String[] noHost = new String[1];
        boolean hosting = steps.run(KEEP_SERVING, () -> {
            try {
                return pack.keepServing(java, System.getProperty("java.class.path"));
            } catch (ToolException e) {
                noHost[0] = e.getMessage();
                return false;
            }
        });
        if (noHost[0] != null) {
            return "server detached: it keeps running after this session ends\n" + noHost[0];
        }
        String properties = "";
        ResourcePack.Applied applied = pack.applied();
        if (hosting && built != null && steps.run(OFFER_PACK, () -> PackProperties.write(serverProperties(),
                built.url(), built.sha1(), applied != null && applied.required()))) {
            properties = "\nserver.properties now offers the pack too, so a restart outside MSC keeps it.";
        }
        return "server detached: it keeps running after this session ends"
                + (hosting ? ", and so does the resource pack host" : " (no pack built yet, so no pack host)")
                + "." + properties + "\nWorld tools and server_command work until this session ends; the next session's "
                + "server_start, dev_cycle or server_stop stops the server (world saved) and the pack host.";
    }

    /**
     * The start, as steps of {@code steps}: stop a leftover server, take over a leftover pack host (serving the pack
     * again and pointing server.properties at it before the server reads the file), update the agent jar, start.
     */
    Report startAndReport(Steps steps, int timeoutSeconds, int maxChars) throws IOException, InterruptedException {
        if (!steps.planned(START)) {
            plan(steps);
        }
        String leftoverServer = steps.note(LEFTOVER_SERVER, server::stopLeftover);
        String[] packNotes = new String[2];
        steps.note(TAKE_OVER_PACK, () -> {
            packNotes[0] = pack.stopLeftoverHost();
            boolean offered = pack.ours(PackProperties.url(serverProperties()));
            packNotes[1] = syncPackProperties();
            return packNotes[0] != null || offered ? "" : null;
        });
        String leftover = LogText.join(leftoverServer, packNotes[0]);
        String packNote = packNotes[1];
        boolean agentUpdated = steps.ran(REFRESH_AGENT, this::refreshAgent);
        ServerManager.StartResult r = steps.run(START, () -> server.start(Duration.ofSeconds(timeoutSeconds)));
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
            ResourcePack.Snapshot before = pack.snapshot();
            Path file = serverProperties();
            byte[] offeredBefore = pack.ours(PackProperties.url(file)) ? Files.readAllBytes(file) : null;
            ResourcePack.Built built = pack.build(applied.description());
            // The host now serves only this build: a server.properties that offers our pack follows it first.
            TextureTools.keepOffered(pack, file, built, applied.required());
            try {
                hub.call(Ops.PACK_APPLY, new Ops.PackParams(built.url(), built.sha1(), applied.required()));
            } catch (RuntimeException e) {
                return "\nresource pack not sent again (" + Steps.reason(e) + "); "
                        + TextureTools.takeBack(pack, before, file, offeredBefore);
            }
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
        // Whole or not at all: a failed copy leaves the earlier agent jar working, which the next start updates.
        OutputFile.copy(agent, installed);
        return true;
    }
}
