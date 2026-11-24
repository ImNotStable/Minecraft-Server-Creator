package io.github.imnotstable.msc.stdlib.ui;

import io.github.imnotstable.msc.stdlib.text.Text;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;

import java.time.Duration;

/**
 * Telling players what happened, in one call each: chat, action bar, title. Text is MiniMessage with placeholders as
 * in {@link Text#mm}, styled by you: there is no stock success or fail look (ADR 0106). Works for anything that is an Adventure {@code Audience}: a player, a world or instance, the server.
 *
 * <pre>{@code
 * Feedback.message(player, "Bought <item> for <price>", "item", "a sword", "price", Format.money(50, "$"));
 * Feedback.title(player, "<gold>Round 2", "<gray>Fight!", Duration.ofMillis(250), Duration.ofSeconds(2), Duration.ofMillis(500));
 * Feedback.actionBar(player, "<yellow>Coins: <c>", "c", Format.number(coins));
 * }</pre>
 */
public final class Feedback {
    /** Default title timing: quick fade in, 2 seconds, short fade out. */
    public static final Title.Times TIMES = Title.Times.times(Duration.ofMillis(250), Duration.ofSeconds(2),
            Duration.ofMillis(500));

    private Feedback() {}

    /** A chat line. */
    public static void message(Audience to, String miniMessage, Object... placeholders) {
        to.sendMessage(Text.mm(miniMessage, placeholders));
    }

    /** Text above the hotbar (lasts about 2 seconds; send again to keep it up). */
    public static void actionBar(Audience to, String miniMessage, Object... placeholders) {
        to.sendActionBar(Text.mm(miniMessage, placeholders));
    }

    /** A title and subtitle with {@link #TIMES}. */
    public static void title(Audience to, String title, String subtitle, Object... placeholders) {
        to.showTitle(Title.title(Text.mm(title, placeholders), Text.mm(subtitle, placeholders), TIMES));
    }

    /** A title and subtitle with your own fade in, stay and fade out. */
    public static void title(Audience to, String title, String subtitle, Duration fadeIn, Duration stay, Duration fadeOut) {
        to.showTitle(Title.title(Text.mm(title), Text.mm(subtitle), Title.Times.times(fadeIn, stay, fadeOut)));
    }

    /** Only a subtitle (the title line empty). */
    public static void subtitle(Audience to, String subtitle, Object... placeholders) {
        to.showTitle(Title.title(Component.empty(), Text.mm(subtitle, placeholders), TIMES));
    }

    public static void clearTitle(Audience to) {
        to.clearTitle();
    }
}
