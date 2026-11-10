package io.github.imnotstable.msc.agent.paper;

/** Version and performance facts, behind an interface because they come from Paper internals that tests lack. */
interface ServerFacts {
    String platformVersion();

    String minecraftVersion();

    /** Ticks per second over the last minute, or -1 when unknown. */
    double tps();
}
