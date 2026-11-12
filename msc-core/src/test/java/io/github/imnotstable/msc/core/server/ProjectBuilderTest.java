package io.github.imnotstable.msc.core.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ProjectBuilderTest {
    @Test
    void wrapperIsCalledByPath() {
        Path project = Path.of("some project");
        // Relative on Windows, so a folder with spaces never puts a quote first on cmd's command line.
        assertThat(ProjectBuilder.wrapperCommand(project, true, "build"))
                .containsExactly("cmd", "/c", ".\\gradlew.bat",
                        "build", "--console=plain", "-Dorg.gradle.welcome=never");
        assertThat(ProjectBuilder.wrapperCommand(project, false, "build").get(1))
                .isEqualTo(project.toAbsolutePath().resolve("gradlew").toString());
    }
}
