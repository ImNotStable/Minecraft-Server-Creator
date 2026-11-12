package io.github.imnotstable.msc.core.fixture;

/** A process that just stays alive, standing in for a server an earlier core left running. */
public final class SleepMain {
    private SleepMain() {
    }

    public static void main(String[] args) throws Exception {
        Thread.sleep(120_000);
    }
}
