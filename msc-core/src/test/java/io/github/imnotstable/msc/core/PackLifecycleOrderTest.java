package io.github.imnotstable.msc.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.core.fixture.FailingPackHostMain;
import io.github.imnotstable.msc.core.fixture.FakeBuildMain;
import io.github.imnotstable.msc.core.fixture.FakeServerMain;
import io.github.imnotstable.msc.core.server.PidFile;
import io.github.imnotstable.msc.core.server.Platform;
import io.github.imnotstable.msc.core.texture.PackHost;
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
 * <p>
 * #747: a second table makes the standalone host fail to come up ({@link Op#BREAK}) and come up again
 * ({@link Op#MEND}) among those calls, also after a server_stop and server_start that leave server.properties offering
 * an earlier detach's pack. A detached server's offer then holds only while a standalone host serves the build, and
 * what every answer says about the pack host and the offer is checked against what runs and what the file says.
 */
class PackLifecycleOrderTest {
    private static final String PROPERTIES = "motd=x\n";

    enum Op { DETACH, PACK, DEV_CYCLE, SWITCH, STOP, START, BREAK, MEND }

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

    /**
     * #747: every order of a host that stops coming up, detach, pack and a switch, then the host mended and a pack:
     * on Paper with server.properties offering an earlier detach's pack (kept by a stop and a start, the issue's
     * case) and without, and on Minestom without.
     */
    static Stream<Arguments> failingHosts() {
        List<List<Op>> orders = new ArrayList<>();
        permutations(List.of(Op.BREAK, Op.DETACH, Op.PACK, Op.SWITCH), new ArrayList<>(), orders);
        List<Arguments> rows = new ArrayList<>();
        for (List<Op> order : orders) {
            List<Op> tail = new ArrayList<>(order);
            tail.addAll(List.of(Op.MEND, Op.PACK));
            List<Op> offered = new ArrayList<>(List.of(Op.PACK, Op.DETACH, Op.STOP, Op.START));
            offered.addAll(tail);
            rows.add(Arguments.of(Platform.PAPER, offered));
            rows.add(Arguments.of(Platform.PAPER, tail));
            rows.add(Arguments.of(Platform.MINESTOM, tail));
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
    /** Whether a standalone host fails to come up when started, and whether one runs now. */
    private boolean failing;
    private boolean hostUp;

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("orders")
    void theOrderOfThePackLifecycleDoesNotMatter(Platform platform, List<Op> order) throws Exception {
        runs(platform, order);
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("failingHosts")
    void noAnswerClaimsAPackHostOrAnOfferThatEndsWithTheSession(Platform platform, List<Op> order) throws Exception {
        runs(platform, order);
    }

    private void runs(Platform platform, List<Op> order) throws Exception {
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
            String when = op + " in " + order;
            List<String> answers = run(op);
            check(platform, when, false);
            for (String answer : answers) {
                claims(platform, when, answer);
            }
        }
        h.close();
        if (detached) {
            published(); // the session end publishes once more (ADR 0051)
        }
        check(platform, "the session end after " + order, true);
    }

    /** Runs {@code op} and updates the model; returns the answers it gave. */
    private List<String> run(Op op) throws Exception {
        // Each time: a switch away from a server that is not detached opens new parts, with the default host command.
        h.core.pack().hostCommand(java(failing ? FailingPackHostMain.class : PackHost.class));
        switch (op) {
            case DETACH -> {
                if (!running) {
                    assertThat(h.error("server_detach")).contains("not running");
                    return List.of();
                }
                boolean before = offered;
                String answer = h.ok("server_detach");
                detached = true;
                published();
                if (built) {
                    assertThat(answer).contains(hostUp ? ", and so does the resource pack host."
                            : "could not start a standalone pack host (");
                    assertThat(answer.endsWith(" and the pack host.")).as(answer).isEqualTo(hostUp);
                    assertThat(answer.contains("offers no pack until a standalone host serves it")).as(answer)
                            .isEqualTo(before && !offered);
                }
                return List.of(answer);
            }
            case PACK -> {
                draw();
                String answer = h.ok("pack");
                url = answer.replaceAll("(?s).*\nurl: (\\S+).*", "$1");
                built = true;
                published();
                return List.of(answer);
            }
            case DEV_CYCLE -> {
                draw();
                String answer = h.ok("dev_cycle", "project", "demo", "restart", false);
                // Only a running server's dev_cycle rebuilds and sends the workspace's pack (#690).
                if (running && built) {
                    url = h.core.pack().lastBuilt().url();
                    published();
                }
                return List.of(answer);
            }
            case SWITCH -> {
                String away = h.ok("msc_workspace", "path", root.resolve("other").toString(), "create", true);
                String back = h.ok("msc_workspace", "path", ws.toString());
                // A switch away stops a server that is not detached; a detached one's pack is published by both.
                running = running && detached;
                if (detached) {
                    published();
                }
                return List.of(away, back);
            }
            case STOP -> {
                String answer = h.ok("server_stop");
                assertThat(answer.contains("the pack host it kept running")).as(answer).isEqualTo(hostUp);
                running = false;
                detached = false;
                hostUp = false;
                return List.of(answer);
            }
            case START -> {
                String answer = h.ok("server_start", "timeoutSeconds", 60);
                running = true;
                // The start rebuilds the workspace's pack, follows this workspace's offer and sends it.
                if (built) {
                    url = h.core.pack().lastBuilt().url();
                    published();
                }
                return List.of(answer);
            }
            case BREAK, MEND -> {
                failing = op == Op.BREAK;
                return List.of();
            }
        }
        throw new AssertionError(op);
    }

    /**
     * A build published while the server is detached is served by the standalone host (started when none runs, unless
     * it fails), and offered exactly while that host serves it (#747). While the server is not detached, no standalone
     * host runs and an offer is followed from then on (ADR 0017).
     */
    private void published() {
        if (detached && built) {
            hostUp = hostUp || !failing;
            offered = hostUp;
        } else if (!detached) {
            hostUp = false;
        }
    }

    /** What {@code answer} says about the pack host and the offer is what runs and what the file says. */
    private void claims(Platform platform, String when, String answer) {
        String as = when + ": " + answer;
        String offer = PackProperties.url(platform, ws.resolve("run/server.properties"));
        if (answer.contains("could not start a standalone pack host")) {
            assertThat(hostUp).as(as).isFalse();
        }
        if (answer.contains("and so does the resource pack host") || answer.contains(" and the pack host.")) {
            assertThat(hostUp).as(as).isTrue();
        }
        if (answer.contains("so a restart outside MSC keeps it")) {
            assertThat(hostUp).as(as).isTrue();
            assertThat(offer).as(as).isEqualTo(url);
        }
        if (answer.contains("updated to offer this build")) {
            assertThat(offer).as(as).isEqualTo(url);
            assertThat(hostUp || !detached).as(as).isTrue();
        }
        if (answer.contains("offers no pack until a standalone host serves it")) {
            assertThat(offer).as(as).isNull();
        }
    }

    private void draw() {
        color++;
        h.ok("texture", "name", "item/dot", "palette", List.of(String.format("a=#%06x", color * 0x101)),
                "rows", List.of("a"));
    }

    private void check(Platform platform, String when, boolean ended) throws Exception {
        if (ended) {
            // Past the session, only a standalone host serves the pack.
            assertThat(serves(url)).as("%s: %s served", when, url).isEqualTo(built && hostUp);
        } else if (built && (running || detached)) {
            assertThat(serves(url)).as("%s: %s served", when, url).isTrue();
        }
        Path properties = ws.resolve("run/server.properties");
        if (!platform.readsPackProperties()) {
            assertThat(properties).as(when).hasContent(PROPERTIES);
        } else {
            assertThat(PackProperties.url(platform, properties)).as("%s: the offer", when).isEqualTo(offered ? url : null);
        }
        assertThat(PidFile.peek(ws.resolve(".msc/pack-host.pid")).filter(ProcessHandle::isAlive).isPresent())
                .as("%s: a standalone pack host runs", when).isEqualTo(hostUp);
    }

    /** True when {@code url} serves the pack now (a 200). */
    private static boolean serves(String url) {
        if (url == null) {
            return false;
        }
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
