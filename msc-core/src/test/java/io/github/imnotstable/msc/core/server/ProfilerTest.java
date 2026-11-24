package io.github.imnotstable.msc.core.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** ADR 0090: a Java Flight Recorder profile read into owners, hot methods, GC and config to tune. */
class ProfilerTest {
    @TempDir
    Path dir;

    private static volatile long sink;

    /** Busy on a thread named like the server's main thread, so the profile looks at that thread. */
    private static void spin(AtomicBoolean stop) {
        while (!stop.get()) {
            sink += Long.toString(sink).hashCode();
        }
    }

    /** This test JVM profiled through jcmd, as the server is: the busy method is the hottest, owned by its package. */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aRecordingOfABusyMainThreadNamesItsMethod() throws Exception {
        AtomicBoolean stop = new AtomicBoolean();
        Thread busy = Thread.ofPlatform().name(Profiler.MAIN_THREAD).start(() -> spin(stop));
        Profiler.Recording r;
        try {
            r = Profiler.record(ProcessHandle.current(), 2, dir.resolve("profile/server.jfr"));
        } finally {
            stop.set(true);
            busy.join();
        }
        String report = Profiler.report(r, Files.createDirectories(dir.resolve("ws/run")), Platform.PAPER, "TPS 20.0");
        assertThat(report).startsWith("profile ").contains("samples of the main server thread; TPS 20.0")
                .contains("main thread busy about ")
                .contains("where the time goes: other: io.github.imnotstable.msc.core.server ")
                .containsPattern("hottest methods \\(own time\\):\\n  \\d+% io\\.github\\.imnotstable\\.msc\\.core\\."
                        + "server\\.ProfilerTest\\.spin:\\d+")
                .contains("GC: ");
    }

    @Test
    void framesFallInTheServerAreaTheyName() {
        Profiler.Recording r = new Profiler.Recording(times(5,
                sample("net.minecraft.world.level.levelgen.NoiseChunk.fill:10", "net.minecraft.server.Main.run:1"),
                sample("net.minecraft.world.level.levelgen.NoiseChunk.fill:10", "net.minecraft.server.Main.run:1"),
                sample("net.minecraft.world.entity.Mob.tick:5", "net.minecraft.server.level.ServerLevel.tick:3"),
                sample("java.lang.Thread.sleep:1", "net.minecraft.server.MinecraftServer.waitUntilNextTick:2")),
                2, 300_000_000L, 200_000_000L, 1900L << 20, 2048L << 20, Map.of("[B at a.B.c", 10L),
                Duration.ofSeconds(2));
        String report = Profiler.report(r, dir.resolve("run"), Platform.PAPER, null);
        assertThat(report).contains("where the time goes: server: chunk generation 50%, server: entities 25%, "
                + "server: other 25%")
                .contains("  50% net.minecraft.world.level.levelgen.NoiseChunk.fill:10")
                .contains("GC: 2 collections, 300 ms paused (15.0%), longest 200 ms; heap after GC 1900 of 2048 MB")
                .contains("  - chunk generation is 50%: pregenerate the play area")
                .contains("  - entities is 25%: many entities")
                .contains("  - garbage collection pauses take over 5% of the time");
    }

    @Test
    void aFewSamplesTuneNothing() {
        Profiler.Recording r = new Profiler.Recording(times(1,
                sample("net.minecraft.world.level.levelgen.NoiseChunk.fill:10", "net.minecraft.server.Main.run:1"),
                sample("net.minecraft.server.MinecraftServer.tickServer:1")),
                0, 0, 0, 0, 0, Map.of(), Duration.ofSeconds(5));
        assertThat(Profiler.report(r, dir.resolve("run"), Platform.PAPER, null))
                .contains("only 2 samples: the server was nearly idle").doesNotContain("tune:");
    }

    private static List<Profiler.Sample> times(int n, Profiler.Sample... samples) {
        List<Profiler.Sample> out = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.addAll(List.of(samples));
        }
        return out;
    }

    @Test
    void anIdleServerSaysSo() {
        Profiler.Recording r = new Profiler.Recording(List.of(), 0, 0, 0, 0, 0, Map.of(), Duration.ofSeconds(5));
        assertThat(Profiler.report(r, dir, Platform.PAPER, null)).endsWith("no samples: the server was idle (a profile "
                + "of an idle server shows nothing; play or load it while profiling)");
    }

    private static Profiler.Sample sample(String... frames) {
        return new Profiler.Sample(Profiler.MAIN_THREAD, List.of(frames));
    }
}
