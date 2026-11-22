package io.github.imnotstable.msc.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.core.api.ProjectSource;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectSourceTest {
    @TempDir
    Path project;

    private void write(String path, String text) throws Exception {
        Path f = project.resolve(path);
        Files.createDirectories(f.getParent());
        Files.writeString(f, text);
    }

    @Test
    void findsDeclarationsByNameWithTheTestsThatUseThem() throws Exception {
        write("src/main/java/a/Progress.java", """
                package a;

                /** Saved progress. */
                public final class Progress {
                    private final java.util.Map<String, Integer> best = new java.util.HashMap<>();

                    public synchronized void save(String player, int ticks) {
                        if (ticks > 0) {
                            best.merge(player, ticks, Math::min);
                        }
                        return;
                    }

                    @Override
                    public String toString() {
                        return best.toString();
                    }

                    static <T> java.util.List<T> bestOf(java.util.List<T> all) throws java.io.IOException {
                        return all;
                    }
                }
                """);
        write("src/main/java/a/Game.java", """
                package a;

                record Game(Progress progress) {
                    void finish(String player, int ticks) {
                        progress.save(player, ticks);
                    }

                    static final java.util.List<Game> ALL = java.util.List.of(
                            new Game(new Progress()));
                }
                """);
        write("src/test/java/a/ProgressTest.java", """
                package a;

                class ProgressTest {
                    void keepsTheBest() {
                        new Progress().save("Steve", 60);
                    }
                }
                """);
        assertThat(ProjectSource.search(project, "progress save", 5)).isEqualTo(
                "src/main/java/a/Progress.java:7 public synchronized void save(String player, int ticks)  tests: ProgressTest.java:5");
        assertThat(ProjectSource.search(project, "best time", 5)).isEqualTo("""
                no name has all of "best time"; closest, most words first:
                src/main/java/a/Progress.java:19 static <T> java.util.List<T> bestOf(java.util.List<T> all) throws java.io.IOException""");
        assertThat(ProjectSource.search(project, "game", 5)).isEqualTo("""
                src/main/java/a/Game.java:3 record Game(Progress progress)
                src/main/java/a/Game.java:4 void finish(String player, int ticks)""");
        assertThat(ProjectSource.search(project, "nothing", 5)).isEqualTo("no declarations match \"nothing\" in 2 source files");
    }
}
