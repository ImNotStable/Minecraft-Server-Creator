package io.github.imnotstable.msc.core.server;

import io.github.imnotstable.msc.process.ServerPropertiesFile;
import io.github.imnotstable.msc.process.rcon.RconClient;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/**
 * Where a dev server answers RCON, from its {@code server.properties}. Used to stop a server whose console belongs to a
 * core that is gone, so it saves the world instead of being killed. The talking is done by msc-process's
 * {@link RconClient}, the one RCON client msc-core and msc-bench share (ADR 0032).
 */
public record RconTarget(String host, int port, String password) {

    /** How long connecting, logging in and waiting for the reply to {@code stop} may each take. */
    static final Duration TIMEOUT = Duration.ofSeconds(3);
    /** The server's {@code rcon.port} when the file has none or not a number. */
    static final int DEFAULT_PORT = 25575;

    /**
     * RCON settings from a server directory's {@code server.properties}, read as the 26.3 server reads them, or null
     * when the server runs no RCON: {@code enable-rcon} not true (any case, as {@link Boolean#parseBoolean}), no
     * password, or a port outside 1 to 65535 (the server logs "rcon disabled"). A port that is not a number is the
     * default 25575, as for the server. The host is {@code server-ip}, the address the server binds RCON to, or
     * loopback when that is empty or a wildcard address.
     */
    public static RconTarget of(Path serverDir) {
        Map<String, String> p;
        try {
            p = ServerPropertiesFile.read(serverDir.resolve(ServerPropertiesFile.NAME));
        } catch (IOException e) {
            return null;
        }
        String password = p.getOrDefault("rcon.password", "");
        if (!Boolean.parseBoolean(p.get("enable-rcon")) || password.isEmpty()) {
            return null;
        }
        int port;
        try {
            port = Integer.parseInt(p.getOrDefault("rcon.port", ""));
        } catch (NumberFormatException e) {
            port = DEFAULT_PORT;
        }
        if (port <= 0 || port > 65535) {
            return null;
        }
        return new RconTarget(host(p.getOrDefault("server-ip", "")), port, password);
    }

    /** Where to reach a server bound to {@code serverIp}: that address, or loopback for none or a wildcard. */
    static String host(String serverIp) {
        return switch (serverIp) {
            case "", "0.0.0.0" -> "127.0.0.1";
            case "::", "[::]", "::0" -> "::1";
            default -> serverIp;
        };
    }

    /**
     * Logs in and sends {@code stop}. Returns false when the server could not be reached or refused the login, and
     * never throws, so a caller's fallback (kill) always runs (#394); true once the command went out, even when the
     * server closed the connection before answering, as a stopping server may.
     */
    public boolean askToStop() {
        RconClient client;
        try {
            client = RconClient.connect(host, port, password, TIMEOUT);
        } catch (IOException | RuntimeException e) {
            return false;
        }
        try (client) {
            client.command("stop");
        } catch (IOException | RuntimeException e) {
            // "stop" may close the connection before answering; the command was still received.
        }
        return true;
    }
}
