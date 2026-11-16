package {{packageName}};

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

/** Builds greeting messages from MiniMessage templates. Plain logic, so it is easy to test. */
public final class Greeter {
    static final String DEFAULT_JOIN = "<green>Welcome to the network, <player>!";
    static final String DEFAULT_HELLO = "<yellow>Hello, <player>!";

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    private final String joinTemplate;
    private final String helloTemplate;

    public Greeter(String joinTemplate, String helloTemplate) {
        this.joinTemplate = joinTemplate;
        this.helloTemplate = helloTemplate;
    }

    public Component join(String playerName) {
        return render(joinTemplate, playerName);
    }

    public Component hello(String playerName) {
        return render(helloTemplate, playerName);
    }

    private static Component render(String template, String playerName) {
        // unparsed: a player name is never interpreted as MiniMessage tags.
        return MINI_MESSAGE.deserialize(template, Placeholder.unparsed("player", playerName));
    }
}
