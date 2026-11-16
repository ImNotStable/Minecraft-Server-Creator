package {{packageName}};

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PostLoginEvent;

/** Greets each player once, when they log in to the proxy (not on every backend switch). */
public final class JoinListener {
    private final Greeter greeter;

    public JoinListener(Greeter greeter) {
        this.greeter = greeter;
    }

    @Subscribe
    public void onLogin(PostLoginEvent event) {
        event.getPlayer().sendMessage(greeter.join(event.getPlayer().getUsername()));
    }
}
