package io.github.imnotstable.msc.bench.check;

import io.github.imnotstable.msc.bench.Text;

/**
 * Outcome of one check.
 *
 * @param name   the check's label
 * @param passed whether it passed
 * @param detail one short line saying why, kept small so reports stay readable
 */
public record CheckResult(String name, boolean passed, String detail) {

    /**
     * The most chars a detail keeps. A producer that quotes output sizes its quote so its whole detail fits, so this
     * cut ({@link Text#cut}, head and tail kept) only catches a detail that did not (#535).
     */
    public static final int MAX_DETAIL = 300;

    public CheckResult {
        detail = detail == null ? "" : detail.strip();
        detail = Text.cut(detail, MAX_DETAIL);
    }

    /**
     * This result as a reader is shown it, {@code name: detail}, the same in {@code run}'s progress line, report.md's
     * Failures and {@code check}'s output (#677): each on one line with every hidden character escaped
     * ({@link Text#singleLine}), and each at most {@link #MAX_DETAIL} characters, so a stored detail is shown whole.
     * {@code results.jsonl} keeps the detail as it is.
     */
    public String shown() {
        return Text.singleLine(name == null ? "" : name, MAX_DETAIL) + ": " + Text.singleLine(detail, MAX_DETAIL);
    }

    public static CheckResult pass(String name, String detail) {
        return new CheckResult(name, true, detail);
    }

    public static CheckResult fail(String name, String detail) {
        return new CheckResult(name, false, detail);
    }
}
