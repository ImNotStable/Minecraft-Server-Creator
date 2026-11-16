package {{packageName}};

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.minestom.server.MinecraftServer;
import net.minestom.server.command.CommandManager;
import net.minestom.server.command.CommandSender;
import net.minestom.server.command.builder.CommandResult;

/**
 * Reads commands from standard input: {@code stop} shuts the server down cleanly (Minecraft Server
 * Creator stops the server this way), anything else runs as a console command.
 *
 * <p>This thread only reads lines. Each command runs on the tick thread, at the start of the next tick, like
 * players' commands and msc's {@code server_command}, so it never runs at the same time as event listeners.
 * The command replies to the console sender from there, which logs the reply. The console waits for each command
 * (at most {@link #WAIT}) before it reads the next line, so commands run in the order typed and {@code stop} comes
 * after them; a command still running after the wait is reported and left to finish.
 */
final class Console {
    static final Duration WAIT = Duration.ofSeconds(30);

    private Console() {
    }

    static void start() {
        Thread thread = new Thread(Console::run, "console");
        thread.setDaemon(true);
        thread.start();
    }

    /** Runs {@code command} as {@code sender} on the next tick of {@code tick} (the server's scheduler). */
    static CompletableFuture<CommandResult> dispatch(CommandManager commands, CommandSender sender, String command,
                                                     Executor tick) {
        return CompletableFuture.supplyAsync(() -> commands.execute(sender, command), tick);
    }

    private static void run() {
        CommandManager commands = MinecraftServer.getCommandManager();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                String command = line.strip();
                if (command.equals("stop")) {
                    MinecraftServer.stopCleanly();
                    return;
                }
                if (!command.isEmpty()) {
                    // As the console sender, so replies are printed to the log.
                    CompletableFuture<CommandResult> result = dispatch(commands, commands.getConsoleSender(), command,
                            MinecraftServer.getSchedulerManager());
                    if (!await(command, result)) {
                        return;
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("Console closed: " + e.getMessage());
        }
    }

    /** Waits for the command; false when this thread was interrupted and should stop. */
    private static boolean await(String command, CompletableFuture<CommandResult> result) {
        try {
            result.get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            System.err.println("Still running after " + WAIT.toSeconds() + "s, reading the next command: " + command);
        } catch (ExecutionException e) {
            System.err.println("Command failed: " + command + ": " + e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        return true;
    }
}
