package io.github.imnotstable.msc.core.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.gson.JsonObject;
import io.github.imnotstable.msc.core.FakeAgent;
import io.github.imnotstable.msc.protocol.Limits;
import io.github.imnotstable.msc.protocol.op.Events;
import io.github.imnotstable.msc.protocol.transport.AgentClient;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class AgentHubTest {
    @Test
    void theCallTimeoutOutlastsEveryWaitOfOneAgentOperation() {
        // Else the core gives up on a write the agent still finishes, and its undo id is lost (#275).
        assertThat(AgentHub.CALL_TIMEOUT).isGreaterThan(Limits.AGENT_WAIT.multipliedBy(Limits.AGENT_WAITS_PER_OP));
    }

    @Test
    void anAgentThatSaidItsServerIsStoppingIsNotWaitedFor() throws Exception {
        // #703: with an agent expected (an attached server), a call after the agent left waited the whole 30 s.
        try (AgentHub hub = new AgentHub(0, "t")) {
            hub.agentExpected(() -> true);
            hub.connectWait(Duration.ofSeconds(60));
            AgentClient.Session first = new FakeAgent().connect(hub.address(), hub.token());
            assertThat(hub.require()).isNotNull();
            first.connection().sendEvent(Events.SERVER_STOPPING, new JsonObject());
            first.connection().close();
            long start = System.nanoTime();
            while (hub.current() != null || hub.refusal() == null) {
                assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(20));
                Thread.sleep(10);
            }
            assertThatThrownBy(hub::require).hasMessage(AgentHub.STOPPING);
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(20));

            // A new agent (a new server run) is waited for and used again.
            AgentClient.Session second = new FakeAgent().connect(hub.address(), hub.token());
            try {
                assertThat(hub.require()).isNotNull();
                assertThat(hub.refusal()).isNull();
            } finally {
                second.connection().close();
            }
        }
    }

    @Test
    void requireDoesNotHoldTheHubLockWhileAskingWhetherAnAgentIsExpected() throws Exception {
        try (AgentHub hub = new AgentHub(0, "t")) {
            CountDownLatch asking = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            hub.connectWait(Duration.ofMillis(100));
            // Like ServerManager.running() while start() holds its monitor and calls readyCount().
            hub.agentExpected(() -> {
                asking.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return false;
            });
            CompletableFuture<Void> require = CompletableFuture.runAsync(() -> {
                try {
                    hub.require();
                } catch (Exception expected) {
                    // No agent: require fails, which is fine here.
                }
            });
            assertThat(asking.await(10, TimeUnit.SECONDS)).isTrue();
            try {
                assertThat(CompletableFuture.supplyAsync(hub::readyCount).get(2, TimeUnit.SECONDS)).isZero();
                assertThat(CompletableFuture.supplyAsync(hub::current).get(2, TimeUnit.SECONDS)).isNull();
            } finally {
                release.countDown();
            }
            require.get(10, TimeUnit.SECONDS);
        }
    }
}
