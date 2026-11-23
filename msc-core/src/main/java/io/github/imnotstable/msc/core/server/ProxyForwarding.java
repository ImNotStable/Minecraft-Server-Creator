package io.github.imnotstable.msc.core.server;

import io.github.imnotstable.msc.process.AtomicFile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Velocity's modern forwarding between a dev proxy and its Paper backend (ADR 0045). Both sides read one secret, kept
 * per machine, so a proxy workspace and a backend workspace set up apart still match. The proxy gets a whole
 * {@code velocity.toml} once; the backend gets the three {@code proxies.velocity} keys of {@code config/paper-global.yml}
 * set in place, every other line left as it was.
 */
public final class ProxyForwarding {
    /** Where the dev proxy listens: Velocity's own port, so it does not take the backend's 25565. */
    public static final String PROXY_ADDRESS = "127.0.0.1:25577";
    /** The backend the dev proxy sends players to: a dev server's default address. */
    public static final String BACKEND_ADDRESS = "127.0.0.1:25565";
    /** The proxy's file with the secret, named in {@code velocity.toml}. */
    public static final String SECRET_FILE = "forwarding.secret";
    /** The proxy's config. */
    public static final String VELOCITY_TOML = "velocity.toml";
    /** The backend's Paper config (Purpur and Folia read it too). */
    public static final String PAPER_GLOBAL = "config/paper-global.yml";
    /** Waterfall's (BungeeCord's) config. */
    public static final String BUNGEE_CONFIG = "config.yml";
    /** The backend's Spigot config, where BungeeCord forwarding is switched on. */
    public static final String SPIGOT_YML = "spigot.yml";

    private static final Pattern SECRET = Pattern.compile("[0-9a-f]{32,128}");

    private ProxyForwarding() {
    }

    /** The machine's secret in {@code file}, made (32 random hex digits) when there is none or it is not one. */
    public static String secret(Path file) throws IOException {
        if (Files.isRegularFile(file)) {
            String kept = Files.readString(file, StandardCharsets.UTF_8).strip();
            if (SECRET.matcher(kept).matches()) {
                ownerOnly(file);
                return kept;
            }
        }
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        String made = HexFormat.of().formatHex(bytes);
        Files.createDirectories(file.toAbsolutePath().getParent());
        AtomicFile.write(file, to -> Files.writeString(to, made + "\n"));
        ownerOnly(file);
        return made;
    }

    /** The secret lets anyone who reads it join a backend as any player, so only its owner may read it (POSIX). */
    static void ownerOnly(Path file) throws IOException {
        if (Files.getFileStore(file).supportsFileAttributeView(PosixFileAttributeView.class)) {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        }
    }

    /**
     * Sets up a Velocity folder: {@code forwarding.secret} always (so it matches the backend's), {@code velocity.toml}
     * only when missing, so the user's edits stay. Returns the lines for the setup report.
     */
    public static String proxy(Path dir, String secret) throws IOException {
        Files.createDirectories(dir);
        StringBuilder out = new StringBuilder();
        Path toml = dir.resolve(VELOCITY_TOML);
        if (Files.exists(toml)) {
            out.append(VELOCITY_TOML).append(" kept as it is\n");
        } else {
            AtomicFile.write(toml, to -> Files.writeString(to, velocityToml(), StandardCharsets.UTF_8));
            out.append("wrote ").append(VELOCITY_TOML).append(": players join ").append(PROXY_ADDRESS)
                    .append(", sent to the backend at ").append(BACKEND_ADDRESS).append(" by modern forwarding\n");
        }
        AtomicFile.write(dir.resolve(SECRET_FILE), to -> Files.writeString(to, secret, StandardCharsets.UTF_8));
        ownerOnly(dir.resolve(SECRET_FILE));
        out.append("backend: a paper, purpur or folia server set up with proxy=velocity, running on ")
                .append(BACKEND_ADDRESS).append('\n');
        return out.toString();
    }

    /**
     * Sets up a Waterfall folder: {@code config.yml} only when missing (BungeeCord fills in the keys it leaves out),
     * listening where Velocity would, offline, with IP forwarding to one backend. Returns the lines for the report.
     */
    public static String waterfall(Path dir) throws IOException {
        Files.createDirectories(dir);
        Path config = dir.resolve(BUNGEE_CONFIG);
        if (Files.exists(config)) {
            return BUNGEE_CONFIG + " kept as it is\n";
        }
        AtomicFile.write(config, to -> Files.writeString(to, bungeeConfig(), StandardCharsets.UTF_8));
        return "wrote " + BUNGEE_CONFIG + ": players join " + PROXY_ADDRESS + ", sent to the backend at "
                + BACKEND_ADDRESS + " by BungeeCord IP forwarding\n"
                + "backend: a paper, purpur or folia server set up with proxy=bungeecord, running on "
                + BACKEND_ADDRESS + "\n";
    }

    private static final Pattern LISTEN = Pattern.compile("(?m)^\\s*(?:bind\\s*=\\s*\"|-?\\s*host:\\s*)[^\"\\s]*:(\\d{1,5})");

    /** The port players join {@code proxy} at, from its config's bind or first listener; Velocity's default without. */
    public static int listenPort(Path dir, Platform proxy) {
        try {
            Matcher m = LISTEN.matcher(Files.readString(dir.resolve(proxy == Platform.WATERFALL ? BUNGEE_CONFIG
                    : VELOCITY_TOML), StandardCharsets.UTF_8));
            return m.find() ? Integer.parseInt(m.group(1)) : 25577;
        } catch (IOException e) {
            return 25577;
        }
    }

    /**
     * Points a proxy's config at {@code servers} (name to {@code host:port}, the first one where players land), in
     * place of the backends it listed: Velocity's {@code [servers]} table and its {@code try} list, BungeeCord's
     * {@code servers:} block and its listener's {@code priorities}. Every other line stays as it was.
     */
    public static void servers(Path dir, Platform proxy, java.util.SequencedMap<String, String> servers)
            throws IOException {
        Path file = dir.resolve(proxy == Platform.WATERFALL ? BUNGEE_CONFIG : VELOCITY_TOML);
        String before = Files.readString(file, StandardCharsets.UTF_8);
        String after = proxy == Platform.WATERFALL ? withBungeeServers(before, servers)
                : withVelocityServers(before, servers);
        AtomicFile.write(file, to -> Files.writeString(to, after, StandardCharsets.UTF_8));
    }

    static String withVelocityServers(String toml, java.util.SequencedMap<String, String> servers) {
        List<String> lines = new ArrayList<>(List.of(toml.split("\n", -1)));
        StringBuilder table = new StringBuilder("[servers]\n");
        servers.forEach((name, address) -> table.append(name).append(" = \"").append(address).append("\"\n"));
        table.append("try = [\n    \"").append(servers.firstEntry().getKey()).append("\"\n]\n");
        int start = -1;
        int end = lines.size();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (start < 0 && line.equals("[servers]")) {
                start = i;
            } else if (start >= 0 && line.startsWith("[")) {
                end = i;
                break;
            }
        }
        if (start < 0) {
            return toml + (toml.endsWith("\n") ? "" : "\n") + "\n" + table;
        }
        // Forced hosts name backends too: one that names a server no longer listed would fail Velocity's start.
        List<String> out = new ArrayList<>(lines.subList(0, start));
        out.addAll(List.of(table.toString().split("\n")));
        out.add("");
        List<String> rest = lines.subList(end, lines.size());
        for (int i = 0; i < rest.size(); i++) {
            out.add(rest.get(i));
            if (rest.get(i).strip().equals("[forced-hosts]")) {
                while (i + 1 < rest.size() && !rest.get(i + 1).strip().startsWith("[")) {
                    i++;
                }
                out.add("");
            }
        }
        return String.join("\n", out);
    }

    static String withBungeeServers(String yaml, java.util.SequencedMap<String, String> servers) {
        List<String> lines = new ArrayList<>(List.of(yaml.split("\n", -1)));
        if (!lines.isEmpty() && lines.getLast().isEmpty()) {
            lines.removeLast();
        }
        List<String> block = new ArrayList<>(List.of("servers:"));
        servers.forEach((name, address) -> block.addAll(List.of("  " + name + ":", "    address: " + address,
                "    motd: " + name, "    restricted: false")));
        int at = find(lines, 0, lines.size(), "", "servers:");
        if (at >= 0) {
            int end = blockEnd(lines, at, 0);
            lines.subList(at, end).clear();
            lines.addAll(at, block);
        } else {
            lines.addAll(block);
        }
        // Each listener's priorities: where a player lands, then where a kicked one goes.
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.strip().equals("priorities:")) {
                int indent = indentOf(line);
                int end = i + 1;
                while (end < lines.size() && lines.get(end).strip().startsWith("- ")
                        && indentOf(lines.get(end)) >= indent) {
                    end++;
                }
                lines.subList(i + 1, end).clear();
                String item = " ".repeat(indent) + "- ";
                lines.addAll(i + 1, servers.sequencedKeySet().stream().map(n -> item + n).toList());
            }
        }
        return String.join("\n", lines) + "\n";
    }

    /**
     * Turns BungeeCord forwarding ({@code settings.bungeecord} in {@code spigot.yml}) on or off in a backend, writing
     * the file when there is none and {@code on} (Spigot fills in the rest on its next start).
     */
    public static void bungeeBackend(Path serverDir, boolean on) throws IOException {
        Path file = serverDir.resolve(SPIGOT_YML);
        String before = Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
        if (before.isEmpty() && !on) {
            return;
        }
        String after = withNested(before, "settings:", "bungeecord: " + on);
        if (!after.equals(before)) {
            AtomicFile.write(file, to -> Files.writeString(to, after, StandardCharsets.UTF_8));
        }
    }

    /** True when the backend's {@code spigot.yml} has {@code settings.bungeecord: true}. */
    public static boolean bungeeEnabled(Path serverDir) throws IOException {
        Path file = serverDir.resolve(SPIGOT_YML);
        if (!Files.isRegularFile(file)) {
            return false;
        }
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        int settings = find(lines, 0, lines.size(), "", "settings:");
        int at = settings < 0 ? -1 : find(lines, settings + 1, blockEnd(lines, settings, 0), "  ", "bungeecord:");
        return at >= 0 && lines.get(at).substring("  bungeecord:".length()).split("#", 2)[0].strip().equals("true");
    }

    /** {@code yaml} with the top-level {@code section} holding {@code key} (one level down), added where missing. */
    static String withNested(String yaml, String section, String key) {
        List<String> lines = new ArrayList<>(yaml.isEmpty() ? List.of() : List.of(yaml.split("\n", -1)));
        if (!lines.isEmpty() && lines.getLast().isEmpty()) {
            lines.removeLast();
        }
        int at = find(lines, 0, lines.size(), "", section);
        if (at < 0) {
            lines.add(section);
            at = lines.size() - 1;
        }
        String name = key.substring(0, key.indexOf(':') + 1);
        int end = blockEnd(lines, at, 0);
        int found = find(lines, at + 1, end, "  ", name);
        if (found >= 0) {
            lines.set(found, "  " + key);
        } else {
            lines.add(end, "  " + key);
        }
        return String.join("\n", lines) + "\n";
    }

    /** The dev Waterfall's config: what BungeeCord needs to listen, forward and find one backend. */
    static String bungeeConfig() {
        return """
                # Dev proxy from Minecraft Server Creator (ADR 0088): loopback only, offline mode, IP forwarding.
                # Backends are under servers; a backend needs settings.bungeecord: true in its spigot.yml.
                listeners:
                - host: %s
                  query_port: 25577
                  query_enabled: false
                  motd: '&3MSC dev proxy'
                  max_players: 8
                  priorities:
                  - lobby
                  force_default_server: false
                  forced_hosts: {}
                  tab_list: GLOBAL_PING
                  tab_size: 60
                  bind_local_address: true
                  ping_passthrough: false
                  proxy_protocol: false
                online_mode: false
                ip_forward: true
                prevent_proxy_connections: false
                enforce_secure_profile: false
                log_commands: false
                log_pings: false
                connection_throttle: -1
                servers:
                  lobby:
                    address: %s
                    motd: lobby
                    restricted: false
                permissions:
                  default:
                  - bungeecord.command.server
                  - bungeecord.command.list
                  admin:
                  - bungeecord.command.alert
                  - bungeecord.command.end
                  - bungeecord.command.ip
                  - bungeecord.command.reload
                  - bungeecord.command.kick
                  - bungeecord.command.send
                  - bungeecord.command.find
                """.formatted(PROXY_ADDRESS, BACKEND_ADDRESS);
    }

    /**
     * Turns Velocity forwarding on ({@code secret} not null) or off in a backend's {@code config/paper-global.yml},
     * writing the file when there is none (Paper fills in the rest on its next start).
     */
    public static void backend(Path serverDir, String secret) throws IOException {
        Path file = serverDir.resolve(PAPER_GLOBAL);
        String before = Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
        String after = withVelocity(before, secret);
        if (!after.equals(before)) {
            Files.createDirectories(file.getParent());
            AtomicFile.write(file, to -> Files.writeString(to, after, StandardCharsets.UTF_8));
        }
        if (Files.isRegularFile(file)) {
            ownerOnly(file); // also a file an earlier version left readable
        }
    }

    /** True when the backend's {@code config/paper-global.yml} has {@code proxies.velocity.enabled: true}. */
    public static boolean enabled(Path serverDir) throws IOException {
        Path file = serverDir.resolve(PAPER_GLOBAL);
        if (!Files.isRegularFile(file)) {
            return false;
        }
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        int proxies = find(lines, 0, lines.size(), "", "proxies:");
        int velocity = proxies < 0 ? -1 : find(lines, proxies + 1, blockEnd(lines, proxies, 0), "  ", "velocity:");
        int at = velocity < 0 ? -1 : find(lines, velocity + 1, blockEnd(lines, velocity, 2), "    ", "enabled:");
        return at >= 0 && lines.get(at).substring("    enabled:".length()).split("#", 2)[0].strip().equals("true");
    }

    /**
     * {@code yaml} with {@code proxies.velocity.enabled}, {@code online-mode} and {@code secret} set: on with
     * {@code secret}, off without (the secret then stays as it was). Lines are matched by indentation, as Paper
     * writes the file (two spaces per level); missing sections and keys are added.
     */
    static String withVelocity(String yaml, String secret) {
        List<String> lines = new ArrayList<>(yaml.isEmpty() ? List.of() : List.of(yaml.split("\n", -1)));
        if (!lines.isEmpty() && lines.getLast().isEmpty()) {
            lines.removeLast();
        }
        List<String> keys = new ArrayList<>(List.of("enabled: " + (secret != null), "online-mode: false"));
        if (secret != null) {
            keys.add("secret: '" + secret + "'");
        }
        int proxies = find(lines, 0, lines.size(), "", "proxies:");
        if (proxies < 0) {
            if (secret == null) {
                return yaml;
            }
            lines.add("proxies:");
            proxies = lines.size() - 1;
        }
        int proxiesEnd = blockEnd(lines, proxies, 0);
        int velocity = find(lines, proxies + 1, proxiesEnd, "  ", "velocity:");
        if (velocity < 0) {
            lines.add(proxiesEnd, "  velocity:");
            velocity = proxiesEnd;
        }
        for (String key : keys) {
            String name = key.substring(0, key.indexOf(':') + 1);
            int end = blockEnd(lines, velocity, 2);
            int at = find(lines, velocity + 1, end, "    ", name);
            if (at >= 0) {
                lines.set(at, "    " + key);
            } else {
                lines.add(end, "    " + key);
            }
        }
        return String.join("\n", lines) + "\n";
    }

    /** The first line in {@code [from, to)} that is {@code indent + key}, followed by nothing or a value. */
    private static int find(List<String> lines, int from, int to, String indent, String key) {
        for (int i = from; i < to; i++) {
            String line = lines.get(i);
            if (line.startsWith(indent + key) && indentOf(line) == indent.length()) {
                return i;
            }
        }
        return -1;
    }

    /** The index after the last line of the block that starts at {@code start}, whose key is at {@code indent}. */
    private static int blockEnd(List<String> lines, int start, int indent) {
        int i = start + 1;
        int end = i;
        for (; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank() || line.stripLeading().startsWith("#")) {
                continue;
            }
            if (indentOf(line) <= indent) {
                break;
            }
            end = i + 1;
        }
        return end;
    }

    private static int indentOf(String line) {
        int n = 0;
        while (n < line.length() && line.charAt(n) == ' ') {
            n++;
        }
        return n;
    }

    /**
     * The dev proxy's whole config, Velocity 4.3's defaults (config version 2.9) except: loopback, offline mode (the
     * test player joins offline, as on a dev server), no key authentication, modern forwarding, and one backend.
     */
    static String velocityToml() {
        return """
                # Dev proxy from Minecraft Server Creator (ADR 0045): loopback only, offline mode, modern forwarding.
                # Add backends under [servers]. The secret is in forwarding.secret, the same as the backend's.
                config-version = "2.9"
                bind = "%s"
                motd = "<#09add3>MSC dev proxy"
                show-max-players = 8
                online-mode = false
                force-key-authentication = false
                prevent-client-proxy-connections = false
                player-info-forwarding-mode = "MODERN"
                forwarding-secret-file = "%s"
                announce-forge = false
                kick-existing-players = false
                sample-players-in-ping = false
                enable-player-address-logging = true

                [ping-passthrough]
                version = false
                players = false
                description = false
                favicon = false
                modinfo = false

                [packet-limiter]
                interval = 7
                packets-per-second = -1
                bytes-per-second = -1
                decompressed-bytes-per-second = 5242880

                [servers]
                lobby = "%s"
                try = [
                    "lobby"
                ]

                [forced-hosts]

                [advanced]
                compression-threshold = 256
                compression-level = -1
                login-ratelimit = 3000
                connection-timeout = 5000
                read-timeout = 30000
                haproxy-protocol = false
                tcp-fast-open = false
                bungee-plugin-message-channel = true
                show-ping-requests = false
                failover-on-unexpected-server-disconnect = true
                announce-proxy-commands = true
                log-command-executions = false
                log-player-connections = true
                accepts-transfers = false
                enable-reuse-port = false
                command-rate-limit = 50
                forward-commands-if-rate-limited = true
                kick-after-rate-limited-commands = 0
                tab-complete-rate-limit = 10
                kick-after-rate-limited-tab-completes = 0

                [query]
                enabled = false
                port = 25577
                map = "Velocity"
                show-plugins = false
                """.formatted(PROXY_ADDRESS, SECRET_FILE, BACKEND_ADDRESS);
    }
}
