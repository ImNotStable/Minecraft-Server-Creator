package {{packageName}};

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.bukkit.configuration.ConfigurationSection;

/** Everything in config.yml, checked once on start. A bad value fails with a message naming the key. */
public record GameConfig(String world, GameMode gameMode, Difficulty difficulty, long time, boolean naturalSpawns,
                         boolean keepInventory, Kit kit, List<Stage> stages) {

    static GameConfig from(ConfigurationSection c) {
        List<Stage> stages = new ArrayList<>();
        Set<String> names = new HashSet<>();
        List<Map<?, ?>> raw = c.getMapList("stages");
        for (int i = 0; i < raw.size(); i++) {
            Map<?, ?> s = raw.get(i);
            String where = "stages[" + i + "]";
            if (!(s.get("name") instanceof String name) || name.isBlank()) {
                throw new IllegalArgumentException(where + " needs a name");
            }
            if (!names.add(name.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException(where + ": duplicate stage name " + name);
            }
            if (!(s.get("spawn") instanceof List<?> spawn)) {
                throw new IllegalArgumentException(where + " (" + name + ") needs a spawn");
            }
            Box goal = s.get("goal") instanceof List<?> g ? Box.parse(g, where + ".goal") : null;
            stages.add(new Stage(name, Spot.parse(spawn, where + ".spawn"), goal));
        }
        if (stages.isEmpty()) {
            throw new IllegalArgumentException("stages: add at least one stage");
        }
        return new GameConfig(
                c.getString("world", "world"),
                parse(GameMode.class, "game-mode", c.getString("game-mode", "adventure")),
                parse(Difficulty.class, "difficulty", c.getString("difficulty", "normal")),
                c.getLong("time", 6000),
                c.getBoolean("natural-spawns", false),
                c.getBoolean("keep-inventory", true),
                Kit.parse(c.getStringList("kit")),
                List.copyOf(stages));
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String key, String value) {
        try {
            return Enum.valueOf(type, value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(key + ": unknown value " + value, e);
        }
    }

    /** Index of the stage with this name or 1-based number, or -1. */
    int stageIndex(String nameOrNumber) {
        for (int i = 0; i < stages.size(); i++) {
            if (stages.get(i).name().equalsIgnoreCase(nameOrNumber)) {
                return i;
            }
        }
        try {
            int n = Integer.parseInt(nameOrNumber);
            return n >= 1 && n <= stages.size() ? n - 1 : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
