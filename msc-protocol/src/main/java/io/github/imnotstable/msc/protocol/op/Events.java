package io.github.imnotstable.msc.protocol.op;

/** Event names an agent may send. Data shapes are noted per event. */
public final class Events {
    /** Server finished starting and the agent can take operations. Data: {}. */
    public static final String SERVER_READY = "server.ready";

    /** Server is stopping. Data: {}. */
    public static final String SERVER_STOPPING = "server.stopping";

    /** A player joined. Data: {@code {"name": "...", "uuid": "..."}}. */
    public static final String PLAYER_JOIN = "player.join";

    /** A player left. Data: {@code {"name": "...", "uuid": "..."}}. */
    public static final String PLAYER_QUIT = "player.quit";

    private Events() {
    }
}
