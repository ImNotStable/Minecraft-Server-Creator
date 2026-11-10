/**
 * The wire protocol between msc-core and the agents that run inside a game server.
 *
 * <p>Transport: one TCP connection on the loopback interface, the agent connects to the core.
 * Each frame is one line of UTF-8 JSON ending in {@code \n}. The agent opens with
 * {@link io.github.imnotstable.msc.protocol.message.Hello}; the core answers with
 * {@link io.github.imnotstable.msc.protocol.message.Welcome} or
 * {@link io.github.imnotstable.msc.protocol.message.Goodbye}. After that either side may send
 * requests, responses, events and pings. Operations are listed in
 * {@link io.github.imnotstable.msc.protocol.op.Ops}.
 */
package io.github.imnotstable.msc.protocol;
