package io.github.imnotstable.msc.bench;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.bench.run.BenchConfig;
import io.github.imnotstable.msc.bench.task.TaskDef;
import io.github.imnotstable.msc.bench.task.TaskLoader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MainTest {

    @TempDir
    Path dir;

    @Test
    void checkStartsTheWorkspacesServerForRconChecksAndStopsIt() throws IOException {
        Files.createDirectories(dir.resolve("tasks/world"));
        Files.writeString(dir.resolve("tasks/world/task.json"), """
                {"category":"world","title":"needs a server",
                 "checks":[{"type":"rcon","command":"list","expect":"players"}]}
                """);
        Files.writeString(dir.resolve("tasks/world/prompt.md"), "look");
        TaskDef task = TaskLoader.loadAll(dir.resolve("tasks")).getFirst();

        Path server = Files.createDirectories(dir.resolve("workspace/server"));
        Files.writeString(server.resolve("paper.jar"), "stands in for paper");
        Files.writeString(server.resolve("eula.txt"), "eula=true");
        Path home = Files.createDirectories(dir.resolve("home/.msc"));
        Files.writeString(home.resolve("eula.json"), """
                {"accepted":true,"eula":"https://aka.ms/MinecraftEULA","acceptedBy":"owner","acceptedAt":"2026-11-12T00:00:00Z"}
                """);
        int port = freePort();
        String java = ProcessHandle.current().info().command().orElse("java").replace("\\", "\\\\");
        String classpath = System.getProperty("java.class.path").replace("\\", "\\\\");
        Files.writeString(dir.resolve("bench.json"), """
                {"server":{"command":["%s","-cp","%s","io.github.imnotstable.msc.bench.FakeRconMain","%d","pw"],
                 "rconPort":%d,"serverPort":%d,"rconPassword":"pw","startTimeoutSeconds":30}}
                """.formatted(java, classpath, port, port, freePort()));
        BenchConfig config = BenchConfig.load(dir.resolve("bench.json"), dir.resolve("home"));
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        int exit = Main.check(task, dir.resolve("workspace"), config, new PrintStream(out, true, StandardCharsets.UTF_8));

        assertThat(out.toString(StandardCharsets.UTF_8)).startsWith("PASS");
        assertThat(exit).isZero();
        assertThat(ProcessHandle.current().descendants().filter(ProcessHandle::isAlive)
                .filter(p -> p.info().commandLine().orElse("").contains("FakeRconMain"))).isEmpty();
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }
}
