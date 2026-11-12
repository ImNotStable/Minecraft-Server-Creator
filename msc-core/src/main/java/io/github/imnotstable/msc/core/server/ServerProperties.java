package io.github.imnotstable.msc.core.server;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads and edits a vanilla {@code server.properties} line by line, so comments and order survive. Values are taken
 * as written; the escapes {@link java.util.Properties} knows are not needed for the keys this core touches.
 */
public final class ServerProperties {
    /** Dev server play modes: what {@code server_setup mode=...} writes. */
    public enum Mode {
        /** Creative and peaceful: for building and looking around (the default). */
        BUILD("creative", "peaceful"),
        /** Survival and normal difficulty: mobs spawn and fight, players take damage. For testing games. */
        GAME("survival", "normal");

        private final String gamemode;
        private final String difficulty;

        Mode(String gamemode, String difficulty) {
            this.gamemode = gamemode;
            this.difficulty = difficulty;
        }

        public String id() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }

        /** The keys this mode sets. {@code force-gamemode} makes players who joined before switch too. */
        public Map<String, String> settings() {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("gamemode", gamemode);
            m.put("difficulty", difficulty);
            m.put("force-gamemode", "true");
            return m;
        }

        public static Mode of(String id) {
            for (Mode m : values()) {
                if (m.id().equals(id)) {
                    return m;
                }
            }
            throw new IllegalArgumentException("mode must be build or game");
        }
    }

    private ServerProperties() {
    }

    /** The file's keys and values, or an empty map when it does not exist. */
    public static Map<String, String> read(Path file) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        if (!Files.exists(file)) {
            return out;
        }
        for (String line : Files.readAllLines(file)) {
            int eq = line.indexOf('=');
            if (eq > 0 && !line.startsWith("#")) {
                out.put(line.substring(0, eq).strip(), line.substring(eq + 1).strip());
            }
        }
        return out;
    }

    /** Set each key: replace its line where it exists, append it otherwise. Creates the file when missing. */
    public static void set(Path file, Map<String, String> values) throws IOException {
        List<String> lines = Files.exists(file) ? new ArrayList<>(Files.readAllLines(file)) : new ArrayList<>();
        Map<String, String> left = new LinkedHashMap<>(values);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            int eq = line.indexOf('=');
            if (eq > 0 && !line.startsWith("#")) {
                String key = line.substring(0, eq).strip();
                String value = left.remove(key);
                if (value != null) {
                    lines.set(i, key + "=" + value);
                }
            }
        }
        left.forEach((k, v) -> lines.add(k + "=" + v));
        Files.createDirectories(file.toAbsolutePath().getParent());
        Files.write(file, lines);
    }

    /**
     * One line for the start report: the game mode and difficulty the server starts with, and how to switch.
     * Null when the file does not say (Minestom has no server.properties).
     */
    public static String modeLine(Path file) throws IOException {
        Map<String, String> p = read(file);
        String gamemode = p.get("gamemode");
        String difficulty = p.get("difficulty");
        if (gamemode == null || difficulty == null) {
            return null;
        }
        boolean game = !gamemode.equals("creative") && !difficulty.equals("peaceful");
        return "mode: " + gamemode + ", " + difficulty + " difficulty"
                + (game ? " (server_setup mode=build for creative, peaceful)"
                : " (server_setup mode=game for survival, normal: mobs attack and players take damage)");
    }
}
