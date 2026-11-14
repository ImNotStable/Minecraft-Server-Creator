package io.github.imnotstable.msc.core.agent;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.protocol.Limits;
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
