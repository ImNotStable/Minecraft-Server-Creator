package io.github.imnotstable.msc.bot;

import java.util.List;

/**
 * A joined test player. The core only talks to this interface, because a bot for an older protocol version is a
 * {@link TestPlayer} loaded in its own class loader (see {@link Bots}).
 */
public interface Bot extends AutoCloseable {
    String name();

    /** Still in the game: not kicked and the connection is open. */
    boolean connected();

    /** Why the player was disconnected, or null while connected. */
    String disconnectReason();

    /** Run script lines and report what happened, one line per step plus what the server said. */
    String run(List<String> lines, String world) throws InterruptedException;

    @Override
    void close();
}
