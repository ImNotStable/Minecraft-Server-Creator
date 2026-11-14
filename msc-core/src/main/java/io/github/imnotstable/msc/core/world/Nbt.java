package io.github.imnotstable.msc.core.world;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Minimal NBT for schematics. Values map to Java types: Byte, Short, Integer, Long, Float, Double,
 * byte[], String, List, Map (compound), int[], long[].
 */
final class Nbt {
    /** Deepest nesting of lists and compounds read, as in Minecraft: a deeper file would overflow the stack. */
    static final int MAX_DEPTH = 512;

    /**
     * Rough heap a read may build, in bytes: every tag costs {@link #TAG_COST} plus its array or string data. Gzip
     * shrinks a run of empty tags about 1000:1, so without a cap a small file could fill memory. A schematic of the
     * largest region the tools accept (palette, block data and some block entities) needs far less.
     */
    static final long MAX_HEAP = 128L * 1024 * 1024;

    /** Estimated heap of one tag (boxed value, map or list and its slot in the parent). */
    static final int TAG_COST = 64;

    private Nbt() {
    }

    /** Read a gzipped NBT file; the root compound's name is dropped. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> read(InputStream stream) throws IOException {
        return read(stream, MAX_HEAP);
    }

    /** {@link #read(InputStream)} with another heap budget. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> read(InputStream stream, long maxHeap) throws IOException {
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(stream))) {
            int type = in.readUnsignedByte();
            if (type != 10) {
                throw new IOException("NBT root is not a compound");
            }
            in.readUTF();
            return (Map<String, Object>) new Reader(in, maxHeap).payload(type, 0);
        }
    }

    /** One read: the stream and what is left of the heap budget. */
    private static final class Reader {
        private final DataInputStream in;
        private final long maxHeap;
        private long left;

        Reader(DataInputStream in, long maxHeap) {
            this.in = in;
            this.maxHeap = maxHeap;
            this.left = maxHeap;
        }

        /** Take {@code bytes} of estimated heap from the budget before building them. */
        private void charge(long bytes) throws IOException {
            left -= bytes;
            if (left < 0) {
                throw new IOException("NBT would take more than " + maxHeap / (1024 * 1024)
                        + " MB of memory; the file is not a schematic this tool reads");
            }
        }

        private String utf() throws IOException {
            String s = in.readUTF();
            charge(2L * s.length());
            return s;
        }

        private Object payload(int type, int depth) throws IOException {
            if (depth > MAX_DEPTH) {
                throw new IOException("NBT nested deeper than " + MAX_DEPTH);
            }
            charge(TAG_COST);
            switch (type) {
                case 1: return in.readByte();
                case 2: return in.readShort();
                case 3: return in.readInt();
                case 4: return in.readLong();
                case 5: return in.readFloat();
                case 6: return in.readDouble();
                case 7: {
                    int n = checked(in.readInt());
                    charge(n);
                    byte[] bytes = new byte[n];
                    in.readFully(bytes);
                    return bytes;
                }
                case 8: return utf();
                case 9: {
                    int itemType = in.readUnsignedByte();
                    int n = checked(in.readInt());
                    List<Object> list = new ArrayList<>();
                    for (int i = 0; i < n; i++) {
                        list.add(payload(itemType, depth + 1));
                    }
                    return list;
                }
                case 10: {
                    Map<String, Object> map = new LinkedHashMap<>();
                    while (true) {
                        int t = in.readUnsignedByte();
                        if (t == 0) {
                            return map;
                        }
                        String name = utf();
                        map.put(name, payload(t, depth + 1));
                    }
                }
                case 11: {
                    int n = checked(in.readInt());
                    charge(4L * n);
                    int[] ints = new int[n];
                    for (int i = 0; i < n; i++) {
                        ints[i] = in.readInt();
                    }
                    return ints;
                }
                case 12: {
                    int n = checked(in.readInt());
                    charge(8L * n);
                    long[] longs = new long[n];
                    for (int i = 0; i < n; i++) {
                        longs[i] = in.readLong();
                    }
                    return longs;
                }
                default:
                    throw new IOException("unknown NBT tag " + type);
            }
        }
    }

    private static int checked(int length) throws IOException {
        if (length < 0 || length > 64 * 1024 * 1024) {
            throw new IOException("NBT length out of range: " + length);
        }
        return length;
    }

    /** Write a gzipped NBT file with the given root name. Lists are written as empty compound lists. */
    static void write(OutputStream stream, String rootName, Map<String, Object> root) throws IOException {
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(stream))) {
            out.writeByte(10);
            out.writeUTF(rootName);
            compound(out, root);
        }
    }

    @SuppressWarnings("unchecked")
    private static void compound(DataOutputStream out, Map<String, Object> map) throws IOException {
        for (Map.Entry<String, Object> e : map.entrySet()) {
            Object v = e.getValue();
            if (v instanceof Short s) {
                tag(out, 2, e.getKey());
                out.writeShort(s);
            } else if (v instanceof Integer i) {
                tag(out, 3, e.getKey());
                out.writeInt(i);
            } else if (v instanceof byte[] b) {
                tag(out, 7, e.getKey());
                out.writeInt(b.length);
                out.write(b);
            } else if (v instanceof String s) {
                tag(out, 8, e.getKey());
                out.writeUTF(s);
            } else if (v instanceof List<?> l && l.isEmpty()) {
                tag(out, 9, e.getKey());
                out.writeByte(10);
                out.writeInt(0);
            } else if (v instanceof Map<?, ?> m) {
                tag(out, 10, e.getKey());
                compound(out, (Map<String, Object>) m);
            } else if (v instanceof int[] ints) {
                tag(out, 11, e.getKey());
                out.writeInt(ints.length);
                for (int i : ints) {
                    out.writeInt(i);
                }
            } else {
                throw new IOException("cannot write NBT value of " + v.getClass());
            }
        }
        out.writeByte(0);
    }

    /** Names are modified UTF-8 like every NBT string, so {@link DataInputStream#readUTF} reads them back. */
    private static void tag(DataOutputStream out, int type, String name) throws IOException {
        out.writeByte(type);
        out.writeUTF(name);
    }
}
