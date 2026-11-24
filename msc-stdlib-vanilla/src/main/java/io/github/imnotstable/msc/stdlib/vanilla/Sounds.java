package io.github.imnotstable.msc.stdlib.vanilla;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;

/** Playing a sound you chose to one player: {@code Sounds.play(player, "block.amethyst_block.chime", 0.8f, 1.3f)}. No stock sounds (ADR 0106). */
public final class Sounds {
    private Sounds() {}

    /** Heard only by {@code player}; {@code key} is a vanilla sound id without {@code minecraft:}. */
    public static void play(ServerPlayer player, String key, float volume, float pitch) {
        BuiltInRegistries.SOUND_EVENT.get(Identifier.withDefaultNamespace(key)).ifPresent(sound ->
                player.connection.send(new ClientboundSoundPacket(sound, SoundSource.MASTER, player.getX(), player.getY(),
                        player.getZ(), volume, pitch, player.getRandom().nextLong())));
    }
}
