package io.github.imnotstable.msc.bench.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * #661, #662, #663: many generated bench.json files, each with several independent problems in different keys and
 * in different entries of one list or object (secrets of the wrong type, values with line breaks or spaces at an
 * end). Every planted problem must be listed, each on exactly one line, and no secret may appear anywhere in the
 * refusal. The seed is fixed, so every run checks the same configs.
 */
class BenchConfigProblemsPropertyTest {

    private static final long SEED = 661_662_663L;
    private static final int CONFIGS = 300;

    private static final String BASE = """
            {"agent":{"command":["claude","-p"],"timeoutSeconds":60},
             "modes":{"m":{"extraArgs":["--x"],"env":{"A":"1"},"serverEnv":{"B":"2"},"serverPlugins":["{agentJar}"],
                           "paperCacheSeed":".msc/cache","requireMcp":"msc"}},
             "server":{"command":["java","-jar","paper.jar"],"paperCacheDirs":["cache"],
                       "downloadsUrl":"https://fill.papermc.io","serverPort":25566,"rconPort":25576,
                       "rconPassword":"pw","startTimeoutSeconds":180},
             "paths":{"agentJar":"agent.jar"},"corePort":25590,"minecraftVersion":"26.3"}
            """;

    private static final String SERVER_KEYS = "[command, paperJar, paperCacheDirs, downloadsUrl, acceptEula, "
            + "serverPort, rconPort, rconPassword, startTimeoutSeconds]";
    private static final String MODE_KEYS = "[extraArgs, env, mcpConfigTemplate, serverEnv, serverPlugins, "
            + "paperCacheSeed, requireMcp]";

    @TempDir
    Path dir;

    /** One config being built: its JSON, the problems planted in it and the secrets it holds. */
    private static final class Config {

        final JsonObject root = JsonParser.parseString(BASE).getAsJsonObject();
        final Random random;
        final List<String> expected = new ArrayList<>();
        final List<String> secrets = new ArrayList<>();
        final Set<String> slots = new HashSet<>();
        int next;

        Config(Random random) {
            this.random = random;
        }

        JsonObject object(String... path) {
            JsonObject at = root;
            for (String key : path) {
                at = at.getAsJsonObject(key);
            }
            return at;
        }

        JsonArray list(String... path) {
            JsonObject holder = object(java.util.Arrays.copyOf(path, path.length - 1));
            return holder.getAsJsonArray(path[path.length - 1]);
        }

        /** A fresh name suffix, so no two planted entries share a key. */
        int id() {
            return next++;
        }

        String token() {
            StringBuilder out = new StringBuilder("sk-ant-");
            String chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
            for (int i = 0; i < 24; i++) {
                out.append(chars.charAt(random.nextInt(chars.length())));
            }
            secrets.add(out.toString());
            return out.toString();
        }

        long secretNumber() {
            long number = 900_000_000L + random.nextInt(99_999_999);
            secrets.add(Long.toString(number));
            return number;
        }
    }

    /** A problem that can be planted; one with a slot is planted at most once per config. */
    private record Plant(String slot, java.util.function.Consumer<Config> plant) {
    }

    private static final List<Plant> PLANTS = List.of(
            // #661: secrets of the wrong type, or refused by a rule that must not show them.
            new Plant("rconPassword", c -> {
                c.object("server").addProperty("rconPassword", c.secretNumber());
                c.expected.add("server.rconPassword: a number of 9 characters is not a string");
            }),
            new Plant("rconPassword", c -> {
                JsonArray list = new JsonArray();
                list.add(c.token());
                c.object("server").add("rconPassword", list);
                c.expected.add("server.rconPassword: a list of 1 entry is not a string");
            }),
            new Plant("rconPassword", c -> {
                JsonObject object = new JsonObject();
                object.addProperty("x", c.token());
                c.object("server").add("rconPassword", object);
                c.expected.add("server.rconPassword: an object of 1 key is not a string");
            }),
            new Plant("rconPassword", c -> {
                c.object("server").addProperty("rconPassword", "\0" + c.token() + "\n ");
                c.expected.add("server.rconPassword: holds a NUL character; the server reads the password only up "
                        + "to it");
            }),
            new Plant(null, c -> {
                String name = "N" + c.id();
                JsonArray list = new JsonArray();
                list.add(c.token());
                c.object("modes", "m", "env").add(name, list);
                c.expected.add("modes.m.env." + name + ": a list of 1 entry is not a string");
            }),
            new Plant(null, c -> {
                String name = "S" + c.id();
                c.object("modes", "m", "serverEnv").addProperty(name, c.secretNumber());
                c.expected.add("modes.m.serverEnv." + name + ": a number of 9 characters is not a string");
            }),
            new Plant(null, c -> {
                String name = "K" + c.id();
                c.object("modes", "m", "env").addProperty(name + "=" + c.token(), "x");
                c.expected.add("modes.m.env: \"" + name + "=\"<31 hidden characters> is no environment variable "
                        + "name");
            }),
            new Plant(null, c -> {
                String name = "Z" + c.id();
                c.object("modes", "m", "serverEnv").addProperty(name, c.token() + "\0\n");
                c.expected.add("modes.m.serverEnv." + name + ": holds a NUL character, which no environment can hold");
            }),
            new Plant(null, c -> {
                String name = "U" + c.id();
                c.object("modes", "m", "env").add(name, JsonNull.INSTANCE);
                c.expected.add("modes.m.env." + name + ": is null");
            }),
            new Plant(null, c -> {
                String mode = "w" + c.id();
                JsonObject object = new JsonObject();
                object.addProperty("env", "KEY=" + c.token());
                c.object("modes").add(mode, object);
                c.expected.add("modes." + mode + ".env: a string of 35 characters is not an object");
            }),
            new Plant(null, c -> {
                String unknown = "evn" + c.id();
                JsonObject env = new JsonObject();
                env.addProperty("TOKEN", c.token());
                c.object("modes", "m").add(unknown, env);
                c.expected.add("modes.m." + unknown + ": unknown key; the keys are " + MODE_KEYS);
            }),
            // #663: entries of one list or object, each refused on its own.
            new Plant(null, c -> {
                String name = "p" + c.id();
                c.object("paths").addProperty(name, 5);
                // A placeholder naming the refused entry is left out; it is not a typo.
                c.list("modes", "m", "serverPlugins").add("{" + name + "}");
                c.expected.add("paths." + name + ": 5 is not a string");
            }),
            new Plant(null, c -> {
                int id = c.id();
                c.object("paths").addProperty("p\n" + id, "x");
                c.expected.add("paths: the name \"p\\n" + id + "\" is no placeholder name; use letters, digits, '.', "
                        + "'_' or '-'");
            }),
            new Plant(null, c -> {
                int id = c.id();
                c.object("paths").addProperty("q " + id, "x");
                c.expected.add("paths: the name \"q " + id + "\" is no placeholder name; use letters, digits, '.', "
                        + "'_' or '-'");
            }),
            new Plant(null, c -> {
                String name = "nope" + c.id();
                c.list("modes", "m", "serverPlugins").add("{" + name + "}");
                c.expected.add("modes.m.serverPlugins: {" + name + "} in \"{" + name + "}\" is no placeholder; name a "
                        + "key of paths, such as {agentJar}");
            }),
            new Plant(null, c -> {
                JsonArray command = c.list("agent", "command");
                c.expected.add("agent.command[" + command.size() + "]: 7 is not a string");
                command.add(7);
            }),
            new Plant(null, c -> {
                JsonArray command = c.list("agent", "command");
                c.expected.add("agent.command: entry " + command.size() + " is blank");
                command.add(" \n");
            }),
            new Plant(null, c -> {
                JsonArray args = c.list("modes", "m", "extraArgs");
                c.expected.add("modes.m.extraArgs: entry " + args.size() + " is null");
                args.add(JsonNull.INSTANCE);
            }),
            new Plant(null, c -> {
                JsonArray args = c.list("modes", "m", "extraArgs");
                c.expected.add("modes.m.extraArgs[" + args.size() + "]: {} is not a string");
                args.add(new JsonObject());
            }),
            new Plant(null, c -> {
                JsonArray dirs = c.list("server", "paperCacheDirs");
                c.expected.add("server.paperCacheDirs: entry " + dirs.size() + " is blank");
                dirs.add("");
            }),
            new Plant(null, c -> {
                JsonArray dirs = c.list("server", "paperCacheDirs");
                c.expected.add("server.paperCacheDirs[" + dirs.size() + "]: 3 is not a string");
                dirs.add(3);
            }),
            new Plant(null, c -> {
                String mode = "y" + c.id();
                c.object("modes").addProperty(mode, 5);
                c.expected.add("modes." + mode + ": 5 is not an object");
            }),
            new Plant(null, c -> {
                String mode = "a b" + c.id();
                c.object("modes").add(mode, new JsonObject());
                c.expected.add("modes.\"" + mode + "\": the name is no mode name (it names attempt folders and --modes "
                        + "lists); use letters, digits, '.', '_' or '-'");
            }),
            // #662: values with line breaks or spaces at an end, quoted on one line.
            new Plant(null, c -> {
                int id = c.id();
                c.object("server").addProperty("bad\nkey" + id, 1);
                c.expected.add("server.\"bad\\nkey" + id + "\": unknown key; the keys are " + SERVER_KEYS);
            }),
            new Plant("minecraftVersion", c -> {
                c.root.addProperty("minecraftVersion", "26.3\n");
                c.expected.add("minecraftVersion: \"26.3\\n\" is no Minecraft version, such as 26.3");
            }),
            new Plant("minecraftVersion", c -> {
                c.root.addProperty("minecraftVersion", " 26.3");
                c.expected.add("minecraftVersion: \" 26.3\" is no Minecraft version, such as 26.3");
            }),
            new Plant("downloadsUrl", c -> {
                c.object("server").addProperty("downloadsUrl", "https://x/\nfoo");
                c.expected.add("server.downloadsUrl: \"https://x/\\nfoo\" is no http or https URL");
            }),
            new Plant("paperCacheSeed", c -> {
                c.object("modes", "m").addProperty("paperCacheSeed", "../x ");
                c.expected.add("modes.m.paperCacheSeed: \"../x \" is not a folder inside the workspace");
            }),
            new Plant("timeoutSeconds", c -> {
                c.object("agent").addProperty("timeoutSeconds", "6\r\n0");
                c.expected.add("agent.timeoutSeconds: \"6\\r\\n0\" is not a whole number of seconds above 0");
            }),
            new Plant("requireMcp", c -> {
                c.object("modes", "m").addProperty("requireMcp", "\t");
                c.expected.add("modes.m.requireMcp: is blank; leave it out or give a value");
            }));

    @Test
    void everyPlantedProblemIsListedOnOneLineAndNoSecretIsShown() throws IOException {
        Random random = new Random(SEED);
        Path file = dir.resolve("bench.json");
        Path home = Files.createDirectories(dir.resolve("home"));
        for (int n = 0; n < CONFIGS; n++) {
            Config config = new Config(random);
            int problems = 3 + random.nextInt(6);
            while (config.expected.size() < problems) {
                Plant plant = PLANTS.get(random.nextInt(PLANTS.size()));
                if (plant.slot() == null || config.slots.add(plant.slot())) {
                    plant.plant().accept(config);
                }
            }
            Files.writeString(file, config.root.toString());

            Throwable refusal = catchThrowable(() -> BenchConfig.load(file, home));
            String context = "config " + n + ": " + config.root;
            assertThat(refusal).as(context).isInstanceOf(IOException.class);
            String head = "Bad bench config " + file + ":\n  ";
            assertThat(refusal.getMessage()).as(context).startsWith(head);
            String listed = refusal.getMessage().substring(head.length());
            // One problem per line: a line break inside a problem would add a line here.
            List<String> lines = List.of(listed.split("\n  ", -1));
            assertThat(lines).as(context).containsExactlyInAnyOrderElementsOf(config.expected);
            for (String line : lines) {
                assertThat(line).as(context).doesNotContain("\n", "\r", "\u0085", " ", " ");
            }
            for (String secret : config.secrets) {
                assertThat(listed).as(context).doesNotContain(secret);
            }
        }
    }

    /** The generator plants what it means to: a planted secret is in the file, so the check above is not vacuous. */
    @Test
    void thePlantedSecretsAreInTheFiles() {
        Random random = new Random(SEED);
        int secrets = 0;
        for (Plant plant : PLANTS) {
            Config config = new Config(random);
            plant.plant().accept(config);
            String written = JsonParser.parseString(config.root.toString()).toString();
            for (String secret : config.secrets) {
                assertThat(written).contains(secret);
            }
            secrets += config.secrets.size();
        }
        assertThat(secrets).isGreaterThanOrEqualTo(10);
    }
}
