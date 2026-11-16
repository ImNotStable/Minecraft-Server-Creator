package io.github.imnotstable.msc.core.e2e;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * A minimal MCP client over a child process's stdin and stdout, the way Claude Code talks to an installed core.
 * Responses are matched to requests by id; anything else the core sends (notifications, logs) is skipped.
 */
final class McpStdioClient implements AutoCloseable {
    /** One tools/call result: the joined text, whether it carried an image, and the isError flag. */
    record Result(String text, boolean image, boolean error) {
    }

    private final Process process;
    private final OutputStream in;
    private final Map<Integer, CompletableFuture<JsonObject>> pending = new ConcurrentHashMap<>();
    private int nextId = 1;

    McpStdioClient(Process process) {
        this.process = process;
        this.in = process.getOutputStream();
        Thread reader = new Thread(this::readLoop, "mcp-e2e-reader");
        reader.setDaemon(true);
        reader.start();
    }

    private void readLoop() {
        try (BufferedReader out = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = out.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                JsonObject message = JsonParser.parseString(line).getAsJsonObject();
                if (message.has("id") && (message.has("result") || message.has("error"))) {
                    CompletableFuture<JsonObject> waiting = pending.remove(message.get("id").getAsInt());
                    if (waiting != null) {
                        waiting.complete(message);
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            pending.values().forEach(f -> f.completeExceptionally(e));
        }
        IOException closed = new IOException("msc-core closed stdout (exit " + exitCode() + ")");
        pending.values().forEach(f -> f.completeExceptionally(closed));
    }

    private String exitCode() {
        return process.isAlive() ? "still running" : String.valueOf(process.exitValue());
    }

    void initialize() throws Exception {
        JsonObject params = new JsonObject();
        params.addProperty("protocolVersion", "2025-06-18");
        params.add("capabilities", new JsonObject());
        JsonObject client = new JsonObject();
        client.addProperty("name", "msc-e2e");
        client.addProperty("version", "1");
        params.add("clientInfo", client);
        request("initialize", params, Duration.ofSeconds(60));
        JsonObject note = new JsonObject();
        note.addProperty("jsonrpc", "2.0");
        note.addProperty("method", "notifications/initialized");
        send(note);
    }

    /** Calls a tool. Arguments alternate name, value; a value may be a String, Number, Boolean or String[]. */
    Result call(String tool, Duration timeout, Object... arguments) throws Exception {
        JsonObject args = new JsonObject();
        for (int i = 0; i < arguments.length; i += 2) {
            String key = (String) arguments[i];
            switch (arguments[i + 1]) {
                case String s -> args.addProperty(key, s);
                case Number n -> args.addProperty(key, n);
                case Boolean b -> args.addProperty(key, b);
                case String[] list -> {
                    JsonArray array = new JsonArray();
                    for (String s : list) {
                        array.add(s);
                    }
                    args.add(key, array);
                }
                default -> throw new IllegalArgumentException("unsupported argument " + key);
            }
        }
        JsonObject params = new JsonObject();
        params.addProperty("name", tool);
        params.add("arguments", args);
        JsonObject result = request("tools/call", params, timeout).getAsJsonObject("result");
        StringBuilder text = new StringBuilder();
        boolean image = false;
        for (JsonElement e : result.getAsJsonArray("content")) {
            JsonObject part = e.getAsJsonObject();
            switch (part.get("type").getAsString()) {
                case "text" -> text.append(text.isEmpty() ? "" : "\n").append(part.get("text").getAsString());
                case "image" -> image = !part.get("data").getAsString().isEmpty();
                default -> {
                }
            }
        }
        return new Result(text.toString(), image, result.has("isError") && result.get("isError").getAsBoolean());
    }

    private JsonObject request(String method, JsonObject params, Duration timeout) throws Exception {
        int id;
        synchronized (this) {
            id = nextId++;
        }
        CompletableFuture<JsonObject> response = new CompletableFuture<>();
        pending.put(id, response);
        JsonObject message = new JsonObject();
        message.addProperty("jsonrpc", "2.0");
        message.addProperty("id", id);
        message.addProperty("method", method);
        message.add("params", params);
        send(message);
        JsonObject answer;
        try {
            answer = response.get(timeout.toSeconds(), TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            pending.remove(id);
            throw new AssertionError(method + " " + params.get("name") + " got no answer in " + timeout, e);
        }
        if (answer.has("error")) {
            throw new AssertionError(method + " failed: " + answer.get("error"));
        }
        return answer;
    }

    private synchronized void send(JsonObject message) throws IOException {
        in.write((message + "\n").getBytes(StandardCharsets.UTF_8));
        in.flush();
    }

    /** Closes stdin, which ends an MCP stdio session, and waits for the core to exit. Returns its exit code. */
    int closeAndWait(Duration timeout) throws Exception {
        in.close();
        if (!process.waitFor(timeout.toSeconds(), TimeUnit.SECONDS)) {
            throw new AssertionError("msc-core did not exit within " + timeout + " after stdin closed");
        }
        return process.exitValue();
    }

    @Override
    public void close() {
        try {
            in.close();
        } catch (IOException ignored) {
            // Already closed.
        }
        if (process.isAlive()) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        }
    }
}
