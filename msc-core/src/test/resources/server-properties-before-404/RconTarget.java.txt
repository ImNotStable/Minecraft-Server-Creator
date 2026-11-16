package io.github.imnotstable.msc.core.server;

import io.github.imnotstable.msc.process.rcon.RconClient;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;

/**
 * Where a dev server answers RCON, from its {@code server.properties}. Used to stop a server whose console belongs to a
 * core that is gone, so it saves the world instead of being killed. The talking is done by msc-process's
 * {@link RconClient}, the one RCON client msc-core and msc-bench share (ADR 0032).
 */
public record RconTarget(String host, int port, String password) {

    /** How long connecting, logging in and waiting for the reply to {@code stop} may each take. */
    static final Duration TIMEOUT = Duration.ofSeconds(3);

    /** RCON settings from a server directory's {@code server.properties}, or null when RCON is off or unset. */
    public static RconTarget of(Path serverDir) {
        Path file = serverDir.resolve("server.properties");
        if (!Files.isRegularFile(file)) {
            return null;
        }
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            p.load(in);
        } catch (IOException | IllegalArgumentException e) {
            return null;
        }
        String password = p.getProperty("rcon.password", "");
        if (!"true".equals(p.getProperty("enable-rcon", "false").strip()) || password.isBlank()) {
            return null;
        }
        try {
            return new RconTarget("127.0.0.1", Integer.parseInt(p.getProperty("rcon.port", "25575").strip()), password);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Logs in and sends {@code stop}. Returns false when the server could not be reached or refused the login; true
     * once the command went out, even when the server closed the connection before answering, as a stopping server
     * may.
     */
    public boolean askToStop() {
        RconClient client;
        try {
            client = RconClient.connect(host, port, password, TIMEOUT);
        } catch (IOException e) {
            return false;
        }
        try (client) {
            client.command("stop");
        } catch (IOException e) {
            // "stop" may close the connection before answering; the command was still received.
        }
        return true;
    }
}
