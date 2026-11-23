package io.github.imnotstable.msc.core.server;

import io.github.imnotstable.msc.core.tool.ToolException;
import io.github.imnotstable.msc.process.AtomicFile;
import io.github.imnotstable.msc.process.ServerJava;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The Paper backends of a proxy workspace (ADR 0088): {@code run/servers/<name>/}, listed with their ports in
 * {@code run/servers/network.txt}. They start once the proxy is up, keep running across its restarts (a
 * {@code dev_cycle} of a proxy plugin), and stop with {@code server_stop} or the session, unless detached. Each writes
 * its console to {@code console.log} in its folder and its process to {@code .msc-server.pid}, so one an earlier
 * core left running is found and stopped before a new start.
 */
public final class Network implements AutoCloseable {
    /** The folder the backends live in, inside the proxy's server folder. */
    public static final String DIR = "servers";
    /** Which backends make the network, one {@code name port} per line, the first where players land. */
    public static final String FILE = DIR + "/network.txt";
    static final String CONSOLE = "console.log";
    static final String PID = ".msc-server.pid";
    /** The first backend's port: one above a dev server's, so a backend workspace on 25565 does not clash. */
    public static final int FIRST_PORT = 25566;
    private static final Pattern NAME = Pattern.compile("[a-z0-9_-]{1,16}");
    /** How many backends a dev network may have: each is a whole server in memory. */
    public static final int MAX = 6;

    /** One backend: its name in the proxy config, its folder and the port it listens on. */
    public record Member(String name, Path dir, int port) {
        public String address() {
            return "127.0.0.1:" + port;
        }
    }

    private final Map<String, Process> running = new LinkedHashMap<>();
    private final Map<String, Writer> consoles = new LinkedHashMap<>();

    /** The backend names {@code servers=} gives, checked; refused before anything changes. */
    public static List<String> names(List<String> given) {
        List<String> names = new ArrayList<>();
        for (String raw : given) {
            String name = raw.strip().toLowerCase(java.util.Locale.ROOT);
            if (!NAME.matcher(name).matches()) {
                throw new ToolException("a server name is 1 to 16 of a-z, 0-9, _ and -, not \"" + raw.strip() + "\"");
            }
            if (names.contains(name)) {
                throw new ToolException("server " + name + " is named twice");
            }
            names.add(name);
        }
        if (names.isEmpty() || names.size() > MAX) {
            throw new ToolException("servers takes 1 to " + MAX + " names, such as lobby, game");
        }
        return names;
    }

    /** The network {@code names} makes in {@code proxyDir}: folders and ports, in order. */
    public static List<Member> plan(Path proxyDir, List<String> names) {
        List<Member> out = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            out.add(new Member(names.get(i), proxyDir.resolve(DIR).resolve(names.get(i)), FIRST_PORT + i));
        }
        return out;
    }

    public static void write(Path proxyDir, List<Member> members) throws IOException {
        Path file = proxyDir.resolve(FILE);
        Files.createDirectories(file.getParent());
        StringBuilder text = new StringBuilder();
        members.forEach(m -> text.append(m.name()).append(' ').append(m.port()).append('\n'));
        AtomicFile.write(file, to -> Files.writeString(to, text, StandardCharsets.UTF_8));
    }

    /** The network set up in {@code proxyDir}, or none. */
    public static List<Member> members(Path proxyDir) {
        Path file = proxyDir.resolve(FILE);
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        List<Member> out = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String[] w = line.strip().split("\\s+");
                if (w.length == 2 && NAME.matcher(w[0]).matches() && w[1].matches("\\d{1,5}")) {
                    out.add(new Member(w[0], proxyDir.resolve(DIR).resolve(w[0]), Integer.parseInt(w[1])));
                }
            }
        } catch (IOException e) {
            return List.of();
        }
        return out;
    }

    /**
     * Starts every backend of {@code proxyDir}'s network that is not running yet and waits until each is ready, it
     * exits, or {@code timeout} passes. Returns one line for the start report, or "" with no network.
     */
    public synchronized String start(Path proxyDir, String java, Duration timeout)
            throws IOException, InterruptedException {
        List<Member> members = members(proxyDir);
        if (members.isEmpty()) {
            return "";
        }
        long begin = System.nanoTime();
        List<Member> started = new ArrayList<>();
        Map<String, String> parts = new LinkedHashMap<>();
        for (Member m : members) {
            Process p = running.get(m.name());
            if (p != null && p.isAlive()) {
                parts.put(m.name(), m.name() + " :" + m.port() + " running");
                continue;
            }
            if (!Files.exists(m.dir().resolve(Platform.PAPER.serverJar()))) {
                parts.put(m.name(), m.name() + " has no " + Platform.PAPER.serverJar()
                        + " (server_setup servers= again)");
                continue;
            }
            if (!ServerManager.eulaAccepted(m.dir().resolve("eula.txt"))) {
                parts.put(m.name(), m.name() + " not started: the Minecraft EULA is not accepted (server_start "
                        + "acceptEula=true)");
                continue;
            }
            stopLeftover(m);
            Files.deleteIfExists(m.dir().resolve(CONSOLE));
            ProcessBuilder pb = ServerJava.processBuilder(Platform.PAPER.launchCommand(java))
                    .directory(m.dir().toFile()).redirectErrorStream(true)
                    .redirectOutput(m.dir().resolve(CONSOLE).toFile());
            Process proc = pb.start();
            PidFile.write(m.dir().resolve(PID), proc.toHandle());
            running.put(m.name(), proc);
            consoles.put(m.name(), new OutputStreamWriter(proc.getOutputStream(), StandardCharsets.UTF_8));
            started.add(m);
            parts.put(m.name(), null);
        }
        long deadline = begin + timeout.toNanos();
        for (Member m : started) {
            Process proc = running.get(m.name());
            String state = "not ready after " + timeout.toSeconds() + "s";
            while (System.nanoTime() < deadline) {
                if (ready(m)) {
                    state = String.format("ready in %.1fs", (System.nanoTime() - begin) / 1e9);
                    break;
                }
                if (!proc.isAlive()) {
                    state = "EXITED (" + proc.exitValue() + "), see " + proxyDir.relativize(m.dir().resolve(CONSOLE));
                    break;
                }
                Thread.sleep(200);
            }
            parts.put(m.name(), m.name() + " :" + m.port() + " " + state);
        }
        return "network: " + String.join(", ", parts.values()) + "; consoles in " + DIR + "/<name>/" + CONSOLE
                + ", commands with @<name> in server_command";
    }

    private static boolean ready(Member m) {
        try {
            return Files.readAllLines(m.dir().resolve(CONSOLE), StandardCharsets.UTF_8).stream()
                    .anyMatch(l -> LogReader.READY.matcher(l).matches());
        } catch (IOException | java.io.UncheckedIOException e) {
            return false; // not written yet, or a line cut in the middle of a character
        }
    }

    /** A backend an earlier core left running holds its port and world: stopped before a new start. */
    private static void stopLeftover(Member m) throws InterruptedException {
        ProcessHandle left = PidFile.peek(m.dir().resolve(PID)).orElse(null);
        if (left != null) {
            ProcessRunner.destroyTree(left);
            left.onExit().completeOnTimeout(left, 20, java.util.concurrent.TimeUnit.SECONDS).join();
        }
    }

    /** Types {@code line} on backend {@code name}'s console; refused for a backend that is not running. */
    public synchronized void command(String name, String line) throws IOException {
        Process p = running.get(name);
        Writer w = consoles.get(name);
        if (p == null || !p.isAlive() || w == null) {
            throw new ToolException("backend " + name + " is not running"
                    + (running.isEmpty() ? "; server_start starts the network" : ": running are "
                    + String.join(", ", running.keySet())));
        }
        w.write(line + "\n");
        w.flush();
    }

    /** The console file of backend {@code name}. */
    public static Path console(Path proxyDir, String name) {
        return proxyDir.resolve(DIR).resolve(name).resolve(CONSOLE);
    }

    public synchronized boolean running() {
        return running.values().stream().anyMatch(Process::isAlive);
    }

    /** Stops every backend: {@code stop} on its console, then its process tree after {@code timeout}. */
    public synchronized String stop(Duration timeout) throws InterruptedException {
        List<String> stopped = new ArrayList<>();
        for (Map.Entry<String, Process> e : running.entrySet()) {
            Process p = e.getValue();
            if (!p.isAlive()) {
                continue;
            }
            try {
                Writer w = consoles.get(e.getKey());
                w.write("stop\n");
                w.flush();
            } catch (IOException ignored) {
                // Its console is gone: it is killed below.
            }
            stopped.add(e.getKey());
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        for (String name : stopped) {
            Process p = running.get(name);
            long left = Math.max(0, deadline - System.nanoTime());
            if (!p.waitFor(left, java.util.concurrent.TimeUnit.NANOSECONDS)) {
                ProcessRunner.destroyTree(p.toHandle());
            }
        }
        running.clear();
        consoles.clear();
        return stopped.isEmpty() ? "" : "stopped backends " + String.join(", ", stopped);
    }

    @Override
    public void close() {
        try {
            stop(Duration.ofSeconds(30));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            running.values().forEach(p -> ProcessRunner.destroyTree(p.toHandle()));
        }
    }
}
