package {{packageName}};

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

/** {@code /hello}: replies with a greeting. A Brigadier command tree, registered from the entry point. */
public final class HelloCommand {
    private HelloCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, Greeter greeter) {
        dispatcher.register(Commands.literal("hello").executes(context -> {
            CommandSourceStack source = context.getSource();
            source.sendSuccess(() -> greeter.hello(source.getTextName()), false);
            return 1;
        }));
    }
}
