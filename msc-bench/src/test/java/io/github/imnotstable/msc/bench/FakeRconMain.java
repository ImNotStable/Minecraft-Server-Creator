package io.github.imnotstable.msc.bench;

import java.io.IOException;

/**
 * Stands in for a Paper server in tests that start one: answers RCON on the port in {@code args[0]} with password
 * {@code args[1]}, replies "There are 0 players online" to {@code list}, and exits on {@code stop}.
 */
public final class FakeRconMain {

    private FakeRconMain() {
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        new FakeRconServer(Integer.parseInt(args[0]), args[1], command -> {
            if (command.equals("stop")) {
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
