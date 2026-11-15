package io.github.imnotstable.msc.bench.run;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs each agent in its own Linux PID namespace ({@code run --isolate}). The agent may run any command, and an agent
 * that cleans up with {@code killall -9 java} kills every Java process it can see: the bench itself, Gradle daemons,
 * other sessions' msc-core. In its own namespace it sees only its own processes, and when it exits the kernel ends
 * every process left in it, msc-core included (ADR 0044).
 */
public final class PidNamespace {

    private PidNamespace() {
    }

    /**
     * The command prefix for this user: root needs no user namespace; anyone else gets one that maps only their own
     * user, so the agent does not run as root (Claude Code refuses {@code --dangerously-skip-permissions} as root).
     */
    static List<String> prefix(boolean root) {
        List<String> out = new ArrayList<>(List.of("unshare"));
        if (!root) {
            out.addAll(List.of("--user", "--map-current-user"));
        }
        out.addAll(List.of("--pid", "--fork", "--mount-proc", "--kill-child"));
        return out;
    }

    /**
     * The prefix, after one trial run of {@code unshare ... true}; refused with the reason when this system cannot
     * create the namespace (not Linux, no {@code unshare}, unprivileged user namespaces turned off).
     */
    public static List<String> require() throws IOException {
        if (!System.getProperty("os.name", "").toLowerCase().startsWith("linux")) {
            throw new IllegalArgumentException("--isolate needs Linux (a PID namespace through unshare)");
        }
        List<String> prefix = prefix(isRoot());
        List<String> trial = new ArrayList<>(prefix);
        trial.add("true");
        String output;
        int exit;
        try {
            Process process = new ProcessBuilder(trial).redirectErrorStream(true).start();
            output = new String(process.getInputStream().readAllBytes()).strip();
            exit = process.waitFor(30, TimeUnit.SECONDS) ? process.exitValue() : -1;
        } catch (IOException e) {
            throw new IllegalArgumentException("--isolate needs unshare (util-linux): " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
        if (exit != 0) {
            throw new IllegalArgumentException("--isolate: " + String.join(" ", trial) + " failed ("
                    + (output.isEmpty() ? "exit " + exit : output.lines().findFirst().orElse(output))
                    + "); on Ubuntu 24.04 unprivileged user namespaces may be off "
                    + "(sysctl kernel.apparmor_restrict_unprivileged_userns)");
        }
        return prefix;
    }

    private static boolean isRoot() {
        try {
            Process process = new ProcessBuilder("id", "-u").redirectErrorStream(true).start();
            return new String(process.getInputStream().readAllBytes()).strip().equals("0");
        } catch (IOException e) {
            return false;
        }
    }
}
