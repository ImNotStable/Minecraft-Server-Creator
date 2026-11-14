package io.github.imnotstable.msc.core.server;

import io.github.imnotstable.msc.process.ServerPropertiesFile;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The dev server play modes and the start report's mode line. The file itself is read and written only through
 * msc-process's {@link ServerPropertiesFile} (ADR 0033).
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

    /**
     * One line for the start report: the game mode and difficulty the server starts with, and how to switch.
     * Null when the file does not say (Minestom has no server.properties).
     */
    public static String modeLine(Path file) throws IOException {
        Map<String, String> p = ServerPropertiesFile.read(file);
        if (p.get("gamemode") == null || p.get("difficulty") == null) {
            return null;
        }
        String gamemode = p.get("gamemode").strip();
        String difficulty = p.get("difficulty").strip();
        boolean game = !gamemode.equals("creative") && !difficulty.equals("peaceful");
        return "mode: " + gamemode + ", " + difficulty + " difficulty"
                + (game ? " (server_setup mode=build for creative, peaceful)"
                : " (server_setup mode=game for survival, normal: mobs attack and players take damage)");
    }
}
