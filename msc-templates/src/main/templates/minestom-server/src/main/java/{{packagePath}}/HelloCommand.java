package {{packageName}};

import net.minestom.server.command.builder.Command;
import net.minestom.server.entity.Player;

/** {@code /hello}: replies with a greeting. */
public final class HelloCommand extends Command {
    public HelloCommand(Greeter greeter) {
        super("hello");
        setDefaultExecutor((sender, context) ->
                sender.sendMessage(greeter.hello(sender instanceof Player p ? p.getUsername() : "console")));
    }
}
