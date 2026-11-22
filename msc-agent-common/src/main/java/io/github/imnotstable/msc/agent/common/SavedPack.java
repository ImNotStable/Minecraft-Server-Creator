package io.github.imnotstable.msc.agent.common;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/**
 * The workspace's last resource pack, kept beside the server by MSC in {@code msc-pack/} (ADR 0076), served by the
 * agent itself when no MSC session started the server, so a server started by hand still sends players its pack: the
 * agent hosts the zip and sends it to each player who joins, as it does with a pack MSC applies. An MSC session that
 * connects later applies its own pack, which replaces this one. Plain sockets from java.base, so it runs in the
 * module layers of NeoForge and Forge too, on daemon threads, so it never holds the server's JVM open.
 */
public final class SavedPack implements AutoCloseable {
    /** The folder in the server's directory: {@code pack.zip}, and {@code host}, the address players reach. */
    public static final String DIR = "msc-pack";
    private final ServerSocket socket;
    private final String url;

    private SavedPack(ServerSocket socket, String url) {
        this.socket = socket;
        this.url = url;
    }

    /**
     * Serve the saved pack in {@code serverDir} when this server has no MSC session and the pack is there; empty
     * otherwise. A pack that cannot be served is logged, never thrown: the server starts as usual.
     */
    public static Optional<SavedPack> serveIfIdle(Path serverDir, ServerOps ops, AgentLog log) {
        return CoreAddress.resolve().isPresent() ? Optional.empty() : serve(serverDir, ops, log);
    }

    static Optional<SavedPack> serve(Path serverDir, ServerOps ops, AgentLog log) {
        Path zip = serverDir.resolve(DIR).resolve("pack.zip");
        if (!Files.isRegularFile(zip)) {
            return Optional.empty();
        }
        try {
            byte[] data = Files.readAllBytes(zip);
            Path hostFile = serverDir.resolve(DIR).resolve("host");
            String host = Files.isRegularFile(hostFile) ? Files.readString(hostFile, StandardCharsets.UTF_8).strip()
                    : "127.0.0.1";
            String sha1 = sha1(data);
            String path = "/pack-" + sha1 + ".zip";
            ServerSocket socket = new ServerSocket(0, 16, InetAddress.getByName(host));
            String url = "http://" + host + ":" + socket.getLocalPort() + path;
            Thread accept = new Thread(() -> {
                while (!socket.isClosed()) {
                    try {
                        Socket client = socket.accept();
                        Thread answer = new Thread(() -> answer(client, path, data), "msc-saved-pack-answer");
                        answer.setDaemon(true);
                        answer.start();
                    } catch (IOException e) {
                        return; // closed
                    }
                }
            }, "msc-saved-pack");
            accept.setDaemon(true);
            accept.start();
            ops.standby(url, sha1);
            log.info("serving the saved resource pack at " + url);
            return Optional.of(new SavedPack(socket, url));
        } catch (IOException | RuntimeException e) {
            log.warn("the saved resource pack in " + zip + " is not served: " + e.getMessage());
            return Optional.empty();
        }
    }

    /** One request: the pack for a GET of its path, 404 for anything else. */
    private static void answer(Socket client, String path, byte[] data) {
        try (client) {
            client.setSoTimeout(10_000);
            BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.ISO_8859_1));
            String request = in.readLine();
            for (String line = in.readLine(); line != null && !line.isEmpty(); line = in.readLine()) {
                // Headers are not needed.
            }
            OutputStream out = client.getOutputStream();
            String[] parts = request == null ? new String[0] : request.split(" ");
            boolean ok = parts.length >= 2 && parts[0].equals("GET") && parts[1].equals(path);
            String head = ok ? "HTTP/1.1 200 OK\r\nContent-Type: application/zip\r\nContent-Length: " + data.length
                    : "HTTP/1.1 404 Not Found\r\nContent-Length: 0";
            out.write((head + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            if (ok) {
                out.write(data);
            }
            out.flush();
        } catch (IOException e) {
            // The client went away; it asks again.
        }
    }

    static String sha1(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The address players download the pack from. */
    public String url() {
        return url;
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException e) {
            // Already closed.
        }
    }
}
