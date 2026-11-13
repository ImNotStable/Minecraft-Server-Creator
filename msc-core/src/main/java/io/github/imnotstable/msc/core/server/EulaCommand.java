package io.github.imnotstable.msc.core.server;

import java.io.Console;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Optional;

/**
 * {@code Main eula accept|status|revoke}: the owner's own command for {@link MachineEula}. It is a command line mode
 * of {@code Main}, not an MCP tool, and {@code accept} reads the answer from an interactive terminal only, so neither
 * a tool call nor a piped script can agree for the owner.
 */
public final class EulaCommand {
    /** Where the command talks to the owner. */
    public interface Terminal {
        /** True only for a real terminal with a person at the keyboard, not piped input. */
        boolean interactive();

        /** Shows {@code prompt} and reads one line, or null at end of input. */
        String readLine(String prompt);

        void println(String line);
    }

    private EulaCommand() {
    }

    /** The process console, when stdin and stdout are a terminal. */
    public static Terminal console() {
        Console c = System.console();
        return new Terminal() {
            @Override
            public boolean interactive() {
                return c != null && c.isTerminal();
            }

            @Override
            public String readLine(String prompt) {
                return c == null ? null : c.readLine("%s", prompt);
            }

            @Override
            public void println(String line) {
                System.out.println(line);
            }
        };
    }

    /** The name the acceptance is recorded under: this machine's host name, or "this machine". */
    public static String hostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (IOException e) {
            return "this machine";
        }
    }

    /**
     * Runs {@code eula <subcommand>}.
     *
     * @param args the arguments after {@code eula}
     * @return the process exit code: 0 done, 1 not accepted, 2 usage or refused
     */
    public static int run(List<String> args, Path file, Path installDir, Terminal t, Clock clock, String user,
                          String host) throws IOException {
        String sub = args.isEmpty() ? "" : args.getFirst();
        switch (sub) {
            case "status" -> {
                Optional<MachineEula.Acceptance> a = MachineEula.read(file);
                t.println(a.map(x -> "Minecraft EULA accepted for this machine by " + x.describe() + " (" + file + ")")
                        .orElse("Minecraft EULA not accepted for this machine (" + file + " missing). Each "
                                + Platform.names(Platform::needsEula, "or") + " server then needs your yes. To accept "
                                + "once: " + MachineEula.acceptCommand(installDir)));
                return a.isPresent() ? 0 : 1;
            }
            case "revoke" -> {
                boolean had = Files.deleteIfExists(file);
                t.println(had ? "Removed " + file + ". New dev servers will need your yes again; servers that already "
                        + "have eula=true in their eula.txt keep it." : "Nothing to revoke: " + file + " does not exist.");
                return 0;
            }
            case "accept" -> {
                return accept(file, installDir, t, clock, user, host);
            }
            default -> {
                t.println("usage: eula accept | status | revoke");
                return 2;
            }
        }
    }

    private static int accept(Path file, Path installDir, Terminal t, Clock clock, String user, String host)
            throws IOException {
        Optional<MachineEula.Acceptance> already = MachineEula.read(file);
        if (already.isPresent()) {
            t.println("Already accepted for this machine by " + already.get().describe() + " (" + file + ").");
            return 0;
        }
        if (!t.interactive()) {
            t.println("Refused: 'eula accept' must be run by you in an interactive terminal, because only your own "
                    + "answer counts. It does not read piped input, and no MSC tool can run it. Command: "
                    + MachineEula.acceptCommand(installDir));
            return 2;
        }
        t.println(Platform.names(Platform::needsEula, "and") + " dev servers run Mojang's Minecraft server,");
        t.println("which needs your agreement to the Minecraft End User License Agreement: " + MachineEula.EULA_URL);
        t.println("Agreeing here records it once for user " + user + " on " + host + " (" + file + "). MSC then");
        t.println("writes eula=true into each dev server it sets up on this machine, without asking again.");
        t.println("Terms: " + MachineEula.TERMS_URL);
        t.println("Undo it any time with 'eula revoke'.");
        String answer = t.readLine("Have you read the Minecraft EULA, and do you agree to it? Type yes to agree: ");
        if (answer == null || !answer.strip().equalsIgnoreCase("yes")) {
            t.println("Not accepted; nothing was written.");
            return 1;
        }
        MachineEula.write(file, new MachineEula.Acceptance(user, host, clock.instant()), MachineEula.VIA_TERMINAL);
        t.println("Accepted. Saved to " + file + ".");
        return 0;
    }
}
