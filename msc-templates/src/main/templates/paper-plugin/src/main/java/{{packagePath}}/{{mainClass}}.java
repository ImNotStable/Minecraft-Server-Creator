package {{packageName}};

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.plugin.java.JavaPlugin;

/** Plugin entry point: wires config, listeners and commands, and holds no game logic itself. */
public final class {{mainClass}} extends JavaPlugin {
    @Override
    public void onEnable() {
        saveDefaultConfig();
        Greeter greeter = new Greeter(
                getConfig().getString("join-message", Greeter.DEFAULT_JOIN),
                getConfig().getString("hello-message", Greeter.DEFAULT_HELLO));

        getServer().getPluginManager().registerEvents(new JoinListener(greeter), this);
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
                event.registrar().register("hello", "Says hello to you.", new HelloCommand(greeter)));
    }
}
