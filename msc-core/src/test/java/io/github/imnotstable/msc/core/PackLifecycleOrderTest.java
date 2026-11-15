package io.github.imnotstable.msc.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.core.fixture.FakeBuildMain;
import io.github.imnotstable.msc.core.fixture.FakeServerMain;
import io.github.imnotstable.msc.core.server.PidFile;
import io.github.imnotstable.msc.core.server.Platform;
import io.github.imnotstable.msc.core.texture.PackProperties;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * ADR 0051 (#743, #744, #745): the pack of a detached server is published from the state alone, so the order of
 * server_detach, pack, dev_cycle's rebuild, a switch away and back, server_stop and the session end never matters.
 * A table over those orders, on Paper (the fake server; it reads its pack from server.properties) and on Minestom
 * (which does not), each ending with the session end. After every step, and after the end: the newest build is served
 * while the server runs or is detached, server.properties offers it exactly when the platform reads the file and a
 * build was published while the server was detached, and a standalone pack host runs exactly when a detached server
 * has a build, so none is left orphaned.
 */
class PackLifecycleOrderTest {
    private static final String PROPERTIES = "motd=x\n";

    enum Op { DETACH, PACK, DEV_CYCLE, SWITCH, STOP }

    @TempDir
    Path root;
    private CoreHarness h;
    private Path ws;

    /** Every order of detach, pack, dev_cycle and a switch, on both platforms, and server_stop at each place of two. */
    static Stream<Arguments> orders() {
        List<List<Op>> orders = new ArrayList<>();
        permutations(List.of(Op.DETACH, Op.PACK, Op.DEV_CYCLE, Op.SWITCH), new ArrayList<>(), orders);
        List<Arguments> rows = new ArrayList<>();
        for (Platform platform : List.of(Platform.PAPER, Platform.MINESTOM)) {
            orders.forEach(order -> rows.add(Arguments.of(platform, order)));
        }
        for (List<Op> base : List.of(List.of(Op.PACK, Op.DETACH, Op.DEV_CYCLE, Op.SWITCH),
                List.of(Op.DETACH, Op.PACK, Op.SWITCH, Op.DEV_CYCLE))) {
            for (int at = 0; at <= base.size(); at++) {
                List<Op> order = new ArrayList<>(base);
                order.add(at, Op.STOP);
                rows.add(Arguments.of(Platform.PAPER, order));
            }
        }
        return rows.stream();
    }

    private static void permutations(List<Op> left, List<Op> prefix, List<List<Op>> out) {
        if (left.isEmpty()) {
            out.add(List.copyOf(prefix));
            return;
        }
        for (Op op : left) {
            List<Op> rest = new ArrayList<>(left);
            rest.remove(op);
            prefix.add(op);
            permutations(rest, prefix, out);
            prefix.removeLast();
        }
    }

    private static List<String> java(Class<?> main) {
        return List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), main.getName());
    }

    /** The model: what the state should be after the steps so far. */
    private boolean running = true;
    private boolean detached;
    private boolean built;
    private boolean offered;
    private String url;
    private int color;

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("orders")
    void theOrderOfThePackLifecycleDoesNotMatter(Platform platform, List<Op> order) throws Exception {
        ws = Files.createDirectories(root.resolve("ws"));
        Path project = Files.createDirectories(ws.resolve("demo"));
        Files.writeString(project.resolve("version.txt"), "1.0");
        Files.createDirectories(ws.resolve("run"));
        Files.writeString(ws.resolve("run/server.properties"), PROPERTIES);
        h = new CoreHarness(ws, c -> c.withServerCommand(java(FakeServerMain.class))
                .withBuildCommand(java(FakeBuildMain.class)));
        Platform.remember(h.core.config(), platform);
        h.ok("server_start", "timeoutSeconds", 60);
        for (Op op : order) {
            run(op);
            check(platform, op + " in " + order);
        }
        h.close();
        check(platform, "the session end after " + order);
        if (detached && built) {
            assertThat(serves(url)).as("served after the session end").isTrue();
        }
    }

    private void run(Op op) throws Exception {
        switch (op) {
            case DETACH -> {
                if (running) {
                    h.ok("server_detach");
                    detached = true;
                    published();
                } else {
                    assertThat(h.error("server_detach")).contains("not running");
                }
            }
            case PACK -> {
                draw();
                url = h.ok("pack").replaceAll("(?s).*\nurl: (\\S+).*", "$1");
                built = true;
                published();
            }
            case DEV_CYCLE -> {
                draw();
                h.ok("dev_cycle", "project", "demo", "restart", false);
                // Only a running server's dev_cycle rebuilds and sends the workspace's pack (#690).
                if (running && built) {
                    url = h.core.pack().lastBuilt().url();
                    published();
                }
            }
            case SWITCH -> {
                h.ok("msc_workspace", "path", root.resolve("other").toString(), "create", true);
                h.ok("msc_workspace", "path", ws.toString());
                // A switch away stops a server that is not detached.
                running = running && detached;
            }
            case STOP -> {
                h.ok("server_stop");
                running = false;
                detached = false;
            }
        }
    }

    /** A build published while the server is detached is offered, and followed from then on (ADR 0017). */
    private void published() {
        offered = offered || detached && built;
    }

    private void draw() {
        color++;
        h.ok("texture", "name", "item/dot", "palette", List.of(String.format("a=#%06x", color * 0x101)),
                "rows", List.of("a"));
    }

    private void check(Platform platform, String when) throws Exception {
        if (built && (running || detached)) {
            assertThat(serves(url)).as("%s: %s served", when, url).isTrue();
        }
        Path properties = ws.resolve("run/server.properties");
        if (!platform.readsPackProperties()) {
            assertThat(properties).as(when).hasContent(PROPERTIES);
        } else {
            assertThat(PackProperties.url(platform, properties)).as("%s: the offer", when).isEqualTo(offered ? url : null);
        }
        assertThat(PidFile.peek(ws.resolve(".msc/pack-host.pid")).filter(ProcessHandle::isAlive).isPresent())
                .as("%s: a standalone pack host runs", when).isEqualTo(detached && built);
    }

    /** True when {@code url} serves the pack now (a 200). */
    private static boolean serves(String url) {
        try {
            HttpURLConnection c = (HttpURLConnection) URI.create(url).toURL().openConnection(Proxy.NO_PROXY);
            c.setConnectTimeout(2000);
            c.setReadTimeout(2000);
            try {
                return c.getResponseCode() == 200;
            } finally {
                c.disconnect();
            }
        } catch (IOException e) {
            return false;
        }
    }

    /** The session ended; the detached server and its pack host, which outlive it, are stopped here. */
    @AfterEach
    void stop() throws Exception {
        if (h != null) {
            h.close();
        }
        for (String file : List.of("server.pid", "pack-host.pid")) {
            Path pid = ws.resolve(".msc/" + file);
            if (Files.exists(pid)) {
                ProcessHandle.of(Long.parseLong(Files.readString(pid).split(" ")[0].strip())).ifPresent(p -> {
                    p.destroyForcibly();
                    p.onExit().orTimeout(10, TimeUnit.SECONDS).join();
                });
            }
        }
    }
}
