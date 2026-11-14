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
import java.util.Objects;
import java.util.Optional;

/**
 * The owner's one-time agreement to the Minecraft EULA for this machine, in {@code ~/.msc/eula.json}.
 *
 * <p>With it, {@code server_setup} and {@code server_start} write {@code eula=true} into the server's {@code eula.txt}.
 * It is written only on an explicit opt-in by the owner: {@code yes} to {@link EulaCommand}, {@code installMsc
 * -Pmsc.acceptEula=true}, or {@code acceptEula=true} on {@code server_setup} or {@code server_start}. Never by default,
 * and never by installing or using MSC alone. See ADR 0020.
 *
 * <p>The file's path never leaves this class (ADR 0036): other code holds a {@code MachineEula}, asks it
 * {@link #read}, has it {@link #applyTo} a server's {@code eula.txt}, calls its one write,
 * {@link #recordAcceptance}, and prints it with {@link #toString}. Writing the acceptance any other way would mean
 * building the path again, file name and all, which EulaWritersTest's literal rule reports.
 */
public final class MachineEula {
    public static final String EULA_URL = "https://aka.ms/MinecraftEULA";
    /** MSC's terms for turning EULA acceptance on. */
    public static final String TERMS_URL = "https://github.com/ImNotStable/Minecraft-Server-Creator/blob/main/TERMS.md";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** Who agreed, where and when. */
    public record Acceptance(String user, String host, Instant acceptedAt) {
        /** For tool results: {@code jeremiah on Flux, 2026-11-12}. */
        public String describe() {
            return user + " on " + host + ", " + acceptedAt.toString().substring(0, 10);
        }
    }

    private static final String FILE_NAME = "eula.json";
    private static final MachineEula NONE = new MachineEula(null);

    /** The acceptance file, or null for {@link #none}. Private: see the class comment. */
    private final Path file;

    private MachineEula(Path file) {
        this.file = file;
    }

    /** The acceptance of the owner whose home folder is {@code home}: {@code <home>/.msc/eula.json}. */
    public static MachineEula forHome(Path home) {
        return new MachineEula(home.resolve(".msc").resolve(FILE_NAME));
    }

    /** No machine-wide acceptance at all (a config built by tests): nothing is read and nothing can be written. */
    public static MachineEula none() {
        return NONE;
    }

    /** False for {@link #none}. */
    public boolean configured() {
        return file != null;
    }

    /** The owner's acceptance, or empty when there is none or the file is not a complete acceptance. */
    public Optional<Acceptance> read() {
        return read(file);
    }

    /** The acceptance in {@code file}, or empty when there is no file or it is not a complete acceptance. */
    static Optional<Acceptance> read(Path file) {
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

    /** How the owner agreed, stored in the file. installMsc (msc-core/build.gradle.kts) writes its own value. */
    public static final String VIA_TERMINAL = "typed yes to 'Main eula accept' in an interactive terminal";
    public static final String VIA_TOOL = "the user's own yes, passed as acceptEula=true";

    /** Records the owner's explicit agreement; callers must only pass one the owner gave (ADR 0020). */
    public void recordAcceptance(Acceptance a, String how) throws IOException {
        if (file == null) {
            throw new IllegalStateException("no machine EULA file to write");
        }
        JsonObject o = new JsonObject();
        o.addProperty("eula", EULA_URL);
        o.addProperty("accepted", true);
        o.addProperty("acceptedBy", a.user());
        o.addProperty("host", a.host());
        o.addProperty("acceptedAt", a.acceptedAt().toString());
        o.addProperty("how", how);
        Files.createDirectories(file.toAbsolutePath().getParent());
        Files.writeString(file, GSON.toJson(o) + "\n");
    }

    /** Deletes the acceptance; false when there was none. */
    public boolean revoke() throws IOException {
        return file != null && Files.deleteIfExists(file);
    }

    /**
     * Writes {@code eula=true} into {@code eulaTxt} when it does not say so yet and the owner has an acceptance.
     *
     * @return the acceptance that was applied, or empty when nothing was written
     */
    public Optional<Acceptance> applyTo(Path eulaTxt) throws IOException {
        if (ServerManager.eulaAccepted(eulaTxt)) {
            return Optional.empty();
        }
        Optional<Acceptance> a = read();
        if (a.isPresent()) {
            Files.createDirectories(eulaTxt.toAbsolutePath().getParent());
            Files.writeString(eulaTxt, "# Minecraft EULA (" + EULA_URL + ") accepted by " + a.get().describe()
                    + " for this machine (" + file + ")\neula=true\n");
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

    /** Where the acceptance is kept, for messages to the user. */
    @Override
    public String toString() {
        return file == null ? "no machine EULA file" : file.toString();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof MachineEula m && Objects.equals(file, m.file);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(file);
    }
}
