package io.github.imnotstable.msc.stdlib.vanilla;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;

/** Sound presets so every mod sounds the same: {@code Sounds.SUCCESS.play(player)}; any sound with {@link #play(ServerPlayer, String, float, float)}. */
public enum Sounds {
    CLICK("ui.button.click", 0.5f, 1.2f),
    SUCCESS("entity.player.levelup", 0.7f, 1.4f),
    FAIL("block.note_block.bass", 0.8f, 0.6f),
    TICK("block.note_block.hat", 0.8f, 1.6f),
    GO("block.note_block.pling", 1f, 2f),
    TELEPORT("entity.enderman.teleport", 0.7f, 1f),
    NOTIFY("block.note_block.chime", 0.8f, 1.2f),
    VICTORY("ui.toast.challenge_complete", 0.8f, 1f),
    DEFEAT("entity.wither.death", 0.4f, 1.4f),
    PICKUP("entity.item.pickup", 0.6f, 1.2f),
    OPEN("block.chest.open", 0.4f, 1.2f);

    private final String key;
    private final float volume;
    private final float pitch;

    Sounds(String key, float volume, float pitch) {
        this.key = key;
        this.volume = volume;
        this.pitch = pitch;
    }

    /** The vanilla sound id, without {@code minecraft:}. */
    public String key() {
        return key;
    }

    /** Heard only by {@code player}. */
    public void play(ServerPlayer player) {
        play(player, key, volume, pitch);
    }

    public static void play(ServerPlayer player, String key, float volume, float pitch) {
        BuiltInRegistries.SOUND_EVENT.get(Identifier.withDefaultNamespace(key)).ifPresent(sound ->
                player.connection.send(new ClientboundSoundPacket(sound, SoundSource.MASTER, player.getX(), player.getY(),
                        player.getZ(), volume, pitch, player.getRandom().nextLong())));
    }
}
