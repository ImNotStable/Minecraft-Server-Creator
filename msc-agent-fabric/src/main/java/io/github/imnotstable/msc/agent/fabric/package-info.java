/**
 * The Fabric agent: a server-side mod that connects to msc-core over msc-protocol and carries out
 * its operations inside the server. {@link io.github.imnotstable.msc.agent.fabric.MscAgentMod} is
 * the entry point; {@code WorldOps} and {@code ServerOps} hold the operations. Everything that
 * touches Minecraft's server classes sits behind {@link io.github.imnotstable.msc.agent.fabric.AgentLevel}
 * and {@link io.github.imnotstable.msc.agent.fabric.ServerAccess}, so the operations are unit tested
 * without a running server.
 */
package io.github.imnotstable.msc.agent.fabric;
