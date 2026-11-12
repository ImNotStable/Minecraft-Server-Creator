/**
 * The agent code every mod-loader agent (Fabric, Quilt, NeoForge, Forge) shares: it adapts vanilla Minecraft's server
 * classes ({@code MinecraftServer}, {@code ServerLevel}) to msc-agent-common's operations. Minecraft 26.x ships
 * unobfuscated, so these sources compile unchanged against each loader's Minecraft; each agent module adds them as a
 * source folder and only writes its entry point, which forwards the loader's events to {@link
 * io.github.imnotstable.msc.agent.vanilla.VanillaAgent}. See ADR 0027.
 */
package io.github.imnotstable.msc.agent.vanilla;
