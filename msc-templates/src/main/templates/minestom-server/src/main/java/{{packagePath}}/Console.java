package {{packageName}};

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import net.minestom.server.MinecraftServer;

/**
 * Reads commands from standard input: {@code stop} shuts the server down cleanly (Minecraft Server
 * Creator stops the server this way), anything else runs as a console command.
 */
final class Console {
    private Console() {
    }

    static void start() {
        Thread thread = new Thread(Console::run, "console");
        thread.setDaemon(true);
        thread.start();
    }

    private static void run() {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                String command = line.strip();
                if (command.equals("stop")) {
                    MinecraftServer.stopCleanly();
                    return;
                }
                if (!command.isEmpty()) {
                    MinecraftServer.getCommandManager().executeServerCommand(command);
                }
            }
        } catch (IOException e) {
            System.err.println("Console closed: " + e.getMessage());
        }
    }
}
