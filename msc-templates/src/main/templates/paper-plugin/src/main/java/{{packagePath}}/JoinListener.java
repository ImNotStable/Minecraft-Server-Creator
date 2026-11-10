package {{packageName}};

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/** Greets each player when they join. */
public final class JoinListener implements Listener {
    private final Greeter greeter;

    public JoinListener(Greeter greeter) {
        this.greeter = greeter;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        event.getPlayer().sendMessage(greeter.join(event.getPlayer().getName()));
    }
}
