package io.github.imnotstable.msc.bench.run;

import io.github.imnotstable.msc.bench.rcon.RconClient;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Runs the dev server of one workspace. Connects to a server that is already up (for example one the
 * agent left running) before starting a new one, and always stops it on close.
 */
public final class ServerManager implements AutoCloseable {

    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(30);

    private final Path serverDir;
    private final BenchConfig.Server config;
    private final Map<String, String> env;
    private Process process;
    private RconClient rcon;
    private boolean reachedRcon;
    private IOException startFailure;

    public ServerManager(Path serverDir, BenchConfig.Server config, Map<String, String> env) {
        this.serverDir = serverDir;
        this.config = config;
        this.env = Map.copyOf(env);
    }

    /** The console connection, starting the server first if nothing answers on the RCON port. */
    public synchronized RconClient rcon() throws IOException {
        if (rcon == null) {
            rcon = tryConnect(COMMAND_TIMEOUT);
        }
        if (rcon == null) {
            // A server that failed to start would fail the same way for every later check, and a second start
            // would only meet the ports and world lock of the first.
            if (startFailure != null) {
                throw new IOException(startFailure.getMessage(), startFailure);
            }
            try {
                try {
                    start();
                } catch (IOException first) {
                    // A launcher download cut short makes Paper exit on start; one retry fetches it again.
                    if (process == null || process.isAlive()) {
                        throw first;
                    }
                    start();
                }
            } catch (IOException e) {
                kill();
                startFailure = e;
                throw e;
            }
        }
        reachedRcon = true;
        return rcon;
    }

    /** True once the server answered on RCON, so its downloaded files are complete. */
    public synchronized boolean reachedRcon() {
        return reachedRcon;
    }

    private void start() throws IOException {
        if (!Files.isRegularFile(serverDir.resolve("paper.jar"))) {
            throw new IOException("no server/paper.jar in the workspace");
        }
        Path eula = serverDir.resolve("eula.txt");
        if (!Files.isRegularFile(eula) || !Files.readString(eula).contains("eula=true")) {
            throw new IOException("Minecraft EULA not accepted on this machine ("
                    + MachineEula.file(Path.of(System.getProperty("user.home"))) + ")");
        }
        ProcessBuilder builder = new ProcessBuilder(List.copyOf(config.command()))
                .directory(serverDir.toFile())
                .redirectErrorStream(true)
                .redirectOutput(serverDir.resolve("bench-console.log").toFile())
                .redirectInput(ProcessBuilder.Redirect.PIPE);
        builder.environment().putAll(env);
        kill();
        process = builder.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(config.startTimeoutSeconds());
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) {
                throw new IOException("server exited with code " + process.exitValue() + "; see server/bench-console.log");
            }
            rcon = tryConnect(COMMAND_TIMEOUT);
            if (rcon != null) {
                return;
            }
            sleep(Duration.ofSeconds(2));
        }
        throw new IOException("server did not open RCON within " + config.startTimeoutSeconds() + "s");
    }

    /** Kills the server this class started, if any, with its child processes. */
    private void kill() {
        if (process == null) {
            return;
        }
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        try {
            process.waitFor(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        process = null;
    }

    private RconClient tryConnect(Duration commandTimeout) {
        try {
            return RconClient.connect("127.0.0.1", config.rconPort(), config.rconPassword(), commandTimeout);
        } catch (IOException e) {
            return null;
        }
    }

    /** Stops whatever server answers on the RCON port and waits for a server this class started. */
    @Override
    public synchronized void close() {
        if (rcon != null) {
            try {
                rcon.command("stop");
            } catch (IOException ignored) {
                // the server may close the connection before replying
            }
            try {
                rcon.close();
            } catch (IOException ignored) {
                // nothing left to release
            }
            rcon = null;
        }
        if (process != null) {
            try {
                if (!process.waitFor(60, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(10, TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
            }
            process = null;
        }
    }

    private static void sleep(Duration duration) throws IOException {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while waiting for the server", e);
        }
    }
}
