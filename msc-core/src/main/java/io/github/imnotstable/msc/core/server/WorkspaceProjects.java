package io.github.imnotstable.msc.core.server;

import io.github.imnotstable.msc.core.CoreConfig;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Where the workspace's Gradle projects are (a folder with a Gradle wrapper, as {@link ProjectBuilder} needs), and the
 * {@code dev_cycle} call that builds one. Every next step that names {@code dev_cycle} names it through here, so a
 * project in a subfolder always comes with its {@code project=} (#665), a nested one is found as deep as
 * {@code project_scaffold} creates one (#679), and only a project the workspace's server runs is named (#680).
 */
public final class WorkspaceProjects {
    /** The deepest folder, counted from the workspace, that is searched and that project_scaffold creates a project in. */
    public static final int MAX_DEPTH = 4;
    /** The most folders one search looks into, so a workspace with a huge tree still answers at once. */
    static final int MAX_FOLDERS = 2000;
    /** Entries of other tools a workspace holds: none is a project, and a scaffold counts a folder of only these empty. */
    public static final Set<String> TOOL_FILES = Set.of(".mcp.json", ".claude", ".git", ".idea", ".vscode");
    /** Folders never searched: other tools' ({@link #TOOL_FILES}), Gradle's build output and caches, npm's. */
    static final Set<String> SKIPPED = Set.of(".mcp.json", ".claude", ".git", ".idea", ".vscode", ".gradle", "build",
            "node_modules");
    /** Said for the folder of a project that is still to be created beside what the workspace holds. */
    public static final String NEW_FOLDER = "<new folder>";

    private WorkspaceProjects() {
    }

    /** True when {@code dir} holds a Gradle wrapper, either script. */
    public static boolean hasWrapper(Path dir) {
        return Files.isRegularFile(dir.resolve("gradlew")) || Files.isRegularFile(dir.resolve("gradlew.bat"));
    }

    /**
     * The projects in {@code from} or under it, shallowest first, then by path: each folder with a Gradle wrapper at
     * most {@link #MAX_DEPTH} folders below the workspace. MSC's own paths ({@link CoreConfig#ownPaths}: the server
     * folder, {@code .msc}), {@link #SKIPPED} folders and links are not searched.
     */
    public static List<Path> find(CoreConfig config, Path from) {
        Path start = from.toAbsolutePath().normalize();
        if (!Files.isDirectory(start)) {
            return List.of();
        }
        List<Path> own = config.ownPaths();
        List<Path> found = new ArrayList<>();
        if (hasWrapper(start)) {
            found.add(start);
        }
        List<Path> level = List.of(start);
        int visited = 0;
        for (int depth = depth(config, start); depth < MAX_DEPTH && !level.isEmpty(); depth++) {
            List<Path> next = new ArrayList<>();
            for (Path dir : level) {
                for (Path child : children(dir)) {
                    if (++visited > MAX_FOLDERS) {
                        return found;
                    }
                    if (SKIPPED.contains(child.getFileName().toString()) || own.contains(child)) {
                        continue;
                    }
                    if (hasWrapper(child)) {
                        found.add(child);
                    }
                    next.add(child);
                }
            }
            level = next;
        }
        return found;
    }

    /** The folders in {@code dir}, by name, without links; none when it cannot be listed. */
    private static List<Path> children(Path dir) {
        try (Stream<Path> list = Files.list(dir)) {
            return list.filter(p -> Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)).map(Path::normalize)
                    .sorted(Comparator.comparing(Path::toString)).toList();
        } catch (IOException | UncheckedIOException e) {
            return List.of();
        }
    }

    /** How many folders {@code dir} is below the workspace: 0 for the workspace itself (or a folder outside it). */
    private static int depth(CoreConfig config, Path dir) {
        Path ws = config.workspace().toAbsolutePath().normalize();
        Path p = dir.toAbsolutePath().normalize();
        return p.startsWith(ws) && !p.equals(ws) ? ws.relativize(p).getNameCount() : 0;
    }

    /**
     * Why {@link #find} would never find a project in {@code dir}, as a refusal of project_scaffold's {@code dir}, or
     * null when it would: too deep, or in a folder that is not searched.
     */
    public static String unfindable(CoreConfig config, Path dir) {
        Path ws = config.workspace().toAbsolutePath().normalize();
        Path p = dir.toAbsolutePath().normalize();
        if (!p.startsWith(ws) || p.equals(ws)) {
            return null;
        }
        Path rel = ws.relativize(p);
        String shown = shown(config, p);
        if (rel.getNameCount() > MAX_DEPTH) {
            return "dir " + shown + " is " + rel.getNameCount() + " folders deep, and MSC finds projects at most "
                    + MAX_DEPTH + " deep: give a shorter dir";
        }
        List<Path> own = config.ownPaths();
        Path at = ws;
        for (Path name : rel) {
            at = at.resolve(name);
            if (SKIPPED.contains(name.toString()) || own.contains(at)) {
                return "dir " + shown + " is in " + shown(config, at) + ", where MSC never looks for projects (its own "
                        + "files, build output or another tool's): give a dir outside it";
            }
        }
        return null;
    }

    /**
     * The entries of {@code dir} that are neither {@link #TOOL_FILES} nor what MSC created there ({@link #mscOwn}), by
     * path; none when {@code dir} is no folder.
     */
    public static List<Path> notMsc(CoreConfig config, Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<Path> own = config.ownPaths();
        try (Stream<Path> list = Files.list(dir)) {
            return list.filter(e -> !TOOL_FILES.contains(e.getFileName().toString()) && !mscOwn(e, own)).sorted()
                    .toList();
        }
    }

    /**
     * True when MSC created {@code entry}: one of {@code own}, or a folder that holds only such entries on the way to
     * one (an MSC_SERVER_DIR of {@code servers/dev}).
     */
    public static boolean mscOwn(Path entry, List<Path> own) {
        Path e = entry.toAbsolutePath().normalize();
        if (own.contains(e)) {
            return true;
        }
        if (own.stream().noneMatch(p -> p.startsWith(e)) || !Files.isDirectory(e, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        try (Stream<Path> list = Files.list(e)) {
            return list.toList().stream().allMatch(child -> mscOwn(child, own));
        } catch (IOException | UncheckedIOException ex) {
            return false;
        }
    }

    /**
     * True when the workspace's server runs {@code project}, so dev_cycle builds and deploys it: a project that shows
     * no platform, or one of the agent family of the workspace's remembered platform ({@link Platform#otherFamily},
     * the check dev_cycle refuses with, ADR 0048).
     */
    public static boolean served(CoreConfig config, Path project) {
        List<Platform> runsOn = Platform.runsOn(project);
        return runsOn.isEmpty() || Platform.otherFamily(config, runsOn) == null;
    }

    /** {@code project} as next steps name it: relative to the workspace, with forward slashes. */
    private static String shown(CoreConfig config, Path project) {
        Path ws = config.workspace().toAbsolutePath().normalize();
        Path p = project.toAbsolutePath().normalize();
        return (p.startsWith(ws) ? ws.relativize(p) : p).toString().replace('\\', '/');
    }

    /** {@code dev_cycle} for the workspace's own project, else {@code dev_cycle project=<folder>}. */
    public static String devCycle(CoreConfig config, Path project) {
        Path ws = config.workspace().toAbsolutePath().normalize();
        return project.toAbsolutePath().normalize().equals(ws) ? "dev_cycle" : "dev_cycle project=" + shown(config, project);
    }

    /** The {@code dev_cycle} calls of {@code projects}, joined with "or"; past four, how many more there are. */
    public static String devCycles(CoreConfig config, List<Path> projects) {
        String calls = projects.stream().limit(4).map(p -> devCycle(config, p)).collect(Collectors.joining(" or "));
        return projects.size() > 4 ? calls + " (and " + (projects.size() - 4) + " more projects)" : calls;
    }

    /**
     * A next step: the calls to make ({@code call}), and what it says about the workspace's projects its server never
     * runs ({@code note}, empty or starting with "; ").
     */
    public record Next(String call, String note) {
        /** The whole step, as a {@code next:} line ends. */
        public String line() {
            return call + note;
        }
    }

    /**
     * The step that builds a project of the workspace as it is: the {@code dev_cycle} call of each project its server
     * runs (plain for the workspace root, {@code project=} for a subfolder at any depth). With none,
     * {@code project_scaffold then dev_cycle}, into a new folder when the workspace holds other files (where a scaffold
     * into it is refused), and a note naming each project the server never runs with the {@code server_setup} that
     * switches the workspace to it.
     */
    public static Next next(CoreConfig config) {
        Path ws = config.workspace();
        List<Path> all = find(config, ws);
        List<Path> served = all.stream().filter(p -> served(config, p)).toList();
        if (!served.isEmpty()) {
            return new Next(devCycles(config, served), "");
        }
        boolean taken;
        try {
            taken = !notMsc(config, ws).isEmpty();
        } catch (IOException | UncheckedIOException e) {
            taken = true;
        }
        String call = taken ? "project_scaffold dir=" + NEW_FOLDER + " then dev_cycle project=" + NEW_FOLDER
                : "project_scaffold then dev_cycle";
        return new Next(call, unservedNote(config, all));
    }

    /** {@link #next}'s whole line. */
    public static String nextDevCycle(CoreConfig config) {
        return next(config).line();
    }

    /** What {@link #next} says about {@code projects}, none of which the workspace's server runs. */
    private static String unservedNote(CoreConfig config, List<Path> projects) {
        if (projects.isEmpty()) {
            return "";
        }
        Platform runs = Platform.current(config);
        Path ws = config.workspace().toAbsolutePath().normalize();
        List<String> names = new ArrayList<>();
        List<Platform> owns = new ArrayList<>();
        for (Path p : projects) {
            names.add(p.toAbsolutePath().normalize().equals(ws) ? "the workspace's project" : shown(config, p));
            owns.add(Platform.runsOn(p).getFirst());
        }
        String switches = owns.stream().distinct().map(p -> "server_setup platform=" + p.id())
                .collect(Collectors.joining(" or "));
        if (projects.size() == 1) {
            return "; " + names.getFirst() + " is a " + owns.getFirst().displayName() + " project, which "
                    + runs.displayName() + " never runs: " + switches + " switches the workspace to it";
        }
        for (int i = 0; i < names.size(); i++) {
            names.set(i, names.get(i) + " (" + owns.get(i).displayName() + ")");
        }
        String listed = String.join(", ", names.subList(0, Math.min(3, names.size())))
                + (names.size() > 3 ? " and " + (names.size() - 3) + " more" : "");
        return "; " + listed + " are projects " + runs.displayName() + " never runs: " + switches
                + " switches the workspace to one";
    }
}
