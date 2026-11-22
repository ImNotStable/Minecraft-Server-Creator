package io.github.imnotstable.msc.stdlib.vanilla;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.level.ServerPlayer;

import java.time.Duration;

/**
 * Telling a player what happened, with a matching sound: chat lines, action bar, titles. Text is plain or a
 * {@link Component} you colour yourself ({@code ItemBuilder.text("Hi", ChatFormatting.GOLD)}).
 *
 * <pre>{@code
 * Feedback.success(player, "Bought a sword");
 * Feedback.title(player, ItemBuilder.text("Round 2", ChatFormatting.GOLD), Component.literal("Fight!"));
 * }</pre>
 */
public final class Feedback {
    private Feedback() {}

    /** A green ✔ line and the success sound. */
    public static void success(ServerPlayer player, String text) {
        player.sendSystemMessage(Component.literal("✔ " + text).withStyle(ChatFormatting.GREEN));
        Sounds.SUCCESS.play(player);
    }

    /** A red ✘ line and the fail sound. */
    public static void fail(ServerPlayer player, String text) {
        player.sendSystemMessage(Component.literal("✘ " + text).withStyle(ChatFormatting.RED));
        Sounds.FAIL.play(player);
    }

    /** A grey » line and the notify sound. */
    public static void info(ServerPlayer player, String text) {
        player.sendSystemMessage(Component.literal("» ").withStyle(ChatFormatting.GRAY).append(Component.literal(text)));
        Sounds.NOTIFY.play(player);
    }

    public static void actionBar(ServerPlayer player, Component text) {
        player.connection.send(new ClientboundSetActionBarTextPacket(text));
    }

    public static void actionBar(ServerPlayer player, String text) {
        actionBar(player, Component.literal(text));
    }

    /** Title and subtitle: quick fade in, 2 seconds, short fade out. */
    public static void title(ServerPlayer player, Component title, Component subtitle) {
        title(player, title, subtitle, Duration.ofMillis(250), Duration.ofSeconds(2), Duration.ofMillis(500));
    }

    public static void title(ServerPlayer player, Component title, Component subtitle, Duration fadeIn, Duration stay,
                             Duration fadeOut) {
        player.connection.send(new ClientboundSetTitlesAnimationPacket((int) (fadeIn.toMillis() / 50),
                (int) (stay.toMillis() / 50), (int) (fadeOut.toMillis() / 50)));
        player.connection.send(new ClientboundSetSubtitleTextPacket(subtitle));
        player.connection.send(new ClientboundSetTitleTextPacket(title));
    }
}
