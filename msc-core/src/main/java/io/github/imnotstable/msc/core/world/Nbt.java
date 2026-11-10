package io.github.imnotstable.msc.core.world;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
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
    private Nbt() {
    }

    /** Read a gzipped NBT file; the root compound's name is dropped. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> read(InputStream stream) throws IOException {
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(stream))) {
            int type = in.readUnsignedByte();
            if (type != 10) {
                throw new IOException("NBT root is not a compound");
            }
            in.readUTF();
            return (Map<String, Object>) payload(in, type);
        }
    }

    private static Object payload(DataInputStream in, int type) throws IOException {
        switch (type) {
            case 1: return in.readByte();
            case 2: return in.readShort();
            case 3: return in.readInt();
            case 4: return in.readLong();
            case 5: return in.readFloat();
            case 6: return in.readDouble();
            case 7: {
                byte[] bytes = new byte[checked(in.readInt())];
                in.readFully(bytes);
                return bytes;
            }
            case 8: return in.readUTF();
            case 9: {
                int itemType = in.readUnsignedByte();
                int n = checked(in.readInt());
                List<Object> list = new ArrayList<>();
                for (int i = 0; i < n; i++) {
                    list.add(payload(in, itemType));
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
                    String name = in.readUTF();
                    map.put(name, payload(in, t));
                }
            }
            case 11: {
                int[] ints = new int[checked(in.readInt())];
                for (int i = 0; i < ints.length; i++) {
                    ints[i] = in.readInt();
                }
                return ints;
            }
            case 12: {
                long[] longs = new long[checked(in.readInt())];
                for (int i = 0; i < longs.length; i++) {
                    longs[i] = in.readLong();
                }
                return longs;
            }
            default:
                throw new IOException("unknown NBT tag " + type);
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

    private static void tag(DataOutputStream out, int type, String name) throws IOException {
        out.writeByte(type);
        byte[] bytes = name.getBytes(StandardCharsets.UTF_8);
        out.writeShort(bytes.length);
        out.write(bytes);
    }
}
