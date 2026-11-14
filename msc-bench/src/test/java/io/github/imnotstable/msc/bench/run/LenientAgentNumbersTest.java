package io.github.imnotstable.msc.bench.run;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.imnotstable.msc.bench.check.CheckResult;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Every number in the agent's result goes through one lenient reader: a wrong type is unparsed output (#529). */
class LenientAgentNumbersTest {

    private static final List<String> TOP = List.of("num_turns", "duration_api_ms", "total_cost_usd");
    private static final List<String> USAGE = List.of("input_tokens", "cache_creation_input_tokens",
            "cache_read_input_tokens", "output_tokens");
    private static final List<String> WRONG = List.of("null", "true", "false", "\"abc\"", "\"12\"", "{}", "[]", "[1]",
            "{\"n\":1}", "1e999");

    static Stream<Arguments> cases() {
        return Stream.concat(TOP.stream().map(f -> new String[] {"top", f}), USAGE.stream().map(f -> new String[] {"usage", f}))
                .flatMap(f -> WRONG.stream().map(v -> Arguments.of(f[0], f[1], v)));
    }

    private static String stdout(String where, String field, String value) {
        JsonObject result = JsonParser.parseString("""
                {"type":"result","is_error":false,"num_turns":3,"duration_api_ms":10,"total_cost_usd":0.5,
                 "usage":{"input_tokens":1,"cache_creation_input_tokens":2,"cache_read_input_tokens":3,"output_tokens":4}}
                """).getAsJsonObject();
        JsonObject target = where.equals("top") ? result : result.getAsJsonObject("usage");
        target.add(field, JsonParser.parseString(value));
        return result.toString();
    }

    @ParameterizedTest(name = "{1}={2}")
    @MethodSource("cases")
    void wrongTypeIsUnparsedAndAPassingAttemptStillPasses(String where, String field, String value) {
        String stdout = stdout(where, field, value);

        AgentResult agent = ClaudeResultParser.parse(stdout);
        assertThat(agent).isEqualTo(AgentResult.UNKNOWN);
        assertThat(ClaudeResultParser.usageLimit(stdout)).isNull();
        RunRecord record = new RunRecord("t", "c", "b", 1, "2026-01-01T00:00:00Z", true, 1000, false, 0, null, agent,
                ToolStats.NONE, List.of(CheckResult.pass("file", "ok")));
        assertThat(record.passed()).isTrue();
        assertThat(record.failureReason()).isNull();
    }

    @ParameterizedTest(name = "api_error_status={0}")
    @MethodSource("wrongValues")
    void wrongStatusIsNoUsageLimit(String value) {
        String stdout = "{\"type\":\"result\",\"is_error\":false,\"api_error_status\":" + value + "}";

        assertThat(ClaudeResultParser.usageLimit(stdout)).isNull();
    }

    static Stream<String> wrongValues() {
        return WRONG.stream();
    }

    @ParameterizedTest(name = "status={0}")
    @MethodSource("wrongValues")
    void wrongMcpServerFieldsAreSkipped(String value) {
        String stdout = "[{\"type\":\"system\",\"mcp_servers\":[{\"name\":\"a\",\"status\":" + value + "},"
                + "{\"name\":" + value + ",\"status\":\"connected\"}]},{\"type\":\"result\",\"num_turns\":1}]";

        assertThat(ClaudeResultParser.parse(stdout).parsed()).isTrue();
    }
}
