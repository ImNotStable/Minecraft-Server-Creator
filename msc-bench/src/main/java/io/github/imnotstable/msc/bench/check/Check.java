package io.github.imnotstable.msc.bench.check;

/** One pass/fail check run against a finished workspace (and the server, if the check needs it). */
public interface Check {

    /** Short label used in results and reports. */
    String name();

    /** True when the check talks to the running server over RCON. */
    default boolean needsServer() {
        return false;
    }

    /** Runs the check. Implementations report problems as a failed result instead of throwing. */
    CheckResult run(CheckContext context);
}
