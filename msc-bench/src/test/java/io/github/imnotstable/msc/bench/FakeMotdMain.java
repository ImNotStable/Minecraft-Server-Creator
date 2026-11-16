package io.github.imnotstable.msc.bench;

import io.github.imnotstable.msc.process.rcon.FakeRconServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Stands in for a Paper server running a correct code-config-motd plugin: answers RCON on the port in {@code args[0]}
 * with password {@code args[1]}. On start it reads {@code motd} from {@code plugins/BenchPlugin/config.yml}, writing
 * the shipped default there first only when the file is missing, as Paper's {@code saveDefaultConfig} does.
 * {@code benchmotd} replies with it, {@code benchmotd set <text>} changes and saves it, and {@code stop} exits.
 */
public final class FakeMotdMain {

    /** The plugin's saved config, relative to the server folder. */
    public static final String CONFIG = "plugins/BenchPlugin/config.yml";
    /** The value the plugin ships in its jar. */
    public static final String DEFAULT = "Welcome to MSC";

    private FakeMotdMain() {
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        Path config = Path.of(CONFIG);
        if (!Files.exists(config)) {
            Files.createDirectories(config.toAbsolutePath().getParent());
            Files.writeString(config, "motd: " + DEFAULT + "\n");
        }
        String[] motd = {Files.readString(config).strip().substring("motd: ".length())};
        new FakeRconServer(Integer.parseInt(args[0]), args[1], command -> {
            if (!command.equals("stop")) {
                return reply(command, motd, config);
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
        });
        Thread.sleep(Long.MAX_VALUE);
    }

    /**
     * The plugin's reply to a command other than {@code stop}, shared with a test's in-process server that plays a
     * server the agent left running.
     */
    public static String reply(String command, String[] motd, Path config) {
        try {
            if (command.startsWith("benchmotd set ")) {
                motd[0] = command.substring("benchmotd set ".length());
                Files.writeString(config, "motd: " + motd[0] + "\n");
                return "Saved";
            }
            return command.equals("benchmotd") ? motd[0] : "Unknown command";
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
