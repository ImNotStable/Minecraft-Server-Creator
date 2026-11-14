package {{packageName}};

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jspecify.annotations.Nullable;

/**
 * Saved progress: the stage each player has reached, by stage name (so reordering stages keeps progress).
 * Stored in {@code plugins/<name>/progress.yml}. Add more per-player fields here (scores, unlocks) as needed.
 *
 * <p>Changes only touch memory, so they are cheap on the main thread; {@link #flush()} builds the file text
 * and writes it, so run it on an async task.
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
            YamlConfiguration yaml = new YamlConfiguration();
            try {
                yaml.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
            } catch (InvalidConfigurationException e) {
                throw new IOException(file + ": " + e.getMessage(), e);
            }
            var section = yaml.getConfigurationSection("stage");
            if (section != null) {
                for (String key : section.getKeys(false)) {
                    stages.put(playerId(key), String.valueOf(section.getString(key)));
                }
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
    public @Nullable String stage(UUID player) {
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
        YamlConfiguration yaml = new YamlConfiguration();
        new TreeMap<>(entries).forEach((uuid, stage) -> yaml.set("stage." + uuid, stage));
        return yaml.saveToString();
    }

    /**
     * Writes the latest changes, if any. Safe to call from any thread: run it on an async task after a change
     * and once more on disable. Writes happen in order, so an older state never overwrites a newer one.
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
