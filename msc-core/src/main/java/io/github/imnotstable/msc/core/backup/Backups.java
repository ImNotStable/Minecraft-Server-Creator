package io.github.imnotstable.msc.core.backup;

import io.github.imnotstable.msc.core.tool.ToolException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Whole-world backups of the dev server, as plain folder copies in {@code .msc/backups/<time>-<label>/}, one folder per
 * world folder ({@link WorldFolders}). A backup is copied to {@code <name>.part} and renamed when complete, so a crash
 * never leaves a half backup that looks whole. At most {@link #KEEP} are kept, and fewer when they pass
 * {@link #KEEP_BYTES}; the newest always stays.
 *
 * <p>A restore runs while the server is stopped. It stages the copies inside the server folder (so the final renames
 * stay on one volume), moves the current world folders aside, moves the copies in and deletes the old ones. A failure
 * puts everything back; a crash is put back by {@link #recover} before the next backup or restore.
 */
public final class Backups {
    /** Most backups kept. */
    public static final int KEEP = 5;
    /** Most bytes the kept backups may use together, unless only the newest is left. */
    public static final long KEEP_BYTES = 2L << 30;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT);
    private static final String PART = ".part";
    private static final java.util.regex.Pattern NAME = java.util.regex.Pattern.compile("\\d{8}-\\d{6}-[a-z0-9-]+");
    static final String STAGE = ".msc-restore";
    /** In each backup: its place in the order backups were taken, so two from one second sort right (#573). */
    private static final String ORDER = ".order";

    /** A kept backup: its folder name, the world folders it holds, their bytes, and its place in the order taken. */
    public record Backup(String name, List<String> worlds, long bytes, long order) {
        /** "name (world, world_nether; 12.3 MB)". */
        public String describe() {
            return name + " (" + String.join(", ", worlds) + "; " + mb(bytes) + ")";
        }
    }

    private final Path dir;
    private final Clock clock;

    public Backups(Path dir, Clock clock) {
        this.dir = dir;
        this.clock = clock;
    }

    /**
     * Copy every world folder of {@code serverDir} into a new backup named for now and {@code label}, then prune,
     * never dropping {@code keep} (a name, or null).
     */
    public Backup save(Path serverDir, String label, String keep) throws IOException, InterruptedException {
        recover(serverDir);
        List<String> worlds = WorldFolders.in(serverDir);
        if (worlds.isEmpty()) {
            throw new ToolException("no world folder in " + serverDir + " (" + WorldFolders.LAYOUTS
                    + "); a server writes its world when it saves or stops");
        }
        Files.createDirectories(dir);
        String base = TIME.format(LocalDateTime.now(clock)) + "-" + slug(label);
        String name = base;
        for (int i = 2; Files.exists(dir.resolve(name)); i++) {
            name = base + "-" + i;
        }
        Path part = dir.resolve(name + PART);
        Trees.delete(part);
        long bytes = 0;
        long order = list().stream().mapToLong(Backup::order).max().orElse(0) + 1;
        try {
            Files.createDirectories(part);
            Files.writeString(part.resolve(ORDER), order + "\n");
            for (String w : worlds) {
                bytes += Trees.copy(serverDir.resolve(w), part.resolve(w));
            }
            Trees.move(part, dir.resolve(name));
        } catch (IOException | RuntimeException | InterruptedException e) {
            Trees.delete(part);
            throw e;
        }
        prune(name, keep);
        return new Backup(name, worlds, bytes, order);
    }

    /** The kept backups, newest first: in the order they were taken, then by name (backups without an order). */
    public List<Backup> list() throws IOException {
        List<Backup> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (Stream<Path> children = Files.list(dir)) {
            for (Path p : children.toList()) {
                if (Files.isDirectory(p) && NAME.matcher(p.getFileName().toString()).matches()) {
                    out.add(new Backup(p.getFileName().toString(), WorldFolders.in(p), Trees.size(p), order(p)));
                }
            }
        }
        out.sort(Comparator.comparingLong(Backup::order).thenComparing(Backup::name).reversed());
        return out;
    }

    /**
     * The backup named {@code text}, else the one backup whose name contains it. A blank text, or one that more than
     * one name contains, is refused: a rollback replaces the whole world, so it never guesses (#573).
     */
    public Backup find(String text) throws IOException {
        List<Backup> all = list();
        String kept = all.isEmpty() ? "none taken yet"
                : "kept: " + String.join(", ", all.stream().map(Backup::name).toList());
        if (text.isBlank()) {
            throw new ToolException("backup takes list, save [label], or a backup name; " + kept);
        }
        List<Backup> matches = new ArrayList<>();
        for (Backup b : all) {
            if (b.name().equals(text)) {
                return b;
            }
            if (b.name().contains(text)) {
                matches.add(b);
            }
        }
        if (matches.size() == 1) {
            return matches.getFirst();
        }
        if (matches.isEmpty()) {
            throw new ToolException("no backup " + text + "; " + kept);
        }
        throw new ToolException("backup " + text + " matches " + matches.size() + ": "
                + String.join(", ", matches.stream().map(Backup::name).toList()) + "; give the whole name");
    }

    /** A backup folder's {@link #ORDER}, or 0 when it has none or it is unreadable. */
    private static long order(Path backup) {
        try {
            return Long.parseLong(Files.readString(backup.resolve(ORDER)).strip());
        } catch (IOException | NumberFormatException e) {
            return 0;
        }
    }

    /** The newest backup's age in seconds, or -1 when there is none. */
    public long newestAgeSeconds() throws IOException {
        List<Backup> all = list();
        if (all.isEmpty()) {
            return -1;
        }
        LocalDateTime when = LocalDateTime.parse(all.getFirst().name().substring(0, 15), TIME);
        return java.time.Duration.between(when, LocalDateTime.now(clock)).toSeconds();
    }

    /**
     * Replace the world folders of {@code serverDir} with {@code backup}'s. World folders the backup does not have are
     * removed too (a nether made after the backup). The server must be stopped.
     */
    public void restore(Backup backup, Path serverDir) throws IOException, InterruptedException {
        recover(serverDir);
        Path stage = serverDir.resolve(STAGE);
        Path fresh = stage.resolve("new");
        Path old = stage.resolve("old");
        try {
            for (String w : backup.worlds()) {
                Trees.copy(dir.resolve(backup.name()).resolve(w), fresh.resolve(w));
            }
            Files.createDirectories(old);
            // From here on recover() knows which folders were moved in: the backup's that are no longer in new/.
            Files.write(stage.resolve("worlds.txt"), backup.worlds());
            for (String w : WorldFolders.in(serverDir)) {
                Trees.move(serverDir.resolve(w), old.resolve(w));
            }
            for (String w : backup.worlds()) {
                Trees.move(fresh.resolve(w), serverDir.resolve(w));
            }
        } catch (IOException | RuntimeException | InterruptedException e) {
            try {
                recover(serverDir);
            } catch (IOException | RuntimeException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e;
        }
        recover(serverDir);
    }

    /**
     * Finish what an interrupted restore left in {@code serverDir}: a complete one only loses its old folders; an
     * incomplete one moves the old folders back, so the world is as before it.
     */
    public static void recover(Path serverDir) throws IOException, InterruptedException {
        Path stage = serverDir.resolve(STAGE);
        if (!Files.exists(stage)) {
            return;
        }
        Path list = stage.resolve("worlds.txt");
        Path fresh = stage.resolve("new");
        Path old = stage.resolve("old");
        boolean incomplete = Files.isDirectory(fresh) && !WorldFolders.in(fresh).isEmpty();
        if (Files.exists(list) && incomplete) {
            List<String> staged = WorldFolders.in(fresh);
            for (String w : Files.readAllLines(list)) {
                if (!w.isBlank() && !staged.contains(w)) {
                    Trees.delete(serverDir.resolve(w));
                }
            }
            for (String w : WorldFolders.in(old)) {
                Trees.move(old.resolve(w), serverDir.resolve(w));
            }
        }
        Trees.delete(stage);
    }

    /** Drop the oldest backups past {@link #KEEP} or {@link #KEEP_BYTES}, never {@code newest} or {@code keep}. */
    private void prune(String newest, String keep) throws IOException, InterruptedException {
        try (Stream<Path> children = Files.list(dir)) {
            for (Path p : children.toList()) {
                if (p.getFileName().toString().endsWith(PART)) {
                    Trees.delete(p);
                }
            }
        }
        List<Backup> all = list();
        List<Backup> others = new ArrayList<>();
        long total = 0;
        int kept = 0;
        for (Backup b : all) {
            if (b.name().equals(newest) || b.name().equals(keep)) {
                total += b.bytes();
                kept++;
            } else {
                others.add(b);
            }
        }
        for (Backup b : others) {
            total += b.bytes();
            if (kept >= KEEP || total > KEEP_BYTES) {
                Trees.delete(dir.resolve(b.name()));
            } else {
                kept++;
            }
        }
    }

    private static String slug(String label) {
        String s = label == null ? "" : label.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
        s = s.length() > 32 ? s.substring(0, 32) : s;
        return s.isEmpty() ? "manual" : s;
    }

    static String mb(long bytes) {
        return String.format(Locale.ROOT, "%.1f MB", bytes / 1_048_576.0);
    }
}
