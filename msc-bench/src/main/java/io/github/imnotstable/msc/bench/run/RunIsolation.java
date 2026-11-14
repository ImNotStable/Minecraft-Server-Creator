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
 *
 * <p>Every path is resolved once, by {@link #resolve}, and the run uses the resolved folder, so the folder checked is
 * the folder the attempts are written to.
 */
public final class RunIsolation {

    /** Files or folders that make a folder part of a project Claude Code loads. */
    static final List<String> MARKERS = List.of("CLAUDE.md", "CLAUDE.local.md", ".claude/CLAUDE.md", ".git");

    /** The default run folder under the home folder, as the docs and help show it. */
    public static final String DEFAULT_RUNS_SHOWN = "~/.msc/bench-runs";

    /** The run folder under the system temporary folder, used when the home folder is itself inside a project. */
    public static final String TEMP_RUNS_NAME = "msc-bench-runs";

    private RunIsolation() {
    }

    /** {@code ~/.msc/bench-runs}: the default run folder. */
    public static Path homeRunsDir(Path home) {
        return home.resolve(".msc").resolve("bench-runs");
    }

    /** {@code <temp>/msc-bench-runs}: the default when the home folder is inside a project (a dotfiles repository). */
    public static Path tempRunsDir(Path temp) {
        return temp.resolve(TEMP_RUNS_NAME);
    }

    /**
     * The resolved default run folder: {@link #homeRunsDir} unless the home folder is inside a project (a dotfiles
     * repository, a {@code ~/CLAUDE.md}), then {@link #tempRunsDir}. Throws when both are inside a project.
     */
    public static Path defaultRunsDir(Path home, Path temp) throws IOException {
        return defaultRunsDir(home, temp, null);
    }

    /** {@link #defaultRunsDir(Path, Path)}, looking no higher than {@code top} (see {@link #leaks}). */
    static Path defaultRunsDir(Path home, Path temp, Path top) throws IOException {
        List<String> refused = new ArrayList<>();
        for (Path candidate : List.of(homeRunsDir(home), tempRunsDir(temp))) {
            Path resolved = resolve(candidate);
            List<Path> leaks = leaks(resolved, home, top);
            if (leaks.isEmpty()) {
                return resolved;
            }
            refused.add(resolved + " (" + leaks.stream().map(Path::toString).toList() + ")");
        }
        throw new IOException("no default run folder is outside a project: " + String.join(" and ", refused)
                + " would load those files into every attempt; pass --out with a folder outside any repository or "
                + "CLAUDE.md folder");
    }

    /**
     * Every project marker in {@code runDir} or a folder above it. {@code ~/.claude/CLAUDE.md} is the user's own
     * memory, which {@code --setting-sources project,local} leaves out, so it does not count.
     */
    public static List<Path> leaks(Path runDir, Path home) {
        return leaks(runDir, home, null);
    }

    /**
     * {@link #leaks(Path, Path)}, looking no higher than {@code top} ({@code null}: up to the file-system root). Tests
     * pass their temporary folder, so what lies above it on the machine (a home with {@code .claude/CLAUDE.md}, as
     * the Windows temporary folder has) cannot change their result; a run folder outside {@code top} is refused.
     */
    static List<Path> leaks(Path runDir, Path home, Path top) {
        Path start = resolve(runDir);
        Path ceiling = top == null ? null : resolve(top);
        if (ceiling != null && !start.startsWith(ceiling)) {
            throw new IllegalArgumentException(start + " is not inside " + ceiling);
        }
        Path userMemory = resolve(home).resolve(".claude").resolve("CLAUDE.md");
        List<Path> found = new ArrayList<>();
        for (Path folder = start; folder != null; folder = folder.equals(ceiling) ? null : folder.getParent()) {
            for (String marker : MARKERS) {
                Path candidate = folder.resolve(marker);
                if (Files.exists(candidate) && !candidate.equals(userMemory)) {
                    found.add(candidate);
                }
            }
        }
        return found;
    }

    /**
     * Refuses a run folder inside a project, naming what would leak into the attempts, and otherwise returns it
     * resolved: the run must write to the returned folder, the one that was checked.
     */
    public static Path requireIsolated(Path runDir, Path home, Path temp) throws IOException {
        return requireIsolated(runDir, home, temp, null);
    }

    /**
     * {@link #requireIsolated(Path, Path, Path)}, looking no higher than {@code top} (see
     * {@link #leaks(Path, Path, Path)}).
     */
    static Path requireIsolated(Path runDir, Path home, Path temp, Path top) throws IOException {
        Path resolved = resolve(runDir);
        List<Path> leaks = leaks(resolved, home, top);
        if (leaks.isEmpty()) {
            return resolved;
        }
        String suggestion;
        try {
            Path fallback = defaultRunsDir(home, temp, top);
            suggestion = fallback.equals(resolved) || fallback.startsWith(resolved) || resolved.startsWith(fallback)
                    ? "a folder outside any repository or CLAUDE.md folder"
                    : "for example " + fallback + " (the default when --out is left out)";
        } catch (IOException noDefault) {
            suggestion = "a folder outside any repository or CLAUDE.md folder";
        }
        throw new IOException("run folder " + resolved + " is inside a project whose files Claude Code would load "
                + "into every attempt (" + leaks.stream().map(Path::toString).toList() + "); pass an --out folder "
                + "outside it, " + suggestion);
    }

    /**
     * The absolute path as the file system sees it: symbolic links and {@code ..} resolved in the order the system
     * resolves them (a {@code link/..} is the link target's parent), the case of existing folders as stored, and a
     * missing tail (folders the run creates) resolved name by name, as it will be once those folders exist: a
     * {@code missing/../link} is the link's target, not a folder named {@code link}.
     */
    public static Path resolve(Path path) {
        Path absolute = path.toAbsolutePath();
        Path root = absolute.getRoot();
        int names = absolute.getNameCount();
        for (int i = names; i >= 0; i--) {
            Path prefix = i == 0 ? root : (root == null ? absolute.subpath(0, i) : root.resolve(absolute.subpath(0, i)));
            if (prefix == null || !Files.exists(prefix)) {
                continue;
            }
            try {
                Path real = prefix.toRealPath();
                return i == names ? real : resolveTail(real, absolute.subpath(i, names));
            } catch (IOException e) {
                // gone or unreadable meanwhile: try a shorter prefix
            }
        }
        return absolute.normalize();
    }

    /**
     * {@code tail} below the resolved folder {@code real}, one name at a time: {@code ..} goes to the parent of what
     * the names so far resolved to, and a name that exists (after a {@code ..} out of a missing folder) is resolved
     * with its links.
     */
    private static Path resolveTail(Path real, Path tail) {
        Path current = real;
        for (Path name : tail) {
            String text = name.toString();
            if (text.equals("..")) {
                current = current.getParent() == null ? current : current.getParent();
            } else if (!text.equals(".")) {
                current = current.resolve(name);
                if (Files.exists(current)) {
                    try {
                        current = current.toRealPath();
                    } catch (IOException e) {
                        // gone or unreadable meanwhile: kept as named
                    }
                }
            }
        }
        return current;
    }
}
