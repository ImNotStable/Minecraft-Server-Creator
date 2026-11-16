package io.github.imnotstable.msc.agent.vanilla;

/**
 * The mod loader the agent runs on, as the core sees it.
 *
 * @param platform the platform id the core knows, for example {@code neoforge}
 * @param version  the loader's own version, for example {@code 26.3.0.102}
 */
public record Loader(String platform, String version) {
}
