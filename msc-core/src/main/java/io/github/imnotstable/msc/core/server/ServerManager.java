package io.github.imnotstable.msc.core.server;

import io.github.imnotstable.msc.core.CoreConfig;
import io.github.imnotstable.msc.core.agent.AgentHub;
import io.github.imnotstable.msc.core.tool.ToolException;
import io.github.imnotstable.msc.protocol.Limits;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Owns the dev server process: start it with the agent's connection details, watch its log, stop it. */
public final class ServerManager implements AutoCloseable {
    private static final Pattern DONE = Pattern.compile(".*\\bDone \\(\\d.*\\)!.*");

    /** Outcome of a start. {@code ready} false means it exited or timed out; the log says why. */
    public record StartResult(boolean ready, boolean exited, long millis, long logMark) {
    }

    private final CoreConfig config;
    private final AgentHub hub;
    private final LogBuffer log = new LogBuffer(20_000);
    private Process process;
    private volatile boolean sawDone;

    public ServerManager(CoreConfig config, AgentHub hub) {
        this.config = config;
        this.hub = hub;
    }

    public LogBuffer log() {
        return log;
    }

    public Path serverDir() {
        return config.serverDir();
    }

    public synchronized boolean running() {
        return process != null && process.isAlive();
    }

    /** The jar {@code server_setup} put in place, or null. */
    public Path serverJar() {
        Path jar = config.serverDir().resolve("paper.jar");
        return Files.exists(jar) ? jar : null;
    }

    private List<String> command() {
        if (config.serverCommand() != null) {
            return config.serverCommand();
        }
        if (serverJar() == null) {
            throw new ToolException("no server in " + config.serverDir() + ". Run server_setup first.");
        }
        // The EULA is the user's to accept: eula.txt in the server directory decides, never a flag set here.
        Path eula = config.serverDir().resolve("eula.txt");
        if (!eulaAccepted(eula)) {
            throw new ToolException("the Minecraft EULA is not accepted (no eula=true in " + eula + "): once the user "
                    + "agrees to https://aka.ms/MinecraftEULA, run server_setup with acceptEula=true");
        }
        return List.of(config.javaCommand(), "-Xmx2G", "-jar", "paper.jar", "--nogui");
    }

    /** True when {@code eulaFile} exists and has an {@code eula=true} line. */
    public static boolean eulaAccepted(Path eulaFile) {
        try {
            return Files.readAllLines(eulaFile).stream().anyMatch(l -> l.strip().equals("eula=true"));
        } catch (IOException e) {
            return false;
        }
    }

    /** Start the server and wait until it is ready, it exits, or the timeout passes. */
    public StartResult start(Duration timeout) throws IOException, InterruptedException {
        long begin = System.nanoTime();
        long mark;
        long readyBefore;
        Process started;
        synchronized (this) {
            if (running()) {
                throw new ToolException("server is already running. Use server_stop or dev_cycle.");
            }
            List<String> command = new ArrayList<>(command());
            Files.createDirectories(config.serverDir());
            ProcessBuilder pb = new ProcessBuilder(command).directory(config.serverDir().toFile())
                    .redirectErrorStream(true);
            pb.environment().put(Limits.ENV_CORE_ADDRESS, hub.address());
            pb.environment().put(Limits.ENV_TOKEN, hub.token());
            mark = log.mark();
            readyBefore = hub.readyCount();
            sawDone = false;
            started = pb.start();
            process = started;
            Thread.ofVirtual().name("msc-server-log").start(() -> pump(started));
        }
        long deadline = begin + timeout.toNanos();
        boolean ready = false;
        while (System.nanoTime() < deadline && started.isAlive()) {
            if (hub.awaitReady(readyBefore, Duration.ofMillis(200)) || sawDone) {
                ready = true;
                break;
            }
        }
        if (!ready && !started.isAlive()) {
            // Give the log pump a moment to drain the last lines.
            Thread.sleep(100);
        }
        return new StartResult(ready, !started.isAlive(), (System.nanoTime() - begin) / 1_000_000, mark);
    }

    private void pump(Process p) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.add(line);
                if (DONE.matcher(line).matches()) {
                    sawDone = true;
                }
            }
        } catch (IOException ignored) {
            // Stream closed with the process.
        }
    }

    /** Type a line into the server console. */
    public synchronized void console(String line) throws IOException {
        if (!running()) {
            throw new ToolException("server is not running");
        }
        OutputStream in = process.getOutputStream();
        in.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        in.flush();
    }

    /** Ask the server to stop, and kill it if it does not within the timeout. Returns false if it was not running. */
    public boolean stop(Duration timeout) throws InterruptedException {
        Process p;
        synchronized (this) {
            if (!running()) {
                return false;
            }
            p = process;
            try {
                console("stop");
            } catch (IOException e) {
                p.destroy();
            }
        }
        if (!p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            p.descendants().forEach(ProcessHandle::destroyForcibly);
            p.destroyForcibly();
            p.waitFor(10, TimeUnit.SECONDS);
        }
        return true;
    }

    @Override
    public void close() {
        try {
            stop(Duration.ofSeconds(20));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
