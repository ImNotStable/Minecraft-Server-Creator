package io.github.imnotstable.msc.process;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * The system's processes through {@link ProcessHandle}. What the JDK guarantees, from {@code ProcessHandleImpl}
 * (JDK 25): a handle holds the pid and the start time it saw; {@code destroyForcibly()} signals only when the pid's
 * current start time equals it (or either is unknown, 0); {@code info()} is blanked when they differ; and
 * {@code descendants()} keeps a child only when it started no earlier than its parent's start time as found in the
 * same snapshot, which is lost when the parent pid is missing from it (start 0 then admits every process recording
 * that pid, #357). So {@code descendants()} is called only on a handle that is alive just before and just after the
 * call: a process cannot come back to life, so it was in that snapshot. {@link TreeKill} then checks every edge again.
 */
final class LiveProcessTable implements ProcessTable {
    private final Map<Proc, ProcessHandle> handles = new HashMap<>();

    /** The identity of {@code h}, remembered for later signals; empty when its start instant is unknown or it died. */
    Optional<Proc> add(ProcessHandle h) {
        Optional<Proc> proc = h.info().startInstant().map(start -> new Proc(h.pid(), start));
        proc.ifPresent(p -> handles.putIfAbsent(p, h));
        return proc;
    }

    @Override
    public List<Row> snapshot(Collection<Proc> seeds) {
        Map<Proc, Row> rows = new LinkedHashMap<>();
        for (Proc seed : seeds) {
            ProcessHandle h = handles.get(seed);
            if (rows.containsKey(seed) || h == null || !alive(seed)) {
                continue; // Covered by an ancestor's call, or gone: a dead seed's descendants() is never asked.
            }
            List<ProcessHandle> found = h.descendants().toList();
            if (!alive(seed)) {
                continue;
            }
            rows.put(seed, new Row(seed, parent(h)));
            for (ProcessHandle d : found) {
                add(d).ifPresent(p -> rows.putIfAbsent(p, new Row(p, parent(d))));
            }
        }
        return List.copyOf(rows.values());
    }

    private static long parent(ProcessHandle h) {
        return h.parent().map(ProcessHandle::pid).orElse(-1L);
    }

    @Override
    public boolean alive(Proc p) {
        ProcessHandle h = handles.get(p);
        return h != null && h.isAlive() && h.info().startInstant().filter(p.start()::equals).isPresent();
    }

    @Override
    public boolean kill(Proc p) {
        return alive(p) && handles.get(p).destroyForcibly();
    }

    @Override
    public boolean stop(List<Proc> ps) {
        return signal("STOP", ps);
    }

    @Override
    public void resume(List<Proc> ps) {
        signal("CONT", ps);
    }

    /**
     * Sends {@code SIG<name>} with the shell's {@code kill} to those of {@code ps} alive just before; false when that
     * could not run. The pid is checked by identity first, and Unix hands out pids in sequence, so a pid freed in the
     * moment between the check and the signal is not given to another process before the counter wraps.
     */
    private boolean signal(String name, List<Proc> ps) {
        List<String> command = new ArrayList<>(List.of("/bin/sh", "-c", "kill -" + name + " \"$@\" 2>/dev/null", "sh"));
        ps.stream().filter(this::alive).forEach(p -> command.add(Long.toString(p.pid())));
        if (command.size() == 4) {
            return true;
        }
        try {
            Process kill = new ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            kill.getOutputStream().close();
            if (kill.waitFor(10, TimeUnit.SECONDS)) {
                return true;
            }
            kill.destroyForcibly();
            return false;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
