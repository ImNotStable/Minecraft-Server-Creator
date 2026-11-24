package io.github.imnotstable.msc.stdlib.bungeecord;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.stdlib.cmd.CommandTree;
import io.github.imnotstable.msc.stdlib.text.Messages;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.plugin.TabExecutor;
import net.md_5.bungee.api.scheduler.ScheduledTask;
import net.md_5.bungee.api.scheduler.TaskScheduler;
import org.junit.jupiter.api.Test;

/** ADR 0104: the BungeeCord adapter against fakes of BungeeCord's interfaces. */
class BungeeAdapterTest {
    @SuppressWarnings("unchecked")
    static <T> T fake(Class<T> type, java.lang.reflect.InvocationHandler h) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, h);
    }

    /** A sender that keeps what it is sent as plain text and has the permissions given. */
    static <T extends CommandSender> T sender(Class<T> type, List<String> got, Locale locale, String... permissions) {
        return fake(type, (p, m, a) -> switch (m.getName()) {
            case "sendMessage" -> {
                if (a != null && a.length == 1 && a[0] instanceof BaseComponent c) {
                    got.add(c.toPlainText());
                }
                yield null;
            }
            case "hasPermission" -> List.of(permissions).contains((String) a[0]);
            case "getName" -> "Alex";
            case "getLocale" -> locale;
            case "getUniqueId" -> new java.util.UUID(1, 2);
            default -> null;
        });
    }

    @Test
    void ticksBecomeMillisecondsOnBungeesScheduler() {
        List<String> scheduled = new ArrayList<>();
        ScheduledTask task = fake(ScheduledTask.class, (p, m, a) -> null);
        TaskScheduler tasks = fake(TaskScheduler.class, (p, m, a) -> {
            if (m.getName().equals("schedule")) {
                scheduled.add(a.length == 4 ? "once after " + ((TimeUnit) a[3]).toMillis((Long) a[2])
                        : "every " + ((TimeUnit) a[4]).toMillis((Long) a[3]) + " after " + ((TimeUnit) a[4]).toMillis((Long) a[2]));
            }
            return task;
        });
        BungeeScheduler s = new BungeeScheduler(null, tasks); // a Plugin can only be made by its class loader
        s.later(20, () -> { });
        var t = s.repeat(2, 10, () -> { });
        assertThat(scheduled).containsExactly("once after 1000", "every 500 after 100");
        assertThat(t.cancelled()).isFalse();
        t.cancel();
        assertThat(t.cancelled()).isTrue();
    }

    @Test
    void miniMessageBecomesBungeeChatWithItsColours() {
        BaseComponent c = BungeeText.mm("<green>Hi <bold><name></bold>", "name", "Alex");
        assertThat(c.toPlainText()).isEqualTo("Hi Alex");
        assertThat(c.toLegacyText()).contains("§a").contains("§l");
    }

    @Test
    void languagesFollowThePlayersLocaleAndFallBackForTheConsole() {
        Lang lang = Lang.of(Messages.of("en", Map.of("hi", "<green>Hi <name>")));
        List<String> got = new ArrayList<>();
        lang.send(sender(CommandSender.class, got, null), "hi", "name", "console");
        lang.send(sender(ProxiedPlayer.class, got, Locale.GERMANY), "hi", "name", "Alex");
        assertThat(got).containsExactly("Hi console", "Hi Alex");
    }

    @Test
    void aCommandTreeRunsChecksPermissionsAndCompletes() {
        List<String> ran = new ArrayList<>();
        CommandTree tree = CommandTree.create("hub").permission("hub.use")
                .sub(CommandTree.create("go").executes(c -> ran.add("go")));
        var command = Cmd.command(tree);
        List<String> got = new ArrayList<>();
        command.execute(sender(CommandSender.class, got, null), new String[]{"go"});
        assertThat(ran).isEmpty();
        assertThat(got).singleElement().asString().contains("permission");
        command.execute(sender(CommandSender.class, got, null, "hub.use"), new String[]{"go"});
        assertThat(ran).containsExactly("go");
        assertThat(((TabExecutor) command).onTabComplete(sender(CommandSender.class, got, null, "hub.use"),
                new String[]{"g"})).contains("go");
        assertThat(command.getName()).isEqualTo("hub");
    }
}
