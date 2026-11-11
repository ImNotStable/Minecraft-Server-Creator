package io.github.imnotstable.msc.bench.run;

import io.github.imnotstable.msc.bench.check.Placeholders;
import io.github.imnotstable.msc.bench.task.TaskDef;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** Builds the starting folder for one attempt, identical for every mode except the mode's server plugins. */
public final class Workspaces {

    private Workspaces() {
    }

    public static Path prepare(
            TaskDef task, Path workspace, BenchConfig config, BenchConfig.Mode mode, Path paperJar, Map<String, String> values)
            throws IOException {
        Files.createDirectories(workspace);
        for (Path fixture : task.fixtures()) {
            copyTree(fixture, workspace);
        }
        if (config.gradleWrapperFrom() != null && isGradleBuild(workspace)) {
            Path from = Path.of(config.gradleWrapperFrom());
            for (String file : List.of("gradlew", "gradlew.bat", "gradle/wrapper/gradle-wrapper.jar",
                    "gradle/wrapper/gradle-wrapper.properties")) {
                Path source = from.resolve(file);
                if (Files.isRegularFile(source)) {
                    Path target = workspace.resolve(file);
                    Files.createDirectories(target.getParent());
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                    if (file.equals("gradlew")) {
                        target.toFile().setExecutable(true);
                    }
                }
            }
        }
        Path server = workspace.resolve("server");
        if (task.needsServer()) {
            Files.createDirectories(server);
        }
        if (paperJar != null && mode.paperCacheSeed() != null) {
            Path cache = Files.createDirectories(workspace.resolve(mode.paperCacheSeed()));
            Files.copy(paperJar, cache.resolve(paperJar.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        }
        if (Files.isDirectory(server)) {
            prepareServer(server, config.server(), mode, paperJar, values);
        }
        return workspace;
    }

    private static boolean isGradleBuild(Path workspace) {
        return Files.isRegularFile(workspace.resolve("settings.gradle.kts"))
                || Files.isRegularFile(workspace.resolve("build.gradle.kts"));
    }

    private static void prepareServer(
            Path server, BenchConfig.Server config, BenchConfig.Mode mode, Path paperJar, Map<String, String> values)
            throws IOException {
        if (paperJar != null) {
            Files.copy(paperJar, server.resolve("paper.jar"), StandardCopyOption.REPLACE_EXISTING);
        }
        if (config.acceptEula()) {
            Files.writeString(server.resolve("eula.txt"), "eula=true\n");
        }
        Map<String, String> props = new LinkedHashMap<>();
        props.put("server-port", Integer.toString(config.serverPort()));
        props.put("enable-rcon", "true");
        props.put("rcon.port", Integer.toString(config.rconPort()));
        props.put("rcon.password", config.rconPassword());
        props.put("broadcast-rcon-to-ops", "false");
        setProperties(server.resolve("server.properties"), props);
        if (!mode.serverPlugins().isEmpty()) {
            Path plugins = server.resolve("plugins");
            Files.createDirectories(plugins);
            for (String plugin : mode.serverPlugins()) {
                Path source = Path.of(Placeholders.fill(plugin, values));
                if (!Files.isRegularFile(source)) {
                    throw new IOException("server plugin not found: " + plugin + " (build it first)");
                }
                Files.copy(source, plugins.resolve(pluginName(source)), StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /**
     * Drops a version suffix ({@code msc-agent-paper-0.1.0-SNAPSHOT.jar} becomes {@code msc-agent-paper.jar}),
     * matching the name msc-core installs the agent under, so a later {@code server_setup} replaces the
     * jar instead of adding a second copy.
     */
    static String pluginName(Path jar) {
        return jar.getFileName().toString().replaceFirst("-\\d[^-]*(-[A-Za-z0-9.]+)?\\.jar$", ".jar");
    }

    /** Sets keys in a {@code .properties} file, keeping other lines and their order. */
    static void setProperties(Path file, Map<String, String> values) throws IOException {
        List<String> lines = Files.isRegularFile(file) ? new ArrayList<>(Files.readAllLines(file)) : new ArrayList<>();
        Map<String, String> remaining = new LinkedHashMap<>(values);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            int eq = line.indexOf('=');
            if (eq > 0 && !line.startsWith("#")) {
                String key = line.substring(0, eq).strip();
                if (remaining.containsKey(key)) {
                    lines.set(i, key + "=" + remaining.remove(key));
                }
            }
        }
        remaining.forEach((k, v) -> lines.add(k + "=" + v));
        Files.write(file, lines);
    }

    static void copyTree(Path from, Path to) throws IOException {
        try (Stream<Path> paths = Files.walk(from)) {
            paths.forEach(source -> {
                Path target = to.resolve(from.relativize(source).toString());
                try {
                    if (Files.isDirectory(source)) {
                        Files.createDirectories(target);
                    } else {
                        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }
}
