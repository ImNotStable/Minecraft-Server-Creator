package io.github.imnotstable.msc.bench;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Stands in for a Paper server in tests that start one: answers RCON on the port in {@code args[0]} with password
 * {@code args[1]}, replies "There are 0 players online" to {@code list}, and exits on {@code stop}, leaving
 * {@link #STOPPED} in its working folder.
 */
public final class FakeRconMain {

    /** The file a stop over RCON leaves in the working folder: a server killed instead leaves none. */
    public static final String STOPPED = "stopped-by-rcon.txt";

    private FakeRconMain() {
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        new FakeRconServer(Integer.parseInt(args[0]), args[1], command -> {
            if (command.equals("stop")) {
                try {
                    Files.writeString(Path.of(STOPPED), "stop received over RCON");
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                Thread.ofPlatform().start(() -> {
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    Runtime.getRuntime().halt(0);
                });
                return "Stopping the server";
            }
            return command.equals("list") ? "There are 0 players online" : "Unknown command";
        });
        Thread.sleep(Long.MAX_VALUE);
    }
}
