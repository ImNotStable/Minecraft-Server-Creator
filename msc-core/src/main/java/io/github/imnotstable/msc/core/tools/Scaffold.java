package io.github.imnotstable.msc.core.tools;

import io.github.imnotstable.msc.core.CoreConfig;
import io.github.imnotstable.msc.core.server.MachineEula;
import io.github.imnotstable.msc.core.server.Platform;
import io.github.imnotstable.msc.core.server.WorkspaceProjects;
import io.github.imnotstable.msc.core.tool.Args;
import io.github.imnotstable.msc.core.tool.ToolException;
import io.github.imnotstable.msc.templates.ScaffoldResult;
import io.github.imnotstable.msc.templates.TemplateVariables;
import io.github.imnotstable.msc.templates.Templates;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** {@code project_scaffold}: a new project from a template, and what to do next. */
final class Scaffold {
    private final CoreConfig config;
    private final EulaPrompts eula;
    private final BuildWarmup warmup;

    Scaffold(CoreConfig config, EulaPrompts eula, BuildWarmup warmup) {
        this.config = config;
        this.eula = eula;
        this.warmup = warmup;
    }

    static final String WRITE_FILES = "write the project files";
    static final String REMEMBER = "remember the workspace platform";
    static final String WARMUP = "start the first build";

    /**
     * Checked first: the template, the name and package, and that the folder can be written. Then: write the files
     * (through a staging folder beside tool files), remember the platform, start the first build.
     */
    String run(Args args) throws IOException, InterruptedException {
        String name = args.str("name");
        Path dir = OutputFile.checkFolder(args.path("dir", ".", config.inWorkspace()), "dir");
        String template = args.str("template", Templates.PAPER_PLUGIN);
        if (!Templates.ids().contains(template)) {
            throw new ToolException("unknown template " + template + ". Available: " + String.join(", ", Templates.ids()));
        }
        String platformId = Templates.info(template).get("platform");
        Platform platform = platformId == null ? null : Platform.of(platformId);
        if (platform != null) {
            // The template's platform has no EULA (minestom, velocity): refused as server_setup refuses it (#625).
            WayArgs.checkEula(args, platform);
        }
        TemplateVariables vars;
        try {
            TemplateVariables v = TemplateVariables.of(name).withAcceptEula(args.bool("acceptEula", false));
            vars = args.has("package") ? v.withPackageName(args.str("package").strip()) : v;
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new ToolException(String.valueOf(e.getMessage()));
        }
        // paper-server writes only server files (server.properties, eula.txt), used by every platform: not a project,
        // so it neither moves the workspace nor is a project the workspace's server could fail to run (#643).
        boolean isProject = platform != null && !template.equals(Templates.PAPER_SERVER);
        // A project where the other tools' next steps never look for one would be built only by a dev_cycle that names
        // it, and every other step would say to create it again (#679).
        String unfindable = isProject ? WorkspaceProjects.unfindable(config, dir) : null;
        if (unfindable != null) {
            throw new ToolException(unfindable);
        }
        // The server that runs the project: a workspace set up as Quilt or Purpur keeps its own for a Fabric mod or a
        // Paper plugin, so its platform is neither overwritten nor reported as not set up (#631). The same rule as
        // dev_cycle's, which reads the project's files: a template's show its platform only. Server files: the
        // workspace's own server.
        Platform serves = platform == null ? null
                : isProject ? Platform.serving(config, List.of(platform)) : Platform.current(config);
        boolean remember = isProject && serves == platform
                && (dir.equals(config.workspace()) || !Files.exists(Platform.file(config)));
        boolean warm = Boolean.parseBoolean(Templates.info(template).get("warmup"));
        Steps steps = new Steps("project_scaffold").then(WRITE_FILES);
        if (remember) {
            steps.then(REMEMBER).ifFails(REMEMBER, "the project is created, but the workspace platform is still "
                    + Platform.current(config).id() + ": server_setup platform=" + platformId + " sets it");
        }
        if (warm) {
            steps.then(WARMUP).ifFails(WARMUP, "the project is created; dev_cycle runs the first build");
        }
        ScaffoldResult result = steps.run(WRITE_FILES, () -> {
            try {
                return onlyToolFiles(dir) ? besideToolFiles(template, dir, vars) : Templates.scaffold(template, dir, vars);
            } catch (IllegalArgumentException | IllegalStateException e) {
                throw new ToolException(String.valueOf(e.getMessage()));
            }
        });
        if (remember) {
            steps.run(REMEMBER, () -> Platform.remember(config, Platform.of(platformId)));
        }
        StringBuilder out = new StringBuilder();
        out.append("created ").append(template).append(" project in ").append(dir).append(" (")
                .append(result.files().size()).append(" files)");
        // Only what the AI needs to find its way around; the template's pinned versions are in its build files.
        for (String key : List.of("projectId", "packageName", "version")) {
            if (result.values().containsKey(key)) {
                out.append('\n').append(key).append(": ").append(result.values().get(key));
            }
        }
        if (warm) {
            steps.run(WARMUP, () -> warmup.start(dir));
            out.append("\nfirst build started in the background (the loader sets up Minecraft once, which takes "
                    + "minutes); dev_cycle waits for it");
        }
        // A subfolder project of another family than the workspace's server (a Fabric mod in a Paper workspace): the
        // workspace keeps its platform, and dev_cycle refuses until server_setup switches it (#636, ADR 0048).
        Platform other = !isProject || remember ? null : Platform.otherFamily(config, List.of(platform));
        // The dev_cycle call that builds this project: with project= when it is not the workspace's own (#665).
        String devCycle = isProject ? WorkspaceProjects.devCycle(config, dir) : "dev_cycle";
        if (other != null || serves != null && serves.needsServerSetup()
                && !Files.exists(config.serverDir().resolve(serves.serverJar()))) {
            // The server is not set up yet: a first dev_cycle would build for nothing and then stop there (#627).
            String setup = other == null ? "server_setup" : "server_setup platform=" + serves.id();
            if (other != null) {
                out.append("\nthe workspace stays ").append(other.displayName()).append(", which never loads this ")
                        .append(serves.displayName()).append(" project: ").append(setup).append(" switches it (")
                        .append(other.displayName()).append(" projects then need server_setup platform=")
                        .append(other.id()).append(')');
            }
            Optional<MachineEula.Acceptance> machine = config.machineEula().read();
            if (!serves.needsEula()) {
                out.append("\nnext: server_setup platform=").append(serves.id())
                        .append(", then edit the code and ").append(devCycle);
            } else if (machine.isPresent()) {
                out.append("\nnext: ").append(setup).append(" (the Minecraft EULA is accepted for this machine by ")
                        .append(machine.get().describe()).append("), then edit the code and ").append(devCycle);
            } else {
                out.append("\nnext: ").append(EulaPrompts.EULA_QUESTION).append(eula.oneTimeHint())
                        .append("\nOn yes: ").append(setup).append(" acceptEula=true, then edit the code and ")
                        .append(devCycle);
            }
        } else {
            out.append("\nnext: edit the code, then ").append(devCycle);
        }
        return out.toString();
    }

    /**
     * True when {@code dir} exists and holds nothing but {@link WorkspaceProjects#TOOL_FILES} and what MSC created
     * ({@link WorkspaceProjects#mscOwn}), as a workspace does
     * before its project: after server_setup that is the server folder too (#664). A folder that holds anything else
     * is refused with the step that works, not the template's bare "not empty".
     */
    private boolean onlyToolFiles(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return false;
        }
        try (Stream<Path> list = Files.list(dir)) {
            if (list.findAny().isEmpty()) {
                return false;
            }
        }
        List<Path> other = WorkspaceProjects.notMsc(config, dir);
        if (other.isEmpty()) {
            return true;
        }
        String names = other.stream().limit(3).map(e -> e.getFileName().toString()).collect(Collectors.joining(", "))
                + (other.size() > 3 ? ", ..." : "");
        throw new ToolException(dir + " already holds files that are not MSC's (" + names + "): give dir=<new folder> "
                + "to create the project beside them");
    }

    /**
     * Templates only write into empty directories, so render into a staging folder and move the files in. A project
     * template's {@code run/} goes into a server folder server_setup already made, beside its files: the server's own
     * {@code server.properties} and {@code eula.txt} are kept, not replaced by the template's defaults.
     */
    private ScaffoldResult besideToolFiles(String template, Path dir, TemplateVariables vars) throws IOException {
        Path staging = Files.createTempDirectory(Files.createDirectories(config.dataDir()), "scaffold-");
        try {
            Path staged = staging.resolve("project");
            ScaffoldResult result = Templates.scaffold(template, staged, vars);
            List<Path> entries;
            try (Stream<Path> list = Files.list(staged)) {
                entries = list.toList();
            }
            // Checked before the first move: nothing MSC made under a template file's name (src, gradle) is replaced,
            // and nothing is half moved.
            List<Path> own = config.ownPaths();
            for (Path entry : entries) {
                Path to = dir.resolve(entry.getFileName().toString());
                if (Files.exists(to, LinkOption.NOFOLLOW_LINKS) && !(Files.isDirectory(entry)
                        && Files.isDirectory(to, LinkOption.NOFOLLOW_LINKS) && WorkspaceProjects.mscOwn(to, own))) {
                    throw new ToolException("the template writes " + entry.getFileName() + ", which " + dir
                            + " already holds: give dir=<new folder> to create the project beside it");
                }
            }
            List<Path> written = new ArrayList<>();
            for (Path file : result.files()) {
                Path to = dir.resolve(staged.relativize(file).toString());
                if (!Files.exists(to, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectories(to.getParent());
                    Files.move(file, to);
                    written.add(to);
                }
            }
            return new ScaffoldResult(dir, written, result.values());
        } finally {
            // The call owns its staging folder: gone also when the template refuses the arguments.
            deleteTree(staging);
        }
    }

    static void deleteTree(Path root) throws IOException {
        try (Stream<Path> tree = Files.walk(root)) {
            for (Path p : tree.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }
}
