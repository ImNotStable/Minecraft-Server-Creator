package io.github.imnotstable.msc.bench;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.bench.run.AgentResult;
import io.github.imnotstable.msc.bench.run.ClaudeResultParser;
import io.github.imnotstable.msc.bench.run.ToolLogReader;
import io.github.imnotstable.msc.bench.run.ToolStats;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ResultParsingTest {

    @Test
    void readsClaudeJsonResult() {
        AgentResult r = ClaudeResultParser.parse("""
                {"type":"result","subtype":"success","is_error":false,"num_turns":7,"duration_api_ms":4000,
                 "total_cost_usd":0.42,"usage":{"input_tokens":10,"cache_creation_input_tokens":20,
                 "cache_read_input_tokens":30,"output_tokens":40},"result":"done"}
                """);

        assertThat(r.parsed()).isTrue();
        assertThat(r.turns()).isEqualTo(7);
        assertThat(r.totalTokens()).isEqualTo(100);
        assertThat(r.costUsd()).isEqualTo(0.42);
    }

    @Test
    void readsTheLastResultOfStreamJson() {
        AgentResult r = ClaudeResultParser.parse("""
                {"type":"system","subtype":"init"}
                {"type":"assistant","message":{}}
                {"type":"result","is_error":true,"num_turns":2,"usage":{"output_tokens":5}}
                """);

        assertThat(r.isError()).isTrue();
        assertThat(r.turns()).isEqualTo(2);
        assertThat(r.outputTokens()).isEqualTo(5);
    }

    @Test
    void unknownOutputGivesZeros() {
        assertThat(ClaudeResultParser.parse("Error: not logged in")).isEqualTo(AgentResult.UNKNOWN);
        assertThat(ClaudeResultParser.parse("")).isEqualTo(AgentResult.UNKNOWN);
    }

    @Test
    void sumsTheToolLogAndSkipsBrokenLines(@TempDir Path dir) throws IOException {
        Path log = dir.resolve("tool-calls.jsonl");
        Files.writeString(log, """
                {"v":1,"ts":"2026-11-10T13:00:00Z","tool":"world.slice","ok":true,"durationMs":12,"argsChars":30,"resultChars":400,"resultTokens":100}
                {"v":1,"tool":"server.start","ok":false,"durationMs":3000,"resultChars":41,"error":"no jar"}
                {"v":1,"tool":"world_render","ok":true,"durationMs":0,"resultChars":0,"resultTokens":0,"imageTokens":1500}
                {"v":1,"tool":"half a li
                {"v":2,"tool":"future","resultTokens":999}
                """);

        assertThat(ToolLogReader.read(log)).isEqualTo(new ToolStats(3, 1, 111, 1500, 441, 3012));
        assertThat(ToolLogReader.read(dir.resolve("missing.jsonl"))).isEqualTo(ToolStats.NONE);
    }
}
