package io.github.imnotstable.msc.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.buildlogic.RepoFiles;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * CLAUDE.md sends readers to plan.md's Decisions table for the decisions, so every ADR in msc-docs/docs/adr has a row or
 * a mention in plan.md; ADR 0017 had none (#411). Runs in msc-core's repoChecks task.
 */
class DecisionsIndexTest {
    /** Declared in msc-core/build.gradle.kts, which makes these files the repoChecks task's inputs too. */
    private static final String SET = "decisions";
    private static final String PLAN = "msc-docs/docs/plan.md";
    private static final Pattern ADR_FILE = Pattern.compile("msc-docs/docs/adr/(\\d{4})-[^/]+\\.md");

    @Test
    void readsThePlanAndEveryAdr() {
        assertThat(RepoFiles.list(SET)).contains(PLAN, "msc-docs/docs/adr/0017-workspace-and-pack-lifetime.md",
                "msc-docs/docs/adr/0035-guard-tests-are-tripwires.md");
    }

    @Test
    void everyAdrHasARowOrMentionInThePlan() throws IOException {
        String plan = Files.readString(RepoFiles.path(SET, PLAN));
        List<String> missing = RepoFiles.list(SET).stream().filter(f -> !mentioned(plan, f)).toList();
        assertThat(missing).as("ADRs with no row or mention in " + PLAN + ": add a row to its Decisions table that links "
                + "adr/<file> (CLAUDE.md: the decisions are in that table and in msc-docs/docs/adr)").isEmpty();
    }

    /** Must flag: plans that do not mention ADR 0017. */
    @ParameterizedTest
    @ValueSource(strings = {"| Detach | ... | [ADR 0016](adr/0016-test-player.md) |", "ADR 00171", "see 0017",
            "[ADR 0018](adr/0018-playtest-tools.md)", "ADR 0117"})
    void flagsAPlanWithoutTheAdr(String plan) {
        assertThat(mentioned(plan, "msc-docs/docs/adr/0017-workspace-and-pack-lifetime.md")).isFalse();
    }

    /** Must not flag: a link, the number in text, or a file that is not an ADR. */
    @ParameterizedTest
    @ValueSource(strings = {"| Workspace | ... | [ADR 0017](adr/0017-workspace-and-pack-lifetime.md) |",
            "as ADR 0017 decided", "(ADR 0017)"})
    void acceptsAMention(String plan) {
        assertThat(mentioned(plan, "msc-docs/docs/adr/0017-workspace-and-pack-lifetime.md")).isTrue();
        assertThat(mentioned("", "msc-docs/docs/adr/README.md")).isTrue();
        assertThat(mentioned("", PLAN)).isTrue();
    }

    /** Whether {@code plan} links or names the ADR in {@code file}; true for a file that is not an ADR. */
    private static boolean mentioned(String plan, String file) {
        Matcher adr = ADR_FILE.matcher(file);
        if (!adr.matches()) {
            return true;
        }
        String n = adr.group(1);
        return plan.contains("adr/" + n + "-") || Pattern.compile("\\bADR " + n + "\\b").matcher(plan).find();
    }
}
