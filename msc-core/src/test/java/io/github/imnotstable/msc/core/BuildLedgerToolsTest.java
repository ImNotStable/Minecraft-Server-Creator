package io.github.imnotstable.msc.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.core.world.BuildLedger;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The build ledger through the tools (ADR 0113): msc_status keeps the plan and lists builds, no new tool. */
class BuildLedgerToolsTest {
    @TempDir
    Path workspace;
    private CoreHarness h;

    @BeforeEach
    void start() throws Exception {
        h = new CoreHarness(workspace);
    }

    @AfterEach
    void stop() throws Exception {
        h.close();
    }

    @Test
    void msc_statusKeepsThePlanInTheWorkspace() throws Exception {
        assertThat(h.ok("msc_status", "builds", true)).startsWith("build ledger is empty");
        assertThat(h.ok("msc_status", "plan", "smithy: by the ford, spruce_planks\ninn: on the rise"))
                .contains("2 builds named (smithy, inn)");
        assertThat(Files.readString(workspace.resolve(".msc").resolve(BuildLedger.FILE))).contains("by the ford");
        assertThat(h.ok("msc_status", "builds", true)).startsWith("plan:\nsmithy: by the ford")
                .endsWith("planned, never reviewed: smithy, inn");
    }

    @Test
    void ledgerArgumentsAreRefusedWhereTheyDoNotFit() {
        assertThat(h.error("msc_status", "plan", "a: b", "guide", "build")).contains("give one of guide, plan, builds or stats");
        assertThat(h.error("msc_status", "builds", true, "stats", true)).contains("give one of guide, plan, builds or stats");
        assertThat(h.error("msc_status", "plan", " ")).contains("plan is empty");
        assertThat(h.error("world_summary", "region", "0,0,0 1,1,1", "name", "smithy"))
                .contains("name and why go with review=true");
    }
}
