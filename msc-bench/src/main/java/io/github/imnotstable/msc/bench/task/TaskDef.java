package io.github.imnotstable.msc.bench.task;

import io.github.imnotstable.msc.bench.check.Check;
import java.nio.file.Path;
import java.util.List;

/**
 * One benchmark task: a prompt, a starting workspace and the checks that decide pass or fail.
 *
 * @param id             folder name under {@code tasks/}
 * @param category       code, build, world, texture or game
 * @param platform       {@code paper} (the default) or {@code minestom} (no Mojang server, so no EULA)
 * @param title          one-line summary for reports
 * @param prompt         the text given to the AI, before placeholders are filled
 * @param scaffold       template written into the workspace first, or null
 * @param fixtures       folders copied into the workspace in order (shared fixtures first, then the
 *                       task's own {@code fixture/} folder if present)
 * @param server         what the task needs from the dev server
 * @param checks         all must pass for the task to pass
 * @param reset          what the bench puts back after the agent and before the checks
 * @param timeoutSeconds limit for the agent run, or 0 for the bench default
 */
public record TaskDef(
        String id,
        String category,
        String title,
        String platform,
        String prompt,
        Scaffold scaffold,
        List<Path> fixtures,
        ServerSpec server,
        List<Check> checks,
        Reset reset,
        int timeoutSeconds) {

    public TaskDef {
        fixtures = List.copyOf(fixtures);
        checks = List.copyOf(checks);
    }

    public static final String PAPER = "paper";
    public static final String MINESTOM = "minestom";

    /**
     * True when the task needs the bench's Paper server: started before the agent, or reached by a check. Such a
     * task runs only on a machine whose owner accepted the Minecraft EULA.
     */
    public boolean needsServer() {
        return server.startBefore() || checks.stream().anyMatch(Check::needsServer);
    }

    /**
     * True when the task uses the bench's game or RCON port, whatever its platform (#577): it has the bench's Paper
     * server, a check starts a server of its own ({@code player}), or the agent runs a server on {@code {serverPort}}
     * (a Minestom task, a scaffolded project, which listens on the bench's game port). Such an attempt starts only once
     * those ports are free.
     */
    public boolean usesBenchPorts() {
        return needsServer() || platform.equals(MINESTOM) || scaffold != null
                || checks.stream().anyMatch(Check::startsServer);
    }

    /**
     * True when the ports are freed again after the agent and before the checks: a check starts its own server, or
     * the agent may have left one there that no check is meant to reach (a task without the Paper server), or a reset
     * deletes what the agent's running server holds in memory. A Paper task without a reset checks the server the
     * agent left running.
     */
    public boolean freesPortsBeforeChecks() {
        return usesBenchPorts() && (!needsServer() || !reset.isEmpty()
                || checks.stream().anyMatch(Check::startsServer));
    }
}
