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

    public static CheckResult pass(String name, String detail) {
        return new CheckResult(name, true, detail);
    }

    public static CheckResult fail(String name, String detail) {
        return new CheckResult(name, false, detail);
    }
}
