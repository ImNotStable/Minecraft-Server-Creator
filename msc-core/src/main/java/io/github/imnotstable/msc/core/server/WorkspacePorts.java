package io.github.imnotstable.msc.core.server;

import io.github.imnotstable.msc.process.ServerAddress;
import io.github.imnotstable.msc.process.ServerPropertiesFile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The ports a workspace's server and MSC use for anything but the resource pack (ADR 0054): the one list a pack port is
 * checked against, when one is picked and when a remembered or configured one is taken again, so the pack host never
 * holds a port the server, the dev proxy, Geyser, RCON or the agent link needs (#755). Each port maps to what uses it,
 * for a refusal that names it.
 *
 * <p>It holds the defaults MSC sets up or a server falls back to (25565 for the game and the dev backend, 25575 for
 * RCON, 25577 for the dev proxy, 19132 for Geyser), what the server folder sets ({@code server-port}, {@code rcon.port}
 * and {@code query.port} in {@code server.properties}, {@code bedrock.port} in Geyser's config, every address and port
 * in {@code velocity.toml}), and the ports the core itself listens on (the agent link).
 */
public record WorkspacePorts(Map<Integer, String> uses) {
    /** The defaults alone: for a workspace whose server folder is not known. */
    public static final WorkspacePorts DEFAULTS = new WorkspacePorts(defaults());

    public WorkspacePorts {
        uses = Collections.unmodifiableMap(new LinkedHashMap<>(uses));
    }

    /** True when {@code port} is used for something else than the pack. */
    public boolean reserved(int port) {
        return uses.containsKey(port);
    }

    /** What uses {@code port}, such as {@code the server's port (server-port in server.properties)}, or null. */
    public String use(int port) {
        return uses.get(port);
    }

    /**
     * The ports reserved for the workspace whose server lives in {@code serverDir}, read now (the files change between
     * calls), plus {@code core}: ports this core listens on, each with what it is.
     */
    public static WorkspacePorts of(Path serverDir, Map<Integer, String> core) {
        Map<Integer, String> uses = defaults();
        Map<String, String> p = properties(serverDir);
        ServerAddress game = ServerAddress.game(p);
        if (game != null) {
            uses.put(game.port(), "the server's port (" + ServerAddress.GAME_PORT_KEY + " in "
                    + ServerPropertiesFile.NAME + ")");
        }
        ServerAddress rcon = ServerAddress.rcon(p);
        if (rcon != null && p.containsKey(ServerAddress.RCON_PORT_KEY)) {
            uses.put(rcon.port(), "the server's RCON port (" + ServerAddress.RCON_PORT_KEY + " in "
                    + ServerPropertiesFile.NAME + ")");
        }
        int query = number(p.get("query.port"));
        if (query > 0) {
            uses.put(query, "the server's query port (query.port in " + ServerPropertiesFile.NAME + ")");
        }
        int bedrock = BedrockConfig.configuredPort(serverDir);
        if (bedrock > 0) {
            uses.put(bedrock, "Geyser's Bedrock port (bedrock.port in plugins/Geyser-Spigot/config.yml)");
        }
        for (int port : velocityPorts(serverDir.resolve(ProxyForwarding.VELOCITY_TOML))) {
            uses.putIfAbsent(port, "a port in " + ProxyForwarding.VELOCITY_TOML);
        }
        core.forEach((port, use) -> {
            if (port != null && port > 0) {
                uses.put(port, use);
            }
        });
        return new WorkspacePorts(uses);
    }

    private static Map<Integer, String> defaults() {
        Map<Integer, String> uses = new LinkedHashMap<>();
        uses.put(ServerAddress.GAME_PORT, "the Minecraft server's default port, also the dev backend's");
        uses.put(ServerAddress.RCON_PORT, "RCON's default port");
        uses.put(port(ProxyForwarding.PROXY_ADDRESS), "the dev proxy's port (Velocity's default)");
        uses.put(BedrockConfig.DEFAULT_PORT, "Geyser's default Bedrock port");
        return uses;
    }

    private static Map<String, String> properties(Path serverDir) {
        try {
            return ServerPropertiesFile.read(serverDir.resolve(ServerPropertiesFile.NAME));
        } catch (IOException e) {
            return Map.of();
        }
    }

    /** {@code "host:port"} values and {@code port = n} keys, such as {@code bind}, the backends and the query port. */
    private static final Pattern VELOCITY_PORT = Pattern.compile("\"[^\"]*:(\\d{1,5})\"|^\\s*port\\s*=\\s*(\\d{1,5})",
            Pattern.MULTILINE);

    static List<Integer> velocityPorts(Path toml) {
        String text;
        try {
            text = Files.readString(toml, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return List.of();
        }
        List<Integer> ports = new java.util.ArrayList<>();
        Matcher m = VELOCITY_PORT.matcher(text);
        while (m.find()) {
            int port = number(m.group(1) != null ? m.group(1) : m.group(2));
            if (port > 0) {
                ports.add(port);
            }
        }
        return ports;
    }

    private static int port(String address) {
        return number(address.substring(address.lastIndexOf(':') + 1));
    }

    /** A port from 1 to 65535, else 0. */
    private static int number(String value) {
        try {
            int port = Integer.parseInt(value == null ? "" : value.strip());
            return port >= 1 && port <= 65535 ? port : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
