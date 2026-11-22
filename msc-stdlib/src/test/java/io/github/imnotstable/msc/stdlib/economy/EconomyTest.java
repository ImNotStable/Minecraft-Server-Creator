package io.github.imnotstable.msc.stdlib.economy;

import io.github.imnotstable.msc.stdlib.db.Database;
import io.github.imnotstable.msc.stdlib.db.DatabaseConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class EconomyTest {
    @TempDir
    Path dir;

    @Test
    void balancesNeverGoNegativeAndTransfersAreAllOrNothing() {
        try (Database db = Database.open(DatabaseConfig.sqlite(dir.resolve("eco.db")))) {
            Economy eco = Economy.sql(db, "coins", "$", 100);
            UUID a = UUID.randomUUID(), b = UUID.randomUUID();
            eco.deposit(a, 1000);
            eco.deposit(a, 250);
            assertThat(eco.balance(a)).isEqualTo(1250);
            assertThat(eco.withdraw(a, 2000)).isFalse();
            assertThat(eco.withdraw(a, 250)).isTrue();
            assertThat(eco.transfer(a, b, 400)).isTrue();
            assertThat(eco.transfer(b, a, 401)).isFalse();
            assertThat(eco.balance(b)).isEqualTo(400);
            assertThat(eco.top(1).getFirst().id()).isEqualTo(a);
            assertThat(eco.format(eco.balance(a))).isEqualTo("$6.00");
        }
    }
}
