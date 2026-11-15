package io.github.imnotstable.msc.bench.check;

import io.github.imnotstable.msc.bench.JsonNumbers;
import io.github.imnotstable.msc.bench.Quoted;
import io.github.imnotstable.msc.bench.Text;
import io.github.imnotstable.msc.bench.UserJson;
import io.github.imnotstable.msc.bench.run.ChildProcesses;
import io.github.imnotstable.msc.bot.BlockSource;
import io.github.imnotstable.msc.bot.Bot;
import io.github.imnotstable.msc.bot.Bots;
import io.github.imnotstable.msc.process.ServerJava;
import io.github.imnotstable.msc.protocol.world.BlockGrid;
import io.github.imnotstable.msc.protocol.world.Region;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Starts the server jar the workspace built (a Minestom game is its own server), lets a headless test player join
 * and play a script, runs console commands, stops the server, and passes when every {@code expect} pattern is found
 * in what the player saw plus the server's console output. The server runs in a fresh folder inside the workspace,
 * so nothing the agent left behind (saved progress, a world) changes the result.
 *
 * <p>The test player gets a flat world for its own physics (ground at y -61, as the minestom templates generate):
 * the script should walk on open ground. It speaks the server's Minecraft version when that is the current or the
 * previous one ({@code -Dmsc.bot.protocols}, set by the bench's Gradle run).
 */
record PlayerCheck(
        String name,
        String jar,
        List<String> command,
        String port,
        String player,
        List<String> script,
        List<String> console,
        Pattern ready,
        List<Pattern> expect,
        int timeoutSeconds) implements Check {

    /** What a server prints once it is ready, when the check names no {@code ready} regex. */
    private static final Pattern DEFAULT_READY = Pattern.compile("Done \\(");

    /** Folder in the workspace the check's server runs in. */
    static final String SERVER_DIR = ".bench-player-check";

    PlayerCheck {
        command = List.copyOf(command);
        script = List.copyOf(script);
        console = List.copyOf(console);
        expect = List.copyOf(expect);
        if (expect.isEmpty()) {
            throw new IllegalArgumentException("player check needs at least one \"expect\" pattern");
        }
    }

    static PlayerCheck parse(String name, UserJson json) {
        Checks.strings(json, "expect");
        List<Pattern> expect = json.patterns("expect", Pattern.MULTILINE);
        Pattern ready = json.pattern("ready", 0);
        return new PlayerCheck(
                name,
                Checks.requirePath(json, "jar"),
                json.exactStrings("command", List.of("{java}", "-Xmx1G", "-jar", "server.jar")),
                port(json),
                Checks.string(json, "player", "Steve"),
                json.exactStrings("script"),
                json.exactStrings("console"),
                ready == null ? DEFAULT_READY : ready,
                expect,
                json.wholeNumber("timeoutSeconds", JsonNumbers.SECONDS, 180));
    }

    /**
     * The {@code port} the player joins: a port number, or text with a placeholder such as {@code {serverPort}} that
     * the run fills in. A port number that is out of range or not a number is refused here, not when the check runs.
     */
    private static String port(UserJson json) {
        String port = Checks.string(json, "port", "{serverPort}");
        if (port.contains("{")) {
            return port;
        }
        if (!port.strip().matches("\\d{1,5}") || Integer.parseInt(port.strip()) < 1
                || Integer.parseInt(port.strip()) > 65535) {
            throw new IllegalArgumentException(json.key("port") + ": " + Quoted.text(port)
                    + " is not a port from 1 to 65535 or a placeholder such as {serverPort}");
        }
        return port;
    }

    @Override
    public boolean startsServer() {
        return true;
    }

    @Override
    public CheckResult run(CheckContext context) {
        Path workspace = context.workspace();
        Path builtJar = workspace.resolve(jar);
        if (!Files.isRegularFile(builtJar)) {
            return CheckResult.fail(name, "no server jar at " + jar + " (did the build pass?)");
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        Process server = null;
        Path dir = workspace.resolve(SERVER_DIR);
        Path log = dir.resolve("console.log");
        try {
            deleteTree(dir);
            Files.createDirectories(dir);
            Files.copy(builtJar, dir.resolve("server.jar"), StandardCopyOption.REPLACE_EXISTING);
            // The console is written and read as UTF-8 below; so must the server's Java speak (#478).
            server = context.start(ServerJava.processBuilder(command)
                    .directory(dir.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(log.toFile()));
            if (!waitFor(ready, log, server, deadline)) {
                String prefix = server.isAlive() ? "server not ready in time: " : "server stopped: ";
                return CheckResult.fail(name, prefix + tail(read(log), CheckResult.MAX_DETAIL - prefix.length()));
            }
            StringBuilder seen = new StringBuilder();
            if (!script.isEmpty()) {
                int p = Integer.parseInt(port.strip());
                try (Bot bot = Bots.join(player, "127.0.0.1", p, FLAT, Duration.ofSeconds(30), protocols())) {
                    seen.append(bot.run(script, "world")).append('\n');
                }
            }
            OutputStream in = server.getOutputStream();
            int before = read(log).length();
            for (String line : console) {
                in.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                in.flush();
            }
            if (!console.isEmpty()) {
                // Replies print to the console; give the server a moment to answer every command.
                waitForGrowth(log, before, deadline);
            }
            stop(server, in);
            String text = seen + read(log);
            for (Pattern p : expect) {
                if (!p.matcher(text).find()) {
                    return CheckResult.fail(name, noMatch(p, seen.toString(), afterReady(read(log))));
                }
            }
            return CheckResult.pass(name, expect.size() + " patterns found");
        } catch (IOException | RuntimeException e) {
            return CheckResult.fail(name, e.getMessage() == null ? e.toString() : e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return CheckResult.fail(name, "interrupted");
        } finally {
            if (server != null && server.isAlive()) {
                ChildProcesses.killTree(server);
            }
        }
    }

    /** Solid up to y -61, air above: the flat world of the minestom templates. */
    static final BlockSource FLAT = (Region r) -> {
        int[] cells = new int[(int) r.volume()];
        for (int y = r.min().y(); y <= r.max().y(); y++) {
            for (int z = r.min().z(); z <= r.max().z(); z++) {
                for (int x = r.min().x(); x <= r.max().x(); x++) {
                    cells[r.index(x, y, z)] = y <= -61 ? 1 : 0;
                }
            }
        }
        return new BlockGrid(r, List.of("minecraft:air", "minecraft:grass_block"), cells);
    };

    private static Path protocols() {
        String dir = System.getProperty("msc.bot.protocols");
        return dir == null ? null : Path.of(dir);
    }

    private static boolean waitFor(Pattern pattern, Path log, Process server, long deadline)
            throws IOException, InterruptedException {
        while (System.nanoTime() < deadline) {
            if (pattern.matcher(read(log)).find()) {
                return true;
            }
            if (!server.isAlive()) {
                return pattern.matcher(read(log)).find();
            }
            Thread.sleep(200);
        }
        return false;
    }

    /** Waits until the log stops growing for a second after first growing past {@code from}, or 10 seconds. */
    private static void waitForGrowth(Path log, int from, long deadline) throws IOException, InterruptedException {
        long until = Math.min(deadline, System.nanoTime() + TimeUnit.SECONDS.toNanos(10));
        int last = from;
        long quietSince = System.nanoTime();
        while (System.nanoTime() < until) {
            Thread.sleep(200);
            int now = read(log).length();
            if (now != last) {
                last = now;
                quietSince = System.nanoTime();
            } else if (last > from && System.nanoTime() - quietSince > TimeUnit.SECONDS.toNanos(1)) {
                return;
            }
        }
    }

    private static void stop(Process server, OutputStream in) throws InterruptedException {
        try {
            in.write("stop\n".getBytes(StandardCharsets.UTF_8));
            in.flush();
            in.close();
        } catch (IOException e) {
            // Already gone: nothing to stop.
        }
        if (!server.waitFor(20, TimeUnit.SECONDS)) {
            ChildProcesses.killTree(server);
        }
    }

    /** The console output after the ready line: what the script and commands caused, without startup noise. */
    private String afterReady(String console) {
        Matcher m = ready.matcher(console);
        return m.find() ? console.substring(m.end()) : console;
    }

    private static String read(Path log) throws IOException {
        return Files.isRegularFile(log) ? Text.read(log) : "";
    }

    /**
     * The detail for a pattern nothing matched: the ends of what the player saw and of the console, sized so the whole
     * detail fits {@link CheckResult#MAX_DETAIL} and is stored without a second cut (#535). The player gets at most
     * half the room and the console the rest, so a short or empty player part leaves the console more.
     */
    static String noMatch(Pattern p, String seen, String console) {
        String start = "no match for /" + p.pattern() + "/; player saw: ";
        String middle = "; console after start: ";
        int room = CheckResult.MAX_DETAIL - start.length() - middle.length();
        String seenTail = tail(seen, Math.max(0, room / 2));
        return start + seenTail + middle + tail(console, Math.max(0, room - seenTail.length()));
    }

    /** The end of {@code text}, where a server says what went wrong, in at most {@code max} chars. */
    private static String tail(String text, int max) {
        return Text.tailWithEllipsis(text.strip(), max);
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }
}
