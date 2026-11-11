package io.github.imnotstable.msc.bench.check;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Checks blocks in the live world with vanilla {@code execute if block}, so it needs nothing but
 * RCON. A block string may carry a partial state ({@code minecraft:oak_door[half=lower]}) to match
 * any block with those properties.
 */
record BlocksCheck(String name, List<Expectation> expectations, int maxFailuresShown) implements Check {

    /** Most positions one check may test, so a typo in a region cannot run for minutes. */
    static final int MAX_POSITIONS = 4096;

    record Expectation(int x, int y, int z, String block) {
        String command() {
            return "execute if block " + x + " " + y + " " + z + " " + block;
        }
    }

    BlocksCheck {
        expectations = List.copyOf(expectations);
    }

    static BlocksCheck parse(String name, JsonObject json) {
        List<Expectation> out = new ArrayList<>();
        if (json.has("blocks")) {
            for (JsonElement e : json.getAsJsonArray("blocks")) {
                JsonObject o = e.getAsJsonObject();
                int[] pos = pos(o.getAsJsonArray("pos"));
                out.add(new Expectation(pos[0], pos[1], pos[2], Checks.require(o, "block")));
            }
        }
        if (json.has("regions")) {
            for (JsonElement e : json.getAsJsonArray("regions")) {
                JsonObject o = e.getAsJsonObject();
                int[] a = pos(o.getAsJsonArray("from"));
                int[] b = pos(o.getAsJsonArray("to"));
                String block = Checks.require(o, "block");
                for (int y = Math.min(a[1], b[1]); y <= Math.max(a[1], b[1]); y++) {
                    for (int z = Math.min(a[2], b[2]); z <= Math.max(a[2], b[2]); z++) {
                        for (int x = Math.min(a[0], b[0]); x <= Math.max(a[0], b[0]); x++) {
                            out.add(new Expectation(x, y, z, block));
                            if (out.size() > MAX_POSITIONS) {
                                throw new IllegalArgumentException("blocks check covers more than " + MAX_POSITIONS + " positions");
                            }
                        }
                    }
                }
            }
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException("blocks check needs \"blocks\" or \"regions\"");
        }
        return new BlocksCheck(name, out, 3);
    }

    private static int[] pos(JsonArray array) {
        if (array == null || array.size() != 3) {
            throw new IllegalArgumentException("a position is [x, y, z]");
        }
        return new int[] {array.get(0).getAsInt(), array.get(1).getAsInt(), array.get(2).getAsInt()};
    }

    @Override
    public boolean needsServer() {
        return true;
    }

    @Override
    public CheckResult run(CheckContext context) {
        List<String> failures = new ArrayList<>();
        int failed = 0;
        try {
            for (Expectation e : expectations) {
                String reply = RconCheck.stripFormatting(context.rcon().command(e.command()));
                if (!reply.startsWith("Test passed")) {
                    failed++;
                    if (failures.size() < maxFailuresShown) {
                        failures.add(e.x() + " " + e.y() + " " + e.z() + " is not " + e.block());
                    }
                }
            }
        } catch (IOException ex) {
            return CheckResult.fail(name, "RCON failed: " + ex.getMessage());
        }
        int total = expectations.size();
        return failed == 0
                ? CheckResult.pass(name, total + "/" + total + " blocks match")
                : CheckResult.fail(name, (total - failed) + "/" + total + " blocks match; " + String.join(", ", failures));
    }
}
