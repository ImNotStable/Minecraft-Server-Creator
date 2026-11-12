package io.github.imnotstable.msc.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Keeps an install from before the msc plugin (ADR 0013: one flat folder, registered with a fixed
 * {@code claude mcp add msc -- java -cp <root>/lib/* ...} line) running the newest core. {@code installMsc} now
 * makes a new versioned folder each time (ADR 0021), which that fixed line never sees, so after each install the flat
 * folder is refreshed from the new one. Nothing is deleted: files are overwritten, and a jar the new install no longer
 * has is renamed to {@code <name>.replaced}, which {@code lib/*} and the agent and bot lookups skip.
 */
public final class LegacyInstall {
    /** The folders a core reads from its install: its class path, the agent jars and the older-protocol bot jars. */
    private static final List<String> FOLDERS = List.of("lib", "agents", "bot", "bin");

    private LegacyInstall() {
    }

    /** A flat install from before ADR 0021: {@code root/lib} holds the core jar itself. */
    static boolean isLegacy(Path root) {
        return holdsCore(root.resolve("lib"));
    }

    /**
     * Refresh the flat install in {@code root} from {@code fresh}, a new versioned install. Returns what to tell the
     * user, or an empty list when {@code root} has no flat install.
     */
    public static List<String> refresh(Path root, Path fresh) throws IOException {
        if (!isLegacy(root) || root.toAbsolutePath().normalize().equals(fresh.toAbsolutePath().normalize())) {
            return List.of();
        }
        int replaced = 0;
        for (String folder : FOLDERS) {
            Path from = fresh.resolve(folder);
            if (Files.isDirectory(from)) {
                replaced += mirror(from, root.resolve(folder));
            }
        }
        Files.copy(fresh.resolve("install.properties"), root.resolve("install.properties"),
                StandardCopyOption.REPLACE_EXISTING);
        Path add = root.resolve("claude-mcp-add.txt");
        Files.writeString(add, "# This folder's lib/ is refreshed by every installMsc, so this older line keeps working.\n"
                + "# Better: claude mcp remove msc -s user, then add the msc plugin (see msc-plugin/README.md).\n"
                + "claude mcp add msc --scope user -- \"" + java(fresh) + "\" -cp \"" + slash(root.resolve("lib"))
                + "/*\" io.github.imnotstable.msc.core.Main\n");
        List<String> out = new ArrayList<>();
        out.add("Also refreshed the older install in " + root + " (" + replaced + " old jars renamed to .replaced),");
        out.add("so a session registered with `claude mcp add` on " + root.resolve("lib") + " gets this core at its"
                + " next /mcp reconnect.");
        out.add("Better: claude mcp remove msc -s user, then use the msc plugin, which always starts the newest install.");
        return out;
    }

    /** Copies every file of {@code from} into {@code to}; renames {@code to}'s jars that {@code from} lacks. */
    private static int mirror(Path from, Path to) throws IOException {
        Files.createDirectories(to);
        Set<Path> fresh = new HashSet<>();
        try (Stream<Path> walk = Files.walk(from)) {
            for (Path f : walk.toList()) {
                Path rel = from.relativize(f);
                Path target = to.resolve(rel.toString());
                if (Files.isDirectory(f)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(f, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                    fresh.add(target.toAbsolutePath().normalize());
                }
            }
        }
        int renamed = 0;
        try (Stream<Path> walk = Files.walk(to)) {
            for (Path f : walk.filter(Files::isRegularFile).toList()) {
                if (f.getFileName().toString().endsWith(".jar") && !fresh.contains(f.toAbsolutePath().normalize())) {
                    Files.move(f, f.resolveSibling(f.getFileName() + ".replaced"), StandardCopyOption.REPLACE_EXISTING);
                    renamed++;
                }
            }
        }
        return renamed;
    }

    private static boolean holdsCore(Path lib) {
        try (Stream<Path> jars = Files.list(lib)) {
            return jars.anyMatch(j -> j.getFileName().toString().matches("msc-core-.*\\.jar"));
        } catch (IOException e) {
            return false;
        }
    }

    /** The Java the new install recorded, or plain {@code java}. */
    private static String java(Path fresh) {
        java.util.Properties p = new java.util.Properties();
        try (var in = Files.newBufferedReader(fresh.resolve("install.properties"))) {
            p.load(in);
        } catch (IOException e) {
            return "java";
        }
        return p.getProperty("java", "java");
    }

    static String slash(Path p) {
        return p.toAbsolutePath().normalize().toString().replace('\\', '/');
    }

    /**
     * The exact command that installs a fresh core from {@code source} next to {@code installDir}, in the form the AI's
     * shell runs (Claude Code uses bash, Git Bash on Windows). Names the install folder unless it is the default.
     */
    static String installCommand(Path installDir, Path source, Path home) {
        Path dir = installDir.toAbsolutePath().normalize();
        Path root = dir.getFileName() != null && dir.getFileName().toString().startsWith("msc-core-")
                && dir.getParent() != null ? dir.getParent() : dir;
        String cmd = "\"" + slash(source.resolve("gradlew")) + "\" -p \"" + slash(source) + "\" :msc-core:installMsc";
        boolean defaultRoot = home != null && root.equals(home.resolve(".msc/core").toAbsolutePath().normalize());
        return defaultRoot ? cmd : cmd + " -Pmsc.installDir=\"" + slash(root) + "\"";
    }
}
