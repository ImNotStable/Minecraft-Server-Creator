package io.github.imnotstable.msc.bench.run;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.bench.check.CheckResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * #602: whatever a run takes from the agent's output and from the tool log, {@code report} reads back from
 * {@code results.jsonl}. A number outside the range the file keeps makes the agent's output unparsed, or leaves the
 * tool log line out with the reader's warning, instead of writing a line {@code report} refuses.
 */
class ResultsRoundTripTest {

    private static final List<String> AGENT_KEYS = List.of("num_turns", "duration_api_ms", "total_cost_usd",
            "usage.input_tokens", "usage.cache_creation_input_tokens", "usage.cache_read_input_tokens",
            "usage.output_tokens");
    private static final List<String> TOOL_KEYS = List.of("resultChars", "resultTokens", "imageTokens", "durationMs");
    /** Numbers of every kind: negative, decimal, beyond each range, beyond an int and a long, with exponents. */
    private static final List<String> NUMBERS = List.of("0", "1", "7", "-1", "-7", "-0.01", "0.5", "2.0", "1e3",
            "1E-3", "1000000", "1000001", "2000000", "2147483648", "3000000000", "-3000000000", "999999.99",
            "1000000.01", "1000000000000000", "1000000000000001", "9223372036854775807", "9223372036854775808",
            "1e30", "-0", "0.0");

    @TempDir
    Path dir;

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            turns beyond an int      | {"type":"result","num_turns":3000000000}
            negative turns           | {"type":"result","num_turns":-1}
            turns above the count    | {"type":"result","num_turns":2000000}
            decimal turns            | {"type":"result","num_turns":2.5}
            negative cost            | {"type":"result","total_cost_usd":-0.01}
            negative output tokens   | {"type":"result","usage":{"output_tokens":-7}}
            tokens above the amounts | {"type":"result","usage":{"input_tokens":1000000000000001}}
            """)
    void agentNumbersReportCannotReadMakeTheOutputUnparsed(String name, String stdout) throws IOException {
        AgentResult agent = ClaudeResultParser.parse(stdout);

        assertThat(agent).isEqualTo(AgentResult.UNKNOWN);
        assertThat(roundTrip(agent, ToolStats.NONE)).isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            negative duration | {"v":1,"tool":"world_slice","ok":true,"resultChars":40,"durationMs":-5}
            negative chars    | {"v":1,"tool":"world_slice","ok":true,"resultChars":-40}
            negative images   | {"v":1,"tool":"world_render","ok":true,"imageTokens":-1}
            decimal tokens    | {"v":1,"tool":"world_slice","ok":true,"resultTokens":1.5}
            huge duration     | {"v":1,"tool":"world_slice","ok":true,"durationMs":1000000000000001}
            """)
    void toolLogLinesReportCannotReadAreLeftOut(String name, String line) throws IOException {
        Path log = dir.resolve("tools.jsonl");
        Files.writeString(log, "{\"v\":1,\"tool\":\"world_slice\",\"ok\":true,\"resultChars\":40,\"durationMs\":5}\n"
                + line + "\n");
        List<String> warnings = new ArrayList<>();

        ToolStats tools = ToolLogReader.read(log, warnings::add);

        assertThat(tools.calls()).isEqualTo(1);
        assertThat(warnings).singleElement().asString().contains("1 line could not be read as a tool call");
        assertThat(roundTrip(AgentResult.UNKNOWN, tools)).isTrue();
    }

    @Test
    void toolLogTotalsStayInsideWhatReportReads() throws IOException {
        // Each line is fine alone; the second takes the duration total past the amounts results.jsonl keeps.
        Path log = dir.resolve("tools.jsonl");
        String line = "{\"v\":1,\"tool\":\"t\",\"ok\":true,\"durationMs\":600000000000000}\n";
        Files.writeString(log, line + line);

        ToolStats tools = ToolLogReader.read(log, w -> { });

        assertThat(tools.calls()).isEqualTo(1);
        assertThat(tools.durationMs()).isEqualTo(600_000_000_000_000L);
        assertThat(roundTrip(AgentResult.UNKNOWN, tools)).isTrue();
    }

    @Test
    void whateverTheParsersTakeReportReadsBack() throws IOException {
        // A property over generated agent outputs and tool logs: every one the parsers accept round-trips.
        Random random = new Random(602);
        for (int i = 0; i < 300; i++) {
            String usage = AGENT_KEYS.stream().filter(k -> k.startsWith("usage.") && random.nextBoolean())
                    .map(k -> "\"" + k.substring(6) + "\":" + pick(random)).collect(Collectors.joining(","));
            String top = AGENT_KEYS.stream().filter(k -> !k.startsWith("usage.") && random.nextBoolean())
                    .map(k -> "\"" + k + "\":" + pick(random) + ",").collect(Collectors.joining());
            String stdout = "{\"type\":\"result\"," + top + "\"usage\":{" + usage + "}}";
            String toolLog = IntStream.range(0, random.nextInt(4))
                    .mapToObj(n -> "{\"v\":1,\"tool\":\"t" + random.nextInt(2) + "\",\"ok\":" + random.nextBoolean()
                            + TOOL_KEYS.stream().filter(k -> random.nextBoolean())
                            .map(k -> ",\"" + k + "\":" + pick(random)).collect(Collectors.joining()) + "}\n")
                    .collect(Collectors.joining());
            Path log = dir.resolve("tools-" + i + ".jsonl");
            Files.writeString(log, toolLog);

            AgentResult agent = ClaudeResultParser.parse(stdout);
            ToolStats tools = ToolLogReader.read(log, w -> { });

            assertThat(roundTrip(agent, tools)).as(stdout + "\n" + toolLog).isTrue();
        }
    }

    private static String pick(Random random) {
        return NUMBERS.get(random.nextInt(NUMBERS.size()));
    }

    /** Whether {@code results.jsonl} gives back the record a run with this agent result and these tools wrote. */
    private boolean roundTrip(AgentResult agent, ToolStats tools) throws IOException {
        RunRecord record = new RunRecord("t", "c", "toolset", 1, "2026-11-15T00:00:00Z", true, 1000, false, 0, null,
                agent, tools, List.of(CheckResult.pass("file", "ok")));
        Path file = Files.createTempFile(dir, "results", ".jsonl");
        ResultsFile.append(file, record);
        return ResultsFile.read(file, w -> {
            throw new AssertionError(w);
        }).equals(List.of(record));
    }
}
