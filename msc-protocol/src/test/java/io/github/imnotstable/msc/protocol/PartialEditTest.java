package io.github.imnotstable.msc.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.protocol.message.ErrorCodes;
import io.github.imnotstable.msc.protocol.message.ProtocolError;
import io.github.imnotstable.msc.protocol.op.PartialEdit;
import io.github.imnotstable.msc.protocol.transport.OpException;
import org.junit.jupiter.api.Test;

class PartialEditTest {
    @Test
    void theIdSurvivesTheTripThroughAProtocolError() {
        OpException sent = new OpException(ErrorCodes.TIMEOUT, PartialEdit.note("main thread did not finish within 120s", "k3x-7"));
        OpException received = new OpException(new ProtocolError(sent.code(), sent.detail()));

        assertThat(PartialEdit.editId(received.detail())).contains("k3x-7");
        assertThat(received.detail()).startsWith("main thread did not finish within 120s (");
        assertThat(PartialEdit.editId("main thread did not finish within 120s")).isEmpty();
        assertThat(PartialEdit.editId(null)).isEmpty();
    }

    @Test
    void stripRemovesOnlyTheNote() {
        assertThat(PartialEdit.strip(PartialEdit.note("timed out", "k3x-7"))).isEqualTo("timed out");
        assertThat(PartialEdit.strip("timed out (see log)")).isEqualTo("timed out (see log)");
        assertThat(PartialEdit.strip(null)).isNull();
    }

    @Test
    void theCoreWaitsPastEveryWaitOfOneAgentOperation() {
        assertThat(Limits.CORE_CALL_TIMEOUT)
                .isGreaterThan(Limits.AGENT_WAIT.multipliedBy(Limits.AGENT_WAITS_PER_OP));
    }
}
