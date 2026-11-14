package io.github.imnotstable.msc.core.building;

import io.github.imnotstable.msc.core.agent.AgentHub;
import io.github.imnotstable.msc.core.tool.ToolException;
import io.github.imnotstable.msc.core.world.Coords;
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
 * build_undo id.
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

    /** {@code beforeWrite}: the world backup a big build takes first (ADR 0041). */
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
        Canvas canvas = new Canvas(bounds, r -> world.readAll(r, MAX_VOLUME, tool));
        for (Step s : steps) {
            s.draw(canvas);
        }
        Region used = canvas.used();
        if (used == null) {
            return "nothing to place: every step was empty or masked out by replace=";
        }
        String counts = counts(canvas.counts());
        if (dryRun) {
            String check = hub.current() == null ? "block names not checked: the server is not running"
                    : check(canvas, used) == null ? "block names: all valid" : null;
            return "dry run, nothing placed: " + canvas.placed() + " blocks in " + Coords.show(used) + " size "
                    + used.sizeX() + "x" + used.sizeY() + "x" + used.sizeZ() + "\n" + counts + "\n" + check;
        }
        check(canvas, used);
        String backup = beforeWrite.before(canvas.placed());
        List<String> ids = new ArrayList<>();
        long changed = 0;
        try {
            for (Region part : Coords.split(used)) {
                BlockGrid grid = canvas.grid(part);
                if (grid != null) {
                    Ops.EditResult r = hub.call(Ops.WORLD_WRITE, new Ops.WriteParams(grid, physics));
                    ids.add(r.editId());
                    changed += r.changed();
                }
            }
        } catch (RuntimeException | InterruptedException e) {
            if (e instanceof OpException op) {
                PartialEdit.editId(op.detail()).ifPresent(ids::add);
            }
            throw cleanUp(ids, e);
        }
        String id = world.remember(ids);
        // One kind of block, or nothing left unchanged: the extra numbers say nothing new.
        return "edit " + id + ": " + changed + " blocks changed"
                + (changed == canvas.placed() ? "" : " (" + canvas.placed() + " placed)") + ", region "
                + Coords.show(used) + (canvas.counts().size() > 1 ? "\n" + counts : "")
                + (backup.isEmpty() ? "" : "\n" + backup);
    }

    /**
     * Undo the parts a failed build changed, newest first, and return the error to throw. The agent's undo id in
     * {@code failure} is stripped: build_undo does not know it. Parts whose undo fails go under a core id that the
     * error names, so no block the build changed is left without a build_undo id (#292).
     */
    private RuntimeException cleanUp(List<String> ids, Exception failure) throws InterruptedException {
        List<String> kept = new ArrayList<>();
        String why = null;
        boolean interrupted = false;
        for (int i = ids.size() - 1; i >= 0; i--) {
            try {
                hub.call(Ops.WORLD_UNDO, new Ops.UndoParams(ids.get(i)));
            } catch (OpException e) {
                if (!e.code().equals(ErrorCodes.NOT_FOUND)) {
                    kept.addFirst(ids.get(i));
                    why = why == null ? e.detail() : why;
                }
            } catch (RuntimeException | InterruptedException e) {
                interrupted |= e instanceof InterruptedException;
                kept.addFirst(ids.get(i));
                why = why == null ? String.valueOf(e.getMessage()) : why;
            }
        }
        if (interrupted || failure instanceof InterruptedException && !kept.isEmpty()) {
            Thread.currentThread().interrupt();
        }
        String reason = failure instanceof OpException op ? "agent answered " + op.code() + ": " + PartialEdit.strip(op.detail())
                : failure instanceof InterruptedException ? "interrupted" : String.valueOf(failure.getMessage());
        if (!kept.isEmpty()) {
            return new ToolException(reason + "; undoing it failed (" + why + "), build_undo edit="
                    + world.remember(kept) + " removes the rest");
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
    private String check(Canvas canvas, Region used) throws InterruptedException {
        List<String> palette = canvas.palette();
        Region column = Region.of(used.world(), used.min().x(), used.min().y(), used.min().z(), used.min().x(),
                used.max().y(), used.min().z());
        try {
            probe(column, palette);
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
                probe(one, List.of(state));
            } catch (OpException e) {
                bad.add(e.detail());
            }
        }
        throw new ToolException("nothing placed; " + bad.size() + (bad.size() == 1 ? " block is" : " blocks are")
                + " not valid:\n" + String.join("\n", bad));
    }

    private void probe(Region region, List<String> palette) throws InterruptedException {
        int[] none = new int[Math.toIntExact(region.volume())];
        Arrays.fill(none, -1);
        hub.call(Ops.WORLD_WRITE, new Ops.WriteParams(new BlockGrid(region, palette, none), false));
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
