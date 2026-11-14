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

    private static final int MAX_DETAIL = 300;

    public CheckResult {
        detail = detail == null ? "" : detail.strip();
        detail = Text.headWithEllipsis(detail, MAX_DETAIL);
    }

    public static CheckResult pass(String name, String detail) {
        return new CheckResult(name, true, detail);
    }

    public static CheckResult fail(String name, String detail) {
        return new CheckResult(name, false, detail);
    }
}
