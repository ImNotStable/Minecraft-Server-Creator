package {{packageName}};

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Mod entry point: registers commands and event handlers, and holds no game logic itself. */
@Mod(value = {{mainClass}}.MOD_ID, dist = Dist.DEDICATED_SERVER)
public final class {{mainClass}} {
    public static final String MOD_ID = "{{modId}}";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    public {{mainClass}}() {
        Greeter greeter = new Greeter(Greeter.DEFAULT_JOIN, Greeter.DEFAULT_HELLO);

        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent.class, e -> HelloCommand.register(e.getDispatcher(), greeter));
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedInEvent.class, e ->
                e.getEntity().sendSystemMessage(greeter.join(e.getEntity().getPlainTextName())));
        LOG.info("{{name}} loaded");
    }
}
