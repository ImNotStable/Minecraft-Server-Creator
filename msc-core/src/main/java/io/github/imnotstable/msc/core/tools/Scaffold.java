package io.github.imnotstable.msc.core.tools;

import io.github.imnotstable.msc.core.CoreConfig;
import io.github.imnotstable.msc.core.server.MachineEula;
import io.github.imnotstable.msc.core.server.Platform;
import io.github.imnotstable.msc.core.tool.Args;
import io.github.imnotstable.msc.core.tool.ToolException;
import io.github.imnotstable.msc.templates.ScaffoldResult;
import io.github.imnotstable.msc.templates.TemplateVariables;
import io.github.imnotstable.msc.templates.Templates;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/** {@code project_scaffold}: a new project from a template, and what to do next. */
final class Scaffold {
    /**
     * Entries a folder may already hold and still count as empty for a scaffold: the core's own {@code .msc},
     * Claude Code's project config, git and editor folders. None of them collide with template files.
     */
    private static final Set<String> TOOL_FILES = Set.of(".msc", ".mcp.json", ".claude", ".git", ".idea", ".vscode");

    private final CoreConfig config;
    private final EulaPrompts eula;
    private final BuildWarmup warmup;

    Scaffold(CoreConfig config, EulaPrompts eula, BuildWarmup warmup) {
        this.config = config;
        this.eula = eula;
        this.warmup = warmup;
    }

    String run(Args args) throws IOException {
        String name = args.str("name");
        Path dir = config.resolve(args.str("dir", "."));
        String template = args.str("template", Templates.PAPER_PLUGIN);
        if (!Templates.ids().contains(template)) {
            throw new ToolException("unknown template " + template + ". Available: " + String.join(", ", Templates.ids()));
        }
        ScaffoldResult result;
        try {
            TemplateVariables vars = TemplateVariables.of(name).withAcceptEula(args.bool("acceptEula", false));
            if (args.has("package")) {
                vars = vars.withPackageName(args.str("package").strip());
            }
            result = onlyToolFiles(dir) ? besideToolFiles(template, dir, vars) : Templates.scaffold(template, dir, vars);
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new ToolException(String.valueOf(e.getMessage()));
        }
        String platformId = Templates.info(template).get("platform");
        if (platformId != null && !template.equals(Templates.PAPER_SERVER)
                && (dir.equals(config.workspace()) || !Files.exists(Platform.file(config)))) {
            Platform.remember(config, Platform.of(platformId));
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
        if (Boolean.parseBoolean(Templates.info(template).get("warmup"))) {
            warmup.start(dir);
            out.append("\nfirst build started in the background (the loader sets up Minecraft once, which takes "
                    + "minutes); dev_cycle waits for it");
        }
        Platform platform = platformId == null ? null : Platform.of(platformId);
        if (platform != null && platform.needsEula()
                && !Files.exists(config.serverDir().resolve(platform.serverJar()))) {
            // The server is not set up yet: a first dev_cycle would build for nothing and then stop there.
            Optional<MachineEula.Acceptance> machine = MachineEula.read(config.eulaFile());
            if (machine.isPresent()) {
                out.append("\nnext: server_setup (the Minecraft EULA is accepted for this machine by ")
                        .append(machine.get().describe()).append("), then edit the code and dev_cycle");
            } else {
                out.append("\nnext: ").append(EulaPrompts.EULA_QUESTION).append(eula.oneTimeHint())
                        .append("\nOn yes: server_setup acceptEula=true, then edit the code and dev_cycle");
            }
        } else {
            out.append("\nnext: edit the code, then dev_cycle");
        }
        return out.toString();
    }

    /** True when {@code dir} exists and holds nothing but {@link #TOOL_FILES}, as a fresh workspace does. */
    private static boolean onlyToolFiles(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return false;
        }
        try (Stream<Path> list = Files.list(dir)) {
            List<Path> entries = list.toList();
            return !entries.isEmpty() && entries.stream().allMatch(e -> TOOL_FILES.contains(e.getFileName().toString()));
        }
    }

    /** Templates only write into empty directories, so render into a staging folder and move the files in. */
    private ScaffoldResult besideToolFiles(String template, Path dir, TemplateVariables vars) throws IOException {
        Path staging = Files.createTempDirectory(Files.createDirectories(config.dataDir()), "scaffold-");
        try {
            Path staged = staging.resolve("project");
            ScaffoldResult result = Templates.scaffold(template, staged, vars);
            try (Stream<Path> list = Files.list(staged)) {
                for (Path entry : list.toList()) {
                    Files.move(entry, dir.resolve(entry.getFileName().toString()));
                }
            }
            return result;
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
