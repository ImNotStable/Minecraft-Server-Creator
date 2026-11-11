package io.github.imnotstable.msc.bench.task;

import java.util.List;

/**
 * What a task needs from the dev server.
 *
 * @param startBefore start the server before the agent runs (world tasks), so both modes begin with
 *                    the same live world
 * @param setup       console commands run over RCON after start and before the agent runs
 */
public record ServerSpec(boolean startBefore, List<String> setup) {

    public static final ServerSpec NONE = new ServerSpec(false, List.of());

    public ServerSpec {
        setup = setup == null ? List.of() : List.copyOf(setup);
    }
}
