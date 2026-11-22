package io.github.imnotstable.msc.stdlib.paper;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;

/**
 * Sound presets so every plugin sounds the same: {@code Sounds.SUCCESS.play(player)}. Any vanilla sound by key:
 * {@code Sounds.of("entity.blaze.shoot", 1, 1.2f)}.
 */
public enum Sounds {
    /** Menu clicks. */
    CLICK("ui.button.click", 0.5f, 1.2f),
    /** Something worked: a purchase, a reward. */
    SUCCESS("entity.player.levelup", 0.7f, 1.4f),
    /** Something was refused: not enough money, no permission. */
    FAIL("block.note_block.bass", 0.8f, 0.6f),
    /** A countdown tick. */
    TICK("block.note_block.hat", 0.8f, 1.6f),
    /** The countdown's end: go! */
    GO("block.note_block.pling", 1f, 2f),
    /** A teleport. */
    TELEPORT("entity.enderman.teleport", 0.7f, 1f),
    /** A notification or new message. */
    NOTIFY("block.note_block.chime", 0.8f, 1.2f),
    /** Winning a round. */
    VICTORY("ui.toast.challenge_complete", 0.8f, 1f),
    /** Losing or dying. */
    DEFEAT("entity.wither.death", 0.4f, 1.4f),
    /** Picking something up, a coin. */
    PICKUP("entity.item.pickup", 0.6f, 1.2f),
    /** Opening a menu. */
    OPEN("block.chest.open", 0.4f, 1.2f);

    private final Sound sound;

    Sounds(String key, float volume, float pitch) {
        this.sound = of(key, volume, pitch);
    }

    public Sound sound() {
        return sound;
    }

    /** Plays at the listener (a player, or {@code Bukkit.getServer()} for everyone). */
    public void play(Audience to) {
        to.playSound(sound, Sound.Emitter.self());
    }

    public static Sound of(String key, float volume, float pitch) {
        return Sound.sound(Key.key(key), Sound.Source.MASTER, volume, pitch);
    }
}
