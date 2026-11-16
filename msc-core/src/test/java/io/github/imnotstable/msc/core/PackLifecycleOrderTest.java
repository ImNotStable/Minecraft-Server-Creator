package io.github.imnotstable.msc.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.core.fixture.FailingPackHostMain;
import io.github.imnotstable.msc.core.fixture.FakeBuildMain;
import io.github.imnotstable.msc.core.fixture.FakeServerMain;
import io.github.imnotstable.msc.core.fixture.SleepMain;
import io.github.imnotstable.msc.core.server.PidFile;
import io.github.imnotstable.msc.core.server.ServerManagerAccess;
import io.github.imnotstable.msc.core.server.Platform;
import io.github.imnotstable.msc.core.texture.PackHost;
import io.github.imnotstable.msc.core.texture.PackProperties;
import io.github.imnotstable.msc.core.tool.ToolRegistry;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
 * <p>
 * #752: that table adds dev_cycle restart=false on the leftover ({@link Op#KEEP_CYCLE}), which stops its pack host with
 * it, and a leftover that exits on its own ({@link Op#EXIT}), whose host the next publish or the session end stops.
 * #753: a fourth table runs two live sessions on one workspace (ADR 0053), and checks after every step and after both
 * end that no pack host runs without a server and that what a running server offers and was last sent is served.
 * <p>
 * #756, ADR 0054: the two-session and leftover tables run again on a fixed pack port ({@code MSC_PACK_PORT}), the
 * two-session one also with orders where the second session packs before the first starts the server, so its own
 * in-process host holds the fixed port while the first session's standalone host serves on another. The offer must then
 * be served by the host the pid file names, told by its identity, after every step and after both sessions end.
 */
class PackLifecycleOrderTest {
    private static final String PROPERTIES = "motd=x\n";

    /**
     * The calls. {@link #KEEP_CYCLE} is dev_cycle with restart=false (#752), {@link #EXIT} the leftover server exiting
     * on its own while its pack host runs.
     */
    enum Op { DETACH, PACK, DEV_CYCLE, SWITCH, STOP, START, BREAK, MEND, KEEP_CYCLE, EXIT }

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
        // #752: dev_cycle restart=false takes the leftover over as a start does, its pack host too, whether that host
        // runs (the earlier session's, or one this session started), died, or never came up.
        for (Left left : Left.values()) {
            rows.add(Arguments.of(left, List.of(Op.KEEP_CYCLE)));
            rows.add(Arguments.of(left, List.of(Op.PACK, Op.KEEP_CYCLE)));
            rows.add(Arguments.of(left, List.of(Op.KEEP_CYCLE, Op.PACK)));
            rows.add(Arguments.of(left, List.of(Op.SWITCH, Op.KEEP_CYCLE, Op.SWITCH)));
            rows.add(Arguments.of(left, List.of(Op.KEEP_CYCLE, Op.DEV_CYCLE, Op.DETACH)));
        }
        // #752: the leftover's server exits on its own while its pack host runs: the next publish or the session end
        // stops that host.
        rows.add(Arguments.of(Left.HOST_UP, List.of(Op.EXIT)));
        rows.add(Arguments.of(Left.HOST_UP, List.of(Op.EXIT, Op.PACK)));
        rows.add(Arguments.of(Left.HOST_DIED, List.of(Op.PACK, Op.EXIT)));
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
        leftover(left, order);
    }

    /** #756: from a leftover each session served on the same fixed pack port, each op alone and some pairs. */
    static Stream<Arguments> leftoversOnAFixedPort() {
        List<Op> ops = List.of(Op.PACK, Op.DEV_CYCLE, Op.START, Op.STOP, Op.DETACH, Op.SWITCH, Op.KEEP_CYCLE);
        List<Arguments> rows = new ArrayList<>();
        for (Left left : Left.values()) {
            rows.add(Arguments.of(left, List.of()));
            for (Op op : ops) {
                rows.add(Arguments.of(left, List.of(op)));
            }
            rows.add(Arguments.of(left, List.of(Op.PACK, Op.SWITCH)));
            rows.add(Arguments.of(left, List.of(Op.SWITCH, Op.PACK)));
            rows.add(Arguments.of(left, List.of(Op.START, Op.DETACH, Op.PACK)));
        }
        rows.add(Arguments.of(Left.HOST_UP, List.of(Op.BREAK, Op.PACK, Op.MEND, Op.PACK)));
        rows.add(Arguments.of(Left.HOST_UP, List.of(Op.EXIT, Op.PACK)));
        return rows.stream();
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("leftoversOnAFixedPort")
    void aLeftoverOnAFixedPackPortOutlivesTheSessionToo(Left left, List<Op> order) throws Exception {
        packPort = freePackPort();
        leftover(left, order);
    }

    private void leftover(Left left, List<Op> order) throws Exception {
        leftBy(left);
        restart = true;
        for (Op op : order) {
            String when = op + " in " + order + " after " + left;
            List<String> answers = run(op);
            if (op == Op.EXIT) {
                continue; // nothing ran yet that sees the server gone; the next call or the session end does
            }
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
        } else {
            hostUp = false; // no server outlives the session, so no pack host does (#752)
        }
        check(Platform.PAPER, "the session end after " + order + " after " + left, true);
        if (detached || leftover) {
            servedByTheHostThatOutlivesThem("the session end after " + order + " after " + left);
        }
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

    /**
     * #753: two live sessions on one workspace (ADR 0037). Session A starts the server; each session then runs pack,
     * dev_cycle with restart ({@code CYCLE}) and without ({@code KEEP}), server_detach and its end ({@code END}), in
     * the order of the row. A call the other session's server refuses is refused.
     */
    static Stream<Arguments> twoSessions() {
        return Stream.of(
                "A:DETACH B:PACK A:END B:END",
                "A:DETACH B:PACK B:END A:END",
                "B:PACK A:DETACH A:END B:PACK B:END",
                "A:DETACH B:PACK A:PACK A:END B:KEEP B:END",
                "A:DETACH B:PACK A:END B:CYCLE B:PACK B:END",
                "A:DETACH A:END B:PACK B:CYCLE B:DETACH B:END",
                "B:PACK A:PACK B:CYCLE A:DETACH B:PACK A:CYCLE A:END B:END",
                "A:PACK B:PACK A:END B:CYCLE B:END",
                "A:DETACH B:PACK B:DETACH A:END B:DETACH B:KEEP B:END",
                "A:DETACH A:PACK B:PACK A:END B:PACK B:CYCLE B:END",
                "A:DETACH B:PACK A:END B:KEEP B:CYCLE B:DETACH B:PACK B:END",
                "A:DETACH B:PACK B:KEEP A:PACK B:END A:END")
                .map(row -> Arguments.of(List.of(row.split(" "))));
    }

    /**
     * #753: whatever the other session builds, after every step and after both sessions end: no pack host runs without
     * a server, and while a server runs, what server.properties offers and the URL its agent was last sent (what
     * joining players get) are both served. A pack in the session that does not own the server never takes either
     * away.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("twoSessions")
    void twoLiveSessionsLeaveEveryOfferedAndSentPackServed(List<String> order) throws Exception {
        twoLive(order);
    }

    /**
     * #756: the rows of {@link #twoSessions} and orders where B packs before A starts the server ({@code A:START}), all
     * with one fixed pack port in both sessions. The first of the new rows is the issue's order.
     */
    static Stream<Arguments> twoSessionsOnAFixedPort() {
        return Stream.concat(twoSessions(), Stream.of(
                        "B:PACK A:START A:DETACH A:END B:PACK B:END",
                        "B:PACK A:START A:DETACH A:END B:END",
                        "B:PACK A:START A:DETACH B:PACK A:END B:PACK B:END",
                        "B:PACK A:START A:DETACH A:END B:CYCLE B:PACK B:END",
                        "B:PACK A:START A:PACK A:DETACH A:END B:KEEP B:END",
                        "B:PACK A:START A:DETACH A:END B:PACK B:DETACH B:END",
                        "B:PACK A:START A:END B:PACK B:END")
                .map(row -> Arguments.of(List.of(row.split(" ")))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("twoSessionsOnAFixedPort")
    void twoLiveSessionsOnAFixedPackPortLeaveEveryOfferedPackServedByItsHost(List<String> order) throws Exception {
        packPort = freePackPort();
        twoLive(order);
    }

    private void twoLive(List<String> order) throws Exception {
        ws = Files.createDirectories(root.resolve("ws"));
        Files.writeString(Files.createDirectories(ws.resolve("demo")).resolve("version.txt"), "1.0");
        Files.writeString(Files.createDirectories(ws.resolve("run")).resolve("server.properties"), PROPERTIES);
        // Session A's core, as another process: B sees A's server as another live session's until A ends.
        Process coreA = new ProcessBuilder(java(SleepMain.class)).start();
        CoreHarness a = null;
        CoreHarness b = null;
        // Rows with A:START begin before any server runs; the others with A's server started and sent a pack.
        boolean started = !order.contains("A:START");
        try {
            a = session();
            ServerManagerAccess.self(a.core.server(), coreA.toHandle());
            Platform.remember(a.core.config(), Platform.PAPER);
            if (started) {
                a.ok("server_start", "timeoutSeconds", 60);
                draw(a);
                // Sent to A's server, whose agent hands its URL to joining players from now on.
                assertThat(a.ok("pack")).contains("applied: sent to");
                assertThat(lastSent(PidFile.peek(ws.resolve(".msc/server.pid")).orElseThrow().pid())).isNotNull();
                servedWhileItRuns("A's pack");
            }
            b = session();
            boolean bRunning = false;
            for (String step : order) {
                boolean isA = step.startsWith("A:");
                CoreHarness s = isA ? a : b;
                String when = step + " in " + order;
                switch (step.substring(2)) {
                    case "START" -> s.ok("server_start", "timeoutSeconds", 60);
                    case "PACK" -> {
                        draw(s);
                        s.ok("pack");
                    }
                    case "CYCLE", "KEEP" -> {
                        draw(s);
                        boolean restart = step.endsWith("CYCLE");
                        ToolRegistry.Result r = s.call("dev_cycle", "project", "demo", "restart", restart);
                        // B's is refused while A's server runs: it is A's (ADR 0037).
                        assertThat(r.ok()).as("%s: %s", when, r.text()).isEqualTo(isA || a == null);
                        bRunning |= !isA && restart && r.ok();
                    }
                    case "DETACH" -> {
                        ToolRegistry.Result r = s.call("server_detach");
                        assertThat(r.ok()).as("%s: %s", when, r.text()).isEqualTo(isA || bRunning);
                    }
                    case "END" -> {
                        s.close();
                        if (isA) {
                            coreA.destroyForcibly();
                            coreA.waitFor(10, TimeUnit.SECONDS);
                            a = null;
                        } else {
                            b = null;
                            bRunning = false;
                        }
                    }
                    default -> throw new AssertionError(step);
                }
                servedWhileItRuns(when);
            }
            servedWhileItRuns("both sessions ended after " + order);
            servedByTheHostThatOutlivesThem("both sessions ended after " + order);
            // Pruned down to the builds the last session had, offered and sent; each draw made a new one.
            try (Stream<Path> files = Files.list(ws.resolve(".msc/build"))) {
                assertThat(files.filter(f -> f.getFileName().toString().matches("pack-[0-9a-f]{40}\\.zip")).count())
                        .as("build files left after %s", order).isBetween(1L, 3L);
            }
        } finally {
            for (CoreHarness s : new CoreHarness[] {a, b}) {
                if (s != null) {
                    s.close();
                }
            }
            coreA.destroyForcibly();
            coreA.waitFor(10, TimeUnit.SECONDS);
        }
    }

    private CoreHarness session() throws Exception {
        return new CoreHarness(ws, c -> c.withServerCommand(java(FakeServerMain.class))
                .withBuildCommand(java(FakeBuildMain.class)).withPackPort(packPort));
    }

    /** {@code MSC_PACK_PORT} in every session of the test, or 0 for none (the remembered or a free port). */
    private int packPort;

    /** A port free now in the range a pack port is picked from, and none MSC uses otherwise. */
    private static int freePackPort() throws IOException {
        for (int i = 0; i < 100; i++) {
            int port = 20000 + java.util.concurrent.ThreadLocalRandom.current().nextInt(12000);
            if (List.of(25565, 25575, 25577).contains(port)) {
                continue;
            }
            try (java.net.ServerSocket s = new java.net.ServerSocket(port, 50,
                    java.net.InetAddress.getLoopbackAddress())) {
                return s.getLocalPort();
            } catch (IOException e) {
                // Taken: another.
            }
        }
        throw new IOException("no free port for the pack");
    }

    /**
     * #756: past every session, what server.properties offers is served by the standalone host the pid file names,
     * told by the identity it answers (ADR 0054), not merely by something on the offered port.
     */
    private void servedByTheHostThatOutlivesThem(String when) {
        Optional<ProcessHandle> server = PidFile.peek(ws.resolve(".msc/server.pid")).filter(ProcessHandle::isAlive);
        String offer = PackProperties.url(Platform.PAPER, ws.resolve("run/server.properties"));
        if (server.isEmpty() || offer == null) {
            return;
        }
        Optional<ProcessHandle> host = PidFile.peek(ws.resolve(".msc/pack-host.pid")).filter(ProcessHandle::isAlive);
        assertThat(host).as("%s: a host serves the offered %s (%s)", when, offer, hostState()).isPresent();
        int port = Integer.parseInt(offer.replaceAll("http://[^:]+:(\\d+)/.*", "$1"));
        assertThat(hostPid(port)).as("%s: the pid of the host on the offered %s (%s)", when, offer, hostState())
                .isEqualTo(host.get().pid());
    }

    /** The pid the MSC pack host on {@code port} says it runs in, or -1 when none answers. */
    private static long hostPid(int port) {
        try {
            HttpURLConnection c = (HttpURLConnection) URI.create("http://127.0.0.1:" + port + "/msc-pack-host").toURL()
                    .openConnection(Proxy.NO_PROXY);
            c.setConnectTimeout(2000);
            c.setReadTimeout(2000);
            try {
                if (c.getResponseCode() != 200) {
                    return -1;
                }
                String text = new String(c.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("pid=(\\d+)").matcher(text);
                return m.find() ? Long.parseLong(m.group(1)) : -1;
            } finally {
                c.disconnect();
            }
        } catch (IOException e) {
            return -1;
        }
    }

    /** The two-session invariants of {@link #twoLiveSessionsLeaveEveryOfferedAndSentPackServed}. */
    private void servedWhileItRuns(String when) throws IOException {
        Optional<ProcessHandle> server = PidFile.peek(ws.resolve(".msc/server.pid")).filter(ProcessHandle::isAlive);
        boolean host = PidFile.peek(ws.resolve(".msc/pack-host.pid")).filter(ProcessHandle::isAlive).isPresent();
        assertThat(!host || server.isPresent()).as("%s: a pack host runs with no server", when).isTrue();
        if (server.isEmpty()) {
            return;
        }
        String offer = PackProperties.url(Platform.PAPER, ws.resolve("run/server.properties"));
        assertThat(offer == null || serves(offer))
                .as("%s: the offered %s served (%s)", when, offer, hostState()).isTrue();
        Optional<ProcessHandle> standalone = PidFile.peek(ws.resolve(".msc/pack-host.pid"))
                .filter(ProcessHandle::isAlive);
        if (offer != null && standalone.isPresent()) {
            // #756: while a standalone host runs, it is what serves the offer, never a session's in-process host.
            int port = Integer.parseInt(offer.replaceAll("http://[^:]+:(\\d+)/.*", "$1"));
            assertThat(hostPid(port)).as("%s: the pid of the host on the offered %s (%s)", when, offer, hostState())
                    .isEqualTo(standalone.get().pid());
        }
        String sent = lastSent(server.get().pid());
        assertThat(sent == null || serves(sent)).as("%s: the sent %s served", when, sent).isTrue();
    }

    /** The URL the fake server {@code pid} was last sent, which its agent hands joining players, or null. */
    private String lastSent(long pid) throws IOException {
        Path file = ws.resolve("run/fake-pack-sent.txt");
        String last = null;
        if (Files.exists(file)) {
            for (String line : Files.readAllLines(file)) {
                if (line.startsWith(pid + " ")) {
                    last = line.substring(line.indexOf(' ') + 1);
                }
            }
        }
        return last;
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
                .withBuildCommand(java(FakeBuildMain.class)).withPackPort(packPort));
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
                .withBuildCommand(java(FakeBuildMain.class)).withPackPort(packPort));
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
            case KEEP_CYCLE -> {
                draw();
                String answer = h.ok("dev_cycle", "project", "demo", "restart", false);
                if (leftover) {
                    // It stops the leftover to deploy, and the pack host serving it with it (#752).
                    assertThat(answer).as(answer).contains("stopped a server left running by an earlier session");
                    leftover = false;
                    hostUp = false;
                } else if (running && built) {
                    url = h.core.pack().lastBuilt().url();
                    published();
                }
                return List.of(answer);
            }
            case EXIT -> {
                ProcessHandle server = PidFile.peek(ws.resolve(".msc/server.pid")).orElseThrow();
                server.destroyForcibly();
                server.onExit().get(10, TimeUnit.SECONDS);
                leftover = false;
                // Its pack host runs on until the next publish or the session end, which stop it.
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
        draw(h);
    }

    private void draw(CoreHarness session) {
        color++;
        session.ok("texture", "name", "item/dot", "palette", List.of(String.format("a=#%06x", color * 0x101)),
                "rows", List.of("a"));
    }

    private void check(Platform platform, String when, boolean ended) throws Exception {
        if (ended) {
            // Past the session, only a standalone host serves the pack.
            assertThat(serves(url)).as("%s: %s served (%s)", when, url, hostState()).isEqualTo(built && hostUp);
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
                assertThat(offer == null || serves(offer))
                        .as("%s: the offered %s served (%s)", when, offer, hostState()).isTrue();
                if (ended) {
                    assertThat(serves(url)).as("%s: %s served exactly when offered", when, url)
                            .isEqualTo(offer != null);
                }
            }
        }
        assertThat(PidFile.peek(ws.resolve(".msc/pack-host.pid")).filter(ProcessHandle::isAlive).isPresent())
                .as("%s: a standalone pack host runs", when).isEqualTo(hostUp);
    }

    /** What a failed check of the pack host shows: the offer, the remembered port, the host, its log, the build files. */
    private String hostState() {
        try {
            Path msc = ws.resolve(".msc");
            List<String> builds;
            try (Stream<Path> files = Files.list(msc.resolve("build"))) {
                builds = files.map(f -> f.getFileName().toString()).sorted().toList();
            }
            Path port = msc.resolve("pack-port");
            Path log = msc.resolve("pack-host.log");
            return "offer " + PackProperties.url(Platform.PAPER, ws.resolve("run/server.properties"))
                    + ", pack-port " + (Files.exists(port) ? Files.readString(port).strip() : "none")
                    + ", host " + PidFile.peek(msc.resolve("pack-host.pid"))
                            .map(p -> p.pid() + (p.isAlive() ? " alive" : " dead")).orElse("none")
                    + ", host log " + (Files.exists(log) ? Files.readAllLines(log) : "none")
                    + ", builds " + builds;
        } catch (IOException e) {
            return e.toString();
        }
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
