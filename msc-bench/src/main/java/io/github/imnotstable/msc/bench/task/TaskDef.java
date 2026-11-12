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
}
