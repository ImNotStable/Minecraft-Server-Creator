package io.github.imnotstable.msc.bench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.imnotstable.msc.bench.run.BenchConfig;
import io.github.imnotstable.msc.bench.task.TaskLoader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * #601: every JSON file a person writes for the bench goes through {@link StrictJson}: a key given twice, at any depth,
 * and text that is not strict JSON are refused naming the file and the key or place.
 */
class StrictJsonTest {

    private static final String CHECK = "{\"type\":\"file\",\"path\":\"a\"}";

    @TempDir
    Path dir;

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            checks twice        | {"category":"c","title":"t","checks":[{"type":"file","path":"a","contains":"x"}],"checks":[{"type":"file","path":"b"}]} | Task t1: task.json checks: given twice
            timeout twice       | {"category":"c","title":"t","timeoutSeconds":60,"timeoutSeconds":6000,"checks":[CHECK]} | Task t1: task.json timeoutSeconds: given twice
            contains twice      | {"category":"c","title":"t","checks":[{"type":"file","path":"a","contains":"needle","contains":""}]} | Task t1: task.json checks[0].contains: given twice
            in the second check | {"category":"c","title":"t","checks":[CHECK,{"type":"file","path":"a","path":"b"}]} | Task t1: task.json checks[1].path: given twice
            in server           | {"category":"c","title":"t","server":{"startBefore":true,"startBefore":false},"checks":[CHECK]} | Task t1: task.json server.startBefore: given twice
            in a nested object  | {"category":"c","title":"t","checks":[{"type":"png","path":"a.png","hue":{"from":1,"from":2,"to":3,"minFraction":0.5}}]} | Task t1: task.json checks[0].hue.from: given twice
            unquoted, comment   | {category:c, title:t, // comment\\n checks:[CHECK]} | Task t1: task.json is not valid JSON: unexpected text at line 1 column 3
            comment             | {"category":"c","title":"t", /* x */ "checks":[CHECK]} | Task t1: task.json is not valid JSON:
            single quotes       | {'category':'c','title':'t','checks':[CHECK]} | Task t1: task.json is not valid JSON:
            trailing comma      | {"category":"c","title":"t","checks":[CHECK,]} | Task t1: task.json is not valid JSON:
            two values          | {"category":"c","title":"t","checks":[CHECK]} {} | Task t1: task.json is not valid JSON: unexpected text at column
            NaN                 | {"category":"c","title":"t","timeoutSeconds":NaN,"checks":[CHECK]} | Task t1: task.json is not valid JSON:
            a list              | [CHECK] | Task t1: task.json is not a JSON object
            """)
    void taskJsonIsReadStrictly(String name, String json, String refusal) throws IOException {
        Path task = Files.createDirectories(dir.resolve("t1"));
        Files.writeString(task.resolve("task.json"), json.replace("CHECK", CHECK).replace("\\n", "\n"));
        Files.writeString(task.resolve("prompt.md"), "p");

        assertThat(catchThrowable(() -> TaskLoader.load(task))).isInstanceOf(IOException.class)
                .hasMessageStartingWith(refusal);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            server twice       | {"server":{"serverPort":25566},"server":{"rconPort":25576}} | : server: given twice
            in agent           | {"agent":{"timeoutSeconds":60,"timeoutSeconds":6000}} | : agent.timeoutSeconds: given twice
            in a mode's list   | {"modes":{"m":{"extraArgs":["a"],"env":{"A":"1","A":"2"}}}} | : modes.m.env.A: given twice
            unquoted           | {agent: {timeoutSeconds: 60}} | : is not valid JSON: unexpected text at column 3
            comment            | {"agent":{"timeoutSeconds":60}} // x | : is not valid JSON:
            lone surrogate     | {"server":{"rconPassword":"a\\uD800b"}} | : is not valid JSON: Unpaired surrogate
            """)
    void benchJsonIsReadStrictly(String name, String json, String refusal) throws IOException {
        Path config = dir.resolve("bench.json");
        Files.writeString(config, json);

        assertThat(catchThrowable(() -> BenchConfig.load(config, dir))).isInstanceOf(IOException.class)
                .hasMessageStartingWith("Bad bench config " + config + refusal);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            env twice | {"mcpServers":{"msc":{"env":{"T":"{coreToken}","T":"x"}}}} | mcp.json mcpServers.msc.env.T: given twice
            comment   | {"mcpServers":{}} // x                                     | mcp.json is not valid JSON:
            """)
    void aSelectedModesMcpTemplateIsReadStrictlyAtRun(String name, String json, String refusal) throws IOException {
        Files.writeString(dir.resolve("mcp.json"), json);
        Path config = dir.resolve("bench.json");
        Files.writeString(config, "{\"modes\":{\"m\":{\"mcpConfigTemplate\":\"mcp.json\"}}}");
        BenchConfig loaded = BenchConfig.load(config, dir);

        assertThat(catchThrowable(() -> loaded.requirePaths(config, List.of("m")))).isInstanceOf(IOException.class)
                .hasMessageContaining("modes.m.mcpConfigTemplate: ")
                .hasMessageContaining(refusal);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            a key in two objects  | {"a":{"k":1},"b":{"k":2},"k":[{"k":3},{"k":4}]}
            a byte-order mark     | ﻿{"a":1}
            spaces and line ends  | \\n { "a" : [ 1 , 2 ] }\\n
            """)
    void strictJsonIsTaken(String name, String json) {
        assertThat(StrictJson.parse(json.replace("\\n", "\n"), "f ").isJsonObject()).isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            twice on one line      | {"a":1,"a":1}                  | f a: given twice; give each key once
            twice in a list        | {"l":[[{"x":1}],[{"x":1,"x":2}]]} | f l[1][0].x: given twice; give each key once
            a syntax error, line 2 | {"a":1,\\n "b":}               | f is not valid JSON: Expected value at line 2 column 6 (in b)
            nothing                | ''                             | f is not valid JSON: End of input at column 1
            """)
    void strictJsonRefusalsNameThePlace(String name, String json, String refusal) {
        assertThat(catchThrowable(() -> StrictJson.parse(json.replace("\\n", "\n"), "f ")))
                .isInstanceOf(IllegalArgumentException.class).hasMessage(refusal);
    }
}
