package io.github.imnotstable.msc.bench.run;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * The arguments other processes started with, as whole arguments, for the command-line proof of
 * {@link AttemptProcesses} where the system shows no environment (#595). Linux and macOS give them through
 * {@link ProcessHandle.Info#arguments()}. Windows gives Java at most a command line, often nothing; there they come
 * from one {@code Win32_Process} query per snapshot, and each command line is split the way a C program (the Java
 * launcher) splits its own.
 */
final class ProcessArguments {

    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT)
            .startsWith("windows");

    /** Every process's id and command line, as JSON, in UTF-8. */
    private static final String QUERY = "[Console]::OutputEncoding = [Text.Encoding]::UTF8; "
            + "Get-CimInstance Win32_Process | Select-Object ProcessId, CommandLine | ConvertTo-Json -Compress";

    private ProcessArguments() {
    }

    /**
     * A view of the processes' arguments (without the program), read once when first needed and then kept, so a
     * pass over all processes queries the system at most once. Empty for a process the system shows none of.
     */
    static Function<ProcessHandle, Optional<List<String>>> snapshot() {
        return new Function<>() {
            private Map<Long, List<String>> windows;

            @Override
            public Optional<List<String>> apply(ProcessHandle process) {
                ProcessHandle.Info info = process.info();
                if (info.arguments().isPresent()) {
                    return Optional.of(List.of(info.arguments().get()));
                }
                if (!WINDOWS) {
                    return Optional.empty();
                }
                if (info.commandLine().isPresent()) {
                    return Optional.of(withoutProgram(splitWindows(info.commandLine().get())));
                }
                if (windows == null) {
                    windows = queryWindows();
                }
                return Optional.ofNullable(windows.get(process.pid()));
            }
        };
    }

    /** The command lines Windows shows, empty when the query fails. */
    private static Map<Long, List<String>> queryWindows() {
        String encoded = Base64.getEncoder().encodeToString(QUERY.getBytes(StandardCharsets.UTF_16LE));
        Process process = null;
        try {
            process = WindowsCommandLine.prepare(new ProcessBuilder("powershell.exe", "-NoProfile",
                    "-NonInteractive", "-EncodedCommand", encoded).redirectError(ProcessBuilder.Redirect.DISCARD)
                    .redirectInput(ProcessBuilder.Redirect.PIPE)).start();
            process.getOutputStream().close();
            Process started = process;
            CompletableFuture<byte[]> output = CompletableFuture.supplyAsync(() -> {
                try {
                    return started.getInputStream().readAllBytes();
                } catch (IOException e) {
                    return new byte[0];
                }
            });
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                return Map.of();
            }
            return parseWindows(new String(output.get(10, TimeUnit.SECONDS), StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Map.of();
        } catch (Exception e) {
            return Map.of();
        } finally {
            if (process != null) {
                process.destroyForcibly();
            }
        }
    }

    /**
     * The arguments (without the program) by process id, from {@code ConvertTo-Json} output: one object or a list of
     * them, each with {@code ProcessId} and {@code CommandLine} (null for a process the user may not read).
     */
    static Map<Long, List<String>> parseWindows(String json) {
        JsonElement tree;
        try {
            tree = JsonParser.parseString(json.strip().replace("﻿", ""));
        } catch (JsonParseException e) {
            return Map.of();
        }
        List<JsonElement> entries = new ArrayList<>();
        if (tree.isJsonArray()) {
            tree.getAsJsonArray().forEach(entries::add);
        } else {
            entries.add(tree);
        }
        Map<Long, List<String>> found = new HashMap<>();
        for (JsonElement entry : entries) {
            if (entry instanceof JsonObject object && object.get("ProcessId") instanceof JsonElement id
                    && id.isJsonPrimitive() && id.getAsJsonPrimitive().isNumber()
                    && object.get("CommandLine") instanceof JsonElement line && line.isJsonPrimitive()
                    && line.getAsJsonPrimitive().isString()) {
                found.put(id.getAsLong(), withoutProgram(splitWindows(line.getAsString())));
            }
        }
        return found;
    }

    /**
     * Splits a Windows command line into its arguments as the C runtime does: blanks separate arguments outside
     * quotes; {@code 2n} backslashes before a quote give {@code n} backslashes and open or close a quoted part,
     * {@code 2n+1} give {@code n} and a literal quote; {@code ""} inside a quoted part is a literal quote; other
     * backslashes are literal. The program comes first, as written.
     */
    static List<String> splitWindows(String line) {
        List<String> arguments = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inArgument = false;
        boolean quoted = false;
        int i = 0;
        int n = line.length();
        while (i < n) {
            char c = line.charAt(i);
            if (c == '\\') {
                int start = i;
                while (i < n && line.charAt(i) == '\\') {
                    i++;
                }
                int count = i - start;
                if (i < n && line.charAt(i) == '"') {
                    current.append("\\".repeat(count / 2));
                    if (count % 2 == 1) {
                        current.append('"');
                        i++;
                    }
                } else {
                    current.append("\\".repeat(count));
                }
                inArgument = true;
            } else if (c == '"') {
                if (quoted && i + 1 < n && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i += 2;
                } else {
                    quoted = !quoted;
                    i++;
                }
                inArgument = true;
            } else if ((c == ' ' || c == '\t') && !quoted) {
                if (inArgument) {
                    arguments.add(current.toString());
                    current.setLength(0);
                    inArgument = false;
                }
                i++;
            } else {
                current.append(c);
                inArgument = true;
                i++;
            }
        }
        if (inArgument) {
            arguments.add(current.toString());
        }
        return arguments;
    }

    private static List<String> withoutProgram(List<String> arguments) {
        return arguments.isEmpty() ? arguments : List.copyOf(arguments.subList(1, arguments.size()));
    }
}
