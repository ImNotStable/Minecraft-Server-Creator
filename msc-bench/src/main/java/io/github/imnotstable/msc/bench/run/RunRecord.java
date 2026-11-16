package io.github.imnotstable.msc.bench.run;

import io.github.imnotstable.msc.bench.Text;
import io.github.imnotstable.msc.bench.check.CheckResult;
import java.util.ArrayList;
import java.util.List;

/**
 * One task attempt in one mode: a line of {@code results.jsonl}.
 *
 * @param task      task id
 * @param category  task category
 * @param mode      mode name, such as baseline or toolset
 * @param repeat    attempt number, from 1
 * @param startedAt ISO-8601 start time
 * @param passed    every check passed and the run had no error
 * @param wallMs    wall-clock time of the agent process; 0 when the agent never started
 * @param timedOut  the agent hit its time limit
 * @param exitCode  agent exit code, or -1 if it did not exit on its own
 * @param error     bench-side problem (setup, server), or null
 * @param agent     the agent's own usage report
 * @param tools     msc-core tool log totals
 * @param checks    every check's result
 */
public record RunRecord(
        String task,
        String category,
        String mode,
        int repeat,
        String startedAt,
        boolean passed,
        long wallMs,
        boolean timedOut,
        int exitCode,
        String error,
        AgentResult agent,
        ToolStats tools,
        List<CheckResult> checks) {

    public RunRecord {
        checks = checks == null ? List.of() : List.copyOf(checks);
        agent = agent == null ? AgentResult.UNKNOWN : agent;
        tools = tools == null ? ToolStats.NONE : tools;
    }

    /** This record marked failed with a bench-side error. */
    public RunRecord failed(String reason) {
        return new RunRecord(task, category, mode, repeat, startedAt, false, wallMs, timedOut, exitCode, reason,
                agent, tools, checks);
    }

    /**
     * Why this attempt failed, or null when it passed: the one text the terminal's progress line and the report's
     * Failures section both show, so they cannot differ. Lists the bench error, the timeout, how the agent ended
     * (non-zero exit, an error it reported, output the bench could not read, said only when it ran) and every failed
     * check. Each part is one line of at most {@link #MAX_REASON_PART} characters, with every hidden character escaped
     * ({@link Text#singleLine}, #677), so the terminal line stays one line and nothing in it is invisible; a failed
     * check's part is {@link CheckResult#shown()}, as {@code check} prints it, whose name is added outside that budget,
     * so a stored detail (already at most {@link CheckResult#MAX_DETAIL}) is shown whole, never cut a second time
     * (#535).
     */
    public String failureReason() {
        if (passed) {
            return null;
        }
        List<String> reasons = new ArrayList<>();
        if (error != null) {
            reasons.add(error);
        }
        if (timedOut) {
            reasons.add("timed out after " + wallMs / 1000 + "s");
        }
        if (exitCode > 0) {
            reasons.add("agent exited with code " + exitCode);
        }
        if (agent.isError()) {
            reasons.add("agent reported an error");
        }
        if (!agent.parsed() && agentRan()) {
            reasons.add("agent output not parsed");
        }
        List<String> parts = new ArrayList<>(reasons.stream().map(r -> Text.singleLine(r, MAX_REASON_PART)).toList());
        checks.stream().filter(c -> !c.passed()).forEach(c -> parts.add(c.shown()));
        return parts.isEmpty() ? "no checks ran" : String.join("; ", parts);
    }

    /**
     * The most chars one part of {@link #failureReason()} keeps, not counting a check's name; equal to
     * {@link CheckResult#MAX_DETAIL}, so a check's detail reads the same here as in results.jsonl.
     */
    static final int MAX_REASON_PART = CheckResult.MAX_DETAIL;

    /** Whether the agent process was started: a setup failure before it leaves {@code wallMs} at 0. */
    public boolean agentRan() {
        return wallMs > 0;
    }

    public long checksPassed() {
        return checks.stream().filter(CheckResult::passed).count();
    }
}
