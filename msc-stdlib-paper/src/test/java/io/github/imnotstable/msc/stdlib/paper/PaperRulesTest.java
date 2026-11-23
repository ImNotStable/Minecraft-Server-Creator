package io.github.imnotstable.msc.stdlib.paper;

import io.github.imnotstable.msc.stdlib.db.Database;
import io.github.imnotstable.msc.stdlib.db.DatabaseConfig;
import io.github.imnotstable.msc.stdlib.economy.Economy;
import io.github.imnotstable.msc.stdlib.perm.Permissions;
import org.bukkit.OfflinePlayer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.lang.reflect.Proxy;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Permissions fed into Bukkit, and the Vault bridge's answers (ADR 0086). */
class PaperRulesTest {
    @TempDir
    Path dir;
    private ServerMock server;

    @BeforeEach
    void start() {
        server = MockBukkit.mock();
        Stdlib.init(MockBukkit.createMockPlugin());
    }

    @AfterEach
    void stop() {
        MockBukkit.unmock();
    }

    @Test
    void permissionsReachBukkitChecks() {
        Permissions perms = Permissions.memory();
        perms.group("default").node("spawn.use").save();
        perms.group("vip").node("kit.vip").deny("spawn.use").weight(5).save();
        PaperPermissions.use(perms);
        PlayerMock p = server.addPlayer();
        assertThat(p.hasPermission("spawn.use")).isTrue();
        assertThat(p.hasPermission("kit.vip")).isFalse();
        perms.addGroup(p.getUniqueId(), "vip");
        PaperPermissions.refresh(p);
        assertThat(p.hasPermission("kit.vip")).isTrue();
        assertThat(p.hasPermission("spawn.use")).isFalse();
    }

    /** Stand-ins for Vault's types, with the same shapes the bridge looks up. */
    public enum ResponseType { SUCCESS, FAILURE, NOT_IMPLEMENTED }

    public record Response(double amount, double balance, ResponseType type, String errorMessage) {
    }

    public interface VaultLike {
        double getBalance(OfflinePlayer player);

        boolean has(OfflinePlayer player, double amount);

        Response withdrawPlayer(OfflinePlayer player, double amount);

        Response depositPlayer(String name, double amount);

        String format(double amount);

        int fractionalDigits();

        Response bankDeposit(String bank, double amount);
    }

    @Test
    void vaultCallsMoveBalancesInShownUnits() {
        assertThat(VaultBridge.register(null, "Coins", "Coin", 100)).isFalse(); // no Vault installed
        Database db = Database.open(DatabaseConfig.sqlite(dir.resolve("eco.db")));
        Economy coins = Economy.sql(db, "coins", "$", 100);
        PlayerMock p = server.addPlayer("Alex");
        VaultLike vault = (VaultLike) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{VaultLike.class},
                new VaultBridge.Handler(coins, "Coins", "Coin", 100, Response.class, ResponseType.class));
        assertThat(vault.depositPlayer("Alex", 12.5).type()).isEqualTo(ResponseType.SUCCESS);
        assertThat(vault.getBalance(p)).isEqualTo(12.5);
        assertThat(vault.has(p, 12.51)).isFalse();
        Response no = vault.withdrawPlayer(p, 20);
        assertThat(no.type()).isEqualTo(ResponseType.FAILURE);
        assertThat(no.balance()).isEqualTo(12.5);
        assertThat(vault.withdrawPlayer(p, 2.5).balance()).isEqualTo(10.0);
        assertThat(vault.fractionalDigits()).isEqualTo(2);
        assertThat(vault.bankDeposit("b", 1).type()).isEqualTo(ResponseType.NOT_IMPLEMENTED);
        db.close();
    }
}
