package {{packageName}};

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;

/** {@code /hello}: a proxy command, answered by the proxy whichever backend the player is on. */
public final class HelloCommand implements SimpleCommand {
    private final Greeter greeter;

    public HelloCommand(Greeter greeter) {
        this.greeter = greeter;
    }

    @Override
    public void execute(Invocation invocation) {
        String name = invocation.source() instanceof Player player ? player.getUsername() : "console";
        invocation.source().sendMessage(greeter.hello(name));
    }
}
