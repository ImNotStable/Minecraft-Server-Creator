package io.github.imnotstable.msc.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.core.fixture.FailingPackHostMain;
import io.github.imnotstable.msc.core.fixture.FakeBuildMain;
import io.github.imnotstable.msc.core.fixture.FakeServerMain;
import io.github.imnotstable.msc.core.fixture.SleepMain;
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
import org.junit.jupiter.api.Test;
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
 * <p>
 * #750: a third table starts from a server an earlier session detached and left running ({@link Left}: with its
 * standalone host, with that host gone since, or with a host that never came up), then runs pack, dev_cycle,
 * server_start, server_stop, server_detach and a switch (alone, in pairs, and with a host that fails) before the session
 * end. Such a leftover outlives this session as a server detached in it does, so its offer, wherever it is, always
 * names a URL that a host which outlives the session serves, or no URL; the earlier session's host is reused or
 * replaced, never left beside a second one.
 */
class PackLifecycleOrderTest {
    private static final String PROPERTIES = "motd=x\n";

    enum Op { DETACH, PACK, DEV_CYCLE, SWITCH, STOP, START, BREAK, MEND }

    /** What the earlier session left with its detached server (#750). */
    enum Left {
        /** Its standalone host, which serves the pack that server.properties offers. */
        HOST_UP,
        /** Nothing: the host died after that session, so the offer names a URL nothing serves. */
        HOST_DIED,
        /** No host and no offer: the host never came up on that session's detach (#747). */
        NO_HOST
    }

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

    /**
     * #750: from a leftover with each {@link Left}, each of pack, dev_cycle, server_start, server_stop, server_detach
     * and a switch alone and (but for {@link Left#NO_HOST}) every ordered pair of them, then the session end; and the
     * host failing among pack and a switch.
     */
    static Stream<Arguments> leftovers() {
        List<Op> ops = List.of(Op.PACK, Op.DEV_CYCLE, Op.START, Op.STOP, Op.DETACH, Op.SWITCH);
        List<Arguments> rows = new ArrayList<>();
        for (Left left : Left.values()) {
            rows.add(Arguments.of(left, List.of()));
            for (Op first : ops) {
                rows.add(Arguments.of(left, List.of(first)));
                for (Op second : left == Left.NO_HOST ? List.<Op>of() : ops) {
                    if (second != first) {
                        rows.add(Arguments.of(left, List.of(first, second)));
                    }
                }
            }
        }
        for (Left left : List.of(Left.HOST_UP, Left.HOST_DIED)) {
            rows.add(Arguments.of(left, List.of(Op.BREAK)));
            rows.add(Arguments.of(left, List.of(Op.BREAK, Op.PACK, Op.MEND, Op.PACK)));
            rows.add(Arguments.of(left, List.of(Op.BREAK, Op.SWITCH, Op.MEND, Op.SWITCH)));
            rows.add(Arguments.of(left, List.of(Op.BREAK, Op.START, Op.DETACH, Op.MEND, Op.PACK)));
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
    /** #750: a server an earlier session left running, not taken over yet; it outlives this session too. */
    private boolean leftover;
    /** #750: the earlier session's offer names a URL nothing serves, and nothing was published since. */
    private boolean stale;
    /** Whether dev_cycle restarts the server (the leftover table: the call that takes a leftover over). */
    private boolean restart;

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

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("leftovers")
    void aLeftoverOutlivesTheSessionAsADetachedServerDoes(Left left, List<Op> order) throws Exception {
        leftBy(left);
        restart = true;
        for (Op op : order) {
            String when = op + " in " + order + " after " + left;
            List<String> answers = run(op);
            check(Platform.PAPER, when, false);
            for (String answer : answers) {
                claims(Platform.PAPER, when, answer);
            }
        }
        // The session end publishes once more, a leftover's pack too; with a host that fails when the order ends so.
        h.core.pack().hostCommand(java(failing ? FailingPackHostMain.class : PackHost.class));
        h.close();
        if (detached || leftover) {
            published();
        }
        check(Platform.PAPER, "the session end after " + order + " after " + left, true);
    }

    /**
     * #750: a server another live session runs here (ADR 0037) outlives this session too, but it is that session's,
     * and so is its offer: a pack built in this session, and this session's end, leave server.properties as it was.
     */
    @Test
    void anotherSessionsServerKeepsItsOffer() throws Exception {
        ws = Files.createDirectories(root.resolve("ws"));
        Path properties = Files.createDirectories(ws.resolve("run")).resolve("server.properties");
        Files.writeString(properties, PROPERTIES);
        String sha1 = "a".repeat(40);
        PackProperties.write(Platform.PAPER, properties, "http://127.0.0.1:1/pack-" + sha1 + ".zip", sha1, false);
        byte[] theirs = Files.readAllBytes(properties);
        Process server = new ProcessBuilder(java(SleepMain.class)).start();
        Process core = new ProcessBuilder(java(SleepMain.class)).start();
        try {
            Files.createDirectories(ws.resolve(".msc"));
            Files.writeString(ws.resolve(".msc/server.pid"), identity(server) + " " + identity(core) + "\n");
            h = new CoreHarness(ws);
            Platform.remember(h.core.config(), Platform.PAPER);
            draw();
            assertThat(h.ok("pack")).doesNotContain("updated to offer");
            assertThat(properties).hasBinaryContent(theirs);
            h.close();
            assertThat(properties).hasBinaryContent(theirs);
        } finally {
            core.destroyForcibly();
            core.waitFor(10, TimeUnit.SECONDS);
        }
    }

    private static String identity(Process p) {
        return p.pid() + " " + p.info().startInstant().orElseThrow().toEpochMilli();
    }

    /**
     * The earlier session: start, a pack, server_detach, the session end; {@code left} says what it leaves beside the
     * server. Then this session opens the same workspace.
     */
    private void leftBy(Left left) throws Exception {
        start(Platform.PAPER);
        draw();
        h.ok("pack");
        failing = left == Left.NO_HOST;
        h.core.pack().hostCommand(java(failing ? FailingPackHostMain.class : PackHost.class));
        h.ok("server_detach");
        url = h.core.pack().lastBuilt().url();
        h.close();
        h = null;
        if (left == Left.HOST_DIED) {
            ProcessHandle host = PidFile.peek(ws.resolve(".msc/pack-host.pid")).orElseThrow();
            host.destroyForcibly();
            host.onExit().get(10, TimeUnit.SECONDS);
        }
        h = new CoreHarness(ws, c -> c.withServerCommand(java(FakeServerMain.class))
                .withBuildCommand(java(FakeBuildMain.class)));
        running = false;
        leftover = true;
        built = true;
        failing = false;
        hostUp = left == Left.HOST_UP;
        offered = left != Left.NO_HOST;
        stale = left == Left.HOST_DIED;
        assertThat(serves(url)).as("the earlier session's pack, served past it").isEqualTo(hostUp);
        assertThat(PackProperties.url(Platform.PAPER, ws.resolve("run/server.properties")))
                .isEqualTo(offered ? url : null);
    }

    private void start(Platform platform) throws Exception {
        ws = Files.createDirectories(root.resolve("ws"));
        Path project = Files.createDirectories(ws.resolve("demo"));
        Files.writeString(project.resolve("version.txt"), "1.0");
        Files.createDirectories(ws.resolve("run"));
        Files.writeString(ws.resolve("run/server.properties"), PROPERTIES);
        h = new CoreHarness(ws, c -> c.withServerCommand(java(FakeServerMain.class))
                .withBuildCommand(java(FakeBuildMain.class)));
        Platform.remember(h.core.config(), platform);
        h.ok("server_start", "timeoutSeconds", 60);
    }

    private void runs(Platform platform, List<Op> order) throws Exception {
        start(platform);
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
                    // A leftover is no server of this session's to detach; the refusal changes nothing.
                    assertThat(h.error("server_detach")).contains(leftover ? "left by an earlier session"
                            : "not running");
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
                String answer = h.ok("dev_cycle", "project", "demo", "restart", restart);
                if (restart && !answer.contains("server not restarted")) {
                    // A start: it takes a leftover over (its server and pack host stopped first), as server_start does.
                    started(answer);
                } else if (running && built) {
                    // Only a running server's dev_cycle rebuilds and sends the workspace's pack (#690).
                    url = h.core.pack().lastBuilt().url();
                    published();
                }
                return List.of(answer);
            }
            case SWITCH -> {
                String away = h.ok("msc_workspace", "path", root.resolve("other").toString(), "create", true);
                String back = h.ok("msc_workspace", "path", ws.toString());
                // A switch away stops a server that is not detached; a detached one's pack is published by both, and
                // a leftover's by the close of the workspace, as at the session end (#750).
                running = running && detached;
                if (detached || leftover) {
                    published();
                }
                return List.of(away, back);
            }
            case STOP -> {
                String answer = h.ok("server_stop");
                if (leftover) {
                    assertThat(answer).startsWith("stopped a server left running by an earlier session");
                } else {
                    assertThat(answer.contains("the pack host it kept running")).as(answer).isEqualTo(hostUp);
                }
                running = false;
                detached = false;
                leftover = false;
                hostUp = false;
                return List.of(answer);
            }
            case START -> {
                if (running) {
                    assertThat(h.error("server_start", "timeoutSeconds", 60)).contains("already running");
                    return List.of();
                }
                String answer = h.ok("server_start", "timeoutSeconds", 60);
                started(answer);
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
     * A start, by server_start or dev_cycle: it stops a leftover and the pack host serving it, then rebuilds the
     * workspace's pack, follows this workspace's offer and sends it.
     */
    private void started(String answer) {
        if (leftover) {
            assertThat(answer).as(answer).contains("stopped a server left running by an earlier session");
        }
        running = true;
        detached = false;
        leftover = false;
        if (built) {
            url = h.core.pack().lastBuilt().url();
            published();
        }
    }

    /**
     * A build published while the server outlives the session (detached in it, or a leftover, #750) is served by the
     * standalone host (an earlier session's when it serves the build, else one started when none runs, unless it
     * fails), and offered exactly while that host serves it (#747). While the server ends with the session, no
     * standalone host runs and an offer is followed from then on (ADR 0017).
     */
    private void published() {
        stale = false;
        boolean outlives = detached || leftover;
        if (outlives && built) {
            hostUp = hostUp || !failing;
            offered = hostUp;
        } else if (!outlives) {
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
            assertThat(hostUp || !detached && !leftover).as(as).isTrue();
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
            // This session's server; a leftover's agent has no connection to it, so only its offer is checked below.
            assertThat(serves(url)).as("%s: %s served", when, url).isTrue();
        }
        Path properties = ws.resolve("run/server.properties");
        if (!platform.readsPackProperties()) {
            assertThat(properties).as(when).hasContent(PROPERTIES);
        } else {
            String offer = PackProperties.url(platform, properties);
            assertThat(offer).as("%s: the offer", when).isEqualTo(offered ? url : null);
            // #750: while a server runs, and past the session while it outlives it, what it offers is served, and
            // past the session what is served is offered.
            boolean outlives = detached || leftover;
            if (!stale && (ended ? outlives : running || outlives)) {
                assertThat(offer == null || serves(offer)).as("%s: the offered %s served", when, offer).isTrue();
                if (ended) {
                    assertThat(serves(url)).as("%s: %s served exactly when offered", when, url)
                            .isEqualTo(offer != null);
                }
            }
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
