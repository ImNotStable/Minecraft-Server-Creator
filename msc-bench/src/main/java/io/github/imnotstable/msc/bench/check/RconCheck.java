package io.github.imnotstable.msc.bench.check;

import java.io.IOException;
import java.util.regex.Pattern;

/** Runs a console command over RCON and passes when the reply matches a pattern. */
record RconCheck(String name, String command, Pattern expect) implements Check {

    @Override
    public boolean needsServer() {
        return true;
    }

    @Override
    public CheckResult run(CheckContext context) {
        try {
            String reply = stripFormatting(context.rcon().command(command));
            return expect.matcher(reply).find()
                    ? CheckResult.pass(name, "reply: " + reply)
                    : CheckResult.fail(name, "reply \"" + reply + "\" has no match for /" + expect.pattern() + "/");
        } catch (IOException e) {
            return CheckResult.fail(name, "RCON failed: " + e.getMessage());
        }
    }

    /** Removes legacy section-sign colour codes, which Paper adds to some console replies. */
    static String stripFormatting(String text) {
        return text.replaceAll("§.", "").strip();
    }
}
