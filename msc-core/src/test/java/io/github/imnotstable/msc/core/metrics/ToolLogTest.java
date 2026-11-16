package io.github.imnotstable.msc.core.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.imnotstable.msc.core.tool.Schema;
import io.github.imnotstable.msc.core.tool.ToolException;
import io.github.imnotstable.msc.core.tool.ToolRegistry;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Every tool call reaches the tool log, whatever its error text holds (#465). */
class ToolLogTest {
    @TempDir
    Path dir;

    @Test
    void aFailedCallWithAnEmojiAtTheCutBoundaryStillGetsItsLine() throws Exception {
        Path file = dir.resolve("tools.jsonl");
        ToolRegistry registry = new ToolRegistry(new ToolLog(file));
        String[] message = {""};
        registry.add("fail", "Fails.", Schema.of(), args -> {
            throw new ToolException(message[0]);
        });
        // Slide an emoji over every position the 200-char cut can land on, with and without its marker.
        int calls = 0;
        for (int pad = 150; pad <= 210; pad++) {
            message[0] = "x".repeat(pad) + "😀".repeat(40);
            assertThat(registry.call("fail", new JsonObject()).ok()).isFalse();
            calls++;
        }
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertThat(lines).hasSize(calls);
        for (String line : lines) {
            String error = JsonParser.parseString(line).getAsJsonObject().get("error").getAsString();
            assertThat(error.length()).isLessThanOrEqualTo(ToolLog.MAX_ERROR_CHARS);
            assertThat(error).startsWith("error: xxx").endsWith("more chars]").doesNotContain("?");
        }
    }

    @Test
    void aWriteFailureIsReportedOnceAndNeverFailsTheCall() throws Exception {
        Path notADir = Files.writeString(dir.resolve("file"), "");
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        ToolLog log = new ToolLog(notADir.resolve("tools.jsonl"), new PrintStream(err, true, StandardCharsets.UTF_8));
        log.record("a", false, 1, 2, 3, "boom", 0);
        log.record("b", true, 1, 2, 3, null, 0);
        assertThat(err.toString(StandardCharsets.UTF_8).lines().toList()).singleElement().asString()
                .contains("could not write the tool log");
        assertThat(log.summary()).contains("total 2 1");
    }
}
