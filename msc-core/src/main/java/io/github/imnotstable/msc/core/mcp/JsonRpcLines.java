package io.github.imnotstable.msc.core.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import io.github.imnotstable.msc.protocol.Limits;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * Stdin as the SDK's stdio transport may read it: one JSON-RPC message per line. The SDK (mcp 2.1.0) stops reading
 * for good at the first line it cannot take as one message, silently, while stdin stays open (#283). So each line is
 * checked here the way the SDK will read it: a batch array goes on as its messages, one per line, and anything else
 * that is not a message (not JSON, not JSON-RPC, longer than the SDK accepts) is dropped with a note on stderr.
 */
final class JsonRpcLines extends InputStream {
    /** The SDK's own limit per line (StdioServerTransportProvider's default input size), in chars. */
    static final int MAX_LINE = 16 * 1024 * 1024;

    private final BufferedReader in;
    private final PrintStream notes;
    private final McpJsonMapper mapper = McpJsonDefaults.getMapper();
    private byte[] pending = new byte[0];
    private int pos;

    JsonRpcLines(InputStream in, PrintStream notes) {
        this.in = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        this.notes = notes;
    }

    @Override
    public int read() throws IOException {
        if (!fill()) {
            return -1;
        }
        return pending[pos++] & 0xff;
    }

    @Override
    public int read(byte[] buf, int off, int len) throws IOException {
        if (len == 0) {
            return 0;
        }
        if (!fill()) {
            return -1;
        }
        int n = Math.min(len, pending.length - pos);
        System.arraycopy(pending, pos, buf, off, n);
        pos += n;
        return n;
    }

    @Override
    public int available() {
        return pending.length - pos;
    }

    @Override
    public void close() throws IOException {
        in.close();
    }

    /** True when there are bytes to hand out; reads lines until one passes, or the input ends. */
    private boolean fill() throws IOException {
        while (pos >= pending.length) {
            String line = readLine();
            if (line == null) {
                return false;
            }
            pending = accept(line).getBytes(StandardCharsets.UTF_8);
            pos = 0;
        }
        return true;
    }

    /** The next line, or null at the end; a line over {@link #MAX_LINE} comes back empty (so it is dropped). */
    private String readLine() throws IOException {
        StringBuilder line = new StringBuilder();
        boolean tooLong = false;
        int c;
        while ((c = in.read()) >= 0 && c != '\n') {
            if (line.length() < MAX_LINE) {
                line.append((char) c);
            } else {
                tooLong = true;
            }
        }
        if (c < 0 && line.isEmpty()) {
            return null;
        }
        if (tooLong) {
            note("a line longer than " + MAX_LINE + " chars");
            return "";
        }
        return line.toString();
    }

    /** What to pass on for one input line: its messages, each ending in a newline; nothing when it holds none. */
    private String accept(String line) {
        String text = line.strip();
        if (text.isEmpty()) {
            return "";
        }
        if (text.startsWith("[")) {
            JsonArray batch;
            try {
                batch = JsonParser.parseString(text).getAsJsonArray();
            } catch (JsonParseException | IllegalStateException e) {
                note(text);
                return "";
            }
            StringBuilder out = new StringBuilder();
            for (JsonElement message : batch) {
                out.append(message(message.toString()));
            }
            if (batch.isEmpty()) {
                note(text);
            }
            return out.toString();
        }
        return message(text);
    }

    private String message(String text) {
        try {
            // The SDK's own parse: what passes here, it reads too.
            McpSchema.deserializeJsonRpcMessage(mapper, text);
            return text + "\n";
        } catch (Exception e) {
            note(text);
            return "";
        }
    }

    private void note(String dropped) {
        String shown = Limits.shorten(dropped, 123);
        notes.println("msc-core: ignored stdin input that is not a JSON-RPC message: " + shown);
    }
}
