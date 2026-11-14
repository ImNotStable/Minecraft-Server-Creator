package io.github.imnotstable.msc.core.fixture;

/**
 * A process that just stays alive, standing in for a server an earlier core left running or for another session's
 * core: 120 seconds, or as many as its first argument says.
 */
public final class SleepMain {
    private SleepMain() {
    }

    public static void main(String[] args) throws Exception {
        Thread.sleep(args.length > 0 ? Long.parseLong(args[0]) * 1000 : 120_000);
    }
}
