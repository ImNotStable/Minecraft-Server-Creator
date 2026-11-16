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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Saved progress: the stage each player has reached, by stage name (so reordering stages keeps progress).
 * Stored as {@code <uuid>=<stage>} lines in {@code progress.properties} next to the world
 * ({@code run/} in development). Add more per-player fields here (scores, unlocks) as needed.
 *
 * <p>Changes only touch memory, so they are cheap on the tick thread; {@link #flush()} builds the file text
 * and writes it, so run it off the tick thread.
 */
public final class Progress {
    private final Map<UUID, String> stages = new ConcurrentHashMap<>();
    private final Path file;
    private final Object writeLock = new Object();
    private final AtomicBoolean dirty = new AtomicBoolean();

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
                stages.put(playerId(key), p.getProperty(key));
            }
        }
        return this;
    }

    private UUID playerId(String key) throws IOException {
        try {
            return UUID.fromString(key);
        } catch (IllegalArgumentException e) {
            throw new IOException(file + ": not a player UUID: " + key, e);
        }
    }

    /** The saved stage name, or null for a player with no progress. */
    public String stage(UUID player) {
        return stages.get(player);
    }

    public void setStage(UUID player, String stage) {
        stages.put(player, stage);
        dirty.set(true);
    }

    public void clear(UUID player) {
        stages.remove(player);
        dirty.set(true);
    }

    public int size() {
        return stages.size();
    }

    /** The file text for these entries, sorted by UUID, so the file only changes when progress does. */
    static String serialize(Map<UUID, String> entries) {
        Properties p = new Properties();
        entries.forEach((uuid, stage) -> p.setProperty(uuid.toString(), stage));
        StringWriter text = new StringWriter();
        try {
            p.store(text, null); // one date comment line, then the entries in key order
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        StringBuilder out = new StringBuilder("# Saved game progress: player UUID = stage name\n");
        text.toString().lines().skip(1).forEach(l -> out.append(l).append('\n'));
        return out.toString();
    }

    /**
     * Writes the latest changes, if any. Safe to call from any thread: run it off the tick thread after a change
     * and once more on stop. Writes happen in order, so an older state never overwrites a newer one.
     */
    public void flush() throws IOException {
        synchronized (writeLock) {
            // Clear the flag before the snapshot: a change made after this is either in the snapshot or sets the
            // flag again for the next flush, so none is lost.
            if (!dirty.getAndSet(false)) {
                return;
            }
            try {
                String text = serialize(Map.copyOf(stages));
                Files.createDirectories(file.toAbsolutePath().getParent());
                Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
                Files.writeString(tmp, text, StandardCharsets.UTF_8);
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException | RuntimeException e) {
                dirty.set(true); // keep the changes for the next flush
                throw e;
            }
        }
    }
}
