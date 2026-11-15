package io.github.imnotstable.msc.bench;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Stands in for a server that speaks no RCON, such as a Minestom game server (#577): listens on loopback port
 * {@code args[0]} (0 for any free one), writes the port it got to {@code args[1]} once it listens, and accepts
 * connections until killed.
 */
public final class PortHolder {

    private PortHolder() {
    }

    public static void main(String[] args) throws IOException {
        try (ServerSocket socket = new ServerSocket(Integer.parseInt(args[0]), 50, InetAddress.getLoopbackAddress())) {
            Path file = Path.of(args[1]);
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temp, Integer.toString(socket.getLocalPort()));
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE);
            List<Socket> held = new ArrayList<>();
            while (true) {
                held.add(socket.accept());
            }
        }
    }

    /**
     * Starts a holder of {@code port} (0: any) in {@code dir}, with each of {@code extra} on its command line (as a jar
     * path names a folder); returns once it listens.
     */
    public static Process start(Path dir, int port, Path portFile, String... extra) throws IOException {
        // The extra arguments come first, as system properties: the system cuts long command lines (the class path).
        List<String> command = new ArrayList<>(List.of(ProcessHandle.current().info().command().orElse("java")));
        for (String argument : extra) {
            command.add("-Dmsc.test.path=" + argument);
        }
        command.addAll(List.of("-cp", System.getProperty("java.class.path"), PortHolder.class.getName(),
                Integer.toString(port), portFile.toAbsolutePath().toString()));
        Process process = new ProcessBuilder(command).directory(dir.toFile())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectErrorStream(true).start();
        awaitPort(process, portFile);
        return process;
    }

    /** The port in {@code portFile} once {@code holder} wrote it; throws if the holder exits first. */
    public static int awaitPort(Process holder, Path portFile) throws IOException {
        while (!Files.exists(portFile)) {
            if (!holder.isAlive()) {
                throw new IOException("the port holder exited with " + holder.exitValue());
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", e);
            }
        }
        return Integer.parseInt(Files.readString(portFile).strip());
    }
}
