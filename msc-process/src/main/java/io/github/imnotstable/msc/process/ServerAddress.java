package io.github.imnotstable.msc.process;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.Map;

/**
 * Where to dial a server on this machine, from the keys of its {@code server.properties} as the 26.3 server reads
 * them ({@link ServerPropertiesFile#read}). The one place that turns {@code server-ip} and a port key into an
 * address, for the game port and the RCON port alike, so a test player and an RCON client reach the same server.
 *
 * <p>The host is {@code server-ip}, the address the server binds both ports to, with loopback in place of an empty
 * value or a wildcard address ({@code 0.0.0.0}, {@code ::} and their other spellings, bracketed or not): a client
 * cannot connect to a wildcard address everywhere (Windows refuses it). Brackets around an IPv6 literal go. Any other
 * value, a host name included, is dialled as written: the server resolves the same name to bind.
 *
 * <p>The port is the key's value when {@link Integer#parseInt} reads it, untrimmed, and the default otherwise, as the
 * server does. A port outside 1 to 65535 gives no address: the server listens on no port a client can be sent to.
 */
public record ServerAddress(String host, int port) {
    /** {@code server-port}: the game port. */
    public static final String GAME_PORT_KEY = "server-port";
    /** {@code rcon.port}: the RCON port. */
    public static final String RCON_PORT_KEY = "rcon.port";
    /** The server's game port when {@code server-port} is missing or not a number. */
    public static final int GAME_PORT = 25565;
    /** The server's RCON port when {@code rcon.port} is missing or not a number. */
    public static final int RCON_PORT = 25575;

    /** The game port's address, or null when {@code server-port} is outside 1 to 65535. */
    public static ServerAddress game(Map<String, String> properties) {
        return of(properties, GAME_PORT_KEY, GAME_PORT);
    }

    /** The RCON port's address, or null when {@code rcon.port} is outside 1 to 65535. */
    public static ServerAddress rcon(Map<String, String> properties) {
        return of(properties, RCON_PORT_KEY, RCON_PORT);
    }

    private static ServerAddress of(Map<String, String> properties, String portKey, int defaultPort) {
        int port;
        try {
            port = Integer.parseInt(properties.getOrDefault(portKey, ""));
        } catch (NumberFormatException e) {
            port = defaultPort;
        }
        if (port < 1 || port > 65535) {
            return null;
        }
        return new ServerAddress(host(properties.getOrDefault("server-ip", "")), port);
    }

    /** Where to reach a server bound to {@code serverIp}: loopback for none or a wildcard, else that address. */
    static String host(String serverIp) {
        if (serverIp.isEmpty()) {
            return "127.0.0.1";
        }
        InetAddress literal;
        try {
            literal = InetAddress.ofLiteral(serverIp); // never a name lookup
        } catch (IllegalArgumentException e) {
            return serverIp;
        }
        if (literal.isAnyLocalAddress()) {
            return literal instanceof Inet4Address ? "127.0.0.1" : "::1";
        }
        return serverIp.startsWith("[") && serverIp.endsWith("]") ? serverIp.substring(1, serverIp.length() - 1)
                : serverIp;
    }
}
