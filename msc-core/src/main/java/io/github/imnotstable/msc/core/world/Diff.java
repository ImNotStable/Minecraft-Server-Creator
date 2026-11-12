package io.github.imnotstable.msc.core.world;

import io.github.imnotstable.msc.protocol.world.BlockGrid;
import io.github.imnotstable.msc.protocol.world.BlockPos;
import io.github.imnotstable.msc.protocol.world.Region;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** What changed between a snapshot of a region and the region now, in a few lines. */
public final class Diff {
    /** Changes listed one by one before the rest are only counted. */
    static final int LISTED = 12;
    /** Kinds of change (stone -> air) named before the rest are only counted. */
    static final int KINDS = 10;

    private Diff() {
    }

    /**
     * @param before the snapshot, placed at the same corner as {@code now}
     * @param now    the world now, over the same box
     * @param name   what to call the snapshot, such as its file name
     */
    public static String compare(BlockGrid before, BlockGrid now, String name) {
        Region r = now.region();
        long changed = 0;
        int[] lo = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
        int[] hi = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        Map<String, Long> kinds = new LinkedHashMap<>();
        List<String> listed = new ArrayList<>();
        for (int y = r.min().y(); y <= r.max().y(); y++) {
            for (int z = r.min().z(); z <= r.max().z(); z++) {
                for (int x = r.min().x(); x <= r.max().x(); x++) {
                    String a = before.region().contains(x, y, z) ? before.stateAt(x, y, z) : null;
                    String b = now.stateAt(x, y, z);
                    if (a == null || b == null || same(a, b)) {
                        continue;
                    }
                    changed++;
                    lo[0] = Math.min(lo[0], x);
                    lo[1] = Math.min(lo[1], y);
                    lo[2] = Math.min(lo[2], z);
                    hi[0] = Math.max(hi[0], x);
                    hi[1] = Math.max(hi[1], y);
                    hi[2] = Math.max(hi[2], z);
                    String ia = BlockNames.brief(BlockNames.id(a));
                    String ib = BlockNames.brief(BlockNames.id(b));
                    String kind = ia.equals(ib) ? ia + " turned or changed state" : ia + " -> " + ib;
                    kinds.merge(kind, 1L, Long::sum);
                    if (listed.size() < LISTED) {
                        listed.add(Coords.show(new BlockPos(x, y, z)) + " " + BlockNames.brief(a) + " -> "
                                + BlockNames.brief(b));
                    }
                }
            }
        }
        StringBuilder out = new StringBuilder();
        Region b = before.region();
        if (b.sizeX() != r.sizeX() || b.sizeY() != r.sizeY() || b.sizeZ() != r.sizeZ()) {
            out.append("note: ").append(name).append(" is ").append(b.sizeX()).append('x').append(b.sizeY()).append('x')
                    .append(b.sizeZ()).append(", the region ").append(r.sizeX()).append('x').append(r.sizeY())
                    .append('x').append(r.sizeZ()).append("; compared where they overlap\n");
        }
        if (changed == 0) {
            return out.append("no change since ").append(name).toString();
        }
        out.append(changed).append(changed == 1 ? " block" : " blocks").append(" changed since ").append(name)
                .append(", in ").append(Coords.show(Region.of(r.world(), lo[0], lo[1], lo[2], hi[0], hi[1], hi[2])))
                .append('\n');
        List<Map.Entry<String, Long>> sorted = new ArrayList<>(kinds.entrySet());
        sorted.sort((p, q) -> Long.compare(q.getValue(), p.getValue()));
        out.append("changes:");
        for (int i = 0; i < Math.min(KINDS, sorted.size()); i++) {
            out.append(i == 0 ? " " : ", ").append(sorted.get(i).getKey()).append(' ').append(sorted.get(i).getValue());
        }
        if (sorted.size() > KINDS) {
            out.append(" (+").append(sorted.size() - KINDS).append(" more kinds)");
        }
        out.append('\n').append(String.join("\n", listed));
        if (changed > listed.size()) {
            out.append("\n[").append(changed - listed.size()).append(" more]");
        }
        return out.toString();
    }

    /** The same block: equal states, or two kinds of air. */
    private static boolean same(String a, String b) {
        return a.equals(b) || BlockNames.full(a).equals(BlockNames.full(b))
                || BlockNames.isAir(a) && BlockNames.isAir(b);
    }
}
