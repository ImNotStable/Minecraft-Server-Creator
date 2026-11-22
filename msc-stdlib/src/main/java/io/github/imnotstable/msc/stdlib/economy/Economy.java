package io.github.imnotstable.msc.stdlib.economy;

import io.github.imnotstable.msc.stdlib.db.Database;
import io.github.imnotstable.msc.stdlib.db.Id;
import io.github.imnotstable.msc.stdlib.db.Repository;
import io.github.imnotstable.msc.stdlib.util.Format;

import java.util.List;
import java.util.UUID;

/**
 * Balances in a database, safe when several servers share it: withdrawals never go below zero, transfers are one
 * transaction. Amounts are in cents (or your smallest unit) as {@code long}, so no rounding errors.
 *
 * <pre>{@code
 * Economy coins = Economy.sql(db, "coins", "⛃", 1);       // whole coins, no decimals
 * coins.deposit(id, 100);
 * if (!coins.withdraw(id, 250)) player.sendMessage("Not enough coins");
 * coins.format(coins.balance(id));                        // "⛃1,350"
 * }</pre>
 */
public final class Economy {
    /** One account. */
    public record Account(@Id UUID id, long balance) {}

    private final Repository<Account> accounts;
    private final Database db;
    private final String symbol;
    private final int scale;

    private Economy(Database db, String table, String symbol, int scale) {
        this.db = db;
        this.accounts = Repository.of(db, table, Account.class);
        this.symbol = symbol;
        this.scale = scale;
    }

    /**
     * @param scale how many units make one shown unit: 100 for cents (shows {@code $12.50}), 1 for whole coins
     */
    public static Economy sql(Database db, String table, String symbol, int scale) {
        return new Economy(db, table, symbol, scale);
    }

    public long balance(UUID id) {
        return accounts.find(id).map(Account::balance).orElse(0L);
    }

    public boolean has(UUID id, long amount) {
        return balance(id) >= amount;
    }

    public void deposit(UUID id, long amount) {
        requirePositive(amount);
        if (!accounts.increment(id, "balance", amount)) {
            db.transaction(c -> {
                // No account yet: create it, then add (another server may have created it meanwhile).
                if (Database.query(c, "SELECT 1 AS x FROM " + accounts.table() + " WHERE id = ?", r -> 1, id).isEmpty()) {
                    Database.update(c, "INSERT INTO " + accounts.table() + " (id, balance) VALUES (?, ?)", id, amount);
                } else {
                    Database.update(c, "UPDATE " + accounts.table() + " SET balance = balance + ? WHERE id = ?", amount, id);
                }
                return null;
            });
        }
    }

    /** Takes {@code amount} only if the balance covers it; false (nothing taken) otherwise. */
    public boolean withdraw(UUID id, long amount) {
        requirePositive(amount);
        return db.update("UPDATE " + accounts.table() + " SET balance = balance - ? WHERE id = ? AND balance >= ?",
                amount, id, amount) > 0;
    }

    /** Moves {@code amount} from one account to another in one transaction; false when {@code from} has too little. */
    public boolean transfer(UUID from, UUID to, long amount) {
        requirePositive(amount);
        return db.transaction(c -> {
            if (Database.update(c, "UPDATE " + accounts.table() + " SET balance = balance - ? WHERE id = ? AND balance >= ?",
                    amount, from, amount) == 0) {
                return false;
            }
            if (Database.update(c, "UPDATE " + accounts.table() + " SET balance = balance + ? WHERE id = ?", amount, to) == 0) {
                Database.update(c, "INSERT INTO " + accounts.table() + " (id, balance) VALUES (?, ?)", to, amount);
            }
            return true;
        });
    }

    public void set(UUID id, long balance) {
        accounts.save(new Account(id, balance));
    }

    /** The richest {@code n} accounts. */
    public List<Account> top(int n) {
        return accounts.top("balance", n);
    }

    /** {@code ⛃1,350} or {@code $12.50}, by the scale. */
    public String format(long amount) {
        return scale == 1 ? symbol + Format.number(amount) : Format.money(amount / (double) scale, symbol);
    }

    public Repository<Account> accounts() {
        return accounts;
    }

    private static void requirePositive(long amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("Amount must be above 0: " + amount);
        }
    }
}
