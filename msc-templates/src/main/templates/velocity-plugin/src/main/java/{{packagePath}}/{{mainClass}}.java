package {{packageName}};

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;

// Written out in full so no main class name can clash with it; becomes velocity-plugin.json at compile time.
@com.velocitypowered.api.plugin.Plugin(id = "{{projectId}}", name = "{{name}}", version = "{{version}}",
        description = "{{description}}")
public class {{mainClass}} {
    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
    }
}
