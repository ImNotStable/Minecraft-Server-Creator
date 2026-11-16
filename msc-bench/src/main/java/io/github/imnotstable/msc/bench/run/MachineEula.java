package io.github.imnotstable.msc.bench.run;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import io.github.imnotstable.msc.bench.Text;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * Reads the owner's one-time Minecraft EULA acceptance that msc-core keeps in {@code ~/.msc/eula.json} (written only
 * by the owner: the {@code Main eula accept} line in {@code ~/.msc/core/eula-accept.txt}, installMsc with
 * {@code -Pmsc.acceptEula=true}, or their own yes to a tool). The bench reads it and never writes it: without it,
 * tasks that run a Paper server are skipped.
 *
 * <p>Read-only by construction (ADR 0036): the file's path never leaves this class. Other bench code asks
 * {@link #accepted} or prints {@link #location}; there is no write method.
 */
public final class MachineEula {

    static final String EULA_URL = "https://aka.ms/MinecraftEULA";

    private MachineEula() {
    }

    /** {@code <home>/.msc/eula.json}, for messages to the user. */
    static String location(Path home) {
        return file(home).toString();
    }

    private static Path file(Path home) {
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
            JsonObject o = JsonParser.parseString(Text.readOwn(file)).getAsJsonObject();
            return o.has("accepted") && o.get("accepted").getAsBoolean()
                    && o.has("eula") && EULA_URL.equals(o.get("eula").getAsString())
                    && o.has("acceptedBy") && o.has("acceptedAt") && Instant.parse(o.get("acceptedAt").getAsString()) != null;
        } catch (IOException | JsonParseException | IllegalStateException | UnsupportedOperationException
                 | DateTimeParseException e) {
            return false;
        }
    }
}
