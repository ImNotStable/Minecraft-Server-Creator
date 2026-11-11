package io.github.imnotstable.msc.bench.task;

import io.github.imnotstable.msc.bench.check.Check;
import java.nio.file.Path;
import java.util.List;

/**
 * One benchmark task: a prompt, a starting workspace and the checks that decide pass or fail.
 *
 * @param id             folder name under {@code tasks/}
 * @param category       code, build, world or texture
 * @param title          one-line summary for reports
 * @param prompt         the text given to the AI, before placeholders are filled
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
        String prompt,
        List<Path> fixtures,
        ServerSpec server,
        List<Check> checks,
        int timeoutSeconds) {

    public TaskDef {
        fixtures = List.copyOf(fixtures);
        checks = List.copyOf(checks);
    }

    /** True when any check talks to the running server. */
    public boolean needsServer() {
        return server.startBefore() || checks.stream().anyMatch(Check::needsServer);
    }
}
