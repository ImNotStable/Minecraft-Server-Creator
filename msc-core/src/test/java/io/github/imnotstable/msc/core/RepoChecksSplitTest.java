package io.github.imnotstable.msc.core;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.imnotstable.msc.buildlogic.RepoFiles;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Runs in msc-core's {@code test} task, whose inputs must stay msc-core's own: the sets every module's files are in
 * belong to the repoChecks task alone, or an edit anywhere in the repository reruns the whole suite (#370).
 */
class RepoChecksSplitTest {
    @ParameterizedTest
    @ValueSource(strings = {"documentedCommands", "testSources"})
    void theRepositoryWideSetsAreNotThisTasksInputs(String set) {
        assertThatThrownBy(() -> RepoFiles.list(set))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("for task :msc-core:test;");
    }
}
