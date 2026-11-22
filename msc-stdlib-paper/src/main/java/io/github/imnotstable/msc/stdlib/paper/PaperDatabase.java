package io.github.imnotstable.msc.stdlib.paper;

import io.github.imnotstable.msc.stdlib.db.DatabaseConfig;
import io.github.imnotstable.msc.stdlib.db.DatabaseType;
import org.bukkit.configuration.ConfigurationSection;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * A {@link DatabaseConfig} from a config.yml section, so server owners pick the backend:
 *
 * <pre>{@code
 * database:
 *   type: sqlite          # sqlite, mysql, mariadb or postgresql
 *   file: data.db         # sqlite: relative to the plugin folder
 *   host: localhost
 *   port: 0               # 0: the backend's default
 *   database: minecraft
 *   user: root
 *   password: ""
 *   pool-size: 10
 *   properties: {}        # driver properties
 * }</pre>
 *
 * {@code Database db = Database.open(PaperDatabase.config(getConfig().getConfigurationSection("database"), getDataPath()));}
 */
public final class PaperDatabase {
    private PaperDatabase() {}

    /** A missing section or key falls back to SQLite in {@code dataFolder/data.db}. */
    public static DatabaseConfig config(ConfigurationSection s, Path dataFolder) {
        if (s == null) {
            return DatabaseConfig.sqlite(dataFolder.resolve("data.db"));
        }
        DatabaseType type = DatabaseType.valueOf(s.getString("type", "sqlite").toUpperCase(Locale.ROOT));
        Map<String, String> props = new HashMap<>();
        ConfigurationSection p = s.getConfigurationSection("properties");
        if (p != null) {
            p.getKeys(false).forEach(k -> props.put(k, String.valueOf(p.get(k))));
        }
        return new DatabaseConfig(type, dataFolder.resolve(s.getString("file", "data.db")), s.getString("host"),
                s.getInt("port", 0), s.getString("database"), s.getString("user"), s.getString("password"),
                s.getString("url"), s.getInt("pool-size", 10), props);
    }
}
