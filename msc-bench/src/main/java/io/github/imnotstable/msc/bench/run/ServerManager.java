package io.github.imnotstable.msc.bench.run;

import io.github.imnotstable.msc.bench.rcon.RconClient;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Runs the dev server of one workspace. Connects to a server that is already up (for example one the
 * agent left running) before starting a new one, and always stops it on close. A server on the bench's ports from
 * before the attempt belongs to another workspace; {@link #clearPorts} stops it first.
 */
public final class ServerManager implements AutoCloseable {

    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(60);

    private final Path serverDir;
    private final BenchConfig.Server config;
    private final Map<String, String> env;
    private final ChildProcesses children;
    private Process process;
    private RconClient rcon;
    private boolean reachedRcon;
    private IOException startFailure;

    public ServerManager(Path serverDir, BenchConfig.Server config, Map<String, String> env) {
        this(serverDir, config, env, new ChildProcesses());
    }

    /** A manager whose server process is tracked in {@code children}, so a cut-short run can kill it. */
    public ServerManager(Path serverDir, BenchConfig.Server config, Map<String, String> env, ChildProcesses children) {
        this.serverDir = serverDir;
        this.config = config;
        this.env = Map.copyOf(env);
        this.children = children;
    }

    /** The console connection, starting the server first if nothing answers on the RCON port. */
    public synchronized RconClient rcon() throws IOException {
        if (process != null && !process.isAlive()) {
            // The agent stopped the server this class started; its connection died with it.
            disconnect();
            process = null;
        }
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

    /**
     * Closes the console connection but leaves the server running, so the next {@link #rcon()} connects afresh,
     * or starts the server, should the agent have stopped or restarted it meanwhile.
     */
    public synchronized void disconnect() {
        if (rcon != null) {
            try {
                rcon.close();
            } catch (IOException ignored) {
                // nothing left to release
            }
            rcon = null;
        }
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
        process = children.start(builder);
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
        ChildProcesses.killTree(process);
        process = null;
    }

    private RconClient tryConnect(Duration commandTimeout) {
        return tryConnect(config, commandTimeout);
    }

    private static RconClient tryConnect(BenchConfig.Server config, Duration commandTimeout) {
        try {
            return RconClient.connect("127.0.0.1", config.rconPort(), config.rconPassword(), commandTimeout);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Stops a server left on the bench's ports from before this attempt (a run cut short, or a server an agent
     * started outside its own process tree), so the setup commands and checks cannot reach another workspace's
     * world, and waits until the ports are free. Throws when they stay taken.
     */
    public static void clearPorts(BenchConfig.Server config) throws IOException {
        clearPorts(config, STOP_TIMEOUT);
    }

    static void clearPorts(BenchConfig.Server config, Duration wait) throws IOException {
        if (!accepts(config.rconPort()) && !accepts(config.serverPort())) {
            return;
        }
        sendStop(config);
        awaitPortsFree(config, wait);
        for (int port : new int[] {config.rconPort(), config.serverPort()}) {
            if (accepts(port)) {
                throw new IOException("port " + port + " is still taken by a process the bench did not start "
                        + "(a server left from an earlier run?); stop it and run again");
            }
        }
    }

    /** Sends {@code stop} to whatever server answers on the RCON port, without waiting for it to go. */
    public static void sendStop(BenchConfig.Server config) {
        RconClient client = tryConnect(config, Duration.ofSeconds(5));
        if (client == null) {
            return;
        }
        try (client) {
            client.command("stop");
        } catch (IOException ignored) {
            // the server may close the connection before replying
        }
    }

    /**
     * Stops whatever server answers on the RCON port and waits until it is gone: for a server this class started,
     * until its process ends; for one it found running, until its RCON and game ports are free.
     */
    @Override
    public synchronized void close() {
        boolean stopSent = rcon != null;
        if (rcon != null) {
            try {
                rcon.command("stop");
            } catch (IOException ignored) {
                // the server may close the connection before replying
            }
            disconnect();
        }
        if (process != null && !process.isAlive()) {
            // Stopped during the run; whatever answered the stop above is another server, waited for below.
            process = null;
        }
        if (process != null) {
            try {
                if (!process.waitFor(60, TimeUnit.SECONDS)) {
                    ChildProcesses.killTree(process);
                }
            } catch (InterruptedException e) {
                ChildProcesses.killTree(process);
                Thread.currentThread().interrupt();
            }
            process = null;
        } else if (stopSent) {
            awaitPortsFree(config, STOP_TIMEOUT);
        }
    }

    /** Waits (bounded) until nothing accepts connections on the RCON or game port any more. */
    private static void awaitPortsFree(BenchConfig.Server config, Duration wait) {
        long deadline = System.nanoTime() + wait.toNanos();
        while ((accepts(config.rconPort()) || accepts(config.serverPort())) && System.nanoTime() < deadline) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static boolean accepts(int port) {
        if (port <= 0) {
            return false;
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 1000);
            return true;
        } catch (IOException e) {
            return false;
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
