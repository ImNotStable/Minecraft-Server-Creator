package io.github.imnotstable.msc.agent.fabric;

import java.util.List;
import java.util.Optional;

/** The server's worlds by name. */
interface Levels {
    /** Every world's name, overworld first. */
    List<String> names();

    Optional<AgentLevel> byName(String name);
}
