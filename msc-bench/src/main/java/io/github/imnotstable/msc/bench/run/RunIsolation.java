package io.github.imnotstable.msc.bench.run;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Keeps attempts out of any project Claude Code would load into them. Claude Code reads {@code CLAUDE.md} and
 * {@code CLAUDE.local.md} (also {@code .claude/CLAUDE.md}) from its working folder and every folder above it, and
 * takes the enclosing git repository as the project, so a run folder inside this repository would give both modes the
 * repository's own instructions and the toolset's sources: the baseline would not be Claude Code alone (ADR 0004).
 */
public final class RunIsolation {

    /** Files or folders that make a folder part of a project Claude Code loads. */
    static final List<String> MARKERS = List.of("CLAUDE.md", "CLAUDE.local.md", ".claude/CLAUDE.md", ".git");

    private RunIsolation() {
    }

    /** The default run folder, outside any repository: {@code ~/.msc/bench-runs}. */
    public static Path defaultRunsDir(Path home) {
        return home.resolve(".msc").resolve("bench-runs");
    }

    /**
     * Every project marker in {@code runDir} or a folder above it. {@code ~/.claude/CLAUDE.md} is the user's own
     * memory, which {@code --setting-sources project,local} leaves out, so it does not count.
     */
    public static List<Path> leaks(Path runDir, Path home) {
        Path userMemory = real(home).resolve(".claude").resolve("CLAUDE.md");
        List<Path> found = new ArrayList<>();
        for (Path folder = real(runDir); folder != null; folder = folder.getParent()) {
            for (String marker : MARKERS) {
                Path candidate = folder.resolve(marker);
                if (Files.exists(candidate) && !candidate.equals(userMemory)) {
                    found.add(candidate);
                }
            }
        }
        return found;
    }

    /** Refuses a run folder inside a project, naming what would leak into the attempts. */
    public static void requireIsolated(Path runDir, Path home) throws IOException {
        List<Path> leaks = leaks(runDir, home);
        if (!leaks.isEmpty()) {
            throw new IOException("run folder " + real(runDir) + " is inside a project whose files Claude Code would "
                    + "load into every attempt (" + leaks.stream().map(Path::toString).toList()
                    + "); pass an --out folder outside any repository, for example " + defaultRunsDir(home));
        }
    }

    /** The absolute path with symbolic links resolved as far as the path exists. */
    private static Path real(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        Path existing = absolute;
        while (existing != null && !Files.exists(existing)) {
            existing = existing.getParent();
        }
        if (existing == null) {
            return absolute;
        }
        try {
            return existing.toRealPath().resolve(existing.relativize(absolute));
        } catch (IOException e) {
            return absolute;
        }
    }
}
