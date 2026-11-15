package io.github.imnotstable.msc.bench;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.bench.run.FullDisk;
import io.github.imnotstable.msc.bench.run.RunFolderUnwritable;
import io.github.imnotstable.msc.buildlogic.RepoFiles;
import io.github.imnotstable.msc.bench.run.RunRecord;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * #443, #444: every subcommand crossed with the inputs it can be given wrong, through the command line's own entry
 * point on a temporary home, with no network and no server. Each is refused with exit code 2 and one
 * {@code msc-bench:} line naming what is wrong, never a stack trace, and leaves the file tree as it was: no run
 * folder, no report.
 */
class CommandLineTest {

    /** One valid run, as a line of results.jsonl. */
    private static final String RUN = "{\"task\":\"t1\",\"category\":\"code\",\"mode\":\"baseline\",\"repeat\":1,"
            + "\"passed\":true}";

    /** results.jsonl contents that report refuses, each with what the refusal says. */
    private static final Map<String, Results> BAD_RESULTS = new TreeMap<>();
    /** results.jsonl contents that report takes. */
    private static final Map<String, byte[]> GOOD_RESULTS = new TreeMap<>();

    private record Results(byte[] bytes, String says) {
    }

    static {
        bad("utf16-no-bom", RUN.getBytes(StandardCharsets.UTF_16LE), "line 1: holds a NUL character");
        bad("latin1", RUN.replace("code", "caf\u00e9").getBytes(StandardCharsets.ISO_8859_1),
                "line 1: the byte at offset 28 is not UTF-8 text; save the file as UTF-8");
        bad("latin1-line2", (RUN + "\n" + RUN.replace("code", "\u00e9")).getBytes(StandardCharsets.ISO_8859_1),
                "line 2: the byte at offset");
        bad("null", "null", "line 1: is null, not a JSON object");
        bad("list", "[]", "line 1: is a list, not a JSON object");
        bad("value", "\"run\"", "line 1: is a single value, not a JSON object");
        bad("empty-object", "{}", "line 1: task: is missing");
        bad("no-mode", RUN.replace("\"mode\":\"baseline\",", ""), "line 1: mode: is missing");
        bad("null-mode", RUN.replace("\"baseline\"", "null"), "line 1: mode: is missing");
        bad("blank-task", RUN.replace("\"t1\"", "\" \""), "line 1: task: is blank");
        bad("number-task", RUN.replace("\"t1\"", "1"), "line 1: task: 1 is not a string");
        bad("string-repeat", RUN.replace("\"repeat\":1", "\"repeat\":\"1\""), "line 1: repeat: \"1\" is not a whole");
        bad("string-passed", RUN.replace("true", "\"true\""), "line 1: passed: \"true\" is not true or false");
        bad("unknown-key", RUN.replace("\"passed\"", "\"pased\":true,\"passed\""), "line 1: pased: unknown key");
        bad("null-check", RUN.replace("}", ",\"checks\":[null]}"), "line 1: checks[0]: null is not an object");
        bad("check-no-name", RUN.replace("}", ",\"checks\":[{\"passed\":true}]}"), "line 1: checks[0].name: is missing");
        bad("null-mcp", RUN.replace("}", ",\"agent\":{\"mcpConnected\":[\"msc\",null]}}"),
                "line 1: agent.mcpConnected[1]: null is not a string");
        bad("agent-list", RUN.replace("}", ",\"agent\":[]}"), "line 1: agent: [] is not an object");
        bad("repeat-0", RUN.replace("\"repeat\":1", "\"repeat\":0"), "line 1: repeat: 0 is not a whole number above 0");
        bad("repeat-huge", RUN.replace("\"repeat\":1", "\"repeat\":4294967297"), "line 1: repeat: 4294967297 is not");
        bad("turns-decimal", RUN.replace("}", ",\"agent\":{\"turns\":3.5}}"), "line 1: agent.turns: 3.5 is not");
        bad("tokens-negative", RUN.replace("}", ",\"agent\":{\"outputTokens\":-1}}"), "line 1: agent.outputTokens: -1 is not");
        bad("tokens-huge", RUN.replace("}", ",\"agent\":{\"inputTokens\":1e30}}"), "line 1: agent.inputTokens: 1e30 is not");
        bad("cost-huge", RUN.replace("}", ",\"agent\":{\"costUsd\":1e400}}"), "line 1: agent.costUsd: 1e400 is not");
        bad("wall-null", RUN.replace("}", ",\"wallMs\":null}"), "line 1: wallMs: null is not");
        bad("tool-calls-0", RUN.replace("}", ",\"tools\":{\"perTool\":{\"x\":{\"calls\":0}}}}"),
                "line 1: tools.perTool.x.calls: 0 is not");
        bad("truncated", RUN.substring(0, 20), "line 1: is not valid JSON: Unterminated string at column 21");
        bad("trailing", RUN + " x", "line 1: is not valid JSON: unexpected text at column");
        bad("two-objects", RUN + RUN, "line 1: is not valid JSON: unexpected text at column");
        bad("single-quotes", RUN.replace('"', '\''), "line 1: is not valid JSON: unexpected text at column 3");
        bad("deep-list", "[".repeat(300), "line 1: is not valid JSON: Nesting limit 255 reached at column");
        // #462: a half-written line is left out only as the last line of a file with runs before it.
        bad("half-line-middle", RUN + "\n" + RUN.substring(0, 20) + "\n" + RUN + "\n",
                "line 2: is not valid JSON: Unterminated string");
        bad("half-line-ended", RUN + "\n" + RUN.substring(0, 20) + "\n", "line 2: is not valid JSON");
        bad("half-line-complete", RUN + "\n{\"task\":\"t1\"}", "line 2: category: is missing");
        bad("blank", "\n  \n", "holds no runs");
        bad("empty", "", "holds no runs");

        good("crlf", (RUN + "\r\n" + RUN + "\r\n").getBytes(StandardCharsets.UTF_8));
        good("utf8-bom", bom(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}, RUN, StandardCharsets.UTF_8));
        good("utf16le-bom", bom(new byte[] {(byte) 0xFF, (byte) 0xFE}, RUN + "\r\n", StandardCharsets.UTF_16LE));
        good("utf16be-bom", bom(new byte[] {(byte) 0xFE, (byte) 0xFF}, RUN + "\n", StandardCharsets.UTF_16BE));
        good("joined-with-boms", ("\uFEFF" + RUN + "\n\uFEFF" + RUN + "\n").getBytes(StandardCharsets.UTF_8));
        good("half-written-last", (RUN + "\n" + RUN.substring(0, 20)).getBytes(StandardCharsets.UTF_8));
        // #477: `cat a b` where a ends on a half line: the half runs straight into b's first run.
        good("half-written-joined", (RUN + "\n" + RUN.substring(0, 20) + RUN + "\n").getBytes(StandardCharsets.UTF_8));
        good("non-ascii", RUN.replace("code", "caf\u00e9 \u2014").getBytes(StandardCharsets.UTF_8));
    }

    /** bench.json files with one value of the wrong type: name to {contents, what the refusal says}. */
    private static final Map<String, String[]> BAD_CONFIGS = new TreeMap<>();
    /** task.json files with one value of the wrong type: name to {contents, what the refusal says}. */
    private static final Map<String, String[]> BAD_TASKS = new TreeMap<>();

    static {
        BAD_CONFIGS.put("password-number", new String[] {"{\"server\":{\"rconPassword\":5}}",
                "server.rconPassword: a number of 1 character is not a string"});
        BAD_CONFIGS.put("command-string", new String[] {"{\"agent\":{\"command\":\"claude\"}}",
                "agent.command: \"claude\" is not a list"});
        BAD_CONFIGS.put("eula-yes", new String[] {"{\"server\":{\"acceptEula\":\"yes\"}}",
                "server.acceptEula: \"yes\" is not true or false"});
        BAD_CONFIGS.put("modes-list", new String[] {"{\"modes\":[]}", "modes: [] is not an object"});
        String check = "\"checks\":[{\"type\":\"file\",\"path\":\"a.txt\"}]";
        BAD_TASKS.put("server-true", new String[] {"{\"category\":\"code\",\"title\":\"t\",\"server\":true," + check
                + "}", "server: true is not an object"});
        BAD_TASKS.put("start-before-yes", new String[] {"{\"category\":\"code\",\"title\":\"t\","
                + "\"server\":{\"startBefore\":\"yes\"}," + check + "}",
                "server.startBefore: \"yes\" is not true or false"});
        // #564: setup commands the runner would never send.
        BAD_TASKS.put("setup-without-start-before", new String[] {"{\"category\":\"world\",\"title\":\"t\","
                + "\"server\":{\"setup\":[\"forceload add 0 0\"]}," + check + "}",
                "server.setup: runs only before the agent, so it needs \"startBefore\": true"});
        BAD_TASKS.put("setup-start-before-false", new String[] {"{\"category\":\"world\",\"title\":\"t\","
                + "\"server\":{\"startBefore\":false,\"setup\":[\"say hi\"]}," + check + "}",
                "server.setup: runs only before the agent, so it needs \"startBefore\": true"});
        // #570: a Minestom task that would start or check a Paper server.
        BAD_TASKS.put("minestom-start-before", new String[] {"{\"category\":\"game\",\"title\":\"t\","
                + "\"platform\":\"minestom\",\"server\":{\"startBefore\":true}," + check + "}",
                "server.startBefore: starts the Paper server, and a minestom task runs none"});
        BAD_TASKS.put("minestom-rcon-check", new String[] {"{\"category\":\"game\",\"title\":\"t\","
                + "\"platform\":\"minestom\",\"checks\":[{\"type\":\"file\",\"path\":\"a\"},"
                + "{\"type\":\"rcon\",\"command\":\"list\",\"expect\":\"x\"}]}",
                "checks[1].type: \"rcon\" checks the Paper server, and a minestom task runs none"});
        BAD_TASKS.put("minestom-blocks-check", new String[] {"{\"category\":\"game\",\"title\":\"t\","
                + "\"platform\":\"minestom\",\"checks\":[{\"type\":\"blocks\","
                + "\"blocks\":[{\"pos\":[0,0,0],\"block\":\"minecraft:air\"}]}]}",
                "checks[0].type: \"blocks\" checks the Paper server, and a minestom task runs none"});
        BAD_CONFIGS.put("path-nul", new String[] {"{\"gradleWrapperFrom\":\"a\\u0000b\"}",
                "gradleWrapperFrom: \"a\\u0000b\" is not a path: Nul character not allowed"});
        // #508: a bad regex is refused in one line naming its key, not in PatternSyntaxException's three lines.
        BAD_TASKS.put("bad-regex", new String[] {"{\"category\":\"code\",\"title\":\"t\",\"checks\":[{\"type\":"
                + "\"file\",\"path\":\"a.txt\",\"contains\":\"(abc\"}]}",
                "checks[0].contains: \"(abc\" is not a regex: Unclosed group at index 4"});
        // #519: a task refusal names its file and key path.
        BAD_TASKS.put("missing-fixture", new String[] {"{\"category\":\"code\",\"title\":\"t\",\"fixtures\":[\"_nope\"],"
                + check + "}", "fixtures: no folder _nope next to the task folders"});
        BAD_TASKS.put("bad-platform", new String[] {"{\"category\":\"code\",\"title\":\"t\",\"platform\":\"spigot\","
                + check + "}", "platform: \"spigot\" is no platform; use paper or minestom"});
        BAD_TASKS.put("no-category", new String[] {"{\"title\":\"t\"," + check + "}", "category: is missing"});
        BAD_TASKS.put("unknown-template", new String[] {"{\"category\":\"code\",\"title\":\"t\",\"scaffold\":"
                + "{\"template\":\"nope\",\"name\":\"n\",\"package\":\"p\"}," + check + "}",
                "scaffold.template: \"nope\" is no template; use one of"});
        BAD_TASKS.put("missing-add-files", new String[] {"{\"category\":\"code\",\"title\":\"t\",\"checks\":[{\"type\":"
                + "\"command\",\"run\":[\"x\"],\"addFiles\":\"tests\"}]}",
                "checks[0].addFiles: no folder tests in the task"});
        // #519: a paths entry a selected mode uses that does not exist is refused before the run folder.
        BAD_CONFIGS.put("missing-core-lib", new String[] {"{\"modes\":{\"toolset\":{\"mcpConfigTemplate\":"
                + "\"mcp.template\"}},\"paths\":{\"coreLib\":\"no-lib\"}}",
                "missing-core-lib.json: paths.coreLib: no such file or folder "});
        BAD_CONFIGS.put("missing-plugin", new String[] {"{\"modes\":{\"toolset\":{\"serverPlugins\":[\"{agentJar}\"]}},"
                + "\"paths\":{\"agentJar\":\"no.jar\"}}",
                "missing-plugin.json: paths.agentJar: no such file or folder "});
        BAD_TASKS.put("title-number", new String[] {"{\"category\":\"code\",\"title\":7," + check + "}",
                "title: 7 is not a string"});
    }

    private static void bad(String name, String text, String says) {
        bad(name, text.getBytes(StandardCharsets.UTF_8), says);
    }

    private static void bad(String name, byte[] bytes, String says) {
        BAD_RESULTS.put(name, new Results(bytes, says));
    }

    private static void good(String name, byte[] bytes) {
        GOOD_RESULTS.put(name, bytes);
    }

    private static byte[] bom(byte[] mark, String text, Charset charset) {
        byte[] body = text.getBytes(charset);
        byte[] out = new byte[mark.length + body.length];
        System.arraycopy(mark, 0, out, 0, mark.length);
        System.arraycopy(body, 0, out, mark.length, body.length);
        return out;
    }

    @TempDir
    Path dir;

    @BeforeEach
    void machine() throws IOException {
        for (String id : List.of("t1", "t2")) {
            Path task = Files.createDirectories(dir.resolve("tasks").resolve(id));
            Files.writeString(task.resolve("task.json"), """
                    {"category":"code","title":"a file","checks":[{"type":"file","path":"a.txt"}]}
                    """);
            Files.writeString(task.resolve("prompt.md"), "write a.txt");
        }
        Files.createDirectories(dir.resolve("empty"));
        Files.createDirectories(dir.resolve("not-tasks/src"));
        Files.writeString(dir.resolve("not-tasks/README.md"), "not a task");
        Files.writeString(dir.resolve("a-file"), "x");
        Files.createDirectories(dir.resolve("home"));
        Files.createDirectories(dir.resolve("ws"));
        Files.writeString(dir.resolve("bench.json"), "{}");
        Files.createDirectories(dir.resolve("run-without-results"));
        for (Map.Entry<String, Results> bad : BAD_RESULTS.entrySet()) {
            Path runDir = Files.createDirectories(dir.resolve("runs").resolve(bad.getKey()));
            Files.write(runDir.resolve("results.jsonl"), bad.getValue().bytes());
        }
        for (Map.Entry<String, String[]> config : BAD_CONFIGS.entrySet()) {
            Files.createDirectories(dir.resolve("configs"));
            Files.writeString(dir.resolve("configs").resolve(config.getKey() + ".json"), config.getValue()[0]);
        }
        Files.writeString(dir.resolve("configs/mcp.template"), "{\"args\":[\"{coreLib}/*\"]}");
        // #519: the shipped config in a fresh checkout, where neither msc-core nor msc-agent-paper is built.
        Path fresh = Files.createDirectories(dir.resolve("fresh/msc-bench"));
        for (String file : List.of("bench.json", "mcp-toolset.template.json")) {
            Files.copy(RepoFiles.path("shippedConfig", "msc-bench/" + file), fresh.resolve(file));
        }
        Files.copy(RepoFiles.path("shippedConfig", "gradlew"), dir.resolve("fresh/gradlew"));
        for (Map.Entry<String, String[]> task : BAD_TASKS.entrySet()) {
            Path folder = Files.createDirectories(dir.resolve("bad-tasks").resolve(task.getKey()).resolve("t1"));
            Files.writeString(folder.resolve("task.json"), task.getValue()[0]);
            Files.writeString(folder.resolve("prompt.md"), "write a.txt");
        }
        // #555: each of the bench's own files saved as Latin-1, as a Windows editor may.
        byte[] cafe = "{\"category\":\"caf\u00e9\",\"title\":\"t\",\"checks\":[{\"type\":\"file\",\"path\":\"a\"}]}"
                .getBytes(StandardCharsets.ISO_8859_1);
        Path latin1Task = Files.createDirectories(dir.resolve("latin1/task-json/t1"));
        Files.write(latin1Task.resolve("task.json"), cafe);
        Files.writeString(latin1Task.resolve("prompt.md"), "write a.txt");
        Path latin1Prompt = Files.createDirectories(dir.resolve("latin1/prompt-md/t1"));
        Files.copy(dir.resolve("tasks/t1/task.json"), latin1Prompt.resolve("task.json"));
        Files.write(latin1Prompt.resolve("prompt.md"), "caf\u00e9".getBytes(StandardCharsets.ISO_8859_1));
        Path latin1Preamble = Files.createDirectories(dir.resolve("latin1/preamble/t1"));
        Files.copy(dir.resolve("tasks/t1/task.json"), latin1Preamble.resolve("task.json"));
        Files.copy(dir.resolve("tasks/t1/prompt.md"), latin1Preamble.resolve("prompt.md"));
        Files.write(dir.resolve("latin1/preamble/_preamble.md"), "caf\u00e9".getBytes(StandardCharsets.ISO_8859_1));
        Files.write(dir.resolve("latin1/bench.json"), "{\"minecraftVersion\":\"caf\u00e9\"}"
                .getBytes(StandardCharsets.ISO_8859_1));
        Files.writeString(dir.resolve("latin1/mcp-bench.json"),
                "{\"modes\":{\"toolset\":{\"mcpConfigTemplate\":\"mcp.template.json\"}}}");
        Files.write(dir.resolve("latin1/mcp.template.json"), "{\"name\":\"caf\u00e9\"}"
                .getBytes(StandardCharsets.ISO_8859_1));
        for (Map.Entry<String, byte[]> good : GOOD_RESULTS.entrySet()) {
            Path runDir = Files.createDirectories(dir.resolve("good").resolve(good.getKey()));
            Files.write(runDir.resolve("results.jsonl"), good.getValue());
        }
    }

    /** #609: the flags each command takes, written out here so the test does not read the table it checks. */
    private static final Map<String, List<String>> TAKES = new TreeMap<>(Map.of(
            "list", List.of("--tasks-dir"),
            "run", List.of("--tasks", "--modes", "--repeat", "--model", "--isolate", "--out", "--tasks-dir", "--config"),
            "check", List.of("--tasks-dir", "--config"),
            "report", List.of()));
    /** Every flag, and the ones that take no value. */
    private static final List<String> ALL_FLAGS = TAKES.get("run");
    private static final Set<String> NO_VALUE = Set.of("--isolate");
    /** Each command's positional arguments, given right. */
    private static final Map<String, List<String>> POSITIONAL = Map.of("list", List.of(), "run", List.of(),
            "check", List.of("t1", "@ws"), "report", List.of("@good/crlf"));

    /** Arguments with {@code @x} as the path of {@code x} in the test folder; then what the refusal says. */
    static Stream<Arguments> refusals() {
        List<Arguments> rows = new ArrayList<>();
        // Wrong for every command alike.
        for (String command : List.of("list", "run", "check", "report")) {
            rows.add(row(command + " --nope", "unknown option --nope", command, "--nope"));
        }
        rows.add(row("run --tasks", "--tasks needs a value", "run", "--tasks"));
        rows.add(row("run --config", "--config needs a value", "run", "--config"));
        rows.add(row("check --config", "--config needs a value", "check", "t1", "@ws", "--config"));
        rows.add(row("unknown command", "unknown command lst; the commands are list, run, check, report", "lst"));
        rows.add(row("unknown command with a flag", "unknown command lst", "lst", "--repeat", "2"));
        // #609: every command crossed with every flag. A flag the command does not take is refused, even without its
        // value; one it takes is refused when given twice, naming how to give it once.
        TAKES.forEach((command, takes) -> {
            for (String flag : ALL_FLAGS) {
                List<String> args = new ArrayList<>(List.of(command));
                args.addAll(POSITIONAL.get(command));
                if (takes.contains(flag)) {
                    for (int i = 0; i < 2; i++) {
                        args.add(flag);
                        if (!NO_VALUE.contains(flag)) {
                            args.add("@twice-" + i);
                        }
                    }
                    rows.add(row(command + " " + flag + " twice", flag + " is given twice; "
                            + (flag.equals("--tasks") ? "list the tasks in one --tasks a,b"
                            : flag.equals("--modes") ? "list the modes in one --modes m1,m2" : "give it once"),
                            args.toArray(String[]::new)));
                } else {
                    args.add(flag);
                    rows.add(row(command + " " + flag, command + " does not take " + flag + "; " + command
                            + (takes.isEmpty() ? " takes no options" : " takes only " + String.join(", ", takes)),
                            args.toArray(String[]::new)));
                    if (!NO_VALUE.contains(flag)) {
                        args.add("x");
                        rows.add(row(command + " " + flag + " x", command + " does not take " + flag,
                                args.toArray(String[]::new)));
                    }
                }
            }
        });
        rows.add(row("list extra", "usage: list", "list", "x", "--tasks-dir", "@tasks"));
        rows.add(row("run extra", "unexpected x", "run", "x", "--tasks-dir", "@tasks", "--config", "@bench.json"));
        rows.add(row("check 1 arg", "usage: check <task> <workspace>", "check", "t1", "--tasks-dir", "@tasks"));
        rows.add(row("report 0 args", "usage: report <runDir>", "report"));
        // The tasks folder: missing, a file, empty, a folder that holds no tasks (the module folder).
        for (String command : List.of("list", "run", "check")) {
            List<String> rest = command.equals("check") ? List.of("t1", "@ws") : List.of();
            for (String[] folder : new String[][] {{"@missing", "Tasks folder not found"},
                    {"@a-file", "Tasks folder not found"}, {"@empty", "no tasks in"}, {"@not-tasks", "no tasks in"}}) {
                List<String> args = new ArrayList<>(List.of(command));
                args.addAll(rest);
                args.addAll(List.of("--tasks-dir", folder[0]));
                if (!command.equals("list")) {
                    args.addAll(List.of("--config", "@bench.json"));
                }
                rows.add(row(command + " tasks-dir " + folder[0], folder[1], args.toArray(String[]::new)));
            }
        }
        // run's selection: no names, an empty name, an unknown one, one twice; and its other arguments.
        String[] run = {"run", "--tasks-dir", "@tasks", "--config", "@bench.json"};
        rows.add(row("run --tasks ,", "--tasks \",\" names no task", with(run, "--tasks", ",")));
        rows.add(row("run --tasks ''", "--tasks \"\" names no task", with(run, "--tasks", "")));
        rows.add(row("run --tasks ' , '", "--tasks \" , \" names no task", with(run, "--tasks", " , ")));
        rows.add(row("run --tasks t1,,t2", "has an empty task name", with(run, "--tasks", "t1,,t2")));
        rows.add(row("run --tasks t1,", "has an empty task name", with(run, "--tasks", "t1,")));
        rows.add(row("run --tasks nosuch", "--tasks: no task nosuch", with(run, "--tasks", "nosuch")));
        rows.add(row("run --tasks t1,t1", "task \"t1\" is listed twice", with(run, "--tasks", "t1,t1")));
        rows.add(row("run --modes ,", "--modes \",\" names no mode", with(run, "--tasks", "t1", "--modes", ",")));
        rows.add(row("run --modes ''", "--modes \"\" names no mode", with(run, "--modes", "")));
        rows.add(row("run --modes a,,b", "has an empty mode name", with(run, "--modes", "baseline,,baseline")));
        rows.add(row("run --modes nosuch", "--modes: no mode nosuch", with(run, "--modes", "nosuch")));
        rows.add(row("run --modes twice", "mode \"baseline\" is listed twice", with(run, "--modes", "baseline,baseline")));
        rows.add(row("run --repeat 0", "--repeat 0", with(run, "--repeat", "0")));
        rows.add(row("run --repeat x", "--repeat x", with(run, "--repeat", "x")));
        rows.add(row("run --config missing", "missing.json does not exist", "run", "--tasks-dir", "@tasks", "--config",
                "@missing.json"));
        rows.add(row("run --config folder", "is not a file", "run", "--tasks-dir", "@tasks", "--config", "@empty"));
        // #462: a bench.json or task.json value of the wrong type, named with the type its key takes.
        BAD_CONFIGS.forEach((name, config) -> rows.add(row("run bench.json " + name, config[1], "run", "--tasks-dir",
                "@tasks", "--config", "@configs/" + name + ".json")));
        BAD_TASKS.forEach((name, task) -> {
            rows.add(row("list task.json " + name, "Task t1: task.json " + task[1], "list", "--tasks-dir",
                    "@bad-tasks/" + name));
            rows.add(row("run task.json " + name, "Task t1: task.json " + task[1], "run", "--tasks-dir",
                    "@bad-tasks/" + name, "--config", "@bench.json"));
        });
        // #555: a file of the bench's own that is not UTF-8 is named, with where its first bad byte is.
        String notUtf8 = " is not UTF-8 text; save the file as UTF-8";
        for (String command : List.of("list", "run")) {
            List<String> config = command.equals("run") ? List.of("--config", "@bench.json") : List.of();
            rows.add(row(command + " Latin-1 task.json", "task.json: the byte at offset 16" + notUtf8,
                    with(with(new String[] {command}, "--tasks-dir", "@latin1/task-json"), config.toArray(String[]::new))));
            rows.add(row(command + " Latin-1 prompt.md", "prompt.md: the byte at offset 3" + notUtf8,
                    with(with(new String[] {command}, "--tasks-dir", "@latin1/prompt-md"), config.toArray(String[]::new))));
        }
        rows.add(row("run Latin-1 bench.json", "bench.json: the byte at offset 24" + notUtf8, "run", "--tasks-dir",
                "@tasks", "--config", "@latin1/bench.json"));
        rows.add(row("run Latin-1 preamble", "_preamble.md: the byte at offset 3" + notUtf8, "run", "--tasks-dir",
                "@latin1/preamble", "--config", "@bench.json"));
        rows.add(row("run Latin-1 MCP template", "mcp.template.json: the byte at offset 12" + notUtf8, "run",
                "--tasks-dir", "@tasks", "--modes", "toolset", "--config", "@latin1/mcp-bench.json"));
        rows.add(row("check Latin-1 bench.json", "bench.json: the byte at offset 24" + notUtf8, "check", "t1", "@ws",
                "--tasks-dir", "@tasks", "--config", "@latin1/bench.json"));
        rows.add(row("run shipped bench.json unbuilt", "bench.json: paths.coreLib: no such file or folder ",
                "run", "--tasks-dir", "@tasks", "--config", "@fresh/msc-bench/bench.json"));
        rows.add(row("run --modes toolset unbuilt", "run ./gradlew :msc-core:installDist first", "run", "--tasks-dir",
                "@tasks", "--modes", "toolset", "--config", "@fresh/msc-bench/bench.json"));
        // check: its task, its workspace and its --config.
        String[] check = {"check", "--tasks-dir", "@tasks"};
        rows.add(row("check task ''", "the task \"\" names no task", with(check, "", "@ws")));
        rows.add(row("check task ,", "the task \",\" names no task", with(check, ",", "@ws")));
        rows.add(row("check task nosuch", "the task: no task nosuch", with(check, "nosuch", "@ws")));
        rows.add(row("check two tasks", "check takes one task, not 2", with(check, "t1,t2", "@ws")));
        rows.add(row("check missing workspace", "does not exist", with(check, "t1", "@missing")));
        rows.add(row("check file workspace", "is not a folder", with(check, "t1", "@a-file")));
        rows.add(row("check --config missing", "missing.json does not exist",
                with(check, "t1", "@ws", "--config", "@missing.json")));
        // report: its folder, and every kind of results.jsonl it cannot read.
        rows.add(row("report missing folder", "does not exist", "report", "@missing"));
        rows.add(row("report file", "is not a folder", "report", "@a-file"));
        rows.add(row("report no results", "has no results.jsonl", "report", "@run-without-results"));
        BAD_RESULTS.forEach((name, results) -> rows.add(row("report " + name,
                "results.jsonl " + results.says(),
                "report", "@runs/" + name)));
        return rows.stream();
    }

    private static Arguments row(String name, String says, String... args) {
        return Arguments.argumentSet(name, says, args);
    }

    private static String[] with(String[] base, String... more) {
        String[] out = new String[base.length + more.length];
        System.arraycopy(base, 0, out, 0, base.length);
        System.arraycopy(more, 0, out, base.length, more.length);
        return out;
    }

    /** #609: each flag a command takes is read once, with its value, a path's {@code ~} as the home folder. */
    static Stream<Arguments> takenFlags() {
        List<Arguments> rows = new ArrayList<>();
        TAKES.forEach((command, takes) -> takes.forEach(flag -> rows.add(Arguments.argumentSet(command + " " + flag,
                command, flag))));
        return rows.stream();
    }

    @ParameterizedTest
    @MethodSource("takenFlags")
    void aFlagTheCommandTakesIsReadOnce(String command, String flag) {
        Path home = dir.resolve("home");
        List<String> args = new ArrayList<>(List.of(command));
        args.addAll(POSITIONAL.get(command));
        args.add(flag);
        if (!NO_VALUE.contains(flag)) {
            args.add("~/v");
        }

        CommandLine line = CommandLine.parse(args.toArray(String[]::new), home);

        assertThat(line.command()).isEqualTo(command);
        assertThat(line.positional()).isEqualTo(POSITIONAL.get(command));
        String expected = NO_VALUE.contains(flag) ? ""
                : Set.of("--out", "--tasks-dir", "--config").contains(flag) ? home.resolve("v").toString() : "~/v";
        assertThat(line.flags()).containsExactly(Map.entry(flag, expected));
    }

    /** #519: only run needs the toolset built; list and check take the shipped config in a fresh checkout. */
    @Test
    void listAndCheckTakeTheShippedConfigBeforeTheToolsetIsBuilt() throws IOException {
        Files.writeString(dir.resolve("ws/a.txt"), "x");

        Outcome list = exec("list", "--tasks-dir", "@tasks");
        Outcome check = exec("check", "t1", "@ws", "--tasks-dir", "@tasks", "--config", "@fresh/msc-bench/bench.json");

        assertThat(list.exit()).as(list.err()).isZero();
        assertThat(check.exit()).as(check.err()).isZero();
    }

    @ParameterizedTest
    @MethodSource("refusals")
    void aBadInputIsRefusedWithOneLineAndExitCode2AndCreatesNothing(String says, String[] args) throws IOException {
        Map<String, Long> before = tree();

        Outcome outcome = exec(args);

        assertThat(outcome.exit()).as(outcome.err()).isEqualTo(Main.BAD_INPUT);
        assertThat(outcome.err().lines().toList()).singleElement().asString()
                .startsWith("msc-bench: ").contains(says).doesNotContain("Exception", "\tat ");
        assertThat(outcome.out()).isEmpty();
        assertThat(tree()).isEqualTo(before);
    }

    @ParameterizedTest
    @MethodSource("goodResults")
    void reportTakesUtf8WithOrWithoutAByteOrderMarkAndUtf16WithOne(String name) throws IOException {
        Outcome outcome = exec("report", "@good/" + name);

        assertThat(outcome.exit()).as(outcome.err()).isZero();
        assertThat(outcome.err()).isEmpty();
        String report = Files.readString(dir.resolve("good").resolve(name).resolve("report.md"));
        assertThat(report).contains("| t1 | ").contains(name.equals("non-ascii") ? "caf\u00e9 \u2014" : "| code |");
        // A half-written last line is left out with one warning naming it; nothing else warns.
        List<String> warnings = outcome.out().lines().filter(l -> l.startsWith("warning: ")).toList();
        if (name.equals("half-written-last")) {
            assertThat(warnings).singleElement().asString().contains("results.jsonl line 2 is half-written");
        } else if (name.equals("half-written-joined")) {
            assertThat(warnings).singleElement().asString().contains("results.jsonl line 2 starts with a half-written");
            assertThat(report).contains("Runs: 2.");
        } else {
            assertThat(warnings).isEmpty();
        }
    }

    static Stream<String> goodResults() {
        return GOOD_RESULTS.keySet().stream();
    }

    /**
     * #476: the end of a run on a disk that may refuse report.md. Each row: the attempts' verdict (pass, fail, a usage
     * limit, an attempt not recorded) and whether report.md can be written; the exit code, which a refused report never
     * changes, and the stop line, which comes before the report line or the one warning. A refused report leaves the
     * earlier report.md as it was, never a 0-byte file.
     */
    @ParameterizedTest
    @CsvSource({
            "pass, false, 0, ''",
            "pass, true, 0, ''",
            "fail, false, 3, ''",
            "fail, true, 3, ''",
            "limit, false, 4, Stopped early on a usage limit (out of usage)",
            "limit, true, 4, Stopped early on a usage limit (out of usage)",
            "unrecorded, false, 5, Stopped early: results.jsonl could not be written (No space left on device); results.jsonl holds",
            "unrecorded, true, 5, Stopped early: results.jsonl could not be written (No space left on device); results.jsonl holds"})
    void theEndOfARunKeepsItsExitCodeWhenTheReportCannotBeWritten(String verdict, boolean reportFails, int exit,
            String stop) throws IOException {
        Path runDir = Files.createDirectories(dir.resolve("run"));
        Files.writeString(runDir.resolve(Main.REPORT), "earlier report");
        List<RunRecord> records = List.of(new RunRecord("t1", "code", "baseline", 1, "", !verdict.equals("fail"), 0,
                false, 0, null, null, null, null));
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        int code = Main.endRun(records, verdict.equals("unrecorded")
                ? new RunFolderUnwritable(Path.of("results.jsonl"), new IOException("No space left on device")) : null,
                verdict.equals("limit") ? "out of usage" : null, runDir, new PrintStream(out, true, StandardCharsets.UTF_8),
                new FullDisk(reportFails ? Set.of(Main.REPORT) : Set.of(), 0, false));

        assertThat(code).isEqualTo(exit);
        List<String> lines = out.toString(StandardCharsets.UTF_8).lines().toList();
        assertThat(lines).hasSize(stop.isEmpty() ? 1 : 2);
        if (!stop.isEmpty()) {
            assertThat(lines.getFirst()).startsWith(stop);
        }
        assertThat(lines.getLast()).startsWith(reportFails ? "warning: " : "Report: ");
        assertThat(Files.readString(runDir.resolve(Main.REPORT)))
                .startsWith(reportFails ? "earlier report" : "# Benchmark report");
        try (Stream<Path> files = Files.list(runDir)) {
            assertThat(files.map(f -> f.getFileName().toString())).containsExactly(Main.REPORT);
        }
    }

    @Test
    void noCommandPrintsTheUsageAndExits1() {
        Outcome outcome = exec();

        assertThat(outcome.exit()).isEqualTo(Main.USAGE);
        assertThat(outcome.out()).startsWith("usage: msc-bench");
        assertThat(outcome.err()).isEmpty();
    }

    @Test
    void anUnexpectedErrorIsOneLineWithItsOwnExitCode() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exit = Main.guarded(new PrintStream(err, true, StandardCharsets.UTF_8), () -> {
            throw new IllegalStateException("broken\nsecond line");
        });

        assertThat(exit).isEqualTo(Main.INTERNAL_ERROR).isNotIn(Main.OK, Main.USAGE, Main.BAD_INPUT, Main.FAILED,
                Main.USAGE_LIMIT, Main.NOT_WRITTEN);
        assertThat(err.toString(StandardCharsets.UTF_8).lines().toList()).singleElement().asString()
                .isEqualTo("msc-bench: internal error (java.lang.IllegalStateException: broken); please report it");
    }

    /** #508: the last guard of the one-line promise, for a refusal whose message nobody made one line. */
    @Test
    void aRefusalWithLineBreaksIsPrintedAsOneLine() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exit = Main.guarded(new PrintStream(err, true, StandardCharsets.UTF_8), () -> {
            throw new IOException("Bad bench config b.json:\n  a: one\r\n  b: two\u2028three\n");
        });

        assertThat(exit).isEqualTo(Main.BAD_INPUT);
        assertThat(err.toString(StandardCharsets.UTF_8).lines().toList()).singleElement().asString()
                .isEqualTo("msc-bench: Bad bench config b.json: a: one; b: two; three");
    }

    @Test
    void theRealMainExitsWithTheCodeAndOneLine() throws IOException, InterruptedException {
        // The JVM's own entry point, to see main hand exec's code to System.exit and print no trace.
        String java = ProcessHandle.current().info().command().orElse("java");
        Process process = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                "-Duser.home=" + dir.resolve("home"), Main.class.getName(), "report", dir.resolve("runs/null").toString())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
        String err = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);

        assertThat(process.waitFor(2, TimeUnit.MINUTES)).isTrue();
        assertThat(process.exitValue()).as(err).isEqualTo(Main.BAD_INPUT);
        assertThat(err.lines().filter(l -> !l.startsWith("Picked up ")).toList()).singleElement().asString()
                .startsWith("msc-bench: ").contains("line 1: is null");
    }

    private record Outcome(int exit, String out, String err) {
    }

    private Outcome exec(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        String[] resolved = Stream.of(args).map(a -> a.startsWith("@") ? dir.resolve(a.substring(1)).toString() : a)
                .toArray(String[]::new);
        int exit = Main.exec(resolved, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8), dir.resolve("home"), dir.resolve("temp"));
        return new Outcome(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    /** Every file and folder under the test folder, with each file's size. */
    private Map<String, Long> tree() throws IOException {
        Map<String, Long> out = new TreeMap<>();
        try (Stream<Path> paths = Files.walk(dir)) {
            for (Path p : paths.toList()) {
                out.put(dir.relativize(p).toString(), Files.isDirectory(p) ? -1 : Files.size(p));
            }
        }
        return out;
    }
}
