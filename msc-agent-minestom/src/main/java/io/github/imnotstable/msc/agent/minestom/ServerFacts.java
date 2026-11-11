package io.github.imnotstable.msc.agent.minestom;

/** Version and performance facts, behind an interface because they depend on the running server. */
interface ServerFacts {
    String platformVersion();

    String minecraftVersion();

    /** Ticks per second over the last minute, or -1 when unknown. */
    double tps();
}
