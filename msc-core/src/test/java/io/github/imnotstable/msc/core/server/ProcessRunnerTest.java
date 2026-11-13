package io.github.imnotstable.msc.core.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.core.fixture.Utf8PrintMain;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProcessRunnerTest {
    @TempDir
    Path dir;

    @Test
    void multiByteCharactersSplitAcrossReadsAreDecodedWhole() throws Exception {
        ProcessRunner.Result r = ProcessRunner.run(List.of(Path.of(System.getProperty("java.home"), "bin", "java")
                .toString(), "-cp", System.getProperty("java.class.path"), Utf8PrintMain.class.getName()), dir,
                Duration.ofSeconds(60));
        assertThat(r.ok()).isTrue();
        // Only the characters are checked: the JVM may print a JAVA_TOOL_OPTIONS note around them.
        assertThat(r.output()).doesNotContain("�").contains("€".repeat(30_000));
    }
}
