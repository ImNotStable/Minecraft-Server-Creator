package io.github.imnotstable.msc.bench.run;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.bench.Text;
import io.github.imnotstable.msc.bench.check.CheckResult;
import io.github.imnotstable.msc.bench.report.ReportWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** A failure reason is one bounded line on the terminal and one list item in report.md (#529). */
class FailureReasonLineTest {

    private static final AgentResult OK = new AgentResult(true, false, 3, 1000, 10, 0, 0, 5, 0.1, "m", List.of());
    private static final String[] PIECES = {"\n", "\r\n", "\r", "\t", " ", "\u0085", "\u0000", "* Try:", "> Run",
        "| a | b |", "- item", "1. item", "# head", "`code`", "[x](y)", "<b>", "\\", "_", "BUILD FAILED", " ", "x"};

    private static String detail(Random random) {
        StringBuilder text = new StringBuilder();
        int length = random.nextInt(4) == 0 ? 10_000 : random.nextInt(400);
        while (text.length() < length) {
            text.append(PIECES[random.nextInt(PIECES.length)]);
        }
        return text.toString();
    }

    private static RunRecord record(int repeat, List<CheckResult> checks, String error) {
        return new RunRecord("t", "c", "b", repeat, "2026-01-01T00:00:00Z", false, 5000, false, 1, error, OK,
                ToolStats.NONE, checks);
    }

    @Test
    void arbitraryDetailsStayOneLineAndOneListItem() {
        Random random = new Random(529);
        for (int round = 0; round < 200; round++) {
            List<RunRecord> records = new ArrayList<>();
            int attempts = 1 + random.nextInt(4);
            for (int i = 1; i <= attempts; i++) {
                List<CheckResult> checks = List.of(CheckResult.fail("build", detail(random)),
                        CheckResult.fail("player", detail(random)));
                records.add(record(i, checks, random.nextBoolean() ? detail(random) : null));
            }
            for (RunRecord r : records) {
                String line = BenchRunner.progressLine(r);
                assertThat(line).doesNotContainPattern("[\\r\\n\\u0085\\u2028\\u2029\\p{Cntrl}]");
                // each part keeps at most 300 characters, and an escape stands for one in at most 6
                assertThat(line.length()).isLessThan(6 * 1200);
            }
            String md = ReportWriter.render(records);
            String failures = md.substring(md.indexOf("## Failures\n\n") + "## Failures\n\n".length());
            List<String> lines = failures.lines().toList();
            assertThat(lines).hasSize(attempts).allMatch(l -> l.startsWith("- t (b #"));
        }
    }

    @Test
    void longPartIsCutWithAVisibleMark() {
        RunRecord r = record(1, List.of(CheckResult.fail("build", "exit 1:\n" + "y".repeat(10_000))), null);

        String reason = r.failureReason();
        assertThat(reason).startsWith("agent exited with code 1; build: exit 1:\\nyyy").contains("y" + Text.CUT_MARK + "y")
                .endsWith("yyy");
        assertThat(reason.length()).isLessThan(2 * RunRecord.MAX_REASON_PART);
    }

    @Test
    void markdownInReasonIsEscaped() {
        String md = ReportWriter.render(List.of(record(1, List.of(CheckResult.fail("build", "* Try:\n> Run | x")), null)));

        // the line break is shown as backslash n, whose backslash Markdown needs escaped
        assertThat(md).contains("- t (b #1): agent exited with code 1; build: \\* Try:\\\\n\\> Run \\| x\n");
    }

    @Test
    void setupFailureDoesNotBlameAgentOutput() {
        RunRecord setup = new RunRecord("t", "c", "b", 1, "2026-01-01T00:00:00Z", false, 0, false, -1,
                "starting the server: server did not open RCON", AgentResult.UNKNOWN, ToolStats.NONE, List.of());
        RunRecord ran = new RunRecord("t", "c", "b", 1, "2026-01-01T00:00:00Z", false, 1, false, 0, null,
                AgentResult.UNKNOWN, ToolStats.NONE, List.of());

        assertThat(setup.agentRan()).isFalse();
        assertThat(setup.failureReason()).isEqualTo("starting the server: server did not open RCON");
        assertThat(ran.failureReason()).isEqualTo("agent output not parsed");
    }
}
