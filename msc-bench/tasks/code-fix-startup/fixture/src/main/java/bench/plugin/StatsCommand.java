package bench.plugin;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;

final class StatsCommand implements CommandExecutor {

    private final Plugin plugin;

    StatsCommand(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // Skip the main world: it is always loaded.
        int worlds = plugin.getServer().getWorlds().size() - 1;
        sender.sendMessage("Loaded worlds: " + worlds);
        return true;
    }
}
