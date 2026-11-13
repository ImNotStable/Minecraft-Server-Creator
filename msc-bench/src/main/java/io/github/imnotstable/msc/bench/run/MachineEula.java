package io.github.imnotstable.msc.bench.run;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * Reads the owner's one-time Minecraft EULA acceptance that msc-core keeps in {@code ~/.msc/eula.json} (written only
 * by the owner: {@code msc eula accept}, {@code installMsc -Pmsc.acceptEula=true}, or their own yes to a tool). The
 * bench reads it and never writes it: without it, tasks that run a Paper server are skipped.
 */
public final class MachineEula {

    static final String EULA_URL = "https://aka.ms/MinecraftEULA";

    private MachineEula() {
    }

    /** {@code <home>/.msc/eula.json}. */
    public static Path file(Path home) {
        return home.resolve(".msc").resolve("eula.json");
    }

    /**
     * True when the file holds an acceptance in msc-core's format, by the same rule as msc-core's
     * {@code MachineEula.read}; both are tested against msc-core's {@code eula-fixtures}.
     */
    public static boolean accepted(Path home) {
        Path file = file(home);
        if (!Files.isRegularFile(file)) {
            return false;
        }
        try {
            JsonObject o = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            return o.has("accepted") && o.get("accepted").getAsBoolean()
                    && o.has("eula") && EULA_URL.equals(o.get("eula").getAsString())
                    && o.has("acceptedBy") && o.has("acceptedAt") && Instant.parse(o.get("acceptedAt").getAsString()) != null;
        } catch (IOException | JsonParseException | IllegalStateException | UnsupportedOperationException
                 | DateTimeParseException e) {
            return false;
        }
    }
}
