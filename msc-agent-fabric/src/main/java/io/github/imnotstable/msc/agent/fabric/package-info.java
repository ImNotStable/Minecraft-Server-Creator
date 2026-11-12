/**
 * The Fabric agent: a server-side mod that connects to msc-core over msc-protocol and carries out its operations inside
 * the server. It also runs on Quilt, which loads Fabric mods. {@link io.github.imnotstable.msc.agent.fabric.MscAgentMod}
 * is the entry point; the Minecraft side is the shared {@code io.github.imnotstable.msc.agent.vanilla} package (ADR 0027)
 * and the operations live in msc-agent-common.
 */
package io.github.imnotstable.msc.agent.fabric;
