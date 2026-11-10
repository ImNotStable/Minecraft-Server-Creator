package io.github.imnotstable.msc.protocol.message;

import java.util.List;
import java.util.Objects;

/**
 * Describes the agent and the server it runs in.
 *
 * @param platform         platform id, for example {@code paper}, {@code fabric} or {@code minestom}
 * @param platformVersion  platform build, for example {@code 26.3.build.231-stable}
 * @param minecraftVersion game version, for example {@code 26.3}
 * @param operations       operation names this agent implements (see {@code Ops})
 */
public record AgentInfo(String platform, String platformVersion, String minecraftVersion, List<String> operations) {
    public AgentInfo {
        Objects.requireNonNull(platform, "platform");
        Objects.requireNonNull(platformVersion, "platformVersion");
        Objects.requireNonNull(minecraftVersion, "minecraftVersion");
        operations = operations == null ? List.of() : List.copyOf(operations);
    }
}
