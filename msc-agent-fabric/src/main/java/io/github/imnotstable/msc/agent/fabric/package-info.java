/**
 * The Fabric agent: a server-side mod that connects to msc-core over msc-protocol and carries out
 * its operations inside the server. {@link io.github.imnotstable.msc.agent.fabric.MscAgentMod} is
 * the entry point. The operations live in msc-agent-common; this package adapts Minecraft's server
 * classes to them ({@code FabricLevels}, {@code FabricLevel}, {@code FabricServerAccess},
 * {@code TickMainThread}), so the operations are unit tested without a running server.
 */
package io.github.imnotstable.msc.agent.fabric;
