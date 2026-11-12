package io.github.imnotstable.msc.core.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ProjectBuilderTest {
    @Test
    void wrapperIsCalledByAbsolutePath() {
        Path project = Path.of("some project");
        assertThat(ProjectBuilder.wrapperCommand(project, true))
                .containsExactly("cmd", "/c", project.toAbsolutePath().resolve("gradlew.bat").toString(),
                        "build", "--console=plain", "-Dorg.gradle.welcome=never");
        assertThat(ProjectBuilder.wrapperCommand(project, false).get(1))
                .isEqualTo(project.toAbsolutePath().resolve("gradlew").toString());
    }
}
