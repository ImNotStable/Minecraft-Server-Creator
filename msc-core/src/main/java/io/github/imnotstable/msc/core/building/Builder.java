package io.github.imnotstable.msc.core.building;

import io.github.imnotstable.msc.core.agent.AgentHub;
import io.github.imnotstable.msc.core.tool.ToolException;
import io.github.imnotstable.msc.core.world.Coords;
import io.github.imnotstable.msc.core.world.Footprint;
import io.github.imnotstable.msc.core.world.WorldService;
import io.github.imnotstable.msc.protocol.message.ErrorCodes;
import io.github.imnotstable.msc.protocol.op.Ops;
import io.github.imnotstable.msc.protocol.op.PartialEdit;
import io.github.imnotstable.msc.protocol.transport.OpException;
import io.github.imnotstable.msc.protocol.world.BlockGrid;
import io.github.imnotstable.msc.protocol.world.Region;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

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
        if (bounds.volume() > MAX_VOLUME) {
            throw new ToolException("the steps span " + Coords.show(bounds) + " = " + bounds.volume() + " blocks; "
                    + tool + " covers at most " + MAX_VOLUME + ". Split it into calls over smaller areas.");
        }
        if (dryRun) {
            Canvas canvas = draw(bounds, steps, tool);
            Region used = canvas.used();
            if (used == null) {
                return NOTHING;
            }
            String check = hub.current() == null ? "block names not checked: the server is not running"
                    : check(world.run(), canvas, used) == null ? "block names: all valid" : null;
            return "dry run, nothing placed: " + canvas.placed() + " blocks in " + Coords.show(used) + " size "
                    + used.sizeX() + "x" + used.sizeY() + "x" + used.sizeZ() + "\n" + counts(canvas.counts()) + "\n"
                    + check;
        }
        // From the first read of the world (replace=, copy) to the new id, no other edit is written or undone, so the
        // world is what the edits give applied in id order (ADR 0049, #704).
        Region box = bounds;
        return world.oneAtATime(() -> build(box, steps, physics, tool));
    }

    private static final String NOTHING = "nothing to place: every step was empty or masked out by replace=";

    /** Draw the steps, reading the world where a step needs it. */
    private Canvas draw(Region bounds, List<Step> steps, String tool) throws InterruptedException {
        Canvas canvas = new Canvas(bounds, r -> world.readAll(r, MAX_VOLUME, tool));
        for (Step s : steps) {
            s.draw(canvas);
        }
        return canvas;
    }

    private String build(Region bounds, List<Step> steps, boolean physics, String tool) throws InterruptedException {
        Canvas canvas = draw(bounds, steps, tool);
        Region used = canvas.used();
        if (used == null) {
            return NOTHING;
        }
        WorldService.Run run = world.run();
        check(run, canvas, used);
        String backup = beforeWrite.before(canvas.placed());
        Footprint where = canvas.footprint(used);
        List<String> ids = new ArrayList<>();
        long changed = 0;
        try {
            for (Region part : Coords.split(used)) {
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
        String counts = counts(canvas.counts());
        // One kind of block, or nothing left unchanged: the extra numbers say nothing new.
        return "edit " + world.remember(ids, where, run) + ": " + changed + " blocks changed"
                + (changed == canvas.placed() ? "" : " (" + canvas.placed() + " placed)") + ", region "
                + Coords.show(used) + (canvas.counts().size() > 1 ? "\n" + counts : "")
                + (backup.isEmpty() ? "" : "\n" + backup);
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
                throw new ToolException("nothing placed: " + e.detail());
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
