package io.github.imnotstable.msc.core.building;

import io.github.imnotstable.msc.core.agent.AgentHub;
import io.github.imnotstable.msc.core.tool.ToolException;
import io.github.imnotstable.msc.core.world.Coords;
import io.github.imnotstable.msc.core.world.Footprint;
import io.github.imnotstable.msc.core.world.OpenReviews;
import io.github.imnotstable.msc.core.world.WorldService;
import io.github.imnotstable.msc.protocol.Limits;
import io.github.imnotstable.msc.protocol.message.ErrorCodes;
import io.github.imnotstable.msc.protocol.op.Ops;
import io.github.imnotstable.msc.protocol.op.PartialEdit;
import io.github.imnotstable.msc.protocol.transport.OpException;
import io.github.imnotstable.msc.protocol.world.BlockGrid;
import io.github.imnotstable.msc.protocol.world.Region;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs steps as one edit: draw them all on a {@link Canvas}, check every block name with the agent,
 * then write the canvas in protocol-sized parts under a single undo id. If a part fails, the parts
 * already written are undone, and so are the blocks the failed part changed before it failed when the agent names
 * their undo id ({@link PartialEdit}), so a build is placed whole or not at all; what a failed undo leaves gets a
 * build_undo id while the server run lasts, and is named with its way back once it has ended. Everything after the
 * argument checks runs in one {@link WorldService#oneAtATime} and talks to one {@link WorldService.Run}.
 */
public final class Builder {
    /** Largest box one build may cover. */
    public static final long MAX_VOLUME = WorldService.MAX_BULK_VOLUME;

    /** Runs before a build's first block is written; returns a line for the result, or "". */
    @FunctionalInterface
    public interface BeforeWrite {
        String before(long blocks) throws InterruptedException;
    }

    private final AgentHub hub;
    private final WorldService world;
    private final BeforeWrite beforeWrite;

    public Builder(AgentHub hub, WorldService world) {
        this(hub, world, blocks -> "");
    }

    /** {@code beforeWrite}: the world backup an edit takes first (ADR 0041, 0069). */
    public Builder(AgentHub hub, WorldService world, BeforeWrite beforeWrite) {
        this.hub = hub;
        this.world = world;
        this.beforeWrite = beforeWrite;
    }

    public String run(String worldName, List<Step> steps, boolean physics, boolean dryRun, String tool)
            throws InterruptedException {
        Region bounds = null;
        for (Step s : steps) {
            Region b = s.bounds();
            bounds = bounds == null ? b : Region.of(worldName, Math.min(bounds.min().x(), b.min().x()),
                    Math.min(bounds.min().y(), b.min().y()), Math.min(bounds.min().z(), b.min().z()),
                    Math.max(bounds.max().x(), b.max().x()), Math.max(bounds.max().y(), b.max().y()),
                    Math.max(bounds.max().z(), b.max().z()));
        }
        if (bounds == null) {
            throw new ToolException("nothing to build");
        }
        String gate = dryRun ? null : world.reviews().gate(bounds);
        if (gate != null) {
            throw new ToolException(gate);
        }
        if (bounds.volume() > MAX_VOLUME) {
            throw new ToolException("the steps span " + Coords.show(bounds) + " = " + bounds.volume() + " blocks; "
                    + tool + " covers at most " + MAX_VOLUME + ". Split it into calls over smaller areas.");
        }
        if (dryRun) {
            Canvas canvas = draw(bounds, steps, tool);
            Region used = canvas.used();
            List<String> commands = canvas.commands();
            if (used == null) {
                return commands.isEmpty() ? NOTHING : "dry run, nothing run: no blocks, " + commands.size()
                        + " command" + (commands.size() == 1 ? "" : "s") + ", first: " + commands.getFirst();
            }
            String check = hub.current() == null ? "block names not checked: the server is not running"
                    : check(world.run(), canvas, used) == null ? "block names: all valid" : null;
            return "dry run, nothing placed: " + canvas.placed() + " blocks in " + Coords.show(used) + " size "
                    + used.sizeX() + "x" + used.sizeY() + "x" + used.sizeZ() + "\n" + counts(canvas.counts()) + "\n"
                    + check + (commands.isEmpty() ? "" : "\nthen " + commands.size() + " command"
                    + (commands.size() == 1 ? "" : "s"));
        }
        // From the first read of the world (replace=, copy) to the new id, no other edit is written or undone, so the
        // world is what the edits give applied in id order (ADR 0049, #704).
        Region box = bounds;
        return world.oneAtATime(() -> build(box, steps, physics, tool));
    }

    private static final String NOTHING = "nothing to place: every step was empty or masked out by replace= (light "
            + "finds no spot darker than level=; decorate, scatter and flora no open spot)";

    /** Draw the steps, reading the world where a step needs it. */
    private Canvas draw(Region bounds, List<Step> steps, String tool) throws InterruptedException {
        return drawJoined(bounds, steps, r -> world.readAll(r, MAX_VOLUME, tool));
    }

    /**
     * Draw the steps on a canvas one block wider than they reach, then join what they placed (ADR 0077): a door's top
     * or a bed's head may land just outside the steps' own bounds.
     */
    static Canvas drawJoined(Region bounds, List<Step> steps, Canvas.WorldReader reader) throws InterruptedException {
        Region grown = Region.of(bounds.world(), bounds.min().x() - 1, bounds.min().y(), bounds.min().z() - 1,
                bounds.max().x() + 1, bounds.max().y() + 1, bounds.max().z() + 1);
        Canvas canvas = new Canvas(grown, reader);
        for (Step s : steps) {
            s.draw(canvas);
        }
        Joins.apply(canvas);
        BlockRules.requireWater(canvas);
        return canvas;
    }

    private String build(Region bounds, List<Step> steps, boolean physics, String tool) throws InterruptedException {
        Canvas canvas = draw(bounds, steps, tool);
        Region used = canvas.used();
        List<String> commands = canvas.commands();
        if (used == null && commands.isEmpty()) {
            return NOTHING;
        }
        WorldService.Run run = world.run();
        if (used != null) {
            check(run, canvas, used);
        }
        String backup = beforeWrite.before(canvas.placed());
        Footprint where = used != null ? canvas.footprint(used)
                : new Footprint(Region.of(bounds.world(), bounds.min().x(), bounds.min().y(), bounds.min().z(),
                bounds.min().x(), bounds.min().y(), bounds.min().z()), new java.util.BitSet());
        List<String> ids = new ArrayList<>();
        long changed = 0;
        try {
            for (Region part : used == null ? List.<Region>of() : Coords.split(used)) {
                BlockGrid grid = canvas.grid(part);
                if (grid != null) {
                    Ops.EditResult r = world.call(run, Ops.WORLD_WRITE, new Ops.WriteParams(grid, physics));
                    ids.add(r.editId());
                    changed += r.changed();
                }
            }
        } catch (RuntimeException | InterruptedException e) {
            if (e instanceof OpException op) {
                PartialEdit.editId(op.detail()).ifPresent(ids::add);
            }
            throw cleanUp(run, ids, where, used, e);
        }
        String ran = commands.isEmpty() ? "" : "\n" + runCommands(run, commands, canvas.tag());
        boolean summons = commands.stream().anyMatch(c -> c.contains(" summon "));
        String id = world.remember(ids, where, run,
                summons ? List.of("kill @e[tag=" + canvas.tag() + "]") : List.of());
        if (used == null) {
            return "edit " + id + ": no blocks" + ran + (backup.isEmpty() ? "" : "\n" + backup);
        }
        String counts = counts(canvas.counts());
        if (changed == 0 && commands.isEmpty()) {
            // Every block was already what the batch places: nothing a review saw has moved, so a passed review stays
            // passed instead of asking for a re-review that can only say the same (ADR 0119).
            return "edit " + id + ": nothing changed: all " + canvas.placed() + " blocks in " + Coords.show(used)
                    + " already were what this batch places. Running it again does the same; change the steps, or "
                    + "move on" + (backup.isEmpty() ? "" : "\n" + backup)
                    + (world.reviews().count().isEmpty() ? "" : "\n" + world.reviews().count());
        }
        java.util.Set<Region> same = unchanged(world.reviews().touched(used));
        world.reviews().edited(used, (int) Math.min(changed, Integer.MAX_VALUE), same);
        world.ledger().edited(used, same);
        String drops = clearDrops(run, used);
        // One kind of block, or nothing left unchanged: the extra numbers say nothing new.
        return "edit " + id + ": " + changed + " blocks changed"
                + (changed == canvas.placed() ? "" : " (" + canvas.placed() + " placed)") + ", region "
                + Coords.show(used) + (canvas.counts().size() > 1 ? "\n" + counts : "") + ran
                + (backup.isEmpty() ? "" : "\n" + backup) + drops
                + (world.reviews().count().isEmpty() ? "" : "\n" + world.reviews().count());
    }

    /** Blocks read at most to check passed reviews after an edit; past it they reopen as before. */
    static final long CHECKED_BLOCKS = 2_000_000;

    /**
     * The passed regions among {@code touched} whose blocks are still those they passed with (ADR 0119): an edit's box
     * reaching over a build does not mean it changed it.
     */
    private java.util.Set<Region> unchanged(List<OpenReviews.Passed> touched) throws InterruptedException {
        java.util.Set<Region> same = new java.util.HashSet<>();
        long read = 0;
        for (OpenReviews.Passed p : touched) {
            read += p.region().volume();
            if (read > CHECKED_BLOCKS) {
                break;
            }
            try {
                BlockGrid grid = world.readAll(p.region(), WorldService.MAX_BULK_VOLUME, "build_batch");
                if (OpenReviews.print(grid, p.devFloor()) == p.print()) {
                    same.add(p.region());
                }
            } catch (RuntimeException e) {
                // Not read: the review reopens, as it did before the check.
            }
        }
        return same;
    }

    private static final Pattern KILLED = Pattern.compile("Killed (\\d+) entities");

    /**
     * Items the edit knocked loose (a torch without its wall, a plant on the wrong soil) lie around the build for five
     * minutes and show in renders: clear them from its box (ADR 0084). Best effort: a platform without the command
     * (Minestom) keeps them, and the build stands either way.
     */
    private String clearDrops(WorldService.Run run, Region used) {
        try {
            String command = dropsCommand(used, world.world(null));
            if (command == null) {
                return "";
            }
            Ops.CommandResult r = world.call(run, Ops.SERVER_COMMAND, new Ops.CommandParams(command));
            String out = r.output() == null ? "" : String.join(" ", r.output());
            Matcher m = KILLED.matcher(out);
            int n = m.find() ? Integer.parseInt(m.group(1)) : out.startsWith("Killed ") ? 1 : 0;
            return n == 0 ? "" : "\ncleared " + n + (n == 1 ? " dropped item" : " dropped items")
                    + " the edit knocked loose; give torches, plants and rails what holds them";
        } catch (RuntimeException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return "";
        }
    }

    /** The kill command for item entities in and just around {@code used}; null for a world it cannot name. */
    static String dropsCommand(Region used, String mainWorld) {
        String dimension = used.world().contains(":") ? used.world()
                : used.world().equals(mainWorld) ? "minecraft:overworld" : null;
        if (dimension == null) {
            return null;
        }
        return String.format(Locale.ROOT, "execute in %s run kill @e[type=item,x=%d,y=%d,z=%d,dx=%d,dy=%d,dz=%d]",
                dimension, used.min().x() - 1, used.min().y() - 1, used.min().z() - 1, used.sizeX() + 1,
                used.sizeY() + 1, used.sizeZ() + 1);
    }

    /**
     * Run the build's commands once its blocks are written (ADR 0082), and say how they went: entities carry the
     * build's tag so build_undo removes them; biome and feature changes are not undone.
     */
    private String runCommands(WorldService.Run run, List<String> commands, String tag) throws InterruptedException {
        List<String> failed = new ArrayList<>();
        for (String command : commands) {
            try {
                Ops.CommandResult r = world.call(run, Ops.SERVER_COMMAND, new Ops.CommandParams(command));
                if (!r.success()) {
                    failed.add(Limits.shorten(command, 60) + ": " + (r.output().isEmpty() ? "failed"
                            : Limits.shorten(r.output().getFirst(), 120)));
                }
            } catch (OpException e) {
                failed.add(Limits.shorten(command, 60) + ": " + Limits.shorten(e.detail(), 120));
            }
        }
        StringBuilder out = new StringBuilder(commands.size() - failed.size() + " of " + commands.size() + " command"
                + (commands.size() == 1 ? "" : "s") + " ran");
        if (commands.stream().anyMatch(c -> c.contains(" summon "))) {
            out.append("; entities tagged ").append(tag).append(" (build_undo removes them)");
        }
        if (commands.stream().anyMatch(c -> c.contains(" fillbiome ") || c.contains(" place "))) {
            out.append("; biome and feature changes are not undone");
        }
        for (int i = 0; i < Math.min(5, failed.size()); i++) {
            out.append("\nfailed: ").append(failed.get(i));
        }
        if (failed.size() > 5) {
            out.append("\n(+").append(failed.size() - 5).append(" more failed)");
        }
        return out.toString();
    }

    /**
     * Undo the parts a failed build changed, newest first, on the run that wrote them, and return the error to throw.
     * The agent's undo id in {@code failure} is stripped: build_undo does not know it. Parts whose undo fails go under
     * a core id that the error names, so no block the build changed is left without a build_undo id (#292), while the
     * run lasts; once it has ended no id can undo them (#701, #702), and no count of what stays is true (#708), so the
     * error is {@link WorldService#cutOff} for {@code used}. {@code where}: the whole build's positions, which hold
     * those parts' (ADR 0049).
     */
    private RuntimeException cleanUp(WorldService.Run run, List<String> ids, Footprint where, Region used,
                                     Exception failure) throws InterruptedException {
        List<String> kept = new ArrayList<>();
        String why = null;
        boolean interrupted = false;
        for (int i = ids.size() - 1; i >= 0; i--) {
            try {
                world.call(run, Ops.WORLD_UNDO, new Ops.UndoParams(ids.get(i)));
                continue;
            } catch (OpException e) {
                if (e.code().equals(ErrorCodes.NOT_FOUND)) {
                    continue;
                }
                why = why == null ? e.detail() : why;
            } catch (RuntimeException | InterruptedException e) {
                interrupted |= e instanceof InterruptedException;
                why = why == null ? String.valueOf(e.getMessage()) : why;
            }
            kept.addFirst(ids.get(i));
        }
        // Once the run has ended, no count is true: the part in flight may be half written, and what stays is what the
        // server saved (#708). Also with no part finished: the first one may have written some blocks.
        boolean ended = world.ended(run);
        if (interrupted || failure instanceof InterruptedException && (ended || !kept.isEmpty())) {
            Thread.currentThread().interrupt();
        }
        if (ended) {
            return new ToolException(WorldService.cutOff("the build", used));
        }
        String reason = failure instanceof OpException op ? "agent answered " + op.code() + ": " + PartialEdit.strip(op.detail())
                : failure instanceof InterruptedException ? "interrupted" : String.valueOf(failure.getMessage());
        if (!kept.isEmpty()) {
            return new ToolException(reason + "; undoing it failed (" + why + "), build_undo edit="
                    + world.remember(kept, where, run) + " removes the rest");
        }
        if (failure instanceof OpException op) {
            return new OpException(op.code(), PartialEdit.strip(op.detail()));
        }
        if (failure instanceof InterruptedException ie) {
            throw ie;
        }
        return (RuntimeException) failure;
    }

    /**
     * Ask the agent to parse every block in the build without placing any: an all-unchanged write of
     * one column spanning the build's height, carrying the full palette. On a bad name, probe each
     * block alone so the error lists every bad one. Returns null when all is well.
     */
    private String check(WorldService.Run run, Canvas canvas, Region used) throws InterruptedException {
        List<String> palette = canvas.palette();
        Region column = Region.of(used.world(), used.min().x(), used.min().y(), used.min().z(), used.min().x(),
                used.max().y(), used.min().z());
        try {
            probe(run, column, palette);
            return null;
        } catch (OpException e) {
            if (!e.code().equals(ErrorCodes.INVALID_PARAMS) || palette.size() == 1 || e.detail().contains("build height")) {
                throw new ToolException("nothing placed: " + e.detail() + (e.detail().contains("build height")
                        ? "; a step reaches past it (a river's or path's depth= and banks dig below its points, a "
                        + "dome or sphere's radius): move it up or down, or make it smaller" : ""));
            }
        }
        Region one = Region.of(used.world(), used.min().x(), used.min().y(), used.min().z(), used.min().x(),
                used.min().y(), used.min().z());
        List<String> bad = new ArrayList<>();
        for (String state : palette) {
            try {
                probe(run, one, List.of(state));
            } catch (OpException e) {
                bad.add(e.detail());
            }
        }
        throw new ToolException("nothing placed; " + bad.size() + (bad.size() == 1 ? " block is" : " blocks are")
                + " not valid:\n" + String.join("\n", bad));
    }

    private void probe(WorldService.Run run, Region region, List<String> palette) throws InterruptedException {
        int[] none = new int[Math.toIntExact(region.volume())];
        Arrays.fill(none, -1);
        world.call(run, Ops.WORLD_WRITE, new Ops.WriteParams(new BlockGrid(region, palette, none), false));
    }

    private static String counts(Map<String, Long> counts) {
        StringBuilder out = new StringBuilder("blocks:");
        int shown = 0;
        for (Map.Entry<String, Long> e : counts.entrySet()) {
            if (shown == 12) {
                out.append(" (+").append(counts.size() - shown).append(" more kinds)");
                break;
            }
            out.append(shown == 0 ? " " : ", ").append(e.getKey()).append(' ').append(e.getValue());
            shown++;
        }
        return out.toString();
    }
}
