package io.github.imnotstable.msc.core.server;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

/**
 * Where a crash finding's code lives (ADR 0089): a frame of the workspace's own project becomes its source file and
 * line, with the line's code; a class that is not the project's is named by the plugin or mod jar it comes from; the
 * class a {@code NoSuchMethodError} or {@code NoClassDefFoundError} names is looked up the same way, so the fix says
 * which installed plugin or mod lacks it, or that none has it.
 */
final class CrashSources {
    /** Folders under the workspace that hold no project source: build output, servers, caches. */
    private static final Set<String> SKIP = Set.of("run", "build", "out", "bin", "libs", "servers", "node_modules",
            ".gradle", ".git", ".msc", ".idea", ".vscode", ".kotlin");
    private static final int MAX_DEPTH = 6;
    /** Folders looked into at most: a server folder set far from its project must not walk a whole disk. */
    private static final int MAX_FOLDERS = 3000;
    private int visited;
    private static final int MAX_CODE_CHARS = 120;
    private static final Pattern FRAME = Pattern.compile("^at ([\\w.$<>]+)\\.[\\w$<>]+\\(([\\w$]+\\.(?:java|kt)):(\\d+)\\)$");
    /** The class a linkage error names: {@code 'void com.x.Lib.greet(java.lang.String)'} or {@code com/x/Lib}. */
    private static final Pattern MISSING_MEMBER = Pattern.compile(
            "(NoSuchMethodError|NoSuchFieldError|AbstractMethodError): '?(?:[\\w.$\\[\\]<>]+ )?([\\w.$]+)\\.[\\w$<>]+[('\\s]");
    private static final Pattern MISSING_CLASS = Pattern.compile(
            "(NoClassDefFoundError|ClassNotFoundException): ([\\w./$]+)");

    private final Path serverDir;
    private final Platform platform;
    private List<Path> roots;
    private List<AddonCheck.Addon> addons;
    private final Map<String, String> owners = new HashMap<>();

    CrashSources(Path serverDir, Platform platform) {
        this.serverDir = serverDir.toAbsolutePath().normalize();
        this.platform = platform;
    }

    /** {@code f} with its frames linked to the project's source, its culprit's jar named, and a linkage error's owner. */
    CrashTriage.Finding locate(CrashTriage.Finding f) {
        List<String> frames = new ArrayList<>();
        String sourceAt = null;
        for (String frame : f.frames()) {
            String link = link(frame);
            frames.add(link == null ? frame : link);
            if (link != null && sourceAt == null) {
                sourceAt = link.substring(link.indexOf(" -> ") + 4, link.indexOf(": ", link.indexOf(" -> ")));
            }
        }
        String culprit = f.culprit();
        if (sourceAt == null && isClassMember(culprit)) {
            String owner = owner(culprit.substring(0, culprit.lastIndexOf('.')));
            if (owner != null) {
                culprit = culprit + " in " + owner;
            }
        }
        String fix = f.fix();
        if (sourceAt != null && fix.startsWith("fix the code at ")) {
            fix = "fix the code at " + sourceAt;
        }
        String linkage = linkage(f.cause(), culprit);
        if (linkage != null) {
            fix = linkage;
        }
        return new CrashTriage.Finding(f.what(), culprit, f.cause(), frames, fix, f.source(), f.times());
    }

    private static boolean isClassMember(String culprit) {
        return culprit.matches("[\\w.$<>]+\\.[\\w$<>]+") && culprit.contains(".");
    }

    /** {@code at a.b.C.m(C.java:9) -> proj/src/main/java/a/b/C.java:9: code}, or null outside the project. */
    String link(String frame) {
        Matcher m = FRAME.matcher(frame);
        if (!m.matches()) {
            return null;
        }
        String cls = m.group(1);
        int dot = cls.lastIndexOf('.');
        String pkg = dot < 0 ? "" : cls.substring(0, dot).replace('.', '/');
        String rel = (pkg.isEmpty() ? "" : pkg + "/") + m.group(2);
        int line = Integer.parseInt(m.group(3));
        for (Path root : roots()) {
            Path file = root.resolve(rel);
            if (Files.isRegularFile(file)) {
                String shown = workspace().relativize(file).toString().replace('\\', '/') + ":" + line;
                return frame.substring(0, frame.indexOf('(')) + " -> " + shown + ": " + code(file, line);
            }
        }
        return null;
    }

    private static String code(Path file, int line) {
        try (Stream<String> lines = Files.lines(file, StandardCharsets.UTF_8)) {
            String code = lines.skip(Math.max(0, line - 1)).findFirst().orElse("").strip();
            return code.length() <= MAX_CODE_CHARS ? code : code.substring(0, MAX_CODE_CHARS) + "...";
        } catch (IOException | java.io.UncheckedIOException e) {
            return "";
        }
    }

    private Path workspace() {
        Path parent = serverDir.getParent();
        return parent == null ? serverDir : parent;
    }

    /** The project source folders under the workspace: every {@code src/main/java} and {@code src/main/kotlin}. */
    private List<Path> roots() {
        if (roots == null) {
            roots = new ArrayList<>();
            find(workspace(), 0);
        }
        return roots;
    }

    private void find(Path dir, int depth) {
        if (depth > MAX_DEPTH || ++visited > MAX_FOLDERS) {
            return;
        }
        for (String lang : List.of("java", "kotlin")) {
            Path src = dir.resolve("src").resolve("main").resolve(lang);
            if (Files.isDirectory(src)) {
                roots.add(src);
            }
        }
        try (Stream<Path> children = Files.list(dir)) {
            for (Path child : children.filter(Files::isDirectory).sorted().toList()) {
                String name = child.getFileName().toString();
                if (!SKIP.contains(name) && !name.equals("src") && !child.equals(serverDir)) {
                    find(child, depth + 1);
                }
            }
        } catch (IOException | java.io.UncheckedIOException e) {
            // An unreadable folder holds no source MSC can show.
        }
    }

    /** {@code plugin Lib 2.0 (plugins/Lib.jar)} for the jar in the addon folder holding {@code cls}, or null. */
    String owner(String cls) {
        return owners.computeIfAbsent(cls, c -> {
            int inner = c.indexOf('$');
            String entry = (inner < 0 ? c : c.substring(0, inner)).replace('.', '/') + ".class";
            for (AddonCheck.Addon a : addons()) {
                try (ZipFile zip = new ZipFile(a.jar().toFile())) {
                    if (zip.getEntry(entry) != null) {
                        return (platform.addonDir().equals("mods") ? "mod " : "plugin ")
                                + a.describe(serverDir.resolve(platform.addonDir()));
                    }
                } catch (IOException e) {
                    // Not a readable jar: AddonCheck reports it.
                }
            }
            return null;
        });
    }

    private List<AddonCheck.Addon> addons() {
        if (addons == null) {
            try {
                addons = platform.addonDir() == null ? List.of()
                        : AddonCheck.read(serverDir.resolve(platform.addonDir()));
            } catch (IOException e) {
                addons = List.of();
            }
        }
        return addons;
    }

    /** The fix for a linkage error whose class is (or is not) in an installed plugin or mod; null for others. */
    String linkage(String cause, String culprit) {
        Matcher member = MISSING_MEMBER.matcher(cause);
        if (member.find()) {
            String cls = member.group(2);
            if (LogCondenser.framework(cls)) {
                return "built against another " + platform.displayName() + " API than the server runs (" + cls
                        + " lacks it): build against the server's version, or set the server up with the one it "
                        + "needs";
            }
            String owner = owner(cls);
            return owner == null ? null : "the installed " + owner + " has no such member of " + cls + ": the caller "
                    + "was built against another version of it; install that version or rebuild against this one";
        }
        Matcher missing = MISSING_CLASS.matcher(cause);
        if (missing.find()) {
            String cls = missing.group(2).replace('/', '.');
            if (LogCondenser.framework(cls) || cls.startsWith("java.")) {
                return null;
            }
            String owner = owner(cls);
            return owner != null ? owner + " has " + cls + " but the caller's class loader cannot see it: add it to "
                    + "depend (or softdepend) in the caller's plugin.yml, or its mod dependencies"
                    : "no installed plugin or mod has " + cls + ": install the one that provides it, or shade the "
                    + "library into the jar";
        }
        return null;
    }
}
