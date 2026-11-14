package io.github.imnotstable.msc.templates;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.imnotstable.msc.templates.TemplateDocs.Build;
import io.github.imnotstable.msc.templates.TemplateDocs.Command;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Every {@code ./gradlew <task>} a scaffolded project's CLAUDE.md or README.md tells you to run names a task its
 * build defines, a {@code --tests} class exists, and a run that the docs say stops with {@code stop} typed on the
 * console forwards standard input (#371: NeoForge's did not). {@code verifyTemplates} checks the same task names
 * against each project's real {@code gradlew tasks --all}.
 */
class TemplateDocsTest {
    @TempDir
    Path dir;

    static Stream<String> projects() {
        return Templates.ids().stream().filter(id -> !id.equals(Templates.PAPER_SERVER));
    }

    @ParameterizedTest
    @MethodSource("projects")
    void documentedCommandsMatchTheBuild(String id) throws IOException {
        Path project = Templates.scaffold(id, dir.resolve(id), TemplateVariables.of("Spawn Lobby")).root();
        Build build = TemplateDocs.build(Files.readString(project.resolve("build.gradle.kts")));
        List<Command> commands = TemplateDocs.commands(project);

        assertThat(commands).extracting(Command::task).contains("build", "test");
        assertThat(commands).extracting(Command::task).containsAnyOf("run", "runServer");
        for (Command c : commands) {
            assertThat(build.tasks()).as(c.doc() + ": " + c.line()).contains(c.task());
            if (c.testClass() != null) {
                try (Stream<Path> tests = Files.walk(project.resolve("src/test/java"))) {
                    assertThat(tests.map(p -> p.getFileName().toString()))
                            .as(c.doc() + ": " + c.line()).contains(c.testClass() + ".java");
                }
            }
            if (c.promisesStop()) {
                assertThat(build.stdinTasks()).as(c.doc() + " says to type stop: " + c.line()).contains(c.task());
            }
        }
    }

    @Test
    void modDevGradleRunsGetNoStandardInputUnlessTheBuildSetsIt() {
        String runs = """
                plugins {
                    alias(libs.plugins.moddev)
                }
                neoForge {
                    runs {
                        create("server") {
                            server()
                        }
                    }
                }
                """;
        assertThat(TemplateDocs.build(runs).tasks()).contains("runServer", "build");
        assertThat(TemplateDocs.build(runs).stdinTasks()).isEmpty();
        assertThat(TemplateDocs.build(runs + "tasks.named<JavaExec>(\"runServer\") {\n    standardInput = System.`in`\n}\n")
                .stdinTasks()).containsExactly("runServer");
    }

    @Test
    void readsRunTasksOfEachKindOfBuild() {
        assertThat(TemplateDocs.build("plugins {\n    alias(libs.plugins.fabric.loom)\n}\nloom {\n    runs {\n"
                + "        named(\"server\") {}\n        register(\"gameTest\") {}\n    }\n}\n").stdinTasks())
                .containsExactlyInAnyOrder("runServer", "runGameTest");
        assertThat(TemplateDocs.build("plugins {\n    java\n    alias(libs.plugins.run.paper)\n}\n").stdinTasks())
                .containsExactly("runServer");
        Build app = TemplateDocs.build("plugins {\n    java\n    application\n}\n");
        assertThat(app.tasks()).contains("run");
        assertThat(app.stdinTasks()).isEmpty();
        assertThat(TemplateDocs.build("plugins {\n    java\n}\n").tasks()).doesNotContain("run", "runServer");
    }

    @Test
    void readsDocumentedCommands() throws IOException {
        Files.writeString(dir.resolve("CLAUDE.md"), """
                - `./gradlew build`: compile and run all tests. It must pass before you stop.
                - `./gradlew test --tests '*GreeterTest'`: one test class.
                - `./gradlew runServer`: start the dev server. Stop it with `stop` on the console.
                """);
        Files.writeString(dir.resolve("README.md"), "Build with `./gradlew build`, run with `./gradlew run` (type stop to stop).\n");

        assertThat(TemplateDocs.commands(dir)).extracting(Command::task, Command::testClass, Command::promisesStop)
                .containsExactly(
                        tuple("build", null, false),
                        tuple("test", "GreeterTest", false),
                        tuple("runServer", null, true),
                        tuple("build", null, false),
                        tuple("run", null, true));
    }
}
