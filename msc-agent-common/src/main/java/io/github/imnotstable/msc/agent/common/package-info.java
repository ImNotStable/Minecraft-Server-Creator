/**
 * Code every msc agent shares: the operations ({@link io.github.imnotstable.msc.agent.common.WorldOps},
 * {@link io.github.imnotstable.msc.agent.common.ServerOps}), the sliced jobs, undo and the link to
 * msc-core. A platform plugs in through {@link io.github.imnotstable.msc.agent.common.AgentWorlds},
 * {@link io.github.imnotstable.msc.agent.common.AgentWorld}, {@link io.github.imnotstable.msc.agent.common.AgentServer}
 * and {@link io.github.imnotstable.msc.agent.common.MainThread}.
 */
package io.github.imnotstable.msc.agent.common;
