package io.github.imnotstable.msc.core.server;

import io.github.imnotstable.msc.core.tool.ToolException;
import io.github.imnotstable.msc.process.ServerPropertiesFile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordingFile;

/**
 * A sampling profile of the running server (ADR 0090), from the JDK's own Java Flight Recorder: {@code jcmd} starts a
 * recording in the server's JVM, dumps it after the asked time, and the samples, garbage collections and allocations
 * are read here with {@code jdk.jfr.consumer}. Every stack is put on one owner (the workspace's project, a plugin or
 * mod jar, or an area of the server such as chunk generation or entities) and the hottest methods are named, with
 * config changes for the areas that take the most time.
 */
public final class Profiler {
    /** The thread vanilla, Paper and the mod loaders tick the world on. */
    static final String MAIN_THREAD = "Server thread";
    private static final int TOP_METHODS = 6;
    private static final int TOP_OWNERS = 6;
    /** The execution sample period of the JDK's "profile" settings. */
    static final int SAMPLE_MILLIS = 10;

    /**
     * Server areas, by a word in any frame's class or method, checked from the top of the stack down: the first area
     * a frame names is where the sample's time went. Order matters where one area calls another.
     */
    static final List<Area> AREAS = List.of(
            new Area("chunk generation", List.of("levelgen", "ChunkGenerator", "NoiseChunk", "Feature.place",
                    "chunk.status", "ChunkStatus", "WorldGen", "Structure"),
                    "pregenerate the play area once (a world border and a fill of its chunks), or lower view-distance "
                            + "in " + ServerPropertiesFile.NAME),
            new Area("lighting", List.of("lighting", "starlight", "LightEngine"),
                    "light updates follow block changes: batch large edits, or lower view-distance"),
            new Area("chunk loading and saving", List.of("chunk.storage", "RegionFile", "ChunkMap", "ChunkHolder",
                    "IOWorker", "ChunkSerializer", "moonrise.patches.chunk_system"),
                    "players moving fast load many chunks: lower view-distance, or keep the play area loaded "
                            + "(forceload) and pregenerated"),
            new Area("redstone and hoppers", List.of("redstone", "RedStoneWire", "Hopper", "Piston", "Observer"),
                    "a redstone clock or hopper chain ticks every tick: slow it, or raise hopper transfer/check "
                            + "in spigot.yml (ticks-per)"),
            new Area("block entities", List.of("BlockEntity", "TickingBlockEntity"),
                    "many ticking block entities (furnaces, spawners, chests with hoppers): fewer, or tick them less"),
            new Area("entities", List.of("world.entity", "EntityTickList", "PathNavigation", "PathFinder", "Brain",
                    "GoalSelector", "tickNonPassenger", "Entity.tick", "entity.ai"),
                    "many entities or mobs pathing: cap them, lower entity-activation-range (spigot.yml) and "
                            + "simulation-distance (" + ServerPropertiesFile.NAME + "), or despawn what nobody sees"),
            new Area("networking", List.of("network", "netty", "Connection", "PacketEncoder", "Compression"),
                    "much sent to players: fewer entity or block updates per tick, or a higher "
                            + "network-compression-threshold"),
            new Area("commands and scheduler", List.of("CraftScheduler", "Commands", "brigadier", "FunctionManager"),
                    "a scheduled task or a function runs often: run it less or spread its work over ticks"));

    /** A part of the server that takes time, the words that place a frame in it, and what lowers it. */
    record Area(String name, List<String> words, String tune) {
        boolean has(String frame) {
            return words.stream().anyMatch(frame::contains);
        }
    }

    /** One sampled stack: its thread and its frames, top first, as {@code class.method:line}. */
    record Sample(String thread, List<String> frames) {
    }

    /** What a recording holds, already read. */
    public record Recording(List<Sample> samples, int gcs, long gcPauseNanos, long gcMaxNanos, long heapUsedAfterGc,
                     long heapMax, Map<String, Long> allocations, Duration length) {
    }

    private Profiler() {
    }

    /**
     * Records {@code seconds} of the JVM {@code server} with {@code jcmd} (next to the server's own {@code java}), into
     * {@code file}.
     */
    public static Recording record(ProcessHandle server, int seconds, Path file) throws IOException,
            InterruptedException {
        String jcmd = jcmd(server);
        String pid = Long.toString(server.pid());
        Files.deleteIfExists(file);
        Files.createDirectories(file.getParent());
        run(jcmd, pid, "JFR.start", "name=msc-profile", "settings=profile");
        try {
            Thread.sleep(TimeUnit.SECONDS.toMillis(seconds));
            run(jcmd, pid, "JFR.dump", "name=msc-profile", "filename=" + file.toAbsolutePath());
        } finally {
            run(jcmd, pid, "JFR.stop", "name=msc-profile");
        }
        if (!Files.isRegularFile(file)) {
            throw new ToolException("the server's JVM wrote no recording to " + file);
        }
        return read(file);
    }

    /** {@code jcmd} of the JDK the server runs on, else the core's own. */
    static String jcmd(ProcessHandle server) {
        String exe = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "jcmd.exe" : "jcmd";
        for (String java : List.of(server.info().command().orElse(""),
                ProcessHandle.current().info().command().orElse(""))) {
            if (!java.isEmpty()) {
                Path jcmd = Path.of(java).resolveSibling(exe);
                if (Files.isExecutable(jcmd)) {
                    return jcmd.toString();
                }
            }
        }
        throw new ToolException("no jcmd next to the server's java: profiling needs a JDK, not a JRE (MSC_JAVA)");
    }

    private static void run(String... command) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!p.waitFor(60, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new ToolException(command[2] + " did not answer within 60 s");
        }
        if (p.exitValue() != 0 || out.contains("Could not") || out.contains("Exception")) {
            throw new ToolException(command[2] + " failed: " + out.strip().lines().reduce((a, b) -> b).orElse("")
                    + " (the server must run as this user, with attach enabled)");
        }
    }

    /** Reads a recording file. */
    public static Recording read(Path file) throws IOException {
        List<Sample> samples = new ArrayList<>();
        Map<String, Long> allocations = new HashMap<>();
        int gcs = 0;
        long pause = 0;
        long max = 0;
        long heapAfter = 0;
        long heapMax = 0;
        java.time.Instant first = null;
        java.time.Instant last = null;
        for (RecordedEvent e : RecordingFile.readAllEvents(file)) {
            first = first == null || e.getStartTime().isBefore(first) ? e.getStartTime() : first;
            last = last == null || e.getEndTime().isAfter(last) ? e.getEndTime() : last;
            switch (e.getEventType().getName()) {
                case "jdk.ExecutionSample" -> {
                    RecordedStackTrace stack = e.getStackTrace();
                    if (stack != null) {
                        String thread = e.getThread("sampledThread") == null ? ""
                                : String.valueOf(e.getThread("sampledThread").getJavaName());
                        samples.add(new Sample(thread, stack.getFrames().stream().map(Profiler::frame).toList()));
                    }
                }
                case "jdk.GarbageCollection" -> {
                    gcs++;
                    long d = e.getLong("sumOfPauses");
                    pause += d;
                    max = Math.max(max, e.getLong("longestPause"));
                }
                case "jdk.GCHeapSummary" -> {
                    if ("After GC".equals(e.getString("when"))) {
                        heapAfter = e.getLong("heapUsed");
                    }
                }
                case "jdk.GCHeapConfiguration" -> heapMax = e.getLong("maxSize");
                case "jdk.ObjectAllocationSample" -> {
                    RecordedStackTrace stack = e.getStackTrace();
                    String site = stack == null ? "" : stack.getFrames().stream().map(Profiler::frame)
                            .filter(f -> !f.startsWith("java.") && !f.startsWith("jdk.")).findFirst().orElse("");
                    String type = e.getClass("objectClass") == null ? "?" : e.getClass("objectClass").getName();
                    allocations.merge(type + (site.isEmpty() ? "" : " at " + site.replaceFirst(":\\d+$", "")),
                            e.getLong("weight"), Long::sum);
                }
                default -> {
                }
            }
        }
        Duration length = first == null ? Duration.ZERO : Duration.between(first, last);
        return new Recording(samples, gcs, pause, max, heapAfter, heapMax, allocations, length);
    }

    private static String frame(RecordedFrame f) {
        return f.getMethod().getType().getName() + "." + f.getMethod().getName() + ":" + f.getLineNumber();
    }

    /**
     * The report: where the time went by owner, the hottest methods, garbage collection, allocation, and config
     * changes for the server areas that took the most. {@code serverDir} places frames in the project or a jar.
     */
    public static String report(Recording r, Path serverDir, Platform platform, String tps) {
        CrashSources sources = new CrashSources(serverDir, platform);
        long onMain = r.samples().stream().filter(s -> s.thread().equals(MAIN_THREAD)).count();
        // The world's tick thread is what makes lag; elsewhere (Minestom, a proxy) every thread counts.
        boolean main = onMain * 4 >= r.samples().size() && onMain > 0;
        List<Sample> samples = main ? r.samples().stream().filter(s -> s.thread().equals(MAIN_THREAD)).toList()
                : r.samples();
        StringBuilder out = new StringBuilder();
        out.append("profile ").append(r.length().toSeconds()).append("s: ").append(samples.size())
                .append(main ? " samples of the main server thread" : " samples of all threads")
                .append(tps == null ? "" : "; " + tps).append('\n');
        if (samples.isEmpty()) {
            return out.append("no samples: the server was idle (a profile of an idle server shows nothing; play or "
                    + "load it while profiling)").toString();
        }
        Map<String, Integer> owners = new LinkedHashMap<>();
        Map<String, Integer> areas = new HashMap<>();
        Map<String, Integer> self = new HashMap<>();
        Map<String, String> ownerOf = new HashMap<>();
        for (Sample s : samples) {
            String owner = owner(s, sources, ownerOf);
            owners.merge(owner, 1, Integer::sum);
            if (owner.startsWith("server: ") && !owner.equals("server: other")) {
                areas.merge(owner.substring("server: ".length()), 1, Integer::sum);
            }
            // The JDK's own frames are where any code spends time: the time is its caller's.
            s.frames().stream().filter(f -> !jdk(f)).findFirst().ifPresent(f -> self.merge(f, 1, Integer::sum));
        }
        int n = samples.size();
        if (main && r.length().toMillis() > 0) {
            // Each sample stands for one period of the profile settings (10 ms) the thread was running.
            double busy = Math.min(1, onMain * SAMPLE_MILLIS / (double) r.length().toMillis());
            out.append(String.format(Locale.ROOT, "main thread busy about %.0f%% of the time (about %.0f ms of each "
                    + "50 ms tick)%n", busy * 100, busy * 50));
        }
        out.append("where the time goes: ").append(String.join(", ", top(owners, TOP_OWNERS).stream()
                .filter(e -> e.getValue() * 100 >= n).map(e -> e.getKey() + " " + pct(e.getValue(), n)).toList()))
                .append('\n');
        out.append("hottest methods (own time):\n");
        for (Map.Entry<String, Integer> e : top(self, TOP_METHODS)) {
            String m = e.getKey();
            String link = sources.link("at " + m.replaceFirst(":(\\d+)$", "(" + fileOf(m) + ":$1)"));
            out.append("  ").append(pct(e.getValue(), n)).append(' ')
                    .append(link != null ? link.substring(3) : m).append('\n');
        }
        double wall = Math.max(1, r.length().toNanos());
        out.append("GC: ").append(r.gcs()).append(" collections, ").append(r.gcPauseNanos() / 1_000_000)
                .append(" ms paused (").append(String.format(Locale.ROOT, "%.1f%%", 100 * r.gcPauseNanos() / wall))
                .append("), longest ").append(r.gcMaxNanos() / 1_000_000).append(" ms");
        if (r.heapMax() > 0) {
            out.append("; heap after GC ").append(r.heapUsedAfterGc() >> 20).append(" of ").append(r.heapMax() >> 20)
                    .append(" MB");
        }
        out.append('\n');
        long allocated = r.allocations().values().stream().mapToLong(Long::longValue).sum();
        if (allocated > 0) {
            out.append("allocation: ").append(String.join(", ", topLong(r.allocations(), 3).stream()
                    .map(e -> pct(e.getValue(), allocated) + " " + e.getKey()).toList())).append('\n');
        }
        List<String> tune = tune(areas, n, r, serverDir, owners);
        if (!tune.isEmpty()) {
            out.append("tune:\n");
            tune.forEach(t -> out.append("  - ").append(t).append('\n'));
        }
        return out.toString().stripTrailing();
    }

    /** The owner of a sample: the project or a jar for the topmost frame outside the framework, else a server area. */
    private static String owner(Sample s, CrashSources sources, Map<String, String> cache) {
        for (String f : s.frames()) {
            String cls = f.substring(0, f.lastIndexOf('.', f.lastIndexOf(':')));
            if (LogCondenser.framework(cls) || cls.startsWith("io.github.imnotstable.msc.agent.")) {
                continue;
            }
            return cache.computeIfAbsent(cls, c -> {
                String link = sources.link("at " + f.replaceFirst(":(\\d+)$", "(" + fileOf(f) + ":$1)"));
                if (link != null) {
                    return "your code";
                }
                String jar = sources.owner(c);
                return jar != null ? jar.replaceFirst(" \\(.*$", "") : "other: " + c.replaceFirst("\\.[^.]+$", "");
            });
        }
        for (String f : s.frames()) {
            for (Area a : AREAS) {
                if (a.has(f)) {
                    return "server: " + a.name();
                }
            }
        }
        return "server: other";
    }

    private static boolean jdk(String frame) {
        return frame.startsWith("java.") || frame.startsWith("jdk.") || frame.startsWith("sun.")
                || frame.startsWith("com.sun.");
    }

    /** {@code Foo.java} for {@code a.b.Foo$Inner.m:3}: the source file a frame's class most likely has. */
    private static String fileOf(String frame) {
        String cls = frame.substring(0, frame.lastIndexOf('.', frame.lastIndexOf(':')));
        String simple = cls.substring(cls.lastIndexOf('.') + 1);
        int inner = simple.indexOf('$');
        return (inner < 0 ? simple : simple.substring(0, inner)) + ".java";
    }

    /** Config changes for what took the most: server areas over 15%, garbage collection over 5% of the time. */
    static List<String> tune(Map<String, Integer> areas, int n, Recording r, Path serverDir,
                             Map<String, Integer> owners) {
        List<String> out = new ArrayList<>();
        Map<String, String> props = Map.of();
        try {
            props = ServerPropertiesFile.read(serverDir.resolve(ServerPropertiesFile.NAME));
        } catch (IOException e) {
            // No server.properties (Minestom, a proxy): the advice names keys without their values.
        }
        for (Map.Entry<String, Integer> e : top(areas, AREAS.size())) {
            if (e.getValue() * 100 < n * 15) {
                break;
            }
            Area a = AREAS.stream().filter(x -> x.name().equals(e.getKey())).findFirst().orElseThrow();
            String values = props.isEmpty() ? "" : " (now view-distance=" + props.getOrDefault("view-distance", "10")
                    + ", simulation-distance=" + props.getOrDefault("simulation-distance", "10") + ")";
            out.add(a.name() + " is " + pct(e.getValue(), n) + ": " + a.tune()
                    + (a.tune().contains("distance") ? values : ""));
        }
        Integer own = owners.get("your code");
        if (own != null && own * 100 >= n * 15) {
            out.add("your code is " + pct(own, n) + ": start with the hottest of its methods above");
        }
        if (r.length().toNanos() > 0 && r.gcPauseNanos() * 20 > r.length().toNanos()) {
            out.add("garbage collection pauses take over 5% of the time: allocate less in the hot paths (see "
                    + "allocation), or give the server more heap if heap after GC is near its maximum");
        } else if (r.heapMax() > 0 && r.heapUsedAfterGc() * 10 > r.heapMax() * 8) {
            out.add("heap after GC is over 80% of the maximum: something keeps growing (caches, loaded chunks, "
                    + "entities), or the server needs more heap");
        }
        return out;
    }

    private static <K> List<Map.Entry<K, Integer>> top(Map<K, Integer> counts, int n) {
        return counts.entrySet().stream().sorted(Map.Entry.<K, Integer>comparingByValue().reversed()).limit(n)
                .toList();
    }

    private static <K> List<Map.Entry<K, Long>> topLong(Map<K, Long> counts, long n) {
        return counts.entrySet().stream().sorted(Comparator.comparing(Map.Entry<K, Long>::getValue).reversed())
                .limit(n).toList();
    }

    private static String pct(long part, long whole) {
        return Math.round(100.0 * part / whole) + "%";
    }
}
