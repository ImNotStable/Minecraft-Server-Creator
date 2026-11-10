package {{packageName}};

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;

/** {@code /hello}: replies with a greeting. Registered through Paper's Brigadier command API. */
public final class HelloCommand implements BasicCommand {
    private final Greeter greeter;

    public HelloCommand(Greeter greeter) {
        this.greeter = greeter;
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        sender.sendMessage(greeter.hello(sender.getName()));
    }
}
