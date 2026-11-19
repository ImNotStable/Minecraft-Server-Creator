package io.github.imnotstable.msc.core.tools;

import io.github.imnotstable.msc.core.CoreConfig;
import io.github.imnotstable.msc.core.agent.AgentHub;
import io.github.imnotstable.msc.core.backup.Backups;
import io.github.imnotstable.msc.core.backup.WorldFolders;
import io.github.imnotstable.msc.core.server.Platform;
import io.github.imnotstable.msc.core.server.ServerManager;
import io.github.imnotstable.msc.core.tool.Budget;
import io.github.imnotstable.msc.core.tool.ToolException;
import io.github.imnotstable.msc.core.world.WorldService;
import io.github.imnotstable.msc.protocol.op.Ops;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

/**
 * Whole-world backups and rollback (ADR 0041), on {@code build_undo backup=}: {@code list}, {@code save [label]}, or
 * a backup to restore. A running server is told {@code save-off} and {@code save-all flush} first, so the copy is
 * consistent and no autosave writes during it, and {@code save-on} after. A rollback stops the server (also one an
 * earlier session left running), saves the current world as a backup, restores, and starts the server again when it
 * was running. A build placing
 * {@link #AUTO_BLOCKS} or more takes a backup first, unless one is younger than {@link #AUTO_EVERY}.
 */
public final class WorldBackups {
    /** Blocks a build places from which it takes a backup first. */
    public static final long AUTO_BLOCKS = 100_000;
    /** A backup younger than this makes a big build skip its own. */
    static final Duration AUTO_EVERY = Duration.ofMinutes(5);
    static final String SAVE_CURRENT = "save the current world as a backup";
    static final String STOP = "stop the server";
    static final String RESTORE = "restore the backup";
    private static final String MINESTOM_NOTE = "\nMinestom saves the world only when it stops: "
            + "this backup is the world as last stopped";

    private final CoreConfig config;
    private final AgentHub hub;
    private final ServerManager server;
    private final ServerRun run;
    private final WorldService world;
    private final Backups backups;
    /** How long a server with no agent may take to answer save-all flush; tests shorten it. */
    static volatile Duration saveWait = Duration.ofSeconds(60);

    WorldBackups(CoreConfig config, AgentHub hub, ServerManager server, ServerRun run, WorldService world, Clock clock) {
        this.config = config;
        this.hub = hub;
        this.server = server;
        this.run = run;
        this.world = world;
        this.backups = new Backups(config.backupsDir(), clock);
    }

    /** {@code build_undo backup=...}. */
    synchronized String run(String what) throws IOException, InterruptedException {
        String w = what.strip();
        if (w.equals("list")) {
            List<Backups.Backup> all = backups.list();
            return all.isEmpty() ? "no backups yet" : Budget.truncate(String.join("\n",
                    all.stream().map(Backups.Backup::describe).toList()), Budget.DEFAULT_CHARS, "");
        }
        if (w.equals("save") || w.startsWith("save ")) {
            // A server another session runs or left running can be neither flushed nor trusted to have saved: refused
            // as every call that needs this session's server is (#705, #709). A stopped one is copied as it is.
            if (!server.running() && hub.current() == null) {
                String why = server.notRunning();
                if (!why.equals(AgentHub.NOT_RUNNING)) {
                    throw new ToolException(why);
                }
            }
            Taken t = take(w.substring(4).strip(), null, true);
            return "backup " + t.backup().describe() + t.note();
        }
        return rollback(w);
    }

    /** Before a build of {@code blocks} blocks: a backup line for its result, or "" when none was due. */
    public synchronized String beforeBigEdit(long blocks) throws InterruptedException {
        if (blocks < AUTO_BLOCKS) {
            return "";
        }
        try {
            long age = backups.newestAgeSeconds();
            if (age >= 0 && age < AUTO_EVERY.toSeconds() || WorldFolders.in(config.serverDir()).isEmpty()) {
                return "";
            }
            Taken t = take("auto", null, true);
            return "backup " + t.backup().name() + " taken first (build_undo backup=NAME restores it)" + t.note();
        } catch (IOException | RuntimeException e) {
            return "no backup taken first: " + Steps.reason(e);
        }
    }

    private String rollback(String name) throws IOException, InterruptedException {
        server.checkNoOtherSession();
        Backups.Backup target = backups.find(name);
        if (!server.running() && hub.current() != null) {
            throw new ToolException("the connected server was not started by this session; stop it, then roll back");
        }
        // Stopped first: every platform saves its world on stop (Minestom only then), so the copy of the current
        // world is whole, and nothing writes while the folders are swapped. A server an earlier session left running
        // is stopped too, reported as server_start reports it, and started again under this session (#583).
        Steps steps = new Steps("build_undo").then(ServerRun.LEFTOVER_SERVER).then(STOP).then(SAVE_CURRENT)
                .then(RESTORE)
                .ifFails(SAVE_CURRENT, "the world is unchanged; the server is stopped: server_start starts it")
                .ifFails(RESTORE, "the world is as it was before this call; the server is stopped: server_start "
                        + "starts it");
        ServerRun.Stopped stopped = run.stopAny(steps, STOP);
        Taken current = steps.run(SAVE_CURRENT,
                () -> WorldFolders.in(config.serverDir()).isEmpty() ? null : take("before-rollback", target.name(), false));
        world.forgetEdits();
        steps.run(RESTORE, () -> backups.restore(target, config.serverDir()));
        StringBuilder out = new StringBuilder("rolled back to ").append(target.describe());
        if (current != null) {
            out.append("\nthe world before it is backup ").append(current.backup().name());
        }
        if (stopped.leftover() != null) {
            out.append('\n').append(stopped.leftover());
        }
        if (!stopped.wasRunning()) {
            return out.append("\nserver not started (it was not running)").toString();
        }
        out.append('\n');
        ServerRun.Report report = run.startAndReport(steps, true, 180);
        return new Budget.Parts().add(out.toString()).add(report.parts())
                .text(Budget.DEFAULT_CHARS, null, ServerRun.HEADER_CUT);
    }

    private record Taken(Backups.Backup backup, String note) {
    }

    /**
     * Copy the world, flushed and with autosave off while the copy runs. {@code keep}: a backup the prune must not
     * drop. {@code live}: false when the server was just stopped, whose agent may not have disconnected yet.
     */
    private Taken take(String label, String keep, boolean live) throws IOException, InterruptedException {
        if (!live) {
            return new Taken(backups.save(config.serverDir(), label, keep), "");
        }
        if (server.platform() == Platform.MINESTOM) {
            return new Taken(backups.save(config.serverDir(), label, keep),
                    server.running() || hub.current() != null ? MINESTOM_NOTE : "");
        }
        boolean agent = hub.current() != null;
        boolean console = !agent && server.running();
        // Autosave the user (or an earlier failed save-on) turned off stays off: vanilla refuses a second save-off.
        boolean restore = agent || console;
        if (restore) {
            try {
                command(agent, "save-off");
            } catch (ToolException e) {
                if (!e.getMessage().contains("already")) {
                    throw e;
                }
                restore = false;
            }
        }
        Backups.Backup b;
        String noSaveOn = null;
        try {
            if (agent) {
                command(true, "save-all flush");
            } else if (console) {
                long mark = server.log().mark();
                server.console("save-all flush");
                if (server.awaitLine(mark, l -> l.contains("Saved the game"), saveWait) == null) {
                    throw new ToolException("the server did not confirm save-all flush within " + saveWait.toSeconds()
                            + "s; no backup taken");
                }
            }
            b = backups.save(config.serverDir(), label, keep);
        } finally {
            if (restore) {
                try {
                    command(agent, "save-on");
                } catch (IOException | RuntimeException e) {
                    noSaveOn = Steps.reason(e);
                }
            }
        }
        return new Taken(b, noSaveOn == null ? "" : "\nsave-on failed (" + noSaveOn + "): autosave is off until a restart");
    }

    private void command(boolean agent, String command) throws IOException, InterruptedException {
        if (!agent) {
            server.console(command);
            return;
        }
        Ops.CommandResult r = hub.call(Ops.SERVER_COMMAND, new Ops.CommandParams(command));
        if (!r.success()) {
            throw new ToolException(command + " failed: " + (r.output() == null ? "" : String.join(" ", r.output())));
        }
    }
}
