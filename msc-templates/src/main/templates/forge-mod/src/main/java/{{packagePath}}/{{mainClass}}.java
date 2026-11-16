package {{packageName}};

import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Mod entry point: registers commands and event listeners, and holds no game logic itself. */
@Mod({{mainClass}}.MOD_ID)
public final class {{mainClass}} {
    public static final String MOD_ID = "{{modId}}";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    public {{mainClass}}() {
        Greeter greeter = new Greeter(Greeter.DEFAULT_JOIN, Greeter.DEFAULT_HELLO);

        RegisterCommandsEvent.BUS.addListener(e -> HelloCommand.register(e.getDispatcher(), greeter));
        PlayerEvent.PlayerLoggedInEvent.BUS.addListener(e ->
                e.getEntity().sendSystemMessage(greeter.join(e.getEntity().getPlainTextName())));
        LOG.info("{{name}} loaded");
    }
}
