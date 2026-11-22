package {{packageName}};

import io.github.imnotstable.msc.stdlib.fabric.Stdlib;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Mod entry point: registers commands and event handlers, and holds no game logic itself. */
public final class {{mainClass}} implements DedicatedServerModInitializer {
    public static final Logger LOG = LoggerFactory.getLogger("{{projectId}}");

    @Override
    public void onInitializeServer() {
        Stdlib.init(); // msc-stdlib: its scheduler runs on the server tick
        Greeter greeter = new Greeter(Greeter.DEFAULT_JOIN, Greeter.DEFAULT_HELLO);

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                HelloCommand.register(dispatcher, greeter));
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                handler.getPlayer().sendSystemMessage(greeter.join(handler.getPlayer().getPlainTextName())));
        LOG.info("{{name}} loaded");
    }
}
