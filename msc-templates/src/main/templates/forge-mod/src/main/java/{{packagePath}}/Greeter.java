package {{packageName}};

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

/** Builds greeting messages. Plain logic, so it is easy to test. */
public final class Greeter {
    static final String DEFAULT_JOIN = "Welcome, {player}!";
    static final String DEFAULT_HELLO = "Hello, {player}!";

    private final String joinTemplate;
    private final String helloTemplate;

    public Greeter(String joinTemplate, String helloTemplate) {
        this.joinTemplate = joinTemplate;
        this.helloTemplate = helloTemplate;
    }

    public Component join(String playerName) {
        return Component.literal(fill(joinTemplate, playerName)).withStyle(ChatFormatting.GREEN);
    }

    public Component hello(String playerName) {
        return Component.literal(fill(helloTemplate, playerName)).withStyle(ChatFormatting.YELLOW);
    }

    /** Component.literal never interprets formatting, so a player name is always shown as typed. */
    static String fill(String template, String playerName) {
        return template.replace("{player}", playerName);
    }
}
