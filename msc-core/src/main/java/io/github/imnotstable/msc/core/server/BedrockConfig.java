package io.github.imnotstable.msc.core.server;

import io.github.imnotstable.msc.core.tool.ToolException;
import io.github.imnotstable.msc.process.AtomicFile;
import io.github.imnotstable.msc.process.ServerAddress;
import io.github.imnotstable.msc.process.ServerPropertiesFile;
import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Geyser-Spigot's {@code plugins/Geyser-Spigot/config.yml} on a Paper or Purpur dev server (ADR 0046): the Bedrock
 * port and address, and Floodgate sign-in. A new file holds only those keys and Geyser's {@code config-version};
 * Geyser fills in the rest with its defaults. An existing file is the user's: a rerun changes only
 * {@code bedrock.port}, and only when another program holds it. Geyser-Spigot fronts the server it runs in, so the
 * Java address and port are the server's own and are not in the file.
 */
public final class BedrockConfig {
    /** Geyser's default Bedrock port, tried first. */
    public static final int DEFAULT_PORT = 19132;
    /** How many ports from {@link #DEFAULT_PORT} up are tried. */
    static final int TRIES = 100;
    /** Geyser's config layout version this file is written in (Geyser migrates it forward itself). */
    static final int CONFIG_VERSION = 12;

    /** Whether a UDP port can be bound on a host; tests replace it. */
    @FunctionalInterface
    public interface PortCheck {
        boolean free(String host, int port);
    }

    /** What {@link #configure} left: where Bedrock players join, and what it changed. */
    public record Result(String address, int port, Change change) {
    }

    /** What {@link #configure} did to the file. */
    public enum Change { WROTE, KEPT, MOVED }

    private BedrockConfig() {
    }

    /** {@code <server>/plugins/Geyser-Spigot/config.yml}. */
    public static Path file(Path serverDir) {
        return serverDir.resolve("plugins").resolve("Geyser-Spigot").resolve("config.yml");
    }

    /** A new config bound like the Java server ({@code server-ip}), or the kept one with its port free. */
    public static Result configure(Path serverDir, PortCheck check) throws IOException {
        Path file = file(serverDir);
        if (!Files.exists(file)) {
            String address = bindAddress(ServerPropertiesFile.read(serverDir.resolve(ServerPropertiesFile.NAME)));
            int port = freePort(address, DEFAULT_PORT, check);
            Files.createDirectories(file.getParent());
            AtomicFile.write(file, to -> Files.writeString(to, fresh(address, port)));
            return new Result(address, port, Change.WROTE);
        }
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        String address = orDefault(get(lines, "bedrock", "address"), "0.0.0.0");
        int port = port(get(lines, "bedrock", "port"));
        if (check.free(address, port)) {
            return new Result(address, port, Change.KEPT);
        }
        int moved = freePort(address, port == DEFAULT_PORT ? DEFAULT_PORT + 1 : DEFAULT_PORT, check);
        List<String> changed = set(lines, "bedrock", "port", Integer.toString(moved));
        AtomicFile.write(file, to -> Files.writeString(to, String.join("\n", changed) + "\n"));
        return new Result(address, moved, Change.MOVED);
    }

    /**
     * The start report's line when the server folder has Geyser: where Bedrock players join and the Java port it
     * fronts. Null without Geyser; best effort, never fails a start.
     */
    public static String line(Path serverDir) {
        if (!Files.isRegularFile(serverDir.resolve("plugins").resolve(GeyserDownloader.Project.GEYSER.jarName()))) {
            return null;
        }
        try {
            Path file = file(serverDir);
            List<String> lines = Files.exists(file) ? Files.readAllLines(file, StandardCharsets.UTF_8) : List.of();
            return describe(orDefault(get(lines, "bedrock", "address"), "0.0.0.0"), port(get(lines, "bedrock", "port")),
                    ServerPropertiesFile.read(serverDir.resolve(ServerPropertiesFile.NAME)));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** The {@code bedrock.port} the server folder's Geyser config sets (its default when unreadable), or 0 for none. */
    public static int configuredPort(Path serverDir) {
        Path file = file(serverDir);
        if (!Files.isRegularFile(file)) {
            return 0;
        }
        try {
            return port(get(Files.readAllLines(file, StandardCharsets.UTF_8), "bedrock", "port"));
        } catch (IOException | RuntimeException e) {
            return 0;
        }
    }

    /** {@code bedrock: UDP 127.0.0.1:19132 (Geyser), Java port 25565; ...}: one line, for setup and start. */
    public static String describe(String address, int port, Map<String, String> properties) {
        ServerAddress java = ServerAddress.game(properties);
        String javaPort = java == null ? "none" : Integer.toString(java.port());
        boolean everywhere = address.equals("0.0.0.0") || address.equals("::");
        return "bedrock: UDP " + (address.contains(":") ? "[" + address + "]" : address) + ":" + port
                + " (Geyser, fronting Java port " + javaPort + "; Floodgate sign-in"
                + (everywhere ? ", open to the network)" : "; bedrock.address 0.0.0.0 in "
                + "plugins/Geyser-Spigot/config.yml lets the LAN in)");
    }

    /** Where Geyser binds: {@code server-ip} like the Java server, all addresses when it is empty. */
    static String bindAddress(Map<String, String> properties) {
        String ip = properties.getOrDefault("server-ip", "").strip();
        if (ip.startsWith("[") && ip.endsWith("]")) {
            ip = ip.substring(1, ip.length() - 1);
        }
        return ip.isEmpty() ? "0.0.0.0" : ip;
    }

    /** The first port from {@code first} up that {@code check} finds free; refuses when none of {@link #TRIES} is. */
    static int freePort(String address, int first, PortCheck check) {
        for (int i = 0; i < TRIES; i++) {
            int port = first + i;
            if (port <= 65535 && check.free(address, port)) {
                return port;
            }
        }
        throw new ToolException("no free UDP port for Bedrock from " + first + " to " + Math.min(first + TRIES - 1, 65535)
                + " on " + address + ": free one, or set bedrock.port in plugins/Geyser-Spigot/config.yml");
    }

    /** Binds a UDP socket on {@code host:port} and lets it go at once. */
    public static boolean udpFree(String host, int port) {
        try (DatagramSocket socket = new DatagramSocket(null)) {
            socket.setReuseAddress(false);
            socket.bind(new InetSocketAddress(host, port));
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    static String fresh(String address, int port) {
        return """
                # Written by Minecraft Server Creator's server_setup (ADR 0046). Geyser uses its defaults for every
                # setting not here. Your edits are kept; server_setup only moves bedrock.port when it is taken.
                bedrock:
                  # Bound like the Java server's server-ip. 0.0.0.0 lets phones and consoles on the LAN join.
                  address: %s
                  port: %d
                java:
                  # Bedrock players sign in with Xbox; Floodgate lets them in without a Java account.
                  auth-type: floodgate
                config-version: %d
                """.formatted(quote(address), port, CONFIG_VERSION);
    }

    private static String quote(String address) {
        return address.contains(":") ? "\"" + address + "\"" : address;
    }

    private static int port(String value) {
        try {
            int port = Integer.parseInt(value == null ? "" : value);
            return port >= 1 && port <= 65535 ? port : DEFAULT_PORT;
        } catch (NumberFormatException e) {
            return DEFAULT_PORT;
        }
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isEmpty() ? fallback : value;
    }

    private static final Pattern SECTION = Pattern.compile("([A-Za-z0-9_-]+):\\s*(#.*)?");
    private static final Pattern KEY = Pattern.compile("(\\s+)([A-Za-z0-9_-]+):(\\s*)(.*?)(\\s+#.*)?");

    /**
     * The value of {@code section.key} in a block-style YAML file, without quotes, or null. Only what this class
     * needs: a top-level section and a key at its first indent.
     */
    static String get(List<String> lines, String section, String key) {
        int at = find(lines, section, key);
        if (at < 0) {
            return null;
        }
        Matcher m = KEY.matcher(lines.get(at));
        if (!m.matches()) {
            return null;
        }
        String v = m.group(4).strip();
        if (v.length() >= 2 && (v.startsWith("\"") && v.endsWith("\"") || v.startsWith("'") && v.endsWith("'"))) {
            v = v.substring(1, v.length() - 1);
        }
        return v;
    }

    /**
     * The lines with {@code section.key} set to {@code value}: the value replaced in place (its indent and comment
     * kept), else the key added under the section, else the section added at the end.
     */
    static List<String> set(List<String> lines, String section, String key, String value) {
        List<String> out = new ArrayList<>(lines);
        int at = find(lines, section, key);
        if (at >= 0) {
            Matcher m = KEY.matcher(lines.get(at));
            if (m.matches()) {
                out.set(at, m.group(1) + key + ": " + value + (m.group(5) == null ? "" : m.group(5)));
                return out;
            }
        }
        int start = sectionStart(lines, section);
        if (start >= 0) {
            out.add(start + 1, indent(lines, start) + key + ": " + value);
        } else {
            out.add(section + ":");
            out.add("  " + key + ": " + value);
        }
        return out;
    }

    private static int sectionStart(List<String> lines, String section) {
        for (int i = 0; i < lines.size(); i++) {
            Matcher m = SECTION.matcher(lines.get(i));
            if (m.matches() && m.group(1).equals(section)) {
                return i;
            }
        }
        return -1;
    }

    /** The indent of the section's first child, or two spaces. */
    private static String indent(List<String> lines, int start) {
        for (int i = start + 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank() || line.strip().startsWith("#")) {
                continue;
            }
            Matcher m = KEY.matcher(line);
            return m.matches() ? m.group(1) : "  ";
        }
        return "  ";
    }

    /** The line of {@code key} at the section's first indent, or -1. */
    private static int find(List<String> lines, String section, String key) {
        int start = sectionStart(lines, section);
        if (start < 0) {
            return -1;
        }
        String indent = null;
        for (int i = start + 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank() || line.strip().startsWith("#")) {
                continue;
            }
            if (!Character.isWhitespace(line.charAt(0))) {
                return -1; // the next top-level key: the section ended
            }
            Matcher m = KEY.matcher(line);
            if (!m.matches()) {
                continue;
            }
            if (indent == null) {
                indent = m.group(1);
            }
            if (m.group(1).equals(indent) && m.group(2).equals(key)) {
                return i;
            }
        }
        return -1;
    }
}
