package {{packageName}};

import java.io.IOException;
import java.io.Reader;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Saved progress: the stage each player has reached, by stage name (so reordering stages keeps progress).
 * Stored as {@code <uuid>=<stage>} lines in {@code progress.properties} next to the world. Add more per-player
 * fields here (scores, unlocks) as needed.
 */
public final class Progress {
    private final Map<UUID, String> stages = new ConcurrentHashMap<>();
    private final Path file;
    private final Object writeLock = new Object();
    private volatile String pending;

    public Progress(Path file) {
        this.file = file;
    }

    /** Reads the file if it exists. Call once on start, before players join. */
    public Progress load() throws IOException {
        if (Files.exists(file)) {
            Properties p = new Properties();
            try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                p.load(in);
            }
            for (String key : p.stringPropertyNames()) {
                try {
                    stages.put(UUID.fromString(key), p.getProperty(key));
                } catch (IllegalArgumentException e) {
                    throw new IOException(file + ": not a player UUID: " + key, e);
                }
            }
        }
        return this;
    }

    /** The saved stage name, or null for a player with no progress. */
    public String stage(UUID player) {
        return stages.get(player);
    }

    public void setStage(UUID player, String stage) {
        stages.put(player, stage);
        pending = serialize();
    }

    public void clear(UUID player) {
        stages.remove(player);
        pending = serialize();
    }

    public int size() {
        return stages.size();
    }

    String serialize() {
        StringBuilder out = new StringBuilder("# Saved game progress: player UUID = stage name\n");
        new TreeMap<>(stages).forEach((uuid, stage) -> {
            StringWriter line = new StringWriter();
            Properties one = new Properties();
            one.setProperty(uuid.toString(), stage);
            try {
                one.store(line, null);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            // store() writes a date comment first; keep only the entry, so the file only changes when progress does.
            line.toString().lines().filter(l -> !l.startsWith("#")).forEach(l -> out.append(l).append('\n'));
        });
        return out.toString();
    }

    /**
     * Writes the latest change, if any. Safe to call from any thread: run it off the tick thread after a change
     * and once more on stop. Writes happen in order, so an older snapshot never overwrites a newer one.
     */
    public void flush() throws IOException {
        synchronized (writeLock) {
            String text = pending;
            if (text == null) {
                return;
            }
            pending = null;
            Path parent = file.toAbsolutePath().getParent();
            Files.createDirectories(parent);
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, text, StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
