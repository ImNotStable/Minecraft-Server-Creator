package io.github.imnotstable.msc.stdlib.ui;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;

/**
 * Playing a sound you chose: {@code Sounds.play(player, Sounds.of("block.amethyst_block.chime", 0.8f, 1.3f))}. There are
 * no stock sounds: pick each one for your game (ADR 0106).
 */
public final class Sounds {
    private Sounds() {}

    public static Sound of(String key, float volume, float pitch) {
        return Sound.sound(Key.key(key), Sound.Source.MASTER, volume, pitch);
    }

    /** Heard by {@code to} only, where they stand. */
    public static void play(Audience to, Sound sound) {
        to.playSound(sound, Sound.Emitter.self());
    }
}
