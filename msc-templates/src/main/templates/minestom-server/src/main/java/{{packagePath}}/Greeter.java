package {{packageName}};

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/** Builds greeting messages. Plain logic, so it is easy to test. */
public final class Greeter {
    public Component hello(String name) {
        return Component.text("Hello, " + name + "!", NamedTextColor.YELLOW);
    }
}
