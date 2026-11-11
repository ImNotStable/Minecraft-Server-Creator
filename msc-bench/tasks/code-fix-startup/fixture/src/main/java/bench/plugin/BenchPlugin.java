package bench.plugin;

import org.bukkit.plugin.java.JavaPlugin;

public final class BenchPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        getCommand("stats").setExecutor(new StatsCommand(this));
        getLogger().info("BenchPlugin enabled");
    }
}
