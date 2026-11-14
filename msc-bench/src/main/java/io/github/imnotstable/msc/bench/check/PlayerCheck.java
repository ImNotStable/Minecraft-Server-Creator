package io.github.imnotstable.msc.bench.check;

import io.github.imnotstable.msc.bench.JsonNumbers;
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
import java.util.ArrayList;
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
        List<Pattern> expect = new ArrayList<>();
        for (String regex : Checks.strings(json, "expect")) {
            expect.add(Pattern.compile(regex, Pattern.MULTILINE));
        }
        return new PlayerCheck(
                name,
                Checks.require(json, "jar"),
                json.exactStrings("command", List.of("{java}", "-Xmx1G", "-jar", "server.jar")),
                Checks.string(json, "port", "{serverPort}"),
                Checks.string(json, "player", "Steve"),
                json.exactStrings("script"),
                json.exactStrings("console"),
                Pattern.compile(Checks.string(json, "ready", "Done \\(")),
                expect,
                json.wholeNumber("timeoutSeconds", JsonNumbers.SECONDS, 180));
    }

    @Override
    public CheckResult run(CheckContext context) {
        Path workspace = context.workspace();
        Path builtJar = workspace.resolve(Placeholders.fill(jar, context.placeholders()));
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
            server = context.start(ServerJava.processBuilder(Placeholders.fill(command, context.placeholders()))
                    .directory(dir.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(log.toFile()));
            if (!waitFor(ready, log, server, deadline)) {
                return CheckResult.fail(name, (server.isAlive() ? "server not ready in time: " : "server stopped: ")
                        + tail(read(log)));
            }
            StringBuilder seen = new StringBuilder();
            if (!script.isEmpty()) {
                int p = Integer.parseInt(Placeholders.fill(port, context.placeholders()).strip());
                try (Bot bot = Bots.join(player, "127.0.0.1", p, FLAT, Duration.ofSeconds(30), protocols())) {
                    seen.append(bot.run(script, "world")).append('\n');
                }
            }
            OutputStream in = server.getOutputStream();
            int before = read(log).length();
            for (String line : console) {
                in.write((Placeholders.fill(line, context.placeholders()) + "\n").getBytes(StandardCharsets.UTF_8));
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
                    return CheckResult.fail(name, "no match for /" + p.pattern() + "/; player saw: "
                            + tail(seen.toString()) + "; console after start: " + tail(afterReady(read(log))));
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

    private static String tail(String text) {
        return Text.tailWithEllipsis(text.strip(), 303);
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
