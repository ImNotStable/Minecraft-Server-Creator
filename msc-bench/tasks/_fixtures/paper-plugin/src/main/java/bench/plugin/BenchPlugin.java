package bench.plugin;

import org.bukkit.plugin.java.JavaPlugin;

public final class BenchPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        getLogger().info("BenchPlugin enabled");
    }
}
