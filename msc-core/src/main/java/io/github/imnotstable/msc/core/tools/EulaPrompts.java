package io.github.imnotstable.msc.core.tools;

import io.github.imnotstable.msc.core.CoreConfig;
import io.github.imnotstable.msc.core.server.EulaCommand;
import io.github.imnotstable.msc.core.server.MachineEula;
import io.github.imnotstable.msc.core.server.Platform;
import java.io.IOException;
import java.time.Instant;

/** The EULA question and the one-time, per-machine acceptance (ADR 0020), shared by scaffold, setup and start. */
final class EulaPrompts {
    static final String EULA_ARG = "Only on the user's yes to https://aka.ms/MinecraftEULA; kept for this machine";
    /**
     * The exact question to put to the user, so their answer reads as an agreement and not as a reply to something
     * vaguer.
     */
    static final String EULA_QUESTION = "ask the user exactly: \"To run a Minecraft server you must agree to the "
            + "Minecraft EULA (https://aka.ms/MinecraftEULA). Do you agree to it? (yes/no)\" Only their own yes "
            + "counts; never answer for them. If they are away, a minestom-server project needs no EULA.";

    private final CoreConfig config;

    EulaPrompts(CoreConfig config) {
        this.config = config;
    }

    /** The one-time, per-machine alternative to asking for every server. Only the owner can run it. */
    String oneTimeHint() {
        return " To never be asked again on this machine, they can instead run once in their own terminal: "
                + MachineEula.acceptCommand(config.installDir());
    }

    /**
     * acceptEula=true is the user's own yes: keep it for this machine too, so later servers here need no question.
     * Returns a line for the result (with the EULA link), or "" when there is no machine file to write.
     */
    String recordForMachine() throws IOException {
        if (config.eulaFile() == null || MachineEula.read(config.eulaFile()).isPresent()) {
            return "";
        }
        MachineEula.write(config.eulaFile(), new MachineEula.Acceptance(System.getProperty("user.name"),
                EulaCommand.hostName(), Instant.now()), MachineEula.VIA_TOOL);
        // Shown once, the first time: what the user's yes now covers. Tell them.
        return "Minecraft EULA (" + MachineEula.EULA_URL + ") accepted for this machine, saved in "
                + config.eulaFile() + ". Tell the user: by this they confirm they read and accept Mojang's EULA, and "
                + "MSC will now write eula=true for every " + Platform.names(Platform::needsEula, "and")
                + " server on this machine without asking. "
                + "Terms: " + MachineEula.TERMS_URL + "\n";
    }
}
