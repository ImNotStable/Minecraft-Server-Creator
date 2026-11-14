package io.github.imnotstable.msc.core.tools;

import java.util.List;

/** The fault-injection seam of {@link Steps}, for tests in other packages. */
public final class StepsAccess {
    private StepsAccess() {
    }

    /** Make {@code step} fail on this thread before it runs; null: none. */
    public static void failAt(String step) {
        Steps.failAt(step);
    }

    /** Record the steps that begin on this thread, as {@code tool: step}. */
    public static List<String> trace() {
        return Steps.trace();
    }

    public static void stopTrace() {
        Steps.stopTrace();
    }
}
