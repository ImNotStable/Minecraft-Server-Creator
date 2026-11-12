package io.github.imnotstable.msc.bot;

import java.util.ArrayList;
import java.util.List;
import org.geysermc.mcprotocollib.network.packet.Packet;

/**
 * What the test player sends in one client tick, in the order a vanilla client sends it: one move packet, then the
 * clicks and attacks made this tick, then the tick end. A vanilla server (26.3) kicks with "invalid_player_movement"
 * when a second position arrives before the tick end, so actions never send their own move: they turn the player and
 * queue their packet here, and it goes out after the move that carries the new facing.
 */
final class Outbox {
    private Packet move;
    private final List<Packet> after = new ArrayList<>();

    /** This tick's move; a later one replaces it, so there is never more than one. */
    synchronized void move(Packet packet) {
        move = packet;
    }

    /** A packet to send after this tick's move, such as an attack aimed with the new facing. */
    synchronized void after(Packet packet) {
        after.add(packet);
    }

    /** The tick's packets in sending order, ending with {@code tickEnd}; empties the box. */
    synchronized List<Packet> drain(Packet tickEnd) {
        List<Packet> out = new ArrayList<>(after.size() + 2);
        if (move != null) {
            out.add(move);
        }
        out.addAll(after);
        out.add(tickEnd);
        move = null;
        after.clear();
        return out;
    }
}
