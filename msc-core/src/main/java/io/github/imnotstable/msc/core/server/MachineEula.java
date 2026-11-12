package io.github.imnotstable.msc.core.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/**
 * The owner's one-time agreement to the Minecraft EULA for this machine, in {@code ~/.msc/eula.json}.
 *
 * <p>MSC tools only read it: with it, {@code server_setup} and {@code server_start} write {@code eula=true} into the
 * server's {@code eula.txt}. Only {@link EulaCommand} writes it, and only after the owner types {@code yes} in an
 * interactive terminal. No tool argument creates it. See ADR 0019.
 */
public final class MachineEula {
    public static final String EULA_URL = "https://aka.ms/MinecraftEULA";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** Who agreed, where and when. */
    public record Acceptance(String user, String host, Instant acceptedAt) {
        /** For tool results: {@code jeremiah on Flux, 2026-11-12}. */
        public String describe() {
            return user + " on " + host + ", " + acceptedAt.toString().substring(0, 10);
        }
    }

    private MachineEula() {
    }

    /** {@code <home>/.msc/eula.json}. */
    public static Path defaultFile(String home) {
        return Path.of(home, ".msc", "eula.json");
    }

    /** The acceptance in {@code file}, or empty when there is no file or it is not a complete acceptance. */
    public static Optional<Acceptance> read(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            JsonObject o = GSON.fromJson(Files.readString(file), JsonObject.class);
            if (o == null || !o.has("accepted") || !o.get("accepted").getAsBoolean() || !o.has("eula")
                    || !EULA_URL.equals(o.get("eula").getAsString()) || !o.has("acceptedBy") || !o.has("acceptedAt")) {
                return Optional.empty();
            }
            String host = o.has("host") ? o.get("host").getAsString() : "this machine";
            return Optional.of(new Acceptance(o.get("acceptedBy").getAsString(), host,
                    Instant.parse(o.get("acceptedAt").getAsString())));
        } catch (IOException | JsonParseException | IllegalStateException | UnsupportedOperationException
                 | DateTimeParseException e) {
            return Optional.empty();
        }
    }

    /** Package-private on purpose: only {@link EulaCommand} records an acceptance. */
    static void write(Path file, Acceptance a) throws IOException {
        JsonObject o = new JsonObject();
        o.addProperty("eula", EULA_URL);
        o.addProperty("accepted", true);
        o.addProperty("acceptedBy", a.user());
        o.addProperty("host", a.host());
        o.addProperty("acceptedAt", a.acceptedAt().toString());
        o.addProperty("how", "typed yes to 'msc eula accept' in an interactive terminal");
        Files.createDirectories(file.toAbsolutePath().getParent());
        Files.writeString(file, GSON.toJson(o) + "\n");
    }

    /**
     * Writes {@code eula=true} into {@code eulaTxt} when it does not say so yet and {@code file} holds an acceptance.
     *
     * @return the acceptance that was applied, or empty when nothing was written
     */
    public static Optional<Acceptance> applyTo(Path file, Path eulaTxt) throws IOException {
        if (ServerManager.eulaAccepted(eulaTxt)) {
            return Optional.empty();
        }
        Optional<Acceptance> a = read(file);
        if (a.isPresent()) {
            Files.createDirectories(eulaTxt.toAbsolutePath().getParent());
            Files.writeString(eulaTxt, "# Minecraft EULA (" + EULA_URL + ") accepted by " + a.get().describe()
                    + " for this machine with 'msc eula accept' (" + file + ")\neula=true\n");
        }
        return a;
    }

    /**
     * The command the owner runs once, in their own terminal, for an install in {@code installDir} (null: not run
     * from an install).
     */
    public static String acceptCommand(Path installDir) {
        if (installDir == null) {
            return "the 'eula accept' command that ./gradlew :msc-core:installMsc prints";
        }
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        return "\"" + java + "\" -cp \"" + installDir.resolve("lib") + File.separator + "*\" "
                + "io.github.imnotstable.msc.core.Main eula accept";
    }
}
