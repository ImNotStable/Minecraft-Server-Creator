package io.github.imnotstable.msc.core.tool;

import com.google.gson.JsonObject;
import io.github.imnotstable.msc.core.metrics.ToolLog;
import io.github.imnotstable.msc.protocol.transport.OpException;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/** All tools by name. Every call goes through {@link #call}, which turns failures into text and logs the call. */
public final class ToolRegistry {
    /** What the model sees. {@code ok} is false when the text is an error; {@code png} is null without an image. */
    public record Result(String text, boolean ok, byte[] png) {
        public Result(String text, boolean ok) {
            this(text, ok, null);
        }
    }

    private final Map<String, Tool> tools = new LinkedHashMap<>();
    private final ToolLog log;
    private final Repeats repeats = new Repeats();

    public ToolRegistry(ToolLog log) {
        this.log = log;
    }

    private final AtomicReference<Supplier<String>> notice = new AtomicReference<>();

    /**
     * A note put in front of the next tool result only, so setup warnings reach the AI once without a call. It counts
     * toward that result's cap ({@link #fit}).
     */
    public void noticeOnce(String text) {
        notice.set(() -> text);
    }

    /**
     * A note for the next tool result only, worked out when that call has run, so it describes the state the call
     * left (a {@code msc_workspace} switch, #633); null or empty for none. A note already waiting is kept: it is
     * worked out then too.
     */
    public void noticeUnlessPending(Supplier<String> text) {
        notice.compareAndSet(null, text);
    }

    public ToolRegistry add(Tool tool) {
        if (tools.putIfAbsent(tool.name(), tool) != null) {
            throw new IllegalStateException("duplicate tool " + tool.name());
        }
        return this;
    }

    public ToolRegistry add(String name, String description, Schema schema, Tool.Handler handler) {
        return add(new Tool(name, description, schema.build(), handler));
    }

    public ToolRegistry addImage(String name, String description, Schema schema, Tool.ImageHandler handler) {
        return add(new Tool(name, description, schema.build(), handler));
    }

    /**
     * Makes each of {@code names}, all registered already, answer {@code refusal}'s line before its handler runs, while
     * that line is not null. The handler is wrapped, so a registry that forwards to these tools' handlers gets it too.
     */
    public ToolRegistry gate(Collection<String> names, java.util.function.Supplier<String> refusal) {
        for (String name : names) {
            Tool tool = tools.get(name);
            if (tool == null) {
                throw new IllegalStateException("no tool " + name + " to gate");
            }
            Tool.Handler inner = tool.handler();
            tools.put(name, new Tool(name, tool.description(), tool.inputSchema(), new Tool.Handler() {
                @Override
                public String call(Args args) throws Exception {
                    return output(args).text();
                }

                @Override
                public Tool.Output output(Args args) throws Exception {
                    String refused = refusal.get();
                    if (refused != null) {
                        throw new ToolException(refused);
                    }
                    return inner.output(args);
                }
            }));
        }
        return this;
    }

    public Collection<Tool> tools() {
        return tools.values();
    }

    /**
     * The one cap rule for every result ({@link Budget#cap}), applied by {@link #fit}. A tool spends the cap as one budget ({@link Budget.Parts}
     * for a result of several parts), so its own cuts keep their hints and closing lines; this cut is only the safety
     * net for a result that came out longer anyway. Its hint never says to raise maxChars: a result that outgrew its
     * cap would outgrow a larger one too (#473).
     */
    private static int cap(Tool tool, Args args) {
        try {
            return tool == null ? Budget.DEFAULT_CHARS : Budget.cap(tool, args);
        } catch (ToolException e) {
            return Budget.DEFAULT_CHARS;
        }
    }

    private static final String NARROW = "Narrow the call.";

    /**
     * The lines of {@code notice} that {@code result} does not hold already, or null for none: msc_status lists the
     * setup warnings itself, so as the first call it shows each once, not once more in front (#637).
     */
    static String unsaid(String notice, String result) {
        Set<String> said = new HashSet<>(result.lines().toList());
        String rest = notice.lines().filter(line -> line.isBlank() || !said.contains(line))
                .collect(Collectors.joining("\n"));
        return rest.isBlank() ? null : rest;
    }

    /**
     * The final text of a call in at most {@code cap} characters: the result, with the one-time {@code notice} (or
     * null) in front of it (#607). Both share the cap as one budget ({@link Budget#split}); a notice cut to its share
     * points at msc_status, which repeats it, and a result cut to its share keeps its last line, the closing line a
     * tool's own budget put there.
     */
    static String fit(String notice, String result, int cap) {
        if (notice == null) {
            return Budget.truncate(result, cap, NARROW);
        }
        int[] shares = Budget.split(cap - 1, notice.length(), result.length());
        String head = Budget.truncate(notice, shares[0], "msc_status shows it all.");
        int room = head.isEmpty() ? cap : cap - head.length() - 1;
        String body = result;
        if (result.length() > room) {
            int lastBreak = result.lastIndexOf('\n');
            String last = result.substring(lastBreak + 1);
            body = lastBreak > 0 && !last.isBlank() && last.length() + 1 <= room / 2
                    ? Budget.truncate(result.substring(0, lastBreak), room - last.length() - 1, NARROW) + "\n" + last
                    : Budget.truncate(result, room, NARROW);
        }
        return head.isEmpty() ? body : head + "\n" + body;
    }

    public Result call(String name, JsonObject arguments) {
        long start = System.nanoTime();
        JsonObject args = arguments == null ? new JsonObject() : arguments;
        String text;
        boolean ok = false;
        Tool.Output output = null;
        Tool tool = tools.get(name);
        if (tool == null) {
            text = "error: unknown tool " + name;
        } else {
            try {
                ArgCheck.check(tool, args);
                output = tool.handler().output(new Args(args));
                text = output.text();
                ok = true;
            } catch (ToolException e) {
                text = "error: " + e.getMessage();
            } catch (OpException e) {
                text = "error: agent answered " + e.code() + ": " + e.detail();
            } catch (java.nio.file.FileSystemException e) {
                // A file the call named or needs could not be read or written (access denied, a folder in the way):
                // the caller can act on that, so it is not an internal error.
                text = "error: " + PathArg.reason(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                text = "error: interrupted";
            } catch (Exception e) {
                text = "error: internal: " + e;
            }
        }
        String again = repeats.check(name, args.toString(), ok ? text : null, output == null ? null : output.png());
        if (again != null) {
            output = new Tool.Output(again, null, 0);
            text = again;
        }
        Supplier<String> queued = notice.getAndSet(null);
        String pending = null;
        try {
            pending = queued == null ? null : queued.get();
        } catch (RuntimeException e) {
            // The call already ran: a notice that cannot be worked out (a folder gone mid-walk) is dropped, not its result.
        }
        pending = pending == null ? null : unsaid(pending, text);
        // The one place every result passes: the cap applies to the final text, the notice included.
        text = fit(pending, text, cap(tool, new Args(args)));
        long ms = (System.nanoTime() - start) / 1_000_000;
        log.record(name, ok, ms, args.toString().length(), text.length(), ok ? null : text,
                output == null ? 0 : output.imageTokens());
        return new Result(text, ok, output == null ? null : output.png());
    }
}
