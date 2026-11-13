package io.github.imnotstable.msc.agent.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonObject;
import io.github.imnotstable.msc.protocol.Limits;
import io.github.imnotstable.msc.protocol.message.AgentInfo;
import io.github.imnotstable.msc.protocol.message.Event;
import io.github.imnotstable.msc.protocol.op.Events;
import io.github.imnotstable.msc.protocol.transport.CoreServer;
import io.github.imnotstable.msc.protocol.transport.OpRouter;
import io.github.imnotstable.msc.protocol.transport.RequestHandler;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class AgentLinkTest {
    private static final String TOKEN = "link-token";
    private static final AgentInfo INFO = new AgentInfo("test", "1", "26.3", List.of("server.info"));

    private final List<String> logged = new CopyOnWriteArrayList<>();
    private final AgentLog log = new AgentLog() {
        @Override
        public void debug(String message) {
            logged.add("debug " + message);
        }

        @Override
        public void info(String message) {
            logged.add("info " + message);
        }

        @Override
        public void warn(String message) {
            logged.add("warn " + message);
        }

        @Override
        public void error(String message) {
            logged.add("error " + message);
        }
    };
    private final BlockingQueue<CoreServer.AgentSession> sessions = new LinkedBlockingQueue<>();
    private final BlockingQueue<String> events = new LinkedBlockingQueue<>();
    private final BlockingQueue<String> rejections = new LinkedBlockingQueue<>();
    private CoreServer core;
    private AgentLink link;

    @AfterEach
    void tearDown() throws Exception {
        if (link != null) {
            link.stop();
        }
        if (core != null) {
            core.close();
        }
    }

    private void startCore() throws Exception {
        core = CoreServer.start(0, TOKEN, RequestHandler.none(), new CoreServer.AgentListener() {
            @Override
            public void connected(CoreServer.AgentSession session) {
                sessions.add(session);
            }

            @Override
            public void event(CoreServer.AgentSession session, Event event) {
                events.add(event.name());
            }

            @Override
            public void rejected(String reason) {
                rejections.add(reason);
            }
        }, Duration.ofSeconds(5));
    }

    private AgentLink link(Supplier<Optional<CoreAddress>> address) {
        link = new AgentLink(INFO, new OpRouter(), address, log);
        return link;
    }

    private AgentLink linkToCore(String token) {
        return link(() -> Optional.of(new CoreAddress("127.0.0.1", core.port(), token)));
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        for (int i = 0; i < 500 && !condition.getAsBoolean(); i++) {
            TimeUnit.MILLISECONDS.sleep(10);
        }
        assertThat(condition.getAsBoolean()).as("condition within 5 s").isTrue();
    }

    @Test
    void staysIdleWithoutACoreAddressAndDropsEvents() throws InterruptedException {
        link(Optional::empty).start();
        await(() -> !logged.isEmpty());
        link.serverLoaded();
        link.playerJoined("Alex", UUID.randomUUID());
        link.stop();

        assertThat(logged).containsExactly("info MSC_CORE_ADDRESS is not set; agent is idle.");
        assertThat(link.session()).isNull();
        assertThat(link.stopping()).isTrue();
    }

    @Test
    void readyOnceWhenConnectedBeforeLoad() throws Exception {
        startCore();
        linkToCore(TOKEN).start();
        assertThat(sessions.poll(5, TimeUnit.SECONDS)).isNotNull();
        await(() -> link.session() != null);
        assertThat(events.poll(200, TimeUnit.MILLISECONDS)).as("no ready before load").isNull();

        link.serverLoaded();
        link.serverLoaded();
        assertThat(events.poll(5, TimeUnit.SECONDS)).isEqualTo(Events.SERVER_READY);
        assertThat(events.poll(200, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void readyOnceWhenLoadedBeforeConnect() throws Exception {
        startCore();
        linkToCore(TOKEN);
        link.serverLoaded();
        link.start();
        assertThat(events.poll(5, TimeUnit.SECONDS)).isEqualTo(Events.SERVER_READY);
        assertThat(events.poll(200, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void refusedHandshakeStopsRetrying() throws Exception {
        startCore();
        linkToCore("wrong").start();
        assertThat(rejections.poll(5, TimeUnit.SECONDS)).isNotNull();
        // The first retry would come after 500 ms of backoff.
        assertThat(rejections.poll(1200, TimeUnit.MILLISECONDS)).isNull();
        assertThat(logged).anyMatch(line -> line.startsWith("error msc-core refused the connection"));
        assertThat(link.session()).isNull();
    }

    @Test
    void badAddressStopsRetrying() throws Exception {
        AtomicInteger asked = new AtomicInteger();
        link(() -> {
            asked.incrementAndGet();
            return CoreAddress.resolve(name -> null, name -> name.equals(CoreAddress.PROPERTY_ADDRESS) ? "no-port" : "t");
        }).start();
        await(() -> !logged.isEmpty());
        TimeUnit.MILLISECONDS.sleep(700);
        assertThat(asked).hasValue(1);
        assertThat(logged).singleElement().asString().startsWith("error Bad msc-core address");
    }

    @Test
    void reconnectsAfterTheCoreDropsItAndSendsReadyAgain() throws Exception {
        startCore();
        linkToCore(TOKEN);
        link.serverLoaded();
        link.start();
        CoreServer.AgentSession first = sessions.poll(5, TimeUnit.SECONDS);
        assertThat(first).isNotNull();
        assertThat(events.poll(5, TimeUnit.SECONDS)).isEqualTo(Events.SERVER_READY);

        first.connection().close();
        CoreServer.AgentSession second = sessions.poll(5, TimeUnit.SECONDS);
        assertThat(second).isNotNull();
        assertThat(second.sessionId()).isNotEqualTo(first.sessionId());
        assertThat(events.poll(5, TimeUnit.SECONDS)).isEqualTo(Events.SERVER_READY);
        await(() -> link.session() != null && link.session().sessionId().equals(second.sessionId()));
    }

    @Test
    void stopSaysStoppingAndDoesNotReconnect() throws Exception {
        startCore();
        linkToCore(TOKEN).start();
        CoreServer.AgentSession session = sessions.poll(5, TimeUnit.SECONDS);
        assertThat(session).isNotNull();
        await(() -> link.session() != null);

        link.stop();
        assertThat(link.session()).isNull();
        assertThat(events.poll(5, TimeUnit.SECONDS)).isEqualTo(Events.SERVER_STOPPING);
        await(() -> !session.connection().isOpen());
        assertThat(sessions.poll(1200, TimeUnit.MILLISECONDS)).isNull();
        assertThat(link.session()).isNull();
    }

    @Test
    void anEventTooLargeToSendIsLoggedAsAWarningAndTheLinkStays() throws Exception {
        startCore();
        linkToCore(TOKEN).start();
        assertThat(sessions.poll(5, TimeUnit.SECONDS)).isNotNull();
        await(() -> link.session() != null);
        JsonObject data = new JsonObject();
        data.addProperty("text", "x".repeat(Limits.MAX_FRAME_BYTES));

        link.send(Events.PLAYER_JOIN, data);
        link.serverLoaded();

        assertThat(logged).anySatisfy(l -> assertThat(l).startsWith("warn Could not send " + Events.PLAYER_JOIN)
                .contains("frame limit"));
        assertThat(events.poll(30, TimeUnit.SECONDS)).isEqualTo(Events.SERVER_READY);
    }
}
