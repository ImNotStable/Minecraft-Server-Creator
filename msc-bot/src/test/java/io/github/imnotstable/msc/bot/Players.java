package io.github.imnotstable.msc.bot;

import java.util.ArrayList;
import java.util.List;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerPositionPacket;

/** Test players with no connection, placed by a server teleport and driven tick by tick. */
final class Players {
    private Players() {
    }

    /** A player the server has placed at x,y,z, simulating against {@code world} (named "w"). */
    static TestPlayer at(BlockSource world, RoutePlanner planner, double x, double y, double z) {
        TestPlayer p = TestPlayer.offline("MscTester", world, planner, new ArrayList<Packet>());
        p.receive(new ClientboundPlayerPositionPacket(1, x, y, z, 0, 0, 0, 0, 0));
        p.useWorld("w");
        return p;
    }

    /** Start the step and tick until it finishes; null when it has not after {@code maxTicks}. */
    static String drive(TestPlayer p, Script.Step step, int maxTicks) {
        p.begin(step);
        for (int i = 0; i < maxTicks && step.resultNow() == null; i++) {
            p.tick();
        }
        return step.resultNow();
    }

    /** A planner that answers this route whatever is asked. */
    static RoutePlanner route(List<int[]> steps) {
        return (from, to) -> steps;
    }
}
