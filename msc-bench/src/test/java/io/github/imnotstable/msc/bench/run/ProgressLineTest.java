package io.github.imnotstable.msc.bench.run;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.bench.check.CheckResult;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** The terminal's line for a finished attempt names why it failed, with the same text as the report (#525). */
class ProgressLineTest {

    private static final AgentResult OK = new AgentResult(true, false, 3, 1000, 10, 0, 0, 5, 0.1, "m", List.of());
    private static final AgentResult AGENT_ERROR = new AgentResult(true, true, 3, 1000, 10, 0, 0, 5, 0.1, "m", List.of());
    private static final List<CheckResult> PASSING = List.of(CheckResult.pass("file", "ok"));

    private static RunRecord record(boolean passed, boolean timedOut, int exitCode, String error, AgentResult agent,
            List<CheckResult> checks) {
        return new RunRecord("t-pass", "c", "b", 1, "2026-01-01T00:00:00Z", passed, 60_500, timedOut, exitCode, error,
                agent, ToolStats.NONE, checks);
    }

    static Stream<Arguments> failures() {
        return Stream.of(
                Arguments.of("timeout", record(false, true, -1, null, OK, PASSING),
                        "[b] t-pass #1 FAIL in 60s, 15 tokens, 1/1 checks (timed out after 60s)"),
                Arguments.of("failed check", record(false, false, 0, null, OK, List.of(CheckResult.fail("file", "missing"))),
                        "[b] t-pass #1 FAIL in 60s, 15 tokens, 0/1 checks (file: missing)"),
                Arguments.of("bench error", record(false, false, 0, "server did not start", OK, List.of()),
                        "[b] t-pass #1 FAIL in 60s, 15 tokens, 0/0 checks (server did not start)"),
                Arguments.of("agent error", record(false, false, 0, null, AGENT_ERROR, List.of()),
                        "[b] t-pass #1 FAIL in 60s, 15 tokens, 0/0 checks (agent reported an error)"),
                Arguments.of("unparsed output", record(false, false, 0, null, AgentResult.UNKNOWN, List.of()),
                        "[b] t-pass #1 FAIL in 60s, 0 tokens, 0/0 checks (agent output not parsed)"),
                Arguments.of("non-zero exit", record(false, false, 2, null, OK, List.of()),
                        "[b] t-pass #1 FAIL in 60s, 15 tokens, 0/0 checks (agent exited with code 2)"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("failures")
    void failedAttemptNamesItsReason(String name, RunRecord record, String line) {
        assertThat(BenchRunner.progressLine(record)).isEqualTo(line);
        assertThat(line).endsWith("(" + record.failureReason() + ")");
    }

    @Test
    void passingAttemptHasNoReason() {
        RunRecord record = record(true, false, 0, null, OK, PASSING);

        assertThat(record.failureReason()).isNull();
        assertThat(BenchRunner.progressLine(record)).isEqualTo("[b] t-pass #1 PASS in 60s, 15 tokens, 1/1 checks");
    }

    @Test
    void malformedNumberIsUnparsedOutput() {
        String stdout = """
                {"type":"result","is_error":false,"num_turns":"abc","api_error_status":"x","usage":{}}""";

        assertThat(ClaudeResultParser.parse(stdout)).isEqualTo(AgentResult.UNKNOWN);
        assertThat(ClaudeResultParser.usageLimit(stdout)).isNull();
    }
}
