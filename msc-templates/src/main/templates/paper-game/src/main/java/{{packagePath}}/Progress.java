package {{packageName}};

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jspecify.annotations.Nullable;

/**
 * Saved progress: the stage each player has reached, by stage name (so reordering stages keeps progress).
 * Stored in {@code plugins/<name>/progress.yml}. Add more per-player fields here (scores, unlocks) as needed.
 */
public final class Progress {
    private final Map<UUID, String> stages = new ConcurrentHashMap<>();
    private final Path file;
    private final Object writeLock = new Object();
    private volatile @Nullable String pending;

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
                    stages.put(UUID.fromString(key), String.valueOf(section.getString(key)));
                }
            }
        }
        return this;
    }

    public @Nullable String stage(UUID player) {
        return stages.get(player);
    }

    public void setStage(UUID player, String stage) {
        stages.put(player, stage);
        pending = toYaml();
    }

    public void clear(UUID player) {
        stages.remove(player);
        pending = toYaml();
    }

    public int size() {
        return stages.size();
    }

    String toYaml() {
        YamlConfiguration yaml = new YamlConfiguration();
        stages.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(e -> yaml.set("stage." + e.getKey(), e.getValue()));
        return yaml.saveToString();
    }

    /**
     * Writes the latest change, if any. Safe to call from any thread: run it async after a change
     * and once more on disable. Writes happen in order, so an older snapshot never overwrites a newer one.
     */
    public void flush() throws IOException {
        synchronized (writeLock) {
            String yaml = pending;
            if (yaml == null) {
                return;
            }
            pending = null;
            Files.createDirectories(file.toAbsolutePath().getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, yaml, StandardCharsets.UTF_8);
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
