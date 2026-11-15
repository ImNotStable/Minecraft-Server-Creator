package io.github.imnotstable.msc.core.server;

import io.github.imnotstable.msc.core.CoreConfig;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Where the workspace's Gradle projects are (a folder with a Gradle wrapper, as {@link ProjectBuilder} needs), and the
 * {@code dev_cycle} call that builds one. Every next step that names {@code dev_cycle} names it through here, so a
 * project in a subfolder always comes with its {@code project=} (#665).
 */
public final class WorkspaceProjects {
    private WorkspaceProjects() {
    }

    /** True when {@code dir} holds a Gradle wrapper, either script. */
    public static boolean hasWrapper(Path dir) {
        return Files.isRegularFile(dir.resolve("gradlew")) || Files.isRegularFile(dir.resolve("gradlew.bat"));
    }

    /** The folders directly in {@code dir} that hold a Gradle wrapper, by name; none when {@code dir} is no folder. */
    public static List<Path> inSubfolders(Path dir) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> list = Files.list(dir)) {
            return list.filter(Files::isDirectory).filter(WorkspaceProjects::hasWrapper).sorted().toList();
        } catch (IOException | UncheckedIOException e) {
            return List.of();
        }
    }

    /** {@code dev_cycle} for the workspace's own project, else {@code dev_cycle project=<folder>}. */
    public static String devCycle(CoreConfig config, Path project) {
        Path ws = config.workspace();
        Path p = project.toAbsolutePath().normalize();
        if (p.equals(ws)) {
            return "dev_cycle";
        }
        return "dev_cycle project=" + (p.startsWith(ws) ? ws.relativize(p) : p).toString().replace('\\', '/');
    }

    /**
     * The {@code dev_cycle} call for the workspace as it is: plain for a project in the workspace root, with
     * {@code project=} for each subfolder that holds one, and with none yet {@code project_scaffold then dev_cycle}.
     */
    public static String nextDevCycle(CoreConfig config) {
        if (hasWrapper(config.workspace())) {
            return "dev_cycle";
        }
        List<Path> found = inSubfolders(config.workspace());
        return found.isEmpty() ? "project_scaffold then dev_cycle"
                : found.stream().map(p -> devCycle(config, p)).collect(Collectors.joining(" or "));
    }
}
