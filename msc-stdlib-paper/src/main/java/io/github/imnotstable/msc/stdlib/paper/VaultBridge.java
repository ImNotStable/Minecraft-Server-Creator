package io.github.imnotstable.msc.stdlib.paper;

import io.github.imnotstable.msc.stdlib.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.UUID;

/**
 * Offers an {@link Economy} to every plugin through Vault, so shops, jobs and auction plugins use your balances. Vault
 * is only needed at run time: the bridge implements Vault's {@code Economy} interface by reflection when Vault is
 * installed, and does nothing otherwise. Amounts cross as doubles in shown units ({@code scale} units each: 100 for
 * cents); bank accounts are not supported.
 *
 * <pre>{@code
 * Economy coins = Economy.sql(db, "coins", "⛃", 1);
 * VaultBridge.register(coins, "Coins", "Coin", 1);    // in onEnable, after Stdlib.init
 * }</pre>
 */
public final class VaultBridge {
    private VaultBridge() {}

    /** Register the economy with Vault; false when Vault is not installed. */
    public static boolean register(Economy economy, String plural, String singular, int scale) {
        Plugin vault = Bukkit.getPluginManager().getPlugin("Vault");
        if (vault == null) {
            return false;
        }
        try {
            ClassLoader loader = vault.getClass().getClassLoader();
            Class<?> api = Class.forName("net.milkbowl.vault.economy.Economy", true, loader);
            Class<?> response = Class.forName("net.milkbowl.vault.economy.EconomyResponse", true, loader);
            @SuppressWarnings({"unchecked", "rawtypes"})
            Class<? extends Enum> type = (Class<? extends Enum>) Class.forName(
                    "net.milkbowl.vault.economy.EconomyResponse$ResponseType", true, loader);
            Object proxy = Proxy.newProxyInstance(loader, new Class<?>[]{api},
                    new Handler(economy, plural, singular, scale, response, type));
            register(api, proxy);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void register(Class api, Object proxy) {
        Bukkit.getServicesManager().register(api, proxy, Stdlib.plugin(), ServicePriority.Normal);
    }

    /** What each Vault method does, by name; players come as OfflinePlayer or (old plugins) a name. */
    static final class Handler implements InvocationHandler {
        private final Economy economy;
        private final String plural;
        private final String singular;
        private final int scale;
        private final Constructor<?> response;
        @SuppressWarnings("rawtypes")
        private final Class<? extends Enum> type;

        @SuppressWarnings("rawtypes")
        Handler(Economy economy, String plural, String singular, int scale, Class<?> response,
                Class<? extends Enum> type) {
            this.economy = economy;
            this.plural = plural;
            this.singular = singular;
            this.scale = scale;
            this.type = type;
            try {
                this.response = response.getConstructor(double.class, double.class, type, String.class);
            } catch (NoSuchMethodException e) {
                throw new IllegalStateException("Vault's EconomyResponse changed", e);
            }
        }

        @Override
        public Object invoke(Object proxy, Method m, Object[] args) throws Throwable {
            return switch (m.getName()) {
                case "isEnabled" -> true;
                case "getName" -> "msc-stdlib " + plural;
                case "hasBankSupport" -> false;
                case "fractionalDigits" -> scale <= 1 ? 0 : (int) Math.round(Math.log10(scale));
                case "format" -> economy.format(units((Double) args[0]));
                case "currencyNamePlural" -> plural;
                case "currencyNameSingular" -> singular;
                case "hasAccount", "createPlayerAccount" -> true;
                case "getBalance" -> shown(economy.balance(id(args[0])));
                case "has" -> economy.has(id(args[0]), units((Double) args[args.length - 1]));
                case "withdrawPlayer" -> {
                    UUID id = id(args[0]);
                    long amount = units((Double) args[args.length - 1]);
                    if (amount < 0) {
                        yield reply(0, id, "FAILURE", "Cannot withdraw a negative amount");
                    }
                    yield economy.withdraw(id, amount) ? reply(amount, id, "SUCCESS", null)
                            : reply(0, id, "FAILURE", "Insufficient funds");
                }
                case "depositPlayer" -> {
                    UUID id = id(args[0]);
                    long amount = units((Double) args[args.length - 1]);
                    if (amount < 0) {
                        yield reply(0, id, "FAILURE", "Cannot deposit a negative amount");
                    }
                    economy.deposit(id, amount);
                    yield reply(amount, id, "SUCCESS", null);
                }
                case "getBanks" -> java.util.List.of();
                case "equals" -> proxy == args[0];
                case "hashCode" -> System.identityHashCode(proxy);
                case "toString" -> "VaultBridge[" + plural + "]";
                default -> m.getReturnType() == boolean.class ? false : reply(0, null, "NOT_IMPLEMENTED",
                        "Banks are not supported");
            };
        }

        private long units(double shown) {
            return Math.round(shown * scale);
        }

        private double shown(long units) {
            return (double) units / scale;
        }

        @SuppressWarnings("unchecked")
        private Object reply(long amount, UUID id, String result, String error) throws ReflectiveOperationException {
            double balance = id == null ? 0 : shown(economy.balance(id));
            return response.newInstance(shown(amount), balance, Enum.valueOf(type, result), error);
        }

        @SuppressWarnings("deprecation")
        private static UUID id(Object player) {
            if (player instanceof OfflinePlayer p) {
                return p.getUniqueId();
            }
            return Bukkit.getOfflinePlayer(String.valueOf(player)).getUniqueId();
        }
    }
}
