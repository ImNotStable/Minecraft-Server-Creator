package {{packageName}};

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.proxy.ProxyServer;
import io.github.imnotstable.msc.stdlib.velocity.Stdlib;
import org.slf4j.Logger;

/**
 * Plugin entry point: wires listeners and commands, and holds no logic itself. Velocity builds it with Guice, so its
 * constructor takes what it needs. The annotation (written out in full, so no main class name can clash with it)
 * becomes velocity-plugin.json at compile time.
 */
@com.velocitypowered.api.plugin.Plugin(id = "{{projectId}}", name = "{{name}}", version = "{{version}}",
        description = "{{description}}")
public final class {{mainClass}} {
    private final ProxyServer proxy;
    private final Logger logger;

    @Inject
    public {{mainClass}}(ProxyServer proxy, Logger logger) {
        this.proxy = proxy;
        this.logger = logger;
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        Stdlib.init(this, proxy); // msc-stdlib: its scheduler, player data and languages need it
        Greeter greeter = new Greeter(Greeter.DEFAULT_JOIN, Greeter.DEFAULT_HELLO);
        proxy.getEventManager().register(this, new JoinListener(greeter));
        CommandManager commands = proxy.getCommandManager();
        commands.register(commands.metaBuilder("hello").plugin(this).build(), new HelloCommand(greeter));
        logger.info("{{name}} enabled");
    }
}
