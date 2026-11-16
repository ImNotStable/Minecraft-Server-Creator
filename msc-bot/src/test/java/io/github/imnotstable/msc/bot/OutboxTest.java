package io.github.imnotstable.msc.bot;

import static org.assertj.core.api.Assertions.assertThat;

import org.cloudburstmc.math.vector.Vector3d;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.Hand;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundClientTickEndPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundAttackPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundInteractPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosRotPacket;
import org.junit.jupiter.api.Test;

class OutboxTest {
    private static final ServerboundClientTickEndPacket END = ServerboundClientTickEndPacket.INSTANCE;

    @Test
    void anAttackQueuedBeforeTheMoveGoesOutAfterIt() {
        // Paper 26.3 kicked the bot when interact sent its own move next to the tick's move.
        Outbox box = new Outbox();
        ServerboundAttackPacket attack = new ServerboundAttackPacket(7);
        ServerboundInteractPacket click = new ServerboundInteractPacket(7, Hand.MAIN_HAND, Vector3d.from(0, 0.9, 0),
                false);
        box.after(attack);
        box.after(click);
        ServerboundMovePlayerPosRotPacket move = move(90);
        box.move(move);
        assertThat(box.drain(END)).containsExactly(move, attack, click, END);
        assertThat(box.drain(END)).containsExactly(END);
    }

    @Test
    void aTickCarriesOnlyOnePosition() {
        Outbox box = new Outbox();
        box.move(move(0));
        ServerboundMovePlayerPosRotPacket last = move(45);
        box.move(last);
        assertThat(box.drain(END)).containsExactly(last, END);
    }

    private static ServerboundMovePlayerPosRotPacket move(float yaw) {
        return new ServerboundMovePlayerPosRotPacket(true, false, 0.5, -60, 0.5, yaw, 0);
    }
}
